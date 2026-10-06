package org.openpump;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WHAT THE DEBUG LOG MAY NOT SAY, applied to every free-text part of a {@link Journal} line
 * before it is written.
 *
 * The journal records what a person does and what the pump and the screen did back, so it
 * carries button labels, dialog titles, toasts and the app's own log lines - all of which
 * can hold things that belong to the person rather than to the bug: the names they gave
 * their routines, sets and stages, the notes they wrote, the pump's Bluetooth address, and
 * on the measurement screens the measurements themselves. The app has always promised that
 * the debug log never contains measurements or photos; this is where that promise is
 * enforced for the richer log, rather than left to every call site to remember.
 *
 * Four rules:
 *   1. REGISTERED TEXT. The app registers every user-authored string it loads (routine,
 *      stage and set names, notes, pump nicknames). Each is replaced by a short stable token
 *      - the third routine registered is "‹routine3›" for the whole session - so a log can
 *      still say "the same routine as before" without saying which. Matched ignoring case,
 *      on word boundaries. Strings shorter than three characters are not registered: they
 *      would erase ordinary words and give nothing away.
 *   2. MACHINE IDENTIFIERS. MAC addresses, e-mail addresses, absolute file paths and
 *      content:// URIs are masked wherever they appear. A file path is the one way a photo
 *      could be named in the log, and a MAC address identifies the person's pump.
 *   3. DIGITS ON MASKED SCREENS ({@link #maskedScreen}). On every screen that is not a
 *      pump-control or editing screen, each digit in a tap label, dialog title, toast or
 *      screen value becomes '#'. It is an ALLOW list: a new screen is masked until someone
 *      decides it only shows pump figures.
 *   4. TEXT FIELDS. What is typed into a field is never read; {@link #field} describes the
 *      field by its hint and length only.
 */
public final class Redact {

    /** Below this length a registered string would match ordinary words and reveal little. */
    public static final int MIN_TERM = 3;
    /** A ceiling on registered terms, so a pathological model cannot make scrub() slow. */
    public static final int MAX_TERMS = 1500;

    private static final Pattern MAC =
        Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}(?![0-9a-f])");
    private static final Pattern EMAIL =
        Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    private static final Pattern URI = Pattern.compile("content://\\S+");
    /** Two or more path segments after a leading slash: /storage/emulated/0/... */
    private static final Pattern PATH = Pattern.compile("(?:/[^\\s/\"':;,()]+){2,}/?");

    /** lower-case term -> token; the terms themselves kept longest first, so "Leg day 2"
     *  is replaced before "Leg day" can split it. */
    private final Map<String, String> tokens = new HashMap<String, String>();
    private final List<String> ordered = new ArrayList<String>();
    private final Map<String, Integer> perKind = new HashMap<String, Integer>();
    private boolean dirty;

    /**
     * Remember one user-authored string. The token is stable: registering the same text
     * again, in any case, keeps the token it already has.
     */
    public synchronized void register(String kind, String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.length() < MIN_TERM) return;
        String low = t.toLowerCase(Locale.ROOT);
        if (tokens.containsKey(low) || tokens.size() >= MAX_TERMS) return;
        Integer n = perKind.get(kind);
        int next = n == null ? 1 : n.intValue() + 1;
        perKind.put(kind, Integer.valueOf(next));
        tokens.put(low, "‹" + kind + next + "›");
        ordered.add(low);
        dirty = true;
    }

    /** Every user-authored string the model holds: routine, stage and set names, saved
     *  shapes, cylinders, remembered pumps (name and address), and every note. */
    public void registerModel(Model m) {
        if (m == null) return;
        for (int i = 0; i < m.routines.size(); i++) {
            Model.Routine r = m.routines.get(i);
            register("routine", r.name);
            for (int k = 0; k < r.stages.size(); k++) register("stage", r.stages.get(k).name);
        }
        for (int i = 0; i < m.sets.size(); i++) register("set", m.sets.get(i).name);
        for (int i = 0; i < m.adhoc.size(); i++) register("set", m.adhoc.get(i).name);
        for (int i = 0; i < m.shapes.size(); i++) register("shape", m.shapes.get(i).name);
        for (int i = 0; i < m.cylinders.size(); i++) register("cylinder", m.cylinders.get(i).label);
        for (int i = 0; i < m.knownPumps.size(); i++) {
            register("pump", m.knownPumps.get(i).name);
            register("pump", m.knownPumps.get(i).address);
        }
        for (int i = 0; i < m.sessLog.all.size(); i++) register("note", m.sessLog.all.get(i).note);
        for (int i = 0; i < m.measLog.all.size(); i++) {
            Model.Reading r = m.measLog.all.get(i);
            register("note", r.note);
            register("note", r.label);
            Model.Reading.Photo[] ph = { r.photoFront, r.photoSide, r.photoTop };
            for (int k = 0; k < ph.length; k++) if (ph[k] != null) register("note", ph[k].note);
        }
    }

    /** Number of registered terms. */
    public synchronized int size() { return ordered.size(); }

    /** Rules 1 and 2. Never returns null. */
    public String scrub(String s) {
        if (s == null || s.length() == 0) return "";
        String out = s;
        synchronized (this) {
            if (dirty) {
                java.util.Collections.sort(ordered, LONGEST_FIRST);
                dirty = false;
            }
            if (!ordered.isEmpty()) {
                String low = out.toLowerCase(Locale.ROOT);
                for (int i = 0; i < ordered.size(); i++) {
                    String term = ordered.get(i);
                    int at = low.indexOf(term);
                    if (at < 0) continue;
                    StringBuilder sb = null;
                    int from = 0;
                    while (at >= 0) {
                        int end = at + term.length();
                        if (boundary(low, at - 1) && boundary(low, end)) {
                            if (sb == null) sb = new StringBuilder(out.length());
                            sb.append(out, from, at).append(tokens.get(term));
                            from = end;
                        }
                        at = low.indexOf(term, end);
                    }
                    if (sb != null) {
                        sb.append(out, from, out.length());
                        out = sb.toString();
                        low = out.toLowerCase(Locale.ROOT);
                    }
                }
            }
        }
        if (out.indexOf('@') >= 0) out = EMAIL.matcher(out).replaceAll("‹email›");
        if (out.indexOf("content://") >= 0) out = URI.matcher(out).replaceAll("‹uri›");
        if (out.indexOf(':') >= 0 || out.indexOf('-') >= 0) {
            Matcher mm = MAC.matcher(out);
            if (mm.find()) out = mm.replaceAll("‹mac›");
        }
        if (out.indexOf('/') >= 0) out = PATH.matcher(out).replaceAll("‹path›");
        return out;
    }

    /** A term only matches as a whole word: the character either side is not a letter or
     *  digit, or is the edge of the string. */
    private static boolean boundary(String s, int i) {
        if (i < 0 || i >= s.length()) return true;
        return !Character.isLetterOrDigit(s.charAt(i));
    }

    private static final java.util.Comparator<String> LONGEST_FIRST =
        new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) { return b.length() - a.length(); }
        };

    /** Rule 3: every ASCII digit becomes '#' - except inside a ‹token›, whose number is
     *  an index this class assigned, not something the screen showed. */
    public static String maskDigits(String s) {
        if (s == null) return "";
        char[] c = null;
        boolean inToken = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '‹') inToken = true;
            else if (ch == '›') inToken = false;
            else if (!inToken && ch >= '0' && ch <= '9') {
                if (c == null) c = s.toCharArray();
                c[i] = '#';
            }
        }
        return c == null ? s : new String(c);
    }

    /** Rule 4: a text field is described, never read. */
    public static String field(CharSequence hint, int length) {
        String h = hint == null ? "" : hint.toString().trim();
        return "field" + (h.length() > 0 ? " " + h : "") + " len=" + length;
    }

    /**
     * Whether digits are masked on a screen. An ALLOW list of the screens that show pump
     * figures or edit routines and sets; everything else - every measurement, photo,
     * progress and summary screen, Today and the Trainer - is masked.
     */
    public static boolean maskedScreen(int screen) {
        switch (screen) {
            case Nav.SCR_MANUAL: case Nav.SCR_CONNECT:
            case Nav.SCR_LIBRARY: case Nav.SCR_ROUTINES: case Nav.SCR_SETS:
            case Nav.SCR_ROUT_EDIT: case Nav.SCR_STAGE_EDIT: case Nav.SCR_SET_EDIT:
            case Nav.SCR_PICKER: case Nav.SCR_ASSESS_EDIT: case Nav.SCR_USED_IN:
            case Nav.SCR_SETTINGS: case Nav.SCR_DIAGNOSTICS: case Nav.SCR_VALIDATE_INTRO:
            case Nav.SCR_HELP:
            case Nav.SCR_HOLD: case Nav.SCR_RELEASE: case Nav.SCR_SEAL: case Nav.SCR_ASSESS:
            case Nav.SCR_RUN: case Nav.SCR_LINK_LOST: case Nav.SCR_VALIDATE_RUN:
            case Nav.SCR_GUIDED: case Nav.SCR_SETUP:
                return false;
            default:
                return true;
        }
    }
}
