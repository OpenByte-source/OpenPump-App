package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THREE THINGS THAT HELD A PLAN AT LEVEL 1 FOR A YEAR (0.10, the owner's decisions; each
 * found by a 52-week simulation of the app's own core):
 *
 *   1. BOTH TRACKS ON, INTERVAL GIRTH: START gives five girth sets to the length session
 *      that runs first (S06). The pressure steps read that session with the sets credited
 *      back; the Level 1 gate read the shortened minutes against its 20 and never passed.
 *   2. "8.0 inHg" IS 27 kPa, and the setup read it against 8 hg's exact 27.09 - so somebody
 *      at 6 or 12 months answering 8.0 was placed at Level 1.
 *   3. LENGTH WITHOUT A PULLING CYLINDER never asked its month gates: the no-pull rung
 *      returned first, so it stayed at Level 1 while the same person with a length tube moved
 *      up at months 3, 6 and 12.
 *
 * The year-long sweep over the setup answers is {@link YearStallsSweepTest}.
 */
class YearStallsTest {

    static final double HG = Plan.HG;

    /* ===================================================== 1. the gate on both-tracks days */

    /** PressureClockTest's model with a length routine beside the girth ones. */
    static Model bothTracks() {
        Model m = PressureClockTest.model();
        Model.Routine l = new Model.Routine();
        l.id = "L";
        l.trainerTrack = Plan.TRACK_LENGTH;
        m.routines.add(l);
        m.trainerLengthOn = true;
        return m;
    }

    /** A both-tracks day: the length session at 8:00, then the girth session that gave five
     *  two-minute sets (10 minutes) to it - filed as START files it (RunShape's "g5:10.0"). */
    static Model.Sess bothDay(Model m, String girth, int day, double net, double target,
                              boolean expansionDone) {
        Model.Sess ls = PressureClockTest.file(m, "L", PressureClockTest.at(day, 8), 12, 12, 20);
        ls.expansionDone = expansionDone;
        Model.Sess gs = PressureClockTest.file(m, girth, PressureClockTest.at(day, 9),
                                               net, target, 27);
        gs.shape = "g5:10.0";
        return gs;
    }

    /** Two weeks of M/W/F both-tracks days. */
    static Model twoWeeks(String girth, double net, double target, boolean expansionDone) {
        Model m = bothTracks();
        if ("t".equals(girth)) m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
        for (int w = 0; w < 2; w++)
            for (int d = 0; d <= 4; d += 2)
                bothDay(m, girth, 7 * w + d, net, target, expansionDone);
        return m;
    }

    static final long NOW = PressureClockTest.at(14, 8);

    @Test void theGateCountsTheHoldsGivenToTheLengthSession() {
        Model m = twoWeeks("g", 10, 10, true);
        assertEquals(2, TrainerTab.gateHeldTrainingWeeks(m, Plan.TRACK_GIRTH_INTERVAL, NOW),
            "10 minutes run and 10 given to the length session that did its expansion: the "
            + "20 the gate asks for, held two weeks");
        // The pressure steps read the very same figure (they always did).
        TrainerTab.NetPairs np = TrainerTab.recentTrackedNets(m, Plan.TRACK_GIRTH_INTERVAL, 3);
        for (int i = 0; i < np.size(); i++) assertEquals(20.0, np.nets[i], 1e-9);
        // ...and the gate card shows it.
        assertArrayEquals(new double[]{ 20.0, 20.0, 20.0 },
            TrainerTab.recentCreditedNetsMin(m, Plan.TRACK_GIRTH_INTERVAL, 3), 1e-9);
        assertArrayEquals(new double[]{ 10.0, 10.0, 10.0 },
            TrainerTab.recentTrackedNetsMin(m, Plan.TRACK_GIRTH_INTERVAL, 3), 1e-9,
            "the raw nets are what they were - only the gate's reading credits them");
    }

    @Test void noCreditWithoutTheExpansionAndAShortSessionStaysShort() {
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(twoWeeks("g", 10, 10, false),
            Plan.TRACK_GIRTH_INTERVAL, NOW),
            "a length session that did not deliver its expansion earns the girth no credit");
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(twoWeeks("g", 9, 10, true),
            Plan.TRACK_GIRTH_INTERVAL, NOW),
            "9 of 10 minutes is credited at the same rate: 18 of 20, not past the gate");
        // A session with no shape code gave nothing up: 10 minutes are 10 minutes.
        Model m = twoWeeks("g", 10, 10, true);
        for (Model.Sess s : m.sessLog.all) s.shape = "";
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(m, Plan.TRACK_GIRTH_INTERVAL, NOW));
        // Simulated length sessions do not count either.
        Model sim = twoWeeks("g", 10, 10, true);
        for (Model.Sess s : sim.sessLog.all) if ("L".equals(s.routineId)) s.sim = true;
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(sim, Plan.TRACK_GIRTH_INTERVAL, NOW));
    }

    /* 0.10 - A RAMP'S CLIMBING HOLDS UNDER THE COUNTING LINE (the owner's decision): not made
     * up and not in the target, but credited, at the rate the session delivered its target. */

    /** Two weeks of M/W/F girth sessions whose routine left `under` climb minutes out of its
     *  target, filed as the app files them (Sess#climbUnderLineMin). */
    static Model climbWeeks(double net, double target, double under) {
        Model m = PressureClockTest.model();
        for (int w = 0; w < 2; w++)
            for (int d = 0; d <= 4; d += 2)
                PressureClockTest.file(m, "g", PressureClockTest.at(7 * w + d, 9), net, target,
                                       27).climbUnderLineMin = under;
        return m;
    }

    @Test void theGateCreditsAClimbHoldUnderTheLine() {
        // Level 1's Firm climb from a low answer: 18 counted, 2 under the line.
        Model m = climbWeeks(18, 18, 2);
        assertEquals(2, TrainerTab.gateHeldTrainingWeeks(m, Plan.TRACK_GIRTH_INTERVAL, NOW),
            "its whole target delivered reads as the plan's 20, held two weeks");
        TrainerTab.NetPairs np = TrainerTab.recentTrackedNets(m, Plan.TRACK_GIRTH_INTERVAL, 3);
        for (int i = 0; i < np.size(); i++) {
            assertEquals(20.0, np.nets[i], 1e-9, "the pressure steps read the same figure");
            assertEquals(20.0, np.targets[i], 1e-9);
        }
        assertArrayEquals(new double[]{ 18.0, 18.0, 18.0 },
            TrainerTab.recentTrackedNetsMin(m, Plan.TRACK_GIRTH_INTERVAL, 3), 1e-9,
            "the raw nets are what they were");
        // Uncredited, the same sessions never pass: this is the stall the credit removes.
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(climbWeeks(18, 18, 0),
            Plan.TRACK_GIRTH_INTERVAL, NOW));
        // A short session stays short: 17 of 18 reads 17 x 20/18, under the gate.
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(climbWeeks(17, 18, 2),
            Plan.TRACK_GIRTH_INTERVAL, NOW));
        // Under-delivery reads each session against its own target, credited alike.
        TrainerTab.NetPairs shortNp = TrainerTab.recentTrackedNets(climbWeeks(9, 18, 2),
            Plan.TRACK_GIRTH_INTERVAL, 3);
        assertEquals(0.5, shortNp.nets[0] / shortNp.targets[0], 1e-9);
    }

    @Test void aClimbHoldGivenToTheLengthSessionIsCreditedOnlyWhenItDelivered() {
        // A both-tracks day that took 4 counted minutes and one 2-minute climb hold under the
        // line ("u2.0"), leaving 8 counted and 2 uncounted: the plan's 16 when length delivered.
        for (int done = 0; done < 2; done++) {
            Model m = bothTracks();
            for (int w = 0; w < 2; w++)
                for (int d = 0; d <= 4; d += 2) {
                    Model.Sess gs = bothDay(m, "g", 7 * w + d, 8, 8, done == 1);
                    gs.shape = "g3:4.0,u2.0";
                    gs.climbUnderLineMin = 2.0;
                }
            TrainerTab.NetPairs np = TrainerTab.recentTrackedNets(m, Plan.TRACK_GIRTH_INTERVAL, 3);
            assertEquals(done == 1 ? 16.0 : 10.0, np.nets[0], 1e-9,
                done == 1 ? "8 run, 2 climbed under the line, 6 given to a length session that "
                            + "delivered" : "no length expansion, so only the run's own climb");
        }
        assertEquals(2.0, RunShape.climbMinutesTaken("g3:4.0,u2.0"), 1e-9);
        assertEquals(4.0, RunShape.minutesTaken("g3:4.0,u2.0"), 1e-9);
        assertEquals(0.0, RunShape.climbMinutesTaken("g3:4.0"), 1e-9);
        assertEquals(0.0, RunShape.climbMinutesTaken("u"), 1e-9);
        assertEquals(0.0, RunShape.climbMinutesTaken("uNaN"), 1e-9);
        assertEquals(Model.CLIMB_UNDER_MAX_MIN, RunShape.climbMinutesTaken("u9999"), 1e-9);
        assertEquals(3, RunShape.Choice.fromCode("g3:4.0,u2.0").girthSetsOff,
            "the record's own token shapes nothing");
    }

    @Test void traditionalIsReadAsBefore() {
        // Its own target, credited or not: held when each session held its own minutes...
        assertEquals(2, TrainerTab.gateHeldTrainingWeeks(twoWeeks("t", 10, 10, true),
            Plan.TRACK_GIRTH_TRADITIONAL, NOW));
        assertEquals(2, TrainerTab.gateHeldTrainingWeeks(twoWeeks("t", 10, 10, false),
            Plan.TRACK_GIRTH_TRADITIONAL, NOW));
        // ...and not when they fell short, whatever the length session did.
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(twoWeeks("t", 9, 10, true),
            Plan.TRACK_GIRTH_TRADITIONAL, NOW));
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(twoWeeks("t", 9, 10, false),
            Plan.TRACK_GIRTH_TRADITIONAL, NOW));
    }

    @Test void theEngineProposesLevelTwoOnBothTracksDays() {
        Model m = twoWeeks("g", 10, 10, true);
        m.trainerMonthsPumping = 4;
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L1;
        st.weekIndex = 17;
        st.setWorkingPressure(27, PressureClockTest.at(0, 0));
        int month = TrainerTab.monthIndexNow(m, NOW);
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = st.level;
        in.monthIndex = month;
        in.pressureKpa = st.pressureKpa;
        in.ceilKpa = 40;
        in.firstDeloadPending = false;
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, month, NOW, in);
        assertEquals(2, in.gateHeldTrainingWeeks);
        assertEquals(20.0, in.netTupMin, 1e-9);
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_LEVEL_UP, d.action, d.rule);

        // The same days with no expansion delivered: no proposal.
        Model none = twoWeeks("g", 10, 10, false);
        none.trainerMonthsPumping = 4;
        Plan.Inputs in2 = new Plan.Inputs();
        in2.track = Plan.TRACK_GIRTH_INTERVAL;
        in2.level = Plan.L1;
        in2.monthIndex = month;
        in2.pressureKpa = 27;
        in2.ceilKpa = 40;
        in2.firstDeloadPending = false;
        TrainerTab.fillGirthInputs(none, Plan.TRACK_GIRTH_INTERVAL, st, month, NOW, in2);
        assertNotEquals(Plan.ACTION_LEVEL_UP, Plan.evaluate(in2).action);
    }

    /* ========================================== 2. whole kPa against the level's floor */

    @Test void eightInHgAsStoredPlacesAtTheMonthsLevel() {
        for (int style : new int[]{ Plan.TRACK_GIRTH_INTERVAL, Plan.TRACK_GIRTH_TRADITIONAL }) {
            for (int months : new int[]{ 6, 12 }) {
                String at = "style " + style + ", " + months + " months";
                TrainerTab.Derived d = TrainerTab.deriveGirth(style, months, 27.0, false,
                                                              40, 0);
                assertFalse(d.pressureCapped, at + ": 27 kPa has reached the 27 kPa floor");
                assertFalse(d.conflict, at);
                assertEquals(d.monthsImpliedLevel, d.level, at);
                assertTrue(d.level >= Plan.L2, at + ": placed at Level " + d.level);
                assertEquals(27.0, d.pressureKpa, 1e-9, at + ": kept, not lifted to 27.09");
                // The setup's split: the plan starts at the answer, no stray offset.
                double plan = Scale.setupPlanKpa(style, d.level, d.monthIndex, d.pressureKpa);
                assertEquals(27.0, plan, 1e-9, at);
                assertEquals(0.0, Scale.offsetFromAnswer(27.0, plan), 0.0, at);
                assertEquals("", Scale.setupAnswerNote(plan, Scale.offsetFromAnswer(27, plan),
                    false, d.monthIndex), at + ": the answer is the plan's own - nothing to say");

                // One whole kPa under is still under - the Level 1 band, the conflict named.
                TrainerTab.Derived below = TrainerTab.deriveGirth(style, months, 26.0, false,
                                                                  40, 0);
                assertTrue(below.pressureCapped, at);
                assertEquals(Plan.L1, below.level, at);
                assertTrue(below.conflict, at);
                // And above it, as before.
                TrainerTab.Derived above = TrainerTab.deriveGirth(style, months, 29.0, false,
                                                                  40, 0);
                assertEquals(d.level, above.level, at);
                assertEquals(29.0, above.pressureKpa, 1e-9, at);

                // The first routine at that position is the plan's own: 27 kPa, and a fresh
                // build never reads as edited.
                Model m = new Model();
                m.ceilKpa = 40;
                m.trainerGirthStyle = style;
                Model.TrainerTrackState st = m.trainerGirth;
                st.level = d.level;
                st.weekIndex = d.weekIndex;
                st.setWorkingPressure(plan, PressureClockTest.at(0, 7));
                long now = PressureClockTest.at(0, 9);
                Mint.Rx rx = TrainerTab.trackRx(m, style, st, d.monthIndex, null);
                assertEquals(27, rx.pressureKpa, at);
                Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                String sig = Mint.signature(rx, m.mintShapeTag(style, st.level, now));
                assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, null, now), at);
            }
        }
        // Level 1 placements are unchanged: 17 kPa is 17, 14 is lifted to the 5 hg floor.
        assertEquals(17.0, TrainerTab.deriveGirth(Plan.TRACK_GIRTH_INTERVAL, 2, 17.0, false,
            40, 0).pressureKpa, 1e-9);
        assertEquals(Plan.L1_FLOOR_KPA, TrainerTab.deriveGirth(Plan.TRACK_GIRTH_INTERVAL, 2,
            14.0, false, 40, 0).pressureKpa, 1e-9);
    }

    @Test void reachingIsOneRuleForTheFloorAndTheGate() {
        assertTrue(Plan.pressureReachedKpa(27, Plan.L234_FLOOR_KPA));
        assertFalse(Plan.pressureReachedKpa(26, Plan.L234_FLOOR_KPA));
        assertTrue(Plan.pressureReachedKpa(Plan.L234_FLOOR_KPA, Plan.L234_FLOOR_KPA));
        assertTrue(Plan.pressureReachedKpa(17, Plan.L1_FLOOR_KPA));
        assertFalse(Plan.pressureReachedKpa(16, Plan.L1_FLOOR_KPA));
        // The gate's hg form is the same rule, unchanged.
        for (int kpa = 10; kpa <= 40; kpa++)
            for (double hg : new double[]{ 5, 6, 7, 8, 10 })
                assertEquals(Plan.pressureReachedKpa(kpa, hg * HG),
                             Plan.pressureReachedHg(kpa, hg), kpa + " kPa, " + hg + " hg");
        assertTrue(Plan.pressureReachedHg(27, Plan.L1_GATE_PRESSURE_HG));
        assertFalse(Plan.pressureReachedHg(26, Plan.L1_GATE_PRESSURE_HG));
    }

    @Test void aLevelStartedAt27StepsAsOneStartedAtTheFloor() {
        // The raises from 27 and from 27.09 land on the same whole kPa, and stop at the same
        // top (the 34 kPa of 10 inHg, D10, untouched).
        double a = 27.0, b = Plan.L234_FLOOR_KPA;
        for (int step = 0; step < 4; step++) {
            Plan.Decision da = Plan.evaluate(PressureClockTest.ready(Plan.L3, 7, a));
            Plan.Decision db = Plan.evaluate(PressureClockTest.ready(Plan.L3, 7, b));
            assertEquals(db.action, da.action, "step " + step);
            Mint.Rx ra = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 1, a, 7, 40, 0, da);
            Mint.Rx rb = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 1, b, 7, 40, 0, db);
            assertEquals(rb.pressureKpa, ra.pressureKpa, "step " + step);
            if (da.action != Plan.ACTION_RAISE_PRESSURE) {
                assertEquals(34, ra.pressureKpa, "held at the level's top");
                return;
            }
            a = da.pressureKpa;
            b = db.pressureKpa;
        }
        throw new AssertionError("never reached the level's top");
    }

    /* =================================== 3. length without a pulling cylinder levels up */

    static Plan.Inputs length(int level, int month, int fit) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = level;
        in.monthIndex = month;
        in.pressureKpa = 6.0 * HG;
        in.ceilKpa = 40;
        in.firstDeloadPending = false;
        in.fitState = fit;
        in.girthCm = fit == Traction.FIT_UNKNOWN ? 0 : 12.7;
        in.loadLb = Plan.LENGTH_LOAD_START_LB;
        in.strainSets = Plan.LENGTH_STRAIN_SETS_MAX;
        in.lengthTrainingWeeks = 4 * month;
        in.strainPct = Double.NaN;
        in.fatiguePct = Double.NaN;
        in.trainingWeeksAtPressure = 0;
        in.hasYieldData = false;
        return in;
    }

    static final int[] NO_PULL = { Traction.FIT_UNKNOWN, Traction.FIT_TOO_TIGHT,
        Traction.FIT_GIRTH_IDEAL, Traction.FIT_GIRTH_OVERSIZE, Traction.FIT_TOO_LOOSE };

    @Test void plainLengthMovesUpAtMonthsThreeSixAndTwelve() {
        int[][] gates = { { Plan.L1, 3 }, { Plan.L2, 6 }, { Plan.L3, 12 } };
        for (int fit : NO_PULL) {
            for (int[] g : gates) {
                String at = "fit " + fit + ", Level " + g[0] + ", month " + g[1];
                Plan.Decision before = Plan.evaluate(length(g[0], g[1] - 1, fit));
                assertNotEquals(Plan.ACTION_LEVEL_UP, before.action, at + " - 1: not yet");
                Plan.Decision d = Plan.evaluate(length(g[0], g[1], fit));
                assertEquals(Plan.ACTION_LEVEL_UP, d.action, at + ": " + d.rule);
                // The same offer, word for word, as with a length tube in the rack.
                Plan.Decision pulling = Plan.evaluate(length(g[0], g[1], Traction.FIT_TRACTION));
                assertEquals(pulling.action, d.action, at);
                assertEquals(pulling.rule, d.rule, at);
                assertEquals(pulling.reason, d.reason, at);
                assertEquals(Plan.lengthGateMetNextLevel(g[0], g[1]), g[0] + 1, at);
                // Never on a reduced day back from a rest: it waits, as every track does.
                Plan.Inputs back = length(g[0], g[1], fit);
                back.returnRunsUnder = true;
                Plan.Decision w = Plan.evaluate(back);
                assertEquals(Plan.ACTION_HOLD, w.action, at);
                assertEquals(Plan.levelUpWaitsForReturn(g[0] + 1).reason, w.reason, at);
            }
            // Level 4 has nowhere to go: the rung is the expansion-only hold (or its creep).
            Plan.Decision top = Plan.evaluate(length(Plan.L4, 20, fit));
            assertEquals(Plan.ACTION_HOLD, top.action);
            assertTrue(top.reason.indexOf("expansion only") >= 0, top.reason);
            Plan.Inputs creep = length(Plan.L4, 20, fit);
            creep.trainingWeeksAtPressure = 1;
            assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(creep).action,
                "the expansion's monthly creep still runs when no gate is due");
        }
    }

    @Test void afterTheTapTheExpansionRunsAtThatLevelsTopMovedByTheOffset() {
        for (int level = Plan.L1; level <= Plan.L4; level++) {
            for (double offHg : new double[]{ -1, 0, 1 }) {
                Model m = new Model();
                m.ceilKpa = 57;
                m.trainerLengthOn = true;
                Model.TrainerTrackState st = m.trainerLength;
                st.level = level;                               // as LevelUpTap moves it
                st.setWorkingPressure(Plan.LENGTH_SOFT_CAP_HI_KPA, PressureClockTest.at(0, 0));
                st.offsetKpa = offHg * HG;
                int month = 13;
                Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_LENGTH, st, month, null);
                int top = Scale.effectiveTopWholeKpa(m, Plan.TRACK_LENGTH, level, month);
                String at = "Level " + level + ", offset " + offHg + " inHg";
                assertEquals(top, rx.pressureKpa, at + ": the plan at its top, plus the offset");
                assertEquals((int) Math.round(Scale.usualTopKpa(Plan.TRACK_LENGTH, level, month)
                    + offHg * HG), top, at);
                Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                assertFalse(Say.isTraction(r), at + ": no cylinder that pulls - expansion");
                assertTrue(m.workPeakKpa(r) <= top, at + ": peak " + m.workPeakKpa(r));
                long now = PressureClockTest.at(0, 9);
                String sig = Mint.signature(rx, m.mintShapeTag(Plan.TRACK_LENGTH, level, now));
                assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, null, now),
                    at + ": a fresh build never reads as edited");
            }
        }
    }
}
