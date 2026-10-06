package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * THE HALF START (the owner's decision, 0.10, final), for traditional girth tracks whose routine
 * predates the growth (TraditionalGrowthTest): above Level 1 they start at half their level's
 * count - 3 at Level 2, 4 at Levels 3 and 4 - or at what they run now if that is more, and add
 * one hold every 2 counted weeks (the growth's own count) until they reach the level's count at
 * that point; then the level's normal growth. Level 1 and new tracks are the growth as built.
 * Level timing does not move: no gate reads the hold count.
 */
class TraditionalHalfStartTest {

    private static final int T = Plan.TRACK_GIRTH_TRADITIONAL;

    /** A traditional track saved before the growth: no weekGrowth, the old week 0, the old
     *  rule's kept yield sets. */
    static Model.TrainerTrackState older(int level, int yieldSets) throws Exception {
        JSONObject o = new Model.TrainerTrackState().toJson();
        o.remove("weekGrowth");
        o.remove("buildSets"); o.remove("buildWeek"); o.remove("buildSaid");
        o.put("level", level); o.put("week", 0);
        o.put("weekBaseIndex", 0); o.put("weekBaseMs", "1000");
        o.put("yieldSets", yieldSets);
        o.put("pressureKpa", "30.0");
        Model.TrainerTrackState st = Model.TrainerTrackState.fromJson(o);
        assertFalse(st.weekGrowth);
        assertEquals(0, st.buildSets, "an older file has no build-up until it is started");
        return st;
    }

    /** The holds at each counted week from the start (0, 1, 2, ...), as the Trainer's week
     *  advance moves the position and the plan prescribes it. */
    static List<Integer> holds(Model.TrainerTrackState st, int weeks) {
        Model m = new Model();
        List<Integer> out = new ArrayList<Integer>();
        for (int k = 0; k < weeks; k++) {
            st.weekIndex = Mint.advanceFromAnchor(T, st, k);
            int total = Mint.totalSets(T, st);
            Mint.Rx rx = TrainerTab.trackRx(m, T, st, 7, null);
            String at = "counted week " + k + " (position " + st.weekIndex + ")";
            // R-27 (the owner's decision, 2026-10-01): the prescription is the track's count
            // under the session's time cap (L2 6, L3 5, L4 7 five-minute holds).
            assertEquals(Math.min(total, Plan.r2MaxHolds(st.level, Mint.HOLD_TRADITIONAL_SEC,
                Mint.standardFatSec(st.level))), rx.sets,
                at + ": the prescription is the track's count, under the time cap");
            // COUNTED == TARGET: the minutes asked are the holds'.
            assertEquals(rx.sets * Mint.HOLD_TRADITIONAL_SEC / 60.0, rx.netTargetMin, 1e-9, at);
            out.add(total);
        }
        return out;
    }

    private static List<Integer> list(int... xs) {
        List<Integer> l = new ArrayList<Integer>();
        for (int x : xs) l.add(x);
        return l;
    }

    @Test void levelTwoBuildsUpFromThree() throws Exception {
        Model.TrainerTrackState st = older(Plan.L2, 0);
        assertTrue(Mint.startWeekGrowth(T, st, 5_000L));
        assertEquals(3, st.buildSets);
        assertFalse(st.buildSaid, "the notice has it to say");
        assertEquals(list(3, 3, 4, 4, 5, 5, 6, 6, 6, 6, 6, 6), holds(st, 12));
        assertFalse(Mint.buildingUp(T, st), "reached: Level 2's normal 6 from here");
    }

    @Test void levelThreeBuildsUpFromFourUnderItsOwnGrowth() throws Exception {
        Model.TrainerTrackState st = older(Plan.L3, 0);
        assertTrue(Mint.startWeekGrowth(T, st, 5_000L));
        // Level 3's own count: 6 for 4 weeks, then 7 - the build-up is held under it.
        // EXPECTATION CHANGED (R-25, A3: Level 3's top is 7, and it stops the calendar too).
        assertEquals(list(4, 4, 5, 5, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7), holds(st, 14));
        for (int w = 1; w <= 20; w++) {
            st.weekIndex = w;
            assertTrue(Mint.totalSets(T, st) <= Plan.traditionalSets(Plan.L3, w),
                "never over the level's own count at its week " + w);
        }
    }

    @Test void levelFourBuildsUpFromFour() throws Exception {
        Model.TrainerTrackState st = older(Plan.L4, 0);
        assertTrue(Mint.startWeekGrowth(T, st, 5_000L));
        assertEquals(list(4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 8, 8), holds(st, 12));
    }

    @Test void whatTheyRunNowIfThatIsMore() throws Exception {
        // 2 + 3 kept yield = 5 at Level 3: from 5, the kept yield folded into the count.
        Model.TrainerTrackState l3 = older(Plan.L3, 3);
        Mint.startWeekGrowth(T, l3, 5_000L);
        assertEquals(5, l3.buildSets);
        assertEquals(0, l3.yieldSets, "folded: the count is what they run");
        assertEquals(list(5, 5, 6, 6, 7, 7, 7, 7, 7, 7), holds(l3, 10));   // R-25: top 7
        // 2 + 1 = 3 at Level 4: under half, so half - 4.
        Model.TrainerTrackState l4 = older(Plan.L4, 1);
        Mint.startWeekGrowth(T, l4, 5_000L);
        assertEquals(4, l4.buildSets);
        // 2 + 5 = 7 at Level 2, already over its 6: no build-up.
        // EXPECTATION CHANGED (R-25, A3: Level 2's top is 6, so what they ran is held to it).
        Model.TrainerTrackState l2 = older(Plan.L2, 5);
        Mint.startWeekGrowth(T, l2, 5_000L);
        assertEquals(0, l2.buildSets);
        assertTrue(l2.buildSaid, "no build-up to say");
        assertEquals(6, Mint.totalSets(T, l2));
        assertEquals(list(6, 6, 6, 6), holds(l2, 4));
        // ...and exactly the count: 2 + 4 = 6 at Level 2 runs 6, never 6 + 4.
        Model.TrainerTrackState six = older(Plan.L2, 4);
        Mint.startWeekGrowth(T, six, 5_000L);
        assertEquals(6, Mint.totalSets(T, six));
    }

    @Test void levelOneAndNewTracksAreTheGrowthAsBuilt() throws Exception {
        // An older Level 1 track starts at 3 from its first week, kept yield on top, as built.
        Model.TrainerTrackState l1 = older(Plan.L1, 1);
        Mint.startWeekGrowth(T, l1, 5_000L);
        assertEquals(0, l1.buildSets);
        assertTrue(l1.buildSaid);
        assertEquals(1, l1.yieldSets);
        List<Integer> h = holds(l1, 14);
        // EXPECTATION CHANGED (R-25, A3): the kept hold never takes it past Level 1's top of 6.
        for (int k = 0; k < 14; k++)
            assertEquals(Math.min(Plan.traditionalSets(Plan.L1, 1 + k) + 1, Plan.traditionalTop(Plan.L1)),
                (int) h.get(k), "L1 week " + k);
        // A track made since never builds up, at any level - and its advance is the old one.
        for (int level = Plan.L1; level <= Plan.L4; level++) {
            Model.TrainerTrackState fresh = new Model.TrainerTrackState();
            fresh.level = level; fresh.weekIndex = 1; fresh.weekBaseIndex = 1;
            assertFalse(Mint.startWeekGrowth(T, fresh, 5_000L));
            assertEquals(0, Mint.buildUpSets(T, fresh));
            for (int k = 0; k < 20; k++) {
                int was = Mint.advanceFromAnchor(T, level, fresh.weekBaseIndex, k, fresh.weekIndex);
                assertEquals(was, Mint.advanceFromAnchor(T, fresh, k), "L" + level + " week " + k);
                fresh.weekIndex = was;
                assertEquals(Plan.traditionalSets(level, was), Mint.totalSets(T, fresh));
            }
        }
        // Interval girth never builds up, whatever is in the fields.
        Model.TrainerTrackState iv = older(Plan.L3, 0);
        Mint.startWeekGrowth(Plan.TRACK_GIRTH_INTERVAL, iv, 5_000L);
        assertEquals(0, iv.buildSets);
        iv.buildSets = 4; iv.buildWeek = 1;
        assertEquals(0, Mint.buildUpSets(Plan.TRACK_GIRTH_INTERVAL, iv));
        assertEquals(Mint.baseSets(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 0, 0),
            Mint.baseSets(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 0, 0, 4));
    }

    /** The reviewer's concern: yieldFloorSets is the base for traditional and totalSets clamps
     *  to it - under a build-up the floor is the build-up's count, never the level's. */
    @Test void theYieldFloorNeverPullsABuildUpToTheLevelsCount() throws Exception {
        Model.TrainerTrackState st = older(Plan.L4, 0);
        Mint.startWeekGrowth(T, st, 5_000L);
        int b = Mint.buildUpSets(T, st);
        assertEquals(4, b);
        assertEquals(4, Mint.yieldFloorSets(T, st.level, st.weekIndex, st.carriedSets, b));
        assertEquals(8, Mint.yieldFloorSets(T, st.level, st.weekIndex, st.carriedSets),
            "the level's own floor is unchanged");
        // A clamp from under: the total is the build-up's 4, not Level 4's 8.
        st.yieldSets = -3;
        assertEquals(4, Mint.totalSets(T, st));
        st.yieldSets = 0;
        // A cut proposed by high yield never takes it under 4, nor lifts it to 8.
        Plan.Decision cut = new Plan.Decision(Plan.ACTION_REDUCE_VOLUME, Plan.TAG_SOURCE, "r", "r",
            Double.NaN, -2);
        Mint.Rx rx = TrainerTab.trackRx(new Model(), T, st, 13, cut);
        assertEquals(4, rx.sets);
        assertFalse(Mint.commitYield(st, T, cut, 6_000L), "nothing under the build-up to cut");
        assertEquals(4, Mint.totalSets(T, st));
        // A pause of kept yield, with none kept: the same 4.
        Plan.Decision pause = new Plan.Decision(Plan.ACTION_PAUSE_VOLUME, Plan.TAG_SOURCE, "p",
            "p", Double.NaN, 0);
        assertEquals(4, TrainerTab.trackRx(new Model(), T, st, 13, pause).sets);
        // Low yield adds on top of the build-up, as on top of any count - one decision's worth.
        Plan.Decision add = new Plan.Decision(Plan.ACTION_ADD_VOLUME, Plan.TAG_SOURCE, "a", "a",
            Double.NaN, 2);
        assertEquals(6, TrainerTab.trackRx(new Model(), T, st, 13, add).sets);
        assertTrue(Mint.commitYield(st, T, add, 7_000L));
        assertEquals(6, Mint.totalSets(T, st));
        // ...and a later cut takes back only that, down to the build-up again.
        assertTrue(Mint.commitYield(st, T, cut, 8_000L));
        assertEquals(4, Mint.totalSets(T, st));
    }

    @Test void theBuildUpSurvivesASaveAndARestart() throws Exception {
        Model m = new Model();
        m.trainerGirthStyle = T;
        m.trainerGirth = older(Plan.L3, 1);
        Mint.startWeekGrowth(T, m.trainerGirth, 5_000L);
        holds(m.trainerGirth, 4);                      // three counted weeks in: 5 holds
        int[] before = { m.trainerGirth.buildSets, m.trainerGirth.buildWeek,
            m.trainerGirth.weekIndex, Mint.totalSets(T, m.trainerGirth) };
        assertEquals(5, before[3]);
        Model back = Model.fromJson(m.toJson());
        Model.TrainerTrackState b = back.trainerGirth;
        assertTrue(b.weekGrowth);
        assertFalse(b.buildSaid, "still to be said");
        assertEquals(Arrays.toString(before), Arrays.toString(new int[] { b.buildSets, b.buildWeek,
            b.weekIndex, Mint.totalSets(T, b) }));
        assertFalse(Mint.startWeekGrowth(T, b, 9_000L), "never started twice");
        // ...and it goes on from there after the restart, on the same count.
        assertEquals(list(5, 6, 6, 7, 7, 7, 7), holdsFrom(b, 3, 7));       // R-25: top 7
    }

    /** holds(), from counted week `from`. */
    private static List<Integer> holdsFrom(Model.TrainerTrackState st, int from, int n) {
        List<Integer> out = new ArrayList<Integer>();
        for (int k = from; k < from + n; k++) {
            st.weekIndex = Mint.advanceFromAnchor(T, st, k);
            out.add(Mint.totalSets(T, st));
        }
        return out;
    }

    @Test void aCrossingDuringTheBuildUpGoesOnFromTheCountRunNow() throws Exception {
        // Level 2, two counted weeks in: 4 holds, its next hold two weeks away.
        Model.TrainerTrackState st = older(Plan.L2, 0);
        Mint.startWeekGrowth(T, st, 5_000L);
        holds(st, 3);
        assertEquals(4, Mint.totalSets(T, st));
        Mint.crossGirthLevel(st, T, Plan.L3, 50_000L);
        assertEquals(Plan.L3, st.level);
        assertEquals(1, st.weekIndex);
        assertEquals(4, Mint.totalSets(T, st), "not Level 3's 6: more than the build-up allows");
        assertTrue(Mint.buildingUp(T, st));
        assertEquals(list(4, 4, 5, 5, 6, 6, 7, 7, 7, 7), holds(st, 10));   // R-25: top 7

        // One counted week after a hold: the week carries, so the next hold comes a week later.
        Model.TrainerTrackState odd = older(Plan.L2, 0);
        Mint.startWeekGrowth(T, odd, 5_000L);
        holds(odd, 4);                                    // 3,3,4,4: one week at 4
        assertEquals(4, Mint.totalSets(T, odd));
        Mint.crossGirthLevel(odd, T, Plan.L3, 50_000L);
        assertEquals(list(4, 5, 5, 6, 6, 7, 7, 7, 7), holds(odd, 9), "Level 3's top of 7 (R-25)");

        // Level 3 to 4 during the build-up: the same.
        Model.TrainerTrackState l3 = older(Plan.L3, 0);
        Mint.startWeekGrowth(T, l3, 5_000L);
        holds(l3, 3);                                     // 4,4,5
        Mint.crossGirthLevel(l3, T, Plan.L4, 50_000L);
        assertEquals(5, Mint.totalSets(T, l3));
        assertEquals(list(5, 5, 6, 6, 7, 7, 8, 8), holds(l3, 8));

        // A build-up already at its level's count lands on the new level's count, as today.
        Model.TrainerTrackState done = older(Plan.L2, 0);
        Mint.startWeekGrowth(T, done, 5_000L);
        holds(done, 8);
        assertFalse(Mint.buildingUp(T, done));
        Mint.crossGirthLevel(done, T, Plan.L3, 50_000L);
        assertEquals(0, done.buildSets);
        assertEquals(6, Mint.totalSets(T, done));
        Mint.crossGirthLevel(done, T, Plan.L4, 90_000L);
        assertEquals(8, Mint.totalSets(T, done));
    }

    @Test void theNoticeSaysTheBuildUp() {
        String said = Say.traditionalBuildUpDetail(4, 8);
        assertTrue(said.startsWith("Traditional girth now grows as the guidance does"), said);
        assertTrue(said.contains("builds up to your level's 8 instead of jumping to it"), said);
        assertTrue(said.contains("4 holds now, one more every 2 training weeks"), said);
        assertTrue(said.contains("Undo"), said);
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L2; st.weekIndex = 3; st.buildSets = 3; st.buildWeek = 1;
        assertEquals("Level 2, week 3: 4 of the level's 6 five-minute holds, building up. "
            + "The next is added at week 5.", TrainerTab.traditionalWeeksSaid(st));
        assertTrue(TrainerTab.traditionalBuildUpWords(st).contains("every 2 training weeks"));
        st.weekIndex = 7;
        assertEquals(TrainerTab.traditionalWeeksSaid(Plan.L2, 7), TrainerTab.traditionalWeeksSaid(st));
        assertEquals("", TrainerTab.traditionalBuildUpWords(st));
    }

    /**
     * THROUGH THE REAL ENGINE: an older Level 2 track at month 5, against a track made now at
     * the same place. Every level-up accepted. The build-up asks only its own holds (counted ==
     * target), adds one at a time and never sooner than 2 counted weeks apart - and the level
     * crossings fall on exactly the same weeks: no gate reads the hold count.
     */
    @Test void throughTheEngineLevelTimingDoesNotMove() {
        PressureClockTest.Timeline fresh = PressureClockTest.runTimeline(T, "t", 60, Plan.L4,
            m -> placeAtLevelTwo(m, false));
        PressureClockTest.Timeline old = PressureClockTest.runTimeline(T, "t", 60, Plan.L4,
            m -> placeAtLevelTwo(m, true));
        String what = "fresh " + PressureClockTest.describe(fresh) + " | older "
            + PressureClockTest.describe(old) + " | holds " + weeksSaid(old.sessions);
        assertEquals(crossings(fresh), crossings(old), what);
        assertEquals(fresh.gateWeek, old.gateWeek, what);
        assertEquals(fresh.deloads, old.deloads, what);
        assertEquals(PressureClockTest.stepsText(fresh), PressureClockTest.stepsText(old), what);
        assertFalse(old.crossed.isEmpty(), what);

        List<int[]> ss = old.sessions;
        assertEquals(3, ss.get(0)[3], "the first session is the half start: " + what);
        int prevSets = ss.get(0)[3], prevLevel = ss.get(0)[1], lastAddWeek = ss.get(0)[0];
        boolean crossedBuilding = false;
        for (int i = 0; i < ss.size(); i++) {
            int[] s = ss.get(i);
            String at = what + " - session " + i + " (week " + s[0] + ", L" + s[1] + ")";
            assertEquals(s[3] * Mint.HOLD_TRADITIONAL_SEC / 60, s[4], at + ": counted == target");
            assertTrue(s[3] <= Plan.traditionalSets(s[1], s[2]), at + ": over the level's count");
            if (s[1] != prevLevel && s[3] < Plan.traditionalSets(s[1], 1)) crossedBuilding = true;
            // R-27: what runs is held to the level's time cap (L3 5, L4 7 five-minute holds), so
            // a crossing from a level whose cap held the run lifts it to the new cap at once.
            boolean capLifted = s[1] != prevLevel && prevSets == Plan.r2MaxHolds(prevLevel,
                Mint.HOLD_TRADITIONAL_SEC, Mint.standardFatSec(prevLevel));
            if (s[3] != prevSets && !capLifted) {
                assertEquals(prevSets + 1, s[3], at + ": one hold at a time, crossings included");
                assertTrue(s[0] - lastAddWeek >= Plan.TRAD_BUILD_WEEKS_PER_HOLD,
                    at + ": a hold sooner than 2 weeks after the last");
                lastAddWeek = s[0];
            }
            prevSets = s[3]; prevLevel = s[1];
        }
        assertTrue(crossedBuilding, "the scenario crosses a level during the build-up: " + what);
        // EXPECTATION CHANGED (R-27): Level 4's 8 run as its time cap's 7 five-minute holds.
        assertEquals(7, ss.get(ss.size() - 1)[3], "it reaches the count: " + what);
        System.out.println("TRADITIONAL HALF START (older L2, month 5): " + what);
    }

    private static void placeAtLevelTwo(Model m, boolean older) {
        m.trainerMonthsPumping = 5;
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L2;
        st.setWorkingPressure(Plan.L234_FLOOR_KPA, PressureClockTest.at(0, 0));
        if (older) {
            st.weekGrowth = false;                  // a track saved before the growth
            Mint.startWeekGrowth(Plan.TRACK_GIRTH_TRADITIONAL, st, PressureClockTest.at(0, 0));
        }
    }

    private static String crossings(PressureClockTest.Timeline t) {
        StringBuilder b = new StringBuilder();
        for (int[] c : t.crossed) b.append(" w").append(c[0]).append("->L").append(c[2]);
        return b.toString();
    }

    private static String weeksSaid(List<int[]> ss) {
        StringBuilder b = new StringBuilder();
        int lastLevel = -1, lastSets = -1;
        for (int[] s : ss) {
            if (s[1] == lastLevel && s[3] == lastSets) continue;
            b.append(" w").append(s[0]).append(":L").append(s[1]).append('x').append(s[3]);
            lastLevel = s[1]; lastSets = s[3];
        }
        return b.toString().trim();
    }
}
