package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R-26 (fix d; APP-FIXES 12 and 13, the owner's decisions, 2026-10-01): ONE VOLUME CHANGE A
 * MORNING. Sets carried into Level 3 or 4 are capped at the new level's top on entry; a
 * level-up and a yield or fallback step never land on the same morning (the step waits for
 * the next eligible one, and the crossing does not restart the fallback's count); and neither
 * the yield add nor the fallback lands on the return days after a deload. With "Same days,
 * stop growing at 90 min" a volume step that would take the both-tracks day past 90 minutes
 * holds (R-60), while a pressure step that is due still comes.
 */
class OneVolumeChangeTest {

    static final int GI = Plan.TRACK_GIRTH_INTERVAL;

    @Test void setsCarriedIntoALevelStopAtItsTop() {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L2; st.weekIndex = 15; st.yieldSets = 1;      // row 15: 15 sets + 1
        assertEquals(16, Mint.totalSets(GI, st));
        Mint.crossGirthLevel(st, GI, Plan.L3, 500L);
        assertEquals(14, Mint.totalSets(GI, st), "16 -> Level 3's top of 14");
        assertEquals(14, st.carriedSets);
        assertEquals(0, st.yieldSets);
        st.carriedSets = 18; st.yieldSets = 2;                        // 20 at Level 3 (old file)
        Mint.crossGirthLevel(st, GI, Plan.L4, 900L);
        assertEquals(18, Mint.totalSets(GI, st), "20 -> Level 4's top of 18");
    }

    /** Tue / Thu / Sat girth sessions for `weeks` weeks from the model's first Monday. */
    static void sessions(Model m, int weeks) {
        int[] days = { 1, 3, 5 };
        for (int w = 0; w < weeks; w++)
            for (int d = 0; d < days.length; d++)
                PressureClockTest.file(m, "g", PressureClockTest.at(7 * w + days[d], 9),
                    28, 28, 30);
    }

    @Test void aLevelUpAndAFallbackStepNeverShareAMorning() {
        Model m = PressureClockTest.model();
        sessions(m, 5);
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3; st.carriedSets = 14;
        long crossing = PressureClockTest.at(35, 8);                  // week 5's Monday
        Mint.crossGirthLevel(st, GI, Plan.L4, crossing);
        assertEquals(14, Mint.totalSets(GI, st));

        Plan.Inputs in = YieldWindowTest.girth(Plan.L4, 14);
        in.monthIndex = 13;
        in.hasYieldData = false;
        TrainerTab.fillGirthInputs(m, GI, st, 13, crossing + 3600000L, in);
        assertTrue(in.levelUpToday);
        assertTrue(in.weeksWithoutReadings >= Plan.NO_READINGS_WEEKS,
            "the crossing does not restart the fallback's count: " + in.weeksWithoutReadings);
        assertFalse(Plan.evaluate(in).action == Plan.ACTION_ADD_VOLUME, "not on the crossing morning");

        long next = PressureClockTest.at(36, 8);
        TrainerTab.fillGirthInputs(m, GI, st, 13, next, in);
        assertFalse(in.levelUpToday);
        Plan.Decision add = Plan.evaluate(in);
        assertEquals(Plan.ACTION_ADD_VOLUME, add.action, "the next eligible morning: " + add.rule);
        assertEquals(2, add.setsDelta);
        assertTrue(Mint.commitYield(st, GI, add, next));
        assertEquals(16, Mint.totalSets(GI, st));
    }

    @Test void neverOnAReturnDay() {
        Plan.Inputs none = YieldWindowTest.girth(Plan.L3, 10);
        none.hasYieldData = false;
        none.weeksWithoutReadings = Plan.NO_READINGS_WEEKS;
        assertEquals(Plan.ACTION_ADD_VOLUME, Plan.evaluate(none).action);
        none.gentleReturnOpen = true;
        assertFalse(Plan.evaluate(none).action == Plan.ACTION_ADD_VOLUME, "return day 1");
        none.gentleReturnOpen = false;
        none.returnRunsUnder = true;
        assertFalse(Plan.evaluate(none).action == Plan.ACTION_ADD_VOLUME, "return day 2");

        Plan.Inputs low = YieldWindowTest.girth(Plan.L3, 10);
        low.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        low.gentleReturnOpen = true;
        assertFalse(Plan.evaluate(low).action == Plan.ACTION_ADD_VOLUME);
        low.gentleReturnOpen = false;
        low.levelUpToday = true;
        assertFalse(Plan.evaluate(low).action == Plan.ACTION_ADD_VOLUME);
    }

    @Test void theNinetyMinuteDayHoldsTheVolumeNotThePressure() {
        Plan.Inputs low = YieldWindowTest.girth(Plan.L3, 10);
        low.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        low.heldAt90 = true;
        Plan.Decision held = Plan.evaluate(low);
        assertEquals(Plan.ACTION_HOLD, held.action);
        assertTrue(Plan.isHeldAt90(held), held.rule);
        assertTrue(held.rule.startsWith(Plan.HELD_AT_90_RULE));
        assertEquals(Plan.HELD_AT_90_WORDS, held.reason);
        low.netTupMin = Plan.netMilestoneMin(Plan.L3);
        low.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(low).action);
        // The fallback holds the same way.
        Plan.Inputs none = YieldWindowTest.girth(Plan.L3, 10);
        none.hasYieldData = false;
        none.weeksWithoutReadings = Plan.NO_READINGS_WEEKS;
        none.heldAt90 = true;
        assertTrue(Plan.isHeldAt90(Plan.evaluate(none)));
    }
}
