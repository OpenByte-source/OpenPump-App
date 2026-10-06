package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * EVERY RAMP STEP IS WHOLE CYCLES OF ITS OWN HOLD AND DROP (0.10, the owner's decision).
 *
 * A ramp step has no length on the wire: the pump repeats the step's hold and drop until the
 * app STARTs the next slot. Manual and Library ramps used to give each step dur / steps in
 * milliseconds - never whole cycles - so every step ended part-way through a hold and the pump
 * went straight on to the next step's pull with no drop (a 2-step 8:42 ramp of 37 s + 5 s: 4:21
 * a step, 6.21 cycles, 46 s under pressure across the seam), and nothing stopped a step being
 * shorter than one cycle (nine 1:40 steps of a 4:10 hold: fifteen minutes of pull, no drop at
 * all, while the editor said "drop for 0:05"). The run screen, meanwhile, capped a step at the
 * wire's 255 s - the width of one hold byte, never a step's length.
 *
 * Pinned here: no plan the model can produce from any Set - built by hand and never clamped,
 * clamped by an editor, run as a manual cycle, saved and loaded, imported from a share code, or
 * written by the trainer - has a step shorter than one cycle or ending inside a hold; the two
 * replay cases now run whole cycles with a drop between every hold, on the simulated pump too;
 * the editors' ramps are inside the strip's and Coming steps' ranges; and the run's own edits
 * (Time per step, "+ step", "+30 s step", Coming steps' steps and time per step) keep whole
 * cycles. WiringCheck invariant 246 pins the roads to these rules.
 */
class ModelWholeCycleRampTest {

    private static final int CEIL = 40;

    private String unitWas;
    @BeforeEach void inHg() { unitWas = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_INHG; }
    @AfterEach void back() { Model.Fmt.unit = unitWas; }

    /* ------------------------------------------------------------------ the one property */

    /** Every step whole cycles of its own (wire) hold + drop, at least one, and every step of
     *  one ramp the same number of them. */
    private static void assertWhole(List<Model.Preset> steps, String what) {
        long k = -1;
        for (int i = 0; i < steps.size(); i++) {
            Model.Preset p = steps.get(i);
            assertTrue(p.uh >= 0 && p.uh <= Proto.WIRE_HOLD_MAX, what + ": step " + (i + 1)
                + " holds " + p.uh + " s, off the wire");
            assertTrue(p.lh >= 0 && p.lh <= Proto.WIRE_HOLD_MAX, what + ": step " + (i + 1)
                + " drops " + p.lh + " s, off the wire");
            long cyc = Math.max(1, p.uh + p.lh) * 1000L;
            assertTrue(p.durMs >= cyc, what + ": step " + (i + 1) + " runs " + p.durMs
                + " ms, shorter than its " + cyc + " ms cycle - it never reaches its drop");
            assertEquals(0L, p.durMs % cyc, what + ": step " + (i + 1) + " runs " + p.durMs
                + " ms, not whole " + cyc + " ms cycles - it ends inside a hold");
            if (k < 0) k = p.durMs / cyc;
            else assertEquals(k, p.durMs / cyc, what + ": step " + (i + 1)
                + " runs a different number of cycles from step 1");
        }
    }

    private static final int[] HOLDS = { 0, 5, 37, 120, 250, 255, 300 };
    private static final int[] DROPS = { 0, 5, 30, 255 };
    private static final int[] STEPS = { -1, 0, 1, 2, 3, 5, 7, 9, 10, 20 };
    private static final int[] DURS = { -5, 0, 10, 30, 100, 261, 522, 900, 1800, 3600, 5000 };

    private static Model.Set ramp(int uh, int lh, int uh2, int lh2, int steps, int dur) {
        return Model.Set.ramp("r", "Ramp", 27, 18, uh, lh, 75, 35, 18, uh2, lh2, 90, steps, dur);
    }

    @Test
    void noSetTheModelCanHoldPlansAStepShorterThanACycleOrEndingInAHold() {
        int built = 0;
        for (int uh : HOLDS) for (int uh2 : HOLDS) for (int lh : DROPS) for (int lh2 : DROPS)
            for (int n : STEPS) for (int dur : DURS) {
                String what = "ramp " + uh + "+" + lh + " -> " + uh2 + "+" + lh2 + " s, " + n
                    + " steps, " + dur + " s";
                // As built and never clamped: the ladder alone keeps the rule.
                Model.Set raw = ramp(uh, lh, uh2, lh2, n, dur);
                assertWhole(raw.ladder(), what + " (raw)");
                // As every editor, load and import leaves it: clamped.
                Model.Set s = ramp(uh, lh, uh2, lh2, n, dur);
                s.clamp(CEIL);
                List<Model.Preset> lad = s.ladder();
                assertWhole(lad, what + " (clamped)");
                assertEquals(s.steps, lad.size(), what + ": the steps it says are the steps it plays");
                long sum = 0;
                for (Model.Preset p : lad) sum += p.durMs;
                assertEquals(s.dur * 1000L, sum, what + ": the duration it says is what its steps run");
                assertTrue(s.dur <= Model.Set.RAMP_MAX_SEC, what + ": past the hour, " + s.dur);
                // Idempotent: the snapped set snaps to itself.
                int was = s.dur, wasSteps = s.steps;
                s.clamp(CEIL);
                s.snapRamp();
                assertEquals(was, s.dur, what + ": snapped twice moved the duration");
                assertEquals(wasSteps, s.steps, what + ": snapped twice moved the steps");
                // Inside the strip's and Coming steps' range: a step is the app's clock, held to
                // its share of the hour, and never the wire's 255 s.
                int share = Model.Set.rampStepMaxSec(lad.size());
                for (Model.Preset p : lad) {
                    int sec = QuickAdjust.stepSec(p.durMs);
                    assertTrue(sec <= share, what + ": a " + sec + " s step past its share " + share);
                    assertTrue(sec <= QuickAdjust.MAX[QuickAdjust.STEP_TIME], what + ": past the strip");
                    assertTrue(sec <= ComingSteps.STEP_TIME_MAX_SEC, what + ": past Coming steps");
                }
                built++;
            }
        assertTrue(built > 80_000, "the sweep ran (" + built + ")");
    }

    @Test
    void aManualRampPlansWholeCyclesThroughTheRunPath() {
        Random r = new Random(7);
        for (int t = 0; t < 3000; t++) {
            Model m = new Model();
            Model.Set stored = ramp(r.nextInt(300), r.nextInt(40), r.nextInt(300), r.nextInt(40),
                                    r.nextInt(12) - 1, r.nextInt(4200) - 100);
            stored.up = 12 + r.nextInt(30); stored.up2 = 12 + r.nextInt(30);
            stored.lo = r.nextInt(stored.up); stored.lo2 = r.nextInt(stored.up2);
            m.sets.add(Manual.ephemeral(stored, m.ceilKpa));
            List<Model.Preset> plan = m.plan(Manual.routine(m.ceilKpa));
            assertFalse(plan.isEmpty());
            assertWhole(plan, "manual ramp #" + t);
            assertEquals(m.routineSec(Manual.routine(m.ceilKpa)), sumSec(plan),
                "manual ramp #" + t + ": the run's announced length is its steps'");
        }
    }

    @Test
    void aSavedOrImportedRampIsCorrectedOnLoadAndOnImport() throws Exception {
        // A saved file from before the rule: the screenshot's ramp, 2 x 4:21 of a 42 s cycle.
        Model src = new Model();
        Model.Set old = ramp(37, 5, 37, 5, 2, 522);
        old.id = "s9"; old.name = "Old ramp";
        src.sets.add(old);
        Model.Set tooShort = ramp(250, 5, 250, 5, 9, 900);
        tooShort.id = "s10"; tooShort.name = "Short steps";
        src.sets.add(tooShort);
        Model.Routine rt = Model.Routine.of("rq", "Ramps", new String[]{ "s9", "s10" });
        src.routines.add(rt);
        Model loaded = Model.fromJson(src.toJson());
        Model.Set a = loaded.set("s9"), b = loaded.set("s10");
        assertEquals(2 * 6 * 42, a.dur, "8:42 of a 42 s cycle loads as 2 steps x 6 cycles, 8:24");
        assertEquals(9 * 255, b.dur, "nine steps of a 4:15 cycle are one cycle each at the least");
        assertEachRampWhole(loaded.plan(loaded.routine("rq")), "a loaded routine");
        // A share code from an older phone carries the old figures; the import corrects them.
        String code = rt.toShareCode(src);
        Model dst = new Model();
        Model.Routine got = dst.importShareCode(code);
        assertNotNull(got);
        for (String id : got.stages.get(0).setIds) {
            Model.Set s = dst.set(id);
            assertTrue(s.imported);
            assertEquals(s.rampDurSec(), s.dur, "an imported ramp arrives in whole cycles a step");
        }
        assertEachRampWhole(dst.plan(got), "an imported routine");
    }

    /** {@link #assertWhole} for each ramp occurrence of a plan on its own - two ramps run their
     *  own numbers of cycles. */
    private static void assertEachRampWhole(List<Model.Preset> plan, String what) {
        List<Model.Preset> occ = new ArrayList<Model.Preset>();
        for (int i = 0; i <= plan.size(); i++) {
            Model.Preset p = i < plan.size() ? plan.get(i) : null;
            if (!occ.isEmpty() && (p == null || !RunEdit.sameOccurrence(occ.get(0), p))) {
                assertWhole(occ, what + " '" + occ.get(0).label + "'");
                occ.clear();
            }
            if (p != null && !p.rest) occ.add(p);
        }
    }

    /** RampClimbTest's trainer: ramps in every set. */
    private static Model trainer() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerMonthsPumping = 4;
        m.ceilKpa = CEIL;
        m.programGirth.work = Model.Program.WORK_RAMP_IN_SET;
        m.programLength.work = Model.Program.WORK_RAMP_IN_SET;
        return m;
    }

    @Test
    void theTrainersRampsAreUnchangedAndWhole() {
        int ramps = 0;
        int[][] rxs = { { Plan.L1, 5, 20 }, { Plan.L1, 10, 24 }, { Plan.L2, 14, 27 },
                        { Plan.L3, 6, 30 }, { Plan.L1, 3, 18 } };
        for (int warm = 0; warm < 2; warm++)
            for (int[] x : rxs) {
                Model m = trainer();
                m.rxWarmRamp = warm == 1;
                Mint.Rx rx = new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, x[0], x[1], 120, 180, x[2],
                                         false, x[1] * 2.0);
                Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                assertNotNull(r);
                for (Model.Stage st : r.stages)
                    for (String id : st.setIds) {
                        Model.Set s = m.set(id);
                        if (s == null || !s.ramp) continue;
                        ramps++;
                        List<Model.Preset> lad = s.ladder();
                        String what = "trainer '" + s.name + "' (L" + x[0] + ", " + x[1] + " sets)";
                        assertWhole(lad, what);
                        // UNCHANGED: the trainer writes whole cycles by construction, so the
                        // rule is the even split it always had, to the millisecond - for a
                        // ramp of one timing. P2's warm-up (t10 R-01) walks its holds 30 -> 60 s:
                        // each of its steps is one whole cycle of its own.
                        for (int i = 0; i < lad.size(); i++) {
                            Model.Preset p = lad.get(i);
                            if (s.uh == s.uh2 && s.lh == s.lh2)
                                assertEquals(s.dur * 1000L / lad.size(), p.durMs, what + ": moved");
                            else
                                assertEquals(s.stepCycleSec(i, lad.size()) * 1000L, p.durMs,
                                             what + ": a walking step not one whole cycle");
                        }
                    }
                assertTrue(eachRampStepWhole(m, r) > 0, "trainer plan L" + x[0] + " x" + x[1]
                    + ": no ramp step in the plan");
            }
        assertTrue(ramps >= 8, "the trainer built ramps (" + ramps + ")");
    }

    /** Every preset of the plan that is a ramp's step, each asked on its own (different ramps
     *  run different numbers of cycles); how many there were. */
    private static int eachRampStepWhole(Model m, Model.Routine r) {
        int n = 0;
        for (Model.Preset p : m.plan(r)) {
            Model.Set s = p.setId == null ? null : m.set(p.setId);
            if (s == null || !s.ramp || p.rest) continue;
            List<Model.Preset> one = new ArrayList<Model.Preset>();
            one.add(p);
            assertWhole(one, "trainer step '" + p.label + "'");
            n++;
        }
        return n;
    }

    private static int sumSec(List<Model.Preset> plan) {
        long t = 0;
        for (Model.Preset p : plan) t += p.durMs;
        return (int) (t / 1000L);
    }

    /* ------------------------------------------------------------ the two replay cases */

    /** The stored manual set exactly as the Quick-run editor keeps it (the findings' harness). */
    private static List<Model.Preset> manualPlan(Model m, int dur, int up2, int uh, int lh, int st) {
        Model.Set stored = Model.Set.fixed("manual", "Manual run", 27, 18, uh, lh, 75, dur);
        stored.ramp = true; stored.up2 = up2; stored.lo2 = 18; stored.uh2 = uh; stored.lh2 = lh;
        stored.sp2 = 75; stored.steps = st;
        m.sets.add(Manual.ephemeral(stored, m.ceilKpa));
        return m.plan(Manual.routine(m.ceilKpa));
    }

    @Test
    void theScreenshotsRampRunsSixWholeCyclesAStep() {
        Model m = new Model();
        List<Model.Preset> plan = manualPlan(m, 522, 35, 37, 5, 2);
        assertEquals(2, plan.size());
        for (Model.Preset p : plan) {
            assertEquals(252_000L, p.durMs, "4:12 a step - 6 whole cycles of 42 s, not 4:21");
            assertEquals(37, p.uh);
            assertEquals(5, p.lh);
        }
        assertWhole(plan, "the screenshot's ramp");
        assertEquals(12, PlannedTime.planCycles(plan), "12 cycles planned, 12 holds run");
        assertEquals("2 steps × 6 cycles (4:12 per step)", m.set(Manual.ID).rampSaid());
        // The strip's Time per step is inside its range, and + is not refused by a wire limit.
        int v = QuickAdjust.stepSec(plan.get(0).durMs);
        assertNull(QuickAdjust.refusal(QuickAdjust.STEP_TIME, v, 42, -1, 22, CEIL));
        assertNull(QuickAdjust.stepTimeRefusal(v, QuickAdjust.stepTimeNext(v, 1, 42), 42, 2));
        assertEquals(294, QuickAdjust.stepTimeNext(v, 1, 42), "one more whole cycle");
        assertEquals(210, QuickAdjust.stepTimeNext(v, -1, 42), "one fewer");
        // The drop's + at its planned −5.3 may go on (owner, 2026-09-30) - up to 1.0 inHg
        // under the pull, where it is said as one.
        assertNull(QuickAdjust.stepRefusal(QuickAdjust.DROP, 18, 1, 27, -1, CEIL));
        assertEquals(RunEdit.dropGapSaid(),
            QuickAdjust.stepRefusal(QuickAdjust.DROP, RunEdit.dropTopKpa(27), 1, 27, -1, CEIL));
        // The strip's header says where the ramp goes.
        assertEquals("THIS RAMP · " + Model.Fmt.pBare(27) + " → " + Model.Fmt.p(35),
            QuickAdjust.rampHeadAt(plan, 0, CEIL));
        assertEquals(2, playsWithADropBetweenEveryHold(plan, CEIL, "the screenshot's ramp"),
            "both step changes came after a drop");
    }

    @Test
    void nineStepsOfAFourMinuteHoldEachReachTheirDrop() {
        Model m = new Model();
        List<Model.Preset> plan = manualPlan(m, 900, 35, 250, 5, 9);
        assertEquals(9, plan.size());
        for (Model.Preset p : plan)
            assertEquals(255_000L, p.durMs, "one whole 4:15 cycle a step, never 1:40 of a hold");
        assertWhole(plan, "nine steps of 4:10 + 0:05");
        assertEquals(9, PlannedTime.planCycles(plan));
        assertEquals(9, playsWithADropBetweenEveryHold(plan, CEIL, "nine steps of 4:10 + 0:05"),
            "every step change came after a drop");
    }

    /**
     * THE SIMULATED PUMP, played with the app's timing (START of slot i at the end of step i-1,
     * STOP at the plan's end): at every cycle's end inside a step, and at every step change,
     * the cuff has come down from the pull through its drop - never still at the pull, as a
     * step cut inside a hold leaves it (the old 4:21 step: 9 s into a hold, at the pull, when
     * the next step's START came). SimPump's own phase clock runs a fraction of a second slow
     * each phase (it drops the overshoot of its 240 ms steps), so the cases here keep a few
     * cycles a step and a drop time longer than that drift; the cuff is then at least a kPa
     * under the pull at every cycle's end.
     */
    private static int playsWithADropBetweenEveryHold(List<Model.Preset> plan, int ceil,
                                                      String what) {
        SimPump pump = new SimPump();
        for (int i = 0; i < Proto.SLOTS; i++) pump.write(Proto.deleteSlot(0));
        for (Model.Preset p : plan) {
            int wu = RunEdit.clampUpper(p.up, ceil);
            pump.write(Proto.addPreset(p.sp, wu, p.uh,
                RunEdit.clampLower(RunEdit.capDrop(p, p.lo), wu), p.lh));
        }
        pump.tick(1000);
        long t = 0;
        final long tick = 250L;
        int[] steps = { 0 };
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            pump.write(Proto.startSlot(i));
            long cyc = (p.uh + p.lh) * 1000L;
            int wu = RunEdit.clampUpper(p.up, ceil);
            for (long into = 0; into < p.durMs; into += tick) {
                pump.tick(tick);
                long at = into + tick;
                // The end of each cycle, the step's own end - the next START - included.
                if (p.lh > 0 && p.lo < wu - 1 && at % cyc == 0) {
                    assertTrue(pump.pressureKpa() <= wu - 1.0, what + ": step " + (i + 1)
                        + " at " + at / 1000 + " s is at " + pump.pressureKpa() + " kPa, still "
                        + "at its " + wu + " kPa pull - a hold with no drop after it");
                    if (at == p.durMs) steps[0]++;
                }
            }
            t += p.durMs;
        }
        pump.write(Proto.stop());
        pump.tick(1000);
        assertTrue(t > 0);
        return steps[0];
    }

    @Test
    void anyManualRampOnTheSimulatedPumpChangesStepOnlyAfterADrop() {
        Random r = new Random(11);
        int played = 0;
        for (int t = 0; t < 200 && played < 40; t++) {
            Model m = new Model();
            int uh = 5 + r.nextInt(60), lh = 4 + r.nextInt(8);
            List<Model.Preset> plan = manualPlan(m, 30 + r.nextInt(900), 21 + r.nextInt(13), uh, lh,
                                                 2 + r.nextInt(8));
            assertWhole(plan, "sim ramp #" + t);
            if (m.set(Manual.ID).rampCycles() > 3) continue;   // SimPump's drift: see above
            assertEquals(plan.size(), playsWithADropBetweenEveryHold(plan, m.ceilKpa, "sim ramp #" + t),
                "sim ramp #" + t + ": every step change came after a drop");
            played++;
        }
        assertTrue(played >= 20, "the simulated ramps ran (" + played + ")");
    }

    /* ------------------------------------------------------- the editors' own steppers */

    @Test
    void theEditorsStepARampOneWholeCycleAStepAndSayHowItDivides() {
        Model.Set s = ramp(37, 5, 37, 5, 2, 522);
        s.clamp(CEIL);
        assertEquals(504, s.dur);
        assertEquals("2 steps × 6 cycles (4:12 per step)", s.rampSaid());
        s.dur = s.rampDurStepped(1);
        s.clamp(CEIL);
        assertEquals(588, s.dur, "+ is one whole cycle a step: 2 x 7 x 42 s");
        s.dur = s.rampDurStepped(-1);
        s.clamp(CEIL);
        assertEquals(504, s.dur, "- is one fewer");
        // Down to one cycle a step and no further.
        for (int i = 0; i < 20; i++) { s.dur = s.rampDurStepped(-1); s.clamp(CEIL); }
        assertEquals(84, s.dur, "one cycle a step is the least");
        assertEquals("2 steps × 1 cycle (0:42 per step)", s.rampSaid());
        // A hold change keeps the duration on whole cycles of the new cycle, floored.
        s.dur = 504; s.uh = 40; s.uh2 = 40; s.clamp(CEIL);
        assertEquals(2 * 5 * 45, s.dur, "8:24 of a 45 s cycle is 5 cycles a step");
        // Up to a step's share of the hour and no further.
        for (int i = 0; i < 200; i++) { s.dur = s.rampDurStepped(1); s.clamp(CEIL); }
        assertTrue(s.dur / 2 <= Model.Set.rampStepMaxSec(2) && s.dur / 2 + 45 > Model.Set.rampStepMaxSec(2),
            "the longest a two-step ramp's step grows is its half of the hour (" + s.dur + ")");
        // A ramp that walks its timing has a shortest and a longest step, and says both.
        Model.Set walk = ramp(30, 5, 45, 5, 5, 426);
        walk.clamp(CEIL);
        assertEquals("5 steps × 2 cycles (1:10–1:40 per step)", walk.rampSaid());
    }

    @Test
    void aRampWhoseCycleWouldNotFitItsShareOfTheHourPlaysFewerSteps() {
        Model.Set s = ramp(255, 255, 255, 255, 9, 3600);
        s.clamp(CEIL);
        assertEquals(7, s.steps, "nine 8:30 cycles do not fit an hour; seven do");
        assertEquals(7 * 510, s.dur);
        assertWhole(s.ladder(), "the longest cycles");
    }

    /* ------------------------------------------------------ the run's own edits (live) */

    private static List<Model.Preset> rampPlan(int dur, int steps, int uh, int lh) {
        Model.Set s = Model.Set.ramp("R", "Work hold", 20, 5, uh, lh, 60, 30, 8, uh, lh, 80, steps, dur);
        s.clamp(CEIL);
        List<Model.Preset> plan = new ArrayList<Model.Preset>(s.ladder());
        for (Model.Preset p : plan) { p.setId = "R"; p.pos = 0; p.stageIdx = 1; RunEdit.asBuilt(p); }
        Model.Preset rest = new Model.Preset();
        rest.rest = true; rest.durMs = 180_000L; rest.stageIdx = 2; rest.setId = "rest"; rest.label = "Rest";
        plan.add(rest);
        return plan;
    }

    @Test
    void plusStepAddsAStepOfTheRampsWholeCycles() {
        List<Model.Preset> plan = rampPlan(4 * 3 * 65, 4, 60, 5);
        int at = RunEdit.addRampStep(plan, 0, CEIL);
        assertEquals(4, at);
        assertEquals(3 * 65_000L, plan.get(at).durMs, "three whole cycles, as its ramp");
        assertWhole(plan.subList(0, 5), "a ramp given a step");
        // The step playing lengthened past whole cycles: the added one is still whole.
        plan.get(4).durMs = 100_000L;
        assertEquals(3 * 65_000L, RunEdit.addedStepMs(plan, 4), "the others' whole cycles");
    }

    @Test
    void comingStepsKeepsWholeCycles() {
        List<Model.Preset> plan = rampPlan(5 * 2 * 43, 5, 38, 5);
        assertEquals(2, ComingSteps.rampCycles(plan, 0, 5));
        // Time per step: one whole cycle a step each way, never under one.
        assertNull(ComingSteps.rampStepTimeRefusal(plan, 0, 5, 3, ComingSteps.totalMs(plan), 43));
        ComingSteps.setRampCycles(plan, 0, 5, 3);
        assertWhole(plan.subList(0, 5), "Coming steps' + time per step");
        assertEquals(129_000L, plan.get(0).durMs);
        assertEquals("Time per step can't be shorter than one cycle, 0:43 — shorten the hold or "
            + "the drop time first.",
            ComingSteps.rampStepTimeRefusal(plan, 0, 5, 0, ComingSteps.totalMs(plan), 43));
        // Past the twenty-minute advisory, refused as the advisory.
        assertNotNull(ComingSteps.rampStepTimeRefusal(plan, 0, 5, 6, ComingSteps.totalMs(plan), 43));
        // Past a step's share of the hour: 12:00 for five steps.
        assertEquals(QuickAdjust.stepTimeLongest(720, 5),
            ComingSteps.rampStepTimeRefusal(plan, 0, 5, 17, 0L, 43));
        // Steps: re-spread and put in the ramp's whole cycles, as comingRampSteps does.
        ComingSteps.respreadRamp(plan, 0, 5, 7, ComingSteps.rampStepSec(plan, 0, 5), CEIL);
        ComingSteps.setRampCycles(plan, 0, 7, 3);
        assertWhole(plan.subList(0, 7), "Coming steps' + steps");
        // Any time per step asked of setRampStepTime lands on whole cycles.
        for (int sec = 1; sec < 400; sec += 7) {
            ComingSteps.setRampStepTime(plan, 0, 7, sec);
            assertWhole(plan.subList(0, 7), "setRampStepTime " + sec);
        }
        // A later step moved one cycle by the strip's Time per step stays whole.
        for (int dir = -1; dir <= 1; dir += 2) {
            long to = QuickAdjust.stepTimeNextMs(plan.get(3), dir);
            assertEquals(0L, to % ((plan.get(3).uh + plan.get(3).lh) * 1000L));
        }
    }

    @Test
    void plusThirtyOnARampStepIsWholeCycles() {
        // EXPECTATION CHANGED (owner follow-up, 0.10): the button on a ramp's step of several
        // cycles adds ONE whole cycle and is labelled with it ("+0:12 step",
        // RampLiveEditWholeTest) - it used to add the whole cycles making at least 30 s under a
        // "+30 s step" label. Still whole cycles, as this test has always held; the "at least
        // 30 s" bound belonged to the old label.
        for (int c = 1; c <= 510; c++) {
            long ms = RunEdit.rampExtendMs(c);
            assertEquals(0L, ms % (c * 1000L), c + " s cycle");
            assertEquals(c * 1000L, ms, c + " s cycle: exactly one cycle");
        }
        assertEquals(42_000L, RunEdit.rampExtendMs(42), "one 42 s cycle");
        assertEquals(12_000L, RunEdit.rampExtendMs(12), "one 12 s cycle");
    }

    @Test
    void theStepTimeBoundIsTheAppsClockNotTheWire() {
        // 255 s is one hold or one drop on the wire; a step can be longer.
        assertNull(QuickAdjust.stepRefusal(QuickAdjust.STEP_TIME, 261, 1, -1, 22, CEIL),
            "4:21 + 0:05 is inside the strip");
        assertNull(ComingSteps.rampStepTimeRefusal(261, 303, 2, 0L, 42), "a 5:03 step of two");
        assertEquals(Model.Set.rampStepMaxSec(2), QuickAdjust.MAX[QuickAdjust.STEP_TIME]);
        assertEquals(Model.Set.rampStepMaxSec(2), ComingSteps.STEP_TIME_MAX_SEC);
        for (int n = 2; n <= Proto.SLOTS; n++) {
            int share = Model.Set.rampStepMaxSec(n);
            assertNull(QuickAdjust.stepTimeRefusal(share - 10, share, 10, n));
            assertEquals(QuickAdjust.stepTimeLongest(share, n),
                QuickAdjust.stepTimeRefusal(share, share + 10, 10, n));
            assertFalse(QuickAdjust.stepTimeLongest(share, n).contains("pump takes"));
        }
        // The hold keeps the wire's true 255 s.
        assertEquals("4:15 is the longest the pump takes.",
            QuickAdjust.stepRefusal(QuickAdjust.HOLD, 255, 1, -1, -1, CEIL));
        assertEquals(255, QuickAdjust.MAX[QuickAdjust.HOLD]);
    }
}
