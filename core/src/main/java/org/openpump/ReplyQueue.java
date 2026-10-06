package org.openpump;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * THE PUMP'S ANSWERS TO STARTS, AND WHICH STARTS EACH ONE MAY BE FOR.
 *
 * The pump answers a START with two bytes, `2C 01` or `2C FD`, that name no slot and no
 * sequence. Answers go missing - the owner's journal of 22 Sep has 343 STARTs and 319 answers,
 * lost in bursts - and they come late: 26 to 1033 ms after the START in that journal. Paired
 * with the oldest START still waiting, one lost answer made the next START's count for the
 * wrong one; with a tombstone that ate one late answer for 2 s, an answer 3.2 s late shifted
 * every pairing after it by one, and a lowering the pump had refused was committed on a
 * borrowed `01` (the safety review of refusal-2, seed 910: pump 27, screen 25).
 *
 * SO AN ANSWER IS NOT PAIRED: IT IS GIVEN EVERY START IT MAY BE FOR (#answer), and it is known
 * to be one START's only when that is the only one. Two facts bound the set:
 *
 *   - ANSWERS KEEP THE ORDER THE STARTS WERE WRITTEN IN. The pump carries its frames out one at
 *     a time and answers each as it does. So an answer is for a START no older than the oldest
 *     one the previous answer may have been for, plus one - an answer never goes back.
 *   - AN ANSWER COMES WITHIN THE HORIZON OF ITS START'S WRITE, or not at all. The horizon is
 *     five times the latest answer in the journal (LiveLink's caller sets it). A START written
 *     longer ago than that can no longer be answered, and drops out.
 *
 * And, as before: an answer is never for a START not yet written (it cannot be), and a later
 * write-done retires an earlier START still unwritten (writes leave in order: it was dropped).
 *
 * An answer no written START within the horizon can be for is an ORPHAN: #answer returns an
 * empty list and moves nothing - it came later than the horizon allows, or for a START written
 * outside the run, and it is paired with nothing. (What the caller does with that doubt is
 * LiveLink's.)
 *
 * #quiet says when nothing written can still be answered: only then is a new live change's
 * answer certain to be its own (the caller holds changes back until it is).
 *
 * Pure: the caller passes the clock (a monotonic one). ReplyQueueTest.
 */
public final class ReplyQueue<T> {

    private static final class Entry<T> {
        final T token;
        final boolean placeholder;
        final long queuedAt;
        long sentAt = -1;
        Entry(T t, boolean p, long q) { token = t; placeholder = p; queuedAt = q; }
    }

    /** Every START that may still be answered, in write order - from the oldest the next
     *  answer can be for. */
    private final ArrayDeque<Entry<T>> q = new ArrayDeque<Entry<T>>();
    private final long horizonMs, unsentMs;
    private final int max;

    /** @param horizonMs how long after its write a START may still be answered;
     *  @param unsentMs how long an entry never written is kept;
     *  @param max the most entries kept (the oldest go first). */
    public ReplyQueue(long horizonMs, long unsentMs, int max) {
        this.horizonMs = horizonMs; this.unsentMs = unsentMs; this.max = max;
    }

    /** How long after its write a START may still be answered. */
    public long horizonMs() { return horizonMs; }

    /** A START is going out now. `placeholder`: it opened no row. */
    public void enqueue(T token, boolean placeholder, long now) {
        q.addLast(new Entry<T>(token, placeholder, now));
        while (q.size() > max) q.pollFirst();
    }

    /** `token`'s frame left the phone. Any earlier entry still unwritten was dropped. */
    public void sent(T token, long now) {
        boolean listed = false;
        for (Iterator<Entry<T>> it = q.iterator(); it.hasNext(); )
            if (it.next().token == token) { listed = true; break; }
        if (!listed) return;
        for (Iterator<Entry<T>> it = q.iterator(); it.hasNext(); ) {
            Entry<T> e = it.next();
            if (e.token == token) { if (e.sentAt < 0) e.sentAt = now; return; }
            if (e.sentAt < 0) it.remove();
        }
    }

    /** Forgets `token` - its frame was never written. */
    public boolean remove(T token) {
        for (Iterator<Entry<T>> it = q.iterator(); it.hasNext(); )
            if (it.next().token == token) { it.remove(); return true; }
        return false;
    }

    /**
     * The pump answered a START. Returns EVERY START it may be for, oldest first: the written
     * ones still within the horizon, from the oldest the order allows. One START: it is that
     * one's. Several: it is one of theirs, and which is not known. None: an orphan, and nothing
     * moves. Otherwise the oldest of them can no longer be answered after this one (the order),
     * and drops out.
     */
    public List<T> answer(long now) {
        prune(now);
        List<T> may = new ArrayList<T>();
        for (Iterator<Entry<T>> it = q.iterator(); it.hasNext(); ) {
            Entry<T> e = it.next();
            if (e.sentAt < 0 || e.sentAt > now) break;       // not written yet: nor any after it
            may.add(e.token);
        }
        if (may.isEmpty()) return Collections.<T>emptyList();
        q.pollFirst();
        return may;
    }

    /** Is there nothing written, or about to be, that may still be answered? */
    public boolean quiet(long now) {
        prune(now);
        return q.isEmpty();
    }

    /** May `token` still be answered? */
    public boolean waiting(T token, long now) {
        prune(now);
        for (Iterator<Entry<T>> it = q.iterator(); it.hasNext(); )
            if (it.next().token == token) return true;
        return false;
    }

    public int size() { return q.size(); }

    public void clear() { q.clear(); }

    /** Drops what can no longer be answered: written longer ago than the horizon, or never
     *  written for the unsent expiry. */
    private void prune(long now) {
        for (Iterator<Entry<T>> it = q.iterator(); it.hasNext(); ) {
            Entry<T> e = it.next();
            if (e.sentAt >= 0 ? now - e.sentAt > horizonMs : now - e.queuedAt > unsentMs)
                it.remove();
        }
    }
}
