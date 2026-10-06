package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * "RESUME WHERE I WAS" (the owner's decision, 2026-09-27): after a plan pause or the month-12
 * break, the same positions, level, week and working pressure - never higher - with the
 * gentle return armed. A pause longer than the layoff rule is still a layoff: the step-back
 * rules apply.
 */
class ResumePlanTest {

    static long at(int day, int hour) { return PressureClockTest.at(day, hour); }

    /** Month 2, Level 1, week 9, at 23 kPa, three weeks of M/W/F sessions ending on day 18 (a Friday). */
    static Model trainedThenPaused() {
        Model m = PressureClockTest.model();
        m.trainerMonthsPumping = 2;                       // month 2: 23 kPa is under its cap
        m.trainerFirstDeloadTaken = true;
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L1; st.weekIndex = 9; st.weekBaseIndex = 9; st.weekBaseMs = at(0, 0);
        st.setWorkingPressure(23, at(0, 0));
        for (int w = 0; w < 3; w++)
            for (int d = 0; d <= 4; d += 2)
                PressureClockTest.file(m, "g", at(7 * w + d, 9), 18, 18, 23);
        m.trainerEnrolled = false;                        // PausePlanConfirm
        return m;
    }

    /** The plan's decision for the girth track at `now`, inputs assembled as the app does. */
    static Plan.Decision evaluate(Model m, long now) {
        int track = Plan.TRACK_GIRTH_INTERVAL;
        Model.TrainerTrackState st = m.trainerGirth;
        int month = TrainerTab.monthIndexNow(m, now);
        Plan.Inputs in = new Plan.Inputs();
        in.track = track; in.level = st.level; in.monthIndex = month;
        in.pressureKpa = st.pressureKpa; in.ceilKpa = m.ceilKpa;
        in.layoff = TrainerTab.layoff(m, track, now);
        in.inDeloadWeek = TrainerTab.inDeloadWeek(m, now);
        in.accumulatedTrainingWeeks = TrainerTab.planTrainingWeeks(m,
            TrainerTab.deloadAnchorMs(m), now);
        in.firstDeloadPending = !m.trainerFirstDeloadTaken;
        in.redFlag = m.trainerState == Model.TRAINER_STATE_SAFETY_FLAG;
        in.returnRunsUnder = TrainerTab.returnRunsUnder(m, now);
        TrainerTab.fillGirthInputs(m, track, st, month, now, in);
        return Plan.evaluate(in);
    }

    @Test void onlyAPausedPlanResumes() {
        assertFalse(TrainerTab.canResume(new Model()), "never set up: set up");
        Model m = trainedThenPaused();
        assertTrue(TrainerTab.canResume(m));
        m.trainerEnrolled = true;
        assertFalse(TrainerTab.canResume(m), "running: nothing to resume");
    }

    @Test void aShortPauseResumesTheSamePositionWithAGentleReturn() {
        Model m = trainedThenPaused();
        Model.TrainerTrackState st = m.trainerGirth;
        long since = st.pressureSinceMs;
        long now = at(21, 8);                             // the Monday after: 3 days away
        TrainerTab.resumePlan(m, now);
        assertTrue(m.trainerEnrolled);
        assertEquals(Plan.L1, st.level);
        assertEquals(9, st.weekIndex);
        assertEquals(9, st.weekBaseIndex);
        assertEquals(at(0, 0), st.weekBaseMs);
        assertEquals(23.0, st.pressureKpa, 1e-9, "the same working pressure");
        assertEquals(since, st.pressureSinceMs, "an unchanged pressure keeps its count");
        assertTrue(Deload.armed(m), "the gentle return is armed");
        assertTrue(TrainerTab.returnRunsUnder(m, now));
        assertTrue(Deload.cutHg(m, Summary.dayNumber(now)) > 0.0, "the first day runs under");
        Plan.Decision d = evaluate(m, now);
        assertNotEquals(Plan.ACTION_STEP_BACK, d.action, "3 days is not a layoff: " + d.reason);
        assertNotEquals(Plan.ACTION_RAISE_PRESSURE, d.action, "no step during the return");
    }

    @Test void aPauseLongerThanTheLayoffRuleStillStepsBack() {
        Model m = trainedThenPaused();
        long now = at(18 + 21, 8);                        // three weeks after the last session
        TrainerTab.resumePlan(m, now);
        assertTrue(TrainerTab.layoff(m, Plan.TRACK_GIRTH_INTERVAL, now),
            "a resume records no deload window: the time away is still a layoff");
        Plan.Decision d = evaluate(m, now);
        assertEquals(Plan.ACTION_STEP_BACK, d.action, d.reason);
        assertEquals(23.0, m.trainerGirth.pressureKpa, 1e-9, "and never higher");
        assertTrue(Deload.armed(m), "with the gentle return armed as well");
    }

    @Test void neverHigherThanTodaysCap() {
        Model m = trainedThenPaused();
        m.trainerMonthsPumping = 0;
        Model.TrainerTrackState st = m.trainerGirth;
        st.setWorkingPressure(27, at(0, 0));              // above the month-0 6 hg cap
        long now = at(21, 8);
        assertEquals(0, TrainerTab.monthIndexNow(m, now));
        TrainerTab.resumePlan(m, now);
        assertEquals(Math.floor(Plan.pressureCapKpa(Plan.L1, 0)), st.pressureKpa, 1e-9,
            "brought down to today's cap, a whole kPa");
        assertTrue(st.pressureKpa < 27);
        assertEquals(now, st.pressureSinceMs, "a lowered pressure starts its own count");
    }

    @Test void theMonthTwelveBreakResumesAtItsReEntryPosition() {
        Model m = trainedThenPaused();
        Model.TrainerTrackState st = m.trainerGirth;
        long brk = at(20, 12);
        // An upgrader's old month-12 girth break (before t10-K): Level 2, week 1, 8 hg, paused.
        st.level = Plan.L2;
        st.weekIndex = 1; st.weekBaseIndex = 1; st.weekBaseMs = brk;
        st.setWorkingPressure(Plan.L234_FLOOR_KPA, brk);
        st.yieldSets = 0; st.yieldSinceMs = brk;
        m.trainerEnrolled = false;
        long now = at(20 + 35, 8);                        // five weeks off
        TrainerTab.resumePlan(m, now);
        assertEquals(Plan.L2, st.level);
        assertEquals(1, st.weekIndex);
        assertEquals(Plan.L234_FLOOR_KPA, st.pressureKpa, 1e-9);
        assertTrue(Deload.armed(m));
        assertEquals(Plan.ACTION_STEP_BACK, evaluate(m, now).action,
            "five weeks off is a layoff: the step-back still applies");
    }

    @Test void theLengthTrackIsKeptAsItWas() {
        Model m = trainedThenPaused();
        m.trainerLengthOn = true;
        Model.TrainerTrackState len = m.trainerLength;
        len.level = Plan.L2; len.weekIndex = 5; len.loadLb = 4.5;
        len.setWorkingPressure(40, at(0, 0));
        TrainerTab.resumePlan(m, at(21, 8));
        assertEquals(Plan.L2, len.level);
        assertEquals(5, len.weekIndex);
        assertEquals(4.5, len.loadLb, 1e-9);
        assertEquals(40.0, len.pressureKpa, 1e-9, "never raised, never girth-capped");
    }
}
