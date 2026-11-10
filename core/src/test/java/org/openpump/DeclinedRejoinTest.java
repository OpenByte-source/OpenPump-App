package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A RUN THE APP LOST, NOT REJOINED, IS KEPT AS A SESSION STOPPED EARLY (run 4 of the live
 * GrowthTrack test, 10 Nov 2026, check 4; the owner's decision).
 *
 * Starter Routine, force-stopped about 61 s in, reopened, "Neither". A killed process files
 * nothing, and declining the rejoin filed nothing either: the minute of work was lost from
 * History and from GrowthTrack, its rows orphaned in asrun.json. Now the part the run snapshot
 * holds is filed as a session stopped early - from the snapshot's figures and the rows the run
 * persisted, on the recording's clock (RunParts#foldTime) - once, when the offer is put, so an
 * answer never tapped (the app closed again) loses nothing either; a rejoin replaces it with
 * the one session, as it replaces a part a destroyed screen filed. Fictional records shaped by
 * run 4's data; nothing here can reach a network or the pump.
 */
class DeclinedRejoinTest {
    private static final long START = 1_794_347_458_331L;      // run 4, check 4: the killed run's start

    private static Model model(int track) {
        Model m = new Model();
        Model.Routine r = new Model.Routine(); r.id = "r3"; r.name = "Starter Routine";
        r.trainerTrack = track;
        m.routines.add(r);
        return m;
    }

    /** What the run persisted under its provisional key: the step, its last row still open. */
    private static AsRun killedRows() {
        AsRun a = new AsRun("r3", "Starter Routine", null, false, 40);
        AsRun.Row r = GrowthTrackIntegrationTest.row(AsRun.PLAN, 839, -1L, 0);
        r.setId = "s5"; r.up = 10; r.upReq = 10; r.lo = 5; r.uhWire = 30; r.uhReq = 30; r.lh = 8;
        r.sp = 50; r.cyclePart = 0;
        a.rows.add(r);
        return a;
    }

    /** The snapshot's figures: "p3;<start>;61000;...", as saveRunSnapshot wrote them last. */
    private static RunParts snapshot(long playedMs) {
        RunParts p = RunParts.sofar(null, START, playedMs, 48.7, 59.0, 210.2, 10.0, 2);
        p.worthSec = 29.06;
        return p;
    }

    private static Model.Sess decline(Model m, RunParts sofar, AsRun rows) {
        Model.Routine r = m.routine("r3");
        Model.Sess earlier = DeclinedRun.earlier(m.sessLog.all, sofar);
        return DeclinedRun.record(m, r, sofar, earlier, DeclinedRun.closed(rows, sofar.playedMs),
            START + 62_100L, START + 300_000L, false, "", 1, 1);
    }

    @Test void theKilledPartIsFiledAsASessionStoppedEarly() throws Exception {
        Model m = model(Model.TRAINER_TRACK_NONE);
        RunParts sofar = snapshot(61_973L);
        AsRun rows = DeclinedRun.closed(killedRows(), sofar.playedMs);
        Model.Sess s = decline(m, sofar, killedRows());
        assertNotNull(s, "the minute of work is kept");
        assertEquals(START, s.ts, "under the run's start, where its rows are");
        assertEquals(61, s.durSec, "the time the snapshot counted, on the recording's clock");
        assertFalse(s.completed, "stopped early");
        assertEquals(RunStopReason.WHY_APP_CLOSED, s.stopWhy);
        assertEquals(Model.TRAINER_TRACK_NONE, s.countedTrack, "never counted toward training");
        assertEquals(0, s.rejoins, "declined: it was not rejoined");
        assertEquals("r3", s.routineId);
        assertEquals(START, s.recordedStartMs);
        assertEquals(START + 62_100L, s.recordedEndMs, "ends when the run last wrote its snapshot");
        assertEquals(48.7, s.netTupSec, 1e-9);
        assertTrue(s.tupOwnPull, "an own routine's time is its own pull's, as fileSession files it");
        assertEquals(29.06, s.countWorthSec, 1e-9);
        assertEquals(10.0, s.peakKpa, 1e-9);
        assertTrue(s.hasAsRun);
        assertEquals(61_973, rows.rows.get(0).t1, "the open row closed where the snapshot had counted");
        assertEquals(Summary.TAG_STOPPED, s.tag);

        // GrowthTrack: only with "Include sessions stopped early", through the filing marker.
        m.gtSend = new GrowthTrackSettings();
        GrowthTrackOutbox.file(s, "conn", m, rows);
        assertEquals("", s.growthTrackConnectionId, "the switch is off by default");
        assertEquals(GrowthTrackSettings.HELD_STOPPED, s.gtHeldWhy, "\"Not sent\", stopped early");
        m.gtSend.stoppedEarly = true;
        GrowthTrackOutbox.file(s, "conn", m, rows);
        assertEquals("conn", s.growthTrackConnectionId);
        assertEquals(GrowthTrackSettings.HELD_NONE, s.gtHeldWhy);
        assertEquals(61, GrowthTrackSession.draft(s, rows).seconds, "and its draft is consistent");
    }

    @Test void nothingPlayedFilesNothing() {
        Model m = model(Model.TRAINER_TRACK_NONE);
        assertNull(decline(m, snapshot(900L), killedRows()), "under a second played");
        assertNull(decline(m, snapshot(61_973L), new AsRun()), "no rows");
        assertNull(decline(m, snapshot(61_973L), null));
        assertNull(DeclinedRun.record(m, m.routine("r3"), null, null, killedRows(), 0L, START, false, "", 1, 1));
    }

    @Test void aRunAlreadyFiledIsNotFiledAgain() {
        // Closed from Recents: onDestroy filed the part, and the snapshot learnt that record
        // (noteFiledInSnapshot writes RunParts.ofRecord(rec)) - it holds nothing more.
        Model m = model(Model.TRAINER_TRACK_NONE);
        Model.Sess filed = GrowthTrackIntegrationTest.session("sess1794347292067");
        filed.ts = START; filed.durSec = 83; filed.completed = false; filed.routineId = "r3";
        m.sessLog.all.add(filed);
        RunParts learnt = RunParts.ofRecord(filed);
        assertSame(filed, DeclinedRun.covering(m.sessLog.all, learnt));
        // ...and a declined run filed once, its snapshot's clearing lost with the process:
        // offered again, the record it already filed covers it.
        Model m2 = model(Model.TRAINER_TRACK_NONE);
        RunParts sofar = snapshot(61_973L);
        Model.Sess once = decline(m2, sofar, killedRows());
        m2.sessLog.all.add(once);
        assertSame(once, DeclinedRun.covering(m2.sessLog.all, snapshot(61_973L)));
        assertNull(DeclinedRun.covering(m2.sessLog.all, snapshot(75_000L)), "more played since: not covered");
    }

    @Test void earlierPartsFoldIntoTheOneSession() {
        // Run 4 check 1's shape: STOP at 1:31 (filed), Resume; killed 30.7 s into the resumed
        // part; declined. The one session replaces the filed part, as a rejoin's filing would.
        Model m = model(Model.TRAINER_TRACK_NONE);
        Model.Sess a = GrowthTrackIntegrationTest.session("sess1794345272584");
        a.ts = START; a.durSec = 91; a.completed = false; a.routineId = "r3"; a.hasAsRun = true;
        a.netTupSec = 40.0;
        m.sessLog.all.add(a);
        RunParts resumed = RunParts.ofRecord(a); resumed.resumes++;
        RunParts sofar = RunParts.sofar(resumed, START + 200_000L, 30_700L, 12.0, 20.0, 50.0, 9.0, 1);
        assertNull(DeclinedRun.covering(m.sessLog.all, sofar));
        assertSame(a, DeclinedRun.earlier(m.sessLog.all, sofar));
        Model.Sess s = decline(m, sofar, killedRows());
        assertEquals(121, s.durSec, "91 s + 30.700 s");
        assertEquals(START, s.ts);
        assertEquals(1, s.resumes);
        assertEquals("sess1794345272584", s.syncId, "GrowthTrack knows it by its first part's id");
        assertEquals(52.0, s.netTupSec, 1e-9, "both parts' time, once");
        assertFalse(s.completed);
    }

    @Test void itCountsLikeAStopNobodyCounted() {
        // A plan session: its time against the plan's floor, as the snapshot counted it; the
        // routine's done count and the measurement cadence untouched (they move only for a
        // completed run, never a STOP).
        Model m = model(Plan.TRACK_GIRTH_INTERVAL);
        Model.Routine r = m.routine("r3");
        int done = r.done, runs = r.versionRuns, sinceN = m.meas.sinceN;
        double sinceH = m.meas.sinceH;
        Model.Sess s = decline(m, snapshot(61_973L), killedRows());
        assertFalse(s.tupOwnPull, "a plan session keeps the plan's figure");
        assertEquals(48.7, s.netTupSec, 1e-9);
        assertNull(s.countWorthSec);
        assertEquals(Model.TRAINER_TRACK_NONE, s.countedTrack);
        assertEquals(done, r.done);
        assertEquals(runs, r.versionRuns);
        assertEquals(sinceN, m.meas.sinceN);
        assertEquals(sinceH, m.meas.sinceH, 0.0);
        assertTrue(m.sessLog.all.isEmpty(), "the record is built, filed by the screen");
    }

    @Test void aRejoinAfterItReplacesItWithTheOneSession() {
        Model m = model(Model.TRAINER_TRACK_NONE);
        RunParts sofar = snapshot(61_973L);
        Model.Sess kept = decline(m, sofar, killedRows());
        m.sessLog.all.add(kept);
        // The offer's prior learns the record, as noteFiledInSnapshot's does; then Rejoin.
        DeclinedRun.learn(sofar, kept);
        sofar.rejoins++;
        assertEquals(kept.id, sofar.sessId);
        List<Model.Sess> log = m.sessLog.all;
        Model.Sess earlier = RunParts.dropEarlier(log, sofar);
        assertSame(kept, earlier);
        Model.Sess whole = new Model.Sess(); whole.id = "sess" + (START + 400_000L);
        whole.durSec = 180; whole.ts = START + 220_000L;
        RunParts.foldTime(sofar, whole, 180_912L);
        RunParts.foldFigures(sofar, whole);
        log.add(whole);
        assertEquals(1, log.size(), "one session");
        assertEquals(242, whole.durSec);
        assertEquals(START, whole.ts);
        assertEquals(1, whole.rejoins);
        assertEquals(kept.id, whole.syncId);
    }

    @Test void theOfferFilesItAndTheStopIsUntouched() throws Exception {
        String sa = new String(Files.readAllBytes(Paths.get("../app/src/main/java/org/openpump/SessionActivity.java")),
            StandardCharsets.UTF_8);
        int offer = sa.indexOf("private boolean offerRunSnapshot()");
        int dialog = sa.indexOf(".setTitle(\"A run did not finish\")", offer);
        int filing = sa.indexOf("fileDeclinedPart(", offer);
        assertTrue(offer > 0 && filing > offer && filing < dialog, "filed when the offer is put, before its dialog");
        assertTrue(sa.contains("DeclinedRun.record("), "from the snapshot's figures");
        int stop = sa.indexOf("private final class StopFromSnapshotTap");
        String stopTap = sa.substring(stop, sa.indexOf("\n    }\n", stop));
        assertFalse(stopTap.contains("DeclinedRun") || stopTap.contains("sessLog"),
            "Stop the pump vents at once and files nothing itself");
        int fn = sa.indexOf("private void fileDeclinedPart(");
        String body = sa.substring(fn, sa.indexOf("\n    }\n", fn));
        assertTrue(body.contains("GrowthTrackOutbox.file("), "the filing marker, and why not");
        assertTrue(body.contains("startSync("), "a sync, so it goes when allowed");
        assertTrue(body.contains("Deload.onFiled("), "the taper, as any filing that got underway");
        assertFalse(body.contains(".done++") || body.contains("meas.sinceN"), "not a completion");
    }
}
