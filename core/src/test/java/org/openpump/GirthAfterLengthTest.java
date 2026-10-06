package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import org.junit.jupiter.api.Test;

/**
 * t10 R-07 (R4) - GIRTH AFTER LENGTH, THE SAME DAY: from L3 the fatigue block goes, and in its
 * place the person's choice (Model#girthAfterLength) - a 2-minute ramped warm-up (30 s holds
 * from P2's start, +3 kPa a rep, to the work; not work), the first 3 holds ramped (counted), or
 * nothing. At L1/L2 the choice leads in only when P4 dropped the warm-up (fix e). No hold is
 * added. The numbers are SPEC.md's (the editor's buildDay, owner both tracks, length first).
 */
class GirthAfterLengthTest {

    /** The day girth follows length within the half hour: R4 and P4 together. */
    static RxBuild.Day afterLength(Model m) { return day(m).sameDay(false, true, true); }

    @Test void ownerL3At30() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), afterLength(m));
        assertEquals(39.25, minutes(m, r), 1e-9);
        int in = stage(r, "rampin");
        assertEquals(0, in, "it leads in");
        assertEquals(list(12, 15, 18, 21, 24, 27, 30), pulls(m, r, in));
        assertEquals(repeat(30, 7), holdSecs(m, r, in));
        assertEquals(Model.STAGE_WARM, r.stages.get(in).colour, "not work");
        assertEquals(-1, stage(r, "fatigue"), "no fatigue block");
        assertEquals(repeat(30, 14), workPulls(m, r), "14 work holds at 30");
        assertEquals(28.0, r.netTargetMin, 1e-9, "the ramp-in counts nothing");
    }

    @Test void ownerL4At37() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L4, 18, 37), afterLength(m));
        assertEquals(52.33, minutes(m, r), 1e-9);
        assertEquals(list(12, 15, 18, 21, 24, 27, 30, 33, 36, 37),
                     pulls(m, r, stage(r, "rampin")));
        assertEquals(18, workPulls(m, r).size());
    }

    @Test void rampedFirstSetsCountAndClimb() {
        Model m = owner();
        m.girthAfterLength = Model.R4_SETS;
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), afterLength(m));
        assertEquals(-1, stage(r, "fatigue"));
        assertEquals(-1, stage(r, "rampin"));
        java.util.List<Integer> w = workPulls(m, r);
        assertEquals(list(21, 24, 27, 30), w.subList(0, 4), "min(30, 30 - (3 - i) x 3)");
        assertEquals(14, w.size(), "they are work holds: counted, none added");
        assertEquals(28.0, r.netTargetMin, 1e-9);
    }

    @Test void nothingInItsPlace() {
        Model m = owner();
        m.girthAfterLength = Model.R4_NONE;
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), afterLength(m));
        assertEquals(-1, stage(r, "fatigue"));
        assertEquals(-1, stage(r, "rampin"));
        assertEquals(-1, stage(r, "warm"));
        assertEquals(repeat(30, 14), workPulls(m, r));
    }

    @Test void levelOneAfterLengthWithP4() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L1, 10, 27), afterLength(m));
        assertEquals(list(12, 15, 18, 21, 24, 27), pulls(m, r, stage(r, "rampin")), "fix e");
        assertEquals(repeat(27, 10), workPulls(m, r));
        assertEquals(24.33, minutes(m, r), 1e-9);
    }

    @Test void levelOneAfterLengthBeyondTheHalfHourKeepsItsWarmUp() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L1, 10, 27), day(m).sameDay(false, true, false));
        assertEquals(-1, stage(r, "rampin"), "no fatigue block to drop, a warm-up to lead in");
        assertEquals(list(12, 15, 18, 21, 24, 27), pulls(m, r, stage(r, "warm")));
    }

    @Test void beyondTheHalfHourTheWarmUpLeadsTheRampInAndNothingCarries() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), day(m).sameDay(false, true, false));
        assertEquals(0, stage(r, "warm"));
        assertEquals(1, stage(r, "rampin"));
        assertEquals(-1, stage(r, "fatigue"));
        assertEquals(repeat(30, 14), workPulls(m, r), "the ramp-in reaches the work: no carry");
    }

    @Test void withoutP2TheRampInIsThreeReps() {
        assertEquals(list(24, 27, 30), list(RxBuild.r4RampReps(false, 30)),
                     "marks: max(2, round(120 / 35)) = 3 reps at work - 6, - 3, work");
        Model m = owner();
        m.marksEasily = true;
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), afterLength(m));
        assertEquals(list(24, 27, 30), pulls(m, r, stage(r, "rampin")));
    }

    @Test void theRunOfTheDay() {
        Model m = SecondSessionTest.ownerDay();
        filed(m, SecondSessionTest.lengthSaved(m), NOW - 5 * MIN, 55);
        Model.Routine saved = SecondSessionTest.girthSaved(m);
        RunShape.Choice c = TrainerTab.dayChoice(m, saved, NOW, false, false);
        assertTrue(c.girthAfterLength);
        RunShape.Built b = RunShape.build(m, saved, c);
        assertEquals(Model.R4_WARM, b.afterLength);
        assertEquals(39.25, minutes(m, b.routine), 1e-9);
        assertTrue(RunShape.lines(b, 14, 5, "Length").contains(
            "Length ran first: no fatigue block — a short climbing warm-up leads into your "
            + "pressure instead."));
        // EXPECTATION CHANGED (R11-5): 51.50 before - the fatigue block's 30 s holds add 25 s.
        assertEquals(51.92, minutes(m, saved), 1e-9, "the saved routine keeps its fatigue block");
    }
}
