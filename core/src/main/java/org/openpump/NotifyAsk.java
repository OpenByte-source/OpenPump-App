package org.openpump;

/**
 * WHETHER TO ASK FOR THE NOTIFICATION PERMISSION NOW - for runs and holds (the release
 * review).
 *
 * On Android 13 and newer an app needs POST_NOTIFICATIONS to show anything in the status
 * bar, and only the two reminder switches ever asked for it. A run got a warning toast; a
 * hold got nothing - and a hold now relies on its notification: the countdown, a Release
 * button, and "Vent not confirmed - disconnect the tubing". So the app asks, once.
 *
 * WHEN is the whole of the safety question, and it is decided here:
 *   - only where the person is STARTING something (the START confirm, a manual run, a set
 *     tried on its own, a hold's own start button) - a hold the session flow starts after
 *     the START it followed is not such a point;
 *   - only while NOTHING IS COMMANDED - never mid-run, never mid-hold, never over a stop the
 *     pump has not yet confirmed. The ask comes after the START gate and before anything is
 *     sent, so it can never stand in front of STOP, a vent, the gate or the hold limit
 *     (WiringCheck invariant 100 pins where it is called);
 *   - once. After an answer - either answer - it is not asked again here; Settings is where
 *     the person asks for it again.
 * Below Android 13 there is nothing to ask.
 */
public final class NotifyAsk {

    private NotifyAsk() { }

    /** The first Android version (API level) that needs the permission. */
    public static final int FIRST_SDK = 33;

    /** The one line that says why, shown before the system's own question. */
    public static final String REASON = "So a hold or run can show its countdown and a Release "
        + "button, and warn you if a vent can't be confirmed.";

    /** Said once, after a "no": what will not appear, and where to turn it on later. */
    public static final String DENIED_NOTE = "Notifications are off, so a run or hold won't show "
        + "its countdown, Release button or vent warning outside the app. You can turn them on "
        + "in Settings › Session.";

    /**
     * @param sdk          Build.VERSION.SDK_INT
     * @param granted      the permission is already held
     * @param askedBefore  the app has asked for runs and holds before (Model#notifyAsked)
     * @param consentPoint the person is starting something with this tap
     * @param commanding   anything is commanded or unconfirmed (SessionActivity#stillUnsafe)
     */
    public static boolean askNow(int sdk, boolean granted, boolean askedBefore,
                                 boolean consentPoint, boolean commanding) {
        return sdk >= FIRST_SDK && !granted && !askedBefore && consentPoint && !commanding;
    }
}
