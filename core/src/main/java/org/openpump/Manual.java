package org.openpump;

/**
 * The MANUAL-CONTROL cycle (Task 18, Part Two), made PURE (no `import android`) so test.sh
 * compiles it and SelfTest can assert on it. Manual mode runs an EPHEMERAL cycle — the six
 * controls the user asked for (max/min pressure, hold, drop, duration, speed) map one-to-one
 * onto Model.Set's existing fields, so this builds an ephemeral Model.Set and the Android
 * side reuses the ENTIRE routine run path (Model.plan → uploadBatch → the run screen). There
 * is deliberately no second implementation of "send presets and watch telemetry" here — that
 * is exactly how the vent safety would be bypassed.
 *
 * What lives here is the part a machine can check without a device:
 *   - the ephemeral set carries the stored manual values, in FIXED mode, clamped to the
 *     ceiling exactly as every other set is (Model.Set#clamp — both setpoints, lower under
 *     upper);
 *   - the PEAK a manual run actually applies, which must be the clamped value, so the
 *     preview and the confirmation quote the pressure that is enforced, not the one typed
 *     (the "a printed range must be the range actually enforced" rule);
 *   - roughly how many pull/drop cycles the duration produces, for the preview caption.
 *
 * "Simplified" means fewer decisions, not less safety: the same clamp, the same run path,
 * the same STOP-vents-with-telemetry-confirmation as a routine. This class only shapes the
 * ephemeral set; it commands nothing.
 */
public final class Manual {
    private Manual() { }

    /** The id the ephemeral set and its one-stage wrapper routine share. Not an `s`-prefixed
     *  library id, so it can never collide with a saved set. */
    public static final String ID = "manual";

    /**
     * The default manual cycle for a phone that has never run one — a gentle, obviously-safe
     * shape well inside the default ceiling. Persisted as a setting thereafter (ephemeral
     * means "not a saved SET", never "forget what you typed"), so this is only ever seen once.
     * Clamped at birth to the ceiling, the same rule Model#newRoutine follows for a routine.
     */
    public static Model.Set defaults(int ceilKpa) {
        Model.Set s = Model.Set.fixed(ID, "Manual run",
            Math.min(28, ceilKpa), Math.min(12, ceilKpa), 30, 5, 75, 360);
        s.clamp(ceilKpa);
        return s;
    }

    /**
     * A clamped copy of the stored manual values, ready to run — carrying the stored MODE
     * (Fixed or Ramp). Manual mode now exposes both, so a ramp end pair is honoured rather
     * than stripped. Same id/name as {@link #ID}, so Model#plan's stage can resolve it, and
     * Set#clamp applies the ceiling to every setpoint (both ends of a ramp included).
     */
    public static Model.Set ephemeral(Model.Set stored, int ceilKpa) {
        Model.Set s = Model.Set.fixed(ID, "Manual run",
            stored.up, stored.lo, stored.uh, stored.lh, stored.sp, stored.dur);
        s.ramp  = stored.ramp;
        s.up2   = stored.up2;
        s.lo2   = stored.lo2;
        s.sp2   = stored.sp2;
        s.uh2   = stored.uh2;
        s.lh2   = stored.lh2;
        s.steps = stored.steps;
        s.clamp(ceilKpa);
        return s;
    }

    /** The one stage a manual run's ephemeral routine holds — references {@link #ID}, which
     *  the caller makes resolvable to the ephemeral set. */
    public static Model.Routine routine(int ceilKpa) {
        Model.Routine r = new Model.Routine();
        r.id = ID;
        r.name = "Manual run";
        // No tissue-adaptation assessment: a manual cycle is not a routine with a measured
        // before/after, so Tau#runsBefore/#runsAfter must both be false and the run goes
        // straight seal-check → run → summary.
        r.assess = new Model.Assess();
        r.assess.on = false;
        r.stages.add(Model.Stage.of("Manual", Model.STAGE_WORK, new String[]{ ID }));
        return r;
    }

    /**
     * The peak a manual run actually APPLIES: the pull setpoint after the ceiling has capped
     * it, which is exactly what uploadBatch() writes (Math.min(up, ceilKpa)). The preview and
     * the confirmation quote THIS, never the raw typed value, so the number the user consents
     * to is the number the pump is commanded to.
     */
    public static int enforcedPeakKpa(Model.Set stored, int ceilKpa) {
        Model.Set e = ephemeral(stored, ceilKpa);
        // A ramp's deepest pull is its end target (up2); a fixed set's is its single pull.
        return e.ramp ? Math.max(e.up, e.up2) : e.up;
    }

    /** Roughly how many pull/drop cycles the duration produces — the preview caption's
     *  "about N cycles". One cycle is a hold-at-max plus a drop; both floor at 1 s so a set
     *  with zero holds still reports a sane count rather than dividing by zero. */
    public static int cycles(int uh, int lh, int durSec) {
        int cyc = Math.max(1, uh + lh);
        return Math.max(1, durSec / cyc);
    }
}
