package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * t10 O7 (review D, F3) - "COME BACK TO GIRTH NOW" ENDS THE GIRTH OFFER'S LENGTH FOCUS AT THE
 * TAP, and never charges a missed girth week for it. The rest keeps its start, so the weeks it
 * touched - the one it ended in included - are forgiven by the miss policy, and the weeks
 * before it are not.
 */
class GirthRestEndTest {

    private static final long DAY = 86400000L, WEEK = 7L * DAY;

    /** Noon on weekday `w` (MON..SUN) of the week of Monday 2026-09-07 plus `weeks`. */
    private static long on(int weeks, int w) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7 + 7 * weeks + w, 12, 0, 0);
        return c.getTimeInMillis();
    }

    /** Midnight starting the week of Monday 2026-09-07 plus `weeks`. */
    private static long weekStart(int weeks) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7 + 7 * weeks, 0, 0, 0);
        return c.getTimeInMillis();
    }

    private static Model both() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        return m;
    }

    @Test void theRestKeepsItsStartAndRunsFourWeeks() {
        Model m = both();
        long start = on(0, Schedule.THU);
        TrainerTab.startGirthRest(m, start);
        assertEquals(start, m.trainerGirth.restFromMs);
        assertEquals(start + PlanCards.LENGTH_FOCUS_WEEKS * WEEK, m.trainerGirth.restUntilMs);
        assertTrue(TrainerTab.girthPausedNow(m, start + DAY));
        // Survives a save.
        Model back = Model.fromJson(m.toJson());
        assertEquals(start, back.trainerGirth.restFromMs);
    }

    /** Back on day 3 of a week (Wednesday): girth is offered at once, and the week the rest
     *  touched is not a missed one. */
    @Test void comingBackOnWednesdayChargesNothing() {
        Model m = both();
        TrainerTab.startGirthRest(m, on(0, Schedule.MON));
        long back = on(2, Schedule.WED);
        TrainerTab.endGirthRest(m, back);
        assertFalse(TrainerTab.girthPausedNow(m, back), "girth is back now");
        assertFalse(m.trainerGirth.resting(back + 1L));
        assertTrue(TrainerTab.girthRestTouchesWeek(m, weekStart(2), weekStart(3)),
            "the week it ended in is forgiven");
        assertTrue(TrainerTab.girthRestTouchesWeek(m, weekStart(1), weekStart(2)));
        assertFalse(TrainerTab.girthRestTouchesWeek(m, weekStart(3), weekStart(4)),
            "the week after is an ordinary week");
        assertFalse(TrainerTab.girthRestTouchesWeek(m, weekStart(-1), weekStart(0)),
            "and the week before the rest is not forgiven");
    }

    /** Ended within its first week, the rest does not reach back four weeks from its end. */
    @Test void anEarlyEndForgivesOnlyTheWeeksItTouched() {
        Model m = both();
        TrainerTab.startGirthRest(m, on(0, Schedule.TUE));
        TrainerTab.endGirthRest(m, on(0, Schedule.THU));
        assertTrue(TrainerTab.girthRestTouchesWeek(m, weekStart(0), weekStart(1)));
        for (int w = -4; w < 0; w++)
            assertFalse(TrainerTab.girthRestTouchesWeek(m, weekStart(w), weekStart(w + 1)),
                "week " + w);
    }

    /** Ending a rest that is not running changes nothing; a rest saved before its start was
     *  kept counts back its four weeks from the end, as before. */
    @Test void nothingToEndAndAnOldRest() {
        Model m = both();
        TrainerTab.endGirthRest(m, on(0, Schedule.MON));
        assertEquals(0L, m.trainerGirth.restUntilMs);
        assertFalse(TrainerTab.girthRestTouchesWeek(m, weekStart(0), weekStart(1)));
        Model old = both();
        old.trainerGirth.restUntilMs = on(4, Schedule.MON);
        assertEquals(0L, old.trainerGirth.restFromMs);
        assertTrue(TrainerTab.girthRestTouchesWeek(old, weekStart(0), weekStart(1)));
        assertFalse(TrainerTab.girthRestTouchesWeek(old, weekStart(-1), weekStart(0)));
    }
}
