package org.openpump;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.List;

/**
 * M4 - THE TISSUE RESPONSE TEST OVER TIME (the owner's item 12): one bar per session, the
 * change in fill time from before the routine to after it, oldest at the left.
 *
 * Normal variation (under {@link Tau#NOISE_PCT}) is a shaded band around zero, and a bar is
 * drawn in the plan blue only when its change is that size or more - so what setup alone can
 * produce reads as grey, and the eye goes to the sessions that might mean something. Where
 * the test's settings changed the bars are split by a dashed line and said so: a fill time
 * only compares with one measured at the same settings (Tau#sameStimulus). Which points and
 * where they break is TauSay#trend's decision; this only draws it.
 */
final class TauTrendChart extends View {
    private final List<TauSay.Point> pts;

    TauTrendChart(Activity a, List<TauSay.Point> pts) {
        super(a);
        this.pts = pts;
        setWillNotDraw(false);
        setContentDescription(TauSay.trendSaid(pts));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || pts == null || pts.isEmpty()) return;

        // The scale: at least twice normal variation, in whole steps of it, so a big change
        // is never clipped and a quiet history still shows its band.
        double maxAbs = 0;
        for (int i = 0; i < pts.size(); i++) maxAbs = Math.max(maxAbs, Math.abs(pts.get(i).pct));
        int step = Tau.NOISE_PCT;
        int scale = Math.max(2 * step, (int) Math.ceil(maxAbs / step) * step);

        float fs = getResources().getConfiguration().fontScale;
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(Look.DIM);
        txt.setTextSize(dp(Look.SP_AXIS) * fs);

        float left = dp(38), right = w - dp(2);
        float top = dp(18), bottom = h - dp(22);
        float mid = (top + bottom) / 2f;
        float half = (bottom - top) / 2f;

        // Normal variation, shaded.
        Paint band = new Paint();
        band.setColor((Look.DIM & 0x00FFFFFF) | 0x1A000000);
        float bandH = half * Tau.NOISE_PCT / (float) scale;
        c.drawRect(left, mid - bandH, right, mid + bandH, band);

        // The three rules: +scale, 0, -scale.
        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setStrokeWidth(Math.max(1f, dp(1)));
        txt.setTextAlign(Paint.Align.RIGHT);
        int[] at = { scale, 0, -scale };
        for (int i = 0; i < at.length; i++) {
            float y = mid - half * at[i] / (float) scale;
            grid.setColor(at[i] == 0 ? Look.CTRL_EDGE : Look.LINE);
            c.drawLine(left, y, right, y, grid);
            c.drawText(at[i] == 0 ? "0" : TauSay.pct(at[i]), left - dp(6), y + dp(4), txt);
        }

        int n = pts.size();
        float slot = (right - left) / n;
        float bw = Math.max(dp(2), Math.min(slot * 0.56f, dp(14)));
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint brk = new Paint(Paint.ANTI_ALIAS_FLAG);
        brk.setColor(Look.DIM);
        brk.setStyle(Paint.Style.STROKE);
        brk.setStrokeWidth(Math.max(1f, dp(1)));
        brk.setPathEffect(new DashPathEffect(new float[]{ dp(3), dp(3) }, 0));
        // Where the last "test settings changed" label ended: two changes close together
        // get a line each, and the words once, rather than two labels printed over each other.
        float labelEnd = -1f;
        for (int i = 0; i < n; i++) {
            TauSay.Point p = pts.get(i);
            float cx = left + slot * i + slot / 2f;
            if (p.breakBefore) {
                float x = left + slot * i;
                c.drawLine(x, top - dp(2), x, bottom, brk);   // below the words, never through them
                String say = "test settings changed";
                float tw = txt.measureText(say);
                boolean roomRight = right - x > tw + dp(6);
                float from = roomRight ? x + dp(4) : x - dp(4) - tw;
                if (from > labelEnd + dp(6)) {
                    txt.setTextAlign(Paint.Align.LEFT);
                    c.drawText(say, from, top - dp(4), txt);
                    labelEnd = from + tw;
                }
            }
            float y = mid - half * (float) (Math.max(-scale, Math.min(scale, p.pct)) / scale);
            float t = Math.min(mid, y), b = Math.max(mid, y);
            if (b - t < dp(1.5f)) b = t + dp(1.5f);
            bar.setColor(TauSay.atOrPastNoise(p.pct) ? Look.PLAN_BLUE : Look.CTRL_EDGE);
            c.drawRoundRect(new RectF(cx - bw / 2f, t, cx + bw / 2f, b), dp(1.5f), dp(1.5f), bar);
        }

        txt.setTextAlign(Paint.Align.LEFT);
        c.drawText("older", left, h - dp(4), txt);
        txt.setTextAlign(Paint.Align.RIGHT);
        c.drawText("latest", right, h - dp(4), txt);
    }
}
