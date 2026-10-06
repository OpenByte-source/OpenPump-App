package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R-27 (R2, the owner's decision, 2026-10-01; K1 caps, K2 clock A, fix a): THE TIME UNDER
 * PRESSURE PER GIRTH SESSION IS CAPPED at 20 / 30 / 36 / 44 minutes at Levels 1-4 - the holds
 * at the working pressure, the fatigue block included - for interval, traditional and the
 * hybrid. Holds the plan writes past the cap are not run; the pressure rises at the same dose
 * instead (P' = P x (cap + dT) / cap), on the pressure step's 3-training-week clock and at
 * most one step (+1 inHg) a morning, never past the usual top unless "Most you will go to" is
 * set above it. The same holds never convert twice; a level change starts again.
 */
class TupCapTest {

    static final int GI = Plan.TRACK_GIRTH_INTERVAL, T = Plan.TRACK_GIRTH_TRADITIONAL;

    /** Girth inputs with the time cap given, as TrainerTab#fillGirthInputs gives it. */
    static Plan.Inputs capped(int track, int level, int workSets, double kpa, double mostKpa) {
        Plan.Inputs in = YieldWindowTest.girth(level, workSets);
        in.track = track;
        in.hasYieldData = false;
        in.monthIndex = level >= Plan.L4 ? 13 : 7;
        in.pressureKpa = kpa;
        in.climbTopKpa = mostKpa;
        in.r2HoldSec = track == T ? Mint.HOLD_TRADITIONAL_SEC : Mint.HOLD_INTERVAL_SEC;
        in.r2FatSec = Mint.standardFatSec(level);
        return in;
    }

    @Test void theSameDose() {
        Plan.R2Conversion a = Plan.r2Convert(30, 36, 4, 37);
        assertEquals(33, a.to, "raw " + a.raw);
        assertEquals(33.33, a.raw, 0.01);
        assertFalse(a.atLimit);
        Plan.R2Conversion b = Plan.r2Convert(30, 20, 2, 27);
        assertEquals(30, b.to);
        assertTrue(b.atLimit);
        Plan.R2Conversion c = Plan.r2Convert(34, 44, 10, 34);
        assertEquals(34, c.to);
        assertTrue(c.atLimit);
        // Down when up would pass the limit.
        assertEquals(37, Plan.r2Convert(30, 36, 10, 37).to, "38.33 -> 37");
    }

    @Test void theMostHoldsUnderTheCap() {
        int[] interval = { 10, 15, 14, 18 }, five = { 4, 6, 5, 7 };
        for (int l = Plan.L1; l <= Plan.L4; l++) {
            int fat = Mint.standardFatSec(l);
            assertEquals(interval[l - 1], Plan.r2MaxHolds(l, Mint.HOLD_INTERVAL_SEC, fat), "L" + l);
            assertEquals(five[l - 1], Plan.r2MaxHolds(l, Mint.HOLD_TRADITIONAL_SEC, fat), "L" + l);
        }
        assertEquals(12, Plan.r2MaxHolds(Plan.L3, Mint.HOLD_INTERVAL_SEC, 675),
            "the extended block counts 11.25 min");
        Model m = new Model();
        m.programGirth.fatigue = Model.Program.FAT_EXTENDED;
        assertEquals(675, Mint.r2FatSec(m, GI, Plan.L3));
        assertEquals(0, Mint.r2FatSec(m, GI, Plan.L2));
        // The prescription never writes past it.
        assertEquals(10, Mint.prescribe(GI, Plan.L1, 16, 27, 2, 43, 0, 2, null).sets,
            "Level 1's 10 sets + 2 kept: 10 run");
        assertEquals(5, Mint.prescribe(T, Plan.L3, 9, 30, 7, 43, 0, 0, null).sets);
    }

    @Test void traditionalLevelThreeAtSevenHolds() {
        Plan.Inputs in = capped(T, Plan.L3, 7, 30, 37);
        // Not a step morning: the conversion is said, and rides on the steps.
        Plan.Decision said = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, said.action);
        assertEquals(Plan.R2_RULE, said.rule);
        assertEquals(Plan.TAG_ADAPTED, said.tag);
        assertEquals("Your session is at its 36-minute cap: instead of 2 more holds, the "
            + "pressure goes up 2.1 inHg (it will rise with the next steps).", said.reason);
        assertEquals(7, said.r2PendKpa, "wants 37");
        // A step morning: +1 inHg, the rest owed.
        in.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        Plan.Decision up = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, up.action);
        assertEquals(Plan.TAG_ADAPTED, up.tag);
        assertEquals(33, (int) Math.round(up.pressureKpa), "+3 kPa a morning");
        assertEquals(2, up.r2ExHolds);
        assertEquals(4, up.r2PendKpa);
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L3;
        assertTrue(Mint.commitYield(st, T, up, 100L));
        assertEquals(2, st.r2ExHolds);
        assertEquals(4, st.r2PendKpa);
        // The same holds never convert twice: off a step morning the plan answers as usual.
        Plan.Inputs after = capped(T, Plan.L3, 7, up.pressureKpa, 37);
        after.r2ExHolds = st.r2ExHolds;
        after.r2PendKpa = st.r2PendKpa;
        Plan.Decision quiet = Plan.evaluate(after);
        assertFalse(quiet.r2(), quiet.rule);
        // ...and the next step morning carries the rest, to the limit.
        after.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        Plan.Decision next = Plan.evaluate(after);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, next.action);
        assertEquals(37, (int) Math.round(next.pressureKpa));
        assertEquals(0, next.r2PendKpa, "at the limit nothing is owed");
        // Not on a return day.
        in.gentleReturnOpen = true;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(in).action);
        // A level change starts again.
        Mint.crossGirthLevel(st, T, Plan.L4, 200L);
        assertEquals(0, st.r2ExHolds);
        assertEquals(0, st.r2PendKpa);
    }

    @Test void theOwnerAsTraditional() {
        // Week 1 at Level 3: 6 holds, 1 past the cap: +5 min -> 34 kPa, which rides on the steps.
        Plan.Decision w1 = Plan.evaluate(capped(T, Plan.L3, 6, 30, 37));
        assertEquals("Your session is at its 36-minute cap: instead of 1 more hold, the "
            + "pressure goes up 1.2 inHg (it will rise with the next steps).", w1.reason);
        // At Level 4 at its limit (37): holding.
        Plan.Decision l4 = Plan.evaluate(capped(T, Plan.L4, 8, 37, 37));
        assertEquals(Plan.ACTION_HOLD, l4.action);
        assertEquals(Plan.R2_AT_LIMIT_WORDS, l4.reason);
        // A low-yield hold that cannot run, at the limit: the offer (R-23), not a silent hold.
        Plan.Inputs low = capped(T, Plan.L4, 8, 37, 37);
        low.hasYieldData = true;
        low.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        assertEquals(Plan.ACTION_OFFER_BREAK, Plan.evaluate(low).action);
    }

    @Test void aLowYieldHoldPastTheCapIsPressure() {
        Plan.Inputs low = capped(T, Plan.L3, 6, 30, 37);
        low.hasYieldData = true;
        low.consecutiveLowYield = Plan.YIELD_DEBOUNCE;
        low.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        Plan.Decision d = Plan.evaluate(low);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action);
        assertTrue(d.rule.startsWith(Plan.R2_RULE), d.rule);
        assertEquals(1, d.r2AddHolds, "the plan's count keeps the hold");
        assertEquals(0, d.setsDelta, "none of it runs: the routine's count does not move");
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L3; st.weekIndex = 1; st.weekGrowth = true;
        assertTrue(Mint.commitYield(st, T, d, 100L));
        assertEquals(7, Mint.totalSets(T, st));
        assertTrue(st.addPending, "an add the yield asked for");
        assertEquals(100L, st.yieldSinceMs);
        assertEquals(2, st.r2ExHolds);
    }

    @Test void theOwnerOnIntervalNeverConverts() {
        assertNull(Plan.r2Step(capped(GI, Plan.L3, 14, 30, 37), 0, null, false));
        assertNull(Plan.r2Step(capped(GI, Plan.L4, 18, 37, 37), 0, null, false));
        Plan.Inputs up = capped(GI, Plan.L4, 18, 33, 37);
        up.netTupMin = Plan.netMilestoneMin(Plan.L4);
        up.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        Plan.Decision d = Plan.evaluate(up);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action);
        assertFalse(d.r2());
    }

    @Test void theHybridAtLevelThree() {
        Plan.Inputs in = capped(GI, Plan.L3, 14, 30, 37);
        in.hybridHolds = 6;
        in.r2HoldSec = Mint.HOLD_TRADITIONAL_SEC;
        in.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action, "O1: 5 run, 30 -> 33");
        assertEquals(33, (int) Math.round(d.pressureKpa));
        assertEquals(1, d.r2ExHolds);
    }
}
