package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 R-46 (L2): "LENGTH LOAD AFTER MONTH 3" - the person's setting. The slow calendar load step
 * (the default) adds half a pound every 2 length training weeks while the strain sets are under
 * 12: toward 5 lb before month 6, then toward 12; never past "Most you will go to" at the bore or
 * 15 lb; one change a morning, none on a return day, none while a reading is over 6 %; it only
 * adds. "Load only after 12 strain sets" is the load as before.
 */
class SlowLoadStepTest {

    private static LengthYear starter(int mode) {
        LengthYear y = new LengthYear();
        y.months = 2;
        y.lengthPlanKpa = 27;          // girth 23, length 27 kPa, Most unset
        y.lengthOffsetKpa = 0;
        y.mostKpa = 0;
        y.startLoadLb = Plan.LENGTH_LOAD_START_LB;
        y.lengthLoadMode = mode;
        return y.setup().run();
    }

    private static double lb(LengthYear y, int week) {
        return Math.round(y.load[week] * 10) / 10.0;
    }

    @Test void theTwoMonthStartersSlowYear() {
        LengthYear y = starter(Model.LENGTH_LOAD_SLOW);
        assertEquals(2.5, lb(y, 1), 1e-9);
        assertEquals(3.0, lb(y, 6), 1e-9, y.trace());
        assertEquals(4.5, lb(y, 13), 1e-9);
        assertEquals(5.0, lb(y, 16), 1e-9);
        // The model's months are calendar months and this plan's are 30.44 days, so the
        // target moves from 5 lb to 12 lb a fortnight later here: 6.5 rather than 7.0 at
        // week 26, the same half-pound every two length training weeks.
        assertTrue(lb(y, 26) >= 6.5 && lb(y, 26) <= 7.0,
            Traction.settingLb(lb(y, 26)) + ": " + y.trace());
        assertEquals(12.0, lb(y, 52), 1e-9);
        for (int w = 2; w <= 52; w++)
            assertTrue(y.load[w] - y.load[w - 1] <= 0.5 + 1e-9, "half a pound at a time");
        assertTrue(y.trace().contains(Plan.LENGTH_SLOW_LOAD_RULE));
    }

    @Test void loadOnlyAfterTwelveSetsHoldsIt() {
        LengthYear y = starter(Model.LENGTH_LOAD_AFTER12);
        for (int w = 6; w <= 52; w++) assertEquals(3.0, lb(y, w), 1e-9, "week " + w);
    }

    @Test void theOwnersPullComesFromTheLengthPressureAlone() {
        LengthYear y = new LengthYear().setup().run();
        assertFalse(y.trace().contains(Plan.LENGTH_SLOW_LOAD_RULE), y.trace());
        assertEquals(14.7, lb(y, 52), 1e-9);
    }

    private static Plan.Inputs slow(double lb) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L2;
        in.monthIndex = 4;
        in.pressureKpa = 30;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.loadLb = lb;
        in.strainSets = 6;
        in.lengthLoadMode = Model.LENGTH_LOAD_SLOW;
        in.slowLoadDue = true;
        return in;
    }

    @Test void theStepAndWhatHoldsIt() {
        Plan.Decision d = Plan.evaluate(slow(3.0));
        assertEquals(Plan.ACTION_RAISE_LOAD, d.action);
        assertEquals(Plan.LENGTH_SLOW_LOAD_RULE, d.rule);
        assertEquals(3.5, d.loadLb, 1e-9);
        Plan.Inputs high = slow(3.0);
        high.lastStrainHigh = true;
        assertFalse(Plan.evaluate(high).action == Plan.ACTION_RAISE_LOAD,
            "over target at 3.0 lb: the slow step waits");
        Plan.Inputs ret = slow(3.0);
        ret.returnRunsUnder = true;
        assertFalse(Plan.evaluate(ret).action == Plan.ACTION_RAISE_LOAD, "not on a return day");
        Plan.Inputs notDue = slow(3.0);
        notDue.slowLoadDue = false;
        assertFalse(Plan.evaluate(notDue).action == Plan.ACTION_RAISE_LOAD);
        assertFalse(Plan.evaluate(slow(5.0)).action == Plan.ACTION_RAISE_LOAD,
            "5 lb before month 6");
        Plan.Inputs six = slow(5.0);
        six.monthIndex = 6;
        assertEquals(5.5, Plan.evaluate(six).loadLb, 1e-9, "then on toward 12");
        Plan.Inputs above = slow(9.0);
        assertFalse(Plan.evaluate(above).action == Plan.ACTION_RAISE_LOAD, "it only adds");
        Plan.Inputs twelve = slow(3.0);
        twelve.strainSets = Plan.LENGTH_STRAIN_SETS_MAX;
        assertFalse(Plan.evaluate(twelve).action == Plan.ACTION_RAISE_LOAD,
            "at 12 sets the low-readings load step takes over");
    }

    @Test void theClockAndMost() {
        LengthYear y = new LengthYear();
        y.months = 4;
        y.lengthPlanKpa = 30;
        y.lengthOffsetKpa = 0;
        y.mostKpa = 30;                                    // the pull is already at Most
        y.startLoadLb = Traction.loadLbAtBore(30, 4.5);
        Model m = y.setup().m;
        long now = LengthYear.t0() + 8 * LengthYear.HOUR;
        assertEquals(Model.TrainerTrackState.SLOW_LOAD_NONE, m.trainerLength.slowLoadWeeks);
        assertTrue(LengthTrack.startSlowClock(m, now), "the first look from month 3 starts it");
        assertFalse(LengthTrack.startSlowClock(m, now), "once");
        m.trainerLength.slowLoadWeeks = 0;
        assertFalse(LengthTrack.slowLoadDue(m, m.trainerLength, 5, now),
            "never past Most you will go to at the bore");
        m.rxLengthMaxKpa = 0;
        assertTrue(LengthTrack.slowLoadDue(m, m.trainerLength, 2, now));
        assertFalse(LengthTrack.slowLoadDue(m, m.trainerLength, 1, now), "two weeks");
        LengthTrack.acceptLoad(m, Plan.LENGTH_SLOW_LOAD_RULE, 9.0, Double.NaN, now);
        assertEquals(TrainerTab.accumulatedTrainingWeeks(m, Plan.TRACK_LENGTH,
            m.trainerEnrolledAt, now), m.trainerLength.slowLoadWeeks, "the clock restarts");
    }
}
