package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R-21 (C2, the owner's decision, 2026-10-01): A HIGH-YIELD DECISION THAT WOULD CHANGE NOTHING
 * FALLS THROUGH. A pause with no yield sets to take back, or a cut at the level's floor (10 at
 * Level 3, 14 at Level 4), used to return before the pressure tier and hold the pressure for
 * as long as the readings stayed high. Now the pressure tier answers, and applying its answer
 * restarts the streak, so the arm is asked again only after three new readings.
 */
class HighYieldFallThroughTest {

    static final int GI = Plan.TRACK_GIRTH_INTERVAL;

    static Plan.Inputs high(int track, int level, int workSets) {
        Plan.Inputs in = YieldWindowTest.girth(level, workSets);
        in.track = track;
        in.consecutiveHighYield = Plan.YIELD_DEBOUNCE;
        return in;
    }

    @Test void atTheFloorThePressureTierAnswers() {
        Plan.Inputs in = high(GI, Plan.L4, 14);
        in.monthIndex = 13;
        Plan.Decision held = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, held.action, "no step due yet: " + held.rule);
        assertTrue(held.restartsYield);
        in.netTupMin = Plan.netMilestoneMin(Plan.L4);
        in.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        Plan.Decision up = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, up.action, "the pressure steps at the floor");
        assertTrue(up.restartsYield);
        // Above the floor it still cuts, never under it.
        Plan.Decision cut = Plan.evaluate(high(GI, Plan.L4, 15));
        assertEquals(Plan.ACTION_REDUCE_VOLUME, cut.action);
        assertEquals(-1, cut.setsDelta, "15 -> 14, the floor");
        assertFalse(cut.restartsYield, "a real cut restarts the streak as any change does");
    }

    @Test void levelTwoWithNothingToTakeBack() {
        Plan.Inputs in = high(GI, Plan.L2, 12);
        in.monthIndex = 4;
        in.pressureKpa = 8.0 * Plan.HG;
        in.netTupMin = 20.0;
        in.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action);
        assertTrue(d.restartsYield);
        in.yieldSets = 2;
        Plan.Decision pause = Plan.evaluate(in);
        assertEquals(Plan.ACTION_PAUSE_VOLUME, pause.action, "O3: L2 keeps its pause at 12 %");
        assertEquals(-2, pause.setsDelta);
    }

    @Test void traditionalAndTheHybridAtTheirOwnFloor() {
        Plan.Inputs trad = high(Plan.TRACK_GIRTH_TRADITIONAL, Plan.L3, 6);
        assertTrue(Plan.evaluate(trad).restartsYield, "nothing yield added: falls through");
        trad.yieldSets = 1;
        Plan.Decision cut = Plan.evaluate(trad);
        assertEquals(Plan.ACTION_REDUCE_VOLUME, cut.action);
        assertEquals(-1, cut.setsDelta, "one hold fewer (R-25)");
        Plan.Inputs hy = high(GI, Plan.L3, 14);
        hy.hybridHolds = 6;
        assertTrue(Plan.evaluate(hy).restartsYield);
        hy.hybridYield = 1;
        hy.hybridHolds = 7;
        Plan.Decision hcut = Plan.evaluate(hy);
        assertEquals(Plan.ACTION_REDUCE_VOLUME, hcut.action);
        assertEquals(-1, hcut.setsDelta);
        assertTrue(hcut.hybrid, "the hybrid's own holds");
    }

    @Test void theStreakRestartsSoNoCutIsShownTwiceWithoutThreeNewReadings() {
        Model m = PressureClockTest.model();
        YieldWindowTest.readings(m, 0, 13.0, 3);
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L4; st.carriedSets = 14;
        Plan.Inputs in = YieldWindowTest.girth(Plan.L4, 14);
        in.monthIndex = 13;
        long now = PressureClockTest.at(7, 8);
        TrainerTab.fillGirthInputs(m, GI, st, 13, now, in);
        assertEquals(3, in.consecutiveHighYield);
        Plan.Decision d = Plan.evaluate(in);
        assertTrue(d.restartsYield, d.rule);
        assertTrue(Mint.commitYield(st, GI, d, now));
        assertEquals(now, st.yieldSinceMs);
        TrainerTab.fillGirthInputs(m, GI, st, 13, now, in);
        assertEquals(0, in.consecutiveHighYield, "three new readings before it is asked again");
        YieldWindowTest.readings(m, 8, 13.0, 3);
        TrainerTab.fillGirthInputs(m, GI, st, 13, PressureClockTest.at(15, 8), in);
        assertEquals(3, in.consecutiveHighYield);
    }
}
