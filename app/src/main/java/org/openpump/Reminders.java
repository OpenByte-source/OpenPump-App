package org.openpump;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * The DEVICE half of the training reminder. Everything decidable — which instants a
 * reminder is due at, given the schedule and the hour and the moment it is being asked —
 * lives in {@link Schedule#nextFires}, which is pure and asserted. What is left here is
 * the wiring: set an alarm at the number that returned, and put a notification up when it
 * arrives. That split is deliberate. An alarm set for a day the user does not train, or
 * for a time already past so it fires the moment Settings is opened, is invisible in any
 * screenshot and shows up only as a notification on a rest day.
 *
 * WHY NOT AN EXACT ALARM. setExactAndAllowWhileIdle would need SCHEDULE_EXACT_ALARM, a
 * permission Android treats as a serious request and which a reminder to train does not
 * deserve. A plain {@link AlarmManager#set} is inexact under Doze — it may land some
 * minutes late — and a training reminder that arrives at 19:07 instead of 19:00 has lost
 * nothing. The app asks for the smallest thing that does the job. (The ring timer's "take it
 * off" is the exception: a safety notice, set as an exact alarm by RingTimer - see there.)
 *
 * WHY ONE ALARM AT A TIME, RESCHEDULED. setRepeating with a day's period cannot express
 * "Mon, Wed and Fri", so a repeating alarm would fire on the rest days and the receiver
 * would have to suppress most of what it woke for. Instead exactly one alarm is
 * outstanding, always aimed at the next SCHEDULED instant, and the receiver sets the
 * following one after it fires. The receiver re-checks the schedule before it notifies —
 * belt and braces, so a stale alarm surviving a schedule change (the user turned Tuesday
 * off while an alarm for Tuesday was already set) cannot produce a reminder on a day that
 * is no longer a training day.
 */
public final class Reminders {
    private Reminders() { }

    public static final String CHANNEL_ID = "training";
    public static final String CHANNEL_NAME = "Training reminders";
    public static final String ACTION_FIRE = "org.openpump.REMINDER";
    private static final int REQUEST_CODE = 4711;
    private static final int NOTIFICATION_ID = 4711;

    /* ---- the MEASUREMENT reminder --------------------------------------------------- */

    /**
     * A SECOND, INDEPENDENT ALARM. Its own request code (so scheduling one never replaces
     * the other — two PendingIntents with the same code and the same Intent are the SAME
     * alarm, and a shared code would have made the measurement reminder silently cancel the
     * training one), its own action (so the receiver can tell which fired), its own
     * notification id (so both can stand in the shade at once) and its own channel (so a
     * user can mute one and keep the other). Four separate things, because they are four
     * separate ways the two reminders could otherwise be mistaken for one.
     */
    public static final String MEAS_CHANNEL_ID = "measure";
    public static final String MEAS_CHANNEL_NAME = "Measurement reminders";
    public static final String ACTION_MEAS_FIRE = "org.openpump.MEAS_REMINDER";

    /** The TRAINER alarm \u2014 its own action and request code, so it can never be mistaken for
     *  either of the other two (G7). One alarm serves both trainer reminders: they are both
     *  "something about the plan happens today", asked at the same hour, and two alarms would
     *  mean two notifications on a day that is both. */
    public static final String ACTION_TRAINER_FIRE = "org.openpump.TRAINER_REMINDER";
    private static final int TRAINER_REQUEST_CODE = 4715;

    /** A2 - the OTHER TRACK alarm: one shot, a few hours after the first track of a
     *  both-tracks day is filed. Its own request code so it cannot displace the daily
     *  training alarm, and its own notification id so the two never overwrite each other. */
    public static final String ACTION_TRACK_FIRE = "org.openpump.TRACK_REMINDER";
    private static final int TRACK_REQUEST_CODE = 4717;
    private static final int TRACK_NOTIFICATION_ID = 4717;

    /** How long after the first track is filed the other one is mentioned. Long enough to
     *  be a second session rather than a nag about the one just finished. */
    public static final long OTHER_TRACK_GAP_MS = 3L * 60L * 60L * 1000L;
    /** Nothing fires after this hour. A reminder to train that arrives at eleven at night is
     *  not a reminder, it is a reproach. */
    public static final int OTHER_TRACK_LATEST_HOUR = 21;
    private static final int MEAS_REQUEST_CODE = 4713;
    private static final int MEAS_NOTIFICATION_ID = 4713;

    /** The words. Kept here as constants so the notification and any copy describing it
     *  cannot drift apart, and so they say what is due without urging anything: a session
     *  is due, not "don't lose your streak". */
    public static final String TITLE = "Training day";
    public static final String TEXT  = "Your session is due";
    public static final String MEAS_TITLE = "Time to measure";
    public static final String MEAS_TEXT  = "A reading is due";
    /** STAGE H TASK 5 — the Schedule's own "train today" reminder, reworded during a trainer
     *  deload week (plan round-2 ruling: "Schedule's 'train today' reminders are reworded/
     *  suppressed during a deload week"). Reword rather than suppress, so the reminder still
     *  shows up and says something true instead of silently going dark for a week. */
    public static final String DELOAD_TITLE = "Deload week";
    public static final String DELOAD_TEXT  = "Light or none — counters are frozen";

    /**
     * Bring the outstanding alarm into line with the schedule — the ONE entry point, called
     * whenever anything it depends on changes (the toggle, the days, the hour) and after a
     * reboot. It always cancels first, so this is idempotent and can never leave two alarms
     * chasing each other.
     *
     * Nothing is scheduled when reminders are off, when no day is selected, or when the
     * pure layer declines to name a next instant. Each of those is the same answer —
     * nothing is due — reached three ways.
     */
    public static void reschedule(Context c, Schedule sched) {
        cancel(c);
        if (c == null || sched == null || !sched.remind || !sched.any()) return;
        long at = sched.nextFire(System.currentTimeMillis());
        if (at <= 0) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.set(AlarmManager.RTC_WAKEUP, at, pending(c));
        } catch (SecurityException ignored) {
            // An inexact alarm needs no permission, so this should not happen; if a vendor
            // ROM refuses anyway, the app simply has no reminder rather than crashing.
        }
    }

    /**
     * A2 - set the one-shot other-track alarm, or drop it.
     *
     * {@code atMs <= 0} cancels, which is how the caller says "nothing is outstanding" -
     * one entry point, so an alarm can never outlive the day that armed it.
     */
    public static void scheduleOtherTrack(Context c, long atMs) {
        if (c == null) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(otherTrackPending(c));
        if (atMs <= 0L) return;
        try { am.set(AlarmManager.RTC_WAKEUP, atMs, otherTrackPending(c)); }
        catch (SecurityException ignored) { }
    }

    private static PendingIntent otherTrackPending(Context c) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ACTION_TRACK_FIRE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, TRACK_REQUEST_CODE, i, flags);
    }

    /** The other-track notification. Same channel as the training reminder - it is the same
     *  kind of thing - but its own id, so it neither replaces nor is replaced by one. */
    public static void notifyOtherTrack(Context c, String what, boolean discreet) {
        if (c == null) return;
        ensureChannel(c);
        NotificationManager nm =
            (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // (re-review 3) the app as it is - never a new screen over a live run or hold.
        Intent open = RunService.appIntent(c);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(c, TRACK_REQUEST_CODE + 1, open, flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(c, CHANNEL_ID);
        else b = new Notification.Builder(c);
        words(b, discreet, "Still to run today", what);
        b.setSmallIcon(R.drawable.ic_notification)
         .setContentIntent(tap)
         .setAutoCancel(true);
        try { nm.notify(TRACK_NOTIFICATION_ID, b.build()); }
        catch (SecurityException ignored) { }
    }

    /** Drop any outstanding alarm — the toggle going off, or the last training day being
     *  unselected. Safe to call when nothing is set. */
    public static void cancel(Context c) {
        if (c == null) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pending(c));
    }

    /** The one PendingIntent this feature owns. FLAG_UPDATE_CURRENT so a reschedule
     *  replaces rather than accumulates, and IMMUTABLE because nothing outside this app has
     *  any business editing it. */
    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ACTION_FIRE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQUEST_CODE, i, flags);
    }

    /**
     * Bring the MEASUREMENT alarm into line with the model — the twin of
     * {@link #reschedule}, and the ONE entry point for it.
     *
     * It aims at the next occurrence of the chosen hh:mm, EVERY day, and the receiver
     * decides at that moment whether a reading is actually due. The measurement cadence
     * counts sessions and hours, not weekdays, so it cannot name a day in advance the way
     * the training schedule can: the only honest schedule is "check daily, notify when the
     * cadence says so". See Model#measRemind.
     *
     * Nothing is scheduled when the reminder is off or the cadence is off — the same
     * answer, nothing is due, reached two ways.
     */
    public static void rescheduleMeas(Context c, Model m) {
        cancelMeas(c);
        if (c == null || m == null || !m.measRemind) return;
        if (m.meas == null || "off".equals(m.meas.mode)) return;
        long at = Schedule.nextDailyAt(System.currentTimeMillis(),
                                       m.measRemindHour, m.measRemindMin);
        if (at <= 0) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.set(AlarmManager.RTC_WAKEUP, at, measPending(c));
        } catch (SecurityException ignored) {
            // Inexact, so this should not happen; a vendor ROM that refuses anyway leaves
            // the user with no measurement reminder rather than a crash.
        }
    }

    /** Drop any outstanding measurement alarm. Safe when nothing is set. */
    public static void cancelMeas(Context c) {
        if (c == null) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(measPending(c));
    }

    /**
     * The trainer's own daily alarm (G7). Set for every day; WHAT happens when it fires is
     * decided at fire time by ReminderReceiver, not here, for the same reason the
     * measurement alarm works that way: the plan can move between the moment an alarm is
     * scheduled and the moment it goes off.
     *
     * Scheduled only while enrolled AND at least one of the two trainer reminders is on \u2014
     * an alarm nothing would act on is an alarm not worth setting.
     */
    public static void rescheduleTrainer(Context c, Model m) {
        cancelTrainer(c);
        if (c == null || m == null || !m.trainerEnrolled) return;
        if (!m.trainerRemindTrainingDay && !m.trainerRemindDeloadStart) return;
        long at = Schedule.nextDailyAt(System.currentTimeMillis(),
                                       m.measRemindHour, m.measRemindMin);
        if (at <= 0) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.set(AlarmManager.RTC_WAKEUP, at, trainerPending(c));
        } catch (SecurityException ignored) { }
    }

    /** Drop any outstanding trainer alarm. Safe when nothing is set. */
    public static void cancelTrainer(Context c) {
        if (c == null) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(trainerPending(c));
    }

    private static PendingIntent trainerPending(Context c) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ACTION_TRAINER_FIRE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, TRAINER_REQUEST_CODE, i, flags);
    }

    /** The trainer notification. Reuses the measurement channel rather than inventing a
     *  third: to the person these are all "the app reminding me about my own plan". */
    public static void notifyTrainer(Context c, String title, String text, boolean discreet) {
        if (c == null) return;
        ensureMeasChannel(c);
        NotificationManager nm =
            (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // (re-review 3) the app as it is - never a new screen over a live run or hold.
        Intent open = RunService.appIntent(c);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(c, TRAINER_REQUEST_CODE + 1, open, flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(c, MEAS_CHANNEL_ID);
        else b = new Notification.Builder(c);
        words(b, discreet, title, text);
        b.setSmallIcon(R.drawable.ic_notification)
         .setContentIntent(tap)
         .setAutoCancel(true);
        try {
            nm.notify(TRAINER_REQUEST_CODE, b.build());
        } catch (SecurityException ignored) { }
    }

    /**
     * THE RING TIMER - "take it off" - is a SAFETY notice, not a reminder (incognito, 0.10):
     * discreet reminders never turn it into a bare "Reminder". It keeps its plain words, and
     * like every other safety notice it reads neutral words on the lock screen only when
     * "Safety warnings on the lock screen" is on (RunService#safetyOnLockScreen).
     *
     * (the incognito safety review, M4) ITS OWN SLOT AND THE SAFETY ALERTS' CHANNEL. It was
     * posted under the trainer reminder's id, so "Training day" or "Deload week starts today"
     * replaced an unseen "take it off" (and it them); and on the measurement reminders'
     * channel, which a privacy-minded person may well mute. Now: RING_NOTIFICATION_ID, and the
     * alert channel that rings and vibrates (RunService#CHANNEL_ALERT_ID, invariant 197).
     */
    public static void notifyRingTimer(Context c, String title, String text) {
        if (c == null) return;
        RunService.ensureChannels(c);
        NotificationManager nm =
            (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = RunService.appIntent(c);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(c, RING_NOTIFICATION_ID, open, flags);
        Notification.Builder b = RunService.builder(c, RunService.CHANNEL_ALERT_ID,
                                                    Notification.PRIORITY_HIGH);
        b.setContentTitle(title)
         .setContentText(text)
         .setSmallIcon(R.drawable.ic_notification)
         .setContentIntent(tap)
         .setCategory(Notification.CATEGORY_ALARM)
         .setDefaults(Notification.DEFAULT_ALL)
         .setAutoCancel(true);
        RunService.safetyOnLockScreen(c, b, RunService.CHANNEL_ALERT_ID,
                                      Notification.PRIORITY_HIGH, tap);
        try {
            nm.notify(RING_NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) { }
    }

    /** The ring timer's own notification id (and its tap's request code): not 4711-4721, which
     *  the reminders and RunService's notices already use. */
    static final int RING_NOTIFICATION_ID = 4722;

    /**
     * DISCREET REMINDERS (incognito, 0.10, the owner's decision): a reminder says "Reminder"
     * and nothing else - no title about training, measuring or a track, no body - on the lock
     * screen and off it. Off, the reminder's own words, as before.
     */
    static void words(Notification.Builder b, boolean discreet, String title, String text) {
        if (discreet) {
            b.setContentTitle(Incognito.REMINDER_TITLE);
            return;
        }
        b.setContentTitle(title).setContentText(text);
    }

    /** The measurement reminder's own PendingIntent — a DIFFERENT request code and a
     *  different action from {@link #pending}, so the two alarms can never be each other. */
    private static PendingIntent measPending(Context c) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ACTION_MEAS_FIRE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, MEAS_REQUEST_CODE, i, flags);
    }

    /** Creates the channel if it is not there. Idempotent — the system ignores a repeat,
     *  and a channel the user has muted stays muted. */
    public static void ensureChannel(Context c) {
        if (c == null || Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm =
            (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("A reminder at your training hour, on the days you train.");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /** The measurement reminder's own channel, so it can be muted without silencing the
     *  training one. Same idempotence. */
    public static void ensureMeasChannel(Context c) {
        if (c == null || Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm =
            (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
            MEAS_CHANNEL_ID, MEAS_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("A reminder to log a measurement, when one is due.");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /** Put the measurement reminder up. Tapping it opens the app, exactly as the training
     *  one does — a pointer at the app, never a place to act from. */
    public static void notifyMeasDue(Context c, boolean discreet) {
        if (c == null) return;
        ensureMeasChannel(c);
        NotificationManager nm =
            (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // (re-review 3) the app as it is - never a new screen over a live run or hold.
        Intent open = RunService.appIntent(c);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(c, MEAS_REQUEST_CODE + 1, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(c, MEAS_CHANNEL_ID);
        else b = new Notification.Builder(c);
        words(b, discreet, MEAS_TITLE, MEAS_TEXT);
        b.setSmallIcon(R.drawable.ic_notification)
         .setContentIntent(tap)
         .setAutoCancel(true);
        try {
            nm.notify(MEAS_NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS revoked since the switch was turned on. Nothing to do.
        }
    }

    /**
     * Put the reminder up. Tapping it opens the app on Today, where the next-due nudge says
     * the same thing in context — the notification is a pointer at the app, never a place
     * to act from.
     */
    public static void notifyDue(Context c) { notifyDue(c, false, false); }

    /**
     * STAGE H TASK 5 — the deload-aware overload. `deloadWeek` true swaps in
     * {@link #DELOAD_TITLE}/{@link #DELOAD_TEXT} — the same notification slot and channel,
     * just different words, so a deload week still gets the reminder rather than going
     * silent, and never a SECOND notification alongside the normal one.
     */
    public static void notifyDue(Context c, boolean deloadWeek, boolean discreet) {
        if (c == null) return;
        ensureChannel(c);
        NotificationManager nm =
            (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // (re-review 3) the app as it is - never a new screen over a live run or hold.
        Intent open = RunService.appIntent(c);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(c, REQUEST_CODE + 1, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(c, CHANNEL_ID);
        else b = new Notification.Builder(c);
        words(b, discreet, deloadWeek ? DELOAD_TITLE : TITLE, deloadWeek ? DELOAD_TEXT : TEXT);
        b.setSmallIcon(R.drawable.ic_notification)
         .setContentIntent(tap)
         .setAutoCancel(true);
        try {
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS revoked between the toggle and the alarm. Nothing to do:
            // the toggle re-checks on the next visit to Settings.
        }
    }
}
