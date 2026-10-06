package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "THIS SET" ON A RAMP NEVER MOVES THE REST OF THE RAMP AGAINST THE GESTURE (safety review
 * of D1, finding 3).
 *
 * A ± in "This set" scope on a ramp moves the step playing and SHIFTS every remaining step
 * of the ramp with it, shape kept. The shift was computed from a snapshot of the ramp taken
 * at the first such nudge, and the running step's own row in it: tail = snapshot + (what is
 * now in force − the running step's snapshot row). Nothing told the snapshot when the tail
 * was edited some other way, and the running step's row is not what was in force whenever
 * an adjustment already carried. The reviewer's sequence, on a ramp 20/24/28/32/36 kPa: pull
 * + settles at 21 (snapshot taken, tail 25/29/33/37); Reshape to an end of 22 (tail about
 * 21/22/22/22); pull − (21 → 20): the tail was rebuilt from the snapshot, 24/28/32/36 - a
 * MINUS that raised the last step by 14 kPa, silently.
 */
class SetShiftTest {

    private static final int UP = LiveEdit.UP, LO = LiveEdit.LO, UH = LiveEdit.UH,
                             SP = LiveEdit.SP, LH = LiveEdit.LH;
    private static final int CEIL = 40;

    private String unitBefore;
    @BeforeEach void kpa() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_KPA; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    private static List<Model.Preset> ramp(String id, int pos, int... ups) {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        addRamp(plan, id, pos, ups);
        return plan;
    }

    private static void addRamp(List<Model.Preset> plan, String id, int pos, int... ups) {
        for (int k = 0; k < ups.length; k++) {
            Model.Preset p = new Model.Preset();
            p.setId = id; p.pos = pos; p.stageIdx = 0;
            p.up = ups[k]; p.lo = Math.max(0, ups[k] - 10); p.uh = 30; p.lh = 5; p.sp = 80;
            p.durMs = 60_000L; p.label = id + " " + (k + 1);
            plan.add(p);
        }
    }

    private static int[] row(List<Model.Preset> plan, int k) { return SetShift.tuple(plan.get(k)); }

    /** What is in force on the running step as the app derives it: the carried tuple, or the
     *  plan's own row clamped as it was sent. */
    private static int[] planned(List<Model.Preset> plan, int k) {
        int[] t = row(plan, k);
        t[UP] = RunEdit.clampUpper(t[UP], CEIL);
        // As uploadBatch sends it: the one drop rule (RunEdit#dropKept, review I4/I5).
        t[LO] = RunEdit.dropKept(plan.get(k), t[LO], t[UP]);
        return t;
    }

    /** One set-scope nudge of the running step: returns the new carried tuple. */
    private static int[] nudge(SetShift s, List<Model.Preset> plan, int idx, int[] before,
                               int field, int dir) {
        int[] after = before.clone();
        LiveEdit.set(after, field, LiveEdit.stepped(field, before[field], dir), CEIL);
        s.shift(plan, idx, RunEdit.remainingStepsOfSet(plan, idx), before, after, CEIL);
        return after;
    }

    private static void reshape(List<Model.Preset> plan, int idx, int[] inForce, int endUp) {
        RunEdit.reshapeRemaining(plan, idx, inForce[UP], inForce[LO], inForce[UH], inForce[LH],
            inForce[SP], endUp, Math.max(0, endUp - 10), 30, 5, 80, CEIL);
    }

    @Test
    void theReviewersSequenceAMinusAfterAReshapeLowersTheTail() {
        List<Model.Preset> plan = ramp("A", 0, 20, 24, 28, 32, 36);
        SetShift s = new SetShift();
        int[] carry = nudge(s, plan, 0, planned(plan, 0), UP, +1);   // 21, tail 25..37
        assertEquals(37, plan.get(4).up);
        reshape(plan, 0, carry, 22);                                 // tail ~21/22/22/22
        int[] reshaped = { plan.get(1).up, plan.get(2).up, plan.get(3).up, plan.get(4).up };
        // The app no longer depends on the reshape remembering to reset the shift: the shift
        // itself notices the tail is not what it last wrote.
        nudge(s, plan, 0, carry, UP, -1);                            // 21 -> 20
        for (int k = 1; k <= 4; k++)
            assertTrue(plan.get(k).up <= reshaped[k - 1], "step " + k + ": " + reshaped[k - 1]
                + " -> " + plan.get(k).up + " on a MINUS (was rebuilt from the old snapshot)");
        assertEquals(21, plan.get(4).up, "the reshaped end, one step lower");
    }

    /** The same with the base cleared at the reshape (what the app does now), but an
     *  adjustment carried well above the plan: the running step's plan row is not what was
     *  in force, and the old "fresh snapshot" took it as the base. */
    @Test
    void aClearedBaseIsRetakenFromWhatIsInForceNotFromThePlan() {
        List<Model.Preset> plan = ramp("A", 0, 20, 24, 28, 32, 36);
        SetShift s = new SetShift();
        int[] carry = planned(plan, 0);
        for (int i = 0; i < 5; i++) carry = nudge(s, plan, 0, carry, UP, +1);   // 25
        reshape(plan, 0, carry, 30);
        s.forget();                                                  // applyRampReshape
        int[] reshaped = { plan.get(1).up, plan.get(2).up, plan.get(3).up, plan.get(4).up };
        nudge(s, plan, 0, carry, UP, -1);                            // 25 -> 24
        for (int k = 1; k <= 4; k++)
            assertEquals(reshaped[k - 1] - 1, plan.get(k).up, "step " + k + " one step lower");
    }

    /** A "This rep" raise, then a "This set" minus: the rest of the set comes down one step
     *  from where it is - it does not jump to follow the rep's raise. */
    @Test
    void aRepRaiseThenASetMinusLowersTheRestOneStep() {
        List<Model.Preset> plan = ramp("A", 0, 20, 24, 28, 32, 36);
        SetShift s = new SetShift();
        int[] carry = planned(plan, 0);
        for (int i = 0; i < 5; i++) {                                // rep scope: no shift
            int[] a = carry.clone();
            LiveEdit.set(a, UP, carry[UP] + 1, CEIL);
            carry = a;
        }
        nudge(s, plan, 0, carry, UP, -1);                            // set scope, 25 -> 24
        assertEquals(23, plan.get(1).up);
        assertEquals(35, plan.get(4).up);
    }

    /** The whole-routine offset (or an upcoming-step edit) changed the tail after the base
     *  was taken: the next shift starts from the tail as it is, and does not undo it. */
    @Test
    void anOffsetOfTheTailIsNotUndoneByTheNextShift() {
        List<Model.Preset> plan = ramp("A", 0, 20, 24, 28, 32, 36);
        SetShift s = new SetShift();
        int[] carry = nudge(s, plan, 0, planned(plan, 0), UP, +1);
        for (int k = 1; k <= 4; k++) plan.get(k).up -= 6;           // routine offset −6
        nudge(s, plan, 0, carry, UP, -1);
        assertEquals(18, plan.get(1).up, "25 − 6 − 1, not the snapshot's 24");
        assertEquals(30, plan.get(4).up);
    }

    /** A running step whose row was clamped (at 0 here) is not a reference: a hold − on it
     *  must not swing the pull of the steps after it. */
    @Test
    void aClampedStepDoesNotSwingTheRestOfTheRamp() {
        List<Model.Preset> plan = ramp("A", 0, 30, 3, 25, 35);
        SetShift s = new SetShift();
        int[] carry = planned(plan, 0);
        for (int i = 0; i < 20; i++) carry = nudge(s, plan, 0, carry, UP, -1);   // −20
        assertEquals(1, plan.get(1).up, "3 − 20, stopped at 1 kPa - never a negative pull, and "
            + "never 0, where no drop could stay under it (RampGridRulesTest)");
        int[] tail = { plan.get(2).up, plan.get(3).up };
        // The next step starts: it plays its (shifted, clamped) row; a HOLD − is tapped on it.
        nudge(s, plan, 1, planned(plan, 1), UH, -1);
        assertEquals(tail[0], plan.get(2).up, "a hold − leaves the pull where it was");
        assertEquals(tail[1], plan.get(3).up);
    }

    /**
     * THE PROPERTY. Random runs over two ramps: set-scope and rep-scope ± on every field,
     * reshapes (with the base cleared, as the app does), step-count changes, whole-routine
     * offsets, upcoming-step edits, steps and ramps advancing, reverts. After every set-scope
     * nudge, every remaining step of the ramp has moved the way the nudge was pushed, or not
     * at all - a minus never raises any figure of any step to come, a plus never lowers one.
     */
    @Test
    void aNudgeNeverMovesTheRestOfTheRampAgainstIt() {
        for (long seed = 1; seed <= 4000; seed++)
            assertEquals(null, firstBreach(seed), "seed " + seed);
    }

    private static String firstBreach(long seed) {
        Random r = new Random(seed);
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        addRamp(plan, "A", 0, ups(r));
        addRamp(plan, "B", 1, ups(r));
        SetShift s = new SetShift();
        int idx = 0;
        int[] carry = null;                 // an adjustment carried into the step playing
        for (int op = 0; op < 60; op++) {
            int[] inForce = carry != null ? carry : planned(plan, idx);
            int occLeft = RunEdit.remainingStepsOfSet(plan, idx);
            int kind = r.nextInt(10);
            if (kind <= 3) {                                        // a ± nudge
                int field = r.nextInt(LiveEdit.FIELDS), dir = r.nextBoolean() ? 1 : -1;
                int[] after = inForce.clone();
                LiveEdit.set(after, field, LiveEdit.stepped(field, inForce[field], dir), CEIL);
                if (java.util.Arrays.equals(after, inForce)) continue;
                boolean set = kind != 3;
                List<int[]> tail = new ArrayList<int[]>();
                for (int k = idx + 1; k <= idx + occLeft; k++) tail.add(planned(plan, k));
                if (set && occLeft > 0) s.shift(plan, idx, occLeft, inForce, after, CEIL);
                carry = after;
                for (int k = idx + 1; k <= idx + occLeft; k++) {
                    // Compared as SENT (the ceiling applied): a row above the ceiling in the
                    // plan was only ever given to the pump at the ceiling.
                    int[] was = tail.get(k - idx - 1), now = planned(plan, k);
                    for (int f = 0; f < LiveEdit.FIELDS; f++) {
                        boolean against = dir < 0 ? now[f] > was[f] : now[f] < was[f];
                        if (!set && now[f] != was[f]) return "op " + op + ": a rep nudge moved step " + k;
                        if (against)
                            return "op " + op + ": a " + (dir < 0 ? "MINUS" : "plus") + " on field "
                                + field + " moved step " + k + " field " + f + " " + was[f]
                                + " -> " + now[f];
                    }
                }
            } else if (kind == 4 && occLeft > 0) {                   // Reshape
                RunEdit.reshapeRemaining(plan, idx, inForce[UP], inForce[LO], inForce[UH],
                    inForce[LH], inForce[SP], r.nextInt(CEIL + 5), r.nextInt(30),
                    1 + r.nextInt(80), r.nextInt(20), r.nextInt(101), CEIL);
                s.forget();
            } else if (kind == 5 && occLeft > 0) {                   // step count
                RunEdit.resizeRemaining(plan, idx, inForce[UP], inForce[LO], inForce[UH],
                    inForce[LH], inForce[SP], 1 + r.nextInt(6), CEIL);
                s.forget();
            } else if (kind == 6) {                                  // whole-routine offset
                int d = r.nextInt(13) - 6;
                for (int k = idx + 1; k < plan.size(); k++) {
                    Model.Preset p = plan.get(k);
                    p.up = RunEdit.clampUpper(Math.max(0, p.up + d), CEIL);
                    p.lo = RunEdit.dropKept(p, p.lo, p.up);     // RoutineOffset#apply's
                }
            } else if (kind == 7 && idx + 1 < plan.size()) {         // edit an upcoming step
                Model.Preset p = plan.get(idx + 1 + r.nextInt(plan.size() - idx - 1));
                p.up = RunEdit.clampUpper(r.nextInt(CEIL + 5), CEIL);
                // Every road that edits a drop holds it to the step's highest (RunEdit#capDrop).
                // ...and keeps the gap under the pull (RunEdit#dropKept, review I4/I5).
                p.lo = RunEdit.dropKept(p, RunEdit.capDrop(p, r.nextInt(30)), p.up);
                p.sp = r.nextInt(101);
            } else if (kind == 8 && idx + 1 < plan.size()) {         // the next step
                idx++;
                carry = null;                                        // ramp carries end here
            } else if (kind == 9) {                                  // revert
                carry = null;
            }
        }
        return null;
    }

    private static int[] ups(Random r) {
        int n = 3 + r.nextInt(5), a = r.nextInt(30), b = r.nextInt(45);
        int[] u = new int[n];
        for (int k = 0; k < n; k++) u[k] = a + (b - a) * k / (n - 1);
        return u;
    }
}
