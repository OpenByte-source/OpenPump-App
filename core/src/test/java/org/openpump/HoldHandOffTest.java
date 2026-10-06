package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * H1 - NO OTHER APP OVER A HELD CUFF.
 *
 * The hold's time limit lives in this app's process. Handing the phone to another app (the
 * phone's camera app when the in-app camera cannot run, the photo picker, a share sheet, a
 * system screen) puts that process in the background, where Android may end it - and a hold
 * whose limit has died with its process holds with no end, under a notification whose RELEASE
 * reaches nothing. So a hold is vented through the normal confirmed vent path BEFORE another
 * app takes the phone, and the person is told so in plain words.
 */
class HoldHandOffTest {

    @Test
    void withNoHoldOnTheCuffTheOtherAppOpensNow() {
        assertEquals(HoldHandOff.NOW, HoldHandOff.before(false, false));
        assertEquals(HoldHandOff.NOW, HoldHandOff.before(false, true),
            "an unconfirmed stop with no hold behind it is not a hold - the run's own "
            + "machinery owns it, and nothing here changes when another app may open");
    }

    @Test
    void aHoldStillCommandedIsAskedAboutAndVentedFirst() {
        assertEquals(HoldHandOff.ASK_THEN_VENT, HoldHandOff.before(true, false),
            "no stop sent for it: the pump is holding, and venting it ends the hold - a choice");
    }

    @Test
    void aHoldWhoseVentIsAlreadyOnItsWayIsWaitedForNotAskedAboutAgain() {
        assertEquals(HoldHandOff.WAIT_FOR_VENT, HoldHandOff.before(true, true),
            "the hold is already ending; there is nothing to ask, only a confirmation to wait "
            + "for before the phone is handed over");
    }

    @Test
    void theOtherAppOpensOnlyOnAConfirmedVent() {
        // A confirmed vent reads resolved() == true as well, so vented is asked first - the
        // ordering trap Handoff#next documents, in the same shape.
        assertEquals(HoldHandOff.OPEN, HoldHandOff.next(true, true, true));
        assertEquals(HoldHandOff.OPEN, HoldHandOff.next(true, true, false));
        assertEquals(HoldHandOff.UNCONFIRMED, HoldHandOff.next(true, false, true),
            "a window that closed with no fall keeps the other app closed");
        assertEquals(HoldHandOff.WAIT, HoldHandOff.next(true, false, false));
    }

    @Test
    void aHandOffNoLongerWaitingNeverOpensAnything() {
        // Superseded (another vent took the watch over) or already answered: a vent landing
        // later must not open another app behind the person's back.
        assertEquals(HoldHandOff.WAIT, HoldHandOff.next(false, true, true));
        assertEquals(HoldHandOff.WAIT, HoldHandOff.next(false, false, true));
        assertEquals(HoldHandOff.WAIT, HoldHandOff.next(false, false, false));
    }

    @Test
    void theWordsSayTheVentComesFirstThenTheApp() {
        assertEquals("The pump vents first, then the camera opens.",
            HoldHandOff.ventFirst(HoldHandOff.CAMERA));
        assertEquals("The pump vents first, then your photos open.",
            HoldHandOff.ventFirst(HoldHandOff.PHOTOS));
        assertEquals("The pump vents first, then the share sheet opens.",
            HoldHandOff.ventFirst(HoldHandOff.SHARE));
        assertTrue(HoldHandOff.venting(HoldHandOff.CAMERA).contains("pressure falling"),
            "the toast while it waits says what it is waiting for");
    }

    @Test
    void anUnconfirmedVentKeepsTheAppClosedAndSaysWhatToDo() {
        String s = HoldHandOff.unconfirmed(HoldHandOff.CAMERA);
        assertTrue(s.contains("the camera stays closed"), s);
        assertTrue(s.contains("disconnect the tubing at the cuff"), s);
        String r = HoldHandOff.refused(HoldHandOff.SHARE);
        assertTrue(r.contains("the share sheet stays closed"), r);
        String p = HoldHandOff.unconfirmed(HoldHandOff.PHOTOS);
        assertTrue(p.contains("your photos stay closed"), p);
    }

    /* ---- (the final review, I2) the connect screen's two ways to fix a link that is down */

    @Test
    void withTheLinkDownTheLinkFixOpensAndTheHoldStaysOn() {
        // The stop cannot be sent over a link that is down, so venting first would wait on a
        // watch that reads UNCONFIRMED at once - and the only way to bring the link back
        // (Bluetooth, or the app's own permissions) would stay shut behind it.
        assertEquals(HoldHandOff.OPEN_UNREACHABLE, HoldHandOff.beforeLinkFix(true, false, true),
            "a hold still commanded, the link down: the fix opens, the hold is not claimed "
            + "vented");
        assertEquals(HoldHandOff.OPEN_UNREACHABLE, HoldHandOff.beforeLinkFix(true, true, true),
            "a hold whose stop is out, the link down: the same");
    }

    @Test
    void withTheLinkUpTheLinkFixKeepsTheVentFirstRule() {
        assertEquals(HoldHandOff.before(true, false), HoldHandOff.beforeLinkFix(true, false, false));
        assertEquals(HoldHandOff.before(true, true), HoldHandOff.beforeLinkFix(true, true, false));
        assertEquals(HoldHandOff.NOW, HoldHandOff.beforeLinkFix(false, false, true),
            "no hold: nothing to say, the fix opens");
        assertEquals(HoldHandOff.NOW, HoldHandOff.beforeLinkFix(false, true, false));
    }

    @Test
    void theUnreachableSentenceSaysWhatIsTrueAndNeverThatItIsTrying() {
        assertEquals("The pump can't be reached, so it can't be vented from here. Turn "
            + "Bluetooth on, or disconnect the tubing at the cuff.", HoldHandOff.UNREACHABLE);
        String s = HoldHandOff.unconfirmed(HoldHandOff.CAMERA, true);
        assertTrue(s.startsWith("The camera stays closed. "), s);
        assertTrue(s.contains(HoldHandOff.UNREACHABLE), s);
        assertFalse(s.toLowerCase(Locale.US).contains("trying"),
            "with the link down nothing is trying to vent: " + s);
        assertEquals(HoldHandOff.unconfirmed(HoldHandOff.PHOTOS),
            HoldHandOff.unconfirmed(HoldHandOff.PHOTOS, false),
            "with the link up the watch is retrying, and says so as before");
    }

    @Test
    void atRestIsSaidAsWhatItIsNeverAsLess() {
        neutral(HoldHandOff.AT_REST);
        for (int how : new int[]{ HoldWindow.VENT_REPORTED, HoldWindow.VENT_INFERRED,
                                  HoldWindow.VENT_BY_EYE }) {
            String s = HoldHandOff.endedSentence(how);
            neutral(s);
            assertTrue(s.contains("at-rest reading"), s);
            assertTrue(s.contains("Measure again at rest"), "measured again, never carried over");
            assertFalse(s.contains("opened"),
                "an unconfirmed vent keeps the other app closed; the sentence cannot claim it "
                + "opened: " + s);
        }
        assertTrue(HoldHandOff.endedSentence(HoldWindow.VENT_BY_EYE).contains("you confirmed"));
        assertTrue(HoldHandOff.endedSentence(HoldWindow.VENT_INFERRED).contains("reads no vacuum"),
            "an inferred vent is never said as a confirmed one");
    }

    private static void neutral(String s) {
        String l = s.toLowerCase(Locale.US);
        for (String bad : new String[]{ "flagged", "not standardised", "no longer",
                                        "like-for-like", "fine, but", "not comparable",
                                        "can't be compared", "only" })
            assertFalse(l.contains(bad), "\"" + s + "\" says \"" + bad + "\"");
    }
}
