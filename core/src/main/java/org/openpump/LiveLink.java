package org.openpump;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * WHAT THE RUN SCREEN DOES WITH THE PUMP'S ANSWER TO A START - OR WITH ITS SILENCE.
 *
 * A live change (an adjustment or the Hold) is written as an override Add and a START, and goes
 * in force only on that START's own `2C 01` (PendingChange). This decides everything around it:
 *
 *   AN ANSWER IS FOR THE STARTS IT MAY BE FOR (ReplyQueue): answers name no START, keep their
 *   order and come within a horizon of the write. When it can be for ONE START only, it is
 *   that START's; when it can be for several, it puts NOTHING in force - a too-late answer
 *   poisons nothing - and the changes it may have been for are left to their windows, as if
 *   unanswered. An answer that can be for no START at all (an orphan) moves nothing either,
 *   and since it means an answer came later than the horizon allows, the run converges on what
 *   it confirmed: WHEN IN DOUBT, CONVERGE.
 *
 *   AN EXPLICIT NO (`2C FD`) FOR A LIVE CHANGE, KNOWN TO BE ITS OWN, is a refusal: the pump did
 *   not take it and carries on with what it ran. Undo, say so (REFUSED).
 *
 *   ANY OTHER FD IS A REFUSAL TOO, WHEREVER IT LANDS - of a step's START, of a converging one,
 *   of one of a convergence already over, of one of several: the pump did not take something the
 *   app wrote, so what it runs is not known, and neither is its table. The run converges on what
 *   was confirmed, the table written again first (afterRefusal). And if the pump refuses a
 *   converging START written onto a table the app had just written because of a refusal -
 *   refuses the very setting the screen shows - nothing the app can write will be taken: STOP.
 *   An FD that may be for several STARTs counts the same WHEN EVERY ONE OF THEM IS SUCH A START:
 *   whichever it was, it was what was confirmed, refused on a table the pump was just given.
 *
 *   NO ANSWER IS UNKNOWN, NOT A REFUSAL. The real pump often carries a START out without
 *   answering it (the owner's journal of 22 Sep: 343 STARTs, 319 answers). So a change with no
 *   answer of its own inside its window CONVERGES: the caller writes what was CONFIRMED again,
 *   as an ordinary START, which overtakes the lost change on the pump whichever way it went. An
 *   ack known to be for a converging START - or one that can only be for converging STARTs of
 *   this convergence, which all write the same setting - ends it (CONVERGED). Refused or
 *   unanswered, it is written again - MAX_CONVERGE times in all - and then GIVE_UP: the pump is
 *   not answering, and the person is told what it may be running.
 *
 *   WHAT THE PUMP MAY BE RUNNING (#mayBeUp): the pull of the last START it is KNOWN to have
 *   taken - acknowledged with certainty, on a table in no doubt - and of every START written
 *   after it that it has not refused with certainty. A step's START refused leaves the step
 *   before it running; one unanswered may or may not have run; both are in it (the review of
 *   refusal-3, HIGH: the figure left out the step the pump stayed on).
 *
 *   NOT ANSWERING STICKS until the pump ACKs, with certainty, a START whose setting is known -
 *   one that is not a live change's, written onto a table no refusal has put in doubt since it
 *   was written (#tableRewritten). An FD, an answer that may be anyone's, an orphan: none of
 *   them is the pump answering what the app asked (invariant 149). While the table is in doubt
 *   the caller writes it again at the next step (#tableSuspect), so it cannot stick.
 *
 *   A HOLD IS BOUNDED FROM THE MOMENT IT IS WRITTEN UNTIL THE PUMP IS KNOWN TO BE OFF IT.
 *   #holdUnsettled is true from its write until its own ack (the caller marks HOLDING) or its
 *   own refusal - and again from its release (#releaseHold: Resume, a stand-down) until the
 *   pump ACKs, with certainty, a START written after it that is not a live change's. An FD or
 *   no answer to that START converges: the release is written again, and the caller keeps the
 *   Hold's limit armed while this says the hold may be up (invariant 146).
 *
 * Every START the run writes goes through here, in write order (#startQueued, #written), with
 * the note that stands for it as the token; the answers come back through #answer, a window
 * passing through #timedOut. Pure; the caller passes a monotonic clock. RunControl drives it for
 * the run screen. LiveLinkTest, LiveLinkFuzzTest, RunControlFuzzTest.
 */
public final class LiveLink<T> {

    /** Converging STARTs written, at most, before the pump is taken to be not answering. */
    public static final int MAX_CONVERGE = 3;

    public static final int NONE = 0, COMMIT = 1, REFUSED = 2, NOT_SENT = 3, CONVERGE = 4,
                            CONVERGED = 5, GIVE_UP = 6, STOP = 7;

    /** What the caller must do. `token`: the START this is known to be about, or null (an
     *  answer that may be for several, or for none); `change` the live change it is about
     *  (COMMIT, REFUSED, NOT_SENT; the lost one, if any, for CONVERGE / CONVERGED / GIVE_UP /
     *  STOP); `forIdx` the step a convergence is for; `attempt` which converging START to
     *  write; `afterRefusal` - an FD was seen: the table is not known, write it first;
     *  `holdSettled` - the pump is known to be off a Hold that may have been up: its limit may
     *  go; `answering` - the pump ACKed a START whose setting is known, after it had stopped
     *  answering; `why` - PendingChange#why for an ack that put nothing in force. */
    public static final class Outcome<T> {
        public final T token;
        public final int action;
        public final PendingChange.Change change;
        public final int attempt;
        public final boolean holdSettled;
        public final int why;
        public boolean afterRefusal;
        public boolean answering;
        public int forIdx = -1;
        Outcome(T token, int action, PendingChange.Change change, int attempt, boolean settled,
                int why) {
            this.token = token; this.action = action; this.change = change;
            this.attempt = attempt; this.holdSettled = settled; this.why = why;
        }
    }

    private static final class Info {
        int changeSeq = -1;
        /** The live change it writes, or null. */
        PendingChange.Change change;
        /** The convergence this START writes for (#convergence), or -1. */
        int convergence = -1;
        /** Its attempt number within it. */
        int attempt;
        /** Not a live change's, written while a Hold may be up: its ack takes the pump off it. */
        boolean settlesHold;
        /** Written onto a table no refusal has put in doubt: its setting is what the app wrote. */
        boolean known;
        /** A converging START written onto a table rewritten BECAUSE of a refusal, with no FD
         *  since: refused, it is the pump refusing what was confirmed on a table it was just
         *  given. */
        boolean fresh;
        /** Its window passed, or its own answer came: decided. */
        boolean decided;
    }

    /** A START written since the last one the pump is known to run, and what it pulls. */
    private static final class Sent<T> {
        final T token; final int up; final boolean known;
        Sent(T t, int u, boolean k) { token = t; up = u; known = k; }
    }

    /** The most STARTs kept in the may-be model; older ones fold into #foldedUp. */
    private static final int MAX_SINCE = 64;

    public final PendingChange changes = new PendingChange();
    private final ReplyQueue<T> replies;
    private final Map<T, Info> info = new IdentityHashMap<T, Info>();

    private boolean holdUnsettled;
    /** The Hold that may be up (#holdUnsettled), for what the caller says. */
    private PendingChange.Change holdChange;
    private boolean converging;
    private int convergence;
    private int attempts;
    private int convergeFor = -1;
    private PendingChange.Change lost;
    /** The last live change whose own answer never came, since the pump last ran a known
     *  setting - what a convergence no one change asked for is about. */
    private PendingChange.Change lastUnknown;
    /** An FD was seen since the attempt under way was asked for: the next is written after
     *  the table. */
    private boolean refusedSince;
    private boolean deaf;
    /** An FD was seen since the table was last written: what an entry holds is not known. */
    private boolean tableSuspect;
    /** Tables written so far (#tableRewritten), and how many there were when a convergence last
     *  asked for one after a refusal (-1: none asked). */
    private int rewrites, rewriteAskedAt = -1;

    /** The pull of the last START the pump is known to run (-1: none known yet), and every
     *  START written since that it has not refused with certainty. */
    private int anchorUp = -1;
    private final List<Sent<T>> since = new ArrayList<Sent<T>>();
    private int foldedUp = -1;

    /** @param horizonMs how long after its write a START may still be answered (ReplyQueue);
     *  @param unsentMs how long a START never written is kept;
     *  @param maxNotes the most STARTs kept. */
    public LiveLink(long horizonMs, long unsentMs, int maxNotes) {
        replies = new ReplyQueue<T>(horizonMs, unsentMs, maxNotes);
    }

    /* ----------------------------------------------------------------------- the gates */

    /** May a live change be written now? Only while nothing written may still be answered,
     *  no convergence is under way, and the pump has not stopped answering. */
    public boolean mayWriteChange(long now) {
        return !converging && !deaf && replies.quiet(now);
    }

    /** Is a convergence under way (what was confirmed being written again)? */
    public boolean converging() { return converging; }

    /** Did the pump stop answering (GIVE_UP), with no ACK since of a START whose setting is
     *  known? */
    public boolean notAnswering() { return deaf; }

    /** May a Hold be up on the pump - written and never confirmed or ruled out, or released and
     *  the pump not yet known to be off it? */
    public boolean holdUnsettled() { return holdUnsettled; }

    /** Has an FD been seen since the table was last written - is what its entries hold not
     *  known? The caller writes it again before the next step's START. */
    public boolean tableSuspect() { return tableSuspect; }

    /** May `token`'s START still be answered? */
    public boolean waiting(T token, long now) { return replies.waiting(token, now); }

    /**
     * THE HIGHEST PULL THE PUMP MAY BE RUNNING: that of the last START it is known to run, and
     * of every START written after it that it has not refused with certainty - or -1 when
     * nothing written says. The caller shows it, with what it confirmed, while the pump is not
     * answering.
     */
    public int mayBeUp() {
        int up = Math.max(anchorUp, foldedUp);
        for (int k = 0; k < since.size(); k++) up = Math.max(up, since.get(k).up);
        return up;
    }

    /* ----------------------------------------------------------------------- the writes */

    /** A live change is about to be written: returns its number for #startQueued. */
    public int beginChange(int kind, int forIdx, int[] values, boolean scopeRep, int holdAt) {
        int s = changes.begin(kind, forIdx, values, scopeRep, holdAt);
        if (kind == PendingChange.HOLD) { holdUnsettled = true; holdChange = changes.latest(); }
        return s;
    }

    /** Change `s` was begun and never written (refused before it went out). */
    public void withdraw(int s, int kind) {
        changes.withdraw(s);
        if (kind == PendingChange.HOLD) holdUnsettled = false;
    }

    /** The Hold is being taken down - Resume, or stood down because the run moved on: until the
     *  pump ACKs a START written after this, it may still be holding. */
    public void releaseHold() { holdUnsettled = true; }

    /** The whole table was just written again: what its entries hold is what the app wrote. */
    public void tableRewritten() { tableSuspect = false; rewrites++; }

    /** A watched vent is evidenced: the pump runs nothing now. */
    public void vented() {
        anchorUp = 0;
        foldedUp = -1;
        since.clear();
    }

    /** A START is being written (#startQueued with the pull it commands unknown). */
    public void startQueued(T token, int changeSeq, boolean converge, boolean placeholder,
                            long now) {
        startQueued(token, changeSeq, converge, placeholder, -1, now);
    }

    /**
     * A START is being written, and `token` stands for it. `changeSeq`: the live change it is
     * (#beginChange), or -1; `converge`: a converging START (a CONVERGE outcome asked for it);
     * `placeholder`: it opened no row; `up`: the pull it commands (-1 unknown). Any START that
     * is not a live change's own overtakes every change still waiting, and one written while a
     * Hold may be up settles it when acknowledged. One that is not a converging START ends a
     * convergence: it is what the pump runs next, either way.
     */
    public void startQueued(T token, int changeSeq, boolean converge, boolean placeholder,
                            int up, long now) {
        Info i = new Info();
        i.changeSeq = changeSeq;
        i.known = !tableSuspect;
        if (changeSeq >= 0) {
            PendingChange.Change c = changes.latest();
            if (c != null && c.seq == changeSeq) i.change = c;
        }
        if (changeSeq < 0) {
            changes.overtaken();
            i.settlesHold = holdUnsettled;
            if (converge && converging) {
                i.convergence = convergence;
                i.attempt = attempts;
                i.fresh = rewriteAskedAt >= 0 && rewrites > rewriteAskedAt && !tableSuspect;
            } else if (!converge) converging = false;
        }
        info.put(token, i);
        replies.enqueue(token, placeholder, now);
        since.add(new Sent<T>(token, up, i.known));
        while (since.size() > MAX_SINCE) foldedUp = Math.max(foldedUp, since.remove(0).up);
    }

    /** `token`'s frame left the phone - or may have (a link torn down with it in flight). */
    public void written(T token, long now) { replies.sent(token, now); }

    /* ---------------------------------------------------------------------- the answers */

    /** The pump answered a START: `acked` for `2C 01`, false for `2C FD`. */
    public Outcome<T> answer(boolean acked, int planIdx, long now) {
        List<T> may = replies.answer(now);
        Outcome<T> o;
        if (may.isEmpty()) o = orphan(acked, planIdx);
        else if (may.size() == 1) o = certain(may.get(0), acked, planIdx);
        else o = uncertain(may, acked, planIdx);
        forget(now);
        return o;
    }

    /** Known to be `t`'s. */
    private Outcome<T> certain(T t, boolean acked, int planIdx) {
        Info i = info.get(t);
        if (i == null) return new Outcome<T>(t, NONE, null, 0, false, 0);
        i.decided = true;
        if (acked) runs(t, i.known); else refusedFor(t);
        boolean settled = false, again = false;
        if (acked && i.changeSeq < 0) {
            // Off the hold only when the entry it started is known: on a table an FD put in doubt,
            // a START may have hit the hold's own entry (the review of refusal-4, LOW).
            if (i.settlesHold && i.known && holdUnsettled) { holdUnsettled = false; settled = true; }
            // THE ONE WAY OUT OF NOT ANSWERING: an ACK, known to be this START's, of a setting
            // the app knows (invariant 149).
            if (i.known && deaf) { deaf = false; again = true; }
        }
        if (i.convergence >= 0) {
            if (acked) {
                if (!converging || i.convergence != convergence)
                    return with(new Outcome<T>(t, NONE, null, 0, settled, 0), again);
                return converged(t, settled, again);
            }
            // THE PUMP REFUSED WHAT WAS CONFIRMED, on a table written again because of a refusal
            // and put in no doubt since: nothing the app can write will be taken, and what the
            // pump runs is not what the screen shows.
            if (i.fresh && converging && i.convergence == convergence) return stop(t, settled);
            tableSuspect = true;
            // A convergence over, or one before it: the refusal counts all the same (the review
            // of refusal-3, LOW) - the table is in doubt and what runs is written again.
            if (!converging || i.convergence != convergence)
                return refusedElsewhere(t, planIdx, settled);
            if (i.attempt != attempts) {
                refusedSince = true;
                Outcome<T> o = new Outcome<T>(t, NONE, null, 0, settled, 0);
                o.afterRefusal = true;
                return o;
            }
            return retry(t, settled, true);
        }
        if (i.changeSeq >= 0) {
            if (acked) {
                PendingChange.Change c = changes.acked(i.changeSeq, planIdx);
                if (c != null && c.kind == PendingChange.HOLD) holdUnsettled = false;
                if (c != null) lastUnknown = null;           // it runs, and overtook all before it
                return new Outcome<T>(t, c == null ? NONE : COMMIT, c, 0, settled, changes.why());
            }
            PendingChange.Change c = changes.failed(i.changeSeq);
            if (c != null && c.kind == PendingChange.HOLD) holdUnsettled = false;
            if (c != null) return new Outcome<T>(t, REFUSED, c, 0, settled, 0);
            // Refused, but overtaken or no longer waiting: what runs after it is not known.
            return refusedElsewhere(t, planIdx, settled);
        }
        if (acked) return with(new Outcome<T>(t, NONE, null, 0, settled, 0), again);
        // A step's, a revert's, a resume's START refused: the pump carries on with what it ran,
        // which is not what the screen shows.
        return refusedElsewhere(t, planIdx, settled);
    }

    /** May be for any of `may` - which, not known. Nothing goes in force on it. */
    private Outcome<T> uncertain(List<T> may, boolean acked, int planIdx) {
        boolean allConverge = converging, allSettle = true, allKnown = true, allFresh = converging;
        boolean allNotLive = true;
        int sameUp = -2;
        for (int k = 0; k < may.size(); k++) {
            Info i = info.get(may.get(k));
            if (i == null || i.convergence < 0 || i.convergence != convergence) allConverge = false;
            if (i == null || !i.fresh || i.convergence != convergence) allFresh = false;
            if (i == null || i.changeSeq >= 0 || !i.settlesHold || !i.known) allSettle = false;
            if (i == null || i.changeSeq >= 0) allNotLive = false;
            if (i == null || !i.known) allKnown = false;
            int up = upOf(may.get(k));
            sameUp = sameUp == -2 ? up : sameUp == up ? up : -1;
        }
        if (acked) {
            // Whichever it was, every one of them pulls the same, on a table in no doubt: that
            // is what runs.
            boolean known = allKnown && sameUp >= 0;
            if (known) runsAll(may);
            boolean settled = false;
            // Whichever of them was taken, none of them is the Hold.
            if (allSettle && holdUnsettled) { holdUnsettled = false; settled = true; }
            boolean again = false;
            if (known && allNotLive && deaf) { deaf = false; again = true; }
            // Every one of them writes what was confirmed: whichever it was, that is what runs.
            if (allConverge) return converged(null, settled, again);
            return with(new Outcome<T>(null, NONE, null, 0, settled, 0), again);
        }
        // AN FD FOR ONE OF THEM - which, not known: it is a refusal all the same.
        // Every one of them the confirmed setting on a table just given after a refusal: the
        // pump refused THAT, whichever it was (the review of refusal-3, HIGH).
        if (allFresh) return stop(null, false);
        tableSuspect = true;
        for (int k = 0; k < may.size(); k++) {
            Info i = info.get(may.get(k));
            if (i != null && i.change != null) unknownChange(i.change);
        }
        if (converging) {
            refusedSince = true;
            Info head = info.get(may.get(0));
            // The attempt under way can no longer get an answer of its own: write it again.
            if (head != null && head.convergence == convergence && head.attempt == attempts
                    && !head.decided) {
                head.decided = true;
                return retry(null, false, true);
            }
            Outcome<T> o = new Outcome<T>(null, NONE, null, 0, false, 0);
            o.afterRefusal = true;
            return o;
        }
        return converge(null, planIdx, true, false);
    }

    private void unknownChange(PendingChange.Change c) {
        if (c != null) { lastUnknown = c; lost = c; }
    }

    /** An answer no START written within the horizon can be for: it came later than the
     *  horizon allows, or for a START written outside the run. Paired with nothing; and an
     *  attribution made meanwhile may be wrong - when in doubt, converge. */
    private Outcome<T> orphan(boolean acked, int planIdx) {
        if (!acked) tableSuspect = true;
        if (converging) {
            if (!acked) refusedSince = true;
            return new Outcome<T>(null, NONE, null, 0, false, 0);
        }
        return converge(null, planIdx, tableSuspect, false);
    }

    /** An FD that is not a live change's own refusal: converge on what was confirmed, the table
     *  first - or, under way, the next attempt writes the table first. */
    private Outcome<T> refusedElsewhere(T t, int planIdx, boolean settled) {
        tableSuspect = true;
        if (converging) {
            refusedSince = true;
            Outcome<T> o = new Outcome<T>(t, NONE, null, 0, settled, 0);
            o.afterRefusal = true;
            return o;
        }
        return converge(t, planIdx, true, settled);
    }

    private Outcome<T> stop(T t, boolean settled) {
        tableSuspect = true;
        converging = false;
        Outcome<T> stop = new Outcome<T>(t, STOP, lost, attempts, settled, 0);
        stop.forIdx = convergeFor;
        return stop;
    }

    /** `token`'s window passed after it was written, with no answer known to be its own. NEVER a
     *  refusal. */
    public Outcome<T> timedOut(T token, int planIdx, long now) {
        forget(now);
        Info i = info.get(token);
        if (i == null || i.decided) return new Outcome<T>(token, NONE, null, 0, false, 0);
        i.decided = true;
        if (i.convergence >= 0) {
            // Kept: an ack that can only be a converging START's still ends it (#answer).
            if (!converging || i.convergence != convergence || i.attempt != attempts)
                return new Outcome<T>(token, NONE, null, 0, false, 0);
            return retry(token, false, refusedSince || tableSuspect);
        }
        if (i.changeSeq >= 0) {
            PendingChange.Change c = changes.unanswered(i.changeSeq);
            // Its own answer never came: whether it runs is not known, overtaken or not.
            if (i.change != null && i.change.forIdx == planIdx) lastUnknown = i.change;
            if (c == null || c.forIdx != planIdx)            // overtaken, or its step is over
                return new Outcome<T>(token, NONE, null, 0, false, 0);
            if (converging) {                                // already writing what was confirmed
                lost = c;
                return new Outcome<T>(token, NONE, null, 0, false, 0);
            }
            return converge(token, c, planIdx, tableSuspect, false);
        }
        // A START that should take the pump off a Hold, unanswered: the Hold may still be up.
        if (i.settlesHold && holdUnsettled && !converging)
            return converge(token, planIdx, tableSuspect, false);
        return new Outcome<T>(token, NONE, null, 0, false, 0);
    }

    /** `token`'s frame was never written (dropped, or no link): nothing reached the pump. */
    public Outcome<T> neverWritten(T token, int planIdx) {
        replies.remove(token);
        refusedFor(token);
        Info i = info.remove(token);
        if (i == null) return new Outcome<T>(token, NONE, null, 0, false, 0);
        if (i.convergence >= 0) {
            if (!converging || i.convergence != convergence || i.attempt != attempts)
                return new Outcome<T>(token, NONE, null, 0, false, 0);
            return retry(token, false, refusedSince || tableSuspect);
        }
        if (i.changeSeq >= 0) {
            PendingChange.Change c = changes.failed(i.changeSeq);
            if (c != null && c.kind == PendingChange.HOLD) holdUnsettled = false;
            return new Outcome<T>(token, c == null ? NONE : NOT_SENT, c, 0, false, 0);
        }
        // The release of a Hold that never went out: the Hold may still be up.
        if (i.settlesHold && holdUnsettled && !converging)
            return converge(token, planIdx, tableSuspect, false);
        return new Outcome<T>(token, NONE, null, 0, false, 0);
    }

    /* ------------------------------------------------------------------ the convergence */

    /**
     * A START the caller had made to write what was confirmed was NOT written - the run moved on
     * before it could be (a posted START gone stale). It must not simply be dropped: what the
     * pump runs is still not known. Write what is confirmed NOW, for the step playing now, as a
     * convergence - the attempt under way again if it is this step's, else a new one (the review
     * of refusal-3, CRITICAL).
     */
    public Outcome<T> reconverge(int planIdx) {
        if (converging && convergeFor == planIdx) {
            Outcome<T> o = new Outcome<T>(null, CONVERGE, lost, attempts, false, 0);
            o.afterRefusal = refusedSince || tableSuspect;
            o.forIdx = planIdx;
            rewriteAskedAt = o.afterRefusal ? rewrites : -1;
            return o;
        }
        return converge(null, planIdx, tableSuspect, false);
    }

    /** A convergence no one live change asked for: it is about the Hold that may be up, or the
     *  last change whose own answer never came, if any. */
    private Outcome<T> converge(T token, int planIdx, boolean afterRefusal, boolean settled) {
        PendingChange.Change c = holdUnsettled ? holdChange : lastUnknown;
        return converge(token, c, planIdx, afterRefusal, settled);
    }

    private Outcome<T> converge(T token, PendingChange.Change c, int planIdx,
                                boolean afterRefusal, boolean settled) {
        converging = true;
        convergence++;
        attempts = 1;
        convergeFor = planIdx;
        refusedSince = false;
        lost = c;
        Outcome<T> o = new Outcome<T>(token, CONVERGE, c, 1, settled, 0);
        o.afterRefusal = afterRefusal;
        o.forIdx = planIdx;
        rewriteAskedAt = afterRefusal ? rewrites : -1;
        return o;
    }

    private Outcome<T> converged(T token, boolean settledAlready, boolean again) {
        converging = false;
        lastUnknown = null;
        boolean settled = settledAlready || holdUnsettled;   // it runs what was confirmed
        holdUnsettled = false;
        Outcome<T> o = new Outcome<T>(token, CONVERGED, lost, attempts, settled, 0);
        o.forIdx = convergeFor;
        return with(o, again);
    }

    private Outcome<T> retry(T token, boolean settled, boolean afterRefusal) {
        if (attempts >= MAX_CONVERGE) {
            converging = false;
            deaf = true;
            Outcome<T> o = new Outcome<T>(token, GIVE_UP, lost, attempts, settled, 0);
            o.forIdx = convergeFor;
            return o;
        }
        attempts++;
        refusedSince = false;
        Outcome<T> o = new Outcome<T>(token, CONVERGE, lost, attempts, settled, 0);
        o.afterRefusal = afterRefusal;
        o.forIdx = convergeFor;
        rewriteAskedAt = afterRefusal ? rewrites : -1;
        return o;
    }

    private static <T> Outcome<T> with(Outcome<T> o, boolean again) {
        o.answering = again;
        return o;
    }

    /* ------------------------------------------------------ what the pump may be running */

    /** The pump is known to run `t`'s START (acknowledged with certainty): on a table in no
     *  doubt, that is the anchor, and only what was written after it may be running too. */
    private void runs(T t, boolean known) {
        int k = indexIn(t);
        if (k < 0 || !known || since.get(k).up < 0) return;
        anchorUp = since.get(k).up;
        foldedUp = -1;
        for (int n = 0; n <= k; n++) since.remove(0);
    }

    /** One of `may` was taken, and every one of them pulls the same: that is what runs. */
    private void runsAll(List<T> may) {
        int last = -1;
        for (int k = 0; k < may.size(); k++) last = Math.max(last, indexIn(may.get(k)));
        if (last < 0) return;
        anchorUp = since.get(last).up;
        foldedUp = -1;
        for (int n = 0; n <= last; n++) since.remove(0);
    }

    /** `t` was refused (or never written): it never ran. */
    private void refusedFor(T t) {
        int k = indexIn(t);
        if (k >= 0) since.remove(k);
    }

    private int indexIn(T t) {
        for (int k = 0; k < since.size(); k++) if (since.get(k).token == t) return k;
        return -1;
    }

    private int upOf(T t) {
        int k = indexIn(t);
        return k < 0 ? -1 : since.get(k).up;
    }

    /* ---------------------------------------------------------------------- the rest */

    /** The convergence cannot go on (the step ended, a rest, the run's end): whatever is
     *  written next is what the pump runs. */
    public void cancelConverge() { converging = false; }

    /** The pump is known to be off a Hold another way (a watched vent confirmed it, the run
     *  ended through STOP). */
    public void holdSettledElsewhere() { holdUnsettled = false; }

    /** A new run, or the end of one: nothing is waiting any more. */
    public void reset() {
        changes.cancel();
        replies.clear();
        info.clear();
        holdUnsettled = false;
        holdChange = null;
        converging = false;
        refusedSince = false;
        lost = null;
        lastUnknown = null;
        deaf = false;
        tableSuspect = false;
        rewriteAskedAt = -1;
        anchorUp = -1;
        foldedUp = -1;
        since.clear();
    }

    /** Drops what is decided and can no longer be answered. */
    private void forget(long now) {
        for (Iterator<Map.Entry<T, Info>> it = info.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<T, Info> e = it.next();
            if (e.getValue().decided && !replies.waiting(e.getKey(), now)) it.remove();
        }
    }

    /** The STARTs still tracked (tests). */
    int tracked() { return info.size(); }
}
