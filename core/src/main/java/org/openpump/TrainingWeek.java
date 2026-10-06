package org.openpump;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * WHEN A TRAINING WEEK COUNTS - the one rule every reader of "training weeks" asks (the owner's
 * decision of 2026-10-03, option B). Pressure steps, level gates, the L1/L2 week tables, the
 * length calendar and its slow load step, the fallbacks, the L4 wait after the month-12 break,
 * the deload cadence and the This week cards all read it through TrainerTab's walks, which
 * hand it one week's sessions at a time. Nothing is stored: a week is re-read from the session
 * log every time, so weeks filed before this rule existed are judged by it too.
 *
 * W1 - A TRACK'S WEEK COUNTS when either
 * <ol>
 * <li>its sessions fell on at least {@link Plan#TRAINING_WEEK_MIN_DAYS} days (the rule as it
 *     always was: days, not sessions - two runs in one day are one day), or</li>
 * <li>its sessions fell on at least {@link Plan#TRAINING_WEEK_FULL_SESSIONS} days that really
 *     ran ({@link Summary#trainedDay}: finished, or a minute of real pressure), and together
 *     they delivered at least that many sessions' worth of the plan (2/3 of the 3-day week's
 *     planned volume). One session never counts, whatever its length.</li>
 * </ol>
 *
 * WHAT "A SESSION'S WORTH" IS, from what the app already records per session:
 * <ul>
 * <li>GIRTH, either style: the net minutes at pressure ({@link Model.Sess#netTupSec} - the
 *     counted holds delivered; a traditional session's minutes held at the working pressure)
 *     against the net that day's routine prescribed ({@link Model.Sess#netTargetMin}). That is
 *     THE DAY'S full plan: on a both-tracks day the girth routine already gave its sets to the
 *     length session, so a session that ran what it was given is a whole one, and the sets it
 *     gave up are NOT credited back here (the editor's model, N54). "Finish here" counts what
 *     was delivered.</li>
 * <li>LENGTH: the strain block is not kept as a figure of its own, so the session's delivered
 *     clock ({@link Model.Sess#durSec}) against its routine's clock plan
 *     ({@link Model.Sess#plannedSec}), each session capped at its plan (a pause is not work).</li>
 * <li>A session on a REDUCED day (the gentle return after a week off, a taper's cut: stamped
 *     {@link Model.Sess#returnDay}, or a girth session asked for no net) is measured against
 *     the track's normal full plan, so it is never one of the two full sessions - a return
 *     week counts by its three days, as before, and the deload rhythm after a week off is
 *     unchanged. One whose plan was never recorded (filed before the app kept it) is a whole
 *     session when it finished, none otherwise.</li>
 * </ul>
 * Sessions on one day are added up into that day (a split prescription files two rows for one
 * session), and the week's worth is the sum of its days.
 *
 * W2 - THE PLAN-WIDE WEEK (the deload cadence) counts when every active track's own week counts,
 * or when the person pumped on {@link Plan#TRAINING_WEEK_MIN_DAYS} or more days with each active
 * track trained at least once. With one track active it is that track's week.
 */
public final class TrainingWeek {

    private TrainingWeek() { }

    /** One track's week, as far as the sessions handed in go. */
    public static final class Tally {
        /** Distinct days with a session on the track - rule (a)'s count. */
        public int days;
        /** Distinct days with a session that really ran - rule (b)'s session count. */
        public int ranDays;
        /** Sessions' worth of the plan delivered, summed over the days. */
        public double worth;
        /** Any session at all on the track this week. */
        public int sessions;
        public boolean qualifies;
        /** Two full sessions are in, and the week waits only for the track's last scheduled
         *  day to be done before they count (rule (b)'s timing). */
        public boolean volumeMet;
        /** The filing time of the session that made the week count; 0 when it does not. */
        public long qualifiedAtMs;

        /** Whole full sessions to SHOW: never more than the days that ran, so a single long
         *  session reads as one - which is exactly when rule (b) is met at 2. */
        public int fullShown() {
            int whole = (int) Math.floor(worth + 1e-9);
            return Math.max(0, Math.min(whole, ranDays));
        }

        /** One more session of ANY length, on another day, and the week counts: its third
         *  day (rule (a)). A full session alone is not promised - two full ones count only once
         *  the track's week is done (TrainingWeek#tally). */
        public boolean oneSessionAway() {
            return !qualifies && days >= Plan.TRAINING_WEEK_MIN_DAYS - 1;
        }

        /** Two full sessions are in and wait only for the track's last training day. */
        public boolean waiting() {
            return !qualifies && volumeMet;
        }
    }

    /** The plan-wide week: each active track's tally and the days of all of them. */
    public static final class PlanWeek {
        public boolean girthActive, lengthActive;
        public final Tally girth = new Tally(), length = new Tally();
        /** Distinct days with a session on any trainer track. */
        public int days;
        public boolean qualifies;

        public int activeTracks() { return (girthActive ? 1 : 0) + (lengthActive ? 1 : 0); }

        /** One more session of any length, on another day, and the plan's week counts. With
         *  both tracks on, two days are one session from the third (of the track not yet
         *  trained, if one is missing - W2's three days with each track in them). */
        public boolean oneSessionAway() {
            if (qualifies) return false;
            if (girthActive && !lengthActive) return girth.oneSessionAway();
            if (lengthActive && !girthActive) return length.oneSessionAway();
            return days >= Plan.TRAINING_WEEK_MIN_DAYS - 1;
        }
    }

    /** THE RULE (W1), on the figures a tally holds. */
    public static boolean qualifies(int days, int ranDays, double worth) {
        if (days >= Plan.TRAINING_WEEK_MIN_DAYS) return true;
        return ranDays >= Plan.TRAINING_WEEK_FULL_SESSIONS
            && worth + 1e-9 >= Plan.TRAINING_WEEK_FULL_SESSIONS;
    }

    /**
     * W1 over one track's sessions in one week - already filtered by the caller to the track, the
     * week and its anchor - in any order. {@code lengthTrack} picks the length volume.
     *
     * WHEN RULE (b) MAY COUNT (the owner's decision of 2026-10-03): only from
     * {@code volumeFromMs}, the moment no more of the track's scheduled days are left in the week
     * (TrainerTab#volumeFromMs: its last scheduled day trained, or passed). Never on the second
     * session of a week that still has a third scheduled, so a three-day week counts on its
     * third session exactly as before, and nothing moves sooner. Rule (a) counts the moment it
     * is met (N22). The week is read as at {@code evalMs}.
     *
     * {@link Tally#qualifiedAtMs} is when the week first counted: the session that met the rule,
     * or {@code volumeFromMs} when rule (b) had been met and only waited for the schedule.
     */
    public static Tally tally(Model model, List<Model.Sess> week, boolean lengthTrack,
                              long volumeFromMs, long evalMs) {
        List<Model.Sess> sorted = new ArrayList<Model.Sess>(week);
        sortOldestFirst(sorted);
        Tally t = new Tally();
        List<Model.Sess> sofar = new ArrayList<Model.Sess>();
        for (int i = 0; i < sorted.size(); i++) {
            sofar.add(sorted.get(i));
            fill(model, sofar, lengthTrack, t);
            long at = sorted.get(i).ts;
            if (t.qualifiedAtMs == 0L && qualifiesAt(t, at >= volumeFromMs))
                t.qualifiedAtMs = at;
        }
        if (sorted.isEmpty()) fill(model, sofar, lengthTrack, t);
        boolean open = evalMs >= volumeFromMs;
        t.qualifies = qualifiesAt(t, open);
        t.volumeMet = qualifies(t.days, t.ranDays, t.worth);
        if (t.qualifies && t.qualifiedAtMs == 0L) t.qualifiedAtMs = volumeFromMs;
        return t;
    }

    /** {@link #tally(Model, List, boolean, long, long)} with rule (b) open throughout - a week
     *  read as a whole, after it ended. */
    public static Tally tally(Model model, List<Model.Sess> week, boolean lengthTrack) {
        return tally(model, week, lengthTrack, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    private static boolean qualifiesAt(Tally t, boolean volumeOpen) {
        if (t.days >= Plan.TRAINING_WEEK_MIN_DAYS) return true;
        return volumeOpen && qualifies(t.days, t.ranDays, t.worth);
    }

    /**
     * W2 - the plan-wide week from {@code girth} (either style) and {@code length} sessions, with
     * which tracks were active that week, each track's rule (b) open from its own
     * {@code ...FromMs}, read as at {@code evalMs}. With no track active, the days of both
     * together at {@link Plan#TRAINING_WEEK_MIN_DAYS} (as the plan-wide count always read).
     */
    public static PlanWeek planWeek(Model model, List<Model.Sess> girth, List<Model.Sess> length,
                                    boolean girthActive, boolean lengthActive,
                                    long girthFromMs, long lengthFromMs, long evalMs) {
        PlanWeek p = new PlanWeek();
        p.girthActive = girthActive;
        p.lengthActive = lengthActive;
        Tally g = tally(model, girth, false, girthFromMs, evalMs);
        Tally l = tally(model, length, true, lengthFromMs, evalMs);
        copy(g, p.girth);
        copy(l, p.length);
        HashSet<Integer> days = new HashSet<Integer>();
        for (int i = 0; i < girth.size(); i++)
            days.add(Integer.valueOf(TrainerTab.sessionDay(girth.get(i))));
        for (int i = 0; i < length.size(); i++)
            days.add(Integer.valueOf(TrainerTab.sessionDay(length.get(i))));
        p.days = days.size();
        boolean enoughDays = p.days >= Plan.TRAINING_WEEK_MIN_DAYS;
        if (girthActive && lengthActive) {
            p.qualifies = (g.qualifies && l.qualifies)
                || (enoughDays && g.sessions >= 1 && l.sessions >= 1);
        } else if (girthActive) {
            p.qualifies = g.qualifies;
        } else if (lengthActive) {
            p.qualifies = l.qualifies;
        } else {
            p.qualifies = enoughDays;
        }
        return p;
    }

    /** {@link #planWeek(Model, List, List, boolean, boolean, long, long, long)} read as a
     *  whole week. */
    public static PlanWeek planWeek(Model model, List<Model.Sess> girth, List<Model.Sess> length,
                                    boolean girthActive, boolean lengthActive) {
        return planWeek(model, girth, length, girthActive, lengthActive,
                        Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    private static void copy(Tally from, Tally to) {
        to.days = from.days; to.ranDays = from.ranDays; to.worth = from.worth;
        to.sessions = from.sessions; to.qualifies = from.qualifies;
        to.volumeMet = from.volumeMet;
        to.qualifiedAtMs = from.qualifiedAtMs;
    }

    private static void fill(Model model, List<Model.Sess> week, boolean lengthTrack, Tally t) {
        HashMap<Integer, double[]> perDay = new HashMap<Integer, double[]>();
        HashSet<Integer> ran = new HashSet<Integer>();
        for (int i = 0; i < week.size(); i++) {
            Model.Sess s = week.get(i);
            Integer day = Integer.valueOf(TrainerTab.sessionDay(s));
            double[] d = perDay.get(day);
            // {delivered, planned, a whole session where no plan is known}
            if (d == null) { d = new double[3]; perDay.put(day, d); }
            if (Summary.trainedDay(s)) ran.add(day);
            // A REDUCED DAY (the gentle return, a taper's cut) is measured against the track's
            // normal full plan, which it never reaches by design: it is never one of the two
            // full sessions (it still counts as one of three days). The normal plan is not on
            // the record, so it adds nothing here (the owner's alignment, 2026-10-03).
            if (reducedDay(s, lengthTrack)) continue;
            double[] v = lengthTrack ? lengthVolume(s) : girthVolume(s);
            if (v == null) {
                if (unitWhenUnknown(s, lengthTrack)) d[2] = 1.0;
            } else {
                d[0] += v[0];
                d[1] += v[1];
            }
        }
        double worth = 0.0;
        for (double[] d : perDay.values())
            worth += d[1] > 0.0 ? d[0] / d[1] : d[2];
        t.days = perDay.size();
        t.ranDays = ran.size();
        t.worth = worth;
        t.sessions = week.size();
        t.qualifies = qualifies(t.days, t.ranDays, t.worth);
    }

    /** {delivered, planned} net minutes of a girth session against that day's own plan, or
     *  null when it has no plan with minutes in it to measure against. */
    private static double[] girthVolume(Model.Sess s) {
        if (s.netTargetMin == null) {
            // Filed before sessions kept their target: the routine's under-pressure plan.
            if (s.netTupSec == null || s.plannedTupSec == null
                    || s.plannedTupSec.doubleValue() <= 0.0) return null;
            return new double[]{ s.netTupSec.doubleValue(), s.plannedTupSec.doubleValue() };
        }
        if (s.netTargetMin.doubleValue() <= 0.0 || s.netTupSec == null) return null;
        return new double[]{ Math.max(0.0, s.netTupSec.doubleValue() / 60.0),
                             s.netTargetMin.doubleValue() };
    }

    /** {delivered, planned} clock seconds of a length session, delivered capped at the plan. */
    private static double[] lengthVolume(Model.Sess s) {
        if (s.plannedSec <= 0L) return null;
        double planned = s.plannedSec;
        return new double[]{ Math.min(Math.max(0L, s.durSec), planned), planned };
    }

    /** A session with no measurable plan: a whole session of its own plan when the plan asked
     *  for no minutes and it ran, or - nothing recorded at all - when it finished. */
    private static boolean unitWhenUnknown(Model.Sess s, boolean lengthTrack) {
        return s.completed;
    }

    /** A session run on a reduced day: stamped as a return day (Sess#returnDay), or - girth -
     *  asked for no net minutes, which is what a reduced prescription asks (Q1/Q2). */
    private static boolean reducedDay(Model.Sess s, boolean lengthTrack) {
        if (s.returnDay) return true;
        return !lengthTrack && s.netTargetMin != null && s.netTargetMin.doubleValue() <= 0.0;
    }

    private static void sortOldestFirst(List<Model.Sess> list) {
        // Insertion sort, stable: a week holds a handful of sessions.
        for (int i = 1; i < list.size(); i++) {
            Model.Sess x = list.get(i);
            int j = i - 1;
            while (j >= 0 && list.get(j).ts > x.ts) { list.set(j + 1, list.get(j)); j--; }
            list.set(j + 1, x);
        }
    }
}
