package org.openpump;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * M4 - THE TISSUE RESPONSE TEST IN WORDS PEOPLE CAN READ. Pure, so every sentence below is
 * pinned by TauSayTest rather than eyeballed on a phone.
 *
 * WHY A CLASS OF ITS OWN. The test's arithmetic is Tau's and stays there. What a person
 * reads was spread over four screens and written for whoever built the maths: a card
 * headed "Δτ +4%", a caption of thirty words of qualification, a History row "τ", refusal
 * reasons about "a 0.0 reading is not a pressure of zero". The owner named the test (the
 * "Tissue response test") and asked for its result in plain words: the two fill times, the
 * change, and what a change of that size means. Every screen that states the result asks
 * here, so the summary, History, the Sessions list and Progress can never word the same
 * session two ways.
 *
 * WHAT IT NEVER SAYS: which way is better. The fill time is a relative index of how fast
 * the cylinder fills under the test's own settings (pump speed, pressure and seal all move
 * it), and this app does not know whether a longer or a shorter one is good. Every
 * sentence here states a size, never a direction.
 */
public final class TauSay {

    private TauSay() { }

    /** The test's name everywhere in the app - the owner's choice. */
    public static final String NAME = "Tissue response test";

    /** What τ is called on a screen: the time each pull takes to fill. */
    public static final String VALUE = "Fill time";

    /* ------------------------------------------------------------------ the bands */

    /** No change was worked out. */
    public static final int BAND_NONE = 0;
    /** Under {@link Tau#NOISE_PCT}: within what setup alone can produce. */
    public static final int BAND_WITHIN = 1;
    /** At the size of normal variation - up to half as much again. Worth repeating. */
    public static final int BAND_EDGE = 2;
    /** More than normal variation. */
    public static final int BAND_BEYOND = 3;

    /**
     * Where a change sits against normal variation - decided on the WHOLE PERCENT the screen
     * prints, so a change shown as "+4 %" is never also called "under 4 %". Half as much
     * again as the noise (6 %) is where "about the size of it" stops being true.
     */
    public static int band(double pct) {
        long r = Math.abs(Math.round(pct));
        if (r < Tau.NOISE_PCT) return BAND_WITHIN;
        if (r < Tau.NOISE_PCT + Tau.NOISE_PCT / 2) return BAND_EDGE;
        return BAND_BEYOND;
    }

    /** Whether a change is drawn as more than setup explains - Progress's "4 % or more". */
    public static boolean atOrPastNoise(double pct) {
        return Math.abs(Math.round(pct)) >= Tau.NOISE_PCT;
    }

    /** A signed whole percent with the space the screens use: "+4 %", "−8 %", "0 %". */
    public static String pct(double pct) {
        long r = Math.round(pct);
        if (r == 0) return "0 %";
        return (r > 0 ? "+" : "−") + Math.abs(r) + " %";
    }

    /** "normal variation" stated once, with its size, for the lines that name it. */
    public static String noiseLabel() {
        return Tau.NOISE_PCT + " %";
    }

    /* ------------------------------------------------------------ one session */

    private static boolean has(String why) { return why != null && why.length() > 0; }

    /** Whether the test ran at all for this session: a number or a reason at either end. */
    public static boolean ran(Model.Sess s) {
        return s != null && (s.tauBeforeSec != null || s.tauAfterSec != null
            || has(s.tauBeforeWhy) || has(s.tauAfterWhy));
    }

    /** Every end that ran was a skip, and none refused. A test left out because it was the
     *  day's second session (S05) is a skip too - the app's, not the person's. */
    public static boolean onlySkipped(Model.Sess s) {
        if (s == null) return false;
        boolean b = has(s.tauBeforeWhy), a = has(s.tauAfterWhy);
        if (!b && !a) return false;
        if (s.tauBeforeSec != null || s.tauAfterSec != null) return false;
        return (!b || skip(s.tauBeforeWhy)) && (!a || skip(s.tauAfterWhy));
    }

    private static boolean skip(String why) {
        return Tau.WHY_SKIPPED.equals(why) || Tau.WHY_SECOND_SESSION.equals(why);
    }

    /** 0.10 (S05) - the test was left out only because this was the day's second session. */
    public static boolean secondSessionOnly(Model.Sess s) {
        if (s == null || s.tauBeforeSec != null || s.tauAfterSec != null) return false;
        boolean b = has(s.tauBeforeWhy), a = has(s.tauAfterWhy);
        if (!b && !a) return false;
        return (!b || Tau.WHY_SECOND_SESSION.equals(s.tauBeforeWhy))
            && (!a || Tau.WHY_SECOND_SESSION.equals(s.tauAfterWhy));
    }

    /** The words History and the summary put on a session whose test was left out as the
     *  day's second. */
    public static final String NOT_RUN_SECOND = "not run: second session today";

    /**
     * The result's headline: "Fill time 6.7 s → 7.0 s". One end only says which end it
     * was; no number at all says what happened. Empty when the test never ran.
     */
    public static String title(Model.Sess s) {
        if (!ran(s)) return "";
        if (s.tauBeforeSec != null && s.tauAfterSec != null)
            return VALUE + " " + Tau.fmtTau(s.tauBeforeSec) + " → " + Tau.fmtTau(s.tauAfterSec);
        if (s.tauAfterSec != null)
            return VALUE + " " + Tau.fmtTau(s.tauAfterSec) + " after the routine";
        if (s.tauBeforeSec != null)
            return VALUE + " " + Tau.fmtTau(s.tauBeforeSec) + " before the routine";
        if (secondSessionOnly(s)) return "Not run: second session today";
        return onlySkipped(s) ? "Skipped this session" : "No fill time this session";
    }

    /**
     * The line under the headline: the change and what a change that size means - or, when
     * there is no change to state, why not. The pressures behind a pair that did not match
     * are kept (they are the only way to tell whether the vent or the seal moved).
     */
    public static String verdict(Model.Sess s) {
        if (!ran(s)) return "";
        Double d = Tau.sessionDeltaPct(s);
        if (d != null) return changeSentence(d.doubleValue());
        if (s.tauBeforeSec != null && s.tauAfterSec != null)
            return "No change is worked out: the two pulls didn't start from, or reach, the "
                 + "same pressure (started " + kpa(s.tauBeforeP0Kpa) + " → "
                 + kpa(s.tauAfterP0Kpa) + ", reached " + kpa(s.tauBeforePeakKpa) + " → "
                 + kpa(s.tauAfterPeakKpa) + ").";
        if (s.tauAfterSec != null) return "Nothing to compare it with: " + missing(false, s.tauBeforeWhy);
        if (s.tauBeforeSec != null) return "Nothing to compare it with: " + missing(true, s.tauAfterWhy);
        StringBuilder sb = new StringBuilder();
        if (has(s.tauBeforeWhy)) sb.append(endSentence(false, s.tauBeforeWhy));
        if (has(s.tauAfterWhy)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(endSentence(true, s.tauAfterWhy));
        }
        if (secondSessionOnly(s))
            return "The other track ran earlier today, and the test runs in the day's first "
                 + "session only: a second pull would time tissue the first session had "
                 + "already worked, and could not be compared with any other.";
        if (onlySkipped(s)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("The session is saved with no fill time, never a made-up one.");
        }
        return sb.toString();
    }

    /** The change and what it means, for a change that WAS worked out. */
    public static String changeSentence(double pct) {
        String p = pct(pct);
        switch (band(pct)) {
            case BAND_WITHIN:
                return p + ", within normal variation. Changes under " + noiseLabel()
                     + " happen between any two pulls, so this one doesn't show anything on "
                     + "its own.";
            case BAND_EDGE:
                return p + ", about the size of normal variation. Repeat it before reading "
                     + "anything into it.";
            default:
                return p + ", more than the normal variation between two pulls (under "
                     + noiseLabel() + ").";
        }
    }

    /** The few words History's row and the Sessions list put after a change. */
    public static String changeWords(double pct) {
        switch (band(pct)) {
            case BAND_WITHIN: return "within normal variation";
            case BAND_EDGE:   return "about normal variation";
            default:          return "more than normal variation";
        }
    }

    /** Why the other end has no number - "the before-test was skipped." and the like. */
    private static String missing(boolean after, String why) {
        String end = after ? "the after-test" : "the before-test";
        if (!has(why)) return "there's no " + (after ? "after-test" : "before-test")
                            + " this session.";
        if (Tau.WHY_SKIPPED.equals(why)) return end + " was skipped.";
        if (Tau.WHY_SECOND_SESSION.equals(why)) return end + " was not run (second session today).";
        if (Tau.WHY_NO_START.equals(why)) return end + " couldn't start, because the pump "
                                               + "didn't report a pressure to measure from.";
        return end + " gave no fill time: " + reason(why) + ".";
    }

    /** One end with no number, as a sentence of its own. */
    private static String endSentence(boolean after, String why) {
        if (Tau.WHY_NO_START.equals(why)) return Tau.noStartSentence(after);
        String end = after ? "The after-test" : "The before-test";
        if (Tau.WHY_SKIPPED.equals(why)) return end + " was skipped.";
        if (Tau.WHY_SECOND_SESSION.equals(why)) return end + " was " + NOT_RUN_SECOND + ".";
        return end + " gave no fill time: " + reason(why) + ".";
    }

    private static String kpa(Double v) {
        return Model.Fmt.p(v == null ? 0 : v.doubleValue());
    }

    /**
     * A refusal in plain words, for a person reading their result. Tau#whyText keeps the
     * engineer's wording for the session log; this says what happened and, where there is
     * one, what to do about it.
     */
    public static String reason(String why) {
        if (Tau.WHY_NO_READING.equals(why))
            return "the pump sent no reading at a moment the test needed one";
        if (Tau.WHY_GAP.equals(why))
            return "part of the pull arrived with no readings, so it couldn't be timed. Check "
                 + "the connection before the next run";
        if (Tau.WHY_STILL_CLIMBING.equals(why))
            return "the pull was still rising when the test ended, so there was no top to time "
                 + "it against. A longer test duration would help";
        if (Tau.WHY_TOO_FEW.equals(why))
            return "too few readings arrived to time the pull";
        if (Tau.WHY_LOW_PLATEAU.equals(why))
            return "the pull never got past " + Model.Fmt.p(Session.DOSE_FLOOR_KPA)
                 + ". Check the seal";
        if (Tau.WHY_HOT_START.equals(why))
            return "the pull didn't start from an open cuff, so there was nothing to time it "
                 + "from";
        if (Tau.WHY_NO_START.equals(why))
            return "the pump didn't report a pressure to measure from, so the test couldn't "
                 + "start";
        if (Tau.WHY_SHORT_PULL.equals(why))
            return "the pull never reached the test's pressure. Check the seal";
        if (Tau.WHY_OVERSHOT.equals(why))
            return "readings came back well past the test's pressure, so this pull isn't "
                 + "used";
        if (Tau.WHY_LINK_LOST.equals(why))
            return "the pump stopped reporting during the pull, so it was stopped. If pressure "
                 + "doesn't clear, disconnect the tubing at the cuff";
        if (Tau.WHY_SKIPPED.equals(why))
            return "skipped";
        if (Tau.WHY_SECOND_SESSION.equals(why))
            return NOT_RUN_SECOND;
        if (Tau.WHY_NO_RISE.equals(why))
            return "the start of the pull wasn't seen";
        return "not measured";
    }

    /**
     * The Sessions list's one line for this test, or "" when it never ran.
     * "Fill time 6.6 s → 6.7 s · +1 %".
     */
    public static String tag(Model.Sess s) {
        if (!ran(s)) return "";
        Double d = Tau.sessionDeltaPct(s);
        if (s.tauBeforeSec != null && s.tauAfterSec != null)
            return title(s) + "  ·  " + (d != null ? pct(d.doubleValue()) : "not compared");
        if (s.tauAfterSec != null) return VALUE + " " + Tau.fmtTau(s.tauAfterSec) + ", after only";
        if (s.tauBeforeSec != null) return VALUE + " " + Tau.fmtTau(s.tauBeforeSec) + ", before only";
        if (secondSessionOnly(s)) return NAME + " " + NOT_RUN_SECOND;
        return onlySkipped(s) ? NAME + " skipped" : NAME + ": no fill time";
    }

    /**
     * History's detail row, under the label {@link #NAME}, or null when the test never ran.
     * "6.6 s → 6.7 s · +1 %, within normal variation".
     */
    public static String detail(Model.Sess s) {
        if (!ran(s)) return null;
        Double d = Tau.sessionDeltaPct(s);
        if (s.tauBeforeSec != null && s.tauAfterSec != null)
            return Tau.fmtTau(s.tauBeforeSec) + " → " + Tau.fmtTau(s.tauAfterSec) + "  ·  "
                 + (d != null ? pct(d.doubleValue()) + ", " + changeWords(d.doubleValue())
                              : "not compared");
        if (s.tauAfterSec != null)
            return "after " + Tau.fmtTau(s.tauAfterSec) + "  ·  nothing to compare with";
        if (s.tauBeforeSec != null)
            return "before " + Tau.fmtTau(s.tauBeforeSec) + "  ·  nothing to compare with";
        if (secondSessionOnly(s)) return NOT_RUN_SECOND;
        return onlySkipped(s) ? "skipped" : "no fill time";
    }

    /**
     * What the test is and what its number means - behind the result's "What this
     * measures" ⓘ. The settings the session ran under are named when the record has them;
     * this is where the old caption's "a relative index of fill rate under this stimulus,
     * not a tissue measurement" now lives.
     */
    public static String about(int kpa, int sp, int durSec) {
        String how = durSec > 0 ? " (" + Tau.stimulus(kpa, sp, durSec) + ")" : "";
        return "The " + NAME + " runs the same short pull before and after the routine" + how
             + " and times how fast the cylinder fills. The fill time is how long a pull takes "
             + "to cover about two thirds of the way from where it started to the top it "
             + "reached.\n\n"
             + "It is a relative index of how fast the cylinder fills under these settings, not "
             + "a direct measure of tissue: pump speed, pressure and the seal all move it, so "
             + "sessions are only compared with others run at the same test settings. The app "
             + "doesn't say whether a longer or shorter fill time is better.\n\n"
             + "Two pulls rarely time exactly alike. Changes under " + noiseLabel()
             + " are normal variation, and a change needs repeating before it means much.";
    }

    /* ---------------------------------------------------------- over time */

    /** One session's change, as Progress draws it. */
    public static final class Point {
        public final long ts;
        public final double pct;
        /** The test settings differ from the point before: the trend breaks here. */
        public final boolean breakBefore;
        Point(long ts, double pct, boolean breakBefore) {
            this.ts = ts; this.pct = pct; this.breakBefore = breakBefore;
        }
    }

    /** At most this many sessions are drawn - the newest ones. */
    public static final int TREND_MAX = 20;

    /**
     * THE TEST OVER TIME: one point per session that produced a change, OLDEST FIRST, the
     * newest {@link #TREND_MAX} of them. Input is the stored log, newest first.
     *
     * A session whose test was off, skipped or refused is LEFT OUT, never drawn as 0 %: a
     * missing result is not a measured "no change" (Insight#expansionSeries's rule). Points
     * are joined only while the test settings stay the same; where they changed, the point
     * carries breakBefore, and a reader is told the two sides are not compared.
     */
    public static List<Point> trend(List<Model.Sess> newestFirst) {
        List<Model.Sess> kept = new ArrayList<Model.Sess>();
        if (newestFirst != null)
            for (int i = 0; i < newestFirst.size() && kept.size() < TREND_MAX; i++) {
                Model.Sess s = newestFirst.get(i);
                if (s == null || s.assessDurSec <= 0) continue;
                if (Tau.sessionDeltaPct(s) == null) continue;
                kept.add(s);
            }
        List<Point> out = new ArrayList<Point>();
        Model.Sess prev = null;
        for (int i = kept.size() - 1; i >= 0; i--) {
            Model.Sess s = kept.get(i);
            boolean brk = prev != null && !Tau.sameStimulus(prev.assessKpa, prev.assessSp,
                prev.assessDurSec, s.assessKpa, s.assessSp, s.assessDurSec);
            out.add(new Point(s.ts, Tau.sessionDeltaPct(s).doubleValue(), brk));
            prev = s;
        }
        return out;
    }

    /** The trend's spoken summary: how many sessions, how many past normal variation, and
     *  where the settings changed. */
    public static String trendSaid(List<Point> pts) {
        int n = pts == null ? 0 : pts.size(), past = 0, breaks = 0;
        for (int i = 0; i < n; i++) {
            if (atOrPastNoise(pts.get(i).pct)) past++;
            if (pts.get(i).breakBefore) breaks++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Change in fill time, ").append(n).append(n == 1 ? " session" : " sessions")
          .append(", oldest first. ");
        if (n > 0) {
            sb.append("Latest ").append(pct(pts.get(n - 1).pct)).append(". ");
            sb.append(past).append(past == 1 ? " was " : " were ").append(noiseLabel())
              .append(" or more.");
        }
        if (breaks > 0)
            sb.append(" The test settings changed ").append(breaks == 1 ? "once" : breaks + " times")
              .append("; sessions either side of a change are not compared.");
        return sb.toString();
    }

    /** The one-line description of the pull chart, with each fill time. */
    public static String chartSaid(Model.Sess s) {
        if (s == null) return VALUE;
        StringBuilder sb = new StringBuilder(VALUE);
        if (s.tauBeforeSec != null)
            sb.append(": before ").append(String.format(Locale.US, "%.1f", s.tauBeforeSec))
              .append(" seconds");
        if (s.tauAfterSec != null)
            sb.append(s.tauBeforeSec != null ? ", after " : ": after ")
              .append(String.format(Locale.US, "%.1f", s.tauAfterSec)).append(" seconds");
        return sb.append('.').toString();
    }
}
