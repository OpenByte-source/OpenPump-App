package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * DAYS OF NINETY MINUTES OR MORE (the owner's decision, 2026-09-30): the plan's training day
 * is girth and length as that day runs them, warm-ups and rests included - and a day that
 * reaches ninety minutes is said, with alternate days offered where the day runs both tracks.
 * Nothing changes until the switch is made. The feeders are not in it (t10 R-60, R-09: they
 * come 4-6 h after the girth session; device walk M3).
 */
class DayLengthTest {

    /** Monday 2026-09-07 at 12:00 local. */
    private static long monday() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7, 12, 0, 0);
        return c.getTimeInMillis();
    }

    /** A routine that takes exactly {@code min} minutes: rest stages of at most an hour. */
    private static Model.Routine routine(Model m, String id, int track, int min) {
        Model.Routine r = new Model.Routine();
        r.id = id;
        r.name = id;
        r.trainerTrack = track;
        int left = min * 60;
        while (left > 0) {
            int s = Math.min(3600, left);
            r.stages.add(Model.Stage.restOf("Rest", s));
            left -= s;
        }
        m.routines.add(r);
        return r;
    }

    /** Enrolled on both tracks at L3 (the feeder's level) on Mon/Wed/Fri, "the plan's
     *  choice": girth 55 min, length 40 min, feeder 10 min. */
    private static Model model() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        m.trainerGirth.level = Plan.L3;
        m.trainerGirth.lastMintId = routine(m, "g", Plan.TRACK_GIRTH_INTERVAL, 55).id;
        m.trainerLength.lastMintId = routine(m, "l", Plan.TRACK_LENGTH, 40).id;
        m.trainerFeederMintId = routine(m, "f", Plan.TRACK_FEEDER, 10).id;
        m.sched = Schedule.ofMask("1010100");
        return m;
    }

    @Test void theRoutinesHaveTheLengthsTheTestGivesThem() {
        Model m = model();
        assertEquals(55 * 60L, m.routineSec(m.routine("g")));
        assertEquals(10 * 60L, m.routineSec(m.routine("f")));
    }

    @Test void aDayIsGirthAndLengthAndTheFeedersComeLater() {
        Model m = model();
        long now = monday();
        assertArrayEquals(new long[]{ 55 * 60, 40 * 60, 2 * 10 * 60 },
            DayLength.parts(m, Schedule.PLAN_ANY, now), "the plan's choice runs both");
        assertEquals((55 + 40) * 60L, DayLength.daySec(m, Schedule.PLAN_BOTH, now),
            "the feeders are not in the day (R-60: no feeders)");
        assertEquals(55 * 60L, DayLength.daySec(m, Schedule.PLAN_GIRTH, now));
        assertEquals(40 * 60L, DayLength.daySec(m, Schedule.PLAN_LENGTH, now));
        assertEquals(0L, DayLength.daySec(m, Schedule.PLAN_REST, now));
        assertEquals(2, Plan.FEEDER_PER_DAY, "the day counts two feeders");
    }

    @Test void aSplitDayCountsBothParts() {
        Model m = model();
        m.trainerGirth.lastMintId2 = routine(m, "g2", Plan.TRACK_GIRTH_INTERVAL, 25).id;
        assertEquals((55 + 25) * 60L, DayLength.parts(m, Schedule.PLAN_GIRTH, monday())[0]);
    }

    @Test void onlyWhatRunsIsCounted() {
        long now = monday();
        Model m = model();
        m.trainerFeederOptIn = false;
        assertEquals(0L, DayLength.parts(m, Schedule.PLAN_ANY, now)[2], "feeder off");
        m = model();
        m.trainerGirth.level = Plan.L2;
        assertEquals(0L, DayLength.parts(m, Schedule.PLAN_ANY, now)[2],
            "no feeder below its level");
        m = model();
        m.trainerLength.restUntilMs = now + 7L * 86400000L;
        assertEquals(0L, DayLength.parts(m, Schedule.PLAN_ANY, now)[1], "length on a rest");
        m = model();
        m.trainerLength.focusBlockUntilMs = now + 7L * 86400000L;
        assertEquals(0L, DayLength.parts(m, Schedule.PLAN_ANY, now)[0],
            "girth paused for a girth-focus block");
        m = model();
        m.trainerLengthOn = false;
        assertEquals(0L, DayLength.parts(m, Schedule.PLAN_ANY, now)[1], "length not on");
        m = model();
        m.trainerEnrolled = false;
        assertEquals(0L, DayLength.daySec(m, Schedule.PLAN_ANY, now), "not enrolled");
        m = model();
        m.trainerGirth.lastMintId = "";
        m.trainerLength.lastMintId = "";
        assertEquals(0L, DayLength.daySec(m, Schedule.PLAN_ANY, now),
            "nothing saved: no day, and no feeders on their own");
    }

    @Test void ninetyMinutesAsPrintedIsTheLine() {
        assertFalse(DayLength.reaches(89 * 60 + 29));
        assertTrue(DayLength.reaches(89 * 60 + 30), "prints as 90");
        assertTrue(DayLength.reaches(90 * 60));
        assertEquals(90, DayLength.NOTICE_MIN);
        assertEquals(SameDay.DAY_BUDGET_MIN, DayLength.NOTICE_MIN, "the source's one figure");
    }

    @Test void aLongDayOfBothTracksIsSaidAndOffersAlternateDays() {
        Model m = model();
        long now = monday();
        assertEquals(Schedule.MON, DayLength.longestWeekday(m, now));
        assertEquals(95 * 60L, DayLength.longestDaySec(m, now));
        assertTrue(DayLength.noticeDue(m, now));
        assertTrue(DayLength.alternateOffered(m, now));
        assertTrue(DayLength.everyDayAsLong(m, 95 * 60L, now));
        assertEquals("Each training day will be about 115 minutes.",
            DayLength.headline(Schedule.MON, true, 115 * 60L));
        assertEquals("Your Wednesday training day will be about 115 minutes.",
            DayLength.headline(Schedule.WED, false, 115 * 60L));
        assertEquals("Girth 55 min and length 40 min, warm-ups and rests included. The "
            + "feeders come later, 4–6 h after the girth session.",
            DayLength.partsLine(DayLength.parts(m, Schedule.PLAN_ANY, now)));
        m.trainerFeederOptIn = false;
        assertEquals("Girth 55 min and length 40 min, warm-ups and rests included.",
            DayLength.partsLine(DayLength.parts(m, Schedule.PLAN_ANY, now)));
    }

    @Test void underNinetyOrTurnedOffNothingIsSaid() {
        long now = monday();
        Model m = model();
        m.trainerFeederOptIn = false;
        m.routine("g").stages.clear();
        m.routine("g").stages.add(Model.Stage.restOf("Rest", 49 * 60));
        assertEquals(89L, DayLength.minutes(DayLength.longestDaySec(m, now)));
        assertFalse(DayLength.noticeDue(m, now), "89 minutes is under the line");
        m = model();
        m.dayBudgetAdvisory = false;
        assertFalse(DayLength.noticeDue(m, now),
            "the ninety-minute advisory turned off turns this off too");
    }

    @Test void theLongestDayIsNamedWhenTheDaysDiffer() {
        long now = monday();
        Model m = model();
        m.sched.plan[Schedule.MON] = Schedule.PLAN_GIRTH;
        m.sched.plan[Schedule.WED] = Schedule.PLAN_BOTH;
        m.sched.plan[Schedule.FRI] = Schedule.PLAN_LENGTH;
        assertEquals(Schedule.WED, DayLength.longestWeekday(m, now));
        assertFalse(DayLength.everyDayAsLong(m, DayLength.longestDaySec(m, now), now));
        assertTrue(DayLength.alternateOffered(m, now));
        // A rest weekday's plan is never read.
        m.sched.days[Schedule.WED] = false;
        assertEquals(Schedule.MON, DayLength.longestWeekday(m, now));
        assertFalse(DayLength.alternateOffered(m, now), "the longest day runs one track");
    }

    /** t10 K10: THE SWITCH SETS LONG TRAINING DAYS to "Alternate days, each track 3 days a
     *  week" - girth Mon/Wed/Fri, length Tue/Thu/Sat - and writes nothing else. */
    @Test void theSwitchSetsAlternateDaysAndNothingElse() {
        long now = monday();
        Model m = model();
        int hour = m.sched.hour;
        boolean[] before = m.sched.days.clone();
        int[] planBefore = m.sched.plan.clone();
        assertTrue(DayLength.noticeDue(m, now));
        assertArrayEquals(before, m.sched.days, "asking changes nothing");
        assertTrue(DayLength.switchNote(m).contains("Mon girth · Tue length · Wed "
            + "girth · Thu length · Fri girth · Sat length"),
            "girth Mon/Wed/Fri, length Tue/Thu/Sat: " + DayLength.switchNote(m));
        DayLength.switchToAlternate(m);
        assertEquals(Schedule.LONG_SPLIT, m.sched.longDays);
        assertEquals(Schedule.LONG_SPLIT, m.sched.longDaysInForce());
        assertArrayEquals(before, m.sched.days, "the person's days are kept");
        assertArrayEquals(planBefore, m.sched.plan, "and each day's own plan");
        assertArrayEquals(Schedule.alternateDays(), m.sched.daysInForce(), "Mon to Sat run");
        assertEquals(hour, m.sched.hour);
        assertEquals(55 * 60L, DayLength.longestDaySec(m, now),
            "the longest day is now girth's (the feeders come later)");
        assertFalse(DayLength.alternateOffered(m, now), "nothing left to alternate");
        assertEquals("Alternate days from now: Mon girth · Tue length · Wed girth "
            + "· Thu length · Fri girth · Sat length.", DayLength.switchedLine(m));
    }

    /** THE SWITCH'S UNDO (0.10): the week exactly as it was - days, each day's plan (the shape
     *  and the lead), today's override, the reminder's time and switch - put back in place, and
     *  only while the schedule is still the week the switch wrote. */
    @Test void undoPutsTheWeekBackExactlyAsItWas() {
        long now = monday();
        Model m = model();
        // A week with every figure the switch could touch or leave: its own plans, an override
        // for today, a reminder at 07:45.
        m.sched.plan[Schedule.MON] = Schedule.PLAN_BOTH;
        m.sched.plan[Schedule.WED] = Schedule.PLAN_ANY;
        m.sched.plan[Schedule.FRI] = Schedule.PLAN_BOTH;
        m.sched.plan[Schedule.SUN] = Schedule.PLAN_REST;
        m.sched.overrideDayKey = 20261005; m.sched.overridePlan = Schedule.PLAN_GIRTH;
        m.sched.hour = 7; m.sched.minute = 45; m.sched.remind = true;
        Schedule held = m.sched;
        Schedule was = m.sched.copy();
        assertTrue(DayLength.alternateOffered(m, now));
        DayLength.switchToAlternate(m);
        Schedule wrote = m.sched.copy();
        assertFalse(m.sched.sameAs(was), "the switch changed the week");
        assertTrue(DayLength.undoAlternate(m, was, wrote));
        assertTrue(m.sched.sameAs(was), "back exactly as it was");
        assertTrue(m.sched == held, "in place: every holder of the schedule sees it");
        assertArrayEquals(was.days, m.sched.days);
        assertArrayEquals(was.plan, m.sched.plan, "the shape and the lead with it");
        assertEquals(20261005, m.sched.overrideDayKey);
        assertEquals(Schedule.PLAN_GIRTH, m.sched.overridePlan);
        assertEquals(7, m.sched.hour);
        assertEquals(45, m.sched.minute);
        assertTrue(m.sched.remind);
        assertTrue(DayLength.alternateOffered(m, now), "the card offers the switch again");
        // The girth-first lead and the person's own days come back too.
        Model g = model();
        g.sched = Schedule.ofMask("1101100");
        g.rxLengthFirst = false;
        Schedule gWas = g.sched.copy();
        DayLength.switchToAlternate(g);
        assertTrue(DayLength.undoAlternate(g, gWas, g.sched.copy()));
        assertTrue(g.sched.sameAs(gWas));
    }

    @Test void undoLeavesAWeekChangedSinceTheSwitchAlone() {
        Model m = model();
        Schedule was = m.sched.copy();
        DayLength.switchToAlternate(m);
        Schedule wrote = m.sched.copy();
        // Changed in Settings after the tap: the person's newer choice stands.
        m.sched.hour = 6;
        Schedule newer = m.sched.copy();
        assertFalse(DayLength.undoAlternate(m, was, wrote));
        assertTrue(m.sched.sameAs(newer), "nothing was put back");
        assertFalse(DayLength.undoAlternate(m, null, wrote));
        assertFalse(DayLength.undoAlternate(null, was, wrote));
        assertTrue(DayLength.UNDO_STALE.contains("nothing was undone"));
        assertEquals("Your week is back as it was.", DayLength.UNDONE);
    }

    /** The switch's week is girth Mon/Wed/Fri and length Tue/Thu/Sat whichever track leads a
     *  day of both, and the person's own days stay saved for the other choices. */
    @Test void thePersonsOwnDaysAreKeptForTheOtherChoices() {
        Model m = model();
        m.sched = Schedule.ofMask("1101100");
        m.rxLengthFirst = false;
        DayLength.switchToAlternate(m);
        assertArrayEquals(Schedule.ofMask("1101100").days, m.sched.days, "days kept");
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planOn(Schedule.MON));
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planOn(Schedule.TUE));
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planOn(Schedule.WED));
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planOn(Schedule.SAT));
        assertFalse(m.sched.trainsOn(Schedule.SUN), "Sunday rests");
    }

    @Test void theSetupsDayIsBothOrTheLongerOneWithoutTheFeeders() {
        assertEquals(95 * 60L, DayLength.setupDaySec(55 * 60, 40 * 60, false));
        assertEquals(55 * 60L, DayLength.setupDaySec(55 * 60, 40 * 60, true));
        assertEquals(0L, DayLength.setupDaySec(-1, -1, false));
    }

    /** t10 device walk M3: a day of both tracks builds each session the one way - length's
     *  pulls with no swap or expansion and, second, no warm-up; girth after length with no
     *  fatigue block and no warm-up; girth first warms up and keeps its block. */
    @Test void aDayOfBothBuildsEachSessionTheOneWay() {
        assertArrayEquals(new boolean[]{ true, false, false },
            DayLength.bothDayFlags(true, true), "length first: no coda, warms up");
        assertArrayEquals(new boolean[]{ false, true, true },
            DayLength.bothDayFlags(false, true), "girth after length: R4, no warm-up");
        assertArrayEquals(new boolean[]{ true, false, true },
            DayLength.bothDayFlags(true, false), "length after girth: no coda, no warm-up");
        assertArrayEquals(new boolean[]{ false, false, false },
            DayLength.bothDayFlags(false, false), "girth first: as saved");
    }

    @Test void theRealBuilderSRoutinesAreCountedAsBuilt() {
        long now = monday();
        Model m = model();
        Mint.Rx g = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 0, 34.0, 8, m.ceilKpa,
                                   0, 0, null);
        Mint.Rx l = Mint.prescribe(Plan.TRACK_LENGTH, Plan.L3, 0, 27.0, 8, m.ceilKpa, 0, 0, null);
        Model.Routine rg = m.routine(RxBuild.routineFromRx(m, g));
        Model.Routine rl = m.routine(RxBuild.routineFromRx(m, l));
        m.trainerGirth.lastMintId = rg.id;
        m.trainerLength.lastMintId = rl.id;
        m.trainerFeederOptIn = false;
        assertTrue(m.routineSec(rg) > 0 && m.routineSec(rl) > 0);
        assertEquals(m.routineSec(rg) + m.routineSec(rl),
            DayLength.daySec(m, Schedule.PLAN_ANY, now),
            "warm-ups, rests and every stage, as the routine's own clock counts them");
    }
}
