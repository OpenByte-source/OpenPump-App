package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * t10 review D, F2 - THE WEEK IN FORCE IS TOLD WHEN ITS FACTS CHANGE, not only on a load. The
 * schedule reads "both tracks on" and "Length first" from the model (LongDays#follow); Pause,
 * the month-12 girth break, Resume and a saved shape change them, and the week has to follow
 * at once - or Up next, the miss policy and the reminders read one week while the Trainer page
 * says another until the next start.
 */
class WeekInForceRefreshTest {

    private static long on(int w) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7 + w, 12, 0, 0);
        return c.getTimeInMillis();
    }

    /** The owner's days, Tue/Thu/Sat, both tracks on and enrolled, with `longDays`. */
    private static Model owner(int longDays) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = on(Schedule.MON) - 200L * 86400000L;
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        m.sched = Schedule.ofMask("0101010");
        m.sched.longDays = longDays;
        m.clampAll();
        return m;
    }

    /** Paused, the week is the person's own days (one plan, nothing to share); resumed, the
     *  split week is back - Monday trains girth - without a reload. */
    @Test void pauseAndResumeTellTheWeek() {
        Model m = owner(Schedule.LONG_SPLIT);
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.MON)));
        TrainerTab.pausePlan(m);
        assertFalse(m.trainerEnrolled);
        assertEquals(Schedule.LONG_COMBINED, m.sched.longDaysInForce(), "paused: as now");
        assertFalse(m.sched.isTrainingDay(on(Schedule.MON)), "paused: the ticked days");
        TrainerTab.resumePlan(m, on(Schedule.MON));
        assertTrue(m.trainerEnrolled);
        assertEquals(Schedule.LONG_SPLIT, m.sched.longDaysInForce(), "resumed: the split week");
        assertTrue(m.sched.isTrainingDay(on(Schedule.MON)));
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.MON)));
        assertEquals(3, m.sched.countForTrack(Plan.TRACK_LENGTH));
    }

    /** A saved shape that turns "Length first" off swaps the lead of an "Alternate on my
     *  days" week at once. */
    @Test void aShapeTellsTheWeekItsLead() {
        Model m = owner(Schedule.LONG_ALTERNATE);
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planAt(on(Schedule.TUE)), "length first");
        Model.Shape girthFirst = Model.Shape.capture(m, "s1", "Girth first");
        girthFirst.lengthFirst = false;
        girthFirst.applyTo(m);
        assertFalse(m.rxLengthFirst);
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.TUE)));
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planAt(on(Schedule.THU)));
    }
}
