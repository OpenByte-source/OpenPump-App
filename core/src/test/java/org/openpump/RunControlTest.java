package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * THE REVIEWS' SEQUENCES, ONE BY ONE, ON THE RUN SCREEN'S OWN DECISIONS (RunControl, via RunRig
 * on SimPump). Each names the finding it replays; the fuzz (RunControlFuzzTest) looks for the
 * ones nobody has thought of.
 */
class RunControlTest {

    /** Fates by START: the n-th START of a kind gets the fate given for it, every other one is
     *  carried out and answered in 40 ms. */
    static final class Script implements RunRig.Fates {
        final Map<String, int[]> byKey = new HashMap<String, int[]>();
        final Map<String, Integer> seen = new HashMap<String, Integer>();
        Script set(String kind, int nth, int executes, int answered, int delay) {
            byKey.put(kind + "#" + nth, new int[]{ executes, answered, delay });
            return this;
        }
        @Override public int[] next(RunRig.Note n) {
            Integer c = seen.get(n.kind);
            int k = c == null ? 1 : c + 1;
            seen.put(n.kind, k);
            int[] f = byKey.get(n.kind + "#" + k);
            return f != null ? f : new int[]{ 1, 1, 40 };
        }
    }

    static final int[] LOST = { 1, 0, 0 }, FD = { 0, 1, 40 };

    /** Steps of the given pulls, each `durMs` long; 0 is a rest. */
    static List<RunRig.Step> steps(long durMs, int... ups) {
        List<RunRig.Step> s = new ArrayList<RunRig.Step>();
        for (int up : ups) s.add(new RunRig.Step(up, up == 0, durMs));
        return s;
    }

    static void assertAgree(RunRig r, String what) {
        assertNull(r.breach, what + ": " + r.breach + "\n" + r.log);
        assertEquals(r.screenUp(), r.pumpUp(), what + ": the pump runs what the screen shows\n" + r.log);
    }

    /* ========================================= the review of refusal-3, CRITICAL */

    /** Step N out of the table (an offset rewrote it from N+1), a raise to 30 whose `01` is
     *  lost, the convergence posting its START 600 ms behind a rewrite from N - and a second
     *  table write inside those 600 ms. Rev3.java: pump 30, screen 24, quiet, until the step
     *  ended. */
    private static RunRig rev3F1(boolean ungated) {
        Script f = new Script().set("change", 1, 1, 0, 0);
        RunRig r = new RunRig(steps(60_000, 24, 22, 20, 18, 16), f);
        r.ungatedEdits = ungated;
        r.run(2000);
        r.editTable(new Random(1), true);                  // the offset: step 0 leaves the table
        assertEquals(1, r.editsDone);
        r.run(6000);
        r.tap(30);                                         // taken, its 01 lost
        r.run(900);                                        // the window: converge, rewrite, post
        assertTrue(r.ctl.startWaiting(), "the converging START waits behind the table\n" + r.log);
        r.run(300);
        r.editTable(new Random(2), true);                  // inside the settle
        r.run(10_000);
        return r;
    }

    @Test void anEditInsideTheSettleWindowIsHeldBack() {
        RunRig r = rev3F1(false);
        assertEquals(1, r.editsRefused, "the second tap waits (invariant 155)\n" + r.log);
        assertAgree(r, "the edit held back");
    }

    @Test void aTableWrittenUnderAWaitingStartDoesNotStrandIt() {
        // The gate held open: the stale START is written again for this table, not dropped.
        RunRig r = rev3F1(true);
        assertEquals(2, r.editsDone);
        assertTrue(r.staleStarts >= 1, "the posted START went stale\n" + r.log);
        assertTrue(r.log.indexOf("tell " + RunControl.SAY_WRITTEN_AGAIN) >= 0,
            "and what is confirmed was written again\n" + r.log);
        assertAgree(r, "a stale converge written again");
        assertFalse(r.link.converging());
    }

    @Test void aStepStartRefusedThenAnEditInsideTheSettle() {
        // The review's second shape: the step START gets an FD, the convergence posts behind a
        // rewrite, and an edit lands in the settle window - the pump must not stay on step N-1.
        Script f = new Script().set("step", 2, 0, 1, 40);
        RunRig r = new RunRig(steps(8000, 28, 20, 22, 21, 19), f);
        r.run(600 + 8000 + 200);                           // step 1's START refused
        assertTrue(r.ctl.startWaiting() || r.link.converging(), r.log.toString());
        r.run(300);
        r.editTable(new Random(3), true);
        assertEquals(1, r.editsRefused, "held back\n" + r.log);
        r.run(3000);
        assertAgree(r, "step 1 in force, not step 0");
        assertEquals(20, r.pumpUp());
        RunRig u = new RunRig(steps(8000, 28, 20, 22, 21, 19), new Script().set("step", 2, 0, 1, 40));
        u.ungatedEdits = true;
        u.run(600 + 8000 + 200);
        u.run(300);
        u.editTable(new Random(3), true);
        u.run(3000);
        assertAgree(u, "ungated: step 1 in force all the same");
        assertEquals(20, u.pumpUp());
    }

    /* ========================================= the review of refusal-4, CRITICAL */

    /** A 30 kPa adjustment carried over a 24 kPa step, the carried START posted behind a table
     *  rewrite (`posting` does it), and a Revert inside the 600 ms. Before the fix the posted
     *  START was not stale (a Revert moved neither the phase nor the table), armCarry wrote 30
     *  whatever carried, the pump took it, and the screen said 24 for the rest of the step. */
    private static RunRig revertInTheSettle(Runnable[] posting, RunRig r) {
        r.run(1500);
        r.tap(30);
        r.run(1500);
        assertTrue(r.carries() && r.carryUp == 30, "carried\n" + r.log);
        posting[0].run();
        assertTrue(r.ctl.startWaiting(), "the carried START waits behind the table\n" + r.log);
        r.run(200);
        r.revert();
        return r;
    }

    @Test void aRevertWaitsForTheCarriedStartBehindTheTable() {
        // The review's sequence A: an FD when no START can be answered converges, table first.
        final RunRig r = new RunRig(steps(60_000, 24, 22), new Script());
        revertInTheSettle(new Runnable[]{ new Runnable() {
            @Override public void run() { r.strayAnswer(false); }
        } }, r);
        assertEquals(1, r.revertsRefused, "the Revert waits, and says so\n" + r.log);
        r.run(8000);
        assertAgree(r, "the carried START written; the adjustment stands");
        assertEquals(30, r.pumpUp());
        r.revert();                                          // and now it goes
        r.run(3000);
        assertEquals(1, r.reverts, r.log.toString());
        assertAgree(r, "reverted");
        assertEquals(24, r.pumpUp());
    }

    @Test void aRevertWaitsForTheRestsCarriedResume() {
        // Sequence B: an inserted rest ends while the adjustment carries - RESUME, the table
        // first, posted; the Revert 200 ms later. No FD anywhere.
        final RunRig r = new RunRig(steps(90_000, 24, 22), new Script());
        revertInTheSettle(new Runnable[]{ new Runnable() {
            @Override public void run() { r.insertRest(5000); r.run(5000 + 20); }
        } }, r);
        assertEquals(1, r.revertsRefused, r.log.toString());
        r.run(8000);
        assertAgree(r, "resumed into the adjustment");
        r.revert();
        r.run(3000);
        assertAgree(r, "reverted");
        assertEquals(24, r.pumpUp());
    }

    @Test void aRevertWaitsForAPostedStartThenGoes() {
        // RunControl's own: while a START waits behind the table a Revert waits; once it is
        // written the Revert goes - the carry ends, the routine's slot is started.
        LiveLink<String> l = link();
        StubRun run = new StubRun() {
            boolean carry = true;
            @Override public boolean carries() { return carry; }
            @Override public void carryEnds() { carry = false; super.carryEnds(); }
        };
        RunControl<String> c = new RunControl<String>(l, run);
        c.writeConfirmed(RunControl.RESUME, true, "rest");
        assertEquals("[rewrite 3, post]", run.did.toString());
        Runnable posted = run.posted;
        assertEquals(RunControl.ANSWERING, c.revert(100), "a Revert waits while it waits");
        posted.run();                                        // the carried START, written
        assertTrue(run.did.contains("carry"), run.did.toString());
        assertEquals(null, c.revert(1000), "then the Revert goes");
        assertTrue(run.did.contains("carry ends") && run.did.contains("start 0 kind "
            + RunControl.REVERT), run.did.toString());
    }

    @Test void aCarriedStartWhoseAdjustmentEndedIsDecidedAgain() {
        LiveLink<String> l = link();
        final boolean[] carry = { true };
        StubRun run = new StubRun() {
            @Override public boolean carries() { return carry[0]; }
        };
        RunControl<String> c = new RunControl<String>(l, run);
        c.writeConfirmed(RunControl.RESUME, true, "rest");
        carry[0] = false;                                    // the carry ended, nothing moved
        run.posted.run();
        assertFalse(run.did.contains("carry"), "not written: " + run.did);
        assertTrue(run.did.contains("start 0 kind " + RunControl.CONVERGE),
            "what is confirmed, written again: " + run.did);
    }

    /* ========================================= the review of refusal-4, HIGH */

    @Test void aRevertOverLinkLostWritesNothingAndResumeWaitsForTheAutoStopsVent() {
        // The adjust sheet left open over Link Lost (uiReach off): its Revert reached
        // sendStartSlot, whose cancelVentWatch ended the auto-stop mid-retry, never re-armed.
        RunRig r = new RunRig(steps(60_000, 24, 22), new Script());
        r.uiReach = false;
        r.run(1500);
        r.tap(30);
        r.run(1500);
        r.loseLink();
        r.run(RunRig.AUTO_STOP_MS + 500);
        assertTrue(r.autoStopWatch, "the auto-stop's STOP is out\n" + r.log);
        r.revert();
        assertEquals(1, r.revertsRefused, "nothing can be commanded over Link Lost\n" + r.log);
        r.regainLink();
        assertFalse(r.reconnectResume(), "no Resume over an unsettled auto-stop\n" + r.log);
        r.run(10_000);
        String since = r.log.substring(r.log.indexOf("LINK LOST"));
        assertFalse(since.indexOf("write START") >= 0 && since.indexOf("write START")
            < since.indexOf("Resume is offered"), "no START before the vent is seen\n" + r.log);
        assertFalse(r.autoStopWatch, "its vent seen after reconnecting\n" + r.log);
        assertNull(r.breach, r.breach + "\n" + r.log);
        // The owner's decision: then Resume is offered - the step as planned, the table first,
        // no carried adjustment restored.
        assertTrue(r.reconnectResume(), "Resume offered once the vent is seen\n" + r.log);
        assertTrue(r.log.indexOf("rewrite the table from step 0") >= 0, r.log.toString());
        r.run(3000);
        assertFalse(r.carries(), "the 30 kPa adjustment is not restored");
        assertAgree(r, "resumed as planned");
        assertEquals(24, r.pumpUp());
    }

    @Test void nothingIsWrittenWhileTheRunCannotBeCommanded() {
        LiveLink<String> l = link();
        final boolean[] up = { true };
        StubRun run = new StubRun() {
            @Override public boolean commandable() { return up[0]; }
        };
        RunControl<String> c = new RunControl<String>(l, run);
        c.writeConfirmed(RunControl.RESUME, true, "rest");        // posted behind the table
        up[0] = false;                                             // the link lost
        run.posted.run();
        assertFalse(run.did.toString().contains("start"), "the posted START: " + run.did);
        assertFalse(c.writeConfirmed(RunControl.REVERT, false, "revert"));
        assertEquals(RunControl.NOT_NOW, c.revert(5000));
        assertFalse(c.reconnect(false), "not before the prompt's freeze ends");
        assertFalse(run.did.toString().contains("start"), run.did.toString());
        up[0] = true;
        assertTrue(c.reconnect(true));
        assertTrue(run.did.contains("carry ends"), "after the auto-stop: no carry " + run.did);
    }

    @Test void aShortLossResumesTheRunTableFirst() {
        // The link back before the auto-stop: Resume at step N - RunControl's, the table first
        // (a reconnect may have lost frames), the carried adjustment too.
        RunRig r = new RunRig(steps(60_000, 24, 22), new Script());
        r.run(1500);
        r.tap(30);
        r.run(1500);
        r.loseLink();
        r.run(2000);
        r.regainLink();
        assertTrue(r.reconnectResume(), r.log.toString());
        assertTrue(r.log.indexOf("rewrite the table from step 0") >= 0, r.log.toString());
        r.run(3000);
        assertAgree(r, "resumed into the adjustment");
        assertEquals(30, r.pumpUp());
    }

    /* ========================================= the review of refusal-4, LOW */

    @Test void aTableInDoubtIsWrittenFirstWhoeverAsks() {
        // A Revert and a Resume passed rewrite = false and wrote a START of an entry an FD had
        // put in doubt: the table first, as the doc says, whoever asks.
        for (int k = 0; k < 2; k++) {
            LiveLink<String> l = link();
            l.startQueued("step", -1, false, false, 24, 0);
            l.written("step", 0);
            l.answer(false, 3, 40);                               // an FD: the table in doubt
            l.cancelConverge();
            StubRun run = new StubRun();
            RunControl<String> c = new RunControl<String>(l, run);
            if (k == 0) assertNull(c.revert(100));
            else c.resume("hold", false);
            assertTrue(run.did.contains("rewrite 3") && run.did.contains("post"),
                (k == 0 ? "revert" : "resume") + ": the table first, " + run.did);
            assertFalse(run.did.contains("start 0 kind " + (k == 0 ? RunControl.REVERT
                : RunControl.RESUME)), "no START of an entry in doubt: " + run.did);
        }
    }

    /* ========================================= the review of refusal-4, its mutation gaps */

    @Test void aWatchedVentSettlesAHoldSoItsLimitCannotStopAVentedRun() {
        // RC15: a Hold whose answer and every convergence's were lost (GIVE_UP), stood down into
        // a rest longer than its limit: the rest's watched vent settles it and the limit goes -
        // left armed, it would stop a run the pump had already vented.
        Script f = new Script().set("hold", 1, 1, 0, 0);
        for (int k = 1; k <= 3; k++) f.set("converge", k, 1, 0, 0);
        List<RunRig.Step> s = new ArrayList<RunRig.Step>();
        s.add(new RunRig.Step(24, false, 8000));
        s.add(new RunRig.Step(0, true, RunRig.LIMIT_MS * 2));
        s.add(new RunRig.Step(20, false, 8000));
        RunRig r = new RunRig(s, f);
        r.run(3000);
        r.hold();
        r.run(8000 + 6000);
        assertTrue(r.resting, "the rest plays\n" + r.log);
        assertTrue(r.log.indexOf("vent evidenced") >= 0, r.log.toString());
        r.run(RunRig.LIMIT_MS);
        assertEquals(0, r.limitStops, "a vented run stopped by the limit\n" + r.log);
        assertEquals(0, r.limitEndsAt, "the limit went with the vent\n" + r.log);
        assertFalse(r.ctl.holdMayBeUp());
    }

    @Test void aRunIsNotStoppedForARefusalOnATableInDoubtSinceItsRewrite() {
        // LL9, on a pump whose table is really one entry short: the step's own Add is lost, so
        // its START finds an empty slot (FD); the convergence writes the table - and the pump
        // loses that Add too; an orphan FD puts it in doubt before the converging START goes,
        // which the empty slot refuses. That refusal may be the doubt's: the table is written
        // again and the step is taken - no STOP of a run a rewrite puts right.
        List<RunRig.Step> s = new ArrayList<RunRig.Step>();
        s.add(new RunRig.Step(24, false, 60_000));
        s.add(new RunRig.Step(0, true, 5000));
        s.add(new RunRig.Step(20, false, 8000));
        RunRig r = new RunRig(s, new Script());
        r.loseAdd(1);                                              // the first table's only Add
        r.loseAdd(2);                                              // and the convergence's
        r.checking = false;                                        // an empty slot: nothing runs
        r.run(600 + 100);
        assertTrue(r.link.converging(), "the step's START refused: converging\n" + r.log);
        r.run(300);
        assertTrue(r.ctl.startWaiting(), "its START waits behind the table\n" + r.log);
        r.strayAnswer(false);                                      // an orphan FD in the settle
        r.checking = true;
        r.run(6000);
        assertEquals(0, r.pumpStops, "stopped for a refusal the doubt explains\n" + r.log);
        assertAgree(r, "the table written again, the step taken");
        assertEquals(24, r.pumpUp());
    }

    @Test void aShiftedTableSeenByAnOrphanFdIsWrittenAgainBeforeTheStep() {
        // SimPump's table one entry short from the start: the step's START runs the NEXT entry,
        // and the pump acknowledges it - nothing any design can see. An FD no START can be for
        // is the first sign: the convergence writes the table first, and the pump comes back to
        // the step the screen shows.
        RunRig r = new RunRig(steps(60_000, 24, 30, 20, 18), new Script());
        r.loseAdd(1);
        r.checking = false;
        r.run(3000);
        assertEquals(30, r.pumpUp(), "the shifted table: the next entry runs\n" + r.log);
        r.strayAnswer(false);
        r.checking = true;
        r.run(3000);
        assertTrue(r.log.indexOf("rewrite the table from step 0") >= 0, r.log.toString());
        assertAgree(r, "put right");
        assertEquals(24, r.pumpUp());
    }

    @Test void theFigureIsNeverBelowWhatIsConfirmed() {
        // RC6: while not answering, the figure is the higher of what is confirmed and what
        // LiveLink says may run - even when a vent has told LiveLink the pump runs nothing.
        LiveLink<String> l = link();
        StubRun run = new StubRun();
        RunControl<String> c = new RunControl<String>(l, run);
        LiveLinkTest.giveUp(l);
        assertTrue(l.notAnswering(), "not answering");
        c.vented();                                                // LiveLink: nothing runs
        assertTrue(c.mayBeUp() >= run.confirmedUp(), "may be " + c.mayBeUp());
    }

    @Test void theHoldsLimitNeverGoesWhileHolding() {
        // RC10: an ACK that settles a hold that may be up never lets the limit go while the
        // screen says HOLDING.
        LiveLink<String> l = link();
        StubRun run = new StubRun();
        RunControl<String> c = new RunControl<String>(l, run);
        l.beginChange(PendingChange.HOLD, 3, null, false, 20);
        l.startQueued("after", -1, false, false, 24, 0);
        l.written("after", 0);
        LiveLink.Outcome<String> o = l.answer(true, 3, 40);
        assertTrue(o.holdSettled);
        run.holding = true;
        c.handle(o);
        assertFalse(run.did.contains("limit goes"), run.did.toString());
    }

    @Test void aStartPostedBeforeAHoldMayBeUpIsNotWrittenOverIt() {
        // RC13, RC14: the stamp asks whether a hold may be up that it was not made for - even
        // with nothing else moved.
        LiveLink<String> l = link();
        StubRun run = new StubRun();
        RunControl<String> c = new RunControl<String>(l, run);
        c.writeConfirmed(RunControl.RESUME, true, "rest");
        run.holding = true;                                        // HOLDING, nothing else moved
        run.posted.run();
        assertFalse(run.did.contains("start 0 kind " + RunControl.RESUME), run.did.toString());
    }

    @Test void aDialStampedBeforeARevertIsNotAppliedAfterIt() {
        // A Revert moves the run on: a dialled change's settle, stamped before it, is stale.
        LiveLink<String> l = link();
        StubRun run = new StubRun();
        RunControl<String> c = new RunControl<String>(l, run);
        ArmStamp settle = c.settleStamp();
        assertNull(c.stale(settle));
        assertNull(c.revert(0));
        assertNotNull(c.stale(settle), "the settle was made before the Revert");
    }

    @Test void nothingWaitsOnceTheRunHasEnded() {
        // RC16: the run's end leaves no START waiting - a posted one dropped with its run must
        // not hold the next run's changes back.
        LiveLink<String> l = link();
        StubRun run = new StubRun();
        RunControl<String> c = new RunControl<String>(l, run);
        c.writeConfirmed(RunControl.RESUME, true, "rest");
        assertTrue(c.startWaiting());
        c.runEnded();
        assertFalse(c.startWaiting(), "nothing waits after the run's end");
        assertTrue(c.mayWriteChange(20_000));
    }

    /* ========================================= the review of refusal-2, CRITICAL (kept) */

    @Test void aConvergingStartPostedBehindARewriteDoesNotLandInTheRest() {
        // Step 0 out of the table, a tap 1.2 s before its end with its 01 lost, the convergence
        // posted 600 ms behind the rewrite; the Advance fires first, into a rest.
        Script f = new Script().set("change", 1, 1, 0, 0);
        RunRig r = new RunRig(steps(20_000, 24, 0, 20), f);
        r.run(1000);
        r.editTable(new Random(4), true);
        r.run(RunControl.SETTLE_MS + 20_000 - 1000 - 1200);
        r.tap(26);
        r.run(1200 + 100);
        assertTrue(r.resting, "the rest plays\n" + r.log);
        r.run(8000);
        assertNull(r.breach, r.breach + "\n" + r.log);
        assertNull(r.pump.running(), "nothing runs in the rest\n" + r.log);
        assertFalse(r.link.converging());
    }

    /* ========================================= the review of refusal-3, HIGH */

    @Test void notAnsweringCountsTheStepThePumpStayedOn() {
        // StepFdFuzz seed 1181's shape: a step's START refused, every convergence refused with
        // its FD lost - GIVE_UP; the pump pulls the step before, which the figure must cover.
        Script f = new Script().set("step", 2, 0, 1, 40)
            .set("converge", 1, 0, 0, 0).set("converge", 2, 0, 0, 0).set("converge", 3, 0, 0, 0);
        RunRig r = new RunRig(steps(30_000, 27, 25, 22), f);
        r.run(600 + 30_000 + 5000);
        assertTrue(r.link.notAnswering(), r.log.toString());
        assertEquals(27, r.pumpUp(), "the pump stayed on step 0");
        assertTrue(r.ctl.mayBeUp() >= 27, "may be at " + r.ctl.mayBeUp() + "\n" + r.log);
        assertNull(r.breach, r.breach + "\n" + r.log);
    }

    @Test void aCarriedAdjustmentsConvergenceWritesTheTableFirstAfterARefusal() {
        // F4: the carried path wrote only the override entry, never the table, so its refusals
        // never counted as "fresh"; now the table is written first too. Here each FD may be the
        // lost change's too (its answer lost within 5 s), so none is certain: it converges.
        Script f = new Script().set("change", 2, 0, 0, 0)          // refused, its FD lost
            .set("converge", 1, 0, 1, 40)                            // the carry refused: FD
            .set("converge", 2, 0, 1, 40);                           // and again, on a fresh table
        RunRig r = new RunRig(steps(60_000, 24, 26, 22), f);
        r.run(1500);
        r.tap(28);
        r.run(1500);
        assertEquals(28, r.carryUp, "carried\n" + r.log);
        r.tap(30);
        r.run(5000);
        assertTrue(r.log.indexOf("rewrite the table from step 0") >= 0,
            "the table first, the carried path too\n" + r.log);
        assertEquals(0, r.giveUps, r.log.toString());
        assertTrue(r.log.indexOf("tell " + RunControl.SAY_CONVERGED) >= 0, r.log.toString());
        assertAgree(r, "the carry, taken on a fresh table");
    }

    @Test void theCarriedPathWritesTheTableBeforeItsConvergenceAfterARefusal() {
        List<Integer> kinds = new ArrayList<Integer>();
        Script f = new Script().set("step", 1, 1, 1, 40).set("resume", 1, 0, 1, 40)
            .set("converge", 1, 0, 1, 40);
        RunRig r = new RunRig(steps(60_000, 24, 26), f);
        r.run(1500);
        r.tap(28);                                         // carried
        r.run(1500);
        assertEquals(28, r.carryUp);
        r.hold();
        r.run(500);
        assertTrue(r.holding, r.log.toString());
        r.release();                                      // the resume (carry) refused: FD
        r.run(200);
        r.run(3000);
        // table first, then the carried entry: fresh - refused again: STOP
        assertTrue(r.log.indexOf("rewrite the table from step 0") >= 0, r.log.toString());
        assertEquals(1, r.pumpStops, "the confirmed setting refused on a fresh table\n" + r.log);
        assertNull(r.breach, r.breach + "\n" + r.log);
    }

    /* ========================================= the review of refusal-3, LOW */

    @Test void notAnsweringEndsAtTheNextStepWhenTheTableWasInDoubt() {
        // F4's second half: GIVE_UP with the table in doubt; every later step's START was acked
        // with certainty, and "not answering" never cleared - for twelve minutes. Here a late FD
        // lands after the last attempt went out: GIVE_UP, the table in doubt.
        Script f = new Script().set("change", 1, 1, 0, 0).set("converge", 1, 1, 0, 0)
            .set("converge", 2, 0, 1, 1200).set("converge", 3, 1, 0, 0);
        RunRig r = new RunRig(steps(12_000, 24, 22, 20, 18), f);
        r.run(1500);
        r.tap(27);
        r.run(5000);
        assertTrue(r.link.notAnswering(), r.log.toString());
        assertTrue(r.link.tableSuspect(), "the table in doubt\n" + r.log);
        r.run(12_000);                                     // step 1: the table first, then acked
        assertTrue(r.log.indexOf("the table first (in doubt)") >= 0, r.log.toString());
        assertFalse(r.link.notAnswering(), "answering again at the next step\n" + r.log);
        assertNull(r.breach, r.breach + "\n" + r.log);
    }

    /* ========================================= the gate, on its own */

    @Test void theTableWaitsWhileAnythingMayStillBeAnswered() {
        Script f = new Script().set("change", 1, 1, 0, 0);
        RunRig r = new RunRig(steps(60_000, 24, 22, 20), f);
        r.run(2000);
        assertTrue(r.ctl.mayEditTable(r.now), "quiet: an edit may go");
        r.tap(26);
        r.run(100);
        assertFalse(r.ctl.mayEditTable(r.now), "the change may still be answered");
        assertEquals(RunControl.ANSWERING, r.ctl.editWaits(r.now));
        r.run(800);
        assertFalse(r.ctl.mayEditTable(r.now), "converging");
        r.run(10_000);
        assertTrue(r.ctl.mayEditTable(r.now), "quiet again");
        r.hold();
        r.run(300);
        assertFalse(r.ctl.mayEditTable(r.now), "a hold is up");
        assertEquals(RunControl.HOLD_UP, r.ctl.editWaits(r.now));
    }

    /* ========================================= the hold */

    @Test void aHoldStoodDownIntoARestIsSettledByItsVent() {
        RunRig r = new RunRig(steps(8000, 24, 0, 20), new Script());
        r.run(3000);
        r.hold();
        r.run(500);
        assertTrue(r.holding);
        assertTrue(r.limitEndsAt > 0);
        r.release();                                      // released, then the rest
        r.run(8000 + 3000);
        assertTrue(r.resting || r.planIdx == 2, r.log.toString());
        assertEquals(0, r.limitEndsAt, "the pump is known off the hold\n" + r.log);
        assertNull(r.breach, r.breach + "\n" + r.log);
    }

    @Test void aResumeRefusedKeepsTheLimitUntilThePumpTakesTheRelease() {
        Script f = new Script().set("resume", 1, 0, 1, 40);
        RunRig r = new RunRig(steps(60_000, 24, 22), f);
        r.run(3000);
        r.hold();
        r.run(500);
        r.release();
        r.run(100);
        assertTrue(r.limitEndsAt > 0, "refused: the limit stays\n" + r.log);
        r.run(3000);
        assertEquals(0, r.limitEndsAt, "taken on the next write: it goes\n" + r.log);
        assertAgree(r, "released");
    }

    @Test void aStrayAnswerWhileHoldingWritesNothingOverTheHold() {
        // An answer nothing can be for asks for a convergence (LiveLink). Over a hold the pump
        // took, what is confirmed IS the hold: nothing is written, and the pump holds on.
        RunRig r = new RunRig(steps(60_000, 24, 22), new Script());
        r.run(3000);
        r.hold();
        r.run(500);
        assertTrue(r.holding, r.log.toString());
        assertEquals(255, r.pump.running().upperHoldS, "the pump runs the hold\n" + r.log);
        for (boolean acked : new boolean[]{ false, true }) {
            r.strayAnswer(acked);
            r.run(3000);
            assertTrue(r.holding, r.log.toString());
            assertEquals(255, r.pump.running().upperHoldS,
                "nothing was written over the hold (" + (acked ? "01" : "FD") + ")\n" + r.log);
            assertAgree(r, "HOLDING, and the pump holding");
        }
        assertTrue(r.limitEndsAt > 0, "and its limit stands");
    }

    @Test void aHoldNeverAnsweredVentsAtItsLimit() {
        Script f = new Script().set("hold", 1, 1, 0, 0);
        for (int k = 1; k <= 3; k++) f.set("converge", k, 0, 0, 0);
        RunRig r = new RunRig(steps(60_000, 24, 22), f);
        r.run(3000);
        r.hold();
        r.run(RunRig.LIMIT_MS + 2000);
        assertEquals(1, r.limitStops, r.log.toString());
        assertNull(r.pump.running(), "stopped and vented");
    }

    /* ========================================= the decisions, one at a time */

    /** A run that records what RunControl asks of it: a step in the table (slot 0), nothing
     *  carried, commandable. `takes` answers putInForce. */
    static class StubRun implements RunControl.Run<String> {
        boolean takes = true, holding;
        Runnable posted;
        final List<String> did = new ArrayList<String>();
        @Override public int planIdx() { return 3; }
        @Override public boolean live() { return true; }
        @Override public boolean commandable() { return true; }
        @Override public boolean resting() { return false; }
        @Override public boolean holding() { return holding; }
        @Override public boolean stepIsRest() { return false; }
        @Override public boolean carries() { return false; }
        @Override public int routineSlot() { return 0; }
        @Override public int confirmedUp() { return 24; }
        @Override public void rewriteTable(int from) { did.add("rewrite " + from); }
        @Override public boolean startSlot(int slot, int kind, String why) {
            did.add("start " + slot + " kind " + kind);
            return true;
        }
        @Override public boolean startCarry(int kind, String why) { did.add("carry"); return true; }
        @Override public void post(Runnable r, long ms) { posted = r; did.add("post"); }
        @Override public boolean putInForce(LiveLink.Outcome<String> o) {
            did.add("in force");
            return takes;
        }
        @Override public void notTaken(LiveLink.Outcome<String> o) { did.add("not taken"); }
        @Override public void holdLimitGoes(String why) { did.add("limit goes"); }
        @Override public void stopRun() { did.add("stop"); }
        @Override public void carryEnds() { did.add("carry ends"); }
        @Override public void holdDown() { holding = false; did.add("hold down"); }
        @Override public void resuming(String why) { did.add("resuming " + why); }
        @Override public void tell(int what, LiveLink.Outcome<String> o, String detail) { }
    }

    static LiveLink<String> link() { return new LiveLink<String>(5000, 15000, 16); }

    @Test void aHoldTakenAfterTheRunMovedOnMayStillBeUp() {
        // The pump took the Hold, but the run had moved on and does not show it: the pump runs
        // it, so it may be up - bounded by its limit - until a later START is taken.
        LiveLink<String> l = link();
        StubRun run = new StubRun();
        RunControl<String> c = new RunControl<String>(l, run);
        int seq = l.beginChange(PendingChange.HOLD, 3, null, false, 20);
        l.startQueued("hold", seq, false, false, 20, 0);
        l.written("hold", 0);
        run.takes = false;
        LiveLink.Outcome<String> o = l.answer(true, 3, 40);
        assertEquals(LiveLink.COMMIT, o.action);
        c.handle(o);
        assertTrue(run.did.contains("in force"), run.did.toString());
        assertTrue(c.holdMayBeUp(), "the pump runs a Hold the screen does not show");
        assertFalse(run.did.contains("limit goes"), "and its limit stands");
    }

    @Test void aRefusalIsUndoneAndSaysNothingOfAHold() {
        // An adjustment refused is undone on screen; only the Hold's own refusal lets the
        // Hold's limit go (invariant 146).
        LiveLink<String> l = link();
        StubRun run = new StubRun();
        RunControl<String> c = new RunControl<String>(l, run);
        l.beginChange(PendingChange.HOLD, 3, null, false, 20);   // a Hold that may be up
        int seq = l.beginChange(PendingChange.EDIT, 3, LiveLinkTest.tuple(26), false, 0);
        l.startQueued("edit", seq, false, false, 26, 0);
        l.written("edit", 0);
        LiveLink.Outcome<String> o = l.answer(false, 3, 40);
        assertEquals(LiveLink.REFUSED, o.action);
        assertEquals(PendingChange.EDIT, o.change.kind);
        c.handle(o);
        assertTrue(run.did.contains("not taken"), "undone on screen: " + run.did);
        assertFalse(run.did.contains("limit goes"), "the Hold's limit stands: " + run.did);
    }

    @Test void everyMoveOfTheRunMakesAPostedStartStale() {
        // A START posted behind the table, and the run moves on - by a step, a rest, a Hold
        // written, confirmed or released, a table - with everything else the same: it is never
        // written as it was made; what it was for is decided again (a convergence), or ended.
        String[] moves = { "step", "rest", "hold written", "hold confirmed", "hold released",
                           "table" };
        for (String m : moves) {
            LiveLink<String> l = link();
            StubRun run = new StubRun();
            RunControl<String> c = new RunControl<String>(l, run);
            assertTrue(c.writeConfirmed(RunControl.RESUME, true, "test"));
            assertEquals("[rewrite 3, post]", run.did.toString());
            if (m.equals("step")) c.stepChanged();
            else if (m.equals("rest")) c.restChanged();
            else if (m.equals("hold written")) c.holdWritten();
            else if (m.equals("hold confirmed")) { c.holdConfirmed(); run.holding = true; }
            else if (m.equals("hold released")) c.holdReleased();
            else c.tableWritten();
            run.posted.run();
            assertFalse(run.did.contains("start 0 kind " + RunControl.RESUME),
                m + ": the START made before it is not written after it: " + run.did);
            if (!run.holding)
                assertTrue(run.did.contains("start 0 kind " + RunControl.CONVERGE),
                    m + ": what is confirmed is written again: " + run.did);
        }
        // and with nothing moved, it is written as it was made
        LiveLink<String> l = link();
        StubRun run = new StubRun();
        RunControl<String> c = new RunControl<String>(l, run);
        c.writeConfirmed(RunControl.RESUME, true, "test");
        run.posted.run();
        assertTrue(run.did.contains("start 0 kind " + RunControl.RESUME), run.did.toString());
    }

    @Test void theFuzzRigIsTheRunScreensDecisions() {
        // RunRig decides nothing RunControl does not: a guard that the rig keeps to carrying out.
        RunRig r = new RunRig(steps(5000, 24, 22), new Script());
        assertNotNull(r.ctl);
        assertTrue(r.ctl.link == r.link);
    }
}
