package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * t10 R-06 (R1) - ON A DAY OF BOTH TRACKS THE LENGTH TRACTION SESSION ENDS AFTER ITS STRAIN
 * HOLDS: no tube swap, no 5 x 2 min expansion, and girth gives up no holds for it. A day of
 * length only keeps the coda. Expansion-only length (no length cylinder) is the day's
 * expansion itself, so girth keeps giving up its 5 holds (K4, the owner's 2A). The numbers are
 * SPEC.md's (the editor's buildDay, owner both tracks, length first).
 */
class NoCodaBothTracksTest {

    /** The owner's day, every weekday planned as both tracks. */
    static Model bothDays() {
        Model m = SecondSessionTest.ownerDay();
        Arrays.fill(m.sched.days, true);
        Arrays.fill(m.sched.plan, Schedule.PLAN_BOTH);
        return m;
    }

    @Test void ownerL3LengthFirst() {
        Model m = bothDays();
        Model.Routine length = SecondSessionTest.lengthSaved(m);
        RunShape.Choice lc = TrainerTab.dayChoice(m, length, NOW, false, false);
        assertTrue(lc.bothTracks, "the day runs both");
        assertFalse(lc.skipWarm, "length first warms up");
        RunShape.Built lb = RunShape.build(m, length, lc);
        Model.Routine lr = lb.routine;
        assertEquals(54.67, minutes(m, lr), 1e-9,
                     "hand 300 s, P2 warm-up, fatigue, 6 strain, no swap, no coda");
        assertEquals(list(12, 15, 18, 21, 24, 27), pulls(m, lr, stage(lr, "warm")));
        assertEquals(list(28, 29, 30, 31, 32, 33, 34, 34, 34, 34),
                     pulls(m, lr, stage(lr, "fatigue")));
        assertEquals(repeat(34, 6), pulls(m, lr, stage(lr, "strain")));
        assertEquals(-1, stage(lr, "swap"));
        assertTrue(lb.codaDropped);
        assertTrue(RunShape.lines(lb, 0, -1, "Girth").get(0)
            .startsWith("Ends after the strain holds"));
        long lengthEnd = NOW;
        filed(m, length, lengthEnd, 55);

        Model.Routine girth = SecondSessionTest.girthSaved(m);
        RunShape.Choice gc = TrainerTab.dayChoice(m, girth, NOW + 5 * MIN, false, false);
        assertEquals(0, gc.girthSetsOff, "girth gives up no holds with a length cylinder");
        Model.Routine gr = RunShape.build(m, girth, gc).routine;
        assertEquals(39.25, minutes(m, gr), 1e-9);
        assertEquals(repeat(30, 14), workPulls(m, gr), "all 14 holds");
    }

    @Test void aDayOfBothTracksIsCountedAsItRuns() {
        // DayLength (the 90-minute notice, the CAP90 hold): length first with no coda, girth
        // straight after with no warm-up and the ramp-in for its fatigue block.
        Model m = bothDays();
        long[] p = DayLength.parts(m, Schedule.PLAN_BOTH, NOW);
        assertEquals(Math.round(54.67 * 60), Math.round(p[1] / 1.0), 1.0, "length 54:40");
        assertEquals(39.25 * 60, p[0], 1.0, "girth 39:15");
        // EXPECTATION CHANGED (R11-5): 51.50 before - the fatigue block's 30 s holds add 25 s.
        assertEquals(51.50 * 60 + 25, m.routineSec(SecondSessionTest.girthSaved(m)), 1.0,
                     "the saved girth routine is unchanged");
    }

    @Test void aLengthOnlyDayKeepsTheCoda() {
        Model m = SecondSessionTest.ownerDay();
        Arrays.fill(m.sched.days, true);
        Arrays.fill(m.sched.plan, Schedule.PLAN_LENGTH);
        Model.Routine length = SecondSessionTest.lengthSaved(m);
        RunShape.Choice c = TrainerTab.dayChoice(m, length, NOW, false, false);
        assertFalse(c.bothTracks);
        Model.Routine r = RunShape.build(m, length, c).routine;
        assertTrue(stage(r, "swap") > 0, "the swap");
        assertEquals(repeat(34, 5), workPulls(m, r), "and the 5 x 2 min expansion");
        assertEquals(67.08, minutes(m, r), 1e-9);
    }

    @Test void expansionOnlyLengthKeepsTheGirthTrim() {
        Model m = owner();                       // no length cylinder
        m.trainerLengthOn = true;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        Mint.Rx g = girth(Plan.L3, 14, 30);
        asMint(m, m.trainerGirth, g, build(m, g, day(m)));
        Mint.Rx l = length(34);
        asMint(m, m.trainerLength, l, build(m, l, day(m)));
        Model.Routine length = SecondSessionTest.lengthSaved(m);
        assertFalse(Say.isTraction(length), "the expansion session");
        filed(m, length, NOW - 10 * MIN, 16);
        Model.Routine girth = SecondSessionTest.girthSaved(m);
        RunShape.Choice c = TrainerTab.dayChoice(m, girth, NOW, false, false);
        assertEquals(5, c.girthSetsOff, "2A: the 5-hold trim stays");
        Model.Routine r = RunShape.build(m, girth, c).routine;
        assertEquals(9, workPulls(m, r).size(), "L3's 14 holds run 9");
    }

    @Test void theBuilderItself() {
        Model m = owner();
        Model.Routine alone = traction(m, 34, 6, day(m));
        Model.Routine both = traction(m, 34, 6, day(m).sameDay(true, false, false));
        assertEquals(67.08, minutes(m, alone), 1e-9);
        assertEquals(54.67, minutes(m, both), 1e-9);
        assertEquals(0.0, both.netTargetMin, 1e-9, "nothing of the coda is asked for net");
        assertFalse(both.assess.on, "no girth tube to check in");
        // A girth-focus block's expansion is the work: it stays on a day of both.
        double lb = Traction.loadLbAtBore(34, 4.5);
        Model.Routine focus = m.routine(RxBuild.tractionRoutineFromRx(m, length(34), lb, 4.5,
            6, true, day(m).sameDay(true, false, false)));
        assertFalse(workPulls(m, focus).isEmpty(), "the doubled expansion runs");
    }

    @Test void aPersonsOwnLengthRoutineLosesOnlyTheSwapAndTheCoda() {
        Model m = bothDays();
        Model.Routine length = SecondSessionTest.lengthSaved(m);
        // The person lowers a strain pull: the routine is theirs now.
        Model.Stage strain = length.stages.get(stage(length, "strain"));
        m.set(strain.setIds.get(0)).up = 30;
        RunShape.Choice c = TrainerTab.dayChoice(m, length, NOW, false, false);
        RunShape.Built b = RunShape.build(m, length, c);
        assertTrue(b.codaDropped);
        // NEW-18 (the owner's wording): what the run's START box says about it.
        assertTrue(RunShape.lines(b, 6, 0, "Girth").contains("Ends after the strain holds — "
            + "no expansion at the end today. Your girth session covers it, so you change "
            + "cylinders once, between the two sessions."), RunShape.lines(b, 6, 0, "Girth")
            .toString());
        assertEquals(-1, stage(b.routine, "swap"));
        assertTrue(workPulls(m, b.routine).isEmpty());
        assertEquals(30, pulls(m, b.routine, stage(b.routine, "strain")).get(0).intValue(),
                     "their pull, as they set it");
        assertTrue(stage(b.routine, "warm") >= 0, "their warm-up, as saved");
    }
}
