package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

/**
 * readings.csv SAYS HOW EACH READING WAS TAKEN, AND AN EMPTY CELL MEANS "NOT MEASURED".
 *
 * The data-integrity review (app-polish 2624f17):
 *   2. there was no method, phase or kind column - BPEL, NBPEL, BPSSL, BPSL and NBPSL rows
 *      could not be told apart, post-session rows mixed with cold ones, and a Std reading
 *      with no hold looked like an at-rest row. The three columns go at the END, so every
 *      existing column keeps its name and position;
 *   3. a half-filled Std reading exported len_cm=0 - the check was the method alone.
 */
class ReadingsCsvTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long T = 1786000000000L;

    private static String[] row(Model.Reading r) {
        List<Model.Reading> rs = new ArrayList<Model.Reading>();
        rs.add(r);
        String csv = Export.readingsCsv(new Model(), rs, UTC);
        String[] lines = csv.split("\r\n");
        assertEquals(2, lines.length, csv);
        return lines[1].split(",", -1);
    }

    private static int col(String name) {
        String[] h = Export.READINGS_HEADER.split(",");
        for (int i = 0; i < h.length; i++) if (h[i].equals(name)) return i;
        throw new AssertionError("no column " + name);
    }

    @Test void theOldColumnsKeepTheirPlacesAndTheNewOnesComeLast() {
        assertTrue(Export.READINGS_HEADER.startsWith(
            "ts_iso,len_cm,gir_cm,len_display,gir_display,size_unit,"
            + "hold_kpa,observed_kpa,has_front,has_side,has_top,"),
            "an old spreadsheet reading by position keeps every column it had");
        assertTrue(Export.READINGS_HEADER.endsWith(",method,phase,kind"), Export.READINGS_HEADER);
    }

    @Test void eachAtRestMethodIsNamed() {
        int[] ms = { Model.Reading.METHOD_BPEL, Model.Reading.METHOD_NBPEL,
                     Model.Reading.METHOD_BPSSL, Model.Reading.METHOD_BPSL,
                     Model.Reading.METHOD_NBPSL, Model.Reading.METHOD_MSEG,
                     Model.Reading.METHOD_MSSG };
        String[] codes = { "BPEL", "NBPEL", "BPSSL", "BPSL", "NBPSL", "MSEG", "MSSG" };
        for (int i = 0; i < ms.length; i++) {
            Model.Reading r = new Model.Reading();
            r.ts = T; r.method = ms[i];
            if (Model.Reading.methodIsGirth(ms[i])) r.gir = 12.0; else r.len = 15.0;
            String[] c = row(r);
            assertEquals(codes[i], c[col("method")]);
            assertEquals("at_rest", c[col("kind")]);
        }
    }

    @Test void thePhaseSaysBeforeOrAfterAndEmptyWhenNotSaid() {
        Model.Reading r = new Model.Reading();
        r.ts = T; r.method = Model.Reading.METHOD_BPEL; r.len = 15.0;
        r.phase = Model.Reading.PHASE_PRE;
        assertEquals("before", row(r)[col("phase")]);
        r.phase = Model.Reading.PHASE_POST;
        assertEquals("after", row(r)[col("phase")]);
        r.phase = Model.Reading.PHASE_UNKNOWN;
        assertEquals("", row(r)[col("phase")], "not said is an empty cell, never a guess");
    }

    @Test void theKindTellsAStdReadingWithNoHoldFromAnAtRestOne() {
        Model.Reading std = new Model.Reading();
        std.ts = T; std.len = 15.4; std.gir = 12.1;
        std.holdKpa = Double.valueOf(20.0); std.observedKpa = Double.valueOf(19.8);
        String[] s = row(std);
        assertEquals("STD", s[col("method")]);
        assertEquals("standardised", s[col("kind")]);
        assertEquals("20", s[col("hold_kpa")]);

        Model.Reading noHold = new Model.Reading();          // Std line, no hold recorded
        noHold.ts = T; noHold.len = 15.0; noHold.gir = 12.2;
        String[] n = row(noHold);
        assertEquals("STD", n[col("method")]);
        assertEquals("no_hold", n[col("kind")],
            "a Std reading with no hold is its own kind - never mistaken for at rest");
        assertEquals("", n[col("hold_kpa")]);
    }

    @Test void anUnmeasuredValueIsAnEmptyCellNeverZero() {
        // The reviewer's half-filled Std reading: girth measured, length not.
        Model.Reading h = new Model.Reading();
        h.ts = T; h.gir = 12.1; h.holdKpa = Double.valueOf(20.0);
        String[] c = row(h);
        assertEquals("", c[col("len_cm")], "len_cm was 0 - it was never measured");
        assertEquals("", c[col("len_display")]);
        assertEquals("12.1", c[col("gir_cm")]);
        assertEquals("", c[col("observed_kpa")], "unknown vacuum, empty");
        for (int i = 0; i < c.length; i++)
            assertFalse(c[i].equals("0") || c[i].equals("0.0"), "column " + i + " is a bare 0");
        // ...and the girth half of the same rule.
        Model.Reading l = new Model.Reading();
        l.ts = T; l.len = 15.0; l.holdKpa = Double.valueOf(20.0);
        c = row(l);
        assertEquals("", c[col("gir_cm")]);
        assertEquals("", c[col("gir_display")]);
    }
}
