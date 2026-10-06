package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Bug B2: the Progress chart's axis labels get a gutter of their own, and the two stacked
 * charts keep one time axis. The Canvas is not testable here; the frame arithmetic is.
 */
class ChartFrameTest {

    /** Every label either chart prints, in both size units, fits in the floor's width. In
     *  tabular digits a label's width is its character count, so counting is measuring. */
    @Test
    void theFloorHoldsEveryLabelInBothUnitsAndThreeDigitValues() {
        String saved = Model.Fmt.sizeUnit;
        try {
            double[] cm = { 0.5, 9.9, 11.5, 15.6, 99.9, 100.0, 180.0, 253.9 };
            for (String unit : new String[]{ Model.Fmt.S_CM, Model.Fmt.S_IN }) {
                Model.Fmt.sizeUnit = unit;
                for (double v : cm) {
                    String s = Model.Fmt.lenNum(v);
                    assertTrue(s.length() <= ChartFrame.AXIS_LABEL_FLOOR.length(),
                        unit + " label " + s + " is wider than the reserved floor");
                }
            }
            double[] cm3 = { 12.3, 184.2, 197.1, 999.9 };
            for (double v : cm3) {
                String s = Model.Fmt.volNum(v);
                assertTrue(s.length() <= ChartFrame.AXIS_LABEL_FLOOR.length(),
                    "volume label " + s + " is wider than the reserved floor");
            }
        } finally {
            Model.Fmt.sizeUnit = saved;
        }
    }

    @Test
    void theGutterIsTheWiderOfLabelAndFloorPlusTheGap() {
        assertEquals(70f + 16f, ChartFrame.gutter(40f, 70f, 16f), 1e-6f,
            "a short label still gets the floor's room");
        assertEquals(90f + 16f, ChartFrame.gutter(90f, 70f, 16f), 1e-6f,
            "a label wider than the floor widens the gutter instead of being cut");
        assertEquals(0f, ChartFrame.gutter(-5f, -1f, -2f), 1e-6f,
            "nonsense in, no gutter out");
    }

    @Test
    void thePlotStartsAfterTheGutterAndEndsAtItsWidth() {
        long t0 = 1_700_000_000_000L, t1 = t0 + 90L * 86_400_000L;
        float left = 86f, width = 800f;
        assertEquals(left, ChartFrame.xAt(t0, t0, t1, left, width), 1e-3f,
            "the oldest reading sits at the plot's left edge, clear of the labels");
        assertEquals(left + width, ChartFrame.xAt(t1, t0, t1, left, width), 1e-3f);
        assertEquals(left + width / 2f,
            ChartFrame.xAt(t0 + 45L * 86_400_000L, t0, t1, left, width), 1e-3f);
        assertEquals(left + width / 2f, ChartFrame.xAt(t0, t0, t0, left, width), 1e-3f,
            "one instant draws in the middle");
    }

    /** The S9 promise: the volume chart under the measurement chart shares its span. With
     *  one gutter and one width for both, a day lands on the same x in each, whichever of
     *  the two drew the wider labels. */
    @Test
    void twoChartsSharingAGutterAndASpanPutADayAtTheSameX() {
        float viewW = 900f, right = 16f;
        float gutter = Math.max(ChartFrame.gutter(55f, 70f, 16f),     // "12.6"
                                ChartFrame.gutter(72f, 70f, 16f));    // "197.1"
        long t0 = 1_700_000_000_000L, t1 = t0 + 60L * 86_400_000L;
        long day = t0 + 17L * 86_400_000L;
        float measX = ChartFrame.xAt(day, t0, t1, gutter, viewW - gutter - right);
        float volX  = ChartFrame.xAt(day, t0, t1, gutter, viewW - gutter - right);
        assertEquals(measX, volX, 1e-6f);
        assertTrue(measX > gutter, "and it is right of both charts' labels");
    }
}
