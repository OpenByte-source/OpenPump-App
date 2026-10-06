package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 parity run 2, O-1: ONE GIRTH CHANGE A MORNING, IN THE LIVE APP TOO. The engine answers one
 * decision an evaluation, but every Trainer render evaluates again - so an add (or a cut, the
 * fallback, the time cap's conversion) applied on one render was followed by the pressure step
 * on the next, the same morning. The day the plan last changed the girth work is kept
 * (TrainerTrackState#planChangeMs), and the next change waits for the next girth morning. An
 * offer the person answered is not a change of the work: that morning's pressure step may land.
 */
class OneGirthChangeAMorningTest {

    private static final long HOUR = 3600000L, DAY = 24 * HOUR;

    /** L3 interval at 30 kPa: three weeks at it, the milestone held - the pressure step is due. */
    private static Plan.Inputs stepDue() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.weekIndex = 1;
        in.ceilKpa = 43;
        in.pressureKpa = 30;
        in.firstDeloadPending = false;
        in.netTupMin = 30.0;
        in.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        return in;
    }

    private static Model girth(long now) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = now - 220L * DAY;
        m.trainerGirth.level = Plan.L3;
        m.trainerGirth.pressureKpa = 30;
        return m;
    }

    private static Plan.Decision add() {
        return new Plan.Decision(Plan.ACTION_ADD_VOLUME, Plan.TAG_SOURCE, Plan.LOW_YIELD_RULE,
            "debounced low yield", Double.NaN, 2);
    }

    @Test void theStepWaitsForTheMorningAfterAChange() {
        Plan.Inputs in = stepDue();
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(in).action, "due");
        in.girthChangedToday = true;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action, "the work changed this morning: " + d.rule);
        assertEquals(Plan.GIRTH_ONE_CHANGE_RULE, d.rule);
    }

    @Test void anAppliedAddMarksTheMorning() {
        long now = System.currentTimeMillis();
        Model m = girth(now);
        Model.TrainerTrackState st = m.trainerGirth;
        assertTrue(Mint.commitYield(st, Plan.TRACK_GIRTH_INTERVAL, add(), now));
        assertEquals(now, st.planChangeMs, "the add reached the routine");
        Plan.Inputs again = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 7, now + 60000L, again);
        assertTrue(again.girthChangedToday, "re-asked the same morning");
        Plan.Inputs next = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 7, now + DAY, next);
        assertFalse(next.girthChangedToday, "the next girth morning");
    }

    @Test void aPressureStepMarksTheMorningToo() {
        long now = System.currentTimeMillis();
        Model m = girth(now);
        Plan.Decision step = Plan.evaluate(stepDue());
        assertEquals(Plan.ACTION_RAISE_PRESSURE, step.action);
        Mint.commitYield(m.trainerGirth, Plan.TRACK_GIRTH_INTERVAL, step, now);
        assertEquals(now, m.trainerGirth.planChangeMs);
    }

    @Test void anAnsweredOfferLetsTheMorningsStepLand() {
        long now = System.currentTimeMillis();
        Model m = girth(now);
        m.trainerGirth.addPending = true;
        Mint.commitYield(m.trainerGirth, Plan.TRACK_GIRTH_INTERVAL, Plan.offerBreak(false, false),
                         now);
        assertEquals(0L, m.trainerGirth.planChangeMs, "the person's answer, not a change");
        Plan.Inputs again = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 7, now, again);
        assertFalse(again.girthChangedToday);
    }

    @Test void aHoldIsNotAChange() {
        long now = System.currentTimeMillis();
        Model m = girth(now);
        Plan.Inputs in = stepDue();
        in.trainingWeeksAtPressure = 1;
        Plan.Decision hold = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, hold.action);
        Mint.commitYield(m.trainerGirth, Plan.TRACK_GIRTH_INTERVAL, hold, now);
        assertEquals(0L, m.trainerGirth.planChangeMs);
    }

    @Test void anOfferIsNotHeldByIt() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = Plan.L4;
        in.monthIndex = 14;
        in.weekIndex = 1;
        in.ceilKpa = 43;
        in.pressureKpa = 34;
        in.firstDeloadPending = false;
        in.hasYieldData = true;
        in.consecutiveLowYield = 3;
        in.addPending = true;
        in.workSets = Plan.GIRTH_INTERVAL_L4_TOP_SETS;
        in.girthChangedToday = true;
        assertEquals(Plan.ACTION_OFFER_BREAK, Plan.evaluate(in).action,
            "the offer is the person's card, not a change of the work");
        assertNotEquals(Plan.GIRTH_ONE_CHANGE_RULE, Plan.evaluate(in).rule);
    }
}
