package org.openpump;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import org.json.JSONObject;

/** Model persistence: one JSON file in internal storage. */
public final class Store {

    private static final String FILE = "model.json";
    /** The scratch file the whole model is written to before it replaces {@link #FILE}.
     *  Never read: {@link #load} only ever opens the real name, so a half-written tmp left
     *  behind by a kill is inert rather than a second, competing store. */
    private static final String TMP  = "model.json.tmp";

    private Store() { }

    /**
     * TRUE when the last {@link #load} found a model.json it could NOT read or parse, as
     * opposed to not finding one at all. While it is set, {@link #save} refuses to write:
     * see the doc on load() for why that distinction is the whole point.
     */
    private static boolean loadFailed = false;
    /** True once this process has found an unreadable store, whether or not it was
     *  successfully put aside - see {@link #storeWasUnreadable}. */
    private static boolean quarantined = false;
    /** What went wrong, for the screen that has to explain it. Null unless loadFailed. */
    private static String loadFailWhy = null;

    public static boolean loadFailed()  { return loadFailed; }
    public static String  loadFailWhy() { return loadFailWhy; }

    /**
     * The user has decided what to do about an unreadable store — start fresh, or restore a
     * backup over it — so writing is allowed again. Called by exactly those two paths, both
     * of which lay down a complete model of their own; nothing else may clear this.
     */
    public static void clearLoadFailure() {
        loadFailed = false; loadFailWhy = null; quarantined = false;
    }

    /**
     * DID THIS PROCESS FIND AN UNREADABLE STORE - whatever became of it afterwards.
     *
     * {@link #loadFailed} is now the WRITE latch alone: it comes down as soon as the bad
     * file has been renamed out of the way, because at that moment saving is safe again.
     * The screens asking "should I warn about the data" are asking a different question,
     * and it is this one. Both existing readers go through here so the two cannot drift.
     */
    public static boolean storeWasUnreadable() { return loadFailed || quarantined; }

    /**
     * A FAILED READ IS NOT A FRESH INSTALL, AND THIS IS WHERE THAT USED TO BE LOST.
     *
     * The old body caught every Exception and returned {@link Model#seed} — the same answer
     * it gives a phone that has genuinely never run this app. Since onCreate loads once and
     * {@link #save} fires on essentially every tap, the next interaction wrote that seeded
     * model over the real file. One transient read error — a low-memory kill mid-read, an
     * interrupted I/O, a filesystem hiccup — and every session, reading, routine and
     * setting was gone, with nothing on screen ever having said a word about it.
     *
     * Two things now separate the cases. Whether the file EXISTS and has bytes is asked
     * before anything is read, so "no file" stays an ordinary first run. And the JSON is
     * PARSED here, by this method, before {@link Model#fromJson} is trusted with it —
     * because fromJson catches JSONException itself and returns seed(), so a corrupt but
     * perfectly readable file never reached the old catch at all and was the likeliest way
     * to hit this in practice.
     *
     * When a real file cannot be turned into a model, the app still gets a usable seeded
     * Model to render — refusing to start would help nobody — but {@link #loadFailed} is
     * raised, {@link #save} goes silent, and the file on disk is left exactly as it was for
     * a backup restore or a manual recovery to work from.
     */
    /**
     * THE ORDINARY LOAD - the one a screen does, which can put an unreadable file aside
     * because it is also the one that can TELL somebody it did.
     */
    public static Model load(Context c) {
        return load(c, true);
    }

    /**
     * @param mayQuarantine whether this caller can WARN. Only a screen can.
     *
     * THE WARNING WAS BEING SPENT BY A BACKGROUND WAKE-UP. A widget refresh and a reminder
     * alarm both call load, in the same process, at times nobody is looking. On a corrupt
     * file whichever ran first renamed it aside - and the next load, the one the Activity
     * does, then found NO FILE AT ALL. No file is an ordinary first run: seed, loadFailed
     * false, saving re-enabled. The user opened an app that looked factory-new, was told
     * nothing, and their library was sitting under a name they had no reason to look for.
     *
     * A caller that cannot warn now leaves the file exactly where it is. It still raises
     * loadFailed, so nothing in this process writes over it either; the next screen load
     * finds the same unreadable file, puts it aside, and says so.
     */
    public static Model load(Context c, boolean mayQuarantine) {
        boolean existed = false;
        try {
            File f = c.getFileStreamPath(FILE);
            existed = f != null && f.exists() && f.length() > 0;
        } catch (Exception ignored) { }
        try {
            InputStream in = c.openFileInput(FILE);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String json = new String(bo.toByteArray(), "UTF-8");
            // Parse it HERE. fromJson swallows JSONException and hands back seed(), so
            // asking it would tell us nothing about whether the file was intelligible.
            new JSONObject(json);
            Model m = Model.fromJson(json);
            loadFailed = false;
            loadFailWhy = null;
            return m;
        } catch (Exception e) {
            if (existed) {
                loadFailed = true;
                loadFailWhy = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
                /* PASS D - THE UNREADABLE FILE IS PUT ASIDE, NOT LEFT WHERE THE NEXT SAVE
                 * WILL LAND ON IT.
                 *
                 * A file that would not parse is handed back as seed(), and the user is
                 * warned - but the warning was the ONLY thing standing between them and
                 * losing it. Tap "Not now", then toggle any setting or open any editor, and
                 * save() atomically replaces the unreadable file with a fresh seed. The one
                 * copy of a library, a year of sessions and every reading, gone - not to the
                 * corruption, but to the recovery.
                 *
                 * It may well have been recoverable. A truncated write loses the tail; a
                 * flipped byte loses one field. Neither is worth destroying by hand, and
                 * neither can be examined once it is overwritten.
                 *
                 * So it is RENAMED rather than deleted or protected: saving still works, the
                 * app stays usable, and the bytes survive under a name nothing writes to.
                 * Best-effort throughout - a rename that fails must not stop the app opening,
                 * which is the one thing worse than losing the file. */
                /* AND THE WRITE LATCH ONLY STAYS UP WHILE THERE IS SOMETHING TO PROTECT.
                 *
                 * The rename is the protection: once it succeeds nothing corrupt is at
                 * model.json and, as the note above says in as many words, "saving still
                 * works". The latch was raised anyway and never lowered, so after the
                 * dialog - whose own words promise a new library ALONGSIDE the old one -
                 * every save in the process returned without writing. A run, a reading, an
                 * edit: gone at the next launch, silently.
                 *
                 * It stays up when the rename was REFUSED, which is the case it was written
                 * for: the unreadable bytes are still at model.json and a save would land
                 * on them. `quarantined` remembers that this process found a bad store even
                 * once, so the warning survives the lowering of the write latch. */
                boolean asideNow = mayQuarantine && quarantine(c);
                if (asideNow) quarantined = true;
                loadFailed = !asideNow;
                return Model.seed();
            }
            loadFailed = false;
            loadFailWhy = null;
            return Model.seed();
        }
    }

    /** The name an unreadable model is put aside under. A constant because
     *  {@link #deleteAll} has to be able to find these again - an erase that leaves a whole
     *  library on disk is the one thing "erase everything" may not do. */
    private static final String QUARANTINE_PREFIX = FILE + ".unreadable-";

    /** Where an unreadable model is put so that neither a save nor a person can lose it by
     *  accident. Timestamped, so a second failure never overwrites the first one's evidence.
     *  Returns true when nothing corrupt is left at {@link #FILE} - see load(). */
    private static boolean quarantine(Context c) {
        try {
            File bad = c.getFileStreamPath(FILE);
            // Nothing there to protect is the same outcome as a successful rename.
            if (bad == null || !bad.exists() || bad.length() <= 0) return true;
            File aside = new File(bad.getParentFile(),
                QUARANTINE_PREFIX + System.currentTimeMillis());
            if (bad.renameTo(aside)) {
                android.util.Log.w("PumpStore", "unreadable model put aside as " + aside.getName());
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /**
     * Writes the whole model ATOMICALLY: the JSON goes to model.json.tmp, the stream is
     * closed, and only then does a rename replace model.json.
     *
     * WHAT THIS GUARANTEES, EXACTLY: that no reader — this app on its next launch
     * included — ever sees a HALF-WRITTEN model.json, AND (0.10, durable save) that the
     * name is never published over bytes still sitting in the kernel's page cache: the tmp
     * file is forced to the disk (getFD().sync(), in {@link AtomicFile#replace}) before the
     * rename. Before 0.10 there was no sync, on the grounds that an fsync on the UI thread
     * costs a few milliseconds per tap; the owner chose the milliseconds over the risk that
     * a battery cut just after a rename leaves model.json pointing at bytes that never
     * reached flash. A power cut can now lose at most the save in progress.
     *
     * WHY. The old version opened model.json itself with MODE_PRIVATE, which TRUNCATES
     * the file, and then wrote. Between those two moments the user's entire library —
     * every set, every routine, the whole session history — is a zero-length file, and
     * this method is called on essentially every tap. A process death, a battery cut or a
     * low-memory kill inside that window left model.json empty; {@link Model#fromJson}
     * reads "" as a parse failure and hands back {@link Model#seed}, so the next launch
     * silently presented a FRESH INSTALL — five example sets, three example routines, the
     * safety ceiling back at 40 kPa and the display unit back to inHg. Nothing about that
     * failure is visible as a failure; it looks like the app.
     *
     * rename(2) on the same filesystem is atomic and replaces the destination, so a reader
     * at any instant sees either the whole previous file or the whole new one, never a
     * truncated one. getFilesDir() is the same directory openFileOutput writes into, so
     * tmp and destination cannot land on different filesystems.
     *
     * If the RENAME is refused we fall back to the old in-place write rather than losing
     * the save: a non-atomic write is worse than an atomic one and far better than none.
     *
     * THE FALLBACK EXISTS ONLY FOR A REFUSED RENAME, NEVER FOR A REFUSED WRITE. That
     * distinction is the whole point of the `written` flag below. openFileOutput with
     * MODE_PRIVATE truncates model.json before the first byte lands, so running it after
     * the filesystem has just REFUSED our bytes (ENOSPC, EIO, a quota) destroys the good
     * file to make room for bytes that are not going to be accepted this time either —
     * load() then returns Model.seed() and the user's whole library is gone. When the
     * write fails we therefore drop the tmp and RETURN, keeping the previous model.json
     * exactly as it was: a lost save, not a lost library. A refused rename is a different
     * animal — the bytes were accepted, only the publish step failed, so writing them in
     * place trades atomicity for a save that actually lands.
     */
    public static void save(Context c, Model m) {
        // What the builder was asked before this save is not what it would answer after it
        // (BuildCache): moved first, whatever the write below does.
        m.changed();
        // A name typed a moment ago is user text from now on: the debug log's redactor learns
        // it here, before anything can log it.
        if (c instanceof SessionActivity) ((SessionActivity) c).journalNames(m);
        // THE OTHER HALF OF THE A33 FIX. A save is what turned a failed READ into a lost
        // library: the seeded stand-in went straight back over the real file on the next
        // tap. While the store is known-unreadable nothing is written at all, so whatever
        // is on disk survives for a restore or a manual recovery. Cleared only when the
        // user has chosen what to do about it — see Store#clearLoadFailure.
        if (loadFailed) return;
        /* THE "NOTHING SERIALISABLE" GUARD WAS DEAD CODE, AND WHAT IT WAS GUARDING WAS
         * THE WHOLE FILE.
         *
         * Model#toJson catches JSONException itself and returns the two characters "{}".
         * It never throws, so this catch never fired - and a serialisation failure was
         * written, atomically, over a year of sessions. The empty object is not a model:
         * every real one carries a ceiling, a unit and a routine list, so "{}" can only
         * ever be the sentinel. Refusing to publish it is the guard the comment always
         * claimed to be. */
        String json = m.toJson();
        if (json == null || json.length() < 3 || "{}".equals(json.trim())) return;
        byte[] data;
        try { data = json.getBytes("UTF-8"); }
        catch (Exception e) { return; }          // nothing serialisable — touch nothing

        File dir = c.getFilesDir();
        replaceSynced(new File(dir, TMP), new File(dir, FILE), data);
    }

    /**
     * EVERY USER-DATA FILE IN THIS CLASS IS WRITTEN THROUGH HERE (WiringCheck invariant
     * 220): tmp, forced to the disk, renamed over the real name ({@link AtomicFile}). A
     * refused WRITE keeps the good file and drops the tmp; only a refused RENAME - the bytes
     * already accepted once - falls back to the in-place write, so a save is never simply
     * dropped.
     */
    private static void replaceSynced(File tmp, File dst, byte[] data) {
        AtomicFile.save(tmp, dst, data);
    }

    /* ------------------------------------------------------------ asrun.json
     *
     * THE AS-RUN RECORDINGS live in their own file, not in model.json: a run writes a row
     * on every preset change, and model.json is rewritten whole on essentially every tap —
     * folding a growing per-run log into it would make the biggest file in the app the one
     * written most often. asrun.json is ONE JSON object keyed by String.valueOf(Sess.ts),
     * each value an AsRun#toJson. It is parsed once per process into a cache and written
     * back whole (atomically, the same tmp+rename shape as save()) on every change; the
     * cache is the truth between writes, so a save never has to re-read the file it just
     * wrote. Trimmed to 90 days at filing (SessionActivity#fileSession), so it cannot grow
     * without bound. Nothing here reads model.json or is read by it: a missing or corrupt
     * asrun.json costs the "save what you ran" card for old sessions and nothing else.
     */

    private static final String ASRUN_FILE = "asrun.json";
    private static final String ASRUN_TMP  = "asrun.json.tmp";
    /** The parsed file. Null until first touched; an empty object when the file is absent
     *  or unreadable (a corrupt file is superseded by the next write, never appended to). */
    private static org.json.JSONObject asRunCache;

    private static org.json.JSONObject asRunAll(Context c) {
        if (asRunCache != null) return asRunCache;
        org.json.JSONObject all = null;
        try {
            InputStream in = c.openFileInput(ASRUN_FILE);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            all = new org.json.JSONObject(new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            all = null;
        }
        asRunCache = all != null ? all : new org.json.JSONObject();
        return asRunCache;
    }

    /** Writes the cache out atomically — tmp, sync, rename — with save()'s own fallback,
     *  including its rule: the truncating in-place path runs ONLY for a refused RENAME,
     *  never for a refused WRITE. A full disk must cost the newest recording, not the
     *  ninety days of recordings already on disk. */
    private static void writeAsRun(Context c) {
        if (asRunCache == null) return;
        byte[] data;
        try { data = asRunCache.toString().getBytes("UTF-8"); }
        catch (Exception e) { return; }
        File dir = c.getFilesDir();
        replaceSynced(new File(dir, ASRUN_TMP), new File(dir, ASRUN_FILE), data);
    }

    /** Files (or re-files) one run's recording under `sessTs` — the Sess.ts it belongs
     *  to, or the recorder's provisional key while the run is still going. Replaces
     *  whatever was under that key. */
    public static void saveAsRun(Context c, long sessTs, AsRun run) {
        if (run == null) return;
        org.json.JSONObject all = asRunAll(c);
        try { all.put(String.valueOf(sessTs), run.toJson()); }
        catch (Exception e) { return; }
        writeAsRun(c);
    }

    /** The recording filed under `sessTs`, or null when there is none (an old session, a
     *  trimmed one, a run that recorded nothing). */
    public static AsRun loadAsRun(Context c, long sessTs) {
        org.json.JSONObject o = asRunAll(c).optJSONObject(String.valueOf(sessTs));
        return o == null ? null : AsRun.fromJson(o);
    }

    /** Removes one entry — the recorder's provisional key once the run is re-filed under
     *  its Sess.ts. A no-op when absent. */
    public static void deleteAsRun(Context c, long sessTs) {
        org.json.JSONObject all = asRunAll(c);
        String key = String.valueOf(sessTs);
        if (!all.has(key)) return;
        all.remove(key);
        writeAsRun(c);
    }

    /**
     * RESTORE'S OWN ENTRY POINT (Task 9) — replaces the as-run cache WHOLESALE with
     * `all` and writes it out through the same atomic tmp+rename path {@link #writeAsRun}
     * gives every other write in this file, rather than a one-off implementation in
     * SessionActivity that would have to re-earn that same guarantee.
     *
     * Used by nothing but a backup restore: every other caller only ever adds
     * ({@link #saveAsRun}) or removes ({@link #deleteAsRun}) ONE run at a time. `all` is
     * expected to be the exact object a validated backup's asrun.json entry parsed into
     * (Backup#looksLikeAsrunJson already confirmed it parses) — this method does not
     * re-validate its shape, the same trust {@link #deleteAll} places in its caller
     * having already decided erasure is safe.
     */
    public static void restoreAsRun(Context c, org.json.JSONObject all) {
        asRunCache = all != null ? all : new org.json.JSONObject();
        writeAsRun(c);
    }

    /* ------------------------------------------------- assessment traces (tau)
     *
     * THE SAMPLES A TAU WAS COMPUTED FROM, kept so the number can be looked at rather than
     * only read. Tau.compute reduces about 187 frames to one figure and the frames were
     * thrown away, so a refusal - "still-climbing", "low-plateau" - was a verdict whose
     * evidence had already been deleted.
     *
     * A SIDE FILE, exactly like asrun.json, and for the same reason: this is bulky, it is
     * per-session, and model.json is read on every launch and rewritten on every change.
     * Two ends at ~187 samples is about 1.5 kB a session - nothing on its own, half a
     * megabyte inside the hot file after a year of training.
     *
     * DELIBERATELY NOT IN THE BACKUP. Backup#build writes model.json, asrun.json and the
     * photos, and validates exactly those on restore; a third entry changes a format that
     * has its own validation and its own migration story. What a restore would lose is
     * EVIDENCE, never a RECORD: tau itself, its refusal reason, the baseline and the peak
     * all live on Model.Sess inside model.json and come back with it. A trace is how you
     * look at a measurement, not the measurement.
     */
    private static final String TAU_FILE = "tau.json";
    private static final String TAU_TMP  = "tau.json.tmp";
    private static org.json.JSONObject tauCache;

    /**
     * One session's two pulls. Times are MILLISECONDS FROM THE COMMAND INSTANT - the same
     * origin Tau.compute measures from, so a trace cannot disagree with the number computed
     * beside it - and pressures are CENTI-kPa integers, because the frames are integers and
     * a double would store precision the pump never had.
     *
     * Either end may be absent: the before-pull can be skipped and the after-pull refused.
     */
    public static final class TauTrace {
        public int[] beforeMs, beforeCkpa, afterMs, afterCkpa;

        public boolean has(boolean after) {
            int[] t = after ? afterMs : beforeMs;
            return t != null && t.length > 1;
        }

        org.json.JSONObject toJson() throws org.json.JSONException {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("bt", csv(beforeMs));   o.put("bp", csv(beforeCkpa));
            o.put("at", csv(afterMs));    o.put("ap", csv(afterCkpa));
            return o;
        }

        static TauTrace fromJson(org.json.JSONObject o) {
            TauTrace t = new TauTrace();
            t.beforeMs   = ints(o.optString("bt", ""));
            t.beforeCkpa = ints(o.optString("bp", ""));
            t.afterMs    = ints(o.optString("at", ""));
            t.afterCkpa  = ints(o.optString("ap", ""));
            // A pair whose halves disagree in length is not half-usable, it is unreadable:
            // every point needs both a time and a pressure. Dropped as a whole.
            if (!paired(t.beforeMs, t.beforeCkpa)) { t.beforeMs = null; t.beforeCkpa = null; }
            if (!paired(t.afterMs, t.afterCkpa))   { t.afterMs  = null; t.afterCkpa  = null; }
            return t;
        }

        private static boolean paired(int[] a, int[] b) {
            return a != null && b != null && a.length == b.length && a.length > 1;
        }

        /** Comma-separated: 187 integers as a JSONArray of numbers costs several times what
         *  the digits themselves do. Null and empty both write the empty string. */
        private static String csv(int[] v) {
            if (v == null || v.length == 0) return "";
            StringBuilder sb = new StringBuilder(v.length * 4);
            for (int i = 0; i < v.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(v[i]);
            }
            return sb.toString();
        }

        private static int[] ints(String s) {
            if (s == null || s.length() == 0) return null;
            String[] parts = s.split(",");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try { out[i] = Integer.parseInt(parts[i].trim()); }
                catch (Exception e) { return null; }   // one bad figure discards the series
            }
            return out;
        }
    }

    private static org.json.JSONObject tauAll(Context c) {
        if (tauCache != null) return tauCache;
        org.json.JSONObject all = null;
        try {
            InputStream in = c.openFileInput(TAU_FILE);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            all = new org.json.JSONObject(new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            all = null;
        }
        tauCache = all != null ? all : new org.json.JSONObject();
        return tauCache;
    }

    private static void writeTau(Context c) {
        if (tauCache == null) return;
        byte[] data;
        try { data = tauCache.toString().getBytes("UTF-8"); }
        catch (Exception e) { return; }
        File dir = c.getFilesDir();
        // Through the same helper as model.json: it already synced, and it no longer deletes
        // the old file before the rename (a kill between the two lost every trace).
        replaceSynced(new File(dir, TAU_TMP), new File(dir, TAU_FILE), data);
    }

    /** Files one session's traces under `sessTs`, replacing whatever was there. Writing
     *  nothing when both ends are absent keeps the file free of empty entries. */
    public static void saveTau(Context c, long sessTs, TauTrace t) {
        if (t == null) return;
        if (!t.has(false) && !t.has(true)) return;
        org.json.JSONObject all = tauAll(c);
        try { all.put(String.valueOf(sessTs), t.toJson()); }
        catch (Exception e) { return; }
        writeTau(c);
    }

    /** The traces for `sessTs`, or null - an older session, or one whose assessment never
     *  ran. EVERY caller must handle null: most sessions on any existing phone have none. */
    public static TauTrace loadTau(Context c, long sessTs) {
        org.json.JSONObject o = tauAll(c).optJSONObject(String.valueOf(sessTs));
        return o == null ? null : TauTrace.fromJson(o);
    }

    /** Removes one session's traces. A no-op when absent. Called wherever the session is
     *  deleted, beside {@link #deleteAsRun} - evidence does not outlive its run. */
    public static void deleteTau(Context c, long sessTs) {
        org.json.JSONObject all = tauAll(c);
        String key = String.valueOf(sessTs);
        if (!all.has(key)) return;
        all.remove(key);
        writeTau(c);
    }

    /* ------------------------------------------------------------ erase everything
     *
     * THE DEVELOPER SECTION'S "Delete all data…" — every file this class owns, gone,
     * INCLUDING ANY QUARANTINED MODEL. Each of those is a complete library - every session,
     * reading, routine and setting - put aside under its own timestamped name; nothing in
     * the app reads them, so an erase that stepped over them left the whole thing on disk
     * while the dialog said "this cannot be undone".
     *
     * IT DELETES THE APP'S OWN STORAGE AND NOTHING ELSE. The pump is a separate device with
     * its own preset table and its own memory; nothing here reaches it, and the dialog that
     * calls this says so, because "delete all data" is otherwise a fair thing to read as
     * "wipe the pump".
     *
     * THE IN-MEMORY CACHE IS CLEARED TOO, and that is not tidiness — asRunCache is the
     * truth between writes, so a cache left populated over a deleted file would be written
     * straight back out by the next recording and the data would reappear. The caller is
     * responsible for the equivalent on its side: replacing its Model with Model.seed().
     *
     * Best effort by design. A file the OS refuses to remove is reported by the return
     * value rather than thrown, because the caller's next move — reseed and re-render — is
     * the same either way, and a half-completed erase must still leave the app usable.
     */
    public static boolean deleteAll(Context c) {
        File dir = c.getFilesDir();
        boolean ok = true;
        ok &= gone(new File(dir, FILE));
        ok &= gone(new File(dir, TMP));
        ok &= gone(new File(dir, ASRUN_FILE));
        ok &= gone(new File(dir, ASRUN_TMP));
        ok &= gone(new File(dir, TAU_FILE));
        ok &= gone(new File(dir, TAU_TMP));
        // Every quarantined model too - there may be several, one per failure.
        File[] all = dir == null ? null : dir.listFiles();
        if (all != null)
            for (int i = 0; i < all.length; i++)
                if (all[i].getName().startsWith(QUARANTINE_PREFIX)) ok &= gone(all[i]);
        // Drop the parsed recordings, or the next saveAsRun would rewrite the file we
        // just deleted from memory that still holds every row of it.
        asRunCache = null;
        tauCache = null;
        return ok;
    }

    /** True when the file is not there afterwards — including when it never was, which is
     *  the same outcome and must not be reported as a failure. */
    private static boolean gone(File f) {
        return !f.exists() || f.delete();
    }

    /** Drops every entry keyed before `keepAfterTs` (the 90-day trim at filing). Keys that
     *  are not timestamps at all are dropped too — nothing this app writes is one. */
    public static void trimAsRun(Context c, long keepAfterTs) {
        org.json.JSONObject all = asRunAll(c);
        java.util.List<String> drop = new java.util.ArrayList<String>();
        java.util.Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String k = it.next();
            long ts;
            try { ts = Long.parseLong(k); } catch (NumberFormatException e) { ts = Long.MIN_VALUE; }
            if (ts < keepAfterTs) drop.add(k);
        }
        if (drop.isEmpty()) return;
        for (int i = 0; i < drop.size(); i++) all.remove(drop.get(i));
        writeAsRun(c);
    }
}
