package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 R-41 (A1, the owner, 1 Oct 2026): A CUT MADE ON CONFIRMED HIGH STRAIN NEVER TAKES THE
 * LOAD UNDER 5 LB. A load already under it - the 2-month starter's 3 lb - is not cut at all; the
 * card asks for the measurement again, and rest if it stays high. Only this path has the 5 lb
 * floor: the absolute minimum everywhere else is still half a pound.
 */
class LoadFloorTest {

    /** The owner's length track at month 7 with a confirmed high reading, at `lb`. */
    private static Plan.Inputs confirmedHigh(double lb) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.pressureKpa = 33.86;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.loadLb = lb;
        in.strainSets = 6;
        in.strainPct = 8.0;
        in.lastStrainHigh = true;
        in.strainHighConfirmed = true;
        return in;
    }

    @Test void atThreePoundsNothingIsCut() {
        Plan.Decision d = Plan.evaluate(confirmedHigh(3.0));
        assertEquals(Plan.ACTION_REMEASURE, d.action);
        assertEquals(Plan.LENGTH_FLOOR_RULE, d.rule);
        assertTrue(Double.isNaN(d.loadLb), "no cut is proposed");
        assertEquals("Load is at its " + Traction.settingLb(5.0) + " floor: measure again, and "
            + "rest if it stays high.", d.reason);
    }

    @Test void atTheFloorNothingIsCutEither() {
        Plan.Decision d = Plan.evaluate(confirmedHigh(5.0));
        assertEquals(Plan.LENGTH_FLOOR_RULE, d.rule);
        assertTrue(Double.isNaN(d.loadLb));
    }

    @Test void aCutLandsOnTheFloorAndNeverUnder() {
        assertEquals(5.0, Plan.evaluate(confirmedHigh(5.2)).loadLb, 1e-9);
        assertEquals(5.0, Plan.evaluate(confirmedHigh(5.5)).loadLb, 1e-9);
        assertEquals(7.5, Plan.evaluate(confirmedHigh(8.0)).loadLb, 1e-9);
        assertEquals(Plan.LENGTH_CUT_RULE, Plan.evaluate(confirmedHigh(8.0)).rule);
    }

    @Test void theOtherPathsKeepHalfAPound() {
        assertEquals(5.0, Plan.LENGTH_LOAD_FLOOR_LB, 0.0);
        assertEquals(0.5, Scale.LOAD_MIN_LB, 0.0, "the absolute minimum is unchanged");
    }
}
