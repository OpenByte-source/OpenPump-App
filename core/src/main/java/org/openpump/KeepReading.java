package org.openpump;

/**
 * KEEP THE READING (the owner's decision): a hold that ends without the person choosing it -
 * the link to the pump lost during it, or the app left during it - no longer throws the
 * reading away on the way to Today.
 *
 * Both still vent exactly as before (onHoldLinkLost's vent, onStop's vent): nothing about the
 * pressure changes, and nothing is commanded on the way back. What changes is where the
 * person lands: the SAME capture they were taking - on screen, or open behind the hold screen
 * ("Hold again", the log door's "Standardise and measure") - now an at-rest one:
 *   - its photos are kept, each with how it was taken (Shot records that per photo);
 *   - numbers typed under the hold are cleared (M1's held-draft rule: a number typed under a
 *     hold never becomes an at-rest number);
 *   - nothing can be saved until the vent is evidenced or the person says they can see the
 *     cuff is vented (HoldWindow#VENTING).
 * When there is no capture to come back to - none was open (the session's own first hold),
 * the reading was already saved, or its id is unknown - it is Today, as before.
 *
 * Pure, so KeepReadingTest holds it; WiringCheck invariants 110, 111 and 122-124 hold the
 * Activity to it.
 */
public final class KeepReading {
    private KeepReading() { }

    /** Why the hold ended without the person choosing it. */
    public static final int LINK_LOST = 1;
    public static final int LEFT_APP  = 2;

    /**
     * Where the person lands: the capture open on `screen` (a capture screen itself, or one
     * open behind a detour - PhotoDiscard#captureAfter), if it can still be resumed
     * (PhotoDiscard#captureToResume: not saved, its id known); otherwise
     * {@link PhotoDiscard#NO_CAPTURE}, which is Today.
     */
    public static int landing(int screen, int openCapture, String captureId,
                              Model.MeasLog log) {
        int open = PhotoDiscard.captureAfter(openCapture, screen);
        return PhotoDiscard.captureToResume(open, captureId, captureId, log);
    }

    /** The chip's title while the link is lost and the vent cannot be confirmed. */
    public static final String LINK_LOST_TITLE = "Link to the pump lost";

    /** What is true while the link is lost: the vent was sent and cannot be confirmed - no
     *  claim that it happened - and the two ways on. */
    public static final String LINK_LOST_SENTENCE = "The pump stopped talking during the "
        + "hold, so the vent was sent but can't be confirmed. Reconnect the pump, or if you "
        + "can see the cuff is vented, say so. Until then this can't be saved.";

    /** The chip once the vent is evidenced: why the hold ended, how the vent is known, and
     *  that this is now an at-rest reading, measured again at rest. */
    public static String atRestSentence(int reason, int how) {
        String why = reason == LINK_LOST
            ? "The link to the pump was lost during the hold, and "
            : "You left the app during the hold, and ";
        return why + HoldWindow.vented(how) + ", so this is an at-rest reading — "
            + "compared with your other at-rest readings taken the same way. Measure again "
            + "at rest.";
    }

    /**
     * (the review, MEDIUM) WHERE THE PERSON'S EYES ARE OFFERED AT ONCE. After a hold whose
     * link was lost, while the link is still down, no retry can land, so "I can see the cuff
     * is vented" is offered straight away - but ONLY on the kept capture's own screen
     * (`screen` is a capture and it is the open one), and only until it is said. Every other
     * vent screen - the summary, the run's link-lost screen, the release gate, the validation
     * screens - keeps the retries' own rule (offered once they are spent), so a flag left
     * behind can never bring the button up early there.
     */
    public static boolean eyeOfferedAtOnce(boolean linkLostKept, int screen, int openCapture,
                                           boolean linkReady, boolean alreadySaid) {
        return linkLostKept && !linkReady && !alreadySaid && PhotoDiscard.isCapture(screen)
            && screen == openCapture;
    }

    /**
     * Whether the capture's hold is still venting (HoldWindow#VENTING - no save): not while
     * the hold is live; otherwise while the pressure is held or an ended hold's stop is
     * unevidenced. (the review, LOW 1) The person's eyes in the kept capture end it for the
     * SAVE only: they unlock saving the at-rest reading, and change nothing else - the pressure
     * stays held and the stop unevidenced, so the vent still owed goes out when the link is
     * back (HoldForeground#ventOwedOnLinkReturn, invariant 119), and START stays gated until
     * it is evidenced.
     */
    public static boolean ventPending(boolean live, boolean held, boolean ended,
                                      boolean unevidenced, boolean keptSeenByEye) {
        return !live && !keptSeenByEye && (held || (ended && unevidenced));
    }

    /**
     * (the review, LOW 2) The screen is finishing - the task swiped away - with the kept
     * capture still open: it is discarded exactly as leaving it would be. A configuration
     * change (not finishing) keeps it. A process killed without onDestroy cannot be handled
     * here; its photos stay until the next capture's discard.
     */
    public static boolean discardOnDestroy(boolean finishing, boolean kept, int openCapture) {
        return finishing && kept && PhotoDiscard.isCapture(openCapture);
    }
}
