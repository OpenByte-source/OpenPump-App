package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * t10 REAL-1 (the owner, 1 Oct 2026 night) - THE CADENCE DELOAD ASKS WHEN IT STARTS. When the
 * block's last training week has counted, the plan asks: from tomorrow, from next Monday, or a
 * picked day within the next seven. Until it is answered no new cycle starts (the deload stays
 * due); "Not now" asks again the next morning; the next cycle counts from the ACTUAL end of the
 * week off, for both tracks at once.
 *
 * t10 REAL-5 - A WEEK OFF THAT STARTS TOMORROW DOES NOT CUT TODAY. The taper is armed at the
 * answer, but its cut belongs to the deload and the return after it: the day of the answer
 * runs at the working pressure and counts.
 */
class DeloadDueAskTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    /** Monday 2026-09-07 at `hour`:00 local, plus `day` days. */
    private static long at(int day, int hour) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7, hour, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, day);
        return c.getTimeInMillis();
    }

    private static Model model() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        m.trainerEnrolledAt = at(0, 0);
        m.trainerMonthsAt = at(0, 0);
        m.trainerMonthsPumping = 1;   // the middle cycles: 4 weeks, then 3
        String[][] rs = { { "g", "" + Plan.TRACK_GIRTH_INTERVAL },
                          { "l", "" + Plan.TRACK_LENGTH } };
        for (String[] x : rs) {
            Model.Routine r = new Model.Routine();
            r.id = x[0];
            r.trainerTrack = Integer.parseInt(x[1]);
            m.routines.add(r);
        }
        return m;
    }

    private static void file(Model m, String routineId, long ts) {
        Model.Sess s = new Model.Sess();
        s.id = "s" + m.sessLog.all.size();
        s.routineId = routineId;
        s.ts = ts;
        m.sessLog.all.add(0, s);
    }

    /** Mon / Tue / Wed / Thu / Fri / Sat girth, for `weeks` weeks from the first Monday. */
    private static void sixDays(Model m, int fromWeek, int weeks) {
        for (int w = fromWeek; w < fromWeek + weeks; w++)
            for (int d = 0; d < 6; d++) file(m, "g", at(7 * w + d, 9));
    }

    /** The answer, as SessionActivity's DeloadStartPick records it (recordDeload). */
    private static void answer(Model m, long startMs) {
        Deload.remember(m, startMs, startMs + Plan.LAYOFF_MS);
        m.trainerFirstDeloadTaken = true;
        Deload.arm(m, startMs + Plan.LAYOFF_MS);
    }

    /* ------------------------------------------------------------------ the start choices */

    @Test void theThreeStartsLandOnLocalMidnights() {
        long thu = at(3, 8);                                         // Thursday 10 Sep
        assertEquals(at(4, 0), Deload.dueStartMs(thu, Deload.START_TOMORROW, 0));
        assertEquals(at(7, 0), Deload.dueStartMs(thu, Deload.START_MONDAY, 0), "next Monday");
        assertEquals(at(5, 0), Deload.dueStartMs(thu, Deload.START_PICK, 2));
        long mon = at(7, 8);
        assertEquals(at(14, 0), Deload.dueStartMs(mon, Deload.START_MONDAY, 0),
            "on a Monday, next Monday is a week away");
        long sun = at(6, 8);
        assertEquals(at(7, 0), Deload.dueStartMs(sun, Deload.START_MONDAY, 0),
            "on a Sunday, tomorrow");
        assertEquals(at(4, 0), Deload.dueStartMs(thu, Deload.START_PICK, 0),
            "a picked day is never today");
        assertEquals(at(10, 0), Deload.dueStartMs(thu, Deload.START_PICK, 99),
            "nor more than seven days away");
    }

    /* ------------------------------------------------------------------ due, and pending */

    @Test void dueAfterTheFourthCountedWeekAndPendingUntilAnswered() {
        Model m = model();
        sixDays(m, 0, 3);
        file(m, "g", at(21, 9));
        file(m, "g", at(22, 9));
        assertFalse(TrainerTab.cadenceDeloadDue(m, at(23, 7)), "Wednesday morning: three weeks");
        file(m, "g", at(23, 9));                                     // wk 4 counts on Wed
        assertFalse(TrainerTab.cadenceDeloadDue(m, at(23, 20)),
            "not the evening it counted: the question comes before a morning's session");
        assertTrue(TrainerTab.cadenceDeloadDue(m, at(24, 7)), "Thursday morning: due");
        assertEquals(4, TrainerTab.cadenceWeeks(m, at(24, 7)));
        // Not answered: no new cycle starts - still due days later, whatever is trained.
        for (int d = 3; d < 6; d++) file(m, "g", at(21 + d, 9));
        file(m, "g", at(28, 9));
        assertTrue(TrainerTab.cadenceDeloadDue(m, at(29, 7)), "still due, never re-anchored");
    }

    @Test void notNowAsksAgainTheNextMorning() {
        Model m = model();
        sixDays(m, 0, 4);
        long thu = at(24, 8);
        assertTrue(TrainerTab.deloadStartAskDue(m, thu));
        m.deloadDueAskedDay = Summary.dayNumber(thu);                 // "Not now"
        assertFalse(TrainerTab.deloadStartAskDue(m, at(24, 21)), "not again today");
        assertTrue(TrainerTab.cadenceDeloadDue(m, at(24, 21)), "still due");
        assertTrue(TrainerTab.deloadStartAskDue(m, at(25, 7)), "asked again next morning");
    }

    @Test void answeredFromNextMondayTheNextCycleCountsFromItsEnd() {
        Model m = model();
        sixDays(m, 0, 4);
        long thu = at(24, 8);
        answer(m, Deload.dueStartMs(thu, Deload.START_MONDAY, 0));   // Mon 5 Oct (day 28)
        assertFalse(TrainerTab.cadenceDeloadDue(m, at(25, 7)), "answered: not asked again");
        assertFalse(TrainerTab.inDeloadWeek(m, at(27, 9)), "the rest of this week trains");
        assertTrue(TrainerTab.inDeloadWeek(m, at(28, 9)), "from Monday");
        assertTrue(TrainerTab.inDeloadWeek(m, at(34, 20)));
        assertFalse(TrainerTab.inDeloadWeek(m, at(35, 0)), "seven days");
        assertEquals(at(35, 0), TrainerTab.deloadAnchorMs(m), "the cycle counts from its end");
        sixDays(m, 5, 3);
        assertEquals(3, TrainerTab.planTrainingWeeks(m, TrainerTab.deloadAnchorMs(m), at(56, 7)));
        assertTrue(TrainerTab.cadenceDeloadDue(m, at(56, 7)), "three more weeks: due again");
    }

    @Test void theReturnWeekCountsOnlyTheDaysAfterTheEnd() {
        Model m = model();
        sixDays(m, 0, 4);
        long thu = at(24, 8);
        answer(m, Deload.dueStartMs(thu, Deload.START_TOMORROW, 0)); // Fri 25 .. Thu 1 (day 31)
        // Two light sessions inside the week off, in the week the deload ends (Tue, Wed) ...
        file(m, "g", at(29, 9));
        file(m, "g", at(30, 9));
        // ... and two days back after it (Fri, Sat): not a counted week.
        file(m, "g", at(32, 9));
        file(m, "g", at(33, 9));
        assertEquals(0, TrainerTab.planTrainingWeeks(m, TrainerTab.deloadAnchorMs(m), at(35, 7)),
            "the days inside the week off are not the new cycle's");
    }

    @Test void bothTracksRestTogetherFromThePickedDay() {
        Model m = model();
        sixDays(m, 0, 4);
        answer(m, Deload.dueStartMs(at(24, 8), Deload.START_PICK, 3));   // Sun 27
        assertFalse(Deload.lengthRests(m, at(26, 9)));
        assertTrue(Deload.lengthRests(m, at(27, 9)), "length rests from the same day");
        assertTrue(TrainerTab.inDeloadWeek(m, at(27, 9)), "and girth");
    }

    @Test void takingADeloadNowReplacesTheOneStillToCome() {
        Model m = model();
        sixDays(m, 0, 4);
        answer(m, Deload.dueStartMs(at(24, 8), Deload.START_MONDAY, 0));
        long now = at(25, 10);                                       // "Take a deload week now"
        Deload.remember(m, now, now + Plan.LAYOFF_MS);
        assertEquals(1, m.deloadWindows.size(), "the Monday week off is not kept beside it");
        assertFalse(Deload.touches(m, now + Plan.LAYOFF_MS, at(40, 0)));
    }

    /* ------------------------------------------------------------------ REAL-5 */

    @Test void aWeekOffFromTomorrowDoesNotCutToday() {
        Model m = model();
        sixDays(m, 0, 2);
        long tap = at(15, 8);                                        // a Tuesday morning
        answer(m, Deload.dueStartMs(tap, Deload.START_TOMORROW, 0));
        long today = Summary.dayNumber(tap);
        assertEquals(0.0, Deload.cutHg(m, today), 1e-9, "today runs at the working pressure");
        assertEquals(30.0, Deload.mainKpaOn(m, 30.0, at(15, 18)), 1e-9);
        assertFalse(m.gentleNow(tap), "and pulls at the usual speed");
        assertEquals("", Deload.stepTag(Deload.cutHg(m, today)), "no step in today's mint");
        assertTrue(Deload.cutHg(m, today + 1) > 0.0, "a light session in the week off runs gently");
        // Today's session is filed: it spends no step and counts as a training day.
        Model.Sess s = new Model.Sess();
        s.id = "today"; s.routineId = "g"; s.ts = at(15, 9);
        m.sessLog.all.add(0, s);
        Deload.onFiled(m, s.ts, Plan.TRACK_GIRTH_INTERVAL, false, true);
        assertEquals(0, m.returnStep, "the return's first step is still waiting");
        assertEquals(0L, m.returnDayRun);
        assertFalse(Deload.touches(m, s.ts, s.ts + 1L), "it is not inside the week off");
        // After the week off the return runs its two steps as before.
        long back = Summary.dayNumber(Deload.endMs(m));
        assertEquals(Plan.returnTaperHg(0), Deload.cutHg(m, back), 1e-9);
    }

    @Test void theTaperFaceSaysWhenTheWeekOffStarts() {
        Model m = model();
        long tap = at(15, 8);
        answer(m, Deload.dueStartMs(tap, Deload.START_PICK, 4));      // Sat 26 Sep
        String face = Say.taperFace(m, tap);
        assertTrue(face.contains("Sat 26 Sep"), face);
        assertFalse(face.contains("under"), face);
        assertTrue(Say.taperRow(m, tap).contains("Sat 26 Sep"), Say.taperRow(m, tap));
        assertTrue(Say.taperMarkFace(m, tap).contains("Sat 26 Sep"), Say.taperMarkFace(m, tap));
    }

    /** t10 device walk M5: before the week off begins, Today's card is not the return's - no
     *  "Coming back gently", no "full pressure from here" (which would end the taper armed for
     *  the week off), no "stay one more day". In the week off it is the deload week's; after
     *  it, the return's. */
    @Test void beforeTheWeekOffTheCardIsNotTheReturns() {
        Model m = model();
        sixDays(m, 0, 2);
        long tap = at(15, 8);
        answer(m, Deload.dueStartMs(tap, Deload.START_TOMORROW, 0));
        assertEquals("Deload week ahead", Say.taperTitle(m, tap));
        assertFalse(Say.taperAnswerable(m, tap), "nothing to answer before it starts");
        long inWeek = at(16, 9);
        assertEquals("Deload week", Say.taperTitle(m, inWeek));
        assertTrue(Say.taperAnswerable(m, inWeek));
        long back = Deload.endMs(m) + 9L * 60L * 60L * 1000L;
        assertEquals("Coming back gently", Say.taperTitle(m, back));
        assertTrue(Say.taperAnswerable(m, back));
    }

    /* ------------------------------------------------------------------ the words */

    @Test void theQuestionIsSaidPlainly() {
        String t = PlanCards.deloadDueText(4, true);
        assertTrue(t.contains("4 training weeks"), t);
        assertTrue(t.contains("both tracks"), t);
        assertTrue(t.contains("Today's session still runs") || t.contains("Today’s session still runs"), t);
        assertTrue(PlanCards.deloadDueText(3, false).contains("since your last deload"));
        // Polish TR-5: one name ("deload week"), the question last.
        assertEquals("4 training weeks since the plan began. A deload week rests both tracks "
            + "for 7 days. Today’s session still runs. When should it start?", t);
        assertTrue(PlanCards.deloadStartItem(Deload.START_TOMORROW, at(4, 0))
            .startsWith(PlanCards.DELOAD_FROM_TOMORROW));
        String set = PlanCards.deloadSetLine(at(7, 0), at(14, 0));
        assertTrue(set.contains("Mon 14 Sep") && set.contains("Sun 20 Sep"), set);
        assertFalse(set.contains("Mon 21 Sep"), "the last day named is the 7th, not the end");
    }
}
