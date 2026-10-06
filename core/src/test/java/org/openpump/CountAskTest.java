package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * "Count this toward training?" asks one question and gives one line before its answers;
 * the accounting waits behind "Why?". The line has to give the reason that is true of the
 * run, and the accounting has to keep every promise the old paragraph made.
 */
class CountAskTest {

    @Test
    void aSingleSetIsSaidToBeOneWhateverRoutineIsLoaded() {
        assertEquals(Say.UNCOUNTED_ONE_SET, Say.uncountedWhy(true, 0));
        assertEquals(Say.UNCOUNTED_ONE_SET, Say.uncountedWhy(true, Plan.TRACK_FEEDER));
        assertEquals("It was a single set, not a plan session, so it isn't counted yet.",
            Say.uncountedLine(Say.UNCOUNTED_ONE_SET));
    }

    @Test
    void aRoutineThePlanDidNotWriteIsNotFromYourPlan() {
        assertEquals(Say.UNCOUNTED_OFF_PLAN, Say.uncountedWhy(false, 0));
        assertEquals("It wasn't from your plan, so it isn't counted yet.",
            Say.uncountedLine(Say.UNCOUNTED_OFF_PLAN));
    }

    @Test
    void aFeederIsNeverToldItWasNotFromThePlan() {
        assertEquals(Say.UNCOUNTED_FEEDER, Say.uncountedWhy(false, Plan.TRACK_FEEDER));
        String line = Say.uncountedLine(Say.UNCOUNTED_FEEDER);
        assertFalse(line.contains("plan"), line);
        assertTrue(line.startsWith("Feeder"), line);
    }

    @Test
    void eachLineIsOneSentence() {
        int[] all = { Say.UNCOUNTED_OFF_PLAN, Say.UNCOUNTED_ONE_SET, Say.UNCOUNTED_FEEDER };
        for (int why : all) {
            String line = Say.uncountedLine(why);
            assertTrue(line.endsWith("."), line);
            assertEquals(line.indexOf('.'), line.length() - 1, line);
        }
    }

    @Test
    void whyKeepsTheWholeAccounting() {
        String why = Say.countWhy(84.0, TrainerTab.trackLabel(Plan.TRACK_GIRTH_INTERVAL));
        assertTrue(why.startsWith("It delivered 1.4 min at or above your working pressure."), why);
        assertTrue(why.contains("this week's delivered total on girth · interval."), why);
        assertTrue(why.contains("doesn't make today a training day"), why);
        assertTrue(why.contains("level gates"), why);
    }
}
