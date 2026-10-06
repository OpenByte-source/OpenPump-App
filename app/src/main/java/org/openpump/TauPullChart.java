package org.openpump;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * M4 - THE TISSUE RESPONSE TEST'S TWO PULLS, drawn so they can be read.
 *
 * The chart it replaces overlaid both pulls in amber, told apart only by thickness, with no
 * axis, no labels and nothing for a screen reader - and on a typical pair the two lines sat
 * on top of each other and read as one. Now: the BEFORE pull dashed in a light grey, the
 * AFTER pull solid in amber (it is pump pressure), a seconds axis from the moment each pull
 * was commanded (the origin Tau.compute measures from, and the one keepAssessTrace stores),
 * the top the pulls reached as a labelled rule, and a dot on each line at its fill time, so
 * the two numbers in the card's title are visibly the two dots. The legend is drawn by the
 * caller under it, in words.
 *
 * The x axis runs to where the pulls stopped rising, not to the end of the window - the
 * held top is flat and says nothing - and the description says so (Summary#tauChartNote).
 *
 * WHY THE BEFORE PULL IS NO LONGER AMBER. The old chart kept both pulls amber on the grounds
 * that the pump commanded both and a second hue would make the earlier one "a second kind of
 * thing". In practice two amber lines of different weight were one line to the eye. The
 * after pull keeps amber; the before pull is the reference it is compared with, drawn as a
 * reference usually is (dashed, neutral), and the legend names both - the hue now separates
 * the two things being compared, not two kinds of measurement.
 */
final class TauPullChart extends View {
    private final Store.TauTrace tr;
    private final Model.Sess s;

    /** The before pull's ink: a light grey, the drawing's #C9D0DA - TEXT at 80 %. */
    static final int BEFORE_INK = (Look.TEXT & 0x00FFFFFF) | 0xCC000000;

    /** `note` is Summary#tauChartNote's sentence for this session - the caller's, so the
     *  site WiringCheck invariant 55 watches is the one that states it. */
    TauPullChart(Activity a, Store.TauTrace t, Model.Sess s, String note) {
        super(a);
        this.tr = t; this.s = s;
        setWillNotDraw(false);
        setContentDescription(TauSay.chartSaid(s) + (note == null ? "" : " " + note));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || tr == null) return;

        int topCk = 1;
        topCk = Math.max(topCk, peakOf(tr.beforeCkpa));
        topCk = Math.max(topCk, peakOf(tr.afterCkpa));
        int riseMs = 1;
        riseMs = Math.max(riseMs, riseOf(tr.beforeMs, tr.beforeCkpa, topCk));
        riseMs = Math.max(riseMs, riseOf(tr.afterMs, tr.afterCkpa, topCk));
        // The axis ends on a whole tick: 5 s steps, 10 s past 40 s.
        int stepS = riseMs > 40000 ? 10 : 5;
        int spanS = Math.max(stepS, (int) Math.ceil(riseMs / 1000.0 / stepS) * stepS);
        int spanMs = spanS * 1000;

        float left = dp(2), right = w - dp(4);
        float top = dp(20), bottom = h - dp(22);

        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(Look.DIM);
        txt.setTextSize(dp(Look.SP_AXIS) * getResources().getConfiguration().fontScale);

        // The top the pulls reached, as a labelled rule.
        Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
        rule.setColor(Look.LINE);
        rule.setStrokeWidth(Math.max(1f, dp(1)));
        c.drawLine(left, top, right, top, rule);
        String topLabel = Model.Fmt.p(topCk / 100.0);
        txt.setTextAlign(Paint.Align.RIGHT);
        c.drawText(topLabel, right, top - dp(6), txt);

        // The seconds axis.
        Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
        axis.setColor(Look.CTRL_EDGE);
        axis.setStrokeWidth(Math.max(1f, dp(1)));
        c.drawLine(left, bottom, right, bottom, axis);
        for (int t = 0; t <= spanS; t += stepS) {
            float x = left + (right - left) * (t / (float) spanS);
            c.drawLine(x, bottom, x, bottom + dp(4), axis);
            txt.setTextAlign(t == 0 ? Paint.Align.LEFT
                           : t == spanS ? Paint.Align.RIGHT : Paint.Align.CENTER);
            c.drawText(t == spanS ? t + " s" : String.valueOf(t), x, bottom + dp(17), txt);
        }

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);

        // AFTER first, solid amber; the dashed BEFORE over it, so where they coincide the
        // dashes still show there were two.
        p.setColor(Look.COMMANDED);
        p.setStrokeWidth(dp(2.4f));
        stroke(c, p, tr.afterMs, tr.afterCkpa, spanMs, topCk, left, right, top, bottom);
        p.setColor(BEFORE_INK);
        p.setStrokeWidth(dp(1.8f));
        p.setPathEffect(new DashPathEffect(new float[]{ dp(5), dp(4) }, 0));
        stroke(c, p, tr.beforeMs, tr.beforeCkpa, spanMs, topCk, left, right, top, bottom);
        p.setPathEffect(null);

        // The fill times, as dots where each line crosses its own two-thirds mark.
        if (s != null) {
            dot(c, s.tauAfterSec, s.tauAfterP0Kpa, s.tauAfterPeakKpa, true,
                spanMs, topCk, left, right, top, bottom);
            dot(c, s.tauBeforeSec, s.tauBeforeP0Kpa, s.tauBeforePeakKpa, false,
                spanMs, topCk, left, right, top, bottom);
        }
    }

    private void dot(Canvas c, Double tauSec, Double p0, Double peak, boolean after,
                     int spanMs, int topCk, float l, float r, float t, float b) {
        if (tauSec == null || p0 == null || peak == null) return;
        double atKpa = p0.doubleValue() + Tau.CROSSING_FRACTION * (peak.doubleValue() - p0.doubleValue());
        float x = l + (r - l) * (float) Math.min(1.0, tauSec.doubleValue() * 1000.0 / spanMs);
        float y = b - (b - t) * (float) Math.min(1.0, atKpa * 100.0 / topCk);
        Paint d = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (after) {
            d.setColor(Look.COMMANDED);
            c.drawCircle(x, y, dp(3.6f), d);
        } else {
            d.setColor(Look.GROUND);
            c.drawCircle(x, y, dp(3f), d);
            d.setStyle(Paint.Style.STROKE);
            d.setStrokeWidth(dp(1.6f));
            d.setColor(BEFORE_INK);
            c.drawCircle(x, y, dp(3f), d);
        }
    }

    private static int peakOf(int[] ck) {
        int m = 0;
        if (ck != null) for (int i = 0; i < ck.length; i++) if (ck[i] > m) m = ck[i];
        return m;
    }

    /** The instant the curve last got meaningfully deeper, plus a quarter for air. A pull
     *  that never rose returns 0 and lets the other end set the axis. */
    private static int riseOf(int[] ms, int[] ck, int topCk) {
        if (ms == null || ck == null || topCk <= 0) return 0;
        int want = (int) (topCk * 0.98), at = 0, i;
        for (i = 0; i < ck.length && i < ms.length; i++) if (ck[i] >= want) { at = ms[i]; break; }
        return at + at / 4;
    }

    private static void stroke(Canvas c, Paint p, int[] ms, int[] ck, int spanMs, int topCk,
                               float l, float r, float t, float b) {
        if (ms == null || ck == null || ms.length < 2) return;
        Path path = new Path();
        boolean started = false;
        for (int i = 0; i < ms.length && i < ck.length; i++) {
            if (ms[i] > spanMs) break;           // the held top is flat; nothing happens there
            float x = l + (r - l) * (ms[i] / (float) spanMs);
            float y = b - (b - t) * Math.min(1f, ck[i] / (float) topCk);
            if (!started) { path.moveTo(x, y); started = true; }
            else path.lineTo(x, y);
        }
        if (started) c.drawPath(path, p);
    }
}
