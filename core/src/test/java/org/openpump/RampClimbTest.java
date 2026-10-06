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
 * 0.10 - THE OWNER'S RAMP DECISIONS, pinned one by one (the sweep over every setting is
 * TrainerBuildSweepTest's): a Ramped block climbs from a share of the day's commanded working
 * pressure (80 %), a step of at most the person's (1.0 inHg, held to whole kPa) each hold, to the
 * working pressure; the first block climbs fully, a block after a rest a short way (2 steps); a
 * lighter day keeps its ramp; the climb counts in full (the make-up as before, but a climb
 * hold under the counting line is not made up: the level credits it), or - the person's
 * choice - only holds at the working pressure count.
 */
class RampClimbTest {

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

    /* ------------------------------------------------------------------ A: the arithmetic */

    @Test
    void aStepIsTheLargestWholeKpaNotOverTheSettingInInches() {
        assertEquals(3, Ramp.stepKpa(1.0), "1.0 inHg is 3.39 kPa: 3");
        assertEquals(1, Ramp.stepKpa(0.3), "0.3 inHg is 1.02 kPa: 1");
        assertEquals(1, Ramp.stepKpa(0.5));
        assertEquals(2, Ramp.stepKpa(0.6));
        assertEquals(3, Ramp.stepKpa(0.9));
        for (int t = 3; t <= 10; t++) {
            double hg = t / 10.0;
            assertTrue(Ramp.stepKpa(hg) <= hg * Model.Fmt.KPA_PER_INHG + 1e-9,
                hg + " inHg: a step over the setting");
        }
    }

    @Test
    void theFirstClimbStartsAtItsShareAndNeverStepsMoreThanTheStep() {
        int n = 0;
        for (int work = 2; work <= 57; work++)
            for (int pct = Model.RAMP_START_PCT_MIN; pct <= Model.RAMP_START_PCT_MAX; pct += 5)
                for (int t = 3; t <= 10; t++) {
                    int k = Ramp.stepKpa(t / 10.0);
                    int start = Ramp.startKpa(work, pct);
                    int[] c = Ramp.firstClimb(start, work, k);
                    String what = work + " kPa, " + pct + " %, " + t / 10.0 + " inHg";
                    if (start >= work) { assertEquals(0, c.length, what); continue; }
                    assertTrue(c.length > 0, what + ": nothing to climb");
                    assertEquals(start, c[0], what + ": the climb starts at its share");
                    assertEquals((work - start + k - 1) / k, c.length,
                        what + ": as few holds as the step allows");
                    assertTrue(Ramp.largestStep(c, work) <= k, what + ": a step over " + k);
                    for (int i = 0; i < c.length; i++) {
                        assertTrue(c[i] < work, what + ": a climbing hold at the work");
                        if (i > 0) assertTrue(c[i] > c[i - 1], what + ": a flat step");
                    }
                    // A ramp's own points: played as ramps inside the table, exactly.
                    for (int[] seg : Ramp.segments(c)) {
                        assertTrue(seg[1] - seg[0] + 1 <= Proto.SLOTS, what + ": past the table");
                        assertTrue(Ramp.reproduces(c, seg[0], seg[1]), what + ": a ladder rounds");
                    }
                    if (c.length <= Proto.SLOTS)
                        assertEquals(1, Ramp.segments(c).size(), what + ": one ramp");
                    n++;
                }
        assertTrue(n > 3000, "the grid is smaller than it claims (" + n + ")");
    }

    @Test
    void theOwnersExampleReadsAsTheOwnerWroteIt() {
        // −10.0 inHg (34 kPa), the defaults: 80 %, 2 steps after a rest, 1.0 inHg a hold.
        String was = Model.Fmt.unit;
        try {
            Model.Fmt.unit = "inHg";
            int[] first = Ramp.firstClimb(Ramp.startKpa(34, 80), 34, Ramp.stepKpa(1.0));
            assertArrayEquals(new int[]{ 27, 30, 32 }, first);
            assertArrayEquals(new int[]{ 32 }, Ramp.shortClimb(first, 2));
            assertEquals("First block: −8.0 → −8.9 → −9.4 → −10.0 inHg, then −10.0 inHg"
                + " · after a rest: −9.4 → −10.0 inHg", Ramp.previewLine(34, 80, 2, 1.0));
            assertEquals("First block: −8.0 → −8.9 → −9.4 → −10.0 inHg, then −10.0 inHg"
                + " · after a rest: starts at −10.0 inHg", Ramp.previewLine(34, 80, 0, 1.0));
        } finally {
            Model.Fmt.unit = was;
        }
    }

    @Test
    void aShortClimbIsTheTopOfTheFirstAndNeverLonger() {
        int[] first = { 20, 23, 26, 29 };
        assertEquals(0, Ramp.shortClimb(first, 0).length, "0 starts at the work");
        assertEquals(0, Ramp.shortClimb(first, 1).length, "1 is the arrival alone: the same");
        assertArrayEquals(new int[]{ 29 }, Ramp.shortClimb(first, 2));
        assertArrayEquals(new int[]{ 26, 29 }, Ramp.shortClimb(first, 3));
        assertArrayEquals(new int[]{ 32 }, Ramp.shortClimb(new int[]{ 32 }, 3),
            "never longer than the first block's climb");
        assertEquals(0, Ramp.shortClimb(new int[0], 3).length);
    }

    /* ------------------------------------------------------------------ A: the builds */

    @Test
    void theFirstBlockClimbsFullyAndEachBlockAfterARestAShortWay() {
        // Girth L2, 12 sets at 34 kPa (−10.0 inHg): blocks of 5 | 5 | 2.
        Model m = ramped();
        Model.Routine r = build(m, girth(Plan.L2, 12, 34));
        List<Model.Stage> b = blocks(r);
        assertEquals(3, b.size());
        assertEquals(java.util.Arrays.asList(27, 30, 32, 34, 34), pulls(m, r, b.get(0)),
            "the first block climbs fully: 27, 30, 32, then the work");
        assertEquals(java.util.Arrays.asList(32, 34, 34, 34, 34), pulls(m, r, b.get(1)),
            "after a rest: one step under the work, then the work");
        // The last block: its two holds, a short climb, and the make-up the climbs owe.
        List<Integer> last = pulls(m, r, b.get(2));
        assertEquals(32, last.get(0).intValue());
        for (int i = 1; i < last.size(); i++) assertEquals(34, last.get(i).intValue());
        assertTrue(last.size() >= 2, "every block reaches the work");
        // Counted in full, and counted exactly: every hold here is over Level 2's line.
        assertEquals(Math.round(r.netTargetMin * 60.0), counted(m, r));
        // No ramp's last step is repeated by the set after it (D3).
        for (Model.Stage st : b)
            for (int i = 0; i + 1 < st.setIds.size(); i++) {
                Model.Set s = m.set(st.setIds.get(i)), next = m.set(st.setIds.get(i + 1));
                if (s.ramp) assertTrue(next.up != s.up2, "the work repeats the climb's last step");
            }
    }

    @Test
    void theShortClimbSettingShortensOrRemovesTheClimbAfterARest() {
        int[] steps = { 0, 2, 3 };
        int[] firsts = { 34, 32, 30 };
        for (int i = 0; i < steps.length; i++) {
            Model m = ramped();
            m.rampShortSteps = steps[i];
            Model.Routine r = build(m, girth(Plan.L2, 12, 34));
            assertEquals(firsts[i], pulls(m, r, blocks(r).get(1)).get(0).intValue(),
                steps[i] + " steps: where a block after a rest starts");
        }
    }

    @Test
    void theStartAndTheStepAreThePersons() {
        Model m = ramped();
        m.rampStartPct = 60;
        m.rampStepHg = 0.3;          // 1 kPa a hold
        Model.Routine r = build(m, girth(Plan.L1, 10, 20));
        List<Integer> p = pulls(m, r, blocks(r).get(0));
        assertEquals(12, p.get(0).intValue(), "60 % of 20");
        for (int i = 1; i < p.size(); i++)
            assertTrue(p.get(i) - p.get(i - 1) <= 1, "a step over 0.3 inHg: " + p);
        assertEquals(20, p.get(p.size() - 1).intValue(), "and it reaches the work");
        // Eight holds under the work - more than the block's ten less one - lengthen the block
        // rather than end it under the work; nothing is made up past the prescription.
        assertTrue(p.size() >= 9, "the climb and at least one hold at the work: " + p);
    }

    @Test
    void aClimbLongerThanTheTableIsPlayedAsConsecutiveRampsOfWholeCycles() {
        Model m = ramped();
        m.ceilKpa = 57;
        m.rampStartPct = 60;
        m.rampStepHg = 0.3;
        Model.Routine r = build(m, girth(Plan.L4, 20, 50));
        Model.Stage first = blocks(r).get(0);
        int ramps = 0;
        for (String id : first.setIds) {
            Model.Set s = m.set(id);
            assertEquals(0, s.dur % s.cycle(), "whole cycles: " + s.name);
            if (s.ramp) {
                ramps++;
                assertTrue(s.steps <= Proto.SLOTS, "inside the table");
                assertEquals(s.steps * s.cycle(), s.dur, "one cycle a step");
            }
        }
        assertTrue(ramps >= 2, "a climb of " + (50 - 30) + " kPa at 1 kPa a hold is several ramps");
        List<Integer> p = pulls(m, r, first);
        for (int i = 1; i < p.size(); i++) assertTrue(p.get(i) - p.get(i - 1) <= 1, "" + p);
    }

    @Test
    void aLighterDayKeepsItsRampClimbingToTheLighterPressure() {
        long now = System.currentTimeMillis();
        for (int flat = 0; flat < 2; flat++) {
            Model m = ramped();
            Deload.arm(m, now - DAY);                  // the first taper day: 4 hg lighter
            m.rampLighterDays = flat == 0;
            Mint.Rx rx = girth(Plan.L2, 12, 34);
            Model.Routine r = build(m, rx);
            int work = RxBuild.commanded(m, rx, RxBuild.Day.at(m, now)).pressureKpa;
            assertTrue(work < 34, "the day is lighter");
            List<Integer> p = pulls(m, r, blocks(r).get(0));
            if (flat == 0) {
                assertEquals(Ramp.startKpa(work, 80), p.get(0).intValue(),
                    "the ramp climbs from 80 % of the lighter day's pressure");
                assertEquals(work, p.get(p.size() - 1).intValue(), "to it");
            } else {
                for (int x : p) assertEquals(work, x, "run flat on a lighter day: " + p);
            }
            assertEquals(0.0, r.netTargetMin, 1e-9, "a lighter day still asks for no net");
        }
    }

    @Test
    void theClimbCountsInFullOrOnlyTheWorkCountsAsThePersonChooses() {
        for (int bias = 0; bias < 3; bias++) {
            // Counted (the default): the climb is part of each block's holds.
            Model a = ramped();
            a.programGirth.pressure = bias;
            Mint.Rx rx = girth(Plan.L2, 12, 30);
            Model.Routine full = build(a, rx);
            assertEquals(Math.round(full.netTargetMin * 60.0), counted(a, full),
                "counted in full: the plan counts its target (bias " + bias + ")");
            for (Model.Stage st : full.stages) assertFalse(st.climb, "no climb stage");
            // Only the work counts: the climb is a stage of its own, out of net, and each
            // block's twelve prescribed holds are all at the work.
            Model b = ramped();
            b.programGirth.pressure = bias;
            b.rampCountClimb = false;
            Model.Routine only = build(b, rx);
            int work = RxBuild.commanded(b, rx, RxBuild.Day.today(b)).pressureKpa;
            int climbs = 0, atWork = 0;
            for (Model.Stage st : only.stages) {
                if (st.climb) {
                    climbs++;
                    assertTrue(st.outOfNet(), "a climb nobody counts is out of net");
                    for (int x : pulls(b, only, st)) assertTrue(x < work, "a climb at the work");
                    continue;
                }
                if (st.rest || st.colour == Model.STAGE_WARM) continue;
                for (int x : pulls(b, only, st)) {
                    assertEquals(work, x, "a counted hold under the work");
                    atWork++;
                }
            }
            assertEquals(3, climbs, "one climb in front of each block");
            if (bias != Model.Program.PRESS_GENTLE)
                assertEquals(12, atWork, "the prescription's holds, all at the work, no make-up");
            else
                assertTrue(atWork > 12, "a Gentle bias still makes its own shortfall up");
            assertEquals(atWork * 2.0, only.netTargetMin, 1e-9, "the target is the working holds");
            assertEquals(Math.round(only.netTargetMin * 60.0), counted(b, only),
                "only the work counted: the plan counts its target (bias " + bias + ")");
        }
    }

    @Test
    void aClimbHoldUnderTheCountingLineIsRunButNotInTheTarget() {
        // Girth L2 at 27 kPa: 80 % is 22 - under Level 2's line (27.09 less 2 %).
        Model m = ramped();
        Model.Routine r = build(m, girth(Plan.L2, 12, 27));
        double floor = m.scoringFloorKpa(r, System.currentTimeMillis());
        double line = floor - m.tupCountTolKpa(floor);
        int under = 0, holds = 0;
        for (Model.Stage st : blocks(r))
            for (int x : pulls(m, r, st)) { holds++; if (x < line) under++; }
        assertTrue(under > 0, "the fixture has climbing holds under the line");
        assertTrue(r.name.contains(" · " + holds + "×2min"), "named for every hold it runs");
        assertEquals((holds - under) * 2.0, r.netTargetMin, 1e-9,
            "the target is the holds net can count");
        assertEquals(Math.round(r.netTargetMin * 60.0), counted(m, r));
        // ...and it is not made up (the owner's decision): the session runs the prescription's
        // twelve holds, as fixed holds do, and the hold net cannot count is recorded beside the
        // target for the level to credit - together, the plan's twenty-four minutes.
        assertEquals(12, holds, "no hold is added for a climbing hold under the line");
        assertEquals(under * 2.0, r.climbUnderLineMin, 1e-9, "the uncounted climb minutes");
        assertEquals(24.0, r.netTargetMin + r.climbUnderLineMin, 1e-9,
            "target and credit are the plan's twelve holds");
    }

    @Test
    void aRampedSessionIsNoLongerThanFixedHoldsForTheSameDay() {
        // Level 2's bottom, 8.0 inHg (27 kPa), twelve sets in blocks of 5 | 5 | 2. Every climb
        // hold is under the line, so none is made up: the Ramped session is the fixed one's
        // twelve holds and its length. (Made up in full it ran 43:20 against 35:00.)
        Model f = model();
        f.programGirth.warm = Model.Program.WARM_NONE;   // as ramped(): no warm-up
        Model.Routine fixed = build(f, girth(Plan.L2, 12, 27));
        Model m = ramped();
        Model.Routine r = build(m, girth(Plan.L2, 12, 27));
        assertEquals(f.routineSec(fixed), m.routineSec(r), "as long as fixed holds");
        assertEquals(24.0, fixed.netTargetMin, 1e-9);
        assertEquals(fixed.netTargetMin, r.netTargetMin + r.climbUnderLineMin, 1e-9,
            "the same plan minutes, counted and credited");
        // At Level 2's top (10.0 inHg) every climb hold counts, and the little each sits under
        // the prescription is made up as it always was: one hold, about two and a half minutes.
        Model f2 = model();
        f2.programGirth.warm = Model.Program.WARM_NONE;
        Model.Routine fixed2 = build(f2, girth(Plan.L2, 12, 34));
        Model m2 = ramped();
        Model.Routine r2 = build(m2, girth(Plan.L2, 12, 34));
        assertEquals(0.0, r2.climbUnderLineMin, 1e-9, "nothing under the line at 34");
        assertTrue(m2.routineSec(r2) <= f2.routineSec(fixed2) + 150,
            m2.routineSec(r2) + " s against " + f2.routineSec(fixed2) + " s fixed");
    }

    @Test
    void aFirmClimbFromALowAnswerStillReadsAsThePlansMinutes() {
        // Level 1, ten sets, a person whose answer sits 3 kPa under the plan's figure (17 -> 14)
        // on Firm: the work runs at 17 and the routine counts from the level's own line (its
        // scale is 0), while the climb starts at 80 % - 14, under that line. The session
        // counts 18 of Level 1's 20 minutes, and read as that the gate never passed and the
        // level never moved. The climb hold is not made up (the owner's decision): its two
        // minutes are credited, so a session that delivered its target reads as the plan's 20.
        Model m = ramped();
        m.programGirth.pressure = Model.Program.PRESS_FIRM;
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 10, 120, 0, 14, false, 20.0,
                                 Mint.POWER_PCT, -3);
        Model.Routine r = build(m, rx);
        assertEquals(0, r.trainerScaleKpa, "counted from the level's own line");
        List<Integer> p = pulls(m, r, blocks(r).get(0));
        assertEquals(14, p.get(0).intValue(), "the climb starts under the line");
        assertEquals(10, p.size(), "the prescription's ten holds, nothing made up");
        assertEquals(18.0, r.netTargetMin, 1e-9, "the target is what the counting holds deliver");
        assertEquals(Math.round(r.netTargetMin * 60.0), counted(m, r));
        assertEquals(2.0, r.climbUnderLineMin, 1e-9, "the climb hold's two minutes, credited");
        // The level reads the session as filed: all of its target delivered reads as 20...
        Model.Sess s = new Model.Sess();
        s.netTupSec = Double.valueOf(r.netTargetMin * 60.0);
        s.netTargetMin = Double.valueOf(r.netTargetMin);
        s.climbUnderLineMin = r.climbUnderLineMin;
        double[] c = TrainerTab.creditedNet(m, s);
        assertEquals(20.0, c[0], 1e-9, "the gate's twenty minutes");
        assertEquals(20.0, c[1], 1e-9);
        assertTrue(c[0] + 1e-9 >= Plan.L1_GATE_NET_MIN);
        // ...and a session stopped short stays short, at the same rate.
        s.netTupSec = Double.valueOf(9.0 * 60.0);
        c = TrainerTab.creditedNet(m, s);
        assertEquals(10.0, c[0], 1e-9, "half its target reads as half the plan's");
        assertTrue(c[0] < Plan.L1_GATE_NET_MIN);
    }

    @Test
    void theTractionCodaClimbsAsARampedBlockDoes() {
        Model m = ramped();
        Model.Reading g = new Model.Reading();
        g.ts = 1788440800000L; g.method = Model.Reading.METHOD_MSEG; g.gir = 12.7;
        m.measLog.all.add(g);
        Model.Cylinder l = new Model.Cylinder();
        l.id = "L"; l.label = "Length tube"; l.role = Model.Cylinder.ROLE_LENGTH; l.boreCm = 4.0; l.lengthCm = 23.0;
        Model.Cylinder gt = new Model.Cylinder();
        gt.id = "G"; gt.label = "Girth tube"; gt.boreCm = 4.5; gt.lengthCm = 23.0;
        m.cylinders.add(l); m.cylinders.add(gt);
        m.trainerLength.loadLb = 4.0; m.trainerLength.strainSets = 3;
        for (int count = 0; count < 2; count++) {
            m.rampCountClimb = count == 0;
            Mint.Rx rx = new Mint.Rx(Plan.TRACK_LENGTH, Plan.L1, 5, 120, 0, 27, false, 10.0);
            Model.Routine r = build(m, rx);
            assertTrue(Say.isTraction(r));
            int coda = RxBuild.commandedKpa(m, rx);
            List<Integer> p = new ArrayList<Integer>();
            for (Model.Stage st : r.stages)
                if (!st.traction && !st.rest && st.colour != Model.STAGE_WARM)
                    p.addAll(pulls(m, r, st));
            assertEquals(Ramp.startKpa(coda, 80), p.get(0).intValue(), "from 80 % of the coda");
            assertEquals(coda, p.get(p.size() - 1).intValue(), "to the coda's figure");
            assertEquals(Math.round(r.netTargetMin * 60.0), counted(m, r),
                "the coda counts its target");
        }
    }

    /* ------------------------------------------------------------- the signature and notice */

    @Test
    void theSettingsAreInTheSignatureWhereTheyReachTheBuild() {
        Model m = model();
        assertEquals("", m.rampTag(Plan.TRACK_GIRTH_INTERVAL), "fixed work: no ramp token");
        m.programGirth.work = Model.Program.WORK_RAMP_IN_SET;
        assertEquals("Y80.2.100.1.1", m.rampTag(Plan.TRACK_GIRTH_INTERVAL));
        assertTrue(m.rxShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L2).contains(":Y80.2.100.1.1"));
        m.rampStartPct = 85;
        assertTrue(m.rxShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L2).contains(":Y85."),
            "a setting moved is a new signature");
    }

    @Test
    void theNoticeSaysWhatChanged() {
        Model m = ramped();
        Mint.Rx rx = girth(Plan.L2, 12, 30);
        long now = System.currentTimeMillis();
        String sig = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
        String legacy = sig.replace(":" + m.rampTag(rx.track), "");
        assertNull(SavedMint.shapeToken(legacy, 'Y'));
        assertEquals("Your ramps changed", Say.shapeChange(legacy, sig));
        assertTrue(Say.shapeChangeDetail(m, legacy, sig).startsWith(
            "Ramps work differently now: they used to climb from your level's floor"));
        m.rampStartPct = 70;
        String moved = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
        assertEquals("Your ramps changed", Say.shapeChange(sig, moved));
        assertTrue(Say.shapeChangeDetail(m, sig, moved).startsWith("Each block climbs from 70%"));
        assertNull(Say.shapeChange(moved, moved), "nothing moved, nothing said");
    }

    @Test
    void aRoutineSavedBeforeTheSettingsIsThePlansAndIsRewritten() {
        long now = System.currentTimeMillis();
        for (int track : new int[]{ Plan.TRACK_GIRTH_INTERVAL, Plan.TRACK_LENGTH }) {
            Model m = ramped();
            Mint.Rx rx = track == Plan.TRACK_LENGTH
                ? new Mint.Rx(Plan.TRACK_LENGTH, Plan.L1, 5, 120, 0, 27, false, 10.0)
                : girth(Plan.L2, 12, 30);
            String sig = Mint.signature(rx, m.mintShapeTag(track, rx.level, now));
            String legacySig = sig.replace(":" + m.rampTag(track), "");
            // What the plan built before the settings: the climb from the floor (a scratch
            // model the old way, as SavedMint rebuilds it).
            Model old = SavedMint.scratchOf(m);
            old.legacyRamp = true;
            Model.Routine was = old.routine(RxBuild.routineFromRx(old, rx));
            assertFalse(SavedMint.edited(old, was, legacySig, 0, 0, now, null, now),
                "track " + track + ": the old ramp is the plan's own under its old signature");
            assertTrue(SavedMint.edited(old, was, sig, 0, 0, now, null, now),
                "track " + track + ": and is not today's build");
            // Today's build, under today's signature, is the plan's.
            Model.Routine fresh = m.routine(RxBuild.routineFromRx(m, rx));
            assertFalse(SavedMint.edited(m, fresh, sig, 0, 0, now, null, now));
            assertNotNull(SavedMint.shapeToken(sig, 'Y'));
        }
    }

    @Test
    void everyRampSettingIsThePlansOwnFresh() {
        long now = System.currentTimeMillis();
        int n = 0;
        int[] pcts = { 60, 80, 95 };
        int[] shorts = { 0, 2, 3 };
        double[] steps = { 0.3, 1.0 };
        for (int pct : pcts) for (int sh : shorts) for (double st : steps)
            for (int lighter = 0; lighter < 2; lighter++) for (int cnt = 0; cnt < 2; cnt++) {
                Model m = ramped();
                m.rampStartPct = pct; m.rampShortSteps = sh; m.rampStepHg = st;
                m.rampLighterDays = lighter == 0; m.rampCountClimb = cnt == 0;
                Mint.Rx rx = girth(Plan.L2, 12, 30);
                String sig = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
                Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, null, now),
                    pct + "/" + sh + "/" + st + "/" + lighter + "/" + cnt);
                n++;
            }
        assertEquals(3 * 3 * 2 * 2 * 2, n);
    }
}
