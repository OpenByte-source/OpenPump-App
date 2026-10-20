package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * BUG HUNT (PR #1, area: what is sent and when). Every test here FAILS on the PR as
 * submitted; each failure is one defect. Fictional records, mocked transport and storage
 * only (GrowthTrackIntegrationTest's fixtures): nothing can reach GrowthTrack.
 */
class GrowthTrackSyncBugsTest {
    private static AsRun run(long ms) {
        AsRun r = new AsRun(); r.rows.add(GrowthTrackIntegrationTest.row(AsRun.PLAN, 0, ms, 0)); return r;
    }
    private static Model.Sess session(String id, long durSec) {
        Model.Sess s = GrowthTrackIntegrationTest.session(id); s.durSec = durSec;
        s.recordedEndMs = s.recordedStartMs + durSec * 1000L; return s;
    }
    private static boolean sentIn(GrowthTrackIntegrationTest.Http http, String externalId) throws Exception {
        for (GrowthTrackIntegrationTest.Request r : http.requests) {
            if (!"/partner-ingest".equals(r.path)) continue;
            JSONArray a = new JSONObject(r.body).getJSONArray("sessions");
            for (int i = 0; i < a.length(); i++) if (externalId.equals(a.getJSONObject(i).getString("external_session_id"))) return true;
        }
        return false;
    }

    /* ======================================================== delivery (GrowthTrackClient) */

    /** S1. The phone is offline when a session ends (airplane mode, no signal, captive
     *  portal). The access token lasts at most 900 s, so after any session longer than
     *  about 14 minutes the first request is a refresh. An IOException on /oauth-token
     *  (a request that never left the phone) is handled as an "ambiguous rotation":
     *  clearAuthority() removes the tokens and the queue, and the session's filing marker
     *  now names a grant that no longer exists, so it can never be sent. */
    @Test void offlineAtTokenRefreshKeepsTheConnectionAndTheFinishedSession() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        f.clock.time += 20 * 60_000L;                       // a 20-minute session
        assertTrue(f.client.recordCompleted(session("offline", 120), run(120_000), new Model(), grant));
        f.http.responses.add(new IOException("fictional: no route to host"));
        assertThrows(IOException.class, f.client::syncPending);
        assertTrue(f.client.snapshot().connected, "being offline at refresh time disconnected the account");
        assertEquals(1, f.client.snapshot().queued, "the finished session was dropped from the queue");
    }

    /** S2. The same for a transient 503/429 from the token endpoint. */
    @Test void transientServerErrorAtTokenRefreshKeepsTheConnection() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        f.clock.time += 20 * 60_000L;
        assertTrue(f.client.recordCompleted(session("busy", 120), run(120_000), new Model(), grant));
        f.http.responses.add(new GrowthTrackClient.Response(503, "", "30"));
        assertThrows(IOException.class, f.client::syncPending);
        assertTrue(f.client.snapshot().connected, "a 503 from /oauth-token disconnected the account");
        assertEquals(1, f.client.snapshot().queued, "a 503 from /oauth-token discarded the pending session");
    }

    /** S3. A 403 pauses sync; the only control offered is "Reconnect to restore sync".
     *  Reconnecting (even the same account) runs begin(), which empties the queue, and the
     *  paused session's filing marker names the old grant, so recovery never re-queues it. */
    @Test void reconnectingAfterAPermissionPauseDeliversThePausedSession() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        Model.Sess s = session("paused", 120); s.growthTrackConnectionId = f.client.snapshot().connectionId;
        assertTrue(f.client.recordCompleted(s, run(120_000), new Model(), s.growthTrackConnectionId));
        f.http.responses.add(new GrowthTrackClient.Response(403, "{}"));
        assertThrows(IOException.class, f.client::syncPending);
        assertTrue(f.client.snapshot().paused);
        // The user follows "Reconnect to restore sync" and approves the same account.
        f.client.begin();
        String state = f.storage.saved.getJSONObject("pending").getString("state");
        f.http.responses.add(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2));
        f.client.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE + "&state=" + state);
        // GrowthTrackManager.startSync's recovery pass over the saved records:
        f.client.recordCompleted(s, run(120_000), new Model(), s.growthTrackConnectionId);
        f.http.acceptAll = true; f.client.syncPending();
        assertTrue(f.client.receipt("op_paused").startsWith("Received"),
            "the session that was 'pending, retried automatically' was silently lost by the reconnect the app asked for");
    }

    /** S4. "Reconnect / change account" (or ticking routines:read) on a working connection,
     *  then Cancel / Back in the browser: begin() has already deleted the tokens. */
    @Test void startingThenCancellingAReconnectLeavesTheWorkingConnectionAlone() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        // CALL SITE CHANGED: the routines:read checkbox is gone (owner decision 6), so the
        // reconnect is begin() with no flag. Same scenario, same assertion.
        f.client.begin();
        f.client.cancelAuthorization();
        assertTrue(f.client.snapshot().connected, "cancelling a reconnect left the user disconnected; later sessions get no marker");
    }

    /** S5. One request-level 400/422 for a batch of up to 100 marks EVERY session in it
     *  "Needs review" and removes it for good. Nothing bisects to find the bad one. */
    @Test void aRequestLevelRejectionDoesNotPermanentlyDropEverySessionInTheBatch() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        for (String id : new String[]{"a", "b", "c"})
            assertTrue(f.client.recordCompleted(session(id, 120), run(120_000), new Model(), grant));
        f.http.responses.add(new GrowthTrackClient.Response(400, "{\"error\":\"one session failed validation\"}"));
        assertThrows(IOException.class, f.client::syncPending);
        int dropped = 0;
        for (String id : new String[]{"op_a", "op_b", "op_c"}) if (f.client.receipt(id).startsWith("Needs review")) dropped++;
        assertTrue(dropped <= 1, dropped + " of 3 sessions were permanently dropped by one batch-level 400");
    }

    /** S6. A single 413 halves batch_limit and nothing ever raises it again. */
    @Test void oneOversizeResponseDoesNotShrinkEveryLaterBatchForever() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        f.client.recordCompleted(session("x1", 120), run(120_000), new Model(), grant);
        f.client.recordCompleted(session("x2", 120), run(120_000), new Model(), grant);
        f.http.responses.add(new GrowthTrackClient.Response(413, "{}"));
        f.http.responses.add(GrowthTrackIntegrationTest.ok(1, 0, new JSONArray()));
        f.http.responses.add(GrowthTrackIntegrationTest.ok(1, 0, new JSONArray()));
        f.client.syncPending();
        int before = f.http.requests.size();
        f.http.acceptAll = true;
        for (int i = 0; i < 6; i++) f.client.recordCompleted(session("later" + i, 120), run(120_000), new Model(), grant);
        f.client.syncPending();
        assertEquals(1, f.http.requests.size() - before, "six small sessions went out one per request");
    }

    /** S7. A session that always gets the transient "Insert failed" is retried forever, and
     *  its backoff (up to 1 h, never reset while it is queued) holds every new session too. */
    @Test void onePoisonedTransientSessionDoesNotHoldBackNewSessions() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        f.client.recordCompleted(session("poison", 120), run(120_000), new Model(), grant);
        JSONArray rejected = new JSONArray().put(new JSONObject().put("external_session_id", "op_poison").put("reason", "Insert failed"));
        for (int i = 0; i < 10; i++) {
            f.clock.time = Math.max(f.clock.time, f.client.automaticRetryAt());
            if (f.clock.time + 30_000 >= f.storage.saved.getJSONObject("tokens").getLong("expires_at"))
                f.http.responses.add(GrowthTrackIntegrationTest.token("gtr_" + Integer.toString(i).repeat(30)));
            f.http.responses.add(GrowthTrackIntegrationTest.ok(0, 0, rejected));
            f.client.syncPending();
        }
        assertEquals(1, f.client.snapshot().queued, "still queued after 10 attempts; there is no limit");
        f.client.recordCompleted(session("fresh", 120), run(120_000), new Model(), grant);
        f.clock.time += 5 * 60_000L;
        f.http.acceptAll = true; f.client.syncPending();
        assertTrue(f.client.receipt("op_fresh").startsWith("Received"),
            "a new session waited behind an unrelated poisoned one (retry_at is " + (f.client.snapshot().retryAt - f.clock.time) / 1000 + " s away)");
    }

    /** S8. History > Delete ("a start with the pump not on the body, a test, a tap in a
     *  pocket") removes the record from model.json, but the queued detached payload is still
     *  uploaded at the next retry. There is no API to withdraw it. */
    @Test void aSessionDeletedFromHistoryWhileQueuedIsNotUploadedAfterwards() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        Model.Sess misfire = session("misfire", 120); misfire.growthTrackConnectionId = f.client.snapshot().connectionId;
        assertTrue(f.client.recordCompleted(misfire, run(120_000), new Model(), misfire.growthTrackConnectionId));
        f.http.responses.add(new IOException("fictional offline"));
        assertThrows(IOException.class, f.client::syncPending);
        int before = f.http.requests.size();
        // The user deletes the misfire (SessionActivity.DeleteSessionConfirm). Next startup.
        // CALL SITE CHANGED: a sync pass now works from the main-thread snapshot (bug 8), which
        // is how a deletion reaches the queue; the pass before was a loop of recordCompleted
        // calls that could not tell "deleted" from "not looked at". Same scenario, same assertion.
        Model afterDelete = new Model();
        f.clock.time = f.client.automaticRetryAt(); f.http.acceptAll = true;
        f.client.sync(GrowthTrackOutbox.take(afterDelete, f.client.snapshot().connectionId, s -> null, null));
        List<GrowthTrackIntegrationTest.Request> after = new ArrayList<>(f.http.requests.subList(before, f.http.requests.size()));
        f.http.requests.clear(); f.http.requests.addAll(after);
        assertFalse(sentIn(f.http, "op_misfire"), "the deleted session was uploaded after the user deleted it");
    }

    /** S9. retry_at is absolute wall-clock time. When the phone's clock is corrected backwards
     *  (a wrong clock fixed by NTP or by hand), automatic sync stops until the old clock time
     *  comes round again, even though the longest backoff is 1 h. */
    @Test void aClockSetBackwardsDoesNotStallSyncBeyondTheMaximumBackoff() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        f.client.recordCompleted(session("skew", 120), run(120_000), new Model(), grant);
        f.http.responses.add(new IOException("fictional offline"));
        assertThrows(IOException.class, f.client::syncPending);
        f.clock.time -= 2L * 24 * 3600_000L;             // clock corrected back two days
        f.clock.time += 3600_000L + 1;                   // more than the maximum backoff passes
        f.http.acceptAll = true; f.client.syncPending();
        assertTrue(f.client.receipt("op_skew").startsWith("Received"), "sync is stalled for ~2 days by a stale absolute retry_at");
    }

    /* ======================================================== payload (GrowthTrackSession/Timing) */

    /** P1. A stitched hold (over 255 s, the Traditional girth track's 5-minute holds) whose
     *  255 s chunk row is 1 ms longer than 255 000 ms makes the whole session unsendable
     *  (GrowthTrackTiming:36, `row.span() > row.uhWire * 1000L`, zero tolerance). Rows are
     *  restamped at START ack and the next step is posted with Handler.postDelayed(durMs),
     *  which is never early, so on a device a chunk row is routinely a few ms over. */
    @Test void aStitchChunkAMillisecondLateIsStillSent() throws Exception {
        Model.Sess s = session("trad", 361);
        AsRun run = GrowthTrackIntegrationTest.stitch(true);
        run.rows.get(0).t1 = 255_001; run.rows.get(1).t0 = 255_001; run.rows.get(1).t1 = 360_001;
        JSONObject p = assertDoesNotThrow(() -> GrowthTrackSession.draft(s, run).payload(),
            "a 5-minute hold with 1 ms of timer lateness cannot be sent");
        assertEquals("static_pumping", p.getJSONArray("sets").getJSONObject(0).getString("method"));
    }

    /** P1b. The same through a real Traditional-girth L1 routine the plan mints, recorded the
     *  way playPreset records it, with each step 40 ms late. */
    @Test void aMintedTraditionalGirthSessionWithOrdinaryTimerLatenessIsSent() {
        Model m = ByHandStageTest.puller();
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, Mint.prescribe(Plan.TRACK_GIRTH_TRADITIONAL, Plan.L1, 1, 20.0, 2, m.ceilKpa, 0, null)));
        GtSim sim = new GtSim(m, r); sim.jitterMs = 40; sim.playAll();
        Model.Sess s = sim.file(false);
        assertDoesNotThrow(() -> GrowthTrackSession.draft(s, sim.run, m).payload(),
            "every Traditional-girth session with >0 ms lateness on a chunk is 'Not synced'");
    }

    /** P2. A set whose duration is a whole number of cycles, recorded a few ms long (the
     *  normal case: the app timer ends a step late, AsRun.CUT_SHORT_TOL_MS), gets an extra
     *  "milking" set with duration null for the leftover milliseconds. Seed "Warm + Build"
     *  (shortest hold 30 s) is sent with two Milking sets that never happened. */
    @Test void timerLatenessDoesNotInventMilkingSets() throws Exception {
        Model m = Model.seed();
        Model.Routine r = m.routine("r1");
        GtSim sim = new GtSim(m, r); sim.jitterMs = 40; sim.playAll();
        JSONArray sets = GrowthTrackSession.draft(sim.file(false), sim.run, m).payload().getJSONArray("sets");
        for (int i = 0; i < sets.length(); i++)
            assertNotEquals("milking", sets.getJSONObject(i).getString("method"),
                "phantom set #" + (i + 1) + " " + sets.getJSONObject(i) + " in a routine with no hold under 30 s");
    }

    /** P3. Length precedence labels EVERY part of a Length-track session length_pumping,
     *  including the plan's own girth expansion (girth cylinder, -5.9 inHg, 120 s interval
     *  holds) and the warm-up. Stage.traction already marks the real length work. GrowthTrack's
     *  length and girth dose-response then get girth minutes counted as length. */
    @Test void theGirthExpansionOfALengthSessionIsNotReportedAsLengthPumping() {
        Model m = ByHandStageTest.puller();
        Model.Routine r = ByHandStageTest.lengthRoutine(m);
        GtSim sim = new GtSim(m, r); sim.playAll();
        GrowthTrackSession.Draft d = GrowthTrackSession.draft(sim.file(false), sim.run, m);
        boolean sawExpansion = false;
        for (GrowthTrackSession.Part p : d.parts) if (p.label.startsWith("Expansion")) {
            sawExpansion = true;
            assertNotEquals("length_pumping", p.method, "'" + p.label + "' (" + p.seconds + " s of girth expansion) sent as length_pumping");
        }
        assertTrue(sawExpansion);
    }

    /** P4. The minted warm-up ramp (short holds climbing to the work, excluded from net) is
     *  sent as one RIP set plus five Interval-pumping sets. GrowthTrack's own vocabulary has
     *  warm_up, which the mapper never uses. */
    @Test void theWarmUpRampIsNotReportedAsWorkSets() {
        Model m = ByHandStageTest.puller();
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 1, 20.0, 2, m.ceilKpa, 0, null)));
        GtSim sim = new GtSim(m, r); sim.playAll();
        GrowthTrackSession.Draft d = GrowthTrackSession.draft(sim.file(false), sim.run, m);
        for (GrowthTrackSession.Part p : d.parts) if (p.label.startsWith("Warm-up"))
            assertEquals("warm_up", p.method, "warm-up step '" + p.label + "' sent as " + p.method);
    }

    /** P5. STOP files the run, which syncs at once (a stopped session with recorded work is
     *  eligible). Tapping Resume on the snackbar and finishing makes fileSession
     *  (SessionActivity:27935-27969) drop that record locally and file ONE session, with
     *  recordedStartMs left 0 because parts.playedMs > 0. That session is never sent.
     *  GrowthTrack keeps only the 5-minute stopped part. The local log no longer has it, and
     *  the real 15-minute completed session is missing. The same happens to a screen destroyed
     *  mid-run and then rejoined. */
    @Test void stopThenResumeEndsWithTheWholeSessionOnGrowthTrack() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        Model.Sess part = session("sess-part", 300); part.completed = false; part.growthTrackConnectionId = grant;
        assertTrue(f.client.recordCompleted(part, run(300_000), new Model(), grant));
        f.http.acceptAll = true; f.client.syncPending();
        assertTrue(f.client.receipt("op_sess-part").startsWith("Received"));
        // Resume, run 10 more minutes, finish. fileSession's own steps:
        RunParts prior = RunParts.ofRecord(part); prior.resumes++;
        List<Model.Sess> log = new ArrayList<>(Arrays.asList(part));
        assertSame(part, RunParts.dropEarlier(log, prior));
        long now = part.recordedEndMs + 60_000 + 600_000, el = 600;
        Model.Sess whole = new Model.Sess(); whole.id = "sess" + now; whole.ts = now - el * 1000; whole.durSec = el;
        RunParts.foldTime(prior, whole);
        if (prior.playedMs == 0) { whole.recordedStartMs = now - el * 1000; whole.recordedEndMs = now; }
        whole.completed = true; whole.routineName = part.routineName; whole.peakKpa = part.peakKpa;
        RunParts.foldFigures(prior, whole); whole.growthTrackConnectionId = grant;
        AsRun second = new AsRun(); second.rows.add(GrowthTrackIntegrationTest.row(AsRun.PLAN, 0, 600_000, 1));
        f.client.recordCompleted(whole, second, new Model(), grant); f.client.syncPending();
        assertTrue(f.client.receipt("op_" + whole.id).startsWith("Received"),
            "GrowthTrack holds only the stopped 300 s part (a record the phone deleted); the finished " + whole.durSec
            + " s session is '" + f.client.receipt("op_" + whole.id) + "'");
    }

    /** P6. A row the pump did not acknowledge is silently left out of the sets while the
     *  session is still sent with its full duration. SessionActivity's own AsRunNote#run says
     *  "the real pump often carries a START out without answering it". */
    @Test void anUnacknowledgedButRunSetIsNotSilentlyLeftOutOfAnUploadedSession() throws Exception {
        Model.Sess s = session("noack", 240);
        AsRun run = new AsRun();
        run.rows.add(GrowthTrackIntegrationTest.row(AsRun.PLAN, 0, 120_000, 0));
        AsRun.Row second = GrowthTrackIntegrationTest.row(AsRun.PLAN, 120_000, 240_000, 1); second.confirmed = false; run.rows.add(second);
        JSONObject p = GrowthTrackSession.draft(s, run).payload();
        int sum = 0; JSONArray sets = p.getJSONArray("sets");
        for (int i = 0; i < sets.length(); i++) if (!sets.getJSONObject(i).isNull("duration_seconds")) sum += sets.getJSONObject(i).getInt("duration_seconds");
        assertTrue(sum >= 230, "uploaded as 240 s with only " + sum + " s of sets: half the work vanished without any status");
    }

    /** P7. One 6-second Bluetooth dropout (a LOST row) on a girth routine makes the whole
     *  session permanently "Not synced". */
    @Test void aShortBluetoothDropoutDoesNotMakeTheWholeSessionUnsendable() {
        Model.Sess s = session("dropout", 120);
        AsRun run = new AsRun();
        run.rows.add(GrowthTrackIntegrationTest.row(AsRun.PLAN, 0, 60_000, 0));
        run.rows.add(GrowthTrackIntegrationTest.row(AsRun.LOST, 60_000, 66_000, 0));
        run.rows.add(GrowthTrackIntegrationTest.row(AsRun.PLAN, 66_000, 120_000, 0));
        assertDoesNotThrow(() -> GrowthTrackSession.draft(s, run).payload());
    }

    /** P8. Routine names are not length-limited in the editor; a name over 200 characters makes
     *  every session of that routine permanently "Not synced" instead of truncating. */
    @Test void aLongRoutineNameIsTruncatedRatherThanBlockingTheSession() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        Model.Sess s = session("longname", 120); s.routineName = "Long routine name ".repeat(12);
        assertTrue(f.client.recordCompleted(s, run(120_000), new Model(), f.client.snapshot().connectionId),
            "receipt: " + f.client.receipt("op_longname"));
    }
}
