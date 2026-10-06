package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 fix round (review D: row 21, D0 row 4, deviation 7) - THE SMALL ONES.
 * <ul>
 * <li>Settings › Training days, while "Alternate days, each track 3 days a week" is in force:
 *     the ticks are not the week, so the day toggles are not tapped there and the card says
 *     so in the editor's words (LongDays#daysNote).</li>
 * <li>An upgrader whose plan was PAUSED when t10 arrived still owes the upgrade card (shown
 *     after Resume) and is handed over at month 3 like everyone else.</li>
 * <li>A recalibration that turns length on says Long training days now applies.</li>
 * </ul>
 */
class TrainingDaysUnderLongDaysTest {

    private static Model both(int longDays, String mask) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        m.sched = Schedule.ofMask(mask);
        m.sched.longDays = longDays;
        m.clampAll();
        return m;
    }

    @Test void underTheSplitWeekTheTicksAreNotTheWeek() {
        Model m = both(Schedule.LONG_SPLIT, "0101010");
        assertTrue(LongDays.daysSetByLongDays(m.sched));
        assertEquals("Long training days is “Alternate days, each track 3 days a week”: girth "
            + "runs Mon/Wed/Fri and length Tue/Thu/Sat, whatever is ticked here. These days are "
            + "used by the other three choices.", LongDays.daysNote(m.sched));
        // Polish (info moves): the one visible line; the rest is behind Settings' info button.
        assertEquals("Set by Long training days (Trainer › When it runs) — girth "
            + "Mon/Wed/Fri, length Tue/Thu/Sat.", LongDays.daysNoteShort(m.sched));
    }

    @Test void aLengthFirstSplitWeekSaysItsOwnDays() {
        Model m = both(Schedule.LONG_SPLIT, "1111110");
        for (int i = 0; i < 6; i++)
            m.sched.plan[i] = i % 2 == 0 ? Schedule.PLAN_LENGTH : Schedule.PLAN_GIRTH;
        assertTrue(m.sched.splitLengthFirst());
        assertTrue(LongDays.daysNote(m.sched).contains(
            "length runs Mon/Wed/Fri and girth Tue/Thu/Sat"));
    }

    @Test void everyOtherWeekIsTheTicks() {
        for (int v = Schedule.LONG_ALTERNATE; v <= Schedule.LONG_COMBINED; v++) {
            Model m = both(v, "0101010");
            assertFalse(LongDays.daysSetByLongDays(m.sched), LongDays.label(v));
            assertEquals("", LongDays.daysNote(m.sched));
        }
        // One track on: the setting is not in force (K21), the ticks are the week.
        Model one = both(Schedule.LONG_SPLIT, "0101010");
        one.trainerLengthOn = false;
        LongDays.follow(one);
        assertFalse(LongDays.daysSetByLongDays(one.sched));
        assertEquals("", LongDays.daysNote(one.sched));
    }

    @Test void aPausedUpgraderStillOwesTheCardAndIsHandedOver() throws Exception {
        // Paused (trainerOn false) before t10, set up long ago: the card is owed and shows
        // once the plan is resumed; the length track is handed over at month 3+.
        String paused = "{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],"
            + "\"trainerOn\":false,\"trainerAt\":\"1767225600000\",\"trainerMonths\":7,"
            + "\"trainerLengthOn\":true,"
            + "\"trainerGirth\":[{\"level\":3,\"week\":0,\"pressureKpa\":\"30.0\"}],"
            + "\"trainerLength\":[{\"level\":2,\"week\":0,\"pressureKpa\":\"34.0\"}]}";
        Model m = Model.fromJson(paused);
        assertFalse(m.planT10Seen, "owed: a trainer was set up before");
        assertFalse(m.planChangedT10Due(), "not shown while paused");
        assertTrue(m.trainerLength.handedOver, "month 3 or later: handed over already");
        m.trainerEnrolled = true;                 // Resume
        assertTrue(m.planChangedT10Due(), "shown after Resume");
        // A file that never had a trainer owes nothing.
        Model none = Model.fromJson("{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],"
            + "\"routines\":[],\"trainerOn\":false}");
        assertTrue(none.planT10Seen);
        assertFalse(none.trainerLength.handedOver);
    }

    @Test void aRecalibrationThatTurnsLengthOnSaysLongTrainingDaysApplies() {
        Model m = both(Schedule.LONG_SPLIT, "0101010");
        m.trainerLengthOn = false;
        LongDays.follow(m);
        String line = LongDays.lengthOnLine(m, true, true);
        assertEquals("Long training days now applies: “Alternate days, each track 3 days a "
            + "week”. Girth Mon/Wed/Fri, length Tue/Thu/Sat. About an hour a day. Change it on "
            + "the Trainer page.", line);
        assertEquals("", LongDays.lengthOnLine(m, false, true), "length stays off");
        assertEquals("", LongDays.lengthOnLine(m, true, false), "length only: one track");
        m.trainerLengthOn = true;
        assertEquals("", LongDays.lengthOnLine(m, true, true), "length was on already");
    }
}
