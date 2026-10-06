package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import java.util.Collections;

import org.junit.jupiter.api.Test;

/**
 * Parity run 3's addendum, the app items (OPEN-1, OPEN-2a, OPEN-4, OPEN-5, OPEN-7) - one test
 * each, on the setups the parity agent traced.
 */
class ParityRun3AppTest {

    private static long d(int day, int hour) {
        return LengthYear.t0() + day * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    /* OPEN-1: traditional L2 at 34 kPa, length first on a both-tracks day, within the half
     * hour (P4). The saved routine's P2 carry ramp never reaches the work in 6 holds
     * ([28..33]); the day's build without the warm-up runs the work at 34 and leads in with
     * the R4 ramp-in (fix e) - but it peaked above the saved routine, so it was refused, the
     * carry-lowered holds stayed and the trim kept one hold at 33: never at 34. */
    @Test void open1TheDroppedWarmUpTakesItsCarryWithIt() {
        Model m = owner();                       // no length cylinder: the 2A trim
        m.trainerLengthOn = true;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
        Mint.Rx g = trad(Plan.L2, 6, 34);
        asMint(m, m.trainerGirth, g, build(m, g, day(m)));
        Model.Routine saved = SecondSessionTest.girthSaved(m);
        assertEquals(33, (int) Collections.max(workPulls(m, saved)), "the carry stops at 33");
        Mint.Rx l = length(34);
        asMint(m, m.trainerLength, l, build(m, l, day(m)));
        filed(m, SecondSessionTest.lengthSaved(m), NOW - 10 * MIN, 16);
        RunShape.Choice c = TrainerTab.dayChoice(m, saved, NOW, false, false);
        assertTrue(c.skipWarm && c.girthAfterLength);
        Model.Routine r = RunShape.build(m, saved, c).routine;
        assertEquals(34, (int) Collections.max(workPulls(m, r)), "the session reaches its 34");
        assertTrue(stage(r, "rampin") >= 0, "led in by the chosen ramp-in");
        assertEquals(-1, stage(r, "warm"), "no warm-up");
    }

    /* OPEN-2a: option D's "a set was added this block" and "the offer was made" belong to the
     * block they were set in. A week off booked for later cleared them at the answer, and a D2
     * set in the days before the week off then carried into the new block - D3 after it. */
    @Test void open2aTheBlocksFlagsRestartAtEveryWeekOff() {
        Model m = new LengthYear().setup().m;
        Deload.remember(m, d(14, 0), d(14, 0) + Plan.LAYOFF_MS);   // booked on day 10
        m.trainerFirstDeloadTaken = true;
        Deload.arm(m, d(14, 0) + Plan.LAYOFF_MS);
        LengthTrack.acceptSets(m, Plan.LENGTH_NEVER_RULE, m.trainerLength.strainSets + 1,
                               d(11, 8));
        LengthTrack.offerAnswered(m, d(12, 8));
        Plan.Inputs before = LengthYear.inputsFor(m, d(13, 8));
        assertTrue(before.dAddedInBlock && before.dOfferedInBlock, "this block's");
        Plan.Inputs after = LengthYear.inputsFor(m, d(22, 8));
        assertFalse(after.dAddedInBlock, "the new block has added nothing");
        assertFalse(after.dOfferedInBlock, "nor offered");
    }

    /* OPEN-4: traditional L1 -> L2, 4-day weeks. 8 inHg is reached on the week's 4th day; the
     * week's other three sessions ran at 26 kPa, so it is not a week held at 8 inHg. (Fixed with
     * A3-1: a week counts only the sessions at or after the run's start.) */
    @Test void open4AWeekIsHeldOnlyWhereItsSessionsRanAtTheGate() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = d(-30, 0);
        m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
        Model.Routine r = new Model.Routine();
        r.id = "PT"; r.name = "PT"; r.trainerTrack = Plan.TRACK_GIRTH_TRADITIONAL;
        m.routines.add(r);
        int[] days = { 0, 2, 4, 6, 7, 9, 11, 13 };
        double[] peak = { 26, 26, 26, 28, 28, 28, 28, 28 };
        for (int i = 0; i < days.length; i++) {
            Model.Sess s = new Model.Sess();
            s.id = "t" + i; s.routineId = "PT"; s.ts = d(days[i], 9); s.durSec = 1200;
            s.completed = true; s.netTargetMin = Double.valueOf(10.0);
            s.netTupSec = Double.valueOf(600.0); s.peakKpa = Double.valueOf(peak[i]);
            m.sessLog.file(s);
        }
        assertEquals(1, TrainerTab.gateHeldTrainingWeeks(m, Plan.TRACK_GIRTH_TRADITIONAL,
            d(14, 8)), "only the second week ran at 8 inHg");
    }

    /* OPEN-5: a confirmed cut is half a pound off the pull the person pulls - the plan's load
     * with the person's offset - and the 5 lb floor is that pull's. With an offset of +1.48 lb a
     * plan load of 5.11 lb pulls 6.59; the cut took the plan's load to its floor, 5.0 - a pull
     * of 6.48, 0.11 lb lighter - where it is 6.09. */
    @Test void open5TheCutIsHalfAPoundOfThePull() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L4;
        in.monthIndex = 20;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.handedOver = true;
        in.strainSets = 6;
        in.pressureKpa = Plan.LENGTH_SOFT_CAP_HI_KPA;
        in.strainPct = 7.0;
        in.lastStrainHigh = true;
        in.strainHighConfirmed = true;
        in.loadLb = 5.11;
        in.lengthOffsetLb = 1.48;
        Plan.Decision cut = Plan.evaluate(in);
        assertEquals(Plan.LENGTH_CUT_RULE, cut.rule);
        assertEquals(6.09, cut.loadLb + in.lengthOffsetLb, 1e-9, "6.59 -> 6.09 pulled");
        in.loadLb = 3.6;                             // pulls 5.08: the floor is the pull's
        assertEquals(5.0, Plan.evaluate(in).loadLb + in.lengthOffsetLb, 1e-9);
        in.loadLb = 3.5;                             // pulls 4.98: at the floor, not cut
        assertEquals(Plan.LENGTH_FLOOR_RULE, Plan.evaluate(in).rule);
    }

    /* OPEN-7: an interval L3 -> L4 crossing left weekBaseIndex 0, which the Trainer's next
     * draw read as a track never anchored, re-anchoring the week (weekBaseMs) to that morning -
     * "levelled up today" a second morning, and the fallback, pressure and volume steps a
     * training morning late. The crossing leaves a real anchor. */
    @Test void open7TheCrossingIsTheOnlyLevelUpMorning() {
        Model m = new Model();
        Model.TrainerTrackState g = m.trainerGirth;
        g.level = Plan.L3;
        long crossing = d(0, 8);
        Mint.crossGirthLevel(g, Plan.TRACK_GIRTH_INTERVAL, Plan.L4, crossing);
        assertEquals(crossing, g.weekBaseMs);
        assertTrue(g.weekBaseIndex > 0, "anchored: the next draw does not anchor it again");
        assertFalse(Mint.anchorWeek(g, d(1, 8)), "nothing to anchor the next morning");
        assertEquals(crossing, g.weekBaseMs, "the crossing morning stays the level's anchor");
    }
}
