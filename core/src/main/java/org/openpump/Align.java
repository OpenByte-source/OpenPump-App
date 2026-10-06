package org.openpump;

import java.util.List;

/**
 * The pure geometry behind the post-capture ALIGN screen — the arithmetic that decides
 * where the just-taken photo sits inside the align frame, how far the user is allowed to
 * push it, and WHICH earlier reading is the ghost it is being lined up against.
 *
 * WHY THIS SCREEN EXISTS. The app drives the SYSTEM camera (MediaStore.ACTION_IMAGE_CAPTURE)
 * because the in-app camera2 preview rendered black on the user's device — see
 * CameraScreen's class doc. A system camera cannot be drawn over, so the tubular alignment
 * guide and the previous-photo ghost that used to live on the live viewfinder were lost.
 * They come back HERE, after the shutter, over the still: the user pans/zooms/rotates the
 * captured frame until the tube sits inside the same guide and over the same ghost as last
 * time, and the transform is baked into the saved pixels. Aligning against a still is also
 * strictly more precise than aligning against a shaking viewfinder — the frame does not
 * move while you judge it.
 *
 * NO ANDROID IMPORT, deliberately: test.sh auto-discovers every source with no
 * `import android` line, so everything here is executed by the desktop suite. The Canvas
 * work — drawing the cylinder, drawing the ghost, drawing the transformed bitmap through a
 * Matrix — lives in CameraScreen's AlignView and is verified by reading, not by machine.
 * What IS machine-verified is the part that can be wrong silently: the rotation clamp, the
 * fit scale, the composed mapping of a bitmap point into the frame, the zoom/rotate step
 * arithmetic, the pan clamp, and the reference-photo choice.
 *
 * THE COMPOSITION CONTRACT. {@link #mapX}/{@link #mapY} state exactly what the Android
 * Matrix built by AlignView must do, in this order:
 *
 *     translate(-bmpW/2, -bmpH/2)        // bitmap centre to the origin
 *     scale(fit * zoom)                  // fit fills the frame; zoom is the user's pinch
 *     rotate(rotDeg)                     // about that origin, so zoom and rotate commute
 *     translate(frameW/2 + panX,         // origin back to the frame centre, plus the pan
 *               frameH/2 + panY)
 *
 * If AlignView's Matrix calls ever stop matching that order these functions still pass —
 * they cannot see the Matrix — so the order is stated here AND restated at the call site,
 * and the call site is the thing to re-read when the rendered result looks wrong.
 */
public final class Align {

    private Align() { }

    /* ------------------------------------------------------------------ limits */

    /** The rotation clamp, in degrees either way. A body photo taken hand-held is at most
     *  a few degrees off level; anything past this is not an alignment correction, it is a
     *  different photo. Kept small deliberately — a user who can rotate freely will rotate
     *  to match a ghost that was itself crooked, and then every later photo inherits the
     *  error. */
    public static final float MAX_ROT_DEG = 90f;

    /** Zoom is a multiplier ON TOP of the fit scale, so 1.0 is always "exactly filling the
     *  frame" regardless of the captured bitmap's pixel size. Below MIN the photo would sit
     *  inside its own frame with dead ground around it; above MAX the stored pixels are
     *  being upsampled past any real detail. */
    public static final float MIN_ZOOM = 0.5f;
    public static final float MAX_ZOOM = 4.0f;

    /** The +/- button's zoom step and the rotate button's step. The buttons exist so the
     *  screen is usable one-handed and reachable without a pinch at all (a pinch is not
     *  available to a switch-access or one-hand user); their steps are deliberately coarse
     *  enough to be worth a tap and fine enough to land on a match. */
    public static final float ZOOM_STEP = 0.1f;
    public static final float ROT_STEP_DEG = 1.0f;

    /** How far the photo may be panned out of the frame, as a fraction of the frame's own
     *  size. Half a frame in any direction is enough to put any part of the tube under the
     *  guide; unbounded panning only lets the user lose the photo off-screen entirely and
     *  then reach for Reset. */
    public static final float MAX_PAN_FRACTION = 0.5f;

    /** The ghost's default opacity, 0..1 — visible enough to line up against, faint enough
     *  that the NEW photo is still the thing being looked at. */
    public static final float DEFAULT_GHOST = 0.35f;

    /* --------------------------------------------------------------- the state */

    /** The user's transform, in the units {@link #mapX}/{@link #mapY} consume: `zoom` is a
     *  multiplier on the fit scale, `rotDeg` is clockwise degrees, `panX`/`panY` are frame
     *  pixels. Mutable and tiny by design — AlignView owns exactly one and hands it to the
     *  functions below; nothing here allocates per touch event. */
    public static final class T {
        public float zoom = 1f;
        public float rotDeg = 0f;
        public float panX = 0f;
        public float panY = 0f;

        public T copy() {
            T t = new T();
            t.zoom = zoom; t.rotDeg = rotDeg; t.panX = panX; t.panY = panY;
            return t;
        }

        /** True when this is the untouched transform — Reset's own target, and what the
         *  "Skip align" path is equivalent to. Compared with a tolerance because a pinch
         *  that ends exactly where it began still accumulates float error. */
        public boolean isIdentity() {
            return Math.abs(zoom - 1f) < 1e-4f
                && Math.abs(rotDeg) < 1e-4f
                && Math.abs(panX) < 1e-3f
                && Math.abs(panY) < 1e-3f;
        }
    }

    /** A fresh, untouched transform — Reset returns exactly this. */
    public static T identity() { return new T(); }

    /* ------------------------------------------------- the saved per-view profile */

    /**
     * How close the live transform has to be to the saved profile before the align frame
     * calls it a match. Deliberately GENEROUS relative to what a finger can hold: the point
     * of the green frame is "you are framing this the way you framed it last time", and a
     * tolerance tight enough that no human hand can satisfy it is a light that never comes
     * on. 5 % of zoom, 2 degrees, 8 frame pixels.
     */
    public static final float TOL_ZOOM = 0.05f;
    public static final float TOL_ROT_DEG = 2.0f;
    public static final float TOL_PAN_PX = 8.0f;

    /**
     * Whether `t` is within tolerance of the view's saved profile — the ONE rule behind the
     * align frame's green border, pure so the self-test can pin it without a Canvas.
     *
     * A null transform, a null profile, or a profile missing any component is NOT a match.
     * That last case is the one worth stating: filling an absent component in with the
     * identity would make a never-saved profile match a freshly-opened align stage, and the
     * user would be shown a green frame telling them they had reproduced a framing they had
     * never chosen. No profile means no claim.
     *
     * Compared on the CLAMPED transform, so a value the screen cannot actually reach cannot
     * be the thing that misses (or makes) the match — the same clamp the draw and the bake
     * both go through.
     */
    public static boolean matchesProfile(T t, Model.EditProfile p) {
        if (t == null || p == null || !p.complete()) return false;
        return Math.abs(clampZoom(t.zoom) - p.zoom.doubleValue()) <= TOL_ZOOM
            && Math.abs(clampRot(t.rotDeg) - p.rot.doubleValue()) <= TOL_ROT_DEG
            && Math.abs(t.panX - p.panX.doubleValue()) <= TOL_PAN_PX
            && Math.abs(t.panY - p.panY.doubleValue()) <= TOL_PAN_PX;
    }

    /** The profile a Save-as-default tap records: exactly the transform on screen, clamped
     *  the same way matchesProfile compares it, so saving and then immediately checking is
     *  always a match. */
    public static Model.EditProfile profileOf(T t) {
        if (t == null) return null;
        return Model.EditProfile.of(clampZoom(t.zoom), clampRot(t.rotDeg), t.panX, t.panY);
    }

    /* ---------------------------------------------------------------- clamping */

    /** Rotation clamped to plus/minus {@link #MAX_ROT_DEG}. NaN (the value a two-pointer
     *  angle produces when both pointers land on the same coordinate) collapses to 0 rather
     *  than propagating into the Matrix, where it would blank the photo with no way back
     *  except Reset. */
    public static float clampRot(float deg) {
        if (Float.isNaN(deg)) return 0f;
        if (deg > MAX_ROT_DEG) return MAX_ROT_DEG;
        if (deg < -MAX_ROT_DEG) return -MAX_ROT_DEG;
        return deg;
    }

    /** Zoom clamped to [{@link #MIN_ZOOM}, {@link #MAX_ZOOM}]. NaN and non-positive values
     *  collapse to 1 — a zero or negative scale in a Matrix collapses or mirrors the
     *  photo, and a mirrored body photo compared against an unmirrored one is exactly the
     *  false difference Photos/PreviewGeom already refuse to introduce. */
    public static float clampZoom(float z) {
        if (Float.isNaN(z) || z <= 0f) return 1f;
        if (z < MIN_ZOOM) return MIN_ZOOM;
        if (z > MAX_ZOOM) return MAX_ZOOM;
        return z;
    }

    /** One pan axis clamped to plus/minus (limit * {@link #MAX_PAN_FRACTION}), where
     *  `limit` is the frame's width for panX and its height for panY. A non-positive frame
     *  dimension (a view that has not been measured yet) pins the pan at 0 rather than
     *  clamping against garbage. */
    public static float clampPan(float v, int frameDim) {
        if (Float.isNaN(v) || frameDim <= 0) return 0f;
        float lim = frameDim * MAX_PAN_FRACTION;
        if (v > lim) return lim;
        if (v < -lim) return -lim;
        return v;
    }

    /** Ghost opacity clamped to 0..1, NaN to the default — the slider's own value, so a
     *  malformed progress reading can never produce an alpha the Paint rejects. */
    public static float clampGhost(float g) {
        if (Float.isNaN(g)) return DEFAULT_GHOST;
        if (g < 0f) return 0f;
        if (g > 1f) return 1f;
        return g;
    }

    /** The 0..255 alpha a 0..1 ghost opacity becomes for Paint.setAlpha — rounded, not
     *  truncated, so 1.0 is a true 255 rather than 254. */
    public static int ghostAlpha(float g) {
        return Math.round(clampGhost(g) * 255f);
    }

    /** Applies every clamp to a whole transform in one call — the single place a T becomes
     *  legal, so a gesture handler cannot forget one of the four. Mutates and returns the
     *  same object (there is one per screen; a copy per touch event is waste). */
    public static T clamp(T t, int frameW, int frameH) {
        if (t == null) return identity();
        t.zoom = clampZoom(t.zoom);
        t.rotDeg = clampRot(t.rotDeg);
        t.panX = clampPan(t.panX, frameW);
        t.panY = clampPan(t.panY, frameH);
        return t;
    }

    /* -------------------------------------------------------------- the steps */

    /** The + / - buttons: one {@link #ZOOM_STEP} in `dir`'s direction, clamped. Separate
     *  from the pinch path only in where the number comes from; both end in clampZoom, so
     *  a button can never reach a zoom a pinch cannot. */
    public static float steppedZoom(float zoom, int dir) {
        return clampZoom(zoom + ZOOM_STEP * dir);
    }

    /** The rotate-left / rotate-right buttons, same contract as {@link #steppedZoom}. */
    public static float steppedRot(float deg, int dir) {
        return clampRot(deg + ROT_STEP_DEG * dir);
    }

    /* -------------------------------------------- one-finger precision mode (round-2 C8) */
    /*
     * A long-press on the preview enters a one-finger mode where a vertical drag is FINE
     * ZOOM and a horizontal drag is FINE ROTATION in 0.1 degree steps — a second, precise
     * path alongside the pinch/twist gesture, for a framing correction too small for a
     * two-finger gesture to hold steady. Both are computed from the ABSOLUTE drag distance
     * since the long press landed, not accumulated per MOVE event, for the same reason the
     * two-finger rotation is computed from a start angle/start rotation pair (see
     * rotationDelta's doc): accumulating past the clamp banks degrees that silently unwind
     * when the drag reverses. The touch handling itself (long-press timing, the drag-start
     * point, the handoff to a second finger) lives in CameraScreen/AlignView, which owns the
     * View and the gesture state; what is testable without a device is only the arithmetic
     * below.
     */

    /** Pixels of horizontal drag per {@link #PRECISION_ROT_STEP_DEG} of rotation — fine
     *  enough that a full thumb's-width drag is a few degrees, coarse enough that landing on
     *  one 0.1 degree step does not need a steady hand. */
    public static final float PRECISION_PX_PER_ROT_STEP = 4f;

    /** The step a one-finger precision drag rotates in — ten times finer than
     *  {@link #ROT_STEP_DEG}'s button step, which is the entire point of a drag over a tap. */
    public static final float PRECISION_ROT_STEP_DEG = 0.1f;

    /** The rotation reached after dragging `dxPx` horizontally from the long-press point,
     *  starting at `startDeg` — rounded to {@link #PRECISION_ROT_STEP_DEG} steps and clamped
     *  by the same {@link #MAX_ROT_DEG} the two-finger gesture and the buttons are, so the
     *  live HUD readout this drives is always the number this returns, never a smoother
     *  in-between float the readout would have to round separately. */
    public static float precisionRot(float startDeg, float dxPx) {
        float steps = Math.round(dxPx / PRECISION_PX_PER_ROT_STEP);
        return clampRot(startDeg + steps * PRECISION_ROT_STEP_DEG);
    }

    /** The zoom reached after dragging `dyPx` vertically from the long-press point, starting
     *  at `startZoom`, inside a preview `viewHeightPx` tall: a full-height drag spans the
     *  whole {@link #MIN_ZOOM}..{@link #MAX_ZOOM} range, so the gesture covers the same range
     *  the pinch does regardless of screen size, and is clamped to that same range. Dragging
     *  UP zooms IN (`dyPx` negative), matching a pinch-out. An unmeasured view (`viewHeightPx`
     *  &lt;= 0) leaves the zoom exactly where the drag started rather than dividing by zero.
     *
     *  Clamped by hand to [MIN_ZOOM, MAX_ZOOM] here rather than through {@link #clampZoom} —
     *  a drag well past the bottom of a full-height range legitimately computes a raw value
     *  at or below zero, and clampZoom's non-positive case is its NaN/garbage-input fallback
     *  ("collapse to 1", the untouched fit), not a floor. Reusing it here would make an
     *  over-drag SNAP BACK to 100% zoom instead of settling at the 0.5 floor a pinch that
     *  goes the same distance would stop at — one drag, two different endings. NaN is still
     *  guarded so a garbage input cannot reach the comparisons below. */
    public static float precisionZoom(float startZoom, float dyPx, int viewHeightPx) {
        if (viewHeightPx <= 0) return clampZoom(startZoom);
        float range = MAX_ZOOM - MIN_ZOOM;
        float z = startZoom - (dyPx / viewHeightPx) * range;
        if (Float.isNaN(z)) return clampZoom(startZoom);
        if (z < MIN_ZOOM) return MIN_ZOOM;
        if (z > MAX_ZOOM) return MAX_ZOOM;
        return z;
    }

    /* ------------------------------------------------------------- frame fit */

    /**
     * The scale at which a bmpW x bmpH bitmap COVERS a frameW x frameH frame — the larger
     * of the two ratios, so the frame is filled and the overflow is cropped, never
     * letterboxed. The align frame is the thing being aligned INTO: dead ground inside it
     * would mean the guide's tube outline is being judged against nothing on one edge.
     *
     * Returns 1.0 for any non-positive input, so an unmeasured view yields an identity-ish
     * transform rather than a collapsed or infinite one. Mirrors PreviewGeom#coverScaleX's
     * own "cover, and fail safe on a zero" rule, deliberately: the two describe the same
     * intent at two different stages, and they must not disagree about it.
     */
    public static float fitScale(int bmpW, int bmpH, int frameW, int frameH) {
        if (bmpW <= 0 || bmpH <= 0 || frameW <= 0 || frameH <= 0) return 1f;
        float sx = (float) frameW / bmpW;
        float sy = (float) frameH / bmpH;
        return sx > sy ? sx : sy;
    }

    /** The total scale actually applied to the bitmap: the fit times the user's zoom. */
    public static float totalScale(int bmpW, int bmpH, int frameW, int frameH, float zoom) {
        return fitScale(bmpW, bmpH, frameW, frameH) * clampZoom(zoom);
    }

    /* ------------------------------------------------------ the composition */

    /**
     * Where bitmap pixel (bx, by) lands in frame coordinates, under the composition stated
     * in this class's doc. This is the assertable statement of what the Matrix must do; the
     * two are kept in step by the call site restating the order.
     */
    public static float mapX(float bx, float by, int bmpW, int bmpH,
                             int frameW, int frameH, T t) {
        T u = t == null ? identity() : t;
        float s = totalScale(bmpW, bmpH, frameW, frameH, u.zoom);
        float ux = (bx - bmpW / 2f) * s;
        float uy = (by - bmpH / 2f) * s;
        double r = Math.toRadians(clampRot(u.rotDeg));
        double cos = Math.cos(r), sin = Math.sin(r);
        return (float) (ux * cos - uy * sin) + frameW / 2f + u.panX;
    }

    /** The y counterpart of {@link #mapX}. */
    public static float mapY(float bx, float by, int bmpW, int bmpH,
                             int frameW, int frameH, T t) {
        T u = t == null ? identity() : t;
        float s = totalScale(bmpW, bmpH, frameW, frameH, u.zoom);
        float ux = (bx - bmpW / 2f) * s;
        float uy = (by - bmpH / 2f) * s;
        double r = Math.toRadians(clampRot(u.rotDeg));
        double cos = Math.cos(r), sin = Math.sin(r);
        return (float) (ux * sin + uy * cos) + frameH / 2f + u.panY;
    }

    /* ------------------------------------------------------- gesture arithmetic */

    /** The angle in degrees of the line from (x0,y0) to (x1,y1) — the raw two-pointer
     *  angle onTouchEvent computes. The ROTATION is the difference between this and the
     *  angle at the moment the second finger went down; that subtraction is
     *  {@link #rotationDelta}, so neither the reference angle nor the wrap-around is left
     *  to the touch handler. */
    public static float angleDeg(float x0, float y0, float x1, float y1) {
        return (float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));
    }

    /**
     * The signed rotation from `startDeg` to `nowDeg`, normalised into (-180, 180]. Without
     * the normalisation a gesture that crosses the atan2 discontinuity (the -180/+180 seam,
     * which is straight left-to-right for a two-finger grip) reports a ~360 degree jump and
     * the photo flips instantly. Not clamped here — the caller adds it to the rotation it
     * started from and clamps THAT, so the clamp applies to the result rather than to the
     * increment.
     */
    public static float rotationDelta(float startDeg, float nowDeg) {
        float d = nowDeg - startDeg;
        while (d <= -180f) d += 360f;
        while (d > 180f) d -= 360f;
        return d;
    }

    /* ------------------------------------------------------- the reference photo */

    /**
     * The reading whose photo is the GHOST for a new capture of `view`, or null when there
     * is none: the most recent reading STRICTLY EARLIER than `newTs` that has a photo for
     * that same view. `ps` is {@link Compare#withPhotos}'s output — newest-first — so the
     * newest qualifying entry is the answer.
     *
     * Three rules, each of which the ghost would be wrong without:
     *
     *   SAME VIEW ONLY. `ps` is already filtered to one view by withPhotos; ghosting a
     *   Front photo under a Side capture would have the user aligning to framing, not to
     *   tissue — the same refusal Compare.withPhotos makes for the comparison itself.
     *
     *   STRICTLY EARLIER. `&gt;=` would let a reading logged in the same millisecond, or the
     *   reading currently being photographed if it were already in the log, ghost itself.
     *
     *   NEVER THIS READING. `excludeId` drops the reading being captured for even when its
     *   timestamp says otherwise — a RETAKE of a view that was already committed this
     *   session must ghost against the PREVIOUS session's photo, not against the shot it is
     *   replacing, or every retake silently re-aligns to itself and the chain drifts.
     */
    public static Model.Reading referenceFor(List<Model.Reading> ps, long newTs, String excludeId) {
        if (ps == null) return null;
        Model.Reading best = null;
        for (int i = 0; i < ps.size(); i++) {
            Model.Reading r = ps.get(i);
            if (r == null) continue;
            if (excludeId != null && excludeId.equals(r.id)) continue;
            if (r.ts >= newTs) continue;
            if (best == null || r.ts > best.ts) best = r;
        }
        return best;
    }

    /** The sentence the screen prints when there IS a reference — it names the date being
     *  aligned against, because "ghost" alone does not say WHICH photo is underneath. The
     *  caller passes the already-drawn date string, the same discipline A11y's UNIT RULE
     *  applies to values. */
    public static String referenceLine(String dayLabel) {
        return "Ghosted against " + dayLabel + " — line the tube up with it.";
    }

    /** The sentence when there is none. Said quietly and completely: this is the FIRST
     *  photo of this view, so there is nothing to be wrong about, and the guide alone is
     *  still worth using because it is what the NEXT photo will be ghosted against. */
    public static String noReferenceLine(String view) {
        return "No earlier " + view + " photo to line up against — this one becomes the "
             + "reference. Fit the tube inside the guide.";
    }

    /** The align frame's accessibility name. The frame is a Canvas: without this it is the
     *  largest element on the screen and the only one a screen reader cannot see at all —
     *  the same gap CompareScreen's stage description closes. Says what is on it and where
     *  the photo currently sits, so the +/- and rotate buttons have a readout to move. */
    public static String frameName(String view, boolean hasGhost, float zoom, float rotDeg) {
        return "The " + view + " photo just taken, inside the alignment guide"
             + (hasGhost ? ", with the previous photo ghosted underneath" : "")
             + ". Zoom " + pct(zoom) + ", rotation " + degrees(rotDeg) + ".";
    }

    /** "120 %" — the zoom as the screen itself prints it. */
    public static String pct(float zoom) {
        return Math.round(clampZoom(zoom) * 100f) + " %";
    }

    /** "3 degrees right" / "3 degrees left" / "level" — spoken words, not a signed number,
     *  because a screen reader saying "minus three" leaves the direction to be guessed. */
    public static String degrees(float rotDeg) {
        float d = clampRot(rotDeg);
        int r = Math.round(d);
        if (r == 0) return "level";
        return Math.abs(r) + (Math.abs(r) == 1 ? " degree " : " degrees ")
             + (r > 0 ? "right" : "left");
    }

    /* ------------------------------------------------------------ cylinder guide */

    /*
     * The guide is a vertical tube with rounded ends, centred in the frame — the fixed
     * target that makes "same distance, same angle, every time" a thing the user can SEE
     * rather than remember. Its geometry is stated here as fractions of the frame so the
     * drawing code holds no numbers of its own, and so the shape is identical on every
     * screen size.
     *
     * THE SHAPE IS A REAL PUMP CYLINDER: a 2 inch diameter by 9 inch length tube, so the
     * silhouette the user aligns into is the actual cylinder, not a generic pill. In the
     * screen's square align frame the diameter is 2/10 of the frame across and the length is
     * 9/10 down — 0.2 : 0.9 = 2 : 9, the true aspect. The user centres the tube's two long
     * side edges and seats its base flange, exactly as the cylinder seats on the body.
     */

    /** The tube's DIAMETER as a fraction of the frame's width — 2 of 10, half of the 2:9
     *  cylinder's aspect (0.2 : 0.9 = 2 : 9). */
    public static final float GUIDE_W_FRACTION = 2f / 10f;
    /** The tube's LENGTH as a fraction of the frame's height — 9 of 10 (see above). */
    public static final float GUIDE_H_FRACTION = 9f / 10f;

    public static float guideW(int frameW) { return frameW <= 0 ? 0f : frameW * GUIDE_W_FRACTION; }
    public static float guideH(int frameH) { return frameH <= 0 ? 0f : frameH * GUIDE_H_FRACTION; }

    /** The tube's edges in frame pixels. These exist so the drawing code asks for
     *  coordinates rather than computing them, and so the "centred" claim is asserted
     *  rather than eyeballed. */
    public static float guideLeft(int frameW)   { return (frameW - guideW(frameW)) / 2f; }
    public static float guideRight(int frameW)  { return (frameW + guideW(frameW)) / 2f; }
    public static float guideTop(int frameH)    { return (frameH - guideH(frameH)) / 2f; }
    public static float guideBottom(int frameH) { return (frameH + guideH(frameH)) / 2f; }

    /** The radius of the tube's rounded ends — half its width, so the ends are true
     *  semicircles and the outline reads as a tube rather than as a rounded rectangle.
     *  Clamped to half the tube's HEIGHT too, so an absurdly short frame degenerates into
     *  a circle rather than into a self-intersecting outline. */
    public static float guideRadius(int frameW, int frameH) {
        float r = guideW(frameW) / 2f;
        float half = guideH(frameH) / 2f;
        return r > half ? half : r;
    }

    /* ------------------------------------------------------ pump silhouette details
     * The guide is meant to read as THE PUMP — a simulated silhouette whose two straight
     * side edges are what the user centres against — not a generic pill. Two features make
     * a plain tube read as a pump cylinder: a wider BASE FLANGE at the bottom (the seal
     * skirt / gaiter end) and a narrow RIM at the top (the cap). Both are stated as fractions
     * of the tube so they scale with it, and both are drawn as thin extra outlines so the
     * user still sees the photo through them. */

    /** How much wider than the tube the base flange is, per side (fraction of tube width).
     *  A real cylinder's seal skirt flares well past the bore, so on the narrow 2:9 tube the
     *  flange reads as a distinct base ring rather than a hairline. */
    public static final float FLANGE_OVERHANG_FRACTION = 0.35f;
    /** The flange's height as a fraction of the tube's LENGTH — a short band at the base of
     *  the long 9-unit tube. */
    public static final float FLANGE_H_FRACTION = 0.035f;
    /** The cap rim's height as a fraction of the tube's length — thinner than the flange, a
     *  flush ring at the closed top end. */
    public static final float RIM_H_FRACTION = 0.025f;

    public static float flangeLeft(int frameW)  { return guideLeft(frameW)  - guideW(frameW) * FLANGE_OVERHANG_FRACTION; }
    public static float flangeRight(int frameW) { return guideRight(frameW) + guideW(frameW) * FLANGE_OVERHANG_FRACTION; }
    /** The flange sits at the tube's bottom, its top edge this far above the tube bottom. */
    public static float flangeTop(int frameH)   { return guideBottom(frameH) - guideH(frameH) * FLANGE_H_FRACTION; }
    /** The rim sits at the tube's top, its bottom edge this far below the tube top. */
    public static float rimBottom(int frameH)   { return guideTop(frameH) + guideH(frameH) * RIM_H_FRACTION; }

    /* ================================================================= FRAMING
     *
     * WHY THIS EXISTS. Every function above assumes the tube runs VERTICALLY: the guide is
     * a portrait silhouette in a square frame, and a photo framed landscape — phone turned
     * on its side, which is how a lot of these are actually taken — has nothing to line up
     * against. The user then either aligned against a guide that did not describe their
     * photo, or gave up on aligning at all, and either way the chain drifts, which is the
     * one thing this whole screen exists to prevent.
     *
     * WHAT CHANGES, exactly: which frame axis the tube's LONG dimension is measured
     * against. Nothing else. The tube keeps the same proportions (GUIDE_W_FRACTION across,
     * GUIDE_H_FRACTION along), the same flange at its BASE end and the same rim at its CAP
     * end; landscape simply lays it on its side with the base at the RIGHT. The portrait
     * answers below are IDENTICAL to the single-argument functions above — asserted, so the
     * two families cannot drift apart and the older call sites keep working untouched.
     *
     * Rectangles are returned as {left, top, right, bottom} float arrays rather than as
     * four more overloads each: eight orientation-aware edge functions is eight places a
     * landscape/portrait flip can be got half right.
     */

    /** The tube's ACROSS dimension in frame pixels — measured against the frame's width in
     *  portrait and against its height in landscape. */
    public static float tubeCross(int frameW, int frameH, boolean landscape) {
        int dim = landscape ? frameH : frameW;
        return dim <= 0 ? 0f : dim * GUIDE_W_FRACTION;
    }

    /** The tube's ALONG dimension — the axis it runs down (portrait) or across (landscape). */
    public static float tubeAlong(int frameW, int frameH, boolean landscape) {
        int dim = landscape ? frameW : frameH;
        return dim <= 0 ? 0f : dim * GUIDE_H_FRACTION;
    }

    /** The tube outline as {left, top, right, bottom}, centred in the frame. */
    public static float[] tubeRect(int frameW, int frameH, boolean landscape) {
        float cross = tubeCross(frameW, frameH, landscape);
        float along = tubeAlong(frameW, frameH, landscape);
        float w = landscape ? along : cross;
        float h = landscape ? cross : along;
        return new float[] {
            (frameW - w) / 2f, (frameH - h) / 2f,
            (frameW + w) / 2f, (frameH + h) / 2f
        };
    }

    /** The rounded-end radius — half the ACROSS dimension, clamped to half the ALONG one so
     *  a degenerate frame produces a circle rather than a self-intersecting outline. Same
     *  rule as {@link #guideRadius}, stated once for both framings. */
    public static float tubeRadius(int frameW, int frameH, boolean landscape) {
        float r = tubeCross(frameW, frameH, landscape) / 2f;
        float half = tubeAlong(frameW, frameH, landscape) / 2f;
        return r > half ? half : r;
    }

    /** The base flange (the seal skirt) as {left, top, right, bottom}: a band across the
     *  tube's BASE end — the BOTTOM in portrait, the RIGHT end in landscape — overhanging
     *  the tube by {@link #FLANGE_OVERHANG_FRACTION} on each side. */
    public static float[] flangeRect(int frameW, int frameH, boolean landscape) {
        float[] t = tubeRect(frameW, frameH, landscape);
        float over = tubeCross(frameW, frameH, landscape) * FLANGE_OVERHANG_FRACTION;
        float thick = tubeAlong(frameW, frameH, landscape) * FLANGE_H_FRACTION;
        if (landscape) return new float[] { t[2] - thick, t[1] - over, t[2], t[3] + over };
        return new float[] { t[0] - over, t[3] - thick, t[2] + over, t[3] };
    }

    /** The cap rim as {left, top, right, bottom}: a narrow band across the tube's FAR end —
     *  the TOP in portrait, the LEFT end in landscape. No overhang; the cap is flush. */
    public static float[] rimRect(int frameW, int frameH, boolean landscape) {
        float[] t = tubeRect(frameW, frameH, landscape);
        float thick = tubeAlong(frameW, frameH, landscape) * RIM_H_FRACTION;
        if (landscape) return new float[] { t[0], t[1], t[0] + thick, t[3] };
        return new float[] { t[0], t[1], t[2], t[1] + thick };
    }

    /** The tube's centre axis as {x0, y0, x1, y1}, drawn between the rim and the flange
     *  only — outside them it would read as a crop mark rather than as the axis "square to
     *  the lens" is judged against. */
    public static float[] axisLine(int frameW, int frameH, boolean landscape) {
        float[] rim = rimRect(frameW, frameH, landscape);
        float[] fl = flangeRect(frameW, frameH, landscape);
        if (landscape) return new float[] { rim[2], frameH / 2f, fl[0], frameH / 2f };
        return new float[] { frameW / 2f, rim[3], frameW / 2f, fl[1] };
    }

    /** The word for a framing — used in the toggle's label, its spoken name and the frame's
     *  own description, so all three say the same thing. */
    public static String framingName(boolean landscape) {
        return landscape ? "landscape" : "portrait";
    }

    /** The framing toggle's accessibility name. Says which framing is ON and what a tap
     *  would change it to — a toggle whose label is only its current state leaves the user
     *  to guess whether tapping confirms or flips it. */
    public static String framingToggleName(boolean landscape) {
        return "Guide framing: " + framingName(landscape) + ". Tap for "
             + framingName(!landscape) + ".";
    }

    /* ------------------------------------------------------------ quarter turns
     *
     * A photo comes back from the system camera in whatever orientation the camera app
     * decided, and the EXIF-upright decode (Photos.decodeOriented) makes it upright as the
     * FILE claims — which is not the same as upright as the USER framed it. A landscape
     * capture of a portrait subject cannot be aligned, cannot be ghosted and cannot be
     * compared, and the fine +/-20 degree align rotation deliberately cannot fix it.
     *
     * THE STORAGE DECISION, stated here because it is the one thing everything downstream
     * depends on: a quarter turn ROTATES THE PIXELS before the file is written. It is not a
     * field on the record applied at every decode. The align stage already bakes its
     * transform into the saved pixels (see CameraScreen#bakeAligned), so this rides the
     * path that exists rather than adding a second, parallel notion of "which way up is
     * this" that every reader — the gallery, Compare, then-vs-now, the align ghost, an
     * export — would have to remember to honour, and that exactly one of them would
     * eventually forget. A saved photo is upright, full stop; there is nothing to migrate
     * and nothing for a reader to get wrong.
     *
     * What lives here is only the arithmetic of the button: the cumulative turn, its spoken
     * name, and which way a ghost has to be turned to match the chosen framing.
     */

    /** One tap of the rotate button: the next quarter turn clockwise, always one of
     *  0/90/180/270. Any input — negative, out of range, not a multiple of 90 — normalises
     *  into that set rather than accumulating a value the readout cannot name. */
    public static int nextQuarter(int deg) {
        int n = ((deg + 90) % 360 + 360) % 360;
        return (n / 90) * 90;
    }

    /** How far the photo has been turned, in words — the readout and the spoken name.
     *  Never a bare signed number: "270" tells a screen-reader user nothing about which
     *  way the photo is now facing. */
    public static String quarterName(int deg) {
        int n = ((deg % 360) + 360) % 360;
        n = (n / 90) * 90;
        if (n == 0) return "as taken";
        if (n == 180) return "turned upside down";
        return "turned a quarter turn " + (n == 90 ? "right" : "left");
    }

    /**
     * How far the GHOST must be turned to be worth lining up against, given the framing the
     * user has chosen: a quarter turn when the ghost's own shape disagrees with that
     * framing, and 0 when it already agrees. A square ghost agrees with everything and is
     * never turned.
     *
     * This is what makes the framing toggle honest rather than decorative. Turning the
     * guide on its side while leaving a portrait ghost upright underneath would ask the
     * user to align to two contradictory references at once — and the ghost, being an
     * actual photo, is the one they would follow.
     */
    public static int ghostQuarter(int ghostW, int ghostH, boolean landscapeFrame) {
        if (ghostW <= 0 || ghostH <= 0 || ghostW == ghostH) return 0;
        boolean ghostIsLandscape = ghostW > ghostH;
        return ghostIsLandscape == landscapeFrame ? 0 : 90;
    }
}
