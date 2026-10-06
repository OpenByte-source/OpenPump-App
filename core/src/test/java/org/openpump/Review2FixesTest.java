package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE SECOND SAFETY REVIEW OF 0.10 - findings 1, 2, 3, 5 and 6, each pinned where its rule
 * lives (core). The app's wiring is WiringCheck invariant 249's.
 *
 *   1  a saved plan routine is held to TODAY's hard limits: at START (RunEdit#capToLimits over
 *      the run's plan; the check pull too), and a limit that moved since it was saved is not
 *      read as the person's edit (SavedMint#edited), so the plan's rewrite reaches it;
 *   2  "Rest of this ramp" raises a later step no further than its own hard limit, nor past the
 *      strip's usual 10.0 inHg unconfirmed; the highest pull a "+" puts anywhere is what the
 *      one-time warning asks about (SetShift#raisedPeak);
 *   3  a recalibration pre-fills what the person runs at (plan + offset) and, left as it was,
 *      keeps the offset - no silent rise;
 *   5  a load answer hidden behind "New to pumping" is ignored;
 *   6  a session's scale loads held to the scale's reach, as a routine's does.
 */
class Review2FixesTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final double HG = Plan.HG;
    private static final int UP = LiveEdit.UP;

    private String unitBefore;
    @BeforeEach void kpa() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_KPA; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    private static Model model(long now, int month, boolean isNew, int ceil, double most) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = now - 3 * DAY;
        m.trainerMonthsPumping = month;
        m.trainerMonthsAt = now - DAY;
        m.rxNewToPumping = isNew;
        m.ceilKpa = ceil;
        m.rxWorkMaxKpa = most;
        return m;
    }

    /** A plan routine minted the way the app mints one: {routine, its signature, its rx}. */
    private static Object[] mint(Model m, int level, double planKpa, int month, long now) {
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = level;
        st.weekIndex = 1;
        st.setWorkingPressure(planKpa, now);
        Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, st, month, null);
        String sig = Mint.signature(rx, m.mintShapeTag(Plan.TRACK_GIRTH_INTERVAL, level, now));
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx, 0, 0, RxBuild.Day.at(m, now)));
        return new Object[]{ r, sig, rx };
    }

    private static int peak(Model m, Model.Routine r) {
        int pk = 0;
        for (Model.Preset p : m.plan(r)) if (!p.rest) pk = Math.max(pk, p.up);
        return pk;
    }

    /* =========================================================== 1: the saved routine */

    @Test
    void aLoweredMostIsNotAnEditSoThePlansRewriteReachesTheRoutine() {
        long now = System.currentTimeMillis();
        int[] biases = { Model.Program.PRESS_GENTLE, Model.Program.PRESS_STANDARD,
                         Model.Program.PRESS_FIRM };
        int checked = 0;
        for (int bias : biases)
            for (double planHg : new double[]{ 8.5, 9.0, 9.8 }) {
                Model m = model(now, 7, false, 57, 0.0);
                m.programGirth.pressure = bias;
                Object[] mt = mint(m, Plan.L2, planHg * HG, 7, now);
                Model.Routine r = (Model.Routine) mt[0];
                String sig = (String) mt[1];
                Mint.Rx rx = (Mint.Rx) mt[2];
                assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, rx, now), "fresh");
                // "Most you will go to" lowered under the routine's work (or a 0.9 routine that
                // never read it): the rebuild in today's model is lower than the routine.
                int was = peak(m, r);
                m.rxWorkMaxKpa = 7.0 * HG;
                int hard = Scale.hardKpa(m, Plan.TRACK_GIRTH_INTERVAL, 7);
                assertTrue(was > hard, "the routine pulls past the new limit (" + was + ")");
                Mint.Rx today = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth,
                                                   7, null);
                assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, today, now),
                    "bias " + bias + " plan " + planHg + ": a lowered Most is the plan's limit, "
                    + "not the person's edit - the rewrite must reach it");
                // ...and what the rewrite writes is held to the new limit.
                assertTrue(SavedMint.rewrite(m, r, today, 0, 0));
                assertTrue(peak(m, r) <= hard, "rewritten under the limit: " + peak(m, r));
                checked++;
            }
        assertEquals(9, checked);
    }

    @Test
    void newToPumpingTurnedOnIsNotAnEditEither() {
        long now = System.currentTimeMillis();
        // In the first month the level's own top is 6 inHg already: only an offset takes a
        // routine past it - built while not new, then New turned on.
        Model m = model(now, 0, false, 57, 0.0);
        m.trainerGirth.offsetKpa = 2.0 * HG;
        Object[] mt = mint(m, Plan.L1, 6.0 * HG, 0, now);
        Model.Routine r = (Model.Routine) mt[0];
        String sig = (String) mt[1];
        assertTrue(peak(m, r) > 20, "built past the first month's 6 inHg while not new");
        m.rxNewToPumping = true;
        assertEquals(20, Scale.hardKpa(m, Plan.TRACK_GIRTH_INTERVAL, 0), "a new person's first month: 6 inHg");
        Mint.Rx today = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 0, null);
        assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, today, now),
            "New to pumping on is the plan's limit, not an edit");
        // ...and at START the run is held to it, whether or not a rewrite came first.
        List<Model.Preset> plan = m.plan(r);
        assertTrue(RunEdit.capToLimits(plan, limits(plan.size(), 20))[0] > 0);
        for (int k = 0; k < plan.size(); k++)
            assertTrue(plan.get(k).up <= 20, "preset " + k + " held to the first month's limit");
    }

    /** A traction session: turning New on in the first month holds its pulls to 4 lb - the
     *  rebuild pulled less than the routine, and it read as edited. */
    @Test
    void newToPumpingOnATractionSessionIsNotAnEdit() {
        long now = System.currentTimeMillis();
        Model m = model(now, 0, false, 57, 0.0);
        m.trainerLengthOn = true;
        Model.Reading rd = new Model.Reading();
        rd.ts = 1788440800000L;
        rd.method = Model.Reading.METHOD_MSEG;
        rd.gir = 12.7;
        m.measLog.all.add(rd);
        Model.Cylinder l = new Model.Cylinder();
        l.id = "L"; l.label = "Length tube"; l.role = Model.Cylinder.ROLE_LENGTH; l.boreCm = 4.0; l.lengthCm = 23.0;
        Model.Cylinder g = new Model.Cylinder();
        g.id = "G"; g.label = "Girth tube"; g.boreCm = 4.5; g.lengthCm = 23.0;
        m.cylinders.add(l);
        m.cylinders.add(g);
        m.activeCylinder = 0;
        Model.TrainerTrackState st = m.trainerLength;
        st.strainSets = 3;
        st.level = Plan.L1;
        st.weekIndex = 1;
        st.loadLb = 10.0;
        st.setWorkingPressure(5.0 * HG, now);
        Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_LENGTH, st, 0, null);
        String sig = Mint.signature(rx, m.mintShapeTag(Plan.TRACK_LENGTH, Plan.L1, now));
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx, 0, 0, RxBuild.Day.at(m, now)));
        assertTrue(Say.isTraction(r), "the fixture pulls");
        assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, rx, now), "fresh");
        m.rxNewToPumping = true;
        int cap = Scale.pullCapKpa(m, m.girthForTraction(), now);
        boolean past = false;
        for (Model.Preset p : m.plan(r)) if (!p.rest && p.up > cap) past = true;
        assertTrue(past, "the 10 lb pulls are past the first month's 4 lb");
        Mint.Rx today = TrainerTab.trackRx(m, Plan.TRACK_LENGTH, st, 0, null);
        assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, today, now),
            "New to pumping on holds the pulls to 4 lb - the plan's limit, not an edit");
    }

    @Test
    void aRealEditStillReadsAsAnEditUnderAMovedLimit() {
        long now = System.currentTimeMillis();
        Model m = model(now, 7, false, 57, 0.0);
        Object[] mt = mint(m, Plan.L2, 9.0 * HG, 7, now);
        Model.Routine r = (Model.Routine) mt[0];
        String sig = (String) mt[1];
        m.rxWorkMaxKpa = 8.0 * HG;
        Mint.Rx today = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 7, null);
        // A hold the person lengthened is theirs, limit or no limit.
        Model.Set s = null;
        for (Model.Stage st : r.stages)
            if (!st.rest && !st.setIds.isEmpty() && st.colour != Model.STAGE_WARM) {
                s = m.set(st.setIds.get(0));
                break;
            }
        assertNotNull(s);
        s.uh = s.uh + 7;
        assertTrue(SavedMint.edited(m, r, sig, 0, 0, now, today, now), "a longer hold is an edit");
        s.uh = s.uh - 7;
        // ...and a pull the person LOWERED under the limit is theirs too.
        s.up = 10;
        s.clamp(m.ceilKpa);
        assertTrue(SavedMint.edited(m, r, sig, 0, 0, now, today, now), "a lowered pull is an edit");
    }

    @Test
    void atStartEveryPresetOfAPlanRoutineIsHeldToTodaysLimit() {
        long now = System.currentTimeMillis();
        Model m = model(now, 7, false, 57, 0.0);
        m.programGirth.pressure = Model.Program.PRESS_FIRM;
        Object[] mt = mint(m, Plan.L2, 9.8 * HG, 7, now);
        Model.Routine r = (Model.Routine) mt[0];
        m.rxWorkMaxKpa = 8.0 * HG;                         // lowered after the save
        int hard = Scale.hardKpa(m, Plan.TRACK_GIRTH_INTERVAL, 7);
        List<Model.Preset> plan = m.plan(r);
        int[] was = new int[plan.size()];
        int[] lim = new int[plan.size()];
        for (int k = 0; k < plan.size(); k++) { was[k] = plan.get(k).up; lim[k] = hard; }
        int[] held = RunEdit.capToLimits(plan, lim);
        assertTrue(held[0] > 0, "the saved routine pulled past the new limit");
        assertEquals(hard, held[1], "the highest lowered lands on the limit");
        for (int k = 0; k < plan.size(); k++) {
            Model.Preset p = plan.get(k);
            if (p.rest) { assertEquals(0, p.up); continue; }
            assertTrue(p.up <= hard, "preset " + k + " at " + p.up);
            assertTrue(p.up <= was[k], "nothing is raised");
            assertTrue(p.lo < p.up, "the drop stays under the pull");
        }
        assertEquals(0, RunEdit.capToLimits(plan, lim)[0], "held once, nothing left to hold");
        // A limit of 0 (none known) holds nothing.
        assertEquals(0, RunEdit.capToLimits(m.plan(r), new int[plan.size()])[0]);
        String said = Scale.heldAtStartSaid(held[0], held[1]);
        assertNotNull(said);
        assertTrue(said.contains(Model.Fmt.p(hard)) && said.contains("most you said"), said);
        assertNull(Scale.heldAtStartSaid(0, 0), "nothing held, nothing said");
    }

    /* ================================================== 2: "Rest of this ramp" */

    private static List<Model.Preset> ramp(int... ups) {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        for (int k = 0; k < ups.length; k++) {
            Model.Preset p = new Model.Preset();
            p.setId = "R"; p.pos = 0; p.stageIdx = 0;
            p.up = ups[k]; p.lo = Math.max(0, ups[k] - 10); p.uh = 30; p.lh = 5; p.sp = 80;
            p.durMs = 60_000L; p.label = "R " + (k + 1);
            plan.add(p);
        }
        return plan;
    }

    private static int[] limits(int n, int lim) {
        int[] out = new int[n];
        java.util.Arrays.fill(out, lim);
        return out;
    }

    private static int[] withUp(int[] t, int up) {
        int[] o = t.clone();
        o[UP] = up;
        return o;
    }

    @Test
    void theReviewersRampAFirstMonthsWarmUpStopsAtSixInHg() {
        // A new person in their first month: 6 inHg, 20 kPa (Scale#hardKpa). A gentle warm-up
        // 14 -> 17 -> 20, its first step raised +3 kPa with "Rest of this ramp".
        int hard = Scale.hardKpa(57, 0.0, true, 0);
        assertEquals(20, hard);
        List<Model.Preset> plan = ramp(14, 17, 20);
        SetShift s = new SetShift();
        int[] before = SetShift.tuple(plan.get(0));
        int[] after = withUp(before, 17);
        int[] pk = s.raisedPeak(plan, 0, 2, before, after, 57, limits(3, hard));
        assertEquals(20, pk[0], "the highest later pull, under the first month's limit");
        assertNull(QuickAdjust.pullWarning(pk[0], 57, hard, 0), "under the usual limit: no ask");
        s.shift(plan, 0, 2, before, after, 57, limits(3, hard), QuickAdjust.CEILING_KPA);
        assertEquals(20, plan.get(1).up);
        assertEquals(20, plan.get(2).up, "23 was past 6 inHg - it stops at 20 (was: 23)");
        assertEquals(1, s.clampedAtLimit());
        assertEquals(0, s.clampedAtCeiling());
        assertEquals(20, s.limitStoppedAtKpa());
        String said = QuickAdjust.rampLimitSaid(s.clampedAtLimit(), s.limitStoppedAtKpa());
        assertTrue(said.contains("one later step stops") && said.contains(Model.Fmt.p(20)), said);
        assertNull(QuickAdjust.rampLimitSaid(0, 20));
        // Without the limits (the old call) it went past - the finding, reproduced.
        List<Model.Preset> old = ramp(14, 17, 20);
        new SetShift().shift(old, 0, 2, SetShift.tuple(old.get(0)),
                             withUp(SetShift.tuple(old.get(0)), 17), 57);
        assertEquals(23, old.get(2).up, "the ceiling alone let it past the first month's limit");
    }

    @Test
    void pastTheStripsUsualLimitIsAskedAboutTheHighestLaterStep() {
        int hard = Scale.hardKpa(57, 0.0, false, 7);           // 15 inHg: 50
        List<Model.Preset> plan = ramp(30, 32, 34);
        SetShift s = new SetShift();
        int[] before = SetShift.tuple(plan.get(0));
        int[] after = withUp(before, 33);                       // the step playing: under 34
        assertNull(QuickAdjust.pullWarning(33, 57, hard, 0), "the step playing alone: no ask");
        int[] pk = s.raisedPeak(plan, 0, 2, before, after, 57, limits(3, hard));
        assertEquals(37, pk[0]);
        assertEquals(2, pk[1]);
        assertNotNull(QuickAdjust.pullWarning(pk[0], 57, hard, 0),
            "the ramp's last step goes past the usual 10.0 inHg: asked once");
        // Unconfirmed (the sheet's road, which never asks): the later steps stop at the usual
        // limit, flat there, and the run says so.
        List<Model.Preset> sheet = ramp(30, 32, 34);
        SetShift ss = new SetShift();
        ss.shift(sheet, 0, 2, SetShift.tuple(sheet.get(0)),
                 withUp(SetShift.tuple(sheet.get(0)), 33), 57, limits(3, hard),
                 QuickAdjust.CEILING_KPA);
        assertEquals(34, sheet.get(1).up);
        assertEquals(34, sheet.get(2).up);
        assertEquals(2, ss.clampedAtLimit());
        // Confirmed to 37 (the warning's "Pull to"): it goes there, and no further.
        s.shift(plan, 0, 2, before, after, 57, limits(3, hard), Math.max(34, pk[0]));
        assertEquals(35, plan.get(1).up);
        assertEquals(37, plan.get(2).up);
        assertEquals(0, s.clampedAtLimit());
        // Past the hard limit nothing is asked - it is held there.
        List<Model.Preset> high = ramp(44, 47, 49);
        int[] hb = SetShift.tuple(high.get(0));
        int[] hp = new SetShift().raisedPeak(high, 0, 2, hb, withUp(hb, 47), 57, limits(3, hard));
        assertEquals(hard, hp[0], "the highest later pull is held to 15 inHg");
    }

    /** The old six-argument shift is the new one with no limits: byte for byte. */
    @Test
    void noLimitsIsTheOldShift() {
        Random rnd = new Random(2049);
        for (int t = 0; t < 400; t++) {
            int n = 2 + rnd.nextInt(6);
            int[] ups = new int[n];
            for (int k = 0; k < n; k++) ups[k] = 5 + rnd.nextInt(50);
            List<Model.Preset> a = ramp(ups), b = ramp(ups);
            SetShift sa = new SetShift(), sb = new SetShift();
            int ceil = 20 + rnd.nextInt(38);
            for (int step = 0; step < 6; step++) {
                int d = rnd.nextInt(13) - 6;
                int[] before = SetShift.tuple(a.get(0));
                int[] after = withUp(before, Math.max(1, before[UP] + d));
                sa.shift(a, 0, n - 1, before, after, ceil);
                sb.shift(b, 0, n - 1, before, after, ceil, null, Integer.MAX_VALUE);
                a.get(0).up = after[UP];
                b.get(0).up = after[UP];
                for (int k = 0; k < n; k++)
                    assertEquals(java.util.Arrays.toString(SetShift.tuple(a.get(k))),
                                 java.util.Arrays.toString(SetShift.tuple(b.get(k))));
                assertEquals(sa.clampedAtCeiling(), sb.clampedAtCeiling());
            }
        }
    }

    /**
     * THE SWEEP: random ramps, limits and nudge sequences. A raise never takes a later step past
     * the ceiling, its own hard limit or the usual limit unless it was already there; a "+"
     * never lowers a step and a "-" never raises one; an ascending ramp under one limit stays in
     * order; and raisedPeak is exactly the highest pull a shift with no usual limit writes.
     */
    @Test
    void everyLaterStepStaysInsideItsLimitsAndTheGestureDirection() {
        Random rnd = new Random(1704);
        int cases = 0, asked = 0, stopped = 0;
        for (int t = 0; t < 3000; t++) {
            int n = 2 + rnd.nextInt(7);
            boolean asc = rnd.nextBoolean();
            int start = 3 + rnd.nextInt(40), stepUp = rnd.nextInt(5);
            int ceil = 15 + rnd.nextInt(43);
            int hard = 12 + rnd.nextInt(45);
            int usual = rnd.nextBoolean() ? QuickAdjust.CEILING_KPA : 30 + rnd.nextInt(25);
            int[] ups = new int[n];
            // As the plan is sent: every pull already inside the ceiling (Model#plan).
            for (int k = 0; k < n; k++)
                ups[k] = Math.min(ceil, Math.max(1, asc ? start + k * stepUp
                                                        : start + (n - k) * stepUp));
            List<Model.Preset> plan = ramp(ups);
            SetShift s = new SetShift();
            for (int step = 0; step < 8; step++) {
                int d = rnd.nextInt(11) - 5;
                if (d == 0) continue;
                int[] before = SetShift.tuple(plan.get(0));
                int[] after = withUp(before, Math.max(1, before[UP] + d));
                int[] pk = s.raisedPeak(plan, 0, n - 1, before, after, ceil, limits(n, hard));
                int[] was = new int[n];
                for (int k = 0; k < n; k++) was[k] = plan.get(k).up;
                s.shift(plan, 0, n - 1, before, after, ceil, limits(n, hard), usual);
                plan.get(0).up = after[UP];
                cases++;
                int raisedTop = 0;
                for (int k = 1; k < n; k++) {
                    int up = plan.get(k).up;
                    String at = "t" + t + " step " + k + " (" + was[k] + " -> " + up + ", ceil "
                        + ceil + ", hard " + hard + ", usual " + usual + ", base " + ups[k] + ")";
                    assertTrue(up <= ceil, "past the ceiling: " + at);
                    assertTrue(up <= Math.max(ups[k], Math.min(hard, usual)),
                        "raised past its hard or usual limit: " + at);
                    if (d > 0) assertTrue(up >= was[k], "a plus lowered: " + at);
                    if (d < 0) assertTrue(up <= was[k], "a minus raised: " + at);
                    // (the base is never retaken here: nothing else edits the tail)
                    if (up > was[k] && up > ups[k]) raisedTop = Math.max(raisedTop, up);
                }
                if (s.clampedAtLimit() > 0) stopped++;
                if (asc)
                    for (int k = 2; k < n; k++)
                        assertTrue(plan.get(k).up >= plan.get(k - 1).up, "t" + t + ": order kept");
                // raisedPeak is what the shift raises to, before the usual limit: equal to the
                // highest raised pull wherever the usual limit did not stop it.
                if (pk[1] < 0) {
                    assertEquals(0, raisedTop, "t" + t + ": no raise previewed, none made past "
                        + "a step's own figure");
                } else {
                    assertTrue(pk[0] > was[pk[1]] && pk[0] > ups[pk[1]],
                        "the peak is a raise past the step's own figure");
                    assertTrue(pk[0] <= Math.min(ceil, Math.max(hard, ups[pk[1]])),
                        "the peak is held to the ceiling and the hard limit");
                    if (pk[0] <= usual) assertEquals(pk[0], raisedTop, "t" + t + ": the peak");
                    else asked++;
                }
            }
        }
        assertTrue(cases > 10_000, "cases " + cases);
        assertTrue(asked > 100 && stopped > 100, "asked " + asked + ", stopped " + stopped);
    }

    /* ============================================================ 3: recalibration */

    @Test
    void anUntouchedRecalibrationKeepsTheOffsetAndThePressure() {
        int[] months = { 0, 2, 5, 7, 13 };
        int[] styles = { Plan.TRACK_GIRTH_INTERVAL, Plan.TRACK_GIRTH_TRADITIONAL };
        int checked = 0, lostBefore = 0;
        for (int style : styles)
            for (int mo : months)
                for (boolean isNew : new boolean[]{ false, true })
                    for (int p = 14; p <= 50; p++) {
                        TrainerTab.Derived d = TrainerTab.deriveGirth(style, mo, p, isNew,
                            Plan.ABSOLUTE_CAP_KPA, 0);
                        double plan = Scale.setupPlanKpa(style, d.level, d.monthIndex,
                                                         d.pressureKpa);
                        if (Math.abs(plan - p) > 1e-9) continue;   // not a figure the plan holds
                        for (int o = -5; o <= 5; o++) {
                            double off = o * HG;
                            double answer = Scale.recalAnswerKpa(plan, off);
                            // Left as it was pre-filled: derived from the figure under it.
                            double basis = Scale.setupBasisKpa(answer, answer, off);
                            TrainerTab.Derived d2 = TrainerTab.deriveGirth(style, mo, basis,
                                isNew, Plan.ABSOLUTE_CAP_KPA, 0);
                            double plan2 = Scale.setupPlanKpa(style, d2.level, d2.monthIndex,
                                                              d2.pressureKpa);
                            double off2 = Scale.offsetFromAnswer(answer, plan2);
                            assertEquals(d.level, d2.level, "the level stays");
                            assertEquals(plan, plan2, 1e-9, "the plan's figure stays");
                            assertEquals(Scale.clampOffset(off), off2, 1e-6,
                                "style " + style + " month " + mo + " plan " + p + " offset "
                                + o + " inHg: the offset stays");
                            assertEquals(plan + Scale.clampOffset(off), plan2 + off2, 1e-6,
                                "what it runs at stays");
                            // The old pre-fill (the plan's figure alone) read as "the plan".
                            if (o != 0 && Math.abs(Scale.offsetFromAnswer(plan, plan2)) < 1e-9)
                                lostBefore++;
                            checked++;
                        }
                    }
        assertTrue(checked > 500, "checked " + checked);
        assertTrue(lostBefore > 400, "the finding: the old pre-fill lost the offset");
    }

    @Test
    void aChangedAnswerIsANewAnswerAndAskedAsAnyOther() {
        double plan = 9.0 * HG, off = -2.0 * HG;
        double prefill = Scale.recalAnswerKpa(plan, off);
        assertEquals(plan - 2.0 * HG, prefill, 1e-9, "what the person runs at");
        assertEquals(plan, Scale.setupBasisKpa(prefill, prefill, off), 1e-9);
        double moved = prefill + 3.0;
        assertEquals(moved, Scale.setupBasisKpa(moved, prefill, off), 1e-9,
            "changed: the answer itself, as a first setup reads it");
        assertEquals(31.0, Scale.setupBasisKpa(31.0, Double.NaN, off), 1e-9,
            "nothing pre-filled: the answer");
        assertTrue(Double.isNaN(Scale.recalAnswerKpa(Double.NaN, off)));
        // The warned-once rule, unchanged: an offset kept as it was asks nothing again; one
        // raised past what was confirmed asks.
        double up = 2.0 * HG;
        assertFalse(Scale.needsWarning(up, up), "kept as confirmed");
        assertTrue(Scale.needsWarning(up + 1.0, up), "raised past it");
        assertFalse(Scale.needsWarning(off, 0.0), "below the plan: nothing past the top");
    }

    /* ======================================================== 5: the hidden load */

    @Test
    void aLoadAnswerHiddenBehindNewToPumpingIsIgnored() {
        // The reviewer's steps: not new, 13 lb, back, New on, Confirm.
        double first = Scale.setupLoadLb(true, true, 13.0, Plan.LENGTH_LOAD_START_LB);
        assertEquals(Plan.LENGTH_LOAD_START_LB, first, 1e-9, "a first enrolment: the plan's start");
        assertFalse(Scale.pastUsualLoad(first));
        // From month 2 the pull is built from the plan's load, never the hidden 13 lb.
        double girth = 12.7;
        assertTrue(Scale.commandedLoadLb(first, 0.0, girth, true, 1) <= Traction.LOAD_MAX_LB);
        assertEquals(6.0, Scale.setupLoadLb(true, false, 13.0, 6.0), 1e-9,
            "a recalibration: the load on file, untouched by the hidden answer");
        // Not new: the answer, held to 15 lb and 1 lb - and past 12 it is warned (setup).
        assertEquals(13.0, Scale.setupLoadLb(false, true, 13.0, 2.5), 1e-9);
        assertTrue(Scale.pastUsualLoad(Scale.setupLoadLb(false, true, 13.0, 2.5)));
        assertEquals(15.0, Scale.setupLoadLb(false, false, 40.0, 2.5), 1e-9);
        assertEquals(1.0, Scale.setupLoadLb(false, false, 0.0, 2.5), 1e-9);
    }

    /* ======================================================= 6: a session's scale */

    @Test
    void aSessionsScaleLoadsHeldToItsReach() throws Exception {
        Model m = new Model();
        Model.Sess s = new Model.Sess();
        s.id = "s1"; s.ts = 5L; s.scaleKpa = -999;
        m.sessLog.all.add(s);
        Model.Sess t = new Model.Sess();
        t.id = "s2"; t.ts = 6L; t.scaleKpa = -4;
        m.sessLog.all.add(t);
        Model back = Model.fromJson(m.toJson());
        int reach = Scale.SCALE_REACH_KPA;
        assertEquals((int) Math.ceil(Scale.OFFSET_MAX_KPA + Scale.BIAS_KPA), reach);
        for (Model.Sess x : back.sessLog.all) {
            if ("s1".equals(x.id)) assertEquals(-reach, x.scaleKpa, "held to its reach");
            if ("s2".equals(x.id)) assertEquals(-4, x.scaleKpa, "an ordinary scale as it was");
        }
        // The readers hold it too: a pull read back on the plan's scale, and the counting line.
        assertEquals(20.0 + reach, Scale.onPlanScaleKpa(20.0, -999), 1e-9);
        assertEquals(reach, Scale.countingShiftKpa(-999), 1e-9);
        assertEquals(4.0, Scale.countingShiftKpa(-4), 1e-9);
        assertEquals(17.0, Scale.onPlanScaleKpa(20.0, 3), 1e-9);
        // 8 inHg is 27 kPa: a 20 kPa pull with a -999 scale no longer reads as past it by miles.
        assertTrue(Scale.onPlanScaleKpa(15.0, -999) < 8.0 * HG + reach);
        assertEquals(reach, Scale.clampScaleKpa(9999));
        assertEquals(0, Scale.clampScaleKpa(0));
    }
}
