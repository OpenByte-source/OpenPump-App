package org.openpump;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Serialized OAuth and upload state machine. Transport and encrypted storage are Android seams.
 *
 * THE CONNECTION, AS THE OWNER DECIDED IT (19 Oct 2026):
 *  - A connection has its own random id (GrowthTrackProtocol#connectionId), made when an
 *    account is connected and kept for as long as that account stays connected - across token
 *    renewals, across a lost credential, across a reconnect. Sessions are marked with it.
 *  - Reconnecting never wipes the queue and never touches the working connection until the
 *    new approval has actually arrived; cancelling it leaves everything as it was.
 *  - Changing account starts a new connection id; the old account's waiting sessions are
 *    dropped from the queue, so nothing of one account is ever sent to another.
 *  - Renewing access only gives up the credential when GrowthTrack definitively refused it
 *    (a 4xx such as invalid_grant). Offline, timeouts, 408/429/5xx, a stopped job or a crash
 *    keep the credential and the queue, and retry with backoff.
 *  - Disconnect forgets the connection on this phone.
 */
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

    /** "Already on this phone": only new sessions, or also the earlier ones. Asked once per
     *  account (connection). */
    public static final String EARLIER_NEW = "new", EARLIER_ALSO = "also";
    public static final int WITHDRAW_NOTHING = 0, WITHDRAWN = 1, WITHDRAW_ALREADY_SENT = 2;
    /** The longest any retry may wait: Retry-After is bounded to a day. */
    static final long MAX_DELAY_MS = 86_400_000L;

    public static final class Snapshot {
        /** connected: a credential is held. needsReconnect: the account is still this phone's
         *  connection, but GrowthTrack refused the credential - sessions keep being marked and
         *  go once the person reconnects. */
        public final boolean connected, pending, paused, needsReconnect;
        public final int queued;
        public final long retryAt, lastSentAt;
        public final String message, connectionId;
        /** The account's email or name when GrowthTrack's token answer carries one, else "". */
        public final String account;
        /** This connection's "earlier sessions" answer: "", EARLIER_NEW or EARLIER_ALSO. */
        public final String earlierChoice;
        /** EARLIER_ALSO chosen and the earlier sessions not yet marked (GrowthTrackOutbox#adoptEarlier). */
        public final boolean earlierToAdopt;
        Snapshot(boolean connected, boolean pending, boolean paused, boolean needsReconnect, int queued, long retryAt,
                 long lastSentAt, String message, String connectionId, String account, String earlierChoice, boolean earlierToAdopt) {
            this.connected = connected; this.pending = pending; this.paused = paused; this.needsReconnect = needsReconnect;
            this.queued = queued; this.retryAt = retryAt; this.lastSentAt = lastSentAt; this.message = message;
            this.connectionId = connectionId; this.account = account; this.earlierChoice = earlierChoice;
            this.earlierToAdopt = earlierToAdopt;
        }
    }
    /** The client's word on one session: its receipt text ("" none) and whether it waits. */
    public static final class SessionState {
        public final String receipt; public final boolean queued;
        SessionState(String receipt, boolean queued) { this.receipt = receipt; this.queued = queued; }
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
        if (state.optBoolean("refreshing")) {
            // The process died while renewing access (bug 18). Whether the request reached the
            // server is unknown; the credential is kept and the next sync tries again. Should
            // GrowthTrack have rotated it, it answers invalid_grant and the person reconnects -
            // nothing is lost, the sessions stay marked.
            put("refreshing", false); put("message", "Renewing GrowthTrack access was interrupted. OpenPump will try again."); save();
        }
        if (state.optBoolean("exchanging")) {
            // A single-use code: never replayed. A working connection (a reconnect) is untouched.
            put("exchanging", false); state.remove("pending");
            put("message", state.optJSONObject("tokens") != null ? "The new approval was interrupted. Your existing connection is unchanged."
                : "Connection interrupted while exchanging credentials. Connect again."); save();
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
            put("approved", new JSONArray()); put("queue_meta", new JSONObject());
            put("message", "The account approval changed. Pending sessions for the previous account were discarded."); save();
        }
    }

    public synchronized Snapshot snapshot() {
        JSONObject t = state.optJSONObject("tokens");
        boolean connected = t != null && !storageBroken;
        String conn = state.optString("connection_id");
        JSONObject account = state.optJSONObject("account");
        String choice = obj("earlier").optString(conn, "");
        return new Snapshot(connected, pendingLive(), state.optBoolean("sync_paused"), !connected && !storageBroken && !conn.isEmpty(),
            approved().length(), nextDue(), state.optLong("last_sent_at", 0), state.optString("message", "Not connected"), conn,
            account == null ? "" : account.optString("label", ""), choice,
            EARLIER_ALSO.equals(choice) && !obj("earlier_done").optBoolean(conn, false));
    }
    /** The receipt for a session's stableId - its own id or the id it was sent under. */
    public synchronized String receipt(String id) { return obj("receipts").optString(external(id), ""); }
    public synchronized SessionState state(String localStableId) {
        String ext = external(localStableId);
        return new SessionState(obj("receipts").optString(ext, ""), ids(approved()).contains(ext));
    }
    /** localStableId -> revision of every record that needs no more work; for
     *  GrowthTrackOutbox#take, so a long history is not drafted again on every sync. */
    public synchronized Map<String, Integer> settled() {
        Map<String, Integer> out = new HashMap<String, Integer>();
        Set<String> queued = ids(approved());
        JSONObject receipts = obj("receipts"), revisions = obj("revisions");
        for (Iterator<String> it = receipts.keys(); it.hasNext();) {
            String ext = it.next();
            if (!queued.contains(ext)) out.put(ext, revisions.optInt(ext, 0));
        }
        JSONObject aliases = obj("aliases");
        for (Iterator<String> it = aliases.keys(); it.hasNext();) {
            String local = it.next(); Integer rev = out.get(aliases.optString(local));
            if (rev != null) out.put(local, rev);
        }
        return out;
    }
    public void cancelRequests() { cancelled = true; }
    private void checkCancelled() throws Problem { if (storageBroken) throw new Problem("Secure storage failed. Disconnect/reset this connection before continuing."); if (cancelled) throw new Problem("Sending stopped. An in-flight request may already have arrived; stable IDs make a later retry safe."); }

    /* ---------------------------------------------------------------- connecting */

    /** Connect, or reconnect the same account: the working connection, its queue and its id
     *  stay exactly as they are until the new approval arrives (bug 12). */
    public synchronized String begin() throws IOException { return beginInternal(false, ""); }
    /** As begin(), carrying the connect page's earlier-sessions answer (used only if this
     *  account has not answered before). */
    public synchronized String begin(String earlierChoice) throws IOException { return beginInternal(false, earlierChoice); }
    /** "Change account": the approval that arrives starts a new connection, and the old
     *  account's waiting sessions are dropped from the queue. Cancelled, nothing changes. */
    public synchronized String beginChangeAccount(String earlierChoice) throws IOException { return beginInternal(true, earlierChoice); }
    private String beginInternal(boolean change, String earlierChoice) throws IOException {
        if (storageBroken) throw new Problem("Secure storage failed. Disconnect/reset this connection before continuing.");
        cancelled = false;
        GrowthTrackProtocol.Pending p = GrowthTrackProtocol.pending(clock.now());
        try {
            JSONObject pj = p.json().put("mode", change ? "change" : "same")
                .put("earlier", EARLIER_NEW.equals(earlierChoice) || EARLIER_ALSO.equals(earlierChoice) ? earlierChoice : "");
            put("pending", pj);
        } catch (JSONException e) { throw new Problem("Could not create the connection request"); }
        put("message", "Waiting for GrowthTrack approval in your browser"); save();
        return p.authorizeUrl();
    }
    public synchronized void cancelAuthorization() throws IOException {
        state.remove("pending");
        put("message", state.optJSONObject("tokens") != null ? "Connection unchanged." : "Connection cancelled. No sessions were sent."); save();
    }
    public synchronized void callback(String uri) throws IOException {
        JSONObject pj = state.optJSONObject("pending");
        GrowthTrackProtocol.Pending pending = null;
        GrowthTrackProtocol.Callback callback;
        try {
            pending = pj == null ? null : GrowthTrackProtocol.Pending.from(pj);
            callback = GrowthTrackProtocol.callback(uri, pending, clock.now());
        } catch (JSONException | IllegalArgumentException e) {
            // An expired request can never complete: drop it rather than "waiting" forever (bug 21).
            if (pending != null && expired(pending)) {
                state.remove("pending"); put("message", "The connection request expired. Connect again."); save();
            }
            throw new Problem(e.getMessage());
        }
        checkCancelled();
        boolean change = "change".equals(pj.optString("mode"));
        String choice = pj.optString("earlier", "");
        boolean hadTokens = state.optJSONObject("tokens") != null;
        state.remove("pending");
        if (callback.error != null) {
            put("message", "access_denied".equals(callback.error)
                ? (hadTokens ? "GrowthTrack approval was declined. Your existing connection is unchanged." : "GrowthTrack approval was declined. No sessions were sent.")
                : (hadTokens ? "GrowthTrack could not approve this. Your existing connection is unchanged." : "GrowthTrack could not approve this connection. Connect again."));
            save(); return;
        }
        put("exchanging", true); save();
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("grant_type", "authorization_code"); form.put("code", callback.code);
        form.put("code_verifier", pending.verifier); form.put("client_id", GrowthTrackProtocol.CLIENT_ID); form.put("redirect_uri", GrowthTrackProtocol.REDIRECT);
        String failed = hadTokens ? "Could not complete the new approval. Your existing connection is unchanged."
            : "Could not complete the connection. Connect again; the authorization code will not be replayed.";
        try {
            Response response;
            try { response = tokenRequest(form); }
            catch (RuntimeException e) { throw new Problem(failed); }
            if (response.status != 200) throw new Problem(tokenError(response));
            JSONObject tokens = tokens(response.body, pending.scope);
            JSONObject account = account(response.body);
            String conn = state.optString("connection_id");
            JSONObject before = state.optJSONObject("account");
            boolean otherAccount = change || conn.isEmpty()
                || (before != null && account != null && !before.optString("id").isEmpty()
                    && !account.optString("id").isEmpty() && !before.optString("id").equals(account.optString("id")));
            if (otherAccount) {
                // Never send one account's sessions to another (owner decision 4).
                conn = GrowthTrackProtocol.connectionId();
                put("connection_id", conn); state.remove("approved_connection_id");
                put("approved", new JSONArray()); put("queue_meta", new JSONObject());
                put("receipts", new JSONObject()); put("aliases", new JSONObject()); put("revisions", new JSONObject());
                put("attempts", 0); put("retry_at", 0); put("batch_limit", GrowthTrackProtocol.BATCH_SIZE);
                state.remove("account"); state.remove("last_sent_at");
            } else {
                // Same account: the queue stays (bug 12); the next pass refreshes each waiting
                // payload from its record (recordCompleted then answers true for it).
                JSONObject meta = obj("queue_meta");
                for (int i = 0; i < approved().length(); i++) {
                    String id = approved().optJSONObject(i).optString("external_session_id");
                    JSONObject m = meta.optJSONObject(id); if (m == null) m = new JSONObject();
                    m.put("carried", true); meta.put(id, m);
                }
                put("attempts", 0); put("retry_at", 0);
            }
            if (account != null) put("account", account);
            put("tokens", tokens); put("exchanging", false); put("automatic_sync", true); put("sync_paused", false);
            JSONObject earlier = obj("earlier");
            if (earlier.optString(conn, "").isEmpty() && !choice.isEmpty()) earlier.put(conn, choice);
            put("message", "Connected to the GrowthTrack account you approved in the browser."); save();
        } catch (JSONException e) {
            put("exchanging", false); put("message", failed); save(); throw new Problem(failed);
        } catch (IOException e) {
            put("exchanging", false); put("message", e instanceof Problem ? e.getMessage() : failed); save(); throw e;
        }
    }
    /** Answers "Already on this phone" for this connection, once: a later answer is ignored. */
    public synchronized void chooseEarlier(String choice) throws IOException {
        String conn = state.optString("connection_id");
        if (conn.isEmpty() || !(EARLIER_NEW.equals(choice) || EARLIER_ALSO.equals(choice))) return;
        JSONObject earlier = obj("earlier");
        if (!earlier.optString(conn, "").isEmpty()) return;
        try { earlier.put(conn, choice); } catch (JSONException e) { throw new Problem("Could not save the choice"); }
        save();
    }
    /** The app marked the earlier sessions (GrowthTrackOutbox#adoptEarlier) and saved them. */
    public synchronized void earlierAdopted(String connectionId) throws IOException {
        if (connectionId == null || !connectionId.equals(state.optString("connection_id"))) return;
        try { obj("earlier_done").put(connectionId, true); } catch (JSONException e) { throw new Problem("Could not save the choice"); }
        save();
    }
    public synchronized void disconnect() throws IOException {
        cancelled = true; clearAuthority("Disconnected on this device. Remove OpenPump in GrowthTrack Connected apps to revoke its server approval.");
        put("receipts", new JSONObject()); put("aliases", new JSONObject()); put("revisions", new JSONObject());
        state.remove("account"); state.remove("last_sent_at"); save();
    }
    public synchronized void cancelApproved() throws IOException {
        put("approved", new JSONArray()); put("queue_meta", new JSONObject()); put("retry_at", 0); put("attempts", 0);
        put("message", "Sending stopped. Sessions already received by GrowthTrack remain there."); save();
    }

    /* ---------------------------------------------------------------- what is queued */

    /** Completion consent is the account connection captured when the record was filed. True
     *  when this call put a fresh payload in the queue: a new session, a resumed session's
     *  update, or a waiting one refreshed after a reconnect. */
    public synchronized boolean recordCompleted(Model.Sess session, AsRun run, Model model, String completionConnectionId) throws IOException {
        if (!accepts(completionConnectionId)) return false;
        return record(GrowthTrackOutbox.Item.of(session, run, model), completionConnectionId);
    }
    private boolean accepts(String conn) {
        String current = state.optString("connection_id");
        return !storageBroken && conn != null && !conn.isEmpty() && conn.equals(current)
            && (state.optJSONObject("tokens") != null || !current.isEmpty());
    }
    private boolean record(GrowthTrackOutbox.Item item, String conn) throws IOException {
        if (!accepts(conn)) return false;
        checkCancelled();
        if (item.externalId == null) { put("message", "Not synced: a finished record has no stable identifier."); save(); return false; }
        String id = item.externalId;
        if (item.localId != null && !item.localId.equals(id)) jput(obj("aliases"), item.localId, id);
        JSONObject meta = obj("queue_meta");
        int index = indexOf(id);
        if (index >= 0) {
            JSONObject m = meta.optJSONObject(id);
            boolean carried = m != null && m.optBoolean("carried");
            int rev = m == null ? 0 : m.optInt("rev", 0);
            if (!carried && item.revision <= rev) return false;
            if (m == null) m = new JSONObject();
            jput(m, "carried", false);
            if (item.sendable()) { replaceAt(index, item.payload); jput(m, "rev", Math.max(rev, item.revision)); jput(obj("revisions"), id, Math.max(rev, item.revision)); }
            jput(meta, id, m); save();
            return item.sendable();
        }
        String receipt = obj("receipts").optString(id, "");
        if (!receipt.isEmpty()) {
            // Already finished. Only a longer record replacing it (resumed / rejoined, bug 5)
            // goes again, under the same id, as an update. A receipt written before revisions
            // existed is final.
            if (!obj("revisions").has(id) || item.revision <= obj("revisions").optInt(id, 0)) return false;
        }
        if (!item.sendable()) {
            jput(obj("revisions"), id, item.revision);
            // What GrowthTrack already holds stays "received"; only the update failed.
            if (receipt.startsWith("Received")) { save(); return false; }
            receiptPut(id, "Not synced: " + safeReason(item.failure));
            put("message", "A finished recording cannot be sent. See Sent and waiting sessions for the reason."); save(); return false;
        }
        approved().put(item.payload);
        JSONObject m = new JSONObject(); jput(m, "rev", item.revision); if (item.localId != null) jput(m, "local", item.localId);
        jput(meta, id, m);
        jput(obj("revisions"), id, item.revision);
        put("approved_connection_id", conn);
        put("message", approved().length() + (approved().length() == 1 ? " session waiting" : " sessions waiting")); save(); return true;
    }
    /** History > Delete (bug 6): a session deleted while it waits is never uploaded. One
     *  GrowthTrack already holds cannot be recalled from here: WITHDRAW_ALREADY_SENT. */
    public synchronized int withdraw(String sessionId) throws IOException {
        String local;
        try { local = GrowthTrackProtocol.stableId(sessionId); } catch (IllegalArgumentException e) { return WITHDRAW_NOTHING; }
        String id = external(local);
        if (indexOf(id) >= 0) { Set<String> one = new HashSet<String>(); one.add(id); removeIds(one); save(); return WITHDRAWN; }
        return obj("receipts").optString(id, "").startsWith("Received") ? WITHDRAW_ALREADY_SENT : WITHDRAW_NOTHING;
    }
    /**
     * ONE SYNC PASS from a main-thread snapshot (bug 8). Withdraws what is no longer on the
     * phone or is held back by a switch, queues what is new, then sends.
     */
    public synchronized void sync(GrowthTrackOutbox box) throws IOException {
        if (box != null && !box.connectionId.isEmpty() && box.connectionId.equals(state.optString("connection_id"))) {
            cancelled = false;
            JSONObject meta = obj("queue_meta");
            Set<String> gone = new HashSet<String>();
            for (int i = 0; i < approved().length(); i++) {
                String id = approved().optJSONObject(i).optString("external_session_id");
                JSONObject m = meta.optJSONObject(id);
                String local = m == null ? "" : m.optString("local", "");
                boolean live = box.live.contains(id) || (!local.isEmpty() && box.live.contains(local));
                boolean held = box.heldBack.contains(id) || (!local.isEmpty() && box.heldBack.contains(local));
                if (!live || held) gone.add(id);
            }
            if (!gone.isEmpty()) { removeIds(gone); save(); }
            for (GrowthTrackOutbox.Item item : box.items) record(item, box.connectionId);
            if (!box.sendingOn) return;
        }
        syncPending();
    }

    /** Read-only scheduling decision: a paused permission error never loops in background. */
    public synchronized long automaticRetryAt() {
        Snapshot s = snapshot(); return s.connected && s.queued > 0 && !s.paused ? Math.max(clock.now(), s.retryAt) : -1;
    }
    public synchronized void backgroundUnavailable() throws IOException {
        put("message", "Background sync could not be scheduled. Pending sessions are secure; they go the next time OpenPump opens."); save();
    }
    /** Called on the serial native worker after a fresh transport generation is entered. */
    public synchronized void prepareAutomaticSync() throws IOException {
        if (storageBroken) throw new Problem("Secure storage failed. Disconnect/reset before continuing.");
        cancelled = false;
    }
    /** Starts no OAuth and scans no unmarked history. Same-connection durable pending work only. */
    public synchronized void syncPending() throws IOException {
        if (automaticRetryAt() < 0 || clock.now() < globalRetryAt()) return;
        cancelled = false; sendApproved();
    }

    /** A detached, allowlisted snapshot of exactly the records the user just reviewed. */
    public synchronized void approve(List<JSONObject> sessions) throws IOException {
        approve(sessions, state.optString("connection_id"));
    }
    /** The review must still refer to the same connection, including across relinking. */
    public synchronized void approve(List<JSONObject> sessions, String reviewedConnectionId) throws IOException {
        checkLinked(); cancelled = false;
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
                if (envelopeBytes(new JSONArray().put(s)) > GrowthTrackProtocol.MAX_BYTES)
                    throw new Problem("A selected session exceeds GrowthTrack's request size limit");
                queue.put(new JSONObject(s.toString()));
            }
        } catch (JSONException | IllegalArgumentException e) { throw new Problem("The selected session payload is invalid"); }
        put("approved", queue); put("queue_meta", new JSONObject()); put("attempts", 0); put("retry_at", 0); put("batch_limit", GrowthTrackProtocol.BATCH_SIZE);
        put("approved_connection_id", reviewedConnectionId);
        put("message", queue.length() + " reviewed sessions approved for sending"); save();
    }
    /** Retry uses the same durable, account-bound queue; startup and scheduled jobs call sync(). */
    public synchronized void resumeApproved() throws IOException { put("sync_paused", false); cancelled = false; sendApproved(); }
    public synchronized void sendApproved() throws IOException {
        checkCancelled(); checkLinked();
        if (approved().length() > 0 && !state.optString("connection_id").equals(state.optString("approved_connection_id")))
            throw new Problem("The approved send belongs to an earlier connection. Cancel it and review your sessions again.");
        if (clock.now() < globalRetryAt()) throw new Problem("Please wait for the retry delay before trying again");
        while (approved().length() > 0) {
            checkCancelled();
            JSONArray batch = batch();
            if (batch.length() == 0) break;           // every waiting session is in its own backoff
            String body = envelope(batch);
            Response response;
            try { response = authorized("POST", "/partner-ingest", body); }
            catch (Problem e) { throw e; }
            catch (IOException e) { retry(null); throw new Problem("Offline or network error. Your sessions are saved for automatic retry."); }
            checkCancelled();
            if ((response.status == 413 || response.status == 400 || response.status == 422) && batch.length() > 1) {
                // Split and send again (bug 13): one bad session must not take the batch with it.
                // The one that fails alone is the one marked below.
                put("batch_limit", Math.max(1, batch.length() / 2)); save(); continue;
            }
            if (response.status == 408 || response.status == 429 || response.status >= 500) {
                retry(response.retryAfter); throw new Problem("GrowthTrack is unavailable or busy. Your sessions are saved for automatic retry.");
            }
            if (response.status == 400 || response.status == 413 || response.status == 422) {
                for (int i = 0; i < batch.length(); i++) receiptPut(batch.optJSONObject(i).optString("external_session_id"), "Needs review: GrowthTrack rejected this session (" + response.status + ")");
                removeIds(ids(batch)); put("message", "GrowthTrack refused a session. See Sent and waiting sessions."); save();
                continue;
            }
            if (response.status != 200) {
                put("sync_paused", true); put("message", "Sync paused: GrowthTrack rejected the request (" + response.status + "). Check the connection."); save();
                throw new Problem("GrowthTrack rejected the request (" + response.status + "). See sync status.");
            }
            try { applyResults(batch, new JSONObject(response.body)); }
            catch (JSONException | IllegalArgumentException e) { retry(null); throw new Problem("GrowthTrack returned an incomplete result. The approved batch is kept; retrying is safe."); }
            if (globalRetryAt() > clock.now()) return;
        }
        // A smaller batch was only for finding one bad or oversize session (small fix).
        if (approved().length() == 0 && state.optInt("batch_limit", GrowthTrackProtocol.BATCH_SIZE) != GrowthTrackProtocol.BATCH_SIZE) {
            put("batch_limit", GrowthTrackProtocol.BATCH_SIZE); save();
        }
    }

    /* ---------------------------------------------------------------- credentials */

    private Response authorized(String method, String path, String body) throws IOException {
        checkLinked();
        JSONObject t = state.optJSONObject("tokens");
        boolean refreshed = false;
        if (clock.now() + 30_000 >= t.optLong("expires_at")) { refresh(); refreshed = true; }
        Response r = authenticatedRequest(method, path, body);
        if (r.status == 401 && !refreshed) { refresh(); r = authenticatedRequest(method, path, body); }
        if (r.status == 401 || r.status == 410) {
            loseAuthority("GrowthTrack access expired or was removed. Connect again; your sessions stay on this phone."); save();
            throw new Problem("GrowthTrack access expired or was removed. Connect again.");
        }
        if (r.status == 403) { put("sync_paused", true); put("message", "Sync paused: GrowthTrack has not granted " + GrowthTrackProtocol.WRITE + ". Reconnect to restore access."); save(); throw new Problem(state.optString("message")); }
        return r;
    }
    private Response authenticatedRequest(String method, String path, String body) throws IOException {
        checkCancelled(); JSONObject t = state.optJSONObject("tokens");
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + t.optString("access_token"));
        headers.put("Accept", "application/json"); headers.put("Cache-Control", "no-store");
        if (body != null) headers.put("Content-Type", "application/json; charset=utf-8");
        // A platform HTTP stack can throw unchecked; for an upload it is a network failure
        // like any other (stable ids make the retry safe).
        try { return transport.request(method, path, headers, body); }
        catch (RuntimeException e) { throw new IOException("Network error"); }
    }
    /**
     * RENEWS ACCESS (bug 1). The credential is given up only when GrowthTrack definitively
     * refused it; anything that may never have reached the server keeps it and retries.
     */
    private void refresh() throws IOException {
        checkCancelled(); JSONObject old = state.optJSONObject("tokens");
        if (old == null) throw new Problem("Connect to GrowthTrack first");
        put("refreshing", true); save();
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("grant_type", "refresh_token"); form.put("refresh_token", old.optString("refresh_token")); form.put("client_id", GrowthTrackProtocol.CLIENT_ID);
        Response r;
        try { r = tokenRequest(form); }
        catch (IOException e) {
            put("refreshing", false); retry(null);
            throw new Problem("Offline or network error while renewing GrowthTrack access. Your connection and sessions are kept; OpenPump retries automatically.");
        } catch (RuntimeException e) {
            // Never strand the marker (bug 18); the caller still sees what went wrong.
            put("refreshing", false); retry(null); throw e;
        }
        if (r.status == 200) {
            // Kept even if the job was stopped meanwhile: a rotated credential must never be lost.
            JSONObject replacement;
            try {
                replacement = tokens(r.body, old.optString("scope"));
                if (old.optString("refresh_token").equals(replacement.optString("refresh_token"))) throw new Problem("GrowthTrack did not rotate the refresh credential");
            } catch (Problem e) {
                loseAuthority("GrowthTrack returned unusable credentials. Connect again; your sessions stay on this phone."); save();
                throw new Problem("Could not safely renew GrowthTrack access. Connect again.");
            }
            put("tokens", replacement); put("refreshing", false); save();
            checkCancelled();
            return;
        }
        put("refreshing", false);
        if (r.status == 408 || r.status == 429 || r.status >= 500) {
            retry(r.retryAfter);
            throw new Problem("GrowthTrack is busy. Your connection and sessions are kept; OpenPump retries automatically.");
        }
        String why = tokenError(r);
        loseAuthority(why); save();
        throw new Problem(why);
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
            return new JSONObject().put("access_token", access).put("refresh_token", refresh).put("scope", scope)
                .put("expires_at", clock.now() + expiry * 1000L);
        } catch (JSONException e) { throw new Problem("GrowthTrack returned invalid credentials. Connect again."); }
    }
    /** The account the approval names, when GrowthTrack's answer carries it (optional; no
     *  profile scope is requested). id tells one account from another; label is shown. */
    static JSONObject account(String body) {
        try {
            JSONObject o = new JSONObject(body);
            JSONObject nested = o.optJSONObject("account"); if (nested == null) nested = o.optJSONObject("user");
            String id = first(nested, o, "account_id", "user_id", "sub", "id");
            String label = first(nested, o, "email", "name", "display_name");
            if (id != null && !id.matches("[A-Za-z0-9_.@:+-]{1,128}")) id = null;
            if (label != null) { label = label.replaceAll("[\\p{Cntrl}]", "").trim(); if (label.length() > 120) label = label.substring(0, 120); if (label.isEmpty()) label = null; }
            if (id == null && label == null) return null;
            return new JSONObject().put("id", id == null ? "" : id).put("label", label == null ? "" : label);
        } catch (JSONException e) { return null; }
    }
    private static String first(JSONObject nested, JSONObject top, String... keys) {
        for (String k : keys) {
            if (nested != null && nested.opt(k) instanceof String && !nested.optString(k).isEmpty()) return nested.optString(k);
            if (!"id".equals(k) && top.opt(k) instanceof String && !top.optString(k).isEmpty()) return top.optString(k);
        }
        return null;
    }
    private String tokenError(Response r) {
        String code = ""; try { code = new JSONObject(r.body).optString("error"); } catch (JSONException ignored) { }
        if ("invalid_client".equals(code)) return "GrowthTrack has not enabled this OpenPump client. Contact the integration maintainer.";
        if ("invalid_grant".equals(code)) return "GrowthTrack authorization expired or was removed. Connect again; your sessions stay on this phone.";
        return "GrowthTrack could not complete authorization (" + r.status + "). Connect again.";
    }

    /* ---------------------------------------------------------------- results and backoff */

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
        Set<String> finished = new HashSet<String>();
        JSONObject meta = obj("queue_meta");
        for (String id : submitted) {
            String reason = reasons.get(id);
            if ("retry".equals(reason)) {
                // Its own backoff (bug 14): one failing session never holds back the others.
                JSONObject m = meta.optJSONObject(id); if (m == null) m = new JSONObject();
                int attempts = Math.min(10, m.optInt("attempts", 0) + 1); long delay = Math.min(3_600_000L, 5000L << (attempts - 1));
                m.put("attempts", attempts); m.put("due", clock.now() + delay); m.put("delay", delay); meta.put(id, m);
                continue;
            }
            receiptPut(id, reason == null ? "Received by GrowthTrack (sent or already present)" : reason); finished.add(id);
        }
        removeIds(finished);
        if (accepted + duplicates > 0) put("last_sent_at", clock.now());
        put("message", accepted + " accepted, " + duplicates + " already present, " + rejected.length() + " rejected.");
        put("attempts", 0); put("retry_at", 0); state.remove("retry_delay"); save();
    }
    private static int count(JSONObject o, String field) throws JSONException {
        Object value = o.get(field); if (!(value instanceof Number)) throw new JSONException("Invalid count");
        double n = ((Number) value).doubleValue(); if (!Double.isFinite(n) || n < 0 || n > Integer.MAX_VALUE || Math.floor(n) != n) throw new JSONException("Invalid count");
        return (int) n;
    }
    /** The due sessions, in queue order, within the batch and byte limits. */
    private JSONArray batch() throws IOException {
        JSONArray out = new JSONArray(); int limit = Math.max(1, state.optInt("batch_limit", GrowthTrackProtocol.BATCH_SIZE));
        long now = clock.now();
        for (int i = 0; i < approved().length() && out.length() < limit; i++) {
            JSONObject s = approved().optJSONObject(i);
            if (itemDue(s.optString("external_session_id")) > now) continue;
            out.put(s);
            if (envelopeBytes(out) > GrowthTrackProtocol.MAX_BYTES) {
                JSONArray smaller = new JSONArray(); for (int j = 0; j < out.length() - 1; j++) smaller.put(out.optJSONObject(j));
                if (smaller.length() == 0) throw new Problem("An approved session exceeds the request size limit. Cancel and review it.");
                return smaller;
            }
        }
        return out;
    }
    private void retry(String retryAfter) throws IOException {
        int attempts = Math.min(10, state.optInt("attempts", 0) + 1); long delay = Math.min(3_600_000L, 5000L << (attempts - 1));
        if (retryAfter != null) try { delay = Math.max(delay, Math.min(86_400L, Math.max(0, Long.parseLong(retryAfter.trim()))) * 1000L); } catch (NumberFormatException ignored) { }
        put("attempts", attempts); put("retry_at", clock.now() + delay); put("retry_delay", delay);
        put("message", "Sessions are securely queued. Automatic retry is waiting for the connection or retry delay."); save();
    }
    /**
     * A RETRY NEVER WAITS LONGER THAN THE DELAY IT WAS GIVEN (small fix). retry_at is a
     * wall-clock instant; a phone clock corrected backwards would otherwise stall sync until the
     * old time came round again. Too far ahead means the clock moved: due now.
     */
    private long globalRetryAt() {
        long at = state.optLong("retry_at", 0);
        return clamp(at, state.has("retry_delay") ? state.optLong("retry_delay") : MAX_DELAY_MS);
    }
    private long itemDue(String id) {
        JSONObject m = obj("queue_meta").optJSONObject(id);
        if (m == null) return 0;
        return clamp(m.optLong("due", 0), m.has("delay") ? m.optLong("delay") : MAX_DELAY_MS);
    }
    private long clamp(long at, long delay) {
        long now = clock.now();
        if (at <= now) return at;
        return at - now > Math.max(0, delay) ? now : at;
    }
    /** When anything can next be sent: the request-level backoff, or the soonest session's own. */
    private long nextDue() {
        long global = globalRetryAt();
        if (approved().length() == 0) return global;
        long soonest = Long.MAX_VALUE;
        for (int i = 0; i < approved().length(); i++) soonest = Math.min(soonest, itemDue(approved().optJSONObject(i).optString("external_session_id")));
        return Math.max(global, soonest == Long.MAX_VALUE ? 0 : soonest);
    }

    /* ---------------------------------------------------------------- state helpers */

    private boolean pendingLive() {
        JSONObject pj = state.optJSONObject("pending");
        if (pj == null) return false;
        long created = pj.optLong("created_at", 0), now = clock.now();
        return now >= created && now - created <= GrowthTrackProtocol.AUTH_LIFETIME_MS;
    }
    private boolean expired(GrowthTrackProtocol.Pending p) {
        long now = clock.now(); return now < p.createdAt || now - p.createdAt > GrowthTrackProtocol.AUTH_LIFETIME_MS;
    }
    private String external(String localStableId) { return obj("aliases").optString(localStableId, localStableId); }
    private int indexOf(String id) { for (int i = 0; i < approved().length(); i++) if (id.equals(approved().optJSONObject(i).optString("external_session_id"))) return i; return -1; }
    private void replaceAt(int index, JSONObject payload) throws Problem {
        try { approved().put(index, new JSONObject(payload.toString())); } catch (JSONException e) { throw new Problem("Could not save the session"); }
    }
    static String envelope(JSONArray list) throws Problem { try { return new JSONObject().put("schema_version", 1).put("sessions", list).toString(); } catch (JSONException e) { throw new Problem("Could not prepare the upload"); } }
    static long envelopeBytes(JSONArray list) throws IllegalArgumentException {
        try { return envelope(list).getBytes(StandardCharsets.UTF_8).length; } catch (Problem e) { throw new IllegalArgumentException(e.getMessage()); }
    }
    private static Set<String> ids(JSONArray rows) { Set<String> out = new HashSet<String>(); for (int i = 0; i < rows.length(); i++) out.add(rows.optJSONObject(i).optString("external_session_id")); return out; }
    private JSONArray approved() { return state.optJSONArray("approved"); }
    private JSONObject obj(String key) {
        JSONObject o = state.optJSONObject(key);
        if (o == null) { o = new JSONObject(); try { state.put(key, o); } catch (JSONException ignored) { } }
        return o;
    }
    private void removeIds(Set<String> ids) throws Problem {
        JSONArray remaining = new JSONArray(); for (int i = 0; i < approved().length(); i++) if (!ids.contains(approved().optJSONObject(i).optString("external_session_id"))) remaining.put(approved().optJSONObject(i));
        put("approved", remaining);
        JSONObject meta = obj("queue_meta"); for (String id : ids) meta.remove(id);
    }
    private void receiptPut(String id, String text) throws Problem { jput(obj("receipts"), id, text); }
    private static void jput(JSONObject o, String key, Object value) throws Problem { try { o.put(key, value); } catch (JSONException e) { throw new Problem("Could not save connection state"); } }
    private void checkLinked() throws Problem { if (storageBroken) throw new Problem("Secure storage failed. Disconnect/reset this connection before continuing."); JSONObject t = state.optJSONObject("tokens"); if (t == null) throw new Problem("Connect to GrowthTrack first"); if (!hasScope(t.optString("scope"), GrowthTrackProtocol.WRITE)) throw new Problem("Connect again to allow " + GrowthTrackProtocol.WRITE); }
    private static boolean hasScope(String scopes, String scope) { for (String s : scopes.trim().split("\\s+")) if (scope.equals(s)) return true; return false; }
    /** GrowthTrack refused the credential: it is dropped, and with it the queue (the payloads
     *  were bound to that credential's sends). The connection id, the receipts and the account
     *  stay, so reconnecting the same account re-queues every marked session from its record. */
    private void loseAuthority(String message) throws Problem {
        state.remove("tokens"); put("refreshing", false); put("exchanging", false); put("automatic_sync", false); put("sync_paused", false);
        put("approved", new JSONArray()); put("queue_meta", new JSONObject()); put("retry_at", 0); put("message", message);
    }
    /** Forgets the connection entirely (Disconnect, an older build's connection). */
    private void clearAuthority(String message) throws Problem { state.remove("tokens"); state.remove("pending"); state.remove("connection_id"); state.remove("approved_connection_id"); put("refreshing", false); put("exchanging", false); put("automatic_sync", false); put("sync_paused", false); put("approved", new JSONArray()); put("queue_meta", new JSONObject()); put("retry_at", 0); put("message", message); }
    private void put(String key, Object value) throws Problem { try { state.put(key, value); } catch (JSONException e) { throw new Problem("Could not save connection state"); } }
    private void save() throws IOException { put("version", 1); try { store.write(new JSONObjectCopy(state).value); } catch (IOException e) { storageBroken = true; cancelled = true; throw e; } }
    private static final class JSONObjectCopy { final JSONObject value; JSONObjectCopy(JSONObject original) throws Problem { try { value = new JSONObject(original.toString()); } catch (JSONException e) { throw new Problem("Could not save connection state"); } } }
    private static String safeReason(String reason) { String r = reason == null ? "Invalid recording" : reason.replaceAll("(?:gta|gtr|gtc|gtp)_[A-Za-z0-9_-]+", "[redacted]"); return r.substring(0, Math.min(200, r.length())); }
    static void validateSession(JSONObject s) throws JSONException {
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
