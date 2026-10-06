package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 fix round, the girth lane (review B F1-F5, the owner's O6, and the merge's open item 5):
 *
 *  - B-F1: at the pressure limit the time cap no longer freezes the pressure 1 kPa under the
 *    usual top - its limit is the step's own (whole kPa, rounded), and with nothing left to
 *    convert the regular step answers; the fallback at the limit is kept and waits its 4 weeks.
 *  - B-F2: a decision the time cap makes run-neutral is still kept (no daily repeat, no
 *    pressure step blocked behind it).
 *  - B-F3: the plan's routine is capped with the person's own fatigue block, as the plan counts.
 *  - B-F4: a reading inside the target clears the SAVED pending add.
 *  - B-F5: a hybrid's step moves its own holds only, and its holds are part of the signature.
 *  - O6: a session ended at the target ("Finish here") counts.
 *  - Open item 5: the girth engine holds while girth rests for 4 weeks of length focus.
 */
class GirthReviewFixesTest {

    static final int GI = Plan.TRACK_GIRTH_INTERVAL, T = Plan.TRACK_GIRTH_TRADITIONAL;

    /** The owner as traditional at `level`, with no "Most" above the usual top. */
    static Plan.Inputs ownerTrad(int level, int holds, double kpa) {
        Plan.Inputs in = TupCapTest.capped(T, level, holds, kpa, Double.NaN);
        in.ownTargetsMet = true;
        in.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        return in;
    }

    /* ---- B-F1 ---------------------------------------------------------------------------- */

    @Test void theCapsLimitIsTheStepsOwnTop() {
        // The usual top at Level 3 is 10 inHg = 33.86 kPa: the step's cap reads it as 34.
        Plan.Inputs in = ownerTrad(Plan.L3, 7, 33);
        assertEquals(34, Plan.r2LimitKpa(in), "rounded as the pressure step rounds it");
        // Most at -11 inHg (37.2): 37.
        in.climbTopKpa = 37.2;
        assertEquals(37, Plan.r2LimitKpa(in));
        // Never past the ceiling.
        in.climbTopKpa = Double.NaN;
        in.ceilKpa = 33.5;
        assertEquals(33, Plan.r2LimitKpa(in));
    }

    @Test void theLastKpaUnderTheTopIsReached() {
        // Review B F1: Level 3, 7 holds, one converted (33 kPa); the calendar's 7th hold is new.
        Plan.Inputs in = ownerTrad(Plan.L3, 7, 33);
        in.r2ExHolds = 1;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action, d.rule + " / " + d.reason);
        assertEquals(34, (int) Math.round(d.pressureKpa), "33 -> 34, not frozen at 33");
    }

    @Test void atTheLimitTheRegularStepAnswers() {
        // At the top (34) with a hold past the cap that has nowhere to go.
        Plan.Inputs in = ownerTrad(Plan.L3, 7, 34);
        in.r2ExHolds = 1;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action);
        assertEquals(Plan.R2_AT_LIMIT_WORDS, d.reason, "the step held: the cap's words");
        assertEquals(2, d.r2ExHolds, "kept when applied: the same holds never ask again");
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L3;
        st.r2ExHolds = 1;
        assertTrue(Mint.commitUnchanged(st, T, d, 100L));
        assertEquals(2, st.r2ExHolds);
        in.r2ExHolds = st.r2ExHolds;
        assertFalse(Plan.evaluate(in).r2(), "the next morning the regular step answers alone");

        // The ceiling's card is never hidden behind the cap's hold.
        Plan.Inputs ceil = ownerTrad(Plan.L3, 7, 30);
        ceil.ceilKpa = 30;
        ceil.r2ExHolds = 1;
        assertEquals(Plan.ACTION_CEILING_DEADLOCK, Plan.evaluate(ceil).action);
    }

    @Test void theFallbackAtTheLimitIsKeptAndWaitsFourWeeks() {
        // Traditional Level 4 at the top: 8 holds (7 run), no readings for 4 weeks.
        Plan.Inputs in = ownerTrad(Plan.L4, 8, 34);
        in.r2ExHolds = 1;
        in.weeksWithoutReadings = Plan.NO_READINGS_WEEKS;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action);
        assertTrue(d.r2(), d.rule);
        assertEquals(1, d.r2AddHolds, "the plan's count keeps the fallback's hold");
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L4; st.weekIndex = 1; st.weekGrowth = true; st.r2ExHolds = 1;
        assertEquals(8, Mint.totalSets(T, st));
        assertTrue(Mint.commitUnchanged(st, T, d, 500L));
        assertEquals(9, Mint.totalSets(T, st), "8 -> 9, the model's count");
        assertEquals(500L, st.yieldSinceMs, "the fallback's clock restarts: it waits 4 weeks");
    }

    /* ---- B-F2 ---------------------------------------------------------------------------- */

    @Test void aRunNeutralCutIsKeptNotRepeated() {
        // Traditional Level 3, standard fatigue: 7 holds in the plan (one from yield), 5 run.
        Model m = new Model();
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3; st.weekIndex = 9; st.weekGrowth = true; st.yieldSets = 1;
        st.setWorkingPressure(30, 1L);
        Plan.Inputs in = TupCapTest.capped(T, Plan.L3, 7, 30, Double.NaN);
        in.hasYieldData = true;
        in.yieldSets = 1;
        in.r2ExHolds = 2;
        in.consecutiveHighYield = Plan.YIELD_DEBOUNCE;
        Plan.Decision cut = Plan.evaluate(in);
        assertEquals(Plan.ACTION_REDUCE_VOLUME, cut.action);
        String before = Mint.signature(TrainerTab.trackRx(m, T, st, 7, null), "");
        assertEquals(before, Mint.signature(TrainerTab.trackRx(m, T, st, 7, cut), ""),
            "the routine does not change: nothing to rewrite");
        assertTrue(Mint.commitUnchanged(st, T, cut, 700L), "...but the plan keeps the cut");
        assertEquals(0, st.yieldSets);
        assertEquals(700L, st.yieldSinceMs, "the streak restarts: not the same cut tomorrow");
        // A plain hold keeps nothing.
        assertFalse(Mint.commitUnchanged(st, T, Plan.evaluate(TupCapTest.capped(T, Plan.L3, 5,
            30, Double.NaN)), 800L));
        // ...and a length decision is not girth's to keep.
        assertFalse(Mint.commitUnchanged(m.trainerLength, Plan.TRACK_LENGTH, cut, 800L));
    }

    @Test void aRunNeutralAddIsKept() {
        // Review B F2 scenario B: traditional Level 3 at 6 holds after a cut, the cap's 7th
        // already converted (the high-water mark) - a low step adds a hold that never runs.
        Model m = new Model();
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3; st.weekIndex = 1; st.weekGrowth = true; st.r2ExHolds = 2;
        st.setWorkingPressure(30, 1L);
        Plan.Inputs in = TupCapTest.capped(T, Plan.L3, 6, 30, Double.NaN);
        in.hasYieldData = true;
        in.r2ExHolds = 2;
        in.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_ADD_VOLUME, d.action, d.rule);
        assertEquals(Mint.signature(TrainerTab.trackRx(m, T, st, 7, null), ""),
            Mint.signature(TrainerTab.trackRx(m, T, st, 7, d), ""), "5 run either way");
        assertTrue(Mint.commitUnchanged(st, T, d, 900L));
        assertEquals(7, Mint.totalSets(T, st), "the plan's count keeps it");
        assertTrue(st.addPending, "an add the yield asked for, waiting to be judged");
        assertEquals(900L, st.yieldSinceMs);
        // The offer is the person's to answer, never kept on its own.
        assertFalse(Mint.commitUnchanged(st, T, Plan.offerBreak(false, false), 950L));
    }

    /* ---- B-F3 ---------------------------------------------------------------------------- */

    @Test void thePlansRoutineIsCappedWithThePersonsFatigueBlock() {
        Model m = new Model();
        m.programGirth.fatigue = Model.Program.FAT_EXTENDED;
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3; st.carriedSets = 14;
        st.setWorkingPressure(30, 1L);
        // 36 min - 11.25 min of block = 24.75 min: 12 two-minute holds, as the plan counts.
        assertEquals(12, TrainerTab.trackRx(m, GI, st, 7, null).sets);
        assertEquals(12, TrainerTab.trackRxAt(m, GI, st, 0, 30, 7, null).sets);
        // With the block off, traditional's 7 holds all fit (36 min): none dropped.
        Model off = new Model();
        off.programGirth.fatigue = Model.Program.FAT_OFF;
        Model.TrainerTrackState t = off.trainerGirth;
        t.level = Plan.L3; t.weekIndex = 9; t.weekGrowth = true;
        t.setWorkingPressure(30, 1L);
        assertEquals(7, TrainerTab.trackRx(off, T, t, 7, null).sets);
    }

    /* ---- B-F4 ---------------------------------------------------------------------------- */

    @Test void aReadingInTheTargetClearsTheSavedPendingAdd() {
        Model m = PressureClockTest.model();
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3; st.carriedSets = 10; st.yieldSets = 2;
        st.addPending = true;
        st.yieldSinceMs = PressureClockTest.at(0, 6);
        YieldWindowTest.readings(m, 0, 7.0, 1);           // 7 %: inside the target
        Plan.Inputs in = YieldWindowTest.girth(Plan.L3, 12);
        TrainerTab.fillGirthInputs(m, GI, st, 7, PressureClockTest.at(1, 8), in);
        assertFalse(in.addPending);
        assertFalse(st.addPending, "kept cleared, not only for this evaluation");
        // A later restart of the streak (a raise applied) does not bring it back.
        st.yieldSinceMs = PressureClockTest.at(2, 6);
        YieldWindowTest.readings(m, 3, 4.0, 3);
        TrainerTab.fillGirthInputs(m, GI, st, 7, PressureClockTest.at(10, 8), in);
        assertFalse(in.addPending);
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_ADD_VOLUME, d.action, "three lows add, no offer");
    }

    /* ---- B-F5 ---------------------------------------------------------------------------- */

    @Test void aHybridStepLeavesTheIntervalCountAlone() {
        Plan.Inputs in = HybridYieldTest.hybrid(Plan.L3, 1);
        in.consecutiveHighYield = Plan.YIELD_DEBOUNCE;
        Plan.Decision cut = Plan.evaluate(in);
        assertEquals(Plan.ACTION_REDUCE_VOLUME, cut.action);
        assertTrue(cut.hybrid);
        assertEquals(14, Mint.prescribe(GI, Plan.L3, 0, 30, 7, 43, 14, cut).sets,
            "the hidden interval count does not move");
    }

    @Test void theHybridsHoldsArePartOfTheSignature() {
        Model m = new Model();
        m.trainerGirthHybrid = true;
        m.trainerGirth.level = Plan.L3;
        // Standard block: 6 or 7 kept holds both run 5 - the same routine, the same signature.
        String a = m.rxShapeTag(GI, Plan.L3);
        m.trainerGirth.hybridYield = 1;
        assertEquals(a, m.rxShapeTag(GI, Plan.L3), "no rewrite for a change that does not run");
        // Block off: 6 -> 7 runs, so the signature moves and the routine is rewritten.
        m.programGirth.fatigue = Model.Program.FAT_OFF;
        String seven = m.rxShapeTag(GI, Plan.L3);
        m.trainerGirth.hybridYield = 0;
        assertNotEquals(seven, m.rxShapeTag(GI, Plan.L3));
        // ...and a routine saved at 7 is rebuilt at 7 (never read as an edit).
        Mint.Rx rx = Mint.prescribe(GI, Plan.L3, 0, 30, 7, 43, 14, null);
        String sig = Mint.signature(rx, seven);
        Model scratch = new Model();
        scratch.trainerGirth.level = Plan.L3;
        assertTrue(SavedMint.applyShapeTag(scratch, sig, GI, Plan.L3));
        assertEquals(7, RxBuild.hybridRx(scratch, rx, false).sets);
    }

    /* ---- O6 ------------------------------------------------------------------------------ */

    @Test void aSessionEndedAtTheTargetCounts() {
        Model m = PressureClockTest.model();
        Model.Sess fin = ReadingsThatDontCountTest.reading(m, PressureClockTest.at(0, 9), 8.0);
        fin.completed = false;                            // "Finish here"
        fin.targetHitAtSet = 9;
        fin.setsPlanned = 12;
        assertFalse(TrainerTab.yieldLeftOut(m, fin));
        Model.Sess stop = ReadingsThatDontCountTest.reading(m, PressureClockTest.at(2, 9), 4.0);
        stop.completed = false;                           // stopped early, target not reached
        assertTrue(TrainerTab.yieldLeftOut(m, stop));
        TrainerTab.YieldStreaks ys = TrainerTab.yieldStreaks(m, GI, Plan.L3);
        assertTrue(ys.inWindow, "the target reading is in the window");
        assertEquals(0, ys.consecutiveLow);
    }

    /* ---- Open item 5 ----------------------------------------------------------------------- */

    @Test void girthHoldsWhileItRestsForLengthFocus() {
        Model m = PressureClockTest.model();
        m.trainerLengthOn = true;
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3; st.carriedSets = 14;
        st.setWorkingPressure(30, 1L);
        long now = PressureClockTest.at(10, 8);
        st.restUntilMs = now + 4L * 7L * 86400000L;
        Plan.Inputs in = PressureClockTest.ready(Plan.L3, 7, 30);
        TrainerTab.fillGirthInputs(m, GI, st, 7, now, in);
        assertTrue(in.girthResting);
        in.netTupMin = 30.0;
        in.trainingWeeksAtPressure = 3;
        in.monthIndex = 13;                               // the month-12 gate is met too
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action);
        assertEquals(Plan.GIRTH_REST_HOLD_RULE, d.rule);
        // The plan-wide deload still applies.
        in.inDeloadWeek = true;
        assertEquals(Plan.ACTION_DELOAD, Plan.evaluate(in).action);
        // When the rest ends the engine answers again.
        TrainerTab.fillGirthInputs(m, GI, st, 7, st.restUntilMs + 1L, in);
        assertFalse(in.girthResting);
    }

    @Test void theRestDoesNotRunGirthsClocks() {
        // Length sessions alone, through the rest: no girth week counts toward the pressure
        // step or the no-readings fallback, so girth comes back where it stopped.
        Model m = PressureClockTest.model();
        Model.Routine l = new Model.Routine();
        l.id = "l"; l.trainerTrack = Plan.TRACK_LENGTH;
        m.routines.add(l);
        for (int w = 0; w < 4; w++)
            for (int d = 0; d < 3; d++)
                PressureClockTest.file(m, "l", PressureClockTest.at(7 * w + 2 * d, 9), 20, 20, 30);
        long now = PressureClockTest.at(28, 8);
        assertEquals(0, TrainerTab.weeksAtPressure(m, GI, PressureClockTest.at(0, 0), now));
        assertEquals(0, TrainerTab.weeksWithoutReadings(m, GI, 0L, 0L, now));
    }
}
