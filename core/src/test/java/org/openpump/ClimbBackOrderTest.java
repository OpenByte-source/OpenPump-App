package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Round 3 follow-up (parity smoke, brk_owner__under1): THE CLIMB BACK AFTER THE MONTH-12 BREAK,
 * IN THE EDITOR MODEL'S ORDER. The return day comes back at three quarters of the load (14.71 ->
 * 11.0, B4); after the gentle week the slow calendar step keeps its own 2-week clock (11.0 ->
 * 11.5, the "usual pace"), and a week under 2 % raises by HALF THE GAP to the target, rounded up
 * to 0.1 lb (11.5 -> 13.2, B5) - on separate mornings, one length change a morning (A-3). The
 * smoke run's "11.05 -> 11.55" was the slow step on its own morning (lSlow), not the half-gap
 * step, which came the next morning from 11.55 (the harness prints the pull at the whole kPa).
 */
class ClimbBackOrderTest {

    /** Length L3 after the gentle week: climbing back to 14.71 from `lb`, a week under 2 %, the
     *  slow step due on its own clock. */
    private static Plan.Inputs climbing(double lb) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = 13;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.handedOver = true;
        in.strainSets = 11;
        in.loadLb = lb;
        in.pressureKpa = Plan.LENGTH_SOFT_CAP_HI_KPA;
        in.lengthLoadMode = Model.LENGTH_LOAD_SLOW;
        in.slowLoadDue = true;
        in.climbTargetLb = 14.71;
        in.climbCapLb = 14.71;
        in.strainPct = 1.5;
        in.strainMissDays = Plan.LENGTH_MISS_DEBOUNCE_DAYS;
        return in;
    }

    @Test void theReturnDayLoadIsThreeQuarters() {
        assertEquals(11.0, MonthBreak.backLoadLb(14.71), 1e-9);
    }

    @Test void aWeekUnderRaisesByHalfTheGapRoundedUp() {
        Plan.Decision d = Plan.evaluate(climbing(11.5));
        assertEquals(MonthBreak.CLIMB_RULE, d.rule, "the half-gap step, not the +0.5 lb one");
        assertEquals(13.2, d.loadLb, 1e-9, "11.5 + ceil(3.21 / 2) = 13.2");
        assertEquals(12.9, Plan.evaluate(climbing(11.0)).loadLb, 1e-9, "11.0 + 1.9 = 12.9");
    }

    @Test void theSlowStepRunsOnItsOwnMorningAtTheUsualPace() {
        Plan.Inputs in = climbing(11.0);
        in.strainMissDays = 0;                        // no week under yet
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.LENGTH_SLOW_LOAD_RULE, d.rule);
        assertEquals(11.5, d.loadLb, 1e-9);
        // Taken this morning: the half-gap step, due the same morning, waits for the next.
        Plan.Inputs after = climbing(11.5);
        after.lengthChangedToday = true;
        assertEquals(Plan.LENGTH_ONE_CHANGE_RULE, Plan.evaluate(after).rule);
    }
}
