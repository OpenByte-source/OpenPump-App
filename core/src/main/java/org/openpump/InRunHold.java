package org.openpump;

/**
 * THE HOLD BUTTON DURING A ROUTINE HAS THE SAME LIMIT AS EVERY OTHER HOLD (the owner's
 * decision).
 *
 * The run screen's Hold keeps the pump at the pressure the cuff is reading (an equal-setpoint
 * override - SessionActivity#enterHold). It had no limit of its own: only STOP, the link
 * watchdog and the two-hour stop ended it, so a person who pressed Hold and was called away
 * left the cuff under pressure for as long as two hours. It now vents after "Vent any hold
 * after at most" (Model#holdMaxSec, 60-900 s, 5:00 by default) - the limit the measurement
 * holds already obey - counted on the holds' monotonic clock (SessionActivity#holdClock). At
 * the limit the run ends exactly as STOP ends it: through finishSession, which vents through
 * the watched StopWork, files the run as stopped and shows the summary. Nothing is resumed
 * and nothing is offered that would put the cuff back under pressure.
 *
 * The decisions are pure and pinned here (InRunHoldTest); WiringCheck invariants 131 and 132
 * hold the Activity, the run screen and the notification to them.
 */
public final class InRunHold {

    private InRunHold() { }

    /** The setting, held to its own range and to a whole half-minute (Model#roundHoldMaxSec)
     *  - a hand-edited file cannot move it outside, nor onto the pump's own step down. */
    public static int limitSec(int holdMaxSec) {
        return Model.roundHoldMaxSec(holdMaxSec);
    }

    /** The Hold's layout on the wire - SessionActivity#enterHold writes applyOverride(at, at,
     *  255, 1, ...): CHUNK_HOLD_S at the pull, then CHUNK_DIP_S at the override's lower clamp,
     *  a kPa under it. That second is the pump's own step down, every 256 s (StepWindow). */
    public static final int CHUNK_HOLD_S = 255;
    public static final int CHUNK_DIP_S = 1;

    /** Seconds from the Hold's press to its vent: the limit - or, when the limit would fall in
     *  the vent watch's window around one of the pump's own step downs (8:30 is the one
     *  setting that does), a few seconds EARLIER, just before it (StepWindow). Never later. */
    public static int firesAtSec(int holdMaxSec) {
        return StepWindow.fireAtSec(limitSec(holdMaxSec), CHUNK_HOLD_S, CHUNK_DIP_S);
    }

    /** When a Hold pressed at `nowMono` (the holds' clock) vents - firesAtSec after it. */
    public static long endsAt(long nowMono, int holdMaxSec) {
        return nowMono + firesAtSec(holdMaxSec) * 1000L;
    }

    /** The time left before the Hold vents; 0 when none is armed or it has passed. */
    public static long leftMs(long endsAt, long nowMono) {
        return endsAt <= 0 ? 0L : Math.max(0L, endsAt - nowMono);
    }

    /**
     * Whether the Hold's limit acts now: a Hold is up in a live run, its limit is armed and has
     * passed - and the link is not lost. Over a lost link the link-loss auto-stop owns the pump:
     * it has sent the stop and keeps retrying it until the fall is seen, and finishing the run
     * from here would replace that watch with the session's own (the two-hour stop's rule).
     */
    public static boolean limitReached(boolean running, boolean holding, boolean linkLost,
                                       long endsAt, long nowMono) {
        return running && holding && !linkLost && endsAt > 0 && nowMono >= endsAt;
    }

    /** "vents in 4:32" - the measurement holds' words, rounded up so it never reads 0:00
     *  while the hold is still up. */
    /** The words ventsIn starts with - Incognito#runText reads the time after them. */
    public static final String VENTS_IN = "vents in ";

    public static String ventsIn(long leftMs) {
        return VENTS_IN + Model.Fmt.t((Math.max(0L, leftMs) + 999L) / 1000L);
    }

    /** Said once the limit has ended the run - what happened, and nothing the pump has not
     *  yet shown: the vent is commanded, and the summary says when it is seen. */
    public static String limitSentence(int holdMaxSec) {
        return "The hold reached its limit (" + Model.Fmt.t(limitSec(holdMaxSec))
            + "), so the run stopped and the pump is venting.";
    }
}
