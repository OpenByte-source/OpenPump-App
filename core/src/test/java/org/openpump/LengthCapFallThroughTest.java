package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * t10 review C-F2 (audit A10) - A WEEK UNDER 2 % AT 12 STRAIN SETS AND THE LOAD AT ITS CAP DOES
 * NOT STARVE THE MONTH GATES. Option D's "never reached" branch has nothing left to add there,
 * and it used to answer the morning with a hold - every morning a low reading a week old was
 * showing - so a low responder who reached 12 sets and 12 lb before month 12 was never offered
 * the L3 -> L4 gate or the month-12 fork while they kept measuring. It now falls through: the
 * later rungs (the month gate, the climb) still run, and the hold is said only when none of them
 * has anything.
 */
class LengthCapFallThroughTest {

    /** 12 strain sets, the load on the 12 lb cap, a reading of 1.5 % a week old - this block
     *  had its D2 step and its offer already, so only D2's "nothing left to add" is left. */
    private static Plan.Inputs atTheCap(int level, int month) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = level;
        in.monthIndex = month;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.handedOver = true;
        in.strainSets = Plan.LENGTH_STRAIN_SETS_MAX;
        in.loadLb = Plan.LENGTH_LOAD_MAX_LB;
        in.strainPct = 1.5;
        in.strainMissDays = 8;
        in.blockHadReachLo = false;
        in.dAddedInBlock = true;
        in.dOfferedInBlock = true;
        in.pressureKpa = Plan.LENGTH_SOFT_CAP_HI_KPA;
        return in;
    }

    @Test void monthTwelveAtTheCapIsOfferedTheLevelUp() {
        Plan.Decision d = Plan.evaluate(atTheCap(Plan.L3, 12));
        assertEquals(Plan.ACTION_LEVEL_UP, d.action, d.rule);
        assertEquals("length L3→L4 gate met: month 12 reached -> move to Level 4, OR take a "
            + "girth block or weeks off length", d.rule);
    }

    @Test void monthSixAtTheCapIsOfferedTheLevelUpToo() {
        Plan.Decision d = Plan.evaluate(atTheCap(Plan.L2, 6));
        assertEquals(Plan.ACTION_LEVEL_UP, d.action, d.rule);
    }

    @Test void withNoGateDueTheCapStillSaysItHolds() {
        Plan.Decision d = Plan.evaluate(atTheCap(Plan.L3, 9));
        assertEquals(Plan.ACTION_HOLD, d.action);
        assertEquals("length load is at the cap that binds first", d.rule);
        // Lane U: the plan's own limit is 15 lb (12 is only warned once before month 12), so
        // the words name where the plan's load steps stop, not "its cap".
        assertEquals("sets are at " + Plan.LENGTH_STRAIN_SETS_MAX + " and the plan's own load "
            + "steps stop at " + Traction.settingLb(Plan.loadCapLb(atTheCap(Plan.L3, 9)))
            + "; the plan holds here", d.reason);   // polish: a sentence, not "- hold"
    }

    @Test void andTheMonthlyClimbStillRunsAtTheCap() {
        Plan.Inputs in = atTheCap(Plan.L3, 9);
        in.pressureKpa = 30;                 // under the usual top, a whole month on it
        in.trainingWeeksAtPressure = 1;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action, d.rule);
    }
}
