package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * R11-2 - THE RUN SCREEN'S TOP BAR IS THE RUN ACTUALLY PLAYING: one part per set, the set
 * playing highlighted, the fill where the clock is. The status line said "set 7 of 8" and the
 * clock 19:09 of 22:57 while the bar's last block showed 2 of 5 parts filled: its parts were
 * counted from the presets as written, not from the sets the run counts (a change in force
 * moves a block's cycle; the day's own build trims sets). The bar now takes the run's own
 * count of each step's sets, so "set N of M" is always the Nth part.
 */
class RunBarTest {

    private static Model.Stage stage(String name, int colour) {
        return Model.Stage.of(name, colour, new String[0]);
    }

    private static Model.Preset work(int stage, int uh, int lh, int sets) {
        Model.Preset p = new Model.Preset();
        p.up = 27; p.lo = 3; p.uh = uh; p.lh = lh; p.sp = 60;
        p.durMs = (long) sets * (uh + lh) * 1000L;
        p.stageIdx = stage;
        p.label = "Work";
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

    /** Warm-up, five sets, a 3-minute rest, five sets. */
    private static List<Model.Stage> stages() {
        List<Model.Stage> s = new ArrayList<Model.Stage>();
        s.add(stage("Warm-up", Model.STAGE_WARM));
        s.add(stage("Work", Model.STAGE_WORK));
        s.add(Model.Stage.restOf("Rest", 180));
        s.add(stage("Work", Model.STAGE_WORK));
        return s;
    }

    private static List<Model.Preset> plan() {
        List<Model.Preset> p = new ArrayList<Model.Preset>();
        Model.Preset w = work(0, 30, 5, 1);
        w.durMs = 365_000L;
        p.add(w);
        p.add(work(1, 120, 5, 5));
        p.add(rest(2, 180));
        p.add(work(3, 120, 5, 5));
        return p;
    }

    /** What the run counts for each step, as the run screen hands it over: RunLook#setsIn
     *  for steps that cycle in a work-kind stage, 0 for the rest and the warm-up. */
    private static int[] counted(List<Model.Stage> st, List<Model.Preset> p) {
        int[] n = new int[p.size()];
        for (int i = 0; i < n.length; i++) {
            int k = p.get(i).rest ? RunLook.REST : RunLook.kindOf(st.get(p.get(i).stageIdx));
            n[i] = k == RunLook.WORK || k == RunLook.FATIGUE || k == RunLook.TRACTION
                ? RunLook.setsIn(p.get(i)) : 0;
        }
        return n;
    }

    private static int[] kinds(List<Model.Stage> st, List<Model.Preset> p) {
        int[] k = new int[p.size()];
        for (int i = 0; i < k.length; i++)
            k[i] = p.get(i).rest ? RunLook.REST : RunLook.kindOf(st.get(p.get(i).stageIdx));
        return k;
    }

    /** The work parts of the bar, in order, skipped ones left out: what "set N of M" counts. */
    private static List<StageBar.Segment> workParts(List<StageBar.Segment> b) {
        List<StageBar.Segment> out = new ArrayList<StageBar.Segment>();
        for (int i = 0; i < b.size(); i++)
            if (b.get(i).kind == RunLook.WORK && !b.get(i).skipped && b.get(i).set > 0)
                out.add(b.get(i));
        return out;
    }

    private static int currentIn(List<StageBar.Segment> parts) {
        for (int i = 0; i < parts.size(); i++) if (parts.get(i).current) return i;
        return -1;
    }

    @Test
    void setNOfMIsTheNthPart() {
        List<Model.Stage> st = stages();
        List<Model.Preset> p = plan();
        int[] sets = counted(st, p);
        // Playing the second block's set 2, 40 % into it.
        int[] nm = StageBar.setNumber(kinds(st, p), new boolean[p.size()], sets, 3, 2, 5);
        assertArrayEquals(new int[] { 7, 10 }, nm);
        List<StageBar.Segment> b = StageBar.buildLive(st, p, 3, 175_000L, sets, 2, 0.4f,
            null, null, null);
        List<StageBar.Segment> w = workParts(b);
        assertEquals(10, w.size(), "one part per set");
        assertEquals(nm[0] - 1, currentIn(w), "set 7 is the 7th part");
        for (int i = 0; i < 6; i++) assertEquals(1f, w.get(i).fill, 1e-6, "part " + i + " done");
        assertEquals(0.4f, w.get(6).fill, 1e-6, "the set playing fills with its clock");
        for (int i = 7; i < 10; i++) assertEquals(0f, w.get(i).fill, 1e-6);
        assertEquals(125_000L, w.get(0).weightMs, "a set's part is its share of the block");
        // The warm-up and the rest stay one part each, the rest done.
        assertEquals(RunLook.WARM, b.get(0).kind);
        assertEquals(1f, b.get(0).fill, 1e-6);
        assertEquals(12, b.size(), "warm-up, five parts, rest, five parts");
    }

    @Test
    void theFillMatchesTheClock() {
        List<Model.Stage> st = stages();
        List<Model.Preset> p = plan();
        List<StageBar.Segment> b = StageBar.buildLive(st, p, 3, 175_000L, counted(st, p), 2,
            0.4f, null, null, null);
        long total = 0, done = 0;
        for (int i = 0; i < b.size(); i++) {
            total += b.get(i).weightMs;
            done += (long) (b.get(i).weightMs * b.get(i).fill);
        }
        long clockDone = 365_000L + 625_000L + 180_000L + 125_000L + 50_000L;
        assertEquals((double) clockDone / total, (double) done / total, 0.001);
    }

    @Test
    void anAdjustedBlockIsDrawnWithTheSetsTheRunCounts() {
        // A change in force made the second block's cycle longer: the run counts 4 sets in it,
        // not the 5 its preset was written with. The bar follows the run.
        List<Model.Stage> st = stages();
        List<Model.Preset> p = plan();
        int[] sets = counted(st, p);
        sets[3] = 4;
        int[] nm = StageBar.setNumber(kinds(st, p), new boolean[p.size()], sets, 3, 3, 4);
        assertArrayEquals(new int[] { 8, 9 }, nm);
        List<StageBar.Segment> w = workParts(StageBar.buildLive(st, p, 3, 400_000L, sets, 3,
            0.2f, null, null, null));
        assertEquals(9, w.size());
        assertEquals(nm[0] - 1, currentIn(w));
        assertEquals(156_250L, w.get(5).weightMs, "the block's time over the sets it now has");
    }

    @Test
    void aTrimmedRoutineHasOnlyTheSetsItRuns() {
        // The day's build took two sets out of the last block ("Routine · adjusted"): the plan
        // the run holds has three, and the bar has three parts there.
        List<Model.Stage> st = stages();
        List<Model.Preset> p = plan();
        p.set(3, work(3, 120, 5, 3));
        int[] sets = counted(st, p);
        int[] nm = StageBar.setNumber(kinds(st, p), new boolean[p.size()], sets, 3, 3, 3);
        assertArrayEquals(new int[] { 8, 8 }, nm);
        List<StageBar.Segment> w = workParts(StageBar.buildLive(st, p, 3, 300_000L, sets, 3,
            0.5f, null, null, null));
        assertEquals(8, w.size());
        assertEquals(7, currentIn(w), "set 8 of 8 is the last part");
    }

    /** One stage of three blocks (a hold, then two blocks of sets), after a warm-up. */
    private static List<Model.Preset> threeBlocks() {
        List<Model.Preset> p = new ArrayList<Model.Preset>();
        Model.Preset w = work(0, 30, 5, 1);
        w.durMs = 300_000L;
        p.add(w);
        for (int pos = 0; pos < 3; pos++) {
            Model.Preset b = work(1, 120, 5, pos == 0 ? 1 : 5);
            b.pos = pos;
            p.add(b);
        }
        p.add(rest(2, 180));
        p.add(work(3, 120, 5, 5));
        return p;
    }

    @Test
    void aSkippedBlockStaysHatchedAndTheCountGoesOnPastIt() {
        List<Model.Stage> st = stages();
        List<Model.Preset> p = threeBlocks();
        java.util.Map<Integer, List<Model.Preset>> sk =
            new java.util.HashMap<Integer, List<Model.Preset>>();
        sk.put(Integer.valueOf(ComingSteps.key(1, 1)),
               ComingSteps.removeStage(p, ComingSteps.key(1, 1), 0));
        // p is now warm-up, the hold, the last block of sets, rest, work. Set 3 of that block.
        int[] sets = counted(st, p);
        int[] nm = StageBar.setNumber(kinds(st, p), new boolean[p.size()], sets, 2, 3, 5);
        assertArrayEquals(new int[] { 4, 11 }, nm);
        List<StageBar.Segment> b = StageBar.buildLive(st, p, 2, 300_000L, sets, 3, 0.5f,
            null, null, sk);
        int hatched = 0;
        for (int i = 0; i < b.size(); i++) if (b.get(i).skipped) hatched++;
        assertEquals(1, hatched, "the skipped block is one hatched part, where it sits");
        assertTrue(b.get(2).skipped, "after the hold, before the sets that still run");
        assertEquals(625_000L, b.get(2).weightMs);
        List<StageBar.Segment> w = workParts(b);
        assertEquals(11, w.size());
        assertEquals(nm[0] - 1, currentIn(w));
        assertFalse(b.get(2).current);
    }

    @Test
    void nextIsTheNextStepNotTheNextSet() {
        List<Model.Stage> st = stages();
        List<Model.Preset> p = plan();
        List<StageBar.Segment> b = StageBar.buildLive(st, p, 1, 130_000L, counted(st, p), 2,
            0.04f, null, null, null);
        int[] nn = StageBar.nowAndNext(b);
        assertTrue(b.get(nn[0]).current);
        assertEquals(RunLook.REST, b.get(nn[1]).kind, "next: the rest, not set 3");
    }

    @Test
    void aRampAndAStepThatDoesNotCycleStayWhole() {
        List<Model.Stage> st = new ArrayList<Model.Stage>();
        st.add(stage("Work", Model.STAGE_WORK));
        st.add(stage("Ramp", Model.STAGE_WORK));
        List<Model.Preset> p = new ArrayList<Model.Preset>();
        p.add(work(0, 60, 5, 3));
        for (int k = 0; k < 4; k++) {
            Model.Preset r = work(1, 20, 5, 1);
            r.durMs = 40_000L;
            p.add(r);
        }
        int[] sets = counted(st, p);
        boolean[] ramp = new boolean[p.size()];
        for (int i = 1; i < p.size(); i++) { ramp[i] = true; sets[i] = 0; }
        List<StageBar.Segment> b = StageBar.buildLive(st, p, 2, 10_000L, sets, 0, 0f,
            null, null, null);
        assertEquals(4, b.size(), "three set parts and the ramp as one");
        assertTrue(b.get(3).current);
        assertEquals(0.3125f, b.get(3).fill, 1e-6, "the ramp fills by time as before");
        assertArrayEquals(new int[] { 3, 3 }, StageBar.setNumber(kinds(st, p), ramp, sets, 0, 3, 3));
    }
}
