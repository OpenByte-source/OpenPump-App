package org.openpump;

/**
 * THE PRESSURE TRACE — the decidable half of the run screen's live "ECG", kept PURE (no
 * `import android`) so test.sh compiles it and SelfTest asserts on it. The DRAWING is a
 * Canvas and can only be judged on a device; everything the drawing is derived FROM is
 * arithmetic and lives here.
 *
 * WHAT THE TRACE IS. A scrolling window of the pressure telemetry actually received,
 * newest at the right, with the commanded setpoint drawn as a dashed line across it. It is
 * AMBER throughout, because it is a pressure and pressure is the one thing amber means. It
 * is not a decoration with a data source bolted on: every sample in it arrived from the
 * device.
 *
 * THE GAP RULE, WHICH IS THE WHOLE REASON THIS IS PURE. A 0.0 reading from this device
 * means NO MEASUREMENT — never ambient, never zero pressure. A trace that joined the
 * samples either side of one would draw a plunge to the floor and back that never
 * happened, and on a screen whose entire job is to show what the cuff is doing that is the
 * worst possible lie. So a no-reading sample is a BREAK in the line ({@link #isGap}), and
 * the break is asserted rather than left to an onDraw nothing can run. The same bug in the
 * PC-side analyser once produced a slope of 383 kPa/s across exactly such a gap.
 *
 * THE BREATHING. The trace's glow and the amber halo behind the screen swell with the
 * measured pressure — the one animation on this screen, and the only honest one, because
 * it is driven by telemetry rather than by a clock. The NUMBER never animates: an
 * instrument readout that eases between values is showing a value the instrument never
 * read. {@link #glowAlpha} and {@link #haloAlpha} are that swell, and they FALL BACK TO
 * THEIR FLOOR when there is no reading, so a silent link makes the screen go still rather
 * than leaving it breathing at whatever it last saw.
 */
public final class Trace {
    private Trace() { }

    /** How many samples the window holds. At the device's ~4.16 Hz telemetry rate this is
     *  a little under half a minute of history — long enough to show the shape of a pull
     *  and its hold, short enough that the newest sample is still moving visibly. */
    public static final int WINDOW = 120;

    /** The vertical scale's floor, in kPa. Without one, a run that never left 2 kPa would
     *  be magnified into a dramatic waveform; with it, a quiet trace looks quiet. */
    public static final double MIN_SCALE_KPA = 10.0;

    /** Headroom above the largest value in view, as a fraction, so the peak of a pull is
     *  not drawn hard against the top edge of the plot. */
    public static final double HEADROOM = 0.12;

    /** A no-reading sample. 0.0 is the device saying it is not measuring. */
    public static boolean isGap(double kpa, boolean noReading) {
        return noReading || kpa <= 0.0;
    }

    /**
     * The full-scale pressure the plot is drawn against: the largest of the commanded
     * setpoint, the largest sample in view, and the floor — plus headroom. Taking the
     * COMMANDED value into the maximum matters: without it, a pull that is falling short
     * would rescale until it looked like it was on target, which is exactly the failure the
     * dashed commanded line exists to make visible.
     *
     * THE HEADROOM IS PART OF THE RULE, not a decoration on top of it. With no samples at
     * all — which is what an open system streaming 0.0 gives you — the maximum IS the
     * commanded value, and without the (1 + HEADROOM) term yFraction(commanded, full)
     * would be exactly 1.0 and the commanded line would be drawn along the very top edge
     * of the plot, unreadable and indistinguishable from a border. SelfTest pins that: for
     * any commanded value the line sits strictly inside the plot.
     */
    public static double fullScaleKpa(double[] kpa, boolean[] noReading, double commandedKpa) {
        double max = MIN_SCALE_KPA;
        if (commandedKpa > max) max = commandedKpa;
        if (kpa != null) {
            for (int i = 0; i < kpa.length; i++) {
                boolean gap = noReading != null && i < noReading.length && noReading[i];
                if (isGap(kpa[i], gap)) continue;
                if (kpa[i] > max) max = kpa[i];
            }
        }
        return max * (1.0 + HEADROOM);
    }

    /* ------------------------------------------- the axis, in round numbers */

    /** The steps an axis counts in, per power of ten (polish item 9). */
    private static final double[] NICE_STEPS = { 1, 2, 2.5, 5 };
    /** The most gridlines the run chart carries, the zero line included. */
    public static final int AXIS_MAX_LINES = 5;

    /**
     * THE RUN CHART'S GRIDLINES, IN ROUND NUMBERS OF THE DISPLAY UNIT (polish item 9).
     *
     * The axis used to divide the plot into quarters and label each - −5.5, −3.6, −1.8 -
     * thirds of whatever the peak was, not numbers anyone counts in. It now counts from 0
     * at the bottom in the smallest step of 1, 2, 2.5 or 5 (times a power of ten) that
     * keeps the plot to {@link #AXIS_MAX_LINES} lines; the step before it gave six or more,
     * so there are never fewer than three. The plot's own top (fullScaleKpa, with its
     * headroom) is unchanged - the lines are simply the round values under it, so the
     * commanded line never sits on the top edge and the top line is never above it.
     *
     * @param fullScaleKpa the plot's full scale, as {@link #fullScaleKpa} gives it
     * @param unitsPerKpa  the display unit per kPa (Model.Fmt#perKpa)
     * @return the lines' values in the DISPLAY unit, as magnitudes, 0 first
     */
    public static double[] axisTicks(double fullScaleKpa, double unitsPerKpa) {
        double top = fullScaleKpa * unitsPerKpa;
        if (!(top > 0) || Double.isInfinite(top)) return new double[]{ 0 };
        double step = axisStep(top);
        int n = (int) Math.floor(top / step + 1e-9) + 1;
        double[] t = new double[n];
        for (int i = 0; i < n; i++) t[i] = Math.rint(i * step * 1e6) / 1e6;
        return t;
    }

    /** The smallest round step that keeps a plot `top` display units tall to
     *  {@link #AXIS_MAX_LINES} lines. */
    static double axisStep(double top) {
        double decade = Math.pow(10, Math.floor(Math.log10(top)) - 2);
        for (int k = 0; k < 6; k++, decade *= 10) {
            for (int i = 0; i < NICE_STEPS.length; i++) {
                double s = NICE_STEPS[i] * decade;
                if (Math.floor(top / s + 1e-9) + 1 <= AXIS_MAX_LINES) return s;
            }
        }
        return top;
    }

    /**
     * ROUND-NUMBER GRIDLINES FOR A RANGE THAT DOES NOT START AT 0 (polish item 14,
     * generalising the run chart's axis above to the Progress screen's measurement and
     * volume charts). {@link #axisTicks(double, double)} is anchored at 0 and never draws a
     * line past its own top, because that top already carries the plot's headroom baked in
     * by {@link #fullScaleKpa}. A length, girth or volume window has no such ceiling — its
     * two axis labels used to be the raw min and max of whatever was on screen ("12.6" /
     * "11.5", bug B2), which is not a number anyone counts in either. So THIS axis rounds
     * BOTH ends outward to the nearest step, and may extend past the data on either side —
     * the plot's own vertical scale extends with it (see SessionActivity.TrendChart#extent,
     * which uses this array's own first and last value as the plot's vMin/vMax, not only
     * as label text).
     *
     * Same {@link #NICE_STEPS} table item 9 chose, tried smallest first; same idea of
     * capping the LINE COUNT rather than deriving a step by formula and hoping it lands —
     * except the count here is taken AFTER both ends are snapped outward, since snapping
     * can add a line the raw span's own division would not predict (a span of 1.1 at a
     * step of 0.2 costs two extra partial steps once both ends round away from it). A range
     * that already sits exactly on a step is not pushed past it — floor and ceil both carry
     * a tiny epsilon so a value that IS a multiple of the step (to floating-point noise)
     * keeps its own end rather than gaining a wasted extra line.
     *
     * @param min       one end of the range to cover, in whatever unit the caller wants the
     *                  gridlines labelled in (the DISPLAY unit — the caller converts once,
     *                  see SessionActivity.TrendChart#toDisplayVal, so a "round step" here
     *                  is a round centimetre or a round inch, never one mislabelled as the
     *                  other). Order does not matter — min and max are sorted first.
     * @param max       the range's other end
     * @param maxLines  the most gridlines the plot carries; a caller with the run chart's
     *                  own shape can pass {@link #AXIS_MAX_LINES}
     * @return the gridlines, ascending. A single value (min and max equal, or either
     *         non-finite) is one line rather than a division by a zero span — the same
     *         "still get a usable number" idea {@link #axisTicks(double, double)} applies
     *         to a non-positive top.
     */
    /**
     * HOW MANY DECIMALS A ROUND AXIS NEEDS: exactly as many as its step has, so a step of 10
     * prints "190", a step of 0.5 prints "11.5" and a step of 0.25 prints "11.25". A label
     * with more decimals than its step ("190.0") says a precision the axis doesn't have, and
     * one with fewer would print two gridlines as the same number. At most 3.
     *
     * @param ticks the gridlines from {@link #rangeTicks}, in the unit they are printed in
     */
    public static int tickDecimals(double[] ticks) {
        if (ticks == null || ticks.length < 2) return 1;
        double step = Math.abs(ticks[1] - ticks[0]);
        for (int d = 0; d < 3; d++) {
            double scaled = step * Math.pow(10, d);
            if (Math.abs(scaled - Math.rint(scaled)) < 1e-6 * Math.max(1, scaled)) return d;
        }
        return 3;
    }

    public static double[] rangeTicks(double min, double max, int maxLines) {
        if (max < min) { double t = min; min = max; max = t; }
        if (!Double.isFinite(min) || !Double.isFinite(max) || !(max > min))
            return new double[]{ Double.isFinite(min) ? min : 0 };
        double span = max - min;
        double decade = Math.pow(10, Math.floor(Math.log10(span)) - 2);
        for (int k = 0; k < 8; k++, decade *= 10) {
            for (int i = 0; i < NICE_STEPS.length; i++) {
                double step = NICE_STEPS[i] * decade;
                double niceMin = Math.floor(min / step + 1e-9) * step;
                double niceMax = Math.ceil(max / step - 1e-9) * step;
                int n = (int) Math.rint((niceMax - niceMin) / step) + 1;
                if (n >= 2 && n <= maxLines) {
                    double[] t = new double[n];
                    for (int j = 0; j < n; j++) t[j] = Math.rint((niceMin + j * step) * 1e6) / 1e6;
                    return t;
                }
            }
        }
        // Unreached in practice (the loop above always finds a step within 8 decades of
        // any finite span), kept as a last-resort pair rather than an exception.
        return new double[]{ Math.rint(min * 1e6) / 1e6, Math.rint(max * 1e6) / 1e6 };
    }

    /** A gridline's label: the round number, with the vacuum sign the mercury units print
     *  (never on zero), and a decimal only when the step has one (2.5, 7.5). */
    public static String tickLabel(double shown, boolean negative) {
        double mag = Math.abs(shown);
        if (mag < 1e-9) return "0";
        String num = Math.abs(mag - Math.rint(mag)) < 1e-6
            ? String.valueOf((long) Math.rint(mag))
            : String.format(java.util.Locale.US, "%.1f", mag);
        return negative ? "−" + num : num;
    }

    /* -------------------------------------------------- what an empty plot says */

    /** The caption an empty plot carries when nothing has arrived yet. */
    public static final String CAP_WAITING = "Waiting for telemetry";
    /** …and when frames ARE arriving and every one of them is the device saying it is not
     *  measuring. On an open system this pump reports 0.0 for real, and a chart that keeps
     *  saying "waiting" while the link is perfectly healthy blames itself for the
     *  hardware's honest answer. */
    public static final String CAP_NO_READING =
        "no pressure reported — the pump reads 0.0 with the system open";

    /** True when the window holds samples and EVERY one of them is a no-reading. False for
     *  an empty window: nothing having arrived is a different fact from everything that
     *  arrived being 0.0, and the two get different captions. */
    public static boolean allNoReading(double[] kpa, boolean[] noReading) {
        if (kpa == null || kpa.length == 0) return false;
        for (int i = 0; i < kpa.length; i++) {
            boolean nr = noReading != null && i < noReading.length && noReading[i];
            if (!isGap(kpa[i], nr)) return false;
        }
        return true;
    }

    /** The caption to draw across the plot, or null when there is a trace to draw and the
     *  plot needs no words at all. */
    public static String emptyCaption(double[] kpa, boolean[] noReading) {
        if (kpa == null || kpa.length == 0) return CAP_WAITING;
        if (allNoReading(kpa, noReading)) return CAP_NO_READING;
        return null;
    }

    /**
     * Where a pressure sits in the plot, as a fraction of the plot's height measured from
     * the BOTTOM — so 0 is the baseline and 1 is full scale. Clamped into [0, 1]; a
     * non-positive full scale is 0 rather than a division by zero.
     */
    public static double yFraction(double kpa, double fullScaleKpa) {
        if (fullScaleKpa <= 0) return 0.0;
        double f = kpa / fullScaleKpa;
        if (f < 0) return 0.0;
        return f > 1.0 ? 1.0 : f;
    }

    /**
     * {@link #yFraction}'s inverse: the kPa value that sits at a given fraction of the
     * plot's height measured from the BOTTOM (0..1, the same convention `yFraction`
     * returns). Exists so a caller that only knows a Y-POSITION on the chart — a fixed
     * grid hairline, say — can label it with the identical value-to-pixel scale the
     * trace and the commanded band are drawn against, through one shared, provable
     * function, rather than re-deriving `frac * fullScaleKpa` inline at each call site
     * and trusting it to stay in step with `yFraction`'s own formula by hand.
     *
     * Only meaningful for `frac` inside [0, 1] — `yFraction`'s own output range — since
     * outside it there is no `kpa` that `yFraction` would have produced that fraction
     * for. A non-positive full scale is 0, matching `yFraction`'s own floor.
     */
    public static double kpaAtFraction(double frac, double fullScaleKpa) {
        if (fullScaleKpa <= 0) return 0.0;
        return frac * fullScaleKpa;
    }

    /** The x of the i-th of n samples across a plot `width` wide, newest at the right.
     *  A single sample sits at the right-hand edge, where the newest always is. */
    public static float xFor(int i, int n, float width) {
        if (n <= 1) return width;
        return width * i / (float) (n - 1);
    }

    /* ------------------------------------------------------------- the breathing */

    /** The floor both swells fall back to — what the screen looks like with no reading at
     *  all. Not zero: a trace with no glow reads as switched off, and the run is not off. */
    public static final float GLOW_FLOOR = 0.30f;
    /** The halo behind the screen is subtler than the trace's own glow, because it is
     *  ambient rather than a thing being read. */
    public static final float HALO_FLOOR = 0.10f;
    public static final float HALO_CEIL  = 0.55f;

    /**
     * The trace's glow alpha (0..1) for a pressure at `frac` of full scale.
     *
     * `hasReading` false forces the FLOOR rather than holding the last swell: a link that
     * has gone quiet must make the screen go still, or the breathing becomes a claim about
     * a pressure nobody is measuring.
     */
    public static float glowAlpha(double frac, boolean hasReading) {
        if (!hasReading) return GLOW_FLOOR;
        double f = frac < 0 ? 0 : (frac > 1 ? 1 : frac);
        return (float) (GLOW_FLOOR + (1.0 - GLOW_FLOOR) * f);
    }

    /** The amber halo behind the whole screen — the same swell, quieter. */
    public static float haloAlpha(double frac, boolean hasReading) {
        if (!hasReading) return HALO_FLOOR;
        double f = frac < 0 ? 0 : (frac > 1 ? 1 : frac);
        return (float) (HALO_FLOOR + (HALO_CEIL - HALO_FLOOR) * f);
    }

    /* ------------------------------------------------------------- the buffer */

    /**
     * THE ROLLING WINDOW ITSELF, moved out of the Activity so it can be asserted.
     *
     * Three parallel arrays, one index: the measured pressure, its no-reading flag, and —
     * this is the merge — the pressure that was COMMANDED at the instant that sample
     * arrived. Recording the commanded value alongside the sample is the only honest way
     * to draw the two together: the alternative is reconstructing, at draw time, what the
     * setpoint "must have been" 25 seconds ago from a plan that a skip, a +30 s or a live
     * adjustment has since rewritten. The chart replays what WAS commanded, not what the
     * current plan implies was commanded.
     *
     * Fixed-size and index-wrapped rather than a List that is added to and shifted: this is
     * written four times a second for twelve minutes and read every 40 ms, and a remove(0)
     * on an ArrayList each time copies the whole buffer per sample.
     */
    public static final class Ring {
        private final double[] kpa = new double[WINDOW];
        private final boolean[] gap = new boolean[WINDOW];
        private final double[] cmd = new double[WINDOW];
        /** …and the LOWER (drop) setpoint commanded beside it. The pump cycles between the
         *  two autonomously, on a firmware clock the app cannot see, so the chart draws the
         *  BAND rather than a reconstructed cycle: both bounds were genuinely commanded and
         *  both can be stood behind; only the timing between them cannot. */
        private final double[] cmdLo = new double[WINDOW];
        /** …and whether a REST was playing when it arrived (0.10 run screen): the chart
         *  draws those samples in the rest colour over a hatched band. */
        private final boolean[] rst = new boolean[WINDOW];
        /** …and the colour of the step playing then (0.10 final: the line takes the step's
         *  colour - work, warm-up, rest, paused); 0 when none was given. */
        private final int[] col = new int[WINDOW];
        private int count = 0;   // how many slots are filled (caps at WINDOW)
        private int head  = 0;   // where the NEXT sample goes

        /** How many samples are in the window, 0..WINDOW. */
        public int count() { return count; }

        /** Appends one sample and the UPPER setpoint in force when it arrived, with no
         *  lower bound recorded — the band collapses to the single line this used to draw. */
        public void push(double kpaValue, boolean noReading, double commandedKpa) {
            push(kpaValue, noReading, commandedKpa, 0.0);
        }

        /** Appends one sample and BOTH setpoints in force when it arrived. */
        public void push(double kpaValue, boolean noReading, double commandedKpa,
                          double commandedLowerKpa) {
            push(kpaValue, noReading, commandedKpa, commandedLowerKpa, false);
        }

        /** Appends one sample, both setpoints, and whether a rest was playing. */
        public void push(double kpaValue, boolean noReading, double commandedKpa,
                          double commandedLowerKpa, boolean resting) {
            push(kpaValue, noReading, commandedKpa, commandedLowerKpa, resting, 0);
        }

        /** Appends one sample, both setpoints, whether a rest was playing, and the colour of
         *  the step playing (0: none). */
        public void push(double kpaValue, boolean noReading, double commandedKpa,
                          double commandedLowerKpa, boolean resting, int colour) {
            col[head] = colour;
            rst[head] = resting;
            kpa[head] = kpaValue;
            gap[head] = noReading;
            cmd[head] = commandedKpa;
            // A lower bound at or above the upper one is not a band, and a negative one is
            // not a pressure: either is recorded as "no lower bound" rather than drawn.
            cmdLo[head] = (commandedLowerKpa > 0 && commandedLowerKpa < commandedKpa)
                          ? commandedLowerKpa : 0.0;
            head = (head + 1) % WINDOW;
            if (count < WINDOW) count++;
        }

        /** Empties it — at the start of a run, so a new routine never opens with the tail
         *  of the last one's trace already scrolling across it. */
        public void clear() { count = 0; head = 0; }

        private int slot(int i) { return (head - count + i + 2 * WINDOW) % WINDOW; }

        /** The i-th OLDEST sample's measured pressure. */
        public double kpaAt(int i) { return kpa[slot(i)]; }
        /** …its no-reading flag. */
        public boolean gapAt(int i) { return gap[slot(i)]; }
        /** …and the setpoint commanded when it arrived. Same index, same instant: the two
         *  lines on the chart are aligned by construction rather than by a lookup. */
        public double commandedAt(int i) { return cmd[slot(i)]; }
        /** …and the LOWER setpoint commanded at that same instant, or 0 when there was no
         *  band (a flat hold, or nothing commanded at all). */
        public double commandedLowerAt(int i) { return cmdLo[slot(i)]; }
        /** …and whether a rest was playing then. */
        public boolean restAt(int i) { return rst[slot(i)]; }
        /** …and the step's colour then, 0 when none was given. */
        public int colourAt(int i) { return col[slot(i)]; }
    }

    /**
     * THE LONG VIEW OF THE RUN, for the chart's rest view (0.10 run screen): during a rest the
     * chart widens to show the whole rest and the pull after it, which the half-minute Ring
     * cannot hold. One sample a second at most (the newest in each second wins), stamped with
     * its arrival time, for the last half hour.
     */
    public static final class History {
        public static final int CAP = 1800;
        private final long[] t = new long[CAP];
        private final double[] kpa = new double[CAP];
        private final boolean[] gap = new boolean[CAP];
        private final boolean[] rst = new boolean[CAP];
        private final int[] col = new int[CAP];
        private int count = 0, head = 0;

        public void push(long atMs, double kpaValue, boolean noReading, boolean resting) {
            push(atMs, kpaValue, noReading, resting, 0);
        }

        /** …with the colour of the step playing (0: none) - the newest in a second wins. */
        public void push(long atMs, double kpaValue, boolean noReading, boolean resting,
                         int colour) {
            if (count > 0) {
                int last = (head - 1 + CAP) % CAP;
                if (atMs < t[last]) return;                  // never out of order
                if (atMs / 1000L == t[last] / 1000L) {       // the same second: newest wins
                    kpa[last] = kpaValue; gap[last] = noReading;
                    rst[last] = rst[last] || resting;
                    col[last] = colour;
                    t[last] = atMs;
                    return;
                }
            }
            col[head] = colour;
            t[head] = atMs; kpa[head] = kpaValue; gap[head] = noReading; rst[head] = resting;
            head = (head + 1) % CAP;
            if (count < CAP) count++;
        }

        public void clear() { count = 0; head = 0; }
        public int count() { return count; }
        private int slot(int i) { return (head - count + i + 2 * CAP) % CAP; }
        public long timeAt(int i) { return t[slot(i)]; }
        public double kpaAt(int i) { return kpa[slot(i)]; }
        public boolean gapAt(int i) { return gap[slot(i)]; }
        public boolean restAt(int i) { return rst[slot(i)]; }
        public int colourAt(int i) { return col[slot(i)]; }
    }

    /**
     * THE CHART'S WINDOW IN A REST: from a little before the rest began to a little after the
     * next pull is due, so the whole rest and the ramp back to pressure are in view. Returns
     * {from, to} in epoch ms. `leadMs` of the time before the rest, `tailMs` after its end.
     */
    public static long[] restWindow(long restStartMs, long restEndMs, long now,
                                    long leadMs, long tailMs) {
        long from = Math.min(restStartMs, now) - Math.max(0L, leadMs);
        long to = Math.max(restEndMs, now) + Math.max(0L, tailMs);
        if (to <= from) to = from + 1L;
        return new long[] { from, to };
    }

    /**
     * The alpha the COMMANDED step line is drawn at. Faint on purpose: it is the reference,
     * not the reading. The measured trace keeps its full stroke and its glow, so the eye
     * lands on what the cuff is actually doing and reads the commanded line as the thing
     * it is being judged against.
     */
    public static final int COMMANDED_ALPHA = 175;

    /** The LOWER bound of the band is drawn fainter than the upper one: the upper setpoint
     *  is the target the run is judged against, the lower is the floor the pump bleeds back
     *  to between pulls. Both are dashed, so neither can be mistaken for the measurement. */
    public static final int COMMANDED_LOW_ALPHA = 120;

    /** The chart's key: the solid stroke is what was MEASURED, the dashed ones what was
     *  COMMANDED - both dashes, the pull and the drop, so neither reads as a second
     *  measurement. One short line in sentence case beside the phase (polish item 10); it
     *  used to say "commanded band" in capitals on a line of its own. */
    public static final String KEY = "— measured · ┄ commanded";

    /* ------------------------------------------------- the routine timeline strip */

    /**
     * HOW FAR THROUGH THE WHOLE ROUTINE the run is, as a fraction of 0..1 — the position
     * of the marker on the timeline strip that now shares the live chart's card.
     *
     * The chart says what the pressure is doing over the last half minute; it said nothing
     * about where in the routine that half minute sits, so a reader could see a healthy
     * pull and still not know whether it was the second minute or the last. The strip
     * answers that in the same card, against the same stage colours the Today rail and the
     * run screen's own stage row already use, so the three are one picture.
     *
     * Derived from the SAME stage index and within-stage fraction the stage rail is painted
     * from (defect #25's rule: never a second computation of a number already derived).
     * `withinFrac` is clamped, a stage index off the end pins to the ends, and a routine
     * with no measurable duration is 0 rather than a division by zero — a marker at an
     * invented position is worse than a marker at the start.
     */
    public static float routineFraction(long[] stageDurMs, int stageIdx, double withinFrac) {
        if (stageDurMs == null || stageDurMs.length == 0) return 0f;
        long total = 0;
        for (int i = 0; i < stageDurMs.length; i++) total += Math.max(0L, stageDurMs[i]);
        if (total <= 0) return 0f;
        if (stageIdx < 0) return 0f;
        if (stageIdx >= stageDurMs.length) return 1f;
        long before = 0;
        for (int i = 0; i < stageIdx; i++) before += Math.max(0L, stageDurMs[i]);
        double f = withinFrac < 0 ? 0 : (withinFrac > 1 ? 1 : withinFrac);
        double at = (before + Math.max(0L, stageDurMs[stageIdx]) * f) / (double) total;
        if (at < 0) return 0f;
        return at > 1 ? 1f : (float) at;
    }

    /** The strip's own label. Stated here beside the fraction it labels, so the card's two
     *  halves are named in one place. */
    public static final String ROUTINE_LABEL = "ROUTINE";

    /* --------------------------------------------------------- the frame budget */

    /**
     * The trace's redraw interval in ms. Deliberately SLOWER than a display refresh: the
     * data arrives at about 4 Hz, so a 60 fps loop would spend fifteen frames redrawing an
     * unchanged buffer and would keep a screen-on device's GPU busy for the length of a
     * routine. 40 ms is fast enough for the leading dot and the swell to read as continuous
     * and cheap enough to run for twelve minutes.
     */
    public static final long FRAME_MS = 40L;
}
