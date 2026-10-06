package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 parity run 2, round 3 follow-up (the coordinator's decisions): THE DAYS BEFORE A WEEK OFF
 * BOOKED FOR A LATER DAY ARE NORMAL TRAINING DAYS. Answering arms the gentle return at once
 * (SessionActivity#recordDeload), and the girth pressure step ("gentle return still open") and
 * every level-up ("runs under the working pressure") waited from the answer, where the editor's
 * model steps and levels as usual until the week off begins. Option D's "fell" read its block
 * from that booked week off's end - in the future - so a block that had reached 2 % read as one
 * that never had. And the Trainer's strain line went on showing a lone reading from before the
 * last week off as the figure the plan acts on, which it no longer is (N24).
 */
class BeforeTheWeekOffTest {

    private static final long HOUR = 3600000L, DAY = 24 * HOUR;

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    /** The answer, as SessionActivity's DeloadStartPick records it (recordDeload). */
    private static void recordDeload(Model m, long startMs) {
        Deload.remember(m, startMs, startMs + Plan.LAYOFF_MS);
        m.trainerFirstDeloadTaken = true;
        Deload.arm(m, startMs + Plan.LAYOFF_MS);
    }

    @Test void girthStepsAndLevelsAsUsualUntilTheWeekOffBegins() {
        long now = System.currentTimeMillis();
        long monday = Deload.dayStartPlus(now, 2);
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = now - 220L * DAY;
        m.trainerGirth.level = Plan.L3;
        m.trainerGirth.pressureKpa = 30;
        recordDeload(m, monday);
        Plan.Inputs in = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 7, now, in);
        assertFalse(in.gentleReturnOpen, "the return has not begun: no pressure step waits");
        assertFalse(TrainerTab.returnRunsUnder(m, now), "nor a level-up");
        // The engine then answers as on any day: a due step lands.
        in.netTupMin = 30.0;
        in.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        in.returnRunsUnder = TrainerTab.returnRunsUnder(m, now);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(in).action);
        // The first day back is a return day, as before.
        long back = monday + Plan.LAYOFF_MS + 9L * HOUR;
        Plan.Inputs ret = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 7, back, ret);
        assertTrue(ret.gentleReturnOpen);
        assertTrue(TrainerTab.returnRunsUnder(m, back));
    }

    @Test void aBlockThatReachedStillFellBeforeABookedWeekOff() {
        Model m = new LengthYear().setup().m;
        LengthYear.measured(m, day(1, 9), "PL", 3.0);
        LengthYear.measured(m, day(8, 9), "PL", 1.5);
        LengthYear.measured(m, day(10, 9), "PL", 1.5);
        LengthYear.measured(m, day(15, 9), "PL", 1.5);
        recordDeload(m, day(21, 0));                         // answered for the Monday after
        Plan.Inputs in = LengthYear.inputsFor(m, day(16, 8));
        assertTrue(in.blockHadReachLo, "the block that is running had its 3 %");
        assertEquals(Plan.LENGTH_FELL_RULE, Plan.evaluate(in).rule, "D1: it fell");
    }

    @Test void theStrainLineShowsOnlyWhatThePlanActsOn() {
        Model m = new LengthYear().setup().m;
        LengthYear.measured(m, day(24, 9), "PL", 7.0);
        recordDeload(m, day(28, 0));
        assertNull(LengthTrack.strainActedOn(m, day(36, 8)),
            "a lone reading from before the week off is not the figure any more");
        Model c = new LengthYear().setup().m;
        LengthYear.measured(c, day(1, 9), "PL", 7.0);
        LengthYear.measured(c, day(3, 9), "PL", 7.0);
        recordDeload(c, day(4, 0));
        assertEquals(7.0, LengthTrack.strainActedOn(c, day(12, 8)).doubleValue(), 0.05,
            "a pair confirmed before it still is (A-5)");
        assertEquals(7.0, LengthTrack.strainActedOn(c, day(5, 8)).doubleValue(), 0.05,
            "and so is any reading in the block that is running");
    }
}
