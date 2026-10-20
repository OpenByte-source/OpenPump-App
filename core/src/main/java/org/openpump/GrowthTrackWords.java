package org.openpump;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WHAT THE GROWTHTRACK ROWS AND CARDS SAY (owner decisions 2, 3 and 7, 19 Oct 2026). Pure, so
 * every state's words are pinned by a test rather than by a screenshot:
 *  - the Settings row's one status line, in three states: a lime dot "Connected · last sent
 *    today 18:42", an amber dot "2 waiting · sends when online", a grey dot "Not connected";
 *  - the status card's line, and the latest result or error under it in plain words (results
 *    live INSIDE the card: no collapsed "Connection update", no stacked notes);
 *  - the list of sent and waiting sessions, and what Erase all data reports.
 */
public final class GrowthTrackWords {
    private GrowthTrackWords() { }

    /** The dot in front of a status line: on (lime), waiting (amber), off (grey). */
    public static final int DOT_ON = 1, DOT_WAITING = 2, DOT_OFF = 3;

    /** One status line: its dot and its words. */
    public static final class Line {
        public final int dot;
        public final String text;
        Line(int dot, String text) { this.dot = dot; this.text = text; }
        @Override public String toString() { return dot + ":" + text; }
    }

    private static final String[] MONTHS = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul",
        "Aug", "Sep", "Oct", "Nov", "Dec" };

    /** "today 18:42", "yesterday 18:42", "12 Oct 18:42", or "12 Oct 2025" in another year. */
    public static String when(long at, long now, TimeZone zone) {
        TimeZone tz = zone == null ? TimeZone.getDefault() : zone;
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(at);
        Calendar n = Calendar.getInstance(tz, Locale.US);
        n.setTimeInMillis(now);
        String time = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        if (sameDay(c, n)) return "today " + time;
        Calendar y = (Calendar) n.clone();
        y.add(Calendar.DAY_OF_MONTH, -1);
        if (sameDay(c, y)) return "yesterday " + time;
        String day = c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)];
        if (c.get(Calendar.YEAR) != n.get(Calendar.YEAR)) return day + " " + c.get(Calendar.YEAR);
        return day + " " + time;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static boolean linked(GrowthTrackClient.Snapshot s) {
        return s != null && (s.connected || s.needsReconnect);
    }

    private static String waiting(int n) { return n + " waiting"; }

    /** Settings › Connected apps › GrowthTrack: the row's one line (decision 2). */
    public static Line settingsRow(GrowthTrackClient.Snapshot s, boolean sendingOn, long now, TimeZone tz) {
        if (!linked(s)) return new Line(DOT_OFF, "Not connected");
        if (s.needsReconnect || s.paused)
            return new Line(DOT_WAITING, s.queued > 0 ? waiting(s.queued) + " · sign in again to send"
                : "Sign in again to keep sending");
        if (!sendingOn) return new Line(DOT_OFF, "Connected · sending is off");
        if (s.queued > 0) return new Line(DOT_WAITING, waiting(s.queued) + " · sends when online");
        return new Line(DOT_ON, s.lastSentAt > 0 ? "Connected · last sent " + when(s.lastSentAt, now, tz)
            : "Connected · nothing sent yet");
    }

    /** The status card's line (decision 3): when it last sent and what waits, or why not. */
    public static Line statusLine(GrowthTrackClient.Snapshot s, boolean sendingOn, long now, TimeZone tz) {
        if (!linked(s)) return new Line(DOT_OFF, "Not connected");
        if (!sendingOn) return new Line(DOT_OFF, "Sending is off · nothing is sent");
        if (s.needsReconnect)
            return new Line(DOT_WAITING, "Sign in again to keep sending" + (s.queued > 0 ? " · " + waiting(s.queued) : ""));
        if (s.paused) return new Line(DOT_WAITING, "Sending paused · sign in again to restore it");
        String sent = s.lastSentAt > 0 ? "Last sent " + when(s.lastSentAt, now, tz) : "Nothing sent yet";
        if (s.queued > 0) return new Line(DOT_WAITING, waiting(s.queued) + " · sends when online");
        return new Line(DOT_ON, sent + " · nothing waiting");
    }

    private static final Pattern RESULT = Pattern.compile("^(\\d+) accepted, (\\d+) already present, (\\d+) rejected\\.$");
    private static final Pattern WAITING = Pattern.compile("^\\d+ sessions? waiting$");

    /**
     * The latest result or error, in plain words, for under the status line - or "" when the
     * client's last word only repeats what the status line already says (waiting counts, a
     * browser request, the resting "Not connected").
     */
    public static String latest(String message) {
        if (message == null) return "";
        String m = message.trim();
        if (m.isEmpty() || m.equals("Not connected")) return "";
        if (m.startsWith("Waiting for GrowthTrack approval")) return "";
        if (m.startsWith("Sessions are securely queued")) return "";
        if (WAITING.matcher(m).matches()) return "";
        Matcher r = RESULT.matcher(m);
        if (r.find()) {
            int received = Integer.parseInt(r.group(1)) + Integer.parseInt(r.group(2));
            int refused = Integer.parseInt(r.group(3));
            String out = "Last send: " + received + (received == 1 ? " session" : " sessions") + " received";
            return refused > 0 ? out + ", " + refused + " refused. See Sent and waiting sessions." : out + ".";
        }
        return m;
    }

    /** The status card's account line: the email or name the sign-in gave, or null (omitted). */
    public static String account(GrowthTrackClient.Snapshot s) {
        if (s == null || s.account == null || s.account.trim().isEmpty()) return null;
        return s.account.trim();
    }

    /** "Sent and waiting sessions ›": its facts, e.g. "3 sent · 1 waiting · 2 not sent". */
    public static String listFacts(int sent, int waiting, int notSent) {
        StringBuilder b = new StringBuilder();
        if (sent > 0) b.append(sent).append(" sent");
        if (waiting > 0) b.append(b.length() > 0 ? " · " : "").append(waiting).append(" waiting");
        if (notSent > 0) b.append(b.length() > 0 ? " · " : "").append(notSent).append(" not sent");
        return b.length() == 0 ? "No sessions yet" : b.toString();
    }

    /** One session's row title: its routine (or "Manual run") and when it started. */
    public static String sessionTitle(Model.Sess s, long now, TimeZone tz) {
        String name = s == null || s.routineName == null || s.routineName.trim().isEmpty()
            ? (s != null && s.manual ? "Manual run" : "Session") : s.routineName.trim();
        return s == null ? name : name + " · " + when(s.ts, now, tz);
    }

    /**
     * ERASE ALL DATA REPORTS DONE ONLY AFTER THE GROWTHTRACK ERASE (bug 19): what the snack
     * says once both the app's own files and the GrowthTrack state (tokens, queue, retry job)
     * have answered. `gtError` is null when the GrowthTrack erase succeeded.
     */
    public static String eraseResult(boolean filesGone, String gtError) {
        String base = filesGone ? "All data deleted" : "Deleted — some files could not be removed";
        if (gtError == null) return base;
        return base + ". The GrowthTrack connection could not be removed; erase again to retry";
    }
}
