package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R-20 (C1) and R-22 (A2), the owner's decisions, 2026-10-01: THE GIRTH YIELD TARGET IS 6-12 %
 * at every level and style where the yield rule applies (it was 3 % at Level 1, 4-6 % at
 * Level 2 and 6-8 % at Levels 3 and 4), and LEVEL 1 HAS NO HIGH ARM - its yield is a floor
 * only, so three highs neither pause it nor hold up its pressure.
 */
class YieldWindowTest {

    static final int GI = Plan.TRACK_GIRTH_INTERVAL;
    static final double HG = Plan.HG;

    static Plan.Inputs girth(int level, int workSets) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = GI;
        in.level = level;
        in.monthIndex = level >= Plan.L3 ? 7 : 2;
        in.weekIndex = 14;
        in.pressureKpa = 30;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.workSets = workSets;
        in.hasYieldData = true;
        return in;
    }

    /** Three girth sessions at `pct` % yield, two days apart, from day `day`. */
    static void readings(Model m, int day, double pct, int n) {
        for (int i = 0; i < n; i++) {
            Model.Sess s = PressureClockTest.file(m, "g", PressureClockTest.at(day + 2 * i, 9),
                20, 20, 30);
            s.afterBaseThisSession = true;
            s.afterComparable = true;
            s.afterGirAbsCm = Double.valueOf(10.0 * (1.0 + pct / 100.0));
            s.afterGirCm = Double.valueOf(10.0 * pct / 100.0);
        }
    }

    @Test void theTargetIsSixToTwelveAtEveryLevel() {
        for (int l = Plan.L1; l <= Plan.L4; l++) {
            assertEquals(6.0, Plan.yieldTargetLo(l), 1e-9, "L" + l);
            assertEquals(12.0, Plan.yieldTargetHi(l), 1e-9, "L" + l);
        }
        assertTrue(Plan.yieldBelowTarget(5.9, Plan.L2));
        assertFalse(Plan.yieldBelowTarget(7.0, Plan.L2), "L2 at 7 %: in the window");
        assertFalse(Plan.yieldAboveTarget(7.0, Plan.L2));
        assertTrue(Plan.yieldAboveTarget(12.1, Plan.L3));
        assertEquals("Girth target: 6–12% after the session (edema included)",
            Plan.YIELD_TARGET_WORDS);
    }

    @Test void theStreaksAreReadAgainstTheWindow() {
        Model m = PressureClockTest.model();
        readings(m, 0, 10.0, 3);
        TrainerTab.YieldStreaks in = TrainerTab.yieldStreaks(m, GI, Plan.L3);
        assertEquals(0, in.consecutiveLow, "10 %: no change");
        assertEquals(0, in.consecutiveHigh);
        assertTrue(in.inWindow);
        Model lo = PressureClockTest.model();
        readings(lo, 0, 5.0, 3);
        assertEquals(3, TrainerTab.yieldStreaks(lo, GI, Plan.L3).consecutiveLow);
        Model hi = PressureClockTest.model();
        readings(hi, 0, 13.0, 3);
        assertEquals(3, TrainerTab.yieldStreaks(hi, GI, Plan.L1).consecutiveHigh);
        Model l2 = PressureClockTest.model();
        readings(l2, 0, 7.0, 3);
        TrainerTab.YieldStreaks w = TrainerTab.yieldStreaks(l2, GI, Plan.L2);
        assertEquals(0, w.consecutiveLow, "L2 at 7 %: in the window");
        assertEquals(0, w.consecutiveHigh);
    }

    @Test void levelOneHasNoHighArm() {
        for (int wk : new int[] { 10, 14 }) {
            Plan.Inputs in = girth(Plan.L1, 10);
            in.weekIndex = wk;
            in.pressureKpa = 7.0 * HG;
            in.consecutiveHighYield = Plan.YIELD_DEBOUNCE;
            in.ownTargetsMetAtPressure = true;
            if (wk == 14) {                      // past the table's volume phase: the step
                in.netTupMin = 20.0;
                in.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
            }
            Plan.Decision d = Plan.evaluate(in);
            assertNotEquals(Plan.ACTION_PAUSE_VOLUME, d.action, "week " + wk);
            assertNotEquals(Plan.ACTION_REDUCE_VOLUME, d.action, "week " + wk);
            if (wk == 14) {
                assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action,
                    "the week-12+ raise toward 8 inHg is still proposed: " + d.rule);
                assertTrue(d.pressureKpa > in.pressureKpa);
            }
        }
        // ...and the Level 1 -> 2 gate is met after 2 training weeks at 8 inHg.
        Plan.Inputs gate = girth(Plan.L1, 10);
        gate.weekIndex = 16;
        gate.pressureKpa = 8.0 * HG;
        gate.netTupMin = 20.0;
        gate.gateHeldTrainingWeeks = Plan.L1_GATE_HOLD_TRAINING_WEEKS;
        gate.consecutiveHighYield = Plan.YIELD_DEBOUNCE;
        assertEquals(Plan.ACTION_LEVEL_UP, Plan.evaluate(gate).action);
    }

    @Test void theWindowMovesTheSets() {
        // L3 at 5 % x3: +2 (10 -> 12).
        Plan.Inputs low = girth(Plan.L3, 10);
        low.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        Plan.Decision add = Plan.evaluate(low);
        assertEquals(Plan.ACTION_ADD_VOLUME, add.action);
        assertEquals(2, add.setsDelta);
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L3; st.carriedSets = 10;
        assertTrue(Mint.commitYield(st, GI, add, 100L));
        assertEquals(12, Mint.totalSets(GI, st));
        // L3 at 10 % x3: nothing moves.
        Plan.Inputs in = girth(Plan.L3, 12);
        Plan.Decision none = Plan.evaluate(in);
        assertNotEquals(Plan.ACTION_ADD_VOLUME, none.action);
        assertNotEquals(Plan.ACTION_REDUCE_VOLUME, none.action);
        // L3 at 13 % x3 at 12 sets: -2 -> 10.
        Plan.Inputs high = girth(Plan.L3, 12);
        high.consecutiveHighYield = Plan.YIELD_DEBOUNCE;
        Plan.Decision cut = Plan.evaluate(high);
        assertEquals(Plan.ACTION_REDUCE_VOLUME, cut.action);
        assertEquals(-2, cut.setsDelta);
        assertTrue(Mint.commitYield(st, GI, cut, 200L));
        assertEquals(10, Mint.totalSets(GI, st));
        // L3 at 13 % at 10: no change - the pressure tier answers (R-21).
        Plan.Inputs floor = girth(Plan.L3, 10);
        floor.consecutiveHighYield = Plan.YIELD_DEBOUNCE;
        floor.netTupMin = Plan.netMilestoneMin(Plan.L3);
        floor.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        Plan.Decision up = Plan.evaluate(floor);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, up.action);
        assertTrue(up.restartsYield);
    }
}
