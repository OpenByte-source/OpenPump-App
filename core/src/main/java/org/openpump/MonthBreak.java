package org.openpump;

/**
 * THE MONTH-12 BREAK, AND THE WAY BACK FROM IT (t10-K, the owner's decisions of 2 Oct 2026).
 *
 * At the month-12 crossing the girth card and the length card each offer the same break: both
 * tracks rest for {@link Plan#LENGTH_BREAK_WEEKS} weeks from tomorrow (today's session counts),
 * and the plan runs again on its own when the date passes. What used to happen is gone: the
 * girth arm paused the whole plan with no end date and re-entered Level 2 week 1 at 8 inHg; the
 * length arm rested length alone and came back with the same load.
 *
 * COMING BACK:
 * <ul>
 * <li>B2 - girth comes back at Level 3 where it was (level, week anchor, holds), never at a
 *     higher pressure. Level 4 is offered again after 4 counted training weeks from the return
 *     day (length's after the gentle week), and the break is not offered again.</li>
 * <li>B3 - THE GENTLE WEEK, the first 7 calendar days from the return day: girth runs at 60 %
 *     of the plan's working pressure, whole kPa; every person gets the "I mark or bruise
 *     easily" warm-up on both tracks; girth takes no yield, fallback, time-cap, pressure or
 *     volume decision; the length load takes no step of any kind; the short gentle return after
 *     a deload is not stacked on it. Feeders take three quarters of the 60 % figure.</li>
 * <li>B4 - the length load comes back at three quarters of what it was, to 0.1 lb, never under
 *     5 lb (and never over what it was); the load before the break is kept as the climb-back
 *     target.</li>
 * <li>B5 - CLIMBING BACK: while the load is under that target, a week under 2 % raises it by
 *     half the gap (rounded up to 0.1 lb, the last step landing on the target) instead of
 *     option D; the usual pace otherwise, never past the target; a confirmed cut ends it.</li>
 * </ul>
 *
 * NOT A LAYOFF AND NOT A DELOAD. The break's days are carved out of the layoff gap (no step-back,
 * no "were you on a deload?"), its weeks are not missed weeks, and the deload cadence counts
 * again from the end of the gentle week - for both tracks, as every week off is plan-wide.
 *
 * PURE: no Android. The screens and the engine's inputs call in here; the harness asks exactly
 * the questions the app asks (MonthBreakTest).
 */
public final class MonthBreak {

    private MonthBreak() { }

    /** B3: the girth figure in the gentle week, percent of the plan's working pressure. */
    public static final int GENTLE_PCT = 60;
    /** B3: how long the gentle week is, calendar days from the return day. */
    public static final int GENTLE_DAYS = 7;
    /** B4: the share of the load the length track comes back at. */
    public static final double BACK_SHARE = 0.75;
    /** B4/B5: the step loads are rounded to here, pounds (the owner's figures: 11.0, 12.9). */
    public static final double LOAD_ROUND_LB = 0.1;
    /** B2: counted training weeks from the return day before Level 4 is offered again. */
    public static final int L4_AFTER_WEEKS = 4;

    /** B5 - the climb-back step's rule (the decision's own, what the card accepts). */
    public static final String CLIMB_RULE =
        "length: a week under 2 % while climbing back after the break -> half the gap";
    /** B3 - the length load holds through the gentle week. */
    public static final String GENTLE_LOAD_RULE =
        "length: no load step in the gentle week after the break";
    /** B1 - the plan holds while the break runs. */
    public static final String BREAK_RULE = "the month-12 break is running -> hold";

    /* ===================== THE DATES ===================== */

    /** Whether a month-12 break was ever taken on this plan (B6: 0 = never). */
    public static boolean taken(Model m) {
        return m != null && m.breakFromMs > 0L && m.breakUntilMs > 0L;
    }

    /** Whether the break runs at `nowMs`: from its first day to the return day. */
    public static boolean on(Model m, long nowMs) {
        return taken(m) && nowMs >= m.breakFromMs && nowMs < m.breakUntilMs;
    }

    /** Taken today, starting tomorrow: today's session still runs and counts (O3). */
    public static boolean pending(Model m, long nowMs) {
        return taken(m) && nowMs < m.breakFromMs;
    }

    /** The return day's instant - the date passing, or the "Come back now" tap. */
    public static long returnMs(Model m) {
        return taken(m) ? m.breakUntilMs : 0L;
    }

    /** The end of the gentle week, exclusive: 7 calendar days from the return day. */
    public static long gentleEndMs(Model m) {
        return taken(m) ? Deload.dayStartPlus(m.breakUntilMs, GENTLE_DAYS) : 0L;
    }

    /** B3 - whether `nowMs` is in the gentle week. */
    public static boolean gentleOn(Model m, long nowMs) {
        return taken(m) && nowMs >= m.breakUntilMs && nowMs < gentleEndMs(m);
    }

    /* ===================== TAKING IT, AND COMING BACK EARLY ===================== */

    /**
     * B1 - THE BREAK IS TAKEN at `nowMs`, from either card: both tracks rest from tomorrow's
     * local midnight for {@link Plan#LENGTH_BREAK_WEEKS} weeks. Nothing about either track is
     * written yet - today's session runs as it is and counts; the break's own changes are made
     * when it starts ({@link #settle}). The caller saves as a schedule edit.
     */
    public static void take(Model m, long nowMs) {
        if (m == null) return;
        m.breakFromMs = Deload.dayStartPlus(nowMs, 1);
        m.breakUntilMs = Deload.dayStartPlus(nowMs, 1 + Plan.LENGTH_BREAK_WEEKS * 7);
        m.breakStarted = false;
    }

    /**
     * B1 - "COME BACK NOW": the break ends at the tap and B2-B5 apply from today. Taken today
     * and not started yet, it is simply not taken (nothing was changed). The caller saves.
     */
    public static void comeBackNow(Model m, long nowMs) {
        if (!taken(m)) return;
        if (pending(m, nowMs)) {
            m.breakFromMs = 0L; m.breakUntilMs = 0L; m.breakStarted = false;
            return;
        }
        if (!on(m, nowMs)) return;
        m.breakUntilMs = nowMs;
        if (!m.breakStarted) start(m);
        else clocks(m);
    }

    /**
     * THE DAY'S STATE, settled - what Today, the Trainer and a run start call first (with the
     * gentle return's own settle). The first time it is asked on or after the break's first
     * day, the break's changes are made ({@link #start}); and the gentle week's warm-up for
     * everybody is put on the model (Model#breakMarks, never saved) so the signature, the
     * build and the card agree. Returns whether anything saved changed, so the caller saves.
     */
    public static boolean settle(Model m, long nowMs) {
        if (m == null) return false;
        settleMarks(m, nowMs);
        if (!taken(m) || m.breakStarted || nowMs < m.breakFromMs) return false;
        start(m);
        return true;
    }

    /** Only the gentle week's warm-up for the day - what an evaluation sets before it signs a
     *  prescription, so the signature and the build agree (nothing saved moves). */
    public static void settleMarks(Model m, long nowMs) {
        if (m != null) m.breakMarks = gentleOn(m, nowMs);
    }

    /** The break's changes, once: the length load and its target (B4), the clocks (B5, O4),
     *  and no gentle return stacked on the gentle week (B3). */
    static void start(Model m) {
        m.breakStarted = true;
        if (Deload.armed(m)) Deload.end(m);
        Model.TrainerTrackState l = m.trainerLength;
        if (m.trainerLengthOn) {
            double was = l.loadLb;
            double back = backLoadLb(was);
            if (back < was - 1e-9) {
                l.setLoadLb(back, m.breakFromMs);
                l.climbTargetLb = was;
            } else {
                l.climbTargetLb = 0.0;
            }
        }
        // A new option-D block starts at the return day, with every other week-off counter.
        l.dAddedInBlock = false;
        l.dOfferedInBlock = false;
        clocks(m);
    }

    /**
     * THE CLOCKS THE RETURN COUNTS FROM - again when "Come back now" moves the return day.
     * Length: readings and the monthly climb count from the return day. Girth: the yield
     * streak and the pressure clock from the end of the gentle week, so a 60 % week's readings
     * never add sets and no pressure step lands on the first full week back.
     */
    private static void clocks(Model m) {
        long back = m.breakUntilMs, full = gentleEndMs(m);
        Model.TrainerTrackState l = m.trainerLength, g = m.trainerGirth;
        l.restartStrainClock(back);
        l.restartPressureClock(back);
        g.yieldSinceMs = full;
        g.restartPressureClock(full);
    }

    /** B4 - the load the length track comes back at: three quarters, to 0.1 lb, never under
     *  the 5 lb floor and never over what it was (a load already under the floor stays). */
    public static double backLoadLb(double wasLb) {
        double back = roundLb(wasLb * BACK_SHARE);
        back = Math.max(Plan.LENGTH_LOAD_FLOOR_LB, back);
        return Math.min(wasLb, back);
    }

    /** To the nearest 0.1 lb. */
    static double roundLb(double lb) {
        return Math.round(lb / LOAD_ROUND_LB) / (1.0 / LOAD_ROUND_LB);
    }

    /** Up to the next 0.1 lb (a hair of float under a tenth is that tenth). */
    static double ceilLb(double lb) {
        return Math.ceil(lb / LOAD_ROUND_LB - 1e-6) / (1.0 / LOAD_ROUND_LB);
    }

    /* ===================== THE ENGINE'S INPUTS ===================== */

    /**
     * WHAT THE BREAK TELLS THE ENGINE about `track` at `nowMs` - called last by the input
     * fillers (TrainerTab#fillGirthInputs, LengthTrack#fill, TrainerTab#feederInputs), so it
     * reads and overrides what they and the caller set.
     */
    public static void fill(Model m, int track, long nowMs, Plan.Inputs in) {
        if (m == null || in == null || !taken(m)) return;
        in.onBreak = on(m, nowMs);
        if (in.onBreak) return;
        boolean gentle = gentleOn(m, nowMs);
        in.gentleWeek = gentle;
        if (track == Plan.TRACK_FEEDER) return;
        // Taken today, not started: neither track is offered Level 4 beside the break.
        in.l4Waits = pending(m, nowMs);
        if (track == Plan.TRACK_LENGTH) {
            /* LENGTH'S LEVEL 4 waits for the gentle week to end (the owner's ruling; the editor
             * model's N23): its month gate waits for a full day, as after any return (below). */
            Model.TrainerTrackState l = m.trainerLength;
            // B5: the climb ends when the load reaches its target, whatever moved it there.
            if (l.climbTargetLb > 0.0 && l.loadLb >= l.climbTargetLb - 1e-9) l.climbTargetLb = 0.0;
            in.climbTargetLb = l.climbTargetLb;
            in.climbCapLb = climbCapLb(m, l.climbTargetLb);
            // B3: nothing on the length ladder that waits for a full day runs in the gentle
            // week - the month gate, the monthly climb, the slow load step.
            if (gentle) in.returnRunsUnder = true;
            return;
        }
        // B2: GIRTH'S LEVEL 4 waits for 4 counted training weeks from the return day.
        if (TrainerTab.accumulatedTrainingWeeks(m, m.trainerGirthStyle, returnMs(m), nowMs)
                < L4_AFTER_WEEKS) in.l4Waits = true;
        if (!gentle) return;
        /* B3 - GIRTH IN THE GENTLE WEEK: the return-day guards (M-3), all week. No level-up,
         * volume, fallback, time-cap or pressure step (the gentle return's own guards), and the
         * readings are kept but drive nothing: no yield streak, no early-target or
         * under-delivery answer read off sessions run at 60 %. */
        in.gentleReturnOpen = true;
        in.returnRunsUnder = true;
        in.hasYieldData = false;
        in.consecutiveLowYield = 0;
        in.consecutiveHighYield = 0;
        in.weeksWithoutReadings = 0;
        in.underDelivery = false;
        in.targetHitFraction = Double.NaN;
    }

    /** B5 - the most the climb back may reach: its target, 15 lb, "Most you will go to" for
     *  length and the device ceiling at the length cylinder's bore. 0 with no target. */
    static double climbCapLb(Model m, double targetLb) {
        if (targetLb <= 0.0) return 0.0;
        double cap = Math.min(targetLb, Scale.LOAD_HARD_MAX_LB);
        double bore = m.lengthBoreCm();
        if (bore > 0) {
            if (m.rxLengthMaxKpa > 0)
                cap = Math.min(cap, Traction.loadLbAtBore(Math.floor(m.rxLengthMaxKpa + 1e-9),
                                                          bore));
            cap = Math.min(cap, Traction.loadLbAtBore(m.ceilKpa, bore));
        }
        return cap;
    }

    /** B5 - whether the length load is climbing back: under its target and its cap. */
    public static boolean climbing(Plan.Inputs in) {
        return in != null && in.climbTargetLb > 0.0
            && in.loadLb < Math.min(in.climbTargetLb, in.climbCapLb) - 1e-9;
    }

    /**
     * B5 - A WEEK UNDER 2 % WHILE CLIMBING BACK: half the gap to the target, rounded up to
     * 0.1 lb, the last step landing exactly on it - never past the cap. Null when the load
     * moved since the last length session (one change between two sessions, REAL-11): the
     * caller's later rungs answer, and its hold says the step waits.
     */
    static Plan.Decision climbStep(Plan.Inputs in) {
        if (in.loadMovedSinceSession) return null;
        double top = Math.min(in.climbTargetLb, in.climbCapLb);
        double next = roundLb(in.loadLb + ceilLb((in.climbTargetLb - in.loadLb) / 2.0));
        if (next >= top - 1e-9) next = top;
        return new Plan.Decision(Plan.ACTION_RAISE_LOAD, Plan.TAG_INFERRED, CLIMB_RULE,
            "under 2 % for a week while climbing back after the break: half the way back to "
            + Traction.settingLb(in.climbTargetLb) + " - " + Traction.settingLb(in.loadLb)
            + " to " + Traction.settingLb(next), Double.NaN, 0, next);
    }

    /**
     * THE LENGTH LADDER'S ANSWER, as the break lets it stand: no load step of any kind in the
     * gentle week (a high reading asks to measure again; the cut waits for the week to end),
     * and while climbing back no load past the target (B5).
     */
    public static Plan.Decision lengthAfter(Plan.Inputs in, Plan.Decision d) {
        if (in == null || d == null || Double.isNaN(d.loadLb)) return d;
        if (in.gentleWeek) {
            if (d.action == Plan.ACTION_REMEASURE)
                return new Plan.Decision(Plan.ACTION_REMEASURE, Plan.TAG_INFERRED,
                    Plan.LENGTH_HIGH_RULE, Plan.LENGTH_HIGH_WORDS, Double.NaN, 0);
            return new Plan.Decision(Plan.ACTION_HOLD, Plan.TAG_INFERRED, GENTLE_LOAD_RULE,
                "the gentle week after the break: the length load holds until it ends",
                Double.NaN, 0);
        }
        if (climbing(in) && d.action == Plan.ACTION_RAISE_LOAD
                && d.loadLb > in.climbTargetLb + 1e-9) {
            double top = Math.min(in.climbTargetLb, in.climbCapLb);
            return new Plan.Decision(Plan.ACTION_RAISE_LOAD, d.tag, d.rule,
                d.reason + " (to " + Traction.settingLb(top) + ", the load before the break)",
                d.pressureKpa, d.setsDelta, top);
        }
        return d;
    }

    /** B1 - the hold every track answers with while the break runs. */
    public static Plan.Decision breakHold() {
        return new Plan.Decision(Plan.ACTION_HOLD, Plan.TAG_INFERRED, BREAK_RULE,
            "your 4-week break is running: both tracks rest until it ends", Double.NaN, 0);
    }

    /* ===================== THE GENTLE WEEK'S GIRTH FIGURE ===================== */

    /** B3 - 60 % of the plan's working pressure (the person's own figure where it is lower),
     *  whole kPa, rounded to nearest - never under the reduction floor. */
    public static int gentleKpa(Model m) {
        Model.TrainerTrackState g = m.trainerGirth;
        double plan = Math.min(g.pressureKpa, g.pressureKpa + g.offsetKpa);
        return (int) Math.max(Mint.MIN_REDUCED_KPA, Math.round(plan * GENTLE_PCT / 100.0));
    }

    /**
     * The gentle week's girth figure for a build of `track` at `atMs`, whole kPa - 0 when none
     * applies. A model a signature was rebuilt in reads the signature's ({@link #fromSig}),
     * so a routine minted in the gentle week is the plan's own after it, and one minted
     * before it is the plan's own in it.
     */
    public static int girthKpaAt(Model m, int track, long atMs) {
        if (m == null || !TrainerTab.isGirthTrack(track)) return 0;
        if (m.breakSigKpa >= 0) return m.breakSigKpa;
        return gentleOn(m, atMs) ? gentleKpa(m) : 0;
    }

    /**
     * B3 - a girth prescription as the gentle week runs it: at its figure - or at 60 % of what
     * the session would otherwise run at, where a limit or the Program's gentle holds that
     * lower - asking for no net minutes (as a reduced day asks none, Mint#reduce). Never raised.
     */
    public static Mint.Rx girthDay(Model m, Mint.Rx rx, long atMs) {
        if (rx == null) return null;
        int kpa = girthKpaAt(m, rx.track, atMs);
        if (kpa <= 0) return rx;
        kpa = Math.min(kpa, (int) Math.max(Mint.MIN_REDUCED_KPA,
                                           Math.round(rx.pressureKpa * GENTLE_PCT / 100.0)));
        if (rx.pressureKpa <= kpa) return rx;
        return new Mint.Rx(rx.track, rx.level, rx.sets, rx.holdSec, rx.restSec, kpa,
                           rx.fatigue, 0.0, rx.powerPct, rx.offKpa);
    }

    /** Feeders in the gentle week: their share of the 60 % figure, not of the full one. */
    public static double feederMainKpa(Model m, double mainKpa, long nowMs) {
        int kpa = girthKpaAt(m, m == null ? 0 : m.trainerGirthStyle, nowMs);
        return kpa > 0 ? Math.min(mainKpa, kpa) : mainKpa;
    }

    /* ===================== THE SIGNATURE ===================== */

    /** The signature's segment spelling: "MB22" - the gentle week's girth figure. */
    private static final String SIG_TAG = "MB";

    /** The gentle week's segment for a mint of `track` at `nowMs`, or "" - so every signature
     *  outside the gentle week is the one it always was. */
    public static String tag(Model m, int track, long nowMs) {
        int kpa = girthKpaAt(m, track, nowMs);
        return kpa > 0 ? SIG_TAG + kpa : "";
    }

    /** Puts on a scratch model what a signature records of the gentle week: its girth figure
     *  (0: none) and no warm-up of its own - the shape's gentle-warm-up token says that. */
    public static void fromSig(Model s, String sig) {
        if (s == null) return;
        s.breakMarks = false;
        s.breakSigKpa = 0;
        if (sig == null) return;
        String[] segs = sig.split("\\|", -1);
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i];
            if (!seg.startsWith(SIG_TAG) || seg.length() <= SIG_TAG.length()
                    || seg.length() > SIG_TAG.length() + 3) continue;
            try { s.breakSigKpa = Integer.parseInt(seg.substring(SIG_TAG.length())); }
            catch (NumberFormatException e) { s.breakSigKpa = 0; }
        }
    }

    /** A scratch copy of `m` carries today's gentle-week warm-up (it is never saved). */
    public static void copyTransient(Model from, Model to) {
        if (from == null || to == null) return;
        to.breakMarks = from.breakMarks;
    }

    /* ===================== WHAT ELSE ASKS ===================== */

    /** The layoff rule's carve-out: how much of [fromMs, toMs) the break covers. */
    public static long overlapMs(Model m, long fromMs, long toMs) {
        if (!taken(m) || toMs <= fromMs) return 0L;
        long lo = Math.max(m.breakFromMs, fromMs), hi = Math.min(m.breakUntilMs, toMs);
        return hi > lo ? hi - lo : 0L;
    }

    /** The miss policy: the break touched the week [weekStartMs, weekEndMs) - not missed. */
    public static boolean touchesWeek(Model m, long weekStartMs, long weekEndMs) {
        return taken(m) && m.breakFromMs < weekEndMs && m.breakUntilMs > weekStartMs;
    }

    /** B1 (the coordinator's ruling, 2 Oct, round 3 follow-up): the deload cadence counts
     *  from the RETURN DAY - the gentle week is a training week for it. Girth's yield streak
     *  and pressure clock still restart at the gentle week's end (#clocks). 0 with no break. */
    public static long cadenceFromMs(Model m) {
        return taken(m) ? returnMs(m) : 0L;
    }

    /** Option D's block starts again at the return day (O4). 0 with no break. */
    public static long blockFromMs(Model m) {
        return taken(m) ? returnMs(m) : 0L;
    }

    /** B5 - an accepted load on the length track: a confirmed cut, or reaching the target,
     *  ends the climb back. */
    public static void loadAccepted(Model m, String rule, double lb) {
        if (m == null) return;
        Model.TrainerTrackState l = m.trainerLength;
        if (l.climbTargetLb <= 0.0) return;
        if (Plan.LENGTH_CUT_RULE.equals(rule) || lb >= l.climbTargetLb - 1e-9)
            l.climbTargetLb = 0.0;
    }

    /** The setup is a fresh start for the length load: no climb back. */
    public static void atSetup(Model m) {
        if (m != null) m.trainerLength.climbTargetLb = 0.0;
    }

    /* ===================== WORDS ===================== */

    /** The break, as a sentence names it. */
    public static final String BREAK_WEEKS_WORDS = Plan.LENGTH_BREAK_WEEKS
        + "-week break on both tracks";

    /** The month-12 card's break button, on either track. */
    public static final String ARM_LABEL = "Take a " + Plan.LENGTH_BREAK_WEEKS
        + "-week break (both tracks) ▸";

    /** The take dialog's title. */
    public static final String TAKE_TITLE = "Take a " + Plan.LENGTH_BREAK_WEEKS + "-week break?";

    /**
     * WHAT THE TAKE DIALOG SAYS (B1): both tracks, the dates, and what happens on return per
     * B2-B5 - only the lines for the tracks that are on. `backOn` is the return day as the
     * screen prints dates.
     */
    public static String takeText(Model m, String backOn) {
        StringBuilder b = new StringBuilder();
        b.append("Both tracks rest for ").append(Plan.LENGTH_BREAK_WEEKS)
         .append(" weeks, from tomorrow. The plan starts again by itself on ").append(backOn)
         .append(". Today’s session still counts, and nothing is deleted.")
         .append("\n\nWhen you come back:");
        if (m.trainerGirthOn)
            b.append(BULLET + "Girth comes back at Level 3, where it is now, never at a higher "
                + "pressure.");
        b.append(BULLET + "The first week back is gentle: ");
        if (m.trainerGirthOn) b.append("girth at ").append(Model.Fmt.pct(GENTLE_PCT))
            .append(" of your working pressure, and ");
        b.append("the gentle warm-up").append(m.trainerGirthOn && m.trainerLengthOn
            ? " on both tracks." : ".");
        if (m.trainerLengthOn) {
            double was = m.trainerLength.loadLb, back = backLoadLb(was);
            if (back < was - 1e-9)
                b.append(BULLET + "Length comes back at ").append(Model.Fmt.load(back))
                 .append(", three quarters of ").append(Model.Fmt.load(was))
                 .append(", and climbs back to it as your readings allow.");
            else
                b.append(BULLET + "Length comes back at its load, ")
                 .append(Model.Fmt.load(was)).append('.');
        }
        if (m.trainerGirthOn)
            b.append(BULLET + "Girth’s Level 4 is offered again after ").append(L4_AFTER_WEEKS)
             .append(" training weeks back").append(m.trainerLengthOn
                 ? "; length’s after the gentle week." : ".");
        else
            b.append(BULLET + "Level 4 is offered again after the gentle week.");
        b.append("\n\nYou can come back early from the Trainer.");
        return b.toString();
    }

    /** A line of the take dialog's list. */
    private static final String BULLET = "\n• ";

    /** What the Trainer says while the break runs (`backOn`: the return day, `days` to go). */
    public static String onText(String backOn, long days) {
        return "Both tracks rest until " + backOn + " — " + days
            + (days == 1 ? " day" : " days") + " to go. Nothing is offered or reminded "
            + "until then, and these weeks are not counted as missed.";
    }

    /** ...and taken today, before it starts. */
    public static String pendingText(String backOn) {
        return "Both tracks rest from tomorrow until " + backOn + ". Today’s session "
            + "still runs and counts.";
    }

    /** What the Trainer says in the gentle week (`until`: its last day as printed). */
    public static String gentleText(Model m, String until, long nowMs) {
        StringBuilder b = new StringBuilder("Until ").append(until).append(": ");
        if (m.trainerGirthOn)
            b.append("girth runs at ").append(Model.Fmt.p(girthRunsKpa(m, nowMs))).append(" (")
             .append(Model.Fmt.pct(GENTLE_PCT)).append(" of your working pressure), and ");
        b.append("the gentle warm-up runs").append(m.trainerGirthOn && m.trainerLengthOn
            ? " on both tracks." : ".");
        Model.TrainerTrackState l = m.trainerLength;
        if (m.trainerLengthOn && l.climbTargetLb > l.loadLb + 1e-9)
            b.append(" Length runs at ").append(Model.Fmt.load(l.loadLb))
             .append(" and climbs back to ").append(Model.Fmt.load(l.climbTargetLb))
             .append(" after this week, as your readings allow.");
        return b.toString();
    }

    /** What the girth session runs at on `nowMs`, as the builder commands it (RxBuild#commanded):
     *  the figure the gentle week's card names. */
    public static int girthRunsKpa(Model m, long nowMs) {
        Mint.Rx rx = TrainerTab.trackRx(m, m.trainerGirthStyle, m.trainerGirth,
            TrainerTab.monthIndexNow(m, nowMs), null);
        Mint.Rx c = RxBuild.commanded(m, rx, RxBuild.Day.at(m, nowMs));
        return c == null ? gentleKpa(m) : c.pressureKpa;
    }

    /** What a plan session started on the break says in the pre-run box. */
    public static String extraLine(String backOn) {
        return "You are on your " + BREAK_WEEKS_WORDS + " until " + backOn
            + ": the plan offers no session. This one runs as an extra.";
    }

    /** "Come back now", asked. */
    public static final String BACK_TITLE = "Come back now?";
    public static final String BACK_TEXT = "The break ends today and the gentle week starts: "
        + "girth at " + Model.Fmt.pct(GENTLE_PCT) + " of its working pressure, the gentle warm-up, and length "
        + "at its lighter load. Girth’s Level 4 is offered again after " + L4_AFTER_WEEKS
        + " training weeks back; length’s after the gentle week.";

    /** Whether the month-12 card still offers the break: never again once one was taken. */
    public static boolean armOffered(Model m) { return !taken(m); }

    /**
     * The month-12 card's sentence after a break: the break is not offered again, so the card
     * does not name it. Before one, the decision's own reason.
     */
    public static String levelUpWords(Model m, int track, int nextLevel, String reason) {
        if (nextLevel != Plan.L4 || !taken(m)) return reason;
        return track == Plan.TRACK_LENGTH
            ? "month 12 — move up to Level 4, or take a girth block instead"
            : "month 12, and your sessions hold the volume — move up to Level 4";
    }
}
