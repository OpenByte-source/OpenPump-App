package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * t10 fix round (review A, F1 and F4) - ONE "BOTH TRACKS RUN TODAY" AND ONLY SESSIONS THAT RAN.
 *
 * F1: "Same days, as now" (Schedule#PLAN_ANY, the default, every upgrader's week) is a day of
 * both tracks (R-60: "both tracks on each ticked day"), exactly as PLAN_BOTH is. The length
 * session that runs first on such a day drops its swap and coda (R1), girth after it keeps all
 * its holds and leads in as the person chose (R4), and DayLength counts the day the way it runs
 * - one rule, TrainerTab#bothTracksToday, read by all of them.
 *
 * F4: a filed attempt that never ran - aborted at the seal check, 0:00, nothing delivered - is
 * not "the other track's session". It takes no warm-up away (P4), no fatigue block (R4), no coda
 * (R1), no tissue test (S05), and turns no feeder on (R-09).
 */
class BothTracksTodayTest {

    /** The owner's day, every weekday at the plan's choice ("Same days, as now"). */
    private static Model anyDays() {
        Model m = SecondSessionTest.ownerDay();
        Arrays.fill(m.sched.days, true);
        Arrays.fill(m.sched.plan, Schedule.PLAN_ANY);
        return m;
    }

    /** An attempt of `r` aborted at the seal check, as SessionActivity files it: 0:00, no
     *  preset delivered, no peak, stopped early. */
    private static Model.Sess aborted(Model m, Model.Routine r, long at) {
        Model.Sess s = new Model.Sess();
        s.ts = at;
        s.durSec = 0;
        s.id = "abort" + at;
        s.routineId = r.id;
        s.routineName = r.name;
        s.completed = false;
        s.presetsDone = 0;
        s.peakKpa = null;
        s.dayKey = PhotoCalendar.dayKey(at);
        m.sessLog.file(s);
        return s;
    }

    /* ------------------------------------------------------------ F1: PLAN_ANY is both */

    @Test void aPlanAnyDayLengthFirstDropsTheCoda() {
        Model m = anyDays();
        Model.Routine length = SecondSessionTest.lengthSaved(m);
        RunShape.Choice lc = TrainerTab.dayChoice(m, length, NOW, false, false);
        assertTrue(TrainerTab.bothTracksToday(m, NOW), "\"Same days, as now\" runs both");
        assertTrue(lc.bothTracks, "the length session that runs first knows the day is both");
        RunShape.Built lb = RunShape.build(m, length, lc);
        assertEquals(-1, stage(lb.routine, "swap"), "no swap");
        assertTrue(workPulls(m, lb.routine).isEmpty(), "and no expansion");
        assertEquals(54.67, minutes(m, lb.routine), 1e-9, "the spec's day of both, as PLAN_BOTH");
        filed(m, length, NOW, 55);

        Model.Routine girth = SecondSessionTest.girthSaved(m);
        RunShape.Choice gc = TrainerTab.dayChoice(m, girth, NOW + 5 * MIN, false, false);
        assertEquals(0, gc.girthSetsOff, "girth gives up no holds with a length cylinder");
        assertTrue(gc.girthAfterLength, "R4: girth after length");
        assertEquals(39.25, minutes(m, RunShape.build(m, girth, gc).routine), 1e-9);
    }

    @Test void theDayIsCountedAsItRuns() {
        Model m = anyDays();
        long[] any = DayLength.parts(m, Schedule.PLAN_ANY, NOW);
        long[] both = DayLength.parts(m, Schedule.PLAN_BOTH, NOW);
        assertEquals(both[0], any[0], "girth: a day of the plan's choice is a day of both");
        assertEquals(both[1], any[1], "length likewise");
        // ...and the figure is the run START builds on it, coda dropped.
        Model.Routine length = SecondSessionTest.lengthSaved(m);
        RunShape.Built lb = RunShape.build(m, length,
            TrainerTab.dayChoice(m, length, NOW, false, false));
        assertEquals(m.routineSec(lb.routine), any[1], 1.0, "the length session as run");
    }

    @Test void notBothWhenATrackIsOff() {
        // A rest day, a length-only day, girth paused for a length-focus rest, length resting:
        // none of them is a day of both, and the length session keeps its coda.
        Model rest = anyDays();
        Arrays.fill(rest.sched.days, false);
        assertFalse(TrainerTab.bothTracksToday(rest, NOW), "a rest day");

        Model lenOnly = anyDays();
        Arrays.fill(lenOnly.sched.plan, Schedule.PLAN_LENGTH);
        assertFalse(TrainerTab.bothTracksToday(lenOnly, NOW), "a length day");
        Model.Routine l = SecondSessionTest.lengthSaved(lenOnly);
        assertFalse(TrainerTab.dayChoice(lenOnly, l, NOW, false, false).bothTracks);

        Model girthRests = anyDays();
        girthRests.trainerGirth.restUntilMs = NOW + 7L * 86_400_000L;
        assertFalse(TrainerTab.bothTracksToday(girthRests, NOW), "girth rests");
        Model.Routine l2 = SecondSessionTest.lengthSaved(girthRests);
        assertFalse(TrainerTab.dayChoice(girthRests, l2, NOW, false, false).bothTracks,
            "length alone today: it keeps its coda");

        Model girthOff = anyDays();
        girthOff.trainerGirthOn = false;
        Model.Routine l3 = SecondSessionTest.lengthSaved(girthOff);
        assertFalse(TrainerTab.dayChoice(girthOff, l3, NOW, false, false).bothTracks,
            "a length-only plan");

        Model lengthRests = anyDays();
        lengthRests.trainerLength.restUntilMs = NOW + 7L * 86_400_000L;
        assertFalse(TrainerTab.bothTracksToday(lengthRests, NOW), "length rests");
    }

    @Test void expansionOnlyLengthOnAPlanAnyDayKeepsTheGirthTrim() {
        // K4 / 2A on a day of both, whichever order: with no length cylinder the length session
        // IS the day's expansion, and girth gives up its 5 holds for it - on "Same days, as now"
        // as on a day planned as both.
        Model m = owner();                       // no length cylinder
        m.trainerLengthOn = true;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        Arrays.fill(m.sched.days, true);
        Arrays.fill(m.sched.plan, Schedule.PLAN_ANY);
        Mint.Rx g = girth(Plan.L3, 14, 30);
        asMint(m, m.trainerGirth, g, build(m, g, day(m)));
        Mint.Rx l = length(34);
        asMint(m, m.trainerLength, l, build(m, l, day(m)));
        Model.Routine girth = SecondSessionTest.girthSaved(m);
        assertEquals(5, TrainerTab.dayChoice(m, girth, NOW, false, false).girthSetsOff,
            "2A: the 5-hold trim on a day of both");
    }

    @Test void theNinetyMinuteCheckReadsTheSameDay() {
        // CAP90 asks the same "both tracks" question: with girth resting there is no day of
        // both to hold, whatever the minutes.
        Model m = anyDays();
        m.sched.longDays = Schedule.LONG_CAP90;
        assertTrue(TrainerTab.heldAt90(m, Plan.TRACK_GIRTH_INTERVAL, 3600, NOW),
            "an hour more on a day of both passes 90 min");
        m.trainerGirth.restUntilMs = NOW + 7L * 86_400_000L;
        assertFalse(TrainerTab.heldAt90(m, Plan.TRACK_LENGTH, 3600, NOW),
            "girth resting: no day of both");
    }

    /* ------------------------------------------------------ F4: only sessions that ran */

    @Test void anAbortedLengthAttemptIsNotTheDaysLengthSession() {
        Model m = SecondSessionTest.ownerDay();
        aborted(m, SecondSessionTest.lengthSaved(m), NOW - 10 * MIN);
        Model.Routine girth = SecondSessionTest.girthSaved(m);
        assertFalse(TrainerTab.ranToday(m, Plan.TRACK_LENGTH, NOW), "nothing ran");
        assertEquals(1, TrainerTab.trackRunsToday(m, Plan.TRACK_LENGTH, NOW),
            "the attempt is still filed (History, the day's tally)");
        RunShape.Choice c = TrainerTab.dayChoice(m, girth, NOW, false, false);
        assertFalse(c.skipWarm, "P4: girth warms up - no length session ended");
        assertFalse(c.girthAfterLength, "R4: the fatigue block stays");
        assertFalse(c.noTissueTest, "S05: this is the day's first session");
        assertEquals(-1, TrainerTab.warmSkipAskMinutes(m, girth, NOW));
        assertEquals(0L, TrainerTab.lastTrackEndMs(m, Plan.TRACK_LENGTH, NOW));
    }

    @Test void anAbortedGirthAttemptTakesNoCodaAndNoWarmUpAndNoFeeder() {
        Model m = FeederGirthDaysTest.withFeeder();
        Arrays.fill(m.sched.days, true);
        Arrays.fill(m.sched.plan, Schedule.PLAN_LENGTH);
        aborted(m, SecondSessionTest.girthSaved(m), NOW - 10 * MIN);
        Model.Routine length = SecondSessionTest.lengthSaved(m);
        RunShape.Choice c = TrainerTab.dayChoice(m, length, NOW, false, false);
        assertFalse(c.skipWarm, "P4: length warms up");
        assertFalse(c.bothTracks, "R1: the coda stays - girth never ran");
        assertFalse(TrainerTab.girthFirstWarning(m, length, NOW), "S08: no girth ran first");
        assertFalse(TrainerTab.feederOn(m, NOW), "R-09: no girth session, no feeder");
    }

    @Test void aSessionThatRanStillCounts() {
        // A real session stopped early after real pressure still is the day's session.
        Model m = SecondSessionTest.ownerDay();
        Model.Sess s = filed(m, SecondSessionTest.lengthSaved(m), NOW - 10 * MIN, 20);
        s.completed = false;
        s.peakKpa = Double.valueOf(34.0);
        s.presetsDone = 4;
        assertTrue(TrainerTab.ranToday(m, Plan.TRACK_LENGTH, NOW));
        RunShape.Choice c = TrainerTab.dayChoice(m, SecondSessionTest.girthSaved(m), NOW,
                                                 false, false);
        assertTrue(c.skipWarm && c.girthAfterLength);
    }
}
