package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE RELEASE REVIEW: ON ANDROID 13+ THE NOTIFICATION PERMISSION WAS NEVER ASKED FOR RUNS OR
 * HOLDS - only by the reminder toggles. A run got a warning toast, a hold nothing, and a hold
 * now relies on its notification for the countdown, RELEASE, and "Vent not confirmed -
 * disconnect the tubing".
 *
 * {@link NotifyAsk#askNow} is the whole decision: ask ONCE, before the first run or hold, at
 * a point where the person is starting something and nothing is commanded - never mid-run,
 * never mid-hold, never again after an answer unless the person asks from Settings.
 */
class NotifyAskTest {

    private static final int T = 33;   // Android 13, the first that needs the permission

    @Test void asksOnceAtAConsentPointWithNothingCommanded() {
        assertTrue(NotifyAsk.askNow(T, false, false, true, false));
        assertTrue(NotifyAsk.askNow(34, false, false, true, false), "Android 14 too");
    }

    @Test void neverBelowAndroid13() {
        assertFalse(NotifyAsk.askNow(32, false, false, true, false),
            "below 13 notifications need no permission");
        assertFalse(NotifyAsk.askNow(24, false, false, true, false));
    }

    @Test void neverWhenItIsAlreadyGranted() {
        assertFalse(NotifyAsk.askNow(T, true, false, true, false));
    }

    @Test void onlyOnceAskedIsAnswered() {
        assertFalse(NotifyAsk.askNow(T, false, true, true, false),
            "asked before: not again, whatever the answer was");
    }

    @Test void neverMidRunOrMidHold() {
        assertFalse(NotifyAsk.askNow(T, false, false, true, true),
            "something is commanded: a run, a hold, or a stop not yet confirmed");
    }

    @Test void neverWhereThePersonDidNotJustStartSomething() {
        assertFalse(NotifyAsk.askNow(T, false, false, false, false),
            "a hold the flow starts itself, after the START it followed, is not a consent point");
    }

    @Test void everyCombinationAsksOnlyInTheOneCase() {
        int asks = 0;
        for (int sdk : new int[]{ 32, 33 })
            for (int g = 0; g < 2; g++)
                for (int a = 0; a < 2; a++)
                    for (int c = 0; c < 2; c++)
                        for (int m = 0; m < 2; m++)
                            if (NotifyAsk.askNow(sdk, g == 1, a == 1, c == 1, m == 1)) asks++;
        assertEquals(1, asks, "exactly one of the 32 states asks");
    }

    @Test void theWordsArePlainAndOneLine() {
        assertFalse(NotifyAsk.REASON.contains("\n"));
        assertTrue(NotifyAsk.REASON.contains("countdown") && NotifyAsk.REASON.contains("Release")
            && NotifyAsk.REASON.contains("vent"), NotifyAsk.REASON);
        assertTrue(NotifyAsk.DENIED_NOTE.contains("Settings"),
            "the note says where to turn them on later: " + NotifyAsk.DENIED_NOTE);
    }
}
