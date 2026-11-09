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
        note = null;                         // a new STOP: no note from an earlier one
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

    /* ---- A NOTE FOR THE SAME MOMENT (follow-up, 9 Nov 2026) ----
     *
     * Answering Count says "Counted toward this week" in a message of its own, and only one
     * message shows at a time: the Resume offer, shown as the question went, replaced it a
     * moment later - or it replaced the offer. So the note goes WITH the offer, in one message
     * that keeps Resume and its full time: kept with an offer that is waiting, or the offer on
     * screen is shown again carrying it. With no offer in play the note is shown alone. */

    /** How a note is shown: alone, with the waiting offer, or by showing the offer again. */
    public static final int ALONE = 0, WITH_OFFER = 1, AGAIN = 2;

    private T onScreen;
    private long onScreenUntil;
    private String note;

    /** The offer is on screen from `now` for `holdMs`. */
    public void shown(T offer, long now, long holdMs) {
        onScreen = offer;
        onScreenUntil = now + Math.max(0L, holdMs);
    }

    /** Resume was tapped: the offer is done with. */
    public void spent() { onScreen = null; }

    /** A note for this moment. WITH_OFFER and AGAIN keep it for the offer's message
     *  ({@link #takeNote}); AGAIN asks the caller to show {@link #again} now. */
    public int note(String text, long now) {
        if (text == null || text.isEmpty()) return ALONE;
        if (kept != null) { note = text; return WITH_OFFER; }
        if (onScreen != null && now < onScreenUntil) { note = text; return AGAIN; }
        return ALONE;
    }

    /** The offer on screen, to be shown again with the note. */
    public T again() { return onScreen; }

    /** The note for the offer's message, once; null when there is none. */
    public String takeNote() {
        String n = note;
        note = null;
        return n;
    }

    /** The offer's message with the note before it: "Counted toward this week. Stopped and
     *  vented at step 2." */
    public static String withNote(String note, String message) {
        return note == null || note.isEmpty() ? message : note + ". " + message;
    }
}
