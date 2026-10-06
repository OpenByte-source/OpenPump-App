package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * KEEP THE READING (the owner's decision): a hold that ends without the person choosing it -
 * the link lost during it, or the app left during it - no longer throws the reading away on
 * the way to Today. The person comes back to the same capture, now at rest: its photos kept,
 * its held numbers cleared (M1's held-draft rule), nothing saved until the vent is evidenced
 * or confirmed by eye. {@link KeepReading#landing} is where they land.
 */
class KeepReadingTest {

    private static final String ID = "m1700000000000";

    private static Model.MeasLog log() { return new Model.MeasLog(); }

    @Test void theCaptureOnScreenIsKept() {
        int[] captures = { Nav.SCR_BASELINE, Nav.SCR_LOG_READING, Nav.SCR_MEASURE_AFTER };
        for (int i = 0; i < captures.length; i++)
            assertEquals(captures[i],
                KeepReading.landing(captures[i], captures[i], ID, log()),
                "on the capture itself, it stays that capture");
    }

    @Test void theCaptureBehindTheHoldScreenIsKept() {
        // "Hold again" and the log door's "Standardise and measure" count on the hold screen
        // with the capture open behind it: the person comes back to that capture.
        assertEquals(Nav.SCR_LOG_READING,
            KeepReading.landing(Nav.SCR_HOLD, Nav.SCR_LOG_READING, ID, log()));
        assertEquals(Nav.SCR_BASELINE,
            KeepReading.landing(Nav.SCR_HOLD, Nav.SCR_BASELINE, ID, log()));
    }

    @Test void noCaptureOpenIsTodayAsBefore() {
        // The session's own first hold, before any capture: nothing to come back to.
        assertEquals(PhotoDiscard.NO_CAPTURE,
            KeepReading.landing(Nav.SCR_HOLD, PhotoDiscard.NO_CAPTURE, ID, log()));
        assertEquals(PhotoDiscard.NO_CAPTURE,
            KeepReading.landing(Nav.SCR_TODAY, PhotoDiscard.NO_CAPTURE, ID, log()));
        assertEquals(PhotoDiscard.NO_CAPTURE,
            KeepReading.landing(Nav.SCR_SUMMARY, PhotoDiscard.NO_CAPTURE, ID, log()),
            "the routine's own end hold on the summary: no capture");
    }

    @Test void aSavedReadingOrAnUnknownOneIsNotResumed() {
        Model.MeasLog saved = log();
        Model.Reading r = new Model.Reading();
        r.id = ID;
        saved.all.add(r);
        assertEquals(PhotoDiscard.NO_CAPTURE,
            KeepReading.landing(Nav.SCR_LOG_READING, Nav.SCR_LOG_READING, ID, saved),
            "a reading already saved is not a capture to come back to");
        Model.MeasLog rows = log();
        Model.Reading row = new Model.Reading();
        row.id = ID + "-m3";
        rows.all.add(row);
        assertEquals(PhotoDiscard.NO_CAPTURE,
            KeepReading.landing(Nav.SCR_LOG_READING, Nav.SCR_LOG_READING, ID, rows),
            "...nor one saved as at-rest rows under its id");
        assertEquals(PhotoDiscard.NO_CAPTURE,
            KeepReading.landing(Nav.SCR_LOG_READING, Nav.SCR_LOG_READING, null, log()),
            "no reading id: nothing known to resume");
    }

    @Test void theLinkLostWordsClaimNoVentAndSayTheWayOn() {
        String t = KeepReading.LINK_LOST_TITLE.toLowerCase(Locale.ROOT);
        String s = KeepReading.LINK_LOST_SENTENCE.toLowerCase(Locale.ROOT);
        assertTrue(t.contains("link"), "it says the link is lost");
        assertFalse(s.contains("the pump vented") || s.contains("has vented")
                    || s.contains("vent is confirmed") || s.contains("vent confirmed"),
            "nothing claims the vent happened: \"" + KeepReading.LINK_LOST_SENTENCE + "\"");
        assertTrue(s.contains("can't be confirmed"), "it says the vent cannot be confirmed");
        assertTrue(s.contains("reconnect"), "it names reconnecting");
        assertTrue(s.contains("see the cuff is vented"), "and confirming by eye");
    }

    @Test void theAtRestWordsSayWhyAndHowTheVentIsKnown() {
        for (int reason = KeepReading.LINK_LOST; reason <= KeepReading.LEFT_APP; reason++) {
            String reported = KeepReading.atRestSentence(reason, HoldWindow.VENT_REPORTED);
            String inferred = KeepReading.atRestSentence(reason, HoldWindow.VENT_INFERRED);
            String eye = KeepReading.atRestSentence(reason, HoldWindow.VENT_BY_EYE);
            assertTrue(reported.contains("at-rest reading") && reported.contains("Measure again"));
            assertTrue(inferred.contains("no vacuum") && !inferred.contains("confirmed"),
                "an inferred vent is never announced as confirmed");
            assertTrue(eye.contains("you confirmed"));
            // The vent is said once, by how it is known - never "was vented, and the pump
            // vented" (the emulator check's finding).
            String[] all = { reported, inferred, eye };
            for (int i = 0; i < all.length; i++)
                assertEquals(all[i].indexOf("vent"), all[i].lastIndexOf("vent"),
                    "the vent is said once: \"" + all[i] + "\"");
        }
        assertTrue(KeepReading.atRestSentence(KeepReading.LINK_LOST, HoldWindow.VENT_REPORTED)
            .toLowerCase(Locale.ROOT).contains("link"));
        assertTrue(KeepReading.atRestSentence(KeepReading.LEFT_APP, HoldWindow.VENT_REPORTED)
            .toLowerCase(Locale.ROOT).contains("left the app"));
    }

    /* ---- the review of keep-reading (96f38c5) ------------------------------------------ */

    @Test void theEarlyEyeIsOfferedOnlyOnTheKeptCaptureWhileTheLinkIsDown() {
        int[] captures = { Nav.SCR_BASELINE, Nav.SCR_LOG_READING, Nav.SCR_MEASURE_AFTER };
        for (int i = 0; i < captures.length; i++)
            assertTrue(KeepReading.eyeOfferedAtOnce(true, captures[i], captures[i], false, false),
                "on the kept capture itself, with the link down, the eyes are offered at once");
        // MEDIUM: a flag left behind never brings it up early on the other vent screens -
        // the summary, the run's link-lost screen, the release gate, the validation screens -
        // nor anywhere else. There the retries' own rule stands.
        int[] elsewhere = { Nav.SCR_SUMMARY, Nav.SCR_LINK_LOST, Nav.SCR_RELEASE,
            Nav.SCR_VALIDATE_RUN, Nav.SCR_VALIDATE_INTRO, Nav.SCR_RUN, Nav.SCR_TODAY,
            Nav.SCR_HOLD, Nav.SCR_CONNECT, Nav.SCR_SETTINGS, Nav.SCR_SEAL, Nav.SCR_ASSESS };
        for (int i = 0; i < elsewhere.length; i++) {
            assertFalse(KeepReading.eyeOfferedAtOnce(true, elsewhere[i], Nav.SCR_LOG_READING,
                false, false), "screen " + elsewhere[i] + " with the kept capture behind it");
            assertFalse(KeepReading.eyeOfferedAtOnce(true, elsewhere[i], PhotoDiscard.NO_CAPTURE,
                false, false), "screen " + elsewhere[i] + " with no capture open");
        }
        assertFalse(KeepReading.eyeOfferedAtOnce(true, Nav.SCR_LOG_READING, Nav.SCR_BASELINE,
            false, false), "a capture that is not the kept one");
        assertFalse(KeepReading.eyeOfferedAtOnce(true, Nav.SCR_LOG_READING, Nav.SCR_LOG_READING,
            true, false), "the link is back: its retries can land, so the usual rule");
        assertFalse(KeepReading.eyeOfferedAtOnce(false, Nav.SCR_LOG_READING, Nav.SCR_LOG_READING,
            false, false), "not a lost link's kept capture (left the app, or none)");
        assertFalse(KeepReading.eyeOfferedAtOnce(true, Nav.SCR_LOG_READING, Nav.SCR_LOG_READING,
            false, true), "already said");
    }

    @Test void theKeptEyeUnlocksTheSaveAndOwesTheStopStill() {
        // As before: held, or an ended hold whose vent is unevidenced, is VENTING - no save.
        assertTrue(KeepReading.ventPending(false, true, true, true, false));
        assertTrue(KeepReading.ventPending(false, false, true, true, false));
        assertFalse(KeepReading.ventPending(false, false, true, false, false));
        assertFalse(KeepReading.ventPending(false, false, false, true, false));
        assertFalse(KeepReading.ventPending(true, true, false, true, false), "a live hold is OPEN");
        // The person's eyes in the kept capture open the save of the at-rest reading...
        assertFalse(KeepReading.ventPending(false, true, true, true, true),
            "the eyes unlock saving the kept, at-rest reading");
        // ...and change nothing the owed vent asks: the pressure stays held and the stop
        // unevidenced, so when the link is back the vent still goes out (invariant 119).
        assertTrue(HoldForeground.ventOwedOnLinkReturn(true, false, true, false, false),
            "held and unevidenced after the eyes: the stop is still owed");
    }

    @Test void aKeptReadingGoesWithAFinishingScreen() {
        assertTrue(KeepReading.discardOnDestroy(true, true, Nav.SCR_LOG_READING),
            "the task swiped away: discarded, as leaving it would");
        assertTrue(KeepReading.discardOnDestroy(true, true, Nav.SCR_BASELINE));
        assertFalse(KeepReading.discardOnDestroy(false, true, Nav.SCR_LOG_READING),
            "a configuration change keeps it");
        assertFalse(KeepReading.discardOnDestroy(true, false, Nav.SCR_LOG_READING),
            "a capture that was not kept: as before");
        assertFalse(KeepReading.discardOnDestroy(true, true, PhotoDiscard.NO_CAPTURE),
            "nothing open, nothing to discard");
    }
}
