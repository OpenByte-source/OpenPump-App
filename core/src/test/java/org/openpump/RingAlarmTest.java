package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * THE RING TIMER'S ALARM (0.10, M4) - the pure half. RingTimer sets an AlarmManager alarm so
 * "take it off" arrives with the process frozen or gone; the in-app countdown stays as a
 * second road to the same notice. These are the decisions that wiring rests on.
 */
class RingAlarmTest {

    @Test void belowAndroid12AnExactAlarmNeedsNoPermission() {
        assertEquals(RingAlarm.EXACT, RingAlarm.how(24, false));
        assertEquals(RingAlarm.EXACT, RingAlarm.how(30, false));
        assertFalse(RingAlarm.asksForExact(30, false), "nothing to ask below API 31");
    }

    @Test void fromAndroid12ItIsExactOnlyWhenAllowed() {
        assertEquals(RingAlarm.EXACT, RingAlarm.how(31, true));
        assertEquals(RingAlarm.EXACT, RingAlarm.how(34, true));
        assertEquals(RingAlarm.INEXACT, RingAlarm.how(31, false),
            "not allowed: an inexact alarm, never none");
        assertEquals(RingAlarm.INEXACT, RingAlarm.how(34, false));
        assertTrue(RingAlarm.asksForExact(34, false), "the card explains and offers the setting");
        assertFalse(RingAlarm.asksForExact(34, true));
    }

    @Test void theAlarmAndTheCountdownPostOnce() {
        long end = 1_000_000L;
        assertTrue(RingAlarm.shouldPost(end, end), "the first arrival posts");
        // posting clears the pending end, so the second road finds nothing
        assertFalse(RingAlarm.shouldPost(0L, end), "the second arrival stays quiet");
    }

    @Test void aStoppedTimerPostsNothing() {
        assertFalse(RingAlarm.shouldPost(0L, 1_000_000L));
        assertFalse(RingAlarm.shouldPost(0L, 0L), "an alarm with no end is not a timer");
    }

    @Test void anOldAlarmCannotSpeakForARestartedTimer() {
        assertFalse(RingAlarm.shouldPost(2_000_000L, 1_000_000L));
    }

    @Test void aRebootSetsARunningTimerAgain() {
        assertEquals(RingAlarm.BOOT_REARM, RingAlarm.afterBoot(10_000L, 5_000L));
    }

    @Test void aTimerThatRanOutWhileTheHandsetWasOffIsSaidAtOnce() {
        assertEquals(RingAlarm.BOOT_POST, RingAlarm.afterBoot(10_000L, 10_000L));
        assertEquals(RingAlarm.BOOT_POST,
            RingAlarm.afterBoot(10_000L, 10_000L + RingAlarm.BOOT_STALE_MS));
    }

    @Test void aLongStaleTimerIsDroppedAndNoneIsNothing() {
        assertEquals(RingAlarm.BOOT_NOTHING,
            RingAlarm.afterBoot(10_000L, 10_001L + RingAlarm.BOOT_STALE_MS));
        assertEquals(RingAlarm.BOOT_NOTHING, RingAlarm.afterBoot(0L, 10_000L));
    }

    @Test void theWordsSayWhatToDo() {
        assertEquals("10 minutes are up - take it off.", RingAlarm.doneText(10));
        assertEquals("1 minute is up - take it off.", RingAlarm.doneText(1),
            "one minute is singular");
        assertEquals("2 minutes are up - take it off.", RingAlarm.doneText(2));
        assertEquals("Ring timer", RingAlarm.DONE_TITLE);
        String n = RingAlarm.INEXACT_NOTE.toLowerCase(Locale.US);
        assertTrue(n.contains("late"), "the note says the alert may be late");
        assertTrue(n.contains("screen open"), "and what keeps it on time");
        assertTrue(RingAlarm.ALLOW_BUTTON.contains("Alarms & reminders"),
            "the button names the system page it opens");
    }

    /* ---- a running countdown comes back after a process restart (2026-09-27) ---------- */

    @Test void aRunningTimerIsRestoredFromItsAlarm() {
        long now = 1_800_000_000_000L;
        long ends = now + 7L * 60000L;                    // 7 of 20 minutes left
        long start = RingAlarm.restoredStart(ends, 20, now);
        assertEquals(ends - 20L * 60000L, start, "start = the alarm's end less its minutes");
        assertEquals(13L * 60000L, now - start, "13 minutes gone, as before the restart");
    }

    @Test void nothingIsRestoredWithoutARunningAlarm() {
        long now = 1_800_000_000_000L;
        assertEquals(0L, RingAlarm.restoredStart(0L, 20, now), "nothing pending");
        assertEquals(0L, RingAlarm.restoredStart(now, 20, now), "ends now: the alarm has it");
        assertEquals(0L, RingAlarm.restoredStart(now - 1000L, 20, now), "already over");
        assertEquals(0L, RingAlarm.restoredStart(now + 60000L, 0, now), "minutes unknown");
    }
}
