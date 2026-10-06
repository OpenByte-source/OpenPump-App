package org.openpump;

/**
 * THE RUN SCREEN'S DECISIONS ABOUT THE PUMP, IN ONE PURE PLACE.
 *
 * LiveLink says what an answer - or a silence - means. This decides what the run then DOES, and
 * it is the only place that does: SessionActivity calls it and carries out what it asks through
 * {@link Run}, and so does the fuzz (RunControlFuzzTest), on the simulated pump - so the fuzz
 * drives the decisions the app makes, not a copy of them (the review of refusal-3: a replica
 * with no stamps, no settle and no hold let seven reverted fixes pass unnoticed).
 *
 *   WHAT AN OUTCOME DOES (#handle): a live change the pump took goes in force; one it refused, or
 *   that never went out, is undone; a convergence writes what is confirmed; STOP stops the run.
 *   A hold the pump is known to be off lets its limit go - only then (invariant 146).
 *
 *   PUTTING THE PUMP ON WHAT IS CONFIRMED (#writeConfirmed) - a convergence, a hold's release, a
 *   rest's end, a revert, a reconnect: the carried adjustment when one carries, else the running
 *   step's own slot - and only while the run can be commanded (the review of refusal-4, HIGH: a
 *   Revert over Link Lost wrote a START and killed the auto-stop). When the table must be
 *   written first - the step is not in it, the pump refused something and the table is in
 *   doubt (whoever asks, the carried path included), or a vent may have emptied it - it is, and
 *   the START waits UPLOAD_SETTLE_MS behind it, STAMPED with the step, the run's phase and the
 *   table it names a slot of (ArmStamp, invariant 148).
 *
 *   THE PERSON'S WAYS BACK ARE DECIDED HERE TOO (#revert, #release, #standDown, #resume,
 *   #reconnect), so the fuzz's run goes through the same decisions as the Activity's and can be
 *   no kinder than it (the review of refusal-4: RunRig's revert cleared the carry itself, and hid
 *   a Revert that left the pump at 30 kPa under a screen saying 24). A Revert waits while a START
 *   waits behind the table, and moves the run on, so a START posted for the adjustment is never
 *   written after it; a posted START of the carried entry whose adjustment has ended is decided
 *   again.
 *
 *   A STAMP GONE STALE IS NEVER JUST DROPPED (the review of refusal-3, CRITICAL). That START was
 *   the pump's way to what the screen shows; the run moved on before it could be written, and
 *   what the pump runs is still not known. If the run can still command this step, what is
 *   confirmed NOW is written again, as a convergence, for this step and this table; if it moved
 *   into a rest, a Hold HOLDING or its end, what comes next writes the pump (the rest's STOP,
 *   the release, the STOP) and the convergence ends there.
 *
 *   NOTHING REWRITES THE TABLE UNDER A START THAT MAY STILL BE ANSWERED (#mayEditTable): the
 *   routine offset, Edit upcoming, the ramp's reshape and recount, +30 s's refresh wait - exactly
 *   as a live change waits - while any START may be answered, a START waits to be written, a
 *   convergence is under way, the pump is not answering, or a hold may be up (invariant 155).
 *
 *   WHAT THE PUMP MAY BE RUNNING while it is not answering (#mayBeUp): the higher of what is
 *   confirmed and everything LiveLink says it may still run - the step before one it refused,
 *   every START unanswered (invariant 152).
 *
 * Pure: no Android import. The caller passes a monotonic clock where one is asked for.
 */
public final class RunControl<T> {

    /** How long a START waits behind a table written just before it (UPLOAD_SETTLE_MS). */
    public static final long SETTLE_MS = 600L;

    /** What a START that puts the pump on what is confirmed is for. */
    public static final int CONVERGE = 1, RESUME = 2, REVERT = 3;
    /** The carried entry written again with its full hold after a hold carried on
     *  (HoldCarryOn): #postCarryAgain. */
    public static final int REARM = 4;

    /** What #handle and the posted STARTs tell the run screen to say or log. */
    public static final int SAY_CONVERGING = 1, SAY_CONVERGED = 2, SAY_GIVE_UP = 3,
                            SAY_ANSWERING = 4, SAY_NOT_IN_FORCE = 5, SAY_CANCELLED = 6,
                            SAY_NOT_WRITTEN = 7, SAY_NOTHING_TO_WRITE = 8, SAY_WRITTEN_AGAIN = 9;

    /** The slot a posted START names when it is the carried adjustment's entry, not a routine
     *  slot. */
    public static final int CARRY = -2;

    /** WHAT THE RUN IS, AND WHAT IT DOES - SessionActivity, or the fuzz's run on SimPump. */
    public interface Run<T> {
        /** The step playing now. */
        int planIdx();
        /** The running step may be armed: the run on, no assessment owning the pump. */
        boolean live();
        /** It may be commanded now: live, no rest, no freeze, the link up (canCommandNow). */
        boolean commandable();
        /** A rest is up - the plan's own or one the person inserted. */
        boolean resting();
        /** HOLDING on screen: the pump acknowledged a hold, not yet released. */
        boolean holding();
        /** The step playing now commands nothing (a rest step of the plan). */
        boolean stepIsRest();
        /** An adjustment carries into the step playing now. */
        boolean carries();
        /** The running step's slot in the table as written, or -1 when it is not in it. */
        int routineSlot();
        /** The pull in force: what the screen shows. */
        int confirmedUp();
        /** Write the table again from step `from`, the countdown kept. It calls #tableWritten. */
        void rewriteTable(int from);
        /** Write a START of routine slot `slot`, noted with the pull it commands; `kind` is
         *  CONVERGE, RESUME or REVERT. False when nothing was written. */
        boolean startSlot(int slot, int kind, String why);
        /** Write the carried adjustment's entry and START it, noted. False when nothing was. */
        boolean startCarry(int kind, String why);
        /** Run `r` after `ms`. */
        void post(Runnable r, long ms);
        /** The pump took this live change (`o.change`): put it in force (an adjustment
         *  committed, a Hold HOLDING). True when it went in force. */
        boolean putInForce(LiveLink.Outcome<T> o);
        /** The pump refused it (REFUSED), or it never went out (NOT_SENT): undo it on screen. */
        void notTaken(LiveLink.Outcome<T> o);
        /** The hold's limit goes: the pump is known to be off the hold. */
        void holdLimitGoes(String why);
        /** The pump refused what was confirmed on a table just given: stop the run through
         *  STOP's own path. */
        void stopRun();
        /** The carried adjustment ends (a Revert): nothing of it carries into this step or the
         *  next - what the pump runs is written next, by RunControl. */
        void carryEnds();
        /** HOLDING comes down on screen: the hold is released (Resume, a Revert over it) or stood
         *  down. RunControl hands it to LiveLink as one that may still be up (#holdReleased). */
        void holdDown();
        /** A pause is over and what is confirmed is about to be written (`why`: "hold", "rest",
         *  "reconnect", "revert"): the repetition clock waits for its START. */
        void resuming(String why);
        /** Say or log something (SAY_*): `o` the outcome it is about, or null; `detail` text. */
        void tell(int what, LiveLink.Outcome<T> o, String detail);
    }

    public final LiveLink<T> link;
    private final Run<T> run;
    /** The run's phase and the table's count (ArmStamp). */
    private int phase, table;
    /** The newest START posted and not yet fired, or null. */
    private Posted latest;

    public RunControl(LiveLink<T> link, Run<T> run) {
        this.link = link;
        this.run = run;
    }

    /* ------------------------------------------------------------------ what the pump says */

    /**
     * WHAT AN OUTCOME DOES. Returns true when a live change went in force (COMMIT, and the run
     * put it there).
     */
    public boolean handle(LiveLink.Outcome<T> o) {
        // THE HOLD'S LIMIT GOES ONLY WHEN THE PUMP IS KNOWN TO BE OFF IT: an ack, known to be
        // for a START written after the hold that is not a live change's (invariant 146).
        if (o.holdSettled && !run.holding()) run.holdLimitGoes("the pump took a START after the hold");
        if (o.answering) run.tell(SAY_ANSWERING, o, null);
        switch (o.action) {
            case LiveLink.COMMIT: {
                boolean took = run.putInForce(o);
                // Taken as the run moved on: the pump runs the Hold; it may be up until a later
                // START is taken or a watched vent is evidenced - its limit stays.
                if (o.change.kind == PendingChange.HOLD && !took && !run.holding())
                    link.releaseHold();
                return took;
            }
            case LiveLink.REFUSED:
            case LiveLink.NOT_SENT:
                // A Hold refused, or never written: it never went up - its limit goes.
                if (o.change.kind == PendingChange.HOLD && !run.holding())
                    run.holdLimitGoes("the hold was " + (o.action == LiveLink.REFUSED
                        ? "refused" : "never written"));
                run.notTaken(o);
                return false;
            case LiveLink.CONVERGE:
                converge(o);
                return false;
            case LiveLink.CONVERGED:
                run.tell(SAY_CONVERGED, o, null);
                return false;
            case LiveLink.GIVE_UP:
                run.tell(SAY_GIVE_UP, o, null);
                return false;
            case LiveLink.STOP:
                latest = null;
                run.stopRun();
                return false;
            default:
                run.tell(SAY_NOT_IN_FORCE, o, null);
                return false;
        }
    }

    /** A convergence: what is confirmed, written again for the step it is for - or ended, when
     *  that step is over, the run cannot be commanded, or a hold is up (what was confirmed is
     *  then the hold, and nothing is written over it). */
    private void converge(LiveLink.Outcome<T> o) {
        if (!run.commandable() || run.planIdx() != o.forIdx || run.holding()) {
            link.cancelConverge();
            run.tell(SAY_CANCELLED, o, null);
            return;
        }
        run.tell(SAY_CONVERGING, o, null);
        if (!writeConfirmed(CONVERGE, o.afterRefusal, "converge")) {
            link.cancelConverge();
            run.tell(SAY_NOTHING_TO_WRITE, o, null);
        }
    }

    /**
     * PUT THE PUMP ON WHAT IS CONFIRMED: the carried adjustment when one carries, else the
     * running step's own slot - with the table written first when `rewrite` asks (the pump
     * refused something, or a vent may have emptied it) or the step is not in it, and then the
     * START posted behind it, stamped. `kind`: CONVERGE, RESUME or REVERT. True when a START was
     * written or posted.
     */
    public boolean writeConfirmed(int kind, boolean rewrite, String why) {
        // Only while the run can be commanded: over Link Lost, the reconnect prompt, a rest, an
        // assessment, nothing is written - a START would cancel the auto-stop's vent watch
        // (the review of refusal-4, HIGH).
        if (!run.live() || !run.commandable() || run.stepIsRest()) return false;
        // A TABLE IN DOUBT IS WRITTEN FIRST, whoever asks - a Revert, a Resume, a reconnect as a
        // convergence does (the review of refusal-4, LOW): an FD put it in doubt, and the START
        // of an entry in it says nothing of what the pump will run.
        if (link.tableSuspect()) rewrite = true;
        if (run.carries()) {
            if (!rewrite) return run.startCarry(kind, why);
            run.rewriteTable(run.planIdx());
            post(CARRY, kind, why);
            return true;
        }
        int slot = rewrite ? -1 : run.routineSlot();
        if (slot >= 0) return run.startSlot(slot, kind, why);
        run.rewriteTable(run.planIdx());
        post(0, kind, why);
        return true;
    }

    private void post(int slot, int kind, String why) {
        Posted p = new Posted(slot, kind, why, stamp(holdMayBeUp()));
        latest = p;
        run.post(p, SETTLE_MS);
    }

    /**
     * THE FULL HOLD AGAIN AFTER A HOLD CARRIED ON (HoldCarryOn). A live change on a block the
     * pump repeats was written with what was LEFT of the new hold, so the carried entry is
     * STARTed once more, with its full hold, when that cycle's drop is over. Posted like every
     * delayed START - stamped now, written (startCarry) only onto what it was made for and only
     * while the adjustment still carries. It is never the newest: nothing else waits behind it
     * (mayWriteChange), and a stale one is only said - the pause, the step or the table that
     * made it stale wrote the pump itself.
     */
    public Posted postCarryAgain(String why, long ms) {
        Posted p = new Posted(CARRY, REARM, why, stamp(holdMayBeUp()));
        run.post(p, Math.max(0L, ms));
        return p;
    }

    /** The same for what is confirmed now: the carried entry when an adjustment carries, else
     *  the running step's own routine slot (a Resume that carried the hold on without an
     *  adjustment). Null when the step is not in the table (nothing to start: the next step
     *  writes the pump). */
    public Posted postAgain(String why, long ms) {
        if (run.carries()) return postCarryAgain(why, ms);
        int slot = run.routineSlot();
        if (slot < 0) return null;
        Posted p = new Posted(slot, REARM, why, stamp(holdMayBeUp()));
        run.post(p, Math.max(0L, ms));
        return p;
    }

    /** A START posted behind a table: written when it fires, only onto what it was made for. */
    public final class Posted implements Runnable {
        final int slot, kind;
        final String why;
        final ArmStamp stamp;

        Posted(int slot, int kind, String why, ArmStamp stamp) {
            this.slot = slot; this.kind = kind; this.why = why; this.stamp = stamp;
        }

        /** What it is for: CONVERGE, RESUME or REVERT. */
        public int kind() { return kind; }

        @Override public void run() {
            boolean newest = latest == this;
            if (newest) latest = null;
            // The run over, an assessment owning the pump, the link lost, a rest: nothing of the
            // run's is armed (invariant 114), and nothing is written again - what comes next
            // (the reconnect, the rest's end, STOP) writes the pump.
            if (!run.live() || !run.commandable()) {
                if (kind == CONVERGE) link.cancelConverge();
                run.tell(SAY_NOT_WRITTEN, null, "the run can no longer be commanded");
                return;
            }
            String stale = stale(stamp);
            // THE CARRIED ENTRY, ITS ADJUSTMENT GONE (the review of refusal-4, CRITICAL): written,
            // it would put the pump back on a pull the screen no longer shows.
            if (stale == null && slot == CARRY && !run.carries())
                stale = "the adjustment it was to write has ended";
            if (stale != null) { reconsider(this, stale, newest); return; }
            boolean written = slot == CARRY ? run.startCarry(kind, why) : run.startSlot(slot, kind, why);
            // Nothing to write after all: a convergence waiting on it would wait for ever.
            if (!written && kind == CONVERGE) {
                link.cancelConverge();
                run.tell(SAY_NOTHING_TO_WRITE, null, null);
            }
        }
    }

    /**
     * A POSTED START GONE STALE: the run moved on before it could be written. Never just
     * dropped - unless a later one carries what it was for. What it was for is decided again,
     * as a convergence, for the run as it is NOW: on a step that can be commanded, what is
     * confirmed is written for this step and this table - a Hold that may be up but is not
     * HOLDING on screen included (the pump may be running it; the screen shows the step). A
     * rest, the run's end, a freeze or a Hold HOLDING: #converge ends it there, and what comes
     * next writes the pump (the rest's STOP, STOP, the release).
     */
    private void reconsider(Posted p, String why, boolean newest) {
        run.tell(SAY_NOT_WRITTEN, null, why);
        if (!newest) return;
        LiveLink.Outcome<T> o = link.reconverge(run.planIdx());
        run.tell(SAY_WRITTEN_AGAIN, o, why);
        handle(o);
    }

    /* ------------------------------------------------------------------ the person's ways back */

    /** Why an action waits when the run cannot be commanded now (the Activity says its own
     *  freeze reason where it has one). */
    public static final String NOT_NOW =
        "Nothing can be sent to the pump right now \u2014 nothing was changed.";

    /**
     * REVERT - the adjust sheet's: the routine's own step, back on the pump. Returns why it waits
     * (said on the sheet), or null when it went.
     *
     * NOT WHILE A START WAITS BEHIND THE TABLE (the review of refusal-4, CRITICAL): a carried
     * START posted 600 ms behind a rewrite - a convergence, a rest's end - was not stale after a
     * Revert (a Revert moved neither the phase nor the table), and wrote the adjustment's 30 kPa
     * after the Revert's 24: the pump ran 30, the screen said 24, quiet. So a Revert waits, as
     * every edit of the table does; and it moves the run on, so nothing posted before it is
     * written after it.
     */
    public String revert(long now) {
        if (!run.live() || !run.commandable()) return NOT_NOW;
        if (latest != null) return ANSWERING;
        phase++;
        // No change still waiting goes in force after it: the Revert's START is what the pump
        // runs next.
        link.changes.cancel();
        run.carryEnds();
        if (run.holding()) { release("revert"); return null; }
        writeConfirmed(REVERT, false, "revert");
        return null;
    }

    /** RESUME (or a Revert over a hold): HOLDING comes down, the hold goes to LiveLink as one
     *  that may still be up - bounded until the pump takes a START after it (invariant 146) -
     *  and what is confirmed is written again. */
    public void release(String why) {
        run.holdDown();
        holdReleased();
        resume(why, false);
    }

    /** The hold stood down by the sequencing - the next step's START, a rest's STOP, a
     *  reconnect: HOLDING comes down and nothing is written here. */
    public void standDown() {
        run.holdDown();
        holdReleased();
    }

    /**
     * A PAUSE ENDS - a hold's release, an inserted rest's end, a reconnect: what is confirmed is
     * written again. `afterVent`: a StopWork may have emptied the table, so it is written first.
     * True when a START was written or posted.
     */
    public boolean resume(String why, boolean afterVent) {
        run.resuming(why);
        return writeConfirmed(RESUME, afterVent, why);
    }

    /**
     * "RESUME AT STEP N" after the link came back: the link loss may have vented the pump, so
     * the table first - the carried adjustment's path too (the review of refusal-4, LOW: it
     * wrote the carried entry with no table and no stamp). `afterStop`: the link-loss auto-stop
     * vented the pump (its vent seen after reconnecting - the owner's decision): the step
     * resumes as planned, and no carried adjustment is restored.
     */
    public boolean reconnect(boolean afterStop) {
        if (afterStop) {
            link.changes.cancel();
            run.carryEnds();
        }
        return resume("reconnect", true);
    }

    /* ------------------------------------------------------------------ stamps and phases */

    /** The stamp of a START made now and written later, naming a slot of the table written
     *  last. `forHold`: it takes the pump off a Hold that may be up. */
    public ArmStamp stamp(boolean forHold) {
        return new ArmStamp(run.planIdx(), phase, table, forHold);
    }

    /** The stamp of a dialled change's settle: it writes its own entry and START, so it names
     *  no slot of the table; never over a hold. */
    public ArmStamp settleStamp() { return new ArmStamp(run.planIdx(), phase, -1, false); }

    /** Why a START stamped `s` must NOT be written now, or null when it may (ArmStamp#stale). */
    public String stale(ArmStamp s) {
        return s.stale(run.live(), run.planIdx(), run.resting(), holdMayBeUp(), phase, table);
    }

    /** The run moved to another step. */
    public void stepChanged() { phase++; }

    /** A rest began or ended. */
    public void restChanged() { phase++; }

    /** A Hold was written. */
    public void holdWritten() { phase++; }

    /** The pump acknowledged the Hold: HOLDING. */
    public void holdConfirmed() { phase++; }

    /** The Hold is taken down - Resume, or stood down because the run moved on: it may be up
     *  until the pump ACKs a START written after this. */
    public void holdReleased() {
        phase++;
        link.releaseHold();
    }

    /** The whole table was just written again. */
    public void tableWritten() {
        table++;
        link.tableRewritten();
    }

    /** The run ended (through STOP's path). */
    public void runEnded() {
        phase++;
        latest = null;
        link.holdSettledElsewhere();
    }

    /* ------------------------------------------------------------------ the questions */

    /** May a Hold be up on the pump - held, or written and not ruled out, or released and not
     *  yet known to be off it? */
    public boolean holdMayBeUp() { return run.holding() || link.holdUnsettled(); }

    /** May a live change (an adjustment, the Hold) be written now? When LiveLink says - and no
     *  START waits behind a table to be written: it would overtake the change on the pump. */
    public boolean mayWriteChange(long now) { return link.mayWriteChange(now) && latest == null; }

    /** Is a START posted behind a table and not yet written? */
    public boolean startWaiting() { return latest != null; }

    /**
     * MAY THE TABLE BE WRITTEN NOW BY AN EDIT - the routine offset, Edit upcoming, the ramp's
     * reshape or recount, +30 s's refresh? Exactly when a live change may: nothing written may
     * still be answered, no convergence, the pump answering - and no START waiting to be written,
     * no hold that may be up (invariant 155).
     */
    public boolean mayEditTable(long now) {
        return link.mayWriteChange(now) && latest == null && !holdMayBeUp();
    }

    /** Why an edit of the table waits, in the words its sheet says, or null when it may go. */
    public String editWaits(long now) {
        if (holdMayBeUp()) return HOLD_UP;
        if (link.notAnswering()) return NOT_ANSWERING;
        if (!mayEditTable(now)) return ANSWERING;
        return null;
    }

    public static final String HOLD_UP =
        "Paused — resume first. Nothing was changed.";
    public static final String NOT_ANSWERING =
        "The pump isn't answering — nothing was changed. STOP vents it.";
    public static final String ANSWERING =
        "The pump is still answering the last command — nothing was changed yet. Try "
        + "again in a moment.";

    /** Must the next step's START wait for the table to be written again - an FD put it in
     *  doubt (the review of refusal-3, LOW: "not answering" stuck for a whole step)? */
    public boolean tableFirst() { return link.tableSuspect(); }

    /**
     * WHAT THE PUMP MAY BE RUNNING while it is not answering: the higher of what is confirmed
     * and everything LiveLink says it may still run - or -1 while it answers (invariant 152).
     */
    public int mayBeUp() {
        if (!link.notAnswering()) return -1;
        return Math.max(run.confirmedUp(), link.mayBeUp());
    }

    /**
     * A WATCHED VENT IS EVIDENCED: the pump runs nothing now, and a Hold stood down by the STOP
     * that vented it is off - its limit goes (invariant 146).
     */
    public void vented() {
        link.vented();
        if (!run.holding() && link.holdUnsettled()) {
            link.holdSettledElsewhere();
            run.holdLimitGoes("a watched vent is evidenced");
        }
    }
}
