package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * NO ANSWER IS NOT A REFUSAL: THE SCREEN MUST NEVER CLAIM A STATE THE PUMP MAY NOT BE IN, AND
 * THE PUMP MUST NEVER RUN ABOVE WHAT THE SCREEN SHOWS.
 *
 * The safety review of pump-refusal (CRITICAL): the app took a live change with no answer
 * inside 800 ms for a refusal - "still at 24" - but the real pump often carries a START out
 * without answering it (the owner's journal of 22 Sep: 343 STARTs, 319 answers; the guided
 * start's STARTs pulled the cuff with none; answers lost in bursts). And the answers are paired
 * in order, so a lost one made the NEXT START's `01` count for the older change: two quick
 * taps left the pump at 28 and the screen at 26. (HIGH) A Hold whose answer was lost held with
 * no limit: the limit was armed only on the ack.
 *
 * Here the real LiveLink, PendingChange and ReplyQueue drive the simulated pump, which now
 * loses chosen answers while still carrying the command out (SimPump#setReplyLoss) - the way
 * the run screen drives the real one. A replica of the shipped behaviour runs the same
 * sequences, so each property is known to catch what the review found.
 */
class LiveLinkTest {

    static final long WINDOW = 800, HORIZON = 5000, UNSENT = 15000;
    /** The shipped replica's tombstone age (b19576a). */
    static final long SHIPPED_TOMB = 2000;
    static final int STEP = 3;
    static final int ROUTINE_UP = 24;

    /* ============================================================ the simulated pump */

    @Test void aLostAnswerStillCarriesTheStartOut() {
        // The guided start at 15:11:29.874 and 15:29:37.875: no `2C` answer, cuff at ~17 kPa.
        SimPump p = new SimPump();
        p.setReplyLoss(new SimPump.ReplyLoss() {
            @Override public boolean lose(int op, int status) { return op == Proto.OP_START; }
        });
        for (int i = 0; i < Proto.SLOTS; i++) p.write(Proto.deleteSlot(0));
        p.write(Proto.addPreset(100, 17, 255, 16, 1));
        p.drain();
        p.write(Proto.startSlot(0));
        assertTrue(answers(p.drain(), Proto.OP_START).isEmpty(), "no answer");
        assertNotNull(p.running(), "and yet it runs");
        double kpa = 0;
        for (int i = 0; i < 60; i++) {
            p.tick(250);
            for (byte[] f : p.drain()) { Proto.Sample s = Proto.parse(f); if (s != null) kpa = s.kpa; }
        }
        assertTrue(kpa > 15.5, "the cuff pulled to " + kpa + " kPa");
    }

    @Test void theJournalsBurstsAreReplayed() {
        // 15:18:43.20 five STARTs, three answers; 15:35:56.98 two, one; 15:45:57.12 two, none.
        int[][] bursts = { { 1, 0, 1, 0, 1 }, { 0, 1 }, { 0, 0 } };
        for (int[] burst : bursts) {
            SimPump p = new SimPump();
            final ArrayDeque<Integer> plan = new ArrayDeque<Integer>();
            for (int b : burst) plan.add(b);
            p.setReplyLoss(new SimPump.ReplyLoss() {
                @Override public boolean lose(int op, int status) {
                    return op == Proto.OP_START && !plan.isEmpty() && plan.poll() == 0;
                }
            });
            for (int i = 0; i < Proto.SLOTS; i++) p.write(Proto.deleteSlot(0));
            for (int i = 0; i < burst.length; i++) p.write(Proto.addPreset(75, 20 + i, 30, 10, 5));
            p.drain();
            int expected = 0;
            for (int i = 0; i < burst.length; i++) { p.write(Proto.startSlot(i)); expected += burst[i]; }
            assertEquals(expected, answers(p.drain(), Proto.OP_START).size());
            assertEquals(20 + burst.length - 1, p.running().upperKpa, "the last START is what runs");
        }
    }

    @Test void aLateAnswerIsSentLateAndNeverAheadOfAnEarlierOne() {
        SimPump p = pumpWithTwoSlots();
        final long[] delays = { 500, 0 };
        final int[] k = { 0 };
        p.setReplyDelay(new SimPump.ReplyDelay() {
            @Override public long delayMs(int op, int status) {
                return op == Proto.OP_START ? delays[k[0]++] : 0;
            }
        });
        p.write(Proto.startSlot(0));
        p.write(Proto.startSlot(1));
        assertEquals(21, p.running().upperKpa, "both carried out at once");
        assertTrue(answers(p.drain(), Proto.OP_START).isEmpty(), "the second waits for the first");
        p.tick(480);
        assertTrue(answers(p.drain(), Proto.OP_START).isEmpty());
        p.tick(40);
        assertEquals(2, answers(p.drain(), Proto.OP_START).size(), "then both, in order");
    }

    @Test void aStartCanBeRefusedWithNothingChanged() {
        SimPump p = pumpWithTwoSlots();
        p.write(Proto.startSlot(0));
        p.drain();
        p.setRefusal(new SimPump.Refusal() {
            @Override public boolean refuse(int slot) { return slot == 1; }
        });
        p.write(Proto.startSlot(1));
        List<byte[]> a = answers(p.drain(), Proto.OP_START);
        assertEquals(1, a.size());
        assertEquals(Proto.REFUSED, a.get(0)[1] & 0xFF, "`2C FD`");
        assertEquals(20, p.running().upperKpa, "it carries on with what it ran");
    }

    @Test void aStartWrittenBehindATableRewriteReachesThePumpAfterIt() {
        SimPump p = pumpWithTwoSlots();
        p.write(Proto.startSlot(0));
        p.drain();
        p.setFrameMs(30);
        final List<Integer> ops = new ArrayList<Integer>();
        p.setOnCarriedOut(new SimPump.CarriedOut() {
            @Override public void carriedOut(byte[] f) { ops.add(f[2] & 0xFF); }
        });
        for (int i = 0; i < Proto.SLOTS; i++) p.write(Proto.deleteSlot(0));
        p.write(Proto.addPreset(75, 26, 30, 10, 5));
        p.write(Proto.startSlot(0));
        assertEquals(Proto.SLOTS + 2, p.inFlight(), "nothing carried out yet");
        assertEquals(20, p.running().upperKpa);
        p.tick(30L * (Proto.SLOTS + 1));
        assertEquals(1, p.inFlight(), "the START is still on its way");
        p.tick(30);
        assertEquals(26, p.running().upperKpa, "and lands after the table");
        assertEquals(Proto.OP_START, ops.get(ops.size() - 1).intValue());
    }

    private static SimPump pumpWithTwoSlots() {
        SimPump p = new SimPump();
        for (int i = 0; i < Proto.SLOTS; i++) p.write(Proto.deleteSlot(0));
        p.write(Proto.addPreset(75, 20, 30, 10, 5));
        p.write(Proto.addPreset(75, 21, 30, 10, 5));
        p.drain();
        return p;
    }

    /* ======================================================= LiveLink on its own */

    @Test void noAnswerIsNeverARefusal() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        int s = l.beginChange(PendingChange.EDIT, STEP, tuple(26), false, 0);
        l.startQueued("raise", s, false, false, 0);
        l.written("raise", 0);
        LiveLink.Outcome<String> o = l.timedOut("raise", STEP, WINDOW);
        assertEquals(LiveLink.CONVERGE, o.action, "unknown: write what was confirmed again");
        assertEquals(1, o.attempt);
        assertTrue(l.converging());
        assertFalse(l.mayWriteChange(WINDOW + 1), "and nothing new goes out meanwhile");
    }

    @Test void onlyAnExplicitNoIsARefusal() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        int s = l.beginChange(PendingChange.EDIT, STEP, tuple(26), false, 0);
        l.startQueued("raise", s, false, false, 0);
        l.written("raise", 0);
        LiveLink.Outcome<String> o = l.answer(false, STEP, 40);
        assertEquals(LiveLink.REFUSED, o.action);
        assertFalse(l.converging(), "the pump said no: it runs what it ran");
    }

    @Test void aConvergenceIsBoundedAndThenSaysThePumpIsNotAnswering() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        int s = l.beginChange(PendingChange.EDIT, STEP, tuple(26), false, 0);
        l.startQueued("raise", s, false, false, 0);
        l.written("raise", 0);
        LiveLink.Outcome<String> o = l.timedOut("raise", STEP, 800);
        long t = 800;
        int written = 0;
        while (o.action == LiveLink.CONVERGE) {
            String c = "converge" + (++written);
            l.startQueued(c, -1, true, false, t);
            l.written(c, t);
            t += WINDOW;
            o = l.timedOut(c, STEP, t);
        }
        assertEquals(LiveLink.MAX_CONVERGE, written, "written again at most MAX_CONVERGE times");
        assertEquals(LiveLink.GIVE_UP, o.action);
        assertTrue(l.notAnswering());
        assertFalse(l.mayWriteChange(t + 10_000), "no live change while the pump is not answering");
    }

    @Test void notAnsweringSticksUntilAStartOfAKnownSettingIsAcked() {
        // The review of refusal-2 (HIGH): ANY answer cleared "not answering" - an FD, or one
        // eaten by a tombstone - and live changes were allowed again with the pump at 26 and the
        // cells at 18 (seed 16240).
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        long t = giveUp(l);
        l.answer(true, STEP, t + 10_001);
        assertTrue(l.notAnswering(), "an answer no START can be for is not the pump answering");
        l.startQueued("next step", -1, false, false, t + 10_100);
        l.written("next step", t + 10_100);
        LiveLink.Outcome<String> o = l.answer(false, STEP, t + 10_150);
        assertTrue(l.notAnswering(), "an FD is not the pump answering what was asked");
        assertEquals(LiveLink.CONVERGE, o.action, "it is a refusal: converge");
        assertTrue(o.afterRefusal, "and write the table first");
        l.tableRewritten();
        l.startQueued("again", -1, true, false, t + 10_800);
        l.written("again", t + 10_800);
        o = l.answer(true, STEP, t + 10_850);
        assertEquals(LiveLink.CONVERGED, o.action);
        assertTrue(o.answering, "an ACK, known to be its own, of a setting the app just wrote");
        assertFalse(l.notAnswering());
        assertTrue(l.mayWriteChange(t + 10_850 + HORIZON + 1));
    }

    @Test void anAckThatMayBeAnyonesDoesNotEndNotAnswering() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        long t = giveUp(l);
        l.startQueued("step a", -1, false, false, t + 10_000);
        l.written("step a", t + 10_000);
        l.startQueued("step b", -1, false, false, t + 10_100);
        l.written("step b", t + 10_100);
        l.answer(true, STEP, t + 10_150);
        assertTrue(l.notAnswering(), "a's, or b's with a's lost: which setting runs is not known");
        l.answer(true, STEP, t + 10_200);
        assertFalse(l.notAnswering(), "then one that can only be b's");
    }

    /** Writes a change, and lets MAX_CONVERGE converging STARTs go unanswered: GIVE_UP. */
    static long giveUp(LiveLink<String> l) {
        int s = l.beginChange(PendingChange.EDIT, STEP, tuple(26), false, 0);
        l.startQueued("raise", s, false, false, 0);
        l.written("raise", 0);
        LiveLink.Outcome<String> o = l.timedOut("raise", STEP, WINDOW);
        long t = WINDOW;
        int n = 0;
        while (o.action == LiveLink.CONVERGE) {
            String c = "giveup-converge" + (++n);
            l.startQueued(c, -1, true, false, t);
            l.written(c, t);
            t += WINDOW;
            o = l.timedOut(c, STEP, t);
        }
        assertEquals(LiveLink.GIVE_UP, o.action);
        assertTrue(l.notAnswering());
        return t;
    }

    @Test void aConvergingStartThatIsAnsweredEndsIt() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        int s = l.beginChange(PendingChange.EDIT, STEP, tuple(26), false, 0);
        l.startQueued("raise", s, false, false, 0);
        l.written("raise", 0);
        l.timedOut("raise", STEP, 800);
        l.startQueued("converge", -1, true, false, 800);
        l.written("converge", 800);
        // Past the lost change's horizon: the ack can only be the converging START's.
        LiveLink.Outcome<String> o = l.answer(true, STEP, HORIZON + 500);
        assertEquals(LiveLink.CONVERGED, o.action);
        assertEquals(26, o.change.values[LiveEdit.UP], "and says which change was undone");
        assertFalse(l.converging());
        assertTrue(l.mayWriteChange(3001));
    }

    @Test void anyOtherStartEndsAConvergence() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        int s = l.beginChange(PendingChange.EDIT, STEP, tuple(26), false, 0);
        l.startQueued("raise", s, false, false, 0);
        l.written("raise", 0);
        l.timedOut("raise", STEP, 800);
        l.startQueued("next step", -1, false, false, 900);
        assertFalse(l.converging(), "what the pump runs next is that START");
    }

    @Test void aHoldMayBeUpFromItsWriteUntilThePumpIsKnownToBeOffIt() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        int s = l.beginChange(PendingChange.HOLD, STEP, null, false, 22);
        assertTrue(l.holdUnsettled(), "from the write - its limit is armed then");
        l.startQueued("hold", s, false, false, 0);
        l.written("hold", 0);
        LiveLink.Outcome<String> o = l.timedOut("hold", STEP, 800);
        assertEquals(LiveLink.CONVERGE, o.action);
        assertTrue(l.holdUnsettled(), "no answer: it may be holding");
        l.startQueued("converge", -1, true, false, 800);
        l.written("converge", 800);
        o = l.answer(true, STEP, 900);
        assertFalse(o.holdSettled, "an ack that may be the hold's own, late, settles nothing");
        assertTrue(l.holdUnsettled());
        l.startQueued("converge 2", -1, true, false, 1600);
        l.written("converge 2", 1600);
        o = l.answer(true, STEP, 1650);
        assertTrue(o.holdSettled, "one that can only be a later START's: the pump is off the hold");
        assertFalse(l.holdUnsettled());
        LiveLink<String> l2 = new LiveLink<String>(HORIZON, UNSENT, 16);
        int s2 = l2.beginChange(PendingChange.HOLD, STEP, null, false, 22);
        l2.startQueued("hold", s2, false, false, 0);
        l2.written("hold", 0);
        assertEquals(LiveLink.REFUSED, l2.answer(false, STEP, 40).action);
        assertFalse(l2.holdUnsettled(), "an explicit no: it is not up");
    }

    @Test void aNewChangeWaitsWhileAnyStartMayStillBeAnswered() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("next step", -1, false, false, 0);
        l.written("next step", 0);
        assertFalse(l.mayWriteChange(10), "its answer could be taken for the change's");
        l.answer(true, STEP, 40);
        assertTrue(l.mayWriteChange(41));
        l.startQueued("refresh", -1, false, true, 50);         // +30 s: a placeholder
        l.written("refresh", 50);
        l.timedOut("refresh", STEP, 850);
        assertFalse(l.mayWriteChange(2900), "past its window it may still be answered, late");
        assertTrue(l.mayWriteChange(50 + HORIZON + 1), "past the horizon it cannot");
    }

    @Test void anFdForOneOfSeveralStartsIsARefusalAllTheSame() {
        // The review of refusal-2 (HIGH): an FD eaten by a tombstone was ignored.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 0);
        l.written("step", 0);
        l.startQueued("next step", -1, false, false, 900);
        l.written("next step", 900);
        LiveLink.Outcome<String> o = l.answer(false, STEP, 950);
        assertNull(o.token, "which one it refused is not known");
        assertEquals(LiveLink.CONVERGE, o.action, "but something was refused: converge");
        assertTrue(o.afterRefusal, "the table first");
        assertEquals(STEP, o.forIdx);
    }

    @Test void anFdForAStepsOwnStartConvergesTableFirst() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 0);
        l.written("step", 0);
        LiveLink.Outcome<String> o = l.answer(false, STEP, 40);
        assertEquals("step", o.token);
        assertEquals(LiveLink.CONVERGE, o.action, "the pump runs the step before it");
        assertTrue(o.afterRefusal);
    }

    @Test void thePumpRefusingWhatWasConfirmedOnAFreshTableStops() {
        // A converging START refused, the table written again, and the START of what was
        // confirmed refused AGAIN: nothing the app writes is taken, and the pump runs what the
        // screen does not show. STOP - the run stops and vents through STOP's own path.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        int s = l.beginChange(PendingChange.EDIT, STEP, tuple(28), false, 0);
        l.startQueued("raise", s, false, false, 0);
        l.written("raise", 0);
        LiveLink.Outcome<String> o = l.timedOut("raise", STEP, WINDOW);
        assertFalse(o.afterRefusal, "no refusal yet: no rewrite");
        l.startQueued("c1", -1, true, false, 6000);
        l.written("c1", 6000);
        o = l.answer(false, STEP, 6040);
        assertEquals(LiveLink.CONVERGE, o.action, "refused on a table not rewritten: again");
        assertTrue(o.afterRefusal, "the table first");
        l.tableRewritten();
        l.startQueued("c2", -1, true, false, 6700);
        l.written("c2", 6700);
        o = l.answer(false, STEP, 6740);
        assertEquals(LiveLink.STOP, o.action, "refused on the table it was just given: stop");
        assertEquals(28, o.change.values[LiveEdit.UP], "and it may be running the lost change");
        assertFalse(l.converging());
    }

    @Test void aReleasedHoldMayBeUpUntilAStartAfterItIsAcknowledged() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        int s = l.beginChange(PendingChange.HOLD, STEP, null, false, 22);
        l.startQueued("hold", s, false, false, 0);
        l.written("hold", 0);
        assertEquals(LiveLink.COMMIT, l.answer(true, STEP, 40).action);
        assertFalse(l.holdUnsettled(), "HOLDING: the caller holds it");
        l.releaseHold();
        assertTrue(l.holdUnsettled(), "Resume written: the pump is not yet known to be off it");
        l.startQueued("resume", -1, false, false, 10_000);
        l.written("resume", 10_000);
        LiveLink.Outcome<String> o = l.answer(false, STEP, 10_040);
        assertTrue(l.holdUnsettled(), "an FD: still holding, the limit stays");
        assertEquals(LiveLink.CONVERGE, o.action, "and the release is written again");
        assertEquals(PendingChange.HOLD, o.change.kind);
        l.tableRewritten();
        l.startQueued("resume again", -1, true, false, 10_700);
        l.written("resume again", 10_700);
        o = l.timedOut("resume again", STEP, 11_500);
        assertTrue(l.holdUnsettled(), "no answer: still may be holding");
        assertEquals(LiveLink.CONVERGE, o.action);
        l.startQueued("resume 3", -1, true, false, 11_500);
        l.written("resume 3", 11_500);
        o = l.answer(true, STEP, 11_540);
        assertTrue(o.holdSettled, "an ack only a START off the hold can have: off it");
        assertFalse(l.holdUnsettled());
    }

    @Test void whatThePumpMayBeRunningIsTheLastStartItTookAndAllWrittenSince() {
        // The review of refusal-3 (HIGH, seed 1181 of StepFdFuzz): a step's START refused leaves
        // the step BEFORE it running, and the figure shown while not answering left it out.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step 3", -1, false, false, 27, 0);
        l.written("step 3", 0);
        l.answer(true, STEP, 40);
        assertEquals(27, l.mayBeUp(), "known to run step 3");
        l.startQueued("step 4", -1, false, false, 25, 6000);
        l.written("step 4", 6000);
        assertEquals(27, l.mayBeUp(), "step 4 unanswered: step 3 may still run");
        LiveLink.Outcome<String> o = l.answer(false, STEP + 1, 6040);
        assertEquals(LiveLink.CONVERGE, o.action, "step 4 refused: converge, table first");
        assertEquals(27, l.mayBeUp(), "and step 3 is what runs");
        l.tableRewritten();
        l.startQueued("again", -1, true, false, 25, 6700);
        l.written("again", 6700);
        assertEquals(27, l.mayBeUp());
        l.answer(true, STEP + 1, 6740);
        assertEquals(25, l.mayBeUp(), "step 4 taken on a fresh table: only step 4 runs");
    }

    @Test void aLiveChangeWhoseAnswerNeverCameIsInTheFigure() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 24, 0);
        l.written("step", 0);
        l.answer(true, STEP, 40);
        int s = l.beginChange(PendingChange.EDIT, STEP, tuple(28), false, 0);
        l.startQueued("raise", s, false, false, 28, 6000);
        l.written("raise", 6000);
        l.timedOut("raise", STEP, 6800);
        assertEquals(28, l.mayBeUp(), "the raise may be running");
        l.startQueued("c1", -1, true, false, 24, 6800);
        l.written("c1", 6800);
        assertEquals(28, l.mayBeUp());
        l.answer(true, STEP, 6850);
        assertEquals(28, l.mayBeUp(), "an ack that may be the raise's own settles nothing");
        l.startQueued("c2", -1, true, false, 24, 7600);
        l.written("c2", 7600);
        l.answer(true, STEP, 7650);
        assertEquals(24, l.mayBeUp(), "one that can only be a converging START's: 24 runs");
    }

    @Test void aStartOfAPullNotKnownNeverMovesTheFigureDown() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 30, 0);
        l.written("step", 0);
        l.answer(true, STEP, 40);
        l.startQueued("placeholder", -1, false, true, 0);        // a pull it does not know
        l.written("placeholder", 6000);
        l.answer(true, STEP, 6040);
        assertEquals(30, l.mayBeUp(), "not known to be lower: not taken as lower");
    }

    /* The review of refusal-4's mutation gaps: the table-in-doubt paths, one by one. */

    @Test void anOrphanFdConvergesTableFirst() {
        // LL11: an FD no START can be for puts the table in doubt - what is confirmed is written
        // again with the table first.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 24, 0);
        l.written("step", 0);
        l.answer(true, STEP, 40);
        LiveLink.Outcome<String> o = l.answer(false, STEP, 9000);  // nothing within the horizon
        assertEquals(LiveLink.CONVERGE, o.action);
        assertTrue(o.afterRefusal, "the table first");
    }

    @Test void aStaleStartDecidedAgainAfterARefusalIsStillTheRefusals() {
        // LL10: a convergence under way (after a refusal), its START posted behind the table and
        // an FD landing meanwhile; the START goes stale - decided again, it is table-first still.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 24, 0);
        l.written("step", 0);
        LiveLink.Outcome<String> o = l.answer(false, STEP, 40);   // refused: converge, table first
        assertEquals(LiveLink.CONVERGE, o.action);
        assertTrue(o.afterRefusal);
        l.tableRewritten();                                        // the caller writes the table...
        l.answer(false, STEP, 9000);                               // ...an orphan FD meanwhile
        l.tableRewritten();                                        // (and another table)
        o = l.reconverge(STEP);                                    // the posted START went stale
        assertEquals(LiveLink.CONVERGE, o.action);
        assertTrue(o.afterRefusal, "refused since: still table first");
    }

    @Test void aRefusalOnATableInDoubtSinceItsRewriteIsNotTheStop() {
        // LL9: the table rewritten for a refusal, then put in doubt (an orphan FD) before the
        // converging START went: its FD may be the doubt's - written again, not STOP.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 24, 0);
        l.written("step", 0);
        l.answer(false, STEP, 40);                                 // converge, table first
        l.tableRewritten();
        l.answer(false, STEP, 9000);                               // an orphan FD: in doubt
        assertTrue(l.tableSuspect());
        l.startQueued("converge", -1, true, false, 24, 9100);
        l.written("converge", 9100);
        LiveLink.Outcome<String> o = l.answer(false, STEP, 9140);
        assertFalse(o.action == LiveLink.STOP, "not fresh: the table was in doubt");
        assertEquals(LiveLink.CONVERGE, o.action);
        assertTrue(o.afterRefusal);
    }

    @Test void aStartNeverWrittenLeavesTheFigureAndOnlyIt() {
        // LL12: a START that never went out ran nothing - it leaves the figure, and the one
        // written before it, never answered, stays in it.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 20, 0);
        l.written("step", 0);
        l.answer(true, STEP, 40);
        l.startQueued("raised", -1, false, false, 30, 6000);
        l.written("raised", 6000);
        l.startQueued("dropped", -1, false, false, 24, 6100);
        l.neverWritten("dropped", STEP);
        assertEquals(30, l.mayBeUp(), "the unanswered 30 may still run");
    }

    @Test void anAckThatMayBeAnyonesOnATableInDoubtDoesNotTakeThePumpOffAHold() {
        // The ambiguous path of the one below: an ACK that may be for either of two STARTs
        // written while a Hold may be up settles it only when every one of them is known - on a
        // table an FD put in doubt, either may have hit the hold's own entry.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 24, 0);
        l.written("step", 0);
        l.answer(true, STEP, 40);
        l.startQueued("other", -1, false, false, 22, 6000);
        l.written("other", 6000);
        l.answer(false, STEP, 6040);                              // an FD: the table in doubt
        l.cancelConverge();
        l.beginChange(PendingChange.HOLD, STEP, null, false, 20);
        l.releaseHold();
        l.startQueued("a", -1, false, false, 24, 13000);
        l.written("a", 13000);
        l.startQueued("b", -1, false, false, 24, 13010);
        l.written("b", 13010);
        LiveLink.Outcome<String> o = l.answer(true, STEP, 13050);   // a's, or b's with a's lost
        assertNull(o.token, "ambiguous");
        assertFalse(o.holdSettled, "an ACK that may be anyone's, of entries in doubt");
        assertTrue(l.holdUnsettled(), "the hold may still be up");
        l.answer(true, STEP, 13060);                               // b's
        l.tableRewritten();
        l.startQueued("c", -1, false, false, 24, 20000);
        l.written("c", 20000);
        l.startQueued("d", -1, false, false, 24, 20010);
        l.written("d", 20010);
        o = l.answer(true, STEP, 20050);
        assertNull(o.token, "ambiguous again");
        assertTrue(o.holdSettled, "known entries, all of them: it is off");
        assertFalse(l.holdUnsettled());
    }

    @Test void anAckOfAnEntryInDoubtDoesNotTakeThePumpOffAHold() {
        // The review of refusal-4, LOW: a START written while a Hold may be up settled it on its
        // ACK - known or not. On a table an FD put in doubt, the START may have hit the hold's
        // own entry: the hold may still be up, and its limit stays.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 24, 0);
        l.written("step", 0);
        l.answer(true, STEP, 40);
        l.startQueued("other", -1, false, false, 22, 6000);
        l.written("other", 6000);
        l.answer(false, STEP, 6040);                              // an FD: the table in doubt
        l.cancelConverge();
        assertTrue(l.tableSuspect());
        l.beginChange(PendingChange.HOLD, STEP, null, false, 20);
        l.releaseHold();                                           // written, then released
        assertTrue(l.holdUnsettled());
        l.startQueued("release", -1, false, false, 24, 13000);
        l.written("release", 13000);
        LiveLink.Outcome<String> o = l.answer(true, STEP, 13040);
        assertFalse(o.holdSettled, "an ACK of an entry in doubt");
        assertTrue(l.holdUnsettled(), "the hold may still be up");
        l.tableRewritten();
        l.startQueued("again", -1, false, false, 24, 20000);
        l.written("again", 20000);
        o = l.answer(true, STEP, 20040);
        assertTrue(o.holdSettled, "on a table written again it is off");
        assertFalse(l.holdUnsettled());
    }

    @Test void anAckOfAnEntryInDoubtDoesNotMoveTheFigureDown() {
        // An FD put the table in doubt: the entry a later START names may hold anything, so its
        // ACK does not say what runs - the figure keeps what the pump was known to run.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 30, 0);
        l.written("step", 0);
        l.answer(true, STEP, 40);
        assertEquals(30, l.mayBeUp());
        l.startQueued("next", -1, false, false, 28, 6000);
        l.written("next", 6000);
        l.answer(false, STEP + 1, 6040);                          // refused: the table in doubt
        assertTrue(l.tableSuspect());
        l.cancelConverge();
        l.startQueued("after", -1, false, false, 22, 13000);
        l.written("after", 13000);
        l.answer(true, STEP + 1, 13040);
        assertEquals(30, l.mayBeUp(), "an ACK of an entry in doubt is not what runs");
        l.tableRewritten();
        l.startQueued("rewritten", -1, false, false, 22, 20000);
        l.written("rewritten", 20000);
        l.answer(true, STEP + 1, 20040);
        assertEquals(22, l.mayBeUp(), "on a table written again it is");
    }

    @Test void anAckOnATableInDoubtDoesNotEndNotAnswering() {
        // The review of refusal-3's M1: an ACK of a START on a table an FD put in doubt ended
        // "not answering" - its entry may hold something other than the app believes.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        long t = giveUp(l);
        l.startQueued("step", -1, false, false, 24, t + 6000);
        l.written("step", t + 6000);
        LiveLink.Outcome<String> o = l.answer(false, STEP, t + 6040);   // an FD: table in doubt
        assertTrue(l.tableSuspect());
        l.cancelConverge();
        l.startQueued("next step", -1, false, false, 22, t + 13000);
        l.written("next step", t + 13000);
        l.answer(true, STEP + 1, t + 13040);
        assertTrue(l.notAnswering(), "not a setting the app knows");
        l.tableRewritten();
        l.startQueued("rewritten", -1, false, false, 22, t + 20000);
        l.written("rewritten", t + 20000);
        o = l.answer(true, STEP + 1, t + 20040);
        assertTrue(o.answering, "the table written again: now it is");
        assertFalse(l.notAnswering());
    }

    @Test void anFdOnAConvergenceAlreadyOverStillCounts() {
        // The review of refusal-3 (LOW): the table goes in doubt and what runs is written again.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        int s = l.beginChange(PendingChange.EDIT, STEP, tuple(28), false, 0);
        l.startQueued("raise", s, false, false, 28, 0);
        l.written("raise", 0);
        l.timedOut("raise", STEP, WINDOW);
        l.startQueued("c1", -1, true, false, 24, 6000);
        l.written("c1", 6000);
        l.startQueued("c2", -1, true, false, 24, 6100);
        l.written("c2", 6100);
        assertEquals(LiveLink.CONVERGED, l.answer(true, STEP, 6150).action);
        LiveLink.Outcome<String> o = l.answer(false, STEP, 6200);   // c2's own FD, after it
        assertTrue(l.tableSuspect(), "a refusal wherever it lands");
        assertEquals(LiveLink.CONVERGE, o.action);
        assertTrue(o.afterRefusal);
    }

    @Test void twoFdsOnFreshTablesStopEvenWhenNeitherIsCertain() {
        // The review of refusal-3 (HIGH): after a lost answer in the last 5 s every FD may be
        // anyone's, and a fresh table refused twice ended in GIVE_UP, not STOP.
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        l.startQueued("step", -1, false, false, 24, 0);
        l.written("step", 0);
        LiveLink.Outcome<String> o = l.answer(false, STEP, 100);
        assertEquals(LiveLink.CONVERGE, o.action);
        l.tableRewritten();
        l.startQueued("c1", -1, true, false, 24, 700);
        l.written("c1", 700);
        o = l.answer(false, STEP, 740);
        assertEquals(LiveLink.STOP, o.action, "certain: the confirmed setting, fresh, refused");
        // And the ambiguous case: an answer lost just before.
        LiveLink<String> m = new LiveLink<String>(HORIZON, UNSENT, 16);
        m.startQueued("lost", -1, false, true, 24, 0);               // its answer is lost
        m.written("lost", 0);
        m.startQueued("step", -1, false, false, 24, 100);
        m.written("step", 100);
        o = m.answer(false, STEP, 150);                               // "lost"'s or the step's
        assertEquals(LiveLink.CONVERGE, o.action, "a refusal all the same: converge");
        assertTrue(o.afterRefusal);
        m.tableRewritten();
        m.startQueued("c1", -1, true, false, 24, 700);
        m.written("c1", 700);
        o = m.answer(false, STEP, 740);                               // the step's or c1's
        assertEquals(LiveLink.NONE, o.action, "may be the step's own, late: not yet");
        o = m.timedOut("c1", STEP, 1500);
        assertEquals(LiveLink.CONVERGE, o.action);
        assertTrue(o.afterRefusal, "table first");
        m.tableRewritten();
        m.startQueued("c2", -1, true, false, 24, 1500);
        m.written("c2", 1500);
        o = m.answer(false, STEP, 1540);                              // c1's or c2's: both fresh
        assertEquals(LiveLink.STOP, o.action, "every one it may be is the confirmed, fresh");
    }

    @Test void aStaleConvergingStartIsWrittenAgainNotDropped() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        int s = l.beginChange(PendingChange.EDIT, STEP, tuple(30), false, 0);
        l.startQueued("raise", s, false, false, 30, 0);
        l.written("raise", 0);
        LiveLink.Outcome<String> o = l.timedOut("raise", STEP, WINDOW);
        assertEquals(LiveLink.CONVERGE, o.action);
        o = l.reconverge(STEP);
        assertEquals(LiveLink.CONVERGE, o.action, "the attempt under way, again");
        assertEquals(1, o.attempt);
        assertTrue(l.converging());
        l.cancelConverge();
        o = l.reconverge(STEP + 1);
        assertEquals(LiveLink.CONVERGE, o.action, "the step moved on: converge for this one");
        assertEquals(STEP + 1, o.forIdx);
    }

    /* =============================================== the sequences, on the simulated pump */

    @Test void aChangeWhoseAnswerIsLostConvergesOnWhatWasConfirmed() {
        for (Rig r : both()) {
            r.loseNextStartAnswers(1);
            r.tapTo(26);
            r.run(4000);
            if (r.shipped) {
                assertEquals(26, r.pumpUp(), "shipped: the pump carried it out");
                assertEquals(ROUTINE_UP, r.screenUp(), "shipped: the screen said still at 24");
            } else {
                r.assertAgree("a lost answer converges");
                assertEquals(ROUTINE_UP, r.pumpUp(), "undone on the pump too");
            }
        }
    }

    @Test void theReviewersTwoTapsNeverCreditAnAnswerToTheWrongChange() {
        // Tap + to 26: carried out, its `2C 01` lost. Tap + again, to 28, within 2 s: with the
        // shipped pairing the 28's `01` was taken for the 26 (its tombstone), the 28 timed out
        // and was undone - the pump at 28, the screen at 26.
        for (Rig r : both()) {
            r.loseNextStartAnswers(1);
            r.tapTo(26);
            r.run(1200);
            r.tapTo(28);
            r.run(6000);
            if (r.shipped) {
                assertEquals(28, r.pumpUp());
                assertEquals(26, r.screenUp(), "shipped: pump 28, app 26");
            } else {
                r.assertAgree("two taps, the first answer lost");
            }
        }
    }

    @Test void aRampShiftThenAQuickSecondTapWithItsAnswerLost() {
        // The review's sequence: + acked (the "This set" shift rewrites the table - no START),
        // then + again at once, its answer lost. Shipped: "still at 26" with the pump at 28.
        for (Rig r : both()) {
            r.tapTo(26);
            r.run(60);
            r.rewriteTable();
            r.loseNextStartAnswers(1);
            r.tapTo(28);
            r.run(5000);
            if (r.shipped) {
                assertEquals(28, r.pumpUp());
                assertEquals(26, r.screenUp());
            } else {
                r.assertAgree("the second tap's answer lost");
            }
        }
    }

    @Test void aHoldWhoseAnswerIsLostIsBoundedFromItsWrite() {
        for (Rig r : both()) {
            r.loseNextStartAnswers(1);
            r.hold(22);
            r.run(300);
            assertEquals(22, r.pumpUp(), "the pump holds");
            if (r.shipped) {
                assertFalse(r.holding || r.limitArmed, "shipped: holding with no limit");
                r.run(3000);
                assertEquals(22, r.pumpUp(), "shipped: still holding, still no limit");
                assertFalse(r.limitArmed);
            } else {
                assertTrue(r.limitArmed, "armed when it was written");
                r.run(4000);
                r.assertAgree("a Hold whose answer was lost");
                assertFalse(r.limitArmed, "and disarmed only once the pump is known to be off it");
            }
        }
    }

    @Test void aResumeTheLimitStaysArmedUntilThePumpTakesIt() {
        // The review of refusal-2 (MEDIUM): the limit was cleared when Resume's START was WRITTEN,
        // and an FD to it was only logged - the pump went on holding with no limit.
        for (boolean asShipped : new boolean[]{ true, false }) {
            Rig r = new Rig(false);
            r.clearOnRelease = asShipped;
            r.hold(22);
            r.run(300);
            assertTrue(r.holding);
            final boolean[] once = { true };
            r.pump.setRefusal(new SimPump.Refusal() {
                @Override public boolean refuse(int slot) {
                    boolean no = once[0] && slot == 0;
                    once[0] = false;
                    return no;
                }
            });
            r.release();
            assertEquals(22, r.pumpUp(), "Resume refused: the pump holds on");
            if (asShipped) {
                assertNotNull(r.holdBreach(), "6bcae1d: from the write, it holds with no limit");
                continue;
            }
            assertNull(r.holdBreach(), "the limit is still armed");
            r.run(3000);
            assertEquals(ROUTINE_UP, r.pumpUp(), "the release, written again, is taken");
            assertFalse(r.limitArmed, "and only now does the limit go");
        }
    }

    @Test void aHoldWhoseConvergenceIsNeverAnsweredKeepsItsLimit() {
        Rig r = new Rig(false);
        r.loseNextStartAnswers(100);
        r.hold(22);
        r.run(8000);
        assertTrue(r.gaveUp(), "the pump is not answering");
        assertTrue(r.limitArmed, "whatever it is running, a Hold that may be up stays bounded");
    }

    /**
     * THE PROPERTY, OVER THE JOURNAL'S BURSTS: taps, Holds and the steps' own STARTs, with
     * answers lost in bursts. Whenever the run is quiet (nothing outstanding, no convergence
     * under way), the pull the pump runs is the one the screen shows - or, when the pump
     * stopped answering, no higher than the "may be at" figure the screen gives. And a Hold
     * the pump is holding always has its limit armed.
     */
    @Test void theJournalsBurstsNeverLeaveThePumpAboveTheScreen() {
        for (long seed = 1; seed <= 400; seed++) {
            String breach = breach(new Rig(false), seed);
            assertNull(breach, "seed " + seed + ": " + breach);
        }
        String shippedBreach = null;
        for (long seed = 1; seed <= 400 && shippedBreach == null; seed++)
            shippedBreach = breach(new Rig(true), seed);
        assertNotNull(shippedBreach, "the property must catch the shipped pairing");
    }

    private static String breach(Rig r, long seed) {
        Random rnd = new Random(seed);
        for (int op = 0; op < 40; op++) {
            int k = rnd.nextInt(10);
            if (k < 3) r.loseNextStartAnswers(1 + rnd.nextInt(2));      // a burst
            if (k < 5) r.tapTo(20 + rnd.nextInt(12));
            else if (k == 5 && !r.holding) r.hold(18 + rnd.nextInt(8));
            else if (k == 6) r.release();
            else if (k == 7) r.nextStep(20 + rnd.nextInt(10));
            r.run(50 + rnd.nextInt(1500));
            String b = r.holdBreach();
            if (b != null) return "op " + op + ": " + b;
            if (r.quiet()) {
                String a = r.agreeBreach();
                if (a != null) return "op " + op + ": " + a;
            }
        }
        r.run(12000);
        String a = r.quiet() ? r.agreeBreach() : null;
        return a == null ? r.holdBreach() : a;
    }

    /* ================================================================ the rig */

    static int[] tuple(int up) {
        int[] t = new int[LiveEdit.FIELDS];
        t[LiveEdit.UP] = up; t[LiveEdit.LO] = 10; t[LiveEdit.UH] = 30; t[LiveEdit.LH] = 5;
        t[LiveEdit.SP] = 75;
        return t;
    }

    static List<byte[]> answers(List<byte[]> frames, int op) {
        List<byte[]> out = new ArrayList<byte[]>();
        for (byte[] f : frames) if (f.length == 2 && (f[0] & 0xFF) == op) out.add(f);
        return out;
    }

    static Rig[] both() { return new Rig[]{ new Rig(false), new Rig(true) }; }

    /** One START the run wrote: its note. */
    static final class N {
        long sentAt = -1;
        boolean answered, timedOut;
        // the shipped replica's own bookkeeping
        int change = -1, value, kind;
        boolean tomb;
        long tombAt;
    }

    /**
     * The run screen's side, in the fewest lines that do what it does: the table has the
     * routine's step in slot 0 and the override entry in slot 1; a change or a Hold replaces the
     * override entry and STARTs it; what was confirmed is written again to converge; the next
     * step STARTs slot 0 afresh. `shipped` replays the pairing and the timeout of b19576a
     * instead of LiveLink.
     */
    static final class Rig {
        final boolean shipped;
        final SimPump pump = new SimPump();
        final LiveLink<N> link = new LiveLink<N>(HORIZON, UNSENT, 16);
        final List<N> notes = new ArrayList<N>();
        final ArrayDeque<N> shippedQ = new ArrayDeque<N>();
        long now = 0;
        int lose = 0;
        int routineUp = ROUTINE_UP;
        boolean carry, overrideInTable;
        int confirmed = ROUTINE_UP;
        boolean holding, limitArmed;
        int holdAt, lostUp;
        int pendingTap = -1;            // a tap held back until the pump can be asked
        int pendingHold = -1;

        Rig(boolean shipped) {
            this.shipped = shipped;
            pump.setReplyLoss(new SimPump.ReplyLoss() {
                @Override public boolean lose(int op, int status) {
                    if (op != Proto.OP_START || lose <= 0) return false;
                    lose--;
                    return true;
                }
            });
            for (int i = 0; i < Proto.SLOTS; i++) pump.write(Proto.deleteSlot(0));
            pump.write(Proto.addPreset(75, routineUp, 30, 10, 5));
            pump.write(Proto.startSlot(0));
            pump.drain();
        }

        void loseNextStartAnswers(int n) { lose += n; }

        int pumpUp() { return pump.running() == null ? 0 : pump.running().upperKpa; }

        int screenUp() { return holding ? holdAt : confirmed; }

        /** Has the pump stopped answering (LiveLink's GIVE_UP, no answer since)? */
        boolean gaveUp() { return !shipped && link.notAnswering(); }

        /** Is there nothing on its way that the screen shows as "sending"? */
        boolean quiet() {
            if (shipped) return true;
            return pendingTap < 0 && pendingHold < 0 && (link.mayWriteChange(now) || gaveUp());
        }

        String agreeBreach() {
            if (gaveUp()) return pumpUp() > Math.max(screenUp(), lostUp)
                ? "the pump runs " + pumpUp() + ", above the " + Math.max(screenUp(), lostUp)
                  + " the screen says it may be at" : null;
            return pumpUp() != screenUp()
                ? "the pump runs " + pumpUp() + ", the screen shows " + screenUp() : null;
        }

        String holdBreach() {
            SimPump.Slot s = pump.running();
            boolean holdOnPump = s != null && s.upperHoldS == 255;
            return holdOnPump && !holding && !limitArmed
                ? "the pump holds at " + s.upperKpa + " with no limit armed" : null;
        }

        void assertAgree(String what) { assertNull(agreeBreach(), what); }

        /* ---- the person */

        void tapTo(int up) {
            if (holding) return;                       // the screen refuses over a Hold
            if (gaveUp()) return;                      // ...and while the pump is not answering
            if (!shipped && !link.mayWriteChange(now)) { pendingTap = up; return; }
            writeChange(up);
        }

        void hold(int at) {
            if (holding) return;
            if (!shipped && (link.holdUnsettled() || !link.mayWriteChange(now))) {
                pendingHold = at; return;
            }
            writeHold(at);
        }

        void release() {
            if (!holding) return;
            holding = false;
            if (shipped || clearOnRelease) limitArmed = false;   // 6bcae1d: cleared on the write
            else link.releaseHold();       // bounded until the pump is known to be off it
            writeConfirmed(false);
        }

        /** Replays 6bcae1d's exitHold: the limit cleared when Resume's START was written. */
        boolean clearOnRelease;

        void nextStep(int up) {
            // The routine moves on: a new step, whatever was carried or held stands down.
            pendingTap = -1; pendingHold = -1;
            holding = false; limitArmed = false; carry = false;
            routineUp = up; confirmed = up;
            for (int i = 0; i < Proto.SLOTS; i++) pump.write(Proto.deleteSlot(0));
            pump.write(Proto.addPreset(75, up, 30, 10, 5));
            overrideInTable = false;
            start(0, -1, false);
            if (!shipped) link.cancelConverge();
        }

        void rewriteTable() {
            // "This set" shifts the ramp: the whole table is rewritten, no START.
            for (int i = 0; i < Proto.SLOTS; i++) pump.write(Proto.deleteSlot(0));
            pump.write(Proto.addPreset(75, routineUp, 30, 10, 5));
            overrideInTable = false;
        }

        /* ---- the screen's writes */

        void writeChange(int up) {
            int seq = shipped ? -1 : link.beginChange(PendingChange.EDIT, STEP, tuple(up), false, 0);
            writeOverride(up, up - 5, 30);
            N n = start(1, seq, false);
            n.change = 1; n.value = up; n.kind = PendingChange.EDIT;
        }

        void writeHold(int at) {
            int seq = shipped ? -1 : link.beginChange(PendingChange.HOLD, STEP, null, false, at);
            if (!shipped) limitArmed = true;           // bounded from the write
            holdAt = at;
            writeOverride(at, at - 1, 255);
            N n = start(1, seq, false);
            n.change = 1; n.value = at; n.kind = PendingChange.HOLD;
        }

        void writeConfirmed(boolean converge) {
            if (carry) {
                writeOverride(confirmed, confirmed - 5, 30);
                start(1, -1, converge);
            } else {
                start(0, -1, converge);
            }
        }

        void writeOverride(int up, int lo, int uh) {
            if (overrideInTable) pump.write(Proto.deleteSlot(1));
            pump.write(Proto.addPreset(75, up, uh, lo, 5));
            overrideInTable = true;
        }

        N start(int slot, int changeSeq, boolean converge) {
            N n = new N();
            if (!shipped) link.startQueued(n, changeSeq, converge, false, now);
            else shippedQ.addLast(n);
            pump.write(Proto.startSlot(slot));
            n.sentAt = now;
            if (!shipped) link.written(n, now);
            notes.add(n);
            return n;
        }

        /* ---- time, answers and windows */

        void run(long ms) {
            long end = now + ms;
            while (now < end) {
                now += 20;
                pump.tick(20);
                for (byte[] f : pump.drain())
                    if (f.length == 2 && (f[0] & 0xFF) == Proto.OP_START) answer(f[1] == 0x01);
                for (int i = 0; i < notes.size(); i++) {
                    N n = notes.get(i);
                    if (n.answered || n.timedOut || now - n.sentAt < WINDOW) continue;
                    n.timedOut = true;
                    timeout(n);
                }
                if (!shipped && pendingHold >= 0 && !link.holdUnsettled() && link.mayWriteChange(now)) {
                    int at = pendingHold; pendingHold = -1; writeHold(at);
                }
                if (!shipped && pendingTap >= 0 && !holding && link.mayWriteChange(now)) {
                    int up = pendingTap; pendingTap = -1; writeChange(up);
                }
            }
        }

        void answer(boolean acked) {
            if (shipped) { shippedAnswer(acked); return; }
            LiveLink.Outcome<N> o = link.answer(acked, STEP, now);
            if (o.token != null) o.token.answered = true;
            act(o);
        }

        void timeout(N n) {
            if (shipped) { shippedTimeout(n); return; }
            act(link.timedOut(n, STEP, now));
        }

        void act(LiveLink.Outcome<N> o) {
            if (o.holdSettled && !holding) limitArmed = false;
            switch (o.action) {
                case LiveLink.COMMIT:
                    if (o.change.kind == PendingChange.HOLD) { holding = true; holdAt = o.change.holdAt; }
                    else { confirmed = o.change.values[LiveEdit.UP]; carry = true; }
                    break;
                case LiveLink.REFUSED:
                case LiveLink.NOT_SENT:
                    if (o.change.kind == PendingChange.HOLD && !holding) limitArmed = false;
                    break;
                case LiveLink.CONVERGE:
                    if (o.change != null)
                        lostUp = Math.max(lostUp, o.change.kind == PendingChange.HOLD
                            ? o.change.holdAt : o.change.values[LiveEdit.UP]);
                    writeConfirmed(true);
                    break;
                case LiveLink.GIVE_UP:
                    pendingTap = -1;                   // said: nothing more is sent
                    break;
                default:
            }
        }

        /* ---- b19576a: FIFO notes, a timed-out note a tombstone for 2 s that commits a late
         *      ack; a timeout undoes; the Hold's limit armed on its ack; nothing held back. */

        void shippedAnswer(boolean acked) {
            N n = shippedQ.pollFirst();
            while (n != null && n.tomb && now - n.tombAt > SHIPPED_TOMB) n = shippedQ.pollFirst();
            if (n == null) return;
            n.answered = true;
            if (n.tomb) {                                 // "the pump took it after all"
                if (acked && n.change > 0) shippedCommit(n);
                return;
            }
            if (acked && n.change > 0) shippedCommit(n);
        }

        void shippedCommit(N n) {
            if (n.kind == PendingChange.HOLD) { holding = true; holdAt = n.value; limitArmed = true; }
            else { confirmed = n.value; carry = true; }
        }

        void shippedTimeout(N n) {
            n.tomb = true; n.tombAt = now;                // "didn't answer - still at X"
        }
    }

    @Test void theRigsShippedReplicaIsTheReviewersReproduction() {
        // The same numbers as scratchpad/refusal-rev/lost/LostReply.java.
        Rig r = new Rig(true);
        r.loseNextStartAnswers(1);
        r.tapTo(26);
        r.run(1200);
        r.tapTo(28);
        r.run(1000);
        assertEquals(28, r.pumpUp());
        assertNotEquals(r.pumpUp(), r.screenUp());
    }
}
