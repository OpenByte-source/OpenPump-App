package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * TRADITIONAL GIRTH GROWS AS THE GUIDANCE DOES (the owner's decision, 0.10, final).
 *
 * The guidance's long-hold path grows by the week at the level: Level 1 starts at three
 * 5-minute holds and adds one every 28 days of training to six; Level 2 runs six; Level 3 runs
 * the fatigue block and then six, adding one every 28 days to eight; Level 4 runs eight. The app
 * used to run 2 holds at every level. The weeks are the plan's own count (the interval table's:
 * qualifying training weeks on the track since the level's anchor, less repeated weeks), so four
 * counted weeks are the guidance's 28 days. Hold length, rests, pressure steps, gates, yield and
 * deloads are unchanged; the interval track's hybrid is not touched.
 */
class TraditionalGrowthTest {

    private static final int T = Plan.TRACK_GIRTH_TRADITIONAL;

    @Test void theTableIsTheGuidances() {
        int[] l1 = { 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 6 };
        for (int w = 1; w <= l1.length; w++)
            assertEquals(l1[w - 1], Mint.baseSets(T, Plan.L1, w), "L1 week " + w);
        assertEquals(3, Mint.baseSets(T, Plan.L1, 0), "week 0 reads as the first");
        // EXPECTATION CHANGED (R-25, A3: Level 3's top is 7 and stops the calendar too, K17).
        int[] l3 = { 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7 };
        for (int w = 1; w <= l3.length; w++) {
            assertEquals(6, Mint.baseSets(T, Plan.L2, w), "L2 week " + w + " is flat");
            assertEquals(l3[w - 1], Mint.baseSets(T, Plan.L3, w), "L3 week " + w);
            assertEquals(8, Mint.baseSets(T, Plan.L4, w), "L4 week " + w + " is flat");
        }
        // A carried count never reaches traditional; the level's own table does.
        assertEquals(6, Mint.baseSets(T, Plan.L3, 1, 14));
        // The hold is still 5 minutes, and the minutes follow the holds.
        Mint.Rx rx = Mint.prescribe(T, Plan.L1, 5, 20, 1, 40, 0, null);
        assertEquals(4, rx.sets);
        assertEquals(Mint.HOLD_TRADITIONAL_SEC, rx.holdSec);
        assertEquals(20.0, rx.netTargetMin, 1e-9);
        // The fatigue block from Level 3, as before.
        assertFalse(Mint.prescribe(T, Plan.L2, 1, 27, 5, 40, 0, null).fatigue);
        assertTrue(Mint.prescribe(T, Plan.L3, 1, 27, 7, 40, 0, null).fatigue);
        // The interval track's hybrid is not touched.
        assertEquals(Mint.baseSets(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 1, 14), 14);
    }

    @Test void theWeekAdvancesAtLevelsOneAndThreeOnlyAndStopsAtTheLastHold() {
        assertEquals(1, Mint.advancedWeekIndex(T, Plan.L1, 1, 0));
        assertEquals(9, Mint.advancedWeekIndex(T, Plan.L1, 1, 8));
        assertEquals(13, Mint.advancedWeekIndex(T, Plan.L1, 1, 40), "stops at the sixth hold");
        assertEquals(5, Mint.advancedWeekIndex(T, Plan.L3, 1, 40),
            "stops at the seventh hold (R-25)");
        assertEquals(1, Mint.advancedWeekIndex(T, Plan.L2, 1, 40));
        assertEquals(0, Mint.advancedWeekIndex(T, Plan.L4, 0, 40));
        // Monotonic from its anchor, as the interval table's position is.
        assertEquals(10, Mint.advanceFromAnchor(T, Plan.L1, 1, 3, 10));
    }

    /** The compliant person of the worked timeline (5 hg, Mon/Wed/Fri) on traditional girth,
     *  a year and more through the real engine, every level-up accepted. */
    @Test void aYearOfTraditionalGrowsAsTheGuidance() {
        PressureClockTest.Timeline t = PressureClockTest.runTimeline(T, "t", 64, Plan.L4);
        String what = PressureClockTest.describe(t);
        List<int[]> ss = t.sessions;
        assertTrue(ss.size() > 100, what);
        int[] first = ss.get(0);
        assertEquals(Plan.L1, first[1], what);
        assertEquals(3, first[3], "the first session is 3 holds");
        boolean[] reached = new boolean[5];
        int prevLevel = first[1], prevSets = first[3], lastAddWeek = first[0], levelStart = first[0];
        for (int i = 0; i < ss.size(); i++) {
            int[] s = ss.get(i);
            int week = s[0], level = s[1], wk = s[2], sets = s[3], net = s[4];
            String at = what + " - session " + i + " (week " + week + ", L" + level + ", its week "
                + wk + ", " + sets + " holds)";
            // COUNTED == TARGET: the minutes the session is asked for are its holds'.
            assertEquals(sets * Mint.HOLD_TRADITIONAL_SEC / 60, net, at);
            // Exactly the guidance's count at its week (no yield readings in this timeline),
            // under the session's time cap (R-27: L1 4, L2 6, L3 5, L4 7 five-minute holds).
            int cap = Plan.r2MaxHolds(level, Mint.HOLD_TRADITIONAL_SEC,
                Mint.standardFatSec(level));
            assertEquals(Math.min(Plan.traditionalSets(level, wk), cap), sets, at);
            if (level != prevLevel) {
                assertTrue(level == prevLevel + 1, at);
                levelStart = week;
                lastAddWeek = week;
            } else if (sets != prevSets) {
                assertEquals(prevSets + 1, sets, at + ": one hold at a time");
                assertTrue(week - lastAddWeek >= Plan.TRAD_WEEKS_PER_HOLD,
                    at + ": a hold added sooner than four weeks after the last");
                lastAddWeek = week;
            }
            // EXPECTATION CHANGED (R-25/R-27): what runs stops at the time cap - Level 1 at 4
            // holds, Level 3 at 5, Level 4 at 7; the plan's count past it is pressure.
            assertTrue(sets <= cap, at);
            assertTrue(level != Plan.L2 || sets == 6, at);
            assertTrue(level != Plan.L4 || sets == 7, at);
            if (level == Plan.L1 && sets == 4) reached[1] = true;
            if (level == Plan.L2) reached[2] = true;
            if (level == Plan.L3 && sets == 5) reached[3] = true;
            if (level == Plan.L3) assertTrue(sets == 5, at);
            prevLevel = level; prevSets = sets;
        }
        System.out.println("TRADITIONAL YEAR (5 hg, M/W/F): " + what + "; holds by week "
            + weeksSaid(ss));
        assertTrue(reached[1], "Level 1 grew 3 -> 4, its time cap: " + weeksSaid(ss));
        assertTrue(reached[2], "Level 2 reached, at 6: " + weeksSaid(ss));
        assertTrue(reached[3], "Level 3 runs its time cap's 5: " + weeksSaid(ss));
        assertTrue(levelStart > 0);
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

    @Test void aLevelCrossingStartsTraditionalsWeeksAgain() {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L2; st.weekIndex = 1; st.pressureKpa = 30;
        Mint.crossGirthLevel(st, T, Plan.L3, 500L);
        assertEquals(1, st.weekIndex);
        assertEquals(1, st.weekBaseIndex);
        assertEquals(500L, st.weekBaseMs);
        assertEquals(6, Mint.totalSets(T, st), "Level 2's 6 carry into Level 3's first week");
        Mint.crossGirthLevel(st, T, Plan.L4, 900L);
        assertEquals(8, Mint.totalSets(T, st), "Level 4 runs 8");
        // Interval is as it was.
        Model.TrainerTrackState iv = new Model.TrainerTrackState();
        iv.level = Plan.L2; iv.weekIndex = 9;
        Mint.crossGirthLevel(iv, Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 500L);
        assertEquals(0, iv.weekIndex);
    }

    /* EXPECTATION CHANGED (t10 REAL-10, the parity run; the editor's model and the spec's
     * "Traditional L1 start, 3 holds"): a setup starts traditional at its level's FIRST week -
     * the holds grow by the weeks trained on the plan, and a setup has trained none. It used
     * to read the week from the months answer (8 at two months, 5 at eight). */
    @Test void setupPlacesTraditionalAtItsLevelsFirstWeek() {
        assertEquals(1, TrainerTab.deriveGirth(T, 0, 17).weekIndex);
        assertEquals(1, TrainerTab.deriveGirth(T, 2, 20).weekIndex);
        assertEquals(3, Mint.baseSets(T, Plan.L1, TrainerTab.deriveGirth(T, 2, 20).weekIndex),
            "two months of pumping start Level 1 at its 3 holds");
        TrainerTab.Derived l3 = TrainerTab.deriveGirth(T, 8, 9.0 * Plan.HG);
        assertEquals(Plan.L3, l3.level);
        assertEquals(1, l3.weekIndex, "Level 3 from its first week");
        assertEquals(6, Mint.baseSets(T, l3.level, l3.weekIndex));
        TrainerTab.Derived l4 = TrainerTab.deriveGirth(T, 14, 9.0 * Plan.HG);
        assertEquals(Plan.L4, l4.level);
        assertEquals(8, Mint.baseSets(T, l4.level, l4.weekIndex));
    }

    @Test void aTrackSavedBeforeStartsItsLevelsGrowthOnceFromItsFirstWeek() throws Exception {
        // An older file has no weekGrowth: its traditional week was never counted.
        Model.TrainerTrackState fresh = new Model.TrainerTrackState();
        assertTrue(fresh.weekGrowth, "every track made now counts its weeks");
        JSONObject o = fresh.toJson();
        o.remove("weekGrowth");
        o.put("level", Plan.L1); o.put("week", 0);
        o.put("weekBaseIndex", 0); o.put("weekBaseMs", "1000");
        Model.TrainerTrackState old = Model.TrainerTrackState.fromJson(o);
        assertFalse(old.weekGrowth);
        assertTrue(Mint.startWeekGrowth(T, old, 5_000L));
        assertEquals(1, old.weekIndex, "its level's first week - never a jump to its old weeks");
        assertEquals(1, old.weekBaseIndex);
        assertEquals(5_000L, old.weekBaseMs, "counted from now");
        assertEquals(3, Mint.totalSets(T, old));
        assertFalse(Mint.startWeekGrowth(T, old, 9_000L), "once");
        assertEquals(5_000L, old.weekBaseMs);
        assertTrue(Model.TrainerTrackState.fromJson(old.toJson()).weekGrowth, "saved as counted");
        // An interval track is only marked: its table always advanced.
        Model.TrainerTrackState iv = Model.TrainerTrackState.fromJson(o);
        iv.weekIndex = 7; iv.weekBaseIndex = 3; iv.weekBaseMs = 1000L;
        assertTrue(Mint.startWeekGrowth(Plan.TRACK_GIRTH_INTERVAL, iv, 5_000L));
        assertEquals(7, iv.weekIndex);
        assertEquals(1000L, iv.weekBaseMs);
        // A track made now is never moved.
        assertFalse(Mint.startWeekGrowth(T, fresh, 5_000L));
    }

    /** A girth routine's shape tag as the app mints it for a plain model (Model#mintShapeTag). */
    private static final String SHAPE = new Model().mintShapeTag(T, Plan.L1, 0L);

    @Test void theOneRewriteSaysSoAndOnlyOnce() {
        String old = Mint.signature(new Mint.Rx(T, Plan.L1, 2, 300, 120, 20, false, 10.0,
            Mint.POWER_PCT, 0), SHAPE);
        String now = Mint.signature(Mint.prescribe(T, Plan.L1, 1, 20, 1, 40, 0, null), SHAPE);
        String said = Say.traditionalGrowthDetail(old, now, 0);
        assertTrue(said.startsWith("Traditional girth now grows as the guidance does"), said);
        assertTrue(said.contains("rewritten once"), said);
        assertTrue(said.contains("Undo"), said);
        assertFalse(NoBookNamesTest.namesASource(said) || said.contains("\""), said);
        // An old routine with a kept yield set: 3 holds, 1 of them yield - still the old rule's.
        String oldY = Mint.signature(new Mint.Rx(T, Plan.L1, 3, 300, 120, 20, false, 15.0,
            Mint.POWER_PCT, 0), SHAPE);
        assertTrue(Say.traditionalGrowthDetail(oldY, now, 1).length() > 0);
        // A routine the new rule built is never told again - its growth or a yield set on top.
        String w5 = Mint.signature(Mint.prescribe(T, Plan.L1, 5, 20, 1, 40, 0, null), SHAPE);
        assertEquals("", Say.traditionalGrowthDetail(now, w5, 0));
        assertEquals("", Say.traditionalGrowthDetail(oldY, w5, 0));
        String l3 = Mint.signature(Mint.prescribe(T, Plan.L3, 1, 30, 7, 40, 0, 2, null), SHAPE);
        assertEquals("", Say.traditionalGrowthDetail(l3, l3, 2));
        // Interval is never told.
        String iv = Mint.signature(Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 1, 20, 1,
            40, 0, null), SHAPE);
        assertEquals("", Say.traditionalGrowthDetail(iv, iv, 0));
        assertEquals("Traditional girth now grows", Say.TRADITIONAL_GROWS);
    }

    @Test void theTrainerSaysWhereTheGrowthIs() {
        // (t10 device walk M4: held to the session's 20-minute cap at Level 1, as the routine is
        // - Plan#r2MaxHolds; TraditionalNextWeeksTest.)
        assertEquals("Level 1, week 1: 3 five-minute holds. The next is added at week 5.",
            TrainerTab.traditionalWeeksSaid(Plan.L1, 1));
        assertEquals("Level 1, week 4: 3 five-minute holds. The next is added at week 5.",
            TrainerTab.traditionalWeeksSaid(Plan.L1, 4));
        assertEquals("Level 1, week 13: 4 five-minute holds, the most its 20-minute cap lets "
            + "run: the plan raises the pressure in place of more holds.",
            TrainerTab.traditionalWeeksSaid(Plan.L1, 13));
        assertTrue(TrainerTab.traditionalWeeksSaid(Plan.L2, 3).endsWith("the same every week."));
        assertTrue(TrainerTab.traditionalWeeksSaid(Plan.L3, 1).contains("after the fatigue block"));
        assertFalse(TrainerTab.TRADITIONAL_GROWTH_WORDS.contains("\""));
    }
}
