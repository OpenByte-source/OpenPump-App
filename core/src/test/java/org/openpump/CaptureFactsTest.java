package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * A PHOTO TAKEN UNDER A HOLD KEEPS THE HOLD'S PRESSURE - all the way to the stored record.
 *
 * The emulator pass (app-polish c81dcae): a photo taken at about -5.8 inHg under a hold was
 * stored as "held":"1","std":"0","kpa":"19.7","hkpa":"". CameraScreen released the pending
 * capture - which clears the commanded pressure - BEFORE shot.commit() read it, so the hold
 * pressure was always null, and held photos at different pressures shared one group: the
 * very pairing 9020d24 set out to prevent. The earlier tests passed because they handed the
 * pressure straight to Shot.commit(); none followed a capture to what is stored.
 *
 * Now the facts of a capture are taken as ONE snapshot ({@link CaptureFacts}) before anything
 * is released, the commit is made from that snapshot alone, and the record saved with a
 * reading is built by {@link Shot#photoRecord} - so this follows a capture from its facts,
 * through the Shot, to the JSON the model writes.
 */
class CaptureFactsTest {

    private static final double HOLD = 20.0, DELIVERED = 19.7;

    private static Shot committed(CaptureFacts f) {
        Shot s = new Shot();
        s.confirmAdvance(Shot.FRONT);
        s.armShutter();
        assertEquals(Shot.FRONT, f.commitTo(s, "/p/reading-m1-front.jpg", null));
        return s;
    }

    private static JSONObject stored(Shot s) throws Exception {
        return Model.Reading.Photo.fromJson(s.photoRecord(Shot.FRONT, 1000L).toJson()).toJson();
    }

    @Test void aHeldPhotoStoresTheHoldPressure() throws Exception {
        // Held before its count was served - SHORT: kept on its own, at 20 kPa.
        CaptureFacts f = new CaptureFacts(false, Double.valueOf(HOLD), Double.valueOf(DELIVERED),
                                          PhotoTruth.TAKEN_SHORT, Double.valueOf(85.3),
                                          Double.valueOf(0.0), "");
        Shot s = committed(f);
        assertEquals(Double.valueOf(HOLD), s.holdKpaFor(Shot.FRONT));
        JSONObject o = stored(s);
        assertEquals("1", o.getString("held"));
        assertEquals("0", o.getString("std"));
        assertEquals("20.0", o.getString("hkpa"), "the stored record carries the hold pressure");
        assertEquals("19.7", o.getString("kpa"));
        Model.Reading.Photo back = Model.Reading.Photo.fromJson(o);
        assertEquals(Double.valueOf(HOLD), back.holdKpa);
    }

    @Test void aServedPhotoStoresBothItsStandardisedAndItsHoldPressure() throws Exception {
        CaptureFacts f = new CaptureFacts(false, Double.valueOf(HOLD), Double.valueOf(DELIVERED),
                                          PhotoTruth.TAKEN_STANDARDISED, null, null, "note");
        JSONObject o = stored(committed(f));
        assertEquals("1", o.getString("std"));
        assertEquals("20.0", o.getString("skpa"));
        assertEquals("20.0", o.getString("hkpa"));
        assertEquals("note", o.getString("note"));
    }

    @Test void atRestAndImportedPhotosClaimNoHold() throws Exception {
        JSONObject rest = stored(committed(new CaptureFacts(false, null, null,
            PhotoTruth.TAKEN_AT_REST, Double.valueOf(1.0), Double.valueOf(2.0), "")));
        assertEquals("0", rest.getString("held"));
        assertEquals("", rest.getString("hkpa"));
        assertEquals("", rest.getString("skpa"));
        // An import has no shutter: nothing about a hold, and no angle, even if the capture
        // was armed while one was on.
        JSONObject imp = stored(committed(new CaptureFacts(true, Double.valueOf(HOLD),
            Double.valueOf(DELIVERED), PhotoTruth.TAKEN_STANDARDISED, Double.valueOf(1.0),
            Double.valueOf(2.0), "")));
        assertEquals("", imp.getString("held"));
        assertEquals("", imp.getString("std"));
        assertEquals("", imp.getString("hkpa"));
        assertEquals("", imp.getString("tilt"));
    }

    @Test void theSnapshotIsTakenOnceAndTheReleaseCannotEmptyIt() {
        // What the old order did: read the pending fields after releasing them. A snapshot
        // taken first keeps every fact, whatever happens to the fields afterwards.
        Double[] pendingHeld = { Double.valueOf(HOLD) };
        CaptureFacts f = new CaptureFacts(false, pendingHeld[0], null, PhotoTruth.TAKEN_SHORT,
                                          null, null, "");
        pendingHeld[0] = null;                                   // releasePendingBitmap()
        assertEquals(Double.valueOf(HOLD), f.heldKpa);
        assertEquals(Double.valueOf(HOLD), committed(f).holdKpaFor(Shot.FRONT));
    }

    @Test void theRecordIsEverythingTheShotKept() {
        Shot s = new Shot();
        s.confirmAdvance(Shot.SIDE);
        s.armShutter();
        Model.EditProfile e = Model.EditProfile.of(1.2, -3.0, 4.0, 5.0);
        new CaptureFacts(false, Double.valueOf(HOLD), Double.valueOf(DELIVERED),
            PhotoTruth.TAKEN_STANDARDISED, Double.valueOf(80.0), Double.valueOf(1.5), "n")
            .commitTo(s, "/p/s.jpg", e);
        Model.Reading.Photo p = s.photoRecord(Shot.SIDE, 42L);
        assertEquals("/p/s.jpg", p.path);
        assertEquals(42L, p.ts);
        assertEquals(Double.valueOf(DELIVERED), p.kpa);
        assertEquals(Double.valueOf(80.0), p.tiltDeg);
        assertEquals(Double.valueOf(1.5), p.turnDeg);
        assertEquals(Double.valueOf(1.2), p.editZoom);
        assertEquals(Double.valueOf(5.0), p.editPanY);
        assertEquals(Boolean.TRUE, p.held);
        assertEquals(Boolean.TRUE, p.std);
        assertEquals(Double.valueOf(HOLD), p.stdKpa);
        assertEquals(Double.valueOf(HOLD), p.holdKpa);
        assertNull(s.photoRecord(Shot.FRONT, 42L).path, "nothing taken for that view");
    }
}
