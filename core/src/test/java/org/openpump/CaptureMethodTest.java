package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * C8 - A SESSION CAPTURE SCREEN SHOWS A STEPPER ONLY FOR WHAT ITS SAVE KEEPS (study
 * problem 6).
 *
 * The baseline and after screens always drew a Girth stepper, pre-filled. At rest every
 * method they offer is a length method, and the save writes a girth only when the method
 * measures one - so a carefully typed girth vanished without a word. {@link
 * Meas#captureMethod} is the one decision both the screen (which steppers to draw) and the
 * save (what to file) now ask; WiringCheck invariant 54 holds both to it.
 */
class CaptureMethodTest {

    private static final int[] AT_REST_SESSION_METHODS = {
        Model.Reading.METHOD_BPEL, Model.Reading.METHOD_BPSSL, Model.Reading.METHOD_BPSL,
        Model.Reading.METHOD_NBPEL, Model.Reading.METHOD_NBPSL };

    @Test void atRestTheChosenLengthMethodIsFiledAndNoGirthIsShown() {
        for (int i = 0; i < AT_REST_SESSION_METHODS.length; i++) {
            int m = Meas.captureMethod(false, true, AT_REST_SESSION_METHODS[i]);
            assertEquals(AT_REST_SESSION_METHODS[i], m, "at rest the chosen method is filed");
            assertTrue(Model.Reading.methodMeasuresLength(m), "...and its length is kept");
            assertFalse(Model.Reading.methodMeasuresGirth(m),
                Model.Reading.methodLabel(m) + " keeps no girth, so the screen draws no Girth "
                + "stepper - the number typed there was the one silently dropped");
        }
    }

    @Test void aServedHoldIsStandardisedAndKeepsBoth() {
        int m = Meas.captureMethod(true, false, Model.Reading.METHOD_BPEL);
        assertEquals(Model.Reading.METHOD_STANDARDIZED, m);
        assertTrue(Model.Reading.methodMeasuresLength(m) && Model.Reading.methodMeasuresGirth(m),
            "a standardised reading keeps length and girth, so both steppers are drawn");
    }

    @Test void aHoldNotServedIsSavedWithNoHoldAndKeepsBoth() {
        // A skipped hold with pressure still up, or one the hold limit ended: saved with no
        // hold recorded, as the Std reading - and both numbers typed under it are kept.
        int m = Meas.captureMethod(false, false, Model.Reading.METHOD_NBPSL);
        assertEquals(Model.Reading.METHOD_STANDARDIZED, m,
            "the chosen at-rest method is not claimed for a reading taken under pressure");
        assertTrue(Model.Reading.methodMeasuresGirth(m), "...and its girth is kept");
    }

    @Test void standardisedWinsOverAStaleAtRestAnswer() {
        assertEquals(Model.Reading.METHOD_STANDARDIZED,
            Meas.captureMethod(true, true, Model.Reading.METHOD_BPSSL),
            "a served hold is standardised whatever the at-rest picker last said");
    }
}
