package org.openpump;

/**
 * M1 - WHAT A CAPTURE TAKEN UNDER (OR AFTER) A STANDARDISATION HOLD IS, AT THE MOMENT IT IS
 * ASKED; "HOLD AGAIN"; AND THE WORDS FOR BOTH (the owner's items 4 and 5).
 *
 * THE TWO MINUTES ARE COUNTED UNTIL SAVE. They used to be checked once, when the hold screen
 * was left: waiting 2:01 there filed the reading as "not standardised", while 4:30 of typing
 * on the next screen, under the same hold, still counted - and the pump holds the whole time,
 * so the rule's own reason ("the pressure has been coming off it") was not true of this hold.
 * The owner chose "two minutes, counted until Save": every capture screen draws, and every
 * save files, {@link #state} asked THEN, never a verdict remembered from the hold screen.
 *
 * AT REST AND STANDARDISED ARE EQUAL (the owner's rule). Neither is a fallback; a reading is
 * only compared within its own method. So nothing here words a reading as flagged, lesser or
 * "not standardised": each state says what the reading IS and what it is compared with.
 *
 * Pure, so HoldWindowTest holds it; WiringCheck holds the Activity to asking it.
 */
public final class HoldWindow {
    private HoldWindow() { }

    /** How long a served dwell keeps a reading standardised, counted until Save. */
    public static final long WINDOW_MS = 120_000L;

    /** Holding, and the dwell at pressure has not been served (the end-of-run hold's
     *  "Measure now" tapped before the pump had held for the count). */
    public static final int SHORT   = 0;
    /** Holding, dwell served, inside the two minutes: saved now, it is standardised. */
    public static final int OPEN    = 1;
    /** Holding, dwell served, the two minutes have passed. */
    public static final int LAPSED  = 2;
    /** Nothing held on the cuff: never held, or vented - by Skip, by the hold's time limit,
     *  from the notification. The reading is an at-rest one. */
    public static final int AT_REST = 3;

    /**
     * THE ONE VERDICT. `holding` is a live hold: pressure commanded and its limit still
     * armed (SessionActivity#measureHoldLive) - once a vent has been sent for it, it is not
     * holding, even while telemetry is still confirming the fall. The dwell and the window
     * are Session#holdFinishedAt's, the one rule for "finished".
     */
    /** The hold's vent has been sent (a skip, its limit, the notification) and the pump has
     *  not yet reported the fall. Not held, and NOT YET AT REST: a stop is not done until
     *  telemetry shows the fall (SAFETY.md #2), so nothing says "vented" and nothing is
     *  filed until it does - or until the person confirms it by eye. */
    public static final int VENTING = 4;

    /**
     * THE ONE VERDICT. `holding` is a live hold: pressure commanded and its limit still
     * armed (SessionActivity#measureHoldLive). `ventPending` is that hold's vent sent and not
     * yet evidenced: once a vent has been sent a hold is not holding, and it is not at rest
     * either until the fall is seen. The dwell and the window are Session#holdFinishedAt's,
     * the one rule for "finished".
     */
    public static int state(boolean holding, boolean ventPending, long dwellMs, int dwellSec,
                            long windowEndsAt, long now) {
        if (!holding) return ventPending ? VENTING : AT_REST;
        if (!Session.holdComplete(dwellMs, dwellSec)) return SHORT;
        return Session.holdFinishedAt(dwellMs, dwellSec, windowEndsAt, now) ? OPEN : LAPSED;
    }

    public static boolean standardised(int state) { return state == OPEN; }
    public static boolean atRest(int state) { return state == AT_REST; }
    /** Whether a capture in this state may be saved at all: not while its vent is still
     *  being confirmed. */
    public static boolean mayFile(int state) { return state != VENTING; }

    /** What a session capture in this state files, through the one decision the screens
     *  draw their steppers from (Meas#captureMethod). */
    public static int method(int state, int chosenAtRest) {
        return Meas.captureMethod(standardised(state), atRest(state), chosenAtRest);
    }

    /** What is left of the two minutes, never negative; 0 when no window was opened. */
    public static long windowLeftMs(long windowEndsAt, long now) {
        return windowEndsAt <= 0 ? 0L : Math.max(0L, windowEndsAt - now);
    }

    /** THE TIME LEFT TO MEASURE, STANDARDISED: the two minutes, or the hold's own limit when
     *  that vents first - after Hold again it can be 30 s away while the window says two
     *  minutes. 0 when no window was opened; the window alone when no limit is armed. */
    public static long measureLeftMs(long windowEndsAt, long limitEndsAt, long now) {
        long win = windowLeftMs(windowEndsAt, now);
        if (limitEndsAt <= 0 || win <= 0) return win;
        return Math.min(win, Math.max(0L, limitEndsAt - now));
    }

    /* ---------------------------------------------------------------- hold again */

    public static final int AGAIN_OK           = 0;
    /** No hold is on the cuff: there is nothing to continue. A new hold starts only through
     *  startStd and its START gate. */
    public static final int AGAIN_NOT_HOLDING  = 1;
    /** The START gate refuses (another phase owns the pump, a stop is unconfirmed, ...). */
    public static final int AGAIN_GATED        = 2;
    public static final int AGAIN_NO_LINK      = 3;
    /** The pressure held is above the ceiling as it stands now. */
    public static final int AGAIN_OVER_CEILING = 4;
    /** The hold's time limit would vent it before the count and a minute to measure. */
    public static final int AGAIN_TOO_LATE     = 5;

    /** The time a hold again must leave after its count to measure in (30 s). The limit is
     *  never extended, so a hold again that cannot finish inside it is not offered. */
    public static final long AGAIN_MEASURE_MS = 30_000L;

    /**
     * MAY THE HOLD ON THE CUFF COUNT ITS DWELL AGAIN? "Hold again" commands nothing new: the
     * pump is already holding the pressure it was sent, clamped at that write, and it keeps
     * the limit it was armed with - never extended. It restarts the count at pressure, so a
     * reading taken after it is standardised again. It still asks every question the first
     * hold asked:
     *   - it continues only a hold that is live (a vented one is not continued: a new hold is
     *     startStd's, behind its gate);
     *   - the START gate, asked with every term except this very hold
     *     (`startRefusalWithoutThisHold`): a run, a pre-run phase, an assessment, a
     *     validation, an unconfirmed or exhausted stop all refuse it, as they refuse START;
     *   - the link, as startStd asks;
     *   - the ceiling as it stands now: a held pressure above it is never prolonged;
     *   - the hold's own limit: the count and {@link #AGAIN_MEASURE_MS} (30 s) must fit
     *     before it.
     */
    public static int againVerdict(boolean holding, int startRefusalWithoutThisHold,
                                   boolean linkReady, double heldKpa, int ceilKpa,
                                   long limitLeftMs, int dwellSec) {
        if (!holding) return AGAIN_NOT_HOLDING;
        if (startRefusalWithoutThisHold != Handoff.START_OK) return AGAIN_GATED;
        if (!linkReady) return AGAIN_NO_LINK;
        if (heldKpa > ceilKpa) return AGAIN_OVER_CEILING;
        if (limitLeftMs < dwellSec * 1000L + AGAIN_MEASURE_MS) return AGAIN_TOO_LATE;
        return AGAIN_OK;
    }

    /** Why a hold again was refused, in the person's words; null when it was not. */
    public static String againRefusalSentence(int verdict) {
        switch (verdict) {
            case AGAIN_NOT_HOLDING:
                return "The pump is no longer holding, so there is nothing to hold again.";
            case AGAIN_GATED:
                return "Something else is using the pump, or the last stop isn't confirmed yet.";
            case AGAIN_NO_LINK:
                return "Not connected to the pump.";
            case AGAIN_OVER_CEILING:
                return "The hold pressure is above your ceiling, so it isn't held any longer.";
            case AGAIN_TOO_LATE:
                return "The hold vents too soon to count again and measure.";
            default:
                return null;
        }
    }

    /* ---------------------------------------------------------------- words */

    /**
     * THE HOLD SCREEN'S OWN TITLE (the owner's pick on the leftovers, option A). "Hold complete
     * — measure now" was a passing message, drawn first over the after form's Save and then
     * over this very title; now the title says it, and nothing passes over anything. Once the
     * two minutes to measure have passed, "measure now" is no longer true: the title goes
     * back to "Standardising" and the card says time's up. A Settings preview says it is
     * complete, as its message did.
     */
    public static String screenTitle(boolean counted, boolean preview, boolean lapsed) {
        if (!counted || (lapsed && !preview)) return "Standardising";
        return preview ? "Preview complete" : "Hold complete — measure now";
    }

    /** The capture screens' and the hold screen's card once the two minutes have passed. */
    public static final String LAPSED_TITLE = "Time’s up for this hold";

    public static String lapsedSentence(String heldAt, int dwellSec) {
        return "The two minutes to measure have passed and the pump is still holding "
            + heldAt + ": hold again for " + dwellSec + " s to take a standardised reading, "
            + "or measure as it is — kept on its own, apart from your standardised "
            + "readings.";
    }

    public static String shortTitle(int dwellSec) {
        return "Held for less than " + dwellSec + " s";
    }

    public static String shortSentence(String heldAt, int dwellSec) {
        return "The pump hasn’t held " + heldAt + " for " + dwellSec + " s yet: hold for "
            + dwellSec + " s to take a standardised reading, or measure as it is — kept "
            + "on its own, apart from your standardised readings.";
    }

    /** How the vent is known: the pump reported the fall; the pump, still talking, reads no
     *  vacuum at all (VENTED_INFERRED - a strong sign, gated on as a vent, but never
     *  announced as a confirmed one); or the person saw the cuff vented. */
    public static final int VENT_REPORTED = 0;
    public static final int VENT_INFERRED = 1;
    public static final int VENT_BY_EYE   = 2;

    /** What is known about the vent, in words - never more than is known. (Public for
     *  HoldHandOff#endedSentence, so a hold vented for another app is said the same way.) */
    public static String vented(int how) {
        return how == VENT_BY_EYE ? "you confirmed the cuff is vented"
             : how == VENT_INFERRED ? "the pump reads no vacuum"
             : "the pump vented";
    }

    /** The chip after "Skip the hold and vent" (item 5's drawn "after") - shown only once the
     *  vent is evidenced (AT_REST). Measure again at rest: nothing typed under the hold is
     *  used for it. */
    public static String skippedSentence(int how) {
        return vented(how) + ", so this is an at-rest reading — compared with your "
            + "other at-rest readings taken the same way";
    }

    /** The chip after the hold's time limit vented the pump (C8's event; now at rest) - shown
     *  only once the vent is evidenced. */
    public static String limitSentence(int how) {
        return "The hold reached its time limit and " + vented(how) + ", so this is an "
            + "at-rest reading — compared with your other at-rest readings taken the "
            + "same way. Measure again at rest.";
    }

    /** The toast when a capture drawn while venting turns out, at Save, to be at rest. */
    public static String ventedToast(int how) {
        return how == VENT_BY_EYE ? "You confirmed the cuff is vented — this is now an "
                                    + "at-rest reading"
             : how == VENT_INFERRED ? "The pump reads no vacuum — this is now an at-rest "
                                    + "reading"
             : "The vent is confirmed — this is now an at-rest reading";
    }

    /**
     * A NUMBER TYPED INTO THE BOX APPLIES ONLY TO THE CAPTURE IT WAS TYPED FOR. The typed-value
     * box can outlive the hold - the limit fires while it is open - and a number typed under
     * a hold must never land in the at-rest capture that follows (the owner's rule: each
     * kind is compared only with its own). Any change of what the capture is refuses; the
     * person types it again.
     */
    public static boolean typedStillApplies(int openedState, int nowState) {
        return openedState == nowState;
    }

    /** The chip while the vent is being confirmed: what happens, and nothing claimed. */
    public static final String VENTING_SENTENCE = "the pump was told to vent. This is taken "
        + "at rest once it reports the pressure falling — until then it cannot be saved.";

    /** The snack for a reading saved while the pump held it outside the count or the two
     *  minutes: what it is, and nothing about what it is not. */
    public static String keptSnack(boolean baseline) {
        return (baseline ? "Baseline" : "Reading") + " logged · held, kept on its own";
    }
}
