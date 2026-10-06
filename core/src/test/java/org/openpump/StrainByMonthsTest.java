package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 R-45 (L1): STARTING STRAIN SETS BY MONTHS. Before month index 3 the calendar rung - 2
 * sets, +1 every 3 length training weeks; from month index 3 the guidance's table's 6: at setup
 * for anybody past it, and at the month-3 hand-over for anybody who reaches it (one change that
 * morning; with "stop growing at 90 min" it waits like any volume step). Readings take it on to
 * 12 from there.
 */
class StrainByMonthsTest {

    @Test void theSevenMonthOwnerStartsAtSix() {
        Model m = new LengthYear().setup().m;
        assertEquals(6, m.trainerLength.strainSets);
        assertTrue(m.trainerLength.handedOver);
        Model early = new Model();
        early.trainerMonthsPumping = 2;
        early.trainerMonthsAt = 1000L;
        LengthTrack.atSetup(early, 2000L);
        assertEquals(2, early.trainerLength.strainSets);
        assertFalse(early.trainerLength.handedOver);
    }

    private static LengthYear starter(int months, boolean isNew, double kpa) {
        LengthYear y = new LengthYear();
        y.months = months;
        y.isNew = isNew;
        y.lengthPlanKpa = kpa;
        y.lengthOffsetKpa = 0;
        y.mostKpa = 0;
        y.startLoadLb = Plan.LENGTH_LOAD_START_LB;
        return y.setup().run();
    }

    @Test void theTwoMonthStarterIsHandedOverInWeekSix() {
        LengthYear y = starter(2, false, 27);
        assertEquals(2, y.sets[1]);
        assertEquals(3, y.sets[4], "the calendar's set in week 4\n" + y.trace());
        assertEquals(6, y.sets[6], "month 4: the guidance's table, in week 6");
        assertTrue(y.trace().contains("w6 strain 3->6 (" + Plan.LENGTH_HANDOVER_RULE + ")"),
            y.trace());
        assertTrue(y.m.trainerLength.handedOver);
        for (int w = 6; w <= 52; w++) assertEquals(6, y.sets[w], "no readings: 6 all year");
    }

    @Test void newToPumpingClimbsTheCalendarThenIsHandedOver() {
        LengthYear y = starter(0, true, 21);
        assertEquals(3, y.sets[4], y.trace());
        assertEquals(4, y.sets[8]);
        assertEquals(5, y.sets[12]);
        assertEquals(5, y.sets[13]);
        assertEquals(6, y.sets[14], "6 at week 14");
    }

    private static Plan.Inputs monthThree(int sets) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L2;
        in.monthIndex = 3;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.strainSets = sets;
        in.handedOver = false;
        return in;
    }

    @Test void theHandOverIsOneStepToSix() {
        Plan.Decision d = Plan.evaluate(monthThree(3));
        assertEquals(Plan.ACTION_ADD_VOLUME, d.action);
        assertEquals(Plan.LENGTH_HANDOVER_RULE, d.rule);
        assertEquals(3, d.setsDelta, "3 -> 6 in one change");
        Plan.Inputs held = monthThree(3);
        held.heldAt90 = true;
        Plan.Decision h = Plan.evaluate(held);
        assertEquals(Plan.ACTION_HOLD, h.action, "stop growing at 90 min: it waits");
        assertEquals(Plan.HELD_AT_90_RULE, h.rule);
        Plan.Inputs done = monthThree(3);
        done.handedOver = true;                        // an upgrader, marked by the migration
        assertFalse(Plan.evaluate(done).action == Plan.ACTION_ADD_VOLUME,
            "an upgrader is not stepped by surprise");
        assertFalse(Plan.evaluate(monthThree(6)).action == Plan.ACTION_ADD_VOLUME,
            "already at 6");
    }

    @Test void acceptingItMarksTheTrackHandedOver() {
        Model m = new Model();
        m.trainerLength.strainSets = 3;
        LengthTrack.acceptSets(m, Plan.LENGTH_HANDOVER_RULE, 6, 5000L);
        assertEquals(6, m.trainerLength.strainSets);
        assertTrue(m.trainerLength.handedOver);
        assertEquals(5000L, m.trainerLength.strainSinceMs, "new work, read afresh");
    }
}
