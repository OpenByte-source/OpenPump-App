package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * REJOIN AFTER A PROCESS DEATH, IN THE RUN AS IT WAS EDITED (device check EMU9b N1).
 *
 * The run's plan is the routine rebuilt and then edited "this run only" - Coming steps' skips
 * and the Skip button's - so the index the run was on is counted in a SHORTER list than the
 * rebuild. Applied to the rebuild, it named a step the person had taken out ("Fatigue hold ·
 * climb", skipped), and the dialog's "step 7 of 13" counted the unedited plan. The snapshot
 * now carries the step's identity in the as-built plan and the skips, and the rebuild is put
 * back into the shape the run had before the index is read.
 */
class RunRejoinTest {

    private static Model.Preset work(int stage, int pos, String setId, int up, long durMs,
                                     String label) {
        Model.Preset p = new Model.Preset();
        p.up = up; p.lo = 3; p.uh = 60; p.lh = 5; p.sp = 60;
        p.durMs = durMs;
        p.stageIdx = stage; p.pos = pos; p.setId = setId;
        p.label = label;
        return p;
    }

    private static Model.Preset rest(int stage, int sec) {
        Model.Preset p = new Model.Preset();
        p.rest = true;
        p.durMs = sec * 1000L;
        p.stageIdx = stage;
        p.label = "Rest";
        return p;
    }

    /** As built from the routine: Warm-up (3 steps) · Fatigue hold (a 3-step climb, then the
     *  block) · Rest · Work (sets 1–5, sets 6–10). Ten presets, the shape on the device. */
    private static List<Model.Preset> built() {
        List<Model.Preset> p = new ArrayList<Model.Preset>();
        for (int k = 0; k < 3; k++) p.add(work(0, 0, "warm", 10 + 3 * k, 75_000L, "Warm-up " + (k + 1) + "/3"));
        for (int k = 0; k < 3; k++) p.add(work(1, 0, "climb", 12 + 3 * k, 70_000L, "Fatigue hold · climb " + (k + 1) + "/3"));
        p.add(work(1, 1, "fat", 20, 325_000L, "Fatigue hold"));
        p.add(rest(2, 180));
        p.add(work(3, 0, "w1", 20, 325_000L, "Work 1"));
        p.add(work(3, 1, "w2", 20, 325_000L, "Work 2"));
        return p;
    }

    /** The run's own copy, stamped as beginRunFlow stamps it. */
    private static List<Model.Preset> run() {
        List<Model.Preset> p = built();
        RunRejoin.stamp(p);
        return p;
    }

    /** What a cold rejoin does: the routine rebuilt, then the snapshot applied. */
    private static RunRejoin.Rebuilt rejoin(RunRejoin.Spot s, List<Model.Preset> into) {
        into.addAll(built());
        RunRejoin.stamp(into);
        return RunRejoin.apply(into, s);
    }

    /** The snapshot written to disk and read back (SessionActivity#saveRunSnapshot). */
    private static RunRejoin.Spot throughDisk(RunRejoin.Spot s) {
        return RunRejoin.Spot.read(s.legacyIdx, s.src, s.ahead, s.block, s.built,
            s.skippedCsv(), s.takenCsv());
    }

    @Test
    void theDeviceCaseResumesTheStepThatWasPlayingAndKeepsTheSkips() {
        List<Model.Preset> plan = run();
        int built = plan.size();
        java.util.Map<Integer, List<Model.Preset>> skipped =
            new java.util.HashMap<Integer, List<Model.Preset>>();
        Set<Integer> taken = new LinkedHashSet<Integer>();
        int planIdx = 0;
        // In the warm-up, Coming steps skips the climb...
        int climb = ComingSteps.key(1, 0);
        skipped.put(Integer.valueOf(climb), ComingSteps.removeStage(plan, climb, planIdx));
        // ...then Skip warm-up takes the rest of the warm-up out and moves on (skipRestOf)...
        List<Model.Preset> out = new ArrayList<Model.Preset>();
        out.add(plan.remove(planIdx + 1));
        out.add(plan.remove(planIdx + 1));
        RunRejoin.took(taken, out);
        planIdx++;                                   // the Fatigue hold block
        assertEquals("Fatigue hold", plan.get(planIdx).label);
        planIdx++;                                   // its Skip: the rest
        planIdx++;                                   // the rest ends: Work, sets 1–5
        Model.Preset playing = plan.get(planIdx);
        assertEquals("Work 1", playing.label);
        int stepOfRun = planIdx + 1, ofRun = plan.size();

        // The process dies here; the snapshot is all that is left.
        RunRejoin.Spot s = throughDisk(RunRejoin.of(plan, planIdx, built, skipped.keySet(), taken));

        List<Model.Preset> again = new ArrayList<Model.Preset>();
        RunRejoin.Rebuilt rb = rejoin(s, again);
        assertTrue(rb.idx >= 0, "the step is found");
        assertEquals("Work 1", again.get(rb.idx).label, "resumes the step that was playing");
        assertEquals(playing.src, again.get(rb.idx).src);
        // The skipped climb stays skipped - and is held as skipped, so Coming steps can put
        // it back - and the warm-up steps the Skip took out are not in the plan either.
        for (int i = 0; i < again.size(); i++) {
            assertFalse(ComingSteps.inBlock(again.get(i), climb), "the skipped climb never replays");
            assertFalse(taken.contains(Integer.valueOf(again.get(i).src)), "a skipped warm-up step is out");
        }
        assertTrue(rb.skipped.containsKey(Integer.valueOf(climb)));
        assertEquals(3, rb.skipped.get(Integer.valueOf(climb)).size());
        // "step X of Y" is counted in the plan the rejoin plays - the run's own.
        assertEquals(stepOfRun, rb.idx + 1);
        assertEquals(ofRun, rb.size);
        assertEquals(ofRun, again.size());
    }

    @Test
    void aLaterBlockSkippedInComingStepsStaysSkipped() {
        List<Model.Preset> plan = run();
        java.util.Map<Integer, List<Model.Preset>> skipped =
            new java.util.HashMap<Integer, List<Model.Preset>>();
        int planIdx = 6;                              // the Fatigue hold block playing
        int w2 = ComingSteps.key(3, 1);
        skipped.put(Integer.valueOf(w2), ComingSteps.removeStage(plan, w2, planIdx));
        planIdx = 8;                                  // advanced to Work, sets 1–5
        RunRejoin.Spot s = throughDisk(RunRejoin.of(plan, planIdx, 10, skipped.keySet(),
            new LinkedHashSet<Integer>()));
        List<Model.Preset> again = new ArrayList<Model.Preset>();
        RunRejoin.Rebuilt rb = rejoin(s, again);
        assertEquals("Work 1", again.get(rb.idx).label);
        assertEquals(rb.idx + 1, again.size(), "nothing after it: sets 6–10 were skipped");
        assertEquals(9, rb.size);
        assertTrue(rb.skipped.containsKey(Integer.valueOf(w2)));
    }

    @Test
    void anUneditedRunRejoinsExactlyAsBefore() {
        List<Model.Preset> plan = run();
        for (int idx = 0; idx < plan.size(); idx++) {
            RunRejoin.Spot s = throughDisk(RunRejoin.of(plan, idx, plan.size(),
                new ArrayList<Integer>(), new LinkedHashSet<Integer>()));
            List<Model.Preset> again = new ArrayList<Model.Preset>();
            RunRejoin.Rebuilt rb = rejoin(s, again);
            assertEquals(idx, rb.idx, "the same index");
            assertEquals(10, rb.size);
            assertEquals(10, again.size(), "nothing taken out");
            assertTrue(rb.skipped.isEmpty());
        }
    }

    @Test
    void anOldSnapshotKeepsItsOldReading() {
        // Written before the step's identity was: only the index (Migrate covers the keys).
        RunRejoin.Spot s = RunRejoin.Spot.read(4, -1, 0, -1, -1, "", "");
        assertFalse(s.known());
        List<Model.Preset> again = new ArrayList<Model.Preset>();
        RunRejoin.Rebuilt rb = rejoin(s, again);
        assertEquals(4, rb.idx);
        assertEquals(10, rb.size);
    }

    @Test
    void aRoutineEditedSinceFallsBackToTheIndex() {
        List<Model.Preset> plan = run();
        RunRejoin.Spot s = RunRejoin.of(plan, 3, 12, new ArrayList<Integer>(),
            new LinkedHashSet<Integer>());   // built from a 12-preset routine; now 10
        List<Model.Preset> again = new ArrayList<Model.Preset>();
        RunRejoin.Rebuilt rb = rejoin(s, again);
        assertEquals(3, rb.idx);
        assertEquals(10, again.size());
    }

    @Test
    void undoPutsTheSkippedStepsBackInTheRecord() {
        List<Model.Preset> plan = run();
        Set<Integer> taken = new LinkedHashSet<Integer>();
        List<Model.Preset> out = new ArrayList<Model.Preset>();
        out.add(plan.remove(1));
        out.add(plan.remove(1));
        RunRejoin.took(taken, out);
        assertEquals(2, taken.size());
        RunRejoin.gaveBack(taken, out);
        assertTrue(taken.isEmpty());
        // Undo's copies of a step carry its identity (RunEdit#copyPreset).
        Model.Preset c = RunEdit.copyPreset(plan.get(0), 1000L);
        assertEquals(plan.get(0).src, c.src);
    }

    @Test
    void aStepAnEditBuiltIsFoundByItsPlaceInItsBlock() {
        // A ramp's recounted step has no as-built identity: it is read as the steps past the
        // last one that has, in the same block.
        List<Model.Preset> plan = run();
        Model.Preset extra = RunEdit.copyPreset(plan.get(4), 70_000L);
        extra.src = -1;
        plan.add(5, extra);                         // climb 1, climb 2, [built], climb 3
        RunRejoin.Spot s = throughDisk(RunRejoin.of(plan, 5, 10, new ArrayList<Integer>(),
            new LinkedHashSet<Integer>()));
        List<Model.Preset> again = new ArrayList<Model.Preset>();
        RunRejoin.Rebuilt rb = rejoin(s, again);
        assertEquals(5, rb.idx, "one past climb 2: climb 3, still in the climb");
        assertSame(again.get(5), again.get(rb.idx));
        assertTrue(ComingSteps.inBlock(again.get(rb.idx), ComingSteps.key(1, 0)));
    }
}
