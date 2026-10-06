package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE 0.10 RUN SCREEN'S PURE PARTS: the live stage bar (rests, skips, edits), the status
 * line's words, the Coming steps edit rules and the run colours' validation.
 */
class RunScreenRulesTest {

    private String unitWas;

    @BeforeEach void inHg() { unitWas = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_INHG; }
    @AfterEach void back() { Model.Fmt.unit = unitWas; }

    /* ------------------------------------------------------------- fixtures */

    private static Model.Stage stage(String name, int colour) {
        return Model.Stage.of(name, colour, new String[0]);
    }

    private static Model.Preset work(int stage, int up, int uh, int lh, int sets) {
        Model.Preset p = new Model.Preset();
        p.up = up; p.lo = 3; p.uh = uh; p.lh = lh; p.sp = 60;
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

    /** The prototype's routine: warm-up, five sets, a 3-minute rest, five sets. */
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
        Model.Preset w = work(0, 14, 30, 5, 1);
        w.durMs = 365_000L;
        p.add(w);
        p.add(work(1, 27, 120, 5, 5));
        p.add(rest(2, 180));
        p.add(work(3, 27, 120, 5, 5));
        return p;
    }

    /* ------------------------------------------------------------ the stage bar */

    @Test
    void theBarIsSizedByTimeWithARestOfItsOwnAndATickPerSet() {
        List<StageBar.Segment> b = StageBar.build(stages(), plan(), 1, 130_000L, null, null, true);
        assertEquals(4, b.size());
        assertEquals(365_000L, b.get(0).weightMs);
        assertEquals(625_000L, b.get(1).weightMs);
        assertEquals(RunLook.REST, b.get(2).kind, "a rest is its own segment, in the rest kind");
        assertEquals(RunLook.WARM, b.get(0).kind);
        assertEquals(4, b.get(1).ticks.length, "five sets, four boundaries");
        assertEquals(0.2f, b.get(1).ticks[0], 1e-6);
        assertEquals(0, b.get(2).ticks.length, "no ticks in a rest");
        assertEquals(0, b.get(0).ticks.length, "no ticks in a warm-up");
    }

    @Test
    void doneIsFullTheCurrentFillsAndWhatIsLeftIsEmpty() {
        List<StageBar.Segment> b = StageBar.build(stages(), plan(), 1, 125_000L, null, null, true);
        assertEquals(1f, b.get(0).fill, 1e-6);
        assertEquals(0.2f, b.get(1).fill, 1e-6);
        assertTrue(b.get(1).current);
        assertFalse(b.get(0).current);
        assertEquals(0f, b.get(2).fill, 1e-6);
        assertEquals(0f, b.get(3).fill, 1e-6);
    }

    @Test
    void theRestFillsAsItRuns() {
        List<StageBar.Segment> b = StageBar.build(stages(), plan(), 2, 90_000L, null, null, true);
        assertTrue(b.get(2).current);
        assertEquals(0.5f, b.get(2).fill, 1e-6);
        assertEquals(1f, b.get(1).fill, 1e-6);
    }

    @Test
    void aSkippedStageKeepsItsPlaceHatchedAndAChangedOneIsMarked() {
        List<Model.Preset> p = plan();
        List<Model.Preset> out = ComingSteps.removeStage(p, 3, 1);
        long[] skipped = new long[4];
        skipped[3] = ComingSteps.lengthOf(out);
        boolean[] changed = new boolean[4];
        changed[2] = true;
        List<StageBar.Segment> b = StageBar.build(stages(), p, 1, 0L, skipped, changed, true);
        assertEquals(4, b.size(), "the skipped stage is still drawn");
        assertTrue(b.get(3).skipped);
        assertEquals(625_000L, b.get(3).weightMs, "sized by what was taken out");
        assertEquals(0f, b.get(3).fill, 1e-6);
        assertFalse(b.get(3).current);
        assertTrue(b.get(2).changed);
        assertFalse(b.get(1).changed);
    }

    /* THE OWNER'S PICK ON THE LEFTOVERS (option A, device check EMU9c seen 4): a block skipped
     * in Coming steps is drawn hatched WHEREVER IT SITS - before, between or after the blocks
     * of its stage that still run - the same as a skipped last block. Undo draws it whole. */

    /** One stage of three blocks (a fatigue hold, then two blocks of sets), after a warm-up. */
    private static List<Model.Preset> threeBlocks() {
        List<Model.Preset> p = new ArrayList<Model.Preset>();
        Model.Preset w = work(0, 14, 30, 5, 1);
        w.durMs = 300_000L;
        p.add(w);
        for (int pos = 0; pos < 3; pos++) {
            Model.Preset b = work(1, 27, 120, 5, pos == 0 ? 1 : 5);
            b.pos = pos;
            p.add(b);
        }
        p.add(rest(2, 180));
        p.add(work(3, 27, 120, 5, 5));
        return p;
    }

    private static java.util.Map<Integer, List<Model.Preset>> skipBlock(List<Model.Preset> p,
                                                                       int stage, int pos) {
        java.util.Map<Integer, List<Model.Preset>> m =
            new java.util.HashMap<Integer, List<Model.Preset>>();
        m.put(Integer.valueOf(ComingSteps.key(stage, pos)),
              ComingSteps.removeStage(p, ComingSteps.key(stage, pos), 0));
        return m;
    }

    @Test
    void aBlockSkippedInTheMiddleOfItsStageIsHatchedInItsPlace() {
        List<Model.Preset> p = threeBlocks();
        java.util.Map<Integer, List<Model.Preset>> sk = skipBlock(p, 1, 1);
        List<StageBar.Segment> b = StageBar.build(stages(), p, 0, 0L, null, null, true, sk);
        assertEquals(6, b.size(), "warm-up, hold, the skipped sets, the sets, rest, work");
        assertFalse(b.get(1).skipped);
        assertEquals(125_000L, b.get(1).weightMs, "the fatigue hold still runs");
        assertTrue(b.get(2).skipped, "the skipped block is hatched where it sits");
        assertEquals(625_000L, b.get(2).weightMs, "sized by what was taken out");
        assertEquals(0f, b.get(2).fill, 1e-6);
        assertFalse(b.get(3).skipped);
        assertEquals(625_000L, b.get(3).weightMs);
        assertEquals(1, b.get(2).stage);
        assertEquals(1, b.get(3).stage);
        assertEquals(4, b.get(3).ticks.length, "a live piece keeps its own set ticks");
        assertEquals(0, b.get(2).ticks.length);
    }

    @Test
    void aBlockSkippedFirstInItsStageIsHatchedAndTheRunFillsTheRest() {
        List<Model.Preset> p = threeBlocks();
        java.util.Map<Integer, List<Model.Preset>> sk = skipBlock(p, 1, 0);
        // Playing the first block of sets (plan index 1 once the hold is out), 125 s in.
        List<StageBar.Segment> b = StageBar.build(stages(), p, 1, 125_000L, null, null, true, sk);
        assertEquals(5, b.size());
        assertTrue(b.get(1).skipped);
        assertEquals(125_000L, b.get(1).weightMs);
        assertFalse(b.get(1).current, "a hatched block is never the one playing");
        assertTrue(b.get(2).current);
        assertEquals(1_250_000L, b.get(2).weightMs, "the two blocks of sets still to run, as one");
        assertEquals(0.1f, b.get(2).fill, 1e-6);
        assertArrayEquals(new int[] { 2, 3 }, StageBar.nowAndNext(b));
    }

    @Test
    void undoDrawsTheStageWholeAgain() {
        List<Model.Preset> p = threeBlocks();
        List<StageBar.Segment> b = StageBar.build(stages(), p, 0, 0L, null, null, true,
            new java.util.HashMap<Integer, List<Model.Preset>>());
        assertEquals(4, b.size());
        assertEquals(1_375_000L, b.get(1).weightMs);
        assertFalse(b.get(1).skipped);
        // Every block of the stage skipped: one hatched segment, as before.
        List<Model.Preset> q = threeBlocks();
        java.util.Map<Integer, List<Model.Preset>> all =
            new java.util.HashMap<Integer, List<Model.Preset>>();
        for (int pos = 0; pos < 3; pos++)
            all.put(Integer.valueOf(ComingSteps.key(1, pos)),
                    ComingSteps.removeStage(q, ComingSteps.key(1, pos), 0));
        long[] ms = new long[4];
        for (List<Model.Preset> out : all.values()) ms[1] += ComingSteps.lengthOf(out);
        List<StageBar.Segment> c = StageBar.build(stages(), q, 0, 0L, ms, null, true, all);
        assertEquals(4, c.size());
        assertTrue(c.get(1).skipped);
        assertEquals(1_375_000L, c.get(1).weightMs);
    }

    @Test
    void theRunScreenHandsTheBarItsSkippedBlocks() throws Exception {
        String rs = NoBookNamesTest.stripComments(new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("../app/src/main/java/org/openpump/RunScreen.java")),
            java.nio.charset.StandardCharsets.UTF_8));
        // R11-2: the live bar is StageBar#buildLive now, still handed the skipped blocks.
        // EXPECTATION CHANGED (FIX11 F4): ...and, after them, what the Skip button cut out.
        assertTrue(rs.contains("a.comingChangedFlags(ns), a.comingSkipped, a.skipCutMs())"));
    }

    @Test
    void theStaticBarFillsNothing() {
        List<StageBar.Segment> b = StageBar.build(stages(), plan(), -1, 0L, null, null, false);
        for (int i = 0; i < b.size(); i++) {
            assertEquals(0f, b.get(i).fill, 1e-6);
            assertFalse(b.get(i).current);
            assertEquals(0, b.get(i).ticks.length);
        }
    }

    @Test
    void tooManySetsDrawNoTicks() {
        List<Model.Stage> s = new ArrayList<Model.Stage>();
        s.add(stage("Work", Model.STAGE_WORK));
        List<Model.Preset> p = new ArrayList<Model.Preset>();
        p.add(work(0, 20, 10, 2, 60));
        assertEquals(0, StageBar.build(s, p, 0, 0L, null, null, true).get(0).ticks.length);
    }

    /* ---------------------------------------------------------- the status line */

    private static RunLook.Now now() {
        RunLook.Now n = new RunLook.Now();
        n.armed = true;
        n.stageKind = RunLook.WORK;
        n.setK = 6; n.setN = 10;
        n.pressure = "−8.0 inHg";
        return n;
    }

    @Test
    void workSaysTheSetAndThePhaseNeverThePressureNorTheStage() {
        String s = RunLook.statusLeft(now());
        assertEquals("WORK · SET 6 OF 10 · HOLD", s);
        assertFalse(s.contains("STAGE"));
        assertFalse(s.contains("inHg"), "the pressure is shown once: the chart's readout");
    }

    @Test
    void aRampCountsItsStepsAndAWarmUpNamesItselfOnly() {
        RunLook.Now n = now();
        n.ramp = true; n.stepK = 3; n.stepN = 5;
        assertEquals("RAMP · STEP 3 OF 5", RunLook.statusLeft(n));
        n.ramp = false;
        n.stageKind = RunLook.WARM;
        assertEquals("WARM-UP", RunLook.statusLeft(n));
        n.stageKind = RunLook.FATIGUE; n.dropPhase = true;
        assertEquals("FATIGUE BLOCK · SET 6 OF 10 · DROP", RunLook.statusLeft(n));
    }

    @Test
    void aRestSaysWhenThePullComes() {
        RunLook.Now n = now();
        n.resting = true; n.pullNext = true; n.restLeftMs = 105_200L;
        assertEquals("REST · PULL IN 1:46", RunLook.statusLeft(n));
        assertFalse(RunLook.pullWarning(n));
        n.restLeftMs = 9_400L;
        assertEquals("PULL IN 0:10", RunLook.statusLeft(n));
        assertTrue(RunLook.pullWarning(n));
        n.pullNext = false;
        assertEquals("REST · 0:10 LEFT", RunLook.statusLeft(n));
        assertFalse(RunLook.pullWarning(n), "no pull, no warning");
    }

    @Test
    void aHoldIsAlwaysSaidAndOutranksEverything() {
        RunLook.Now n = now();
        n.holding = true;
        n.resting = true;
        assertEquals("PAUSED · PRESSURE KEPT · TAP RESUME", RunLook.statusLeft(n));
        assertEquals(RunLook.HOLD, RunLook.liveKind(true, true, RunLook.WORK, true));
        assertFalse(RunLook.pullWarning(n), "a held rest counts nothing down");
    }

    @Test
    void theDropOfAWorkSetIsItsOwnKind() {
        RunLook.Now n = now();
        n.dropPhase = true;
        assertEquals("WORK · SET 6 OF 10 · DROP", RunLook.statusLeft(n));
        assertEquals(RunLook.DROP, RunLook.liveKind(false, false, RunLook.WORK, true));
        assertEquals(RunLook.WARM, RunLook.liveKind(false, false, RunLook.WARM, true));
        assertEquals(RunLook.REST, RunLook.liveKind(false, true, RunLook.WORK, true));
    }

    @Test
    void aSingleSetNamesNoSetAndNothingArmedSaysStarting() {
        RunLook.Now n = now();
        n.setN = 1; n.setK = 1;
        assertEquals("WORK · HOLD", RunLook.statusLeft(n));
        n.armed = false;
        assertEquals("STARTING", RunLook.statusLeft(n));
    }

    @Test
    void setsAreCountedFromTheCycle() {
        Model.Preset p = work(1, 27, 120, 5, 10);
        assertEquals(125_000L, RunLook.cycleMs(p));
        assertEquals(10, RunLook.setsIn(p));
        assertEquals(1, RunLook.setAt(p, 0));
        assertEquals(6, RunLook.setAt(p, 5 * 125_000L + 3));
        assertEquals(10, RunLook.setAt(p, 99_000_000L), "never past the last");
        assertEquals(0, RunLook.setsIn(rest(2, 60)));
    }

    @Test
    void theNowLineSaysWhatComesNext() {
        Model.Preset next = work(3, 27, 120, 5, 5);
        String step = RunLook.nextStep(next, 6, "−8.0 inHg");
        assertEquals("set 6 · 2:00 at −8.0 inHg", step);
        assertEquals("Rest · next: set 6 · 2:00 at −8.0 inHg",
            RunLook.nowLine("Rest", 0, 0, step));
        assertEquals("Work · set 3 of 5 · next: rest 3:00",
            RunLook.nowLine("Work", 3, 5, RunLook.nextStep(rest(2, 180), 0, "")));
        assertEquals("Work · then the routine ends", RunLook.nowLine("Work", 1, 1, null));
    }

    /* ----------------------------------------------------------- Coming steps */

    @Test
    void setsNeverGoBelowTheOnePlayingPlusOne() {
        long c = 125_000L;
        assertEquals(1, ComingSteps.minSets(false, 0, c));
        assertEquals(6, ComingSteps.minSets(true, 5 * c + 10, c), "set 6 is under way");
        assertNotNull(ComingSteps.setsRefusal(10, 5, 6, c, 0));
        assertNull(ComingSteps.setsRefusal(10, 6, 6, c, 0));
        assertNotNull(ComingSteps.setsRefusal(1, 0, 1, c, 0), "a block keeps one set");
    }

    @Test
    void setsKeepTheAdvisoryAndTheTwoHourCap() {
        long c = 125_000L;
        // 9 x 2:05 = 18:45 may go; 10 x 2:05 = 20:50 passes the twenty minutes.
        assertNull(ComingSteps.setsRefusal(8, 9, 1, c, 0));
        assertNotNull(ComingSteps.setsRefusal(9, 10, 1, c, 0));
        // An over-long block can still be shortened.
        assertNull(ComingSteps.setsRefusal(12, 11, 1, c, 0));
        // The two-hour stop.
        long nearCap = ComingSteps.CAP_MS - 60_000L;
        assertNotNull(ComingSteps.setsRefusal(2, 3, 1, c, nearCap));
        assertNull(ComingSteps.setsRefusal(3, 2, 1, c, nearCap), "shortening is never capped");
    }

    @Test
    void holdsMoveInsideOneToTheWiresLongest() {
        assertEquals(255, ComingSteps.HOLD_MAX_SEC, "5 minutes, but a preset holds 255 s at most");
        assertNull(ComingSteps.holdRefusal(120, 135, 5, 5, 0));
        assertNotNull(ComingSteps.holdRefusal(60, 45, 5, 5, 0));
        assertNotNull(ComingSteps.holdRefusal(255, 270, 5, 1, 0));
        // 8 x (150 + 5) = 20:40 passes the advisory.
        assertNotNull(ComingSteps.holdRefusal(135, 150, 5, 8, 0));
        assertNull(ComingSteps.holdRefusal(150, 135, 5, 8, 0));
    }

    @Test
    void restsMoveInsideHalfAMinuteToTenMinutes() {
        assertNull(ComingSteps.restRefusal(180, 210, -1, 0));
        assertNull(ComingSteps.restRefusal(180, 150, -1, 0));
        assertNotNull(ComingSteps.restRefusal(30, 0, -1, 0));
        assertNotNull(ComingSteps.restRefusal(600, 630, -1, 0));
        // A running rest with 20 s left cannot lose 30 s of it.
        assertNotNull(ComingSteps.restRefusal(180, 150, 20_000L, 0));
        assertNull(ComingSteps.restRefusal(180, 150, 60_000L, 0));
        assertNotNull(ComingSteps.restRefusal(180, 210, -1, ComingSteps.CAP_MS));
    }

    @Test
    void onlyAStepThatHasNotStartedMayBeSkippedAndNeverTheChangeover() {
        List<Model.Stage> st = stages();
        List<Model.Preset> p = plan();
        assertNull(ComingSteps.skipRefusal(st, p, 2, 1));
        assertNull(ComingSteps.skipRefusal(st, p, 3, 1));
        assertNotNull(ComingSteps.skipRefusal(st, p, 1, 1), "the step playing");
        assertNotNull(ComingSteps.skipRefusal(st, p, 0, 1), "a step already played");
        st.get(2).awaitAck = true;
        assertNotNull(ComingSteps.skipRefusal(st, p, 2, 1), "the cylinder change");
    }

    @Test
    void aSkipTakesOnlyTheStepOutAndUndoPutsItBackInPlace() {
        List<Model.Preset> p = plan();
        Model.Preset restP = p.get(2);
        List<Model.Preset> out = ComingSteps.removeStage(p, 2, 1);
        assertEquals(1, out.size());
        assertEquals(3, p.size());
        assertEquals(3, p.get(2).stageIdx);
        int at = ComingSteps.reinsertAt(p, 2, 1);
        assertEquals(2, at);
        p.addAll(at, out);
        assertEquals(restP, p.get(2));
        // The last stage goes back at the end.
        List<Model.Preset> last = ComingSteps.removeStage(p, 3, 1);
        assertEquals(p.size(), ComingSteps.reinsertAt(p, 3, 1));
        assertEquals(1, last.size());
    }

    @Test
    void aSkipNeverTouchesAPressure() {
        List<Model.Preset> p = plan();
        int[] ups = new int[p.size()];
        for (int i = 0; i < p.size(); i++) ups[i] = p.get(i).up;
        List<Model.Preset> out = ComingSteps.removeStage(p, 2, 1);
        p.addAll(ComingSteps.reinsertAt(p, 2, 1), out);
        for (int i = 0; i < p.size(); i++) assertEquals(ups[i], p.get(i).up);
    }

    @Test
    void blocksAreSingleCyclingPresetsAndRestsSingleRests() {
        List<Model.Preset> p = plan();
        assertEquals(1, ComingSteps.blockOf(p, 1));
        assertEquals(-1, ComingSteps.blockOf(p, 2), "a rest is not a block");
        assertEquals(2, ComingSteps.restOf(p, 2));
        assertEquals(-1, ComingSteps.restOf(p, 1));
        p.add(2, work(1, 27, 120, 5, 1));
        assertEquals(-1, ComingSteps.blockOf(p, 1), "two presets: not one block");
    }

    @Test
    void theSetLimitShrinksWithSetsTakenOff() {
        List<Model.Preset> p = plan();
        TupClock.SetLimit lim = new TupClock.SetLimit();
        lim.start(p, 1);
        long before = lim.plannedMs(p, 1);
        lim.shorten(125_000L);
        assertEquals(before - 125_000L, lim.plannedMs(p, 1));
        lim.shorten(99_000_000L);
        assertEquals(0L, lim.plannedMs(p, 1));
    }

    /* ------------------------------------------- the bar's words: now and next */

    @Test
    void theBarNamesNowAndTheNextStepTheRunWillPlay() {
        List<StageBar.Segment> b = StageBar.build(stages(), plan(), 1, 0L, null, null, true);
        assertArrayEquals(new int[] { 1, 2 }, StageBar.nowAndNext(b));
        // The rest after the block is skipped: next is the work after it.
        List<Model.Preset> p = plan();
        long[] skipped = new long[4];
        skipped[2] = ComingSteps.lengthOf(ComingSteps.removeStage(p, 2, 1));
        List<StageBar.Segment> b2 = StageBar.build(stages(), p, 1, 0L, skipped, null, true);
        assertArrayEquals(new int[] { 1, 3 }, StageBar.nowAndNext(b2));
        // On the last stage there is no next: "then the end".
        List<StageBar.Segment> b3 = StageBar.build(stages(), plan(), 3, 0L, null, null, true);
        assertArrayEquals(new int[] { 3, -1 }, StageBar.nowAndNext(b3));
    }

    @Test
    void aStepKnowsWhereItIsInItsRamp() {
        List<Model.Preset> p = new ArrayList<Model.Preset>();
        for (int k = 0; k < 5; k++) {
            Model.Preset r = work(1, 10 + 2 * k, 20, 5, 1);
            r.setId = "ramp"; r.ordinal = k;
            p.add(r);
        }
        p.add(rest(2, 60));
        assertArrayEquals(new int[] { 1, 5 }, RunEdit.stepOfSet(p, 0));
        assertArrayEquals(new int[] { 3, 5 }, RunEdit.stepOfSet(p, 2));
        assertArrayEquals(new int[] { 5, 5 }, RunEdit.stepOfSet(p, 4));
        assertArrayEquals(new int[] { 1, 1 }, RunEdit.stepOfSet(p, 5));
        assertArrayEquals(new int[] { 0, 0 }, RunEdit.stepOfSet(p, 9));
    }

    /* ---------------------------------- Coming steps, the approved 1A list (0.10 final) */

    @Test
    void theSheetsWordsAreTheApprovedOnes() {
        long c = 125_000L;
        assertEquals("At least one set.", ComingSteps.setsRefusal(1, 0, 1, c, 0));
        assertEquals("15 sets is the most.", ComingSteps.setsRefusal(15, 16, 1, 10_000L, 0));
        assertEquals("The routine would pass 2 hours.",
            ComingSteps.setsRefusal(2, 3, 1, c, ComingSteps.CAP_MS - 60_000L));
        assertEquals("Holds under 1:00 can only get longer.",
            ComingSteps.holdRefusal(45, 30, 5, 5, 0));
        assertEquals("1:00 is the shortest hold.", ComingSteps.holdRefusal(60, 45, 5, 5, 0));
        assertEquals("4:15 is the longest hold the pump takes.",
            ComingSteps.holdRefusal(255, 270, 5, 1, 0));
        assertEquals("0:30 is the shortest rest.", ComingSteps.restRefusal(30, 0, -1, 0));
        assertEquals("10:00 is the longest rest.", ComingSteps.restRefusal(600, 630, -1, 0));
    }

    @Test
    void aHoldUnderAMinuteOnlyGetsLongerAndJumpsToAMinute() {
        assertEquals(60, ComingSteps.nextHold(45, 15));
        assertEquals(60, ComingSteps.nextHold(20, 15));
        assertEquals(75, ComingSteps.nextHold(60, 15));
        assertEquals(30, ComingSteps.nextHold(45, -15));
        assertNull(ComingSteps.holdRefusal(45, ComingSteps.nextHold(45, 15), 5, 5, 0));
    }

    @Test
    void theDropStaysOneInchUnderThePull() {
        assertNull(ComingSteps.dropRefusal(5, 6, 20));
        assertEquals("Already a full vent.", ComingSteps.dropRefusal(0, -1, 20));
        // Past the floor is the person's call (owner, 2026-09-30) - asked once by the run
        // screen, not refused here; 1.0 inHg under the pull is the limit.
        assertNull(ComingSteps.dropRefusal(10, 11, 20));
        assertEquals(RunEdit.dropGapSaid(), ComingSteps.dropRefusal(16, 17, 20));
        assertEquals("The drop stays below the pull.", ComingSteps.dropRefusal(8, 9, 9));
        // A drop planned above the floor may still come down.
        assertNull(ComingSteps.dropRefusal(14, 13, 30));
        Model.Preset p = work(3, 20, 60, 5, 5);
        ComingSteps.applyDrop(p, 99);
        assertEquals(RunEdit.dropTopKpa(20), p.lo, "held 1.0 inHg under the pull whatever it "
            + "was asked");
        assertEquals(20, p.up, "the pull is never touched");
        Model.Preset q = work(3, 9, 60, 5, 5);
        ComingSteps.applyDrop(q, 9);
        assertEquals(8, q.lo, "and always under the pull");
        ComingSteps.applyDrop(q, -4);
        assertEquals(0, q.lo, "a full vent at the bottom");
    }

    @Test
    void theDropTimeKeepsTheSetsAndAHoldOnlyBlockDropsUnderTheFloor() {
        Model.Preset p = work(3, 27, 120, 5, 5);
        assertNull(ComingSteps.dropTimeRefusal(5, 6, 120, 5, 0));
        assertEquals("Already 0: no drop.", ComingSteps.dropTimeRefusal(0, -1, 120, 5, 0));
        assertEquals("0:30 is the longest drop.", ComingSteps.dropTimeRefusal(30, 31, 60, 1, 0));
        ComingSteps.applyDropTime(p, 10);
        assertEquals(10, p.lh);
        assertEquals(5, RunLook.setsIn(p), "the sets are kept");
        assertEquals(5 * 130_000L, p.durMs);
        Model.Preset h = work(3, 27, 120, 0, 5);
        h.lo = 26;                                    // a hold-only block's filler
        ComingSteps.applyDropTime(h, 5);
        assertEquals(ComingSteps.DROP_MAX_KPA, h.lo, "a new drop is under the floor");
        assertEquals(27, h.up);
    }

    private static List<Model.Preset> rampPlan() {
        List<Model.Preset> p = new ArrayList<Model.Preset>();
        p.add(work(0, 27, 60, 5, 3));
        for (int k = 0; k < 5; k++) {
            Model.Preset r = work(1, 10 + 5 * k / 2, 20, 5, 1);
            r.up = 10 + (10 * k) / 4;                 // 10 .. 20 kPa
            r.lo = 4;
            r.durMs = 40_000L;
            r.setId = "ramp"; r.ordinal = k; r.label = "Ramp " + (k + 1) + "/5";
            p.add(r);
        }
        p.add(rest(2, 60));
        return p;
    }

    @Test
    void aRampIsFoundAndReadAsStepsAndATimePerStep() {
        List<Model.Preset> p = rampPlan();
        assertEquals(1, ComingSteps.rampOf(p, 1));
        assertEquals(-1, ComingSteps.rampOf(p, 0), "one preset is not a ramp");
        assertEquals(-1, ComingSteps.rampOf(p, 2), "nor is a rest");
        assertEquals(5, ComingSteps.rampSteps(p, 1));
        assertEquals(40, ComingSteps.rampStepSec(p, 1, 5));
        ComingSteps.Shape sh = ComingSteps.shapeOf(p, 1, true);
        assertEquals(ComingSteps.SHAPE_RAMP, sh.kind);
        assertEquals(5, sh.steps);
        assertEquals(40, sh.stepSec);
        assertEquals(ComingSteps.SHAPE_OTHER, ComingSteps.shapeOf(p, 1, false).kind,
            "a set that is not a ramp is not edited as one");
    }

    @Test
    void aRampReSpreadsBetweenTheSameEndsAndNeverRaisesThePull() {
        List<Model.Preset> p = rampPlan();
        int before = p.size();
        assertEquals(2, ComingSteps.respreadRamp(p, 1, 5, 7, 40, 34));
        assertEquals(before + 2, p.size());
        assertEquals(10, p.get(1).up, "the first end is kept");
        assertEquals(20, p.get(7).up, "the last end is kept");
        for (int k = 1; k <= 7; k++) {
            assertTrue(p.get(k).up >= 10 && p.get(k).up <= 20, "between the ends");
            assertTrue(p.get(k).lo < p.get(k).up);
            assertEquals(40_000L, p.get(k).durMs);
            assertEquals(1, p.get(k).stageIdx);
            assertEquals("Ramp " + k + "/7", p.get(k).label);
            if (k > 1) assertTrue(p.get(k).up >= p.get(k - 1).up, "still a climb");
        }
        assertEquals(7, ComingSteps.rampSteps(p, 1));
        assertTrue(p.get(8).rest, "the rest after it is where it was");
        // Under a lower ceiling no step passes it.
        List<Model.Preset> q = rampPlan();
        ComingSteps.respreadRamp(q, 1, 5, 3, 40, 15);
        for (int k = 1; k <= 3; k++) assertTrue(q.get(k).up <= 15);
    }

    @Test
    void aRampsStepsAndTimesKeepTheirRangesAndTheCaps() {
        assertEquals("A ramp needs at least 2 steps.", ComingSteps.rampStepsRefusal(2, 1, 40, 0));
        assertEquals("9 steps is the most the pump’s table holds.",
            ComingSteps.rampStepsRefusal(9, 10, 40, 0));
        assertNull(ComingSteps.rampStepsRefusal(5, 6, 40, 0));
        assertEquals("0:15 is the shortest step.", ComingSteps.rampStepTimeRefusal(15, 10, 5, 0));
        // A step is the app's clock (0.10): past 4:15 is allowed, up to the step's share of the
        // hour - 12:00 for five steps - and the twenty-minute set advisory still holds.
        assertNull(ComingSteps.rampStepTimeRefusal(255, 260, 3, 0), "past 4:15, a step is not the wire's hold");
        assertEquals("12:00 is the longest step — a ramp's hour, shared by its 5 steps.",
            ComingSteps.rampStepTimeRefusal(720, 725, 5, 0));
        assertNotNull(ComingSteps.rampStepTimeRefusal(200, 205, 9, 0), "9 x 3:25 passes 20:00");
        assertNotNull(ComingSteps.rampStepsRefusal(5, 6, 40, ComingSteps.CAP_MS), "two hours");
        List<Model.Preset> p = rampPlan();
        // In WHOLE CYCLES of each step's own 20 s hold + 5 s drop (0.10): 0:45 asked is the
        // nearest whole number of them, two - 0:50 a step, ending after a drop.
        ComingSteps.setRampStepTime(p, 1, 5, 45);
        for (int k = 1; k <= 5; k++) assertEquals(50_000L, p.get(k).durMs);
    }

    @Test
    void aCardCountsItsChangesAndUndoPutsABlockBack() {
        List<Model.Preset> p = plan();
        ComingSteps.Shape was = ComingSteps.shapeOf(p, 3, false);
        assertEquals(ComingSteps.SHAPE_BLOCK, was.kind);
        assertEquals(5, was.sets);
        Model.Preset b = p.get(3);
        b.uh = 135;                                   // as comingHold writes it: sets kept
        b.durMs = 5L * (135 + 5) * 1000L;
        ComingSteps.applyDrop(b, 2);
        ComingSteps.applyDropTime(b, 8);
        ComingSteps.Shape now = ComingSteps.shapeOf(p, 3, false);
        assertEquals(3, ComingSteps.changes(was, now), "hold, drop and drop time");
        ComingSteps.restoreBlock(b, was);
        assertEquals(0, ComingSteps.changes(was, ComingSteps.shapeOf(p, 3, false)));
        assertEquals(was.ms, ComingSteps.shapeOf(p, 3, false).ms, "the same length again");
        assertEquals(27, b.up, "the pull is never touched");
        ComingSteps.Shape r = ComingSteps.shapeOf(p, 2, false);
        assertEquals(ComingSteps.SHAPE_REST, r.kind);
        assertEquals(180, r.restSec);
    }

    /* ------------------------------------------- the review of 8d98a69, folded in */

    @Test
    void aPauseInTheDropSaysPausedNotDrop() {
        RunLook.Now n = now();
        n.dropPhase = true;
        n.holding = true;
        assertEquals("PAUSED · PRESSURE KEPT · TAP RESUME", RunLook.statusLeft(n));
        n.ramp = true; n.stepK = 2; n.stepN = 4;
        assertEquals("PAUSED · PRESSURE KEPT · TAP RESUME", RunLook.statusLeft(n));
    }

    @Test
    void theLastStageSkippedMakesTheOneBeforeItTheEnd() {
        List<Model.Preset> p = plan();
        long[] skipped = new long[4];
        skipped[3] = ComingSteps.lengthOf(ComingSteps.removeStage(p, 3, 2));
        List<StageBar.Segment> b = StageBar.build(stages(), p, 2, 10_000L, skipped, null, true);
        assertArrayEquals(new int[] { 2, -1 }, StageBar.nowAndNext(b), "then the end");
    }

    @Test
    void aTwoStepRampReSpreadsBetweenItsEnds() {
        List<Model.Preset> p = rampPlan();
        ComingSteps.respreadRamp(p, 1, 5, 2, 40, 34);
        assertEquals(2, ComingSteps.rampSteps(p, 1));
        assertEquals(10, p.get(1).up);
        assertEquals(20, p.get(2).up);
        // And back up to five: the same ends, the steps between them again.
        ComingSteps.respreadRamp(p, 1, 2, 5, 40, 34);
        assertEquals(10, p.get(1).up);
        assertEquals(20, p.get(5).up);
        for (int k = 2; k <= 5; k++) assertTrue(p.get(k).up >= p.get(k - 1).up);
    }

    @Test
    void aDescendingRampReSpreadsDownAndNeverAboveItsFirstStep() {
        List<Model.Preset> p = rampPlan();
        for (int k = 0; k < 5; k++) p.get(1 + k).up = 24 - 3 * k;   // 24 .. 12 kPa
        ComingSteps.respreadRamp(p, 1, 5, 7, 40, 34);
        assertEquals(24, p.get(1).up);
        assertEquals(12, p.get(7).up);
        for (int k = 1; k <= 7; k++) {
            assertTrue(p.get(k).up <= 24, "never above the higher end");
            assertTrue(p.get(k).lo < p.get(k).up);
            if (k > 1) assertTrue(p.get(k).up <= p.get(k - 1).up, "still a descent");
        }
    }

    @Test
    void aDropTimeChangeKeepsAPlannedDropPressure() {
        Model.Preset p = work(3, 30, 60, 5, 5);
        p.lo = 14;                                   // planned above the floor
        ComingSteps.applyDropTime(p, 4);
        assertEquals(14, p.lo, "only a new drop is brought under the floor");
        assertEquals(4, p.lh);
        ComingSteps.applyDropTime(p, 0);
        ComingSteps.applyDropTime(p, 3);
        assertEquals(ComingSteps.DROP_MAX_KPA, p.lo, "a drop added again drops under the floor");
    }

    @Test
    void aHoldOnlyBlockGivenADropUnlocksItsDropControlsAndUndoLocksThemAgain() {
        List<Model.Preset> p = plan();
        Model.Preset b = p.get(3);
        b.lh = 0; b.lo = 26;                         // a hold-only block, as the plan builds it
        b.durMs = 5L * 120 * 1000L;
        RunEdit.asBuilt(b);
        assertTrue(RunEdit.dropLocked(b), "built hold-only");
        ComingSteps.Shape was = ComingSteps.shapeOf(p, 3, false);
        assertTrue(was.holdOnlyBuild);
        ComingSteps.applyDropTime(b, 5);
        assertFalse(RunEdit.dropLocked(b), "the pump drops every set: the controls are live");
        assertEquals(ComingSteps.DROP_MAX_KPA, b.lo);
        assertEquals(5, RunLook.setsIn(b), "the sets are kept");
        ComingSteps.Shape now = ComingSteps.shapeOf(p, 3, false);
        assertEquals(2, ComingSteps.changes(was, now), "the drop and its time");
        ComingSteps.restoreBlock(b, was);
        assertTrue(RunEdit.dropLocked(b), "undone: hold-only again, and locked again");
        assertEquals(0, b.lh);
        assertEquals(0, ComingSteps.changes(was, ComingSteps.shapeOf(p, 3, false)));
        assertEquals(was.ms, ComingSteps.shapeOf(p, 3, false).ms);
        assertEquals(27, b.up, "the pull never touched");
    }

    @Test
    void aBlockBuiltWithADropStaysUnlockedWhateverItsDropIsDialledTo() {
        Model.Preset b = work(3, 27, 60, 5, 5);
        RunEdit.asBuilt(b);
        ComingSteps.applyDropTime(b, 0);
        ComingSteps.applyDrop(b, 0);
        assertFalse(RunEdit.dropLocked(b), "a drop dialled to nothing can be raised again");
    }

    /* ------------------------ Coming steps' cards are blocks, not stages (device check) */

    /** "Warm + Build": a warm-up, then ONE stage "Work" holding a block of five sets and a
     *  five-step ramp. */
    private static List<Model.Preset> warmAndBuild() {
        List<Model.Preset> p = new ArrayList<Model.Preset>();
        p.add(work(0, 12, 60, 0, 1));
        Model.Preset b = work(1, 20, 40, 8, 5);
        b.pos = 0; b.setId = "gentle";
        p.add(b);
        for (int k = 0; k < 5; k++) {
            Model.Preset r = work(1, 12 + 2 * k, 20, 5, 1);
            r.pos = 1; r.setId = "ramp"; r.ordinal = k; r.durMs = 40_000L;
            r.label = "Progressive Ramp " + (k + 1) + "/5";
            p.add(r);
        }
        return p;
    }

    @Test
    void aStageOfTwoSetsIsTwoCardsAndTheLaterOneCanBeChanged() {
        List<Model.Preset> p = warmAndBuild();
        int gentle = ComingSteps.key(1, 0), ramp = ComingSteps.key(1, 1);
        assertEquals(1, gentle, "a stage's first set keeps the stage's own number");
        assertEquals(1, ComingSteps.blockOf(p, gentle), "the block of five sets");
        assertEquals(2, ComingSteps.rampOf(p, ramp), "the ramp, on its own");
        assertEquals(-1, ComingSteps.blockOf(p, 1 | (5 << 16)), "no such block");
        assertEquals(java.util.Arrays.asList(1, ramp), ComingSteps.blocksFrom(p, 1, null));
        assertTrue(ComingSteps.after(ramp, gentle));
        assertFalse(ComingSteps.after(gentle, ramp));
        // The block of five is playing: the ramp after it, in the same stage, may be skipped.
        List<Model.Stage> st = new ArrayList<Model.Stage>();
        st.add(stage("Warm-up", Model.STAGE_WARM));
        st.add(stage("Work", Model.STAGE_WORK));
        assertNull(ComingSteps.skipRefusal(st, p, ramp, 1));
        assertNotNull(ComingSteps.skipRefusal(st, p, gentle, 1), "the block playing");
        List<Model.Preset> out = ComingSteps.removeStage(p, ramp, 1);
        assertEquals(5, out.size());
        assertEquals(2, p.size(), "only the ramp went");
        assertEquals(p.size(), ComingSteps.reinsertAt(p, ramp, 1));
        assertEquals(java.util.Arrays.asList(1, ramp),
            ComingSteps.blocksFrom(p, 1, java.util.Collections.singleton(ramp)),
            "a skipped block keeps its place in the list");
        p.addAll(ComingSteps.reinsertAt(p, ramp, 1), out);
        ComingSteps.Shape sh = ComingSteps.shapeOf(p, ramp, true);
        assertEquals(ComingSteps.SHAPE_RAMP, sh.kind);
        assertEquals(5, sh.steps);
        assertEquals(200_000L, sh.ms, "the ramp's own length, not the stage's");
        assertEquals(ComingSteps.SHAPE_BLOCK, ComingSteps.shapeOf(p, gentle, false).kind);
    }

    @Test
    void theNowCardSaysWhatTheMockSays() {
        assertEquals("Hold · set 3 of 10 · next: set 4",
            RunLook.nowLineWork(false, 3, 10, false, "rest 1:00"));
        assertEquals("Drop · set 5 of 10 · next: rest 1:00",
            RunLook.nowLineWork(true, 5, 10, true, "rest 1:00"));
        assertEquals("Hold · set 10 of 10 · next: the end",
            RunLook.nowLineWork(false, 10, 10, true, null));
        assertEquals("Step 2 of 5 at −3.9 inHg · next: −4.4 inHg",
            RunLook.nowLineRamp(2, 5, "−3.9 inHg", "−4.4 inHg", "rest 1:00"));
        assertEquals("Step 5 of 5 at −5.9 inHg · next: rest 1:00",
            RunLook.nowLineRamp(5, 5, "−5.9 inHg", null, "rest 1:00"));
        assertEquals("next: ramp from −3.0 inHg", RunLook.nowLineRest(RunLook.nextRamp("−3.0 inHg")));
        assertEquals("Warm-up at −3.0 inHg · next: set 1", RunLook.nowLineWarm("−3.0 inHg", "set 1"));
    }

    @Test
    void doneIsTheLengthLessWhatIsLeftSoTimeAddedGoesToTheLengthOnly() {
        // 4:00 block, 2:45 left: 1:15 done.
        assertEquals(75_000L, RunEdit.doneOfStepMs(240_000L, 1_165_000L, 1_000_000L));
        // The end moved out 44 s (a longer hold): done is unchanged, the length grows.
        assertEquals(75_000L, RunEdit.doneOfStepMs(284_000L, 1_209_000L, 1_000_000L));
        // Never past the length ("4:45 of 4:44"), never below nothing.
        assertEquals(284_000L, RunEdit.doneOfStepMs(284_000L, 990_000L, 1_000_000L));
        assertEquals(0L, RunEdit.doneOfStepMs(60_000L, 1_200_000L, 1_000_000L));
    }

    @Test
    void aNarrowStatusLineSaysThePauseShort() {
        RunLook.Now n = now();
        n.holding = true;
        n.narrow = true;
        assertEquals("PAUSED · PRESSURE KEPT", RunLook.statusLeft(n));
    }

    /* ------------------------------------------------------ rest on the chart */

    @Test
    void theRestViewShowsTheWholeRestAndThePullAfterIt() {
        long[] w = Trace.restWindow(100_000L, 280_000L, 150_000L, 20_000L, 25_000L);
        assertEquals(80_000L, w[0]);
        assertEquals(305_000L, w[1]);
        // A rest overrun (now past its end) still keeps now in view.
        long[] late = Trace.restWindow(100_000L, 120_000L, 130_000L, 0L, 0L);
        assertEquals(130_000L, late[1]);
    }

    @Test
    void theLongHistoryKeepsOneSampleASecondAndTheRestFlag() {
        Trace.History h = new Trace.History();
        h.push(1_000L, 20.0, false, false);
        h.push(1_400L, 21.0, false, true);   // same second: newest wins, a rest marks it
        h.push(2_100L, 0.4, false, true);
        h.push(1_900L, 9.0, false, false);   // out of order: dropped
        assertEquals(2, h.count());
        assertEquals(21.0, h.kpaAt(0), 1e-9);
        assertTrue(h.restAt(0));
        assertTrue(h.restAt(1));
        for (int i = 0; i < Trace.History.CAP + 5; i++) h.push(10_000L + i * 1000L, 1.0, false, false);
        assertEquals(Trace.History.CAP, h.count());
        h.clear();
        assertEquals(0, h.count());
    }

    @Test
    void theHalfMinuteRingRemembersWhichSamplesWereARest() {
        Trace.Ring r = new Trace.Ring();
        r.push(10.0, false, 12.0, 3.0, false);
        r.push(0.5, false, 0.0, 0.0, true);
        r.push(0.4, false, 0.0);
        assertFalse(r.restAt(0));
        assertTrue(r.restAt(1));
        assertFalse(r.restAt(2), "the old push is not a rest");
    }

    @Test
    void theStatusInkIsLegibleOnEveryPresetColour() {
        for (int i = 0; i < RunLook.PRESET_NAMES.length; i++) {
            int[] c = RunLook.preset(i);
            for (int k = 0; k < c.length; k++)
                assertTrue(Look.contrastRatio(RunLook.inkOn(c[k]), c[k]) >= 4.5,
                    RunLook.PRESET_NAMES[i] + " " + RunLook.NAMES[k]);
        }
    }

    /* -------------------------------------------------------------- colours */

    @Test
    void everyPresetIsValidAndTheDefaultsAreTheOwners() {
        for (int i = 0; i < RunLook.PRESET_NAMES.length; i++)
            assertNull(RunLook.problem(RunLook.preset(i)), RunLook.PRESET_NAMES[i]);
        assertEquals(0xFF7CC4F2, RunLook.DEFAULT[RunLook.WARM]);
        assertEquals(0xFFD4F53C, RunLook.DEFAULT[RunLook.WORK]);
        assertEquals(0xFF6D8BFF, RunLook.DEFAULT[RunLook.REST]);
        assertEquals(RunLook.KINDS, RunLook.DEFAULT.length);
    }

    @Test
    void stopRedAndItsNeighboursAreRefused() {
        int[] c = RunLook.DEFAULT.clone();
        assertNotNull(RunLook.rejectWhy(c, RunLook.WORK, Look.CRITICAL));
        assertNotNull(RunLook.rejectWhy(c, RunLook.WORK, 0xFFE85A50), "a near red");
        assertEquals(Look.CRITICAL, RunLook.STOP, "STOP's red is the app's red");
    }

    @Test
    void twoStepsInOneColourAreRefused() {
        int[] c = RunLook.DEFAULT.clone();
        assertNotNull(RunLook.rejectWhy(c, RunLook.WARM, RunLook.DEFAULT[RunLook.REST]));
        assertNotNull(RunLook.rejectWhy(c, RunLook.WARM, 0xFF6E8CFE), "nearly the rest's");
        assertNull(RunLook.rejectWhy(c, RunLook.WARM, RunLook.DEFAULT[RunLook.WARM]),
            "a kind may keep its own colour");
        assertNotNull(RunLook.rejectWhy(c, RunLook.WARM, 0x807CC4F2), "not solid");
    }

    @Test
    void aBadStoredSetLoadsTheDefaults() {
        int[] red = RunLook.DEFAULT.clone();
        red[RunLook.REST] = Look.CRITICAL;
        assertArrayEquals(RunLook.DEFAULT, RunLook.sanitize(red));
        assertArrayEquals(RunLook.DEFAULT, RunLook.sanitize(new int[3]));
        assertArrayEquals(RunLook.DEFAULT, RunLook.sanitize(null));
        int[] cb = RunLook.preset(1);
        assertArrayEquals(cb, RunLook.sanitize(cb));
    }

    @Test
    void hexRoundTripsAndTintsFollowWhere() {
        assertEquals("#6D8BFF", RunLook.hex(0xFF6D8BFF));
        assertEquals(Integer.valueOf(0xFF6D8BFF), RunLook.parseHex("#6d8bff"));
        assertNull(RunLook.parseHex("#12345"));
        assertNull(RunLook.parseHex("zzzzzz"));
        assertEquals(0f, RunLook.tintAlpha(RunLook.WHERE_LINE), 0f);
        assertTrue(RunLook.tintAlpha(RunLook.WHERE_STRONG) > RunLook.tintAlpha(RunLook.WHERE_SOFT));
        assertEquals(RunLook.WHERE_LINE, RunLook.clampWhere(7));
    }

    @Test
    void stagesAreAskedWhatKindTheyAre() {
        Model.Stage f = stage("Fatigue", Model.STAGE_WORK);
        f.fatigueBlock = true;
        assertEquals(RunLook.FATIGUE, RunLook.kindOf(f));
        Model.Stage t = stage("Traction", Model.STAGE_WORK);
        t.traction = true;
        assertEquals(RunLook.TRACTION, RunLook.kindOf(t));
        Model.Stage r = stage("Retention", Model.STAGE_WORK);
        r.retention = true;
        assertEquals(RunLook.WARM, RunLook.kindOf(r));
        assertEquals(RunLook.REST, RunLook.kindOf(Model.Stage.restOf("Rest", 60)));
        assertEquals(RunLook.WORK, RunLook.kindOf(stage("Work", Model.STAGE_WORK)));
    }
}
