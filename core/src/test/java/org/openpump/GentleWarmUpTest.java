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
 * 0.10 - "I MARK OR BRUISE EASILY" WARMS UP GENTLY (the owner's own rule for this app, never the
 * guidance's): from 4.0 inHg and 60 % speed, rep by rep, to the working pressure - the first work
 * hold's - at most 1.0 inHg a rep, its speed rising to the work's own; a start at or over the work
 * starts at it; on the length track it stops at 80 % of the work (S18). The sweep over every
 * setting is TrainerBuildSweepTest's.
 */
class GentleWarmUpTest {

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

    /* --------------------------------------------------------- C: the gentle warm-up */

    private static List<Model.Preset> warmUp(Model m, Model.Routine r) {
        List<Model.Preset> out = new ArrayList<Model.Preset>();
        for (Model.Preset p : m.plan(r))
            if (p.stageIdx >= 0 && r.stages.get(p.stageIdx).colour == Model.STAGE_WARM) out.add(p);
        return out;
    }

    private static int firstWorkPull(Model m, Model.Routine r) {
        return RxBuild.firstPullKpa(m, r);
    }

    @Test
    void theGentleWarmUpClimbsRepByRepToTheWorkingPressure() {
        Model m = model();
        m.marksEasily = true;
        Model.Routine r = build(m, girth(Plan.L2, 12, 30));
        List<Model.Preset> w = warmUp(m, r);
        int work = firstWorkPull(m, r);
        assertEquals(30, work);
        assertEquals(14, w.get(0).up, "from 4.0 inHg (13.55 kPa, 14 on the wire)");
        assertEquals(work, w.get(w.size() - 1).up, "to the working pressure - the owner's rule");
        assertEquals((30 - 14 + 2) / 3 + 1, w.size(), "one rep more than ceil(16 / 3) steps");
        for (int i = 1; i < w.size(); i++)
            assertTrue(w.get(i).up - w.get(i - 1).up <= 3, "a rep over 1.0 inHg");
        assertEquals(60, w.get(0).sp, "from 60 % speed");
        assertEquals(75, w.get(w.size() - 1).sp, "to the work's own");
        for (int i = 1; i < w.size(); i++) assertTrue(w.get(i).sp >= w.get(i - 1).sp);
        for (Model.Preset p : w) {
            assertEquals(RxBuild.GENTLE_REP_HOLD_SEC, p.uh, "a 25 s hold a rep");
            assertEquals(Mint.DROP_SEC, p.lh);
            assertTrue(p.lo <= Mint.clampDropKpa(m.rxDropKpa), "the work's drop");
            assertEquals((p.uh + p.lh) * 1000L, p.durMs, "one rep a step");
        }
        assertTrue(r.stages.get(0).outOfNet(), "out of net like any warm-up");
    }

    @Test
    void aStartAtOrOverTheWorkIsOneRepAtTheWork() {
        Model m = model();
        m.marksEasily = true;
        m.gentleWarmStartKpa = 30;
        Model.Routine r = build(m, girth(Plan.L1, 5, 20));
        List<Model.Preset> w = warmUp(m, r);
        assertEquals(1, w.size());
        assertEquals(20, w.get(0).up, "never above the work");
    }

    @Test
    void theGentleWarmUpsSettingsAreThePersons() {
        Model m = model();
        m.marksEasily = true;
        m.gentleWarmStartKpa = 7;         // 2.0 inHg
        m.gentleWarmSpeedPct = 40;
        m.gentleWarmStepHg = 0.6;         // 2 kPa a rep
        Model.Routine r = build(m, girth(Plan.L2, 12, 30));
        List<Model.Preset> w = warmUp(m, r);
        assertEquals(7, w.get(0).up);
        assertEquals(40, w.get(0).sp);
        assertEquals(30, w.get(w.size() - 1).up);
        for (int i = 1; i < w.size(); i++) assertTrue(w.get(i).up - w.get(i - 1).up <= 2);
        // Never faster than the work: a start speed of 100 % starts at the work's 75.
        m.gentleWarmSpeedPct = 100;
        List<Model.Preset> fast = warmUp(m, build(m, girth(Plan.L2, 12, 30)));
        for (Model.Preset p : fast) assertEquals(75, p.sp);
    }

    @Test
    void theGentleWarmUpLeadsIntoARampAtItsFirstHold() {
        Model m = ramped();
        m.marksEasily = true;
        Model.Routine r = build(m, girth(Plan.L2, 12, 34));
        List<Model.Preset> w = warmUp(m, r);
        assertEquals(27, firstWorkPull(m, r), "the ramp's first hold");
        assertEquals(27, w.get(w.size() - 1).up, "the warm-up ends at it");
    }

    @Test
    void onTheLengthTrackItStopsWhereALengthWarmUpMay() {
        // S18 (the safety review): a length warm-up is never as deep as the work it leads into.
        Model m = model();
        m.marksEasily = true;
        Model.Routine r = build(m, new Mint.Rx(Plan.TRACK_LENGTH, Plan.L1, 5, 120, 0, 27, false,
                                               10.0));
        List<Model.Preset> w = warmUp(m, r);
        assertEquals(RunShape.warmCapKpa(27), w.get(w.size() - 1).up, "80 % of the expansion");
        for (Model.Preset p : w) assertTrue(p.up < 27);
    }

    @Test
    void noWarmUpMeansNoneWhoeverMarks() {
        // t10 R-01: the warm-up length no longer decides whether there is one - P2 builds for
        // every Program choice but "none".
        // EXPECTATION CHANGED (t10 fix, review A F2): with the length at 0 somebody who marks
        // still gets the gentle warm-up (K3) - the length is the manual run's now - not P2's.
        Model m = model();
        m.marksEasily = true;
        m.rxWarmMin = 0;
        List<Model.Preset> gentle = warmUp(m, build(m, girth(Plan.L2, 12, 30)));
        assertEquals((int) Math.round(m.gentleWarmStartKpa), gentle.get(0).up,
            "the gentle warm-up, from the person's own start");
        Model n = model();
        n.marksEasily = true;
        n.programGirth.warm = Model.Program.WARM_NONE;
        assertTrue(warmUp(n, build(n, girth(Plan.L2, 12, 30))).isEmpty());
    }

    @Test
    void withoutTheAnswerTheWarmUpIsTheUsualOne() {
        Model m = model();
        Model.Routine r = build(m, girth(Plan.L2, 12, 30));
        List<Model.Preset> w = warmUp(m, r);
        // t10 R-01: the usual warm-up is P2's - 12 kPa up 3 a rep, stopping at 27 under 30.
        assertEquals(RxBuild.p2Reps(30)[5], w.get(w.size() - 1).up);
        assertTrue(w.get(w.size() - 1).up <= firstWorkPull(m, r), "never past the work");
        assertFalse(r.stages.get(0).setIds.isEmpty());
        assertTrue(m.set(r.stages.get(0).setIds.get(0)).name.startsWith("Warm-up to"),
            "P2's reps (t10)");
    }

    /* ------------------------------------------------------------- the signature and notice */

    @Test
    void theAnswerIsInTheSignatureWhereItReachesTheBuild() {
        Model m = model();
        assertEquals("", m.gentleWarmTag(Plan.TRACK_GIRTH_INTERVAL), "no answer: no token");
        m.marksEasily = true;
        assertEquals("G1355.60.100", m.gentleWarmTag(Plan.TRACK_GIRTH_INTERVAL));
        assertEquals("", m.gentleWarmTag(Plan.TRACK_FEEDER), "the feeder has no warm-up");
        assertTrue(m.rxShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L2).contains(":G1355.60.100"));
        m.gentleWarmSpeedPct = 45;
        assertTrue(m.rxShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L2).contains(":G1355.45."),
            "a setting moved is a new signature");
        // EXPECTATION CHANGED (t10 fix, review A F2): the warm-up length is the manual run's,
        // so a length of 0 keeps the gentle warm-up and its token; the Program's "none" drops it.
        m.rxWarmMin = 0;
        assertTrue(m.gentleWarmTag(Plan.TRACK_GIRTH_INTERVAL).startsWith("G"),
            "the gentle warm-up is still built");
        m.programGirth.warm = Model.Program.WARM_NONE;
        assertEquals("", m.gentleWarmTag(Plan.TRACK_GIRTH_INTERVAL), "no warm-up: no token");
    }

    @Test
    void theNoticeSaysTheWarmUpChanged() {
        Model m = model();
        Mint.Rx rx = girth(Plan.L2, 12, 30);
        long now = System.currentTimeMillis();
        String off = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
        m.marksEasily = true;
        String on = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
        assertEquals("Your warm-up changed", Say.shapeChange(off, on));
        assertTrue(Say.shapeChangeDetail(m, off, on).contains("mark or bruise easily"));
        assertEquals("Your warm-up changed", Say.shapeChange(on, off));
        assertTrue(Say.shapeChangeDetail(m, on, off).contains("back to the usual one"));
        assertNull(Say.shapeChange(on, on), "nothing moved, nothing said");
    }

    @Test
    void aRoutineWarmedUpBeforeTheAnswerIsThePlansAndIsRewritten() {
        long now = System.currentTimeMillis();
        Model m = model();
        Mint.Rx rx = girth(Plan.L2, 12, 30);
        String before = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
        Model.Routine was = m.routine(RxBuild.routineFromRx(m, rx));
        m.marksEasily = true;               // the answer, after the routine was saved
        String now2 = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
        assertFalse(SavedMint.edited(m, was, before, 0, 0, now, null, now),
            "the ordinary warm-up is the plan's own under its old signature");
        assertTrue(SavedMint.edited(m, was, now2, 0, 0, now, null, now),
            "and is not today's build");
    }

    @Test
    void everyGentleSettingIsThePlansOwnFresh() {
        long now = System.currentTimeMillis();
        int n = 0;
        double[] starts = { Model.GENTLE_START_KPA_MIN, 13.55, 40.0 };
        int[] speeds = { 40, 60, 100 };
        double[] steps = { 0.3, 0.7, 1.0 };
        for (double start : starts) for (int sp : speeds) for (double st : steps)
            for (int ramped = 0; ramped < 2; ramped++) {
                Model m = model();
                if (ramped == 1) m.programGirth.work = Model.Program.WORK_RAMP_IN_SET;
                m.marksEasily = true;
                m.gentleWarmStartKpa = start; m.gentleWarmSpeedPct = sp; m.gentleWarmStepHg = st;
                Mint.Rx rx = girth(Plan.L2, 12, 30);
                String sig = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
                Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, null, now),
                    start + "/" + sp + "/" + st + "/" + ramped);
                n++;
            }
        assertEquals(3 * 3 * 3 * 2, n);
    }
}
