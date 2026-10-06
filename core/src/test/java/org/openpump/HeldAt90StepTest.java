package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import org.junit.jupiter.api.Test;

/**
 * Parity run 3, A3-2 ("Same days, stop growing at 90 min"): THE STEP IS MEASURED AS IT WILL RUN.
 * The 90-minute check added each new hold's hold and drop only, but two more interval holds at
 * Level 3 also open a block - and the rest before it - so 10 -> 12 holds added 7:10 where the
 * check counted 4:10: a both-tracks day of 83 minutes passed 90 with the no-readings fallback's
 * two holds, as the editor's model (which rebuilds the routine) never lets it.
 */
class HeldAt90StepTest {

    /** The owner's both-tracks day with girth at Level 3, `holds` interval holds at 30. */
    private static Model withHolds(int holds) {
        Model m = SecondSessionTest.ownerDay();
        m.programGirth.rest = Model.Program.REST_LONG;   // girth rests half again: 270 s
        Mint.Rx g = girth(Plan.L3, holds, 30);
        asMint(m, m.trainerGirth, g, build(m, g, day(m)));
        m.trainerGirth.carriedSets = holds;
        m.trainerGirthOn = true;
        m.sched.longDays = Schedule.LONG_CAP90;
        return m;
    }

    private static long daySec(Model m) {
        long[] p = DayLength.parts(m, Schedule.PLAN_BOTH, NOW);
        return p[0] + p[1];
    }

    @Test void twoMoreHoldsAddWhatTheyRun() {
        long before = daySec(withHolds(10)), after = daySec(withHolds(12));
        Model m = withHolds(10);
        assertEquals(after - before, TrainerTab.girthStepSec(m, Plan.TRACK_GIRTH_INTERVAL,
            m.trainerGirth, 2), "the holds, their drops and the new block's rest");
        assertEquals(2 * 125 + 270, after - before);
    }

    @Test void theFallbacksTwoHoldsWaitAtNinety() {
        Model m = withHolds(10);
        assertTrue(daySec(m) + 2 * 125 <= Plan.HELD_AT_90_MIN * 60L,
            "the holds and drops alone stay under 90 min");
        assertTrue(daySec(withHolds(12)) > Plan.HELD_AT_90_MIN * 60L, "12 holds pass 90 min");
        Plan.Inputs in = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 7, NOW, in);
        assertTrue(in.heldAt90, "so the step that would take it there holds");
    }

    /* Parity run 4, A1: the length side - the D2 strain set and the no-readings fallback hold
     * at 90 min too. Traced: the app already holds them (owner_cap90__under1 wk 8 on, no field
     * differs from the model); the model logs its hold every morning, the harness once. */
    private static Plan.Inputs lengthAt90() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = 8;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.handedOver = true;
        in.strainSets = 7;
        in.loadLb = 12.0;
        in.pressureKpa = Plan.LENGTH_SOFT_CAP_HI_KPA;
        in.heldAt90 = true;
        return in;
    }

    @Test void lengthStrainAddsHoldAtNinety() {
        Plan.Inputs d2 = lengthAt90();
        d2.strainPct = 1.5;
        d2.strainMissDays = Plan.LENGTH_MISS_DEBOUNCE_DAYS;
        assertTrue(Plan.evaluate(d2).rule.startsWith(Plan.HELD_AT_90_RULE), "D2 holds");
        Plan.Inputs fb = lengthAt90();
        fb.strainSets = 5;
        fb.weeksWithoutReadings = Plan.NO_READINGS_WEEKS;
        assertTrue(Plan.evaluate(fb).rule.startsWith(Plan.HELD_AT_90_RULE), "the fallback too");
        assertEquals(330, Plan.HELD_AT_90_STRAIN_SET_SEC, "a strain set as it runs: 255+1+45+30 s");
    }
}
