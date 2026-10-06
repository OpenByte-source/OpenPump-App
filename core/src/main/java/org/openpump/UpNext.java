package org.openpump;

/**
 * WHAT TO RUN NEXT - one answer, derived rather than remembered.
 *
 * The app knew a great deal about what a person should do today and said none of it on the
 * screen where they press START. Today showed whatever routine was last SELECTED, which on
 * a both-tracks day is the one they already finished, and after a session it still showed
 * the session they had just done. The plan wrote the routines and the schedule said which
 * day was which, and nothing joined the two.
 *
 * NOTHING NEW IS PERSISTED. Every input is already stored for its own reasons - the week's
 * plan, whether the length track is on, which tracks have sessions filed under today's day
 * key, the feeder's own gap clock - so the answer is recomputed on every render and cannot
 * go stale, be orphaned by a deleted routine, or survive a day it was written for. A queue
 * would have to be maintained; this cannot be wrong for longer than one render.
 *
 * PURE, so test.sh compiles it into the desktop self-test: the ordering below is asserted
 * rather than eyeballed on a device.
 */
public final class UpNext {

    /** Nothing outstanding - the day's work is done, or there is no plan to run. */
    public static final int NOTHING = 0;
    public static final int GIRTH = 1;
    public static final int LENGTH = 2;
    /** The feeder, which is only ever offered AFTER the day's real work (guide: extra
     *  volume BETWEEN sessions, never instead of one). */
    public static final int FEEDER = 3;
    /** Something was left unfinished today. It outranks everything: the day's own work is
     *  not done, and starting a second routine on top of an abandoned one is how somebody
     *  ends up doing two half-sessions and counting neither. */
    public static final int REMAINDER = 4;

    public final int what;
    public final String why;
    /**
     * 0.10 (S09, S10) - WHEN the answer is for, when that is not now: epoch ms, or 0 for now.
     *
     * A SOFT WAIT, never a gate (the owner's decisions of 2026-09-26). The track is still
     * named and its START still works; Today prints "from HH:MM" beside it and {@link
     * #fromWhy} says why. Forty minutes after a session that pulled, for the next track (the
     * numbness window, SameDay#PULL_GAP_MS); four hours after the last session, for a feeder
     * (SameDay#feederFromMs).
     */
    public final long fromMs;
    public final String fromWhy;

    private UpNext(int what, String why) { this(what, why, 0L, ""); }

    private UpNext(int what, String why, long fromMs, String fromWhy) {
        this.what = what; this.why = why;
        this.fromMs = Math.max(0L, fromMs);
        this.fromWhy = fromWhy == null ? "" : fromWhy;
    }

    public boolean any() { return what != NOTHING; }

    /** Whether the answer is waiting for a time - "from HH:MM" rather than now. */
    public boolean waits() { return fromMs > 0L; }

    /** Why a next track waits after a pull. */
    public static final String WHY_PULL_GAP =
        "numbness after a pull should clear within " + Plan.NUMBNESS_CLEAR_MIN + " minutes";
    /** Why a feeder waits. */
    public static final String WHY_FEEDER_GAP =
        "a feeder goes 4–6 h after the girth session";

    /** The feeder's spacing rule in one sentence (polish NEW-16) - the ⓘ behind the feeder's
     *  time on Today, and the Trainer's feeder card, say it the same way. */
    public static final String FEEDER_RULE =
        "Feeders go 4–6 h after the day’s main session. Starting sooner is your call.";
    /** The ⓘ sheet's title for {@link #FEEDER_RULE}. */
    public static final String FEEDER_RULE_TITLE = "When the feeder runs";
    /** Before its window the feeder's button is the quiet one, and says so (polish NEW-16). */
    public static final String FEEDER_EARLY = "Start the feeder early";

    /**
     * THE FEEDER'S TIME AS THE HEADLINE before its window (polish NEW-16): "Feeder from 10:14 ·
     * in 3 h 50 min". `clock` is the time as the screen writes it; `inMs` how long until then.
     * Display only - the timing rule is SameDay's and is not touched here.
     */
    public static String feederFromLine(String clock, long inMs) {
        String in = waitWords(inMs);
        return "Feeder from " + clock + (in.length() == 0 ? "" : " · in " + in);
    }

    /** "3 h 50 min", "1 h", "25 min", "1 min" - a wait, rounded up to the minute; "" when
     *  there is none left. */
    public static String waitWords(long ms) {
        if (ms <= 0L) return "";
        long min = (ms + 59999L) / 60000L;
        long h = min / 60L, m = min % 60L;
        if (h == 0L) return m + " min";
        return m == 0L ? h + " h" : h + " h " + m + " min";
    }

    /**
     * @param dayPlan       {@link Schedule#planAt} for today.
     * @param lengthOn      whether the length track is enrolled at all.
     * @param girthDone     a girth-track session is already filed under today.
     * @param lengthDone    a length-track session is already filed under today.
     * @param lengthFirst   the user's order for a both-tracks day.
     * @param feederReady   the feeder is eligible AND its gap has elapsed.
     * @param remainder     an unfinished session from today is still outstanding.
     */
    public static UpNext pick(int dayPlan, boolean lengthOn,
                              boolean girthDone, boolean lengthDone,
                              boolean lengthFirst, boolean feederReady,
                              boolean remainder) {
        return pick(dayPlan, lengthOn, girthDone, lengthDone, lengthFirst, feederReady,
                    remainder, true);
    }

    /**
     * @param feederOnRestDays S16 (the owner's decision, 2026-09-26): whether the feeder is
     *        offered on a rest day at all. The guidance differs with itself - its girth
     *        routines place feeder sets on training days, while elsewhere it allows light
     *        pumping at reduced pressure on rest days - so it is the person's setting,
     *        {@link Model#feederRestDays}, false by default. The seven-argument form above
     *        passes true, the answer it always gave, for the callers that have no model.
     */
    public static UpNext pick(int dayPlan, boolean lengthOn,
                              boolean girthDone, boolean lengthDone,
                              boolean lengthFirst, boolean feederReady,
                              boolean remainder, boolean feederOnRestDays) {
        return pick(dayPlan, lengthOn, girthDone, lengthDone, lengthFirst, remainder,
                    false, 0L, feederReady, 0L, feederOnRestDays);
    }

    /**
     * 0.10 - the same answer, with what a both-tracks day adds (SameDay decides each input).
     *
     * @param girthPaused     S14: a length girth-focus block has paused the girth track, so
     *                        the day does not want girth whatever the planner says.
     * @param pullGapUntilMs  S09: until when the NEXT track waits after a session that pulled
     *                        (0 = no wait).
     * @param feederOn        the feeder is eligible, has a routine, and today's are not done.
     * @param feederFromMs    S10: from when the feeder is due (0 = now). A feeder that waits
     *                        is still NAMED, with its time, once the day's work is done - the
     *                        wait is shown and a tap overrides it.
     * @param feederOnRestDays S16, as above.
     */
    public static UpNext pick(int dayPlan, boolean lengthOn,
                              boolean girthDone, boolean lengthDone,
                              boolean lengthFirst, boolean remainder,
                              boolean girthPaused, long pullGapUntilMs,
                              boolean feederOn, long feederFromMs,
                              boolean feederOnRestDays) {
        return pick(dayPlan, lengthOn, girthDone, lengthDone, lengthFirst, remainder,
                    girthPaused, pullGapUntilMs, feederOn, feederFromMs, feederOnRestDays, true);
    }

    /**
     * ...and `girthFinished`: today's girth session finished, or ended at its target (t10 device
     * walk). A feeder after a girth session stopped part-way is still named, but not as "the
     * work is done" - the work was not done.
     */
    public static UpNext pick(int dayPlan, boolean lengthOn,
                              boolean girthDone, boolean lengthDone,
                              boolean lengthFirst, boolean remainder,
                              boolean girthPaused, long pullGapUntilMs,
                              boolean feederOn, long feederFromMs,
                              boolean feederOnRestDays, boolean girthFinished) {
        if (remainder)
            return new UpNext(REMAINDER, "you stopped part-way through today");

        /* WHICH TRACKS THE DAY WANTS. PLAN_ANY defers to enrolment rather than inventing a
         * preference - it is the migration value, and on an unplanned day the honest answer
         * is "whatever you are enrolled in", which is what the app did before the planner. */
        boolean wantG, wantL;
        switch (dayPlan) {
            case Schedule.PLAN_GIRTH:  wantG = true;  wantL = false;    break;
            case Schedule.PLAN_LENGTH: wantG = false; wantL = lengthOn; break;
            case Schedule.PLAN_BOTH:   wantG = true;  wantL = lengthOn; break;
            // A4 - a rest chosen just for today wants neither, and says so rather than
            // falling through to enrolment the way an unplanned day does.
            case Schedule.PLAN_REST:   wantG = false; wantL = false;    break;
            default:                   wantG = true;  wantL = lengthOn; break;
        }

        /* S14 - A GIRTH-FOCUS BLOCK PAUSES THE GIRTH TRACK. The length session doubles its
         * expansion for the block, which IS the girth work of those weeks; offering a girth
         * session beside it would be two girth-type sessions a day (SameDay#girthPaused). */
        boolean pausedHere = girthPaused && wantG;
        if (girthPaused) wantG = false;

        boolean gLeft = wantG && !girthDone;
        boolean lLeft = wantL && !lengthDone;
        // S09 - the next track after a pull waits, softly. Only a real track is ever moved.
        long gap = pullGapUntilMs > 0L ? pullGapUntilMs : 0L;
        String gapWhy = gap > 0L ? WHY_PULL_GAP : "";

        if (gLeft && lLeft) {
            // BOTH STILL TO DO. The order is the user's; the reason says the other is
            // coming so that finishing the first does not read as finishing the day.
            return lengthFirst
                ? new UpNext(LENGTH, "length first today, then girth", gap, gapWhy)
                : new UpNext(GIRTH,  "girth first today, then length", gap, gapWhy);
        }
        // ONE LEFT, and if the other is already filed the reason says so - "the girth half
        // is done" is the sentence that makes a second routine on the same day make sense.
        if (gLeft) return new UpNext(GIRTH, wantL && lengthDone
            ? "the length half is done — girth is left" : "girth is what today is for",
            gap, gapWhy);
        if (lLeft) return new UpNext(LENGTH, wantG && girthDone
            ? "the girth half is done — length is left"
            : pausedHere
              ? "girth is paused for the girth-focus block — length is what today is for"
              : "length is what today is for", gap, gapWhy);

        /* THE DAY'S WORK IS DONE. Only now is the feeder offered - never alongside girth or
         * length, which is this method's own ordering above - and on a rest day only when the
         * person has said feeders run on rest days (S16).
         *
         * t10 R-09 (A6) - AND ONLY ON A GIRTH DAY: the app's `feederOn` says today's girth
         * session ran (TrainerTab#feederOn), so a day of length only has no feeder; and "feeder
         * on rest days too" is disabled - the app passes it as false
         * (TrainerTab#feederRestDaysOn). The pure rule here is unchanged for its other callers.
         *
         * S10 (0.10): its gap now runs from the main session too - four hours from the last
         * feeder AND from the end of today's last girth or length session (SameDay#feederFromMs;
         * it used to be feeder-to-feeder only, so the first was due the instant the tracked
         * work was done). While that gap has not run the feeder is named WITH its time, never
         * as due: the time is shown, and a tap on it is the person's override. */
        if (feederOn && (feederOnRestDays || dayPlan != Schedule.PLAN_REST)) {
            String done = girthFinished ? "the work is done \u2014 " : "";
            return feederFromMs > 0L
                ? new UpNext(FEEDER, done + "a feeder is next", feederFromMs, WHY_FEEDER_GAP)
                : new UpNext(FEEDER, done + "a feeder is due");
        }
        if (pausedHere && !wantL)
            return new UpNext(NOTHING, "the girth track is paused for the girth-focus block");
        return new UpNext(NOTHING, "today’s work is done");
    }

    /** The track constant this answer names, or {@link Model#TRAINER_TRACK_NONE}-style 0
     *  for the answers that are not a plan track. */
    public static int trackOf(int what, int girthStyleTrack) {
        switch (what) {
            case GIRTH:  return girthStyleTrack;
            case LENGTH: return Plan.TRACK_LENGTH;
            case FEEDER: return Plan.TRACK_FEEDER;
            default:     return 0;
        }
    }
}
