package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * H1 (safety review) - A LIVE HOLD KEEPS THE SERVICE IN THE FOREGROUND.
 *
 * SAFETY.md #5: leaving a screen never orphans a pump under pressure. The hold's limit is a
 * tick in this process; without a foreground service the process can be cached and ended
 * with the hold on (a routine that ended into a hold in the background, the screen off with
 * another app coming to the front). So a hold is one of the service's reasons to live - a
 * term of its own, read by the service's lifetime only, never by liveWork(), which onStop
 * asks and which must keep meaning "a run" so every exit still vents a hold.
 */
class HoldForegroundTest {

    @Test
    void aCommandedHoldIsHeld() {
        assertEquals(HoldForeground.HELD, HoldForeground.phase(true, true, false, false, false));
        assertEquals(HoldForeground.HELD, HoldForeground.phase(true, true, true, true, true),
            "while the limit runs the hold is held, whatever an older watch says");
    }

    @Test
    void aHoldWhoseVentIsBeingConfirmedIsStillWatched() {
        assertEquals(HoldForeground.VENTING,
            HoldForeground.phase(true, false, true, false, false),
            "the stop was sent and is being retried: the process must live to confirm it");
    }

    @Test
    void nothingIsKeptAliveForNothing() {
        assertEquals(HoldForeground.NONE, HoldForeground.phase(false, false, false, false, false));
        assertEquals(HoldForeground.NONE, HoldForeground.phase(false, true, true, false, false));
        assertEquals(HoldForeground.NONE, HoldForeground.phase(false, false, true, true, true));
        assertEquals(HoldForeground.NONE, HoldForeground.phase(true, false, false, false, false),
            "no limit and no stop outstanding: the fall was shown, nothing is left to watch");
    }

    /* THE RE-REVIEW'S MEDIUM: a vent watch that gave up with nobody looking erased every
     * trace of the hold - the phase went to NONE, the Tick cleared hold_live, dropped the
     * foreground and the wakelock and took the "disconnect the tubing" notice down. An
     * exhausted, unconfirmed vent is not the end of the hold. */

    @Test
    void anUnconfirmedGiveUpIsNotTheEndOfTheHold() {
        assertEquals(HoldForeground.UNCONFIRMED,
            HoldForeground.phase(true, false, true, true, false),
            "the watch gave up and nobody has seen it: the hold goes on in every sense");
        assertEquals(HoldForeground.UNCONFIRMED_SEEN,
            HoldForeground.phase(true, false, true, true, true));
        assertTrue(HoldTrace.live(HoldForeground.UNCONFIRMED), "the next launch must warn");
        assertTrue(HoldTrace.live(HoldForeground.UNCONFIRMED_SEEN),
            "seen but not acted on: a kill now must still be said");
    }

    @Test
    void theServiceStaysUntilThePersonHasSeenTheGiveUp() {
        assertTrue(HoldForeground.keepsService(false, HoldForeground.UNCONFIRMED),
            "nobody has seen it: the process, its wakelock and its notice stay");
        assertFalse(HoldForeground.keepsService(false, HoldForeground.UNCONFIRMED_SEEN),
            "the person has the give-up in front of them; no retry is running");
        assertTrue(HoldForeground.noticeStays(HoldForeground.UNCONFIRMED_SEEN),
            "the service goes, the notice that tells the truth does not");
        assertFalse(HoldForeground.noticeStays(HoldForeground.UNCONFIRMED),
            "the service is still up and the notice is its own");
        assertFalse(HoldForeground.noticeStays(HoldForeground.NONE));
        assertFalse(HoldForeground.noticeStays(HoldForeground.VENTING));
    }

    @Test
    void theServiceLivesForARunOrAHold() {
        assertTrue(HoldForeground.keepsService(true, HoldForeground.NONE));
        assertTrue(HoldForeground.keepsService(false, HoldForeground.HELD),
            "a run that ended into a hold keeps the service across the hand-over");
        assertTrue(HoldForeground.keepsService(false, HoldForeground.VENTING),
            "every retry runs in the foreground");
        assertFalse(HoldForeground.keepsService(false, HoldForeground.NONE));
    }

    @Test
    void aHoldStartWithNoScreenBehindItStops() {
        assertTrue(HoldForeground.holdStartEnters(true, HoldForeground.HELD));
        assertFalse(HoldForeground.holdStartEnters(false, HoldForeground.NONE),
            "a redelivered hold start with no live screen: nothing to keep - it stops");
        assertFalse(HoldForeground.holdStartEnters(true, HoldForeground.NONE),
            "the hold ended before the start arrived");
        assertFalse(HoldForeground.holdStartEnters(true, HoldForeground.UNCONFIRMED_SEEN));
    }

    @Test
    void aRunEndsIntoAHoldOnlyWithSomeoneLooking() {
        assertTrue(HoldForeground.runEndsIntoHold(false, false, true, true, true, true, true));
        assertFalse(HoldForeground.runEndsIntoHold(false, false, true, true, true, true, false),
            "the screen is not started - nobody is looking - so the run vents instead");
        assertFalse(HoldForeground.runEndsIntoHold(true, false, true, true, true, true, true),
            "an abort is never answered with more pressure");
        assertFalse(HoldForeground.runEndsIntoHold(false, true, true, true, true, true, true),
            "a manual run is offered no after measurement");
        assertFalse(HoldForeground.runEndsIntoHold(false, false, false, true, true, true, true));
        assertFalse(HoldForeground.runEndsIntoHold(false, false, true, false, true, true, true));
        assertFalse(HoldForeground.runEndsIntoHold(false, false, true, true, false, true, true));
        assertFalse(HoldForeground.runEndsIntoHold(false, false, true, true, true, false, true));
    }

    @Test
    void theNotificationSaysWhatIsTrueNow() {
        assertEquals("Pressure held at -5.9 inHg", HoldForeground.heldTitle("-5.9 inHg"));
        assertEquals("Vents on its own in 4:53", HoldForeground.heldText(293));
        assertEquals("Vents on its own in 0:07", HoldForeground.heldText(7));
        assertTrue(HoldForeground.VENTING_TEXT.contains("pressure falling"),
            "while the vent is confirmed it says it is waiting, and claims nothing more");
        assertFalse(HoldForeground.VENTING_TEXT.contains("vented"), HoldForeground.VENTING_TEXT);
        assertEquals("The app couldn't confirm the pump vented. Disconnect the tubing at the cuff.",
            HoldForeground.UNCONFIRMED_TEXT);
    }

    /* ---- (re-review 4) the vent that could not be sent, sent when the link is back ---- */

    @Test
    void aHoldWhoseStopCouldNotBeSentIsVentedWhenTheLinkIsBack() {
        assertTrue(HoldForeground.ventOwedOnLinkReturn(true, false, true, false, false),
            "the stop was wanted, the watch gave up with the link down: vent now");
    }

    @Test
    void nothingIsOwedWhileHeldRetryingSettledOrUnderARun() {
        assertFalse(HoldForeground.ventOwedOnLinkReturn(true, true, false, false, false),
            "a hold still commanded, its limit running: nobody asked for its stop");
        assertFalse(HoldForeground.ventOwedOnLinkReturn(true, false, true, true, false),
            "the watch is still retrying - its next retry goes over the link that came back");
        assertFalse(HoldForeground.ventOwedOnLinkReturn(true, false, false, false, false),
            "the stop was evidenced");
        assertFalse(HoldForeground.ventOwedOnLinkReturn(false, false, true, false, false),
            "no hold");
        assertFalse(HoldForeground.ventOwedOnLinkReturn(true, false, true, false, true),
            "a run owns its own stops");
    }

    /* ---- (the safety review of the in-run Hold's limit) a run's stop, watched like a hold's */

    @Test
    void aStopNotYetSeenWithNothingLiveIsOwedAVentLikeAHold() {
        assertTrue(HoldForeground.ventOwed(true, false, false), "a hold outstanding");
        assertTrue(HoldForeground.ventOwed(false, false, true),
            "a run has ended (STOP, the Hold's limit, the two-hour stop, link loss, the plan's "
            + "end) and its stop is not evidenced: the cuff may still be under pressure");
        assertFalse(HoldForeground.ventOwed(false, true, true),
            "during a run the run itself keeps the service");
        assertFalse(HoldForeground.ventOwed(false, false, false), "nothing owed");
    }

    @Test
    void anEndedRunsUnseenStopKeepsTheServiceAndItsNoticeAsAHoldsDoes() {
        int venting = HoldForeground.phase(HoldForeground.ventOwed(false, false, true), false,
                                           true, false, false);
        assertEquals(HoldForeground.VENTING, venting, "retrying: VENTING");
        assertTrue(HoldForeground.keepsService(false, venting));
        int unconfirmed = HoldForeground.phase(HoldForeground.ventOwed(false, false, true),
                                               false, true, true, false);
        assertEquals(HoldForeground.UNCONFIRMED, unconfirmed,
            "the watch gave up with nobody looking: kept, the true notice up");
        assertTrue(HoldForeground.keepsService(false, unconfirmed));
        int seen = HoldForeground.phase(HoldForeground.ventOwed(false, false, true), false,
                                        true, true, true);
        assertEquals(HoldForeground.UNCONFIRMED_SEEN, seen);
        assertFalse(HoldForeground.keepsService(false, seen), "the give-up seen: it may go");
        assertTrue(HoldForeground.noticeStays(seen), "...and its notice stays");
        assertEquals(HoldForeground.NONE, HoldForeground.phase(
            HoldForeground.ventOwed(false, false, false), false, false, false, false),
            "the vent seen, or the person's eyes: over");
    }

    @Test
    void ventNotConfirmedRingsOncePerGiveUp() {
        assertTrue(HoldForeground.unconfirmedRings(HoldForeground.VENTING),
            "replacing the quiet venting notice on the same id: it rings");
        assertTrue(HoldForeground.unconfirmedRings(HoldForeground.NONE), "nothing up: it rings");
        assertTrue(HoldForeground.unconfirmedRings(HoldForeground.HELD));
        assertFalse(HoldForeground.unconfirmedRings(HoldForeground.UNCONFIRMED),
            "posted again over itself - the same give-up: no second ring");
        assertFalse(HoldForeground.unconfirmedRings(HoldForeground.UNCONFIRMED_SEEN));
    }

    @Test
    void theVentingNoticeSaysWhyTheRunStopped() {
        assertEquals(HoldForeground.VENTING_TEXT, HoldForeground.ventingText(null));
        assertEquals(HoldForeground.VENTING_TEXT, HoldForeground.ventingText(""));
        String t = HoldForeground.ventingText("Stopped: the hold reached its limit (5:00)");
        assertTrue(t.startsWith("Stopped: the hold reached its limit (5:00). "), t);
        assertTrue(t.endsWith(HoldForeground.VENTING_TEXT), t);
    }
}
