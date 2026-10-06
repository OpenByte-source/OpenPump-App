package org.openpump;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * V1 SAFETY REVIEW, FINDING 3 - A WRITE-DONE BELONGS TO ITS OWN WRITE.
 *
 * PumpLink sends one frame at a time and retires it on the stack's onCharacteristicWrite, or on
 * a 400 ms timeout when the stack is slow. A late onCharacteristicWrite for the frame the timeout
 * already retired then arrived while the StopWork was in flight, retired the StopWork with the
 * earlier frame's completion time, and the vent watch counted frames from before the stop as
 * evidence about it.
 *
 * Android's callback does not say which write it is for. So PumpLink bumps a token and notes
 * the issue time just BEFORE each write is handed to the stack, and reads the token on the
 * callback thread when the callback fires. {@link WritePairing#isOwn} accepts a write-done only
 * when it carries the in-flight write's token and was stamped no earlier than that write was
 * issued. A stale report can then only be ignored, and the in-flight write retires on its own
 * report or its own timeout, which is later: the conservative direction.
 */
class WritePairingTest {

    @Test void theInFlightWritesOwnReportIsAccepted() {
        assertTrue(WritePairing.isOwn(7, 7, 1_010L, 1_000L));
        assertTrue(WritePairing.isOwn(7, 7, 1_000L, 1_000L), "completed the instant it was issued");
    }

    @Test void aReportForTheWriteTheTimeoutRetiredIsIgnored() {
        // The callback fired for write 6 on the Bluetooth thread, but the UI thread had already
        // timed 6 out and issued 7 (the StopWork) before it handled the report.
        assertFalse(WritePairing.isOwn(6, 7, 1_390L, 1_400L));
        assertFalse(WritePairing.isOwn(6, 7, 1_410L, 1_400L),
            "the token decides, whatever the time");
    }

    @Test void aReportStampedBeforeTheWriteWasIssuedIsIgnored() {
        assertFalse(WritePairing.isOwn(7, 7, 999L, 1_000L),
            "nothing can complete before it was handed to the stack");
    }

    @Test void nothingIsInFlightBeforeTheFirstWrite() {
        assertFalse(WritePairing.isOwn(0, 0, 5L, 0L),
            "token 0 is the one no write ever carries");
    }
}
