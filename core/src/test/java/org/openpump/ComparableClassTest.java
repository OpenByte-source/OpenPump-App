package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * C8 - ONE RULE FOR "THESE TWO READINGS MAY BE SUBTRACTED" (study problem 7).
 *
 * Three findings, one cause:
 *   - {@link Meas#deltaFor} subtracted the oldest reading of ANY method from the newest, so
 *     "+0.3 this period", the LEN/GIR tiles and "LENGTH Δ" mixed a stretched length with an
 *     erect one, and the headline named only the newest reading's method;
 *   - {@link Model.Reading#comparable}'s hard/soft fallback, kept for readings saved before
 *     the method axis, also matched two readings that DO carry methods - BPEL with NBPEL,
 *     BPSSL with BPSL and NBPSL, even BPEL with MSEG - so the since-last strip, the row
 *     deltas and the chart's joined lines crossed protocols, captioned "taken at rest under
 *     matching conditions";
 *   - comparable() decided "standardised" by holdKpa while the method's doc said method 0.
 *
 * Now: a reading was standardised when a hold is recorded on it
 * ({@link Model.Reading#isStandardised}), and that is the one test everywhere; two
 * readings compare only within one method, and the hard/soft fallback is for two readings
 * that have no method at all; every delta, the chart's line breaks and the words all go
 * through that one comparable().
 */
class ComparableClassTest {

    private static Model.Reading atRest(int method, long ts, double len, double gir) {
        Model.Reading r = new Model.Reading();
        r.id = "r" + ts; r.ts = ts; r.method = method;
        r.state = Model.Reading.stateForMethod(method);
        r.len = len; r.gir = gir;
        return r;
    }

    /** A reading saved before the method axis existed: method 0, no hold, a state. */
    private static Model.Reading legacy(String state, long ts, double len) {
        Model.Reading r = new Model.Reading();
        r.id = "l" + ts; r.ts = ts; r.state = state; r.len = len; r.gir = 12.0;
        return r;
    }

    private static Model.Reading std(double observedKpa, long ts, double len, double gir) {
        Model.Reading r = new Model.Reading();
        r.id = "s" + ts; r.ts = ts;
        r.holdKpa = Double.valueOf(20); r.holdSec = Integer.valueOf(30);
        r.observedKpa = Double.valueOf(observedKpa);
        r.len = len; r.gir = gir;
        return r;
    }

    private static final long DAY = 86400000L;
    private static final long NOW = 2_000_000_000_000L;

    private static Model.MeasLog log(Model.Reading... newestFirst) {
        Model.MeasLog l = new Model.MeasLog();
        l.all.addAll(Arrays.asList(newestFirst));
        return l;
    }

    /* ------------------------------------------------------------ comparable() */

    @Test void twoDifferentMethodsAreNeverComparableEvenInTheSameState() {
        int[][] sameState = {
            { Model.Reading.METHOD_BPEL, Model.Reading.METHOD_NBPEL },
            { Model.Reading.METHOD_BPSSL, Model.Reading.METHOD_BPSL },
            { Model.Reading.METHOD_BPSSL, Model.Reading.METHOD_NBPSL },
            { Model.Reading.METHOD_BPSL, Model.Reading.METHOD_NBPSL },
            { Model.Reading.METHOD_BPEL, Model.Reading.METHOD_MSEG },
            { Model.Reading.METHOD_NBPSL, Model.Reading.METHOD_MSSG },
        };
        for (int i = 0; i < sameState.length; i++) {
            Model.Reading a = atRest(sameState[i][0], 1L, 15, 12);
            Model.Reading b = atRest(sameState[i][1], 2L, 15, 12);
            assertEquals(a.state, b.state, "fixture: both derive the same hard/soft state");
            assertFalse(Model.Reading.comparable(a, b),
                Model.Reading.methodLabel(a.method) + " vs " + Model.Reading.methodLabel(b.method)
                + " - different protocols are different measurements, whatever their state");
            String why = Model.Reading.comparabilityReason(a, b);
            assertTrue(why.indexOf("not directly comparable") >= 0
                    && why.indexOf(Model.Reading.methodLabel(a.method)) >= 0
                    && why.indexOf(Model.Reading.methodLabel(b.method)) >= 0,
                "and the words say so, naming both methods (got: " + why + ")");
        }
    }

    @Test void theSameMethodIsComparable() {
        Model.Reading a = atRest(Model.Reading.METHOD_BPSSL, 1L, 14, 0);
        Model.Reading b = atRest(Model.Reading.METHOD_BPSSL, 2L, 14.4, 0);
        assertTrue(Model.Reading.comparable(a, b));
        assertTrue(Model.Reading.comparabilityReason(a, b).indexOf("BPSSL") >= 0);
    }

    @Test void theStateFallbackIsForTwoReadingsWithNoMethod() {
        assertTrue(Model.Reading.comparable(legacy(Model.Reading.STATE_HARD, 1L, 15),
                                            legacy(Model.Reading.STATE_HARD, 2L, 15.2)),
            "two readings saved before the method axis still line up by their state");
        assertFalse(Model.Reading.comparable(legacy(Model.Reading.STATE_HARD, 1L, 15),
                                             legacy(Model.Reading.STATE_SOFT, 2L, 11)),
            "...and hard against soft still does not");
        Model.Reading bpel = atRest(Model.Reading.METHOD_BPEL, 2L, 15.2, 0);
        assertFalse(Model.Reading.comparable(legacy(Model.Reading.STATE_HARD, 1L, 15), bpel),
            "a reading with no method is not matched to a BPEL by its state - it could have "
                + "been an NBPEL, and it is drawn on a different line");
    }

    @Test void standardisedIsTheRecordedHoldEverywhere() {
        Model.Reading noHold = new Model.Reading();   // Std tag, hold skipped
        noHold.len = 15; noHold.gir = 12;
        assertFalse(Model.Reading.isStandardised(noHold),
            "a Std-tagged reading with no hold recorded was NOT standardised - the tag alone "
                + "is not the claim");
        assertEquals(Model.Reading.CLASS_AT_REST_UNKNOWN, Model.Reading.classOf(noHold));
        Model.Reading held = std(20.1, 1L, 15, 12);
        assertTrue(Model.Reading.isStandardised(held));
        assertEquals(Model.Reading.CLASS_STANDARDISED, Model.Reading.classOf(held));
        assertFalse(Model.Reading.comparable(held, noHold),
            "held against not held is never comparable");
        assertFalse(Model.Reading.comparable(noHold, noHold),
            "and a reading with nothing recorded matches nothing, not even itself");
    }

    /* ------------------------------------------------------ the period's delta */

    @Test void thePeriodDeltaNeverCrossesMethods() {
        // Oldest a BPEL at 14.0, then two Std readings at one vacuum. The old code printed
        // 15.3 - 14.0 = +1.3 "this period" under the Std label.
        Model.MeasLog l = log(std(20.2, NOW - DAY, 15.3, 12.4),
                              std(20.0, NOW - 3 * DAY, 15.0, 12.1),
                              atRest(Model.Reading.METHOD_BPEL, NOW - 5 * DAY, 14.0, 0));
        double[] d = Meas.deltaFor(l, Meas.PERIOD_ALL, NOW);
        assertEquals(0.3, d[0], 1e-9, "Std against Std only (got " + d[0] + ")");
        assertEquals(0.3, d[1], 1e-9);
        Model.Reading[] pair = Meas.periodPair(l, Meas.PERIOD_ALL, NOW, true);
        assertNotNull(pair);
        assertEquals(NOW - DAY, pair[0].ts, "the newer end is the headline's own reading");
        assertEquals(NOW - 3 * DAY, pair[1].ts,
            "and the older end is the one the headline names, never the BPEL");
    }

    @Test void thePeriodDeltaNeverCrossesStatesOrUnknowns() {
        Model.MeasLog l = log(atRest(Model.Reading.METHOD_NBPEL, NOW - DAY, 15.6, 0),
                              atRest(Model.Reading.METHOD_BPEL, NOW - 2 * DAY, 16.9, 0),
                              atRest(Model.Reading.METHOD_NBPEL, NOW - 4 * DAY, 15.1, 0));
        assertEquals(0.5, Meas.deltaFor(l, Meas.PERIOD_ALL, NOW)[0], 1e-9,
            "NBPEL against the older NBPEL, stepping over the BPEL between them");
        Model.MeasLog lone = log(atRest(Model.Reading.METHOD_NBPEL, NOW - DAY, 15.6, 0),
                                 atRest(Model.Reading.METHOD_BPEL, NOW - 2 * DAY, 16.9, 0));
        assertTrue(Double.isNaN(Meas.deltaFor(lone, Meas.PERIOD_ALL, NOW)[0]),
            "no second NBPEL in the window: no delta, never NBPEL minus BPEL");
        assertNull(Meas.periodPair(lone, Meas.PERIOD_ALL, NOW, true));
    }

    @Test void heldReadingsSubtractOnlyWithinTheVacuumTolerance() {
        // Newest held at 22.0 kPa; the oldest at 20.0 is two tolerances away, the middle one
        // at 22.5 is within it - so the change is measured against the middle one.
        Model.MeasLog l = log(std(22.0, NOW - DAY, 15.4, 12),
                              std(22.5, NOW - 2 * DAY, 15.1, 12),
                              std(20.0, NOW - 3 * DAY, 15.0, 12));
        assertEquals(0.3, Meas.deltaFor(l, Meas.PERIOD_ALL, NOW)[0], 1e-9);
    }

    /* ---------------------------------------- the chart and the strip, same rule */

    @Test void theChartBreaksTheLineWhereverTheDeltaWouldRefuse() {
        List<Model.Reading> oldestFirst = new ArrayList<Model.Reading>();
        oldestFirst.add(atRest(Model.Reading.METHOD_BPEL, 1L, 15, 0));
        oldestFirst.add(atRest(Model.Reading.METHOD_NBPEL, 2L, 14.2, 0));
        oldestFirst.add(atRest(Model.Reading.METHOD_NBPEL, 3L, 14.4, 0));
        boolean[] e = Meas.drawableEdges(oldestFirst);
        assertFalse(e[1], "BPEL to NBPEL is not drawn as one line");
        assertTrue(e[2], "NBPEL to NBPEL is");
    }

    @Test void sinceLastSessionRefusesACrossMethodPairAndSaysWhy() {
        List<Model.Reading> newestFirst = Arrays.asList(
            atRest(Model.Reading.METHOD_BPSL, 3L, 11.0, 0),
            atRest(Model.Reading.METHOD_BPSSL, 2L, 14.0, 0));
        assertNull(Meas.sincePair(newestFirst),
            "BPSL against BPSSL is not a change since last session");
        String why = Meas.sinceWhy(newestFirst);
        assertTrue(why.indexOf("BPSL") >= 0 && why.indexOf("BPSSL") >= 0,
            "and the strip names the two methods (got: " + why + ")");
    }
}
