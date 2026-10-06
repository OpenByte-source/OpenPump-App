package org.openpump;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * THE DEBUG LOG, AS A RECORD OF CAUSE AND EFFECT.
 *
 * The old log was free text: whatever each call site thought worth saying. It could not
 * answer the question a report from a phone always comes down to - "I pressed this, and
 * then what happened?" - because the press was never in it, what the screen showed was
 * never in it, and the pump's readings were either all of them or none. This records all
 * three sides of every exchange on one clock, one compact line per event:
 *
 *   +MM:SS.cc TAG detail
 *
 * relative to when the log was opened. The tags:
 *   TAP   a touch on a control: screen, label, "disabled" when it was, "val=" for a slider
 *   BACK  the back key
 *   NAV   a screen change, "dialog"/"dialog-closed", or "paused"/"resumed"
 *   CMD   a frame handed to the pump, decoded ("add spd=75 up=40 uh=120 lo=10 lh=5")
 *   ACK   the pump's ack, with ms since the command it answers; RX any other frame, as hex
 *   PMP   the pump's reading, COMPRESSED: only when it moves 0.3 kPa, when "no reading"
 *         flips, or every 2 s, with n= the number of samples it stands for
 *   UI    what the screen showed, only when it changed: the run cells, phase, countdown,
 *         chart progress, toasts, and the screen signature after a tap
 *   LINK  connection state
 *   APP   the app's own log lines
 *   CHK   a discrepancy {@link JournalWatch} found, always "!! RULE ..."
 *
 * Lines are fed to a JournalWatch as they are made; its findings are written as CHK lines.
 * The line format is the contract JournalCheck (the desktop replay tool, in the test source
 * set) parses, so it stays plain: tokens
 * separated by single spaces, free text always double-quoted (and never containing a double
 * quote), numbers in kPa, seconds or per cent.
 *
 * LIGHT BY DESIGN. Nothing is written per event: lines collect in a buffer the app drains
 * about once a second onto a background thread. A pump reading that is going to be
 * collapsed costs a few comparisons and no allocation. Past {@link #FILE_CAP} bytes the
 * pump and screen detail stops being written (one line says so) while taps, commands, acks,
 * checks and app lines continue. An in-memory ring keeps the last {@link #RING} lines
 * regardless.
 *
 * PRIVATE BY CONSTRUCTION. Every free-text part goes through {@link Redact}; see there.
 */
public final class Journal {

    public static final int RING = 4000;
    public static final long FILE_CAP = 3L * 1000 * 1000;
    public static final double PMP_STEP_KPA = 0.3;
    public static final long PMP_HEARTBEAT_MS = 2000;
    public static final long CHART_EVERY_MS = 1000;
    public static final int COUNT_STEP_S = 5;
    public static final int LABEL_MAX = 40;
    /** Toasts and dialog titles are sentences; they get more room than a button label. */
    public static final int TEXT_MAX = 120;

    public static final String[] TAGS =
        { "TAP", "BACK", "NAV", "CMD", "ACK", "RX", "PMP", "UI", "LINK", "APP", "CHK" };
    private static final int TAP = 0, BACK = 1, NAV = 2, CMD = 3, ACK = 4, RX = 5, PMP = 6,
        UI = 7, LINK = 8, APP = 9, CHK = 10;

    public final Redact redact = new Redact();
    private final JournalWatch watch = new JournalWatch();
    private final long t0;

    private final StringBuilder pending = new StringBuilder(8192);
    private final String[] ring = new String[RING];
    private int ringNext, ringCount;
    private long written;
    private boolean capped;
    private final int[] tagCount = new int[TAGS.length];
    private final List<String> chk = new ArrayList<String>();

    private int screen = Integer.MIN_VALUE;
    private boolean masked = true;
    private final Map<String, String> uiLast = new HashMap<String, String>();

    // PMP compression
    private long lastSampleAt = -1, lastLoggedAt = -1;
    private double lastLoggedKpa = Double.NaN;
    private boolean lastLoggedNone, anyLogged, silentSaid;
    private int collapsed, lastSpd = Integer.MIN_VALUE;
    private String lastMode = "";

    // link
    private boolean linkUp;
    private String linkState = "";

    // chart: plain fields the app writes from the draw path, read on the tick
    /** Samples the app has pushed into the chart's ring, ever (monotonic). */
    public volatile long chartFed;
    /** chartFed as it was when the chart last drew. */
    public volatile long chartDrawn;
    /** The chart's target line, kPa (0 = none) and the big readout, kPa (NaN = "—"). */
    public volatile double chartTargetKpa, bigKpa = Double.NaN;
    private boolean chartShowing;
    private long chartLineAt = -1;
    private int lastCount = Integer.MIN_VALUE;

    public Journal(long t0) { this.t0 = t0; }

    /** The file's first lines: what is needed to read the rest, and nothing that names
     *  anyone. No Bluetooth address, no names. */
    public void header(String app, int api, String device, boolean sim, String unit) {
        synchronized (this) {
            pending.append("# OpenPump journal v1 - format: +MM:SS.cc TAG detail\n");
            pending.append("# app=").append(tokenSafe(app)).append(" android=").append(api)
                   .append(" device=").append(tokenSafe(device))
                   .append(" pump=").append(sim ? "sim" : "real")
                   .append(" unit=").append(tokenSafe(unit)).append('\n');
        }
    }

    /* ================================================================= user actions */

    /** A touch that landed on a control (or on nothing: pass control=false). */
    public void tap(long now, String label, boolean enabled, boolean control,
                    boolean inDialog, String val, int sig) {
        StringBuilder d = new StringBuilder(64);
        d.append(screenName(screen)).append(inDialog ? "/dialog" : "").append(' ')
         .append(quote(safeText(label)));
        if (!control) d.append(" nocontrol");
        else if (!enabled) d.append(" disabled");
        if (val != null) d.append(" val=").append(masked ? Redact.maskDigits(val) : val);
        if (control && enabled && sig != 0) d.append(" sig=").append(hex6(sig));
        emit(now, TAP, d.toString());
    }

    /** The screen's signature 800 ms after a tap - see JournalWatch's TAP_NO_EFFECT. */
    public void tapAfter(long now, int sig) { emit(now, UI, "after sig=" + hex6(sig)); }

    public void back(long now) { emit(now, BACK, screenName(screen)); }

    /** Called with the current screen whenever it may have changed; logs only a change. */
    public void nav(long now, int scr) {
        if (scr == screen) return;
        screen = scr;
        masked = Redact.maskedScreen(scr);
        uiLast.clear();
        lastCount = Integer.MIN_VALUE;
        emit(now, NAV, screenName(scr));
    }

    /** The app leaving or regaining the foreground (a share sheet, a system picker, Home). */
    public void lifecycle(long now, boolean resumed) {
        emit(now, NAV, resumed ? "resumed" : "paused");
    }

    public void dialog(long now, boolean open, String title) {
        emit(now, NAV, open ? "dialog " + quote(safeText(title, TEXT_MAX)) : "dialog-closed");
    }

    /* ======================================================================= pump */

    /** A frame the app handed to the pump, decoded, with the app's own name for it. */
    public void cmd(long now, byte[] frame, String what) {
        emit(now, CMD, decode(frame) + " " + quote(clip(redact.scrub(what), 60)));
    }

    /** Any frame from the pump that is not telemetry. An ack (`<op> 01`) and a REFUSAL
     *  (`<op> FD`, Proto#REFUSED) are both the pump's answer to a command, so both are ACK
     *  lines with their latency, and both settle the command JournalWatch is owed an answer
     *  for; a refusal says REFUSED. It used to be left as bare hex (`RX 2C FD`, 23 times in
     *  the owner's journal of 22 Sep), and the command it answered counted as unanswered. */
    public void frame(long now, byte[] raw) {
        if (raw != null && raw.length == 2
                && ((raw[1] & 0xFF) == 0x01 || (raw[1] & 0xFF) == Proto.REFUSED)) {
            String op = opName(raw[0] & 0xFF);
            boolean refused = (raw[1] & 0xFF) == Proto.REFUSED;
            long ms;
            synchronized (this) { ms = watch.ackLatency(op, now - t0); }
            emit(now, ACK, op + (refused ? " REFUSED" : "") + (ms >= 0 ? " ms=" + ms : ""));
            return;
        }
        emit(now, RX, hex(raw, 48));
    }

    /**
     * One telemetry sample. Returns without allocating when the sample is collapsed into
     * the next line, which is almost every sample during a steady hold.
     */
    public void sample(long now, double kpa, boolean noReading, int spd, String mode) {
        long gap = lastSampleAt < 0 ? 0 : now - lastSampleAt;
        lastSampleAt = now;
        collapsed++;
        boolean modeChanged = mode != null && !mode.equals(lastMode);
        boolean log = !anyLogged
            || noReading != lastLoggedNone
            || (!noReading && Math.abs(kpa - lastLoggedKpa) >= PMP_STEP_KPA)
            || now - lastLoggedAt >= PMP_HEARTBEAT_MS
            || spd != lastSpd || modeChanged
            || gap > JournalWatch.SILENT_MS || silentSaid;
        if (!log) return;
        StringBuilder d = new StringBuilder(40);
        if (noReading) d.append("none"); else d.append(JournalWatch.kpa1(kpa));
        d.append(" n=").append(collapsed);
        if (spd != lastSpd) d.append(" spd=").append(spd);
        if (modeChanged) d.append(" mode=").append(tokenSafe(mode));
        if (gap > JournalWatch.SILENT_MS || silentSaid)
            d.append(" gap=").append(JournalWatch.secs(gap).replace(" s", ""));
        anyLogged = true;
        silentSaid = false;
        lastLoggedAt = now;
        lastLoggedKpa = noReading ? lastLoggedKpa : kpa;
        lastLoggedNone = noReading;
        lastSpd = spd;
        if (modeChanged) lastMode = mode;
        collapsed = 0;
        emit(now, PMP, d.toString());
    }

    public void link(long now, String state, boolean up) {
        String s = state == null ? "" : state;
        if (up == linkUp && s.equals(linkState)) return;
        linkUp = up;
        linkState = s;
        if (!up) { lastSampleAt = -1; silentSaid = false; }
        emit(now, LINK, (up ? "up " : "down ") + quote(safeText(s)));
    }

    /* ===================================================================== screen */

    /**
     * A run-screen cell showing a PRESSURE: the number as displayed, its caption (the unit
     * word, or "sending…" while an edit is on its way) and the display unit, from which the
     * number is converted back to kPa. Logged only when it changes.
     */
    public void pressureCell(long now, String key, CharSequence shown, CharSequence caption,
                             String unit) {
        String s = shown == null ? "" : shown.toString();
        double k = shownKpa(s, unit);
        cell(now, key, Double.isNaN(k) ? "-" : JournalWatch.kpa1(k), s, caption);
    }

    /** A run-screen cell showing seconds or a percentage. */
    public void plainCell(long now, String key, CharSequence shown, CharSequence caption) {
        String s = shown == null ? "" : shown.toString();
        double v = firstNumber(s);
        cell(now, key, Double.isNaN(v) ? "-" : trimNum(v), s, caption);
    }

    private void cell(long now, String key, String value, String shown, CharSequence caption) {
        String cap = caption == null ? "" : caption.toString();
        boolean sending = cap.startsWith("sending");
        // "not confirmed": the pump is not answering, and the figure is what it MAY be at.
        boolean unconfirmed = cap.startsWith("not confirmed");
        String d = key + "=" + value + " " + quote(clip(shown + (cap.length() > 0 && !sending
                && !unconfirmed ? " " + cap : "")))
            + (sending ? " sending" : "") + (unconfirmed ? " unconfirmed" : "");
        ui(now, key, d);
    }

    /** A named screen value, logged only when it changes. `value` is free text. */
    public void uiText(long now, String key, String value) {
        ui(now, key, key + " " + quote(safeText(value)));
    }

    /** A named screen state (a single token such as "hold" or "run"). */
    public void uiState(long now, String key, String token) {
        ui(now, key, key + "=" + tokenSafe(token));
    }

    /** The run countdown, in seconds left: logged each 5 s, on any jump, and at zero. */
    public void countdown(long now, int secondsLeft) {
        if (secondsLeft == lastCount) return;
        boolean jump = lastCount == Integer.MIN_VALUE || secondsLeft > lastCount
            || lastCount - secondsLeft > COUNT_STEP_S + 1;
        if (!jump && secondsLeft % COUNT_STEP_S != 0 && secondsLeft != 0) return;
        lastCount = secondsLeft;
        emit(now, UI, "count=" + (secondsLeft < 0 ? "waiting" : String.valueOf(secondsLeft)));
    }

    public void toast(long now, String text) { emit(now, UI, "toast " + quote(safeText(text, TEXT_MAX))); }

    private void ui(long now, String key, String detail) {
        String prev = uiLast.get(key);
        if (detail.equals(prev)) return;
        uiLast.put(key, detail);
        emit(now, UI, detail);
    }

    /* ======================================================================== app */

    public void app(long now, String line) {
        emit(now, APP, oneLine(redact.scrub(line)));
    }

    /**
     * The heartbeat, about twice a second: silence on the link, the chart line while the
     * run chart is on screen, and the watcher's timeouts.
     */
    public void tick(long now, boolean chartVisible) {
        if (linkUp && lastSampleAt >= 0 && !silentSaid
                && now - lastSampleAt > JournalWatch.SILENT_MS) {
            silentSaid = true;
            emit(now, PMP, "silent");
        }
        if (chartVisible) {
            if (!chartShowing || now - chartLineAt >= CHART_EVERY_MS) {
                chartShowing = true;
                chartLineAt = now;
                double big = bigKpa;
                emit(now, UI, "chart n=" + chartDrawn + " of=" + chartFed
                    + " cmd=" + Math.round(chartTargetKpa)
                    + " big=" + (Double.isNaN(big) ? "-" : JournalWatch.kpa1(big)));
            }
        } else if (chartShowing) {
            chartShowing = false;
            emit(now, UI, "chart off");
        }
        List<String> out = new ArrayList<String>(0);
        synchronized (this) { watch.tick(now - t0, out); }
        for (int i = 0; i < out.size(); i++) emitChk(now, out.get(i));
    }

    /** The end-of-log summary: one line per rule and the count of events by tag. */
    public void summary(long now, String why) {
        StringBuilder sb = new StringBuilder();
        synchronized (this) {
            sb.append("# summary (").append(why).append(") at ").append(stampOf(now)).append('\n');
            List<String> rules = watch.summary();
            for (int i = 0; i < rules.size(); i++) sb.append("# ").append(rules.get(i)).append('\n');
            sb.append("# events");
            for (int i = 0; i < TAGS.length; i++) sb.append(' ').append(TAGS[i]).append('=').append(tagCount[i]);
            sb.append(" bytes=").append(written + pending.length()).append('\n');
            pending.append(sb);
        }
    }

    /* ==================================================================== output */

    /** Everything written since the last drain, for the app to put on disk. */
    public synchronized String drain() {
        if (pending.length() == 0) return "";
        String s = pending.toString();
        written += s.length();
        pending.setLength(0);
        return s;
    }

    /** The last n lines, oldest first, whatever the file cap dropped. */
    public synchronized List<String> recent(int n) {
        List<String> out = new ArrayList<String>();
        int k = Math.min(n, ringCount);
        for (int i = k; i > 0; i--) out.add(ring[(ringNext - i + RING) % RING]);
        return out;
    }

    /** Every CHK line so far. */
    public synchronized List<String> findings() { return new ArrayList<String>(chk); }

    public synchronized int count(String tag) {
        for (int i = 0; i < TAGS.length; i++) if (TAGS[i].equals(tag)) return tagCount[i];
        return 0;
    }

    public synchronized long bytes() { return written + pending.length(); }

    /* ==================================================================== internals */

    private void emit(long now, int tag, String detail) {
        List<String> out = null;
        synchronized (this) {
            String line = stampOf(now) + ' ' + TAGS[tag] + ' ' + detail;
            ring[ringNext] = line;
            ringNext = (ringNext + 1) % RING;
            if (ringCount < RING) ringCount++;
            tagCount[tag]++;
            boolean detailTag = tag == PMP || tag == UI || tag == RX;
            if (!capped && written + pending.length() + line.length() > FILE_CAP) {
                capped = true;
                pending.append(stampOf(now)).append(" APP journal is over ")
                       .append(FILE_CAP / 1000000).append(" MB: pump readings and screen values are no longer written;"
                        + " taps, commands, acks, checks and app lines still are\n");
            }
            if (!capped || !detailTag) pending.append(line).append('\n');
            if (tag == CHK) { chk.add(line); return; }
            out = new ArrayList<String>(0);
            watch.onLine(now - t0, TAGS[tag], detail, out);
        }
        for (int i = 0; i < out.size(); i++) emitChk(now, out.get(i));
    }

    private void emitChk(long now, String detail) { emit(now, CHK, detail); }

    /** +MM:SS.cc since the log opened; minutes grow past 99 rather than wrapping. */
    String stampOf(long now) { return stamp(now - t0); }

    /** The same stamp for a time already measured from the log's start. */
    public static String stamp(long sinceStartMs) {
        long cs = Math.max(0, sinceStartMs) / 10;
        long m = cs / 6000, s = (cs / 100) % 60, c = cs % 100;
        StringBuilder b = new StringBuilder(10);
        b.append('+');
        if (m < 10) b.append('0');
        b.append(m).append(':');
        if (s < 10) b.append('0');
        b.append(s).append('.');
        if (c < 10) b.append('0');
        b.append(c);
        return b.toString();
    }

    /** Parse a line's stamp back to ms since the log opened, or -1. */
    public static long parseStamp(String st) {
        if (st == null || st.length() < 9 || st.charAt(0) != '+') return -1;
        int colon = st.indexOf(':'), dot = st.indexOf('.');
        if (colon < 2 || dot < colon) return -1;
        try {
            long m = Long.parseLong(st.substring(1, colon));
            long s = Long.parseLong(st.substring(colon + 1, dot));
            long c = Long.parseLong(st.substring(dot + 1));
            return (m * 60 + s) * 1000 + c * 10;
        } catch (NumberFormatException e) { return -1; }
    }

    /** Free text for a label/title/toast: scrubbed, digits masked where the screen says so,
     *  one line, clipped. */
    private String safeText(String s) { return safeText(s, LABEL_MAX); }

    private String safeText(String s, int max) {
        String t = oneLine(redact.scrub(s));
        if (masked) t = Redact.maskDigits(t);
        return clip(t, max);
    }

    static String quote(String s) { return '"' + s.replace('"', '\'') + '"'; }

    static String clip(String s) { return clip(s, LABEL_MAX); }

    static String clip(String s, int max) {
        if (s == null) return "";
        String t = oneLine(s).trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    static String oneLine(String s) {
        if (s == null) return "";
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                if (b == null) { b = new StringBuilder(s.length()); b.append(s, 0, i); }
                b.append(c == '\n' ? " | " : " ");
            } else if (b != null) b.append(c);
        }
        return b == null ? s : b.toString();
    }

    private static String tokenSafe(String s) {
        if (s == null || s.length() == 0) return "-";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return b.toString();
    }

    private static String hex6(int sig) {
        String h = Integer.toHexString(sig & 0xFFFFFF);
        return "000000".substring(h.length()) + h;
    }

    static String trimNum(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return JournalWatch.kpa1(v);
    }

    /** The first decimal number in a string (sign ignored), or NaN. "75 %" -> 75. */
    static double firstNumber(String s) {
        if (s == null) return Double.NaN;
        int i = 0, n = s.length();
        while (i < n && !(Character.isDigit(s.charAt(i)))) i++;
        if (i >= n) return Double.NaN;
        int j = i;
        while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
        try { return Double.parseDouble(s.substring(i, j)); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /** A pressure as the screen printed it, back in kPa: the inverse of Model.Fmt.p for the
     *  given unit. The sign is ignored (vacuum reads negative in the mercury units). */
    public static double shownKpa(String shown, String unit) {
        double v = firstNumber(shown);
        if (Double.isNaN(v)) return v;
        if (Model.Fmt.U_KPA.equals(unit)) return v;
        if (Model.Fmt.U_CMHG.equals(unit)) return v * Model.Fmt.KPA_PER_CMHG;
        return v * Model.Fmt.KPA_PER_INHG;
    }

    /* ================================================================== decoding */

    /** A frame the app writes, in words. */
    public static String decode(byte[] f) {
        if (f == null || f.length < 3 || (f[0] & 0xFF) != 0x66 || (f[1] & 0xFF) != 0x2A)
            return "raw " + hex(f, 16);
        int op = f[2] & 0xFF;
        switch (op) {
            case Proto.OP_ADD:
                if (f.length < 8) return "add short=" + hex(f, 16).replace(' ', '.');
                return "add spd=" + Proto.speedPct(f[3] & 0xFF) + " up=" + (f[4] & 0xFF)
                    + " uh=" + (f[5] & 0xFF) + " lo=" + (f[6] & 0xFF) + " lh=" + (f[7] & 0xFF);
            case Proto.OP_START: return "start slot=" + (f.length > 3 ? f[3] & 0xFF : -1);
            case Proto.OP_DELETE: return "delete slot=" + (f.length > 3 ? f[3] & 0xFF : -1);
            case Proto.OP_STOP: return "stop";
            case Proto.OP_LIST: return "list";
            case Proto.OP_TIME: return "time";
            default: return "op=" + Integer.toHexString(op) + " len=" + f.length;
        }
    }

    static String opName(int op) {
        switch (op) {
            case Proto.OP_ADD: return "add";
            case Proto.OP_START: return "start";
            case Proto.OP_DELETE: return "delete";
            case Proto.OP_STOP: return "stop";
            case Proto.OP_LIST: return "list";
            case Proto.OP_TIME: return "time";
            default: return "op=" + Integer.toHexString(op);
        }
    }

    static String hex(byte[] b, int max) {
        if (b == null) return "-";
        StringBuilder sb = new StringBuilder(b.length * 3);
        int n = Math.min(b.length, max);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            int v = b[i] & 0xFF;
            sb.append(Character.toUpperCase(Character.forDigit(v >> 4, 16)))
              .append(Character.toUpperCase(Character.forDigit(v & 15, 16)));
        }
        if (b.length > max) sb.append(" +").append(b.length - max);
        return sb.toString();
    }

    /* ============================================================== screen names */

    /** Short stable names for Nav's screens, as NAV lines print them. */
    public static String screenName(int s) {
        switch (s) {
            case Nav.SCR_TODAY: return "today";
            case Nav.SCR_MANUAL: return "manual";
            case Nav.SCR_CONNECT: return "connect";
            case Nav.SCR_LOG_READING: return "log-reading";
            case Nav.SCR_LIBRARY: return "library";
            case Nav.SCR_ROUTINES: return "routines";
            case Nav.SCR_SETS: return "sets";
            case Nav.SCR_ROUT_EDIT: return "routine-edit";
            case Nav.SCR_STAGE_EDIT: return "stage-edit";
            case Nav.SCR_SET_EDIT: return "set-edit";
            case Nav.SCR_PICKER: return "picker";
            case Nav.SCR_ASSESS_EDIT: return "assess-edit";
            case Nav.SCR_USED_IN: return "used-in";
            case Nav.SCR_PROGRESS: return "progress";
            case Nav.SCR_SESSION_HIST: return "session-history";
            case Nav.SCR_MEAS_HIST: return "meas-history";
            case Nav.SCR_COMPARE: return "compare";
            case Nav.SCR_MEAS_EDIT: return "meas-edit";
            case Nav.SCR_SAVE_CARD: return "save-card";
            case Nav.SCR_EXPORT: return "export";
            case Nav.SCR_GALLERY: return "gallery";
            case Nav.SCR_PHOTO: return "photo";
            case Nav.SCR_SETTINGS: return "settings";
            case Nav.SCR_DIAGNOSTICS: return "diagnostics";
            case Nav.SCR_VALIDATE_INTRO: return "validate-intro";
            case Nav.SCR_HELP: return "help";
            case Nav.SCR_TRAINER: return "trainer";
            case Nav.SCR_HOLD: return "hold";
            case Nav.SCR_BASELINE: return "baseline";
            case Nav.SCR_CAMERA: return "camera";
            case Nav.SCR_RELEASE: return "release";
            case Nav.SCR_SEAL: return "seal";
            case Nav.SCR_ASSESS: return "assess";
            case Nav.SCR_RUN: return "run";
            case Nav.SCR_SUMMARY: return "summary";
            case Nav.SCR_MEASURE_AFTER: return "measure-after";
            case Nav.SCR_LINK_LOST: return "link-lost";
            case Nav.SCR_VALIDATE_RUN: return "validate-run";
            case Nav.SCR_GUIDED: return "guided";
            case Nav.SCR_SETUP: return "setup";
            default: return s == Integer.MIN_VALUE ? "none" : "screen" + s;
        }
    }
}
