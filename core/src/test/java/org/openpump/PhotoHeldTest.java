package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * POINT 19 - A PHOTO RECORDS HOW IT WAS TAKEN: under a hold, or at rest.
 *
 * The camera knew at the shutter whether a hold was outstanding - it chose the reference
 * angle by it - and then dropped the fact, so a saved photo could only borrow its kind from
 * whichever reading it ended up on. It is now carried Shot -> Photo -> model.json, and the
 * readers ask {@link Compare#photoHeld}, which falls back to the reading's kind only for a
 * photo that has no record of its own (written before this, or imported).
 */
class PhotoHeldTest {

    @Test void theShotCarriesItPerViewAndAResetClearsIt() {
        Shot s = new Shot();
        s.confirmAdvance(Shot.FRONT);
        s.armShutter();
        s.commit("/p/front.jpg", Double.valueOf(20.0), "", false, null, null, null, Boolean.TRUE);
        s.confirmAdvance(Shot.SIDE);
        s.armShutter();
        s.commit("/p/side.jpg", null, "", false, null, null, null, Boolean.FALSE);
        assertEquals(Boolean.TRUE, s.heldFor(Shot.FRONT));
        assertEquals(Boolean.FALSE, s.heldFor(Shot.SIDE), "each view keeps its own");

        // An import retaking a view has no shutter: the old fact must not survive onto it.
        s.confirmAdvance(Shot.FRONT);
        s.armShutter();
        s.commit("/p/front.jpg", null, "", true, null, null, null, null);
        assertNull(s.heldFor(Shot.FRONT), "an import is unknown, never the shot it replaced");

        s.reset();
        assertNull(s.heldFor(Shot.SIDE), "a fresh attempt carries nothing over");
    }

    @Test void itSurvivesASaveAndAnOldPhotoReadsUnknown() throws Exception {
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = "/p/a.jpg";
        p.held = Boolean.TRUE;
        assertEquals(Boolean.TRUE, Model.Reading.Photo.fromJson(p.toJson()).held);
        p.held = Boolean.FALSE;
        assertEquals(Boolean.FALSE, Model.Reading.Photo.fromJson(p.toJson()).held);
        p.held = null;
        assertNull(Model.Reading.Photo.fromJson(p.toJson()).held);

        Model.Reading.Photo old = Model.Reading.Photo.fromJson(
            new JSONObject("{\"path\":\"/p/front.jpg\",\"note\":\"\",\"kpa\":\"20.0\",\"ts\":\"1\"}"));
        assertNull(old.held, "a photo saved before point 19 has no record - unknown, not guessed");
    }

    private static Model.Reading reading(String id, long ts, boolean standardised, Boolean held) {
        Model.Reading r = new Model.Reading();
        r.id = id; r.ts = ts;
        if (standardised) { r.holdKpa = Double.valueOf(20.0); r.observedKpa = Double.valueOf(20.0); }
        else r.method = Model.Reading.METHOD_BPEL;
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = "/p/" + id + ".jpg";
        p.held = held;
        r.photoFront = p;
        r.photo = true;
        return r;
    }

    @Test void theRecordWinsAndTheReadingOnlyFillsAGap() {
        Model.Reading std = reading("s", 1, true, null);
        assertTrue(Compare.photoHeld(std, std.photoFront), "no record: the reading's kind");
        Model.Reading rest = reading("r", 1, false, null);
        assertFalse(Compare.photoHeld(rest, rest.photoFront));
        Model.Reading heldOnRest = reading("h", 1, false, Boolean.TRUE);
        assertTrue(Compare.photoHeld(heldOnRest, heldOnRest.photoFront),
            "a photo taken under a hold whose reading was saved without one is still held");
    }

    @Test void theGhostIsChosenFromPhotosTakenTheSameWay() {
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(reading("new-rest", 30, false, Boolean.FALSE));
        log.all.add(reading("mid-std", 20, true, Boolean.TRUE));
        log.all.add(reading("old-rest", 10, false, null));
        List<Model.Reading> held = Compare.withPhotosTaken(log, "front", true);
        assertEquals(1, held.size());
        assertEquals("mid-std", held.get(0).id);
        List<Model.Reading> rest = Compare.withPhotosTaken(log, "front", false);
        assertEquals(2, rest.size(), "both at-rest photos, the recorded and the legacy one");
        assertEquals("new-rest", rest.get(0).id, "newest first, as withPhotos keeps them");
    }

    @Test void thePhotoScreenSaysWhenThePhotoWasTakenAnotherWay() {
        Model.Reading heldOnRest = reading("h", 1, false, Boolean.TRUE);
        heldOnRest.len = 15.0;
        assertTrue(Gallery.photoFacts(heldOnRest, "front").contains("taken while the pump was holding"));
        Model.Reading same = reading("s", 1, true, Boolean.TRUE);
        same.len = 15.0; same.gir = 12.0;
        assertFalse(Gallery.photoFacts(same, "front").contains("This photo was taken"),
            "nothing to add when the photo and its reading agree");
    }
}
