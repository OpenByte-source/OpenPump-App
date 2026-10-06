package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE OWNER'S CALLS ON LEVEL CROSSINGS (2026-09-27), pinned against the engine.
 *
 * A level-up proposal waits while the gentle return still runs under the working pressure,
 * on every track and level, and comes on the first full day. A crossing restarts the
 * pressure count only when it changes the working pressure. Levels 3 and 4 need the month
 * AND the volume.
 */
class LevelCallsTest {

    static final double HG = Plan.HG;

    /** Interval girth, Level 1, the gate met and held. */
    static Plan.Inputs l1GateMet() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL; in.level = Plan.L1; in.monthIndex = 4;
        in.netTupMin = 20.0; in.pressureKpa = 27; in.firstDeloadPending = false;
        in.gateHeldTrainingWeeks = Plan.L1_GATE_HOLD_TRAINING_WEEKS;
        in.ownTargetsMet = true;
        in.ceilKpa = 200;
        return in;
    }

    /* ---------------------------------------------- the proposal waits for the full day */

    @Test void aMetGateWaitsWhileTheReturnRunsUnder() {
        assertEquals(Plan.ACTION_LEVEL_UP, Plan.evaluate(l1GateMet()).action);
        Plan.Inputs in = l1GateMet();
        in.returnRunsUnder = true;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action, d.reason);
        assertTrue(d.reason.contains("first full day"), d.reason);
        assertTrue(d.reason.contains("Level 2"), d.reason);
    }

    @Test void everyTrackAndLevelWaits() {
        Plan.Inputs trad = l1GateMet();
        trad.track = Plan.TRACK_GIRTH_TRADITIONAL; trad.netTupMin = 10;
        assertEquals(Plan.ACTION_LEVEL_UP, Plan.evaluate(trad).action);
        trad.returnRunsUnder = true;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(trad).action, "traditional");

        Plan.Inputs len = new Plan.Inputs();
        len.track = Plan.TRACK_LENGTH; len.level = Plan.L1; len.monthIndex = 3;
        len.firstDeloadPending = false;
        len.fitState = Traction.FIT_TRACTION; len.girthCm = 12.7;
        len.strainPct = 5.0; len.fatiguePct = 4.0;
        len.loadLb = Plan.LENGTH_LOAD_START_LB; len.strainSets = Plan.LENGTH_STRAIN_SETS_START;
        assertEquals(Plan.ACTION_LEVEL_UP, Plan.evaluate(len).action, "length month 3");
        len.returnRunsUnder = true;
        Plan.Decision ld = Plan.evaluate(len);
        assertEquals(Plan.ACTION_HOLD, ld.action, "length waits too: " + ld.reason);
        assertTrue(ld.reason.contains("first full day"), ld.reason);
    }

    @Test void theWaitIsAHoldNeverARaise() {
        // A pressure step is held by the open return anyway; the wait must not become one.
        Plan.Inputs in = l1GateMet();
        in.pressureKpa = 24; in.trainingWeeksAtPressure = 5;
        in.returnRunsUnder = true; in.gentleReturnOpen = true;
        assertFalse(Plan.evaluate(in).action == Plan.ACTION_RAISE_PRESSURE);
    }

    /** The return, on the model: armed at the deload's end, two reduced days, then full. */
    @Test void returnRunsUnderUntilTheFirstFullDay() {
        Model m = PressureClockTest.model();
        long end = PressureClockTest.at(7, 0);          // the deload week ends Monday 00:00
        Deload.arm(m, end);
        assertTrue(TrainerTab.returnRunsUnder(m, PressureClockTest.at(7, 8)), "day 1 back");
        Deload.onFiled(m, PressureClockTest.at(7, 9));  // day 1 run
        long wed = PressureClockTest.at(9, 8);
        Deload.settle(m, Summary.dayNumber(wed));
        assertTrue(TrainerTab.returnRunsUnder(m, wed), "day 2 back still runs under");
        Deload.onFiled(m, PressureClockTest.at(9, 9));  // day 2 run
        long fri = PressureClockTest.at(11, 8);
        Deload.settle(m, Summary.dayNumber(fri));
        assertTrue(Deload.armed(m), "open until the full day is filed");
        assertFalse(TrainerTab.returnRunsUnder(m, fri),
            "the first full day: the proposal comes this morning");
        Deload.onFiled(m, PressureClockTest.at(11, 9));
        assertFalse(Deload.armed(m));
        assertFalse(TrainerTab.returnRunsUnder(m, PressureClockTest.at(14, 8)));
    }

    /* ------------------------ the pressure count restarts only when the pressure changes */

    static Model.TrainerTrackState track(int level, double kpa, long since) {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = level; st.weekIndex = level == Plan.L2 ? 9 : 0;
        st.carriedSets = level >= Plan.L3 ? 13 : 0;
        st.setWorkingPressure(kpa, since);
        st.lastMintSig = "old"; st.lastMintId = "r"; st.lastMintMs = since;
        return st;
    }

    @Test void levelTwoToThreeKeepsTheCountAndThePressure() {
        long since = PressureClockTest.at(0, 8), now = PressureClockTest.at(30, 8);
        Model.TrainerTrackState st = track(Plan.L2, 30, since);
        int before = Mint.totalSets(Plan.TRACK_GIRTH_INTERVAL, st);
        Mint.crossGirthLevel(st, Plan.TRACK_GIRTH_INTERVAL, Plan.L3, now);
        assertEquals(Plan.L3, st.level);
        assertEquals(30.0, st.pressureKpa, 1e-9, "the pressure carries");
        assertEquals(since, st.pressureSinceMs, "same pressure, the count carries on");
        assertEquals(before, Mint.totalSets(Plan.TRACK_GIRTH_INTERVAL, st), "sets carry");
        assertEquals("", st.lastMintSig, "the new level's routine is offered");
    }

    @Test void levelThreeToFourKeepsTheCount() {
        long since = PressureClockTest.at(0, 8), now = PressureClockTest.at(30, 8);
        Model.TrainerTrackState st = track(Plan.L3, 33, since);
        Mint.crossGirthLevel(st, Plan.TRACK_GIRTH_INTERVAL, Plan.L4, now);
        assertEquals(Plan.L4, st.level);
        assertEquals(33.0, st.pressureKpa, 1e-9);
        assertEquals(since, st.pressureSinceMs);
    }

    @Test void levelOneToTwoRestartsOnlyIfTheFloorMovesThePressure() {
        long since = PressureClockTest.at(0, 8), now = PressureClockTest.at(30, 8);
        Model.TrainerTrackState at8 = track(Plan.L1, 27, since);
        Mint.crossGirthLevel(at8, Plan.TRACK_GIRTH_INTERVAL, Plan.L2, now);
        assertEquals(Plan.L2, at8.level);
        assertEquals(27, Math.round(at8.pressureKpa), "8 hg, the same whole kPa");
        assertEquals(since, at8.pressureSinceMs, "27 -> 8 hg is no change at the wire");
        Model.TrainerTrackState below = track(Plan.L1, 24, since);
        Mint.crossGirthLevel(below, Plan.TRACK_GIRTH_INTERVAL, Plan.L2, now);
        assertEquals(Plan.L234_FLOOR_KPA, below.pressureKpa, 1e-9, "the 8 hg floor");
        assertEquals(now, below.pressureSinceMs, "a new pressure starts its own count");
    }

    /* APP-Q1 (the owner, parity run 5): a level-up never lowers the pressure. Level 1 climbed
     * past its usual top toward "Most you will go to" (G2) at 30 kPa; Level 2 started at the
     * 8 hg floor, 27 - a step down on the day the person moved up. */
    @Test void levelOneToTwoNeverLowersThePressure() {
        long since = PressureClockTest.at(0, 8), now = PressureClockTest.at(30, 8);
        Model.TrainerTrackState above = track(Plan.L1, 30, since);
        Mint.crossGirthLevel(above, Plan.TRACK_GIRTH_INTERVAL, Plan.L2, now);
        assertEquals(Plan.L2, above.level);
        assertEquals(30.0, above.pressureKpa, 1e-9, "the higher of 8 hg and the Level 1 plan");
        assertEquals(since, above.pressureSinceMs, "the same pressure: its count carries on");
    }

    @Test void theNextStepAtTheNewLevelStillWaitsForItsOwnConditions() {
        // The count carried, but the step asks the new level's milestone and holds.
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL; in.level = Plan.L3; in.monthIndex = 7;
        in.pressureKpa = 30; in.trainingWeeksAtPressure = 4; in.netTupMin = 19;
        in.hasYieldData = true; in.firstDeloadPending = false; in.ceilKpa = 200;
        assertFalse(Plan.evaluate(in).action == Plan.ACTION_RAISE_PRESSURE,
            "under Level 3's 20 minutes");
        in.netTupMin = 20;
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(in).action);
        in.gentleReturnOpen = true;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(in).action, "the return still holds it");
    }

    /* ------------------------------------ Levels 3 and 4 need the volume, not just the month */

    static Plan.Inputs atLevel(int track, int level, int month, double net, boolean own) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = track; in.level = level; in.monthIndex = month;
        in.pressureKpa = 30; in.netTupMin = net; in.ownTargetsMet = own;
        in.hasYieldData = true; in.firstDeloadPending = false; in.ceilKpa = 200;
        return in;
    }

    @Test void theExitVolumes() {
        assertEquals(30.0, Plan.L2_EXIT_NET_MIN, 1e-9,
            "the Level 2 table's last training row, 15 sets - the guidance's 30 (owner 2026-09-27)");
        assertEquals(15, Plan.lastTrainingWeek(Plan.GIRTH_INTERVAL_L2).sets);
        assertEquals(Plan.L2_EXIT_NET_MIN, Plan.levelExitNetMin(Plan.L2), 1e-9);
        assertEquals(20.0, Plan.levelExitNetMin(Plan.L3), 1e-9, "the guidance's 20-21, low");
        assertEquals(Plan.GIRTH_INTERVAL_L2[Plan.GIRTH_INTERVAL_L2.length - 1].netTupMin,
            Plan.L2_EXIT_NET_MIN, 1e-9);
    }

    @Test void levelTwoToThreeNeedsMonthSixAndTheVolume() {
        int gi = Plan.TRACK_GIRTH_INTERVAL;
        assertFalse(Plan.evaluate(atLevel(gi, Plan.L2, 6, 24, true)).action
            == Plan.ACTION_LEVEL_UP, "month 6 but 24 minutes");
        assertFalse(Plan.evaluate(atLevel(gi, Plan.L2, 6, 28, true)).action
            == Plan.ACTION_LEVEL_UP, "month 6 but 28 minutes - short of the 30");
        assertFalse(Plan.evaluate(atLevel(gi, Plan.L2, 5, 30, true)).action
            == Plan.ACTION_LEVEL_UP, "30 minutes but month 5");
        Plan.Decision d = Plan.evaluate(atLevel(gi, Plan.L2, 6, 30, false));
        assertEquals(Plan.ACTION_LEVEL_UP, d.action, d.reason);
        assertTrue(d.rule.contains("30 min"), d.rule);
        assertTrue(Plan.gateL2toL3(6, true));
        assertFalse(Plan.gateL2toL3(6, false));
    }

    @Test void levelThreeToFourNeedsMonthTwelveAndTheVolumeThenTheFork() {
        int gi = Plan.TRACK_GIRTH_INTERVAL;
        assertFalse(Plan.evaluate(atLevel(gi, Plan.L3, 12, 19, true)).action
            == Plan.ACTION_LEVEL_UP, "month 12 but 19 minutes");
        assertFalse(Plan.evaluate(atLevel(gi, Plan.L3, 11, 26, true)).action
            == Plan.ACTION_LEVEL_UP, "the volume but month 11");
        Plan.Decision d = Plan.evaluate(atLevel(gi, Plan.L3, 12, 20, false));
        assertEquals(Plan.ACTION_LEVEL_UP, d.action, d.reason);
        assertTrue(d.reason.contains("break"), "the month-12 fork still offers the break");
        assertTrue(Plan.gateL3toL4(12, true));
        assertFalse(Plan.gateL3toL4(12, false));
    }

    @Test void traditionalAsksItsOwnPlannedMinutes() {
        int gt = Plan.TRACK_GIRTH_TRADITIONAL;
        assertFalse(Plan.evaluate(atLevel(gt, Plan.L2, 6, 10, false)).action
            == Plan.ACTION_LEVEL_UP);
        assertEquals(Plan.ACTION_LEVEL_UP,
            Plan.evaluate(atLevel(gt, Plan.L2, 6, 10, true)).action,
            "10 minutes, its own plan, is traditional's volume");
        assertEquals(Plan.ACTION_LEVEL_UP,
            Plan.evaluate(atLevel(gt, Plan.L3, 12, 10, true)).action);
        assertFalse(Plan.evaluate(atLevel(gt, Plan.L3, 12, 30, false)).action
            == Plan.ACTION_LEVEL_UP, "the interval minutes don't stand in for its own");
    }

    @Test void lengthStaysCalendarOnly() {
        Plan.Inputs len = new Plan.Inputs();
        len.track = Plan.TRACK_LENGTH; len.level = Plan.L2; len.monthIndex = 6;
        len.firstDeloadPending = false;
        len.fitState = Traction.FIT_TRACTION; len.girthCm = 12.7;
        len.strainPct = 5.0; len.fatiguePct = 4.0;
        len.loadLb = Plan.LENGTH_LOAD_START_LB; len.strainSets = Plan.LENGTH_STRAIN_SETS_START;
        assertEquals(Plan.ACTION_LEVEL_UP, Plan.evaluate(len).action,
            "the guidance names no volume for leaving a length level");
    }

    /** The Level 2 table reaches the exit volume, so the gate can be met by following it:
     *  a row prescribes the 30 minutes and every row after it holds them. */
    @Test void theLevelTwoTableReachesItsExitVolume() {
        int first = -1;
        for (int i = 0; i < Plan.GIRTH_INTERVAL_L2.length; i++) {
            Plan.Week w = Plan.GIRTH_INTERVAL_L2[i];
            if (w.deload) continue;
            assertTrue(w.sets <= 15 && w.netTupMin <= 30.0 + 1e-9, "week " + w.num);
            assertEquals(w.sets * 2.0, w.netTupMin, 1e-9, "2-minute holds, week " + w.num);
            assertTrue(w.pressHgHi <= Plan.WORKING_CAP_KPA / Plan.HG + 1e-9, "week " + w.num);
            if (first < 0 && w.netTupMin + 1e-9 >= Plan.L2_EXIT_NET_MIN) first = i;
            if (first >= 0) assertTrue(w.netTupMin + 1e-9 >= Plan.L2_EXIT_NET_MIN,
                "week " + w.num + " drops below the exit volume after reaching it");
        }
        assertTrue(first >= 0, "no Level 2 row reaches the exit volume");
        assertEquals(32, Plan.GIRTH_INTERVAL_L2[Plan.GIRTH_INTERVAL_L2.length - 1].num);
        // +1 set about every 14 days: never more than one set between training rows.
        int prev = -1;
        for (Plan.Week w : Plan.GIRTH_INTERVAL_L2) {
            if (w.deload) continue;
            if (prev > 0) assertTrue(w.sets - prev == 0 || w.sets - prev == 1, "week " + w.num);
            prev = w.sets;
        }
        // Deload rows every fourth week, where the table already placed them.
        for (Plan.Week w : Plan.GIRTH_INTERVAL_L2)
            assertEquals((w.num - 17) % 4 == 0, w.deload, "week " + w.num);
        // 15 sets run as three blocks of 5, with the 3-minute rest between them.
        assertArrayEquals(new int[]{ 5, 5, 5 }, Mint.workBlocks(15,
            Mint.setsPerBlock(Plan.TRACK_GIRTH_INTERVAL, Plan.L2)));
        assertEquals(180, Mint.REST_SEC);
    }

    /** The worked example, on through Level 2 to the Level 3 gate, through the real engine. */
    @Test void theWorkedTimelineReachesLevelThree() {
        PressureClockTest.Timeline t = PressureClockTest.runTimeline(
            Plan.TRACK_GIRTH_INTERVAL, "g", 120, Plan.L2);
        System.out.println("WORKED TIMELINE TO L3: " + PressureClockTest.describe(t));
        assertEquals(1, t.crossed.size(), PressureClockTest.describe(t));
        assertEquals(PressureClockTest.TIMELINE_GATE_WEEK, t.crossed.get(0)[0],
            "Level 2 from the week its gate was proposed");
        assertEquals(Plan.L3, t.gateToLevel);
        assertEquals(PressureClockTest.TIMELINE_L3_GATE_WEEK, t.gateWeek,
            PressureClockTest.describe(t));
        assertEquals(PressureClockTest.TIMELINE_L3_GATE_MONTH, t.gateMonth,
            PressureClockTest.describe(t));
        assertTrue(t.gateMonth >= Plan.L2_GATE_MONTH, "never before month 6");
        assertEquals(PressureClockTest.timelineToL3Steps(), PressureClockTest.stepsText(t));
        assertEquals("[" + PressureClockTest.TIMELINE_DELOAD_1 + ", "
            + PressureClockTest.TIMELINE_DELOAD_2 + ", " + PressureClockTest.TIMELINE_DELOAD_3
            + ", " + PressureClockTest.TIMELINE_DELOAD_4 + ", "
            + PressureClockTest.TIMELINE_DELOAD_5 + ", " + PressureClockTest.TIMELINE_DELOAD_6
            + ", " + PressureClockTest.TIMELINE_DELOAD_7 + "]", t.deloads.toString());
        assertEquals(0, t.gateDow, "proposed on the Monday: the return closed the week before");
        assertFalse(t.gateOnReducedDay);
    }

    @Test void theWorkedTimelineProposesOnAFullDay() {
        PressureClockTest.Timeline t = PressureClockTest.runTimeline(
            Plan.TRACK_GIRTH_INTERVAL, "g", 80);
        assertTrue(t.gateWeek > 0, PressureClockTest.describe(t));
        assertFalse(t.gateOnReducedDay, "the gate was proposed on a reduced day back");
    }
}
