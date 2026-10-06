package org.openpump;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.File;

/**
 * Reading and writing stored photos so that what comes back is what the user framed.
 *
 * THE PROBLEM THIS SOLVES. Before Task 14 no source in this app mentioned
 * ExifInterface, ORIENTATION, setRotation or postRotate. Every stored photo was decoded
 * with BitmapFactory and drawn as-is, so any file carrying an EXIF orientation tag —
 * which is how essentially every phone camera records "which way up was this" — was
 * displayed rotated a quarter or a half turn in Compare. Comparing a body measurement
 * photo against a sideways copy of the previous one is not a comparison.
 *
 * HOW MUCH OF THAT BITES TODAY, honestly: this app's own capture path is
 * TextureView.getBitmap() followed by Bitmap.compress(JPEG), which writes pixels
 * already the right way up and no EXIF block at all — so its own files decode correctly
 * without any of this. What decodeOriented() buys is (a) correctness for any file that
 * did not come from that path (a restored backup, a file copied in by hand, a future
 * capture path that uses a real still-capture request, which WOULD carry the tag), and
 * (b) the guarantee that adding such a path later cannot silently start showing rotated
 * photos. stampUpright() closes the same gap from the writing end: a file with no tag is
 * relying on every reader defaulting to "upright", where a file tagged NORMAL says so.
 */
public final class Photos {

    private Photos() { }

    /**
     * Drops a {@code .nomedia} marker into the photos directory — see
     * {@link CapturePaths#NOMEDIA_NAME} for why, in full. Short version: the directory is
     * app-specific external storage and is therefore not indexed by MediaStore at all; the
     * marker is the second lock, for gallery apps that scan the filesystem themselves.
     *
     * IDEMPOTENT and silent. An existing marker is left alone (no rewrite, no touch), a
     * missing directory is created, and every failure is swallowed: this runs on startup,
     * and a phone that will not let us create an empty file is not a phone we should refuse
     * to start on. The photos are still unindexed either way.
     */
    public static void ensureNoMedia(File photosDir) {
        if (photosDir == null) return;
        try {
            if (!photosDir.exists() && !photosDir.mkdirs()) return;
            File marker = new File(photosDir, CapturePaths.NOMEDIA_NAME);
            if (marker.exists()) return;
            marker.createNewFile();
        } catch (Exception ignored) {
        } catch (OutOfMemoryError ignored) {
        }
    }

    /** Long-edge budget for a decoded photo. Compare draws two of these into a phone
     *  screen; decoding a 12 MP JPEG at full size to do that is how a photo screen runs
     *  out of memory. */
    private static final int MAX_DECODED_EDGE = 1000;

    /**
     * Long-edge budget for a GRID THUMBNAIL. The gallery draws a whole day's photos, and
     * several days of them, in one scroll: decoding each at MAX_DECODED_EDGE would put a
     * dozen megabyte-class bitmaps on screen to fill tiles roughly a third of the screen
     * wide. 320 px is comfortably above the largest tile any phone in this app's range
     * draws, so a thumbnail is still sharp and costs about a tenth of the memory.
     *
     * It is a CEILING on the decode, not a resize afterwards: decodeSampled halves until
     * the file is under it, so the expensive full-size decode never happens at all.
     */
    private static final int THUMB_EDGE = 320;

    /**
     * Decodes a stored photo and applies its EXIF orientation, so the returned bitmap is
     * upright regardless of how the file describes itself. Downsampled to
     * MAX_DECODED_EDGE on the long edge first, so the rotation works on a small bitmap.
     * Returns null for a missing/unreadable/undecodable file — every caller already has
     * a "no photo" rendering and must keep using it.
     */
    public static Bitmap decodeOriented(String path) {
        return decodeOriented(path, MAX_DECODED_EDGE);
    }

    /**
     * decodeOriented with an explicit long-edge budget — the SAME code path, so a
     * thumbnail is upright by exactly the rule a full-size decode is upright by. A second
     * decoder for small images is how one of the two ends up not reading the EXIF tag.
     * Use {@link #decodeThumb} rather than passing a size at each call site.
     */
    public static Bitmap decodeOriented(String path, int maxEdge) {
        if (path == null || path.length() == 0) return null;
        Bitmap raw = decodeSampled(path, maxEdge <= 0 ? MAX_DECODED_EDGE : maxEdge);
        if (raw == null) return null;
        int orientation = readOrientation(path);
        return applyOrientation(raw, orientation);
    }

    /** A grid thumbnail: the same upright decode, capped at {@link #THUMB_EDGE}. Every
     *  list in the app that draws many photos at once goes through this one, so no list
     *  can accidentally be the one that loads photos at full size. */
    public static Bitmap decodeThumb(String path) {
        return decodeOriented(path, THUMB_EDGE);
    }

    /**
     * THE PRIVACY BLUR'S PORTABLE HALF (Model#privacyBlur): the same image with its detail
     * genuinely removed — decimated to a sixteenth of each edge and scaled back up with
     * filtering.
     *
     * WHY THE PIXELS AND NOT AN OVERLAY. A translucent panel over a photo is still that
     * photo underneath: a screenshot, a screen recording or a long look at a bright display
     * gets through it. Throwing the detail away in the bitmap means there is nothing left
     * to get through to.
     *
     * On API 31+ the ImageView route uses the framework's own RenderEffect instead (no
     * second allocation, done on the render thread) — this is the floor for everything
     * older, and the only route available where the image is drawn onto a Canvas rather
     * than into an ImageView. One copy, here, so the two callers cannot drift into two
     * different strengths of "blurred".
     *
     * Never mutates or recycles its argument: decoded bitmaps are shared between callers.
     * Null in, null out. Each edge floors at one pixel, since a zero-sized scale throws.
     */
    public static Bitmap pixelate(Bitmap b) {
        if (b == null) return null;
        try {
            int w = Math.max(1, b.getWidth() / 16), h = Math.max(1, b.getHeight() / 16);
            Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
            return Bitmap.createScaledBitmap(small, b.getWidth(), b.getHeight(), true);
        } catch (OutOfMemoryError e) {
            // A blurred photo that cannot be built must not fall back to the SHARP one —
            // that would show exactly what the setting was turned on to hide. No image.
            return null;
        }
    }

    private static Bitmap decodeSampled(String path, int maxEdge) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / sample > maxEdge) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            // A photo screen that dies is worse than a photo screen that says "no photo".
            return null;
        }
    }

    /** The file's EXIF orientation tag, or EXIF_NORMAL when there is none or it cannot
     *  be read. "No claim" is treated as upright — see PreviewGeom#exifRotationDeg. */
    static int readOrientation(String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, PreviewGeom.EXIF_NORMAL);
        } catch (Exception e) {
            return PreviewGeom.EXIF_NORMAL;
        }
    }

    /**
     * Applies an EXIF orientation to a decoded bitmap, returning the original object
     * untouched when there is nothing to do (the overwhelmingly common case for this
     * app's own files, so the usual path allocates nothing). The source is recycled only
     * when a genuinely new bitmap replaced it.
     */
    static Bitmap applyOrientation(Bitmap src, int exifOrientation) {
        if (src == null) return null;
        int rotate = PreviewGeom.exifRotationDeg(exifOrientation);
        boolean mirror = PreviewGeom.exifMirrored(exifOrientation);
        if (rotate == 0 && !mirror) return src;
        try {
            /* ROTATE FIRST, THEN MIRROR. exifRotationDeg and exifMirrored are defined
             * against the F-after-R convention, and postScale/postRotate apply in call
             * order - so mirroring first composed R-after-F and rendered TRANSPOSE (tag 5)
             * and TRANSVERSE (tag 7) a half-turn out. The other six tags are unaffected,
             * which is why it survived: those two are the rare ones. */
            Matrix m = new Matrix();
            if (rotate != 0) m.postRotate(rotate);
            if (mirror) m.postScale(-1f, 1f);
            Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (out != null && out != src) src.recycle();
            return out == null ? src : out;
        } catch (Exception e) {
            return src;
        } catch (OutOfMemoryError e) {
            return src;
        }
    }

    /**
     * Records "these pixels are already upright" on a file this app just wrote.
     * Bitmap.compress emits no EXIF block, so without this the file makes no claim at
     * all and every reader is relying on its own default. Best-effort: a photo that
     * saved fine is not worth failing over a metadata write, and decodeOriented()
     * treats an absent tag exactly as it treats this one.
     */
    static void stampUpright(File f) {
        if (f == null || !f.exists()) return;
        try {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                              String.valueOf(PreviewGeom.EXIF_NORMAL));
            exif.saveAttributes();
        } catch (Exception ignored) {
        } catch (OutOfMemoryError ignored) {
        }
    }
}
