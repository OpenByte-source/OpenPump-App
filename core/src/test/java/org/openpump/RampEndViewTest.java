package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE ADJUST SHEET'S RAMP END MUST BE THE END THE RAMP HAS NOW (safety review of D1,
 * finding 2).
 *
 * The END sliders are a copy of the running ramp's last step, read when the sheet is
 * drawn. Reshape re-interpolates the remaining steps up to that copy, and "step size ±"
 * moves it by a step per remaining step and reshapes. Two ways the copy stops being the
 * ramp's end while the sheet stays open:
 *   - a different ramp starts (a sheet opened on ramp A, end −8.3 inHg, left open into
 *     ramp B, end −5.0: Reshape climbed B up to −8.3);
 *   - a "This set" change shifts the whole remaining ramp, its end included (a pull −10
 *     settles; the sheet still shows the old end, and "smaller steps - the end comes down"
 *     lifts the end back up past where it now is).
 * So the end controls act only while the sheet's end is still the plan's end
 * ({@link RunEdit#endTuple}, checked with {@link LiveEdit#viewCurrent}); otherwise nothing
 * changes and the sheet is redrawn.
 */
class RampEndViewTest {

    private static final int UP = LiveEdit.UP, LO = LiveEdit.LO, UH = LiveEdit.UH,
                             SP = LiveEdit.SP, LH = LiveEdit.LH;
    private static final int CEIL = 40;

    private String unitBefore;
    @BeforeEach void kpa() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_KPA; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    /** A ramp occurrence of set `id` at stage position `pos`: `n` steps from `from` to `to`. */
    private static void ramp(List<Model.Preset> plan, String id, int pos, int n, int from, int to) {
        for (int k = 0; k < n; k++) {
            Model.Preset p = new Model.Preset();
            p.setId = id; p.pos = pos; p.stageIdx = 0;
            p.up = from + (to - from) * k / Math.max(1, n - 1);
            p.lo = Math.max(0, p.up - 8);
            p.uh = 30 + k; p.lh = 5; p.sp = 80 + k;
            p.durMs = 60_000L; p.label = id + " " + (k + 1) + "/" + n;
            plan.add(p);
        }
    }

    @Test
    void theEndIsTheRunningRampsLastStepClampedAsItIsSent() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        ramp(plan, "A", 0, 4, 10, 28);
        ramp(plan, "B", 1, 3, 12, 17);
        int[] a = RunEdit.endTuple(plan, 0, CEIL);
        assertEquals(28, a[UP]);
        assertEquals(20, a[LO]);
        assertEquals(17, RunEdit.endTuple(plan, 4, CEIL)[UP], "ramp B's own end");
        assertNull(RunEdit.endTuple(plan, 3, CEIL), "A's last step: no end left to reshape to");
        plan.get(3).up = 55;
        assertEquals(CEIL, RunEdit.endTuple(plan, 0, CEIL)[UP], "never above the ceiling");
    }

    /** The reviewer's sequence: ramp A's end drawn, ramp B playing - not current. */
    @Test
    void aSheetDrawnOnOneRampIsNotCurrentOnTheNext() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        ramp(plan, "A", 0, 4, 10, 28);                 // end 28 kPa (about −8.3 inHg)
        ramp(plan, "B", 1, 4, 10, 17);                 // end 17 kPa (−5.0)
        int[] drawn = RunEdit.endTuple(plan, 1, CEIL);
        assertFalse(LiveEdit.viewCurrent(1, drawn, 5, RunEdit.endTuple(plan, 5, CEIL)));

        // What the unguarded Reshape did: B's remaining steps climbed to A's end.
        int[] anchor = { plan.get(5).up, plan.get(5).lo, plan.get(5).uh, plan.get(5).sp, plan.get(5).lh };
        RunEdit.reshapeRemaining(plan, 5, anchor[UP], anchor[LO], anchor[UH], anchor[LH], anchor[SP],
            drawn[UP], drawn[LO], drawn[UH], drawn[LH], drawn[SP], CEIL);
        assertEquals(28, plan.get(7).up, "the hazard: ramp B now ends at ramp A's end");
    }

    /** A "This set" change moves the end under the sheet: not current either. */
    @Test
    void aShiftOfTheWholeRampMovesTheEndUnderTheSheet() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        ramp(plan, "A", 0, 5, 20, 36);
        int[] drawn = RunEdit.endTuple(plan, 0, CEIL);
        for (int k = 1; k < 5; k++) plan.get(k).up -= 10;   // the shift, pull −10
        assertFalse(LiveEdit.viewCurrent(0, drawn, 0, RunEdit.endTuple(plan, 0, CEIL)));
        // "Smaller steps - the end comes down", from the stale copy: 36 − 4 = 32, above the
        // 26 the ramp now ends at.
        assertTrue(drawn[UP] - 4 > RunEdit.endTuple(plan, 0, CEIL)[UP]);
    }

    /**
     * THE PROPERTY. Random runs over a plan of two ramps: a sheet drawn and redrawn, its end
     * slider moved, "This set" shifts of the remaining ramp, steps and ramps changing under
     * it, the tick redrawing a stale sheet (guarded only), Reshape and "step size −". After
     * every reshape toward a lower end (the end copy never raised above the drawn end), the
     * ramp's end is no higher than before it, and no reshaped step is above both what is in
     * force and the end the ramp had.
     */
    @Test
    void aReshapeTowardALowerEndNeverLiftsTheRamp() {
        for (long seed = 1; seed <= 3000; seed++)
            assertEquals(null, firstBreach(seed, true), "seed " + seed);
    }

    @Test
    void theModelCatchesTheEndControlsAsTheyShipped() {
        String lifted = null;
        for (long seed = 1; seed <= 3000 && lifted == null; seed++) lifted = firstBreach(seed, false);
        assertTrue(lifted != null && lifted.contains("LIFTED"), "a stale end lifted the ramp: " + lifted);
    }

    private static String firstBreach(long seed, boolean guarded) {
        Random r = new Random(seed);
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        ramp(plan, "A", 0, 3 + r.nextInt(5), 5 + r.nextInt(10), 10 + r.nextInt(30));
        int bStart = plan.size();
        ramp(plan, "B", 1, 3 + r.nextInt(5), 5 + r.nextInt(10), 10 + r.nextInt(30));
        int idx = 0;
        int sheetIdx = -1;
        int[] drawn = null, copy = null;                  // the drawn end, and the END sliders
        for (int op = 0; op < 60; op++) {
            int kind = r.nextInt(8);
            int[] end = RunEdit.endTuple(plan, idx, CEIL);
            boolean current = LiveEdit.viewCurrent(sheetIdx, drawn, idx, end);
            if (kind == 0 || (kind == 7 && guarded && drawn != null && !current)) {
                sheetIdx = idx; drawn = end; copy = end == null ? null : end.clone();   // (re)draw
            } else if (kind == 1 && copy != null) {                           // move the end slider
                if (guarded && !current) { sheetIdx = idx; drawn = end; copy = end == null ? null : end.clone(); continue; }
                LiveEdit.set(copy, UP, copy[UP] + (r.nextBoolean() ? 1 : -1) * (1 + r.nextInt(5)), CEIL);
            } else if (kind == 2) {                                           // a "This set" shift
                int m = RunEdit.remainingStepsOfSet(plan, idx), d = r.nextInt(21) - 10;
                for (int k = idx + 1; k <= idx + m; k++) {
                    Model.Preset p = plan.get(k);
                    p.up = RunEdit.clampUpper(Math.max(0, p.up + d), CEIL);
                    p.lo = RunEdit.clampLower(p.lo, p.up);
                }
            } else if (kind == 3) {                                           // the next step
                if (idx + 1 < plan.size()) idx++;
            } else if (kind == 4) {                                           // the next ramp
                if (idx < bStart) idx = bStart;
            } else if ((kind == 5 || kind == 6) && copy != null && end != null) {   // Reshape / step size −
                if (guarded && !current) { sheetIdx = idx; drawn = end; copy = end.clone(); continue; }
                int m = RunEdit.remainingStepsOfSet(plan, idx);
                if (kind == 6) LiveEdit.set(copy, UP, copy[UP] + (Model.Fmt.nudgeKpa(10, -1) - 10) * m, CEIL);
                boolean lowering = copy[UP] <= drawn[UP];
                Model.Preset cur = plan.get(idx);
                int anchor = RunEdit.clampUpper(cur.up, CEIL);
                RunEdit.reshapeRemaining(plan, idx, anchor, RunEdit.clampLower(cur.lo, anchor),
                    cur.uh, cur.lh, cur.sp, copy[UP], copy[LO], copy[UH], copy[LH], copy[SP], CEIL);
                int[] after = RunEdit.endTuple(plan, idx, CEIL);
                if (lowering && after[UP] > end[UP])
                    return "op " + op + ": a reshape toward a lower end LIFTED the ramp's end from "
                        + end[UP] + " to " + after[UP];
                for (int k = idx + 1; k <= idx + m && lowering; k++)
                    if (plan.get(k).up > Math.max(anchor, end[UP]))
                        return "op " + op + ": a reshape toward a lower end LIFTED step " + k
                            + " to " + plan.get(k).up + ", above " + anchor + " and " + end[UP];
                sheetIdx = idx; drawn = after; copy = after == null ? null : after.clone();   // refilled
            }
        }
        return null;
    }
}
