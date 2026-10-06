package org.openpump;

/**
 * THE DRAG GEOMETRY FOR ONE PULL/DROP CYCLE — the manual run's fixed mode.
 *
 * WHY THIS IS NOT RampGraph. The two look alike on screen and mean opposite things, and
 * that is exactly the reason they are separate classes rather than one widget with a flag.
 *
 *   RampGraph's two handles are ONE VALUE AT TWO TIMES: {@link Model.Set#up} at the start
 *   of the set and {@link Model.Set#up2} at the end of it. The line between them is a
 *   climb, the x axis is the set's whole duration, and a handle's horizontal position is
 *   fixed because neither end owns a time field.
 *
 *   These two handles are TWO DIFFERENT VALUES INSIDE ONE CYCLE: the pull ({@link
 *   Model.Set#up}) and the drop ({@link Model.Set#lo}) the pump alternates between for the
 *   whole set. The line between them is not a trajectory at all — it is a repeating square
 *   wave — and the x axis is ONE CYCLE, not the set.
 *
 * Reusing RampGraph with a flag would have produced a control that looks identical in both
 * places and edits a different pair of fields depending on which screen it is on. That is
 * how two controls that look the same come to do different things, so this is its own
 * class with its own name for its own pair.
 *
 * WHAT IT DOES REUSE, deliberately: {@link RampGraph#yFromKpa}, {@link RampGraph#kpaFromY}
 * and {@link RampGraph#withinHandle} are plain plot arithmetic with no opinion about what
 * a handle MEANS, and a second copy of "where does this pressure sit in this box" is a
 * second thing to get wrong. Only the parts that carry meaning — where the two plateaux
 * are, and which field a drag writes — live here.
 *
 * PURE: no Android import, so the desktop harness compiles and pins it.
 */
public final class CycleGraph {
    private CycleGraph() { }

    /**
     * How much of a cycle the rise and the fall each take, as a fraction of the hold they
     * lead into. THE SAME CONSTANT {@link Waveform#RISE_UNITS} draws the preview with, and
     * referenced from there rather than repeated: the draggable chart and the read-only one
     * must not disagree about where a plateau starts, or a handle would sit off the plateau
     * it is supposed to be on.
     */
    public static final float RISE_UNITS = Waveform.RISE_UNITS;

    /** The cycle's total width in "units" — rise, hold, fall, drop-hold. */
    private static float units(int uh, int lh) {
        return RISE_UNITS + Math.max(1, uh) + RISE_UNITS + Math.max(1, lh);
    }

    /**
     * Where the PULL plateau's centre sits across the cycle, 0..1. The handle goes here
     * rather than at the start of the plateau so a short hold still puts it clear of the
     * rise, and a long one does not push it to an edge.
     */
    public static float pullCentreFrac(int uh, int lh) {
        float u = units(uh, lh);
        return (RISE_UNITS + Math.max(1, uh) / 2f) / u;
    }

    /** The same for the DROP plateau. */
    public static float dropCentreFrac(int uh, int lh) {
        float u = units(uh, lh);
        return (RISE_UNITS + Math.max(1, uh) + RISE_UNITS + Math.max(1, lh) / 2f) / u;
    }

    /** A fraction across the cycle, in pixels. */
    public static float xFromFrac(float frac, float viewLeft, float viewRight) {
        if (viewRight <= viewLeft) return viewLeft;
        float f = frac < 0f ? 0f : (frac > 1f ? 1f : frac);
        return viewLeft + (viewRight - viewLeft) * f;
    }

    /**
     * The polyline of one cycle, as {x,y} fractions of the plot box — 0 at the baseline,
     * 1 at full scale. Six points: baseline, up, up, down, down, and back to the rise, so
     * the shape reads as the repeating wave it is rather than as a single pulse.
     *
     * Returned as a flat array for the same reason {@link Waveform.Shape} uses arrays:
     * this is built on every onDraw of a live view.
     */
    public static float[] outline(int up, int lo, int uh, int lh, double fullScaleKpa) {
        double full = fullScaleKpa <= 0 ? 1 : fullScaleKpa;
        float u = units(uh, lh);
        float upY = (float) (up / full), loY = (float) (lo / full);
        if (upY > 1f) upY = 1f;
        if (upY < 0f) upY = 0f;
        if (loY > 1f) loY = 1f;
        if (loY < 0f) loY = 0f;
        float t1 = RISE_UNITS / u;
        float t2 = (RISE_UNITS + Math.max(1, uh)) / u;
        float t3 = (RISE_UNITS + Math.max(1, uh) + RISE_UNITS) / u;
        return new float[]{
            0f,  loY,          // the cycle begins where the previous one left off
            t1,  upY,
            t2,  upY,
            t3,  loY,
            1f,  loY
        };
    }

    /**
     * E3 - WHERE THE TWO PLATEAU EDGES SIT, 0..1 across the cycle.
     *
     * The pull plateau ENDS where the fall begins, and the drop plateau ends where the cycle
     * does. Dragging those two boundaries sideways is dragging the two HOLD TIMES, which are
     * the only thing left in the shape that the two pressure handles do not already cover.
     *
     * The pull edge is the honest one to drag: moving it lengthens the hold at pressure and
     * everything after it shifts. The cycle's own end is not draggable - it is the right-hand
     * wall of the plot, and a handle you cannot move past is a handle that lies about what it
     * does - so the DROP time is dragged from its own plateau's start instead, which is the
     * same boundary seen from the other side.
     */
    public static float pullEdgeFrac(int uh, int lh) {
        float u = units(uh, lh);
        return (RISE_UNITS + Math.max(1, uh)) / u;
    }

    public static float dropEdgeFrac(int uh, int lh) {
        float u = units(uh, lh);
        return (RISE_UNITS + Math.max(1, uh) + RISE_UNITS) / u;
    }

    /** The smallest hold either edge may be dragged to, and the largest each may be - the
     *  SAME bounds {@link Model.Set#clamp} enforces, so a drag can never ask for a value a
     *  typed edit would be refused, and never refuses one a typed edit would allow.
     *
     *  The two sides differ because the set's own limits differ: an upper hold can be
     *  stitched out of several presets and a lower one cannot. */
    public static final int HOLD_MIN_SEC = 1;
    public static int holdMaxSec(boolean pull) {
        return pull ? Model.Set.HOLD_MAX_SEC : Proto.WIRE_HOLD_MAX;
    }

    /**
     * The hold time a horizontal drag to `frac` is asking for, given the cycle's OTHER hold.
     * Derived by inverting {@link #pullEdgeFrac} rather than by scaling the pixels, so the
     * number the finger lands on is the number the plateau will actually be drawn at.
     *
     * Bounded here as well as at the clamp, because an unbounded intermediate can divide by
     * a denominator that has gone to zero on its way past the edge of the plot.
     */
    public static int holdFromEdge(float frac, boolean pull, int uh, int lh) {
        // THE EPSILON IS TINY ON PURPOSE. It exists only to keep the divisor off zero for a
        // finger dragged past the edge of the plot; a wider one silently truncates real
        // values, because a long hold beside a short one puts its edge legitimately close to
        // the wall - 120 s against 1 s sits at 0.99 - and clamping that to 0.98 asked for a
        // two-second hold instead of a hundred-and-twenty-second one. The round-trip
        // assertions caught it.
        double f = frac < 1e-5f ? 1e-5 : (frac > 1 - 1e-5f ? 1 - 1e-5 : frac);
        int other = Math.max(1, pull ? lh : uh);
        // pullEdge = (R + uh) / (R + uh + R + lh)  ->  uh = (f*(2R + lh) - R) / (1 - f)
        // dropEdge = (2R + uh) / (2R + uh + lh)    ->  lh = (2R + uh)*(1 - f)/f
        double r = RISE_UNITS;
        double want;
        if (pull) want = (f * (2 * r + other) - r) / (1 - f);
        else      want = (2 * r + other) * (1 - f) / f;
        int v = (int) Math.round(want);
        if (v < HOLD_MIN_SEC) v = HOLD_MIN_SEC;
        if (v > holdMaxSec(pull)) v = holdMaxSec(pull);
        return v;
    }

    /** Writes a dragged HOLD TIME, through the same clamp every other edit uses. */
    public static void applyHold(Model.Set s, boolean pull, int sec, int ceilKpa) {
        if (s == null) return;
        if (pull) s.uh = sec; else s.lh = sec;
        s.clamp(ceilKpa);
    }

    /**
     * Writes a dragged pressure to whichever value `pull` selects — {@link Model.Set#up}
     * (true) or {@link Model.Set#lo} (false) — and runs the WHOLE set through {@link
     * Model.Set#clamp}.
     *
     * The same discipline {@link RampGraph#applyPull} holds to, for the same reason: this
     * is the one place a dragged value is written, and it is written through the one method
     * that has ever been allowed to decide what a set's numbers may be. So a drag has the
     * identical cross-field consequences a stepper edit has — dragging the pull down past
     * the drop pulls the drop down with it, and dragging the drop up past the pull is
     * refused by the same {@code lo >= up} rule, not by a second rule invented here.
     *
     * A null set is a no-op: this is called from a live View's touch handler, and the
     * manual set can in principle be replaced out from under an open screen.
     */
    public static void applyCycle(Model.Set s, boolean pull, int kpaRounded, int ceilKpa) {
        if (s == null) return;
        if (pull) s.up = kpaRounded; else s.lo = kpaRounded;
        s.clamp(ceilKpa);
    }

    /* ===================== C1 - THE SET AS A STRIP OF REAL TIME =======================
     *
     * The chart used to draw ONE cycle across the full width and caption it "about 60
     * cycles". Two things went wrong with that at once. The shape on screen was not the
     * shape of the run - a one-second pull looked as wide as a five-second drop - and there
     * was no way to see any cycle but the first.
     *
     * It is now a window onto real seconds: a fixed number of seconds is shown, the strip
     * pans sideways, and every plateau is as wide as it is long. Sideways is also the axis
     * the page does NOT use, which is what ends the fight between dragging a pressure and
     * scrolling the screen.
     *
     * All of this is arithmetic over seconds, so it lives here where the harness can reach
     * it rather than inside a View that needs a phone to run.
     */

    /** How many seconds of the set a strip shows at once: about four cycles, never fewer
     *  than eight seconds (a very short cycle would otherwise show a stripe of noise) and
     *  never more than the set actually lasts. */
    public static int windowSec(int uh, int lh, int durSec) {
        int cycle = Math.max(1, Math.max(0, uh) + Math.max(0, lh));
        int win = Math.max(8, cycle * 4);
        return Math.min(Math.max(1, durSec), win);
    }

    /** The furthest left the window may be panned to, so the strip cannot be dragged past
     *  the end of the set into empty space. */
    public static int maxPanSec(int durSec, int winSec) {
        return Math.max(0, Math.max(1, durSec) - Math.max(1, winSec));
    }

    /** Clamp a pan offset into that range. Fractional seconds are kept - a pan that
     *  snapped to whole seconds would stutter under the finger. */
    public static float clampPan(float panSec, int durSec, int winSec) {
        float max = maxPanSec(durSec, winSec);
        if (panSec < 0f) return 0f;
        return panSec > max ? max : panSec;
    }

    /** Where a moment in the SET lands on screen, given the window. Values outside the
     *  window come back outside [viewLeft, viewRight]; callers clip. */
    public static float xFromSec(float sec, float panSec, int winSec,
                                 float viewLeft, float viewRight) {
        float w = Math.max(1, winSec);
        return viewLeft + ((sec - panSec) / w) * (viewRight - viewLeft);
    }

    /** The inverse, for a finger position. */
    public static float secFromX(float x, float panSec, int winSec,
                                 float viewLeft, float viewRight) {
        float span = viewRight - viewLeft;
        if (span <= 0f) return panSec;
        return panSec + ((x - viewLeft) / span) * Math.max(1, winSec);
    }

    /** Which cycle a moment falls in, counting from 1 - what a caption says out loud. */
    public static int cycleAt(float sec, int uh, int lh) {
        int cycle = Math.max(1, Math.max(0, uh) + Math.max(0, lh));
        if (sec < 0f) return 1;
        return (int) (sec / cycle) + 1;
    }

    /** How many whole cycles a set of this length contains, at least one. */
    public static int cycleCount(int uh, int lh, int durSec) {
        int cycle = Math.max(1, Math.max(0, uh) + Math.max(0, lh));
        return Math.max(1, Math.max(1, durSec) / cycle);
    }

    /**
     * The first cycle that is FULLY inside the window, as a start time in seconds - the one
     * the handles are drawn on. Editing any cycle edits the set, because in a fixed set they
     * are all the same cycle; drawing the handles on one that is only half on screen would
     * put a grab target under the edge of the chart.
     *
     * Falls back to the cycle the window starts in when none fits whole, which happens only
     * when a single cycle is longer than the window itself.
     */
    public static float handleCycleStartSec(float panSec, int winSec, int uh, int lh,
                                            int durSec) {
        int cycle = Math.max(1, Math.max(0, uh) + Math.max(0, lh));
        int first = (int) Math.ceil(panSec / cycle);
        float start = first * (float) cycle;
        if (start + cycle <= panSec + winSec && start + cycle <= Math.max(1, durSec))
            return start;
        return ((int) (panSec / cycle)) * (float) cycle;
    }

    /**
     * WHICH WAY A DRAG THAT DID NOT START ON A HANDLE IS GOING, decided once, from the
     * first movement that clears the slop: 1 pans the strip, -1 gives the gesture back to
     * the page, 0 means not yet decided.
     *
     * A chart that keeps every touch would trap the screen; one that keeps none could never
     * be panned. Deciding by direction gives each axis an owner, which is the whole idea:
     * sideways is the strip's, up and down stays the page's.
     */
    public static int gestureAxis(float dx, float dy, float slopPx) {
        float ax = Math.abs(dx), ay = Math.abs(dy);
        if (ax < slopPx && ay < slopPx) return 0;
        return ax > ay ? 1 : -1;
    }

}
