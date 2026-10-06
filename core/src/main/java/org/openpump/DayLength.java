package org.openpump;

/**
 * HOW LONG THE PLAN'S TRAINING DAY IS, AND WHETHER TO SAY SO (the owner's decision,
 * 2026-09-30): "days of 90 minutes or more: warn and offer alternate days".
 *
 * With the length track on, some setups build days of 90 minutes and more - the longest about
 * 150. SameDay's S07 advisory says so only at START, once a day is already under way. This is
 * the same fact said while it can still be planned around: the Trainer page and the setup's
 * week step show what a day of the plan will take, and offer the week shape that already
 * exists for it - girth and length on alternate days ({@link Schedule#SHAPE_ALTERNATE}).
 * Nothing is changed here or by the notice: only the person's tap changes the week.
 *
 * WHAT A DAY IS. Every routine the plan runs that day, as saved - its warm-ups and rests
 * included, which is what {@link Model#routineSec} counts:
 * <ul>
 * <li>the girth routine (both parts of a split day);</li>
 * <li>the length routine (both parts of a split day);</li>
 * <li>the feeders, {@link Plan#FEEDER_PER_DAY} of them, when the feeder is on.</li>
 * </ul>
 * The feeders are worked out ({@link #parts}) but are NOT in the day's figure (t10 R-60, R-09;
 * device walk M3): they run 4-6 h after the girth session, not as part of it, and the
 * 90-minute check the plan holds at counts girth plus length as run, no feeders
 * (TrainerTab#heldAt90). One figure for the notice, the setup's week step and that check. A
 * track that is resting, or girth while a girth-focus block pauses it, is not in the day.
 *
 * The figure is the plan's routines as written. A both-tracks day's run can still take its
 * girth sets off (S06, RunShape) and skip a second warm-up at START (S04), so the day as run
 * can be shorter. The notice therefore says "about".
 *
 * PURE: the model in, seconds and words out. Asserted in DayLengthTest.
 */
public final class DayLength {
    private DayLength() { }

    /** The minutes a day reaches before the notice shows: the source's figure for a combined
     *  day, the one S07 uses ({@link SameDay#DAY_BUDGET_MIN}). */
    public static final int NOTICE_MIN = SameDay.DAY_BUDGET_MIN;

    /** Whether a day planned as {@code dayPlan} runs girth / length. {@link Schedule#PLAN_ANY}
     *  ("the plan's choice") runs whatever is enrolled, which is both. */
    public static boolean planRunsGirth(int dayPlan) {
        return dayPlan == Schedule.PLAN_ANY || dayPlan == Schedule.PLAN_GIRTH
            || dayPlan == Schedule.PLAN_BOTH;
    }

    public static boolean planRunsLength(int dayPlan) {
        return dayPlan == Schedule.PLAN_ANY || dayPlan == Schedule.PLAN_LENGTH
            || dayPlan == Schedule.PLAN_BOTH;
    }

    /** The saved seconds of one track's plan routines: the day's routine, and the second
     *  part's too where the day is split in two. 0 when nothing is saved. */
    static long trackSec(Model m, Model.TrainerTrackState st) {
        if (m == null || st == null) return 0L;
        long sec = routineSec(m, st.lastMintId);
        if (TrainerTab.partsOf(m, st) == 2) sec += routineSec(m, st.lastMintId2);
        return sec;
    }

    /**
     * t10 R-05/R-06/R-07 - HOW A DAY OF BOTH TRACKS BUILDS ONE TRACK'S SESSION, as
     * {bothTracks, girthAfterLength, skipWarm} for RxBuild.Day#sameDay: the pulls end without a
     * swap or an expansion (R1), girth after length runs without its fatigue block (R4), and
     * the second session of the two, back to back in the person's order, has no warm-up (P4).
     * The one answer the Trainer page's day (#parts) and the setup's week step both take.
     */
    public static boolean[] bothDayFlags(boolean isLength, boolean lengthFirst) {
        return isLength ? new boolean[]{ true, false, !lengthFirst }
                        : new boolean[]{ false, lengthFirst, lengthFirst };
    }

    /** #trackSec on a day of both tracks: the day's first routine as that day runs it
     *  (RxBuild#dayRunSec, #bothDayFlags), a split's second part as saved. */
    static long dayTrackSec(Model m, Model.TrainerTrackState st, boolean isLength, long now) {
        if (m == null || st == null) return 0L;
        Model.Routine r = st.lastMintId == null ? null : m.routine(st.lastMintId);
        boolean[] f = bothDayFlags(isLength, m.rxLengthFirst);
        long sec = r == null ? 0L : RxBuild.dayRunSec(m, r, f[0], f[1], f[2], now);
        if (TrainerTab.partsOf(m, st) == 2) sec += routineSec(m, st.lastMintId2);
        return sec;
    }

    private static long routineSec(Model m, String id) {
        if (id == null || id.length() == 0) return 0L;
        Model.Routine r = m.routine(id);
        return r == null ? 0L : Math.max(0L, m.routineSec(r));
    }

    /** Whether the girth track is in the plan's day at all: enrolled, girth on, and not paused
     *  for a girth-focus block. */
    static boolean girthLive(Model m, long now) {
        return m != null && m.trainerEnrolled && m.trainerGirthOn
            && !TrainerTab.girthPausedNow(m, now);
    }

    /** Whether the length track is in the plan's day: enrolled, on, and not on a dated rest.
     *  A deload week's week off length is not counted out: the notice is about the shape of
     *  the week, and it should not come and go with every fourth week. */
    static boolean lengthLive(Model m, long now) {
        return m != null && m.trainerEnrolled && m.trainerLengthOn
            && !m.trainerLength.resting(now);
    }

    /** Whether the feeders are in a training day: opted in, eligible at the girth level, and
     *  a feeder routine saved. */
    static boolean feederLive(Model m) {
        if (m == null || !m.trainerEnrolled || !m.trainerFeederOptIn) return false;
        if (!Plan.feederEligible(m.trainerGirth.level)) return false;
        return routineSec(m, m.trainerFeederMintId) > 0L;
    }

    /** {girth, length, feeders} seconds of a training day planned as {@code dayPlan}. */
    public static long[] parts(Model m, int dayPlan, long now) {
        long[] out = new long[3];
        if (m == null || dayPlan == Schedule.PLAN_REST) return out;
        if (planRunsGirth(dayPlan) && girthLive(m, now)) out[0] = trackSec(m, m.trainerGirth);
        if (planRunsLength(dayPlan) && lengthLive(m, now)) out[1] = trackSec(m, m.trainerLength);
        /* t10 R-05/R-06/R-07 - A DAY OF BOTH TRACKS RUNS SHORTER THAN ITS SAVED ROUTINES: the
         * pulls end without a swap or an expansion, the second session starts straight after
         * the first with no warm-up, and girth after length runs without its fatigue block,
         * led in as the person chose. Counted as the day runs them, back to back in the
         * person's order (RxBuild#dayRunSec). */
        if (out[0] > 0L && out[1] > 0L) {
            out[1] = dayTrackSec(m, m.trainerLength, true, now);
            out[0] = dayTrackSec(m, m.trainerGirth, false, now);
        }
        if ((out[0] > 0L || out[1] > 0L) && feederLive(m))
            out[2] = Plan.FEEDER_PER_DAY * routineSec(m, m.trainerFeederMintId);
        return out;
    }

    /** The training day: girth and length as {@link #parts} has them. The feeders come hours
     *  later and are not in it (R-60, R-09). */
    public static long daySec(Model m, int dayPlan, long now) {
        long[] p = parts(m, dayPlan, now);
        return p[0] + p[1];
    }

    /** The day's minutes, rounded, as the notice prints them. */
    public static int minutes(long sec) {
        return (int) Math.max(0L, (sec + 30L) / 60L);
    }

    /** Whether a day of {@code sec} reaches the notice: {@link #NOTICE_MIN} minutes or more,
     *  as printed. */
    public static boolean reaches(long sec) {
        return minutes(sec) >= NOTICE_MIN;
    }

    /** The repeating week's longest training day (MON..SUN), the first of equals; -1 when no
     *  training day runs anything. A today-only change is not the week and is not read. */
    public static int longestWeekday(Model m, long now) {
        if (m == null || m.sched == null) return -1;
        int best = -1;
        long bestSec = 0L;
        for (int w = 0; w < Schedule.DAYS; w++) {
            if (!m.sched.trainsOn(w)) continue;
            long s = daySec(m, m.sched.planOn(w), now);
            if (s > bestSec) { bestSec = s; best = w; }
        }
        return best;
    }

    /** The seconds of {@link #longestWeekday}, or 0. */
    public static long longestDaySec(Model m, long now) {
        int w = longestWeekday(m, now);
        return w < 0 ? 0L : daySec(m, m.sched.planOn(w), now);
    }

    /** Whether every training day that runs anything runs as long as {@code sec} - so the
     *  notice can say "each training day" rather than name one. */
    public static boolean everyDayAsLong(Model m, long sec, long now) {
        if (m == null || m.sched == null) return false;
        for (int w = 0; w < Schedule.DAYS; w++) {
            if (!m.sched.trainsOn(w)) continue;
            long s = daySec(m, m.sched.planOn(w), now);
            if (s > 0L && minutes(s) != minutes(sec)) return false;
        }
        return true;
    }

    /** Whether the day runs both tracks - the only day alternate days can shorten. */
    public static boolean runsBoth(Model m, int dayPlan, long now) {
        long[] p = parts(m, dayPlan, now);
        return p[0] > 0L && p[1] > 0L;
    }

    /** Whether the Trainer page shows the notice: the person has not turned the 90-minute
     *  advisory off (Model#dayBudgetAdvisory, the same answer S07 reads), and the longest
     *  training day reaches {@link #NOTICE_MIN}. */
    public static boolean noticeDue(Model m, long now) {
        return m != null && m.dayBudgetAdvisory && reaches(longestDaySec(m, now));
    }

    /** Whether the notice offers the switch: the longest day runs both tracks. */
    public static boolean alternateOffered(Model m, long now) {
        int w = longestWeekday(m, now);
        return w >= 0 && runsBoth(m, m.sched.planOn(w), now);
    }

    /* ---- the switch ---------------------------------------------------------------- */

    /**
     * THE DAYS AN ALTERNATE WEEK RUNS ON, from the days picked - the setup's rule, in one
     * place. Monday, Wednesday and Friday is the combined week's starting week, and alternated
     * it gives girth one day a week, so it becomes Monday to Saturday
     * ({@link Schedule#alternateDays}). Any other days are the person's own and are kept.
     */
    public static boolean[] alternateDaysFrom(boolean[] picked) {
        boolean[] mwf = { true, false, true, false, true, false, false };
        if (picked == null || picked.length != Schedule.DAYS) return Schedule.alternateDays();
        if (java.util.Arrays.equals(picked, mwf)) return Schedule.alternateDays();
        return picked.clone();
    }

    /** The track that leads the week: the person's own order for a day of both
     *  ({@link Model#rxLengthFirst}). */
    public static int alternateLead(Model m) {
        return (m == null || m.rxLengthFirst) ? Schedule.PLAN_LENGTH : Schedule.PLAN_GIRTH;
    }

    /** The days the switch's week runs: Monday to Saturday (K10, K22 - "Alternate days, each
     *  track 3 days a week", whatever is ticked). */
    public static boolean[] switchDays() {
        return Schedule.alternateDays();
    }

    /** The week the switch gives, per weekday: girth Mon/Wed/Fri, length Tue/Thu/Sat
     *  (Schedule#splitPlan), Sunday left at PLAN_ANY. */
    public static int[] alternatePlanFor(Model m) {
        int[] out = new int[Schedule.DAYS];
        boolean lengthFirst = m != null && m.sched != null && m.sched.splitLengthFirst();
        for (int i = 0; i < Schedule.DAYS; i++) out[i] = Schedule.splitPlan(i, lengthFirst);
        return out;
    }

    /**
     * THE SWITCH (t10 K10): Long training days becomes "Alternate days, each track 3 days a
     * week" ({@link Schedule#LONG_SPLIT}). It used to write the alternation into each day's
     * plan; the setting is the rule now, so the days and their plans are left as the person
     * set them and the Undo puts the setting back with them. Called only from the person's
     * tap; the caller saves.
     */
    public static void switchToAlternate(Model m) {
        if (m == null || m.sched == null) return;
        LongDays.choose(m, Schedule.LONG_SPLIT);
    }

    /* ---- its Undo (0.10) ------------------------------------------------------------- */

    /**
     * THE SWITCH'S UNDO: the week exactly as it was before the tap (`was`, taken just before
     * it - days, each day's plan, so the shape and the lead, today's override, the reminder's
     * time and whether it reminds), put back in place. Only while the schedule is still the
     * week the switch wrote (`wrote`): a week changed since - in Settings, or by the setup -
     * is the person's newer choice, and an Undo from the switch's message must not take it
     * back. Returns whether it put it back; the caller saves (schedSaved) and says so.
     */
    public static boolean undoAlternate(Model m, Schedule was, Schedule wrote) {
        if (m == null || m.sched == null || was == null || wrote == null) return false;
        if (!m.sched.sameAs(wrote)) return false;
        m.sched.setFrom(was);
        return true;
    }

    /** What the Undo says when it put the week back. */
    public static final String UNDONE = "Your week is back as it was.";

    /** ...and when the week had changed since the switch, so nothing was put back. */
    public static final String UNDO_STALE =
        "Your week has changed since the switch - nothing was undone. Settings \u203a Training "
        + "schedule can change it.";

    /* ---- the words ----------------------------------------------------------------- */

    /** "Mon length · Tue girth · Wed length" - an alternate week, as the notice previews it. */
    public static String weekLine(boolean[] days, int[] plan) {
        if (days == null || plan == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Schedule.DAYS && i < days.length && i < plan.length; i++) {
            if (!days[i]) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(Schedule.DAY_ABBR[i]).append(' ')
             .append(Schedule.planLabel(plan[i]).toLowerCase(java.util.Locale.US));
        }
        return b.toString();
    }

    /** The notice's headline figure: "Each training day will be about 150 minutes." or
     *  "Your Wednesday training day will be about 150 minutes." */
    public static String headline(int weekday, boolean everyDay, long sec) {
        String who = everyDay || weekday < 0 || weekday >= Schedule.DAYS
            ? "Each training day"
            : "Your " + DAY_NAME[weekday] + " training day";
        return who + " will be about " + minutes(sec) + " minutes.";
    }

    /** What the day is made of: "Girth 39 min and length 55 min, warm-ups and rests
     *  included." - and, where the feeders run, that they are not in it: "The feeders come
     *  later, 4-6 h after the girth session." */
    public static String partsLine(long[] parts) {
        if (parts == null || parts.length < 3) return "";
        java.util.List<String> bits = new java.util.ArrayList<String>();
        if (parts[0] > 0L) bits.add("girth " + minutes(parts[0]) + " min");
        if (parts[1] > 0L) bits.add("length " + minutes(parts[1]) + " min");
        if (bits.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < bits.size(); i++) {
            if (i > 0) b.append(i == bits.size() - 1 ? " and " : ", ");
            b.append(bits.get(i));
        }
        String s = b.toString();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1)
            + ", warm-ups and rests included."
            + (parts[2] > 0L ? " " + FEEDERS_LATER : "");
    }

    /** The feeders are not in the day's figure, and why (R-09). */
    public static final String FEEDERS_LATER =
        "The feeders come later, 4–6 h after the girth session.";

    /** Why it is said, on a day of both tracks. */
    public static final String WHY_BOTH = "The guidance keeps a day of both tracks under "
        + NOTICE_MIN + " minutes, and allows girth and length on alternate days instead.";

    /** Why it is said, where alternate days would not shorten it. */
    public static final String WHY_ONE = "That is past " + NOTICE_MIN + " minutes. Alternate "
        + "days would not shorten it: this day already runs one track.";

    /** The switch's label. */
    public static final String SWITCH_LABEL = "Run girth and length on alternate days";

    /** Under the switch: what it writes, and that nothing has changed yet. */
    public static String switchNote(Model m) {
        return "Your week would be: " + weekLine(switchDays(), alternatePlanFor(m)) + ". "
            + "Nothing changes until you tap. " + LongDays.ROW + " on the Trainer page can "
            + "change it back.";
    }

    /** Said once the switch is made: the week now in force. */
    public static String switchedLine(Model m) {
        if (m == null || m.sched == null) return "";
        return "Alternate days from now: " + LongDays.weekLine(m.sched) + ".";
    }

    /** The setup's week step: the day a combined week runs is both tracks, each as a day of
     *  both builds it (#bothDayFlags); an alternate week's longest is the longer track. The
     *  feeders are not in either (#daySec). */
    public static long setupDaySec(long girthSec, long lengthSec, boolean alternate) {
        long g = Math.max(0L, girthSec), l = Math.max(0L, lengthSec);
        return alternate ? Math.max(g, l) : g + l;
    }

    static final String[] DAY_NAME = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
        "Saturday", "Sunday" };
}
