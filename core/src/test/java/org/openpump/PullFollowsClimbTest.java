package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 R-43 (C12 + fix f + A9): ABOVE THE USUAL TOP THE PULL FOLLOWS THE CLIMB ONE STEP AT A
 * TIME. The length pressure climbs to "Most you will go to" a step a month (R-42); the pull
 * follows by half a pound or one pressure step's worth at the length cylinder's bore, whichever
 * is more, and never jumps there. Not on a return day; not while the last reading is over 6 %;
 * an accepted cut lowers the climbed pressure with it; past 12 lb before month 12 it is said
 * once; never past 15 lb.
 */
class PullFollowsClimbTest {

    @Test void theOwnersYear() {
        LengthYear y = new LengthYear().setup().run();   // no readings
        assertEquals(34, Math.round(y.kpa[1]));
        assertEquals(12.2, Math.round(y.load[1] * 10) / 10.0, 1e-9);
        assertEquals(37, Math.round(y.kpa[6]), "a step at week 6\n" + y.trace());
        assertEquals(13.2, Math.round(y.load[6] * 10) / 10.0, 1e-9);
        assertEquals(41, Math.round(y.kpa[11]), "and to the owner's -12 at week 11");
        assertEquals(14.4, Math.round(y.load[11] * 10) / 10.0, 1e-9, "a step, not 14.7 at once");
        assertEquals(14.7, Math.round(y.load[16] * 10) / 10.0, 1e-9, "the pull catches up");
        assertEquals(14.7, Math.round(y.load[52] * 10) / 10.0, 1e-9);
        for (int w = 2; w <= 52; w++)
            assertTrue(y.load[w] - y.load[w - 1] <= 1.3, "no jumps: week " + w);
        assertFalse(y.past12Said, "already past 12 at setup: the 12-lb words are not owed");
    }

    private static Plan.Inputs climbing(double kpa, double lb) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.pressureKpa = kpa;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.loadLb = lb;
        in.strainSets = 6;
        in.trainingWeeksAtPressure = 1;
        in.climbTopKpa = 41 - 0.14;
        return in;
    }

    private static double step(double kpa) {
        return Math.max(Plan.LENGTH_LOAD_STEP_LB, Traction.loadLbAtBore(kpa + Plan.STEP_HG_KPA,
            4.5) - Traction.loadLbAtBore(kpa, 4.5));
    }

    @Test void aLightPullTakesOneStepNotTheWholeWay() {
        // A person at 4.5 lb whose length pressure starts the climb.
        Plan.Decision d = Plan.evaluate(climbing(33.86, 4.5));
        assertEquals(Plan.ACTION_RAISE_LOAD, d.action);
        assertTrue(d.loadLb <= 4.5 + step(33.86) + 1e-9, "at most one step: " + d.loadLb);
        assertTrue(d.loadLb < Traction.loadLbAtBore(37, 4.5) - 1.0, "not 13.2 lb in one tap");
    }

    @Test void aCutLowersTheClimbedPressureAndTheNextStepStartsFromIt() {
        // One step past the top, 13.2 lb; two readings over 6 %: the cut.
        Plan.Inputs in = climbing(33.86 + Plan.STEP_HG_KPA, Traction.loadLbAtBore(37, 4.5));
        in.strainPct = 7.0;
        in.lastStrainHigh = true;
        in.strainHighConfirmed = true;
        Plan.Decision cut = Plan.evaluate(in);
        assertEquals(Plan.LENGTH_CUT_RULE, cut.rule);
        double to = in.loadLb - 0.5;
        assertEquals(to, cut.loadLb, 1e-9);
        assertEquals(Math.round(Traction.kpaForLbAtBore(to, 4.5)), cut.pressureKpa, 1e-9,
            "the pressure the new load needs at the bore");
        assertTrue(cut.pressureKpa < in.pressureKpa && cut.pressureKpa >= 33.86,
            "lower, never under the usual top");
        // A month on, a reading back inside the window: the climb starts from the lowered
        // pressure, a step from the cut load - not back to the old pressure's load at once.
        Plan.Decision next = Plan.evaluate(climbing(cut.pressureKpa, to));
        assertEquals(Plan.ACTION_RAISE_LOAD, next.action);
        assertEquals(cut.pressureKpa + Plan.STEP_HG_KPA, next.pressureKpa, 1e-9);
        assertTrue(next.loadLb <= to + step(cut.pressureKpa) + 1e-9,
            Traction.settingLb(next.loadLb));
        // Under the top the cut moves no pressure (the coda's is its own).
        Plan.Inputs low = climbing(30, 8.0);
        low.strainPct = 7.0; low.lastStrainHigh = true; low.strainHighConfirmed = true;
        assertTrue(Double.isNaN(Plan.evaluate(low).pressureKpa));
    }

    @Test void noClimbOnAReturnDayOrWhileTheLastReadingIsHigh() {
        Plan.Inputs ret = climbing(33.86, 12.16);
        ret.returnRunsUnder = true;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(ret).action, "a return day");
        Plan.Inputs high = climbing(33.86, 12.16);
        high.lastStrainHigh = true;                        // an old reading, out of the window
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(high).action, "the last reading was high");
    }

    @Test void mostAtExactlyTenDoesNotClimb() {
        LengthYear y = new LengthYear();
        y.mostKpa = 33.86;                                  // 10.0 inHg
        Model m = y.setup().m;
        Plan.Inputs in = LengthYear.inputsFor(m, LengthYear.t0() + 40 * LengthYear.DAY);
        assertFalse(Plan.climbsPastTop(in), "10.0 inHg is the usual top as the wire carries it");
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(in).action);
    }

    @Test void passingTwelveBeforeMonthTwelveIsSaidOnce() {
        LengthYear y = new LengthYear();
        y.startLoadLb = 11.8;
        Model m = y.setup().m;
        long now = LengthYear.t0() + 40 * LengthYear.DAY;
        assertTrue(LengthTrack.past12Due(m, 12.6, now));
        assertFalse(LengthTrack.past12Due(m, 12.0, now), "to 12 is not past it");
        LengthTrack.acceptLoad(m, "length: pressure climb", 12.6, 37.0, now);
        assertTrue(m.trainerLength.warned12);
        m.trainerLength.loadLb = 11.8;                     // a cut later
        assertFalse(LengthTrack.past12Due(m, 12.6, now), "once");
        assertEquals("This passes " + Traction.settingLb(12.0) + " before month 12. Your call: "
            + "the plan never goes past " + Traction.settingLb(15.0) + ".",
            Plan.lengthPast12Words());
        Model late = new LengthYear().setup().m;
        late.trainerLength.loadLb = 11.8;
        late.trainerMonthsPumping = 12;
        assertFalse(LengthTrack.past12Due(late, 12.6, now), "from month 12 nothing to say");
    }

    @Test void neverPastFifteen() {
        Plan.Inputs in = climbing(33.86, 12.16);
        in.climbTopKpa = 50;
        in.ceilKpa = 50;
        double kpa = in.pressureKpa, lb = in.loadLb;
        for (int i = 0; i < 12; i++) {
            Plan.Inputs at = climbing(kpa, lb);
            at.climbTopKpa = 50; at.ceilKpa = 50;
            Plan.Decision d = Plan.evaluate(at);
            if (d.action != Plan.ACTION_RAISE_LOAD) break;
            kpa = d.pressureKpa;
            lb = d.loadLb;
            assertTrue(lb <= Scale.LOAD_HARD_MAX_LB + 1e-9, Traction.settingLb(lb));
        }
    }
}
