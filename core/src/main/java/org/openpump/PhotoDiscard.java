package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * PRIVACY - A DISCARDED CAPTURE LEAVES NO PHOTO BEHIND (point 19 follow-up).
 *
 * The photos in this app are intimate. A capture screen (the session baseline, the after
 * screen, Log a reading) writes each photo to reading-&lt;id&gt;-&lt;view&gt;.jpg the moment it is
 * taken, before anything is saved. When the capture was then left without saving - the hold
 * given up, Cancel, Skip, Back, a tab, the hold's limit or the notification's release and
 * then leaving - its photo buffer was simply forgotten, and the files stayed on the phone
 * with no reading pointing at them. A person who discards a capture expects its photos gone.
 *
 * WHAT MAY BE DELETED, and nothing else. This is not a sweep of "orphaned" files: a bug in
 * a reference must never be able to delete a photo a saved reading uses. (The start-up sweep
 * for captures that never got to leave - a killed app - is StrayPhotos, with its own and
 * stricter rules: no reading with the id, no reference, over 24 hours old.) A file is
 * deleted only when BOTH are true:
 *   - THIS CAPTURE MADE IT: it is in this capture's photo buffer, under the name the camera
 *     gives this capture's own reserved id and that view (Shot#filename) - so a path that
 *     came from anywhere else is never touched;
 *   - NO SAVED READING USES IT: not the reading it was taken for (a capture that was saved
 *     keeps its files, of course), and not any other - a photo shared by several readings
 *     from one sitting (E3) is kept while any of them refers to it (Gallery#pathInUse).
 *
 * The "Remove these photos" Undo keeps its own copy of the buffer for five seconds and
 * applies the same rule to it once the window closes.
 */
public final class PhotoDiscard {
    private PhotoDiscard() { }

    /**
     * The files of a capture being discarded that may be deleted: each photo in `shot` that
     * this capture (`captureId`) made and that no reading in `log` refers to. Empty for an
     * empty buffer or an unknown id - never a guess.
     */
    public static List<String> filesToDelete(Shot shot, String captureId, Model.MeasLog log) {
        List<String> out = new ArrayList<String>();
        if (shot == null || captureId == null || captureId.length() == 0) return out;
        for (int i = 0; i < Shot.VIEWS.length; i++) {
            String view = Shot.VIEWS[i];
            String path = shot.pathFor(view);
            if (path == null || path.length() == 0) continue;
            if (!madeBy(path, captureId, view)) continue;          // not this capture's file
            if (Gallery.pathInUse(log, path)) continue;            // a saved reading uses it
            if (!out.contains(path)) out.add(path);
        }
        return out;
    }

    /** Whether `path` is the file the camera writes for `captureId` and `view` - the name,
     *  compared whole, so reading-m1-front.jpg is never read as reading-m12-front.jpg's. */
    public static boolean madeBy(String path, String captureId, String view) {
        if (path == null || captureId == null || view == null) return false;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.equals(Shot.filename(captureId, view));
    }

    /** No capture is open (see {@link #captureAfter}). Never a screen id. */
    public static final int NO_CAPTURE = -1;

    /** The three capture screens - the ones whose photo buffer is a capture's. */
    public static boolean isCapture(int screen) {
        return screen == Nav.SCR_BASELINE || screen == Nav.SCR_LOG_READING
            || screen == Nav.SCR_MEASURE_AFTER;
    }

    /**
     * The screens a capture stays open behind: the hold screen ("Hold again" counts on it and
     * hands back to the same capture) and the connect screen (the pump chip, which the log
     * door suggests while disconnected; every exit from it hands back to the capture while
     * {@link #captureToResume} says it can be resumed). Going there is not leaving the
     * capture; leaving THEM for anywhere but the capture is - the connect screen does that
     * only when the capture can no longer be resumed, and then its photos go.
     */
    public static boolean isDetour(int screen) {
        return screen == Nav.SCR_HOLD || screen == Nav.SCR_CONNECT;
    }

    /**
     * Whether moving to screen `to` leaves the capture open on `open` (a capture screen, or
     * {@link #NO_CAPTURE}): it does unless `to` is that same capture (a re-render, or a detour
     * handing back) or a detour. Every other way out - Cancel, Give up (on the capture or on
     * the hold screen), Skip, Save and on, Back, a tab, the connect screen leaving to Today
     * because the capture could not be resumed - is.
     *
     * A capture that was SAVED leaves this way too; its files are kept because the saved
     * reading uses them ({@link #filesToDelete}).
     */
    public static boolean leavesCapture(int open, int to) {
        if (!isCapture(open) || open == to || isDetour(to)) return false;
        return true;
    }

    /**
     * THE CONNECT SCREEN HANDS BACK (privacy review). The log door says "Not connected to the
     * pump", and the pump chip opens the connect screen over the capture; every way out of it
     * (connected, "Not now", "Done", Back) asks this where to go. The capture screen `open`
     * that it was opened over - to be redrawn as it was left, photos and numbers intact - when
     * that capture can still be resumed: it is a capture screen, its reading id is still the
     * one it had when the connect screen opened (`idAtOpen`), and no saved reading carries
     * that id (its own, or an at-rest row's `<id>-m<method>`). Otherwise {@link #NO_CAPTURE}:
     * the screen goes to Today, as before, and the capture is discarded on the way.
     */
    public static int captureToResume(int open, String idAtOpen, String idNow,
                                      Model.MeasLog log) {
        if (!isCapture(open) || idAtOpen == null || idAtOpen.length() == 0
                || !idAtOpen.equals(idNow)) return NO_CAPTURE;
        if (log != null)
            for (int i = 0; i < log.all.size(); i++) {
                String id = log.all.get(i).id;
                if (id != null && (id.equals(idNow) || id.startsWith(idNow + "-")))
                    return NO_CAPTURE;                        // it was saved
            }
        return open;
    }

    /** The capture open once the app is on screen `to`, `open` having been open before: a
     *  capture screen is its own; a detour keeps what was open; anything else closes it. */
    public static int captureAfter(int open, int to) {
        if (isCapture(to)) return to;
        if (isDetour(to)) return isCapture(open) ? open : NO_CAPTURE;
        return NO_CAPTURE;
    }
}
