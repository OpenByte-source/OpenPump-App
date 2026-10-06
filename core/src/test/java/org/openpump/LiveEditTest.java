package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BUG B1 - A PULL CHANGE SHOWED THE OLD VALUE WHILE IT WAS SENT, AND A QUICK SECOND TAP
 * COULD BE LOST.
 *
 * Found on the emulator: right after pull +, the cell still read -6.5 for a moment before
 * -7.1 appeared, and two taps close together counted as one step. The run screen's − / +
 * cells are now a pending TARGET ({@link LiveEdit}): every tap steps from the latest target,
 * the cell shows it at once, the write carries the last one, and every target passes the same
 * limits the wire is written with.
 *
 * The run screen steps pressures in whole kPa when the unit is kPa, so the tests use that
 * unit and exact numbers; one test repeats the ceiling rule in inHg.
 */
class LiveEditTest {

    private static final int UP = LiveEdit.UP, LO = LiveEdit.LO, UH = LiveEdit.UH,
                             SP = LiveEdit.SP, LH = LiveEdit.LH;
    private static final int CEIL = 40;
    private static final int STEP = 3;           // the plan index playing

    private String unitBefore;

    @BeforeEach void kpa() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_KPA; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    /** What is in force on the pump: pull 22, drop 12, hold 20 s, speed 50 %, drop time 5 s. */
    private static int[] inForce(int up) {
        int[] t = new int[LiveEdit.FIELDS];
        t[UP] = up; t[LO] = 12; t[UH] = 20; t[SP] = 50; t[LH] = 5;
        return t;
    }

    @Test
    void twoTapsBeforeTheFirstAcknowledgementAreTwoSteps() {
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        int[] before = inForce(22);
        assertEquals(LiveEdit.MOVED, e.tap(work, UP, 1, before, CEIL, false, STEP, 1000L));
        // The second tap lands 450 ms later. The 400 ms settle is due, but the write has not
        // been handed over yet (the job runs late whenever the main thread is busy) - nothing
        // the pump was given has changed, so this tap must build on the first one.
        assertEquals(LiveEdit.MOVED, e.tap(work, UP, 1, before, CEIL, false, STEP, 1450L));
        assertEquals(24, work[UP], "two taps are two steps: 22 -> 23 -> 24");

        e.sending(STEP);
        assertEquals(24, e.shown(UP, work, before, STEP), "and the write carries both");
    }

    @Test
    void aTapAfterTheWriteLeftButBeforeItsAckStillBuildsOnIt() {
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        e.tap(work, UP, 1, inForce(22), CEIL, false, STEP, 1000L);
        int seq = e.sending(STEP);
        // The write has gone; the app now carries 23 as what is in force.
        e.tap(work, UP, 1, inForce(23), CEIL, false, STEP, 1700L);
        assertEquals(24, work[UP]);
        assertFalse(e.acked(seq), "the first write's ack does not end the newer target");
        assertTrue(e.pending(UP, work, STEP), "24 is still on its way");
        int seq2 = e.sending(STEP);
        assertTrue(e.acked(seq2));
        assertFalse(e.pending(UP, work, STEP), "acknowledged: no longer pending");
    }

    @Test
    void theCellShowsTheTargetAtOnceAndUntilThePumpAcknowledges() {
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        int[] old = inForce(22);
        e.tap(work, UP, 1, old, CEIL, false, STEP, 1000L);
        assertEquals(23, e.shown(UP, work, old, STEP), "the tap shows at once");
        assertTrue(e.pending(UP, work, STEP), "with the pending style");
        assertFalse(e.pending(LO, work, STEP), "only on the cell that changed");

        int seq = e.sending(STEP);
        // The screen repaints while the write goes out, before the app records the new
        // value as in force - this is where the cell used to fall back to 22.
        assertEquals(23, e.shown(UP, work, old, STEP), "never the old value while it is sent");
        assertTrue(e.pending(UP, work, STEP));

        assertTrue(e.acked(seq));
        assertFalse(e.pending(UP, work, STEP));
        assertEquals(23, e.shown(UP, work, inForce(23), STEP));
    }

    @Test
    void aTapSequencePastTheCeilingStopsAtTheCeiling() {
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        int[] now = inForce(37);
        int[] results = new int[6];
        for (int i = 0; i < results.length; i++)
            results[i] = e.tap(work, UP, 1, now, CEIL, false, STEP, 1000L + 100L * i);
        assertEquals(CEIL, work[UP], "the target never passes the ceiling");
        assertEquals(LiveEdit.MOVED, results[2], "37 -> 40 in three taps");
        assertEquals(LiveEdit.AT_LIMIT, results[3], "the fourth is refused, and says so");
        assertEquals(LiveEdit.AT_LIMIT, results[5]);
    }

    @Test
    void theCeilingHoldsInInchesOfMercuryToo() {
        Model.Fmt.unit = Model.Fmt.U_INHG;
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        for (int i = 0; i < 10; i++) e.tap(work, UP, 1, inForce(34), CEIL, false, STEP, 1000L + i);
        assertEquals(CEIL, work[UP], "half-inch steps still stop at the ceiling, never past it");
    }

    @Test
    void aRefusedWriteRevertsTheCellToWhatIsInForce() {
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        int[] real = inForce(22);
        e.tap(work, UP, 1, real, CEIL, false, STEP, 1000L);
        e.sending(STEP);
        e.refused();
        assertFalse(e.pending(UP, work, STEP), "nothing is on its way any more");
        assertEquals(22, e.shown(UP, work, real, STEP), "the cell shows the real value again");
        // ...and the next tap starts from what is really in force, not from the refused target.
        e.tap(work, UP, 1, real, CEIL, false, STEP, 5000L);
        assertEquals(23, work[UP]);
    }

    @Test
    void everyStepKeepsTheLimitsTheWireIsWrittenWith() {
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        int[] now = inForce(22);
        now[LO] = 21;
        assertEquals(LiveEdit.AT_LIMIT, e.tap(work, LO, 1, now, CEIL, false, STEP, 1000L),
            "the drop stays strictly under the pull");
        assertFalse(e.pendingAt(STEP), "a refused tap dials nothing");
        assertEquals(LiveEdit.MOVED, e.tap(work, UP, -1, now, CEIL, false, STEP, 1100L));
        assertEquals(21, work[UP]);
        assertEquals(20, work[LO], "lowering the pull under the drop takes the drop with it");

        LiveEdit t = new LiveEdit();
        int[] w2 = inForce(0);
        int[] n2 = inForce(22);
        n2[UH] = 3; n2[LH] = 2; n2[SP] = 98;
        t.tap(w2, UH, -1, n2, CEIL, false, STEP, 1000L);
        assertEquals(1, w2[UH], "the hold is never under 1 s");
        assertEquals(LiveEdit.AT_LIMIT, t.tap(w2, UH, -1, n2, CEIL, false, STEP, 1100L));
        t.tap(w2, LH, -1, n2, CEIL, false, STEP, 1200L);
        assertEquals(0, w2[LH], "a drop time of 0 s is allowed - the wire carries it");
        t.tap(w2, SP, 1, n2, CEIL, false, STEP, 1300L);
        assertEquals(100, w2[SP], "speed tops out at 100 %");
        assertEquals(LiveEdit.AT_LIMIT, t.tap(w2, SP, 1, n2, CEIL, false, STEP, 1400L));
    }

    @Test
    void aHoldOnlyPresetLocksTheDropAndDropTimeOnEveryTap() {
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        assertEquals(LiveEdit.LOCKED, e.tap(work, LO, 1, inForce(22), CEIL, true, STEP, 1000L));
        assertEquals(LiveEdit.LOCKED, e.tap(work, LH, -1, inForce(22), CEIL, true, STEP, 1100L));
        assertFalse(e.pendingAt(STEP));
        assertEquals(LiveEdit.MOVED, e.tap(work, UP, 1, inForce(22), CEIL, true, STEP, 1200L),
            "the pull and the hold stay adjustable");
    }

    @Test
    void anEditNeverOutlivesItsStep() {
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        e.tap(work, UP, 1, inForce(22), CEIL, false, STEP, 1000L);
        assertTrue(e.pendingAt(STEP));
        assertFalse(e.pendingAt(STEP + 1), "the next step shows what it is really playing");
        assertTrue(e.needsSeed(STEP + 1));
        assertEquals(30, e.shown(UP, work, inForce(30), STEP + 1));
        e.tap(work, UP, 1, inForce(30), CEIL, false, STEP + 1, 1100L);
        assertEquals(31, work[UP], "a tap on the new step starts from that step, not the old target");
    }

    @Test
    void aWriteThePumpDidNotTakeEndsTheEditAndShowsWhatIsInForce() {
        // The pump-refusal fix: a write refused (`2C FD`) or never answered is NOT in force.
        // It used to keep its value and say "not confirmed" while the app counted it.
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        e.tap(work, UP, 1, inForce(22), CEIL, false, STEP, 1000L);
        int seq = e.sending(STEP);
        assertTrue(e.failed(seq));
        assertFalse(e.pendingAt(STEP), "the edit is over");
        assertEquals(22, e.shown(UP, work, inForce(22), STEP),
            "the cell shows what the pump is really running, not the refused target");
        e.tap(work, UP, 1, inForce(22), CEIL, false, STEP, 5000L);
        assertEquals(23, work[UP], "a new tap starts from what is in force");
        assertFalse(e.acked(seq), "and the failed write's late ack changes nothing");
        assertFalse(e.failed(seq), "nor does a second answer");
    }

    @Test
    void aChangeDialledOnARefusedWriteGoesWithIt() {
        // A + to 24 is on its way; a - is tapped on the cell showing 24 "sending", to 23. The
        // pump refuses the 24. Sent now, the 23 would RAISE the 22 the pump never left - so it
        // is dropped with the write it was built on, and the caller cancels its settle.
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        e.tap(work, UP, 1, inForce(22), CEIL, false, STEP, 1000L);
        e.tap(work, UP, 1, inForce(22), CEIL, false, STEP, 1100L);
        int seq = e.sending(STEP);
        e.tap(work, UP, -1, inForce(22), CEIL, false, STEP, 1200L);
        assertEquals(23, work[UP], "built on the target on its way");
        assertTrue(e.failed(seq), "the edit ends");
        assertTrue(e.lastAnswerDroppedADial(), "and the caller hears a dial was dropped");
        assertFalse(e.pendingAt(STEP));
        assertEquals(22, e.shown(UP, work, inForce(22), STEP), "the cell shows what the pump runs");
    }

    @Test
    void aChangeDialledOnAnAcknowledgedWriteStands() {
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        e.tap(work, UP, 1, inForce(22), CEIL, false, STEP, 1000L);
        int seq = e.sending(STEP);
        e.tap(work, UP, 1, inForce(22), CEIL, false, STEP, 1200L);
        assertFalse(e.acked(seq, true), "the dial on top goes on");
        assertFalse(e.lastAnswerDroppedADial());
        assertTrue(e.dialling(STEP));
        assertEquals(24, work[UP]);
        assertFalse(e.waitsOnAnswer(STEP), "its base is a fact now");
    }

    @Test
    void aChangeDialledOnAnOvertakenWriteGoesWithIt() {
        // +30 s re-sent the old carry after the write: the pump took the write and then the
        // re-send, so it runs the OLD figures. A dial on the write's target is built on nothing.
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        e.tap(work, UP, 1, inForce(22), CEIL, false, STEP, 1000L);
        int seq = e.sending(STEP);
        e.tap(work, LO, -1, inForce(22), CEIL, false, STEP, 1200L);
        assertTrue(e.acked(seq, false));
        assertTrue(e.lastAnswerDroppedADial());
        assertFalse(e.pendingAt(STEP));
    }

    @Test
    void aChangeBegunOnWhatIsConfirmedWhileAWriteIsOutWaitsForItsAnswer() {
        // A drop of 10 -> 5 is on its way. The dial on top is refused (a hold, say) and a new
        // one begins from what is CONFIRMED - drop 10 - with a - on the pull. If the pump
        // takes the 5, that dial would put the drop back up to 10: so it waits for the answer,
        // and is dropped if the write went in force.
        LiveEdit e = new LiveEdit();
        int[] confirmed = inForce(22);
        confirmed[LO] = 10;
        int[] work = confirmed.clone();
        LiveEdit.set(work, LO, 5, CEIL);
        e.edited(confirmed, STEP, 1000L);
        int seq = e.sending(STEP);
        e.edited(confirmed, STEP, 1100L);
        e.refused();
        int[] fresh = confirmed.clone();
        e.tap(fresh, UP, -1, confirmed, CEIL, false, STEP, 1300L);
        assertEquals(10, fresh[LO], "begun on what is confirmed");
        assertTrue(e.waitsOnAnswer(STEP), "not sent until the pump answers the drop of 5");
        assertTrue(e.acked(seq, true), "the drop of 5 went in force");
        assertTrue(e.lastAnswerDroppedADial(), "the dial built on 10 is dropped");
        assertFalse(e.pendingAt(STEP));

        // ...and had the pump refused the 5, the dial stands on what is still in force.
        LiveEdit e2 = new LiveEdit();
        int[] w2 = confirmed.clone();
        LiveEdit.set(w2, LO, 5, CEIL);
        e2.edited(confirmed, STEP, 1000L);
        int s2 = e2.sending(STEP);
        e2.refused();
        int[] f2 = confirmed.clone();
        e2.tap(f2, UP, -1, confirmed, CEIL, false, STEP, 1300L);
        assertTrue(e2.waitsOnAnswer(STEP));
        assertFalse(e2.failed(s2), "the refusal does not end it");
        assertFalse(e2.waitsOnAnswer(STEP), "and it may be sent now");
        assertTrue(e2.dialling(STEP));
    }

    @Test
    void theWriteWaitsForTheTargetToSettleAndCarriesOnlyTheLast() {
        LiveEdit e = new LiveEdit();
        int[] work = inForce(0);
        e.tap(work, UP, 1, inForce(22), CEIL, false, STEP, 1000L);
        e.tap(work, UP, 1, inForce(22), CEIL, false, STEP, 1300L);
        assertFalse(e.settled(1500L), "still moving");
        assertEquals(200L, e.settleDelay(1500L));
        assertTrue(e.settled(1700L));
        assertEquals(24, work[UP], "one write, carrying the last target");
        assertEquals(-1, new LiveEdit().sending(STEP), "nothing dialled, nothing to send");
    }

    /* ---- D1: a cancelled settle must END the edit, or a − tap can raise the pump ------ */

    /**
     * THE REVIEWER'S SEQUENCE. Pull 20 in force; the NOW slider dialled to 30; Reshape tapped
     * inside the settle window. The reshape cancelled the settle job and did nothing else -
     * and LiveEdit cannot see a job being cancelled, so it still said DIALLING: the cell kept
     * "30 sending…" with nothing left to send it, and the next − tap built on 30.
     *
     * The first half pins that hazard (it is why every cancel site must end the edit -
     * WiringCheck invariant 17); the second is what the app does now.
     */
    @Test
    void aCancelledSettleMustEndTheEditOrTheNextMinusRaisesThePull() {
        int[] real = inForce(20);

        LiveEdit shipped = new LiveEdit();
        int[] w1 = real.clone();
        LiveEdit.set(w1, UP, 30, CEIL);
        shipped.edited(real, STEP, 1000L);                  // the slider; the settle is queued
        // Reshape, as shipped: removeCallbacks(ovDebounce) and nothing else.
        assertTrue(shipped.dialling(STEP), "LiveEdit cannot know the job is gone");
        assertEquals(30, shipped.shown(UP, w1, real, STEP), "so the cell keeps 30 \"sending…\"");
        shipped.tap(w1, UP, -1, real, CEIL, false, STEP, 1200L);
        assertEquals(29, w1[UP], "and a MINUS would write 29 over the 20 in force");

        LiveEdit fixed = new LiveEdit();
        int[] w2 = real.clone();
        LiveEdit.set(w2, UP, 30, CEIL);
        fixed.edited(real, STEP, 1000L);
        // Reshape, now: note the dropped change (the caller says so), end the edit, and put
        // the working copy back to what is in force.
        assertTrue(fixed.dialling(STEP), "a change was being dialled - it is dropped, and said");
        fixed.refused();
        System.arraycopy(real, 0, w2, 0, LiveEdit.FIELDS);
        assertFalse(fixed.pending(UP, w2, STEP), "nothing shows as on its way");
        assertEquals(20, fixed.shown(UP, w2, real, STEP), "the cell shows what is in force");
        fixed.tap(w2, UP, -1, real, CEIL, false, STEP, 1200L);
        assertEquals(19, w2[UP], "and − steps DOWN from it");
    }

    /**
     * THE ADJUST SHEET LEFT OPEN ACROSS A STEP CHANGE (found on the emulator while testing
     * D1). The sheet seeded its working copy when it OPENED, on step 4 (pull −3.8 inHg);
     * step 5 (pull −2.1) began with the sheet still up, and moving the DROP slider wrote
     * step 4's pull, hold and speed onto step 5 - a drop change that raised the pull. An
     * edit starts from what is in force whenever nothing is pending for the step playing,
     * whichever editor makes it.
     */
    @Test
    void anEditOfOneFieldLeavesTheOthersAtWhatIsInForce() {
        LiveEdit e = new LiveEdit();
        int[] sheet = inForce(13);            // what the sheet was seeded with on step STEP
        sheet[UH] = 43; sheet[SP] = 95;
        int[] next = inForce(7);              // step STEP + 1, now playing
        next[LO] = 6; next[UH] = 45; next[SP] = 100;

        int[] w = e.startFrom(sheet, next, STEP + 1);
        LiveEdit.set(w, LO, 5, CEIL);         // the drop slider, and nothing else
        assertEquals(7, w[UP], "the pull nobody touched stays at what is in force");
        assertEquals(45, w[UH]);
        assertEquals(100, w[SP]);
        assertEquals(5, w[LO]);

        // While an edit IS pending for the step, its target is what the next change builds on.
        e.edited(next, STEP + 1, 1000L);
        int[] target = w.clone();
        int[] w2 = e.startFrom(target, next, STEP + 1);
        assertEquals(5, w2[LO], "a pending target is kept");
        assertFalse(w2 == target, "a copy - the caller's array is not written through");
    }

    /** How the model's cancel sites (reshape, hold, revert, the reconnect prompt) treat the
     *  live edit: as the app does now, or as applyRampReshape shipped. */
    private static final int ENDS_EDIT = 0, CANCEL_ONLY = 1;
    /** Which rule a run of the model checks. SHEET_RULE: a slider / typed figure moving one
     *  field never changes what the pump will be given for another (bar the drop that a
     *  lowered pull takes down with it). */
    private static final int MINUS_RULE = 0, CELL_RULE = 1, SHEET_RULE = 2;
    /** How the model's slider / typed edits start: from LiveEdit#startFrom (the app now), or
     *  from whatever the working copy holds - the sheet seeded only when it opened. */
    private static final boolean STARTS_FROM = true, SHEET_AS_SHIPPED = false;

    /**
     * A − TAP NEVER RAISES WHAT THE PUMP WILL BE GIVEN - from any state (D1). The model below
     * is the run screen's live-edit wiring: the working copy (ov*), what is in force, whether
     * the settle job is queued, and every piece that acts on them - taps, a slider / the
     * speed bar / a typed figure, the settle (hand-over, re-post, refusal), the ack or its
     * absence, a step change, and a site that cancels the job. Thousands of random
     * sequences, in both units; after every − tap, no field of what the pump will end up
     * with may be higher than before the tap, and the tapped field not above what its cell
     * showed. A cell that says "sending…" must have a write queued or on its way.
     */
    @Test
    void aMinusTapNeverRaisesThePressureFromAnyState() {
        for (String unit : new String[]{ Model.Fmt.U_KPA, Model.Fmt.U_INHG }) {
            Model.Fmt.unit = unit;
            for (long seed = 1; seed <= 3000; seed++) {
                for (int rule : new int[]{ MINUS_RULE, CELL_RULE, SHEET_RULE })
                    assertEquals(null, firstBreach(seed, ENDS_EDIT, rule, STARTS_FROM),
                        "seed " + seed + " " + unit);
            }
        }
    }

    /** ...and the model catches the sheet as it shipped (seeded only when it opened). */
    @Test
    void theModelCatchesASheetEditBuiltOnAnotherStepsFigures() {
        String leaked = null;
        for (long seed = 1; seed <= 3000 && leaked == null; seed++)
            leaked = firstBreach(seed, ENDS_EDIT, SHEET_RULE, SHEET_AS_SHIPPED);
        assertTrue(leaked != null && leaked.contains("moved field"), "a stale field rode along: " + leaked);
    }

    /** The same model with the cancel sites as applyRampReshape shipped them: both rules
     *  must catch it, or the property above proves nothing. */
    @Test
    void theModelCatchesACancelThatLeavesTheEditDialling() {
        String raised = null, unsent = null;
        for (long seed = 1; seed <= 3000 && (raised == null || unsent == null); seed++) {
            if (raised == null) raised = firstBreach(seed, CANCEL_ONLY, MINUS_RULE, STARTS_FROM);
            if (unsent == null) unsent = firstBreach(seed, CANCEL_ONLY, CELL_RULE, STARTS_FROM);
        }
        assertTrue(raised != null && raised.contains("RAISED"), "a − tap raising the pump: " + raised);
        assertTrue(unsent != null && unsent.contains("sending"), "a cell stuck on sending…: " + unsent);
    }

    /** What the pump will be given if nothing else happens: the working copy when a settle
     *  is queued for the step playing (OverrideDebounce hands it over), else what is in force. */
    private static int[] committed(LiveEdit e, boolean queued, int idx, int[] work, int[] inForce) {
        return (queued && e.dialledFor() == idx ? work : inForce).clone();
    }

    /** The same, with a write on its way: what the pump ends up with if it takes that write -
     *  the pending target the cells show. A change dialled on what was confirmed while it was
     *  out is then dropped (LiveEdit#acked); refused, whatever was dialled on its target is
     *  dropped instead, and the pump keeps `inForce` (LiveEdit#failed). */
    private static int[] committed(LiveEdit e, boolean queued, int idx, int[] work, int[] inForce,
                                   int flight, int flightIdx, int[] sentTarget) {
        if (dialStands(e, queued, idx, flight, flightIdx)) return work.clone();
        return (flight >= 0 && flightIdx == idx ? sentTarget : inForce).clone();
    }

    /** Does the change being dialled survive the answer to the write on its way, if the pump
     *  takes it? Only then is what it sends the tap's own doing. */
    private static boolean dialStands(LiveEdit e, boolean queued, int idx, int flight, int flightIdx) {
        boolean takes = flight >= 0 && flightIdx == idx;
        return queued && e.dialledFor() == idx && !(takes && e.waitsOnAnswer(idx));
    }

    private static int[] randomTuple(java.util.Random r) {
        int[] t = new int[LiveEdit.FIELDS];
        LiveEdit.set(t, UP, 1 + r.nextInt(CEIL), CEIL);
        LiveEdit.set(t, LO, r.nextInt(CEIL), CEIL);
        LiveEdit.set(t, UH, 1 + r.nextInt(60), CEIL);
        LiveEdit.set(t, LH, r.nextInt(30), CEIL);
        LiveEdit.set(t, SP, r.nextInt(101), CEIL);
        return t;
    }

    /** Runs one random sequence; the first breach of `rule`, or null. */
    private static String firstBreach(long seed, int cancelSites, int rule, boolean sheetStarts) {
        java.util.Random r = new java.util.Random(seed);
        LiveEdit e = new LiveEdit();
        int idx = 0;
        int[] inForce = randomTuple(r);
        int[] work = randomTuple(r);          // whatever the working copy last held
        boolean queued = false;               // the settle job is posted
        int flight = -1;                      // the write handed over and not yet answered
        int[] sentTarget = inForce;           // what that write carries: in force on its ack
        int flightIdx = -1;                   // ...and only for the step it was made on
        long now = 1000L;
        for (int op = 0; op < 80; op++) {
            now += r.nextInt(600);
            int kind = r.nextInt(10);
            if (kind <= 3) {                                   // a − / + tap (nudgeOverride)
                int field = r.nextInt(LiveEdit.FIELDS), dir = r.nextBoolean() ? 1 : -1;
                int[] before = committed(e, queued, idx, work, inForce, flight, flightIdx, sentTarget);
                int shown = e.shown(field, work, inForce, idx);
                if (e.tap(work, field, dir, inForce, CEIL, false, idx, now) == LiveEdit.MOVED)
                    queued = true;                             // overrideChanged re-posts it
                if (rule == MINUS_RULE && dir < 0) {
                    int[] after = committed(e, queued, idx, work, inForce, flight, flightIdx, sentTarget);
                    for (int f = 0; f < LiveEdit.FIELDS; f++)
                        if (after[f] > before[f])
                            return "op " + op + ": a − tap on field " + field + " RAISED field "
                                + f + " from " + before[f] + " to " + after[f];
                    // What the tap itself sends - when its change survives the answer to a write
                    // still on its way; dropped, what the pump runs is that write's doing.
                    if (dialStands(e, queued, idx, flight, flightIdx) && after[field] > shown)
                        return "op " + op + ": a − tap gave " + after[field]
                            + ", above the " + shown + " its cell showed";
                }
            } else if (kind == 4) {                            // a slider, the speed bar, a typed figure
                int field = r.nextInt(LiveEdit.FIELDS);
                int[] before = committed(e, queued, idx, work, inForce, flight, flightIdx, sentTarget);
                if (sheetStarts) work = e.startFrom(work, inForce, idx);
                LiveEdit.set(work, field, r.nextInt(60), CEIL);
                e.edited(inForce, idx, now);
                queued = true;
                if (rule == SHEET_RULE) {
                    int[] after = committed(e, queued, idx, work, inForce, flight, flightIdx, sentTarget);
                    for (int f = 0; f < LiveEdit.FIELDS; f++) {
                        if (f == field) continue;
                        boolean clamped = f == LO && field == UP && after[f] <= before[f];
                        if (after[f] != before[f] && !clamped)
                            return "op " + op + ": an edit of field " + field + " moved field "
                                + f + " from " + before[f] + " to " + after[f];
                    }
                }
            } else if (kind == 5 && queued) {                  // the settle job runs
                queued = false;
                if (e.dialledFor() != idx) e.refused();        // its step ended
                else if (!e.settled(now)) queued = true;       // still moving: re-posted
                else if (e.waitsOnAnswer(idx)) queued = true;  // its base is not known yet
                else if (r.nextInt(6) == 0) e.refused();       // no link, or refused inside
                else { flight = e.sending(idx); sentTarget = work.clone(); flightIdx = idx; }
            } else if (kind == 6 && flight >= 0) {             // the pump answers, or does not
                // Only the ack puts the target in force (carry* = the sent values then), and only
                // on its own step, not overtaken (PendingChange#acked). An answer that ends a
                // change still being dialled cancels its settle too.
                boolean took = flightIdx == idx;
                if (r.nextBoolean()) {
                    if (e.acked(flight, took) && e.lastAnswerDroppedADial()) queued = false;
                    if (took) inForce = sentTarget;
                } else if (e.failed(flight) && e.lastAnswerDroppedADial()) queued = false;
                flight = -1;
            } else if (kind == 7) {                            // the next step
                idx++;
                inForce = randomTuple(r);
            } else if (kind == 8) {                            // reshape, hold, revert, reconnect
                queued = false;                                // removeCallbacks(ovDebounce)
                // A write still on its way never goes in force after one of these: a revert
                // overtakes it (PendingChange#cancel), a reshape waits for its answer
                // (refused while one is on its way), a hold refuses every edit until it is
                // released, and the reconnect prompt refuses them while it is up.
                flightIdx = -1;
                if (cancelSites == ENDS_EDIT) {
                    e.refused();
                    if (r.nextBoolean()) work = inForce.clone();   // the sites that also reseed
                }
                if (r.nextInt(3) == 0) inForce = randomTuple(r);  // a revert or hold moves it
            }
            if (rule == CELL_RULE) {
                for (int f = 0; f < LiveEdit.FIELDS; f++)
                    if (e.pending(f, work, idx) && !queued && flight < 0)
                        return "op " + op + ": field " + f + " shows \"sending…\" with nothing "
                            + "queued or on its way";
            }
        }
        return null;
    }

    /* ---- A VIEW OF ABSOLUTE FIGURES MUST BE CURRENT (safety review of D1, finding 1) ---- */

    /**
     * THE REVIEWER'S SEQUENCE. The adjust sheet was drawn on step STEP (pull −3.8 inHg, 13
     * kPa) and left open; step STEP + 1 plays at −2.1 (7 kPa). A slider sets an ABSOLUTE
     * figure: a small leftward drag from where the pull slider was drawn lands at about
     * −3.6 (12 kPa) - a gesture toward less pull that would RAISE the pump from 7 to 12.
     * startFrom alone does not stop it (the other four figures are right, the dragged one is
     * not). The view is not current, so the gesture is refused and the sheet redrawn.
     */
    @Test
    void aSheetLeftOpenIntoAnotherStepIsNotCurrent() {
        LiveEdit e = new LiveEdit();
        int[] drawn = inForce(13);                        // what the sheet shows, step STEP
        int[] next = inForce(7);                          // step STEP + 1, playing now
        next[LO] = 6;
        int[] now = e.startFrom(drawn, next, STEP + 1);
        assertFalse(LiveEdit.viewCurrent(STEP, drawn, STEP + 1, now),
            "drawn for another step - refused, redrawn");

        // What the unguarded drag would have sent: the slider's position, "a bit left".
        int[] w = e.startFrom(drawn, next, STEP + 1);
        LiveEdit.set(w, UP, drawn[UP] - 1, CEIL);
        assertTrue(w[UP] > next[UP], "the hazard: a leftward drag raising the pull, "
            + next[UP] + " -> " + w[UP]);

        // Redrawn, the same gesture is a lowering.
        assertTrue(LiveEdit.viewCurrent(STEP + 1, now, STEP + 1, e.startFrom(now, next, STEP + 1)));
        int[] w2 = e.startFrom(now, next, STEP + 1);
        LiveEdit.set(w2, UP, now[UP] - 1, CEIL);
        assertEquals(6, w2[UP]);
    }

    /** The same on ONE step: the sheet shows a target the app then refused (no link) - the
     *  cell went back to what is in force, the sheet did not. Not current either. */
    @Test
    void aViewDrawnBeforeARefusalIsNotCurrent() {
        LiveEdit e = new LiveEdit();
        int[] real = inForce(20);
        int[] work = real.clone();
        LiveEdit.set(work, UP, 30, CEIL);
        e.edited(real, STEP, 1000L);
        int[] drawn = work.clone();
        assertTrue(LiveEdit.viewCurrent(STEP, drawn, STEP, e.startFrom(work, real, STEP)));
        e.refused();
        assertFalse(LiveEdit.viewCurrent(STEP, drawn, STEP, e.startFrom(work, real, STEP)),
            "30 was never sent - a drag 'a bit lower' than 30 would still raise the 20 in force");
    }

    /** ...and no false refusals: a view stays current through its own edit, the write and
     *  the ack (the carry the app records IS the target it drew). */
    @Test
    void aViewStaysCurrentThroughItsOwnEditAndItsAck() {
        LiveEdit e = new LiveEdit();
        int[] real = inForce(20);
        int[] work = e.startFrom(real, real, STEP);
        LiveEdit.set(work, UP, 24, CEIL);
        int[] drawn = work.clone();                       // fillOverrideSheet after the move
        e.edited(real, STEP, 1000L);
        assertTrue(LiveEdit.viewCurrent(STEP, drawn, STEP, e.startFrom(work, real, STEP)));
        int s = e.sending(STEP);
        int[] carried = work.clone();                     // applyOverride records the carry
        assertTrue(LiveEdit.viewCurrent(STEP, drawn, STEP, e.startFrom(work, carried, STEP)));
        e.acked(s);
        assertTrue(LiveEdit.viewCurrent(STEP, drawn, STEP, e.startFrom(work, carried, STEP)));
        assertFalse(LiveEdit.viewCurrent(STEP, null, STEP, real), "nothing drawn is not current");
    }

    /**
     * A GESTURE TOWARD LESS NEVER RAISES WHAT THE PUMP WILL BE GIVEN - through a VIEW (safety
     * review, finding 1). The run screen's absolute-figure editors - the adjust sheet's
     * sliders, a typed figure, the speed bar - each set a figure picked against what they
     * SHOWED. The model: the live-edit wiring of the tests above, plus a sheet (drawn for a
     * step, showing a tuple; redrawn by the tick when it is not current), a typed-figure
     * dialog and a speed-bar drag, each holding what it showed when it was opened. After
     * every gesture toward less - a leftward drag, a lower typed figure, a − tap - no field of
     * what the pump will be given may be higher than before it. Step changes, refusals,
     * acks, the settle and the cancel sites all interleave.
     */
    @Test
    void aGestureTowardLessNeverRaisesThroughAView() {
        for (String unit : new String[]{ Model.Fmt.U_KPA, Model.Fmt.U_INHG }) {
            Model.Fmt.unit = unit;
            for (long seed = 1; seed <= 3000; seed++)
                assertEquals(null, firstViewBreach(seed, true), "seed " + seed + " " + unit);
        }
    }

    /** The model with the views as they shipped (no currency check, no redraw): caught. */
    @Test
    void theModelCatchesAStaleViewRaisingThePump() {
        String raised = null;
        for (long seed = 1; seed <= 3000 && raised == null; seed++)
            raised = firstViewBreach(seed, false);
        assertTrue(raised != null && raised.contains("RAISED"), "a stale view raised the pump: " + raised);
    }

    /** One view: the step it was drawn for and the tuple it showed (null: not open). */
    private static final class View {
        int idx = -1;
        int[] shows;
        void draw(int i, int[] t) { idx = i; shows = t.clone(); }
        void close() { idx = -1; shows = null; }
        boolean open() { return shows != null; }
    }

    /** The first breach of "a gesture toward less never raises", or null. */
    private static String firstViewBreach(long seed, boolean guarded) {
        java.util.Random r = new java.util.Random(seed);
        LiveEdit e = new LiveEdit();
        int idx = 0;
        int[] inForce = randomTuple(r);
        int[] work = randomTuple(r);
        boolean queued = false;
        int flight = -1;
        int[] sentTarget = inForce;
        int flightIdx = -1;
        long now = 1000L;
        View sheet = new View(), typed = new View(), speed = new View();
        int typedField = UP;
        for (int op = 0; op < 80; op++) {
            now += r.nextInt(600);
            int kind = r.nextInt(14);
            int[] before = committed(e, queued, idx, work, inForce, flight, flightIdx, sentTarget);
            String gesture = null;
            if (kind == 0) {                                   // open / redraw the sheet
                if (e.needsSeed(idx)) work = inForce.clone();  // seedOverrideIfIdle
                sheet.draw(idx, work);
            } else if (kind <= 3 && sheet.open()) {            // drag a sheet slider
                int f = r.nextInt(LiveEdit.FIELDS), dir = r.nextBoolean() ? 1 : -1;
                int[] from = e.startFrom(work, inForce, idx);
                if (guarded && !LiveEdit.viewCurrent(sheet.idx, sheet.shows, idx, from)) {
                    if (e.needsSeed(idx)) work = inForce.clone();
                    sheet.draw(idx, work);                     // refused, redrawn, said
                } else {
                    // The slider was drawn at shows[f]: the drag lands a little either side.
                    LiveEdit.set(from, f, sheet.shows[f] + dir * (1 + r.nextInt(4)), CEIL);
                    work = from;
                    e.edited(inForce, idx, now);
                    queued = true;
                    sheet.draw(idx, work);                     // fillOverrideSheet after a move
                    if (dir < 0) gesture = "a leftward drag of field " + f;
                }
            } else if (kind == 4) {                            // open a typed figure
                if (e.needsSeed(idx)) work = inForce.clone();
                typedField = r.nextInt(LiveEdit.FIELDS);
                typed.draw(idx, work);
            } else if (kind == 5 && typed.open()) {            // Set: a figure a little lower
                int[] from = e.startFrom(work, inForce, idx);
                if (!guarded || LiveEdit.viewCurrent(typed.idx, typed.shows, idx, from)) {
                    LiveEdit.set(from, typedField, typed.shows[typedField] - 1 - r.nextInt(3), CEIL);
                    work = from;
                    e.edited(inForce, idx, now);
                    queued = true;
                    gesture = "a lower typed figure for field " + typedField;
                }
                typed.close();
            } else if (kind == 6) {                            // touch the speed bar
                if (e.needsSeed(idx)) work = inForce.clone();
                speed.draw(idx, work);
            } else if (kind == 7 && speed.open()) {            // ...and drag it left
                int[] from = e.startFrom(work, inForce, idx);
                if (guarded && !LiveEdit.viewCurrent(speed.idx, speed.shows, idx, from)) {
                    speed.close();                             // refused until the next touch
                } else {
                    LiveEdit.set(from, SP, speed.shows[SP] - 1 - r.nextInt(5), CEIL);
                    work = from;
                    e.edited(inForce, idx, now);
                    queued = true;
                    speed.draw(idx, work);
                    gesture = "a leftward speed-bar drag";
                }
            } else if (kind == 8) {                            // a − / + tap on a cell
                int f = r.nextInt(LiveEdit.FIELDS), dir = r.nextBoolean() ? 1 : -1;
                if (e.tap(work, f, dir, inForce, CEIL, false, idx, now) == LiveEdit.MOVED) queued = true;
                if (dir < 0) gesture = "a − tap on field " + f;
            } else if (kind == 9 && queued) {                  // the settle job runs
                queued = false;
                if (e.dialledFor() != idx) e.refused();
                else if (!e.settled(now)) queued = true;
                else if (e.waitsOnAnswer(idx)) queued = true;  // its base is not known yet
                else if (r.nextInt(5) == 0) e.refused();       // no link, or refused inside
                else { flight = e.sending(idx); sentTarget = work.clone(); flightIdx = idx; }
            } else if (kind == 10 && flight >= 0) {            // the pump answers, or not
                boolean took = flightIdx == idx;
                if (r.nextBoolean()) {
                    if (e.acked(flight, took) && e.lastAnswerDroppedADial()) queued = false;
                    if (took) inForce = sentTarget;
                } else if (e.failed(flight) && e.lastAnswerDroppedADial()) queued = false;
                flight = -1;
            } else if (kind == 11) {                           // the next step
                idx++;
                inForce = randomTuple(r);
            } else if (kind == 12) {                           // a cancel site (D1 discipline)
                queued = false;
                e.refused();
                flightIdx = -1;   // a write on its way is overtaken (a revert), or waited for
                if (r.nextBoolean()) work = inForce.clone();
            } else if (kind == 13 && guarded && sheet.open()) {   // the tick redraws a stale sheet
                if (!LiveEdit.viewCurrent(sheet.idx, sheet.shows, idx, e.startFrom(work, inForce, idx))) {
                    if (e.needsSeed(idx)) work = inForce.clone();
                    sheet.draw(idx, work);
                }
            }
            if (gesture != null) {
                int[] after = committed(e, queued, idx, work, inForce, flight, flightIdx, sentTarget);
                for (int f = 0; f < LiveEdit.FIELDS; f++)
                    if (after[f] > before[f])
                        return "op " + op + ": " + gesture + " RAISED field " + f + " from "
                            + before[f] + " to " + after[f];
            }
        }
        return null;
    }
}
