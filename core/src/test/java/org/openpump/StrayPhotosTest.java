package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * STRAY PHOTOS (0.10). A photo whose reading was never saved - the app killed mid-capture -
 * is deleted at the next start, but only when no reading has its id or refers to it, no
 * capture is making it, and it is over 24 hours old; and never when the model holds no
 * readings at all. Run over a real temporary folder, the way the Activity runs it
 * (SessionActivity#sweepStrayPhotos; WiringCheck invariant 222).
 */
class StrayPhotosTest {

    @TempDir Path dir;

    private static final long NOW = 2_000_000_000_000L;
    private static final long OLD = NOW - StrayPhotos.MIN_AGE_MS - 60_000L;   // 24 h + 1 min
    private static final long NEW = NOW - 60_000L;                             // 1 min

    private File file(String name, long modified) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.write(f.toPath(), new byte[]{ 1, 2, 3 });
        assertTrue(f.setLastModified(modified));
        return f;
    }

    private static Model.Reading reading(String id, String frontPath) {
        Model.Reading r = new Model.Reading();
        r.id = id; r.ts = 1000L; r.method = Model.Reading.METHOD_BPEL; r.len = 15;
        if (frontPath != null) {
            Model.Reading.Photo p = new Model.Reading.Photo();
            p.path = frontPath;
            r.photoFront = p;
        }
        return r;
    }

    /** What the Activity does: list the folder, ask, delete. Returns what was deleted. */
    private List<String> sweep(Model.MeasLog log, List<String> open) {
        File[] all = dir.toFile().listFiles();
        List<StrayPhotos.Entry> files = new ArrayList<StrayPhotos.Entry>();
        for (File f : all) if (f.isFile()) files.add(new StrayPhotos.Entry(f.getName(), f.lastModified()));
        List<String> gone = StrayPhotos.toDelete(files, log, open, NOW);
        for (String n : gone) assertTrue(new File(dir.toFile(), n).delete());
        Collections.sort(gone);
        return gone;
    }

    private boolean exists(String name) { return new File(dir.toFile(), name).exists(); }

    @Test void anOldPhotoOfAReadingThatWasNeverSavedIsSwept() throws Exception {
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(reading("m1", dir.resolve("reading-m1-front.jpg").toString()));
        file("reading-m1-front.jpg", OLD);
        file("reading-m9-front.jpg", OLD);
        file("reading-m9-side.jpg", OLD);
        file("reading-m9-top.jpg", OLD);
        assertEquals(Arrays.asList("reading-m9-front.jpg", "reading-m9-side.jpg",
                                   "reading-m9-top.jpg"), sweep(log, null));
        assertTrue(exists("reading-m1-front.jpg"), "a saved reading's photo stays");
    }

    @Test void aNewFileIsNeverTouched_aCaptureMayStillNeedIt() throws Exception {
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(reading("m1", null));
        file("reading-m9-front.jpg", NEW);
        file("reading-m8-front.jpg", NOW - StrayPhotos.MIN_AGE_MS);   // exactly 24 h: kept
        file("reading-m7-front.jpg", NOW + 3_600_000L);               // a clock in the future
        assertTrue(sweep(log, null).isEmpty());
        assertTrue(exists("reading-m9-front.jpg") && exists("reading-m8-front.jpg")
                   && exists("reading-m7-front.jpg"));
    }

    @Test void aCaptureInProgressIsNeverTouched() throws Exception {
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(reading("m1", null));
        file("reading-m9-front.jpg", OLD);
        assertTrue(sweep(log, Arrays.asList("m9")).isEmpty());
        assertTrue(exists("reading-m9-front.jpg"));
    }

    @Test void aReferencedFileStays_evenUnderAnotherIdOrFolder() throws Exception {
        Model.MeasLog log = new Model.MeasLog();
        // E3: an at-rest batch's second row shares the first row's file, and the first row
        // may since have been deleted; a restored backup can point at another folder.
        log.all.add(reading("m5-m6", "/old/app/Pictures/reading-m5-front.jpg"));
        log.all.add(reading("m2", "/elsewhere/reading-m3-side.jpg"));
        file("reading-m5-front.jpg", OLD);
        file("reading-m5-side.jpg", OLD);    // no reference - but its batch row exists
        file("reading-m3-side.jpg", OLD);
        assertTrue(sweep(log, null).isEmpty());
    }

    @Test void nothingButACapturesOwnNameIsACandidate() throws Exception {
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(reading("m1", null));
        file(".nomedia", OLD);
        file("capture-pending.jpg", OLD);
        file("reading-m9-back.jpg", OLD);
        file("reading--front.jpg", OLD);
        file("reading-m9-front.png", OLD);
        file("holiday.jpg", OLD);
        assertTrue(new File(dir.toFile(), ".restore-staging").mkdirs());
        assertTrue(sweep(log, null).isEmpty());
        assertNull(StrayPhotos.idOf("reading-m9-back.jpg"));
        assertEquals("m1-m6", StrayPhotos.idOf("reading-m1-m6-front.jpg"));
        assertEquals("m12", StrayPhotos.idOf("reading-m12-top.jpg"));
    }

    @Test void anEmptyLibraryIsNeverSwept_itCannotBeToldFromALostOne() throws Exception {
        file("reading-m9-front.jpg", OLD);
        assertTrue(sweep(new Model.MeasLog(), null).isEmpty());
        assertTrue(sweep(null, null).isEmpty());
        assertTrue(exists("reading-m9-front.jpg"));
    }

    @Test void anIdIsComparedWhole() throws Exception {
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(reading("m12", null));
        file("reading-m1-front.jpg", OLD);   // m1 is not m12
        assertEquals(Arrays.asList("reading-m1-front.jpg"), sweep(log, null));
        assertFalse(exists("reading-m1-front.jpg"));
    }
}
