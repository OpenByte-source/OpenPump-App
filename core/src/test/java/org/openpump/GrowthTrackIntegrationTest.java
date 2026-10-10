package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Only fictional records and mocked HTTP/storage. These tests cannot reach GrowthTrack. */
class GrowthTrackIntegrationTest {
    static final String A = "gta_" + "a".repeat(43), R = "gtr_" + "r".repeat(43), R2 = "gtr_" + "s".repeat(43);
    static final String CODE = "gtc_" + "c".repeat(43);
    static class Clock implements GrowthTrackClient.Clock { long time = 1_700_000_000_000L; public long now() { return time; } }
    static class Storage implements GrowthTrackClient.Store {
        JSONObject saved = new JSONObject(); boolean fail;
        public JSONObject read() throws IOException { try { return new JSONObject(saved.toString()); } catch (Exception e) { throw new IOException(); } }
        public void write(JSONObject state) throws IOException { if (fail) throw new IOException("fixture storage failure"); try { saved = new JSONObject(state.toString()); } catch (Exception e) { throw new IOException(); } }
    }
    static class Request {
        final String method, path, body; final Map<String, String> headers;
        Request(String method, String path, Map<String, String> headers, String body) { this.method = method; this.path = path; this.headers = headers; this.body = body; }
    }
    static class Http implements GrowthTrackClient.Transport {
        final Deque<Object> responses = new ArrayDeque<>(); final List<Request> requests = new ArrayList<>(); Runnable beforeReturn; boolean acceptAll;
        public GrowthTrackClient.Response request(String method, String path, Map<String, String> headers, String body) throws IOException {
            requests.add(new Request(method, path, headers, body));
            if (beforeReturn != null) beforeReturn.run();
            if (acceptAll && path.equals("/partner-ingest")) try { return ok(new JSONObject(body).getJSONArray("sessions").length(), 0, new JSONArray()); } catch (Exception e) { throw new IOException(); }
            Object response = responses.removeFirst(); if (response instanceof IOException) throw (IOException) response;
            return (GrowthTrackClient.Response) response;
        }
    }
    static class Fixture {
        final Storage storage = new Storage(); final Http http = new Http(); final Clock clock = new Clock(); final GrowthTrackClient client;
        Fixture(boolean routines) throws Exception {
            client = new GrowthTrackClient(storage, http, clock); client.begin(routines);
            String state = storage.saved.getJSONObject("pending").getString("state");
            http.responses.add(token(R, routines)); client.callback(GrowthTrackProtocol.REDIRECT + "?code=" + CODE + "&state=" + state);
            http.requests.clear();
        }
        void approve(String... ids) throws Exception { List<JSONObject> list = new ArrayList<>(); for (String id : ids) list.add(payload(id)); client.approve(list); }
    }
    static GrowthTrackClient.Response token(String refresh, boolean routines) throws Exception {
        return new GrowthTrackClient.Response(200, new JSONObject().put("access_token", A).put("refresh_token", refresh)
            .put("token_type", "Bearer").put("expires_in", 900).put("scope", "sessions:write" + (routines ? " routines:read" : "")).toString());
    }
    static GrowthTrackClient.Response ok(int accepted, int duplicates, JSONArray rejected) throws Exception { return new GrowthTrackClient.Response(200, new JSONObject().put("accepted", accepted).put("duplicates", duplicates).put("rejected", rejected).toString()); }
    static Model.Sess session(String id) {
        Model.Sess s = new Model.Sess(); s.id = id; s.ts = 1_700_000_000_000L; s.durSec = 120; s.routineName = "Fictional fixture"; s.completed = true; s.recordedStartMs = s.ts; s.recordedEndMs = s.ts + s.durSec * 1000L;
        s.peakKpa = 33.8639; s.netTupSec = 60.8; s.grossTupSec = 110.0; s.note = "PRIVATE NOTE"; s.afterLenAbsCm = 999.0; s.afterGirAbsCm = 999.0; s.afterLenCm = 1.0;
        return s;
    }
    static JSONObject payload(String id) throws Exception { AsRun run = new AsRun(); run.rows.add(row(AsRun.PLAN, 0, 120_000, 0)); return GrowthTrackSession.draft(session(id), run).payload(); }
    static AsRun.Row row(int kind, long t0, long t1, int pos) { AsRun.Row r = new AsRun.Row(); r.kind = kind; r.t0 = t0; r.t1 = t1; r.pos = pos; r.setId = "set" + pos; r.up = 20; r.uhWire = 20; r.uhReq = 20; r.lh = 5; return r; }

    @Test void pkceMatchesRfc7636() { assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GrowthTrackProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")); }
    @Test void authorizationUsesOnlyPublicClientAndExactRedirect() {
        GrowthTrackProtocol.Pending p = GrowthTrackProtocol.pending(true, 1000);
        String url = p.authorizeUrl(); assertTrue(url.startsWith(GrowthTrackProtocol.API + "/oauth-authorize?"));
        assertTrue(url.contains("client_id=" + GrowthTrackProtocol.CLIENT_ID)); assertTrue(url.contains("code_challenge_method=S256"));
        assertFalse(url.contains("secret")); assertNotEquals(p.state, p.verifier); assertEquals(64, p.state.length());
    }
    @Test void callbackRejectsWrongStateEndpointDuplicateAndMissingParameters() {
        GrowthTrackProtocol.Pending p = GrowthTrackProtocol.pending(false, 1000);
        String suffix = "?code=" + CODE + "&state=" + p.state;
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackProtocol.callback(GrowthTrackProtocol.REDIRECT + "?code=" + CODE + "&state=wrong", p, 1001));
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackProtocol.callback("gt-openpump://evil/callback" + suffix, p, 1001));
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackProtocol.callback(GrowthTrackProtocol.REDIRECT + suffix + "&state=" + p.state, p, 1001));
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackProtocol.callback(GrowthTrackProtocol.REDIRECT + suffix + "&access_token=bad", p, 1001));
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackProtocol.callback(GrowthTrackProtocol.REDIRECT + suffix + "#fragment", p, 1001));
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackProtocol.callback(GrowthTrackProtocol.REDIRECT + suffix, p, 1_000 + GrowthTrackProtocol.AUTH_LIFETIME_MS + 1));
    }
    @Test void denialAndCancelNeverExchangeOrSend() throws Exception {
        Storage storage = new Storage(); Http http = new Http(); Clock clock = new Clock(); GrowthTrackClient client = new GrowthTrackClient(storage, http, clock);
        client.begin(false); String state = storage.saved.getJSONObject("pending").getString("state");
        client.callback(GrowthTrackProtocol.REDIRECT + "?error=access_denied&state=" + state);
        assertFalse(client.snapshot().connected); assertFalse(client.snapshot().pending); assertTrue(http.requests.isEmpty());
        client.begin(false); client.cancelAuthorization(); assertFalse(client.snapshot().pending); assertTrue(http.requests.isEmpty());
    }
    @Test void forgedCallbackPreservesPendingRequestAndSendsNothing() throws Exception {
        Storage store = new Storage(); Http http = new Http(); GrowthTrackClient c = new GrowthTrackClient(store, http, new Clock()); c.begin(false);
        assertThrows(IOException.class, () -> c.callback(GrowthTrackProtocol.REDIRECT + "?code=" + CODE + "&state=forged"));
        assertTrue(c.snapshot().pending); assertTrue(http.requests.isEmpty());
    }
    @Test void lostCodeExchangeRequiresFreshApprovalAndIsNotReplayed() throws Exception {
        Storage storage = new Storage(); Http http = new Http(); Clock clock = new Clock(); GrowthTrackClient c = new GrowthTrackClient(storage, http, clock); c.begin(false);
        String callback = GrowthTrackProtocol.REDIRECT + "?code=" + CODE + "&state=" + storage.saved.getJSONObject("pending").getString("state");
        http.responses.add(new IOException()); assertThrows(IOException.class, () -> c.callback(callback));
        assertFalse(c.snapshot().pending); assertFalse(c.snapshot().connected); assertThrows(IOException.class, () -> c.callback(callback)); assertEquals(1, http.requests.size());
    }
    @Test void mapperAllowlistUsesStartTimestampObservedPeakAndNoPrivateFields() throws Exception {
        JSONObject p = payload("stable"); assertEquals("op_stable", p.getString("external_session_id")); assertEquals("2023-11-14T22:13:20.000Z", p.getString("started_at"));
        assertEquals("2023-11-14T22:15:20.000Z", p.getString("completed_at")); assertEquals(10, p.getDouble("pressure_peak_inhg"), .000001); assertEquals(60, p.getInt("time_under_tension_seconds"));
        for (String key : Arrays.asList("note", "measurements", "afterLenAbsCm", "user_id", "email", "link_id", "heat", "vibration", "pressure_inhg")) assertFalse(p.has(key), key);
        assertFalse(p.toString().contains("PRIVATE")); assertFalse(p.toString().contains("999"));
    }
    @Test void simulatedUnidentifiedAndInvalidTimeRecordsAreNotExportable() {
        Model.Sess s = session("x"); s.sim = true; assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(s, null));
        s.sim = false; s.id = ""; assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(s, null));
        s.id = "x"; s.durSec = 0; assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(s, null));
    }
    @Test void oneSittingContainsFixedAutomaticMethodsAndExcludesPauseAndUnconfirmedRows() throws Exception {
        AsRun run = new AsRun(); run.rows.add(row(AsRun.PLAN, 0, 30_000, 0)); run.rows.add(row(AsRun.REST, 30_000, 40_000, 0)); run.rows.add(row(AsRun.OVERRIDE, 40_000, 60_000, 0)); run.rows.add(row(AsRun.PLAN, 60_000, 90_000, 1));
        AsRun.Row unconfirmed = row(AsRun.OVERRIDE, 90_000, 120_000, 1); unconfirmed.confirmed = false; run.rows.add(unconfirmed);
        run.rows.get(0).uhWire = 6; run.rows.get(0).lh = 4; run.rows.get(2).uhWire = 6; run.rows.get(2).lh = 4; run.rows.get(3).uhWire = 31;
        GrowthTrackSession.Draft d = GrowthTrackSession.draft(session("mixed"), run); assertEquals(2, d.parts.size());
        JSONObject p = d.payload(); assertEquals("milking", p.getJSONArray("sets").getJSONObject(0).getString("method")); assertEquals(50, p.getJSONArray("sets").getJSONObject(0).getInt("duration_seconds"));
        assertEquals(30, p.getJSONArray("sets").getJSONObject(1).getInt("duration_seconds")); assertEquals("rip", p.getJSONArray("sets").getJSONObject(1).getString("method")); assertFalse(p.getJSONArray("sets").getJSONObject(0).has("pressure_inhg"));
    }
    @Test void inconsistentRecordingIsRejectedRatherThanInventingDurations() {
        AsRun run = new AsRun(); run.rows.add(row(AsRun.PLAN, 0, 60_000, 0)); run.rows.add(row(AsRun.PLAN, 50_000, 80_000, 1));
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(session("overlap"), run));
    }
    @Test void sendRequiresExplicitApprovalAndNoTrafficOnStartup() throws Exception {
        Fixture f = new Fixture(false); f.client.sendApproved(); assertTrue(f.http.requests.isEmpty());
        GrowthTrackClient restarted = new GrowthTrackClient(f.storage, f.http, f.clock); assertTrue(restarted.snapshot().connected); assertTrue(f.http.requests.isEmpty());
    }
    @Test void durableApprovedSnapshotsAreDetachedFromMutableReviewObjects() throws Exception {
        Fixture f = new Fixture(false); JSONObject p = payload("stable"); f.client.approve(Arrays.asList(p)); p.put("routine_name", "changed later");
        GrowthTrackClient restarted = new GrowthTrackClient(f.storage, f.http, f.clock); f.http.responses.add(ok(1, 0, new JSONArray())); restarted.resumeApproved();
        assertEquals("Fictional fixture", new JSONObject(f.http.requests.get(0).body).getJSONArray("sessions").getJSONObject(0).getString("routine_name"));
    }
    @Test void duplicateSendUsesExactlyTheSameExternalId() throws Exception {
        Fixture f = new Fixture(false); f.approve("stable"); f.http.responses.add(ok(1, 0, new JSONArray())); f.client.sendApproved();
        f.approve("stable"); f.http.responses.add(ok(0, 1, new JSONArray())); f.client.sendApproved();
        assertEquals(f.http.requests.get(0).body, f.http.requests.get(1).body); assertEquals(0, f.client.snapshot().queued); assertTrue(f.client.receipt("op_stable").startsWith("Received"));
    }
    @Test void offlineRetrySurvivesRestartAndHonorsBackoff() throws Exception {
        Fixture f = new Fixture(false); f.approve("offline"); f.http.responses.add(new IOException()); assertThrows(IOException.class, () -> f.client.sendApproved());
        String first = f.http.requests.get(0).body; assertEquals(1, f.client.snapshot().queued); assertEquals(f.clock.time + 5000, f.client.snapshot().retryAt);
        GrowthTrackClient restarted = new GrowthTrackClient(f.storage, f.http, f.clock); assertThrows(IOException.class, () -> restarted.resumeApproved()); assertEquals(1, f.http.requests.size());
        f.clock.time += 5001; f.http.responses.add(ok(0, 1, new JSONArray())); restarted.resumeApproved(); assertEquals(first, f.http.requests.get(1).body); assertEquals(0, restarted.snapshot().queued);
    }
    @Test void repeatedServerFailuresBackOffAndHonorRetryAfter() throws Exception {
        Fixture f = new Fixture(false); f.approve("server"); f.http.responses.add(new GrowthTrackClient.Response(503, "", "30")); assertThrows(IOException.class, () -> f.client.sendApproved()); assertEquals(f.clock.time + 30_000, f.client.snapshot().retryAt);
        f.clock.time += 30_001; f.http.responses.add(new GrowthTrackClient.Response(503, "")); assertThrows(IOException.class, () -> f.client.resumeApproved()); assertEquals(f.clock.time + 10_000, f.client.snapshot().retryAt);
    }
    @Test void unauthorizedRefreshesOnlyOnceAndRotatesBeforeResendingTheSameBatch() throws Exception {
        Fixture f = new Fixture(false); f.approve("auth"); f.http.responses.add(new GrowthTrackClient.Response(401, "{}")); f.http.responses.add(token(R2, false)); f.http.responses.add(ok(1, 0, new JSONArray())); f.client.sendApproved();
        assertEquals(3, f.http.requests.size()); assertEquals("/oauth-token", f.http.requests.get(1).path); assertEquals(f.http.requests.get(0).body, f.http.requests.get(2).body); assertEquals(R2, f.storage.saved.getJSONObject("tokens").getString("refresh_token"));
        assertFalse(f.storage.saved.optBoolean("refreshing")); assertFalse(f.http.requests.get(0).headers.containsKey("x-partner-key"));
    }
    @Test void aSecondUnauthorizedResponseDisconnectsWithoutAnInfiniteRefreshLoop() throws Exception {
        Fixture f = new Fixture(false); f.approve("auth"); f.http.responses.add(new GrowthTrackClient.Response(401, "{}")); f.http.responses.add(token(R2, false)); f.http.responses.add(new GrowthTrackClient.Response(401, "{}"));
        assertThrows(IOException.class, () -> f.client.sendApproved()); assertEquals(3, f.http.requests.size()); assertFalse(f.client.snapshot().connected); assertEquals(0, f.client.snapshot().queued);
    }
    @Test void ambiguousRotatingRefreshRequiresRelinkRatherThanReplaying() throws Exception {
        Fixture f = new Fixture(false); f.approve("refresh"); f.clock.time += 900_000; f.http.responses.add(new IOException()); assertThrows(IOException.class, () -> f.client.sendApproved());
        assertFalse(f.client.snapshot().connected); assertEquals(0, f.client.snapshot().queued); assertEquals(1, f.http.requests.size()); assertThrows(IOException.class, () -> f.client.resumeApproved()); assertEquals(1, f.http.requests.size());
    }
    @Test void restartDuringRefreshDropsTheOldCredentialAndQueuedApprovals() throws Exception {
        Fixture f = new Fixture(false); f.approve("refresh"); f.storage.saved.put("refreshing", true);
        GrowthTrackClient restarted = new GrowthTrackClient(f.storage, f.http, f.clock); assertFalse(restarted.snapshot().connected); assertEquals(0, restarted.snapshot().queued); assertTrue(f.http.requests.isEmpty());
    }
    @Test void partialResultsRecordOnlyKnownCompleteOutcomesAndRetainTransientFailures() throws Exception {
        Fixture f = new Fixture(false); f.approve("accepted", "duplicate", "temporary");
        f.http.responses.add(ok(1, 1, new JSONArray().put(new JSONObject().put("external_session_id", "op_temporary").put("reason", "Insert failed")))); f.client.sendApproved();
        assertEquals(1, f.client.snapshot().queued); assertTrue(f.client.receipt("op_accepted").startsWith("Received")); assertEquals("", f.client.receipt("op_temporary"));
    }
    @Test void permanentPerSessionRejectionIsVisibleAndNotRetriedAutomatically() throws Exception {
        Fixture f = new Fixture(false); f.approve("bad"); f.http.responses.add(ok(0, 0, new JSONArray().put(new JSONObject().put("external_session_id", "op_bad").put("reason", "Unknown method")))); f.client.sendApproved();
        assertEquals(0, f.client.snapshot().queued); assertTrue(f.client.receipt("op_bad").startsWith("Needs review"));
    }
    @Test void incompleteOrForeignResultDoesNotMarkAnythingSent() throws Exception {
        Fixture f = new Fixture(false); f.approve("mine"); f.http.responses.add(ok(0, 0, new JSONArray().put(new JSONObject().put("external_session_id", "op_someone_else").put("reason", "bad"))));
        assertThrows(IOException.class, () -> f.client.sendApproved()); assertEquals(1, f.client.snapshot().queued); assertEquals("", f.client.receipt("op_mine"));
    }
    @Test void oversizeResponseSplitsWithoutChangingStableIds() throws Exception {
        Fixture f = new Fixture(false); f.approve("one", "two"); f.http.responses.add(new GrowthTrackClient.Response(413, "{}")); f.http.responses.add(ok(1, 0, new JSONArray())); f.http.responses.add(ok(1, 0, new JSONArray())); f.client.sendApproved();
        assertEquals(2, new JSONObject(f.http.requests.get(0).body).getJSONArray("sessions").length()); assertEquals(1, new JSONObject(f.http.requests.get(1).body).getJSONArray("sessions").length()); assertEquals(0, f.client.snapshot().queued);
    }
    @Test void boundedBatchingCountsUtf8BytesAndNeverExceedsLimits() throws Exception {
        Fixture f = new Fixture(false); List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < 105; i++) { JSONObject s = payload("batch" + i); s.put("routine_name", "å".repeat(150)); s.remove("method"); s.put("total_duration_seconds", 1000); JSONArray sets = new JSONArray(); for (int j = 0; j < 500; j++) sets.put(new JSONObject().put("set_index", j + 1).put("method", "rapid_interval_vacuum_extending").put("duration_seconds", 1)); s.put("sets", sets); list.add(s); }
        f.client.approve(list); f.http.acceptAll = true; f.client.sendApproved(); assertTrue(f.http.requests.size() > 2);
        for (Request r : f.http.requests) { assertTrue(r.body.getBytes(StandardCharsets.UTF_8).length <= GrowthTrackProtocol.MAX_BYTES); assertTrue(new JSONObject(r.body).getJSONArray("sessions").length() <= GrowthTrackProtocol.BATCH_SIZE); }
        assertEquals(0, f.client.snapshot().queued);
    }
    @Test void approvalRejectsPrivateFieldsBeforeAnyNetworkRequest() throws Exception {
        Fixture f = new Fixture(false); JSONObject s = payload("private"); s.put("note", "must never escape"); assertThrows(IOException.class, () -> f.client.approve(Arrays.asList(s))); assertTrue(f.http.requests.isEmpty());
    }
    @Test void cancelAndDisconnectStopFutureRequestsAndClearAuthority() throws Exception {
        Fixture f = new Fixture(false); f.approve("cancel"); f.http.beforeReturn = () -> f.client.cancelRequests(); f.http.responses.add(ok(1, 0, new JSONArray()));
        assertThrows(IOException.class, () -> f.client.sendApproved()); f.client.cancelApproved(); assertEquals(0, f.client.snapshot().queued); assertEquals("", f.client.receipt("op_cancel"));
        f.client.disconnect(); assertFalse(f.client.snapshot().connected); assertFalse(f.storage.saved.has("tokens")); assertThrows(IOException.class, () -> f.client.resumeApproved()); assertEquals(1, f.http.requests.size());
    }
    @Test void relinkingCannotCarryAnOldAccountsApprovedPayloadsOrReceipts() throws Exception {
        Fixture f = new Fixture(false); f.approve("previous"); f.client.begin(true); assertFalse(f.client.snapshot().connected); assertEquals(0, f.client.snapshot().queued); assertTrue(f.client.snapshot().pending); assertEquals("", f.client.receipt("op_previous"));
    }
    @Test void storageFailureFailsClosedBeforeUpload() throws Exception {
        Fixture f = new Fixture(false); f.storage.fail = true; assertThrows(IOException.class, () -> f.approve("storage")); assertThrows(IOException.class, () -> f.client.resumeApproved()); assertFalse(f.client.snapshot().connected); assertTrue(f.http.requests.isEmpty());
    }
    @Test void serializedSendsCannotRefreshInParallelOrDuplicateTheQueue() throws Exception {
        Fixture f = new Fixture(false); f.approve("parallel"); f.clock.time += 900_000; f.http.responses.add(token(R2, false)); f.http.responses.add(ok(1, 0, new JSONArray()));
        var executor = Executors.newFixedThreadPool(2);
        try { List<Callable<Void>> calls = Arrays.asList(() -> { f.client.sendApproved(); return null; }, () -> { f.client.sendApproved(); return null; }); for (var future : executor.invokeAll(calls)) future.get(5, TimeUnit.SECONDS); }
        finally { executor.shutdownNow(); }
        assertEquals(2, f.http.requests.size()); assertEquals(0, f.client.snapshot().queued);
    }
    @Test void routinePermissionIsSeparateAndUnsupportedFormatsAreRejected() throws Exception {
        Fixture without = new Fixture(false); assertThrows(IOException.class, () -> without.client.routines(null)); assertTrue(without.http.requests.isEmpty());
        Fixture with = new Fixture(true); with.http.responses.add(new GrowthTrackClient.Response(200, "{\"format\":\"pet-routine\",\"schema_version\":1,\"routines\":[]}")); assertThrows(IOException.class, () -> with.client.routines(null));
        with.http.responses.add(new GrowthTrackClient.Response(200, "{\"format\":\"gt-routine\",\"schema_version\":2,\"routines\":[]}")); assertThrows(IOException.class, () -> with.client.routines(null));
        with.http.responses.add(new GrowthTrackClient.Response(200, "{\"format\":\"gt-routine\",\"schema_version\":1,\"routines\":[],\"new_optional_field\":true}")); assertEquals(0, with.client.routines(null).getJSONArray("routines").length());
    }

    @Test void fixedHoldRulesHonorEveryInclusiveBoundaryBeforeRounding() {
        assertEquals("milking", GrowthTrackSession.methodForHoldMillis(0.1));
        assertEquals("milking", GrowthTrackSession.methodForHoldMillis(5999.9));
        assertEquals("milking", GrowthTrackSession.methodForHoldMillis(6000));
        assertEquals("rip", GrowthTrackSession.methodForHoldMillis(6000.1));
        assertEquals("rip", GrowthTrackSession.methodForHoldMillis(30000));
        assertEquals("interval_pumping", GrowthTrackSession.methodForHoldMillis(30000.1));
        assertEquals("interval_pumping", GrowthTrackSession.methodForHoldMillis(120000));
        assertEquals("static_pumping", GrowthTrackSession.methodForHoldMillis(120000.1));
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.methodForHoldMillis(invalid));
    }
    @Test void actualAcknowledgedHoldDrivesLabelNotRequestedHoldSetLengthOrRelease() throws Exception {
        AsRun run = new AsRun(); AsRun.Row r = row(AsRun.PLAN, 0, 120_000, 0);
        r.uhWire = 6; r.uhReq = 200; r.lh = 80; run.rows.add(r);
        JSONObject p = GrowthTrackSession.draft(session("wire"), run).payload();
        assertEquals("milking", p.getJSONArray("sets").getJSONObject(0).getString("method"));
        assertEquals(120, p.getInt("total_duration_seconds"));
        assertEquals(120, p.getJSONArray("sets").getJSONObject(0).getInt("duration_seconds"));
        assertEquals(60, p.getInt("time_under_tension_seconds"));
    }
    @Test void liveHoldChangesWithinOneSetRemainMixedWithoutSplittingTheSitting() throws Exception {
        Model.Sess s = session("varying"); s.durSec = 1000; s.recordedEndMs = s.recordedStartMs + 1000000L;
        AsRun run = new AsRun(); int[] holds = {6, 7, 30, 31, 120, 121};
        for (int i = 0; i < holds.length; i++) { AsRun.Row r = row(AsRun.OVERRIDE, i * 150_000L, i * 150_000L + (holds[i] + 5) * 1000L, 0); r.uhWire = holds[i]; run.rows.add(r); }
        JSONObject p = GrowthTrackSession.draft(s, run).payload();
        assertEquals("op_varying", p.getString("external_session_id")); assertEquals(6, p.getJSONArray("sets").length());
        String[] expected = {"milking", "rip", "rip", "interval_pumping", "interval_pumping", "static_pumping"};
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], p.getJSONArray("sets").getJSONObject(i).getString("method"));
    }
    @Test void frozenLengthTrackOverridesEveryHoldIncludingUnknownHoldAndSurvivesRoutineDeletion() throws Exception {
        Model.Sess s = session("length"); s.durSec = 1000; s.recordedEndMs = s.recordedStartMs + 1000000L;
        AsRun run = new AsRun(); run.snap = new Model.AsRunSnapshot(); run.snap.trainerTrack = Plan.TRACK_LENGTH;
        int[] holds = {0, 6, 30, 120, 255};
        for (int i = 0; i < holds.length; i++) { AsRun.Row r = row(AsRun.PLAN, i * 150_000L, (i + 1) * 150_000L, i); r.uhWire = holds[i]; run.rows.add(r); }
        run.snap = Model.AsRunSnapshot.fromJson(run.snap.toJson());
        JSONObject p = GrowthTrackSession.draft(s, run, new Model()).payload();
        for (int i = 0; i < p.getJSONArray("sets").length(); i++) assertEquals("length_pumping", p.getJSONArray("sets").getJSONObject(i).getString("method"));
        s.asRunSnapshot = run.snap; JSONObject withoutRows = GrowthTrackSession.draft(s, null).payload();
        assertEquals("length_pumping", withoutRows.getString("method")); assertFalse(withoutRows.has("sets"));
    }
    @Test void snapshotTrackMigrationPreservesUnknownVersusKnownUnmarkedAndRecordedLength() throws Exception {
        Model.AsRunSnapshot old = Model.AsRunSnapshot.fromJson(new JSONObject("{\"rid\":\"old\",\"stages\":[]}"));
        assertEquals(-1, old.trainerTrack); assertFalse(old.toJson().has("trainerTrack"));
        Model.Routine routine = new Model.Routine(); routine.id = "new"; routine.trainerTrack = Plan.TRACK_LENGTH;
        Model.AsRunSnapshot frozen = Model.AsRunSnapshot.of(routine, new Model()); routine.trainerTrack = 0;
        assertEquals(Plan.TRACK_LENGTH, Model.AsRunSnapshot.fromJson(frozen.toJson()).trainerTrack);
        assertEquals(0, Model.AsRunSnapshot.fromJson(Model.AsRunSnapshot.of(routine, new Model()).toJson()).trainerTrack);
    }
    @Test void legacyStructuredLengthMarkerRequiresAnUnchangedMatchingSnapshot() throws Exception {
        Model model = new Model(); Model.Routine routine = new Model.Routine(); routine.id = "r"; routine.trainerTrack = Plan.TRACK_LENGTH; model.routines.add(routine);
        AsRun run = new AsRun(); run.rows.add(row(AsRun.PLAN, 0, 120_000, 0)); run.snap = Model.AsRunSnapshot.of(routine, model); run.snap.trainerTrack = -1;
        Model.Sess s = session("legacy"); s.routineId = "r";
        assertEquals("length_pumping", GrowthTrackSession.draft(s, run, model).parts.get(0).method);
        Model.Stage added = new Model.Stage(); added.name = "Later edit"; routine.stages.add(added);
        assertEquals("rip", GrowthTrackSession.draft(s, run, model).parts.get(0).method);
    }
    @Test void freeTextNamesAndCountedTrackDoNotInventAPhysicalLengthDesignation() {
        Model.Sess s = session("name"); s.routineName = "Length pumping"; s.countedTrack = Plan.TRACK_LENGTH;
        AsRun run = new AsRun(); run.rows.add(row(AsRun.PLAN, 0, 120_000, 0));
        assertEquals("rip", GrowthTrackSession.draft(s, run).parts.get(0).method);
    }
    @Test void noRecordingZeroHoldAndNoConfirmedWorkCannotInventATechnique() {
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(session("old"), null));
        AsRun run = new AsRun(); AsRun.Row r = row(AsRun.PLAN, 0, 120_000, 0); r.uhWire = 0; run.rows.add(r);
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(session("zero"), run));
        r.uhWire = 6; r.confirmed = false;
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(session("unconfirmed"), run));
        r.confirmed = true; r.kind = AsRun.REST;
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(session("rest"), run));
    }
    @Test void subsecondWorkKeepsItsMethodWithoutFabricatingASecond() throws Exception {
        Model.Sess s = session("fraction"); s.durSec = 1; s.recordedEndMs = s.recordedStartMs + 1000L; s.netTupSec = null;
        AsRun run = new AsRun(); AsRun.Row r = row(AsRun.PLAN, 0, 600, 0); r.uhWire = 6; run.rows.add(r);
        JSONObject p = GrowthTrackSession.draft(s, run).payload();
        assertTrue(p.getJSONArray("sets").getJSONObject(0).isNull("duration_seconds"));
        Fixture f = new Fixture(false); f.client.approve(Arrays.asList(p)); assertEquals(1, f.client.snapshot().queued);
        s.durSec = 0; assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(s, run));
    }
    @Test void grossSealedTimeIsNeverSentAsUnderTensionWhenNetWasNotRecorded() throws Exception {
        Model.Sess s = session("gross"); s.netTupSec = null; s.grossTupSec = 120.0;
        AsRun run = new AsRun(); run.rows.add(row(AsRun.PLAN, 0, 120_000, 0));
        assertFalse(GrowthTrackSession.draft(s, run).payload().has("time_under_tension_seconds"));
    }
    @Test void transientPerItemFailuresUseIncreasingBackoffAcrossRetries() throws Exception {
        Fixture f = new Fixture(false); f.approve("temporary");
        JSONArray rejected = new JSONArray().put(new JSONObject().put("external_session_id", "op_temporary").put("reason", "Insert failed"));
        f.http.responses.add(ok(0, 0, rejected)); f.client.sendApproved(); assertEquals(f.clock.time + 5000, f.client.snapshot().retryAt);
        f.clock.time += 5001; f.http.responses.add(ok(0, 0, rejected)); f.client.resumeApproved(); assertEquals(f.clock.time + 10000, f.client.snapshot().retryAt);
    }
    @Test void invalidTypedPayloadFieldsFailBeforeNetworkAndDoNotCreateApproval() throws Exception {
        for (String field : Arrays.asList("total_duration_seconds", "time_under_tension_seconds", "pressure_peak_inhg", "started_at", "completed_at", "routine_name")) {
            Fixture f = new Fixture(false); JSONObject p = payload("bad");
            if (field.endsWith("seconds")) p.put(field, 1.5); else if (field.equals("pressure_peak_inhg")) p.put(field, 30); else if (field.equals("routine_name")) p.put(field, "x".repeat(201)); else p.put(field, "invalid");
            assertThrows(IOException.class, () -> f.client.approve(Arrays.asList(p)), field);
            assertTrue(f.http.requests.isEmpty()); assertEquals(0, f.client.snapshot().queued);
        }
        Fixture f = new Fixture(false); JSONObject p = payload("index"); p.getJSONArray("sets").getJSONObject(0).put("set_index", 0);
        assertThrows(IOException.class, () -> f.client.approve(Arrays.asList(p))); assertTrue(f.http.requests.isEmpty());
    }
    @Test void goneGrantClearsAuthorityAndForbiddenPermissionPreservesApprovalForExplicitRelink() throws Exception {
        Fixture gone = new Fixture(false); gone.approve("gone"); gone.http.responses.add(new GrowthTrackClient.Response(410, "{}"));
        assertThrows(IOException.class, () -> gone.client.sendApproved()); assertFalse(gone.client.snapshot().connected); assertEquals(0, gone.client.snapshot().queued);
        Fixture denied = new Fixture(false); denied.approve("denied"); denied.http.responses.add(new GrowthTrackClient.Response(403, "{}"));
        assertThrows(IOException.class, () -> denied.client.sendApproved()); assertEquals(1, denied.client.snapshot().queued); assertEquals(1, denied.http.requests.size());
    }
    @Test void supportedRoutineDetailUsesReadScopeAndDoesNotChangeSessionQueue() throws Exception {
        Fixture f = new Fixture(true); String id = "00000000-0000-4000-8000-000000000001";
        JSONObject detail = new JSONObject().put("format", "gt-routine").put("schema_version", 1)
            .put("routine", new JSONObject().put("external_id", id).put("steps", new JSONArray()));
        f.http.responses.add(new GrowthTrackClient.Response(200, detail.toString())); assertEquals(id, f.client.routines(id).getJSONObject("routine").getString("external_id"));
        assertEquals("GET", f.http.requests.get(0).method); assertNull(f.http.requests.get(0).body);
        assertEquals("no-store", f.http.requests.get(0).headers.get("Cache-Control")); assertEquals(0, f.client.snapshot().queued);
        assertThrows(IOException.class, () -> f.client.routines("../../tokens")); assertEquals(1, f.http.requests.size());
    }
    @Test void stoppedUpperHoldUsesClippedDurationAndNeverItsLongerSetting() {
        AsRun run = new AsRun(); AsRun.Row r = row(AsRun.PLAN, 0, 6000, 0); r.uhWire = 121; run.rows.add(r);
        GrowthTrackSession.Part p = GrowthTrackSession.draft(session("clipped"), run).parts.get(0);
        assertEquals("milking", p.method); assertEquals(6000, p.holdMillis);
        r.t1 = 30001; assertEquals("interval_pumping", GrowthTrackSession.draft(session("clipped"), run).parts.get(0).method);
    }
    @Test void completedCyclesAndInterruptedLastCycleCanHaveDifferentMethods() {
        AsRun run = new AsRun(); AsRun.Row r = row(AsRun.PLAN, 0, 26000, 0); r.uhWire = 20; r.lh = 5; run.rows.add(r);
        GrowthTrackSession.Draft d = GrowthTrackSession.draft(session("cycles"), run);
        assertEquals(2, d.parts.size()); assertEquals("rip", d.parts.get(0).method); assertEquals(25000, d.parts.get(0).recordedMillis);
        assertEquals("milking", d.parts.get(1).method); assertEquals(1000, d.parts.get(1).holdMillis);
    }
    @Test void zeroReleaseIsOneContinuousRecordedHold() {
        Model.Sess s = session("continuous"); s.durSec = 150; s.recordedEndMs = s.recordedStartMs + 150000L;
        AsRun run = new AsRun(); AsRun.Row r = row(AsRun.PLAN, 0, 121000, 0); r.uhWire = 6; r.lh = 0; run.rows.add(r);
        assertEquals("static_pumping", GrowthTrackSession.draft(s, run).parts.get(0).method);
    }
    static AsRun stitch(boolean frozen) {
        AsRun run = new AsRun();
        AsRun.Row first = row(AsRun.PLAN, 0, 255000, 0); first.uhWire = 255; first.uhReq = 255; first.lh = 1; first.lo = first.up - 1;
        AsRun.Row last = row(AsRun.PLAN, 255000, 360000, 0); last.ordinal = 1; last.uhWire = 45; last.uhReq = 45; last.lh = 60;
        if (frozen) { first.cyclePart = 1; last.cyclePart = 0; }
        run.rows.add(first); run.rows.add(last); return run;
    }
    @Test void verifiedStitchKeepsOneLongHoldWithReleaseExcludedFromLabel() throws Exception {
        Model.Sess s = session("stitch"); s.durSec = 360; s.recordedEndMs = s.recordedStartMs + 360000L;
        GrowthTrackSession.Draft d = GrowthTrackSession.draft(s, stitch(true));
        assertEquals(1, d.parts.size()); assertEquals(300000, d.parts.get(0).holdMillis); assertEquals("static_pumping", d.parts.get(0).method);
        assertEquals(360, d.payload().getJSONArray("sets").getJSONObject(0).getInt("duration_seconds"));
    }
    @Test void oldSnapshotCanProveStitchButAnUnknownBoundaryCannot() {
        Model.Sess s = session("oldStitch"); s.durSec = 360; s.recordedEndMs = s.recordedStartMs + 360000L; AsRun run = stitch(false);
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(s, run));
        Model model = new Model(); Model.Set set = Model.Set.fixed("set0", "old long hold", 20, 0, 300, 60, 50, 360); model.sets.add(set);
        Model.Routine routine = new Model.Routine(); Model.Stage stage = new Model.Stage(); stage.setIds.add(set.id); routine.stages.add(stage);
        run.snap = Model.AsRunSnapshot.of(routine, model);
        assertEquals(1, GrowthTrackSession.draft(s, run).parts.size()); assertEquals(300000, GrowthTrackSession.draft(s, run).parts.get(0).holdMillis);
    }
    @Test void changedOrUnconfirmedStitchFailsClosedAndStoppedChunkIsClipped() {
        Model.Sess s = session("badStitch"); s.durSec = 360; s.recordedEndMs = s.recordedStartMs + 360000L; AsRun run = stitch(true); run.rows.get(1).confirmed = false; final AsRun unconfirmed = run;
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(s, unconfirmed));
        run = stitch(true); run.rows.get(1).up = 21; final AsRun changed = run;
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(s, changed));
        run = stitch(true); run.rows.remove(1); run.rows.get(0).t1 = 6000;
        assertEquals("milking", GrowthTrackSession.draft(s, run).parts.get(0).method);
    }
    @Test void pausedOrLostPhaseCannotBeInventedButStructuredLengthStillOverrides() {
        for (int kind : new int[]{AsRun.HOLD, AsRun.LOST}) {
            AsRun run = new AsRun(); run.rows.add(row(AsRun.PLAN, 0, 20000, 0)); run.rows.add(row(kind, 20000, 30000, 0)); run.rows.add(row(AsRun.PLAN, 30000, 50000, 0));
            assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(session("gap"), run));
            run.snap = new Model.AsRunSnapshot(); run.snap.trainerTrack = Plan.TRACK_LENGTH;
            for (GrowthTrackSession.Part p : GrowthTrackSession.draft(session("gap"), run).parts) assertEquals("length_pumping", p.method);
        }
    }
    @Test void frozenStitchMarkerRoundTripsAndLegacyRemainsUnknown() throws Exception {
        AsRun.Row old = AsRun.Row.fromJson(new JSONObject()); assertEquals(-1, old.cyclePart); assertFalse(old.toJson().has("cyclePart"));
        AsRun run = new AsRun(); Model.Preset preset = new Model.Preset(); preset.cyclePart = true;
        AsRun.Row now = run.open(AsRun.PLAN, 0, preset, 20, 20, 19, 255, 255, 1, 50);
        assertEquals(1, AsRun.Row.fromJson(now.toJson()).cyclePart);
        preset.cyclePart = false; assertEquals(0, run.open(AsRun.PLAN, 1000, preset, 20, 20, 0, 6, 6, 4, 50).cyclePart);
    }

    @Test void staleReviewCannotBeApprovedAfterAnAccountRelink() throws Exception {
        Fixture f = new Fixture(false); String old = f.client.snapshot().connectionId; f.approve("old");
        f.client.begin(false); assertEquals(0, f.client.snapshot().queued);
        String state = f.storage.saved.getJSONObject("pending").getString("state"); f.http.responses.add(token(R2, false));
        f.client.callback(GrowthTrackProtocol.REDIRECT + "?code=" + CODE + "&state=" + state); f.http.requests.clear();
        assertNotEquals(old, f.client.snapshot().connectionId);
        assertThrows(IOException.class, () -> f.client.approve(Arrays.asList(payload("stale")), old));
        assertEquals(0, f.client.snapshot().queued); assertTrue(f.http.requests.isEmpty());
        f.client.approve(Arrays.asList(payload("fresh")), f.client.snapshot().connectionId); assertEquals(1, f.client.snapshot().queued);
    }
    @Test void restoredQueueWithDifferentGrantIdentityIsDiscardedWithoutTraffic() throws Exception {
        Fixture f = new Fixture(false); f.approve("old"); f.storage.saved.put("approved_connection_id", "another-grant");
        GrowthTrackClient restored = new GrowthTrackClient(f.storage, f.http, f.clock);
        assertTrue(restored.snapshot().connected); assertEquals(0, restored.snapshot().queued); assertTrue(f.http.requests.isEmpty());
    }
    @Test void refreshRetainsGrantIdentityAndApprovedSnapshotsForTheSameAccount() throws Exception {
        Fixture f = new Fixture(false); String id = f.client.snapshot().connectionId; f.approve("refreshIdentity");
        f.clock.time += 901000; f.http.responses.add(token(R2, false)); f.http.responses.add(ok(1, 0, new JSONArray())); f.client.sendApproved();
        assertEquals(id, f.client.snapshot().connectionId); assertEquals(0, f.client.snapshot().queued); assertEquals(2, f.http.requests.size());
    }

    @Test void legacyTimestampMeaningAndRejoinedBoundsAreNeverGuessedEvenForLength() {
        Model.Sess s = session("unknownTime"); s.recordedStartMs = 0; s.recordedEndMs = 0;
        AsRun run = new AsRun(); run.rows.add(row(AsRun.PLAN, 0, 120000, 0)); run.snap = new Model.AsRunSnapshot(); run.snap.trainerTrack = Plan.TRACK_LENGTH;
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(s, run));
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackSession.draft(s, null));
    }
    @Test void explicitlyRecordedWallClockBoundsTakePrecedenceOverLegacyKeyAndElapsed() throws Exception {
        Model.Sess s = session("wallclock"); s.ts += 999000; s.recordedEndMs += 60000;
        AsRun run = new AsRun(); run.rows.add(row(AsRun.PLAN, 0, 120000, 0));
        JSONObject payload = GrowthTrackSession.draft(s, run).payload();
        assertEquals("2023-11-14T22:13:20.000Z", payload.getString("started_at"));
        assertEquals("2023-11-14T22:16:20.000Z", payload.getString("completed_at")); assertEquals(120, payload.getInt("total_duration_seconds"));
        Model.Sess restored = Model.Sess.fromJson(s.toJson()); assertEquals(s.recordedStartMs, restored.recordedStartMs); assertEquals(s.recordedEndMs, restored.recordedEndMs);
    }

}
