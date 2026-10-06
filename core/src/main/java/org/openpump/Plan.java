package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage H — the pumping-progression engine, PURE LOGIC ONLY.
 *
 * NO {@code import android}. Like {@link Progression}, {@link Meas} and {@link Summary},
 * this file carries no framework reference, so {@code test.sh} auto-discovers it as a pure
 * source and compiles it into the desktop self-test. Tasks 2–6 wire it to {@code Model},
 * the Trainer tab and the outside-tab hooks; NONE of that lives here. This file is the
 * spec's §9 engine, its §5/§6 girth tables, its §4 length spec, and the plan's binding
 * misuse/audit/round-2/round-3 rulings, expressed as static methods over small value types
 * the UI tasks call. It stores and computes everything in kPa; the guide's hg values are
 * converted ONCE, at constant-definition, through the existing {@link Model.Fmt#KPA_PER_INHG}
 * (3.38639) — never at runtime.
 *
 * WHY THE SPEC AUTHOR'S §11 TAGS SURVIVE INTO CODE. The source PDF marks ~17 rules
 * {@code [INF]}/{@code [ADAPTED]}/{@code [DERIVED]} — reconstructions, not source-stated
 * facts. Any {@link Decision} resting on one carries the matching {@link #TAG_INFERRED} /
 * {@link #TAG_ADAPTED} / {@link #TAG_DERIVED} so the tab can show it plainly; a decision on
 * a source-stated rule carries {@link #TAG_SOURCE}.
 *
 * THE THREE META-RULES everything reduces to (plan "Misuse/inconsistency rules"):
 *   1. absence of data = HOLD — {@link #evaluate} never guesses a progression from missing
 *      signals; with no yield/net data and no event, it holds and says what it lacks;
 *   2. non-compliance = doesn't count — a non-qualifying week, an out-of-band session, a
 *      RED-flagged run never BLOCK, they simply fail to advance a counter;
 *   3. every non-count is visible with its reason — the {@code reason} on every HOLD /
 *      non-advancing {@link Decision} carries why.
 *
 * <h2>WHERE THE NUMBERS ACTUALLY COME FROM — read this before trusting a §-citation</h2>
 *
 * THE "§" REFERENCES THROUGHOUT THIS FILE POINT AT A SPEC PDF THAT IS NOT IN THIS
 * REPOSITORY. Seventy-eight of them, across twelve sections, and not one resolves: the
 * publicly available guidance this app follows uses no such numbering, and the string
 * net_tup, written here as if lifted from §9, appears nowhere outside this codebase. The
 * rules are real; the addresses are not. Anyone auditing this engine against the guidance
 * had to find each rule by hunting, which is the opposite of what a citation is for.
 *
 * So the load-bearing constants are checked below against the guidance itself, each rule
 * paraphrased. Where the guidance and this engine disagree, the disagreement is stated
 * rather than smoothed over.
 *
 * <pre>
 *   CONSTANT                      VALUE      WHAT THE GUIDANCE SAYS
 *   ABSOLUTE_CAP_KPA              15 hg      never pump past 15 hg; it is repeated in the
 *                                            guidance's list of things not to do.
 *   GROSS_CAP_SEC                 2 h        the same list: no more than two hours of
 *                                            pumping in one session.
 *   SET_ADVISORY_SEC              20 min     a single set stays under 20 minutes, ideally
 *                                            10 or less.
 *   MONTH1_CAP_KPA                6 hg       stay at 6 hg or under for the first month.
 *   L1_BAND_LO/HI_KPA             4–7 hg     girth pumping usually sits between 4 and 7 hg,
 *                                            and the interval routine targets that band.
 *   WORKING_CAP_KPA               10 hg      most users pump somewhere from 5 to 10 hg.
 *   L1_GATE_NET_MIN               20 min     add one interval at a time until the total
 *                                            time under pressure reaches 20 minutes.
 *   STEP_HG_KPA                   +1 hg      then raise the pressure by one hg every three
 *   PRESSURE_RAISE_EVERY_         3 weeks    weeks until it reaches eight - which is also
 *     TRAINING_WEEKS                         L1_GATE_PRESSURE_HG and L1_CAP_KPA, all three
 *   L1_GATE_PRESSURE_HG           8 hg       from that one rule.
 *   L1_GATE_HOLD_TRAINING_WEEKS   2 weeks    keep that routine for two weeks, then move on.
 *   DELOAD_FIRST_AFTER_           4 then 3   a dated table of deload weeks: the first after
 *     TRAINING_WEEKS / _AFTER_               weeks 1-4 (week 5), the next after weeks 6-8
 *                                            (week 9), then weeks 13 and 17.
 *                                            NOTE THE CONFLICT, and that this table wins:
 *                                            elsewhere the guidance repeats a plain week off
 *                                            after every four weeks, which would give 4/4/4.
 *                                            The dated table is the more specific statement
 *                                            and is what the ladder reproduces.
 *   FEEDER_PER_DAY / pressure     2, 70–80%  two light feeder sessions a day, ten minutes
 *     / 10 min / 4–6 h spacing               each at 70-80% of the main pressure, four to
 *                                            six hours away from the main session; the
 *                                            guidance states it twice.
 *                                            S20/NOTE THE GAP THIS BUILDS: FEEDER_MIN_GAP_MS
 *                                            / FEEDER_MAX_GAP_MS and feederState below are
 *                                            verified against these numbers, but
 *                                            TrainerTab#feederReady measures the 4-6 h
 *                                            FEEDER-TO-FEEDER, from the day's last feeder
 *                                            session only - never from the main session as
 *                                            the rule has it. Not smoothed over (S10, a
 *                                            different lane's fix).
 *   TRAINING_WEEK_MIN_DAYS        3 days     train three to five days a week (stated for
 *                                            each routine).
 * </pre>
 *
 * WHAT IS STILL UNVERIFIABLE, listed so it is not mistaken for verified: the L1/L2–L4
 * Net-TUP FLOORS (5 hg / 8 hg) as a two-tier split, the four-level ladder itself, the
 * month-6 and month-12 gates, the yield-debounce of 3, and the §11 provenance tags. Those are
 * the spec author's synthesis, not statements in the guidance, and they cannot be
 * checked until that spec is in the repository.
 */
public final class Plan {
    private Plan() { }

    /* ===================================================================== *
     *  PRESSURE CONSTANTS — kPa, hg in the comment, converted once here.    *
     *  hg -> kPa via Model.Fmt.KPA_PER_INHG at definition, never at call.   *
     * ===================================================================== */

    /** One inch-of-mercury in kPa (3.38639), the guide's native unit. All the pressure
     *  constants below are {@code <hg> * HG} so the conversion happens exactly once. */
    public static final double HG = Model.Fmt.KPA_PER_INHG;

    /** L1 Net-TUP floor — 16.93 kPa ≈ 5 hg (the guidance: L1 = 5 hg). */
    public static final double L1_FLOOR_KPA = 5.0 * HG;
    /** L2/L3/L4 Net-TUP floor — 27.09 kPa ≈ 8 hg (the guidance: L2 = L3 = L4 = 8 hg). */
    public static final double L234_FLOOR_KPA = 8.0 * HG;

    /** Month-1 pressure ceiling — 20.32 kPa ≈ 6 hg (the guidance: month-1 cap 6 hg;
     *  §11 item 12 globalizes the device-track note, tagged {@link #TAG_ADAPTED}). */
    public static final double MONTH1_CAP_KPA = 6.0 * HG;
    /** L1 pressure cap — 27.09 kPa ≈ 8 hg (the guidance L1 band tops at 8 hg, the L1→L2 gate). */
    public static final double L1_CAP_KPA = 8.0 * HG;
    /** Working pressure cap for L2/L3/L4 — 33.86 kPa ≈ 10 hg (the guidance: cap 10). */
    public static final double WORKING_CAP_KPA = 10.0 * HG;
    /** Absolute ceiling — 50.80 kPa ≈ 15 hg — NEVER exceeded (the guidance: 15 hg — never). */
    public static final double ABSOLUTE_CAP_KPA = 15.0 * HG;

    /**
     * THE SAME CAP AS A WHOLE kPa, AND IT ROUNDS DOWN.
     *
     * Everything that reaches the wire is a whole kPa, so the double above has to become an
     * int somewhere - and the arithmetic that does it decides whether "never exceeded" is
     * true. 15 hg is 50.7958; rounding to nearest gives 51, which is 15.06 hg, and the
     * clamps that are supposed to ENFORCE the limit were the things breaking it. The
     * self-test agreed with them, asserting {@code <= Math.round(ABSOLUTE_CAP_KPA)}.
     *
     * A cap rounds DOWN. Rounding a ceiling to nearest raises it half the time, and half the
     * time is not never. Guidance figures - a band, a suggestion, a target - may round to
     * nearest, because being a tenth either side of them is not a broken promise. This is
     * not one of those.
     */
    public static int absoluteCapWholeKpa() {
        return (int) Math.floor(ABSOLUTE_CAP_KPA);
    }

    /**
     * WHAT A STARTING-PRESSURE ANSWER IS HELD TO — the whole of it, in one place.
     *
     * Three of the four terms are not negotiable and apply to everybody: {@link
     * #ABSOLUTE_CAP_KPA} ("15 hg — never"), the device's own ceiling, and whatever MAXIMUM
     * the person said they are willing to go to (0 when they did not say). The fourth is the
     * beginner cap, and it is the one this method exists to make conditional.
     *
     * WHY IT IS CONDITIONAL NOW. The month-1 cap of {@link #MONTH1_CAP_KPA} is written for
     * somebody who has never pumped, and the app used to apply it to everybody whose plan was
     * new — which is everybody, on the day they enrol. Reported from a device: a working
     * pressure of 11 inHg came back as a prescribed routine peaking at 5.9. The person was
     * not a beginner; their PLAN was. Those are different facts and the app now asks for the
     * first one rather than inferring it from the second.
     *
     * {@code statedMaxKpa} 0 means "not stated" and drops out of the min — never treated as
     * a cap of zero, which would prescribe nothing at all.
     */
    public static double startCapKpa(boolean newToPumping, int monthIndex,
                                      double ceilKpa, double statedMaxKpa) {
        double cap = Math.min(ABSOLUTE_CAP_KPA, ceilKpa > 0 ? ceilKpa : ABSOLUTE_CAP_KPA);
        if (statedMaxKpa > 0) cap = Math.min(cap, statedMaxKpa);
        if (newToPumping && monthIndex < 1) cap = Math.min(cap, MONTH1_CAP_KPA);
        return cap;
    }

    /** Is this pressure past the band all three documents treat as ordinary? Not a refusal
     *  and not a cap — {@link #WORKING_CAP_KPA} is the guidance's stated working cap, and
     *  somebody already training above it should be told that, not corrected. The refusal
     *  line is {@link #ABSOLUTE_CAP_KPA}, which is a different sentence entirely. */
    public static boolean pastUsualPressure(double kpa) {
        return kpa > WORKING_CAP_KPA + 1e-9;
    }

    /** L1 starting-pressure band low/high — 4/7 hg (the guidance: girth L1 band 4–7). */
    public static final double L1_BAND_LO_KPA = 4.0 * HG;
    public static final double L1_BAND_HI_KPA = 7.0 * HG;

    /**
     * THE WEEK YIELD STARTS MOVING THINGS - L1 week 10 (the guidance calendar engine, "yield
     * modifiers from wk 10", and the L1 table's own event cell "yield tracking begins").
     *
     * The spec says two things here and the code used to take the other one: §1.4's role
     * table calls yield REFERENCE-ONLY at L1, which gated the whole volume tier to L2 and up.
     * Ruling: §7 governs, because it is the section that describes what the engine DOES,
     * and the L1 week table independently marks week 10 as where tracking begins. Before
     * week 10 yield still moves nothing - there is no cadence to debounce over yet.
     */
    public static final int YIELD_FROM_WEEK_L1 = 10;

    /**
     * THE TWO-HOUR GROSS CAP - the longest a single session may stay sealed (the guidance,
     * "Gross TUP with the 2-hour cap, hard stop").
     *
     * GROSS, not net and not wall clock. Time out of the cylinder does not count, and neither
     * does time below the floor: this bounds how long the tissue has been under a seal, which
     * is the quantity the limit is about.
     *
     * IT IS NOT A PLAN FEATURE. The guide files it under safety, and a limit that only
     * applies to people who happen to be enrolled is not a safety limit - so it binds every
     * run, plan or not.
     */
    public static final double GROSS_CAP_SEC = 2 * 3600;

    /** Whether a session has reached the cap. A pure comparison, so the rule is stated once
     *  and the caller is left with nothing to get wrong about it. */
    public static boolean grossCapReached(double grossSec) {
        return grossSec >= GROSS_CAP_SEC;
    }

    /** One pressure step = +1 hg (the guidance: pressure += 1 hg). */
    public static final double STEP_HG_KPA = 1.0 * HG;

    /* ===================================================================== *
     *  ENUM-LIKE INT CONSTANTS (codebase convention: int constants, not     *
     *  Java enums — matches Model/Proto).                                    *
     * ===================================================================== */

    // Tracks. Girth style is interval XOR traditional; length is concurrent; feeder is
    // its own marker (plan §7 / round-2 feeder ruling).
    public static final int TRACK_GIRTH_INTERVAL = 1;
    public static final int TRACK_GIRTH_TRADITIONAL = 2;
    public static final int TRACK_LENGTH = 3;
    public static final int TRACK_FEEDER = 4;

    // Levels.
    public static final int L1 = 1, L2 = 2, L3 = 3, L4 = 4;

    // Engines. month<6 -> CALENDAR (+ yield modifiers from wk10); month>=6 -> METRIC.
    public static final int ENGINE_CALENDAR = 0;
    public static final int ENGINE_METRIC = 1;

    // Decision actions (the §9 order of operations plus the plan's event/feeder branches).
    public static final int ACTION_HOLD = 0;
    public static final int ACTION_ADD_VOLUME = 1;      // yield<target x3 -> +sets (Net TUP first)
    public static final int ACTION_RAISE_PRESSURE = 2;  // net>=milestone -> +1hg/3wk to cap
    public static final int ACTION_DELOAD = 3;          // usage-linked deload week, counters frozen
    public static final int ACTION_STEP_BACK = 4;       // RED / layoff / under-delivery
    public static final int ACTION_CEILING_DEADLOCK = 5;// wanted a raise but ceilKpa < next step
    public static final int ACTION_FEEDER_SUGGEST = 6;  // feeder pressure re-derived from main
    public static final int ACTION_FEEDER_PAUSED = 7;   // feeder paused (deload/safety)
    public static final int ACTION_DISABLED = 8;        // feeder below L3
    public static final int ACTION_PAUSE_VOLUME = 9;    // L2 yield>hi x3 -> pause set additions
    public static final int ACTION_REDUCE_VOLUME = 10;  // L3/L4 yield>hi x3 -> reduce volume
    public static final int ACTION_LEVEL_UP = 11;       // a met level gate -> propose next level
    public static final int ACTION_RAISE_LOAD = 12;     // strain low at set cap -> +0.5 lb
    public static final int ACTION_GIRTH_FOCUS = 13;    // stretch outrunning erect -> focus block
    public static final int ACTION_REMEASURE = 14;      // strain reads high -> confirm before acting
    public static final int ACTION_OFFER_BREAK = 15;    // t10: adds did not help -> a week off, or a block of the other track

    /* ---- t10 RULE IDS (the owner's decisions, 30 Sep - 1 Oct 2026) ----------------------- *
     *  The rule a decision carries, named once here so the lanes that decide and the card    *
     *  that renders compare the same string.                                                 */

    /** R-27 (R2): holds past the session's time-under-pressure cap become pressure instead. */
    public static final String R2_RULE =
        "time under pressure at the level's cap -> the pressure rises at the same dose instead";
    /** R-23 (C4/A4): the girth readings stayed low after an add, or the holds are at their
     *  top -> a week off, or 4 weeks of length focus. */
    public static final String YIELD_OFFER_RULE =
        "girth yield still under target after an add -> offer a week off or length focus";
    /** R-40 D1: the length reading fell under target in a block that had reached it -> the
     *  week off comes forward; no set added. */
    public static final String LENGTH_FELL_RULE =
        "length reading fell under target this block -> the week off comes forward";
    /** R-40 D3: still under target a week after a D2 set -> offer a week off or a girth
     *  block, once a block. */
    public static final String LENGTH_STILL_UNDER_RULE =
        "length reading still under target after an added set -> offer a week off or a girth block";
    /** R-45 (L1): from month 3 the strain sets rise to the table's 6. */
    public static final String LENGTH_HANDOVER_RULE =
        "month 3 hand-over -> strain sets rise to 6";
    /** R-46 (L2): the slow calendar load step. */
    public static final String LENGTH_SLOW_LOAD_RULE =
        "slow calendar load step -> +0.5 lb every 2 length training weeks";
    /** R-60 (CAP90): the step would take a both-tracks day past 90 minutes -> hold. */
    public static final String HELD_AT_90_RULE =
        "both-tracks day at 90 minutes -> volume holds";

    /** R-07 (R4): how many first work holds climb to the work when girth follows length and
     *  "Ramped first sets" is chosen (Model#R4_SETS). */
    public static final int R4_RAMP_SETS = 3;

    /** R-60 (CAP90): the minutes a both-tracks day may reach before a volume step waits
     *  (TrainerTab#heldAt90). */
    public static final int HELD_AT_90_MIN = 90;
    /** R-60 (CAP90): what one added strain set adds to the day, seconds - its hold and its
     *  rest (330 s). */
    public static final int HELD_AT_90_STRAIN_SET_SEC =
        Mint.TRACTION_STRAIN_HOLD_SEC + Mint.TRACTION_STRAIN_REST_SEC;

    /* ---- t10 LANE A: the routine build (the owner's P1/P2/P4/R1/R3/R4, 1 Oct 2026) -------- */

    /** R-01 (P2): the warm-up's length, seconds - about five minutes of reps. */
    public static final int P2_WARM_SEC = 300;
    /** R-01 (P2): where it starts, whole kPa (3.5 inHg). */
    public static final int P2_START_KPA = 12;
    /** R-01 (P2): the first rep's hold and the last's, seconds; the reps between grow evenly. */
    public static final int P2_HOLD0 = 30, P2_HOLD1 = 60;
    /** R-01 (P2): the most a rep climbs over the one before it, kPa (1.0 inHg). */
    public static final int P2_STEP_KPA = 3;
    /** R-01 (P2): the drop between reps, seconds. */
    public static final int P2_DROP_SEC = 5;
    /** R-02 (P2): a warm-up that ends under the work climbs on this much a hold, kPa. */
    public static final int P2_CARRY_KPA = 1;
    /** R-07 (R4): the ramp-in's reps - a 30 s hold and a 5 s drop. */
    public static final int R4_RAMP_HOLD_SEC = 30, R4_RAMP_DROP_SEC = 5;
    /** R-07 (R4): the ramp-in without P2 (somebody who marks): about two minutes of reps. */
    public static final int R4_RAMP_SEC = 120;
    /** R-04 (P1): no run, built or live, passes this on the whole-session clock - minutes. */
    public static final int SESSION_CAP_MIN = 120;
    /** R-08 (R3): traditional girth's rest between holds and after the fatigue block, s. */
    public static final int TRAD_REST_SEC = 30;
    /** R-09 (A6): the feeder's days, in the words the Trainer and Up next use. */
    public static final String FEEDER_DAYS_WORDS =
        "Feeder: girth days only, 4–6 h after the girth session";

    // §11 provenance tags.
    public static final int TAG_SOURCE = 0;    // stated in source, untagged
    public static final int TAG_INFERRED = 1;  // [INF]
    public static final int TAG_ADAPTED = 2;   // [ADAPTED]
    public static final int TAG_DERIVED = 3;   // [DERIVED]

    /* ===================================================================== *
     *  DEBOUNCE / CADENCE / GATE CONSTANTS                                   *
     * ===================================================================== */

    /** Yield debounce — never act on a single reading; only 3 consecutive tracked
     *  sessions below target trigger a change (the guidance). */
    public static final int YIELD_DEBOUNCE = 3;

    /** Deload is usage-linked: due after N accumulated TRAINING weeks since the last
     *  deload, NOT the calendar-4th week (plan round-2 ruling; inactivity never burns a
     *  deload). The guide's own §5 L1 table (deloads at wks 5/9/13/17) makes the FIRST
     *  cycle 4 training weeks (1,2,3,4 -> deload) and every SUBSEQUENT cycle 3 (6-8 -> 9,
     *  etc.). Reproduces the table exactly when the user trains every week, and correctly
     *  delays when they skip. */
    public static final int DELOAD_FIRST_AFTER_TRAINING_WEEKS = 4;
    public static final int DELOAD_AFTER_TRAINING_WEEKS = 3;   // the middle cycles only

    /**
     * ...AND THEN BACK TO FOUR. The three-week cadence is what the dated table prints, and
     * the table stops printing at week 17: deloads at weeks 5, 9, 13 and 17. Past that the
     * same table moves months 4-6 to a deload every four weeks, and both girth routines in
     * the guidance ask for a week off every four weeks with no qualification at all.
     * Keeping three for ever was more rest than any part of the guidance asks for at that
     * stage.
     *
     * Week 17 is month index 4 (weeks/4), which is where the table's last deload falls and
     * where the months 4-6 wording takes over.
     */
    public static final int DELOAD_BACK_TO_FOUR_MONTH = 4;

    /** A training week = a calendar week with ≥3 logged plan sessions, counted in DAYS,
     *  not sessions (plan misuse rules; the guidance: 3–5 days/week) - rule (a) of
     *  {@link TrainingWeek}, which every reader of the week count asks. */
    public static final int TRAINING_WEEK_MIN_DAYS = 3;

    /** ...OR (the owner's decision of 2026-10-03, option B) at least this many days that ran
     *  which together delivered this many sessions' worth of the track's plan - 2/3 of a
     *  3-day week's volume. A 2 + 2 four-day week counts for both tracks when each session is
     *  full; one session never counts, whatever its length. NOT IN THE GUIDANCE: it asks for
     *  three to five days a week; counting by volume is the owner's choice (TrainingWeek). */
    public static final int TRAINING_WEEK_FULL_SESSIONS = 2;

    /** Layoff step-back trigger — ≥7 elapsed days with no plan session (plan misuse rule;
     *  the guidance: miss ≥1 wk → step back 1 wk (2 to be sure)). */
    public static final long LAYOFF_MS = 7L * 24L * 60L * 60L * 1000L;

    /**
     * WHAT MAY BE REPORTED AS A DELOAD, in whole days.
     *
     * THREE AT LEAST, because two days off costs nothing: the miss policy only repeats a week
     * at three missed sessions and only steps back on a week with no training in it at all,
     * so a shorter rest has nothing for a report to put right.
     *
     * TWENTY-ONE AT MOST — the guidance's planned breaks are one to three weeks. Past that
     * the guidance stops calling it a deload and starts calling it a break, which costs strength
     * adaptation and wants the recalibrate path rather than a week's forgiveness.
     *
     * AND IT MUST BE RECENT. Four weeks back is as far as the plan's own week arithmetic
     * still reaches; older than that, nothing the report would change is still in play.
     */
    public static final int DELOAD_REPORT_MIN_DAYS = 3;
    public static final int DELOAD_REPORT_MAX_DAYS = 21;
    public static final int DELOAD_REPORT_MAX_AGE_DAYS = 28;

    /** How many missed-week charges the ledger remembers. Twelve weeks is three months, past
     *  which a report can no longer reach them anyway ({@link #DELOAD_REPORT_MAX_AGE_DAYS}). */
    public static final int MISS_CHARGES_KEPT = 12;

    /**
     * THE PETECHIAE REDUCTIONS, in inHg under the working pressure.
     *
     * THE RETURN TAPER is modelled on a rule that someone back after more than a week away
     * should pump about 4 hg under their last working pressure for the full set - followed
     * by a second, smaller step rather than a jump straight back, because a jump is the same
     * too-much-pressure-too-quickly the red-dots section blames in the first place. One step
     * per TRAINING DAY, so two sessions on one day share a pressure.
     * S20: that rule could not be found in the guidance, unlike this file's own header
     * table's constants (each checked against the guidance) - treat it as this codebase's
     * synthesis, not a verified citation, until the guidance turns out to state it.
     *
     * BIG_CYLINDER_HG is the guidance for a tube a size up, for as long as it is the one in
     * use. The two are NOT added together: a return session in a larger cylinder is 4 hg
     * under, not 6.
     *
     * THE TAPER APPLIES TO ANYBODY WHO REPORTS A DELOAD, because reporting it is the consent
     * — the same reasoning the accepted oversize reduction already follows. Only the
     * AUTOMATIC arming, off a detected week away, still waits for Model#marksEasily.
     */
    private static final double[] RETURN_TAPER = { 4.0, 2.0 };

    /** DERIVED FROM THE TABLE, never written twice. Deload#finished and Deload#onFiled branch
     *  on this while {@link #returnTaperHg} bounds on the array, so a third cut added to the
     *  table with a hand-written 2 left here would silently skip the last step - the taper
     *  would close one day early and nothing would say so. */
    public static final int RETURN_TAPER_STEPS = RETURN_TAPER.length;
    public static final double BIG_CYLINDER_HG = 2.0;

    /** The cut for a taper step; 0 for any step past the end of the taper. */
    public static double returnTaperHg(int step) {
        return (step >= 0 && step < RETURN_TAPER.length) ? RETURN_TAPER[step] : 0.0;
    }

    /**
     * HOW HARD THE PUMP PULLS ON A REDUCED DAY, as a percentage — the owner's figure
     * (2026-09-15). The guidance names pumping up too fast as a cause of the red dots the
     * return is avoiding, so a gentle day comes down in speed as well as in pressure. Slower
     * than the work speed ({@link Mint#POWER_PCT}, 75) and above the prime warm-up's 50.
     */
    public static final int RETURN_POWER_PCT = 60;

    /** Pressure raises every 3 training weeks at the current pressure (the guidance). */
    public static final int PRESSURE_RAISE_EVERY_TRAINING_WEEKS = 3;

    /** L1→L2 gate Net-TUP threshold — 20 min (the guidance: net_tup >= 20). */
    public static final double L1_GATE_NET_MIN = 20.0;
    /** L1→L2 gate pressure — 8 hg (the guidance: AND pressure == 8). */
    public static final double L1_GATE_PRESSURE_HG = 8.0;
    /** L2→L3 gate — month 6, achieved volume carries (the guidance). */
    public static final int L2_GATE_MONTH = 6;
    /** L3→L4 gate — month 12, then option A or C (the guidance). */
    public static final int L3_GATE_MONTH = 12;

    /** Numbness safety flag needs a 7-day off period ELAPSED before it can clear
     *  (the guidance: dulled sensitivity/numbness — 1 wk off; plan round-3 ruling). */
    public static final long NUMBNESS_OFF_MS = 7L * 24L * 60L * 60L * 1000L;

    /** Fatigue block (L3+) is 10 sets × 30–60 s, counted separately — it does NOT feed the
     *  Net-TUP gate metric (the guidance). {@link #fatigueBlockPresent} exposes which levels
     *  carry it so the caller's per-frame pass can exclude those frames (plan round-3). */
    public static final int FATIGUE_BLOCK_SETS = 10;

    /* ===================================================================== *
     *  WEEK TABLE — one girth table row (the guidance). REFERENCE DATA the    *
     *  UI displays and the self-test pins; the CALENDAR arithmetic in §9     *
     *  reproduces it. Pressure is stored as an hg lo/hi range (many weeks    *
     *  give a range like 5–6 or 8–9); a single value sets lo == hi.          *
     * ===================================================================== */
    public static final class Week {
        public final int num;          // week number within the level's span
        public final boolean deload;   // true -> DELOAD week (sets/net/pressure meaningless)
        public final int sets;         // planned interval sets (at 2-min holds, §DERIVED)
        public final double netTupMin; // planned Net TUP, minutes
        public final double pressHgLo; // prescribed pressure low bound, hg
        public final double pressHgHi; // prescribed pressure high bound, hg
        public final String event;     // the table's own Event cell (may be "")

        Week(int num, boolean deload, int sets, double netTupMin,
             double pressHgLo, double pressHgHi, String event) {
            this.num = num; this.deload = deload; this.sets = sets;
            this.netTupMin = netTupMin; this.pressHgLo = pressHgLo;
            this.pressHgHi = pressHgHi; this.event = event;
        }
        /** Prescribed pressure high bound in kPa — the value the gate's in-band check reads. */
        public double pressHiKpa() { return pressHgHi * HG; }
    }

    private static Week train(int n, int sets, double net, double plo, double phi, String ev) {
        return new Week(n, false, sets, net, plo, phi, ev);
    }
    private static Week deload(int n, String ev) {
        return new Week(n, true, 0, 0.0, 0.0, 0.0, ev);
    }

    /** Girth INTERVAL, Level 1 · weeks 1–17 · calendar engine · floor 5 hg (the guidance).
     *  Deload rows sit where the guide printed them (wks 5,9,13,17); the ENGINE's own
     *  deload cadence is usage-linked (see {@link #deloadDue}) — the table is reference. */
    public static final Week[] GIRTH_INTERVAL_L1 = new Week[] {
        train (1,  5, 10, 5, 5,   "Start"),
        train (2,  5, 10, 5, 5,   ""),
        train (3,  6, 12, 5, 6,   "+1 set (14-day cadence)"),
        train (4,  6, 12, 6, 6,   "month-1 ceiling"),
        deload(5,  "counters frozen"),
        train (6,  7, 14, 6, 6,   ""),
        train (7,  7, 14, 6, 7,   ""),
        train (8,  8, 16, 7, 7,   ""),
        deload(9,  ""),
        train (10, 9, 18, 7, 7,   "yield tracking begins"),
        train (11, 9, 18, 7, 7,   "~3% ref"),
        train (12, 10, 20, 7, 7,  "Net TUP milestone -> pressure phase"),
        deload(13, ""),
        train (14, 10, 20, 8, 8,  "+1 hg after 3 training wks at 7"),
        train (15, 10, 20, 8, 8,  ""),
        train (16, 10, 20, 8, 8,  "GATE: Net 20 AND 8 hg -> L2"),
        deload(17, "enter Level 2"),
    };

    /**
     * THE LAST WEEK OF LEVEL 1'S VOLUME PHASE - the first table row whose session reaches the
     * Level 1 milestone (week 12, "Net TUP milestone -> pressure phase"). DERIVED FROM THE
     * TABLE, never written twice. Up to and including this row the working pressure follows
     * the table's own pressure ({@link #l1TablePressureKpa}); after it the pressure phase's
     * +1 hg per 3 counting weeks takes over.
     */
    public static final int L1_VOLUME_PHASE_LAST_WEEK = firstRowReaching(GIRTH_INTERVAL_L1, 20.0);

    private static int firstRowReaching(Week[] table, double netMin) {
        for (int i = 0; i < table.length; i++)
            if (!table[i].deload && table[i].netTupMin >= netMin) return i + 1;
        return table.length;
    }

    /**
     * THE PRESSURE THE GUIDANCE'S LEVEL 1 TABLE GIVES AT A POSITION, in kPa - the row's lower
     * bound (5, 5, 5, 6, 6, 6, 7, ...), a deload row reading the training row before it. NaN
     * once the position is past the volume phase ({@link #L1_VOLUME_PHASE_LAST_WEEK}): from
     * there the pressure phase's own rule decides.
     */
    public static double l1TablePressureKpa(int weekIndex) {
        int w = Math.max(1, weekIndex);
        if (w > L1_VOLUME_PHASE_LAST_WEEK) return Double.NaN;
        for (int i = w - 1; i >= 0; i--)
            if (!GIRTH_INTERVAL_L1[i].deload) return GIRTH_INTERVAL_L1[i].pressHgLo * HG;
        return GIRTH_INTERVAL_L1[0].pressHgLo * HG;
    }

    /**
     * HOW CLOSE COUNTS AS AT THE TABLE'S PRESSURE, in kPa (the owner's decision, 2026-09-27:
     * 6.8 hg counts as the Level 1 table's 7 hg). A prescription lives in whole kPa and the
     * table in whole hg (3.39 kPa), so a table pressure usually sits between two whole kPa;
     * the whole kPa just below it is as close as a whole-kPa prescription gets from below.
     */
    public static final double TABLE_REACHED_WITHIN_KPA = 1.0;

    /**
     * THE RULE: a table pressure is reached when the working pressure is less than
     * {@link #TABLE_REACHED_WITHIN_KPA} (one whole kPa) below it - or at or above it. 23 kPa
     * has reached 7 hg (23.7), 20 kPa has reached 6 hg (20.3), 22 kPa has not reached 7 hg
     * (1.7 short). Only the Level 1 table's lead uses it; the cap, the device ceiling and the
     * L1 -> L2 gate's 8 hg ({@link #pressureReachedHg}) keep their own rules.
     */
    public static boolean tablePressureReached(double pressureKpa, double targetKpa) {
        return targetKpa - pressureKpa < TABLE_REACHED_WITHIN_KPA - 1e-9;
    }

    /** Girth INTERVAL, Level 2 · weeks 18–32 · calendar + yield modifier · floor 8 hg ·
     *  target 4–6% (the guidance).
     *
     *  TO 30 MINUTES (the owner's decision, 2026-09-27). The guidance builds Level 2 by one
     *  interval about every 14 days until a session holds 30 minutes at pressure, and raises
     *  the pressure 1 hg every 3 weeks towards 10 hg once it holds 20. The table used to stop
     *  at week 26 (13 sets, 26 minutes); rows 27-32 carry the same rule on: +1 set every two
     *  training weeks to 15 sets (30 minutes), a deload row every fourth week (29) where the
     *  table already places them (21, 25), and the pressure column stepping 8 -> 9 -> 10 hg,
     *  three training weeks apart. The pressure column is reference only - the working
     *  pressure moves by the raise rule and the caps, never by this column. The 3-minute rest
     *  after every 5 sets is unchanged (15 sets = three blocks of 5). */
    public static final Week[] GIRTH_INTERVAL_L2 = new Week[] {
        train (18, 10, 20, 8, 8,  "rest 3-5 min every 5 sets begins"),
        train (19, 10, 20, 8, 8,  ""),        // guide prints 20–21; lo bound stored
        train (20, 11, 22, 8, 8,  "+1 set / 14 days"),
        deload(21, ""),
        train (22, 11, 22, 8, 8,  ""),
        train (23, 12, 24, 8, 8,  ""),
        train (24, 12, 24, 8, 8,  ""),
        deload(25, ""),
        train (26, 13, 26, 8, 9,  "month-6 gate approaching"),
        train (27, 13, 26, 9, 9,  ""),
        train (28, 14, 28, 9, 9,  ""),
        deload(29, ""),
        train (30, 14, 28, 9, 9,  ""),
        train (31, 15, 30, 9, 10, "30 min reached - no more sets"),
        train (32, 15, 30, 10, 10, "GATE: month 6 AND 30 min -> L3"),
    };

    /**
     * THE VOLUME LEVEL 2 IS LEFT WITH (the owner's decisions, 2026-09-27: Levels 3 and 4 need
     * volume, not just the calendar; Level 2's table runs on to 30 minutes and Level 2 is left
     * at 30) - the minutes at pressure of the Level 2 table's last training row, 30 (15 sets),
     * the figure where the guidance's Level 2 additions stop. DERIVED FROM THE TABLE, never
     * written twice: a gate the table cannot reach would hold everybody whose yield is on
     * target at Level 2 for good, so the gate and the table's top are one number.
     */
    public static final double L2_EXIT_NET_MIN = lastTrainingRowNet(GIRTH_INTERVAL_L2);

    private static double lastTrainingRowNet(Week[] table) {
        for (int i = table.length - 1; i >= 0; i--)
            if (!table[i].deload) return table[i].netTupMin;
        return 0.0;
    }

    /** Girth TRADITIONAL's first week at Level 1 (the owner's decision, 0.10 - the guidance's
     *  long-hold path): 3 holds of 5 min, 15 min, start 5-7 hg. From here the holds grow by
     *  the week at the level ({@link #traditionalSets}); pressure steps as the plan's own rule
     *  says. Stored as a single reference row. */
    public static final Week TRADITIONAL_START =
        train(1, 3, 15, 5, 7, "traditional start: 3 x 5 min, one more every 4 weeks to 6");

    /* ---- TRADITIONAL GIRTH GROWS AS THE GUIDANCE DOES (the owner's decision, 0.10, final) ----
     *
     * The guidance's long-hold path grows: Level 1 starts at three 5-minute holds and adds one
     * every 28 days at the level until six (30 minutes); Level 2 runs six; Level 3 puts the
     * fatigue intervals first and grows from six by one every 28 days to eight (40 minutes);
     * Level 4 runs eight. The app used to keep two holds at every level, growing by yield alone.
     *
     * THE WEEKS ARE THE PLAN'S OWN COUNT: the track's week position at its level, advanced as
     * the interval table's is - one per QUALIFYING training week (three trained days on the
     * track, TrainerTab#accumulatedTrainingWeeks) since the level's anchor, less the weeks the
     * miss policy repeats - so four counted weeks are the 28 days of training a hold waits on.
     * Hold length (5 min), rests, pressure steps, gates, yield and deloads are unchanged, and
     * yield stays on top of this count. The interval track's hybrid is not touched. */

    /** Level 1 traditional: the holds its first week runs. */
    public static final int TRAD_L1_START_SETS = 3;
    /** ...and the most the week adds up to (30 minutes). */
    public static final int TRAD_L1_TOP_SETS = 6;
    /** Level 2 traditional: flat. */
    public static final int TRAD_L2_SETS = 6;
    /** Level 3 traditional, after the fatigue block: its first week... */
    public static final int TRAD_L3_START_SETS = 6;
    /** ...growing to this (35 minutes). R-25 (A3, the owner's decision, 2026-10-01): the
     *  guidance's growth went on to 8; the owner's top for Level 3 is 7 - the interval top's
     *  minutes under pressure (14 x 2 min + the 7.5-minute fatigue block) in 5-minute holds,
     *  round(35.5 / 5) - and the top stops the calendar too (K17). */
    public static final int TRAD_L3_TOP_SETS = 7;
    /** Level 4 traditional: flat; more only from yield, up to {@link #TRAD_L4_TOP_SETS}. */
    public static final int TRAD_L4_SETS = 8;
    /** R-25 (A3): Level 4's top - round((18 x 2 min + 7.5) / 5) = 9 holds. */
    public static final int TRAD_L4_TOP_SETS = 9;

    /** R-25 (A3): THE MOST HOLDS TRADITIONAL GIRTH RUNS AT `level` - 6 at Levels 1 and 2, 7 at
     *  Level 3, 9 at Level 4. Every growth stops here: the calendar, the yield steps and the
     *  no-readings fallback. */
    public static int traditionalTop(int level) {
        if (level <= L2) return TRAD_L1_TOP_SETS;
        if (level == L3) return TRAD_L3_TOP_SETS;
        return TRAD_L4_TOP_SETS;
    }

    /** R-25 (C7): the hybrid's own five-minute holds at Level 3 and Level 4 - the guidance's
     *  counts - and the most yield and the fallback may take them to. */
    public static final int HYBRID_L3_HOLDS = 6, HYBRID_L4_HOLDS = 8;
    public static final int HYBRID_TOP_HOLDS = 8;

    /** R-25: the hybrid's holds at `level` with `hybridYield` kept
     *  (TrainerTrackState#hybridYield): the level's count plus the kept holds, never past the
     *  top and never under the level's count. */
    public static int hybridHolds(int level, int hybridYield) {
        int base = level >= L4 ? HYBRID_L4_HOLDS : HYBRID_L3_HOLDS;
        return Math.max(base, Math.min(base + hybridYield, HYBRID_TOP_HOLDS));
    }
    /** Counted training weeks at the level for each hold added: 28 days of training. */
    public static final int TRAD_WEEKS_PER_HOLD = 4;

    /** Traditional girth's holds at `level`, week `weekIndex` of it (1-based; 0 reads as 1):
     *  3 growing by one every {@link #TRAD_WEEKS_PER_HOLD} weeks to 6 at Level 1, 6 at Level 2,
     *  6 growing to 7 at Level 3 (R-25: the owner's top stops the calendar), 8 at Level 4. */
    public static int traditionalSets(int level, int weekIndex) {
        int grown = (Math.max(1, weekIndex) - 1) / TRAD_WEEKS_PER_HOLD;
        if (level <= L1) return Math.min(TRAD_L1_TOP_SETS, TRAD_L1_START_SETS + grown);
        if (level == L2) return TRAD_L2_SETS;
        if (level == L3) return Math.min(TRAD_L3_TOP_SETS, TRAD_L3_START_SETS + grown);
        return TRAD_L4_SETS;
    }

    /** The last week position at `level` that adds a hold - 13 at Level 1, 5 at Level 3 - and
     *  1 for a level that does not grow: where the position stops advancing. */
    public static int traditionalTopWeek(int level) {
        if (level <= L1) return 1 + (TRAD_L1_TOP_SETS - TRAD_L1_START_SETS) * TRAD_WEEKS_PER_HOLD;
        if (level == L3) return 1 + (TRAD_L3_TOP_SETS - TRAD_L3_START_SETS) * TRAD_WEEKS_PER_HOLD;
        return 1;
    }

    /** The most holds `level`'s growth reaches: 6 at Levels 1 and 2, 7 at Level 3, 8 at
     *  Level 4 (whose 9 comes from yield or the fallback, {@link #traditionalTop}). */
    public static int traditionalTopSets(int level) {
        return traditionalSets(level, traditionalTopWeek(level));
    }

    /* ---- THE HALF START (the owner's decision, 0.10, final) --------------------------------
     *
     * Somebody whose traditional routine predates the growth ran the old flat rule's 2 holds
     * (plus any yield sets it kept) at every level. At Level 1 the growth itself starts at 3, so
     * they follow it from the first week as everybody does. Above Level 1 the level's count is 6
     * or 8 - three or four times what they ran - so they BUILD UP to it instead: from half the
     * level's count (3 at Level 2, 4 at Levels 3 and 4), or from what they run now if that is
     * more, adding one hold every TRAD_BUILD_WEEKS_PER_HOLD counted weeks (the same count the
     * growth uses) until they reach the level's count at that point; then the level's normal
     * growth. Level gates never read the hold count, so the build-up does not move them. */

    /** What the old flat rule built at every level, before the kept yield sets. */
    public static final int TRAD_OLD_SETS = 2;
    /** Counted training weeks for each hold a build-up adds. */
    public static final int TRAD_BUILD_WEEKS_PER_HOLD = 2;

    /** Where a build-up at `level` starts at the least: half the level's count (3 at Level 2,
     *  4 at Levels 3 and 4 - half of the 8 the guidance's Level 3 grows to). Unchanged by
     *  R-25's top of 7 at Level 3, which stops the growth and not the half start. */
    public static int traditionalHalfStart(int level) {
        if (level >= L3) return TRAD_L4_SETS / 2;
        return traditionalTopSets(level) / 2;
    }

    /* ---- LENGTH spec (the guidance) — static, no progression, no yield gate ---- */

    /** Length L-A interval (default): 5 × 120 s, rest none, 10 min Net, pressure M1 ≤6 hg
     *  then +≈1 hg/month to a soft cap 8–10 hg (the guidance; creep is [INF], cap [ADAPTED]). */
    public static final int LENGTH_A_SETS = 5;
    public static final double LENGTH_A_NET_MIN = 10.0;
    public static final double LENGTH_MONTH1_CAP_KPA = MONTH1_CAP_KPA;   // ≤6 hg first month
    public static final double LENGTH_SOFT_CAP_LO_KPA = 8.0 * HG;        // soft cap 8..10 hg
    public static final double LENGTH_SOFT_CAP_HI_KPA = 10.0 * HG;
    /** Length L-B traditional: 2 × 5 min, rest 120 s, 10 min Net, same pressure rule. */
    public static final int LENGTH_B_SETS = 2;

    /* ---- LENGTH, the traction premise (trainer-v3 Phase 2) ------------------------- *
     *  A length session PULLS: the cylinder is a piston, and what the tissue feels is a
     *  LOAD in pounds, not a pressure. The pressure is merely how the load is produced,
     *  which is why every figure below is in pounds and the conversion lives in one place
     *  ({@link Traction}). The girth engine's own numbers are untouched by all of this.    */

    /** Below this month the length track progresses by CALENDAR alone. The metrics that
     *  drive the ladder (strain, fatigue) are differences between measurements, and three
     *  months is the earliest they say anything a week-to-week wobble does not. */
    public static final int LENGTH_METRICS_FROM_MONTH = 3;

    /** The strain window, percent - one after-session reading against the session's own
     *  before-reading (t10 R-40, option D; the owner's A2: 2-4 % green, 4-6 % fine). Under
     *  {@link #LENGTH_STRAIN_LO} for a week the ladder asks what this block's readings did
     *  (fell / never reached / still under); over {@link #LENGTH_STRAIN_HI} it asked too
     *  much - but that reading is CONFIRMED before anything is cut, because a single
     *  mis-measure at the high end would otherwise walk the load down on its own. */
    public static final double LENGTH_STRAIN_LO = 2.0;
    public static final double LENGTH_STRAIN_HI = 6.0;

    /** The old fatigue floor, percent. NO LONGER A RUNG (t10 R-40, K7): option D's "fell"
     *  branch replaced the 21-day fatigue deload. Kept for the band the Trainer page draws
     *  under the fatigue figure, which is a picture, not a decision. */
    public static final double LENGTH_FATIGUE_MIN = 2.0;

    /** A1 (the owner, 1 Oct 2026): a cut made on CONFIRMED high strain never takes the load
     *  under five pounds. The other paths keep the absolute minimum (Scale#LOAD_MIN_LB); a
     *  load already under this (a 2-month starter's 3 lb) is not cut at all. */
    public static final double LENGTH_LOAD_FLOOR_LB = 5.0;
    /** C10: at most one confirmed-high cut in this many days. */
    public static final int LENGTH_CUT_GAP_DAYS = 7;
    /** After this many cuts in a row the card asks the person to check for slippage and
     *  how they measure. */
    public static final int LENGTH_CUTS_CHECK = 2;
    /** L1 (R-45): the strain sets from month index 3 - the guidance's months 4-12 table. */
    public static final int LENGTH_HANDOVER_SETS = 6;
    /** L3 (R-47): the no-readings fallback adds strain sets only up to here; past it, sets
     *  need readings. */
    public static final int NO_READINGS_LENGTH_TOP = 6;
    /** L2 (R-46): the slow calendar load step aims at {@link #LENGTH_LOAD_L1_TARGET_LB} until
     *  this month index, then at {@link #LENGTH_LOAD_MAX_LB}. */
    public static final int LENGTH_SLOW_LOAD_FULL_MONTH = 6;

    /* ---- t10 LENGTH RULE IDS AND WORDS (lane C; the owner's decisions, 30 Sep - 1 Oct) --- *
     *  The ids the cards compare, beside D0's (LENGTH_FELL_RULE ...), and the words the cards *
     *  say. A reason a card prints with a full stop after it ends without one.               */

    /** R-40 D2: under target for a week and no reading reached it since the last deload ->
     *  +1 strain set (then +0.5 lb at 12 sets). An accepted D2 step marks the block
     *  (TrainerTrackState#dAddedInBlock) so a week more under target is D3's offer. */
    public static final String LENGTH_NEVER_RULE =
        "length reading never reached target since the last deload -> +1 strain set";
    /** R-40: one reading over the window - measure again, nothing cut. */
    public static final String LENGTH_HIGH_RULE =
        "length reading over target -> measure again before anything is cut";
    /** R-40 / C10 / A1: two readings over the window since the work last changed -> -0.5 lb,
     *  one cut in 7 days, never under 5 lb. */
    public static final String LENGTH_CUT_RULE =
        "length reading over target twice -> -0.5 lb (one cut in 7 days, floor 5 lb)";
    /** R-41: confirmed high at the 5 lb floor - measure again, rest if it stays high. */
    public static final String LENGTH_FLOOR_RULE =
        "length reading over target at the 5 lb floor -> measure again, rest if it stays high";
    /** C10: confirmed high again inside 7 days of a cut - hold. */
    public static final String LENGTH_CUT_WAIT_RULE =
        "length reading over target again within 7 days of a cut -> hold";
    /** t10 REAL-11: the load moved since the last length session - the next load step waits
     *  for a session at it (one load change between two sessions). */
    public static final String LENGTH_LOAD_WAITS_RULE =
        "length load moved since the last length session -> the next load step waits for one";
    /** t10 parity run 2, A-3 (R-46): the length work already changed this morning - its level,
     *  load, strain sets or pressure - so the next change waits for the next length morning. */
    public static final String LENGTH_ONE_CHANGE_RULE =
        "length changed this morning -> one change a morning, the next waits for the next one";
    /** t10 parity run 2, O-1: the plan already changed the girth work this morning - one change
     *  a girth morning, the next waits for the next one. */
    public static final String GIRTH_ONE_CHANGE_RULE =
        "girth changed this morning -> one change a morning, the next waits for the next one";
    /** R-44: the girth-focus block holds the length ladder. */
    public static final String LENGTH_FOCUS_HOLD_RULE =
        "girth-focus block -> the length ladder holds until it ends";
    /** R-23 (the merge's open item 5): girth rests for the offer's 4 weeks of length focus
     *  (TrainerTrackState#restUntilMs on the girth track) - the girth ladder holds. */
    public static final String GIRTH_REST_HOLD_RULE =
        "4 weeks of length focus -> the girth plan holds until they end";
    /** R-47: the no-readings fallback at its top. */
    public static final String LENGTH_NO_READINGS_TOP_RULE =
        "no length readings for " + Plan.NO_READINGS_WEEKS
        + " training weeks, strain sets at the fallback's top";

    /** R-40 D1, the week off brought forward (`nextAnyway` false) or already next. */
    public static String lengthFellWords(boolean nextAnyway) {
        // Polish TR-31: a sentence, not engine shorthand ("…this block: it fell").
        return "Your length reading stayed under " + pctSp(LENGTH_STRAIN_LO) + " for a week, "
            + "after reaching " + pctSp(LENGTH_STRAIN_LO) + " earlier in this block. "
            + (nextAnyway ? "The week off is next anyway; no set is added"
                          : "The week off starts tomorrow; no set is added");
    }

    /** R-40 D2, a strain set. */
    public static String lengthNeverWords(int sets) {
        return "Length under " + pctSp(LENGTH_STRAIN_LO) + " since the last week off. Add a "
            + "strain set: " + sets + " → " + (sets + 1);
    }

    /** R-40 D2 with the sets at their top: the load step. */
    public static String lengthNeverLoadWords(double fromLb, double toLb) {
        return "Length under " + pctSp(LENGTH_STRAIN_LO) + " since the last week off, with the "
            + "strain sets at " + LENGTH_STRAIN_SETS_MAX + ": add "
            + Traction.settingLb(LENGTH_LOAD_STEP_LB) + " (" + Traction.settingLb(fromLb)
            + " → " + Traction.settingLb(toLb) + ")";
    }

    /** R-40 D3: the offer, once a block. */
    /** Polish: the three buttons ask the question, so the sentence does not. */
    public static final String LENGTH_STILL_UNDER_WORDS = "Still under 2% a week after the "
        + "added set.";

    /** R-40: one high reading. */
    public static final String LENGTH_HIGH_WORDS = "Over 6% after the session: measure again.";

    /** R-40: a confirmed high - the cut it proposes. */
    public static String lengthCutWords(double toLb) {
        return "Over 6% again: lower the load to " + Traction.settingLb(toLb) + "?";
    }

    /** R-41: a confirmed high with the load at (or under) its floor. */
    public static String lengthFloorWords() {
        return "Load is at its " + Traction.settingLb(LENGTH_LOAD_FLOOR_LB) + " floor: measure "
            + "again, and rest if it stays high.";
    }

    /** C10: a confirmed high again within a week of a cut. */
    public static final String LENGTH_CUT_WAIT_WORDS = "Over 6% again, and the load came "
        + "down less than a week ago: it holds this week. Measure again.";

    /** After {@link #LENGTH_CUTS_CHECK} cuts in a row: what the card adds. */
    public static final String LENGTH_CUTS_CHECK_WORDS = "That is two cuts in a row: check the "
        + "cylinder is not slipping, and how you measure.";

    /** R-45: the month-3 hand-over. */
    public static String lengthHandoverWords(int sets) {
        return "From your fourth month the plan runs " + LENGTH_HANDOVER_SETS
            + " strain sets, the guidance's table: " + sets + " to " + LENGTH_HANDOVER_SETS;
    }

    /** R-46: the slow calendar load step. */
    public static String lengthSlowLoadWords(double fromLb, double toLb, double targetLb) {
        // Said by the setting's own name (Y-2: "Small steps over time").
        return "Small steps over time: " + Traction.settingLb(fromLb) + " to "
            + Traction.settingLb(toLb) + ", on the way to " + Traction.settingLb(targetLb);
    }

    /** R-47: the length no-readings fallback's card. */
    public static String lengthNoReadingsWords() {
        return "No length readings for " + NO_READINGS_WEEKS + " weeks: add a strain set (up to "
            + NO_READINGS_LENGTH_TOP + "). Measuring lets the plan adjust to you";
    }

    /** R-47: ...and at its top. */
    public static String lengthNoReadingsTopWords() {
        // Polish TR-32: a sentence, not a log line ("…needs readings - hold").
        return "At " + NO_READINGS_LENGTH_TOP + " strain sets the plan needs a reading to go "
            + "further: past " + NO_READINGS_LENGTH_TOP + ", the plan needs readings";
    }

    /** R-43 / A9: what the climb card adds, once, when the pull passes 12 lb before month 12. */
    public static String lengthPast12Words() {
        return "This passes " + Traction.settingLb(LENGTH_LOAD_MAX_LB) + " before month 12. Your "
            + "call: the plan never goes past " + Traction.settingLb(Scale.LOAD_HARD_MAX_LB) + ".";
    }

    /** "2 %" - a percentage as the t10 length words write it. */
    private static String pctSp(double v) {
        // No space before the sign (polish SYS-13): "2%", as every other percent is written.
        return pct(v);
    }

    /** A low strain reading must persist this many days before volume moves - the same
     *  debounce discipline the girth engine keeps, in days because length measurements are
     *  not per-session. */
    public static final int LENGTH_MISS_DEBOUNCE_DAYS = 7;

    /** Volume before load, again: sets rise to this cap before a single pound is added. */
    public static final int LENGTH_STRAIN_SETS_MAX = 12;

    /** The load ladder, pounds. Start at 2.5, step 0.5, month one held at 4, L1 aiming at 5.
     *  The two caps are NOT restated here - they are {@link Traction}'s, re-exported so a
     *  reader finds them under the name the ladder uses while there stays exactly one place
     *  a cap can be changed. */
    /** How many QUALIFYING TRAINING WEEKS earn one load step, and one strain set, while the
     *  calendar is driving. Training weeks rather than wall-clock ones, for the same reason
     *  the deload cadence counts them: a fortnight nobody trained in has not earned a heavier
     *  pull, and this codebase's rule is already that inactivity does not burn a cadence. */
    public static final int LENGTH_LOAD_STEP_WEEKS = 2;
    public static final int LENGTH_STRAIN_ADD_WEEKS = 3;

    public static final double LENGTH_LOAD_START_LB = 2.5;
    public static final double LENGTH_LOAD_M1_MAX_LB = 4.0;
    public static final double LENGTH_LOAD_STEP_LB = 0.5;
    public static final double LENGTH_LOAD_L1_TARGET_LB = 5.0;
    public static final double LENGTH_LOAD_MAX_LB = Traction.LOAD_MAX_LB;
    public static final double LENGTH_LOAD_ABS_LB = Traction.LOAD_ABS_LB;

    /** How long stretched length may outrun erect length before the plan says so. Six weeks
     *  of one rising while the other does not is not noise; it is the tissue lengthening
     *  without filling, which is a girth answer to a length observation. */
    public static final int DIVERGENCE_WEEKS = 6;

    /** How long a girth-focus block runs. The spec's "1-3 months"; eight weeks is the middle
     *  of it, and a single number is what lets the block state its own end date on the card
     *  that proposes it. */
    /**
     * THE LENGTH BREAK, IN WEEKS - the fork's third arm at the month-12 crossing.
     *
     * The guide says four to six. The DATE is set at the near end deliberately: the app
     * stops holding the track back after four weeks and offers it again, and anybody who
     * wants the far end simply does not run - which is a decision the app has no business
     * making for them. Dating it at six would be the app deciding somebody rests longer
     * than the guide's own minimum on its own authority.
     */
    public static final int LENGTH_BREAK_WEEKS = 4;

    public static final int GIRTH_FOCUS_WEEKS = 8;

    /**
     * WHAT A GIRTH-FOCUS BLOCK IS SCORED AGAINST - the spec's own "6-8 % girth expansion".
     *
     * Fixed, and deliberately not read off either track's level. The block exists because
     * stretched length is outrunning erect, so the question it answers is whether the tissue
     * FILLED - and that question has one answer, not one per level. Reading the girth track's
     * current window would also make the score depend on a level the block has nothing to do
     * with, and reading the LENGTH level's would ask a girth question of a length number.
     */
    public static final double GIRTH_FOCUS_YIELD_LO = 6.0;
    public static final double GIRTH_FOCUS_YIELD_HI = 8.0;

    /** SelfTest pins this against the L3/L4 window, which is where the spec's figures come
     *  from - so if that window ever moves, the disagreement is visible rather than silent. */
    public static boolean focusBlockScored(double pct) {
        return !Double.isNaN(pct) && pct >= GIRTH_FOCUS_YIELD_LO;
    }

    /** TRACTION HYGIENE. A beginner comes out of the tube every half hour, whatever the
     *  session still has left to run - long unbroken traction is how a numb hour happens.
     *  An ADVISORY, never a stop: the plan says so once and the person decides. */
    public static final int TRACTION_OUT_EVERY_SEC = 1800;

    /** And the question that gets asked AFTER one: numbness that has not gone in forty
     *  minutes is not the ordinary post-session dullness, and the guide treats it as the
     *  flag it is. Forty minutes rather than an hour because it is the figure the source
     *  uses, and rounding a safety threshold up is not a rounding anyone is entitled to. */
    public static final int NUMBNESS_CLEAR_MIN = 40;

    /**
     * RETENTION - time spent in a retention device between sessions, minutes per day.
     *
     * THE LOW END OF EVERY BAND THE SPEC GIVES: L1 1-2 h, L2 2-4 h/day including rest days,
     * L3 2-6 h. The earlier figures here were the app's own invention and every one of them
     * sat BELOW the spec's own floor.
     *
     * Taking the low bound is this codebase's habit with a source range, and it is the right
     * habit for an opt-in target: going under it costs nothing and going over it is not
     * progress. L3 takes 180 rather than its band's own 120 only so that L2 and L3 differ -
     * a level ladder whose rungs are identical makes the level irrelevant to the target.
     */
    public static int retentionTargetMin(int level) {
        // L4 CARRIES L3's FIGURE FORWARD. The spec's table simply stops at L3, and silence is
        // not an instruction to go higher - inventing a bigger number at the top of the
        // ladder is exactly the app-authored dose this constant exists to avoid.
        if (level >= L4) return 180;
        if (level == L3) return 180;
        if (level == L2) return 120;
        return 60;
    }

    /** The default number of strain sets a length track starts with. */
    public static final int LENGTH_STRAIN_SETS_START = 2;

    /* ===================================================================== *
     *  PURE QUERIES                                                          *
     * ===================================================================== */

    /** Rule (a) alone: ≥3 DAYS carried a logged plan session (misuse rule: "frequency
     *  counted in days, not sessions" — two sessions on one day is one day). The whole rule,
     *  with the volume half, is {@link TrainingWeek#qualifies}; nothing outside it asks this. */
    public static boolean trainingWeekQualifies(int loggedPlanSessionDays) {
        return loggedPlanSessionDays >= TRAINING_WEEK_MIN_DAYS;
    }

    /** Month index for the month-6/month-12 gates = effective weeks / 4, where training AND
     *  deload weeks count but empty weeks do not (audit ruling, {@link #TAG_INFERRED}). */
    public static int monthIndex(int effectiveWeeks) {
        if (effectiveWeeks < 0) return 0;
        return effectiveWeeks / 4;
    }

    /** Engine select: month<6 -> CALENDAR, month>=6 -> METRIC (the guidance). */
    public static int engineFor(int monthIndex) {
        return monthIndex >= L2_GATE_MONTH ? ENGINE_METRIC : ENGINE_CALENDAR;
    }

    /**
     * WHETHER YIELD MAY MOVE THE PRESCRIPTION YET.
     *
     * Open from L2 onward unconditionally, and at L1 from {@link #YIELD_FROM_WEEK_L1}. A
     * pure query rather than an inline condition because it settles a documented conflict
     * between two sections of the spec, and a rule that had to be argued for should be
     * readable in one place rather than reconstructed from an `if`.
     */
    public static boolean yieldTierOpen(int level, int weekIndex) {
        return yieldTierOpen(TRACK_GIRTH_INTERVAL, level, weekIndex);
    }

    /**
     * ...AND THE TRADITIONAL STYLE HAS NO WEEK TABLE TO BE PAST.
     *
     * weekIndex is a position in the INTERVAL master plan. Traditional girth has no such
     * table - its weekIndex counts its weeks at the level (0.10, traditionalSets), not rows
     * of the interval table - so the L1 test `weekIndex >= 10` means nothing for it.
     *
     * R-25 (A3/C7, the owner's decision, 2026-10-01): TRADITIONAL'S YIELD TIER OPENS AT LEVEL 2.
     * Level 1 grows by its calendar alone (3 -> 6 holds); from Level 2 the 6-12 % target moves
     * its holds one at a time, as it does the hybrid's.
     */
    public static boolean yieldTierOpen(int track, int level, int weekIndex) {
        if (track == TRACK_GIRTH_TRADITIONAL) return level >= L2;   // no table; from Level 2
        if (level >= L2) return true;
        return weekIndex >= YIELD_FROM_WEEK_L1;
    }

    /** Per-level Net-TUP floor in kPa (the guidance). L1 = 5 hg; L2/L3/L4 = 8 hg. */
    public static double floorKpa(int level) {
        return level <= L1 ? L1_FLOOR_KPA : L234_FLOOR_KPA;
    }

    /** Per-level, per-month pressure CAP in kPa (the guidance), always inside the
     *  absolute 15 hg. L1 in its first month caps at 6 hg; L1 otherwise 8 hg; L2/L3/L4
     *  cap at the 10 hg working cap. Never returns above {@link #ABSOLUTE_CAP_KPA}.
     *  The beginner's reading: the month-1 cap applies - see the three-argument form. */
    public static double pressureCapKpa(int level, int monthIndex) {
        return pressureCapKpa(level, monthIndex, true);
    }

    /**
     * {@link #pressureCapKpa(int,int)} FOR THIS PERSON (t10 REAL-14, the parity run, 2026-10-01):
     * the month-1 cap of {@link #MONTH1_CAP_KPA} is written for somebody who has never pumped
     * ({@link #startCapKpa} says why), so in month 0 it binds only somebody new to pumping.
     * Somebody who has pumped before and is in their plan's first month has the cap of their
     * level - 8 hg at L1, 10 above - as the setup already gave them (TrainerTab#deriveGirth).
     * It bound everybody after the setup: their plan stepped to 6 hg and held there for the
     * month, and Firm could not lift it.
     */
    public static double pressureCapKpa(int level, int monthIndex, boolean newToPumping) {
        double cap;
        if (level <= L1) {
            cap = newToPumping && monthIndex < 1 ? MONTH1_CAP_KPA : L1_CAP_KPA;
        } else {
            cap = WORKING_CAP_KPA;
        }
        return Math.min(cap, ABSOLUTE_CAP_KPA);
    }

    /** HOW MANY TRAINING WEEKS THIS CYCLE NEEDS: four for the first, three for the middle
     *  ones the dated table prints, four again from {@link #DELOAD_BACK_TO_FOUR_MONTH} -
     *  see that constant for the three sources. Stated as its own function because the
     *  decision below and the sentence it writes must quote the same number. */
    public static int deloadNeedWeeks(boolean firstCycle, int monthIndex) {
        if (firstCycle) return DELOAD_FIRST_AFTER_TRAINING_WEEKS;
        return monthIndex >= DELOAD_BACK_TO_FOUR_MONTH
             ? DELOAD_FIRST_AFTER_TRAINING_WEEKS : DELOAD_AFTER_TRAINING_WEEKS;
    }

    /** The head of the cadence deload's rule ({@link #evaluate}), so a card can tell the deload
     *  that asks when it starts (t10 REAL-1) from the ones a reading or a target brings. */
    public static final String CADENCE_DELOAD_RULE_HEAD = "deload due after ";

    /** Whether `d` is the cadence deload - due by the training weeks counted. */
    public static boolean isCadenceDeload(Decision d) {
        return d != null && d.action == ACTION_DELOAD && d.rule != null
            && d.rule.startsWith(CADENCE_DELOAD_RULE_HEAD);
    }

    /** Usage-linked deload: due once enough training weeks have accumulated since the last
     *  deload (plan round-2 ruling). Inactivity never counts. */
    public static boolean deloadDue(int accumulatedTrainingWeeks, boolean firstCycle,
                                     int monthIndex) {
        return accumulatedTrainingWeeks >= deloadNeedWeeks(firstCycle, monthIndex);
    }

    /** The early-months form, kept for callers that have no month to hand: the middle
     *  cadence, which is what months one to four run on. */
    public static boolean deloadDue(int accumulatedTrainingWeeks, boolean firstCycle) {
        return deloadDue(accumulatedTrainingWeeks, firstCycle, 0);
    }
    /** Convenience for a SUBSEQUENT cycle (threshold 3) — the common case past the first
     *  deload (feeder is always here, being L3+). */
    public static boolean deloadDue(int accumulatedTrainingWeeks) {
        return deloadDue(accumulatedTrainingWeeks, false);
    }

    /** Debounce boundary: a change fires only on {@link #YIELD_DEBOUNCE} (3) CONSECUTIVE
     *  tracked sessions below target — 2 is not enough, 3 is (the guidance). */
    public static boolean debounceTriggered(int consecutiveBelowTarget) {
        return consecutiveBelowTarget >= YIELD_DEBOUNCE;
    }

    /**
     * R-22 (A2, the owner's decision, 2026-10-01): THE GIRTH YIELD TARGET IS 6-12 % AT EVERY
     * LEVEL AND STYLE where the yield rule applies - Level 1 from week 10, Level 2, Levels 3
     * and 4, traditional from Level 2. The figure includes the edema a pumping session cannot
     * avoid. It replaces the per-level windows (3 % at L1, 4-6 % at L2, 6-8 % at L3/L4).
     * Level 1 below it adds a set and above it does nothing (R-20, the app's own rule: Level 1
     * is a floor only).
     */
    public static final double YIELD_LO = 6.0;
    public static final double YIELD_HI = 12.0;
    /** R-22 - the target as the Trainer says it. */
    public static final String YIELD_TARGET_WORDS =
        "Girth target: 6–12% after the session (edema included)";

    /** The yield's low bound, percent, below which the debounce counts a "below" - the same
     *  at every level (R-22); `level` is kept for the callers. */
    public static double yieldTargetLo(int level) {
        return YIELD_LO;
    }
    /** ...and its high bound, above which it counts an "above". */
    public static double yieldTargetHi(int level) {
        return YIELD_HI;
    }
    /**
     * THE YIELD A PAIR OF GIRTH READINGS SHOWS, percent - the one arithmetic behind every
     * yield figure in the app, so a mid-run check and an end-of-session one cannot disagree
     * about what the same two numbers mean.
     *
     * NaN when the starting figure is missing or not positive: a percentage of nothing is
     * not a large percentage, it is not a percentage - the same rule Meas#prePostPct states
     * for its own division.
     */
    public static double yieldPct(double startCm, double nowCm) {
        if (startCm <= 0 || nowCm <= 0) return Double.NaN;
        return (nowCm - startCm) / startCm * 100.0;
    }

    /** Whether a yield reading has REACHED the target (6 %, R-22) - the mid-run
     *  stop-at-target question, which is the low bound met rather than merely approached. */
    public static boolean yieldTargetMet(double yieldPct, int level) {
        return !Double.isNaN(yieldPct) && yieldPct >= yieldTargetLo(level);
    }

    /* ---- EARLY TARGET (trainer-v3 Phase 3 item 2) --------------------------------- *
     *  Meeting the session's whole yield target in the first third of it is not a good
     *  session, it is a warning: the tissue is giving way far sooner than the prescription
     *  expects, and the prescription is what needs to change.                            */

    /** At or below this fraction of the planned sets, hitting the target is EARLY. */
    /** How long the L1 gate's conditions must have been held, in training weeks. */
    public static final int L1_GATE_HOLD_TRAINING_WEEKS = 2;

    /**
     * FORTY MINUTES SEALED, and the screen says so once.
     *
     * The two-hour gross cap ends a run; this is the far softer thing that should happen
     * long before it. Oedema is cumulative and its early signs are not dramatic, so the
     * useful moment to mention it is well before anything looks wrong - and mentioning it is
     * all this does. No sheet, no stop, no gate.
     */
    public static final int EDEMA_ADVISORY_SEC = 2400;

    /**
     * TWENTY MINUTES IN ONE SET, and the editor says so once.
     *
     * The guidance is explicit and it is about a SET, not a session: a single set should stay
     * under 20 minutes, ideally 10 or less. It is the same passage that gives the 15 hg
     * ceiling and the two-hour session cap - both of which this engine already honours.
     *
     * NOTHING THE PLAN WRITES CAN REACH IT. The longest hold any prescription produces is
     * five minutes (Mint#HOLD_TRADITIONAL_SEC), so this is a bound on what a person can build
     * BY HAND in the set editor, where Model.Set#HOLD_MAX_SEC allows an hour. That hour is
     * correct as a clamp - ladder() can stitch a hold that long and the wire will carry it -
     * and it was never a claim that an hour is a good idea.
     *
     * AN ADVISORY, NOT A GATE, exactly like the oedema note above: no sheet, no stop, no
     * refusal. A hand-built routine is the user's own, and the app's job at that point is to
     * make sure they know what the guidance says, not to decide for them.
     */
    public static final int SET_ADVISORY_SEC = 1200;

    /* ---- WHAT A MISSED WEEK COSTS (Phase 3 item 8) -------------------------------- */

    public static final int MISS_CONTINUE = 0;
    public static final int MISS_REPEAT_WEEK = 1;
    public static final int MISS_STEP_BACK_WEEK = 2;

    /**
     * Missing this many sessions inside one week is a week that did not happen as prescribed,
     * so the week repeats rather than being counted.
     *
     * THREE, because the spec's own sentence is "1-2 missed sessions continue; only SEVERAL
     * repeat the week" - and it never numbers "several". Two sat inside the band the spec
     * says should carry on, so an ordinary busy week repeated. Three is the smallest number
     * the wording permits, and a threshold that repeats a week should be reached deliberately
     * rather than early.
     */
    public static final int MISS_SESSIONS_FOR_REPEAT = 3;

    /**
     * WHAT TO DO ABOUT SESSIONS THAT DID NOT HAPPEN.
     *
     * NEVER "CATCH UP", and never double a session. Missed work is missed; the tissue did
     * not do it and cannot do it twice as fast next time. The only three honest answers are
     * to carry on, to repeat the week that did not happen, or - after a whole week gone - to
     * re-enter one week behind, which is the same step-back a layoff already earns.
     *
     * A missed FULL WEEK outranks any number of missed sessions inside one: a week nobody
     * trained in is not a hard week, it is a break, and coming back where you left off is
     * how people get hurt on their first session back.
     */
    public static int missPolicy(int missedSessions, int missedFullWeeks) {
        if (missedFullWeeks >= 1) return MISS_STEP_BACK_WEEK;
        if (missedSessions >= MISS_SESSIONS_FOR_REPEAT) return MISS_REPEAT_WEEK;
        return MISS_CONTINUE;
    }

    public static final double EARLY_TARGET_FRACTION = 0.35;
    /** And it must happen this many tracked sessions running before anything is proposed -
     *  one early session is a good day, two is a pattern. */
    public static final int EARLY_TARGET_DEBOUNCE = 2;

    /** Whether a hit-fraction counts as early. NaN (no data) never does. */
    public static boolean earlyTarget(double hitFraction) {
        return !Double.isNaN(hitFraction) && hitFraction > 0
            && hitFraction <= EARLY_TARGET_FRACTION;
    }

    /** Whether a yield reading sits below the level's target low bound. */
    public static boolean yieldBelowTarget(double yieldPct, int level) {
        return yieldPct < yieldTargetLo(level);
    }
    /** Whether a yield reading sits ABOVE the target's high bound (12 %, R-22) - the
     *  high-yield signal that pauses (L2) or reduces (L3/L4) volume, and does nothing at
     *  Level 1 (R-20). */
    public static boolean yieldAboveTarget(double yieldPct, int level) {
        return yieldPct > yieldTargetHi(level);
    }

    /** Net-TUP milestone (minutes) above which the pressure phase may raise load. L1 20;
     *  L2 20 as well - the guidance raises Level 2's pressure once a session holds 20
     *  minutes while it builds towards 30 (the owner's decision, 2026-09-26; it was 30,
     *  which the L2 table's 26-minute top never reached); L3 main 20–21, L4 main 28–33 —
     *  the low bounds. */
    public static double netMilestoneMin(int level) {
        switch (level) {
            case L1: return 20.0;
            case L2: return 20.0;
            case L3: return 20.0;
            default: return 28.0;         // L4
        }
    }
    public static boolean netMilestoneReached(int level, double netTupMin) {
        return netTupMin >= netMilestoneMin(level);
    }

    /**
     * WHETHER A PRESCRIBED PRESSURE HAS REACHED A TARGET IN HG - AT THE RESOLUTION THE APP
     * CAN ACTUALLY PRESCRIBE AT.
     *
     * THE GATE COULD NOT BE CROSSED. A prescription is a WHOLE kPa: Mint#clampPressureKpa
     * rounds to an int because that is what the wire's field carries. Eight hg is 27.09 kPa,
     * so the deepest an L1 track can ever be prescribed is 27 - and the old tolerance of a
     * hundredth of a hg (0.034 kPa) put the bar at 27.057. Twenty-seven is not twenty-seven
     * point oh-six, so the pressure half of the L1 gate read FALSE at the plan's own L1
     * ceiling, for ever, for everybody. Nothing caught it because the tests passed the exact
     * double 8.0 * HG, which the app never stores.
     *
     * Rounding the TARGET to the same resolution the prescription lives at is the whole fix,
     * and it is exact rather than a tolerance somebody has to justify: 8 hg rounds to 27 kPa,
     * a prescription of 27 has reached it, and 7 hg (24 kPa) has not.
     */
    public static boolean pressureReachedHg(double prescribedKpa, double targetHg) {
        return pressureReachedKpa(prescribedKpa, targetHg * HG);
    }

    /**
     * THE SAME RULE FOR ANY LEVEL FIGURE IN kPa - a floor or a gate: reached when the pressure
     * is at or above the figure rounded to the whole kPa the app stores and commands.
     * {@link #pressureReachedHg} is this with the target in hg; the setup's placement
     * (TrainerTab#deriveGirth) reads the Level 2 floor through it too (0.10, the owner's
     * decision): "8.0 inHg" is stored as 27 kPa, and 27 is below 8 hg's 27.09, so an
     * experienced person answering 8.0 was placed at Level 1 by a fraction the wire cannot
     * carry. Caps round as they always did (Plan#absoluteCapWholeKpa, Scale's tops) - this
     * is for reaching a figure, never for how high a pressure may go.
     */
    public static boolean pressureReachedKpa(double pressureKpa, double levelKpa) {
        return pressureKpa >= Math.round(levelKpa) - 1e-9;
    }

    /** L1→L2 gate: Net TUP ≥ 20 AND the prescribed pressure of qualifying (in-band)
     *  sessions reached 8 hg (the guidance). {@code prescribedPressureReached8} is what the
     *  caller derives from its in-band machinery — NOT a raw sensor comparison. */
    public static boolean gateL1toL2(double netTupMin, boolean prescribedPressureReached8) {
        return gateL1toL2(netTupMin, prescribedPressureReached8,
                          L1_GATE_HOLD_TRAINING_WEEKS);
    }

    /**
     * AND HELD THERE FOR TWO TRAINING WEEKS.
     *
     * Meeting the gate is one session's arithmetic; holding it is what says the tissue has
     * adapted rather than had a good week. Two training weeks is the guide's own figure, and
     * it is the difference between "you touched 20 minutes at 8 hg once" and "this is what
     * your sessions look like now".
     *
     * DERIVED FROM THE SESSION LOG, never a stored timestamp. That choice does two things:
     * there is no new persisted field to migrate, and an upgrading user's history already
     * satisfies it - somebody who has been at this for months does not get sent back to
     * week one by a rule that was added yesterday.
     */
    public static boolean gateL1toL2(double netTupMin, boolean prescribedPressureReached8,
                                     int heldTrainingWeeks) {
        return netTupMin >= L1_GATE_NET_MIN && prescribedPressureReached8
            && heldTrainingWeeks >= L1_GATE_HOLD_TRAINING_WEEKS;
    }
    /**
     * TRADITIONAL GIRTH'S L1 -> L2 GATE, ON ITS OWN PLANNED MINUTES (the owner's decision,
     * 2026-09-27). The interval gate's 20 minutes is a figure traditional's 2 x 5 min can never
     * reach, so a traditional track could never leave Level 1. It mirrors the interval gate in
     * everything but the time: each of the last 3 scored sessions held its own routine's
     * planned minutes (TrainerTab.NetSignals#ownTargetsMet), the prescribed pressure reached
     * 8 hg ({@link #pressureReachedHg}), and both held for {@link #L1_GATE_HOLD_TRAINING_WEEKS}
     * counting weeks. The time condition is the app's, as traditional's pressure step is.
     */
    public static boolean gateL1toL2Traditional(boolean ownTargetsMet,
                                                boolean prescribedPressureReached8,
                                                int heldTrainingWeeks) {
        return ownTargetsMet && prescribedPressureReached8
            && heldTrainingWeeks >= L1_GATE_HOLD_TRAINING_WEEKS;
    }
    /** L2→L3 gate's calendar half: month 6 reached (the guidance). */
    public static boolean gateL2toL3(int monthIndex) {
        return monthIndex >= L2_GATE_MONTH;
    }
    /** L3→L4 gate's calendar half: month 12 reached, then the A|C fork (the guidance). */
    public static boolean gateL3toL4(int monthIndex) {
        return monthIndex >= L3_GATE_MONTH;
    }

    /**
     * LEVELS 3 AND 4 ALSO NEED THE VOLUME (the owner's decision, 2026-09-27). The calendar
     * alone moved somebody on at month 6 or 12 whatever their sessions were holding, so a
     * person who had missed half of Level 2's build-up was handed Level 3 with it. Now the
     * month gate AND the sessions: each of the last 3 scored sessions held the minutes the
     * level is left with ({@link #levelExitNetMin}) - the same per-session minimum over the
     * last three (Inputs#netTupMin) every other time gate reads. Traditional girth, which
     * has no volume schedule, asks its own planned minutes instead (Inputs#ownTargetsMet),
     * as its pressure step and its Level 1 gate do. Length stays calendar-only: the
     * guidance names no volume for leaving a length level (its length progressions are
     * driven by strain and fatigue, which the length ladder already reads).
     */
    public static double levelExitNetMin(int level) {
        switch (level) {
            case L2: return L2_EXIT_NET_MIN;              // the L2 table's top, 30 (derived)
            case L3: return netMilestoneMin(L3);          // the guidance's 20-21, low bound
            default: return 0.0;
        }
    }

    /** Whether the sessions hold the volume the current level is left with - interval by
     *  the per-session minutes, traditional by each session's own planned minutes. */
    public static boolean levelVolumeHeld(int track, int level, double netTupMin,
                                          boolean ownTargetsMet) {
        if (track == TRACK_GIRTH_TRADITIONAL) return ownTargetsMet;
        return netTupMin + 1e-9 >= levelExitNetMin(level);
    }

    /** L2→L3 gate: month 6 AND the sessions hold Level 2's exit volume. */
    public static boolean gateL2toL3(int monthIndex, boolean volumeHeld) {
        return gateL2toL3(monthIndex) && volumeHeld;
    }
    /** L3→L4 gate: month 12 AND the sessions hold Level 3's exit volume; then the fork. */
    public static boolean gateL3toL4(int monthIndex, boolean volumeHeld) {
        return gateL3toL4(monthIndex) && volumeHeld;
    }

    /** Ceiling deadlock (plan round-3): the plan wants to raise pressure but the user's
     *  device ceiling sits below the next +1 hg step AND that step is still within the
     *  plan's own cap — so the gate is unreachable and the tab must say so. Returns false
     *  when we are already at the plan cap (that is "at cap: hold", not a deadlock). */
    public static boolean ceilingDeadlock(double currentPressureKpa, double ceilKpa,
                                          int level, int monthIndex) {
        return ceilingDeadlock(currentPressureKpa, ceilKpa, level, monthIndex, true);
    }

    /** {@link #ceilingDeadlock(double,double,int,int)} against this person's own cap (REAL-14:
     *  the month-1 cap binds only somebody new to pumping). */
    public static boolean ceilingDeadlock(double currentPressureKpa, double ceilKpa,
                                          int level, int monthIndex, boolean newToPumping) {
        double next = currentPressureKpa + STEP_HG_KPA;
        double cap = pressureCapKpa(level, monthIndex, newToPumping);
        if (next > cap + 1e-9) return false;      // next step is above the plan cap anyway
        return ceilKpa + 1e-9 < next;             // device ceiling can't reach the next step
    }

    /* ---- feeder (the guidance, plan round-2 feeder ruling) ---- */

    /** Feeder is L3+ only; auto-disabled below L3 (plan round-2 feeder ruling (d)). */
    public static boolean feederEligible(int level) { return level >= L3; }
    /** Feeder pressure = 70–80% of the main-session pressure (the guidance). */
    public static double feederPressureLoKpa(double mainKpa) { return 0.70 * mainKpa; }
    public static double feederPressureHiKpa(double mainKpa) { return 0.80 * mainKpa; }
    /** The mid-band 75% suggestion the mint carries. */
    public static double feederPressureKpa(double mainKpa) { return 0.75 * mainKpa; }

    /* ---- FEEDER CADENCE (the guidance: twice a day, 10 min, 4–6 h apart) ------------- */

    /** How many feeder sessions a day the guide asks for. */
    public static final int FEEDER_PER_DAY = 2;
    /** The window between them: no sooner than four hours, and six is the far edge of what
     *  the guide describes rather than a deadline - a feeder taken late is still a feeder. */
    public static final long FEEDER_MIN_GAP_MS = 4L * 3600000L;
    public static final long FEEDER_MAX_GAP_MS = 6L * 3600000L;

    /**
     * WHAT THE FEEDER CARD SHOULD SAY ABOUT TODAY - one of four states, decided from the
     * count so far and the time since the last one.
     *
     * The pressure and the volume were both right and the CADENCE was implemented nowhere:
     * "2x/day, 4–6 h apart" appeared in no code and on no screen. This is the rule, as a
     * pure query, so the card states a position rather than a slogan.
     */
    public static final int FEEDER_DUE = 0;        // none yet today, or the gap has elapsed
    public static final int FEEDER_TOO_SOON = 1;   // one done, less than four hours ago
    public static final int FEEDER_DONE = 2;       // both done today

    public static int feederState(int doneToday, long msSinceLast) {
        if (doneToday >= FEEDER_PER_DAY) return FEEDER_DONE;
        if (doneToday <= 0) return FEEDER_DUE;
        return msSinceLast < FEEDER_MIN_GAP_MS ? FEEDER_TOO_SOON : FEEDER_DUE;
    }

    /** How long until the next one may be taken, or 0 when it may be taken now. */
    public static long feederWaitMs(int doneToday, long msSinceLast) {
        if (feederState(doneToday, msSinceLast) != FEEDER_TOO_SOON) return 0L;
        return FEEDER_MIN_GAP_MS - msSinceLast;
    }

    /** Whether a level carries the separately-counted fatigue block (L3+), whose frames the
     *  caller's per-frame Net-TUP pass must exclude (the guidance; plan round-3). */
    public static boolean fatigueBlockPresent(int level) { return level >= L3; }

    /** Safety flag clears ONLY on a later "all good" readiness report; a numbness flag
     *  additionally requires the 7-day off period elapsed. No auto-expiry on silence
     *  (plan round-3 ruling). */
    public static boolean safetyClears(boolean allGoodReport, boolean numbnessFlag,
                                       long offPeriodMs) {
        if (!allGoodReport) return false;
        if (numbnessFlag && offPeriodMs < NUMBNESS_OFF_MS) return false;
        return true;
    }

    /**
     * B2 - WHETHER THE PRE-RUN READINESS CHECK COULD CHANGE ANYTHING TODAY.
     *
     * Pure, and here rather than on the screen, for the same reason {@code Meas#matchingNotes}
     * is: this decides whether a SAFETY prompt appears, and what it means has to be asserted
     * in the harness rather than read off a dialog.
     *
     * Starting a trainer routine used to cost two dialogs unconditionally - the readiness ask,
     * then the start confirm. On almost every day "All good" is the answer AND that answer
     * does nothing: {@link #safetyClears} is only ever consulted when a flag is up, so with no
     * flag the tap had no branch behind it. A prompt that cannot change anything teaches
     * people to dismiss prompts without reading them, which is the worst state a safety
     * prompt can reach.
     *
     * DELIBERATELY NOT A CACHED ANSWER. Remembering "all good" for the rest of the day would
     * cut the same prompt, and would do it by acting on something the person said hours ago.
     * Nothing here is remembered: it asks whether TODAY is a day on which the answer matters.
     *
     * @param safetyFlagUp   a report is standing. The ask is the only thing that clears one,
     *                       so it must appear or the flag is permanent.
     * @param backAfterBreak a taper step is in force ({@link #returnTaperHg}); the plan
     *                       already treats this as a changed body.
     * @param levelUpUnrun   a graduation has been announced and not yet run - the pressure,
     *                       the set count and the target have all moved.
     */
    public static boolean readinessMatters(boolean safetyFlagUp, boolean backAfterBreak,
                                           boolean levelUpUnrun) {
        return safetyFlagUp || backAfterBreak || levelUpUnrun;
    }

    /* ---- week-table lookup + table exhaustion ---- */

    /** The table row for a given 1-based ordinal within a level's table; past the end,
     *  the LAST TRAINING week's row repeats (plan round-3 "table exhaustion": gate unmet
     *  at table end → repeat the last training week's prescription until met). */
    public static Week rowForOrdinal(Week[] table, int ordinal1Based) {
        if (table == null || table.length == 0) return null;
        if (ordinal1Based >= 1 && ordinal1Based <= table.length) {
            return table[ordinal1Based - 1];
        }
        return lastTrainingWeek(table);   // exhaustion: repeat the last training pattern
    }
    /** The last non-deload row of a table (the pattern that repeats on exhaustion). */
    public static Week lastTrainingWeek(Week[] table) {
        if (table == null) return null;
        for (int i = table.length - 1; i >= 0; i--) {
            if (!table[i].deload) return table[i];
        }
        return null;
    }

    /** The interval↔traditional style switch (plan §7 ruling (d)): month and engine carry,
     *  the prescription maps to the other style's tables. Same level, same month — a
     *  {@link #TAG_INFERRED} decision, since the source states no cross-style mapping. */
    public static Decision switchTrackMapping(int fromTrack, int toTrack, int level,
                                              int monthIndex) {
        String from = trackName(fromTrack), to = trackName(toTrack);
        return new Decision(ACTION_HOLD, TAG_INFERRED,
            "Style switch " + from + " -> " + to + ": level " + level
            + " and month " + monthIndex + " carry; prescription remaps to the "
            + to + " tables.",
            "switch carries month/engine; no source-stated cross-style mapping",
            Double.NaN, 0);
    }

    private static String trackName(int track) {
        switch (track) {
            case TRACK_GIRTH_INTERVAL: return "girth-interval";
            case TRACK_GIRTH_TRADITIONAL: return "girth-traditional";
            case TRACK_LENGTH: return "length";
            case TRACK_FEEDER: return "feeder";
            default: return "?";
        }
    }

    /* ===================================================================== *
     *  DECISION VALUE TYPE                                                   *
     * ===================================================================== */

    /** One engine decision: the fired action, its §11 provenance tag, the exact rule text,
     *  the human reason (esp. why a HOLD holds), and — where the action changes the
     *  prescription — the suggested pressure (kPa, NaN if none) and set delta (0 if none). */
    public static final class Decision {
        public final int action;
        public final int tag;
        public final String rule;
        public final String reason;
        public final double pressureKpa;
        public final int setsDelta;
        /**
         * THE LOAD THIS DECISION ASKS FOR, pounds - NaN on every decision that does not move
         * one, which is every girth decision and most length ones.
         *
         * A separate field rather than borrowing pressureKpa: the two are not the same
         * quantity and the conversion between them depends on the user's girth, so a caller
         * that read one as the other would command a plausible-looking wrong number.
         */
        public final double loadLb;

        /* ---- t10, girth (lane B). What applying the decision keeps (Mint#commitYield); every
         *      other decision carries the "nothing" value. ------------------------------------ */

        /** R-25: the volume change moves a hybrid's own 5-minute holds
         *  (TrainerTrackState#hybridYield), not the interval count. */
        public final boolean hybrid;
        /** R-27: an applied time-cap conversion keeps the holds converted so far (a high-water
         *  mark, TrainerTrackState#r2ExHolds) and the pressure still owed, whole kPa
         *  (TrainerTrackState#r2PendKpa). -1 on every decision that is not one. */
        public final int r2ExHolds, r2PendKpa;
        /** R-27: the holds a volume step adds to the plan's own count when the whole step lands
         *  past the cap - kept when the conversion is applied, none of them run. 0 otherwise.
         *  Never in {@link #setsDelta}, which is what the prescription moves by. */
        public final int r2AddHolds;
        /** R-21 (C2): a high-yield arm that would have changed nothing fell through to this
         *  decision - applying it restarts the yield streak. */
        public final boolean restartsYield;

        public Decision(int action, int tag, String rule, String reason,
                        double pressureKpa, int setsDelta) {
            this(action, tag, rule, reason, pressureKpa, setsDelta, Double.NaN);
        }

        public Decision(int action, int tag, String rule, String reason,
                        double pressureKpa, int setsDelta, double loadLb) {
            this(action, tag, rule, reason, pressureKpa, setsDelta, loadLb, false, -1, -1, 0,
                 false);
        }

        Decision(int action, int tag, String rule, String reason, double pressureKpa,
                 int setsDelta, double loadLb, boolean hybrid, int r2ExHolds, int r2PendKpa,
                 int r2AddHolds, boolean restartsYield) {
            this.action = action; this.tag = tag; this.rule = rule;
            this.reason = reason; this.pressureKpa = pressureKpa; this.setsDelta = setsDelta;
            this.loadLb = loadLb;
            this.hybrid = hybrid; this.r2ExHolds = r2ExHolds; this.r2PendKpa = r2PendKpa;
            this.r2AddHolds = r2AddHolds;
            this.restartsYield = restartsYield;
        }
        public boolean inferred() { return tag != TAG_SOURCE; }

        /** R-25: the same decision, moving the hybrid's own holds. */
        Decision forHybrid(boolean on) {
            if (on == hybrid) return this;
            return new Decision(action, tag, rule, reason, pressureKpa, setsDelta, loadLb, on,
                                r2ExHolds, r2PendKpa, r2AddHolds, restartsYield);
        }

        /** R-21: the same decision, restarting the yield streak when applied. */
        Decision restarting() {
            if (restartsYield) return this;
            return new Decision(action, tag, rule, reason, pressureKpa, setsDelta, loadLb, hybrid,
                                r2ExHolds, r2PendKpa, r2AddHolds, true);
        }

        /** R-27: whether this is a time-cap conversion (raise or hold). */
        public boolean r2() { return r2ExHolds >= 0; }
    }

    /* ===================================================================== *
     *  EVALUATE INPUTS                                                       *
     *  A caller-assembled snapshot. Key-absent-safe defaults are the        *
     *  meta-rule's "no data" state: all-zero/false -> HOLD.                  *
     * ===================================================================== */
    public static final class Inputs {
        public int track = TRACK_GIRTH_INTERVAL;
        public int level = L1;
        public int monthIndex = 0;
        /** Whether the person said they are new to pumping (Scale#isNew) - the only person the
         *  first month's caps bind (REAL-14). True by default: the beginner's reading, which is
         *  what every cap here applied before the person was asked. */
        public boolean newToPumping = true;
        /** Current PRESCRIBED working pressure, kPa. */
        public double pressureKpa = L1_FLOOR_KPA;
        /** Device ceiling from the user's Settings, kPa. */
        public double ceilKpa = ABSOLUTE_CAP_KPA;
        /** Main-session pressure for feeder derivation, kPa (NaN if not feeder). */
        public double mainPressureKpa = Double.NaN;

        // progression signals
        /** The week within the level's own span - only read to decide whether L1 has reached
         *  {@link #YIELD_FROM_WEEK_L1}. Levels above L1 ignore it: their yield tier is open
         *  from their first week. */
        public int weekIndex = 1;

        public boolean hasYieldData = false;
        public int consecutiveLowYield = 0;     // consecutive tracked sessions below target
        public int consecutiveHighYield = 0;    // consecutive tracked sessions above hi target
        /** PER-SESSION achieved Net TUP, minutes — NOT a weekly sum. The milestone
         *  ({@link #netMilestoneReached}) and the L1→L2 gate are per-session figures (a table
         *  row is one session's sets × 2-min), so the caller must feed a per-session signal
         *  that means "the user is CONSISTENTLY reaching the per-session target" (e.g. the
         *  minimum over the last few tracked sessions — all of them reached it), never the
         *  sum of every session in a window (three 11-min sessions summing to 33 must not read
         *  as the 30-min L2 milestone). */
        public double netTupMin = 0.0;
        /** Counting weeks at the CURRENT working pressure, toward the +1 hg step - since the
         *  pressure last changed, never since the routine was last rewritten
         *  (TrainerTab#weeksAtPressure; a week touching a deload never counts). For the
         *  length track the caller's contract is months since the last nudge. */
        public int trainingWeeksAtPressure = 0;
        /** The gentle return after a deload or layoff is still open (Deload#armed): its
         *  steps have not all been run and closed by a full-pressure day. No pressure step
         *  while it is - the return is still bringing the tissue back to this pressure. */
        public boolean gentleReturnOpen = false;
        /** PLAN-WIDE: the gentle return still has a reduced day to run - today, or a later
         *  day, runs under the working pressure (TrainerTab#returnRunsUnder). No level-up is
         *  proposed while it does (the owner's decision, 2026-09-27): the move comes on the
         *  first full day. Unlike {@link #gentleReturnOpen}, a finished taper waiting for
         *  its full-pressure day to close it no longer counts. */
        public boolean returnRunsUnder = false;
        /** t10 parity run 2, A-4: the week off the gentle return is armed for has not begun
         *  (Deload#notBegunOn) - a week off answered for a later day arms the return at the
         *  answer, and the days before it are not return days (REAL-5): they run at the
         *  working pressure and count. R-26's wait (#evaluate, 5) does not read them as one. */
        public boolean weekOffAhead = false;
        /** TRADITIONAL GIRTH'S "TIME HELD": each of the last {@link #YIELD_DEBOUNCE} scored
         *  sessions delivered its OWN routine's planned minutes at pressure
         *  (TrainerTab.NetSignals#ownTargetsMet). Only the traditional track reads it: its
         *  2 x 5 min can never reach an interval milestone. */
        public boolean ownTargetsMet = false;
        /** LEVEL 1'S TABLE PRESSURE ASKS THE SAME, AT THE CURRENT PRESSURE: each of the last
         *  {@link #YIELD_DEBOUNCE} scored sessions run SINCE THE WORKING PRESSURE LAST CHANGED
         *  delivered its own planned minutes (TrainerTab#fillGirthInputs). Sessions at the
         *  old pressure never count, so a table step is always earned at the pressure it
         *  steps from - two steps can never land without a session between them. */
        public boolean ownTargetsMetAtPressure = false;
        /** The sets yield has added (or taken) and the plan keeps
         *  (Model.TrainerTrackState#yieldSets) - what a high-yield pause at L1/L2 removes. */
        public int yieldSets = 0;
        /** G3 - training weeks (counted as the pressure clock counts them: weeks of three or
         *  more training days on the track, a deload week never counting) with no qualifying
         *  reading for this track since the later of its last reading, its last volume
         *  change and enrolment (TrainerTab#weeksWithoutReadings). 0 = a reading is recent,
         *  or not given: no fallback. */
        public int weeksWithoutReadings = 0;
        /** G4 - the girth routine's work holds as Mint counts them (Mint#totalSets: the
         *  level's base or carried count plus the kept yield sets) - what a volume step is held
         *  to the level's top against ({@link #volumeStep}). 0 = not given: no top binds. */
        public int workSets = 0;

        // plan-wide events / safety (a RED steps back ALL tracks)
        public boolean redFlag = false;
        public boolean layoff = false;          // ≥7-day layoff auto-detected
        public boolean underDelivery = false;   // persistent Net-TUP under-delivery

        // cadence
        public boolean inDeloadWeek = false;    // currently a deload week (counters frozen)
        public int accumulatedTrainingWeeks = 0;// since last deload -> deloadDue at ≥4 first, ≥3 after
        // no deload taken yet -> first cycle needs 4 wks (then 3). CALLER OBLIGATION
        // (Tasks 2-5): set this false once the first deload has actually been taken, so
        // every subsequent cycle uses the 3-week threshold.
        public boolean firstDeloadPending = true;

        /** Hit-set divided by planned sets, taken as the MINIMUM over the last
         *  {@link #EARLY_TARGET_DEBOUNCE} tracked sessions - so it reads "even the LATEST
         *  of the recent sessions hit early", never "one of them did". NaN = no data. */
        public double targetHitFraction = Double.NaN;

        /** How many training weeks the L1 gate's two conditions have BOTH been true for -
         *  walked from the session log by the caller, never stored. 0 = not held yet, which
         *  is what an unknowable history reads as. */
        public int gateHeldTrainingWeeks = 0;

        /** Qualifying training weeks on the LENGTH track since enrolment - what the calendar
         *  ladder counts while the metrics are still closed. Walked from the log, never
         *  stored. */
        public int lengthTrainingWeeks = 0;
        /** R11-3: the training weeks the strain-set calendar starts from (Model
         *  .TrainerTrackState#strainCalWeeks). */
        public int strainCalWeeks = 0;
        /** R11-4 (GainBrake): readings on target and the at-rest average rising - the calendar
         *  steps come half as often. Never on a return day or the gentle week (the caller). */
        public boolean gainBrake = false;
        /** R11-4: "Step up now" answered for the step waiting now - girth's pressure step, or
         *  length's climb - so it comes at the normal pace. */
        public boolean brakeStepUp = false;
        /** R11-4: the same for the slow load step. */
        public boolean brakeStepUpSlow = false;
        /** R11-4: the slow load step's normal 2 training weeks are up, its braked 4 not. */
        public boolean slowLoadBraked = false;

        /* ---- LENGTH signals. Every one of these is DERIVED from the logs at evaluation
         *      time; none of them is persisted. NaN / 0 / false is "no data", and the
         *      ladder's answer to no data is HOLD - the same discipline the girth signals
         *      above already keep. ------------------------------------------------------ */

        /** Session-to-session stretched-length gain, percent. NaN = not measurable yet. */
        public double strainPct = Double.NaN;
        /** Pre/post recovery between sessions, percent. NaN = not measurable yet. */
        public double fatiguePct = Double.NaN;
        /** How many days the strain reading has sat below {@link #LENGTH_STRAIN_LO}. */
        public int strainMissDays = 0;
        /** t10 O8: the newest length reading ({@link #strainPct}) was taken on a reduced return
         *  day after a deload (Sess#returnDay). It counts as the reading the ladder acts on
         *  (O5) but not toward the low streak, which {@link #strainMissDays} then answers
         *  alone (Plan#strainLowForAWeek). */
        public boolean strainNewestReduced = false;
        /** How the ACTIVE cylinder classifies against the measured girth - a
         *  {@link Traction} FIT_* band. FIT_UNKNOWN when no girth or no cylinder. */
        public int fitState = Traction.FIT_UNKNOWN;
        /** Measured girth in CENTIMETRES, for the load/pressure conversion. 0 = unknown.
         *  Centimetres and not the spec's inches because every store and every caller in
         *  this app holds cm, and {@link Traction} takes cm too - carrying inches here would
         *  put a conversion at each call site, which is where a unit bug goes to live. */
        public double girthCm = 0.0;
        /** A cylinder is MARKED for length (Model#lengthPulls): the track pulls whatever
         *  {@link #fitState} says (owner, 2026-09-30) - the fit only warns now. */
        public boolean lengthTube = false;
        /** That cylinder's bore, cm - what the load converts at (Traction#maxLoadLbAtBore).
         *  0 = none; the conversion then falls back to {@link #girthCm}. */
        public double boreCm = 0.0;
        /** Stretched length rising while erect length stays flat, for
         *  {@link #DIVERGENCE_WEEKS} weeks. */
        public boolean stretchOutrunningErect = false;

        /** The track's current load in pounds, and how many strain sets it runs. */
        public double loadLb = LENGTH_LOAD_START_LB;
        public int strainSets = LENGTH_STRAIN_SETS_START;
        /** True while a girth-focus block is running - the ladder holds rather than
         *  proposing another one. */
        public boolean inGirthFocus = false;

        /**
         * G2 (the owner's decision, 2026-10-01) - THE TOP THE PLAN'S OWN FIGURE CLIMBS TO, kPa
         * on the plan's scale, when the track's "Most you will go to" is above its usual top
         * (Scale#planTopKpa: that maximum less the offset, within the ceiling, 15 inHg and -
         * for a length track that pulls - the 15 lb load limit). NaN, or a figure at or under
         * the usual top, is the usual top: what every evaluation did before.
         */
        public double climbTopKpa = Double.NaN;

        /* ---- t10 signals (lanes B and C fill and read them; D0 only declares them). Every
         *      default is the value under which the new rule does not fire. ---------------- */

        /** R-26 (fix d): a level-up landed this morning - no volume step the same morning. */
        public boolean levelUpToday = false;
        /** R-60 (CAP90): the pending volume step would take the both-tracks day past 90
         *  minutes (TrainerTab#heldAt90) - the step holds with {@link #HELD_AT_90_RULE}. */
        public boolean heldAt90 = false;
        /** R-23: a low-yield add is waiting to be judged (TrainerTrackState#addPending). */
        public boolean addPending = false;
        /** R-23: girth rests for the offer's 4 weeks of length focus (the girth track's dated
         *  rest, TrainerTrackState#resting) - the girth ladder holds. */
        public boolean girthResting = false;

        /* ---- t10-K: THE MONTH-12 BREAK (MonthBreak#fill sets these) ---------------------- */
        /** B1: the break runs - every track holds ({@link MonthBreak#breakHold}). */
        public boolean onBreak = false;
        /** B3: the gentle week after it - no length load step of any kind
         *  ({@link MonthBreak#lengthAfter}). */
        public boolean gentleWeek = false;
        /** B2: Level 4 waits - girth for 4 counted training weeks from the return day, either
         *  track on the day the break is taken (length's waits for the gentle week through
         *  {@link #returnRunsUnder}). */
        public boolean l4Waits = false;
        /** B5: the climb-back target, pounds (0 = none), and the most the climb may reach. */
        public double climbTargetLb = 0.0;
        public double climbCapLb = 0.0;
        /** R-27: holds already converted at the cap (TrainerTrackState#r2ExHolds). */
        public int r2ExHolds = 0;
        /** R-27: pressure a conversion still owes, whole kPa (TrainerTrackState#r2PendKpa). */
        public int r2PendKpa = 0;
        /** R-25: holds yield has added to a hybrid's own holds
         *  (TrainerTrackState#hybridYield). */
        public int hybridYield = 0;
        /** R-25: the hybrid's own 5-minute holds the plan writes today (Plan#hybridHolds);
         *  0 = not a hybrid, and the volume steps move the interval count. */
        public int hybridHolds = 0;
        /** R-27: the plan's hold for the time cap, seconds (120 interval, 300 traditional and
         *  the hybrid's own); 0 = not given: no cap. */
        public int r2HoldSec = 0;
        /** R-27: the fatigue block's seconds the cap counts (450, 675 extended; 0 when it is
         *  off or under Level 3, Mint#r2FatSec). */
        public int r2FatSec = 0;

        /** R-40 (option D): this deload block had a length reading at or above
         *  {@link #LENGTH_STRAIN_LO} (Meas#reachedSince). */
        public boolean blockHadReachLo = false;
        /** R-40: a D2 strain set was added in this block (TrainerTrackState#dAddedInBlock). */
        public boolean dAddedInBlock = false;
        /** R-40: the D3 offer was made in this block (TrainerTrackState#dOfferedInBlock). */
        public boolean dOfferedInBlock = false;
        /** R-40 (C10): two pairs over the high target since the strain clock
         *  (Meas#strainHighConfirmed). */
        public boolean strainHighConfirmed = false;
        /** R-43: the last length reading was over the high target - no climb. */
        public boolean lastStrainHigh = false;
        /** R-40 (C10): whole days since the last confirmed-high cut
         *  (TrainerTrackState#lastCutMs); -1 = no cut on record. */
        public int daysSinceLastCut = -1;
        /** R-45: the month-3 hand-over to 6 strain sets has happened or is not owed
         *  (TrainerTrackState#handedOver). True = nothing to hand over. */
        public boolean handedOver = true;
        /** R-46: a slow calendar load step is due (2 length training weeks since the last,
         *  TrainerTrackState#slowLoadWeeks). */
        public boolean slowLoadDue = false;
        /** R-46: the person's "Length load after month 3" (Model#lengthLoadMode).
         *  LENGTH_LOAD_AFTER12 - the load moves only at 12 strain sets, as before. */
        public int lengthLoadMode = Model.LENGTH_LOAD_AFTER12;
        /** R-40 D1 (lane C): the regular deload follows this training week anyway - the
         *  weeks counted BEFORE this one, plus this one, bring it due (LengthTrack#fill).
         *  Only the words of a "fell" decision read it. false - the week off comes forward. */
        public boolean deloadNextAnyway = false;
        /** t10 REAL-8 (parity round 2): the newest length reading was taken before the last
         *  confirmed-high cut (TrainerTrackState#lastCutMs) - the cut has answered it, so it
         *  asks for nothing more; a reading after the cut asks again. */
        public boolean strainHighAnswered = false;
        /** t10 REAL-11 (parity round 2): the load has moved since the last length session
         *  (TrainerTrackState#loadMovedMs) - no further load step until a session has run at
         *  it. A cut is never held by it. */
        public boolean loadMovedSinceSession = false;
        /** t10 parity run 2, A-3 (R-46, one change a morning): the length work already changed
         *  today - its level, load, strain sets or pressure (LengthTrack#fill). No other
         *  level-up, load, set or pressure step lands the same morning; a cut is not held. */
        public boolean lengthChangedToday = false;
        /** Parity run 3, OPEN-5: the person's length offset as pounds of pull at the length
         *  bore (Scale#offsetLb) - the load they pull is #loadLb plus this. */
        public double lengthOffsetLb = 0.0;
        /** t10 parity run 2, O-1 (one decision a girth morning): the plan already changed the
         *  girth work today (TrainerTrackState#planChangeMs) or the level moved this morning
         *  (#levelUpToday). No other volume, pressure or level step lands the same morning. */
        public boolean girthChangedToday = false;
    }

    /* ===================================================================== *
     *  G4 - VOLUME TOPS PER LEVEL (the owner's decision, 2026-10-01)        *
     * ===================================================================== */

    /**
     * THE MOST WORK HOLDS INTERVAL GIRTH RUNS AT LEVEL 3 - and {@link
     * #GIRTH_INTERVAL_L4_TOP_SETS} at Level 4. Counted as Mint counts a routine's work holds
     * (the base or carried count plus the kept yield sets, Mint#totalSets). Every volume step
     * - the yield rule's and the no-readings fallback's - stops here; a count already above
     * it is not cut. The owner's figures, under review: each is a one-line change here, and
     * {@link #volumeTopSets} is the one place they are read.
     */
    public static final int GIRTH_INTERVAL_L3_TOP_SETS = 14;
    public static final int GIRTH_INTERVAL_L4_TOP_SETS = 18;
    /** The length track's top: its strain sets, at most {@link #LENGTH_STRAIN_SETS_MAX}. */
    public static final int LENGTH_TOP_STRAIN_SETS = LENGTH_STRAIN_SETS_MAX;

    /**
     * G4 - THE LEVEL'S VOLUME TOP for `track`, sets - the one place the tops are read. Interval
     * girth at Levels 3 and 4, and the length track's strain sets (always 12: the length
     * ladder reads it); {@link Integer#MAX_VALUE} where no top is set - interval Levels 1 and 2
     * follow their week tables.
     *
     * R-25 (A3, the owner's decision, 2026-10-01): TRADITIONAL GIRTH HAS TOPS TOO - 6 at Levels
     * 1 and 2, 7 at Level 3, 9 at Level 4 ({@link #traditionalTop}). The hybrid's own holds top
     * at {@link #HYBRID_TOP_HOLDS}: see {@link #hybridHolds}.
     */
    public static int volumeTopSets(int track, int level) {
        if (track == TRACK_LENGTH) return LENGTH_TOP_STRAIN_SETS;
        if (track == TRACK_GIRTH_INTERVAL) {
            if (level == L3) return GIRTH_INTERVAL_L3_TOP_SETS;
            if (level >= L4) return GIRTH_INTERVAL_L4_TOP_SETS;
        }
        if (track == TRACK_GIRTH_TRADITIONAL) return traditionalTop(level);
        return Integer.MAX_VALUE;
    }

    /** G4 - the sets a volume step that wants `want` more may add to `sets`: all of them below
     *  the top, what is left of the way to it near it, 0 at or above it. */
    public static int volumeStep(int track, int level, int sets, int want) {
        return stepUnder(volumeTopSets(track, level), sets, want);
    }

    /** The same against a top already known (the hybrid's own, R-25). */
    static int stepUnder(int top, int sets, int want) {
        if (top == Integer.MAX_VALUE) return want;
        return Math.max(0, Math.min(want, top - Math.max(0, sets)));
    }

    /** G4 - the rule a hold carries when a volume step was due and the top stopped it. */
    public static final String VOLUME_TOP_RULE = "volume at the level's top";

    /** G4 - what the card says then: "The volume is at Level 3's top of 14 sets". */
    public static String volumeTopWords(int track, int level) {
        int top = volumeTopSets(track, level);
        if (track == TRACK_LENGTH)
            return "The volume is at its top of " + top + " strain sets";
        if (track == TRACK_GIRTH_TRADITIONAL)
            return "The holds are at Level " + level + "'s top of " + top;
        return "The volume is at Level " + level + "'s top of " + top + " sets";
    }

    /** R-25 - the same for a hybrid's own holds. */
    static String hybridTopWords() {
        return "The hybrid's holds are at their top of " + HYBRID_TOP_HOLDS;
    }

    /** G4 - a hold that follows a volume step the top stopped says so first; any other
     *  decision stands as it is. */
    static Decision atVolumeTop(Decision d, String words) {
        if (d == null || d.action != ACTION_HOLD || words == null) return d;
        return new Decision(ACTION_HOLD, d.tag, VOLUME_TOP_RULE + "; " + d.rule,
            words + "; " + d.reason, Double.NaN, 0);
    }

    /** G4 - the engine's answer with the volume step the top stopped taken out: the yield
     *  runs (and the no-readings fallback) cleared for this one evaluation, then put back.
     *  R-21 (C2) and R-26 use it too: a high-yield arm that would change nothing, and a step
     *  that waits for the next eligible morning, leave the rest of the engine to answer. */
    private static Decision withoutVolumeStep(Inputs in) {
        int low = in.consecutiveLowYield, high = in.consecutiveHighYield,
            none = in.weeksWithoutReadings;
        in.consecutiveLowYield = 0;
        in.consecutiveHighYield = 0;
        in.weeksWithoutReadings = 0;
        try {
            return evaluate(in);
        } finally {
            in.consecutiveLowYield = low;
            in.consecutiveHighYield = high;
            in.weeksWithoutReadings = none;
        }
    }

    /* ===================================================================== *
     *  G3 - THE TIME-BASED FALLBACK WHEN NOTHING IS MEASURED                 *
     *  (the owner's decision, 2026-10-01)                                    *
     * ===================================================================== */

    /** Training weeks with no qualifying reading before the plan steps the volume itself. */
    public static final int NO_READINGS_WEEKS = 4;
    /** What the girth fallback adds - the yield rule's own Level 3/4 step. */
    public static final int NO_READINGS_GIRTH_SETS = 2;
    /** What the length fallback adds - the strain rule's own step. */
    public static final int NO_READINGS_LENGTH_SETS = 1;
    /** The rule a fallback step carries. */
    public static final String NO_READINGS_RULE = "no qualifying readings for "
        + NO_READINGS_WEEKS + " training weeks";

    /** R-25/R-29 - what the fallback adds to traditional girth's holds and to a hybrid's own
     *  holds: one 5-minute hold, the yield rule's own step for them. */
    public static final int NO_READINGS_HOLDS = 1;

    /**
     * G3 - WHETHER THE GIRTH FALLBACK APPLIES: girth at Levels 3 and 4 from month 6, where the
     * yield rule is the only thing that moves the volume (Levels 1 and 2 follow their week
     * tables and calendar), and {@link #NO_READINGS_WEEKS} training weeks have passed with no
     * reading since the last volume change. R-25/R-29 (the owner's decision, 2026-10-01):
     * interval girth and, now, traditional girth and the hybrid's own holds; the tops, the
     * time cap and the return days hold it as they hold the yield step.
     */
    static boolean noReadingsFallback(Inputs in) {
        return (in.track == TRACK_GIRTH_INTERVAL || in.track == TRACK_GIRTH_TRADITIONAL)
            && in.level >= L3 && in.monthIndex >= L2_GATE_MONTH
            && in.weeksWithoutReadings >= NO_READINGS_WEEKS;
    }

    /** G3 - the card's words. R-29: the girth card says "No girth readings for 4 weeks: add 2
     *  sets? Measuring lets the plan adjust to you." ({@link #noReadingsGirthWords}); the
     *  length card keeps "No measurements for 4 weeks — add a strain set? ..." until the
     *  length lane gives it its own. */
    public static String noReadingsWords(int sets, boolean strainSets) {
        if (!strainSets) return noReadingsGirthWords(sets, false);
        String what = sets == 1 ? "a strain set" : sets + " strain sets";
        return "No length readings for " + NO_READINGS_WEEKS + " weeks — add " + what
            + "? Measuring lets the plan adjust to you";
    }

    /** R-29 - the girth fallback's words, naming girth (APP-FIXES 10's wording part), in sets
     *  (interval) or holds (traditional, the hybrid's own). */
    public static String noReadingsGirthWords(int n, boolean holds) {
        String what = holds ? (n == 1 ? "1 hold" : n + " holds")
                            : (n == 1 ? "1 set" : n + " sets");
        return "No girth readings for " + NO_READINGS_WEEKS + " weeks: add " + what
            + "? Measuring lets the plan adjust to you.";
    }

    /** Whether a decision is a hold the level's volume top caused (G4) - the card says it. */
    public static boolean atVolumeTop(Decision d) {
        return d != null && d.action == ACTION_HOLD && d.rule != null
            && d.rule.startsWith(VOLUME_TOP_RULE);
    }

    /* ===================================================================== *
     *  t10 - GIRTH PROGRESSION (the owner's decisions, 30 Sep - 1 Oct 2026)  *
     * ===================================================================== */

    /** The rule a low-yield add carries - named once, so applying it (Mint#commitYield) can
     *  tell an add the yield asked for (R-23's pending add) from the fallback's. */
    public static final String LOW_YIELD_RULE =
        "yield < target for 3 consecutive tracked sessions -> add volume (Net TUP first)";

    /** R-23 - the offer's words (the card is rendered by the Trainer screen). */
    public static final String YIELD_OFFER_WORDS =
        "Your girth readings stayed under 6% after the added sets.";
    /** R-23 - ...when the holds are at their top and a low step is due. */
    public static final String YIELD_OFFER_TOP_WORDS =
        "Your girth readings stayed under 6% and the holds are at their top.";

    /**
     * R-23 (C4/A4, the owner's decision, 2026-10-01): THE OFFER after a low-yield add that did
     * not help, or when the holds are at their top and a low step is due - a week off, or 4
     * weeks of length focus. Nothing in the prescription moves (setsDelta 0); whatever the
     * answer, applying it clears the pending add and restarts the streak
     * (Mint#answerYieldOffer), and "Not now" holds the volume.
     */
    static Decision offerBreak(boolean atTop, boolean hybrid) {
        return new Decision(ACTION_OFFER_BREAK, TAG_INFERRED, YIELD_OFFER_RULE,
            atTop ? YIELD_OFFER_TOP_WORDS : YIELD_OFFER_WORDS, Double.NaN, 0, Double.NaN,
            hybrid, -1, -1, 0, false);
    }

    /** R-60 - what the 90-minute hold says (the card itself is the Trainer screen's). */
    public static final String HELD_AT_90_WORDS =
        "Your both-tracks day would pass 90 min with more holds, so the plan holds here. "
        + "Alternate days keep each session near an hour.";

    /** R-60 (CAP90): a girth volume step the 90-minute day stopped. The volume holds; the rest
     *  of the engine still answers (a pressure step that is due still comes, as the editor's
     *  model has it), and a hold says why. */
    static Decision holdAt90(Decision d) {
        if (d == null || d.action != ACTION_HOLD) return d;
        return new Decision(ACTION_HOLD, TAG_INFERRED, HELD_AT_90_RULE + "; " + d.rule,
            HELD_AT_90_WORDS, Double.NaN, 0);
    }

    /** Whether a decision is a hold the 90-minute day caused (R-60). */
    public static boolean isHeldAt90(Decision d) {
        return d != null && d.action == ACTION_HOLD && d.rule != null
            && d.rule.startsWith(HELD_AT_90_RULE);
    }

    /** Interval girth's base at Levels 3 and 4 - 10 and 14 sets, the milestone's minutes in
     *  2-minute holds - which a high-yield cut never goes under (Mint#yieldFloorSets). */
    static int intervalFloorSets(int level) {
        return (int) Math.round(netMilestoneMin(level) / 2.0);
    }

    /* ---- R-27 (R2): THE TIME-UNDER-PRESSURE CAP PER GIRTH SESSION ------------------------- *
     *  Time under pressure - the holds at the working pressure, the fatigue block included -  *
     *  is capped at 20 / 30 / 36 / 44 minutes at Levels 1-4 (K1), for interval, traditional   *
     *  and the hybrid. Holds the plan would write past it are not run: the pressure rises at   *
     *  the same dose instead, P' = P x (cap + dT) / cap, on the pressure step's own clock     *
     *  (K2, clock A) and at most one step (+1 inHg) a morning; the rest rides on the next      *
     *  steps and is dropped at the top. The same holds never convert twice (a high-water      *
     *  mark, reset at a level change).                                                         */

    /** The cap per level, minutes: Level 1 at [0]. */
    public static final int[] R2_CAP_MIN = { 20, 30, 36, 44 };

    /** The cap at `level`, minutes. */
    public static int r2CapMin(int level) {
        return R2_CAP_MIN[Math.max(L1, Math.min(L4, level)) - 1];
    }

    /** The holds of `holdSec` that fit under `level`'s cap after `fatSec` of fatigue block -
     *  never fewer than 1. Interval (120 s): 10 / 15 / 14 / 18; five-minute holds: 4 / 6 / 5 /
     *  7 (the standard block, 450 s, counted from Level 3). */
    public static int r2MaxHolds(int level, int holdSec, int fatSec) {
        if (holdSec <= 0) return Integer.MAX_VALUE;
        return Math.max(1, (int) Math.floor((r2CapMin(level) * 60.0 - fatSec) / holdSec + 1e-9));
    }

    /** {@link #r2MaxHolds(int,int,int)} from the inputs; Integer.MAX_VALUE when no hold is
     *  given (Inputs#r2HoldSec 0) - no cap. */
    static int r2MaxHolds(Inputs in) {
        if (in == null || in.r2HoldSec <= 0
                || (in.track != TRACK_GIRTH_INTERVAL && in.track != TRACK_GIRTH_TRADITIONAL))
            return Integer.MAX_VALUE;
        return r2MaxHolds(in.level, in.r2HoldSec, in.r2FatSec);
    }

    /** One conversion: from and to whole kPa, the unrounded figure, and whether a limit
     *  stopped it. */
    public static final class R2Conversion {
        public final int from, to;
        public final double raw;
        public final boolean atLimit;
        R2Conversion(int from, int to, double raw, boolean atLimit) {
            this.from = from; this.to = to; this.raw = raw; this.atLimit = atLimit;
        }
    }

    /**
     * R-27: THE SAME DOSE AS PRESSURE. `p0` whole kPa, `capMin` the level's cap, `dTmin` the
     * minutes of the holds not run, `limKpa` the limit ({@link #r2LimitKpa}). P' = P x (cap +
     * dT) / cap to the whole kPa, down if up would pass the limit, never past it, never under
     * P; at or over the limit it holds.
     */
    public static R2Conversion r2Convert(int p0, int capMin, double dTmin, int limKpa) {
        double raw = p0 * (capMin + dTmin) / capMin;
        if (p0 >= limKpa) return new R2Conversion(p0, p0, raw, true);
        int p = (int) Math.round(raw);
        if (p > limKpa) p = (int) Math.floor(raw);
        if (p > limKpa) p = limKpa;
        p = Math.max(p0, p);
        return new R2Conversion(p0, p, raw, p >= limKpa && raw > limKpa);
    }

    /**
     * R-27 (fix a): THE LIMIT A CONVERSION MAY NOT PASS, whole kPa on the plan's own scale -
     * the level's usual top (with the month-1 cap) or, when "Most you will go to" is set above
     * it, the climb's top ({@link Inputs#climbTopKpa}, already within the ceiling and 15
     * inHg); then the ceiling and 15 inHg.
     *
     * THE TOP TO THE WHOLE kPa THE PRESSURE STEP READS IT AT (t10 fix, review B F1): rounded,
     * as step 6 compares round(pressure) with round(cap) - 10 inHg is 33.86 kPa, which the
     * step reaches as 34. Floored, this stopped a conversion at 33, and the step itself never
     * came, so the pressure froze 1 kPa under the usual top. The ceiling and 15 inHg are hard
     * limits and stay floored.
     */
    public static int r2LimitKpa(Inputs in) {
        double top = climbsPastTop(in) ? in.climbTopKpa : usualTopKpa(in);
        int hard = (int) Math.floor(Math.min(in.ceilKpa, ABSOLUTE_CAP_KPA) + 1e-9);
        return Math.min((int) Math.round(top), hard);
    }

    /** R-27's words at a limit. */
    public static final String R2_AT_LIMIT_WORDS =
        "At the time cap and the pressure top: holding.";

    /** R-27's words: "Your session is at its 36-minute cap: instead of 2 more holds, the
     *  pressure goes up 1.0 inHg (it will rise with the next steps)." */
    public static String r2Words(int capMin, int holds, int kpa, boolean rides) {
        return "Your session is at its " + capMin + "-minute cap: instead of " + holds
            + (holds == 1 ? " more hold" : " more holds") + ", the pressure goes up "
            + fmt1(kpa / HG) + " inHg" + (rides ? " (it will rise with the next steps)." : ".");
    }

    /**
     * R-27 - THE TIME CAP'S ANSWER this morning, or null when it has nothing to say. `add` is
     * a volume step's holds that would land wholly past the cap (0 for none) - the plan's own
     * count it keeps when the conversion is applied ({@link Decision#r2AddHolds}); `why` is
     * that step's rule.
     *
     * Holds past the cap not yet converted (above Inputs#r2ExHolds) convert to pressure
     * ({@link #r2Convert}), added to what an earlier conversion still owes (Inputs#r2PendKpa).
     * On a morning the pressure step may come - three training weeks at the pressure, not a
     * return day - one step (+1 inHg) rises, labelled as the cap's; the rest rides on the next
     * steps. On any other morning a new conversion is said as a hold, and an older one leaves
     * the rest of the engine to answer. At the limit nothing is owed, and a low-yield add that
     * cannot run is offered as R-23's break instead (the volume can grow no further in time or
     * pressure).
     *
     * AT THE LIMIT THE REGULAR STEP ANSWERS (t10 fix, review B F1). The conversion has nowhere
     * to go, but the pressure step still runs: its answer stands, and only when it holds too is
     * the hold said as the cap's - carrying the holds past it, so applying it keeps the mark
     * (Mint#commitUnchanged) and the same holds never ask again. A fallback step at the limit
     * is that hold with its hold kept in the plan's count, so applying it restarts its clock
     * and the next one waits its 4 weeks.
     */
    static Decision r2Step(Inputs in, int add, String why, boolean fromYield) {
        int max = r2MaxHolds(in);
        if (max == Integer.MAX_VALUE) return null;
        boolean hybrid = in.track == TRACK_GIRTH_INTERVAL && in.hybridHolds > 0;
        int holds = (hybrid ? in.hybridHolds : in.workSets) + Math.max(0, add);
        int excess = Math.max(0, holds - max);
        int newly = Math.max(0, excess - Math.max(0, in.r2ExHolds));
        int owed = Math.max(0, in.r2PendKpa);
        if (newly == 0 && owed == 0) return null;
        int cap = r2CapMin(in.level);
        int lim = r2LimitKpa(in);
        int p0 = (int) Math.round(in.pressureKpa);
        if (newly > 0)
            owed += r2Convert(p0, cap, newly * in.r2HoldSec / 60.0, lim).to - p0;
        if (p0 >= lim) owed = 0;                     // at the top it has nowhere to go
        String rule = R2_RULE + (why == null ? "" : "; " + why);
        if (owed <= 0) {
            if (newly == 0) return null;
            if (fromYield) return offerBreak(true, hybrid);
            Decision step = loadStep(in);
            if (add <= 0 && step.action != ACTION_HOLD) return step;
            return new Decision(ACTION_HOLD, TAG_ADAPTED, rule + "; " + step.rule,
                R2_AT_LIMIT_WORDS, Double.NaN, 0, Double.NaN, hybrid, excess, 0,
                Math.max(0, add), false);
        }
        boolean stepDue = !in.gentleReturnOpen && !in.returnRunsUnder
            && in.trainingWeeksAtPressure >= PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        if (stepDue) {
            double next = Math.min(in.pressureKpa + STEP_HG_KPA, lim);
            int moved = (int) Math.round(next) - p0;
            if (moved > 0) {
                int left = (int) Math.round(next) >= lim ? 0 : Math.max(0, owed - moved);
                return new Decision(ACTION_RAISE_PRESSURE, TAG_ADAPTED, rule,
                    r2Words(cap, newly > 0 ? newly : excess, moved, left > 0), next,
                    0, Double.NaN, hybrid, excess, left, Math.max(0, add), false);
            }
        }
        if (newly == 0) return null;   // an older conversion still owed: the steps carry it
        return new Decision(ACTION_HOLD, TAG_ADAPTED, rule, r2Words(cap, newly, owed, true),
            Double.NaN, 0, Double.NaN, hybrid, excess, owed, Math.max(0, add), false);
    }

    /** G2 - the track's usual top for the plan's own figure: girth's level/month cap, or
     *  length's own (6 inHg in the first month, then 10) - what Scale#usualTopKpa says. */
    static double usualTopKpa(Inputs in) {
        if (in.track == TRACK_LENGTH)
            return Math.min(in.newToPumping && in.monthIndex < 1 ? LENGTH_MONTH1_CAP_KPA
                                                                 : LENGTH_SOFT_CAP_HI_KPA,
                            ABSOLUTE_CAP_KPA);
        return pressureCapKpa(in.level, in.monthIndex, in.newToPumping);
    }

    /** G2 - whether the plan's pressure step goes on past the usual top to the person's own
     *  maximum ({@link Inputs#climbTopKpa}). */
    public static boolean climbsPastTop(Inputs in) {
        return in != null && !Double.isNaN(in.climbTopKpa)
            && in.climbTopKpa > usualTopKpa(in) + 1e-9;
    }

    /** S11 - the length track's deload week, as the rule and as the card says it. */
    public static final String LENGTH_DELOAD_RULE =
        "deload week -> length rests: no session, no traction (the guidance: rest, or light "
        + "retention only); normal load after the week";
    public static final String LENGTH_DELOAD_REASON =
        "deload week — no length session and no traction this week: the guidance "
        + "asks for rest, or light retention only. The pulls come back at your normal load "
        + "when the week ends";

    /* ===================================================================== *
     *  THE ENGINE — §9 order of operations, VERBATIM precedence.            *
     *                                                                       *
     *  RED -> deload/step-back                                              *
     *  (event step-backs: layoff, under-delivery)                           *
     *  (usage-linked deload week)                                           *
     *  yield<target x3 -> add volume (Net TUP FIRST — volume before load)   *
     *  net>=milestone  -> raise pressure (+1hg/3wk, to cap; deadlock-aware)  *
     *  else            -> hold (with the reason it is holding)              *
     * ===================================================================== */
    public static Decision evaluate(Inputs in) {
        if (in == null) {
            return hold(TAG_SOURCE, "no inputs", "absence of data = hold");
        }
        // t10-K (B1): the month-12 break - both tracks and the feeders rest until it ends.
        if (in.onBreak) return MonthBreak.breakHold();

        // Feeder is its own machine — no yield, no net gate; L3+ only; paused on
        // deload/safety; pressure derived from main (plan round-2 feeder ruling).
        if (in.track == TRACK_FEEDER) {
            return evaluateFeeder(in);
        }

        /* t10 parity run 2, O-1 - ONE GIRTH CHANGE A MORNING, IN THE LIVE APP TOO. The engine
         * answers one decision an evaluation, but every Trainer render evaluates again, so an
         * add, a cut, the fallback or the time cap's conversion applied on one render was
         * followed by the pressure step on the next - the same morning. Once the plan has
         * changed the girth work today, or the level moved (Inputs#girthChangedToday), the
         * engine's answer is asked as ever and a further step holds until the next morning
         * (#oneGirthChangeAMorning). An offer the person answered is not a change of the work. */
        if (in.girthChangedToday
                && (in.track == TRACK_GIRTH_INTERVAL || in.track == TRACK_GIRTH_TRADITIONAL)) {
            in.girthChangedToday = false;
            try {
                return oneGirthChangeAMorning(evaluate(in));
            } finally {
                in.girthChangedToday = true;
            }
        }

        // 1. SAFETY (plan-wide). RED steps back immediately; a session run anyway does not
        //    count. Highest precedence, above even a due deload.
        if (in.redFlag) {
            return new Decision(ACTION_STEP_BACK, TAG_SOURCE,
                "readiness == RED -> deload, step back 1–2 wks",
                "safety flag active — plan steps back immediately, all tracks",
                Double.NaN, 0);
        }

        // 2. Layoff ≥7d -> guide's step-back (one week, two to be sure). VERIFIED in §11 item 16.
        if (in.layoff) {
            return new Decision(ACTION_STEP_BACK, TAG_SOURCE,
                "layoff >= 7d -> step back 1 wk (2 to be sure)",
                "A break of a week or more: you re-enter 1–2 weeks back.",
                Double.NaN, 0);
        }

        // 3. Persistent Net-TUP under-delivery -> step-back proposal (the guidance "plan ahead
        //    of the tissue"). No gate; a diagnostic-driven proposal.
        if (in.underDelivery) {
            return new Decision(ACTION_STEP_BACK, TAG_INFERRED,
                "persistent Net-TUP under-delivery -> step back (plan ahead of the tissue)",
                "Your time at pressure stayed under the plan's target.",
                Double.NaN, 0);
        }

        // 4. Deload. A deload week in progress freezes counters and feeds nothing; a
        //    usage-linked deload comes due after 4 accumulated training weeks on the FIRST
        //    cycle (the guidance L1 wks 1-4), then every 3 thereafter (via firstDeloadPending).
        if (in.inDeloadWeek) {
            /* S11 (the owner's decision, 2026-09-26): THE LENGTH TRACK RESTS FOR THE WEEK -
             * no traction, and no length session at all. The guidance describes a deload
             * week as rest: complete rest or pulse stretches, rest or light retention only,
             * complete rest on training days, and one week off in every four. None of that
             * is a pump
             * session, so the length session keeps none of its parts - the
             * expansion coda included. Normal load after the week: the owner ruled out a
             * load taper, and the traction blocks never took the pressure taper anyway
             * (RxBuild#tractionRoutineFromRx), so nothing about the return changes. */
            if (in.track == TRACK_LENGTH)
                return new Decision(ACTION_DELOAD, TAG_SOURCE, LENGTH_DELOAD_RULE,
                    LENGTH_DELOAD_REASON, Double.NaN, 0);
            return new Decision(ACTION_DELOAD, TAG_INFERRED,
                "deload week — counters frozen, light or no pumping",
                "Sessions this week are logged as usual; the plan's week count pauses.",
                Double.NaN, 0);
        }
        /* 4a. EARLY TARGET -> DELOAD, and it sits in the DELOAD tier rather than the volume
         *     one on purpose.
         *
         *     Hitting the whole session's yield target in its first third looks like a
         *     high-yield session, and the volume tier's answer to high yield is to cut sets.
         *     Both rules can be true at once and they prescribe different things: cutting
         *     sets leaves somebody training a tissue that is already giving way too easily,
         *     with a shorter session. The rest is what it needs, so the deload wins - and
         *     SelfTest pins that ordering, because the two firing together is the ordinary
         *     case rather than the exotic one. */
        if (Plan.earlyTarget(in.targetHitFraction)) {
            return new Decision(ACTION_DELOAD, TAG_INFERRED,
                "yield target met inside the first "
                + Math.round(EARLY_TARGET_FRACTION * 100) + "% of the sets, "
                + EARLY_TARGET_DEBOUNCE + " sessions running -> deload",
                "you are reaching the whole session's target in its first third — that is "
                + "the tissue giving way early, and it wants rest rather than more work",
                Double.NaN, 0);
        }
        if (deloadDue(in.accumulatedTrainingWeeks, in.firstDeloadPending, in.monthIndex)) {
            int need = deloadNeedWeeks(in.firstDeloadPending, in.monthIndex);
            return new Decision(ACTION_DELOAD, TAG_INFERRED,
                CADENCE_DELOAD_RULE_HEAD + need + " accumulated training weeks (usage-linked; "
                + "4, then 3 through the dated table, then 4 again from month "
                + DELOAD_BACK_TO_FOUR_MONTH + ")",
                need + " training weeks since last deload — take a deload week",
                Double.NaN, 0);
        }

        /* R-23 (the merge's open item 5): GIRTH RESTS FOR THE OFFER'S 4 WEEKS OF LENGTH FOCUS.
         * Only safety and the deload tier above apply while it runs, as a girth-focus block
         * holds the length ladder: no level-up, volume or pressure proposal for a track the
         * person has put down. Its clocks need nothing - the position, the pressure clock and
         * the no-readings count all count girth's own training weeks, and none run while it
         * rests; the month is the plan's calendar and runs on, as it does for every track. */
        if (in.girthResting
                && (in.track == TRACK_GIRTH_INTERVAL || in.track == TRACK_GIRTH_TRADITIONAL))
            return hold(TAG_INFERRED, GIRTH_REST_HOLD_RULE,
                "4 weeks of length focus are running: the girth plan holds until they end");

        // S20: this used to say length is static (no progression, no yield gate) with only
        // a monthly pressure creep - true once, no longer true of evaluateLength below,
        // which runs its own ladder: calendar-driven volume and load rungs before month
        // LENGTH_METRICS_FROM_MONTH, then strain/fatigue-gated load and volume moves, a
        // girth-focus trigger, and its own calendar level gates. What IS still static is
        // the YIELD GATE girth uses (length has no yield metric) and the coda's pressure
        // creep, which is the smallest move on this ladder and runs last, below everything
        // that changes the traction work. Kept as a pointer to evaluateLength rather than
        // restated here, so the two cannot drift apart the way this comment did.
        if (in.track == TRACK_LENGTH) {
            // t10-K (B3/B5): as the month-12 break's return lets the ladder's answer stand - and
            // then one length change a morning (t10 parity run 2, A-3).
            return oneLengthChangeAMorning(in, MonthBreak.lengthAfter(in, evaluateLength(in)));
        }

        // 4.5 LEVEL UP. A met level gate is a progression EVENT — propose crossing to the
        //     next level. Placed after the safety/step-back/deload tier (a crossing must not
        //     fire while a flag is active or a deload is due) and BEFORE the volume/pressure
        //     tier, because moving to a new level supersedes a same-cycle volume tweak or a
        //     pressure raise that would only be capped at this level anyway. Uses the existing
        //     gate queries — the caller feeds a PER-SESSION net (see Inputs.netTupMin).
        int nextLevel = gateMetNextLevel(in);
        if (nextLevel != 0) {
            /* NOT WHILE THE GENTLE RETURN STILL RUNS UNDER (the owner's decision,
             * 2026-09-27). A met gate on a reduced day back from a rest used to propose the
             * next level that morning - a new routine, and at L1 -> L2 the level's 8 hg
             * floor, on the day the plan is deliberately bringing the tissue back. The
             * proposal waits and comes on the first full day. A hold, because the crossing
             * outranks the volume and pressure tiers below it anyway. */
            if (in.returnRunsUnder)
                return levelUpWaitsForReturn(nextLevel);
            return levelUpDecision(in.track, in.level, nextLevel);
        }

        // 5. VOLUME BEFORE LOAD. The whole volume tier (both the low-yield add AND the
        //    high-yield pause/reduce) outranks the pressure branch even when net>=milestone
        //    is also true — the §9 order is explicit. The tier opens at L1 WEEK 10 (§7's
        //    calendar engine and the L1 table's own "yield tracking begins"), not at L2 —
        //    see yieldTierOpen for why that reading won over §1.4's role table.
        // G4: set when a volume step was due but the level's top stopped it.
        String atTop = null;
        /* R-25 (A3/C7): traditional girth and the hybrid step ONE 5-minute hold at a time, and
         * a hybrid's steps move its own holds (Inputs#hybridHolds), never the hidden interval
         * count. */
        boolean hybrid = in.track == TRACK_GIRTH_INTERVAL && in.hybridHolds > 0;
        boolean oneHold = hybrid || in.track == TRACK_GIRTH_TRADITIONAL;
        int holds = hybrid ? in.hybridHolds : in.workSets;
        int top = hybrid ? HYBRID_TOP_HOLDS : volumeTopSets(in.track, in.level);
        String topWords = hybrid ? hybridTopWords() : volumeTopWords(in.track, in.level);
        /* R-26 (fix d; APP-FIXES 12): a level-up and a volume step never land on the same
         * morning, and neither the yield add nor the fallback lands on the return days after a
         * deload - the step waits for the next eligible morning, and the rest of the engine
         * answers meanwhile. */
        /* t10 parity run 2, A-4: the days before a week off answered for a later day are not
         * return days (Inputs#weekOffAhead) - the return is armed at the answer, and read as
         * one it held the owner's offer (R-23) at the L4 top until after the week off. */
        boolean stepWaits = in.levelUpToday
            || ((in.gentleReturnOpen || in.returnRunsUnder) && !in.weekOffAhead);
        // R-27: the holds the session's time cap lets run (Integer.MAX_VALUE: no cap given).
        int r2Max = r2MaxHolds(in);
        if (yieldTierOpen(in.track, in.level, in.weekIndex) && in.hasYieldData) {
            // 5a. yield < target x3 consecutive -> add volume (Net TUP first).
            if (debounceTriggered(in.consecutiveLowYield) && !stepWaits) {
                // METRIC +2 main sets; CALENDAR +1; one 5-minute hold (traditional, hybrid).
                int want = oneHold ? 1 : (in.level >= L3) ? 2 : 1;
                // G4: never past the level's top - the last step lands on it.
                int delta = stepUnder(top, holds, want);
                /* R-23 (C4/A4, the owner's decision, 2026-10-01): AN ADD THAT DID NOT HELP IS
                 * NOT REPEATED. Three more readings under the target after a low-yield add -
                 * or no room left for one - and the plan offers the guidance's next step as a
                 * decision instead: a week off, or 4 weeks of length focus. */
                if (in.addPending || delta <= 0)
                    return offerBreak(delta <= 0, hybrid);
                // R-60 (CAP90): the both-tracks day would pass 90 minutes - the volume holds.
                if (in.heldAt90) return holdAt90(withoutVolumeStep(in));
                // R-27: none of the step would run under the time cap - pressure instead.
                if (holds >= r2Max) {
                    Decision r2 = r2Step(in, delta, LOW_YIELD_RULE, true);
                    if (r2 != null) return r2;
                }
                return new Decision(ACTION_ADD_VOLUME, TAG_SOURCE, LOW_YIELD_RULE,
                    "Your girth readings stayed low — volume rises before pressure.",
                    Double.NaN, delta).forHybrid(hybrid);
            }
            // 5b. yield > hi target consistently -> PAUSE additions (L2) / REDUCE volume
            //     (L3/L4). Must sit in the volume tier so a calendar "+1 set due" can never
            //     override it.
            /* R-20 (C1, the owner's decision, 2026-10-01): LEVEL 1 HAS NO HIGH ARM. Its yield is
             * a floor only - three highs neither pause nor cut there, and never hold up its
             * pressure tier. (It used to pause, and before that to cut two sets.) */
            if (debounceTriggered(in.consecutiveHighYield) && in.level >= L2
                    && !in.levelUpToday) {
                /* L2 PAUSES (O3: the app's own arm, at the new 12 %): it takes back what yield
                 * added (the owner's decision, 2026-09-26) - the kept yield sets go back to
                 * none, which leaves the table's own count. */
                if (in.level == L2) {
                    if (in.yieldSets > 0)
                        return new Decision(ACTION_PAUSE_VOLUME, TAG_SOURCE,
                            "yield > " + pct(yieldTargetHi(in.level))
                            + " consistently -> pause volume additions",
                            "Your girth readings stayed high — the sets they added come off.",
                            Double.NaN, -in.yieldSets);
                } else {
                    /* L3/L4: two sets fewer, never under the level's base of 10 / 14; one hold
                     * fewer on traditional and the hybrid (R-25), never under what the
                     * calendar or the guidance gives the level. */
                    int cut;
                    if (oneHold)
                        cut = Math.min(1, Math.max(0, hybrid ? in.hybridYield : in.yieldSets));
                    else
                        cut = in.workSets > 0
                            ? Math.min(2, Math.max(0, in.workSets - intervalFloorSets(in.level)))
                            : 2;
                    if (cut > 0)
                        return new Decision(ACTION_REDUCE_VOLUME, TAG_SOURCE,
                            "yield > " + pct(yieldTargetHi(in.level))
                            + " consistently -> reduce volume",
                            oneHold ? "Your girth readings stayed high — one hold fewer."
                                    : "Your girth readings stayed high — fewer main sets.",
                            Double.NaN, -cut).forHybrid(hybrid);
                }
                /* R-21 (C2, the owner's decision, 2026-10-01): A HIGH-YIELD ANSWER THAT WOULD
                 * CHANGE NOTHING FALLS THROUGH. A pause with no yield sets to take back, or a
                 * cut at the floor, used to return before the pressure tier and hold the
                 * pressure for as long as the readings stayed high. The pressure tier answers
                 * instead, as withoutVolumeStep does; applying its answer restarts the streak,
                 * so the arm needs three new readings before it is asked again. */
                return withoutVolumeStep(in).restarting();
            }
        }

        // 5c. G3 (the owner's decision, 2026-10-01) - NOTHING MEASURED FOR FOUR TRAINING WEEKS
        //     where the yield rule is the only thing that moves the volume (girth at Levels 3
        //     and 4): the plan offers +2 sets itself (one hold on traditional and the hybrid,
        //     R-25), saying why. Accepted, the yield streak restarts (Mint#commitYield), and so
        //     does this count; any reading hands the volume back to the yield rule. Never past
        //     the level's top (G4), never on a level-up morning or a return day (R-26), and
        //     under the time cap the holds become pressure (R-27).
        if (noReadingsFallback(in) && !stepWaits) {
            int want = oneHold ? NO_READINGS_HOLDS : NO_READINGS_GIRTH_SETS;
            int delta = stepUnder(top, holds, want);
            if (delta > 0) {
                if (in.heldAt90) return holdAt90(withoutVolumeStep(in));
                String rule = NO_READINGS_RULE + " -> +" + want + (oneHold ? " hold" : " sets");
                if (holds >= r2Max) {
                    Decision r2 = r2Step(in, delta, rule, false);
                    if (r2 != null) return r2;
                }
                return new Decision(ACTION_ADD_VOLUME, TAG_INFERRED, rule,
                    noReadingsGirthWords(delta, oneHold), Double.NaN, delta).forHybrid(hybrid);
            }
            if (atTop == null) atTop = topWords;
        }

        // G4: a volume step the level's top stopped - the rest of the engine answers as if
        // it had not been due, and a hold says the volume is at the level's top.
        if (atTop != null) return atVolumeTop(withoutVolumeStep(in), atTop);

        // 5d. R-27 (R2, the owner's decision, 2026-10-01): HOLDS THE PLAN WRITES PAST THE
        //     SESSION'S TIME CAP - the calendar's, a table row's, carried ones - run as
        //     pressure instead, on the pressure step's own clock.
        Decision r2 = r2Step(in, 0, null, false);
        if (r2 != null) return r2;

        return loadStep(in);
    }

    /** Steps 6 and 7 of {@link #evaluate} - the pressure step, else the hold - asked on their
     *  own by the time cap at its limit too (R-27, Plan#r2Step). */
    private static Decision loadStep(Inputs in) {
        // 6. LOAD. net>=milestone -> raise pressure +1 hg per 3 training weeks, to cap,
        //    ceiling-deadlock aware.
        /* TRADITIONAL GIRTH HOLDS ITS OWN TIME (the owner's decision, 2026-09-26). Its
         * 2 x 5 min is 10 minutes, and the interval milestone (20 at L1) is a figure it can
         * never reach, so its pressure could never move. For traditional the time condition
         * is each of the last three scored sessions delivering its own routine's planned
         * minutes at pressure; every other condition below is the same. */
        boolean traditional = in.track == TRACK_GIRTH_TRADITIONAL;
        boolean timeHeld = traditional ? in.ownTargetsMet
                                       : netMilestoneReached(in.level, in.netTupMin);
        String held = traditional ? "each session held its planned time"
                                  : "net milestone met";
        /* LEVEL 1 FOLLOWS THE GUIDANCE TABLE'S PRESSURE (the owner's decision, 2026-09-27).
         * Before the 20-minute milestone the pressure used to wait, so Level 1 ran to month
         * 6; the guidance raises it while the sets are still being added. While the table
         * position is in the volume phase, the working pressure follows the Level 1 row's
         * pressure - see followL1TablePressure for every condition it keeps. */
        if (!timeHeld && in.track == TRACK_GIRTH_INTERVAL && in.level == L1) {
            Decision t = followL1TablePressure(in);
            if (t != null) return t;
        }
        if (timeHeld) {
            /* THE CAP, AT THE RESOLUTION A PRESCRIPTION LIVES AT. Same defect as
             * pressureReachedHg and a worse symptom: the L1 cap is 27.09 kPa, a prescription
             * rounds to 27, so "at cap" read false at the cap - the plan offered "raise
             * +1 hg", the user accepted, the mint rounded the new figure straight back to 27,
             * and the same offer came round again. A live-lock that looks like progress. */
            double cap = pressureCapKpa(in.level, in.monthIndex, in.newToPumping);
            /* G2 (the owner's decision, 2026-10-01): WITH THE PERSON'S OWN MAXIMUM ABOVE THE
             * USUAL TOP, the same step goes on to that maximum instead of stopping at the
             * cap - the ceiling and 15 inHg are already inside the climb's top. */
            boolean climb = climbsPastTop(in);
            if (climb) cap = in.climbTopKpa;
            if (Math.round(in.pressureKpa) >= Math.round(cap)) {
                if (climb)
                    return hold(TAG_INFERRED, "at the most you will go to",
                        held + " and the pressure has reached the most you said you will "
                        + "go to — hold");
                return hold(TAG_SOURCE, "at pressure cap for this level/month",
                    held + " but pressure is already at the "
                    + capHgText(cap) + " cap — hold");
            }
            /* NOT WHILE THE GENTLE RETURN IS OPEN (the owner's decision, 2026-09-26). The
             * pressure clock no longer restarts at every taper step, so without this a
             * step could land on the first day back from a deload - the one day the plan is
             * deliberately running UNDER the working pressure. The step waits until the
             * return has closed on a full-pressure day. */
            if (in.gentleReturnOpen) {
                return hold(TAG_INFERRED, "gentle return still open -> no pressure step",
                    held + "; the next pressure step waits until the gentle return "
                    + "after your rest has finished");
            }
            if (in.trainingWeeksAtPressure < PRESSURE_RAISE_EVERY_TRAINING_WEEKS) {
                return hold(TAG_SOURCE, "accumulating training weeks toward the +1hg raise",
                    held + "; "
                    + in.trainingWeeksAtPressure + " of 3 training weeks at this pressure");
            }
            if (ceilingDeadlock(in.pressureKpa, in.ceilKpa, in.level, in.monthIndex,
                                in.newToPumping)) {
                return new Decision(ACTION_CEILING_DEADLOCK, TAG_SOURCE,
                    "device ceiling below the plan's next pressure step",
                    "raise the device ceiling in Settings to progress, or hold here",
                    Double.NaN, 0);
            }
            /* R11-4 - GAINING WHILE ON TARGET, THE STEP COMES EVERY 6 TRAINING WEEKS. When the
             * normal 3 are up and the braked 6 not, the plan says the step waits (the card
             * offers it: "Step up now" gives this very step). Never anything added for gains. */
            if (in.gainBrake && !in.brakeStepUp
                    && in.trainingWeeksAtPressure < GainBrake.GIRTH_WEEKS)
                return GainBrake.hold(GainBrake.KIND_PRESSURE);
            double next = Math.min(in.pressureKpa + STEP_HG_KPA, cap);
            // L1/L2 milestones (20/20) are source-stated; L3/L4 (20/28) are this file's
            // derivation from the stated main-block minutes -> tag the decision DERIVED.
            // Traditional's own-time condition is the app's (the owner's decision) -> INFERRED.
            int raiseTag = traditional ? TAG_INFERRED : (in.level >= L3) ? TAG_DERIVED : TAG_SOURCE;
            // G2: a step past the usual top is the person's own maximum's, and says so.
            if (next > pressureCapKpa(in.level, in.monthIndex, in.newToPumping) + 1e-9)
                return new Decision(ACTION_RAISE_PRESSURE, TAG_INFERRED,
                    "the same +1 hg / 3 training wks step, past the usual top to the most "
                    + "you will go to",
                    "3 training weeks at pressure and " + held + " — raise +1 hg, past the "
                    + "usual top toward the most you said you will go to",
                    next, 0);
            return new Decision(ACTION_RAISE_PRESSURE, raiseTag,
                traditional
                    ? "each of the last 3 sessions held its planned minutes -> raise "
                      + "pressure +1 hg / 3 training wks, to cap"
                    : "net >= milestone -> raise pressure +1 hg / 3 training wks, to cap",
                "3 training weeks at pressure and " + held + " — raise +1 hg",
                next, 0);
        }

        // 7. HOLD — the default and, on missing yield data at L3+, the meta-rule's answer.
        if (!in.hasYieldData && in.level >= L3) {
            return hold(TAG_SOURCE, "no yield data at L3+ (metric engine)",
                "waiting on tracked yield readings — absence of data = hold");
        }
        return hold(TAG_SOURCE, "no progression signal fired",
            "hold — nothing above target/milestone/debounce triggered");
    }

    /**
     * LEVEL 1'S VOLUME PHASE: THE WORKING PRESSURE FOLLOWS THE GUIDANCE'S TABLE (the owner's
     * decision, 2026-09-27) - interval girth, Level 1, the 20-minute milestone not yet held.
     * Returns null when there is nothing to follow (past the volume phase, or the row's
     * pressure already reached - {@link #tablePressureReached}: less than one whole kPa below
     * it, or at or above it; it only ever goes up, and never lowers a pressure somebody
     * already has), and the evaluation goes on as before.
     *
     * The safety conditions, each pinned by LevelOneTablePressureTest: it is reached only
     * after the safety flag, layoff, under-delivery, deload-week, deload-due and early-target
     * tiers have had their say (so never under a flag or in a deload week); never while the
     * gentle return is open; never above the level/month cap (6 hg in month 0, 8 hg at
     * Level 1), the device ceiling or the user's maximum; at most 1 hg per step and one step
     * per evaluation; and only once each of the last 3 scored sessions at the CURRENT
     * pressure delivered its own planned minutes.
     */
    static Decision followL1TablePressure(Inputs in) {
        double table = l1TablePressureKpa(in.weekIndex);
        if (Double.isNaN(table)) return null;
        double cap = pressureCapKpa(L1, in.monthIndex, in.newToPumping);
        double target = Math.min(table, cap);
        if (tablePressureReached(in.pressureKpa, target)) return null;
        String row = "the guidance's Level 1 table gives " + capHgText(table)
            + " at this point of the plan";
        if (in.gentleReturnOpen) {
            return hold(TAG_INFERRED, "gentle return still open -> no pressure step",
                row + "; the step waits until the gentle return after your rest has finished");
        }
        if (!in.ownTargetsMetAtPressure) {
            return hold(TAG_SOURCE,
                "Level 1 table pressure waits for 3 sessions at their planned minutes",
                row + "; the pressure follows once each of your last 3 sessions at this "
                + "pressure held its planned minutes");
        }
        double next = Math.min(in.pressureKpa + STEP_HG_KPA, target);
        if (in.ceilKpa + 1e-9 < Math.round(next)) {
            return new Decision(ACTION_CEILING_DEADLOCK, TAG_SOURCE,
                "device ceiling below the plan's next pressure step",
                "raise the device ceiling in Settings to progress, or hold here",
                Double.NaN, 0);
        }
        return new Decision(ACTION_RAISE_PRESSURE, TAG_SOURCE,
            "Level 1 volume phase -> pressure follows the guidance's table, "
            + "at most +1 hg a step, never down",
            "each of your last 3 sessions held its planned minutes, and " + row
            + " — follow it", next, 0);
    }

    /** The next level whose gate the current inputs meet, or 0 when none. Calls the existing
     *  gate queries; L1→L2 needs the per-session Net TUP milestone AND the prescribed pressure
     *  reached 8 hg (never raw sensor equality — {@link #pressureReachedHg}); L2→L3 needs
     *  month 6. L3→L4 is the branching A|C fork (the guidance) and is deferred to its own choice
     *  screen — never auto-crossed here. */
    private static int gateMetNextLevel(Inputs in) {
        if (in.level == L1) {
            /* The whole kPa the plan's figure commands: the figure is kept exact (t10 REAL-15),
             * and 26.77 is the 27 that reaches 8 inHg on the wire, as the editor's model reads
             * it - its own figure would never reach 27 under the 27.09 cap. */
            boolean reached8 = pressureReachedHg(Math.round(in.pressureKpa),
                                                 L1_GATE_PRESSURE_HG);
            if (in.track == TRACK_GIRTH_TRADITIONAL)
                return gateL1toL2Traditional(in.ownTargetsMet, reached8,
                                             in.gateHeldTrainingWeeks) ? L2 : 0;
            return gateL1toL2(in.netTupMin, reached8, in.gateHeldTrainingWeeks) ? L2 : 0;
        }
        if (in.level == L2) {
            return gateL2toL3(in.monthIndex,
                levelVolumeHeld(in.track, L2, in.netTupMin, in.ownTargetsMet)) ? L3 : 0;
        }
        /* L3 -> L4, WHICH THE ENGINE COULD NEVER PROPOSE. gateL3toL4 was written, tested
         * and called by nothing: the method returned 0 for every level above L2, so
         * somebody a year into Level 3 was simply never told they had reached the gate.
         *
         * IT IS A FORK, NOT A LADDER RUNG, and that is probably why it was left. The guide
         * offers two answers at month 12 - continue on girth (option A), or take a 4-6 week
         * break and re-enter lower (option C) - and only the first is a level-up. The
         * engine proposes the one it can express; the decision's own reason names the other
         * so the choice is stated rather than hidden, and the break is reachable where
         * every other deliberate pause already is. */
        // t10-K (B2): after the month-12 break, 4 counted training weeks back first.
        if (in.level == L3 && in.l4Waits) return 0;
        if (in.level == L3) {
            return gateL3toL4(in.monthIndex,
                levelVolumeHeld(in.track, L3, in.netTupMin, in.ownTargetsMet)) ? L4 : 0;
        }
        return 0;
    }

    /**
     * THE LENGTH TRACK'S OWN CROSSING, IN ITS OWN WORDS.
     *
     * It borrowed {@link #levelUpDecision}, which is written for girth - so a length user
     * reaching month 3 was told "Net TUP >= 20 AND pressure reached 8 hg", two conditions
     * the length track has no gate on and no way to read. Its gates are the calendar
     * (3 / 6 / 12), which is what the card should say; the month-12 fork is the same fork,
     * with the length track's own two alternatives, which the card already draws.
     */
    private static Decision lengthLevelUpDecision(int from, int to, int monthIndex) {
        int gateMonth = from == L1 ? 3 : from == L2 ? 6 : 12;
        String lvl = "Level " + to;      // L1..L4 are 1..4 - the label is the number
        if (from == L3) {
            return new Decision(ACTION_LEVEL_UP, TAG_INFERRED,
                "length L3→L4 gate met: month 12 reached -> move to " + lvl + ", OR take a "
                + "girth block or weeks off length",
                "month 12 — move up to " + lvl + ", or take a girth block or a "
                + MonthBreak.BREAK_WEEKS_WORDS + " instead.", Double.NaN, 0);
        }
        return new Decision(ACTION_LEVEL_UP, TAG_INFERRED,
            "length gate met: month " + gateMonth + " reached -> move to " + lvl,
            "month " + gateMonth + " reached — move up to " + lvl
            + ". Length levels are time, not a number to push.", Double.NaN, 0);
    }

    private static Decision levelUpDecision(int track, int from, int to) {
        if (from == L1 && track == TRACK_GIRTH_TRADITIONAL) {
            return new Decision(ACTION_LEVEL_UP, TAG_INFERRED,
                "traditional L1→L2 gate met: each of the last 3 sessions held its planned "
                + "minutes AND pressure reached 8 hg, held 2 weeks -> move to Level 2",
                "gate met — move up to Level 2 (each session held its planned time at 8 hg)",
                Double.NaN, 0);
        }
        if (from == L1) {
            return new Decision(ACTION_LEVEL_UP, TAG_SOURCE,
                "L1→L2 gate met: Net TUP >= 20 AND pressure reached 8 hg -> move to Level 2",
                "gate met — move up to Level 2 (net 20 and 8 hg reached)", Double.NaN, 0);
        }
        boolean trad = track == TRACK_GIRTH_TRADITIONAL;
        String held = trad ? "each of the last 3 sessions held its planned minutes"
            : "each of the last 3 sessions held " + Math.round(levelExitNetMin(from)) + " min";
        if (from == L3) {
            /* THE FORK, SAID OUT LOUD. The guide gives month 12 two answers and only one of
             * them is a level-up, so the reason names the other rather than letting the
             * absence of a button be the whole story. Taking the break is Steer the plan >
             * pause, which is where every other deliberate stop already lives. Both
             * conditions hold before the fork is offered (the owner's decision, 2026-09-27). */
            return new Decision(ACTION_LEVEL_UP, TAG_INFERRED,
                "L3→L4 gate met: month 12 reached AND " + held + " -> move to Level 4, OR "
                + "take a 4-week break on both tracks and come back at L3",
                "month 12 and your sessions hold the volume — move up to Level 4, or take a "
                + "4-week break on both tracks and come back at Level 3. Your volume carries "
                + "either way.",
                Double.NaN, 0);
        }
        // L2→L3 rests on the month index (audit ruling: effective-weeks/4, inferred) and the
        // app's own exit volume -> INF.
        return new Decision(ACTION_LEVEL_UP, TAG_INFERRED,
            "L2→L3 gate met: month 6 reached AND " + held
            + " -> move to Level 3 (achieved volume carries)",
            "gate met — move up to Level 3 (month 6, and your sessions hold the volume; "
            + "it carries)", Double.NaN, 0);
    }

    private static Decision evaluateFeeder(Inputs in) {
        if (!feederEligible(in.level)) {
            return new Decision(ACTION_DISABLED, TAG_SOURCE,
                "feeder is L3+ only", "recalibration below L3 auto-disables feeder",
                Double.NaN, 0);
        }
        if (in.redFlag) {
            return new Decision(ACTION_FEEDER_PAUSED, TAG_INFERRED,
                "feeder paused while the plan-wide safety flag is active",
                "safety flag active", Double.NaN, 0);
        }
        if (in.inDeloadWeek
                || deloadDue(in.accumulatedTrainingWeeks, in.firstDeloadPending, in.monthIndex)) {
            return new Decision(ACTION_FEEDER_PAUSED, TAG_INFERRED,
                "feeder paused during deload (light or no pumping)",
                "deload week", Double.NaN, 0);
        }
        double main = Double.isNaN(in.mainPressureKpa) ? in.pressureKpa : in.mainPressureKpa;
        // the guidance states the 70–80% RANGE (source); the single 75% mid-point the mint
        // carries is this file's pick within it -> TAG_DERIVED.
        return new Decision(ACTION_FEEDER_SUGGEST, TAG_DERIVED,
            "feeder pressure = 70–80% of main (75% mid); re-suggested when main changes",
            "Feeder: 10 min, twice a day, at a pressure set from your main sessions.",
            feederPressureKpa(main), 0);
    }

    /** O-1 - a girth step (volume, pressure, level, the time cap's conversion) on a morning the
     *  work already changed waits for the next; anything else - a hold, the offer, the week off,
     *  a step back - stands. */
    private static Decision oneGirthChangeAMorning(Decision d) {
        if (d == null) return d;
        switch (d.action) {
            case ACTION_ADD_VOLUME:
            case ACTION_REDUCE_VOLUME:
            case ACTION_PAUSE_VOLUME:
            case ACTION_RAISE_PRESSURE:
            case ACTION_LEVEL_UP:
                break;
            default:
                if (!d.r2()) return d;
        }
        return hold(TAG_INFERRED, GIRTH_ONE_CHANGE_RULE,
            "your girth plan already changed today — the next change waits for your next "
            + "girth day");
    }

    /**
     * THE LENGTH TRACK'S DECISION - {@link #evaluateLength}'s, ONE CHANGE A MORNING (t10 parity
     * run 2, A-3; R-46). The ladder answers one move a morning, and accepting it re-asks the
     * question - so the calendar's load step, once taken, was followed the same morning by the
     * monthly creep, and a month gate's level-up by the creep too: two changes to the length
     * work before a session had run at either. The editor's model takes the first and lets the
     * rest wait for the next length morning, in the ladder's own order (the rungs, then the
     * month gate, then the creep and the pull that follows it). So here: once the level, the
     * load, the strain sets or the pressure moved today, the next such step holds. A cut, a
     * re-measure, the week off and the offers are not steps up and are never held by it.
     */
    private static Decision oneLengthChangeAMorning(Inputs in, Decision d) {
        if (!in.lengthChangedToday || d == null) return d;
        switch (d.action) {
            case ACTION_LEVEL_UP:
            case ACTION_ADD_VOLUME:
            case ACTION_RAISE_LOAD:
            case ACTION_RAISE_PRESSURE:
                return hold(TAG_INFERRED, LENGTH_ONE_CHANGE_RULE,
                    "your length work already changed today — the next change waits for your "
                    + "next length day");
            default:
                return d;
        }
    }

    /**
     * THE LENGTH LADDER - first match wins, and it runs AFTER the shared safety tier.
     *
     * RED, layoff, under-delivery and the usage-linked deload all happen upstream in {@link
     * #evaluate}; length does not get its own copy of them and does not skip them. What is
     * left here is length's own progression, and its order is the argument:
     *
     *   0  girth focus     - HOLD: the block holds the whole ladder (t10 R-44)
     *   1  no fit          - a bore that inflates cannot pull; say so, run expansion only
     *   2  before month 3  - calendar only, because the metrics do not mean anything yet
     *   3  hand-over       - month 3: the strain sets rise to the table's 6 (R-45)
     *   4  strain high     - RE-MEASURE; a CONFIRMED high cuts 0.5 lb, floor 5 lb (R-40/41)
     *   5  under a week    - option D: FELL -> the week off comes forward; STILL UNDER after
     *                        an added set -> the offer; NEVER REACHED -> a set (then load)
     *   6  no readings     - a strain set every 4 weeks, up to 6 (R-47)
     *   6b slow load       - +0.5 lb every 2 length training weeks (R-46, the setting)
     *   7  divergence      - GIRTH FOCUS
     *   8  month gate      - LEVEL UP
     *   9  HOLD, with the coda's monthly creep and the climb (R-42/43)
     *
     * THE 21-DAY FATIGUE DELOAD IS GONE (t10 R-40, K7): option D's "fell" branch asks the
     * same question - is this block's work still landing - of the one reading the plan acts
     * on, and answers it with the week off it was going to take anyway, brought forward.
     *
     * THE LOAD STEP STILL SITS BELOW THE VOLUME STEP, which is the same volume-before-load
     * precedence the girth engine keeps - sets are the cheaper stimulus and they are spent
     * first. SelfTest pins it.
     */
    private static Decision evaluateLength(Inputs in) {
        // 0. A GIRTH-FOCUS BLOCK HOLDS THE LADDER (t10 R-44, C13). Only safety and the deload
        //    tier - upstream, in evaluate() - apply while it runs: no strain, load, month-gate
        //    or pressure proposal for work the block has paused. When it ends the strain
        //    clock restarts (LengthTrack#focusEnded), so the readings start afresh.
        if (in.inGirthFocus)
            return hold(TAG_INFERRED, LENGTH_FOCUS_HOLD_RULE,
                "a girth block is running: the length ladder holds until it ends");

        // 1. A CYLINDER THAT DOES NOT PULL. Traction is a piston acting on a seal; a bore
        //    wide enough to be a girth tube expands the tissue instead of drawing it, so
        //    there is no load to govern and nothing on this ladder applies. The session is
        //    still worth running - as expansion - and the card must say which it is.
        // (A cylinder marked for length pulls whatever its fit - owner, 2026-09-30.)
        if (!in.lengthTube && !Traction.pulls(in.fitState)) {
            /* THE MONTH GATES STILL APPLY (0.10, the owner's decision). Length levels are
             * the calendar's (3 / 6 / 12), not the cylinder's, and this rung used to return
             * before rung 8 asked them - so length without a pulling cylinder stayed at
             * Level 1 all year while the same person with one moved up at months 3, 6 and
             * 12. The same gate, the same wait for the gentle return's full day, the same
             * offer the person taps; the expansion-only session then runs at that level's
             * top (Scale#usualTopKpa, moved by the track's offset). */
            int gate = lengthGateMetNextLevel(in);
            if (gate != 0) {
                if (in.returnRunsUnder) return levelUpWaitsForReturn(gate);
                return lengthLevelUpDecision(in.level, gate, in.monthIndex);
            }
            // EXPANSION-ONLY IS NOT NOTHING, and it is what every length track did before
            // traction existed: the coda's own monthly pressure creep still runs. An empty
            // rack reads as FIT_UNKNOWN here, so making this rung swallow the creep would
            // have quietly stopped the pressure of every length user who has not described a
            // cylinder - the exact opposite of "an empty rack behaves as it does today".
            return creepOr(in, hold(TAG_SOURCE,
                "length pulls in a cylinder marked for length - none is listed",
                "no length cylinder listed: this runs as expansion only"));
        }

        // 2. BEFORE MONTH 3 THE METRICS ARE NOISE. Strain is a difference between
        //    measurements; early on the difference is mostly the measuring. The calendar
        //    still moves load and sets (the tables above), so this is a hold on the METRIC
        //    ladder, not on progress (t10 R-48: kept).
        if (in.monthIndex < LENGTH_METRICS_FROM_MONTH) {
            /* AND THE CALENDAR ACTUALLY DRIVES. This rung used to say so and do nothing.
             *
             * VOLUME BEFORE LOAD here as everywhere else: a set is the cheaper stimulus, so
             * the sets rung is asked first and the load only once it has nothing to offer.
             *
             * ONE STEP PER ACCEPTANCE, never a jump to the derived position. Somebody who
             * ignored three cards is three steps behind; taking 2.5 lb to 5 lb in a single
             * tap doubles the load on tissue that never ran the rungs between, which is
             * exactly what a ladder exists to prevent. The card names where the calendar has
             * got to, so the next tap is not a surprise. */
            // R11-3: from the count the setup placed (strainCalWeeks), not from the first two.
            int wantSets = calendarStrainSets(in.lengthTrainingWeeks + in.strainCalWeeks);
            if (wantSets > in.strainSets) {
                // R-60 (CAP90): a strain set that would take a both-tracks day past 90 minutes
                // waits, like any volume step - and the load waits behind it that morning.
                if (in.heldAt90) return heldAt90Hold();
                return new Decision(ACTION_ADD_VOLUME, TAG_SOURCE,
                    "length L1 calendar: +1 strain set every " + LENGTH_STRAIN_ADD_WEEKS
                    + " training weeks (now " + wantSets + ")",
                    "the calendar has earned another strain set: " + in.strainSets + " to "
                    + (in.strainSets + 1) + ", on the way to " + wantSets,
                    Double.NaN, 1);
            }
            double wantLb = calendarLoadLb(in.lengthTrainingWeeks, in);
            if (wantLb > in.loadLb + 1e-9) {
                // REAL-11: one load change between two length sessions.
                if (in.loadMovedSinceSession) return creepOr(in, loadWaitsHold());
                double next = Math.min(in.loadLb + LENGTH_LOAD_STEP_LB, wantLb);
                return new Decision(ACTION_RAISE_LOAD, TAG_SOURCE,
                    "length L1 calendar: +" + Traction.settingLb(LENGTH_LOAD_STEP_LB)
                    + " every " + LENGTH_LOAD_STEP_WEEKS + " training weeks, to "
                    + Traction.settingLb(LENGTH_LOAD_L1_TARGET_LB),
                    "the calendar has earned a heavier pull: "
                    + Traction.settingLb(in.loadLb) + " to " + Traction.settingLb(next)
                    + ", on the way to " + Traction.settingLb(wantLb),
                    Double.NaN, 0, next);
            }
            return creepOr(in, hold(TAG_INFERRED,
                "length metrics open at month " + LENGTH_METRICS_FROM_MONTH
                + " - before that, load and sets progress by calendar",
                "the calendar has nothing new to ask for; the readings open at month "
                + LENGTH_METRICS_FROM_MONTH));
        }

        // 3. THE MONTH-3 HAND-OVER (t10 R-45, L1). The calendar rung stops here and the
        //    guidance's months 4-12 table runs 6 strain sets; readings take it on from there.
        //    One change that morning. A person set up at month 3 or later starts at 6
        //    (LengthTrack#atSetup) and an upgrader is marked handed over by the migration, so
        //    neither is stepped by surprise.
        if (!in.handedOver && in.strainSets < LENGTH_HANDOVER_SETS) {
            if (in.heldAt90) return heldAt90Hold();
            int delta = LENGTH_HANDOVER_SETS - in.strainSets;
            return new Decision(ACTION_ADD_VOLUME, TAG_SOURCE, LENGTH_HANDOVER_RULE,
                lengthHandoverWords(in.strainSets), Double.NaN, delta);
        }

        // 4. STRAIN HIGH -> CONFIRM IT FIRST (t10 R-40, C10, A1). Over the window means the
        //    last session asked too much, and the answer is less load. But a mis-measure
        //    reads exactly like that, and acting on one would walk the load down on its own.
        //    So one high reading asks for another, and only two (since the work last
        //    changed; a re-measure the same day replaces that day's pair) propose the cut -
        //    which the person then accepts.
        //    REAL-8: a reading the last cut has already answered (taken before it) asks for
        //    nothing more - the cut restarted the strain clock, and only a new reading asks
        //    again (LengthTrack#fill: Inputs#strainHighAnswered).
        if (!Double.isNaN(in.strainPct) && in.strainPct > LENGTH_STRAIN_HI
                && !in.strainHighAnswered) {
            Decision high;
            if (!in.strainHighConfirmed) {
                high = new Decision(ACTION_REMEASURE, TAG_INFERRED, LENGTH_HIGH_RULE,
                    LENGTH_HIGH_WORDS, Double.NaN, 0);
            } else if (in.loadLb + in.lengthOffsetLb <= LENGTH_LOAD_FLOOR_LB + 1e-9) {
                /* A1: NEVER UNDER FIVE POUNDS on this path - and a load already at or under it
                 * (the 2-month starter's 3 lb) is not cut at all: measure again, and rest if it
                 * stays high. */
                high = new Decision(ACTION_REMEASURE, TAG_INFERRED, LENGTH_FLOOR_RULE,
                    lengthFloorWords(), Double.NaN, 0);
            } else if (in.daysSinceLastCut >= 0 && in.daysSinceLastCut < LENGTH_CUT_GAP_DAYS) {
                // C10: one cut in seven days. The cut restarted the strain clock, so two new
                // readings confirm the next one; on a reading every session that can be inside
                // the week, and the week is the rule.
                high = hold(TAG_INFERRED, LENGTH_CUT_WAIT_RULE, LENGTH_CUT_WAIT_WORDS);
            } else {
                /* Parity run 3, OPEN-5: HALF A POUND OF THE PULL THE PERSON PULLS - the plan's
                 * load with their offset (Inputs#lengthOffsetLb) - and the 5 lb floor is that
                 * pull's. On the plan's own load the floor cut 6.59 lb to 6.48 where the rule
                 * says 6.09. The card names the pull; the decision carries the plan's load. */
                double pull = Math.max(LENGTH_LOAD_FLOOR_LB,
                    in.loadLb + in.lengthOffsetLb - LENGTH_LOAD_STEP_LB);
                double lb = pull - in.lengthOffsetLb;
                return new Decision(ACTION_REMEASURE, TAG_INFERRED, LENGTH_CUT_RULE,
                    lengthCutWords(pull), cutPressureKpa(in, lb), 0, lb);
            }
            /* REAL-16 (C-F2's family): A PENDING RE-MEASURE DOES NOT HOLD BACK THE MONTH GATE.
             * "Measure again", the floor's re-measure and the week a cut waits each used to
             * answer every morning a high reading was showing, ahead of rung 8, so somebody
             * reading high at month 12 stayed at Level 3 for weeks after the gate. Only a cut
             * ready to take answers the morning before it. Not on a gentle return day, where
             * the gate waits anyway (rung 8) and the re-measure stays the answer. */
            int gate = lengthGateMetNextLevel(in);
            if (gate != 0 && !in.returnRunsUnder)
                return lengthLevelUpDecision(in.level, gate, in.monthIndex);
            return high;
        }

        // 5. UNDER TARGET FOR A WEEK - OPTION D (t10 R-40; the owner, 1 Oct 2026). The one
        //    after-session reading under 2 %, still showing a week after the first low one
        //    (the existing debounce, counted from the last change to the work), is read
        //    against THIS BLOCK - the training since the last deload:
        Decision atCap = null;
        Decision loadWait = null;   // REAL-11: a load step owed, waiting for a session
        /* t10-K (B5): CLIMBING BACK AFTER THE MONTH-12 BREAK, a week under 2 % raises the load
         * by half the gap to the load before the break - in place of option D (no fell week
         * off, no set, no offer). Waiting for a session at the last step, the later rungs. */
        boolean climbing = MonthBreak.climbing(in);
        if (climbing && strainLowForAWeek(in)) {
            Decision back = MonthBreak.climbStep(in);
            if (back != null) return back;
            loadWait = loadWaitsHold();
        }
        if (!climbing && strainLowForAWeek(in)) {
            // D1 FELL: the block had a reading at 2 % or over (only that - K9). The work was
            // landing and stopped: tired tissue, and more work is the wrong answer. The week
            // off comes forward; no set is added.
            if (in.blockHadReachLo)
                return new Decision(ACTION_DELOAD, TAG_INFERRED, LENGTH_FELL_RULE,
                    lengthFellWords(in.deloadNextAnyway), Double.NaN, 0);
            // D3 STILL UNDER: a week after a D2 set in this block, still under. Once a block,
            // the choice is the person's: a week off, or a girth block. "Not now" lets the
            // ladder carry on, so the next week under adds a set.
            if (in.dAddedInBlock && !in.dOfferedInBlock)
                return new Decision(ACTION_OFFER_BREAK, TAG_INFERRED, LENGTH_STILL_UNDER_RULE,
                    LENGTH_STILL_UNDER_WORDS, Double.NaN, 0);
            // D2 NEVER REACHED: no reading reached 2 % since the last deload - the work is
            // not enough. +1 strain set (to 12), as the low-strain rung always did...
            if (volumeStep(TRACK_LENGTH, in.level, in.strainSets, 1) > 0) {
                if (in.heldAt90) return heldAt90Hold();
                return new Decision(ACTION_ADD_VOLUME, TAG_INFERRED, LENGTH_NEVER_RULE,
                    lengthNeverWords(in.strainSets), Double.NaN, 1);
            }
            // ...and with the sets spent, the load. Whichever cap binds first wins, and the
            // device cap can bind below the load cap on a low ceiling - Traction owns that
            // comparison so no caller has to remember which is tighter. FIX c (C11): the last
            // half-pound lands ON the cap rather than stopping short of it.
            double cap = loadCapLb(in);
            double next = Math.min(in.loadLb + LENGTH_LOAD_STEP_LB, cap);
            if (next > in.loadLb + 1e-9 && !in.loadMovedSinceSession)
                return new Decision(ACTION_RAISE_LOAD, TAG_INFERRED, LENGTH_NEVER_RULE,
                    lengthNeverLoadWords(in.loadLb, next), Double.NaN, 0, next);
            // REAL-11: owed, but the load moved since the last length session - the later rungs
            // still run, and the hold (in place of the cap's below) says why when none of them
            // has anything.
            if (next > in.loadLb + 1e-9) loadWait = loadWaitsHold();
            /* AT THE CAP THE LADDER FALLS THROUGH (t10 review C-F2, audit A10). With nothing
             * left to add here, this rung used to answer the morning with its hold - every
             * morning a low reading a week old was showing - so a low responder who reached 12
             * sets and the cap before month 12 was never offered the month gate (L3 -> L4, the
             * month-12 fork) while they kept measuring. The later rungs run instead; the hold
             * is the morning's answer only when none of them has anything, and the coda's creep
             * and the climb to the person's own maximum (G2) still run with it (APP-FIXES 20). */
            atCap = hold(TAG_INFERRED, "length load is at the cap that binds first",
                "sets are at " + LENGTH_STRAIN_SETS_MAX + " and the plan's own load steps stop at "
                + Traction.settingLb(cap) + "; the plan holds here");
        }

        // 6. G3 / L3 (the owner's decisions, 1 Oct 2026) - NOTHING MEASURED FOR FOUR TRAINING
        //    WEEKS, past month 3 where the calendar rungs stop: the plan offers a strain set
        //    itself, saying why, on the card every strain set is accepted on - ONLY UP TO 6
        //    (t10 R-47). Accepted, the strain clock restarts (TrainerTrackState#setStrainSets),
        //    and so does this count; any reading hands the volume back to the strain rung.
        //    Past 6, the sets need readings.
        boolean noReadingsTop = false;
        if (in.weeksWithoutReadings >= NO_READINGS_WEEKS) {
            int delta = Math.min(NO_READINGS_LENGTH_SETS,
                                 NO_READINGS_LENGTH_TOP - Math.max(0, in.strainSets));
            if (delta > 0) {
                if (in.heldAt90) return heldAt90Hold();
                return new Decision(ACTION_ADD_VOLUME, TAG_INFERRED, NO_READINGS_RULE
                    + " -> +" + NO_READINGS_LENGTH_SETS + " strain set (up to "
                    + NO_READINGS_LENGTH_TOP + ")", lengthNoReadingsWords(), Double.NaN, delta);
            }
            noReadingsTop = true;
        }

        // 6b. L2 - "LENGTH LOAD AFTER MONTH 3" (t10 R-46, the person's setting). With the slow
        //     calendar load step chosen, while the strain sets are under their top, the load
        //     takes half a pound every 2 length training weeks: toward 5 lb before month 6,
        //     then toward 12 - never past the cap that binds (the device's, at the bore) or
        //     15 lb; "Most you will go to" is held by the caller (Inputs#slowLoadDue). Not on
        //     a return day, nor while a reading is over the window (rung 4 owns the load
        //     then). It only adds: a pull already above the target - the length pressure's -
        //     stays. "Load only after 12 strain sets" leaves the load to rung 5's D2, as before.
        if (in.lengthLoadMode == Model.LENGTH_LOAD_SLOW && in.slowLoadDue
                && in.strainSets < LENGTH_STRAIN_SETS_MAX
                && !in.returnRunsUnder && !strainHigh(in)) {
            double target = in.monthIndex < LENGTH_SLOW_LOAD_FULL_MONTH
                ? LENGTH_LOAD_L1_TARGET_LB : LENGTH_LOAD_MAX_LB;
            target = Math.min(target, Math.min(Scale.LOAD_HARD_MAX_LB, loadCapLb(in)));
            if (in.loadLb < target - 1e-9) {
                // R11-4: gaining while on target, the slow step comes every 4 training weeks.
                if (in.slowLoadBraked && !in.brakeStepUpSlow)
                    return GainBrake.hold(GainBrake.KIND_SLOW);
                double next = Math.min(target, in.loadLb + LENGTH_LOAD_STEP_LB);
                if (!in.loadMovedSinceSession)
                    return new Decision(ACTION_RAISE_LOAD, TAG_INFERRED, LENGTH_SLOW_LOAD_RULE,
                        lengthSlowLoadWords(in.loadLb, next, target), Double.NaN, 0, next);
                loadWait = loadWaitsHold();   // REAL-11
            }
        }

        // 7. STRETCHED RISING, ERECT FLAT. The tissue is lengthening without filling, and
        //    more pulling does not fill it. A temporary girth focus does - and it IS
        //    temporary: a block with an end date, not a track switch.
        if (in.stretchOutrunningErect) {
            return new Decision(ACTION_GIRTH_FOCUS, TAG_INFERRED,
                "stretched rising while erect is flat for " + DIVERGENCE_WEEKS
                + " weeks -> a girth-focus block",
                "you are stretching but not filling — a girth block for a month or two "
                + "puts something in the new space",
                Double.NaN, 0);
        }

        // 8. THE MONTH GATES. Length levels are calendar gates (3 / 6 / 12), unlike girth's
        //    metric ones, so they are asked here rather than through gateMetNextLevel.
        int next = lengthGateMetNextLevel(in);
        if (next != 0) {
            // Length waits for the gentle return's full day too (the owner's decision,
            // 2026-09-27: no level-up proposal on any track while it runs under).
            if (in.returnRunsUnder) return levelUpWaitsForReturn(next);
            return lengthLevelUpDecision(in.level, next, in.monthIndex);
        }

        // 8.5 THE CODA'S PRESSURE CREEP, below everything that changes the traction work.
        //     It governs the expansion coda - and, past the usual top, the pull with it
        //     (creepOr) - so it is the smallest move on the ladder and goes last.
        // 9.  HOLD - with the reason, like every other hold in this engine.
        Decision rest = hold(TAG_INFERRED,
            "length: nothing on the ladder is asking for a change",
            "the length readings are inside their window; the plan holds this week");
        // L3: the fallback's strain set was due and its top stopped it - the hold says so.
        if (noReadingsTop)
            rest = hold(TAG_INFERRED, LENGTH_NO_READINGS_TOP_RULE, lengthNoReadingsTopWords());
        // C-F2: a week under 2 % at the cap, and nothing below it asked - its hold says why.
        if (atCap != null) rest = atCap;
        if (loadWait != null) rest = loadWait;
        return creepOr(in, rest);
    }

    /**
     * RUNG 5's QUESTION - a low reading still showing a week after the first low one. The
     * newest reading under 2 % and the debounce's week (Inputs#strainMissDays). t10 O8: when
     * the newest reading was taken on a reduced return day (Inputs#strainNewestReduced) it is
     * not part of the low streak - it neither starts, extends nor breaks it - so the streak
     * alone answers (LengthTrack#fill counts it without such readings, and only while its own
     * newest reading is under 2 % and inside the week).
     */
    static boolean strainLowForAWeek(Inputs in) {
        if (in.strainMissDays < LENGTH_MISS_DEBOUNCE_DAYS) return false;
        if (in.strainNewestReduced) return true;
        return !Double.isNaN(in.strainPct) && in.strainPct < LENGTH_STRAIN_LO;
    }

    /** Whether the newest length reading is over the window (Inputs#lastStrainHigh, or the
     *  figure itself for a caller that set only that). */
    static boolean strainHigh(Inputs in) {
        return in.lastStrainHigh
            || (!Double.isNaN(in.strainPct) && in.strainPct > LENGTH_STRAIN_HI);
    }

    /** t10 REAL-11: a load step waits - the load moved since the last length session. */
    private static Decision loadWaitsHold() {
        return hold(TAG_INFERRED, LENGTH_LOAD_WAITS_RULE,
            "your length load changed after your last length session — the next step waits "
            + "until you have run a session at it");
    }

    /** REAL-11 - the fallback when it is a decision of its own, else the load-waits hold. */
    private static Decision loadWaitsOr(Decision fallback) {
        return fallback != null && fallback.action != ACTION_HOLD ? fallback : loadWaitsHold();
    }

    /** R-60 (CAP90): a length volume step waits - the both-tracks day would pass 90 minutes. */
    private static Decision heldAt90Hold() {
        return hold(TAG_INFERRED, HELD_AT_90_RULE,
            "your both-tracks day would pass " + HELD_AT_90_MIN + " min with another strain "
            + "set, so the plan holds here");
    }

    /**
     * R-43 (C12): AN ACCEPTED CUT ALSO LOWERS THE LENGTH PRESSURE that made the pull - but only
     * where the climb took it past the usual top, and never under that top: the pressure the
     * new load needs at the length cylinder's bore. NaN (no pressure change) everywhere else,
     * which is what the cut always was. Without it the next climb step would re-derive the old
     * load from the pressure and walk straight back up.
     */
    static double cutPressureKpa(Inputs in, double toLb) {
        double top = usualTopKpa(in);
        if (in.boreCm <= 0 || in.pressureKpa <= top + 0.05) return Double.NaN;
        double kpa = Math.max(top, Math.round(Traction.kpaForLbAtBore(toLb, in.boreCm)));
        return kpa < in.pressureKpa - 0.05 ? kpa : Double.NaN;
    }

    /**
     * THE EXPANSION CODA'S OWN PRESSURE CREEP: +about one hg per month, month one held at
     * 6 hg, to a soft cap of 8-10. Unchanged from what the length track always did, and it
     * governs the CODA only - a traction block's pressure is whatever produces the governed
     * LOAD, and has nothing to do with this number. Past the usual top, though, the pull
     * follows it (G2, R-43).
     *
     * TRULY MONTHLY (t10 R-42, C11): the caller's months are whole months of 30.44 days since
     * the length pressure last moved (TrainerTrackState#pressureSinceMs, floored) - never since
     * a re-mint, and never rounded up from a fortnight.
     *
     * NOT ON A RETURN DAY, and not while the last reading is over the window (C12, fix f):
     * neither a day still under the working pressure nor a session that asked too much is the
     * moment to ask for more.
     *
     * Returns the passed-in fallback when no nudge is due, so each terminal rung reads as
     * one statement instead of repeating the creep check.
     */
    private static Decision creepOr(Inputs in, Decision fallback) {
        Decision d = creepStep(in, fallback);
        /* R11-4 - GAINING WHILE ON TARGET, THE LENGTH CLIMB (its pressure, and the pull that
         * follows it) COMES EVERY 2 MONTHS. A month in, with a step to take, the plan says it
         * waits - over a hold only: a decision of its own is never pushed aside for it. */
        if (d != fallback && in.gainBrake && !in.brakeStepUp
                && in.trainingWeeksAtPressure < GainBrake.CLIMB_MONTHS
                && fallback.action == ACTION_HOLD)
            return GainBrake.hold(GainBrake.KIND_CLIMB);
        return d;
    }

    private static Decision creepStep(Inputs in, Decision fallback) {
        boolean pulls = (in.lengthTube || Traction.pulls(in.fitState)) && in.boreCm > 0;
        if (in.returnRunsUnder || (pulls && strainHigh(in))) return fallback;
        if (in.trainingWeeksAtPressure < 1) return fallback;
        double p = in.pressureKpa;
        double softCap = LENGTH_SOFT_CAP_HI_KPA;
        double monthCap = in.newToPumping && in.monthIndex < 1 ? LENGTH_MONTH1_CAP_KPA
                                                               : softCap;
        double target = Math.min(in.ceilKpa, Math.min(monthCap, ABSOLUTE_CAP_KPA));
        // FIX c (C11): the last step LANDS ON the limit when the whole-kPa command moves,
        // instead of stopping up to a step short of it for good.
        double toCap = Math.min(p + STEP_HG_KPA, target);
        if (Math.round(toCap) > Math.round(p)) {
            return new Decision(ACTION_RAISE_PRESSURE, TAG_INFERRED,
                "length: pressure +≈1 hg / month, soft cap 8–10 hg (expansion coda only)",
                "a month elapsed; nudge length pressure up one hg", toCap, 0);
        }
        /* G2 (the owner's decision, 2026-10-01): PAST THE USUAL TOP TO THE PERSON'S OWN
         * MAXIMUM. The same monthly step - the length track's own - goes on past the soft cap
         * when "Most you will go to" for length is above it, to that maximum (the ceiling,
         * 15 inHg and the 15 lb load limit are inside it), the last step landing on it.
         *
         * AND THE PULL FOLLOWS, ONE STEP AT A TIME (t10 R-43, C12 + fix f): the pull is the
         * length pressure at the length cylinder's bore, but it never jumps there - each
         * month it moves by half a pound or by one pressure step's worth at the bore, whichever
         * is more, toward the load the pressure makes (never past 15 lb), and never lighter
         * than the load already pulled. A load still behind a pressure that has stopped
         * climbing catches up the same way, a step a month. A load decision, accepted on the
         * card like every other load step; past the usual 12 lb only because the person's
         * maximum is (warned once - LengthTrack#past12Due). */
        boolean climbs = climbsPastTop(in);
        String rule = "length: pressure +≈1 hg / month, past the usual top to the most "
            + "you will go to";
        if (climbs && Math.round(p) < Math.round(in.climbTopKpa)) {
            double nextKpa = Math.min(p + STEP_HG_KPA, in.climbTopKpa);
            if (pulls) {
                // REAL-11: the pull waits for a session at the load it last moved to.
                if (in.loadMovedSinceSession) return loadWaitsOr(fallback);
                double lb = followLb(in, nextKpa);
                return new Decision(ACTION_RAISE_LOAD, TAG_INFERRED, rule + "; the pull follows",
                    "a month at this pressure; the length pressure steps up one hg toward the "
                    + "most you said you will go to, and the pull follows it: "
                    + Traction.settingLb(in.loadLb) + " to " + Traction.settingLb(lb),
                    nextKpa, 0, lb);
            }
            return new Decision(ACTION_RAISE_PRESSURE, TAG_INFERRED, rule,
                "a month at this pressure; nudge length pressure up one hg toward the most "
                + "you said you will go to", nextKpa, 0);
        }
        if (pulls && p > usualTopKpa(in) + 0.05) {
            double lb = followLb(in, p);
            if (lb > in.loadLb + 1e-9 && in.loadMovedSinceSession) return loadWaitsOr(fallback);
            if (lb > in.loadLb + 1e-9)
                return new Decision(ACTION_RAISE_LOAD, TAG_INFERRED,
                    "length: the pull follows the climb, one step a month",
                    "a month at this pressure; the pull takes its next step toward what the "
                    + "length pressure makes: " + Traction.settingLb(in.loadLb) + " to "
                    + Traction.settingLb(lb), p, 0, lb);
        }
        return fallback;
    }

    /** R-43 - the pull's next step toward the load `kpa` makes at the length bore: half a pound
     *  or one pressure step's worth there, whichever is more, never past that load or 15 lb,
     *  never under the load already pulled. */
    private static double followLb(Inputs in, double kpa) {
        // At the WHOLE kPa the pump is commanded at - the load the pull will really be.
        double toward = Math.min(Scale.LOAD_HARD_MAX_LB,
                                 Traction.loadLbAtBore(Math.round(kpa), in.boreCm));
        double step = Math.max(LENGTH_LOAD_STEP_LB,
            Traction.loadLbAtBore(in.pressureKpa + STEP_HG_KPA, in.boreCm)
            - Traction.loadLbAtBore(in.pressureKpa, in.boreCm));
        return Math.max(in.loadLb, Math.min(toward, in.loadLb + step));
    }

    /* ---- THE CALENDAR LADDER (spec section 2's length table) ---------------------- *
     *  "L.L1.lbStep / stepWks / lbTarget: +0.5 lb / 2 wks -> 5 lb (calendar-driven at L1)"
     *  and "L.L1.strainSets / strainAddWks: 2 x 300 s, +1 / 3 wks".
     *
     *  Before month three the metrics say nothing that a week-to-week wobble does not, so
     *  the plan moves on the calendar instead - and that half of it did not exist. Rung 2
     *  returned a hold whose comment claimed "the calendar still moves load and sets (the
     *  tables above)", and there was no length table: LENGTH_LOAD_L1_TARGET_LB had exactly
     *  one occurrence in the whole repo, its own definition. A beginner's load sat at
     *  2.5 lb and their strain block at two sets, for three months, with nothing offering
     *  a change and every card looking correct.
     *
     *  BOTH ARE DERIVED, never stored: the position is a function of weeks trained, so it
     *  cannot drift from the log and needs no migration.                                  */

    /** The load the calendar has earned - from the starting rung, one step per
     *  {@link #LENGTH_LOAD_STEP_WEEKS} training weeks, stopping at L1's target and never
     *  past whichever cap binds this month. */
    public static double calendarLoadLb(int trainingWeeks, Inputs in) {
        int steps = Math.max(0, trainingWeeks) / LENGTH_LOAD_STEP_WEEKS;
        double lb = LENGTH_LOAD_START_LB + LENGTH_LOAD_STEP_LB * steps;
        if (lb > LENGTH_LOAD_L1_TARGET_LB) lb = LENGTH_LOAD_L1_TARGET_LB;
        double cap = loadCapLb(in);
        return lb > cap ? cap : lb;
    }

    /** And the strain sets it has earned - from the starting two, one per
     *  {@link #LENGTH_STRAIN_ADD_WEEKS} training weeks, to the same cap the metric ladder
     *  stops at. No separate L1 ceiling: the month-3 handover tops this out at six on its
     *  own, so a second constant would be an invented limit that never binds. */
    public static int calendarStrainSets(int trainingWeeks) {
        int n = LENGTH_STRAIN_SETS_START + Math.max(0, trainingWeeks) / LENGTH_STRAIN_ADD_WEEKS;
        return n > LENGTH_STRAIN_SETS_MAX ? LENGTH_STRAIN_SETS_MAX : n;
    }

    /** Which cap binds the length load first - the device's, through the ceiling the user
     *  set, or the plan's own 12 lb. Month one is held tighter still. {@link Traction} owns
     *  the device side of that comparison so the answer cannot drift between callers. */
    /**
     * The pounds cap THE PLAN applies, before the device is consulted - twelve, or the first
     * month's four.
     *
     * Published because the load card has to name which of the two ceilings is binding, and
     * it was comparing the device's reach against the flat twelve. In month one the plan's
     * own cap is four, which almost any device beats - so the card blamed "your device
     * ceiling" for a limit the plan had set, and pointed somebody at a Settings number that
     * would not have moved it.
     */
    public static double loadCapPolicyLb(Inputs in) {
        return loadCapPolicyLb(in.monthIndex);
    }

    /** The same policy cap for a caller that has a month but no Inputs - the cylinder rack
     *  describes a tube, not a session, and was left quoting the flat twelve. */
    public static double loadCapPolicyLb(int monthIndex) {
        double cap = LENGTH_LOAD_MAX_LB;
        if (monthIndex < 1) cap = Math.min(cap, LENGTH_LOAD_M1_MAX_LB);
        return cap;
    }

    public static double loadCapLb(Inputs in) {
        double cap = loadCapPolicyLb(in);
        // Passing `cap` back in as the policy side makes this the tighter of the two,
        // which is exactly what "whichever binds first" means.
        // By the length cylinder's bore (owner, 2026-09-30); a girth only where no bore is given.
        if (in.boreCm > 0) cap = Math.min(cap, Traction.maxLoadLbAtBore(in.boreCm, in.ceilKpa, cap));
        else if (in.girthCm > 0)
            cap = Math.min(cap, Traction.maxLoadLb(in.girthCm, in.ceilKpa, cap));
        return cap;
    }

    /** The length track's LEVEL gates: calendar months, 3 / 6 / 12. Returns 0 when the next
     *  gate is not met. Separate from {@link #gateMetNextLevel} because girth's gates are
     *  earned with metrics and length's are served by the calendar - merging them would put
     *  a metric gate in front of a length user who has none. */
    public static int lengthGateMetNextLevel(Inputs in) {
        // t10-K (B2): not beside a month-12 break just taken (Inputs#l4Waits).
        if (in.level == L3 && in.l4Waits) return 0;
        return lengthGateMetNextLevel(in.level, in.monthIndex);
    }

    public static int lengthGateMetNextLevel(int level, int monthIndex) {
        if (level == L1 && monthIndex >= 3)  return L2;
        if (level == L2 && monthIndex >= 6)  return L3;
        if (level == L3 && monthIndex >= 12) return L4;
        return 0;
    }

    /** One decimal, no locale surprises - these figures are printed inside rule strings the
     *  tests match on, so they must read the same everywhere. */
    /** A percentage as a rule states it: "3%", "6%", "2.5%". */
    static String pct(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        return (r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r)) + "%";
    }

    private static String fmt1(double v) {
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    /** The hold a met gate gets while the gentle return still runs under - one wording for
     *  every track, so the card says the same thing whichever level is waiting. */
    static Decision levelUpWaitsForReturn(int nextLevel) {
        return hold(TAG_INFERRED, "level gate met; waits for the gentle return's first full day",
            "gate met — the move to Level " + nextLevel + " is offered on your first full "
            + "day back, once the lighter days after your rest are done");
    }

    private static Decision hold(int tag, String rule, String reason) {
        return new Decision(ACTION_HOLD, tag, rule, reason, Double.NaN, 0);
    }

    private static String capHgText(double capKpa) {
        long hg = Math.round(capKpa / HG);
        return hg + " hg";
    }

    /** The full, ordered list of table rows across L1 and L2 interval girth — a convenience
     *  the tab uses to render the master plan; kept here so the table lives in ONE place. */
    public static List<Week> intervalMasterPlan() {
        List<Week> all = new ArrayList<Week>();
        for (int i = 0; i < GIRTH_INTERVAL_L1.length; i++) all.add(GIRTH_INTERVAL_L1[i]);
        for (int i = 0; i < GIRTH_INTERVAL_L2.length; i++) all.add(GIRTH_INTERVAL_L2[i]);
        return all;
    }
}
