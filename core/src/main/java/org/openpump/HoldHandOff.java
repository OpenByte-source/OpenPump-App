package org.openpump;

/**
 * H1 - NO OTHER APP OVER A HELD CUFF.
 *
 * A standardisation hold keeps the cuff at vacuum until something ends it, and the thing
 * that ends it on a timer - the hold limit - runs in this app's process. Handing the phone
 * to another app (the phone's camera app when the in-app camera cannot run, the photo
 * picker, a share sheet, a system screen) puts that process in the background, where
 * Android may end it. The hold then has no limit at all, its notification freezes on
 * "Vents on its own in m:ss", and its RELEASE starts a process with no screen behind it.
 * The app used to allow exactly that for its own launches, suppressing the give-up check
 * so the hold would survive the camera.
 *
 * So the rule is the reverse: a hold is vented, through the normal confirmed vent path,
 * BEFORE another app takes the phone, and the person is told so in plain words ("The pump
 * vents first, then the camera opens"). The in-app camera is not another app - it draws in
 * this Activity - and keeps working under a hold exactly as before.
 *
 * The decisions are pure and live here so HoldHandOffTest can pin them. SessionActivity
 * owns the one door that applies them (ventFirst), and WiringCheck invariants 74 and 75 hold
 * every launch in the app to that door and the door to the confirmed vent.
 */
public final class HoldHandOff {

    private HoldHandOff() { }

    /* ------------------------------------------------------------ before the hand-off */

    /** No hold on the cuff: the other app opens now. */
    public static final int NOW = 0;
    /** A hold is still commanded: ask, then vent through the confirmed path, then open. */
    public static final int ASK_THEN_VENT = 1;
    /** The hold's vent was already sent and is not confirmed yet: nothing to ask - the hold
     *  is ending - but the other app still waits for the confirmation. */
    public static final int WAIT_FOR_VENT = 2;

    /**
     * What a request to hand the phone to another app must do first.
     *
     * @param holdOutstanding Session#heldKpa is set - the hold is commanded, or its vent was
     *                        sent and telemetry has not shown the fall yet (it is cleared
     *                        only by that evidence).
     * @param ventUnevidenced a stop was sent and not confirmed (Handoff#ventUnevidenced).
     */
    public static int before(boolean holdOutstanding, boolean ventUnevidenced) {
        if (!holdOutstanding) return NOW;
        return Handoff.holdCommanded(holdOutstanding, ventUnevidenced)
            ? ASK_THEN_VENT : WAIT_FOR_VENT;
    }

    /** (the final review, I2) The link is down, and this hand-off is one of the two that fix
     *  it (the phone's Bluetooth screen, the app's own permissions): it opens now, and the
     *  hold keeps everything it has - its trace, the foreground and the notice that tells the
     *  truth. Nothing is claimed vented. */
    public static final int OPEN_UNREACHABLE = 3;

    /**
     * What a hand-off that FIXES THE LINK must do first (the final review, I2). The stop cannot
     * be sent over a link that is down: venting first waits on a watch that reads UNCONFIRMED
     * at once, and keeps shut the only way the link - and so the vent - can come back. With
     * the link down it opens (OPEN_UNREACHABLE); with it up, the vent-first rule applies as to
     * every other hand-off.
     *
     * @param linkDown the stop cannot reach the pump now (the link is not ready).
     */
    public static int beforeLinkFix(boolean holdOutstanding, boolean ventUnevidenced,
                                    boolean linkDown) {
        if (!holdOutstanding) return NOW;
        if (linkDown) return OPEN_UNREACHABLE;
        return before(holdOutstanding, ventUnevidenced);
    }

    /* --------------------------------------------------- while the vent is confirmed */

    /** Keep waiting: the evidence window has not answered yet. */
    public static final int WAIT = 0;
    /** Telemetry shows the fall: the other app may open. */
    public static final int OPEN = 1;
    /** The window closed with no fall: the other app stays closed, and the person is told. */
    public static final int UNCONFIRMED = 2;

    /**
     * What a vent-watch update means for a hand-off waiting on it. The same shape as
     * Handoff#next, for the same reason: VentWatcher reports resolved() for BOTH outcomes,
     * so `vented` is asked first or a good vent is reported as a failure.
     *
     * @param pending is a hand-off still waiting on THIS watch? False once it has been
     *                answered, or once another vent superseded the watch - a vent landing
     *                later must never open another app behind the person's back.
     */
    public static int next(boolean pending, boolean vented, boolean resolved) {
        if (!pending) return WAIT;
        if (vented) return OPEN;
        if (resolved) return UNCONFIRMED;
        return WAIT;
    }

    /* ------------------------------------------------------------------ the words */

    /** What opens, in the words the sentences below are built from. */
    public static final String CAMERA = "the camera";
    public static final String PHOTOS = "your photos";
    public static final String FILES = "the file picker";
    public static final String SHARE = "the share sheet";
    public static final String SETTINGS = "Settings";
    public static final String LOCK = "your phone's lock screen";
    /** The backstop's word, where the launch no longer knows which app it was. */
    public static final String OTHER_APP = "the other app";

    public static final String TITLE = "Vent the pump first?";
    public static final String GO = "Vent, then open";
    public static final String CANCEL = "Cancel";

    /** Said under the question on a measurement's photo, because venting changes what the
     *  reading is. At rest is one of the two equal ways of measuring, so it is said as that;
     *  and, as after any hold that ends under a capture (M1), it is measured again at rest. */
    public static final String AT_REST = "The hold ends, so this becomes an at-rest reading, "
        + "measured again at rest.";

    /** What a capture screen says once its hold was vented for another app - the sibling of
     *  the limit's and the release's own sentences (HoldWindow), for the same shape of event,
     *  shown once the vent is confirmed. It does not say the other app opened: an unconfirmed
     *  vent keeps it closed, and the person's own eyes can still settle the vent. How the vent
     *  is known (`how`, HoldWindow.VENT_*) is said in HoldWindow's own words - never more
     *  than is known. */
    public static String endedSentence(int how) {
        return "The hold was vented so another app could open, and " + HoldWindow.vented(how)
            + ", so this is an at-rest reading \u2014 compared with your other at-rest "
            + "readings taken the same way. Measure again at rest.";
    }

    /** "The pump vents first, then the camera opens." */
    public static String ventFirst(String opens) {
        return "The pump vents first, then " + opens + " " + verb(opens, "open") + ".";
    }

    /** The toast while the confirmation is awaited. */
    public static String venting(String opens) {
        return "Venting - " + opens + " " + verb(opens, "open")
            + " once the pump reports the pressure falling";
    }

    /** The window closed with no fall. The watch keeps retrying on its own. */
    public static String unconfirmed(String opens) {
        return "The pump has not reported the pressure falling, so " + opens + " "
            + verb(opens, "stay") + " closed. It is still trying to vent - if pressure does not "
            + "clear, disconnect the tubing at the cuff.";
    }

    /** (the final review, I2) The pump cannot be reached: said as the link-fix opens with a
     *  hold on, and wherever a hand-off's vent could not be sent - never "still trying". */
    public static final String UNREACHABLE = "The pump can't be reached, so it can't be vented "
        + "from here. Turn Bluetooth on, or disconnect the tubing at the cuff.";

    /** The window closed with no fall; with the link down nothing is retrying, and it says
     *  that instead (the final review, I2). */
    public static String unconfirmed(String opens, boolean linkDown) {
        if (!linkDown) return unconfirmed(opens);
        return capital(opens) + " " + verb(opens, "stay") + " closed. " + UNREACHABLE;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The backstop at a launch reached without the door, which should never happen. */
    public static String refused(String opens) {
        return "The pump may still be holding, so " + opens + " " + verb(opens, "stay")
            + " closed until it has vented.";
    }

    /** "your photos open", "the camera opens": the one plural among the things that open. */
    private static String verb(String opens, String plural) {
        return PHOTOS.equals(opens) ? plural : plural + "s";
    }
}
