package org.openpump;

/**
 * A DAY THAT RUNS BOTH TRACKS - what the second session leaves out, and what the day is told.
 *
 * Every session the plan writes is written for itself: its own warm-up, its own tissue test,
 * its own expansion, its own limits. On a day that runs both tracks that means two of each,
 * back to back, where the guidance's combined day has one prep sequence and one
 * interval-pump block. These are the owner's answers to the trainer study's "same day"
 * group (S04-S10 and S14, decided 2026-09-26), as rules over facts the app already files -
 * which sessions ran today, of which track, and when they ended (TrainerTab reads them).
 *
 * NOTHING HERE RAISES A PRESSURE, ADDS WORK OR REFUSES A START. Each answer either takes
 * something out of the one run it is asked about (and only when the person is asked, for the
 * warm-up and for traction), or puts a line of text in front of them. The saved routines are
 * never touched - see {@link RunShape}, which builds the run.
 *
 * PURE, like {@link UpNext}: numbers and flags in, numbers and flags out, so every rule is
 * asserted in SameDayTest rather than read off a screen.
 */
public final class SameDay {
    private SameDay() { }

    /* ---- S04: the second warm-up ------------------------------------------------------ */

    /**
     * How recently the other track's session must have ended for this one's warm-up to go.
     *
     * THE GUIDANCE IS SILENT ON THIS FIGURE. It says the combined day has one prep sequence
     * and that tissue is still dilated across short rests,
     * and gives no time after which a second warm-up is needed again. t10 R-05 (the owner's
     * P4, 1 Oct 2026): THIRTY minutes, and AUTOMATIC - within it the second session has no
     * warm-up, skipped by the plan, not offered (it used to be an hour, and a question at
     * START). Beyond it both sessions warm up. The rest of the second session stays: length
     * keeps its strain holds and fatigue holds (fix g), girth its fatigue block unless girth
     * follows length (R-07).
     */
    public static final long WARM_SKIP_WINDOW_MS = 30L * 60000L;

    /**
     * Minutes since the other track's session ended, when that is recent enough for this
     * warm-up to go - or -1 when it is not (nothing filed today, longer ago than the window,
     * or a clock that says it ended in the future).
     */
    public static int warmSkipAskMinutes(long otherTrackEndMs, long now) {
        if (otherTrackEndMs <= 0L) return -1;
        long ago = now - otherTrackEndMs;
        if (ago < 0L || ago > WARM_SKIP_WINDOW_MS) return -1;
        return (int) (ago / 60000L);
    }

    /** t10 R-05 (P4) - whether this session's warm-up goes by itself: the other track's
     *  session ended within {@link #WARM_SKIP_WINDOW_MS}. */
    public static boolean warmSkipAuto(long otherTrackEndMs, long now) {
        return warmSkipAskMinutes(otherTrackEndMs, now) >= 0;
    }

    /* ---- S05: one tissue response test a day ------------------------------------------ */

    /**
     * Whether this session's tissue response test is left out because the other track has
     * already run today.
     *
     * The test is a relative index that only means something under identical conditions
     * (Tau.java:15-20): run in the day's second session it measures tissue the first has
     * already expanded, and the trend cannot tell (it compares only pressure, speed and
     * duration). So the day's first session keeps its test and the second does not - whether
     * or not the first had one, because the reason is the second session's tissue, not the
     * count. The owner's decision of 2026-09-26; the test itself is the app's own, not the
     * material's.
     */
    public static boolean tissueTestSkipped(boolean otherTrackRanToday, boolean testWouldRun) {
        return otherTrackRanToday && testWouldRun;
    }

    /* ---- S06: one day's expansion ----------------------------------------------------- */

    /**
     * How many sets of interval work a girth session gives up on a both-tracks day: the
     * length session's expansion coda ({@link RxBuild#CODA_SETS}, 5 x 2 minutes) stands in for
     * them. The guidance's combined day lists a single interval-pump block; the owner's
     * decision of 2026-09-26 keeps the coda and takes the sets off the
     * girth work.
     */
    public static final int GIRTH_SETS_OFF = RxBuild.CODA_SETS;

    /**
     * The sets actually taken off a girth session of {@code workSets} sets - never all of
     * them. t10 R-06 (R1, the owner's 2A): only where the length session is expansion only (no
     * length cylinder) - with one, the length session drops its expansion on a day of both
     * tracks instead, and girth gives up nothing (TrainerTab#dayChoice decides). At Level 1's first weeks the whole prescription is five sets, and a session with
     * no work left in it would still warm up, hold a retention and file as a training day.
     *
     * THE FLOOR OF ONE IS NOT IN THE GUIDANCE OR THE DECISION: the combined table would have
     * no girth block at all there. One set is kept so the session is still the session it
     * says it is (decided while building 0.10, 2026-09-26, and reported to the owner).
     */
    public static int girthSetsOff(int workSets) {
        return Math.max(0, Math.min(GIRTH_SETS_OFF, workSets - 1));
    }

    /**
     * Whether today is a day on which the girth session gives up its sets: a length session
     * has already been filed today, or the day's plan says both tracks and the length track
     * is running (not resting, and not holding a girth-focus block - which pauses girth
     * instead). TrainerTab#bothTracksToday says why "whatever you are enrolled in" is not
     * enough on its own.
     */
    public static boolean bothTracksDay(boolean lengthFiledToday, boolean dayWantsLength,
                                        boolean lengthLive) {
        return lengthFiledToday || (dayWantsLength && lengthLive);
    }

    /* ---- S07: the day's budget -------------------------------------------------------- */

    /**
     * The combined day the guidance asks to stay under: under ninety minutes of session time
     * when length and girth are combined.
     *
     * t10: the day is counted as it runs - with a length cylinder the pulls end without their
     * expansion (R-06), the second session runs with no warm-up within the half hour (R-05),
     * and girth after length without its fatigue block (R-07): the run START builds is the one
     * counted (RunShape, DayLength#parts).
     *
     * WHAT COUNTS is the routine clock of the girth and length sessions filed today plus the
     * one about to start - the guidance counts session time, and the routine clock is what the
     * app calls that. Feeders are left out: the guidance puts them apart from the main work,
     * as extra volume. Nothing is stopped: it is an advisory line, and the
     * owner's decision of 2026-09-26 lets the person turn it off (Model#dayBudgetAdvisory).
     */
    public static final int DAY_BUDGET_MIN = 90;

    /** Whether starting a session of {@code plannedSec} takes a day that has already run
     *  {@code doneSec} past the budget. Only a COMBINED day: with nothing filed yet, one
     *  session's length is its own limits' business (the 40-minute and two-hour ones). */
    public static boolean overBudget(long doneSec, long plannedSec) {
        if (doneSec <= 0L) return false;
        return doneSec + Math.max(0L, plannedSec) > DAY_BUDGET_MIN * 60L;
    }

    /** "45 min", "1 h 30 min" - a day's minutes, rounded to the minute. */
    public static String minutes(long sec) {
        long m = Math.max(0L, (sec + 30L) / 60L);
        if (m < 60L) return m + " min";
        long h = m / 60L, r = m % 60L;
        return h + " h" + (r > 0L ? " " + r + " min" : "");
    }

    /** S07 - the day's figure, as Today and the pre-run box print it. */
    public static String budgetLine(long doneSec, long plannedSec) {
        if (plannedSec <= 0L) return "Today so far: " + minutes(doneSec) + " of sessions.";
        return "Today: " + minutes(doneSec) + " so far — about "
            + minutes(doneSec + plannedSec) + " with this one.";
    }

    /** S07 - the advisory, when {@link #overBudget} and the person has not turned it off. */
    public static final String BUDGET_ADVISORY = "That takes today past " + DAY_BUDGET_MIN
        + " minutes. The guidance keeps a day of both tracks under " + DAY_BUDGET_MIN
        + " — nothing is stopped.";

    /* ---- S08: girth before length ----------------------------------------------------- */

    /**
     * Whether a length session that pulls starts after a girth session today - the order the
     * guidance advises against (length first, then girth; or length in the morning and girth
     * in the evening). The traction load is worked out from the
     * RESTING erect girth (Traction.java:9-14), and a girth session leaves the girth 3-8 %
     * bigger, as the guidance says, so the same pressure pulls about 6-17 % harder
     * than the governed figure. What the guidance does NOT say is what to do about it; the
     * owner's decision of 2026-09-26 is a warning and the choice of expansion only.
     */
    public static boolean girthFirstWarning(boolean girthFiledToday, boolean lengthPulls) {
        return girthFiledToday && lengthPulls;
    }

    /** S08 - the one line said before a length session that pulls after girth. */
    public static final String GIRTH_FIRST_WARNING = "Girth ran first today. The "
        + "guidance puts length first, before girth work has swollen the tissue. You can "
        + "run the expansion only today.";

    /** S08 - the order setting's note: the default is the guidance's, not a preference. */
    public static final String ORDER_NOTE = "The guidance puts length first on a day that runs "
        + "both, and length in the morning with girth in the evening, before girth work has "
        + "swollen the tissue.";

    /* ---- S09: the gap after a pull ---------------------------------------------------- */

    /** Numbness after vacuum hanging should clear within forty minutes, as the guidance
     *  says - the same figure the "After the pull" card asks about. */
    public static final long PULL_GAP_MS = Plan.NUMBNESS_CLEAR_MIN * 60000L;

    /**
     * Until when the next track is shown as "from HH:MM" after a session that pulled: forty
     * minutes after it ended, or not at all once the person has answered "It cleared". 0 when
     * there is nothing to wait for. A SOFT gap (the owner's decision of 2026-09-26): START is
     * always allowed, this only moves what Today says.
     */
    public static long pullGapUntil(long lastPullEndMs, boolean clearedSaid, long now) {
        if (lastPullEndMs <= 0L || clearedSaid) return 0L;
        long until = lastPullEndMs + PULL_GAP_MS;
        return until > now ? until : 0L;
    }

    /* ---- S10: the feeder's gap, from the main session too ----------------------------- */

    /**
     * When the next feeder may start: four hours after the last feeder AND four hours after
     * today's GIRTH session ended - the guidance puts feeders four to six hours away from the
     * main session, and the gap used to be measured between feeders
     * only, so the first was "due now" the moment the main work was filed. t10 R-09 (A6): the
     * feeders follow the girth work, on girth days only - measured from the girth session's
     * end, not any session's (the caller passes it).
     *
     * 0 means now. {@link Long#MAX_VALUE} means today's feeders are done. A time in the
     * future is a wait to SHOW - the owner's decision of 2026-09-26 lets a tap override it.
     */
    public static long feederFromMs(int feedersToday, long lastFeederMs, long girthEndMs) {
        if (feedersToday >= Plan.FEEDER_PER_DAY) return Long.MAX_VALUE;
        long a = (feedersToday > 0 && lastFeederMs > 0L) ? lastFeederMs + Plan.FEEDER_MIN_GAP_MS : 0L;
        long b = girthEndMs > 0L ? girthEndMs + Plan.FEEDER_MIN_GAP_MS : 0L;
        return Math.max(a, b);
    }

    /* ---- G1: the girth before reading, taken before the length session ---------------- */

    /**
     * WHETHER A LENGTH SESSION'S START ASKS FOR THE DAY'S GIRTH BEFORE READING (the owner's
     * decision, 2026-10-01). On a day that runs both tracks with length first, the girth
     * session's own before reading is taken after the length coda has expanded the tissue,
     * so it is left out of the yield streak (TrainerTab#baselineAfterOtherWork) - every one
     * of them, on such a week, and the yield rule never ran. So when a measurement is due
     * today - at this session or at the girth session after it - the before reading is asked
     * here, before the length work, and the girth session uses it. The order of the sessions
     * does not change.
     *
     * Only where the before reading here measures girth at all ({@code beforeMeasuresGirth}:
     * the session screens' at-rest methods are length methods, so it is the standardised
     * reading under the hold that carries a girth). Skipping it leaves the day as it was.
     */
    public static boolean girthBeforeFirst(boolean lengthStarting, boolean girthLaterToday,
                                           boolean beforeMeasuresGirth, boolean dueNow,
                                           boolean dueAtGirth) {
        return lengthStarting && girthLaterToday && beforeMeasuresGirth
            && (dueNow || dueAtGirth);
    }

    /** G1 - what the before screen says when it is also the girth session's before. */
    public static final String GIRTH_BEFORE_FIRST_NOTE = "Girth runs after this session "
        + "today. A girth measured after the length work reads low, so this before reading is "
        + "your girth session's too.";

    /** G1 - what START says when a girth session uses the before reading taken earlier. */
    public static final String GIRTH_BEFORE_USED = "Using your before reading from ahead of "
        + "the length session";

    /* ---- S14: a girth-focus block pauses the girth track ------------------------------ */

    /**
     * Whether the girth track is paused because the length track is running a girth-focus
     * block. The guidance's girth focus is a SWITCH - drop the strain sets and double the
     * pumping - and the app doubles the length
     * session's expansion for the block, so a girth session beside it would be two girth-type
     * sessions a day. The owner's decision of 2026-09-26 pauses the girth track for the block.
     */
    public static boolean girthPaused(boolean lengthLive, boolean lengthInGirthFocus) {
        return lengthLive && lengthInGirthFocus;
    }

    /** S14 - what a girth session started during the block says in the pre-run box. */
    public static String girthPausedLine(String untilLabel) {
        return "The girth track is paused for the girth-focus block"
            + (untilLabel == null || untilLabel.length() == 0 ? "" : " (until " + untilLabel + ")")
            + " — the length session's doubled expansion is the girth work. This session "
            + "runs as an extra.";
    }

    /** Whether a paused block touches the week [weekStartMs, weekEndMs) - such a week is not
     *  one of MISSED girth sessions, any more than a deload week is. */
    public static boolean pauseTouchesWeek(long pauseFromMs, long pauseUntilMs,
                                           long weekStartMs, long weekEndMs) {
        if (pauseUntilMs <= 0L || pauseFromMs <= 0L) return false;
        return pauseFromMs < weekEndMs && pauseUntilMs > weekStartMs;
    }
}
