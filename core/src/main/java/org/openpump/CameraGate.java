package org.openpump;

/**
 * Pure request/pending-flag bookkeeping behind the real camera2 device open, pulled out
 * of CameraScreen so the exact shape of prototype defect #24 can be proven correct on the
 * desktop without a camera or a device. Mirrors proto/pump-console.html's camReq/camPending
 * pair (camReal()):
 *
 *   #24 — camReal()'s getUserMedia grant lands asynchronously; if the user has already
 *   left the viewfinder (or tapped Enable a second time) by the time it arrives, the
 *   prototype's stale-grant branch stops the tracks and returns WITHOUT restoring the
 *   "Asking…" button label — camPending itself is cleared, but nothing re-derives what
 *   the button should say, so it can read "Asking…" forever even though nothing is
 *   actually pending. The routed brief for this task states the stronger, more dangerous
 *   version an async camera2 callback can produce if this is done carelessly: a callback
 *   that never fires at all (permission dialog dismissed, activity backgrounded before
 *   the device open completes) must not leave `pending` stuck true, or the screen could
 *   never be re-opened cleanly ("Still asking for the camera…" on every subsequent tap).
 *
 * The fix is two independent, unconditional guarantees:
 *   1. `isCurrent(token)` never depends on whether anything is pending — it only compares
 *      the token issued to whoever is asking against the CURRENT generation, so a late
 *      callback can always tell "am I still the request that matters" even after pending
 *      has already been cleared some other way.
 *   2. `clearPending()` is called by CameraScreen on EVERY exit from an open attempt's
 *      callback — success, error, disconnect, AND the stale/superseded branch alike — and
 *      `reset()` (called when the camera screen is left, or (re)entered fresh) clears it
 *      too, unconditionally, so even a callback that never arrives at all cannot strand
 *      the flag.
 */
public final class CameraGate {
    private int seq = 0;
    private boolean pending = false;

    /** A new open attempt starts: bump the generation and mark pending. The returned
     *  token is what the eventual callback must present back via isCurrent() to prove it
     *  is still the most recent attempt — a later start() (a second tap, or leaving and
     *  re-entering the screen) invalidates every token issued before it. */
    public int start() { pending = true; return ++seq; }

    /** Whether `token` is still the most recent attempt. Deliberately independent of
     *  `pending`: a callback must be able to answer this even after it (or reset()) has
     *  already cleared pending on its way out — see the class doc's guarantee #1. */
    public boolean isCurrent(int token) { return token == seq; }

    /** Must be called on every exit from an open attempt's callback, unconditionally —
     *  see the class doc's guarantee #2. Idempotent: calling it when nothing is pending
     *  is a harmless no-op. */
    public void clearPending() { pending = false; }

    public boolean isPending() { return pending; }

    /** Leaving the camera screen, or (re)entering it fresh: invalidate any attempt in
     *  flight so a late callback is provably stale, AND clear pending unconditionally —
     *  so even an attempt whose callback never arrives at all cannot leave the screen
     *  unable to be re-entered cleanly (defect #24's exact shape). */
    public void reset() { seq++; pending = false; }
}
