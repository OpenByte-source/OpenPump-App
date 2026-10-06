package org.openpump;

/**
 * THE RAMP'S ARITHMETIC (0.10, the owner's ramp decisions) - where a Ramped block's climb
 * starts, which pressures it steps through, and the same for the gentle warm-up of somebody
 * who marks easily. Pure whole-kPa arithmetic, no model: RxBuild lays the sets out from it,
 * the Trainer page previews it, and RampClimbTest pins it.
 *
 * WHOLE kPa, BECAUSE THE WIRE TAKES NOTHING FINER. A step "of 1.0 inHg" is at most the largest
 * whole kPa not over it - 3 kPa, 0.89 inHg - so no climbing hold is ever more than the step the
 * person chose above the one before it, however the kPa round.
 *
 * THE POINTS ARE A RAMP'S OWN. A climb is played as a ramp set, whose steps the device's table
 * holds and {@link Model.Set#ladder} spreads evenly between its two ends; so a climb is chosen
 * as exactly the points such a ladder gives ({@link #ladder}), and never as points a ladder
 * would round to something else. A climb longer than the table ({@link Proto#SLOTS}) is played
 * as consecutive ramp sets, each a run of points its own ladder reproduces ({@link #segments}).
 */
public final class Ramp {

    private Ramp() { }

    /** The most a climbing step may add, whole kPa: the largest whole kPa not over `hg`
     *  inches of mercury, and at least 1 (0.3 inHg is 1.02 kPa). */
    public static int stepKpa(double hg) {
        int k = (int) Math.floor(hg * Model.Fmt.KPA_PER_INHG + 1e-9);
        return Math.max(1, k);
    }

    /** Where the first block's climb starts: `pct` % of the working pressure `workKpa`,
     *  whole kPa, at least 1. At or above the work there is nothing to climb. */
    public static int startKpa(int workKpa, int pct) {
        return Math.max(1, (int) Math.round(workKpa * pct / 100.0));
    }

    /** Point `i` of `n` on a ramp from `a` to `b`, exactly as {@link Model.Set#ladder} sends
     *  it: one point is the start itself. */
    public static int ladderPoint(int a, int b, int i, int n) {
        if (n <= 1) return a;
        double f = (double) i / (n - 1);
        return (int) Math.round(a + (b - a) * f);
    }

    /** The `n` points of a ramp from `a` to `b` ({@link #ladderPoint}). */
    public static int[] ladder(int a, int b, int n) {
        int[] out = new int[Math.max(0, n)];
        for (int i = 0; i < out.length; i++) out[i] = ladderPoint(a, b, i, n);
        return out;
    }

    /**
     * THE FIRST BLOCK'S CLIMB: the holds it spends UNDER the working pressure `workKpa`, from
     * `startKpa`, each at most `stepKpa` above the one before and the last at most `stepKpa`
     * under the work - after which the block's holds are at the working pressure. As few
     * holds as the step allows, evenly spread. Empty when the start is at or above the work.
     */
    public static int[] firstClimb(int startKpa, int workKpa, int stepKpa) {
        int k = Math.max(1, stepKpa);
        if (startKpa >= workKpa) return new int[0];
        int span = workKpa - startKpa;
        int n = (span + k - 1) / k;              // holds under the work, the arrival not one
        if (n <= 1) return new int[]{ startKpa };
        /* The last climbing hold: evenly spaced toward the work, and inside the only window
         * that keeps every step within k - no more than k under the work, and no more than
         * (n-1) steps of k above the start (a ladder's steps are then at most k apart). */
        int end = startKpa + (int) Math.round((n - 1) * span / (double) n);
        end = Math.max(end, workKpa - k);
        end = Math.min(end, startKpa + (n - 1) * k);
        return ladder(startKpa, end, n);
    }

    /**
     * THE SHORT CLIMB AFTER A REST: `steps` steps, the last of them the arrival at the working
     * pressure - so 2 is one hold under it, then it; 0 or 1 start at it. Taken from the top of
     * the first block's climb (`first`), never longer than it and never starting lower.
     */
    public static int[] shortClimb(int[] first, int steps) {
        if (first == null || first.length == 0) return new int[0];
        int m = Math.min(first.length, Math.max(0, steps - 1));
        if (m <= 0) return new int[0];
        int from = first[first.length - m], to = first[first.length - 1];
        return m == 1 ? new int[]{ to } : ladder(from, to, m);
    }

    /**
     * THE GENTLE WARM-UP'S PRESSURES (the owner's own rule for this app): rep by rep from
     * `startKpa` to `workKpa`, every rep at most `stepKpa` above the one before, ending AT the
     * work - one rep, at the work, where the work is at or under the start (never above it).
     * As few reps as the step allows: the climb's increments are ceil(climb / step), and the
     * reps are one more than that, the first rep being the start itself.
     */
    public static int[] gentleReps(int startKpa, int workKpa, int stepKpa) {
        int k = Math.max(1, stepKpa);
        if (workKpa < 1) return new int[0];
        if (startKpa >= workKpa) return new int[]{ workKpa };
        int increments = (workKpa - startKpa + k - 1) / k;
        return ladder(startKpa, workKpa, increments + 1);
    }

    /** The speed of rep `i` of `n`, rising evenly from `fromPct` to `toPct` - as a ramp's own
     *  speed walks between its ends. */
    public static int speedAt(int fromPct, int toPct, int i, int n) {
        return ladderPoint(fromPct, toPct, i, n);
    }

    /**
     * THE RAMP SETS A RUN OF POINTS IS PLAYED AS: {from, to} index pairs, in order, each run no
     * longer than the device's table and each exactly the points its own ramp's ladder sends
     * between its two ends. A run of one point is a single fixed hold. A climb inside the
     * table is one run whenever its points are one ladder - which {@link #firstClimb} and
     * {@link #gentleReps} make them.
     */
    public static java.util.List<int[]> segments(int[] pts) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        if (pts == null) return out;
        int a = 0;
        while (a < pts.length) {
            int best = a;
            for (int b = Math.min(pts.length - 1, a + Proto.SLOTS - 1); b > a; b--) {
                if (reproduces(pts, a, b)) { best = b; break; }
            }
            out.add(new int[]{ a, best });
            a = best + 1;
        }
        return out;
    }

    /** Whether a ramp from pts[a] to pts[b] in (b - a + 1) steps sends exactly pts[a..b]. */
    static boolean reproduces(int[] pts, int a, int b) {
        int n = b - a + 1;
        for (int i = 0; i < n; i++)
            if (ladderPoint(pts[a], pts[b], i, n) != pts[a + i]) return false;
        return true;
    }

    /** The largest rise between one point and the next, `then` taken as the point after the
     *  last (Integer.MIN_VALUE for none) - what every climb here keeps within its step. */
    public static int largestStep(int[] pts, int then) {
        int big = 0;
        for (int i = 1; i < pts.length; i++) big = Math.max(big, pts[i] - pts[i - 1]);
        if (then != Integer.MIN_VALUE && pts.length > 0)
            big = Math.max(big, then - pts[pts.length - 1]);
        return big;
    }

    /* ------------------------------------------------------------ how it reads on the page */

    /**
     * THE RAMPS' PREVIEW LINE at a working pressure: "First block: −8.0 → −8.9 → −9.4 → −10.0,
     * then −10.0 · after a rest: −9.4 → −10.0", in the display unit - the climb the settings
     * give, the arrival at the work included. "No climb" where the start is at or above it.
     */
    public static String previewLine(int workKpa, int pct, int shortSteps, double stepHg) {
        if (workKpa < 1) return "";
        int k = stepKpa(stepHg);
        int[] first = firstClimb(startKpa(workKpa, pct), workKpa, k);
        if (first.length == 0)
            return "No climb at " + Model.Fmt.p(workKpa) + " — the start is at the working "
                + "pressure, so every hold is.";
        int[] after = shortClimb(first, shortSteps);
        StringBuilder b = new StringBuilder("First block: ");
        b.append(arrows(first, workKpa)).append(", then ").append(Model.Fmt.p(workKpa));
        b.append(" · after a rest: ");
        if (after.length == 0) b.append("starts at ").append(Model.Fmt.p(workKpa));
        else b.append(arrows(after, workKpa));
        return b.toString();
    }

    /** The points, then the work, joined by arrows; the unit on the last only. */
    private static String arrows(int[] pts, int workKpa) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < pts.length; i++) b.append(Model.Fmt.pBare(pts[i])).append(" → ");
        b.append(Model.Fmt.p(workKpa));
        return b.toString();
    }
}
