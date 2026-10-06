package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * S15 (the owner's decision, 2026-09-26): WHEN LENGTH IS ON, SETUP OFFERS BOTH WEEK SHAPES -
 * both tracks on the same days, or alternate days (length one day, girth the next).
 *
 * The guidance allows either: length days and girth days in turn, or both combined.
 * Combined is what setup always wrote (Mon/Wed/Fri, "Plan's choice" on
 * each day). Alternate sets each day's plan automatically from the days picked.
 */
class WeekShapeTest {

    private static final int L = Schedule.PLAN_LENGTH, G = Schedule.PLAN_GIRTH,
                             A = Schedule.PLAN_ANY;

    @Test void alternateDaysDefaultToSixWithTheseRanges() {
        boolean[] d = Schedule.alternateDays();
        assertArrayEquals(new boolean[]{ true, true, true, true, true, true, false }, d,
            "Monday to Saturday, Sunday off");
        Schedule s = new Schedule();
        s.applyWeekShape(Schedule.SHAPE_ALTERNATE, d);
        assertArrayEquals(new int[]{ L, G, L, G, L, G, A }, s.plan);
        assertEquals(3, s.countForTrack(Plan.TRACK_LENGTH),
            "length 3 days - the guidance notes some bodies need only three");
        assertEquals(3, s.countForTrack(Plan.TRACK_GIRTH_INTERVAL),
            "girth 3 days - inside the guidance's three to five");
        assertEquals(6, s.count(), "6 training days - the top of the four to six the guidance "
            + "gives beginners, with a rest day left in the week");
    }

    @Test void theDaysPickedAreAlternatedLengthFirst() {
        boolean[] mwf = { true, false, true, false, true, false, false };
        assertArrayEquals(new int[]{ L, A, G, A, L, A, A }, Schedule.alternatePlan(mwf));
        boolean[] all = { true, true, true, true, true, true, true };
        assertArrayEquals(new int[]{ L, G, L, G, L, G, L }, Schedule.alternatePlan(all));
        assertArrayEquals(new int[]{ A, A, A, A, A, A, A },
            Schedule.alternatePlan(new boolean[7]), "no day, no plan");
        assertArrayEquals(new int[]{ A, A, A, A, A, A, A }, Schedule.alternatePlan(null));
    }

    @Test void combinedIsWhatSetupAlwaysWrote() {
        Schedule s = new Schedule();
        s.plan[Schedule.FRI] = Schedule.PLAN_BOTH;          // anything already chosen stays
        boolean[] mwf = { true, false, true, false, true, false, false };
        s.applyWeekShape(Schedule.SHAPE_COMBINED, mwf);
        assertEquals("1010100", s.mask());
        assertArrayEquals(new int[]{ A, A, A, A, Schedule.PLAN_BOTH, A, A }, s.plan,
            "the days are written, the per-day plans are left as they were");
        boolean[] mine = mwf.clone();
        s.applyWeekShape(Schedule.SHAPE_ALTERNATE, mine);
        mine[Schedule.TUE] = true;
        assertFalse(s.days[Schedule.TUE], "the schedule keeps its own copy of the days");
    }

    /** Monday 2026-09-07 at 12:00 local, plus `day` days. */
    private static long at(int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7, 12, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, day);
        return c.getTimeInMillis();
    }

    @Test void upNextFollowsTheAlternateWeek() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerLengthOn = true;
        m.sched.applyWeekShape(Schedule.SHAPE_ALTERNATE, Schedule.alternateDays());
        UpNext mon = TrainerTab.upNextNow(m, at(0), false);
        assertEquals(UpNext.LENGTH, mon.what, "Monday is a length day");
        assertTrue(mon.why.contains("length is what today is for"), mon.why);
        assertEquals(UpNext.GIRTH, TrainerTab.upNextNow(m, at(1), false).what, "Tuesday girth");
        assertEquals(UpNext.NOTHING, TrainerTab.upNextNow(m, at(6), false).what, "Sunday rests");
    }

    @Test void theWeekSurvivesASave() throws Exception {
        Model m = new Model();
        m.sched.applyWeekShape(Schedule.SHAPE_ALTERNATE, Schedule.alternateDays());
        Model back = Model.fromJson(m.toJson());
        assertArrayEquals(m.sched.plan, back.sched.plan);
        assertEquals(m.sched.mask(), back.sched.mask());
    }
}
