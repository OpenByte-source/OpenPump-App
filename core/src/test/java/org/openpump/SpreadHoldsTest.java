package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 0.10 - THE HOLDS A BOTH-TRACKS DAY GIVES TO LENGTH (the owner: "spread across the session"):
 * they come out evenly across the girth session - every block keeping its shape, the session a
 * hold at its working pressure, a ramp never cut part-way - and only the counted minutes they
 * take shorten the target or earn the credit. The year-long sweep is YearStallsSweepTest's.
 */
class SpreadHoldsTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    private static Model model() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerMonthsPumping = 4;
        m.ceilKpa = 40;
        return m;
    }

    private static Model ramped() {
        Model m = model();
        m.programGirth.work = Model.Program.WORK_RAMP_IN_SET;
        m.programLength.work = Model.Program.WORK_RAMP_IN_SET;
        // t10: no warm-up, so the ramp is seen alone. A P2 warm-up that stops short of the work
        // carries on through the climbing holds, the lower of the two (R-02) - CarryRampTest's.
        m.programGirth.warm = Model.Program.WARM_NONE;
        m.programLength.warm = Model.Program.WARM_NONE;
        return m;
    }

    private static Mint.Rx girth(int level, int sets, int kpa) {
        return new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, level, sets, 120, 180, kpa, false,
                           sets * 2.0);
    }

    private static Model.Routine build(Model m, Mint.Rx rx) {
        return m.routine(RxBuild.routineFromRx(m, rx));
    }

    /** The pulls of a stage's holds, in order - one entry a hold (a fixed set's preset repeats
     *  its cycle for its whole length). */
    private static List<Integer> pulls(Model m, Model.Routine r, Model.Stage st) {
        List<Integer> out = new ArrayList<Integer>();
        for (Model.Preset p : m.plan(r)) {
            if (p.stageIdx < 0 || r.stages.get(p.stageIdx) != st || p.rest || p.cyclePart)
                continue;
            int n = Manual.cycles(p.uh, p.lh, (int) (p.durMs / 1000));
            for (int i = 0; i < Math.max(1, n); i++) out.add(p.up);
        }
        return out;
    }

    /** The work stages (counted or not), in order: not a rest, a warm-up or a fatigue block. */
    private static List<Model.Stage> blocks(Model.Routine r) {
        List<Model.Stage> out = new ArrayList<Model.Stage>();
        for (Model.Stage st : r.stages)
            if (!st.rest && st.colour != Model.STAGE_WARM && !st.fatigueBlock && !st.retention
                    && !st.climb) out.add(st);
        return out;
    }

    private static long counted(Model m, Model.Routine r) {
        double floor = m.scoringFloorKpa(r, System.currentTimeMillis());
        return PlannedTime.underPressureSec(m, r, floor - m.tupCountTolKpa(floor));
    }

    /* ---------------------------------------------------- B: the both-tracks day's holds */

    @Test
    void theHoldsGivenToLengthComeOutSpreadAndEveryBlockKeepsItsShape() {
        Model m = ramped();
        m.trainerGirth.level = Plan.L2;
        Model.Routine r = build(m, girth(Plan.L2, 12, 34));
        r.trainerTrack = Plan.TRACK_GIRTH_INTERVAL;
        int before = RunShape.workSets(m, r);
        int savedPeak = m.workPeakKpa(r);
        RunShape.Built b = RunShape.build(m, r, RunShape.Choice.fromCode("g5"));
        assertEquals(5, b.setsTaken);
        assertEquals(before - 5, RunShape.workSets(m, b.routine));
        List<Model.Stage> was = blocks(r), now = blocks(b.routine);
        assertEquals(was.size(), now.size(), "no block goes");
        int shorter = 0;
        for (int i = 0; i < now.size(); i++) {
            List<Integer> p = pulls(m, b.routine, now.get(i));
            List<Integer> q = pulls(m, r, was.get(i));
            assertEquals(q.get(q.size() - 1), p.get(p.size() - 1), "block " + i
                + " still ends at its work");
            assertEquals(q.get(0), p.get(0), "block " + i + " still climbs from where it did");
            if (p.size() < q.size()) shorter++;
        }
        assertTrue(shorter >= 2, "the holds came out of more than one block (" + shorter + ")");
        assertEquals(savedPeak, m.workPeakKpa(b.routine), "the working pressure is still run");
        for (Model.Preset p : m.plan(b.routine)) assertTrue(p.up <= savedPeak, "raised");
        assertEquals(r.netTargetMin - b.minutesTaken, b.routine.netTargetMin, 1e-9);
    }

    @Test
    void aRampedSessionGivingMostOfItsHoldsStillKeepsAHoldAtItsWork() {
        Model m = ramped();
        Model.Routine r = build(m, girth(Plan.L1, 5, 20));
        r.trainerTrack = Plan.TRACK_GIRTH_INTERVAL;
        RunShape.Built b = RunShape.build(m, r, RunShape.Choice.fromCode("g5"));
        assertEquals(20, m.workPeakKpa(b.routine), "the level gate's pressure is still run");
        assertTrue(RunShape.workSets(m, b.routine) >= 1);
    }

    @Test
    void theMinutesTakenAreOnlyTheCountedOnes() {
        // At 27 kPa the first block climbs through holds under Level 2's line: taking one of
        // them takes nothing from the target, and is recorded apart ("u", 0.10).
        boolean tookUnder = false;
        for (int shortSteps : new int[]{ 3, 2 }) {
            for (String ask : new String[]{ "g5", "g9" }) {
                Model m = ramped();
                m.rampShortSteps = shortSteps;
                Model.Routine r = build(m, girth(Plan.L2, 12, 27));
                r.trainerTrack = Plan.TRACK_GIRTH_INTERVAL;
                RunShape.Built b = RunShape.build(m, r, RunShape.Choice.fromCode(ask));
                assertEquals(Math.round(b.routine.netTargetMin * 60.0), counted(m, b.routine),
                    "the shortened run counts exactly its target");
                assertTrue(b.minutesTaken <= b.setsTaken * 2.0 + 1e-9);
                // Every hold taken is the plan's (0.10): the counted ones, and the climb holds
                // under the line, recorded apart ("u") because they were never in the target.
                // What the run keeps under the line stays its own to credit.
                assertEquals(b.setsTaken * 2.0, b.minutesTaken + b.climbMinutesTaken, 1e-9);
                assertEquals(r.climbUnderLineMin - b.climbMinutesTaken,
                    b.routine.climbUnderLineMin, 1e-9);
                assertEquals(r.netTargetMin + r.climbUnderLineMin,
                    b.routine.netTargetMin + b.routine.climbUnderLineMin + b.minutesTaken
                        + b.climbMinutesTaken, 1e-9, "nothing of the plan's minutes is lost");
                assertEquals(b.climbMinutesTaken, RunShape.climbMinutesTaken(b.code()), 0.05);
                assertEquals(b.minutesTaken, RunShape.minutesTaken(b.code()), 0.05);
                if (b.climbMinutesTaken > 0.0) tookUnder = true;
            }
        }
        assertTrue(tookUnder, "a single-hold climb under the line was taken somewhere");
    }
}
