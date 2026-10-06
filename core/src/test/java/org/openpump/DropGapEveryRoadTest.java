package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A DROP ABOVE THE FLOOR STAYS 1.0 inHg UNDER ITS PULL ON EVERY ROAD (review I4/I5, the
 * controller's ruling 2026-09-30): one function, RunEdit#dropKept, wherever a step's pull or
 * drop is produced or changed. A drop at or under the floor (10 kPa) keeps the rule it always
 * had - strictly under the pull - so nothing that existed before moves (review M1 stays).
 */
class DropGapEveryRoadTest {

    /** The rule, said once: a drop above 10 kPa is at least 4 kPa under its pull; at or under
     *  10 kPa it is under it. */
    static boolean gapHolds(int up, int lo) {
        return lo <= RunEdit.DROP_FLOOR_KPA ? lo < up : lo <= up - RunEdit.DROP_GAP_KPA;
    }

    static Model.Preset step(int up, int lo, int uh, int lh) {
        Model.Preset p = new Model.Preset();
        p.up = up; p.lo = lo; p.uh = uh; p.lh = lh; p.sp = 50; p.durMs = 60_000L;
        p.stageIdx = 0; p.pos = 0; p.setId = "s1"; p.label = "step";
        return p;
    }

    /* ------------------------------------------------------------------ the function */

    @Test void theOneFunction() {
        assertEquals(26, RunEdit.dropKept(26, 30, false));
        assertEquals(23, RunEdit.dropKept(26, 27, false), "a lowered pull takes the drop down");
        assertEquals(10, RunEdit.dropKept(12, 13, false), "down to the floor, not past it");
        assertEquals(10, RunEdit.dropKept(10, 11, false), "at the floor: under the pull, as ever");
        assertEquals(8, RunEdit.dropKept(8, 9, false), "M1: a low drop 1 kPa under stays");
        assertEquals(9, RunEdit.dropKept(12, 10, false), "never at or above the pull");
        assertEquals(29, RunEdit.dropKept(29, 30, true), "a hold keeps its filler");
        assertTrue(RunEdit.dropPastGap(27, 30));
        assertTrue(!RunEdit.dropPastGap(26, 30) && !RunEdit.dropPastGap(10, 11));
    }

    /* ------------------------------------------------------- the warm-up's ease cycle (I4) */

    @Test void theWarmUpsFirstCycleKeepsTheGap() {
        // The review's probe: girth at 30 kPa, a drop of 26 - "First cycle at" went out 20/19.
        Model m = new Model();
        m.ceilKpa = 43;
        m.rxNewToPumping = false;
        m.trainerMonthsPumping = 7;
        m.trainerMonthsAt = System.currentTimeMillis();
        m.rxDropKpa = 26;
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 10, 120, 60, 30, false,
                                 20.0, Mint.POWER_PCT);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        boolean sawEase = false;
        for (int i = 0; i < r.stages.size(); i++)
            for (int j = 0; j < r.stages.get(i).setIds.size(); j++) {
                Model.Set s = m.set(r.stages.get(i).setIds.get(j));
                if (s == null || s.rest || s.lh <= 0) continue;
                if (s.name.startsWith("Warm-up to")) sawEase = true;
                assertTrue(gapHolds(s.up, s.lo), s.name + " " + s.up + "/" + s.lo);
                if (s.ramp) assertTrue(gapHolds(s.up2, s.lo2), s.name + " end " + s.up2 + "/" + s.lo2);
            }
        assertTrue(sawEase, "the P2 warm-up's reps are there (t10 R-01)");
        for (Model.Preset p : m.plan(r))
            if (!p.rest && p.lh > 0 && !p.cyclePart)
                assertTrue(gapHolds(p.up, p.lo), p.label + " " + p.up + "/" + p.lo);
    }

    /* ---------------------------------------------------------- the routine offset (I5) */

    @Test void theRoutineOffsetTakesTheDropDownWithThePull() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        Model.Preset p = step(30, 26, 120, 5);
        p.loMax = 26;
        plan.add(p);
        RoutineOffset.apply(plan, 0, new boolean[]{ true }, -3, 0, Integer.MAX_VALUE, 43, null);
        assertEquals(27, p.up);
        assertEquals(23, p.lo, "the review's probe: 27/26 before");
    }

    /* ---------------------------------------- the set clamp and a lowered ceiling (I5) */

    @Test void theSetClampAndALoweredCeiling() {
        Model.Set s = Model.Set.fixed("s1", "Work", 30, 26, 120, 30, 50, 600);
        s.clamp(28);
        assertEquals(28, s.up);
        assertEquals(24, s.lo, "28/26 before");
        Model m = new Model();
        m.ceilKpa = 43;
        Model.Set t = Model.Set.fixed("s2", "Work", 30, 26, 120, 30, 50, 600);
        m.sets.add(t);
        m.ceilKpa = 28;                                  // Settings lowers the ceiling
        m.clampAll();
        assertEquals(24, t.lo);
        // Unchanged: a low drop (M1), and a set that only holds.
        Model.Set low = Model.Set.fixed("s3", "Low", 12, 10, 60, 10, 50, 600);
        low.clamp(43);
        assertEquals(10, low.lo, "a drop at the floor 2 kPa under its pull is as it was");
        Model.Set hold = Model.Set.fixed("s4", "Hold", 30, 29, 120, 0, 50, 600);
        hold.clamp(43);
        assertEquals(29, hold.lo, "no drop time: the filler stays");
    }

    /* ------------------------------------------------------------------------ ramps */

    @Test void aRampsInterpolatedStepsKeepTheGap() {
        // From a drop at the floor 1 kPa under its pull to one above it: a middle step rounded
        // to 21/18 before.
        Model m = new Model();
        m.ceilKpa = 43;
        Model.Set s = Model.Set.ramp("r1", "Climb", 11, 10, 60, 10, 50, 30, 26, 60, 10, 50, 5, 700);
        s.clamp(43);
        m.sets.add(s);
        Model.Routine r = new Model.Routine();
        r.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "r1" }));
        m.routines.add(r);
        List<Model.Preset> plan = m.plan(r);
        assertTrue(plan.size() >= 3);
        for (Model.Preset p : plan) assertTrue(gapHolds(p.up, p.lo), p.label + " " + p.up + "/" + p.lo);
    }

    @Test void theReshapeTheRecountAndTheStartCapKeepTheGap() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        for (int i = 0; i < 4; i++) { Model.Preset p = step(30, 26, 60, 10); p.loMax = 26; plan.add(p); }
        // Reshape the rest of the ramp to a lower end, its drop as it was.
        RunEdit.reshapeRemaining(plan, 0, 30, 26, 60, 10, 50, 20, 26, 60, 10, 50, 43);
        for (int k = 1; k < plan.size(); k++)
            assertTrue(gapHolds(plan.get(k).up, plan.get(k).lo),
                "reshape " + plan.get(k).up + "/" + plan.get(k).lo);
        // The step count, to the same end.
        List<Model.Preset> plan2 = new ArrayList<Model.Preset>();
        for (int i = 0; i < 3; i++) { Model.Preset p = step(30, 26, 60, 10); p.loMax = 26; plan2.add(p); }
        plan2.get(2).up = 22;                             // an end under the drop's gap
        plan2.get(2).lo = 18;
        RunEdit.resizeRemaining(plan2, 0, 30, 26, 60, 10, 50, 4, 43);
        for (int k = 1; k < plan2.size(); k++)
            assertTrue(gapHolds(plan2.get(k).up, plan2.get(k).lo),
                "recount " + plan2.get(k).up + "/" + plan2.get(k).lo);
        // A run held to today's hard limits at START.
        List<Model.Preset> plan3 = new ArrayList<Model.Preset>();
        plan3.add(step(30, 26, 60, 10));
        RunEdit.capToLimits(plan3, new int[]{ 25 });
        assertEquals(25, plan3.get(0).up);
        assertEquals(21, plan3.get(0).lo);
        // The end the adjust sheet reads.
        List<Model.Preset> plan4 = new ArrayList<Model.Preset>();
        plan4.add(step(30, 20, 60, 10));
        plan4.add(step(28, 26, 60, 10));
        assertEquals(24, RunEdit.endTuple(plan4, 0, 43)[LiveEdit.LO]);
        // One more step on the ramp.
        List<Model.Preset> plan5 = new ArrayList<Model.Preset>();
        plan5.add(step(30, 20, 60, 10));
        plan5.add(step(28, 26, 60, 10));
        int at = RunEdit.addRampStep(plan5, 0, 43);
        if (at >= 0) assertTrue(gapHolds(plan5.get(at).up, plan5.get(at).lo));
    }

    /* -------------------------------------------------------------------- Coming steps */

    @Test void comingStepsKeepTheGap() {
        // Re-spread a ramp from 11/10 to 30/26.
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        Model.Preset a = step(11, 10, 60, 10), z = step(30, 26, 60, 10);
        plan.add(a); plan.add(z);
        ComingSteps.respreadRamp(plan, 0, 2, 5, 70, 43);
        for (Model.Preset p : plan) assertTrue(gapHolds(p.up, p.lo), "respread " + p.up + "/" + p.lo);
        // Undo puts a drop back under a pull that has since come down.
        Model.Preset b = step(20, 10, 60, 10);
        ComingSteps.Shape was = new ComingSteps.Shape();
        was.sets = 1; was.uh = 60; was.lh = 10; was.dropKpa = 26;
        ComingSteps.restoreBlock(b, was);
        assertEquals(16, b.lo, "19 before");
        // A drop raised past the gap is held to it.
        Model.Preset c = step(30, 10, 60, 10);
        ComingSteps.applyDrop(c, 29);
        assertEquals(26, c.lo);
        assertEquals(RunEdit.dropGapSaid(), ComingSteps.dropRefusal(10, 27, 30));
    }

    /* ------------------------------------------------------------- "Rest of this ramp" */

    @Test void aShiftThatRaisesTheDropAndLowersThePullKeepsTheGap() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        plan.add(step(30, 20, 60, 10));
        plan.add(step(32, 26, 60, 10));
        plan.add(step(34, 28, 60, 10));
        int[] before = { 30, 20, 60, 10, 50 };
        int[] after = { 27, 23, 60, 10, 50 };             // the pull −3, the drop +3
        new SetShift().shift(plan, 0, 2, before, after, 43);
        for (int k = 1; k < plan.size(); k++)
            assertTrue(gapHolds(plan.get(k).up, plan.get(k).lo),
                "shift " + plan.get(k).up + "/" + plan.get(k).lo);
    }

    /* -------------------------------------------------------------------- the strip */

    @Test void theStripRefusesWhatWouldCloseTheGap() {
        assertEquals(RunEdit.dropGapSaid(),
            QuickAdjust.refusal(QuickAdjust.PULL, 30, -1, 27, -1, 43, 43));
        assertEquals(null, QuickAdjust.refusal(QuickAdjust.PULL, 30, -1, 25, -1, 43, 43));
        assertEquals(RunEdit.dropGapSaid(),
            QuickAdjust.refusal(QuickAdjust.DROP, 26, 1, 30, -1, 43, 43));
        assertEquals(null, QuickAdjust.refusal(QuickAdjust.PULL, 13, -1, 10, -1, 43, 43),
            "M1: a drop at the floor 2 kPa under a pull of 12 is allowed as ever");
    }
}
