package org.openpump;

/**
 * Pure "does the user still want the live camera preview, right now" tracker.
 *
 * Fix-round-1 bug: the FIRST-EVER CAMERA permission grant left the preview permanently
 * dead. `CameraScreen` used a single boolean (`liveOnCamera`) both to gate async
 * callbacks (correct — that half is `CameraGate`'s job, unchanged) AND, via
 * `Activity.onPause()` -> `releaseCamera()`, to mean "the user left the live screen".
 * Those are NOT the same event: the system permission dialog itself pauses the hosting
 * Activity, so requesting the permission caused `onPause()` to fire, which cleared the
 * flag BEFORE the grant callback ever ran — `onPermissionResult()`'s
 * `if (liveOnCamera) openCameraIfPermitted();` then saw `false` and silently did
 * nothing. The user had to back out and re-enter the whole photo flow to recover, on
 * every first-ever grant.
 *
 * This class is the fix: `screenWanted` is changed ONLY by genuine screen transitions
 * (`screenOpened()`/`screenClosed()`, called from showLive()/showReview()/finish()) —
 * never by the Activity being paused for any reason, including a dialog it triggered
 * itself. `shouldBeLive()` is the single decision every camera-open call site (the
 * initial tap, the permission callback) must go through, so "the process was merely
 * paused for a system dialog" and "the user actually left the flow" can never be
 * confused again.
 */
public final class CameraIntent {
    private boolean screenWanted = false;
    private boolean permissionGranted = false;
    private boolean askedPending = false;

    /** The user opened (or re-opened, e.g. after Retake) the live camera screen. */
    public void screenOpened() { screenWanted = true; }

    /** The user genuinely left the live screen — Skip, the flow finishing after Use, or
     *  moving on to the review screen. MUST NEVER be called from an Activity-level
     *  onPause()/onStop() — a system dialog (including the CAMERA permission prompt
     *  this same class exists to survive) pausing the process is not the user leaving;
     *  see the class doc. Hardware teardown on pause is a separate concern
     *  (CameraScreen#releaseCamera) that does not touch this. */
    public void screenClosed() {
        screenWanted = false;
        // An outstanding permission request belongs to the screen that asked. Leaving it
        // set meant refreshEnableButton() rendered "Asking…" for the life of the
        // CameraScreen — which is the life of the whole SessionActivity — if a result
        // never came back, on a button whose real job is to say whether anything is
        // actually happening. CameraGate has reset() for exactly this hazard; this is the
        // same discipline for the permission half. resolved() still records the OUTCOME
        // if the result arrives later, so nothing about shouldBeLive() changes.
        askedPending = false;
    }

    /** A CAMERA permission request was just issued — marks a result as outstanding. */
    public void asked() { askedPending = true; }

    /** The permission result arrived: records the outcome and clears the outstanding
     *  request, on both the grant and the deny path. */
    public void resolved(boolean granted) {
        askedPending = false;
        permissionGranted = granted;
    }

    /** Permission is already known granted without going through asked()/resolved() —
     *  e.g. a later camera open in the same process where CAMERA was granted earlier.
     *  Keeps shouldBeLive() accurate even on a path that never had to re-ask. */
    public void permissionAlreadyGranted() { permissionGranted = true; }

    public boolean isAskedPending() { return askedPending; }
    public boolean isPermissionGranted() { return permissionGranted; }
    public boolean screenWanted() { return screenWanted; }

    /** The single decision: should the live preview actually be running right now?
     *  True only once the screen is genuinely wanted AND permission has actually been
     *  granted — independent of whatever the hosting process's pause/resume state
     *  happens to be at the instant this is asked. This is what
     *  onPermissionResult()/openCameraIfPermitted() must consult instead of a raw
     *  Activity-lifecycle flag. */
    public boolean shouldBeLive() { return screenWanted && permissionGranted; }
}
