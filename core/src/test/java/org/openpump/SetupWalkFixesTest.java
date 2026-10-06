package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE TRAINER SETUP, AS THE DEVICE WALK OF 511895d FOUND IT (2026-09-30): the pure halves of
 * E-I2 (no "Past the usual top?" for an offset that leaves the top where it is, never "about
 * 0 kg more"), E-M4 (an offset that shows as zero reads "the plan") and E-M5 (the pressure
 * steppers land on one grid, up and down alike).
 */
class SetupWalkFixesTest {

    private String unitBefore, loadBefore;
    @BeforeEach void inHg() {
        unitBefore = Model.Fmt.unit; loadBefore = Model.Fmt.loadUnit;
        Model.Fmt.unit = Model.Fmt.U_INHG; Model.Fmt.loadUnit = Model.Fmt.L_KG;
    }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; Model.Fmt.loadUnit = loadBefore; }

    /* ------------------------------------------------------------------------- E-I2 */

    @Test void anOffsetAtTheUsualTopIsNotPastIt() {
        // The owner's length answer: −10.0 inHg, 34 kPa on the wire, over the plan's 33.86.
        double plan = Plan.LENGTH_SOFT_CAP_HI_KPA;
        double off = Scale.offsetFromAnswer(34, plan);
        assertTrue(off > 0 && off < 0.2, "a tenth of a kPa: " + off);
        assertTrue(Scale.needsWarning(off, 0), "the old test alone asked");
        assertFalse(Scale.pastUsualTop(Plan.TRACK_LENGTH, Plan.L3, 7, off),
            "the top stays at 34 kPa - not past it");
        // One step of the unit above it is past it, and is asked.
        assertTrue(Scale.pastUsualTop(Plan.TRACK_LENGTH, Plan.L3, 7, off + 1.0));
        assertTrue(Scale.pastUsualTop(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7, 3.4));
        assertFalse(Scale.pastUsualTop(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7, 0.0));
        assertFalse(Scale.pastUsualTop(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7, -2.0));
    }

    /** Plan simulator t8, finding 4: an answer that is the usual top as the wire's whole kPa
     *  carries it is AT the top on both tracks - no warning, no "kept as your own" note - and
     *  one whole kPa above it is past it. Composes with E-I2: the warning asks only when both
     *  Scale#needsWarning and Scale#pastUsualTop say so. */
    @Test void theTopRoundedToTheWireIsAtTheTopOnBothTracks() {
        int[] tracks = { Plan.TRACK_LENGTH, Plan.TRACK_GIRTH_INTERVAL };
        for (int t : tracks) {
            double top = Scale.usualTopKpa(t, Plan.L3, 7);
            int wire = (int) Math.round(top);
            double at = Scale.offsetFromAnswer(wire, top);
            boolean asked = Scale.needsWarning(at, 0) && Scale.pastUsualTop(t, Plan.L3, 7, at);
            assertFalse(asked, "track " + t + ": " + wire + " kPa over a top of " + top);
            assertEquals("", Scale.setupAnswerNote(top, at, false, 7),
                "track " + t + ": no \"kept as your own pressure, the plan\" note");
            double past = Scale.offsetFromAnswer(wire + 1, top);
            assertTrue(Scale.needsWarning(past, 0) && Scale.pastUsualTop(t, Plan.L3, 7, past),
                "track " + t + ": one whole kPa over is past it");
            assertTrue(Scale.setupAnswerNote(top, past, false, 7).length() > 0);
        }
    }

    @Test void theWarningNeverSaysAboutZeroMore() {
        String w = Scale.offsetWarning(Plan.TRACK_LENGTH, Plan.L3, 7, 0.14, 41, 4.5, false);
        assertFalse(w.contains("0.0 kg") || w.contains("0 kg more"), w);
        assertFalse(w.contains("Your traction pulls move with it"), w);
        String w2 = Scale.offsetWarning(Plan.TRACK_LENGTH, Plan.L3, 7, 3.4, 41, 4.5, false);
        assertTrue(w2.contains("Your traction pulls move with it"), w2);
    }

    /* ------------------------------------------------------------------------- E-M4 */

    @Test void anOffsetThatShowsAsZeroIsThePlan() {
        assertEquals("the plan", Scale.offsetText(0.0));
        assertEquals("the plan", Scale.offsetText(0.14), "−10.0 over 33.9: it read plan +0.0");
        assertEquals("plan +0.1 inHg", Scale.offsetText(Model.Fmt.KPA_PER_INHG * 0.1));
        assertEquals("plan −1.0 inHg", Scale.offsetText(-Model.Fmt.KPA_PER_INHG));
    }

    /* ------------------------------------------------------------------------- E-M5 */

    @Test void theStepperGridIsOneGridBothWays() {
        // Up from the −5.0 inHg default (17 kPa) and back down: the same values.
        List<Integer> up = new ArrayList<Integer>();
        int v = 17;
        while (v < 44) { v = Model.Fmt.gridStepWholeKpa(v, 1); up.add(v); }
        List<Integer> down = new ArrayList<Integer>();
        v = up.get(up.size() - 1);
        while (v > 17) { v = Model.Fmt.gridStepWholeKpa(v, -1); down.add(0, v); }
        down.add(up.get(up.size() - 1));
        assertEquals(up, down.subList(1, down.size()), "up " + up + " down " + down);
        // Between −8 and −12 inHg: −8.0, −8.9, −10.0, −10.9, −12.1 (27, 30, 34, 37, 41 kPa).
        assertTrue(up.contains(27) && up.contains(30) && up.contains(34) && up.contains(37)
            && up.contains(41), up.toString());
        assertFalse(up.contains(29) || up.contains(32), "no −8.6, no −9.4: " + up);
        assertEquals("−8.9 inHg", Model.Fmt.p(30));
        // From off the grid (a linked load's pressure) the next tap lands on it, either way.
        assertEquals(30, Model.Fmt.gridStepWholeKpa(29, 1));
        assertEquals(27, Model.Fmt.gridStepWholeKpa(29, -1));
        // kPa: every whole kPa, as before.
        Model.Fmt.unit = Model.Fmt.U_KPA;
        assertEquals(30, Model.Fmt.gridStepWholeKpa(29, 1));
        assertEquals(28, Model.Fmt.gridStepWholeKpa(29, -1));
    }
}
