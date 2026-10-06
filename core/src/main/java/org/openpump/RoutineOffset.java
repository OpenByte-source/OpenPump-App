package org.openpump;

import java.util.List;

/**
 * THE WHOLE-ROUTINE OFFSET, the pure half: whether a routine takes it at all, and what one
 * tap on its − or + does to the remaining work steps.
 *
 * WHY THIS EXISTS. On a Length routine the sheet's − and + looked dead (owner report): the
 * Activity refused every tap and said so in a snackbar, and on the run screen a snackbar
 * draws UNDER the sheet, so nothing could be seen. The sheet never asked beforehand and went
 * on showing "N work presets remaining". So the refusal is one pure check that both the sheet
 * and the Activity ask (the sheet shows its words IN PLACE OF the buttons), and every other
 * answer a tap can get comes back as words for the sheet to write under its buttons. WiringCheck
 * invariant 140 holds the app to that.
 *
 * THE COUNTER IS HONEST. "Offset now …" moves only when at least one step's pull actually
 * changed. It used to move on every tap, even with no work step left to move: a counter that
 * wandered to −5 over nothing left five more kPa of headroom under the trainer's +2 cap for
 * whatever came next.
 *
 * AND THE CAP IS HELD PER STEP. Each preset carries the offset this tool has put on it
 * (Model.Preset#offsetKpa), and an upward tap never takes a step past `cap` of its own. The
 * counter alone could not hold it: a ramp's step count, changed mid-run, rebuilds the rest of
 * the ramp between the running step (not offset) and the ramp's end (offset), so the new steps
 * carry only part of the counter - and a later + measured against the counter took them past
 * the cap (RunEdit#resizeRemaining now interpolates the offset it rebuilds, rounded up).
 * Every other edit that writes a step's pull outright - a reshape, the ramp's shift, the
 * upcoming-step editor - goes through RunEdit#writePull, which drops a negative record: a step
 * written back up keeps no headroom from an earlier − (WiringCheck invariant 151).
 */
public final class RoutineOffset {
    private RoutineOffset() { }

    /*
     * A LENGTH ROUTINE USED TO BE REFUSED here, by design: its pulls come from the traction load.
     * The owner's decision (0.10) made that refusal, like the trainer's +2 kPa, a programme cap
     * that is warned once - your call (#warning): a length routine's usual cap is nothing, so
     * its first "+" asks, and its pulls and its expansion move together, each inside its own
     * hard limit (the pull's load limit - Scale#pullCapKpa - per step, #apply).
     */

    /** The hold's own refusal: the offset rewrites the pump's table, the hold's entry with it
     *  (WiringCheck invariant 48b). */
    public static final String HOLD_UP =
        "Paused — resume first. Nothing was changed.";

    public static final String REDUCED_DAY =
        "A reduced day runs as prescribed — no upward offset today.";

    public static final String NO_WORK_LEFT =
        "No work sets are left in this run — nothing was changed.";

    public static final String AT_TOP =
        "Every remaining work pull is already as high as it may go — nothing was changed.";

    public static final String AT_BOTTOM =
        "Every remaining work pull is already at zero — nothing was changed.";

    /**
     * THE ONE REFUSAL CHECK: the words that say why this routine takes no offset, or null
     * when it does. Since 0.10 no routine is refused outright - the length refusal became a
     * one-time warning (#warning) - but the sheet and the apply still ask here first, so a
     * routine that must take no offset has one place to say so (WiringCheck invariant 140).
     */
    public static String refusal(int trainerTrack) {
        return null;
    }

    /** The same check for the routine being run - a Library routine when it names no track,
     *  and nothing to refuse when there is no routine at all. */
    public static String refusal(Model.Routine running) {
        return refusal(running == null ? Model.TRAINER_TRACK_NONE : running.trainerTrack);
    }

    /**
     * THE ONE-TIME WARNING FOR AN UPWARD TAP PAST THE USUAL CAP (the owner's decision, 0.10:
     * warned once, your call), or null when none is owed: a downward tap, a reduced day (its
     * cap of nothing is a safety rule and stays - #REDUCED_DAY), a Library routine (the
     * ceiling alone bounds it), and a tap that stays within the usual cap
     * (RunEdit#usualRoutineOffsetCapKpa) or within what the person already confirmed this run
     * (`warnedKpa`). Past it, the words that ask; confirmed, the run's cap rises to
     * `counter + dKpa` (RunEdit#routineOffsetCapKpa) and the tap goes through #apply.
     */
    public static String warning(int trainerTrack, int counter, int dKpa, int warnedKpa,
                                 boolean cutInForce) {
        if (dKpa <= 0 || cutInForce || trainerTrack == Model.TRAINER_TRACK_NONE) return null;
        long want = (long) counter + dKpa;
        int usual = RunEdit.usualRoutineOffsetCapKpa(trainerTrack);
        if (want <= Math.max(usual, warnedKpa)) return null;
        if (trainerTrack == Plan.TRACK_LENGTH)
            return "Length routines usually take their pull from the traction load, raised in "
                + "the Trainer. Offset anyway? Every remaining work pull and the expansion move "
                + "together, up to " + Model.Fmt.dMag(want) + " more than prescribed, each "
                + "inside its own limit - never past your ceiling, the most you said you will "
                + "go to, 15 inHg or " + Traction.settingLb(Scale.LOAD_HARD_MAX_LB)
                + ". You are asked once for each new highest offset "
                + "this run.";
        return "More than the plan's own step? This run's remaining work would pull "
            + Model.Fmt.dMag(want) + " more than prescribed, past the usual "
            + Model.Fmt.dMag(usual) + ". Go there only if it suits you. You are asked once for "
            + "each new highest offset this run; your ceiling, the most you said you will go to "
            + "and 15 inHg still hold.";
    }

    /** The one-time warning's buttons. */
    public static final String WARN_GO = "Go on", WARN_KEEP = "Keep it";

    /** The cap's own words: how much more pull than prescribed a trainer run may carry. */
    public static String capped(int capKpa) {
        return capKpa <= 0 ? REDUCED_DAY
            : "Capped at the plan's own step — at most " + Model.Fmt.dMag(capKpa)
              + " more pull than prescribed.";
    }

    /** What one tap did. */
    public static final class Result {
        /** The Δ the counter moved by: 0 unless at least one step changed. */
        public int applied;
        /** How many work steps' pull actually changed. */
        public int changed;
        /** The counter after the tap. */
        public int counter;
        /** What the sheet writes under its buttons, or null when there is nothing to say. */
        public String note;
    }

    /**
     * ONE TAP. Moves the PULL of every remaining work step (work[k] true, k from `fromIdx`) by
     * dKpa - never the drop, which only re-clamps under the moved pull - and returns what
     * happened.
     *
     * Upward, the tap is first trimmed so the counter stays within `cap` (the note says so),
     * then each step goes no further than `cap` above what the tool has already put on it.
     * Every pull stays within 0 and the ceiling. A step whose pull did not move is not counted,
     * and the counter moves only when one did.
     *
     * @param work    per plan index, whether the offset may touch that step (a work stage);
     *                read only from fromIdx on
     * @param counter the offset applied so far this run
     * @param cap     the most the offset may add (RunEdit#routineOffsetCapKpa);
     *                Integer.MAX_VALUE for a routine the ceiling alone bounds
     * @param limits  each step's own hard limit by plan index (0.10), or null for the ceiling
     *                alone: a length routine's traction pulls stop at the pull's own limit (its
     *                load's, Scale#pullCapKpa), its expansion at the trainer's, and a plan's
     *                routine at "Most you will go to" as well as the ceiling
     */
    public static Result apply(List<Model.Preset> plan, int fromIdx, boolean[] work,
                               int dKpa, int counter, int cap, int ceilKpa, int[] limits) {
        Result r = new Result();
        r.counter = counter;
        int d = dKpa;
        if (d > 0 && (long) counter + d > cap) {
            d = (int) Math.max(0L, (long) cap - counter);
            r.note = capped(cap);
        }
        if (d == 0) return r;
        int touched = 0;
        for (int k = Math.max(0, fromIdx); k < plan.size(); k++) {
            if (k >= work.length || !work[k]) continue;
            touched++;
            Model.Preset p = plan.get(k);
            long want = d;
            // Per step: never more than `cap` above what this tool has put on the step.
            if (d > 0) want = Math.min(want, (long) cap - p.offsetKpa);
            if (d > 0 && want <= 0) continue;
            int lim = limits != null && k < limits.length ? Math.min(ceilKpa, limits[k])
                                                           : ceilKpa;
            int nu = (int) Math.max(0L, Math.min((long) lim, p.up + want));
            if (d > 0 && nu < p.up) continue;     // a step above the ceiling is not lowered by a +
            if (nu == p.up) continue;
            p.offsetKpa += nu - p.up;
            p.up = nu;
            // A lowered pull takes a drop above the floor down with it, 1.0 inHg under
            // (RunEdit#dropKept - review I5: 30/26 offset -3 left 27/26).
            p.lo = RunEdit.dropKept(p, p.lo, p.up);
            r.changed++;
        }
        if (r.changed > 0) {
            r.applied = d;
            r.counter = counter + d;
            return r;
        }
        if (r.note == null)
            r.note = touched == 0 ? NO_WORK_LEFT : (d > 0 ? AT_TOP : AT_BOTTOM);
        // (A step reshaped, shifted or typed back up since its − keeps no headroom from it:
        // RunEdit#writePull drops a negative offsetKpa, so AT_TOP can be the honest answer
        // while the counter still reads below zero.)
        return r;
    }

    /** {@link #apply} with the ceiling alone for every step - a Library routine, and every
     *  caller from before 0.10 gave each step its own hard limit. */
    public static Result apply(List<Model.Preset> plan, int fromIdx, boolean[] work,
                               int dKpa, int counter, int cap, int ceilKpa) {
        return apply(plan, fromIdx, work, dKpa, counter, cap, ceilKpa, null);
    }
}
