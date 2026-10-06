package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * B3 - THE SUMMARY COMPARES A SESSION WITH ITS OWN ROUTINE, IN THE COUNT THE APP TIMES BY.
 *
 * Found on a device: a 9:30 routine's summary read "net at pressure 1.4 min of 20.0 min
 * planned · −93%". The 20 minutes was the training LEVEL's per-session goal, not anything
 * that routine planned, so a routine that did what it said read as a 93 % failure.
 *
 * Set timing has two counts (Settings › How a run behaves): BY THE CLOCK, where a set runs
 * its minutes on the wall clock, and AT PRESSURE ONLY, where a set's clock only moves while
 * the cuff is at its target. "Planned" has to be counted the same way as the figure beside
 * it, and the level's goal has to be its own line.
 */
class PlannedTimeTest {

    /* ------------------------------------------------------------------ fixtures */

    /**
     * A hand-built routine shaped like the one on the device: a 2:00 warm-up, a 1:00 rest,
     * then 8 pull-and-drop cycles of 45 s at pressure and 10 s dropped (7:20), plus the
     * default 45 s after-assessment pull - turned on here, as on the device (M4: the test is
     * off until a routine turns it on).
     *
     *   by the clock:        120 + 60 + 440 + 45 = 665 s = 11:05
     *   under pressure:      8 x 45 s = 360 s = 6.0 min (warm-up, rest and drops are not work)
     */
    private static Model.Routine handRoutine(Model m) {
        Model.Set warm = Model.Set.fixed("s-warm", "Ease in", 20, 16, 120, 0, 50, 120);
        Model.Set work = Model.Set.fixed("s-work", "Work", 24, 16, 45, 10, 80, 8 * 55);
        m.sets.add(warm);
        m.sets.add(work);
        Model.Routine r = new Model.Routine();
        r.id = "r-hand";
        r.name = "Pulse Session";
        r.assess.on = true;
        r.stages.add(Model.Stage.of("Warm-up", Model.STAGE_WARM, new String[]{ "s-warm" }));
        r.stages.add(Model.Stage.restOf("Rest", 60));
        r.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "s-work" }));
        m.routines.add(r);
        return r;
    }

    private static Model model() {
        Model m = new Model();
        m.ceilKpa = 40;
        m.trainerEnrolled = true;
        m.trainerGirth.level = Plan.L1;          // the level whose goal is 20.0 min
        return m;
    }

    /** The line a level's net is counted from - its floor less the counting tolerance - the
     *  way SessionActivity#fileSession derives it. */
    private static double line(Model m, int level) {
        double floor = m.netFloorKpa(Plan.floorKpa(level));
        return floor - m.tupCountTolKpa(floor);
    }

    /** A filed session of routine `r`, with the plan stamped the way fileSession stamps it:
     *  counted from the girth level's line when it carries a net, from nothing otherwise. */
    private static Model.Sess filed(Model m, Model.Routine r, long durSec, Double netSec,
                                    boolean completed) {
        Model.Sess s = new Model.Sess();
        s.id = "sess1";
        s.routineId = r == null ? "" : r.id;
        s.routineName = r == null ? "" : r.name;
        s.durSec = durSec;
        s.completed = completed;
        s.netTupSec = netSec;
        PlannedTime.stamp(m, r, s, netSec == null ? 0.0 : line(m, m.trainerGirth.level));
        return s;
    }

    /** A one-off counted toward training afterwards - how a routine outside the plan comes
     *  to carry a net figure (SessionActivity's CountTowardTrainingTap). */
    private static Model.Sess counted(Model m, Model.Routine r, long durSec, double netSec) {
        Model.Sess s = filed(m, r, durSec, Double.valueOf(netSec), true);
        s.countedTrack = Plan.TRACK_GIRTH_INTERVAL;
        return s;
    }

    /* ------------------------------------------------ the defect, reproduced first */

    @Test
    void underPressureComparesWithTheRoutinesOwnPlanNotTheLevelsGoal() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        Model.Sess s = counted(m, r, 501, 84);           // 8:21, 1.4 min at pressure

        PlannedTime.Rows rows = PlannedTime.rows(m, s, true);

        assertEquals("1.4 min of 6.0 min planned   ·   −77%", rows.tupValue,
            "planned is what THIS routine planned under pressure, not the level's 20.0 min");
        assertEquals("time under pressure", rows.tupLabel,
            "the row says which count it is in plain words");
        assertEquals("level 1 target", rows.levelLabel,
            "the level's goal is its own line, tied to the level");
        assertEquals("20.0 min under pressure per session", rows.levelValue);
        assertEquals("8:21", rows.duration,
            "counting under pressure, the clock is stated and not compared");
    }

    /* ------------------------------------------------------------- by the clock */

    @Test
    void byTheClockThePlanIsTheRoutinesDurationAndItSitsOnTheDurationRow() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        Model.Sess s = counted(m, r, 501, 84);

        PlannedTime.Rows rows = PlannedTime.rows(m, s, false);

        assertEquals("8:21 of 11:05 planned   ·   −25%", rows.duration,
            "clock against clock: the elapsed and the routine's duration, rests and warm-up "
            + "in both");
        assertNull(rows.tupLabel, "no second comparison in the other count");
        assertFalse(rows.underPressure);
        assertEquals("level 1 target", rows.levelLabel,
            "the level's goal is still stated, as its own line");
        assertEquals("20.0 min under pressure per session", rows.levelValue,
            "and says it is counted under pressure, the only way the plan counts it");
    }

    @Test
    void byTheClockACompleteRunReadsAsPlanned() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        Model.Sess s = filed(m, r, 667, null, true);      // 2 s of settling over 11:05
        assertEquals("11:07 of 11:05 planned   ·   as planned",
            PlannedTime.rows(m, s, false).duration);
        // Found on the device: a Pulse Session that ran exactly to plan read "9:00 of 8:45
        // planned · +3%". The fifteen seconds were the after-test's vent, which ends when
        // the pump reports the cuff open and which no plan can hold.
        s = filed(m, r, 665 + 15, null, true);
        assertEquals("11:20 of 11:05 planned   ·   as planned",
            PlannedTime.rows(m, s, false).duration);
        assertEquals(Integer.valueOf(0), PlannedTime.rows(m, s, false).pct);
    }

    @Test
    void byTheClockARealExtensionIsNotAbsorbedByTheVentsSlack() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        assertEquals(22L, PlannedTime.CLOCK_SLACK_SEC,
            "the after-test's vent window (20 s) and the timer's settling (2 s)");
        Model.Sess s = filed(m, r, 665 + 30, null, true);  // one +30 s
        assertEquals("11:35 of 11:05 planned   ·   +5%",
            PlannedTime.rows(m, s, false).duration,
            "the smallest deliberate lengthening is +30 s, and it shows");
        s = filed(m, r, 665 - 15, null, false);            // stopped 15 s short
        assertEquals("10:50 of 11:05 planned   ·   \u22122%",
            PlannedTime.rows(m, s, false).duration,
            "a shortfall is never absorbed: stopping is the person's doing");
    }

    @Test
    void byTheClockAPartialRunShowsItsRealShortfall() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        Model.Sess s = filed(m, r, 300, null, false);     // stopped at 5:00
        PlannedTime.Rows rows = PlannedTime.rows(m, s, false);
        assertEquals("5:00 of 11:05 planned   ·   −55%", rows.duration);
        assertEquals(Integer.valueOf(-55), rows.pct);
    }

    @Test
    void byTheClockAnOverRunShowsAPositiveDifference() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        Model.Sess s = filed(m, r, 800, null, true);      // held and extended to 13:20
        assertEquals("13:20 of 11:05 planned   ·   +20%",
            PlannedTime.rows(m, s, false).duration);
    }

    /* ------------------------------------------------------------ under pressure */

    @Test
    void underPressureACompleteRunReadsAsPlanned() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        Model.Sess s = counted(m, r, 700, 360);
        assertEquals("6.0 min of 6.0 min planned   ·   as planned",
            PlannedTime.rows(m, s, true).tupValue);
    }

    @Test
    void underPressureAPartialRunShowsItsRealShortfall() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        Model.Sess s = counted(m, r, 250, 150);
        s.completed = false;
        PlannedTime.Rows rows = PlannedTime.rows(m, s, true);
        assertEquals("2.5 min of 6.0 min planned   ·   −58%", rows.tupValue);
        assertEquals("4:10", rows.duration,
            "the clock is not a second comparison when the count is time under pressure");
    }

    @Test
    void underPressureAnOverRunShowsAPositiveDifference() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        Model.Sess s = counted(m, r, 800, 420);
        assertEquals("7.0 min of 6.0 min planned   ·   +17%",
            PlannedTime.rows(m, s, true).tupValue);
    }

    /* --------------------------------------------- what a routine plans, per count */

    @Test
    void restsWarmUpAndDropsAreClockTimeButNotTimeUnderPressure() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        assertEquals(665, PlannedTime.clockSec(m, r),
            "the clock plan is every preset - warm-up and rest included - plus the "
            + "after-assessment pull, the span a session's elapsed is measured over");
        assertEquals(360, PlannedTime.underPressureSec(m, r, 0.0),
            "under pressure: only the hold half of the work cycles");
    }

    @Test
    void outOfNetStagesAndRestSetsPlanNoTimeUnderPressure() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        Model.Set rest = Model.Set.restOf("s-rest", "Breather", 60);
        m.sets.add(rest);
        Model.Set hold = Model.Set.fixed("s-hold", "Hold", 12, 5, 300, 0, 50, 300);
        m.sets.add(hold);
        r.stages.get(2).setIds.add("s-rest");                      // a rest SET in the work
        Model.Stage fatigue = Model.Stage.of("Fatigue", Model.STAGE_WORK,
                                             new String[]{ "s-work" });
        fatigue.fatigueBlock = true;
        Model.Stage retention = Model.Stage.of("Retention", Model.STAGE_WORK,
                                               new String[]{ "s-hold" });
        retention.retention = true;
        Model.Stage cool = Model.Stage.of("Cool-down", Model.STAGE_COOL,
                                          new String[]{ "s-hold" });
        r.stages.add(fatigue);
        r.stages.add(retention);
        r.stages.add(cool);
        assertEquals(360, PlannedTime.underPressureSec(m, r, 0.0),
            "a rest set, the fatigue block, retention and the cool-down add clock time and "
            + "no planned time under pressure - the same stages net never counts");
        assertEquals(665 + 60 + 440 + 300 + 300, PlannedTime.clockSec(m, r));
    }

    @Test
    void theHoldHalfOfEachCycleIsTheUnderPressurePart() {
        Model.Preset p = new Model.Preset();
        p.up = 24; p.lo = 16; p.uh = 45; p.lh = 10; p.durMs = 120_000L;
        assertEquals(100_000L, PlannedTime.holdPartMs(p),
            "two whole cycles (90 s held) and a 10 s remainder that is still in its hold");
        Model.Preset chunk = new Model.Preset();
        chunk.up = 30; chunk.lo = 30; chunk.uh = 255; chunk.lh = 1; chunk.durMs = 255_000L;
        chunk.cyclePart = true;
        assertEquals(255_000L, PlannedTime.holdPartMs(chunk),
            "a stitched chunk has equal setpoints - it holds for all of it");
        Model.Preset rest = new Model.Preset();
        rest.rest = true; rest.durMs = 60_000L;
        assertEquals(0L, PlannedTime.holdPartMs(rest));
    }

    /* ------------------------------------------ a routine outside the plan, and one in it */

    @Test
    void aRoutineOutsideThePlanHasNoLevelLineAndNoTimeUnderPressureToCompare() {
        Model m = model();
        m.trainerEnrolled = false;
        Model.Routine r = handRoutine(m);
        Model.Sess s = filed(m, r, 501, null, true);   // never scored: no net figure

        PlannedTime.Rows tup = PlannedTime.rows(m, s, true);
        assertNull(tup.levelLabel, "nothing was scored against a level");
        assertNull(tup.tupLabel,
            "no net figure was recorded, so there is no time under pressure to compare");
        assertEquals("8:21 of 11:05 planned   ·   −25%", tup.duration,
            "the clock is the only count this session has, and the row says duration");

        PlannedTime.Rows clock = PlannedTime.rows(m, s, false);
        assertNull(clock.levelLabel);
        assertEquals("8:21 of 11:05 planned   ·   −25%", clock.duration);
    }

    @Test
    void aTrainerRoutinePlansExactlyItsPrescribedNet() {
        Model m = model();
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 2, 29.0, 1,
                                    m.ceilKpa, 10, null);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        assertNotNull(r);
        assertTrue(r.netTargetMin > 0);
        assertEquals(Math.round(r.netTargetMin * 60.0),
            PlannedTime.underPressureSec(m, r, line(m, r.trainerLevel)),
            "the under-pressure plan read off the routine, counted from its level's line, is "
            + "the net the Trainer prescribed - warm-up, fatigue block and drops are out of both");

        long planned = PlannedTime.underPressureSec(m, r, line(m, r.trainerLevel));
        Model.Sess s = filed(m, r, 2600, Double.valueOf(planned * 0.9), true);
        PlannedTime.stamp(m, r, s, line(m, r.trainerLevel));
        s.netTargetMin = Double.valueOf(r.netTargetMin);
        PlannedTime.Rows rows = PlannedTime.rows(m, s, true);
        assertEquals(Say.fmtMin(planned * 0.9 / 60.0) + " min of "
            + Say.fmtMin(planned / 60.0) + " min planned   ·   −10%", rows.tupValue);
        assertEquals("level " + r.trainerLevel + " target", rows.levelLabel,
            "the level STAMPED on the routine, not the track's current one");
    }

    @Test
    void holdsBelowTheLineNetCountsFromPlanNoTimeUnderPressure() {
        // Pulse Session as it is on the device: a 14 kPa Gentle Hold (40 s held, 8 s dropped,
        // 4:00) then a 22 kPa Pulse (4 s held, 3 s dropped, 4:00), in one work stage. Level
        // 1's net counts from its 16.9 kPa floor, so the Gentle Hold can never count.
        Model m = model();
        m.sets.add(Model.Set.fixed("s-gentle", "Gentle Hold", 14, 7, 40, 8, 60, 240));
        m.sets.add(Model.Set.fixed("s-pulse", "Pulse", 22, 12, 4, 3, 80, 240));
        Model.Routine r = new Model.Routine();
        r.id = "r-pulse";
        r.name = "Pulse Session";
        r.stages.add(Model.Stage.of("Work", Model.STAGE_WORK,
                                    new String[]{ "s-gentle", "s-pulse" }));
        m.routines.add(r);

        assertEquals(200 + 138, PlannedTime.underPressureSec(m, r, 0.0),
            "counted from nothing, every hold: 5 x 40 s, and 34 x 4 s plus a last 2 s");
        assertEquals(138, PlannedTime.underPressureSec(m, r, line(m, Plan.L1)),
            "counted from Level 1's line, only the Pulse holds - the Gentle Hold is planned "
            + "below it and could never count");

        Model.Sess s = counted(m, r, 540, 132);           // 9:00, 2.2 min counted
        assertEquals("2.2 min of 2.3 min planned   ·   \u22124%",
            PlannedTime.rows(m, s, true).tupValue,
            "not \"2.2 of 5.6 min planned · -61%\" for a run that did what it planned");
    }

    @Test
    void aSessionFiledBeforeThePlanWasRecordedStillComparesWithItsPrescription() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        r.trainerTrack = Plan.TRACK_GIRTH_INTERVAL;
        r.trainerLevel = Plan.L1;
        Model.Sess s = filed(m, r, 1500, Double.valueOf(18 * 60), true);
        s.plannedSec = 0;
        s.plannedTupSec = null;                         // an old record
        s.netTargetMin = Double.valueOf(20.0);          // what Q2 filed
        PlannedTime.Rows rows = PlannedTime.rows(m, s, true);
        assertEquals("18.0 min of 20.0 min planned   ·   −10%", rows.tupValue,
            "a Trainer session's filed net target IS its routine's under-pressure plan");
        assertEquals("25:00", PlannedTime.rows(m, s, false).duration,
            "no clock plan was recorded, so the clock is stated and not compared");
    }

    @Test
    void aLengthSessionIsStatedAgainstTheLengthTarget() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        r.trainerTrack = Plan.TRACK_LENGTH;
        r.trainerLevel = Plan.L1;
        Model.Sess s = filed(m, r, 700, Double.valueOf(300), true);
        PlannedTime.Rows rows = PlannedTime.rows(m, s, true);
        assertEquals("length target", rows.levelLabel,
            "not the girth level's goal: a length session is scored against the length track");
        assertEquals("10.0 min under pressure per session", rows.levelValue);
    }

    @Test
    void theLevelLineIsNeverCalledPlanned() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        for (boolean tup : new boolean[]{ true, false }) {
            PlannedTime.Rows rows = PlannedTime.rows(m, counted(m, r, 501, 84), tup);
            assertFalse(rows.levelLabel.contains("planned"));
            assertFalse(rows.levelValue.contains("planned"));
        }
    }

    @Test
    void aRunWithNoRoutineHasNoPlanToCompare() {
        Model m = model();
        Model.Sess s = filed(m, null, 90, null, true);
        assertEquals(0L, s.plannedSec);
        assertNull(s.plannedTupSec);
        assertEquals("1:30", PlannedTime.rows(m, s, false).duration);
    }

    @Test
    void underPressureWithNoRecordedPlanStatesTheFigureAlone() {
        Model m = model();
        Model.Routine r = handRoutine(m);
        Model.Sess s = counted(m, r, 501, 84);
        s.plannedTupSec = null;                  // filed before the plan was recorded,
        s.netTargetMin = null;                   // and never a Trainer prescription
        PlannedTime.Rows rows = PlannedTime.rows(m, s, true);
        assertEquals("1.4 min", rows.tupValue,
            "nothing to compare against - the figure, and no invented plan or percentage");
        assertNull(rows.pct);
    }

    /* ------------------------------------------------------------------ the record */

    @Test
    void thePlanSurvivesASaveAndAnOldRecordReadsAsUnknown() throws Exception {
        Model m = model();
        Model.Routine r = handRoutine(m);
        Model.Sess s = filed(m, r, 501, null, true);
        Model.Sess back = Model.Sess.fromJson(new JSONObject(s.toJson().toString()));
        assertEquals(665L, back.plannedSec);
        assertEquals(Double.valueOf(360.0), back.plannedTupSec);

        Model.Sess old = Model.Sess.fromJson(new JSONObject(
            "{\"id\":\"x\",\"ts\":\"1755000000000\",\"dur\":\"600\",\"done\":true}"));
        assertEquals(0L, old.plannedSec, "absent: the clock plan was not recorded");
        assertNull(old.plannedTupSec, "absent: unknown, never a made-up 0");
    }
}
