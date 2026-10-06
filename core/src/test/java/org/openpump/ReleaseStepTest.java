package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * M1 (E1) - THE RELEASE STEP SAYS WHAT THE PUMP DOES.
 *
 * The release is a plain StopWork: the pump has no way to stop part-way, so it vents on
 * towards open air, and the release level is only where the routine may start. The screen
 * used to model the fall as stopping AT that level ("Sitting at −1.5 inHg"). It now models
 * the whole vent, to open air - and the moment it calls the cuff ready must not move.
 */
class ReleaseStepTest {

    private static final double RATE = 4.68;   // SessionActivity.VENT_RATE, kPa/s

    @Test void theReadyMomentIsTheSameWhetherTheModelStopsAtTheLevelOrGoesOn() {
        double from = 20.0, level = 5.0;
        for (long t = 0; t <= 8000; t += 10) {
            boolean readyBefore = Session.ventPressureAt(from, level, RATE, t) <= level;
            boolean readyNow = Session.ventPressureAt(from, 0.0, RATE, t) <= level;
            assertEquals(readyBefore, readyNow, "ready at " + t + " ms");
        }
    }

    @Test void theModelledVentGoesOnPastTheLevelToOpenAir() {
        double from = 20.0, level = 5.0;
        assertTrue(Session.ventPressureAt(from, 0.0, RATE, 4000L) < level,
            "four seconds on, the modelled pressure is below the release level - it does not "
            + "sit there");
        assertEquals(0.0, Session.ventPressureAt(from, 0.0, RATE, 60_000L), 1e-9,
            "and it ends at open air, never below it");
    }
}
