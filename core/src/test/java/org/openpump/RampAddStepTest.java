package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "+ step" ON THE RAMP PLAYING (owner request, 0.10): one more step at the ramp's top - the last
 * step's pull, drop, hold, drop time and speed - with its time per step; the pump's nine steps,
 * the two-hour stop and the ceiling kept; "− step" takes an added step out again, never the step
 * playing nor one already run. The status line, the NOW line, Coming steps and the stage bar all
 * read the plan, so they follow.
 */
class RampAddStepTest {

    private static final int CEIL = 40;

    private String unitBefore;
    @BeforeEach void kpa() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_KPA; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    /** A 5-step ramp 20 → 30 kPa, 2:12 a step, then a 3-minute rest (another stage). */
    private static List<Model.Preset> plan(int steps) {
        Model.Set s = Model.Set.ramp("R", "Work hold", 20, 5, 60, 5, 60, 30, 8, 60, 5, 80,
                                     steps, steps * 132);
        List<Model.Preset> plan = new ArrayList<Model.Preset>(s.ladder());
        for (Model.Preset p : plan) { p.setId = "R"; p.pos = 0; p.stageIdx = 1; RunEdit.asBuilt(p); }
        Model.Preset rest = new Model.Preset();
        rest.rest = true; rest.durMs = 180_000L; rest.stageIdx = 2; rest.setId = "rest"; rest.label = "Rest";
        plan.add(rest);
        return plan;
    }

    @Test
    void plusStepAppendsTheTopStepWithItsTimePerStep() {
        List<Model.Preset> plan = plan(5);
        long before = ComingSteps.totalMs(plan);
        int at = RunEdit.addRampStep(plan, 1, CEIL);
        assertEquals(5, at, "after the ramp's last step, before the rest");
        Model.Preset z = plan.get(4), p = plan.get(5);
        assertEquals(z.up, p.up);
        assertEquals(z.lo, p.lo);
        assertEquals(z.uh, p.uh);
        assertEquals(z.lh, p.lh);
        assertEquals(z.sp, p.sp);
        assertEquals(z.durMs, p.durMs, "the ramp's time per step");
        assertTrue(p.added);
        assertEquals(before + p.durMs, ComingSteps.totalMs(plan), "the run is one step longer");
        assertTrue(plan.get(6).rest, "the rest still follows the ramp");
        // Every reader of the plan sees six steps.
        assertEquals(6, RunEdit.stepOfSet(plan, 1)[1]);
        assertEquals(2, RunEdit.stepOfSet(plan, 1)[0]);
        assertEquals(4, RunEdit.remainingStepsOfSet(plan, 1));
        assertEquals("Work hold 6/6", p.label);
        assertEquals("Work hold 1/6", plan.get(0).label);
        assertEquals("Step 2 of 6 at " + Model.Fmt.p(plan.get(1).up) + " · next: "
            + Model.Fmt.p(plan.get(2).up),
            RunLook.nowLineRamp(2, 6, Model.Fmt.p(plan.get(1).up), Model.Fmt.p(plan.get(2).up), "rest"));
    }

    @Test
    void theAddedStepKeepsTheCeilingAndTheDropFloor() {
        List<Model.Preset> plan = plan(3);
        plan.get(2).up = 44;                      // a top step planned over a lower ceiling
        plan.get(2).lo = 24;                      // and a drop carried past its highest
        RunEdit.addRampStep(plan, 0, 36);
        Model.Preset p = plan.get(3);
        assertEquals(36, p.up, "never past the ceiling");
        assertTrue(p.lo <= Math.max(RunEdit.DROP_FLOOR_KPA, 8), "the drop held to the floor");
        assertTrue(p.lo < p.up);
    }

    @Test
    void thePumpTakesAtMostNineSteps() {
        List<Model.Preset> plan = plan(8);
        assertNull(RunEdit.addStepRefusal(plan, 0));
        RunEdit.addRampStep(plan, 0, CEIL);
        assertEquals(9, RunEdit.stepOfSet(plan, 0)[1]);
        assertEquals("The pump takes at most 9 steps.", RunEdit.addStepRefusal(plan, 0));
        assertEquals(-1, RunEdit.addRampStep(plan, 0, CEIL), "refused: nothing added");
        assertEquals(9, RunEdit.stepOfSet(plan, 0)[1]);
    }

    @Test
    void theTwoHourStopIsKept() {
        List<Model.Preset> plan = plan(5);
        Model.Preset big = new Model.Preset();
        big.durMs = ComingSteps.CAP_MS - ComingSteps.totalMs(plan) - 60_000L;
        big.stageIdx = 3; big.setId = "Z"; big.up = 20; big.uh = 60; big.lh = 5;
        plan.add(big);
        assertTrue(RunEdit.addStepRefusal(plan, 0) != null, "a 2:12 step would pass the stop");
    }

    @Test
    void minusStepTakesOnlyAnAddedStepNotYetStarted() {
        List<Model.Preset> plan = plan(5);
        assertEquals("Only a step you added, not yet started, can be taken out.",
            RunEdit.removeStepRefusal(plan, 1));
        RunEdit.addRampStep(plan, 1, CEIL);
        RunEdit.addRampStep(plan, 1, CEIL);
        assertEquals(7, RunEdit.stepOfSet(plan, 1)[1]);
        assertNull(RunEdit.removeStepRefusal(plan, 1));
        assertEquals(6, RunEdit.removeAddedRampStep(plan, 1));
        assertEquals(6, RunEdit.stepOfSet(plan, 1)[1]);
        assertEquals("Work hold 6/6", plan.get(5).label);
        // The added step playing (the ramp's last): it is never taken out.
        assertEquals("The ramp's last step is playing — it can't be taken out.",
            RunEdit.removeStepRefusal(plan, 5));
        // Taken out, the plan is the ramp it was.
        RunEdit.removeAddedRampStep(plan, 1);
        assertEquals(5, RunEdit.stepOfSet(plan, 1)[1]);
        assertTrue(plan.get(5).rest);
    }

    @Test
    void theLastStepPlayingGivesTheRampsAverageTime() {
        List<Model.Preset> plan = plan(3);
        plan.get(2).durMs = 400_000L;                 // the step playing was lengthened
        long avg = (plan.get(0).durMs + plan.get(1).durMs) / 2;
        assertEquals(avg, RunEdit.addedStepMs(plan, 2), "the others' time per step");
        RunEdit.addRampStep(plan, 2, CEIL);
        assertEquals(avg, plan.get(3).durMs);
        assertEquals(1, RunEdit.remainingStepsOfSet(plan, 2), "one step now follows the one playing");
    }
}
