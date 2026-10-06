package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * D2 - WHILE THE APP HAS TOLD THE PUMP TO HOLD PRESSURE, AN UNKNOWN READING COUNTS TOWARD
 * THE TWO-HOUR STOP.
 *
 * The final safety review: frames reading 0.0 (the pump's "no measurement") moved neither
 * the set clock nor the two-hour cap, which counted only frames with a real reading
 * (Session#tupMs's gross). A sensor that drops to 0.0 while the cuff is under pressure left
 * STOP as the only thing that ended the run. The app cannot tell a dead sensor from a vented
 * cuff, so while it is commanding pressure it must assume the pressure is there: a 0.0
 * frame, or no frame at all, counts exactly as a real reading would. Not during a rest, not
 * once a vent is confirmed, not while an assessment owns the pump - the caller decides that
 * (RunEdit#commandingPressure) and marks each frame with it.
 *
 * The figures a session FILES are not changed: gross stays what was measured.
 */
class UnknownReadingsCapTest {

    /** SimPump.TELEM_PERIOD_MS - the pump's telemetry period. */
    private static final long FRAME_MS = 240L;
    private static final long MIN = 60_000L;

    /** Frames from `from` to `to`, each reading `kpa` (NaN for no reading), marked
     *  commanding or not, into `s`. Returns the last frame's time. */
    private static long frames(Session s, long from, long to, double kpa, boolean commanding) {
        long t = from;
        for (; t <= to; t += FRAME_MS) {
            boolean nr = Double.isNaN(kpa);
            s.noteHoldFrame(t, nr ? 0.0 : kpa, nr, 17, Session.HOLD_PHASE_HOLD, 0,
                            false, false, commanding);
        }
        return t - FRAME_MS;
    }

    private static Session run() {
        Session s = new Session();
        s.beginRun(0L);
        return s;
    }

    @Test
    void aNoReadingFrameWhilePressureIsCommandedCountsExactlyAsARealOne() {
        Session real = run(), blind = run();
        long end = frames(real, 0L, 10 * MIN, 17.0, true);
        frames(blind, 0L, 10 * MIN, Double.NaN, true);

        double realSec = real.sealedForCapSec(end, false);
        assertEquals(real.netGrossTupSec(16.93)[1], realSec, 1e-9,
            "with real readings the stop counts what gross always counted");
        assertEquals(realSec, blind.sealedForCapSec(end, false), 1e-9,
            "and a sensor reading nothing counts the same, second for second");
    }

    @Test
    void aNoReadingFrameThatIsNotUnderCommandDoesNotCount() {
        Session s = run();
        long end = frames(s, 0L, 10 * MIN, Double.NaN, false);
        assertEquals(0.0, s.sealedForCapSec(end, false), 1e-9,
            "a rest, a confirmed vent, an assessment: a 0.0 there is a vented cuff");
    }

    @Test
    void silenceWhilePressureIsCommandedCountsUpToNow() {
        Session s = run();
        long last = frames(s, 0L, MIN, 17.0, true);
        double sealed = s.sealedForCapSec(last, true);
        assertEquals(sealed + 30.0, s.sealedForCapSec(last + 30_000L, true), 1e-9,
            "no frame at all while commanding: the half-minute since the last one counts");
        assertEquals(sealed, s.sealedForCapSec(last + 30_000L, false), 1e-9,
            "and not once nothing is commanded");
    }

    @Test
    void theGapBeforeACommandedFrameIsCountedOnce() {
        Session s = run();
        long last = frames(s, 0L, MIN, 17.0, true);
        double before = s.sealedForCapSec(last + 20_000L, true);
        s.noteHoldFrame(last + 20_000L, 0.0, true, 17, Session.HOLD_PHASE_HOLD, 0,
                        false, false, true);
        assertEquals(before, s.sealedForCapSec(last + 20_000L, true), 1e-9,
            "when the frame arrives, the silence it closes is counted by it, not twice");
    }

    @Test
    void theFiledFiguresSayWhatWasMeasured() {
        Session s = run();
        long end = frames(s, 0L, 10 * MIN, Double.NaN, true);
        assertEquals(0.0, s.netGrossTupSec(16.93)[1], 1e-9,
            "gross, as filed, is still real sealed time: nothing was measured");
        assertTrue(s.sealedForCapSec(end, false) > 590.0, "the stop counts it all the same");
    }

    @Test
    void twoHoursOfABlindCuffUnderCommandReachTheStop() {
        Session s = run();
        long end = frames(s, 0L, 121 * MIN, Double.NaN, true);
        assertFalse(Plan.grossCapReached(s.netGrossTupSec(16.93)[1]),
            "the finding: counted from real readings, a blind sensor never ends the run");
        assertTrue(Plan.grossCapReached(s.sealedForCapSec(end, true)),
            "counted as the stop now counts, it does");
    }

    @Test
    void aNewRunForgetsWhatTheLastOneCommanded() {
        Session s = run();
        frames(s, 0L, 5 * MIN, Double.NaN, true);
        s.beginRun(10 * MIN);
        long end = frames(s, 10 * MIN, 15 * MIN, Double.NaN, false);
        assertEquals(0.0, s.sealedForCapSec(end, false), 1e-9,
            "the marks start over with the run, like every other per-frame record");
    }

    @Test
    void outsideARunNothingIsCountedFromTheClock() {
        Session s = new Session();
        assertEquals(0.0, s.sealedForCapSec(5 * MIN, true), 1e-9,
            "no run: no frames, and no silence to count");
        s.beginRun(0L);
        assertEquals(30.0, s.sealedForCapSec(30_000L, true), 1e-9,
            "a run commanding pressure before its first frame: counted from its start");
        s.endRun();
        assertEquals(0.0, s.sealedForCapSec(60_000L, true), 1e-9, "and not after it ended");
    }

    /* ------------------------------------------------ what counts as commanding */

    @Test
    void pressureIsCommandedByAPresetOrAHoldAndNotByARestOrAnAssessment() {
        assertTrue(RunEdit.commandingPressure(true, false, false, false, false, false),
            "a preset under way");
        assertFalse(RunEdit.commandingPressure(false, false, false, false, false, false),
            "no run");
        assertFalse(RunEdit.commandingPressure(true, true, false, false, false, false),
            "an assessment owns the pump: its pull and its vent wait end on their own timers");
        assertFalse(RunEdit.commandingPressure(true, false, true, false, false, false),
            "a planned rest");
        assertFalse(RunEdit.commandingPressure(true, false, false, true, false, false),
            "an inserted rest");
        assertFalse(RunEdit.commandingPressure(true, false, false, false, true, false),
            "a changeover waiting for its acknowledgement");
        assertFalse(RunEdit.commandingPressure(true, false, false, false, false, true),
            "a rest step");
    }
}
