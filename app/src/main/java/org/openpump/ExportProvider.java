package org.openpump;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * Serves the files the Progress › Export sheet just wrote — and nothing else. Third
 * sibling of {@link LogProvider} (log READS) and {@link CaptureProvider} (one scratch
 * WRITE); the pure rules live in {@link Export}, the same split those two use with
 * LogPaths/CapturePaths.
 *
 * THE BOUNDARY vs LogProvider, stated precisely because the two providers make OPPOSITE
 * promises. The debug log never contains measurements or photos — LogProvider enforces
 * that where the bytes are served, and it is untouched by this class. THIS provider's
 * files deliberately DO contain measurements: they are the user's own data export,
 * created by an explicit Export tap that states on-screen exactly what the files hold,
 * and shared only through the chooser the user then picks a recipient on. Two separate
 * authorities, two separate directories, two separate containment rules — neither
 * provider can serve the other's files, so the log path can never leak a measurement and
 * the export path can never masquerade as "just a log".
 *
 * WHAT IT SERVES: files sitting DIRECTLY in getFilesDir()/export/ whose name is one of
 * the three stamped shapes the export writes — sessions-&lt;stamp&gt;.csv,
 * readings-&lt;stamp&gt;.csv, report-&lt;stamp&gt;.pdf ({@link Export#mimeFor} — anything
 * else, including any name carrying a separator, resolves to null and is refused).
 * Photos are NEVER served as files here: the per-export photo opt-in (its own confirm
 * dialog) embeds them as pages inside the PDF report, so a photo file path never crosses
 * this boundary at all. The export dir is emptied before every new export and the names
 * are stamped PER RUN — a still-live grant from an earlier share can neither resolve to
 * older bytes nor quietly re-resolve to a later, broader export written under the same
 * name (doExport also revokes the previous names' grants outright).
 *
 * Only reads are supported. exported="false" + grantUriPermissions="true": a recipient
 * reaches a file solely through the read grant attached to the exact Uri the share
 * intent named.
 */
public class ExportProvider extends ContentProvider {

    public static final String AUTHORITY = Export.AUTHORITY;

    @Override
    public boolean onCreate() { return true; }

    /**
     * Resolve a content Uri to one of this app's export files, or null. Same order as
     * LogProvider#resolve, for the same reason: the NAME is validated before any File is
     * built from it (Uri.getLastPathSegment returns the DECODED segment — see LogPaths'
     * class doc for the encoded-slash write-up), and the directory containment is then a
     * second, independent guard.
     */
    private File resolve(Uri uri) {
        if (uri == null) return null;
        String name = uri.getLastPathSegment();          // DECODED — see LogPaths' class doc
        if (Export.mimeFor(name) == null) return null;
        Context ctx = getContext();
        if (ctx == null) return null;
        File dir = new File(ctx.getFilesDir(), Export.DIR_NAME);
        File f = new File(dir, name);
        try {
            if (!LogPaths.isDirectChildOf(dir.getCanonicalPath(), f.getCanonicalPath(),
                                          File.separatorChar)) return null;
        } catch (Exception e) {
            return null;
        }
        return f.isFile() ? f : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File f = resolve(uri);
        if (f == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Display name and size, exactly as LogProvider answers them, so a receiving app
     *  shows "sessions-20260819-143000.csv" rather than a generic blob. */
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

    /** text/csv or application/pdf for a file this provider will actually serve; null —
     *  the documented "no type known" answer — for everything it refuses, exactly as
     *  LogProvider answers only for what it serves. */
    @Override public String getType(Uri uri) {
        File f = resolve(uri);
        return f == null ? null : Export.mimeFor(f.getName());
    }

    // Read-only, like LogProvider: the export sheet writes the files itself, directly;
    // nothing may insert/delete/update through this boundary.
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
