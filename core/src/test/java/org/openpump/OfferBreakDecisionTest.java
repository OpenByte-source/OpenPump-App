package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * t10 R-64 - THE DECISION CARDS' KINDS: which card a decision from lanes B and C draws (the
 * offer of a break, the length reading that fell, the 90-minute hold), the buttons each
 * offers, and the girth offer's length focus pausing girth.
 */
class OfferBreakDecisionTest {

    private static Plan.Decision d(int action, String rule) {
        return new Plan.Decision(action, Plan.TAG_INFERRED, rule, "why", Double.NaN, 0);
    }

    @Test void eachNewDecisionIsKnownByItsKindAndRule() {
        assertTrue(PlanCards.isOfferBreak(d(Plan.ACTION_OFFER_BREAK, Plan.YIELD_OFFER_RULE)));
        assertTrue(PlanCards.isOfferBreak(d(Plan.ACTION_OFFER_BREAK,
            Plan.LENGTH_STILL_UNDER_RULE)));
        assertFalse(PlanCards.isOfferBreak(d(Plan.ACTION_DELOAD, Plan.LENGTH_FELL_RULE)));
        assertTrue(PlanCards.isLengthFell(d(Plan.ACTION_DELOAD, Plan.LENGTH_FELL_RULE)));
        assertFalse(PlanCards.isLengthFell(d(Plan.ACTION_DELOAD, "deload due")),
            "the regular deload keeps its own card");
        assertTrue(PlanCards.isHeldAt90(d(Plan.ACTION_HOLD, Plan.HELD_AT_90_RULE)));
        assertFalse(PlanCards.isHeldAt90(d(Plan.ACTION_HOLD, "no change")));
        // Lane B's girth hold names the step it held after the id.
        assertTrue(PlanCards.isHeldAt90(d(Plan.ACTION_HOLD,
            Plan.HELD_AT_90_RULE + "; " + Plan.LOW_YIELD_RULE)));
        assertFalse(PlanCards.isHeldAt90(null));
    }

    @Test void theButtonsAreTheDecidedWords() {
        assertEquals("Take a week off", PlanCards.TAKE_WEEK_OFF);
        assertEquals("4 weeks of length focus", PlanCards.LENGTH_FOCUS);
        assertEquals("8-week girth block", PlanCards.GIRTH_BLOCK);
        // Polish TR-25 / TR-26.
        assertEquals("Start an 8-week girth block", PlanCards.girthBlockStart("Start"));
        assertEquals("A week off, or length focus?", PlanCards.offerTitle(true));
        assertEquals("Time for a week off?", PlanCards.offerTitle(false));
        assertEquals("Not now", PlanCards.NOT_NOW);
        assertEquals("Start the week off", PlanCards.START_WEEK_OFF);
        assertEquals("Switch to alternate days", PlanCards.HELD90_SWITCH);
        assertEquals("Keep as is", PlanCards.HELD90_KEEP);
        assertEquals("Your both-tracks day would pass 90 min with more holds, so the plan "
            + "holds here. Alternate days keep each session near an hour.",
            PlanCards.heldAt90Text(Plan.TRACK_GIRTH_INTERVAL));
        assertTrue(PlanCards.heldAt90Text(Plan.TRACK_LENGTH).contains("more strain sets"));
    }

    @Test void lengthFocusPausesGirthForFourWeeks() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.OCTOBER, 1, 12, 0, 0);
        long now = c.getTimeInMillis();
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        assertFalse(TrainerTab.girthPausedNow(m, now));
        m.trainerGirth.restUntilMs = now + PlanCards.LENGTH_FOCUS_WEEKS * 7L * 86400000L;
        assertTrue(TrainerTab.girthPausedNow(m, now), "girth paused");
        assertTrue(TrainerTab.girthPausedNow(m, now + 27L * 86400000L));
        assertFalse(TrainerTab.girthPausedNow(m, now + 29L * 86400000L), "four weeks, then back");
        assertTrue(PlanCards.lengthFocusTaken("Thu 29 Oct").startsWith("Girth paused for 4 weeks"));
    }
}
