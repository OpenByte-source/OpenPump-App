package org.openpump;

import java.util.Locale;

/**
 * The hardware validation routine — the whole of it except the BLE writes and the screen.
 *
 * WHAT THIS IS. Every task in this plan closed its report with the same sentence:
 * <em>verified by reading, not by machine</em>. test.sh compiles only sources with no
 * `import android` line, so the wiring layer is untested — and every safety defect found
 * in this project lived exactly there. Separately, several of the app's CONSTANTS are
 * figures about pump physics that have never been measured on this rig: SessionActivity's
 * VENT_RATE (4.68 kPa/s — measured once, from the build-17 hardware log, and not since;
 * release checklist H10's pass confirms that STOP vents, not the rate), Tau's
 * TELEMETRY_INTERVAL_MS (240 ms, i.e. ~4.16 Hz), the ultimate vacuum of ~90 kPa that Tau's
 * class doc derives PAIR_MATCH_TOL_KPA from (an assumption), and a seal-check decay figure
 * the app prints and explicitly refuses to judge (the coast rate is unmeasured: H14).
 *
 * This class runs a bounded sequence against a RIGID SEALED TEST VESSEL and measures them.
 *
 * IT IS A MEASUREMENT INSTRUMENT, NOT A TEST SUITE. Nothing here returns "pass" or "fail"
 * about the pump. {@link #diverges} flags a measured value more than
 * {@link #DIVERGENCE_FLAG_FRACTION} away from what the app assumes, and that is the
 * strongest claim anything in this file makes — a flag is an invitation to look, not a
 * verdict. The one measurement where a verdict WOULD be useful (the coast rate) has no
 * grounded reference in this repo at all, which is precisely why it is measured here.
 *
 * WHY IT IS ALL IN HERE. The sequence, the abort conditions, the vent gate between steps,
 * every derived number and the summary comparison are decidable from arrays of samples,
 * so they are decided here where test.sh can execute them. SessionActivity keeps only the
 * writes, the timers and the views. That is the lesson of this project, applied to the one
 * feature whose whole purpose is to be trustworthy about hardware.
 *
 * TWO RULES BIND EVERY FUNCTION BELOW.
 *
 *   0.0 kPa MEANS NO MEASUREMENT, NOT ATMOSPHERIC (Proto.Sample#noReading). No slope is
 *   ever taken across one, no gap is ever bridged over one, and no 0.0 is ever an
 *   endpoint of anything. In step 1 it is reported VERBATIM ({@link #verbatim}) rather
 *   than converted to a pressure, because whether a vented pump reports 0.0 or a real low
 *   value IS the question that step exists to answer.
 *
 *   A WRITE IS NOT A VENT (Session#ventResult). {@link #ventGate} never treats a stop as
 *   landed because it was sent: it consumes the shared VentWatcher's telemetry verdict
 *   and, for every step but the first, additionally requires a SETTLED reading at or
 *   below Tau.ARM_BELOW_KPA before the next step may arm.
 */
public final class Validate {

    private Validate() { }

    /* ==================================================== what the app assumes ==== */

    /**
     * The ultimate vacuum Tau's class doc uses as A in its sensitivity derivation, which
     * is what sets Tau.PAIR_MATCH_TOL_KPA. It lives here rather than in Tau because in
     * Tau it is prose inside a derivation, not a constant anything can read — and this
     * routine's job is to put a measured number beside it. Step 4 measures it.
     *
     * The OTHER assumed values are deliberately NOT copied: Tau.TELEMETRY_INTERVAL_MS and
     * Tau.ARM_BELOW_KPA are already constants and are read directly, and the vent rate is
     * a PARAMETER of {@link #ventRateLine} rather than a second copy of
     * SessionActivity.VENT_RATE. Two literals of one physical constant is how they drift
     * apart, and this file exists to detect drift, not to add some.
     */
    public static final double ASSUMED_ULTIMATE_KPA = 90.0;

    /** How far a measured value may sit from the assumed one before the summary flags it
     *  for a human to look at. Not a pass/fail bound: a flag says "these two numbers
     *  disagree by more than a fifth", which is a fact, and leaves what it means to a
     *  reader who knows what rig it was measured on. */
    public static final double DIVERGENCE_FLAG_FRACTION = 0.20;

    /* ============================================================== the phases ==== */

    /** One measurement phase. The addendum's eight STEPS map to eleven phases: step 2
     *  measures cadence idle and under load, and step 6 runs the same pull at three
     *  speeds, so each of those is several phases carrying the same step number. */
    public static final class Phase {
        /** The addendum's step number, 1..8 — what the log and the summary group by. */
        public final int step;
        /** Stable identifier for the log, unique across the sequence. */
        public final String key;
        /** What the screen says is happening. */
        public final String label;
        /** The pressure to COMMAND, already ceiling-clamped; 0 means this phase commands
         *  no pressure at all (steps 1, 2-idle and 8 only observe, or write the table). */
        public final int commandKpa;
        /** The lower setpoint that goes on the wire beside it — equal to commandKpa for a
         *  hold, strictly below it for a band. Always clamped ({@link #clamp}). */
        public final int lowerKpa;
        public final int speedPct;
        /** True when this phase writes the nine-preset table and reads it back (step 8).
         *  It starts nothing: writing presets does not run one. */
        public final boolean writesPresetTable;
        /** This phase's own wall-clock bound. Exceeding it aborts the run
         *  ({@link #ABORT_PHASE_OVERRUN}) rather than letting a phase hang. */
        public final long boundMs;
        /** Step 3 only: the vent that FOLLOWS this phase is the measurement. The decay
         *  curve after StopWork is what step 3 IS, so the routine's ordinary
         *  vent-between-steps is step 3's instrument rather than a second vent bolted on
         *  beside it. */
        public final boolean decayOnExit;
        /** True when reaching the commanded pressure ends the phase early — there is
         *  nothing more to learn from holding it once the top is reached, and a shorter
         *  unattended run at pressure is better than a longer one. */
        public final boolean advanceOnReach;
        /**
         * True when the device should CYCLE between the two setpoints for the whole phase
         * rather than reach the upper one and sit there — step 2's under-load half, and
         * nothing else.
         *
         * It matters because of what the firmware does after it reaches target: it coasts,
         * motor off (Proto's class doc; how fast is unmeasured, release checklist H14). A
         * phase that pulls to 30 kPa and then holds for 90 s spends about two seconds under
         * load and fifty-eight coasting, so "cadence under load" measured across it would be a
         * second measurement of cadence at IDLE wearing the other label — and the two
         * agreeing would then be reported as evidence that load does not affect the
         * telemetry rate. A one-second hold at each end keeps the motor working for the
         * whole window, which is the condition the step is named for.
         */
        public final boolean cycles;

        Phase(int step, String key, String label, int commandKpa, int lowerKpa, int speedPct,
              boolean writesPresetTable, long boundMs, boolean decayOnExit,
              boolean advanceOnReach, boolean cycles) {
            this.step = step; this.key = key; this.label = label;
            this.commandKpa = commandKpa; this.lowerKpa = lowerKpa; this.speedPct = speedPct;
            this.writesPresetTable = writesPresetTable; this.boundMs = boundMs;
            this.decayOnExit = decayOnExit; this.advanceOnReach = advanceOnReach;
            this.cycles = cycles;
        }

        /** Does this phase put the pump under pressure? Note what this does NOT decide:
         *  whether the ROUTINE counts as unsafe. It does, for its whole length, because a
         *  phase that only observes still follows one that pulled. */
        public boolean commandsPressure() { return commandKpa > 0; }

        /**
         * The upper-hold seconds written into the preset. The device must hold for longer
         * than the app intends to watch, so the app never races the firmware's own hold
         * expiring mid-measurement — the same guard the seal check (Model#sealCheckHoldS) and
         * the assessment (ASSESS_HOLD_GUARD_S) already use.
         *
         * A CYCLING phase is the deliberate exception: one second at each end is what makes
         * it cycle at all, and it has nothing to race — the device is meant to reach the
         * end of that hold, over and over, for the whole window.
         */
        public int holdSec() {
            if (cycles) return 1;
            long s = boundMs / 1000L + HOLD_GUARD_S;
            return (int) Math.min(255L, s);
        }
    }

    /** The device's hold-time byte is one byte, and the phase must not outlive its hold. */
    public static final int HOLD_GUARD_S = 30;

    /** Step 6 runs the same pull at these three speeds — the ends of the app's usable
     *  range and its middle — so Proto#speedCode's mapping is exercised end to end rather
     *  than at one point. */
    public static final int[] STEP6_SPEEDS = { 30, 60, 100 };

    /** Step 6's pull is the tissue-adaptation assessment's own default (Model.Assess:
     *  20 kPa, 45 s), so what it validates is the shipped configuration rather than a
     *  shape invented for this routine. */
    public static final int STEP6_KPA = 20;
    public static final long STEP6_MS = 45000L;

    /**
     * The sequence, built against the ceiling in force. Every commanded pressure here has
     * already been through {@link #clamp}, so a table built against a ceiling of 7 kPa
     * commands 7 and not the 30 or 20 the step nominally asks for — the clamp is part of
     * the sequence, not something applied to it afterwards.
     */
    public static Phase[] phases(int ceilKpa) {
        Setpoints load  = clamp(Math.min(30, ceilKpa), false, ceilKpa);  // a band
        Setpoints deep  = clamp(ceilKpa, false, ceilKpa);                // step 4: the ceiling
        Setpoints coast = clamp(Math.min(20, ceilKpa), true, ceilKpa);   // a hold: lower == upper
        Setpoints six   = clamp(Math.min(STEP6_KPA, ceilKpa), true, ceilKpa);
        Phase[] ps = new Phase[11];
        int i = 0;
        ps[i++] = new Phase(1, "1", "Vented baseline — what a vented pump reports",
                            0, 0, 0, false, 20000L, false, false, false);
        ps[i++] = new Phase(2, "2-idle", "Telemetry cadence, idle",
                            0, 0, 0, false, 60000L, false, false, false);
        ps[i++] = new Phase(2, "2-load", "Telemetry cadence, under load",
                            load.upper, load.lower, 75, false, 60000L, false, false, true);
        ps[i++] = new Phase(3, "3", "Vent fall rate — pull, then StopWork",
                            load.upper, load.lower, 75, false, 30000L, true, true, false);
        ps[i++] = new Phase(4, "4", "Ultimate vacuum at the ceiling",
                            deep.upper, deep.lower, 100, false, 60000L, false, false, false);
        ps[i++] = new Phase(5, "5", "Coast rate under a sealed load",
                            coast.upper, coast.lower, 60, false, 60000L, false, false, false);
        for (int k = 0; k < STEP6_SPEEDS.length; k++)
            ps[i++] = new Phase(6, "6@" + STEP6_SPEEDS[k],
                                "Rise curve at " + STEP6_SPEEDS[k] + "%",
                                six.upper, six.lower, STEP6_SPEEDS[k], false,
                                STEP6_MS + 5000L, false, false, false);
        ps[i++] = new Phase(7, "7", "The re-asserted start after a stop",
                            coast.upper, coast.lower, 60, false,
                            RESTART_ARM_MS + RESTART_STOP_GAP_MS + RESTART_OBSERVE_MS + 5000L,
                            false, false, false);
        ps[i++] = new Phase(8, "8", "Preset table round-trip",
                            0, 0, 0, true, 15000L, false, false, false);
        return ps;
    }

    /**
     * How far past its own bound a phase may run before the routine treats it as a fault
     * rather than as a phase ending normally.
     *
     * The two are different questions and this app has been bitten by conflating them
     * before. A phase ENDS at {@link #phaseEndsAt} — that is its length, and reaching it
     * is the ordinary outcome. It ABORTS at {@link #phaseAbortAt}, which only fires when
     * the driver failed to end it: a tick that never arrived, a branch that fell through.
     * The slack is deliberately many telemetry intervals wide so an ordinarily late tick
     * on a busy main thread is not read as a fault.
     */
    public static final long PHASE_OVERRUN_SLACK_MS = 5000L;

    /** When this phase is DONE — the driver's own deadline. */
    public static long phaseEndsAt(long startedAt, Phase p) {
        return startedAt + p.boundMs;
    }

    /** When this phase outran the driver — the abort backstop, always later than
     *  {@link #phaseEndsAt}. */
    public static long phaseAbortAt(long startedAt, Phase p) {
        return startedAt + p.boundMs + PHASE_OVERRUN_SLACK_MS;
    }

    /**
     * What each STEP does, for the screen the user reads before walking away.
     *
     * Separate from Phase#label because a phase label describes one phase and several
     * steps are more than one: listing the phases would tell the user step 6 is "Rise curve
     * at 30%" when it is three curves, and step 2 that it is the idle half alone. The
     * screen is the last thing read before an unattended run at the ceiling; it should
     * describe the run that is about to happen.
     */
    public static String stepSummary(int step) {
        switch (step) {
            case 1: return "What a fully vented pump reports — 30 frames, verbatim";
            case 2: return "Telemetry cadence, 60 s idle and 60 s under load";
            case 3: return "The StopWork vent fall rate, at frame resolution";
            case 4: return "The plateau reached at the ceiling, and the time to reach it";
            case 5: return "The coast rate under a sealed load, per 10 s window";
            case 6: return "Rise curves at " + STEP6_SPEEDS[0] + "%, " + STEP6_SPEEDS[1]
                         + "% and " + STEP6_SPEEDS[2] + "%, each through Tau";
            case 7: return "Whether a re-asserted start survives a stop";
            case 8: return "Whether nine written presets come back unchanged";
            default: return "";
        }
    }

    /** How long a vent gate may take before the run is abandoned. Derived rather than
     *  picked, exactly as SessionActivity's ASSESS_VENT_WINDOW_MS is: at the measured vent
     *  rate a fall from the protocol's own 57 kPa maximum to Tau.ARM_BELOW_KPA takes
     *  (57 − 2) / 4.68 = 11.8 s, and this routine's deepest phase pulls to the ceiling,
     *  which cannot exceed 57. 20 s leaves eight seconds of margin over the worst case. */
    public static final long GATE_BOUND_MS = 20000L;

    /**
     * The whole run's wall-clock bound, reported BEFORE the user starts so they know how
     * long to leave it: every phase, plus the vent gates that are ACTUALLY run between
     * them ({@link #gateNeededAfter}) and one closing vent on the way out.
     *
     * It counts the gates the driver runs, not one per phase. Counting a gate the routine
     * skips would over-state the wait by a minute and — worse — would be a second,
     * disagreeing statement of when a gate happens, in the one file whose job is to make
     * that decision once.
     */
    public static long totalBoundMs(int ceilKpa) {
        Phase[] ps = phases(ceilKpa);
        long total = GATE_BOUND_MS;                       // the closing vent
        for (int i = 0; i < ps.length; i++) {
            total += ps[i].boundMs;
            if (i + 1 < ps.length && gateNeededAfter(ps[i])) total += GATE_BOUND_MS;
        }
        return total;
    }

    /* ============================================================== the clamps ==== */

    /** An upper/lower setpoint pair as it will go on the wire. */
    public static final class Setpoints {
        public final int upper, lower;
        Setpoints(int u, int l) { upper = u; lower = l; }
    }

    /**
     * THE CEILING, APPLIED AT WRITE TIME, TO BOTH SETPOINTS — the same rule
     * SessionActivity#uploadBatch applies to every routine preset, and here for the same
     * reason: a stored pressure can become over-ceiling because the CEILING moved, not
     * because anyone typed it.
     *
     * `hold` picks between the two shapes this app already writes, and both are real:
     *   - a BAND (hold false), lower strictly below upper, which is what a routine preset
     *     is — the device cycles between the two setpoints;
     *   - a HOLD (hold true), lower equal to upper, which is what the seal check and the
     *     tissue-adaptation assessment write so the device simply pulls and stays there.
     *     Step 5 needs exactly that shape (the addendum: "with upper == lower"), so
     *     forcing lower &lt; upper unconditionally would command a different experiment
     *     from the one asked for.
     *
     * Whichever shape, NEITHER setpoint may exceed the ceiling, and the device's own
     * 57 kPa limit binds above that. Proto#addPreset clamps there too; this is the
     * app-side half of the same rule, so an over-ceiling value never reaches the wire.
     */
    public static Setpoints clamp(int wantUpper, boolean hold, int ceilKpa) {
        int cap = Math.min(57, Math.max(0, ceilKpa));
        int upper = Math.max(0, Math.min(wantUpper, cap));
        int lower = hold ? upper : (upper > 0 ? upper - 1 : 0);
        return new Setpoints(upper, lower);
    }

    /**
     * The band form used by the preset table (step 8), where the lower setpoint is a value
     * in its own right rather than "one below the upper". Same ceiling rule on both
     * setpoints, and lower is re-asserted strictly below upper AFTERWARDS — the order
     * matters, because clamping the upper DOWN can leave a perfectly ordinary lower above
     * it.
     */
    public static Setpoints clampBand(int wantUpper, int wantLower, int ceilKpa) {
        Setpoints u = clamp(wantUpper, false, ceilKpa);
        int lower = Math.max(0, Math.min(wantLower, u.upper > 0 ? u.upper - 1 : 0));
        return new Setpoints(u.upper, lower);
    }

    /* ========================================================== the vent gate ==== */

    /** Keep waiting: the vent has produced neither the evidence nor the settled reading
     *  the next phase needs, and its bound has not run out. */
    public static final String GATE_WAIT = "wait";
    /** Arm the next phase. */
    public static final String GATE_PROCEED = "proceed";
    /** Abandon the whole run. The caller names the phase it stopped at. */
    public static final String GATE_ABORT = "abort";

    /**
     * Whether the next phase may arm, given what the vent has shown so far.
     *
     * A WRITE IS NOT A VENT, AND NEITHER IS A FALL. The shared VentWatcher reports VENTED
     * as soon as pressure is FALLING at roughly the expected rate — a statement about
     * motion, not arrival (Session#ventResult, and SessionActivity's own note on
     * AssessVentTick). So an ordinary phase needs both: the watch must have evidenced the
     * fall, AND telemetry must have SETTLED at or below Tau.ARM_BELOW_KPA, which is this
     * app's existing definition of a known start.
     *
     * STEP 1 IS THE EXCEPTION, and it is not a loosening — it IS the measurement.
     * "Settled at or below ARM_BELOW_KPA" is decidable only from REAL readings, because
     * Session#freshBaselineKpa returns NaN for a 0.0 and a no-measurement frame is never
     * evidence of anything. If this pump reports 0.0 when vented — the exact hypothesis
     * step 1 exists to test, and the one that makes every tissue-adaptation assessment
     * refuse forever — then NO gate anywhere in the run could ever be satisfied, and a
     * routine that demanded one before step 1 would abort before recording the single
     * highest-value number it was built to obtain. So step 1's gate accepts the bound
     * expiring as a legitimate way through, and step 1 then reports VERBATIM what it saw.
     * Every later phase measures a rise or a decay FROM a known start, so for those an
     * unsettled vent really is a reason to stop.
     *
     * `watchResolved`/`vented` come from SessionActivity's VentWatcher (resolved() and
     * vented()); `settledKpa` is the settled reading or NaN when nothing has settled yet
     * ({@link #settledKpa}); `boundPassed` is this gate's own wall-clock bound.
     */
    public static String ventGate(int step, boolean watchResolved, boolean vented,
                                   double settledKpa, boolean boundPassed) {
        // STEP 1'S BOUND ESCAPE COMES FIRST, and the ordering is the whole fix.
        //
        // The doc above already says step 1 accepts its bound expiring as a legitimate way
        // through, precisely because a vented pump on this hardware reports 0.0 (no
        // measurement) and can satisfy no evidence test at all. But the abort below was
        // checked FIRST, so on exactly that pump the watch resolved UNCONFIRMED before the
        // bound ever passed and step 1 aborted — the one measurement the routine exists to
        // obtain, refused by the very condition it was built to observe. Step 1 commands
        // nothing after this gate; it only records what it sees. So its escape is safe to
        // take before the abort, and it must be, or it is unreachable.
        if (step == 1 && boundPassed) return GATE_PROCEED;
        // The watch gave a definite answer and it was NOT a vent (VENTED_INFERRED counts as
        // vented here — see Session#ventedForGating; the caller passes it through): positive
        // evidence that the pump did not respond to a stop. That must stop a routine which
        // is about to command more pressure, so it is checked before any waiting.
        if (watchResolved && !vented) return GATE_ABORT;
        if (!Double.isNaN(settledKpa) && settledKpa <= Tau.ARM_BELOW_KPA) return GATE_PROCEED;
        if (boundPassed) return (step == 1) ? GATE_PROCEED : GATE_ABORT;
        return GATE_WAIT;
    }

    /**
     * IS THERE ANYTHING FOR A VENT GATE TO PROVE between these two phases?
     *
     * This is df76208's finding, applied to the routine that still had it. That commit
     * fixed the SELF-TEST's V1, which opened with a vent gate on a pump nothing had yet
     * pressurised; the deep validation run opens exactly the same way (beginValidation →
     * the gate before step 1) and then does it again between step 1 and 2-idle, and again
     * between 2-idle and 2-load — three gates in a row whose preceding phase commanded no
     * pressure at all.
     *
     * On this pump those gates cannot be satisfied and can only end the run. A vented pump
     * here reports 0.0 — NO MEASUREMENT — so {@link #settledKpa} over the gate's buffer is
     * NaN however long it waits, and {@link #ventGate} has only two exits left: the watch
     * resolving UNCONFIRMED, or the 20 s bound expiring against a step that is not step 1.
     * Both are GATE_ABORT. So the 11:58 run reached the gate before 2-idle at about fifty
     * seconds in and was abandoned there with "the vent before the next step was never
     * confirmed", every time — which is also why step 2's cadence numbers, the two the
     * routine exists to measure, were never once reported: the phases that measure them
     * are on the far side of that gate.
     *
     * The rule is the one df76208 wrote for V1, stated generally: a gate asks "is the
     * pressure this section is about to command gone", and when nothing commanded any
     * there is no such pressure and no question. `prev` null means "nothing ran before
     * this" — the opening gate — which is the same answer for the same reason.
     *
     * decayOnExit OUTRANKS ALL OF THIS. Step 3's gate IS step 3's measurement (the fall
     * curve after StopWork), and step 3 commands pressure, so it is never skipped. And the
     * CLOSING gate is not this function's business at all: the caller runs it
     * unconditionally, because a run that commanded anything must end vented.
     */
    public static boolean gateNeededAfter(Phase prev) {
        if (prev == null) return false;
        if (prev.decayOnExit) return true;
        return prev.commandsPressure();
    }

    /* ============================================================== the aborts ==== */

    public static final String ABORT_NONE = "";
    /** No telemetry for the link timeout — the same bound, and the same pure decisions
     *  (Session#linkLost over Session#silenceReference), that the run screen, the seal
     *  check and the assessment all use. */
    public static final String ABORT_SILENCE = "no-telemetry";
    /** A reading above the ceiling by more than the pump's own overshoot. */
    public static final String ABORT_OVER_CEILING = "over-ceiling";
    /** A phase outran its own bound. */
    public static final String ABORT_PHASE_OVERRUN = "phase-overrun";
    /** A vent between phases was never evidenced, or never settled — {@link #ventGate}. */
    public static final String ABORT_VENT_UNCONFIRMED = "vent-unconfirmed";
    /** The user stopped it, or left. Every exit from a live routine is an abort. */
    public static final String ABORT_USER = "stopped";

    /**
     * How far above the ceiling a reading may sit before it is read as the clamp having
     * failed rather than as the pump overshooting.
     *
     * NOT ZERO, deliberately, and this is the one place this routine is weaker than the
     * addendum's literal wording ("any reading above the ceiling"). Step 4 commands the
     * ceiling ITSELF, and a healthy pull overshoots: an early bench log reached 21.0 kPa
     * against a 20 kPa target, the same 1.0 kPa
     * Session.SEAL_REACH_TOLERANCE_KPA already encodes as normal. A zero-margin rule would
     * therefore abort every run at step 4 on healthy hardware, and an instrument that
     * cannot complete its own deepest measurement measures nothing. This is twice that
     * observed overshoot: comfortably outside ordinary regulation, comfortably inside "the
     * clamp is not working".
     */
    public static final double CEILING_OVERSHOOT_MARGIN_KPA = 2.0;

    /** Is this reading evidence that pressure went above the ceiling? A 0.0 is NO
     *  MEASUREMENT and is never evidence, in either direction. */
    public static boolean overCeiling(double kpa, boolean noReading, int ceilKpa) {
        if (noReading) return false;
        return kpa > ceilKpa + CEILING_OVERSHOOT_MARGIN_KPA;
    }

    /**
     * The one place the routine decides to give up, asked on every tick.
     *
     * ORDER IS PART OF THE ANSWER. Over-ceiling first, because it is the only condition
     * that says something has gone physically wrong RIGHT NOW. Silence next, because a
     * link that has stopped speaking cannot be watched and everything after that point
     * would be inferred rather than observed. The phase bound last, because outrunning a
     * bound is a fault in the measurement, not in the pump.
     *
     * SILENCE IS ANY FRAME, AND IT IS NOT PER-PHASE. `lastFrameAt` must be the instant the
     * last frame OF ANY KIND arrived — a 0.0 NO MEASUREMENT frame is silence-breaking
     * evidence that the link is alive, which is the only thing this term is asking about,
     * and it must survive a phase boundary because the link does. The caller previously
     * passed a timestamp that the run and seal-check paths ZERO (SessionActivity's
     * lastSampleAt is cleared at beginRun and again at startSession, so a fresh reading is
     * never shown against a stale command). Zeroing it collapses Session#silenceReference
     * onto `phaseStartedAt`, and any driver tick that then landed more than the timeout
     * after a phase or gate began read a perfectly healthy 4 Hz link as five seconds of
     * silence and aborted the routine with "telemetry stopped arriving". The self-test's
     * vessel section runs both of those zeroing paths with the hv driver's flags still set,
     * which is exactly where those aborts came from.
     *
     * `phaseStartedAt` remains the fallback for the case it was introduced for and only
     * that one — round 1's CRITICAL: a routine that has never received a single frame at
     * all must still time out rather than wait forever for a reference instant.
     */
    public static String abortReason(long now, long lastFrameAt, long phaseStartedAt,
                                      long linkTimeoutMs, double lastKpa, boolean lastNoReading,
                                      int ceilKpa, long phaseBoundAt) {
        if (lastFrameAt > 0 && overCeiling(lastKpa, lastNoReading, ceilKpa))
            return ABORT_OVER_CEILING;
        long since = Session.silenceReference(phaseStartedAt, lastFrameAt);
        if (Session.linkLost(now, since, linkTimeoutMs)) return ABORT_SILENCE;
        if (phaseBoundAt > 0 && now >= phaseBoundAt) return ABORT_PHASE_OVERRUN;
        return ABORT_NONE;
    }

    /** Plain wording for the log and the screen. */
    public static String abortText(String reason) {
        if (ABORT_SILENCE.equals(reason))
            return "telemetry stopped arriving — the pump could not be watched";
        if (ABORT_OVER_CEILING.equals(reason))
            return "a reading came back above the safety ceiling";
        if (ABORT_PHASE_OVERRUN.equals(reason))
            return "the step outran its own time bound";
        if (ABORT_VENT_UNCONFIRMED.equals(reason))
            return "the vent before the next step was never confirmed by telemetry";
        if (ABORT_USER.equals(reason))
            return "stopped";
        return reason;
    }

    /* ========================================== the settle rule, shared by three ==== */

    /**
     * The index of the second frame of the first pair of consecutive REAL readings that
     * agree to within Session.VENT_FALL_NOISE_FLOOR_KPA, or -1 when no such pair exists.
     *
     * The pairing rule is the arming decision's (see {@link #settledIndexAtOrBelow}), including
     * the part that matters most: 0.0 frames are SKIPPED rather than breaking the pair,
     * because 0.0 is NO MEASUREMENT. Two real frames either side of a dropout are still two
     * consecutive real frames as far as the arming decision is concerned.
     *
     * NO LONGER A MODEL OF THE LIVE TICK. AssessVentTick used to pair frames itself, one
     * poll at a time, and this replayed it with two stated divergences (freshness, and two
     * frames inside one poll). It now asks Tau#armStartKpa over the vent window's own
     * frames, so there is one decision and nothing to diverge from.
     */
    public static int settledIndex(double[] kpa, boolean[] noReading) {
        int n = kpa == null ? 0 : kpa.length;
        int prev = -1;
        for (int i = 0; i < n; i++) {
            if (nr(noReading, i)) continue;
            if (prev >= 0 && Math.abs(kpa[prev] - kpa[i]) <= Session.VENT_FALL_NOISE_FLOOR_KPA)
                return i;
            prev = i;
        }
        return -1;
    }

    /**
     * {@link #settledIndex}, but the first agreeing pair whose second reading is AT OR BELOW
     * `maxKpa` - scanning on past any pair above it. The arming question: a late StopWork
     * leaves the vent window opening on two agreeing frames at the HELD pressure (29.0,
     * 28.9), and settledIndex stops there for good, while the pull's real start is the pair
     * at the bottom (1.4, 1.3). Pairs are consecutive REAL frames with 0.0 skipped, exactly
     * as in settledIndex. -1 when no such pair exists.
     */
    public static int settledIndexAtOrBelow(double[] kpa, boolean[] noReading, double maxKpa) {
        int n = kpa == null ? 0 : kpa.length;
        int prev = -1;
        for (int i = 0; i < n; i++) {
            if (nr(noReading, i)) continue;
            if (prev >= 0 && kpa[i] <= maxKpa
                    && Math.abs(kpa[prev] - kpa[i]) <= Session.VENT_FALL_NOISE_FLOOR_KPA)
                return i;
            prev = i;
        }
        return -1;
    }

    /** The settled reading itself, or NaN when nothing has settled — what
     *  {@link #ventGate} compares against Tau.ARM_BELOW_KPA. */
    public static double settledKpa(double[] kpa, boolean[] noReading) {
        int at = settledIndex(kpa, noReading);
        return at < 0 ? Double.NaN : kpa[at];
    }

    /** How much of the tail the seal check's plateau test looks at. ~1.5 s is six frames at
     *  this pump's ~4.16 Hz — enough for the two-real-frame rule with room for dropouts,
     *  short enough that a pair of flat frames from minutes ago cannot speak for NOW. */
    public static final long SETTLE_TRAIL_MS = 1500L;

    /**
     * {@link #settledKpa} over only the TRAILING `trailMs` of frames.
     *
     * Fix round 4's HIGH: the seal check latched its plateau from settledKpa over the WHOLE
     * buffer, and settledIndex returns the FIRST agreeing pair it ever finds. Two flat
     * frames during motor spin-up, residual pressure still on the cuff at the start, or a
     * mid-climb stall therefore declared "plateau" at 1.3 or 9.2 kPa against 20 kPa
     * commanded — and the coast window that opened there measured a CLIMB and reported it
     * as decay, i.e. a leaking cuff reported as sealed or the reverse. Asking only what the
     * last ~1.5 s did makes the answer a statement about NOW, which is what a plateau is.
     *
     * `tsMs` are the arrival times of the same frames (same length as `kpa`); a missing or
     * mismatched clock answers NaN rather than silently falling back to the whole buffer.
     */
    public static double settledKpaTrailing(double[] kpa, boolean[] noReading, long[] tsMs,
                                             long trailMs) {
        int n = kpa == null ? 0 : kpa.length;
        if (n == 0 || tsMs == null || tsMs.length < n) return Double.NaN;
        long cutoff = tsMs[n - 1] - Math.max(0L, trailMs);
        int from = n;
        while (from > 0 && tsMs[from - 1] >= cutoff) from--;
        int len = n - from;
        double[] k = new double[len];
        boolean[] nr = new boolean[len];
        for (int i = 0; i < len; i++) {
            k[i] = kpa[from + i];
            nr[i] = nr(noReading, from + i);
        }
        return settledKpa(k, nr);
    }

    /**
     * THE SEAL CHECK'S PLATEAU, whole: the pressure it settled at, or NaN while there is
     * no plateau to start a coast window from. Both halves of fix round 4's HIGH live
     * here rather than in the Activity, so the rule is testable and cannot drift:
     * FLAT NOW ({@link #settledKpaTrailing}, not "flat at some point in the whole buffer")
     * AND AT THE PRESSURE ASKED FOR ({@link Session#reachedCommanded}, which alone
     * separates a genuine plateau from a mid-climb stall — a stall is flat in every
     * window there is).
     */
    public static double sealPlateauKpa(double[] kpa, boolean[] noReading, long[] tsMs,
                                         long trailMs, int commandedKpa) {
        double settled = settledKpaTrailing(kpa, noReading, tsMs, trailMs);
        if (Double.isNaN(settled)) return Double.NaN;
        return Session.reachedCommanded(settled, commandedKpa) ? settled : Double.NaN;
    }

    /* ============================================ seal check · window state ==== */

    /** Keep sampling — the hold has not plateaued yet, or the coast window is still open. */
    public static final String SEAL_WAIT = "wait";
    /** The coast window measured from the plateau is complete: report the decay. */
    public static final String SEAL_MEASURE = "measure";
    /** The hard ceiling expired with the pressure still rising (no plateau ever seen). The
     *  verdict is INCONCLUSIVE — the check never got a coast to measure. It is emphatically
     *  NOT "did not reach target": nothing here observed a target being missed, only a
     *  window closing early, and reporting the two as the same thing accuses a healthy cuff
     *  of leaking because the pump took longer than the app's patience. */
    public static final String SEAL_INCONCLUSIVE_RISING = "still-rising";

    /**
     * The seal check's window state — pure, so the fixed 10 s guess it replaces cannot come
     * back by accident.
     *
     * The old design started a 10 s clock the instant the hold was COMMANDED, and reported
     * whatever it had when that clock ran out. But the pump has to reach pressure first,
     * and the coast (motor off) is the only part worth measuring — so on a slower cuff the
     * window closed during the RISE and the check reported a verdict about a plateau that
     * had not happened. The window now starts at the plateau ({@link #settledKpa} over the
     * seal buffer: two real frames within the noise floor of each other), with a hard
     * ceiling so a cuff that never plateaus still ends — as inconclusive, which is what it
     * is.
     */
    /**
     * How long from NOW the seal check's BACKSTOP should be armed for.
     *
     * Fix round 4's MEDIUM: the backstop was armed once, for the ceiling, and never moved.
     * A plateau seen at t=20 s legitimately opens a `coastMs` window — and the ceiling then
     * cut it off at 5 s, producing a MEASURED verdict (not an inconclusive one) from half a
     * coast. The ceiling is the answer to "this never plateaued"; once a plateau opens a
     * coast, the backstop belongs at the end of THAT coast, measured from the plateau,
     * plus a margin so it can only ever fire after the tick has had its chance.
     */
    public static long sealBackstopDelayMs(boolean plateauSeen, long coastMs, long marginMs,
                                            long ceilingMs) {
        return plateauSeen ? coastMs + marginMs : ceilingMs;
    }

    public static String sealPhase(boolean plateauSeen, long sincePlateauMs, long coastMs,
                                    long sincePhaseStartMs, long ceilingMs) {
        if (plateauSeen) return sincePlateauMs >= coastMs ? SEAL_MEASURE : SEAL_WAIT;
        return sincePhaseStartMs >= ceilingMs ? SEAL_INCONCLUSIVE_RISING : SEAL_WAIT;
    }

    /* ================================================================== step 1 ==== */

    /** How many consecutive frames step 1 logs. */
    public static final int BASELINE_FRAMES = 30;

    /**
     * One frame, VERBATIM — the addendum's own word, and the reason this exists instead of
     * Model.Fmt.p().
     *
     * Fmt.p() applies the display unit and prints vacuum as a NEGATIVE inHg value, both of
     * which are conversions. Step 1's whole question is whether the device says 0.0 or a
     * real low value when vented, and 0.0 means NO MEASUREMENT (Proto.Sample). Converting
     * it produces "−0.0 inHg", which reads as a pressure — exactly the fabrication the 0.0
     * rule exists to prevent. So this prints what came off the wire, in the unit the wire
     * uses, and says plainly when there was no reading at all.
     */
    public static String verbatim(double kpa, boolean noReading) {
        String v = String.format(Locale.US, "%.1f", kpa);
        return noReading ? v + "  (NO MEASUREMENT)" : v + " kPa";
    }

    /** Every frame in this window carried a reading. */
    public static final String BASE_ALL_REAL = "all-real";
    /** Some frames carried 0.0. */
    public static final String BASE_SOME_NO_MEASUREMENT = "some-no-measurement";
    /** EVERY frame carried 0.0 — a vented pump that reports nothing. */
    public static final String BASE_ALL_NO_MEASUREMENT = "all-no-measurement";
    /** No frames arrived at all. */
    public static final String BASE_NO_FRAMES = "no-frames";

    /** What step 1 found. */
    public static final class Baseline {
        public final int frames, noMeasurementFrames, realFrames;
        /** The lowest and highest REAL readings, or NaN when there were none. */
        public final double lowestReal, highestReal;
        /**
         * WOULD A TISSUE-ADAPTATION ASSESSMENT EVER ARM ON THIS PUMP? The single
         * highest-value bit in this routine. It used to be false for a pump that reports
         * 0.0 when vented, because AssessVentTick armed only on a SETTLED real reading at
         * or below Tau.ARM_BELOW_KPA — and that was the owner's hardware: the after-test
         * refused, silently, whenever the vent's tail offered no settled pair. The pull now
         * also arms from the VENTED STATE (Tau#ventedStartKpa), so this is derived by
         * replaying the whole arming decision, {@link Tau#armStartKpa}, over the frames
         * actually seen — the app's own decision run against real data rather than a
         * second opinion about it.
         */
        public final boolean assessmentWouldArm;
        /** What that decision would arm the pull FROM: a settled real reading, or
         *  Tau.VENTED_START_KPA from the vented state. NaN when it would not arm. A real
         *  reading is never 0.0 (that is NO MEASUREMENT), so the two cannot be confused. */
        public final double armFromKpa;
        public final String finding;

        Baseline(int frames, int noMeas, int real, double lo, double hi,
                 double armFrom, String finding) {
            this.frames = frames; this.noMeasurementFrames = noMeas; this.realFrames = real;
            this.lowestReal = lo; this.highestReal = hi;
            this.armFromKpa = armFrom;
            this.assessmentWouldArm = !Double.isNaN(armFrom);
            this.finding = finding;
        }
    }

    /**
     * {@link #baseline(long[], double[], boolean[])} for frames with no timestamps: they are
     * taken to have arrived at the nominal Tau.TELEMETRY_INTERVAL_MS, which step 2 and P1
     * measure separately. The app's own call sites pass the real arrival times.
     */
    public static Baseline baseline(double[] kpa, boolean[] noReading) {
        int n = kpa == null ? 0 : kpa.length;
        long[] ts = new long[n];
        for (int i = 0; i < n; i++) ts[i] = i * Tau.TELEMETRY_INTERVAL_MS;
        return baseline(ts, kpa, noReading);
    }

    /**
     * What step 1 / P2 found in a window of frames from a pump nothing has pressurised.
     *
     * `assessmentWouldArm` asks Tau#armStartKpa exactly what AssessVentTick asks, with the
     * vent taken as evidenced: an assessment always issues its own StopWork first, and on a
     * live link streaming nothing but NO MEASUREMENT with no pressure seen, the vent watch
     * infers that stop (Session#ventedByInference). The question left is whether these
     * frames would let the pull start — settled at or below ARM_BELOW_KPA, or quiet long
     * enough after a low (or no) real reading — asked as of the last frame.
     */
    public static Baseline baseline(long[] ts, double[] kpa, boolean[] noReading) {
        int n = kpa == null ? 0 : kpa.length;
        int noMeas = 0, real = 0;
        double lo = Double.NaN, hi = Double.NaN;
        for (int i = 0; i < n; i++) {
            if (nr(noReading, i)) { noMeas++; continue; }
            real++;
            if (Double.isNaN(lo) || kpa[i] < lo) lo = kpa[i];
            if (Double.isNaN(hi) || kpa[i] > hi) hi = kpa[i];
        }
        String finding = n == 0 ? BASE_NO_FRAMES
                       : real == 0 ? BASE_ALL_NO_MEASUREMENT
                       : noMeas == 0 ? BASE_ALL_REAL : BASE_SOME_NO_MEASUREMENT;
        double armFrom = (n == 0 || ts == null || ts.length < n) ? Double.NaN
                       : Tau.armStartKpa(ts, kpa, noReading, ts[n - 1], true, Double.NaN);
        return new Baseline(n, noMeas, real, lo, hi, armFrom, finding);
    }

    /** What this finding means for the shipped feature, in the log, in words. */
    public static String baselineConsequence(Baseline b) {
        if (b == null || BASE_NO_FRAMES.equals(b.finding))
            return "no frames arrived — nothing was measured here";
        // THE FINDING THIS ROUTINE WAS BUILT FOR, and the owner's bug: a pump that reports
        // 0.0 when vented. It used to read "can NEVER arm", which was true of the app, not
        // of the pump; the app now arms from the vented state, so the line says so.
        if (b.assessmentWouldArm && BASE_ALL_NO_MEASUREMENT.equals(b.finding))
            return "this pump reports 0.0 (NO MEASUREMENT) when vented, so the tissue-adaptation "
                 + "assessment arms from the VENTED STATE: once its own StopWork is evidenced "
                 + "and the pump has stayed quiet for " + Session.VENT_INFER_MIN_FRAMES
                 + " frames, the pull starts from the open air (0 kPa)";
        if (!b.assessmentWouldArm && BASE_ALL_NO_MEASUREMENT.equals(b.finding))
            return "every frame was 0.0 (NO MEASUREMENT), but too few or too far apart to call "
                 + "the pump open, so a tissue-adaptation assessment would have waited and then "
                 + "refused (" + Tau.WHY_NO_START + ")";
        if (!b.assessmentWouldArm)
            return "no settled reading at or below " + Tau.ARM_BELOW_KPA + " kPa appeared in this "
                 + "window and the pump did not go quiet after a low one, so a tissue-adaptation "
                 + "assessment would have refused (" + Tau.WHY_NO_START + ") — either the vent "
                 + "had not finished, or the readings do not settle to within "
                 + Session.VENT_FALL_NOISE_FLOOR_KPA + " kPa of each other";
        if (b.armFromKpa == Tau.VENTED_START_KPA)
            return "the pump went quiet after a low reading, so the tissue-adaptation assessment "
                 + "arms from the vented state (0 kPa) on this pump";
        return "a settled real reading at or below " + Tau.ARM_BELOW_KPA + " kPa appeared, so the "
             + "tissue-adaptation assessment can arm on this pump";
    }

    /* ================================================================== step 2 ==== */

    /** A telemetry interval longer than this is reported as a GAP. Tau.MAX_GAP_MS is the
     *  same bound for the same reason: past a second, the density and contiguity checks
     *  Tau builds on the ~4.16 Hz spacing stop holding. */
    public static final long CADENCE_GAP_MS = Tau.MAX_GAP_MS;

    /** What step 2 found. A FRAME IS A FRAME whether or not it carried a reading: this
     *  measures the LINK's spacing, which is what Tau's density check and the link-loss
     *  timeout are built on, so 0.0 frames count like any other. */
    public static final class Cadence {
        public final int frames;
        public final long spanMs, meanMs, minMs, maxMs;
        public final int gaps;
        public final double hz;
        /** False when fewer than two frames arrived: an interval needs two frames, and one
         *  frame is not a rate of zero. */
        public final boolean derivable;
        Cadence(int frames, long span, long mean, long min, long max, int gaps, double hz,
                boolean derivable) {
            this.frames = frames; this.spanMs = span; this.meanMs = mean;
            this.minMs = min; this.maxMs = max; this.gaps = gaps; this.hz = hz;
            this.derivable = derivable;
        }
    }

    public static Cadence cadence(long[] tsMs) {
        int n = tsMs == null ? 0 : tsMs.length;
        if (n < 2) return new Cadence(n, 0, 0, 0, 0, 0, Double.NaN, false);
        long span = tsMs[n - 1] - tsMs[0];
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        int gaps = 0;
        for (int i = 1; i < n; i++) {
            long d = tsMs[i] - tsMs[i - 1];
            if (d < min) min = d;
            if (d > max) max = d;
            if (d > CADENCE_GAP_MS) gaps++;
        }
        long mean = Math.round(span / (double) (n - 1));
        double hz = span > 0 ? (n - 1) * 1000.0 / span : Double.NaN;
        return new Cadence(n, span, mean, min, max, gaps, hz, true);
    }

    /* ================================================================== step 3 ==== */

    /**
     * The fall rate, in kPa/s, over the `withinMs` immediately after `fromMs` — the instant
     * StopWork was issued, supplied by the caller rather than inferred from the first
     * frame, because "the first second of the vent" means the first second after the STOP
     * and not after whenever a frame happened to land.
     *
     * POSITIVE MEANS FALLING. The slope is taken between the first and last REAL readings
     * inside the window: never across a 0.0, never with one as an endpoint — the PC-side
     * analyser did exactly that and produced 383 kPa/s (Proto#parse's own note).
     *
     * Returns NaN, never 0.0, when fewer than two real readings fall inside the window or
     * they share a timestamp. 0.0 would claim a rate was measured and came out flat, which
     * is a different statement from "this could not be measured".
     *
     * `withinMs` of 0 or less means "to the end of the buffer".
     */
    public static double fallRate(long fromMs, long[] tsMs, double[] kpa, boolean[] noReading,
                                   long withinMs) {
        int n = (tsMs == null || kpa == null) ? 0 : Math.min(tsMs.length, kpa.length);
        long until = withinMs > 0 ? fromMs + withinMs : Long.MAX_VALUE;
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) {
            if (tsMs[i] < fromMs || tsMs[i] > until) continue;
            if (nr(noReading, i)) continue;
            if (first < 0) first = i;
            last = i;
        }
        if (first < 0 || last <= first) return Double.NaN;
        double dt = (tsMs[last] - tsMs[first]) / 1000.0;
        if (dt <= 0) return Double.NaN;
        return (kpa[first] - kpa[last]) / dt;
    }

    /** How long after `fromMs` the readings first settled ({@link #settledIndex}), or -1
     *  when they never did inside this buffer. */
    public static long settleMs(long fromMs, long[] tsMs, double[] kpa, boolean[] noReading) {
        int at = settledIndex(kpa, noReading);
        if (at < 0 || tsMs == null || at >= tsMs.length) return -1L;
        return tsMs[at] - fromMs;
    }

    /* ================================================================== step 4 ==== */

    /** How long the curve must stop climbing before it counts as flat. */
    public static final long PLATEAU_STABLE_MS = 5000L;
    /** How much deeper it may still go inside that window and still count as flat. The
     *  same 0.5 kPa Tau.PAIR_MATCH_TOL_KPA treats as "the same physical point", and
     *  comfortably above the 0.3 kPa Session.VENT_FALL_NOISE_FLOOR_KPA calls jitter. */
    public static final double PLATEAU_TOL_KPA = 0.5;

    /** What step 4 found. */
    public static final class Plateau {
        /** True when the curve stopped climbing with at least PLATEAU_STABLE_MS of
         *  telemetry left to prove it. False is a real and useful answer: it means the
         *  pull was still going deeper when its bound ran out. */
        public final boolean flattened;
        /** The deepest REAL reading anywhere in the window, or NaN when there was none.
         *  Reported whether or not the curve flattened — it is what the pump reached. */
        public final double deepestKpa;
        /** The reading at the onset of the flat stretch, or NaN when it never flattened. */
        public final double plateauKpa;
        /** Time from `fromMs` to that onset, or -1 when it never flattened. */
        public final long atMs;
        Plateau(boolean f, double deepest, double plateau, long at) {
            flattened = f; deepestKpa = deepest; plateauKpa = plateau; atMs = at;
        }
    }

    /**
     * Where the rise stopped.
     *
     * The onset is the first real reading after which nothing goes more than
     * PLATEAU_TOL_KPA deeper for a whole PLATEAU_STABLE_MS. Defined as "stops CLIMBING"
     * rather than "stays inside a band in both directions" on purpose: the firmware coasts
     * once it reaches target (Proto's class doc: 13.1 to 9.0 kPa in one hardware log, at a
     * rate nobody has measured - H14), so a two-sided flatness test could never fire on
     * this hardware and would report "never
     * flattened" for a pump that plainly had.
     */
    public static Plateau plateau(long fromMs, long[] tsMs, double[] kpa, boolean[] noReading) {
        int n = (tsMs == null || kpa == null) ? 0 : Math.min(tsMs.length, kpa.length);
        double deepest = Double.NaN;
        for (int i = 0; i < n; i++) {
            if (nr(noReading, i)) continue;
            if (Double.isNaN(deepest) || kpa[i] > deepest) deepest = kpa[i];
        }
        if (n == 0) return new Plateau(false, deepest, Double.NaN, -1L);
        long endTs = tsMs[n - 1];
        for (int i = 0; i < n; i++) {
            if (nr(noReading, i)) continue;
            if (endTs - tsMs[i] < PLATEAU_STABLE_MS) break;      // not enough left to prove it
            boolean flat = true;
            for (int j = i + 1; j < n && flat; j++) {
                if (tsMs[j] > tsMs[i] + PLATEAU_STABLE_MS) break;
                if (nr(noReading, j)) continue;
                if (kpa[j] > kpa[i] + PLATEAU_TOL_KPA) flat = false;
            }
            if (flat) return new Plateau(true, deepest, kpa[i], tsMs[i] - fromMs);
        }
        return new Plateau(false, deepest, Double.NaN, -1L);
    }

    /**
     * THE MEASURED ULTIMATE VACUUM, or NaN when this run did not measure one.
     *
     * Step 4 commands the CEILING, because a routine that commands more than the ceiling
     * is the one thing this app never does. The ceiling is at most 57 kPa (Proto#addPreset)
     * and the assumed ultimate vacuum is ~90, so on healthy hardware the pull stops because
     * it was TOLD to, not because the pump ran out — and the plateau it reaches is the
     * setpoint, which is a fact about the command and not about the pump.
     *
     * Reporting that setpoint as "measured ultimate vacuum" would put 57.00 against an
     * assumed 90.00 in the summary and flag a 37% divergence that means nothing whatever.
     * So: the ultimate vacuum counts as measured ONLY when the curve flattened SHORT of
     * what was commanded — that is the case where the pump, rather than the ceiling,
     * decided where it stopped. Otherwise this returns NaN and the summary says "not
     * measured", which is true, and {@link #ultimateNote} says why.
     *
     * The reach tolerance is Session.SEAL_REACH_TOLERANCE_KPA, the same 1.0 kPa the app
     * already uses for "did the cuff get to what was commanded", rather than a second
     * bound that could disagree with it.
     */
    public static double ultimateFrom(Plateau pl, int commandedKpa) {
        if (pl == null || Double.isNaN(pl.deepestKpa)) return Double.NaN;
        // FOUND BY THE ASSERTION, not by review: a curve that never FLATTENED has a
        // deepest reading too, and it was being returned. But that reading is where the
        // pull had got to when the phase's bound ran out — a lower bound on the pump's
        // limit, not the limit. Reporting it as an ultimate vacuum would put a number
        // decided by this routine's own clock into the summary against Tau's 90 kPa.
        if (!pl.flattened) return Double.NaN;
        if (commandedKpa > 0
                && pl.deepestKpa >= commandedKpa - Session.SEAL_REACH_TOLERANCE_KPA)
            return Double.NaN;
        return pl.deepestKpa;
    }

    /** Why the ultimate vacuum is, or is not, a number this run can report. */
    public static String ultimateNote(Plateau pl, int commandedKpa) {
        if (pl == null || Double.isNaN(pl.deepestKpa))
            return "no valid reading arrived during the pull — nothing was measured";
        if (!Double.isNaN(ultimateFrom(pl, commandedKpa)))
            return "the pull flattened SHORT of the " + commandedKpa + " kPa commanded, so "
                 + "this is the pump's own limit rather than the ceiling's";
        return "the pull reached the " + commandedKpa + " kPa it was commanded to, so the "
             + "CEILING decided where it stopped and this run did not measure an ultimate "
             + "vacuum at all. Raising the ceiling (up to the protocol's own 57 kPa) is the "
             + "only way to measure more of it, and 57 is still well short of the ~"
             + (int) ASSUMED_ULTIMATE_KPA + " kPa Tau's derivation assumes.";
    }

    /* ================================================================== step 5 ==== */

    /** The window step 5 reports its coast rate over. */
    public static final long COAST_WINDOW_MS = 10000L;

    /**
     * The decay rate over each consecutive `windowMs` window from `fromMs`, in kPa/s.
     *
     * POSITIVE MEANS FALLING, NEGATIVE MEANS STILL CLIMBING, NaN means the window did not
     * carry two real readings. That signed convention is a deliberate divergence from
     * Session#coastingDecayRate, which clamps at zero and anchors at the peak — correct
     * for a seal check, which asks "how fast is it leaking" of a curve that has already
     * risen. This is an instrument: a window in which the pressure ROSE is a fact about
     * the pump, and printing 0.00 for it would hide exactly the shape (a pull still
     * completing inside what was supposed to be a coast) that would invalidate the
     * measurement. The clamped, peak-anchored figure the app itself would print is
     * reported ALONGSIDE these, from Session#coastingDecayRate, so the log carries both.
     */
    public static double[] windowRates(long fromMs, long[] tsMs, double[] kpa,
                                        boolean[] noReading, long windowMs, int windows) {
        double[] out = new double[Math.max(0, windows)];
        for (int w = 0; w < out.length; w++)
            out[w] = fallRate(fromMs + w * windowMs, tsMs, kpa, noReading, windowMs);
        return out;
    }

    /** How many whole `windowMs` windows the buffer spanning `fromMs`..last actually
     *  covers — so the report never prints windows the data does not reach. */
    public static int windowCount(long fromMs, long[] tsMs, long windowMs) {
        int n = tsMs == null ? 0 : tsMs.length;
        if (n == 0 || windowMs <= 0) return 0;
        long span = tsMs[n - 1] - fromMs;
        if (span <= 0) return 0;
        return (int) (span / windowMs);
    }

    /* ================================================================== step 6 ==== */

    /** One rise curve, reduced by Tau exactly as the shipped feature reduces one. */
    public static final class Rise {
        public final int speedPct;
        /** What the device ECHOED as its speed in telemetry, or -1 when nothing did.
         *  Proto#speedCode's mapping is only validated end to end by comparing this
         *  against {@link #expectedEchoPct}. */
        public final int echoedSpeedPct;
        public final Tau.Result tau;
        Rise(int sp, int echo, Tau.Result t) { speedPct = sp; echoedSpeedPct = echo; tau = t; }
        public boolean speedEchoMatches() {
            return echoedSpeedPct >= 0 && echoedSpeedPct == expectedEchoPct(speedPct);
        }
    }

    /**
     * What a device that received Proto#speedCode(pct) should echo back. NOT `pct`: the
     * wire carries a 0..255 code, so what comes home is that code decoded.
     *
     * AS IT HAPPENS, the round trip IS the identity for every percentage 0..100 this app
     * can send — SelfTest asserts that over all 101 values rather than leaving it as an
     * impression, and if it ever stops being true, that assertion is the finding. So this
     * method is not currently correcting anything. It exists because the RULE is "compare
     * against what the codec says should come back", and a comparison written directly
     * against `pct` would be relying on a coincidence of the rounding constants without
     * saying so — and would quietly start reporting a codec defect on a healthy pump the
     * day either constant changed.
     */
    public static int expectedEchoPct(int pct) {
        return Proto.speedPct(Proto.speedCode(pct));
    }

    /**
     * Step 6's report for one speed: Tau.compute run against a REAL curve, carrying its
     * refusal reason unchanged when it refuses. The addendum asks for exactly that —
     * "report what Tau.compute returns for each, with its refusal reason if it refuses" —
     * so nothing here second-guesses the result or retries it with looser inputs.
     */
    public static Rise rise(int speedPct, int echoedSpeedPct, long[] tsMs, double[] kpa,
                             boolean[] noReading, int commandedKpa, long commandedAtMs,
                             double startKpa) {
        return new Rise(speedPct, echoedSpeedPct,
                        Tau.compute(tsMs, kpa, noReading, commandedKpa, commandedAtMs, startKpa));
    }

    /* ================================================================== step 7 ==== */

    /** How long the first pull runs before the stop that the re-assert has to survive. */
    public static final long RESTART_ARM_MS = 8000L;
    /** The addendum's own figure: StopWork, wait 500 ms, then startSlot. */
    public static final long RESTART_STOP_GAP_MS = 500L;
    /** How long the re-asserted start is watched for evidence that the pump ran. */
    public static final long RESTART_OBSERVE_MS = 10000L;
    /** How far pressure must move above the re-assert's own starting reading before the
     *  pump counts as having run. Comfortably above the 0.3 kPa this app calls jitter
     *  (Session.VENT_FALL_NOISE_FLOOR_KPA) and far below the commanded target, so neither
     *  a noisy sensor nor a pump that merely twitched can answer this question yes. */
    public static final double RESTART_RISE_KPA = 1.0;

    public static final String RESTART_PULL = "pull";
    public static final String RESTART_STOP = "stop";
    public static final String RESTART_REASSERT = "reassert";
    public static final String RESTART_OBSERVE = "observe";
    public static final String RESTART_DONE = "done";

    /**
     * Which part of step 7 is live, `sinceArmMs` into the phase — pure, so the sequence
     * itself (pull, stop, exactly RESTART_STOP_GAP_MS, re-assert, watch) is asserted
     * rather than living in a chain of postDelayed calls nothing can see.
     *
     * RESTART_REASSERT is the single instant the re-assert goes out; the driver polls
     * faster than that instant is wide, so it acts on the FIRST tick at or past the gap.
     * The boundary is written as ">=" here rather than "==" so a tick that lands late (a
     * busy main thread) still produces the re-assert rather than skipping silently into
     * the observation window with nothing having been sent — the caller latches it once.
     */
    public static String restartSubPhase(long sinceArmMs) {
        long reassertAt = RESTART_ARM_MS + RESTART_STOP_GAP_MS;
        if (sinceArmMs < RESTART_ARM_MS) return RESTART_PULL;
        if (sinceArmMs < reassertAt) return RESTART_STOP;
        if (sinceArmMs < reassertAt + RESTART_OBSERVE_MS) return RESTART_REASSERT;
        return RESTART_DONE;
    }

    /** What step 7 found. */
    public static final class Restart {
        /** True when the question cannot be answered: no baseline reading at the instant
         *  the start was re-asserted, or no real reading after it. An unanswerable question
         *  is reported as unanswered — never as "the pump did not run", which is a claim
         *  about the firmware. */
        public final boolean indeterminate;
        public final boolean ran;
        /** The deepest real reading after the re-assert, or NaN. */
        public final double peakKpa;
        /** When pressure first cleared RESTART_RISE_KPA above the start, or -1. */
        public final long firstRiseMs;
        Restart(boolean ind, boolean ran, double peak, long first) {
            indeterminate = ind; this.ran = ran; peakKpa = peak; firstRiseMs = first;
        }
    }

    public static Restart restart(long fromMs, long[] tsMs, double[] kpa, boolean[] noReading,
                                   double startKpa) {
        int n = (tsMs == null || kpa == null) ? 0 : Math.min(tsMs.length, kpa.length);
        double peak = Double.NaN;
        long first = -1L;
        int real = 0;
        for (int i = 0; i < n; i++) {
            if (tsMs[i] < fromMs) continue;
            if (nr(noReading, i)) continue;
            real++;
            if (Double.isNaN(peak) || kpa[i] > peak) peak = kpa[i];
            if (first < 0 && !Double.isNaN(startKpa) && kpa[i] >= startKpa + RESTART_RISE_KPA)
                first = tsMs[i] - fromMs;
        }
        if (Double.isNaN(startKpa) || real == 0) return new Restart(true, false, peak, -1L);
        return new Restart(false, first >= 0, peak, first);
    }

    /* ================================================================== step 8 ==== */

    /** One preset record, in either direction: what was sent, or what came back in the
     *  0x29 dump. Field order is the Add frame's own payload order (docs/protocols/zd21.md),
     *  which is what makes a shifted payload visible AS a shifted payload. */
    public static final class Rec {
        public final int speedPct, upper, upperHold, lower, lowerHold;
        public Rec(int speedPct, int upper, int upperHold, int lower, int lowerHold) {
            this.speedPct = speedPct; this.upper = upper; this.upperHold = upperHold;
            this.lower = lower; this.lowerHold = lowerHold;
        }
        @Override public String toString() {
            return speedPct + "% " + upper + "/" + lower + " kPa " + upperHold + "/"
                 + lowerHold + " s";
        }
    }

    /**
     * The nine presets step 8 writes. Every field differs from every other field in the
     * same record AND from the same field in every other record, so a swapped upper/lower
     * pair, a shifted payload or an off-by-one slot index all show up as a mismatch rather
     * than reading as a pass.
     *
     * At a low ceiling the upper setpoints clamp together and stop being distinguishable.
     * That is the ceiling doing its job, and it is reported as such: the comparison is
     * against what was SENT (post-clamp), so it still detects a transport fault — it just
     * loses the ability to detect a slot mix-up.
     */
    public static Rec[] planPresets(int ceilKpa) {
        Rec[] out = new Rec[Proto.SLOTS];
        for (int i = 0; i < out.length; i++) {
            Setpoints sp = clampBand(10 + 5 * i, 8 + 4 * i, ceilKpa);
            out[i] = new Rec(30 + 5 * i, sp.upper, 3 + i, sp.lower, 12 + i);
        }
        return out;
    }

    /**
     * The device's work-mode dump (opcode 0x29): one header byte, then fixed 5-byte
     * records — speed code, upper kPa, upper hold s, lower kPa, lower hold s, the Add
     * payload's own order (docs/protocols/zd21.md). Returns null when this is not a dump
     * frame at all, and a zero-length array for the one-byte "no presets configured" reply,
     * which is a real answer rather than an error. A trailing partial record is ignored
     * rather than guessed at.
     */
    public static Rec[] parseDump(byte[] frame) {
        if (frame == null || frame.length < 1) return null;
        if ((frame[0] & 0xFF) != Proto.OP_LIST) return null;
        int n = (frame.length - 1) / 5;
        Rec[] out = new Rec[n];
        for (int i = 0; i < n; i++) {
            int b = 1 + 5 * i;
            out[i] = new Rec(Proto.speedPct(frame[b] & 0xFF),
                             frame[b + 1] & 0xFF, frame[b + 2] & 0xFF,
                             frame[b + 3] & 0xFF, frame[b + 4] & 0xFF);
        }
        return out;
    }

    /**
     * What differs between the record sent and the record that came back — "" when they
     * agree. The speed is compared against {@link #expectedEchoPct}, never against the
     * percentage asked for, because the wire carries a code (see that method).
     */
    public static String compareRec(Rec sent, Rec got) {
        if (sent == null && got == null) return "";
        if (sent == null) return "nothing was sent for this slot but " + got + " came back";
        if (got == null) return "sent " + sent + " but nothing came back for this slot";
        StringBuilder sb = new StringBuilder();
        diff(sb, "speed", expectedEchoPct(sent.speedPct), got.speedPct);
        diff(sb, "upper", sent.upper, got.upper);
        diff(sb, "upperHold", sent.upperHold, got.upperHold);
        diff(sb, "lower", sent.lower, got.lower);
        diff(sb, "lowerHold", sent.lowerHold, got.lowerHold);
        return sb.toString();
    }

    private static void diff(StringBuilder sb, String field, int sent, int got) {
        if (sent == got) return;
        if (sb.length() > 0) sb.append("; ");
        sb.append(field).append(" sent ").append(sent).append(" got ").append(got);
    }

    /* ================================================================= summary ==== */

    /** One line of the closing summary: a measured value beside what the app assumes. */
    public static final class Line {
        public final String name, units, note;
        /** NaN when this run did not manage to measure it — reported as such, never as 0. */
        public final double measured;
        /** NaN when the app assumes nothing, which is itself the finding for the coast
         *  rate: the seal check prints a decay figure it explicitly cannot judge. */
        public final double assumed;
        public final boolean flagged;
        Line(String name, double measured, double assumed, String units, String note) {
            this.name = name; this.measured = measured; this.assumed = assumed;
            this.units = units; this.note = note;
            this.flagged = diverges(measured, assumed);
        }
    }

    public static Line line(String name, double measured, double assumed, String units,
                             String note) {
        return new Line(name, measured, assumed, units, note);
    }

    /**
     * Do these two numbers disagree by more than a fifth?
     *
     * False whenever the comparison cannot honestly be made: nothing measured, nothing
     * assumed, or an assumed value of zero (which has no percentage). A flag that fired on
     * absent data would make an unmeasured run look like a divergent one, which is the
     * opposite of what this instrument is for.
     */
    public static boolean diverges(double measured, double assumed) {
        if (Double.isNaN(measured) || Double.isNaN(assumed)) return false;
        if (assumed == 0.0) return false;
        return Math.abs(measured - assumed) / Math.abs(assumed) > DIVERGENCE_FLAG_FRACTION;
    }

    /** The signed divergence as a fraction of the assumed value, or NaN when there is no
     *  honest comparison to make. */
    public static double divergence(double measured, double assumed) {
        if (Double.isNaN(measured) || Double.isNaN(assumed) || assumed == 0.0) return Double.NaN;
        return (measured - assumed) / Math.abs(assumed);
    }

    /**
     * One summary line, rendered.
     *
     * ALWAYS kPa, never the display unit. Storage is kPa everywhere in this app and the
     * unit setting is display-only; this log records what the wire carried and what the
     * app's own constants say, both of which are kPa quantities. Model.Fmt.p() would also
     * print vacuum as a NEGATIVE inHg value, which would make a measured constant read as
     * its own negation in an engineering log. The SCREEN, which is a screen, uses Fmt like
     * every other screen does.
     */
    public static String render(Line l) {
        StringBuilder sb = new StringBuilder();
        sb.append(l.flagged ? "!! " : "   ").append(l.name).append(": ");
        sb.append(Double.isNaN(l.measured)
                    ? "not measured"
                    : String.format(Locale.US, "%.2f", l.measured) + " " + l.units);
        if (Double.isNaN(l.assumed)) {
            sb.append("   ·   the app assumes nothing here");
        } else {
            sb.append("   ·   the app assumes ")
              .append(String.format(Locale.US, "%.2f", l.assumed)).append(" ").append(l.units);
            double d = divergence(l.measured, l.assumed);
            if (!Double.isNaN(d))
                sb.append("   ·   ").append(String.format(Locale.US, "%+.1f%%", d * 100.0));
        }
        if (l.note != null && l.note.length() > 0) sb.append("\n        ").append(l.note);
        return sb.toString();
    }

    /** The vent-rate line. `assumedVentRate` is SessionActivity.VENT_RATE, handed in
     *  rather than copied — see {@link #ASSUMED_ULTIMATE_KPA}'s note on why exactly one
     *  assumed constant lives in this file and the rest do not. */
    public static Line ventRateLine(double measured, double assumedVentRate) {
        return line("StopWork vent rate", measured, assumedVentRate, "kPa/s",
            "sets the vent-confirmation window and its noise floor "
            + "(Session.VENT_FALL_NOISE_FLOOR_KPA " + Session.VENT_FALL_NOISE_FLOOR_KPA
            + " kPa, VENT_FALL_RATE_TOLERANCE " + Session.VENT_FALL_RATE_TOLERANCE + ")");
    }

    public static Line cadenceLine(double measuredMeanMs) {
        return line("telemetry interval", measuredMeanMs, (double) Tau.TELEMETRY_INTERVAL_MS,
            "ms", "Tau's density and contiguity checks (MIN_DENSITY, MAX_GAP_MS) and the "
            + "link-loss timeout are all built on this spacing");
    }

    public static Line ultimateLine(double measured) {
        return line("ultimate vacuum", measured, ASSUMED_ULTIMATE_KPA, "kPa",
            "Tau's class doc derives PAIR_MATCH_TOL_KPA from A ~ 90 kPa; redo that "
            + "derivation with the measured number");
    }

    public static Line ventedBaselineLine(double measuredLowest) {
        return line("vented baseline", measuredLowest, Tau.ARM_BELOW_KPA, "kPa",
            "a tissue-adaptation assessment arms only on a settled reading at or below "
            + "ARM_BELOW_KPA — and 0.0 is NO MEASUREMENT, not a low value");
    }

    public static Line coastLine(double measured) {
        return line("coast rate under a sealed load", measured, Double.NaN, "kPa/s",
            "THE APP HAS NO REFERENCE FOR THIS. The seal check prints a decay figure and "
            + "says plainly that it does not judge it, because how fast a hold coasts has "
            + "never been measured (release checklist H14): the only figure on record is a "
            + "13.1 to 9.0 kPa drift in one early hardware log. This measurement is what "
            + "would ground a real threshold — taken on a RIGID VESSEL, which is not a cuff "
            + "on a person, so it bounds the leak-free case rather than setting the "
            + "threshold outright.");
    }

    /* ------------------------------------------------------------------ helpers */

    private static boolean nr(boolean[] noReading, int i) {
        return noReading != null && i < noReading.length && noReading[i];
    }

    /** Milliseconds as a plain "m:ss", for the up-front bound and the countdowns. */
    public static String mmss(long ms) {
        long s = Math.max(0L, ms) / 1000L;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
