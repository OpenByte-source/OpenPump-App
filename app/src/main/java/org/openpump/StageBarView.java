package org.openpump;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * THE STAGE BAR, drawn - ONE drawing for the Today card, the Library card and the run screen
 * (0.10 run-screen redesign). The numbers are StageBar's; this only paints them.
 *
 * STATIC (Today, Library): every stage in its own colour, sized by time, a rest the thin line
 * it has always been. LIVE (the run screen): each stage dim until the run reaches it, filled
 * as it plays, whole once passed; a work block one part per set (R11-2, StageBar#buildLive); a
 * rest its own segment in the rest colour; the part playing now outlined in white; a stage
 * skipped in Coming steps hatched, one changed there marked with a small dot.
 *
 * ONE SILENT GRAPHIC: the words under it (or the card's caption) say in words what this says
 * in colour, so a screen reader is not read a bar chart one segment at a time.
 */
final class StageBarView extends View {
    private List<StageBar.Segment> segs = new ArrayList<StageBar.Segment>();
    private int[] colours = new int[0];
    private boolean live;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path clip = new Path();

    StageBarView(Activity a) {
        super(a);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    /** @param segColours one colour per segment, in order */
    void set(List<StageBar.Segment> s, int[] segColours, boolean liveBar) {
        segs = s == null ? new ArrayList<StageBar.Segment>() : s;
        colours = segColours == null ? new int[0] : segColours;
        live = liveBar;
        invalidate();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private static int alpha(int c, float a) {
        return (Math.round(255 * a) << 24) | (c & 0x00FFFFFF);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int n = segs.size();
        int w = getWidth(), h = getHeight();
        if (n == 0 || w <= 0 || h <= 0) return;
        long[] weights = new long[n];
        for (int i = 0; i < n; i++) weights[i] = segs.get(i).weightMs;
        float[] shares = Insight.railShares(weights);
        float gap = dp(3);
        float avail = w - gap * (n - 1);
        if (avail <= 0) return;
        float rad = live ? dp(3) : dp(4);
        float x = 0;
        for (int i = 0; i < n; i++) {
            StageBar.Segment g = segs.get(i);
            float sw = Math.max(dp(2), avail * shares[i]);
            if (i == n - 1) sw = Math.max(dp(2), w - x);
            int col = i < colours.length ? colours[i] : Look.ACCENT;
            if (!live) {
                // THE STATIC BAR IS THE RAIL TODAY HAS ALWAYS DRAWN: a rest is a thin line
                // (it is an absence), everything else a full-height segment in its colour.
                float hh = g.kind == RunLook.REST ? Math.min(h, dp(2)) : h;
                float top = (h - hh) / 2f;
                r.set(x, top, x + sw, top + hh);
                p.setStyle(Paint.Style.FILL);
                p.setColor(col);
                c.drawRoundRect(r, rad, rad, p);
                x += sw + gap;
                continue;
            }
            r.set(x, 0, x + sw, h);
            c.save();
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            p.setStyle(Paint.Style.FILL);
            if (g.skipped) {
                // HATCHED: the step keeps its place in the routine and plainly will not run.
                p.setColor(Look.SURFACEHI);
                c.drawRect(r, p);
                p.setColor(Look.LINE | 0xFF000000);
                p.setStrokeWidth(dp(1.5f));
                p.setStyle(Paint.Style.STROKE);
                for (float hx = x - h; hx < x + sw; hx += dp(5))
                    c.drawLine(hx, h, hx + h, 0, p);
                p.setColor(alpha(Look.DIM, 0.45f));
                for (float hx = x - h + dp(2.5f); hx < x + sw; hx += dp(5))
                    c.drawLine(hx, h, hx + h, 0, p);
            } else {
                p.setColor(alpha(col, 0.22f));
                c.drawRect(r, p);
                float f = Math.max(0f, Math.min(1f, g.fill));
                if (f > 0f) {
                    p.setColor(col);
                    c.drawRect(x, 0, x + sw * f, h, p);
                }
                // A TICK PER SET, cut in the page's own colour so it reads as a gap.
                if (g.ticks.length > 0) {
                    p.setColor(Look.GROUND);
                    float tw = dp(1.5f);
                    for (int k = 0; k < g.ticks.length; k++) {
                        float tx = x + sw * g.ticks[k];
                        c.drawRect(tx - tw / 2f, 0, tx + tw / 2f, h, p);
                    }
                }
            }
            c.restore();
            if (g.changed && !g.skipped) {
                // CHANGED IN COMING STEPS: a small dot, in the text colour on a ground ring.
                float cx = x + sw - dp(5), cy = h / 2f;
                if (sw > dp(10)) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Look.GROUND);
                    c.drawCircle(cx, cy, dp(3.2f), p);
                    p.setColor(Look.TEXT);
                    c.drawCircle(cx, cy, dp(2f), p);
                }
            }
            if (g.current) {
                // THE STAGE PLAYING NOW: a thin white outline.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1.5f));
                p.setColor(Look.TEXT);
                float in = dp(0.75f);
                r.set(x + in, in, x + sw - in, h - in);
                c.drawRoundRect(r, rad, rad, p);
            }
            x += sw + gap;
        }
    }
}
