package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Parity run 3, A3-1: THE L1 -> L2 CROSSING WEEK IS NOT A LEVEL 2 TABLE WEEK. The crossing on a
 * Saturday anchors Level 2's week table there (TrainerTrackState#weekBaseMs), but that week's
 * Monday, Wednesday and Friday ran at Level 1 - and the per-track count read the whole week from
 * its Monday, so the week counted and Level 2 ran a week ahead (one hold more, a week early).
 * A track's own weeks count only sessions at or after the anchor, as the pressure clock and the
 * plan-wide cadence already do.
 */
class CrossingWeekTableTest {

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    private static void girth(Model m, long ts) {
        Model.Sess s = new Model.Sess();
        s.id = "g" + ts; s.routineId = "PG"; s.ts = ts; s.durSec = 40 * 60; s.completed = true;
        m.sessLog.file(s);
    }

    @Test void theCrossingWeeksLevelOneSessionsDoNotCount() {
        Model m = new LengthYear().setup().m;
        for (int d : new int[]{ 0, 2, 4 }) girth(m, day(d, 9));      // Level 1, Mon/Wed/Fri
        long crossing = day(5, 10);                                    // Saturday: Level 2
        assertEquals(0, TrainerTab.accumulatedTrainingWeeks(m, Plan.TRACK_GIRTH_INTERVAL,
            crossing, day(7, 8)), "the crossing week is no Level 2 week");
        for (int d : new int[]{ 7, 9, 11 }) girth(m, day(d, 9));     // Level 2's first week
        assertEquals(1, TrainerTab.accumulatedTrainingWeeks(m, Plan.TRACK_GIRTH_INTERVAL,
            crossing, day(12, 8)), "one Level 2 week");
    }
}
