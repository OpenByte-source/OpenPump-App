package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 O1 (the owner, 1 Oct 2026, refined the same day): AFTER A CONFIRMED LENGTH LOAD CUT THE
 * MONTHLY CLIMB AND THE SLOW LOAD STEP HOLD until a counted after-session reading at or under
 * 6 % - any such reading, under 2 % included; only another reading over 6 % keeps them held.
 * Then they resume from the lowered pull. (Replaces review C's deviation 8.)
 */
class LengthCutHoldTest {

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    private static Plan.Decision eval(Model m, long now) {
        return Plan.evaluate(LengthYear.inputsFor(m, now));
    }

    /** The owner, cut once: two readings over 6 %, the cut taken on day 5. */
    private static Model cutOnce() {
        Model m = new LengthYear().setup().m;
        LengthYear.measured(m, day(1, 9), "PL", 7.0);
        LengthYear.measured(m, day(3, 9), "PL", 7.0);
        Plan.Decision cut = Plan.evaluate(LengthYear.inputsFor(m, day(5, 8)));
        assertEquals(Plan.LENGTH_CUT_RULE, cut.rule);
        LengthTrack.acceptLoad(m, cut.rule, cut.loadLb, cut.pressureKpa, day(5, 8));
        return m;
    }

    private static boolean climbs(Plan.Decision d) {
        return d.action == Plan.ACTION_RAISE_PRESSURE || d.action == Plan.ACTION_RAISE_LOAD;
    }

    @Test void theClimbHoldsAfterTheCutUntilAReadingInsideTheWindow() {
        Model m = cutOnce();
        double cutLb = m.trainerLength.loadLb;
        // A month and more on the pressure: the climb is due - but no reading since the cut.
        Plan.Decision held = eval(m, day(40, 8));
        assertFalse(climbs(held), "no reading since the cut: held - " + held.rule);
        // Another reading over 6 % keeps it held.
        LengthYear.measured(m, day(41, 9), "PL", 8.0);
        Plan.Decision still = eval(m, day(50, 8));
        assertFalse(climbs(still), "a reading over 6 %: still held - " + still.rule);
        // A reading inside the window releases it, and it climbs from the lowered pull.
        LengthYear.measured(m, day(51, 9), "PL", 4.0);
        Plan.Decision go = eval(m, day(52, 8));
        assertTrue(climbs(go), "a reading at or under 6 %: the climb resumes - " + go.rule);
        assertEquals(cutLb, m.trainerLength.loadLb, 1e-9, "from the cut's load");
    }

    @Test void aReadingUnderTwoPercentReleasesItToo() {
        Model m = cutOnce();
        assertFalse(climbs(eval(m, day(40, 8))));
        LengthYear.measured(m, day(41, 9), "PL", 1.5);
        Plan.Decision go = eval(m, day(42, 8));
        assertTrue(climbs(go), "under 2 % releases the hold too - " + go.rule);
    }

    @Test void theSlowLoadStepHoldsWithIt() {
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
        in.loadLb = 6.0;
        in.lengthLoadMode = Model.LENGTH_LOAD_SLOW;
        in.slowLoadDue = true;
        in.pressureKpa = Plan.LENGTH_SOFT_CAP_HI_KPA;
        in.lastStrainHigh = true;            // the cut's own reading is still the newest
        assertFalse(Plan.LENGTH_SLOW_LOAD_RULE.equals(Plan.evaluate(in).rule));
        in.lastStrainHigh = false;           // a reading at or under 6 % came in
        assertEquals(Plan.LENGTH_SLOW_LOAD_RULE, Plan.evaluate(in).rule);
        // ...and what fills it: the cut's high reading holds it, the next one inside frees it.
        Model m = cutOnce();
        assertTrue(Meas.lastStrainHigh(m, LengthTrack.METHOD, Plan.LENGTH_STRAIN_HI));
        LengthYear.measured(m, day(8, 9), "PL", 5.9);
        assertFalse(Meas.lastStrainHigh(m, LengthTrack.METHOD, Plan.LENGTH_STRAIN_HI),
            "5.9 % is inside the window");
    }
}
