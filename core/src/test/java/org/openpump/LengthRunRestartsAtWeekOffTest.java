package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * t10 parity run 2, N24 (the owner, 2 Oct 2026; option D): THE LOW-STRAIN RUN RESTARTS AT EACH
 * WEEK OFF. It counts from the later of the last change to the length work and the end of the
 * last week off, so readings from before the week off neither start nor finish the one-week
 * debounce - a low run begun before the week off used to add a strain set (D2) on the first
 * readings back. The over-6 % confirmation restarts there too: a confirmation is never
 * straddled across a week off - one high reading before it and one after ask to measure again.
 * (A pair confirmed entirely before the week off still cuts at the first session back, A-5.)
 */
class LengthRunRestartsAtWeekOffTest {

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    private static Plan.Decision eval(Model m, int d) {
        return Plan.evaluate(LengthYear.inputsFor(m, day(d, 8)));
    }

    /** The owner (month 7, 6 strain sets); a reading after the length session on each day. */
    private static Model readings(int[] days, double[] pcts) {
        Model m = new LengthYear().setup().m;
        for (int i = 0; i < days.length; i++) LengthYear.measured(m, day(days[i], 9), "PL", pcts[i]);
        return m;
    }

    /** The regular week off in week 5 (days 28-34), as DeloadStartPick records it. */
    private static void weekOff(Model m) {
        Deload.remember(m, day(28, 0), day(28, 0) + Plan.LAYOFF_MS);
        m.trainerFirstDeloadTaken = true;
        Deload.arm(m, day(28, 0) + Plan.LAYOFF_MS);
    }

    @Test void aRunBegunBeforeTheWeekOffDoesNotAddASetOnTheReturn() {
        // 3 % weeks 1-2, 1.5 % weeks 3-4 (D1: the week off is next anyway), the week off in
        // week 5, 1.5 % on the return's Tuesday and Thursday.
        Model m = readings(new int[]{ 1, 3, 8, 10, 15, 17, 22, 24, 36, 38 },
                           new double[]{ 3, 3, 3, 3, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5 });
        weekOff(m);
        Plan.Decision fri6 = eval(m, 39);
        assertNotEquals(Plan.LENGTH_NEVER_RULE, fri6.rule,
            "week 6 Friday: three days of lows in the new block - " + fri6.rule);
        LengthYear.measured(m, day(43, 9), "PL", 1.5);
        LengthYear.measured(m, day(45, 9), "PL", 1.5);
        Plan.Decision fri7 = eval(m, 46);
        assertEquals(Plan.LENGTH_NEVER_RULE, fri7.rule, "week 7 Friday: a week of lows - D2");
        assertEquals(Plan.ACTION_ADD_VOLUME, fri7.action);
    }

    @Test void allLowsTheSetWaitsAFullWeekAfterTheWeekOff() {
        Model m = readings(new int[]{ 22, 24, 36, 38, 41 },
                           new double[]{ 1.5, 1.5, 1.5, 1.5, 1.5 });
        weekOff(m);
        // The first reading back was taken on day 36 after the session: a week on is the
        // morning of day 44.
        assertNotEquals(Plan.LENGTH_NEVER_RULE, eval(m, 43).rule, "under a week after it");
        assertEquals(Plan.LENGTH_NEVER_RULE, eval(m, 44).rule, "a week after the first back");
    }

    @Test void aReachingReadingAfterTheWeekOffAddsNoSet() {
        Model m = readings(new int[]{ 22, 24, 36, 38 }, new double[]{ 1.5, 1.5, 3.0, 1.5 });
        weekOff(m);
        assertNotEquals(Plan.LENGTH_NEVER_RULE, eval(m, 39).rule);
        assertNotEquals(Plan.ACTION_ADD_VOLUME, eval(m, 39).action);
    }

    @Test void readingsThatNeverStraddleAWeekOffAreAsBefore() {
        Model m = readings(new int[]{ 1, 3, 6, 8 }, new double[]{ 1.5, 1.5, 1.5, 1.5 });
        assertEquals(Plan.LENGTH_NEVER_RULE, eval(m, 9).rule, "a week of lows: D2");
    }

    @Test void oneHighBeforeTheWeekOffAndOneAfterAskToMeasureAgain() {
        Model m = readings(new int[]{ 24, 36 }, new double[]{ 7.0, 7.0 });
        weekOff(m);
        Plan.Decision d = eval(m, 37);
        assertNotEquals(Plan.LENGTH_CUT_RULE, d.rule, "never confirmed across a week off");
        assertEquals(Plan.ACTION_REMEASURE, d.action, d.rule);
        assertEquals(Plan.LENGTH_HIGH_RULE, d.rule);
        // ...and a second high after it confirms the cut.
        LengthYear.measured(m, day(38, 9), "PL", 7.0);
        assertEquals(Plan.LENGTH_CUT_RULE, eval(m, 39).rule);
    }

    @Test void oneHighBeforeTheWeekOffAsksNothingOnTheReturn() {
        Model m = readings(new int[]{ 24 }, new double[]{ 7.0 });
        weekOff(m);
        assertNotEquals(Plan.ACTION_REMEASURE, eval(m, 36).action,
            "the pending re-measure restarted at the week off");
    }
}
