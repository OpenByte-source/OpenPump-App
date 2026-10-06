package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * FIX11 - R11-4 WAS NOT REACHED ON THE DEVICE (EMU12 F8). The walk built 11 weeks of two girth
 * sessions (Tue and Thu), one rising standardised at-rest reading a week, a newest yield of
 * 8 % and the pressure unchanged since Sep 7 - and the plan recorded no girth decision, and
 * the week strip did not count the stored Tue 29 Sep session.
 *
 * THE TEST DATA, NOT THE APP. Reproduced from the walk's model (emu12w/m_brake2.json), two
 * things in it stop the brake, each for a reason the app is right about:
 *
 *  1. Every one of the 22 sessions was a copy of one real session with only its time changed,
 *     so every one kept that session's day key, 20261001 (Thu 1 Oct). A session's day is its
 *     day key (TrainerTab#sessionDay - the day it was filed for), so each week's Tuesday and
 *     Thursday read as ONE day: one day, one full session, never a counted week. No counted
 *     week means no pressure clock (0 of 3 weeks) and no at-rest series - and the Tue 29 Sep
 *     copy sat on Thursday's day, which is why the strip did not show it on Tuesday.
 *  2. The 8 % reading's pair was filed "not comparable" (Sess#afterComparable false, "acmp":
 *     false in the file): such a reading neither counts toward the yield nor breaks it
 *     (TrainerTab#yieldLeftOut, R-24), so the newest counted yield was none - not on target.
 *
 * With the same data and each session's own day key and a comparable pair, the plan's girth
 * decision is the brake and the card offers "You're gaining, so the next step waits until
 * Mon 12 Oct. Step up now?" - tested below end to end, with both answers.
 */
class GainBrakeOfferTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    private static long at(int month, int day, int hour) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, month, day, hour, 0, 0);
        return c.getTimeInMillis();
    }

    /** The walk's model, as built: Level 3 interval girth at 30 kPa since Mon 7 Sep, enrolled
     *  Sun 19 Jul, Tue and Thu sessions of 20 net minutes from Tue 21 Jul to Thu 1 Oct, a
     *  standardised at-rest girth reading each Tuesday rising 11.50 to 12.00, and the newest
     *  session's 8 % yield. `ownDays` and `comparable` are the two things the walk's copy got
     *  wrong. */
    private static Model walk(boolean ownDays, boolean comparable) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = at(Calendar.JULY, 19, 0);
        m.trainerGirthOn = true;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerMonthsPumping = 7;
        m.ceilKpa = 43;
        // Its week: Tue, Thu and Sat, the last day Saturday (Schedule#lastWeekdayFor).
        for (int i = 0; i < m.sched.days.length; i++) m.sched.days[i] = i == 1 || i == 3 || i == 5;
        Model.Routine r = new Model.Routine();
        r.id = "rg";
        r.name = "Girth L3";
        r.trainerTrack = Plan.TRACK_GIRTH_INTERVAL;
        m.routines.add(r);
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L3;
        st.weekIndex = 1;
        st.lastMintId = "rg";
        st.setWorkingPressure(30, at(Calendar.SEPTEMBER, 7, 0));
        long firstTue = at(Calendar.JULY, 21, 19);
        int copiedKey = PhotoCalendar.dayKey(at(Calendar.OCTOBER, 1, 19));
        for (int w = 0; w < 11; w++) {
            for (int d = 0; d <= 2; d += 2) {
                Model.Sess s = new Model.Sess();
                s.ts = firstTue + w * 7 * DAY + d * DAY;
                s.durSec = 2455;
                s.id = "s" + s.ts;
                s.routineId = "rg";
                s.routineName = r.name;
                s.completed = true;
                s.netTupSec = Double.valueOf(1200.0);
                s.netTargetMin = Double.valueOf(20.0);
                s.dayKey = ownDays ? PhotoCalendar.dayKey(s.ts) : copiedKey;
                if (w == 10 && d == 2) {             // Thu 1 Oct: the 8 % reading
                    s.afterGirCm = Double.valueOf(0.96);
                    s.afterGirAbsCm = Double.valueOf(12.96);
                    s.afterBaseThisSession = true;
                    s.afterComparable = comparable;
                }
                m.sessLog.file(s);
            }
            Model.Reading at = new Model.Reading();
            at.ts = firstTue + w * 7 * DAY - 3600000L;
            at.method = Model.Reading.METHOD_STANDARDIZED;
            at.phase = Model.Reading.PHASE_PRE;
            at.gir = 11.5 + 0.05 * w;
            at.len = 15.2;
            m.measLog.all.add(at);
        }
        return m;
    }

    /** Sun 4 Oct, the walk's morning. */
    private static final long NOW = at(Calendar.OCTOBER, 4, 9);

    private static Plan.Decision decide(Model m) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = m.trainerGirth.level;
        in.monthIndex = 7;
        in.pressureKpa = m.trainerGirth.pressureKpa;
        in.ceilKpa = m.ceilKpa;
        in.firstDeloadPending = false;
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 7, NOW, in);
        return Plan.evaluate(in);
    }

    @Test
    void theWalksCopiedSessionsNeverMadeACountedWeekAndItsReadingWasLeftOut() {
        Model m = walk(false, false);
        assertTrue(TrainerTab.countedWeekStarts(m, Plan.TRACK_GIRTH_INTERVAL,
            m.trainerEnrolledAt, NOW).isEmpty(), "each week's Tue and Thu are one day: 1 Oct");
        assertEquals(0, TrainerTab.weeksAtPressure(m, Plan.TRACK_GIRTH_INTERVAL,
            m.trainerGirth.pressureSinceMs, NOW), "the device's \"0 of 3 training weeks\"");
        assertTrue(GainBrake.atRestSeries(m, Plan.TRACK_GIRTH_INTERVAL, NOW).isEmpty());
        assertEquals(-1, GainBrake.kindOf(decide(m)), "no brake: nothing to brake");
        // Each defect alone is enough.
        assertFalse(TrainerTab.latestYieldOnTarget(walk(true, false), Plan.TRACK_GIRTH_INTERVAL,
            Plan.L3), "a pair not comparable is left out: no yield on target");
        assertEquals(-1, GainBrake.kindOf(decide(walk(true, false))));
        assertEquals(-1, GainBrake.kindOf(decide(walk(false, true))));
    }

    @Test
    void withItsOwnDaysTheTuesdaySessionCountsOnTuesday() {
        Model m = walk(true, true);
        TrainingWeek.Tally t = TrainerTab.weekTally(m, Plan.TRACK_GIRTH_INTERVAL,
            at(Calendar.OCTOBER, 1, 22));
        assertEquals(2, t.days, "Tue 29 Sep and Thu 1 Oct, two days");
        assertEquals(11, TrainerTab.countedWeekStarts(m, Plan.TRACK_GIRTH_INTERVAL,
            m.trainerEnrolledAt, NOW).size());
    }

    @Test
    void theOfferAppearsAndStepUpNowGivesTheNormalStep() {
        Model m = walk(true, true);
        Model.TrainerTrackState st = m.trainerGirth;
        Plan.Decision d = decide(m);
        assertEquals(GainBrake.KIND_PRESSURE, GainBrake.kindOf(d), "the step waits");
        assertEquals(Plan.ACTION_HOLD, d.action);
        GainBrake.Card c = GainBrake.card(m, Plan.TRACK_GIRTH_INTERVAL, st, d, NOW);
        assertNotNull(c);
        assertTrue(c.offer, "[Step up now] [Wait]");
        String until = GainBrake.untilWords(m, Plan.TRACK_GIRTH_INTERVAL, st,
            GainBrake.KIND_PRESSURE, NOW);
        assertEquals("You're gaining, so the next step waits until " + until + ". Step up now?",
            c.words);
        // Step up now: the next evaluation gives the normal +1 hg step, to be confirmed as usual.
        GainBrake.answer(st, GainBrake.clockKey(st, GainBrake.KIND_PRESSURE),
            GainBrake.ANSWER_STEP_UP);
        Plan.Decision up = decide(m);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, up.action);
        assertEquals(30 + Plan.STEP_HG_KPA, up.pressureKpa, 1e-9);
        assertNull(GainBrake.card(m, Plan.TRACK_GIRTH_INTERVAL, st, up, NOW),
            "no brake card over the step itself");
        // The answer is kept through a save.
        Model back = Model.fromJson(m.toJson());
        assertEquals(Plan.ACTION_RAISE_PRESSURE, decide(back).action);
    }

    @Test
    void waitKeepsTheSlowerPaceAndTheCardSaysUntilWhen() {
        Model m = walk(true, true);
        Model.TrainerTrackState st = m.trainerGirth;
        GainBrake.answer(st, GainBrake.clockKey(st, GainBrake.KIND_PRESSURE),
            GainBrake.ANSWER_WAIT);
        Plan.Decision d = decide(m);
        assertEquals(GainBrake.KIND_PRESSURE, GainBrake.kindOf(d), "still waiting");
        GainBrake.Card c = GainBrake.card(m, Plan.TRACK_GIRTH_INTERVAL, st, d, NOW);
        assertNotNull(c);
        assertFalse(c.offer, "answered: no buttons");
        assertTrue(c.words.startsWith("You're gaining, so the next step waits until "));
        assertFalse(c.words.contains("Step up now?"));
        // A new pressure starts a new clock: the next step is asked afresh.
        st.setWorkingPressure(31, NOW);
        assertFalse(GainBrake.answered(st, GainBrake.clockKey(st, GainBrake.KIND_PRESSURE)));
    }

    /**
     * FIX11 (device recheck EMU13, High) - WITH THE ROUTINE SAVED AS THE PLAN WROTE IT, THE ROW
     * AND ITS OFFER SHOW. The brake's decision is a hold, and a hold whose routine is saved and
     * unedited hid the track's row - the only place the offer is drawn - so in normal use it
     * never appeared. Girth and length (TrainerScreen#trackSpeaks asks TrainerTab#rowShows for
     * both).
     */
    @Test
    void aSavedPlanRoutineWithAStepThatWaitsStillShowsItsRowAndTheOffer() throws Exception {
        Model m = walk(true, true);
        Plan.Decision d = decide(m);
        assertEquals(GainBrake.KIND_PRESSURE, GainBrake.kindOf(d));
        assertTrue(TrainerTab.rowShows(d, true), "saved and unedited: the row still shows");
        GainBrake.Card c = GainBrake.card(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, d, NOW);
        assertTrue(c.offer, "and in it, the offer");
        assertEquals("You're gaining · step up now?", GainBrake.ROW_OFFER);
        // After Wait the row stays, with the date.
        GainBrake.answer(m.trainerGirth, GainBrake.clockKey(m.trainerGirth,
            GainBrake.KIND_PRESSURE), GainBrake.ANSWER_WAIT);
        Plan.Decision w = decide(m);
        assertTrue(TrainerTab.rowShows(w, true));
        GainBrake.Card cw = GainBrake.card(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, w, NOW);
        assertEquals("Next step waits until " + cw.until, GainBrake.rowWait(cw));
        // The length track's steps that wait, likewise.
        assertTrue(TrainerTab.rowShows(GainBrake.hold(GainBrake.KIND_CLIMB), true));
        assertTrue(TrainerTab.rowShows(GainBrake.hold(GainBrake.KIND_SLOW), true));
        // A plain hold over the saved routine stays quiet, as before.
        assertFalse(TrainerTab.rowShows(new Plan.Decision(Plan.ACTION_HOLD, Plan.TAG_SOURCE,
            "accumulating training weeks toward the +1hg raise", "", Double.NaN, 0), true));
        assertTrue(TrainerTab.rowShows(new Plan.Decision(Plan.ACTION_HOLD, Plan.TAG_SOURCE,
            "x", "", Double.NaN, 0), false), "a hold not yet saved still offers");
        // And the screen asks exactly this, for girth and length.
        String ts = NoBookNamesTest.stripComments(new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("../app/src/main/java/org/openpump/TrainerScreen.java")),
            "UTF-8"));
        int at = ts.indexOf("private boolean trackSpeaks(");
        int end = ts.indexOf("private boolean lengthSpeaks(", at);
        assertTrue(at > 0 && end > at);
        assertTrue(ts.substring(at, end).contains("TrainerTab.rowShows(e.decision"));
        assertTrue(ts.substring(end, ts.indexOf("private boolean feederSpeaks(", end))
            .contains("trackSpeaks(Plan.TRACK_LENGTH"), "length goes through the same test");
    }
}
