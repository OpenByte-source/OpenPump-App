package org.openpump;

/**
 * H1 - THE LAST STOP LEAVES THE PHONE BEFORE THE LINK GOES.
 *
 * SessionActivity#onDestroy is the last chance to send anything, and it used to queue its
 * stop and close the link in the same main-thread turn. PumpLink#close drops every frame
 * still queued, so a stop waiting behind a write in flight never left the phone: the pump
 * went on holding while the next screen had no hold on record and said nothing (the
 * Activity's own note, docs/BUILD.md 6.3).
 *
 * PumpLink#stopThenClose now keeps the link open until the stop's OWN write has completed
 * - the stack's onCharacteristicWrite, or the write timeout that advances past a stack that
 * never answers - plus a short grace for the radio to put it on the air, and never longer
 * than a bound, so a dead screen's connection cannot linger. This is the decision it asks.
 *
 * WHAT IT CANNOT PROMISE, stated so nobody reads more into it: a write-without-response has
 * no acknowledgement from the pump, so "written" means the phone's Bluetooth stack took it,
 * not that the pump acted on it. It is the best a screen that is going away can do; the
 * confirmation that only telemetry can give needs a screen still watching.
 */
public final class LastStop {

    private LastStop() { }

    /** How long after the stop's write completes the link stays up, for the radio. A few
     *  connection intervals at the slowest this pump negotiates. */
    public static final long GRACE_MS = 250L;
    /** The longest the link is kept for the stop at all - enough to outlast one write
     *  already in flight (PumpLink's 400 ms timeout) and a refused first attempt. */
    public static final long MAX_MS = 1_500L;
    /** writtenAt before the stop's write has completed. */
    public static final long NOT_WRITTEN = -1L;

    /** The instant the link may close: a grace after the stop was written, never later than
     *  the bound after it was queued. */
    public static long closeAt(long queuedAt, long writtenAt, long graceMs, long maxMs) {
        long bound = queuedAt + maxMs;
        if (writtenAt == NOT_WRITTEN) return bound;
        return Math.min(writtenAt + graceMs, bound);
    }

    public static boolean mayClose(long now, long queuedAt, long writtenAt,
                                   long graceMs, long maxMs) {
        return now >= closeAt(queuedAt, writtenAt, graceMs, maxMs);
    }

    /* The next screen's account of it. A destroyed screen has nothing left to say it on, and
     * the screen that replaces it has no hold on record, so SessionActivity leaves itself a
     * note and says this once on the next resume. It says only that the app TRIED: nothing
     * waits for the pump to acknowledge a stop (whether it acknowledges every one is release
     * checklist H15), and nothing was left watching to see whether it landed. */
    public static final String SCREEN_CLOSED_TITLE = "The hold was ended";
    public static final String SCREEN_CLOSED_TEXT = "The app closed while the pump was holding "
        + "a measurement, so it tried to stop the pump as it closed - check the cuff. If it is "
        + "still under pressure, disconnect the tubing at the cuff.";
}
