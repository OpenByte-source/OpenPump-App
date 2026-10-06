package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NO EDIT EVER RAISES A STEP'S DROP PAST THE FLOOR - OR PAST ITS OWN PLANNED DROP WHEN THAT IS
 * HIGHER (owner report from a real pump, 0.10: a ramp step showed "Pull to −11.5, Drop to
 * −7.1 inHg" - 24 kPa, a cuff that barely releases - after the adjust sheet's pull, the ramp's
 * end pull and Reshape).
 *
 * The road: the adjust sheet's drop slider reached the ceiling (40 kPa) before 7fe2487, so a
 * drop could be dialled and carried far above the floor; "Rest of this ramp" (SetShift, before
 * 5b4f922) moved every later drop by the same amount, and the ramp's Reshape and step count
 * interpolate every later drop FROM the step playing's - a raised anchor spread the raise down
 * the ramp. Every one of those roads now asks the step's own highest drop (Preset#loMax, stamped
 * with the build: max(10 kPa, the planned drop)), and so does the wire (uploadBatch, the
 * adjustment's write, the carry into a step). Only a drop the PERSON sets on a step (Coming
 * steps, the strip's Drop +) raises that step's highest - past the floor as their own call
 * since 2026-09-30 (DropAboveFloorTest).
 */
class DropFloorTest {

    private static final int UP = LiveEdit.UP, LO = LiveEdit.LO;
    private static final int CEIL = 40, FLOOR = RunEdit.DROP_FLOOR_KPA;

    private String unitBefore;
    @BeforeEach void kpa() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_KPA; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    /** A ramp as the plan builds it (Model#plan stamps each step through RunEdit#asBuilt). */
    private static List<Model.Preset> ramp(int up, int lo, int up2, int lo2, int steps) {
        Model.Set s = Model.Set.ramp("R", "Ramp", up, lo, 60, 5, 60, up2, lo2, 60, 5, 80,
                                     steps, steps * 132);
        List<Model.Preset> plan = new ArrayList<Model.Preset>(s.ladder());
        for (Model.Preset p : plan) { p.setId = "R"; p.pos = 0; p.stageIdx = 0; RunEdit.asBuilt(p); }
        return plan;
    }

    private static int[] tuple(int up, int lo) {
        return new int[]{ up, lo, 60, 5, 60 };
    }

    private static void assertFloor(List<Model.Preset> plan, int[] planned, String what) {
        for (int k = 0; k < plan.size(); k++) {
            Model.Preset p = plan.get(k);
            int cap = Math.max(FLOOR, planned[Math.min(k, planned.length - 1)]);
            assertTrue(p.lo <= cap, what + ": step " + (k + 1) + " drop " + p.lo
                + " kPa past max(10, planned " + planned[Math.min(k, planned.length - 1)] + ")");
            assertTrue(p.lo < p.up || p.up == 0, what + ": step " + (k + 1) + " drop not under its pull");
        }
    }

    private static int[] los(List<Model.Preset> plan) {
        int[] l = new int[plan.size()];
        for (int k = 0; k < l.length; k++) l[k] = plan.get(k).lo;
        return l;
    }

    @Test
    void theBuildStampsEachStepsHighestDrop() {
        List<Model.Preset> plan = ramp(10, 4, 30, 9, 5);
        for (Model.Preset p : plan) assertEquals(FLOOR, p.loMax, "planned under the floor: the floor");
        List<Model.Preset> high = ramp(10, 5, 20, 15, 3);          // the warm-up ramp's up2 − 5
        assertEquals(15, high.get(2).loMax, "planned above it: its own planned drop");
        assertEquals(24, RunEdit.capDrop(new Model.Preset(), 24), "unstamped: nothing held");
        assertEquals(10, RunEdit.capDrop(plan.get(1), 24));
    }

    /** The owner's sequence: a drop carried at 24 kPa on the step playing, "Rest of this ramp"
     *  moving the pull (and the drop), then Reshape to an end pull of −11.5 (39 kPa). */
    @Test
    void aShiftAndAReshapeToTheTopNeverRaiseADropPastTheFloor() {
        List<Model.Preset> plan = ramp(10, 4, 30, 9, 5);
        int[] planned = los(plan);
        // Rest of this ramp: pull +9 and a drop dialled up to 24 on the step playing.
        new SetShift().shift(plan, 0, RunEdit.remainingStepsOfSet(plan, 0),
            tuple(10, 4), tuple(19, 24), CEIL);
        assertFloor(plan, planned, "after the shift");
        // Reshape the rest of the ramp to 39 kPa from an anchor carrying the 24 kPa drop.
        RunEdit.reshapeRemaining(plan, 0, 19, 24, 60, 5, 60, 39, 24, 60, 5, 80, CEIL);
        assertFloor(plan, planned, "after the reshape");
        assertEquals(39, plan.get(4).up);
        assertEquals(FLOOR, plan.get(4).lo, "the end's drop stops at the floor, not at −7.1 inHg");
        // The step count, from the same anchor.
        RunEdit.resizeRemaining(plan, 0, 19, 24, 60, 5, 60, 7, CEIL);
        assertFloor(plan, planned, "after the step count");
        for (Model.Preset p : plan.subList(1, plan.size())) assertEquals(FLOOR, p.loMax);
    }

    @Test
    void aStepPlannedDeeperKeepsItsDropButIsNeverRaisedPastIt() {
        List<Model.Preset> plan = ramp(10, 5, 20, 15, 3);
        int[] planned = los(plan);
        RunEdit.reshapeRemaining(plan, 0, 10, 24, 60, 5, 60, 25, 24, 60, 5, 80, CEIL);
        assertFloor(plan, planned, "reshaped from a raised anchor");
        assertEquals(15, plan.get(2).lo, "its own planned 15 kPa, not 24");
        // A re-spread between the ends keeps within what the ends may have.
        ComingSteps.respreadRamp(plan, 0, 3, 5, 60, CEIL);
        for (Model.Preset p : plan) assertTrue(p.lo <= 15 && p.loMax <= 15);
    }

    /** Coming steps' "Drop to" is the person setting the drop on that step: past the floor is
     *  their call since 2026-09-30 (asked once on the run screen), held 1.0 inHg under the
     *  step's pull (RunEdit#dropTopKpa) - never at the pull, never the floor's silent clamp. */
    @Test
    void comingStepsDropStopsOneInchUnderThePull() {
        List<Model.Preset> plan = ramp(10, 4, 30, 9, 5);
        Model.Preset p = plan.get(3);
        ComingSteps.applyDrop(p, 24);
        assertEquals(RunEdit.dropTopKpa(p.up), p.lo, "under a pull of " + p.up + " kPa");
        assertTrue(p.lo > FLOOR && p.lo <= p.up - RunEdit.DROP_GAP_KPA);
        assertEquals(p.lo, p.loMax, "the step's highest drop rose to what was set");
    }

    @Test
    void theSheetsApplyRefusesADropPastTheStepsHighest() {
        assertNull(QuickAdjust.dropRefusal(4, 10, 10));
        assertEquals("The drop stops at " + Model.Fmt.p(10) + " — the drop floor.",
            QuickAdjust.dropRefusal(4, 24, 10));
        assertNull(QuickAdjust.dropRefusal(24, 12, 10), "coming down is always allowed");
        assertEquals(15, RunEdit.dropCapOf(ramp(10, 5, 20, 15, 3).get(2), 0));
        assertEquals(12, RunEdit.dropCapOf(null, 12), "unstamped: the floor or what it has");
    }

    /** THE PROPERTY: any mix of shifts, reshapes, recounts, re-spreads and Coming steps' drops,
     *  from anchors carried anywhere up to the ceiling - no step's drop ever passes max(10 kPa,
     *  the ramp's own planned drop, a drop the person set in Coming steps), and every drop
     *  stays under its pull. */
    @Test
    void noEditEverRaisesADropPastTheFloor() {
        for (long seed = 1; seed <= 3000; seed++) {
            Random r = new Random(seed);
            int lo = r.nextInt(12), lo2 = r.nextInt(18), up = 12 + r.nextInt(10), up2 = 12 + r.nextInt(28);
            List<Model.Preset> plan = ramp(up, Math.min(lo, up - 1), up2, Math.min(lo2, up2 - 1),
                                           2 + r.nextInt(8));
            int maxPlanned = 0;
            for (Model.Preset p : plan) maxPlanned = Math.max(maxPlanned, p.lo);
            int cap = Math.max(FLOOR, maxPlanned);
            SetShift shift = new SetShift();
            for (int op = 0; op < 30 && RunEdit.remainingStepsOfSet(plan, 0) > 0; op++) {
                int aUp = 1 + r.nextInt(CEIL), aLo = r.nextInt(CEIL);
                switch (r.nextInt(5)) {
                    case 0: {
                        int[] before = SetShift.tuple(plan.get(0));
                        int[] after = before.clone();
                        after[UP] = Math.min(CEIL, Math.max(1, before[UP] + r.nextInt(21) - 10));
                        after[LO] = Math.max(0, Math.min(after[UP] - 1, before[LO] + r.nextInt(31) - 10));
                        shift.shift(plan, 0, RunEdit.remainingStepsOfSet(plan, 0), before, after, CEIL);
                        break;
                    }
                    case 1:
                        RunEdit.reshapeRemaining(plan, 0, aUp, aLo, 60, 5, 60, 1 + r.nextInt(CEIL),
                            r.nextInt(CEIL), 60, 5, 80, CEIL);
                        shift.forget();
                        break;
                    case 2:
                        RunEdit.resizeRemaining(plan, 0, aUp, aLo, 60, 5, 60, 1 + r.nextInt(8), CEIL);
                        shift.forget();
                        break;
                    case 3:
                        ComingSteps.respreadRamp(plan, 1, RunEdit.remainingStepsOfSet(plan, 0),
                            2 + r.nextInt(6), 60, CEIL);
                        shift.forget();
                        break;
                    default: {
                        // The person's own drop on that step (2026-09-30): it may pass the
                        // floor, and from then on it is part of what that step may have.
                        Model.Preset last = plan.get(plan.size() - 1);
                        int was = last.lo;
                        ComingSteps.applyDrop(last, r.nextInt(CEIL));
                        cap = Math.max(cap, last.lo);
                        if (last.lo > was)
                            assertTrue(last.lo <= Math.max(FLOOR, last.up - RunEdit.DROP_GAP_KPA),
                                "seed " + seed + ": a raised drop within 1.0 inHg of its pull");
                    }
                }
                for (int k = 1; k < plan.size(); k++) {
                    Model.Preset p = plan.get(k);
                    assertTrue(p.lo <= cap, "seed " + seed + " op " + op + ": step " + (k + 1)
                        + " drop " + p.lo + " kPa past " + cap);
                    assertTrue(p.loMax >= 0 && p.loMax <= cap, "seed " + seed + ": stamp " + p.loMax);
                    assertTrue(p.lo < p.up || p.up == 0, "seed " + seed + ": drop not under pull");
                }
            }
        }
    }
}
