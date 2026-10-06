package org.openpump;

/**
 * THE CYLINDER AS A PISTON — the one place a load in pounds is ever worked out.
 *
 * A close-fitting cylinder makes the penis a piston. The pressure field inside is uniform, so
 * the net AXIAL force appears only where tissue crosses the vacuum boundary at the base seal:
 *
 *     F = dP * A          A = C^2 / 4pi          (C = erect girth, the circumference)
 *
 * The area comes from the MEASURED erect girth, never from the bore: the bore only validates
 * which mode the cylinder is for. Everything this class returns is therefore a governed UPPER
 * BOUND — friction and tissue compliance subtract from it. Nothing here may be presented as a
 * measured tension.
 *
 * WHY A CLASS AND NOT A MULTIPLY AT EACH CALL SITE. Two tracks pull in opposite directions:
 * on girth a bigger bore is GENTLER, on traction force goes as diameter squared so a bigger
 * bore is HARDER (5.0in -> 6.0in at 5 inHg is 4.9 -> 7.0 lb, +44%). A stray inline multiply on
 * the wrong side of that inversion is a safety bug, not a rounding bug. Every load shown or
 * commanded converts through here.
 *
 * THE CONSTANT IS DERIVED, NOT TYPED. The guidance gives F(lb) = 0.0391 * C(in)^2 * P(inHg). That
 * 0.0391 is a rounded PRINT of PSI_PER_INHG / 4pi (= 0.03908...); typing the rounded form would
 * bake a third-digit error into every load and make the identity unfalsifiable. Only
 * PSI_PER_INHG is a measured primitive here, and the unit conversions themselves are borrowed
 * from {@link Model.Fmt}, which already owns kPa<->inHg and cm<->in for the whole app.
 *
 * PURE, so test.sh discovers it: no `import android`, no state, no I/O.
 */
public final class Traction {
    private Traction() { }

    /** 1 inHg in psi. The one measured primitive in this file. */
    public static final double PSI_PER_INHG = 0.4911541;

    /* ---- fit bands, on the ratio of bore to tissue diameter ------------------------- */

    /**
     * The classification of a cylinder against a girth. The ratio is bore / (girth / pi),
     * which is identically the bore's CIRCUMFERENCE over the girth — the form the guidance
     * states the ideal in (a bore circumference 10-15% over the girth).
     *
     * FIT_UNKNOWN exists because a pure function must still answer when there is nothing to
     * answer with: no measured girth, or no bore. It is not a sixth band, it is the absence of
     * the question — callers must say "measure first" rather than guess a band.
     */
    public static final int FIT_UNKNOWN = -1;
    public static final int FIT_TOO_TIGHT = 0;
    public static final int FIT_TRACTION = 1;
    public static final int FIT_GIRTH_IDEAL = 2;
    public static final int FIT_GIRTH_OVERSIZE = 3;
    public static final int FIT_TOO_LOOSE = 4;

    /** Band edges. Each is the INCLUSIVE floor of the band it names. */
    public static final double FIT_TRACTION_MIN = 0.92;
    public static final double FIT_GIRTH_IDEAL_MIN = 1.10;
    public static final double FIT_GIRTH_OVERSIZE_MIN = 1.16;
    public static final double FIT_TOO_LOOSE_MIN = 1.32;

    /* ---- load ceilings -------------------------------------------------------------- */

    /** The working vacuum cap, in pounds — the guidance's length progression caps a vacuum
     *  hanger at 12 lb. */
    public static final double LOAD_MAX_LB = 12.0;

    /**
     * THE PLAN'S MAXIMUM, in pounds - the same twelve as {@link #LOAD_MAX_LB}, the most the
     * plan's own steps ever reach. It is not the hard limit: that is fifteen pounds
     * (Scale#LOAD_HARD_MAX_LB), and only a person's own answer, offset or "Most you will go
     * to" takes a pull past twelve toward it - warned once, their call (the owner's rule, t10
     * A9). Nothing ever passes fifteen.
     *
     * The comment here used to call twelve the limit nothing may pass and fifteen a figure
     * from nowhere in the guidance. The owner's later rule is the one above: twelve is the plan's
     * maximum, fifteen is hard (t10 R-48, APP-FIXES 22).
     */
    public static final double LOAD_ABS_LB = LOAD_MAX_LB;

    /** Which ceiling the user is actually against — the UI must be able to say WHICH. */
    public static final int BINDS_DEVICE = 0;
    public static final int BINDS_LOAD = 1;

    /* ---- the core, in the guidance's own units: inches and inHg --------------------- */

    /** Cross-section in square inches from an erect girth in inches: A = C^2 / 4pi. */
    public static double areaIn2(double girthIn) {
        if (girthIn <= 0) return 0;
        return girthIn * girthIn / (4.0 * Math.PI);
    }

    /**
     * Pounds produced per inHg at this girth — the table's own column. 5.0in gives 0.98,
     * 5.5in gives 1.18. This is the whole physics; everything else is a multiply or a divide.
     */
    public static double lbPerInHg(double girthIn) {
        return areaIn2(girthIn) * PSI_PER_INHG;
    }

    /** Load in pounds at a pressure in inHg. */
    public static double loadLbAtInHg(double inHg, double girthIn) {
        return lbPerInHg(girthIn) * inHg;
    }

    /**
     * The inverse the mint needs: what pressure produces this load at this girth. Returns 0
     * when the girth is unknown — a caller with no girth must not command anything.
     */
    public static double inHgForLb(double lb, double girthIn) {
        double per = lbPerInHg(girthIn);
        if (per <= 0) return 0;
        return lb / per;
    }

    /* ---- the app's own units: centimetres and kPa ----------------------------------- */

    /** Girth and bore are stored in cm; the guidance is in inches. Converted HERE, once. */
    public static double inFromCm(double cm) { return cm / Model.Fmt.CM_PER_IN; }

    public static double inHgFromKpa(double kpa) { return kpa / Model.Fmt.KPA_PER_INHG; }
    public static double kpaFromInHg(double inHg) { return inHg * Model.Fmt.KPA_PER_INHG; }

    /** Load in pounds at a commanded pressure in kPa, for a girth in cm. */
    public static double loadLb(double kpa, double girthCm) {
        return loadLbAtInHg(inHgFromKpa(kpa), inFromCm(girthCm));
    }

    /**
     * The pressure in kPa that produces this load at this girth — the mint's entry point.
     * The caller still has to round it to a whole kPa, because that is all the wire carries
     * (Proto#addPreset); this returns the unrounded figure so the rounding happens once, at
     * the point that knows it is talking to the device.
     */
    public static double kpaForLb(double lb, double girthCm) {
        return kpaFromInHg(inHgForLb(lb, inFromCm(girthCm)));
    }

    /* ---- THE LENGTH CYLINDER'S BORE: how the app converts (owner, 2026-09-30) -------- */

    /*
     * THE LOAD AND THE PRESSURE ARE CONVERTED BY THE LENGTH CYLINDER'S BORE, never by a girth.
     * The owner's decision: the tube the length track pulls in is the one fixed, known
     * dimension in the system, and a pull that waited for an erect girth to be logged - or
     * moved every time one was - made two figures that were meant to be one (the load and the
     * pressure) drift apart on the setup and the cards. A cylinder marked for length now pulls
     * straight away, converted at its own bore.
     *
     * THE SAME PHYSICS, the bore standing where the tissue diameter stood: A = pi * (bore/2)^2.
     * A girth of pi * bore gives exactly this area through {@link #areaIn2}, which is what the
     * test pins - but these are named entry points rather than a faked girth at the call
     * sites, so nobody reading a call can mistake which dimension it converts at. The girth
     * methods above stay for the fit check (Model#tractionFit), which still compares the bore
     * with a logged girth and WARNS - it no longer decides whether anything pulls.
     */

    /** Cross-section in square inches from a bore (inside diameter) in inches: pi * d^2 / 4. */
    public static double areaIn2AtBore(double boreIn) {
        if (boreIn <= 0) return 0;
        return Math.PI * boreIn * boreIn / 4.0;
    }

    /** Pounds per inHg in a cylinder of this bore, in inches. */
    public static double lbPerInHgAtBore(double boreIn) {
        return areaIn2AtBore(boreIn) * PSI_PER_INHG;
    }

    /** Load in pounds at a commanded pressure in kPa, in a cylinder of this bore in cm. 0 with
     *  no bore - a caller with no length cylinder has nothing to convert at. */
    public static double loadLbAtBore(double kpa, double boreCm) {
        return lbPerInHgAtBore(inFromCm(boreCm)) * inHgFromKpa(kpa);
    }

    /** The pressure in kPa that makes this load in a cylinder of this bore, unrounded (the
     *  caller rounds once, where it talks to the device - see {@link #kpaForLb}). 0 with no
     *  bore: nothing may be commanded without one. */
    public static double kpaForLbAtBore(double lb, double boreCm) {
        double per = lbPerInHgAtBore(inFromCm(boreCm));
        if (per <= 0) return 0;
        return kpaFromInHg(lb / per);
    }

    /** The most the DEVICE can pull in this bore at its pressure ceiling. */
    public static double deviceMaxLbAtBore(double boreCm, double ceilKpa) {
        return loadLbAtBore(ceilKpa, boreCm);
    }

    /** {@link #maxLoadLb} at a bore: the tighter of the device's reach and the policy cap. */
    public static double maxLoadLbAtBore(double boreCm, double ceilKpa, double capLb) {
        double dev = deviceMaxLbAtBore(boreCm, ceilKpa);
        return dev < capLb ? dev : capLb;
    }

    /** {@link #binding} at a bore - {@link #BINDS_DEVICE} or {@link #BINDS_LOAD}. */
    public static int bindingAtBore(double boreCm, double ceilKpa, double capLb) {
        return deviceMaxLbAtBore(boreCm, ceilKpa) < capLb ? BINDS_DEVICE : BINDS_LOAD;
    }

    /* ---- the two ceilings, and which one binds -------------------------------------- */

    /** The most the DEVICE can produce at this girth, given its pressure ceiling. */
    public static double deviceMaxLb(double girthCm, double ceilKpa) {
        return loadLb(ceilKpa, girthCm);
    }

    /**
     * The governed maximum: the tighter of the device's reach and the policy cap in pounds.
     *
     * The crossover is real and worth knowing: at the device's 15 inHg a girth produces
     * 0.586 * C^2 lb, so the 15 lb absolute cap is only reached at C = sqrt(15/0.586) = 5.06in.
     * BELOW about 5.1in of girth the device runs out first and no load rule can be met; above
     * it, the load cap binds and the device has headroom to spare. The UI must say which,
     * because "turn it up" is useless advice against a device that is already at its ceiling.
     */
    public static double maxLoadLb(double girthCm, double ceilKpa, double capLb) {
        double dev = deviceMaxLb(girthCm, ceilKpa);
        return dev < capLb ? dev : capLb;
    }

    /** Which of the two ceilings is the binding one — {@link #BINDS_DEVICE} or {@link #BINDS_LOAD}. */
    public static int binding(double girthCm, double ceilKpa, double capLb) {
        return deviceMaxLb(girthCm, ceilKpa) < capLb ? BINDS_DEVICE : BINDS_LOAD;
    }

    /* ---- fit ------------------------------------------------------------------------ */

    /**
     * Bore over tissue diameter — identically the bore's circumference over the girth.
     * Returns 0 when either side is unknown, which {@link #fit} reads as FIT_UNKNOWN.
     */
    public static double fitRatio(double boreCm, double girthCm) {
        if (boreCm <= 0 || girthCm <= 0) return 0;
        double dTissueCm = girthCm / Math.PI;
        return boreCm / dTissueCm;
    }

    /**
     * The band this cylinder falls in for this girth.
     *
     * DERIVED AT READ TIME, NEVER STORED. As girth grows a girth cylinder drifts toward a
     * traction fit, and the rack has to say so; a stored label would keep insisting on what
     * the cylinder was bought as.
     */
    public static int fit(double boreCm, double girthCm) {
        double r = fitRatio(boreCm, girthCm);
        if (r <= 0) return FIT_UNKNOWN;
        if (r < FIT_TRACTION_MIN) return FIT_TOO_TIGHT;
        if (r < FIT_GIRTH_IDEAL_MIN) return FIT_TRACTION;
        if (r < FIT_GIRTH_OVERSIZE_MIN) return FIT_GIRTH_IDEAL;
        if (r < FIT_TOO_LOOSE_MIN) return FIT_GIRTH_OVERSIZE;
        return FIT_TOO_LOOSE;
    }

    /** True when this cylinder is a usable traction tube — the length track's precondition. */
    public static boolean pulls(int fitBand) { return fitBand == FIT_TRACTION; }

    /** True when the oversize petechiae reduction is the rule for this cylinder. */
    public static boolean oversize(int fitBand) { return fitBand == FIT_GIRTH_OVERSIZE; }

    /** The band's name, for a chip or a line. */
    public static String fitLabel(int fitBand) {
        if (fitBand == FIT_TOO_TIGHT)       return "too tight";
        if (fitBand == FIT_TRACTION)        return "length";
        if (fitBand == FIT_GIRTH_IDEAL)     return "girth";
        if (fitBand == FIT_GIRTH_OVERSIZE)  return "girth, oversize";
        if (fitBand == FIT_TOO_LOOSE)       return "too loose";
        return "measure first";
    }

    /** How far a girth cylinder's bore circumference sits over the girth, in per cent. */
    public static double overGirthPct(double boreCm, double girthCm) {
        double r = fitRatio(boreCm, girthCm);
        if (r <= 0) return 0;
        return (r - 1.0) * 100.0;
    }

    /* ===================================================================== *
     *  HOW A POUND FIGURE IS WORDED                                         *
     *                                                                       *
     *  The class that owns the physics owns the sentence, because the       *
     *  sentence is part of the physics. Everything here returns a GOVERNED   *
     *  UPPER BOUND: friction at the seal and the tissue's own compliance     *
     *  both subtract from it, neither is visible to the pump, and nothing    *
     *  in this app measures the tension that is actually left. A bare figure *
     *  on a card is a claim of measured tension, and it would be false.      *
     * ===================================================================== */

    /*
     * IN THE PERSON'S LOAD UNIT (owner, 2026-09-30): the three wordings below draw the figure
     * through Model.Fmt#load - pounds or kilos - and every user-visible load goes through
     * one of them. The figure handed in is always POUNDS; nothing here or upstream converts.
     */

    /** The figure itself, in POUNDS, one decimal, with no trailing ".0" - 5.0 becomes "5". */
    public static String num(double lb) {
        double r = Math.round(lb * 10.0) / 10.0;
        long whole = (long) r;
        return (Math.abs(r - whole) < 1e-9) ? String.valueOf(whole) : String.valueOf(r);
    }

    /** A load the BODY is on the receiving end of - always stated as a bound. */
    public static String boundLb(double lb) { return "up to " + Model.Fmt.load(lb); }

    /** The same bound where there is no room for words - the live run readout, which sits
     *  beside a pressure on one line and may not grow. "\u2264" is read as a bound by people
     *  who could not name the symbol, and it costs one character instead of six. */
    public static String boundLbShort(double lb) { return "\u2264" + Model.Fmt.load(lb); }

    /** A figure the PLAN chose rather than one the body receives - a step, a cap, a target.
     *  Bare, because a cap is a bound by its own name and hedging a setting would make the
     *  hedge meaningless everywhere it matters. */
    public static String settingLb(double lb) { return Model.Fmt.load(lb); }
}
