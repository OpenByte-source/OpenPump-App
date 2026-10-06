package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R-25 (C7, the owner's decision, 2026-10-01): THE HYBRID'S OWN HOLDS MOVE. From Level 3 the
 * hybrid runs the fatigue block and then five-minute holds - 6 at Level 3, 8 at Level 4. Its
 * yield steps and its no-readings fallback move those holds (TrainerTrackState#hybridYield,
 * up to 8), one at a time; the hidden interval count is not touched. They used to be fixed,
 * so yield did nothing to a hybrid. What runs is held to the time cap (R-27).
 */
class HybridYieldTest {

    static final int GI = Plan.TRACK_GIRTH_INTERVAL;

    static Plan.Inputs hybrid(int level, int hybridYield) {
        Plan.Inputs in = YieldWindowTest.girth(level, level >= Plan.L4 ? 18 : 14);
        in.hybridYield = hybridYield;
        in.hybridHolds = Plan.hybridHolds(level, hybridYield);
        return in;
    }

    @Test void theHybridsHolds() {
        assertEquals(6, Plan.hybridHolds(Plan.L3, 0));
        assertEquals(7, Plan.hybridHolds(Plan.L3, 1));
        assertEquals(8, Plan.hybridHolds(Plan.L3, 5), "its top is 8");
        assertEquals(8, Plan.hybridHolds(Plan.L4, 0));
        assertEquals(RxBuild.HYBRID_HOLDS_L3, Plan.HYBRID_L3_HOLDS);
        assertEquals(RxBuild.HYBRID_HOLDS_L4, Plan.HYBRID_L4_HOLDS);
    }

    @Test void aLowStepMovesTheHybridsOwnHolds() {
        Plan.Inputs in = hybrid(Plan.L3, 0);
        in.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        Plan.Decision add = Plan.evaluate(in);
        assertEquals(Plan.ACTION_ADD_VOLUME, add.action);
        assertEquals(1, add.setsDelta, "one hold, not the interval's two sets");
        assertTrue(add.hybrid);
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L3; st.carriedSets = 14;
        assertTrue(Mint.commitYield(st, GI, add, 100L));
        assertEquals(1, st.hybridYield, "hybrid holds 6 -> 7");
        assertEquals(0, st.yieldSets, "the interval count untouched");
        assertEquals(14, Mint.totalSets(GI, st));
        // At the top (8): the offer.
        Plan.Inputs top = hybrid(Plan.L3, 2);
        top.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        assertEquals(Plan.ACTION_OFFER_BREAK, Plan.evaluate(top).action);
    }

    @Test void theFallbackAndTheHighArmMoveThemToo() {
        Plan.Inputs none = hybrid(Plan.L3, 0);
        none.hasYieldData = false;
        none.weeksWithoutReadings = Plan.NO_READINGS_WEEKS;
        Plan.Decision fb = Plan.evaluate(none);
        assertEquals(Plan.ACTION_ADD_VOLUME, fb.action);
        assertEquals(1, fb.setsDelta);
        assertTrue(fb.hybrid);
        assertEquals("No girth readings for 4 weeks: add 1 hold? Measuring lets the plan "
            + "adjust to you.", fb.reason);
        Plan.Inputs high = hybrid(Plan.L3, 1);
        high.consecutiveHighYield = Plan.YIELD_DEBOUNCE;
        Plan.Decision cut = Plan.evaluate(high);
        assertEquals(Plan.ACTION_REDUCE_VOLUME, cut.action);
        assertTrue(cut.hybrid);
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L3; st.carriedSets = 14; st.hybridYield = 1;
        assertTrue(Mint.commitYield(st, GI, cut, 100L));
        assertEquals(0, st.hybridYield);
        assertFalse(Mint.commitYield(st, GI, cut, 200L), "never under the level's count");
    }

    @Test void theRoutineRunsTheHybridsHoldsUnderTheCap() {
        Model m = new Model();
        m.trainerGirthHybrid = true;
        m.trainerGirth.level = Plan.L3;
        m.trainerGirth.hybridYield = 1;
        Mint.Rx rx = Mint.prescribe(GI, Plan.L3, 0, 30, 7, 43, 14, null);
        // The standard fatigue block: 36 min - 7.5 = 28.5 -> 5 five-minute holds run.
        assertEquals(5, RxBuild.hybridRx(m, rx, false).sets);
        // With the fatigue block off the cap has room: the kept hold runs (6 + 1 = 7).
        m.programGirth.fatigue = Model.Program.FAT_OFF;
        assertEquals(7, RxBuild.hybridRx(m, rx, false).sets);
        m.trainerGirth.hybridYield = 0;
        assertEquals(6, RxBuild.hybridRx(m, rx, false).sets);
        // A level crossing starts the kept holds again at the new level's count.
        Mint.crossGirthLevel(m.trainerGirth, GI, Plan.L4, 500L);
        assertEquals(0, m.trainerGirth.hybridYield);
    }

    @Test void fillGivesTheHybridsHolds() {
        Model m = PressureClockTest.model();
        m.trainerGirthHybrid = true;
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3; st.carriedSets = 14; st.hybridYield = 1;
        Plan.Inputs in = YieldWindowTest.girth(Plan.L3, 14);
        TrainerTab.fillGirthInputs(m, GI, st, 7, PressureClockTest.at(7, 8), in);
        assertEquals(7, in.hybridHolds);
        assertEquals(Mint.HOLD_TRADITIONAL_SEC, in.r2HoldSec);
        assertEquals(450, in.r2FatSec);
        m.trainerGirthHybrid = false;
        TrainerTab.fillGirthInputs(m, GI, st, 7, PressureClockTest.at(7, 8), in);
        assertEquals(0, in.hybridHolds);
        assertEquals(Mint.HOLD_INTERVAL_SEC, in.r2HoldSec);
    }
}
