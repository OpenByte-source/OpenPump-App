package org.openpump;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * A LIVE CHANGE - AN ADJUSTMENT OR A HOLD - FROM THE MOMENT IT IS WRITTEN UNTIL THE PUMP ANSWERS.
 *
 * WHAT WAS WRONG. The owner raised the pull on a warm-up step. The screen took it, and the pump
 * answered `2C FD`, a refusal (Proto#REFUSED); the cuff stayed at 24 kPa. The app had already
 * saved the raise as in force, before any reply:
 *   - the carried adjustment took the new value;
 *   - the at-pressure clock paused against a line the pump was never given;
 *   - a "This set" raise of a ramp was written into its remaining steps.
 * In the owner's journal of 22 Sep, four refused taps put 8 kPa on the next step, and the cuff
 * went from 24 to 36.4 kPa: a pressure nobody had seen the pump accept.
 *
 * THE RULE NOW. A change is written (the override Add and START, and nothing else) and held here.
 * Only the pump's ack of that START puts it in force, and only on the step it was made on. The
 * caller then records the carry, shifts the ramp, or marks the run HOLDING. A refusal (`2C FD`)
 * hands the change back so the caller can put the screen back to what is in force and say so
 * plainly. NO ANSWER IS NOT A REFUSAL (#unanswered): the pump may have carried it out, so the
 * caller converges on what was confirmed instead (LiveLink).
 *
 * WHAT THE PUMP RUNS IS WHAT ITS LAST ACKNOWLEDGED START ASKED FOR - a refused START changes
 * nothing (the owner's journal: the pump carried on cycling). So:
 *   - changes still waiting are answered in the order they were written, and each ack puts its
 *     own change in force, even with a newer one on its way (the pump runs it until the newer
 *     one is taken);
 *   - a START that is NOT a live change's - the next step, a revert, +30 s's refresh - OVERTAKES
 *     every change still waiting: whatever it started is what the pump runs, so no older change
 *     may go in force after it (#overtaken);
 *   - an ack for a change whose step has already ended puts nothing in force (LATE).
 *
 * Pure, so the rules are tested without a device (PendingChangeTest). SessionActivity holds the
 * one instance and asks it from the as-run note of the START it wrote (WiringCheck invariant
 * 143).
 */
public final class PendingChange {

    public static final int EDIT = 1, HOLD = 2;

    /** Why the last {@link #acked} put nothing in force. */
    public static final int TAKEN = 0, UNKNOWN = 1, LATE = 2, OVERTAKEN = 3;

    /** At most this many changes wait at once; the oldest is forgotten past it. Each one is a
     *  START written inside one ack window, so a handful is already a burst. */
    static final int MAX_IN_FLIGHT = 8;

    /** One change as it was written. `values` is a LiveEdit tuple for an EDIT (the target, as
     *  asked, before the ceiling); `holdAt` is a Hold's pressure. */
    public static final class Change {
        public final int seq, kind, forIdx, holdAt;
        public final int[] values;
        public final boolean scopeRep;
        boolean overtaken, superseded;
        Change(int seq, int kind, int forIdx, int[] values, boolean scopeRep, int holdAt) {
            this.seq = seq; this.kind = kind; this.forIdx = forIdx; this.holdAt = holdAt;
            this.values = values == null ? null : values.clone();
            this.scopeRep = scopeRep;
        }
        /** Was a newer live change written after this one? Then its answer, not this one's,
         *  is what the person is waiting to hear about. */
        public boolean superseded() { return superseded; }
    }

    private final ArrayDeque<Change> inFlight = new ArrayDeque<Change>();
    private int seq = 0;
    private int why = UNKNOWN;

    /** A change was just written for step `forIdx`. Returns its number, which the reply to its
     *  START is matched by. */
    public int begin(int kind, int forIdx, int[] values, boolean scopeRep, int holdAt) {
        for (Iterator<Change> it = inFlight.iterator(); it.hasNext(); ) it.next().superseded = true;
        if (inFlight.size() >= MAX_IN_FLIGHT) inFlight.pollFirst();
        Change c = new Change(++seq, kind, forIdx, values, scopeRep, holdAt);
        inFlight.addLast(c);
        return c.seq;
    }

    /** The change begun last, or null. */
    Change latest() { return inFlight.peekLast(); }

    /** Is a change of this kind written, not yet answered, and not overtaken? */
    public boolean pending(int kind) {
        for (Iterator<Change> it = inFlight.iterator(); it.hasNext(); ) {
            Change c = it.next();
            if (c.kind == kind && !c.overtaken) return true;
        }
        return false;
    }

    /**
     * Change `s` had NO ANSWER inside its ack window. That is NOT a refusal: the real pump
     * often carries a START out without answering it (the owner's journal of 22 Sep: 343
     * STARTs, 319 answers; the guided start's pulled the cuff with none). Whether it is in force
     * is UNKNOWN. It is no longer waiting - the caller converges, writing what was confirmed
     * again, which overtakes it on the pump whichever way it went (LiveLink) - and a late
     * answer for it puts nothing in force. Returns the change, or null when it is not waiting
     * or was overtaken already.
     */
    public Change unanswered(int s) {
        Change c = take(s);
        return c == null || c.overtaken ? null : c;
    }

    /** A START that is not a live change's was written: it overtakes every change still
     *  waiting. Their acks put nothing in force; their refusals say nothing. */
    public void overtaken() {
        for (Iterator<Change> it = inFlight.iterator(); it.hasNext(); ) it.next().overtaken = true;
    }

    /** The pump acknowledged the START of change `s`. Returns the change to put in force now,
     *  or null - see {@link #why}. Either way it is no longer waiting. */
    public Change acked(int s, int planIdxNow) {
        Change c = take(s);
        if (c == null) { why = UNKNOWN; return null; }
        if (c.forIdx != planIdxNow) { why = LATE; return null; }
        if (c.overtaken) { why = OVERTAKEN; return null; }
        why = TAKEN;
        return c;
    }

    /** Why the last {@link #acked} returned what it did. */
    public int why() { return why; }

    /** The pump REFUSED the START of change `s` (`2C FD`) - an explicit no - or its frame was
     *  never written. Either way the pump did not take it. Returns the change, so the caller
     *  can undo the screen and say what was not taken - or null when it is not waiting, or was
     *  overtaken (what overtook it is what the pump was told last). */
    public Change failed(int s) {
        Change c = take(s);
        return c == null || c.overtaken ? null : c;
    }

    /** Change `s` was begun but never written (the write was refused before it went out):
     *  it is forgotten, and nothing older is touched. */
    public void withdraw(int s) {
        for (Iterator<Change> it = inFlight.iterator(); it.hasNext(); )
            if (it.next().seq == s) { it.remove(); break; }
        if (!inFlight.isEmpty()) inFlight.peekLast().superseded = false;
    }

    /** Nothing is waiting any more: the run ended, a new one began, or a revert put the
     *  routine's own slot back. */
    public void cancel() { inFlight.clear(); why = UNKNOWN; }

    /** Removes change `s`, and any older one still listed (its answer can no longer come
     *  before this one's), and returns it; null when it is not waiting. */
    private Change take(int s) {
        boolean waiting = false;
        for (Iterator<Change> it = inFlight.iterator(); it.hasNext(); )
            if (it.next().seq == s) { waiting = true; break; }
        if (!waiting) return null;
        Change found = null;
        for (Iterator<Change> it = inFlight.iterator(); it.hasNext(); ) {
            Change c = it.next();
            if (c.seq > s) break;
            it.remove();
            if (c.seq == s) found = c;
        }
        return found;
    }
}
