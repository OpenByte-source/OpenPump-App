package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * C8 - WHERE A STANDARDISATION HOLD'S TIME LIMIT LEAVES THE USER (study problem 2).
 *
 * The hold limit vented and then ALWAYS drew a session summary - the one call written for
 * the routine that ends into a hold. From "Log a reading", or the pre-session hold and
 * baseline, that was a previous session's summary with its questions live, and the reading
 * being typed was lost (reproduced on the emulator). {@link Nav#holdLimitLanding} is the
 * decision the app now acts on; WiringCheck invariant 52 holds the Activity to it.
 */
class HoldLimitLandingTest {

    /* ---------------------------------------------------- where the limit lands */

    @Test void theSummaryOwnsTheEndOfRunHoldAndKeepsIt() {
        assertEquals(Nav.LIMIT_SUMMARY,
            Nav.holdLimitLanding(Nav.SCR_SUMMARY, false, false),
            "the routine that ended into a hold is released from its own summary, as before");
    }

    @Test void aCaptureScreenIsKeptWithWhatWasTyped() {
        int[] captures = { Nav.SCR_LOG_READING, Nav.SCR_BASELINE, Nav.SCR_MEASURE_AFTER };
        for (int i = 0; i < captures.length; i++)
            assertEquals(Nav.LIMIT_KEEP, Nav.holdLimitLanding(captures[i], false, false),
                "screen " + captures[i] + ": the reading being typed stays where it is - the "
                + "study's run opened a stale summary from here and lost it");
    }

    @Test void theHoldScreenIsLeftTheWaySkipLeavesIt() {
        assertEquals(Nav.LIMIT_LEAVE_HOLD, Nav.holdLimitLanding(Nav.SCR_HOLD, false, false),
            "a hold that ends on its own screen goes on to that hold's capture screen, saved "
                + "at rest - never to a session summary for a session that never ran");
    }

    @Test void aSettingsPreviewGoesBackToSettings() {
        assertEquals(Nav.LIMIT_PREVIEW, Nav.holdLimitLanding(Nav.SCR_HOLD, false, true),
            "a preview records nothing and belongs to Settings");
    }

    @Test void theCameraIsNeverTornDown() {
        int[] captures = { Nav.SCR_LOG_READING, Nav.SCR_BASELINE, Nav.SCR_MEASURE_AFTER };
        for (int i = 0; i < captures.length; i++)
            assertEquals(Nav.LIMIT_STAY, Nav.holdLimitLanding(captures[i], true, false),
                "screen " + captures[i] + " with the camera up: the photo in hand is not "
                + "thrown away - the capture screen redraws when the camera hands back");
    }

    @Test void anywhereElseOnlyTheVentHappens() {
        int[] others = { Nav.SCR_RELEASE, Nav.SCR_TODAY, Nav.SCR_SETTINGS, Nav.SCR_SEAL,
                         Nav.SCR_PROGRESS };
        for (int i = 0; i < others.length; i++)
            assertEquals(Nav.LIMIT_STAY, Nav.holdLimitLanding(others[i], false, false),
                "screen " + others[i] + ": nothing to keep and nothing to redraw - above all "
                + "not a summary");
    }

    @Test void nothingButTheEndOfRunScreenLandsOnASummary() {
        for (int scr = 0; scr <= 70; scr++) {
            if (scr == Nav.SCR_SUMMARY) continue;
            assertFalse(Nav.holdLimitLanding(scr, false, false) == Nav.LIMIT_SUMMARY,
                "screen " + scr + " must never be sent to a summary by the hold limit");
            assertFalse(Nav.holdLimitLanding(scr, true, false) == Nav.LIMIT_SUMMARY,
                "screen " + scr + " (camera up) must never be sent to a summary either");
        }
    }
}
