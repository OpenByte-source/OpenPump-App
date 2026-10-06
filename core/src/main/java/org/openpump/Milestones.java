package org.openpump;

import java.util.ArrayList;
import java.util.List;


/**
 * MILESTONES and the WEEKLY INSIGHT — the only two derived-encouragement features in this
 * app, and both are pure so every claim they make is asserted rather than eyeballed.
 *
 * WHAT THIS DELIBERATELY DOES NOT REWARD. Nothing here is earned by running the pump
 * deeper, longer per session, or more often than the user's own schedule asks for. There
 * is no milestone for a peak pressure, for a dose, for a volume, for a "personal best
 * session". Those would be an app built to escalate a physical stimulus applied to a
 * person, dressed as a game, and the whole reason the milestones below are keyed on TIME
 * (hours accumulated, weeks kept) and on EXPANSION (a before/after measurement the user
 * took) is that neither can be gamed by turning a dial up. Consistency and measured
 * outcome are the only two axes.
 *
 * They are also not keyed on how MANY measurements were taken: measuring more often reads
 * noise as progress (the same rule Settings' cadence note states), so rewarding the count
 * would push the user toward exactly the behaviour that corrupts their own data.
 */
public final class Milestones {
    private Milestones() { }

    /* ------------------------------------------------------------------- ids */

    public static final String ID_FIRST_WEEK   = "week1";
    public static final String ID_THIRTY_DAYS  = "days30";
    public static final String ID_HOUR_1       = "hours1";
    public static final String ID_HOUR_10      = "hours10";
    public static final String ID_HOUR_50      = "hours50";
    public static final String ID_FIRST_PAIR   = "pair1";
    public static final String ID_BEST_EXPAND  = "expand";

    /** One earned milestone: a stable id (persisted in Model#seenMilestones so the card
     *  shows once), the headline, and a line saying what earned it. */
    public static final class Earned {
        public final String id, title, detail;
        Earned(String id, String title, String detail) {
            this.id = id; this.title = title; this.detail = detail;
        }
    }

    /* -------------------------------------------------- days kept on schedule */

    /**
     * The longest stretch of consecutive CALENDAR days, anywhere in the record, across
     * which every SCHEDULED day inside it carried a completed session — in days.
     *
     * Measured in calendar days rather than in sessions on purpose: "a full week on
     * schedule" is a claim about a week of the user's life, and a Mon/Wed/Fri user earns
     * it with three sessions while a daily user needs seven. Both kept their schedule for
     * seven days, which is the thing being recognised.
     *
     * TODAY is excluded from the end of the walk when it is a scheduled day with nothing
     * filed yet: the day is not over, so it is neither compliance nor a failure — the same
     * "alive until the day ends" rule the streak follows.
     *
     * 0 when the schedule is empty: with no training days there is no schedule to keep,
     * and a stretch of unscheduled days is not an achievement.
     */
    public static int onScheduleSpanDays(List<Model.Sess> log, Schedule sched, long now) {
        if (sched == null || !sched.any()) return 0;
        List<Long> done = Summary.completedDayNumbers(log);
        if (done.isEmpty()) return 0;
        long first = done.get(0).longValue();
        for (int i = 1; i < done.size(); i++)
            if (done.get(i).longValue() < first) first = done.get(i).longValue();
        long today = Summary.dayNumber(now);
        long end = today;
        if (sched.trainsOnDay(today) && !done.contains(Long.valueOf(today))) end = today - 1;

        int best = 0;
        long start = Long.MIN_VALUE;
        for (long d = first; d <= end; d++) {
            boolean ok = !sched.trainsOnDay(d) || done.contains(Long.valueOf(d));
            if (!ok) { start = Long.MIN_VALUE; continue; }
            if (start == Long.MIN_VALUE) start = d;
            int span = (int) (d - start + 1);
            if (span > best) best = span;
        }
        return best;
    }

    /* ------------------------------------------------------------- expansion */

    /**
     * The largest single before→after EXPANSION in the record — the biggest positive
     * after-minus-before change from a COMPARABLE pair, in cm, or null when no comparable
     * pair has ever been filed.
     *
     * Comparability is the gate, not a nicety: a delta measured under different hold
     * conditions is not a measurement of expansion, and Model.Reading#comparable's verdict
     * is already recorded on the session (Sess#afterComparable). A milestone built on an
     * incomparable pair would be a personal best the user cannot reproduce or trust.
     *
     * Length and girth are both eligible and the larger is taken; which one it was is in
     * the detail line, because "+0.40 cm" without saying of what is not a number.
     */
    public static Double bestExpansionCm(List<Model.Sess> log) {
        Double best = null;
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s == null || s.manual || !s.afterComparable) continue;
            if (s.afterLenCm != null && s.afterLenCm.doubleValue() > 0
                    && (best == null || s.afterLenCm.doubleValue() > best.doubleValue()))
                best = s.afterLenCm;
            if (s.afterGirCm != null && s.afterGirCm.doubleValue() > 0
                    && (best == null || s.afterGirCm.doubleValue() > best.doubleValue()))
                best = s.afterGirCm;
        }
        return best;
    }

    /** Whether any session ever recorded a comparable before/after pair at all — a
     *  measurement was taken at both ends under the same conditions. A NEGATIVE result
     *  still counts: the milestone is for having measured honestly, not for the sign. */
    public static boolean hasComparablePair(List<Model.Sess> log) {
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s == null || s.manual || !s.afterComparable) continue;
            if (s.afterLenCm != null || s.afterGirCm != null) return true;
        }
        return false;
    }

    /* --------------------------------------------------------- personal records */

    /**
     * PERSONAL RECORDS (S5 A, round6-options.html's decisions list — "PR card in
     * Progress > Sessions; lime flash on a new record"), a different KIND of figure from
     * every milestone above. NOT A MILESTONE: this file's own class doc explains why
     * earned() deliberately never rewards a peak pressure, a dose, or "a personal best
     * session" — a card built to escalate a physical stimulus applied to a person,
     * dressed as a game. {@link #longestHoldSec} and {@link #deepestPeakKpa} do not do
     * that. Neither is earned, neither has a badge, neither is ever marked "seen" and
     * withheld from view again, and nothing here nudges the user toward a bigger number
     * next time — they are a plain restatement of two figures the session log already
     * contains, the same way a Progress screen already states a longest streak or a
     * best-expansion figure elsewhere without either being a reward. S5's own locked
     * spec asks a Sessions card to say what the longest hold and the deepest pressure
     * have been; these two answer exactly that question and nothing more.
     */

    /** The longest single session's elapsed duration ever filed, in seconds — the
     *  larger of every {@link Model.Sess#durSec} in the log — or null when the log is
     *  empty or holds no session with a positive duration. Every filed session counts,
     *  manual cycles included: the pump held for exactly as long whether or not a
     *  routine was behind it, and this states what happened, not what earns a badge. */
    public static Long longestHoldSec(List<Model.Sess> log) {
        Long best = null;
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s == null || s.durSec <= 0) continue;
            if (best == null || s.durSec > best.longValue()) best = Long.valueOf(s.durSec);
        }
        return best;
    }

    /** The deepest vacuum any session ever actually telemetered, in kPa — the larger of
     *  every {@link Model.Sess#peakKpa} in the log — or null when the log is empty or
     *  holds no session with a real peak reading. {@link Model.Sess#peakKpa} is already
     *  "null = the run produced no real reading", so this asks nothing new of it. */
    public static Double deepestPeakKpa(List<Model.Sess> log) {
        Double best = null;
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s == null || s.peakKpa == null || s.peakKpa.doubleValue() <= 0) continue;
            if (best == null || s.peakKpa.doubleValue() > best.doubleValue()) best = s.peakKpa;
        }
        return best;
    }

    /**
     * WHETHER THE MOST RECENT SESSION SET A NEW HOLD RECORD — the PR card's one-time
     * lime flash. `log` is read in {@link Model.SessLog#all}'s own order (newest first,
     * `file()` inserts at index 0), so `log.get(0)` is the session that just happened;
     * this asks whether ITS OWN duration beats every session filed before it — strictly,
     * so a tie is not a new record, only a fresh longest.
     *
     * Recomputed fresh from the log every time this is asked, never persisted: unlike
     * S6+'s one-time "GOAL REACHED" card, this is a fact about which session currently
     * sits newest, true for exactly as long as no later session unseats it, so asking
     * again on every render is correct rather than a shortcut around a missing flag.
     */
    public static boolean isNewHoldRecord(List<Model.Sess> log) {
        if (log == null || log.isEmpty()) return false;
        Model.Sess latest = log.get(0);
        if (latest == null || latest.durSec <= 0) return false;
        Long priorBest = longestHoldSec(log.subList(1, log.size()));
        return priorBest == null || latest.durSec > priorBest.longValue();
    }

    /** The deepest-pressure mirror of {@link #isNewHoldRecord} — same order, same
     *  "strictly beats everything before it", same "recomputed, never persisted". */
    public static boolean isNewPeakRecord(List<Model.Sess> log) {
        if (log == null || log.isEmpty()) return false;
        Model.Sess latest = log.get(0);
        if (latest == null || latest.peakKpa == null || latest.peakKpa.doubleValue() <= 0)
            return false;
        Double priorBest = deepestPeakKpa(log.subList(1, log.size()));
        return priorBest == null || latest.peakKpa.doubleValue() > priorBest.doubleValue();
    }

    /* -------------------------------------------------------------- the list */

    /** Every milestone earned as of `now`, in the order they are shown. */
    public static List<Earned> earned(List<Model.Sess> log, Schedule sched, long now) {
        List<Earned> out = new ArrayList<Earned>();
        int span = onScheduleSpanDays(log, sched, now);
        if (span >= 7)
            out.add(new Earned(ID_FIRST_WEEK, "First full week on schedule",
                "Seven days running with every scheduled session done."));
        if (span >= 30)
            out.add(new Earned(ID_THIRTY_DAYS, "30 days on schedule",
                span + " days running with every scheduled session done."));

        long sec = Summary.of(log, now, sched).totalSec;
        if (sec >= 3600L)
            out.add(new Earned(ID_HOUR_1, "First hour under vacuum",
                hours(sec) + " of session time recorded so far."));
        if (sec >= 10L * 3600L)
            out.add(new Earned(ID_HOUR_10, "10 hours under vacuum",
                hours(sec) + " of session time recorded so far."));
        if (sec >= 50L * 3600L)
            out.add(new Earned(ID_HOUR_50, "50 hours under vacuum",
                hours(sec) + " of session time recorded so far."));

        if (hasComparablePair(log))
            out.add(new Earned(ID_FIRST_PAIR, "First before/after pair recorded",
                "A measurement at both ends of one session, taken under the same hold — "
                + "the only kind that can be compared."));
        Double be = bestExpansionCm(log);
        if (be != null)
            out.add(new Earned(ID_BEST_EXPAND, "Your best before→after expansion so far",
                Summary.cm(be.doubleValue()) + " across one session, measured like-for-like."));
        return out;
    }

    /** Everything in {@link #earned} whose id is not already in `seen` — what the one-time
     *  card actually shows. `seen` is persisted (Model#seenMilestones) so the card appears
     *  once; null is treated as "nothing seen yet". */
    public static List<Earned> newlyEarned(List<Model.Sess> log, Schedule sched, long now,
                                            List<String> seen) {
        List<Earned> all = earned(log, sched, now);
        List<Earned> out = new ArrayList<Earned>();
        for (int i = 0; i < all.size(); i++)
            if (seen == null || !seen.contains(all.get(i).id)) out.add(all.get(i));
        return out;
    }

    /** The ids of everything earned — what the caller marks seen after showing the card. */
    public static List<String> ids(List<Earned> es) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; es != null && i < es.size(); i++) out.add(es.get(i).id);
        return out;
    }

    /** "1 h 30 m" — total accumulated time, never a pressure and never a dose. */
    public static String hours(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60;
        return h + " h " + m + " m";
    }

    /* ------------------------------------------------- G1 gauge (Stage B task 6) */

    /**
     * The 5 CATEGORIES Today's "G1" achievement gauge counts against — a grouping of the
     * 7 discrete ids above (declared in the "ids" section at the top of this file) down
     * to 5, because a gauge with 7 slots for 7 ids that are not all independent claims
     * would silently over-represent whichever axis happens to have the most thresholds.
     *
     * THE GROUPING, AND WHY.
     *
     *   1. ID_FIRST_WEEK  — a week kept on schedule, its own category.
     *   2. ID_THIRTY_DAYS — a month kept on schedule, kept SEPARATE from #1 rather than
     *      folded into one "consistency" category. Both are read off the SAME stat
     *      (onScheduleSpanDays) at two thresholds, which could argue for folding them the
     *      way the hour thresholds are folded below — but a week and a month are
     *      different CALENDAR-MEANINGFUL claims about the user's life (this file's own
     *      earned() already gives them wholly separate titles, "First full week on
     *      schedule" vs "30 days on schedule", and separate detail lines), not merely two
     *      checkpoints of one counter read at different granularities. They stay two.
     *   3. ID_HOUR_1, ID_HOUR_10, ID_HOUR_50 — accumulated time under vacuum, folded into
     *      ONE category, earned if ANY of the three is earned. Unlike #1/#2 these three
     *      are not three different claims: they are the identical stat (Summary#of's
     *      totalSec) at three escalating checkpoints with no qualitative change in
     *      meaning between them — "more of the same accumulation", never a different kind
     *      of achievement. Counting all three separately would let ONE underlying number
     *      (elapsed hours) fill 3 of the gauge's 5 slots, silently making raw TIME three
     *      times as influential on the gauge as CONSISTENCY or OUTCOME — which directly
     *      contradicts this file's own stated design (see the class doc above: "the whole
     *      reason the milestones below are keyed on TIME ... and on EXPANSION ...
     *      Consistency and measured outcome are the only two axes").
     *   4. ID_FIRST_PAIR  — a comparable before/after pair was ever recorded: a
     *      measurement-DILIGENCE claim (you measured correctly), independent of what it
     *      found. Its own category.
     *   5. ID_BEST_EXPAND — the best before/after expansion recorded: a measurement-
     *      OUTCOME claim (what the measuring found). Kept distinct from #4 because a user
     *      can satisfy #4 with a null or even a negative result (hasComparablePair is true
     *      for a recorded SHRINK too, see bestExpansionCm/hasComparablePair above); #5
     *      requires an actual positive delta on top of that.
     *
     * 1 (week) + 1 (month) + 1 (hours, folded) + 1 (pair) + 1 (expand) = 5.
     */
    public static final int CATEGORY_TOTAL = 5;

    /**
     * Pure fold over the ids already earned (as returned by {@link #ids} on
     * {@link #earned}) into the 5 categories documented on {@link #CATEGORY_TOTAL} above.
     * A {@link List} rather than a {@link java.util.Set} on purpose: the only operation
     * this does is membership testing, which a List answers exactly as well for 7
     * possible ids, and it composes directly with {@link #ids}'s own return type with no
     * extra collection conversion at the call site.
     *
     * No device state, no `import android` — a fold over already-earned ids, so test.sh
     * compiles it into the desktop self-test and SelfTest can pin the grouping directly.
     */
    public static int earnedCategoryCount(List<String> earnedIds) {
        if (earnedIds == null) return 0;
        int n = 0;
        if (earnedIds.contains(ID_FIRST_WEEK)) n++;
        if (earnedIds.contains(ID_THIRTY_DAYS)) n++;
        if (earnedIds.contains(ID_HOUR_1) || earnedIds.contains(ID_HOUR_10)
                || earnedIds.contains(ID_HOUR_50)) n++;
        if (earnedIds.contains(ID_FIRST_PAIR)) n++;
        if (earnedIds.contains(ID_BEST_EXPAND)) n++;
        return n;
    }

    /**
     * NEXT BADGE — the nearest not-yet-earned milestone and how far it is, as ONE line
     * for Today's combined week/achievements panel.
     *
     * "Nearest" is taken as the next one in {@link #earned}'s OWN progression order
     * (week1, days30, hours1, hours10, hours50, pair1, expand) rather than a cross-axis
     * distance comparison: a schedule milestone is short by a count of DAYS and a time
     * milestone by a count of HOURS, and there is no principled exchange rate between
     * them that would make one truly "nearer" than the other. earned()'s own list is
     * already evidence of a natural progression (each threshold assumes the one before
     * it), so walking it in that order and reporting the first gap is the least arbitrary
     * reading of "nearest" available from what this file already knows.
     *
     * The two measurement milestones (pair1, expand) have no partial-credit distance to
     * report — a comparable pair either was recorded or it was not — so their line names
     * the action rather than a count.
     */
    public static String nextBadgeLine(List<Model.Sess> log, Schedule sched, long now) {
        int span = onScheduleSpanDays(log, sched, now);
        if (span < 7)
            return "FULL WEEK · " + (7 - span) + ((7 - span) == 1 ? " DAY TO GO" : " DAYS TO GO");
        if (span < 30)
            return "30 DAYS · " + (30 - span) + " TO GO";

        long sec = Summary.of(log, now, sched).totalSec;
        if (sec < 3600L)
            return "1 H UNDER VACUUM · " + hours(3600L - sec) + " TO GO";
        if (sec < 10L * 3600L)
            return "10 H UNDER VACUUM · " + hours(10L * 3600L - sec) + " TO GO";
        if (sec < 50L * 3600L)
            return "50 H UNDER VACUUM · " + hours(50L * 3600L - sec) + " TO GO";

        if (!hasComparablePair(log))
            return "FIRST PAIR · RECORD ONE";
        if (bestExpansionCm(log) == null)
            return "BEST EXPANSION · RECORD A COMPARABLE PAIR";
        return "ALL EARNED";
    }

    /* -------------------------------------------------------- weekly insight */

    /**
     * The compact weekly card: this week against last week, on the three axes that are
     * about the user's consistency and their measured outcome — time trained, scheduled
     * days kept, and the average like-for-like before→after change. Nothing about how hard
     * the pump was run.
     *
     * "This week" is the seven days ending today; "last week" the seven before that. Both
     * windows are aligned to today rather than to a calendar Monday, so the comparison is
     * always seven days against the seven immediately before them.
     */
    public static final class Week {
        public long thisSec, lastSec;
        public int thisDone, thisScheduled, lastDone, lastScheduled;
        /**
         * The mean comparable after-minus-before change in cm across the window, PER AXIS,
         * or null when the window holds no comparable pair for that axis — never 0.0, which
         * would claim a measured change of nothing.
         *
         * LENGTH AND GIRTH ARE NEVER POOLED (audit A4). They used to share one running sum
         * and one count, so "0.15 cm on average" was the mean of two quantities measured
         * along different axes — a number with no physical meaning, and one that
         * contradicted every other surface in the app (tag(), bestExpansionCm) that keeps
         * them apart. A week with a length pair and no girth pair now reports the length
         * change and says nothing about girth, rather than halving one into the other.
         */
        public Double thisExpLenCm, lastExpLenCm;
        public Double thisExpGirCm, lastExpGirCm;

        /** The one compact line Today shows. */
        public String line() {
            return "This week " + Milestones.hours(thisSec) + " · " + thisDone + " of "
                 + thisScheduled + " scheduled days";
        }

        /** The detail behind the tap — each axis with last week beside it. */
        public String detail() {
            StringBuilder sb = new StringBuilder();
            sb.append("Time trained: ").append(Milestones.hours(thisSec))
              .append("  (last week ").append(Milestones.hours(lastSec)).append(")\n");
            sb.append("Days on schedule: ").append(thisDone).append(" of ").append(thisScheduled)
              .append("  (last week ").append(lastDone).append(" of ").append(lastScheduled)
              .append(")\n");
            sb.append("Before→after length: ")
              .append(thisExpLenCm == null ? "no comparable pair this week"
                                           : Summary.cm(thisExpLenCm.doubleValue()) + " on average")
              .append("  (last week ")
              .append(lastExpLenCm == null ? "none" : Summary.cm(lastExpLenCm.doubleValue()))
              .append(")\n");
            sb.append("Before→after girth: ")
              .append(thisExpGirCm == null ? "no comparable pair this week"
                                           : Summary.cm(thisExpGirCm.doubleValue()) + " on average")
              .append("  (last week ")
              .append(lastExpGirCm == null ? "none" : Summary.cm(lastExpGirCm.doubleValue()))
              .append(")");
            return sb.toString();
        }
    }

    public static Week week(List<Model.Sess> log, Schedule sched, long now) {
        if (sched == null) sched = Schedule.allDays();
        Week w = new Week();
        long today = Summary.dayNumber(now);
        // [today-6 .. today] and [today-13 .. today-7].
        fill(w, log, sched, today - 6, today, true);
        fill(w, log, sched, today - 13, today - 7, false);
        return w;
    }

    private static void fill(Week w, List<Model.Sess> log, Schedule sched,
                             long lo, long hi, boolean current) {
        long sec = 0;
        double lenSum = 0; int lenN = 0;
        double girSum = 0; int girN = 0;
        List<Long> lit = new ArrayList<Long>();
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s == null || s.manual) continue;      // a manual run is not a routine session
            long d = Summary.dayNumber(s.ts);
            if (d < lo || d > hi) continue;
            sec += Math.max(0L, s.durSec);
            // The SAME trained-day rule the streak uses (Summary.trainedDay) — a stopped
            // session that delivered real pressure counts; the two cards must not disagree.
            if (Summary.trainedDay(s) && !lit.contains(Long.valueOf(d))) lit.add(Long.valueOf(d));
            if (s.afterComparable) {
                if (s.afterLenCm != null) { lenSum += s.afterLenCm.doubleValue(); lenN++; }
                if (s.afterGirCm != null) { girSum += s.afterGirCm.doubleValue(); girN++; }
            }
        }
        int schedDays = 0;
        for (long d = lo; d <= hi; d++) if (sched.trainsOnDay(d)) schedDays++;
        Double lenExp = lenN == 0 ? null : Double.valueOf(lenSum / lenN);
        Double girExp = girN == 0 ? null : Double.valueOf(girSum / girN);
        if (current) {
            w.thisSec = sec; w.thisDone = lit.size(); w.thisScheduled = schedDays;
            w.thisExpLenCm = lenExp; w.thisExpGirCm = girExp;
        } else {
            w.lastSec = sec; w.lastDone = lit.size(); w.lastScheduled = schedDays;
            w.lastExpLenCm = lenExp; w.lastExpGirCm = girExp;
        }
    }

    /** A signed minute-scale change between two totals, for the card's trend chip —
     *  "+12 m" / "−5 m" / "same as last week". */
    public static String timeTrend(long thisSec, long lastSec) {
        long d = thisSec - lastSec;
        if (d == 0) return "same as last week";
        long m = Math.abs(d) / 60;
        return (d > 0 ? "+" : "−") + m + " m vs last week";
    }
}
