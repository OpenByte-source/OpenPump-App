package org.openpump;

import java.util.Locale;

/**
 * The strings an accessibility service speaks, derived from the strings that are drawn.
 *
 * Pure by construction — no `import android` — so test.sh compiles it and SelfTest can
 * assert on it. That is the whole point of the class: an Android app with no XML layouts
 * has every accessibility property set programmatically, which normally puts all of it
 * out of reach of a harness that cannot load android.jar. Everything decidable from
 * strings alone lives here and is tested; only the setContentDescription() call itself
 * stays in the view code.
 *
 * THE UNIT RULE. Nothing here formats a pressure, and nothing here ever should. Every
 * caller passes the value string it has ALREADY drawn on screen (via Model.Fmt.p for an
 * absolute pressure, Model.Fmt.d for a delta, or a plain "%d %%" / "%.1f cm" for the
 * things that are neither). The announcement is therefore the same characters the screen
 * shows, so it cannot hardcode kPa while the user is in inHg, cannot drop the sign inHg
 * displays, and cannot push a difference through the absolute-pressure formatter. A
 * second formatting path here would be a second chance to get that wrong.
 */
public final class A11y {
    private A11y() { }

    /**
     * The app's radio marker pair. These two glyphs are used ONLY to mean
     * selected/unselected — in the set editor's Fixed/Ramp row, the stage colour rows,
     * Settings' unit and cadence rows, the reading editor's hold row, Compare's
     * Front/Side toggle, the history period row and the routine list's ● picker — so a
     * helper may safely read a leading one as state.
     *
     * The other tick-like glyphs in this app may NOT be read that way, which is why the
     * detection below is deliberately narrow. "✓ Use this" is the camera review screen's
     * CONFIRM button, not a selected item; "↺ Retake" sits beside it; "▸" marks the open
     * stage and "✓" the picked sets, but both of those have call sites that know their
     * own state and say so explicitly. Widening this to every tick would have announced
     * the camera's save button as "Use this, selected".
     */
    public static final String MARK_ON = "● ";
    public static final String MARK_OFF = "○ ";

    /** Spoken suffixes, so the same words are used everywhere state is announced. */
    public static final String SELECTED = ", selected";
    public static final String NOT_SELECTED = ", not selected";

    /**
     * The plain name of a stepper's field, from the label the row already prints.
     *
     * Stepper labels carry their enforced range in a trailing parenthetical — "Hold at
     * target  (0-255 s)", "Assessment pressure  (5.0 kPa to 57.0 kPa)" — which is useful
     * to read and useless to hear on every one of a screen's fourteen buttons. Only the
     * LAST parenthetical group is removed, and only when it closes the string, so
     * "Power (start)  (5-100 %)" keeps the part that distinguishes it from "Power (end)".
     *
     * Lower-cased because the name is spoken mid-sentence ("decrease hold at target,
     * currently 30 s"), matching the three names the assessment editor already passes in
     * by hand.
     */
    public static String fieldName(String label) {
        if (label == null) return "value";
        String s = label.trim();
        if (s.endsWith(")")) {
            int depth = 0, cut = -1;
            for (int i = s.length() - 1; i >= 0; i--) {
                char c = s.charAt(i);
                if (c == ')') depth++;
                else if (c == '(') {
                    depth--;
                    if (depth == 0) { cut = i; break; }
                }
            }
            if (cut > 0) {
                String head = s.substring(0, cut).trim();
                if (head.length() > 0) s = head;
            }
        }
        s = collapse(s).toLowerCase(Locale.US);
        return s.length() == 0 ? "value" : s;
    }

    /** Runs of whitespace (the labels use double spaces as a visual gap) become one
     *  space; a screen reader should not hear the gap. */
    public static String collapse(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean ws = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') { ws = true; continue; }
            if (ws && b.length() > 0) b.append(' ');
            ws = false;
            b.append(c);
        }
        return b.toString();
    }

    /** The − button's name. `value` is the already-drawn value string — see THE UNIT
     *  RULE above. */
    public static String decrease(String name, String value) {
        return "decrease " + name + ", currently " + value;
    }

    /** The + button's name, same contract as {@link #decrease}. */
    public static String increase(String name, String value) {
        return "increase " + name + ", currently " + value;
    }

    /**
     * The name for the value readout between the two buttons. Every screen in this app
     * rebuilds its views on each change rather than mutating a TextView in place, so a
     * description built here alongside the text is rebuilt with it and can never go
     * stale — the one thing that would make announcing a value worse than announcing
     * nothing. (The two screens that DO mutate text on a timer, the standardisation
     * countdown and the release readout, set their descriptions at the point they set
     * the text, not at build time.)
     */
    public static String value(String name, String value) {
        return name + " " + value;
    }

    /** TRUE / FALSE for a label carrying a radio marker, null for one that carries
     *  none — null means "this control is not a selection", never "not selected". */
    public static Boolean markerState(String text) {
        if (text == null) return null;
        if (text.startsWith(MARK_ON)) return Boolean.TRUE;
        if (text.startsWith(MARK_OFF)) return Boolean.FALSE;
        return null;
    }

    /** The label without its radio marker; unchanged when there is none. */
    public static String stripMarker(String text) {
        if (text == null) return "";
        if (text.startsWith(MARK_ON) || text.startsWith(MARK_OFF))
            return text.substring(MARK_ON.length());
        return text;
    }

    /** What a marked control should announce, or null when the label carries no marker
     *  and so must be left exactly as the view already reads it. */
    public static String describeMarked(String text) {
        Boolean on = markerState(text);
        if (on == null) return null;
        return state(stripMarker(text), on.booleanValue());
    }

    /** "<label>, selected" / "<label>, not selected" — for the controls whose call site
     *  knows its own state and does not encode it in a marker glyph. */
    public static String state(String label, boolean on) {
        return collapse(label) + (on ? SELECTED : NOT_SELECTED);
    }
}
