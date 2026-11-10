package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * WAIT UNTIL FINAL (the owner's decision, 10 Nov 2026). GrowthTrack keeps the first copy of a
 * session it is sent: the same id sent again is answered "already present" and nothing on it
 * changes. STOP filed a part and sent it at once, so a Resume (or a rejoin after the app was
 * lost) made a whole session GrowthTrack never took. Now a session that can still be continued
 * is held, says so in "Sent and waiting sessions", and goes once, whole, when that chance is
 * over. Fictional records, mocked transport and storage only: nothing here reaches GrowthTrack.
 */
class GrowthTrackHoldTest {
    private static final long T0 = 1_700_000_000_000L;
    private static final long MAX_AGE = 6L * 60L * 60L * 1000L;

    private static AsRun run(long ms) {
        AsRun r = new AsRun(); r.rows.add(GrowthTrackIntegrationTest.row(AsRun.PLAN, 0, ms, 0)); return r;
    }
    private static Model.Sess session(String id, long start, long durSec) {
        Model.Sess s = GrowthTrackIntegrationTest.session(id);
        s.ts = start; s.recordedStartMs = start; s.durSec = durSec; s.recordedEndMs = start + durSec * 1000L; return s;
    }
    private static JSONArray sent(GrowthTrackIntegrationTest.Http http) throws Exception {
        JSONArray all = new JSONArray();
        for (GrowthTrackIntegrationTest.Request r : http.requests) if ("/partner-ingest".equals(r.path)) {
            JSONArray a = new JSONObject(r.body).getJSONArray("sessions"); for (int i = 0; i < a.length(); i++) all.put(a.get(i));
        }
        return all;
    }
    private static Map<String, Integer> held(String id, int why) { Map<String, Integer> h = new HashMap<>(); h.put(id, why); return h; }

    /* ------------------------------------------------------------------ the outbox */

    @Test void aPartThatCanStillBeResumedIsNotSentAndTheWholeSessionGoesOnce() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); f.http.acceptAll = true;
        String grant = f.client.snapshot().connectionId;
        Model m = new Model();
        Model.Sess part = session("sess" + (T0 + 300_000), T0, 300); part.completed = false; part.growthTrackConnectionId = grant;
        m.gtSend.stoppedEarly = true; m.sessLog.all.add(part);
        // STOP filed it and synced at once - while Resume was still on offer.
        GrowthTrackOutbox box = GrowthTrackOutbox.take(m, grant, s -> run(s.durSec * 1000L), null, held(part.id, GrowthTrackHold.RESUME));
        assertTrue(box.items.isEmpty(), "held while it can still be resumed");
        f.client.sync(box);
        assertEquals(0, sent(f.http).length(), "nothing sent for it");
        assertEquals("Waiting · can still be resumed",
            GrowthTrackOutbox.statusOf(part, f.client.snapshot(), f.client.state("op_" + part.id), m, GrowthTrackHold.RESUME).text);
        assertEquals(GrowthTrackOutbox.Status.WAITING,
            GrowthTrackOutbox.statusOf(part, f.client.snapshot(), f.client.state("op_" + part.id), m, GrowthTrackHold.RESUME).state);
        // Resumed, ten more minutes, finished: what fileSession does.
        RunParts prior = RunParts.ofRecord(part); prior.resumes++;
        assertSame(part, RunParts.dropEarlier(m.sessLog.all, prior));
        long now = T0 + 300_000 + 60_000 + 600_000;
        Model.Sess whole = new Model.Sess(); whole.id = "sess" + now; whole.ts = now - 600_000; whole.durSec = 600;
        RunParts.foldTime(prior, whole);
        whole.recordedEndMs = now; whole.completed = true; whole.routineName = part.routineName; whole.growthTrackConnectionId = grant;
        RunParts.foldFigures(prior, whole); m.sessLog.all.add(whole);
        f.client.sync(GrowthTrackOutbox.take(m, grant, s -> run(s.durSec * 1000L), f.client.settled(), new HashMap<>()));
        JSONArray out = sent(f.http);
        assertEquals(1, out.length(), "sent once");
        assertEquals("op_" + part.id, out.getJSONObject(0).getString("external_session_id"));
        assertEquals(900, out.getJSONObject(0).getInt("total_duration_seconds"), "whole");
        assertEquals("Sent", GrowthTrackOutbox.statusOf(whole, f.client.snapshot(), f.client.state("op_" + whole.id), m, GrowthTrackHold.NONE).text);
        f.client.sync(GrowthTrackOutbox.take(m, grant, s -> run(s.durSec * 1000L), f.client.settled(), new HashMap<>()));
        assertEquals(1, sent(f.http).length(), "and only once");
    }

    @Test void aHeldPartGoesOnTheNextPassOnceItsChanceIsOver() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); f.http.acceptAll = true;
        String grant = f.client.snapshot().connectionId;
        Model m = new Model();
        Model.Sess part = session("declined", T0, 300); part.growthTrackConnectionId = grant; m.sessLog.all.add(part);
        f.client.sync(GrowthTrackOutbox.take(m, grant, s -> run(s.durSec * 1000L), null, held(part.id, GrowthTrackHold.REJOIN)));
        assertEquals(0, sent(f.http).length());
        // "Neither": no rejoin now. Final, and sent as it is.
        f.client.sync(GrowthTrackOutbox.take(m, grant, s -> run(s.durSec * 1000L), f.client.settled(), new HashMap<>()));
        assertEquals(1, sent(f.http).length());
        assertEquals(300, sent(f.http).getJSONObject(0).getInt("total_duration_seconds"));
    }

    @Test void aHeldPartAlreadyWaitingInTheQueueIsWithdrawnUntilFinal() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); String grant = f.client.snapshot().connectionId;
        Model m = new Model();
        Model.Sess part = session("queued", T0, 300); part.growthTrackConnectionId = grant; m.sessLog.all.add(part);
        assertTrue(f.client.recordCompleted(part, run(300_000), m, grant), "queued by an earlier build, offline");
        f.http.acceptAll = true;
        f.client.sync(GrowthTrackOutbox.take(m, grant, s -> run(s.durSec * 1000L), null, held(part.id, GrowthTrackHold.REJOIN)));
        assertEquals(0, f.client.snapshot().queued, "taken out of the queue while it can be rejoined");
        assertEquals(0, sent(f.http).length());
    }

    @Test void theOldTakeStillDraftsEverything() {
        Model m = new Model();
        Model.Sess s = session("plain", T0, 120); s.growthTrackConnectionId = "conn"; m.sessLog.all.add(s);
        assertEquals(1, GrowthTrackOutbox.take(m, "conn", x -> run(120_000), null).items.size());
        assertEquals(1, GrowthTrackOutbox.take(m, "conn", x -> run(120_000), null, null).items.size());
    }

    /* ------------------------------------------------------------------ what holds it */

    @Test void theWordsSayWhyItWaits() {
        assertEquals("Waiting · can still be resumed", GrowthTrackHold.words(GrowthTrackHold.RESUME));
        assertEquals("Waiting · can still be rejoined", GrowthTrackHold.words(GrowthTrackHold.REJOIN));
        assertEquals("Waiting · sends when the run ends", GrowthTrackHold.words(GrowthTrackHold.RUNNING));
        assertNull(GrowthTrackHold.words(GrowthTrackHold.NONE));
    }

    @Test void theHoldsComeFromEachWayBackAndARunningOneSaysSo() {
        Map<String, Integer> h = GrowthTrackHold.of("a", "b", "c", "d");
        assertEquals(GrowthTrackHold.RUNNING, h.get("a"));
        assertEquals(GrowthTrackHold.RESUME, h.get("b"));
        assertEquals(GrowthTrackHold.REJOIN, h.get("c"));
        assertEquals(GrowthTrackHold.REJOIN, h.get("d"));
        assertTrue(GrowthTrackHold.of(null, "", null, null).isEmpty(), "nothing in play, nothing held");
        assertEquals(GrowthTrackHold.RUNNING, GrowthTrackHold.of("x", "x", null, "x").get("x"), "being continued now");
        Model.Sess s = session("x", T0, 60);
        assertEquals(GrowthTrackHold.RUNNING, GrowthTrackHold.kind(h, session("a", T0, 60)));
        assertEquals(GrowthTrackHold.NONE, GrowthTrackHold.kind(h, s));
        assertEquals(GrowthTrackHold.NONE, GrowthTrackHold.kind(null, s));
    }

    @Test void theRunSnapshotHoldsItsFiledPartWhileAColdOpenWouldStillOfferIt() {
        Model m = new Model();
        Model.Routine r = new Model.Routine(); r.id = "r1"; r.name = "Fixture"; m.routines.add(r);
        Model.Sess filed = session("sess-destroyed", T0, 300);
        String parts = RunParts.ofRecord(filed).code();
        long now = T0 + 600_000;
        assertEquals("sess-destroyed", GrowthTrackHold.snapshot(m, "r1", 2, false, T0, parts, now, MAX_AGE));
        assertNull(GrowthTrackHold.snapshot(m, null, 2, false, T0, parts, now, MAX_AGE), "no snapshot");
        assertNull(GrowthTrackHold.snapshot(m, "r1", -1, false, T0, parts, now, MAX_AGE), "no step");
        assertNull(GrowthTrackHold.snapshot(m, "r1", 2, true, T0, parts, now, MAX_AGE), "a manual run is never offered");
        assertNull(GrowthTrackHold.snapshot(m, "gone", 2, false, T0, parts, now, MAX_AGE), "its routine deleted");
        assertNull(GrowthTrackHold.snapshot(m, "r1", 2, false, 0L, parts, now, MAX_AGE), "never started");
        assertNull(GrowthTrackHold.snapshot(m, "r1", 2, false, T0, parts, T0 + MAX_AGE + 1, MAX_AGE), "too old to offer");
        assertEquals("sess-destroyed", GrowthTrackHold.snapshot(m, "r1", 2, false, T0, parts, T0 + MAX_AGE, MAX_AGE));
        assertNull(GrowthTrackHold.snapshot(m, "r1", 2, false, T0, "", now, MAX_AGE), "no parts kept");
        assertNull(GrowthTrackHold.snapshot(m, "r1", 2, false, T0, new RunParts().code(), now, MAX_AGE), "nothing filed yet");
    }

    /* ------------------------------------------------------------------ STOP's offer */

    @Test void stopsOfferHoldsThePartFromItsFilingUntilItsTimeIsUp() {
        ResumeOffer<String> o = new ResumeOffer<String>();
        assertNull(o.held(0L), "nothing filed");
        o.holding("part");
        assertEquals("part", o.held(0L), "filed, the offer still to come");
        assertNull(o.offer("resume", true));
        assertEquals("part", o.held(60_000L), "kept under the question, however long it is up");
        o.questionGone();
        o.shown("resume", 100_000L, Snack.HOLD_MS_ACTIONABLE);
        assertEquals("part", o.held(100_000L + Snack.HOLD_MS_ACTIONABLE - 1));
        assertFalse(o.ended(100_000L + Snack.HOLD_MS_ACTIONABLE - 1));
        assertNull(o.held(100_000L + Snack.HOLD_MS_ACTIONABLE), "its time up: final");
        assertTrue(o.ended(100_000L + Snack.HOLD_MS_ACTIONABLE), "said once");
        assertFalse(o.ended(100_000L + Snack.HOLD_MS_ACTIONABLE + 1));
    }

    @Test void resumeTakenOrNoOfferAfterAllEndsTheOffersHold() {
        ResumeOffer<String> o = new ResumeOffer<String>();
        o.holding("part"); o.offer("resume", false); o.shown("resume", 0L, 5000);
        o.spent();
        assertNull(o.held(1L), "the resumed run holds it now, not the offer");
        o.holding("again");
        assertTrue(o.release(), "not offered after all");
        assertNull(o.held(1L));
        assertFalse(o.release());
        o.shown("old", 0L, 5000);
        o.holding("newer");
        assertEquals("newer", o.held(10_000L), "an earlier offer's time is not the newer part's");
    }

    /* ------------------------------------------------------------------ the app's wiring */

    private static String src(String file) throws Exception {
        Path p = Paths.get("..", "app", "src", "main", "java", "org", "openpump", file);
        if (!Files.exists(p)) p = Paths.get("app", "src", "main", "java", "org", "openpump", file);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
    private static String body(String code, String head) {
        int at = code.indexOf(head);
        assertTrue(at >= 0, "found: " + head);
        int open = code.indexOf('{', at), depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return code.substring(open, i + 1);
        }
        return code.substring(open);
    }

    @Test void everySyncPassAndTheListAskWhatIsHeld() throws Exception {
        String mgr = src("GrowthTrackManager.java");
        assertTrue(body(mgr, "private void sync(Model model").contains("GrowthTrackOutbox.take(model, connectionId(), lookup, settled, holds(model))"),
            "every pass - the screen's, the job's - leaves held sessions out");
        String list = body(src("GrowthTrackActivity.java"), "private void listPage()");
        assertTrue(list.contains("manager.holds(model)") && list.contains("GrowthTrackHold.kind("), "the list says why one waits");
    }

    @Test void theRunScreenHoldsWhatItCanStillContinueAndSyncsWhenThatEnds() throws Exception {
        String sa = src("SessionActivity.java");
        String holds = body(sa, "java.util.Map<String, Integer> gtHolds()");
        assertTrue(holds.contains("runPrior") && holds.contains("resumeOffer.held(") && holds.contains("rejoinAsked")
            && holds.contains("gtSnapshotHold("), "every way back counts");
        String file = body(sa, "private void fileSession(boolean aborted, long now)");
        int hold = file.indexOf("resumeOffer.holding(rec.id)"), sync = file.indexOf("sync.startSync(model, null)");
        assertTrue(hold >= 0 && hold < sync, "STOP's part is held before the sync its filing starts");
        String stop = body(sa, "private void stopWithResumeOffer()");
        int resumable = stop.indexOf("stopResumable = "), finish = stop.indexOf("finishSession(true)");
        assertTrue(resumable >= 0 && resumable < finish, "decided before the filing");
        assertTrue(stop.contains("resumeOffer.release()"), "no offer after all: final at once");
        assertTrue(body(sa, "private final class ResumeSnack").contains("gtOfferEnds"), "its time up, the next pass sends it");
        assertTrue(body(sa, "private final class ResumeAfterStopTap").contains("gtHoldEnded()"), "a refused Resume ends the hold");
        String offer = body(sa, "private boolean offerRunSnapshot()");
        assertTrue(offer.contains("rejoinAsked = ") && offer.contains("setOnDismissListener("), "held while the rejoin is asked");
    }

    @Test void theStopStillVentsFirst() throws Exception {
        String stop = body(src("SessionActivity.java"), "private void stopWithResumeOffer()");
        int vent = stop.indexOf("stopBecause(RunStopReason.WHY_STOP)"), finish = stop.indexOf("finishSession(true)");
        assertTrue(vent >= 0 && vent < finish, "the stop's own order is unchanged");
        assertFalse(stop.substring(0, finish).contains("startSync"), "nothing networked before the stop");
    }

    /* ------------------------------------------------------------------ an update GrowthTrack keeps the earlier copy of */

    @Test void anUpdateAnsweredAlreadyPresentStaysSentAndSaysTheLaterChangesStayed() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        Model m = new Model();
        Model.Sess part = session("sess-part", T0, 300); part.completed = false; part.growthTrackConnectionId = grant;
        assertTrue(f.client.recordCompleted(part, run(300_000), m, grant));
        f.http.responses.add(GrowthTrackIntegrationTest.ok(1, 0, new JSONArray())); f.client.syncPending();
        // A part an earlier build sent, then resumed: the whole session under the same id.
        RunParts prior = RunParts.ofRecord(part); prior.resumes++;
        Model.Sess whole = new Model.Sess(); whole.id = "sess-whole"; whole.ts = T0 + 900_000; whole.durSec = 600;
        RunParts.foldTime(prior, whole); whole.recordedEndMs = T0 + 1_500_000; whole.completed = true;
        whole.routineName = part.routineName; whole.growthTrackConnectionId = grant; RunParts.foldFigures(prior, whole);
        assertTrue(f.client.recordCompleted(whole, run(600_000), m, grant));
        f.http.responses.add(GrowthTrackIntegrationTest.ok(0, 1, new JSONArray()));   // GrowthTrack: already present
        f.client.syncPending();
        GrowthTrackOutbox.Status s = GrowthTrackOutbox.statusOf(whole, f.client.snapshot(), f.client.state("op_" + whole.id), m);
        assertEquals(GrowthTrackOutbox.Status.SENT, s.state, "what GrowthTrack holds was sent");
        assertEquals("Sent · later changes stayed on this phone", s.text);
        assertEquals("Last send: 1 session received. Later changes to 1 session stayed on this phone.",
            GrowthTrackWords.latest(f.client.snapshot().message));
        assertFalse(f.client.recordCompleted(whole, run(600_000), m, grant), "not sent again and again");
    }

    @Test void anUpdateGrowthTrackTookIsSentAndAPlainRetryAnsweredAlreadyPresentIsSent() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        Model m = new Model();
        Model.Sess retried = session("retried", T0, 120); retried.growthTrackConnectionId = grant;
        assertTrue(f.client.recordCompleted(retried, run(120_000), m, grant));
        f.http.responses.add(GrowthTrackIntegrationTest.ok(0, 1, new JSONArray())); f.client.syncPending();
        assertEquals("Sent", GrowthTrackOutbox.statusOf(retried, f.client.snapshot(), f.client.state("op_retried"), m).text,
            "a first send it already had (an earlier attempt that did arrive) is simply sent");
        assertEquals("Last send: 1 session received.", GrowthTrackWords.latest(f.client.snapshot().message));
        Model.Sess part = session("taken", T0, 300); part.growthTrackConnectionId = grant;
        assertTrue(f.client.recordCompleted(part, run(300_000), m, grant));
        f.http.responses.add(GrowthTrackIntegrationTest.ok(1, 0, new JSONArray())); f.client.syncPending();
        part.resumes = 1;
        assertTrue(f.client.recordCompleted(part, run(300_000), m, grant));
        f.http.responses.add(GrowthTrackIntegrationTest.ok(1, 0, new JSONArray())); f.client.syncPending();
        assertEquals("Sent", GrowthTrackOutbox.statusOf(part, f.client.snapshot(), f.client.state("op_taken"), m).text, "accepted: updated");
    }

    /* ------------------------------------------------------------------ GrowthTrack's own page's name */

    @Test void disconnectNamesGrowthTracksPageAsItIsCalled() throws Exception {
        String gt = body(src("GrowthTrackActivity.java"), "case DISCONNECT:");
        assertTrue(gt.contains("Connected apps and devices"), "GrowthTrack's page is \"Connected apps and devices\"");
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        f.client.disconnect();
        assertTrue(f.client.snapshot().message.contains("Connected apps and devices"), f.client.snapshot().message);
    }

    /* ------------------------------------------------------------------ docs */

    @Test void noDocStillPromisesAResumedSessionIsSentAgainAsAnUpdate() throws Exception {
        List<Path> docs = new ArrayList<>();
        Path root = Files.exists(Paths.get("..", "docs")) ? Paths.get("..") : Paths.get(".");
        try (java.util.stream.Stream<Path> w = Files.walk(root.resolve("docs"))) { w.filter(p -> p.toString().endsWith(".md")).forEach(docs::add); }
        for (Path p : Arrays.asList(root.resolve("CHANGELOG.md"), root.resolve("ARCHITECTURE.md"))) if (Files.exists(p)) docs.add(p);
        for (Path p : docs) {
            String t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
            assertFalse(t.contains("as an update"), p + " still says a resumed session is sent as an update");
            assertFalse(t.contains("replaces the fragment already sent"), p + " still says a resumed session replaces what was sent");
        }
    }
}
