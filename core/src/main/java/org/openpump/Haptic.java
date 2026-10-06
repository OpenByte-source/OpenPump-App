package org.openpump;

/**
 * WHICH COUNTDOWN SECOND-MARK SHOULD BUZZ RIGHT NOW — the whole decision behind the run
 * screen's 3/2/1 ticks, kept PURE (no `import android`) so test.sh compiles it and
 * SelfTest can pin its edges. The Android side owns only the Vibrator call.
 *
 * WHY THIS IS NOT INLINE IN tickRun(). The screen's heartbeat is not a metronome: it is
 * a posted Runnable on the main thread, so it fires late under load, can skip a whole
 * second, and can fire twice in quick succession after a layout pass. A "buzz when the
 * countdown text changes" or "buzz every Nth tick" rule therefore double-fires, or
 * silently drops the 2, exactly when the phone is busiest — which on this screen is
 * while a routine is playing. So the decision is made from the WALL CLOCK (the same
 * `presetFireAt - now` the countdown itself is drawn from, never a tick count) plus one
 * piece of state: the mark most recently ticked. That makes it a pure function, and a
 * pure function is a thing a machine can hold to its edges.
 *
 * THE RULE, stated once:
 *   - the candidate mark is the countdown SECOND the user is currently seeing, i.e.
 *     ceil(remainingMs / 1000) — at exactly 3000 ms left the screen reads 0:03, so the
 *     3-mark is due;
 *   - it fires only if it is inside the lead-in (1..3) and STRICTLY DEEPER than the last
 *     mark ticked, so a repeated or late tick within the same second is silent;
 *   - if a slow tick skipped a mark (3000 ms to 900 ms in one go), the mark that fires is
 *     the one now TRUE — 1, not 3, and not both. A buzz is a statement about how much
 *     time is left; replaying a mark that has already passed would be a false one;
 *   - at or past zero nothing ticks. Zero is the advance itself, and that gets the
 *     distinct longer preset-change buzz instead — two different events, two different
 *     feels, never the same one twice.
 *
 * These ticks are about TIME ONLY. Nothing here reads a pressure: a haptic driven by the
 * live reading would be the phone asserting something about a measurement, and a
 * measurement that is jittering or absent (the 0.0 "no reading" case) would buzz noise
 * into the user's hand during a run. The countdown is a fact the app owns; the pressure
 * is not.
 */
public final class Haptic {
    private Haptic() { }

    /** No mark has been ticked yet in this preset. Not a valid mark, so it can never be
     *  confused with one — the marks are 1..3. */
    public static final int NONE = 0;

    /** How many seconds before the end of a preset the ticks start. Three: enough to be a
     *  ready-signal, few enough that it is not a rattle. */
    public static final int LEAD_SECONDS = 3;

    /**
     * The mark to buzz on this tick, or {@link #NONE} for silence.
     *
     * @param remainingMs  wall-clock milliseconds left in the current preset
     *                     (`presetFireAt - now`), which may be zero or negative
     * @param lastTicked   the mark this preset last buzzed, or {@link #NONE}
     */
    public static int markFor(long remainingMs, int lastTicked) {
        // The second the screen shows. Ceiling division, so 3000 ms is 0:03 and 2999 ms
        // still is. Zero and any overdue (negative) remaining land at or below 0 here and
        // are rejected by the same bound that rejects 0:04 - one test, not a special case
        // that no assertion could tell apart from the general one.
        long mark = (remainingMs + 999L) / 1000L;
        if (mark < 1 || mark > LEAD_SECONDS) return NONE;
        int m = (int) mark;
        if (lastTicked != NONE && m >= lastTicked) return NONE;
        return m;
    }
}
