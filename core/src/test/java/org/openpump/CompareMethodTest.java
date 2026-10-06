package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * POINT 19 - COMPARE PAIRS PHOTOS OF THE SAME METHOD, AND SAYS WHICH.
 *
 * Compare seeded the oldest photo of a view against the newest whatever method each was
 * taken with, and printed a coloured "length +0.60 cm" between a BPEL sitting and a
 * standardised one; the then-vs-now card did the same without naming either. At rest and
 * standardised are equal methods, each compared only with its own kind - so the key a photo
 * is paired by is its method ({@link Compare#methodKey}), and every pairing list is one key.
 */
class CompareMethodTest {

    /** The owner's rule: standardised photos are grouped by their hold pressure too. */
    private static final String STD20 = Compare.stdKey(20.0);

    private static Model.Reading std(String id, long ts) {
        Model.Reading r = base(id, ts);
        r.holdKpa = Double.valueOf(20.0);
        r.observedKpa = Double.valueOf(20.0);
        r.photoFront.held = Boolean.TRUE;
        return r;
    }

    private static Model.Reading atRest(String id, long ts, int method) {
        Model.Reading r = base(id, ts);
        r.method = method;
        r.photoFront.held = Boolean.FALSE;
        return r;
    }

    private static Model.Reading base(String id, long ts) {
        Model.Reading r = new Model.Reading();
        r.id = id; r.ts = ts; r.len = 15; r.gir = 12;
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = "/p/" + id + ".jpg";
        r.photoFront = p;
        r.photo = true;
        return r;
    }

    @Test void eachPhotoIsKeyedByItsMethod() {
        assertEquals(STD20, Compare.methodKey(std("s", 1), "front"));
        assertEquals("m" + Model.Reading.METHOD_BPEL,
            Compare.methodKey(atRest("b", 1, Model.Reading.METHOD_BPEL), "front"));
        Model.Reading heldNoHold = base("h", 1);
        heldNoHold.photoFront.held = Boolean.TRUE;           // the count was not served
        assertEquals(Compare.KEY_HELD, Compare.methodKey(heldNoHold, "front"));
        Model.Reading legacy = base("l", 1);                  // method 0, no hold, no record
        assertEquals(Compare.KEY_REST, Compare.methodKey(legacy, "front"));
        assertNull(Compare.methodKey(legacy, "side"), "no photo of that view, no key");
    }

    @Test void theNamesSayWhatEachIsWithoutRankingThem() {
        assertEquals("Standardised", Compare.methodName(Compare.KEY_STD));
        assertEquals("BPEL", Compare.methodName("m" + Model.Reading.METHOD_BPEL));
        assertEquals("BPEL at rest", Compare.methodPhrase("m" + Model.Reading.METHOD_BPEL));
        assertEquals("At rest · method not recorded", Compare.methodName(Compare.KEY_REST));
        String line = Compare.noPairLine("front", 2, Arrays.asList(
            "m" + Model.Reading.METHOD_BPEL, Compare.KEY_STD));
        assertTrue(line.startsWith("Your 2 POV photos were each taken a different way (BPEL at "
            + "rest and standardised)"), line);
        assertFalse(line.toLowerCase().contains("not comparable"), line);
        assertFalse(line.toLowerCase().contains("flagged"), line);
    }

    private static Model.MeasLog mixedLog() {
        Model.MeasLog log = new Model.MeasLog();          // newest first, as MeasLog keeps it
        log.all.add(std("s3", 900));
        log.all.add(atRest("b2", 800, Model.Reading.METHOD_BPEL));
        log.all.add(std("s2", 700));
        log.all.add(atRest("m1", 600, Model.Reading.METHOD_MSEG));
        log.all.add(atRest("b1", 500, Model.Reading.METHOD_BPEL));
        log.all.add(std("s1", 400));
        return log;
    }

    @Test void theListsAreOneMethodEach() {
        Model.MeasLog log = mixedLog();
        List<Model.Reading> s = Compare.withPhotos(log, "front", STD20);
        assertEquals(Arrays.asList("s3", "s2", "s1"), ids(s));
        List<Model.Reading> b = Compare.withPhotos(log, "front", "m" + Model.Reading.METHOD_BPEL);
        assertEquals(Arrays.asList("b2", "b1"), ids(b));
        assertEquals(Arrays.asList(STD20, "m" + Model.Reading.METHOD_BPEL),
            Compare.methodsWithPairs(log, "front"),
            "MSEG has one photo, so no pair; the others in order of their newest photo");
        assertEquals(STD20, Compare.defaultMethod(log, "front"),
            "Compare opens on the method of the newest photo that has a pair");
    }

    @Test void aLibraryWithNoTwoAlikeHasNoDefaultPair() {
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(std("s1", 900));
        log.all.add(atRest("b1", 800, Model.Reading.METHOD_BPEL));
        assertNull(Compare.defaultMethod(log, "front"),
            "two photos taken two ways are not a pair - the old screen would have paired them");
        assertEquals(2, Compare.methodsOf(log, "front").size());
    }

    @Test void thenVsNowSpansOneMethodAndNamesIt() {
        Model.MeasLog log = mixedLog();
        Compare.ThenNow tn = Compare.thenVsNow(log);
        assertEquals("front", tn.view);
        assertEquals(STD20, tn.method, "the deepest method: 3 standardised vs 2 BPEL");
        assertEquals("s1", tn.firstId, "its OWN first, never an older BPEL photo");
        assertEquals("s3", tn.latestId);

        // A hand-picked photo of another method is not a valid side of this pair.
        Model.ThenNowPick pick = new Model.ThenNowPick();
        pick.left = Model.ThenNowPick.LEFT_PICK;
        pick.leftId = "b1";
        Compare.ThenNow resolved = Compare.resolvePick(log, pick, 1000);
        assertEquals("s1", resolved.firstId, "a pick outside the method falls back to the default");
        assertEquals(STD20, resolved.method);
    }

    private static List<String> ids(List<Model.Reading> rs) {
        String[] out = new String[rs.size()];
        for (int i = 0; i < rs.size(); i++) out[i] = rs.get(i).id;
        return Arrays.asList(out);
    }
}
