package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import org.junit.jupiter.api.Test;

/**
 * t10 R-04 (P1) - NO RUN OVER 120 MINUTES ON THE WHOLE CLOCK. A trainer routine built over it
 * loses counted work holds from the end - never its warm-up, fatigue block, release, swap or
 * expansion coda - a rest left at the end goes too, and the routine says so; the live run
 * stops at two hours on its clock, whatever was sealed. The numbers are SPEC.md's.
 */
class SessionCapTest {

    @Test void girthL4FortySetsLosesOneHold() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L4, 40, 37), day(m));
        // EXPECTATION CHANGED (R11-5): the fatigue block is 15 x 30 s (+25 s of drops); was 118.58.
        assertEquals(119.00, minutes(m, r), 1e-9);
        assertEquals(39, workPulls(m, r).size(), "one work hold trimmed, from the end");
        assertEquals(15, pulls(m, r, stage(r, "fatigue")).size(), "the fatigue block is whole");
        assertEquals(list(12, 15, 18, 21, 24, 27), pulls(m, r, stage(r, "warm")), "and the warm-up");
        assertTrue(r.name.contains("39×2min"), r.name);
        assertEquals(78.0, r.netTargetMin, 1e-9, "the target is what is left");
        assertEquals("Trimmed to stay under 2 hours", RxBuild.trimmedNote(r));
        assertFalse(r.stages.get(r.stages.size() - 1).rest, "no rest left at the end");
        assertEquals(39, RxBuild.holdsRun(m, girth(Plan.L4, 40, 37), day(m)));
    }

    @Test void aBlockTrimmedAwayTakesItsRestWithIt() {
        Model m = owner();
        // 41 sets: 9 blocks, the last of one hold - the trim empties it, and its rest goes too.
        Model.Routine r = build(m, girth(Plan.L4, 41, 37), day(m));
        assertTrue(m.durationMs(r) <= Plan.SESSION_CAP_MIN * 60000L);
        assertFalse(r.stages.get(r.stages.size() - 1).rest, "no rest left at the end");
        for (int i = 1; i < r.stages.size(); i++)
            assertFalse(r.stages.get(i).rest && r.stages.get(i - 1).rest, "no two rests together");
        assertEquals("Trimmed to stay under 2 hours", RxBuild.trimmedNote(r));
    }

    @Test void lengthTwelveStrainAndCodaIsUntrimmed() {
        Model m = owner();
        Model.Routine r = traction(m, 34, 12, day(m));
        assertEquals(100.08, minutes(m, r), 1e-9);
        assertEquals(12, pulls(m, r, stage(r, "strain")).size());
        assertEquals("", RxBuild.trimmedNote(r));
        assertTrue(stage(r, "swap") > 0, "the coda is there");
    }

    @Test void aLongTractionDayTrimsStrainHoldsOnlyAndKeepsTheCoda() {
        Model m = owner();
        Model.Routine r = traction(m, 34, 30, day(m));
        assertTrue(m.durationMs(r) <= Plan.SESSION_CAP_MIN * 60000L, "" + minutes(m, r));
        assertTrue(pulls(m, r, stage(r, "strain")).size() < 30, "strain holds came off");
        assertEquals(10, pulls(m, r, stage(r, "fatigue")).size(), "never the fatigue holds");
        assertTrue(stage(r, "swap") > 0, "never the swap");
        assertEquals(5, workPulls(m, r).size(), "never the expansion coda");
        assertEquals("Trimmed to stay under 2 hours", RxBuild.trimmedNote(r));
    }

    @Test void theLiveStopCountsTheWholeClock() {
        // The run's clock at 2:00:00 with only 1:55:00 sealed: it stops.
        double whole = PreRunHold.wholeForStopSec(0L, true, 7_200_000L, 6900.0);
        assertTrue(Plan.grossCapReached(whole));
        assertFalse(Plan.grossCapReached(PreRunHold.sealedForStopSec(0L, true, 6900.0)),
                    "the sealed count alone would not have");
        assertFalse(Plan.grossCapReached(PreRunHold.wholeForStopSec(0L, true, 7_199_000L, 6900.0)));
        // Before this attempt's run begins only the pressure held before it counts.
        assertEquals(60.0, PreRunHold.wholeForStopSec(60_000L, false, 7_200_000L, 7200.0), 1e-9);
        // ...and the sealed time is never less than counted.
        assertEquals(7300.0, PreRunHold.wholeForStopSec(0L, true, 7_000_000L, 7300.0), 1e-9);
    }
}
