package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TRADITIONAL GIRTH CAN RAISE (the owner's decision, 2026-09-26). Its 2 x 5 min is 10 minutes,
 * and the pressure step asked for the interval milestone (20 minutes at Level 1), so the
 * pressure could never move. For traditional the time condition is now each of the last three
 * scored sessions delivering its OWN routine's planned minutes at pressure. Every other
 * condition is unchanged - the same weeks at pressure, the same caps, the same safety holds.
 */
class TraditionalRaiseTest {

    static Plan.Inputs ready(boolean ownMet, double net) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_TRADITIONAL;
        in.level = Plan.L1;
        in.monthIndex = 1;
        in.pressureKpa = 20;
        in.netTupMin = net;
        in.ownTargetsMet = ownMet;
        in.trainingWeeksAtPressure = 3;
        in.firstDeloadPending = false;
        return in;
    }

    @Test void eachSessionHoldingItsOwnMinutesRaises() {
        Plan.Decision d = Plan.evaluate(ready(true, 10.0));
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action, d.reason);
        assertEquals(20 + Plan.STEP_HG_KPA, d.pressureKpa, 1e-9);
        assertEquals(Plan.TAG_INFERRED, d.tag, "the condition is the app's");
        assertTrue(d.reason.contains("planned time"), d.reason);
    }

    @Test void aSessionShortOfItsOwnMinutesHolds() {
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(ready(false, 10.0)).action);
    }

    @Test void theIntervalTrackStillAsksForItsMilestone() {
        Plan.Inputs in = ready(true, 10.0);
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(in).action,
            "ownTargetsMet is traditional's alone: an interval track at 10 minutes holds");
    }

    @Test void theOtherConditionsAreUnchanged() {
        Plan.Inputs weeks = ready(true, 10.0);
        weeks.trainingWeeksAtPressure = 2;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(weeks).action);
        Plan.Inputs gentle = ready(true, 10.0);
        gentle.gentleReturnOpen = true;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(gentle).action);
        Plan.Inputs deload = ready(true, 10.0);
        deload.inDeloadWeek = true;
        assertEquals(Plan.ACTION_DELOAD, Plan.evaluate(deload).action);
        Plan.Inputs red = ready(true, 10.0);
        red.redFlag = true;
        assertEquals(Plan.ACTION_STEP_BACK, Plan.evaluate(red).action);
        Plan.Inputs cap = ready(true, 10.0);
        cap.pressureKpa = 27;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(cap).action, "the L1 cap");
        Plan.Inputs m0 = ready(true, 10.0);
        m0.monthIndex = 0;
        m0.pressureKpa = 20;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(m0).action, "the month-0 cap");
        Plan.Inputs ceil = ready(true, 10.0);
        ceil.ceilKpa = 22;
        assertEquals(Plan.ACTION_CEILING_DEADLOCK, Plan.evaluate(ceil).action,
            "the user's maximum");
    }

    @Test void theSignalIsEachSessionAgainstItsOwnTarget() {
        // newest first: 10 of 10, 10 of 10, 10 of 10
        assertTrue(TrainerTab.netSignals(new double[]{ 10, 10, 10 },
            new double[]{ 10, 10, 10 }, 10).ownTargetsMet);
        // one short
        assertFalse(TrainerTab.netSignals(new double[]{ 10, 9.5, 10 },
            new double[]{ 10, 10, 10 }, 10).ownTargetsMet);
        // a split half asked for 5 and gave 5 - its own target, not the whole session's
        assertTrue(TrainerTab.netSignals(new double[]{ 5, 10, 10 },
            new double[]{ 5, 10, 10 }, 10).ownTargetsMet);
        // a target that was never recorded is judged against the planned figure
        assertFalse(TrainerTab.netSignals(new double[]{ 10, 10, 8 },
            new double[]{ 10, 10, Double.NaN }, 10).ownTargetsMet);
        // fewer than three sessions: no signal
        assertFalse(TrainerTab.netSignals(new double[]{ 10, 10 },
            new double[]{ 10, 10 }, 10).ownTargetsMet);
    }

    @Test void theRealInputsCarryIt() {
        Model m = PressureClockTest.model();
        m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L1;
        st.setWorkingPressure(20, PressureClockTest.at(0, 8));
        for (int w = 0; w < 3; w++)
            for (int d = 0; d <= 4; d += 2)
                PressureClockTest.file(m, "t", PressureClockTest.at(7 * w + d, 9), 10, 10, 20);
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_TRADITIONAL; in.level = Plan.L1; in.monthIndex = 1;
        in.pressureKpa = 20; in.firstDeloadPending = false;
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_TRADITIONAL, st, 1,
            PressureClockTest.at(21, 8), in);
        assertTrue(in.ownTargetsMet);
        assertEquals(3, in.trainingWeeksAtPressure);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(in).action);
    }

    /* ---- the L1 -> L2 gate on its own planned minutes (the owner's decision, 2026-09-27) */

    static Plan.Inputs atGate(boolean ownMet, double kpa, int held) {
        Plan.Inputs in = ready(ownMet, 10.0);
        in.pressureKpa = kpa;
        in.gateHeldTrainingWeeks = held;
        return in;
    }

    @Test void traditionalLeavesLevelOneOnItsOwnPlannedMinutes() {
        Plan.Decision d = Plan.evaluate(atGate(true, 27, 2));
        assertEquals(Plan.ACTION_LEVEL_UP, d.action, d.reason);
        assertEquals(Plan.TAG_INFERRED, d.tag, "the time condition is the app's");
        assertTrue(d.reason.contains("planned time"), d.reason);
        assertTrue(Plan.gateL1toL2Traditional(true, true, 2));
    }

    @Test void theGateMirrorsTheIntervalGateOtherwise() {
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(atGate(false, 27, 2)).action,
            "a session short of its own minutes");
        assertTrue(Plan.evaluate(atGate(true, 26, 2)).action != Plan.ACTION_LEVEL_UP,
            "short of 8 hg");
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(atGate(true, 27, 1)).action,
            "held one week, not two");
        assertFalse(Plan.gateL1toL2Traditional(true, true, 1));
        assertFalse(Plan.gateL1toL2Traditional(true, false, 2));
        assertFalse(Plan.gateL1toL2Traditional(false, true, 2));
        Plan.Inputs interval = atGate(true, 27, 2);
        interval.track = Plan.TRACK_GIRTH_INTERVAL;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(interval).action,
            "an interval track at 10 minutes still needs its 20");
        Plan.Inputs deload = atGate(true, 27, 2);
        deload.inDeloadWeek = true;
        assertEquals(Plan.ACTION_DELOAD, Plan.evaluate(deload).action);
        Plan.Inputs red = atGate(true, 27, 2);
        red.redFlag = true;
        assertEquals(Plan.ACTION_STEP_BACK, Plan.evaluate(red).action);
    }

    @Test void theHeldWeeksCountSessionsAtTheirOwnMinutesAndEightHg() {
        Model m = PressureClockTest.model();
        m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
        for (int w = 0; w < 2; w++)
            for (int d = 0; d <= 4; d += 2)
                PressureClockTest.file(m, "t", PressureClockTest.at(7 * w + d, 9), 10, 10, 27);
        long now = PressureClockTest.at(14, 8);
        assertEquals(2, TrainerTab.gateHeldTrainingWeeks(m, Plan.TRACK_GIRTH_TRADITIONAL, now));
        assertEquals(3, TrainerTab.ownTargetsHeldOfLast(m, Plan.TRACK_GIRTH_TRADITIONAL));
        // The interval reading of the same sessions: 10 minutes is not 20.
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(m, Plan.TRACK_GIRTH_INTERVAL, now));
        // A short session breaks the run.
        PressureClockTest.file(m, "t", PressureClockTest.at(14, 9), 9, 10, 27);
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(m, Plan.TRACK_GIRTH_TRADITIONAL,
            PressureClockTest.at(15, 8)));
        assertEquals(2, TrainerTab.ownTargetsHeldOfLast(m, Plan.TRACK_GIRTH_TRADITIONAL));
    }

    /** The same compliant person on traditional girth, through the real engine. */
    @Test void theTraditionalTimelineSteps() {
        PressureClockTest.Timeline t = PressureClockTest.runTimeline(
            Plan.TRACK_GIRTH_TRADITIONAL, "t", 40);
        System.out.println("TRADITIONAL TIMELINE (5 hg, M/W/F): " + PressureClockTest.describe(t));
        assertEquals(TRAD_STEPS, PressureClockTest.stepsText(t));
        assertEquals(TRAD_GATE_WEEK, t.gateWeek, PressureClockTest.describe(t));
        assertEquals(TRAD_GATE_MONTH, t.gateMonth, PressureClockTest.describe(t));
    }

    /** The week traditional's L1 -> L2 gate is proposed, and its month - public for the
     *  guide's markers. It could never be proposed before (the 20-minute gate). */
    /* EXPECTATION CHANGED (t10 REAL-15): the plan keeps its figure exact, so from the month-0
     * cap (6 hg) two steps of 1 hg reach 8 hg - three steps in all, and the gate four weeks
     * sooner. It was 17 -> 20 -> 23 -> 26 -> 27, the gate in week 19 (month 4). */
    public static final int TRAD_GATE_WEEK = 15;
    public static final int TRAD_GATE_MONTH = 3;

    /** The weeks of traditional's steps from 5 hg, public for the guide's markers: 17 -> 20
     *  (the month-0 cap) -> 24 -> 27 kPa (the plan's 6, 7 and 8 hg), one per deload cycle. */
    public static final int TRAD_STEP_1 = 4, TRAD_STEP_2 = 8, TRAD_STEP_3 = 12;
    static final String TRAD_STEPS = TRAD_STEP_1 + ":17->20, " + TRAD_STEP_2 + ":20->24, "
        + TRAD_STEP_3 + ":24->27";
}
