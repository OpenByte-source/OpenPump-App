package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * COMING STEPS - the run screen's sheet for changing what is still to come, for THIS RUN
 * ONLY (0.10 run-screen redesign). The rules, and the list surgery, as pure code.
 *
 * WHAT IT MAY CHANGE, and nothing else - and only in a step AFTER the one playing (0.10
 * final: the step playing is changed with the run screen's − / + strip, and the sheet lists
 * it read-only):
 *   - a work block's number of SETS (pump cycles), 1 to 15;
 *   - its HOLD, 15 s at a time, between 1:00 and the wire's longest (a hold planned under
 *     1:00 can only get longer);
 *   - its DROP: the drop pressure 1 kPa at a time - above {@link #DROP_MAX_KPA} only as the
 *     person's own call (asked once, RunEdit#dropNeedsWarning) and never past 1.0 inHg under
 *     the pull (RunEdit#dropTopKpa); its seconds never count as time at pressure whatever its
 *     pressure (they are left out by phase); the drop time 1 s at a time, 0 to 0:30;
 *   - a rest's length, 30 s at a time, between 0:30 and 10:00;
 *   - a ramp's number of STEPS (2 to 9, the pump's table), re-spread between the pressures
 *     it already climbs between, and its time per step - in WHOLE CYCLES of each step's own
 *     hold and drop, one cycle at a time, never under one cycle and never past a step's
 *     share of the hour (Model.Set#rampStepMaxSec: the app's own clock, not the wire's 255 s,
 *     which is one hold or one drop, never a step);
 *   - whether a LATER step runs at all (skip / undo skip).
 *
 * IT NEVER RAISES THE PULL. A set count, a rest and a time per step are durations (the app's
 * own advance clock, not a wire value), a hold is seconds, and a skip only takes steps out. A
 * drop is the one pressure here, and it is held 1.0 inHg under the pull once it is above the
 * floor ({@link #applyDrop}); a ramp re-spread interpolates between the pulls the ramp already has
 * and so never passes the higher of them ({@link #respreadRamp}). The only road to a
 * different pull stays the run screen's own controls. WiringCheck holds the app's apply
 * methods to that.
 *
 * EVERY CAP STILL APPLIES: nothing may make the plan longer than the two-hour stop
 * (Plan#GROSS_CAP_SEC), nor lengthen a block past the twenty-minute set advisory
 * (Plan#SET_ADVISORY_SEC) - a block already planned longer can only be shortened.
 */
public final class ComingSteps {
    private ComingSteps() { }

    public static final int HOLD_STEP_SEC = 15;
    public static final int HOLD_MIN_SEC = 60;
    /** 5 minutes as asked, but never past what one preset can hold on the wire. */
    public static final int HOLD_MAX_SEC = Math.min(300, Proto.WIRE_HOLD_MAX);
    public static final int REST_STEP_SEC = 30;
    public static final int REST_MIN_SEC = 30;
    public static final int REST_MAX_SEC = 600;
    /** A block's sets, 1 to this. */
    public static final int SETS_MAX = 15;
    /** THE DROP'S USUAL TOP: 10 kPa (−3.0 inHg), the pump presets' own rule. Past it is the
     *  person's call, asked once (RunEdit#dropNeedsWarning), up to RunEdit#dropTopKpa; a NEW
     *  drop given to a hold-only block still starts here ({@link #applyDropTime}). */
    public static final int DROP_MAX_KPA = 10;
    public static final int DROP_STEP_KPA = 1;
    public static final int DROP_TIME_STEP_SEC = 1;
    public static final int DROP_TIME_MAX_SEC = 30;
    /** A ramp's steps: at least two, at most what the pump's table holds. */
    public static final int RAMP_STEPS_MIN = 2;
    public static final int RAMP_STEPS_MAX = Proto.SLOTS;
    public static final int STEP_TIME_STEP_SEC = 5;
    public static final int STEP_TIME_MIN_SEC = 15;
    /** THE LONGEST STEP OF ANY RAMP: the two-step ramp's share of the hour. Each ramp is held to
     *  its own share (Model.Set#rampStepMaxSec, 3600 s ÷ its steps). It was the wire's 255 s,
     *  which is the width of one hold or one drop - a step's length is the app's clock. */
    public static final int STEP_TIME_MAX_SEC = Model.Set.rampStepMaxSec(RAMP_STEPS_MIN);
    /** A running rest shortened is never left with less than this. */
    public static final long REST_LEFT_FLOOR_MS = 5_000L;

    public static final long CAP_MS = (long) (Plan.GROSS_CAP_SEC * 1000L);
    public static final long ADVISORY_MS = Plan.SET_ADVISORY_SEC * 1000L;

    /* ------------------------------------------------------------------ the cards */

    /**
     * A CARD IS A BLOCK, NOT A STAGE (0.10 final, device check). A stage is the person's own
     * grouping and may hold several sets - "Work" holding a block of five sets and a ramp - and
     * Coming steps must list and change each of them on its own. A block is one set's place in
     * its stage: the presets of one (stage, position) - Model.Preset#stageIdx and #pos. Its key
     * is the stage in the low 16 bits and the position above them, so the key of a stage's
     * first (or only) set IS the stage's own number, and every rule below that is handed a
     * stage number of a one-set stage reads it as before.
     */
    public static int key(int stageIdx, int pos) {
        return (Math.max(0, stageIdx) & 0xFFFF) | (Math.max(0, pos) << 16);
    }

    /** The key of the block `p` belongs to. */
    public static int key(Model.Preset p) {
        return key(p.stageIdx, p.pos);
    }

    /** The stage a block is in. */
    public static int stageOf(int key) { return key & 0xFFFF; }

    /** Its place in the stage. */
    public static int posOf(int key) { return key >>> 16; }

    /** Is `p` one of block `key`'s presets? */
    public static boolean inBlock(Model.Preset p, int key) {
        return p != null && p.stageIdx == stageOf(key) && p.pos == posOf(key);
    }

    /** Does block `a` come after block `b` in the run? (By stage, then by place in it.) */
    public static boolean after(int a, int b) {
        return stageOf(a) != stageOf(b) ? stageOf(a) > stageOf(b) : posOf(a) > posOf(b);
    }

    /** The blocks from the one at `planIdx` on, in the order they run, each once - plus the
     *  keys in `extra` (skipped blocks, no longer in the plan) in their places. */
    public static List<Integer> blocksFrom(List<Model.Preset> plan, int planIdx,
                                           java.util.Collection<Integer> extra) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = Math.max(0, planIdx); plan != null && i < plan.size(); i++) {
            Integer k = Integer.valueOf(key(plan.get(i)));
            if (!out.contains(k)) out.add(k);
        }
        if (extra != null)
            for (Integer k : extra) {
                if (out.contains(k)) continue;
                int at = out.size();
                for (int j = 0; j < out.size(); j++)
                    if (after(out.get(j).intValue(), k.intValue())) { at = j; break; }
                out.add(at, k);
            }
        return out;
    }

    /**
     * THE PRESETS CARD `key` READS ITS FIGURES FROM: the plan's, or - for a skipped block - the
     * ones the skip took out (`skipped`, by key). A skipped card is still drawn in its place
     * with the figures it had, and its presets are no longer in the plan: read there, a block
     * was not found (-1) and the sheet's redraw after the skip crashed the run (device check,
     * IndexOutOfBounds in the Coming steps sub line). Every per-card plan lookup goes through
     * this.
     */
    public static List<Model.Preset> cardPresets(List<Model.Preset> plan,
            java.util.Map<Integer, List<Model.Preset>> skipped, int key) {
        List<Model.Preset> out = skipped == null ? null : skipped.get(Integer.valueOf(key));
        if (out != null) return out;
        return plan != null ? plan : new ArrayList<Model.Preset>();
    }

    /** The sum of every preset's length. */
    public static long totalMs(List<Model.Preset> plan) {
        long t = 0;
        if (plan != null) for (int i = 0; i < plan.size(); i++) t += Math.max(0L, plan.get(i).durMs);
        return t;
    }

    /** Refused when putting back `deltaMs` of plan (an undone skip, a longer inserted rest)
     *  would take it past the two-hour stop; null when it may. */
    public static String addRefusal(long totalBeforeMs, long deltaMs) {
        return capRefusal(totalBeforeMs, deltaMs);
    }

    /** Refused when a change that ADDS time would take the plan past the two-hour stop. */
    static String capRefusal(long totalBeforeMs, long deltaMs) {
        if (deltaMs > 0 && totalBeforeMs + deltaMs > CAP_MS)
            return "The routine would pass 2 hours.";
        return null;
    }

    /* ------------------------------------------------------------------ the blocks */

    /**
     * The plan index of stage `s`'s ONE preset when the stage is a single block the pump
     * repeats (sets can be counted and changed), or -1. A stage of several presets - a ramp,
     * a stitched long hold, overridden repetitions - is not offered: changing one of its
     * presets would not be "one more set".
     */
    public static int blockOf(List<Model.Preset> plan, int s) {
        int found = -1;
        for (int i = 0; i < plan.size(); i++) {
            if (!inBlock(plan.get(i), s)) continue;
            if (found >= 0) return -1;
            found = i;
        }
        if (found < 0) return -1;
        return RunLook.cycleMs(plan.get(found)) > 0 ? found : -1;
    }

    /** The plan index of rest stage `s`'s one preset, or -1. */
    public static int restOf(List<Model.Preset> plan, int s) {
        int found = -1;
        for (int i = 0; i < plan.size(); i++) {
            if (!inBlock(plan.get(i), s)) continue;
            if (found >= 0 || !plan.get(i).rest) return -1;
            found = i;
        }
        return found;
    }

    /** The fewest sets a block may be cut to: the one playing plus… nothing, i.e. the set
     *  under way always finishes; an upcoming block keeps at least one. */
    public static int minSets(boolean current, long elapsedInPresetMs, long cycleMs) {
        if (!current || cycleMs <= 0) return 1;
        return (int) (Math.max(0L, elapsedInPresetMs) / cycleMs) + 1;
    }

    /** Why a block of `sets` sets of `cycleMs` may not become `newSets`, or null. */
    public static String setsRefusal(int sets, int newSets, int minSets, long cycleMs,
                                     long planTotalMs) {
        if (cycleMs <= 0) return "This step's sets can't be counted.";
        if (newSets == sets) return "Nothing to change.";
        if (newSets < minSets)
            return minSets > 1 ? "Set " + (minSets - 1) + " is under way — it can't be taken out."
                               : "At least one set.";
        if (newSets > sets && newSets > SETS_MAX) return SETS_MAX + " sets is the most.";
        if (newSets > sets && (long) newSets * cycleMs > ADVISORY_MS)
            return advisory();
        return capRefusal(planTotalMs, (long) (newSets - sets) * cycleMs);
    }

    /** The twenty-minute set advisory, said. */
    static String advisory() {
        return "No block runs past " + Model.Fmt.t(Plan.SET_ADVISORY_SEC) + " — the set advisory.";
    }

    /** The hold one tap of `deltaSec` takes an upcoming block to: a hold planned under 1:00
     *  that is lengthened goes to at least 1:00. */
    public static int nextHold(int uh, int deltaSec) {
        int h = uh + deltaSec;
        if (deltaSec > 0 && uh < HOLD_MIN_SEC) h = Math.max(HOLD_MIN_SEC, h);
        return h;
    }

    /** Why an upcoming block's hold may not go from `uh` to `newUh` seconds (its sets and
     *  drop kept), or null. */
    public static String holdRefusal(int uh, int newUh, int lh, int sets, long planTotalMs) {
        if (newUh == uh) return "Nothing to change.";
        if (newUh < HOLD_MIN_SEC && newUh < uh)
            return uh < HOLD_MIN_SEC ? "Holds under " + Model.Fmt.t(HOLD_MIN_SEC)
                                       + " can only get longer."
                                     : Model.Fmt.t(HOLD_MIN_SEC) + " is the shortest hold.";
        if (newUh > HOLD_MAX_SEC && newUh > uh)
            return Model.Fmt.t(HOLD_MAX_SEC) + " is the longest hold the pump takes.";
        long before = (long) sets * (uh + Math.max(0, lh)) * 1000L;
        long after = (long) sets * (newUh + Math.max(0, lh)) * 1000L;
        if (after > before && after > ADVISORY_MS) return advisory();
        return capRefusal(planTotalMs, after - before);
    }

    /** Why a rest may not go from `sec` to `newSec`, or null. `leftMs` is what a RUNNING
     *  rest has left (ignored, pass -1, for one still to come). */
    public static String restRefusal(int sec, int newSec, long leftMs, long planTotalMs) {
        if (newSec == sec) return "Nothing to change.";
        if (newSec < sec && newSec < REST_MIN_SEC)
            return Model.Fmt.t(REST_MIN_SEC) + " is the shortest rest.";
        if (newSec > sec && newSec > REST_MAX_SEC)
            return Model.Fmt.t(REST_MAX_SEC) + " is the longest rest.";
        if (leftMs >= 0 && newSec < sec
                && leftMs - (long) (sec - newSec) * 1000L < REST_LEFT_FLOOR_MS)
            return "Use End rest to finish it now.";
        return capRefusal(planTotalMs, (long) (newSec - sec) * 1000L);
    }

    /* ------------------------------------------------------------------- the drop */

    /**
     * Why an upcoming block's drop pressure may not go from `lo` to `newLo` kPa under a pull
     * of `up`, or null. Lowering it is always allowed down to a full vent; raising it stays
     * below the pull, and above {@link #DROP_MAX_KPA} at least 1.0 inHg under it
     * (RunEdit#dropTopKpa - owner, 2026-09-30). Past {@link #DROP_MAX_KPA} the run screen
     * asks once before it goes (RunEdit#dropNeedsWarning); that is not a refusal.
     */
    public static String dropRefusal(int lo, int newLo, int up) {
        if (newLo == lo) return "Nothing to change.";
        if (newLo < 0) return "Already a full vent.";
        if (newLo > lo && newLo >= up) return "The drop stays below the pull.";
        if (newLo > lo && RunEdit.dropPastGap(newLo, up)) return RunEdit.dropGapSaid();
        return null;
    }

    /** Why an upcoming block's drop time may not go from `lh` to `newLh` seconds (its sets and
     *  hold kept), or null. */
    public static String dropTimeRefusal(int lh, int newLh, int uh, int sets, long planTotalMs) {
        if (newLh == lh) return "Nothing to change.";
        if (newLh < 0) return "Already 0: no drop.";
        if (newLh > lh && newLh > DROP_TIME_MAX_SEC)
            return Model.Fmt.t(DROP_TIME_MAX_SEC) + " is the longest drop.";
        long before = (long) sets * (uh + Math.max(0, lh)) * 1000L;
        long after = (long) sets * (uh + newLh) * 1000L;
        if (after > before && after > ADVISORY_MS) return advisory();
        return capRefusal(planTotalMs, after - before);
    }

    /**
     * THE ONE WRITE OF A DROP PRESSURE ON THIS ROAD. The caller asks {@link #dropRefusal}
     * first; held here again whatever it was asked: a raise never past 1.0 inHg under the pull
     * once above the floor (RunEdit#dropUnder), never at or above the pull (RunEdit#clampLower,
     * the wire's own rule), never below a full vent. A raise is the person's own setting on
     * this block, so its highest drop rises with it (RunEdit#allowDrop) and the wire keeps it.
     * The pull is not touched.
     */
    public static void applyDrop(Model.Preset p, int newLo) {
        int lo = Math.max(0, newLo);
        if (lo > p.lo) { lo = RunEdit.dropUnder(lo, p.up); RunEdit.allowDrop(p, lo); }
        // A drop pressure being set is a drop (a hold-only block's drop time follows -
        // #applyDropTime), so only a stitch chunk keeps its filler (RunEdit#dropKept).
        p.lo = RunEdit.clampLower(RunEdit.dropKept(RunEdit.capDrop(p, lo), p.up, p.cyclePart),
                                  p.up);
    }

    /**
     * An upcoming block's drop time, its sets and hold kept - the block's length follows.
     *
     * A BLOCK THAT HAD NO DROP AND NOW GETS ONE is re-designed with one:
     *   - it drops UNDER THE FLOOR - its drop pressure (the wire's filler just under the pull,
     *     on a hold-only block) comes down to {@link #DROP_MAX_KPA}, so the new drop never
     *     counts as time at pressure. Only then: a drop time changed on a block that already
     *     drops keeps its drop pressure, whatever it is;
     *   - it is no longer "built hold-only" (RunEdit#redesigned), so when it plays the − / +
     *     strip and the adjust sheet leave its drop controls live - the pump drops every set,
     *     and a drop the controls could not reach would be the "drop greyed out" dead end.
     */
    public static void applyDropTime(Model.Preset p, int newLh) {
        int sets = Math.max(1, RunLook.setsIn(p));
        long rem = Math.max(0L, p.durMs - (long) sets * (p.uh + Math.max(0, p.lh)) * 1000L);
        int was = p.lh;
        p.lh = RunEdit.clampSeconds(newLh);
        if (was <= 0 && p.lh > 0) {
            if (p.lo > DROP_MAX_KPA) applyDrop(p, DROP_MAX_KPA);
            if (p.lo < p.up && p.builtHoldOnly && !p.cyclePart) RunEdit.redesigned(p, false);
        }
        p.durMs = Math.max(1000L, (long) sets * (p.uh + p.lh) * 1000L + rem);
    }

    /* -------------------------------------------------------------------- the ramp */

    /**
     * The plan index of stage `s`'s FIRST step when the stage is one ramp - two or more
     * presets of one set occurrence in a row, none a rest or a stitched chunk - or -1. The
     * caller also asks the set itself whether it is a ramp (a set with per-repetition
     * overrides expands the same way and is not one).
     */
    public static int rampOf(List<Model.Preset> plan, int s) {
        int first = -1, n = 0;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (!inBlock(p, s)) continue;
            if (p.rest || p.cyclePart) return -1;
            if (first < 0) first = i;
            else if (!RunEdit.sameOccurrence(plan.get(first), p) || i != first + n) return -1;
            n++;
        }
        return n >= 2 ? first : -1;
    }

    /** How many steps the ramp starting at `first` has. */
    public static int rampSteps(List<Model.Preset> plan, int first) {
        if (plan == null || first < 0 || first >= plan.size()) return 0;
        int n = 1;
        while (first + n < plan.size()
                && RunEdit.sameOccurrence(plan.get(first), plan.get(first + n))) n++;
        return n;
    }

    /** The ramp's time per step, in whole seconds: the steps' average (the ladder spreads a
     *  remainder of a few milliseconds over the first ones). */
    public static int rampStepSec(List<Model.Preset> plan, int first, int n) {
        if (n <= 0) return 0;
        long t = 0;
        for (int k = 0; k < n; k++) t += plan.get(first + k).durMs;
        return (int) Math.round(t / (double) n / 1000.0);
    }

    /** Why a ramp of `n` steps of `stepSec` may not become `newN` steps, or null. */
    public static String rampStepsRefusal(int n, int newN, int stepSec, long planTotalMs) {
        if (newN == n) return "Nothing to change.";
        if (newN < n && newN < RAMP_STEPS_MIN)
            return "A ramp needs at least " + RAMP_STEPS_MIN + " steps.";
        if (newN > n && newN > RAMP_STEPS_MAX)
            return RAMP_STEPS_MAX + " steps is the most the pump’s table holds.";
        long before = (long) n * stepSec * 1000L, after = (long) newN * stepSec * 1000L;
        if (after > before && after > ADVISORY_MS) return advisory();
        return capRefusal(planTotalMs, after - before);
    }

    /** Why a ramp of `n` steps may not go from `stepSec` to `newSec` a step, or null. */
    public static String rampStepTimeRefusal(int stepSec, int newSec, int n, long planTotalMs) {
        return rampStepTimeRefusal(stepSec, newSec, n, planTotalMs, 0);
    }

    /** The same, never under the longest cycle (hold + drop time) of the ramp's steps - the
     *  one rule a step's cycle and its length keep (QuickAdjust#stepTooShort) - and never past
     *  a step's share of the hour (Model.Set#rampStepMaxSec). */
    public static String rampStepTimeRefusal(int stepSec, int newSec, int n, long planTotalMs,
                                             int cycleSec) {
        if (newSec == stepSec) return "Nothing to change.";
        if (newSec < stepSec && newSec < cycleSec)
            return QuickAdjust.stepTooShort(cycleSec, QuickAdjust.LABEL[QuickAdjust.STEP_TIME]);
        if (newSec < stepSec && newSec < STEP_TIME_MIN_SEC)
            return Model.Fmt.t(STEP_TIME_MIN_SEC) + " is the shortest step.";
        int most = Model.Set.rampStepMaxSec(n);
        if (newSec > stepSec && newSec > most) return QuickAdjust.stepTimeLongest(most, n);
        long before = (long) n * stepSec * 1000L, after = (long) n * newSec * 1000L;
        if (after > before && after > ADVISORY_MS) return advisory();
        return capRefusal(planTotalMs, after - before);
    }

    /**
     * TIME PER STEP IN WHOLE CYCLES (0.10): why the later ramp of `n` steps at `first` may not
     * run `newCycles` whole cycles a step, each of its own hold + drop, or null. Never under
     * one cycle (the step would end before its drop - said with `cycleSec`, the ramp's longest
     * cycle, {@link #rampCycleSec}), never a step past its share of the hour, and - growing -
     * never past the twenty-minute set advisory or the two-hour stop.
     */
    public static String rampStepTimeRefusal(List<Model.Preset> plan, int first, int n,
                                             int newCycles, long planTotalMs, int cycleSec) {
        if (plan == null || first < 0 || n < 1 || first + n > plan.size()) return "Nothing to change.";
        int k = rampCycles(plan, first, n);
        if (newCycles == k) return "Nothing to change.";
        if (newCycles < 1)
            return QuickAdjust.stepTooShort(cycleSec, QuickAdjust.LABEL[QuickAdjust.STEP_TIME]);
        long before = 0L, after = 0L;
        int most = Model.Set.rampStepMaxSec(n);
        for (int i = 0; i < n; i++) {
            Model.Preset p = plan.get(first + i);
            before += Math.max(0L, p.durMs);
            long step = (long) newCycles * cycleSecOf(p);
            after += step * 1000L;
            if (newCycles > k && step > most) return QuickAdjust.stepTimeLongest(most, n);
        }
        if (after > before && after > ADVISORY_MS) return advisory();
        return capRefusal(planTotalMs, after - before);
    }

    /** One cycle of a step - its hold plus its drop time - at least a second. */
    public static int cycleSecOf(Model.Preset p) {
        return p == null ? 1 : Math.max(1, Math.max(0, p.uh) + Math.max(0, p.lh));
    }

    /** THE WHOLE CYCLES A STEP OF THE RAMP RUNS: the ramp's length over one cycle of each of its
     *  steps, to the nearest whole number, at least one (Model.Set#rampCycles, read back). */
    public static int rampCycles(List<Model.Preset> plan, int first, int n) {
        if (plan == null || n <= 0) return 1;
        long t = 0L, pass = 0L;
        for (int i = 0; i < n && first + i < plan.size(); i++) {
            Model.Preset p = plan.get(first + i);
            t += Math.max(0L, p.durMs);
            pass += cycleSecOf(p) * 1000L;
        }
        if (pass <= 0L) return 1;
        return (int) Math.max(1L, Math.round(t / (double) pass));
    }

    /** The ramp's time per step, as {@link #rampStepSec} reads it, once each of its steps runs
     *  `cycles` whole cycles. */
    public static int rampStepSecAt(List<Model.Preset> plan, int first, int n, int cycles) {
        if (plan == null || n <= 0) return 0;
        long t = 0L;
        for (int i = 0; i < n && first + i < plan.size(); i++)
            t += (long) Math.max(1, cycles) * cycleSecOf(plan.get(first + i));
        return (int) Math.round(t / (double) n);
    }

    /** Every step of the ramp at `first` runs `cycles` whole cycles of its own hold + drop -
     *  so it ends as a drop ends, and the next step's pull starts from a released cuff.
     *  Durations only - the app's clock. */
    public static void setRampCycles(List<Model.Preset> plan, int first, int n, int cycles) {
        int k = Math.max(1, cycles);
        for (int i = 0; i < n && first + i < plan.size(); i++) {
            Model.Preset p = plan.get(first + i);
            p.durMs = Math.max(1000L, (long) k * cycleSecOf(p) * 1000L);
        }
    }

    /** The longest cycle - hold plus drop time - of the ramp's `n` steps from `first`. */
    public static int rampCycleSec(List<Model.Preset> plan, int first, int n) {
        int most = 0;
        if (plan == null) return 0;
        for (int k = 0; k < n && first + k < plan.size(); k++) {
            Model.Preset p = plan.get(first + k);
            most = Math.max(most, p.uh + p.lh);
        }
        return most;
    }

    /** Every step of the ramp at `first` runs about `newSec`: the same whole number of cycles
     *  of its own hold + drop each, the one nearest `newSec` a step, at least one
     *  ({@link #setRampCycles}). Durations only - the app's clock. */
    public static void setRampStepTime(List<Model.Preset> plan, int first, int n, int newSec) {
        long pass = 0L;
        for (int i = 0; i < n && first + i < plan.size(); i++) pass += cycleSecOf(plan.get(first + i));
        long k = pass <= 0L ? 1L : Math.round(Math.max(0, newSec) * (double) n / pass);
        setRampCycles(plan, first, n, (int) Math.max(1L, k));
    }

    /**
     * RE-SPREADS the ramp at `first` (n steps) into `newN` steps between the SAME two ends:
     * the first step's figures and the last's, interpolated as the set's own ladder is, each
     * step `stepSec` long. The pull of every new step lies between the two ends' pulls - never
     * above the higher, and under the ceiling - so a re-spread cannot raise pressure. Each new
     * step carries an offset record interpolated from the ends' and rounded UP (the trainer's
     * cap is held against it, as RunEdit#resizeRemaining does), and is built as its ramp was
     * (hold-only only when both ends were). Returns the change in the plan's size.
     *
     * The new steps' lengths are `stepSec` as given; the run's own road (comingRampSteps, the
     * undo) then puts them in WHOLE CYCLES with {@link #setRampCycles}, as every ramp step is.
     */
    public static int respreadRamp(List<Model.Preset> plan, int first, int n, int newN,
                                   int stepSec, int ceilKpa) {
        if (plan == null || first < 0 || n < 1 || newN < 1 || first + n > plan.size()) return 0;
        Model.Preset a = plan.get(first), z = plan.get(first + n - 1);
        String base = stepLabelBase(a.label);
        List<Model.Preset> out = new ArrayList<Model.Preset>();
        for (int k = 0; k < newN; k++) {
            double f = newN == 1 ? 0.0 : (double) k / (newN - 1);
            Model.Preset p = new Model.Preset();
            int up = RunEdit.clampUpper(Math.max(0, lerp(a.up, z.up, f)), ceilKpa);
            p.up = Math.min(up, Math.max(a.up, z.up));
            // The highest drop the ends had - the new steps never go past it.
            p.loMax = Math.max(RunEdit.dropCapOf(a, a.lo), RunEdit.dropCapOf(z, z.lo));
            int lh = RunEdit.clampSeconds(lerp(a.lh, z.lh, f));
            // ...and above the floor 1.0 inHg under this step's pull (RunEdit#dropKept).
            p.lo = RunEdit.clampLower(RunEdit.dropKept(RunEdit.capDrop(p,
                Math.max(0, lerp(a.lo, z.lo, f))), p.up, a.builtHoldOnly && z.builtHoldOnly),
                p.up);
            p.uh = RunEdit.clampSeconds(lerp(a.uh, z.uh, f));
            p.lh = lh;
            p.sp = RunEdit.clampSpeed(lerp(a.sp, z.sp, f));
            p.stageIdx = a.stageIdx; p.pos = a.pos; p.setId = a.setId;
            RunEdit.redesigned(p, a.builtHoldOnly && z.builtHoldOnly);
            p.offsetKpa = (int) Math.ceil(a.offsetKpa + (z.offsetKpa - a.offsetKpa) * f - 1e-9);
            p.ordinal = a.ordinal + k;
            p.durMs = Math.max(1000L, stepSec * 1000L);
            p.label = base + " " + (k + 1) + "/" + newN;
            out.add(p);
        }
        for (int k = n - 1; k >= 0; k--) plan.remove(first + k);
        plan.addAll(first, out);
        return newN - n;
    }

    /** "Warm-up to −5.9 inHg 3/5" → "Warm-up to −5.9 inHg". */
    static String stepLabelBase(String label) {
        if (label == null) return "Ramp";
        return label.replaceFirst("\\s+\\d+/\\d+\\s*$", "");
    }

    private static int lerp(int from, int to, double f) {
        return (int) Math.round(from + (to - from) * f);
    }

    /* ---------------------------------------------------------- what a card is, and undo */

    public static final int SHAPE_OTHER = 0, SHAPE_BLOCK = 1, SHAPE_REST = 2, SHAPE_RAMP = 3;

    /** WHAT COMING STEPS MAY CHANGE ABOUT ONE STAGE, read off the plan: the card's figures,
     *  and - kept from before the first change - what "Undo" puts back. */
    public static final class Shape {
        public int kind = SHAPE_OTHER;
        /** A block's sets, hold, drop pressure (kPa) and drop time. */
        public int sets, uh, dropKpa, lh;
        /** ...and whether it was built without a drop (Undo puts that back too). */
        public boolean holdOnlyBuild;
        public int restSec;
        public int steps, stepSec;
        /** A ramp's whole cycles a step (Model.Set#rampCycles) - what Undo puts back. */
        public int cycles;
        /** The stage's whole length in the plan. */
        public long ms;
    }

    /** Stage `s` as Coming steps sees it; `ramp` is the set's own answer (Model.Set#ramp). */
    public static Shape shapeOf(List<Model.Preset> plan, int s, boolean ramp) {
        Shape sh = new Shape();
        if (plan == null) return sh;
        for (int i = 0; i < plan.size(); i++)
            if (inBlock(plan.get(i), s)) sh.ms += Math.max(0L, plan.get(i).durMs);
        int b = blockOf(plan, s);
        if (b >= 0) {
            Model.Preset p = plan.get(b);
            sh.kind = SHAPE_BLOCK;
            sh.sets = RunLook.setsIn(p); sh.uh = p.uh; sh.dropKpa = p.lo; sh.lh = p.lh;
            sh.holdOnlyBuild = p.builtHoldOnly;
            return sh;
        }
        int r = restOf(plan, s);
        if (r >= 0) {
            sh.kind = SHAPE_REST;
            sh.restSec = (int) (plan.get(r).durMs / 1000L);
            return sh;
        }
        int f = ramp ? rampOf(plan, s) : -1;
        if (f >= 0) {
            sh.kind = SHAPE_RAMP;
            sh.steps = rampSteps(plan, f);
            sh.stepSec = rampStepSec(plan, f, sh.steps);
            sh.cycles = rampCycles(plan, f, sh.steps);
        }
        return sh;
    }

    /**
     * A SKIPPED CARD'S SHAPE, read from the presets the skip took out (device check EMU9b N3):
     * a ramp still a ramp - asked of the block's own set, as a card to run is - and its own
     * length. Read as not-a-ramp, a skipped climb lost its "Ramp · 2 steps" and was named by
     * its stage, at 0:00.
     */
    public static Shape skippedShape(List<Model.Preset> taken, int s, boolean ramp) {
        return shapeOf(taken, s, ramp);
    }

    /**
     * A SKIPPED BLOCK OF SETS IS NAMED BY ITS OWN COUNT - "5 sets" - never a range (device check
     * EMU9b N3). The set numbers belong to the sets that will run: a skip renumbers the blocks
     * after it, and two skipped blocks both read "Sets 1–5" beside a live one that read the same.
     */
    public static String skippedSetsName(int sets) {
        return sets == 1 ? "1 set" : Math.max(0, sets) + " sets";
    }

    /** How many of a card's figures differ from what it was before Coming steps changed it -
     *  the card's changed dot, and "Undo this change" / "Undo these changes". */
    public static int changes(Shape was, Shape now) {
        if (was == null || now == null || was.kind != now.kind) return 0;
        int n = 0;
        switch (now.kind) {
            case SHAPE_BLOCK:
                if (was.sets != now.sets) n++;
                if (was.uh != now.uh) n++;
                if (was.dropKpa != now.dropKpa) n++;
                if (was.lh != now.lh) n++;
                break;
            case SHAPE_REST:
                if (was.restSec != now.restSec) n++;
                break;
            case SHAPE_RAMP:
                if (was.steps != now.steps) n++;
                if (was.stepSec != now.stepSec || was.cycles != now.cycles) n++;
                break;
            default:
        }
        return n;
    }

    /**
     * PUTS A BLOCK BACK as it was before Coming steps changed it: its hold, drop time and sets,
     * its drop pressure - held under the pull as it stands now (never raised past it; the pull
     * itself is never touched here) - and what it was built as, so a hold-only block given a
     * drop and then undone locks its drop controls again.
     */
    public static void restoreBlock(Model.Preset p, Shape was) {
        int sets = Math.max(1, RunLook.setsIn(p));
        long rem = Math.max(0L, p.durMs - (long) sets * (p.uh + Math.max(0, p.lh)) * 1000L);
        p.uh = RunEdit.clampSeconds(was.uh);
        p.lh = RunEdit.clampSeconds(was.lh);
        p.lo = RunEdit.clampLower(RunEdit.dropKept(Math.max(0, was.dropKpa), p.up,
                                                   was.holdOnlyBuild || p.cyclePart), p.up);
        RunEdit.redesigned(p, was.holdOnlyBuild);
        p.durMs = Math.max(1000L, (long) Math.max(1, was.sets) * (p.uh + p.lh) * 1000L + rem);
    }

    /* ------------------------------------------------------------------- skipping */

    /** Why block `s` may not be skipped (or un-skipped) now, or null. Only a block wholly
     *  after the one playing may be; a cylinder changeover never may - it is the one step that
     *  keeps the next pressure out of the wrong tube. */
    public static String skipRefusal(List<Model.Stage> stages, List<Model.Preset> plan,
                                     int s, int planIdx) {
        int stage = stageOf(s);
        if (stages == null || stage >= stages.size()) return "No such step.";
        Model.Stage st = stages.get(stage);
        if (st != null && st.rest && st.awaitAck)
            return "The cylinder change can't be skipped.";
        for (int i = 0; plan != null && i < plan.size(); i++)
            if (inBlock(plan.get(i), s) && plan.get(i).awaitAck)
                return "The cylinder change can't be skipped.";
        if (planIdx >= 0 && planIdx < plan.size() && !after(s, key(plan.get(planIdx))))
            return "Only a step that hasn't started can be skipped.";
        return null;
    }

    /** Takes stage `s`'s presets out of the plan (only those after `planIdx`; the caller has
     *  asked {@link #skipRefusal} first) and returns them, in order. */
    public static List<Model.Preset> removeStage(List<Model.Preset> plan, int s, int planIdx) {
        List<Model.Preset> out = new ArrayList<Model.Preset>();
        for (int i = plan.size() - 1; i > planIdx; i--) {
            if (inBlock(plan.get(i), s)) out.add(0, plan.remove(i));
        }
        return out;
    }

    /** Where stage `s`'s presets go back: before the first later stage's preset after
     *  `planIdx`, or at the end. */
    public static int reinsertAt(List<Model.Preset> plan, int s, int planIdx) {
        for (int i = Math.max(0, planIdx + 1); i < plan.size(); i++)
            if (after(key(plan.get(i)), s)) return i;
        return plan.size();
    }

    /** The first plan index a skip of stage `s` would take out, or -1. */
    public static int firstOf(List<Model.Preset> plan, int s, int planIdx) {
        for (int i = Math.max(0, planIdx + 1); i < plan.size(); i++)
            if (inBlock(plan.get(i), s)) return i;
        return -1;
    }

    /** The sum of the lengths of `ps`. */
    public static long lengthOf(List<Model.Preset> ps) {
        return totalMs(ps);
    }
}
