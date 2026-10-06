package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * t10 R-60 - LONG TRAINING DAYS (the owner's decision, 1 Oct 2026): four ways the two tracks
 * share the week, the week following the choice ({@link Schedule#planAt},
 * {@link Schedule#isTrainingDay}, {@link Schedule#countForTrack}), one track on acting as
 * "Same days, as now" (K21), and the 90-minute card's switch with its Undo (K10).
 */
class LongTrainingDaysTest {

    /** The local instant of weekday `w` (MON..SUN) in the week of Monday 2026-09-07, noon. */
    private static long on(int w) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7 + w, 12, 0, 0);
        return c.getTimeInMillis();
    }

    /** The owner's days, Tue/Thu/Sat, both tracks on, with `longDays`. */
    private static Model owner(int longDays) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        m.sched = Schedule.ofMask("0101010");
        m.sched.longDays = longDays;
        m.clampAll();
        return m;
    }

    @Test void theWeekdayHelperIsMondayFirst() {
        assertEquals(Schedule.MON, Schedule.weekdayAt(on(Schedule.MON)));
        assertEquals(Schedule.SUN, Schedule.weekdayAt(on(Schedule.SUN)));
    }

    /** Test 1: girth Mon/Wed/Fri, length Tue/Thu/Sat whatever is ticked; Sunday rests. */
    @Test void alternateDaysRunGirthMonWedFriAndLengthTueThuSat() {
        Schedule s = owner(Schedule.LONG_SPLIT).sched;
        int[] want = { Schedule.PLAN_GIRTH, Schedule.PLAN_LENGTH, Schedule.PLAN_GIRTH,
                       Schedule.PLAN_LENGTH, Schedule.PLAN_GIRTH, Schedule.PLAN_LENGTH };
        for (int w = Schedule.MON; w <= Schedule.SAT; w++) {
            assertTrue(s.isTrainingDay(on(w)), Schedule.DAY_ABBR[w] + " trains");
            assertEquals(want[w], s.planAt(on(w)), Schedule.DAY_ABBR[w]);
        }
        assertFalse(s.isTrainingDay(on(Schedule.SUN)), "Sunday is a rest day");
        assertEquals(6, s.count());
        assertEquals("Mon, Tue, Wed, Thu, Fri, Sat", s.daysLine());
        assertArrayEquals(Schedule.ofMask("0101010").days, s.days, "the ticks are kept");
    }

    /** Test 3: each track gets its 3 days, so both count every week. */
    @Test void alternateDaysGiveEachTrackThreeDays() {
        Model m = owner(Schedule.LONG_SPLIT);
        assertEquals(3, m.sched.countForTrack(Plan.TRACK_GIRTH_INTERVAL));
        assertEquals(3, m.sched.countForTrack(Plan.TRACK_LENGTH));
        assertEquals("", LongDays.shortLine(m));
    }

    /** Test 2: "Alternate on my days" on Tue/Thu/Sat gives girth one day - the red line. */
    @Test void alternateOnThreeDaysLeavesATrackShortAndSaysSo() {
        Model m = owner(Schedule.LONG_ALTERNATE);
        Schedule s = m.sched;
        assertEquals(Schedule.PLAN_LENGTH, s.planAt(on(Schedule.TUE)), "length first leads");
        assertEquals(Schedule.PLAN_GIRTH, s.planAt(on(Schedule.THU)));
        assertEquals(Schedule.PLAN_LENGTH, s.planAt(on(Schedule.SAT)));
        assertFalse(s.isTrainingDay(on(Schedule.MON)), "the person's own days");
        assertEquals(1, s.countForTrack(Plan.TRACK_GIRTH_INTERVAL));
        assertEquals(2, s.countForTrack(Plan.TRACK_LENGTH));
        // Week B (2026-10-03): a track's week counts with 2 full sessions, so 2 days are enough
        // (LongDays#WEEK_DAYS); it was 3, and this week was short on both tracks.
        assertTrue(LongDays.shortLine(m).startsWith("With these days girth doesn't get 2 days "
            + "a week, so its plan won't move."), LongDays.shortLine(m));
        // Girth first leads instead: the person's order for a day of both.
        m.rxLengthFirst = false;
        m.clampAll();
        assertEquals(Schedule.PLAN_GIRTH, s.planAt(on(Schedule.TUE)));
        // Five days: length 3, girth 2 - both can move now, nothing to say.
        m.sched.days[Schedule.MON] = true;
        m.sched.days[Schedule.WED] = true;
        m.rxLengthFirst = true;
        m.clampAll();
        assertEquals(3, s.countForTrack(Plan.TRACK_LENGTH));
        assertEquals(2, s.countForTrack(Plan.TRACK_GIRTH_INTERVAL));
        assertEquals("", LongDays.shortLine(m));
        // One day: neither track gets 2.
        Model one = owner(Schedule.LONG_ALTERNATE);
        one.sched = Schedule.ofMask("0100000");
        one.sched.longDays = Schedule.LONG_ALTERNATE;
        one.clampAll();
        assertEquals(LongDays.SHORT_BOTH, LongDays.shortLine(one));
        assertTrue(LongDays.SHORT_BOTH.startsWith("With these days neither track gets 2 days "
            + "a week, so its plan won't move."));
    }

    @Test void theSameDaysChoicesRunBothTracksOnTheTickedDays() {
        for (int ld : new int[]{ Schedule.LONG_CAP90, Schedule.LONG_COMBINED }) {
            Schedule s = owner(ld).sched;
            assertEquals(Schedule.PLAN_ANY, s.planAt(on(Schedule.TUE)));
            assertFalse(s.isTrainingDay(on(Schedule.MON)));
            assertEquals(3, s.countForTrack(Plan.TRACK_GIRTH_INTERVAL));
            assertEquals(3, s.countForTrack(Plan.TRACK_LENGTH));
        }
    }

    /** Test 5 (K21): one track on, the setting is hidden and the week is the ticked days. */
    @Test void oneTrackOnActsAsSameDaysAsNow() {
        Model m = owner(Schedule.LONG_SPLIT);
        m.trainerLengthOn = false;
        m.clampAll();
        assertFalse(LongDays.shown(m), "the row is hidden");
        assertEquals(Schedule.LONG_COMBINED, m.sched.longDaysInForce());
        assertEquals(Schedule.LONG_SPLIT, m.sched.longDays, "the choice itself is kept");
        assertFalse(m.sched.isTrainingDay(on(Schedule.MON)));
        assertTrue(m.sched.isTrainingDay(on(Schedule.TUE)));
        assertEquals(Schedule.PLAN_ANY, m.sched.planAt(on(Schedule.TUE)));
        // Not enrolled: the same.
        Model n = owner(Schedule.LONG_SPLIT);
        n.trainerEnrolled = false;
        n.clampAll();
        assertEquals(Schedule.LONG_COMBINED, n.sched.longDaysInForce());
        // A schedule nobody told anything reads as it always did.
        Schedule fresh = Schedule.ofMask("0101010");
        fresh.longDays = Schedule.LONG_SPLIT;
        assertFalse(fresh.isTrainingDay(on(Schedule.MON)));
    }

    /** Test 6 (and K22): the migration of a week saved before the setting. */
    @Test void anOldWeekMapsToTheChoiceItAlreadyWas() throws Exception {
        JSONObject alt = new JSONObject();
        alt.put("days", "1111110");
        alt.put("plan", "1212120");
        assertEquals(Schedule.LONG_SPLIT, Schedule.fromJson(alt).longDays, "GLGLGL-");
        JSONObject any = new JSONObject();
        any.put("days", "0101010");
        any.put("plan", "0000000");
        assertEquals(Schedule.LONG_COMBINED, Schedule.fromJson(any).longDays, "all ANY");
        // Saved: survives a round trip, the flags do not travel.
        Model m = owner(Schedule.LONG_CAP90);
        Schedule back = Schedule.fromJson(m.sched.toJson());
        assertEquals(Schedule.LONG_CAP90, back.longDays);
        assertFalse(back.bothTracks, "the model's facts are told again on load, not saved");
        Model loaded = Model.fromJson(m.toJson());
        assertTrue(loaded.sched.bothTracks, "a load tells them (Model#clampAll)");
        assertEquals(Schedule.LONG_CAP90, loaded.sched.longDaysInForce());
    }

    /** Test 4's switch (K10): the 90-minute card's tap sets alternate days, Undo restores the
     *  choice that was. */
    @Test void theSwitchSetsAlternateDaysAndUndoRestoresTheChoice() {
        Model m = owner(Schedule.LONG_CAP90);
        Schedule was = m.sched.copy();
        DayLength.switchToAlternate(m);
        assertEquals(Schedule.LONG_SPLIT, m.sched.longDays);
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.MON)));
        Schedule wrote = m.sched.copy();
        assertTrue(DayLength.undoAlternate(m, was, wrote));
        assertEquals(Schedule.LONG_CAP90, m.sched.longDays, "Undo restores CAP90");
        assertFalse(m.sched.isTrainingDay(on(Schedule.MON)));
    }

    /** Leaving an alternate week for a "same days" choice: the alternation an old switch
     *  wrote into each day's plan goes back to the plan's choice, so both tracks run. */
    @Test void sameDaysPutsAnOldAlternationBackToBothTracks() {
        Model m = owner(Schedule.LONG_SPLIT);
        m.sched.applyWeekShape(Schedule.SHAPE_ALTERNATE, Schedule.alternateDays(),
                               Schedule.PLAN_LENGTH);
        LongDays.choose(m, Schedule.LONG_COMBINED);
        for (int i = 0; i < Schedule.DAYS; i++)
            assertEquals(Schedule.PLAN_ANY, m.sched.plan[i]);
        assertEquals(Schedule.PLAN_ANY, m.sched.planAt(on(Schedule.MON)));
        // A week set day by day is the person's and is kept.
        Model own = owner(Schedule.LONG_SPLIT);
        own.sched.plan[Schedule.TUE] = Schedule.PLAN_BOTH;
        own.sched.plan[Schedule.THU] = Schedule.PLAN_GIRTH;
        LongDays.choose(own, Schedule.LONG_CAP90);
        assertEquals(Schedule.PLAN_BOTH, own.sched.plan[Schedule.TUE]);
        assertEquals(Schedule.PLAN_GIRTH, own.sched.planAt(on(Schedule.THU)));
    }

    @Test void theRowsWordsAreTheAppsAndCycleInOrder() {
        assertEquals("Long training days", LongDays.ROW);
        assertEquals("Alternate days, each track 3 days a week",
            LongDays.label(Schedule.LONG_SPLIT));
        assertEquals("Alternate on my days", LongDays.label(Schedule.LONG_ALTERNATE));
        assertEquals("Same days, stop growing at 90 min", LongDays.label(Schedule.LONG_CAP90));
        assertEquals("Same days, no limit", LongDays.label(Schedule.LONG_COMBINED));
        assertEquals("Girth Mon/Wed/Fri, length Tue/Thu/Sat. About an hour a day.",
            LongDays.effect(Schedule.LONG_SPLIT));
        assertEquals("Both tracks on your days; holds and strain sets stop growing once the "
            + "day would pass 90 min.", LongDays.effect(Schedule.LONG_CAP90));
        assertEquals(Schedule.LONG_ALTERNATE, LongDays.next(Schedule.LONG_SPLIT));
        assertEquals(Schedule.LONG_SPLIT, LongDays.next(Schedule.LONG_COMBINED));
        assertEquals(Schedule.LONG_SPLIT, Schedule.LONG_DAYS_NEW_SETUP, "a new setup's");
    }

    @Test void theWeekLineSaysTheWeekInForce() {
        Model m = owner(Schedule.LONG_SPLIT);
        assertEquals("Mon girth · Tue length · Wed girth · Thu length · Fri girth · Sat length",
            LongDays.weekLine(m.sched));
        assertTrue(m.sched.planSetByLongDays());
        LongDays.choose(m, Schedule.LONG_COMBINED);
        assertFalse(m.sched.planSetByLongDays());
    }
    /** An upgrader whose old alternate week led with length ("LGLGLG-", the old setup's
     *  default lead) maps to the split week and keeps each track on the days it had: length
     *  Mon/Wed/Fri, girth Tue/Thu/Sat, and the row and the week line say so. */
    @Test void aLengthFirstOldWeekKeepsItsDaysUnderTheSplitWeek() throws Exception {
        JSONObject o = new JSONObject();
        o.put("days", "1111110");
        o.put("plan", "2121210");
        Model m = owner(Schedule.LONG_SPLIT);
        m.sched = Schedule.fromJson(o);
        assertEquals(Schedule.LONG_SPLIT, m.sched.longDays, "LGLGLG-");
        m.clampAll();
        assertTrue(m.sched.splitLengthFirst());
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planAt(on(Schedule.MON)));
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.TUE)));
        assertEquals(Schedule.PLAN_LENGTH, m.sched.planAt(on(Schedule.FRI)));
        assertEquals(Schedule.PLAN_GIRTH, m.sched.planAt(on(Schedule.SAT)));
        assertEquals(3, m.sched.countForTrack(Plan.TRACK_GIRTH_INTERVAL));
        assertEquals(3, m.sched.countForTrack(Plan.TRACK_LENGTH));
        assertEquals("Mon length · Tue girth · Wed length · Thu girth · Fri length · Sat girth",
            LongDays.weekLine(m.sched));
        assertEquals(LongDays.SPLIT_LENGTH_FIRST, LongDays.effect(m.sched, Schedule.LONG_SPLIT));
        // A week stored any other way runs girth first, as the setting says.
        Model g = owner(Schedule.LONG_SPLIT);
        assertFalse(g.sched.splitLengthFirst());
        assertEquals(LongDays.effect(Schedule.LONG_SPLIT),
            LongDays.effect(g.sched, Schedule.LONG_SPLIT));
    }
}
