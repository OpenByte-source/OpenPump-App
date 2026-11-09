package org.openpump;

/**
 * THE RESUME OFFER WAITS FOR THE QUESTION (the live GrowthTrack test, 9 Nov 2026, finding 1).
 *
 * STOP on a routine the plan did not write files the part and asks "Count this toward
 * training?" in a dialog, and the "Stopped and vented at step N · Resume" message was shown at
 * the same moment, dimmed under that dialog. It goes after Snack#HOLD_MS_ACTIONABLE, so by the
 * time the question was answered the way back was gone: a person reading the dialog missed it.
 *
 * So while that question is coming or up, the offer is kept, and shown - with its full time -
 * once the question has gone, answered or dismissed. With no question (a plan session, a
 * session with nothing to count, any other STOP), it is shown at once, exactly as before.
 * Only the moment it appears changes: what Resume does, and STOP's vent, are untouched.
 *
 * Holds one offer at a time; a newer STOP's offer replaces one still kept. Pure.
 */
public final class ResumeOffer<T> {
    private T kept;

    /** STOP's offer: given back to be shown now, or kept (null returned) while a question is
     *  coming or up. */
    public T offer(T offer, boolean questionComing) {
        if (!questionComing) { kept = null; return offer; }
        kept = offer;
        return null;
    }

    /** The question has gone - answered, dismissed, or never shown after all: the kept offer,
     *  once, or null when none is kept. */
    public T questionGone() {
        T o = kept;
        kept = null;
        return o;
    }

    /** Whether an offer is waiting for the question. */
    public boolean waiting() { return kept != null; }
}
