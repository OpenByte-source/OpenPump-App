package org.openpump;

/**
 * The camera's GEOMETRY arithmetic, with no android import, so the desktop suite can
 * actually execute it. Everything here is a pure function of numbers that CameraScreen
 * reads off the device (sensor orientation, display rotation, the chosen buffer size,
 * the TextureView's measured bounds) — the same split Shot/CameraGate/CameraIntent
 * already use to get the camera flow's rules under test without a camera.
 *
 * WHY THIS EXISTS AT ALL. Until Task 14 the app called no setTransform anywhere and read
 * neither SENSOR_ORIENTATION nor the display rotation. A TextureView with no transform
 * stretches its buffer to exactly fill the view's bounds, ignoring aspect entirely — so
 * a sensor-native 1280x720 buffer scaled into a tall portrait viewfinder was squashed
 * horizontally and stretched vertically, under a GuideOverlay drawn in an
 * aspect-CORRECT 120x200 design frame. The guide's whole promise is geometric ("sides
 * flush sets the distance, symmetric ends squares you to the lens"), and it was being
 * drawn over a distorted image, so every judgement it invited was wrong. Worse, the
 * capture is TextureView.getBitmap() — the photo IS the viewfinder — so the distortion
 * was baked into the stored file, and comparing two of them compared two distortions.
 *
 * WHAT IS AND IS NOT SETTLED HERE. The arithmetic below is exact and asserted. What it
 * cannot settle is the platform behaviour it is fed: whether the camera stack really
 * delivers preview buffers rotated to the device's NATURAL orientation (which is what
 * makes swapsDimensions() the right question), and whether TextureView.getBitmap()
 * honours the transform matrix set by setTransform(). Both are documented AOSP
 * behaviour and both are stated in docs/BUILD.md as hardware checks, because this APK
 * has never run against a camera.
 */
public final class PreviewGeom {

    private PreviewGeom() { }

    /* ------------------------------------------------------------ EXIF orientation */

    /* The standard TIFF/EXIF orientation values, identical to android.media
     * .ExifInterface's ORIENTATION_* constants. Restated here rather than imported so
     * this class stays in the desktop suite's pure set; CameraScreen/Photos pass the
     * raw int straight through, so there is one place these numbers are interpreted. */
    public static final int EXIF_UNDEFINED       = 0;
    public static final int EXIF_NORMAL          = 1;
    public static final int EXIF_FLIP_HORIZONTAL = 2;
    public static final int EXIF_ROTATE_180      = 3;
    public static final int EXIF_FLIP_VERTICAL   = 4;
    public static final int EXIF_TRANSPOSE       = 5;
    public static final int EXIF_ROTATE_90       = 6;
    public static final int EXIF_TRANSVERSE      = 7;
    public static final int EXIF_ROTATE_270      = 8;

    /**
     * Clockwise degrees a decoded bitmap must be rotated to display upright, for a given
     * EXIF orientation tag. An unknown or absent tag means "no claim", which is treated
     * as upright — the same thing every image viewer does, and the right default for
     * this app's own files, which are written with pixels already upright.
     */
    public static int exifRotationDeg(int exifOrientation) {
        switch (exifOrientation) {
            case EXIF_ROTATE_90:
            case EXIF_TRANSPOSE:   return 90;
            case EXIF_ROTATE_180:
            case EXIF_FLIP_VERTICAL: return 180;
            case EXIF_ROTATE_270:
            case EXIF_TRANSVERSE:  return 270;
            default:               return 0;
        }
    }

    /** True when the EXIF tag also asks for a horizontal mirror. The four mirrored
     *  values are rarely produced by phone cameras but are produced by editors, and a
     *  mirrored body photo compared against an unmirrored one is exactly the kind of
     *  false difference this feature must not invent. */
    public static boolean exifMirrored(int exifOrientation) {
        return exifOrientation == EXIF_FLIP_HORIZONTAL
            || exifOrientation == EXIF_FLIP_VERTICAL
            || exifOrientation == EXIF_TRANSPOSE
            || exifOrientation == EXIF_TRANSVERSE;
    }

    /** True when applying this orientation swaps the image's width and height. */
    public static boolean exifSwapsDimensions(int exifOrientation) {
        int deg = exifRotationDeg(exifOrientation);
        return deg == 90 || deg == 270;
    }

    /* ------------------------------------------------------- preview orientation */

    /**
     * True when the preview buffer's width and height are swapped by the time it is
     * DISPLAYED — i.e. when the sensor's mounting and the display's current rotation
     * are a quarter turn apart.
     *
     * The camera stack delivers preview buffers rotated to the device's NATURAL
     * orientation, so a phone whose sensor is mounted at 90 degrees hands a
     * landscape-shaped buffer that is displayed portrait-shaped. This app is locked to
     * sensorPortrait, so displayRotationDeg is 0 or 180 in practice; the 90/270 cases
     * are handled anyway rather than assumed away, because an orientation lock is a
     * request the platform is allowed to refuse on large screens.
     */
    public static boolean swapsDimensions(int sensorOrientationDeg, int displayRotationDeg) {
        boolean sensorQuarter = norm(sensorOrientationDeg) == 90 || norm(sensorOrientationDeg) == 270;
        boolean displayQuarter = norm(displayRotationDeg) == 90 || norm(displayRotationDeg) == 270;
        return sensorQuarter != displayQuarter;
    }

    /**
     * Clockwise degrees the TextureView's transform must rotate the drawn preview by, to
     * correct for the display being rotated away from the device's natural orientation.
     * 0 at natural; 180 in an inverted dock — the case the portrait lock now permits
     * (sensorPortrait) and plain `portrait` did not, which is why this is not simply
     * hardcoded to 0.
     */
    public static int previewCorrectionDeg(int displayRotationDeg) {
        int d = norm(displayRotationDeg);
        return d == 0 ? 0 : 360 - d;
    }

    private static int norm(int deg) {
        int d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    /* ------------------------------------------------------------- cover scaling */

    /**
     * The x scale factor for the TextureView transform that turns the default
     * fill-the-bounds STRETCH into an aspect-preserving CENTRE-CROP.
     *
     * TextureView with an identity transform maps the buffer onto exactly (viewW,
     * viewH), whatever its real shape. Scaling that result about the view's centre by
     * (coverScaleX, coverScaleY) restores the displayed aspect and crops the overflow —
     * the image fills the viewfinder with no letterboxing and no distortion, which is
     * what the GuideOverlay drawn on top of it assumes.
     *
     * dispW/dispH are the buffer's dimensions AS DISPLAYED (already swapped by the
     * caller when swapsDimensions() says so), not as the camera reports them.
     * Returns 1.0 for any non-positive input, so a not-yet-measured view yields an
     * identity transform rather than a collapsed one.
     */
    public static double coverScaleX(int viewW, int viewH, int dispW, int dispH) {
        if (viewW <= 0 || viewH <= 0 || dispW <= 0 || dispH <= 0) return 1.0;
        double scale = Math.max((double) viewW / dispW, (double) viewH / dispH);
        return dispW * scale / viewW;
    }

    /** The y counterpart of {@link #coverScaleX}. */
    public static double coverScaleY(int viewW, int viewH, int dispW, int dispH) {
        if (viewW <= 0 || viewH <= 0 || dispW <= 0 || dispH <= 0) return 1.0;
        double scale = Math.max((double) viewW / dispW, (double) viewH / dispH);
        return dispH * scale / viewH;
    }

    /* ---------------------------------------------------------- size selection */

    /**
     * Index of the preview size to request: the one whose DISPLAYED aspect is closest to
     * the viewfinder's, among those no larger than maxDim on their long edge, breaking
     * ties towards more pixels.
     *
     * Aspect is compared in log space so "twice as wide as wanted" and "twice as tall as
     * wanted" score equally badly — a plain difference of ratios silently prefers wide
     * mismatches. Choosing on aspect matters because the transform above CROPS: a 16:9
     * buffer in a 3:4 viewfinder throws away a third of the frame's height before the
     * user ever sees it, so the guide is being lined up against a narrower field of view
     * than the sensor actually offered.
     *
     * Falls back to "largest under the cap" when the view has not been measured yet
     * (viewW/viewH non-positive), and to "smallest overall" when nothing is under the
     * cap at all — the previous code left that case on an arbitrary sizes[0].
     * Returns -1 only when there is nothing to choose from.
     */
    public static int bestPreviewIndex(int[] widths, int[] heights, int maxDim,
                                       int viewW, int viewH, boolean swap) {
        if (widths == null || heights == null) return -1;
        int n = Math.min(widths.length, heights.length);
        if (n == 0) return -1;

        int best = -1;
        double bestErr = 0;
        long bestArea = 0;
        for (int i = 0; i < n; i++) {
            int w = widths[i], h = heights[i];
            if (w <= 0 || h <= 0) continue;
            if (Math.max(w, h) > maxDim) continue;
            long area = (long) w * h;
            double err = aspectError(w, h, viewW, viewH, swap);
            if (best < 0 || err < bestErr - 1e-9 || (Math.abs(err - bestErr) <= 1e-9 && area > bestArea)) {
                best = i; bestErr = err; bestArea = area;
            }
        }
        if (best >= 0) return best;

        // Nothing fits the cap. Take the smallest rather than whatever happened to be
        // first: an oversized preview buffer costs memory and bandwidth on a screen that
        // is about to crop it anyway.
        for (int i = 0; i < n; i++) {
            int w = widths[i], h = heights[i];
            if (w <= 0 || h <= 0) continue;
            long area = (long) w * h;
            if (best < 0 || area < bestArea) { best = i; bestArea = area; }
        }
        return best;
    }

    /**
     * Index of the STILL size to capture at: the SMALLEST whose long edge is at least
     * `minLongEdge` (1080 in practice), falling back to the largest available when nothing
     * reaches it.
     *
     * WHY THE SMALLEST THAT QUALIFIES rather than the biggest on offer. A reading photo is
     * downscaled, aligned and stored; the pixels beyond about 1080 on the long edge are
     * never seen and are paid for three times over — a slower shutter, a larger JPEG to
     * write to the scratch file, and a decode that has to fit in this app's heap next to a
     * ghost bitmap. Below 1080 the tube's edges start to soften and the align stage is
     * lining up a blur, so that is the floor rather than a preference.
     *
     * Returns -1 only when there is nothing to choose from, which the caller reads as
     * "this camera offers no JPEG output" and falls back to the system camera app.
     */
    public static int bestCaptureIndex(int[] widths, int[] heights, int minLongEdge) {
        if (widths == null || heights == null) return -1;
        int n = Math.min(widths.length, heights.length);
        int best = -1;
        long bestArea = 0;
        for (int i = 0; i < n; i++) {
            int w = widths[i], h = heights[i];
            if (w <= 0 || h <= 0) continue;
            if (Math.max(w, h) < minLongEdge) continue;
            long area = (long) w * h;
            if (best < 0 || area < bestArea) { best = i; bestArea = area; }
        }
        if (best >= 0) return best;
        for (int i = 0; i < n; i++) {
            int w = widths[i], h = heights[i];
            if (w <= 0 || h <= 0) continue;
            long area = (long) w * h;
            if (best < 0 || area > bestArea) { best = i; bestArea = area; }
        }
        return best;
    }

    /** 0 when the displayed aspect matches the view exactly; grows symmetrically with
     *  mismatch in either direction. Returns 0 for an unmeasured view, which makes
     *  bestPreviewIndex fall back to pure area. */
    static double aspectError(int w, int h, int viewW, int viewH, boolean swap) {
        if (viewW <= 0 || viewH <= 0) return 0.0;
        double dispW = swap ? h : w;
        double dispH = swap ? w : h;
        return Math.abs(Math.log((dispW / dispH) / ((double) viewW / viewH)));
    }
}
