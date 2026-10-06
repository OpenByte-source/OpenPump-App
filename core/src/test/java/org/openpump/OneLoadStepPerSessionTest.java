package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 REAL-11 (parity round 2): AT MOST ONE LOAD STEP BETWEEN TWO LENGTH SESSIONS. The slow
 * calendar step (R-46) was taken one morning and "the pull follows the climb" (R-43) the next -
 * a girth-only morning, no length session between them - so the next pull was two steps
 * heavier than the last one run (5 -> 6.7 lb). A load that has moved since the last length
 * session waits for a session at it before it moves up again; a cut (lighter) is never held.
 */
class OneLoadStepPerSessionTest {

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    /** Month 7, L3, 6 strain sets, the slow step due; a month at a pressure past the usual top
     *  with the pull behind it - both the slow step and the climb's pull want to land. */
    private static Plan.Inputs bothWant() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.handedOver = true;
        in.strainSets = 6;
        in.loadLb = 5.0;
        in.lengthLoadMode = Model.LENGTH_LOAD_SLOW;
        in.slowLoadDue = true;
        in.pressureKpa = Plan.LENGTH_SOFT_CAP_HI_KPA + Plan.STEP_HG_KPA;
        in.trainingWeeksAtPressure = 1;
        in.climbTopKpa = 41 - 0.14;
        return in;
    }

    @Test void theSlowStepWaitsForASessionAtTheLastOne() {
        Plan.Inputs in = bothWant();
        assertEquals(Plan.LENGTH_SLOW_LOAD_RULE, Plan.evaluate(in).rule, "due, and free to land");
        in.loadMovedSinceSession = true;
        Plan.Decision d = Plan.evaluate(in);
        assertNotEquals(Plan.ACTION_RAISE_LOAD, d.action, "the load moved since the last session: "
            + d.rule);
    }

    @Test void theClimbsPullWaitsToo() {
        Plan.Inputs in = bothWant();
        in.slowLoadDue = false;
        assertEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(in).action, "the pull follows the climb");
        in.loadMovedSinceSession = true;
        assertNotEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(in).action);
    }

    @Test void theCalendarAndTheNeverReachedLoadWaitToo() {
        Plan.Inputs cal = new Plan.Inputs();
        cal.track = Plan.TRACK_LENGTH;
        cal.level = Plan.L1;
        cal.monthIndex = 1;
        cal.ceilKpa = 43;
        cal.firstDeloadPending = false;
        cal.lengthTube = true;
        cal.boreCm = 4.5;
        cal.strainSets = 4;
        cal.loadLb = Plan.LENGTH_LOAD_START_LB;
        cal.lengthTrainingWeeks = 8;
        cal.pressureKpa = 20;
        assertEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(cal).action, Plan.evaluate(cal).rule);
        cal.loadMovedSinceSession = true;
        assertNotEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(cal).action);

        Plan.Inputs d2 = bothWant();
        d2.slowLoadDue = false;
        d2.trainingWeeksAtPressure = 0;
        d2.strainSets = Plan.LENGTH_STRAIN_SETS_MAX;
        d2.strainPct = 1.5;
        d2.strainMissDays = 8;
        assertEquals(Plan.LENGTH_NEVER_RULE, Plan.evaluate(d2).rule);
        assertEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(d2).action);
        d2.loadMovedSinceSession = true;
        assertNotEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(d2).action);
    }

    @Test void aCutIsNeverHeld() {
        Plan.Inputs in = bothWant();
        in.strainPct = 7.0;
        in.lastStrainHigh = true;
        in.strainHighConfirmed = true;
        in.loadLb = 8.0;
        in.loadMovedSinceSession = true;
        assertEquals(Plan.LENGTH_CUT_RULE, Plan.evaluate(in).rule);
    }

    @Test void theLogsSayWhetherTheLoadMovedSinceTheLastLengthSession() {
        Model m = new LengthYear().setup().m;
        LengthYear.measured(m, day(1, 9), "PL", 3.0);
        assertFalse(LengthYear.inputsFor(m, day(2, 8)).loadMovedSinceSession,
            "a session has run at the setup load");
        LengthTrack.acceptLoad(m, Plan.LENGTH_SLOW_LOAD_RULE, m.trainerLength.loadLb + 0.5,
            Double.NaN, day(2, 8));
        assertTrue(LengthYear.inputsFor(m, day(3, 8)).loadMovedSinceSession,
            "no length session since the step");
        LengthYear.measured(m, day(3, 9), "PL", 3.0);
        assertFalse(LengthYear.inputsFor(m, day(4, 8)).loadMovedSinceSession,
            "a session ran at the new load");
        // The same load written again is not a move.
        LengthTrack.acceptLoad(m, Plan.LENGTH_SLOW_LOAD_RULE, m.trainerLength.loadLb,
            Double.NaN, day(4, 8));
        assertFalse(LengthYear.inputsFor(m, day(5, 8)).loadMovedSinceSession);
    }
}
