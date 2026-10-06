package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * A LIVE CHANGE IS NOT IN FORCE UNTIL THE PUMP ACKNOWLEDGES ITS START.
 *
 * The owner raised the pull on a warm-up step. The screen took it, the pump answered `2C FD`, a
 * refusal, and the cuff stayed at 24 kPa. The app had already saved the raise as in force. The
 * at-pressure clock then paused against a line the pump was never given. A "This set" raise was
 * written into the ramp's remaining steps before any reply, so four refused taps put 8 kPa on
 * step 4, and the cuff went from 24 to 36.4 kPa (the owner's journal of 22 Sep).
 *
 * PendingChange holds a change - an adjustment or a Hold - from the moment it is written until
 * the pump answers. Only an ack puts it in force, and only on the step it was made on. A
 * refusal, or no answer, hands it back so the caller can undo the screen and say so. A START
 * that is not a live change's overtakes whatever is still waiting.
 */
class PendingChangeTest {

    private static final int STEP = 3;
    private static final int[] RAISE = { 26, 19, 30, 5, 70 };

    private static int[] pull(int up) { return new int[]{ up, 5, 30, 5, 70 }; }

    @Test void anAckPutsTheChangeInForceForItsStep() {
        PendingChange pc = new PendingChange();
        int seq = pc.begin(PendingChange.EDIT, STEP, RAISE, false, 0);
        assertTrue(pc.pending(PendingChange.EDIT));
        PendingChange.Change c = pc.acked(seq, STEP);
        assertNotNull(c, "acknowledged on the step it was made on: in force");
        assertEquals(PendingChange.TAKEN, pc.why());
        assertEquals(PendingChange.EDIT, c.kind);
        assertArrayEquals(RAISE, c.values);
        assertFalse(pc.pending(PendingChange.EDIT), "and nothing is pending any more");
        assertNull(pc.acked(seq, STEP), "a second ack for it changes nothing");
    }

    @Test void aRefusalOrNoAnswerHandsItBackAndNothingIsInForce() {
        PendingChange pc = new PendingChange();
        int seq = pc.begin(PendingChange.EDIT, STEP, RAISE, false, 0);
        PendingChange.Change c = pc.failed(seq);
        assertNotNull(c, "the caller undoes the screen and says so");
        assertArrayEquals(RAISE, c.values);
        assertNull(pc.acked(seq, STEP), "a late ack after the refusal puts nothing in force");
        int hold = pc.begin(PendingChange.HOLD, STEP, null, false, 22);
        assertTrue(pc.pending(PendingChange.HOLD), "a Hold waits for its answer as well");
        assertEquals(22, pc.failed(hold).holdAt, "a refused Hold says at what it was asked");
        assertFalse(pc.pending(PendingChange.HOLD));
    }

    @Test void anAckAfterItsStepEndedPutsNothingInForce() {
        // The pump was told the next step's START after the change's START; the ack arriving
        // then is for a change the pump has already left behind.
        PendingChange pc = new PendingChange();
        int seq = pc.begin(PendingChange.EDIT, STEP, RAISE, false, 0);
        pc.overtaken();                                      // the next step's START
        assertNull(pc.acked(seq, STEP + 1));
        assertEquals(PendingChange.LATE, pc.why(), "and it says the change came too late for its step");
        assertFalse(pc.pending(PendingChange.EDIT));
    }

    @Test void changesAreAnsweredInOrderAndEachAckPutsItsOwnInForce() {
        // Two raises written inside one ack window. The pump takes the first and refuses the
        // second: it runs the first, and so does the app.
        PendingChange pc = new PendingChange();
        int a = pc.begin(PendingChange.EDIT, STEP, pull(26), false, 0);
        int b = pc.begin(PendingChange.EDIT, STEP, pull(28), false, 0);
        PendingChange.Change first = pc.acked(a, STEP);
        assertNotNull(first, "the pump runs the first until the second is taken");
        assertEquals(26, first.values[0]);
        assertTrue(first.superseded(), "a newer change was written after it");
        PendingChange.Change second = pc.failed(b);
        assertNotNull(second);
        assertFalse(second.superseded(), "the newest: its refusal is what the person hears");
    }

    @Test void aStartThatIsNotALiveChangesOvertakesWhatIsWaiting() {
        // A revert, the next step, +30 s's refresh: whatever that START asked for is what the
        // pump runs, so a change written before it must not go in force after it.
        PendingChange pc = new PendingChange();
        int seq = pc.begin(PendingChange.EDIT, STEP, RAISE, false, 0);
        int hold = pc.begin(PendingChange.HOLD, STEP, null, false, 22);
        pc.overtaken();
        assertFalse(pc.pending(PendingChange.HOLD), "an overtaken Hold is not on its way");
        assertNull(pc.acked(seq, STEP));
        assertEquals(PendingChange.OVERTAKEN, pc.why());
        assertNull(pc.failed(hold), "and its refusal has nothing to undo");
        int next = pc.begin(PendingChange.EDIT, STEP, RAISE, false, 0);
        assertNotNull(pc.acked(next, STEP), "a change written after it is answered as usual");
    }

    @Test void anOlderChangeStillListedIsDroppedByANewerAnswer() {
        PendingChange pc = new PendingChange();
        int a = pc.begin(PendingChange.EDIT, STEP, pull(26), false, 0);
        int b = pc.begin(PendingChange.EDIT, STEP, pull(28), false, 0);
        assertNotNull(pc.acked(b, STEP));
        assertNull(pc.acked(a, STEP), "its answer can no longer come before the newer one's");
        assertNull(pc.failed(a));
    }

    @Test void noAnswerIsUnknownNotARefusalAndALateAnswerPutsNothingInForce() {
        // The safety review of pump-refusal: the real pump often carries a START out without
        // answering it. No answer is not "not taken" - the caller converges (LiveLink) - and
        // it is not "taken" either when an answer turns up later: by then the convergence has
        // overtaken it, and the answer may be the NEXT START's.
        PendingChange pc = new PendingChange();
        int seq = pc.begin(PendingChange.EDIT, STEP, RAISE, false, 0);
        PendingChange.Change c = pc.unanswered(seq);
        assertNotNull(c, "handed back, for the caller to converge");
        assertFalse(pc.pending(PendingChange.EDIT));
        assertNull(pc.acked(seq, STEP), "a late ack puts nothing in force");
        assertNull(pc.failed(seq), "and a late refusal undoes nothing twice");
    }

    @Test void anUnansweredChangeAlreadyOvertakenNeedsNoConvergence() {
        PendingChange pc = new PendingChange();
        int seq = pc.begin(PendingChange.HOLD, STEP, null, false, 22);
        pc.overtaken();                              // the next step's START went out after it
        assertNull(pc.unanswered(seq), "what overtook it is what the pump runs");
    }

    @Test void cancelDropsWhatIsPending() {
        PendingChange pc = new PendingChange();
        int seq = pc.begin(PendingChange.HOLD, STEP, null, false, 22);
        pc.cancel();
        assertFalse(pc.pending(PendingChange.HOLD));
        assertNull(pc.acked(seq, STEP));
        assertEquals(PendingChange.UNKNOWN, pc.why());
        assertNull(pc.failed(seq));
    }

    @Test void theScopeIsTheOneTheChangeWasMadeIn() {
        PendingChange pc = new PendingChange();
        int seq = pc.begin(PendingChange.EDIT, STEP, RAISE, true, 0);
        assertTrue(pc.acked(seq, STEP).scopeRep, "Rep scope, as chosen when it was sent");
    }

    /**
     * THE PROPERTY: once every START written has been answered, what the app counts as in force
     * is what the pump runs. The pump runs what its last ACKNOWLEDGED START asked for; a refused
     * START changes nothing. Other STARTs (a revert, the next step) are always taken here - the
     * app records them as it writes them, as it always has.
     *
     * Recording a change as it is written, the old way, fails this at once: the last change
     * refused leaves the app counting a value the pump never ran.
     */
    @Test void onceEverythingIsAnsweredTheAppCountsWhatThePumpRuns() {
        Random r = new Random(143);
        for (int trial = 0; trial < 3000; trial++) {
            PendingChange pc = new PendingChange();
            int pump = 20, app = 20;
            ArrayDeque<int[]> wire = new ArrayDeque<int[]>();   // {change seq or -1, pull}
            for (int k = 0; k < 40; k++) {
                int kind = r.nextInt(5);
                if (kind == 0 && wire.size() < PendingChange.MAX_IN_FLIGHT) {
                    int v = 10 + r.nextInt(30);
                    wire.addLast(new int[]{ pc.begin(PendingChange.EDIT, STEP, pull(v), false, 0), v });
                } else if (kind == 1 && wire.size() < PendingChange.MAX_IN_FLIGHT) {
                    int v = 10 + r.nextInt(30);
                    pc.overtaken();
                    app = v;
                    wire.addLast(new int[]{ -1, v });
                } else if (!wire.isEmpty()) {
                    int[] w = wire.pollFirst();
                    boolean taken = w[0] < 0 || kind != 4;       // kind 4 refuses a change
                    if (taken) {
                        pump = w[1];
                        if (w[0] >= 0) {
                            PendingChange.Change c = pc.acked(w[0], STEP);
                            if (c != null) app = c.values[0];
                        }
                    } else {
                        pc.failed(w[0]);
                    }
                }
                if (wire.isEmpty())
                    assertEquals(pump, app, "trial " + trial + " at " + k
                        + ": everything answered, and the app counts a pull the pump is not running");
            }
        }
    }
}
