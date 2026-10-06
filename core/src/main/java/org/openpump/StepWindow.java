package org.openpump;

/**
 * (the safety review of the in-run Hold's limit, follow-up) A HOLD'S LIMIT NEVER FIRES IN THE
 * VENT WATCH'S WINDOW AROUND ONE OF THE PUMP'S OWN STEP DOWNS.
 *
 * A hold longer than one preset can carry (Proto#WIRE_HOLD_MAX) is a preset the pump repeats:
 * `holdS` at the pull, then `dipS` at its lower setpoint. When that lower setpoint is below the
 * upper one - the run's Hold, whose override is clamped a kPa under the pull
 * (RunEdit#clampLower) - the pump itself lets the cuff fall that kPa for that second, at
 * holdS, holdS + period, holdS + 2 x period... (255, 511, 767 s for the run's Hold). A limit
 * that fires just before one sends its StopWork into it, and if that stop is lost the vent
 * watch sees the step's own fall. A one-kPa fall used to pass the watch's fall test; since
 * Session#VENT_FALL_EVIDENCE_MIN_KPA (2.5 kPa) it does not, on any attempt. This stays as the
 * second line: the first window never holds the step at all.
 *
 * So a limit whose moment falls in (step - BEFORE_S, step + AFTER_S] fires at step - BEFORE_S
 * instead - just before the window. EARLIER, NEVER LATER: the hold is never made longer.
 * BEFORE_S is the watch's first window (SessionActivity's VENT_WATCH_WINDOW_MS, 6 s) and one
 * second more, so that window closes before the step; AFTER_S is the step's own second and one
 * more. A hold whose setpoints are equal (every measurement hold, the guided start, the seal
 * check, the assessment) has no step, and its limit is left alone.
 *
 * StepWindowTest pins the times; WiringCheck invariant 135 holds the watch's window, the run's
 * Hold's layout and the equal-setpoint holds to this.
 */
public final class StepWindow {

    private StepWindow() { }

    /** How far before a step the limit's moment must be: the watch's 6 s window + 1 s. */
    public static final int BEFORE_S = 7;
    /** How far after a step the window reaches: the step's own second + 1 s. */
    public static final int AFTER_S = 2;

    /**
     * The moment, in whole seconds from the hold's own start, at which a hold that repeats
     * `holdS` at the pull and `dipS` below it vents for a limit of `limitSec`: the limit, or
     * step - BEFORE_S when the limit falls in a step's window. A layout with no dip
     * (dipS or holdS 0) is never moved. Never later than the limit; never below 0.
     */
    public static int fireAtSec(int limitSec, int holdS, int dipS) {
        if (limitSec <= 0) return 0;
        if (holdS <= 0 || dipS <= 0) return limitSec;
        int period = holdS + dipS;
        int t = limitSec;
        // Each move is strictly earlier, so this ends; with the run's layout it moves once.
        while (t > 0) {
            int step = stepAround(t, holdS, period);
            if (step < 0) return t;
            t = step - BEFORE_S;
        }
        return 0;
    }

    /** The step whose window holds `t`, or -1. Steps are at holdS + k x period, k >= 0. */
    private static int stepAround(int t, int holdS, int period) {
        int over = t - AFTER_S - holdS;
        int k = over <= 0 ? 0 : (over + period - 1) / period;   // the first step not yet past
        int step = holdS + k * period;
        return (t > step - BEFORE_S && t <= step + AFTER_S) ? step : -1;
    }
}
