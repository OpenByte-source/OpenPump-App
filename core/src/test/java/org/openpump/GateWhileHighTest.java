package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * t10 REAL-16 (parity round 2; C-F2's family): A HIGH LENGTH READING DOES NOT HOLD BACK THE
 * MONTH GATE. The strain-high rung (R-40 rung 4) answered every morning a reading over 6 % was
 * showing - "measure again", the floor's re-measure, or the week a cut waits - ahead of the
 * month gates, so somebody reading high at month 12 stayed at Level 3 for weeks after the
 * gate. The gate is now asked while a re-measure is pending; only an actual cut, ready to
 * take, answers the morning before it.
 */
class GateWhileHighTest {

    private static Plan.Inputs high(int level, int month) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = level;
        in.monthIndex = month;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.handedOver = true;
        in.strainSets = 6;
        in.loadLb = 11.0;
        in.pressureKpa = Plan.LENGTH_SOFT_CAP_HI_KPA;
        in.strainPct = 7.0;
        in.lastStrainHigh = true;
        return in;
    }

    @Test void aPendingReMeasureDoesNotHoldBackMonthTwelve() {
        Plan.Decision d = Plan.evaluate(high(Plan.L3, 12));
        assertEquals(Plan.ACTION_LEVEL_UP, d.action, d.rule);
    }

    @Test void norDoesTheWeekACutWaits() {
        Plan.Inputs in = high(Plan.L3, 12);
        in.strainHighConfirmed = true;
        in.daysSinceLastCut = 3;
        assertEquals(Plan.ACTION_LEVEL_UP, Plan.evaluate(in).action);
    }

    @Test void norTheFloorsReMeasure() {
        Plan.Inputs in = high(Plan.L2, 6);
        in.strainHighConfirmed = true;
        in.loadLb = Plan.LENGTH_LOAD_FLOOR_LB;
        assertEquals(Plan.ACTION_LEVEL_UP, Plan.evaluate(in).action);
    }

    @Test void aCutReadyToTakeStillComesFirst() {
        Plan.Inputs in = high(Plan.L3, 12);
        in.strainHighConfirmed = true;
        assertEquals(Plan.LENGTH_CUT_RULE, Plan.evaluate(in).rule);
    }

    @Test void withNoGateDueTheReMeasureIsTheAnswer() {
        Plan.Decision d = Plan.evaluate(high(Plan.L3, 9));
        assertEquals(Plan.ACTION_REMEASURE, d.action);
        assertEquals(Plan.LENGTH_HIGH_RULE, d.rule);
    }

    @Test void onAGentleReturnDayTheReMeasureStaysTheAnswer() {
        Plan.Inputs in = high(Plan.L3, 12);
        in.returnRunsUnder = true;
        assertEquals(Plan.LENGTH_HIGH_RULE, Plan.evaluate(in).rule);
    }
}
