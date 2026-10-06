package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * t10 R-02 (P2 carry) and R-10 - A WARM-UP THAT STOPS SHORT OF THE WORK CLIMBS ON at +1 kPa a
 * hold through the holds after it - the fatigue block, then the work holds (girth); the
 * fatigue holds, then the strain holds (traction); the work holds (expansion only) - never
 * past the work. The holds stay what they are: a fatigue hold out of the count, a work hold
 * counted. It composes with "Ramp in each set" (the lower of the two). Not after an R4
 * ramp-in, nor when there is no warm-up (P4). The numbers are SPEC.md's.
 */
class CarryRampTest {

    @Test void ownerGirthL3At30() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), day(m));
        // EXPECTATION CHANGED (R11-5): 15 fatigue holds of 30 s, the carry the same.
        assertEquals(join(list(28, 29), repeat(30, 13)),
                     pulls(m, r, stage(r, "fatigue")));
        assertEquals(repeat(30, 14), workPulls(m, r), "the work is all at 30");
        assertEquals(51.92, minutes(m, r), 1e-9);   // R11-5: was 51.50, +25 s of drops
        assertTrue(r.stages.get(stage(r, "fatigue")).fatigueBlock, "still the fatigue block");
        assertEquals(28.0, r.netTargetMin, 1e-9, "the counted work is 14 x 2 min");
        Model.Set drop = m.set(r.stages.get(stage(r, "fatigue")).setIds.get(0));
        assertEquals(Mint.DROP_KPA, drop.lo, "the fatigue block still vents to 5 kPa");
    }

    @Test void ownerGirthL4At37RampsNineFatigueHolds() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L4, 18, 37), day(m));
        // EXPECTATION CHANGED (R11-5): 15 fatigue holds of 30 s - the carry's nine steps, then 37.
        assertEquals(join(list(28, 29, 30, 31, 32, 33, 34, 35, 36), repeat(37, 6)),
                     pulls(m, r, stage(r, "fatigue")));
        assertEquals(repeat(37, 18), workPulls(m, r));
        assertEquals(63.25, minutes(m, r), 1e-9);   // R11-5: was 62.83, +25 s of drops
    }

    @Test void lengthTractionCarriesThroughTheFatigueHolds() {
        Model m = owner();
        Model.Routine r = traction(m, 34, 6, day(m));
        assertEquals(list(28, 29, 30, 31, 32, 33, 34, 34, 34, 34),
                     pulls(m, r, stage(r, "fatigue")));
        assertEquals(repeat(34, 6), pulls(m, r, stage(r, "strain")));
        assertEquals(67.08, minutes(m, r), 1e-9);
        // The traction holds keep their release to 0 between them.
        Model.Stage fat = r.stages.get(stage(r, "fatigue"));
        for (int j = 0; j < fat.setIds.size(); j++)
            assertEquals(0, m.set(fat.setIds.get(j)).lo, "a pull releases to 0");
    }

    @Test void aCarryLongerThanTheFatigueBlockReachesTheStrainHolds() {
        Model m = owner();
        Model.Routine r = traction(m, 40, 6, day(m));     // 80 % of 40 is 32: P2 stops at 27
        assertEquals(list(28, 29, 30, 31, 32, 33, 34, 35, 36, 37),
                     pulls(m, r, stage(r, "fatigue")));
        assertEquals(list(38, 39, 40, 40, 40, 40), pulls(m, r, stage(r, "strain")));
    }

    @Test void expansionOnlyCarriesThroughItsWork() {
        Model m = owner();
        Model.Routine r = build(m, length(34), day(m));
        assertEquals(list(28, 29, 30, 31, 32), workPulls(m, r));
        assertEquals(15.42, minutes(m, r), 1e-9);
    }

    @Test void itComposesWithRampInEachSet() {
        Model m = owner();
        m.programGirth.work = Model.Program.WORK_RAMP_IN_SET;
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), day(m));
        // EXPECTATION CHANGED (R11-5): 15 fatigue holds of 30 s, the carry the same.
        assertEquals(join(list(28, 29), repeat(30, 13)),
                     pulls(m, r, stage(r, "fatigue")));
        List<Integer> ws = workStages(r);
        assertEquals(list(24, 27, 30, 30, 30), pulls(m, r, ws.get(0).intValue()),
                     "R-02 test 5: the first block's own climb");
        assertEquals(list(27, 30), pulls(m, r, ws.get(1).intValue()).subList(0, 2),
                     "a block after a rest climbs a short way");
        assertEquals(list(27, 30), pulls(m, r, ws.get(2).intValue()).subList(0, 2));
    }

    @Test void neverPastTheWork() {
        Model m = owner();
        for (int kpa = 10; kpa <= 43; kpa++) {
            Model.Routine r = build(m, girth(Plan.L4, 18, kpa), day(m));
            List<Integer> fat = pulls(m, r, stage(r, "fatigue"));
            for (int i = 0; i < fat.size(); i++)
                assertTrue(fat.get(i) <= kpa, "a carried hold past the work at " + kpa);
            for (int i = 1; i < fat.size(); i++)
                assertTrue(fat.get(i) - fat.get(i - 1) <= Plan.P2_CARRY_KPA,
                           "+1 kPa a hold at most, at " + kpa);
        }
    }

    @Test void noCarryWithoutTheWarmUpOrAfterTheGentleOne() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), day(m).sameDay(false, false, true));
        assertEquals(-1, stage(r, "warm"));
        // EXPECTATION CHANGED (R11-5): 15 fatigue holds of 30 s.
        assertEquals(repeat(30, 15), pulls(m, r, stage(r, "fatigue")), "P4: nothing to carry");
        Model g = owner();
        g.marksEasily = true;
        Model.Routine gr = build(g, girth(Plan.L3, 14, 30), day(g));
        assertEquals(repeat(30, 15), pulls(g, gr, stage(gr, "fatigue")));
    }

    /** The two lists, one after the other. */
    private static List<Integer> join(List<Integer> a, List<Integer> b) {
        List<Integer> out = new java.util.ArrayList<Integer>(a);
        out.addAll(b);
        return out;
    }

    @Test void theRuleItself() {
        RxBuild.CarryRule c = new RxBuild.CarryRule(27, 30, 1);
        assertEquals(28, c.at(0, 30));
        assertEquals(29, c.at(1, 30));
        assertEquals(30, c.at(2, 30));
        assertEquals(25, c.at(3, 25), "a hold already lower keeps its own");
        assertFalse(c.at(4, 30) > 30);
    }
}
