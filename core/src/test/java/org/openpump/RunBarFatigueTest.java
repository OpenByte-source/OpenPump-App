package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FIX11 F2 / F3 / F4 (device check EMU12) - the run bar and "set N of M" in the fatigue block,
 * after "Adjust the running set › Rest of this block", and after Skip.
 *
 * F2: the fatigue block is a two-hold climb (built as a ramp) and thirteen holds. The status
 * counted the thirteen ("SET 1 OF 13") while the bar drew fifteen parts, so set 1 filled the
 * third part. F3: after the rest of the block went from 30 s to 46 s holds, the bar re-ticked
 * to 18 parts and its fill, by time over the longer block, went backwards. Both came from one
 * step in the block with no set count (the climb), which sent the whole block back to the
 * static bar's ticks. F4: Skip these sets drew the block as done, and a skipped warm-up solid.
 */
class RunBarFatigueTest {

    private static Model.Preset step(int stage, int uh, int lh, long durMs) {
        Model.Preset p = new Model.Preset();
        p.up = 27; p.lo = 5; p.uh = uh; p.lh = lh; p.sp = 75;
        p.durMs = durMs;
        p.stageIdx = stage;
        p.label = "Step";
        return p;
    }

    /** Warm-up, the fatigue block (2-hold climb + 13 holds of 30 s), a rest, a work block. */
    private static List<Model.Stage> stages() {
        List<Model.Stage> s = new ArrayList<Model.Stage>();
        s.add(Model.Stage.of("Warm-up", Model.STAGE_WARM, new String[0]));
        Model.Stage f = Model.Stage.of("Fatigue block", Model.STAGE_WORK, new String[0]);
        f.fatigueBlock = true;
        s.add(f);
        s.add(Model.Stage.restOf("Rest", 180));
        s.add(Model.Stage.of("Work 1", Model.STAGE_WORK, new String[0]));
        return s;
    }

    private static List<Model.Preset> plan() {
        List<Model.Preset> p = new ArrayList<Model.Preset>();
        p.add(step(0, 30, 5, 365_000L));          // the warm-up, one step
        p.add(step(1, 30, 5, 35_000L));           // climb, hold 1
        p.add(step(1, 30, 5, 35_000L));           // climb, hold 2
        p.add(step(1, 30, 5, 13 * 35_000L));      // 13 holds
        Model.Preset r = step(2, 0, 0, 180_000L);
        r.rest = true;
        p.add(r);
        p.add(step(3, 120, 5, 5 * 125_000L));
        return p;
    }

    private static int[] kinds(List<Model.Stage> st, List<Model.Preset> p) {
        int[] k = new int[p.size()];
        for (int i = 0; i < k.length; i++)
            k[i] = p.get(i).rest ? RunLook.REST : RunLook.kindOf(st.get(p.get(i).stageIdx));
        return k;
    }

    private static int[] stageOf(List<Model.Preset> p) {
        int[] s = new int[p.size()];
        for (int i = 0; i < s.length; i++) s[i] = p.get(i).stageIdx;
        return s;
    }

    /** As the run screen counts them (RunScreen#countSteps): the fatigue climb's holds are
     *  the block's sets; the warm-up and the rest are not counted in sets. */
    private static int[] counted(List<Model.Stage> st, List<Model.Preset> p) {
        int[] k = kinds(st, p), n = new int[p.size()];
        for (int i = 0; i < n.length; i++)
            n[i] = k[i] == RunLook.WORK || k[i] == RunLook.FATIGUE ? RunLook.setsIn(p.get(i)) : 0;
        return n;
    }

    private static List<StageBar.Segment> group(List<StageBar.Segment> b, int stage) {
        List<StageBar.Segment> out = new ArrayList<StageBar.Segment>();
        for (int i = 0; i < b.size(); i++) if (b.get(i).stage == stage) out.add(b.get(i));
        return out;
    }

    private static int current(List<StageBar.Segment> parts) {
        for (int i = 0; i < parts.size(); i++) if (parts.get(i).current) return i;
        return -1;
    }

    private static double doneShare(List<StageBar.Segment> parts) {
        double total = 0, done = 0;
        for (int i = 0; i < parts.size(); i++) {
            total += parts.get(i).weightMs;
            done += parts.get(i).weightMs * (double) parts.get(i).fill;
        }
        return done / total;
    }

    @Test
    void theFatigueBlockCountsItsClimbHoldsSoSetNIsTheNthPart() {
        List<Model.Stage> st = stages();
        List<Model.Preset> p = plan();
        int[] sets = counted(st, p);
        boolean[] ramp = new boolean[p.size()];    // the fatigue climb is counted in sets
        // The first of the thirteen holds: set 3 of 15, as the notice's "15 holds" counts.
        int[] nm = StageBar.setNumber(kinds(st, p), ramp, sets, stageOf(p), 3, 1, 13);
        assertArrayEquals(new int[] { 3, 15 }, nm);
        List<StageBar.Segment> f = group(StageBar.buildLive(st, p, 3, 10_000L, sets, 1, 0.3f,
            null, null, null), 1);
        assertEquals(15, f.size(), "fifteen parts");
        assertEquals(nm[0] - 1, current(f), "set 3 is the 3rd part");
        assertEquals(1f, f.get(0).fill, 1e-6);
        assertEquals(1f, f.get(1).fill, 1e-6);
        // While the climb plays: set 1 of 15, the 1st part.
        assertArrayEquals(new int[] { 1, 15 },
            StageBar.setNumber(kinds(st, p), ramp, sets, stageOf(p), 1, 1, 1));
        List<StageBar.Segment> c = group(StageBar.buildLive(st, p, 1, 5_000L, sets, 1, 0.1f,
            null, null, null), 1);
        assertEquals(15, c.size());
        assertEquals(0, current(c));
    }

    @Test
    void aStepWithNoSetCountIsOnePartNotTheWholeBlockByTicks() {
        // A work block's own climb is still steps (RAMP · STEP 1 OF 2): it is one part of the
        // block, and the rest of the block is still drawn set by set.
        List<Model.Stage> st = stages();
        List<Model.Preset> p = plan();
        int[] sets = counted(st, p);
        sets[1] = 0;
        sets[2] = 0;
        List<StageBar.Segment> f = group(StageBar.buildLive(st, p, 3, 10_000L, sets, 1, 0.3f,
            null, null, null), 1);
        assertEquals(15, f.size(), "two step parts and thirteen set parts");
        assertEquals(0, f.get(0).set);
        assertEquals(1, f.get(2).set);
        assertEquals(2, current(f));
    }

    @Test
    void afterRestOfThisBlockTheCountAndTheBarStayOneAndTheFillNeverGoesBack() {
        // "Adjust the running set › Rest of this block": 30 s → 46 s holds. The block keeps its
        // 13 sets (SetClock) and its end moves out: the preset is longer, its own cycle as
        // written. The bar reads the run's count, so it is still 15 parts, as the status says.
        List<Model.Stage> st = stages();
        List<Model.Preset> p = plan();
        int[] sets = counted(st, p);
        p.get(3).durMs = 6 * 35_000L + 7 * 51_000L;  // 6 sets run at 30 s, 7 left at 46 s
        sets[3] = 13;                                // the clock's count, as RunScreen passes it
        boolean[] ramp = new boolean[p.size()];
        double last = -1;
        int[][] at = { { 7, 50 }, { 8, 20 }, { 9, 10 }, { 9, 90 }, { 13, 99 } };
        for (int t = 0; t < at.length; t++) {
            int k = at[t][0];
            int[] nm = StageBar.setNumber(kinds(st, p), ramp, sets, stageOf(p), 3, k, 13);
            List<StageBar.Segment> f = group(StageBar.buildLive(st, p, 3, 0L, sets, k,
                at[t][1] / 100f, null, null, null), 1);
            assertEquals(15, f.size(), "the block keeps its parts");
            assertEquals(nm[1], f.size(), "\"of M\" is the bar's parts");
            assertEquals(nm[0] - 1, current(f), "set " + nm[0] + " is its part");
            double done = doneShare(f);
            assertTrue(done > last, "the fill never goes backwards (" + done + " after " + last + ")");
            last = done;
        }
    }

    @Test
    void skipTheseSetsHatchesTheSetsItSkippedNotDone() {
        // Skip these sets at set 8 of the 13, 20 s into it: Skip writes the step's length down to
        // what it delivered, and the run plays the rest. The 5 sets not run are hatched.
        List<Model.Stage> st = stages();
        List<Model.Preset> p = plan();
        int[] sets = counted(st, p);
        long planned = p.get(3).durMs, delivered = 7 * 35_000L + 20_000L;
        p.get(3).durMs = delivered;
        sets[3] = 13;                                // its count from when it started
        long[] cut = new long[p.size()];
        cut[3] = planned - delivered;
        List<StageBar.Segment> b = StageBar.buildLive(st, p, 4, 10_000L, sets, 0, 0f,
            null, null, null, cut);
        List<StageBar.Segment> f = group(b, 1);
        assertEquals(15, f.size(), "the parts keep their planned size and number");
        int full = 0, hatched = 0;
        for (int i = 0; i < f.size(); i++) {
            if (f.get(i).skipped) { hatched++; assertEquals(0f, f.get(i).fill, 1e-6); }
            else if (f.get(i).fill >= 1f - 1e-6) full++;
        }
        assertEquals(5, hatched, "sets 9 to 13 hatched");
        assertEquals(9, full, "the climb's 2 and sets 1 to 7");
        assertEquals(20f / 35f, f.get(9).fill, 1e-3, "set 8 as far as it ran");
        assertEquals(35_000L, f.get(14).weightMs, "a hatched set is its planned size");
        // Without the cut the same plan is drawn as before (nothing hatched).
        List<StageBar.Segment> plain = group(StageBar.buildLive(st, p, 4, 10_000L, sets, 0, 0f,
            null, null, null), 1);
        for (int i = 0; i < plain.size(); i++) assertFalse(plain.get(i).skipped);
    }

    @Test
    void aSkippedWarmUpIsHatched() {
        List<Model.Stage> st = stages();
        List<Model.Preset> p = plan();
        int[] sets = counted(st, p);
        long planned = p.get(0).durMs;
        p.get(0).durMs = 60_000L;                    // Skip warm-up a minute in
        long[] cut = new long[p.size()];
        cut[0] = planned - 60_000L;
        List<StageBar.Segment> w = group(StageBar.buildLive(st, p, 1, 1_000L, sets, 1, 0.03f,
            null, null, null, cut), 0);
        assertEquals(2, w.size(), "what ran, and what was skipped");
        assertFalse(w.get(0).skipped);
        assertEquals(1f, w.get(0).fill, 1e-6);
        assertTrue(w.get(1).skipped, "the skipped warm-up is hatched");
        assertEquals(planned - 60_000L, w.get(1).weightMs);
        int[] nn = StageBar.nowAndNext(StageBar.buildLive(st, p, 1, 1_000L, sets, 1, 0.03f,
            null, null, null, cut));
        assertTrue(nn[0] >= 0);
    }
}
