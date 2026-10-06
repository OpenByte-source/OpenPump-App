package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * STOP, RESUME, STOP AGAIN FILES ONE SESSION (device check EMU10, High).
 *
 * On the device the resumed part was never filed: no "STOPPED after" line, the summary and
 * Today showed the first part only (1:23), and no "Resumed once after STOP" line. The first
 * STOP's filing claims Session's once-only latch; only beginRunFlow re-armed it
 * (session.beginAttempt()), and a Resume goes through RejoinRunTap, never beginRunFlow. So the
 * second STOP's fileSession returned at its "already filed" check. A kill and Rejoin worked
 * only because a new process starts with a fresh latch.
 *
 * The fix: the rejoin path starts a new attempt, as beginRunFlow does. The merge (RunParts)
 * replaces the first part's record, so the re-armed latch can never make two rows.
 *
 * And the Low: the Resume message, timed out, left the summary under a late tap (it
 * recorded a "Too much"). An action message now swallows taps while up, and for a moment
 * after it goes, in its own place.
 */
class ResumeFilingTest {

    /** The filing seam as SessionActivity runs it: the latch, then the earlier parts. */
    private static final class Run {
        final Session session = new Session();
        final Model.SessLog log = new Model.SessLog();
        RunParts runPrior;
        Model.Sess filed;

        /** fileSession's own order: claim, drop the earlier record, fold, file. */
        boolean file(Model.Sess part) {
            if (!session.markRunFiled()) return false;      // already filed by another exit
            RunParts parts = runPrior;
            runPrior = null;
            Model.Sess earlier = RunParts.dropEarlier(log.all, parts);
            RunParts.foldTime(parts, part);
            RunParts.carryBefore(earlier, part);
            RunParts.foldFigures(parts, part);
            log.file(part);
            filed = part;
            return true;
        }

        /** stopWithResumeOffer: what a Resume carries from the part STOP just filed. */
        RunParts resumeCarry() {
            RunParts p = RunParts.ofRecord(filed);
            p.resumes++;
            return p;
        }
    }

    private static Model.Sess part(String id, long ts, long durSec, Double net, Double gross,
                                   double dose, Double peak, int cycles) {
        Model.Sess s = new Model.Sess();
        s.id = id; s.ts = ts; s.durSec = durSec;
        s.netTupSec = net; s.grossTupSec = gross;
        s.doseKpaS = dose; s.peakKpa = peak; s.cyclesDone = cycles;
        return s;
    }

    @Test void stopResumeStopFilesOneMergedSession() {
        Run run = new Run();
        run.log.file(part("older", 100L, 600, 1.0, 1.0, 1.0, 1.0, 1));
        run.session.beginAttempt();                                   // START
        assertTrue(run.file(part("p1", 1_000_000L, 83, 40.0, 50.0, 300.0, 29.0, 3)),
            "the first STOP files the first part");
        // Resume: what RejoinRunTap does - a new attempt, carrying the first part.
        RunParts carried = run.resumeCarry();
        run.session.beginAttempt();
        run.runPrior = carried;
        // The second STOP, 50 s later.
        assertTrue(run.file(part("p2", 1_100_000L, 50, 20.0, 25.0, 100.0, 31.0, 2)),
            "the resumed part is filed");
        assertEquals(2, run.log.all.size(), "ONE row for the run (beside the older one)");
        Model.Sess one = run.log.all.get(0);
        assertEquals(133L, one.durSec, "1:23 + 0:50");
        assertEquals(1_000_000L, one.ts, "it starts when the first part did");
        assertEquals(60.0, one.netTupSec.doubleValue(), 1e-9);
        assertEquals(75.0, one.grossTupSec.doubleValue(), 1e-9);
        assertEquals(400.0, one.doseKpaS, 1e-9);
        assertEquals(31.0, one.peakKpa.doubleValue(), 1e-9);
        assertEquals(5, one.cyclesDone);
        assertEquals(1, one.resumes);
        assertEquals("Resumed once after STOP", RunParts.line(one.rejoins, one.resumes));
        // And another exit (onDestroy) after it cannot file the run a second time.
        assertFalse(run.file(part("p3", 1_200_000L, 1, null, null, 0.0, null, 0)),
            "never two rows: the latch holds after the merged filing");
        assertEquals(2, run.log.all.size());
    }

    @Test void withoutANewAttemptTheResumedPartWasLost() {
        // The device's FAIL, as it was: the latch the first STOP claimed is still claimed.
        Run run = new Run();
        run.session.beginAttempt();
        run.file(part("p1", 1_000_000L, 83, null, null, 0.0, null, 3));
        run.runPrior = run.resumeCarry();
        assertFalse(run.file(part("p2", 1_100_000L, 50, null, null, 0.0, null, 2)));
        assertEquals(83L, run.log.all.get(0).durSec, "only the first part, as the device showed");
    }

    @Test void aSecondResumeCountsTwiceAndStillFilesOnce() {
        Run run = new Run();
        run.session.beginAttempt();
        run.file(part("p1", 1_000L, 60, null, null, 10.0, null, 1));
        RunParts c = run.resumeCarry(); run.session.beginAttempt(); run.runPrior = c;
        run.file(part("p2", 2_000L, 30, null, null, 5.0, null, 1));
        c = run.resumeCarry(); run.session.beginAttempt(); run.runPrior = c;
        assertTrue(run.file(part("p3", 3_000L, 20, null, null, 1.0, null, 1)));
        assertEquals(1, run.log.all.size());
        assertEquals(110L, run.log.all.get(0).durSec);
        assertEquals(16.0, run.log.all.get(0).doseKpaS, 1e-9);
        assertEquals("Resumed 2 times after STOP", RunParts.line(0, run.log.all.get(0).resumes));
    }

    @Test void killAndRejoinIsStillOneMergedRow() {
        // A new process: a fresh latch, nothing filed; the snapshot's figures come with it.
        RunParts snap = RunParts.fromCode(
            RunParts.sofar(null, 1_000_000L, 304_000L, 200.0, 250.0, 1000.0, 30.0, 9).code());
        assertNotNull(snap);
        snap.rejoins++;
        Run run = new Run();
        run.session.beginAttempt();          // the rejoin's new attempt: harmless on a fresh latch
        run.runPrior = snap;
        assertTrue(run.file(part("p2", 2_000_000L, 105, 20.0, 30.0, 100.0, 28.0, 3)));
        assertEquals(1, run.log.all.size());
        assertEquals(409L, run.log.all.get(0).durSec);
        assertEquals(12, run.log.all.get(0).cyclesDone);
        assertEquals("Rejoined once after the app closed",
            RunParts.line(run.log.all.get(0).rejoins, run.log.all.get(0).resumes));
    }

    /* ----------------------------------------------------------- the wiring */

    private static String code(String file) throws Exception {
        return NoBookNamesTest.stripComments(new String(Files.readAllBytes(
            Paths.get("../app/src/main/java/org/openpump/" + file)), StandardCharsets.UTF_8));
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

    @Test void theRejoinPathStartsANewAttempt() throws Exception {
        String sa = code("SessionActivity.java");
        String rejoin = body(sa, "private final class RejoinRunTap");
        int arm = rejoin.indexOf("session.beginAttempt()");
        assertTrue(arm >= 0, "a Resume (and a Rejoin) re-arms the once-only filing latch");
        int empty = rejoin.indexOf("plan.isEmpty()");
        int start = rejoin.indexOf("startSession(");
        assertTrue(empty >= 0 && empty < arm, "only once the rejoin is not refused");
        assertTrue(start > arm, "before the resumed part starts, so any exit after can file it");
        assertTrue(body(sa, "private final class ResumeAfterStopTap").contains("new RejoinRunTap("),
            "a Resume is a rejoin");
        assertTrue(body(sa, "private final class ResumeAfterStopTap").contains("if (running) return"),
            "a second Resume tap, with the run going, starts nothing and re-arms nothing");
    }

    @Test void theResumeMessageLeavesNothingToTapThrough() throws Exception {
        assertTrue(Snack.LATE_TAP_GUARD_MS >= 1000,
            "a late tap at the action's place lands on nothing for a moment after it goes");
        assertTrue(Snack.LATE_TAP_GUARD_MS < Snack.HOLD_MS, "a moment, not another message");
        String ui = code("Ui.java");
        String dismiss = body(ui, "private static final class SnackDismiss");
        assertTrue(dismiss.contains("Snack.LATE_TAP_GUARD_MS"),
            "an action message, timed out, stays as a tap guard before it is removed");
        assertTrue(dismiss.contains("setEnabled(false)"),
            "and its action can no longer be fired");
        String snack = body(ui, "View.OnClickListener action, int holdMs,");   // the full form
        assertTrue(snack.contains("new SnackSwallow()"),
            "while up, a tap on the message itself does not reach the screen under it");
    }
}
