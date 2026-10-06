package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R-23 (C4/A4, the owner's decision, 2026-10-01): AFTER A LOW-YIELD ADD THAT DOES NOT HELP, THE
 * PLAN DOES NOT ADD AGAIN. Three more readings under 6 % - or a low step due with the holds at
 * their top - and it offers the guidance's next step as a decision: a week off, or 4 weeks of
 * length focus. The pending add is cleared by a reading inside the target, a level-up, or the
 * offer's answer (any answer).
 */
class OfferBreakGirthTest {

    static final int GI = Plan.TRACK_GIRTH_INTERVAL;

    static Plan.Inputs low(int level, int workSets) {
        Plan.Inputs in = YieldWindowTest.girth(level, workSets);
        in.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        return in;
    }

    @Test void anAddThatDidNotHelpIsOfferedNotRepeated() {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L3; st.carriedSets = 10;
        Plan.Decision add = Plan.evaluate(low(Plan.L3, 10));
        assertEquals(Plan.ACTION_ADD_VOLUME, add.action);
        assertTrue(Mint.commitYield(st, GI, add, 100L));
        assertEquals(12, Mint.totalSets(GI, st), "10 -> 12");
        assertTrue(st.addPending, "the add waits to be judged");

        Plan.Inputs again = low(Plan.L3, 12);
        again.addPending = st.addPending;
        Plan.Decision offer = Plan.evaluate(again);
        assertEquals(Plan.ACTION_OFFER_BREAK, offer.action, "not 14");
        assertEquals(Plan.YIELD_OFFER_RULE, offer.rule);
        assertEquals(Plan.YIELD_OFFER_WORDS, offer.reason);
        assertEquals(0, offer.setsDelta);
        assertEquals(12, Mint.prescribe(GI, Plan.L3, 0, 30, 7, 43, 10, st.yieldSets, offer).sets,
            "nothing in the prescription moves");

        // Answered, whichever way: the pending add goes and the streak restarts.
        assertTrue(Mint.commitYield(st, GI, offer, 200L));
        assertFalse(st.addPending);
        assertEquals(200L, st.yieldSinceMs);
        st.addPending = true;
        assertTrue(Mint.answerYieldOffer(st, 300L));
        assertFalse(st.addPending);
        assertEquals(300L, st.yieldSinceMs);
    }

    @Test void atTheTopTheLowStepIsTheOffer() {
        Plan.Decision top = Plan.evaluate(low(Plan.L3, 14));
        assertEquals(Plan.ACTION_OFFER_BREAK, top.action);
        assertEquals(Plan.YIELD_OFFER_TOP_WORDS, top.reason);
        assertEquals(Plan.ACTION_OFFER_BREAK, Plan.evaluate(low(Plan.L4, 18)).action);
    }

    @Test void aReadingInsideTheTargetClearsThePendingAdd() {
        Model m = PressureClockTest.model();
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3; st.carriedSets = 10; st.yieldSets = 2;
        st.addPending = true;
        st.yieldSinceMs = PressureClockTest.at(0, 6);
        YieldWindowTest.readings(m, 0, 4.0, 3);
        Plan.Inputs in = YieldWindowTest.girth(Plan.L3, 12);
        TrainerTab.fillGirthInputs(m, GI, st, 7, PressureClockTest.at(7, 8), in);
        assertTrue(in.addPending);
        assertEquals(Plan.ACTION_OFFER_BREAK, Plan.evaluate(in).action);

        Model w = PressureClockTest.model();
        Model.TrainerTrackState sw = w.trainerGirth;
        sw.level = Plan.L3; sw.carriedSets = 10; sw.yieldSets = 2;
        sw.addPending = true;
        sw.yieldSinceMs = PressureClockTest.at(0, 6);
        YieldWindowTest.readings(w, 0, 7.0, 1);          // 7 %: inside the target
        YieldWindowTest.readings(w, 2, 4.0, 3);
        Plan.Inputs win = YieldWindowTest.girth(Plan.L3, 12);
        TrainerTab.fillGirthInputs(w, GI, sw, 7, PressureClockTest.at(9, 8), win);
        assertEquals(3, win.consecutiveLowYield);
        assertFalse(win.addPending, "the 7 % reading in between cleared it");
        Plan.Decision add = Plan.evaluate(win);
        assertEquals(Plan.ACTION_ADD_VOLUME, add.action);
        assertEquals(2, add.setsDelta, "12 -> 14");
    }

    @Test void aLevelUpClearsIt() {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L3; st.carriedSets = 12; st.addPending = true;
        Mint.crossGirthLevel(st, GI, Plan.L4, 500L);
        assertFalse(st.addPending);
    }
}
