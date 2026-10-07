package org.openpump;

/**
 * A STEP DONE BY HAND, NAMED WHILE IT PLAYS (the owner's decision, 2026-10-07).
 *
 * A length session that pulls opens with the tunica release, done by hand with the pump vented,
 * and may change tubes before its expansion. Both are manual REST stages (Model.Stage#manual),
 * and the run screen used to draw every rest the same way: the title "REST", the routine line
 * without the stage's name, Coming steps "Rest". So the one step that asks the person to DO
 * something was the one step the screen did not name. These are the words it uses instead -
 * pure, so they are tested, and the run screen, the status line, the notification and the
 * screen reader all read them from here.
 *
 * AND THE RELEASE WAITS FOR DONE. Its five minutes are a guide: the clock counts them down, and
 * at 0:00 the run does not move on to the warm-up by itself - it says "Done when you are" and
 * keeps waiting, with the pump vented and nothing commanded, until the person taps Done (which
 * they may do at any time). The changeover waits from its start as it always has (its
 * Model.Stage#awaitAck); the release is told apart from it by having no such flag, so no saved
 * field changes and a routine saved before this waits too.
 *
 * A plain rest is none of this: it stays "REST".
 */
public final class ByHand {
    private ByHand() { }

    /** The word the run uses for a by-hand step, where it used to say REST. */
    public static final String WORD = "BY HAND";
    /** What a stage name carries to say it is done by hand - dropped where WORD says it. */
    public static final String SUFFIX = " — by hand";
    /** The NOW card's line while the release plays. */
    public static final String VENTED_LINE = "Pump vented · do it by hand now";
    /** ...and once its guide time has run out. */
    public static final String WHEN_READY = "Done when you are";
    /** The changeover's line: its own instruction, with the same first fact. */
    public static final String SWAP_LINE =
        "Pump vented · swap the cylinder, then press “I’ve swapped”";
    /** The button that ends a by-hand step whose time is a guide. */
    public static final String DONE = "Done ›";
    /** The − / + strip's heading over a by-hand step. */
    public static final String HEAD = "BY HAND · CUFF VENTED";

    /** Is `p` a step done by hand - vented, commanding nothing? */
    public static boolean is(Model.Preset p) {
        return p != null && p.rest && p.manual;
    }

    /**
     * Is `p` THE RELEASE: a by-hand step whose time is a guide, that waits for Done once its
     * clock reaches 0:00? The changeover (Preset#awaitAck) is by hand too, but waits from its
     * start, so it is not this.
     */
    public static boolean waitsAfterClock(Model.Preset p) {
        return is(p) && !p.awaitAck;
    }

    /** "Tunica release — by hand" is "Tunica release": the title already says BY HAND. A name
     *  without the suffix (the changeover's sentence) is kept whole. */
    public static String bare(String name) {
        String n = name == null ? "" : name.trim();
        if (n.endsWith(SUFFIX)) n = n.substring(0, n.length() - SUFFIX.length()).trim();
        return n;
    }

    /** The NOW card's title: "BY HAND · Tunica release", "BY HAND · Swap to your girth
     *  cylinder — next is expansion at …"; "BY HAND" alone for a stage with no name. */
    public static String title(String name) {
        String n = bare(name);
        return n.length() == 0 ? WORD : WORD + " · " + n;
    }

    /** Coming steps' name for it: "Tunica release (by hand)". */
    public static String coming(String name) {
        String n = bare(name);
        return (n.length() == 0 ? "Step" : n) + " (by hand)";
    }

    /** The NOW card's line under the time: the release's own, or "Done when you are" once its
     *  guide time is up; the changeover's instruction for the changeover. */
    public static String nowLine(boolean swap, boolean timeUp) {
        if (swap) return SWAP_LINE;
        return timeUp ? WHEN_READY : VENTED_LINE;
    }

    /** The status line: "BY HAND · 4:32 LEFT", then "BY HAND · DONE WHEN YOU ARE". */
    public static String status(long leftMs, boolean timeUp) {
        if (timeUp) return WORD + " · " + WHEN_READY.toUpperCase(java.util.Locale.US);
        return WORD + " · " + RunLook.left(leftMs) + " LEFT";
    }

    /** The notification's lead: "By hand · Tunica release", or "By hand · Done when you are"
     *  once the guide time is up. */
    public static String notification(String name, boolean timeUp) {
        if (timeUp) return "By hand · " + WHEN_READY;
        String n = bare(name);
        return n.length() == 0 ? "By hand" : "By hand · " + n;
    }
}
