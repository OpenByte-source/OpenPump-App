package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/**
 * PRIVACY - A DISCARDED CAPTURE LEAVES NO PHOTO BEHIND, AND NOTHING ELSE IS TOUCHED
 * (PhotoDiscard). A photo taken under a hold that was then given up stayed on the phone with
 * no reading; now the files a discarded capture made are deleted - but only those, and never
 * one a saved reading (or a shared-photo reference, E3) still uses. WiringCheck invariant 85
 * holds the app to asking this before any photo buffer is emptied.
 */
class PhotoDiscardTest {

    private static final String DIR = "/storage/emulated/0/Android/data/org.openpump/files/Pictures/";

    private static Shot captured(String id, String... views) {
        Shot s = new Shot();
        for (int i = 0; i < views.length; i++) {
            s.confirmAdvance(views[i]);
            s.armShutter();
            s.commit(DIR + Shot.filename(id, views[i]), null, "", false, null, null, null,
                     Boolean.TRUE);
        }
        return s;
    }

    private static Model.Reading reading(String id, String frontPath) {
        Model.Reading r = new Model.Reading();
        r.id = id; r.ts = 1000L; r.method = Model.Reading.METHOD_BPEL; r.len = 15;
        if (frontPath != null) {
            Model.Reading.Photo p = new Model.Reading.Photo();
            p.path = frontPath;
            r.photoFront = p;
            r.photo = true;
        }
        return r;
    }

    @Test void aGivenUpCapturesPhotosAreDeleted() {
        Shot s = captured("m1", Shot.FRONT, Shot.SIDE);
        assertEquals(Arrays.asList(DIR + "reading-m1-front.jpg", DIR + "reading-m1-side.jpg"),
            PhotoDiscard.filesToDelete(s, "m1", new Model.MeasLog()),
            "nothing saved them: both files go");
    }

    @Test void aSavedCapturesPhotosAreKept() {
        Shot s = captured("m1", Shot.FRONT);
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(reading("m1", DIR + "reading-m1-front.jpg"));
        assertTrue(PhotoDiscard.filesToDelete(s, "m1", log).isEmpty(),
            "the reading it was taken for uses it");
    }

    @Test void aSharedPhotoIsKeptWhileAnyReadingUsesIt() {
        Shot s = captured("m1", Shot.FRONT);
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(reading("m1-m6", DIR + "reading-m1-front.jpg"));   // E3: another reading
        assertTrue(PhotoDiscard.filesToDelete(s, "m1", log).isEmpty());
    }

    @Test void aFileThisCaptureDidNotMakeIsNeverTouched() {
        Shot s = captured("m9", Shot.FRONT);
        assertTrue(PhotoDiscard.filesToDelete(s, "m1", new Model.MeasLog()).isEmpty(),
            "a path under another capture's name is not this capture's to delete");
        assertFalse(PhotoDiscard.madeBy(DIR + "reading-m12-front.jpg", "m1", Shot.FRONT),
            "names are compared whole");
        assertTrue(PhotoDiscard.madeBy("C:\\pics\\reading-m1-front.jpg", "m1", Shot.FRONT));
        assertTrue(PhotoDiscard.filesToDelete(s, null, new Model.MeasLog()).isEmpty());
        assertTrue(PhotoDiscard.filesToDelete(null, "m1", new Model.MeasLog()).isEmpty());
        assertTrue(PhotoDiscard.filesToDelete(new Shot(), "m1", new Model.MeasLog()).isEmpty());
    }

    @Test void theUndoCopyIsJudgedTheSameWay() {
        Shot live = captured("m1", Shot.FRONT);
        Shot kept = live.copy();
        live.reset();                                  // "Remove these photos"
        assertEquals(Collections.singletonList(DIR + "reading-m1-front.jpg"),
            PhotoDiscard.filesToDelete(kept, "m1", new Model.MeasLog()),
            "once the Undo window closes, the removed photo goes");
        live.copyFrom(kept);                           // UNDO
        assertEquals(DIR + "reading-m1-front.jpg", live.pathFor(Shot.FRONT),
            "and an Undo inside the window puts it back first");
    }

    @Test void whatCountsAsLeavingACapture() {
        assertTrue(PhotoDiscard.leavesCapture(Nav.SCR_LOG_READING, Nav.SCR_TODAY));
        assertTrue(PhotoDiscard.leavesCapture(Nav.SCR_BASELINE, Nav.SCR_RELEASE),
            "Save or Skip measurements, and on to the release gate");
        assertTrue(PhotoDiscard.leavesCapture(Nav.SCR_MEASURE_AFTER, Nav.SCR_PROGRESS));
        assertFalse(PhotoDiscard.leavesCapture(Nav.SCR_LOG_READING, Nav.SCR_CONNECT),
            "the connect screen is a detour, like the hold screen - going there keeps it");
        assertFalse(PhotoDiscard.leavesCapture(Nav.SCR_LOG_READING, Nav.SCR_LOG_READING),
            "a re-render is not leaving");
        assertFalse(PhotoDiscard.leavesCapture(Nav.SCR_BASELINE, Nav.SCR_HOLD),
            "Hold again counts on the hold screen and hands back to the same capture");
        assertFalse(PhotoDiscard.leavesCapture(Nav.SCR_TODAY, Nav.SCR_LOG_READING));
        assertFalse(PhotoDiscard.leavesCapture(Nav.SCR_HOLD, Nav.SCR_BASELINE));
        assertFalse(PhotoDiscard.leavesCapture(PhotoDiscard.NO_CAPTURE, Nav.SCR_TODAY));
    }

    /** The capture stays open behind a detour (the hold screen, the connect screen), so
     *  leaving the DETOUR to anywhere but the capture is leaving the capture. */
    @Test void theCaptureStaysOpenBehindADetour() {
        int open = PhotoDiscard.captureAfter(PhotoDiscard.NO_CAPTURE, Nav.SCR_LOG_READING);
        assertEquals(Nav.SCR_LOG_READING, open);
        open = PhotoDiscard.captureAfter(open, Nav.SCR_HOLD);          // Hold again
        assertEquals(Nav.SCR_LOG_READING, open, "the hold screen keeps it open");
        assertTrue(PhotoDiscard.leavesCapture(open, Nav.SCR_TODAY),
            "given up on the hold screen: the capture is left, its photos go");
        assertFalse(PhotoDiscard.leavesCapture(open, Nav.SCR_LOG_READING),
            "the hold handing back to its capture keeps it");

        open = PhotoDiscard.captureAfter(Nav.SCR_LOG_READING, Nav.SCR_CONNECT);
        assertEquals(Nav.SCR_LOG_READING, open, "the pump chip's connect screen keeps it open");
        assertTrue(PhotoDiscard.leavesCapture(open, Nav.SCR_TODAY),
            "and the connect screen leaving to Today leaves the capture");
        assertEquals(PhotoDiscard.NO_CAPTURE, PhotoDiscard.captureAfter(open, Nav.SCR_TODAY));

        assertEquals(Nav.SCR_MEASURE_AFTER,
            PhotoDiscard.captureAfter(Nav.SCR_BASELINE, Nav.SCR_MEASURE_AFTER),
            "another capture screen is its own capture");
        assertEquals(PhotoDiscard.NO_CAPTURE,
            PhotoDiscard.captureAfter(PhotoDiscard.NO_CAPTURE, Nav.SCR_HOLD),
            "a detour opens nothing");
    }

    /**
     * THE CONNECT SCREEN HANDS BACK TO ITS CAPTURE. The log door says "Not connected to the
     * pump" and the pump chip opens the connect screen over the capture; every way out of it
     * (connected, "Not now", "Done", Back) goes back to that capture as it was left - unless
     * it can no longer be resumed, when it goes to Today and the capture is discarded.
     */
    @Test void theConnectScreenHandsBackToAResumableCapture() {
        Model.MeasLog log = new Model.MeasLog();
        log.all.add(reading("m12", DIR + "reading-m12-front.jpg"));   // someone else's
        assertEquals(Nav.SCR_LOG_READING,
            PhotoDiscard.captureToResume(Nav.SCR_LOG_READING, "m1", "m1", log),
            "the log door, same reading, not saved: back to it");
        assertEquals(Nav.SCR_BASELINE,
            PhotoDiscard.captureToResume(Nav.SCR_BASELINE, "m1", "m1", log));
        assertEquals(Nav.SCR_MEASURE_AFTER,
            PhotoDiscard.captureToResume(Nav.SCR_MEASURE_AFTER, "a1", "a1", log));
        // and going back to it keeps it open - its photos are not discarded on the way
        int open = PhotoDiscard.captureAfter(Nav.SCR_LOG_READING, Nav.SCR_CONNECT);
        assertFalse(PhotoDiscard.leavesCapture(open,
            PhotoDiscard.captureToResume(open, "m1", "m1", log)));
    }

    @Test void aCaptureThatCannotBeResumedGoesToToday() {
        Model.MeasLog log = new Model.MeasLog();
        assertEquals(PhotoDiscard.NO_CAPTURE,
            PhotoDiscard.captureToResume(PhotoDiscard.NO_CAPTURE, "m1", "m1", log),
            "opened from no capture (Today, Settings): Today, as ever");
        assertEquals(PhotoDiscard.NO_CAPTURE,
            PhotoDiscard.captureToResume(Nav.SCR_TODAY, "m1", "m1", log));
        assertEquals(PhotoDiscard.NO_CAPTURE,
            PhotoDiscard.captureToResume(Nav.SCR_LOG_READING, "m1", "m2", log),
            "its id changed: another reading now, not the one left");
        assertEquals(PhotoDiscard.NO_CAPTURE,
            PhotoDiscard.captureToResume(Nav.SCR_LOG_READING, null, "m1", log));
        assertEquals(PhotoDiscard.NO_CAPTURE,
            PhotoDiscard.captureToResume(Nav.SCR_LOG_READING, "m1", null, log));

        log.all.add(reading("m1", DIR + "reading-m1-front.jpg"));
        assertEquals(PhotoDiscard.NO_CAPTURE,
            PhotoDiscard.captureToResume(Nav.SCR_LOG_READING, "m1", "m1", log),
            "it was saved: nothing to resume");
        Model.MeasLog batch = new Model.MeasLog();
        batch.all.add(reading("m7-m3", DIR + "reading-m7-front.jpg"));   // an at-rest row
        assertEquals(PhotoDiscard.NO_CAPTURE,
            PhotoDiscard.captureToResume(Nav.SCR_LOG_READING, "m7", "m7", batch),
            "saved as a batch of at-rest rows (<id>-m<method>): saved too");
        Model.MeasLog near = new Model.MeasLog();
        near.all.add(reading("m70", DIR + "reading-m70-front.jpg"));
        assertEquals(Nav.SCR_LOG_READING,
            PhotoDiscard.captureToResume(Nav.SCR_LOG_READING, "m7", "m7", near),
            "m70 is not m7's: ids are compared whole");
    }
}
