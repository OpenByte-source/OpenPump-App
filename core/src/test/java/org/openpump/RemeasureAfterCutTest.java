package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * t10 REAL-8 (parity round 2): "MEASURE AGAIN" IS NOT ASKED OF THE READING A CUT HAS ALREADY
 * ANSWERED. Two readings over 6 % confirm the cut; the cut restarts the strain clock, so the
 * reading that confirmed it is no longer "confirmed" - and the morning after the cut the ladder
 * read that same pre-cut reading as one new high reading and asked for a re-measure. The model
 * asks again only on a new reading: a high reading taken after the cut asks once more, and two
 * of them confirm the next cut (a week after the last).
 */
class RemeasureAfterCutTest {

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    private static Plan.Decision eval(Model m, long now) {
        return Plan.evaluate(LengthYear.inputsFor(m, now));
    }

    /** The owner, cut once: two readings over 6 %, the cut taken on day 5. */
    private static Model cutOnce() {
        Model m = new LengthYear().setup().m;
        LengthYear.measured(m, day(1, 9), "PL", 7.0);
        LengthYear.measured(m, day(3, 9), "PL", 7.0);
        Plan.Decision cut = eval(m, day(5, 8));
        assertEquals(Plan.LENGTH_CUT_RULE, cut.rule);
        LengthTrack.acceptLoad(m, cut.rule, cut.loadLb, cut.pressureKpa, day(5, 8));
        return m;
    }

    @Test void theMorningAfterTheCutDoesNotAskAgainOnThePreCutReading() {
        Model m = cutOnce();
        Plan.Decision d = eval(m, day(6, 8));
        assertNotEquals(Plan.LENGTH_HIGH_RULE, d.rule, "the cut answered that reading: " + d.rule);
        assertNotEquals(Plan.ACTION_REMEASURE, d.action, d.rule);
    }

    @Test void aNewHighReadingAfterTheCutAsksOnceMore() {
        Model m = cutOnce();
        LengthYear.measured(m, day(7, 9), "PL", 7.5);
        Plan.Decision d = eval(m, day(8, 8));
        assertEquals(Plan.ACTION_REMEASURE, d.action, d.rule);
        assertEquals(Plan.LENGTH_HIGH_RULE, d.rule);
        // A second one confirms - inside the cut's week, so it waits.
        LengthYear.measured(m, day(9, 9), "PL", 7.5);
        assertEquals(Plan.LENGTH_CUT_WAIT_RULE, eval(m, day(10, 8)).rule);
    }
}
