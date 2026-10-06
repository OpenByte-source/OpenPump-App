package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * MEASUREMENT POLISH, ITEM 20 - PLAIN WORDS ON THE MEASUREMENT SCREENS, AND THE OWNER'S RULE.
 *
 * At rest and standardised are two equal ways of tracking progress; readings are compared
 * within one method. The words these screens build must say what a reading IS and what it
 * is compared with, and never present an at-rest reading as flagged, lesser or a fault
 * (STUDY-19's R17-R20 and V2 are the ones built in core).
 */
class PlainWordsTest {

    private static Model.Reading held(double observed) {
        Model.Reading r = new Model.Reading();
        r.method = Model.Reading.METHOD_STANDARDIZED;
        r.holdKpa = Double.valueOf(20);
        r.holdSec = Integer.valueOf(30);
        r.observedKpa = Double.valueOf(observed);
        r.len = 15.6; r.gir = 12.6;
        return r;
    }

    private static Model.Reading atRest(int method) {
        Model.Reading r = new Model.Reading();
        r.method = method;
        r.state = Model.Reading.stateForMethod(method);
        if (Model.Reading.methodIsGirth(method)) r.gir = 12.0; else r.len = 14.0;
        return r;
    }

    private static void neutral(String s) {
        String l = s.toLowerCase(Locale.US);
        for (String bad : new String[]{ "flagged", "not standardised", "no longer",
                                        "like-for-like", "fine, but", "not comparable",
                                        "can't be compared" })
            assertFalse(l.contains(bad), "\"" + s + "\" says \"" + bad + "\"");
    }

    @Test void theReadingsCardSaysWhatTheWindowHolds() {
        List<Model.Reading> mixed = Arrays.asList(held(20), held(20.2), atRest(Model.Reading.METHOD_MSEG),
                                                  atRest(Model.Reading.METHOD_MSEG));
        String[] c = Meas.windowSummary(mixed);
        assertEquals("2 standardised, 2 at rest", c[0]);
        neutral(c[0] + " " + c[1]);

        String[] same = Meas.windowSummary(Arrays.asList(atRest(Model.Reading.METHOD_BPSSL),
                                                         atRest(Model.Reading.METHOD_BPSSL)));
        assertEquals("2 at rest · BPSSL", same[0]);
        assertTrue(same[1].startsWith("All taken the same way"), same[1]);

        String[] apart = Meas.windowSummary(Arrays.asList(held(19), held(23)));
        assertEquals("2 standardised", apart[0]);
        assertTrue(apart[1].startsWith("The holds reached "), apart[1]);
        neutral(apart[1]);

        String[] methods = Meas.windowSummary(Arrays.asList(atRest(Model.Reading.METHOD_BPEL),
                                                            atRest(Model.Reading.METHOD_NBPEL)));
        assertEquals("2 at rest", methods[0]);
        neutral(methods[1]);
    }

    @Test void onlyAStdLineReadingWithNoHoldIsDrawnApart() {
        assertFalse(Meas.markedApart(held(20)), "a standardised dot is filled");
        for (int m = Model.Reading.METHOD_BPEL; m <= Model.Reading.METHOD_MSSG; m++)
            assertFalse(Meas.markedApart(atRest(m)),
                Model.Reading.methodLabel(m) + "'s dots are filled - an at-rest line is not lesser");
        Model.Reading noHold = new Model.Reading();
        noHold.method = Model.Reading.METHOD_STANDARDIZED;
        assertTrue(Meas.markedApart(noHold), "the one mark kept: on the Std line with no hold");
    }

    @Test void theReadingsRowSaysWhatKindOfReadingItIs() {
        assertEquals("at rest · MSEG", Say.readingClass(atRest(Model.Reading.METHOD_MSEG)));
        assertTrue(Say.readingClass(held(20)).startsWith("standardised · measured at "));
        assertFalse(Say.readingClass(held(20)).contains("actual"));
        Model.Reading r = atRest(Model.Reading.METHOD_MSEG);
        r.photo = true;
        assertEquals("at rest · MSEG · photo", Say.measTags(r));
        String saved = Model.Fmt.sizeUnit;
        Model.Fmt.sizeUnit = Model.Fmt.S_CM;
        try {
            assertEquals("— / 12.0 cm", Say.measFigures(r, null),
                "an unmeasured length is a dash");
        } finally {
            Model.Fmt.sizeUnit = saved;
        }
    }

    @Test void mixedPairsAreTwoWaysOfMeasuringNotAFault() {
        String why = Model.Reading.comparabilityReason(held(20), atRest(Model.Reading.METHOD_BPEL));
        assertTrue(why.contains("two ways of measuring"), why);
        neutral(why);
        String since = Meas.sinceWhy(Arrays.asList(held(20), atRest(Model.Reading.METHOD_BPEL)));
        // sinceWhy reads only cold (pre / unknown-phase) readings; both are unknown here.
        assertTrue(since.startsWith(Meas.SINCE_NO_CHANGE), since);
        neutral(since);
    }

    @Test void theAfterScreenAndTheEditorSpeakTheSameWay() {
        neutral(Say.takenWords(atRest(Model.Reading.METHOD_BPEL)));
        neutral(Say.readingKind(atRest(Model.Reading.METHOD_BPEL)));
        String[] c = Say.afterChip(atRest(Model.Reading.METHOD_BPEL), held(20), false, 30);
        neutral(c[0] + " " + c[1]);
    }
}
