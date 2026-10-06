package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LEVEL 1 PACING (the owner's decision, 2026-09-26): a pressure step waits for three counting
 * girth weeks since the WORKING PRESSURE last changed - not since the routine was last
 * rewritten. Every gentle-return taper step, week-table set step, shape change and save used
 * to restart the count, so a compliant beginner saw no step before month 4 and reached the
 * Level 2 gate at week 40.
 *
 * The safety conditions the faster pacing must keep, each pinned below: never during a deload
 * week, never while the gentle return is still open, never under a safety flag, never above
 * the level/month cap, the device ceiling or the user's maximum, one step per evaluation, and
 * a deload week never counts as a counting week.
 */
class PressureClockTest {

    static final double HG = Plan.HG;

    /** Monday 2026-01-05 at `hour`:00 local, plus `day` days. */
    static long at(int day, int hour) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.JANUARY, 5, hour, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, day);
        return c.getTimeInMillis();
    }

    static Model model() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerGirthOn = true;
        m.trainerLengthOn = false;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerEnrolledAt = at(0, 0);
        m.trainerMonthsAt = at(0, 0);
        m.trainerMonthsPumping = 0;
        for (String[] x : new String[][] { { "g", "" + Plan.TRACK_GIRTH_INTERVAL },
                                           { "t", "" + Plan.TRACK_GIRTH_TRADITIONAL } }) {
            Model.Routine r = new Model.Routine();
            r.id = x[0];
            r.trainerTrack = Integer.parseInt(x[1]);
            m.routines.add(r);
        }
        return m;
    }

    static Model.Sess file(Model m, String routineId, long ts, double netMin, double targetMin,
                           double peakKpa) {
        Model.Sess s = new Model.Sess();
        s.id = "s" + m.sessLog.all.size();
        s.routineId = routineId;
        s.ts = ts;
        s.completed = true;
        s.netTupSec = netMin * 60.0;
        s.netTargetMin = targetMin;
        s.peakKpa = peakKpa;
        m.sessLog.all.add(0, s);
        return s;
    }

    /** Raise-ready girth inputs: milestone met, three weeks at pressure, nothing in the way. */
    static Plan.Inputs ready(int level, int month, double kpa) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = level;
        in.monthIndex = month;
        in.pressureKpa = kpa;
        in.netTupMin = 30.0;
        in.trainingWeeksAtPressure = 3;
        in.weekIndex = 1;
        in.firstDeloadPending = false;
        return in;
    }

    /* ---------------------------------------------------------------- the clock itself */

    @Test void sameFigureKeepsTheClockADifferentOneRestartsIt() {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.setWorkingPressure(17, at(0, 9));
        assertEquals(at(0, 9), st.pressureSinceMs, "the first write starts the clock");
        st.setWorkingPressure(17.2, at(9, 9));
        assertEquals(at(0, 9), st.pressureSinceMs,
            "a rewrite that writes the same whole kPa back (a taper step, a set step) keeps it");
        st.lastMintMs = at(10, 9);
        assertEquals(at(0, 9), st.pressureSinceMs, "a new mint time is not a new pressure");
        st.setWorkingPressure(20, at(11, 9));
        assertEquals(at(11, 9), st.pressureSinceMs, "a raise restarts it");
        st.setWorkingPressure(19, at(12, 9));
        assertEquals(at(12, 9), st.pressureSinceMs, "so does any other change of the figure");
        st.restartPressureClock(at(13, 9));
        assertEquals(at(13, 9), st.pressureSinceMs, "a level crossing restarts it explicitly");
    }

    @Test void onlySessionsFromTheChangeOnCount() {
        Model m = model();
        // Monday at the old pressure, the change at noon Monday, then Wed and Fri.
        file(m, "g", at(0, 9), 10, 10, 17);
        file(m, "g", at(2, 9), 10, 10, 20);
        file(m, "g", at(4, 9), 10, 10, 20);
        assertEquals(0, TrainerTab.weeksAtPressure(m, Plan.TRACK_GIRTH_INTERVAL, at(0, 12),
            at(6, 20)), "two days at the new pressure are not a week at it");
        assertEquals(1, TrainerTab.weeksAtPressure(m, Plan.TRACK_GIRTH_INTERVAL, at(0, 8),
            at(6, 20)), "three are");
        assertEquals(0, TrainerTab.weeksAtPressure(m, Plan.TRACK_GIRTH_INTERVAL, 0L,
            at(6, 20)), "no clock, no weeks");
    }

    @Test void aDeloadWeekNeverCountsEvenWithThreeLightDaysInIt() {
        Model m = model();
        for (int w = 0; w < 4; w++)
            for (int d = 0; d <= 4; d += 2) file(m, "g", at(7 * w + d, 9), 10, 10, 17);
        long now = at(27, 20);
        assertEquals(4, TrainerTab.weeksAtPressure(m, Plan.TRACK_GIRTH_INTERVAL, at(0, 0), now));
        // Week 2 (days 7-13) was a deload that is no longer the newest one on record; its
        // three light sessions are not training days at this pressure.
        Deload.remember(m, at(7, 0), at(14, 0));
        Deload.remember(m, at(21, 0), at(28, 0));
        assertEquals(2, TrainerTab.weeksAtPressure(m, Plan.TRACK_GIRTH_INTERVAL, at(0, 0), now),
            "both deload weeks drop out - the older one from the kept windows, the newer one "
            + "from the last deload on record");
        assertEquals(2, m.deloadWindows.size());
        // The accumulated count the week table and the cadence read is unchanged by this.
        assertEquals(4, TrainerTab.accumulatedTrainingWeeks(m, Plan.TRACK_GIRTH_INTERVAL,
            at(0, 0), now));
    }

    @Test void aReportThatExtendsTheLastWindowReplacesIt() {
        Model m = model();
        Deload.remember(m, at(7, 0), at(14, 0));
        Deload.remember(m, at(7, 0), at(17, 0));
        assertEquals(1, m.deloadWindows.size());
        assertEquals(at(17, 0), m.deloadWindows.get(0)[1]);
        for (int i = 0; i < 20; i++) Deload.remember(m, at(30 + 10 * i, 0), at(37 + 10 * i, 0));
        assertEquals(Model.DELOAD_WINDOWS_KEPT, m.deloadWindows.size(), "the list is bounded");
    }

    @Test void theRealInputsReadThePressureClockNotTheLastMint() {
        Model m = model();
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L1; st.weekIndex = 17;
        st.setWorkingPressure(20, at(0, 8));
        for (int w = 0; w < 3; w++)
            for (int d = 0; d <= 4; d += 2) file(m, "g", at(7 * w + d, 9), 20, 20, 20);
        st.lastMintMs = at(18, 8);                  // a taper or set rewrite last Friday
        Plan.Inputs in = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 1, at(21, 8), in);
        assertEquals(3, in.trainingWeeksAtPressure,
            "three weeks at 20 kPa, whatever the routine's last rewrite was");
        assertFalse(in.gentleReturnOpen);
        Deload.arm(m, at(21, 0));
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 1, at(21, 8), in);
        assertTrue(in.gentleReturnOpen, "an armed taper is an open gentle return");
    }

    /* ---------------------------------------------------------------- the safety conditions */

    @Test void theControlRaisesOneStep() {
        Plan.Decision d = Plan.evaluate(ready(Plan.L1, 1, 20));
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action);
        assertEquals(20 + Plan.STEP_HG_KPA, d.pressureKpa, 1e-9, "one hg, no more");
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 17, 20, 1, 40, d);
        assertEquals(23, rx.pressureKpa, "one whole-kPa step from 20");
    }

    @Test void neverWhileTheGentleReturnIsOpen() {
        Plan.Inputs in = ready(Plan.L1, 1, 20);
        in.gentleReturnOpen = true;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action);
        assertTrue(d.reason.contains("gentle return"), d.reason);
    }

    @Test void neverInADeloadWeekOrWhenOneIsDue() {
        Plan.Inputs in = ready(Plan.L1, 1, 20);
        in.inDeloadWeek = true;
        assertEquals(Plan.ACTION_DELOAD, Plan.evaluate(in).action);
        Plan.Inputs due = ready(Plan.L1, 1, 20);
        due.accumulatedTrainingWeeks = 3;
        assertEquals(Plan.ACTION_DELOAD, Plan.evaluate(due).action);
    }

    @Test void neverUnderASafetyFlagALayoffOrUnderDelivery() {
        Plan.Inputs red = ready(Plan.L1, 1, 20);
        red.redFlag = true;
        assertEquals(Plan.ACTION_STEP_BACK, Plan.evaluate(red).action);
        Plan.Inputs lay = ready(Plan.L1, 1, 20);
        lay.layoff = true;
        assertEquals(Plan.ACTION_STEP_BACK, Plan.evaluate(lay).action);
        Plan.Inputs und = ready(Plan.L1, 1, 20);
        und.underDelivery = true;
        assertEquals(Plan.ACTION_STEP_BACK, Plan.evaluate(und).action);
    }

    @Test void neverAboveTheLevelOrMonthCap() {
        // Month 0: 6 hg (20 kPa). L1 after: 8 hg (27). L2 and up: 10 hg (34).
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(ready(Plan.L1, 0, 20)).action);
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(ready(Plan.L1, 1, 27)).action);
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(ready(Plan.L2, 5, 34)).action);
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(ready(Plan.L3, 11, 34)).action);
        // Just under a cap the step is cut to the cap, and the prescription rounds to it.
        Plan.Decision d = Plan.evaluate(ready(Plan.L1, 1, 26));
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action);
        assertTrue(d.pressureKpa <= Plan.L1_CAP_KPA + 1e-9);
        assertEquals(27, Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 17, 26, 1, 40, d)
            .pressureKpa);
        Plan.Decision m0 = Plan.evaluate(ready(Plan.L1, 0, 18));
        assertEquals(20, Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 17, 18, 0, 40, m0)
            .pressureKpa, "month 0 is held to 6 hg");
    }

    @Test void neverAboveTheDeviceCeilingOrTheUsersMaximum() {
        Plan.Inputs in = ready(Plan.L1, 1, 20);
        in.ceilKpa = 22;                                   // the user's own maximum
        assertEquals(Plan.ACTION_CEILING_DEADLOCK, Plan.evaluate(in).action,
            "a step the user's maximum cannot reach is refused and said, not taken");
        Plan.Decision d = Plan.evaluate(ready(Plan.L1, 1, 20));
        assertEquals(22, Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 17, 20, 1, 22, d)
            .pressureKpa, "and the prescription is clamped to it whatever the decision says");
        Plan.Decision hi = new Plan.Decision(Plan.ACTION_RAISE_PRESSURE, Plan.TAG_SOURCE, "r",
            "r", 80.0, 0);
        assertTrue(Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 0, 34, 20, 99, hi)
            .pressureKpa <= Plan.absoluteCapWholeKpa(), "the app's absolute ceiling holds");
    }

    @Test void oneStepPerEvaluationBecauseTheStepRestartsTheClock() {
        Model m = model();
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L1; st.weekIndex = 17;
        st.setWorkingPressure(20, at(0, 8));
        for (int w = 0; w < 3; w++)
            for (int d = 0; d <= 4; d += 2) file(m, "g", at(7 * w + d, 9), 20, 20, 20);
        long now = at(21, 8);
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL; in.level = Plan.L1; in.monthIndex = 1;
        in.pressureKpa = st.pressureKpa; in.firstDeloadPending = false;
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 1, now, in);
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action);
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 17, st.pressureKpa, 1,
            40, d);
        st.setWorkingPressure(rx.pressureKpa, now);         // applyPlanTo / saveMint
        Plan.Inputs again = new Plan.Inputs();
        again.track = Plan.TRACK_GIRTH_INTERVAL; again.level = Plan.L1; again.monthIndex = 1;
        again.pressureKpa = st.pressureKpa; again.firstDeloadPending = false;
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, st, 1, now + 60000L, again);
        assertEquals(0, again.trainingWeeksAtPressure);
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(again).action,
            "the next render after a step holds: the clock started again");
    }

    /* ---------------------------------------------------------------- the worked timeline */

    /** What the scenario saw: calendar week (1-based) of each step, and of the gate. */
    static final class Timeline {
        final List<int[]> steps = new ArrayList<int[]>();   // {week, fromKpa, toKpa}
        final List<Integer> deloads = new ArrayList<Integer>();
        int gateWeek = -1;
        int gateMonth = -1;
        int gateDow = -1;                 // 0 Monday, 2 Wednesday, 4 Friday
        boolean gateOnReducedDay = false;
        /** Every proposal the scenario accepted and crossed: {week, month, toLevel}. */
        final List<int[]> crossed = new ArrayList<int[]>();
        /** The level the proposal it stopped at would move to (gateWeek's). */
        int gateToLevel = -1;
        int evaluations = 0;
        /** {week, level, week at the level, sets, net target minutes} of every session filed
         *  at full pressure (TraditionalGrowthTest reads how the holds grew). */
        final List<int[]> sessions = new ArrayList<int[]>();
    }

    /**
     * THE WORKED EXAMPLE, DRIVEN THROUGH THE REAL ENGINE: new to pumping, 5 hg (17 kPa),
     * interval girth, Mon/Wed/Fri every week, each deload taken the Monday it is due (the tap:
     * the gentle return armed for the week after), every session delivers its target, no
     * yield readings. Each training morning does what the app does before a run: settle the
     * taper, advance the week table, assemble the inputs (the plan-wide half as
     * SessionActivity#buildTrackInputs does, the girth half through the same
     * TrainerTab#fillGirthInputs), evaluate, prescribe, write the working pressure back - and
     * then files the session the prescription asked for, spending a taper step.
     *
     * Every evaluation is also checked against the safety conditions, so a scenario that
     * raised in a deload week or during the return would fail here.
     */
    static Timeline runTimeline(int track, String routine, int maxWeeks) {
        return runTimeline(track, routine, maxWeeks, Plan.L1);
    }

    /**
     * ...and on past a gate: every level-up proposal to a level up to {@code crossUpTo} is
     * accepted the way the app's "Move to Level N" does it (Mint#crossGirthLevel) and the
     * same morning is evaluated again at the new level; the run stops at the first proposal
     * beyond it, whose week and month are gateWeek / gateMonth.
     */
    static Timeline runTimeline(int track, String routine, int maxWeeks, int crossUpTo) {
        return runTimeline(track, routine, maxWeeks, crossUpTo, null);
    }

    /** ...from a position `start` sets up on the model before the first morning (its level,
     *  week, pressure, months - an older track, say: TraditionalHalfStartTest). The week
     *  advances from the track's own anchor and the prescription is the track's own
     *  (Mint#advanceFromAnchor(int,Model.TrainerTrackState,int), TrainerTab#trackRx's build-up),
     *  as the app's are; with no build-up they are the figures they always were. */
    static Timeline runTimeline(int track, String routine, int maxWeeks, int crossUpTo,
                                java.util.function.Consumer<Model> start) {
        Model m = model();
        m.trainerGirthStyle = track;
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L1;
        st.weekIndex = 1; st.weekBaseIndex = 1; st.weekBaseMs = at(0, 0);
        st.setWorkingPressure(17, at(0, 0));
        if (start != null) start.accept(m);
        Timeline t = new Timeline();
        for (int week = 1; week <= maxWeeks; week++) {
            for (int dow = 0; dow <= 4; dow += 2) {
                long now = at(7 * (week - 1) + dow, 8);
                long day = Summary.dayNumber(now);
                Deload.settle(m, day);
                int weeks = TrainerTab.accumulatedTrainingWeeks(m, track, st.weekBaseMs, now);
                st.weekIndex = Mint.advanceFromAnchor(track, st,
                    Math.max(0, weeks - st.weekRepeats));
                int month = TrainerTab.monthIndexNow(m, now);
                Plan.Inputs in = new Plan.Inputs();
                in.track = track; in.level = st.level; in.monthIndex = month;
                in.pressureKpa = st.pressureKpa; in.ceilKpa = m.ceilKpa;
                in.layoff = TrainerTab.layoff(m, track, now);
                in.inDeloadWeek = TrainerTab.inDeloadWeek(m, now);
                in.accumulatedTrainingWeeks = TrainerTab.planTrainingWeeks(m,
                    TrainerTab.deloadAnchorMs(m), now);
                in.firstDeloadPending = !m.trainerFirstDeloadTaken;
                in.redFlag = m.trainerState == Model.TRAINER_STATE_SAFETY_FLAG;
                in.returnRunsUnder = TrainerTab.returnRunsUnder(m, now);
                TrainerTab.fillGirthInputs(m, track, st, month, now, in);
                Plan.Decision d = Plan.evaluate(in);
                t.evaluations++;
                if (DEBUG) System.out.println("w" + week + " d" + dow + " m" + month + " row"
                    + st.weekIndex + " p" + st.pressureKpa + " net" + in.netTupMin + " wap"
                    + in.trainingWeeksAtPressure + " acc" + in.accumulatedTrainingWeeks
                    + " dl" + in.inDeloadWeek + " gr" + in.gentleReturnOpen + " -> " + d.action
                    + " " + d.reason);
                if (in.inDeloadWeek || in.gentleReturnOpen || in.redFlag)
                    assertNotEquals(Plan.ACTION_RAISE_PRESSURE, d.action,
                        "week " + week + ": a raise while resting or returning");
                if (in.returnRunsUnder)
                    assertNotEquals(Plan.ACTION_LEVEL_UP, d.action,
                        "week " + week + ": a level-up proposed on a reduced day back");
                assertNotEquals(Plan.ACTION_STEP_BACK, d.action, "week " + week + ": "
                    + d.reason + " - a compliant plan is never stepped back");
                if (in.inDeloadWeek) break;               // resting: no session this week
                if (d.action == Plan.ACTION_DELOAD) {     // due: tapped this morning
                    // Tapped at six, before the morning's render: seven days from the tap
                    // end before next Monday's render whichever way a clock change goes.
                    long tap = now - 2L * 3600000L;
                    Deload.remember(m, tap, tap + Plan.LAYOFF_MS);
                    m.trainerFirstDeloadTaken = true;
                    Deload.arm(m, tap + Plan.LAYOFF_MS);
                    t.deloads.add(week);
                    break;
                }
                if (d.action == Plan.ACTION_LEVEL_UP) {
                    int to = st.level + 1;
                    if (to <= crossUpTo) {
                        t.crossed.add(new int[]{ week, month, to });
                        Mint.crossGirthLevel(st, track, to, now);
                        dow -= 2;                     // the same morning, at the new level
                        continue;
                    }
                    t.gateWeek = week;
                    t.gateMonth = month;
                    t.gateDow = dow;
                    t.gateToLevel = to;
                    t.gateOnReducedDay = Deload.cutHg(m, day) > 0.0;
                    return t;
                }
                Mint.Rx rx = Mint.prescribe(track, st.level, st.weekIndex, st.pressureKpa,
                    month, m.ceilKpa, st.carriedSets, st.yieldSets, d, Scale.Limits.NONE,
                    Mint.buildUpSets(track, st));
                assertTrue(rx.pressureKpa <= Math.round(Plan.pressureCapKpa(st.level, month)),
                    "week " + week + ": above the cap");
                int was = (int) Math.round(st.pressureKpa);
                double wasPlan = st.pressureKpa;
                // As applyPlanTo / saveMint write it back: the plan's figure, exact (t10
                // REAL-15 - it used to be the whole kPa, losing the fraction at every step).
                st.setWorkingPressure(TrainerTab.savedPlanKpa(m, track, rx, false, now), now);
                if (rx.pressureKpa != was) {
                    assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action);
                    // EXPECTATION CHANGED (t10 REAL-15): one step at a time is the PLAN'S
                    // figure moving at most 1 inHg; the whole kPa it commands may move 4 where
                    // the fraction carries (20.3 -> 23.7 kPa runs 20 -> 24), as the editor's
                    // model has it.
                    assertTrue(st.pressureKpa - wasPlan <= Plan.STEP_HG_KPA + 1e-9,
                        "one step at a time");
                    assertTrue(rx.pressureKpa - was <= (int) Math.ceil(Plan.STEP_HG_KPA),
                        "one step at a time on the wire");
                    t.steps.add(new int[]{ week, was, rx.pressureKpa });
                }
                double cut = Deload.cutHg(m, day);
                long ts = at(7 * (week - 1) + dow, 9);
                if (cut > 0.0) {
                    file(m, routine, ts, 0, 0, Mint.reducedKpa(rx.pressureKpa, cut * HG));
                } else {
                    file(m, routine, ts, rx.netTargetMin, rx.netTargetMin, rx.pressureKpa);
                    t.sessions.add(new int[]{ week, st.level, st.weekIndex, rx.sets,
                        (int) Math.round(rx.netTargetMin) });
                }
                Deload.onFiled(m, ts, track, false, true);
            }
        }
        return t;
    }

    static String describe(Timeline t) {
        StringBuilder b = new StringBuilder("deloads " + t.deloads + "; steps");
        for (int[] s : t.steps) b.append(" w").append(s[0]).append(' ').append(s[1])
            .append("->").append(s[2]);
        for (int[] c : t.crossed) b.append("; to L").append(c[2]).append(" w").append(c[0])
            .append(" (month ").append(c[1]).append(')');
        return b.append("; gate week ").append(t.gateWeek).append(" (month ")
            .append(t.gateMonth).append(')').toString();
    }

    @Test void theWorkedTimeline() {
        Timeline t = runTimeline(Plan.TRACK_GIRTH_INTERVAL, "g", 80);
        System.out.println("WORKED TIMELINE (interval, 5 hg, M/W/F): " + describe(t));
        // Pinned, so docs/trainer-guide.md chapter 13 cannot drift from the engine.
        assertEquals(TIMELINE_DELOADS, t.deloads.toString());
        assertEquals(TIMELINE_STEPS, stepsText(t));
        assertEquals(TIMELINE_GATE_WEEK, t.gateWeek, describe(t));
        assertEquals(TIMELINE_GATE_MONTH, t.gateMonth, describe(t));
        // EXPECTATION CHANGED (t10 REAL-15): the gate is met in the week after the week-18
        // return, on its Monday - a full day (the owner's decision, 2026-09-27: never while
        // the gentle return still runs under). It was the Friday after the week-22 deload.
        assertEquals(0, t.gateDow, "proposed on the Monday, a full day");
        assertFalse(t.gateOnReducedDay);
        assertTrue(t.gateWeek < 40, "faster than the week 40 every rewrite used to cost");
        /* Month 4: the plan's figure exact (REAL-15), 6 hg + 1 hg is the table's 7 hg and the
         * pressure phase's one step lands on 8 hg. (Month 5 while 23 kPa counted as 7 hg and
         * 8 hg took one more step.) */
        assertTrue(t.gateMonth <= 4, "Level 1 no later than month 4");
    }

    static String stepsText(Timeline t) {
        StringBuilder b = new StringBuilder();
        for (int[] s : t.steps) {
            if (b.length() > 0) b.append(", ");
            b.append(s[0]).append(':').append(s[1]).append("->").append(s[2]);
        }
        return b.toString();
    }

    static boolean DEBUG = false;
    /* Public, so docs/trainer-guide.md chapter 13 checks its timeline against these
     * (TrainerGuideDocTest markers) - and these are checked against the engine above. */
    /** Calendar weeks of each deload: the guidance's dated 5, 9, 13, 17 - and the gate comes
     *  in the week after its return (week 22's, from month 4 after 4 weeks that count, is
     *  Level 2's: TIMELINE_DELOAD_5). */
    public static final int TIMELINE_DELOAD_1 = 5, TIMELINE_DELOAD_2 = 9,
        TIMELINE_DELOAD_3 = 13, TIMELINE_DELOAD_4 = 17;
    /** The week of each pressure step: 17 -> 20 -> 24 -> 27 kPa (5.0 -> 5.9 -> 7.1 -> 8.0 hg
     *  on the wire; the plan's own figure 5 -> 6 -> 7 -> 8 hg). The first two follow the
     *  guidance's Level 1 table (6 hg, then 7 hg); the last is the pressure phase's +1 hg after
     *  3 counting weeks once the sessions hold 20 minutes (the owner's decisions, 2026-09-27).
     *  EXPECTATION CHANGED (t10 REAL-15): the plan keeps its figure exact, so 6 hg + 1 hg is
     *  the table's 7 hg itself (23.7 kPa, 24 on the wire) and 7 hg + 1 hg is the Level 1 cap -
     *  it was 23 (6.8 hg), then 26, then a fourth step to 27. */
    public static final int TIMELINE_STEP_1 = 4, TIMELINE_STEP_2 = 11, TIMELINE_STEP_3 = 16;
    /** The week the L1 -> L2 gate is proposed, and the trainer month it falls in. It was
     *  week 40 (month 8) while every rewrite restarted the clock, week 31 (month 6) while the
     *  pressure waited for the 20 minutes, and week 23 (month 5) while every step was written
     *  back as the whole kPa (REAL-15). */
    public static final int TIMELINE_GATE_WEEK = 19;
    public static final int TIMELINE_GATE_MONTH = 4;
    /** The week the L2 -> L3 gate is proposed to the same person after "Move to Level 2" in
     *  the gate's week, and its month (LevelCallsTest#theWorkedTimelineReachesLevelThree):
     *  month 6 AND each of the last 3 sessions holding Level 2's 30 minutes, the last rows
     *  of its table (the owner's decisions, 2026-09-27). It was week 34 (month 7) while the
     *  table stopped at 26 minutes, and week 28 (month 6) on the calendar alone. */
    public static final int TIMELINE_L3_GATE_WEEK = 36;
    public static final int TIMELINE_L3_GATE_MONTH = 8;
    /** On the way: Level 2's pressure steps (27 -> 30 -> 34 kPa, 8 -> 9 -> 10 hg, the Level 2
     *  cap: the plan's figure exact, REAL-15) - the first one week after the crossing, because
     *  the count at 27 kPa carried across it - and the deloads of weeks 22, 27 and 32. */
    public static final int TIMELINE_L2_STEP_1 = 20, TIMELINE_L2_STEP_2 = 24;
    public static final int TIMELINE_DELOAD_5 = 22, TIMELINE_DELOAD_6 = 27,
        TIMELINE_DELOAD_7 = 32;
    static String timelineToL3Steps() {
        return TIMELINE_STEPS + ", " + TIMELINE_L2_STEP_1 + ":27->30, " + TIMELINE_L2_STEP_2
            + ":30->34";
    }
    static final String TIMELINE_DELOADS = "[" + TIMELINE_DELOAD_1 + ", " + TIMELINE_DELOAD_2
        + ", " + TIMELINE_DELOAD_3 + ", " + TIMELINE_DELOAD_4 + "]";
    static final String TIMELINE_STEPS = TIMELINE_STEP_1 + ":17->20, " + TIMELINE_STEP_2
        + ":20->24, " + TIMELINE_STEP_3 + ":24->27";
}
