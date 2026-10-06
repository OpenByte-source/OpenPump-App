package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * H1 (the re-review's concern 1) - A HOLD WHOSE PROCESS WAS KILLED OUTRIGHT IS SAID ON THE NEXT
 * LAUNCH.
 *
 * With the hold in the foreground only an outright kill (a force stop, kill -9) can end the
 * process under it - and then the system removes the hold's notice with the process, and
 * nothing said anything. A flag, written when a hold is armed and cleared on every way a hold
 * ends, survives the kill; the next launch finds it with no hold of its own and says so. It
 * informs only: nothing about STOP, START or the vent reads it.
 */
class HoldTraceTest {

    @Test
    void theFlagFollowsWhetherAHoldIsLive() {
        assertTrue(HoldTrace.live(HoldForeground.HELD));
        assertTrue(HoldTrace.live(HoldForeground.VENTING),
            "a vent still being confirmed is still a hold the process was keeping");
        assertTrue(HoldTrace.live(HoldForeground.UNCONFIRMED),
            "the watch's give-up is NOT the end of the hold (the re-review)");
        assertTrue(HoldTrace.live(HoldForeground.UNCONFIRMED_SEEN));
        assertFalse(HoldTrace.live(HoldForeground.NONE),
            "the fall shown, the person's eyes: the hold is over");
    }

    @Test
    void aFlagLeftWithNoHoldInThisProcessWasOrphaned() {
        assertTrue(HoldTrace.orphaned(true, false),
            "set, and this process holds nothing: the process that held it ended with no "
            + "callback");
        assertFalse(HoldTrace.orphaned(true, true), "this process's own live hold");
        assertFalse(HoldTrace.orphaned(false, false));
        assertFalse(HoldTrace.orphaned(false, true));
    }

    @Test
    void aNewStartWithTheCuffAtRestSettlesAnUnseenMessage() {
        assertTrue(HoldTrace.restSettles(true, true, false, 0.0));
        assertTrue(HoldTrace.restSettles(true, true, false, HoldTrace.REST_KPA - 0.01));
        assertFalse(HoldTrace.restSettles(true, true, false, HoldTrace.REST_KPA + 0.5),
            "pressure still on the cuff: the message stands");
        assertFalse(HoldTrace.restSettles(true, false, false, 0.0),
            "no fresh reading is no evidence of rest");
        assertFalse(HoldTrace.restSettles(true, true, true, 0.0),
            "a no-measurement frame is not a reading of zero");
        assertFalse(HoldTrace.restSettles(false, true, false, 0.0), "nothing pending");
        assertTrue(HoldTrace.REST_KPA > Session.VENT_FALL_NOISE_FLOOR_KPA,
            "above telemetry jitter");
        assertTrue(HoldTrace.REST_KPA < Session.HOLD_EFF_MIN_COMMANDED_KPA,
            "well under the least pressure a hold commands");
    }

    @Test
    void theMessageSaysPlainlyWhatHappenedAndWhatToDo() {
        // (the run-stop-watch follow-up) The trace is set for a hold AND for an ended run's
        // stop not yet confirmed (HoldForeground#ventOwed), so the words are true of both.
        assertEquals("The app closed while the pump was holding or venting", HoldTrace.TITLE);
        assertEquals("The app was closed while the pump was holding or before its vent was "
            + "confirmed. Check the cuff is vented.", HoldTrace.MESSAGE);
        assertFalse(HoldTrace.MESSAGE.contains("stopped"),
            "nothing is claimed about a stop: the process was killed with no callback");
        assertFalse(HoldTrace.TITLE.contains("during a hold"),
            "an ended run's unconfirmed stop is not a hold");
    }
}
