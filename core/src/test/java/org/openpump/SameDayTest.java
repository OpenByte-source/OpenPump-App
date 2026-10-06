package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 0.10 - A DAY THAT RUNS BOTH TRACKS (the trainer study's S04-S10 and S14, the owner's
 * decisions of 2026-09-26). Every rule {@link SameDay} states, asserted at its edges.
 */
class SameDayTest {

    private static final long MIN = 60_000L;
    private static final long NOW = 1_790_000_000_000L;

    /* ---- S04 ---- */

    @Test void theWarmUpGoesWithinTheHalfHourOnly() {
        // t10 R-05 (P4): thirty minutes, and the plan's rule - no longer an hour's question.
        assertEquals(12, SameDay.warmSkipAskMinutes(NOW - 12 * MIN, NOW), "twelve minutes ago");
        assertEquals(0, SameDay.warmSkipAskMinutes(NOW - 20_000L, NOW), "seconds ago reads 0");
        assertEquals(30, SameDay.warmSkipAskMinutes(NOW - 30 * MIN, NOW), "the edge is still in");
        assertEquals(-1, SameDay.warmSkipAskMinutes(NOW - 31 * MIN, NOW), "past it: it stays");
        assertEquals(-1, SameDay.warmSkipAskMinutes(0L, NOW), "nothing filed today");
        assertEquals(-1, SameDay.warmSkipAskMinutes(NOW + MIN, NOW),
            "a session that ends in the future is a clock problem, not a reason to skip");
        assertTrue(SameDay.warmSkipAuto(NOW - 30 * MIN, NOW));
        assertFalse(SameDay.warmSkipAuto(NOW - 31 * MIN, NOW));
        assertEquals(30L * MIN, SameDay.WARM_SKIP_WINDOW_MS, "the owner's thirty minutes (P4)");
    }

    /* ---- S05 ---- */

    @Test void theTissueTestIsTheFirstSessionsOnly() {
        assertTrue(SameDay.tissueTestSkipped(true, true), "the other track ran: this one skips");
        assertFalse(SameDay.tissueTestSkipped(false, true), "the day's first keeps its test");
        assertFalse(SameDay.tissueTestSkipped(true, false), "no test to skip is not a skip");
    }

    /* ---- S06 ---- */

    @Test void fiveGirthSetsGoButNeverTheLastOne() {
        assertEquals(5, SameDay.GIRTH_SETS_OFF, "the coda's five");
        assertEquals(5, SameDay.girthSetsOff(12));
        assertEquals(5, SameDay.girthSetsOff(6));
        assertEquals(4, SameDay.girthSetsOff(5), "Level 1's first weeks keep one set");
        assertEquals(0, SameDay.girthSetsOff(1));
        assertEquals(0, SameDay.girthSetsOff(0));
    }

    @Test void aBothTracksDayIsOneWithLengthFiledOrStillToCome() {
        assertTrue(SameDay.bothTracksDay(true, false, false), "length already ran");
        assertTrue(SameDay.bothTracksDay(false, true, true), "length is still to come today");
        assertFalse(SameDay.bothTracksDay(false, true, false), "a resting length track");
        assertFalse(SameDay.bothTracksDay(false, false, true), "a girth-only day");
    }

    /* ---- S07 ---- */

    @Test void theBudgetIsNinetyMinutesOfACombinedDay() {
        assertFalse(SameDay.overBudget(0L, 100 * 60L),
            "one long session alone is its own limits' business, not the day's");
        assertFalse(SameDay.overBudget(58 * 60L, 32 * 60L), "exactly ninety is not past it");
        assertTrue(SameDay.overBudget(58 * 60L, 33 * 60L), "ninety-one is");
        assertEquals("45 min", SameDay.minutes(45 * 60L));
        assertEquals("1 h 30 min", SameDay.minutes(90 * 60L));
        assertEquals("2 h", SameDay.minutes(120 * 60L));
        assertEquals("Today: 58 min so far — about 1 h 31 min with this one.",
                     SameDay.budgetLine(58 * 60L, 33 * 60L));
        assertTrue(SameDay.BUDGET_ADVISORY.contains("90"));
        assertTrue(SameDay.BUDGET_ADVISORY.contains("nothing is stopped"),
            "an advisory says it is one");
    }

    /* ---- S08 ---- */

    @Test void girthFirstIsWarnedOnlyBeforeAPull() {
        assertTrue(SameDay.girthFirstWarning(true, true));
        assertFalse(SameDay.girthFirstWarning(true, false), "an expansion session pulls nothing");
        assertFalse(SameDay.girthFirstWarning(false, true), "length first: nothing to warn");
        assertTrue(SameDay.GIRTH_FIRST_WARNING.contains("expansion only"),
            "the warning names the way out");
    }

    /* ---- S09 ---- */

    @Test void theNextTrackWaitsFortyMinutesAfterAPullUnlessItCleared() {
        long end = NOW - 10 * MIN;
        assertEquals(end + 40 * MIN, SameDay.pullGapUntil(end, false, NOW));
        assertEquals(0L, SameDay.pullGapUntil(end, true, NOW), "\"It cleared\" ends the wait");
        assertEquals(0L, SameDay.pullGapUntil(NOW - 41 * MIN, false, NOW), "forty minutes gone");
        assertEquals(0L, SameDay.pullGapUntil(0L, false, NOW), "nothing pulled today");
        assertEquals(Plan.NUMBNESS_CLEAR_MIN * MIN, SameDay.PULL_GAP_MS,
            "the same forty minutes the \"After the pull\" card names");
    }

    /* ---- S10 ---- */

    @Test void theFeederWaitsFourHoursFromTheMainSessionToo() {
        long h = 3_600_000L;
        assertEquals(NOW + 4 * h, SameDay.feederFromMs(0, 0L, NOW),
            "the first feeder is no longer due the moment the main work is filed");
        assertEquals(0L, SameDay.feederFromMs(0, 0L, 0L), "no main session: due now");
        assertEquals(NOW + 4 * h, SameDay.feederFromMs(1, NOW - h, NOW),
            "the later of the two gaps wins");
        assertEquals(NOW + 3 * h, SameDay.feederFromMs(1, NOW - h, NOW - 5 * h));
        assertEquals(Long.MAX_VALUE, SameDay.feederFromMs(2, NOW, NOW), "both done today");
    }

    /* ---- S14 ---- */

    @Test void aGirthFocusBlockPausesTheGirthTrack() {
        assertTrue(SameDay.girthPaused(true, true));
        assertFalse(SameDay.girthPaused(false, true), "a resting length track pauses nothing");
        assertFalse(SameDay.girthPaused(true, false));
        long w = 7L * 86_400_000L;
        assertTrue(SameDay.pauseTouchesWeek(NOW, NOW + 8 * w, NOW - w, NOW + 1), "touches the start");
        assertFalse(SameDay.pauseTouchesWeek(NOW, NOW + 8 * w, NOW - 2 * w, NOW - w));
        assertFalse(SameDay.pauseTouchesWeek(0L, 0L, NOW - w, NOW), "no block at all");
        assertTrue(SameDay.girthPausedLine("Nov 3").contains("until Nov 3"));
    }
}
