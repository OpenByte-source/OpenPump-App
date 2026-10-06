package org.openpump;

/**
 * H1 (the safety review) - A LIVE HOLD KEEPS THE SERVICE IN THE FOREGROUND.
 *
 * SAFETY.md #5: leaving a screen never orphans a pump under pressure. A standardisation hold
 * keeps the cuff at vacuum until something ends it, and the thing that ends it on a timer -
 * the hold limit - is a tick in this app's process. With no foreground service that process
 * could be cached and ended with the hold on: a routine that ended into a hold while the app
 * was in the background, or a screen that went off with the app on top before another app
 * (the lock-screen camera, an incoming call) came to the front with no callback to vent it.
 *
 * So a hold is one of RunService's reasons to live, beside a run: the service stays in the
 * foreground, with its partial wakelock, while a hold is held and while its vent is being
 * confirmed, so the process survives and the limit fires on time. It is a term of its own,
 * read by the service's lifetime ONLY - never by SessionActivity#liveWork, which onStop asks
 * to decide that leaving is not an abandonment, and which kill detection and the widget read
 * as "a run". Every exit from the app still vents a hold.
 *
 * These are the pure decisions (HoldForegroundTest); RunService and SessionActivity apply
 * them, and WiringCheck invariants 92 and 93 hold them to it.
 */
public final class HoldForeground {

    private HoldForeground() { }

    /** No hold for the service to keep. */
    public static final int NONE = 0;
    /** The hold is commanded and its limit is running. */
    public static final int HELD = 1;
    /** The hold's stop was sent and is being retried and confirmed. */
    public static final int VENTING = 2;
    /** (the second re-review) The vent watch gave up - the fall was never seen - and the
     *  person has not yet been shown the give-up. NOT the end of the hold. */
    public static final int UNCONFIRMED = 3;
    /** The same, with the give-up in front of the person, who has not yet acted on it. */
    public static final int UNCONFIRMED_SEEN = 4;

    /**
     * Where a hold stands, for the service.
     *
     * AN EXHAUSTED, UNCONFIRMED VENT IS NOT THE END OF THE HOLD (the second re-review). Home
     * during a hold vents it; if the fall is never seen the watch gives up with its question on
     * a screen nobody is looking at. This used to read NONE, and within a second the hold's
     * trace, its foreground and its "disconnect the tubing" notice were gone. Now the hold ends
     * only with a confirmed vent or the person's own eyes (both clear `held`) - a retry they ask
     * for from the give-up puts it back to VENTING.
     *
     * @param held            Session#heldKpa is set - cleared only by telemetry showing the
     *                        fall (or the person's own eyes).
     * @param limitRunning    the hold limit is armed (holdEndsAt > 0): the hold is commanded.
     * @param ventUnevidenced a stop was sent and not confirmed (Handoff#ventUnevidenced).
     * @param ventExhausted   the vent watch ran out of retries and asked the person.
     * @param giveUpSeen      that question has been in front of the person (the screen was
     *                        started while it showed).
     */
    public static int phase(boolean held, boolean limitRunning, boolean ventUnevidenced,
                            boolean ventExhausted, boolean giveUpSeen) {
        if (!held) return NONE;
        if (limitRunning) return HELD;
        if (ventUnevidenced && !ventExhausted) return VENTING;
        if (ventUnevidenced) return giveUpSeen ? UNCONFIRMED_SEEN : UNCONFIRMED;
        return NONE;
    }

    /**
     * (the safety review of the in-run Hold's limit) WHETHER THE CUFF IS OWED A VENT THE SERVICE
     * MUST WATCH - phase()'s first term. A hold outstanding; or, with nothing live, a stop that
     * was sent and not yet evidenced. The second is how every run ends - STOP, the Hold's
     * limit, the two-hour stop, the link-loss auto-stop, the plan's end - and it used to read
     * NONE the moment the run was over: the service stopped, its wakelock went and the
     * retries ran in a background process that can sleep, with an in-app question as the only
     * give-up. Now an ended run's unseen stop is VENTING, UNCONFIRMED and UNCONFIRMED_SEEN
     * exactly as a hold's is. During a run the run keeps the service itself.
     */
    public static boolean ventOwed(boolean held, boolean liveWork, boolean ventUnevidenced) {
        return held || (!liveWork && ventUnevidenced);
    }

    /** The service's lifetime: a run (liveWork), or a hold that is held, whose retry is
     *  running, or whose give-up the person has not yet been shown. Once they have it in front
     *  of them and no retry runs, the service goes - its notice stays (noticeStays). */
    public static boolean keepsService(boolean liveWork, int holdPhase) {
        return liveWork || holdPhase == HELD || holdPhase == VENTING || holdPhase == UNCONFIRMED;
    }

    /** The service is going but the hold is not over: its "couldn't confirm" notice stays, and
     *  goes only with a confirmed vent, the person's eyes, or a retry. */
    public static boolean noticeStays(int holdPhase) {
        return holdPhase == UNCONFIRMED_SEEN;
    }

    /**
     * (the incognito safety review, C1) WHETHER "VENT NOT CONFIRMED" RINGS AS IT IS POSTED -
     * once per give-up. `shownNotice` is the hold notice up now (a phase; UNCONFIRMED_SEEN is
     * shown as UNCONFIRMED). Posted over anything else - the quiet venting notice it replaces
     * on the same id above all, or nothing - it rings. Posted again over itself (the service
     * keeping it in the foreground, handing it over, leaving it behind) it is the same give-up
     * and does not ring twice. A retry puts the venting notice back, so the next give-up rings.
     */
    public static boolean unconfirmedRings(int shownNotice) {
        return shownNotice != UNCONFIRMED && shownNotice != UNCONFIRMED_SEEN;
    }

    /** Whether a hold start (RunService's ACTION_HOLD_FG) enters hold mode: only with a live
     *  screen behind it and a hold that keeps the service. A redelivered start with no screen
     *  stops - and touches nothing the next launch reads. */
    public static boolean holdStartEnters(boolean liveScreen, int holdPhase) {
        return liveScreen && keepsService(false, holdPhase);
    }

    /**
     * Whether a routine that has just ended goes on into the after-measurement hold. Every
     * term but the last is the one finishSession already asked (an abort is never answered
     * with more pressure; a manual run has no after measurement; no baseline, no link or no
     * pressure set leaves nothing to hold for). The last is the safety review's: with the
     * screen not started nobody is looking, so the run vents through its ordinary watch
     * instead of arming a hold in the background.
     */
    public static boolean runEndsIntoHold(boolean aborted, boolean manual, boolean wanted,
                                          boolean linkReady, boolean pressureSet,
                                          boolean haveBaseline, boolean screenStarted) {
        return !aborted && !manual && wanted && linkReady && pressureSet && haveBaseline
            && screenStarted;
    }

    /**
     * (re-review 4) THE VENT THAT COULD NOT BE SENT, SENT WHEN IT CAN BE. A hold whose stop was
     * wanted - its limit is down, the stop unevidenced - while the link was down, and whose
     * watch has run out of retries (or was stood down keeping its evidence), is owed a vent
     * the moment the link is ready again: through the normal vent watch, not at a limit that
     * is no longer running. Not while the watch is still retrying (its next retry goes over the
     * link that just came back) and not under a run, whose own stops are the run's.
     */
    public static boolean ventOwedOnLinkReturn(boolean held, boolean limitRunning,
                                               boolean ventUnevidenced, boolean watchRetrying,
                                               boolean runLive) {
        return held && !limitRunning && ventUnevidenced && !watchRetrying && !runLive;
    }

    /* ---------------------------------------------------------------- the notification */

    /** While the hold is held - and, with the service in the foreground, the countdown is
     *  one this process lives to keep. */
    public static String heldTitle(String pressure) {
        return "Pressure held at " + pressure;
    }

    public static String heldText(long leftSec) {
        long s = Math.max(0L, leftSec);
        return "Vents on its own in " + s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    /** Once the watch gave up: what is true, and what to do (the second re-review). */
    public static final String UNCONFIRMED_TITLE = "Vent not confirmed";
    public static final String UNCONFIRMED_TEXT =
        "The app couldn't confirm the pump vented. Disconnect the tubing at the cuff.";

    /** While the vent is being confirmed: what is happening, and nothing claimed. */
    public static final String VENTING_TITLE = "Venting the pump";
    public static final String VENTING_TEXT = "Waiting for it to report the pressure falling. "
        + "If pressure does not clear, disconnect the tubing at the cuff.";

    /** (the safety review) The venting notice with the reason the run stopped, when a limit
     *  stopped it ("Stopped: the hold reached its limit (5:00)", RunStopReason) - a stop that
     *  happened with the phone locked is explained where the person looks first. */
    public static String ventingText(String reason) {
        if (reason == null || reason.trim().isEmpty()) return VENTING_TEXT;
        return reason.trim() + ". " + VENTING_TEXT;
    }
}
