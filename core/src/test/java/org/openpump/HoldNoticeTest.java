package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * H1 - THE HOLD NOTIFICATION NEVER PROMISES WHAT CANNOT HAPPEN.
 *
 * Its RELEASE starts RunService, which hands the tap to the live screen. With no live
 * screen in the process - the process ended and the tap started a new one - it did nothing,
 * under a countdown still reading "Vents on its own in m:ss". And a process that ended
 * without taking the notice down left that countdown frozen in the tray.
 */
class HoldNoticeTest {

    @Test
    void releaseReachesTheAppWhenThereIsAScreenToReach() {
        assertEquals(HoldNotice.RELEASE_IN_APP, HoldNotice.onRelease(true));
    }

    @Test
    void aReleaseWithNothingBehindItSaysSoInsteadOfDoingNothing() {
        assertEquals(HoldNotice.SAY_CLOSED, HoldNotice.onRelease(false),
            "no live screen in this process: RELEASE cannot reach the pump, so the notice says "
            + "that rather than sitting there promising a vent");
    }

    @Test
    void theClosedNoticePromisesNothingAndSaysWhatToDo() {
        for (String s : new String[]{ HoldNotice.CLOSED_UNKNOWN, HoldNotice.CLOSED_TOLD_STOP }) {
            assertFalse(s.contains("Vents on its own"), s);
            assertTrue(s.contains("disconnect the tubing at the cuff"), s);
        }
        assertTrue(HoldNotice.CLOSED_TOLD_STOP.contains("tried to stop the pump"),
            "the crash guard queued a stop as the process died - it may never have left the "
            + "phone, so it says it tried, never that it told the pump");
        assertFalse(HoldNotice.CLOSED_TOLD_STOP.contains("told the pump"));
        assertTrue(HoldNotice.CLOSED_TOLD_STOP.contains("check the cuff"));
        assertFalse(HoldNotice.CLOSED_UNKNOWN.contains("told the pump"),
            "a process that ended with no callback cannot claim a stop was sent");
        assertTrue(HoldNotice.CLOSED_TITLE.length() > 0);
        // (the run-stop-watch follow-up) The hold's notice id also carries an ended run's
        // venting notice now, so the notice that replaces it is true of both.
        assertEquals("OpenPump closed while the pump was holding or venting",
            HoldNotice.CLOSED_TITLE);
    }

    @Test
    void aNoticeLeftByAnEndedProcessIsReplaced() {
        assertTrue(HoldNotice.stale(true, false),
            "in the tray, and no hold in this process posted it: its countdown and its RELEASE "
            + "belong to a process that is gone");
        assertFalse(HoldNotice.stale(true, true), "this process's own live hold");
        assertFalse(HoldNotice.stale(false, false), "nothing in the tray");
        assertFalse(HoldNotice.stale(false, true));
    }
}
