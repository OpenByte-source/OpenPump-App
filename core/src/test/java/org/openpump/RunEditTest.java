package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * S01 and S03 - THE RUN SCREEN'S TWO "TIME LEFT" FIGURES.
 *
 * S01: the ROUTINE card (SessionActivity#paintRoutineStrip, via {@link
 * RunEdit#routineElapsedLine}) keeps the plan's total and shows what Hold, an inserted
 * rest, a changeover wait or at-pressure timing has ADDED beside it, the way the NOW card
 * already reports one set (RunScreen#nowSub).
 *
 * S03: "run ends in" (RunEdit#runEndsLine) already counts the after-assessment's length;
 * the ROUTINE card's total does not. Two figures on one screen, differing by a fixed
 * amount with nothing said about it - kept both, and {@link
 * RunEdit#runEndsLine(long, int)} names the gap.
 */
class RunEditTest {

    /* ------------------------------------------------------------ S01: routineElapsedLine */

    @Test
    void withNothingPushedTheFigureIsJustDoneOverPlanned() {
        // 88 s done of a 40:20 (2420 s) plan, nothing added yet.
        assertEquals("1:28 / 40:20",
            RunEdit.routineElapsedLine(88000L, 2420000L, 0L));
    }

    @Test
    void pushedTimeIsBackedOutOfThePlannedFigureAndShownBeside() {
        // The example straight from the owner's own decision: "1:28 / 40:20 +2:10" - a
        // routine planned for 40:20, 2:10 of which was Hold/rest/changeover/at-pressure
        // push, so the growing total handed in is 40:20 + 2:10 = 42:30.
        long doneMs = 88000L;
        long addedMs = (2 * 60 + 10) * 1000L;
        long plannedMs = (40 * 60 + 20) * 1000L;
        assertEquals("1:28 / 40:20 +2:10",
            RunEdit.routineElapsedLine(doneMs, plannedMs + addedMs, addedMs));
    }

    @Test
    void underOneSecondAddedIsNotShown() {
        // Rounds to nothing worth a "+0:00" on the card - matches the NOW card's own
        // ">= 1000" gate on tupPausedMs (RunScreen#nowSub).
        assertEquals("1:28 / 40:20",
            RunEdit.routineElapsedLine(88000L, 2420000L + 999L, 999L));
    }

    @Test
    void plannedNeverGoesNegativeEvenIfAddedWasOverstated() {
        // Defensive only - addedMs should never exceed the growing total it was summed
        // into, but a caller bug here must not print a negative planned time.
        assertEquals("0:05 / 0:00 +0:10",
            RunEdit.routineElapsedLine(5000L, 5000L, 10000L));
    }

    /* -------------------------------------------------------------- S03: runEndsLine(2) */

    @Test
    void noAfterTestReadsExactlyAsTheOneArgumentForm() {
        assertEquals(RunEdit.runEndsLine(863000L), RunEdit.runEndsLine(863000L, 0));
    }

    @Test
    void anAfterTestNamesTheGapItAdds() {
        assertEquals("run ends in 14:23 (0:45 of that is a tissue test after)",
            RunEdit.runEndsLine(863000L, 45));
    }
}
