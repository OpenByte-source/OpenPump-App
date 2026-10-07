package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE TWO-HOUR STOP AND A LONG WAIT BY HAND (the coordinator's round 2, 2026-10-07). The
 * release now waits for Done and the changeover always did, so a run can sit vented for as
 * long as somebody takes. The owner's rule is a hard 120 minutes per run on the clock.
 *
 * What the app does (SessionActivity#checkGrossCap, asked on every tick whatever the run is
 * doing): the stop counts the run's WHOLE CLOCK - wall time since the run began
 * (Session#elapsedMs), the wait included, frozen only while the link is lost and caught up
 * as soon as frames return - and the larger of that and the sealed count
 * (PreRunHold#wholeForStopSec). So:
 *   (a) a wait spends the run's two hours; the pressure after it can never take the run past
 *       120 minutes;
 *   (b) the stop may fire in the middle of a vented wait. It ends the run the one way every
 *       limit does - runStopWhy = WHY_TWO_HOURS, then finishSession(true), which files the
 *       session and vents through the watch - so the record says "Stopped: the two-hour
 *       limit" and nothing is lost. WiringCheck invariants 134 and 251 hold the wiring.
 * Driven here through the real Session the Activity asks, at its own link timeout.
 */
class ByHandTwoHourTest {

    static final long LINK_TIMEOUT_MS = 5000L;        // SessionActivity.LINK_TIMEOUT_MS
    static final long T0 = 1_790_000_000_000L;
    static final double WORK_KPA = 20.0;

    /** The stop's own figure at `now`, as checkGrossCap computes it. */
    static double stopSec(Session s, long now, boolean commanding) {
        boolean tracking = s.isRunTracking();
        double sealed = tracking ? s.sealedForCapSec(now, commanding) : 0.0;
        return PreRunHold.wholeForStopSec(0L, tracking,
            tracking ? s.elapsedMs(now, LINK_TIMEOUT_MS) : 0L, sealed);
    }

    /** Frames once a second: vented ("no measurement") while by hand, the pull otherwise. */
    static void frames(Session s, long from, long to, boolean vented) {
        for (long t = from; t < to; t += 1000L) {
            s.noteSample(t, vented ? 0.0 : WORK_KPA, vented, LINK_TIMEOUT_MS);
            s.noteHoldFrame(t, vented ? 0.0 : WORK_KPA, vented, vented ? 0.0 : WORK_KPA,
                            0, 0, false, false, !vented);
        }
    }

    /** The first second at or after `from` at which the stop fires, or -1 by `to`. */
    static long firstStop(Session s, long from, long to, boolean vented) {
        for (long t = from; t <= to; t += 1000L) {
            s.noteSample(t, vented ? 0.0 : WORK_KPA, vented, LINK_TIMEOUT_MS);
            s.noteHoldFrame(t, vented ? 0.0 : WORK_KPA, vented, vented ? 0.0 : WORK_KPA,
                            0, 0, false, false, !vented);
            if (Plan.grossCapReached(stopSec(s, t, !vented))) return t;
        }
        return -1L;
    }

    @Test void aLongWaitNeverLetsPressureRunPastTwoHoursOfTheRun() {
        Session s = new Session();
        s.beginRun(T0);
        // The release: 5:00 by hand, then 100 minutes more waiting for Done - vented.
        frames(s, T0, T0 + 105 * 60_000L, true);
        double afterWait = stopSec(s, T0 + 105 * 60_000L, false);
        assertTrue(afterWait >= 105 * 60 - 1, "the wait counts on the run's clock: " + afterWait);
        assertFalse(Plan.grossCapReached(afterWait));
        // Done, and the pump pulls: the stop comes at 2:00:00 of the run, 15 minutes of pressure
        // later - never later than the run's 120 minutes, however long the wait was.
        long at = firstStop(s, T0 + 105 * 60_000L, T0 + 3 * 3_600_000L, false);
        assertTrue(at > 0, "the stop fires");
        assertEquals(T0 + 7_200_000L, at, 1000L, "at 120 minutes of the run, not of pressure");
        assertTrue(at - (T0 + 105 * 60_000L) <= 15 * 60_000L + 1000L,
            "pressure ran at most what the two hours had left");
    }

    @Test void aWaitAcrossTheTwoHourMarkEndsTheRunCleanlyWithItsReason() {
        Session s = new Session();
        s.beginRun(T0);
        // 100 minutes of work, then the changeover - vented, waiting for "I've swapped" -
        // left standing past the two hours.
        frames(s, T0, T0 + 100 * 60_000L, false);
        long at = firstStop(s, T0 + 100 * 60_000L, T0 + 3 * 3_600_000L, true);
        assertEquals(T0 + 7_200_000L, at, 1000L,
            "the stop still fires during a vented wait, on the run's clock");
        // Nothing is commanded in the wait (RunEdit#commandingPressure: a rest, the gate up),
        // so the sealed count alone would never have reached it - the whole clock does.
        assertFalse(RunEdit.commandingPressure(true, false, true, false, true, true));
        assertFalse(Plan.grossCapReached(s.sealedForCapSec(at, false)),
            "the sealed count alone would have let the wait run on");
        // The ending it takes says why, in the record and the venting notice.
        assertEquals("Stopped: the two-hour limit",
            RunStopReason.line(RunStopReason.WHY_TWO_HOURS, 0));
        assertTrue(RunStopReason.saysInNotice(RunStopReason.WHY_TWO_HOURS));
    }

    @Test void aSilentPumpDuringTheWaitIsCaughtUpWhenFramesReturn() {
        Session s = new Session();
        s.beginRun(T0);
        frames(s, T0, T0 + 60 * 60_000L, true);
        // The pump goes quiet for 70 minutes (the link-loss auto-stop owns it then; the stop
        // is not asked over a lost link) - the clock is frozen...
        long quietEnd = T0 + 130 * 60_000L;
        long frozen = s.elapsedMs(T0 + 61 * 60_000L, LINK_TIMEOUT_MS);
        assertTrue(s.isLinkLost());
        assertEquals(frozen, s.elapsedMs(quietEnd - 1000L, LINK_TIMEOUT_MS));
        // ...and the first frame back catches it up: the run is past two hours, and stops.
        s.noteSample(quietEnd, 0.0, true, LINK_TIMEOUT_MS);
        assertFalse(s.isLinkLost());
        assertTrue(Plan.grossCapReached(stopSec(s, quietEnd, false)),
            "the silence counts once frames return - no pull can follow it");
    }

    @Test void theWordsDuringAByHandStepNeverSayRest() {
        assertEquals("+30 s", ByHand.PLUS);
        assertEquals("Time", ByHand.STRIP_LABEL);
        assertEquals("The pump is vented — tap Done when you’re finished.",
            ByHand.pauseTap(false));
        assertTrue(ByHand.pauseTap(true).contains("I’ve swapped"));
        assertEquals("Use Done to finish it now.", ByHand.reword("Use End rest to finish it now."));
        assertEquals("30 s is the shortest.", ByHand.reword("30 s is the shortest."));
        String[] said = { ByHand.PLUS, ByHand.PLUS_SAID, ByHand.STRIP_LABEL, ByHand.STRIP_SPOKEN,
            ByHand.PAUSE_SAID, ByHand.pauseTap(false), ByHand.pauseTap(true), ByHand.HEAD,
            ByHand.VENTED_LINE, ByHand.WHEN_READY, ByHand.DONE,
            ByHand.reword("Use End rest to finish it now."),
            ByHand.status(60_000L, false), ByHand.status(0L, true) };
        for (int i = 0; i < said.length; i++) {
            assertNotNull(said[i]);
            assertFalse(said[i].toLowerCase(java.util.Locale.US).matches(".*\\brest\\b.*"),
                "\"" + said[i] + "\" says rest");
        }
        // The widget names the step too - never while discreet.
        assertEquals("By hand · Tunica release",
            ByHand.widgetName("Length L1", "By hand · Tunica release", false));
        assertEquals("Length L1", ByHand.widgetName("Length L1", "By hand · x", true));
        assertEquals("Length L1", ByHand.widgetName("Length L1", "", false));
    }
}
