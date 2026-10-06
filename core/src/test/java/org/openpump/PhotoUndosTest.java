package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * "REMOVE THESE PHOTOS" AND ITS UNDO NEVER DELETE A PHOTO THE UNDO PUT BACK (PhotoUndos).
 *
 * The review's sequence: Remove at t=0 (clean-up due at 6.5 s), Undo at t=1, Remove again at
 * t=3 (its Undo open until 8 s). The FIRST removal's clean-up then ran at 6.5 s, found no
 * reading and an empty buffer, and deleted both files - so the second Undo at t=7 put back
 * paths to deleted files and Save filed a reading with its photos missing.
 *
 * Here the phone's storage is a set of paths: a clean-up's list is "deleted" from it, and the
 * photos a saved reading refers to must still be in it at the end.
 */
class PhotoUndosTest {

    private static final String DIR = "/storage/emulated/0/Android/data/org.openpump/files/Pictures/";
    private static final String K_FRONT = DIR + "reading-mK-front.jpg";
    private static final String K_SIDE = DIR + "reading-mK-side.jpg";

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

    private static Set<String> disk(Shot s) {
        Set<String> d = new LinkedHashSet<String>();
        for (int i = 0; i < Shot.VIEWS.length; i++)
            if (s.pathFor(Shot.VIEWS[i]) != null) d.add(s.pathFor(Shot.VIEWS[i]));
        return d;
    }

    /** The at-rest save, reduced to what matters here: a reading that uses the live photos. */
    private static void save(Model.MeasLog log, Shot live, String id) {
        Model.Reading r = new Model.Reading();
        r.id = id; r.ts = 1000L; r.method = Model.Reading.METHOD_BPEL; r.len = 15;
        if (live.pathFor(Shot.FRONT) != null) {
            r.photoFront = new Model.Reading.Photo();
            r.photoFront.path = live.pathFor(Shot.FRONT);
        }
        if (live.pathFor(Shot.SIDE) != null) {
            r.photoSide = new Model.Reading.Photo();
            r.photoSide.path = live.pathFor(Shot.SIDE);
        }
        r.photo = live.tookAny();
        log.all.add(r);
    }

    /** Remove, as RemoveAtRestPhotosTap does it: the copy is kept for the Undo, then the
     *  buffer is emptied. */
    private static PhotoUndos.Removal remove(PhotoUndos u, Shot live, String id) {
        PhotoUndos.Removal r = u.removed(live.copy(), id);
        live.reset();
        return r;
    }

    /** Undo, as UndoRemoveAtRestPhotosTap does it: the removal is closed (its clean-up
     *  cancelled) and the copy goes back. */
    private static void undo(PhotoUndos u, PhotoUndos.Removal r, Shot live) {
        assertTrue(u.isOpen(r), "an Undo only while its removal is open");
        u.undone(r);
        live.copyFrom(r.kept());
    }

    @Test void theReviewSequenceKeepsThePhotosTheSecondUndoPutBack() {
        Shot live = captured("mK", Shot.FRONT, Shot.SIDE);
        Set<String> disk = disk(live);
        Model.MeasLog log = new Model.MeasLog();
        PhotoUndos u = new PhotoUndos();

        PhotoUndos.Removal first = remove(u, live, "mK");          // t=0
        undo(u, first, live);                                      // t=1
        PhotoUndos.Removal second = remove(u, live, "mK");         // t=3
        // t=6.5 - the FIRST removal's clean-up. The app cancels it at the Undo; were it to
        // run anyway, it must delete nothing.
        disk.removeAll(u.cleanUp(first, live, log));
        assertEquals(new LinkedHashSet<String>(Arrays.asList(K_FRONT, K_SIDE)), disk,
            "the first clean-up must not delete what the second Undo can still put back");
        undo(u, second, live);                                     // t=7
        disk.removeAll(u.cleanUp(second, live, log));              // t=9.5 (cancelled too)
        save(log, live, "mK");
        assertTrue(disk.contains(live.pathFor(Shot.FRONT)) && disk.contains(live.pathFor(Shot.SIDE)),
            "the saved reading's photos are on the phone");
    }

    @Test void removeUndoRemoveUndoSaveKeepsEverything() {
        Shot live = captured("mK", Shot.FRONT);
        Set<String> disk = disk(live);
        Model.MeasLog log = new Model.MeasLog();
        PhotoUndos u = new PhotoUndos();
        PhotoUndos.Removal a = remove(u, live, "mK");
        undo(u, a, live);
        PhotoUndos.Removal b = remove(u, live, "mK");
        undo(u, b, live);
        save(log, live, "mK");
        disk.removeAll(u.cleanUp(a, live, log));
        disk.removeAll(u.cleanUp(b, live, log));
        assertEquals(Collections.singleton(K_FRONT), disk);
        assertFalse(u.holds(K_FRONT), "no removal is left open");
    }

    @Test void removeUndoRemoveThenTheWindowClosesDeletesThem() {
        Shot live = captured("mK", Shot.FRONT, Shot.SIDE);
        Model.MeasLog log = new Model.MeasLog();
        PhotoUndos u = new PhotoUndos();
        PhotoUndos.Removal a = remove(u, live, "mK");
        undo(u, a, live);
        PhotoUndos.Removal b = remove(u, live, "mK");
        assertTrue(u.cleanUp(a, live, log).isEmpty(), "the undone removal cleans nothing");
        assertEquals(Arrays.asList(K_FRONT, K_SIDE), u.cleanUp(b, live, log),
            "the removal that was not undone deletes its photos once its window closes");
        assertFalse(u.isOpen(b));
    }

    @Test void aCleanUpSkipsWhatAnotherOpenUndoHolds() {
        // The first removal's Undo is used but, say, its cancel was lost: the first clean-up
        // still runs while the second removal's Undo is open. It must leave the files alone.
        Shot live = captured("mK", Shot.FRONT);
        Model.MeasLog log = new Model.MeasLog();
        PhotoUndos u = new PhotoUndos();
        PhotoUndos.Removal a = u.removed(live.copy(), "mK");
        live.reset();
        live.copyFrom(a.kept());                    // an Undo that did not close `a`
        PhotoUndos.Removal b = remove(u, live, "mK");
        assertTrue(u.holds(K_FRONT));
        assertTrue(u.cleanUp(a, live, log).isEmpty(),
            "a path an open Undo can still put back is never deleted");
        assertTrue(u.isOpen(b));
    }

    @Test void aCleanUpSkipsWhatTheLiveBufferHoldsAgain() {
        // One removal, its Undo used without closing it: the live buffer holds the file again.
        Shot live = captured("mK", Shot.FRONT);
        Model.MeasLog log = new Model.MeasLog();
        PhotoUndos u = new PhotoUndos();
        PhotoUndos.Removal a = u.removed(live.copy(), "mK");
        live.reset();
        live.copyFrom(a.kept());
        assertTrue(u.cleanUp(a, live, log).isEmpty(),
            "a photo back in the buffer is the capture's again, not the removal's to delete");
    }

    @Test void aCleanUpKeepsWhatASavedReadingUses() {
        Shot live = captured("mK", Shot.FRONT);
        Model.MeasLog log = new Model.MeasLog();
        PhotoUndos u = new PhotoUndos();
        PhotoUndos.Removal a = remove(u, live, "mK");
        Shot other = captured("mK", Shot.FRONT);
        save(log, other, "mK");
        assertTrue(u.cleanUp(a, live, log).isEmpty());
    }

    @Test void aRemovalIsCleanedOnceAndNotUndoneAfter() {
        Shot live = captured("mK", Shot.FRONT);
        Model.MeasLog log = new Model.MeasLog();
        PhotoUndos u = new PhotoUndos();
        PhotoUndos.Removal a = remove(u, live, "mK");
        assertTrue(u.holds(K_FRONT));
        List<String> gone = u.cleanUp(a, live, log);
        assertEquals(Collections.singletonList(K_FRONT), gone);
        assertFalse(u.isOpen(a), "an Undo after its clean-up is refused (its files are gone)");
        assertFalse(u.holds(K_FRONT));
        assertTrue(u.cleanUp(a, live, log).isEmpty(), "a second run deletes nothing");
        u.undone(a);                                // harmless once closed
        assertFalse(u.isOpen(a));
    }

    @Test void nullsAreNothing() {
        PhotoUndos u = new PhotoUndos();
        assertTrue(u.cleanUp(null, new Shot(), new Model.MeasLog()).isEmpty());
        assertFalse(u.holds(null));
        assertFalse(u.isOpen(null));
        u.undone(null);
    }
}
