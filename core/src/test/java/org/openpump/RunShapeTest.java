package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 0.10 - THE RUN A BOTH-TRACKS DAY BUILDS ({@link RunShape}), over routines the app's own
 * builders write. What the owner ruled (2026-09-26), and what may never happen with it: the
 * saved routine changes, or anything is commanded deeper than the routine as saved.
 */
class RunShapeTest {

    private static Model model() {
        Model m = new Model();
        m.ceilKpa = 40;
        m.trainerEnrolled = true;
        m.trainerGirth.level = Plan.L2;
        return m;
    }

    /** A plan girth routine of `sets` two-minute intervals at 27 kPa, as RxBuild writes it. */
    private static Model.Routine girth(Model m, int level, int sets) {
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, level, sets, 120, 180, 27, false,
                                 sets * 2.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        r.assess.on = true;
        return r;
    }

    /** The length session that pulls, in the rack Scenarios' traction scenario uses. */
    private static Model.Routine traction(Model m) {
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
        return r;
    }

    private static RunShape.Choice choice(String code) { return RunShape.Choice.fromCode(code); }

    private static int peak(Model m, Model.Routine r) {
        int pk = 0;
        List<Model.Preset> p = m.plan(r);
        for (int i = 0; i < p.size(); i++) pk = Math.max(pk, p.get(i).up);
        return pk;
    }

    private static String json(Model.Routine r) {
        try { return r.toJson().toString(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test void nothingAskedIsTheSavedRoutineItself() {
        Model m = model();
        Model.Routine r = girth(m, Plan.L2, 12);
        RunShape.Built b = RunShape.build(m, r, new RunShape.Choice());
        assertSame(r, b.routine, "a day that changes nothing runs the routine exactly as saved");
        assertFalse(b.changed());
        assertEquals("", b.code());
        assertTrue(RunShape.lines(b, 12, -1, "").isEmpty(), "and the pre-run box says nothing");
    }

    @Test void skippingTheWarmUpTakesOnlyTheWarmUp() {
        Model m = model();
        Model.Routine r = girth(m, Plan.L2, 12);
        assertTrue(RunShape.hasWarmUp(r), "the plan's routine opens with a warm-up");
        String before = json(r);
        int setsInLibrary = m.sets.size();
        RunShape.Built b = RunShape.build(m, r, choice("w"));
        assertTrue(b.warmSkipped);
        assertNotSame(r, b.routine, "a copy runs");
        assertEquals(r.id, b.routine.id, "...under the saved routine's id, so it files as it");
        assertFalse(RunShape.hasWarmUp(b.routine));
        assertEquals(r.stages.size() - 1, b.routine.stages.size(), "one stage fewer, no more");
        assertEquals(before, json(r), "THE SAVED ROUTINE IS UNCHANGED");
        assertSame(r, m.routine(r.id), "and it is still the one in the library");
        assertEquals(setsInLibrary, m.sets.size(), "nothing was added to the set library");
        assertEquals(r.netTargetMin, b.routine.netTargetMin, 1e-9, "the work is untouched");
        List<String> said = RunShape.lines(b, 12, 12, "Length");
        assertEquals("No warm-up: your length session ended 12 min ago.", said.get(0),
                     "t10 R-05 (P4): the plan's rule, said");
        assertEquals("This run only — the saved routine is unchanged.",
                     said.get(said.size() - 1));
    }

    @Test void noTissueTestSwitchesOffOnlyTheTest() {
        Model m = model();
        Model.Routine r = girth(m, Plan.L2, 12);
        r.assess.when = Model.Assess.WHEN_BOTH;
        RunShape.Built b = RunShape.build(m, r, choice("t"));
        assertTrue(b.testSkipped);
        assertFalse(Tau.runsBefore(b.routine) || Tau.runsAfter(b.routine));
        assertTrue(r.assess.on, "the saved routine keeps its test");
        assertEquals(m.plan(r).size(), m.plan(b.routine).size(), "every step still runs");
        assertTrue(RunShape.lines(b, 12, -1, "").get(0).contains("first session only"));
    }

    @Test void fiveGirthSetsComeOutSpreadAcrossTheSession() {
        Model m = model();
        Model.Routine r = girth(m, Plan.L2, 12);
        String before = json(r);
        assertEquals(12, RunShape.workSets(m, r));
        RunShape.Built b = RunShape.build(m, r, choice("g5"));
        assertEquals(5, b.setsTaken);
        assertEquals(7, RunShape.workSets(m, b.routine), "12 → 7");
        assertEquals(10.0, b.minutesTaken, 1e-9, "five two-minute holds");
        assertEquals(24.0 - 10.0, b.routine.netTargetMin, 1e-9,
            "scored against what this run was asked for, not the saved routine's 24 min");
        assertEquals("g5:10.0", b.code());
        assertEquals(before, json(r), "THE SAVED ROUTINE IS UNCHANGED");
        /* 0.10 (the owner's decision): SPREAD ACROSS THE SESSION, not off the end. Blocks of 5
         * at L2: 5 | rest | 5 | rest | 2. Each block keeps its last hold; the five come evenly
         * out of the rest - two, two and one - so the run is 3 | rest | 3 | rest | 1: the same
         * shape, shorter. (Until 0.10 the last block went whole and three came off the one
         * before it.) */
        java.util.List<Integer> holds = new java.util.ArrayList<Integer>();
        int rests = 0;
        for (int i = 0; i < b.routine.stages.size(); i++) {
            Model.Stage st = b.routine.stages.get(i);
            if (st.rest) { rests++; continue; }
            if (st.colour != Model.STAGE_WORK) continue;
            int n = 0;
            for (String id : st.setIds) { Model.Set s = m.set(id); n += s.dur / s.cycle(); }
            holds.add(n);
        }
        assertEquals(java.util.Arrays.asList(3, 3, 1), holds, "every block shorter, none gone");
        assertEquals(2, rests, "and the rests between them stay");
        assertTrue(RunShape.lines(b, 12, -1, "").get(0).startsWith("5 sets fewer (12 → 7)"));
    }

    @Test void aFiveSetSessionKeepsItsLastSet() {
        Model m = model();
        Model.Routine r = girth(m, Plan.L1, 5);
        RunShape.Built b = RunShape.build(m, r, choice("g" + SameDay.girthSetsOff(5)));
        assertEquals(4, b.setsTaken);
        assertEquals(1, RunShape.workSets(m, b.routine), "never an empty session");
        RunShape.Built greedy = RunShape.build(m, r, choice("g5"));
        assertEquals(1, RunShape.workSets(m, greedy.routine),
            "the build keeps the last cycle even when asked for more");
    }

    @Test void aLengthRoutineHasNoGirthSetsToGive() {
        Model m = model();
        Model.Routine r = traction(m);
        RunShape.Built b = RunShape.build(m, r, choice("g5"));
        assertSame(r, b.routine, "the girth rule is the girth track's only");
    }

    @Test void expansionOnlyDropsThePullsAndMovesTheWarmUpToTheExpansion() {
        Model m = model();
        Model.Routine r = traction(m);
        String before = json(r);
        int basePeak = peak(m, r);
        RunShape.Built b = RunShape.build(m, r, choice("x"));
        assertTrue(b.expansionOnly);
        assertFalse(Say.isTraction(b.routine), "no block pulls");
        Model.Stage coda = null;
        for (int i = 0; i < b.routine.stages.size(); i++) {
            Model.Stage st = b.routine.stages.get(i);
            assertFalse(st.rest && st.awaitAck, "no tube swap to wait for");
            if (st.colour == Model.STAGE_WORK && !st.rest) coda = st;
        }
        assertNotNull(coda, "the expansion runs");
        int codaKpa = m.set(coda.setIds.get(0)).up;
        for (int i = 0; i < b.routine.stages.size(); i++) {
            Model.Stage st = b.routine.stages.get(i);
            if (!RunShape.isWarmUp(st)) continue;
            assertEquals(coda.cylinderId, st.cylinderId, "warmed up in the tube it expands in");
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = m.set(st.setIds.get(j));
                assertTrue(Math.max(s.up, s.up2) <= codaKpa,
                    "never deeper than the work it precedes");
            }
        }
        assertEquals(r.netTargetMin, b.routine.netTargetMin, 1e-9, "net comes from the coda");
        assertTrue(peak(m, b.routine) <= basePeak);
        assertTrue(b.routine.assess.kpa <= m.workPeakKpa(b.routine),
            "the check pull never exceeds the session");
        assertEquals(before, json(r), "THE SAVED ROUTINE IS UNCHANGED");
        assertTrue(RunShape.ranExpansionOnly(b.code()));
    }

    /** THE CAP THAT MATTERS (the 0.10 safety review): at a heavy load the warm-up is built
     *  for a pull near the ceiling - its eased cycle is the pull less 3 hg, about 30 kPa -
     *  and the expansion it now precedes runs near 20. Without the cap this fails. */
    @Test void atAHeavyLoadTheWarmUpIsCappedUnderTheExpansion() {
        Model m = model();
        m.trainerLength.loadLb = 15.0;
        Model.Routine r = traction(m);
        Model.Stage coda = null, warm = null;
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (RunShape.isWarmUp(st)) warm = st;
            if (st.colour == Model.STAGE_WORK && !st.rest && !st.traction) coda = st;
        }
        assertNotNull(warm, "the heavy session warms up");
        int codaKpa = m.set(coda.setIds.get(0)).up;
        int warmPeak = 0;
        for (int j = 0; j < warm.setIds.size(); j++)
            warmPeak = Math.max(warmPeak, RunShape.peakOf(m.set(warm.setIds.get(j))));
        assertTrue(warmPeak > codaKpa, "the fixture really is the case: a warm-up of "
            + warmPeak + " kPa built for the pull, in front of an expansion at " + codaKpa);

        RunShape.Built b = RunShape.build(m, r, choice("x"));
        int cap = RunShape.warmCapKpa(codaKpa);
        assertTrue(cap < codaKpa, "the cap is lighter than the work");
        for (int i = 0; i < b.routine.stages.size(); i++) {
            Model.Stage st = b.routine.stages.get(i);
            if (!RunShape.isWarmUp(st)) continue;
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = m.set(st.setIds.get(j));
                assertTrue(RunShape.peakOf(s) <= cap, "a warm-up step at " + RunShape.peakOf(s)
                    + " kPa in front of an expansion at " + codaKpa + " (cap " + cap + ")");
            }
        }
    }

    /** Under 4 inHg the prime would have equalled the expansion: S18's 80 % keeps it under. */
    @Test void aLowExpansionIsWarmedUpForAtEightyPerCent() {
        Model m = model();
        Model.Routine r = traction(m);
        Model.Stage coda = null, warm = null;
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (RunShape.isWarmUp(st)) warm = st;
            if (st.colour == Model.STAGE_WORK && !st.rest && !st.traction) coda = st;
        }
        Model.Set codaSet = m.set(coda.setIds.get(0));
        codaSet.up = 10; codaSet.lo = 9;                      // an expansion under 4 inHg
        Model.Set prime = m.set(warm.setIds.get(0));
        prime.up = 10; prime.lo = 9;                          // a prime as deep as it
        Model.Set.Rep rep = new Model.Set.Rep();
        rep.rep = 1; rep.up = 30; rep.lo = 29; rep.uh = 20; rep.lh = 5; rep.sp = 60;
        prime.reps.add(rep);                                  // and a repetition of its own
        RunShape.Built b = RunShape.build(m, r, choice("x"));
        for (int i = 0; i < b.routine.stages.size(); i++) {
            Model.Stage st = b.routine.stages.get(i);
            if (!RunShape.isWarmUp(st)) continue;
            Model.Set s = m.set(st.setIds.get(0));
            assertEquals(8, s.up, "80 % of a 10 kPa expansion");
            assertTrue(s.lo < s.up);
            assertEquals(1, s.reps.size());
            assertEquals(8, s.reps.get(0).up, "the repetition's own pull is capped too");
            assertTrue(s.reps.get(0).lo < s.reps.get(0).up);
        }
        assertEquals(10, prime.up, "the saved set is untouched");
        assertEquals(30, prime.reps.get(0).up);
    }

    /** S06's credit is earned only by an expansion delivered in full (the 0.10 review). */
    @Test void theExpansionIsDeliveredOnlyWhenEveryCycleOfItRan() {
        Model m = model();
        Model.Routine r = traction(m);
        List<Model.Preset> plan = m.plan(r);
        java.util.List<AsRun.Block> all = new java.util.ArrayList<AsRun.Block>();
        int coda = -1;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            AsRun.Block b = new AsRun.Block();
            b.stageIdx = p.stageIdx;
            b.up = p.up; b.lo = p.lo; b.uh = p.uh; b.lh = p.lh; b.sp = p.sp;
            b.spanMs = p.durMs;
            all.add(b);
            Model.Stage st = r.stages.get(p.stageIdx);
            if (st.colour == Model.STAGE_WORK && !st.rest && !st.traction) coda = p.stageIdx;
        }
        assertTrue(RunShape.expansionDelivered(m, r, all), "a whole run delivered it");

        java.util.List<AsRun.Block> release = new java.util.ArrayList<AsRun.Block>();
        release.add(all.get(0));
        assertFalse(RunShape.expansionDelivered(m, r, release),
            "a run stopped in the tunica release did none of it");

        java.util.List<AsRun.Block> half = new java.util.ArrayList<AsRun.Block>();
        for (int i = 0; i < all.size(); i++) {
            AsRun.Block b = all.get(i);
            if (b.stageIdx != coda) { half.add(b); continue; }
            AsRun.Block c = new AsRun.Block();
            c.stageIdx = b.stageIdx; c.up = b.up; c.lo = b.lo; c.uh = b.uh; c.lh = b.lh;
            c.spanMs = b.spanMs / 2;
            half.add(c);
        }
        assertFalse(RunShape.expansionDelivered(m, r, half), "half the expansion is not it");

        for (int i = 0; i < all.size(); i++) if (all.get(i).stageIdx == coda) all.get(i).skipped = true;
        assertFalse(RunShape.expansionDelivered(m, r, all), "a skipped expansion is not it");
    }

    /** S08 FOLLOW-UP (the owner, 2026-09-26): the hand release is asked at START, and a "skip
     *  it" takes out that vented stage and nothing else - every commanded step is the same. */
    @Test void skippingTheHandReleaseTakesOnlyTheVentedStage() {
        Model m = model();
        Model.Routine r = traction(m);
        assertTrue(RunShape.hasRelease(r), "the length session opens with the hand release");
        String before = json(r);
        RunShape.Built x = RunShape.build(m, r, choice("x"));
        RunShape.Built xr = RunShape.build(m, r, choice("x,r"));
        assertTrue(xr.releaseSkipped && xr.expansionOnly);
        assertFalse(RunShape.hasRelease(xr.routine), "the release is gone");
        assertTrue(RunShape.hasRelease(x.routine), "...and only when the person said so");
        assertEquals(x.routine.stages.size() - 1, xr.routine.stages.size(), "one stage, no more");
        List<Model.Preset> a = m.plan(x.routine), b = m.plan(xr.routine);
        java.util.List<String> cmdA = new java.util.ArrayList<String>();
        java.util.List<String> cmdB = new java.util.ArrayList<String>();
        for (int i = 0; i < a.size(); i++) if (!a.get(i).rest) cmdA.add(step(a.get(i)));
        for (int i = 0; i < b.size(); i++) if (!b.get(i).rest) cmdB.add(step(b.get(i)));
        assertEquals(cmdA, cmdB, "every commanded step is exactly what it was");
        assertEquals(m.routineSec(x.routine) - RxBuild.TUNICA_RELEASE_SEC,
                     m.routineSec(xr.routine), "five minutes by hand, and nothing else");
        assertEquals("x,r", xr.code());
        assertEquals(before, json(r), "THE SAVED ROUTINE IS UNCHANGED");
        assertTrue(RunShape.lines(xr, 0, -1, "").contains(
            "No hand release — you chose to skip it before the expansion."));
    }

    @Test void theHandReleaseStaysWhereThereArePullsToPrepareFor() {
        Model m = model();
        Model.Routine r = traction(m);
        assertSame(r, RunShape.build(m, r, choice("r")).routine,
            "without expansion only the release precedes the pulls, and is never left out");
        Model.Routine g = girth(m, Plan.L2, 12);
        assertSame(g, RunShape.build(m, g, choice("r")).routine, "a girth run has none");
        assertTrue(choice("x,r").skipRelease && choice("x,r").expansionOnly);
    }

    private static String step(Model.Preset p) {
        return p.up + "/" + p.lo + "/" + p.uh + "/" + p.lh + "/" + p.sp + "/" + p.durMs;
    }

    @Test void expansionOnlyIsOnlyForASessionThatPulls() {
        Model m = model();
        Model.Routine r = girth(m, Plan.L2, 12);
        assertSame(r, RunShape.build(m, r, choice("x")).routine);
    }

    /** THE SAFETY PROPERTY, over every combination on every fixture: nothing any shape
     *  builds is deeper than the saved routine, or past the ceiling, anywhere in its plan. */
    @Test void noShapeEverCommandsDeeperThanTheSavedRoutine() {
        String[] codes = { "w", "t", "g5", "x", "w,t", "w,g5", "t,g5", "w,t,g5", "w,x", "t,x",
                           "w,t,x", "w,t,g5,x", "r", "x,r", "w,x,r", "w,t,g5,x,r" };
        Model m = model();
        Model.Routine[] fixtures = { girth(m, Plan.L1, 5), girth(m, Plan.L2, 12),
                                     girth(m, Plan.L2, 7), traction(m) };
        for (int f = 0; f < fixtures.length; f++) {
            Model.Routine r = fixtures[f];
            int basePeak = peak(m, r);
            String before = json(r);
            for (int c = 0; c < codes.length; c++) {
                RunShape.Built b = RunShape.build(m, r, choice(codes[c]));
                List<Model.Preset> p = m.plan(b.routine);
                assertFalse(p.isEmpty(), codes[c] + ": the run still has something to run");
                for (int i = 0; i < p.size(); i++) {
                    assertTrue(p.get(i).up <= basePeak, codes[c] + " on fixture " + f
                        + ": a step at " + p.get(i).up + " kPa over the saved peak " + basePeak);
                    assertTrue(p.get(i).up <= m.ceilKpa, "inside the ceiling");
                }
                assertTrue(m.routineSec(b.routine) <= m.routineSec(r),
                    codes[c] + ": a shape only ever takes time out");
                assertEquals(before, json(r), codes[c] + ": the saved routine is unchanged");
            }
        }
    }

    @Test void theCodeReadsBackAndIgnoresWhatItDoesNotKnow() {
        RunShape.Choice c = choice("w,t,g5:10.0,x");
        assertTrue(c.skipWarm && c.noTissueTest && c.expansionOnly);
        assertEquals(5, c.girthSetsOff);
        assertEquals("w,t,g5,x", c.code());
        assertEquals(10.0, RunShape.minutesTaken("w,g5:10.0"), 1e-9);
        assertEquals(0.0, RunShape.minutesTaken("g5"), 1e-9, "an ask records no minutes");
        assertEquals(0.0, RunShape.minutesTaken(""), 1e-9);
        assertFalse(choice("z,q9,gx").any(), "a later version's tokens shape nothing here");
    }

    @Test void aNewBuildForgetsTheLastOnesCopies() {
        Model m = model();
        Model.Routine r = girth(m, Plan.L2, 12);
        Model.Set manual = Model.Set.fixed("manual", "Manual", 20, 10, 30, 5, 60, 120);
        m.adhoc.add(manual);
        RunShape.build(m, r, choice("g5"));
        int copies = 0;
        for (int i = 0; i < m.adhoc.size(); i++)
            if (m.adhoc.get(i).id.startsWith(RunShape.SET_PREFIX)) copies++;
        // 0.10: the holds come out of every block (spread), so each block's set is copied once.
        assertEquals(3, copies, "each shortened block, once");
        RunShape.build(m, r, choice("g5"));
        int again = 0;
        for (int i = 0; i < m.adhoc.size(); i++)
            if (m.adhoc.get(i).id.startsWith(RunShape.SET_PREFIX)) again++;
        assertEquals(copies, again, "a rebuild replaces them rather than piling up");
        assertTrue(m.adhoc.contains(manual), "and leaves anything else in the list alone");
    }
}
