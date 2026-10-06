package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * THE OWNER'S RULE: COMPARE ONLY PHOTOS TAKEN THE SAME WAY - and never a photo with itself.
 *
 * The data-integrity review (app-polish 2624f17):
 *   1. a save of several at-rest methods gives every reading the SAME photo (E3), and the
 *      standardised group ignores the method, so Compare and the then-vs-now card paired m1
 *      with m1-m6 - one file shown as a pair (the reviewer's Pair.java, reproduced here);
 *   4. "Held, kept on its own" mixed photos from unfinished holds at different pressures,
 *      and the at-rest group mixed older photos taken erect and soft, from readings saved
 *      before methods existed.
 */
class ComparePairTest {

    private static final long DAY = 86400000L;

    private static Model.Reading.Photo stdPhoto(String path, double kpa) {
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = path; p.held = Boolean.TRUE; p.std = Boolean.TRUE;
        p.stdKpa = Double.valueOf(kpa); p.holdKpa = Double.valueOf(kpa);
        p.kpa = Double.valueOf(kpa - 0.3);
        return p;
    }

    /** The reviewer's scenario: BPEL 15.0 and MSEG 12.0 saved at once, one std photo. */
    private static Model sharedBatch(long t) {
        Model m = new Model();
        Model.Reading.Photo p = stdPhoto("/p/reading-m1-front.jpg", 20.0);
        p.ts = t;
        List<Model.Reading> batch = Meas.buildLogReadings(
            new Double[]{ 15.0, null, null, null, null, 12.0, null }, t, "d", "m1", "",
            Model.Reading.PHASE_PRE);
        for (int i = 0; i < batch.size(); i++) {
            Model.Reading r = batch.get(i);
            r.photoFront = p; r.photo = true;
            m.measLog.log(r, null);
        }
        return m;
    }

    @Test void aSharedPhotoIsNeverComparedWithItself() throws Exception {
        long t = System.currentTimeMillis() - DAY;
        Model m = sharedBatch(t);
        assertEquals(2, m.measLog.all.size(), "two readings, one photo file");
        String k = Compare.stdKey(20.0);
        assertEquals(1, Compare.withPhotos(m.measLog, "front", k).size(),
            "the same file is listed once in its group");
        assertTrue(Compare.methodsWithPairs(m.measLog, "front").isEmpty(),
            "one file is not a pair");
        assertNull(Compare.thenVsNow(m.measLog), "the then-vs-now card has nothing to pair");
        // ...and after a save and a reload, where the two records are separate objects.
        Model back = Model.fromJson(m.toJson());
        assertNull(Compare.thenVsNow(back.measLog));
        assertEquals(1, Compare.photoCount(back.measLog, "front"),
            "the count the no-pair line prints is of photos, not of readings");
    }

    @Test void withASecondPhotoThePairIsTwoDifferentFiles() throws Exception {
        long t = System.currentTimeMillis() - 2 * DAY;
        Model m = sharedBatch(t);
        Model.Reading later = new Model.Reading();
        later.id = "m2"; later.ts = t + DAY; later.len = 15.2; later.gir = 12.1;
        later.holdKpa = Double.valueOf(20.0); later.observedKpa = Double.valueOf(19.7);
        later.photoFront = stdPhoto("/p/reading-m2-front.jpg", 20.0);
        later.photo = true;
        m.measLog.log(later, null);
        Compare.ThenNow tn = Compare.thenVsNow(m.measLog);
        assertNotNull(tn);
        assertEquals(Compare.stdKey(20.0), tn.method);
        assertNotEquals(path(m, tn.firstId), path(m, tn.latestId), "never one file shown as a pair");

        // A hand pick of the duplicate reading (m1-m6) is not a second photo either.
        Model.ThenNowPick pick = new Model.ThenNowPick();
        pick.left = Model.ThenNowPick.LEFT_PICK;
        for (int i = 0; i < m.measLog.all.size(); i++)
            if (!m.measLog.all.get(i).id.equals("m2")
                    && !m.measLog.all.get(i).id.equals(tn.firstId))
                pick.leftId = m.measLog.all.get(i).id;
        Compare.ThenNow resolved = Compare.resolvePick(m.measLog, pick, t + 2 * DAY);
        assertNotEquals(path(m, resolved.firstId), path(m, resolved.latestId));
    }

    @Test void heldPhotosAreGroupedByTheirHoldPressure() {
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(held("h3", 3 * DAY, 25.0));
        log.all.add(held("h2", 2 * DAY, 20.0));
        log.all.add(held("h1", 1 * DAY, 20.0));
        Model.Reading unknown = held("h0", 0, 20.0);
        unknown.photoFront.holdKpa = null;                   // before the pressure was kept
        log.all.add(unknown);
        assertEquals(Compare.KEY_HELD + "@20.0", Compare.methodKey(log.all.get(1), "front"));
        assertEquals(Compare.KEY_HELD + "@25.0", Compare.methodKey(log.all.get(0), "front"));
        assertEquals(Compare.KEY_HELD, Compare.methodKey(unknown, "front"),
            "no pressure recorded: its own group, never paired across pressures");
        assertEquals(Arrays.asList(Compare.KEY_HELD + "@20.0"),
            Compare.methodsWithPairs(log, "front"), "only the two held at 20 kPa pair");
        assertEquals("Held, kept on its own · " + Model.Fmt.p(20.0),
            Compare.methodName(Compare.KEY_HELD + "@20.0"));
        assertEquals("held at " + Model.Fmt.p(20.0) + ", kept on its own",
            Compare.methodPhrase(Compare.KEY_HELD + "@20.0"));
        assertEquals("Held, kept on its own", Compare.methodName(Compare.KEY_HELD));
        assertFalse(Compare.isStdKey(Compare.KEY_HELD + "@20.0"));
    }

    @Test void atRestPhotosWithNoMethodRecordedAreKeptApartByWhatWasRecorded() {
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(legacy("e2", 4 * DAY, Model.Reading.STATE_HARD));
        log.all.add(legacy("s1", 3 * DAY, Model.Reading.STATE_SOFT));
        log.all.add(legacy("e1", 2 * DAY, Model.Reading.STATE_HARD));
        log.all.add(legacy("u1", 1 * DAY, Model.Reading.STATE_UNKNOWN));
        Model.Reading bpel = legacy("b1", 0, Model.Reading.STATE_HARD);
        bpel.method = Model.Reading.METHOD_BPEL;
        log.all.add(bpel);
        assertEquals(Compare.KEY_REST + ":hard", Compare.methodKey(log.all.get(0), "front"));
        assertEquals(Compare.KEY_REST + ":soft", Compare.methodKey(log.all.get(1), "front"));
        assertEquals(Compare.KEY_REST, Compare.methodKey(log.all.get(3), "front"));
        assertEquals("m" + Model.Reading.METHOD_BPEL, Compare.methodKey(bpel, "front"),
            "a recorded method is never folded into the not-recorded groups");
        assertEquals(Arrays.asList(Compare.KEY_REST + ":hard"),
            Compare.methodsWithPairs(log, "front"), "erect with erect only");
        assertEquals("At rest · erect, method not recorded",
            Compare.methodName(Compare.KEY_REST + ":hard"));
        assertEquals("At rest · soft, method not recorded",
            Compare.methodName(Compare.KEY_REST + ":soft"));
        assertEquals("At rest · method not recorded", Compare.methodName(Compare.KEY_REST));
        assertEquals("at rest, erect, method not recorded",
            Compare.methodPhrase(Compare.KEY_REST + ":hard"));
    }

    @Test void theHeldPressureSurvivesASave() throws Exception {
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = "/p/x.jpg"; p.held = Boolean.TRUE; p.std = Boolean.FALSE;
        p.holdKpa = Double.valueOf(20.0);
        assertEquals(Double.valueOf(20.0), Model.Reading.Photo.fromJson(p.toJson()).holdKpa);
        p.holdKpa = null;
        assertNull(Model.Reading.Photo.fromJson(p.toJson()).holdKpa);
        Shot s = new Shot();
        s.confirmAdvance(Shot.FRONT);
        s.armShutter();
        s.commit("/p/f.jpg", Double.valueOf(19.6), "", false, null, null, null, Boolean.TRUE,
                 null, Double.valueOf(20.0));
        assertEquals(Double.valueOf(20.0), s.holdKpaFor(Shot.FRONT));
        assertEquals(Double.valueOf(20.0), s.copy().holdKpaFor(Shot.FRONT));
        s.reset();
        assertNull(s.holdKpaFor(Shot.FRONT));
    }

    private static Model.Reading held(String id, long ts, double kpa) {
        Model.Reading r = new Model.Reading();
        r.id = id; r.ts = ts; r.len = 15; r.method = Model.Reading.METHOD_BPEL;
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = "/p/" + id + ".jpg"; p.held = Boolean.TRUE; p.std = Boolean.FALSE;
        p.holdKpa = Double.valueOf(kpa);
        r.photoFront = p; r.photo = true;
        return r;
    }

    private static Model.Reading legacy(String id, long ts, String state) {
        Model.Reading r = new Model.Reading();
        r.id = id; r.ts = ts; r.len = 15; r.state = state;       // method 0, no hold
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = "/p/" + id + ".jpg";
        r.photoFront = p; r.photo = true;
        return r;
    }

    private static String path(Model m, String id) {
        for (int i = 0; i < m.measLog.all.size(); i++)
            if (m.measLog.all.get(i).id.equals(id)) return m.measLog.all.get(i).photoFront.path;
        return null;
    }
}
