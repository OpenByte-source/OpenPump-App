package org.openpump;

import java.util.List;
import java.util.Locale;

/**
 * A REJOINED RUN IS ONE SESSION (the owner's pick on the leftovers, option A; device check
 * EMU9c, seen-not-counted 1).
 *
 * A run the app lost mid-way - the process killed, the screen destroyed - can be rejoined
 * ("Rejoin the run"), and a run stopped by STOP can be resumed. Each time, the part after
 * started a session of its own: the summary read "Duration 0:44 of 40:55 planned", Today's
 * "Last session" 0:44, and the five minutes played before were nowhere - nor was their time
 * at pressure, which the plan reads, so the session looked under-delivered.
 *
 * WHAT IS CARRIED, the earlier parts' figures added to the part that files: the time played,
 * the time at pressure (net and gross), the dose, the peak (the higher) and the cycles; the
 * session starts when the first part did. And how many times it was rejoined or resumed, for
 * the summary's one line.
 *
 * WHERE IT COMES FROM. A process that died filed nothing, so the run snapshot keeps the
 * figures so far ({@link #sofar}, written at each step and on the tick's slow beat). A part
 * that WAS filed - STOP files it, and so does a screen destroyed mid-run - is read from its
 * record ({@link #ofRecord}) and that record is REPLACED by the one session
 * ({@link #dropEarlier}), so nothing is counted twice.
 *
 * Bookkeeping only: nothing here reaches the pump, and the plan reads the record as it reads
 * every other.
 */
public final class RunParts {
    /** When the first part started (wall clock, ms); 0 unknown. */
    public long startTs;
    /** The time played in the earlier parts, ms. */
    public long playedMs;
    /** Their time at pressure, s; NaN when nothing scored them. */
    public double netSec = Double.NaN, grossSec = Double.NaN;
    /** Their dose, kPa·s. */
    public double doseKpaS;
    /** Their highest observed reading, kPa; NaN when none. */
    public double peakKpa = Double.NaN;
    /** Their delivered cycles; -1 when not recorded. */
    public int cycles = -1;
    /** Times the run was rejoined after the app closed, and resumed after STOP. */
    public int rejoins, resumes;
    /** The earlier parts' record, already filed, that the one session replaces; "" none. */
    public String sessId = "";

    private static final String V = "p1";

    public RunParts copy() {
        RunParts p = new RunParts();
        p.startTs = startTs; p.playedMs = playedMs;
        p.netSec = netSec; p.grossSec = grossSec;
        p.doseKpaS = doseKpaS; p.peakKpa = peakKpa; p.cycles = cycles;
        p.rejoins = rejoins; p.resumes = resumes; p.sessId = sessId;
        return p;
    }

    /** The earlier parts plus the part running now: what the snapshot keeps. */
    public static RunParts sofar(RunParts prior, long partStartTs, long partMs, Double net,
                                 Double gross, double dose, Double peak, int cycles) {
        RunParts p = prior == null ? new RunParts() : prior.copy();
        if (p.startTs <= 0) p.startTs = Math.max(0L, partStartTs);
        p.playedMs += Math.max(0L, partMs);
        if (net != null) p.netSec = (Double.isNaN(p.netSec) ? 0.0 : p.netSec) + net.doubleValue();
        if (gross != null)
            p.grossSec = (Double.isNaN(p.grossSec) ? 0.0 : p.grossSec) + gross.doubleValue();
        p.doseKpaS += Math.max(0.0, dose);
        if (peak != null && (Double.isNaN(p.peakKpa) || peak.doubleValue() > p.peakKpa))
            p.peakKpa = peak.doubleValue();
        if (cycles >= 0) p.cycles = (p.cycles < 0 ? 0 : p.cycles) + cycles;
        return p;
    }

    /** A part already filed, as the earlier parts of the session it will become. */
    public static RunParts ofRecord(Model.Sess s) {
        RunParts p = new RunParts();
        if (s == null) return p;
        p.startTs = s.ts;
        p.playedMs = Math.max(0L, s.durSec) * 1000L;
        p.netSec = s.netTupSec == null ? Double.NaN : s.netTupSec.doubleValue();
        p.grossSec = s.grossTupSec == null ? Double.NaN : s.grossTupSec.doubleValue();
        p.doseKpaS = s.doseKpaS;
        p.peakKpa = s.peakKpa == null ? Double.NaN : s.peakKpa.doubleValue();
        p.cycles = s.cyclesDone;
        p.rejoins = s.rejoins;
        p.resumes = s.resumes;
        p.sessId = s.id == null ? "" : s.id;
        return p;
    }

    /** The session filing now starts when the first part did and lasts them all. Called
     *  where the record's start and duration are stamped, before anything keys on its ts. */
    public static void foldTime(RunParts prior, Model.Sess rec) {
        if (prior == null || rec == null) return;
        rec.durSec += prior.playedMs / 1000L;
        rec.ts = prior.startTs > 0 ? prior.startTs : rec.ts - prior.playedMs;
        rec.rejoins = prior.rejoins;
        rec.resumes = prior.resumes;
    }

    /** ...and carries the earlier parts' figures, once its own are on it. */
    public static void foldFigures(RunParts prior, Model.Sess rec) {
        if (prior == null || rec == null) return;
        if (!Double.isNaN(prior.netSec))
            rec.netTupSec = Double.valueOf(prior.netSec
                + (rec.netTupSec == null ? 0.0 : rec.netTupSec.doubleValue()));
        if (!Double.isNaN(prior.grossSec))
            rec.grossTupSec = Double.valueOf(prior.grossSec
                + (rec.grossTupSec == null ? 0.0 : rec.grossTupSec.doubleValue()));
        rec.doseKpaS += prior.doseKpaS;
        if (!Double.isNaN(prior.peakKpa)
                && (rec.peakKpa == null || prior.peakKpa > rec.peakKpa.doubleValue()))
            rec.peakKpa = Double.valueOf(prior.peakKpa);
        if (prior.cycles >= 0) rec.cyclesDone = prior.cycles + Math.max(0, rec.cyclesDone);
    }

    /** Takes the earlier parts' filed record out of the log, so the one session replaces it
     *  rather than counting beside it; returns it, or null when there is none. */
    public static Model.Sess dropEarlier(List<Model.Sess> log, RunParts prior) {
        if (log == null || prior == null || prior.sessId == null || prior.sessId.length() == 0)
            return null;
        for (int i = 0; i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s != null && prior.sessId.equals(s.id)) return log.remove(i);
        }
        return null;
    }

    /** The before-test, when only the earlier part ran it (it runs before the first set). */
    public static void carryBefore(Model.Sess earlier, Model.Sess rec) {
        if (earlier == null || rec == null) return;
        if (rec.tauBeforeSec != null || (rec.tauBeforeWhy != null && rec.tauBeforeWhy.length() > 0))
            return;
        rec.tauBeforeSec = earlier.tauBeforeSec;
        rec.tauBeforeWhy = earlier.tauBeforeWhy;
        rec.tauBeforeP0Kpa = earlier.tauBeforeP0Kpa;
        rec.tauBeforePeakKpa = earlier.tauBeforePeakKpa;
    }

    /** The summary's one line, or null for a run that played in one part. */
    public static String line(int rejoins, int resumes) {
        String a = rejoins > 0 ? "Rejoined " + times(rejoins) + " after the app closed" : null;
        String b = resumes > 0 ? "Resumed " + times(resumes) + " after STOP" : null;
        if (a == null) return b;
        return b == null ? a : a + " · " + b;
    }

    private static String times(int n) { return n == 1 ? "once" : n + " times"; }

    /** One line for the snapshot's preferences. */
    public String code() {
        return V + ";" + startTs + ";" + playedMs + ";" + num(netSec) + ";" + num(grossSec)
            + ";" + num(doseKpaS) + ";" + num(peakKpa) + ";" + cycles + ";" + rejoins + ";"
            + resumes + ";" + (sessId == null ? "" : sessId.replace(";", ""));
    }

    /** Read back; null for a snapshot that holds none (written before it) or an odd one. */
    public static RunParts fromCode(String c) {
        if (c == null) return null;
        String[] f = c.split(";", -1);
        if (f.length != 11 || !V.equals(f[0])) return null;
        try {
            RunParts p = new RunParts();
            p.startTs = Math.max(0L, Long.parseLong(f[1]));
            p.playedMs = Math.max(0L, Long.parseLong(f[2]));
            p.netSec = dbl(f[3]);
            p.grossSec = dbl(f[4]);
            double d = dbl(f[5]);
            p.doseKpaS = Double.isNaN(d) ? 0.0 : Math.max(0.0, d);
            p.peakKpa = dbl(f[6]);
            p.cycles = Math.max(-1, Integer.parseInt(f[7]));
            p.rejoins = Math.max(0, Integer.parseInt(f[8]));
            p.resumes = Math.max(0, Integer.parseInt(f[9]));
            p.sessId = f[10];
            return p;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, "%.3f", v);
    }

    private static double dbl(String s) {
        if (s == null || s.length() == 0) return Double.NaN;
        double v = Double.parseDouble(s);
        return Double.isInfinite(v) ? Double.NaN : v;
    }
}
