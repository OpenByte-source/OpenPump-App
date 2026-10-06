package org.openpump;

/**
 * H1 - THE HOLD NOTIFICATION NEVER PROMISES WHAT CANNOT HAPPEN.
 *
 * The hold's notification (RunService#showHoldNotification) says "Vents on its own in
 * m:ss" and carries RELEASE. Both are true only while the screen that holds the pump is
 * alive in this process: the countdown is that screen's hold limit, and RELEASE starts
 * RunService, which hands the tap to that screen's own vent path. Neither can reach the pump
 * from anywhere else - RunService deliberately owns no Bluetooth connection.
 *
 * So when there is no such screen, the notice must stop saying either thing. A process that
 * ended without taking the notice down left it in the tray, frozen; a RELEASE tapped on it
 * started a new process that did nothing. The notice is then REPLACED by one that says what
 * is true and what to do, with no button and no countdown. These are the decisions and the
 * words; RunService applies them and WiringCheck invariant 78 holds it to them.
 */
public final class HoldNotice {

    private HoldNotice() { }

    /** RELEASE reached a live screen in this process: it vents through the app's own path. */
    public static final int RELEASE_IN_APP = 0;
    /** Nothing in this process holds anything: RELEASE cannot reach the pump, and the
     *  notice says so instead of doing nothing under a frozen countdown. */
    public static final int SAY_CLOSED = 1;

    public static int onRelease(boolean liveScreen) {
        return liveScreen ? RELEASE_IN_APP : SAY_CLOSED;
    }

    /** A hold notice in the tray that no hold in this process posted belongs to a process
     *  that ended without taking it down - its countdown and its RELEASE are both untrue. */
    public static boolean stale(boolean inTray, boolean postedByThisProcess) {
        return inTray && !postedByThisProcess;
    }

    /** (the run-stop-watch follow-up) The hold's notice id carries an ended run's venting
     *  notice too (HoldForeground#ventOwed), so the notice that replaces it says what is true
     *  of both. */
    public static final String CLOSED_TITLE = "OpenPump closed while the pump was holding or venting";
    /** The app closed with a stop queued on the way out (the crash guard queues one as the
     *  process dies). It says only that the app TRIED: the frame may never have left the
     *  phone, and nothing was left to see whether it landed (the safety review). */
    public static final String CLOSED_TOLD_STOP = "It tried to stop the pump as it closed - "
        + "check the cuff. If it is still under pressure, disconnect the tubing at the cuff.";
    /** The app is not running, and nothing is known about what it sent. */
    public static final String CLOSED_UNKNOWN = "It cannot release the pump from here. If the "
        + "cuff is still under pressure, disconnect the tubing at the cuff.";
}
