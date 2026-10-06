package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * SETUP PLACES THE LENGTH LEVEL FROM THE MONTHS ANSWER (TrainerTab#deriveLength), by the
 * length track's own calendar gates - months 3, 6 and 12 (Plan#lengthGateMetNextLevel).
 *
 * Reported from a device: seven months of pumping, length on - and setup put length at Level
 * 1, so the first thing the Trainer did was ask "month 3 reached - move up to Level 2", and the
 * closing "Save your routines" sheet said "The Trainer has a question for this track first
 * (level up)" where a Length routine should have been.
 */
class LengthPlacementTest {

    static final long NOW = 1_780_000_000_000L;
    static final long MONTH = 31L * 24 * 60 * 60 * 1000;

    static int placed(int months) {
        return TrainerTab.deriveLength(months, 34, false, 43, 41).level;
    }

    @Test void theMonthsAnswerPlacesTheLengthLevelByItsOwnGates() {
        assertEquals(Plan.L1, placed(0));
        assertEquals(Plan.L1, placed(2));
        assertEquals(Plan.L2, placed(3), "month 3 is the first gate");
        assertEquals(Plan.L2, placed(5));
        assertEquals(Plan.L3, placed(6), "month 6 the second");
        assertEquals(Plan.L3, placed(7), "the owner's seven months");
        assertEquals(Plan.L3, placed(11));
        assertEquals(Plan.L4, placed(12), "at month 12, Level 4 - as girth is placed there");
        assertEquals(Plan.L4, placed(40));
        assertEquals(Plan.L1, TrainerTab.deriveLength(0, 17).level, "a beginner starts at 1");
        assertEquals(Plan.L3, TrainerTab.deriveLength(7, 34, true, 43, 0).level,
            "new to pumping changes the caps, not the calendar");
    }

    @Test void thePlacementIsTheLevelTheGatesWouldHaveReached() {
        for (int mo = 0; mo <= 30; mo++) {
            int level = placed(mo);
            assertEquals(0, Plan.lengthGateMetNextLevel(level, mo),
                "month " + mo + ": no gate is left open behind the placement");
        }
    }

    /** The length track's engine inputs on the day of setup, as SessionActivity builds them
     *  for a track with no readings yet. */
    static Plan.Decision evaluateAt(int level, int month, int fit) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = level;
        in.monthIndex = month;
        in.pressureKpa = 34;
        in.ceilKpa = 43;
        in.fitState = fit;
        in.hasYieldData = false;
        in.strainPct = Double.NaN;
        in.fatiguePct = Double.NaN;
        return Plan.evaluate(in);
    }

    @Test void sevenMonthsOffersALengthRoutineNotALevelUp() {
        Model m = new Model();
        m.trainerMonthsPumping = 7;
        m.trainerMonthsAt = NOW;
        m.trainerEnrolledAt = NOW;
        int month = TrainerTab.monthIndexNow(m, NOW);
        int level = TrainerTab.deriveLength(7, 34, false, 43, 41).level;
        int[] fits = { Traction.FIT_UNKNOWN, Traction.FIT_TRACTION };
        for (int i = 0; i < fits.length; i++) {
            Plan.Decision was = evaluateAt(Plan.L1, month, fits[i]);
            assertEquals(Plan.ACTION_LEVEL_UP, was.action, "the reported defect, at Level 1");
            assertEquals(TrainerOnboard.ROW_ASKS,
                TrainerOnboard.rowState(Plan.TRACK_LENGTH, was.action, true, false, false));

            Plan.Decision d = evaluateAt(level, month, fits[i]);
            assertNotEquals(Plan.ACTION_LEVEL_UP, d.action, d.rule);
            assertEquals(TrainerOnboard.ROW_SAVE,
                TrainerOnboard.rowState(Plan.TRACK_LENGTH, d.action, true, false, false),
                "the save sheet offers the Length routine: " + d.rule);
        }
    }

    @Test void theNextGateFallsAtMonthTwelve() {
        Model m = new Model();
        m.trainerMonthsPumping = 7;
        m.trainerMonthsAt = NOW;
        m.trainerEnrolledAt = NOW;
        int level = TrainerTab.deriveLength(7, 34, false, 43, 41).level;
        int at11 = TrainerTab.monthIndexNow(m, NOW + 4 * MONTH);
        int at12 = TrainerTab.monthIndexNow(m, NOW + 5 * MONTH);
        assertEquals(11, at11);
        assertEquals(12, at12);
        assertNotEquals(Plan.ACTION_LEVEL_UP,
            evaluateAt(level, at11, Traction.FIT_TRACTION).action, "not before month 12");
        assertEquals(Plan.ACTION_LEVEL_UP,
            evaluateAt(level, at12, Traction.FIT_TRACTION).action, "the L3 -> L4 fork at 12");
    }
}
