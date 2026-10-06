package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE PERSON'S OWN SCALE (0.10), the pure arithmetic: the offset, the tops that move with it,
 * the hard limits that do not, the Program's gentle/standard/firm as offsets, the warned-once
 * state, the plan's scale the gates and the counting line read, and the one length offset in
 * the traction pull's pounds. Scale is the one place each rule is written; these pin them so
 * the owner can change a rule in one place and see exactly what moved.
 */
class ScaleTest {

    private static final double HG = Plan.HG;
    private static final int[] GIRTH_TRACKS = { Plan.TRACK_GIRTH_INTERVAL,
        Plan.TRACK_GIRTH_TRADITIONAL };
    private static final int[] BIASES = { Model.Program.PRESS_GENTLE,
        Model.Program.PRESS_STANDARD, Model.Program.PRESS_FIRM };

    /* ------------------------------------------------------------------------ the tops */

    @Test
    void girthKeepsItsUsualTopsAndLengthHasItsOwn() {
        assertEquals(6.0 * HG, Scale.usualTopKpa(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 0), 1e-9);
        assertEquals(8.0 * HG, Scale.usualTopKpa(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 1), 1e-9);
        assertEquals(10.0 * HG, Scale.usualTopKpa(Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 3), 1e-9);
        assertEquals(10.0 * HG, Scale.usualTopKpa(Plan.TRACK_GIRTH_TRADITIONAL, Plan.L4, 20),
                     1e-9);
        // Length: 6 for the first month, then its own 10 - at Level 1 too, not girth's 8.
        assertEquals(6.0 * HG, Scale.usualTopKpa(Plan.TRACK_LENGTH, Plan.L1, 0), 1e-9);
        assertEquals(10.0 * HG, Scale.usualTopKpa(Plan.TRACK_LENGTH, Plan.L1, 1), 1e-9);
        assertEquals(10.0 * HG, Scale.usualTopKpa(Plan.TRACK_LENGTH, Plan.L3, 7), 1e-9);
    }

    @Test
    void theTopMovesWithTheOffsetOnEveryTrackButNotInANewPersonsFirstMonth() {
        int[] tracks = { Plan.TRACK_GIRTH_INTERVAL, Plan.TRACK_GIRTH_TRADITIONAL,
                         Plan.TRACK_LENGTH };
        for (int t : tracks)
            for (int level = Plan.L1; level <= Plan.L4; level++)
                for (int month = 0; month <= 7; month++)
                    for (int o = -3; o <= 5; o++) {
                        double off = o * HG;
                        // EXPECTATION CHANGED (t10 REAL-14): the first month's 6 inHg is a
                        // new person's top only; somebody not new has their level's own.
                        double usual = Scale.usualTopKpa(t, level, month);
                        assertEquals(Scale.usualTopKpa(t, level, month, false) + off,
                            Scale.effectiveTopKpa(t, level, month, off, false), 1e-9);
                        double forNew = Scale.effectiveTopKpa(t, level, month, off, true);
                        assertEquals(month < 1 ? usual : usual + off, forNew, 1e-9,
                            "a new person's first month keeps the usual top");
                    }
    }

    /* ----------------------------------------------------------------- the hard limits */

    @Test
    void theHardLimitsAreTheLowestOfCeilingMostAndFifteenAndNeverRoundUp() {
        assertEquals(Plan.absoluteCapWholeKpa(), Scale.pullHardKpa(57, 0));
        assertEquals(50, Scale.pullHardKpa(57, 0), "15 inHg is 50.8 kPa, floored");
        assertEquals(40, Scale.pullHardKpa(40, 0));
        assertEquals(33, Scale.pullHardKpa(57, 10.0 * HG), "a stated 10.0 inHg is 33.9 - 33");
        assertEquals(27, Scale.pullHardKpa(57, 8.0 * HG));
        assertEquals(20, Scale.pullHardKpa(20, 8.0 * HG));
        // New to pumping, first month: 6 inHg too, hard.
        assertEquals(20, Scale.hardKpa(57, 0, true, 0));
        assertEquals(50, Scale.hardKpa(57, 0, true, 1), "from month two it is not");
        assertEquals(50, Scale.hardKpa(57, 0, false, 0), "nor for somebody who is not new");
        assertEquals(15, Scale.hardKpa(15, 0, true, 0));
        for (int ceil = 1; ceil <= 60; ceil++)
            for (double most = 0; most <= 16 * HG; most += 0.7)
                for (int month = 0; month <= 2; month++) {
                    int h = Scale.hardKpa(ceil, most, true, month);
                    assertTrue(h <= ceil || ceil < 1);
                    assertTrue(h <= Plan.ABSOLUTE_CAP_KPA);
                    if (most > 0) assertTrue(h <= most + 1e-9 || h == 1);
                    if (month < 1) assertTrue(h <= Plan.MONTH1_CAP_KPA);
                }
    }

    /* ------------------------------------------------------------------ the prescription */

    @Test
    void withNoOffsetThePrescriptionIsExactlyTheOldClampForGirth() {
        for (int t : GIRTH_TRACKS)
            for (int level = Plan.L1; level <= Plan.L4; level++)
                for (int month = 0; month <= 6; month++)
                    for (int ceil : new int[]{ 15, 20, 27, 34, 40, 57 })
                        for (double want = 0; want < 60; want += 0.37) {
                            int hard = Scale.hardKpa(ceil, 0, false, month);
                            int old = Mint.clampPressureKpa(want, level, month, ceil);
                            assertEquals(old, Scale.planOwnKpa(want, t, level, month, hard));
                            assertEquals(old, Scale.scaledKpa(want, 0.0, t, level, month, hard));
                        }
    }

    @Test
    void theOffsetMovesThePrescriptionByItselfInsideTheLimits() {
        // Girth L2 at 8 inHg, month 5, a roomy ceiling: plan 27, top 34.
        int hard = Scale.hardKpa(57, 0, false, 5);
        double plan = 8.0 * HG;
        assertEquals(27, Scale.planOwnKpa(plan, Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 5, hard));
        assertEquals(30, Scale.scaledKpa(plan, HG, Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 5, hard));
        assertEquals(20, Scale.scaledKpa(plan, -2 * HG, Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 5,
                                         hard));
        // At the plan's top, the offset still reaches past it - the top moved with it.
        assertEquals(41, Scale.scaledKpa(10 * HG, 2 * HG, Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 5,
                                         hard));
        // ...but never past the hard limit.
        assertEquals(36, Scale.scaledKpa(10 * HG, 2 * HG, Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 5,
                                         Scale.hardKpa(36, 0, false, 5)));
        assertEquals(27, Scale.scaledKpa(10 * HG, 5 * HG, Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 5,
                                         Scale.hardKpa(57, 8.0 * HG, false, 5)));
        // A plan figure an old file left above the usual top is held to it first.
        assertEquals(34, Scale.scaledKpa(11 * HG, 0, Plan.TRACK_LENGTH, Plan.L1, 3, hard));
    }

    @Test
    void theOffsetNeverChangesThePlansOwnFigure() {
        for (int t : new int[]{ Plan.TRACK_GIRTH_INTERVAL, Plan.TRACK_LENGTH })
            for (int level = Plan.L1; level <= Plan.L4; level++)
                for (int month = 0; month <= 4; month++)
                    for (double plan = 4 * HG; plan <= 10 * HG; plan += 0.5 * HG)
                        for (int o = -3; o <= 5; o++) {
                            int hard = Scale.hardKpa(57, 0, false, month);
                            Mint.Rx rx = Mint.prescribe(t, level, 1, plan, month, 57, 0, 0, null,
                                new Scale.Limits(o * HG, false, 0));
                            // EXPECTATION CHANGED (t10 REAL-14): the same person with no
                            // offset - not new, so their level's own top in month 0 too.
                            Mint.Rx rx0 = Mint.prescribe(t, level, 1, plan, month, 57, 0, 0,
                                null, new Scale.Limits(0, false, 0));
                            assertEquals(rx0.pressureKpa, rx.planKpa(),
                                "the plan's own figure is carried under any offset");
                            assertEquals(Scale.planOwnKpa(plan,
                                Scale.usualTopKpa(t, level, month, false), hard), rx.planKpa());
                            assertEquals(rx0.sets, rx.sets);
                            assertEquals(rx0.netTargetMin, rx.netTargetMin, 1e-9);
                        }
    }

    /* ------------------------------------------------------------ gentle, standard, firm */

    @Test
    void gentleStandardFirmAreThePlanLessOneTheePlanAndThePlanPlusOne() {
        int hard = 50;
        int top = 34;
        for (int rx = 5; rx <= 30; rx++) {
            assertEquals(Math.max(2, rx - 3), Scale.biasedKpa(rx, Model.Program.PRESS_GENTLE,
                                                             top, hard));
            assertEquals(rx, Scale.biasedKpa(rx, Model.Program.PRESS_STANDARD, top, hard));
            assertEquals(rx + 3, Scale.biasedKpa(rx, Model.Program.PRESS_FIRM, top, hard));
        }
        // Firm never passes the top (the bias does not move it) nor the hard limit, and
        // never goes under the prescription.
        assertEquals(34, Scale.biasedKpa(33, Model.Program.PRESS_FIRM, 34, 50));
        assertEquals(34, Scale.biasedKpa(34, Model.Program.PRESS_FIRM, 34, 50));
        assertEquals(30, Scale.biasedKpa(28, Model.Program.PRESS_FIRM, 34, 30));
        assertEquals(36, Scale.biasedKpa(36, Model.Program.PRESS_FIRM, 34, 50),
            "a prescription already over the top is not lowered by firm");
        // Gentle never above the prescription, never under 2.
        assertEquals(2, Scale.biasedKpa(3, Model.Program.PRESS_GENTLE, 34, 50));
        assertEquals(1, Scale.biasedKpa(1, Model.Program.PRESS_GENTLE, 34, 50));
    }

    @Test
    void aBeginnerOnFirmStartsOneAboveThePlanNotAtTheBandTop() {
        // Level 1, month 2, plan at the 5 inHg floor (17): the band top is 8 inHg (27).
        int hard = Scale.hardKpa(57, 0, true, 2);
        int top = Scale.effectiveTopWholeKpa(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 2, 0, true);
        int rx = Scale.scaledKpa(5 * HG, 0, Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 2, hard);
        assertEquals(17, rx);
        assertEquals(20, Scale.biasedKpa(rx, Model.Program.PRESS_FIRM, top, hard));
        // And in a new person's first month, firm never passes the hard 6 inHg.
        int hard0 = Scale.hardKpa(57, 0, true, 0);
        int top0 = Scale.effectiveTopWholeKpa(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 0, 3 * HG,
                                              true);
        assertEquals(20, top0, "no offset in the first month: the top is 6 inHg");
        assertEquals(20, Scale.biasedKpa(19, Model.Program.PRESS_FIRM, top0, hard0));
    }

    /* ---------------------------------------------------------------- the plan's scale */

    @Test
    void theCountingLineMovesDownWithWorkUnderThePlanAndNeverUp() {
        assertEquals(0.0, Scale.countingShiftKpa(0), 1e-9);
        assertEquals(0.0, Scale.countingShiftKpa(3), 1e-9, "working above never raises it");
        assertEquals(3.0, Scale.countingShiftKpa(-3), 1e-9);
        assertEquals(10.0, Scale.countingShiftKpa(-10), 1e-9);
        Model.Routine r = new Model.Routine();
        r.trainerScaleKpa = -7;
        assertEquals(Plan.floorKpa(Plan.L2) - 7, Model.scaledLevelFloorKpa(r, Plan.L2), 1e-9);
        r.trainerScaleKpa = 4;
        assertEquals(Plan.floorKpa(Plan.L2), Model.scaledLevelFloorKpa(r, Plan.L2), 1e-9);
    }

    @Test
    void theGateReadsThePlansFigure() {
        // A session at the plan's 8 inHg with a -2 inHg offset pulled 20: on the plan's scale
        // it is 27, which reaches the gate's 8 inHg.
        assertTrue(Plan.pressureReachedHg(Scale.onPlanScaleKpa(20, -7),
                                          Plan.L1_GATE_PRESSURE_HG));
        // A session at 7 inHg on the plan with +1 inHg on top pulled 27: the plan's 24 does
        // not reach it - an offset does not earn a level early.
        assertFalse(Plan.pressureReachedHg(Scale.onPlanScaleKpa(27, 3),
                                           Plan.L1_GATE_PRESSURE_HG));
        // And a session on the plan's own scale reads as it always did.
        assertTrue(Plan.pressureReachedHg(Scale.onPlanScaleKpa(27, 0),
                                          Plan.L1_GATE_PRESSURE_HG));
    }

    /* ---------------------------------------------------------------------- traction */

    @Test
    void oneLengthOffsetMovesThePullInItsOwnPounds() {
        // The length cylinder's bore (owner, 2026-09-30) - one with a 5 inch girth's
        // cross-section, so the table's 0.98 lb per inHg still holds.
        double girth = 5.0 * Model.Fmt.CM_PER_IN / Math.PI;
        double lbPerHg = Traction.lbPerInHgAtBore(5.0 / Math.PI);
        assertEquals(Traction.lbPerInHg(5.0), lbPerHg, 1e-9);
        // +1 inHg in it is about 0.98 lb more.
        assertEquals(5.0 + lbPerHg, Scale.commandedLoadLb(5.0, HG, girth, false, 4), 1e-9);
        // The pull pressure moves by exactly the offset: the expansion part and the pulls
        // move together.
        double withOff = Traction.kpaForLbAtBore(Scale.commandedLoadLb(5.0, HG, girth, false, 4),
                                                 girth);
        assertEquals(Traction.kpaForLbAtBore(5.0, girth) + HG, withOff, 1e-9);
        // Past twelve only by the person's own offset, never past fifteen.
        assertEquals(12.0 + 2 * lbPerHg, Scale.commandedLoadLb(12.0, 2 * HG, girth, false, 6),
                     1e-9);
        assertEquals(15.0, Scale.commandedLoadLb(12.0, 5 * HG, girth, false, 6), 1e-9);
        assertEquals(15.0, Scale.commandedLoadLb(15.0, 0, girth, false, 6), 1e-9);
        // A new person's first month: the 4 lb and no offset.
        assertEquals(4.0, Scale.commandedLoadLb(6.0, 0, girth, true, 0), 1e-9);
        assertEquals(4.0, Scale.commandedLoadLb(4.0, Scale.appliedOffsetKpa(3 * HG, true, 0),
                                                girth, true, 0), 1e-9);
        // A negative offset never leaves less than the least pull.
        assertEquals(Scale.LOAD_MIN_LB, Scale.commandedLoadLb(1.0, -5 * HG, girth, false, 6),
                     1e-9);
        // No girth, no conversion.
        assertEquals(5.0, Scale.commandedLoadLb(5.0, 2 * HG, 0, false, 6), 1e-9);
        for (double lb = 0.5; lb <= 15; lb += 0.5)
            for (int o = -3; o <= 5; o++)
                for (int month = 0; month <= 2; month++)
                    for (int nw = 0; nw < 2; nw++) {
                        boolean isNew = nw == 1;
                        double got = Scale.commandedLoadLb(lb,
                            Scale.appliedOffsetKpa(o * HG, isNew, month), girth, isNew, month);
                        assertTrue(got <= Scale.LOAD_HARD_MAX_LB + 1e-9);
                        if (isNew && month < 1) assertTrue(got <= 4.0 + 1e-9);
                    }
    }

    /* ---------------------------------------------------------------- warned once */

    @Test
    void anOffsetAboveTheUsualTopIsWarnedOncePerNewHigherValue() {
        double warned = 0.0;
        assertFalse(Scale.needsWarning(0.0, warned), "the plan itself is never warned");
        assertFalse(Scale.needsWarning(-2 * HG, warned), "nor anything under it");
        assertTrue(Scale.needsWarning(0.1 * HG, warned), "any offset over lifts the top");
        warned = Scale.confirmed(HG, warned);
        assertFalse(Scale.needsWarning(HG, warned), "confirmed once, not asked again");
        assertFalse(Scale.needsWarning(0.5 * HG, warned), "nor anything under it");
        assertTrue(Scale.needsWarning(1.1 * HG, warned), "a new higher value is asked");
        assertEquals(HG, Scale.confirmed(0.5 * HG, warned), 1e-9, "it only ever rises");
    }

    /* -------------------------------------------------------------- the stepper, setup */

    @Test
    void theStepperStepsExactlyAndStopsAtItsReach() {
        double step = 0.1 * HG;
        double v = 0.0;
        for (int i = 0; i < 17; i++) v = Scale.stepOffset(v, +1, step);
        for (int i = 0; i < 17; i++) v = Scale.stepOffset(v, -1, step);
        assertEquals(0.0, v, 0.0, "n up and n down is the identity");
        for (int i = 0; i < 200; i++) v = Scale.stepOffset(v, +1, step);
        assertEquals(Scale.OFFSET_MAX_KPA, v, 1e-9);
        for (int i = 0; i < 400; i++) v = Scale.stepOffset(v, -1, 1.0);
        assertEquals(-Scale.OFFSET_MAX_KPA, v, 1e-9);
    }

    @Test
    void aSetupAnswerOffThePlansFigureBecomesTheOffset() {
        assertEquals(2 * HG, Scale.offsetFromAnswer(10 * HG, 8 * HG), 1e-6);
        assertEquals(-1 * HG, Scale.offsetFromAnswer(4 * HG, 5 * HG), 1e-6);
        assertEquals(0.0, Scale.offsetFromAnswer(6 * HG, 6 * HG), 0.0);
        assertEquals(Scale.OFFSET_MAX_KPA, Scale.offsetFromAnswer(15 * HG, 5 * HG), 1e-9);
    }

    /* -------------------------------------------------------------- the signature */

    @Test
    void theOffsetTagRoundTripsAndIsAbsentWithNone() {
        assertEquals("", Scale.offsetTag(0.0));
        for (int o = -5; o <= 5; o++) {
            double off = o * HG;
            String sig = "0|1|6|27|120|0|S4:0:1355:300:R0" + (o == 0 ? ""
                : "|" + Scale.offsetTag(off)) + "|RET20";
            assertEquals(Math.round(off * 100) / 100.0, Scale.offsetOfSig(sig), 1e-9);
        }
        assertEquals(0.0, Scale.offsetOfSig("0|1|6|27|120|0|OFx|OF"), 0.0,
            "a segment that is not one is not read");
        Mint.Rx rx = Mint.rxFromSignature("0|1|6|30|120|0|" + Scale.offsetTag(HG));
        assertEquals(3, rx.offKpa, "the whole kPa it moved the figure by, as near as it can");
    }

    /* ---------------------------------------------------------------- the model's figures */

    @Test
    void aModelsLimitsAreItsOwn() {
        Model m = new Model();
        m.trainerGirth.offsetKpa = 2 * HG;
        m.trainerLength.offsetKpa = -HG;
        m.rxNewToPumping = false;
        m.rxWorkMaxKpa = 9 * HG;
        m.ceilKpa = 40;
        assertEquals(2 * HG, Scale.limitsOf(m, Plan.TRACK_GIRTH_INTERVAL).offsetKpa, 1e-9);
        assertEquals(2 * HG, Scale.limitsOf(m, Plan.TRACK_GIRTH_TRADITIONAL).offsetKpa, 1e-9);
        assertEquals(-HG, Scale.limitsOf(m, Plan.TRACK_LENGTH).offsetKpa, 1e-9);
        assertEquals(0.0, Scale.limitsOf(m, Plan.TRACK_FEEDER).offsetKpa, 0.0,
            "the feeder takes girth's offset through its main pressure, not twice");
        assertEquals(30, Scale.hardKpa(m, Plan.TRACK_GIRTH_INTERVAL, 4), "9 inHg is 30.5 - floored");
        m.trainerGirth.offsetKpa = 99;
        assertEquals(Scale.OFFSET_MAX_KPA, Scale.offsetKpa(m, Plan.TRACK_GIRTH_INTERVAL), 1e-9,
            "a file cannot hand the offset more than its reach");
    }

    @Test
    void biasNamesSayWhatTheyDoInThePersonsUnit() {
        String was = Model.Fmt.unit;
        try {
            Model.Fmt.unit = Model.Fmt.U_INHG;
            assertEquals("gentle (plan −1.0 inHg)", Scale.biasName(Model.Program.PRESS_GENTLE));
            assertEquals("standard (the plan)", Scale.biasName(Model.Program.PRESS_STANDARD));
            assertEquals("firm (plan +1.0 inHg)", Scale.biasName(Model.Program.PRESS_FIRM));
            assertEquals("the plan", Scale.offsetText(0));
            assertEquals("plan +1.0 inHg", Scale.offsetText(HG));
            assertEquals("plan −0.5 inHg", Scale.offsetText(-0.5 * HG));
            Model.Fmt.unit = Model.Fmt.U_KPA;
            assertEquals("firm (plan +3.4 kPa)", Scale.biasName(Model.Program.PRESS_FIRM));
            assertEquals("plan +2.0 kPa", Scale.offsetText(2.0));
            assertEquals(1.0, Scale.offsetStepKpa(), 0.0);
        } finally {
            Model.Fmt.unit = was;
        }
    }

    @Test
    void theBiasRuleAgreesWithTheOldCallersForm() {
        for (int b : BIASES)
            for (int level = Plan.L1; level <= Plan.L4; level++)
                for (int month = 0; month <= 3; month++)
                    for (int rx = 5; rx <= 40; rx++) {
                        int top = Scale.effectiveTopWholeKpa(Plan.TRACK_GIRTH_INTERVAL, level,
                                                             month, 0, false);
                        int hard = Scale.hardKpa(40, 0, false, month);
                        assertEquals(Math.min(hard, Scale.biasedKpa(rx, b, top, hard)),
                            Mint.biasKpa(rx, b, level, month, 40));
                    }
    }
}
