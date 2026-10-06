package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * WHICH PRESET THE SCREEN IS TALKING ABOUT — {@link RunEdit#liveIndex}.
 *
 * From a device: after a hold and a long paused clock the wall clock had run about 79
 * seconds ahead of the real playback (the card read "11:03 of 11:11" while the countdown
 * still had 1:27 to go). Everything that resolved the live preset from that clock — the
 * tiles, the dashed band, the deviation, the paused-clock sentence — was then describing
 * a preset the pump had not been given.
 */
class LiveIndexTest {

    @Test
    void theArmedPresetWinsOverAWallClockThatHasDrifted() {
        assertEquals(3, RunEdit.liveIndex(true, 3, 4, 9),
            "the pump holds preset 3; the wall clock having reached 4 does not change that");
    }

    @Test
    void theWallClockIsTheFallbackWhileNothingIsArmed() {
        assertEquals(4, RunEdit.liveIndex(false, 3, 4, 9),
            "in the settle between presets nothing is armed, so the clock's answer stands");
    }

    @Test
    void anIndexOutsideThePlanIsNeverReturned() {
        assertEquals(-1, RunEdit.liveIndex(true, 9, 12, 9), "armed index past the end");
        assertEquals(-1, RunEdit.liveIndex(false, -1, -1, 9), "no answer at all");
        assertEquals(2, RunEdit.liveIndex(true, -1, 2, 9), "no armed preset, clock stands");
    }

    @Test
    void anAdvanceThatArrivesBeforeItsDeadlineIsNotItsOwn() {
        long now = 1_000_000L;
        // A twelve-minute step barely started: an advance here would end the run.
        org.junit.jupiter.api.Assertions.assertTrue(
            RunEdit.advanceTooEarly(now + 720_000L, now), "12:00 still to run");
        // The ordinary cases every re-post site produces: on time, or a hair late.
        org.junit.jupiter.api.Assertions.assertFalse(
            RunEdit.advanceTooEarly(now, now), "exactly due");
        org.junit.jupiter.api.Assertions.assertFalse(
            RunEdit.advanceTooEarly(now - 5_000L, now), "late, as a busy handler is");
        org.junit.jupiter.api.Assertions.assertFalse(
            RunEdit.advanceTooEarly(now + RunEdit.ADVANCE_SLACK_MS, now), "inside the slack");
    }
}
