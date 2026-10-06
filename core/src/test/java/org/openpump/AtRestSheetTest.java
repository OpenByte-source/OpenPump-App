package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * MEASUREMENT POLISH, ITEM 8 - THE AT-REST SHEET OFFERS NO "Std".
 *
 * "Std" means measured while the pump holds a set pressure. The log door's at-rest sheet
 * offered it anyway, under both Length and Girth, so a reading taken at rest could be filed
 * with the Std tag and no hold behind it: drawn on the Std line, compared with nothing. After
 * this a standardised reading only comes from choosing "Standardised", which runs the hold.
 */
class AtRestSheetTest {

    private static final int[] AT_REST = {
        Model.Reading.METHOD_BPEL, Model.Reading.METHOD_BPSSL, Model.Reading.METHOD_BPSL,
        Model.Reading.METHOD_NBPEL, Model.Reading.METHOD_NBPSL,
        Model.Reading.METHOD_MSEG, Model.Reading.METHOD_MSSG };

    @Test void theSheetHasNoStdRow() {
        for (int i = 0; i < Meas.LOG_ROWS.length; i++)
            assertNotEquals(Model.Reading.METHOD_STANDARDIZED, Meas.LOG_ROWS[i].method,
                "row " + i + " (" + Meas.LOG_ROWS[i].label + ") must not be Std");
    }

    @Test void everyAtRestMethodHasExactlyOneRowOnItsOwnMetric() {
        assertEquals(AT_REST.length, Meas.LOG_ROWS.length);
        for (int m : AT_REST) {
            int rows = 0;
            for (int i = 0; i < Meas.LOG_ROWS.length; i++) {
                Meas.LogRow row = Meas.LOG_ROWS[i];
                if (row.method != m) continue;
                rows++;
                assertEquals(Model.Reading.methodIsGirth(m), row.girth,
                    Model.Reading.methodLabel(m) + " sits under the metric it measures");
                assertEquals(Model.Reading.methodLabel(m), row.label,
                    "the row is named by its method's own code");
            }
            assertEquals(1, rows, Model.Reading.methodLabel(m) + " has one row");
        }
    }

    @Test void noFillOfTheSheetFilesAStandardisedReading() {
        Double[] all = new Double[Meas.LOG_ROWS.length];
        for (int i = 0; i < all.length; i++) all[i] = Double.valueOf(10.0 + i);
        List<Model.Reading> batch = Meas.buildLogReadings(all, 1L, "d", "m1", "",
                                                          Model.Reading.PHASE_UNKNOWN);
        assertEquals(Meas.LOG_ROWS.length, batch.size(), "one reading per filled row");
        for (Model.Reading r : batch) {
            assertTrue(Model.Reading.isAtRestMethod(r.method), "an at-rest method's reading");
            assertFalse(Model.Reading.isStandardised(r), "...with no hold recorded");
        }
    }
}
