package org.openpump;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * THE RING TIMER'S ALARM - the device half of {@link RingAlarm} (0.10, the owner's decision
 * after the incognito safety review's M4).
 *
 * The "take it off" notice is an AlarmManager alarm, so it fires with the app's process
 * frozen or gone and the summary screen closed. The summary's countdown stays, as the display
 * and as a second way to the same notice while the process lives; {@link #fire} lets exactly
 * one of them post it (RingAlarm#shouldPost).
 *
 * EXACT, AND WHY setExactAndAllowWhileIdle AND NOT setAlarmClock. Both fire on time in Doze.
 * setAlarmClock also puts an alarm icon in the status bar and offers "next alarm" to the lock
 * screen and clock apps - an announcement this app has no business making, least of all for
 * someone using incognito. The idle-exempt exact alarm says nothing until the notice itself.
 *
 * THE PERMISSION. SCHEDULE_EXACT_ALARM (declared with no upper bound): granted at install on
 * API 31-33, off by default for a new install on 34+ (Android 14), where the person can turn it on in
 * "Alarms & reminders". USE_EXACT_ALARM is not declared: Play keeps it for apps whose core
 * job is an alarm clock or calendar, and this is not one. When exact alarms are not allowed
 * the card says so in plain words and offers the setting ({@link #allowIntent}); meanwhile the
 * alarm is an inexact setAndAllowWhileIdle, never nothing.
 *
 * A REBOOT drops every alarm. The pending end is kept in its own small preferences file, so
 * {@link #afterBoot} can set it again, or post the notice at once if it ran out while the
 * phone was off (RingTimerReceiver, BOOT_COMPLETED).
 */
final class RingTimer {
    private RingTimer() { }

    static final String ACTION_RING = "org.openpump.RING_TIMER";
    static final String EXTRA_ENDS_AT = "org.openpump.RING_ENDS_AT";
    /** Its own request code: 4711-4722 are the reminders' and the notices'. */
    private static final int REQUEST_CODE = 4723;
    private static final String PREFS = "ring_timer";
    private static final String K_ENDS = "endsAt";
    private static final String K_MIN = "minutes";
    private static final String K_EXACT = "exact";
    /** Which session's summary started it (0.10), so a countdown restored after a process
     *  restart shows on that summary only (SessionActivity#cockringCard). */
    private static final String K_SESS = "session";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** May this app set an exact alarm right now. */
    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    /** True when the card must explain the inexact alarm and offer the setting. */
    static boolean asksForExact(Context c) {
        return RingAlarm.asksForExact(Build.VERSION.SDK_INT, canExact(c));
    }

    /** Set (or replace) the one ring alarm for {@code endsAt}. Returns RingAlarm.EXACT or
     *  INEXACT - what was actually set. */
    static int schedule(Context c, long endsAt, int minutes, String sessionId) {
        prefs(c).edit().putString(K_SESS, sessionId == null ? "" : sessionId).commit();
        return schedule(c, endsAt, minutes);
    }

    /** {@link #schedule(Context, long, int, String)} keeping the session already recorded -
     *  what a re-arm (upgrade, boot) uses. */
    static int schedule(Context c, long endsAt, int minutes) {
        prefs(c).edit().putLong(K_ENDS, endsAt).putInt(K_MIN, minutes).commit();
        int how = arm(c, endsAt);
        prefs(c).edit().putBoolean(K_EXACT, how == RingAlarm.EXACT).commit();
        return how;
    }

    private static int arm(Context c, long endsAt) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return RingAlarm.INEXACT;
        PendingIntent pi = pending(c, endsAt);
        if (RingAlarm.how(Build.VERSION.SDK_INT, canExact(c)) == RingAlarm.EXACT) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pi);
                return RingAlarm.EXACT;
            } catch (SecurityException revoked) {
                // Taken away between the question and the call - fall through to inexact.
            }
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pi);
        } catch (SecurityException ignored) {
            // An inexact alarm needs no permission; the in-app countdown still stands.
        }
        return RingAlarm.INEXACT;
    }

    /** The pending timer's end, epoch millis - 0 when none is pending. */
    static long pendingEndsAt(Context c) { return prefs(c).getLong(K_ENDS, 0L); }

    /** The pending timer's minutes - 0 when none is pending. */
    static int pendingMinutes(Context c) { return prefs(c).getInt(K_MIN, 0); }

    /** The session whose summary started the pending timer, or null when unknown (a timer
     *  set before 0.10 recorded none). */
    static String pendingSession(Context c) {
        String s = prefs(c).getString(K_SESS, "");
        return s == null || s.length() == 0 ? null : s;
    }

    /** The timer was stopped (or restarted): drop the alarm and what it was for. */
    static void cancel(Context c) {
        prefs(c).edit().clear().commit();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, 0L));
    }

    /** Exact alarms were just allowed: an inexact alarm still pending becomes exact. */
    static void upgrade(Context c) {
        SharedPreferences p = prefs(c);
        long ends = p.getLong(K_ENDS, 0L);
        if (ends <= System.currentTimeMillis() || p.getBoolean(K_EXACT, false)) return;
        schedule(c, ends, p.getInt(K_MIN, 0));
    }

    /** The alarm, or the in-app countdown, reached {@code firedFor}. Posts the notice once. */
    static void fire(Context c, long firedFor) {
        SharedPreferences p = prefs(c);
        if (!RingAlarm.shouldPost(p.getLong(K_ENDS, 0L), firedFor)) return;
        int minutes = p.getInt(K_MIN, 0);
        p.edit().clear().commit();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, 0L));
        Reminders.notifyRingTimer(c, RingAlarm.DONE_TITLE, RingAlarm.doneText(minutes));
    }

    /** After a reboot: set a still-running timer again, or say a finished one is up. */
    static void afterBoot(Context c) {
        SharedPreferences p = prefs(c);
        long ends = p.getLong(K_ENDS, 0L);
        switch (RingAlarm.afterBoot(ends, System.currentTimeMillis())) {
            case RingAlarm.BOOT_REARM: schedule(c, ends, p.getInt(K_MIN, 0)); break;
            case RingAlarm.BOOT_POST: fire(c, ends); break;
            default: p.edit().clear().commit(); break;
        }
    }

    /** The system's "Alarms & reminders" page for this app (API 31+), or null below it. */
    static Intent allowIntent(Context c) {
        if (Build.VERSION.SDK_INT < 31) return null;
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                          Uri.parse("package:" + c.getPackageName()));
    }

    /** The one PendingIntent: the same request code and action every time, so a new timer
     *  replaces the old alarm and cancel() finds it; the end rides along for fire(). */
    private static PendingIntent pending(Context c, long endsAt) {
        Intent i = new Intent(c, RingTimerReceiver.class);
        i.setAction(ACTION_RING);
        i.putExtra(EXTRA_ENDS_AT, endsAt);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQUEST_CODE, i, flags);
    }
}
