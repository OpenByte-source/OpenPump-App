package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE SAFETY REVIEW OF 0.10 (741b51d..866eea3), ITS FINDINGS PINNED. Each test names the
 * finding it holds shut; the app's glue around them is pinned by WiringCheck invariant 242.
 *
 *   H1  "· the rest" of a hybrid routine builds the holds that are left, never the hybrid again;
 *   H2  a new prescription never rewrites a pressure the person set under the Program's bias,
 *       and the notice states the figures each side runs at;
 *   M1  "Adjust first..." starts from the biased figure, so a pressure moved down never
 *       commands more than the plan's own routine;
 *   M2  a lighter-day rebuild that would switch the length session is recognised (refused);
 *   M3  older plan-built routines are weighed on a lighter day;
 *   M4  a lower set count under the hybrid is honoured - fewer holds, never more than the
 *       guidance's;
 *   L1  the rest of an adjusted routine runs at the person's pressure;
 *   L2  the setup's trainer choice on an enrolled phone just opens the Trainer;
 *   and the card's count is the routine's name's (the T5 report's concern 5).
 * (M5 - the drift comparison - is pinned in SavedMintTest beside the rest of it.)
 */
class ReviewFixesTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final int[] BIASES = { Model.Program.PRESS_GENTLE,
        Model.Program.PRESS_STANDARD, Model.Program.PRESS_FIRM };
    private static final String[] BIAS_NAMES = { "gentle", "standard", "firm" };
    private static final int[] SHAPES = { Model.Program.WORK_FIXED,
        Model.Program.WORK_RAMP_IN_SET, Model.Program.WORK_ASCENDING,
        Model.Program.WORK_PYRAMID };
    private static final int NONE = 0, CUT = 1, TAPER1 = 2, TAPER2 = 3;
    private static final String[] RED_NAMES = { "none", "cylinder cut", "taper 1", "taper 2" };

    /* ------------------------------------------------------------------------- helpers */

    private static Model model(int reduction, long now) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerMonthsPumping = 4;
        m.ceilKpa = 40;
        reduce(m, reduction, now);
        return m;
    }

    private static void reduce(Model m, int reduction, long now) {
        if (reduction == CUT) m.oversizeAccepted = true;
        if (reduction == TAPER1 || reduction == TAPER2) {
            Deload.arm(m, now - DAY);
            if (reduction == TAPER2) m.returnStep = 1;
        }
    }

    private static void program(Model m, int track, int work, int bias) {
        Model.Program p = m.programFor(track);
        if (p == null) return;
        p.work = work;
        p.pressure = bias;
    }

    private static Mint.Rx rx(Model m, int track, int level, int week, double hg, int carried,
                              long now) {
        return Mint.prescribe(track, level, week, hg * Plan.HG,
            TrainerTab.monthIndexNow(m, now), m.ceilKpa, carried, 0, null);
    }

    private static Mint.Rx girthL3(Model m, long now) {
        return rx(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 1, 9.0, 13, now);
    }

    private static Mint.Rx girthL4(Model m, long now) {
        return rx(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L4, 1, 10.0, 0, now);
    }

    private static Mint.Rx girthL2(Model m, long now) {
        return rx(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 6, 9.0, 0, now);
    }

    private static String sig(Model m, Mint.Rx rx, long now) {
        return Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
    }

    /** Work holds (not the warm-up, rests, the fatigue block or retention), and whether every
     *  one of them is five minutes long. */
    private static int holds(Model m, Model.Routine r) {
        int n = 0;
        for (Model.Stage st : r.stages) {
            if (st.rest || st.outOfNet()) continue;
            for (String id : st.setIds) {
                Model.Set s = m.set(id);
                if (s != null && !s.rest) n += s.dur / s.cycle();
            }
        }
        return n;
    }

    private static boolean allFiveMinutes(Model m, Model.Routine r) {
        for (Model.Stage st : r.stages) {
            if (st.rest || st.outOfNet()) continue;
            for (String id : st.setIds) {
                Model.Set s = m.set(id);
                if (s != null && !s.rest && s.uh != Mint.HOLD_TRADITIONAL_SEC) return false;
            }
        }
        return true;
    }

    /** The deepest pull of the work (not the warm-up, a rest, the fatigue block, retention). */
    private static int workPeak(Model m, Model.Routine r) {
        int pk = 0;
        for (Model.Stage st : r.stages) {
            if (st.rest || st.colour == Model.STAGE_WARM || st.traction) continue;
            for (String id : st.setIds) {
                Model.Set s = m.set(id);
                if (s != null) pk = Math.max(pk, Math.max(s.up, s.endUp()));
            }
        }
        return pk;
    }

    /** The save "Adjust first..." makes, exactly as SessionActivity#saveMint makes it now. */
    private static Model.Routine adjustedSave(Model m, Mint.Rx rx, int sets, int kpa) {
        boolean adjusted = RxBuild.adjustMoved(m, rx, sets, kpa);
        Mint.Rx use = RxBuild.adjustedRx(m, rx, sets, kpa);
        return m.routine(RxBuild.routineFromRx(m, use, 0, 0, RxBuild.Day.today(m)
            .ownPressure(RxBuild.adjustOwnKpa(m, rx, kpa)).ownSetCount(adjusted)));
    }

    /** The signature saveMint stores for that save. */
    private static String adjustedSig(Model m, Mint.Rx rx, int sets, int kpa, long now) {
        Mint.Rx use = RxBuild.adjustedRx(m, rx, sets, kpa);
        return Mint.adjustedSignature(sig(m, rx, now), use.sets, use.pressureKpa,
                                      RxBuild.adjustOwnKpa(m, rx, kpa));
    }

    /* ------------------------------------------------------------------ H1 - the rest */

    @Test
    void theRestOfAHybridIsTheHoldsThatAreLeftNeverTheWholeHybridAgain() {
        long now = System.currentTimeMillis();
        // EXPECTATION CHANGED (R-27): the guidance's 6 / 8 run as the time cap's 5 / 7.
        int[] guide = { 5, 7 };
        for (int lv = 0; lv < 2; lv++)
            for (int left = 1; left <= 12; left++) {
                Model m = model(NONE, now);
                m.trainerGirthHybrid = true;
                Mint.Rx rx = lv == 0 ? girthL3(m, now) : girthL4(m, now);
                Model.Routine src = m.routine(RxBuild.routineFromRx(m, rx));
                assertEquals(guide[lv], holds(m, src), "the plan's own hybrid");
                String what = (lv == 0 ? "L3" : "L4") + ", " + left + " left";
                String id = RxBuild.remainder(m, src, rx, null, left, true);
                assertNotNull(id, what + ": refused");
                Model.Routine rest = m.routine(id);
                int want = Math.min(left, guide[lv]);
                assertEquals(want, holds(m, rest), what + ": '" + rest.name + "'");
                assertTrue(allFiveMinutes(m, rest), what + ": the hybrid's own holds");
                assertTrue(rest.name.contains(" · " + want + "×5min @ "), what + ": " + rest.name);
                assertEquals(want, RxBuild.runsAs(m, RxBuild.remainderRx(rx, null, left), true).sets,
                    what + ": what the snack says is left");
                assertTrue(SavedMint.governedPeakKpa(m, rest) <= SavedMint.governedPeakKpa(m, src),
                    what + ": the rest pulls harder than the session");
                assertEquals(want * 5.0, rest.netTargetMin, 1e-9, what + ": its target");
            }
    }

    @Test
    void theRestOfAnyRoutineNeverPullsHarderThanItAndARefusalBuildsNothing() {
        long now = System.currentTimeMillis();
        for (int bi = 0; bi < BIASES.length; bi++)
            for (int si = 0; si < SHAPES.length; si++) {
                Model m = model(NONE, now);
                program(m, Plan.TRACK_GIRTH_INTERVAL, SHAPES[si], BIASES[bi]);
                Mint.Rx rx = girthL2(m, now);
                Model.Routine src = m.routine(RxBuild.routineFromRx(m, rx));
                String what = BIAS_NAMES[bi] + " / shape " + SHAPES[si];
                String id = RxBuild.remainder(m, src, rx, null, 3, false);
                assertNotNull(id, what);
                assertTrue(SavedMint.governedPeakKpa(m, m.routine(id))
                    <= SavedMint.governedPeakKpa(m, src), what);
                // A source the person made lighter by hand: the rest is not built heavier.
                for (Model.Stage st : src.stages) {
                    if (st.rest || st.colour == Model.STAGE_WARM) continue;
                    for (String sid : st.setIds) {
                        Model.Set s = m.set(sid);
                        s.up = Math.max(2, s.up - 3);
                        s.lo = Math.min(s.lo, s.up - 1);
                        if (s.ramp) { s.up2 = Math.max(2, s.up2 - 3); s.lo2 = Math.min(s.lo2, s.up2 - 1); }
                    }
                }
                int routines = m.routines.size(), sets = m.sets.size();
                // No warm-up on the rest: after P2 a three-hold rest climbs on at +1 kPa a hold
                // and may never reach the figure at all (t10 R-02) - weighed from its first hold.
                assertNull(RxBuild.remainder(m, src, rx, null, 3, false),
                    what + ": the rest of a lighter routine built at the plan's figure");
                assertEquals(routines, m.routines.size(), what + ": a refusal left a routine");
                assertEquals(sets, m.sets.size(), what + ": a refusal left sets");
            }
    }

    /* ------------------------------------------------------ L1 - the rest of an adjustment */

    @Test
    void theRestOfAnAdjustedRoutineRunsAtThePersonsPressure() {
        long now = System.currentTimeMillis();
        for (int bi = 0; bi < BIASES.length; bi++)
            for (int red = NONE; red <= TAPER2; red++) {
                Model m = model(red, now);
                program(m, Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_FIXED, BIASES[bi]);
                Mint.Rx rx = girthL2(m, now);
                int kpa = RxBuild.adjustStartKpa(m, rx) - 3;
                Model.Routine src = adjustedSave(m, rx, rx.sets, kpa);
                String stored = adjustedSig(m, rx, rx.sets, kpa, now);
                Mint.Adjust kept = SavedMint.carried(stored, sig(m, rx, now));
                String what = BIAS_NAMES[bi] + " / " + RED_NAMES[red];
                assertNotNull(kept, what + ": the save carries its adjustment");
                String id = RxBuild.remainder(m, src, rx, kept, 3, true);
                assertNotNull(id, what + ": the rest of an adjusted routine refused");
                assertEquals(Mint.reducedKpa(kpa, m.reductionKpa(now)), workPeak(m, m.routine(id)),
                    what + ": the rest runs at another figure than the person's less the cut");
                // Built as the plan's (review L1) it would pull harder than the session under
                // Standard and Firm - and that is now refused rather than run.
                if (BIASES[bi] != Model.Program.PRESS_GENTLE)
                    assertNull(RxBuild.remainder(m, src, rx, null, 3, false),
                        what + ": the plan's figure for the rest of a lighter routine");
            }
    }

    /* --------------------------------------------------------- M4 - the hybrid's count */

    @Test
    void aLowerSetCountUnderTheHybridIsFewerHoldsAndNeverMoreThanTheGuidance() {
        long now = System.currentTimeMillis();
        // EXPECTATION CHANGED (R-27): the guidance's 6 / 8 run as the time cap's 5 / 7.
        int[] guide = { 5, 7 };
        for (int lv = 0; lv < 2; lv++)
            for (int red = NONE; red <= TAPER2; red++)
                for (int want = 1; want <= guide[lv] + 3; want++) {
                    Model m = model(red, now);
                    m.trainerGirthHybrid = true;
                    Mint.Rx rx = lv == 0 ? girthL3(m, now) : girthL4(m, now);
                    String what = (lv == 0 ? "L3" : "L4") + " / " + RED_NAMES[red] + " / " + want;
                    assertEquals(guide[lv], RxBuild.adjustStartSets(m, rx), what + ": the start");
                    assertEquals(guide[lv], RxBuild.adjustMaxSets(m, rx), what + ": the stepper's top");
                    int kpa = RxBuild.adjustStartKpa(m, rx);
                    Model.Routine r = adjustedSave(m, rx, want, kpa);
                    assertEquals(Math.min(want, guide[lv]), holds(m, r), what + ": " + r.name);
                    assertTrue(allFiveMinutes(m, r), what);
                    // The save is the plan's own, and a taper step rebuilds it as they set it.
                    String stored = adjustedSig(m, rx, want, kpa, now);
                    assertFalse(SavedMint.edited(m, r, stored, 0, 0, now, null, now),
                        what + ": the adjusted hybrid read as edited");
                    Mint.Adjust kept = SavedMint.carried(stored, sig(m, rx, now));
                    if (want != guide[lv]) assertNotNull(kept, what);
                    assertTrue(SavedMint.rewrite(m, r, SavedMint.carriedRx(rx, sig(m, rx, now), stored),
                        0, 0, kept), what);
                    assertEquals(Math.min(want, guide[lv]), holds(m, r), what + ": the rewrite's count");
                }
        // With "each hold" moved (a reshape would recount the sets at the other hold).
        Model m = model(NONE, now);
        m.trainerGirthHybrid = true;
        m.rxHoldSec = 180;
        Mint.Rx rx = girthL3(m, now);
        assertEquals(3, holds(m, adjustedSave(m, rx, 3, RxBuild.adjustStartKpa(m, rx))),
            "the hold setting recounted the person's holds");
        // The plan's own hybrid is still the guidance's, the hold setting notwithstanding.
        // (R-27: under the time cap, 5 of the guidance's 6.)
        assertEquals(5, holds(m, m.routine(RxBuild.routineFromRx(m, rx))));
        // Off the hybrid the stepper's bound and the sets are as they were.
        Model plain = model(NONE, now);
        Mint.Rx prx = girthL3(plain, now);
        assertEquals(prx.sets, RxBuild.adjustStartSets(plain, prx));
        assertEquals(RxBuild.ADJUST_MAX_SETS, RxBuild.adjustMaxSets(plain, prx));
    }

    /* ------------------------------------------------ M1 - the sheet starts from the plan */

    @Test
    void aPressureMovedDownInAdjustNeverCommandsMoreThanThePlansOwnRoutine() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (int bi = 0; bi < BIASES.length; bi++)
            for (int red = NONE; red <= TAPER2; red++)
                for (int si = 0; si < SHAPES.length; si++)
                    for (int pos = 0; pos < 4; pos++) {
                        Model m = model(red, now);
                        program(m, Plan.TRACK_GIRTH_INTERVAL, SHAPES[si], BIASES[bi]);
                        Mint.Rx rx = pos == 0 ? girthL2(m, now) : pos == 1 ? girthL3(m, now)
                            : pos == 2 ? girthL4(m, now)
                            : rx(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 10, 7.0, 0, now);
                        String what = BIAS_NAMES[bi] + " / " + RED_NAMES[red] + " / shape "
                            + SHAPES[si] + " / position " + pos;
                        int start = RxBuild.adjustStartKpa(m, rx);
                        assertEquals(RxBuild.biasedKpa(m, rx), start, what);
                        Model.Routine plan = m.routine(RxBuild.routineFromRx(m, rx));
                        int planPeak = workPeak(m, plan);
                        // Saving the sheet as it opened is the plan's own routine, unadjusted.
                        assertFalse(RxBuild.adjustMoved(m, rx, RxBuild.adjustStartSets(m, rx), start),
                            what);
                        assertSame(rx, RxBuild.adjustedRx(m, rx, RxBuild.adjustStartSets(m, rx),
                            start), what);
                        for (int d = 1; d <= 3; d++) {
                            Model.Routine r = adjustedSave(m, rx, RxBuild.adjustStartSets(m, rx),
                                                           start - d);
                            assertTrue(workPeak(m, r) <= planPeak, what + ": " + d + " down from "
                                + start + " pulls " + workPeak(m, r) + " over the plan's "
                                + planPeak);
                        }
                        n++;
                    }
        assertEquals(3 * 4 * 4 * 4, n);
    }

    @Test
    void underGentleTheSheetShowsTheGentleFigure() {
        long now = System.currentTimeMillis();
        Model m = model(NONE, now);
        program(m, Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_FIXED, Model.Program.PRESS_GENTLE);
        Mint.Rx rx = girthL2(m, now);
        int gentle = RxBuild.commandedKpa(m, rx);
        assertTrue(gentle < rx.pressureKpa, "the position is one Gentle lowers");
        assertEquals(gentle, RxBuild.adjustStartKpa(m, rx));
        // One step down from the sheet's figure is one under the Gentle routine - where one step
        // down from the plain prescription (the old start) pulled harder than it.
        assertTrue(RxBuild.commandedKpa(m, Mint.adjusted(rx, rx.sets, gentle - 1), true) < gentle);
        assertTrue(RxBuild.commandedKpa(m, Mint.adjusted(rx, rx.sets, rx.pressureKpa - 1), true)
            > gentle, "the finding as it was: the plain figure less one is above Gentle");
    }

    /* ------------------------------------------------- H2 - a pressure the person set */

    @Test
    void aNewPrescriptionOffersRatherThanRewritingAPressureThePersonSet() {
        long now = System.currentTimeMillis();
        for (int bi = 0; bi < BIASES.length; bi++) {
            Model m = model(NONE, now);
            program(m, Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_FIXED, BIASES[bi]);
            Mint.Rx offer = girthL2(m, now);
            int own = RxBuild.adjustStartKpa(m, offer) - 2;
            String stored = adjustedSig(m, offer, offer.sets, own, now);
            assertTrue(SavedMint.ownKpa(stored), BIAS_NAMES[bi]);
            // The plan's next week: a set more, from the working pressure the save moved to.
            Mint.Rx next = Mint.adjusted(Mint.adjusted(offer, offer.sets, own), offer.sets + 1, own);
            String nextSig = sig(m, next, now);
            assertFalse(Mint.answers(stored, nextSig), BIAS_NAMES[bi]);
            assertNull(SavedMint.carried(stored, nextSig), BIAS_NAMES[bi]);
            assertFalse(SavedMint.planMayRewrite(stored, nextSig),
                BIAS_NAMES[bi] + ": the person's pressure rewritten by a new prescription");
            // A taper step keeps it: that rewrite is the person's pressure under the cut.
            Deload.arm(m, now - DAY);
            assertTrue(SavedMint.planMayRewrite(stored, sig(m, offer, now)),
                BIAS_NAMES[bi] + ": a taper step");
        }
        // Where the pressure was the plan's own, the plan writes its change as before.
        long t = now;
        Model m = model(NONE, t);
        program(m, Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_FIXED, Model.Program.PRESS_FIRM);
        Mint.Rx offer = girthL2(m, t);
        Mint.Rx next = Mint.adjusted(offer, offer.sets + 1, offer.pressureKpa);
        assertTrue(SavedMint.planMayRewrite(sig(m, offer, t), sig(m, next, t)), "unadjusted");
        String setsOnly = adjustedSig(m, offer, offer.sets - 1, RxBuild.adjustStartKpa(m, offer), t);
        assertFalse(SavedMint.ownKpa(setsOnly));
        assertTrue(SavedMint.planMayRewrite(setsOnly, sig(m, next, t)), "sets only");
        assertTrue(SavedMint.planMayRewrite("", sig(m, next, t)), "a cleared signature");
    }

    @Test
    void theNoticeStatesTheFiguresEachSideRunsAt() {
        long now = System.currentTimeMillis();
        Model m = model(NONE, now);
        program(m, Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_FIXED, Model.Program.PRESS_FIRM);
        Mint.Rx offer = girthL2(m, now);
        int firm = RxBuild.commandedKpa(m, offer);
        assertTrue(firm > offer.pressureKpa, "the position is one Firm raises");
        Mint.Rx was = Mint.adjusted(offer, offer.sets, offer.pressureKpa - 1);   // theirs, 1 down
        Mint.Rx now1 = Mint.adjusted(was, was.sets + 1, was.pressureKpa);          // plan's, biased
        // In plain prescriptions the pressure did not move: the finding's "6 -> 7 sets".
        assertEquals(was.sets + " → " + now1.sets + " sets", Say.planChange(was, now1));
        String said = Say.planChange(RxBuild.asCommanded(m, was, true),
                                     RxBuild.asCommanded(m, now1, false));
        assertFalse(said.equals(was.sets + " → " + now1.sets + " sets"),
            "the notice hides the pressure the bias put back: " + said);
        assertTrue(said.contains("deeper"), said);
        // A lighter, shorter pair is never "more" and "deeper": it says both figures.
        String down = Say.planChange(RxBuild.asCommanded(m, now1, false),
                                     RxBuild.asCommanded(m, was, true));
        assertTrue(down.contains(Model.Fmt.p(RxBuild.commandedKpa(m, was, true))), down);
        assertFalse(down.contains("More"), down);
        assertTrue(down.contains("→"), down);
    }

    /* --------------------------------------------- M2 - a rebuild never switches shape */

    private static Model rack(Model m) {
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
        m.trainerLength.loadLb = 4.0;
        m.trainerLength.strainSets = 3;
        return m;
    }

    @Test
    void aLighterDayRebuildThatWouldSwitchTheLengthSessionIsRecognised() {
        long now = System.currentTimeMillis();
        Model m = model(TAPER1, now);
        Mint.Rx lrx = rx(m, Plan.TRACK_LENGTH, Plan.L1, 1, 6.0, 0, now);
        Model.Routine plain = m.routine(RxBuild.routineFromRx(m, lrx));
        assertFalse(Say.isTraction(plain));
        assertFalse(SavedMint.switchesShape(m, plain, now), "no rack: the same shape");
        rack(m);
        assertTrue(SavedMint.switchesShape(m, plain, now),
            "a plain routine on a rack that now pulls would be rebuilt as traction");
        Model.Routine pulls = m.routine(RxBuild.routineFromRx(m, lrx));
        assertTrue(Say.isTraction(pulls));
        assertFalse(SavedMint.switchesShape(m, pulls, now), "traction on a rack that pulls");
        Model.Routine girth = m.routine(RxBuild.routineFromRx(m, girthL2(m, now)));
        assertFalse(SavedMint.switchesShape(m, girth, now), "girth never switches");
    }

    /* ------------------------------------------- M3 - older plan routines on a lighter day */

    @Test
    void anOlderPlanRoutineIsWeighedOnALighterDay() {
        long now = System.currentTimeMillis();
        for (int red = CUT; red <= TAPER2; red++)
            for (int bi = 0; bi < BIASES.length; bi++) {
                Model m = model(NONE, now);
                program(m, Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_FIXED, BIASES[bi]);
                Mint.Rx rx = girthL2(m, now);
                Model.Routine old = m.routine(RxBuild.routineFromRx(m, rx));
                Model.Routine src = m.routine(RxBuild.routineFromRx(m, rx));
                Model.Routine rest = m.routine(RxBuild.remainder(m, src, rx, null, 3, true));
                String what = RED_NAMES[red] + " / " + BIAS_NAMES[bi];
                assertFalse(SavedMint.heavierThanToday(m, old, rx, 0, 0, now), what + ": full day");
                reduce(m, red, now);
                assertTrue(SavedMint.heavierThanToday(m, old, rx, 0, 0, now),
                    what + ": an older full-pressure mint passes a lighter day");
                assertTrue(SavedMint.heavierThanToday(m, rest, rx, 0, 0, now),
                    what + ": 'the rest' from a full day passes a lighter day");
                // What today builds is never refused.
                Model.Routine today = m.routine(RxBuild.routineFromRx(m, rx));
                assertFalse(SavedMint.heavierThanToday(m, today, rx, 0, 0, now), what);
            }
    }

    /* -------------------------------------------- the card counts what the routine runs */

    @Test
    void theCardsCountIsTheRoutinesNamesCount() {
        long now = System.currentTimeMillis();
        int differs = 0, n = 0;
        for (int bi = 0; bi < BIASES.length; bi++)
            for (int si = 0; si < SHAPES.length; si++)
                for (int red = NONE; red <= TAPER2; red++)
                    for (int hy = 0; hy < 2; hy++) {
                        Model m = model(red, now);
                        m.trainerGirthHybrid = hy == 1;
                        program(m, Plan.TRACK_GIRTH_INTERVAL, SHAPES[si], BIASES[bi]);
                        Mint.Rx rx = girthL3(m, now);
                        int count = RxBuild.holdsRun(m, rx, RxBuild.Day.today(m));
                        int routines = m.routines.size();
                        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                        assertEquals(routines, m.routines.size() - 1, "holdsRun built into the model");
                        assertEquals(holds(m, r), count, r.name);
                        assertTrue(r.name.contains(" · " + count + "×"), r.name + " vs " + count);
                        if (count != RxBuild.runsAs(m, rx).sets) differs++;
                        n++;
                    }
        assertTrue(differs > 5, "no make-up in the grid: " + differs + " of " + n);
        // A traction session is named for its load: no count.
        Model t = rack(model(NONE, now));
        assertEquals(-1, RxBuild.holdsRun(t, rx(t, Plan.TRACK_LENGTH, Plan.L1, 1, 6.0, 0, now),
            RxBuild.Day.today(t)));
    }

    /* ------------------------------------------------- L2 - setup on an enrolled phone */

    @Test
    void theSetupsTrainerChoiceOnAnEnrolledPhoneJustOpensTheTrainer() {
        assertEquals(FirstRun.FINISH_TRAINER_OPEN, FirstRun.finishAction("trainer", true, true));
        assertEquals(FirstRun.FINISH_TRAINER_OPEN, FirstRun.finishAction("trainer", false, true));
        assertEquals(FirstRun.FINISH_TRAINER_ONBOARD, FirstRun.finishAction("trainer", true, false));
        // The Starter needs the Starter routine; without it, the trainer as above.
        assertEquals(FirstRun.FINISH_STARTER, FirstRun.finishAction("starter", true, true));
        assertEquals(FirstRun.FINISH_TRAINER_OPEN, FirstRun.finishAction("starter", false, true));
        assertEquals(FirstRun.FINISH_TRAINER_ONBOARD,
            FirstRun.finishAction("starter", false, false));
        assertEquals(FirstRun.FINISH_LIBRARY, FirstRun.finishAction("own", true, true));
        assertEquals(FirstRun.FINISH_LIBRARY, FirstRun.finishAction("own", false, false));
        // Labelled as what it does.
        assertEquals("Open the trainer", SetupText.FIRST_TRAINER_ENROLLED);
        assertEquals("Open the trainer ›", SetupText.finalButton("trainer", true));
        assertEquals("Set up the trainer ›", SetupText.finalButton("trainer", false));
        assertTrue(SetupText.firstResponse("trainer", 3, true).startsWith("Opens the Trainer"));
        assertTrue(SetupText.firstResponse("trainer", 3, false).startsWith("Six quick questions"));
        assertEquals(SetupText.finalButton("own"), SetupText.finalButton("own", true));
    }
}
