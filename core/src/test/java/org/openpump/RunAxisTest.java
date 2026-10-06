package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * POLISH ITEM 9 - THE RUN CHART'S SCALE IN ROUND NUMBERS.
 *
 * The axis read −5.5, −3.6, −1.8: thirds of the current peak, not numbers anyone counts in.
 * It now counts in steps of 1, 2, 2.5 or 5 (times a power of ten) in the unit the person
 * chose, from 0 at the bottom, with three to five gridlines.
 */
class RunAxisTest {

    private String unitBefore;

    @BeforeEach void keep() { unitBefore = Model.Fmt.unit; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    private static double[] ticks(String unit, double fullScaleKpa) {
        Model.Fmt.unit = unit;
        return Trace.axisTicks(fullScaleKpa, Model.Fmt.perKpa());
    }

    /** The plot's own full scale for a routine whose pull is `peakKpa`. */
    private static double full(double peakKpa) {
        return Trace.fullScaleKpa(new double[0], new boolean[0], peakKpa);
    }

    @Test
    void theMockupsRunCountsInTwosOfInchesOfMercury() {
        // A −6.5 inHg pull (22 kPa): 0, −2, −4, −6 - the approved mock.
        assertArrayEquals(new double[]{0, 2, 4, 6}, ticks(Model.Fmt.U_INHG, full(22)), 1e-9);
    }

    @Test
    void theSameRunInKilopascalsCountsInFives() {
        assertArrayEquals(new double[]{0, 5, 10, 15, 20}, ticks(Model.Fmt.U_KPA, full(22)), 1e-9);
    }

    @Test
    void aSmallRangeCountsInSmallSteps() {
        double quiet = full(0);           // nothing commanded: the 10 kPa floor
        assertArrayEquals(new double[]{0, 1, 2, 3}, ticks(Model.Fmt.U_INHG, quiet), 1e-9);
        assertArrayEquals(new double[]{0, 2.5, 5, 7.5, 10}, ticks(Model.Fmt.U_KPA, quiet), 1e-9);
    }

    @Test
    void aLargeRangeCountsInLargeSteps() {
        double atCeiling = full(40);      // a pull at a −11.8 inHg ceiling
        assertArrayEquals(new double[]{0, 5, 10}, ticks(Model.Fmt.U_INHG, atCeiling), 1e-9);
        assertArrayEquals(new double[]{0, 10, 20, 30, 40}, ticks(Model.Fmt.U_KPA, atCeiling), 1e-9);
    }

    @Test
    void aRangeThatIsExactlyAStepKeepsItsTopLine() {
        // A full scale of exactly −6 inHg: the −6 line is drawn, not lost to rounding.
        assertArrayEquals(new double[]{0, 2, 4, 6},
            ticks(Model.Fmt.U_INHG, 6 * Model.Fmt.KPA_PER_INHG), 1e-9);
        assertArrayEquals(new double[]{0, 5, 10, 15, 20}, ticks(Model.Fmt.U_KPA, 20), 1e-9);
    }

    @Test
    void everyPlotCarriesThreeToFiveLinesAndNoneAboveTheTop() {
        String[] units = { Model.Fmt.U_INHG, Model.Fmt.U_KPA, Model.Fmt.U_CMHG };
        for (String u : units) {
            for (double peak = 0; peak <= 60; peak += 0.7) {
                double fs = full(peak);
                double[] t = ticks(u, fs);
                assertTrue(t.length >= 3 && t.length <= 5, u + " at " + peak + ": " + t.length);
                assertEquals(0.0, t[0], 1e-12, "0 at the bottom");
                double step = t[1] - t[0];
                for (int i = 1; i < t.length; i++)
                    assertEquals(step, t[i] - t[i - 1], 1e-9, "evenly spaced");
                double top = fs * Model.Fmt.perKpa();
                assertTrue(t[t.length - 1] <= top + 1e-9, "no line above the plot");
                double mant = step / Math.pow(10, Math.floor(Math.log10(step)));
                assertTrue(Math.abs(mant - 1) < 1e-9 || Math.abs(mant - 2) < 1e-9
                    || Math.abs(mant - 2.5) < 1e-9 || Math.abs(mant - 5) < 1e-9,
                    "a round step: " + step);
            }
        }
    }

    @Test
    void theLabelsAreRoundAndCarryTheVacuumSign() {
        assertEquals("−6", Trace.tickLabel(6, true), "inHg reads negative");
        assertEquals("0", Trace.tickLabel(0, true), "zero has no sign");
        assertEquals("7.5", Trace.tickLabel(7.5, false), "kPa does not");
        assertEquals("20", Trace.tickLabel(20, false));
    }
}
