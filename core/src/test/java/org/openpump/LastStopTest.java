package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * H1 - THE LAST STOP LEAVES THE PHONE BEFORE THE LINK GOES.
 *
 * onDestroy used to queue its last-resort stop and close the link in the same main-thread
 * turn. PumpLink's close() drops every frame still queued, so a stop waiting behind a write
 * in flight never left the phone - the pump went on holding while the next screen had no
 * hold on record. The link now closes only once the stop's own write has completed (plus a
 * short grace for the radio), and never later than a bound, so a stack that never answers
 * cannot keep a dead screen's connection open.
 */
class LastStopTest {

    private static final long Q = 1_000L;       // when the stop was queued

    @Test
    void theLinkStaysOpenUntilTheStopHasLeftThePhone() {
        assertFalse(LastStop.mayClose(Q, Q, LastStop.NOT_WRITTEN,
                                      LastStop.GRACE_MS, LastStop.MAX_MS),
            "closing in the same turn is exactly the bug");
        assertFalse(LastStop.mayClose(Q + LastStop.MAX_MS - 1, Q, LastStop.NOT_WRITTEN,
                                      LastStop.GRACE_MS, LastStop.MAX_MS),
            "not written yet, bound not reached: keep waiting");
    }

    @Test
    void itClosesAGraceAfterTheStopIsWritten() {
        long w = Q + 60;
        assertEquals(w + LastStop.GRACE_MS,
            LastStop.closeAt(Q, w, LastStop.GRACE_MS, LastStop.MAX_MS));
        assertFalse(LastStop.mayClose(w + LastStop.GRACE_MS - 1, Q, w,
                                      LastStop.GRACE_MS, LastStop.MAX_MS));
        assertTrue(LastStop.mayClose(w + LastStop.GRACE_MS, Q, w,
                                     LastStop.GRACE_MS, LastStop.MAX_MS));
    }

    @Test
    void itNeverWaitsPastTheBound() {
        assertEquals(Q + LastStop.MAX_MS,
            LastStop.closeAt(Q, LastStop.NOT_WRITTEN, LastStop.GRACE_MS, LastStop.MAX_MS),
            "a stack that never reports the write still lets the link go");
        long late = Q + LastStop.MAX_MS - 10;
        assertEquals(Q + LastStop.MAX_MS,
            LastStop.closeAt(Q, late, LastStop.GRACE_MS, LastStop.MAX_MS),
            "a late write does not stretch the bound");
        assertTrue(LastStop.mayClose(Q + LastStop.MAX_MS, Q, LastStop.NOT_WRITTEN,
                                     LastStop.GRACE_MS, LastStop.MAX_MS));
    }

    @Test
    void theBoundLeavesRoomForAWriteAlreadyInFlight() {
        // The stop can queue behind one write already in flight (PumpLink's 400 ms write
        // timeout), and a refused write is retried up to three times on that timeout. The
        // bound has to outlast one of those plus the grace, or the stop can still be dropped.
        assertTrue(LastStop.GRACE_MS > 0 && LastStop.GRACE_MS < LastStop.MAX_MS);
        assertTrue(LastStop.MAX_MS >= 400 + 400 + LastStop.GRACE_MS, "" + LastStop.MAX_MS);
        assertTrue(LastStop.MAX_MS <= 3_000, "and it stays short: the screen is gone");
    }

    @Test
    void theNextScreenSaysOnlyWhatWasSentAndWhatToDo() {
        String t = LastStop.SCREEN_CLOSED_TEXT;
        assertTrue(t.contains("tried to stop the pump"), t);
        assertFalse(t.contains("told the pump"), "a write the pump never acknowledges: " + t);
        assertTrue(t.contains("disconnect the tubing at the cuff"), t);
        assertFalse(t.contains("vented") || t.contains("safe"),
            "nothing watched the stop land, so nothing claims it did: " + t);
    }
}
