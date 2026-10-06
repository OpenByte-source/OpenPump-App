package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * LEVEL 1 FOLLOWS THE GUIDANCE TABLE'S PRESSURE (the owner's decision, 2026-09-27). While the
 * table position is in the volume phase (before the 20-minute milestone), interval girth's
 * working pressure follows the Level 1 row's pressure: at most 1 hg a step, only upward, and
 * only once each of the last 3 scored sessions at the current pressure delivered its own
 * planned minutes. After the milestone the +1 hg per 3 counting weeks rule continues.
 *
 * Every safety condition is pinned here: never above 6 hg in month 0, the Level 1 cap, the
 * device ceiling or the user's maximum; never in a deload week, with a deload due, while the
 * gentle return is open, or under a safety flag; one step per evaluation.
 */
class LevelOneTablePressureTest {

    static final double HG = Plan.HG;

    /** Ready to follow the table: interval, Level 1, below the milestone, 3 sessions held. */
    static Plan.Inputs ready(int weekIndex, int month, double kpa) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = Plan.L1;
        in.monthIndex = month;
        in.weekIndex = weekIndex;
        in.pressureKpa = kpa;
        in.netTupMin = 12.0;
        in.ownTargetsMet = true;
        in.ownTargetsMetAtPressure = true;
        in.firstDeloadPending = false;
        return in;
    }

    static int rx(Plan.Inputs in, Plan.Decision d) {
        return Mint.prescribe(in.track, in.level, in.weekIndex, in.pressureKpa, in.monthIndex,
            (int) Math.round(in.ceilKpa), 0, d).pressureKpa;
    }

    @Test void theVolumePhaseIsTheTableUpToTheMilestoneRow() {
        assertEquals(12, Plan.L1_VOLUME_PHASE_LAST_WEEK, "week 12: 10 sets, the 20 minutes");
        double[] want = { 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 7 };   // row 5 and 9 are deloads
        for (int w = 1; w <= 12; w++)
            assertEquals(want[w - 1] * HG, Plan.l1TablePressureKpa(w), 1e-9, "week " + w);
        assertTrue(Double.isNaN(Plan.l1TablePressureKpa(13)), "past it the table does not lead");
        assertTrue(Double.isNaN(Plan.l1TablePressureKpa(40)));
        for (int w = 1; w <= 12; w++)
            assertTrue(Plan.l1TablePressureKpa(w) <= Plan.L1_CAP_KPA + 1e-9,
                "the volume phase never asks past the Level 1 cap");
    }

    @Test void followsTheTableAndSaysItIsTheGuidance() {
        Plan.Inputs in = ready(4, 0, 17);
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action, d.reason);
        assertEquals(Plan.TAG_SOURCE, d.tag, "following the guidance's own table");
        assertTrue(d.rule.contains("guidance's table"), d.rule);
        assertTrue(d.reason.contains("6 hg"), d.reason);
        assertEquals(20, rx(in, d), "5 -> 6 hg: 17 -> 20 kPa");
    }

    /** THE REACHED RULE (the owner's decision, 2026-09-27: 6.8 hg counts as 7 hg): a table
     *  pressure is reached when the working pressure is less than one whole kPa below it. */
    @Test void aTablePressureIsReachedWithinOneWholeKpa() {
        assertEquals(1.0, Plan.TABLE_REACHED_WITHIN_KPA, 0.0);
        assertTrue(Plan.tablePressureReached(23, 7 * HG), "23 kPa (6.8 hg) has the table's 7 hg");
        assertTrue(Plan.tablePressureReached(24, 7 * HG));
        assertTrue(Plan.tablePressureReached(20, 6 * HG), "20 kPa (5.9 hg) has 6 hg");
        assertTrue(Plan.tablePressureReached(17, 5 * HG));
        assertTrue(Plan.tablePressureReached(27, 7 * HG), "above it: reached, never lowered");
        assertFalse(Plan.tablePressureReached(22, 7 * HG), "1.7 kPa short is not 7 hg");
        assertFalse(Plan.tablePressureReached(19, 6 * HG), "1.3 kPa short is not 6 hg");
        assertFalse(Plan.tablePressureReached(26, 8 * HG), "1.1 kPa short is not 8 hg");
        assertFalse(Plan.tablePressureReached(23, 24.0), "a whole kPa short is not reached");
    }

    @Test void sixPointEightHgCountsAsTheTablesSevenNoExtraKpaStep() {
        for (int w = 8; w <= Plan.L1_VOLUME_PHASE_LAST_WEEK; w++) {
            if (Plan.GIRTH_INTERVAL_L1[w - 1].deload) continue;
            Plan.Decision d = Plan.evaluate(ready(w, 2, 23));
            assertEquals(Plan.ACTION_HOLD, d.action, "week " + w + ": " + d.reason);
            assertEquals(23, rx(ready(w, 2, 23), d), "no 23 -> 24 step");
        }
        Plan.Inputs from22 = ready(12, 2, 22);             // 1.7 kPa short: one step, to 24
        Plan.Decision d = Plan.evaluate(from22);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action, d.reason);
        assertEquals(24, rx(from22, d));
        Plan.Inputs from20 = ready(12, 2, 20);             // the usual step lands on 23
        assertEquals(23, rx(from20, Plan.evaluate(from20)));
    }

    @Test void atMostOneHgAStep() {
        Plan.Inputs in = ready(8, 1, 17);                  // the table says 7, they are at 5
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action);
        assertEquals(17 + Plan.STEP_HG_KPA, d.pressureKpa, 1e-9, "one hg, not two");
        assertEquals(20, rx(in, d));
    }

    @Test void onlyUpwardNeverLowersAPressureTheyHave() {
        Plan.Inputs in = ready(1, 1, 24);                  // the table says 5, they are at 7
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action, d.reason);
        assertEquals(24, rx(in, d), "kept, not lowered to the table");
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(ready(4, 1, 20)).action,
            "at the table's pressure: nothing to follow");
    }

    @Test void neverAboveSixHgInMonthZero() {
        Plan.Inputs at6 = ready(8, 0, 20);                 // the table says 7, month 0
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(at6).action);
        Plan.Inputs from5 = ready(8, 0, 17);
        Plan.Decision d = Plan.evaluate(from5);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action);
        assertTrue(d.pressureKpa <= Plan.MONTH1_CAP_KPA + 1e-9, "cut to the month-0 cap");
        assertEquals(20, rx(from5, d));
    }

    @Test void neverAboveTheLevelOneCap() {
        for (int w = 1; w <= 20; w++) {
            Plan.Decision d = Plan.evaluate(ready(w, 3, 26));
            if (d.action == Plan.ACTION_RAISE_PRESSURE)
                assertTrue(d.pressureKpa <= Plan.L1_CAP_KPA + 1e-9, "week " + w);
        }
        Plan.Decision d = Plan.evaluate(ready(12, 3, 27));
        assertEquals(Plan.ACTION_HOLD, d.action, "8 hg is Level 1's top; the table never asks it");
    }

    @Test void neverAboveTheDeviceCeilingOrTheUsersMaximum() {
        Plan.Inputs in = ready(8, 1, 20);
        in.ceilKpa = 22;
        assertEquals(Plan.ACTION_CEILING_DEADLOCK, Plan.evaluate(in).action,
            "a step the user's maximum cannot reach is refused and said, not taken");
        Plan.Decision d = Plan.evaluate(ready(8, 1, 20));
        in.ceilKpa = 22;
        assertEquals(22, rx(in, d), "and the prescription is clamped to it anyway");
        Plan.Inputs fits = ready(8, 1, 20);
        fits.ceilKpa = 23;
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(fits).action,
            "a ceiling the whole-kPa step fits under is no deadlock");
    }

    @Test void neverInADeloadWeekOrWithOneDue() {
        Plan.Inputs dl = ready(8, 1, 20);
        dl.inDeloadWeek = true;
        assertEquals(Plan.ACTION_DELOAD, Plan.evaluate(dl).action);
        Plan.Inputs due = ready(8, 1, 20);
        due.accumulatedTrainingWeeks = 3;
        assertEquals(Plan.ACTION_DELOAD, Plan.evaluate(due).action);
    }

    @Test void neverWhileTheGentleReturnIsOpen() {
        Plan.Inputs in = ready(8, 1, 20);
        in.gentleReturnOpen = true;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action);
        assertTrue(d.reason.contains("gentle return"), d.reason);
    }

    @Test void neverUnderASafetyFlagALayoffOrUnderDelivery() {
        Plan.Inputs red = ready(8, 1, 20);
        red.redFlag = true;
        assertEquals(Plan.ACTION_STEP_BACK, Plan.evaluate(red).action);
        Plan.Inputs lay = ready(8, 1, 20);
        lay.layoff = true;
        assertEquals(Plan.ACTION_STEP_BACK, Plan.evaluate(lay).action);
        Plan.Inputs und = ready(8, 1, 20);
        und.underDelivery = true;
        assertEquals(Plan.ACTION_STEP_BACK, Plan.evaluate(und).action);
    }

    @Test void onlyWhenThreeSessionsAtThisPressureHeldTheirMinutes() {
        Plan.Inputs in = ready(8, 1, 20);
        in.ownTargetsMetAtPressure = false;               // held - but at the old pressure
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action);
        assertTrue(d.reason.contains("3 sessions"), d.reason);
    }

    @Test void theVolumeTierStillComesFirst() {
        Plan.Inputs in = ready(10, 2, 20);
        in.hasYieldData = true;
        in.consecutiveLowYield = 3;
        assertEquals(Plan.ACTION_ADD_VOLUME, Plan.evaluate(in).action, "volume before load");
    }

    @Test void onlyIntervalGirthAtLevelOne() {
        Plan.Inputs trad = ready(8, 1, 20);
        trad.track = Plan.TRACK_GIRTH_TRADITIONAL;
        trad.ownTargetsMet = false;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(trad).action, "traditional has no table");
        Plan.Inputs l2 = ready(8, 4, 20);
        l2.level = Plan.L2;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(l2).action, "Level 2 keeps its own rule");
    }

    @Test void afterTheMilestoneTheOldRuleTakesOver() {
        Plan.Inputs in = ready(8, 2, 20);
        in.netTupMin = 20.0;                               // the 20 minutes are held
        in.trainingWeeksAtPressure = 1;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action, "3 counting weeks at pressure first");
        assertTrue(d.reason.contains("of 3 training weeks"), d.reason);
        in.trainingWeeksAtPressure = 3;
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(in).action);
        Plan.Inputs past = ready(14, 3, 24);               // past the volume phase, short of 20
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(past).action,
            "the pressure phase's 8 hg rows wait for the 20 minutes");
    }

    @Test void oneStepPerEvaluationTheRealInputsWaitForSessionsAtTheNewPressure() {
        Model m = PressureClockTest.model();
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L1; st.weekIndex = 8;
        st.setWorkingPressure(17, PressureClockTest.at(0, 8));
        for (int d = 0; d <= 4; d += 2)
            PressureClockTest.file(m, "g", PressureClockTest.at(d, 9), 16, 16, 17);
        long now = PressureClockTest.at(7, 8);
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL; in.level = Plan.L1; in.monthIndex = 1;
        in.pressureKpa = st.pressureKpa; in.firstDeloadPending = false;
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 1, now, in);
        assertTrue(in.ownTargetsMetAtPressure, "three sessions at 17 held their minutes");
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action, d.reason);
        int p = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 8, st.pressureKpa, 1, 40, 0,
            d).pressureKpa;
        assertEquals(20, p);
        st.setWorkingPressure(p, now);                     // applyPlanTo / saveMint
        Plan.Inputs again = new Plan.Inputs();
        again.track = Plan.TRACK_GIRTH_INTERVAL; again.level = Plan.L1; again.monthIndex = 1;
        again.pressureKpa = st.pressureKpa; again.firstDeloadPending = false;
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 1, now + 60000L, again);
        assertFalse(again.ownTargetsMetAtPressure, "the sessions at 17 do not count at 20");
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(again).action,
            "the table still says 7 hg, but the next step waits for 3 sessions at 20");
        // Two sessions at 20: still not three.
        PressureClockTest.file(m, "g", PressureClockTest.at(7, 9), 16, 16, 20);
        PressureClockTest.file(m, "g", PressureClockTest.at(9, 9), 16, 16, 20);
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 1,
            PressureClockTest.at(11, 8), again);
        assertFalse(again.ownTargetsMetAtPressure);
        PressureClockTest.file(m, "g", PressureClockTest.at(11, 9), 16, 16, 20);
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 1,
            PressureClockTest.at(14, 8), again);
        assertTrue(again.ownTargetsMetAtPressure, "the third one at 20 earns the next step");
        PressureClockTest.file(m, "g", PressureClockTest.at(14, 9), 10, 16, 20);
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 1,
            PressureClockTest.at(16, 8), again);
        assertFalse(again.ownTargetsMetAtPressure, "a short session breaks it");
    }
}
