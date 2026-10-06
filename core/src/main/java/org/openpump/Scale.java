package org.openpump;

/**
 * THE PERSON'S OWN SCALE - "plan ± x" - and the limits every trainer pressure is held to.
 *
 * WHY THIS EXISTS (the owner's decisions, 0.10). The plan's pressure figure is the guidance's:
 * it starts where the level starts and steps up as the level allows. People do not all sit on
 * that figure. Somebody who has pumped for years at 9 inHg was told "kept, not corrected" by
 * the setup and then quietly held to the level's top anyway; somebody who marks easily at the
 * plan's figure had no way to say "a little less, always" except by editing every routine the
 * plan wrote, which the plan then read as edited and stopped keeping current. So each track
 * (girth, length) carries ONE personal offset, and every prescription for it is the plan's own
 * figure plus that offset. The plan keeps progressing underneath exactly as it did: every step
 * up still happens, on top of the offset.
 *
 * THE LEVEL'S TOP MOVES WITH THE OFFSET (the owner, final). A track's usual top - 6, 8 or 10
 * inHg by level and month; length's own 6 then 10, not girth's - plus that track's offset is
 * its effective top, so the plan's steps land inside a band shifted by exactly the offset.
 * Setting an offset that takes a track above its usual top is warned once, and the person's
 * answer is kept (the warned-once state, {@link #needsWarning}); it is never silently undone.
 *
 * THE HARD LIMITS DO NOT MOVE, EVER: the device ceiling, "Most you will go to" and the absolute
 * 15 inHg bind every trainer pressure ({@link #hardKpa}), and a person who said they are new to
 * pumping keeps the first month's 6 inHg (4 lb of traction) whatever their offset - the offset
 * applies from their second month ({@link #appliedOffsetKpa}).
 *
 * THE PROGRAM'S GENTLE AND FIRM ARE OFFSETS TOO: one inHg under or over the prescription
 * ({@link #biasedKpa}), stacked on the personal offset. They do not move the top.
 *
 * THE PLAN READS ITS OWN FIGURE. Level gates and the plan's progression read the plan's figure,
 * never the scaled one, so no offset and no bias stalls a level. A routine records how far its
 * work sits from the plan's figure ({@link Model.Routine#trainerScaleKpa}), and the two things
 * that read what a session actually pulled - the line net time is counted from, and the Level 1
 * gate's pressure - read it on the plan's scale ({@link #countingShiftKpa}, {@link #onPlanScaleKpa}).
 *
 * PURE: the arithmetic is here with no Android, so JUnit sweeps it (ScaleTest).
 */
public final class Scale {
    private Scale() { }

    /** How far the personal offset reaches either way: 5 inHg. The hard limits bind long
     *  before the top end matters; the bottom end keeps a negative offset from asking for no
     *  pressure at all. */
    public static final double OFFSET_MAX_KPA = 5.0 * Plan.HG;

    /** One Program bias step: gentle is the plan less one inHg, firm the plan plus one. */
    public static final double BIAS_KPA = Plan.HG;

    /** The least a gentle bias commands - the floor Mint#biasKpa always had. */
    public static final int GENTLE_MIN_KPA = 2;

    /** Traction's hard load limit, pounds. The plan's own ladder stops at twelve (Traction's
     *  working cap, the usual top); an offset or the setup's own answer may take a pull past
     *  it, warned once, and never past this. */
    public static final double LOAD_HARD_MAX_LB = 15.0;

    /** The least load a negative offset leaves a traction pull. */
    public static final double LOAD_MIN_LB = 0.5;

    /* ===================================================================== *
     *  WHO THE MONTH-1 LIMITS BIND                                           *
     * ===================================================================== */

    /** A person who said they are new to pumping, in their first month: the guidance's
     *  first-month limits are hard for them, and their offset waits for month two. */
    public static boolean newMonthOne(boolean newToPumping, int monthIndex) {
        return newToPumping && monthIndex < 1;
    }

    /** The offset that applies this month: the person's own, or none in a new person's first
     *  month. Held to {@link #OFFSET_MAX_KPA} either way, whatever a file says. */
    public static double appliedOffsetKpa(double offsetKpa, boolean newToPumping,
                                          int monthIndex) {
        if (newMonthOne(newToPumping, monthIndex)) return 0.0;
        return clampOffset(offsetKpa);
    }

    /** An offset held inside the stepper's own reach; NaN reads as none. */
    public static double clampOffset(double offsetKpa) {
        if (Double.isNaN(offsetKpa) || Double.isInfinite(offsetKpa)) return 0.0;
        return Math.max(-OFFSET_MAX_KPA, Math.min(OFFSET_MAX_KPA, offsetKpa));
    }

    /* ===================================================================== *
     *  THE TOPS                                                              *
     * ===================================================================== */

    /**
     * THE LEVEL'S USUAL TOP for a track, kPa - what the plan's own figure never passes.
     * Girth (and the feeder, which is girth's): Plan#pressureCapKpa - 6 inHg for Level 1's
     * first month, 8 at Level 1, 10 above. LENGTH HAS ITS OWN (the owner, final): 6 inHg for
     * the first month, then the 10 inHg the length guidance's soft cap ends at - it used to be
     * held to girth's caps for its level, which is not what the length guidance says.
     */
    public static double usualTopKpa(int track, int level, int monthIndex) {
        return usualTopKpa(track, level, monthIndex, true);
    }

    /**
     * {@link #usualTopKpa(int,int,int)} FOR THIS PERSON (t10 REAL-14, the parity run): the
     * first month's 6 inHg is a beginner's top, and binds only somebody new to pumping
     * ({@link #newMonthOne}, as the hard limits and the offset already read it). Somebody who
     * has pumped before has their level's own top from the first day - girth's 8 inHg at
     * Level 1, length's 10 - which is where the setup already put them (TrainerTab
     * #deriveGirth). The three-argument form is the beginner's reading.
     */
    public static double usualTopKpa(int track, int level, int monthIndex,
                                     boolean newToPumping) {
        if (track == Plan.TRACK_LENGTH) {
            double top = newMonthOne(newToPumping, monthIndex) ? Plan.LENGTH_MONTH1_CAP_KPA
                                                               : Plan.LENGTH_SOFT_CAP_HI_KPA;
            return Math.min(top, Plan.ABSOLUTE_CAP_KPA);
        }
        return Plan.pressureCapKpa(level, monthIndex, newToPumping);
    }

    /**
     * THE EFFECTIVE TOP: the usual top moved by the track's offset (the owner, final - the one
     * rule, so it can change in one place). A new person's first month keeps the usual top: the
     * offset does not apply yet. The Program's bias does not move it. The hard limits are
     * applied separately ({@link #hardKpa}) - this may say more than the device allows.
     */
    public static double effectiveTopKpa(int track, int level, int monthIndex,
                                         double offsetKpa, boolean newToPumping) {
        return usualTopKpa(track, level, monthIndex, newToPumping)
             + appliedOffsetKpa(offsetKpa, newToPumping, monthIndex);
    }

    /** The effective top as the whole kPa a prescription can carry - rounded as the plan's
     *  own figure is (Mint#clampPressureKpa; the 34 kPa of 10 inHg is kept, D10). */
    public static int effectiveTopWholeKpa(int track, int level, int monthIndex,
                                           double offsetKpa, boolean newToPumping) {
        return (int) Math.round(effectiveTopKpa(track, level, monthIndex, offsetKpa,
                                                newToPumping));
    }

    /* ---- G2: CLIMB TO MY MAXIMUM (the owner's decision, 2026-10-01) ------------------- */

    /**
     * THE TOP THE PLAN CLIMBS TO, whole kPa on the person's own scale. The effective top - or,
     * when the track's own "Most you will go to" is set above it, that maximum: the plan's
     * existing pressure step goes on past the usual top, at its own pace, until it reaches
     * it. Never past the device ceiling or 15 inHg ({@link #pullHardKpa}, floored as the hard
     * limits are); a new person's first month keeps its hard limits and climbs nothing; no
     * maximum set, or one at or under the effective top as the wire carries it, is the
     * effective top exactly as before.
     */
    public static int climbTopWholeKpa(int track, int level, int monthIndex, double offsetKpa,
                                       boolean newToPumping, double mostKpa, int ceilKpa) {
        int eff = effectiveTopWholeKpa(track, level, monthIndex, offsetKpa, newToPumping);
        if (newMonthOne(newToPumping, monthIndex) || !(mostKpa > 0)) return eff;
        int top = pullHardKpa(ceilKpa, mostKpa);
        return top > eff ? top : eff;
    }

    /** Whether the track climbs past its effective top to the person's maximum. */
    public static boolean climbs(int track, int level, int monthIndex, double offsetKpa,
                                 boolean newToPumping, double mostKpa, int ceilKpa) {
        return climbTopWholeKpa(track, level, monthIndex, offsetKpa, newToPumping, mostKpa,
                                ceilKpa)
             > effectiveTopWholeKpa(track, level, monthIndex, offsetKpa, newToPumping);
    }

    /**
     * THE PLAN'S OWN FIGURE'S TOP, kPa, on the plan's scale: the usual top - or, climbing, the
     * climb top less the offset that applies, so the figure plus the offset lands on it. What
     * the prescription holds the plan's figure to (Mint#prescribe) and what the plan's step
     * stops at (Plan.Inputs#climbTopKpa). The feeder takes girth's main pressure and never
     * climbs on its own.
     */
    public static double planTopKpa(int track, int level, int monthIndex, int ceilKpa,
                                    Limits lim) {
        double usual = usualTopKpa(track, level, monthIndex,
                                   lim == null || lim.firstMonthTop);
        if (lim == null || track == Plan.TRACK_FEEDER) return usual;
        if (!climbs(track, level, monthIndex, lim.offsetKpa, lim.newToPumping, lim.mostKpa,
                    ceilKpa)) return usual;
        int climb = climbTopWholeKpa(track, level, monthIndex, lim.offsetKpa,
                                     lim.newToPumping, lim.mostKpa, ceilKpa);
        return Math.max(usual,
            climb - appliedOffsetKpa(lim.offsetKpa, lim.newToPumping, monthIndex));
    }

    /* ===================================================================== *
     *  THE HARD LIMITS                                                       *
     * ===================================================================== */

    /**
     * THE MOST ANY PULL MAY BE, kPa, for everybody: the device ceiling, "Most you will go to"
     * (0 = not set) and the absolute 15 inHg - whichever is lowest. Floored, never rounded:
     * rounding 15 inHg (50.8 kPa) or a stated 10.0 inHg (33.9) up would put the clamp that
     * exists to enforce "never exceeded" above the figure it enforces (Plan#absoluteCapWholeKpa).
     */
    public static int pullHardKpa(int ceilKpa, double mostKpa) {
        int hard = Plan.absoluteCapWholeKpa();
        if (ceilKpa < hard) hard = ceilKpa;
        if (mostKpa > 0) hard = Math.min(hard, (int) Math.floor(mostKpa + 1e-9));
        return Math.max(1, hard);
    }

    /**
     * THE MOST A TRAINER PRESSURE MAY BE for this person this month - {@link #pullHardKpa},
     * and for somebody new to pumping in their first month the guidance's 6 inHg as well
     * (hard for them, whatever their offset). A traction PULL is governed by its load, whose
     * own first-month limit is {@link #loadHardMaxLb}, so it takes {@link #pullHardKpa} alone.
     */
    public static int hardKpa(int ceilKpa, double mostKpa, boolean newToPumping,
                              int monthIndex) {
        int hard = pullHardKpa(ceilKpa, mostKpa);
        if (newMonthOne(newToPumping, monthIndex))
            hard = Math.min(hard, (int) Math.floor(Plan.MONTH1_CAP_KPA + 1e-9));
        return Math.max(1, hard);
    }

    /* ===================================================================== *
     *  THE PRESCRIPTION                                                      *
     * ===================================================================== */

    /**
     * THE PLAN'S OWN FIGURE AS A PRESCRIPTION, whole kPa: never above the track's usual top,
     * rounded as the wire carries it, then never above the hard limit, never under 1. Exactly
     * what Mint#clampPressureKpa did for girth; for length, against length's own top.
     */
    public static int planOwnKpa(double planKpa, int track, int level, int monthIndex,
                                 int hardKpa) {
        return planOwnKpa(planKpa, usualTopKpa(track, level, monthIndex), hardKpa);
    }

    /** {@link #planOwnKpa} against the plan figure's own top (G2: the climb's when the
     *  person's maximum is above the usual top - {@link #planTopKpa}). */
    public static int planOwnKpa(double planKpa, double planTopKpa, int hardKpa) {
        double capped = Math.min(planKpa, planTopKpa);
        return held((int) Math.round(capped), hardKpa);
    }

    /**
     * THE PRESCRIPTION ON THE PERSON'S SCALE, whole kPa: the plan's figure (held to the usual
     * top, as the plan holds it) plus the offset that applies - so it is never above the
     * effective top - then the hard limits. With no offset it is {@link #planOwnKpa} exactly.
     */
    public static int scaledKpa(double planKpa, double appliedOffsetKpa, int track, int level,
                                int monthIndex, int hardKpa) {
        return scaledKpa(planKpa, appliedOffsetKpa, usualTopKpa(track, level, monthIndex),
                         hardKpa);
    }

    /** {@link #scaledKpa} against the plan figure's own top (G2, {@link #planTopKpa}). */
    public static int scaledKpa(double planKpa, double appliedOffsetKpa, double planTopKpa,
                                int hardKpa) {
        double plan = Math.min(planKpa, planTopKpa);
        return held((int) Math.round(plan + appliedOffsetKpa), hardKpa);
    }

    /**
     * THE PROGRAM'S BIAS ON A PRESCRIPTION (the owner's decision, 0.10): gentle is the
     * prescription less one inHg, firm the prescription plus one, standard the prescription.
     * Both stack on the personal offset (the prescription already carries it). Gentle never
     * goes above the prescription nor under {@link #GENTLE_MIN_KPA}; firm never below the
     * prescription, never above the effective top (`topWholeKpa` - the bias does not move the
     * top: past the usual top only through the person's own offset, which was warned), and
     * never above the hard limit. Bias is a change of one inHg from the plan, so a beginner on
     * firm starts one inHg above the plan, not at the band's top.
     */
    public static int biasedKpa(int rxKpa, int programPressure, int topWholeKpa, int hardKpa) {
        if (programPressure == Model.Program.PRESS_GENTLE) {
            int g = (int) Math.round(rxKpa - BIAS_KPA);
            return Math.min(rxKpa, Math.max(GENTLE_MIN_KPA, g));
        }
        if (programPressure == Model.Program.PRESS_FIRM) {
            int f = (int) Math.round(rxKpa + BIAS_KPA);
            f = Math.min(f, Math.min(topWholeKpa, hardKpa));
            return Math.max(rxKpa, f);
        }
        return rxKpa;
    }

    private static int held(int kpa, int hardKpa) {
        if (kpa > hardKpa) kpa = hardKpa;
        return kpa < 1 ? 1 : kpa;
    }

    /* ===================================================================== *
     *  THE PLAN'S SCALE: THE COUNTING LINE AND THE GATE                     *
     * ===================================================================== */

    /**
     * HOW FAR THE COUNTING LINE MOVES DOWN for a routine whose work sits `scaleKpa` from the
     * plan's figure (Model.Routine#trainerScaleKpa): all of it when the work sits under the
     * plan, nothing when it sits over (the owner's decision, 0.10). Working below the plan on
     * purpose - a negative offset, gentle - still counts, so progression continues; working
     * above it never lowers the bar.
     */
    public static double countingShiftKpa(int scaleKpa) {
        // Held to the scale's reach whatever a caller hands in (review 2, finding 6).
        int sc = clampScaleKpa(scaleKpa);
        return sc < 0 ? -sc : 0.0;
    }

    /** A pressure a session pulled, read on the plan's own scale - what a gate that asks "did
     *  the plan's figure reach 8 inHg" compares (TrainerTab#gateHeldTrainingWeeks). */
    public static double onPlanScaleKpa(double pulledKpa, int scaleKpa) {
        return pulledKpa - clampScaleKpa(scaleKpa);
    }

    /* ===================================================================== *
     *  TRACTION: ONE LENGTH OFFSET, IN THE PULL'S OWN POUNDS                 *
     * ===================================================================== */

    /** The load the offset adds (or takes) in the length cylinder - the same load/pressure
     *  conversion the setup and the Trainer tab show (Traction#loadLbAtBore, by the bore -
     *  owner, 2026-09-30), so the traction pulls and the expansion part of a length session
     *  move together. 0 with no length cylinder. */
    public static double offsetLb(double appliedOffsetKpa, double boreCm) {
        if (boreCm <= 0 || appliedOffsetKpa == 0.0) return 0.0;
        return Traction.loadLbAtBore(appliedOffsetKpa, boreCm);
    }

    /** The most load a pull may be: 15 lb, or the guidance's 4 lb for a new person's first
     *  month. The ceiling, "Most you will go to" and 15 inHg still bind the pressure that
     *  makes it (Mint#tractionSet). */
    public static double loadHardMaxLb(boolean newToPumping, int monthIndex) {
        return newMonthOne(newToPumping, monthIndex) ? Plan.LENGTH_LOAD_M1_MAX_LB
                                                     : LOAD_HARD_MAX_LB;
    }

    /**
     * THE LOAD A TRACTION PULL IS BUILT AT: the plan's load (the ladder's, or the setup's own
     * answer) plus the length offset in pounds, never past the hard load limit, never under
     * {@link #LOAD_MIN_LB}. The ladder itself stops at twelve pounds, so past twelve is only
     * ever the person's offset or answer - warned once - never the plan's own step.
     */
    public static double commandedLoadLb(double planLoadLb, double appliedOffsetKpa,
                                         double boreCm, boolean newToPumping,
                                         int monthIndex) {
        double hard = loadHardMaxLb(newToPumping, monthIndex);
        double lb = planLoadLb + offsetLb(appliedOffsetKpa, boreCm);
        if (lb > hard) lb = hard;
        if (lb < LOAD_MIN_LB) lb = Math.min(LOAD_MIN_LB, Math.max(0.0, planLoadLb));
        return lb;
    }

    /** Would a pull at `lb` be past the plan's usual twelve pounds? What the setup warns of. */
    public static boolean pastUsualLoad(double lb) {
        return lb > Traction.LOAD_MAX_LB + 1e-9;
    }

    /**
     * THE PLAN'S USUAL LOAD CAP FOR `monthIndex`, pounds (Plan#loadCapPolicyLb): twelve, and
     * the first month's four. For everybody - the plan's own steps stop there - but HARD only
     * for somebody new to pumping in their first month (#loadHardMaxLb); for everybody else it
     * is a programme cap, warned once, their call (the owner's rule). A "0 months, not new"
     * setup pulled 5-10 lb from the first day with nothing said, because setup warned only
     * past twelve (plan simulator t8, finding 1).
     */
    public static double usualLoadCapLb(int monthIndex) {
        return Plan.loadCapPolicyLb(monthIndex);
    }

    /* ---- the length load's limit, as the screens say it (t10 device walk M6) -------------
     *
     * Twelve pounds is the plan's USUAL load, not its cap: the pull follows a climb past it
     * (R-43), and only fifteen is hard (A9) - a new person's first month four. The setup's
     * cylinder lines and the length detail called twelve "the plan's cap" next to a proposed
     * load above it. These are the one wording: the limit (the hard one, or the pump's reach
     * in this bore where that is lower), what sets it, and what passing the usual load means. */

    /** The most a length pull can be, pounds: the hard limit, or the pump's reach at `ceilKpa`
     *  in a `boreCm` tube where that is lower. */
    public static double loadLimitLb(boolean newToPumping, int monthIndex, double boreCm,
                                     double ceilKpa) {
        double hard = loadHardMaxLb(newToPumping, monthIndex);
        return boreCm > 0 ? Traction.maxLoadLbAtBore(boreCm, ceilKpa, hard) : hard;
    }

    /** Whether the pump's ceiling, not the plan, sets {@link #loadLimitLb}. */
    public static boolean loadLimitIsDevice(boolean newToPumping, int monthIndex, double boreCm,
                                            double ceilKpa) {
        return boreCm > 0 && Traction.bindingAtBore(boreCm, ceilKpa,
            loadHardMaxLb(newToPumping, monthIndex)) == Traction.BINDS_DEVICE;
    }

    /** What sets the limit: "your device ceiling", "the plan's first-month limit" or "the
     *  plan's limit". */
    public static String loadLimitWhy(boolean newToPumping, int monthIndex, double boreCm,
                                      double ceilKpa) {
        if (loadLimitIsDevice(newToPumping, monthIndex, boreCm, ceilKpa))
            return "your device ceiling";
        return newMonthOne(newToPumping, monthIndex) ? "the plan's first-month limit"
                                                     : "the plan's limit";
    }

    /** What passing the usual load means at `monthIndex`: "Past 12 lb before month 12 is
     *  warned once - your call." (A9), the first month's four likewise; "" where there is no
     *  such warning (a new person's first month, where four is hard; month 12 on). */
    public static String loadUsualNote(boolean newToPumping, int monthIndex) {
        if (newMonthOne(newToPumping, monthIndex) || monthIndex >= 12) return "";
        return "Past " + Traction.settingLb(usualLoadCapLb(monthIndex))
            + (monthIndex < 1 ? " in your first month" : " before month 12")
            + " is warned once — your call.";
    }

    /** The setup rack's line under a length tube: which limit binds, and the usual load. */
    public static String loadLimitLine(boolean newToPumping, int monthIndex, double boreCm,
                                       double ceilKpa) {
        double hard = loadHardMaxLb(newToPumping, monthIndex);
        String note = loadUsualNote(newToPumping, monthIndex);
        String s = loadLimitIsDevice(newToPumping, monthIndex, boreCm, ceilKpa)
            ? "The pump’s ceiling is what limits the load here, before the plan’s "
              + Traction.settingLb(hard) + " limit."
            : "The plan’s " + (newMonthOne(newToPumping, monthIndex) ? "first-month " : "")
              + "limit of " + Traction.settingLb(hard) + " is what limits the load here, before "
              + "the pump’s ceiling.";
        return note.length() == 0 ? s : s + " " + note;
    }

    /** Would a pull at `lb` be past the plan's usual load cap for `monthIndex`? */
    public static boolean pastUsualLoad(double lb, int monthIndex) {
        return lb > usualLoadCapLb(monthIndex) + 1e-9;
    }

    /* ===================================================================== *
     *  WARNED ONCE, YOUR CALL                                                *
     * ===================================================================== */

    /**
     * DOES SETTING `offsetKpa` NEED THE ONE-TIME WARNING? Any offset above zero lifts the
     * track's top above its usual top (the top moves with the offset), so it is warned - once
     * per new higher value: an offset at or under one the person has already confirmed
     * (`warnedKpa`, per track) is not asked again, and nothing at or under zero ever is.
     */
    public static boolean needsWarning(double offsetKpa, double warnedKpa) {
        return offsetKpa > 1e-6 && offsetKpa > warnedKpa + 1e-6;
    }

    /**
     * DOES THE OFFSET TAKE THE TRACK PAST ITS USUAL TOP AT ALL (device walk E-I2, 2026-09-30;
     * plan simulator t8 finding 4)? An answer that is the usual top as the wire's whole kPa
     * carries it - -10.0 inHg, 34 kPa, over the length top of 33.86 - is AT the top, not past
     * it, on either track.
     * Only when the top it moves to is a higher whole kPa - all the wire carries - than the
     * usual top. A length answer of -10.0 inHg (34 kPa) over the plan's 33.9 is an offset of
     * a tenth of a kPa: the top stays 34, nothing can be prescribed past it, and "Past the
     * usual top?" asked about "plan +0.0 inHg ... about 0 kg more". Asked as the offset will
     * apply (not a new person's first month, where it waits), so the warning a first-month
     * answer is owed for month two is still given (#offsetWarning says so).
     */
    public static boolean pastUsualTop(int track, int level, int monthIndex, double offsetKpa) {
        return effectiveTopWholeKpa(track, level, monthIndex, offsetKpa, false)
             > effectiveTopWholeKpa(track, level, monthIndex, 0.0, false);
    }

    /** The warned-up-to value after the person confirms `offsetKpa`: it only ever rises. */
    public static double confirmed(double offsetKpa, double warnedKpa) {
        return Math.max(warnedKpa, offsetKpa);
    }

    /** The offset stepper's step in the person's unit: a whole kPa in kPa (all the wire
     *  carries), otherwise a tenth of the unit shown - 0.1 inHg. */
    public static double offsetStepKpa() {
        return Model.Fmt.U_KPA.equals(Model.Fmt.unit) ? 1.0 : Model.Fmt.stepKpaForUnit();
    }

    /** One step of the offset stepper: 0.1 of the display unit (whole kPa in kPa), held to
     *  {@link #OFFSET_MAX_KPA}, quantised so n taps up and n down come back to the same
     *  double. */
    public static double stepOffset(double offsetKpa, int dir, double stepKpa) {
        double v = clampOffset(offsetKpa) + dir * stepKpa;
        v = Math.rint(v * 1e6) / 1e6;
        if (Math.abs(v) < 1e-6) v = 0.0;
        return clampOffset(v);
    }

    /* ===================================================================== *
     *  IN A SIGNATURE                                                        *
     * ===================================================================== */

    private static final String OFFSET_TAG = "OF";

    /** The signature segment that records an applied offset: "OF" and whole hundredths of a
     *  kPa, signed ("OF339", "OF-169"); "" with none, so a signature without an offset is
     *  byte for byte what it was before offsets existed (Model#mintShapeTag). */
    public static String offsetTag(double appliedOffsetKpa) {
        long h = Math.round(clampOffset(appliedOffsetKpa) * 100.0);
        return h == 0 ? "" : OFFSET_TAG + h;
    }

    /** The applied offset a stored signature records, kPa - 0 for one with none (every
     *  signature from before offsets, and every one on the plan's own figure). Only a segment
     *  that is "OF", an optional minus and one to five digits is read, so a cylinder id or a
     *  shape tag can never be taken for one. */
    public static double offsetOfSig(String sig) {
        if (sig == null) return 0.0;
        String[] segs = sig.split("\\|", -1);
        for (int i = 6; i < segs.length; i++) {
            String s = segs[i];
            if (!s.startsWith(OFFSET_TAG)) continue;
            String n = s.substring(OFFSET_TAG.length());
            String digits = n.startsWith("-") ? n.substring(1) : n;
            if (digits.length() < 1 || digits.length() > 5) continue;
            boolean ok = true;
            for (int j = 0; j < digits.length(); j++)
                if (!Character.isDigit(digits.charAt(j))) { ok = false; break; }
            if (!ok) continue;
            return clampOffset(Long.parseLong(n) / 100.0);
        }
        return 0.0;
    }

    /* ===================================================================== *
     *  THE SETUP'S ANSWER                                                    *
     * ===================================================================== */

    /**
     * THE OFFSET A SETUP ANSWER SETS: the difference between the working pressure the person
     * says they run and the plan's figure for their level (the answer kept, as an offset,
     * instead of being clamped into the band - the owner's decision). Held to the offset's
     * reach; what the hard limits allow is applied where it is commanded.
     */
    public static double offsetFromAnswer(double answerKpa, double planFigureKpa) {
        if (Double.isNaN(answerKpa) || Double.isNaN(planFigureKpa)) return 0.0;
        double v = Math.rint((answerKpa - planFigureKpa) * 1e6) / 1e6;
        if (Math.abs(v) < 1e-6) v = 0.0;
        return clampOffset(v);
    }

    /** THE PLAN'S FIGURE A SETUP ANSWER STARTS A TRACK AT: what the setup derives for the
     *  level (TrainerTab#deriveGirth / #deriveLength), never above the track's usual top - the
     *  rest of the answer is the person's offset ({@link #offsetFromAnswer}). */
    public static double setupPlanKpa(int track, int level, int monthIndex, double derivedKpa) {
        return setupPlanKpa(track, level, monthIndex, derivedKpa, true);
    }

    /** {@link #setupPlanKpa(int,int,int,double)} against the person's own usual top (REAL-14:
     *  the first month's 6 inHg only for somebody new to pumping - the derivation already
     *  gave somebody who is not their level's band, TrainerTab#deriveGirth). */
    public static double setupPlanKpa(int track, int level, int monthIndex, double derivedKpa,
                                      boolean newToPumping) {
        return Math.min(derivedKpa, usualTopKpa(track, level, monthIndex, newToPumping));
    }

    /**
     * WHAT A RECALIBRATION PRE-FILLS AS THE WORKING-PRESSURE ANSWER (review 2, finding 3): the
     * pressure the person runs at - the plan's figure with their offset on it. It pre-filled
     * the plan's figure alone, so Confirm read the answer as "the plan" and wrote offset 0:
     * somebody on plan −2.0 inHg came out of a recalibration 2 inHg harder, unasked.
     */
    public static double recalAnswerKpa(double planKpa, double offsetKpa) {
        if (Double.isNaN(planKpa)) return planKpa;
        return planKpa + clampOffset(offsetKpa);
    }

    /**
     * THE FIGURE THE SETUP DERIVES THE PLAN'S POSITION FROM (review 2, finding 3). A first
     * setup, or an answer the person changed: the answer itself, as always. A recalibration's
     * answer left as it was pre-filled ({@link #recalAnswerKpa}, `prefillKpa`): the plan's
     * figure under it - the answer less the offset already kept - so the level, the plan's
     * figure and the offset come out as they were, and only what the person changed moves.
     * `prefillKpa` NaN: nothing was pre-filled.
     */
    public static double setupBasisKpa(double answerKpa, double prefillKpa, double keptOffsetKpa) {
        if (Double.isNaN(answerKpa) || Double.isNaN(prefillKpa)) return answerKpa;
        if (Math.abs(answerKpa - prefillKpa) > 1e-6) return answerKpa;
        return answerKpa - clampOffset(keptOffsetKpa);
    }

    /**
     * THE LOAD SETUP WRITES (review 2, finding 5). The load question is asked only of somebody
     * not new to pumping, and its warning past the usual 12 lb with it; an answer given before
     * "New to pumping" was turned on stayed hidden behind it and was saved all the same - 13 lb,
     * never warned, pulled from the second month. With New on the answer is IGNORED: a first
     * enrolment starts at the plan's own starting load, a recalibration keeps the load on file
     * (the ladder's, or one confirmed before). Not new: the answer, held to the hard 15 lb and
     * the stepper's 1 lb floor.
     */
    public static double setupLoadLb(boolean newToPumping, boolean firstEnrol, double answerLb,
                                     double onFileLb) {
        if (newToPumping)
            return firstEnrol || Double.isNaN(onFileLb) ? Plan.LENGTH_LOAD_START_LB : onFileLb;
        return Math.max(1.0, Math.min(LOAD_HARD_MAX_LB, answerLb));
    }

    /**
     * THE LOAD ANSWER THE SETUP'S ONE LENGTH VALUE GIVES (owner, 2026-09-30: the length load
     * and the length pressure are one value, linked through the length cylinder's bore). The
     * pressure answered, as a load in that cylinder, LESS the part of it the length offset
     * carries - the builder adds the offset back in its own pounds (#commandedLoadLb), so the
     * pull the plan builds is exactly the pressure answered. With no length cylinder there is
     * no load to read off it: a first enrolment takes the plan's starting load, a
     * recalibration keeps the one on file. What Confirm writes still goes through
     * {@link #setupLoadLb} (New to pumping, the 1 and 15 lb bounds).
     */
    public static double setupLinkedAnswerLb(double answerKpa, double offsetKpa, double boreCm,
                                             boolean firstEnrol, double onFileLb) {
        if (boreCm <= 0 || Double.isNaN(answerKpa))
            return firstEnrol || Double.isNaN(onFileLb) ? Plan.LENGTH_LOAD_START_LB : onFileLb;
        return Traction.loadLbAtBore(answerKpa, boreCm) - offsetLb(clampOffset(offsetKpa), boreCm);
    }

    /**
     * WHAT THE SETUP SAYS UNDER A WORKING-PRESSURE ANSWER (0.10; it used to say "Kept, not
     * corrected" while the answer was clamped into the band for girth). "" when the answer is
     * the plan's own figure. The answer is kept - as the track's offset - and this says so.
     */
    public static String setupAnswerNote(double planKpa, double offsetKpa, boolean newToPumping,
                                         int monthIndex) {
        double off = clampOffset(offsetKpa);
        // An answer that shows as the plan's own figure - the wire's whole kPa a tenth of a kPa
        // over it (-10.0 inHg is 34 kPa over the plan's 33.86) - is the plan (#offsetText).
        if (Math.abs(off) < 1e-6 || "the plan".equals(offsetText(off))) return "";
        String s = "The plan starts your level at " + Model.Fmt.p(planKpa)
            + ". Your answer is kept as your own pressure, " + offsetText(off) + ": "
            + (off > 0
               ? "your routines run " + Model.Fmt.dMag(off) + " above the plan as it steps up. "
                 + "Going past the usual top is asked once, when you confirm."
               : "your routines run " + Model.Fmt.dMag(-off) + " under the plan as it steps "
                 + "up, and that time still counts.");
        if (newMonthOne(newToPumping, monthIndex))
            s += " In your first month the plan's own pressure holds; yours starts in your "
                + "second month.";
        return s;
    }

    /**
     * HOW FAR A ROUTINE'S OR A SESSION'S SCALE MAY SIT FROM THE PLAN'S FIGURE, whole kPa: the
     * offset's reach and the Program's one step (Model.Routine#trainerScaleKpa). Every file
     * read holds a scale to it - a routine's and, since review 2 (finding 6), a session's, which
     * the Level 1 gate reads its pulls back through: a hand-edited backup with a large negative
     * scale read every pull as the plan's 8 inHg and dropped the counting line to nothing.
     */
    public static final int SCALE_REACH_KPA = (int) Math.ceil(OFFSET_MAX_KPA + BIAS_KPA);

    /** `scaleKpa` held to {@link #SCALE_REACH_KPA} either way. */
    public static int clampScaleKpa(int scaleKpa) {
        return Math.max(-SCALE_REACH_KPA, Math.min(SCALE_REACH_KPA, scaleKpa));
    }

    /**
     * WHAT START SAYS WHEN A PLAN ROUTINE WAS HELD TO TODAY'S HARD LIMITS (review 2, finding 1):
     * one line. `steps` presets were lowered, the highest now at `toKpa`. Null when none was.
     */
    public static String heldAtStartSaid(int steps, int toKpa) {
        if (steps <= 0) return null;
        return (steps == 1 ? "One pull of this routine was" : steps + " pulls of this routine were")
            + " above your limit today and run at " + Model.Fmt.p(toKpa) + " - your ceiling, "
            + "the most you said you will go to, " + Model.Fmt.p(Plan.ABSOLUTE_CAP_KPA)
            + " and a first month's limit stay hard.";
    }

    /* ===================================================================== *
     *  THE MODEL'S OWN FIGURES                                               *
     * ===================================================================== */

    /**
     * DID THIS PERSON SAY THEY ARE NEW TO PUMPING? Only an enrolled plan was asked (the setup's
     * first question, Model#rxNewToPumping); a file from before the question defaults to yes,
     * which is the beginner reading the plan always applied. A model nobody enrolled has no
     * answer to hold anybody to, and a plan with no start on record (no enrolment date, no
     * dated months answer - never an enrolment the setup wrote, which stamps both) cannot say
     * which month of pumping it is in.
     */
    public static boolean isNew(Model m) {
        return m != null && m.rxNewToPumping && m.trainerEnrolled
            && (m.trainerEnrolledAt > 0L || m.trainerMonthsAt > 0L);
    }

    /** The track state whose offset a track uses: length its own, girth and the feeder
     *  girth's. */
    public static Model.TrainerTrackState stateOf(Model m, int track) {
        if (m == null) return null;
        return track == Plan.TRACK_LENGTH ? m.trainerLength : m.trainerGirth;
    }

    /** The track's offset setting, kPa (0 with none). */
    public static double offsetKpa(Model m, int track) {
        Model.TrainerTrackState st = stateOf(m, track);
        return st == null ? 0.0 : clampOffset(st.offsetKpa);
    }

    /** The offset that applies to `track` this month. */
    public static double appliedOffsetKpa(Model m, int track, int monthIndex) {
        return m == null ? 0.0
            : appliedOffsetKpa(offsetKpa(m, track), isNew(m), monthIndex);
    }

    /**
     * "MOST YOU WILL GO TO" FOR A TRACK, kPa (0 = not stated): length its own
     * (Model#rxLengthMaxKpa), girth and the feeder - which is girth's - the girth answer. One
     * answer used to serve both tracks, so length stopped at the girth maximum.
     */
    public static double mostKpa(Model m, int track) {
        if (m == null) return 0.0;
        return track == Plan.TRACK_LENGTH ? m.rxLengthMaxKpa : m.rxWorkMaxKpa;
    }

    /** {@link #hardKpa} for this model this month, for `track` - its own maximum. */
    public static int hardKpa(Model m, int track, int monthIndex) {
        return m == null ? 1 : hardKpa(m.ceilKpa, mostKpa(m, track), isNew(m), monthIndex);
    }

    /** {@link #pullHardKpa} for this model, for `track` - a traction pull is length's. */
    public static int pullHardKpa(Model m, int track) {
        return m == null ? 1 : pullHardKpa(m.ceilKpa, mostKpa(m, track));
    }

    /**
     * THE HARD LIMIT FOR A PLAN'S WORK THAT DOES NOT PULL, for `track` this month: the track's
     * own (#hardKpa) - and for a length session, whose expansion runs in the GIRTH cylinder,
     * the lower of length's and girth's (review I3, the controller's ruling 2026-09-30, the
     * safer reading): the coda expands girth tissue in the girth tube, so the most the person
     * said they will go to for girth binds it as well. A traction pull answers to its load
     * instead (#pullCapKpa, #stagePullCapKpa).
     */
    public static int workHardKpa(Model m, int track, int monthIndex) {
        int hard = hardKpa(m, track, monthIndex);
        if (m == null || track != Plan.TRACK_LENGTH) return hard;
        return Math.min(hard, hardKpa(m, Plan.TRACK_GIRTH_INTERVAL, monthIndex));
    }

    /**
     * THE CYLINDER A TRACTION STAGE PULLS IN, as the run resolves it: the one it names, or the
     * active one when it names none (SessionActivity#cylinderForStage). Null when it names one
     * that is gone, or there is no rack.
     */
    public static Model.Cylinder stageCylinder(Model m, Model.Stage st) {
        if (m == null || st == null) return null;
        if (st.cylinderId != null && st.cylinderId.length() > 0) return m.cylinderById(st.cylinderId);
        return m.cylinder();
    }

    /** Does the stage pull in a cylinder marked for length (#stageCylinder)? */
    public static boolean stagePullsInLength(Model m, Model.Stage st) {
        Model.Cylinder c = stageCylinder(m, st);
        return c != null && c.isLength();
    }

    /**
     * THE BORE A TRACTION STAGE'S LOAD IS WORKED OUT AT (review I1, 2026-09-30): its own
     * cylinder's (#stageCylinder), marked for length or not - it is the tube it pulls in. When
     * that tube is gone, the widest one listed: a pressure pulls most there, so the load limit
     * worked out at it is the most restrictive the rack allows. 0 with no rack at all.
     */
    public static double stageBoreCm(Model m, Model.Stage st) {
        Model.Cylinder c = stageCylinder(m, st);
        if (c != null) return c.boreCm;
        if (m == null) return 0.0;
        double widest = 0.0;
        for (int i = 0; i < m.cylinders.size(); i++)
            if (m.cylinders.get(i) != null) widest = Math.max(widest, m.cylinders.get(i).boreCm);
        return widest;
    }

    /**
     * THE MOST A TRACTION STAGE'S PULL MAY BE COMMANDED AT IN A RUN, whole kPa (review I1,
     * 2026-09-30). The limit came from the length cylinder of the moment (Model#lengthBoreCm),
     * not the tube the stage pulls in: with no cylinder marked for length - a tube re-marked
     * for girth, or deleted - it fell back to the pressure limit alone, and the 15 lb load
     * limit and a new person's first-month 4 lb went with it.
     *
     * Now it is the load limit at the stage's own bore (#stageBoreCm, #pullCapKpa). And when
     * the stage's tube cannot be resolved as a length cylinder, the pull is also never raised
     * past the routine's own saved pull (`savedPullKpa`, the set as the plan wrote it): what the
     * run commands stays what was built under a load limit. Never the pressure limit alone.
     */
    public static int stagePullCapKpa(Model m, Model.Stage st, int savedPullKpa, long atMs) {
        double bore = stageBoreCm(m, st);
        int cap = bore > 0 ? pullCapKpa(m, bore, atMs) : pullHardKpa(m, Plan.TRACK_LENGTH);
        if (!stagePullsInLength(m, st)) cap = Math.min(cap, Math.max(1, savedPullKpa));
        return Math.max(1, cap);
    }

    /** The effective top, whole kPa, for `track` at `level` this month. */
    public static int effectiveTopWholeKpa(Model m, int track, int level, int monthIndex) {
        return effectiveTopWholeKpa(track, level, monthIndex, offsetKpa(m, track), isNew(m));
    }

    /**
     * G2 - THE CEILING A TRACK CLIMBS UNDER, whole kPa: the device ceiling - and for a length
     * track that pulls, the pressure that makes the hard load limit in the length cylinder
     * (15 lb), floored as #pullCapKpa is, because the pull follows the length pressure up.
     */
    static int climbCeilKpa(Model m, int track, int monthIndex) {
        if (m == null) return 1;
        int ceil = m.ceilKpa;
        double bore = m.lengthBoreCm();
        if (track == Plan.TRACK_LENGTH && m.lengthPulls() && bore > 0)
            ceil = Math.min(ceil, (int) Math.floor(Traction.kpaForLbAtBore(
                loadHardMaxLb(isNew(m), monthIndex), bore) + 1e-9));
        return Math.max(1, ceil);
    }

    /** G2 - {@link #climbTopWholeKpa} for this model's track this month. */
    public static int climbTopWholeKpa(Model m, int track, int level, int monthIndex) {
        if (m == null) return 1;
        return climbTopWholeKpa(track, level, monthIndex, offsetKpa(m, track), isNew(m),
                                mostKpa(m, track), climbCeilKpa(m, track, monthIndex));
    }

    /** G2 - whether this model's track climbs past its effective top this month. */
    public static boolean climbs(Model m, int track, int level, int monthIndex) {
        return m != null && track != Plan.TRACK_FEEDER
            && climbTopWholeKpa(m, track, level, monthIndex)
               > effectiveTopWholeKpa(m, track, level, monthIndex);
    }

    /** G2 - {@link #planTopKpa} for this model's track this month. */
    public static double planTopKpa(Model m, int track, int level, int monthIndex) {
        if (m == null) return usualTopKpa(track, level, monthIndex);
        return planTopKpa(track, level, monthIndex, climbCeilKpa(m, track, monthIndex),
                          limitsOf(m, track));
    }

    /** THE LOAD A TRACTION PULL IS BUILT AT TODAY for this model: the length track's own load
     *  with its offset in pounds (#commandedLoadLb). What the builder pulls, and what every
     *  card that states a pull states. */
    public static double pullLoadLb(Model m, long atMs) {
        if (m == null) return 0.0;
        return pullLoadLb(m, m.trainerLength.loadLb, atMs);
    }

    /** The same for a load the plan proposes (`planLoadLb`) rather than the one it has. */
    public static double pullLoadLb(Model m, double planLoadLb, long atMs) {
        if (m == null) return planLoadLb;
        int month = TrainerTab.monthIndexNow(m, atMs);
        return commandedLoadLb(planLoadLb, appliedOffsetKpa(m, Plan.TRACK_LENGTH, month),
                               m.lengthBoreCm(), isNew(m), month);
    }

    /**
     * THE MOST A TRACTION PULL MAY BE COMMANDED AT, whole kPa: the pull's hard limit (the
     * ceiling, "Most you will go to", 15 inHg) and the pressure that makes the hard load limit
     * in the length cylinder of this bore (15 lb, or a new person's first-month 4 lb) -
     * FLOORED, because the wire's whole kPa rounded up would put the pull a fraction of a
     * pound past the limit it is held to. With no bore only the pressure limit is known.
     */
    public static int pullCapKpa(Model m, double boreCm, long atMs) {
        int cap = pullHardKpa(m, Plan.TRACK_LENGTH);
        if (m == null || boreCm <= 0) return cap;
        double lb = loadHardMaxLb(isNew(m), TrainerTab.monthIndexNow(m, atMs));
        return Math.max(1, Math.min(cap,
            (int) Math.floor(Traction.kpaForLbAtBore(lb, boreCm) + 1e-9)));
    }

    /** The pressure a traction pull is commanded at today, whole kPa, within the pull's hard
     *  limits (#pullCapKpa) - 0 with no length cylinder to convert at. */
    public static int pullKpa(Model m, long atMs) {
        if (m == null) return 0;
        double bore = m.lengthBoreCm();
        if (bore <= 0) return 0;
        return Mint.clampTractionKpa(Traction.kpaForLbAtBore(pullLoadLb(m, atMs), bore),
                                     pullCapKpa(m, bore, atMs));
    }

    /** What that pull delivers, pounds, once rounded and clamped - the bound a card states. */
    public static double deliveredLoadLb(Model m, double planLoadLb, long atMs) {
        if (m == null) return 0.0;
        double bore = m.lengthBoreCm();
        return Mint.deliveredLoadLb(pullLoadLb(m, planLoadLb, atMs), bore,
                                    pullCapKpa(m, bore, atMs));
    }

    /**
     * THE LENGTH LOAD AS EVERY SCREEN NAMES IT (t10 device walk): what the pull delivers in the
     * length cylinder at its whole-kPa command (#deliveredLoadLb) - the figure the length card
     * says it is "pulling up to" - so "Where I am" cannot print 4.6 kg beside a card's 4.5 kg
     * for one load. With no length cylinder, the load as planned (#pullLoadLb).
     */
    public static double shownLoadLb(Model m, long atMs) {
        if (m == null) return 0.0;
        if (m.lengthBoreCm() <= 0) return pullLoadLb(m, atMs);
        return deliveredLoadLb(m, m.trainerLength.loadLb, atMs);
    }

    /** The limits a prescription for `track` is built within. */
    public static Limits limitsOf(Model m, int track) {
        if (m == null) return Limits.NONE;
        // The feeder takes girth's offset through its main pressure (TrainerTab#feederInputs),
        // never a second time on its own figure.
        double off = track == Plan.TRACK_FEEDER ? 0.0 : offsetKpa(m, track);
        return new Limits(off, isNew(m), mostKpa(m, track));
    }

    /** What a prescription is built within besides the plan's own figure: the track's offset,
     *  whether the person is new to pumping, and "Most you will go to" (0 = not set). */
    public static final class Limits {
        /** No offset, not new, no stated maximum - the plan's figure against the usual top,
         *  the ceiling and 15 inHg: what every prescription was before 0.10, the first
         *  month's usual top included (REAL-14 left it to the person's own limits). */
        public static final Limits NONE = new Limits(0.0, false, 0.0, true);
        public final double offsetKpa;
        public final boolean newToPumping;
        public final double mostKpa;
        /** Whether the first month's usual top (6 inHg) holds the plan's figure: for somebody
         *  new to pumping only (REAL-14, #usualTopKpa(int,int,int,boolean)). */
        public final boolean firstMonthTop;
        public Limits(double offsetKpa, boolean newToPumping, double mostKpa) {
            this(offsetKpa, newToPumping, mostKpa, newToPumping);
        }
        private Limits(double offsetKpa, boolean newToPumping, double mostKpa,
                       boolean firstMonthTop) {
            this.offsetKpa = clampOffset(offsetKpa);
            this.newToPumping = newToPumping;
            this.mostKpa = mostKpa;
            this.firstMonthTop = firstMonthTop;
        }
    }

    /* ===================================================================== *
     *  WORDS                                                                 *
     * ===================================================================== */

    /** "the plan", or "plan +1.0 inHg" / "plan −0.5 inHg" - plus meaning more pressure, in the
     *  person's own unit (a vacuum's sign is not the direction). */
    public static String offsetText(double offsetKpa) {
        double v = clampOffset(offsetKpa);
        // An offset that shows as zero in the person's unit is the plan - setup's length answer
        // of -10.0 inHg (34 kPa) over the plan's 33.9 read "plan +0.0 inHg" beside girth's "the
        // plan" (device walk, E-M4).
        if (Math.abs(v) < 1e-6 || Model.Fmt.dMag(Math.abs(v)).equals(Model.Fmt.dMag(0.0)))
            return "the plan";
        return "plan " + (v > 0 ? "+" : "−") + Model.Fmt.dMag(Math.abs(v));
    }

    /** The one-time warning's title. */
    public static final String WARN_TITLE = "Past the usual top?";

    /** "girth" / "length" - how a warning names the track. */
    static String trackWord(int track) {
        return track == Plan.TRACK_LENGTH ? "length" : "girth";
    }

    /**
     * THE ONE-TIME WARNING FOR AN OFFSET ABOVE THE PLAN (the owner's decision: warned once,
     * your call). Says what the offset does to the track's top at this level, how high it can
     * take a routine as the plan steps up, that the hard limits do not move, and - for a length
     * track that pulls - what it does to the pull in pounds. `hardKpa` is the lowest of the
     * ceiling, "Most you will go to" and 15 inHg; `boreCm` the length cylinder's bore, 0 when
     * the track does not pull.
     */
    public static String offsetWarning(int track, int level, int monthIndex, double offsetKpa,
                                       int hardKpa, double boreCm, boolean newToPumping) {
        double usual = usualTopKpa(track, level, monthIndex, newToPumping);
        double reach = Math.min(usual + clampOffset(offsetKpa), hardKpa);
        StringBuilder b = new StringBuilder();
        b.append(Character.toUpperCase(offsetText(offsetKpa).charAt(0)))
         .append(offsetText(offsetKpa).substring(1))
         .append(" takes your ").append(trackWord(track))
         .append(" work past the usual top for Level ").append(level)
         .append(", ").append(Model.Fmt.p(usual)).append(": up to ")
         .append(Model.Fmt.p(reach)).append(" as the plan steps up. The usual top is there "
            + "for a reason - go past it only if you already train there and it suits you.");
        double lb = boreCm > 0 ? offsetLb(clampOffset(offsetKpa), boreCm) : 0.0;
        // Never "about 0 kg more" (E-I2): a pull the offset does not move in the unit shown
        // is not said to move.
        if (boreCm > 0 && !Traction.settingLb(lb).equals(Traction.settingLb(0.0))) {
            // Past the plan's usual load for this month - the first month's four pounds, then
            // twelve (#usualLoadCapLb, plan simulator t8 finding 1).
            b.append(" Your traction pulls move with it, about ")
             .append(Traction.settingLb(lb)).append(" more in your length cylinder, and can go "
                + "past the "
                + "usual ").append(Traction.settingLb(usualLoadCapLb(monthIndex)))
             .append(" - never past ").append(Traction.settingLb(LOAD_HARD_MAX_LB)).append('.');
        }
        b.append(" Nothing goes past ").append(Model.Fmt.p(hardKpa))
         .append(": your ceiling, the most you said you will go to and ")
         .append(Model.Fmt.p(Plan.ABSOLUTE_CAP_KPA)).append(" stay hard.");
        if (newToPumping && monthIndex < 1)
            b.append(" In your first month the plan's own pressure holds; this starts in your "
                + "second.");
        b.append(" You are asked once - a higher offset asks again.");
        return b.toString();
    }

    /**
     * G2 - THE PARAGRAPH THE ONE-TIME WARNING ADDS WHEN "MOST YOU WILL GO TO" IS ABOVE THE
     * USUAL TOP (the owner's decision, 2026-10-01): the plan keeps stepping up, at the track's
     * own step, until it reaches that maximum - and, for a length track that pulls (`boreCm`
     * its length cylinder's bore, 0 when it does not pull), that the pull follows the length
     * pressure past the usual load, never past the hard load limit. "" when the maximum is
     * not above the top. In the person's own units.
     */
    public static String climbWords(int track, int level, int monthIndex, double offsetKpa,
                                    boolean newToPumping, double mostKpa, int ceilKpa,
                                    double boreCm) {
        int ceil = ceilKpa;
        if (track == Plan.TRACK_LENGTH && boreCm > 0)
            ceil = Math.min(ceil, (int) Math.floor(Traction.kpaForLbAtBore(
                loadHardMaxLb(newToPumping, monthIndex), boreCm) + 1e-9));
        if (!climbs(track, level, monthIndex, offsetKpa, newToPumping, mostKpa, ceil))
            return "";
        int eff = effectiveTopWholeKpa(track, level, monthIndex, offsetKpa, newToPumping);
        int top = climbTopWholeKpa(track, level, monthIndex, offsetKpa, newToPumping, mostKpa,
                                   ceil);
        boolean length = track == Plan.TRACK_LENGTH;
        StringBuilder b = new StringBuilder();
        b.append("The most you will go to for ").append(trackWord(track))
         .append(" is above its usual top of ").append(Model.Fmt.p(eff))
         .append(", so the plan keeps stepping up ").append(Model.Fmt.dMag(Plan.STEP_HG_KPA))
         .append(length ? " about every month"
                        : " every " + Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS
                          + " training weeks while your sessions hold their minutes")
         .append(" until it reaches ").append(Model.Fmt.p(top)).append('.');
        if (length && boreCm > 0) {
            double lb = Math.min(LOAD_HARD_MAX_LB, Traction.loadLbAtBore(top, boreCm));
            b.append(" Your traction pulls follow the length pressure in your length cylinder, "
                + "so they pass the usual ").append(Traction.settingLb(usualLoadCapLb(monthIndex)))
             .append(" and reach about ").append(Traction.settingLb(lb))
             .append(" - never past ").append(Traction.settingLb(LOAD_HARD_MAX_LB)).append('.');
        }
        return b.toString();
    }

    /** The warning's confirm and keep buttons. */
    public static String warnGo(double offsetKpa) {
        return "Go to " + offsetText(offsetKpa);
    }
    public static final String WARN_KEEP = "Keep it as it was";

    /** The one-time warning for a traction load answered past the usual twelve pounds. */
    public static String loadWarning(double lb) {
        return loadWarning(lb, 1);
    }

    /**
     * The same past the plan's usual load for `monthIndex` (#usualLoadCapLb): twelve pounds,
     * or in a first month the four the plan holds it to - in the person's load unit.
     */
    public static String loadWarning(double lb, int monthIndex) {
        double usual = usualLoadCapLb(monthIndex);
        return "A load of " + Traction.settingLb(lb) + " is past the usual "
            + Traction.settingLb(usual)
            + (usual < Traction.LOAD_MAX_LB
               ? " the plan holds a first month to" : " the plan's own steps stop at")
            + ". Go past it only if you already pull that much. Nothing goes past "
            + Traction.settingLb(LOAD_HARD_MAX_LB) + ", your ceiling or the most you said you "
            + "will go to.";
    }

    /** The Program's pressure choices as the picker names them - what each one does. */
    public static String biasName(int programPressure) {
        String step = Model.Fmt.dMag(BIAS_KPA);
        if (programPressure == Model.Program.PRESS_GENTLE) return "gentle (plan −" + step + ")";
        if (programPressure == Model.Program.PRESS_FIRM) return "firm (plan +" + step + ")";
        return "standard (the plan)";
    }
}
