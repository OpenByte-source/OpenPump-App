package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * D2 FOLLOW-UP - NOTHING BEFORE THE RUN HOLDS PRESSURE WITHOUT A BOUND.
 *
 * The guided start pulls to 5.0 inHg and waits for the cuff to hold it. After thirty seconds
 * it asks "Keep waiting?", and every "Keep waiting" started the thirty seconds again - so a
 * cuff that never sealed, or a sensor reading 0.0 on one that did, was held for as long as
 * someone kept tapping, or for ever behind a question nobody answered. None of it counted
 * toward the two-hour stop either, because it comes before the run's own recording begins.
 *
 * The owner's rule (the controller's decision):
 *   (a) pressure commanded before the run - the guided start's pull - counts toward the
 *       two-hour stop whatever the sensor reads;
 *   (b) the guided start has a total limit of 10 minutes from its first pull, across every
 *       "Keep waiting"; at the limit it stops and vents through the normal path and says why;
 *   (c) a question left unanswered for 60 s is a stop.
 */
class PreRunHoldTest {

    private static final long S = 1000L, MIN = 60_000L;
    /** Any wall-clock instant: the first pull. */
    private static final long T0 = 1_790_000_000_000L;
    /** SessionActivity.RUN_TICK_MS. */
    private static final long TICK_MS = 400L;

    /* ------------------------------------------------------------ the verdict */

    @Test
    void theGuidedStartStopsTenMinutesAfterItsFirstPull() {
        assertEquals(PreRunHold.WAIT, PreRunHold.guidedVerdict(T0 + 10 * MIN - 1, T0, 0L));
        assertEquals(PreRunHold.LIMIT, PreRunHold.guidedVerdict(T0 + 10 * MIN, T0, 0L),
            "ten minutes exactly is the limit");
        assertEquals(PreRunHold.LIMIT, PreRunHold.guidedVerdict(T0 + 20 * MIN, T0, 0L));
    }

    @Test
    void aQuestionLeftUnansweredForAMinuteIsAStop() {
        long asked = T0 + 30 * S;
        assertEquals(PreRunHold.WAIT, PreRunHold.guidedVerdict(asked + 59_999L, T0, asked));
        assertEquals(PreRunHold.UNANSWERED, PreRunHold.guidedVerdict(asked + 60_000L, T0, asked),
            "sixty seconds exactly");
        assertEquals(PreRunHold.WAIT, PreRunHold.guidedVerdict(T0 + 5 * MIN, T0, 0L),
            "no question up: nothing to answer");
        assertTrue(PreRunHold.unanswered(asked + 60_000L, asked));
        assertFalse(PreRunHold.unanswered(asked + 59_999L, asked));
        assertFalse(PreRunHold.unanswered(T0 + 10 * MIN, 0L), "nothing asked");
    }

    @Test
    void theLimitIsWhatIsSaidWhenBothAreDue() {
        assertEquals(PreRunHold.LIMIT,
            PreRunHold.guidedVerdict(T0 + 10 * MIN, T0, T0 + 9 * MIN - 1));
    }

    /* ---------------------------------------------- the wait, tick for tick */

    @Test
    void keepWaitingOverAndOverStillStopsAtTenMinutes() {
        Ended e = waitFor(5 * S);    // every question answered "Keep waiting" in five seconds
        assertEquals(PreRunHold.LIMIT, e.why);
        assertEquals(10 * MIN, e.at, "however many times Keep waiting was pressed ("
            + e.asked + " questions)");
        assertTrue(e.asked >= 17, "the question kept coming back every half minute");
    }

    @Test
    void aQuestionNobodyAnswersStopsAMinuteAfterItWasAsked() {
        Ended e = waitFor(-1);        // asked at 30 s and never answered
        assertEquals(PreRunHold.UNANSWERED, e.why);
        assertEquals(30 * S + 60 * S, e.at, "thirty seconds of waiting, then one minute");
    }

    @Test
    void anAnswerJustInsideTheMinuteKeepsItWaitingUntilTheLimit() {
        Ended e = waitFor(59 * S);
        assertEquals(PreRunHold.LIMIT, e.why, "answered each time with a second to spare");
        assertEquals(10 * MIN, e.at);
    }

    /* ------------------------------------------- what the two-hour stop counts */

    @Test
    void thePullBeforeTheRunCountsWhateverTheSensorReads() {
        PreRunHold.Clock c = new PreRunHold.Clock();
        for (long t = 0; t <= 5 * MIN; t += TICK_MS) c.tick(T0 + t, true);
        assertEquals(5 * MIN, c.msAt(T0 + 5 * MIN), "five minutes of pull, counted");
        for (long t = 5 * MIN + TICK_MS; t <= 8 * MIN; t += TICK_MS) c.tick(T0 + t, false);
        assertEquals(5 * MIN + TICK_MS, c.msAt(T0 + 8 * MIN),
            "the tick in which the pull ended is counted, and nothing after it");
        c.reset();
        assertEquals(0L, c.msAt(T0 + 9 * MIN), "a new attempt starts from nothing");
    }

    @Test
    void theTimeSinceTheLastTickCountsWhilePressureIsCommanded() {
        PreRunHold.Clock c = new PreRunHold.Clock();
        c.tick(T0, true);
        assertEquals(30 * S, c.msAt(T0 + 30 * S), "between ticks the pull is still on");
        PreRunHold.Clock off = new PreRunHold.Clock();
        off.tick(T0, false);
        assertEquals(0L, off.msAt(T0 + 30 * S));
    }

    @Test
    void theStopAddsThePullBeforeTheRunToTheRunsOwnSealedTime() {
        assertEquals(600.0 + 6600.0, PreRunHold.sealedForStopSec(10 * MIN, true, 6600.0), 1e-9);
        assertTrue(Plan.grossCapReached(PreRunHold.sealedForStopSec(10 * MIN, true, 6600.0)),
            "ten minutes before the run and 1:50 in it reach the two hours");
        assertEquals(90.0, PreRunHold.sealedForStopSec(90 * S, false, 7200.0), 1e-9,
            "before this attempt's run has begun, the frames the session still holds are "
            + "the LAST run's: counted, a run that reached the stop ended the next attempt");
    }

    @Test
    void theSentencesSayWhatHappened() {
        assertEquals("The pump didn't reach pressure in 10 minutes, so it is being released. "
            + "Check the cuff's seal and start again.",
            PreRunHold.sentence(PreRunHold.LIMIT, true));
        assertEquals("The cuff didn't reach pressure in 10 minutes, so this start has ended. "
            + "Check the cuff's seal and start again.",
            PreRunHold.sentence(PreRunHold.LIMIT, false));
        assertEquals("Nobody answered for a minute, so the pump is being released. Start "
            + "again when you're ready.", PreRunHold.sentence(PreRunHold.UNANSWERED, true));
        assertEquals("Nobody answered for a minute, so this start has ended. Start again "
            + "when you're ready.", PreRunHold.sentence(PreRunHold.UNANSWERED, false));
    }

    /* ------------------------------------------------------------------ fixtures */

    private static final class Ended {
        int why = PreRunHold.WAIT, asked;
        long at = -1L;
    }

    /**
     * SessionActivity#tickGuidedStart in miniature, on a cuff that never holds: every 400 ms
     * the verdict is asked; thirty seconds after the window last (re)started the question
     * goes up; `answerAfterMs` later it is answered "Keep waiting", which restarts the window
     * and takes the question down - and moves nothing else (-1: never answered). Returns the
     * first verdict that is not WAIT, relative to the first pull, or gives up at an hour.
     */
    private static Ended waitFor(long answerAfterMs) {
        Ended e = new Ended();
        long window = T0;             // guidedStartedAt, which Keep waiting restarts
        long asked = 0L;              // guidedAskedAt: 0 while no question is up
        for (long now = T0; now <= T0 + 60 * MIN; now += TICK_MS) {
            if (asked > 0 && answerAfterMs >= 0 && now - asked >= answerAfterMs) {
                window = now;         // GuidedKeepWaiting
                asked = 0L;
            }
            int v = PreRunHold.guidedVerdict(now, T0, asked);
            if (v != PreRunHold.WAIT) { e.why = v; e.at = now - T0; return e; }
            if (asked == 0L && now - window >= Model.GUIDED_START_WINDOW_MS) {
                asked = now;          // askGuidedStart
                e.asked++;
            }
        }
        return e;
    }
}
