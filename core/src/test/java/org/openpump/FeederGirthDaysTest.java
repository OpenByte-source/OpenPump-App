package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * t10 R-09 (A6) - FEEDERS ON GIRTH DAYS ONLY, 4-6 h AFTER THE GIRTH SESSION: two a day, 5 x 2
 * min at 75 % of that day's girth command. A day of length only has none; the gap runs from
 * the girth session's end, not the length session's; "feeder on rest days too" is disabled
 * (kept saved, read as false).
 */
class FeederGirthDaysTest {

    /** The owner at L3 with feeders on and a feeder routine saved. */
    static Model withFeeder() {
        Model m = SecondSessionTest.ownerDay();
        m.trainerGirth.level = Plan.L3;
        m.trainerFeederOptIn = true;
        Mint.Rx f = new Mint.Rx(Plan.TRACK_FEEDER, Plan.L3, 5, 120, 0,
                                (int) Math.round(Plan.feederPressureKpa(30)), false, 10.0);
        Model.Routine fr = m.routine(RxBuild.routineFromRx(m, f, 0, 0, day(m)));
        m.trainerFeederMintId = fr.id;
        Arrays.fill(m.sched.days, true);
        return m;
    }

    @Test void aLengthOnlyDayHasNoFeeder() {
        Model m = withFeeder();
        filed(m, SecondSessionTest.lengthSaved(m), NOW - 300 * MIN, 55);
        assertFalse(TrainerTab.feederOn(m, NOW), "no girth today: no feeder");
        assertFalse(TrainerTab.feederReady(m, NOW));
        UpNext up = TrainerTab.upNextNow(m, NOW, false);
        assertTrue(up.what != UpNext.FEEDER, "Up next never names one");
    }

    @Test void theGapRunsFromTheGirthSession() {
        Model m = withFeeder();
        long girthEnd = NOW - 2 * 60 * MIN;                       // 10:00
        filed(m, SecondSessionTest.girthSaved(m), girthEnd, 52);
        filed(m, SecondSessionTest.lengthSaved(m), NOW - 30 * MIN, 55);   // length later
        assertTrue(TrainerTab.feederOn(m, NOW));
        assertEquals(girthEnd + 4 * 60 * MIN, TrainerTab.feederFromMs(m, NOW),
                     "from 14:00 - four hours after the girth session, not the length one");
        assertEquals(girthEnd + Plan.FEEDER_MIN_GAP_MS, SameDay.feederFromMs(0, 0L, girthEnd));
        assertFalse(TrainerTab.feederReady(m, NOW));
        assertTrue(TrainerTab.feederReady(m, girthEnd + 4 * 60 * MIN));
    }

    /** t10 device walk (low): "the work is done - a feeder is next" only after a girth session
     *  that finished or ended at its target - not after one stopped at 1:28 of 40:10. */
    @Test void aStoppedGirthIsNotTheWorkDone() {
        Model m = withFeeder();
        filed(m, SecondSessionTest.lengthSaved(m), NOW - 120 * MIN, 55);
        Model.Sess s = filed(m, SecondSessionTest.girthSaved(m), NOW - 30 * MIN, 1);
        s.completed = false; s.durSec = 88; s.peakKpa = 30.0;
        s.ts = NOW - 30 * MIN - 88_000L;
        assertFalse(TrainerTab.girthFinishedToday(m, NOW));
        UpNext up = TrainerTab.upNextNow(m, NOW, false);
        assertEquals(UpNext.FEEDER, up.what, "it ran: the feeder still follows it (R-09)");
        assertEquals("a feeder is next", up.why);
        s.targetHitAtSet = 6;                                   // "Finish here" at the target
        assertTrue(TrainerTab.girthFinishedToday(m, NOW));
        s.targetHitAtSet = 0; s.completed = true;
        assertTrue(TrainerTab.girthFinishedToday(m, NOW));
        up = TrainerTab.upNextNow(m, NOW, false);
        assertEquals(UpNext.FEEDER, up.what);
        assertTrue(up.why.startsWith("the work is done"), up.why);
    }

    @Test void restDaysTooIsDisabled() {
        Model m = withFeeder();
        m.feederRestDays = true;                 // an older answer, kept
        Arrays.fill(m.sched.days, false);        // today is a rest day
        assertTrue(TrainerTab.feederOffToday(m, NOW), "read as false: the feeder stands down");
        assertFalse(TrainerTab.feederRestDaysOn(m));
        assertTrue(m.feederRestDays, "the saved value is kept");
    }

    @Test void ownerL3FeederRoutine() {
        Model m = withFeeder();
        Model.Routine f = m.routine(m.trainerFeederMintId);
        assertEquals(23, (int) Math.round(Plan.feederPressureKpa(30)), "75 % of 30");
        assertEquals(repeat(23, 5), workPulls(m, f), "5 x 2 min at 23 kPa");
        assertEquals(-1, stage(f, "warm"), "no warm-up");
        assertEquals(10.42, minutes(m, f), 1e-9);
    }

    @Test void theWords() {
        assertEquals("Feeder: girth days only, 4–6 h after the girth session",
                     Plan.FEEDER_DAYS_WORDS);
        assertEquals("a feeder goes 4–6 h after the girth session", UpNext.WHY_FEEDER_GAP);
    }
}
