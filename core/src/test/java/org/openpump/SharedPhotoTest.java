package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E3 - A SHARED PHOTO IS REMOVED FROM THIS READING ONLY, AND THE SCREEN SAYS SO.
 *
 * One save of several methods (BPEL, NBPEL and MSEG at one sitting) gives the same file to
 * each reading. Deleting it from one used to unlink the file under all of them without a
 * word. The owner's choice: remove it from this reading only, keep it on the others, and
 * let the file leave the phone only when no reading uses it. The photo day card also says
 * what each reading is in plain words ("MSEG 12.0 cm · at rest").
 */
class SharedPhotoTest {

    private String savedSize, savedUnit;

    @BeforeEach void units() {
        savedSize = Model.Fmt.sizeUnit; savedUnit = Model.Fmt.unit;
        Model.Fmt.sizeUnit = Model.Fmt.S_CM; Model.Fmt.unit = "inHg";
    }
    @AfterEach void restore() { Model.Fmt.sizeUnit = savedSize; Model.Fmt.unit = savedUnit; }

    private static Model.Reading atRest(String id, int method, double len, double gir,
                                        Model.Reading.Photo front) {
        Model.Reading r = new Model.Reading();
        r.id = id; r.ts = 1000L; r.method = method; r.len = len; r.gir = gir;
        r.photoFront = front;
        r.photo = front != null;
        return r;
    }

    private static Model.Reading.Photo photo(String path) {
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = path;
        p.ts = 1000L;
        return p;
    }

    /** One sitting: BPEL, NBPEL and MSEG sharing one POV file; another day's own photo. */
    private static Model.MeasLog sitting() {
        Model.Reading.Photo shared = photo("/p/reading-m1-front.jpg");
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(atRest("m1", Model.Reading.METHOD_BPEL, 15.2, 0, shared));
        log.all.add(atRest("m1-m4", Model.Reading.METHOD_NBPEL, 14.0, 0, shared));
        log.all.add(atRest("m1-m6", Model.Reading.METHOD_MSEG, 0, 12.0, shared));
        Model.Reading other = atRest("m0", Model.Reading.METHOD_MSEG, 0, 11.8,
                                     photo("/p/reading-m0-front.jpg"));
        other.ts = 1000L - 5 * 86400000L;
        log.all.add(other);
        return log;
    }

    @Test void theOtherReadingsOfTheSittingAreFound() {
        Model.MeasLog log = sitting();
        Model.Reading mseg = Gallery.readingOf(log, "m1-m6");
        List<Model.Reading> others = Gallery.sharers(log, mseg, "front");
        assertEquals(2, others.size());
        assertEquals("BPEL", Gallery.readingName(others.get(0)));
        assertEquals("NBPEL", Gallery.readingName(others.get(1)));
        assertTrue(Gallery.sharers(log, Gallery.readingOf(log, "m0"), "front").isEmpty(),
            "a photo of its own is shared with nobody");
    }

    @Test void theDialogSaysWhereItStays() {
        Model.MeasLog log = sitting();
        Model.Reading mseg = Gallery.readingOf(log, "m1-m6");
        List<Model.Reading> others = Gallery.sharers(log, mseg, "front");
        assertEquals("Remove this photo from the MSEG reading?", Gallery.removeTitle(mseg));
        assertEquals("It stays on the other 2 readings from that sitting (BPEL and NBPEL). It "
            + "leaves the phone only when no reading uses it.", Gallery.removeMessage(others));
        assertEquals("Photo removed from the MSEG reading · still on BPEL and NBPEL",
            Gallery.removedSnack(mseg, others));
    }

    @Test void removingFromOneLeavesTheOthersAndTheFile() {
        Model.MeasLog log = sitting();
        Model.Reading mseg = Gallery.readingOf(log, "m1-m6");
        String path = Gallery.clearPhoto(mseg, "front");
        assertEquals("/p/reading-m1-front.jpg", path);
        assertNull(mseg.photoFront, "off this reading");
        assertNotNull(Gallery.readingOf(log, "m1").photoFront, "still on BPEL");
        assertNotNull(Gallery.readingOf(log, "m1-m4").photoFront, "still on NBPEL");
        assertTrue(Gallery.pathInUse(log, path), "so the file must not be deleted");
        assertEquals(12.0, mseg.gir, 0.0, "and the reading keeps its number");

        Gallery.clearPhoto(Gallery.readingOf(log, "m1"), "front");
        Gallery.clearPhoto(Gallery.readingOf(log, "m1-m4"), "front");
        assertFalse(Gallery.pathInUse(log, path), "only now does no reading use the file");
    }

    @Test void thePhotoCountCountsFilesNotReferences() {
        assertEquals(2, Gallery.photoCount(sitting()),
            "one shared file and one other: two photos, not four");
    }

    @Test void theDayLineIsPlainWords() {
        Model.MeasLog log = sitting();
        List<Gallery.Day> days = Gallery.byDay(log);
        assertEquals("BPEL 15.2 cm · NBPEL 14.0 cm · MSEG 12.0 cm · at rest",
            Gallery.daySummary(days.get(0).readings));
        assertEquals("MSEG 11.8 cm · at rest", Gallery.daySummary(days.get(1).readings),
            "the mock's own line: no '— long', the method and its one number");

        Model.Reading std = new Model.Reading();
        std.id = "s"; std.ts = 1000L; std.len = 15.4; std.gir = 12.3;
        std.holdKpa = Double.valueOf(20.0); std.observedKpa = Double.valueOf(20.0);
        String line = Gallery.readingLine(std);
        assertTrue(line.startsWith("15.4 cm long · 12.3 cm around · standardised at "), line);
    }

    @Test void tilesNameTheirMethodOnlyWhenTheDayHoldsSeveral() {
        List<Gallery.Day> days = Gallery.byDay(sitting());
        Gallery.Day sittingDay = days.get(0);
        assertEquals("POV · BPEL", Gallery.tileLabel(sittingDay, sittingDay.items.get(0)));
        assertEquals("POV · MSEG", Gallery.tileLabel(sittingDay, sittingDay.items.get(2)));
        Gallery.Day single = days.get(1);
        assertEquals("POV", Gallery.tileLabel(single, single.items.get(0)),
            "one kind on the day: the view alone, as before");
        assertTrue(Gallery.tileName(sittingDay, sittingDay.items.get(2))
            .startsWith("POV · MSEG photo, "));
    }
}
