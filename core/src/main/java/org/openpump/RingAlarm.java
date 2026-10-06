package org.openpump;

/**
 * THE POST-SESSION RING TIMER'S ALARM - the decisions, with no Android in them (0.10, the
 * owner's decision after the incognito safety review's M4).
 *
 * The "N minutes are up - take it off" notice used to be a Handler post inside the app's
 * process. A cached process that Android freezes or ends takes the post with it, and the
 * phone is in a pocket: the one time the notice matters is the time nobody is looking at
 * the screen. So the timer is an AlarmManager alarm (RingTimer, in the app), and the
 * in-app countdown stays as the display and as a second, in-process way to the same notice.
 *
 * Everything here is what that wiring has to decide, kept pure so it can be asserted:
 *   - which kind of alarm the phone allows ({@link #how}),
 *   - whether the card has to explain that and offer the setting ({@link #asksForExact}),
 *   - that the alarm and the in-app countdown post the notice ONCE between them
 *     ({@link #shouldPost}),
 *   - what a reboot does to a timer that was running ({@link #afterBoot}),
 *   - and the words.
 */
public final class RingAlarm {
    private RingAlarm() { }

    /** An exact alarm (setExactAndAllowWhileIdle): on time, in Doze too. */
    public static final int EXACT = 1;
    /** An inexact one (setAndAllowWhileIdle): it still fires with the process gone, but
     *  Android may hold it back some minutes. */
    public static final int INEXACT = 2;

    /** Android 12 (API 31) is where exact alarms started to need the Alarms & reminders
     *  permission. Below it every app may set one. */
    public static final int EXACT_ASK_SDK = 31;

    /**
     * Which alarm to set. Below API 31 an exact alarm needs no permission; from 31 on it
     * needs SCHEDULE_EXACT_ALARM, which is granted at install on 31-33 and off by default
     * for a new install on 34+ (Android 14), where the person turns it on in "Alarms & reminders".
     */
    public static int how(int sdk, boolean canScheduleExact) {
        return sdk < EXACT_ASK_SDK || canScheduleExact ? EXACT : INEXACT;
    }

    /** The card explains and offers the setting exactly when the alarm would be inexact. */
    public static boolean asksForExact(int sdk, boolean canScheduleExact) {
        return how(sdk, canScheduleExact) == INEXACT;
    }

    /**
     * ONE NOTICE, WHICHEVER WAY ARRIVES FIRST. The alarm carries the end it was set for, and
     * the in-app countdown knows the end it counted to; the notice is posted only while that
     * end is the timer still pending, and posting clears it. So the second arrival finds
     * nothing pending and stays quiet, a stopped timer (nothing pending) posts nothing, and
     * an old alarm for a timer since restarted (a different end) cannot speak for the new one.
     */
    public static boolean shouldPost(long pendingEndsAt, long firedFor) {
        return pendingEndsAt > 0L && firedFor == pendingEndsAt;
    }

    /**
     * THE RUNNING COUNTDOWN, BACK AFTER A PROCESS RESTART (the owner's decision, 2026-09-27).
     * The alarm outlives the process; the summary's countdown did not, so a timer still
     * running when Android ended the app came back as a "Start" button over a pending alarm.
     * The start is recovered from what the alarm keeps: its end and its minutes. Returns 0 -
     * nothing to restore - when nothing is pending, the end has passed (the alarm or the
     * countdown has it), or the minutes are unknown.
     */
    public static long restoredStart(long pendingEndsAt, int minutes, long now) {
        if (pendingEndsAt <= now || minutes <= 0) return 0L;
        return pendingEndsAt - (long) minutes * 60000L;
    }

    /** What a boot does with a timer that was pending when the phone went off. */
    public static final int BOOT_NOTHING = 0;
    /** Still running: set the alarm again (a reboot drops every alarm). */
    public static final int BOOT_REARM = 1;
    /** It ran out while the phone was off: say so now. */
    public static final int BOOT_POST = 2;

    /** A timer that ran out longer ago than this before the phone came back is dropped, not
     *  announced: two hours on, a "take it off" is about a ring that has been dealt with. */
    public static final long BOOT_STALE_MS = 2L * 60L * 60L * 1000L;

    public static int afterBoot(long pendingEndsAt, long now) {
        if (pendingEndsAt <= 0L) return BOOT_NOTHING;
        if (pendingEndsAt > now) return BOOT_REARM;
        return now - pendingEndsAt <= BOOT_STALE_MS ? BOOT_POST : BOOT_NOTHING;
    }

    /** The notice's title and body. A safety notice, so incognito never turns it into a
     *  bare "Reminder" (Reminders#notifyRingTimer). */
    public static final String DONE_TITLE = "Ring timer";

    public static String doneText(int minutes) {
        return minutes == 1 ? "1 minute is up - take it off."
                            : minutes + " minutes are up - take it off.";
    }

    /** The card's words when the phone will only allow an inexact alarm. Plain: what may
     *  happen, what keeps it on time, and where to change it. */
    public static final String INEXACT_NOTE = "Exact alarms are off for this app on this phone, "
        + "so if the phone closes the app the \"take it off\" alert may come a few minutes "
        + "late. Keeping this screen open keeps it on time, or allow exact alarms.";

    /** The button that opens the system's "Alarms & reminders" page for this app. */
    public static final String ALLOW_BUTTON = "Open Alarms & reminders";
}
