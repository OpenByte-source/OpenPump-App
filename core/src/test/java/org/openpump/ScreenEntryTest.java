package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * (re-review 4) WHAT AN INTENT ARRIVING AT THE ONE SCREEN MAY DO.
 *
 * There is one SessionActivity (singleTask; a duplicate finishes itself), so every way into
 * the app - the launcher icon, a notification, the widget, a reminder, a launcher shortcut -
 * arrives at the screen that may be running a run or holding a cuff. None of them may start a
 * second run or hold, or get round START's gate: with one live, the screen simply comes
 * forward as it is.
 */
class ScreenEntryTest {

    @Test
    void anArrivalWithNoActionOnlyBringsTheScreenForward() {
        // The launcher icon, a notification, the widget, a reminder: nothing to route.
        assertEquals(ScreenEntry.NOTHING, ScreenEntry.onArrival(null, false, false));
        assertEquals(ScreenEntry.NOTHING, ScreenEntry.onArrival(null, false, true));
    }

    @Test
    void aShortcutDuringARunOrHoldIsHeldBackNeverRouted() {
        for (String a : new String[]{ "start_last", "log_measurement", "progress_photos" })
            assertEquals(ScreenEntry.HELD_BACK, ScreenEntry.onArrival(a, false, true),
                "\"" + a + "\" with a run or hold live would take the screen off it, or "
                + "start a second one");
    }

    @Test
    void aShortcutWithNothingLiveIsRoutedAndStartsNothingItself() {
        // Routed: "Start last routine" still lands on START's own confirmation and gate.
        assertEquals(ScreenEntry.ROUTE, ScreenEntry.onArrival("start_last", false, false));
        assertEquals(ScreenEntry.ROUTE, ScreenEntry.onArrival("log_measurement", false, false));
    }

    /* ---- (re-review 5) the whole-app lock ------------------------------------------- */

    @Test
    void withTheWholeAppLockedAShortcutWaitsForTheUnlock() {
        // The lock card is up (the cold-open challenge was cancelled). "Progress photos" used
        // to open the photos over it: the areas' own locks stand down in whole-app mode.
        for (String a : new String[]{ "start_last", "log_measurement", "progress_photos" }) {
            assertEquals(ScreenEntry.HELD_FOR_UNLOCK, ScreenEntry.onArrival(a, true, false),
                "\"" + a + "\" with the whole app locked went past the lock");
            assertEquals(ScreenEntry.HELD_FOR_UNLOCK, ScreenEntry.onArrival(a, true, true),
                "the lock is asked before anything else");
        }
    }

    @Test
    void anArrivalWithNoActionNeverTouchesTheLockCard() {
        // A notice, the widget, a reminder: nothing to route, locked or not.
        assertEquals(ScreenEntry.NOTHING, ScreenEntry.onArrival(null, true, false));
        assertEquals(ScreenEntry.NOTHING, ScreenEntry.onArrival(null, true, true));
    }

    @Test
    void theHeldForUnlockSentenceSaysUnlockFirst() {
        String l = ScreenEntry.HELD_FOR_UNLOCK_WORDS.toLowerCase(Locale.US);
        assertTrue(l.contains("unlock"), ScreenEntry.HELD_FOR_UNLOCK_WORDS);
    }

    @Test
    void theHeldBackSentenceSaysWhyAndPromisesNothing() {
        String s = ScreenEntry.HELD_BACK_WORDS;
        String l = s.toLowerCase(Locale.US);
        assertTrue(l.contains("run or hold"), s);
        assertFalse(l.contains("started") || l.contains("starting"), s);
    }
}
