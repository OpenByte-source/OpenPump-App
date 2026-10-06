package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * YIELD SETS ARE KEPT (the owner's decision, 2026-09-26). The set change yield asked for was
 * worked out afresh at every look and vanished when the run of readings ended. Now:
 * three low readings in a row keep +1 set (L1/L2) or +2 (L3/L4); three high ones keep 2 fewer
 * at L3/L4 (never under the level's minimum, never under a table row) or, at L1/L2, take the
 * yield-added sets back off; after any change the streak restarts, so the next needs three new
 * readings. A level crossing keeps the total set count.
 */
class YieldSetsTest {

    static final int GI = Plan.TRACK_GIRTH_INTERVAL;

    static Plan.Decision decide(int level, int low, int high, int yieldSets) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = GI;
        in.level = level;
        in.monthIndex = level >= Plan.L3 ? 7 : 1;
        in.weekIndex = 12;
        in.pressureKpa = 20;
        in.hasYieldData = true;
        in.consecutiveLowYield = low;
        in.consecutiveHighYield = high;
        in.yieldSets = yieldSets;
        in.firstDeloadPending = false;
        return Plan.evaluate(in);
    }

    static Model.TrainerTrackState track(int level, int week, int carried) {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = level;
        st.weekIndex = week;
        st.carriedSets = carried;
        return st;
    }

    @Test void threeLowReadingsKeepASet() {
        // Row 10 (9 sets): row 12's 10 already fill Level 1's 20-minute time cap (R-27).
        Model.TrainerTrackState st = track(Plan.L1, 10, 0);
        Plan.Decision d = decide(Plan.L1, 3, 0, 0);
        assertEquals(Plan.ACTION_ADD_VOLUME, d.action);
        Mint.Rx before = Mint.prescribe(GI, Plan.L1, 10, 20, 1, 40, 0, st.yieldSets, d);
        assertEquals(10, before.sets, "row 10's 9 sets and the proposed one");
        assertTrue(Mint.commitYield(st, GI, d, 1000L));
        assertEquals(1, st.yieldSets);
        assertEquals(1000L, st.yieldSinceMs, "the streak restarts at the change");
        // The streak has ended - the next look holds - and the set is still there.
        Plan.Decision after = decide(Plan.L1, 0, 0, st.yieldSets);
        Mint.Rx kept = Mint.prescribe(GI, Plan.L1, 10, 20, 1, 40, 0, st.yieldSets, after);
        assertEquals(10, kept.sets, "the set stays when the run of low readings ends");
        assertEquals(before.sets, kept.sets, "so the routine is not rewritten back and forth");
        // Level 3 adds two.
        Model.TrainerTrackState l3 = track(Plan.L3, 0, 12);
        assertTrue(Mint.commitYield(l3, GI, decide(Plan.L3, 3, 0, 0), 5L));
        assertEquals(2, l3.yieldSets);
    }

    @Test void highReadingsAtL1AndL2TakeTheAddedSetsBack() {
        Model.TrainerTrackState st = track(Plan.L2, 3, 0);   // L2 row 3: 11 sets
        st.yieldSets = 2;
        Plan.Decision d = decide(Plan.L2, 0, 3, st.yieldSets);
        assertEquals(Plan.ACTION_PAUSE_VOLUME, d.action);
        assertEquals(-2, d.setsDelta);
        assertEquals(11, Mint.prescribe(GI, Plan.L2, 3, 27, 7, 40, 0, st.yieldSets, d).sets,
            "back to the table's own count");
        assertTrue(Mint.commitYield(st, GI, d, 9L));
        assertEquals(0, st.yieldSets);
        assertEquals(9L, st.yieldSinceMs);
        // Nothing added: EXPECTATION CHANGED (R-21, C2) - a pause with nothing to take back
        // changes nothing, so the pressure tier answers instead (here: a hold), and applying
        // it restarts the streak. The table's row is never cut.
        Plan.Decision none = decide(Plan.L2, 0, 3, 0);
        assertEquals(Plan.ACTION_HOLD, none.action);
        assertEquals(0, none.setsDelta);
        assertTrue(none.restartsYield);
        assertTrue(Mint.commitYield(st, GI, none, 10L), "no change, but the streak restarts");
        assertEquals(10L, st.yieldSinceMs);
        assertEquals(11, Mint.prescribe(GI, Plan.L2, 3, 27, 7, 40, 0, 0, none).sets);
    }

    @Test void highReadingsAtL3AndL4CutTwoButNeverUnderTheLevelsMinimum() {
        Model.TrainerTrackState st = track(Plan.L3, 0, 13);   // carried 13, L3 minimum 10
        Plan.Decision d = decide(Plan.L3, 0, 3, 0);
        assertEquals(Plan.ACTION_REDUCE_VOLUME, d.action);
        assertEquals(11, Mint.prescribe(GI, Plan.L3, 0, 30, 7, 40, 13, 0, d).sets);
        assertTrue(Mint.commitYield(st, GI, d, 1L));
        assertEquals(-2, st.yieldSets);
        assertEquals(11, Mint.totalSets(GI, st));
        assertTrue(Mint.commitYield(st, GI, d, 2L));
        assertEquals(-3, st.yieldSets, "13 - 4 would be 9: held at the level's 10");
        assertEquals(10, Mint.totalSets(GI, st));
        assertFalse(Mint.commitYield(st, GI, d, 3L), "at the minimum nothing more comes off");
        assertEquals(10, Mint.prescribe(GI, Plan.L3, 0, 30, 7, 40, 13, st.yieldSets, d).sets);
        Model.TrainerTrackState l4 = track(Plan.L4, 0, 0);    // L4 minimum 14
        assertFalse(Mint.commitYield(l4, GI, decide(Plan.L4, 0, 3, 0), 4L));
        assertEquals(14, Mint.totalSets(GI, l4));
    }

    @Test void theStreakCountsOnlyReadingsAfterTheChange() {
        Model m = PressureClockTest.model();
        for (int i = 0; i < 3; i++) {
            Model.Sess s = PressureClockTest.file(m, "g", PressureClockTest.at(2 * i, 9),
                20, 20, 27);
            s.afterGirCm = 0.05;             // 0.4 % on 12.6 cm: below every target
            s.afterGirAbsCm = 12.65;
            s.afterBaseThisSession = true;
            s.afterComparable = true;        // R-24: like for like
        }
        assertEquals(3, TrainerTab.yieldStreaks(m, GI, Plan.L1).consecutiveLow);
        long change = PressureClockTest.at(4, 12);
        assertEquals(0, TrainerTab.yieldStreaks(m, GI, Plan.L1, change).consecutiveLow,
            "a change restarts the count");
        Model.Sess next = PressureClockTest.file(m, "g", PressureClockTest.at(7, 9), 20, 20, 27);
        next.afterGirCm = 0.05; next.afterGirAbsCm = 12.65; next.afterBaseThisSession = true;
        next.afterComparable = true;
        assertEquals(1, TrainerTab.yieldStreaks(m, GI, Plan.L1, change).consecutiveLow,
            "and only new readings count toward the next one");
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L1; st.weekIndex = 12; st.yieldSinceMs = change; st.yieldSets = 1;
        st.weekBaseIndex = 12;               // placed at week 12: its yield tier is open
        Plan.Inputs in = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, GI, st, 2, PressureClockTest.at(8, 8), in);
        assertEquals(1, in.consecutiveLowYield);
        assertEquals(1, in.yieldSets);
    }

    @Test void aLevelCrossingKeepsTheTotal() {
        // L1 -> L2: the table-level destination keeps the difference as yield sets.
        Model.TrainerTrackState st = track(Plan.L1, 17, 0);
        st.yieldSets = 2;                                     // 10 + 2
        int total = Mint.totalSets(GI, st);
        assertEquals(12, total);
        st.level = Plan.L2; st.weekIndex = 1; st.carriedSets = 0;
        Mint.carryYieldAcrossLevel(st, GI, total, 50L);
        assertEquals(12, Mint.totalSets(GI, st), "L2 week 1 is 10 sets; 2 kept");
        assertEquals(2, st.yieldSets);
        // EXPECTATION CHANGED (R-26, fix d): the new level's streak starts fresh from its anchor
        // (weekBaseMs, which the crossing sets - TrainerTab#fillGirthInputs), not by moving the
        // yield clock, which also times the no-readings fallback.
        assertEquals(0L, st.yieldSinceMs, "the volume clock is not moved by a crossing");
        // L2 -> L3: folded into the carried count.
        st.weekIndex = 9; st.yieldSets = 1;                   // L2 row 9: 13 sets + 1
        total = Mint.totalSets(GI, st);
        assertEquals(14, total);
        st.level = Plan.L3; st.weekIndex = 0; st.carriedSets = total;
        Mint.carryYieldAcrossLevel(st, GI, total, 60L);
        assertEquals(0, st.yieldSets);
        assertEquals(14, Mint.totalSets(GI, st));
        // L3 -> L4 below L4's minimum of 14: the level's own floor, as before.
        Model.TrainerTrackState l3 = track(Plan.L3, 0, 10);
        l3.yieldSets = 1;
        total = Mint.totalSets(GI, l3);
        l3.level = Plan.L4; l3.carriedSets = total;
        Mint.carryYieldAcrossLevel(l3, GI, total, 70L);
        assertEquals(14, Mint.totalSets(GI, l3));
        // Traditional: the kept sets stay kept across the crossing.
        // EXPECTATION CHANGED (the owner's decision, 0.10 - traditional grows as the guidance
        // does): it used to start every level at 2 holds, so L1's 2 + 2 kept carried as 4. Its
        // Level 1 now grows 3 -> 6 by the week and Level 2 runs 6: a total under it comes up to
        // the level's own 6 (never under the new level's floor, the rule this test has always
        // held). EXPECTATION CHANGED AGAIN (R-25, A3): traditional has tops - 6 at Levels 1 and
        // 2 - so kept holds never take it past them.
        Model.TrainerTrackState tr = track(Plan.L1, Plan.traditionalTopWeek(Plan.L1), 0);
        tr.yieldSets = 2;
        total = Mint.totalSets(Plan.TRACK_GIRTH_TRADITIONAL, tr);
        assertEquals(6, total, "L1's top, 6 holds; the 2 kept do not pass it");
        tr.level = Plan.L2; tr.weekIndex = 1;
        Mint.carryYieldAcrossLevel(tr, Plan.TRACK_GIRTH_TRADITIONAL, total, 80L);
        assertEquals(6, Mint.totalSets(Plan.TRACK_GIRTH_TRADITIONAL, tr));
        assertEquals(0, tr.yieldSets);
        Model.TrainerTrackState early = track(Plan.L1, 1, 0);
        early.yieldSets = 2;
        total = Mint.totalSets(Plan.TRACK_GIRTH_TRADITIONAL, early);
        assertEquals(5, total, "L1 week 1, 3 holds, + 2 kept");
        early.level = Plan.L2; early.weekIndex = 1;
        Mint.carryYieldAcrossLevel(early, Plan.TRACK_GIRTH_TRADITIONAL, total, 80L);
        assertEquals(6, Mint.totalSets(Plan.TRACK_GIRTH_TRADITIONAL, early), "Level 2's own 6");
    }

    @Test void lengthIsNotTouched() {
        Model.TrainerTrackState st = track(Plan.L1, 1, 0);
        Plan.Decision add = new Plan.Decision(Plan.ACTION_ADD_VOLUME, Plan.TAG_SOURCE, "r", "r",
            Double.NaN, 1);
        assertFalse(Mint.commitYield(st, Plan.TRACK_LENGTH, add, 1L));
        assertEquals(0, st.yieldSets);
        assertEquals(Mint.prescribe(Plan.TRACK_LENGTH, Plan.L1, 1, 20, 1, 40, 0, null).sets,
            Mint.prescribe(Plan.TRACK_LENGTH, Plan.L1, 1, 20, 1, 40, 0, 5, null).sets,
            "kept yield sets are girth's alone");
    }

    @Test void theFieldsSurviveASave() throws Exception {
        Model m = new Model();
        m.trainerGirth.yieldSets = -3;
        m.trainerGirth.yieldSinceMs = 1788440800000L;
        Model back = Model.fromJson(m.toJson());
        assertEquals(-3, back.trainerGirth.yieldSets);
        assertEquals(1788440800000L, back.trainerGirth.yieldSinceMs);
    }
}
