package org.openpump;

import java.util.List;

/**
 * THE ONE CHART LANGUAGE FOR A SET: PRESSURE OVER TIME, for fixed and ramp alike.
 *
 * WHAT WAS WRONG WITH THE OLD PICTURE. A ramp used to be drawn as one BAR PER STEP,
 * climbing left to right. That is a histogram, not a chart of anything the pump does, and
 * it threw away three of the four things a step actually is. A ramp step is a WHOLE
 * PRESET — its own pull, its own drop, its own two hold times, its own duration — and the
 * device cycles pull → hold → drop → hold INSIDE that step before moving to the next one.
 * So the bars hid the drop entirely, hid the hold (which on a typical ramp shrinks from
 * 30 s to 5 s, a change of character the user chose deliberately), and hid the pulsing.
 * Worse, a FIXED set was drawn as a pulse waveform, so the two modes spoke two different
 * chart languages for the same idea and neither could be compared with the other.
 *
 * WHAT THIS DRAWS INSTEAD. The shape the pump will really execute, over time: a pulse
 * waveform whose peaks CLIMB step by step and whose plateaus change width as the holds
 * change. A fixed set is the degenerate case of exactly the same construction — one step,
 * one repeated pulse, a flat envelope — so there is ONE renderer and one language, which
 * is the actual fix.
 *
 * WHY IT IS PURE. The Canvas that strokes these points can only be judged on a device.
 * The points themselves are arithmetic over Model.Set#ladder(), and the claims that matter
 * are all claims about the numbers: that a fixed set yields identical repeated pulses, that
 * a ramp's peaks move monotonically toward its end target, that shrinking holds yield
 * shrinking plateaus, and that the envelope really does span the start pull to the end
 * pull. Those are asserted in SelfTest, and they are the whole reason to trust the picture.
 *
 * COORDINATES. x runs 0..1 across the set's DURATION; y runs 0..1 where 1 is full scale
 * (the deepest pressure the plot is drawn against). The caller maps them to pixels — with
 * y=0 at the bottom — and decides colour. No pressure is FORMATTED here: every label the
 * chart carries goes through Model.Fmt.p/d at the call site, so it follows the display unit
 * and reads negative in inHg.
 */
public final class Waveform {
    private Waveform() { }

    /**
     * The largest number of pulses the whole shape is drawn with. A four-minute fixed set
     * with a 4 s hold and a 3 s drop really contains thirty-four cycles; drawn at thumbnail
     * width that is a grey smear with no readable plateau. So the picture is a SCHEMATIC of
     * the period's shape rather than a cycle-accurate transcript — which is what the set
     * editor's preview has always been — and the count is capped. The truncation is
     * reported ({@link Shape#truncated}) so a caller can say so rather than implying the
     * set is shorter than it is.
     */
    public static final int MAX_CYCLES = 24;

    /** How much of one cycle the rise and the fall each take, as a fraction of the hold
     *  time they lead into. The pump's real ramp rate is not a constant and is not what
     *  this picture is claiming; a visible slope simply distinguishes a transition from a
     *  plateau. Small enough that a long hold still reads as a long plateau. */
    public static final float RISE_UNITS = 0.5f;

    /** One set's drawable shape. Arrays rather than objects: this is built on every
     *  onDraw of every card in a list. */
    public static final class Shape {
        /** The polyline, normalised. Same length, index-aligned. */
        public final float[] x;
        public final float[] y;
        /** Which ladder step each point belongs to; -1 for the single leading point that
         *  sits on the baseline before the first rise. Drives the run screen's numbered
         *  step bands. */
        public final int[] step;
        /** Band boundaries in x, one pair per ladder step, partitioning [0, 1]. */
        public final float[] stepStart;
        public final float[] stepEnd;
        /** The envelope: the first step's pull and the last step's pull, as y. On a fixed
         *  set these are equal and the envelope is flat — which is exactly right, and is
         *  why the same renderer serves both modes. */
        public final float envStartY;
        public final float envEndY;
        /** The pressure 1.0 means, so the caller can label the axis through Model.Fmt.p. */
        public final double fullScaleKpa;
        /** True when the real cycle count exceeded MAX_CYCLES and the picture is a
         *  schematic of the period rather than a transcript of every pulse. */
        public final boolean truncated;

        Shape(float[] x, float[] y, int[] step, float[] ss, float[] se,
              float envStartY, float envEndY, double fullScaleKpa, boolean truncated) {
            this.x = x; this.y = y; this.step = step;
            this.stepStart = ss; this.stepEnd = se;
            this.envStartY = envStartY; this.envEndY = envEndY;
            this.fullScaleKpa = fullScaleKpa; this.truncated = truncated;
        }

        public int size() { return x.length; }
        public int steps() { return stepStart.length; }
    }

    /**
     * The pressure the plot is drawn against: the deepest pull in the ladder, or the
     * safety ceiling if that is higher, so the ceiling line is always on the chart rather
     * than off the top of it. Floored at 1 so an all-zero set cannot divide by zero.
     */
    public static double fullScaleKpa(List<Model.Preset> ladder, int ceilKpa) {
        double max = ceilKpa > 0 ? ceilKpa : 1;
        if (ladder != null)
            for (int i = 0; i < ladder.size(); i++)
                if (ladder.get(i).up > max) max = ladder.get(i).up;
        return max < 1 ? 1 : max;
    }

    /**
     * Builds the shape for one ladder — the very list of Presets the pump will be sent, so
     * the picture cannot drift away from the commands.
     *
     * Within each step the cycle is rise → hold(uh) → fall → hold(lh), with the two hold
     * times setting the plateau widths. How many cycles a step is drawn with is how many
     * really fit in its duration, capped so the whole shape stays under MAX_CYCLES; the cap
     * is shared out per step rather than by truncating the time axis, because on a ramp the
     * later steps are the point of the picture and a truncated window would cut off the
     * climb it exists to show.
     */
    public static Shape of(List<Model.Preset> ladder, double fullScaleKpa) {
        int n = (ladder == null) ? 0 : ladder.size();
        if (n == 0 || fullScaleKpa <= 0)
            return new Shape(new float[0], new float[0], new int[0],
                             new float[0], new float[0], 0f, 0f,
                             fullScaleKpa <= 0 ? 1 : fullScaleKpa, false);

        long totalMs = 0;
        for (int i = 0; i < n; i++) totalMs += Math.max(1L, ladder.get(i).durMs);

        int maxPerStep = Math.max(1, MAX_CYCLES / n);
        boolean truncated = false;

        // Two passes: count the points, then fill. One allocation each rather than a
        // growing List of float[2], because this runs inside onDraw.
        int[] cycles = new int[n];
        int points = 1;                                    // the leading baseline point
        for (int i = 0; i < n; i++) {
            Model.Preset p = ladder.get(i);
            double stepSec = Math.max(1L, p.durMs) / 1000.0;
            double period = Math.max(1, p.uh) + Math.max(1, p.lh);
            int want = (int) Math.max(1, Math.round(stepSec / period));
            if (want > maxPerStep) { want = maxPerStep; truncated = true; }
            cycles[i] = want;
            points += want * 4;
        }

        float[] xs = new float[points], ys = new float[points];
        int[] step = new int[points];
        float[] ss = new float[n], se = new float[n];

        int k = 0;
        // The pump starts from ambient, so the line starts on the baseline and rises into
        // the first pull rather than beginning already at pressure.
        xs[k] = 0f; ys[k] = 0f; step[k] = -1; k++;

        long cumMs = 0;
        for (int i = 0; i < n; i++) {
            Model.Preset p = ladder.get(i);
            long durMs = Math.max(1L, p.durMs);
            float x0 = (float) (cumMs / (double) totalMs);
            float x1 = (float) ((cumMs + durMs) / (double) totalMs);
            ss[i] = x0; se[i] = x1;

            float upY = (float) (p.up / fullScaleKpa);
            float loY = (float) (p.lo / fullScaleKpa);
            if (upY > 1f) upY = 1f;
            if (loY < 0f) loY = 0f;

            int cyc = cycles[i];
            float cw = (x1 - x0) / cyc;
            float uh = Math.max(1, p.uh), lh = Math.max(1, p.lh);
            float units = RISE_UNITS + uh + RISE_UNITS + lh;

            for (int c = 0; c < cyc; c++) {
                float base = x0 + c * cw;
                float t = 0;
                t += RISE_UNITS; xs[k] = base + cw * (t / units); ys[k] = upY; step[k] = i; k++;
                t += uh;         xs[k] = base + cw * (t / units); ys[k] = upY; step[k] = i; k++;
                t += RISE_UNITS; xs[k] = base + cw * (t / units); ys[k] = loY; step[k] = i; k++;
                t += lh;         xs[k] = base + cw * (t / units); ys[k] = loY; step[k] = i; k++;
            }
            cumMs += durMs;
        }

        float envStart = (float) (ladder.get(0).up / fullScaleKpa);
        float envEnd = (float) (ladder.get(n - 1).up / fullScaleKpa);
        if (envStart > 1f) envStart = 1f;
        if (envEnd > 1f) envEnd = 1f;

        return new Shape(xs, ys, step, ss, se, envStart, envEnd, fullScaleKpa, truncated);
    }

    /* --------------------------------------------------------- what a caller asks */

    /** The y of each step's PEAK, in order — the sequence the envelope traces and the one
     *  "does this ramp actually climb" is a question about. */
    public static float[] peaks(List<Model.Preset> ladder, double fullScaleKpa) {
        int n = (ladder == null) ? 0 : ladder.size();
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            float v = (float) (ladder.get(i).up / fullScaleKpa);
            out[i] = v > 1f ? 1f : v;
        }
        return out;
    }

    /**
     * The width, in x, of one step's UPPER plateau — the visible consequence of its hold
     * time. This is what makes "hold 30 s shrinking to 5 s" a thing the eye can see rather
     * than a number in a tile, and it is asserted for exactly that reason.
     */
    public static float plateauWidth(Shape s, int stepIdx) {
        if (s == null || stepIdx < 0 || stepIdx >= s.steps()) return 0f;
        for (int i = 1; i < s.size(); i++) {
            if (s.step[i] != stepIdx) continue;
            // The first point of a step is the top of its rise; the next is the end of its
            // upper hold. The gap between them IS the plateau.
            if (i + 1 < s.size() && s.step[i + 1] == stepIdx) return s.x[i + 1] - s.x[i];
            return 0f;
        }
        return 0f;
    }

    /** Which step contains a given position along the set, 0..1 — the run screen's "which
     *  step am I in", answered from the same partition the bands are drawn from rather
     *  than from a second walk that could disagree. -1 when out of range. */
    public static int stepAt(Shape s, float frac) {
        if (s == null) return -1;
        for (int i = 0; i < s.steps(); i++)
            if (frac >= s.stepStart[i] && (frac < s.stepEnd[i] || i == s.steps() - 1))
                return i;
        return -1;
    }
}
