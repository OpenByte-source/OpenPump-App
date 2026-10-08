package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure state and decision logic behind the standardisation hold and the release gate
 * that follows it — kept out of SessionActivity (which owns the BLE link, the Handler
 * ticks and the real screens) so the load-bearing arithmetic can be exercised by the
 * desktop self-test without a device or an Activity.
 *
 * Mirrors proto/pump-console.html's STDHELD/STDSKIPPED/RELFROM machinery, corrected per
 * .superpowers/sdd/2026-08-17-pump-beta/prototype-known-defects.md #12, #14, #15, #17,
 * #19 and #29 — six defects that share one root cause: a timer armed before its
 * navigation was confirmed, and "did the hold run?" inferred from which button was
 * pressed instead of from measured elapsed time. See SessionActivity's startStd /
 * exitStdHold / startRelease / exitRelease for the callers that own the actual screens.
 *
 * Task 10 (see the class's own "Task 10" section below) adds the seal check and the
 * live run screen's decision logic: vent confirmation (S1), the elapsed-time-driven
 * stage/phase derivation that replaces defects #25/#26's hardcoded values, the
 * link-loss freeze that stops the playhead and suppresses shortfall (S2), and the
 * coasting-hold decay-rate arithmetic behind the seal check. "Extracting the run
 * engine into Session" (this task's Step 1) means exactly this: the PURE decisions —
 * which stage/phase is live, whether a vent is confirmed, what elapsed time is safe
 * to display — move here, testable without a device. The impure mechanics they still
 * need (BLE writes, Handler ticks, the actual upload-batches-of-9 preset table
 * management) stay in SessionActivity, which owns the real link and the real screens.
 */
public final class Session {

    /** The pressure actually commanded by the standardisation hold currently (or most
     *  recently) in effect, or null once no hold is outstanding. Set the instant the
     *  hold starts pulling — armHold() — never by whether the countdown finished, and
     *  never cleared by a skip: a skipped hold still commanded the pump to pull to this
     *  pressure, so the release gate still has real vacuum to vent (defect #12 — the
     *  prototype's bug was inferring "did the hold run?" from STDSKIPPED, so a skip made
     *  the release gate believe there was nothing to release while the pump was still
     *  holding vacuum). Cleared only by clearHeld(), once a release has actually
     *  completed — never merely because a screen was exited. */
    private Double heldKpa;

    /** Start time of the hold this heldKpa belongs to, on the hold's clock - the app's is
     *  monotonic (SessionActivity#holdClock, the final review's M6); 0 when none is armed. */
    private long holdStartedAt;

    /** Whether the CURRENT hold's countdown has genuinely elapsed, as of the last time it
     *  was checked. SessionActivity re-derives this from elapsed time on every exit from
     *  the hold screen, regardless of which control triggered the exit — defect #14: a
     *  hold that ran to completion must not be filed as skipped just because the tap
     *  that ended the screen happened to land on the Skip button rather than the
     *  (disabled-until-done) Done button. Read by the reading logger and the
     *  comparability badge — never by anything that only knows which button was
     *  pressed. */
    private boolean holdFinished;

    /** Arms a new hold: the pump is being commanded to `kpa`, starting at `now`. */
    public void armHold(int kpa, long now) {
        heldKpa = Double.valueOf(kpa);
        holdStartedAt = now;
        holdFinished = false;
        // A NEW hold, even at the SAME configured pressure as the last one, is a
        // distinct instance — Fix round 2's Important finding: a caller watching
        // to see "has THIS hold been vented" (e.g. an async confirmation still
        // outstanding when a newer hold begins) cannot use heldKpa's VALUE to
        // tell them apart, since it is always the same configured int. This
        // counter is real identity, the pattern CameraGate already uses.
        holdGeneration++;
    }

    /** Bumped by every armHold() call — see its doc comment. Callers that start
     *  an async confirmation for "the hold outstanding right now" must snapshot
     *  this at that instant and compare it later, never compare heldKpa by value. */
    public long holdGeneration() { return holdGeneration; }
    private long holdGeneration;

    /** Milliseconds elapsed since armHold() was called, given the current time on the
     *  clock armHold() was given (the app's is monotonic). A clock, not a tick count — see the class doc comment and defect #17:
     *  a countdown must never depend on how many ticks a (possibly throttled) app
     *  managed to deliver. */
    public long holdElapsedMs(long now) {
        return holdStartedAt == 0 ? 0 : Math.max(0, now - holdStartedAt);
    }

    /** Whether a hold that has been running for `elapsedMs` against a `configuredSec`
     *  target has genuinely completed. The single source of truth for "did the hold
     *  run?" — SessionActivity calls this at every exit from the hold screen (Done tap,
     *  either Skip tap — all funnel through the same exit path) rather than branching on
     *  which control was tapped. Inclusive at the boundary: a hold observed at exactly
     *  its configured duration counts as complete, matching the on-screen countdown
     *  reaching exactly 0. */
    public static boolean holdComplete(long elapsedMs, int configuredSec) {
        return elapsedMs >= (long) configuredSec * 1000L;
    }

    /**
     * C8 - WHAT "FINISHED" MEANS, stated once: the dwell AT pressure reached the configured
     * duration ({@link #holdComplete}, the same inclusive boundary), AND the measuring
     * started while the two-minute window that the completed dwell opened was still open.
     *
     * `windowEndsAt` is 0 until the tick that completes the dwell opens the window; a
     * window that was never opened is not an open one. It closes AT its end.
     *
     * One rule for every route into a reading: the hold screen's own exit (exitStdHold) and
     * the routine that ends into a hold, whose "Measure now" never passes that screen and so
     * was never filed as standardised at all (study problem 3). WiringCheck invariant 53
     * holds both call sites to it; HoldFinishedTest pins it.
     */
    public static boolean holdFinishedAt(long dwellMs, int configuredSec, long windowEndsAt,
                                         long now) {
        return holdComplete(dwellMs, configuredSec) && windowEndsAt > 0 && now < windowEndsAt;
    }

    public void setHoldFinished(boolean finished) { holdFinished = finished; }
    public boolean isHoldFinished() { return holdFinished; }

    /** The pressure commanded by the outstanding hold, or null if none is outstanding —
     *  the release gate's ONLY source of truth for "is there real vacuum to vent?". */
    public Double heldKpa() { return heldKpa; }

    /** True only when no hold has ever commanded pressure since the last completed
     *  release — never true merely because the hold was skipped (defect #12), and never
     *  confused with "the hold ran but the release target happens to equal the hold
     *  pressure" (defect #29 — that case still has vacuum to acknowledge, it just has
     *  nothing left to vent). */
    public boolean noHoldRan() { return heldKpa == null; }

    /** The pressure the release gate must vent FROM: the pressure actually commanded, if
     *  any hold is outstanding, falling back to the configured release target only when
     *  no hold ever ran (nothing was pulled, so there is nothing above the target to
     *  vent from). Never derived from STDSKIPPED / which button ended the hold screen —
     *  that inference is exactly what made defect #12 a false safety statement. */
    public double releaseFromKpa(int stdReleaseKpa) {
        return heldKpa != null ? heldKpa.doubleValue() : stdReleaseKpa;
    }

    /** Marks the outstanding hold as fully vented — call once the release gate has
     *  actually reached its target (or an equivalent real vent has been issued), never
     *  merely because the gate screen was exited (defect #15's "belt and braces": tearing
     *  down the on-screen timer is not the same event as the pump actually being safe). */
    public void clearHeld() {
        heldKpa = null;
        holdStartedAt = 0;
        holdFinished = false;
    }

    /* ------------------------------------------------------------- vent arithmetic */

    /** How long, in milliseconds, venting from `fromKpa` down to `toKpa` takes at
     *  `kpaPerSec` (the measured StopWork rate — VENT_RATE in SessionActivity). Zero
     *  when there is nothing to vent — a target at or above the starting pressure —
     *  never negative. */
    public static long ventMs(double fromKpa, double toKpa, double kpaPerSec) {
        double span = fromKpa - toKpa;
        if (span <= 0 || kpaPerSec <= 0) return 0L;
        return Math.round(span / kpaPerSec * 1000.0);
    }

    /** The simulated pressure `elapsedMs` into a vent from `fromKpa` toward `toKpa` at
     *  `kpaPerSec`, clamped so it can never overshoot past the target — mirrors the
     *  prototype's paintRel() relP computation, which SessionActivity's release-gate
     *  Handler tick re-runs every 200 ms against the real wall clock. */
    public static double ventPressureAt(double fromKpa, double toKpa, double kpaPerSec,
                                         long elapsedMs) {
        double dropped = kpaPerSec * (elapsedMs / 1000.0);
        double p = fromKpa - dropped;
        return p < toKpa ? toKpa : p;
    }

    /* ============================================================ Task 10 ==== */
    /*
     * The seal check and the live run screen — the screens on-display while the pump
     * is actually pulling vacuum on a person. Two blocking safety requirements govern
     * everything below (see .superpowers/sdd/2026-08-17-pump-beta/task-10-brief.md):
     *
     *   S1 — a vent must be CONFIRMED, never merely sent. PumpLink.tx() silently
     *   no-ops (logs and returns false) when the link is down, so "I called tx()" is
     *   not evidence anything happened. ventConfirmed()/ventState() are the ONE place
     *   that decision is made; SessionActivity must never render a completed vent
     *   without asking one of them first.
     *
     *   S2 — link loss during a run must not read as a completed or safe state. The
     *   run-tracking fields and elapsedMs() below implement the freeze: once
     *   telemetry has been silent for longer than a caller-supplied timeout, the
     *   elapsed value returned is LATCHED at whatever it was the instant the gap was
     *   detected, not wherever wall-clock has since wandered to. Every other Task 10
     *   quantity that depends on "how far along is this run" (stage, phase, progress)
     *   is derived from that same latched value, so they freeze together rather than
     *   independently drifting out of agreement.
     */

    /** Whether a stop just attempted can be trusted as VENTED — never merely that it
     *  was attempted. Both conditions are required: the link had to be ready to
     *  accept a write AND the write itself had to report success. Neither alone is
     *  sufficient — PumpLink.tx() can be attempted while isReady() is already false
     *  (mid-teardown), and, conversely, checking only isReady() before a write that
     *  itself failed would still be trusting an unconfirmed vent. */
    public static boolean ventConfirmed(boolean linkWasReady, boolean txSucceeded) {
        return linkWasReady && txSucceeded;
    }

    public static final String VENT_STATE_VENTED      = "VENTED";
    public static final String VENT_STATE_UNCONFIRMED = "UNCONFIRMED";

    /** ventConfirmed(), spelled as the exact two-state vocabulary the UI must use:
     *  a stop that cannot be substantiated is UNCONFIRMED, never quietly treated as
     *  the absence of a problem. SessionActivity must render UNCONFIRMED as "may
     *  still be holding pressure — disconnect the tubing at the cuff" (S1), never as
     *  a completed vent.
     *
     *  IMPORTANT: this only proves the WRITE — the local BLE stack accepted the
     *  bytes on a ready link. ZD21 (Epic Hydro PE Pump) writes are WRITE_TYPE_NO_RESPONSE,
     *  and whether the pump acknowledges every stop is unknown (release checklist H15), so a
     *  successful write and a pump that silently ignored it are indistinguishable by this
     *  test alone. Wherever
     *  telemetry is available to actually watch the pressure, use ventResult()
     *  below instead — it is the real S1 test. ventConfirmed()/ventState() remain
     *  as the write-only signal ventResult() itself gates on internally, and for
     *  the rare caller with no telemetry at all to watch. */
    public static String ventState(boolean linkWasReady, boolean txSucceeded) {
        return ventConfirmed(linkWasReady, txSucceeded) ? VENT_STATE_VENTED : VENT_STATE_UNCONFIRMED;
    }

    /**
     * HIGH (fix round 2): whether a generic vent-watch loop (SessionActivity's
     * VentWatchTick/VentWatchRetry) should schedule ANY further work — a poll or
     * a retry — after invoking its update callback. False whenever the callback
     * itself dismissed the watch (it has decided to act on the current result
     * and move on to something else, e.g. arming a fresh hold) OR the watch has
     * already resolved to VENTED. Without this check, the loop's own control
     * flow would reschedule a retry regardless of what the callback just
     * decided — which is exactly how a stray StopWork ended up firing into a
     * screen that had already moved on (the seal check's fresh hold, the
     * routine's own presets, both real cases this fixes).
     */
    public static boolean ventWatchShouldContinue(boolean dismissedByCallback, boolean vented) {
        return !dismissedByCallback && !vented;
    }

    /** The third vent-result state: the write went out on a ready link, but no
     *  telemetry evidence of an actual pressure fall exists YET — still within the
     *  watch window. Never confuse this with VENTED; a caller must keep watching
     *  (or retry) rather than treat silence-so-far as success. */
    public static final String VENT_STATE_SENT_UNVERIFIED = "SENT_UNVERIFIED";

    /** The fourth vent-result state: no pressure was DETECTED, on a link that is still
     *  delivering frames, for long enough that a vented pump is by far the likeliest
     *  explanation — but no single reading ever proved it, because this pump reports
     *  "no measurement" (0.0) for most frames when it is open. Gated the same as VENTED
     *  (there is nothing further to retry), RENDERED honestly and differently: a caller
     *  must never print "vent confirmed by falling telemetry" for this state. See
     *  {@link #ventedByInference}. */
    public static final String VENT_STATE_VENTED_INFERRED = "VENTED_INFERRED";

    /**
     * NOT VENTED, ON PURPOSE: the routine ended into a hold at the standardised measurement
     * pressure, so an after reading is taken under the same conditions as the last one.
     *
     * A state of its own rather than a flavour of UNCONFIRMED, because the two are opposite
     * claims. UNCONFIRMED means "a stop was sent and the fall was not seen" - a failure to
     * evidence. This means "no stop was sent, and the app knows exactly why". Folding them
     * together would have the summary warn about a vent nobody attempted, and would let
     * {@link #ventedNow} treat a deliberate hold as a possible vent.
     *
     * {@link #ventedNow} returns false for it, which is the point: the cuff IS under
     * pressure, every screen that asks is told so, and leaving the app still vents it.
     */
    public static final String VENT_STATE_HELD_FOR_MEASURE = "HELD_FOR_MEASURE";

    /** How many CONSECUTIVE no-measurement frames the inference needs. At ~4.16 Hz
     *  (~240 ms/frame) 12 frames is about 2.9 s of unbroken no-measurement — far past any
     *  ordinary burst of frame loss. */
    public static final int VENT_INFER_MIN_FRAMES = 12;
    /** …and how long that run must actually SPAN in wall-clock terms, so a burst of
     *  frames arriving back-to-back after a stall cannot satisfy the count alone. */
    public static final long VENT_INFER_MIN_SPAN_MS = 2500L;
    /** The largest gap between frames (and between the last frame and now) that still
     *  counts as "telemetry is alive". ~5 frames at 4.16 Hz: tolerant of BLE jitter,
     *  intolerant of a link that has actually stopped. */
    public static final long VENT_INFER_MAX_FRAME_GAP_MS = 1200L;
    /** The pressure below which a last real reading is "already low" — low enough that a
     *  run of no-measurement frames after it reads as an open system rather than as a
     *  sensor dropout at pressure. NOT always under what the app commands: the ceiling can
     *  be as low as 7 kPa and a set's pull as low as 1 kPa, so a lost stop over a hold at or
     *  under about 6.5 kPa, followed by a sensor dropout, can still read as inferred-vented
     *  (the fall-threshold review measured it; it is not new). */
    public static final double VENT_INFER_LOW_KPA = 6.0;

    /** Whether a vent-state string may be treated as vented FOR GATING — VENTED or
     *  VENTED_INFERRED. Rendering must still distinguish the two; this is the one place
     *  the gating question is answered, so a consumer cannot half-handle the new state. */
    public static boolean ventedForGating(String state) {
        return VENT_STATE_VENTED.equals(state) || VENT_STATE_VENTED_INFERRED.equals(state);
    }

    /**
     * (the final review, M4) HOW A VENT WAS KNOWN, IN THE JOURNAL'S ONE WORDING. A reported
     * fall is "evidenced (VENTED)"; no pressure from a pump still talking is "inferred
     * (VENTED_INFERRED)" - gated on as a vent, never logged as one confirmed by telemetry;
     * anything else is "not evidenced (state)". Every vent-watch update that logs how its vent
     * ended says it through here.
     */
    public static String ventEvidence(String state) {
        if (VENT_STATE_VENTED.equals(state)) return "evidenced (" + state + ")";
        if (VENT_STATE_VENTED_INFERRED.equals(state)) return "inferred (" + state + ")";
        return "not evidenced (" + state + ")";
    }

    /** How much of a pressure reading counts as "has genuinely moved" rather than
     *  ordinary telemetry jitter — comfortably larger than sample-to-sample noise,
     *  comfortably smaller than what even a slow vent produces within a couple of
     *  samples at the ~4.16 Hz telemetry rate (Proto's doc comment). The vent watch uses it
     *  as the margin on an arrival at the target; a FALL has to be
     *  {@link #VENT_FALL_EVIDENCE_MIN_KPA} before it is evidence of a vent. */
    public static final double VENT_FALL_NOISE_FLOOR_KPA = 0.3;

    /** How much of the RATE-predicted fall (kpaPerSec * elapsed) must actually be
     *  observed before it counts as real progress rather than the noise floor above
     *  being crossed by chance — half the reference rate, because real hardware
     *  (valve wear, tubing, cuff fit, a coasting drift already in progress) is never
     *  exactly the measured constant, but a genuine vent still has to show SOME
     *  measurable fraction of the expected drop. */
    public static final double VENT_FALL_RATE_TOLERANCE = 0.5;

    /**
     * THE LEAST FALL THAT IS EVIDENCE OF A VENT: more than the pump ever falls by itself while
     * it holds.
     *
     * The pump moves the cuff by itself. The run's Hold goes to it as the pull for 255 s, then
     * a kPa under it for 1 s (InRunHold, RunEdit#clampLower), so every 256 s the pump lets the
     * cuff fall 1 kPa within a frame or two. With the noise floor (0.3 kPa) as the least fall,
     * that step seen within 0.43 s of a StopWork passed as its vent, and when the StopWork was
     * LOST the watch said VENTED over a pump that went straight back to the pull. StepWindow
     * keeps a Hold limit's first window clear of the step; the retries, 8 and 16 s later, and
     * a STOP pressed by hand are not.
     *
     * 2.5 kPa, from the owner's journal of the real pump (session-20260922-151005), which logs
     * a reading about every 6 s. Of its 239 pairs of readings with nothing sent between them,
     * every fall of 2.5 kPa or more (19) is the pump's own programmed release to a routine's
     * lower setpoint. Of the rest, the largest fall is 1.9 kPa (41.8 to 39.9, settling from an
     * overshoot at 40), and every other is 1.2 kPa or less. The Hold's step is 1 kPa, plus a
     * reading's jitter at each end. 2.5 kPa is past all of these, and two and a half steps. A
     * vent passes it quickly: 0.53 s at the measured 4.68 kPa/s, and the journal's vents fall
     * faster than that once they begin (36.6 to 4.7 kPa within 2 s).
     *
     * It is a floor under the rate test, not a replacement. A fall must be at least this AND
     * at least VENT_FALL_RATE_TOLERANCE of the vent rate since the stop was queued, which
     * asks for more than 2.5 kPa from 1.07 s on. So only the first second changed: a real vent
     * that begins at once is confirmed up to half a second later from a normal pull; from a
     * pull of 7-8 kPa it can take up to about 4 s longer, or end by asking the person, because
     * a vented pump's leftover reading (2-5.8 kPa) can sit within 2.5 kPa of the pull. A
     * smaller fall is never
     * evidence. It can only take VENTED away. The arrival at the target and the inference are
     * unchanged (FallThresholdTest; WiringCheck invariant 150).
     */
    public static final double VENT_FALL_EVIDENCE_MIN_KPA = 2.5;

    /**
     * The real S1 test: not "was the write accepted" (ventConfirmed/ventState above)
     * but "did the pump actually vent" — read from telemetry, the only direct
     * physical evidence this protocol offers. A genuine vent has a signature:
     * pressure FALLS, at roughly the measured rate (`kpaPerSec`, VENT_RATE in
     * SessionActivity). Pressure that stays flat/high after a stop is positive
     * evidence the vent did NOT happen — that is exactly the dangerous case S1
     * exists to catch, and it is indistinguishable from success under a
     * write-success-only test.
     *
     * `startKpa` is the pressure immediately before the stop was issued, or
     * Double.NaN when unknown (no recent reading) — without a baseline a FALL can
     * never be measured, so only the "already at or below target" test can fire in
     * that case; fabricating a baseline would be exactly the "recorded what you'd
     * like to have happened" dishonesty this app tries to avoid elsewhere.
     * `targetKpa` is what "vented" means reaching (0 for a full stop, a release
     * target for the release gate). `kpa`/`noReading`/`elapsedMs` are PARALLEL
     * arrays of every sample observed since the stop was issued, in order,
     * `elapsedMs` measured from the instant of the stop. "Observed since" means ARRIVED
     * after the stop's write completed, not handled after it: the vent watch goes through
     * {@link #ventWatchResult}, which makes that split from arrival stamps (V1). `observedForMs` is the
     * total wall-clock time watched so far (independent of how many samples
     * actually arrived — a link that has gone silent still needs the window to
     * elapse before giving up). `windowMs` bounds how long to wait for evidence
     * before reporting UNCONFIRMED rather than continuing to wait.
     *
     * noReading (0.0) samples are NEVER evidence of anything, in either direction —
     * Proto.Sample's own doc comment: 0.0 means NO MEASUREMENT, not atmospheric. A
     * stream of nothing but noReading samples must never be read as "fell to zero".
     */
    public static String ventResult(boolean linkWasReady, boolean txSucceeded,
                                     double startKpa, double targetKpa, double kpaPerSec,
                                     double[] kpa, boolean[] noReading, long[] elapsedMs,
                                     long observedForMs, long windowMs) {
        return ventResult(linkWasReady, txSucceeded, startKpa, targetKpa, kpaPerSec,
                          kpa, noReading, elapsedMs, observedForMs, windowMs, Double.NaN);
    }

    /**
     * THE SAME QUESTION, PLUS ONE FACT THE FRESHNESS RULE THROWS AWAY.
     *
     * `everRealKpa` is the last REAL reading the caller has ever seen, AT ANY AGE — NaN if
     * it has never seen one. It is used for exactly one thing: refusing the inference below
     * when the pump is known to have been at pressure. It NEVER measures a fall, is never a
     * baseline, and cannot make any verdict more favourable than the 10-argument form would
     * have given — it only takes VENTED_INFERRED away.
     *
     * WHY IT HAS TO BE SEPARATE FROM startKpa. Round 2's CRITICAL: a stale baseline lets
     * cuff-leak decay banked during an unobserved gap read as a fall that never happened, so
     * freshBaselineKpa refuses anything older than the caller's freshness bound. Right —
     * for the RATE branch. But the no-baseline inference below is not measuring anything; it
     * is asking "was there ever pressure in this thing?", and for THAT question a ten-second
     * old reading of 20 kPa is not stale at all. Handing it in as startKpa would re-open the
     * round-2 defect; handing it in here cannot.
     */
    public static String ventResult(boolean linkWasReady, boolean txSucceeded,
                                     double startKpa, double targetKpa, double kpaPerSec,
                                     double[] kpa, boolean[] noReading, long[] elapsedMs,
                                     long observedForMs, long windowMs, double everRealKpa) {
        if (!linkWasReady || !txSucceeded) return VENT_STATE_UNCONFIRMED;
        return ventResultInner(startKpa, targetKpa, kpaPerSec, kpa, noReading, elapsedMs,
                               observedForMs, windowMs, everRealKpa);
    }

    private static String ventResultInner(double startKpa, double targetKpa, double kpaPerSec,
                                           double[] kpa, boolean[] noReading, long[] elapsedMs,
                                           long observedForMs, long windowMs,
                                           double everRealKpa) {

        int n = kpa == null ? 0 : kpa.length;
        boolean startKnown = !Double.isNaN(startKpa);
        for (int i = 0; i < n; i++) {
            boolean unreadable = noReading != null && i < noReading.length && noReading[i];
            if (unreadable) continue;                       // never evidence, either direction
            double v = kpa[i];
            if (v <= targetKpa + VENT_FALL_NOISE_FLOOR_KPA) return VENT_STATE_VENTED;
            if (startKnown) {
                long t = (elapsedMs != null && i < elapsedMs.length) ? elapsedMs[i] : 0L;
                double fallen = startKpa - v;
                double expectedFall = kpaPerSec * (t / 1000.0);
                double required = Math.max(VENT_FALL_EVIDENCE_MIN_KPA, expectedFall * VENT_FALL_RATE_TOLERANCE);
                if (fallen >= required) return VENT_STATE_VENTED;
            }
        }
        if (ventedByInference(startKpa, kpa, noReading, elapsedMs, observedForMs, everRealKpa))
            return VENT_STATE_VENTED_INFERRED;
        return (observedForMs >= windowMs) ? VENT_STATE_UNCONFIRMED : VENT_STATE_SENT_UNVERIFIED;
    }

    /**
     * THE THIRD VERDICT'S PREDICATE — pure, and deliberately narrow.
     *
     * This pump reports 0.0 — "no measurement" (docs/protocols/zd21.md, "Telemetry") — for
     * roughly three quarters of its frames while the system is OPEN, and keeps streaming
     * telemetry at ~4.16 Hz the whole time. That is what a VENTED pump looks like on this
     * hardware. Both of ventResult()'s real-evidence branches need a REAL reading (an
     * arrival at/below target, or a fall measured against a non-NaN baseline), and
     * freshBaselineKpa() refuses a 0.0 as a baseline — so on a genuinely vented pump
     * NEITHER branch can ever fire, the watch resolves UNCONFIRMED forever and the retry
     * loop re-issues StopWork indefinitely. That was observed on a real device.
     *
     * So: a SUSTAINED, UNBROKEN run of no-measurement frames, on a link that is demonstrably
     * still delivering frames, whose last REAL reading was ALREADY LOW, is circumstantial
     * evidence of a vent. It is NOT proof, and it is never reported as
     * VENTED — callers must render it as what it is: "no pressure detected — the pump
     * reports no measurement, which is what a vented pump reports."
     *
     * What is explicitly NOT inferred:
     *   - silence (no frames at all, or frames that stopped arriving) — an absence of
     *     telemetry is an absence of evidence, and the classic sensor/link failure;
     *   - 0.0s that begin immediately after a HIGH real reading — that is a sensor
     *     dropout AT PRESSURE, the single most dangerous misreading available here;
     *   - a short burst of 0.0s (< VENT_INFER_MIN_FRAMES over < VENT_INFER_MIN_SPAN_MS),
     *     which ordinary frame loss produces routinely.
     */
    private static boolean ventedByInference(double startKpa, double[] kpa, boolean[] noReading,
                                              long[] elapsedMs, long observedForMs,
                                              double everRealKpa) {
        int n = kpa == null ? 0 : kpa.length;
        if (n == 0 || noReading == null || elapsedMs == null) return false;
        if (noReading.length < n || elapsedMs.length < n) return false;

        // The trailing unbroken run of no-measurement frames.
        int runStart = n;
        while (runStart > 0 && noReading[runStart - 1]) runStart--;
        int runLen = n - runStart;
        if (runLen < VENT_INFER_MIN_FRAMES) return false;
        if (elapsedMs[n - 1] - elapsedMs[runStart] < VENT_INFER_MIN_SPAN_MS) return false;

        // ALIVE, not merely "the last thing seen was a 0.0": every frame in the run has to
        // have arrived within a plausible inter-frame gap of the one before it, and the
        // stream has to still be current as of NOW. A link that went silent mid-run — or one
        // that delivered a burst and then stopped — proves nothing about pressure.
        for (int i = runStart + 1; i < n; i++)
            if (elapsedMs[i] - elapsedMs[i - 1] > VENT_INFER_MAX_FRAME_GAP_MS) return false;
        if (observedForMs - elapsedMs[n - 1] > VENT_INFER_MAX_FRAME_GAP_MS) return false;

        // What the pressure was doing when measurement was last actually possible.
        double lastReal = Double.NaN;
        for (int i = runStart - 1; i >= 0; i--) {
            if (noReading[i]) continue;
            lastReal = kpa[i];
            break;
        }
        boolean startKnown = !Double.isNaN(startKpa);
        if (Double.isNaN(lastReal)) {
            // Nothing real inside the window at all. A baseline that was ALREADY low
            // supports the inference; a HIGH one refuses it (a dropout at pressure).
            if (startKnown) return startKpa <= VENT_INFER_LOW_KPA;
            /* AND A STALE READING STILL ANSWERS "WAS IT EVER AT PRESSURE?".
             *
             * The branch below used to run whenever startKpa was NaN, on the stated ground
             * that a NaN there means no real reading has EVER existed. That was true of
             * every path except the one that matters most. A link-loss auto-stop fires
             * >= LINK_TIMEOUT_MS + AUTO_STOP_AFTER_MS after the last frame BY CONSTRUCTION,
             * so freshBaselineKpa refuses its baseline for staleness and the watch opens
             * with no samples at all. When the link then comes back streaming 0.0 NO
             * MEASUREMENT frames — which is what a vented pump looks like on this hardware
             * AND what a sensor dropout looks like — this fell through to "nothing was ever
             * measured" and inferred a vent on a cuff that had been at working pressure
             * seconds earlier.
             *
             * The reading that refuses it was there the whole time; it was simply not
             * allowed in, because the one number had to double as a fall baseline. It does
             * not any more. Age is irrelevant to this question: a pump measured at 20 kPa
             * at any point in this session is not a pump that "was never pressurised". */
            if (!Double.isNaN(everRealKpa)) return everRealKpa <= VENT_INFER_LOW_KPA;
            // AND NEITHER IS THERE A BASELINE, NOR ANY READING AT ALL — not in this window,
            // not in an earlier attempt of this watch (carriedBaselineKpa), and not at any
            // age anywhere in the session (`everRealKpa`, checked directly above). On this
            // hardware that
            // is a pump that was never pressurised: it streamed 0.0 — NO MEASUREMENT — from
            // the first frame to the last, on a link that is demonstrably still alive, and
            // the stop went out on a ready link (ventResult's own gate above).
            //
            // Before this, that case returned false and the watch could NEVER resolve: it
            // retried StopWork forever, ventUnevidenced() latched, and stillUnsafe() then
            // refused every subsequent run with "Something is still commanding or venting
            // the pump" — the whole app wedged by a pump with nothing in it to vent.
            //
            // It does NOT weaken the dangerous case this predicate exists to exclude: a run
            // that measured 20 kPa and then went to 0.0s has lastReal — or, across attempts,
            // a carried startKpa — of 20, and takes the branch above, which refuses.
            return true;
        }
        // THE ONLY REMAINING GROUND: the last thing actually measured was ALREADY LOW.
        //
        // Fix round 4's CRITICAL removed the second ground this used to have — "already
        // falling", i.e. startKpa - lastReal >= VENT_FALL_NOISE_FLOOR_KPA (0.3 kPa). That
        // difference has no floor under lastReal, so an 0.3 kPa dip at 20 or 30 kPa — well
        // inside ordinary regulation ripple — followed by a sensor dropout inferred a VENT
        // on a pump still holding hard. That is the single most dangerous misreading
        // available here, and it is the very case the comment above claims is excluded.
        // A fall large enough to be real evidence is already proven by ventResult()'s own
        // rate-tolerance branch, which needs no inference at all.
        return lastReal <= VENT_INFER_LOW_KPA;
    }

    /**
     * The baseline a RETRY of the vent watch should use.
     *
     * `freshKpa` is Session#freshBaselineKpa at retry time — normally the right answer, and
     * always preferred when it exists. But on the exact pump this whole inference exists
     * for, it is NaN: a vented pump reports 0.0 (no measurement), which freshBaselineKpa
     * refuses as a baseline, and the retry's own buffer then holds nothing real either.
     * With no baseline and no real reading, ventedByInference above can reach neither of
     * its grounds, so the inference was UNREACHABLE on attempts 2..N — the pump that needs
     * it most was the one that could never get it.
     *
     * So a retry falls back to what the earlier attempts DID see, most recent knowledge
     * first: the last REAL reading observed in any earlier window, then the pre-stop
     * baseline banked by the first attempt. Both are real measurements that were once
     * true; neither is fabricated, and neither flatters the pump — a pump that was last
     * really seen at 18 kPa carries 18 kPa forward and is still not inferred vented.
     *
     * V1: IT ANSWERS THE INFERENCE, AND ONLY THE INFERENCE. The watch used to hand this
     * value to ventResult as `startKpa`, which made it the RATE branch's baseline too - and
     * on a first attempt with a stale pre-stop reading the "last real reading seen" was the
     * current window's own newest one, so a rise read as a fall (19.2 then 19.9: "fallen"
     * 0.7). ventWatchResult now takes its fall baseline only from a frame that arrived
     * before the stop's write, and uses this chain - most recent real knowledge first - for
     * the inference's "was it low?" alone.
     */
    public static double carriedBaselineKpa(double freshKpa, double lastRealSeenKpa,
                                             double firstAttemptKpa) {
        if (!Double.isNaN(freshKpa)) return freshKpa;
        if (!Double.isNaN(lastRealSeenKpa)) return lastRealSeenKpa;
        return firstAttemptKpa;
    }

    /* ============================= V1 - EVIDENCE STAMPED WHERE IT ARRIVED, NOT WHERE IT WAS READ
     *
     * The watch used to stamp each frame when the UI thread handled it. PumpLink posts every
     * frame from the Bluetooth callback thread to the UI thread, so when the UI thread stalls
     * - the very thing that makes the pre-stop reading stale - the frames the pump sent during
     * the stall are handled in a burst just after the stop is queued, stamped a few
     * milliseconds after it, and look like evidence about a stop they were measured before.
     * The reverted fix 8772fb6 took the first of them as a baseline: a cuff leak's 0.4 kPa
     * over the burst met the 0.3 floor and a LOST stop read VENTED. The composition before it
     * took the window's own newest reading as the baseline when the pre-stop one was stale,
     * so 19.2 then 19.9 read as a 0.7 kPa fall, and a pump that ignored the stop and topped
     * up read VENTED too.
     *
     * So the watch now hands over every frame with the time it ARRIVED (PumpLink stamps it on
     * the callback thread) and the time the stop's write COMPLETED (stamped where it completed:
     * the stack's callback, the write timeout, the simulator's write), and this holds to:
     *   - only a frame that arrived after the write completed is evidence of anything - of a
     *     fall, of an arrival at target, or towards the inference;
     *   - the baseline a fall is measured from is the last frame that arrived at or before
     *     that instant, through the unchanged freshBaselineKpa rule (real, and no older than
     *     `freshnessMs` at the write) - never a reading from inside the window;
     *   - a rise is never a fall: nothing inside the window is ever a baseline, so nothing
     *     inside it can be measured against a later, higher one.
     * With no valid baseline the verdict falls to ventResult's own branches - an arrival at
     * target, or ventedByInference - exactly as before. The expected fall is still reckoned
     * from when the stop was QUEUED, the earliest it could have begun, which only ever asks
     * for more.
     *
     * The window counts from the write, not the queue: evidence cannot begin before the stop
     * has left the phone. VentArrivalTest walks each case.
     */

    /**
     * The vent watch's verdict for one attempt, from frames stamped where they arrived.
     *
     * `kpa`/`noReading`/`arrivedAt` are every telemetry frame the attempt holds, in the order
     * the UI thread handled them: the last frame in hand when the stop was queued, then every
     * frame handled since. `arrivedAt` is PumpLink's arrival stamp, never the time the UI
     * thread got to it. Every time here - the arrivals, `stopQueuedAt`, `stopSentAt`, `now` -
     * must come from ONE monotonic clock (SystemClock.elapsedRealtime() on the phone): a wall
     * clock stepped back between two readings reorders "before the write" and "after it", and
     * the safety review showed a lost stop read VENTED at +30 ms that way. `stopQueuedAt` is when the StopWork was handed to the link;
     * `stopSentAt` when its write completed, or 0 while that is not known - and while it is
     * not, nothing is evidence of anything. `carriedKpa` is the last real reading an EARLIER
     * attempt of the same watch saw (NaN on the first) and `everRealKpa` the session's last
     * real reading at any age; both only ever answer the inference's "was it low?" and are
     * never a baseline.
     */
    public static String ventWatchResult(boolean linkWasReady, boolean txSucceeded,
                                         double targetKpa, double kpaPerSec,
                                         long stopQueuedAt, long stopSentAt,
                                         double[] kpa, boolean[] noReading, long[] arrivedAt,
                                         long now, long windowMs, long freshnessMs,
                                         double carriedKpa, double everRealKpa) {
        if (!linkWasReady || !txSucceeded) return VENT_STATE_UNCONFIRMED;
        // The write has not been reported gone out: whatever the frames say, they cannot be
        // about it. A write dropped before it left the phone never reports, and is reported
        // UNCONFIRMED here when the window runs out, as it always was. And a completion time
        // from before the stop was even queued is not this stop's (the safety review's finding
        // 3: a late write-done for the previous frame retired the StopWork with that frame's
        // time, and frames from before the stop counted as evidence). It is treated as no
        // report at all.
        if (stopSentAt <= 0 || stopSentAt < stopQueuedAt)
            return (now - stopQueuedAt >= windowMs) ? VENT_STATE_UNCONFIRMED
                                                    : VENT_STATE_SENT_UNVERIFIED;

        int n = kpa == null ? 0 : kpa.length;
        if (noReading == null || arrivedAt == null || noReading.length < n || arrivedAt.length < n)
            n = 0;                                          // malformed: no frame is evidence
        int before = -1, after = 0;
        double lastRealBefore = Double.NaN;
        long lastRealBeforeAt = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (arrivedAt[i] > stopSentAt) { after++; continue; }
            if (before < 0 || arrivedAt[i] >= arrivedAt[before]) before = i;
            if (!noReading[i] && arrivedAt[i] >= lastRealBeforeAt) {
                lastRealBefore = kpa[i];
                lastRealBeforeAt = arrivedAt[i];
            }
        }
        // THE BASELINE: the last frame that arrived before the write completed, if it is real
        // and fresh at that instant - the same rule, and the same bound, as ever.
        double baseline = before < 0 ? Double.NaN
            : freshBaselineKpa(kpa[before], noReading[before], arrivedAt[before], stopSentAt,
                               freshnessMs);
        // THE EVIDENCE: only what arrived after it, on the stop's own clock.
        double[] k = new double[after];
        boolean[] r = new boolean[after];
        long[] t = new long[after];
        for (int i = 0, j = 0; i < n; i++) {
            if (arrivedAt[i] <= stopSentAt) continue;
            k[j] = kpa[i]; r[j] = noReading[i]; t[j] = arrivedAt[i] - stopQueuedAt; j++;
        }
        // What the pump was last really measured at, most recent knowledge first - for the
        // inference only, which consults it when the window itself holds nothing real.
        double prior = carriedBaselineKpa(lastRealBefore, carriedKpa, everRealKpa);
        long sendDelay = Math.max(0L, stopSentAt - stopQueuedAt);
        return ventResultInner(baseline, targetKpa, kpaPerSec, k, r, t,
                               now - stopQueuedAt, windowMs + sendDelay, prior);
    }

    /**
     * The pressure to use as ventResult()'s `startKpa` baseline — the most recent
     * telemetry reading, but ONLY if it is fresh enough to actually represent "the
     * pressure immediately before the stop was issued" (ventResult()'s own
     * contract). Returns Double.NaN when there is no reading yet, the reading was
     * itself noReading (0.0 — never a baseline, same rule as everywhere else), or
     * it is older than `freshnessMs`.
     *
     * Fix round 2's CRITICAL finding: an unbounded-stale baseline lets ordinary
     * cuff-leak decay accumulated during a long silence (the link-loss auto-stop
     * path is >= LINK_TIMEOUT_MS + AUTO_STOP_AFTER_MS stale BY CONSTRUCTION) be
     * misattributed to the WATCHED period once telemetry resumes — enough leak
     * decay can then clear ventResult()'s rate-tolerance threshold on its own,
     * falsely reading as VENTED while the pump may still be holding. ventResult()
     * itself is trusted to do the right thing WITH a baseline; this function is
     * what stops a caller from ever handing it a stale one. `freshnessMs` is
     * supplied by the caller so this reuses whatever bound the rest of the screen
     * already trusts (SessionActivity's TELEMETRY_FRESH_MS, the same freshness
     * check RelTick uses for its real-vs-modelled readout) rather than a second,
     * independent one that could quietly disagree.
     */
    public static double freshBaselineKpa(double lastKpa, boolean lastNoReading,
                                           long lastSampleAt, long now, long freshnessMs) {
        if (lastSampleAt <= 0 || lastNoReading) return Double.NaN;
        if (now - lastSampleAt > freshnessMs) return Double.NaN;
        return lastKpa;
    }

    /* --------------------------------------------------- stage-live / phase (#25/#26) */

    /** Which stage is "live" — the one actually running — given each stage's total
     *  commanded duration in ms (stage order) and how far into the routine playback
     *  has gotten. The single source of truth the rail, the current-set readout and
     *  the progress fraction all share (defect #25: the prototype hardcoded index 1,
     *  so nothing lit up once play moved past stage 1). Clamps to the last stage once
     *  elapsedMs reaches or passes the total, and to the first at or before zero.
     *  Returns -1 only when there are no stages at all. A zero-length stage (every
     *  set removed or dangling) can never be reported live — elapsedMs cannot fall
     *  strictly inside a range of width zero. */
    public static int liveStageIndex(long[] stageDurMs, long elapsedMs) {
        if (stageDurMs == null || stageDurMs.length == 0) return -1;
        if (elapsedMs <= 0) return 0;
        long acc = 0;
        for (int i = 0; i < stageDurMs.length; i++) {
            acc += Math.max(0L, stageDurMs[i]);
            if (elapsedMs < acc) return i;
        }
        return stageDurMs.length - 1;
    }

    /** Fraction (0..1) of the live stage's OWN duration that has elapsed — the rail's
     *  progress fill. Derived from the exact same stageDurMs/elapsedMs liveStageIndex
     *  used, never a literal (the prototype's fill was a hardcoded "51%"). */
    public static double liveStageFraction(long[] stageDurMs, long elapsedMs, int liveIdx) {
        if (stageDurMs == null || liveIdx < 0 || liveIdx >= stageDurMs.length) return 0.0;
        long before = 0;
        for (int i = 0; i < liveIdx; i++) before += Math.max(0L, stageDurMs[i]);
        long dur = Math.max(1L, stageDurMs[liveIdx]);
        double f = (elapsedMs - before) / (double) dur;
        return f < 0 ? 0.0 : (f > 1 ? 1.0 : f);
    }

    /** Where in the CURRENT preset's upper-hold/lower-hold cycle `elapsedInPresetMs`
     *  (wall-clock since that preset started playing) falls — wraps at the cycle
     *  length (upperHoldS + lowerHoldS) instead of growing without bound. Defect #26:
     *  the prototype's hold-phase elapsed was a literal 18 s that could exceed the
     *  phase's own length whenever the set's own hold was shorter than 18 s. Always
     *  returns a value in [0, phaseLenS] for the phase currently in effect. */
    public static long phaseElapsedMs(long elapsedInPresetMs, int upperHoldS, int lowerHoldS) {
        long upperMs = (long) Math.max(0, upperHoldS) * 1000L;
        long cycleMs = upperMs + (long) Math.max(0, lowerHoldS) * 1000L;
        if (cycleMs <= 0) return 0;
        long inCycle = ((elapsedInPresetMs % cycleMs) + cycleMs) % cycleMs;  // never negative
        return inCycle < upperMs ? inCycle : inCycle - upperMs;
    }

    /** True while the cycle above is in its UPPER (hold-at-target) phase, false while
     *  it is in the lower (drop) phase — drives the run screen's HOLD/DROP label. */
    public static boolean isUpperPhase(long elapsedInPresetMs, int upperHoldS, int lowerHoldS) {
        long upperMs = (long) Math.max(0, upperHoldS) * 1000L;
        long cycleMs = upperMs + (long) Math.max(0, lowerHoldS) * 1000L;
        if (cycleMs <= 0) return true;
        long inCycle = ((elapsedInPresetMs % cycleMs) + cycleMs) % cycleMs;
        return inCycle < upperMs;
    }

    /* ------------------------------------------------------------- link loss (S2) */

    /**
     * True once `timeoutMs` has passed since `since` — the trigger to stop trusting
     * wall-clock progress and freeze the playhead (S2).
     *
     * `since` is the instant silence is measured FROM, which is NOT simply "when the
     * last sample arrived": for a run that has not had its first sample yet it is the
     * instant the RUN started. Callers must obtain it from silenceReference() rather
     * than passing a raw lastSampleAt — see that method for the failure this prevents.
     * A `since` of 0 means there is no reference instant at all (no run in progress and
     * no sample ever): nothing to measure silence from, so nothing to report as lost.
     * That is a statement about this function's inputs, NOT a claim that a run without
     * telemetry is healthy.
     */
    public static boolean linkLost(long now, long since, long timeoutMs) {
        if (since <= 0) return false;
        return (now - since) >= timeoutMs;
    }

    /**
     * The instant a run's silence is measured from: the last sample if one has arrived
     * this run, otherwise the run's own start.
     *
     * Fix round 1's CRITICAL finding. beginRun() zeroes lastSampleAt, so a run whose
     * telemetry NEVER STARTS — the link connected-but-silent from the first instant, S2's
     * exact failure mode — had no reference instant at all: linkLost() saw 0, reported
     * "not lost" forever, the freeze never latched, the link-lost screen never appeared,
     * and the AUTO_STOP_AFTER_MS auto-stop was structurally disarmed for the whole run.
     * Playback advances on postDelayed alone, so the plan walked to its end and the
     * session was filed as a COMPLETED one — full wall-clock duration, a streak day and a
     * cadence advance — for a run in which zero telemetry was ever observed, while the
     * pump was commanded up to the ceiling on a person. An identical physical failure
     * that happened to deliver ONE frame after beginRun() was caught, stopped and filed
     * as an abort; one frame earlier and it was filed as a success.
     *
     * Falling back to the run start arms the timeout from the moment the routine begins,
     * so "never spoke at all" and "stopped speaking" are treated as the same hazard —
     * because they are the same hazard.
     */
    public static long silenceReference(long runStartedAt, long lastSampleAt) {
        return lastSampleAt > 0 ? lastSampleAt : runStartedAt;
    }

    /** The elapsed-into-the-run value the screen should actually display: wall-clock
     *  while telemetry is arriving, but FROZEN at whatever it was the instant the
     *  link was lost. Never advances on wall-clock alone once there is no telemetry
     *  to confirm the pump is still doing what was commanded (S2). */
    public static long frozenElapsedMs(long rawElapsedMs, long elapsedAtLossMs, boolean lost) {
        return lost ? elapsedAtLossMs : rawElapsedMs;
    }

    /** The deviation (actual minus commanded, in the same vacuum-depth kPa terms
     *  Model.Fmt.p takes) the run screen may safely report, or null when it must not
     *  report one at all. Null both while the link is lost AND whenever the most
     *  recent sample was itself a "no reading" (0.0) — either way there is no real
     *  actual pressure to compare against commanded, and substituting 0 would compute
     *  a shortfall of the ENTIRE commanded pressure: a dropped link would otherwise
     *  read as ~100% shortfall, exactly the wrong diagnosis (S2). */
    public static Double deviationKpaOrNull(boolean linkLost, boolean lastSampleNoReading,
                                             double actualKpa, double commandedKpa) {
        if (linkLost || lastSampleNoReading) return null;
        return Double.valueOf(actualKpa - commandedKpa);
    }

    /** Per-run tracking: when the run started, when telemetry last arrived, and
     *  whether the gap has tripped the S2 freeze. Deliberately owned by a Session
     *  INSTANCE, not the static helpers above, so SessionActivity has one object to
     *  ask "what elapsed time should I show" without re-deriving the freeze/latch
     *  bookkeeping itself on every tick (the exact kind of duplicated derivation that
     *  produced defect #25 elsewhere). Every timestamp is wall-clock, supplied by the
     *  caller — never a tick count — so this stays deterministic under test. */
    private long runStartedAt;

    /** When the run began, or 0 when none has. Read by SessionActivity's run snapshot (S2)
     *  to decide whether an unfinished run is recent enough to still be worth offering. */
    public long runStartedAtOrZero() { return runTracking ? runStartedAt : 0L; }
    private long lastSampleAt;
    private boolean linkWasLost;
    private long elapsedAtLoss;

    /** Starts tracking a live run from `now`, and resets everything the run is going
     *  to accumulate — a new run must never inherit the previous run's peak, dose or
     *  sample counts (defect #02's shape: stale progress surviving into a session that
     *  did not produce it). */
    public void beginRun(long now) {
        runStartedAt = now;
        lastSampleAt = 0;
        linkWasLost = false;
        elapsedAtLoss = 0;
        runTracking = true;
        observedPeak = 0;
        sawRealSample = false;
        doseKpaS = 0;
        segmentOpen = false;
        segAt = 0;
        segKpa = 0;
        realSamples = 0;
        noReadingSamples = 0;
        // Task 5 (T14+): the hold-efficiency recording is per-run exactly like the dose/
        // peak accumulators above, and clears with them — see noteHoldFrame's own doc for
        // why nothing shorter-lived than a whole run may hold onto these.
        holdTs.clear();
        holdMeasuredKpa.clear();
        holdNoReading.clear();
        holdCommandedKpa.clear();
        holdPhase.clear();
        holdStageIdx.clear();
        holdDropPhase.clear();
        holdCommanding.clear();
        // AND THE OUT-OF-NET MARKS, which were the one list left out: from the second run in
        // a process the mask began with the first run's marks and no longer lined up with
        // the frames, so the net kept or dropped the wrong seconds (SecondRunNetTest).
        holdFatigueStage.clear();
    }

    /**
     * Ends the run: further samples are no longer attributed to it, and elapsedMs()
     * stops advancing. What the run ACCUMULATED (peak, dose, counts) is deliberately
     * left intact so the summary can still be redrawn afterwards — beginRun() is the
     * only thing that clears it.
     *
     * Calling this matters for more than tidiness: without it, runStartedAt still
     * points at the finished run, so a LATER attempt that is abandoned before its
     * routine ever starts (aborting during the seal check, which runs before
     * beginRun()) would report the previous run's elapsed time, growing on wall clock,
     * as its own. That is exactly defect #02 — History filing a duration for a session
     * that delivered nothing.
     */
    public void endRun() {
        runTracking = false;
        runStartedAt = 0;
        linkWasLost = false;
        elapsedAtLoss = 0;
        segmentOpen = false;
    }

    /** Whether a run is currently being tracked — true between beginRun() and
     *  endRun(). The ONE gate the summary uses for "did the routine actually get
     *  underway": elapsed, delivered presets, dose and peak all key off it together,
     *  rather than each screen deciding separately and disagreeing (defect #02). */
    public boolean isRunTracking() { return runTracking; }

    private boolean runTracking;

    /**
     * Call on every telemetry sample received during a run: keeps the link-loss
     * bookkeeping current (the run screen's only signal that the link is ACTUALLY
     * delivering data, independent of the BLE layer's own connected/disconnected flag
     * — a connection that reports "connected" but has gone silent is exactly the
     * failure S2 exists for; a sample resuming is what "reconnected" means here) AND
     * accumulates what the session is delivering.
     *
     * `maxGapMs` is supplied by the caller rather than kept as a second constant in
     * here, so this reuses the same silence bound the rest of the run screen already
     * trusts (SessionActivity's LINK_TIMEOUT_MS) instead of a private one that could
     * quietly disagree with it — the same reasoning as freshBaselineKpa()'s
     * freshnessMs.
     *
     * THE 0.0 RULE (a safety rule, not a formatting one — Proto.Sample's own doc
     * comment: 0.0 means NO MEASUREMENT, not atmospheric pressure):
     *   - a noReading sample is never a data point: it can never be the peak, and it
     *     is never integrated;
     *   - it CLOSES the current dose segment. When a gap of no-readings sits between
     *     two real samples the interval is NOT bridged — bridging would invent
     *     pressure the pump never reported, over the exact window where the app knows
     *     least about what the pump was doing. The segment before the gap is closed
     *     and a new one opens at the next real sample.
     * A silence longer than `maxGapMs` breaks the segment the same way and for the
     * same reason: nothing was observed across it.
     */
    public void noteSample(long now, double kpa, boolean noReading, long maxGapMs) {
        noteSample(now, kpa, noReading, maxGapMs, false);
    }

    /**
     * THE SAME, TOLD WHETHER THE SAMPLE FELL IN THE DROP HALF OF A CYCLE (owner, 2026-09-30).
     * A drop may now sit above DOSE_FLOOR_KPA (RunEdit#dropTopKpa: up to 1.0 inHg under the
     * pull), and the dose would have integrated the seconds between holds as work at that
     * pressure - it never asked the phase, because a drop under the floor added nothing by
     * itself. So the drop is excluded BY PHASE, as net's is (noteHoldFrame's `dropPhase`, the
     * same split, the interval a sample closes): an interval that ends IN the drop half adds no
     * dose, whatever it read; an interval that ends in the hold counts exactly as before, its
     * opening sample a drop one or not. The segment stays open - the sample is a real reading,
     * and the peak still sees it. The caller marks the drop only where the drop is set above
     * the floor (TupClock#doseLeavesOut), so a run whose drops sit at or under it is counted to
     * the bit as it always was.
     */
    public void noteSample(long now, double kpa, boolean noReading, long maxGapMs,
                           boolean dropPhase) {
        lastSampleAt = now;
        linkWasLost = false;
        if (!runTracking) return;           // not part of any run — nothing to attribute it to
        if (noReading) {
            noReadingSamples++;
            segmentOpen = false;            // never bridge a no-reading gap
            return;
        }
        realSamples++;
        if (!sawRealSample || kpa > observedPeak) { observedPeak = kpa; sawRealSample = true; }
        if (segmentOpen) {
            long dt = now - segAt;
            if (dt > 0 && dt <= maxGapMs && !dropPhase)
                doseKpaS += 0.5 * (excess(segKpa) + excess(kpa)) * (dt / 1000.0);
        }
        segmentOpen = true;
        segAt = now;
        segKpa = kpa;
    }

    /* ------------------------------------------------ delivered telemetry (Task 11) */

    /** Dose counts only pressure ABOVE this floor — the prototype's ∫(P−10)dt, and the
     *  figure the summary labels "above 10 kPa". Below it the pump is barely engaged;
     *  counting that time as dose would let a long idle stretch look like work. */
    public static final double DOSE_FLOOR_KPA = 10.0;

    private double observedPeak;
    private boolean sawRealSample;
    private double doseKpaS;
    private boolean segmentOpen;
    private long segAt;
    private double segKpa;
    private int realSamples, noReadingSamples;

    private static double excess(double kpa) {
        double e = kpa - DOSE_FLOOR_KPA;
        return e > 0 ? e : 0;
    }

    /** The deepest vacuum telemetry ACTUALLY reported during the run, or null when the
     *  run produced no real reading at all — never 0.0, which would be a claim that
     *  something was measured and came out at atmospheric. The summary shows an em dash
     *  for null: an honest "not measured" beats a number that was never observed
     *  (defect #07 — the prototype printed the routine's configured peak TARGET under a
     *  card headed "Delivered"). */
    public Double observedPeakKpa() {
        return sawRealSample ? Double.valueOf(observedPeak) : null;
    }

    /** The dose actually delivered, in kPa·s above DOSE_FLOOR_KPA — integrated over
     *  OBSERVED pressure across OBSERVED time (trapezoid between consecutive real
     *  samples), with no-reading gaps and link-silence gaps excluded entirely.
     *  Defect #08: the prototype prorated the whole routine's planned dose by the
     *  elapsed-time fraction, which assumes pressure is uniform across a routine
     *  deliberately built warm-up → work → cool-down, and so systematically
     *  over-credited a stop during the warm-up. */
    public double deliveredDoseKpaS() { return doseKpaS; }

    /** How many samples carried a real reading, and how many reported none (0.0).
     *  The summary reports the second number rather than hiding it: a run whose
     *  telemetry was mostly dropouts delivered a dose measured across much less time
     *  than it looks. */
    public int realSampleCount() { return realSamples; }
    public int noReadingSampleCount() { return noReadingSamples; }

    /**
     * The same dose arithmetic over a series already in hand, for callers (and the
     * self-test) holding parallel arrays rather than a live stream. Deliberately
     * implemented by REPLAYING the arrays through the live accumulator above rather
     * than as a second loop that could drift away from it — one derivation, asserted
     * on directly.
     */
    public static double deliveredDoseKpaS(long[] tsMs, double[] kpa, boolean[] noReading,
                                            long maxGapMs) {
        Session s = replay(tsMs, kpa, noReading, maxGapMs);
        return s == null ? 0.0 : s.deliveredDoseKpaS();
    }

    /** observedPeakKpa() over a series already in hand — same replay, same rules. */
    public static Double observedPeakKpa(long[] tsMs, double[] kpa, boolean[] noReading,
                                          long maxGapMs) {
        Session s = replay(tsMs, kpa, noReading, maxGapMs);
        return s == null ? null : s.observedPeakKpa();
    }

    private static Session replay(long[] tsMs, double[] kpa, boolean[] noReading, long maxGapMs) {
        if (tsMs == null || kpa == null || tsMs.length == 0) return null;
        Session s = new Session();
        s.beginRun(tsMs[0]);
        for (int i = 0; i < tsMs.length && i < kpa.length; i++)
            s.noteSample(tsMs[i], kpa[i],
                         noReading != null && i < noReading.length && noReading[i], maxGapMs);
        return s;
    }

    /* ============================== Stage E — task 5 (T14+) ====================
     *
     * HOLD EFFICIENCY % — dist/round7-options.html's T14+ amendment, quoted in full in
     * the doc comments below at the point each clause is implemented. "Time at target:
     * counted seconds within band ÷ eligible hold seconds", TIME-WEIGHTED (each frame
     * contributes its real timestamp gap, not a flat per-frame count) so BLE drops
     * cannot skew it, over HOLD-PHASE-ONLY eligible time, shown only past a 60 s floor.
     *
     * NO EXISTING STRUCTURE RETAINS THIS. Trace.Ring is a rolling ~30 s window (WINDOW =
     * 120 samples at the device's ~4 Hz), cleared per run; AsRun records commanded-only,
     * on step-change events, never per-frame measured telemetry; Session#noteSample
     * above accumulates scalars (peak, integrated dose) and keeps no history at all. So
     * this is new: a per-run, in-memory-only recording (noteHoldFrame, below — the same
     * shape SessionActivity's own seal-check/assessment/hardware-validation buffers
     * already use: parallel ArrayLists, filled live, converted to arrays and consumed
     * once, never persisted raw), plus the pure calculation over it.
     *
     * WHAT SessionActivity RECORDS, PER SAMPLE, WHILE session.isRunTracking(): the
     * timestamp, the measured kPa (ignored when noReading), the commanded kPa THIS
     * INSTANT (the same commandedNowKpa() derivation the live trace's dashed reference
     * line already uses — one derivation, never a second that could disagree), which of
     * HOLD/RAMP/REST the live preset is (Model.Preset#rest, else the owning Model.Set's
     * `ramp` flag — AsRun#blocks already classifies a block's kind the identical way,
     * `set.ramp ? RAMP : FIXED`, so this is not a new classification, just the same one
     * read live instead of after the fact), and the stage index (Model.Preset#stageIdx)
     * — recorded per-frame rather than reconstructed later, per the task brief's own
     * steer, because a live classification can never disagree with what actually played.
     *
     * PHASE STAYS THE PRESET'S, NOT THE OVERRIDE'S. A live Adjust (RunEdit.carryActiveAt)
     * can carry a DIFFERENT commanded VALUE into the preset now playing — and that
     * carried value is exactly what is compared for in-band, mirroring commandedNowKpa()
     * — but it does not change what KIND of preset is live: an override on a hold preset
     * is still a hold, and the pull-up grace (below) still resets on ITS OWN terms, from
     * whenever the commanded VALUE actually changed, override or not.
     */

    /** One frame's classification — which of the three the live preset was when the
     *  sample arrived. HOLD is the only one that can ever be ELIGIBLE; RAMP (the target
     *  is moving) and REST (nothing is commanded) are excluded outright, per T14+'s
     *  "Excluded: ramp segments while the target is moving, rest stages". */
    public static final int HOLD_PHASE_HOLD = 0, HOLD_PHASE_RAMP = 1, HOLD_PHASE_REST = 2;

    /** T14+, verbatim: "hold phases with commanded ≥ 5 kPa" is the eligibility floor —
     *  a hold configured below it (a gentle standardisation-style target) never counts,
     *  in or out of band. */
    public static final double HOLD_EFF_MIN_COMMANDED_KPA = 5.0;

    /** T14+'s in-band tolerance, verbatim: "|measured − commanded| ≤ max(0.5 kPa, 5% of
     *  commanded) — absolute floor for low targets, relative band for high ones." At
     *  commanded ≤ 10 kPa the 0.5 kPa floor is the binding term (5% of 10 is exactly
     *  0.5); above it the 5% relative band takes over. */
    public static final double HOLD_EFF_BAND_FLOOR_KPA = 0.5;
    public static final double HOLD_EFF_BAND_REL_FRAC = 0.05;

    /**
     * THE ONE DEFINITION OF "AT PRESSURE" (Stage K, F13).
     *
     * Four things in this app need to ask whether the pump is where it was asked to be:
     * hold efficiency (which already had this band), Net TUP's own accrual, the guided
     * start's attachment gate, and the standardised hold's dwell gate. Before this they
     * would each have grown their own answer — the app has already been through that with
     * "did I train that day", and once three versions exist the screens start contradicting
     * each other over the same reading.
     *
     * The band is T14+'s, verbatim and unchanged: |measured − target| within
     * max(0.5 kPa, 5% of target). Below roughly 10 kPa the absolute floor binds, above it
     * the relative term does — a real pump oscillates by a little at any target, and
     * proportionally more at a high one.
     *
     * ONE-SIDED HERE, deliberately. Hold efficiency asks "is it AT the value", so it
     * measures both directions. Everything else asks "is it AT LEAST this deep", where
     * overshooting is not a failure to be at pressure — it is being more than at it.
     */
    public static double bandKpa(double targetKpa) {
        double rel = Math.abs(targetKpa) * HOLD_EFF_BAND_REL_FRAC;
        return rel > HOLD_EFF_BAND_FLOOR_KPA ? rel : HOLD_EFF_BAND_FLOOR_KPA;
    }

    /** True when `measuredKpa` counts as having REACHED `targetKpa`, tolerating the band. */
    public static boolean atOrAbove(double measuredKpa, double targetKpa) {
        return measuredKpa >= targetKpa - bandKpa(targetKpa);
    }

    /** T14+, verbatim: "the first 3 s after each new target (pull-up grace)" — excluded
     *  regardless of how the target got there (a new preset, or a live Adjust carrying a
     *  different value into the one already playing). */
    public static final long HOLD_EFF_GRACE_MS = 3000L;

    /** T14+, verbatim: "shown only when ≥ 60 s of eligible hold time exists; otherwise
     *  '—'". The guard is on ELIGIBLE time (the percentage's own denominator), never on
     *  in-band time — a session that held perfectly on target for 10 s is still under
     *  the floor and still shows the dash, not a misleadingly confident 100%. */
    public static final double HOLD_EFF_MIN_ELIGIBLE_SEC = 60.0;

    /** T14+'s in-band test, exactly: the absolute floor for a low commanded target, the
     *  5% relative band for a high one, whichever is WIDER — never both, never neither.
     *  Inclusive at the boundary ("≤"): a measurement sitting exactly on the tolerance
     *  is in band, matching holdComplete()'s own "observed at exactly the boundary
     *  counts" convention elsewhere in this class. */
    public static boolean holdInBandKpa(double measuredKpa, double commandedKpa) {
        double tol = Math.max(HOLD_EFF_BAND_FLOOR_KPA, HOLD_EFF_BAND_REL_FRAC * commandedKpa);
        return Math.abs(measuredKpa - commandedKpa) <= tol;
    }

    /** {eligibleMs, inBandMs} accumulator, private to this section — see holdMs() below
     *  for why both the whole-session and the per-stage figures share one instance of
     *  this rather than two independently-written totals. */
    private static final class HoldMs {
        double eligibleMs, inBandMs;
    }

    /**
     * THE ONE PASS both holdEfficiencyPct() and holdEfficiencyPctForStage() below run —
     * never two independent walks of the same frames that could quietly disagree.
     *
     * TIME-WEIGHTED, T14+ verbatim: "each telemetry frame contributes its real timestamp
     * gap [since the previous frame], not a frame count." Frame 0 has no "previous
     * frame" and contributes nothing on its own; from i=1 on, the gap ts[i]-ts[i-1] is
     * judged by FRAME i's own recorded state (what the frame that just closed the gap
     * reports), and — when that state is eligible — the WHOLE gap counts, however long
     * it was. This is deliberate, not an approximation: T14+'s own justification for
     * time-weighting is "so BLE drops (irregular sampling) can't skew the percentage",
     * i.e. a long silence followed by one honest frame must count for exactly what it
     * was, not be discounted for having arrived as a single frame.
     *
     * PULL-UP GRACE is tracked across the WHOLE array regardless of `wantStage` — target-
     * change detection (a per-frame commanded value that differs from the previous
     * frame's) has to see every frame in chronological order to know when a target
     * genuinely started, so a per-stage-only pass could not restart it correctly at an
     * arbitrary stage boundary. Comparing commanded kPa with `!=` is safe here: every
     * value on the wire is Model.Preset#up (an int) or RunEdit's carried int, never an
     * arithmetic result that could drift by a floating-point epsilon.
     *
     * `wantStage` < 0 sums every frame; ≥ 0 sums only frames whose recorded stageIdx
     * matches — the SAME per-frame stageIdx AsRun.Block#stageIdx is itself stamped from
     * (Model.Preset#stageIdx, via Model#plan's one walk), so a block's own stage index is
     * always the right key to ask this for.
     */
    private static HoldMs holdMs(long[] tsMs, double[] measuredKpa, boolean[] noReading,
                                  double[] commandedKpa, int[] phase, int[] stageIdx,
                                  int wantStage) {
        HoldMs r = new HoldMs();
        if (tsMs == null || commandedKpa == null || tsMs.length < 2) return r;
        int n = tsMs.length;
        double targetKpa = commandedKpa[0];
        long targetStartTs = tsMs[0];
        for (int i = 1; i < n; i++) {
            if (commandedKpa[i] != targetKpa) {
                targetKpa = commandedKpa[i];
                targetStartTs = tsMs[i];
            }
            long gap = tsMs[i] - tsMs[i - 1];
            if (gap <= 0) continue;             // a clock going backwards contributes nothing
            if (wantStage >= 0
                && (stageIdx == null || i >= stageIdx.length || stageIdx[i] != wantStage))
                continue;
            boolean nr = noReading != null && i < noReading.length && noReading[i];
            int ph = phase != null && i < phase.length ? phase[i] : HOLD_PHASE_HOLD;
            boolean eligible = !nr && ph == HOLD_PHASE_HOLD
                             && commandedKpa[i] >= HOLD_EFF_MIN_COMMANDED_KPA
                             && (tsMs[i] - targetStartTs) >= HOLD_EFF_GRACE_MS;
            if (!eligible) continue;
            r.eligibleMs += gap;
            if (holdInBandKpa(measuredKpa[i], commandedKpa[i])) r.inBandMs += gap;
        }
        return r;
    }

    /** eligibleMs → the displayable percentage, or null (T14+'s "—") when under the 60 s
     *  floor. Rounded to a whole number here, at the ONLY point the raw frames this is
     *  derived from are ever in hand — the per-frame recording is not persisted (see the
     *  class doc above), so unlike a live-recomputed figure (Presets, say) there is no
     *  later moment to round a stored fraction from. */
    private static Double holdEffPctFromMs(double eligibleMs, double inBandMs) {
        if (eligibleMs < HOLD_EFF_MIN_ELIGIBLE_SEC * 1000.0) return null;
        return Double.valueOf(Math.round(100.0 * inBandMs / eligibleMs));
    }

    /**
     * THE T14+ FIGURE for a whole session, over a series already in hand — the self-test
     * form, and what holdEfficiencyPct() (the live instance method, below) replays
     * through. `commandedKpa[i]`/`phase[i]`/pull-up-grace are all as recorded by
     * noteHoldFrame(); see holdMs() for the one pass that turns them into a percentage.
     */
    public static Double holdEfficiencyPct(long[] tsMs, double[] measuredKpa,
                                            boolean[] noReading, double[] commandedKpa,
                                            int[] phase) {
        HoldMs m = holdMs(tsMs, measuredKpa, noReading, commandedKpa, phase, null, -1);
        return holdEffPctFromMs(m.eligibleMs, m.inBandMs);
    }

    /** The SAME figure, narrowed to one stage's own frames (by Model.Preset#stageIdx) —
     *  T14+'s "also per-stage in the expanded view". Grace/target tracking still runs
     *  over every frame in the session (see holdMs()'s own doc); only the SUMMING is
     *  narrowed. */
    public static Double holdEfficiencyPctForStage(long[] tsMs, double[] measuredKpa,
                                                    boolean[] noReading, double[] commandedKpa,
                                                    int[] phase, int[] stageIdx, int stage) {
        HoldMs m = holdMs(tsMs, measuredKpa, noReading, commandedKpa, phase, stageIdx, stage);
        return holdEffPctFromMs(m.eligibleMs, m.inBandMs);
    }

    /** TEST-ONLY: {eligibleMs, inBandMs}, unrounded and UNGATED by the 60 s floor —
     *  holdEfficiencyPct() itself hides both behind a rounded percentage-or-null, which
     *  makes a small hand-built fixture (a few frames, a few seconds) unable to prove
     *  anything past "under 60 s, so null" no matter what it is testing. SelfTest is in
     *  this package and calls straight through to holdMs() via this, so each exclusion
     *  rule (ramp, rest, no-reading, the 5 kPa floor, the 3 s grace) can be pinned in
     *  isolation, on its own millisecond total, without every fixture separately having
     *  to clear the guard first. Nothing outside the self-test may reach for this. */
    static double[] holdMsForTest(long[] tsMs, double[] measuredKpa, boolean[] noReading,
                                   double[] commandedKpa, int[] phase) {
        HoldMs m = holdMs(tsMs, measuredKpa, noReading, commandedKpa, phase, null, -1);
        return new double[]{ m.eligibleMs, m.inBandMs };
    }

    /* ============================== Stage H — task 2 ============================
     *
     * NET / GROSS TIME UNDER PRESSURE — the guide's own delivered-volume figures,
     * EXTENDING this same per-frame hold pass rather than adding a second recorder (the
     * task's own brief): the frames noteHoldFrame() already keeps (ts/measured/noReading,
     * now with fatigueStage alongside them) are walked ONE more time, with different
     * eligibility rules than holdMs()'s in-band test, by {@link #tupMs} below.
     *
     * GROSS is "real sealed time" — every frame's gap where a genuine reading exists
     * (`!noReading`), regardless of the track's floor, the live phase or a fatigue
     * marker. NET narrows that to frames whose MEASURED pressure sat at/above the
     * marked track's own level floor (a SESSION-LEVEL constant handed in by the caller —
     * unlike commandedKpa in holdMs(), the floor a Net-TUP frame is judged against never
     * moves mid-run) and whose stage is NOT the trainer's fatigue block (plan round-3:
     * "fatigue does not feed the gate metric" — GROSS still counts it; only NET excludes
     * it). Both are TIME-WEIGHTED exactly as holdMs() is, for the identical reason: a
     * BLE drop must not silently discount (or inflate) either figure.
     */

    /** {netMs, grossMs} accumulator, private to this section. */
    private static final class TupMs {
        double netMs, grossMs;
    }

    private static TupMs tupMs(long[] tsMs, double[] measuredKpa, boolean[] noReading,
                                boolean[] fatigueStage, double floorKpa, double tolKpa) {
        TupMs r = new TupMs();
        if (tsMs == null || tsMs.length < 2) return r;
        int n = tsMs.length;
        for (int i = 1; i < n; i++) {
            long gap = tsMs[i] - tsMs[i - 1];
            if (gap <= 0) continue;               // a clock going backwards counts nothing
            boolean nr = noReading != null && i < noReading.length && noReading[i];
            if (nr) continue;                     // unknown, never a failure and never gross
            r.grossMs += gap;
            boolean fatigue = fatigueStage != null && i < fatigueStage.length
                             && fatigueStage[i];
            if (fatigue) continue;                // fatigue-block frames never feed NET
            // WITHIN THE BAND, not a hard cutoff (F13). A pump tracking a commanded step
            // chart exactly is not a thing that happens; it settles a little under, breathes
            // around the target, and dips when the tissue gives. Comparing raw against the
            // floor shredded the count for a session that was, to any honest reading, at
            // pressure the whole time. atOrAbove is the same band hold efficiency has always
            // used — one definition, so the two metrics can never disagree about one frame.
            // THE TOLERANCE IS HANDED IN, not decided here. It used to be bandKpa()'s
            // 5% - the app's one definition of "is the pump where it was told to be",
            // which is a CONTROL question. Whether a second counts as delivered work is an
            // ACCOUNTING question, and it is now the user's to answer (Model#tupCountPct):
            // a person training against a mandated level is entitled to say how close to
            // it still counts. Those two questions having the same answer by default was a
            // coincidence, not a design, and every screen that asks the control question
            // still goes through atOrAbove() unchanged.
            if (measuredKpa[i] >= floorKpa - tolKpa) r.netMs += gap;
        }
        return r;
    }

    /** TEST-ONLY: {netMs, grossMs}, UNROUNDED and in MILLISECONDS — the raw pass, for the
     *  same reason {@link #holdMsForTest} exists for holdMs(): a hand-built fixture needs
     *  the ungated total, not a caller-facing seconds figure, to pin each exclusion rule
     *  in isolation. Nothing outside the self-test may reach for this. */
    static double[] tupMsForTest(long[] tsMs, double[] measuredKpa, boolean[] noReading,
                                  boolean[] fatigueStage, double floorKpa) {
        return tupMsForTest(tsMs, measuredKpa, noReading, fatigueStage, floorKpa,
                            bandKpa(floorKpa));
    }

    /** The same, with the tolerance stated - what the configurable counting threshold is
     *  pinned with. */
    static double[] tupMsForTest(long[] tsMs, double[] measuredKpa, boolean[] noReading,
                                  boolean[] fatigueStage, double floorKpa, double tolKpa) {
        TupMs m = tupMs(tsMs, measuredKpa, noReading, fatigueStage, floorKpa, tolKpa);
        return new double[]{ m.netMs, m.grossMs };
    }

    /**
     * {netTupSec, grossTupSec} for THIS run's own recording, in seconds — UNCONDITIONALLY
     * computed from whatever frames were noted, never null and never gated by a duration
     * floor the way holdEfficiencyPct() is (Net/Gross TUP carries no such floor in the
     * plan's own spec). The CALLER (SessionActivity#fileSession) decides whether to KEEP
     * this pair at all: Session stays decoupled from Model (no import of it appears
     * anywhere in this file, exactly as the class doc above states), so it has no way to
     * know whether the routine that was run carries a trainer track marker — that check,
     * and the resulting null for an unmarked/off-plan session, lives in the caller, the
     * same split noteHoldFrame's own doc describes for phase/stageIdx.
     */
    public double[] netGrossTupSec(double floorKpa) {
        return netGrossTupSec(floorKpa, bandKpa(floorKpa));
    }

    /** The same figures with an explicit tolerance below the floor - what the app passes
     *  once the user has set one. */
    public double[] netGrossTupSec(double floorKpa, double tolKpa) {
        TupMs m = tupMs(toLongArr(holdTs), toDoubleArr(holdMeasuredKpa), toBoolArr(holdNoReading),
                         orDrop(toBoolArr(holdFatigueStage), toBoolArr(holdDropPhase)),
                         floorKpa, tolKpa);
        return new double[]{ m.netMs / 1000.0, m.grossMs / 1000.0 };
    }

    /**
     * D2 - SEALED TIME AS THE TWO-HOUR STOP COUNTS IT (Plan#GROSS_CAP_SEC), in seconds.
     *
     * Gross as {@link #tupMs} counts it - every frame's gap where there is a real reading -
     * and ALSO every gap closed by a frame the run marked as commanding pressure
     * (RunEdit#commandingPressure) whatever it read, plus, while pressure is commanded
     * `commandingNow`, the time since the last frame.
     *
     * WHY. The stop counted real readings only, so a sensor reporting 0.0 - "no
     * measurement" - moved neither the set clock nor the stop: with the cuff under pressure
     * and the sensor dead, only STOP ended the run (the final safety review). The app cannot
     * tell a dead sensor from a vented cuff, so while it has told the pump to hold pressure
     * it assumes the pressure is there - a 0.0 frame, or no frame at all, counts exactly as a
     * real reading would. Where the cuff was vented on purpose (a rest, an assessment's vent
     * wait) the caller does not mark the frame, and a 0.0 there counts for nothing, as before.
     *
     * NOT A FIGURE ANYTHING FILES. {@link #netGrossTupSec} - what the summary and the record
     * say - is unchanged and still counts only what was measured; this answers one question,
     * "has this run been sealed two hours", and it may only answer it sooner.
     */
    public double sealedForCapSec(long now, boolean commandingNow) {
        long[] ts = toLongArr(holdTs);
        double ms = sealedForCapMs(ts, toBoolArr(holdNoReading), toBoolArr(holdCommanding));
        if (runTracking && commandingNow) {
            long from = ts.length > 0 ? Math.max(ts[ts.length - 1], runStartedAt) : runStartedAt;
            if (now > from) ms += now - from;
        }
        return ms / 1000.0;
    }

    /** The frame pass of {@link #sealedForCapSec}: a gap counts when the frame that closes
     *  it had a real reading (gross's own rule, unchanged) or was marked commanding. */
    static double sealedForCapMs(long[] tsMs, boolean[] noReading, boolean[] commanding) {
        double ms = 0.0;
        if (tsMs == null) return ms;
        for (int i = 1; i < tsMs.length; i++) {
            long gap = tsMs[i] - tsMs[i - 1];
            if (gap <= 0) continue;
            boolean nr = noReading != null && i < noReading.length && noReading[i];
            boolean cmd = commanding != null && i < commanding.length && commanding[i];
            if (!nr || cmd) ms += gap;
        }
        return ms;
    }

    private final List<Long> holdTs = new ArrayList<Long>();
    private final List<Double> holdMeasuredKpa = new ArrayList<Double>();
    private final List<Boolean> holdNoReading = new ArrayList<Boolean>();
    private final List<Double> holdCommandedKpa = new ArrayList<Double>();
    private final List<Integer> holdPhase = new ArrayList<Integer>();
    private final List<Integer> holdStageIdx = new ArrayList<Integer>();
    private final List<Boolean> holdDropPhase = new ArrayList<Boolean>();
    /** D2 - whether the run was commanding pressure when this frame arrived
     *  (RunEdit#commandingPressure): what lets {@link #sealedForCapSec} count a 0.0 frame
     *  toward the two-hour stop. Never read by any figure the run files. */
    private final List<Boolean> holdCommanding = new ArrayList<Boolean>();
    /** STAGE H TASK 2 — whether the live preset's STAGE was marked as the trainer's
     *  fatigue block (Model.Stage#fatigueBlock) at the instant this frame arrived. Fed by
     *  the SAME per-frame recording every other hold-pass array already is; consumed by
     *  {@link #tupMs} to exclude fatigue-block frames from netMs, never from grossMs
     *  (guide: fatigue "does not feed the gate metric" — the NET figure only). */
    private final List<Boolean> holdFatigueStage = new ArrayList<Boolean>();

    /**
     * Call on every telemetry sample received during a run, alongside noteSample() —
     * SessionActivity#onSample derives `commandedKpa`/`phase`/`stageIdx` from the SAME
     * live-plan lookup commandedNowKpa() already uses (see the class doc above) and
     * hands them in, because Session itself stays decoupled from Model (no import of it
     * appears anywhere in this file) exactly as noteSample's own commandedKpa-free
     * signature already does.
     *
     * Gated on runTracking exactly like noteSample() — a sample arriving before
     * beginRun() (the seal check, the BEFORE half of an assessment pull, which runs
     * outside beginRun()/endRun() for exactly this reason) belongs to no run and must
     * not be misattributed to preset 0's hold phase; see noteSample's own doc for why
     * this gate exists. THE AFTER half of an assessment pull runs INSIDE beginRun()/
     * endRun(), so this gate alone does not exclude it — SessionActivity#recordHoldFrame,
     * the one caller, carries its own additional `assessing` check for that window; see
     * its own doc for why. killedMidRun's neighbour section states the wider "never
     * resumes/attributes anything on a guess" rule this class holds to throughout.
     *
     * STAGE H TASK 2 — `fatigueStage` EXTENDS this same per-frame pass rather than adding
     * a second recorder (the task's own brief): SessionActivity#recordHoldFrame derives it
     * from Model.Stage#fatigueBlock the SAME way it already derives `phase` from the live
     * preset's owning Set, and {@link #tupMs} is the one new pure pass that reads it back
     * alongside the arrays every hold-efficiency figure already uses.
     */
    /**
     * NET EXCLUDES A DROP FRAME FOR THE SAME REASON IT EXCLUDES A FATIGUE FRAME — neither is
     * work at the prescribed pressure — so they fold into one exclusion mask rather than
     * teaching tupMs a second rule. Gross still counts both, which is what gross is for.
     */
    private static boolean[] orDrop(boolean[] fatigue, boolean[] drop) {
        if (drop == null || drop.length == 0) return fatigue;
        int n = fatigue == null ? drop.length : Math.max(fatigue.length, drop.length);
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean f = fatigue != null && i < fatigue.length && fatigue[i];
            boolean d = i < drop.length && drop[i];
            out[i] = f || d;
        }
        return out;
    }

    /** Kept for callers that cannot tell a drop apart — records the frame as not-a-drop. */
    public void noteHoldFrame(long now, double measuredKpa, boolean noReading,
                               double commandedKpa, int phase, int stageIdx,
                               boolean fatigueStage) {
        noteHoldFrame(now, measuredKpa, noReading, commandedKpa, phase, stageIdx,
                      fatigueStage, false);
    }

    /**
     * `dropPhase` marks a frame that fell in the DROP half of a hold set's cycle (F14).
     *
     * The drop is a deliberate release to a lower pressure; time spent there is not time
     * under vacuum at the working pressure, and Net TUP must not count it. Until now it was
     * excluded only BY ACCIDENT — the drop target sits below the level floor, so the
     * measured-vs-floor comparison happened to reject those frames. That was always
     * fragile, and Stage K's tolerance band made it more so: a drop configured close to the
     * floor could now land INSIDE the band and start counting as work.
     *
     * So the exclusion becomes structural, exactly like the fatigue block's: the recorder
     * knows which half of the cycle it is in, says so, and net excludes it regardless of
     * what the pressure happened to read. Since a drop may be set above every floor (up to
     * 1.0 inHg under the pull, RunEdit#dropTopKpa - owner, 2026-09-30) this is the only
     * thing that keeps it out, and the dose asks the same split (noteSample's `dropPhase`).
     */
    public void noteHoldFrame(long now, double measuredKpa, boolean noReading,
                               double commandedKpa, int phase, int stageIdx,
                               boolean fatigueStage, boolean dropPhase) {
        noteHoldFrame(now, measuredKpa, noReading, commandedKpa, phase, stageIdx,
                      fatigueStage, dropPhase, false);
    }

    /** D2 - `commanding` marks a frame that arrived while the run was commanding pressure
     *  (RunEdit#commandingPressure), so the two-hour stop counts it whatever it read
     *  ({@link #sealedForCapSec}). The eight-argument form marks nothing, which is what the
     *  stop counted before. */
    public void noteHoldFrame(long now, double measuredKpa, boolean noReading,
                               double commandedKpa, int phase, int stageIdx,
                               boolean fatigueStage, boolean dropPhase, boolean commanding) {
        if (!runTracking) return;
        holdTs.add(Long.valueOf(now));
        holdMeasuredKpa.add(Double.valueOf(measuredKpa));
        holdNoReading.add(Boolean.valueOf(noReading));
        holdCommandedKpa.add(Double.valueOf(commandedKpa));
        holdPhase.add(Integer.valueOf(phase));
        holdStageIdx.add(Integer.valueOf(stageIdx));
        holdFatigueStage.add(Boolean.valueOf(fatigueStage));
        holdDropPhase.add(Boolean.valueOf(dropPhase));
        holdCommanding.add(Boolean.valueOf(commanding));
    }

    /** The whole session's T14+ figure, computed ONCE at finishSession from this run's
     *  recording — see Model.Sess#holdEfficiencyPct, the one place the result is kept
     *  past the process that ran it. */
    public Double holdEfficiencyPct() {
        return holdEfficiencyPct(toLongArr(holdTs), toDoubleArr(holdMeasuredKpa),
            toBoolArr(holdNoReading), toDoubleArr(holdCommandedKpa), toIntArr(holdPhase));
    }

    /** The per-stage figure, live — see holdEfficiencyPctForStage(long[], ...)'s own doc.
     *  Fix round 1: SessionActivity#fileSession calls this once per stage, at the SAME
     *  finishSession moment holdEfficiencyPct() above is read, and files every result
     *  onto Model.Sess#holdEfficiencyByStagePct — the one place a reopened session
     *  reads a per-stage figure back from. This method itself still only ever answers
     *  for the run currently held in THIS Session instance (cleared at the next
     *  beginRun()), which is exactly right for a call made once, synchronously, at the
     *  instant that run ends — it is simply no longer how a later DISPLAY reads the
     *  figure back. */
    public Double holdEfficiencyPctForStage(int stage) {
        return holdEfficiencyPctForStage(toLongArr(holdTs), toDoubleArr(holdMeasuredKpa),
            toBoolArr(holdNoReading), toDoubleArr(holdCommandedKpa), toIntArr(holdPhase),
            toIntArr(holdStageIdx), stage);
    }

    private static long[] toLongArr(List<Long> l) {
        long[] a = new long[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i).longValue();
        return a;
    }
    private static double[] toDoubleArr(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i).doubleValue();
        return a;
    }
    private static boolean[] toBoolArr(List<Boolean> l) {
        boolean[] a = new boolean[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i).booleanValue();
        return a;
    }
    private static int[] toIntArr(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i).intValue();
        return a;
    }

    /** The elapsed-into-the-run value the screen should display right now: real
     *  wall-clock time while telemetry is arriving, latched at whatever it was the
     *  instant the gap first exceeded `timeoutMs` (S2's freeze). Sticky — once
     *  tripped it stays frozen on every subsequent call, even before a new sample
     *  arrives, until noteSample() is called again. The silence is measured from
     *  silenceReference(), so a run that has not had its FIRST sample yet is on the
     *  clock from the moment it started — see that method. */
    public long elapsedMs(long now, long timeoutMs) {
        if (runStartedAt <= 0) return 0;
        long raw = Math.max(0, now - runStartedAt);
        boolean lostNow = linkLost(now, silenceReference(runStartedAt, lastSampleAt), timeoutMs);
        if (lostNow && !linkWasLost) {
            linkWasLost = true;
            elapsedAtLoss = raw;
        }
        return frozenElapsedMs(raw, elapsedAtLoss, linkWasLost);
    }

    public boolean isLinkLost() { return linkWasLost; }

    /** Whether any telemetry has arrived since beginRun(). The link-lost screen needs
     *  this to word itself honestly: "last seen N s ago" is a lie when nothing was ever
     *  seen, and the Activity's own lastSampleAt cannot answer it — that one still holds
     *  the SEAL CHECK's samples, which arrived before this run began. */
    public boolean sawSampleThisRun() { return lastSampleAt > 0; }

    /* --------------------------------------------------- filed once, and only once */

    /**
     * Starts a new ATTEMPT: the point at which the pump is first commanded on a person
     * — the seal check — which is EARLIER than beginRun(), the routine's own playback.
     * The attempt, not the run, is the unit that gets filed to History: an attempt
     * abandoned during the seal check is filed too (0:00, nothing delivered), and it
     * never had a beginRun() to re-arm anything.
     */
    public void beginAttempt() { runFiled = false; }

    /**
     * Claims the right to file THIS attempt to History, returning false if it has
     * already been filed. Fix round 1's Important finding added a second exit that
     * files — onDestroy(), for a run destroyed in-process — and two filing paths is
     * exactly the shape that produces two rows for one session.
     *
     * What that second exit actually covers, since it was once described as covering a
     * task swipe and does not: a uiMode/density/font-scale/locale change, an explicit
     * finish(), and any recreate the manifest's configChanges does not absorb. A swipe
     * from Recents can kill the process without calling onDestroy at all, and that run
     * is simply lost — no row, and nothing here runs.
     * The latch lives here rather than in the Activity so it is asserted by machine and
     * survives any future third exit; `running` remains the Activity's own gate.
     */
    public boolean markRunFiled() {
        if (runFiled) return false;
        runFiled = true;
        return true;
    }

    public boolean isRunFiled() { return runFiled; }

    private boolean runFiled;

    /* ------------------------------------------------------------- seal check (Step 2) */

    /**
     * The deepest pressure telemetry actually REPORTED across a window, or NaN when no
     * valid sample arrived. Samples flagged noReading (0.0 — "no measurement", see
     * Proto.Sample) are skipped: a 0.0 is never a peak, in either direction, which is the
     * same rule that governs every other 0.0 in this app.
     *
     * This exists so a screen can print what the pump REACHED instead of what it was
     * TOLD to reach. The seal-check result chip printed
     * {@code Model.Fmt.p(Math.min(20, model.ceilKpa))} — the byte-identical expression
     * used to build the addPreset setpoint — under the words "held X kPa", so a cuff that
     * plateaued at 11 against a commanded 20 still read "held 20.0 kPa".
     */
    public static double peakValidKpa(double[] kpa, boolean[] noReading) {
        int n = kpa == null ? 0 : kpa.length;
        double peak = Double.NaN;
        for (int i = 0; i < n; i++) {
            if (noReading != null && i < noReading.length && noReading[i]) continue;
            if (Double.isNaN(peak) || kpa[i] > peak) peak = kpa[i];
        }
        return peak;
    }

    /** The index of the first valid sample achieving {@link #peakValidKpa}, or -1 when
     *  there is none. Exposed for the same reason the rate below is: the coast begins at
     *  the top of the rise, and that instant has to be nameable to be asserted. */
    public static int peakValidIndex(double[] kpa, boolean[] noReading) {
        int n = kpa == null ? 0 : kpa.length;
        int at = -1;
        for (int i = 0; i < n; i++) {
            if (noReading != null && i < noReading.length && noReading[i]) continue;
            if (at < 0 || kpa[i] > kpa[at]) at = i;
        }
        return at;
    }

    /**
     * The decay rate (kPa/s, always &gt;= 0) measured across a COASTING hold — never
     * after StopWork, which vents and would measure the valve rather than the seal
     * (Step 2's whole point). Samples flagged noReading (0.0) are skipped entirely, at
     * both ends and anywhere in between: never an endpoint of the slope, never
     * integrated, so a dropout mid-check cannot manufacture a huge false decay rate.
     * Returns 0 when fewer than two valid samples exist after the peak, or when the
     * pressure did not fall between them — never negative.
     *
     * FINAL REVIEW, Important — WHERE THE SLOPE STARTS. This took the drop from the
     * FIRST valid sample to the LAST, and the buffer it is handed starts at the instant
     * the hold is COMMANDED, with the cuff at ~0 kPa. So the window spanned the RISE:
     * the first valid sample sat at a few kPa on the way up and the last sat near the
     * target after coasting, dropKpa came out negative, and the guard returned exactly
     * 0.00 kPa/s for every seal that let the pump reach pressure — good or bad. A cuff
     * leaking 2 kPa/s printed the same 0.00 as a perfect one. The only shape that ever
     * produced a non-zero figure was a leak so severe the pressure fell back below its
     * own first reading inside the window.
     *
     * The slope is therefore anchored at the PEAK valid sample — the top of the rise,
     * which is where the coast begins — and runs to the last valid sample. No new input
     * is needed: the peak is in the data. This is a strict generalisation, not a
     * different measurement; every window that already started at its plateau (which is
     * the only shape the harness fed before this) has its peak at index 0 and is
     * unchanged. Tau.java's class doc ("the seal check exists to MEASURE that coast") is
     * true of this version and was not true of the previous one.
     *
     * It does NOT need the commanded pressure, deliberately: a cuff that never reached
     * the target still coasts from wherever it got to, and that coast is exactly what
     * this is measuring. Whether the target was reached at all is a separate question,
     * answered separately from {@link #peakValidKpa}.
     */
    public static double coastingDecayRate(long[] tsMs, double[] kpa, boolean[] noReading) {
        int n = tsMs == null ? 0 : tsMs.length;
        int first = peakValidIndex(kpa, noReading);
        if (first < 0) return 0.0;
        int last = -1;
        for (int i = first; i < n; i++) {
            if (noReading != null && i < noReading.length && noReading[i]) continue;
            last = i;
        }
        if (last <= first) return 0.0;
        double dropKpa = kpa[first] - kpa[last];
        double dtSec = (tsMs[last] - tsMs[first]) / 1000.0;
        if (dtSec <= 0 || dropKpa <= 0) return 0.0;
        return dropKpa / dtSec;
    }

    /**
     * Did the cuff actually get to the pressure that was commanded?
     *
     * `reachedKpa` is {@link #peakValidKpa} over the check's window; NaN (no valid
     * sample at all) is NOT a shortfall — it is an absence of evidence, which the caller
     * reports as inconclusive rather than as a verdict about the seal.
     *
     * The tolerance is 1.0 kPa, the same bound the summary's Noticed card already treats
     * as a "sustained shortfall", and it is a real bound rather than a rounding
     * allowance: an early bench log reached 21.0 kPa against a 20 kPa target, so a
     * healthy pull overshoots slightly rather than falling short.
     */
    public static final double SEAL_REACH_TOLERANCE_KPA = 1.0;

    public static boolean reachedCommanded(double reachedKpa, int commandedKpa) {
        if (Double.isNaN(reachedKpa)) return true;      // no evidence either way
        return reachedKpa >= commandedKpa - SEAL_REACH_TOLERANCE_KPA;
    }

    /* ------------------------------------------------------ the live-run notification */

    /**
     * FIX ROUND B — the words on the ongoing notification that now keeps a run alive when
     * the app goes off screen (see {@link RunService}).
     *
     * Here, in the pure layer, rather than in the service, for the reason every other
     * decidable thing in this app is: RunService cannot be compiled or exercised by
     * test.sh (it carries `import android`), so any string-building done inside it is
     * unasserted, and an ongoing notification is the ONLY thing a user who has left the
     * app can see about a run that is happening to their body. "preset 0 of 0" or a
     * dangling separator is not cosmetic there.
     *
     * THE RULES, and each one is asserted:
     *   - Segments are joined with " · " and a segment is only present when it says
     *     something. A missing preset index, a missing countdown and a missing pressure
     *     each drop out entirely rather than leaving an empty slot or a doubled separator.
     *   - The preset segment appears only when the total is positive AND the index is
     *     within 1..total. A live run has both; a seal check, an assessment and the
     *     hardware self-test have no preset sequence at all and simply do not claim one.
     *   - The countdown and the pressure arrive ALREADY FORMATTED, from the same Fmt
     *     calls the run screen uses (Fmt.t and Fmt.p). This method must never format a
     *     pressure itself: Fmt.p follows the user's display unit, and a second formatter
     *     here is how the notification would come to read kPa while the screen reads inHg.
     *   - null is treated as absent, never printed. A null name falls back to the generic
     *     title rather than to the string "null".
     *   - "Discreet notifications" (T17, and since 0.10 incognito's own switch, the owner's
     *     decision of 2026-09-26): with `discreet` true the title is "Session running" and
     *     the body only the time left - "12:30 left", or "Paused · ends in 4:32" while the
     *     run's Hold is up (Incognito#runText) - never the name, the preset or the pressure.
     *     It was the fixed pair "Reminder" / "Timer running"; the owner chose words that
     *     keep the one figure a person needs (when it ends) and still say nothing about the
     *     pump.
     *
     * The title carries the name and the body carries the changing numbers, so the line
     * the user reads at a glance on a locked screen is stable while the countdown moves.
     */
    public static final String RUN_NOTIFICATION_FALLBACK_TITLE = "Pump session";
    /** The discreet title, used verbatim and only when discreet mode is on — never
     *  combined with the routine's real name. */
    public static final String DISCREET_NOTIFICATION_TITLE = Incognito.RUN_TITLE;
    private static final String NOTIFICATION_SEP = " · ";

    /** The notification's title: with `discreet` on, always the neutral "Session running";
     *  otherwise the routine's own name when there is one, else a generic-but-honest
     *  label. Never blank either way — a titleless ongoing notification is a handle the
     *  user cannot identify. */
    public static String runNotificationTitle(boolean discreet, String name) {
        if (discreet) return DISCREET_NOTIFICATION_TITLE;
        if (name == null) return RUN_NOTIFICATION_FALLBACK_TITLE;
        String t = name.trim();
        return t.length() == 0 ? RUN_NOTIFICATION_FALLBACK_TITLE : t;
    }

    /**
     * The notification's body: with `discreet` on, only the time left (Incognito#runText);
     * otherwise "preset 2 of 6 · 1:30 left · 18.0 kPa", minus whatever is not
     * known, or "in progress" when nothing at all is known — an ongoing notification with
     * an empty body reads as a bug rather than as a run.
     */
    public static String runNotificationText(boolean discreet, String name, int preset,
                                             int total, String countdown, String pressure) {
        return runNotificationText(discreet, name, preset, total, countdown, pressure, null);
    }

    /**
     * The same line, with the run's Hold (the owner's decision): while it is up the preset's
     * countdown is frozen, so "1:30 left" would be a number that is not moving; the Hold's own
     * limit is what the person needs - "holding, vents in 4:32" (InRunHold#ventsIn). Empty or
     * null `holdVentsIn` means no Hold, and the line is exactly as before.
     */
    public static String runNotificationText(boolean discreet, String name, int preset,
                                             int total, String countdown, String pressure,
                                             String holdVentsIn) {
        if (discreet) return Incognito.runText(countdown, holdVentsIn);
        StringBuilder sb = new StringBuilder();
        if (total > 0 && preset >= 1 && preset <= total)
            append(sb, "preset " + preset + " of " + total);
        String hold = holdVentsIn == null ? "" : holdVentsIn.trim();
        String left = countdown == null ? "" : countdown.trim();
        if (hold.length() > 0) append(sb, "holding, " + hold);
        else if (left.length() > 0) append(sb, left + " left");
        String p = pressure == null ? "" : pressure.trim();
        if (p.length() > 0) append(sb, p);
        if (sb.length() == 0) return "in progress";
        return sb.toString();
    }

    /**
     * THE NOTIFICATION LEADS WITH A STEP DONE BY HAND (the owner's decision, 2026-10-07):
     * "By hand · Tunica release · preset 1 of 14 · 4:32 left" (ByHand#notification). `phase`
     * empty or null - every other step - leaves the line exactly as it was; DISCREET leaves it
     * too, because a discreet notification says the time left and nothing about the session.
     */
    public static String runNotificationPhase(boolean discreet, String phase, String text) {
        String p = phase == null ? "" : phase.trim();
        if (discreet || p.length() == 0) return text;
        if (text == null || text.length() == 0 || "in progress".equals(text)) return p;
        return p + NOTIFICATION_SEP + text;
    }

    private static void append(StringBuilder sb, String seg) {
        if (sb.length() > 0) sb.append(NOTIFICATION_SEP);
        sb.append(seg);
    }

    /* ============================== Stage D — task 10 (N2) ====================
     *
     * THE HOME-SCREEN WIDGET'S WORDS — dist/round5-options.html #n2, decision "B — LIVE
     * 4x2 TILE": "RUNNING · <name>" over "<mm:ss> left", an amber progress bar, then
     * "<pressure>" over "step <i> of <n>". PumpWidgetProvider carries `import android`
     * (RemoteViews, AppWidgetManager) and is therefore outside test.sh's pure set exactly
     * like RunService is — so, as with the FIX ROUND B notification words above, the
     * strings themselves live here, pure, and are asserted in SelfTest. RunService feeds
     * these the SAME snapshot (name/idx/total/countdown/pressure) it already pulls once
     * per tick to build the notification; nothing here talks to Android or to the pump.
     */

    /** The widget's status line while a run is live: "RUNNING · <name>", or bare
     *  "RUNNING" when the name is blank — never a dangling " · " with nothing after it. */
    public static String widgetStatusLine(String name) {
        String n = name == null ? "" : name.trim();
        return n.length() == 0 ? "RUNNING" : "RUNNING · " + n;
    }

    /** "<countdown> left", or "" when there is no countdown to show — the widget hides
     *  the whole line rather than render a bare "left". */
    public static String widgetTimeLeft(String countdown) {
        String c = countdown == null ? "" : countdown.trim();
        return c.length() == 0 ? "" : c + " left";
    }

    /** "step <i> of <n>", under the exact same validity rule runNotificationText uses for
     *  the same numbers (a preset-less phase, or an index the plan does not have, claims
     *  no step rather than printing a lie like "step 0 of 0" or "step 7 of 6"). */
    public static String widgetStepLine(int idx, int total) {
        if (total > 0 && idx >= 1 && idx <= total) return "step " + idx + " of " + total;
        return "";
    }

    /** The progress bar's fill, 0-100. Built from preset-step progress (idx/total) rather
     *  than a time-within-step fraction: livePresetIndex/Total are the only numeric
     *  progress RunService's Live interface exposes (liveCountdown is a pre-formatted
     *  string, not a fraction), and reusing exactly what build() already pulls for the
     *  notification is the point — no new data invented for the widget alone. Clamped so
     *  an index outside 0..total (the same "index the plan does not have" case
     *  widgetStepLine drops) can never overshoot or undershoot the bar. */
    public static int widgetProgressPercent(int idx, int total) {
        if (total <= 0) return 0;
        int clamped = idx < 0 ? 0 : (idx > total ? total : idx);
        return (int) Math.round(clamped * 100.0 / total);
    }

    /** The pressure line, already formatted (Model.Fmt.p) by whoever called in — this
     *  only trims and turns a null into "", the same absence rule every field here uses. */
    public static String widgetPressureLine(String pressure) {
        return pressure == null ? "" : pressure.trim();
    }

    /* ============================== Stage E — task 3 (T18) ====================
     *
     * BATTERY-KILLER PROTECTION'S PURE HALF — dist/round7-options.html's T18 A decision:
     * "guided exemption in setup + pre-run check; service-killed detection re-offers."
     * RunService carries `import android` and is outside test.sh's pure set exactly like
     * the widget section above, so the ONE comparison behind "was this run killed out
     * from under the app" lives here instead, pure, and is asserted in SelfTest.
     */

    /**
     * True only when a run was believed active with no clean stop recorded, AND the
     * service that would prove it is still going is not alive in this process — the shape
     * a battery-hungry OEM killing the whole process out from under a live run leaves
     * behind.
     *
     * THE TWO INPUTS. `believedActive` is a flag SessionActivity persists to
     * SharedPreferences (survives a process death, unlike any in-memory field) the moment
     * syncRunService() sees liveWork() go true, and clears the moment that SAME method
     * sees it go false through a real stop — so "still true" on the next read means "no
     * clean stop was recorded", not merely "a run was running at some point".
     * `serviceAliveNow` is RunService.isAlive(), read fresh in the CURRENT process.
     *
     * WHY isAlive(), NOT isRunning(). RunService's own class comment (its `alive` and
     * `foreground` fields) and PumpWidgetProvider's class comment both already draw this
     * exact line, for the identical reason: isRunning() (the `foreground` field) reads
     * false whenever startForeground() itself failed — a vendor ROM refusing the
     * foreground service type, a revoked notification permission — even though the
     * service, the process and the run are all still genuinely alive and driving the
     * pump; RunService's own syncRunService()-adjacent toast already tells the user about
     * that degraded-but-continuing case. Comparing against isRunning() here would
     * misreport that ALREADY-HANDLED case as a battery kill, which is not just noise: it
     * would tell someone whose run is fine to go check whether their pump is still under
     * pressure. isAlive() reads true only from the top of onStartCommand's normal path
     * through onDestroy() IN THAT PROCESS, so a fresh process — and only a fresh process —
     * reads it false, which is exactly the one condition this comparison needs to catch.
     *
     * NEVER RESUMES ANYTHING. This function only answers a question; nothing that calls
     * it may treat a "true" as licence to re-arm the pump. See SessionActivity#onResume,
     * the one caller, and its own showKilledMidRunDialog() for the same "surface it, never
     * silently resume" rule Stage D's Task 5 reconnect-offer already established for a
     * link that comes back on its own.
     */
    public static boolean killedMidRun(boolean believedActive, boolean serviceAliveNow) {
        return believedActive && !serviceAliveNow;
    }
}
