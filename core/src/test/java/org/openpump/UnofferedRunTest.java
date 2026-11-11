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
import org.junit.jupiter.api.Test;

/**
 * A RUN THE APP LOST AND NEVER OFFERED BACK IS KEPT TOO (the owner's decision, 11 Nov 2026).
 *
 * DeclinedRejoinTest keeps the part when the rejoin is offered. No rejoin is offered for a
 * manual run, a routine deleted or edited shorter since, or a snapshot past the rejoin window;
 * those parts were still lost. Now each is filed the same way - a session stopped early, from
 * the snapshot's figures and the rows the run persisted - under the routine
 * DeclinedRun#unoffered names, once, and never held for an answer nobody is asked. Fictional
 * records; nothing here can reach a network or the pump.
 */
class UnofferedRunTest {
    private static final long START = 1_794_347_458_331L;

    private static Model model() {
        Model m = new Model();
        Model.Routine r = new Model.Routine(); r.id = "r3"; r.name = "Starter Routine";
        m.routines.add(r);
        return m;
    }

    private static AsRun rows(String rid, String name, boolean manual) {
        AsRun a = new AsRun(rid, name, null, manual, 40);
        AsRun.Row r = GrowthTrackIntegrationTest.row(AsRun.PLAN, 839, -1L, 0);
        r.setId = "s5"; r.up = 10; r.upReq = 10; r.lo = 5; r.uhWire = 30; r.uhReq = 30; r.lh = 8;
        r.sp = 50; r.cyclePart = 0;
        a.rows.add(r);
        return a;
    }

    private static RunParts snapshot(Double net) {
        return RunParts.sofar(null, START, 61_973L, net, net == null ? null : Double.valueOf(59.0),
            210.2, 10.0, 2);
    }

    private static Model.Sess file(Model m, Model.Routine r, RunParts sofar, AsRun rows, String shape) {
        return DeclinedRun.record(m, r, sofar, DeclinedRun.earlier(m.sessLog.all, sofar),
            DeclinedRun.closed(rows, sofar.playedMs), START + 62_100L, START + 7L * 3_600_000L,
            false, shape, 3, 5);
    }

    @Test void aManualRunIsKeptAsAManualSessionStoppedEarly() {
        Model m = model();
        Model.Routine r = DeclinedRun.unoffered(m, Manual.ID, true);
        assertEquals(Manual.ID, r.id, "a manual run's own ephemeral routine, as fileSession files it");
        AsRun run = rows(Manual.ID, "Manual run", true);
        Model.Sess s = file(m, r, snapshot(null), run, "x");
        assertNotNull(s, "the manual minute is kept");
        assertTrue(s.manual, "never read as a routine session");
        assertFalse(s.completed);
        assertEquals(Summary.TAG_MANUAL_STOPPED, s.tag);
        assertEquals("", s.shape, "every manual run's shape is empty");
        assertFalse(s.returnDay);
        assertEquals(61, s.durSec);
        assertEquals(RunStopReason.WHY_APP_CLOSED, s.stopWhy);
        assertEquals(Model.TRAINER_TRACK_NONE, s.countedTrack);
        // GrowthTrack: the normal rules - stopped early first, then the manual switch.
        m.gtSend = new GrowthTrackSettings();
        GrowthTrackOutbox.file(s, "conn", m, DeclinedRun.closed(run, 61_973L));
        assertEquals(GrowthTrackSettings.HELD_STOPPED, s.gtHeldWhy, "off by default");
        m.gtSend.stoppedEarly = true;
        m.gtSend.manualRuns = false;
        GrowthTrackOutbox.file(s, "conn", m, DeclinedRun.closed(run, 61_973L));
        assertEquals(GrowthTrackSettings.HELD_MANUAL, s.gtHeldWhy);
        m.gtSend.manualRuns = true;
        GrowthTrackOutbox.file(s, "conn", m, DeclinedRun.closed(run, 61_973L));
        assertEquals("conn", s.growthTrackConnectionId);
        assertEquals(GrowthTrackSettings.HELD_NONE, s.gtHeldWhy);
    }

    @Test void aDeletedRoutinesRunIsKeptAsFileSessionNamesIt() {
        Model m = model();
        Model.Routine r = DeclinedRun.unoffered(m, "gone", false);
        assertEquals("", r.id);
        assertEquals("(routine deleted)", r.name);
        Model.Sess s = file(m, r, snapshot(48.7), rows("gone", "Old Routine", false), "");
        assertNotNull(s);
        assertEquals("", s.routineId);
        assertEquals("(routine deleted)", s.routineName);
        assertFalse(s.manual);
        assertFalse(s.completed);
        assertEquals(Summary.TAG_STOPPED, s.tag);
        assertEquals(61, s.durSec);
        assertEquals(48.7, s.netTupSec, 1e-9);
        assertTrue(m.routines.size() == 1, "nothing is made up in the routine list");
    }

    @Test void aRoutineStillThereIsFiledUnderIt() {
        // Too old to offer, or edited shorter: the routine as it stands, as the offer would.
        Model m = model();
        Model.Routine r = DeclinedRun.unoffered(m, "r3", false);
        assertSame(m.routine("r3"), r);
        Model.Sess s = file(m, r, snapshot(48.7), rows("r3", "Starter Routine", false), "");
        assertNotNull(s);
        assertEquals("r3", s.routineId);
        assertFalse(s.manual);
        assertEquals(Summary.TAG_STOPPED, s.tag);
        assertEquals(START, s.ts);
        assertEquals(3, s.presetsDone);
        assertEquals(5, s.presetsPlanned);
    }

    @Test void nothingWorthFilingStillFilesNothing() {
        Model m = model();
        Model.Routine man = DeclinedRun.unoffered(m, Manual.ID, true);
        assertNull(file(m, man, RunParts.sofar(null, START, 400L, null, null, 0.0, null, 0),
            rows(Manual.ID, "Manual run", true), ""), "under a second");
        assertNull(file(m, DeclinedRun.unoffered(m, "gone", false), snapshot(48.7), new AsRun(), ""), "no rows");
    }

    @Test void filedOnceItIsCovered() {
        // The snapshot's clearing lost with the process: on the next start the record covers it.
        Model m = model();
        RunParts sofar = snapshot(48.7);
        Model.Sess s = file(m, DeclinedRun.unoffered(m, "gone", false), sofar, rows("gone", "x", false), "");
        m.sessLog.all.add(s);
        DeclinedRun.learn(sofar, s);
        assertSame(s, DeclinedRun.covering(m.sessLog.all, sofar));
        assertSame(s, DeclinedRun.covering(m.sessLog.all, snapshot(48.7)), "by its start, too");
        // A manual one, whose snapshot never learnt an id: covered by its start, as well.
        Model m2 = model();
        RunParts manSofar = snapshot(null);
        Model.Sess man = file(m2, DeclinedRun.unoffered(m2, Manual.ID, true), manSofar,
            rows(Manual.ID, "Manual run", true), "");
        m2.sessLog.all.add(man);
        assertSame(man, DeclinedRun.covering(m2.sessLog.all, snapshot(null)));
    }

    @Test void everyWayNotOfferedFilesItAndNeverHoldsIt() throws Exception {
        String sa = new String(Files.readAllBytes(Paths.get("../app/src/main/java/org/openpump/SessionActivity.java")),
            StandardCharsets.UTF_8);
        int offer = sa.indexOf("private boolean offerRunSnapshot()");
        String body = sa.substring(offer, sa.indexOf("\n    }\n", offer));
        int cleared = body.indexOf("clearRunSnapshot();");
        int dialog = body.indexOf(".setTitle(\"A run did not finish\")");
        // Not offered (manual, deleted, too old): filed under DeclinedRun.unoffered, not held.
        int notOffered = body.indexOf("DeclinedRun.unoffered(");
        assertTrue(cleared > 0 && notOffered > cleared && notOffered < dialog,
            "filed once the snapshot is cleared, so a next start cannot file it again");
        assertFalse(body.contains("if (man) return false;"), "a manual run is no longer dropped");
        assertFalse(body.contains("if (r == null) return false;"), "nor a deleted routine's");
        // Edited shorter: filed too, then its hold ends.
        int shorter = body.indexOf("where.idx < 0 || where.idx >= where.size");
        String shorterArm = body.substring(shorter, body.indexOf("return false;", shorter));
        assertTrue(shorterArm.contains("fileDeclinedPart(") && shorterArm.contains("false)"),
            "an edit that took its step away files it, not held");
        // The offered call holds it; the others do not.
        String offered = body.substring(body.indexOf("fileDeclinedPart(", shorter + shorterArm.length()), dialog);
        assertTrue(offered.contains(", true)"), "held while the offer asks");
        int fn = sa.indexOf("private void fileDeclinedPart(");
        String fileBody = sa.substring(fn, sa.indexOf("\n    }\n", fn));
        assertTrue(fileBody.contains("if (offered) rejoinAsked = rec.id;"), "no answer to wait for");
        assertTrue(fileBody.contains("Deload.onFiled(model, rec.ts, r.trainerTrack, rec.manual, true)"),
            "a manual part spends no taper step, as fileSession's");
    }
}
