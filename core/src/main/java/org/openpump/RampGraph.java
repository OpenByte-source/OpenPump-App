package org.openpump;

/**
 * PURE COORDINATE MATH FOR THE DRAG-THE-CURVE RAMP EDITOR (T11) — kept PURE (no `import
 * android`) so test.sh compiles it and SelfTest asserts on it, exactly as {@link Trace}'s
 * own header explains for the run screen's live trace. The DRAWING and the onTouchEvent
 * dispatch in SessionActivity's RampDragView can only be judged on a device; the
 * arithmetic that turns a touch's pixel position into a pressure, a pressure back into a
 * pixel, and a dragged value into a written (and clamped) {@link Model.Set} field is
 * decidable, and lives here so it is testable without an Android View.
 *
 * THE AXES. Y is pressure in kPa: 0 at the BOTTOM of the plot, `fullScaleKpa` at the top —
 * the same bottom-is-zero, clamped-into-range convention {@link Trace#yFraction}/{@link
 * Trace#kpaAtFraction} use for the run screen's live trace, adapted here to take explicit
 * pixel bounds (viewTop/viewBottom) rather than an implicit view height, because this plot
 * draws inside its own padding rather than filling its View edge-to-edge. X is elapsed
 * time in seconds: 0 at the left, the set's own duration at the right.
 *
 * WHY THERE IS NO PIXEL-TO-TIME FUNCTION. {@link Model.Set}'s ramp is exactly two points —
 * a START (up/lo/uh/lh/sp) and an END (up2/lo2/uh2/lh2/sp2) that {@link
 * Model.Set#ladder()} linearly interpolates across `steps` presets — and NEITHER point
 * carries a time field of its own: the start is always at elapsed 0 and the end is always
 * at elapsed `dur` (the set's one duration field), by construction, never by a value a
 * drag could move. So a touch's horizontal position has nothing to write into for either
 * handle; only its vertical (pressure) position does. {@link #xFromTime} exists to PLACE
 * the two handles (and would place a future third point without change) — there is
 * deliberately no inverse of it, because shipping a pixel-to-time function with no caller
 * would be exactly the kind of dead code this codebase's own review culture flags.
 */
public final class RampGraph {
    private RampGraph() { }

    /**
     * {@link Trace#kpaAtFraction}'s shape, but starting from a PIXEL Y rather than an
     * already-normalised fraction, and measured from the BOTTOM of an explicit [viewTop,
     * viewBottom] band rather than an implicit view height. Clamped into [0, fullScaleKpa]
     * the same way {@link Trace#yFraction} clamps its own fraction into [0, 1], so a
     * finger dragged above the top of the plot (or below its bottom) reads as the plot's
     * own extreme rather than an out-of-range value every caller would otherwise have to
     * guard separately. A non-positive full scale, or a degenerate (zero-or-negative-
     * height) band, is 0 — matching {@link Trace#yFraction}'s own floor for a non-positive
     * full scale.
     */
    public static double kpaFromY(float touchY, float viewTop, float viewBottom,
                                   double fullScaleKpa) {
        if (fullScaleKpa <= 0 || viewBottom <= viewTop) return 0.0;
        double frac = (viewBottom - (double) touchY) / (viewBottom - (double) viewTop);
        if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
        return frac * fullScaleKpa;
    }

    /**
     * {@link #kpaFromY}'s inverse: the pixel Y a given pressure sits at, for drawing the
     * handle and the envelope line it terminates. A pressure outside [0, fullScaleKpa] (an
     * over-ceiling set opened before the ceiling was lowered, say) still gets a Y clamped
     * to the plot's own top/bottom, rather than a coordinate that draws off the card.
     */
    public static float yFromKpa(double kpa, float viewTop, float viewBottom,
                                  double fullScaleKpa) {
        if (fullScaleKpa <= 0 || viewBottom <= viewTop) return viewBottom;
        double frac = kpa / fullScaleKpa;
        if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
        return (float) (viewBottom - frac * (viewBottom - viewTop));
    }

    /**
     * Where a moment in the set's run — 0..durationSec — sits along the plot's width, left
     * to right. Used to PLACE the two ramp handles at their fixed elapsed times (0 and the
     * set's own duration); see the class doc for why there is no reverse of this.
     */
    public static float xFromTime(double timeSec, float viewLeft, float viewRight,
                                   double durationSec) {
        if (durationSec <= 0 || viewRight <= viewLeft) return viewLeft;
        double frac = timeSec / durationSec;
        if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
        return (float) (viewLeft + frac * (viewRight - viewLeft));
    }

    /**
     * Euclidean hit test: is (touchX, touchY) within `grabRadius` pixels of a handle drawn
     * at (handleX, handleY)? Exactly ON the boundary counts as within it. Compared as
     * squared distances, so ACTION_DOWN never pays for a sqrt.
     */
    public static boolean withinHandle(float touchX, float touchY,
                                        float handleX, float handleY, float grabRadius) {
        double dx = touchX - handleX, dy = touchY - handleY;
        double gr = grabRadius;
        return dx * dx + dy * dy <= gr * gr;
    }

    /**
     * Applies a drag's Y-derived pressure to whichever end of the ramp `start` selects —
     * {@link Model.Set#up} (true) or {@link Model.Set#up2} (false) — then runs the WHOLE
     * set through {@link Model.Set#clamp}, the EXACT SAME clamp every stepper edit on this
     * screen already calls (SessionActivity#bump, #setParamExact, ParamSlide's own
     * commit). No second, drag-only clamp exists anywhere in this class: this is the one
     * place a dragged value is ever written, and it is written through the one method
     * that has ever been allowed to decide what a set's numbers may be — which is what
     * makes a drag reshape a set exactly as the equivalent stepper edit would (down to the
     * SAME cross-field consequence: dragging the pull below the current drop-to value
     * pulls the drop down with it, precisely as bump()'s own `if (lo >= up)` rule already
     * does for a typed or stepped edit). A null `s` is a no-op, defensively — this is
     * called from a live View's touch handler, and a Set can in principle be deleted out
     * from under an open editor by another path.
     */
    public static void applyPull(Model.Set s, boolean start, int kpaRounded, int ceilKpa) {
        if (s == null) return;
        if (start) s.up = kpaRounded; else s.up2 = kpaRounded;
        s.clamp(ceilKpa);
    }
}
