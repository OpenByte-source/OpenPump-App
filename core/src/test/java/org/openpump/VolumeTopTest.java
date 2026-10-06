package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * G4 (the owner's decision, 2026-10-01): VOLUME TOPS PER LEVEL. Interval girth runs at most
 * 14 work holds at Level 3 and 18 at Level 4 (counted as Mint counts them, carried and yield
 * sets included); the length track at most 12 strain sets. Every volume step stops at the
 * top, and the hold that follows says the volume is at the level's top.
 */
class VolumeTopTest {

    @Test void theTopsAreNamedAndReadInOnePlace() {
        assertEquals(14, Plan.GIRTH_INTERVAL_L3_TOP_SETS);
        assertEquals(18, Plan.GIRTH_INTERVAL_L4_TOP_SETS);
        assertEquals(Plan.LENGTH_STRAIN_SETS_MAX, Plan.LENGTH_TOP_STRAIN_SETS);
        assertEquals(14, Plan.volumeTopSets(Plan.TRACK_GIRTH_INTERVAL, Plan.L3));
        assertEquals(18, Plan.volumeTopSets(Plan.TRACK_GIRTH_INTERVAL, Plan.L4));
        assertEquals(12, Plan.volumeTopSets(Plan.TRACK_LENGTH, Plan.L3));
        assertEquals(Integer.MAX_VALUE, Plan.volumeTopSets(Plan.TRACK_GIRTH_INTERVAL, Plan.L2),
            "Levels 1 and 2 follow their week tables");
        // EXPECTATION CHANGED (R-25, A3: traditional has tops - 6, 6, 7, 9).
        assertEquals(6, Plan.volumeTopSets(Plan.TRACK_GIRTH_TRADITIONAL, Plan.L1));
        assertEquals(6, Plan.volumeTopSets(Plan.TRACK_GIRTH_TRADITIONAL, Plan.L2));
        assertEquals(7, Plan.volumeTopSets(Plan.TRACK_GIRTH_TRADITIONAL, Plan.L3));
        assertEquals(9, Plan.volumeTopSets(Plan.TRACK_GIRTH_TRADITIONAL, Plan.L4));
    }

    @Test void aStepStopsAtTheTop() {
        assertEquals(2, Plan.volumeStep(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 10, 2));
        assertEquals(1, Plan.volumeStep(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 13, 2),
            "the last step lands on the top");
        assertEquals(0, Plan.volumeStep(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 14, 2));
        assertEquals(0, Plan.volumeStep(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 16, 2),
            "a count above the top gets nothing more, and is not cut");
        assertEquals(2, Plan.volumeStep(Plan.TRACK_GIRTH_INTERVAL, Plan.L4, 16, 2));
        assertEquals(0, Plan.volumeStep(Plan.TRACK_GIRTH_INTERVAL, Plan.L4, 18, 2));
        assertEquals(0, Plan.volumeStep(Plan.TRACK_LENGTH, Plan.L3, 12, 1));
    }

    private static Plan.Inputs lowYield(int level, int workSets) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = level;
        in.monthIndex = 7;
        in.pressureKpa = 30;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.hasYieldData = true;
        in.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        in.workSets = workSets;
        return in;
    }

    @Test void theYieldRuleStopsAtTheTopAndSaysSo() {
        Plan.Decision add = Plan.evaluate(lowYield(Plan.L3, 10));
        assertEquals(Plan.ACTION_ADD_VOLUME, add.action);
        assertEquals(2, add.setsDelta);
        assertEquals(1, Plan.evaluate(lowYield(Plan.L3, 13)).setsDelta);

        // EXPECTATION CHANGED (R-23, C4/A4: a low step due at the top is the offer - a week
        // off, or 4 weeks of length focus - not a bare hold).
        Plan.Decision top = Plan.evaluate(lowYield(Plan.L3, 14));
        assertEquals(Plan.ACTION_OFFER_BREAK, top.action);
        assertEquals(Plan.YIELD_OFFER_RULE, top.rule);
        assertEquals(Plan.YIELD_OFFER_TOP_WORDS, top.reason);

        assertEquals(Plan.ACTION_ADD_VOLUME, Plan.evaluate(lowYield(Plan.L4, 14)).action,
            "Level 4's top is 18");
        assertEquals(Plan.ACTION_OFFER_BREAK, Plan.evaluate(lowYield(Plan.L4, 18)).action);

        // The fallback at the top still holds and says so.
        Plan.Inputs none = lowYield(Plan.L3, 14);
        none.hasYieldData = false;
        none.consecutiveLowYield = 0;
        none.weeksWithoutReadings = Plan.NO_READINGS_WEEKS;
        Plan.Decision held = Plan.evaluate(none);
        assertTrue(Plan.atVolumeTop(held));
        assertTrue(held.reason.startsWith("The volume is at Level 3's top of 14 sets"),
            held.reason);

        // ...and there the pressure step still comes: only a hold carries the words.
        none.netTupMin = Plan.netMilestoneMin(Plan.L3);
        none.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        Plan.Decision up = Plan.evaluate(none);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, up.action);
        assertFalse(Plan.atVolumeTop(up));
    }

    @Test void theWorkSetsAreTheRoutinesOwnCount() {
        Model m = new Model();
        m.trainerEnrolled = true;
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3;
        st.yieldSets = 4;
        Plan.Inputs in = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 7, 1_000_000_000L, in);
        assertEquals(Mint.totalSets(Plan.TRACK_GIRTH_INTERVAL, st), in.workSets);
        assertEquals(14, in.workSets, "Level 3's 10 holds and the 4 yield kept");
        st.carriedSets = 12;
        st.yieldSets = 0;
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 7, 1_000_000_000L, in);
        assertEquals(12, in.workSets, "a carried count counts");
    }

    @Test void theLengthLadderReadsTheSameTop() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.ceilKpa = 43;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.loadLb = 8.0;
        in.firstDeloadPending = false;
        in.strainPct = 1.5;                       // t10 option D: under 2 %, never reached
        in.strainMissDays = Plan.LENGTH_MISS_DEBOUNCE_DAYS;
        in.strainSets = 11;
        assertEquals(Plan.ACTION_ADD_VOLUME, Plan.evaluate(in).action);
        in.strainSets = 12;
        assertEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(in).action,
            "at the top of 12 the ladder moves to the load, as it always did");
    }
}
