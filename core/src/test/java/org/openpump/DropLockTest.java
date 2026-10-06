package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * THE DROP CONTROLS LOCK ON WHAT A PRESET IS, NOT ON WHAT SOMEBODY DIALLED IT TO.
 *
 * Reported from a device: lowering DROP TIME to 0 during a run greyed out both drop rows
 * ("hold only"), and there was then no control left that could raise it again. The lock
 * read the live, edited values, so a legitimate 0 s drop looked exactly like a preset that
 * never drops. The rule now reads the preset as it was designed.
 *
 * AND REPORTED AGAIN, on the 0.9.0 review build: a manual run, "This set", DROP TIME − to 0,
 * and the drop cells went grey for the rest of the run. The lock read the RUN'S PLAN, and
 * the plan stops being the design once an edit writes into it: "This set" on a ramp shifts
 * every remaining step by the change (SetShift), so the next step arrived with lh = 0 and
 * read as a preset built without a drop. The Reshape and the step count write the tail the
 * same way. What a preset was BUILT as is now stamped where the plan is built (Model#plan),
 * and no edit writes it.
 */
class DropLockTest {

    private static final int CEIL = 40;

    /** A preset stamped the way Model#plan stamps every preset it builds. */
    private static Model.Preset preset(int up, int lo, int lh, boolean cyclePart) {
        Model.Preset p = new Model.Preset();
        p.up = up; p.lo = lo; p.uh = 20; p.lh = lh; p.sp = 50; p.cyclePart = cyclePart;
        return RunEdit.asBuilt(p);
    }

    @Test
    void aPresetThatDropsKeepsItsDropControlsWhateverTheLiveDropTime() {
        Model.Preset designed = preset(30, 10, 5, false);
        assertFalse(RunEdit.dropLocked(designed), "a dropping preset is never locked");
        // The live edit is carried elsewhere (carryLh); the design still drops, so the
        // controls that can bring the drop back must stay usable at 0 s.
        assertFalse(RunEdit.holdOnly(designed.lh, designed.lo, designed.up, designed.cyclePart));
    }

    @Test
    void aPresetBuiltWithoutADropStaysLocked() {
        assertTrue(RunEdit.dropLocked(preset(30, 29, 0, false)), "the prime: no drop dwell by design");
        assertTrue(RunEdit.dropLocked(preset(30, 29, 1, true)), "a stitched chunk: its drop is wire filler");
        assertTrue(RunEdit.dropLocked(preset(30, 30, 5, false)), "lo >= up is not a drop");
    }

    @Test
    void nothingPlayingMeansNothingToEdit() {
        assertTrue(RunEdit.dropLocked(null));
    }

    /* ------------------------------------------------ the lock after an edit (0.9.0) */

    /** A manual run of a three-step ramp, planned exactly as beginEphemeralRun plans it. */
    private static List<Model.Preset> manualRampPlan(int lh) {
        Model m = new Model();
        m.ceilKpa = CEIL;
        Model.Set src = Model.Set.ramp(Manual.ID, "Manual run", 34, 18, 60, lh, 75,
                                       38, 20, 60, lh, 75, 3, 540);
        m.adhoc.clear();
        m.adhoc.add(Manual.ephemeral(src, m.ceilKpa));
        return m.plan(Manual.routine(m.ceilKpa));
    }

    /** A routine (not a manual run) whose one work stage is the same ramp. */
    private static List<Model.Preset> routineRampPlan() {
        Model m = new Model();
        m.ceilKpa = CEIL;
        m.sets.add(Model.Set.ramp("s1", "Climb", 34, 18, 60, 5, 75, 38, 20, 60, 5, 75, 3, 540));
        Model.Routine r = new Model.Routine();
        r.id = "r1";
        r.name = "Routine";
        r.stages.add(Model.Stage.of("Main", Model.STAGE_WORK, new String[]{ "s1" }));
        return m.plan(r);
    }

    /** What the run screen does for DROP TIME − to 0 in "This set" on step 1 of a ramp:
     *  the carry takes the step playing to 0 s and the rest of the ramp shifts with it
     *  (SessionActivity#shiftRemainingOfSet). */
    private static void dropTimeToZeroThisSet(List<Model.Preset> plan) {
        int[] before = SetShift.tuple(plan.get(0));
        int[] after = before.clone();
        after[LiveEdit.LH] = 0;
        new SetShift().shift(plan, 0, RunEdit.remainingStepsOfSet(plan, 0), before, after, CEIL);
    }

    @Test
    void aDropDialledToZeroInThisSetLeavesTheRestOfTheRampAdjustable() {
        List<Model.Preset> plan = manualRampPlan(5);
        assertEquals(3, plan.size());
        assertFalse(RunEdit.dropLocked(plan.get(1)), "the ramp was built with a drop");
        dropTimeToZeroThisSet(plan);
        // What is commanded is what was dialled: the next steps hold straight through...
        assertEquals(0, plan.get(1).lh);
        assertEquals(0, plan.get(2).lh);
        // ...and they are still presets that were BUILT with a drop, so the chips that can
        // bring it back stay live when they play (the device report: they went grey).
        assertFalse(RunEdit.dropLocked(plan.get(1)), "step 2 locked after This set took lh to 0");
        assertFalse(RunEdit.dropLocked(plan.get(2)), "step 3 locked after This set took lh to 0");
    }

    @Test
    void theSameInARoutine() {
        List<Model.Preset> plan = routineRampPlan();
        dropTimeToZeroThisSet(plan);
        assertEquals(0, plan.get(1).lh);
        assertFalse(RunEdit.dropLocked(plan.get(1)), "a routine's ramp locked the same way");
    }

    @Test
    void theDropComesBackUpAsARealDropOnTheWire() {
        List<Model.Preset> plan = manualRampPlan(5);
        dropTimeToZeroThisSet(plan);
        Model.Preset next = plan.get(1);
        // Drop time 0 on the wire is an ordinary preset: the real drop pressure and a 0 s
        // dwell - not the stitched chunk's filler (lo = up − 1, lh = 1).
        int up = RunEdit.clampUpper(next.up, CEIL);
        int lo = RunEdit.clampLower(next.lo, up);
        assertTrue(lo < up - 1, "the drop pressure is the ramp's own, not filler");
        byte[] zero = Proto.addPreset(next.sp, up, next.uh, lo, next.lh);
        assertEquals(lo, zero[6], "lower setpoint: the real drop");
        assertEquals(0, zero[7], "lower hold: 0 s");
        // The + on that step, as nudgeOverride makes it: not refused, one step up
        // (LiveEdit.SECONDS_STEP), the drop pressure untouched - so the write that follows
        // carries a real drop again.
        int[] inForce = SetShift.tuple(next);
        int[] work = inForce.clone();
        int r = new LiveEdit().tap(work, LiveEdit.LH, +1, inForce, CEIL,
                                   RunEdit.dropLocked(next), 1, 0L);
        assertEquals(LiveEdit.MOVED, r, "the + was refused as hold only");
        assertEquals(LiveEdit.SECONDS_STEP, work[LiveEdit.LH]);
        assertEquals(lo, work[LiveEdit.LO]);
        int wUp = RunEdit.clampUpper(work[LiveEdit.UP], CEIL);
        byte[] back = Proto.addPreset(work[LiveEdit.SP], wUp, work[LiveEdit.UH],
                                      RunEdit.clampLower(work[LiveEdit.LO], wUp),
                                      work[LiveEdit.LH]);
        assertEquals(lo, back[6], "lower setpoint: the same real drop");
        assertEquals(LiveEdit.SECONDS_STEP, back[7], "lower hold: back above 0 s");
    }

    @Test
    void theReshapeAndTheStepCountDoNotLockItEither() {
        // Reshape: the END's drop time to 0, anchored on a carry already at 0.
        List<Model.Preset> plan = manualRampPlan(5);
        Model.Preset cur = plan.get(0);
        Model.Preset end = plan.get(2);
        RunEdit.reshapeRemaining(plan, 0, cur.up, cur.lo, cur.uh, 0, cur.sp,
                                 end.up, end.lo, end.uh, 0, end.sp, CEIL);
        assertEquals(0, plan.get(1).lh);
        assertFalse(RunEdit.dropLocked(plan.get(1)), "a reshape to 0 s locked the drop");
        assertFalse(RunEdit.dropLocked(plan.get(2)), "a reshape to 0 s locked the drop");
        // Step count: the new steps start from a carry at 0 s, after This set took the tail
        // there too.
        plan = manualRampPlan(5);
        cur = plan.get(0);
        dropTimeToZeroThisSet(plan);
        assertEquals(2, RunEdit.resizeRemaining(plan, 0, cur.up, cur.lo, cur.uh, 0, cur.sp,
                                                4, CEIL));
        assertEquals(0, plan.get(1).lh);
        for (int k = 1; k < plan.size(); k++)
            assertFalse(RunEdit.dropLocked(plan.get(k)), "a recounted step " + k + " locked");
    }

    @Test
    void whatWasBuiltWithoutADropStaysLockedWhateverAnEditWrites() {
        // A ramp built with no drop dwell at all is a hold-only set by design.
        List<Model.Preset> plan = manualRampPlan(0);
        for (int k = 0; k < plan.size(); k++)
            assertTrue(RunEdit.dropLocked(plan.get(k)), "built hold-only, step " + k);
        // A pull + in This set moves the tail's figures - not what they were built as.
        int[] before = SetShift.tuple(plan.get(0));
        int[] after = before.clone();
        after[LiveEdit.UP] += 1;
        new SetShift().shift(plan, 0, 2, before, after, CEIL);
        assertTrue(RunEdit.dropLocked(plan.get(1)), "a pull + unlocked a built hold");
        // A recount of it stays hold-only too.
        Model.Preset c = plan.get(0);
        RunEdit.resizeRemaining(plan, 0, c.up, c.lo, c.uh, c.lh, c.sp, 5, CEIL);
        for (int k = 1; k < plan.size(); k++)
            assertTrue(RunEdit.dropLocked(plan.get(k)), "a recounted hold-only step " + k);
        // A stitched long hold: every 255 s chunk is filler; the last one carries the drop.
        Model m = new Model();
        m.ceilKpa = CEIL;
        m.adhoc.clear();
        m.adhoc.add(Manual.ephemeral(Model.Set.fixed(Manual.ID, "Manual run",
                                                     30, 12, 600, 5, 75, 605), CEIL));
        List<Model.Preset> st = m.plan(Manual.routine(CEIL));
        assertEquals(3, st.size());
        assertTrue(RunEdit.dropLocked(st.get(0)), "a stitch chunk");
        assertTrue(RunEdit.dropLocked(st.get(1)), "a stitch chunk");
        assertFalse(RunEdit.dropLocked(st.get(2)), "the chunk carrying the drop");
    }
}
