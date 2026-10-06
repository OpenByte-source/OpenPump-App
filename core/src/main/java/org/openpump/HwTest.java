package org.openpump;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * THE MERGED DIAGNOSTIC ENGINE — the decidable half of "Hardware self-test", kept PURE
 * (no `import android`) so test.sh compiles it and SelfTest pins every judgement below.
 *
 * WHAT THIS REPLACES. The app used to carry ONE hardware door: the validation routine
 * (Validate), which is a MEASUREMENT INSTRUMENT — it puts a measured number beside each
 * of the app's physics assumptions and deliberately refuses to say pass or fail. That is
 * still true and still reachable ("Full measurement routine ▸" on the report). What it
 * never did was answer the other question: does the WIRING work — does a preset that goes
 * out actually come back acknowledged, does an adjustment carry, does a hold release into
 * the right thing, does the batch boundary hold. This class answers that one, and the two
 * now live behind one door.
 *
 * IT RETURNS VERDICTS, AND EVERY VERDICT IS EVIDENCED. This is the difference from
 * Validate and it is the whole design. A phase passes only when the AS-RUN RECORDER and
 * the START-ack matcher say the rows it needed exist AND are confirmed; a row that exists
 * but was never acknowledged by the pump is a FAILURE of that phase, never a warning.
 * "Green" in this app is legal only for telemetry-confirmed facts, and the ✓ this engine
 * produces is exactly that.
 *
 * TWO SECTIONS, AND ONLY ONE OF THEM TOUCHES PRESSURE.
 *
 *   PRE-FLIGHT (P1..P4, ~30 s, silent). Runs the instant the door opens, while the user is
 *   still attaching the vessel. Nothing here commands pressure: two observation phases and
 *   two write-only phases. It reuses Validate's own analysis (cadence, baseline, planPresets
 *   / parseDump / compareRec) and the existing armPhase write-only branch, so the pre-flight
 *   introduces no new arming site at all.
 *
 *   VESSEL (V1..V13, ~10 min). Runs a REAL SESSION over a synthetic routine placed in
 *   model.adhoc — the same hook manual mode uses. That is load-bearing: it means
 *   playPreset / applyOverride / enterHold / exitHold / skipPreset / extendPreset /
 *   revertOverride execute with their EXISTING as-run notes, their existing ceiling clamp,
 *   their existing vent discipline and their existing WiringCheck invariants. The driver
 *   scripts the run by calling the SAME methods the user's buttons call, at scripted
 *   moments. It never writes a frame of its own. A self-test that armed the pump by its own
 *   path would be testing its own path.
 *
 * EVERY VESSEL PHASE IS SHALLOW — {@link #shallowKpa}, min(20, ceiling) — and every phase
 * is separated from the next by the routine's own preset sequencing, with the vent gate
 * (Validate#ventGate, VENTED_INFERRED included) before the section and after it.
 *
 * FAILURE IS NOT FATAL, EXCEPT WHERE IT IS. A phase that fails is marked ✗ and the run
 * CONTINUES to the next phase — a diagnostic that stops at the first fault reports one
 * fault per run and takes ten minutes to do it. The exception is V1, the vent proof: if
 * the pump cannot be shown to vent, nothing after it may be run at all, so V1 failing
 * aborts the whole vessel section. Every exit is an abort that vents.
 */
public final class HwTest {
    private HwTest() { }

    /* ======================================================== §1 the phase table ==== */

    /** Phase keys. Stable strings: they are the log's grouping, the report's row identity
     *  and the judge's dispatch, and a renumbering must not silently re-point any of them. */
    public static final String P1 = "P1", P2 = "P2", P3 = "P3", P4 = "P4";
    public static final String V1 = "V1", V2 = "V2", V3 = "V3", V4 = "V4", V5 = "V5",
                               V6 = "V6", V7 = "V7", V8 = "V8", V9 = "V9", V10 = "V10",
                               V11 = "V11", V12 = "V12", V13 = "V13";

    /** One phase of the merged routine: what it is called, what it PROVES (the sentence
     *  the report puts under it when there is no evidence to put there instead), and
     *  whether it needs the vessel attached. */
    public static final class Ph {
        public final String key, label, proves;
        public final boolean needsVessel;
        Ph(String key, String label, String proves, boolean needsVessel) {
            this.key = key; this.label = label; this.proves = proves;
            this.needsVessel = needsVessel;
        }
    }

    /** The whole sequence, in order. Pre-flight first, then the vessel section. */
    public static Ph[] phases() {
        return new Ph[]{
            new Ph(P1, "Link and telemetry cadence",
                   "frames arrive, and they arrive at the rate Tau assumes", false),
            new Ph(P2, "Vented baseline",
                   "a vented pump gives an assessment a start it can arm from", false),
            new Ph(P3, "Preset table round-trip",
                   "nine written presets come back byte for byte, speed echo included", false),
            new Ph(P4, "Slot arithmetic",
                   "the override slot is where RunEdit says it is, and deleting it shifts "
                   + "nothing", false),
            new Ph(V1, "Vent proof",
                   "StopWork vents, and telemetry (or the inference) evidences the fall", true),
            new Ph(V2, "Fixed set, two cycles",
                   "every preset is START-acked and the pressure reaches target", true),
            new Ph(V3, "Ramp, four steps",
                   "each step's wire values are the ladder's, and all five fields walk", true),
            new Ph(V4, "Multi-stage, two stages",
                   "blocks group as the plan does, and an adjustment is cleared at the "
                   + "stage boundary", true),
            new Ph(V5, "Live adjustment mid-fixed",
                   "the override is acked and the countdown does not move", true),
            new Ph(V6, "Adjustment mid-ramp",
                   "the adjustment replaces the remaining steps of the stage", true),
            new Ph(V7, "Revert",
                   "the routine's own values come back on the next row", true),
            new Ph(V8, "Hold and resume",
                   "a hold delivers no set values, and the release resumes the right slot", true),
            new Ph(V9, "+30 s",
                   "one extension is recorded on the open row, and a carried hold is "
                   + "refreshed", true),
            new Ph(V10, "Skip",
                   "a preset cut short of half its plan is SKIPPED, and the next one starts",
                   true),
            new Ph(V11, "Batch boundary",
                   "a plan longer than the batch crosses it with no unconfirmed row and no "
                   + "gap", true),
            new Ph(V12, "Seal-check calibration",
                   "time to target at 100% and the coast rate, on this vessel — numbers "
                   + "only, no verdict", true),
            new Ph(V13, "Closing vent",
                   "the section ends vented, and the report is written to the log", true)
        };
    }

    /** The phase with this key, or null. */
    public static Ph phase(String key) {
        Ph[] ps = phases();
        for (int i = 0; i < ps.length; i++) if (ps[i].key.equals(key)) return ps[i];
        return null;
    }

    /** How many of the phases run before the vessel is needed — the pre-flight's own
     *  count, which is what the one progress line on screen counts down. */
    public static int preflightCount() {
        Ph[] ps = phases();
        int n = 0;
        for (int i = 0; i < ps.length; i++) if (!ps[i].needsVessel) n++;
        return n;
    }

    /* ------------------------------------------- what the pre-flight LOOKS like live */

    /**
     * THE PRE-FLIGHT HAS TO LOOK LIKE IT IS HAPPENING.
     *
     * On a device the four checks take about thirty-five seconds, during which the screen
     * said "· Link and telemetry cadence" and three more dots, unchanged, from the first
     * frame to the last. Two users in a row read that as an app that had hung, left, and —
     * because leaving used to abort — got a report saying seventeen checks did not run.
     * The checks were fine. The screen was the defect.
     *
     * So each row carries a STATE, and the running one carries the seconds left on its own
     * phase bound. Pure, so SelfTest pins the wording rather than a device having to.
     */
    public static final int PF_PENDING = 0, PF_RUNNING = 1, PF_DONE = 2;

    /** The glyph a row is marked with. The done rows take theirs from the Verdict itself
     *  (✓ / ✗ / —) so the line and the report can never disagree; these are the two states
     *  a verdict has no answer for yet. */
    public static final String PF_MARK_PENDING = "·";
    public static final String PF_MARK_RUNNING = "▸";

    /** What the phase that is running is DOING, in the user's terms rather than the
     *  driver's. Empty for a key with nothing to say. */
    public static String preflightDoing(String key) {
        if (P1.equals(key)) return "listening for frames";
        if (P2.equals(key)) return "reading the vented baseline";
        if (P3.equals(key)) return "writing the preset table and reading it back";
        if (P4.equals(key)) return "checking the override slot arithmetic";
        return "";
    }

    /** Seconds remaining, rounded UP, so a bound with 200 ms left still reads "1 s" and the
     *  countdown never shows 0 s while the phase is still running. Never negative. */
    public static String secondsLeft(long msLeft) {
        long s = msLeft <= 0 ? 0 : (msLeft + 999L) / 1000L;
        return s + " s left";
    }

    /**
     * One row of the live pre-flight block.
     *
     * @param mark    the glyph — a Verdict's mark() for a judged row, PF_MARK_* otherwise
     * @param key     the phase key, so the row names itself the way the log does
     * @param label   the phase's label
     * @param state   PF_PENDING / PF_RUNNING / PF_DONE
     * @param msLeft  what is left of the running phase's bound; ignored unless PF_RUNNING
     */
    public static String preflightRow(String mark, String key, String label, int state,
                                      long msLeft) {
        StringBuilder sb = new StringBuilder(mark).append("  ").append(key).append(" · ")
            .append(label);
        if (state == PF_RUNNING) {
            String doing = preflightDoing(key);
            if (doing.length() > 0) sb.append(" · ").append(doing);
            sb.append(" · ").append(secondsLeft(msLeft));
        }
        return sb.toString();
    }

    /* ==================================================== §2 the pre-flight phases ==== */

    /**
     * The pre-flight's four phases, expressed as Validate.Phase so the EXISTING driver
     * (armPhase, tickValidationPhase) runs them unchanged. None of them commands pressure:
     * two observe, two write the table and read it back.
     *
     * Step numbers are Validate's, deliberately, because armPhase and finishPhase branch on
     * them: step 2 is the cadence analysis, step 1 the baseline, step 8 the round-trip.
     * {@link #STEP_SLOT_ARITH} is new — it is the same WRITE-ONLY shape as step 8 (it adds a
     * ninth entry as the override slot would, deletes it, and dumps), which is why it can
     * share armPhase's write-only branch rather than needing an arming site of its own.
     */
    public static final int STEP_SLOT_ARITH = 9;

    /** P1 samples for this long. Thirty seconds at Tau's assumed 240 ms is ~125 frames, so
     *  the {@link #minFrames} floor has real headroom over the window rather than
     *  being a restatement of it. */
    public static final long CADENCE_WINDOW_MS = 30000L;
    /**
     * The FLOOR on the sample size, and it is a floor rather than a restatement of the
     * window.
     *
     * WHAT WAS WRONG. This was a flat 100 frames against a 30 s window. That is not a
     * floor, it is the window itself: this pump streams at ~4 Hz (250 ms, the RX log's
     * every-25th-frame lines land ~6.25 s apart), so 30 s delivers about 120 frames and the
     * bound leaves ~5 s of slack. One reconnect, one scheduling hiccup on the main thread,
     * one phase armed a beat late — any of them costs more than the 20 frames of headroom
     * there were, and P1 then FAILED a link that was demonstrably healthy, reporting
     * "N frames (needs 100)" about a cadence it had just measured correctly. A gate that
     * fails on a fifth of a window missing is measuring the scheduler, not the link.
     *
     * WHAT IT IS NOW. Fifty frames — twelve seconds of this pump's telemetry, which is
     * plenty to derive a mean interval from — or, for a window too short to deliver even
     * that, four fifths of what the window itself implies. Derived from the window rather
     * than written down twice, so shortening a phase can never leave a floor above its own
     * ceiling, which is the shape that made this unpassable in the first place.
     */
    public static final int CADENCE_FLOOR_FRAMES = 50;

    /** How much of a window's nominal frame count a link must actually deliver before the
     *  sample is called too small. Not 1.0: a window is not a promise, and the last frame
     *  of one lands wherever the device's own clock puts it. */
    public static final double CADENCE_WINDOW_FRACTION = 0.8;

    /** The frame floor for a window of this length — {@link #CADENCE_FLOOR_FRAMES}, or the
     *  window's own 80% expectation when that is smaller. Never below 2: fewer than two
     *  frames is not a small sample, it is no interval at all, and Cadence#derivable
     *  already says so. */
    public static int minFramesFor(long windowMs) {
        long expected = Math.round(windowMs * CADENCE_WINDOW_FRACTION
                                   / (double) Tau.TELEMETRY_INTERVAL_MS);
        long floor = Math.min((long) CADENCE_FLOOR_FRAMES, expected);
        return (int) Math.max(2L, floor);
    }

    /** P1's own floor, for the window P1 actually samples over. */
    public static int minFrames() { return minFramesFor(CADENCE_WINDOW_MS); }
    /** How far the measured mean spacing may sit from Tau.TELEMETRY_INTERVAL_MS and still
     *  pass. A quarter is generous on purpose: this is a PASS/FAIL gate on the link, not
     *  the measurement (Validate#cadenceLine is the measurement, and it flags at a fifth). */
    public static final double CADENCE_TOL_FRACTION = 0.25;

    public static Validate.Phase[] preflight(int ceilKpa) {
        return new Validate.Phase[]{
            new Validate.Phase(2, P1, "Link and telemetry cadence",
                               0, 0, 0, false, CADENCE_WINDOW_MS + 5000L, false, false, false),
            new Validate.Phase(1, P2, "Vented baseline",
                               0, 0, 0, false, 20000L, false, false, false),
            new Validate.Phase(8, P3, "Preset table round-trip",
                               0, 0, 0, true, 15000L, false, false, false),
            new Validate.Phase(STEP_SLOT_ARITH, P4, "Slot arithmetic",
                               0, 0, 0, true, 15000L, false, false, false)
        };
    }

    /** The pre-flight's own bound, for the sentence that tells the user how long to wait
     *  before the vessel is needed. No vent gates: nothing in it commands pressure, so
     *  there is nothing to vent between phases and a gate would only cost the user
     *  eighty seconds of standing still. */
    public static long preflightBoundMs(int ceilKpa) {
        Validate.Phase[] ps = preflight(ceilKpa);
        long t = 0;
        for (int i = 0; i < ps.length; i++) t += ps[i].boundMs;
        return t;
    }

    /* --------------------------------------------------------- P1 / P4 decisions --- */

    /** P1's verdict, from Validate's own cadence analysis. Both terms are required: a mean
     *  inside tolerance computed from eleven frames says nothing. */
    public static boolean cadenceOk(Validate.Cadence c) {
        if (c == null || !c.derivable) return false;
        if (c.frames < minFrames()) return false;
        double want = Tau.TELEMETRY_INTERVAL_MS;
        return Math.abs(c.meanMs - want) <= want * CADENCE_TOL_FRACTION;
    }

    /**
     * P4's verdict, computed from RunEdit's own arithmetic rather than from a second
     * derivation of it. `entries` is how many entries stood in the table when the ninth was
     * appended — the state the device was actually left in by the write.
     *
     * Both halves are RunEdit's and both are asserted there too; what this adds is the
     * pairing, which is the thing the pump can get wrong: the override goes into the slot
     * RunEdit names, and deleting THAT slot must not renumber the eight below it.
     */
    public static boolean slotArithmeticOk(int planSize, int slots, int entries) {
        int idx = RunEdit.overrideSlotIndex(0, planSize, slots);
        if (idx != slots - 1) return false;
        return RunEdit.deleteShiftsNothing(idx, entries);
    }

    /* =================================================== §3 the synthetic routine ==== */

    /**
     * THE CEILING FOR EVERY VESSEL PHASE. Shallow, always: min(20, ceiling). The vessel
     * section is long and runs against a rigid vessel, and nothing it is trying to prove —
     * an ack, a carry, a hold release, a batch boundary — is proved any better by pulling
     * deeper. The ordinary ceiling still applies on top of this at write time, in
     * uploadBatch/sendOverridePreset, because this is a routine like any other.
     */
    public static int shallowKpa(int ceilKpa) {
        int k = Math.min(20, ceilKpa);
        return k < 1 ? 1 : k;
    }

    /** The lower setpoint that goes with it — strictly below the upper, the same rule
     *  uploadBatch and RunEdit#clampLower apply. */
    public static int shallowLowKpa(int ceilKpa) {
        return RunEdit.clampLower(shallowKpa(ceilKpa) / 2, shallowKpa(ceilKpa));
    }

    /** The routine's id — not an `s`-prefixed library id, so it can never collide with a
     *  saved set, and the same convention Manual.ID follows. */
    public static final String ID = "hwtest";

    /** Set ids. One per shape the section needs; a stage may hold the same id more than
     *  once, which is exactly what V11 uses to overrun the batch. */
    public static final String SET_FIXED = "hwtest-fixed";
    public static final String SET_RAMP  = "hwtest-ramp";
    public static final String SET_SHORT = "hwtest-short";

    /** Which STAGE of the synthetic routine each vessel phase occupies. The driver reads
     *  this to know when a phase has begun, and the judges read it to filter the recorder's
     *  rows down to the phase being judged. V1, V12 and V13 are the driver's own — they run
     *  outside the routine's presets — and are -1 here. */
    public static int stageOf(String key) {
        if (V2.equals(key))  return 0;
        if (V3.equals(key))  return 1;
        if (V4.equals(key))  return 2;   // and 3 — see stageEndOf
        if (V5.equals(key))  return 4;
        if (V6.equals(key))  return 5;
        if (V7.equals(key))  return 6;
        if (V8.equals(key))  return 7;
        if (V9.equals(key))  return 8;
        if (V10.equals(key)) return 9;
        if (V11.equals(key)) return 10;
        return -1;
    }

    /** The LAST stage a phase occupies. Only V4 spans two — it is the phase about stage
     *  boundaries, so it needs a boundary to be about. */
    public static int stageEndOf(String key) {
        return V4.equals(key) ? 3 : stageOf(key);
    }

    /** How many stages the synthetic routine has. */
    public static final int STAGES = 11;

    /**
     * The three sets the section is built from, ready to be placed in model.adhoc so
     * Model#plan resolves them exactly as it resolves a library set. Every setpoint is
     * shallow and every one is clamped by Set#clamp before it is used, the same call
     * Model#plan makes for every set in every routine.
     *
     * The DURATION FLOOR is Model.Set#clamp's own 30 s — that is why nothing here is
     * shorter, and why V3's four steps come from a 40 s ramp rather than from four
     * ten-second sets.
     */
    public static List<Model.Set> sets(int ceilKpa) {
        int up = shallowKpa(ceilKpa), lo = shallowLowKpa(ceilKpa);
        List<Model.Set> out = new ArrayList<Model.Set>();
        Model.Set fixed = Model.Set.fixed(SET_FIXED, "Self-test hold", up, lo, 6, 4, 75, 30);
        fixed.clamp(ceilKpa);
        out.add(fixed);
        // A ramp whose FIVE fields all walk — the point of V3 is that every one of them
        // reaches the wire, so no two ends may be equal in any field.
        Model.Set ramp = Model.Set.ramp(SET_RAMP, "Self-test ramp",
                                        Math.max(1, up / 2), Math.max(0, lo / 2), 4, 3, 40,
                                        up, lo, 8, 6, 90, 4, 40);
        ramp.clamp(ceilKpa);
        out.add(ramp);
        // The batch-boundary set: an eight-step ramp so one STAGE can hold ten presets
        // without holding ten thirty-second sets.
        Model.Set shrt = Model.Set.ramp(SET_SHORT, "Self-test boundary",
                                        Math.max(1, up / 2), Math.max(0, lo / 2), 3, 2, 50,
                                        up, lo, 5, 3, 85, 8, 32);
        shrt.clamp(ceilKpa);
        out.add(shrt);
        return out;
    }

    /**
     * The synthetic routine. One stage per vessel phase that plays presets, so a phase's
     * rows can be filtered out of the recording by stage index alone rather than by a
     * time window that a hold or a +30 s would have moved.
     *
     * Built like Manual#routine: a throwaway Routine object that is NOT in model.routines,
     * so `r.runs++` on the run path increments something nobody keeps and the self-test
     * cannot inflate a routine's run count.
     */
    public static Model.Routine routine(int ceilKpa) {
        Model.Routine r = new Model.Routine();
        r.id = ID;
        r.name = "Hardware self-test";
        // No tissue-adaptation assessment: this is not a routine with a measured before and
        // after, and an assessment pull would be pressure this section never asked for.
        r.assess = new Model.Assess();
        r.assess.on = false;
        r.stages.add(Model.Stage.of("V2 fixed",     Model.STAGE_WORK, new String[]{ SET_FIXED }));
        r.stages.add(Model.Stage.of("V3 ramp",      Model.STAGE_WORK, new String[]{ SET_RAMP }));
        r.stages.add(Model.Stage.of("V4 stage A",   Model.STAGE_WARM, new String[]{ SET_FIXED }));
        r.stages.add(Model.Stage.of("V4 stage B",   Model.STAGE_COOL, new String[]{ SET_FIXED }));
        r.stages.add(Model.Stage.of("V5 adjust",    Model.STAGE_WORK, new String[]{ SET_FIXED }));
        r.stages.add(Model.Stage.of("V6 ramp adj",  Model.STAGE_WORK, new String[]{ SET_RAMP }));
        r.stages.add(Model.Stage.of("V7 revert",    Model.STAGE_WORK,
                                    new String[]{ SET_FIXED, SET_FIXED }));
        r.stages.add(Model.Stage.of("V8 hold",      Model.STAGE_WORK, new String[]{ SET_FIXED }));
        r.stages.add(Model.Stage.of("V9 extend",    Model.STAGE_WORK, new String[]{ SET_FIXED }));
        r.stages.add(Model.Stage.of("V10 skip",     Model.STAGE_WORK,
                                    new String[]{ SET_FIXED, SET_FIXED }));
        // Ten presets in one stage: an eight-step ramp plus two fixed sets. The batch is
        // eight (RunEdit#routineBatchSize keeps the ninth slot for the override), so this
        // stage is guaranteed to cross it.
        r.stages.add(Model.Stage.of("V11 boundary", Model.STAGE_WORK,
                                    new String[]{ SET_SHORT, SET_FIXED, SET_FIXED }));
        return r;
    }

    /** The number of presets the routine's V11 stage holds — pinned so the assertion that
     *  it actually crosses the batch is about a fact rather than an intention. */
    public static int presetsInStage(List<Model.Preset> plan, int stageIdx) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) if (plan.get(i).stageIdx == stageIdx) n++;
        return n;
    }

    /* ========================================================== §4 the action script ==== */

    /** What the driver does at a scripted moment. Each is the name of a method the USER'S
     *  OWN BUTTON calls — there is no action here that has a path of its own. */
    public static final int ACT_NONE = 0, ACT_APPLY = 1, ACT_HOLD = 2, ACT_RELEASE = 3,
                            ACT_SKIP = 4, ACT_EXTEND = 5, ACT_REVERT = 6;

    /**
     * One scripted action: at `afterStageMs` into the stage `stageIdx`, do `action`.
     *
     * TIMED FROM THE STAGE, not from the run: a hold, a +30 s and a skip all move the run's
     * own clock, so a script written against absolute elapsed time would drift out of the
     * phase it was written for by the time it reached the end. Stage-relative moments
     * cannot: the driver re-bases the clock every time the running preset's stage changes.
     */
    public static final class Act {
        public final int stageIdx;
        public final long afterStageMs;
        public final int action;
        /** Only for ACT_APPLY — the five values handed to applyOverride, before the
         *  ceiling clamp that method applies at write time. */
        public final int up, lo, uh, lh, sp;
        Act(int stageIdx, long afterStageMs, int action,
            int up, int lo, int uh, int lh, int sp) {
            this.stageIdx = stageIdx; this.afterStageMs = afterStageMs; this.action = action;
            this.up = up; this.lo = lo; this.uh = uh; this.lh = lh; this.sp = sp;
        }
        static Act at(int stage, long ms, int action) {
            return new Act(stage, ms, action, 0, 0, 0, 0, 0);
        }
    }

    /**
     * The whole script, in order. Every entry is one tap the user could have made, at a
     * moment chosen so the thing it is meant to prove is observable: an adjustment lands
     * mid-preset (so the countdown has somewhere to move to if it is going to), a skip
     * lands well under half the preset (so the recorder's own SKIPPED classification is
     * the one being exercised), a hold is released with time still on the clock.
     */
    public static Act[] script(int ceilKpa) {
        int up = shallowKpa(ceilKpa), lo = shallowLowKpa(ceilKpa);
        // The adjustment's own values: deliberately DIFFERENT from the plan's in every
        // field, so "the adjustment carried" and "the plan carried" can never be confused
        // for one another by a judge comparing values.
        int aUp = Math.max(1, up - 3), aLo = Math.max(0, Math.min(lo + 1, aUp - 1));
        List<Act> out = new ArrayList<Act>();
        // V4: adjust inside stage A, then let the stage boundary clear it. Nothing is
        // scripted in stage B — the point is that the adjustment does NOT reach it.
        out.add(new Act(2, 8000L, ACT_APPLY, aUp, aLo, 9, 5, 55));
        // V5: adjust mid-fixed. The countdown must not move.
        out.add(new Act(4, 10000L, ACT_APPLY, aUp, aLo, 9, 5, 55));
        // V6: adjust mid-ramp, on the second of four steps. It must replace the steps that
        // remain rather than being overwritten by the next PLAN row.
        out.add(new Act(5, 12000L, ACT_APPLY, aUp, aLo, 9, 5, 55));
        // V7: adjust, then revert. The next row must be the routine's own values again.
        out.add(new Act(6, 8000L,  ACT_APPLY, aUp, aLo, 9, 5, 55));
        out.add(Act.at(6, 20000L, ACT_REVERT));
        // V8: hold, then resume with time still on the clock.
        out.add(Act.at(7, 8000L,  ACT_HOLD));
        out.add(Act.at(7, 18000L, ACT_RELEASE));
        // V9: one +30 s on the open row, with an adjustment carrying so the wire hold has
        // something to refresh.
        out.add(new Act(8, 6000L, ACT_APPLY, aUp, aLo, 9, 5, 55));
        out.add(Act.at(8, 12000L, ACT_EXTEND));
        // V10: skip the first preset of the stage well under half of it, so the recorder
        // classifies it SKIPPED rather than "cut short".
        out.add(Act.at(9, 6000L, ACT_SKIP));
        Act[] a = new Act[out.size()];
        for (int i = 0; i < out.size(); i++) a[i] = out.get(i);
        return a;
    }

    /* ============================================================== §5 the judges ==== */

    /**
     * Everything the judges need that does NOT come from the recorder: the telemetry the
     * Android side observed, and the handful of facts only the live driver can know (did
     * the countdown move, did the vent watch resolve, did the wire hold get refreshed).
     *
     * All defaults are the FAILING answer. A field the driver forgot to fill must not read
     * as a pass — that is the one failure mode a self-test may never have.
     */
    public static final class Stats {
        /** V1 / V13: the vent watch resolved as VENTED or VENTED_INFERRED. */
        public boolean vented;
        /** V1: Validate#fallRate over the first three seconds, kPa/s. NaN if not measured. */
        public double fallRateKpaS = Double.NaN;
        /** V2: the deepest valid reading the phase saw, and what was commanded. */
        public double reachedKpa = Double.NaN;
        public int targetKpa;
        /** V5: RunEdit#countdownPreserved's answer, taken across the apply. */
        public boolean countdownPreserved;
        /** V8: whether an adjustment carried into the held preset, which is what decides
         *  the row kind the release must produce (RunEdit#resumeSlotAfterHold). */
        public boolean holdCarried;
        /** V9: the carried adjustment's full hold was re-sent after the extension. */
        public boolean holdRefreshed;
        /** V12: the calibration numbers. NaN / -1 when not measured. */
        public long timeToTargetMs = -1L;
        public double coastKpaS = Double.NaN;
        /** The batch size in force, so V11 judges against the arithmetic actually used. */
        public int batchSize = RunEdit.routineBatchSize(Proto.SLOTS);
    }

    /** How far apart two consecutive rows may sit at the batch boundary. Two seconds: the
     *  re-upload posts its own 600 ms Advance and nine deletes plus eight adds precede it,
     *  so a boundary that is working is well inside this, and one that is not — a table
     *  written but never started — is far outside it. */
    public static final long BOUNDARY_GAP_MS = 2000L;

    /** V2's plateau tolerance: the pressure must arrive at the target, not near it. */
    public static final double TARGET_TOL_KPA = 1.0;

    /** One phase's answer. `skipped` is the third state — the phase never ran, which is a
     *  dash on the report and is not a failure of anything. */
    public static final class Verdict {
        public final boolean pass, skipped;
        public final String evidence;
        Verdict(boolean pass, boolean skipped, String evidence) {
            this.pass = pass; this.skipped = skipped; this.evidence = evidence;
        }
        /** The report's glyph. ✓ is reserved for an EVIDENCED pass and nothing else. */
        public String mark() { return skipped ? "—" : (pass ? "✓" : "✗"); }
    }

    public static Verdict pass(String why) { return new Verdict(true, false, why); }
    public static Verdict fail(String why) { return new Verdict(false, false, why); }
    public static Verdict skip(String why) { return new Verdict(false, true, why); }

    /* -------------------------------------------------------------- row helpers --- */

    /** The recorder's rows for one stage, in order. */
    public static List<AsRun.Row> rowsIn(List<AsRun.Row> rows, int stageIdx) {
        return rowsIn(rows, stageIdx, stageIdx);
    }

    /** The recorder's rows across a range of stages, in order. */
    public static List<AsRun.Row> rowsIn(List<AsRun.Row> rows, int fromStage, int toStage) {
        List<AsRun.Row> out = new ArrayList<AsRun.Row>();
        if (rows == null) return out;
        for (int i = 0; i < rows.size(); i++) {
            AsRun.Row r = rows.get(i);
            if (r.stageIdx >= fromStage && r.stageIdx <= toStage) out.add(r);
        }
        return out;
    }

    /**
     * THE RULE THAT MAKES EVERY VERDICT EVIDENCED: a row the pump never acknowledged is a
     * failure of the phase it belongs to. Returns the index of the first such row, or -1.
     *
     * This is not a warning and is deliberately not softened anywhere below. `confirmed`
     * is the START-ack matcher's own answer (awaitStartAck pairs the note with the STARTING
     * frame, not with the Add), so an unconfirmed row means the app believes it changed the
     * pump and has no evidence that it did — which is precisely the class of defect this
     * whole routine exists to catch.
     */
    public static int firstUnconfirmed(List<AsRun.Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            AsRun.Row r = rows.get(i);
            if (r.hasValues() && !r.confirmed) return i;
        }
        return -1;
    }

    /** Rows that DELIVERED set values — what a phase actually put on the pump. A HOLD, a
     *  LOST, a VENT and a SKIPPED carry no set values and are excluded by Row#hasValues,
     *  which is the same rule the as-run card's blocks use. */
    public static List<AsRun.Row> delivered(List<AsRun.Row> rows) {
        List<AsRun.Row> out = new ArrayList<AsRun.Row>();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).hasValues()) out.add(rows.get(i));
        return out;
    }

    public static int countKind(List<AsRun.Row> rows, int kind) {
        int n = 0;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).kind == kind) n++;
        return n;
    }

    /** The largest gap between the end of one row and the start of the next, in ms. -1 when
     *  there are fewer than two closed rows to compare. */
    public static long largestGapMs(List<AsRun.Row> rows) {
        long worst = -1L;
        for (int i = 0; i + 1 < rows.size(); i++) {
            AsRun.Row a = rows.get(i), b = rows.get(i + 1);
            if (a.t1 < a.t0) continue;                 // still open — no gap to measure
            long gap = b.t0 - a.t1;
            if (gap > worst) worst = gap;
        }
        return worst;
    }

    /* ------------------------------------------------------------- the dispatch --- */

    /**
     * THE ONE ENTRY POINT. `rows` is the WHOLE recording; each judge filters it down to its
     * own stage itself, so a caller cannot hand a judge the wrong slice.
     */
    public static Verdict judge(String key, List<AsRun.Row> rows, Stats st) {
        if (st == null) st = new Stats();
        if (rows == null) rows = new ArrayList<AsRun.Row>();
        if (V1.equals(key))  return judgeVent(st, "vent proof");
        if (V2.equals(key))  return judgeFixed(rows, st);
        if (V3.equals(key))  return judgeRamp(rows);
        if (V4.equals(key))  return judgeStages(rows);
        if (V5.equals(key))  return judgeAdjust(rows, st);
        if (V6.equals(key))  return judgeRampAdjust(rows);
        if (V7.equals(key))  return judgeRevert(rows);
        if (V8.equals(key))  return judgeHold(rows, st);
        if (V9.equals(key))  return judgeExtend(rows, st);
        if (V10.equals(key)) return judgeSkip(rows);
        if (V11.equals(key)) return judgeBoundary(rows, st);
        if (V12.equals(key)) return judgeSealCal(st);
        if (V13.equals(key)) return judgeVent(st, "closing vent");
        return skip("no judge for " + key);
    }

    /** V1 and V13. The same question — did the pump vent and did anything evidence it —
     *  asked at the two ends of the section, so it is one judge and not two. */
    static Verdict judgeVent(Stats st, String what) {
        if (!st.vented)
            return fail(what + ": the vent watch never resolved as vented or inferred");
        return pass(what + ": vented, evidenced" + (Double.isNaN(st.fallRateKpaS) ? ""
            : " — fall rate " + rate(st.fallRateKpaS)));
    }

    /** V2. Every preset START-acked, and the pressure actually arrived. */
    static Verdict judgeFixed(List<AsRun.Row> all, Stats st) {
        List<AsRun.Row> rows = delivered(rowsIn(all, stageOf(V2)));
        if (rows.isEmpty()) return skip("the fixed stage never ran");
        int bad = firstUnconfirmed(rows);
        if (bad >= 0)
            return fail("row " + bad + " of " + rows.size() + " was never acknowledged by "
                      + "the pump — the app changed the preset with no evidence it landed");
        if (Double.isNaN(st.reachedKpa))
            return fail(rows.size() + " row(s) acknowledged, but no valid reading arrived — "
                      + "an ack is not a pressure");
        /* SHORT IS A FAILURE; DEEP IS NOT.
         *
         * Math.abs made this two-sided, so a pump that pulled PAST the commanded figure -
         * which the hardware does, and which the app's own reachedCommanded test treats as
         * reaching it - was reported as a failed self-test. The bound this row exists for is
         * "did it get there", and a healthy overshoot got there. */
        double miss = st.targetKpa - st.reachedKpa;
        if (miss > TARGET_TOL_KPA)
            return fail(rows.size() + " row(s) acknowledged, but the plateau reached "
                      + kpa(st.reachedKpa) + " against " + st.targetKpa + " kPa commanded ("
                      + kpa(miss) + " short)");
        return pass(rows.size() + " row(s), all acknowledged; plateau " + kpa(st.reachedKpa)
                  + " against " + st.targetKpa + " kPa commanded");
    }

    /** V3. Four steps, every one acked, and all five fields walking across them. A ramp
     *  whose speed never changes is a ramp with a field that never reached the wire. */
    static Verdict judgeRamp(List<AsRun.Row> all) {
        List<AsRun.Row> rows = delivered(rowsIn(all, stageOf(V3)));
        if (rows.isEmpty()) return skip("the ramp stage never ran");
        int bad = firstUnconfirmed(rows);
        if (bad >= 0) return fail("ramp step " + bad + " was never acknowledged by the pump");
        if (rows.size() < 2)
            return fail("only " + rows.size() + " ramp step(s) were recorded — a ramp needs "
                      + "at least two for a field to walk at all");
        AsRun.Row a = rows.get(0), z = rows.get(rows.size() - 1);
        String stuck = "";
        if (a.up == z.up)    stuck += (stuck.isEmpty() ? "" : ", ") + "upper";
        if (a.lo == z.lo)    stuck += (stuck.isEmpty() ? "" : ", ") + "lower";
        if (a.uhReq == z.uhReq) stuck += (stuck.isEmpty() ? "" : ", ") + "hold";
        if (a.lh == z.lh)    stuck += (stuck.isEmpty() ? "" : ", ") + "drop";
        if (a.sp == z.sp)    stuck += (stuck.isEmpty() ? "" : ", ") + "speed";
        if (!stuck.isEmpty())
            return fail(rows.size() + " step(s) acknowledged, but these never moved between "
                      + "the first step and the last: " + stuck);
        return pass(rows.size() + " step(s), all acknowledged; all five fields walked — "
                  + a.up + "→" + z.up + " / " + a.lo + "→" + z.lo + " kPa, "
                  + a.uhReq + "→" + z.uhReq + " / " + a.lh + "→" + z.lh + " s, "
                  + a.sp + "→" + z.sp + "%");
    }

    /** V4. Two stages, grouped as the plan groups them, with the adjustment cleared at the
     *  boundary — an override that leaked into the second stage would mean the carry rule
     *  is keyed on something other than the stage. */
    static Verdict judgeStages(List<AsRun.Row> all) {
        int s0 = stageOf(V4), s1 = stageEndOf(V4);
        List<AsRun.Row> first  = delivered(rowsIn(all, s0));
        List<AsRun.Row> second = delivered(rowsIn(all, s1));
        if (first.isEmpty() || second.isEmpty())
            return skip("the two-stage phase did not reach both stages");
        int bad = firstUnconfirmed(first);
        if (bad < 0) { bad = firstUnconfirmed(second); if (bad >= 0) bad += first.size(); }
        if (bad >= 0) return fail("row " + bad + " across the two stages was never acknowledged");
        int leaked = countKind(second, AsRun.OVERRIDE);
        if (leaked > 0)
            return fail("the adjustment made in the first stage carried into the second: "
                      + leaked + " OVERRIDE row(s) there, where the plan's own values were "
                      + "due");
        if (countKind(first, AsRun.OVERRIDE) == 0)
            return fail("no adjustment was recorded in the first stage, so the boundary was "
                      + "never actually asked to clear one");
        return pass("blocks grouped as the plan does — " + first.size() + " row(s) in the "
                  + "first stage (adjustment included), " + second.size() + " in the second, "
                  + "all PLAN: the adjustment was cleared at the boundary");
    }

    /** V5. The override is acked AND the countdown did not move. Both, because an override
     *  that shifts the clock is a correctness defect the pump would never report. */
    static Verdict judgeAdjust(List<AsRun.Row> all, Stats st) {
        List<AsRun.Row> rows = delivered(rowsIn(all, stageOf(V5)));
        if (rows.isEmpty()) return skip("the adjustment stage never ran");
        int bad = firstUnconfirmed(rows);
        if (bad >= 0) return fail("the adjustment row was never acknowledged by the pump");
        int ov = countKind(rows, AsRun.OVERRIDE);
        if (ov == 0) return fail("no OVERRIDE row was recorded — the adjustment never landed");
        if (!st.countdownPreserved)
            return fail("the adjustment was acknowledged, but it MOVED the countdown — an "
                      + "override changes pressure, not the clock");
        return pass(ov + " OVERRIDE row(s) acknowledged, and the countdown did not move");
    }

    /** V6. The adjustment replaces what remains of the stage: once it lands, no PLAN row
     *  may follow it inside that stage. */
    static Verdict judgeRampAdjust(List<AsRun.Row> all) {
        List<AsRun.Row> rows = delivered(rowsIn(all, stageOf(V6)));
        if (rows.isEmpty()) return skip("the ramp-adjustment stage never ran");
        int bad = firstUnconfirmed(rows);
        if (bad >= 0) return fail("row " + bad + " of the ramp adjustment was never acknowledged");
        int at = -1;
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).kind == AsRun.OVERRIDE) { at = i; break; }
        if (at < 0) return fail("no OVERRIDE row was recorded inside the ramp");
        for (int i = at + 1; i < rows.size(); i++)
            if (rows.get(i).kind == AsRun.PLAN)
                return fail("the ramp went back to its PLAN values at row " + i + ", after "
                          + "the adjustment at row " + at + " — the adjustment must carry "
                          + "for the rest of the stage");
        return pass("the adjustment landed at row " + at + " of " + rows.size()
                  + " and carried: no PLAN row followed it inside the stage");
    }

    /** V7. Revert puts the routine's own values back, on the very next row. */
    static Verdict judgeRevert(List<AsRun.Row> all) {
        List<AsRun.Row> rows = delivered(rowsIn(all, stageOf(V7)));
        if (rows.isEmpty()) return skip("the revert stage never ran");
        int bad = firstUnconfirmed(rows);
        if (bad >= 0) return fail("row " + bad + " of the revert stage was never acknowledged");
        int at = -1;
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).kind == AsRun.OVERRIDE) { at = i; break; }
        if (at < 0) return fail("no OVERRIDE row was recorded, so nothing was reverted");
        if (at + 1 >= rows.size())
            return fail("the adjustment was the last row of the stage — the revert produced "
                      + "no row at all");
        AsRun.Row next = rows.get(at + 1);
        if (next.kind != AsRun.PLAN)
            return fail("the row after the revert is not a PLAN row — the routine's own "
                      + "values did not come back");
        AsRun.Row ov = rows.get(at);
        if (next.sameValues(ov))
            return fail("the row after the revert carries the ADJUSTMENT's values, not the "
                      + "plan's — it is a PLAN row in name only");
        return pass("the row after the revert is PLAN with the plan's own values — "
                  + next.up + "/" + next.lo + " kPa, " + next.uhReq + "/" + next.lh + " s, "
                  + next.sp + "%");
    }

    /** V8. A hold delivers nothing, and the release resumes whatever was in force before it
     *  — RunEdit#resumeSlotAfterHold's own decision, restated as the row kind it implies. */
    static Verdict judgeHold(List<AsRun.Row> all, Stats st) {
        List<AsRun.Row> rows = rowsIn(all, stageOf(V8));
        if (rows.isEmpty()) return skip("the hold stage never ran");
        int at = -1;
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).kind == AsRun.HOLD) { at = i; break; }
        if (at < 0) return fail("no HOLD row was recorded — the hold never reached the pump");
        List<AsRun.Row> gave = delivered(rows);
        for (int i = 0; i < gave.size(); i++)
            if (gave.get(i).kind == AsRun.HOLD)
                return fail("a HOLD row appeared among the delivered rows — a hold carries "
                          + "no set values and must never count as one");
        int bad = firstUnconfirmed(gave);
        if (bad >= 0) return fail("delivered row " + bad + " of the hold stage was never "
                                + "acknowledged");
        if (at + 1 >= rows.size())
            return fail("the hold was the last row of the stage — it was never released");
        int want = st.holdCarried ? AsRun.OVERRIDE : AsRun.PLAN;
        AsRun.Row next = rows.get(at + 1);
        if (next.kind != want)
            return fail("the release resumed the wrong thing: the row after the hold is "
                      + kindName(next.kind) + ", and RunEdit.resumeSlotAfterHold("
                      + st.holdCarried + ") says it must be " + kindName(want));
        return pass("the HOLD row is excluded from the " + gave.size() + " delivered row(s), "
                  + "and the release resumed a " + kindName(want) + " row as "
                  + "RunEdit.resumeSlotAfterHold(" + st.holdCarried + ") requires");
    }

    /** V9. Exactly one extension, on the open row, and the carried hold refreshed with it. */
    static Verdict judgeExtend(List<AsRun.Row> all, Stats st) {
        List<AsRun.Row> rows = delivered(rowsIn(all, stageOf(V9)));
        if (rows.isEmpty()) return skip("the extend stage never ran");
        int bad = firstUnconfirmed(rows);
        if (bad >= 0) return fail("row " + bad + " of the extend stage was never acknowledged");
        int extended = 0;
        long total = 0L;
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).extendMs > 0) { extended++; total += rows.get(i).extendMs; }
        if (extended == 0)
            return fail("no row carries an extension — the +30 s was never recorded");
        if (extended > 1)
            return fail(extended + " rows carry an extension; one tap must land on exactly "
                      + "one row (the open one)");
        if (total != RunEdit.EXTEND_MS)
            return fail("the recorded extension is " + total + " ms, and one tap is "
                      + RunEdit.EXTEND_MS + " ms");
        if (!st.holdRefreshed)
            return fail("the extension was recorded, but the carried adjustment's hold was "
                      + "not re-sent — the pump keeps cycling on the shorter hold it was "
                      + "given before the step grew");
        return pass("one extension of " + RunEdit.EXTEND_MS + " ms on the open row, and the "
                  + "carried adjustment's full hold was refreshed on the wire");
    }

    /** V10. Under half of what it planned to run, so the recorder's own rule classifies it
     *  SKIPPED — and the next preset starts, because skip is the existing sequencing
     *  arriving early and not a parallel one. */
    static Verdict judgeSkip(List<AsRun.Row> all) {
        List<AsRun.Row> rows = rowsIn(all, stageOf(V10));
        if (rows.isEmpty()) return skip("the skip stage never ran");
        int at = -1;
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).kind == AsRun.SKIPPED) { at = i; break; }
        if (at < 0)
            return fail("no SKIPPED row was recorded — the preset was cut short of half its "
                      + "plan, so AsRun.skip must have classified it SKIPPED");
        if (at + 1 >= rows.size())
            return fail("the skip produced no following row — the next preset never started");
        AsRun.Row next = rows.get(at + 1);
        if (!next.hasValues())
            return fail("the row after the skip carries no set values — the next preset never "
                      + "started");
        if (!next.confirmed)
            return fail("the preset after the skip was never acknowledged by the pump");
        return pass("the cut-short preset was classified SKIPPED, and the next preset started "
                  + "and was acknowledged");
    }

    /** V11. A plan longer than the batch: no unconfirmed row anywhere, and no silence at
     *  the crossing. A re-upload that writes the table but never starts it shows up here
     *  as a gap, not as an error anyone would otherwise see. */
    static Verdict judgeBoundary(List<AsRun.Row> all, Stats st) {
        List<AsRun.Row> rows = delivered(rowsIn(all, stageOf(V11)));
        if (rows.isEmpty()) return skip("the batch-boundary stage never ran");
        if (rows.size() <= st.batchSize)
            return fail("only " + rows.size() + " row(s) were recorded against a batch of "
                      + st.batchSize + " — the plan never crossed the boundary, so nothing "
                      + "was tested");
        int bad = firstUnconfirmed(rows);
        if (bad >= 0)
            return fail("row " + bad + " of " + rows.size() + " was never acknowledged — the "
                      + "batch boundary lost a preset");
        long gap = largestGapMs(rows);
        if (gap > BOUNDARY_GAP_MS)
            return fail("the largest gap between consecutive rows is " + gap + " ms, over the "
                      + BOUNDARY_GAP_MS + " ms bound — the table was rewritten and the next "
                      + "preset was late starting");
        return pass(rows.size() + " row(s) across a batch of " + st.batchSize
                  + ", all acknowledged; largest gap " + (gap < 0 ? 0 : gap) + " ms");
    }

    /**
     * V12. NUMBERS ONLY. This is the one phase with no verdict about the pump, for the same
     * reason the whole of Validate has none: nothing in this repo grounds a reference for
     * either figure. It passes when it MEASURED them and fails only when it could not — a
     * calibration that produced no numbers is a calibration that did not happen.
     */
    static Verdict judgeSealCal(Stats st) {
        boolean haveT = st.timeToTargetMs >= 0;
        boolean haveC = !Double.isNaN(st.coastKpaS);
        if (!haveT && !haveC)
            return fail("neither figure was measured — the seal check produced no plateau and "
                      + "no coast window on this vessel");
        String said = "on this vessel: time to target at 100% "
            + (haveT ? Validate.mmss(st.timeToTargetMs) : "not measured")
            + ", coast " + (haveC ? rate(st.coastKpaS) : "not measured")
            + " — reported, not judged";
        return (haveT && haveC) ? pass(said) : fail(said + " (one of the two is missing)");
    }

    static String kindName(int kind) {
        switch (kind) {
            case AsRun.PLAN:     return "PLAN";
            case AsRun.OVERRIDE: return "OVERRIDE";
            case AsRun.HOLD:     return "HOLD";
            case AsRun.LOST:     return "LOST";
            case AsRun.VENT:     return "VENT";
            case AsRun.SKIPPED:  return "SKIPPED";
            default:             return "kind " + kind;
        }
    }

    /* ============================================================== §6 the report ==== */

    /** One line of the report, ready for the screen AND for the log — the same string in
     *  both, so "Save to log" cannot record something other than what was shown. */
    public static String reportLine(Ph p, Verdict v) {
        return v.mark() + "  " + p.key + " " + p.label;
    }

    /** The evidence line that goes under it, dim. A phase that never ran says what it WOULD
     *  have proved, so a dash is still informative. */
    public static String evidenceLine(Ph p, Verdict v) {
        if (v == null) return "would have proved: " + p.proves;
        if (v.skipped) return v.evidence + " — would have proved: " + p.proves;
        return v.evidence;
    }

    /** The report's one-sentence headline: how many phases were evidenced, how many failed,
     *  how many never ran. Never rounded up into "all good" — a skipped phase is named. */
    public static String headline(int passed, int failed, int skipped) {
        StringBuilder sb = new StringBuilder();
        sb.append(passed).append(" evidenced");
        if (failed > 0) sb.append(", ").append(failed).append(" FAILED");
        if (skipped > 0) sb.append(", ").append(skipped).append(" did not run");
        if (failed == 0 && skipped == 0) sb.append(" — every phase proved what it claims");
        return sb.toString();
    }

    /** kPa/s for the log and the evidence lines: always kPa, never the display unit. This
     *  file records what the wire carried; the unit setting is display-only. */
    static String rate(double kpaPerSec) {
        return Double.isNaN(kpaPerSec) ? "not measurable"
                                       : String.format(Locale.US, "%.2f kPa/s", kpaPerSec);
    }

    static String kpa(double v) {
        return Double.isNaN(v) ? "no reading" : String.format(Locale.US, "%.1f kPa", v);
    }
}
