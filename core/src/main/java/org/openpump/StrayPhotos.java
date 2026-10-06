package org.openpump;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * STRAY PHOTOS ARE SWEPT - SAFELY (0.10, the owner's call).
 *
 * A photo is written to reading-&lt;id&gt;-&lt;view&gt;.jpg the moment it is taken, before its
 * reading is saved. Leaving a capture deletes what it made (PhotoDiscard), but a capture that
 * never got to leave - the app killed, the phone restarted, a crash - left its files with no
 * reading pointing at them, and nothing ever looked at them again. Intimate photos with no
 * way to see or delete them in the app.
 *
 * So once per start, after the model has loaded, the photo folder is swept. A file is
 * deleted ONLY when every one of these holds:
 *   - its name is exactly a capture's: reading-&lt;id&gt;-front|side|top.jpg (Shot#filename) -
 *     nothing else in the folder (the .nomedia marker, a restore's staging folder, the
 *     camera's scratch file) is ever a candidate;
 *   - NO READING HAS THAT ID - nor is one of an at-rest batch's rows built on it
 *     (&lt;id&gt;-m&lt;method&gt;), which shares the first row's files;
 *   - NO READING'S PHOTO REFERS TO IT, by path or by file name (a restored backup can point
 *     at the same name under another folder);
 *   - it is NOT a capture in progress (its id is not one being captured now);
 *   - it was last written MORE THAN 24 HOURS AGO - so a capture that is still open, or one
 *     whose reading is about to be saved, is never touched; an unknown time (0) or one in
 *     the future counts as new.
 * And not at all when the model holds NO readings: an empty library cannot be told apart
 * from a lost one, and the photos may be all that is left of it. The Activity does not sweep
 * either when the store could not be read (it is then the seeded stand-in).
 */
public final class StrayPhotos {
    private StrayPhotos() { }

    /** How old a stray file must be before it is swept. */
    public static final long MIN_AGE_MS = 24L * 60L * 60L * 1000L;

    /** The views a capture file can be named for (Shot#filename's, lower case). */
    static final String[] VIEWS = { "front", "side", "top" };

    /** A file in the photo folder: its name and when it was last written (ms, 0 unknown). */
    public static final class Entry {
        public final String name;
        public final long lastModified;
        public Entry(String name, long lastModified) {
            this.name = name; this.lastModified = lastModified;
        }
    }

    /** The reading id a capture file is named for, or null when `name` is not exactly
     *  reading-&lt;id&gt;-&lt;view&gt;.jpg with a non-empty id. */
    public static String idOf(String name) {
        if (name == null || !name.startsWith("reading-") || !name.endsWith(".jpg")) return null;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return null;
        String core = name.substring("reading-".length(), name.length() - ".jpg".length());
        for (int i = 0; i < VIEWS.length; i++) {
            String tail = "-" + VIEWS[i];
            if (core.endsWith(tail)) {
                String id = core.substring(0, core.length() - tail.length());
                if (id.length() == 0) return null;
                // Exactly Shot's name for that id and view, or not a capture file at all.
                return name.equals(Shot.filename(id, VIEWS[i])) ? id : null;
            }
        }
        return null;
    }

    /**
     * The names of the files in `files` that may be deleted, by every rule above. `inProgress`
     * holds the ids of captures open now (may be null or empty).
     */
    public static List<String> toDelete(List<Entry> files, Model.MeasLog log,
                                        Collection<String> inProgress, long now) {
        List<String> out = new ArrayList<String>();
        if (files == null || log == null || log.all.isEmpty()) return out;
        Set<String> ids = new HashSet<String>();
        Set<String> used = new HashSet<String>();
        for (int i = 0; i < log.all.size(); i++) {
            Model.Reading r = log.all.get(i);
            if (r == null) continue;
            if (r.id != null && r.id.length() > 0) ids.add(r.id);
            for (int v = 0; v < Compare.PHOTO_VIEWS.length; v++) {
                Model.Reading.Photo p = Compare.photoOf(r, Compare.PHOTO_VIEWS[v]);
                if (p == null || p.path == null || p.path.length() == 0) continue;
                used.add(baseName(p.path));
            }
        }
        for (int i = 0; i < files.size(); i++) {
            Entry f = files.get(i);
            if (f == null) continue;
            String id = idOf(f.name);
            if (id == null) continue;                                  // not a capture file
            if (belongs(id, ids)) continue;                            // its reading exists
            if (used.contains(f.name)) continue;                       // a reading uses it
            if (inProgress != null && inProgress.contains(id)) continue;   // being captured
            if (f.lastModified <= 0 || now - f.lastModified <= MIN_AGE_MS) continue;  // too new
            if (!out.contains(f.name)) out.add(f.name);
        }
        return out;
    }

    /** Whether a reading has `id`, or is an at-rest batch row built on it (id-m&lt;method&gt;). */
    static boolean belongs(String id, Set<String> ids) {
        if (ids.contains(id)) return true;
        String prefix = id + "-m";
        for (String r : ids) if (r.startsWith(prefix)) return true;
        return false;
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
