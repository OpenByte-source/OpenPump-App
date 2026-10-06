package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * "REMOVE THESE PHOTOS" AND ITS UNDO - THE REMOVALS WHOSE UNDO IS STILL OPEN (privacy review).
 *
 * The log door's "Remove these photos" empties the photo buffer at once and keeps a copy for
 * the Undo; the files themselves are deleted only once the Undo window has closed. The review
 * found the loss this class exists to prevent:
 *
 *   t=0   Remove - capture K's copy kept, its clean-up due at 6.5 s;
 *   t=1   Undo   - the photos go back, the id is K again;
 *   t=3   Remove - the buffer is emptied again, the second Undo open until 8 s;
 *   t=6.5 the FIRST clean-up ran, saw no reading and an empty buffer, and deleted both files;
 *   t=7   Undo   - put back paths to deleted files, and Save filed a reading without its photos.
 *
 * So a removal is OPEN from Remove until its Undo is used ({@link #undone}) or its clean-up
 * runs ({@link #cleanUp}), and:
 *   - an Undo closes its removal - its clean-up then deletes nothing, even if it still runs
 *     (the app also cancels it);
 *   - a clean-up deletes only what the same rule as any discarded capture allows
 *     ({@link PhotoDiscard#filesToDelete}: this capture's own files, used by no saved
 *     reading), minus anything the live buffer holds again, minus anything ANY still-open
 *     removal holds - an Undo that can still put a file back keeps it on the phone;
 *   - a discarded capture skips what an open removal holds too ({@link #holds}).
 *
 * Pure: the app keeps one of these for the activity's life and does the deleting itself.
 */
public final class PhotoUndos {

    /** One "Remove these photos": the buffer copy its Undo puts back, and the id it had. */
    public static final class Removal {
        private final Shot kept;
        private final String keptId;
        Removal(Shot k, String id) { kept = k; keptId = id; }
        /** The copy the Undo puts back. */
        public Shot kept() { return kept; }
        /** The capture id the photos were taken under (their file names). */
        public String keptId() { return keptId; }
    }

    private final List<Removal> open = new ArrayList<Removal>();

    /** Remove: `kept` (a copy - the caller empties its own buffer) is held for the Undo. */
    public Removal removed(Shot kept, String keptId) {
        Removal r = new Removal(kept, keptId);
        open.add(r);
        return r;
    }

    /** Whether `r`'s Undo can still put its photos back - neither undone nor cleaned up. */
    public boolean isOpen(Removal r) {
        return r != null && open.contains(r);
    }

    /** The Undo was used: `r` is closed, so its clean-up deletes nothing. */
    public void undone(Removal r) {
        if (r != null) open.remove(r);
    }

    /** Whether any open removal's Undo could still put `path` back. */
    public boolean holds(String path) {
        if (path == null || path.length() == 0) return false;
        for (int i = 0; i < open.size(); i++)
            for (int v = 0; v < Shot.VIEWS.length; v++)
                if (path.equals(open.get(i).kept.pathFor(Shot.VIEWS[v]))) return true;
        return false;
    }

    /**
     * `r`'s Undo window has closed: closes it and returns the files to delete - nothing when
     * it was undone (or already cleaned up); otherwise the rule's files, minus any the live
     * buffer holds again and any another open removal still holds.
     */
    public List<String> cleanUp(Removal r, Shot live, Model.MeasLog log) {
        List<String> out = new ArrayList<String>();
        if (r == null || !open.remove(r)) return out;
        List<String> gone = PhotoDiscard.filesToDelete(r.kept, r.keptId, log);
        for (int i = 0; i < gone.size(); i++) {
            String path = gone.get(i);
            if (inBuffer(live, path)) continue;      // back in the capture: not ours to delete
            if (holds(path)) continue;               // another open Undo can put it back
            out.add(path);
        }
        return out;
    }

    private static boolean inBuffer(Shot s, String path) {
        if (s == null) return false;
        for (int v = 0; v < Shot.VIEWS.length; v++)
            if (path.equals(s.pathFor(Shot.VIEWS[v]))) return true;
        return false;
    }
}
