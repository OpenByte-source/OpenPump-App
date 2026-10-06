package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * S13 (the owner's decision, 2026-09-26): TRACK-AWARE MEASUREMENT CADENCE AND PAIRING.
 *
 *  (a) The "1st and 4th session" cadence counts the sessions of the track being started, not
 *      of every track - with two sessions a day the 4th landed on day 2's girth session.
 *  (b) Length strain pairs a pre and a post from the SAME session, not the newest of each for
 *      the day - a second measured session used to replace the first.
 *  (c) A girth baseline taken soon after a length session is "after other work" - the length
 *      coda has already expanded the tissue - and is left out of the girth yield streak.
 */
class TrackMeasurementTest {

    private static final long H = 3600000L;

    /** Monday 2026-09-07 at `hour`:00 local, plus `day` days. */
    private static long at(int day, int hour) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7, hour, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, day);
        return c.getTimeInMillis();
    }

    private static Model withRoutines() {
        Model m = new Model();
        m.trainerEnrolled = true;
        int[][] rs = { { Plan.TRACK_GIRTH_INTERVAL }, { Plan.TRACK_GIRTH_TRADITIONAL },
                       { Plan.TRACK_LENGTH }, { Plan.TRACK_FEEDER },
                       { Model.TRAINER_TRACK_NONE } };
        String[] ids = { "g", "t", "l", "f", "own" };
        for (int i = 0; i < ids.length; i++) {
            Model.Routine r = new Model.Routine();
            r.id = ids[i];
            r.trainerTrack = rs[i][0];
            m.routines.add(r);
        }
        return m;
    }

    private static Model.Sess file(Model m, String routineId, long ts, boolean manual) {
        Model.Sess s = new Model.Sess();
        s.id = "s" + m.sessLog.all.size();
        s.routineId = routineId;
        s.ts = ts;
        s.manual = manual;
        m.sessLog.all.add(0, s);
        return s;
    }

    /* ------------------------------------------------------------------ (a) cadence */

    @Test void theCadenceCountsTheTrackBeingStarted() {
        Model m = withRoutines();
        file(m, "l", at(0, 9), false);            // Mon length
        file(m, "g", at(0, 18), false);           // Mon girth
        file(m, "l", at(1, 9), false);            // Tue length
        file(m, "t", at(1, 18), false);           // Tue girth, the other style
        file(m, "f", at(1, 20), false);           // a feeder
        file(m, "g", at(1, 21), true);            // a manual cycle
        long now = at(2, 8);
        assertEquals(6, m.sessionsThisTrainingWeek(now), "the old count: every session");
        assertEquals(2, m.sessionsThisTrainingWeek(now, Plan.TRACK_LENGTH));
        assertEquals(2, m.sessionsThisTrainingWeek(now, Plan.TRACK_GIRTH_INTERVAL),
            "both girth styles are the girth track; a feeder and a manual cycle are not");
        assertEquals(2, m.sessionsThisTrainingWeek(now, Plan.TRACK_GIRTH_TRADITIONAL));
        assertEquals(1, m.sessionsThisTrainingWeek(now, Plan.TRACK_FEEDER));
        assertEquals(6, m.sessionsThisTrainingWeek(now, Model.TRAINER_TRACK_NONE),
            "a routine of your own keeps the old count - the decision is about the tracks");

        Model.Meas cfg = new Model.Meas();
        cfg.mode = Model.MeasLog.MEAS_WEEK_14;
        assertFalse(m.measLog.due(cfg, m.sessionsThisTrainingWeek(now)),
            "the old count put Wednesday's session at #7 - not asked");
        assertFalse(m.measLog.due(cfg, m.sessionsThisTrainingWeek(now, Plan.TRACK_LENGTH)),
            "Wednesday's length session is length's 3rd - not asked");
        file(m, "l", at(2, 9), false);
        assertTrue(m.measLog.due(cfg, m.sessionsThisTrainingWeek(at(3, 8), Plan.TRACK_LENGTH)),
            "Thursday's length session is length's 4th - asked, as the cadence means");
        assertEquals(0, m.sessionsThisTrainingWeek(at(7, 8), Plan.TRACK_LENGTH),
            "a new week starts again at the 1st");
    }

    @Test void theReminderCountsTheTrackUpNextWouldStart() {
        Model m = withRoutines();
        long mon = at(0, 12);
        assertEquals(Model.TRAINER_TRACK_NONE, TrainerTab.cadenceTrackNow(new Model(), mon),
            "no plan: the old count");
        assertEquals(Plan.TRACK_GIRTH_INTERVAL, TrainerTab.cadenceTrackNow(m, mon),
            "girth is next on an ordinary day");
        m.trainerLengthOn = true;
        m.rxLengthFirst = true;
        assertEquals(Plan.TRACK_LENGTH, TrainerTab.cadenceTrackNow(m, mon), "length first");
    }

    /* ------------------------------------------------------------------ (b) pairing */

    private static Model.Reading reading(String id, long ts, int phase, double len, String pair) {
        Model.Reading r = new Model.Reading();
        r.id = id;
        r.ts = ts;
        r.phase = phase;
        r.method = Model.Reading.METHOD_BPSSL;
        r.state = Model.Reading.stateForMethod(r.method);
        r.len = len;
        r.pairOf = pair;
        return r;
    }

    private static double pct(double from, double to) { return (to - from) / from * 100.0; }

    @Test void twoSessionsOnOneDayAreTwoPairs() {
        List<Model.Reading> w = new ArrayList<Model.Reading>();
        w.add(reading("b2", at(0, 19), Model.Reading.PHASE_POST, 10.3, "b1"));
        w.add(reading("b1", at(0, 18), Model.Reading.PHASE_PRE, 10.2, ""));
        w.add(reading("a2", at(0, 10), Model.Reading.PHASE_POST, 10.5, "a1"));
        w.add(reading("a1", at(0, 9), Model.Reading.PHASE_PRE, 10.0, ""));
        double want = (pct(10.0, 10.5) + pct(10.2, 10.3)) / 2.0;
        assertEquals(want, Meas.prePostPct(w).doubleValue(), 1e-9,
            "each post against its own session's pre, not the day's newest against newest");
        assertEquals(want, Meas.prePostPct(w, Model.Reading.METHOD_BPSSL).doubleValue(), 1e-9);
    }

    @Test void aSessionWithNoBaselineOfItsOwnPairsWithNothing() {
        List<Model.Reading> w = new ArrayList<Model.Reading>();
        w.add(reading("b2", at(0, 19), Model.Reading.PHASE_POST, 10.9,
                      Model.Reading.PAIR_NONE));
        w.add(reading("a1", at(0, 9), Model.Reading.PHASE_PRE, 10.0, ""));
        assertNull(Meas.prePostPct(w),
            "the morning's baseline was not this session's - no figure rather than a wrong one");
    }

    @Test void readingsWithNoLinkPairByTheDayAsBefore() {
        List<Model.Reading> w = new ArrayList<Model.Reading>();
        w.add(reading("x3", at(0, 19), Model.Reading.PHASE_POST, 10.4, ""));
        w.add(reading("x2", at(0, 12), Model.Reading.PHASE_POST, 10.6, ""));
        w.add(reading("x1", at(0, 9), Model.Reading.PHASE_PRE, 10.0, ""));
        assertEquals(pct(10.0, 10.4), Meas.prePostPct(w).doubleValue(), 1e-9,
            "an old or standalone pair: the newest of each for the day, unchanged");
    }

    @Test void aLinkedPreIsNotLentToAnotherPost() {
        List<Model.Reading> w = new ArrayList<Model.Reading>();
        w.add(reading("loose", at(0, 20), Model.Reading.PHASE_POST, 11.0, ""));
        w.add(reading("a2", at(0, 10), Model.Reading.PHASE_POST, 10.5, "a1"));
        w.add(reading("a1", at(0, 9), Model.Reading.PHASE_PRE, 10.0, ""));
        assertEquals(pct(10.0, 10.5), Meas.prePostPct(w).doubleValue(), 1e-9,
            "a1 belongs to a2's session; the unlinked post has no pre of its own that day");
        List<Model.Reading> gone = new ArrayList<Model.Reading>();
        gone.add(reading("a2", at(0, 10), Model.Reading.PHASE_POST, 10.5, "a1-deleted"));
        gone.add(reading("z1", at(0, 9), Model.Reading.PHASE_PRE, 10.0, ""));
        assertNull(Meas.prePostPct(gone), "a post whose own pre is gone pairs with nothing");
    }

    @Test void strainReadsTheSessionPairs() {
        Model m = new Model();
        long now = at(0, 21);
        m.measLog.all.add(reading("b2", at(0, 19), Model.Reading.PHASE_POST, 10.3, "b1"));
        m.measLog.all.add(reading("b1", at(0, 18), Model.Reading.PHASE_PRE, 10.2, ""));
        m.measLog.all.add(reading("a2", at(0, 10), Model.Reading.PHASE_POST, 10.5, "a1"));
        m.measLog.all.add(reading("a1", at(0, 9), Model.Reading.PHASE_PRE, 10.0, ""));
        double want = (pct(10.0, 10.5) + pct(10.2, 10.3)) / 2.0;
        assertEquals(want, Meas.strainPct(m.measLog, Model.Reading.METHOD_BPSSL, now), 1e-9,
            "the engine's strain figure is the per-session one");
    }

    @Test void theAfterReadingIsFiledWithItsOwnSessionsBaseline() {
        Model.Sess s = new Model.Sess();
        s.afterBaseThisSession = true;
        s.afterBaseId = "m123";
        assertEquals("m123", Meas.pairFor(s));
        s.afterBaseThisSession = false;             // diffed against an older baseline
        assertEquals(Model.Reading.PAIR_NONE, Meas.pairFor(s));
        s.afterBaseThisSession = true;
        s.afterBaseId = null;
        assertEquals(Model.Reading.PAIR_NONE, Meas.pairFor(s));
        assertEquals(Model.Reading.PAIR_NONE, Meas.pairFor(null));
    }

    @Test void theLinkIsSavedCopiedAndAbsentOnOldReadings() throws Exception {
        Model.Reading r = reading("p", at(0, 10), Model.Reading.PHASE_POST, 10.5, "a1");
        assertEquals("a1", Model.Reading.fromJson(r.toJson()).pairOf);
        assertEquals("a1", r.copy().pairOf, "an edit's staged copy keeps it");
        Model.Reading none = reading("q", at(0, 10), Model.Reading.PHASE_POST, 10.5, "");
        assertFalse(none.toJson().has("pair"), "an unlinked reading writes what it always did");
        assertEquals("", Model.Reading.fromJson(new JSONObject(
            "{\"id\":\"o\",\"ts\":\"1\",\"len\":\"10\",\"gir\":\"0\",\"phase\":2}")).pairOf,
            "a reading saved before the link has none");
        assertEquals(Model.Reading.PAIR_NONE, Model.Reading.fromJson(reading("n", 1L,
            Model.Reading.PHASE_POST, 10, Model.Reading.PAIR_NONE).toJson()).pairOf);
    }

    /* ------------------------------------------------------------ (c) after other work */

    private static Model.Sess girthWithYield(Model m, long baseTs, long fileTs,
                                             double pre, double post) {
        Model.Sess s = file(m, "g", fileTs, false);
        s.afterBaseThisSession = true;
        s.afterComparable = true;           // R-24: a like-for-like pair, run to the end
        s.completed = true;
        s.afterBaseTs = baseTs;
        s.afterGirAbsCm = Double.valueOf(post);
        s.afterGirCm = Double.valueOf(post - pre);
        return s;
    }

    @Test void aGirthBaselineSoonAfterALengthSessionIsAfterOtherWork() {
        Model m = withRoutines();
        file(m, "l", at(0, 9), false);                             // length filed 09:00
        Model.Sess soon = girthWithYield(m, at(0, 9) + H, at(0, 10), 12.0, 12.1);
        assertTrue(TrainerTab.baselineAfterOtherWork(m, soon), "an hour after the length coda");
        Model.Sess later = girthWithYield(m, at(0, 9) + TrainerTab.AFTER_OTHER_WORK_MS,
                                          at(0, 15), 12.0, 12.4);
        assertFalse(TrainerTab.baselineAfterOtherWork(m, later), "far enough apart");
        Model.Sess before = girthWithYield(m, at(1, 8), at(1, 9), 12.0, 12.4);
        file(m, "l", at(1, 10), false);                            // length AFTER the girth
        assertFalse(TrainerTab.baselineAfterOtherWork(m, before), "girth first is not after");
        Model.Sess manualOnly = girthWithYield(m, at(2, 10), at(2, 11), 12.0, 12.1);
        file(m, "l", at(2, 9), true);                              // a manual cycle
        file(m, "f", at(2, 9) + 1, false);                         // and a feeder
        assertFalse(TrainerTab.baselineAfterOtherWork(m, manualOnly),
            "only a length session is the other work the decision names");
        Model.Sess stale = file(m, "g", at(0, 10) + 1, false);
        stale.afterBaseThisSession = false;
        stale.afterBaseTs = at(0, 9) + H;
        assertFalse(TrainerTab.baselineAfterOtherWork(m, stale),
            "no baseline of its own - nothing to mark");
        Model.Sess lengthItself = file(m, "l", at(0, 10) + 2, false);
        lengthItself.afterBaseThisSession = true;
        lengthItself.afterBaseTs = at(0, 9) + H;
        assertFalse(TrainerTab.baselineAfterOtherWork(m, lengthItself),
            "the decision is about a GIRTH baseline");
        assertEquals(4L * H, TrainerTab.AFTER_OTHER_WORK_MS,
            "the lower bound of the only same-day spacing the guidance gives");
    }

    @Test void theYieldStreakLeavesThoseSessionsOut() {
        Model m = withRoutines();
        double lo = Plan.yieldTargetLo(Plan.L1);
        double lowPost = 12.0 * (1.0 + (lo * 0.5) / 100.0);        // half the target: low
        for (int d = 0; d < 3; d++) {
            file(m, "l", at(d, 9), false);
            girthWithYield(m, at(d, 9) + H, at(d, 10), 12.0, lowPost);
        }
        TrainerTab.YieldStreaks ys = TrainerTab.yieldStreaks(m, Plan.TRACK_GIRTH_INTERVAL,
                                                             Plan.L1);
        assertEquals(0, ys.consecutiveLow,
            "three low readings taken on already-expanded tissue add no girth sets");
        assertFalse(ys.hasData);

        Model plain = withRoutines();
        for (int d = 0; d < 3; d++) girthWithYield(plain, at(d, 9), at(d, 10), 12.0, lowPost);
        assertEquals(3, TrainerTab.yieldStreaks(plain, Plan.TRACK_GIRTH_INTERVAL, Plan.L1)
            .consecutiveLow, "without the length session before them they count, as before");
    }
}
