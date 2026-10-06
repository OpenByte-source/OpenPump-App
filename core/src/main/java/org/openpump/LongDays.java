package org.openpump;

/**
 * LONG TRAINING DAYS - the setting, in words and in the one write every screen makes (t10
 * R-60, R-63; the owner's decision, 1 Oct 2026).
 *
 * The week itself is {@link Schedule}'s: {@link Schedule#longDays} is the choice, and
 * {@link Schedule#planAt}, {@link Schedule#isTrainingDay} and {@link Schedule#countForTrack}
 * read it through {@link Schedule#longDaysInForce}. This class is what the Trainer page row,
 * the setup's week step, the 90-minute card and Settings say about it, and how a choice is
 * written - one place, so the four of them cannot describe the same week differently.
 *
 * ONE TRACK ON, THE SETTING IS HIDDEN and the week is the person's own days (K21):
 * {@link #shown} is the row's rule and Schedule#bothTracks the week's, both from the same
 * three answers ({@link #bothTracksOn}).
 *
 * PURE: the model in, words and a schedule write out. Asserted in LongTrainingDaysTest.
 */
public final class LongDays {
    private LongDays() { }

    /** The row's label, on the Trainer page and in the setup's week step. */
    public static final String ROW = "Long training days";

    /** The four choices, in {@link Schedule}'s order (LONG_SPLIT .. LONG_COMBINED), which is
     *  also the order the row cycles through. */
    public static final String[] LABELS = {
        "Alternate days, each track 3 days a week",
        "Alternate on my days",
        "Same days, stop growing at 90 min",
        "Same days, no limit",
    };

    /** The line under the row, per choice. */
    public static final String[] EFFECTS = {
        "Girth Mon/Wed/Fri, length Tue/Thu/Sat. About an hour a day.",
        "The tracks take turns on your days. Each track moves on with 2 full sessions a week "
            + "(or 3 shorter ones).",
        "Both tracks on your days; holds and strain sets stop growing once the day would pass "
            + Plan.HELD_AT_90_MIN + " min.",
        "Both tracks on your days, as before.",
    };

    /** What "Alternate on my days" says when a track it runs cannot reach 2 days a week. */
    public static final String SHORT_BOTH = "With these days neither track gets 2 days a week, "
        + "so its plan won't move. Choose “" + LABELS[Schedule.LONG_SPLIT]
        + "” or add training days.";

    /** Settings' per-day plan, while an alternate choice decides it. */
    public static final String SET_BY = "Set by Long training days";

    /** The fewest days a training week can count on for a track: two full sessions
     *  (TrainingWeek, W1 - the owner's decision of 2026-10-03; it was 3 days). Fewer, and the
     *  track's weeks never count. */
    public static final int WEEK_DAYS = Plan.TRAINING_WEEK_FULL_SESSIONS;

    /** The label of a value, clamped into the four. */
    public static String label(int longDays) {
        return LABELS[clamp(longDays)];
    }

    /** The effect line of a value, clamped into the four. */
    public static String effect(int longDays) {
        return EFFECTS[clamp(longDays)];
    }

    /** The split week's line when the stored week keeps length first
     *  (Schedule#splitLengthFirst). */
    public static final String SPLIT_LENGTH_FIRST =
        "Length Mon/Wed/Fri, girth Tue/Thu/Sat. About an hour a day.";

    /** {@link #effect(int)} for this schedule: the split week names the days it runs. */
    public static String effect(Schedule s, int longDays) {
        if (clamp(longDays) == Schedule.LONG_SPLIT && s != null && s.splitLengthFirst())
            return SPLIT_LENGTH_FIRST;
        return effect(longDays);
    }

    /** A value's place in {@link #LABELS} - the chosen row of a list of them. */
    public static int index(int longDays) {
        return clamp(longDays);
    }

    /** {@link #effect(Schedule, int)} for every choice, in {@link #LABELS}' order: a list
     *  of them shows each one's effect under its label (polish SYS-2). */
    public static String[] effects(Schedule s) {
        String[] out = new String[LABELS.length];
        for (int i = 0; i < out.length; i++) out[i] = effect(s, i);
        return out;
    }

    /** The next value a tap cycles to. */
    public static int next(int longDays) {
        return (clamp(longDays) + 1) % LABELS.length;
    }

    private static int clamp(int v) {
        return (v < Schedule.LONG_SPLIT || v > Schedule.LONG_COMBINED) ? Schedule.LONG_COMBINED : v;
    }

    /** Whether the plan runs both tracks: enrolled, girth on and length on. */
    public static boolean bothTracksOn(Model m) {
        return m != null && m.trainerEnrolled && m.trainerGirthOn && m.trainerLengthOn;
    }

    /** Whether the Trainer page shows the row: both tracks on (K21 hides it otherwise). */
    public static boolean shown(Model m) {
        return bothTracksOn(m);
    }

    /** Copies the model's facts into its schedule (Schedule#follow). Called wherever they
     *  change; Model#clampAll does it on every load. */
    public static void follow(Model m) {
        if (m == null || m.sched == null) return;
        m.sched.follow(bothTracksOn(m), m.rxLengthFirst);
    }

    /**
     * THE ONE WRITE OF A CHOICE. The value, and - for the two "same days" choices - each
     * day's plan put back to the plan's choice where it is still the alternation an earlier
     * alternate week wrote (Schedule#longDaysFor reads it as one), so "both tracks on your
     * days" is true of the days. A week the person set day by day is theirs and is kept.
     * The days themselves are never touched. The caller saves (schedSaved).
     */
    public static void choose(Model m, int longDays) {
        if (m == null || m.sched == null) return;
        Schedule s = m.sched;
        int v = clamp(longDays);
        s.longDays = v;
        if ((v == Schedule.LONG_CAP90 || v == Schedule.LONG_COMBINED)
                && Schedule.longDaysFor(s.days, s.plan) != Schedule.LONG_COMBINED) {
            for (int i = 0; i < Schedule.DAYS; i++) s.plan[i] = Schedule.PLAN_ANY;
        }
        follow(m);
    }

    /**
     * THE RED LINE (R-60): "Alternate on my days" in force, and a track on that gets fewer
     * than {@link #WEEK_DAYS} days a week from it - its training weeks never count, so its
     * plan never moves. "" when there is nothing to say.
     */
    public static String shortLine(Model m) {
        if (m == null || m.sched == null || !bothTracksOn(m)) return "";
        if (m.sched.longDaysInForce() != Schedule.LONG_ALTERNATE) return "";
        boolean g = m.sched.countForTrack(m.trainerGirthStyle) < WEEK_DAYS;
        boolean l = m.sched.countForTrack(Plan.TRACK_LENGTH) < WEEK_DAYS;
        if (g && l) return SHORT_BOTH;
        if (!g && !l) return "";
        return "With these days " + (g ? "girth" : "length") + " doesn't get 2 days a week, "
            + "so its plan won't move. Choose “" + LABELS[Schedule.LONG_SPLIT]
            + "” or add training days.";
    }

    /**
     * t10 fix (review D row 21) - WHETHER THE TICKED DAYS ARE NOT THE WEEK: "Alternate days,
     * each track 3 days a week" is in force, and it runs Monday to Saturday whatever is ticked.
     * Settings' day toggles are then not tapped (a tap would change nothing the week shows),
     * and the card says why ({@link #daysNote}). Under every other choice the ticks are the
     * week, and they are the person's to change.
     */
    public static boolean daysSetByLongDays(Schedule s) {
        return s != null && s.longDaysInForce() == Schedule.LONG_SPLIT;
    }

    /** What Settings › Training days says while {@link #daysSetByLongDays}: the editor's line,
     *  naming the days each track runs (Schedule#splitLengthFirst); "" otherwise. */
    public static String daysNote(Schedule s) {
        if (!daysSetByLongDays(s)) return "";
        return "Long training days is “" + LABELS[Schedule.LONG_SPLIT] + "”: "
            + (s.splitLengthFirst() ? "length runs Mon/Wed/Fri and girth Tue/Thu/Sat"
                                    : "girth runs Mon/Wed/Fri and length Tue/Thu/Sat")
            + ", whatever is ticked here. These days are used by the other three choices.";
    }

    /** The one visible line Settings › Training days shows while {@link #daysSetByLongDays}
     *  (polish, info moves): who sets the days, and which; "" otherwise. The rest of
     *  {@link #daysNote} goes behind its ⓘ. */
    public static String daysNoteShort(Schedule s) {
        if (!daysSetByLongDays(s)) return "";
        return "Set by " + ROW + " (Trainer › When it runs) — "
            + (s.splitLengthFirst() ? "length Mon/Wed/Fri, girth Tue/Thu/Sat."
                                    : "girth Mon/Wed/Fri, length Tue/Thu/Sat.");
    }

    /**
     * t10 fix (review D deviation 7) - WHAT A RECALIBRATION'S CONFIRM SAYS WHEN IT TURNS LENGTH
     * ON with girth on: the saved Long training days comes into force (it was hidden and
     * acted as "as now" with one track, K21), and a recalibration has no week step to show it.
     * "" when length was on already, stays off, or girth is off (one track).
     */
    public static String lengthOnLine(Model m, boolean lengthOnAfter, boolean girthOnAfter) {
        if (m == null || m.sched == null || m.trainerLengthOn || !lengthOnAfter
                || !girthOnAfter) return "";
        int v = clamp(m.sched.longDays);
        return ROW + " now applies: “" + LABELS[v] + "”. " + effect(m.sched, v)
            + " Change it on the Trainer page.";
    }

    /** The week as Settings' strip and the switch's message print it: "Mon girth · Tue length
     *  · ..." for the week in force. */
    public static String weekLine(Schedule s) {
        if (s == null) return "";
        return DayLength.weekLine(s.daysInForce(), s.planInForce());
    }
}
