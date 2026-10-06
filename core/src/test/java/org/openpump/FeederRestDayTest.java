package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * S16 (the owner's decision, 2026-09-26): WHETHER THE FEEDER RUNS ON REST DAYS IS A SETTING,
 * AND ITS DEFAULT IS TRAINING DAYS ONLY.
 *
 * The guidance differs with itself. Its girth routines place feeder sets on training days;
 * elsewhere it allows light pumping at reduced pressure on rest days. The app used to offer
 * the feeder on rest days without saying which it followed. Now the default follows the
 * training-days reading, the setting follows the rest-days one, and the card names
 * both.
 */
class FeederRestDayTest {

    /** Monday 2026-09-07 at 12:00 local, plus `day` days. */
    private static long at(int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7, 12, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, day);
        return c.getTimeInMillis();
    }

    /** Enrolled at L3 with a saved feeder and a Mon/Wed/Fri week; nothing filed. */
    private static Model model() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerGirth.level = Plan.L3;
        Model.Routine f = new Model.Routine();
        f.id = "feeder";
        f.trainerTrack = Plan.TRACK_FEEDER;
        m.routines.add(f);
        m.trainerFeederMintId = "feeder";
        m.sched = Schedule.ofMask("1010100");
        return m;
    }

    /** Files the day's girth session, so the feeder is what is left on a training day. */
    private static void girthDone(Model m, long ts) {
        Model.Routine g = new Model.Routine();
        g.id = "g" + ts;
        g.trainerTrack = Plan.TRACK_GIRTH_INTERVAL;
        m.routines.add(g);
        m.trainerGirth.lastMintId = g.id;
        Model.Sess s = new Model.Sess();
        s.id = "s" + ts;
        s.routineId = g.id;
        s.ts = ts;
        /* FIXTURE CHANGED (t10 fix, review A F4): the feeder follows a girth session that RAN
         * (TrainerTab#ran) - a 0:00 attempt filed at the seal check is not one - so the day's
         * girth session is filed as a finished one. */
        s.completed = true;
        s.durSec = 40 * 60;
        m.sessLog.all.add(0, s);
    }

    @Test void theDefaultIsTrainingDaysOnly() throws Exception {
        assertFalse(new Model().feederRestDays, "a new install follows the training-days reading");
        String old = "{\"ceil\":40,\"unit\":\"inHg\",\"selected\":\"r1\",\"sets\":[],"
            + "\"routines\":[{\"id\":\"r1\",\"name\":\"R\",\"sets\":[]}]}";
        assertFalse(Model.fromJson(old).feederRestDays,
            "a file saved before the setting reads training days only - the owner's default");
        Model m = new Model();
        m.feederRestDays = true;
        assertTrue(Model.fromJson(m.toJson()).feederRestDays, "the person's yes survives a save");
    }

    @Test void onARestDayTheFeederStandsDownWhateverTheOldSettingSays() {
        // t10 R-09 (A6): feeders follow the girth session, on girth days only, and "feeder on
        // rest days too" is disabled - its saved value kept, read as false.
        Model m = model();
        long tue = at(1);                                  // not a training day
        assertFalse(TrainerTab.feederReady(m, tue), "no girth session today: no feeder");
        assertEquals(Schedule.PLAN_REST, TrainerTab.dayPlanNow(m, tue));
        assertTrue(TrainerTab.feederOffToday(m, tue));
        assertEquals(UpNext.NOTHING, TrainerTab.upNextNow(m, tue, false).what,
            "nothing is offered on a rest day");
        m.feederRestDays = true;
        assertTrue(TrainerTab.feederOffToday(m, tue), "the old yes no longer reaches it");
        assertEquals(UpNext.NOTHING, TrainerTab.upNextNow(m, tue, false).what);
        assertTrue(m.feederRestDays, "the saved answer is kept");
    }

    @Test void onATrainingDayTheFeederFollowsTheWorkEitherWay() {
        for (boolean restDays : new boolean[]{ false, true }) {
            Model m = model();
            m.feederRestDays = restDays;
            long mon = at(0);
            assertFalse(TrainerTab.feederOffToday(m, mon));
            assertEquals(UpNext.GIRTH, TrainerTab.upNextNow(m, mon, false).what,
                "the day's work comes first");
            girthDone(m, mon - 3600000L);
            assertEquals(UpNext.FEEDER, TrainerTab.upNextNow(m, mon, false).what,
                "then the feeder (rest days " + restDays + ")");
        }
    }

    @Test void aTodayOnlyOverrideDecidesWhatKindOfDayItIs() {
        Model m = model();
        long tue = at(1);
        m.sched.setOverride(PhotoCalendar.dayKey(tue), Schedule.PLAN_GIRTH);
        assertFalse(TrainerTab.feederOffToday(m, tue), "training today after all");
        long wed = at(2);
        m.sched.setOverride(PhotoCalendar.dayKey(wed), Schedule.PLAN_REST);
        assertTrue(TrainerTab.feederOffToday(m, wed), "a rest chosen for today is a rest day");
    }

    @Test void thePureRuleAndItsOldForm() {
        assertEquals(UpNext.NOTHING, UpNext.pick(Schedule.PLAN_REST, false, false, false, true,
            true, false, false).what, "rest day, training days only");
        assertEquals(UpNext.FEEDER, UpNext.pick(Schedule.PLAN_REST, false, false, false, true,
            true, false, true).what, "rest day, rest days too");
        assertEquals(UpNext.FEEDER, UpNext.pick(Schedule.PLAN_ANY, false, true, true, true,
            true, false, false).what, "a training day's done work, training days only");
        assertEquals(UpNext.FEEDER, UpNext.pick(Schedule.PLAN_REST, false, false, false, true,
            true, false).what, "the old form keeps its old answer for its old callers");
        assertEquals(UpNext.REMAINDER, UpNext.pick(Schedule.PLAN_REST, false, false, false,
            true, true, true, false).what, "an unfinished session still outranks it");
    }

    @Test void theCardNamesBothSources() {
        for (boolean restDays : new boolean[]{ false, true }) {
            String s = TrainerTab.feederDaysNote(restDays);
            // Both positions, and no source named (the owner's rule, 2026-09-26).
            assertTrue(s.contains("one part of the guidance") && s.contains("another"), s);
            assertTrue(!NoBookNamesTest.namesASource(s), s);
        }
        assertTrue(TrainerTab.feederDaysNote(false).startsWith("Training days only"));
        assertTrue(TrainerTab.feederDaysNote(true).startsWith("Rest days too"));
        String why = TrainerTab.feederDaysWhy();
        assertTrue(why.contains("One part") && why.contains("Another")
            && !NoBookNamesTest.namesASource(why), why);
        assertTrue(TrainerTab.feederDaysNote(false).length() <= 140
            && TrainerTab.feederDaysNote(true).length() <= 140, "one short line on the face");
    }
}
