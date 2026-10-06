package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * "+30 s HOLD" AND THE STRIP'S HOLD / DROP TIME NEVER REPEAT THE HOLD UNDER WAY (owner report
 * from a real Epic Hydro pump, 0.10): 1:30 into a 2:00 hold, +30 s gave a fresh 2:30.
 *
 * The protocol can only START an entry, and a started entry begins its cycle from the top.
 * So the entry is written for the cycle under way with what is LEFT of the new hold; on a block
 * the pump repeats, the full hold is written again after that cycle's drop (the re-arm); in the
 * drop, the change waits for the drop to end. These pin the figures - and, through SetClock,
 * the time the run counts.
 */
class HoldCarryOnTest {

    /** The owner's case: 1:30 into a 2:00 hold, +30 s: 0:30 left, then 0:30 more - the hold
     *  ends at 2:30 of its own time, the step (one cycle, 5 s drop) at 2:35. */
    @Test
    void ninetySecondsIntoATwoMinuteHoldPlusThirtyEndsAtTwoThirty() {
        HoldCarryOn.Plan co = HoldCarryOn.plan(120, 5, 150, 5, 90_000L, false, true, 1, false);
        assertEquals(HoldCarryOn.CONTINUE, co.kind);
        assertEquals(60, co.wireUh, "0:30 that was left + 0:30 more - never a fresh 2:30");
        assertEquals(90_000L + co.wireUh * 1000L, 150_000L, "the hold ends at 2:30");
        assertEquals(30_000L, co.endMoveMs, "the end moves by exactly the 30 s added");
        assertEquals(-1L, co.rearmInMs, "one cycle: nothing to write again");
        assertFalse(co.laterToo);
        assertEquals("This hold runs 30 s longer, from where it is.",
            HoldCarryOn.plusHoldSaid(30, false, false, true));
    }

    /** A block the pump repeats as one program (4 sets of 2:00 + 5 s, the second one 1:30 in):
     *  this hold carries on to 2:30, then the full 2:30 is written again for the sets after it. */
    @Test
    void aBlockCarriesThisHoldOnAndWritesTheFullHoldAgainAfterItsDrop() {
        HoldCarryOn.Plan co = HoldCarryOn.plan(120, 5, 150, 5, 90_000L, false, false, 3, false);
        assertEquals(HoldCarryOn.CONTINUE, co.kind);
        assertEquals(60, co.wireUh);
        assertTrue(co.laterToo, "the rest of the block takes the new hold too");
        assertEquals(65_000L, co.cycleEndsInMs, "this cycle: 1:00 of hold, 5 s of drop");
        assertEquals(65_000L + HoldCarryOn.REARM_MARGIN_MS, co.rearmInMs,
            "the full hold goes back on just after this cycle's drop");
        // This set 30 s longer, and the two after it 30 s each: 1:30 in all - elapsed stays.
        assertEquals(90_000L, co.endMoveMs);
        assertEquals("This hold runs 30 s longer, from where it is, and so does each one after it.",
            HoldCarryOn.plusHoldSaid(30, false, true, false));
    }

    /** The run's own count of the block (SetClock) after the carry-on: the set under way keeps
     *  its number and its place, ends when the carried-on cycle ends, and the sets after it run
     *  at the new cycle - so the block ends exactly endMoveMs later than it would have. */
    @Test
    void theSetCountCarriesOnWithoutStartingAgain() {
        SetClock c = new SetClock();
        long t0 = 0L, cycle = 125_000L;
        c.start(3, t0, cycle, 4);
        long now = t0 + cycle + 90_000L;                     // set 2, 1:30 into its hold
        assertEquals(2, c.set(now));
        long oldEnd = t0 + 4 * cycle;
        HoldCarryOn.Plan co = HoldCarryOn.plan(120, 5, 150, 5, c.inCycleMs(now), false, false,
            c.setsLeft(now), false);
        long setEnds = now + co.cycleEndsInMs;
        c.carryOn(now, setEnds, 155_000L);
        assertEquals(2, c.set(now), "the set under way keeps its number");
        assertEquals(2, c.set(setEnds - 1), "and runs to its carried-on end");
        assertEquals(3, c.set(setEnds + 1));
        long newEnd = setEnds + 2 * 155_000L;
        assertEquals(oldEnd + co.endMoveMs, newEnd, "the block ends exactly the added time later");
        assertEquals(90_000L, co.endMoveMs, "30 s for set 2 and for each of the two after it");
    }

    /** In the drop there is no hold left to carry on, and a started entry would cut the drop:
     *  the change waits for the drop to end and goes in as the next hold. */
    @Test
    void inTheDropTheChangeWaitsForTheNextHold() {
        HoldCarryOn.Plan co = HoldCarryOn.plan(120, 5, 150, 5, 122_000L, false, false, 3, false);
        assertEquals(HoldCarryOn.AFTER_DROP, co.kind);
        assertEquals(3_000L + HoldCarryOn.REARM_MARGIN_MS, co.waitMs, "3 s of drop left");
        assertEquals("The drop is under way: the next hold is 30 s longer, and each one after it.",
            HoldCarryOn.plusHoldSaid(30, true, true, false));
        // Once the drop is over the change carries on from the (new) hold's first moments.
        HoldCarryOn.Plan next = HoldCarryOn.plan(120, 5, 150, 5, HoldCarryOn.REARM_MARGIN_MS,
            false, false, 2, false);
        assertEquals(HoldCarryOn.CONTINUE, next.kind);
        assertEquals(150, next.wireUh, "the whole new hold: nothing of it had run");
        assertEquals(-1L, next.rearmInMs, "the full hold is what was written: no re-arm");
        // The pressure showing the drop a little early is believed...
        assertEquals(HoldCarryOn.AFTER_DROP,
            HoldCarryOn.plan(120, 5, 150, 5, 118_000L, true, false, 3, false).kind);
        // ...but not a whole hold early (a stuck reading must not hold a change back).
        assertEquals(HoldCarryOn.CONTINUE,
            HoldCarryOn.plan(120, 5, 150, 5, 30_000L, true, false, 3, false).kind);
    }

    /** A one-cycle step in its drop: its hold is over and it ends with the drop - nothing to
     *  carry on, and nothing is changed; said. */
    @Test
    void aOneCycleStepInItsDropIsNotChanged() {
        HoldCarryOn.Plan co = HoldCarryOn.plan(120, 5, 150, 5, 122_000L, false, true, 1, false);
        assertEquals(HoldCarryOn.AFTER_DROP, co.kind);
        assertTrue(co.oneCycle);
        assertEquals("This step's hold is over — its drop is under way. Nothing was changed.",
            HoldCarryOn.plusHoldSaid(30, true, false, true));
        assertEquals(HoldCarryOn.ONE_CYCLE_DROP, HoldCarryOn.plusHoldSaid(30, true, false, true));
    }

    /** The strip's Hold − and Drop time ± mid-hold carry on the same way: the hold under way
     *  goes on to its new length, never less than a few seconds; a drop time takes effect at
     *  this cycle's drop. */
    @Test
    void holdMinusAndDropTimeCarryOnToo() {
        // Hold 2:00 → 1:40, 1:30 in: 0:10 left.
        HoldCarryOn.Plan minus = HoldCarryOn.plan(120, 5, 100, 5, 90_000L, false, false, 3, false);
        assertEquals(10, minus.wireUh);
        assertEquals(-20_000L * 3, minus.endMoveMs, "20 s sooner for each of the 3 sets");
        // Hold 2:00 → 1:00, 1:30 in: past the new length - a few seconds, not a repeat.
        HoldCarryOn.Plan past = HoldCarryOn.plan(120, 5, 60, 5, 90_000L, false, true, 1, false);
        assertEquals(3, past.wireUh);
        // Drop time 5 → 8 s, 1:30 into the hold: the hold's 0:30 goes on, the drop is 8 s.
        HoldCarryOn.Plan dt = HoldCarryOn.plan(120, 5, 120, 8, 90_000L, false, true, 1, false);
        assertEquals(30, dt.wireUh);
        assertEquals(3_000L, dt.endMoveMs);
        // A pull change alone: the hold under way goes on to its own end (+ under a second).
        HoldCarryOn.Plan pull = HoldCarryOn.plan(120, 5, 120, 5, 90_400L, false, false, 3, false);
        assertEquals(30, pull.wireUh);
        assertTrue(pull.endMoveMs >= 0 && pull.endMoveMs < 1000L);
        assertEquals(35_000L + HoldCarryOn.REARM_MARGIN_MS, pull.rearmInMs,
            "the block's later sets get their full hold back after this drop");
    }

    /** A ramp's step of several cycles keeps its time per step; a continuous hold keeps its end. */
    @Test
    void aRampStepOfSeveralCyclesKeepsItsTime() {
        HoldCarryOn.Plan co = HoldCarryOn.plan(38, 5, 48, 5, 20_000L, false, false, 1, true);
        assertEquals(28, co.wireUh);
        assertEquals(0L, co.endMoveMs);
        assertEquals(33_000L + HoldCarryOn.REARM_MARGIN_MS, co.rearmInMs,
            "the step's next cycle gets the full 0:48 hold");
        HoldCarryOn.Plan cont = HoldCarryOn.plan(60, 0, 90, 0, 30_000L, false, false, 1, true);
        assertEquals(60, cont.wireUh);
        assertEquals(-1L, cont.rearmInMs, "no drop: nothing to write again");
    }

    /** A change after a carried-on Resume carries on from where the hold then is. */
    @Test
    void pauseAndResumeThenACarryOn() {
        SetClock c = new SetClock();
        c.start(0, 0L, 125_000L, 4);
        c.carryOn(90_000L, 155_000L, 155_000L);          // +30 s, 1:30 into set 1
        c.pause(100_000L);
        assertEquals(1, c.set(200_000L), "paused: the set is held");
        assertEquals(100_000L, c.pausedInCycleMs(), "1:40 into its 2:30 hold when it paused");
        HoldCarryOn.Plan r = HoldCarryOn.resume(150, 5, c.pausedInCycleMs(), false, 4, false);
        c.resumeCarryOn(200_000L, 200_000L + r.cycleEndsInMs, 155_000L);
        assertEquals(1, c.set(200_000L), "the paused set carries on, its number kept");
        assertEquals(100_000L, c.inCycleMs(200_000L), "from where it paused, not from its hold");
        // 0:20 later, +30 s: 1:00 of hold left (0:30 + 0:30) - not a fresh 3:00.
        HoldCarryOn.Plan co = HoldCarryOn.plan(150, 5, 180, 5, c.inCycleMs(220_000L), false,
            false, c.setsLeft(220_000L), false);
        assertEquals(60, co.wireUh);
    }

    /* ------------------------------------------------ RESUME CARRIES THE HOLD ON (0.10) */

    /** The owner's case: paused 1:30 into a 2:00 hold for 0:20. The pump held the pressure, so
     *  Resume sends only the 0:30 that was left; the end moves by the 0:20 paused, nothing more. */
    @Test
    void pausedNinetySecondsIntoTwoMinutesForTwentyThenResumedHasThirtyLeft() {
        SetClock c = new SetClock();
        long cycle = 125_000L;
        c.start(0, 0L, cycle, 1);                        // a one-cycle step, 2:00 + 5 s
        c.pause(90_000L);
        long resumeAt = 110_000L;                        // 0:20 paused
        HoldCarryOn.Plan r = HoldCarryOn.resume(120, 5, c.pausedInCycleMs(), true, 1, false);
        assertEquals(HoldCarryOn.CONTINUE, r.kind);
        assertEquals(30, r.wireUh, "0:30 left - never a fresh 2:00");
        assertEquals(0L, r.endMoveMs, "the end moves by the paused time only (the countdown waited)");
        assertEquals(-1L, r.rearmInMs, "one cycle: nothing more to write");
        c.resumeCarryOn(resumeAt, resumeAt + r.cycleEndsInMs, cycle);
        assertEquals(90_000L, c.inCycleMs(resumeAt), "the hold goes on from 1:30");
        // The step ends 2:05 + the 0:20 paused after it began.
        assertEquals(125_000L + 20_000L, resumeAt + r.cycleEndsInMs);
        assertEquals("Resumed — the hold carries on from where it was: 0:30 left.",
            HoldCarryOn.resumeSaid(r));
    }

    /** A pause in the drop: the hold was run - Resume starts the NEXT hold as normal, never
     *  the paused set's hold again. On a one-cycle step the next hold is the next step's. */
    @Test
    void aPauseInTheDropResumesIntoTheNextHold() {
        SetClock c = new SetClock();
        c.start(0, 0L, 125_000L, 4);
        c.pause(125_000L + 122_000L);                    // set 2, 2 s into its drop
        HoldCarryOn.Plan r = HoldCarryOn.resume(120, 5, c.pausedInCycleMs(), false, 3, false);
        assertEquals(HoldCarryOn.AFTER_DROP, r.kind);
        c.resumeNext(300_000L, 125_000L);
        assertEquals(3, c.set(300_000L), "set 3 starts now - set 2's hold is not repeated");
        assertEquals(0L, c.inCycleMs(300_000L));
        assertEquals("Resumed — the drop was under way: the next hold starts now.",
            HoldCarryOn.resumeSaid(r));
        HoldCarryOn.Plan one = HoldCarryOn.resume(120, 5, 122_000L, true, 1, false);
        assertEquals(HoldCarryOn.AFTER_DROP, one.kind);
        assertEquals("Resumed — this step's hold was done; the next step follows.",
            HoldCarryOn.resumeSaid(one));
    }

    /** A block the pump repeats: Resume sends what was left of the paused hold, and the full
     *  hold goes back on after that cycle's drop - the same re-arm as +30 s hold. */
    @Test
    void aBlockResumedCarriesOnAndWritesTheFullHoldAgainAfterTheDrop() {
        SetClock c = new SetClock();
        c.start(0, 0L, 125_000L, 4);
        c.pause(125_000L + 90_000L);                     // set 2, 1:30 into its hold
        HoldCarryOn.Plan r = HoldCarryOn.resume(120, 5, c.pausedInCycleMs(), false,
            c.setsLeft(125_000L + 90_000L), false);
        assertEquals(HoldCarryOn.CONTINUE, r.kind);
        assertEquals(30, r.wireUh);
        assertEquals(35_000L + HoldCarryOn.REARM_MARGIN_MS, r.rearmInMs,
            "the routine slot's full 2:00 again after this cycle's 5 s drop");
        assertEquals(0L, r.endMoveMs, "the sets after it keep their cycle: no move");
        c.resumeCarryOn(400_000L, 400_000L + r.cycleEndsInMs, 125_000L);
        assertEquals(2, c.set(400_000L));
        assertEquals(3, c.set(400_000L + r.cycleEndsInMs + 1));
    }

    /** The warm-up's "+30 s" with an adjustment carried: the hold under way 30 s longer from
     *  where it is - never the carried hold started again; in the drop, nothing is written. */
    @Test
    void theWarmUpsPlusThirtyCarriesTheHoldOn() {
        // The prime: a 4:00 hold, 3:20 in - 0:40 left, +30 s: 1:10 written.
        HoldCarryOn.Plan prime = HoldCarryOn.plan(240, 0, 270, 0, 200_000L, false, true, 1, true);
        assertEquals(HoldCarryOn.CONTINUE, prime.kind);
        assertEquals(70, prime.wireUh, "0:40 left + 0:30 - never a fresh 4:30");
        // A warm-up ramp's step (0:30 + 5 s cycles), 0:32 in - in the drop: nothing written.
        assertEquals(HoldCarryOn.AFTER_DROP,
            HoldCarryOn.plan(30, 5, 60, 5, 32_000L, false, false, 1, true).kind);
        // ...and 0:10 in: 0:50 of hold left, the full 0:30 back after this drop.
        HoldCarryOn.Plan step = HoldCarryOn.plan(30, 5, 60, 5, 10_000L, false, false, 1, true);
        assertEquals(50, step.wireUh);
        assertEquals(0L, step.endMoveMs, "the step's +30 s already moved its end");
    }

    /** A Revert puts the plan's own figures back: the one change that starts the set again,
     *  and it says so. */
    @Test
    void aRevertSaysItIsARestart() {
        assertTrue(HoldCarryOn.REVERT_SAID.contains("starts this set again from its hold"));
    }
}
