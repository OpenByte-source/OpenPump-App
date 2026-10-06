package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * POINT 19 - the capture flow's words tell the truth about the photo (PhotoTruth).
 *
 * The review label said "Taken at −5.9 inHg", in green, whenever any hold was outstanding,
 * including a skipped hold whose reading is filed without one; and it spoke an at-rest shot
 * as "not comparable to standardised ones". The label now says "standardised" only when the
 * reading will be, names an at-rest photo for what it is, and never ranks one below the
 * other.
 */
class PhotoTruthTest {

    private String savedUnit;

    @BeforeEach void inHg() { savedUnit = Model.Fmt.unit; Model.Fmt.unit = "inHg"; }
    @AfterEach void restore() { Model.Fmt.unit = savedUnit; }

    private static final Double KPA20 = Double.valueOf(20.0);

    @Test void atRestIsSaidAsWhatItIs() {
        String[] l = PhotoTruth.reviewLabel(false, PhotoTruth.TAKEN_AT_REST, null, 30);
        assertEquals("At rest", l[0]);
        assertTrue(l[1].contains("other at-rest photos"), l[1]);
        assertFalse((l[0] + l[1]).toLowerCase().contains("not comparable"),
            "an at-rest photo is one of the two equal kinds, never 'not comparable'");
        assertEquals(PhotoTruth.TAKEN_AT_REST,
            PhotoTruth.takenState(false, HoldWindow.AT_REST));
    }

    /** The label follows HoldWindow's verdict at the moment the photo is taken (M1: the two
     *  minutes are counted until Save), never a verdict remembered from the hold screen. */
    @Test void standardisedOnlyInsideTheServedWindow() {
        assertEquals(PhotoTruth.TAKEN_STANDARDISED,
            PhotoTruth.takenState(true, HoldWindow.OPEN));
        String[] ok = PhotoTruth.reviewLabel(false, PhotoTruth.TAKEN_STANDARDISED, KPA20, 30);
        assertTrue(ok[0].startsWith("Standardised · "), ok[0]);
        assertTrue(ok[0].contains("inHg"), "the vacuum follows the display unit: " + ok[0]);

        assertEquals(PhotoTruth.TAKEN_SHORT, PhotoTruth.takenState(true, HoldWindow.SHORT));
        String[] shortOf = PhotoTruth.reviewLabel(false, PhotoTruth.TAKEN_SHORT, KPA20, 30);
        assertEquals("Held at −5.9 inHg · for less than 30 s", shortOf[0]);
        assertTrue(shortOf[1].contains("kept on its own"), shortOf[1]);

        assertEquals(PhotoTruth.TAKEN_LAPSED,
            PhotoTruth.takenState(true, HoldWindow.LAPSED));
        String[] lapsed = PhotoTruth.reviewLabel(false, PhotoTruth.TAKEN_LAPSED, KPA20, 30);
        assertEquals("Held at −5.9 inHg · after the two minutes", lapsed[0]);
        assertFalse((lapsed[0] + shortOf[0]).contains("Standardised"),
            "a reading saved outside the count or the two minutes is not standardised, so its "
            + "photo is not called that");
    }

    /**
     * THE OWNER'S WORDS: STANDARDISED ONCE "THE COUNT FINISHES". A photo is standardised only
     * when the count has completed and the pump is still holding inside its two-minute window
     * (HoldWindow OPEN). A photo taken while the count is still running - 12 s into a 30 s
     * count, SHORT by the dwell - is held, kept on its own, and says so; it is never called
     * standardised on the strength of a count that had not finished (this replaces the
     * earlier rule that labelled it standardised).
     */
    @Test void aPhotoWhileTheCountIsStillRunningIsKeptApart() {
        int taken = PhotoTruth.takenState(true, HoldWindow.SHORT);
        assertEquals(PhotoTruth.TAKEN_SHORT, taken, "the count has not finished: not standardised");
        String[] l = PhotoTruth.reviewLabel(false, taken, KPA20, 30);
        assertEquals("Held at −5.9 inHg · for less than 30 s", l[0]);
        assertTrue(l[1].contains("kept on its own"), l[1]);
        assertFalse(l[0].startsWith("Standardised"), l[0]);
        // ...and what the camera keeps for it is the same verdict: not a standardised photo,
        // so Compare groups it "Held, kept on its own" (PhotoStandardisedTest).
        assertEquals(PhotoTruth.TAKEN_STANDARDISED, PhotoTruth.takenState(true, HoldWindow.OPEN),
            "the moment the count has finished, inside the two minutes, it is standardised");
    }

    /** M1: a hold whose vent has been sent is neither held nor at rest until the fall is
     *  seen. A photo taken then says so, and claims no pressure. */
    @Test void aPhotoWhileTheVentIsConfirmedSaysVenting() {
        assertEquals(PhotoTruth.TAKEN_VENTING,
            PhotoTruth.takenState(true, HoldWindow.VENTING));
        assertEquals(PhotoTruth.TAKEN_VENTING,
            PhotoTruth.takenState(false, HoldWindow.VENTING),
            "held already cleared, the vent still unconfirmed: still venting, not at rest");
        String[] l = PhotoTruth.reviewLabel(false, PhotoTruth.TAKEN_VENTING, KPA20, 30);
        assertEquals("Venting · the pressure is falling", l[0]);
        assertFalse(l[0].contains("inHg"));
    }

    @Test void anUnmeasuredVacuumIsSaidAsUnknownNeverAsTheSetpoint() {
        assertEquals("Standardised · vacuum not measured",
            PhotoTruth.reviewLabel(false, PhotoTruth.TAKEN_STANDARDISED, null, 30)[0]);
        assertEquals("Held · vacuum not measured · for less than 45 s",
            PhotoTruth.reviewLabel(false, PhotoTruth.TAKEN_SHORT, null, 45)[0]);
    }

    @Test void anImportCannotClaimAPressureOrAnAngle() {
        String[] l = PhotoTruth.reviewLabel(true, PhotoTruth.TAKEN_STANDARDISED, KPA20, 30);
        assertEquals("▣ Imported", l[0]);
        assertFalse(l[0].contains("inHg"), "no shutter, so no pressure stamp");
        assertTrue(l[1].contains("unknown"), l[1]);
    }

    @Test void thePermissionMessageIsTrueAndOffersWhatWorks() {
        assertEquals("Camera permission is off", PhotoTruth.PERMISSION_TITLE);
        String free = PhotoTruth.permissionLine(false);
        assertTrue(free.contains("settings") && free.contains("gallery"), free);
        assertFalse(free.contains("camera app"),
            "the phone's camera app is refused too without the permission - never offered");
        assertTrue(PhotoTruth.permissionLine(true).contains("once this reading is saved"),
            "under a hold the settings wait: leaving the app vents the pump");
    }

    @Test void theAdvanceToastSaysPovNotFront() {
        assertEquals("POV saved — now the Side view",
            PhotoTruth.savedAdvance(Shot.FRONT, Shot.SIDE));
    }

    /**
     * THE PRESSURE AT THE SHUTTER IS THE LIVE READOUT'S (the emulator pass, c81dcae). A photo
     * said "Standardised · vacuum not measured" while the screen showed -5.9 inHg live: the
     * shutter asked the vent watch's 600 ms freshness bound, the readout calls a reading live
     * for five seconds. The shutter now takes the latest reading by the readout's own bound,
     * and says "not measured" only when there is no fresh reading - never a stale number,
     * never 0.0, never the setpoint.
     */
    @Test void theShutterTakesTheLatestLiveReading() {
        long bound = 5000L;
        assertEquals(Double.valueOf(19.7),
            PhotoTruth.shutterKpa(19.7, false, 10_000L, 14_000L, bound),
            "4 s old: the readout still calls it live, so does the photo");
        assertEquals(null, PhotoTruth.shutterKpa(19.7, false, 10_000L, 15_000L, bound),
            "as old as the bound: not live any more - vacuum not measured");
        assertEquals(null, PhotoTruth.shutterKpa(0.0, true, 10_000L, 10_100L, bound),
            "the device not measuring is no reading, never 0.0");
        assertEquals(null, PhotoTruth.shutterKpa(19.7, false, 0L, 100L, bound),
            "no reading ever: not measured");
    }
}
