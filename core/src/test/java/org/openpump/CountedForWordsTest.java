package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE SUMMARY'S "WHAT THIS COUNTED FOR", SAID AS THE RULES ACTUALLY WORK.
 *
 *   - The week: three days is the plan's MINIMUM ("3-5 days"), not a target, so "4 of 3
 *     days" read as an overshoot of a goal. Once the minimum is met it says how many days
 *     and what was needed; below it, the wording stays as it was. The Trainer's "4/3" too.
 *   - The streak row said "Not counted" for every stopped session, but the streak counts a
 *     stopped session that delivered at least 60 s of real pressure (Summary#trainedDay).
 *     The row now asks that same rule.
 */
class CountedForWordsTest {

    /* Week B (2026-10-03): the week is said against its rule - 2 full sessions, or 3 days. */
    private static TrainingWeek.Tally tally(int days, int ranDays, double worth) {
        TrainingWeek.Tally t = new TrainingWeek.Tally();
        t.days = days; t.ranDays = ranDays; t.worth = worth; t.sessions = days;
        t.qualifies = TrainingWeek.qualifies(days, ranDays, worth);
        return t;
    }

    @Test void belowTheRuleTheWeekSaysBothWaysToCount() {
        assertEquals("0 of 2 full sessions · 0 of 3 days", Say.weekProgress(tally(0, 0, 0)));
        assertEquals("1 of 2 full sessions · 2 of 3 days",
            Say.weekProgress(tally(2, 2, 1.6)));
        assertEquals("1 of 2 full sessions · 1 of 3 days",
            Say.weekProgress(tally(1, 1, 2.5)), "one long session reads as one");
    }

    /** Device check EMU11: two full sessions waiting for the track's last training day say so
     *  where they show, and Today promises a count only for what a session of any length does. */
    @Test void waitingFullSessionsSayWhyAndTodayPromisesOnlyADay() {
        TrainingWeek.Tally w = tally(2, 2, 2.0);
        w.qualifies = false;                       // the track's last training day is to come
        w.volumeMet = true;
        assertEquals("2 of 2 full sessions · counts after this week's last training day",
            Say.weekProgress(w));
        assertTrue(w.oneSessionAway(), "a third day of any length counts it");
        TrainingWeek.Tally one = tally(1, 1, 1.0);
        assertFalse(one.oneSessionAway(), "one full session: a second is not promised to count");
        TrainingWeek.Tally two = tally(2, 2, 0.8);
        assertTrue(two.oneSessionAway());
        assertTrue(Say.WEEK_WHEN.startsWith("Two full sessions count after the track's last"));
    }

    @Test void onceTheWeekCountsItIsNotAnOvershoot() {
        assertEquals("2 full sessions — counted", Say.weekProgress(tally(2, 2, 2.0)));
        assertEquals("3 days — counted", Say.weekProgress(tally(3, 3, 1.0)));
        assertEquals("4 days — counted", Say.weekProgress(tally(4, 4, 4.0)));
        assertFalse(Say.weekProgress(tally(4, 4, 4.0)).contains("of 3"));
    }

    private static Model.Sess sess(boolean completed, Double peak, long durSec, boolean manual) {
        Model.Sess s = new Model.Sess();
        s.completed = completed;
        s.peakKpa = peak;
        s.durSec = durSec;
        s.manual = manual;
        return s;
    }

    @Test void theStreakRowAsksTheStreaksOwnRule() {
        assertTrue(Summary.countsForStreak(sess(true, Double.valueOf(20), 600, false)),
            "a finished session counts");
        assertTrue(Summary.countsForStreak(sess(false, Double.valueOf(20), 480, false)),
            "stopped after 8 real minutes: the streak counts it, and so must the row");
        assertTrue(Summary.countsForStreak(sess(false, Double.valueOf(20),
            Summary.TRAINED_MIN_SEC, false)), "a minute of real pressure is enough");
        assertFalse(Summary.countsForStreak(sess(false, Double.valueOf(20), 20, false)),
            "a 20 s stab is not");
        assertFalse(Summary.countsForStreak(sess(false, null, 600, false)),
            "no reading from the pump is not");
        assertFalse(Summary.countsForStreak(sess(true, Double.valueOf(20), 600, true)),
            "a manual run never lights a streak day");
        assertFalse(Summary.countsForStreak(null));
    }

    @Test void theRowAndTheStreakAgreeOnEveryCase() {
        boolean[] tf = { false, true };
        for (boolean completed : tf)
            for (boolean manual : tf)
                for (Double peak : new Double[]{ null, Double.valueOf(15) })
                    for (long dur : new long[]{ 0, 59, 60, 600 }) {
                        Model.Sess s = sess(completed, peak, dur, manual);
                        s.ts = 1_790_000_000_000L;
                        java.util.List<Model.Sess> log = new java.util.ArrayList<Model.Sess>();
                        log.add(s);
                        boolean lit = Summary.completedDayNumbers(log).size() == 1;
                        assertEquals(lit, Summary.countsForStreak(s),
                            "completed=" + completed + " manual=" + manual + " peak=" + peak
                            + " dur=" + dur);
                    }
    }
}
