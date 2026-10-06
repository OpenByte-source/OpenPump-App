package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * t10 REAL-13 (parity round 2): ON A REDUCED RETURN DAY THE FEEDER IS THREE QUARTERS OF THE
 * GIRTH WORK AS IT RUNS - the whole-kPa working pressure less the day's cut, rounded to the
 * whole kPa the pump is sent (Mint#reducedKpa, what the girth session itself is commanded at).
 * It took three quarters of the unrounded figure, so a 33.4 kPa main on a 4 hg day gave a
 * 15 kPa feeder beside a 19 kPa girth session - three quarters of 19 is 14.
 */
class FeederReturnDayKpaTest {

    private static final double K = Model.Fmt.KPA_PER_INHG;

    private static Model main(double kpa, long now) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = now - 300L * 86400000L;
        m.trainerGirth.level = Plan.L3;
        m.trainerGirth.pressureKpa = kpa;
        return m;
    }

    private static int feeder(Model m, long now) {
        Plan.Inputs in = TrainerTab.feederInputs(m, now);
        return TrainerTab.feederRx(m, in, Plan.evaluate(in)).pressureKpa;
    }

    @Test void theFirstDayBackSharesTheGirthSessionsWholeKpa() {
        long now = System.currentTimeMillis();
        Model m = main(33.4, now);
        Deload.arm(m, now - 86400000L);              // day one back: 4 hg under
        int girth = Mint.reducedKpa(33, 4.0 * K);
        assertEquals(19, girth, "the girth session runs at 19 kPa");
        assertEquals(19.0, Deload.mainKpaOn(m, 33.4, now), 1e-9,
            "the day's main pressure is the girth session's own whole kPa");
        assertEquals(14, feeder(m, now), "three quarters of 19");
    }

    @Test void aFullDayIsUnchanged() {
        long now = System.currentTimeMillis();
        Model m = main(33.4, now);
        assertEquals(33.0, Deload.mainKpaOn(m, 33.4, now), 1e-9,
            "a full day's main is the whole kPa the girth session is sent");
        assertEquals(25, feeder(m, now));
    }

    /* t10 parity run 2, A-1: ON A FULL DAY TOO the feeder is three quarters of the whole kPa the
     * girth session runs at. REAL-15 made the plan figure exact (10 hg = 33.86), and three
     * quarters of that is 25.4 -> 25, where the girth session is sent 34 and its share 25.5 -> 26. */
    @Test void aFullDaySharesTheGirthSessionsWholeKpa() {
        long now = System.currentTimeMillis();
        Model m = main(10.0 * K, now);
        assertEquals(34.0, Deload.mainKpaOn(m, 10.0 * K, now), 1e-9,
            "the girth session runs at 34 kPa");
        assertEquals(26, feeder(m, now), "three quarters of 34, 25.5, rounds to 26");
    }
}
