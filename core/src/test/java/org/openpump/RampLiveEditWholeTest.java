package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * A RAMP STEP KEEPS WHOLE CYCLES AFTER A LIVE HOLD / DROP CHANGE (0.10 safety follow-up).
 *
 * Every ramp step is whole cycles of its own hold and drop, but a live change of the hold or the
 * drop time on a step of several cycles used to keep the step's length: with a new cycle the
 * step ended part-way through a hold and the pump went from that cut hold straight into the next
 * step's pull with no drop. Now the step playing ends as one of the pump's cycles ends - the
 * cycle end nearest the end it had, never before the cycle under way is over - and every later
 * step whose figures moved with it is put on whole cycles of its own new hold + drop.
 *
 * The sweep plays the engine's own arithmetic (SessionActivity's carryOnPlanFor, commitEdit,
 * keepSetsWhole, quickRampStep, the extend button, the re-arm after a carried-on hold) over
 * random edit sequences - hold and drop-time taps with "this step" or "rest of this ramp",
 * Time per step, one more cycle, a restart - against an independent model of the pump (an entry
 * STARTed runs its hold, then its drop, and repeats), and holds after every event: the step
 * playing ends exactly as one of the pump's cycles ends (so a drop comes before the next step's
 * pull), is never shorter than the cycle under way, and every later step is whole cycles of its
 * own hold + drop. WiringCheck invariant 247 pins the engine's roads to these helpers.
 */
class RampLiveEditWholeTest {

    private static final int CEIL = 40;

    /** A ramp of `steps` steps, each `k` whole cycles, then a rest. */
    private static List<Model.Preset> rampPlan(int steps, int uh, int lh, int uh2, int lh2, int k) {
        Model.Set s = Model.Set.ramp("R", "Work hold", 20, 5, uh, lh, 60, 30, 8, uh2, lh2, 80,
            steps, 3600);
        List<Model.Preset> plan = new ArrayList<Model.Preset>(s.ladder());
        for (Model.Preset p : plan) {
            p.setId = "R"; p.pos = 0; p.stageIdx = 1; RunEdit.asBuilt(p);
            p.durMs = (long) k * (p.uh + p.lh) * 1000L;
        }
        Model.Preset rest = new Model.Preset();
        rest.rest = true; rest.durMs = 180_000L; rest.stageIdx = 2; rest.setId = "rest"; rest.label = "Rest";
        plan.add(rest);
        return plan;
    }

    /** The pump as it runs an entry STARTed at `start`: its hold, then its drop, repeated. */
    private static final class Pump {
        long start; int hold; int drop;
        /** The full hold written again at this time after a carried-on hold, or −1. */
        long rearmAt = -1L; int rearmHold;
        long cycle() { return (long) (hold + drop) * 1000L; }
        void startEntry(long at, int h, int d) { start = at; hold = h; drop = d; rearmAt = -1L; }
    }

    /** The engine playing one ramp step, as SessionActivity does it. */
    private static final class Engine {
        final List<Model.Preset> plan; final int idx; final SetClock clock = new SetClock();
        final Pump pump = new Pump();
        int uh, lh;            // in force
        long now;
        Engine(List<Model.Preset> plan, int idx) {
            this.plan = plan; this.idx = idx;
            Model.Preset p = plan.get(idx);
            uh = p.uh; lh = p.lh;
            clock.start(idx, 0L, cycleMs(), Manual.cycles(uh + lh, 0, (int) (p.durMs / 1000L)));
            pump.startEntry(0L, uh, lh);
        }
        long cycleMs() { return (long) (uh + lh) * 1000L; }
        long end() { return plan.get(idx).durMs; }      // the step began at 0
        /** comingRetimeRunning: the step's end and its duration move together. */
        boolean retime(long d) {
            if (end() + d - now < RunEdit.MIN_REPOST_MS) return false;
            plan.get(idx).durMs = Math.max(1000L, plan.get(idx).durMs + d);
            return true;
        }
        boolean oneCycle() { return QuickAdjust.oneCycle(uh, lh, end()); }

        /** The re-arm after a carried-on hold, when it is due before `t` (CarryOnRearm): the
         *  carried entry again with its full hold, and the set that starts runs whole
         *  (keepSetsWhole: a restart). */
        void rearmsUpTo(long t) {
            if (pump.rearmAt < 0 || pump.rearmAt > t) return;
            long r = pump.rearmAt;
            pump.rearmAt = -1L;
            if (r >= end() || end() - r < RunEdit.MIN_REPOST_MS + 1000L) return;
            now = r;
            restart();
        }

        /** keepSetsWhole on a restart (a re-arm, a Revert, a convergence): the pump STARTs the
         *  entry with its full hold now; the step ends as one of its cycles ends. */
        void restart() {
            pump.startEntry(now, uh, lh);
            clock.restart(now, cycleMs());
            long move = RunEdit.rampEndMoveMs(cycleMs(), cycleMs(), end() - now,
                RunEdit.rampGrowMaxMs(plan, idx));
            String cap = move > 0 ? ComingSteps.addRefusal(ComingSteps.totalMs(plan), move) : null;
            if (cap == null && move != 0) retime(move);
        }

        /** A live hold / drop-time change to (nUh, nLh), "rest of this ramp" or this step only:
         *  refused as the strip refuses it, else carried on (applyOverride, commitEdit). */
        boolean change(int nUh, int nLh, boolean restOfRamp) {
            int left = RunEdit.remainingStepsOfSet(plan, idx);
            String no = QuickAdjust.cycleRefusal(plan, idx, uh, lh, nUh, nLh, restOfRamp,
                QuickAdjust.LABEL[QuickAdjust.STEP_TIME], now - 0L, false);
            if (no != null) return false;
            HoldCarryOn.Plan co = HoldCarryOn.plan(uh, lh, nUh, nLh, clock.inCycleMs(now), false,
                false, clock.setsLeft(now), true);
            if (co.kind == HoldCarryOn.AFTER_DROP) {
                // The change waits for the drop to end and goes in with the next hold.
                long at = now + co.waitMs;
                if (at >= end() - RunEdit.MIN_REPOST_MS) return false;    // the step ends first
                now = at;
                // The pump began its next hold at the drop's end, of the entry it runs.
                co = HoldCarryOn.plan(uh, lh, nUh, nLh, clock.inCycleMs(now), false, false,
                    clock.setsLeft(now), true);
                if (co.kind != HoldCarryOn.CONTINUE) return false;
            }
            long newCycle = (long) (nUh + nLh) * 1000L;
            // carryOnPlanFor: a ramp's step of several cycles ends as a cycle ends.
            co.endMoveMs = RunEdit.rampEndMoveMs(co.cycleEndsInMs, newCycle, end() - now,
                RunEdit.rampGrowMaxMs(plan, idx));
            if (co.endMoveMs > 0 && ComingSteps.addRefusal(ComingSteps.totalMs(plan), co.endMoveMs) != null)
                return false;
            // The entry written for the cycle under way, started now.
            pump.startEntry(now, co.wireUh, nLh);
            if (co.rearmInMs >= 0) { pump.rearmAt = now + co.rearmInMs; }
            int dUh = nUh - uh, dLh = nLh - lh;
            uh = nUh; lh = nLh;
            // commitEdit reads the later one-cycle steps (and their slack) before the shift.
            long[][] laterOne = restOfRamp ? QuickAdjust.oneCycleLater(plan, idx, left) : new long[0][];
            // commitEdit: "Rest of this ramp" moves the later steps' figures by the change...
            if (restOfRamp)
                for (int k = 1; k <= left; k++) {
                    Model.Preset p = plan.get(idx + k);
                    p.uh = RunEdit.clampSeconds(p.uh + dUh);
                    p.lh = RunEdit.clampSeconds(p.lh + dLh);
                }
            // ...setClockAfterChange: carried on, the step's end moved...
            clock.carryOn(now, now + co.cycleEndsInMs, newCycle);
            if (co.endMoveMs != 0) retime(co.endMoveMs);
            // ...the later one-cycle steps one cycle again (their own rule)...
            for (int j = 0; j < laterOne.length; j++) {
                Model.Preset p = plan.get((int) laterOne[j][0]);
                p.durMs = QuickAdjust.oneCycleLengthMs(p, laterOne[j][1]);
            }
            // ...and the later steps put on whole cycles of their own hold + drop.
            if (restOfRamp)
                RunEdit.wholeCyclesLater(plan, idx, left, ComingSteps.CAP_MS - ComingSteps.totalMs(plan));
            return true;
        }

        /** The strip's Time per step, one tap (quickRampStep). */
        boolean timePerStep(int dir) {
            int len = (int) ((end() + 500L) / 1000L);
            int el = (int) (now / 1000L);
            int cyc = uh + lh;
            int next = QuickAdjust.stepTimeNext(len, dir, cyc);
            int steps = RunEdit.stepOfSet(plan, idx)[1];
            String why = QuickAdjust.stepTimeRefusal(len, next, cyc, steps);
            if (why == null) why = QuickAdjust.refusal(QuickAdjust.STEP_TIME, len, next - len, -1, el, CEIL);
            if (why != null) return false;
            long d = (long) (next - len) * 1000L;
            if (QuickAdjust.stepLengthRefusal(end(), end() + d, cyc, "Time per step") != null) return false;
            if (d > 0 && ComingSteps.addRefusal(ComingSteps.totalMs(plan), d) != null) return false;
            return retime(d);
        }

        /** The extend button on a ramp's step of several cycles: one whole cycle. */
        boolean extend() {
            long ext = RunEdit.rampExtendMs(uh + lh);
            int len = (int) ((end() + 500L) / 1000L);
            if (QuickAdjust.stepTimeRefusal(len, len + (int) (ext / 1000L), uh + lh,
                    RunEdit.stepOfSet(plan, idx)[1]) != null) return false;
            if (ComingSteps.addRefusal(ComingSteps.totalMs(plan), ext) != null) return false;
            return retime(ext);
        }
    }

    /** The step playing ends as one of the pump's cycles ends - after its drop - and not before
     *  the cycle under way is over; the SetClock agrees; every later step is whole cycles. */
    private static void assertWholeNow(Engine e, String what) {
        long end = e.end();
        Pump p = e.pump;
        long firstEnd = p.start + p.cycle();
        assertTrue(end > e.now, what + ": the step's end is behind the clock");
        assertTrue(end >= firstEnd, what + ": the step ends at " + end + " ms, before the cycle "
            + "under way does at " + firstEnd + " ms - the pump never reaches its drop");
        if (p.rearmAt >= 0 && p.rearmAt < end) {
            // The carried entry repeats its short hold until the full hold goes back on: the
            // step must be past that re-arm, which then starts a cycle the step ends with.
            assertTrue(end - p.rearmAt >= RunEdit.MIN_REPOST_MS + 1000L || end == firstEnd,
                what + ": the step ends inside the carried cycle's re-arm");
        } else {
            assertEquals(0L, (end - p.start) % p.cycle(), what + ": the step ends " + (end - p.start)
                + " ms into an entry of " + p.cycle() + " ms cycles - inside a hold, and the next "
                + "step's pull follows with no drop");
        }
        int left = RunEdit.remainingStepsOfSet(e.plan, e.idx);
        for (int k = 1; k <= left; k++) {
            Model.Preset q = e.plan.get(e.idx + k);
            long c = (long) Math.max(1, q.uh + q.lh) * 1000L;
            assertTrue(q.durMs >= c, what + ": later step " + k + " is shorter than its cycle");
            assertEquals(0L, q.durMs % c, what + ": later step " + k + " runs " + q.durMs
                + " ms, not whole " + c + " ms cycles");
        }
        assertTrue(ComingSteps.totalMs(e.plan) <= ComingSteps.CAP_MS, what + ": past the two-hour stop");
    }

    @Test
    void anyLiveEditSequenceLeavesEveryStepOnWholeCyclesWithADropBetweenHolds() {
        Random r = new Random(247);
        int played = 0, edits = 0, moved = 0;
        for (int t = 0; t < 1500; t++) {
            int steps = 2 + r.nextInt(8);
            int uh = 10 + r.nextInt(90), lh = 3 + r.nextInt(15);
            int uh2 = 10 + r.nextInt(90), lh2 = 3 + r.nextInt(15);
            // Whole cycles a step, inside its share of the hour, as every built ramp is.
            int kMax = Model.Set.rampStepMaxSec(steps) / (Math.max(uh, uh2) + Math.max(lh, lh2));
            if (kMax < 2) continue;
            int k = 2 + r.nextInt(Math.min(6, kMax - 1));
            List<Model.Preset> plan = rampPlan(steps, uh, lh, uh2, lh2, k);
            int idx = r.nextInt(steps - 1);           // a step with later ones after it
            Engine e = new Engine(plan, idx);
            String what = "ramp #" + t;
            assertWholeNow(e, what + " as built");
            int n = 1 + r.nextInt(8);
            for (int j = 0; j < n; j++) {
                // The next event some time into what is left of the step.
                long left = e.end() - e.now;
                if (left < 3000L) break;
                e.now += 500L + (long) (r.nextDouble() * (left - 1500L));
                e.rearmsUpTo(e.now);
                if (e.now >= e.end() - 1000L) break;
                if (e.oneCycle()) break;                 // a one-cycle step: its own rule
                long before = e.end();
                int kind = r.nextInt(10);
                boolean did;
                if (kind < 6) {
                    int nUh = RunEdit.clampSeconds(e.uh + (r.nextInt(9) - 4) * QuickAdjust.STEP[QuickAdjust.HOLD]);
                    int nLh = Math.max(1, RunEdit.clampSeconds(e.lh + (r.nextInt(5) - 2)));
                    if (nUh < 5) nUh = 5;
                    did = e.change(nUh, nLh, r.nextBoolean());
                } else if (kind < 8) {
                    did = e.timePerStep(r.nextBoolean() ? 1 : -1);
                } else if (kind < 9) {
                    did = e.extend();
                } else {
                    e.restart();
                    did = true;
                }
                if (did) edits++;
                if (e.end() != before) moved++;
                assertWholeNow(e, what + " after event " + (j + 1) + " (kind " + kind + ")");
            }
            // To the step's end: any re-arm still due goes on, and the step ends as a drop ends.
            e.rearmsUpTo(e.end());
            assertWholeNow(e, what + " at its end");
            played++;
        }
        assertTrue(played >= 700, "the ramps played (" + played + ")");
        assertTrue(edits >= 2000, "the edits went in (" + edits + ")");
        assertTrue(moved >= 500, "the steps' ends moved (" + moved + ")");
    }

    @Test
    void theHoldChangeOfTheFindingsEndsTheStepOnADrop() {
        // 4:12 a step, 6 cycles of 37 s + 5 s. At 1:00 (18 s into the second hold) the hold goes
        // to 40 s: the hold under way carries on to 40 (22 s more), so that cycle ends at 1:27
        // and each after it takes 45 s - 2:12, 2:57, 3:42, 4:27. Kept at 4:12 the step would
        // have ended 30 s into a hold; it now ends at 4:27, the nearest cycle end.
        List<Model.Preset> plan = rampPlan(2, 37, 5, 37, 5, 6);
        assertEquals(252_000L, plan.get(0).durMs);
        Engine e = new Engine(plan, 0);
        e.now = 60_000L;
        assertTrue(e.change(40, 5, true));
        assertEquals(267_000L, e.end(), "4:27 - whole cycles of 45 s from where the pump is");
        assertWholeNow(e, "the findings' change");
        // The later step moved with it: 6 cycles of 45 s.
        assertEquals(270_000L, plan.get(1).durMs);
        assertEquals("This step now ends 0:15 later — whole cycles of the new hold and drop, "
            + "ending on a drop.", RunEdit.rampEndSaid(15_000L));
        // Time per step + from there is one more of the pump's 45 s cycles: 5:12, not 5:15.
        assertTrue(e.timePerStep(1));
        assertEquals(312_000L, e.end());
        assertWholeNow(e, "Time per step after the change");
    }

    @Test
    void theStepPlayingEndsOnTheNearestCycleEndNeverBeforeTheOneUnderWay() {
        // Nearest: 10 s left, the cycle under way ends in 4 s, cycles of 5 s: 4 + 5 = 9 (1 s off)
        // beats 14 (4 s off).
        assertEquals(-1_000L, RunEdit.rampEndMoveMs(4_000L, 5_000L, 10_000L, 60_000L));
        // A tie goes to the fewer cycles: 4 + 5 = 9 or 14, 11.5 s left.
        assertEquals(-2_500L, RunEdit.rampEndMoveMs(4_000L, 5_000L, 11_500L, 60_000L));
        // Never before the cycle under way is over - one cycle at least - whatever the room.
        assertEquals(20_000L, RunEdit.rampEndMoveMs(30_000L, 45_000L, 10_000L, 0L));
        // The room (the two-hour stop, the share of the hour): fewer cycles, not more.
        // 80 s left, cycles ending at 5, 50, 95 s: 95 is nearest (+15 s) - with no room, 50.
        assertEquals(-30_000L, RunEdit.rampEndMoveMs(5_000L, 45_000L, 80_000L, 0L));
        assertEquals(-30_000L, RunEdit.rampEndMoveMs(5_000L, 45_000L, 80_000L, 14_999L));
        assertEquals(15_000L, RunEdit.rampEndMoveMs(5_000L, 45_000L, 80_000L, 15_000L));
        // A step that does not cycle keeps its end.
        assertEquals(0L, RunEdit.rampEndMoveMs(5_000L, 0L, 80_000L, 60_000L));
    }

    @Test
    void laterStepsGoOnWholeCyclesOfTheirOwnFiguresInsideTheStop() {
        assertEquals(270_000L, RunEdit.rampStepWholeMs(252_000L, 45, 0L), "5.6 cycles: 6");
        assertEquals(225_000L, RunEdit.rampStepWholeMs(247_500L, 45, 0L), "5.5: a tie, 5");
        assertEquals(45_000L, RunEdit.rampStepWholeMs(10_000L, 45, 0L), "never under one");
        assertEquals(90_000L, RunEdit.rampStepWholeMs(252_000L, 45, 100_000L), "its share");
        assertEquals(225_000L, RunEdit.rampStepWholeFloorMs(252_000L, 45));
        List<Model.Preset> plan = rampPlan(4, 37, 5, 37, 5, 6);
        for (int k = 1; k < 4; k++) plan.get(k).uh = 40;
        assertEquals(3 * 18_000L, RunEdit.wholeCyclesLater(plan, 0, 3, ComingSteps.CAP_MS));
        for (int k = 1; k < 4; k++) assertEquals(270_000L, plan.get(k).durMs);
        assertEquals(252_000L, plan.get(0).durMs, "the step playing is the engine's own");
        // No room left under the two-hour stop: each floored instead (5.7 cycles of 44 s: 5,
        // not 6), still whole.
        for (int k = 1; k < 4; k++) { plan.get(k).uh = 39; plan.get(k).durMs = 252_000L; }
        assertEquals(3 * -32_000L, RunEdit.wholeCyclesLater(plan, 0, 3, 0L));
        for (int k = 1; k < 4; k++) assertEquals(220_000L, plan.get(k).durMs);
        // With room: the nearest, 6 of them.
        for (int k = 1; k < 4; k++) plan.get(k).durMs = 252_000L;
        assertEquals(3 * 12_000L, RunEdit.wholeCyclesLater(plan, 0, 3, ComingSteps.CAP_MS));
        for (int k = 1; k < 4; k++) assertEquals(264_000L, plan.get(k).durMs);
        // The strip asks what the later steps would add against the two-hour stop.
        List<Model.Preset> full = rampPlan(2, 37, 5, 37, 5, 6);
        Model.Preset big = new Model.Preset();
        big.durMs = ComingSteps.CAP_MS - ComingSteps.totalMs(full) - 1_000L;
        big.stageIdx = 3; big.setId = "Z";
        full.add(big);
        assertNotNull(QuickAdjust.cycleRefusal(full, 0, 37, 5, 40, 5, true, "Time per step"),
            "the later step's 18 s of whole cycles would pass the stop");
        assertNull(QuickAdjust.cycleRefusal(full, 0, 37, 5, 40, 5, false, "Time per step"),
            "this step only: the later step keeps its figures");
    }

    @Test
    void timePerStepMovesExactlyOneCycleFromWhereTheStepIs() {
        assertEquals(294, QuickAdjust.stepTimeNext(252, 1, 42));
        assertEquals(210, QuickAdjust.stepTimeNext(252, -1, 42));
        // A step a live change re-timed to 4:27 of 45 s cycles: one of those, not a multiple.
        assertEquals(312, QuickAdjust.stepTimeNext(267, 1, 45));
        assertEquals(222, QuickAdjust.stepTimeNext(267, -1, 45));
        // Under one cycle: refused as before.
        assertEquals("Time per step can't be shorter than one cycle, 0:45 — shorten the hold or "
            + "the drop time first.",
            QuickAdjust.stepTimeRefusal(45, QuickAdjust.stepTimeNext(45, -1, 45), 45, 2));
    }

    @Test
    void theExtendButtonOnARampStepAddsOneCycleAndSaysSo() {
        assertEquals(42_000L, RunEdit.rampExtendMs(42));
        assertEquals(12_000L, RunEdit.rampExtendMs(12));
        assertEquals("+0:42 step", RunEdit.rampExtendLabel(42));
        assertEquals("+2:05 step", RunEdit.rampExtendLabel(125));
        assertEquals("One more cycle on this step of the ramp, 0:42", RunEdit.rampExtendSaid(42));
    }

    @Test
    void plusStepKeepsEveryStepInsideItsShareOfTheHour() {
        // Two steps of 25:00 (30:00 each is their share): a third would share the hour 20:00 a
        // step, and these are longer.
        List<Model.Preset> plan = rampPlan(2, 250, 5, 250, 5, 6);
        assertEquals(1_530_000L, plan.get(0).durMs);
        assertEquals("With one more step, " + QuickAdjust.stepTimeLongest(1200, 3)
            + " Shorten Time per step first.", RunEdit.addStepRefusal(plan, 0));
        assertEquals(-1, RunEdit.addRampStep(plan, 0, CEIL), "refused: nothing added");
        // Four whole cycles a step (17:00) fit a third step's share.
        for (int k = 0; k < 2; k++) plan.get(k).durMs = 4 * 255_000L;
        assertNull(RunEdit.addStepRefusal(plan, 0));
        int at = RunEdit.addRampStep(plan, 0, CEIL);
        assertEquals(2, at);
        for (int k = 0; k < 3; k++)
            assertTrue(QuickAdjust.stepSec(plan.get(k).durMs) <= Model.Set.rampStepMaxSec(3));
        // Only the steps still to come are asked: a long step already run is history.
        List<Model.Preset> ran = rampPlan(3, 250, 5, 250, 5, 3);
        ran.get(0).durMs = 1_530_000L;
        assertNull(RunEdit.addStepRefusal(ran, 1), "step 1 has run");
    }
}
