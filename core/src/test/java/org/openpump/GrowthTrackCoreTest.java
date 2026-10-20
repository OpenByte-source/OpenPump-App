package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The core side of the owner's 19 Oct 2026 decisions on Karl's GrowthTrack pull request: the
 * connection lifecycle, the main-thread snapshot, the switches, earlier sessions, resumed
 * sessions as updates. Fictional records, mocked transport and storage only: nothing here can
 * reach GrowthTrack.
 */
class GrowthTrackCoreTest {
    private static final long T0 = 1_700_000_000_000L;
    private static AsRun run(long ms) {
        AsRun r = new AsRun(); r.rows.add(GrowthTrackIntegrationTest.row(AsRun.PLAN, 0, ms, 0)); return r;
    }
    private static Model.Sess session(String id, long start, long durSec) {
        Model.Sess s = GrowthTrackIntegrationTest.session(id);
        s.ts = start; s.recordedStartMs = start; s.durSec = durSec; s.recordedEndMs = start + durSec * 1000L; return s;
    }
    /** A manual run as History files it (the "man" flag). Built from its JSON so the stage-flag
     *  wiring rule, which reads `.manual = true` as a Stage, is not confused by a session. */
    private static Model.Sess manualSession(String id) {
        try { return Model.Sess.fromJson(session(id, T0, 120).toJson().put("man", true)); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static GrowthTrackOutbox.AsRunLookup rows() {
        return s -> run(s.durSec * 1000L);
    }
    private static String connect(GrowthTrackClient c, GrowthTrackIntegrationTest.Storage storage, GrowthTrackIntegrationTest.Http http,
                                  boolean change, String earlier, GrowthTrackClient.Response token) throws Exception {
        if (change) c.beginChangeAccount(earlier); else c.begin(earlier);
        String state = storage.saved.getJSONObject("pending").getString("state");
        http.responses.add(token);
        c.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE + "&state=" + state);
        return c.snapshot().connectionId;
    }
    private static GrowthTrackClient.Response tokenFor(String refresh, String accountId, String email) throws Exception {
        JSONObject o = new JSONObject().put("access_token", GrowthTrackIntegrationTest.A).put("refresh_token", refresh)
            .put("token_type", "Bearer").put("expires_in", 900).put("scope", "sessions:write");
        if (accountId != null) o.put("account", new JSONObject().put("id", accountId).put("email", email));
        return new GrowthTrackClient.Response(200, o.toString());
    }
    private static JSONArray sentSessions(GrowthTrackIntegrationTest.Http http) throws Exception {
        JSONArray all = new JSONArray();
        for (GrowthTrackIntegrationTest.Request r : http.requests) if ("/partner-ingest".equals(r.path)) {
            JSONArray a = new JSONObject(r.body).getJSONArray("sessions"); for (int i = 0; i < a.length(); i++) all.put(a.get(i));
        }
        return all;
    }

    /* ------------------------------------------------------------------ bug 5 */

    @Test void aResumedSessionIsSentWholeAsAnUpdateOfItsFirstPart() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); f.http.acceptAll = true;
        String grant = f.client.snapshot().connectionId;
        Model.Sess part = session("sess" + (T0 + 300_000), T0, 300); part.completed = false; part.growthTrackConnectionId = grant;
        assertTrue(f.client.recordCompleted(part, run(300_000), new Model(), grant)); f.client.syncPending();
        // Resume, ten more minutes, finish: what fileSession does.
        RunParts prior = RunParts.ofRecord(part); prior.resumes++;
        long now = T0 + 300_000 + 60_000 + 600_000;
        Model.Sess whole = new Model.Sess(); whole.id = "sess" + now; whole.ts = now - 600_000; whole.durSec = 600;
        RunParts.foldTime(prior, whole);
        assertEquals(part.id, whole.syncId, "the whole session keeps the id its first part was sent under");
        assertEquals(T0, whole.recordedStartMs, "...and its first part's start");
        whole.recordedEndMs = now; whole.completed = true; whole.routineName = part.routineName; whole.growthTrackConnectionId = grant;
        RunParts.foldFigures(prior, whole);
        assertTrue(f.client.recordCompleted(whole, run(600_000), new Model(), grant), "the longer record goes again, as an update");
        f.client.syncPending();
        JSONArray sent = sentSessions(f.http);
        assertEquals(2, sent.length());
        JSONObject update = sent.getJSONObject(1);
        assertEquals("op_" + part.id, update.getString("external_session_id"));
        assertEquals(900, update.getInt("total_duration_seconds"));
        assertEquals("2023-11-14T22:13:20.000Z", update.getString("started_at"));
        assertTrue(f.client.receipt("op_" + whole.id).startsWith("Received"), "status reads through the record's own id");
        assertFalse(f.client.recordCompleted(whole, run(600_000), new Model(), grant), "and only once");
    }
    @Test void runPartsCarryTheSyncIdAcrossAProcessDeathAndReadTheOldFormat() {
        Model.Sess part = session("sessA", T0, 60); part.syncId = "sessFirst";
        RunParts p = RunParts.ofRecord(part);
        assertEquals("sessFirst", RunParts.fromCode(p.code()).syncId);
        RunParts old = RunParts.fromCode("p1;1000;2000;;;0.000;;-1;0;1;sessA");
        assertNotNull(old, "a snapshot written by 0.10.0 still reads");
        assertEquals("", old.syncId); assertEquals("sessA", old.sessId);
        Model.Sess whole = new Model.Sess(); whole.id = "sessB"; whole.ts = 5000; RunParts.foldTime(old, whole);
        assertEquals("sessA", whole.syncId, "without a carried sync id the dropped part's own id is used");
    }

    /* ------------------------------------------------------------------ bug 6 */

    @Test void deletingAWaitingSessionWithdrawsItAndASentOneSaysSo() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); String grant = f.client.snapshot().connectionId;
        assertTrue(f.client.recordCompleted(session("misfire", T0, 120), run(120_000), new Model(), grant));
        f.http.responses.add(new IOException("fictional offline")); assertThrows(IOException.class, f.client::syncPending);
        f.http.requests.clear();   // the attempt that never arrived
        assertEquals(GrowthTrackClient.WITHDRAWN, f.client.withdraw("misfire"));
        assertEquals(0, f.client.snapshot().queued);
        f.clock.time += 3600_000L; f.http.acceptAll = true; f.client.syncPending();
        assertEquals(0, sentSessions(f.http).length());
        f.http.responses.add(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2));   // an hour on: renewed first
        assertTrue(f.client.recordCompleted(session("kept", T0, 120), run(120_000), new Model(), grant)); f.client.syncPending();
        assertEquals(GrowthTrackClient.WITHDRAW_ALREADY_SENT, f.client.withdraw("kept"));
        assertEquals(GrowthTrackClient.WITHDRAW_NOTHING, f.client.withdraw("never-seen"));
    }

    /* ------------------------------------------------------------------ bug 12, decision 4 */

    @Test void reconnectingTheSameAccountKeepsItsIdAndQueueAndCancellingChangesNothing() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); String grant = f.client.snapshot().connectionId;
        assertTrue(f.client.recordCompleted(session("waiting", T0, 120), run(120_000), new Model(), grant));
        f.client.begin(); assertTrue(f.client.snapshot().connected); assertTrue(f.client.snapshot().pending);
        f.client.cancelAuthorization(); assertTrue(f.client.snapshot().connected); assertEquals(1, f.client.snapshot().queued);
        f.client.beginChangeAccount(""); f.client.cancelAuthorization();
        assertEquals(grant, f.client.snapshot().connectionId); assertEquals(1, f.client.snapshot().queued);
        assertEquals(grant, connect(f.client, f.storage, f.http, false, "", GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2)));
        assertEquals(1, f.client.snapshot().queued);
        f.http.acceptAll = true; f.client.syncPending();
        assertTrue(f.client.receipt("op_waiting").startsWith("Received"));
    }
    @Test void aChangeOfAccountNeverSendsTheOldAccountsSessions() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); String a = f.client.snapshot().connectionId;
        Model m = new Model(); Model.Sess mine = session("forA", T0, 120); mine.growthTrackConnectionId = a; m.sessLog.all.add(mine);
        assertTrue(f.client.recordCompleted(mine, run(120_000), m, a));
        String b = connect(f.client, f.storage, f.http, true, GrowthTrackClient.EARLIER_ALSO, GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2));
        assertNotEquals(a, b); assertEquals(0, f.client.snapshot().queued);
        assertFalse(f.client.recordCompleted(mine, run(120_000), m, a));
        GrowthTrackOutbox box = GrowthTrackOutbox.take(m, b, rows(), null);
        assertTrue(box.items.isEmpty(), "a session marked for account A is never in B's snapshot");
        assertEquals(0, GrowthTrackOutbox.adoptEarlier(m, b, rows()), "...nor offered to B as an earlier session");
        f.http.acceptAll = true; f.client.sync(box); assertEquals(0, sentSessions(f.http).length());
    }
    @Test void aReconnectThatLandsOnAnotherAccountIsAChangeOfAccount() throws Exception {
        GrowthTrackIntegrationTest.Storage storage = new GrowthTrackIntegrationTest.Storage(); GrowthTrackIntegrationTest.Http http = new GrowthTrackIntegrationTest.Http();
        GrowthTrackClient c = new GrowthTrackClient(storage, http, new GrowthTrackIntegrationTest.Clock());
        String first = connect(c, storage, http, false, "", tokenFor(GrowthTrackIntegrationTest.R, "user-1", "one@example.invalid"));
        assertEquals("one@example.invalid", c.snapshot().account);
        assertTrue(c.recordCompleted(session("one", T0, 120), run(120_000), new Model(), first));
        String same = connect(c, storage, http, false, "", tokenFor(GrowthTrackIntegrationTest.R2, "user-1", "one@example.invalid"));
        assertEquals(first, same); assertEquals(1, c.snapshot().queued);
        String other = connect(c, storage, http, false, "", tokenFor("gtr_" + "t".repeat(43), "user-2", "two@example.invalid"));
        assertNotEquals(first, other, "the browser was signed in to someone else");
        assertEquals(0, c.snapshot().queued); assertEquals("two@example.invalid", c.snapshot().account);
    }

    /* ------------------------------------------------------------------ bug 1 */

    @Test void definitiveRefusalOfTheRefreshEndsTheCredentialButNotTheConnection() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); String grant = f.client.snapshot().connectionId;
        Model.Sess s = session("refused", T0, 120); s.growthTrackConnectionId = grant;
        assertTrue(f.client.recordCompleted(s, run(120_000), new Model(), grant));
        f.clock.time += 16 * 60_000L;
        f.http.responses.add(new GrowthTrackClient.Response(400, "{\"error\":\"invalid_grant\"}"));
        assertThrows(IOException.class, f.client::syncPending);
        GrowthTrackClient.Snapshot after = f.client.snapshot();
        assertFalse(after.connected); assertTrue(after.needsReconnect); assertEquals(grant, after.connectionId);
        assertFalse(f.storage.saved.has("tokens"));
        assertEquals(-1, f.client.automaticRetryAt(), "nothing loops without a credential");
        assertEquals(grant, connect(f.client, f.storage, f.http, false, "", GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2)));
        assertTrue(f.client.recordCompleted(s, run(120_000), new Model(), grant), "the marked session goes after the reconnect");
        f.http.acceptAll = true; f.client.syncPending();
        assertTrue(f.client.receipt("op_refused").startsWith("Received"));
    }

    /* ------------------------------------------------------------------ decision 3: the switches */

    @Test void theMasterSwitchStopsSendingWithoutDisconnectingAndResumesWhenOn() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); String grant = f.client.snapshot().connectionId;
        Model m = new Model(); Model.Sess s = session("switched", T0, 120); s.growthTrackConnectionId = grant; m.sessLog.all.add(s);
        f.http.responses.add(new IOException("fictional offline"));
        assertThrows(IOException.class, () -> f.client.sync(GrowthTrackOutbox.take(m, grant, rows(), null)));
        assertEquals(1, f.client.snapshot().queued); f.http.requests.clear();
        m.gtSend.on = false; f.clock.time += 3600_000L; f.http.acceptAll = true;
        f.client.sync(GrowthTrackOutbox.take(m, grant, rows(), f.client.settled()));
        assertEquals(0, f.client.snapshot().queued, "held back while sending is off");
        assertEquals(0, sentSessions(f.http).length()); assertTrue(f.client.snapshot().connected);
        m.gtSend.on = true; f.http.responses.add(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2));
        f.client.sync(GrowthTrackOutbox.take(m, grant, rows(), f.client.settled()));
        assertTrue(f.client.receipt("op_switched").startsWith("Received"));
        assertTrue(GrowthTrackOutbox.take(m, grant, rows(), f.client.settled()).items.isEmpty(), "a settled record is not drafted again");
    }
    @Test void eachTypeHasItsSwitchAndStoppedEarlyIsOffByDefault() {
        Model m = new Model();
        GrowthTrackSettings defaults = new GrowthTrackSettings();
        assertFalse(defaults.stoppedEarly);
        Model.Sess own = session("own", T0, 120);
        assertNull(defaults.refusal(own, null, m));
        Model.Sess stopped = session("stopped", T0, 120); stopped.completed = false;
        assertNotNull(defaults.refusal(stopped, null, m));
        Model.Sess manualRun = manualSession("manual");
        assertEquals(GrowthTrackSettings.KIND_MANUAL, GrowthTrackSettings.kindOf(manualRun, null, m));
        Model.Sess length = session("length", T0, 120); length.asRunSnapshot = new Model.AsRunSnapshot(); length.asRunSnapshot.trainerTrack = Plan.TRACK_LENGTH;
        Model.Sess girth = session("girth", T0, 120); girth.asRunSnapshot = new Model.AsRunSnapshot(); girth.asRunSnapshot.trainerTrack = Plan.TRACK_GIRTH_TRADITIONAL;
        assertEquals(GrowthTrackSettings.KIND_LENGTH_PLAN, GrowthTrackSettings.kindOf(length, null, m));
        assertEquals(GrowthTrackSettings.KIND_GIRTH_PLAN, GrowthTrackSettings.kindOf(girth, null, m));
        assertEquals(GrowthTrackSettings.KIND_OWN, GrowthTrackSettings.kindOf(own, null, m));
        GrowthTrackSettings s = new GrowthTrackSettings(); s.manualRuns = false; s.lengthPlan = false;
        assertNotNull(s.refusal(manualRun, null, m)); assertNotNull(s.refusal(length, null, m));
        assertNull(s.refusal(girth, null, m)); assertNull(s.refusal(own, null, m));
        s.ownRoutines = false; s.girthPlan = false; s.stoppedEarly = true;
        assertNotNull(s.refusal(own, null, m)); assertNotNull(s.refusal(girth, null, m));
    }
    @Test void aPlanSwitchShowsOnlyForATrackThePersonTrains() {
        Model m = new Model();
        assertFalse(GrowthTrackSettings.showsPlanSwitch(m, GrowthTrackSettings.KIND_GIRTH_PLAN));
        assertFalse(GrowthTrackSettings.showsPlanSwitch(m, GrowthTrackSettings.KIND_LENGTH_PLAN));
        m.trainerEnrolled = true;
        assertTrue(GrowthTrackSettings.showsPlanSwitch(m, GrowthTrackSettings.KIND_GIRTH_PLAN));
        assertFalse(GrowthTrackSettings.showsPlanSwitch(m, GrowthTrackSettings.KIND_LENGTH_PLAN));
        m.trainerLengthOn = true;
        assertTrue(GrowthTrackSettings.showsPlanSwitch(m, GrowthTrackSettings.KIND_LENGTH_PLAN));
        Model past = new Model(); Model.Sess s = session("past", T0, 120); s.asRunSnapshot = new Model.AsRunSnapshot(); s.asRunSnapshot.trainerTrack = Plan.TRACK_LENGTH; past.sessLog.all.add(s);
        assertTrue(GrowthTrackSettings.showsPlanSwitch(past, GrowthTrackSettings.KIND_LENGTH_PLAN), "a track trained before still has sessions to send");
    }
    @Test void practiceRunsAndSwitchedOffSessionsAreNeverMarked() {
        Model m = new Model();
        Model.Sess practice = session("practice", T0, 120); practice.sim = true;
        assertEquals("", GrowthTrackOutbox.markerFor(practice, "conn", m, null));
        assertEquals("conn", GrowthTrackOutbox.markerFor(session("real", T0, 120), "conn", m, null));
        Model.Sess stopped = session("stopped", T0, 120); stopped.completed = false;
        assertEquals("", GrowthTrackOutbox.markerFor(stopped, "conn", m, null), "stopped early is off by default");
        m.gtSend.on = false;
        assertEquals("", GrowthTrackOutbox.markerFor(session("off", T0, 120), "conn", m, null));
        assertEquals("", GrowthTrackOutbox.markerFor(session("none", T0, 120), "", new Model(), null));
    }
    @Test void theSettingIsStoredOnceInModelJsonAndOldFilesReadAsTheDefaults() throws Exception {
        Model m = Model.seed(); m.gtSend.lengthPlan = false; m.gtSend.stoppedEarly = true;
        Model back = Model.fromJson(m.toJson());
        assertFalse(back.gtSend.lengthPlan); assertTrue(back.gtSend.stoppedEarly); assertTrue(back.gtSend.on);
        Model old = Model.fromJson("{\"sets\":[],\"routines\":[]}");
        assertTrue(old.gtSend.on && old.gtSend.girthPlan && old.gtSend.lengthPlan && old.gtSend.ownRoutines && old.gtSend.manualRuns);
        assertFalse(old.gtSend.stoppedEarly);
    }

    /* ------------------------------------------------------------------ decision 4: earlier sessions */

    @Test void a0100SessionGetsItsTimesFromItsOwnRecord() throws Exception {
        // 0.10.0 filed id "sess" + the filing instant (the end) and ts = the start; no
        // recordedStartMs/EndMs. With its own recording, both bounds are known.
        Model.Sess old = GrowthTrackIntegrationTest.session("sess" + (T0 + 120_000)); old.recordedStartMs = 0; old.recordedEndMs = 0; old.ts = T0;
        JSONObject p = GrowthTrackSession.draft(old, run(120_000)).payload();
        assertEquals("2023-11-14T22:13:20.000Z", p.getString("started_at"));
        assertEquals("2023-11-14T22:15:20.000Z", p.getString("completed_at"));
        assertNull(GrowthTrackSession.bounds(old, null), "no recording, no derived times");
        AsRun other = run(120_000); other.ts = T0 + 5; assertNull(GrowthTrackSession.bounds(old, other), "only its own recording");
        Model.Sess endStamped = GrowthTrackIntegrationTest.session("sess" + T0); endStamped.recordedStartMs = 0; endStamped.recordedEndMs = 0; endStamped.ts = T0;
        assertNull(GrowthTrackSession.bounds(endStamped, run(120_000)), "a release that stamped ts at the end is not guessed");
    }
    @Test void earlierSessionsAreCountedAndOnlyTheSendableOnesAdopted() {
        Model m = new Model(); m.gtSend.manualRuns = false;
        Model.Sess sendable = GrowthTrackIntegrationTest.session("sess" + (T0 + 120_000)); sendable.recordedStartMs = 0; sendable.recordedEndMs = 0; sendable.ts = T0;
        Model.Sess endStamped = GrowthTrackIntegrationTest.session("sess" + T0); endStamped.recordedStartMs = 0; endStamped.recordedEndMs = 0; endStamped.ts = T0;
        Model.Sess practice = session("practice", T0, 120); practice.sim = true;
        Model.Sess otherAccount = session("theirs", T0, 120); otherAccount.growthTrackConnectionId = "account-a";
        Model.Sess manualRun = manualSession("manual");
        m.sessLog.all.add(sendable); m.sessLog.all.add(endStamped); m.sessLog.all.add(practice); m.sessLog.all.add(otherAccount); m.sessLog.all.add(manualRun);
        GrowthTrackOutbox.Earlier e = GrowthTrackOutbox.earlier(m, rows());
        assertEquals(3, e.total); assertEquals(1, e.sendable); assertEquals("1 of 3 can be sent", e.line());
        assertEquals(1, GrowthTrackOutbox.adoptEarlier(m, "account-b", rows()));
        assertEquals("account-b", sendable.growthTrackConnectionId);
        assertEquals("", endStamped.growthTrackConnectionId); assertEquals("", manualRun.growthTrackConnectionId);
        assertEquals("account-a", otherAccount.growthTrackConnectionId);
        assertEquals(0, GrowthTrackOutbox.earlier(m, rows()).sendable, "adopted ones are no longer earlier");
    }
    @Test void theEarlierSessionsQuestionIsAnsweredOncePerAccount() throws Exception {
        GrowthTrackIntegrationTest.Storage storage = new GrowthTrackIntegrationTest.Storage(); GrowthTrackIntegrationTest.Http http = new GrowthTrackIntegrationTest.Http();
        GrowthTrackClient c = new GrowthTrackClient(storage, http, new GrowthTrackIntegrationTest.Clock());
        String a = connect(c, storage, http, false, GrowthTrackClient.EARLIER_ALSO, GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R));
        assertEquals(GrowthTrackClient.EARLIER_ALSO, c.snapshot().earlierChoice); assertTrue(c.snapshot().earlierToAdopt);
        c.earlierAdopted(a); assertFalse(c.snapshot().earlierToAdopt);
        connect(c, storage, http, false, GrowthTrackClient.EARLIER_NEW, GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2));
        assertEquals(GrowthTrackClient.EARLIER_ALSO, c.snapshot().earlierChoice, "a reconnect does not ask again");
        c.chooseEarlier(GrowthTrackClient.EARLIER_NEW); assertEquals(GrowthTrackClient.EARLIER_ALSO, c.snapshot().earlierChoice);
        connect(c, storage, http, true, GrowthTrackClient.EARLIER_NEW, GrowthTrackIntegrationTest.token("gtr_" + "u".repeat(43)));
        assertEquals(GrowthTrackClient.EARLIER_NEW, c.snapshot().earlierChoice, "a new account answers for itself");
    }

    /* ------------------------------------------------------------------ bug 8: the snapshot */

    @Test void theSnapshotCarriesDraftsAndLiveIdsAndIsIgnoredForAnotherConnection() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); String grant = f.client.snapshot().connectionId;
        Model m = new Model();
        Model.Sess marked = session("marked", T0, 120); marked.growthTrackConnectionId = grant;
        Model.Sess unmarked = session("unmarked", T0, 120);
        m.sessLog.all.add(marked); m.sessLog.all.add(unmarked);
        int[] lookups = {0};
        GrowthTrackOutbox box = GrowthTrackOutbox.take(m, grant, s -> { lookups[0]++; return run(120_000); }, null);
        assertEquals(1, box.items.size()); assertTrue(box.items.get(0).sendable());
        assertEquals(1, lookups[0], "only marked sessions read their recording");
        assertTrue(box.live.contains("op_marked") && box.live.contains("op_unmarked"));
        marked.routineName = "edited after the snapshot";
        f.http.acceptAll = true; f.client.sync(box);
        assertEquals("Fictional fixture", sentSessions(f.http).getJSONObject(0).getString("routine_name"), "the worker sends what the main thread drafted");
        GrowthTrackOutbox foreign = GrowthTrackOutbox.take(m, "another-connection", rows(), null);
        f.client.recordCompleted(session("still", T0, 120), run(120_000), new Model(), grant);
        f.http.acceptAll = false; f.http.responses.add(new IOException("fictional offline"));
        assertThrows(IOException.class, () -> f.client.sync(foreign));
        assertEquals(1, f.client.snapshot().queued, "another connection's snapshot withdraws nothing");
        Map<String, Integer> settled = f.client.settled();
        assertEquals(Integer.valueOf(0), settled.get("op_marked"));
    }

    /* ------------------------------------------------------------------ small fixes */

    @Test void extraCallbackParametersAreIgnoredButACredentialIsRefused() {
        GrowthTrackProtocol.Pending p = GrowthTrackProtocol.pending(1000);
        String ok = GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE + "&state=" + p.state;
        assertEquals(GrowthTrackIntegrationTest.CODE, GrowthTrackProtocol.callback(ok + "&iss=https%3A%2F%2Fexample.invalid", p, 1001).code);
        assertEquals(GrowthTrackIntegrationTest.CODE, GrowthTrackProtocol.callback(ok + "&session_state=abc", p, 1001).code);
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackProtocol.callback(ok + "&id_token=x", p, 1001));
        assertThrows(IllegalArgumentException.class, () -> GrowthTrackProtocol.callback(ok + "&code=" + GrowthTrackIntegrationTest.CODE, p, 1001));
    }
    @Test void theConnectionIdIsRandomAndNotTheOAuthState() throws Exception {
        GrowthTrackIntegrationTest.Storage storage = new GrowthTrackIntegrationTest.Storage(); GrowthTrackIntegrationTest.Http http = new GrowthTrackIntegrationTest.Http();
        GrowthTrackClient c = new GrowthTrackClient(storage, http, new GrowthTrackIntegrationTest.Clock());
        c.begin(); String state = storage.saved.getJSONObject("pending").getString("state");
        http.responses.add(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R));
        c.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE + "&state=" + state);
        assertTrue(c.snapshot().connectionId.matches("[0-9a-f]{64}")); assertNotEquals(state, c.snapshot().connectionId);
    }
    @Test void thePeakIsSentToTwoDecimalsAndALongNameIsCut() throws Exception {
        Model.Sess s = session("peak", T0, 120); s.peakKpa = 20.0; s.routineName = "x".repeat(199) + "😀";
        JSONObject p = GrowthTrackSession.draft(s, run(120_000)).payload();
        assertEquals(5.91, p.getDouble("pressure_peak_inhg"), 1e-9);
        assertEquals(199, p.getString("routine_name").length(), "never half a character");
    }
    @Test void anExpiredRequestIsNotReportedAsWaiting() throws Exception {
        GrowthTrackIntegrationTest.Storage storage = new GrowthTrackIntegrationTest.Storage(); GrowthTrackIntegrationTest.Clock clock = new GrowthTrackIntegrationTest.Clock();
        GrowthTrackClient c = new GrowthTrackClient(storage, new GrowthTrackIntegrationTest.Http(), clock);
        c.begin(); assertTrue(c.snapshot().pending);
        clock.time += GrowthTrackProtocol.AUTH_LIFETIME_MS + 1; assertFalse(c.snapshot().pending);
    }
    @Test void warmUpAndTractionStagesAreLabelledByTheirFrozenRole() throws Exception {
        Model.Routine r = new Model.Routine(); r.id = "roles";
        Model.Stage warm = new Model.Stage(); warm.name = "Warm-up"; warm.colour = Model.STAGE_WARM;
        Model.Stage pull = new Model.Stage(); pull.name = "Traction"; pull.traction = true;
        Model.Stage work = new Model.Stage(); work.name = "Work";
        r.stages.add(warm); r.stages.add(pull); r.stages.add(work);
        AsRun run = new AsRun(); run.snap = Model.AsRunSnapshot.fromJson(Model.AsRunSnapshot.of(r, null).toJson());
        for (int i = 0; i < 3; i++) { AsRun.Row row = GrowthTrackIntegrationTest.row(AsRun.PLAN, i * 40_000L, (i + 1) * 40_000L, 0); row.stageIdx = i; run.rows.add(row); }
        r.stages.get(1).traction = false;   // edited after the run: the frozen role answers
        GrowthTrackSession.Draft d = GrowthTrackSession.draft(session("roles", T0, 120), run, new Model());
        assertEquals("warm_up", d.parts.get(0).method); assertEquals("length_pumping", d.parts.get(1).method); assertEquals("rip", d.parts.get(2).method);
        Model.AsRunSnapshot legacy = Model.AsRunSnapshot.fromJson(new JSONObject("{\"rid\":\"x\",\"stages\":[{\"name\":\"a\"}]}"));
        assertEquals(Model.AsRunSnapshot.ROLE_UNKNOWN, legacy.stages.get(0).role);
        assertFalse(legacy.toJson().getJSONArray("stages").getJSONObject(0).has("role"));
    }
    @Test void statusLinesReadSentWaitingAndNotSent() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); String grant = f.client.snapshot().connectionId;
        Model m = new Model();
        Model.Sess waiting = session("waiting", T0, 120); waiting.growthTrackConnectionId = grant;
        f.client.recordCompleted(waiting, run(120_000), m, grant);
        GrowthTrackClient.Snapshot snap = f.client.snapshot();
        assertEquals(GrowthTrackOutbox.Status.WAITING, GrowthTrackOutbox.statusOf(waiting, snap, f.client.state("op_waiting"), m).state);
        f.http.acceptAll = true; f.client.syncPending();
        assertEquals(GrowthTrackOutbox.Status.SENT, GrowthTrackOutbox.statusOf(waiting, f.client.snapshot(), f.client.state("op_waiting"), m).state);
        assertTrue(f.client.snapshot().lastSentAt > 0);
        Model.Sess practice = session("practice", T0, 120); practice.sim = true;
        assertEquals(GrowthTrackOutbox.Status.NOT_SENT, GrowthTrackOutbox.statusOf(practice, snap, null, m).state);
        Model.Sess unmarked = session("unmarked", T0, 120);
        assertEquals(GrowthTrackOutbox.Status.NOT_SENT, GrowthTrackOutbox.statusOf(unmarked, snap, f.client.state("op_unmarked"), m).state);
    }
}
