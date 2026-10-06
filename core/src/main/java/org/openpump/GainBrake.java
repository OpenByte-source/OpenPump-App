package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * R11-4 - THE BRAKE WHILE IT IS WORKING (the owner's pick, option A, with his note: "let the
 * user say if he wants to progress when it's time").
 *
 * While a track's readings are on target (girth's yield inside its level's window, length's
 * strain inside 2-6 %) AND its at-rest measurement is rising - the average of the last 4
 * counted training weeks above the 4 before - the calendar steps come half as often:
 *
 *  - girth pressure every 6 training weeks at a pressure instead of 3 (Plan, the +1 hg step);
 *  - the length climb (its pressure, and the pull that follows it) every 2 months instead of 1;
 *  - the slow load step every 4 length training weeks instead of 2.
 *
 * WHEN THE NORMAL STEP WOULD HAVE BEEN DUE, the plan says so instead of the step (a hold of its
 * own, #kindOf) and the Trainer card offers it: "You're gaining, so the next step waits until
 * <date>. Step up now?" - Step up now gives the normal step, proposed and confirmed as every
 * step is; Wait keeps the slower pace. Asked once for each step (the step's own clock,
 * #clockKey): applying the step starts a new clock, and the next one is asked afresh.
 *
 * NEVER ADDS ANYTHING BECAUSE OF GAINS - it only waits. Gains that stall (the last 4 weeks no
 * higher than the 4 before) bring the normal pace back on their own. Not on the return days
 * after a week off, nor the gentle week after the month-12 break (#active).
 *
 * THE AT-REST SERIES (#atRestSeries): one value per counted training week of the track (the
 * week rule, TrainingWeek) - the average of that week's at-rest readings in the app's
 * standardised method, girth for girth and length for length, a post-session reading never
 * among them (it is swollen). A week with no such reading has no value. Where a track has no
 * standardised readings at all, its own goal method (the Progress chart's other default line:
 * MSEG for girth, BPSSL for length) is read instead - one method, never a mix.
 */
public final class GainBrake {
    private GainBrake() { }

    /** The braked paces: twice the normal ones. */
    public static final int GIRTH_WEEKS = 2 * Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
    public static final int CLIMB_MONTHS = 2;
    public static final int SLOW_WEEKS = 2 * Plan.LENGTH_LOAD_STEP_WEEKS;
    /** How many weekly values are compared: the last 4 against the 4 before. */
    public static final int WINDOW_WEEKS = 4;

    /** Which step waits: girth's pressure, length's monthly climb, the slow load step. */
    public static final int KIND_PRESSURE = 0, KIND_CLIMB = 1, KIND_SLOW = 2;

    /** The person's answer to the offer, kept with the step it answered (TrainerTrackState). */
    public static final int ANSWER_NONE = 0, ANSWER_WAIT = 1, ANSWER_STEP_UP = 2;

    /** The plan's rule for a step that waits - one per kind, so the card knows which. */
    static final String RULE = "gaining while on target -> the calendar step waits (R11-4): ";
    static final String[] RULE_KIND = {
        "pressure every " + GIRTH_WEEKS + " training weeks",
        "length climb every " + CLIMB_MONTHS + " months",
        "slow load step every " + SLOW_WEEKS + " training weeks" };
    static final String REASON =
        "your readings are on target and your at-rest measurements are rising, so the next "
        + "step waits";

    public static final String STEP_UP = "Step up now";
    public static final String WAIT = "Wait";

    /** "You're gaining, so the next step waits until Mon 26 Oct. Step up now?" */
    public static String offerWords(String until) {
        return waitWords(until) + " Step up now?";
    }

    /** "You're gaining, so the next step waits until Mon 26 Oct." */
    public static String waitWords(String until) {
        return "You're gaining, so the next step waits until " + until + ".";
    }

    /** The hold the plan returns when the normal step would have been due. */
    static Plan.Decision hold(int kind) {
        return new Plan.Decision(Plan.ACTION_HOLD, Plan.TAG_INFERRED, RULE + RULE_KIND[kind],
            REASON, Double.NaN, 0);
    }

    /** Which step a decision says waits, or -1 for every other decision. */
    public static int kindOf(Plan.Decision d) {
        if (d == null || d.rule == null || !d.rule.startsWith(RULE)) return -1;
        String k = d.rule.substring(RULE.length());
        for (int i = 0; i < RULE_KIND.length; i++) if (RULE_KIND[i].equals(k)) return i;
        return -1;
    }

    /* ----------------------------------------------------------------- when it brakes */

    /** The last {@link #WINDOW_WEEKS} weekly values' average above the ones before - with at
     *  least twice the window to compare; fewer is not yet known, and is not gaining. */
    public static boolean gaining(List<Double> series) {
        if (series == null || series.size() < 2 * WINDOW_WEEKS) return false;
        int n = series.size();
        double last = 0, before = 0;
        for (int i = 0; i < WINDOW_WEEKS; i++) {
            last += series.get(n - 1 - i).doubleValue();
            before += series.get(n - 1 - WINDOW_WEEKS - i).doubleValue();
        }
        return last > before + 1e-9;
    }

    /** On target and rising, and not a return day or the gentle week. */
    public static boolean active(boolean gaining, boolean onTarget, boolean returnOrGentle) {
        return gaining && onTarget && !returnOrGentle;
    }

    /** Whether `track` brakes at `nowMs` - `onTarget` being the track's readings' answer
     *  (TrainerTab#latestYieldOnTarget for girth, #lengthOnTarget for length). */
    public static boolean on(Model m, int track, boolean onTarget, long nowMs) {
        if (m == null || !onTarget) return false;
        boolean back = Deload.armed(m) || TrainerTab.returnRunsUnder(m, nowMs)
            || MonthBreak.gentleOn(m, nowMs);
        return active(gaining(atRestSeries(m, track, nowMs)), true, back);
    }

    /** Length's readings on target: the strain the ladder acts on inside 2-6 %. */
    public static boolean lengthOnTarget(double strainPct) {
        return !Double.isNaN(strainPct) && strainPct >= Plan.LENGTH_STRAIN_LO
            && strainPct <= Plan.LENGTH_STRAIN_HI;
    }

    /** The slow load step's normal two weeks are up (Inputs#slowLoadDue) and its braked four
     *  are not: `weeksSince` length training weeks since its last step. */
    public static boolean slowBraked(boolean gainBrake, int weeksSince) {
        return gainBrake && weeksSince < SLOW_WEEKS;
    }

    /* ------------------------------------------------------------- the at-rest series */

    /** One at-rest value per counted training week of `track`, oldest first (class doc). */
    public static List<Double> atRestSeries(Model m, int track, long nowMs) {
        List<Double> out = new ArrayList<Double>();
        if (m == null) return out;
        boolean girth = track != Plan.TRACK_LENGTH;
        int method = Model.Reading.METHOD_STANDARDIZED;
        if (!hasAtRest(m, method, girth))
            method = girth ? Model.Reading.METHOD_MSEG : Model.Reading.METHOD_BPSSL;
        List<Long> weeks = TrainerTab.countedWeekStarts(m, track, m.trainerEnrolledAt, nowMs);
        long weekMs = 7L * 24L * 60L * 60L * 1000L;
        for (int w = 0; w < weeks.size(); w++) {
            long from = weeks.get(w).longValue(), to = from + weekMs;
            double sum = 0;
            int n = 0;
            for (int i = 0; i < m.measLog.all.size(); i++) {
                Model.Reading r = m.measLog.all.get(i);
                if (r == null || r.ts < from || r.ts >= to || r.ts > nowMs) continue;
                double v = atRest(r, method, girth);
                if (v > 0) { sum += v; n++; }
            }
            if (n > 0) out.add(Double.valueOf(sum / n));
        }
        return out;
    }

    private static boolean hasAtRest(Model m, int method, boolean girth) {
        for (int i = 0; i < m.measLog.all.size(); i++)
            if (atRest(m.measLog.all.get(i), method, girth) > 0) return true;
        return false;
    }

    /** The reading's at-rest value in `method`, or 0 when it is not one. */
    private static double atRest(Model.Reading r, int method, boolean girth) {
        if (r == null || r.method != method || r.phase == Model.Reading.PHASE_POST) return 0;
        double v = girth ? r.gir : r.len;
        return v > 0 && !Double.isNaN(v) ? v : 0;
    }

    /* ----------------------------------------------------- the answer, kept for its step */

    /** The clock of the step that waits: the pressure's (girth's pressure step, length's
     *  climb - each restarts when the step is applied), or the slow load step's. */
    public static long clockKey(Model.TrainerTrackState st, int kind) {
        if (st == null) return -1L;
        long base = kind == KIND_SLOW ? (long) st.slowLoadWeeks : st.pressureSinceMs;
        return base * 4L + kind;
    }

    public static boolean answered(Model.TrainerTrackState st, long key) {
        return st != null && key >= 0L && st.brakeKey == key && st.brakeAnswer != ANSWER_NONE;
    }

    public static boolean steppedUp(Model.TrainerTrackState st, long key) {
        return answered(st, key) && st.brakeAnswer == ANSWER_STEP_UP;
    }

    public static void answer(Model.TrainerTrackState st, long key, int answer) {
        if (st == null) return;
        st.brakeKey = key;
        st.brakeAnswer = answer;
    }

    /** When the waiting step comes at the braked pace: the Monday its training weeks are up
     *  (girth's pressure, the slow load step - training weeks are counted, so it is the week it
     *  falls in, not a day), or two months from the length pressure's last change. */
    public static long untilMs(Model m, int track, Model.TrainerTrackState st, int kind,
                               long nowMs) {
        long weekMs = 7L * 24L * 60L * 60L * 1000L;
        if (kind == KIND_CLIMB)
            return st.pressureSinceMs + (long) (CLIMB_MONTHS * 30.44 * 24.0 * 3600000.0);
        int have = kind == KIND_SLOW
            ? TrainerTab.accumulatedTrainingWeeks(m, Plan.TRACK_LENGTH, m.trainerEnrolledAt,
                                                  nowMs) - st.slowLoadWeeks
            : TrainerTab.weeksAtPressure(m, track, st.pressureSinceMs, nowMs);
        int need = kind == KIND_SLOW ? SLOW_WEEKS : GIRTH_WEEKS;
        return TrainerTab.mondayStartMs(nowMs) + Math.max(1, need - have) * weekMs;
    }

    /** What the Trainer card says for a step that waits: the offer, or - answered Wait - when. */
    public static final class Card {
        /** "You're gaining, so the next step waits until Mon 12 Oct. Step up now?" */
        public final String words;
        /** True while unanswered: the card shows [Step up now] [Wait]. */
        public final boolean offer;
        /** Which step waits (#KIND_PRESSURE, #KIND_CLIMB, #KIND_SLOW). */
        public final int kind;
        /** "Mon 12 Oct" - when the step comes at the slower pace. */
        public final String until;
        Card(String w, boolean o, int k, String u) { words = w; offer = o; kind = k; until = u; }
    }

    /** The shut row's words while the offer waits for an answer (FIX11, EMU13). */
    public static final String ROW_OFFER = "You're gaining · step up now?";

    /** The shut row's words after Wait: "Next step waits until Mon 12 Oct". */
    public static String rowWait(Card c) {
        return "Next step waits until " + (c == null ? "" : c.until);
    }

    /**
     * FIX11 (R11-4's device check could not reach it) - THE CARD FOR A TRACK'S DECISION, as the
     * Trainer tab draws it: null when the decision is not a step that waits; the offer while
     * the step's own clock (#clockKey) is unanswered; the date alone once it was answered Wait.
     * In core so a unit test drives the offer and both answers end to end.
     */
    public static Card card(Model m, int track, Model.TrainerTrackState st, Plan.Decision d,
                            long nowMs) {
        int kind = kindOf(d);
        if (kind < 0 || st == null) return null;
        String until = untilWords(m, track, st, kind, nowMs);
        boolean offer = !answered(st, clockKey(st, kind));
        return new Card(offer ? offerWords(until) : waitWords(until), offer, kind, until);
    }

    /** The card's date for {@link #untilMs}: "Mon 26 Oct". */
    public static String untilWords(Model m, int track, Model.TrainerTrackState st, int kind,
                                    long nowMs) {
        return Say.dayLabel(untilMs(m, track, st, kind, nowMs));
    }
}
