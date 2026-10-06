package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE − / + GRID AGAINST A RAMP SET'S OWN VARIABLES (owner request, 0.10).
 *
 * A ramp Set carries its two ends (up→up2, lo→lo2, uh→uh2, lh→lh2, sp→sp2), `steps` and
 * `dur`, expanded by Set#ladder into one preset per step. The grid edits the step playing, and
 * "Rest of this ramp" moves the steps after it (SetShift). These pin, for ascending and
 * descending ramps of 2 and 9 steps:
 *   1. the one rule for a step's cycle: hold + drop time never longer than the step - a longer
 *      cycle is refused, and the step's length never goes under one cycle;
 *   2. a shift keeps the ramp a ramp: each figure moves by its own change and nothing else,
 *      the order never inverts, and pulls stopped at the ceiling are counted to be said;
 *   3. every step's drop stays under that step's pull - the ladder's lo→lo2 and after a
 *      shift - and a raised drop stops at the floor;
 *   5. a later edit of the ramp reads its ends from the plan as shifted, never a stale copy;
 *   6. the speed moves only with a speed change;
 *   7. the warm-up's ramp keeps the same rules.
 */
class RampGridRulesTest {

    private static final int UP = LiveEdit.UP, LO = LiveEdit.LO, UH = LiveEdit.UH,
                             LH = LiveEdit.LH, SP = LiveEdit.SP;
    private static final String STEP_TIME = QuickAdjust.LABEL[QuickAdjust.STEP_TIME];

    private String unitBefore;
    @BeforeEach void kpa() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_KPA; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    /** A ramp set's ladder as the run plays it: one occurrence, its steps in a row. */
    private static List<Model.Preset> ladder(int up, int lo, int uh, int lh, int sp,
                                             int up2, int lo2, int uh2, int lh2, int sp2,
                                             int steps, int dur) {
        Model.Set s = Model.Set.ramp("R", "Ramp", up, lo, uh, lh, sp, up2, lo2, uh2, lh2, sp2,
                                     steps, dur);
        List<Model.Preset> plan = new ArrayList<Model.Preset>(s.ladder());
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            p.setId = "R"; p.pos = 0; p.stageIdx = 0;
            // THE STEPS AS THE RUN HOLDS THEM, `dur` shared evenly - 1:12 each for the device's
            // ramp below. These tests are the live rules (the cycle never outgrows its step, the
            // drop floor, the shift), which judge whatever length a step has in the run: a
            // live edit, a Resume or an older plan can leave any. The builder's own steps are
            // whole cycles (0.10, Model.Set#rampCycles) - ModelWholeCycleRampTest's.
            p.durMs = (long) dur * 1000L / plan.size();
        }
        return plan;
    }

    private static List<Model.Preset> ascending(int steps) {
        return ladder(10, 4, 38, 5, 60, 30, 9, 38, 5, 90, steps, steps * 72);
    }

    /** −6.5 → −2.1 inHg: 22 kPa down to 7. */
    private static List<Model.Preset> descending(int steps) {
        return ladder(22, 9, 38, 5, 90, 7, 2, 38, 5, 60, steps, steps * 72);
    }

    private static int[] inForce(List<Model.Preset> plan, int k, int ceil) {
        int[] t = SetShift.tuple(plan.get(k));
        t[UP] = RunEdit.clampUpper(t[UP], ceil);
        t[LO] = RunEdit.clampLower(t[LO], t[UP]);
        return t;
    }

    private static int[][] rows(List<Model.Preset> plan) {
        int[][] r = new int[plan.size()][];
        for (int k = 0; k < plan.size(); k++) r[k] = SetShift.tuple(plan.get(k));
        return r;
    }

    /** "Rest of this ramp": the step playing (0) changes `field` by `d`, and SetShift moves
     *  the steps after it. Returns the shift. */
    private static SetShift shift(List<Model.Preset> plan, int field, int d, int ceil) {
        int[] before = inForce(plan, 0, ceil);
        int[] after = before.clone();
        after[field] += d;
        SetShift s = new SetShift();
        s.shift(plan, 0, RunEdit.remainingStepsOfSet(plan, 0), before, after, ceil);
        return s;
    }

    private static void assertOrder(List<Model.Preset> plan, boolean ascending, String what) {
        for (int k = 1; k < plan.size(); k++) {
            int a = plan.get(k - 1).up, b = plan.get(k).up;
            assertTrue(ascending ? b >= a : b <= a, what + ": step " + (k + 1) + " "
                + a + " -> " + b + " turned the ramp round");
        }
    }

    private static void assertDropsUnderPulls(List<Model.Preset> plan, String what) {
        for (int k = 0; k < plan.size(); k++) {
            Model.Preset p = plan.get(k);
            assertTrue(p.lo < p.up, what + ": step " + (k + 1) + " drop " + p.lo
                + " not under its pull " + p.up);
        }
    }

    /* ------------------------------------------------------------ 3. the ladder's drops */

    @Test
    void theLaddersInterpolatedDropsStayUnderEachStepsPull() {
        int[] counts = { 2, 9 };
        for (int n : counts) {
            List<Model.Preset> a = ascending(n), d = descending(n);
            assertEquals(n, a.size());
            assertEquals(n, d.size());
            assertDropsUnderPulls(a, "ascending " + n);
            assertDropsUnderPulls(d, "descending " + n);
            assertOrder(a, true, "ascending " + n);
            assertOrder(d, false, "descending " + n);
            assertEquals(10, a.get(0).up); assertEquals(30, a.get(n - 1).up);
            assertEquals(22, d.get(0).up); assertEquals(7, d.get(n - 1).up);
        }
    }

    /* -------------------------------------------- 2 and 6. each figure by its own change */

    @Test
    void restOfThisRampMovesOnlyTheFigureThatChanged() {
        int[] counts = { 2, 9 };
        for (int n : counts) {
            for (int dir = 0; dir < 2; dir++) {
                boolean asc = dir == 0;
                int[] fields = { UP, UH, LH, SP, LO };
                for (int field : fields) {
                    List<Model.Preset> plan = asc ? ascending(n) : descending(n);
                    int[][] was = rows(plan);
                    shift(plan, field, field == UP || field == LO ? 1 : 5, 40);
                    String what = (asc ? "ascending " : "descending ") + n + ", field " + field;
                    for (int k = 1; k < n; k++) {
                        int[] now = SetShift.tuple(plan.get(k));
                        for (int f = 0; f < LiveEdit.FIELDS; f++) {
                            if (f == field) {
                                assertEquals(was[k][f] + (f == UP || f == LO ? 1 : 5), now[f],
                                    what + ": step " + (k + 1) + " moved by the same change");
                            } else if (f == LO && field == UP) {
                                assertTrue(now[f] == was[k][f], what + ": the drop stays put");
                            } else {
                                assertEquals(was[k][f], now[f], what + ": step " + (k + 1)
                                    + " field " + f + " moved without being changed");
                            }
                        }
                    }
                    assertOrder(plan, asc, what);
                    assertDropsUnderPulls(plan, what);
                    // Durations: the ramp is never re-timed by a figure's change.
                    for (int k = 0; k < n; k++) assertEquals(72_000L, plan.get(k).durMs);
                }
            }
        }
    }

    @Test
    void aPullMinusOnADescendingRampNeverTurnsItRound() {
        List<Model.Preset> plan = descending(9);
        shift(plan, UP, -12, 40);
        assertOrder(plan, false, "descending, pull −12");
        assertDropsUnderPulls(plan, "descending, pull −12");
        for (int k = 1; k < plan.size(); k++)
            assertTrue(plan.get(k).up >= 1, "a pull stops at 1 kPa, where a drop still fits under");
    }

    /* ---------------------------------------------------- 2. the ceiling flattens, said */

    @Test
    void aShiftClampedAtTheCeilingKeepsTheOrderAndCountsTheFlatSteps() {
        // Ascending 10 → 30 in 9 steps (10, 12.5, 15 ... 30), raised by 8 under a 32 ceiling.
        List<Model.Preset> plan = ascending(9);
        int[][] was = rows(plan);
        SetShift s = shift(plan, UP, 8, 32);
        int flat = 0;
        for (int k = 1; k < 9; k++) {
            assertTrue(plan.get(k).up <= 32, "never past the ceiling");
            if (was[k][UP] + 8 > 32) { flat++; assertEquals(32, plan.get(k).up); }
            else assertEquals(was[k][UP] + 8, plan.get(k).up);
        }
        assertTrue(flat >= 2, "the fixture really flattens the top steps (" + flat + ")");
        assertEquals(flat, s.clampedAtCeiling(), "every step stopped at the ceiling is counted");
        assertOrder(plan, true, "clamped at the ceiling");
        assertDropsUnderPulls(plan, "clamped at the ceiling");
        String said = QuickAdjust.rampFlatSaid(s.clampedAtCeiling(), 32);
        assertEquals("The rest of this ramp moved with it; " + flat + " later steps stop at your "
            + "ceiling, " + Model.Fmt.p(32) + " — the ramp is flat there.", said);
        assertNull(QuickAdjust.rampFlatSaid(0, 32), "nothing flat, nothing to say");
    }

    @Test
    void aDescendingRampClampedAtTheCeilingStaysDescending() {
        List<Model.Preset> plan = descending(9);
        SetShift s = shift(plan, UP, 15, 30);
        // (The step playing moves by its own carry, not in the plan: the order is the later
        // steps'.)
        assertOrder(plan.subList(1, plan.size()), false, "descending, clamped");
        assertTrue(s.clampedAtCeiling() > 0);
        assertEquals(30, plan.get(1).up, "the first later steps stop at the ceiling");
        assertDropsUnderPulls(plan, "descending, clamped");
    }

    @Test
    void aTwoStepRampClampedAtTheCeilingSaysItsOneStep() {
        List<Model.Preset> plan = ascending(2);
        SetShift s = shift(plan, UP, 5, 32);
        assertEquals(32, plan.get(1).up);
        assertEquals(1, s.clampedAtCeiling());
        assertTrue(QuickAdjust.rampFlatSaid(1, 32).contains("one later step stops"));
    }

    /* --------------------------------------------------------- 3. the drop after a shift */

    @Test
    void aRaisedDropStopsAtTheFloorAndNeverLowersAHigherPlannedOne() {
        // lo 4 → 9 over 9 steps: +8 would take the later drops to 12..17.
        List<Model.Preset> plan = ascending(9);
        int[][] was = rows(plan);
        shift(plan, LO, 8, 40);
        for (int k = 1; k < 9; k++) {
            assertEquals(Math.min(was[k][LO] + 8, ComingSteps.DROP_MAX_KPA), plan.get(k).lo,
                "step " + (k + 1) + ": a raised drop stops at the floor");
            assertTrue(plan.get(k).lo < plan.get(k).up);
        }
        // A step planned above the floor already (the warm-up ramp's up2 − 5) keeps its drop.
        List<Model.Preset> high = ladder(10, 5, 30, 5, 60, 20, 15, 30, 5, 75, 4, 240);
        int planned = high.get(3).lo;
        assertTrue(planned > ComingSteps.DROP_MAX_KPA);
        shift(high, LO, 1, 40);
        assertEquals(planned, high.get(3).lo, "a raise never lowers a drop");
        assertDropsUnderPulls(high, "high drops");
    }

    /* ------------------------------------------------------- 1. the step is one cycle at least */

    @Test
    void aHoldOrDropTimeThatOutgrowsTheStepIsRefused() {
        // The device's ramp step: hold 0:38, drop time 5 s, 1:12 a step.
        List<Model.Preset> plan = ascending(9);
        assertNull(QuickAdjust.cycleRefusal(plan, 0, 38, 5, 43, 5, false, STEP_TIME),
            "48 s of cycle fits 1:12");
        assertEquals("A cycle can't be longer than the step, 1:12 — lengthen Time per step first.",
            QuickAdjust.cycleRefusal(plan, 0, 38, 5, 68, 5, false, STEP_TIME));
        assertEquals("A cycle can't be longer than the step, 1:12 — lengthen Time per step first.",
            QuickAdjust.cycleRefusal(plan, 0, 38, 5, 38, 35, false, STEP_TIME));
        // Toward the rule is never refused, even from a cycle the plan made too long.
        assertNull(QuickAdjust.cycleRefusal(plan, 0, 100, 5, 95, 5, false, STEP_TIME));
    }

    /* --------------------------------------------- 1b. a step that is exactly one cycle */

    /** Trainer Length L1: five 2:05 steps, each exactly one cycle (2:00 + 5 s). */
    private static List<Model.Preset> lengthL1() {
        return ladder(17, 7, 120, 5, 60, 20, 10, 120, 5, 60, 5, 625);
    }

    @Test
    void oneCycleIsTheStepsLengthWithinASecond() {
        assertTrue(QuickAdjust.oneCycle(120, 5, 125_000L));
        assertTrue(QuickAdjust.oneCycle(120, 5, 126_000L), "within a second");
        assertTrue(QuickAdjust.oneCycle(240, 0, 240_000L), "the warm-up's prime: one hold");
        assertTrue(!QuickAdjust.oneCycle(120, 5, 127_000L));
        assertTrue(!QuickAdjust.oneCycle(38, 5, 72_000L), "the device's ramp holds more cycles");
        assertTrue(!QuickAdjust.oneCycle(0, 0, 0L));
        assertEquals(135_000L, QuickAdjust.oneCycleStepMs(120, 5, 125_000L, 130, 5));
        assertEquals(135_300L, QuickAdjust.oneCycleStepMs(120, 5, 125_300L, 130, 5), "slack kept");
        assertEquals(115_000L, QuickAdjust.oneCycleStepMs(120, 5, 125_000L, 115, 0));
    }

    @Test
    void theOwnersLengthRampTakesALongerHoldAndItsStepFollows() {
        List<Model.Preset> plan = lengthL1();
        assertEquals(125_000L, plan.get(0).durMs);
        // Hold +10 s is not refused: the step is one cycle, its time moves with it.
        assertNull(QuickAdjust.cycleRefusal(plan, 0, 120, 5, 130, 5, false, STEP_TIME, 30_000L));
        assertNull(QuickAdjust.cycleRefusal(plan, 0, 120, 5, 120, 15, false, STEP_TIME, 30_000L),
            "a drop time moves it the same way");
        assertEquals(135_000L, QuickAdjust.oneCycleStepMs(120, 5, plan.get(0).durMs, 130, 5));
        HoldCarryOn.Plan co = HoldCarryOn.plan(120, 5, 130, 5, 60_000L, false, true, 1, false);
        assertEquals("Step is now 2:15 — the hold carries on from where it was.",
            HoldCarryOn.endSaid(co, 135_000L, false));
        // Rest of this ramp: every later one-cycle step moves too - each still one cycle.
        assertNull(QuickAdjust.cycleRefusal(plan, 0, 120, 5, 130, 5, true, STEP_TIME, 30_000L));
        long[][] later = QuickAdjust.oneCycleLater(plan, 0, RunEdit.remainingStepsOfSet(plan, 0));
        assertEquals(4, later.length, "the four steps after the one playing are one cycle");
        shift(plan, UH, 10, 40);
        for (int j = 0; j < later.length; j++) {
            Model.Preset p = plan.get((int) later[j][0]);
            p.durMs = QuickAdjust.oneCycleLengthMs(p, later[j][1]);
            assertEquals(130, p.uh);
            assertEquals(135_000L, p.durMs, "step " + (later[j][0] + 1) + " is 2:15, one cycle");
            assertTrue(QuickAdjust.oneCycle(p.uh, p.lh, p.durMs));
        }
    }

    /**
     * A ONE-CYCLE STEP'S HOLD CARRIES ON (HoldCarryOn). The owner's case: an L1 step 1:00 into
     * its 2:05, hold +10 s - the pump is sent the 1:10 left of the new 2:10 hold, the step ends
     * 2:15 from its start (its end moves by exactly the 10 s), and nothing is repeated.
     */
    @Test
    void aOneMinuteInHoldPlusTenCarriesOnToTheNewHold() {
        List<Model.Preset> plan = lengthL1();
        long stepMs = plan.get(0).durMs;
        HoldCarryOn.Plan co = HoldCarryOn.plan(120, 5, 130, 5, 60_000L, false, true, 1, false);
        assertEquals(HoldCarryOn.CONTINUE, co.kind);
        assertEquals(70, co.wireUh, "1:10 of hold left of the new 2:10");
        assertEquals(10_000L, co.endMoveMs, "the end moves by exactly the 10 s added");
        assertEquals(stepMs + 10_000L, 60_000L + co.cycleEndsInMs, "the step ends 2:15 from its start");
        assertEquals(-1L, co.rearmInMs, "one cycle: the next step arms itself");
        // The pump is never told to hold short of that end.
        long now = 1_000_000L;
        assertTrue(RunEdit.overrideHoldSeconds(now + co.cycleEndsInMs, now) >= co.wireUh);
        // A second change on the same step carries on the same way (judged one cycle as it began).
        assertNull(QuickAdjust.cycleRefusal(plan, 0, 130, 5, 140, 5, false, STEP_TIME, 90_000L, true));
        // A hold − late in the step: never under a few seconds left, never repeated.
        HoldCarryOn.Plan down = HoldCarryOn.plan(120, 5, 60, 5, 100_000L, false, true, 1, false);
        assertEquals(3, down.wireUh, "never less than a few seconds");
        assertEquals(-17_000L, down.endMoveMs, "the step ends 17 s sooner - 3 s of hold and its drop");
    }

    @Test
    void aOneCycleStepStaysInsideTheTwoHourStop() {
        List<Model.Preset> plan = lengthL1();
        // The run is at the two-hour stop already: a longer one-cycle step is refused.
        Model.Preset big = new Model.Preset();
        big.durMs = ComingSteps.CAP_MS - 625_000L;
        big.stageIdx = 1; big.pos = 0; big.setId = "Z"; big.uh = 30; big.lh = 5; big.up = 20;
        plan.add(big);
        assertNotNull(QuickAdjust.cycleRefusal(plan, 0, 120, 5, 130, 5, false, STEP_TIME, 0L),
            "past the two-hour stop");
        assertNull(QuickAdjust.cycleRefusal(plan, 0, 120, 5, 115, 5, false, STEP_TIME, 0L),
            "a shorter one never is");
    }

    @Test
    void laterStepsThatHoldSeveralCyclesKeepRuleA() {
        // The step playing is one cycle; the later ones hold several and are short.
        List<Model.Preset> plan = lengthL1();
        for (int k = 1; k < plan.size(); k++) { plan.get(k).uh = 40; plan.get(k).durMs = 50_000L; }
        assertNull(QuickAdjust.cycleRefusal(plan, 0, 120, 5, 130, 5, false, STEP_TIME, 0L));
        assertEquals("A later step's cycle can't be longer than its step, 0:50 — change this "
            + "step only, or lengthen Time per step first.",
            QuickAdjust.cycleRefusal(plan, 0, 120, 5, 130, 5, true, STEP_TIME, 0L));
    }

    @Test
    void timePerStepOnAOneCycleStepIsItsHoldDownToTheMinimum() {
        // Time per step − on a one-cycle step shortens the hold (the strip sends Hold −); at the
        // hold's minimum it stops, and says why.
        assertEquals("The hold is at its shortest, 0:10 — Time per step can't be shorter on a "
            + "step that is one cycle.", QuickAdjust.oneCycleShortest(STEP_TIME));
        assertEquals(10, QuickAdjust.MIN[QuickAdjust.HOLD]);
        // A step that holds several cycles keeps rule (a): never under one cycle.
        assertEquals("Time per step can't be shorter than one cycle, 2:05 — shorten the hold or "
            + "the drop time first.",
            QuickAdjust.stepLengthRefusal(130_000L, 120_000L, 125, STEP_TIME));
        assertNull(QuickAdjust.stepLengthRefusal(125_000L, 130_000L, 125, STEP_TIME),
            "a longer step always fits");
    }

    @Test
    void theWarmUpPrimeIsOneCycleAndItsLengthFollowsItsHold() {
        // t10 R-01: the trainer's warm-up is P2's reps now; the flat 4:00 prime is the manual
        // warm-up's (RxBuild#manualWarmUp), on a routine with no warm-up of its own.
        Model m = new Model();
        m.ceilKpa = 40;
        m.programLength.warm = Model.Program.WARM_NONE;
        m.rxManualWarm = true;
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_LENGTH, Plan.L1, 5, 120, 60, 20, false, 10.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        RxBuild.manualWarmUp(m, r);
        List<Model.Preset> all = m.plan(r);
        int prime = -1;
        for (int i = 0; i < all.size() && prime < 0; i++)
            if (QuickAdjust.modeAt(m, r, all, i, false) == QuickAdjust.MODE_WARM) prime = i;
        assertTrue(prime >= 0);
        Model.Preset p = all.get(prime);
        assertTrue(QuickAdjust.oneCycle(p.uh, p.lh, p.durMs), "a 4:00 hold in a 4:00 step");
        String warm = QuickAdjust.LABEL[QuickAdjust.WARM];
        // Hold + 10 s: allowed, the warm-up becomes 4:10.
        assertNull(QuickAdjust.cycleRefusal(all, prime, p.uh, p.lh, p.uh + 10, p.lh, false, warm, 0L));
        long to = QuickAdjust.oneCycleStepMs(p.uh, p.lh, p.durMs, p.uh + 10, p.lh);
        assertEquals(p.durMs + 10_000L, to);
        // Hold + 10 s 2:00 into the prime: 2:10 of hold left, the warm-up 10 s longer.
        HoldCarryOn.Plan co = HoldCarryOn.plan(p.uh, p.lh, p.uh + 10, p.lh, 120_000L, false, true, 1, false);
        assertEquals(p.uh + 10 - 120, co.wireUh);
        assertEquals(10_000L, co.endMoveMs);
        assertEquals("Warm-up step is now 4:10 — the hold carries on from where it was.",
            HoldCarryOn.endSaid(co, 250_000L, true));
        // No drop: the prime is never "in its drop".
        assertEquals(HoldCarryOn.CONTINUE,
            HoldCarryOn.plan(p.uh, p.lh, p.uh + 10, p.lh, 239_000L, true, true, 1, false).kind);
        assertEquals("The hold is at its shortest, 0:10 — Warm-up length can't be shorter on a "
            + "step that is one cycle.", QuickAdjust.oneCycleShortest(warm));
    }

    /* --------------------------------------------------- 2. the drop floor on the sheet */

    @Test
    void theSheetsDropStopsAtTheStripsFloor() {
        assertEquals(10, QuickAdjust.dropMax(4), "the floor");
        assertEquals(15, QuickAdjust.dropMax(15), "a drop already higher may stay");
        assertNull(QuickAdjust.dropRaiseRefusal(4, 10));
        assertEquals("The drop stops at " + Model.Fmt.p(10) + " — the drop floor.",
            QuickAdjust.dropRaiseRefusal(4, 11));
        assertNull(QuickAdjust.dropRaiseRefusal(15, 12), "coming down is always allowed");
        assertEquals("The drop stops at " + Model.Fmt.p(15) + " — the drop floor.",
            QuickAdjust.dropRaiseRefusal(15, 16));
        // The strip's own range says the same.
        assertEquals(QuickAdjust.MAX[QuickAdjust.DROP], QuickAdjust.dropMax(0));
    }

    /* ------------------------------------------------- 3. "flat there" once per ramp */

    @Test
    void theFlatNoteIsSaidOncePerRamp() {
        assertNotNull(QuickAdjust.rampFlatSaidOnce(2, 32, 7, -1), "the first time");
        assertNull(QuickAdjust.rampFlatSaidOnce(3, 32, 7, 7), "not again for the same ramp");
        assertNotNull(QuickAdjust.rampFlatSaidOnce(1, 32, 20, 7), "a new ramp is told");
        assertNull(QuickAdjust.rampFlatSaidOnce(0, 32, 20, 7), "nothing flat, nothing said");
    }

    @Test
    void timePerStepNeverGoesUnderOneCycle() {
        assertNull(QuickAdjust.stepLengthRefusal(72_000L, 67_000L, 43, STEP_TIME));
        assertEquals("Time per step can't be shorter than one cycle, 0:43 — shorten the hold or "
            + "the drop time first.",
            QuickAdjust.stepLengthRefusal(45_000L, 40_000L, 43, STEP_TIME));
        // Coming steps' time per step for a later ramp keeps the same rule.
        List<Model.Preset> plan = ascending(9);
        int cyc = ComingSteps.rampCycleSec(plan, 0, 9);
        assertEquals(43, cyc);
        assertEquals("Time per step can't be shorter than one cycle, 0:43 — shorten the hold or "
            + "the drop time first.",
            ComingSteps.rampStepTimeRefusal(45, 40, 9, 0, cyc));
        assertNull(ComingSteps.rampStepTimeRefusal(72, 67, 9, 0, cyc));
        assertNull(ComingSteps.rampStepTimeRefusal(45, 50, 9, 0, cyc));
    }

    @Test
    void restOfThisRampAsksEveryLaterStepAboutTheSameChange() {
        // The later steps are shorter than the step playing: 0:50 against 1:12.
        List<Model.Preset> plan = ascending(9);
        for (int k = 1; k < 9; k++) plan.get(k).durMs = 50_000L;
        // hold 38 → 48 fits the step playing (53 of 72) ...
        assertNull(QuickAdjust.cycleRefusal(plan, 0, 38, 5, 48, 5, false, STEP_TIME),
            "this step only: only this step is asked");
        // ... but moved into the later steps it would outgrow them (53 of 50).
        assertEquals("A later step's cycle can't be longer than its step, 0:50 — change this "
            + "step only, or lengthen Time per step first.",
            QuickAdjust.cycleRefusal(plan, 0, 38, 5, 48, 5, true, STEP_TIME));
    }

    @Test
    void aReshapeWhoseEndCycleOutgrowsTheStepsIsRefused() {
        List<Model.Preset> plan = ascending(9);
        assertNull(QuickAdjust.reshapeCycleRefusal(plan, 0, 38, 5, 60, 10, STEP_TIME));
        assertEquals("A cycle can't be longer than the step, 1:12 — lengthen Time per step first.",
            QuickAdjust.reshapeCycleRefusal(plan, 0, 38, 5, 80, 5, STEP_TIME));
    }

    /* --------------------------------------- 5. later edits read the ends as shifted */

    @Test
    void aRespreadReadsTheRampsEndsAsShiftedNeverAStaleCopy() {
        List<Model.Preset> plan = ascending(9);
        shift(plan, UP, 3, 40);
        int shiftedLast = plan.get(8).up;
        assertEquals(33, shiftedLast);
        // Re-spread the tail (steps 2..9) into 4: its ends are the plan's, as shifted.
        int shiftedSecond = plan.get(1).up;
        ComingSteps.respreadRamp(plan, 1, 8, 4, 72, 40);
        assertEquals(shiftedSecond, plan.get(1).up);
        assertEquals(shiftedLast, plan.get(4).up);
        assertOrder(plan, true, "re-spread after a shift");
        assertDropsUnderPulls(plan, "re-spread after a shift");
    }

    /* ------------------------------------------------------------- 7. the warm-up's ramp */

    @Test
    void theWarmUpRampKeepsTheSameRules() {
        Model m = new Model();
        m.ceilKpa = 40;
        m.programLength.warm = Model.Program.WARM_RAMP;
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_LENGTH, Plan.L1, 5, 120, 60, 20, false, 10.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        List<Model.Preset> all = m.plan(r);
        List<Model.Preset> warm = new ArrayList<Model.Preset>();
        int first = -1;
        for (int i = 0; i < all.size(); i++)
            if (QuickAdjust.modeAt(m, r, all, i, false) == QuickAdjust.MODE_WARM) {
                if (first < 0) first = i;
                warm.add(all.get(i));
            }
        assertTrue(warm.size() >= 2, "the warm-up climbs in steps");
        assertTrue(QuickAdjust.rampAt(m, all, first), "a ramp - so its scope starts on this step");
        assertDropsUnderPulls(warm, "warm-up ramp");
        assertOrder(warm, true, "warm-up ramp");
        Model.Preset p = all.get(first);
        int step = QuickAdjust.stepSec(p.durMs);
        assertTrue(p.uh + p.lh <= step, "the warm-up's own steps fit their cycle");
        String lenName = QuickAdjust.LABEL[QuickAdjust.lengthField(QuickAdjust.MODE_WARM)];
        // t10 R-01: P2's reps are one cycle a step - a longer hold makes a longer step, as the
        // prime's does - so it is not refused; a step of several cycles would be.
        if (QuickAdjust.oneCycle(p.uh, p.lh, p.durMs))
            assertNull(QuickAdjust.cycleRefusal(all, first, p.uh, p.lh, step, p.lh, false,
                                                lenName));
        else
            assertEquals("A cycle can't be longer than the step, " + Model.Fmt.t(step)
                    + " — lengthen Warm-up length first.",
                QuickAdjust.cycleRefusal(all, first, p.uh, p.lh, step, p.lh, false, lenName));
        // Rest of this ramp on the warm-up: a pull + moves only its later pulls.
        int[][] was = rows(all);
        int[] before = inForce(all, first, 40), after = before.clone();
        after[UP] += 2;
        int ofSet = RunEdit.remainingStepsOfSet(all, first);
        new SetShift().shift(all, first, ofSet, before, after, 40);
        // (t10 R-01: P2's last rep at the work it reached early is a set of its own.)
        for (int k = first + 1; k <= first + ofSet; k++) {
            assertEquals(Math.min(40, was[k][UP] + 2), all.get(k).up);
            assertEquals(was[k][UH], all.get(k).uh);
            assertEquals(was[k][SP], all.get(k).sp);
            assertTrue(all.get(k).lo < all.get(k).up);
        }
        // The work after the warm-up is another occurrence: untouched.
        for (int k = first + ofSet + 1; k < all.size(); k++)
            assertEquals(was[k][UP], all.get(k).up, "step " + (k + 1) + " is not the warm-up's");
        assertNotNull(QuickAdjust.rampSaid(true, 1));
    }
}
