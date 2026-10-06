package org.openpump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The two moments the reminder alarm has to be touched from outside the app: the alarm
 * itself arriving, and the phone having been rebooted (every AlarmManager alarm is dropped
 * across a reboot, so without BOOT_COMPLETED a user who restarts their phone silently
 * stops getting reminders and has no way to tell).
 *
 * Both paths re-read the schedule from the SAVED MODEL rather than trusting anything in
 * the Intent. An alarm can outlive the settings that created it — the user turns Tuesday
 * off, or turns reminders off, while an alarm for Tuesday is already outstanding — and the
 * rule "never fire a reminder for a day that is not scheduled" has to hold against that,
 * not only against a correctly-set alarm. So the day is checked again here, against
 * {@link Schedule#isTrainingDay}, immediately before anything is shown.
 */
public final class ReminderReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context c, Intent i) {
        if (c == null) return;
        Model m;
        // AN ALARM CANNOT WARN EITHER - same reason as the widget. See Store#load.
        try { m = Store.load(c, false); } catch (Exception e) { return; }
        if (m == null || m.sched == null) return;

        String action = i == null ? null : i.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean measFire = Reminders.ACTION_MEAS_FIRE.equals(action);
        boolean trainerFire = Reminders.ACTION_TRAINER_FIRE.equals(action);
        boolean trackFire = Reminders.ACTION_TRACK_FIRE.equals(action);
        if (trackFire) {
            /* A2 - THE OTHER TRACK. Re-asked HERE, from the session log, never trusted from
             * when the alarm was set: the whole point of the reminder is that the answer may
             * have changed in the three hours since, and the commonest way it changes is
             * that the session got done. TrainerTab.upNextNow is the same question the Today
             * screen asks, so the two cannot drift into disagreeing. */
            long tnow = System.currentTimeMillis();
            if (m.remindOtherTrack && m.sched.remind && m.trainerEnrolled
                    && !MonthBreak.on(m, tnow)) {           // t10-K (B1): no plan sessions
                UpNext up = TrainerTab.upNextNow(m, tnow, false);
                if (up.what == UpNext.GIRTH)
                    Reminders.notifyOtherTrack(c, "Your girth session is still to run.",
                        m.discreetReminders);
                else if (up.what == UpNext.LENGTH)
                    Reminders.notifyOtherTrack(c, "Your length session is still to run.",
                        m.discreetReminders);
            }
            return;
        }
        /* THE TRAINING ALARM, AND ONLY IT. This tested "not boot and not the measurement
         * alarm", so the TRAINER alarm fell straight through into it - and the schedule's
         * own "Your session is due" fired at the trainer hour as well as its own. Matching
         * the action positively is the form the other three branches already use, and it
         * closes the null/unknown-action leak at the same time. */
        if (Reminders.ACTION_FIRE.equals(action)) {
            // The TRAINING alarm fired. Three gates, all of which must hold, because each
            // is a way an outstanding alarm can have outlived the settings that set it.
            if (m.sched.remind && m.sched.any()
                    && m.sched.isTrainingDay(System.currentTimeMillis())
                    // t10-K (B1): the month-12 break - no session is due for either track.
                    && !(m.trainerEnrolled && MonthBreak.on(m, System.currentTimeMillis())))
                // STAGE H TASK 5 — reword, never suppress, during a trainer deload week
                // (plan round-2 ruling). TrainerTab.inDeloadWeek is false for every
                // non-trainer user, so this is unchanged for the population the feature
                // does not touch.
                Reminders.notifyDue(c, TrainerTab.inDeloadWeek(m, System.currentTimeMillis()),
                    m.discreetReminders);
        }
        if (measFire) {
            // The MEASUREMENT alarm fired. It is set for every day, so the cadence is what
            // decides — asked HERE, at the moment it matters, rather than trusted from
            // whenever the alarm was scheduled. Same belt-and-braces re-check the training
            // path makes: the switch may have gone off, or the cadence may have, while an
            // alarm was already outstanding.
            // THE WEEK POSITION IS PASSED, not defaulted away. The one-argument due()
            // passes -1, and the 1st-and-4th cadence answers -1 with "not due" - so this
            // call could never fire for anybody on that cadence, while the settings switch
            // went on saying the reminder was on. Every other mode ignores the argument, so
            // this changes nothing for them.
            if (m.measRemind && m.meas != null && !"off".equals(m.meas.mode)
                    && m.measLog != null
                    && m.measLog.due(m.meas,
                        // S13 (a): the week of the track Up next would start.
                        m.sessionsThisTrainingWeek(System.currentTimeMillis(),
                            TrainerTab.cadenceTrackNow(m, System.currentTimeMillis()))))
                Reminders.notifyMeasDue(c, m.discreetReminders);
        }
        if (trainerFire) {
            // THE TRAINER ALARM (G7). What it says is decided HERE, at the moment it
            // matters, never trusted from when the alarm was set — the plan can move in
            // between. Both reminders share one alarm and one notification slot, so a
            // day that is both a training day and a deload start says the deload, which
            // is the one that changes what you should do.
            long tnow = System.currentTimeMillis();
            if (m.trainerEnrolled && !MonthBreak.on(m, tnow)) {   // t10-K (B1)
                long[] dl = TrainerTab.deloadDayRange(m);
                long today = Summary.dayNumber(tnow);
                boolean deloadStartsToday = dl[0] > 0L && today == dl[0];
                if (m.trainerRemindDeloadStart && deloadStartsToday) {
                    Reminders.notifyTrainer(c, "Deload week starts today",
                        "Counters are frozen and your streak is protected.", m.discreetReminders);
                // t10 REAL-1: the deload is due and its start not yet chosen - the one thing
                // the plan is waiting on, so it is what the reminder says. Today's session
                // still runs, as the question itself says.
                } else if (m.trainerRemindDeloadStart && TrainerTab.cadenceDeloadDue(m, tnow)) {
                    Reminders.notifyTrainer(c, PlanCards.DELOAD_DUE_TITLE,
                        PlanCards.DELOAD_DUE_REMIND, m.discreetReminders);
                // THROUGH THE PREDICATE THAT ENCODES THE RULING, not the raw flag: the
                // flag alone fires a second "Training day" on a day the schedule reminder
                // has already spoken for.
                } else if (TrainerTab.trainerTrainingDayShouldFire(m) && !deloadStartsToday
                        && m.sched != null && m.sched.trainsOnDay(today)) {
                    Reminders.notifyTrainer(c, "Training day",
                        "Your plan has a session scheduled today.", m.discreetReminders);
                }
            }
        }
        // Either way, and for BOTH alarms, line the next one up with whatever is saved NOW.
        // After a boot that is the whole job (a reboot drops every AlarmManager alarm, both
        // of them); after a fire it is what keeps exactly one of each outstanding.
        Reminders.reschedule(c, m.sched);
        Reminders.rescheduleMeas(c, m);
        Reminders.rescheduleTrainer(c, m);
    }
}
