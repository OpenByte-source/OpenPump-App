package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * AUDIT D1 + D7 (the owner's decisions, 0.10): IS A SAVED PLAN ROUTINE STILL THE PLAN'S?
 *
 * A saved Ramped, traditional or ascending routine read as EDITED from the moment it was
 * saved, because "edited" was a count of holds at the stored pressure that those shapes could
 * never satisfy - so no reduction and no plan change ever reached them, and switching the
 * Program away from Ramped did nothing. The answer is now the builder's: a fresh build of the
 * prescription the routine's signature records, in the shape and on the day it was minted
 * (SavedMint#edited).
 *
 * Pinned here, through the app's own chain (Mint.prescribe -> RxBuild.routineFromRx ->
 * Model.plan):
 *   1. a fresh build never reads as edited - every track position, every work shape, every
 *      bias, every reduction, the warm-up variants, both halves of a split, traction;
 *   2. a real edit does - a hold, a pressure, a set added, a set taken away;
 *   3. moving the Program or a Shape setting after the save is not an edit, and the rewrite
 *      then builds the new shape (D7);
 *   4. a taper-day rewrite lands at the reduced pressure for every shape;
 *   5. the run-start net: a saved routine heavier than today's build is caught, and not after
 *      the rewrite.
 */
class SavedMintTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final int[] SHAPES = { Model.Program.WORK_FIXED,
        Model.Program.WORK_RAMP_IN_SET, Model.Program.WORK_ASCENDING,
        Model.Program.WORK_PYRAMID };
    private static final String[] SHAPE_NAMES = { "fixed", "ramp", "ascending", "pyramid" };
    private static final int[] BIASES = { Model.Program.PRESS_GENTLE,
        Model.Program.PRESS_STANDARD, Model.Program.PRESS_FIRM };
    private static final String[] BIAS_NAMES = { "gentle", "standard", "firm" };
    private static final int NONE = 0, CUT = 1, TAPER1 = 2, TAPER2 = 3;
    private static final String[] RED_NAMES = { "none", "cylinder cut", "taper 1", "taper 2" };

    /** A track position: track, level, week, working pressure (hg), carried sets. */
    private static final class Pos {
        final int track, level, week, carried; final double hg; final String name;
        Pos(String name, int track, int level, int week, double hg, int carried) {
            this.name = name; this.track = track; this.level = level; this.week = week;
            this.hg = hg; this.carried = carried;
        }
    }

    private static final Pos[] POSITIONS = {
        new Pos("girth interval L1 w1", Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 1, 6.0, 0),
        new Pos("girth interval L1 w10", Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 10, 7.0, 0),
        new Pos("girth interval L2 w6", Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 6, 8.0, 0),
        new Pos("girth interval L3 carried 15", Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 1, 9.0, 15),
        new Pos("girth traditional L1", Plan.TRACK_GIRTH_TRADITIONAL, Plan.L1, 1, 6.0, 0),
        new Pos("girth traditional L3", Plan.TRACK_GIRTH_TRADITIONAL, Plan.L3, 1, 9.0, 0),
        new Pos("length L1 (no rack)", Plan.TRACK_LENGTH, Plan.L1, 1, 6.0, 0),
        new Pos("length L2 (no rack)", Plan.TRACK_LENGTH, Plan.L2, 1, 8.0, 0),
        new Pos("feeder L3", Plan.TRACK_FEEDER, Plan.L3, 0, 9.0, 0),
    };

    /* ------------------------------------------------------------------------- helpers */

    private static Model model(int reduction, long now) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerMonthsPumping = 4;
        if (reduction == CUT) m.oversizeAccepted = true;
        if (reduction == TAPER1 || reduction == TAPER2) {
            Deload.arm(m, now - DAY);
            if (reduction == TAPER2) m.returnStep = 1;
        }
        return m;
    }

    private static Mint.Rx rxFor(Model m, Pos p, long now) {
        return Mint.prescribe(p.track, p.level, p.week, p.hg * Plan.HG,
            TrainerTab.monthIndexNow(m, now), m.ceilKpa, p.carried, 0, null);
    }

    private static String sig(Model m, Mint.Rx rx, long now) {
        return Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
    }

    private static void shape(Model m, int track, int work, int bias) {
        Model.Program p = m.programFor(track);
        if (p == null) return;                       // the feeder has none
        p.work = work;
        p.pressure = bias;
    }

    private static Model.Routine build(Model m, Mint.Rx rx) {
        return m.routine(RxBuild.routineFromRx(m, rx));
    }

    private static boolean edited(Model m, Model.Routine r, String sig, long now) {
        return SavedMint.edited(m, r, sig, 0, 0, now, null, now);
    }

    /** The work sets of a routine: in a non-rest, non-warm stage. */
    private static List<Model.Set> workSets(Model m, Model.Routine r) {
        List<Model.Set> out = new ArrayList<Model.Set>();
        for (Model.Stage st : r.stages) {
            if (st.rest || st.colour == Model.STAGE_WARM || st.traction) continue;
            for (String id : st.setIds) { Model.Set s = m.set(id); if (s != null) out.add(s); }
        }
        return out;
    }

    /* ------------------------------------------------ 1. a fresh build is the plan's own */

    @Test
    void aFreshBuildNeverReadsAsEditedAcrossTheGrid() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Pos p : POSITIONS)
            for (int si = 0; si < SHAPES.length; si++)
                for (int bi = 0; bi < BIASES.length; bi++)
                    for (int red = NONE; red <= TAPER2; red++) {
                        Model m = model(red, now);
                        if (p.track == Plan.TRACK_GIRTH_TRADITIONAL)
                            m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
                        shape(m, p.track, SHAPES[si], BIASES[bi]);
                        Mint.Rx rx = rxFor(m, p, now);
                        Model.Routine r = build(m, rx);
                        String what = p.name + " / " + SHAPE_NAMES[si] + " / "
                            + BIAS_NAMES[bi] + " / " + RED_NAMES[red];
                        assertNotNull(r, what);
                        assertFalse(edited(m, r, sig(m, rx, now), now),
                            what + ": a routine the plan just built read as edited");
                        n++;
                    }
        assertEquals(POSITIONS.length * 4 * 3 * 4, n);
    }

    /** The owner's report's three shapes, by name: the ones the count could never satisfy. */
    @Test
    void rampTraditionalAndAscendingAreThePlansOwn() {
        long now = System.currentTimeMillis();
        int[][] cases = {
            { Plan.TRACK_LENGTH, Model.Program.WORK_RAMP_IN_SET },
            { Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_RAMP_IN_SET },
            { Plan.TRACK_GIRTH_TRADITIONAL, Model.Program.WORK_FIXED },
            { Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_ASCENDING },
        };
        for (int[] c : cases) {
            Model m = model(NONE, now);
            shape(m, c[0], c[1], Model.Program.PRESS_STANDARD);
            Pos p = new Pos("case", c[0], c[0] == Plan.TRACK_GIRTH_INTERVAL ? Plan.L2 : Plan.L1,
                            1, 8.0, 0);
            Mint.Rx rx = rxFor(m, p, now);
            Model.Routine r = build(m, rx);
            assertFalse(edited(m, r, sig(m, rx, now), now),
                "track " + c[0] + " shape " + c[1] + " read as edited at birth");
        }
    }

    @Test
    void warmUpVariantsAreThePlansOwn() {
        long now = System.currentTimeMillis();
        Pos p = POSITIONS[2];
        int n = 0;
        for (int variant = 0; variant < 11; variant++)
            for (int si = 0; si < 2; si++)
                for (int red = NONE; red <= TAPER1; red += 2) {
                    Model m = model(red, now);
                    shape(m, p.track, SHAPES[si], Model.Program.PRESS_STANDARD);
                    switch (variant) {
                        case 1: m.rxWarmRamp = true; break;
                        case 2: m.rxWarmRamp = true; m.rxWarmSteps = 8; break;
                        case 3: m.programGirth.warm = Model.Program.WARM_RAMP; break;
                        case 4: m.programGirth.warm = Model.Program.WARM_SHORT; break;
                        case 5: m.programGirth.warm = Model.Program.WARM_NONE; break;
                        case 6: m.rxWarmMin = 0; break;
                        case 7: m.rxWarmMin = 10; break;
                        case 8: m.rxEaseHg = 0; break;
                        case 9: m.rxPrimeKpa = 20.4; break;
                        case 10: m.rxRetention = true; m.rxRetentionMin = 10; break;
                        default: break;
                    }
                    Mint.Rx rx = rxFor(m, p, now);
                    Model.Routine r = build(m, rx);
                    assertFalse(edited(m, r, sig(m, rx, now), now), "warm-up variant "
                        + variant + " / " + SHAPE_NAMES[si] + " / " + RED_NAMES[red]);
                    n++;
                }
        assertEquals(44, n);
    }

    @Test
    void bothHalvesOfASplitAreThePlansOwn() {
        long now = System.currentTimeMillis();
        for (int si = 0; si < SHAPES.length; si++)
            for (int warmBoth = 0; warmBoth < 2; warmBoth++)
                for (int red = NONE; red <= TAPER1; red += 2) {
                    Model m = model(red, now);
                    m.rxSplit = true;
                    m.rxSplitWarmBoth = warmBoth == 1;
                    shape(m, Plan.TRACK_GIRTH_INTERVAL, SHAPES[si], Model.Program.PRESS_STANDARD);
                    Mint.Rx rx = rxFor(m, POSITIONS[2], now);
                    String s = sig(m, rx, now);
                    Model.Routine one = m.routine(RxBuild.routineFromRx(m, rx, 1, 2));
                    Model.Routine two = m.routine(RxBuild.routineFromRx(m, rx, 2, 2));
                    String what = SHAPE_NAMES[si] + " warmBoth=" + warmBoth + " "
                        + RED_NAMES[red];
                    assertFalse(SavedMint.edited(m, one, s, 1, 2, now, null, now), "part 1 " + what);
                    assertFalse(SavedMint.edited(m, two, s, 2, 2, now, null, now), "part 2 " + what);
                    // ...and a half is not the whole: asked as the wrong part, it is not the plan's.
                    assertTrue(SavedMint.edited(m, one, s, 0, 0, now, null, now),
                        "part 1 read as the whole session " + what);
                }
    }

    /* ----------------------------------------------------- traction: compared by the coda */

    private static Model rack(int reduction, long now) {
        Model m = model(reduction, now);
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
    void aTractionSessionIsThePlansOwnAndItsCodaEditIsNot() {
        long now = System.currentTimeMillis();
        int[] coda = { Model.Program.WORK_FIXED, Model.Program.WORK_RAMP_IN_SET,
                       Model.Program.WORK_ASCENDING };
        for (int c : coda)
            for (int red = NONE; red <= TAPER1; red += 2) {
                Model m = rack(red, now);
                shape(m, Plan.TRACK_LENGTH, c, Model.Program.PRESS_STANDARD);
                Mint.Rx rx = rxFor(m, POSITIONS[6], now);
                Model.Routine r = build(m, rx);
                assertTrue(Say.isTraction(r), "the rack pulls, so the session is traction");
                String s = sig(m, rx, now);
                assertFalse(edited(m, r, s, now), "traction coda " + c + " " + RED_NAMES[red]);
                // The load is the ladder's: a different load is not the person's edit.
                m.trainerLength.loadLb = 3.0;
                assertFalse(edited(m, r, s, now), "a load change read as an edit");
                // The coda is the prescription's: a shorter hold there is.
                List<Model.Set> ws = workSets(m, r);
                assertFalse(ws.isEmpty(), "the coda was found");
                ws.get(ws.size() - 1).uh -= 10;
                if (ws.get(ws.size() - 1).ramp) ws.get(ws.size() - 1).uh2 -= 10;
                assertTrue(edited(m, r, s, now), "an edited coda read as the plan's");
            }
    }

    /**
     * REVIEW M5 (0.10): THE DRIFT CHECK SEES THE WHOLE SESSION. It skipped a traction session's
     * pulls, the warm-up that primes for them and every rest - so a strain pull the person had
     * lowered read as the plan's, and the next plan step wrote it back up. Each of those edits
     * now reads as edited; the ladder moving the load, and the rest setting moving, do not.
     */
    @Test
    void theDriftCheckSeesThePullsTheirWarmUpAndTheRests() {
        long now = System.currentTimeMillis();
        String[] edits = { "a strain pull lowered", "a fatigue pull lowered",
            "the traction warm-up changed", "the release lengthened", "the changeover shortened" };
        for (int red = NONE; red <= TAPER1; red += 2)
            for (int edit = 0; edit < edits.length; edit++) {
                Model m = rack(red, now);
                Mint.Rx rx = rxFor(m, POSITIONS[6], now);
                Model.Routine r = build(m, rx);
                assertTrue(Say.isTraction(r));
                String s = sig(m, rx, now);
                String what = RED_NAMES[red] + ": " + edits[edit];
                assertFalse(edited(m, r, s, now), what + ": the fresh build read as edited");
                boolean done = false;
                for (Model.Stage st : r.stages) {
                    if (edit == 0 && st.traction && !st.fatigueBlock) {
                        m.set(st.setIds.get(0)).up -= 1; done = true; break;
                    }
                    if (edit == 1 && st.traction && st.fatigueBlock) {
                        m.set(st.setIds.get(0)).up -= 1; done = true; break;
                    }
                    if (edit == 2 && st.colour == Model.STAGE_WARM) {
                        m.set(st.setIds.get(0)).uh += 10; done = true; break;
                    }
                    if (edit == 3 && st.rest && st.manual && !st.awaitAck) {
                        st.restSec += 60; done = true; break;
                    }
                    if (edit == 4 && st.rest && st.awaitAck) {
                        st.restSec -= 30; done = true; break;
                    }
                }
                assertTrue(done, what + ": nothing to edit");
                assertTrue(edited(m, r, s, now), what + ": read as the plan's own");
            }
        // The ladder moves the load after the save: still the plan's, and so is a new girth.
        Model m = rack(NONE, now);
        Mint.Rx rx = rxFor(m, POSITIONS[6], now);
        Model.Routine r = build(m, rx);
        String s = sig(m, rx, now);
        m.trainerLength.loadLb = 2.5;
        m.trainerLength.strainSets = 5;
        assertFalse(edited(m, r, s, now), "a load the ladder moved read as the person's edit");

        // THE RESTS OF A GIRTH ROUTINE: blocks with rests between (L2), the traditional track's
        // rest between holds, and the rest after an L3 fatigue block.
        Pos[] pos = { POSITIONS[2], POSITIONS[5], POSITIONS[3] };
        for (Pos p : pos)
            for (int si = 0; si < SHAPES.length; si++) {
                Model g = model(NONE, now);
                if (p.track == Plan.TRACK_GIRTH_TRADITIONAL)
                    g.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
                shape(g, p.track, SHAPES[si], Model.Program.PRESS_STANDARD);
                Mint.Rx grx = rxFor(g, p, now);
                Model.Routine gr = build(g, grx);
                String gs = sig(g, grx, now);
                String what = p.name + " / " + SHAPE_NAMES[si];
                List<Model.Stage> rests = new ArrayList<Model.Stage>();
                for (Model.Stage st : gr.stages) if (st.rest && !st.manual) rests.add(st);
                assertFalse(rests.isEmpty(), what + ": no rest to edit");
                // The rest SETTING moving after the save is not an edit to this routine.
                g.rxRestSec += 30;
                g.rxRestSecTrad += 30;
                assertFalse(edited(g, gr, gs, now), what + ": the rest setting read as an edit");
                // A rest the person changed is: to a length the setting cannot give...
                rests.get(0).restSec += 7;
                assertTrue(edited(g, gr, gs, now), what + ": a rest edit read as the plan's");
                rests.get(0).restSec -= 7;
                assertFalse(edited(g, gr, gs, now), what + ": put back");
                // ...or one rest of several, to any other length.
                if (rests.size() > 1) {
                    rests.get(rests.size() - 1).restSec += 30;
                    assertTrue(edited(g, gr, gs, now), what + ": one rest of several edited");
                }
            }
    }

    /**
     * AUDIT D5, D6, D8, D9, D11 (0.10): THE NEW LAYOUTS ARE THE PLAN'S OWN, AND EDITING THEM
     * IS AN EDIT. A ramp longer than the table (its climb and its top step), the climbing
     * warm-up that now ends under the work, the guidance's hybrid at L3 and L4, a pyramid of
     * two, a Gentle "ramp" that is a fixed set, and a doubled traction coda - every shape,
     * every bias, every reduction: a fresh build never reads as edited, and a changed hold in
     * its last work set does.
     */
    @Test
    void theNewLayoutsAreThePlansOwnAndAnEditToThemIsNot() {
        long now = System.currentTimeMillis();
        Pos[] pos = {
            new Pos("girth interval L1 w14", Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 14, 8.0, 0),
            new Pos("girth interval L4", Plan.TRACK_GIRTH_INTERVAL, Plan.L4, 1, 10.0, 0),
            POSITIONS[3], POSITIONS[5], POSITIONS[4], POSITIONS[6] };
        int n = 0;
        for (Pos p : pos)
            for (int variant = 0; variant < 3; variant++)
                for (int si = 0; si < SHAPES.length; si++)
                    for (int bi = 0; bi < BIASES.length; bi++)
                        for (int red = NONE; red <= TAPER2; red++) {
                            // 0 plain, 1 the climbing warm-up and blocks of ten, 2 hybrid (L3+)
                            if (variant == 2 && p.level < Plan.L3) continue;
                            Model m = model(red, now);
                            if (p.track == Plan.TRACK_GIRTH_TRADITIONAL)
                                m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
                            shape(m, p.track, SHAPES[si], BIASES[bi]);
                            if (variant == 1) {
                                m.programFor(p.track).warm = Model.Program.WARM_RAMP;
                                m.rxSetsPerBlock = 10;
                            }
                            if (variant == 2) m.trainerGirthHybrid = true;
                            Mint.Rx rx = rxFor(m, p, now);
                            Model.Routine r = build(m, rx);
                            String s = sig(m, rx, now);
                            String what = p.name + " / variant " + variant + " / "
                                + SHAPE_NAMES[si] + " / " + BIAS_NAMES[bi] + " / " + RED_NAMES[red];
                            assertFalse(edited(m, r, s, now), what + ": read as edited at birth");
                            List<Model.Set> ws = workSets(m, r);
                            Model.Set last = ws.get(ws.size() - 1);
                            last.uh -= 10;
                            if (last.ramp) last.uh2 -= 10;
                            assertTrue(edited(m, r, s, now), what + ": an edited hold read as the plan's");
                            n++;
                        }
        // A doubled coda (a girth-focus block): its climb and its top step.
        for (int si = 0; si < SHAPES.length; si++)
            for (int bi = 0; bi < BIASES.length; bi++)
                for (int red = NONE; red <= TAPER2; red++) {
                    Model m = rack(red, now);
                    m.trainerLength.focusBlockUntilMs = now + 7 * DAY;
                    shape(m, Plan.TRACK_LENGTH, SHAPES[si], BIASES[bi]);
                    Mint.Rx rx = rxFor(m, POSITIONS[6], now);
                    Model.Routine r = build(m, rx);
                    String s = sig(m, rx, now);
                    String what = "girth-focus coda / " + SHAPE_NAMES[si] + " / "
                        + BIAS_NAMES[bi] + " / " + RED_NAMES[red];
                    assertFalse(edited(m, r, s, now), what + ": read as edited at birth");
                    List<Model.Set> ws = workSets(m, r);
                    Model.Set last = ws.get(ws.size() - 1);
                    last.uh -= 10;
                    if (last.ramp) last.uh2 -= 10;
                    assertTrue(edited(m, r, s, now), what + ": an edited coda read as the plan's");
                    n++;
                }
        assertTrue(n > 700, "the grid is smaller than it claims (" + n + ")");
    }

    /* ------------------------------------------------------------- 2. a real edit is not */

    @Test
    void aRealEditReadsAsEdited() {
        long now = System.currentTimeMillis();
        Pos[] pos = { POSITIONS[2], POSITIONS[4], POSITIONS[6], POSITIONS[3] };
        int n = 0;
        for (Pos p : pos)
            for (int si = 0; si < SHAPES.length; si++)
                for (int edit = 0; edit < 5; edit++) {
                    Model m = model(NONE, now);
                    if (p.track == Plan.TRACK_GIRTH_TRADITIONAL)
                        m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
                    shape(m, p.track, SHAPES[si], Model.Program.PRESS_STANDARD);
                    Mint.Rx rx = rxFor(m, p, now);
                    Model.Routine r = build(m, rx);
                    String s = sig(m, rx, now);
                    List<Model.Set> ws = workSets(m, r);
                    Model.Set last = ws.get(ws.size() - 1);
                    Model.Set first = ws.get(0);
                    String what;
                    switch (edit) {
                        case 0:
                            what = "hold changed";
                            first.uh -= 10;
                            if (first.ramp) first.uh2 -= 10;
                            break;
                        case 1:
                            what = "pressure changed";
                            if (last.ramp) last.up2 -= 1; else last.up -= 1;
                            break;
                        case 2: {
                            what = "a set added";
                            Model.Set extra = Model.Set.fixed(m.newSetId(), "Mine", last.up,
                                last.lo, last.uh, last.lh, last.sp, last.uh + last.lh);
                            m.sets.add(extra);
                            for (Model.Stage st : r.stages)
                                if (st.setIds.contains(last.id)) { st.setIds.add(extra.id); break; }
                            break;
                        }
                        case 3: {
                            what = "a set taken away";
                            Model.Stage owner = null;
                            for (Model.Stage st : r.stages)
                                if (st.setIds.contains(last.id)) owner = st;
                            owner.setIds.remove(last.id);
                            if (owner.setIds.isEmpty()) r.stages.remove(owner);
                            break;
                        }
                        default:
                            what = "the drop time changed";
                            first.lh += 2;
                            if (first.ramp) first.lh2 += 2;
                            break;
                    }
                    assertTrue(edited(m, r, s, now), p.name + " / " + SHAPE_NAMES[si] + ": "
                        + what + " read as the plan's own");
                    n++;
                }
        assertEquals(4 * 4 * 5, n);
    }

    @Test
    void anAdjustedSaveIsThePersonsAndAClearedSignatureIsAskedOfToday() {
        long now = System.currentTimeMillis();
        Model m = model(NONE, now);
        Mint.Rx rx = rxFor(m, POSITIONS[2], now);
        String s = sig(m, rx, now);
        Mint.Rx fewer = Mint.remainderOf(rx, rx.sets - 2);
        Model.Routine adjusted = build(m, fewer);
        assertTrue(edited(m, adjusted, s, now),
            "two sets fewer than the signature records is the person's choice");
        // A cleared signature: the plan's only if it is what the plan would build today.
        Model.Routine today = build(m, rx);
        assertFalse(SavedMint.edited(m, today, "", 0, 0, 0L, rx, now),
            "today's own build, with its signature cleared, read as edited");
        assertTrue(SavedMint.edited(m, adjusted, "", 0, 0, 0L, rx, now),
            "a routine that is not today's build, signature cleared (an Undo), read as the plan's");
        assertTrue(SavedMint.edited(m, today, "", 0, 0, 0L, null, now),
            "with nothing to compare against it is not the plan's to overwrite");
        // ...and one built before a cylinder cut was accepted is still the plan's afterwards.
        m.oversizeAccepted = true;
        assertFalse(SavedMint.edited(m, today, "", 0, 0, 0L, rx, now),
            "a routine built before the cut was accepted read as edited");
    }

    /* --------------------------------------- 3. a setting moved after the save (D7) */

    @Test
    void movingTheProgramAfterTheSaveIsNotAnEditAndTheRewriteBuildsTheNewShape() {
        long now = System.currentTimeMillis();
        Pos[] pos = { POSITIONS[2], POSITIONS[6], POSITIONS[4] };
        for (Pos p : pos)
            for (int a = 0; a < SHAPES.length; a++)
                for (int b = 0; b < SHAPES.length; b++) {
                    if (a == b) continue;
                    Model m = model(NONE, now);
                    if (p.track == Plan.TRACK_GIRTH_TRADITIONAL)
                        m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
                    shape(m, p.track, SHAPES[a], Model.Program.PRESS_FIRM);
                    m.rxWarmRamp = true;
                    m.rxWarmSteps = 6;
                    Mint.Rx rx = rxFor(m, p, now);
                    Model.Routine r = build(m, rx);
                    String was = sig(m, rx, now);
                    // The person moves the Program and the Shape screen afterwards.
                    shape(m, p.track, SHAPES[b], Model.Program.PRESS_STANDARD);
                    m.rxWarmRamp = false;
                    m.rxWarmSteps = 4;
                    String is = sig(m, rx, now);
                    String what = p.name + " " + SHAPE_NAMES[a] + " -> " + SHAPE_NAMES[b];
                    assertFalse(is.equals(was), what + ": the signature moved");
                    assertFalse(edited(m, r, was, now), what + ": read as the person's edit");
                    assertTrue(SavedMint.rewrite(m, r, rx, 0, 0), what);
                    Model.Routine fresh = build(m, rx);
                    assertEquals(SavedMint.workPrint(m, fresh), SavedMint.workPrint(m, r),
                        what + ": the rewrite is not the new shape");
                    assertFalse(edited(m, r, is, now), what + ": its own rewrite read as edited");
                }
    }

    /* ------------------------------------ 4. a taper-day rewrite lands at the reduced figure */

    @Test
    void aReducedDayRewriteLandsAtTheReducedPressureForEveryShape() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Pos p : POSITIONS)
            for (int si = 0; si < SHAPES.length; si++)
                for (int bi = 0; bi < BIASES.length; bi++)
                    for (int red = CUT; red <= TAPER2; red++) {
                        // Saved on an ordinary day...
                        Model m = model(NONE, now);
                        if (p.track == Plan.TRACK_GIRTH_TRADITIONAL)
                            m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
                        shape(m, p.track, SHAPES[si], BIASES[bi]);
                        Mint.Rx rx = rxFor(m, p, now);
                        Model.Routine r = build(m, rx);
                        String was = sig(m, rx, now);
                        int fullPeak = SavedMint.governedPeakKpa(m, r);
                        // ...and the day turns lighter.
                        if (red == CUT) { m.oversizeAccepted = true; }
                        else { Deload.arm(m, now - DAY); if (red == TAPER2) m.returnStep = 1; }
                        double cut = m.reductionKpa(now);
                        assertTrue(cut > 0.0, RED_NAMES[red] + " cuts nothing");
                        String what = p.name + " / " + SHAPE_NAMES[si] + " / " + BIAS_NAMES[bi]
                            + " / " + RED_NAMES[red];
                        String is = sig(m, rx, now);
                        if (red != CUT) assertFalse(is.equals(was), what + ": the step moved nothing");
                        // It is not edited, so the plan reaches it. (Accepting a cylinder cut
                        // CLEARS the signature - SessionActivity#remintAfterReductionChange - so
                        // that day is asked the cleared-signature way, as the app asks it.)
                        boolean ed = red == CUT
                            ? SavedMint.edited(m, r, "", 0, 0, 0L, rx, now)
                            : edited(m, r, was, now);
                        assertFalse(ed, what + ": read as edited");
                        assertTrue(SavedMint.rewrite(m, r, rx, 0, 0), what);
                        // ...as today's own build, and not the person's afterwards.
                        assertEquals(SavedMint.workPrint(m, build(m, rx)), SavedMint.workPrint(m, r),
                            what + ": the rewrite is not today's build");
                        assertFalse(edited(m, r, is, now), what + ": the rewrite read as edited");
                        // A FEEDER IS NOT CUT BY THE BUILDER ON A TAPER DAY: its prescription is
                        // already its share of the day's reduced main pressure (RxBuild), which
                        // this grid - one prescription for both days - does not model.
                        boolean feederTaper = p.track == Plan.TRACK_FEEDER && red != CUT;
                        if (!feederTaper)
                            assertEquals(0.0, r.netTargetMin, 1e-9,
                                what + ": a reduced session still asks for net");
                        int peak = SavedMint.governedPeakKpa(m, r);
                        // D2: the bias lands on the FULL prescription and the day's cut comes
                        // off whatever it chose, so every bias is lighter on a lighter day -
                        // Firm at the band's top less the cut, never the band's top.
                        if (!feederTaper) {
                            int reduced = BIASES[bi] == Model.Program.PRESS_FIRM
                                ? Mint.reducedKpa(Mint.biasKpa(rx.pressureKpa, BIASES[bi],
                                      rx.level, TrainerTab.monthIndexNow(m, now), m.ceilKpa), cut)
                                : Mint.reducedKpa(rx.pressureKpa, cut);
                            assertTrue(peak <= reduced, what + ": peak " + peak
                                + " above the reduced figure " + reduced);
                            assertTrue(peak < fullPeak || fullPeak <= reduced,
                                what + ": the rewrite is no lighter (" + fullPeak + " -> " + peak + ")");
                        }
                        n++;
                    }
        assertEquals(POSITIONS.length * 4 * 3 * 3, n);
    }

    /* ------------------------------------------------------------- 5. the run-start net */

    @Test
    void theRunStartNetCatchesARoutineHeavierThanTodaysBuild() {
        long now = System.currentTimeMillis();
        Pos[] pos = { POSITIONS[1], POSITIONS[2], POSITIONS[4], POSITIONS[6], POSITIONS[8] };
        // Firm too, since D2: a Firm routine saved at the band's top is heavier than a taper
        // day's Firm build, which is that top less the day's cut.
        int[] bias = { Model.Program.PRESS_GENTLE, Model.Program.PRESS_STANDARD,
                       Model.Program.PRESS_FIRM };
        for (Pos p : pos)
            for (int si = 0; si < SHAPES.length; si++)
                for (int b : bias) {
                    Model m = model(NONE, now);
                    if (p.track == Plan.TRACK_GIRTH_TRADITIONAL)
                        m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
                    shape(m, p.track, SHAPES[si], b);
                    Mint.Rx rx = rxFor(m, p, now);
                    Model.Routine r = build(m, rx);
                    String what = p.name + " / " + SHAPE_NAMES[si] + " / bias " + b;
                    assertFalse(SavedMint.heavierThanToday(m, r, rx, 0, 0, now),
                        what + ": on an ordinary day today's build is the saved one");
                    // The feeder's taper-day prescription is its share of the reduced main
                    // pressure, not this one - so its reduced day here is the cylinder's.
                    if (p.track == Plan.TRACK_FEEDER) m.oversizeAccepted = true;
                    else Deload.arm(m, now - DAY);
                    assertTrue(SavedMint.heavierThanToday(m, r, rx, 0, 0, now),
                        what + ": a full-pressure routine on a taper day was not caught");
                    // An edited one is caught all the same - the net does not ask whose it is.
                    List<Model.Set> ws = workSets(m, r);
                    ws.get(0).uh -= 10;
                    if (ws.get(0).ramp) ws.get(0).uh2 -= 10;
                    assertTrue(SavedMint.heavierThanToday(m, r, rx, 0, 0, now),
                        what + ": an edited heavy routine was not caught");
                    // Rebuilt for today, it is no longer heavier.
                    assertTrue(SavedMint.rewrite(m, r, rx, 0, 0));
                    assertFalse(SavedMint.heavierThanToday(m, r, rx, 0, 0, now),
                        what + ": still heavier after the rebuild");
                }
        // A routine the person made lighter than today's is not the net's business.
        Model m = model(NONE, now);
        Mint.Rx rx = rxFor(m, POSITIONS[2], now);
        Model.Routine r = build(m, rx);
        // Every commanding set - the warm-up's prime is a pull as well, and is weighed.
        for (Model.Stage st : r.stages)
            for (String id : st.setIds) {
                Model.Set s = m.set(id);
                if (s == null || s.rest) continue;
                s.up = 8; s.up2 = Math.min(s.up2, 8); s.lo = 2; s.lo2 = Math.min(s.lo2, 2);
            }
        Deload.arm(m, now - DAY);
        assertFalse(SavedMint.heavierThanToday(m, r, rx, 0, 0, now),
            "a routine lighter than today's build was flagged");
    }

    @Test
    void theNetOnATractionSessionWeighsTheCodaNotThePulls() {
        long now = System.currentTimeMillis();
        Model m = rack(NONE, now);
        Mint.Rx rx = rxFor(m, POSITIONS[6], now);
        Model.Routine r = build(m, rx);
        assertTrue(Say.isTraction(r));
        assertFalse(SavedMint.heavierThanToday(m, r, rx, 0, 0, now));
        Deload.arm(m, now - DAY);
        assertTrue(SavedMint.heavierThanToday(m, r, rx, 0, 0, now),
            "a full-pressure coda on a taper day was hidden behind the pulls");
        assertTrue(SavedMint.rewrite(m, r, rx, 0, 0));
        assertFalse(SavedMint.heavierThanToday(m, r, rx, 0, 0, now));
    }

    /* ------------------------------------------------ the comparison leaves nothing behind */

    @Test
    void askingLeavesTheLibraryAndTheLogAsTheyWere() {
        long now = System.currentTimeMillis();
        Model m = model(NONE, now);
        shape(m, Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_RAMP_IN_SET,
              Model.Program.PRESS_FIRM);
        Model.Sess s = new Model.Sess();
        s.id = "d1"; s.ts = now - DAY;
        m.sessLog.all.add(s);
        Mint.Rx rx = rxFor(m, POSITIONS[2], now);
        Model.Routine r = build(m, rx);
        String was = sig(m, rx, now);
        shape(m, Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_FIXED,
              Model.Program.PRESS_STANDARD);
        m.rxWarmMin = 8;
        int routines = m.routines.size(), sets = m.sets.size();
        Model.SessLog log = m.sessLog;
        assertFalse(edited(m, r, was, now));
        // Firm, saved; Standard, today: heavier than today's build (the net asks only on a
        // reduced day - this is here for what it leaves behind).
        assertTrue(SavedMint.heavierThanToday(m, r, rx, 0, 0, now));
        assertEquals(routines, m.routines.size(), "a comparison left a routine in the library");
        assertEquals(sets, m.sets.size(), "a comparison left a set in the library");
        assertTrue(log == m.sessLog && log.all.size() == 1, "the session log was not put back");
        assertEquals(Model.Program.WORK_FIXED, m.programGirth.work,
            "the minted shape leaked into the person's own Program");
        assertEquals(8, m.rxWarmMin, "the minted warm-up leaked into the person's settings");
    }

    /* --------------------------------------------------------- the build day, in RxBuild */

    @Test
    void aBuildForTodayIsTheOrdinaryBuild() {
        long now = System.currentTimeMillis();
        for (int red = NONE; red <= TAPER2; red++)
            for (int si = 0; si < SHAPES.length; si++) {
                Model m = model(red, now);
                shape(m, Plan.TRACK_GIRTH_INTERVAL, SHAPES[si], Model.Program.PRESS_STANDARD);
                Mint.Rx rx = rxFor(m, POSITIONS[2], now);
                Model.Routine plain = build(m, rx);
                Model.Routine dated = m.routine(RxBuild.routineFromRx(m, rx, 0, 0,
                    RxBuild.Day.at(m, now)));
                assertEquals(SavedMint.workPrint(m, plain), SavedMint.workPrint(m, dated),
                    RED_NAMES[red] + " " + SHAPE_NAMES[si]);
            }
    }

    @Test
    void aShapeTagReadsBackToTheChoicesThatWroteIt() {
        long now = System.currentTimeMillis();
        Model m = model(NONE, now);
        m.rxWarmMin = 7; m.rxWarmRamp = true; m.rxWarmSteps = 6; m.rxPrimeKpa = 17.0;
        m.rxEaseHg = 1.5; m.rxRetention = true; m.rxRetentionKpa = 12.0; m.rxRetentionMin = 9;
        m.rxHoldSec = 150; m.rxSetsPerBlock = 3; m.trainerGirthHybrid = true;
        m.programGirth.warm = Model.Program.WARM_SHORT;
        m.programGirth.work = Model.Program.WORK_PYRAMID;
        m.programGirth.pressure = Model.Program.PRESS_GENTLE;
        m.programGirth.rest = Model.Program.REST_LONG;
        m.programGirth.fatigue = Model.Program.FAT_EXTENDED;
        String tag = m.rxShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L3);
        Mint.Rx rx = rxFor(m, POSITIONS[3], now);
        String s = Mint.signature(rx, tag);
        Model back = new Model();
        assertTrue(SavedMint.applyShapeTag(back, s, Plan.TRACK_GIRTH_INTERVAL, Plan.L3));
        assertEquals(tag, back.rxShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L3),
            "the choices read back do not write the same tag");
        // An empty tag is every default - but the fatigue hold, which before R11-5 was 45 s.
        Model def = new Model();
        def.rxFatigueHoldSec = Mint.HOLD_FATIGUE_SEC;
        m = new Model();
        m.rxWarmMin = 9; m.programGirth.work = Model.Program.WORK_RAMP_IN_SET;
        assertTrue(SavedMint.applyShapeTag(m, Mint.signature(rx, ""), Plan.TRACK_GIRTH_INTERVAL,
                                           Plan.L3));
        assertEquals(def.rxShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L3),
                     m.rxShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L3));
        // EXPECTATION CHANGED (t10): every girth and length tag states the t10 build token,
        // so the defaults' tag is that token's, not "" (Model#T10_BUILD_TOKEN).
        assertTrue(m.rxShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L3)
            .endsWith(":" + Model.T10_BUILD_TOKEN));
        assertTrue(m.legacyT10, "an empty tag was minted before the t10 build");
        // A segment it cannot read leaves the model alone.
        Model odd = new Model();
        odd.rxWarmMin = 9;
        assertFalse(SavedMint.applyShapeTag(odd, "1|2|10|27|120|0|S4:x", Plan.TRACK_GIRTH_INTERVAL,
                                            Plan.L2));
        assertEquals(9, odd.rxWarmMin);
    }

    /* ------------------------------ "Adjust first..." (the owner's ruling, 0.10) */

    /** The save "Adjust first..." makes, exactly as SessionActivity#saveMint makes it: the
     *  person's sets and pressure, and no bias on a pressure they set. */
    private static Model.Routine adjustedSave(Model m, Mint.Rx rx, int sets, int kpa,
                                              int part, int ofParts) {
        return m.routine(RxBuild.routineFromRx(m, Mint.adjusted(rx, sets, kpa), part, ofParts,
            RxBuild.Day.today(m).ownPressure(kpa != rx.pressureKpa)));
    }

    /** The deepest pull of the work (not the warm-up, a rest, the fatigue block, retention). */
    private static int workPeak(Model m, Model.Routine r) {
        int pk = 0;
        for (Model.Set s : workSets(m, r)) pk = Math.max(pk, Math.max(s.up, s.endUp()));
        return pk;
    }

    private static final Pos[] ADJUSTABLE = {       // every position "Adjust first..." offers
        POSITIONS[0], POSITIONS[1], POSITIONS[2], POSITIONS[3], POSITIONS[4], POSITIONS[5],
        POSITIONS[6], POSITIONS[7],
    };

    @Test
    void anAdjustedPressureIsThePersonsUnderEveryBiasAndItsSaveIsThePlans() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Pos p : ADJUSTABLE)
            for (int si = 0; si < SHAPES.length; si++)
                for (int red = NONE; red <= TAPER2; red++) {
                    String standardPrint = null;
                    for (int bi = 0; bi < BIASES.length; bi++) {
                        Model m = model(red, now);
                        if (p.track == Plan.TRACK_GIRTH_TRADITIONAL)
                            m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
                        shape(m, p.track, SHAPES[si], BIASES[bi]);
                        Mint.Rx rx = rxFor(m, p, now);
                        String what = p.name + " / " + SHAPE_NAMES[si] + " / " + BIAS_NAMES[bi]
                            + " / " + RED_NAMES[red];
                        // Lowered by 3 kPa - the move Firm used to undo - and a set fewer.
                        int kpa = rx.pressureKpa - 3;
                        int sets = rx.sets > 1 ? rx.sets - 1 : 2;
                        Model.Routine r = adjustedSave(m, rx, sets, kpa, 0, 0);
                        String s = Mint.adjustedSignature(sig(m, rx, now), sets, kpa, true);
                        // The person's pressure wins: the day's cut comes off it, no bias.
                        assertEquals(Mint.reducedKpa(kpa, m.reductionKpa(now)), workPeak(m, r),
                            what + ": the adjusted pressure is not what the work runs");
                        // ...so every bias builds the same routine from it.
                        String print = SavedMint.workPrint(m, r);
                        if (standardPrint == null) standardPrint = print;
                        assertEquals(standardPrint, print, what + ": the bias moved it");
                        // And the save is the plan's own: a fresh build of its signature.
                        assertFalse(edited(m, r, s, now), what + ": an adjusted save read as edited");
                        // A real edit to it is still an edit.
                        Model.Set last = workSets(m, r).get(workSets(m, r).size() - 1);
                        last.uh += 10;
                        if (last.ramp) last.uh2 += 10;
                        assertTrue(edited(m, r, s, now), what + ": an edit to an adjusted save "
                            + "read as the plan's");
                        n++;
                    }
                }
        assertEquals(ADJUSTABLE.length * 4 * 4 * 3, n);
    }

    @Test
    void adjustingOnlyTheSetsLeavesThePressureThePlansAndBiased() {
        long now = System.currentTimeMillis();
        for (Pos p : ADJUSTABLE)
            for (int bi = 0; bi < BIASES.length; bi++)
                for (int red = NONE; red <= TAPER2; red++) {
                    Model m = model(red, now);
                    if (p.track == Plan.TRACK_GIRTH_TRADITIONAL)
                        m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
                    shape(m, p.track, Model.Program.WORK_FIXED, BIASES[bi]);
                    Mint.Rx rx = rxFor(m, p, now);
                    String what = p.name + " / " + BIAS_NAMES[bi] + " / " + RED_NAMES[red];
                    int sets = rx.sets + 1;
                    Model.Routine r = adjustedSave(m, rx, sets, rx.pressureKpa, 0, 0);
                    String s = Mint.adjustedSignature(sig(m, rx, now), sets, rx.pressureKpa, false);
                    assertEquals(RxBuild.commandedKpa(m, rx), workPeak(m, r),
                        what + ": the plan's own pressure takes the bias and the cut as ever");
                    assertFalse(edited(m, r, s, now), what + ": a sets-only adjustment read as edited");
                }
    }

    @Test
    void anAdjustedSplitIsThePlansInBothHalves() {
        long now = System.currentTimeMillis();
        for (int bi = 0; bi < BIASES.length; bi++)
            for (int red = NONE; red <= TAPER2; red++) {
                Model m = model(red, now);
                shape(m, Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_FIXED, BIASES[bi]);
                Mint.Rx rx = rxFor(m, POSITIONS[2], now);
                int kpa = rx.pressureKpa - 2, sets = rx.sets - 1;
                String s = Mint.adjustedSignature(sig(m, rx, now), sets, kpa, true);
                for (int part = 1; part <= 2; part++) {
                    Model.Routine r = adjustedSave(m, rx, sets, kpa, part, 2);
                    assertFalse(SavedMint.edited(m, r, s, part, 2, now, null, now),
                        BIAS_NAMES[bi] + " / " + RED_NAMES[red] + ": half " + part
                        + " of an adjusted split read as edited");
                    assertEquals(Mint.reducedKpa(kpa, m.reductionKpa(now)), workPeak(m, r));
                }
            }
    }

    @Test
    void aTaperStepRebuildsAnAdjustedRoutineAsThePersonSetIt() {
        long now = System.currentTimeMillis();
        Pos p = POSITIONS[2];
        int n = 0;
        for (int si = 0; si < SHAPES.length; si++)
            for (int bi = 0; bi < BIASES.length; bi++)
                for (int red = CUT; red <= TAPER2; red++) {
                    Model m = model(NONE, now);
                    shape(m, p.track, SHAPES[si], BIASES[bi]);
                    Mint.Rx rx = rxFor(m, p, now);
                    int kpa = rx.pressureKpa - 3, sets = rx.sets - 1;
                    Model.Routine r = adjustedSave(m, rx, sets, kpa, 0, 0);
                    String stored = Mint.adjustedSignature(sig(m, rx, now), sets, kpa, true);
                    String what = SHAPE_NAMES[si] + " / " + BIAS_NAMES[bi] + " / " + RED_NAMES[red];
                    // The plan's next look: the working pressure advanced to the person's, so it
                    // prescribes at it - the same offer, answered.
                    Mint.Rx next = Mint.prescribe(p.track, p.level, p.week, kpa,
                        TrainerTab.monthIndexNow(m, now), m.ceilKpa, p.carried, 0, null);
                    assertTrue(Mint.alreadyMinted(stored, sig(m, next, now), true),
                        what + ": the saved adjustment is offered again");
                    // The lighter day.
                    if (red == CUT) m.oversizeAccepted = true;
                    else { Deload.arm(m, now - DAY); if (red == TAPER2) m.returnStep = 1; }
                    String today = sig(m, next, now);
                    Mint.Adjust kept = SavedMint.carried(stored, today);
                    if (red != CUT) {
                        // A taper step is in the signature: the adjustment carries across it.
                        assertNotNull(kept, what + ": a taper step dropped the adjustment");
                        assertTrue(kept.ownKpa && kept.sets == sets, what);
                    }
                    Mint.Rx build = SavedMint.carriedRx(next, today, stored);
                    boolean own = kept != null && kept.ownKpa;
                    assertTrue(SavedMint.rewrite(m, r, build, 0, 0, own), what);
                    String after = SavedMint.carriedSig(today, stored);
                    assertEquals(Mint.reducedKpa(kpa, m.reductionKpa(now)), workPeak(m, r),
                        what + ": rebuilt at another pressure than the person's less the cut");
                    assertFalse(edited(m, r, after, now), what + ": the rebuild read as edited");
                    assertFalse(SavedMint.heavierThanToday(m, r, build, 0, 0, now, own),
                        what + ": the run-start net would rebuild it again");
                    assertEquals(0.0, r.netTargetMin, 1e-9, what + ": a reduced day asks for net");
                    n++;
                }
        assertEquals(4 * 3 * 3, n);
        // A NEW PRESCRIPTION IS THE PLAN'S OWN OFFER: the adjustment does not carry to it.
        long now2 = System.currentTimeMillis();
        Model m = model(NONE, now2);
        Mint.Rx rx = rxFor(m, POSITIONS[2], now2);
        String stored = Mint.adjustedSignature(sig(m, rx, now2), rx.sets - 1, rx.pressureKpa - 3,
                                               true);
        // The plan's next step: a set added to the prescription at the person's pressure.
        Mint.Rx atTheirs = Mint.adjusted(rx, rx.sets, rx.pressureKpa - 3);
        String moved = sig(m, Mint.adjusted(atTheirs, atTheirs.sets + 1, atTheirs.pressureKpa),
                           now2);
        assertFalse(Mint.alreadyMinted(stored, moved, true), "a new offer read as answered");
        assertNull(SavedMint.carried(stored, moved), "an adjustment carried to a new offer");
    }

    @Test
    void theAdjustSegmentReadsBackAndOlderSignaturesHaveNone() {
        String plain = "1|2|10|27|120|0";
        assertNull(Mint.adjustOf(plain), "a signature from before 0.10 records no adjustment");
        assertEquals(plain, Mint.withoutAdjust(plain));
        assertEquals(plain, Mint.adjustedIdentity(plain));
        assertEquals(plain, Mint.adjustedSignature(plain, 10, 27, false), "nothing adjusted");
        String shaped = plain + "|S4:0:1355:300:R0|RET40";
        String a = Mint.adjustedSignature(shaped, 8, 24, true);
        assertEquals("1|2|10|27|120|0|S4:0:1355:300:R0|RET40|ADJ8:24", a,
            "the offer's own six fields, and the adjustment beside them");
        Mint.Adjust x = Mint.adjustOf(a);
        assertNotNull(x);
        assertEquals(8, x.sets);
        assertTrue(x.ownKpa);
        assertEquals(24, x.kpa);
        assertEquals(4.0, Deload.stepHg(a), 1e-9, "the step still reads");
        assertEquals(shaped, Mint.withoutAdjust(a));
        assertEquals("1|2|10|24|120|0|S4:0:1355:300:R0|RET40", Mint.adjustedIdentity(a));
        // The save answers the offer it adjusted AND that offer at the person's pressure (what
        // the plan prescribes once the working pressure has moved to it) - nothing else.
        assertTrue(Mint.alreadyMinted(a, shaped, true), "the offer it adjusted");
        assertTrue(Mint.alreadyMinted(a, Mint.adjustedIdentity(a), true), "at their pressure");
        assertFalse(Mint.alreadyMinted(a, "1|2|11|27|120|0|S4:0:1355:300:R0|RET40", true),
            "a new offer");
        assertFalse(Mint.alreadyMinted(a, shaped, false), "a deleted routine re-offers");
        assertEquals(24, SavedMint.builtRx(a).pressureKpa);
        assertEquals(8, SavedMint.builtRx(a).sets);
        assertEquals(8 * 2.0, SavedMint.builtRx(a).netTargetMin, 1e-9);
        assertTrue(SavedMint.ownKpa(a));
        // Sets only: the pressure is the plan's and takes the bias.
        String b = Mint.adjustedSignature(plain, 12, 27, false);
        assertEquals(plain + "|ADJ12", b);
        assertFalse(SavedMint.ownKpa(b));
        assertEquals(27, SavedMint.builtRx(b).pressureKpa);
        assertEquals(plain, Mint.adjustedIdentity(b));
        // Adjusted again: one segment, the new one.
        String c = Mint.adjustedSignature(a, 9, 25, true);
        assertEquals("1|2|10|27|120|0|S4:0:1355:300:R0|RET40|ADJ9:25", c);
        // Nothing that merely begins with the letters is read as one.
        assertNull(Mint.adjustOf(plain + "|ADJX"));
        assertNull(Mint.adjustOf(plain + "|ADJ"));
        assertNull(Mint.adjustOf(plain + "|ADJ8:"));
        assertNull(Mint.adjustOf(plain + "|ADJ8:1:2"));
        assertNull(Mint.adjustOf("ADJ8|2|10|27|120|0"), "only after the prescription's six");
        assertEquals(plain + "|ADJX", Mint.withoutAdjust(plain + "|ADJX"));
    }

    @Test
    void anOfferTurnedDownByKeepingThePressureStaysTurnedDown() {
        // A raise is offered; the person keeps their current pressure in "Adjust first...". The
        // pressure clock did not restart (the figure did not move), so the plan goes on
        // prescribing the raise - which the save has already answered: it must neither be
        // offered as new nor rewrite the routine up to it.
        long now = System.currentTimeMillis();
        for (int bi = 0; bi < BIASES.length; bi++) {
            Model m = model(NONE, now);
            shape(m, Plan.TRACK_GIRTH_INTERVAL, Model.Program.WORK_FIXED, BIASES[bi]);
            Mint.Rx raise = rxFor(m, POSITIONS[2], now);
            int kept = raise.pressureKpa - 3;
            String offer = sig(m, raise, now);
            String stored = Mint.adjustedSignature(offer, raise.sets, kept, true);
            Model.Routine r = adjustedSave(m, raise, raise.sets, kept, 0, 0);
            assertTrue(Mint.alreadyMinted(stored, offer, true), BIAS_NAMES[bi]);
            assertEquals(kept, SavedMint.carriedRx(raise, offer, stored).pressureKpa,
                BIAS_NAMES[bi] + ": rebuilt from the raise instead of the person's pressure");
            assertFalse(edited(m, r, stored, now), BIAS_NAMES[bi]);
            assertEquals(kept, workPeak(m, r), BIAS_NAMES[bi] + ": the bias moved their pressure");
        }
    }
}
