package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE SET COUNT OF THE STEP PLAYING (SetClock, 0.10 final - the mock's reanchor()): the count of
 * sets never changes inside a block; a live change, a pause and a resume keep the set under way
 * (the pump starts it again, from its hold); a change of cycle moves the block's end so every
 * set left runs whole. The device check that found the count restarting at 1 and shrinking
 * ("SET 1 OF 8", then "set 1 of 7") is the case pinned here.
 */
class SetClockTest {

    private static final long S = 1000L;

    @Test
    void untouchedItCountsThePlansCycle() {
        SetClock c = new SetClock();
        Model.Preset p = new Model.Preset();
        p.uh = 40; p.lh = 8; p.durMs = 10 * 48 * S;
        c.start(3, 0L, 48 * S, 10);
        for (long t = 0; t < 480 * S; t += 7 * S) {
            assertEquals(RunLook.setAt(p, t), c.set(t), "the plan's count at " + t);
            assertEquals(10, c.sets());
        }
        assertTrue(c.counts(3));
        assertFalse(c.counts(4));
    }

    @Test
    void aLongerHoldKeepsTheSetAndTheCountAndMovesTheEndOut() {
        SetClock c = new SetClock();
        c.start(1, 0L, 48 * S, 10);                 // 10 x (0:40 hold + 0:08 drop)
        long t = 2 * 48 * S + 20 * S;                // 20 s into set 3
        long end = 10 * 48 * S;
        assertEquals(3, c.set(t));
        int left = c.setsLeft(t);
        assertEquals(8, left, "set 3 and the seven after it");
        // Hold +10 s: the pump starts set 3 again at the new cycle of 58 s.
        long move = SetClock.retimeMs(left, 58 * S, end - t);
        assertEquals(100 * S, move, "8 sets x 10 s, plus the 20 s of set 3 the pump runs again");
        c.restart(t, 58 * S);
        end += move;
        assertEquals(3, c.set(t), "the set under way keeps its number");
        assertEquals(10, c.sets(), "and the block keeps its sets");
        assertEquals(0L, c.inCycleMs(t), "from its hold");
        assertEquals(3, c.set(t + 57 * S));
        assertEquals(4, c.set(t + 58 * S));
        // The new end holds the eight sets left, whole.
        assertEquals(8 * 58 * S, end - t);
        assertEquals(10, c.set(end - 1));
    }

    @Test
    void twoTapsAndAPauseNeverRestartTheCountAtOne() {
        // The device check: hold 0:40 -> 0:50 in two taps, then Pause at 2:01.
        SetClock c = new SetClock();
        c.start(1, 0L, 48 * S, 10);
        c.restart(20 * S, 53 * S);                    // the first tap in force (set 1)
        c.restart(25 * S, 58 * S);                    // the second
        assertEquals(1, c.set(25 * S));
        long pause = 121 * S;
        assertEquals(2, c.set(pause), "set 1 ran again from 0:25; set 2 from 1:23");
        c.pause(pause);
        assertEquals(2, c.set(pause + 90 * S), "nothing is counted while paused");
        assertEquals(10, c.sets());
        c.resume(pause + 90 * S, 58 * S);             // the slot started again
        assertEquals(2, c.set(pause + 90 * S), "the paused set carries on");
        assertEquals(0L, c.inCycleMs(pause + 90 * S));
        assertEquals(3, c.set(pause + 90 * S + 58 * S));
        assertEquals(10, c.sets());
    }

    @Test
    void anyRestartRunsTheSetUnderWayWholeByTheOneRule() {
        // The device check of 772e1e7: five sets of 0:48, paused at 2:20 in set 3 and resumed a
        // second later. The pump starts set 3 again from its hold, so the end moves by the 44 s
        // of set 3 already run - or set 5 is cut short when the ramp takes over.
        SetClock c = new SetClock();
        c.start(1, 0L, 48 * S, 5);
        long end = 5 * 48 * S;
        c.pause(140 * S);
        end += 1 * S;                                 // the pause's second, pushed by the clock
        long resume = 141 * S;
        int left = c.setsLeft(resume);
        assertEquals(3, left);
        c.resume(resume, 48 * S);
        long move = SetClock.retimeMs(left, 48 * S, end - resume);
        assertEquals(44 * S, move, "the part of set 3 already run");
        end += move;
        assertEquals(3 * 48 * S, end - resume, "sets 3, 4 and 5, each whole");
        assertEquals(5, c.set(end - 1));
        // A pull-only change is the same rule: 20 s into set 4, the cycle unchanged.
        long t = resume + 48 * S + 20 * S;
        assertEquals(4, c.set(t));
        long m2 = SetClock.retimeMs(c.setsLeft(t), 48 * S, end - t);
        assertEquals(20 * S, m2);
        c.restart(t, 48 * S);
        assertEquals(4, c.set(t));
    }

    @Test
    void aShorterHoldBringsTheEndIn() {
        SetClock c = new SetClock();
        c.start(1, 0L, 60 * S, 5);
        long t = 30 * S;                              // set 1, 30 s in
        long move = SetClock.retimeMs(c.setsLeft(t), 50 * S, 5 * 60 * S - t);
        assertEquals(5 * 50 * S - 270 * S, move, "20 s sooner: five sets of 50 s from here");
        c.restart(t, 50 * S);
        assertEquals(1, c.set(t));
    }

    @Test
    void theCountNeverPassesTheSetsNorGoesBelowOne() {
        SetClock c = new SetClock();
        c.start(0, 0L, 60 * S, 3);
        assertEquals(3, c.set(10_000 * S));
        assertEquals(1, c.setsLeft(10_000 * S));
        c.reset();
        assertEquals(-1, c.forIdx());
        c.start(2, 0L, 0L, 0);                        // a step that does not cycle
        assertFalse(c.counts(2));
        assertEquals(1, c.set(500 * S));
        assertEquals(0L, SetClock.retimeMs(0, 60 * S, 10 * S));
    }

    @Test
    void aRestartDuringAPauseStaysPaused() {
        SetClock c = new SetClock();
        c.start(1, 0L, 48 * S, 10);
        c.pause(100 * S);
        c.restart(150 * S, 48 * S);                   // a convergence while paused
        assertTrue(c.paused());
        assertEquals(3, c.set(400 * S));
        c.resume(400 * S, 48 * S);
        assertEquals(3, c.set(400 * S));
    }
}
