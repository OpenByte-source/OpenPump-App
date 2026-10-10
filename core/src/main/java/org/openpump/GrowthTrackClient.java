package org.openpump;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Serialized OAuth and upload state machine. Transport and encrypted storage are Android seams. */
public final class GrowthTrackClient {
    public interface Store { JSONObject read() throws IOException; void write(JSONObject state) throws IOException; }
    public interface Clock { long now(); }
    public interface Transport { Response request(String method, String path, Map<String, String> headers, String body) throws IOException; }
    public static final class Response {
        public final int status;
        public final String body, retryAfter;
        public Response(int status, String body) { this(status, body, null); }
        public Response(int status, String body, String retryAfter) { this.status = status; this.body = body; this.retryAfter = retryAfter; }
    }
    public static final class Problem extends IOException {
        public Problem(String message) { super(message); }
    }
    public static final class Snapshot {
        public final boolean connected, routines, pending, paused;
        public final int queued;
        public final long retryAt;
        public final String message;
        public final String connectionId;
        Snapshot(boolean connected, boolean routines, boolean pending, int queued, long retryAt, String message, String connectionId, boolean paused) {
            this.connected = connected; this.routines = routines; this.pending = pending;
            this.queued = queued; this.retryAt = retryAt; this.message = message;
            this.connectionId = connectionId; this.paused = paused;
        }
    }
    private final Store store;
    private final Transport transport;
    private final Clock clock;
    private JSONObject state;
    private volatile boolean cancelled;
    private boolean storageBroken;

    public GrowthTrackClient(Store store, Transport transport, Clock clock) throws IOException {
        this.store = store; this.transport = transport; this.clock = clock;
        state = store.read();
        if (state == null) state = new JSONObject();
        if (state.optInt("version", 1) != 1) throw new Problem("This connection storage needs a newer OpenPump version");
        if (state.optBoolean("refreshing") || state.optBoolean("exchanging")) {
            // A rotating token may have been consumed before the process died. Never replay it.
            clearAuthority("Connection interrupted while exchanging credentials. Connect again."); save();
        }
        if (state.optJSONArray("approved") == null) put("approved", new JSONArray());
        if (state.optJSONObject("receipts") == null) put("receipts", new JSONObject());
        if (state.optJSONObject("tokens") != null && state.optString("connection_id").isEmpty()) {
            clearAuthority("This older connection needs fresh browser approval."); save();
        }
        if (state.optJSONObject("tokens") != null && !state.optBoolean("automatic_sync")) {
            clearAuthority("Connect again to enable automatic transfer of every new session."); save();
        }
        if (approved().length() > 0 && !state.optString("connection_id").equals(state.optString("approved_connection_id"))) {
            put("approved", new JSONArray()); put("message", "The account approval changed. Pending sessions for the previous account were discarded."); save();
        }
    }
    public synchronized Snapshot snapshot() {
        JSONObject t = state.optJSONObject("tokens");
        return new Snapshot(t != null && !storageBroken, t != null && !storageBroken && hasScope(t.optString("scope"), GrowthTrackProtocol.READ),
            state.optJSONObject("pending") != null, approved().length(), state.optLong("retry_at", 0), state.optString("message", "Not connected"), state.optString("connection_id"), state.optBoolean("sync_paused"));
    }
    public synchronized String receipt(String id) { return state.optJSONObject("receipts").optString(id, ""); }
    public void cancelRequests() { cancelled = true; }
    private void checkCancelled() throws Problem { if (storageBroken) throw new Problem("Secure storage failed. Disconnect/reset this connection before continuing."); if (cancelled) throw new Problem("Sending stopped. An in-flight request may already have arrived; stable IDs make a later retry safe."); }

    /** Changing account invalidates every old local approval; only future completions belong to the new grant. */
    public synchronized String begin(boolean routines) throws IOException {
        if (storageBroken) throw new Problem("Secure storage failed. Disconnect/reset this connection before continuing.");
        cancelled = false;
        clearAuthority("Waiting for GrowthTrack approval in your browser");
        put("receipts", new JSONObject());
        put("pending", pendingJson(GrowthTrackProtocol.pending(routines, clock.now()))); save();
        try { return GrowthTrackProtocol.Pending.from(state.getJSONObject("pending")).authorizeUrl(); }
        catch (JSONException e) { throw new Problem("Could not create the connection request"); }
    }
    public synchronized void cancelAuthorization() throws IOException {
        state.remove("pending"); put("message", "Connection cancelled. No sessions were sent."); save();
    }
    public synchronized void callback(String uri) throws IOException {
        GrowthTrackProtocol.Pending pending;
        GrowthTrackProtocol.Callback callback;
        try {
            pending = state.optJSONObject("pending") == null ? null : GrowthTrackProtocol.Pending.from(state.getJSONObject("pending"));
            callback = GrowthTrackProtocol.callback(uri, pending, clock.now());
        } catch (JSONException | IllegalArgumentException e) { throw new Problem(e.getMessage()); }
        checkCancelled();
        state.remove("pending");
        if (callback.error != null) { put("message", "access_denied".equals(callback.error) ? "GrowthTrack approval was declined. No sessions were sent." : "GrowthTrack could not approve this connection. Connect again."); save(); return; }
        put("exchanging", true); save();
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("grant_type", "authorization_code"); form.put("code", callback.code);
        form.put("code_verifier", pending.verifier); form.put("client_id", GrowthTrackProtocol.CLIENT_ID); form.put("redirect_uri", GrowthTrackProtocol.REDIRECT);
        try {
            Response response = tokenRequest(form); checkCancelled();
            if (response.status != 200) throw new Problem(tokenError(response));
            put("tokens", tokens(response.body, pending.scope)); put("exchanging", false);
            put("connection_id", pending.state); put("automatic_sync", true); put("sync_paused", false);
            put("message", "Connected to the GrowthTrack account you approved in the browser. Every new real finished session will sync automatically. Earlier history stays on this device."); save();
        } catch (IOException e) { clearAuthority("Could not complete the connection. Connect again; the authorization code will not be replayed."); save(); throw e; }
    }
    public synchronized void disconnect() throws IOException {
        cancelled = true; clearAuthority("Disconnected on this device. Remove OpenPump in GrowthTrack Connected apps to revoke its server approval.");
        put("receipts", new JSONObject()); save();
    }
    public synchronized void cancelApproved() throws IOException {
        put("approved", new JSONArray()); put("retry_at", 0); put("attempts", 0);
        put("message", "Sending stopped. Sessions already received by GrowthTrack remain there."); save();
    }

    /** Completion consent is the browser grant captured when the record was filed.
     * Persisted record markers recover the tiny filing/queue crash window, without
     * admitting disconnected history or records belonging to another account. */
    public synchronized boolean recordCompleted(Model.Sess session, AsRun run, Model model, String completionConnectionId) throws IOException {
        if (!snapshot().connected || completionConnectionId == null || completionConnectionId.isEmpty()
                || !completionConnectionId.equals(state.optString("connection_id"))) return false;
        checkCancelled();
        String id;
        try { id = GrowthTrackProtocol.stableId(session == null ? "" : session.id); }
        catch (IllegalArgumentException e) { put("message", "Not synced: a finished record has no stable identifier."); save(); return false; }
        if (!receipt(id).isEmpty() || ids(approved()).contains(id)) return false;
        JSONObject payload;
        try {
            payload = GrowthTrackSession.draft(session, run, model).payload(); validateSession(payload);
            if (envelope(new JSONArray().put(payload)).getBytes(StandardCharsets.UTF_8).length > GrowthTrackProtocol.MAX_BYTES)
                throw new IllegalArgumentException("The recording exceeds GrowthTrack's request size limit");
        } catch (IllegalArgumentException | JSONException e) {
            receiptPut(id, "Not synced: " + safeReason(e.getMessage() == null ? "Invalid recording" : e.getMessage()));
            put("message", "A finished recording cannot be sent. See session sync status for the reason."); save(); return false;
        }
        approved().put(payload); put("approved_connection_id", completionConnectionId);
        put("message", approved().length() + " sessions waiting for automatic sync"); save(); return true;
    }
    /** Read-only scheduling decision: a paused permission error never loops in background. */
    public synchronized long automaticRetryAt() {
        Snapshot s = snapshot(); return s.connected && s.queued > 0 && !s.paused ? Math.max(clock.now(), s.retryAt) : -1;
    }
    public synchronized void backgroundUnavailable() throws IOException {
        put("message", "Background sync could not be scheduled. Pending sessions are secure; reopen OpenPump or tap Retry to resume."); save();
    }
    /** Called on the serial native worker after a fresh transport generation is entered. */
    public synchronized void prepareAutomaticSync() throws IOException {
        if (storageBroken) throw new Problem("Secure storage failed. Disconnect/reset before continuing.");
        cancelled = false;
    }
    /** Starts no OAuth and scans no unmarked history. Same-grant durable pending work only. */
    public synchronized void syncPending() throws IOException {
        if (automaticRetryAt() < 0 || clock.now() < state.optLong("retry_at", 0)) return;
        cancelled = false; sendApproved();
    }

    /** A detached, allowlisted snapshot of exactly the records the user just reviewed. */
    public synchronized void approve(List<JSONObject> sessions) throws IOException {
        approve(sessions, state.optString("connection_id"));
    }
    /** The review must still refer to the same browser grant, including across relinking. */
    public synchronized void approve(List<JSONObject> sessions, String reviewedConnectionId) throws IOException {
        checkLinked(GrowthTrackProtocol.WRITE); cancelled = false;
        if (reviewedConnectionId == null || reviewedConnectionId.isEmpty() || !reviewedConnectionId.equals(state.optString("connection_id")))
            throw new Problem("The GrowthTrack account approval changed. Review these sessions again before sending.");
        if (sessions == null || sessions.isEmpty()) throw new Problem("Select at least one session");
        if (approved().length() != 0) throw new Problem("Retry or cancel the existing approved send first");
        JSONArray queue = new JSONArray(); Set<String> ids = new HashSet<String>();
        try {
            for (JSONObject s : sessions) {
                validateSession(s);
                String id = s.getString("external_session_id");
                if (!ids.add(id)) throw new Problem("The selection contains a duplicate session");
                if (envelope(new JSONArray().put(s)).getBytes(StandardCharsets.UTF_8).length > GrowthTrackProtocol.MAX_BYTES)
                    throw new Problem("A selected session exceeds GrowthTrack's request size limit");
                queue.put(new JSONObject(s.toString()));
            }
        } catch (JSONException | IllegalArgumentException e) { throw new Problem("The selected session payload is invalid"); }
        put("approved", queue); put("attempts", 0); put("retry_at", 0); put("batch_limit", GrowthTrackProtocol.BATCH_SIZE);
        put("approved_connection_id", reviewedConnectionId);
        put("message", queue.length() + " reviewed sessions approved for sending"); save();
    }
    /** Retry uses the same durable, account-bound queue; startup and scheduled jobs call syncPending(). */
    public synchronized void resumeApproved() throws IOException { put("sync_paused", false); cancelled = false; sendApproved(); }
    public synchronized void sendApproved() throws IOException {
        checkCancelled(); checkLinked(GrowthTrackProtocol.WRITE);
        if (approved().length() > 0 && !state.optString("connection_id").equals(state.optString("approved_connection_id")))
            throw new Problem("The approved send belongs to an earlier connection. Cancel it and review your sessions again.");
        if (clock.now() < state.optLong("retry_at", 0)) throw new Problem("Please wait for the retry delay before trying again");
        while (approved().length() > 0) {
            checkCancelled();
            JSONArray batch = batch(); String body = envelope(batch);
            Response response;
            try { response = authorized("POST", "/partner-ingest", body, GrowthTrackProtocol.WRITE); }
            catch (Problem e) { throw e; }
            catch (IOException e) { retry(null); throw new Problem("Offline or network error. Your sessions are saved for automatic retry."); }
            checkCancelled();
            if (response.status == 413 && batch.length() > 1) {
                put("batch_limit", Math.max(1, batch.length() / 2)); save(); continue;
            }
            if (response.status == 408 || response.status == 429 || response.status >= 500) {
                retry(response.retryAfter); throw new Problem("GrowthTrack is unavailable or busy. Your sessions are saved for automatic retry.");
            }
            if (response.status != 200) {
                if (response.status == 400 || response.status == 413 || response.status == 422) {
                    for (int i = 0; i < batch.length(); i++) receiptPut(batch.optJSONObject(i).optString("external_session_id"), "Needs review: GrowthTrack rejected the request (" + response.status + ")");
                    removeIds(ids(batch)); put("message", "GrowthTrack rejected the request. Review the selected sessions before sending again."); save();
                }
                if (response.status != 400 && response.status != 413 && response.status != 422) {
                    put("sync_paused", true); put("message", "Sync paused: GrowthTrack rejected the request (" + response.status + "). Check the connection."); save();
                }
                throw new Problem("GrowthTrack rejected the request (" + response.status + "). See sync status.");
            }
            try { applyResults(batch, new JSONObject(response.body)); }
            catch (JSONException | IllegalArgumentException e) { retry(null); throw new Problem("GrowthTrack returned an incomplete result. The approved batch is kept; retrying is safe."); }
            if (state.optLong("retry_at", 0) > clock.now()) return;
        }
    }

    public synchronized JSONObject routines(String id) throws IOException {
        cancelled = false;
        if (id != null && !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new Problem("Invalid GrowthTrack routine ID");
        Response r = authorized("GET", "/partner-routines" + (id == null ? "" : "?id=" + id), null, GrowthTrackProtocol.READ);
        checkCancelled();
        if (r.status == 404) throw new Problem("This routine is no longer available. Refresh the library.");
        if (r.status != 200) throw new Problem("Could not read the GrowthTrack library (" + r.status + "). Try again later.");
        try {
            JSONObject o = new JSONObject(r.body);
            if (!"gt-routine".equals(o.getString("format")) || o.getInt("schema_version") != 1)
                throw new Problem("This GrowthTrack routine format is not supported");
            if (id == null) { if (o.getJSONArray("routines").length() > 1000) throw new JSONException("Too many routines"); }
            else { JSONObject routine = o.getJSONObject("routine"); if (!id.equals(routine.getString("external_id")) || routine.getJSONArray("steps").length() > 1000) throw new JSONException("Invalid routine"); }
            return o;
        } catch (JSONException e) { throw new Problem("GrowthTrack returned an invalid routine response"); }
    }

    private Response authorized(String method, String path, String body, String scope) throws IOException {
        checkLinked(scope);
        JSONObject t = state.optJSONObject("tokens");
        boolean refreshed = false;
        if (clock.now() + 30_000 >= t.optLong("expires_at")) { refresh(); refreshed = true; }
        Response r = authenticatedRequest(method, path, body);
        if (r.status == 401 && !refreshed) { refresh(); r = authenticatedRequest(method, path, body); }
        if (r.status == 401 || r.status == 410) {
            clearAuthority("GrowthTrack access expired or was removed. Automatic sync stopped; connect again."); save();
            throw new Problem("GrowthTrack access expired or was removed. Connect again.");
        }
        if (r.status == 403) { put("sync_paused", true); put("message", "Sync paused: GrowthTrack has not granted " + scope + ". Reconnect to restore access."); save(); throw new Problem(state.optString("message")); }
        return r;
    }
    private Response authenticatedRequest(String method, String path, String body) throws IOException {
        checkCancelled(); JSONObject t = state.optJSONObject("tokens");
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + t.optString("access_token"));
        headers.put("Accept", "application/json"); headers.put("Cache-Control", "no-store");
        if (body != null) headers.put("Content-Type", "application/json; charset=utf-8");
        return transport.request(method, path, headers, body);
    }
    private void refresh() throws IOException {
        checkCancelled(); JSONObject old = state.optJSONObject("tokens");
        if (old == null) throw new Problem("Connect to GrowthTrack first");
        put("refreshing", true); save();
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("grant_type", "refresh_token"); form.put("refresh_token", old.optString("refresh_token")); form.put("client_id", GrowthTrackProtocol.CLIENT_ID);
        try {
            Response r = tokenRequest(form); checkCancelled();
            if (r.status != 200) throw new Problem(tokenError(r));
            JSONObject replacement = tokens(r.body, old.optString("scope"));
            if (old.optString("refresh_token").equals(replacement.optString("refresh_token"))) throw new Problem("GrowthTrack did not rotate the refresh credential");
            put("tokens", replacement); put("refreshing", false); save();
        } catch (IOException e) {
            clearAuthority("Could not safely renew GrowthTrack access. Connect again; the old refresh credential will not be replayed."); save();
            throw new Problem("Could not safely renew GrowthTrack access. Connect again.");
        }
    }
    private Response tokenRequest(Map<String, String> form) throws IOException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/x-www-form-urlencoded"); headers.put("Accept", "application/json"); headers.put("Cache-Control", "no-store");
        return transport.request("POST", "/oauth-token", headers, GrowthTrackProtocol.form(form));
    }
    private JSONObject tokens(String body, String requestedScope) throws Problem {
        try {
            JSONObject o = new JSONObject(body); String access = o.getString("access_token"), refresh = o.getString("refresh_token");
            int expiry = o.getInt("expires_in"); String scope = o.getString("scope");
            if (!access.matches("gta_[A-Za-z0-9_-]{20,256}") || !refresh.matches("gtr_[A-Za-z0-9_-]{20,256}")
                    || !"Bearer".equalsIgnoreCase(o.getString("token_type")) || expiry < 1 || expiry > 900
                    || !hasScope(scope, GrowthTrackProtocol.WRITE)) throw new JSONException("Invalid credentials");
            for (String s : scope.trim().split("\\s+")) if (!hasScope(requestedScope, s)) throw new JSONException("Unexpected permission");
            if (hasScope(requestedScope, GrowthTrackProtocol.READ) && !hasScope(scope, GrowthTrackProtocol.READ))
                throw new JSONException("Missing permission");
            return new JSONObject().put("access_token", access).put("refresh_token", refresh).put("scope", scope)
                .put("expires_at", clock.now() + expiry * 1000L);
        } catch (JSONException e) { throw new Problem("GrowthTrack returned invalid credentials. Connect again."); }
    }
    private String tokenError(Response r) {
        String code = ""; try { code = new JSONObject(r.body).optString("error"); } catch (JSONException ignored) { }
        if ("invalid_client".equals(code)) return "GrowthTrack has not enabled this OpenPump client. Contact the integration maintainer.";
        if ("invalid_grant".equals(code)) return "GrowthTrack authorization expired or was removed. Connect again.";
        return "GrowthTrack could not complete authorization (" + r.status + "). Connect again.";
    }
    private void applyResults(JSONArray batch, JSONObject response) throws JSONException, IOException {
        int accepted = count(response, "accepted"), duplicates = count(response, "duplicates");
        JSONArray rejected = response.getJSONArray("rejected"); Set<String> submitted = ids(batch), seen = new HashSet<String>();
        Map<String, String> reasons = new LinkedHashMap<String, String>();
        for (int i = 0; i < rejected.length(); i++) {
            JSONObject item = rejected.getJSONObject(i); String id = item.getString("external_session_id");
            if (!submitted.contains(id) || !seen.add(id)) throw new JSONException("Invalid result IDs");
            String reason = item.getString("reason");
            reasons.put(id, reason.equals("Insert failed") || reason.contains("retry later") ? "retry" : "Needs review: " + safeReason(reason));
        }
        if ((long) accepted + duplicates + rejected.length() != batch.length()) throw new JSONException("Incomplete result counts");
        Set<String> finished = new HashSet<String>(); boolean retry = false;
        for (String id : submitted) {
            String reason = reasons.get(id);
            if ("retry".equals(reason)) { retry = true; continue; }
            receiptPut(id, reason == null ? "Received by GrowthTrack (sent or already present)" : reason); finished.add(id);
        }
        removeIds(finished);
        put("message", accepted + " accepted, " + duplicates + " already present, " + rejected.length() + " rejected.");
        if (!retry) put("attempts", 0);
        put("retry_at", 0); save();
        if (retry) retry(null);
    }
    private static int count(JSONObject o, String field) throws JSONException {
        Object value = o.get(field); if (!(value instanceof Number)) throw new JSONException("Invalid count");
        double n = ((Number) value).doubleValue(); if (!Double.isFinite(n) || n < 0 || n > Integer.MAX_VALUE || Math.floor(n) != n) throw new JSONException("Invalid count");
        return (int) n;
    }
    private JSONArray batch() throws IOException {
        JSONArray out = new JSONArray(); int limit = Math.max(1, state.optInt("batch_limit", GrowthTrackProtocol.BATCH_SIZE));
        for (int i = 0; i < approved().length() && i < limit; i++) {
            out.put(approved().optJSONObject(i));
            if (envelope(out).getBytes(StandardCharsets.UTF_8).length > GrowthTrackProtocol.MAX_BYTES) {
                JSONArray smaller = new JSONArray(); for (int j = 0; j < out.length() - 1; j++) smaller.put(out.optJSONObject(j));
                if (smaller.length() == 0) throw new Problem("An approved session exceeds the request size limit. Cancel and review it.");
                return smaller;
            }
        }
        return out;
    }
    private String envelope(JSONArray list) throws Problem { try { return new JSONObject().put("schema_version", 1).put("sessions", list).toString(); } catch (JSONException e) { throw new Problem("Could not prepare the upload"); } }
    private static Set<String> ids(JSONArray rows) { Set<String> out = new HashSet<String>(); for (int i = 0; i < rows.length(); i++) out.add(rows.optJSONObject(i).optString("external_session_id")); return out; }
    private JSONArray approved() { return state.optJSONArray("approved"); }
    private void removeIds(Set<String> ids) throws Problem { JSONArray remaining = new JSONArray(); for (int i = 0; i < approved().length(); i++) if (!ids.contains(approved().optJSONObject(i).optString("external_session_id"))) remaining.put(approved().optJSONObject(i)); put("approved", remaining); }
    private void receiptPut(String id, String text) throws Problem { try { state.optJSONObject("receipts").put(id, text); } catch (JSONException e) { throw new Problem("Could not save the upload result"); } }
    private void retry(String retryAfter) throws IOException {
        int attempts = Math.min(10, state.optInt("attempts", 0) + 1); long delay = Math.min(3_600_000L, 5000L << (attempts - 1));
        if (retryAfter != null) try { delay = Math.max(delay, Math.min(86_400L, Math.max(0, Long.parseLong(retryAfter))) * 1000L); } catch (NumberFormatException ignored) { }
        put("attempts", attempts); put("retry_at", clock.now() + delay); put("message", "Sessions are securely queued. Automatic retry is waiting for the connection or retry delay."); save();
    }
    private void checkLinked(String scope) throws Problem { if (storageBroken) throw new Problem("Secure storage failed. Disconnect/reset this connection before continuing."); JSONObject t = state.optJSONObject("tokens"); if (t == null) throw new Problem("Connect to GrowthTrack first"); if (!hasScope(t.optString("scope"), scope)) throw new Problem("Connect again to allow " + scope); }
    private static boolean hasScope(String scopes, String scope) { for (String s : scopes.trim().split("\\s+")) if (scope.equals(s)) return true; return false; }
    private void clearAuthority(String message) throws Problem { state.remove("tokens"); state.remove("pending"); state.remove("connection_id"); state.remove("approved_connection_id"); put("refreshing", false); put("exchanging", false); put("automatic_sync", false); put("sync_paused", false); put("approved", new JSONArray()); put("retry_at", 0); put("message", message); }
    private JSONObject pendingJson(GrowthTrackProtocol.Pending p) throws Problem { try { return p.json(); } catch (JSONException e) { throw new Problem("Could not save authorization"); } }
    private void put(String key, Object value) throws Problem { try { state.put(key, value); } catch (JSONException e) { throw new Problem("Could not save connection state"); } }
    private void save() throws IOException { put("version", 1); try { store.write(new JSONObjectCopy(state).value); } catch (IOException e) { storageBroken = true; cancelled = true; throw e; } }
    private static final class JSONObjectCopy { final JSONObject value; JSONObjectCopy(JSONObject original) throws Problem { try { value = new JSONObject(original.toString()); } catch (JSONException e) { throw new Problem("Could not save connection state"); } } }
    private static String safeReason(String reason) { return reason.replaceAll("(?:gta|gtr|gtc|gtp)_[A-Za-z0-9_-]+", "[redacted]").substring(0, Math.min(200, reason.replaceAll("(?:gta|gtr|gtc|gtp)_[A-Za-z0-9_-]+", "[redacted]").length())); }
    private static void validateSession(JSONObject s) throws JSONException {
        Set<String> allowed = new HashSet<String>(java.util.Arrays.asList("external_session_id", "started_at", "completed_at", "routine_name", "total_duration_seconds", "time_under_tension_seconds", "pressure_peak_inhg", "method", "sets"));
        for (java.util.Iterator<String> it = s.keys(); it.hasNext();) if (!allowed.contains(it.next())) throw new JSONException("Unapproved session field");
        if (s.getString("external_session_id").length() > 128 || s.getString("external_session_id").isEmpty()
                || s.getString("routine_name").isEmpty() || s.getString("routine_name").length() > 200) throw new JSONException("Invalid session identity");
        long started = timestamp(s.getString("started_at")), completed = timestamp(s.getString("completed_at"));
        if (completed <= started) throw new JSONException("Invalid session timestamps");
        int seconds = count(s, "total_duration_seconds"); if (seconds < 1 || seconds > 86400) throw new JSONException("Invalid duration");
        if (s.has("time_under_tension_seconds")) { int tension = count(s, "time_under_tension_seconds"); if (tension < 1 || tension > seconds) throw new JSONException("Invalid time under tension"); }
        if (s.has("pressure_peak_inhg")) {
            Object value = s.get("pressure_peak_inhg");
            if (!(value instanceof Number)) throw new JSONException("Invalid pressure");
            double peak = ((Number) value).doubleValue();
            if (!Double.isFinite(peak) || peak < 0 || peak > 29.92) throw new JSONException("Invalid pressure");
        }
        JSONArray sets = s.optJSONArray("sets");
        if (sets == null || sets.length() == 0) { if (!GrowthTrackSession.method(s.getString("method"))) throw new JSONException("Invalid method"); }
        else {
            if (sets.length() > 500) throw new JSONException("Too many sets");
            long totalSetSeconds = 0; Set<Integer> indexes = new HashSet<Integer>();
            for (int i = 0; i < sets.length(); i++) {
                JSONObject set = sets.getJSONObject(i);
                for (java.util.Iterator<String> it = set.keys(); it.hasNext();) if (!java.util.Arrays.asList("method", "set_index", "duration_seconds").contains(it.next())) throw new JSONException("Unapproved set field");
                int index = count(set, "set_index");
                if (index < 1 || !indexes.add(index) || !GrowthTrackSession.method(set.getString("method"))) throw new JSONException("Invalid set");
                if (!set.isNull("duration_seconds")) {
                    int duration = count(set, "duration_seconds"); if (duration < 1 || duration > seconds) throw new JSONException("Invalid set duration");
                    totalSetSeconds += duration;
                }
            }
            if (totalSetSeconds > seconds) throw new JSONException("Set durations exceed the session duration");
        }
    }
    private static long timestamp(String text) throws JSONException {
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")) throw new JSONException("Invalid timestamp");
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT);
        format.setLenient(false); format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        java.text.ParsePosition pos = new java.text.ParsePosition(0); java.util.Date date = format.parse(text, pos);
        if (date == null || pos.getIndex() != text.length()) throw new JSONException("Invalid timestamp");
        return date.getTime();
    }
}
