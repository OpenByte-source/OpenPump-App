package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * THE WAY BACK FROM A GROWTHTRACK SIGN-IN (the owner's pick A, 9 Nov 2026). The GrowthTrack
 * screen runs in a task of its own, and after the browser the sign-in's return brought that
 * task back: Back then closed it onto whatever lay under it - the home screen or the browser -
 * not setup, a sign-in that worked said nothing, and setup's row never changed. Pure, so where
 * the screen goes back to, which page the return lands on and what the rows say are pinned by
 * tests:
 *  - WHERE IT WAS OPENED FROM (setup's Ready step or Settings) is recorded when the screen
 *    opens and kept across the browser round-trip and a process death; the way-back button
 *    names it ("Back to setup" / "Back to Settings");
 *  - THE LANDING after a sign-in this phone started: a one-time "You’re connected" page when
 *    the approval was stored, or a page that says in plain words why not, with "Try again";
 *  - "WHAT HAPPENS NOW": which sessions go, from the one stored setting;
 *  - SETUP'S ROW: connected (with the account when GrowthTrack gave one), why the last
 *    sign-in did not connect, or the invitation's own line.
 */
public final class GrowthTrackReturn {
    private GrowthTrackReturn() { }

    /* ------------------------------------------------------------------ the origin */

    /** Where the GrowthTrack screen was opened from. */
    public static final int FROM_NONE = 0, FROM_SETUP = 1, FROM_SETTINGS = 2;

    /** The saved form of an origin (the opening intent, instance state, the stored copy). */
    public static String key(int from) {
        if (from == FROM_SETUP) return "setup";
        if (from == FROM_SETTINGS) return "settings";
        return "";
    }

    /** A saved origin back; FROM_NONE for anything else. */
    public static int fromKey(String key) {
        if ("setup".equals(key)) return FROM_SETUP;
        if ("settings".equals(key)) return FROM_SETTINGS;
        return FROM_NONE;
    }

    /**
     * The origin the screen goes on with. An opening from setup or Settings names its own; the
     * sign-in's return names none, so the screen keeps the one it has - or, when the process
     * died while the browser was up and the screen is new, the one stored when it was opened.
     */
    public static int origin(int opened, int kept, int stored) {
        if (opened != FROM_NONE) return opened;
        if (kept != FROM_NONE) return kept;
        return stored == FROM_SETUP || stored == FROM_SETTINGS ? stored : FROM_NONE;
    }

    /** The way-back button's words: it names where it goes. */
    public static String backLabel(int from) {
        if (from == FROM_SETUP) return "Back to setup";
        if (from == FROM_SETTINGS) return "Back to Settings";
        return "Done";
    }

    /* ------------------------------------------------------------------ the outcome */

    /** What a sign-in this phone started came to. */
    public static final class Outcome {
        /** The approval was stored; the person (in the browser, or with Cancel sign-in) said
         *  no; or it did not go through, for the reason in `note`. */
        public static final int CONNECTED = 1, CANCELLED = 2, REFUSED = 3;
        public final int kind;
        /** The plain words for why not ("" when connected). */
        public final String note;
        private Outcome(int kind, String note) { this.kind = kind; this.note = note == null ? "" : note.trim(); }

        public static Outcome connected() { return new Outcome(CONNECTED, ""); }
        public static Outcome cancelled(String note) { return new Outcome(CANCELLED, note); }
        public static Outcome refused(String note) { return new Outcome(REFUSED, note); }

        /** The client's answer to a return (GrowthTrackClient#callback), with the words it
         *  saved for it. */
        public static Outcome of(int answer, String clientWords) {
            if (answer == GrowthTrackClient.APPROVED) return connected();
            if (answer == GrowthTrackClient.DECLINED) return cancelled(clientWords);
            return refused(clientWords);
        }

        @Override public String toString() { return kind + ":" + note; }
    }

    /** The page a sign-in's return lands on: none when the outcome is not known (the return
     *  was answered with no screen, or the process went in between). */
    public static int landing(Outcome o) {
        if (o == null) return 0;
        return o.kind == Outcome.CONNECTED ? GrowthTrackPages.CONNECTED : GrowthTrackPages.NOT_CONNECTED;
    }

    /** The not-connected page's heading. */
    public static String failedTitle(Outcome o) {
        return o != null && o.kind == Outcome.CANCELLED ? "Sign-in cancelled" : "Not connected";
    }

    /** The not-connected page's words: the refusal's own note, or a plain fallback. */
    public static String failedWords(Outcome o) {
        if (o == null || o.note.isEmpty()) return "The sign-in did not finish. No sessions were sent.";
        return o.note;
    }

    /** What "Try again" does, from the connection as it is now. */
    public static final int TRY_CONNECT = 1, TRY_CHANGE_ACCOUNT = 2, TRY_SIGN_IN_AGAIN = 3;

    /**
     * Not connected at all: the connect page again (its choices, then Sign in). A connection that
     * needs a new sign-in (refused or paused): straight to the browser, as "Sign in again" does.
     * Still connected (a change of account that did not go through): the connect page for a
     * new account.
     */
    public static int tryAgain(GrowthTrackClient.Snapshot s) {
        if (s != null && (s.needsReconnect || s.paused)) return TRY_SIGN_IN_AGAIN;
        if (s != null && s.connected) return TRY_CHANGE_ACCOUNT;
        return TRY_CONNECT;
    }

    /* ------------------------------------------------------------------ what happens now */

    public static final String CHANGE_IT = "Change this any time in Settings › Connected apps.";

    /**
     * "What happens now": which sessions go to the account, from the one stored setting. A plan
     * the person does not train (its switch hidden, `girthShown` / `lengthShown`) is not named.
     */
    public static String whatGoes(GrowthTrackSettings s, boolean girthShown, boolean lengthShown, String earlierChoice) {
        GrowthTrackSettings set = s == null ? new GrowthTrackSettings() : s;
        if (!set.on) return "Sending is switched off, so nothing goes to this account until you turn it on.";
        List<String> kinds = new ArrayList<String>();
        if (girthShown && set.girthPlan) kinds.add("girth plan");
        if (lengthShown && set.lengthPlan) kinds.add("length plan");
        if (set.ownRoutines) kinds.add("own-routine");
        if (set.manualRuns) kinds.add("manual");
        if (kinds.isEmpty()) return "No kind of session is switched on, so nothing goes to this account yet.";
        StringBuilder b = new StringBuilder("Your finished ");
        for (int i = 0; i < kinds.size(); i++) {
            if (i > 0) b.append(i == kinds.size() - 1 ? " and " : ", ");
            b.append(kinds.get(i));
        }
        b.append(" sessions go to this account.");
        if (set.stoppedEarly) b.append(" Sessions stopped early go too.");
        if (GrowthTrackClient.EARLIER_ALSO.equals(earlierChoice)) b.append(" So do the earlier ones on this phone that can be sent.");
        return b.toString();
    }

    /** The connected page's account line: the email or name GrowthTrack gave, else null. */
    public static String account(GrowthTrackClient.Snapshot s) { return GrowthTrackWords.account(s); }

    /* ------------------------------------------------------------------ setup's row */

    /** The invitation's own line, before any sign-in. */
    public static final String SETUP_INVITE = "Keep your sessions in your own account too";

    /** Setup's row line: whether it reads as connected (drawn in the accent), and its words. */
    public static final class Row {
        public final boolean connected;
        public final String text;
        Row(boolean connected, String text) { this.connected = connected; this.text = text; }
        @Override public String toString() { return (connected ? "on:" : "off:") + text; }
    }

    /**
     * "✓ Connected · <account>" (or "✓ Connected") once connected; after a sign-in that did not
     * connect, why not; otherwise the invitation's own line.
     */
    public static Row setupRow(GrowthTrackClient.Snapshot s, Outcome last) {
        if (s != null && s.connected) {
            String who = GrowthTrackWords.account(s);
            return new Row(true, who == null ? "✓ Connected" : "✓ Connected · " + who);
        }
        if (last != null && last.kind != Outcome.CONNECTED) return new Row(false, "Not connected · " + reason(last));
        return new Row(false, SETUP_INVITE);
    }

    /** A refusal said short, for a row: its first sentence, as a clause. */
    static String reason(Outcome o) {
        if (o.kind == Outcome.CANCELLED) return "sign-in was cancelled";
        String n = o.note;
        if (n.isEmpty()) return "the sign-in did not finish";
        if (n.toLowerCase(java.util.Locale.ROOT).contains("expired")) return "the sign-in request expired";
        int stop = n.indexOf(". ");
        String first = stop > 0 ? n.substring(0, stop) : n;
        if (first.endsWith(".")) first = first.substring(0, first.length() - 1);
        boolean name = first.startsWith("GrowthTrack") || first.startsWith("OpenPump");
        return name || first.isEmpty() ? first : Character.toLowerCase(first.charAt(0)) + first.substring(1);
    }
}
