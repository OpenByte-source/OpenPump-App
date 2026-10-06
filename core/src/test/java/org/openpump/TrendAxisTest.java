package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * POLISH ITEM 14 — THE PROGRESS CHART'S SCALE IN ROUND NUMBERS.
 *
 * The measurement and volume mini charts used to print only their raw window's min and max
 * ("12.6" / "11.5" — bug B2's own example), never a round number and never a gridline. They
 * now use {@link Trace#rangeTicks}, item 9's zero-anchored run-chart axis generalised to a
 * range that need not start at 0: both ends round OUTWARD to the nearest step of 1, 2, 2.5
 * or 5 (times a power of ten), so the plot may extend a little past the data on either side
 * rather than stopping exactly where the readings happened to.
 */
class TrendAxisTest {

    @Test
    void aNarrowCmRangeCountsInHalves() {
        // The girth mock's own numbers: Std + MSEG span 11.5 to 12.6 cm.
        assertArrayEquals(new double[]{ 11.5, 12.0, 12.5, 13.0 },
            Trace.rangeTicks(11.5, 12.6, Trace.AXIS_MAX_LINES), 1e-9);
    }

    @Test
    void aWideCmRangeCountsInWholeNumbers() {
        // The length "all series" mock's span, roughly 12.8 to 17.9 cm.
        assertArrayEquals(new double[]{ 12.0, 14.0, 16.0, 18.0 },
            Trace.rangeTicks(12.8, 17.9, Trace.AXIS_MAX_LINES), 1e-9);
    }

    @Test
    void theSameRangesInInchesCountInTheirOwnRoundSteps() {
        // The girth range above, converted to inches (÷ 2.54) — a round step of inches,
        // not a round step of centimetres relabelled.
        assertArrayEquals(new double[]{ 4.4, 4.6, 4.8, 5.0 },
            Trace.rangeTicks(11.5 / 2.54, 12.6 / 2.54, Trace.AXIS_MAX_LINES), 1e-9);
        assertArrayEquals(new double[]{ 5.0, 6.0, 7.0, 8.0 },
            Trace.rangeTicks(12.8 / 2.54, 17.9 / 2.54, Trace.AXIS_MAX_LINES), 1e-9);
    }

    @Test
    void aRangeThatSitsExactlyOnStepsKeepsItsOwnEnds() {
        // 10 to 13 is already four whole-number steps — neither end should be pushed
        // outward to a fifth line it does not need.
        assertArrayEquals(new double[]{ 10, 11, 12, 13 },
            Trace.rangeTicks(10, 13, Trace.AXIS_MAX_LINES), 1e-9);
        // Same idea at a 0.5 step: both 11.5 and 13.0 are already on the grid.
        assertArrayEquals(new double[]{ 11.5, 12.0, 12.5, 13.0 },
            Trace.rangeTicks(11.5, 13.0, Trace.AXIS_MAX_LINES), 1e-9);
    }

    @Test
    void aSingleValueRangeIsOneLineNotADivisionByZero() {
        assertArrayEquals(new double[]{ 12.0 }, Trace.rangeTicks(12.0, 12.0, Trace.AXIS_MAX_LINES), 1e-9);
        // Order does not matter, and neither does which argument is the flat one.
        assertArrayEquals(new double[]{ 12.0 }, Trace.rangeTicks(12.0, 12.0, 3), 1e-9);
    }

    @Test
    void theVolumeMiniChartsSmallerCapStillCoversARoundRange() {
        // cm3, and the mini chart's own cap rather than the measurement chart's 5 — see
        // SessionActivity.TrendChart#setAxisMaxLines.
        double[] t = Trace.rangeTicks(184.2, 197.1, Look.VOL_CHART_AXIS_MAX_LINES);
        assertArrayEquals(new double[]{ 180, 190, 200 }, t, 1e-9);
    }

    @Test
    void everyRangeIsCoveredEvenlySpacedAndRoundAtSeveralCaps() {
        double[] spans = { 0.08, 0.3, 1.1, 2.0, 4.7, 9.9, 25.0, 63.0, 140.0, 310.0 };
        for (int cap = 2; cap <= 6; cap++) {
            for (double span : spans) {
                double lo = 3.7, hi = lo + span;   // an arbitrary non-zero, non-round base
                double[] t = Trace.rangeTicks(lo, hi, cap);
                assertTrue(t.length >= 2 && t.length <= cap,
                    "span " + span + " cap " + cap + ": " + t.length + " lines");
                assertTrue(t[0] <= lo + 1e-6, "covers the low end: " + t[0] + " vs " + lo);
                assertTrue(t[t.length - 1] >= hi - 1e-6, "covers the high end");
                double step = t[1] - t[0];
                for (int i = 1; i < t.length; i++)
                    assertEquals(step, t[i] - t[i - 1], 1e-6, "evenly spaced");
                // The tiny epsilon before flooring matters: log10(0.1) itself lands a hair
                // below -1 in double precision, so a bare floor would pick the wrong decade
                // for a step that IS exactly 0.1 and fail this check on a floating-point
                // artefact rather than a real one — the same epsilon rangeTicks's own
                // floor/ceil calls already carry, for the same reason.
                double mant = step / Math.pow(10, Math.floor(Math.log10(step) + 1e-9));
                assertTrue(Math.abs(mant - 1) < 1e-6 || Math.abs(mant - 2) < 1e-6
                    || Math.abs(mant - 2.5) < 1e-6 || Math.abs(mant - 5) < 1e-6,
                    "a round step: " + step);
            }
        }
    }

    @Test
    void aReversedRangeIsTheSameAsTheOrderedOne() {
        assertArrayEquals(Trace.rangeTicks(11.5, 12.6, 5), Trace.rangeTicks(12.6, 11.5, 5), 1e-9);
    }

    @Test
    void nonFiniteInputsStillReturnOneUsableLine() {
        assertArrayEquals(new double[]{ 0 }, Trace.rangeTicks(Double.NaN, Double.NaN, 5), 1e-9);
        assertArrayEquals(new double[]{ 5.0 }, Trace.rangeTicks(5.0, Double.POSITIVE_INFINITY, 5), 1e-9);
    }

    @Test
    void labelsCarryTheStepsDecimalsAndNoMore() {
        assertEquals(0, Trace.tickDecimals(new double[]{ 180, 190, 200 }));
        assertEquals(0, Trace.tickDecimals(new double[]{ 11, 12, 13 }));
        assertEquals(1, Trace.tickDecimals(new double[]{ 15.3, 15.4, 15.5, 15.6 }));
        assertEquals(1, Trace.tickDecimals(new double[]{ 11.0, 11.5, 12.0 }));
        assertEquals(2, Trace.tickDecimals(new double[]{ 6.00, 6.25, 6.50 }));
        assertEquals(1, Trace.tickDecimals(new double[]{ 2.5, 5.0, 7.5 }));
        assertEquals(1, Trace.tickDecimals(new double[]{ 42 }));   // one line: the old one decimal
    }
}
