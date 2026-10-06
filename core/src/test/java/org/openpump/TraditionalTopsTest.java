package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R-25 (A3/C7, the owner's decisions, 2026-10-01): TRADITIONAL GIRTH. Its yield rule opens at
 * Level 2 with the 6-12 % target and moves one hold at a time; its tops are 6 at Levels 1 and
 * 2, 7 at Level 3 and 9 at Level 4 (the interval top's minutes in 5-minute holds), and the top
 * stops the calendar too (K17: Level 3's growth to 8 stops at 7); with nothing measured for 4
 * training weeks at Levels 3 and 4 the plan adds a hold itself, then waits 4. The half start
 * is unchanged.
 */
class TraditionalTopsTest {

    static final int T = Plan.TRACK_GIRTH_TRADITIONAL;

    static Plan.Inputs trad(int level, int workSets) {
        Plan.Inputs in = YieldWindowTest.girth(level, workSets);
        in.track = T;
        return in;
    }

    @Test void theTopsAndTheTier() {
        assertEquals(6, Plan.traditionalTop(Plan.L1));
        assertEquals(6, Plan.traditionalTop(Plan.L2));
        assertEquals(7, Plan.traditionalTop(Plan.L3));
        assertEquals(9, Plan.traditionalTop(Plan.L4));
        assertEquals(7, Plan.traditionalSets(Plan.L3, 99), "Level 3's calendar stops at 7");
        assertEquals(8, Plan.traditionalSets(Plan.L4, 1));
        assertEquals(3, Plan.traditionalHalfStart(Plan.L2), "the half start is unchanged");
        assertEquals(4, Plan.traditionalHalfStart(Plan.L3));
        assertEquals(4, Plan.traditionalHalfStart(Plan.L4));
        assertFalse(Plan.yieldTierOpen(T, Plan.L1, 1), "Level 1 grows by its calendar");
        assertTrue(Plan.yieldTierOpen(T, Plan.L2, 1));
        Plan.Inputs l1 = trad(Plan.L1, 4);
        l1.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        assertTrue(Plan.evaluate(l1).action != Plan.ACTION_ADD_VOLUME);
    }

    @Test void levelThreeAtFourPercentGrowsByOneThenStops() {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L3; st.weekIndex = 1; st.weekGrowth = true;
        Plan.Inputs in = trad(Plan.L3, Mint.totalSets(T, st));
        in.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        Plan.Decision add = Plan.evaluate(in);
        assertEquals(Plan.ACTION_ADD_VOLUME, add.action);
        assertEquals(1, add.setsDelta, "one hold");
        assertTrue(Mint.commitYield(st, T, add, 100L));
        assertEquals(7, Mint.totalSets(T, st), "6 -> 7");
        // At the top, with three more lows (the pending add cleared): the offer, not 8.
        Plan.Inputs top = trad(Plan.L3, Mint.totalSets(T, st));
        top.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        assertEquals(Plan.ACTION_OFFER_BREAK, Plan.evaluate(top).action);
        // ...and the calendar's own growth never passes it either.
        st.weekIndex = 12;
        assertEquals(7, Mint.totalSets(T, st));
    }

    @Test void levelFourWithNothingMeasured() {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L4; st.weekIndex = 1; st.weekGrowth = true;
        Plan.Inputs in = trad(Plan.L4, Mint.totalSets(T, st));
        in.hasYieldData = false;
        in.monthIndex = 13;
        in.weeksWithoutReadings = Plan.NO_READINGS_WEEKS;
        Plan.Decision add = Plan.evaluate(in);
        assertEquals(Plan.ACTION_ADD_VOLUME, add.action);
        assertEquals(1, add.setsDelta);
        assertTrue(Mint.commitYield(st, T, add, 100L));
        assertEquals(100L, st.yieldSinceMs, "the count waits another 4 weeks");
        assertEquals(9, Mint.totalSets(T, st), "8 -> 9, the top");
        in.workSets = Mint.totalSets(T, st);
        assertTrue(Plan.atVolumeTop(Plan.evaluate(in)), "then it holds at its top");
        // Not before month 6.
        Plan.Inputs early = trad(Plan.L3, 6);
        early.hasYieldData = false;
        early.monthIndex = 5;
        early.weeksWithoutReadings = 9;
        assertTrue(Plan.evaluate(early).action != Plan.ACTION_ADD_VOLUME);
    }

    @Test void whatRunsIsHeldToTheTimeCap() {
        // The owner as traditional: the plan writes 7 at Level 3 and 9 at Level 4; the
        // session runs 5 and 7 (R-27: 7 x 5 min + 7.5 = 42.5 <= 44).
        Model.TrainerTrackState l3 = new Model.TrainerTrackState();
        l3.level = Plan.L3; l3.weekIndex = 9; l3.weekGrowth = true;
        assertEquals(7, Mint.totalSets(T, l3));
        assertEquals(5, Mint.prescribe(T, Plan.L3, 9, 30, 7, 43, 0, 0, null).sets);
        Model.TrainerTrackState l4 = new Model.TrainerTrackState();
        l4.level = Plan.L4; l4.weekIndex = 1; l4.weekGrowth = true; l4.yieldSets = 1;
        assertEquals(9, Mint.totalSets(T, l4));
        Mint.Rx rx = Mint.prescribe(T, Plan.L4, 1, 37, 13, 43, 0, 1, null);
        assertEquals(7, rx.sets);
        assertTrue(7 * 5 + 7.5 <= Plan.r2CapMin(Plan.L4));
        // A crossing carries no more than the new level's top.
        Model.TrainerTrackState two = new Model.TrainerTrackState();
        two.level = Plan.L2; two.weekIndex = 1; two.weekGrowth = true; two.yieldSets = 3;
        assertEquals(6, Mint.totalSets(T, two), "Level 2's top of 6");
        Mint.crossGirthLevel(two, T, Plan.L3, 500L);
        assertEquals(6, Mint.totalSets(T, two));
    }
}
