package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import org.junit.jupiter.api.Test;

/**
 * t10 R-01 (P2) and R-03 - THE WARM-UP: about five minutes of reps from 3.5 inHg (12 kPa),
 * climbing at most 1.0 inHg (3 kPa) a rep, holds growing evenly 30 s -> 60 s, a 5 s drop after
 * each; girth ends at the work (or 1 inHg steps short of it), a traction session at 80 % of the
 * pull, expansion-only length at the work. "I mark or bruise easily" keeps the gentle warm-up
 * (K3). The numbers are SPEC.md's, from the editor's model (t10/acc.js).
 */
class P2WarmUpTest {

    @Test void ownerGirthL3At30StopsThreeShort() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), day(m));
        int w = stage(r, "warm");
        assertEquals(0, w, "the warm-up comes first");
        assertEquals(list(12, 15, 18, 21, 24, 27), pulls(m, r, w));
        assertEquals(list(30, 36, 42, 48, 54, 60), holdSecs(m, r, w));
        Model.Set s = m.set(r.stages.get(w).setIds.get(0));
        assertEquals(Plan.P2_DROP_SEC, s.lh, "a 5 s drop after each rep");
        assertTrue(r.stages.get(w).name.startsWith("Warm-up to "), r.stages.get(w).name);
        assertEquals(6, RxBuild.p2RepCount(), "max(2, round(300 / 50))");
    }

    @Test void levelOneWeekOneClimbsOneKpaARep() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L1, 5, 17), day(m));
        assertEquals(list(12, 13, 14, 15, 16, 17), pulls(m, r, stage(r, "warm")));
        assertEquals(15.42, minutes(m, r), 1e-9, "warm 5:00 + 5 x 2:05");
    }

    @Test void levelTwoAt27ReachesTheWorkWithNoCarry() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L2, 10, 27), day(m));
        assertEquals(list(12, 15, 18, 21, 24, 27), pulls(m, r, stage(r, "warm")));
        assertEquals(repeat(27, 10), workPulls(m, r), "nothing to carry: the work is reached");
        assertEquals(28.83, minutes(m, r), 1e-9, "10 x 2 min, one rest of 180 s");
    }

    @Test void workUnderTheStartIsEveryRep() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L1, 5, 10), day(m));
        assertEquals(repeat(10, 6), pulls(m, r, stage(r, "warm")));
        assertEquals(list(10, 10, 10, 10, 10, 10), list(RxBuild.p2Reps(10)));
    }

    @Test void warmUpNoneBuildsNone() {
        Model m = owner();
        m.programGirth.warm = Model.Program.WARM_NONE;
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), day(m));
        assertEquals(-1, stage(r, "warm"));
        // EXPECTATION CHANGED (R11-5): 15 fatigue holds of 30 s.
        assertEquals(repeat(30, 15), pulls(m, r, stage(r, "fatigue")), "and nothing to carry");
    }

    @Test void everyWarmUpChoiceButNoneBuildsP2() {
        int[] choices = { Model.Program.WARM_STANDARD, Model.Program.WARM_SHORT,
                          Model.Program.WARM_RAMP };
        for (int i = 0; i < choices.length; i++) {
            Model m = owner();
            m.programGirth.warm = choices[i];
            Model.Routine r = build(m, girth(Plan.L2, 10, 27), day(m));
            assertEquals(list(12, 15, 18, 21, 24, 27), pulls(m, r, stage(r, "warm")),
                         "K19: warm-up choice " + choices[i]);
        }
    }

    @Test void theOldSteppersNoLongerShapeIt() {
        Model m = owner();
        m.rxWarmMin = 0;
        m.rxPrimeKpa = 20.0;
        m.rxWarmRamp = true;
        Model.Routine r = build(m, girth(Plan.L2, 10, 27), day(m));
        assertEquals(list(12, 15, 18, 21, 24, 27), pulls(m, r, stage(r, "warm")));
    }

    @Test void theCeilingCapsTheEnd() {
        Model m = owner();
        m.ceilKpa = 20;
        Model.Routine r = build(m, girth(Plan.L2, 10, 27), day(m));
        int[] ps = RxBuild.p2Reps(20);
        assertEquals(list(ps), pulls(m, r, stage(r, "warm")));
        assertEquals(20, ps[ps.length - 1]);
    }

    @Test void marksKeepTheGentleWarmUp() {
        Model m = owner();
        m.marksEasily = true;
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), day(m));
        int w = stage(r, "warm");
        assertEquals(list(14, 17, 19, 22, 25, 27, 30), pulls(m, r, w),
                     "R-03 test 2: gentle, from the person's 4.0 inHg, ending at the work");
        // EXPECTATION CHANGED (R11-5): 15 fatigue holds of 30 s.
        assertEquals(repeat(30, 15), pulls(m, r, stage(r, "fatigue")), "no carry after it");
        assertEquals(50.42, minutes(m, r), 1e-9);   // R11-5: was 50.00, +25 s of fatigue drops
    }

    @Test void lengthTractionEndsAtEightyPerCent() {
        Model m = owner();
        Model.Routine r = traction(m, 34, 6, day(m));
        assertEquals(list(12, 15, 18, 21, 24, 27), pulls(m, r, stage(r, "warm")),
                     "R-03 test 1: 80 % of 34 is 27");
        assertEquals(1, stage(r, "warm"), "straight after the hand release");
    }

    @Test void lengthWithMarksIsGentleToEightyPerCent() {
        Model m = owner();
        m.marksEasily = true;
        Model.Routine r = traction(m, 34, 6, day(m));
        assertEquals(list(14, 17, 19, 22, 24, 27), pulls(m, r, stage(r, "warm")), "R-03 test 3");
        assertEquals(repeat(34, 10), pulls(m, r, stage(r, "fatigue")));
        assertEquals(65.08, minutes(m, r), 1e-9);
    }

    @Test void expansionOnlyLengthWarmsToTheWork() {
        Model m = owner();                       // no length cylinder: the expansion session
        Model.Routine r = build(m, length(34), day(m));
        assertEquals(list(12, 15, 18, 21, 24, 27), pulls(m, r, stage(r, "warm")),
                     "fix b: to the work, stopping at the step's reach");
        assertEquals(list(28, 29, 30, 31, 32), workPulls(m, r), "and the work carries on");
        assertEquals(15.42, minutes(m, r), 1e-9);
    }

    @Test void theCardLine() {
        assertEquals("Warm-up: 6 holds from " + Model.Fmt.pBare(12) + " to " + Model.Fmt.p(27)
            + ", 30 → 60 s", RxBuild.p2WarmLine(30, false));
        assertEquals("Warm-up: 6 holds from " + Model.Fmt.pBare(12) + " to " + Model.Fmt.p(27)
            + ", 30 → 60 s", RxBuild.p2WarmLine(34, true));
    }
}
