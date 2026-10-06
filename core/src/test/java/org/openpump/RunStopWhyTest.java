package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R11-6 - EVERY ENDED RUN SAYS WHY IT ENDED. The Hold's limit, the two-hour stop and the pump's
 * refusal already did (InRunHoldTest); a run ended by STOP, by the vent on a lost link, by a
 * pump never reached, by Android closing the app or by a safety stop said only "Session
 * stopped". Each now files its own reason, and the summary says it from the record.
 */
class RunStopWhyTest {

    @Test
    void eachEndingHasItsOwnLine() {
        assertEquals("Stopped: you ended the session",
            RunStopReason.line(RunStopReason.WHY_STOP, 0));
        assertEquals("Stopped: the link to the pump was lost, so the pump was told to vent",
            RunStopReason.line(RunStopReason.WHY_LINK_LOST, 0));
        assertEquals("Stopped: the pump could not be reached before the session started",
            RunStopReason.line(RunStopReason.WHY_PUMP_NOT_FOUND, 0));
        assertEquals("Stopped: Android closed the app during the session",
            RunStopReason.line(RunStopReason.WHY_APP_CLOSED, 0));
        assertEquals("Stopped for safety: a question went unanswered for a minute, so the pump "
            + "was released", RunStopReason.line(RunStopReason.WHY_UNANSWERED, 0));
        assertEquals("Stopped for safety: the pressure did not hold at the start, so the pump "
            + "was released", RunStopReason.line(RunStopReason.WHY_NO_HOLD, 0));
        assertEquals("Stopped for safety: the pump check ended the run",
            RunStopReason.line(RunStopReason.WHY_SAFETY, 0));
        assertEquals("Ended early: you chose Finish here at the target",
            RunStopReason.line(RunStopReason.WHY_AT_TARGET, 0));
        assertEquals("Stopped: the app was left and the session could not carry on in the "
            + "background", RunStopReason.line(RunStopReason.WHY_LEFT_APP, 0));
    }

    @Test
    void theCodesAreDistinctAndNoneStillSaysNothing() {
        int[] all = { RunStopReason.WHY_HOLD_LIMIT, RunStopReason.WHY_TWO_HOURS,
            RunStopReason.WHY_PUMP_REFUSED, RunStopReason.WHY_STOP, RunStopReason.WHY_LINK_LOST,
            RunStopReason.WHY_PUMP_NOT_FOUND, RunStopReason.WHY_APP_CLOSED,
            RunStopReason.WHY_UNANSWERED, RunStopReason.WHY_NO_HOLD, RunStopReason.WHY_SAFETY,
            RunStopReason.WHY_AT_TARGET, RunStopReason.WHY_LEFT_APP };
        for (int i = 0; i < all.length; i++) {
            assertNotNull(RunStopReason.line(all[i], 300), "code " + all[i] + " has a line");
            for (int j = i + 1; j < all.length; j++)
                assertTrue(all[i] != all[j], "codes " + i + " and " + j + " differ");
        }
        assertNull(RunStopReason.line(RunStopReason.WHY_NONE, 0), "a completed run: none");
        assertNull(RunStopReason.line(99, 0), "a code from a newer build: nothing guessed");
    }

    @Test
    void theVentingNoticeIsUnchangedForTheNewReasons() {
        // No behaviour change but the summary: the notice still names only the three limits.
        assertTrue(RunStopReason.saysInNotice(RunStopReason.WHY_HOLD_LIMIT));
        assertTrue(RunStopReason.saysInNotice(RunStopReason.WHY_TWO_HOURS));
        assertTrue(RunStopReason.saysInNotice(RunStopReason.WHY_PUMP_REFUSED));
        assertFalse(RunStopReason.saysInNotice(RunStopReason.WHY_STOP));
        assertFalse(RunStopReason.saysInNotice(RunStopReason.WHY_LINK_LOST));
        assertFalse(RunStopReason.saysInNotice(RunStopReason.WHY_APP_CLOSED));
        assertFalse(RunStopReason.saysInNotice(RunStopReason.WHY_NONE));
    }

    @Test
    void aNewReasonIsFiledAndReadBack() throws Exception {
        Model.Sess s = new Model.Sess();
        s.id = "s1";
        s.stopWhy = RunStopReason.WHY_LINK_LOST;
        Model.Sess back = Model.Sess.fromJson(s.toJson());
        assertEquals(RunStopReason.WHY_LINK_LOST, back.stopWhy);
        assertEquals("Stopped: the link to the pump was lost, so the pump was told to vent",
            RunStopReason.line(back.stopWhy, back.stopLimSec));
    }
}
