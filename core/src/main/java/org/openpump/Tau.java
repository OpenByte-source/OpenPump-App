package org.openpump;

/**
 * The TISSUE ADAPTATION ASSESSMENT's arithmetic — Task 15. Pure (no `import android`),
 * so test.sh compiles it into the desktop self-test and every number below is asserted
 * on rather than eyeballed.
 *
 * WHAT THIS MEASURES, AND WHAT IT DOES NOT
 * ---------------------------------------
 * An identical short pull is run BEFORE and AFTER the work, each one starting from a
 * VENTED cuff. Each pull's rise curve is reduced to one number, tau: the time taken to
 * cover 63.2% of the distance from where the pull started to the top it reached.
 * Comparing the two gives Delta tau.
 *
 * tau SCALES WITH PUMP RATE. It is a relative index of how fast the cylinder fills
 * under a FIXED stimulus — not a physiological measurement — so it only means anything
 * when the stimulus (pressure, motor speed, duration) is identical at both ends AND
 * identical to the sessions it is being compared against. sameStimulus() is the one
 * decision about that; the editor, the summary and History all ask it, so they can
 * never give opposite answers about whether two readings are comparable.
 *
 * WHY Pf IS THE TOP AND NOT A TAIL MEAN (fix round 1)
 * --------------------------------------------------
 * THE FIRMWARE COASTS. Proto's class doc records it as seen on hardware — a drift of
 * 13.1 -> 9.0 kPa during a long upper hold, at a rate still unmeasured (H14) — and the
 * seal check exists to MEASURE that coast (Session#coastingDecayRate). The assessment commands one
 * preset with lower == upper held past the end of the window, so the whole post-rise
 * portion IS a coast. A tail mean is therefore the mean of a DECAYING segment: it sits
 * below the top the pull actually reached, drags the crossing target down with it, and
 * reports a tau that is confidently too small. Worse, the coast rate is a function of
 * SEAL QUALITY — the one thing that changes between the two ends — so a
 * coast-contaminated Pf makes Delta tau partly a leak-rate measurement, the exact
 * confusion this feature exists to avoid.
 *
 * So Pf is the TOP THE PULL REACHED, and "did it settle?" is answered by WHEN the top
 * arrived, never by the tail's level. Neither is touched by the coast.
 *
 * WHAT THE START-RELATIVE CROSSING DOES AND DOES NOT DO (corrected, fix round 2)
 * -----------------------------------------------------------------------------
 * The crossing is measured from the observed start rather than from an assumed zero:
 * `target = P0 + CROSSING_FRACTION * (top - P0)`.
 *
 * Fix round 1 justified that by claiming it is "exact from any point on a first-order
 * curve, so the residual cancels". THAT CLAIM WAS FALSE, and it is corrected here
 * because a constant justified by a false identity is worse than no justification. The
 * invariance holds only when the level the fraction is taken of is the curve's
 * ASYMPTOTE. On this rig it is not: the firmware cuts the motor at the commanded
 * setpoint, far below the pump's ultimate vacuum A, so `top` is a level partway up an
 * exponential that is never allowed to finish.
 *
 * Written out, with `A` the ultimate vacuum, `Pc` the commanded setpoint and `f` the
 * crossing fraction, a window opening at P0 on the curve `P(t) = A(1 - exp(-t/tau_phys))`
 * reports
 *
 *     reported(P0) = tau_phys * ln[ (1 - P0/A) / (1 - (1-f)*P0/A - f*Pc/A) ]
 *
 * which is independent of P0 only when Pc == A. Differentiating shows no choice of `f`
 * repairs that: the sensitivity is structural, not a tuning error.
 *
 * WHAT IT COSTS, MEASURED. At the default stimulus (Pc = 20 kPa) and a plausible
 * A = 90 kPa, the reported index runs about 4.2% LOW per kPa of start pressure — 8.5%
 * at 2 kPa, 12.9% at 3 kPa, 21.9% at BASELINE_MAX_KPA. The round-1 change was still an
 * improvement (a FIXED target of f*Pc costs 7.4% per kPa, nearly twice as much), but it
 * is a reduction, not a cancellation.
 *
 * WHY THE FEATURE SURVIVES THAT. The bias is a pure multiplier: reported = tau_phys *
 * g(P0), with the same g at both ends because f and Pc are the same by construction. So
 * in Delta tau = (after - before)/before it CANCELS EXACTLY whenever the two windows
 * open at the same pressure, whatever A is. The absolute number carries the bias and is
 * therefore a relative index only — which is all the UI ever claims for it. The DELTA is
 * clean to the extent the two starts match, so that is what is gated, tightly, by
 * PAIR_MATCH_TOL_KPA, and why SessionActivity arms each pull from a SETTLED vent (the
 * bottom of the vent is a repeatable physical point) rather than from a threshold
 * crossing (which lands wherever a frame happens to fall).
 *
 * THE WINDOW OPENS WHEN THE PULL IS COMMANDED, NOT WHEN THE FIRST FRAME LANDS (round 3)
 * ------------------------------------------------------------------------------------
 * Rounds 1 and 2 both took `tsMs[0]` as the instant the pull began and `kpa[0]` as the
 * pressure it began from. Nothing checked either, and both were wrong by however long the
 * first frame took to arrive — which is not a fault condition but a COIN FLIP ON FRAME
 * PHASE: telemetry lands every ~240 ms regardless of when the motor starts, and from
 * ARM_BELOW_KPA the default pull crosses BASELINE_MAX_KPA in about 150 ms. So the same
 * healthy session could report the right number, one about 20% low, or a refusal, decided
 * by nothing but where the frame boundary fell. Density and contiguity were both measured
 * FROM `tsMs[0]`, so a gap before it was invisible to all three of round 2's checks.
 *
 * The fix is not to bound that gap but to remove it: the caller passes the instant it
 * commanded the pull and the pressure telemetry showed at that instant, and compute()
 * prepends them as the window's own first sample. `tsMs[0]` is then the command instant
 * because it IS the command instant, and every existing check — density, contiguity, the
 * crossing, the interpolation bound — applies to the leading interval exactly as it
 * applies to any other. A lost leading frame is replaced by the reading it would have
 * carried and the window reports the identical number; too many lost, and the ordinary
 * MAX_GAP_MS refuses.
 *
 * WHY THE CALLER CAN SUPPLY THAT PRESSURE HONESTLY: arming requires a SETTLED vent — two
 * consecutive frames agreeing to within Session.VENT_FALL_NOISE_FLOOR_KPA — so the last
 * vent reading is not an extrapolation to the command instant, it is a measurement of a
 * pressure that has demonstrably stopped changing, taken a fraction of a second earlier
 * with nothing commanding the pump in between. OR it requires the VENTED STATE
 * (armStartKpa / ventedStartKpa): the app's own StopWork evidenced, then the pump quiet on
 * a live link after a low last reading — and then the start is the open air, 0 kPa, a
 * pressure the app knows rather than one it read. This pump reports NO MEASUREMENT once
 * the cuff is open, so on it that is how both ends normally arm.
 *
 * A GAP IS NOT A FLAGGED NO-READING (fix round 2)
 * ----------------------------------------------
 * A frame the device flagged 0.0 leaves an entry that says "no measurement". A frame
 * that never arrived leaves NOTHING, and no flag can see it. Both mean the same thing —
 * a stretch of the pull was not observed — and the binding rule for both is the one
 * Session#noteSample already applies to dose: never bridge it. compute() therefore
 * treats sample DENSITY and sample CONTIGUITY as first-class checks (MIN_DENSITY,
 * MAX_GAP_MS, GAP_TOL_FRACTION) rather than inferring them from flags. Without them a
 * 4.66 s dropout — deliberately shorter than the link-loss timeout, so nothing else
 * fires — turned a true 0.575 s into a confidently reported 2.641 s.
 *
 * HOLD != ASSESSMENT. Two short holds exist in this app. This one measures the rise
 * curve and returns tau. The STANDARDISATION hold (Model.Std, Session#armHold) returns
 * nothing — it standardises the USER so a tape measure is comparable. Confusing them
 * means reading a pump measurement as a tissue measurement.
 *
 * REFUSING TO REPORT
 * ------------------
 * compute() returns a Result whose tauSec is NULL whenever the samples cannot support a
 * number, with `why` saying which condition tripped. That is the point of this class:
 * an assessment that reports a confident number from contaminated data is worse than
 * one that reports nothing. The refusal conditions are listed on compute().
 *
 * THE 0.0 RULE. A telemetry reading of 0.0 kPa means NO MEASUREMENT, not atmospheric
 * pressure (Proto.Sample#noReading). It is never averaged in, never interpolated
 * across, never treated as a pressure of zero, and never accepted ON ITS OWN as evidence
 * that the cuff reached a vented baseline. Where one falls somewhere that would change the
 * answer, the answer is refused instead of adjusted.
 *
 * The vented start (ventedStartKpa) does not bend that rule, and the distinction is the
 * whole of it: the 0.0 frames there are read only as "the link is alive and the pump has
 * nothing to report", exactly as Session#ventedByInference reads them. What establishes
 * the open-air start is the app's own StopWork, evidenced by the vent watch, plus a low
 * last REAL reading and time for the open valve to finish the job. Take away either of
 * those and the same frames arm nothing.
 */
public final class Tau {

    private Tau() { }

    /* ------------------------------------------------------------- parameters */

    /** The fraction of the rise (from the observed start to the top reached) whose
     *  crossing time is reported as tau. */
    public static final double CROSSING_FRACTION = 0.632;

    /**
     * The nominal gap between telemetry frames, in ms — Proto's documented ~4.16 Hz.
     *
     * Named because the density and contiguity checks below are stated in terms of it
     * rather than in bare milliseconds: "how much of this window was actually observed"
     * is a question about frames, and the rate is a property of the protocol.
     */
    public static final long TELEMETRY_INTERVAL_MS = 240L;

    /**
     * The fraction of the frames a window's span COULD have carried that must actually
     * have arrived as real readings.
     *
     * MY CHOICE (fix round 2). Before it, MIN_REAL_SAMPLES = 3 was the only density
     * check in the class, against roughly 187 frames expected in a default 45 s window —
     * which is not a density check at all. 0.5 tolerates losing every other frame, well
     * beyond anything ordinary BLE jitter produces, and refuses a window in which more
     * time went unobserved than observed. A window that sparse is not a rise curve
     * however neatly the surviving points line up.
     */
    public static final double MIN_DENSITY = 0.5;

    /**
     * The longest a window may go with no real reading, anywhere between its start and
     * the top, before it is refused as unobserved.
     *
     * Deliberately much tighter than the 5 s Session#noteSample uses to break a dose
     * segment: that bound answers "is the link alive", this one answers "was this
     * stretch of the curve observed", and a rise curve needs the tighter of the two. One
     * second is just over four frame intervals, so a dropped frame or two passes and a
     * genuine stall does not.
     *
     * Measured between consecutive REAL readings, so a run of 0.0-flagged frames counts
     * exactly as a run of absent ones — the two mean the same thing.
     */
    public static final long MAX_GAP_MS = 1000L;

    /**
     * How large the interval actually INTERPOLATED ACROSS may be, as a fraction of the
     * tau it produces.
     *
     * MAX_GAP_MS is an absolute bound and cannot be enough on its own: the error a
     * linear interpolation makes across a gap of length h on a curve of time constant
     * tau goes as h^2/(8*tau^2), so what matters is h RELATIVE to the answer. At 0.5
     * the interpolation error is bounded near 3%; at the nominal 240 ms frame interval
     * it costs nothing until tau falls below about half a second, which is the point at
     * which a 4.16 Hz stream stops being able to describe the rise at all.
     */
    public static final double GAP_TOL_FRACTION = 0.5;

    /**
     * The highest START pressure a pull may have and still be called a rise from a
     * vented cuff.
     *
     * WHAT THIS IS FOR, restated in fix round 2 because round 1's justification rested
     * on the identity corrected in the class doc. This is a SANITY gate, not an accuracy
     * gate: it exists to catch the defect it was introduced for — a pull that began on
     * the previous phase's hold, which is 10 kPa and upward — and not to bound the
     * start-pressure bias, which it cannot. At this boundary that bias is about 21.9%
     * low on the absolute index, which is stated here rather than glossed.
     *
     * ROUND 3: it is now a guard on the CALLER'S CONTRACT rather than a live outcome.
     * The start pressure is the one the caller says the pull was commanded from, and
     * SessionActivity will not arm without a settled vent at or below ARM_BELOW_KPA, or
     * the vented state (0 kPa, armStartKpa) — so on the app's own path this can no longer
     * fire. It is kept because compute() must
     * not silently accept a start it was handed that contradicts that contract, and
     * because nothing enforces the contract from inside this class. WHY_HOT_START's
     * wording was corrected to match: it states what was observed and makes no claim
     * about why.
     *
     * That is tolerable ONLY because the bias is a common-mode multiplier: it cancels
     * exactly in Delta tau when the two windows open at the same pressure, and it is
     * PAIR_MATCH_TOL_KPA, not this constant, that guards the number anyone reads as a
     * result. The absolute tau is a relative index carrying a start-dependent scale
     * factor, and the UI says so.
     *
     * 5.0 kPa is the pressure this app already calls "released" (Model.Std#release
     * defaults to it) and more than sixteen times Session.VENT_FALL_NOISE_FLOOR_KPA, so
     * it can never be reached by telemetry jitter.
     */
    public static final double BASELINE_MAX_KPA = 5.0;

    /**
     * A settled vent must be at or below this before the pull may be armed —
     * SessionActivity waits for two consecutive frames that agree to within
     * Session.VENT_FALL_NOISE_FLOOR_KPA and are both under this.
     *
     * The SETTLING is what makes the two ends' start pressures match (fix round 2): the
     * bottom of the vent is a repeatable physical point, whereas a bare threshold
     * crossing lands wherever a frame happens to fall — and during a 4.68 kPa/s vent
     * consecutive frames are about 1.1 kPa apart, so a threshold alone would scatter the
     * two starts across more than PAIR_MATCH_TOL_KPA and lose the Delta tau to
     * "not comparable" about as often as not. This constant is the ceiling on where a
     * settled reading may be, so a vent that stalls high is not mistaken for one that
     * finished.
     *
     * IT IS ALSO THE BOUND ON THE LAST REAL READING before the vented start
     * (ventedStartKpa): a pump that went quiet at or below this, with its StopWork
     * evidenced, has vented; one that went quiet above it may be a dropout at pressure.
     */
    public static final double ARM_BELOW_KPA = 2.0;

    /**
     * The top must have arrived before the last SETTLE_TAIL_FRACTION of the window,
     * with real readings after it — that is what "the pull settled" means here.
     *
     * MY CHOICE (fix round 1), replacing a signed comparison of two tail means. That
     * test only ever refused a RISING tail, while the firmware is documented to COAST
     * DOWN through exactly the segment it was measuring, so in normal operation it
     * passed on a decaying tail and let the bias straight through. Asking WHEN the top
     * arrived instead of WHAT LEVEL the tail sat at is immune to the coast in both
     * directions.
     */
    public static final double SETTLE_TAIL_FRACTION = 0.25;

    /** Real readings required AFTER the top, so "it stopped climbing" is answered from
     *  data rather than from a single sample that happened to be the highest. */
    public static final int MIN_SETTLE_SAMPLES = 2;

    /**
     * How close another real reading must come to the highest one for that highest one
     * to count as the top.
     *
     * MY CHOICE (fix round 2). The top is the single quantity here that ONE frame could
     * capture, and the wire protocol carries no checksum: a mangled line that still
     * parses — deci-kPa "999" reads as 99.9 kPa — would otherwise set the top, move the
     * crossing target far above anything the curve reaches, and return the corrupt
     * frame's own timestamp as tau. Requiring corroboration makes a lone frame unable to
     * do that, and readings above the corroborated top are then excluded from every
     * later step rather than merely refused, so the window still yields its correct
     * number instead of being thrown away.
     *
     * 0.3 kPa is three telemetry quanta, and it is generous rather than tight for a
     * genuine top: the documented coast is about 0.1 kPa/s, i.e. 0.024 kPa per frame, so
     * a real top has a dozen frames inside this band. A corrupt frame is orders of
     * magnitude outside it. It is its own constant rather than a reuse of
     * Session.VENT_FALL_NOISE_FLOOR_KPA (numerically the same today) because the two
     * answer different questions and should be free to move apart.
     */
    public static final double TOP_CORROBORATION_KPA = 0.3;

    /**
     * How close to the COMMANDED pressure the pull must actually get, as a fraction of
     * it. The band is symmetric — REACH_FRACTION below and the same 10% above.
     *
     * BELOW: the pull did not deliver the stimulus it claims to have delivered, so its
     * curve is not the same curve as the other end's and no comparison may be built on
     * it (WHY_SHORT_PULL).
     *
     * ABOVE: a top the stimulus cannot produce (WHY_OVERSHOT). Corroboration already
     * disposes of a LONE corrupt frame; this is what remains for a top that is genuinely
     * wrong — a controller that overshot, or two corrupt frames close enough together to
     * corroborate each other. Defence in depth, deliberately, because the top sets the
     * crossing target and everything downstream of it.
     *
     * The commanded value's ONLY role in this class is those two refusals — the reported
     * number is derived entirely from observations. That asymmetry is deliberate: if the
     * value handed in were ever wrong, the effect is to refuse more often, never to
     * report more confidently.
     */
    public static final double REACH_FRACTION = 0.9;

    /**
     * How far the two ends' observed START pressures may differ and still be ONE
     * comparison.
     *
     * The absolute index moves about 4% per kPa of start pressure — measured, and stable
     * across the plausible range of the pump's ultimate vacuum (3.7%/kPa at the
     * protocol's own 57 kPa maximum, the lowest physically possible, rising towards 5%
     * as it grows), so nothing here rests on guessing that figure. The bias cancels out
     * of Delta tau only to the extent the two starts agree, so a MISMATCH of d kPa
     * leaves roughly 4*d percent of artefact in the delta. 0.5 kPa therefore admits
     * about 1.9%.
     *
     * It is affordable only because each pull is armed from a SETTLED vent
     * (ARM_BELOW_KPA), which puts both starts at the same physical point instead of
     * wherever a frame fell.
     */
    public static final double PAIR_MATCH_TOL_KPA = 0.5;

    /**
     * How far the two ends' reached TOPS may differ and still be one comparison.
     *
     * ITS OWN CONSTANT SINCE ROUND 3, and the reason is arithmetic rather than tidiness.
     * Round 2 gated both terms at PAIR_MATCH_TOL_KPA and justified the value from the
     * START sensitivity alone — but the top is the more sensitive of the two, about
     * 5.6% per kPa against the start's 4%, so 0.5 kPa on the top admitted ~2.8% while
     * the doc claimed the pair was bounded at "~2%". That is the identical criticism
     * round 1's 1.0 kPa attracted, at half the size, and it is fixed by measuring the
     * two terms separately rather than by asserting a number.
     *
     * 0.3 kPa admits about 1.7%, which puts the two terms on the same footing. The
     * WORST CASE for a displayed Delta tau is therefore the sum, about 3.6% — stated
     * here, in comparablePulls() and in SelfTest, because the suite's own worked example
     * of a real signal is +9% and a reader is entitled to know what share of a small
     * delta could be setup. Anything smaller than about 4% should not be trusted without
     * repeating the session.
     *
     * Three telemetry quanta is also the finest the two tops could be compared at: the
     * firmware stops the motor at the commanded setpoint, so a matched pair differs only
     * by quantisation and by whatever the controller overshoots.
     */
    public static final double PAIR_TOP_TOL_KPA = 0.3;

    /**
     * M4 - THE SIZE OF NORMAL VARIATION, in whole percent, as the screens state it.
     *
     * The two tolerances above bound what setup alone can put into a displayed change: about
     * 1.9% from the starts plus 1.7% from the tops, 3.6% in all. That is the "under about 4%"
     * this class has always said should not be trusted without repeating the session, and
     * it was never on a screen. It is now, from this one number: the summary says whether a
     * change is within it, Progress shades it, and a change of this size or more is the one
     * drawn as more than setup could explain. Rounded UP from the worst case, never down -
     * a smaller figure would call a pure setup artefact a result.
     */
    public static final int NOISE_PCT = 4;

    /**
     * Fewer real readings than this in the window and there is no curve to fit.
     *
     * CORRECTED IN ROUND 3. Rounds 1 and 2 both asserted here that this "can never be
     * the deciding refusal". That was wrong, in the direction of understating the code:
     * a two-reading window passes the density check (two readings across a single 250 ms
     * interval is a perfectly dense window) and reaches the top scan, where nothing
     * corroborates anything, so without this gate it would refuse as STILL_CLIMBING
     * instead of TOO_FEW. It IS the deciding refusal there, SelfTest pins it, and
     * mutating the gate turns the suite red. The note is kept only to record that the
     * claim was made twice and was false both times.
     */
    public static final int MIN_REAL_SAMPLES = 3;

    /* ------------------------------------------------------------------ arming */

    /**
     * The pressure a pull starts from when it is armed from the VENTED STATE: the open air,
     * no vacuum at all.
     *
     * NOT A 0.0 READING. Telemetry's 0.0 still means NO MEASUREMENT and is still never read
     * as a pressure (the class doc's 0.0 rule). This is the start the app ESTABLISHES from
     * other evidence — its own StopWork, evidenced by the vent watch, followed by the pump
     * going quiet on a live link after a low last reading — see {@link #ventedStartKpa}.
     */
    public static final double VENTED_START_KPA = 0.0;

    /**
     * THE VENTED START — the second way a pull may be armed, and the one this pump needs.
     *
     * WHY IT EXISTS (the owner's first hardware session, 0.1.0). The pull used to arm ONLY
     * on a settled REAL reading at or below ARM_BELOW_KPA. This pump reports 0.0 — NO
     * MEASUREMENT — once the cuff is open (Session#ventedByInference records it; that
     * session ended "No pressure detected"), so a real low reading exists only in the last
     * second of a vent's tail, if a frame happens to land there. The before-pull caught
     * one; the after-pull did not, timed out, and filed WHY_NO_START. A cuff that was
     * already open when the vent began (a routine ending in a rest, or no pull before the
     * before-test) could never arm at all. The simulator vents to nothing the same way, so
     * on it NEITHER end ever armed.
     *
     * WHAT IT ACCEPTS — all of it, or NaN:
     *   1. The app's own StopWork for this assessment has been EVIDENCED: the vent watch
     *      resolved VENTED (a real fall, measured) or VENTED_INFERRED (Session#ventedForGating).
     *      Without that, silence proves nothing.
     *   2. Since then the pump has reported NO MEASUREMENT, unbroken, for at least
     *      Session.VENT_INFER_MIN_FRAMES frames spanning Session.VENT_INFER_MIN_SPAN_MS, with
     *      no gap over Session.VENT_INFER_MAX_FRAME_GAP_MS inside the run or between its last
     *      frame and NOW — the same bounds the vent watch uses to call a quiet pump open
     *      rather than a dropout, so the two questions cannot be answered differently.
     *   3. The last REAL reading before that silence — in this window, or else at any age
     *      (`everRealKpa`) — was at or below ARM_BELOW_KPA, or there never was one this
     *      session. Silence straight after a HIGH reading is a sensor dropout AT PRESSURE,
     *      and calling that the open air would measure a rise from a held cuff.
     *
     * WHY THE START IS THEN 0 kPa, HONESTLY. With the valve open (1) and the pump last seen
     * at or below 2 kPa (3), 2.5 s of venting (2) at the measured 4.68 kPa/s covers more
     * than eleven kPa — the cuff is at the open air, which is a pressure the app knows
     * rather than one it read. It is the bottom of the vent, the repeatable physical point
     * ARM_BELOW_KPA's settling was always reaching for.
     *
     * WHAT IT COSTS. The start carries the same 4%/kPa scale on the absolute index as any
     * other; a pull armed from a settled 1.5 kPa at one end and the open air at the other
     * differs by more than PAIR_MATCH_TOL_KPA, so comparablePulls() withholds the Δτ and the
     * summary says the two did not start alike. On a pump that stops reporting once open
     * both ends normally arm here, from the same 0 kPa.
     */
    public static double ventedStartKpa(long[] tsMs, double[] kpa, boolean[] noReading,
                                         long nowMs, boolean ventEvidenced, double everRealKpa) {
        if (!ventEvidenced) return Double.NaN;
        int n = (tsMs == null || noReading == null) ? 0 : Math.min(tsMs.length, noReading.length);
        if (n == 0) return Double.NaN;
        // The trailing unbroken run of NO MEASUREMENT frames.
        int runStart = n;
        while (runStart > 0 && noReading[runStart - 1]) runStart--;
        if (n - runStart < Session.VENT_INFER_MIN_FRAMES) return Double.NaN;
        if (tsMs[n - 1] - tsMs[runStart] < Session.VENT_INFER_MIN_SPAN_MS) return Double.NaN;
        for (int i = runStart + 1; i < n; i++)
            if (tsMs[i] - tsMs[i - 1] > Session.VENT_INFER_MAX_FRAME_GAP_MS) return Double.NaN;
        if (nowMs - tsMs[n - 1] > Session.VENT_INFER_MAX_FRAME_GAP_MS) return Double.NaN;
        // What the pump last actually measured before it went quiet.
        double lastReal = (runStart > 0 && kpa != null && runStart - 1 < kpa.length)
                        ? kpa[runStart - 1] : everRealKpa;
        if (!Double.isNaN(lastReal) && lastReal > ARM_BELOW_KPA) return Double.NaN;
        return VENTED_START_KPA;
    }

    /**
     * THE ARMING DECISION: the pressure a pull may be armed from, given every frame since
     * the assessment's own vent was commanded — or NaN to keep waiting.
     *
     * Two ways, in this order:
     *   - SETTLED: the first two consecutive real readings agreeing to within
     *     Session.VENT_FALL_NOISE_FLOOR_KPA with the second at or below ARM_BELOW_KPA
     *     (Validate#settledIndexAtOrBelow). The pull starts from that reading - a pump that
     *     reports a real pressure at rest arms here. A pair that agrees ABOVE the gate (a
     *     late StopWork: the window opens on the held pressure) is passed over, not final.
     *
     * THIS IS THE LIVE DECISION, not a model of it: SessionActivity's AssessVentTick calls
     * this on every poll over the vent window's own frames, so step 1, P2 and the tests
     * replay exactly what the app does.
     *   - VENTED: {@link #ventedStartKpa}. The pull starts from the open air.
     *
     * `tsMs` / `kpa` / `noReading` are the vent window's frames in arrival order, `nowMs`
     * is the instant the question is asked, `ventEvidenced` is whether the vent watch for
     * the app's own StopWork has resolved vented (Session#ventedForGating) and
     * `everRealKpa` is the last real reading at any age, NaN if there never was one.
     */
    public static double armStartKpa(long[] tsMs, double[] kpa, boolean[] noReading,
                                      long nowMs, boolean ventEvidenced, double everRealKpa) {
        int at = Validate.settledIndexAtOrBelow(kpa, noReading, ARM_BELOW_KPA);
        if (at >= 0) return kpa[at];
        return ventedStartKpa(tsMs, kpa, noReading, nowMs, ventEvidenced, everRealKpa);
    }

    /**
     * What the summary says when one end of the assessment could not start at all — ONE
     * plain sentence. The reasoning a developer needs (the 0.0 rule, the settled vent, the
     * silence after an evidenced stop) is in the session log and in this class, not in
     * front of someone reading their result.
     */
    public static String noStartSentence(boolean after) {
        return (after ? "The after-test" : "The before-test")
             + " couldn't start: the pump didn't report a pressure to measure from.";
    }

    /* ---------------------------------------------------------------- reasons */

    /** tau was computed. */
    public static final String OK = "ok";
    /** A 0.0 reading landed where it would have changed the answer. */
    public static final String WHY_NO_READING = "no-reading";
    /** A stretch of the pull produced no readings at all — frames that never arrived,
     *  which no flag can see. */
    public static final String WHY_GAP = "gap";
    /** The pull had not stopped climbing when the window ended. */
    public static final String WHY_STILL_CLIMBING = "still-climbing";
    /** Not enough real readings — in the window, across its span, or after the top. */
    public static final String WHY_TOO_FEW = "too-few";
    /** The top is at or below the 10 kPa floor the dose uses: barely engaged. */
    public static final String WHY_LOW_PLATEAU = "low-plateau";
    /** The pull was commanded from a pressure above BASELINE_MAX_KPA, so what follows is
     *  not a rise from a vented start. Unreachable on the app's own path since arming
     *  began requiring a settled vent (round 2) or the vented state (both at or below
     *  ARM_BELOW_KPA) — a contract guard on the caller, and worded as an observation
     *  rather than a diagnosis for exactly that reason. */
    public static final String WHY_HOT_START = "hot-start";
    /**
     * No starting pressure could be established at all before the pull — the pump never
     * reported a settled reading low enough to begin from.
     *
     * SEPARATE FROM WHY_HOT_START, and fix round 2 split them because sharing one reason
     * made the app state something false. WHY_HOT_START has a real reading above the
     * gate and can say so. This one covers the case where there is NO usable reading:
     * most often a cuff that vented perfectly into the range where the device reports
     * 0.0, meaning NO MEASUREMENT. Wording the two alike told a user with a healthy
     * cuff that "the vent never completed", which is a specific claim about their
     * hardware and it was not true.
     *
     * Round 3 gave it a second producer inside compute() itself: a caller that cannot say
     * WHEN it commanded the pull, or from WHAT pressure, has not established a starting
     * point either, and the same sentence covers it.
     *
     * NO LONGER THE ORDINARY OUTCOME ON THIS PUMP. "A cuff that vented perfectly into 0.0"
     * was the commonest way here, and it was the owner's after-test on real hardware; that
     * cuff now arms from the vented state (ventedStartKpa). What is left is a vent the watch
     * could not evidence, silence straight after a high reading, or a link that stopped —
     * none of which is a known start. Its caption is one plain sentence (whyText,
     * noStartSentence); the detail above is for whoever reads the log.
     */
    public static final String WHY_NO_START = "no-start";
    /** The pull never got near the pressure it was commanded to, so it is not the same
     *  stimulus the other end was given. */
    public static final String WHY_SHORT_PULL = "short-pull";
    /** The top sat well ABOVE the pressure the pull was commanded to — not something
     *  the stimulus can produce, so the window is not trustworthy. */
    public static final String WHY_OVERSHOT = "overshot";
    /** Telemetry went silent during the window — the pull was ended and the pump
     *  stopped. */
    public static final String WHY_LINK_LOST = "link-lost";
    /** The user skipped this assessment, or the session ended before the window did.
     *  Filed as skipped, never as a tau. */
    public static final String WHY_SKIPPED = "skipped";
    /** 0.10 (S05) - not run, because the other track already ran today: one tissue response
     *  test a day, in the day's first session. The second session's tissue has been worked
     *  already, and a number from it would not be comparable with any other. */
    public static final String WHY_SECOND_SESSION = "second-session";
    /**
     * RETIRED, and kept only so a session filed by an earlier build still reads back
     * with a sentence rather than a blank. compute() can no longer produce it: it meant
     * "the first reading was already past the crossing", which was reachable only while
     * the crossing was defined against an ASSUMED zero start.
     */
    public static final String WHY_NO_RISE = "no-rise";

    /** Human wording for a `why`, for a caption under a refusal. */
    public static String whyText(String why) {
        if (WHY_NO_READING.equals(why))
            return "the pump reported no measurement where one was needed — a 0.0 reading is not "
                 + "a pressure of zero, so there is nothing to measure from or interpolate across";
        if (WHY_GAP.equals(why))
            return "part of the pull arrived as no readings at all, and a curve cannot be drawn "
                 + "across a stretch that was never observed — check the link before the next run";
        if (WHY_STILL_CLIMBING.equals(why))
            return "the pull was still climbing when the window ended — nothing had settled to "
                 + "measure against. A longer assessment duration would settle it";
        if (WHY_TOO_FEW.equals(why))
            return "too few readings arrived to describe a rise curve";
        if (WHY_LOW_PLATEAU.equals(why))
            return "the pull never rose above " + (int) Session.DOSE_FLOOR_KPA
                 + " kPa — check the seal";
        if (WHY_HOT_START.equals(why))
            return "the pull was commanded from above " + (int) BASELINE_MAX_KPA + " kPa instead "
                 + "of from a settled low pressure, so what followed is not a rise from a known "
                 + "start and nothing is reported from it";
        // PLAIN WORDS, as the owner asked: this used to be a paragraph about the 0.0 rule
        // printed under a person's result. The reasoning lives on WHY_NO_START's own doc
        // and in the session log; the caption says what happened and blames nothing.
        if (WHY_NO_START.equals(why))
            return "the pump didn't report a pressure to measure from, so the test couldn't "
                 + "start";
        if (WHY_SHORT_PULL.equals(why))
            return "the pull never reached the pressure it was commanded to, so it is not the "
                 + "same stimulus the other end was given — check the seal";
        if (WHY_OVERSHOT.equals(why))
            return "readings came back well above the pressure the pull was commanded to, "
                 + "which the stimulus cannot produce — the telemetry for this window is not "
                 + "trustworthy, so nothing is reported from it";
        if (WHY_LINK_LOST.equals(why))
            return "telemetry went silent during the pull, so the pump was stopped — if pressure "
                 + "does not clear, disconnect the tubing at the cuff";
        if (WHY_SKIPPED.equals(why))
            return "skipped — no reading was taken";
        if (WHY_SECOND_SESSION.equals(why))
            return "not run: second session today — the test runs in the day's first session";
        if (WHY_NO_RISE.equals(why))
            return "the first reading was already past the crossing, so the rise itself was "
                 + "never observed";
        return "not measured";
    }

    /* ----------------------------------------------------------------- result */

    /** What one assessment window produced. `tauSec` is null when tau is not
     *  computable — never 0.0, which would be a claim that a rise was measured and
     *  took no time. */
    public static final class Result {
        public final Double tauSec;
        /** The top the pull actually reached, in kPa, or 0 when it was never
         *  established. The highest CORROBORATED real reading, never a mean of the
         *  tail — see the class doc on the coast, and TOP_CORROBORATION_KPA. */
        public final double peakKpa;
        /** The pressure the pull was COMMANDED FROM, in kPa, or 0 when none was
         *  established. Not "the first reading in the buffer" — that is a frame-phase
         *  accident; this is the caller's measured start, prepended as the window's own
         *  first sample. The crossing target is measured from here, and the two ends'
         *  values are what comparablePulls() checks before any Delta tau is shown. */
        public final double baselineKpa;
        public final String why;

        Result(Double tauSec, double peakKpa, double baselineKpa, String why) {
            this.tauSec = tauSec; this.peakKpa = peakKpa;
            this.baselineKpa = baselineKpa; this.why = why;
        }

        public boolean ok() { return tauSec != null; }
    }

    private static Result refuse(String why, double peakKpa, double baselineKpa) {
        return new Result(null, peakKpa, baselineKpa, why);
    }

    /* ---------------------------------------------------------------- compute */

    /**
     * tau for one assessment window, from the raw telemetry it produced.
     *
     * `tsMs` / `kpa` / `noReading` are PARALLEL arrays in arrival order — the same
     * shape Session's own replay helpers take, and fed from the same sample stream, so
     * the 0.0 rule is applied identically in both places. A frame that never arrived
     * has NO ENTRY at all, which is why the timestamps are load-bearing here and not
     * merely decorative. `commandedKpa` is the pressure the pull was actually commanded
     * to (already ceiling-clamped, exactly as it went on the wire); 0 means "not known",
     * which switches the reach band off.
     *
     * `commandedAtMs` and `startKpa` are WHEN the pull was commanded and the pressure
     * telemetry showed at that instant. They are prepended as the window's own first
     * sample whenever the first frame arrived later, which is what makes the rest of
     * this method's assumption about `tsMs[0]` true rather than hopeful — see the class
     * doc. `startKpa` of NaN means the caller established no starting point at all and
     * refuses; a `commandedAtMs` after the first frame is incoherent and refuses too.
     *
     * The method:
     *   1. The command instant is prepended, so the window begins where the pull did.
     *   2. The window must be OBSERVED: at least MIN_DENSITY of the frames its span
     *      could have carried arrived as real readings.
     *   3. P0, the start, is the first reading — which is now the commanded-from
     *      pressure. It must be real and at or below BASELINE_MAX_KPA.
     *   4. Pf, the top, is the highest real reading that another real reading
     *      corroborates to within TOP_CORROBORATION_KPA. Readings above it are treated
     *      as no measurement at all for every later step.
     *   5. Nothing between the start and the top may be separated by more than
     *      MAX_GAP_MS of no real readings, and the pull must have reached the top before
     *      the final SETTLE_TAIL_FRACTION of the window with MIN_SETTLE_SAMPLES real
     *      readings after it.
     *   6. tau is the first time the pressure reaches `P0 + CROSSING_FRACTION *
     *      (Pf - P0)`, linearly interpolated between the two usable readings straddling
     *      it — and the interval interpolated across must be no more than
     *      GAP_TOL_FRACTION of the answer.
     *
     * It refuses (tauSec null) when:
     *   - fewer than MIN_REAL_SAMPLES real readings, fewer than MIN_DENSITY of the
     *     frames the span could have carried, or fewer than MIN_SETTLE_SAMPLES after the
     *     top — WHY_TOO_FEW;
     *   - a stretch longer than MAX_GAP_MS between start and top carried no real
     *     reading, or the interval interpolated across is too large a share of the
     *     answer — WHY_GAP;
     *   - the window's first reading is a 0.0, or a 0.0 falls anywhere at or before the
     *     crossing — WHY_NO_READING;
     *   - the window opened above BASELINE_MAX_KPA — WHY_HOT_START;
     *   - Pf is at or below Session.DOSE_FLOOR_KPA — WHY_LOW_PLATEAU;
     *   - no reading was ever corroborated, or the top arrived inside the final quarter
     *     of the window — WHY_STILL_CLIMBING;
     *   - Pf fell short of, or sat above, REACH_FRACTION of `commandedKpa` —
     *     WHY_SHORT_PULL / WHY_OVERSHOT;
     *   - the caller established no starting point — WHY_NO_START.
     */
    public static Result compute(long[] tsMs, double[] kpa, boolean[] noReading,
                                  int commandedKpa, long commandedAtMs, double startKpa) {
        int raw = (tsMs == null || kpa == null) ? 0 : Math.min(tsMs.length, kpa.length);
        // NO STARTING POINT. Both of these are things the caller knows by construction —
        // SessionActivity records the instant immediately before it sends, and will not
        // arm at all without a settled reading — so neither is a live outcome. They fail
        // closed rather than falling back on `tsMs[0]`, because falling back silently is
        // precisely what let the frame-phase defect live through two rounds.
        if (Double.isNaN(startKpa)) return refuse(WHY_NO_START, 0, 0);
        if (raw > 0 && commandedAtMs > tsMs[0]) return refuse(WHY_NO_START, 0, 0);

        // THE COMMAND INSTANT IS THE WINDOW'S FIRST SAMPLE. Prepended rather than handled
        // as a special case, so density, contiguity, the crossing search and the
        // interpolation bound all see the leading interval as an ordinary one and no
        // check has to be taught about it.
        long[] ts = tsMs;
        double[] ks = kpa;
        boolean[] nrs = noReading;
        if (raw > 0 && commandedAtMs < tsMs[0]) {
            ts = new long[raw + 1];
            ks = new double[raw + 1];
            nrs = new boolean[raw + 1];
            ts[0] = commandedAtMs;
            ks[0] = startKpa;
            nrs[0] = false;
            for (int i = 0; i < raw; i++) {
                ts[i + 1] = tsMs[i];
                ks[i + 1] = kpa[i];
                nrs[i + 1] = nr(noReading, i);
            }
        }

        int n = (ts == null || ks == null) ? 0 : Math.min(ts.length, ks.length);
        int real = 0;
        for (int i = 0; i < n; i++) if (!nr(nrs, i)) real++;
        if (real < MIN_REAL_SAMPLES) return refuse(WHY_TOO_FEW, 0, 0);

        long t0 = ts[0];
        long span = ts[n - 1] - t0;
        if (span <= 0) return refuse(WHY_TOO_FEW, 0, 0);

        // WAS THIS WINDOW OBSERVED AT ALL? A count of readings says nothing without the
        // stretch of time they are spread over; this is the same question the dose asks
        // when it refuses to bridge a gap, asked once over the whole window.
        if (real < MIN_DENSITY * (span / (double) TELEMETRY_INTERVAL_MS))
            return refuse(WHY_TOO_FEW, 0, 0);

        // THE START. The window opens when the pull is commanded, so the first frame is
        // the pressure it started from. A 0.0 there is NO MEASUREMENT, not atmospheric:
        // there is no baseline, and inventing one would be the fabrication the 0.0 rule
        // exists to prevent.
        //
        // HONESTY NOTE, the same admission MIN_REAL_SAMPLES carries: for the sample
        // stream this app actually produces, this line can never be the DECIDING
        // refusal. Proto.Sample sets noReading exactly when kpa == 0.0, so a flagged
        // first frame also carries 0.0, which passes the gate below and is then caught
        // by the rise-window check further down — WHY_NO_READING either way. It is kept
        // because it is the only thing that reads the FLAG rather than the value, which
        // is the actual rule (SelfTest pins that with a fixture whose flagged first frame
        // carries a non-zero value, the one case where the two answers differ), and
        // because a future change to the rise window must not silently leave a
        // no-measurement frame serving as the pressure everything else is measured from.
        if (nr(nrs, 0)) return refuse(WHY_NO_READING, 0, 0);
        double p0 = ks[0];
        if (p0 > BASELINE_MAX_KPA) return refuse(WHY_HOT_START, 0, p0);

        // THE TOP: the highest real reading that ANOTHER real reading corroborates. One
        // frame cannot set it, which is what stops a single corrupt line from choosing
        // the crossing target (TOP_CORROBORATION_KPA).
        double top = 0;
        boolean haveTop = false;
        for (int i = 0; i < n; i++) {
            if (nr(nrs, i)) continue;
            if (haveTop && ks[i] <= top) continue;
            // NEAR it, in both directions. A one-sided test ("something is at least this
            // minus the tolerance") is satisfied by any HIGHER reading, so it would only
            // ever reject the single global maximum and would call every other reading
            // corroborated — including, on a steadily climbing pull, the second-highest.
            boolean corroborated = false;
            for (int j = 0; j < n && !corroborated; j++)
                if (j != i && !nr(nrs, j)
                        && Math.abs(ks[j] - ks[i]) <= TOP_CORROBORATION_KPA)
                    corroborated = true;
            if (corroborated) { top = ks[i]; haveTop = true; }
        }
        // No reading agreed with any other to within TOP_CORROBORATION_KPA: the pressure
        // never held anywhere, which is the same statement the settled test makes and is
        // reported as such. Reachable, and the DECIDING refusal, for a pull whose every
        // frame steps further than the tolerance and which therefore has no top at all.
        if (!haveTop) return refuse(WHY_STILL_CLIMBING, 0, p0);
        // "at or below the floor" — the same 10 kPa the delivered dose refuses to
        // count, reused rather than restated so the two can never drift apart.
        if (top <= Session.DOSE_FLOOR_KPA) return refuse(WHY_LOW_PLATEAU, top, p0);

        // The FIRST time the top was reached. `top` is one of the array's own values, so
        // this always finds it; readings strictly above it are the uncorroborated ones.
        int pk = -1;
        for (int i = 0; i < n; i++)
            if (!nr(nrs, i) && !(ks[i] < top) && !(ks[i] > top)) { pk = i; break; }

        // CONTIGUITY. Measured between consecutive REAL readings, so a run of flagged
        // frames counts exactly as a run of absent ones — they mean the same thing, and
        // only one of them is visible to a flag.
        long prevAt = t0;
        for (int i = 1; i <= pk; i++) {
            if (nr(nrs, i)) continue;
            if (ts[i] - prevAt > MAX_GAP_MS) return refuse(WHY_GAP, top, p0);
            prevAt = ts[i];
        }

        // SETTLED, asked as "when did the top arrive", never as "what level is the tail
        // sitting at" — the tail is a coast, and its level says more about the seal than
        // about the fill.
        if (ts[pk] - t0 > span * (1.0 - SETTLE_TAIL_FRACTION))
            return refuse(WHY_STILL_CLIMBING, top, p0);
        int afterTop = 0;
        for (int i = pk + 1; i < n; i++) if (!nr(nrs, i)) afterTop++;
        if (afterTop < MIN_SETTLE_SAMPLES) return refuse(WHY_TOO_FEW, top, p0);

        // DID IT DELIVER THE STIMULUS, AND ONLY THE STIMULUS?
        if (commandedKpa > 0 && top < REACH_FRACTION * commandedKpa)
            return refuse(WHY_SHORT_PULL, top, p0);
        if (commandedKpa > 0 && top > (2.0 - REACH_FRACTION) * commandedKpa)
            return refuse(WHY_OVERSHOT, top, p0);

        double target = p0 + CROSSING_FRACTION * (top - p0);
        // Readings above the top are the ones nothing corroborated, so they are not
        // measurements and cannot be either end of the crossing.
        int x = -1;
        for (int i = 0; i < n; i++)
            if (!nr(nrs, i) && ks[i] <= top && ks[i] >= target) { x = i; break; }
        if (x <= 0) return refuse(WHY_STILL_CLIMBING, top, p0);
        // The lower end of the interpolation must ALSO be a usable reading. Without the
        // `<= top` test a corrupt above-top frame sitting immediately before the crossing
        // becomes the lower end, `f` clamps to 1, and tau jumps a whole frame interval.
        int w = -1;
        for (int i = x - 1; i >= 0; i--)
            if (!nr(nrs, i) && ks[i] <= top) { w = i; break; }
        // Unreachable: index 0 is real, kpa[0] = p0 <= BASELINE_MAX_KPA < target <= top.
        // Kept so a future change to how either end is derived fails closed rather than
        // running off the end of the array.
        if (w < 0) return refuse(WHY_STILL_CLIMBING, top, p0);

        // The rise window is everything up to and including the crossing. A 0.0 in here
        // would be interpolated across.
        for (int i = 0; i <= x; i++)
            if (nr(nrs, i)) return refuse(WHY_NO_READING, top, p0);

        double a = ks[w], b = ks[x];
        // a < target <= b by construction, so the denominator is positive.
        double f = (b > a) ? (target - a) / (b - a) : 0.0;
        if (f < 0) f = 0;
        if (f > 1) f = 1;
        double crossMs = (ts[w] - t0) + f * (ts[x] - ts[w]);
        // THE INTERVAL ACTUALLY INTERPOLATED ACROSS, relative to the answer it produced.
        // MAX_GAP_MS above is an absolute bound and cannot be enough on its own — the
        // error of a linear interpolation scales with the interval SQUARED over tau
        // squared, so a gap that is harmless for a ten-second rise is fatal for a
        // half-second one.
        // `crossMs <= 0` is defensive only and no assertion claims to pin it: the
        // crossing lies strictly above p0 = ks[0], so w >= 0 and x > w give a positive
        // time. It is kept so a future change to either end fails closed instead of
        // dividing by it.
        if (crossMs <= 0 || (ts[x] - ts[w]) > GAP_TOL_FRACTION * crossMs)
            return refuse(WHY_GAP, top, p0);
        return new Result(Double.valueOf(crossMs / 1000.0), top, p0, OK);
    }

    private static boolean nr(boolean[] noReading, int i) {
        return noReading != null && i < noReading.length && noReading[i];
    }

    /* ------------------------------------------------------------- delta, fmt */

    /**
     * Whether the two pulls were run from close enough conditions to be ONE comparison:
     * the same measured start pressure and the same measured top, each within
     * PAIR_MATCH_TOL_KPA.
     *
     * This is the measured half of "an identical short pull at both ends". The stimulus
     * fields (sameStimulus) say the two pulls were CONFIGURED alike; this says the two
     * pulls actually BEHAVED alike. Neither substitutes for the other.
     *
     * NEITHER CHECK IS COSMETIC, and they are gated separately because they are not
     * equally sensitive. The absolute index moves about 4% per kPa of start pressure and
     * about 5.6% per kPa of top, and both cancel out of Delta tau only to the degree the
     * two ends agree — so a mismatch is not noise around the answer, it IS the answer,
     * made of setup. At the two tolerances the worst case a displayed delta can carry is
     * about 1.9% from the starts plus 1.7% from the tops: **3.6% in total**, against the
     * suite's own worked example of a real signal of +9%. A delta smaller than about 4%
     * should not be trusted without repeating the session, and that is a property of the
     * rig rather than of this function.
     *
     * An end that produced no measurement is not comparable to anything, which includes
     * a session filed before these were recorded: its Delta tau was computed under maths
     * that did not gate the start at all, and it is better withdrawn than reprinted.
     */
    public static boolean comparablePulls(Double p0Before, Double peakBefore,
                                           Double p0After, Double peakAfter) {
        if (p0Before == null || peakBefore == null || p0After == null || peakAfter == null)
            return false;
        return Math.abs(p0Before.doubleValue() - p0After.doubleValue()) <= PAIR_MATCH_TOL_KPA
            && Math.abs(peakBefore.doubleValue() - peakAfter.doubleValue()) <= PAIR_TOP_TOL_KPA;
    }

    /**
     * Delta tau as a PERCENTAGE: (after - before) / before * 100. Null when either end
     * is missing, or when the before-reading is not a usable denominator — a bare
     * after-reading has nothing to compare against and must say so, not show a delta
     * against nothing.
     */
    public static Double deltaPct(Double beforeSec, Double afterSec) {
        if (beforeSec == null || afterSec == null) return null;
        double b = beforeSec.doubleValue();
        if (b <= 0) return null;
        return Double.valueOf((afterSec.doubleValue() - b) / b * 100.0);
    }

    /**
     * THE Delta tau for a filed session — the one decision the summary chip and the
     * History tag both ask, so the two screens can never disagree about whether a
     * session produced a delta. Null when either end is missing OR when the two pulls
     * were not run from comparable conditions.
     */
    public static Double sessionDeltaPct(Model.Sess s) {
        if (s == null) return null;
        if (!comparablePulls(s.tauBeforeP0Kpa, s.tauBeforePeakKpa,
                             s.tauAfterP0Kpa, s.tauAfterPeakKpa)) return null;
        return deltaPct(s.tauBeforeSec, s.tauAfterSec);
    }

    /** tau in seconds, one decimal — "1.4 s". Never routed through a pressure
     *  formatter: it is a time, and Model.Fmt.p would print it as a vacuum. */
    public static String fmtTau(Double tauSec) {
        if (tauSec == null) return "—";
        return String.format(java.util.Locale.US, "%.1f s", tauSec.doubleValue());
    }

    /**
     * A signed whole-percent change — "+9%", "−4%", "0%". A PERCENTAGE, never a
     * pressure: pushing this through Model.Fmt.p would print the wrong sign in inHg
     * (and a unit that has nothing to do with it).
     */
    public static String fmtDeltaPct(double pct) {
        long r = Math.round(pct);
        if (r == 0) return "0%";
        return (r > 0 ? "+" : "−") + Math.abs(r) + "%";
    }

    /* ---------------------------------------------------- schedule + stimulus */

    /** Does the assessment run BEFORE stage 1 for this routine? */
    public static boolean runsBefore(Model.Routine r) {
        if (r == null || r.assess == null || !r.assess.on) return false;
        return !Model.Assess.WHEN_AFTER.equals(r.assess.when);
    }

    /** Does the assessment run AFTER the last stage for this routine? */
    public static boolean runsAfter(Model.Routine r) {
        if (r == null || r.assess == null || !r.assess.on) return false;
        return !Model.Assess.WHEN_BEFORE.equals(r.assess.when);
    }

    /**
     * The pressure the assessment pull is actually COMMANDED to, in whole kPa — the
     * stored value clamped exactly as it is clamped on the wire. 0 when no assessment
     * runs for this routine.
     *
     * ONE derivation, because three places need this same number and a difference
     * between any two of them is a lie: the arming site puts it on the wire; Model#peak
     * folds it into the "peak" the START consent dialog states; and the summary folds it
     * into the COMMANDED peak that the observed peak is compared against.
     */
    public static int commandedKpa(Model.Routine r, int ceilKpa) {
        if (r == null || r.assess == null || !r.assess.on) return 0;
        return Math.max(5, Math.min(Math.min(57, ceilKpa), r.assess.kpa));
    }

    /**
     * THE SAME PULL, FOR A ROUTINE WHOSE WORK PEAKS AT `workPeakKpa` - the form every site
     * that arms, states or folds in the pull asks.
     *
     * A PLAN ROUTINE'S CHECK PULL NEVER EXCEEDS ITS SESSION (wave 1 §4, the owner's ruling):
     * the builders cap the stored figure at the work peak. The 5 kPa floor above then lifted it
     * back up whenever the work itself sat under 5 - a Gentle taper day runs 3 kPa - so an
     * enabled check pull armed above the whole session, the hardest pull of a day meant to be
     * the lightest (0.10). For a plan routine the floor never goes past the work. A routine
     * somebody built by hand keeps what its editor allows - a check deeper than its work is a
     * choice that editor offers, and the START dialog states it.
     */
    public static int commandedKpa(Model.Routine r, int ceilKpa, int workPeakKpa) {
        int a = commandedKpa(r, ceilKpa);
        if (a > 0 && workPeakKpa > 0 && r.trainerTrack != Model.TRAINER_TRACK_NONE
                && a > workPeakKpa)
            a = workPeakKpa;
        return a;
    }

    /** Seconds the assessment adds to the routine — the prototype's assessDur(r).
     *  "both" runs the identical pull twice, so it costs twice. */
    public static int assessDur(Model.Routine r) {
        if (r == null || r.assess == null || !r.assess.on) return 0;
        return r.assess.dur * (Model.Assess.WHEN_BOTH.equals(r.assess.when) ? 2 : 1);
    }

    /**
     * HOW LONG THE VENT BEFORE AN ASSESSMENT PULL MAY TAKE, at most - SessionActivity's own
     * window (its doc there says why 20 s, and why it is not lengthened). Here, in the pure
     * core, because it is also the part of a session's ELAPSED that no plan can hold: the
     * after-pull's vent runs inside the run and ends when the pump reports the cuff open,
     * which PlannedTime#clockSec cannot know in advance. One number, two readers.
     */
    public static final long ASSESS_VENT_WINDOW_MS = 20000L;

    /**
     * The assessment seconds that fall INSIDE the tracked run and are PLANNABLE, which
     * is the after-pull's own sampling window and nothing else.
     *
     * The before-pull runs between the seal check and Session#beginRun(), exactly as the
     * seal check itself does, so it is in neither the run's elapsed clock nor its dose.
     * The after-pull runs before the run is filed, so it is in both.
     *
     * WHAT THIS DELIBERATELY EXCLUDES (fix round 2, and the invariant restated with it):
     * the VENT that precedes the after-pull also runs inside beginRun/endRun, so ELAPSED
     * contains it, but its length is decided by telemetry — it ends when the pressure is
     * observed to settle — and a plan cannot state a duration that the pump has not yet
     * chosen.
     *
     * WHAT THAT DOES AND DOES NOT IMPLY, corrected in round 3 because round 2's sentence
     * over-generalised in its turn. On a session that plays to the end, ELAPSED exceeds
     * PLANNED by the vent, up to ASSESS_VENT_WINDOW_MS. On an ABORTED one it can sit
     * anywhere below PLANNED, because the routine stopped early. (B3: the summary now
     * states the pair on EVERY session counted by the clock, finished ones included - so
     * PlannedTime reads an over-run no larger than this vent as "as planned" rather than
     * claiming the run overran; see PlannedTime#CLOCK_SLACK_SEC.) Round 1's comment
     * claimed the two measured the same stretch of time; that was false. Round 2 replaced
     * it with a sentence that was false on exactly the sessions that show the pair. What
     * this function does guarantee, and what defect #11 was actually about, is that PLANNED
     * and ELAPSED are derived from ONE decision about what is inside the run rather than
     * from two that could disagree about the presets.
     */
    public static int trackedAssessSec(Model.Routine r) {
        return runsAfter(r) ? r.assess.dur : 0;
    }

    /**
     * Whether two assessments were run under the SAME stimulus, and are therefore
     * comparable. tau scales with pump rate, so a changed speed — or pressure, or
     * duration — makes two tau values incomparable however similar they look. This is
     * the one decision; the routine editor warns from it at the point of change, and
     * History marks rows from it, so the two can never disagree.
     */
    public static boolean sameStimulus(int kpaA, int spA, int durA,
                                        int kpaB, int spB, int durB) {
        return kpaA == kpaB && spA == spB && durA == durB;
    }

    /** The stimulus in words — "20.0 kPa / 60% for 45 s" — for a caption that has to
     *  say what a tau was measured under. Pressure goes through Model.Fmt.p so it
     *  honours the display unit; the percentage and the seconds do not. */
    public static String stimulus(int kpa, int sp, int durSec) {
        return Model.Fmt.p(kpa) + " / " + sp + "% for " + durSec + " s";
    }
}
