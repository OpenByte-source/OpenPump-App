package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE AT-PRESSURE SET CLOCK SPLITS A TICK BY THE CLOCK THE NET SPLITS IT BY (review M3,
 * 2026-09-30). With a drop above the floor the net reads the drop half from where the pump's
 * cycle last began (the set clock - TupClock#readsSetClock), but the set clock's own tick split
 * its step by the arming stamp, so after a live change, a Revert or a resume the two disagreed
 * about which half a second was in. TupClock#setClockIntoMs puts the set clock's place in the
 * cycle on a running time TupClock#dropMsBetween can split.
 */
class SetClockSplitTest {

    @Test void theSplitIsTheRunningTimesForEveryPlaceInTheCycle() {
        int uh = 15, lh = 5, lo = 20, up = 30;          // a 20 s cycle, a drop above the floor
        long cyc = (uh + lh) * 1000L;
        for (long run = 0; run < 5 * cyc; run += 250) {
            for (long step = 100; step <= 3 * cyc; step += 900) {
                if (run - step < 0) continue;
                long truth = TupClock.dropMsBetween(uh, lh, lo, up, false, run - step, run);
                long at = TupClock.setClockIntoMs(run % cyc, cyc, step);
                assertEquals(truth, TupClock.dropMsBetween(uh, lh, lo, up, false, at - step, at),
                    "run " + run + " step " + step);
                assertTrue(at - step >= 0, "never before zero");
            }
        }
    }

    @Test void aTickAcrossTheCyclesTurnIsSplitWhereItTurns() {
        // 2 s into a cycle, a 4 s tick: the last 2 s of the drop before it, then 2 s of hold.
        long at = TupClock.setClockIntoMs(2000, 20_000, 4000);
        assertEquals(2000, TupClock.dropMsBetween(15, 5, 20, 30, false, at - 4000, at));
        // And the half the net reads at that instant is the hold.
        assertTrue(!TupClock.inDrop(15, 5, 20, 30, false, 2000));
    }
}
