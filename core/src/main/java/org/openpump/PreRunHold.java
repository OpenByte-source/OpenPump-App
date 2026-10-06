package org.openpump;

/**
 * D2 - PRESSURE COMMANDED BEFORE THE RUN'S OWN RECORDING: ITS LIMITS, AND ITS SHARE OF THE
 * TWO-HOUR STOP.
 *
 * THE FINDING. The guided start pulls to {@link Model#GUIDED_START_KPA} and waits for the
 * cuff to hold it. After {@link Model#GUIDED_START_WINDOW_MS} it asks "Keep waiting?", and
 * every "Keep waiting" started that window again - so a cuff that never sealed, or a sensor
 * reading 0.0 on one that did, was held for as long as someone kept tapping, or for ever
 * behind a question nobody answered. And none of it counted toward the two-hour stop: the
 * stop counted the run's recorded frames, and this comes before Session#beginRun.
 *
 * THE OWNER'S RULE (the controller's decision):
 *   (a) pressure commanded before the run counts toward Plan#GROSS_CAP_SEC whatever the
 *       sensor reads - the principle of RunEdit#commandingPressure, one step earlier
 *       ({@link Clock}, {@link #sealedForStopSec});
 *   (b) the guided start has a total limit of {@link #GUIDED_LIMIT_MS} from its first pull,
 *       across every "Keep waiting"; at the limit it stops and vents through the normal path
 *       and says why ({@link #guidedVerdict}, {@link #sentence});
 *   (c) a question left unanswered for {@link #UNANSWERED_MS} is a stop: pressure is never
 *       held behind a dialog nobody answers ({@link #unanswered}).
 *
 * PURE, so the arithmetic is pinned by PreRunHoldTest; the Activity keeps the timestamps,
 * the dialog and the one stop path (finishSession), and WiringCheck invariants 58 and 59
 * hold it to asking here.
 */
public final class PreRunHold {
    private PreRunHold() { }

    /** The guided start's own limit: ten minutes from its first pull. Far past any seal
     *  that is going to take (it asks at thirty seconds), and far short of the two hours. */
    public static final long GUIDED_LIMIT_MS = 10L * 60_000L;

    /** How long a question may stand unanswered while pressure may be held: a minute, the
     *  same bound the reconnect prompt already keeps (SessionActivity#RECONNECT_ANSWER_MAX_MS). */
    public static final long UNANSWERED_MS = 60_000L;

    /** What the guided start's wait does now. */
    public static final int WAIT = 0, LIMIT = 1, UNANSWERED = 2;

    /**
     * THE GUIDED START'S WAIT, asked on every tick. `startedAt` is its first pull (the phase's
     * start, which nothing an answer does can move); `askedAt` is when the "Keep waiting?"
     * question went up, 0 while none is. The limit is said in preference to the unanswered
     * question when both are due: it is the more useful reason.
     */
    public static int guidedVerdict(long now, long startedAt, long askedAt) {
        if (startedAt > 0 && now - startedAt >= GUIDED_LIMIT_MS) return LIMIT;
        if (unanswered(now, askedAt)) return UNANSWERED;
        return WAIT;
    }

    /**
     * THE GUIDED START'S TARGET: {@link Model#GUIDED_START_KPA} clamped to the ceiling - the
     * ONE value its pull commands, its screen and Settings show, and its test waits for.
     *
     * The pull, the screen and Settings used min(17 kPa, ceiling) while the test that lets the
     * routine begin compared the reading with 17 kPa as it stands, so under a ceiling below
     * 17 kPa the app's own pull could never pass it: the wait ran to {@link #GUIDED_LIMIT_MS}
     * and vented. One value now, asked here (GuidedStartTargetTest; WiringCheck invariant 127).
     * Commanded pressure is unchanged: the pull was already clamped to the ceiling.
     */
    public static int guidedTargetKpa(int ceilKpa) {
        return RunEdit.clampUpper(Model.GUIDED_START_KPA, ceilKpa);
    }

    /** Whether a reading counts as holding the guided start's target: a fresh reading at or
     *  above {@link #guidedTargetKpa} for the same ceiling. The dwell (two seconds of it in a
     *  row) stays the Activity's.
     *
     *  AND ALWAYS A REAL, POSITIVE PRESSURE. 0.0 is the device saying it is not measuring,
     *  never a vacuum, and nothing at or below it can be holding a cuff at any target. The
     *  ceiling is 7-57 kPa, so the target is never 0 today - but a target of 0 would let "no
     *  measurement" start the routine, and this floor makes that impossible whatever the
     *  ceiling (GuidedStartTargetTest). NaN fails every comparison, so it never counts. */
    public static boolean guidedAtTarget(boolean fresh, double kpa, int ceilKpa) {
        return fresh && kpa > 0.0 && kpa >= guidedTargetKpa(ceilKpa);
    }

    /** Whether a question put at `askedAt` (0 = none is up) has stood a minute unanswered. */
    public static boolean unanswered(long now, long askedAt) {
        return askedAt > 0 && now - askedAt >= UNANSWERED_MS;
    }

    /**
     * WHAT THE TWO-HOUR STOP COUNTS: the pressure commanded before the run, plus the run's
     * own sealed time (Session#sealedForCapSec) once THIS attempt's run has begun. Before
     * that the session still holds the LAST run's frames - it clears them only in beginRun -
     * and counting them let a run that had reached the stop end the next attempt at once.
     */
    public static double sealedForStopSec(long preRunMs, boolean runTracking,
                                          double runSealedSec) {
        return Math.max(0L, preRunMs) / 1000.0 + (runTracking ? Math.max(0.0, runSealedSec) : 0.0);
    }

    /**
     * t10 R-04 (P1) - WHAT THE TWO-HOUR STOP COUNTS NOW: the WHOLE CLOCK. The pressure
     * commanded before the run, plus - once THIS attempt's run has begun - the run's own
     * elapsed time (warm-up, holds, drops, rests and the steps done by hand), never less than
     * its sealed time. A run whose clock reads 2:00:00 stops though only 1:55:00 of it was
     * sealed.
     */
    public static double wholeForStopSec(long preRunMs, boolean runTracking, long runElapsedMs,
                                         double runSealedSec) {
        double run = runTracking
            ? Math.max(Math.max(0L, runElapsedMs) / 1000.0, Math.max(0.0, runSealedSec)) : 0.0;
        return Math.max(0L, preRunMs) / 1000.0 + run;
    }

    /** The one sentence each stop says. `commanded` is false for a guided start in hand-bulb
     *  mode, where the app pulled nothing and has nothing to release. */
    public static String sentence(int why, boolean commanded) {
        String ended = commanded ? "is being released" : "so this start has ended";
        if (why == LIMIT)
            return (commanded ? "The pump" : "The cuff") + " didn't reach pressure in "
                 + (GUIDED_LIMIT_MS / 60_000L) + " minutes, "
                 + (commanded ? "so it " : "") + ended
                 + ". Check the cuff's seal and start again.";
        if (why == UNANSWERED)
            return "Nobody answered for a minute, " + (commanded ? "so the pump " : "") + ended
                 + ". Start again when you're ready.";
        return "";
    }

    /**
     * PRESSURE COMMANDED BEFORE THE RUN, as wall time, whatever the sensor reads - ticked by
     * the run's heartbeat. The stretch since the last tick counts when pressure was commanded
     * at the start of it, so the tick in which a pull ends is counted and nothing after it;
     * {@link #msAt} adds the stretch still running. A gap the phone slept through counts in
     * full while commanding, which is the direction the stop may err in.
     */
    public static final class Clock {
        private long ms, lastAt;
        private boolean on;

        /** A new attempt: nothing commanded yet. */
        public void reset() { ms = 0L; lastAt = 0L; on = false; }

        public void tick(long now, boolean commanding) {
            if (on && lastAt > 0 && now > lastAt) ms += now - lastAt;
            on = commanding;
            lastAt = now;
        }

        public long msAt(long now) {
            return ms + (on && lastAt > 0 && now > lastAt ? now - lastAt : 0L);
        }
    }
}
