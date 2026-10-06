package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 R-42 (C11 + fix c, APP-FIXES 15/20): THE MONTHLY LENGTH STEP IS TRULY MONTHLY. The length
 * pressure's creep runs 30.44 days after the length pressure last changed - the pressure clock
 * alone, floored - not after a re-mint, and never on a fortnight rounded up to a month. Its last
 * step lands on the limit; the load's last half-pound lands on 12 lb; and at the load cap the
 * coda's creep still runs.
 */
class LengthCreepMonthlyTest {

    private static final long DAY = LengthYear.DAY;

    /** A month-7 length track at 27 kPa (8 inHg), under the usual 10, no maximum set. */
    private static Model model() {
        LengthYear y = new LengthYear();
        y.lengthPlanKpa = 27;
        y.lengthOffsetKpa = 0;
        y.mostKpa = 0;
        return y.setup().m;
    }

    @Test void sixteenDaysIsNotAMonth() {
        Model m = model();
        long from = m.trainerLength.pressureSinceMs;
        long d16 = from + 16 * DAY, d31 = from + 31 * DAY;
        assertEquals(1, TrainerTab.monthsBetween(from, d16), "what it read before: a month");
        Plan.Inputs early = LengthYear.inputsFor(m, d16);
        assertEquals(0, early.trainingWeeksAtPressure);
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(early).action, "16 days: no creep");
        Plan.Decision d = Plan.evaluate(LengthYear.inputsFor(m, d31));
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action, "31 days: the creep");
        assertEquals(27 + Plan.STEP_HG_KPA, d.pressureKpa, 1e-9);
    }

    @Test void aReMintDoesNotRestartTheClock() {
        Model m = model();
        long from = m.trainerLength.pressureSinceMs;
        // A strain set accepted (and the routine re-minted) on day 20.
        m.trainerLength.setStrainSets(7, from + 20 * DAY);
        m.trainerLength.lastMintMs = from + 20 * DAY;
        assertEquals(Plan.ACTION_RAISE_PRESSURE,
            Plan.evaluate(LengthYear.inputsFor(m, from + 31 * DAY)).action,
            "the month runs from the pressure's last change");
    }

    private static Plan.Inputs length(double kpa) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.pressureKpa = kpa;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.loadLb = 8.0;
        in.strainSets = 6;
        in.trainingWeeksAtPressure = 1;
        return in;
    }

    @Test void theLastStepLandsOnTheLimit() {
        double top = Plan.LENGTH_SOFT_CAP_HI_KPA;              // 10 inHg
        Plan.Decision d = Plan.evaluate(length(top - 0.9 * Plan.HG));
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action, "0.9 inHg under: a step");
        assertEquals(top, d.pressureKpa, 1e-9, "...that lands ON the limit");
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(length(top - 0.1)).action,
            "no whole-kPa step left to take");
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(length(top)).action);
    }

    private static Plan.Inputs lowAtTwelveSets(double lb, double kpa) {
        Plan.Inputs in = length(kpa);
        in.strainSets = Plan.LENGTH_STRAIN_SETS_MAX;
        in.loadLb = lb;
        in.strainPct = 1.5;
        in.strainMissDays = Plan.LENGTH_MISS_DEBOUNCE_DAYS;
        return in;
    }

    @Test void theLoadsLastHalfPoundLandsOnTwelve() {
        Plan.Decision d = Plan.evaluate(lowAtTwelveSets(11.7, 33.86));
        assertEquals(Plan.ACTION_RAISE_LOAD, d.action);
        assertEquals(12.0, d.loadLb, 1e-9, "11.7 -> 12.0, not stuck at 11.7");
        assertEquals(Plan.LENGTH_NEVER_RULE, d.rule);
    }

    @Test void atTheLoadCapTheCodaStillCreeps() {
        double kpa = 27;
        int steps = 0;
        for (int guard = 0; guard < 10; guard++) {
            Plan.Decision d = Plan.evaluate(lowAtTwelveSets(12.0, kpa));
            if (d.action != Plan.ACTION_RAISE_PRESSURE) {
                assertEquals(Plan.ACTION_HOLD, d.action);
                break;
            }
            kpa = d.pressureKpa;
            steps++;
        }
        assertTrue(steps >= 2, "it crept");
        assertEquals(Math.round(Plan.LENGTH_SOFT_CAP_HI_KPA), Math.round(kpa), "12 sets, 12 lb, "
            + "low strain: the coda still reaches 10 inHg on the wire");
    }
}
