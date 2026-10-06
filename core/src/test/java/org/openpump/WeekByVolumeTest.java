package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * COUNTING WEEKS BY VOLUME (the owner's decision of 2026-10-03, option B; TrainingWeek).
 *
 * A track's week counts with 3 days of it, as before, OR with 2 days whose sessions together
 * delivered 2 sessions' worth of the plan. A 4-day week of 2 girth + 2 length days used to
 * count for neither track, so the pressure steps, the level gates, the length calendar and the
 * deload cadence all stalled; 3 + 3 needed six days a week.
 */
class WeekByVolumeTest {

    /** Monday 2026-09-07 at `hour`:00 local, plus `day` days. */
    private static long at(int day, int hour) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7, hour, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, day);
        return c.getTimeInMillis();
    }

    private static Model model(boolean lengthOn) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerGirthOn = true;
        m.trainerLengthOn = lengthOn;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerEnrolledAt = at(0, 0);
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

    /** The week the schedule runs: `days` "1101100" (Monday first), each ticked day's track
     *  from `plans` ('g' girth, 'l' length, anything else either). */
    private static void week(Model m, String days, String plans) {
        m.sched = Schedule.ofMask(days);
        for (int i = 0; i < 7; i++) {
            char c = plans.charAt(i);
            m.sched.plan[i] = c == 'g' ? Schedule.PLAN_GIRTH
                : c == 'l' ? Schedule.PLAN_LENGTH : Schedule.PLAN_ANY;
        }
    }

    /** A girth session asked for 20 net minutes that delivered `share` of them. */
    private static Model.Sess girth(Model m, long ts, double share) {
        Model.Sess s = bare(m, "g", ts);
        s.netTargetMin = Double.valueOf(20.0);
        s.netTupSec = Double.valueOf(20.0 * 60.0 * share);
        s.completed = share >= 1.0;
        s.peakKpa = Double.valueOf(30.0);
        s.durSec = (long) (40 * 60 * Math.min(1.0, share));
        return s;
    }

    /** A length session planned at 50 minutes on the clock that ran `share` of them. */
    private static Model.Sess length(Model m, long ts, double share) {
        Model.Sess s = bare(m, "l", ts);
        s.netTargetMin = Double.valueOf(10.0);
        s.plannedSec = 50L * 60L;
        s.durSec = (long) (50L * 60L * share);
        s.completed = share >= 1.0;
        s.peakKpa = Double.valueOf(20.0);
        return s;
    }

    /** A session with nothing but its routine and time - as most older records read. */
    private static Model.Sess bare(Model m, String routineId, long ts) {
        Model.Sess s = new Model.Sess();
        s.id = "s" + m.sessLog.all.size();
        s.routineId = routineId;
        s.ts = ts;
        m.sessLog.all.add(0, s);
        return s;
    }

    private static int girthWeeks(Model m, long now) {
        return TrainerTab.accumulatedTrainingWeeks(m, Plan.TRACK_GIRTH_INTERVAL,
            m.trainerEnrolledAt, now);
    }

    private static int lengthWeeks(Model m, long now) {
        return TrainerTab.accumulatedTrainingWeeks(m, Plan.TRACK_LENGTH, m.trainerEnrolledAt, now);
    }

    @Test void aTwoPlusTwoWeekOfFullSessionsCountsForBothTracks() {
        Model m = model(true);
        week(m, "1101100", "gl.gl..");
        girth(m, at(0, 9), 1.0);
        length(m, at(1, 9), 1.0);
        girth(m, at(3, 9), 1.0);
        length(m, at(4, 9), 1.0);
        long now = at(6, 20);
        assertEquals(1, girthWeeks(m, now), "two full girth sessions are a girth week");
        assertEquals(1, lengthWeeks(m, now), "two full length sessions are a length week");
        assertEquals(1, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, now),
            "and both tracks' weeks counting is a plan week");
        assertEquals(1, TrainerTab.weeksAtPressure(m, Plan.TRACK_GIRTH_INTERVAL,
            m.trainerEnrolledAt, now), "the pressure clock reads the same rule");
    }

    @Test void twoShortSessionsDoNotCount() {
        Model m = model(false);
        girth(m, at(0, 9), 0.6);
        girth(m, at(3, 9), 0.6);
        assertEquals(0, girthWeeks(m, at(6, 20)), "1.2 sessions' worth is not 2");
        Model n = model(false);
        girth(n, at(0, 9), 1.0);
        girth(n, at(3, 9), 0.9);
        assertEquals(0, girthWeeks(n, at(6, 20)), "1.9 is not 2 either");
    }

    @Test void oneLongSessionDoesNotCount() {
        Model m = model(false);
        girth(m, at(0, 9), 3.0);
        assertEquals(0, girthWeeks(m, at(6, 20)), "one session never counts, however long");
        Model n = model(false);
        girth(n, at(0, 9), 1.5);
        girth(n, at(0, 18), 1.5);
        assertEquals(0, girthWeeks(n, at(6, 20)), "two runs on one day are one session");
    }

    @Test void threeSessionsCountAsBefore() {
        Model m = model(false);
        bare(m, "g", at(0, 9));
        bare(m, "g", at(2, 9));
        bare(m, "g", at(4, 9));
        assertEquals(1, girthWeeks(m, at(6, 20)), "three days with no volume recorded count");
        Model n = model(false);
        girth(n, at(0, 9), 0.3);
        girth(n, at(2, 9), 0.3);
        girth(n, at(4, 9), 0.3);
        assertEquals(1, girthWeeks(n, at(6, 20)), "three short days count, as they always did");
        Model o = model(false);
        bare(o, "g", at(0, 9));
        bare(o, "g", at(3, 9));
        assertEquals(0, girthWeeks(o, at(6, 20)), "two days of unknown volume do not");
    }

    @Test void theDeloadCadenceCountsAFourDayTwoPlusTwoWeek() {
        Model m = model(true);
        week(m, "1101100", "gl.gl..");
        for (int w = 0; w < 4; w++) {
            girth(m, at(7 * w, 9), 1.0);
            length(m, at(7 * w + 1, 9), 1.0);
            girth(m, at(7 * w + 3, 9), 1.0);
            length(m, at(7 * w + 4, 9), 1.0);
        }
        long now = at(28, 6);
        assertEquals(4, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, now));
        assertEquals(4, TrainerTab.cadenceWeeks(m, now));
        assertTrue(Plan.deloadDue(TrainerTab.cadenceWeeks(m, now), true, 0),
            "four 2 + 2 weeks bring the first deload due");
    }

    @Test void aTwoPlusTwoWeekCountsAfterItsLastScheduledSession() {
        Model m = model(true);
        week(m, "1101100", "gl.gl..");
        girth(m, at(0, 9), 1.0);
        length(m, at(1, 9), 1.0);
        assertEquals(0, girthWeeks(m, at(2, 20)));
        assertEquals(0, girthWeeks(m, at(3, 8)), "Thursday morning: girth's last day is to come");
        girth(m, at(3, 9), 1.0);
        assertEquals(1, girthWeeks(m, at(3, 10)), "its last scheduled session counts the week");
        TrainingWeek.Tally t = TrainerTab.weekTally(m, Plan.TRACK_GIRTH_INTERVAL, at(3, 10));
        assertTrue(t.qualifies);
        assertEquals(at(3, 9), t.qualifiedAtMs);
        assertEquals(2, t.fullShown());
        assertEquals(0, lengthWeeks(m, at(3, 10)), "length's Friday is still to come");
        length(m, at(4, 9), 1.0);
        assertEquals(1, lengthWeeks(m, at(4, 10)));
    }

    /** THE OWNER'S CONDITION (2026-10-03): a normal three-session week moves nothing sooner -
     *  two full sessions never count while a third day of the track is still scheduled. */
    @Test void aThreePlusThreeWeekCountsWhenItAlwaysDid() {
        Model m = model(true);
        week(m, "1111110", "glglgl.");
        // Filed day by day, each read before the next is filed.
        girth(m, at(0, 9), 1.0);
        length(m, at(1, 9), 1.0);
        assertEquals(0, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, at(1, 20)));
        girth(m, at(2, 9), 1.0);
        assertEquals(0, girthWeeks(m, at(2, 20)), "two full girth sessions by Wednesday: not yet");
        assertEquals(0, TrainerTab.weeksAtPressure(m, Plan.TRACK_GIRTH_INTERVAL,
            m.trainerEnrolledAt, at(2, 20)), "the pressure clock waits for Friday too");
        // The plan-wide week reads 3 days with both tracks in them, as it always did.
        assertEquals(1, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, at(2, 20)));
        length(m, at(3, 9), 1.0);
        assertEquals(0, lengthWeeks(m, at(3, 20)));
        assertEquals(0, girthWeeks(m, at(4, 8)), "nor on Friday morning");
        girth(m, at(4, 9), 1.0);
        assertEquals(1, girthWeeks(m, at(4, 10)), "the third session counts it, as before");
        assertEquals(at(4, 9),
            TrainerTab.weekTally(m, Plan.TRACK_GIRTH_INTERVAL, at(4, 10)).qualifiedAtMs);
        assertEquals(0, lengthWeeks(m, at(5, 8)));
        length(m, at(5, 9), 1.0);
        assertEquals(1, lengthWeeks(m, at(5, 10)));
    }

    @Test void aMissedThirdDayLetsTwoFullSessionsCountOnceItHasPassed() {
        Model m = model(false);
        week(m, "1010100", "g.g.g..");
        girth(m, at(0, 9), 1.0);
        girth(m, at(2, 9), 1.0);
        assertEquals(0, girthWeeks(m, at(4, 20)), "Friday was missed, and Friday is not over");
        assertEquals(1, girthWeeks(m, at(5, 8)), "from Saturday morning the two count");
        assertEquals(Deload.dayStartMs(at(5, 8)),
            TrainerTab.weekTally(m, Plan.TRACK_GIRTH_INTERVAL, at(5, 8)).qualifiedAtMs);
    }

    /** The owner's alignment (2026-10-03): a reduced return day is measured against the
     *  track's NORMAL full plan, so a return week of two reduced sessions does not count by the
     *  two-full-sessions rule; it still counts with three days, as before. */
    @Test void aReturnWeekOfReducedSessionsDoesNotCountByVolume() {
        Model m = model(false);
        week(m, "1001000", "g..g...");
        Model.Sess r1 = girth(m, at(0, 9), 1.0);
        r1.netTargetMin = Double.valueOf(0.0);     // the return's reduced day asks for no net
        r1.netTupSec = Double.valueOf(0.0);
        r1.returnDay = true;
        Model.Sess r2 = girth(m, at(3, 9), 1.0);
        r2.returnDay = true;                        // a reduced day, whatever it was asked
        assertEquals(0, girthWeeks(m, at(7, 6)), "two reduced sessions are not two full ones");
        Model n = model(false);
        week(n, "1010100", "g.g.g..");
        for (int d = 0; d < 5; d += 2) {
            Model.Sess s = girth(n, at(d, 9), 1.0);
            s.returnDay = true;
            s.netTargetMin = Double.valueOf(0.0);
        }
        assertEquals(1, girthWeeks(n, at(7, 6)), "three days of them count, as before");
        Model o = model(false);
        week(o, "1001000", "g..g...");
        Model.Sess r = girth(o, at(0, 9), 1.0);
        r.returnDay = true;
        girth(o, at(3, 9), 1.0);
        assertEquals(0, girthWeeks(o, at(7, 6)), "one reduced and one full are not two full");
    }

    @Test void aSessionThatNeverRanIsNotOneOfTheTwo() {
        Model m = model(false);
        Model.Sess abort = bare(m, "g", at(0, 9));     // a seal-check abort: 0:00, no reading
        abort.netTargetMin = Double.valueOf(20.0);
        abort.netTupSec = Double.valueOf(0.0);
        girth(m, at(3, 9), 2.5);
        assertEquals(0, girthWeeks(m, at(6, 20)), "a 0:00 run is not the second session");
    }

    @Test void aOneTrackPlanIsUnchanged() {
        Model m = model(false);
        for (int w = 0; w < 6; w++) {
            bare(m, "g", at(7 * w, 9));
            bare(m, "g", at(7 * w + 2, 9));
            if (w % 2 == 0) bare(m, "g", at(7 * w + 4, 9));
        }
        long now = at(7 * 6, 6);
        assertEquals(3, girthWeeks(m, now), "the 3-day weeks count, the 2-day ones do not");
        assertEquals(3, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, now),
            "and the plan-wide week is that track's week");
    }

    @Test void anOwnersYearOfThreePlusThreeIsUnchanged() {
        Model m = model(true);
        for (int w = 0; w < 52; w++) {
            for (int d = 0; d < 6; d += 2) girth(m, at(7 * w + d, 9), 1.0);
            for (int d = 1; d < 6; d += 2) length(m, at(7 * w + d, 9), 1.0);
        }
        // and a short session or two along the way, as a real year has
        girth(m, at(7 * 10 + 6, 9), 0.4);
        length(m, at(7 * 20 + 6, 9), 0.2);
        long now = at(7 * 52, 6);
        assertEquals(52, girthWeeks(m, now));
        assertEquals(52, lengthWeeks(m, now));
        assertEquals(52, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, now));
    }

    @Test void withBothTracksOnAWeekWithoutOneOfThemIsNotAPlanWeek() {
        Model m = model(true);
        girth(m, at(0, 9), 1.0);
        length(m, at(1, 9), 1.0);                   // length has been trained
        int wk2 = 7;
        for (int d = 0; d < 4; d++) girth(m, at(wk2 + d, 9), 1.0);
        long now = at(13, 20);
        assertEquals(1, girthWeeks(m, now), "girth's own week counts");
        assertEquals(0, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, now),
            "but each active track trains at least once in a plan week (W2)");
    }

    @Test void theRuleItself() {
        assertTrue(TrainingWeek.qualifies(3, 0, 0.0));
        assertTrue(TrainingWeek.qualifies(2, 2, 2.0));
        assertFalse(TrainingWeek.qualifies(2, 2, 1.99));
        assertFalse(TrainingWeek.qualifies(1, 1, 5.0));
        assertFalse(TrainingWeek.qualifies(2, 1, 3.0));
    }
}
