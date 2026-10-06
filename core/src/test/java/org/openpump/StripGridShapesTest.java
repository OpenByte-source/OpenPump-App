package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * EVERY STEP AT PRESSURE OFFERS THE FOUR FIGURES (owner report from a device, 0.10).
 *
 * The trainer's Length L1 session ("Trainer · Length L1 · 5×2min @ −5.9 inHg") builds its
 * warm-up as a ramp and its work as a RAMP of five 2:05 "Work hold" steps. The run screen's
 * − / + strip read a ramp's step and the warm-up as a length only, so for the whole session it
 * showed "Time per step" or "Warm-up length" and never the pull, the hold, the drop or the drop
 * time - a real work set at pressure nobody could change live.
 *
 * These build the routines the way the plan does (RxBuild, then Model#plan) and ask the run
 * screen's own reading of the step playing (QuickAdjust#modeAt) about every step: a step at
 * pressure - a set, a ramp's step, the warm-up, the fatigue block, the traditional hold, the
 * retention hold, a traction session's pull - shows the grid; a rest shows only its length.
 */
class StripGridShapesTest {

    private static Model model() {
        Model m = new Model();
        m.ceilKpa = 40;
        return m;
    }

    /** Asks every step of the routine's plan what the strip shows for it. */
    private static void everyStepAtPressureHasTheGrid(Model m, Model.Routine r, String what) {
        List<Model.Preset> plan = m.plan(r);
        assertFalse(plan.isEmpty(), what + ": the plan has steps");
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            int mode = QuickAdjust.modeAt(m, r, plan, i, false);
            if (p.rest) {
                assertEquals(QuickAdjust.MODE_REST, mode, what + ": step " + (i + 1) + " is a rest");
                assertFalse(QuickAdjust.showsGrid(mode), what + ": a rest has no pull to change");
                assertEquals(QuickAdjust.REST, QuickAdjust.lengthField(mode));
                continue;
            }
            assertTrue(QuickAdjust.showsGrid(mode), what + ": step " + (i + 1) + " '" + p.label
                + "' at " + p.up + " kPa offers no pull, hold, drop or drop time (mode " + mode + ")");
        }
        // A rest inserted mid-step is a rest, whatever it interrupted.
        assertEquals(QuickAdjust.MODE_REST, QuickAdjust.modeAt(m, r, plan, 0, true));
        // Nothing playing: nothing to read.
        assertEquals(QuickAdjust.MODE_NONE, QuickAdjust.modeAt(m, r, plan, -1, false));
        assertEquals(QuickAdjust.MODE_NONE, QuickAdjust.modeAt(m, r, plan, plan.size(), false));
    }

    @Test
    void theTrainersLengthL1SessionOffersTheFourFiguresInItsRampWarmUpAndItsRampOfWork() {
        Model m = model();
        // The owner's session: a warm-up that climbs, and the work as a ramp inside the set.
        m.programLength.warm = Model.Program.WARM_RAMP;
        m.programLength.work = Model.Program.WORK_RAMP_IN_SET;
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_LENGTH, Plan.L1, 5, 120, 60, 20, false, 10.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        assertFalse(Say.isTraction(r), "no rack - the expansion session, as on the owner's phone");
        List<Model.Preset> plan = m.plan(r);

        int warm = 0, warmRamp = 0, ramp = 0, rampSteps = 0;
        for (int i = 0; i < plan.size(); i++) {
            int mode = QuickAdjust.modeAt(m, r, plan, i, false);
            if (mode == QuickAdjust.MODE_WARM) {
                warm++;
                if (QuickAdjust.rampAt(m, plan, i)) warmRamp++;
                // The grid AND the warm-up's own length under it.
                assertTrue(QuickAdjust.showsGrid(mode));
                assertEquals(QuickAdjust.WARM, QuickAdjust.lengthField(mode));
                assertEquals("WARM-UP · CHANGES APPLY NOW", QuickAdjust.headOf(mode));
            } else if (mode == QuickAdjust.MODE_RAMP) {
                ramp++;
                rampSteps = RunEdit.stepOfSet(plan, i)[1];
                assertTrue(QuickAdjust.showsGrid(mode));
                assertEquals(QuickAdjust.STEP_TIME, QuickAdjust.lengthField(mode));
                assertEquals("THIS RAMP STEP · CHANGES APPLY NOW", QuickAdjust.headOf(mode));
                assertTrue(plan.get(i).up > 0, "a ramp's step is a work set at pressure");
            }
        }
        // t10 R-01: P2's six reps to 20 kPa - 12, 14 .. 20 a ramp of steps, and a last rep at
        // the work it reached a rep early, a step of its own.
        assertTrue(warm == 6 && warmRamp == 5, "the warm-up is a ramp of steps (" + warm + ", "
            + warmRamp + ")");
        /* 0.10 (the owner's ramp decisions): the block climbs from 80 % of the work (16 kPa
         * under 20), at most 1.0 inHg - 3 kPa - a hold, so two climbing steps, 16 and 18; then
         * its holds at the work, the make-up among them. (Until 0.10: one ramp of six from the
         * floor, the make-up its sixth step - audit D3.) */
        assertEquals(2, ramp, "the climb is two ramp steps");
        assertEquals(2, rampSteps, "one ramp of two");
        everyStepAtPressureHasTheGrid(m, r, "Length L1, ramp warm-up and ramp work");
    }

    @Test
    void theDefaultLengthSessionAndItsHoldWarmUpOfferTheGrid() {
        Model m = model();
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_LENGTH, Plan.L1, 5, 120, 60, 20, false, 10.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        List<Model.Preset> plan = m.plan(r);
        boolean sawWarm = false;
        for (int i = 0; i < plan.size(); i++)
            if (QuickAdjust.modeAt(m, r, plan, i, false) == QuickAdjust.MODE_WARM) sawWarm = true;
        assertTrue(sawWarm, "the default warm-up (a prime hold) is the warm-up");
        everyStepAtPressureHasTheGrid(m, r, "Length L1, the default shape");
    }

    @Test
    void theGirthFatigueBlockOffersTheGrid() {
        Model m = model();
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 10, 120, 60, 27, true, 20.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        boolean fatigue = false;
        for (int i = 0; i < r.stages.size(); i++) if (r.stages.get(i).fatigueBlock) fatigue = true;
        assertTrue(fatigue, "the fixture has its fatigue block");
        everyStepAtPressureHasTheGrid(m, r, "girth L3 with the fatigue block");
    }

    @Test
    void theTraditionalHoldAndTheRetentionHoldOfferTheGrid() {
        Model m = model();
        m.trainerGirthHybrid = true;
        m.rxRetention = true;
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 10, 120, 60, 27, false, 20.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        boolean trad = false, retention = false;
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if ("Traditional hold".equals(st.name)) trad = true;
            if (st.retention) retention = true;
        }
        assertTrue(trad, "the fixture opens with the traditional hold");
        assertTrue(retention, "the fixture closes with the retention hold");
        List<Model.Preset> plan = m.plan(r);
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p.stageIdx >= 0 && r.stages.get(p.stageIdx).retention)
                assertEquals(QuickAdjust.MODE_WORK, QuickAdjust.modeAt(m, r, plan, i, false),
                    "the retention hold is a set, never read as the warm-up");
        }
        everyStepAtPressureHasTheGrid(m, r, "girth L3, traditional hold and retention");
    }

    @Test
    void theTraditionalGirthTrackOffersTheGrid() {
        Model m = model();
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_GIRTH_TRADITIONAL, Plan.L2, 3, 600, 120, 27, false,
                                 30.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        everyStepAtPressureHasTheGrid(m, r, "girth traditional");
    }

    @Test
    void aRampOfWorkOnGirthOffersTheGrid() {
        Model m = model();
        m.programGirth.warm = Model.Program.WARM_RAMP;
        m.programGirth.work = Model.Program.WORK_RAMP_IN_SET;
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 10, 120, 180, 27, false, 20.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        everyStepAtPressureHasTheGrid(m, r, "girth L2, ramp warm-up and ramp work");
    }

    @Test
    void theLengthSessionThatPullsOffersTheGrid() {
        Model m = model();
        m.trainerLengthOn = true;
        Model.Reading g = new Model.Reading();
        g.ts = 1788440800000L;
        g.method = Model.Reading.METHOD_MSEG;
        g.gir = 12.7;
        m.measLog.all.add(g);
        Model.Cylinder lt = new Model.Cylinder();
        lt.id = "L"; lt.label = "Length tube"; lt.role = Model.Cylinder.ROLE_LENGTH; lt.boreCm = 4.0; lt.lengthCm = 23.0;
        Model.Cylinder gt = new Model.Cylinder();
        gt.id = "G"; gt.label = "Girth tube"; gt.boreCm = 4.5; gt.lengthCm = 23.0;
        m.cylinders.add(lt);
        m.cylinders.add(gt);
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_LENGTH, Plan.L1, 1, 6.0 * Plan.HG, 2,
                                    m.ceilKpa, 0, null);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        assertTrue(Say.isTraction(r), "the fixture really is a session that pulls");
        everyStepAtPressureHasTheGrid(m, r, "Length L1 that pulls");
    }
}
