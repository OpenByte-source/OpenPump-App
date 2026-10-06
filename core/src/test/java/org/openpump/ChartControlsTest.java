package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Item 14: the Progress chart's controls as segmented controls and series chips. The views
 * are Android; the words they print and say, and when All / None can act, are pinned here.
 */
class ChartControlsTest {

    @Test
    void aChipPrintsThePhaseAsAShortWordAndNothingWhenUntagged() {
        assertEquals("pre", Say.phaseTag(Model.Reading.PHASE_PRE));
        assertEquals("post", Say.phaseTag(Model.Reading.PHASE_POST));
        assertEquals("", Say.phaseTag(Model.Reading.PHASE_UNKNOWN),
            "\"Std —\" becomes \"Std\": no stray dash for an untagged reading");
    }

    @Test
    void aChipSaysThePhaseInWords() {
        assertEquals("MSEG, before a session",
            Say.seriesSaid(Model.Reading.METHOD_MSEG, Model.Reading.PHASE_PRE));
        assertEquals("BPSSL, after a session",
            Say.seriesSaid(Model.Reading.METHOD_BPSSL, Model.Reading.PHASE_POST));
        assertEquals("Standardised",
            Say.seriesSaid(Model.Reading.METHOD_STANDARDIZED, Model.Reading.PHASE_UNKNOWN));
    }

    @Test
    void theCaptionNamesTheMetricAndTheDisplayUnit() {
        assertEquals("Girth, cm", Say.trendAxisCaption(false, Model.Fmt.S_CM));
        assertEquals("Length, in", Say.trendAxisCaption(true, Model.Fmt.S_IN));
    }

    @Test
    void allAndNoneAreInertWhenTheyWouldChangeNothing() {
        assertTrue(Meas.seriesAllWouldAdd(4, 2), "two of four ticked: All adds two");
        assertFalse(Meas.seriesAllWouldAdd(4, 4), "every series already on");
        assertFalse(Meas.seriesAllWouldAdd(0, 0), "nothing to add");
        assertTrue(Meas.seriesNoneWouldClear(1));
        assertFalse(Meas.seriesNoneWouldClear(0), "nothing ticked: None clears nothing");
    }

    @Test
    void noneIsAnAnswerButAPrunedSelectionFallsBackToTheDefaults() {
        assertTrue(Meas.seriesNeedsDefault(0, false),
            "first open, or the window or metric emptied it: choose for them");
        assertFalse(Meas.seriesNeedsDefault(0, true),
            "they pressed None or unticked the last chip: keep it empty");
        assertFalse(Meas.seriesNeedsDefault(2, false), "something is ticked");
    }

    @Test
    void aChipsLineSampleIsItsTracesInkAndGirthNeverBorrowsStdsBlue() {
        for (int m = Model.Reading.METHOD_STANDARDIZED; m <= Model.Reading.METHOD_NBPSL; m++)
            assertEquals(Look.BLOCKS[m], Look.seriesInk(m), "length method " + m);
        assertEquals(Look.BLOCKS[1], Look.seriesInk(Model.Reading.METHOD_MSEG));
        assertEquals(Look.BLOCKS[2], Look.seriesInk(Model.Reading.METHOD_MSSG));
        assertTrue(Look.seriesInk(Model.Reading.METHOD_MSEG)
                != Look.seriesInk(Model.Reading.METHOD_STANDARDIZED));
        assertTrue(Look.seriesInk(Model.Reading.METHOD_MSSG)
                != Look.seriesInk(Model.Reading.METHOD_STANDARDIZED));
    }
}
