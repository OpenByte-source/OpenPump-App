package org.openpump;

import java.util.Arrays;
import java.util.List;

/**
 * "THIS SET" ON A RAMP: THE REMAINING STEPS MOVE WITH THE STEP PLAYING (wave 2 §4), pure.
 *
 * A ± in "This set" scope on a ramp changes the step playing and shifts every remaining step
 * of the ramp by the same change, shape kept. The shift is kept against a BASE - the
 * remaining steps as they were when it was taken - plus the TOTAL of the changes since, so
 * repeated nudges never compound (a step clamped at the ceiling on one nudge is not
 * flattened for good; the next nudge down lets it follow the ramp again).
 *
 * WHAT WAS WRONG (safety review of D1, finding 3). The old shift (SessionActivity's
 * shiftRemainingOfSet) derived the change from the running step's own row in its snapshot:
 * tail = snapshot + (what is in force now − the running step's snapshot row). Three ways
 * that moved the rest of the ramp AGAINST the gesture:
 *   - nothing told the snapshot when the tail was edited another way. A reshape, a step
 *     count, the whole-routine offset or an upcoming-step edit made after the snapshot was
 *     undone by the next nudge: on a ramp 20/24/28/32/36 kPa, pull + at 21, a reshape to an
 *     end of 22, then pull − rebuilt 24/28/32/36 - a MINUS that raised the last step 14 kPa.
 *   - a snapshot taken fresh took the running step's PLAN row as its reference, which is not
 *     what is in force while an adjustment already carries: after + ×5 (rep scope, or before
 *     a reshape that cleared the snapshot), one set-scope − shifted the tail UP by four steps.
 *   - a step reached with its row clamped (at 0 or the ceiling) made its clamped value the
 *     reference, so a hold − on it could swing the pull of the steps after it.
 *
 * THE RULE NOW. Each nudge contributes exactly its own change (`after` − `before`, the
 * running step's figures just before and just after it) to the total. The base is re-taken
 * - from the tail as it is, the total reset - whenever the tail is not what the last shift
 * wrote into it (any other edit, whatever made it), when the ramp was recounted, or when a
 * different ramp is playing. Every written figure is a non-decreasing function of the total,
 * clamped the way it is sent, so a gesture toward less never raises any step to come, and
 * one toward more never lowers one.
 *
 * EACH FIGURE BY ITS OWN CHANGE, AND ONLY IT ("Rest of this ramp", 0.10). The total is kept
 * per figure, so a pull moves the pulls, a hold the holds, a speed the speeds - nothing
 * re-times the ramp that was not asked. A ramp stays a ramp: every write is a monotone
 * function of one shared total, so the steps keep their order (ascending or descending) and
 * never cross. Two limits can make it FLATTER, and each is counted for the run to say:
 *   - the safety ceiling: a pull raised past it stops at it (clampedAtCeiling - the top steps
 *     of a raised ascending ramp meet there);
 *   - the drop floor: a drop RAISED by a shift stops at the step's own highest drop
 *     (RunEdit#dropCapOf: ComingSteps#DROP_MAX_KPA, its planned drop when that is already
 *     higher, or a drop the person raised on it - RunEdit#allowDrop), never lowered by a
 *     raise; and always under that step's pull, 1.0 inHg under it once above the floor
 *     (RunEdit#dropKept).
 */
public final class SetShift {

    /** The step the base was taken on, and the identity of its ramp occurrence. */
    private int from = -1;
    private Model.Preset head;
    /** The remaining steps at the time the base was taken, by (plan index − from). */
    private int[][] base;
    /** What the last shift wrote into each of them - anything else there is someone's edit. */
    private int[][] written;
    /** The sum of every nudge's change since the base was taken. */
    private final int[] total = new int[LiveEdit.FIELDS];
    /** How many of the steps the last shift wrote have their pull stopped at the ceiling. */
    private int atCeiling;
    /** How many stopped at a lower limit instead, and the highest of those limits. */
    private int atLimit, limitKpa;

    /** How many of the steps the last shift wrote have their pull stopped at the safety
     *  ceiling - the ramp is flatter there than its shape, and the run says so. */
    public int clampedAtCeiling() { return atCeiling; }

    /** How many of the steps the last shift wrote have their pull stopped at a limit under the
     *  ceiling - a hard limit, or the strip's usual one (review 2, finding 2). */
    public int clampedAtLimit() { return atLimit; }

    /** The highest limit under the ceiling a step stopped at (for the words), or 0. */
    public int limitStoppedAtKpa() { return limitKpa; }

    /** Drop the base: the next shift takes it afresh from the tail as it then is. Called when
     *  the tail is replaced on purpose (a reshape, a step count, a new run) - the shift would
     *  notice anyway; this says so where it happens. */
    public void forget() {
        from = -1; head = null; base = null; written = null;
        Arrays.fill(total, 0);
    }

    /**
     * Shifts the `occLeft` remaining steps after `planIdx` by the change of the step playing
     * from `before` to `after` (LiveEdit tuples: what was in force just before this
     * adjustment, and what is in force now). Returns the plan index of the last step written,
     * or `planIdx` when there were none.
     */
    public int shift(List<Model.Preset> plan, int planIdx, int occLeft, int[] before, int[] after,
                     int ceilKpa) {
        return shift(plan, planIdx, occLeft, before, after, ceilKpa, null, Integer.MAX_VALUE);
    }

    /**
     * THE SAME, HELD TO EACH STEP'S OWN LIMITS (review 2, finding 2). "Rest of this ramp" moved
     * the later steps under the safety ceiling alone: on a new person's first month at 20 kPa, a
     * gentle warm-up 14/17/20 with +3 kPa went to 20 and 23 - past the first month's 6 inHg, and
     * the same past "Most you will go to", 15 inHg and the strip's usual 10.0 inHg, unasked.
     *
     * A later step's pull is RAISED no further than `hardKpa[k]` (the run's hard limit for plan
     * index k - SessionActivity#runHardLimits; null: none) nor than `usualKpa` (the strip's usual
     * limit, or past it as far as the person confirmed this run - QuickAdjust#pullLimit; the run
     * asks before a "+" that would take a step past it, #raisedPeak). Neither limit ever LOWERS
     * a step below its own figure, so it is still a non-decreasing function of the total and the
     * ramp keeps its order; where it stops one, the ramp is flatter there, counted for the run to
     * say (#clampedAtLimit).
     */
    public int shift(List<Model.Preset> plan, int planIdx, int occLeft, int[] before, int[] after,
                     int ceilKpa, int[] hardKpa, int usualKpa) {
        int last = Math.min(planIdx + occLeft, plan.size() - 1);
        if (last <= planIdx) return planIdx;
        if (!holds(plan, planIdx, last)) retake(plan, planIdx, last);
        for (int f = 0; f < LiveEdit.FIELDS; f++) total[f] += after[f] - before[f];
        atCeiling = 0;
        atLimit = 0;
        limitKpa = 0;
        for (int k = planIdx + 1; k <= last; k++) {
            int i = k - from;
            Model.Preset p = plan.get(k);
            int cap = pullCap(base[i][LiveEdit.UP], ceilKpa, hardAt(hardKpa, k), usualKpa);
            write(p, base[i], total, cap);
            written[i] = tuple(p);
            if (total[LiveEdit.UP] > 0 && base[i][LiveEdit.UP] + total[LiveEdit.UP] > cap) {
                if (cap >= ceilKpa) atCeiling++;
                else { atLimit++; limitKpa = Math.max(limitKpa, cap); }
            }
        }
        return last;
    }

    /**
     * THE HIGHEST PULL A SHIFT FROM `before` TO `after` WOULD RAISE A LATER STEP TO PAST ITS OWN
     * FIGURE, under the ceiling and each step's hard limit but NOT the usual limit - what the run
     * asks the person about before the "+" goes (QuickAdjust#pullWarning, review 2 finding 2) -
     * with the plan index it lands on: {pull, index}, or {0, -1} when no later step would go up
     * past where it is and past its own figure (a step coming back up to its own figure is never
     * held by the usual limit, #pullCap, so it is nothing to ask about). Changes nothing.
     */
    public int[] raisedPeak(List<Model.Preset> plan, int planIdx, int occLeft, int[] before,
                            int[] after, int ceilKpa, int[] hardKpa) {
        int last = Math.min(planIdx + occLeft, plan.size() - 1);
        int[] none = { 0, -1 };
        if (plan == null || last <= planIdx) return none;
        boolean kept = holds(plan, planIdx, last);
        int t = (kept ? total[LiveEdit.UP] : 0) + after[LiveEdit.UP] - before[LiveEdit.UP];
        int peak = 0, at = -1;
        for (int k = planIdx + 1; k <= last; k++) {
            int b = kept ? base[k - from][LiveEdit.UP] : plan.get(k).up;
            int cap = pullCap(b, ceilKpa, hardAt(hardKpa, k), Integer.MAX_VALUE);
            int v = RunEdit.clampUpper(Math.max(Math.min(Math.max(0, b), 1), b + t), cap);
            if (v > plan.get(k).up && v > b && v > peak) { peak = v; at = k; }
        }
        return peak > 0 ? new int[]{ peak, at } : none;
    }

    /** The hard limit for plan index k, or none. */
    private static int hardAt(int[] hardKpa, int k) {
        return hardKpa != null && k >= 0 && k < hardKpa.length && hardKpa[k] > 0
            ? hardKpa[k] : Integer.MAX_VALUE;
    }

    /** The most a step whose base pull is `b` may be written at: the ceiling, and a raise held
     *  to the hard and usual limits - never under the step's own figure (a limit stops a raise,
     *  it never lowers a step), never past the ceiling. */
    private static int pullCap(int b, int ceilKpa, int hardKpa, int usualKpa) {
        return Math.min(ceilKpa, Math.max(Math.min(hardKpa, usualKpa), b));
    }

    /** The shift applied since the base was taken (for the log). */
    public int totalOf(int field) { return total[field]; }

    /** Is the base still the base of these steps - same ramp, same count, untouched since? */
    private boolean holds(List<Model.Preset> plan, int planIdx, int last) {
        if (base == null || from < 0 || from > planIdx) return false;
        if (!RunEdit.sameOccurrence(head, plan.get(planIdx))) return false;
        if (last - from != base.length - 1) return false;            // recounted
        for (int k = planIdx + 1; k <= last; k++) {
            int[] w = written[k - from];
            if (w == null || !Arrays.equals(w, tuple(plan.get(k)))) return false;   // edited
        }
        return true;
    }

    private void retake(List<Model.Preset> plan, int planIdx, int last) {
        Model.Preset cur = plan.get(planIdx);
        head = new Model.Preset();
        head.stageIdx = cur.stageIdx; head.pos = cur.pos; head.setId = cur.setId;
        from = planIdx;
        base = new int[last - planIdx + 1][];
        written = new int[last - planIdx + 1][];
        for (int k = planIdx + 1; k <= last; k++) {
            base[k - from] = tuple(plan.get(k));
            written[k - from] = base[k - from].clone();
        }
        Arrays.fill(total, 0);
    }

    /** base + total, clamped exactly as the wire is written (and never below zero, as a
     *  reshape writes) - each figure non-decreasing in the total. */
    private static void write(Model.Preset p, int[] b, int[] t, int ceilKpa) {
        // Through writePull: a shifted pull keeps no offset headroom it no longer has (151).
        // A pull under pressure stops at 1 kPa, never 0 (Model.Set#clamp's floor): at 0 no
        // drop can stay under it. A step planned at 0 stays there.
        int upFloor = Math.min(Math.max(0, b[LiveEdit.UP]), 1);
        RunEdit.writePull(p, RunEdit.clampUpper(Math.max(upFloor, b[LiveEdit.UP] + t[LiveEdit.UP]),
                                                ceilKpa));
        int lo = Math.max(0, b[LiveEdit.LO] + t[LiveEdit.LO]);
        // A drop RAISED by the shift stops at the step's own highest - the drop floor, its
        // planned drop when that is higher already (a raise never lowers it), or what the
        // person raised it to on the strip (RunEdit#allowDrop) - and under its pull: 1.0 inHg
        // under it once above the floor (owner, 2026-09-30), unless the step only holds.
        if (t[LiveEdit.LO] > 0) lo = Math.min(lo, Math.max(b[LiveEdit.LO], RunEdit.dropCapOf(p, b[LiveEdit.LO])));
        // Asked only when the shift raises the drop or lowers the pull - the two moves that can
        // close the gap - and a raise never ends under the step's own drop, so a plus never
        // lowers it - as far as the gap under the pull it now has allows (the one drop rule,
        // RunEdit#dropKept, review I4/I5: a raise that also lowered the pull kept a drop the
        // new pull had closed on). Not by the drop time: a drop time nudged to 0 would let the
        // drop up again, a minus that raised it (SetShiftTest's property).
        if ((t[LiveEdit.LO] > 0 || t[LiveEdit.UP] < 0) && !p.cyclePart) {
            int held = RunEdit.dropKept(lo, p.up, false);
            lo = t[LiveEdit.LO] > 0
                ? Math.max(held, RunEdit.dropKept(p, b[LiveEdit.LO], p.up))
                : held;
        }
        p.lo = RunEdit.clampLower(RunEdit.capDrop(p, lo), p.up);
        p.uh = RunEdit.clampSeconds(b[LiveEdit.UH] + t[LiveEdit.UH]);
        p.lh = RunEdit.clampSeconds(b[LiveEdit.LH] + t[LiveEdit.LH]);
        p.sp = RunEdit.clampSpeed(b[LiveEdit.SP] + t[LiveEdit.SP]);
    }

    /** A preset's five wire figures as a LiveEdit tuple. */
    static int[] tuple(Model.Preset p) {
        int[] t = new int[LiveEdit.FIELDS];
        t[LiveEdit.UP] = p.up; t[LiveEdit.LO] = p.lo; t[LiveEdit.UH] = p.uh;
        t[LiveEdit.LH] = p.lh; t[LiveEdit.SP] = p.sp;
        return t;
    }
}
