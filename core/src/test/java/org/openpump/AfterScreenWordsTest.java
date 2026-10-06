package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * MEASUREMENT POLISH, ITEM E2 - THE AFTER SCREEN TELLS YOU AS MUCH AS THE BEFORE SCREEN.
 *
 * "Measurements · after" had no line saying how the reading is being taken; the before
 * screen has one. Say#afterChip is that line: what this reading is, and what it is compared
 * with - in words that never rank an at-rest reading below a standardised one.
 */
class AfterScreenWordsTest {

    private static Model.Reading held(double observedKpa) {
        Model.Reading r = new Model.Reading();
        r.method = Model.Reading.METHOD_STANDARDIZED;
        r.holdKpa = Double.valueOf(20);
        r.holdSec = Integer.valueOf(30);
        r.observedKpa = Double.valueOf(observedKpa);
        return r;
    }

    private static Model.Reading atRest(int method) {
        Model.Reading r = new Model.Reading();
        r.method = method;
        r.state = Model.Reading.stateForMethod(method);
        return r;
    }

    /** Saved under a hold that did not run its count: Std tag, no hold recorded. */
    private static Model.Reading heldUncounted() {
        Model.Reading r = new Model.Reading();
        r.method = Model.Reading.METHOD_STANDARDIZED;
        return r;
    }

    private static void noRanking(String s) {
        String l = s.toLowerCase(Locale.US);
        for (String bad : new String[]{ "flagged", "not standardised", "no longer",
                                        "not comparable", "like-for-like", "fine, but" })
            assertFalse(l.contains(bad), "\"" + s + "\" ranks one kind below the other");
    }

    @Test void aStandardisedPairSaysItIsTakenTheSameWay() {
        String[] c = Say.afterChip(held(20.1), held(20.2), true, 30);
        assertTrue(c[0].startsWith("Standardised at ") && c[0].endsWith("held 30 s"), c[0]);
        assertEquals("taken the same way as your before reading, so the two compare", c[1]);
        noRanking(c[0] + " " + c[1]);
    }

    @Test void anAtRestPairIsNamedByItsMethodAndComparesJustTheSame() {
        String[] c = Say.afterChip(atRest(Model.Reading.METHOD_BPSSL),
                                   atRest(Model.Reading.METHOD_BPSSL), true, 30);
        assertEquals("At rest · BPSSL", c[0]);
        assertEquals("taken the same way as your before reading, so the two compare", c[1]);
    }

    @Test void aMixedPairSaysWhatTheBeforeWasAndThatNoChangeIsWorkedOut() {
        String[] c = Say.afterChip(atRest(Model.Reading.METHOD_BPEL), held(20), false, 30);
        assertTrue(c[1].contains("at rest · BPEL"), c[1]);
        assertTrue(c[1].contains("no before-to-after change is worked out"), c[1]);
        noRanking(c[0] + " " + c[1]);

        String[] d = Say.afterChip(held(20), atRest(Model.Reading.METHOD_NBPEL), false, 30);
        assertEquals("At rest · NBPEL", d[0]);
        assertTrue(d[1].contains("your before reading was standardised"), d[1]);
        noRanking(d[0] + " " + d[1]);
    }

    @Test void twoHoldsAtDifferentVacuumsSaySoWithTheNumbers() {
        String[] c = Say.afterChip(held(20), held(23), false, 30);
        assertTrue(c[1].startsWith("your before reading was held at "), c[1]);
        assertTrue(c[1].contains("this one at"), c[1]);
        Model.Reading unknown = held(20);
        unknown.observedKpa = null;
        String[] u = Say.afterChip(held(20), unknown, false, 30);
        assertTrue(u[1].contains("was not read"), u[1]);
    }

    @Test void aHoldThatDidNotRunItsCountSaysExactlyThat() {
        String[] c = Say.afterChip(held(20), heldUncounted(), false, 30);
        assertEquals("Held, without the 30 s count", c[0]);
        assertTrue(c[1].contains("no hold recorded"), c[1]);
        noRanking(c[0] + " " + c[1]);
    }

    @Test void readingKindNamesEachWayOfMeasuring() {
        assertEquals("standardised", Say.readingKind(held(20)));
        assertEquals("at rest · MSEG", Say.readingKind(atRest(Model.Reading.METHOD_MSEG)));
        assertEquals("at rest", Say.readingKind(heldUncounted()));
    }
}
