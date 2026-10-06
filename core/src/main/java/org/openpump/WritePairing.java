package org.openpump;

/**
 * V1 SAFETY REVIEW, FINDING 3 - WHICH WRITE A WRITE-DONE BELONGS TO.
 *
 * PumpLink hands the Bluetooth stack one frame at a time. It retires each one on the stack's
 * onCharacteristicWrite, or on a 400 ms timeout when the stack is slow to answer. Android's
 * callback does not say which write it is for. A late callback for a frame the timeout had
 * already retired then retired the NEXT frame - the StopWork - with the earlier frame's
 * completion time. The vent watch counted frames from before the stop as evidence about it.
 *
 * So PumpLink gives every write a token and notes its issue time just BEFORE handing it to the
 * stack. It reads the in-flight token on the callback thread the moment the callback fires, and
 * asks this before retiring anything. A report that fails is ignored. The write in flight then
 * retires on its own report or its own timeout, both of which are later, and later is the
 * conservative direction for "when did the stop go out".
 *
 * WHAT THIS CANNOT TELL APART, stated rather than hidden: a late callback for the previous frame
 * that fires AFTER the next write was handed to the stack. It carries the new token and a time
 * no earlier than the new write's issue, so it is accepted as the new write's. The time it
 * reports then lies between the new write's issue and its real completion. That is never
 * before the stop was handed to the stack, which is the property the vent watch needs. The
 * watch also rejects any completion time from before its stop was queued
 * (Session#ventWatchResult).
 */
public final class WritePairing {

    /** Whether a write-done reported with `reportedToken`, stamped `at`, belongs to the write in
     *  flight - the one holding `inFlightToken`, handed to the stack at `issuedAt`. Token 0 is
     *  never issued. All times are on one monotonic clock. */
    public static boolean isOwn(int reportedToken, int inFlightToken, long at, long issuedAt) {
        return inFlightToken != 0 && reportedToken == inFlightToken && at >= issuedAt;
    }

    private WritePairing() { }
}
