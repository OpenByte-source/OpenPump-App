package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 parity run 2, A-3 (R-46, "one change a morning"): AT MOST ONE CHANGE TO THE LENGTH WORK A
 * MORNING. Accepting the ladder's answer re-asks the question, and the next answer landed the
 * same morning: before month 3 the calendar raised the load (4 -> 4.5 lb) and then the monthly
 * creep took 24 -> 27 kPa; on an expansion-only track the month gate's level-up was followed by
 * the creep. The editor's model takes the first, in the ladder's order (the rungs, the month
 * gate, the creep and its pull), and the rest waits for the next length morning. A cut is not
 * held by it.
 */
class OneLengthChangeAMorningTest {

    private static final long DAY = LengthYear.DAY, HOUR = LengthYear.HOUR;

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * DAY + hour * HOUR;
    }

    /** Month 2, traction, the calendar's load just taken (nothing more owed), a month at 24. */
    private static Plan.Inputs loadJustTaken() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L1;
        in.monthIndex = 2;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.lengthTrainingWeeks = 8;
        in.strainSets = Plan.calendarStrainSets(in.lengthTrainingWeeks);
        in.loadLb = Plan.calendarLoadLb(in.lengthTrainingWeeks, in);
        in.pressureKpa = 24;
        in.trainingWeeksAtPressure = 4;
        return in;
    }

    /** Expansion-only length (no cylinder that pulls) at month 6 with the L2 -> L3 gate met. */
    private static Plan.Inputs expansionAtTheGate() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L2;
        in.monthIndex = 6;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = false;
        in.fitState = Traction.FIT_UNKNOWN;
        in.pressureKpa = 24;
        in.trainingWeeksAtPressure = 2;
        return in;
    }

    @Test void theCreepWaitsForTheMorningAfterTheCalendarsLoadStep() {
        Plan.Inputs in = loadJustTaken();
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(in).action,
            "on its own morning the creep is free to land");
        in.lengthChangedToday = true;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action, "the load moved this morning: " + d.rule);
        assertEquals(Plan.LENGTH_ONE_CHANGE_RULE, d.rule);
    }

    @Test void theLoadStillGoesFirst() {
        Plan.Inputs in = loadJustTaken();
        in.loadLb -= Plan.LENGTH_LOAD_STEP_LB;      // the calendar's step not yet taken
        assertEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(in).action,
            "the load step answers the morning, ahead of the creep");
    }

    @Test void theCreepWaitsForTheMorningAfterALevelUp() {
        Plan.Inputs in = expansionAtTheGate();
        assertEquals(Plan.ACTION_LEVEL_UP, Plan.evaluate(in).action, "the gate goes first");
        in.level = Plan.L3;                          // the level-up taken
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(in).action,
            "then the creep is due");
        in.lengthChangedToday = true;
        assertEquals(Plan.LENGTH_ONE_CHANGE_RULE, Plan.evaluate(in).rule,
            "but not the morning the level moved");
    }

    @Test void aCutIsNeverHeld() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.strainSets = 6;
        in.loadLb = 8.0;
        in.pressureKpa = Plan.LENGTH_SOFT_CAP_HI_KPA;
        in.strainPct = 7.0;
        in.lastStrainHigh = true;
        in.strainHighConfirmed = true;
        in.lengthChangedToday = true;
        assertEquals(Plan.LENGTH_CUT_RULE, Plan.evaluate(in).rule);
    }

    @Test void theTrackSaysWhenItsWorkChangedToday() {
        Model m = new LengthYear().setup().m;
        Model.TrainerTrackState st = m.trainerLength;
        assertFalse(LengthYear.inputsFor(m, day(2, 8)).lengthChangedToday, "nothing moved today");
        LengthTrack.acceptLoad(m, Plan.LENGTH_SLOW_LOAD_RULE, st.loadLb + 0.5, Double.NaN,
                               day(2, 8));
        assertTrue(LengthYear.inputsFor(m, day(2, 8) + 5 * LengthYear.MIN).lengthChangedToday,
            "the load moved this morning");
        assertFalse(LengthYear.inputsFor(m, day(3, 8)).lengthChangedToday, "the next morning");
        LengthTrack.crossLevel(m, st.level + 1, day(3, 8));
        assertTrue(LengthYear.inputsFor(m, day(3, 8) + 5 * LengthYear.MIN).lengthChangedToday,
            "the level moved this morning");
        assertEquals("", st.lastMintSig, "the level's routine is written afresh");
        assertFalse(LengthYear.inputsFor(m, day(4, 8)).lengthChangedToday);
        st.setWorkingPressure(st.pressureKpa + Plan.STEP_HG_KPA, day(4, 8));
        assertTrue(LengthYear.inputsFor(m, day(4, 8) + 5 * LengthYear.MIN).lengthChangedToday,
            "the creep moved the pressure this morning");
        LengthTrack.acceptSets(m, Plan.LENGTH_HANDOVER_RULE, st.strainSets + 1, day(5, 8));
        assertTrue(LengthYear.inputsFor(m, day(5, 8) + 5 * LengthYear.MIN).lengthChangedToday,
            "a strain set this morning");
    }
}
