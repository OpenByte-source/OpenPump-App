package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * THE MONTH THE PLAN IS IN — {@link TrainerTab#monthIndexNow}.
 *
 * Reported from a device: six months entered in setup, and the app went on saying "first
 * month" and holding the prescription at the beginner's cap. The wizard's answer shaped the
 * starting level and then went nowhere, so every gate, cap and header afterwards read
 * months-since-enrolment, which is zero on the day you enrol.
 */
class TrainerMonthTest {

    /** A calendar month with a day's headroom: monthsElapsed floors against 30.44 days,
     *  so a truncated 30.44 lands just short of the boundary it is meant to cross. */
    private static final long MONTH = 31L * 24 * 60 * 60 * 1000;

    @Test
    void theAnswerCountsFromTheDayItWasGiven() {
        Model m = new Model();
        long now = 1_700_000_000_000L;
        m.trainerEnrolledAt = now;
        m.trainerMonthsPumping = 6;
        m.trainerMonthsAt = now;
        assertEquals(6, TrainerTab.monthIndexNow(m, now), "six months answered is month six");
        assertEquals(8, TrainerTab.monthIndexNow(m, now + 2 * MONTH),
            "and two months of training later, month eight");
    }

    @Test
    void anOldSaveKeepsTheBehaviourItHad() {
        Model m = new Model();
        long now = 1_700_000_000_000L;
        m.trainerEnrolledAt = now - 3 * MONTH;   // enrolled three months ago
        m.trainerMonthsPumping = 0;              // the answer was never kept
        m.trainerMonthsAt = 0L;
        assertEquals(3, TrainerTab.monthIndexNow(m, now),
            "falls back to months since enrolment, exactly as before");
    }

    @Test
    void aBeginnerEnrollingTodayIsStillInTheirFirstMonth() {
        Model m = new Model();
        long now = 1_700_000_000_000L;
        m.trainerEnrolledAt = now;
        m.trainerMonthsPumping = 0;
        m.trainerMonthsAt = now;
        assertEquals(0, TrainerTab.monthIndexNow(m, now));
        assertEquals(0, Plan.monthIndex(0), "and the month-1 cap still applies to them");
    }

    @Test
    void theAnswerSurvivesASaveAndReload() {
        Model m = new Model();
        m.trainerMonthsPumping = 6;
        m.trainerMonthsAt = 1_700_000_000_000L;
        Model back = Model.fromJson(m.toJson());
        assertEquals(6, back.trainerMonthsPumping, "months pumping survives the round trip");
        assertEquals(1_700_000_000_000L, back.trainerMonthsAt, "and the date it was given");
    }
}
