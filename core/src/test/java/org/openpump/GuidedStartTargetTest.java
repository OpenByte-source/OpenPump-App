package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE GUIDED START WAITS FOR THE PRESSURE IT PULLS TO.
 *
 * The guided start's pull, its screen and Settings all use min(17 kPa, ceiling), but the test
 * that lets the routine begin compared the reading with 17 kPa as it stands. Under a ceiling
 * below 17 kPa the app's own pull could never pass that test: the wait ran to its ten-minute
 * limit and vented. It failed safe, but the routine could never start on the app's own pull.
 *
 * So the pull and the test take their pressure from one place, PreRunHold#guidedTargetKpa,
 * and the test is PreRunHold#guidedAtTarget. Nothing commanded changes: the pull was already
 * clamped to the ceiling. WiringCheck invariant 127 holds the Activity to both.
 */
class GuidedStartTargetTest {

    @Test
    void theTargetIsSeventeenKpaClampedToTheCeiling() {
        assertEquals(17, PreRunHold.guidedTargetKpa(40), "the default ceiling leaves 17 kPa");
        assertEquals(17, PreRunHold.guidedTargetKpa(57));
        assertEquals(17, PreRunHold.guidedTargetKpa(17), "a ceiling of exactly 17 kPa");
        assertEquals(12, PreRunHold.guidedTargetKpa(12), "a lower ceiling lowers the target");
        assertEquals(7, PreRunHold.guidedTargetKpa(7), "the lowest ceiling Settings allows");
    }

    @Test
    void theTargetIsWhatThePullCommands() {
        for (int ceil = 7; ceil <= 57; ceil++)
            assertEquals(RunEdit.clampUpper(Model.GUIDED_START_KPA, ceil),
                PreRunHold.guidedTargetKpa(ceil), "ceiling " + ceil);
    }

    @Test
    void underALowCeilingThePullsOwnPressurePassesTheTest() {
        assertTrue(PreRunHold.guidedAtTarget(true, 12.0, 12),
            "a 12 kPa ceiling: the pull goes to 12 kPa, and 12 kPa held is the target reached");
        assertTrue(PreRunHold.guidedAtTarget(true, 12.4, 12));
        assertFalse(PreRunHold.guidedAtTarget(true, 11.9, 12), "below the clamped target");
    }

    @Test
    void everyCeilingsOwnPullCanStartTheRoutine() {
        for (int ceil = 7; ceil <= 57; ceil++) {
            int pull = PreRunHold.guidedTargetKpa(ceil);
            assertTrue(PreRunHold.guidedAtTarget(true, pull, ceil),
                "ceiling " + ceil + ": a cuff holding the pull's own " + pull + " kPa");
            assertFalse(PreRunHold.guidedAtTarget(true, pull - 0.1, ceil),
                "ceiling " + ceil + ": just under the pull is not there yet");
        }
    }

    @Test
    void atOrAboveTheDefaultCeilingNothingChanges() {
        assertFalse(PreRunHold.guidedAtTarget(true, 16.9, 40));
        assertTrue(PreRunHold.guidedAtTarget(true, 17.0, 40));
        assertTrue(PreRunHold.guidedAtTarget(true, 25.0, 40));
    }

    @Test
    void aStaleOrMissingReadingIsNeverTheTarget() {
        assertFalse(PreRunHold.guidedAtTarget(false, 30.0, 40), "not fresh");
        assertFalse(PreRunHold.guidedAtTarget(false, 12.0, 12), "not fresh, low ceiling");
        assertFalse(PreRunHold.guidedAtTarget(true, Double.NaN, 40), "not a number");
        assertFalse(PreRunHold.guidedAtTarget(true, 0.0, 40),
            "0.0 is the device's \"no measurement\", never a pressure");
        assertFalse(PreRunHold.guidedAtTarget(true, 0.0, 12), "...under a low ceiling too");
        for (int ceil = 7; ceil <= 57; ceil++)
            assertFalse(PreRunHold.guidedAtTarget(true, PreRunHold.guidedTargetKpa(ceil) - 0.05,
                ceil), "ceiling " + ceil + ": 0.05 kPa under the target is not the target");
    }

    /** A FLOOR UNDER THE TEST, whatever the ceiling: a 0.0 or non-positive reading never
     *  counts. Latent today - Settings keeps the ceiling at 7-57 kPa - but a ceiling of 0 would
     *  otherwise make the target 0 and "no measurement" would pass it. */
    @Test
    void aZeroOrNegativeReadingNeverCountsEvenAtAZeroCeiling() {
        assertFalse(PreRunHold.guidedAtTarget(true, 0.0, 0), "0.0 at a zero ceiling");
        assertFalse(PreRunHold.guidedAtTarget(true, -0.1, 0), "a negative reading");
        assertFalse(PreRunHold.guidedAtTarget(true, -3.0, -5), "a negative ceiling");
        assertFalse(PreRunHold.guidedAtTarget(true, 0.0, -5));
    }
}
