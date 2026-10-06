package org.openpump;

import java.io.File;
import java.io.FileOutputStream;

/**
 * THE ONE WAY A FILE OF USER DATA IS REPLACED: written to a scratch file, FORCED TO THE
 * DISK, and only then renamed over the real name (0.10, durable save - the owner's call).
 *
 * WHY THE FORCE. A rename on the same filesystem is atomic, so a reader never sees half a
 * file. But close() only hands the bytes to the kernel: a battery cut or a crash of the
 * phone a moment after the rename could leave the NEW name pointing at bytes that never
 * reached flash - an empty or truncated model.json on some filesystems, which the next
 * launch reads as unreadable and sets aside. getFD().sync() before the rename means the
 * name is only ever published over bytes that are already on the disk: a power cut loses
 * at most the save that was in progress, never the one before it.
 *
 * The cost is one fsync per save, a few milliseconds on flash. It was once judged too dear
 * for the UI thread; the owner chose durability over those milliseconds.
 *
 * What it does not do: sync the DIRECTORY after the rename (not reachable from plain Java
 * on Android). The rename itself may then be lost to a power cut, which leaves the previous
 * whole file in place - the same outcome as a lost save, never a damaged one.
 *
 * Pure java.io, so AtomicFileTest holds it on the desktop; WiringCheck invariant 220 holds
 * every user-data writer in Store to coming through here.
 */
public final class AtomicFile {
    private AtomicFile() { }

    /** The bytes are on the disk under the real name. */
    public static final int REPLACED = 0;
    /** The filesystem refused the bytes (full, I/O error): the scratch file is gone and the
     *  real file is exactly as it was. The caller must NOT fall back to an in-place write -
     *  that truncates the good file for bytes that will not be accepted either. */
    public static final int WRITE_REFUSED = 1;
    /** The bytes are written and synced in the scratch file, but the rename was refused. The
     *  scratch file is left for the caller, whose last resort is an in-place write
     *  ({@link #writeInPlace}); it deletes the scratch file afterwards. */
    public static final int RENAME_REFUSED = 2;

    /** Writes `data` to `tmp`, forces it to the disk, then renames it over `dst`. */
    public static int replace(File tmp, File dst, byte[] data) {
        if (!writeSynced(tmp, data)) {
            tmp.delete();
            return WRITE_REFUSED;
        }
        if (tmp.renameTo(dst)) return REPLACED;
        // Never "delete the old name, then rename": between the two there would be no file
        // at the real name, and a load that finds none starts a fresh install.
        return RENAME_REFUSED;
    }

    /**
     * WHAT A STORE WRITE DOES: {@link #replace}, and after a refused RENAME only - the bytes
     * already accepted once - the in-place write, so a save is never simply dropped. A
     * refused WRITE keeps the good file untouched. Returns what {@link #replace} returned.
     */
    public static int save(File tmp, File dst, byte[] data) {
        int how = replace(tmp, dst, data);
        if (how == RENAME_REFUSED) {
            // Last resort - the pre-atomic path.
            writeInPlace(dst, data);
            tmp.delete();
        }
        return how;
    }

    /** The last resort after {@link #RENAME_REFUSED}: `data` straight into `dst`, synced.
     *  Not atomic - only ever used when the bytes were already accepted once. */
    public static boolean writeInPlace(File dst, byte[] data) {
        return writeSynced(dst, data);
    }

    /** Writes the whole of `data` to `f` and forces it to the disk before closing. Also the
     *  restore's staging write, whose photos replace ones already deleted. */
    public static boolean writeSynced(File f, byte[] data) {
        FileOutputStream o = null;
        try {
            o = new FileOutputStream(f);
            o.write(data);
            o.flush();
            // THE POINT OF THIS CLASS: the bytes are on the disk before anyone may rename
            // them into place.
            o.getFD().sync();
            o.close();
            o = null;
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (o != null) try { o.close(); } catch (Exception ignored) { }
        }
    }
}
