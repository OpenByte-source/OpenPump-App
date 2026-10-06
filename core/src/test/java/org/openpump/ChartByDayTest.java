package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R11-1: the Length · girth chart's x-axis is by day. A before and an after five minutes
 * apart sat at opposite ends of the plot, reading as days apart; they now share their day's
 * x, a hair apart, and the axis spreads only across different days.
 */
class ChartByDayTest {

    private static long at(int y, int m0, int d, int h, int min) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(y, m0, d, h, min, 0);
        return c.getTimeInMillis();
    }

    @Test
    void aBeforeAndAfterOnOneDaySitTogetherInTheMiddle() {
        long before = at(2026, 9, 3, 19, 0), after = at(2026, 9, 3, 19, 5);
        float xb = ChartFrame.dayXAt(before, before, after, 0f, 300f, -2f);
        float xa = ChartFrame.dayXAt(after, before, after, 0f, 300f, 2f);
        assertEquals(148f, xb, 0.001f, "one day: the middle, the before a hair left");
        assertEquals(152f, xa, 0.001f, "the after a hair right");
    }

    @Test
    void theAxisSpreadsOnlyAcrossDifferentDays() {
        long d1a = at(2026, 9, 1, 8, 0), d1b = at(2026, 9, 1, 21, 30);
        long d3 = at(2026, 9, 3, 7, 0);
        assertEquals(0f, ChartFrame.dayXAt(d1a, d1a, d3, 0f, 200f, 0f), 0.001f);
        assertEquals(0f, ChartFrame.dayXAt(d1b, d1a, d3, 0f, 200f, 0f), 0.001f,
            "a later reading the same day keeps that day's x");
        assertEquals(200f, ChartFrame.dayXAt(d3, d1a, d3, 0f, 200f, 0f), 0.001f);
        long d2 = at(2026, 9, 2, 23, 59);
        assertEquals(100f, ChartFrame.dayXAt(d2, d1a, d3, 0f, 200f, 0f), 0.5f,
            "the middle day sits in the middle whatever its hour");
    }

    @Test
    void twoChartsGivenTheSameSpanStillLineUp() {
        long a = at(2026, 8, 20, 9, 0), b = at(2026, 9, 2, 18, 0), r = at(2026, 8, 27, 12, 0);
        assertTrue(ChartFrame.dayXAt(r, a, b, 40f, 260f, 0f)
            == ChartFrame.dayXAt(r, a, b, 40f, 260f, 0f));
        assertEquals(ChartFrame.dayAnchor(at(2026, 8, 27, 0, 1)), ChartFrame.dayAnchor(r));
    }
}
