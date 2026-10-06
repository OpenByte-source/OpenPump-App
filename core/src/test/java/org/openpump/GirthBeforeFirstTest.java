package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;

import org.junit.jupiter.api.Test;

/**
 * G1 (the owner's decision, 2026-10-01): MEASURE GIRTH BEFORE LENGTH. On a both-tracks day
 * with length first, the girth before reading is asked at the length session's START, the
 * girth session that follows takes it as its own, and the pair counts for yield - while a
 * girth baseline genuinely taken after the length work is still left out (S13 c).
 */
class GirthBeforeFirstTest {

    private static final long H = 3600000L;

    /** Tuesday 2026-09-08 at `hour`:`min` local. */
    private static long at(int hour, int min) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 8, hour, min, 0);
        return c.getTimeInMillis();
    }

    private static Model model() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerGirth.level = Plan.L3;
        for (int i = 0; i < m.sched.days.length; i++) m.sched.days[i] = true;
        m.std.on = true;
        m.meas.mode = "sessions";
        m.meas.n = 5;
        String[] ids = { "g", "l" };
        int[] tracks = { Plan.TRACK_GIRTH_INTERVAL, Plan.TRACK_LENGTH };
        for (int i = 0; i < ids.length; i++) {
            Model.Routine r = new Model.Routine();
            r.id = ids[i];
            r.trainerTrack = tracks[i];
            m.routines.add(r);
        }
        return m;
    }

    private static Model.Sess file(Model m, String routineId, long ts) {
        Model.Sess s = new Model.Sess();
        s.id = "s" + ts;
        s.routineId = routineId;
        s.ts = ts;
        s.dayKey = PhotoCalendar.dayKey(ts);
        m.sessLog.all.add(0, s);
        return s;
    }

    private static Model.Reading pre(Model m, long ts, int method) {
        Model.Reading r = new Model.Reading();
        r.id = "m" + ts;
        r.ts = ts;
        r.method = method;
        r.phase = Model.Reading.PHASE_PRE;
        r.len = 16.0;
        r.gir = 12.7;
        m.measLog.log(r, m.meas);
        return r;
    }

    @Test void theRuleItself() {
        assertTrue(SameDay.girthBeforeFirst(true, true, true, false, true),
            "due at the girth session: asked at the length start instead");
        assertTrue(SameDay.girthBeforeFirst(true, true, true, true, false), "due now");
        assertFalse(SameDay.girthBeforeFirst(false, true, true, true, true),
            "only a length start asks ahead");
        assertFalse(SameDay.girthBeforeFirst(true, false, true, true, true),
            "girth is not still to run today");
        assertFalse(SameDay.girthBeforeFirst(true, true, false, true, true),
            "an at-rest session reading takes no girth - nothing to move");
        assertFalse(SameDay.girthBeforeFirst(true, true, true, false, false), "nothing due");
    }

    @Test void theLengthStartAsksWhenTheGirthSessionWouldBeAsked() {
        Model m = model();
        Model.Routine len = m.routine("l");
        long start = at(9, 0);
        m.meas.sinceN = 4;                      // the length run makes it 5: due at girth
        assertFalse(m.measLog.due(m.meas, 0), "not due at the length session itself");
        assertTrue(TrainerTab.measureDueAtGirth(m, start));
        assertTrue(TrainerTab.girthBeforeFirst(m, len, start, false));
        assertTrue(TrainerTab.measureAtStart(m, len, start, false),
            "START asks for the before reading ahead of the length work");

        m.meas.sinceN = 2;
        assertFalse(TrainerTab.measureAtStart(m, len, start, false), "nothing due today");

        m.meas.sinceN = 4;
        m.std.on = false;
        assertFalse(TrainerTab.measureAtStart(m, len, start, false),
            "at rest the session reading takes length only - the day is as it was");
        m.std.on = true;

        file(m, "g", at(7, 0));                 // girth already ran today
        assertFalse(TrainerTab.girthBeforeFirst(m, len, start, false),
            "girth first: nothing to ask ahead of");
    }

    @Test void theGirthSessionTakesTheEarlyReadingAndThePairCounts() {
        Model m = model();
        Model.Routine girth = m.routine("g");
        Model.Reading early = pre(m, at(9, 0), Model.Reading.METHOD_STANDARDIZED);
        file(m, "l", at(9, 10));                // the length session starts after it
        long girthStart = at(10, 30);
        assertSame(early, TrainerTab.earlyGirthBaseline(m, girth, girthStart));
        assertFalse(TrainerTab.measureAtStart(m, girth, girthStart, true),
            "the girth session does not ask again, even with the cadence due");

        // The girth session's pair, before = the early reading, after its own run.
        Model.Sess g = file(m, "g", girthStart);
        g.afterBaseThisSession = true;
        g.afterComparable = true;           // R-24: a like-for-like pair, run to the end
        g.completed = true;
        g.afterBaseTs = early.ts;
        g.afterGirAbsCm = Double.valueOf(12.7 * 1.04);
        g.afterGirCm = Double.valueOf(12.7 * 0.04);
        assertFalse(TrainerTab.baselineAfterOtherWork(m, g),
            "the before reading predates the length session: not after other work");
        TrainerTab.YieldStreaks ys =
            TrainerTab.yieldStreaks(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L3);
        assertTrue(ys.hasData, "the pair counts for yield");
        assertEquals(1, ys.consecutiveLow, "4% is under Level 3's 6%");

        assertNull(TrainerTab.earlyGirthBaseline(m, girth, at(15, 0)),
            "used once: a second girth session takes its own");
    }

    @Test void aBaselineTakenAfterTheLengthWorkIsStillLeftOut() {
        Model m = model();
        Model.Routine girth = m.routine("g");
        file(m, "l", at(9, 0));
        Model.Reading late = pre(m, at(10, 20), Model.Reading.METHOD_STANDARDIZED);
        assertNull(TrainerTab.earlyGirthBaseline(m, girth, at(10, 30)),
            "taken after the length session started - not an early reading");
        Model.Sess g = file(m, "g", at(10, 30));
        g.afterBaseThisSession = true;
        g.afterBaseTs = late.ts;
        g.afterGirAbsCm = Double.valueOf(13.0);
        g.afterGirCm = Double.valueOf(0.3);
        assertTrue(TrainerTab.baselineAfterOtherWork(m, g), "excluded, as today");
        assertFalse(TrainerTab.yieldStreaks(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L3).hasData);
    }

    @Test void noEarlyReadingWithoutAGirthOrALengthSessionBetween() {
        Model m = model();
        Model.Routine girth = m.routine("g");
        pre(m, at(9, 0), Model.Reading.METHOD_BPSSL);   // length only, at rest
        file(m, "l", at(9, 10));
        assertNull(TrainerTab.earlyGirthBaseline(m, girth, at(10, 30)),
            "a length-only reading is no girth before");

        Model n = model();
        pre(n, at(9, 0), Model.Reading.METHOD_STANDARDIZED);
        assertNull(TrainerTab.earlyGirthBaseline(n, n.routine("g"), at(10, 30)),
            "no length session since it: the girth session measures as it always did");
        file(n, "l", at(9, 10));
        assertNull(TrainerTab.earlyGirthBaseline(n, n.routine("l"), at(10, 30)),
            "only a girth session takes it");
        assertNull(TrainerTab.earlyGirthBaseline(n, n.routine("g"), at(9, 0) + 24 * H),
            "yesterday's reading is not today's before");
    }
}
