package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R-24 (C5, the owner's decision, 2026-10-01), the girth side: READINGS THAT NEITHER COUNT
 * TOWARD A YIELD STREAK NOR BREAK IT - a pair not taken like for like, one on a reduced
 * (return) day after a deload, one inside a deload, one from a session stopped early. A
 * left-out reading still shows the person measures, so it resets the no-readings fallback.
 * And R-28's two fixes: the 4-hour "after other work" rule is timed from the length session's
 * end (Y-M1), and Level 1's streak starts when its yield tier opens at week 10 (Y-M2).
 */
class ReadingsThatDontCountTest {

    static final int GI = Plan.TRACK_GIRTH_INTERVAL;
    static final long H = 3600000L;

    static Model.Sess reading(Model m, long ts, double pct) {
        Model.Sess s = PressureClockTest.file(m, "g", ts, 20, 20, 30);
        s.afterBaseThisSession = true;
        s.afterComparable = true;
        s.afterGirAbsCm = Double.valueOf(10.0 * (1.0 + pct / 100.0));
        s.afterGirCm = Double.valueOf(10.0 * pct / 100.0);
        return s;
    }

    @Test void leftOutReadingsNeitherCountNorBreakTheStreak() {
        Model m = PressureClockTest.model();
        reading(m, PressureClockTest.at(0, 9), 4.0);
        reading(m, PressureClockTest.at(2, 9), 4.0);
        assertEquals(2, TrainerTab.yieldStreaks(m, GI, Plan.L3).consecutiveLow);

        Model.Sess odd = reading(m, PressureClockTest.at(4, 9), 4.0);
        odd.afterComparable = false;                      // not like for like
        Model.Sess ret = reading(m, PressureClockTest.at(7, 9), 4.0);
        ret.netTargetMin = Double.valueOf(0.0);           // a reduced (return) day
        Model.Sess stop = reading(m, PressureClockTest.at(9, 9), 4.0);
        stop.completed = false;                           // stopped early
        TrainerTab.YieldStreaks ys = TrainerTab.yieldStreaks(m, GI, Plan.L3);
        assertEquals(2, ys.consecutiveLow, "the streak is unchanged: none of them counts");
        assertTrue(TrainerTab.yieldLeftOut(m, odd));
        assertTrue(TrainerTab.yieldLeftOut(m, ret));
        assertTrue(TrainerTab.yieldLeftOut(m, stop));

        // ...nor breaks it: a high one left out does not end the run of lows.
        Model.Sess hiOdd = reading(m, PressureClockTest.at(10, 9), 15.0);
        hiOdd.afterComparable = false;
        assertEquals(2, TrainerTab.yieldStreaks(m, GI, Plan.L3).consecutiveLow);

        // ...and they still reset the no-readings fallback.
        assertEquals(hiOdd.ts, TrainerTab.lastYieldReadingMs(m, GI));
    }

    @Test void aReadingInsideADeloadIsLeftOut() {
        Model m = PressureClockTest.model();
        Deload.remember(m, PressureClockTest.at(7, 0), PressureClockTest.at(14, 0));
        Model.Sess in = reading(m, PressureClockTest.at(9, 9), 4.0);
        assertTrue(TrainerTab.yieldLeftOut(m, in));
        Model.Sess after = reading(m, PressureClockTest.at(16, 9), 4.0);
        assertFalse(TrainerTab.yieldLeftOut(m, after));
        assertEquals(1, TrainerTab.yieldStreaks(m, GI, Plan.L3).consecutiveLow);
    }

    @Test void afterOtherWorkIsTimedFromTheLengthSessionsEnd() {
        Model m = PressureClockTest.model();
        Model.Routine l = new Model.Routine();
        l.id = "l"; l.trainerTrack = Plan.TRACK_LENGTH;
        m.routines.add(l);
        Model.Sess len = PressureClockTest.file(m, "l", PressureClockTest.at(0, 9), 0, 0, 30);
        len.durSec = 105L * 60L;                          // 1 h 45: ends 10:45
        // A baseline 2 h 15 after the length session ENDS (4 h after it started): left out.
        Model.Sess g = reading(m, PressureClockTest.at(0, 13), 4.0);
        g.afterBaseTs = PressureClockTest.at(0, 13);
        assertTrue(TrainerTab.baselineAfterOtherWork(m, g));
        // Four hours and a minute after the end: counts.
        Model.Sess late = reading(m, PressureClockTest.at(0, 14) + 46L * 60000L, 4.0);
        late.afterBaseTs = late.ts;
        assertFalse(TrainerTab.baselineAfterOtherWork(m, late));
    }

    @Test void levelOnesStreakStartsAtWeekTen() {
        Model m = PressureClockTest.model();
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L1;
        st.weekBaseIndex = 7;
        st.weekBaseMs = PressureClockTest.at(0, 6);
        // Weeks 7, 8 and 9 (Mon / Wed / Fri), every reading low.
        for (int w = 0; w < 3; w++)
            for (int d = 0; d < 3; d++)
                reading(m, PressureClockTest.at(7 * w + 2 * d, 9), 4.0);
        st.weekIndex = 10;                                // the Trainer's advance
        long monday = PressureClockTest.at(21, 7);        // week 10's first morning
        Plan.Inputs in = YieldWindowTest.girth(Plan.L1, 10);
        in.monthIndex = 2;
        TrainerTab.fillGirthInputs(m, GI, st, 2, monday, in);
        assertEquals(0, in.consecutiveLowYield, "three lows in weeks 7-9: no decision yet");
        assertTrue(Plan.evaluate(in).action != Plan.ACTION_ADD_VOLUME);
        // Week 10's own readings count.
        for (int d = 0; d < 3; d++) reading(m, PressureClockTest.at(21 + 2 * d, 9), 4.0);
        TrainerTab.fillGirthInputs(m, GI, st, 2, PressureClockTest.at(28, 7), in);
        assertEquals(3, in.consecutiveLowYield);
    }
}
