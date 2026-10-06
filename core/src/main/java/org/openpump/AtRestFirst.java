package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * POINT 19, OPTION A - "SAVE AT REST, THEN STANDARDISE". The owner's choice, stated once and
 * pinned (AtRestFirstTest), for the Log-a-reading door.
 *
 * WHAT WENT WRONG. A user who entered at-rest numbers and took photos at rest, then chose
 * Standardised and tapped "Standardise and measure ›", got ONE reading, marked standardised,
 * carrying the photos taken at rest - and the at-rest numbers were never saved. Photos taken
 * on the "Standardised, not yet held" screen went the same way. At rest and standardised are
 * two equal methods; the problem was never the at-rest photos, it was that they were filed
 * as something they were not, and that a real at-rest reading was thrown away.
 *
 * WHAT HAPPENS NOW. Choosing Standardised moves and drops nothing. If an at-rest reading has
 * been started, a card names it and the button at the foot says it will be saved first:
 * "Save at rest, then standardise ›" files the at-rest reading exactly as the at-rest Save
 * would (one reading per filled method, with its photos), then starts the hold for a NEW
 * reading - a fresh id, an empty photo buffer, the time of the hold. Two readings from one
 * visit, each with its own photos, each what it is. The "Standardised, not yet held" screen
 * has no photo slots: a standardised reading's photos are taken under the hold, as its
 * numbers already are.
 *
 * PHOTOS WITH NO NUMBER. A reading needs a measurement, so photos taken at rest with no
 * at-rest row cannot be filed on their own. The app never drops them: the card asks for the
 * at-rest measurement they go with (or lets the person remove them, with Undo), and the
 * button waits, saying why.
 */
public final class AtRestFirst {
    private AtRestFirst() { }

    /** Nothing entered at rest: the button standardises, as it always did. */
    public static final int NOTHING = 0;
    /** At-rest measurements entered (photos or not): save them first, then standardise. */
    public static final int SAVE_FIRST = 1;
    /** Photos taken at rest and no at-rest measurement: nothing to save them with yet. */
    public static final int NEEDS_NUMBER = 2;

    /** Which of the three the door is in. */
    public static int state(int filledRows, int photos) {
        if (filledRows > 0) return SAVE_FIRST;
        return photos > 0 ? NEEDS_NUMBER : NOTHING;
    }

    /** Whether the door draws its photo slots: always at rest, always under the hold (the
     *  capture), and NOT on the "Standardised, not yet held" screen - a photo taken there is
     *  taken at rest, and the standardised reading's photos are taken under its hold. */
    public static boolean photoSlotsShown(boolean capture, boolean standardisedChosen) {
        return capture || !standardisedChosen;
    }

    public static final String CARD_TITLE = "Your at-rest reading";

    /** What is in the at-rest reading so far: "BPEL 15.2 cm · MSEG 12.0 cm · POV and Side
     *  photos · 14:02". `rowVals` is parallel to {@link Meas#LOG_ROWS}; `photoViews` are the
     *  views taken, as stored keys. */
    public static String cardLine(Double[] rowVals, List<String> photoViews, String time) {
        List<String> parts = new ArrayList<String>();
        if (rowVals != null)
            for (int i = 0; i < rowVals.length && i < Meas.LOG_ROWS.length; i++)
                if (rowVals[i] != null)
                    parts.add(Meas.LOG_ROWS[i].label + " "
                              + Model.Fmt.len(rowVals[i].doubleValue()));
        if (photoViews != null && !photoViews.isEmpty()) parts.add(photosPhrase(photoViews));
        if (time != null && time.length() > 0) parts.add(time);
        return join(parts, " · ");
    }

    /** The card's caption: what will happen to it, or what it still needs. */
    public static String cardCaption(int state, int photos) {
        if (state == NEEDS_NUMBER)
            return (photos == 1 ? "1 photo" : photos + " photos") + " taken at rest — add the "
                 + "at-rest measurement " + (photos == 1 ? "it goes" : "they go") + " with.";
        return "Saved as its own at-rest reading when the hold starts.";
    }

    /** The foot button's words. */
    public static String buttonLabel(int state) {
        return state == NOTHING ? "Standardise and measure ›" : "Save at rest, then standardise ›";
    }

    /** The line under the button. `pressure` is the hold's target, already formatted. */
    public static String buttonNote(int state, String pressure) {
        String hold = "the pump pulls to " + pressure + " and holds it while you measure. It "
                    + "vents itself after the hold limit, and leaving the app vents it "
                    + "immediately.";
        if (state == NOTHING) return "T" + hold.substring(1);
        return "Saves your at-rest reading, then " + hold;
    }

    /** What a screen reader says the waiting button is waiting for. */
    public static String waitingReason(int photos) {
        return "Save at rest, then standardise. Waiting: add the at-rest measurement for your "
             + (photos == 1 ? "photo" : photos + " photos") + ", or remove "
             + (photos == 1 ? "it" : "them") + ", first.";
    }

    /** The snack once the at-rest part is saved: "At-rest reading saved · BPEL, MSEG · 2
     *  photos". `methods` are the labels of the readings filed. */
    public static String savedSnack(List<String> methods, int photos) {
        StringBuilder b = new StringBuilder("At-rest reading saved");
        if (methods != null && !methods.isEmpty()) b.append(" · ").append(join(methods, ", "));
        if (photos > 0) b.append(" · ").append(photos == 1 ? "1 photo" : photos + " photos");
        return b.toString();
    }

    /** The snack when the person removes photos they took at rest, with Undo beside it. */
    public static String removedSnack(int photos) {
        return (photos == 1 ? "1 photo" : photos + " photos") + " taken at rest "
             + (photos == 1 ? "was" : "were") + " removed";
    }

    /** "POV photo" / "POV and Side photos". */
    static String photosPhrase(List<String> views) {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < views.size(); i++) names.add(Shot.label(views.get(i)));
        return join(names, " and ") + (views.size() == 1 ? " photo" : " photos");
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(parts.get(i));
        }
        return b.toString();
    }
}
