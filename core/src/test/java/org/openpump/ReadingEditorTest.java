package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MEASUREMENT POLISH, ITEM 18 - A READING EDITOR THAT MATCHES HOW READINGS ARE TAKEN.
 *
 * An MSEG reading (a girth) showed "LENGTH 0.00", and one tap on + made it a 1.0 cm length
 * nobody measured. The editor now offers only the number the method measured (the owner:
 * hide the other), and states how the reading was taken - the hold, the photos - as facts
 * it cannot change.
 */
class ReadingEditorTest {

    private static Model.Reading r(int method, double len, double gir) {
        Model.Reading x = new Model.Reading();
        x.method = method;
        x.len = len;
        x.gir = gir;
        return x;
    }

    @Test void aGirthMethodEditsItsGirthOnly() {
        Model.Reading mseg = r(Model.Reading.METHOD_MSEG, 0, 12.0);
        assertTrue(Meas.editsGirth(mseg));
        assertFalse(Meas.editsLength(mseg), "no Length stepper on a girth reading");
    }

    @Test void aLengthMethodEditsItsLengthOnly() {
        Model.Reading bpssl = r(Model.Reading.METHOD_BPSSL, 14.2, 0);
        assertTrue(Meas.editsLength(bpssl));
        assertFalse(Meas.editsGirth(bpssl));
        // Even one an older editor gave a girth: that number is not what BPSSL measures.
        Model.Reading edited = r(Model.Reading.METHOD_BPSSL, 14.2, 11.0);
        assertFalse(Meas.editsGirth(edited), "hidden, as the owner chose");
    }

    @Test void aStandardisedReadingEditsBothOfItsHalves() {
        Model.Reading std = r(Model.Reading.METHOD_STANDARDIZED, 15.6, 12.6);
        assertTrue(Meas.editsLength(std));
        assertTrue(Meas.editsGirth(std));
    }

    @Test void aStdHalfNeverFilledIsNotOfferedButNeverBoth() {
        Model.Reading lenOnly = r(Model.Reading.METHOD_STANDARDIZED, 15.0, 0);
        assertTrue(Meas.editsLength(lenOnly));
        assertFalse(Meas.editsGirth(lenOnly), "a 0.0 nobody measured is not a number to correct");
        Model.Reading empty = r(Model.Reading.METHOD_STANDARDIZED, 0, 0);
        assertTrue(Meas.editsLength(empty) && Meas.editsGirth(empty),
            "with nothing recorded the method's own numbers are offered, never an empty editor");
    }

    @Test void takenSaysHowItWasTaken() {
        Model.Reading held = r(Model.Reading.METHOD_STANDARDIZED, 15, 12);
        held.holdKpa = Double.valueOf(20);
        held.holdSec = Integer.valueOf(30);
        assertTrue(Say.takenWords(held).startsWith("Standardised at "), Say.takenWords(held));
        assertTrue(Say.takenWords(held).endsWith(", held 30 s"), Say.takenWords(held));
        assertEquals("At rest, no hold", Say.takenWords(r(Model.Reading.METHOD_MSEG, 0, 12)));
        assertEquals("No hold recorded", Say.takenWords(r(Model.Reading.METHOD_STANDARDIZED, 15, 12)));
    }

    @Test void photosAreTheOnesOnTheReadingNotAFlag() {
        Model.Reading x = r(Model.Reading.METHOD_MSEG, 0, 12);
        x.photo = true;   // the old toggle's flag, with nothing behind it
        assertEquals("None", Say.photosWords(x));
        x.photoFront = new Model.Reading.Photo();
        assertEquals("POV", Say.photosWords(x));
        x.photoSide = new Model.Reading.Photo();
        assertEquals("POV and Side", Say.photosWords(x));
    }
}
