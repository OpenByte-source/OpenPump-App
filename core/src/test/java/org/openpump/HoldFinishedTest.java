package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * C8 - WHAT "FINISHED" MEANS FOR A STANDARDISATION HOLD, ONCE (study problem 3).
 *
 * "Finished" - the hold that makes a reading standardised - was decided in exactly one
 * place, the hold screen's exit. The routine that ends into a hold ("Hold for the
 * measurement") never passes that screen, so its after-reading was never filed as
 * standardised, and one taken before the pump had reached the pressure was filed the same
 * way. {@link Session#holdFinishedAt} is the one rule both routes now file; WiringCheck
 * invariant 53 holds both call sites to it.
 */
class HoldFinishedTest {

    private static final int SEC = 30;
    private static final long NOW = 1_000_000L;

    @Test void aDwellServedInsideItsWindowIsFinished() {
        assertTrue(Session.holdFinishedAt(30_000L, SEC, NOW + 60_000L, NOW),
            "30 s at pressure, measured with a minute of the window left: standardised");
        assertTrue(Session.holdFinishedAt(45_000L, SEC, NOW + 1L, NOW),
            "...up to the last millisecond of the window");
    }

    @Test void measuringBeforeThePumpGotThereIsNotFinished() {
        assertFalse(Session.holdFinishedAt(0L, SEC, 0L, NOW),
            "\"Measure now\" tapped the moment the routine ended - the cuff never reached the "
                + "pressure - is saved without the hold");
        assertFalse(Session.holdFinishedAt(29_999L, SEC, 0L, NOW),
            "one millisecond short of the dwell is saved without the hold");
    }

    @Test void aDwellWhoseWindowHasClosedIsNotFinished() {
        assertFalse(Session.holdFinishedAt(30_000L, SEC, NOW, NOW),
            "the window closes AT its end, the same instant the hold screen stops offering it");
        assertFalse(Session.holdFinishedAt(30_000L, SEC, NOW - 5_000L, NOW),
            "a dwell completed long enough ago that its window has passed is saved without it");
    }

    @Test void aCompletedDwellWithNoWindowOpenedIsNotFinished() {
        assertFalse(Session.holdFinishedAt(30_000L, SEC, 0L, NOW),
            "a window that was never opened (0) is not an open one - the tick that completes "
                + "the dwell is what opens it, and nothing else may stand in for it");
    }

    @Test void theDwellIsTheSameInclusiveBoundaryAsHoldComplete() {
        assertEquals(Session.holdComplete(30_000L, SEC),
            Session.holdFinishedAt(30_000L, SEC, NOW + 1L, NOW),
            "exactly the configured dwell counts, as it always has on the hold screen");
    }
}
