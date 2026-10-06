package org.openpump;

/**
 * WHERE A TREND CHART'S PLOT SITS INSIDE ITS VIEW — the left gutter its y-axis labels own,
 * and the time-to-x mapping inside what is left.
 *
 * WHY IT EXISTS (bug B2). The Progress measurement chart used to draw its axis extremes
 * ("12.6", "11.5") at the plot's own left edge, four pixels in, so the plot started under
 * them: the oldest point of a series sat exactly on the low label and its line ran straight
 * through it. A label that shares its pixels with the data is not a label. The plot now
 * starts to the RIGHT of a gutter as wide as the widest label plus a gap.
 *
 * THE GUTTER HAS A FLOOR, not only the labels it happens to draw today. Two charts stack in
 * the Progress card — the measurement chart and the S9 volume mini chart under it — and they
 * share a time axis: a length spike and a volume spike on the same day have to sit at the
 * same x. That holds only while both plots start at the same x, so the screen gives both the
 * wider of their two gutters, and the floor keeps that width from jumping as the series or
 * the unit changes. {@link #AXIS_LABEL_FLOOR} is the widest label either chart draws in
 * ordinary use: three whole digits and a decimal ("197.1" cm³), which in tabular digits is
 * exactly as wide as an inch reading's "6.14" or "10.25" and wider than a centimetre one's
 * "15.6". A label wider still (a fourth whole digit) widens the gutter rather than being cut.
 *
 * NO ANDROID IMPORT. Text is measured by the caller (a Paint); what lives here is the
 * arithmetic that decides the frame, so it can be checked without a device.
 */
public final class ChartFrame {
    private ChartFrame() { }

    /** The widest y-axis label the gutter always has room for — see the class doc. */
    public static final String AXIS_LABEL_FLOOR = "000.0";

    /** The gap between a label's right edge and the plot, in dp. */
    public static final int LABEL_GAP_DP = 6;

    /** The plot's right inset, in dp: room for the newest reading's dot (4.5 px radius plus
     *  its ring) and the right-aligned goal-line value, so neither is cut at the edge. */
    public static final int PLOT_RIGHT_DP = 6;

    /**
     * The left gutter, in px: the wider of the widest label drawn and the floor label, plus
     * the gap. Negative inputs count as nothing.
     */
    public static float gutter(float widestLabelPx, float floorLabelPx, float gapPx) {
        float label = Math.max(Math.max(widestLabelPx, floorLabelPx), 0f);
        return label + Math.max(gapPx, 0f);
    }

    /**
     * The x of `ts` in a plot that starts at `left` and is `width` wide, spanning
     * [tMin, tMax]. A span of zero (one instant) puts everything in the middle, as the
     * chart always has. Two charts given the same left, width and span place the same
     * instant at the same x — the alignment the stacked Progress charts rely on.
     */
    public static float xAt(long ts, long tMin, long tMax, float left, float width) {
        long span = tMax - tMin;
        if (span <= 0) return left + width / 2f;
        return left + (float) ((ts - tMin) / (double) span) * width;
    }

    /* R11-1 - THE TREND'S X-AXIS IS BY DAY. A before and an after five minutes apart were
     * drawn at the two ends of the plot (the span was those five minutes), reading as days
     * apart. Every reading now sits at its LOCAL day's x - the day's noon on the time axis -
     * so a day's readings share one x and the axis spreads only across different days. The
     * caller nudges a before a hair left and an after a hair right, and joins them. */

    /** The point on the time axis a reading's day stands at: local noon of that day. */
    public static long dayAnchor(long ts) {
        return Deload.dayStartMs(ts) + 12L * 3600000L;
    }

    /** The x of the day `ts` falls on, in a plot spanning the days of [tMin, tMax], moved by
     *  `nudgePx` (negative for a before, positive for an after, 0 otherwise). One day puts
     *  everything in the middle, as {@link #xAt} does for one instant. */
    public static float dayXAt(long ts, long tMin, long tMax, float left, float width,
                               float nudgePx) {
        return xAt(dayAnchor(ts), dayAnchor(tMin), dayAnchor(tMax), left, width) + nudgePx;
    }
}
