package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * (the safety review of the in-run Hold's limit, follow-up) A HOLD'S LIMIT NEVER FIRES IN THE
 * VENT WATCH'S WINDOW AROUND ONE OF THE PUMP'S OWN STEP DOWNS.
 *
 * The run's Hold is one preset the pump repeats: 255 s at the pull, then 1 s a kPa lower (the
 * override's lower clamp). So the pump itself lets the cuff fall a kPa at 255, 511, 767 s... A
 * limit that fires just before one sends its StopWork into it, and if that stop is lost the
 * watch reads the step's fall as the vent. A limit in [step - 7 s, step + 2 s] fires at
 * step - 7 s instead: earlier, never later.
 */
class StepWindowTest {

    private static final int H = InRunHold.CHUNK_HOLD_S, D = InRunHold.CHUNK_DIP_S;

    @Test
    void theRunsHoldIsTheLayoutItWrites() {
        assertEquals(255, H, "applyOverride(at, at, 255, 1, ...) in SessionActivity#enterHold");
        assertEquals(1, D);
        assertEquals(7, StepWindow.BEFORE_S, "the watch's 6 s window and a second more");
        assertEquals(2, StepWindow.AFTER_S, "the step's own second and a second more");
    }

    @Test
    void aLimitOnAStepFiresSevenSecondsBeforeIt() {
        assertEquals(248, StepWindow.fireAtSec(255, H, D), "the first step, at 255 s");
        assertEquals(504, StepWindow.fireAtSec(510, H, D), "8:30, one second before 511");
        assertEquals(760, StepWindow.fireAtSec(767, H, D), "the third step, at 767 s");
    }

    @Test
    void aLimitAlreadyClearOfEveryStepIsUnchanged() {
        assertEquals(300, StepWindow.fireAtSec(300, H, D), "5:00, the default");
        assertEquals(60, StepWindow.fireAtSec(60, H, D));
        assertEquals(900, StepWindow.fireAtSec(900, H, D));
        assertEquals(480, StepWindow.fireAtSec(480, H, D));
        assertEquals(540, StepWindow.fireAtSec(540, H, D));
    }

    @Test
    void theWindowsEdges() {
        // [step - 7, step + 2] around 255: step - 7 is itself the early moment, so it stands.
        assertEquals(247, StepWindow.fireAtSec(247, H, D));
        assertEquals(248, StepWindow.fireAtSec(248, H, D));
        assertEquals(248, StepWindow.fireAtSec(249, H, D));
        assertEquals(248, StepWindow.fireAtSec(257, H, D), "step + 2 is still inside");
        assertEquals(258, StepWindow.fireAtSec(258, H, D), "step + 3 is clear: the step is over");
        assertEquals(504, StepWindow.fireAtSec(513, H, D));
        assertEquals(514, StepWindow.fireAtSec(514, H, D));
    }

    @Test
    void neverLaterNeverInAWindowAndOnlyMovedWhenItHadTo() {
        for (int t = 0; t <= 2000; t++) {
            int f = StepWindow.fireAtSec(t, H, D);
            assertTrue(f <= t, "never later: " + t + " -> " + f);
            assertTrue(f >= 0, "never negative: " + t + " -> " + f);
            boolean inside = false, fInside = false;
            for (int step = H; step <= 2100; step += H + D) {
                if (t > step - StepWindow.BEFORE_S && t <= step + StepWindow.AFTER_S) inside = true;
                if (f > step - StepWindow.BEFORE_S && f <= step + StepWindow.AFTER_S) fInside = true;
            }
            assertTrue(!fInside, "the moment is clear of every step: " + t + " -> " + f);
            if (!inside) assertEquals(t, f, "a clear limit is not moved: " + t);
            else assertTrue(t - f <= StepWindow.BEFORE_S + StepWindow.AFTER_S,
                "moved only to just before its own step: " + t + " -> " + f);
        }
    }

    @Test
    void aHoldThatDoesNotStepDownIsNeverMoved() {
        assertEquals(510, StepWindow.fireAtSec(510, H, 0), "no dip, no step");
        assertEquals(510, StepWindow.fireAtSec(510, 0, 0));
    }

    @Test
    void theRunsHoldVentsWhereTheWindowSays() {
        assertEquals(504, InRunHold.firesAtSec(510), "8:30 is a setting Settings offers");
        assertEquals(300, InRunHold.firesAtSec(300));
        assertEquals(1000L + 504_000L, InRunHold.endsAt(1000L, 510));
        assertEquals(1000L + 300_000L, InRunHold.endsAt(1000L, 300));
        for (int s = Model.END_HOLD_MIN_SEC; s <= Model.END_HOLD_MAX_SEC; s += 30) {
            int f = InRunHold.firesAtSec(s);
            assertTrue(f <= InRunHold.limitSec(s), "never longer than the setting: " + s);
            assertEquals(StepWindow.fireAtSec(InRunHold.limitSec(s), H, D), f, "setting " + s);
        }
    }
}
