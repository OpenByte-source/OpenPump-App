package org.openpump;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * Minimal ContentProvider that hands out this app's LOG FILES — and nothing else.
 *
 * Why not androidx FileProvider: this APK is built without Gradle and therefore
 * without AndroidX, and the framework has no FileProvider of its own. Sharing a
 * raw file:// Uri is not an option either - that throws FileUriExposedException
 * on API 24+, which is exactly the crash this class exists to avoid.
 *
 * WHAT IT WILL SERVE, and why the rule is this narrow:
 *
 * The earlier rule was "any existing file whose canonical path starts with
 * canonical(getExternalFilesDir(null)) + separator". That rejects escaping UP with ".."
 * but accepts descending IN — and the photo library lives at
 * getExternalFilesDir(DIRECTORY_PICTURES), i.e. INSIDE that base. Since
 * {@link Uri#getLastPathSegment()} returns the DECODED segment, a Uri whose single
 * segment is {@code Pictures%2Freading-1-front.jpg} decoded to a relative path that
 * resolved to a real photo and passed containment. See {@link LogPaths} for the full
 * write-up and for the executable assertions.
 *
 * The app tells the user, in three separate places, that photos and measurements are
 * never part of an exported log. That promise now lives HERE, where the bytes are
 * served, rather than resting entirely on every caller happening to construct a
 * well-behaved Uri:
 *
 *   - the requested name must be a bare log filename ({@link LogPaths#isLogName}), which
 *     rejects any separator and any name that is not one this app's log writers produce;
 *   - the resolved file must be an IMMEDIATE child of one of the two log directories
 *     ({@link LogPaths#isDirectChildOf}), never merely somewhere beneath one.
 *
 * Two log directories, because the app has two log writers: MainActivity (the frozen
 * diagnostic console, in debug builds only) writes pumpdebug-*.txt to
 * getExternalFilesDir(null), and SessionActivity writes session-*.txt to
 * getExternalFilesDir(DIRECTORY_DOCUMENTS). The second was previously unservable — the
 * provider only ever looked in the root — so Settings' "Export debug log" could not have
 * worked against the old resolve() at all.
 *
 * Only reads are supported. The provider is declared exported="false"; a recipient
 * reaches it solely through the read grant attached to the exact Uri the app passed it.
 */
public class LogProvider extends ContentProvider {

    public static final String AUTHORITY = "org.openpump.logs";

    @Override
    public boolean onCreate() { return true; }

    /**
     * Resolve a content Uri to one of this app's log files, or null.
     *
     * Order matters: the NAME is validated before any File is built from it, so a
     * crafted segment never reaches the filesystem in the first place, and the
     * directory check is then a second, independent guard rather than the only one.
     */
    private File resolve(Uri uri) {
        if (uri == null) return null;
        String name = uri.getLastPathSegment();          // DECODED — see the class doc
        if (!LogPaths.isLogName(name)) return null;
        Context ctx = getContext();
        if (ctx == null) return null;
        File[] dirs = new File[]{
            ctx.getExternalFilesDir(null),                              // pumpdebug-*.txt
            ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)    // session-*.txt
        };
        for (int i = 0; i < dirs.length; i++) {
            File dir = dirs[i];
            if (dir == null) continue;
            File f = new File(dir, name);
            try {
                if (!LogPaths.isDirectChildOf(dir.getCanonicalPath(), f.getCanonicalPath(),
                                              File.separatorChar)) continue;
            } catch (Exception e) {
                continue;
            }
            if (f.isFile()) return f;
        }
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File f = resolve(uri);
        if (f == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * WhatsApp and Gmail query for the display name and size before attaching.
     * Returning them makes the attachment show a real filename instead of a
     * generic blob.
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] selArgs, String sort) {
        File f = resolve(uri);
        if (f == null) return null;
        String[] cols = (projection != null) ? projection
            : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i]))      row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i]))         row[i] = f.length();
            else                                                    row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    /**
     * text/plain only for something this provider will actually serve. Returning it
     * unconditionally described every Uri under this authority as a text log — including
     * ones openFile() refuses — which is the wrong answer to give a receiving app and
     * would have mislabelled anything non-log that ever became servable. Null is the
     * documented answer for "no type known for this Uri".
     */
    @Override public String getType(Uri uri) { return resolve(uri) == null ? null : "text/plain"; }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
