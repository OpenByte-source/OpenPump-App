package org.openpump;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * Minimal ContentProvider that hands the system camera a WRITABLE descriptor for exactly
 * one scratch file — and nothing else. Sibling of {@link LogProvider} (which serves log
 * READS); the containment rules and the executable assertions live in {@link CapturePaths},
 * the same pure-class split LogProvider/LogPaths uses.
 *
 * MediaStore.ACTION_IMAGE_CAPTURE's EXTRA_OUTPUT needs a {@code content://} URI the camera
 * app can write to; a {@code file://} URI throws FileUriExposedException on API 24+, and
 * this Gradle-free build has no AndroidX FileProvider. Declared exported="false",
 * grantUriPermissions="true": the camera reaches it solely through the per-capture write
 * grant CameraScreen attaches to the one URI it passes, and CameraScreen additionally
 * grants that URI to each resolving camera package explicitly.
 *
 * The read path (LogProvider) can never reach a photo; this write path can never reach a
 * log or a committed photo — the two authorities and the two containment rules are
 * deliberately separate so neither promise leans on the other.
 */
public class CaptureProvider extends ContentProvider {

    public static final String AUTHORITY = CapturePaths.AUTHORITY;

    @Override
    public boolean onCreate() { return true; }

    /**
     * The single writable scratch file inside app-private external Pictures, or null.
     *
     * Unlike {@link LogProvider}'s resolve this does NOT require the file to already
     * exist — openFile in write mode is what creates it — but it applies the same two
     * independent guards, in the same order (name first, so a crafted segment never
     * reaches the filesystem): the name must be the bare staging name
     * ({@link CapturePaths#isCaptureName}), and the resolved canonical file must be a
     * DIRECT child of the Pictures dir ({@link LogPaths#isDirectChildOf}).
     */
    private File resolve(Uri uri) {
        if (uri == null) return null;
        String name = uri.getLastPathSegment();          // DECODED — see LogPaths' class doc
        if (!CapturePaths.isCaptureName(name)) return null;
        Context ctx = getContext();
        if (ctx == null) return null;
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir == null) return null;
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, name);
        try {
            if (!LogPaths.isDirectChildOf(dir.getCanonicalPath(), f.getCanonicalPath(),
                                          File.separatorChar)) return null;
        } catch (Exception e) {
            return null;
        }
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File f = resolve(uri);
        if (f == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
        String m = (mode == null || mode.length() == 0) ? "w" : mode;
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(m));
    }

    @Override
    public String getType(Uri uri) { return resolve(uri) == null ? null : "image/jpeg"; }

    // The provider exists only to hand the camera a writable fd — it is not a queryable
    // collection and nothing may insert/delete/update through it, exactly as LogProvider's
    // mutators are inert.
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
