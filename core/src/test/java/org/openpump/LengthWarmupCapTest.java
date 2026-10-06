package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * S18 - THE LENGTH WARM-UP IS CAPPED AT 80% OF THE PULL.
 *
 * t10 R-01/R-03: the warm-up is P2's reps now, and a length warm-up in front of a traction
 * pull ENDS at 80 % of it (RunShape#warmCapKpa) - at every load, not only the low ones where
 * the old flat prime collapsed onto the pull. Girth warms up to its work. Every rep of the
 * length warm-up stays under the pull it precedes.
 *
 * These pin RxBuild#warmupStage directly (a pure core method) rather than the whole mint
 * pipeline, against a hand-built Mint.Rx, so the low-load and high-load cases are exact and
 * do not drift with the ladder's own load table.
 */
class LengthWarmupCapTest {

    private static Model model() {
        Model m = new Model();
        m.ceilKpa = 40;          // default device ceiling
        return m;
    }

    private static Mint.Rx rx(int track, int pressureKpa) {
        return new Mint.Rx(track, Plan.L1, 1, 60, 30, pressureKpa, false, 5.0);
    }

    /** The last rep's pull - where the warm-up gets to. */
    private static int endKpaOf(Model m, Model.Stage warm) {
        return RxBuild.lastPullKpa(m, warm);
    }

    @Test
    void atALowTractionLoadTheLengthWarmUpEndsAtEightyPerCent() {
        Model m = model();
        Model.Stage warm = RxBuild.warmupStage(m, rx(Plan.TRACK_LENGTH, 10));
        assertEquals(8, endKpaOf(m, warm), "80% of a 10 kPa pull, rounded");
    }

    @Test
    void theSameLowPressureLeavesGirthsWarmUpAtItsWork() {
        Model m = model();
        Model.Stage warm = RxBuild.warmupStage(m, rx(Plan.TRACK_GIRTH_INTERVAL, 10));
        assertEquals(10, endKpaOf(m, warm), "girth warms up to its work");
    }

    @Test
    void atAWorkingLengthLoadTheWarmUpStillEndsAtEightyPerCent() {
        Model m = model();
        Model.Stage warmLen = RxBuild.warmupStage(m, rx(Plan.TRACK_LENGTH, 34));
        Model.Stage warmGirth = RxBuild.warmupStage(m, rx(Plan.TRACK_GIRTH_INTERVAL, 20));
        assertEquals(27, endKpaOf(m, warmLen), "80 % of 34");
        assertEquals(20, endKpaOf(m, warmGirth), "girth at 20 reaches it: 12 .. 20");
    }

    @Test
    void theLengthWarmUpNeverReachesThePullItPrecedes() {
        Model m = model();
        for (int top = 2; top <= 40; top += 1) {
            Model.Stage warm = RxBuild.warmupStage(m, rx(Plan.TRACK_LENGTH, top));
            if (warm == null) continue;
            assertTrue(endKpaOf(m, warm) <= RunShape.warmCapKpa(top),
                "the warm-up's " + endKpaOf(m, warm) + " kPa past 80 % of the pull " + top);
            for (String id : warm.setIds) {
                Model.Set s = m.set(id);
                assertTrue(s.up <= top && s.up2 <= top, "a rep past the pull at " + top);
            }
        }
    }
}
