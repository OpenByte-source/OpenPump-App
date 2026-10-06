package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * POINT 19, OPTION A - "Save at rest, then standardise" (AtRestFirst).
 *
 * Choosing Standardised on the log door used to hide the at-rest rows (never saved) and keep
 * the at-rest photos, which the hold then filed on the standardised reading. Now what was
 * entered at rest is named, saved as its own at-rest reading when the hold starts, and the
 * hold begins a new reading. WiringCheck invariant 82 holds the door to the order.
 */
class AtRestFirstTest {

    private String savedSize;

    @BeforeEach void cm() { savedSize = Model.Fmt.sizeUnit; Model.Fmt.sizeUnit = Model.Fmt.S_CM; }
    @AfterEach void restore() { Model.Fmt.sizeUnit = savedSize; }

    private static Double[] rows(double bpel, double mseg) {
        Double[] v = new Double[Meas.LOG_ROWS.length];
        for (int i = 0; i < Meas.LOG_ROWS.length; i++) {
            if (Meas.LOG_ROWS[i].method == Model.Reading.METHOD_BPEL && bpel > 0) v[i] = bpel;
            if (Meas.LOG_ROWS[i].method == Model.Reading.METHOD_MSEG && mseg > 0) v[i] = mseg;
        }
        return v;
    }

    @Test void theThreeStates() {
        assertEquals(AtRestFirst.NOTHING, AtRestFirst.state(0, 0));
        assertEquals(AtRestFirst.SAVE_FIRST, AtRestFirst.state(2, 0));
        assertEquals(AtRestFirst.SAVE_FIRST, AtRestFirst.state(1, 2), "rows with photos save first");
        assertEquals(AtRestFirst.NEEDS_NUMBER, AtRestFirst.state(0, 2),
            "photos with no measurement cannot be filed on their own, and are never dropped");
    }

    @Test void noPhotoSlotsBeforeTheHold() {
        assertTrue(AtRestFirst.photoSlotsShown(false, false), "at rest: slots");
        assertFalse(AtRestFirst.photoSlotsShown(false, true),
            "Standardised, not yet held: a photo here would be at rest on a standardised reading");
        assertTrue(AtRestFirst.photoSlotsShown(true, true), "under the hold: slots");
    }

    @Test void theCardNamesWhatWillBeSaved() {
        String line = AtRestFirst.cardLine(rows(15.2, 12.0), Arrays.asList(Shot.FRONT, Shot.SIDE),
                                           "14:02");
        assertEquals("BPEL 15.2 cm · MSEG 12.0 cm · POV and Side photos · 14:02", line);
        assertEquals("Saved as its own at-rest reading when the hold starts.",
            AtRestFirst.cardCaption(AtRestFirst.SAVE_FIRST, 2));
        assertEquals("POV photo · 09:10",
            AtRestFirst.cardLine(rows(0, 0), Collections.singletonList(Shot.FRONT), "09:10"));
        assertEquals("2 photos taken at rest — add the at-rest measurement they go with.",
            AtRestFirst.cardCaption(AtRestFirst.NEEDS_NUMBER, 2));
    }

    @Test void theButtonSaysWhatItWillDo() {
        assertEquals("Standardise and measure ›", AtRestFirst.buttonLabel(AtRestFirst.NOTHING));
        assertEquals("Save at rest, then standardise ›",
            AtRestFirst.buttonLabel(AtRestFirst.SAVE_FIRST));
        assertTrue(AtRestFirst.buttonNote(AtRestFirst.SAVE_FIRST, "−5.9 inHg")
            .startsWith("Saves your at-rest reading, then the pump pulls to −5.9 inHg"));
        assertTrue(AtRestFirst.buttonNote(AtRestFirst.NOTHING, "−5.9 inHg")
            .startsWith("The pump pulls to −5.9 inHg and holds it while you measure."),
            "unchanged when nothing was entered at rest");
        assertTrue(AtRestFirst.waitingReason(2).contains("add the at-rest measurement"));
    }

    @Test void theSnacksAreNeutral() {
        assertEquals("At-rest reading saved · BPEL, MSEG · 2 photos",
            AtRestFirst.savedSnack(Arrays.asList("BPEL", "MSEG"), 2));
        assertEquals("At-rest reading saved · BPEL", AtRestFirst.savedSnack(
            Collections.singletonList("BPEL"), 0));
        assertEquals("2 photos taken at rest were removed", AtRestFirst.removedSnack(2));
        assertEquals("1 photo taken at rest was removed", AtRestFirst.removedSnack(1));
    }

    @Test void aShotCopyIsIndependentAndRestores() {
        Shot s = new Shot();
        s.confirmAdvance(Shot.FRONT);
        s.armShutter();
        s.commit("/p/front.jpg", null, "lit from the left", false, 1.0, 2.0, null, Boolean.FALSE);
        Shot kept = s.copy();
        s.reset();
        assertFalse(s.tookAny());
        assertTrue(kept.has(Shot.FRONT), "the copy keeps what the buffer lost");
        s.copyFrom(kept);
        assertEquals("/p/front.jpg", s.pathFor(Shot.FRONT));
        assertEquals("lit from the left", s.noteFor(Shot.FRONT));
        assertEquals(Boolean.FALSE, s.heldFor(Shot.FRONT));
        assertEquals(Arrays.asList(Shot.FRONT), s.taken());
    }
}
