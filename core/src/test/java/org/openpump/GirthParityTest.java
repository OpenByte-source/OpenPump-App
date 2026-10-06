package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 ROUND 2, LANE G - THE GIRTH ITEMS THE YEAR PARITY RUN FOUND REAL (PARITY-RESULT-1 §4,
 * editor model vs the merged app), each pinned where the app now does what the editor's
 * model does:
 *
 * REAL-14 (and REAL-9, the same root): the first month's 6 inHg caps bind only somebody new to
 * pumping - an experienced person in their plan's first month has their level's own top.
 *
 * REAL-15: the plan's own figure is written back exact, never rounded to the whole kPa at
 * every rewrite - the owner's second +1 inHg step lands at 37, not 36.
 *
 * REAL-10 (and REAL-7, the same root): a setup starts traditional girth at its level's first
 * week, so the first low-yield step finds the hold it adds instead of a level already at its
 * top, and offers a break no earlier than the editor's model does.
 *
 * REAL-6: the make-up cycles a Gentle day or a ramp adds count in the session's time cap
 * (R-27): they fill the room under it, never past it.
 */
class GirthParityTest {

    private static final double HG = Plan.HG;
    private static final long T0 = 1_790_000_000_000L;
    private static final long DAY = 24L * 60L * 60L * 1000L;

    /** An enrolled plan in its first month: `isNew` as the setup's first question answered. */
    private static Model enrolled(boolean isNew, int months) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = T0;
        m.trainerMonthsAt = T0;
        m.trainerMonthsPumping = months;
        m.rxNewToPumping = isNew;
        m.ceilKpa = 43;
        return m;
    }

    /* ------------------------------------------------- REAL-14: the first month's caps */

    @Test
    void theMonthOneCapBindsOnlySomebodyNewToPumping() {
        assertEquals(6.0 * HG, Plan.pressureCapKpa(Plan.L1, 0, true), 1e-9);
        assertEquals(8.0 * HG, Plan.pressureCapKpa(Plan.L1, 0, false), 1e-9,
            "pumped before: Level 1's own cap from the first day");
        assertEquals(8.0 * HG, Plan.pressureCapKpa(Plan.L1, 1, true), 1e-9);
        assertEquals(10.0 * HG, Plan.pressureCapKpa(Plan.L3, 0, false), 1e-9);
        // The two-argument form is the beginner's reading, as it always was.
        assertEquals(Plan.pressureCapKpa(Plan.L1, 0, true), Plan.pressureCapKpa(Plan.L1, 0), 0);

        for (int t : new int[]{ Plan.TRACK_GIRTH_INTERVAL, Plan.TRACK_GIRTH_TRADITIONAL }) {
            assertEquals(6.0 * HG, Scale.usualTopKpa(t, Plan.L1, 0, true), 1e-9);
            assertEquals(8.0 * HG, Scale.usualTopKpa(t, Plan.L1, 0, false), 1e-9);
        }
        assertEquals(6.0 * HG, Scale.usualTopKpa(Plan.TRACK_LENGTH, Plan.L1, 0, true), 1e-9);
        assertEquals(10.0 * HG, Scale.usualTopKpa(Plan.TRACK_LENGTH, Plan.L1, 0, false), 1e-9,
            "length's own 10 inHg");
    }

    /** REAL-9: traditional Level 1, not new, month 0, at 20 kPa with three weeks held - the
     *  plan steps +1 inHg (the editor: 20 -> 23); a new person holds at the 6 inHg cap. */
    @Test
    void anExperiencedPersonsPressureStepsPastSixInHgInTheFirstMonth() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_TRADITIONAL;
        in.level = Plan.L1;
        in.monthIndex = 0;
        in.pressureKpa = 20;
        in.ceilKpa = 43;
        in.ownTargetsMet = true;
        in.trainingWeeksAtPressure = 3;
        in.firstDeloadPending = true;
        in.accumulatedTrainingWeeks = 3;
        in.newToPumping = false;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action, d.reason);
        assertEquals(20 + Plan.STEP_HG_KPA, d.pressureKpa, 1e-9);

        in.newToPumping = true;
        Plan.Decision held = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, held.action, "a new person's first month holds at 6 inHg");
    }

    @Test
    void theInputsAreTheirsAndSoIsThePrescription() {
        long now = T0 + 10 * DAY;
        Model old = enrolled(false, 0);
        Model fresh = enrolled(true, 0);
        Plan.Inputs in = new Plan.Inputs();
        TrainerTab.fillGirthInputs(old, Plan.TRACK_GIRTH_INTERVAL, old.trainerGirth, 0, now, in);
        assertTrue(!in.newToPumping, "not new");
        Plan.Inputs inNew = new Plan.Inputs();
        TrainerTab.fillGirthInputs(fresh, Plan.TRACK_GIRTH_INTERVAL, fresh.trainerGirth, 0, now,
                                   inNew);
        assertTrue(inNew.newToPumping, "new");
        Plan.Inputs len = new Plan.Inputs();
        LengthTrack.fill(old, old.trainerLength, 0, now, len);
        assertTrue(!len.newToPumping, "the length ladder reads the same answer");

        // The plan's figure at 23 kPa in month 0: kept for somebody who has pumped before.
        old.trainerGirth.level = Plan.L1;
        old.trainerGirth.setWorkingPressure(23, now);
        Mint.Rx rx = TrainerTab.trackRx(old, Plan.TRACK_GIRTH_INTERVAL, old.trainerGirth, 0, null);
        assertEquals(23, rx.pressureKpa, "not held to 6 inHg");
        fresh.trainerGirth.level = Plan.L1;
        fresh.trainerGirth.setWorkingPressure(23, now);
        Mint.Rx rxNew = TrainerTab.trackRx(fresh, Plan.TRACK_GIRTH_INTERVAL, fresh.trainerGirth,
                                           0, null);
        assertEquals(20, rxNew.pressureKpa, "a new person's first month: 6 inHg");
        // Firm lifts it, as it does past month 1 (the sweep's m0 Firm: 20 + 1 inHg = 23).
        assertEquals(27, Scale.effectiveTopWholeKpa(old, Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 0));
        assertEquals(20, Scale.effectiveTopWholeKpa(fresh, Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 0));
        assertEquals(34, Scale.effectiveTopWholeKpa(old, Plan.TRACK_LENGTH, Plan.L1, 0));
    }

    /* ------------------------------------------ REAL-6: the make-up and the time cap */

    /** Time under pressure as the parity run counts it: every work and fatigue hold of the
     *  routine as written, minutes (a climb, a warm-up and the rests are not). */
    private static double tupMin(Model m, Model.Routine r) {
        double sec = 0;
        int carry = 0;
        for (Model.Preset p : m.plan(r)) {
            if (p.rest) continue;
            Model.Stage st = p.stageIdx >= 0 && p.stageIdx < r.stages.size()
                ? r.stages.get(p.stageIdx) : null;
            if (st == null || st.rest) continue;
            if (p.cyclePart) { carry += p.uh; continue; }
            boolean counted = st.fatigueBlock || (st.colour != Model.STAGE_WARM && !st.climb
                                                  && !st.traction);
            int cyc = p.uh + p.lh;
            int n = carry > 0 || cyc <= 0 ? 1 : (int) Math.max(1, Math.round(p.durMs / 1000.0 / cyc));
            if (counted) sec += n * (p.uh + carry);
            carry = 0;
        }
        return sec / 60.0;
    }

    private static Model gentle(int work) {
        Model m = enrolled(false, 7);
        m.programGirth.pressure = Model.Program.PRESS_GENTLE;
        m.programGirth.work = work;
        return m;
    }

    @Test
    void aGentleDaysMakeUpNeverTakesTheSessionPastItsTimeCap() {
        int[] works = { Model.Program.WORK_FIXED, Model.Program.WORK_RAMP_IN_SET };
        for (int work : works) {
            // Traditional Level 1 at its time cap: 4 holds of 5 minutes, 20 minutes.
            Model m = gentle(work);
            Mint.Rx trad = new Mint.Rx(Plan.TRACK_GIRTH_TRADITIONAL, Plan.L1, 4, 300, 30, 20,
                                       false, 20.0);
            double t = tupMin(m, m.routine(RxBuild.routineFromRx(m, trad)));
            assertTrue(t <= Plan.r2CapMin(Plan.L1) + 1e-9, "work " + work + ": " + t + " min");
            // Interval Level 3 at its cap: 13 holds of 2 minutes and the 7.5-minute block.
            Mint.Rx l3 = new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 14, 120, 180, 30, true,
                                     28.0);
            double t3 = tupMin(m, m.routine(RxBuild.routineFromRx(m, l3)));
            assertTrue(t3 <= Plan.r2CapMin(Plan.L3) + 1e-9, "work " + work + ": " + t3 + " min");
            // Level 4, 16 holds in blocks of 5: the last block's one hold is laid out after a
            // climbing hold of its own, so the layout runs 17 before any make-up - the make-up
            // fills to the cap's 18 holds, never past (the sweep's m7 Gentle Ramped, 45.5 min).
            Mint.Rx l4 = new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, Plan.L4, 16, 120, 180, 34, true,
                                     32.0);
            double t4 = tupMin(m, m.routine(RxBuild.routineFromRx(m, l4)));
            assertTrue(t4 <= Plan.r2CapMin(Plan.L4) + 1e-9, "work " + work + ": " + t4 + " min");
        }
    }

    @Test
    void underTheCapTheMakeUpStillComes() {
        // Interval Level 1, 5 holds of 2 minutes: 10 minutes, well under 20 - a Gentle day
        // still makes up what it ran under the prescription, as it did.
        Model m = gentle(Model.Program.WORK_FIXED);
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 5, 120, 180, 20, false,
                                 10.0);
        double t = tupMin(m, m.routine(RxBuild.routineFromRx(m, rx)));
        assertTrue(t > 10.0 + 1e-9, "the make-up: " + t + " min");
        assertTrue(t <= Plan.r2CapMin(Plan.L1) + 1e-9);
        assertEquals(10, RxBuild.capHolds(m, rx), "Level 1's 20 minutes in 2-minute holds");
        assertEquals(Integer.MAX_VALUE, RxBuild.capHolds(m, new Mint.Rx(Plan.TRACK_LENGTH,
            Plan.L1, 2, 120, 180, 20, false, 4.0)), "length has no time cap");
    }

    /* ---------------------------------- REAL-10 / REAL-7: traditional's first week */

    @Test
    void twoMonthsOfPumpingStartTraditionalAtThreeHolds() {
        int t = Plan.TRACK_GIRTH_TRADITIONAL;
        TrainerTab.Derived d = TrainerTab.deriveGirth(t, 2, 23, false, 43, 0);
        assertEquals(Plan.L1, d.level);
        assertEquals(1, d.weekIndex);
        assertEquals(3, Mint.baseSets(t, d.level, d.weekIndex), "the editor's 3, not 4");
        TrainerTab.Derived l3 = TrainerTab.deriveGirth(t, 7, 30, false, 43, 37);
        assertEquals(Plan.L3, l3.level);
        assertEquals(1, l3.weekIndex);
        assertEquals(6, Mint.baseSets(t, l3.level, l3.weekIndex));
    }

    /** REAL-7: the owner as traditional (Level 3, month 7, 30 kPa, Most 37), three low
     *  readings in week 3. Level 3's calendar has not reached its top of 7, so the step adds
     *  the hold (under the time cap: as pressure) - not the break the top offers. */
    @Test
    void theOwnerAsTraditionalAddsAHoldInWeekThreeAndIsNotOfferedABreak() {
        int t = Plan.TRACK_GIRTH_TRADITIONAL;
        TrainerTab.Derived d = TrainerTab.deriveGirth(t, 7, 30, false, 43, 37);
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = d.level;
        st.weekIndex = Mint.advancedWeekIndex(t, d.level, d.weekIndex, 2);   // week 3
        int holds = Mint.totalSets(t, st);
        assertEquals(6, holds, "under Level 3's top of " + Plan.traditionalTop(Plan.L3));

        Plan.Inputs in = new Plan.Inputs();
        in.track = t;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.newToPumping = false;
        in.weekIndex = st.weekIndex;
        in.pressureKpa = 30;
        in.ceilKpa = 43;
        in.climbTopKpa = 37;
        in.workSets = holds;
        in.hasYieldData = true;
        in.consecutiveLowYield = 3;
        in.r2HoldSec = Mint.HOLD_TRADITIONAL_SEC;
        in.r2FatSec = Mint.standardFatSec(Plan.L3);
        in.trainingWeeksAtPressure = 2;
        in.accumulatedTrainingWeeks = 2;
        in.firstDeloadPending = true;
        Plan.Decision dec = Plan.evaluate(in);
        assertNotEquals(Plan.ACTION_OFFER_BREAK, dec.action, dec.rule);
        assertTrue(dec.r2() && dec.r2AddHolds == 1, "the hold is added, past the time cap as "
            + "pressure: " + dec.rule);
    }

    /* ------------------------------------------- REAL-15: the plan's figure, kept exact */

    private static Plan.Decision raiseTo(double kpa) {
        return new Plan.Decision(Plan.ACTION_RAISE_PRESSURE, Plan.TAG_SOURCE, "step", "step",
                                 kpa, 0);
    }

    @Test
    void theOwnersSecondStepLandsAt37NotAt36() {
        long now = T0 + 40 * DAY;
        Model m = enrolled(false, 7);
        m.rxWorkMaxKpa = 37;   // the owner's "Most you will go to": the step climbs past 10 inHg
        int track = Plan.TRACK_GIRTH_INTERVAL;
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3;
        st.setWorkingPressure(30, now);
        Mint.Rx first = TrainerTab.trackRx(m, track, st, 8, raiseTo(30 + Plan.STEP_HG_KPA));
        assertEquals(33, first.pressureKpa);
        double saved = TrainerTab.savedPlanKpa(m, track, first, false, now);
        assertEquals(30 + Plan.STEP_HG_KPA, saved, 1e-9, "the step's own 33.39, not 33");
        st.setWorkingPressure(saved, now);
        assertEquals(now, st.pressureSinceMs, "a new whole kPa: the step's clock restarts");
        // A rewrite that moves nothing writes the same exact figure back.
        Mint.Rx same = TrainerTab.trackRx(m, track, st, 8, null);
        assertEquals(33, same.pressureKpa);
        assertEquals(saved, TrainerTab.savedPlanKpa(m, track, same, false, now), 1e-9);
        // The second step: 36.77, which the wire carries as 37 (the editor's week 8).
        Mint.Rx second = TrainerTab.trackRx(m, track, st, 8,
                                            raiseTo(saved + Plan.STEP_HG_KPA));
        assertEquals(37, second.pressureKpa);
        // An "Adjust first..." pressure is the person's, written back as before.
        Mint.Rx own = Mint.adjusted(second, second.sets, 35);
        assertEquals(35.0, TrainerTab.savedPlanKpa(m, track, own, true, now), 1e-9);
        assertEquals(35.0, own.planFigureKpa(), 1e-9, "a copy at another pressure is whole");
        // A sets-only adjustment keeps the plan's exact figure.
        Mint.Rx sets = Mint.adjusted(second, second.sets + 1, second.pressureKpa);
        assertEquals(saved + Plan.STEP_HG_KPA, sets.planFigureKpa(), 1e-9);
    }

    @Test
    void aFigureAHardLimitHeldIsWrittenBackAsTheLimit() {
        long now = T0 + 40 * DAY;
        Model m = enrolled(false, 7);
        m.ceilKpa = 31;
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3;
        st.setWorkingPressure(30, now);
        Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, st, 8,
                                        raiseTo(30 + Plan.STEP_HG_KPA));
        assertEquals(31, rx.pressureKpa, "the ceiling holds it");
        assertEquals(31.0, TrainerTab.savedPlanKpa(m, Plan.TRACK_GIRTH_INTERVAL, rx, false, now),
                     1e-9, "the figure the pump was held to, as before");
        // ...and a figure held to the level's top keeps the top (10 inHg, 33.86).
        m.ceilKpa = 43;
        st.setWorkingPressure(33, now);
        Mint.Rx top = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, st, 8,
                                         raiseTo(33 + Plan.STEP_HG_KPA));
        assertEquals(34, top.pressureKpa);
        assertEquals(10.0 * HG, top.planFigureKpa(), 1e-9);
    }

    @Test
    void theLengthCreepGoesOnToItsSoftCapForSomebodyNotNew() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L1;
        in.monthIndex = 0;
        in.pressureKpa = 20;
        in.ceilKpa = 43;
        in.trainingWeeksAtPressure = 1;   // a month at this pressure (the creep's contract)
        in.firstDeloadPending = true;
        in.newToPumping = false;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action, d.reason);
        assertEquals(20 + Plan.STEP_HG_KPA, d.pressureKpa, 1e-9);
        in.newToPumping = true;
        assertNotEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(in).action,
            "a new person's length holds at 6 inHg in the first month");
    }
}
