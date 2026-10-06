package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;

import org.junit.jupiter.api.Test;

/**
 * G3 (the owner's decision, 2026-10-01): THE TIME-BASED FALLBACK WHEN NOTHING IS MEASURED.
 * Where the readings are the only thing that moves the volume - interval girth at Levels 3 and
 * 4, and length from month 3 - four training weeks with no qualifying reading make the plan
 * offer +2 sets (girth) or +1 strain set (length) itself, on its usual card, saying why. An
 * accepted step restarts the count (and the yield / strain clocks); any reading hands the
 * volume back to the reading-driven rules; the level's top (G4) stops it.
 */
class NoReadingsFallbackTest {

    private static final long H = 3600000L;

    /** Monday 2026-09-07, plus `week` weeks and `day` days, at `hour`:00 local. */
    private static long at(int week, int day, int hour) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7, hour, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, week * 7 + day);
        return c.getTimeInMillis();
    }

    private static Plan.Inputs girth(int level, int weeks, int workSets) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = level;
        in.monthIndex = 7;
        in.pressureKpa = 30;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.weeksWithoutReadings = weeks;
        in.workSets = workSets;
        return in;
    }

    @Test void girthAtLevelThreeAndFourAfterFourWeeksOfNothing() {
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(girth(Plan.L3, 3, 10)).action,
            "three weeks: the no-data hold, as before");
        Plan.Decision d = Plan.evaluate(girth(Plan.L3, 4, 10));
        assertEquals(Plan.ACTION_ADD_VOLUME, d.action);
        assertEquals(2, d.setsDelta);
        // EXPECTATION CHANGED (R-29: the girth card names girth).
        assertEquals("No girth readings for 4 weeks: add 2 sets? Measuring lets the plan "
            + "adjust to you.", d.reason);
        assertEquals(Plan.ACTION_ADD_VOLUME, Plan.evaluate(girth(Plan.L4, 6, 14)).action);

        Plan.Decision near = Plan.evaluate(girth(Plan.L3, 4, 13));
        assertEquals(1, near.setsDelta, "the last step lands on the top");
        assertTrue(near.reason.contains("add 1 set?"), near.reason);
        Plan.Decision top = Plan.evaluate(girth(Plan.L3, 4, 14));
        assertEquals(Plan.ACTION_HOLD, top.action);
        assertTrue(Plan.atVolumeTop(top), "at the top the card says so: " + top.reason);

        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(girth(Plan.L2, 8, 10)).action,
            "Level 2 follows its week table");
        // EXPECTATION CHANGED (R-25/R-29: traditional has the fallback too, one hold at a time,
        // up to its top of 9 at Level 4).
        Plan.Inputs trad = girth(Plan.L4, 8, 8);
        trad.track = Plan.TRACK_GIRTH_TRADITIONAL;
        Plan.Decision t = Plan.evaluate(trad);
        assertEquals(Plan.ACTION_ADD_VOLUME, t.action, "traditional: +1 hold");
        assertEquals(1, t.setsDelta);
        assertEquals("No girth readings for 4 weeks: add 1 hold? Measuring lets the plan "
            + "adjust to you.", t.reason);
        trad.workSets = 9;
        assertTrue(Plan.atVolumeTop(Plan.evaluate(trad)), "...and stops at its top");
    }

    @Test void aReadingDrivenStepComesFirstAndTheDeloadStillWins() {
        Plan.Inputs in = girth(Plan.L3, 4, 10);
        in.inDeloadWeek = true;
        assertEquals(Plan.ACTION_DELOAD, Plan.evaluate(in).action);
    }

    private static Plan.Inputs length(int month, int weeks, int sets) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = month;
        in.pressureKpa = 30;
        in.ceilKpa = 43;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.loadLb = 10.0;
        in.strainSets = sets;
        in.firstDeloadPending = false;
        in.weeksWithoutReadings = weeks;
        return in;
    }

    @Test void lengthFromMonthThree() {
        Plan.Decision d = Plan.evaluate(length(7, 4, 2));
        assertEquals(Plan.ACTION_ADD_VOLUME, d.action);
        assertEquals(1, d.setsDelta);
        // t10 R-47 (L3): the length card's own words, and its top of 6.
        assertEquals("No length readings for 4 weeks: add a strain set (up to 6). Measuring "
            + "lets the plan adjust to you", d.reason);
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(length(7, 3, 2)).action);
        assertEquals(Plan.ACTION_ADD_VOLUME, Plan.evaluate(length(7, 4, 5)).action);
        for (int sets : new int[]{ 6, 9, 12 }) {
            Plan.Decision top = Plan.evaluate(length(7, 9, sets));
            assertEquals(Plan.ACTION_HOLD, top.action, sets + " sets");
            assertEquals(Plan.LENGTH_NO_READINGS_TOP_RULE, top.rule);
            assertTrue(top.reason.contains("past 6, the plan needs readings"), top.reason);
            assertTrue(top.reason.startsWith("At 6 strain sets the plan needs a reading"),
                top.reason);   // polish TR-32
        }

        Plan.Decision early = Plan.evaluate(length(2, 9, 2));
        assertFalse(early.rule.startsWith(Plan.NO_READINGS_RULE),
            "before month 3 the calendar rungs drive: " + early.rule);
    }

    /* ---- the count, from the logs ---------------------------------------------------- */

    private static Model model() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = at(0, 0, 7);
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        m.trainerGirth.level = Plan.L3;
        String[] ids = { "g", "l" };
        int[] tracks = { Plan.TRACK_GIRTH_INTERVAL, Plan.TRACK_LENGTH };
        for (int i = 0; i < ids.length; i++) {
            Model.Routine r = new Model.Routine();
            r.id = ids[i];
            r.trainerTrack = tracks[i];
            m.routines.add(r);
        }
        // Tue / Thu / Sat, both tracks, five weeks.
        int[] days = { 1, 3, 5 };
        for (int w = 0; w < 5; w++)
            for (int d = 0; d < days.length; d++) {
                file(m, "l", at(w, days[d], 9));
                file(m, "g", at(w, days[d], 11));
            }
        return m;
    }

    private static Model.Sess file(Model m, String routineId, long ts) {
        Model.Sess s = new Model.Sess();
        s.id = "s" + routineId + ts;
        s.routineId = routineId;
        s.ts = ts;
        s.dayKey = PhotoCalendar.dayKey(ts);
        m.sessLog.all.add(0, s);
        return s;
    }

    @Test void theCountRunsFromTheLastReadingOrTheLastStep() {
        Model m = model();
        long now = at(5, 0, 8);
        Model.TrainerTrackState g = m.trainerGirth;
        assertEquals(5, TrainerTab.weeksWithoutReadings(m, Plan.TRACK_GIRTH_INTERVAL,
            TrainerTab.lastYieldReadingMs(m, Plan.TRACK_GIRTH_INTERVAL), g.yieldSinceMs, now),
            "never measured: five training weeks since enrolment");
        Plan.Inputs in = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, g, 7, now, in);
        assertEquals(5, in.weeksWithoutReadings);

        // A girth yield pair in week 3 hands the volume back to the yield rule.
        Model.Sess y = file(m, "g", at(3, 5, 15));
        y.afterBaseThisSession = true;
        y.afterBaseTs = y.ts - H;
        y.afterGirAbsCm = Double.valueOf(13.0);
        y.afterGirCm = Double.valueOf(0.3);
        assertEquals(y.ts, TrainerTab.lastYieldReadingMs(m, Plan.TRACK_GIRTH_INTERVAL));
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, g, 7, now, in);
        assertEquals(1, in.weeksWithoutReadings, "only week 4 is after the reading");

        // An accepted fallback step restarts the count, through the yield clock.
        Model fresh = model();
        Model.TrainerTrackState fg = fresh.trainerGirth;
        Plan.Decision step = Plan.evaluate(girth(Plan.L3, 5, 10));
        assertTrue(Mint.commitYield(fg, Plan.TRACK_GIRTH_INTERVAL, step, now));
        assertEquals(2, fg.yieldSets);
        assertEquals(now, fg.yieldSinceMs, "the yield streak restarts on a fallback step too");
        TrainerTab.fillGirthInputs(fresh, Plan.TRACK_GIRTH_INTERVAL, fg, 7, now, in);
        assertEquals(0, in.weeksWithoutReadings, "another four weeks before the next one");
    }

    @Test void theLengthCountReadsStrainPairsAndTheStrainClock() {
        Model m = model();
        long now = at(5, 0, 8);
        assertEquals(5, TrainerTab.lengthWeeksWithoutReadings(m, now));

        Model.Reading pre = new Model.Reading();
        pre.id = "p"; pre.ts = at(2, 1, 8); pre.phase = Model.Reading.PHASE_PRE;
        pre.method = Model.Reading.METHOD_BPSSL; pre.state = Model.Reading.stateForMethod(pre.method);
        pre.len = 16.0;
        Model.Reading post = new Model.Reading();
        post.id = "q"; post.ts = at(2, 1, 10); post.phase = Model.Reading.PHASE_POST;
        post.method = Model.Reading.METHOD_BPSSL; post.state = pre.state;
        post.len = 16.5; post.pairOf = "p";
        m.measLog.all.add(post);
        m.measLog.all.add(pre);
        assertEquals(post.ts, Meas.lastPairedMs(m.measLog, Model.Reading.METHOD_BPSSL));
        assertEquals(2, TrainerTab.lengthWeeksWithoutReadings(m, now),
            "weeks 3 and 4 after the pair");

        m.trainerLength.setStrainSets(3, at(4, 0, 8));
        assertEquals(1, TrainerTab.lengthWeeksWithoutReadings(m, now),
            "an accepted strain set restarts the count");

        Model lone = model();
        Model.Reading only = new Model.Reading();
        only.id = "o"; only.ts = at(4, 1, 8); only.phase = Model.Reading.PHASE_PRE;
        only.method = Model.Reading.METHOD_BPSSL; only.len = 16.0;
        lone.measLog.all.add(only);
        assertEquals(0L, Meas.lastPairedMs(lone.measLog, Model.Reading.METHOD_BPSSL),
            "a reading the strain rung cannot read is not a measurement it acts on");
    }
}
