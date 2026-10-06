package org.openpump;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * THE LOG READS ITSELF. Consumes the {@link Journal}'s own lines - the same text that is
 * written to the file - and says when what the person did, what the app showed and what the
 * pump did stop agreeing with each other. Every finding is one CHK line starting "!!".
 *
 * It sees ONLY the journal's lines, never the app's internals. That is deliberate: the
 * offline tool (JournalCheck, in the test source set) replays a log file through a fresh instance of this
 * class and gets the same findings the phone got, so a log sent in months ago can be
 * re-checked with rules that have improved since. Time is handed in; nothing reads a clock.
 *
 * The pump's preset table is modelled from the commands alone: the app clears it with nine
 * "delete slot 0" before every upload (Delete COMPACTS, so nine of them empty it whatever it
 * held), Add appends, Delete compacts, and a work-mode dump (RX 29 ...) states it outright.
 * "start slot=n" then says exactly what the pump was asked to do. Until the table is known
 * the pressure rules stay quiet rather than guess.
 *
 * Each rule reports once per episode and then waits out a cooldown, so a fault that
 * persists produces one line and a count, not a flood.
 */
public final class JournalWatch {

    public static final String NO_ACK = "NO_ACK";
    public static final String PUMP_NOT_FOLLOWING = "PUMP_NOT_FOLLOWING";
    public static final String PUMP_ABOVE_COMMAND = "PUMP_ABOVE_COMMAND";
    public static final String STOP_NOT_VENTING = "STOP_NOT_VENTING";
    public static final String READINGS_SILENT = "READINGS_SILENT";
    public static final String CHART_FROZEN = "CHART_FROZEN";
    public static final String SCREEN_MISMATCH = "SCREEN_MISMATCH";
    public static final String TAP_NO_EFFECT = "TAP_NO_EFFECT";

    public static final String[] RULES = { NO_ACK, PUMP_NOT_FOLLOWING, PUMP_ABOVE_COMMAND,
        STOP_NOT_VENTING, READINGS_SILENT, CHART_FROZEN, SCREEN_MISMATCH, TAP_NO_EFFECT };

    /* ---- thresholds, named once ------------------------------------------------------ */
    public static final long ACK_TIMEOUT_MS = 2000;
    /** A raise is "reached" at up - this; a lowering at up + this. */
    public static final double FOLLOW_BAND_KPA = 1.5;
    public static final long FOLLOW_MIN_WINDOW_MS = 12000;
    public static final long FOLLOW_SLACK_MS = 8000;
    /** The simulator's full-speed pull rate; the window allows 1.5x the time it implies. */
    public static final double PULL_KPA_PER_S = SimPump.PULL_KPA_PER_S_AT_FULL;
    public static final double DROP_KPA_PER_S = SimPump.DROP_KPA_PER_S;
    public static final double ABOVE_KPA = 3.0;
    public static final long ABOVE_MS = 2000;
    public static final long VENT_WINDOW_MS = 12000;
    public static final double VENT_FLOOR_KPA = 1.5;
    public static final double VENT_FRACTION = 0.25;
    public static final long SILENT_MS = 1500;
    public static final long CHART_STALL_MS = 1500;
    public static final long MISMATCH_MS = 1000;
    public static final long PENDING_MS = 6000;
    public static final double TOL_KPA = 0.6;
    public static final double TOL_S = 1.0;
    public static final double TOL_PCT = 1.0;
    public static final long COOLDOWN_MS = 10000;

    /* ---- what the log has said so far ------------------------------------------------ */
    private String screen = "";
    private boolean linkUp;

    /** The pump's preset table, modelled from CMD/RX lines. Each entry {spd, up, uh, lo, lh}. */
    private final List<int[]> table = new ArrayList<int[]>();
    private boolean tableKnown;
    private int zeroDeletes;

    private boolean running;
    /** The preset the pump was last started on, or null when unknown / stopped. */
    private int[] cur;
    /** For each START still owing its answer, in order: what the pump was running before it
     *  ({running ? 1 : 0} and the preset). A START the pump REFUSES (`2C FD`) leaves the pump on
     *  that - the owner's pump cycled on at 24 kPa through four refused raises - so the cells
     *  are compared with it, not with the preset that was refused. */
    private final List<Object[]> beforeStart = new ArrayList<Object[]>();

    /** Commands that owe an ack: {opCode, sentAt, reported(0/1)}. */
    private final List<long[]> owed = new ArrayList<long[]>();
    private final List<String> owedWhat = new ArrayList<String>();

    private double kpa = Double.NaN;           // last logged reading, NaN when none
    private long pmpAt = -1, pmpMoveAt = -1;

    // PUMP_NOT_FOLLOWING
    private boolean fActive, fRaise;
    private int fTarget;
    private long fStart, fWindow;
    private double fBest;
    /** A lowering that was reported as not followed: the reading staying high is that same
     *  fault, so ABOVE_COMMAND does not report it a second time. */
    private boolean fLowFailed;
    // PUMP_ABOVE_COMMAND
    private long aboveSince = -1;
    private boolean aboveFired;
    // STOP_NOT_VENTING
    private boolean vActive;
    private long vStart;
    private double vFrom, vTarget, vLowest;
    // READINGS_SILENT
    private boolean silentFired;
    // CHART_FROZEN
    private boolean chartOn;
    private long chartN = -1, chartOf = -1, nChangeAt, ofChangeAt;
    private long ofAtNChange;
    private int samplesSinceOf;
    private boolean chartFired;
    // SCREEN_MISMATCH
    private String runState = "";
    private final Map<String, Cell> cells = new HashMap<String, Cell>();
    /** The chart's target line and the big readout: compared like cells, kept apart from
     *  them because they are not fields of a preset. */
    private final Cell target = new Cell(), readout = new Cell();
    // TAP_NO_EFFECT
    private boolean tapPending, tapEffect;
    private String tapSig = "", tapLabel = "", tapScreen = "";

    private final Map<String, int[]> counts = new HashMap<String, int[]>();   // {found, suppressed}
    private final Map<String, Long> lastFired = new HashMap<String, Long>();

    private static final class Cell {
        double value = Double.NaN;
        String shown = "";
        boolean pending;
        long pendingSince = -1, badSince = -1;
        boolean pendingFired, badFired;
        /** It says "not confirmed": compared with nothing. */
        boolean unconfirmed;
    }

    public JournalWatch() {
        for (int i = 0; i < RULES.length; i++) counts.put(RULES[i], new int[2]);
    }

    /* =============================================================== the public face */

    /** How long ago an ack for this op was owed, or -1 when nothing is waiting. Does not
     *  consume: the ACK line itself, fed through {@link #onLine}, does that. */
    public long ackLatency(String op, long t) {
        int code = opCode(op);
        for (int i = 0; i < owed.size(); i++)
            if (owed.get(i)[0] == code) return t - owed.get(i)[1];
        return -1;
    }

    /** Found / suppressed counts for one rule. */
    public int found(String rule) { int[] c = counts.get(rule); return c == null ? 0 : c[0]; }
    public int suppressed(String rule) { int[] c = counts.get(rule); return c == null ? 0 : c[1]; }

    /** One line per rule, for the end of a log. */
    public List<String> summary() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < RULES.length; i++) {
            int[] c = counts.get(RULES[i]);
            out.add("rule " + RULES[i] + " found=" + c[0] + (c[1] > 0 ? " repeats=" + c[1] : ""));
        }
        return out;
    }

    /** Time passing with nothing said: the timeouts. */
    public void tick(long t, List<String> out) {
        // NO_ACK
        for (int i = 0; i < owed.size(); i++) {
            long[] o = owed.get(i);
            if (o[2] == 0 && t - o[1] > ACK_TIMEOUT_MS) {
                o[2] = 1;
                fire(t, NO_ACK, owedWhat.get(i) + ": no ack " + secs(t - o[1]) + " after it was sent", out);
            }
        }
        for (int i = owed.size() - 1; i >= 0; i--)
            if (t - owed.get(i)[1] > 30000) { owed.remove(i); owedWhat.remove(i); }
        // PUMP_NOT_FOLLOWING
        if (fActive && t - fStart > fWindow) {
            fActive = false;
            if (!fRaise) fLowFailed = true;
            fire(t, PUMP_NOT_FOLLOWING, (fRaise ? "raised to " : "lowered to ") + fTarget
                + " kPa, " + (Double.isNaN(fBest) ? "no reading" : (fRaise ? "best " : "lowest ")
                + kpa1(fBest)) + " after " + secs(t - fStart), out);
        }
        checkAbove(t, out);
        // STOP_NOT_VENTING
        if (vActive && t - vStart > VENT_WINDOW_MS) {
            vActive = false;
            fire(t, STOP_NOT_VENTING, "stop at " + kpa1(vFrom) + " kPa, still "
                + kpa1(vLowest) + " after " + secs(t - vStart) + " (expected <= "
                + kpa1(vTarget) + ")", out);
        }
        checkCells(t, out);
    }

    /** One journal line: its time, tag and everything after the tag. */
    public void onLine(long t, String tag, String detail, List<String> out) {
        tick(t, out);
        List<String> tk = tokens(detail);
        if ("CMD".equals(tag)) onCmd(t, tk, out);
        else if ("ACK".equals(tag)) onAck(tk);
        else if ("RX".equals(tag)) onRx(tk);
        else if ("PMP".equals(tag)) onPmp(t, tk, out);
        else if ("NAV".equals(tag)) onNav(t, tk);
        else if ("LINK".equals(tag)) onLink(tk);
        else if ("UI".equals(tag)) onUi(t, tk, out);
        else if ("TAP".equals(tag)) onTap(tk);
        else if ("BACK".equals(tag)) tapEffect = true;
    }

    /* ==================================================================== the events */

    private void onCmd(long t, List<String> tk, List<String> out) {
        tapEffect = true;
        if (tk.isEmpty()) return;
        String op = tk.get(0);
        String what = unquote(last(tk));
        if ("add".equals(op)) {
            int[] p = { num(tk, "spd"), num(tk, "up"), num(tk, "uh"), num(tk, "lo"), num(tk, "lh") };
            zeroDeletes = 0;
            // The pump does not store an all-zero preset (the owner's journal of 22 Sep): the
            // table model does not either, or every later slot would be one off.
            boolean allZero = p[0] == 0 && p[1] == 0 && p[2] == 0 && p[3] == 0 && p[4] == 0;
            if (tableKnown && table.size() < Proto.SLOTS && !allZero) table.add(p);
            owe(t, Proto.OP_ADD, "add up=" + p[1] + (what.length() > 0 ? " (" + what + ")" : ""));
        } else if ("delete".equals(op)) {
            int s = num(tk, "slot");
            if (s == 0) zeroDeletes++; else zeroDeletes = 0;
            if (tableKnown && s >= 0 && s < table.size()) table.remove(s);
            if (zeroDeletes >= Proto.SLOTS) { table.clear(); tableKnown = true; }
        } else if ("start".equals(op)) {
            zeroDeletes = 0;
            int s = num(tk, "slot");
            owe(t, Proto.OP_START, "start slot=" + s + (what.length() > 0 ? " (" + what + ")" : ""));
            int[] next = (tableKnown && s >= 0 && s < table.size()) ? table.get(s) : null;
            boolean sameTarget = next != null && fActive && next[1] == fTarget;
            beforeStart.add(new Object[]{ Boolean.valueOf(running), cur });
            running = true;
            cur = next;
            fLowFailed = false;
            aboveSince = -1; aboveFired = false;
            vActive = false;
            for (Cell c : cells.values()) { c.badSince = -1; c.badFired = false; }
            if (next == null) { fActive = false; return; }
            if (sameTarget) return;               // a continuation: the same aim, the same clock
            beginFollow(t, next);
        } else if ("stop".equals(op)) {
            zeroDeletes = 0;
            owe(t, Proto.OP_STOP, "stop" + (what.length() > 0 ? " (" + what + ")" : ""));
            running = false;
            cur = null;
            fActive = false;
            aboveSince = -1;
            if (!vActive && !Double.isNaN(kpa) && kpa > VENT_FLOOR_KPA) {
                vActive = true;
                vStart = t;
                vFrom = kpa;
                vLowest = kpa;
                vTarget = Math.max(VENT_FLOOR_KPA, VENT_FRACTION * kpa);
            }
        }
    }

    private void beginFollow(long t, int[] p) {
        double from = Double.isNaN(kpa) ? 0.0 : kpa;
        int up = p[1];
        if (from < up - FOLLOW_BAND_KPA) {
            double rate = PULL_KPA_PER_S * Math.max(10, p[0]) / 100.0;
            long pull = (long) (1.5 * (up - from) / rate * 1000.0);
            fRaise = true;
            fWindow = Math.max(FOLLOW_MIN_WINDOW_MS,
                Math.max((p[2] + p[4]) * 1000L + FOLLOW_SLACK_MS, pull + FOLLOW_SLACK_MS));
        } else if (from > up + FOLLOW_BAND_KPA) {
            long drop = (long) (1.5 * (from - up) / DROP_KPA_PER_S * 1000.0);
            fRaise = false;
            fWindow = Math.max(FOLLOW_MIN_WINDOW_MS,
                (p[2] + p[4]) * 1000L + FOLLOW_SLACK_MS + drop);
        } else {
            fActive = false;
            return;
        }
        fActive = true;
        fTarget = up;
        fStart = t;
        fBest = Double.NaN;
    }

    private void onAck(List<String> tk) {
        if (tk.isEmpty()) return;
        int code = opCode(tk.get(0));
        if (code == Proto.OP_START && !beforeStart.isEmpty()) {
            Object[] b = beforeStart.remove(0);
            if (tk.contains("REFUSED")) {
                // The START never ran. With nothing started after it, the pump is on what it
                // ran before; with a later START still owed, THAT one replaced this "before".
                if (beforeStart.isEmpty()) {
                    running = ((Boolean) b[0]).booleanValue();
                    cur = (int[]) b[1];
                    fActive = false;
                    for (Cell c : cells.values()) { c.badSince = -1; c.badFired = false; }
                } else {
                    beforeStart.set(0, b);
                }
            }
        }
        for (int i = 0; i < owed.size(); i++)
            if (owed.get(i)[0] == code) { owed.remove(i); owedWhat.remove(i); return; }
    }

    /** A work-mode dump states the whole table: 29, then five bytes per preset. */
    private void onRx(List<String> tk) {
        if (tk.isEmpty() || !"29".equals(tk.get(0))) return;
        List<int[]> t = new ArrayList<int[]>();
        int[] b = new int[tk.size() - 1];
        for (int i = 1; i < tk.size(); i++) {
            try { b[i - 1] = Integer.parseInt(tk.get(i), 16); } catch (NumberFormatException e) { return; }
        }
        if (b.length % 5 != 0) return;
        for (int i = 0; i + 4 < b.length; i += 5)
            t.add(new int[]{ Proto.speedPct(b[i]), b[i + 1], b[i + 2], b[i + 3], b[i + 4] });
        table.clear();
        table.addAll(t);
        tableKnown = true;
    }

    private void onPmp(long t, List<String> tk, List<String> out) {
        if (tk.isEmpty()) return;
        String v = tk.get(0);
        if ("silent".equals(v)) {
            if (!silentFired) {
                silentFired = true;
                fire(t, READINGS_SILENT, "no reading for " + secs(SILENT_MS) + " with the link up", out);
            }
            return;
        }
        String gap = kv(tk, "gap");
        if (gap != null) {
            double g = dbl(gap);
            if (!silentFired && g * 1000 > SILENT_MS)
                fire(t, READINGS_SILENT, "readings stopped for " + gap + " s", out);
            silentFired = false;
        }
        int n = num(tk, "n");
        samplesSinceOf += Math.max(1, n);
        pmpAt = t;
        if ("none".equals(v)) {
            kpa = Double.NaN;
            // A stop that ends in "no measurement" cannot be judged either way.
            vActive = false;
            return;
        }
        double k = dbl(v);
        if (Double.isNaN(k)) return;
        if (Double.isNaN(kpa) || Math.abs(k - kpa) >= 0.25) pmpMoveAt = t;
        kpa = k;
        if (fActive) {
            if (fRaise) {
                fBest = Double.isNaN(fBest) ? k : Math.max(fBest, k);
                if (k >= fTarget - FOLLOW_BAND_KPA) fActive = false;
            } else {
                fBest = Double.isNaN(fBest) ? k : Math.min(fBest, k);
                if (k <= fTarget + FOLLOW_BAND_KPA) fActive = false;
            }
        }
        if (vActive) {
            vLowest = Math.min(vLowest, k);
            if (k <= vTarget) vActive = false;
        }
        checkAbove(t, out);
    }

    private void checkAbove(long t, List<String> out) {
        boolean above = running && cur != null && !Double.isNaN(kpa)
            && !(fActive && !fRaise) && !fLowFailed && kpa > cur[1] + ABOVE_KPA;
        if (!above) { aboveSince = -1; aboveFired = false; return; }
        if (aboveSince < 0) aboveSince = t;
        if (!aboveFired && t - aboveSince >= ABOVE_MS) {
            aboveFired = true;
            fire(t, PUMP_ABOVE_COMMAND, "reading " + kpa1(kpa) + " kPa, commanded " + cur[1]
                + " kPa, for " + secs(t - aboveSince), out);
        }
    }

    private void onNav(long t, List<String> tk) {
        tapEffect = true;
        if (tk.isEmpty()) return;
        String s = tk.get(0);
        if (s.startsWith("dialog") || "paused".equals(s) || "resumed".equals(s)) return;
        if (!s.equals(screen)) {
            screen = s;
            cells.clear();
            chartOn = false;
            runState = "";
        }
    }

    private void onLink(List<String> tk) {
        boolean up = !tk.isEmpty() && "up".equals(tk.get(0));
        if (up == linkUp) return;
        linkUp = up;
        if (!up) {
            owed.clear(); owedWhat.clear();
            running = false; cur = null;
            fActive = false; vActive = false; aboveSince = -1;
            silentFired = false;
            tableKnown = false; table.clear();
        }
    }

    private void onTap(List<String> tk) {
        tapPending = false;
        if (tk.size() < 2) return;
        if (tk.contains("disabled") || tk.contains("nocontrol")) return;
        String sig = kv(tk, "sig");
        if (sig == null) return;
        tapPending = true;
        tapEffect = false;
        tapSig = sig;
        tapScreen = tk.get(0);
        tapLabel = tk.get(1);
    }

    private void onUi(long t, List<String> tk, List<String> out) {
        if (tk.isEmpty()) return;
        String first = tk.get(0);
        int eq = first.indexOf('=');
        String key = eq < 0 ? first : first.substring(0, eq);
        String val = eq < 0 ? "" : first.substring(eq + 1);
        if ("after".equals(key)) {
            String sig = kv(tk, "sig");
            if (tapPending && !tapEffect && sig != null && sig.equals(tapSig))
                fire(t, TAP_NO_EFFECT, "TAP " + tapScreen + " " + tapLabel
                    + ": nothing changed on screen, no command, no dialog", out);
            tapPending = false;
            return;
        }
        if ("chart".equals(key)) { onChart(t, tk, out); return; }
        if ("count".equals(key)) return;
        tapEffect = true;
        if ("state".equals(key)) { runState = val; return; }
        if (isCell(key)) {
            Cell c = cells.get(key);
            if (c == null) { c = new Cell(); cells.put(key, c); }
            boolean pending = tk.contains("sending");
            // A cell saying "not confirmed" (the pump is not answering) shows what the pump MAY
            // be at - the higher figure - and is no claim to compare with the wire.
            c.unconfirmed = tk.contains("unconfirmed");
            double v = "-".equals(val) ? Double.NaN : dbl(val);
            if (pending && !c.pending) { c.pendingSince = t; c.pendingFired = false; }
            c.pending = pending;
            if (Math.abs(v - c.value) > 1e-9 || Double.isNaN(v) != Double.isNaN(c.value)) {
                c.value = v;
                c.badSince = -1;
                c.badFired = false;
            }
            c.shown = tk.size() > 1 ? tk.get(1) : "";
            checkCells(t, out);
        }
    }

    private static boolean isCell(String k) {
        return "pull".equals(k) || "drop".equals(k) || "hold".equals(k)
            || "dropt".equals(k) || "speed".equals(k);
    }

    /** The field of the running preset a cell must agree with. */
    private static int wireIndex(String k) {
        if ("pull".equals(k)) return 1;
        if ("drop".equals(k)) return 3;
        if ("hold".equals(k)) return 2;
        if ("dropt".equals(k)) return 4;
        if ("speed".equals(k)) return 0;
        return -1;
    }

    private void checkCells(long t, List<String> out) {
        if (cells.isEmpty()) return;
        boolean comparable = "run".equals(screen) && running && cur != null
            && ("run".equals(runState) || "adjusted".equals(runState));
        for (Map.Entry<String, Cell> e : cells.entrySet()) {
            String k = e.getKey();
            Cell c = e.getValue();
            if (c.pending) {
                if (comparable && !c.pendingFired && t - c.pendingSince > PENDING_MS) {
                    c.pendingFired = true;
                    fire(t, SCREEN_MISMATCH, k + " has shown an unsent edit " + c.shown
                        + " (" + num1(c.value) + ") for " + secs(t - c.pendingSince), out);
                }
                c.badSince = -1;
                continue;
            }
            int wi = wireIndex(k);
            // A live adjustment sends the hold/drop time LEFT in the step (the countdown is
            // untouched), while the cell keeps the step's own length - by design. Times are
            // compared only while nothing is adjusted.
            boolean timeField = wi == 2 || wi == 4;
            if (!comparable || c.unconfirmed || Double.isNaN(c.value)
                    || (timeField && !"run".equals(runState))) {
                c.badSince = -1;
                continue;
            }
            double wire = cur[wi];
            double tol = wi == 1 || wi == 3 ? TOL_KPA : wi == 0 ? TOL_PCT : TOL_S;
            if (Math.abs(c.value - wire) <= tol) { c.badSince = -1; c.badFired = false; continue; }
            if (c.badSince < 0) c.badSince = t;
            if (!c.badFired && t - c.badSince > MISMATCH_MS) {
                c.badFired = true;
                fire(t, SCREEN_MISMATCH, k + " shows " + c.shown + " (" + num1(c.value)
                    + ") but the pump was sent " + (int) wire, out);
            }
        }
    }

    private void onChart(long t, List<String> tk, List<String> out) {
        if (tk.contains("off")) { chartOn = false; return; }
        long n = num(tk, "n"), of = num(tk, "of");
        if (n < 0 || of < 0) return;
        if (!chartOn) {
            chartOn = true;
            chartN = n; chartOf = of; nChangeAt = t; ofChangeAt = t;
            ofAtNChange = of; samplesSinceOf = 0; chartFired = false;
            return;
        }
        if (n != chartN) { chartN = n; nChangeAt = t; ofAtNChange = of; chartFired = false; }
        if (of != chartOf) { chartOf = of; ofChangeAt = t; samplesSinceOf = 0; }
        if (!chartFired && of - ofAtNChange >= 3 && t - nChangeAt > CHART_STALL_MS) {
            chartFired = true;
            fire(t, CHART_FROZEN, "the chart has not redrawn for " + secs(t - nChangeAt) + " while "
                + (of - ofAtNChange) + " new readings arrived", out);
        } else if (!chartFired && samplesSinceOf >= 4 && t - ofChangeAt > CHART_STALL_MS) {
            chartFired = true;
            fire(t, CHART_FROZEN, "no reading has reached the chart for " + secs(t - ofChangeAt)
                + " while the pump sent " + samplesSinceOf, out);
        }
        // The chart's target line and its big readout, against the wire and the pump.
        String cmd = kv(tk, "cmd");
        if (cmd != null) {
            Cell c = target;
            double v = dbl(cmd);
            boolean comparable = "run".equals(screen) && running && cur != null && v > 0
                && ("run".equals(runState) || "adjusted".equals(runState));
            if (!comparable || Math.abs(v - cur[1]) <= TOL_KPA) { c.badSince = -1; c.badFired = false; }
            else {
                if (c.badSince < 0) c.badSince = t;
                if (!c.badFired && t - c.badSince > MISMATCH_MS) {
                    c.badFired = true;
                    fire(t, SCREEN_MISMATCH, "the chart's target line is at " + cmd
                        + " kPa but the pump was sent " + cur[1], out);
                }
            }
        }
        String big = kv(tk, "big");
        if (big != null) {
            Cell c = readout;
            double v = "-".equals(big) ? Double.NaN : dbl(big);
            boolean steady = pmpAt >= 0 && t - pmpMoveAt >= 1500 && t - pmpAt < 3000;
            boolean bad = steady && (Double.isNaN(v) != Double.isNaN(kpa)
                || (!Double.isNaN(v) && Math.abs(v - kpa) > TOL_KPA));
            if (!bad) { c.badSince = -1; c.badFired = false; }
            else {
                if (c.badSince < 0) c.badSince = t;
                if (!c.badFired && t - c.badSince > MISMATCH_MS) {
                    c.badFired = true;
                    fire(t, SCREEN_MISMATCH, "the big readout shows " + big
                        + " kPa but the pump reports " + (Double.isNaN(kpa) ? "no reading" : kpa1(kpa)), out);
                }
            }
        }
    }

    /* ======================================================================= helpers */

    private void owe(long t, int op, String what) {
        owed.add(new long[]{ op, t, 0 });
        owedWhat.add(what);
    }

    private void fire(long t, String rule, String what, List<String> out) {
        int[] c = counts.get(rule);
        Long last = lastFired.get(rule);
        long cool = TAP_NO_EFFECT.equals(rule) ? 0 : COOLDOWN_MS;
        if (last != null && t - last.longValue() < cool) { c[1]++; return; }
        c[0]++;
        lastFired.put(rule, Long.valueOf(t));
        out.add("!! " + rule + " " + what);
    }

    static int opCode(String name) {
        if ("add".equals(name)) return Proto.OP_ADD;
        if ("start".equals(name)) return Proto.OP_START;
        if ("stop".equals(name)) return Proto.OP_STOP;
        if ("delete".equals(name)) return Proto.OP_DELETE;
        if ("list".equals(name)) return Proto.OP_LIST;
        if ("time".equals(name)) return Proto.OP_TIME;
        if (name != null && name.startsWith("op")) {
            try { return Integer.parseInt(name.substring(name.indexOf('=') + 1), 16); }
            catch (RuntimeException e) { return -1; }
        }
        return -1;
    }

    /** Space-separated tokens; a double-quoted run is one token, quotes kept. */
    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && s.charAt(i) == ' ') i++;
            if (i >= n) break;
            int start = i;
            if (s.charAt(i) == '"') {
                int end = s.indexOf('"', i + 1);
                i = end < 0 ? n : end + 1;
            } else {
                while (i < n && s.charAt(i) != ' ') i++;
            }
            out.add(s.substring(start, i));
        }
        return out;
    }

    /** The value of a key=value token, or null. */
    public static String kv(List<String> tk, String key) {
        String p = key + "=";
        for (int i = 0; i < tk.size(); i++) if (tk.get(i).startsWith(p)) return tk.get(i).substring(p.length());
        return null;
    }

    private static int num(List<String> tk, String key) {
        String v = kv(tk, key);
        if (v == null) return -1;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return -1; }
    }

    private static double dbl(String s) {
        if (s == null) return Double.NaN;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static String last(List<String> tk) { return tk.isEmpty() ? "" : tk.get(tk.size() - 1); }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
            return s.substring(1, s.length() - 1);
        return "";
    }

    static String secs(long ms) {
        long ds = (ms + 50) / 100;
        return (ds / 10) + "." + (ds % 10) + " s";
    }

    static String kpa1(double k) {
        long d = Math.round(k * 10);
        return (d / 10) + "." + Math.abs(d % 10);
    }

    private static String num1(double v) { return Double.isNaN(v) ? "-" : kpa1(v); }
}
