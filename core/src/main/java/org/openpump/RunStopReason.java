package org.openpump;

/**
 * (the safety review of the in-run Hold's limit) WHY A RUN STOPPED ON ITS OWN, IN ONE LINE
 * THAT STAYS.
 *
 * The Hold's limit and the two-hour stop end a run through STOP's own path, and said why in a
 * snackbar that is gone in three seconds - so a limit that fired with the phone locked left the
 * person with "Session stopped" and no reason. The reason is now kept with the run's ending:
 * filed on the record (Model.Sess#stopWhy, #stopLimSec), so the summary says it in a line that
 * stays - reopened from History too - and given to the closing stop's watch, so the venting
 * notice says it (HoldForeground#ventingText). The record keeps the fact, not the words: the
 * words are these, in one place. InRunHoldTest pins them; WiringCheck invariant 134 the wiring.
 */
public final class RunStopReason {

    private RunStopReason() { }

    /** No reason filed - a run that reached its end, or a stop filed before R11-6: no line. */
    public static final int WHY_NONE = 0;
    /** The in-run Hold reached its limit (the owner's decision). */
    public static final int WHY_HOLD_LIMIT = 1;
    /** Two hours sealed (D2, Plan#grossCapReached). */
    public static final int WHY_TWO_HOURS = 2;
    /** The pump refused the START of what the screen showed, on a table just written again
     *  after an earlier refusal (LiveLink#STOP): nothing it was sent was being taken. */
    public static final int WHY_PUMP_REFUSED = 3;

    /* R11-6 - EVERY OTHER ENDING SAYS WHY TOO. A run stopped by the person, by the vent on a
     * lost link, by a pump never reached, by Android closing the app or by a safety stop said
     * only "Session stopped". Each now files its own reason, set where the run ends, and the
     * summary says it from the record. The venting notice is unchanged (saysInNotice). */
    /** The person ended it: STOP, End, Back, the notification's STOP. */
    public static final int WHY_STOP = 4;
    /** The link to the pump was lost and the auto-stop vented it. */
    public static final int WHY_LINK_LOST = 5;
    /** The pump stopped answering before anything was commanded. */
    public static final int WHY_PUMP_NOT_FOUND = 6;
    /** The screen was destroyed under a live run (Android closed the app). */
    public static final int WHY_APP_CLOSED = 7;
    /** A question (the seal check's result, the guided start's ask, Reconnected) stood
     *  unanswered for its minute, and the pump was released. */
    public static final int WHY_UNANSWERED = 8;
    /** The guided start's pressure never held within its limit. */
    public static final int WHY_NO_HOLD = 9;
    /** The pump check (self-test) ended the run. */
    public static final int WHY_SAFETY = 10;
    /** "Finish here" at the yield target. */
    public static final int WHY_AT_TARGET = 11;
    /** The app left the screen with no background service to carry the run (onStop). */
    public static final int WHY_LEFT_APP = 12;

    /** "Stopped: the hold reached its limit (5:00)". */
    public static String holdLimit(int holdMaxSec) {
        return "Stopped: the hold reached its limit (" + Model.Fmt.t(InRunHold.limitSec(holdMaxSec))
            + ")";
    }

    public static final String TWO_HOURS = "Stopped: the two-hour limit";

    public static final String PUMP_REFUSED =
        "Stopped: the pump refused the pressure on screen, twice - the cuff was vented";

    public static final String STOP = "Stopped: you ended the session";
    public static final String LINK_LOST =
        "Stopped: the link to the pump was lost, so the pump was told to vent";
    public static final String PUMP_NOT_FOUND =
        "Stopped: the pump could not be reached before the session started";
    public static final String APP_CLOSED = "Stopped: Android closed the app during the session";
    public static final String UNANSWERED =
        "Stopped for safety: a question went unanswered for a minute, so the pump was released";
    public static final String NO_HOLD =
        "Stopped for safety: the pressure did not hold at the start, so the pump was released";
    public static final String SAFETY = "Stopped for safety: the pump check ended the run";
    public static final String AT_TARGET = "Ended early: you chose Finish here at the target";
    public static final String LEFT_APP =
        "Stopped: the app was left and the session could not carry on in the background";

    /** The line for a filed reason, or null when there is none to say - WHY_NONE, or a code
     *  this build does not know (a record from a newer one): nothing is guessed. */
    public static String line(int why, int limitSec) {
        if (why == WHY_HOLD_LIMIT) return holdLimit(limitSec);
        if (why == WHY_TWO_HOURS) return TWO_HOURS;
        if (why == WHY_PUMP_REFUSED) return PUMP_REFUSED;
        if (why == WHY_STOP) return STOP;
        if (why == WHY_LINK_LOST) return LINK_LOST;
        if (why == WHY_PUMP_NOT_FOUND) return PUMP_NOT_FOUND;
        if (why == WHY_APP_CLOSED) return APP_CLOSED;
        if (why == WHY_UNANSWERED) return UNANSWERED;
        if (why == WHY_NO_HOLD) return NO_HOLD;
        if (why == WHY_SAFETY) return SAFETY;
        if (why == WHY_AT_TARGET) return AT_TARGET;
        if (why == WHY_LEFT_APP) return LEFT_APP;
        return null;
    }

    /** Whether the venting notice names this reason. Only the three limits it always named:
     *  R11-6 changes the summary, not the notice (the other endings' own toasts and screens
     *  already say what happened while it happens). */
    public static boolean saysInNotice(int why) {
        return why == WHY_HOLD_LIMIT || why == WHY_TWO_HOURS || why == WHY_PUMP_REFUSED;
    }
}
