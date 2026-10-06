package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * THE PERSONAL SCALE THROUGH THE APP'S OWN CHAIN (0.10): TrainerTab#trackRx -> RxBuild ->
 * Model#plan, over offsets -3..+5 inHg x gentle/standard/firm x levels x months x new or not
 * x ceilings x "Most you will go to" x the plan's figure. For every build:
 *
 *   HARD   every pressure the pump is told - work, warm-up, fatigue, retention, drops and the
 *          check pull - is at most min(ceiling, most, 15 inHg); a new person's first month at
 *          most 6 inHg (and 4 lb of traction);
 *   PLAN   the prescription carries the plan's own figure unchanged under any offset, and a
 *          save writes that figure back, so the plan's progression is the same whatever the
 *          offset (a raise sequence is walked to show it);
 *   GATE   the routine's scale reads its work back to the plan's figure, so the Level 1 gate
 *          reads the plan's figure, never the scaled one;
 *   BIAS   gentle / standard / firm are the prescription -1 / 0 / +1 inHg, within the limits,
 *          firm never past the effective top;
 *   COUNT  a negative offset still counts: wherever the plan's own build counts its target in
 *          full, the lower build does too.
 */
class ScaleSweepTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final double HG = Plan.HG;
    private static final int[] OFFSETS_HG = { -3, -1, 0, 1, 2, 5 };
    private static final int[] BIASES = { Model.Program.PRESS_GENTLE,
        Model.Program.PRESS_STANDARD, Model.Program.PRESS_FIRM };
    private static final int[] MONTHS = { 0, 1, 7 };
    private static final int[] CEILINGS = { 20, 34, 57 };
    private static final double[] MOSTS = { 0.0, 8.0 * HG };

    private static Model model(long now, int month, boolean isNew, int ceil, double most) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = now - 3 * DAY;
        m.trainerMonthsPumping = month;
        m.trainerMonthsAt = now - DAY;
        m.rxNewToPumping = isNew;
        m.ceilKpa = ceil;
        // The same "Most you will go to" answered for both tracks (length has its own since
        // 0.10, Model#rxLengthMaxKpa), so every limit here binds whichever track is swept.
        m.rxWorkMaxKpa = most;
        m.rxLengthMaxKpa = most;
        return m;
    }

    private static Model.Stage stageOf(Model.Routine r, Model.Preset p) {
        return p.stageIdx >= 0 && p.stageIdx < r.stages.size() ? r.stages.get(p.stageIdx) : null;
    }

    /** The highest pressure the pump is told anywhere in `r` - every preset's pull and drop,
     *  and the check pull as it arms. */
    private static int toldPeak(Model m, Model.Routine r) {
        int pk = 0;
        for (Model.Preset p : m.plan(r)) {
            if (p.rest) continue;
            pk = Math.max(pk, Math.max(p.up, p.lo));
        }
        if (r.assess != null && r.assess.on)
            pk = Math.max(pk, Tau.commandedKpa(r, m.ceilKpa, m.workPeakKpa(r)));
        return pk;
    }

    @Test
    void everyGirthPressureStaysUnderTheHardLimitsAndThePlanIsThePlans() {
        long now = System.currentTimeMillis();
        int builds = 0, clampedByHard = 0, pastUsualTop = 0, counted = 0, gentleUnderFloor = 0;
        for (int level = Plan.L1; level <= Plan.L4; level++)
            for (int month : MONTHS)
                for (int nw = 0; nw < 2; nw++)
                    for (int ceil : CEILINGS)
                        for (double most : MOSTS)
                            for (int fig = 0; fig < 2; fig++)
                                for (int o : OFFSETS_HG)
                                    for (int bias : BIASES) {
                    boolean isNew = nw == 1;
                    Model m = model(now, month, isNew, ceil, most);
                    Model.TrainerTrackState st = m.trainerGirth;
                    st.level = level;
                    st.weekIndex = 1;
                    // EXPECTATION CHANGED (t10 REAL-14): the usual top is the person's - the
                    // first month's 6 inHg binds only somebody new to pumping.
                    double plan = fig == 0 ? Plan.floorKpa(level)
                                           : Scale.usualTopKpa(Plan.TRACK_GIRTH_INTERVAL,
                                                               level, month, isNew);
                    st.setWorkingPressure(plan, now);
                    st.offsetKpa = o * HG;
                    m.programGirth.pressure = bias;
                    int mo = TrainerTab.monthIndexNow(m, now);
                    assertEquals(month, mo);
                    String what = "L" + level + " m" + month + (isNew ? " new" : "")
                        + " ceil " + ceil + " most " + Math.round(most) + " plan "
                        + Math.round(plan) + " off " + o + " bias " + bias;

                    Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, st, mo, null);
                    Mint.Rx rx0 = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, level, 1, plan, mo,
                        ceil, 0, 0, null, new Scale.Limits(0, isNew, most));
                    Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                    assertNotNull(r, what);
                    builds++;

                    /* HARD - nothing past min(ceiling, most, 15 inHg); a new person's first
                     * month nothing past 6 inHg. */
                    int hard = Math.min(ceil, Plan.absoluteCapWholeKpa());
                    if (most > 0) hard = Math.min(hard, (int) Math.floor(most + 1e-9));
                    int told = toldPeak(m, r);
                    assertTrue(told <= hard, what + ": told " + told + " past " + hard);
                    if (isNew && month < 1)
                        assertTrue(told <= Plan.MONTH1_CAP_KPA,
                            what + ": a new person's first month told " + told);
                    if (told == hard) clampedByHard++;

                    /* PLAN - the plan's own figure, carried and written back unchanged. */
                    assertEquals(rx0.pressureKpa, rx.planKpa(), what + ": the plan's figure");
                    assertEquals(rx0.sets, rx.sets, what);
                    // EXPECTATION CHANGED (t10 REAL-15): the plan's figure is written back as
                    // the plan holds it - exact, the whole kPa it commands being that figure
                    // rounded - never rounded at every rewrite.
                    double saved = TrainerTab.savedPlanKpa(m, Plan.TRACK_GIRTH_INTERVAL, rx,
                                                           false, now);
                    assertEquals(rx.planFigureKpa(), saved, 1e-9,
                        what + ": a save writes back the plan's figure");
                    assertEquals(rx0.pressureKpa, Math.round(saved), what + ": as commanded");

                    /* GATE - the routine's scale reads its work back to the plan's figure. */
                    int pre = RxBuild.commanded(m, rx,
                        new RxBuild.Day(now, 0.0, false, -1)).pressureKpa;
                    assertEquals((double) rx.planKpa(),
                        Scale.onPlanScaleKpa(pre, r.trainerScaleKpa), 1e-9, what + ": gate");
                    if (o == 0 && bias == Model.Program.PRESS_STANDARD)
                        assertEquals(0, r.trainerScaleKpa, what + ": the plan's own scale");

                    /* BIAS - -1 / 0 / +1 inHg on the prescription, within the limits. */
                    int applied = isNew && month < 1 ? 0 : 1;
                    int top = (int) Math.round(Scale.usualTopKpa(Plan.TRACK_GIRTH_INTERVAL,
                        level, month, isNew) + applied * o * HG);
                    int hardAll = isNew && month < 1
                        ? Math.min(hard, (int) Math.floor(Plan.MONTH1_CAP_KPA)) : hard;
                    int want;
                    if (bias == Model.Program.PRESS_GENTLE)
                        want = Math.min(rx.pressureKpa,
                                        Math.max(2, (int) Math.round(rx.pressureKpa - HG)));
                    else if (bias == Model.Program.PRESS_FIRM)
                        want = Math.max(rx.pressureKpa, Math.min((int) Math.round(
                            rx.pressureKpa + HG), Math.min(top, hardAll)));
                    else want = rx.pressureKpa;
                    assertEquals(Math.min(want, hardAll), pre, what + ": the bias");
                    if (pre > Math.round(Scale.usualTopKpa(Plan.TRACK_GIRTH_INTERVAL, level,
                                                            month, isNew))) {
                        pastUsualTop++;
                        assertTrue(applied * o > 0,
                            what + ": past the usual top only through a positive offset");
                    }

                    /* COUNT - where the plan's own build counts its target, this one does. */
                    if (o < 0 && r.netTargetMin > 0.0) {
                        Model m0 = model(now, month, isNew, ceil, most);
                        m0.trainerGirth.level = level;
                        m0.trainerGirth.weekIndex = 1;
                        m0.trainerGirth.setWorkingPressure(plan, now);
                        m0.programGirth.pressure = Model.Program.PRESS_STANDARD;
                        Model.Routine r0 = m0.routine(RxBuild.routineFromRx(m0,
                            TrainerTab.trackRx(m0, Plan.TRACK_GIRTH_INTERVAL, m0.trainerGirth,
                                               mo, null)));
                        double f0 = m0.scoringFloorKpa(r0, now);
                        long plan0 = PlannedTime.underPressureSec(m0, r0,
                            f0 - m0.tupCountTolKpa(f0));
                        if (plan0 == Math.round(r0.netTargetMin * 60.0)) {
                            double f = m.scoringFloorKpa(r, now);
                            long got = PlannedTime.underPressureSec(m, r, f - m.tupCountTolKpa(f));
                            assertEquals(Math.round(r.netTargetMin * 60.0), got,
                                what + ": the lower scale counts " + got + " s of "
                                + r.netTargetMin + " min");
                            counted++;
                            if (pre < Plan.floorKpa(level)) gentleUnderFloor++;
                        }
                    }
                }
        assertTrue(builds > 3000, "the grid ran: " + builds);
        assertTrue(clampedByHard > 100, "the hard limits were reached: " + clampedByHard);
        assertTrue(pastUsualTop > 50, "offsets went past the usual top: " + pastUsualTop);
        assertTrue(counted > 100, "negative offsets were counted: " + counted);
        assertTrue(gentleUnderFloor > 50, "...under the level's own floor: " + gentleUnderFloor);
    }

    /** THE PLAN'S PROGRESSION IS THE SAME UNDER ANY OFFSET: walk the plan's raises one step at
     *  a time, saving each (the working pressure a save writes back), and the plan's figures
     *  are the same sequence whatever the offset - only what is commanded moves. */
    @Test
    void thePlansRaisesAreTheSameSequenceUnderAnyOffset() {
        long now = System.currentTimeMillis();
        for (int ceil : CEILINGS)
            for (int nw = 0; nw < 2; nw++) {
                java.util.List<Double> base = null;
                for (int o : OFFSETS_HG) {
                    Model m = model(now, 2, nw == 1, ceil, 0.0);
                    Model.TrainerTrackState st = m.trainerGirth;
                    st.level = Plan.L2;
                    st.weekIndex = 1;
                    st.setWorkingPressure(Plan.L234_FLOOR_KPA, now);
                    st.offsetKpa = o * HG;
                    java.util.List<Double> seq = new java.util.ArrayList<Double>();
                    for (int step = 0; step < 4; step++) {
                        double next = st.pressureKpa + Plan.STEP_HG_KPA;
                        Plan.Decision d = new Plan.Decision(Plan.ACTION_RAISE_PRESSURE,
                            Plan.TAG_INFERRED, "raise", "raise", next, 0);
                        Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, st, 2, d);
                        st.setWorkingPressure(TrainerTab.savedPlanKpa(m,
                            Plan.TRACK_GIRTH_INTERVAL, rx, false, now), now);
                        seq.add(st.pressureKpa);
                        assertTrue(rx.pressureKpa <= Math.min(ceil, 50));
                    }
                    if (base == null) base = seq;
                    assertEquals(base, seq, "ceil " + ceil + " offset " + o
                        + ": the plan's figures moved with the offset");
                }
            }
    }

    /** "ADJUST FIRST..." ROUND TRIP: a pressure the person sets is written back on the plan's
     *  side of their offset, so the next prescription is their pressure again. */
    @Test
    void aPressureSetInAdjustFirstIsTheNextPrescriptionUnderAnyOffset() {
        long now = System.currentTimeMillis();
        for (int o : OFFSETS_HG)
            for (int x = 18; x <= 34; x++) {
                Model m = model(now, 7, false, 57, 0.0);
                Model.TrainerTrackState st = m.trainerGirth;
                st.level = Plan.L2;
                st.weekIndex = 1;
                st.setWorkingPressure(9 * HG, now);
                st.offsetKpa = o * HG;
                Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, st, 7, null);
                int top = Scale.effectiveTopWholeKpa(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 7);
                if (x > top) continue;          // the sheet stops at the effective top
                Mint.Rx use = Mint.adjusted(rx, rx.sets, x);
                st.setWorkingPressure(TrainerTab.savedPlanKpa(m, Plan.TRACK_GIRTH_INTERVAL, use,
                                                              true, now), now);
                Mint.Rx next = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, st, 7, null);
                assertEquals(x, next.pressureKpa, "offset " + o + ": set " + x);
            }
    }

    /** A changed offset is a new signature, so the plan rewrites; the same offset is the same
     *  signature and a fresh build of it never reads as edited; a saved routine rebuilt at the
     *  offset its signature records is its own. */
    @Test
    void theOffsetIsPartOfTheIdentityAndAFreshBuildIsNeverAnEdit() {
        long now = System.currentTimeMillis();
        for (int o : OFFSETS_HG)
            for (int bias : BIASES) {
                Model m = model(now, 7, false, 57, 0.0);
                Model.TrainerTrackState st = m.trainerGirth;
                st.level = Plan.L2;
                st.weekIndex = 1;
                st.setWorkingPressure(9 * HG, now);
                st.offsetKpa = o * HG;
                m.programGirth.pressure = bias;
                Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, st, 7, null);
                String sig = Mint.signature(rx, m.mintShapeTag(Plan.TRACK_GIRTH_INTERVAL,
                                                               Plan.L2, now));
                assertEquals(Math.round(o * HG * 100) / 100.0, Scale.offsetOfSig(sig), 1e-9);
                Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx, 0, 0,
                                                                  RxBuild.Day.at(m, now)));
                assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, rx, now),
                    "offset " + o + " bias " + bias + ": a fresh build is not an edit");
                // The person moves their offset: the saved routine is still the plan's own
                // (rebuilt at the offset it records) and the signature has moved, so the plan
                // rewrites it with its notice rather than offering around an "edit".
                if ((o + 1) * HG > Scale.OFFSET_MAX_KPA) continue;   // past its reach
                st.offsetKpa = (o + 1) * HG;
                String sig2 = Mint.signature(TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL,
                    st, 7, null), m.mintShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L2, now));
                assertFalse(sig.equals(sig2), "a new offset is a new prescription");
                assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, rx, now),
                    "offset " + o + " -> " + (o + 1) + ": still the plan's own routine");
            }
    }

    /* ------------------------------------------------- "Adjust first..." (bugs c, d) */

    @Test
    void adjustFirstReadsTheEnginesMonthAndAPlusNeverLowers() {
        long now = System.currentTimeMillis();
        // Somebody who answered five months at setup, enrolled today: the engine's month is 5,
        // so Level 1's top is 8 inHg - the sheet read the months since enrolment (0) and held
        // them to the first month's 6 (bug d).
        Model m = model(now, 5, false, 57, 0.0);
        m.trainerEnrolledAt = now;
        m.trainerGirth.level = Plan.L1;
        m.trainerGirth.weekIndex = 1;
        m.trainerGirth.setWorkingPressure(6 * HG, now);
        Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 5, null);
        assertEquals((int) Math.round(8 * HG), RxBuild.adjustMaxKpa(m, rx, now));
        // With an offset the top moves with it; the hard limits do not.
        m.trainerGirth.offsetKpa = 2 * HG;
        assertEquals((int) Math.round(10 * HG), RxBuild.adjustMaxKpa(m, rx, now));
        m.rxWorkMaxKpa = 9 * HG;
        assertEquals(30, RxBuild.adjustMaxKpa(m, rx, now), "the most you will go to");
        // A "+" never lowers (bug c's kind): a figure above the most stays where it is.
        assertEquals(35, RxBuild.adjustBumpKpa(m, rx, 35, 36, now));
        assertEquals(29, RxBuild.adjustBumpKpa(m, rx, 28, 29, now));
        assertEquals(28, RxBuild.adjustBumpKpa(m, rx, 30, 28, now), "a minus goes down");
        assertEquals(1, RxBuild.adjustBumpKpa(m, rx, 1, 0, now), "never under 1 kPa");
        // A new person's first month: 6 inHg, hard, whatever the offset.
        Model n = model(now, 0, true, 57, 0.0);
        n.trainerGirth.level = Plan.L1;
        n.trainerGirth.setWorkingPressure(6 * HG, now);
        n.trainerGirth.offsetKpa = 3 * HG;
        Mint.Rx nrx = TrainerTab.trackRx(n, Plan.TRACK_GIRTH_INTERVAL, n.trainerGirth, 0, null);
        assertEquals(20, nrx.pressureKpa, "the offset waits for month two");
        assertEquals(20, RxBuild.adjustMaxKpa(n, nrx, now));
    }

    /* -------------------------------------------------------------------- traction */

    private static Model rack(long now, int month, boolean isNew, int ceil, double most) {
        Model m = model(now, month, isNew, ceil, most);
        m.trainerLengthOn = true;
        Model.Reading r = new Model.Reading();
        r.ts = 1788440800000L;
        r.method = Model.Reading.METHOD_MSEG;
        r.gir = 12.7;
        m.measLog.all.add(r);
        Model.Cylinder l = new Model.Cylinder();
        l.id = "L"; l.label = "Length tube"; l.role = Model.Cylinder.ROLE_LENGTH; l.boreCm = 4.0; l.lengthCm = 23.0;
        Model.Cylinder g = new Model.Cylinder();
        g.id = "G"; g.label = "Girth tube"; g.boreCm = 4.5; g.lengthCm = 23.0;
        m.cylinders.add(l);
        m.cylinders.add(g);
        m.activeCylinder = 0;
        m.trainerLength.strainSets = 3;
        return m;
    }

    @Test
    void oneLengthOffsetMovesThePullsAndTheExpansionTogetherInsideEveryLimit() {
        long now = System.currentTimeMillis();
        double[] loads = { 2.5, 5.0, 12.0, 15.0 };
        int builds = 0, movedTogether = 0, atFifteen = 0;
        for (int month : MONTHS)
            for (int nw = 0; nw < 2; nw++)
                for (int ceil : CEILINGS)
                    for (double most : MOSTS)
                        for (double load : loads)
                            for (int o : OFFSETS_HG) {
                boolean isNew = nw == 1;
                Model m = rack(now, month, isNew, ceil, most);
                // t10: no warm-up, so the pulls are the load's alone - a P2 warm-up's carry
                // climbs into them at +1 kPa a hold (R-02, CarryRampTest).
                m.programLength.warm = Model.Program.WARM_NONE;
                // The conversion is the length cylinder's bore (owner, 2026-09-30).
                double gir = m.lengthBoreCm();
                assertTrue(gir > 0);
                Model.TrainerTrackState st = m.trainerLength;
                st.level = Plan.L1;
                st.weekIndex = 1;
                st.loadLb = load;
                st.setWorkingPressure(6.0 * HG, now);
                st.offsetKpa = o * HG;
                String what = "m" + month + (isNew ? " new" : "") + " ceil " + ceil
                    + " most " + Math.round(most) + " load " + load + " off " + o;
                Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_LENGTH, st, month, null);
                Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                assertTrue(Say.isTraction(r), what + ": the fixture pulls");
                builds++;
                int pullHard = Math.min(ceil, Plan.absoluteCapWholeKpa());
                if (most > 0) pullHard = Math.min(pullHard, (int) Math.floor(most + 1e-9));
                double maxLb = isNew && month < 1 ? 4.0 : 15.0;
                int pull = 0, coda = 0;
                for (Model.Preset p : m.plan(r)) {
                    Model.Stage stg = stageOf(r, p);
                    if (p.rest || stg == null) continue;
                    if (stg.traction) {
                        pull = Math.max(pull, p.up);
                        assertTrue(p.up <= pullHard, what + ": pull " + p.up);
                        assertTrue(Traction.loadLbAtBore(p.up, gir) <= maxLb + 1e-9,
                            what + ": " + Traction.settingLb(Traction.loadLbAtBore(p.up, gir)));
                    } else if (stg.colour == Model.STAGE_WORK) {
                        coda = Math.max(coda, p.up);
                        int hard = isNew && month < 1
                            ? Math.min(pullHard, (int) Math.floor(Plan.MONTH1_CAP_KPA)) : pullHard;
                        assertTrue(p.up <= hard, what + ": coda " + p.up);
                    }
                }
                if (pull >= Math.min(pullHard,
                        (int) Math.floor(Traction.kpaForLbAtBore(maxLb, gir) + 1e-9))) atFifteen++;
                // Together: the pull moves by the offset exactly where nothing clamps it.
                if (o != 0 && !(isNew && month < 1)) {
                    Model m0 = rack(now, month, isNew, ceil, most);
                    m0.trainerLength.level = Plan.L1;
                    m0.trainerLength.weekIndex = 1;
                    m0.trainerLength.loadLb = load;
                    m0.trainerLength.setWorkingPressure(6.0 * HG, now);
                    int pull0 = Scale.pullKpa(m0, now);
                    double want = Traction.kpaForLbAtBore(load, gir) + o * HG;
                    if (want < Math.min(pullHard, Traction.kpaForLbAtBore(maxLb, gir)) - 1
                            && load + Scale.offsetLb(o * HG, gir) > Scale.LOAD_MIN_LB) {
                        assertEquals(Math.round(want), pull, what + ": the pull moved by "
                            + (pull - pull0) + " kPa for an offset of " + o + " inHg");
                        movedTogether++;
                    }
                }
            }
        assertTrue(builds > 500, "the grid ran: " + builds);
        assertTrue(movedTogether > 50, "pulls moved with the offset: " + movedTogether);
        assertTrue(atFifteen > 5, "the pull's hard limits were reached: " + atFifteen);
    }
}
