package org.openpump;

/**
 * POINT 19 - WHAT THE CAPTURE FLOW SAYS ABOUT A PHOTO, stated once and pinned
 * (PhotoTruthTest), so the camera screen cannot claim more than the photo is.
 *
 * THE REVIEW LABEL. It used to read "Taken at −5.9 inHg", in green, whenever ANY hold was
 * outstanding - a skipped hold and a lapsed window included, both of which file the reading
 * without a hold - and "No hold pressure", in grey, at rest, spoken as "not comparable to
 * standardised ones". At rest and standardised are two equal ways of tracking progress,
 * each compared with its own kind, so the label now says which this photo is, in one
 * neutral colour, BY WHAT WAS TRUE WHEN IT WAS TAKEN ({@link #takenState}) - the hold's
 * state as HoldWindow judges it at that moment (M1: "counted until Save"). Standardised only
 * once "the count finishes" (the owner's words): a photo taken while the count is still
 * running is held, kept on its own, like any count not served.
 *
 *   no hold                                    "At rest"
 *   held, the count finished, inside its two
 *     minutes (OPEN)                           "Standardised · −5.9 inHg"
 *   held, the count not finished (SHORT) -
 *     still running, or stopped short          "Held at −5.9 inHg · for less than 30 s"
 *   held, the two minutes passed (LAPSED)      "Held at −5.9 inHg · after the two minutes"
 *   the vent sent, not yet seen (VENTING)      "Venting · the pressure is falling"
 *   imported                                   "▣ Imported"
 *
 * The held words are HoldWindow's own titles for those states ("Held for less than 30 s",
 * "Time's up for this hold"), so the photo and the capture screen it was taken on say the
 * same thing about the same hold.
 *
 * THE CAMERA-PERMISSION MESSAGE. With the permission off the old toast said the photo was
 * now being taken "using the phone's camera app", which the system refuses as well to an
 * app that declares the permission without holding it. The screen now says what is true
 * and offers the two ways forward that work: the app's settings, and the gallery.
 */
public final class PhotoTruth {
    private PhotoTruth() { }

    /** What a photo was taken as - see the class doc. */
    public static final int TAKEN_AT_REST = 0, TAKEN_STANDARDISED = 1, TAKEN_SHORT = 2,
                            TAKEN_LAPSED = 3, TAKEN_VENTING = 4;

    /**
     * WHAT WAS TRUE WHEN THE PHOTO WAS TAKEN, from the hold as it stood then. Standardised
     * ONLY once the count has finished and the pump is still holding inside its two-minute
     * window (HoldWindow OPEN) - the owner's words, "the count finishes". A photo taken while
     * the count is still running is SHORT: held, kept on its own (this replaces the earlier
     * rule that called it standardised because its count was running).
     *
     * @param held         a hold was outstanding (pressure commanded, its fall not yet seen)
     * @param windowState  HoldWindow#state asked at that moment
     */
    public static int takenState(boolean held, int windowState) {
        if (windowState == HoldWindow.VENTING) return TAKEN_VENTING;
        if (!held) return TAKEN_AT_REST;
        if (windowState == HoldWindow.OPEN) return TAKEN_STANDARDISED;
        if (windowState == HoldWindow.LAPSED) return TAKEN_LAPSED;
        return TAKEN_SHORT;
    }

    /**
     * The review screen's label: [0] what the pill shows, [1] what a screen reader says.
     *
     * @param imported    the image came out of the phone's gallery - it has no shutter, so
     *                    neither the angle nor the pressure at the time can be claimed
     * @param taken       {@link #takenState} when the capture was armed
     * @param observedKpa the vacuum the pump reported when the capture was armed, or null
     * @param holdSec     the configured count, for "for less than 30 s"
     */
    public static String[] reviewLabel(boolean imported, int taken, Double observedKpa,
                                       int holdSec) {
        if (imported)
            return new String[]{ "▣ Imported",
                "Imported. It was not taken in the app, so the angle it was shot from and the "
                + "pressure at the time are unknown." };
        if (taken == TAKEN_AT_REST)
            return new String[]{ "At rest",
                "At rest. Compared with your other at-rest photos of this view." };
        if (taken == TAKEN_VENTING)
            return new String[]{ "Venting · the pressure is falling",
                "Venting. The pump was told to vent and the pressure is falling; the reading "
                + "is taken at rest once the pump reports the fall." };
        String at = observedKpa == null ? "vacuum not measured"
                  : Model.Fmt.p(observedKpa.doubleValue());
        if (taken == TAKEN_STANDARDISED)
            return new String[]{ "Standardised · " + at,
                "Standardised, " + at + ". Compared with your other standardised photos of "
                + "this view." };
        String held = observedKpa == null ? "Held · vacuum not measured · " : "Held at " + at + " · ";
        if (taken == TAKEN_LAPSED)
            return new String[]{ held + "after the two minutes",
                "Held, " + at + ", after the two minutes to measure had passed. A reading "
                + "saved now is kept on its own, apart from your standardised readings." };
        return new String[]{ held + "for less than " + holdSec + " s",
            "Held, " + at + ", for less than the " + holdSec + " s count. A reading saved now "
            + "is kept on its own, apart from your standardised readings." };
    }

    /**
     * THE VACUUM AT THE SHUTTER, as the live readout would show it (the emulator pass,
     * c81dcae): the latest reading, when it is younger than `liveMs` - the bound the readout
     * itself calls "live" - and the device was measuring; otherwise null, "vacuum not
     * measured". A photo said "not measured" while the screen showed -5.9 inHg live, because
     * the shutter asked the vent watch's 600 ms bound instead. Never a stale number, never the
     * 0.0 the device sends when it is not measuring, never the commanded setpoint.
     *
     * @param sampleAt when the latest reading arrived (0 = never), on the same clock as `now`
     */
    public static Double shutterKpa(double lastKpa, boolean noReading, long sampleAt, long now,
                                    long liveMs) {
        if (sampleAt <= 0 || noReading) return null;
        if (now - sampleAt >= liveMs) return null;
        return Double.valueOf(lastKpa);
    }

    /** The permission card's title. */
    public static final String PERMISSION_TITLE = "Camera permission is off";

    /**
     * The permission card's line. While a hold is outstanding the app's settings are not
     * offered: opening them leaves the app, and leaving the app vents the pump - a reading
     * under way would be abandoned for a switch that can wait until it is done.
     */
    public static String permissionLine(boolean holding) {
        return holding
            ? "Pick a photo from the gallery, or turn the camera on in the app's settings once "
              + "this reading is saved."
            : "Turn it on in the app's settings, or pick a photo from the gallery.";
    }

    /** "POV saved — now the Side view": the auto-advance toast, by the views' LABELS. */
    public static String savedAdvance(String committedView, String nextView) {
        return Shot.label(committedView) + " saved — now the " + Shot.label(nextView) + " view";
    }
}
