package org.openpump;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Manual backup / restore (Task 9, N4 — dist/round5-options.html #n4, decision
 * "A — MANUAL EXPORT / IMPORT"): building one zip out of the app's three stores —
 * model.json, asrun.json, and the reading photos under getExternalFilesDir(Pictures) —
 * and reading one back.
 *
 * NO `import android` ANYWHERE IN THIS FILE, deliberately. Every method here takes a
 * plain java.io.File/InputStream/OutputStream/byte[]; the Android-specific half — the
 * SAF Uri from ACTION_CREATE_DOCUMENT/ACTION_OPEN_DOCUMENT, the ContentResolver stream,
 * getFilesDir()/getExternalFilesDir() — lives in SessionActivity, which hands this class
 * the streams and paths it already has. That split means the whole of what actually
 * decides "is this a valid backup" — entry naming, zip-build, zip-read, and content
 * validation, including a real zip round-trip against temp files — runs under test.sh on
 * the desktop JVM, not merely the naming rules a "pure-ish" split would have left
 * testable.
 *
 * RESTORE'S SAFETY RESTS ON ONE RULE: {@link #readAll} only ever reads bytes into
 * memory and never opens, creates or overwrites a real file, and every validation method
 * below ({@link #looksLikeValidBackup}, {@link #looksLikeModelJson},
 * {@link #looksLikeAsrunJson}, {@link #isRestorable}) works purely off those in-memory
 * bytes. SessionActivity's restore path is therefore able to validate a candidate backup
 * COMPLETELY before touching a single file on real storage — the brief's "validate fully
 * before any file is touched" is enforced by this class's own shape, not merely by
 * caller discipline.
 */
public final class Backup {
    private Backup() { }

    public static final String ENTRY_MODEL = "model.json";
    public static final String ENTRY_ASRUN = "asrun.json";
    /** Every photo entry's name is this prefix plus its bare on-disk filename. */
    public static final String PHOTO_DIR = "photos/";

    /* ---------------------------------------------------- which files belong in a backup */

    /**
     * True for a file name that belongs in a backup's photos/ folder: the exact shape
     * {@link Shot#filename} writes (reading-&lt;id&gt;-&lt;front|side|top&gt;.jpg) under
     * getExternalFilesDir(Pictures), and nothing else — not the {@code .nomedia} marker
     * ({@link CapturePaths#NOMEDIA_NAME}), not {@link CapturePaths#STAGING_NAME}
     * (capture-pending.jpg, a throwaway scratch file that may be mid-write when a backup
     * runs), and, via {@link LogPaths#isBareFileName}, nothing carrying a path separator
     * — the same guard {@link CapturePaths}/{@link LogPaths} apply for exactly this
     * hazard on the READ side of a content:// Uri, reused rather than reimplemented, so
     * a zip entry decoded to something like {@code photos/../../evil.jpg} can never be
     * treated as a real photo name on the way back OUT of a zip either (see
     * {@link #looksLikeValidBackup}, which strips {@link #PHOTO_DIR} and runs the
     * remainder through this same check).
     */
    public static boolean isBackupPhotoName(String name) {
        return LogPaths.isBareFileName(name) && name.startsWith("reading-") && name.endsWith(".jpg");
    }

    /**
     * A BACKUP FROM ANOTHER APP ID. A reading's photo is stored as an ABSOLUTE path, and
     * that path contains the app's own id (…/Android/data/&lt;id&gt;/files/Pictures/…). A
     * backup made under a different id — PumpDebug, before the OpenPump rename — restores
     * its photo FILES into this app's Pictures folder while its model.json still points
     * at the old one, so every restored photo would open as missing. Each photo path
     * whose bare file name is a backup photo name is re-pointed at {@code picturesDir};
     * any other path is left exactly as it was. Returns how many paths changed.
     */
    public static int rebasePhotoPaths(Model m, String picturesDir) {
        if (m == null || picturesDir == null || picturesDir.length() == 0) return 0;
        String base = picturesDir;
        while (base.endsWith("/") || base.endsWith("\\")) base = base.substring(0, base.length() - 1);
        int changed = 0;
        for (int i = 0; i < m.measLog.all.size(); i++) {
            Model.Reading r = m.measLog.all.get(i);
            if (r == null) continue;
            changed += rebasePhoto(r.photoFront, base);
            changed += rebasePhoto(r.photoSide, base);
            changed += rebasePhoto(r.photoTop, base);
        }
        return changed;
    }

    private static int rebasePhoto(Model.Reading.Photo p, String base) {
        if (p == null || p.path == null || p.path.length() == 0) return 0;
        int cut = Math.max(p.path.lastIndexOf('/'), p.path.lastIndexOf('\\'));
        String bare = cut < 0 ? p.path : p.path.substring(cut + 1);
        if (!isBackupPhotoName(bare)) return 0;
        String want = base + "/" + bare;
        if (want.equals(p.path)) return 0;
        p.path = want;
        return 1;
    }

    /** The subset of `namesInDir` that belongs in a backup, in the order given — pure,
     *  so SelfTest can assert it directly against a plain String[] without touching a
     *  filesystem. */
    public static List<String> selectBackupPhotoNames(String[] namesInDir) {
        List<String> out = new ArrayList<String>();
        if (namesInDir == null) return out;
        for (int i = 0; i < namesInDir.length; i++)
            if (isBackupPhotoName(namesInDir[i])) out.add(namesInDir[i]);
        return out;
    }

    /* ------------------------------------------------------------------------- build --- */

    /**
     * Writes model.json + asrun.json + every matching photo in `photosDir` into `out` as
     * a zip, and closes `out` whether this returns normally or throws — the caller never
     * has to remember to close it themselves.
     *
     * `modelJson`/`asrunJson` null writes no bytes for that entry rather than throwing;
     * SessionActivity's own writeBackup always supplies both (asrun.json as "{}" when
     * the file does not exist yet — see its own doc), so in practice this app never
     * produces a zip missing either, but build() itself does not need to know that to
     * stay correct: a zip this produced missing an entry fails cleanly at the NEXT
     * restore's {@link #looksLikeValidBackup}, which is a fine place for that to be
     * caught.
     */
    public static void build(OutputStream out, byte[] modelJson, byte[] asrunJson,
                              File photosDir) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(out);
        try {
            writeBytesEntry(zos, ENTRY_MODEL, modelJson);
            writeBytesEntry(zos, ENTRY_ASRUN, asrunJson);
            if (photosDir != null) {
                List<String> names = selectBackupPhotoNames(photosDir.list());
                for (int i = 0; i < names.size(); i++) {
                    String n = names.get(i);
                    writeFileEntry(zos, PHOTO_DIR + n, new File(photosDir, n));
                }
            }
        } finally {
            zos.close();
        }
    }

    private static void writeBytesEntry(ZipOutputStream zos, String name, byte[] data)
            throws IOException {
        if (data == null) return;
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static void writeFileEntry(ZipOutputStream zos, String entryName, File f)
            throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        } finally {
            in.close();
        }
        zos.closeEntry();
    }

    /* -------------------------------------------------------------- validate + read --- */

    /** One entry read out of a candidate backup zip: its name and its full bytes. */
    public static final class Entry {
        public final String name;
        public final byte[] data;
        public Entry(String name, byte[] data) { this.name = name; this.data = data; }
    }

    /** A single entry's uncompressed size is capped here — an oversized or maliciously
     *  crafted entry (a zip bomb: a few KB compressed, gigabytes inflated) cannot exhaust
     *  memory just because the app offered a file picker. 64 MiB is generous for
     *  anything this app ever writes (asrun.json/model.json stay well under a MB even
     *  with years of history; a single captured photo is a few MB at most) and small
     *  enough that filling it many times over still fits under the total cap below. */
    public static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;
    /** The whole zip's total uncompressed bytes, across every entry — a second, wider
     *  cap so a zip with many entries each just under MAX_ENTRY_BYTES cannot add up to
     *  an out-of-memory failure either. 512 MiB is far beyond any real backup this app
     *  produces and still a hard bound. */
    public static final int MAX_TOTAL_BYTES = 512 * 1024 * 1024;
    /** A ceiling on entry COUNT, independent of size — a zip bomb built from many empty
     *  or near-empty entries rather than one huge one. Years of readings, two photos
     *  each, plus the two JSON files, is nowhere near this. */
    public static final int MAX_ENTRIES = 20000;

    /**
     * Reads every entry of `in` fully into memory and returns them — WITHOUT writing
     * anything to a real file. The caller validates the result ({@link #looksLikeValidBackup}
     * / {@link #isRestorable}) before acting on any of it; nothing this method does can
     * itself touch real app storage, which is what lets restore validate a candidate
     * backup fully before any file is touched.
     *
     * Throws IOException when a stream trips one of the size/count ceilings above, or
     * when the zip is corrupt enough for the decoder to say so. A stream that is not a
     * zip at all — or one truncated before its first entry — is NOT an exception:
     * ZipInputStream simply reports no entries, and this hands back an empty list. Both
     * outcomes reach the caller as "not a valid backup", because the caller asks
     * {@link #isRestorable} before acting, and an empty list has no model.json in it.
     */
    public static List<Entry> readAll(InputStream in) throws IOException {
        if (in == null) throw new IOException("no stream to read a backup from");
        List<Entry> out = new ArrayList<Entry>();
        long total = 0;
        ZipInputStream zin = new ZipInputStream(in);
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) { zin.closeEntry(); continue; }
                if (out.size() >= MAX_ENTRIES) throw new IOException("backup has too many entries");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                long entryTotal = 0;
                while ((n = zin.read(buf)) > 0) {
                    entryTotal += n;
                    total += n;
                    if (entryTotal > MAX_ENTRY_BYTES)
                        throw new IOException("backup entry too large: " + e.getName());
                    if (total > MAX_TOTAL_BYTES) throw new IOException("backup too large");
                    bo.write(buf, 0, n);
                }
                out.add(new Entry(e.getName(), bo.toByteArray()));
                zin.closeEntry();
            }
        } finally {
            try { zin.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    /**
     * Pure check over the entry-NAME list only (no bytes touched) — model.json and
     * asrun.json each exactly once, every other entry a bare photo name directly under
     * {@link #PHOTO_DIR} with no path-traversal shape ({@link #isBackupPhotoName},
     * reused verbatim). A zip carrying anything else — a foreign entry, a duplicate, an
     * entry escaping photos/ — is rejected outright: restore never needs to be lenient
     * about what it will treat as its own backup.
     */
    public static boolean looksLikeValidBackup(List<String> entryNames) {
        if (entryNames == null || entryNames.isEmpty()) return false;
        boolean hasModel = false, hasAsrun = false;
        for (int i = 0; i < entryNames.size(); i++) {
            String n = entryNames.get(i);
            if (n == null) return false;
            if (n.equals(ENTRY_MODEL)) {
                if (hasModel) return false;
                hasModel = true;
            } else if (n.equals(ENTRY_ASRUN)) {
                if (hasAsrun) return false;
                hasAsrun = true;
            } else if (n.startsWith(PHOTO_DIR)) {
                if (!isBackupPhotoName(n.substring(PHOTO_DIR.length()))) return false;
            } else {
                return false;
            }
        }
        return hasModel && hasAsrun;
    }

    /**
     * True when `modelJson` is a shape restore should trust — structurally AND, having
     * actually run it through {@code Model#fromJson}, in content.
     *
     * FIX ROUND 1 (review). {@code Model#fromJson} is deliberately lenient — a top-level
     * parse failure quietly returns {@code Model.seed()} rather than signalling an error,
     * so an upgrading phone never loses its library over a transient read glitch — and
     * the first version of this check only confirmed the one field every model.json this
     * app has ever written carries ({@code Model#toJson}'s "ceil", {@code Model.java:4265}).
     * That is not enough: it does not rule out a zip whose model.json has a plausible
     * "ceil" but corrupted "sets"/"routines"/"measLog"/"sessions" content underneath it.
     *
     * WHAT "corrupted" CAN ACTUALLY DO HERE, verified by reading, not assumed: every
     * field {@code Model#fromJson} reads goes through {@code JSONObject#opt*}
     * (confirmed — no {@code .get*}/{@code getJSONObject}/{@code getJSONArray} anywhere
     * in Model.java), which never throws, and every array it walks
     * ({@code for (i = 0; i < a.length(); i++) list.add(X.fromJson(a.optJSONObject(i)))})
     * adds exactly one entry per raw index regardless of whether that index parses
     * cleanly — a malformed element becomes a blank/default object at its own slot, it is
     * never dropped. So once the top-level JSON has parsed, {@code fromJson} cannot reach
     * its own {@code catch (JSONException e) { return seed(); }} through corrupted
     * nested content at all; the ONLY path back into it is the explicit
     * {@code !hasLibraryShape && sets.isEmpty() && routines.isEmpty()} fallback — which
     * fires only when the file has NEITHER a "sets" NOR a "routines" array, a shape
     * {@code Model#toJson} never produces for a successful save (it always writes both,
     * even empty; its own failure path writes literal "{}", which has neither).
     *
     * So the real, closable gap is narrower than "any corruption silently wipes the
     * model" — it is "a model.json missing the array keys entirely still passes a
     * ceil-only check and then silently seeds." This method closes exactly that gap,
     * two ways, neither alone load-bearing:
     *   1. STRUCTURAL — "sets" and "routines" must both be present AND be JSON arrays,
     *      the two things {@code Model#toJson} always writes alongside "ceil".
     *   2. CONTENT — actually calls {@code Model#fromJson}, the exact code restore itself
     *      will use, and compares its re-serialized output against a freshly built
     *      {@code Model.seed()}'s. {@code Model.seed()} is a pure function (no timestamp,
     *      no randomness — hardcoded example sets/routines only), so its
     *      {@code toJson()} is byte-identical on every call; a restore that quietly
     *      produced it is therefore detectable by direct comparison, with no need to
     *      reverse-engineer which internal branch fired. This is the belt to check 1's
     *      braces — it keeps catching a silent seed() even if a future change to
     *      {@code Model#fromJson} ever adds a different way to reach one.
     *
     * optInt's own sentinel default stands in for a plain {@code .has()}, which this
     * project's minimal desktop JSON shim (desktoptest/org/json/JSONObject.java, kept
     * deliberately small — "enough of org.json to round-trip Model on a desktop JVM")
     * does not implement. Integer.MIN_VALUE is never a real ceilKpa: every write clamps
     * it to [7,57] (Model#clampAll).
     */
    public static boolean looksLikeModelJson(byte[] modelJson) {
        if (modelJson == null) return false;
        try {
            String s = new String(modelJson, "UTF-8");
            JSONObject o = new JSONObject(s);
            if (o.optInt("ceil", Integer.MIN_VALUE) == Integer.MIN_VALUE) return false;
            if (o.optJSONArray("sets") == null || o.optJSONArray("routines") == null) return false;
            Model m = Model.fromJson(s);
            return !m.toJson().equals(Model.seed().toJson());
        } catch (Exception e) {
            return false;
        }
    }

    /** True when `asrunJson` parses as a JSON object. asrun.json has no further required
     *  shape — "{}" is exactly what a phone with no recorded runs backs up (Store's own
     *  empty-object default, {@code Store.java:140}) — so this only rules out bytes that
     *  are not valid JSON at all. */
    public static boolean looksLikeAsrunJson(byte[] asrunJson) {
        if (asrunJson == null) return false;
        try {
            new JSONObject(new String(asrunJson, "UTF-8"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * THE ONE GATE RESTORE ASKS. Both the entry-NAME shape and the two JSON entries'
     * CONTENT shape, over data {@link #readAll} already read fully into memory — nothing
     * on real storage is opened, created or overwritten until this returns true. A zip
     * that fails here is refused with no file, old or new, touched at all.
     */
    public static boolean isRestorable(List<Entry> entries) {
        if (entries == null) return false;
        List<String> names = new ArrayList<String>();
        byte[] modelJson = null, asrunJson = null;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            names.add(e.name);
            if (ENTRY_MODEL.equals(e.name)) modelJson = e.data;
            else if (ENTRY_ASRUN.equals(e.name)) asrunJson = e.data;
        }
        if (!looksLikeValidBackup(names)) return false;
        return looksLikeModelJson(modelJson) && looksLikeAsrunJson(asrunJson);
    }

    /** The photo entries only, in the same order `entries` was given. */
    public static List<Entry> photoEntries(List<Entry> entries) {
        List<Entry> out = new ArrayList<Entry>();
        if (entries == null) return out;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            /* THE NAME UNDER photos/ HAS TO BE ONE THIS APP WOULD HAVE WRITTEN.
             *
             * This accepted anything beginning with the prefix, and the restore then
             * wrote each entry to `new File(stageDir, name.substring(prefix))`. An entry
             * called `photos/../../reading-x-front.jpg` therefore resolved OUTSIDE the
             * staging folder — a backup file is chosen from a picker and can come from
             * anyone, so that is a stranger writing where the app can write.
             * isBackupPhotoName refuses a separator, a traversal or any other shape than
             * the reading-*.jpg the camera produces. */
            if (e.name != null && e.name.startsWith(PHOTO_DIR)
                    && isBackupPhotoName(e.name.substring(PHOTO_DIR.length()))) out.add(e);
        }
        return out;
    }

    /** The bytes of the entry named `name`, or null if absent. */
    public static byte[] find(List<Entry> entries, String name) {
        if (entries == null || name == null) return null;
        for (int i = 0; i < entries.size(); i++)
            if (name.equals(entries.get(i).name)) return entries.get(i).data;
        return null;
    }
}
