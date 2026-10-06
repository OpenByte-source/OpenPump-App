package org.openpump;

/**
 * WHERE THE PUMP IS IN ITS CYCLE, READ FROM THE PRESSURE.
 *
 * The pump runs each preset's hold-and-drop cycle on its own clock, which the app cannot
 * see. The run screen used to reconstruct it - (now - when the preset was armed) modulo
 * (hold + drop) - and the reconstruction drifted both ways on real hardware: the drop
 * came before the timer said it would, and with a seal that topped out short of the target
 * the pump never dropped at all while the screen said "DROP 0:02 of 0:05".
 *
 * The measured pressure knows the truth, so this reads it. Three phases:
 *
 *   PULLING  the pressure is on its way up to the target and not there yet;
 *   HOLD     at the target (or stalled short of it - {@link #shortOfTarget()} says which);
 *   DROP     the pump has let go down toward the drop pressure.
 *
 * A drop is recognised by a FAST fall, never by a slow one: the pump coasts during a long
 * hold (about 4 kPa in the one hardware log on record; the rate is unmeasured, release
 * checklist H14), and that drift must not read as a drop.
 * A hold-only preset (no drop dwell, a stitched chunk) never enters DROP at all.
 *
 * Pure: no Android, no clock of its own. The caller feeds it samples with their times and
 * restarts it whenever the pump's cycle restarts (a new step, a skip, a re-armed step after
 * a live change). Pressures are magnitudes in kPa, as everywhere else in the app.
 */
public final class PhaseTracker {

    public enum Phase { PULLING, HOLD, DROP }

    /** How close to the target counts as having reached it. */
    static double reachedTolKpa(int upKpa) { return Math.max(0.7, upKpa * 0.04); }

    /** A fall at least this steep over the window is the pump letting go, not a coast. */
    static final double FAST_KPA = 1.2;
    /** The window the fall or rise is measured over. */
    static final long WINDOW_MS = 1500L;
    /** No new high for this long, while well above the drop, is a stalled pull. */
    static final long STALL_MS = 3000L;
    /** What counts as "no new high" during that stall window. */
    static final double STALL_KPA = 0.3;

    private int upKpa, loKpa;
    private boolean drops;
    private Phase phase = Phase.PULLING;
    private long phaseStartMs = -1L;
    private boolean started;
    private boolean shortOfTarget;

    // A small ring of recent samples: enough for the fall/rise window and the stall window.
    private static final int RING = 64;
    private final long[] ts = new long[RING];
    private final double[] ks = new double[RING];
    private int n, head;

    // The highest reading so far in this pull, and when it was set - for the stall test.
    private double pullHigh = -1;
    private long pullHighAt = -1L;

    /** The pump's cycle (re)starts now, toward these targets. */
    public void start(long nowMs, int upKpa, int loKpa, boolean drops) {
        this.upKpa = upKpa; this.loKpa = loKpa; this.drops = drops;
        phase = Phase.PULLING; phaseStartMs = nowMs; started = true; shortOfTarget = false;
        n = 0; head = 0; pullHigh = -1; pullHighAt = -1L;
    }

    /** Nothing is being tracked (a rest, no run). */
    public void stop() { started = false; }

    public boolean started() { return started; }
    public Phase phase() { return phase; }
    /** In HOLD, true when the hold began because the pull stalled short of the target. */
    public boolean shortOfTarget() { return shortOfTarget; }
    public long phaseElapsedMs(long nowMs) { return phaseStartMs < 0 ? 0 : Math.max(0, nowMs - phaseStartMs); }

    /** One pressure reading, magnitude in kPa. Readings of 0 (no measurement) are ignored. */
    public void sample(long t, double kpa) {
        if (!started || Double.isNaN(kpa) || Double.isInfinite(kpa) || kpa <= 0) return;
        ts[head] = t; ks[head] = kpa; head = (head + 1) % RING; if (n < RING) n++;

        double tol = reachedTolKpa(upKpa);
        switch (phase) {
            case PULLING:
                if (kpa > pullHigh + STALL_KPA || pullHigh < 0) { pullHigh = kpa; pullHighAt = t; }
                if (kpa >= upKpa - tol) { enter(Phase.HOLD, t); shortOfTarget = false; break; }
                // A pull that has stopped climbing, well clear of the drop, is as high as this
                // seal goes: the pump is holding there, short of the target.
                if (pullHighAt >= 0 && t - pullHighAt >= STALL_MS && kpa > dropLine() + tol) {
                    enter(Phase.HOLD, t); shortOfTarget = true; break;
                }
                if (drops && fellFast(t)) enter(Phase.DROP, t);
                break;
            case HOLD:
                if (shortOfTarget && kpa >= upKpa - tol) shortOfTarget = false;   // it got there late
                if (drops && fellFast(t)) enter(Phase.DROP, t);
                break;
            case DROP:
                // Back on the way up: a fast rise, or - for a slow pump speed, where the climb
                // is gentler than any "fast" test - clearly above the drop and still climbing.
                if (roseFast(t) || (kpa > loKpa + 0.8 && rising(t))) {
                    enter(Phase.PULLING, t); pullHigh = kpa; pullHighAt = t;
                }
                break;
        }
    }

    /** Where the drop is heading: the drop pressure, or half-way when it is barely below the pull. */
    private double dropLine() { return drops ? loKpa : upKpa; }

    private void enter(Phase p, long t) { phase = p; phaseStartMs = t; }

    private boolean fellFast(long t) {
        double peak = -1;
        for (int i = 0; i < n; i++) {
            int j = (head - 1 - i + RING) % RING;
            if (t - ts[j] > WINDOW_MS) break;
            peak = Math.max(peak, ks[j]);
        }
        double cur = ks[(head - 1 + RING) % RING];
        // Scaled down for a narrow band, so a small drop still registers, never below 0.6
        // (telemetry moves in 0.1 kPa steps; a coast moves about 0.02 kPa in the window).
        double need = Math.max(0.6, Math.min(FAST_KPA, 0.6 * (upKpa - loKpa)));
        return peak - cur >= need;
    }

    private boolean roseFast(long t) {
        double low = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int j = (head - 1 - i + RING) % RING;
            if (t - ts[j] > WINDOW_MS) break;
            low = Math.min(low, ks[j]);
        }
        double cur = ks[(head - 1 + RING) % RING];
        return cur - low >= FAST_KPA;
    }

    /** A steady climb over a longer window: 0.6 kPa in 3 s is a pull even at 10 % speed
     *  (about 0.3 kPa/s), and more than telemetry jitter (+-0.1 kPa) can fake at rest. */
    private boolean rising(long t) {
        double low = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int j = (head - 1 - i + RING) % RING;
            if (t - ts[j] > 2 * WINDOW_MS) break;
            low = Math.min(low, ks[j]);
        }
        return ks[(head - 1 + RING) % RING] - low >= 0.6;
    }

    /** The run screen's line: "HOLD · 0:56 of 2:00", "DROP · 0:02 of 0:05", "PULLING · 0:04".
     *
     *  PAST THE PLAN IT SAYS SO: "HOLD · 3:02 · 1:02 past 2:00". It read "3:02 of 2:00", which
     *  looks like a slip. A pump short of its target can genuinely hold longer than planned on
     *  real hardware, so the overrun is kept - said as what it is. */
    public String line(long nowMs, int holdS, int dropS, String targetText) {
        long s = phaseElapsedMs(nowMs) / 1000;
        switch (phase) {
            case HOLD:
                return "HOLD · " + againstPlan(s, holdS)
                    + (shortOfTarget ? " · short of " + targetText : "");
            case DROP:
                return "DROP · " + againstPlan(s, dropS);
            default:
                return "PULLING · " + Model.Fmt.t(s) + " · to " + targetText;
        }
    }

    /** "0:56 of 2:00" within the plan; "3:02 · 1:02 past 2:00" beyond it. */
    private static String againstPlan(long s, int planS) {
        if (s <= planS) return Model.Fmt.t(s) + " of " + Model.Fmt.t(planS);
        return Model.Fmt.t(s) + " · " + Model.Fmt.t(s - planS) + " past " + Model.Fmt.t(planS);
    }
}
