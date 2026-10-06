package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * D2 - A MEASUREMENT HOLD WHOSE VENT WAS NEVER CONFIRMED MUST NOT LOCK START UNTIL A RESTART.
 *
 * The hold's pressure (Session#heldKpa) is cleared only by telemetry showing the fall. When
 * its vent was sent and never confirmed, START answered "pressure is commanded" - the one
 * refusal START cannot recover from - and "I can see the cuff is vented" did not clear the
 * hold either, so nothing short of restarting the app let a run start. A released hold is a
 * stop that was sent and not confirmed: exactly START's recoverable case, which re-checks and,
 * once the watch has given up, offers the person's own eyes (which now clear the hold too).
 */
class HoldReleaseGateTest {

    /** Handoff#startRefusal with only the hold's terms varied - no run, nothing else up. */
    private static Handoff.StartGate gate(boolean held, boolean stopUnconfirmed, boolean exhausted) {
        boolean commanded = Handoff.holdCommanded(held, stopUnconfirmed);
        return Handoff.startRefusal(false, false, false, false, stopUnconfirmed, commanded,
                                    exhausted);
    }

    @Test
    void aHoldThatWasReleasedAndNeverConfirmedIsAnUnconfirmedStop() {
        Handoff.StartGate g = gate(true, true, true);
        assertEquals(Handoff.START_STOP_UNCONFIRMED, g.refusal,
            "START re-checks the stop rather than refusing until the app restarts");
        assertTrue(g.offerRecheck);
        assertTrue(g.offerSeenVented, "and, the watch having given up, offers the person's eyes");
    }

    @Test
    void whileTheWatchIsStillTryingThePersonIsNotYetAsked() {
        Handoff.StartGate g = gate(true, true, false);
        assertEquals(Handoff.START_STOP_UNCONFIRMED, g.refusal);
        assertFalse(g.offerSeenVented, "evidence first, eyes only once the watch gives up");
    }

    @Test
    void aHoldStillOnIsStillCommanded() {
        assertTrue(Handoff.holdCommanded(true, false), "no stop sent for it: it is holding");
        assertEquals(Handoff.START_STILL_UNSAFE, gate(true, false, false).refusal,
            "START does not start over a live hold - its own screen owns the exit");
    }

    @Test
    void noHoldIsNothing() {
        assertFalse(Handoff.holdCommanded(false, true));
        assertFalse(Handoff.holdCommanded(false, false));
        assertEquals(Handoff.START_OK, gate(false, false, false).refusal);
    }
}
