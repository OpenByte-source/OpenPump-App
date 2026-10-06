package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * t10 O2 (the owner's decision, 1 Oct 2026) - UPGRADERS WHO ALREADY ALTERNATE ON THEIR OWN DAYS
 * KEEP THEIR DAYS AND THEIR LEAD. An old week that alternates girth and length on days other
 * than Monday to Saturday maps to "Alternate on my days" (Schedule#longDaysFor); its lead was
 * only ever written into each day's plan, so the week runs as stored - nothing moves - whatever
 * "Length first" says. A lead the person chooses afterwards is theirs and the week follows it.
 */
class AlternateLeadKeptTest {

    /** The local instant of weekday `w` (MON..SUN) in the week of Monday 2026-09-07, noon. */
    private static long on(int w) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7 + w, 12, 0, 0);
        return c.getTimeInMillis();
    }

    /** A file saved before Long training days: both tracks on, the week `days`/`plan` (masks,
     *  plan 1 = girth, 2 = length), "Length first" as given (absent = the default, on). */
    private static Model upgrader(String days, String plan, Boolean lengthFirst) {
        return Model.fromJson("{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],"
            + "\"trainerOn\":true,\"trainerMonths\":7,\"trainerLengthOn\":true,"
            + (lengthFirst == null ? "" : "\"rxLengthFirst\":" + lengthFirst + ",")
            + "\"trainerGirth\":[{\"level\":3,\"week\":0,\"pressureKpa\":\"30.0\"}],"
            + "\"trainerLength\":[{\"level\":2,\"week\":0,\"pressureKpa\":\"34.0\"}],"
            + "\"sched\":[{\"days\":\"" + days + "\",\"plan\":\"" + plan
            + "\",\"hour\":19,\"min\":0}]}");
    }

    /** Tue/Thu/Sat, girth first, saved with "Length first" at its default (on). */
    @Test void aGirthFirstWeekOnTheirOwnDaysKeepsItsLead() {
        Model m = upgrader("0101010", "0102010", null);
        assertTrue(m.rxLengthFirst, "the default the old setup never changed");
        assertEquals(Schedule.LONG_ALTERNATE, m.sched.longDaysInForce());
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.TUE)));
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planAt(on(Schedule.THU)));
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.SAT)));
        assertEquals(2, m.sched.countForTrack(Plan.TRACK_GIRTH_INTERVAL));
        assertEquals(1, m.sched.countForTrack(Plan.TRACK_LENGTH));
    }

    /** Mon/Tue/Thu/Fri, length first, saved with "Length first" off. */
    @Test void aLengthFirstWeekKeepsItsLeadWhateverLengthFirstSays() {
        Model m = upgrader("1101100", "2102100", Boolean.FALSE);
        assertEquals(Schedule.LONG_ALTERNATE, m.sched.longDaysInForce());
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planAt(on(Schedule.MON)));
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.TUE)));
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planAt(on(Schedule.THU)));
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.FRI)));
        // ...and after a save and a load, as before.
        Model back = Model.fromJson(m.toJson());
        assertEquals(Schedule.PLAN_LENGTH, back.sched.planAt(on(Schedule.MON)));
        assertEquals(Schedule.PLAN_GIRTH, back.sched.planAt(on(Schedule.TUE)));
    }

    /** The person picks the lead afterwards ("Length first", the setup's answer): the week
     *  follows it, and keeps following it after a load. */
    @Test void aLeadChosenLaterIsThePersons() {
        Model m = upgrader("0101010", "0102010", null);
        m.rxLengthFirst = false;
        m.sched.leadWith(false);
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.TUE)), "already girth");
        m.rxLengthFirst = true;
        m.sched.leadWith(true);
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planAt(on(Schedule.TUE)));
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.THU)));
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planAt(on(Schedule.SAT)));
        Model back = Model.fromJson(m.toJson());
        assertEquals(Schedule.PLAN_LENGTH, back.sched.planAt(on(Schedule.TUE)), "and kept");
    }

    /** A day ticked later (Settings): the week still starts with the track the person's own
     *  week started with. */
    @Test void aDayAddedLaterKeepsTheirLead() {
        Model m = upgrader("0101010", "0102010", null);
        m.sched.days[Schedule.MON] = true;
        LongDays.follow(m);
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.MON)));
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planAt(on(Schedule.TUE)));
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.THU)));
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planAt(on(Schedule.SAT)));
    }

    /** A week set up under the setting (no stored alternation) is led by "Length first". */
    @Test void aWeekWithNoStoredLeadFollowsLengthFirst() {
        Model fresh = upgrader("0101010", "0000000", Boolean.FALSE);
        fresh.sched.longDays = Schedule.LONG_ALTERNATE;
        assertEquals(Schedule.PLAN_GIRTH, fresh.sched.planAt(on(Schedule.TUE)));
        fresh.rxLengthFirst = true;
        LongDays.follow(fresh);
        assertEquals(Schedule.PLAN_LENGTH, fresh.sched.planAt(on(Schedule.TUE)));
        // A plan that names one track on every day is no alternation, nor is a single day.
        int[] all = { 0, 1, 0, 1, 0, 1, 0 }, one = { 0, 1, 0, 0, 0, 0, 0 };
        System.arraycopy(all, 0, fresh.sched.plan, 0, Schedule.DAYS);
        assertEquals(Schedule.PLAN_ANY, fresh.sched.storedLead());
        System.arraycopy(one, 0, fresh.sched.plan, 0, Schedule.DAYS);
        assertEquals(Schedule.PLAN_ANY, fresh.sched.storedLead());
        assertEquals(Schedule.PLAN_LENGTH, fresh.sched.planAt(on(Schedule.TUE)));
    }
}
