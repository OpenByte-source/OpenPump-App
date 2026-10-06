package org.openpump;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE FIRST-RUN SETUP'S OWN COLOURS READ. Every text colour the setup adds to Look is held to
 * WCAG AA on the surface it is drawn on - the words in the info box, the green response, the
 * amber warning, the red "Before you start" card, the "i" itself and the quiet label half.
 */
class SetupLookTest {

    private static void aa(int fg, int bg, String what) {
        assertTrue(Look.meetsAA(fg, bg, false),
            what + " is " + String.format("%.2f", Look.contrastRatio(fg, bg)) + ":1, under 4.5:1");
    }

    @Test void everySetupInkClearsAA() {
        aa(Look.SETUP_HELP_INK, Look.SETUP_HELP_FILL, "the info box's words");
        aa(Look.SETUP_SAY_INK, Look.SETUP_SAY_FILL, "a response's words");
        aa(Look.SETUP_WARN_INK, Look.SETUP_WARN_FILL, "a warning's words");
        aa(Look.SETUP_STOP_INK, Look.SETUP_STOP_TOP, "the safety card's words");
        aa(Look.SETUP_STOP_HEAD, Look.SETUP_STOP_BOTTOM, "the safety card's heading");
        aa(Look.SETUP_INFO_INK, Look.SETUP_INFO_DOT, "the closed i");
        aa(Look.GROUND, Look.INFO_BLUE, "the open i");
        aa(Look.SETUP_QUIET, Look.SURFACE, "a label's quiet half");
    }
}
