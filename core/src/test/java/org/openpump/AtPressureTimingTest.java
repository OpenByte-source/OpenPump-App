package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * C6 - "AT PRESSURE ONLY": THE SET CLOCK, THE NET AND THE PLAN MUST AGREE.
 *
 * Found on the emulator: a Trainer routine ("the rest", 7 cycles of 2:00 at 5.0 inHg, 14:35)
 * run with Set timing at "At pressure only" took 29:34, and its summary read "time under
 * pressure 7.5 min of 14.0 min planned · −47%". Every set clock had run its full time at
 * pressure; the net said half of that had been under pressure.
 *
 * These tests drive a whole run through the simulated pump the app itself runs against
 * (SimPump), with the SAME pieces the run uses: Model#plan for the presets, TupClock for each
 * 400 ms tick of the set clock (SessionActivity#tickTupTiming), TupClock#inDrop for the drop
 * half a net frame is marked with (SessionActivity#recordHoldFrame), Session for the net,
 * and PlannedTime for what the routine planned. What cannot come along is the Activity's
 * Handler; the loop below stands in for it, tick for tick.
 *
 * D2 - AND THE SET'S TIME LIMIT (TupClock.SetLimit), as the Activity applies it: the clock
 * may lengthen a set to at most max(planned, min(2 x planned, 20 min)). The device's own
 * case now ends at that limit at the default 2 % (below); the agreement these tests pin is
 * unchanged by it, because the limit only decides WHEN a set ends, never what is counted.
 */
class AtPressureTimingTest {

    /** SessionActivity.RUN_TICK_MS - how often the set clock is asked. */
    private static final long TICK_MS = 400L;
    /** The loop's own step: a common divisor of the pump's 240 ms telemetry and the tick. */
    private static final long STEP_MS = 80L;
    /** SessionActivity.LINK_TIMEOUT_MS - a reading older than this is not a reading. */
    private static final long LINK_TIMEOUT_MS = 5000L;

    /* ------------------------------------------------------------------ fixtures */

    private static Model enrolledL1() {
        Model m = new Model();
        m.ceilKpa = 40;
        m.trainerEnrolled = true;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerGirth.level = Plan.L1;
        return m;
    }

    private static Model.Routine trainerRoutine(Model m, String id, Model.Stage... stages) {
        Model.Routine r = new Model.Routine();
        r.id = id;
        r.name = "Trainer · Girth L1 · " + id;
        r.trainerTrack = Plan.TRACK_GIRTH_INTERVAL;
        r.trainerLevel = Plan.L1;
        for (Model.Stage st : stages) r.stages.add(st);
        m.routines.add(r);
        return r;
    }

    /** The device's routine, set for set: 7 cycles of 120 s at 17 kPa (5.0 inHg, whole kPa)
     *  and a 5 s drop to 3 kPa, 875 s, speed 75 % - 14.0 min planned under pressure. */
    private static Model.Routine theRest(Model m) {
        m.sets.add(Model.Set.fixed("s-work", "Work hold", 17, 3, 120, 5, 75, 875));
        return trainerRoutine(m, "the rest",
            Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "s-work" }));
    }

    /** Pulses whose drop is as long as their hold, after a warm-up (out of net) and a rest. */
    private static Model.Routine pulses(Model m) {
        m.sets.add(Model.Set.fixed("s-warm", "Ease in", 12, 12, 60, 0, 60, 60));
        m.sets.add(Model.Set.fixed("s-pulse", "Pulses", 17, 8, 10, 10, 75, 300));
        return trainerRoutine(m, "pulses",
            Model.Stage.of("Warm-up", Model.STAGE_WARM, new String[]{ "s-warm" }),
            Model.Stage.restOf("Rest", 30),
            Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "s-pulse" }));
    }

    /** A five-minute hold, past the wire's 255 s, so each repetition is stitched: a 255 s
     *  chunk that holds, then a 45 s hold carrying the 10 s drop. */
    private static Model.Routine longHolds(Model m) {
        m.sets.add(Model.Set.fixed("s-long", "Long hold", 27, 10, 300, 10, 80, 620));
        return trainerRoutine(m, "long holds",
            Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "s-long" }));
    }

    /** A ramp climbing through the counting line: steps at 14, 16, 18 and 20 kPa. The two
     *  below the line are planned to count nothing and must still end. */
    private static Model.Routine rampThroughTheLine(Model m) {
        m.sets.add(Model.Set.ramp("s-ramp", "Climb", 14, 8, 20, 10, 75,
                                  20, 12, 20, 10, 75, 4, 480));
        return trainerRoutine(m, "ramp",
            Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "s-ramp" }));
    }

    /** A hand-built routine the plan does not score. */
    private static Model.Routine handBuilt(Model m) {
        m.sets.add(Model.Set.fixed("s-hand", "Hand pulses", 20, 10, 30, 10, 75, 400));
        Model.Routine r = new Model.Routine();
        r.id = "hand";
        r.name = "Hand built";
        r.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "s-hand" }));
        m.routines.add(r);
        return r;
    }

    /* -------------------------------------------------------------- the run loop */

    /** What one simulated run produced. */
    private static final class Ran {
        long wallMs, plannedMs;
        /** Per commanding preset: the time its clock MOVED (wall less pushes). */
        long movedMs, pushedMs;
        /** D2 - what the set's time limit refused to push, and the sets that ended short of
         *  their time at pressure because of it (what the run says as each one ends). */
        long cutMs;
        int endedAtLimit;
        double netSec, grossSec;
        /** The set clock's moving time spent at or above the line in the hold half - the
         *  seconds the clock counted as delivered. */
        long countedMs;
        /** Pump cycles started, and presets armed - what the slack below is counted in. */
        int cycles, armed;
        /** The plan's cycles and work sets as the run started, and counted again over the
         *  live plan at the end, once the clock has lengthened it the way
         *  SessionActivity#setPresetDuration does; and the cycles delivered, counted the way
         *  SessionActivity#deliveredCycles counts the recording. */
        int cyclesAsStarted, setsAsStarted, cyclesLive, setsLive, cyclesDone;
    }

    /**
     * One run of `r`, the way SessionActivity drives it: each preset armed on the pump and
     * its deadline set to its duration; every telemetry frame noted into the net (marked a
     * drop by the preset's own cycle, as recordHoldFrame does); every 400 ms, with Set timing
     * at pressure, one TupClock tick whose push moves the deadline out.
     */
    private static Ran run(Model m, Model.Routine r, boolean atPressure) {
        List<Model.Preset> plan = m.plan(r);
        Ran out = new Ran();
        out.cyclesAsStarted = PlannedTime.planCycles(plan);
        out.setsAsStarted = Model.plannedSets(r, plan);
        SimPump pump = new SimPump();
        Session s = new Session();
        long now = 0L;
        s.beginRun(now);
        double floor = m.scoringFloorKpa(r, now);
        boolean scored = !Double.isNaN(floor);
        double netLine = scored ? floor - m.tupCountTolKpa(floor) : 0.0;
        double lastKpa = 0.0;
        boolean lastNoReading = true;
        long lastSampleAt = -1L;
        TupClock.SetLimit limit = new TupClock.SetLimit();
        for (int idx = 0; idx < plan.size(); idx++) {
            Model.Preset p = plan.get(idx);
            out.plannedMs += p.durMs;
            limit.start(plan, idx);                    // resetTupWatch, as each preset starts
            Model.Stage st = r.stages.get(p.stageIdx);
            boolean outOfNet = st.outOfNet();
            if (p.rest) {
                pump.write(Proto.stop());
                long end = now + p.durMs;
                while (now < end) {
                    long dt = Math.min(STEP_MS, end - now);
                    pump.tick(dt);
                    now += dt;
                    for (Proto.Sample x : pump.drainSamples()) {
                        lastKpa = x.kpa; lastNoReading = x.noReading; lastSampleAt = now;
                        s.noteHoldFrame(now, x.kpa, x.noReading, 0, Session.HOLD_PHASE_REST,
                                        p.stageIdx, outOfNet, false);
                    }
                }
                continue;
            }
            for (int k = 0; k < Proto.SLOTS; k++) pump.write(Proto.deleteSlot(0));
            pump.write(Proto.addPreset(p.sp, p.up, p.uh, p.lo, p.lh));
            pump.write(Proto.startSlot(0));
            int up = Math.min(p.up, m.ceilKpa);
            Model.Set owner = m.set(p.setId);
            int phase = owner != null && owner.ramp ? Session.HOLD_PHASE_RAMP
                                                    : Session.HOLD_PHASE_HOLD;
            long startedAt = now;
            long fireAt = now + p.durMs;
            long dropLeft = TupClock.dropPartMs(p.uh, p.lh, p.lo, up, p.cyclePart, p.durMs);
            long lastTick = now;
            double line = TupClock.lineKpa(up, scored && !outOfNet ? netLine : 0.0);
            while (now < fireAt) {
                // Never past the deadline: the Advance fires AT it, not on the next step.
                long dt = Math.min(STEP_MS, fireAt - now);
                pump.tick(dt);
                now += dt;
                for (Proto.Sample x : pump.drainSamples()) {
                    lastKpa = x.kpa; lastNoReading = x.noReading; lastSampleAt = now;
                    boolean drop = TupClock.inDrop(p.uh, p.lh, p.lo, up, p.cyclePart,
                                                   now - startedAt);
                    s.noteHoldFrame(now, x.kpa, x.noReading, up, phase, p.stageIdx,
                                    outOfNet, drop);
                }
                if (now - lastTick >= TICK_MS) {
                    long step = now - lastTick;
                    lastTick = now;
                    long into = now - startedAt;
                    long dropMs = TupClock.dropMsBetween(p.uh, p.lh, p.lo, up, p.cyclePart,
                                                         into - step, into);
                    boolean reading = !lastNoReading && lastSampleAt > 0
                                   && now - lastSampleAt < LINK_TIMEOUT_MS;
                    if (reading && lastKpa >= line) out.countedMs += step - dropMs;
                    if (atPressure) {
                        TupClock.Step t = TupClock.tick(step, dropMs, reading, lastKpa, line,
                                                        dropLeft);
                        dropLeft -= t.dropUsedMs;
                        long push = limit.allow(plan, idx, t.pushMs);
                        out.cutMs += t.pushMs - push;
                        fireAt += push;
                        p.durMs += push;               // setPresetDuration, as the run does
                        out.pushedMs += push;
                    }
                }
            }
            if (limit.reachedLimitAtEnd(plan, idx)) out.endedAtLimit++;   // the Advance
            out.movedMs += (now - startedAt);
            out.armed++;
            out.cycles += (int) ((now - startedAt) / ((long) Math.max(1, p.uh + p.lh) * 1000L)) + 1;
            if (!p.cyclePart)
                out.cyclesDone += Manual.cycles(p.uh, p.lh, (int) ((now - startedAt) / 1000L));
        }
        out.cyclesLive = PlannedTime.planCycles(plan);
        out.setsLive = Model.plannedSets(r, plan);
        pump.write(Proto.stop());
        out.wallMs = now;
        out.movedMs -= out.pushedMs;
        double[] tup = scored ? s.netGrossTupSec(floor, m.tupCountTolKpa(floor))
                              : new double[]{ 0.0, 0.0 };
        out.netSec = tup[0];
        out.grossSec = tup[1];
        return out;
    }

    /** The routine's own time under pressure, counted from the net's line - exactly what
     *  fileSession stamps on the record (PlannedTime#stamp). */
    private static double plannedTupSec(Model m, Model.Routine r) {
        double floor = m.scoringFloorKpa(r, 0L);
        return PlannedTime.underPressureSec(m, r, floor - m.tupCountTolKpa(floor));
    }

    /**
     * How close the net and the plan must come, counted in the two clocks' own grain - never
     * a percentage that could hide a systematic gap.
     *
     * The set clock splits each 400 ms tick exactly where the cycle turns, but judges the held
     * part by the latest reading; the net sums telemetry frames (every 240 ms), each judged
     * whole by its own reading and its own half of the cycle. So once a cycle the net can
     * place the turn into the drop up to a frame early, and where the pressure crosses the
     * line the two can disagree by up to a tick (crossings up and down pull opposite ways and
     * mostly cancel). Plus one tick for each preset armed.
     */
    private static double slackSec(Ran ran) {
        double frame = SimPump.TELEM_PERIOD_MS / 1000.0, tick = TICK_MS / 1000.0;
        return ran.cycles * (frame + tick) + ran.armed * tick;
    }

    /* --------------------------------------------------------- the device's case */

    /**
     * D2 CHANGED THIS EXPECTATION. It asserted that at 2 % the device's set runs until it has
     * delivered its whole 840 s (it took 3165 s) - the "no cap, as ruled" behaviour the
     * final safety review found release-blocking. The owner's rule ends the set at its time
     * limit, 20:00 for a 14:35 set, and the net then says what was delivered. What this test
     * protected - a set that DOES run its full time at pressure delivers the plan's time
     * under pressure - is kept by the test after it, where the set finishes inside its limit.
     */
    @Test
    void atPressureTheDevicesRoutineEndsAtItsTimeLimitAndTheNetSaysWhatItGot() {
        Model m = enrolledL1();
        Model.Routine r = theRest(m);
        double planned = plannedTupSec(m, r);
        assertEquals(840.0, planned, 0.001, "7 holds of 120 s: 14.0 min under pressure");

        Ran ran = run(m, r, true);

        assertEquals(1_200_000L, ran.wallMs, "the 14:35 set ends at 20:00, its limit");
        assertTrue(ran.cutMs > 0, "the limit, not the set's time at pressure, ended it");
        assertEquals(1, ran.endedAtLimit, "and the run says so as it ends");
        Model.Sess filed = new Model.Sess();
        filed.setsAtLimit = ran.endedAtLimit;         // fileSession, from runSetsAtLimit
        assertEquals("1 set reached its time limit: the pump didn't stay at pressure long "
            + "enough to finish it.", PlannedTime.rows(m, filed, true).limit,
            "and the summary says it too, so the sentence is never lost under it");
        assertTrue(ran.netSec < planned - 60.0,
            "no pretending: the net is what was delivered - " + ran.netSec + " s of "
            + planned + " s");
        assertEquals(ran.countedMs / 1000.0, ran.netSec, slackSec(ran),
            "and it is still exactly what the set clock counted as at pressure");
    }

    @Test
    void atPressureASetThatFinishesInsideItsLimitDeliversItsPlannedTimeUnderPressure() {
        Model m = enrolledL1();
        m.tupCountPct = 10.0;   // the line at 15.24 kPa: the coasting pump stays over it longer
        Model.Routine r = theRest(m);
        double planned = plannedTupSec(m, r);
        assertEquals(840.0, planned, 0.001, "7 holds of 120 s, whatever the tolerance");

        Ran ran = run(m, r, true);

        assertTrue(ran.wallMs < 1_200_000L, "it finished inside its limit: " + ran.wallMs);
        assertEquals(0L, ran.cutMs, "the limit took nothing from it");
        assertEquals(0, ran.endedAtLimit);
        assertEquals(planned, ran.netSec, slackSec(ran),
            "a set clock that ran its full time at pressure delivered the time the plan "
            + "asked for under pressure - net " + ran.netSec + " s of " + planned + " s, "
            + "run " + ran.wallMs / 1000 + " s");
    }

    @Test
    void atPressureTheClockCountsExactlyTheSecondsTheNetCounts() {
        Model m = enrolledL1();
        Model.Routine r = theRest(m);

        Ran ran = run(m, r, true);

        assertEquals(ran.countedMs / 1000.0, ran.netSec, slackSec(ran),
            "what the set clock counted as at pressure is what the net counted");
    }

    /* ------------------------------------------------------------ the drop halves */

    @Test
    void atPressureAPulsesPlannedDropsAreNotOwedAsTimeUnderPressure() {
        Model m = enrolledL1();
        Model.Routine r = pulses(m);
        double planned = plannedTupSec(m, r);
        assertEquals(150.0, planned, 0.001, "15 pulses of 10 s; the warm-up and rest plan none");

        Ran ran = run(m, r, true);

        assertEquals(planned, ran.netSec, slackSec(ran),
            "a 10 s drop is part of the set's shape, not 10 s more it owes at pressure - "
            + "net " + ran.netSec + " s of " + planned + " s");
    }

    @Test
    void atPressureAStitchedHoldsDropIsKnownForADrop() {
        Model m = enrolledL1();
        Model.Routine r = longHolds(m);
        double planned = plannedTupSec(m, r);
        assertEquals(600.0, planned, 0.001, "two 5:00 holds");

        Ran ran = run(m, r, true);

        assertEquals(planned, ran.netSec, slackSec(ran),
            "each repetition's last chunk carries its drop, and that drop is the set's own "
            + "- net " + ran.netSec + " s of " + planned + " s");
    }

    @Test
    void atPressureARampThroughTheLineDeliversItsPlanAndItsLowStepsStillEnd() {
        Model m = enrolledL1();
        Model.Routine r = rampThroughTheLine(m);
        double planned = plannedTupSec(m, r);
        assertTrue(planned > 0, "the steps above the line plan time under pressure");

        Ran ran = run(m, r, true);

        assertEquals(planned, ran.netSec, slackSec(ran),
            "net " + ran.netSec + " s of " + planned + " s");
        assertTrue(ran.wallMs < 4L * ran.plannedMs,
            "a step commanded below the counting line is timed at its own target and ends");
    }

    /* ------------------------------------------------------- what does not change */

    @Test
    void byTheClockARunTakesExactlyItsPlannedTimeAndNothingIsPushed() {
        Model m = enrolledL1();
        for (Model.Routine r : new Model.Routine[]{ theRest(m), pulses(m), longHolds(m),
                                                    rampThroughTheLine(m) }) {
            Ran ran = run(m, r, false);
            assertEquals(0L, ran.pushedMs, r.name + ": the clock is never pushed by the clock");
            assertEquals(ran.plannedMs, ran.wallMs, r.name + ": wall time is the plan");
        }
    }

    @Test
    void anUnscoredRoutineIsStillTimedAtItsOwnTargetAndItsDropsAreItsOwn() {
        Model m = enrolledL1();
        Model.Routine r = handBuilt(m);
        assertTrue(Double.isNaN(m.scoringFloorKpa(r, 0L)), "the plan does not score it");
        Model.Preset p = m.plan(r).get(0);

        Ran ran = run(m, r, true);

        long holdPart = TupClock.holdPartMs(p.uh, p.lh, p.lo, p.up, p.cyclePart, p.durMs);
        assertEquals(holdPart / 1000.0, ran.countedMs / 1000.0, slackSec(ran),
            "the set runs until its hold half has been at its own target");
    }

    /* ------------------------------------------------- what the run planned (item 2) */

    @Test
    void atPressureThePlanIsTheRoutineAsItStartedNotTheSetsTheClockLengthened() {
        Model m = enrolledL1();
        Model.Routine r = theRest(m);

        Ran ran = run(m, r, true);

        assertEquals(7, ran.cyclesAsStarted, "the routine as it started: 7 cycles");
        assertEquals(7, ran.setsAsStarted, "and 7 work sets");
        assertTrue(ran.cyclesDone > 7, "the pump went on cycling while sets waited for "
            + "pressure: " + ran.cyclesDone + " cycles");
        assertEquals(ran.cyclesDone, ran.cyclesLive,
            "counted over the plan the clock lengthened, planned reads as many as ran - the "
            + "device's 'cycles 13 of 13 planned', which is why it is counted at the start");
        assertTrue(ran.setsLive > 7, "and the set count the deload rule's early-hit fraction "
            + "is read against grew with it (" + ran.setsLive + ")");
    }

    @Test
    void theCyclesRowSaysWhyARunTimedAtPressureRanMoreCyclesThanItPlanned() {
        Model m = enrolledL1();
        Model.Routine r = theRest(m);
        Ran ran = run(m, r, true);
        Model.Sess s = sess(ran.cyclesDone, ran.cyclesAsStarted, (int) (ran.pushedMs / 1000L));

        String row = PlannedTime.rows(m, s, true).cycles;

        assertEquals(ran.cyclesDone + " (7 planned — sets ran longer to reach their time at "
            + "pressure)", row);
        assertEquals(row, PlannedTime.rows(m, s, false).cycles,
            "a fact about this run, whatever Set timing says when the summary is reopened");
    }

    @Test
    void byTheClockTheCyclesRowIsWhatItWas() {
        Model m = enrolledL1();
        assertEquals("7 of 7 planned", PlannedTime.rows(m, sess(7, 7, 0), false).cycles);
        assertEquals("5 of 7 planned", PlannedTime.rows(m, sess(5, 7, 0), false).cycles,
            "stopped early");
        assertEquals("8 of 7 planned", PlannedTime.rows(m, sess(8, 7, 0), true).cycles,
            "+30 s by the clock is an over-run, not a set waiting for pressure");
        assertEquals("5 of 7 planned", PlannedTime.rows(m, sess(5, 7, 90), true).cycles,
            "timed at pressure and stopped early is still a shortfall");
    }

    @Test
    void aRecordFiledBeforeThisShowsTheCountsItWasFiledWith() throws Exception {
        Model m = enrolledL1();
        // The device's record: filed "13 of 13" by the old count, and nothing about the clock.
        Model.Sess old = sess(13, 13, -1);
        assertEquals("13 of 13 planned", PlannedTime.rows(m, old, true).cycles,
            "history is not rewritten: the counts it was filed with, and no reason it cannot "
            + "know");
        org.json.JSONObject o = sess(26, 7, 1375).toJson();
        assertEquals(1375, Model.Sess.fromJson(o).tupPausedSec, "survives a save");
        o.remove("tupP");
        assertEquals(-1, Model.Sess.fromJson(o).tupPausedSec,
            "absent means not recorded, never 0 (which would claim the clock held nothing)");
    }

    private static Model.Sess sess(int done, int planned, int heldSec) {
        Model.Sess s = new Model.Sess();
        s.id = "sess-c6";
        s.routineId = "the rest";
        s.cyclesDone = done;
        s.cyclesPlanned = planned;
        s.tupPausedSec = heldSec;
        return s;
    }

    /* ------------------------------------------------------------------ the rule */

    @Test
    void theLineIsTheNetsForASetTheNetCountsAndTheSetsOwnOtherwise() {
        double net = 16.594;
        assertEquals(net, TupClock.lineKpa(17, net), 1e-9,
            "a set at the level is timed from the line its net counts from");
        assertEquals(net, TupClock.lineKpa(24, net), 1e-9,
            "a set above the level too: the net counts it from that line");
        assertEquals(14 - Session.bandKpa(14), TupClock.lineKpa(14, net), 1e-9,
            "a set below the line is not in the net or the plan: timed at its own target");
        assertEquals(20 - Session.bandKpa(20), TupClock.lineKpa(20, 0.0), 1e-9,
            "a run the plan does not score: timed at its own target, as it always was");
    }

    @Test
    void aDropMovesTheClockOnlyAsFarAsTheSetPlannedToDrop() {
        TupClock.Step inAllowance = TupClock.tick(400, 400, true, 3.0, 16.6, 5000);
        assertEquals(0L, inAllowance.pushMs, "a planned drop is part of the set's time");
        assertEquals(400L, inAllowance.dropUsedMs);
        TupClock.Step edge = TupClock.tick(400, 400, true, 3.0, 16.6, 150);
        assertEquals(250L, edge.pushMs, "only what is left of the planned drop moves");
        assertEquals(150L, edge.dropUsedMs);
        TupClock.Step past = TupClock.tick(400, 400, true, 17.0, 16.6, 0);
        assertEquals(400L, past.pushMs,
            "a drop past the planned ones is not time under pressure, whatever it reads");
        TupClock.Step hold = TupClock.tick(400, 0, true, 16.7, 16.6, 0);
        assertEquals(0L, hold.pushMs, "at the line in the hold half: the clock moves");
        TupClock.Step low = TupClock.tick(400, 0, true, 16.5, 16.6, 5000);
        assertEquals(400L, low.pushMs, "under it: the deadline goes out");
        assertEquals(0L, low.dropUsedMs, "and no drop time is spent outside a drop");
        TupClock.Step blind = TupClock.tick(400, 0, false, 17.0, 16.6, 0);
        assertEquals(400L, blind.pushMs, "no reading is not at pressure");
        TupClock.Step turnAt = TupClock.tick(400, 100, true, 17.0, 16.6, 5000);
        assertEquals(0L, turnAt.pushMs, "a tick across the turn: 300 ms held at the line, "
            + "100 ms of planned drop");
        assertEquals(100L, turnAt.dropUsedMs, "the drop part only, never the whole tick");
        TupClock.Step turnLow = TupClock.tick(400, 100, true, 16.0, 16.6, 5000);
        assertEquals(300L, turnLow.pushMs, "the held part under the line is pushed");
        assertEquals(100L, turnLow.dropUsedMs);
    }

    @Test
    void theSplitIsThePlansSplit() {
        for (Model.Preset p : m2plan()) {
            if (p.rest) continue;             // a rest commands nothing; the plan skips it
            assertEquals(PlannedTime.holdPartMs(p),
                TupClock.holdPartMs(p.uh, p.lh, p.lo, p.up, p.cyclePart, p.durMs),
                "the clock and the plan divide a preset into hold and drop the same way");
        }
        assertFalse(TupClock.inDrop(120, 5, 3, 17, false, 119_999L));
        assertTrue(TupClock.inDrop(120, 5, 3, 17, false, 120_000L));
        assertFalse(TupClock.inDrop(120, 5, 3, 17, false, 125_000L), "the next cycle");
        assertFalse(TupClock.inDrop(255, 1, 27, 27, true, 255_500L), "a chunk never drops");
        assertEquals(1_000L, TupClock.dropMsBetween(120, 5, 3, 17, false, 119_000L, 121_000L),
            "a stretch across the turn is split where the cycle turns");
        assertEquals(5_000L, TupClock.dropMsBetween(120, 5, 3, 17, false, 0L, 130_000L),
            "one drop in a cycle and a bit");
    }

    private static List<Model.Preset> m2plan() {
        Model m = enrolledL1();
        List<Model.Preset> all = m.plan(theRest(m));
        all.addAll(m.plan(pulses(m)));
        all.addAll(m.plan(longHolds(m)));
        all.addAll(m.plan(rampThroughTheLine(m)));
        return all;
    }
}
