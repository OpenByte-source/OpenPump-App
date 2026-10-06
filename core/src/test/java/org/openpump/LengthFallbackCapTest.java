package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 R-47 (L3): THE LENGTH NO-READINGS FALLBACK STOPS AT 6. Four length training weeks with no
 * reading (from month 3) add a strain set, then it waits four more - only up to 6; past 6 the
 * sets need readings.
 */
class LengthFallbackCapTest {

    @Test void fromFourSetsToSixAndNoFurther() {
        LengthYear y = new LengthYear();
        y.months = 4;
        y.lengthPlanKpa = 30;
        y.lengthOffsetKpa = 0;
        y.mostKpa = 0;
        y.lengthLoadMode = Model.LENGTH_LOAD_AFTER12;
        y.setup();
        y.m.trainerLength.strainSets = 4;                  // an upgrader's 4, handed over
        y.run();
        int fives = 0, sixes = 0;
        for (int i = 0; i < y.events.size(); i++) {
            String e = y.events.get(i);
            if (e.contains("strain 4->5")) fives++;
            if (e.contains("strain 5->6")) sixes++;
            assertTrue(!e.contains("strain 6->7"), "past 6 needs readings: " + e);
        }
        assertEquals(1, fives, y.trace());
        assertEquals(1, sixes, y.trace());
        assertTrue(y.trace().contains(Plan.NO_READINGS_RULE), y.trace());
        assertEquals(6, y.sets[52]);
        int firstSix = 0;
        for (int w = 1; w <= 52 && firstSix == 0; w++) if (y.sets[w] == 6) firstSix = w;
        assertTrue(firstSix > 5 && firstSix < 20, "two four-week waits: week " + firstSix);
    }

    @Test void theOwnerWithNoReadingsStaysAtSix() {
        LengthYear y = new LengthYear().setup().run();
        for (int w = 1; w <= 52; w++) assertEquals(6, y.sets[w], "week " + w);
    }

    @Test void theCardSaysUpToSix() {
        assertEquals(6, Plan.NO_READINGS_LENGTH_TOP);
        assertTrue(Plan.lengthNoReadingsWords().contains("(up to 6)"));
    }
}
