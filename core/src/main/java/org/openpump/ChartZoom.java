package org.openpump;

/**
 * The pure geometry behind pinch-zoom on the Progress/Trends charts (round6-options.html
 * S20 B: "pinch-zoom in place... no fullscreen mode"). Ported from Align's shape —
 * CameraScreen's AlignView/PinchListener is the only other pinch gesture in this app — but
 * adapted for a picture that already exactly fills its own frame at rest, unlike a captured
 * photo that may legitimately sit smaller than its guide with room to spare:
 *
 *   - Scale never goes BELOW 1 (the chart's undisturbed, already-fitted drawing). Align's
 *     photo can be shrunk to 0.5x because a photo may need to sit smaller than its frame to
 *     line up with a guide; a chart's plot never benefits from that — shrinking it below its
 *     fitted size only shows dead margin around an already-complete picture.
 *   - The pan clamp is tied to the CURRENT scale rather than Align's fixed MAX_PAN_FRACTION
 *     of the frame. Align's photo can be dragged around even at zoom 1 (it may not fill the
 *     frame to begin with); a chart's picture DOES exactly fill its card at scale 1, so any
 *     pan there would only reveal empty margin at one edge. The bound here is exactly the
 *     overflow zooming to `scale` introduces on one side — dim * (scale - 1) / 2 — so a pan
 *     can never drag the picture's own edge past the middle of its card, and at scale 1 the
 *     bound is exactly zero.
 *
 * NO ANDROID IMPORT, deliberately — see Align's own doc for why (test.sh auto-discovers
 * every android-import-free source under the desktop suite). The Canvas work — translating
 * then scaling about the view's own centre, wrapping the chart's existing draw calls — lives
 * in SessionActivity's TrendChart and is verified by reading; what IS machine-verified is
 * the part that can be wrong silently: the scale clamp and the pan clamp.
 */
public final class ChartZoom {
    private ChartZoom() { }

    /** The floor — see the class doc for why this differs from Align's 0.5. */
    public static final float MIN_SCALE = 1f;
    /** The ceiling — the same 4x Align.MAX_ZOOM allows, a sane bound past which the drawn
     *  strokes and dots would be reading as isolated shapes rather than a chart. */
    public static final float MAX_SCALE = 4f;

    /** Scale clamped to [{@link #MIN_SCALE}, {@link #MAX_SCALE}]. NaN and non-positive
     *  values collapse to 1, the same defensive floor Align.clampZoom uses for the identical
     *  reason: a zero or negative Canvas.scale mirrors or blanks the drawing with no way
     *  back except the reset gesture. */
    public static float clampScale(float s) {
        if (Float.isNaN(s) || s <= 0f) return 1f;
        if (s < MIN_SCALE) return MIN_SCALE;
        if (s > MAX_SCALE) return MAX_SCALE;
        return s;
    }

    /** One pan axis clamped to the overflow the CURRENT `scale` actually introduces on that
     *  axis: plus/minus (dim * (scale - 1) / 2) — see the class doc for why this tracks
     *  scale rather than Align's fixed fraction. `scale` is clamped first, so a caller can
     *  never smuggle an over-range scale into a wider pan allowance than the drawn picture
     *  will actually have. A non-positive `dim` (a view not yet measured) or a NaN pan pins
     *  at 0 rather than clamping against garbage, the same defensive shape Align.clampPan
     *  uses. */
    public static float clampPan(float v, float scale, int dim) {
        if (Float.isNaN(v) || dim <= 0) return 0f;
        float lim = dim * (clampScale(scale) - 1f) / 2f;
        if (v > lim) return lim;
        if (v < -lim) return -lim;
        return v;
    }
}
