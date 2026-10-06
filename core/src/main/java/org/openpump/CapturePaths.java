package org.openpump;

/**
 * What {@link CaptureProvider} is allowed to hand a WRITABLE descriptor for, expressed as
 * pure rules so the desktop self-test can execute them — CaptureProvider itself imports
 * android.* and is invisible to test.sh's pure suite, exactly the split
 * {@link LogProvider}/{@link LogPaths} uses.
 *
 * WHY THIS EXISTS. The system camera (MediaStore.ACTION_IMAGE_CAPTURE) needs a
 * {@code content://} URI it can WRITE the JPEG into (EXTRA_OUTPUT). This APK is built
 * without Gradle and therefore without AndroidX, so there is no FileProvider; a raw
 * {@code file://} URI throws FileUriExposedException on API 24+. CaptureProvider serves
 * that write itself, and a provider that hands out a WRITABLE fd is the mirror image of
 * LogProvider's read hazard: the containment must be at least as strict, because here the
 * risk is an OVERWRITE, not a read. The app's flat promise — the debug log never contains
 * measurements or photos, and photos leave the phone only through an export the user
 * explicitly asked to include them in — has to be enforced where the descriptor is
 * opened, not left to the caller happening to build a well-behaved URI.
 *
 * The rule is deliberately the NARROWEST possible: exactly ONE filename is ever writable,
 * a dedicated scratch file ({@link #STAGING_NAME}) that is neither a reading photo
 * (reading-&lt;id&gt;-&lt;view&gt;.jpg) nor a log (session-* / pumpdebug-*.txt). The
 * committed photo is written by the app itself (CameraScreen.saveFinal), NEVER through
 * this provider, so the provider never needs to address a real photo path at all — the
 * camera app can only ever overwrite the one throwaway file. Two independent guards, as in
 * LogPaths, so neither one alone is load-bearing:
 *   1. {@link #isCaptureName} — the requested name must be the bare staging name, with no
 *      separator of any kind (so a decoded {@code Pictures/x} or {@code ../reading-1-front.jpg}
 *      is rejected before any File is built), and it must be THAT name, not merely some
 *      bare name.
 *   2. CaptureProvider then re-checks {@link LogPaths#isDirectChildOf} against the Pictures
 *      dir, so even a name that somehow passed (1) cannot resolve outside it.
 */
public final class CapturePaths {

    private CapturePaths() { }

    /** Must equal the AndroidManifest &lt;provider android:authorities&gt; for CaptureProvider.
     *  Kept here, pure, so the self-test can pin the authority the manifest and the URI both
     *  depend on without an Activity. */
    public static final String AUTHORITY = "org.openpump.capture";

    /** The single scratch file the system camera is granted write access to. Deliberately
     *  NOT a reading photo name and NOT a log name: the camera can only ever overwrite this
     *  one throwaway file, never a committed photo or a diagnostic log. */
    public static final String STAGING_NAME = "capture-pending.jpg";

    /**
     * THE MARKER THAT KEEPS THESE PHOTOS OUT OF THE PHONE'S GALLERY.
     *
     * Photos already live in getExternalFilesDir(Pictures) — the APP-SPECIFIC external
     * directory, which MediaStore does not index, so they do not appear in Google Photos or
     * the stock gallery to begin with. That is the actual guarantee. This file is
     * BELT-AND-BRACES: a third-party gallery app that walks the filesystem itself rather
     * than querying MediaStore honours a .nomedia in the folder, and so does a media
     * scanner that has been pointed at the directory by hand or by a file manager. It costs
     * one empty file, and the failure it prevents — a body photo surfacing in a shared
     * gallery app — is not one worth relying on a single mechanism for.
     *
     * Writing it is idempotent (see Photos#ensureNoMedia): an existing marker is left
     * exactly as it is, never rewritten, so startup does no IO on the common path.
     */
    public static final String NOMEDIA_NAME = ".nomedia";

    /** The marker is NOT a capture target: {@link #isCaptureName} must refuse it, so the
     *  system camera can never be handed a writable descriptor for it and blank it out. */
    public static boolean isNoMediaName(String name) {
        return NOMEDIA_NAME.equals(name);
    }

    /**
     * True only for the one bare filename this provider will hand a writable fd for.
     * Rejects any name carrying a separator (the LogPaths discipline, reused verbatim so
     * the two providers cannot drift on what "a bare filename" means), and any name that is
     * not the staging file — a reading photo, a log, the model JSON all fail here.
     */
    public static boolean isCaptureName(String name) {
        return LogPaths.isBareFileName(name) && STAGING_NAME.equals(name);
    }

    /** The exact {@code content://} URI CameraScreen hands the camera as EXTRA_OUTPUT, and
     *  the authority half of it must match the manifest's provider declaration. Pure so the
     *  self-test pins both halves (authority + name) in one place. */
    public static String contentUri() {
        return "content://" + AUTHORITY + "/" + STAGING_NAME;
    }
}
