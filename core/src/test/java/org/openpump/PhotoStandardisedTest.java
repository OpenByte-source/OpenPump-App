package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * THE OWNER'S RULE: A PHOTO IS GROUPED BY HOW IT WAS TAKEN; THE NUMBERS KEEP THEIR OWN KIND.
 *
 * A photo taken during a SERVED hold - the count completed, the pump holding at the hold
 * pressure, inside its two minutes - is a standardised photo, compared WITH the standardised
 * photos taken at the same pressure, even when its reading's numbers end up saved at rest
 * because the hold ended or vented before Save. It used to be grouped "Held, kept on its
 * own". Only the same way is compared, so a different pressure is a different group; a photo
 * taken under a hold whose count had not completed stays apart, as before.
 *
 * The photo carries that truth itself ({@link Model.Reading.Photo#std},
 * {@link Model.Reading.Photo#stdKpa}), recorded at the shutter, and it survives a save, a
 * backup and a restore (the model's JSON).
 */
class PhotoStandardisedTest {

    private static final double K20 = 20.0, K25 = 25.0;

    /** A reading saved AT REST (BPEL) whose photo records how it was taken. */
    private static Model.Reading restWithPhoto(String id, long ts, Boolean held, Boolean std,
                                               Double stdKpa) {
        Model.Reading r = new Model.Reading();
        r.id = id; r.ts = ts; r.len = 15; r.method = Model.Reading.METHOD_BPEL;
        r.photoFront = photo(id, held, std, stdKpa);
        r.photo = true;
        return r;
    }

    /** A STANDARDISED reading at `holdKpa` whose photo records how it was taken. */
    private static Model.Reading stdWithPhoto(String id, long ts, double holdKpa, Boolean held,
                                              Boolean std, Double stdKpa) {
        Model.Reading r = new Model.Reading();
        r.id = id; r.ts = ts; r.len = 15; r.gir = 12;
        r.holdKpa = Double.valueOf(holdKpa); r.observedKpa = Double.valueOf(holdKpa);
        r.photoFront = photo(id, held, std, stdKpa);
        r.photo = true;
        return r;
    }

    private static Model.Reading.Photo photo(String id, Boolean held, Boolean std, Double stdKpa) {
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = "/p/" + id + ".jpg";
        p.held = held; p.std = std; p.stdKpa = stdKpa;
        return p;
    }

    @Test void aServedHoldPhotoOnAnAtRestReadingIsStandardised() {
        // The owner's case: taken in the two minutes, the hold then vented, the numbers
        // measured again at rest and saved as BPEL.
        Model.Reading r = restWithPhoto("r", 1, Boolean.TRUE, Boolean.TRUE, Double.valueOf(K20));
        assertEquals(Compare.KIND_STD, Compare.photoKind(r, r.photoFront));
        assertEquals(Double.valueOf(K20), Compare.photoStdKpa(r, r.photoFront));
        assertEquals(Compare.stdKey(K20), Compare.methodKey(r, "front"),
            "grouped with the standardised photos, not 'Held, kept on its own'");
        assertTrue(Compare.isStdKey(Compare.methodKey(r, "front")));
    }

    @Test void itIsComparedWithTheStandardisedPhotosAtTheSamePressure() {
        Model.MeasLog log = new Model.MeasLog();                       // newest first
        log.all.add(restWithPhoto("vented", 900, Boolean.TRUE, Boolean.TRUE, Double.valueOf(K20)));
        log.all.add(stdWithPhoto("new", 800, K20, Boolean.TRUE, Boolean.TRUE, Double.valueOf(K20)));
        log.all.add(stdWithPhoto("other", 700, K25, Boolean.TRUE, Boolean.TRUE, Double.valueOf(K25)));
        log.all.add(stdWithPhoto("legacy", 600, K20, Boolean.TRUE, null, null));   // before this
        log.all.add(stdWithPhoto("older", 500, K20, null, null, null));            // before point 19
        List<Model.Reading> at20 = Compare.withPhotos(log, "front", Compare.stdKey(K20));
        assertEquals(Arrays.asList("vented", "new", "legacy", "older"), ids(at20),
            "one group: every standardised photo at 20 kPa, recorded or derived");
        assertEquals(Arrays.asList("other"),
            ids(Compare.withPhotos(log, "front", Compare.stdKey(K25))),
            "only the same way is compared: another pressure is another group");
        assertEquals(Arrays.asList(Compare.stdKey(K20)), Compare.methodsWithPairs(log, "front"));
        assertEquals(Compare.stdKey(K20), Compare.defaultMethod(log, "front"));
    }

    @Test void aHoldWhoseCountDidNotCompleteStaysApart() {
        // Taken under the hold before its count was served (or after its two minutes).
        Model.Reading onRest = restWithPhoto("a", 1, Boolean.TRUE, Boolean.FALSE, null);
        assertEquals(Compare.KIND_HELD, Compare.photoKind(onRest, onRest.photoFront));
        assertEquals(Compare.KEY_HELD, Compare.methodKey(onRest, "front"));
        // ...even on a reading that ended up standardised (Hold again served it after).
        Model.Reading onStd = stdWithPhoto("b", 1, K20, Boolean.TRUE, Boolean.FALSE, null);
        assertEquals(Compare.KEY_HELD, Compare.methodKey(onStd, "front"),
            "grouped by how the PHOTO was taken, not by its reading");
        // At rest stays at rest, by its reading's method.
        Model.Reading rest = restWithPhoto("c", 1, Boolean.FALSE, Boolean.FALSE, null);
        assertEquals(Compare.KIND_REST, Compare.photoKind(rest, rest.photoFront));
        assertEquals("m" + Model.Reading.METHOD_BPEL, Compare.methodKey(rest, "front"));
    }

    @Test void aPhotoWithNoRecordTakesItsReadingsKindAsBefore() {
        Model.Reading std = stdWithPhoto("s", 1, K20, Boolean.TRUE, null, null);
        assertEquals(Compare.stdKey(K20), Compare.methodKey(std, "front"),
            "held on a standardised reading: standardised at the reading's hold pressure");
        Model.Reading held = restWithPhoto("h", 1, Boolean.TRUE, null, null);
        assertEquals(Compare.KEY_HELD, Compare.methodKey(held, "front"),
            "held on an at-rest reading, nothing more recorded: kept on its own, as before");
        Model.Reading old = restWithPhoto("o", 1, null, null, null);
        assertEquals("m" + Model.Reading.METHOD_BPEL, Compare.methodKey(old, "front"));
    }

    @Test void theNamesSayWhatIsTrue() {
        String k = Compare.stdKey(K20);
        assertEquals("Standardised · " + Model.Fmt.p(K20), Compare.methodName(k));
        assertEquals("standardised at " + Model.Fmt.p(K20), Compare.methodPhrase(k));
        assertEquals(Double.valueOf(K20), Compare.stdKpaOfKey(k));
        assertNull(Compare.stdKpaOfKey(Compare.KEY_HELD));
        assertFalse(Compare.isStdKey(Compare.KEY_HELD));
        assertFalse(Compare.isStdKey("m" + Model.Reading.METHOD_BPEL));
        assertTrue(Compare.noPairLine("front", 2, Arrays.asList(Compare.stdKey(K20),
                Compare.stdKey(K25)))
            .contains("(standardised at " + Model.Fmt.p(K20) + " and standardised at "
                      + Model.Fmt.p(K25) + ")"));
    }

    @Test void thePhotoScreenAndThePdfSayHowThePhotoWasTaken() {
        Model.Reading r = restWithPhoto("r", 1, Boolean.TRUE, Boolean.TRUE, Double.valueOf(K20));
        assertTrue(Gallery.photoFacts(r, "front").contains(
                "This photo was taken standardised, at " + Model.Fmt.p(K20)),
            Gallery.photoFacts(r, "front"));
        r.photoFront.kpa = Double.valueOf(19.6);                        // delivered at the shutter
        assertEquals("standardised at " + Model.Fmt.p(19.6),
            Compare.photoTakenLine(r, r.photoFront));
        r.photoFront.kpa = null;
        assertEquals("standardised, vacuum not measured", Compare.photoTakenLine(r, r.photoFront));
        Model.Reading held = restWithPhoto("h", 1, Boolean.TRUE, Boolean.FALSE, null);
        held.photoFront.kpa = Double.valueOf(19.6);
        assertEquals("held at " + Model.Fmt.p(19.6), Compare.photoTakenLine(held, held.photoFront));
        assertTrue(Gallery.photoFacts(held, "front").contains("taken while the pump was holding"));
        Model.Reading rest = restWithPhoto("a", 1, Boolean.FALSE, Boolean.FALSE, null);
        assertEquals("at rest", Compare.photoTakenLine(rest, rest.photoFront));
        assertFalse(Gallery.photoFacts(rest, "front").contains("This photo was taken"),
            "nothing to add when the photo and its reading agree");
        Model.Reading agree = stdWithPhoto("s", 1, K20, Boolean.TRUE, Boolean.TRUE,
                                           Double.valueOf(K20));
        assertFalse(Gallery.photoFacts(agree, "front").contains("This photo was taken"));
    }

    @Test void theShotCarriesItAndAResetOrAnImportClearsIt() {
        Shot s = new Shot();
        s.confirmAdvance(Shot.FRONT);
        s.armShutter();
        s.commit("/p/f.jpg", Double.valueOf(19.6), "", false, null, null, null, Boolean.TRUE,
                 Double.valueOf(K20));
        s.confirmAdvance(Shot.SIDE);
        s.armShutter();
        s.commit("/p/s.jpg", Double.valueOf(19.6), "", false, null, null, null, Boolean.TRUE,
                 null);
        assertEquals(Double.valueOf(K20), s.stdKpaFor(Shot.FRONT));
        assertNull(s.stdKpaFor(Shot.SIDE), "held, the count not served: not standardised");
        Shot c = s.copy();
        assertEquals(Double.valueOf(K20), c.stdKpaFor(Shot.FRONT), "an Undo's copy keeps it");
        s.confirmAdvance(Shot.FRONT);
        s.armShutter();
        s.commit("/p/f.jpg", null, "", true, null, null, null, null);
        assertNull(s.stdKpaFor(Shot.FRONT), "an import has no shutter: never the shot it replaced");
        c.reset();
        assertNull(c.stdKpaFor(Shot.FRONT));
    }

    @Test void itSurvivesASaveAndAnOldPhotoReadsUnknown() throws Exception {
        Model.Reading.Photo p = photo("x", Boolean.TRUE, Boolean.TRUE, Double.valueOf(K20));
        Model.Reading.Photo back = Model.Reading.Photo.fromJson(p.toJson());
        assertEquals(Boolean.TRUE, back.std);
        assertEquals(Double.valueOf(K20), back.stdKpa);
        p.std = Boolean.FALSE; p.stdKpa = null;
        back = Model.Reading.Photo.fromJson(p.toJson());
        assertEquals(Boolean.FALSE, back.std, "FALSE is the answer a write-only-when-true loses");
        assertNull(back.stdKpa);
        Model.Reading.Photo old = Model.Reading.Photo.fromJson(new JSONObject(
            "{\"path\":\"/p/f.jpg\",\"note\":\"\",\"kpa\":\"\",\"ts\":\"1\",\"held\":\"1\"}"));
        assertNull(old.std, "written before this: unknown, never guessed into the file");
        assertNull(old.stdKpa);
    }

    private static List<String> ids(List<Model.Reading> rs) {
        String[] out = new String[rs.size()];
        for (int i = 0; i < rs.size(); i++) out[i] = rs.get(i).id;
        return Arrays.asList(out);
    }
}
