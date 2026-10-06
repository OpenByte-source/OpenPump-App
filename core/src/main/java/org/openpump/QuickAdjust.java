package org.openpump;

/**
 * THE RUN SCREEN'S − / + STRIP ("quick adjust", 0.10 run-screen redesign), as pure rules.
 *
 * The strip changes the step that is PLAYING, and only that: on a work set its pull, its
 * hold, its drop and its drop time (through the live-override path every other live edit
 * uses - LiveEdit, SessionActivity#applyOverride - so the countdown carries on where it is);
 * on a rest, a ramp or the warm-up, how long it runs (the app's own advance clock, never a
 * wire value). This class holds what the owner approved for it: the step of one tap, the
 * range each figure stays inside and the sentence a tap at a limit is told.
 *
 * THE RANGES ARE NARROWER THAN THE WIRE'S, never wider. A figure is still passed through
 * LiveEdit#set (the ceiling, the drop under the pull, 1..255 s) after this has let it go, so
 * the strip can refuse more than the wire would but never send what the wire would not.
 *
 * A TAP TOWARD THE RANGE IS NEVER REFUSED. A routine can carry a figure outside the strip's
 * range (an old set with a 5 s hold, a drop planned at −4.0 inHg): the strip then refuses
 * only the direction that would take it further out, so the figure can always be brought
 * back in. Each bound is asked only in the direction of the tap.
 */
public final class QuickAdjust {
    private QuickAdjust() { }

    /** The cells. */
    public static final int PULL = 0, HOLD = 1, DROP = 2, DROP_TIME = 3,
                            REST = 4, STEP_TIME = 5, WARM = 6;
    public static final int FIELDS = 7;

    /** What the strip is showing: a work set's four cells, one length, or nothing. */
    public static final int MODE_NONE = 0, MODE_WORK = 1, MODE_REST = 2, MODE_RAMP = 3,
                            MODE_WARM = 4;

    /** One tap: kPa for the pressures, seconds for the rest. Time per step on a ramp's step of
     *  more than one cycle moves by ONE WHOLE CYCLE of the step instead ({@link #stepTimeNext});
     *  its 5 s is the hold's, on a step that is one cycle (its length and its hold are one). */
    public static final int[] STEP = { 1, 5, 1, 1, 15, 5, 15 };
    /** The range each figure stays inside (kPa or seconds).
     *
     *  TIME PER STEP IS THE APP'S CLOCK, NOT THE WIRE'S. It was capped at 255 s, "the longest
     *  the pump takes" - but 255 s is the width of the hold byte and the drop byte, one phase
     *  of a cycle; a step has no length on the wire at all (the app STARTs the next slot when
     *  it is done). A manual ramp of 4:21 steps was shown as past a limit it was not past,
     *  and its + greyed. The bound now is a ramp step's even share of the hour a set may run
     *  (Model.Set#rampStepMaxSec: 3600 s ÷ its steps) - the fewest steps, two, give the
     *  largest share, and {@link #stepTimeRefusal} holds each ramp to its own. Every ramp an
     *  editor can build is inside it, because Model.Set#rampCycles is built to it. The hold
     *  (and the drop time) keep the wire's true 255 s. */
    public static final int[] MIN = { 12, 10, 0, 0, 30, 15, 30 };
    public static final int[] MAX = { 34, 255, 10, 30, 600, Model.Set.rampStepMaxSec(2), 600 };

    /** The pull's own ceiling: 10.0 inHg, whatever the safety ceiling allows above it. */
    public static final int CEILING_KPA = 34;
    /** A length cut from a step that is playing leaves at least this much of it. */
    public static final int LEFT_FLOOR_SEC = 5;
    /** "+30 s hold": the hold of the set playing, 30 s longer, 4:15 at most. */
    public static final int PLUS_HOLD_SEC = 30;

    /** The cells' names, as the strip prints them over the figure - always whole: a change on
     *  its way to the pump shows in the figure's amber (and the keys' spoken names), never by
     *  cutting the label ("Hold time · sen…", device check). */
    public static final String[] LABEL = {
        "Pull to · target", "Hold time", "Drop to · target", "Drop time",
        "Rest length", "Time per step", "Warm-up length"
    };

    /** The name a screen reader says with "Less" / "More". */
    public static final String[] SPOKEN = {
        "pull", "hold time", "drop", "drop time", "rest length", "time per step",
        "warm-up length"
    };

    /** Settings › On the run screen › "Where the − / + controls sit": the two answers, in
     *  Model#runStripWhere's order, and what each one means. */
    public static final String WHERE_TITLE = "Where the − / + controls sit";
    public static final String[] WHERE_NAMES = { "Pinned above the buttons", "Under the chart",
                                                 "Chart first on ramps" };
    public static final String[] WHERE_SAYS = {
        "Always in view while the routine runs. The chart gets less room.",
        "The chart gets its full height; scroll down a little to reach the controls.",
        "On a ramp step and in the warm-up the chart comes first, controls right under it. "
            + "Every other step keeps them pinned above the buttons."
    };

    /** A ramp's step or the warm-up is playing - the steps "Chart first on ramps" is about. */
    public static boolean rampKind(int mode) {
        return mode == MODE_RAMP || mode == MODE_WARM;
    }

    /** Does the chart come before the status block and the timer card? Only on a ramp's step
     *  (or the warm-up) when the setting is {@link Model#RUN_STRIP_RAMP_FIRST}. */
    public static boolean chartFirst(int where, int mode) {
        return Model.clampRunStripWhere(where) == Model.RUN_STRIP_RAMP_FIRST && rampKind(mode);
    }

    /** Do the − / + controls sit in the scrolling page, under the chart, rather than pinned in
     *  the footer? Always for "Under the chart"; on a ramp step for "Chart first on ramps";
     *  and whenever the pinned footer was found too tall for the screen (`squeezed`). */
    public static boolean stripInPage(int where, int mode, boolean squeezed) {
        int w = Model.clampRunStripWhere(where);
        return w == Model.RUN_STRIP_UNDER_CHART || squeezed || chartFirst(w, mode);
    }

    /** The strip's headers. */
    public static final String HEAD_WORK = "THIS SET · CHANGES APPLY NOW";
    public static final String HEAD_REST = "THIS REST · CUFF VENTED";
    public static final String HEAD_WARM = "WARM-UP · CHANGES APPLY NOW";
    /** A ramp's step playing: its four figures change THIS step (and, when More › says "Rest
     *  of this ramp", every step after it by the same move). */
    public static final String HEAD_RAMP_STEP = "THIS RAMP STEP · CHANGES APPLY NOW";
    /** Nothing is playing yet (the run is starting, or between steps): the strip stays in
     *  its place and says so - it never simply goes. */
    public static final String HEAD_NONE = "THE − / + COME WITH THE FIRST STEP";

    /**
     * CAN THE STRIP STAY PINNED? (Owner report, a Samsung phone in a trainer session: only the
     * buttons showed, no − / + card.) The pinned footer - the strip, the buttons and STOP - has
     * to leave the page above it at least `minScrollPx`, or the chart and the NOW card are
     * squeezed to nothing on a short screen or at a large font; then the strip moves into the
     * page, under the chart, where it scrolls and is never clipped. STOP stays pinned either way.
     */
    public static boolean fitsPinned(int screenPx, int footerPx, int minScrollPx) {
        if (screenPx <= 0 || footerPx <= 0) return true;
        return screenPx - footerPx >= minScrollPx;
    }

    /**
     * THE SCOPE OF A LIVE CHANGE, in the adjust sheet's words: segment 0 is the rest (of the
     * block, or of the ramp), segment 1 the step playing alone. A block of sets defaults to the
     * rest of the block; a RAMP defaults to this step only (owner request, 0.10) - its later
     * steps keep their plan unless More › says "Rest of this ramp".
     */
    public static final String[] SCOPE_BLOCK = { "Rest of this block", "This set only" };
    public static final String[] SCOPE_RAMP = { "Rest of this ramp", "This step only" };

    public static String[] scopeNames(boolean ramp) { return ramp ? SCOPE_RAMP : SCOPE_BLOCK; }

    /** Where the scope starts at a new occurrence: "this step only" on a ramp of more than one
     *  step, the rest of the block everywhere else. */
    public static boolean scopeStepOnlyAtStart(boolean rampOfSteps) { return rampOfSteps; }

    /** What an acknowledged change on a ramp's step says about the steps after it - or null
     *  when there are none. */
    public static String rampSaid(boolean stepOnly, int stepsAfter) {
        if (stepsAfter <= 0) return null;
        return stepOnly
            ? "This step only — the ramp's later steps keep their plan."
            : "The rest of this ramp moved with it, never past your ceiling.";
    }

    /**
     * WHICH STRIP THE STEP PLAYING GETS - read here, in one place, for the run screen and for
     * the tests that build the trainer's own routines. (Owner report from a device: a trainer
     * Length L1 session - a ramp warm-up, then its work as a five-step ramp - showed only
     * "Time per step" and "Warm-up length", never the pull, hold, drop or drop time.)
     *
     * A rest (or an inserted rest) is a rest; a step of the warm-up stage is the warm-up,
     * whether it is a hold or a ramp; a step of a ramp of more than one step is the ramp; every
     * other step is a set. MODE_NONE only when nothing is playing.
     */
    public static int modeAt(Model model, Model.Routine r, java.util.List<Model.Preset> plan,
                             int idx, boolean restingNow) {
        if (plan == null || idx < 0 || idx >= plan.size()) return MODE_NONE;
        Model.Preset cur = plan.get(idx);
        if (restingNow || cur.rest) return MODE_REST;
        if (warmUpAt(r, cur)) return MODE_WARM;
        if (rampAt(model, plan, idx)) return MODE_RAMP;
        return MODE_WORK;
    }

    /** The preset belongs to the warm-up every builder writes (never a retention stage). */
    public static boolean warmUpAt(Model.Routine r, Model.Preset cur) {
        if (r == null || cur == null || cur.stageIdx < 0 || cur.stageIdx >= r.stages.size())
            return false;
        Model.Stage st = r.stages.get(cur.stageIdx);
        return RunShape.isWarmUp(st) && !st.retention;
    }

    /** The preset at `idx` is a step of a ramp of more than one step. */
    public static boolean rampAt(Model model, java.util.List<Model.Preset> plan, int idx) {
        if (model == null || plan == null || idx < 0 || idx >= plan.size()) return false;
        Model.Preset cur = plan.get(idx);
        if (cur == null || cur.setId == null) return false;
        Model.Set set = model.set(cur.setId);
        return set != null && set.ramp && RunEdit.stepOfSet(plan, idx)[1] > 1;
    }

    /** THE FOUR FIGURES - pull, hold, drop, drop time - are shown and changed live in every
     *  step at pressure: a set, a ramp's step and the warm-up. A rest has none. */
    public static boolean showsGrid(int mode) {
        return mode == MODE_WORK || mode == MODE_RAMP || mode == MODE_WARM;
    }

    /** The length cell under the figures (or alone, in a rest), or -1 for none. */
    public static int lengthField(int mode) {
        switch (mode) {
            case MODE_REST: return REST;
            case MODE_RAMP: return STEP_TIME;
            case MODE_WARM: return WARM;
            default:        return -1;
        }
    }

    /** The strip's header for a mode. */
    public static String headOf(int mode) {
        switch (mode) {
            case MODE_WORK: return HEAD_WORK;
            case MODE_REST: return HEAD_REST;
            case MODE_RAMP: return HEAD_RAMP_STEP;
            case MODE_WARM: return HEAD_WARM;
            default:        return HEAD_NONE;
        }
    }

    /* ============================ THE STEP IS AT LEAST ONE CYCLE ============================
     *
     * ONE RULE FOR A RAMP'S STEP AND THE WARM-UP'S (owner decision, 0.10 - option (a): it
     * keeps the ramp's timing). A step's cycle - its hold plus its drop time - never becomes
     * longer than the step itself: past the step's end the pump never reaches the drop (or the
     * rest of the hold), so the figure would be one nobody gets. Both ways round:
     *   - a hold or a drop time that would make the cycle longer than the step is REFUSED,
     *     "A cycle can't be longer than the step, 1:12 — lengthen Time per step first.";
     *   - the step's length (Time per step, Warm-up length) never goes below one cycle of the
     *     step playing, "Time per step can't be shorter than one cycle, 0:43 — shorten the hold
     *     or the drop time first.".
     * Nothing grows on its own to make room - EXCEPT A STEP THAT IS ONE CYCLE (owner, 0.10):
     * when the step playing is exactly one cycle (hold + drop time = the step, within
     * ONE_CYCLE_SLACK_MS) its length and its cycle are one figure. A hold or drop-time change
     * moves the step's time with it; with "Rest of this ramp" every later one-cycle step moves
     * too (each one cycle again); and Time per step (or Warm-up length) ± on it IS Hold ±, down
     * to the hold's minimum. The warm-up's prime - a 4:00 hold in a 4:00 step - is one such step.
     * A live change CARRIES THE HOLD UNDER WAY ON (HoldCarryOn, owner report from a real pump):
     * 1:00 into a 2:05 step, hold +10 s, the pump is sent the 1:10 of hold that is left of the
     * new 2:10, and the step ends 2:15 from its start - never a repeated hold. Resume and Revert
     * start the slot again, and on such a step keep it whole (keepSetsWhole, oneCycleRestartMs).
     * A step is judged one cycle as it STARTED and stays one for its whole run.
     * A cycle the plan already made longer than its step
     * is never made longer still, and may always be shortened (a tap toward the rule is never
     * refused). With "Rest of this ramp", every later step is asked the same about the same
     * change. It holds on every road a hold, a drop time or a length takes: the strip, the
     * adjust sheet (SessionActivity#applyOverride), the strip's lengths and Coming steps' time
     * per step. */

    /** How near a step's length must be to its cycle to be ONE cycle (the ladder spreads a few
     *  milliseconds of remainder, and a length is shown in whole seconds). */
    public static final long ONE_CYCLE_SLACK_MS = 1000L;

    /** Is a step of `stepMs` exactly one cycle of (uh, lh)? */
    public static boolean oneCycle(int uh, int lh, long stepMs) {
        long cyc = (long) (Math.max(0, uh) + Math.max(0, lh)) * 1000L;
        return cyc > 0 && stepMs > 0 && Math.abs(cyc - stepMs) <= ONE_CYCLE_SLACK_MS;
    }

    /** A one-cycle step's new length when its cycle goes from (wasUh, wasLh) to (nowUh, nowLh):
     *  moved by exactly the change, its few milliseconds of slack kept. */
    public static long oneCycleStepMs(int wasUh, int wasLh, long stepMs, int nowUh, int nowLh) {
        return Math.max(1000L, stepMs + (long) ((nowUh + nowLh) - (wasUh + wasLh)) * 1000L);
    }

    /** The later steps of the ramp (idx+1 .. idx+left) that are one cycle, each as {plan index,
     *  its slack in ms} - read BEFORE "Rest of this ramp" moves their figures. */
    public static long[][] oneCycleLater(java.util.List<Model.Preset> plan, int idx, int left) {
        java.util.List<long[]> out = new java.util.ArrayList<long[]>();
        if (plan != null)
            for (int k = idx + 1; k <= idx + left && k < plan.size(); k++) {
                Model.Preset p = plan.get(k);
                if (oneCycle(p.uh, p.lh, p.durMs))
                    out.add(new long[]{ k, p.durMs - (long) (p.uh + p.lh) * 1000L });
            }
        return out.toArray(new long[0][]);
    }

    /** A one-cycle step's length once its figures moved: one cycle again, its slack kept. */
    public static long oneCycleLengthMs(Model.Preset p, long slackMs) {
        return Math.max(1000L, (long) (p.uh + p.lh) * 1000L + slackMs);
    }

    /** A one-cycle step restarted `elapsedMs` into it with a cycle of `cycleSec`: its new
     *  length - the part already run plus one whole new cycle. The same figure as a set's
     *  (SetClock#retimeMs with one set left), which the tests hold it to. */
    public static long oneCycleRestartMs(long elapsedMs, int cycleSec) {
        return Math.max(1000L, Math.max(0L, elapsedMs) + (long) Math.max(0, cycleSec) * 1000L);
    }

    /** Time per step − on a one-cycle step whose hold is at its shortest. */
    public static String oneCycleShortest(String lengthName) {
        return "The hold is at its shortest, " + Model.Fmt.t(MIN[HOLD]) + " — " + lengthName
            + " can't be shorter on a step that is one cycle.";
    }

    /** "A cycle can't be longer than the step, 1:12 — lengthen Time per step first." */
    public static String cycleTooLong(int stepSec, String lengthName) {
        return "A cycle can't be longer than the step, " + Model.Fmt.t(Math.max(0, stepSec))
            + " — lengthen " + lengthName + " first.";
    }

    /** "Time per step can't be shorter than one cycle, 0:43 — shorten the hold or the drop
     *  time first." */
    public static String stepTooShort(int cycleSec, String lengthName) {
        return lengthName + " can't be shorter than one cycle, " + Model.Fmt.t(Math.max(0, cycleSec))
            + " — shorten the hold or the drop time first.";
    }

    /** A step's length in whole seconds, as the strip shows it. */
    public static int stepSec(long durMs) { return (int) ((Math.max(0L, durMs) + 500L) / 1000L); }

    /**
     * WHY THE STEP PLAYING'S HOLD / DROP TIME MAY NOT GO FROM (wasUh, wasLh) - what is in force -
     * TO (nowUh, nowLh), or null. Asked of the step playing (`idx`, its length as it stands) and,
     * when `restOfRamp`, of every later step of its ramp, each moved by the same change.
     */
    public static String cycleRefusal(java.util.List<Model.Preset> plan, int idx, int wasUh,
                                      int wasLh, int nowUh, int nowLh, boolean restOfRamp,
                                      String lengthName) {
        return cycleRefusal(plan, idx, wasUh, wasLh, nowUh, nowLh, restOfRamp, lengthName, -1L);
    }

    /**
     * The same, knowing how much of the step playing has run (`elapsedMs`, or −1): a ONE-CYCLE
     * step is never refused for its cycle - its time moves with it - but it is never cut to
     * under what has run (plus LEFT_FLOOR_SEC), and what it and the later one-cycle steps add
     * stays inside the two-hour stop.
     */
    public static String cycleRefusal(java.util.List<Model.Preset> plan, int idx, int wasUh,
                                      int wasLh, int nowUh, int nowLh, boolean restOfRamp,
                                      String lengthName, long elapsedMs) {
        if (plan == null || idx < 0 || idx >= plan.size()) return null;
        return cycleRefusal(plan, idx, wasUh, wasLh, nowUh, nowLh, restOfRamp, lengthName,
            elapsedMs, oneCycle(wasUh, wasLh, plan.get(idx).durMs));
    }

    /**
     * The same, told whether the step playing is ONE CYCLE (`one` - judged as the step
     * started). A one-cycle step's hold carries on to its new length and its end moves by the
     * change (HoldCarryOn), so it is never cut short; only what it adds is asked, against the
     * two-hour stop.
     */
    public static String cycleRefusal(java.util.List<Model.Preset> plan, int idx, int wasUh,
                                      int wasLh, int nowUh, int nowLh, boolean restOfRamp,
                                      String lengthName, long elapsedMs, boolean one) {
        if (plan == null || idx < 0 || idx >= plan.size()) return null;
        int dUh = nowUh - wasUh, dLh = nowLh - wasLh, d = dUh + dLh;
        Model.Preset cur = plan.get(idx);
        if (d == 0 || (d < 0 && !one)) return null;         // no longer: always
        long move = 0L;
        if (one) {
            move += oneCycleStepMs(wasUh, wasLh, cur.durMs, nowUh, nowLh) - cur.durMs;
        } else {
            int step = stepSec(cur.durMs);
            if (nowUh + nowLh > step) return cycleTooLong(step, lengthName);
        }
        if (restOfRamp) {
            int left = RunEdit.remainingStepsOfSet(plan, idx);
            long most = Model.Set.rampStepMaxSec(RunEdit.stepOfSet(plan, idx)[1]) * 1000L;
            for (int k = idx + 1; k <= idx + left; k++) {
                Model.Preset p = plan.get(k);
                if (oneCycle(p.uh, p.lh, p.durMs)) { move += d * 1000L; continue; }
                int cyc = RunEdit.clampSeconds(p.uh + dUh) + RunEdit.clampSeconds(p.lh + dLh);
                // ...AND EACH LATER STEP GOES ON WHOLE CYCLES OF ITS NEW HOLD + DROP (0.10
                // follow-up, RunEdit#wholeCyclesLater): what that adds is asked too.
                move += RunEdit.rampStepWholeMs(p.durMs, cyc, most) - p.durMs;
                if (d <= 0) continue;
                int st = stepSec(p.durMs);
                if (cyc > st && cyc > p.uh + p.lh)
                    return "A later step's cycle can't be longer than its step, " + Model.Fmt.t(st)
                        + " — change this step only, or lengthen " + lengthName + " first.";
            }
        }
        if (move > 0) return ComingSteps.addRefusal(ComingSteps.totalMs(plan), move);
        return null;
    }

    /**
     * The sheet's Reshape: the remaining steps re-anchored from the step playing's (uh, lh) to
     * the end's, interpolated as RunEdit#reshapeRemaining does (the k-th of m at k/m). Why a
     * step would get a cycle longer than itself - and longer than it has now - or null.
     */
    public static String reshapeCycleRefusal(java.util.List<Model.Preset> plan, int idx,
                                             int aUh, int aLh, int endUh, int endLh,
                                             String lengthName) {
        if (plan == null || idx < 0 || idx >= plan.size()) return null;
        int m = RunEdit.remainingStepsOfSet(plan, idx);
        for (int k = 1; k <= m; k++) {
            double f = (double) k / m;
            Model.Preset p = plan.get(idx + k);
            int cyc = (int) Math.round(aUh + (endUh - aUh) * f)
                    + (int) Math.round(aLh + (endLh - aLh) * f);
            int st = stepSec(p.durMs);
            if (cyc > st && cyc > p.uh + p.lh) return cycleTooLong(st, lengthName);
        }
        return null;
    }

    /** Why the step playing may not become `newStepMs` long with a cycle of `cycleSec`, or
     *  null - only a SHORTER step is asked (a longer one always fits). */
    public static String stepLengthRefusal(long oldStepMs, long newStepMs, int cycleSec,
                                           String lengthName) {
        if (newStepMs >= oldStepMs) return null;
        if (newStepMs < cycleSec * 1000L) return stepTooShort(cycleSec, lengthName);
        return null;
    }

    /** THE "FLAT THERE" NOTE ONCE PER RAMP: said for the ramp whose first step is `rampFirst`
     *  unless it was already said for it (`saidFor`); null otherwise. */
    public static String rampFlatSaidOnce(int clamped, int ceilKpa, int rampFirst, int saidFor) {
        if (rampFirst == saidFor) return null;
        return rampFlatSaid(clamped, ceilKpa);
    }

    /** THE DROP FLOOR (the strip's MAX[DROP], 10 kPa): the highest a drop may be raised to
     *  from `inForceLo` - the floor, or the drop itself when it is already higher (a drop above
     *  the floor may always come down, never go further up) - where the pull is not known.
     *  Knowing it, the strip's "+" goes past the floor as the person's own call (asked once,
     *  RunEdit#dropNeedsWarning), up to RunEdit#dropTopKpa, and what it raises the step to is
     *  that step's highest from then on (SessionActivity#allowDropRaise) - which is where the
     *  adjust sheet's drop slider stops (RunEdit#dropCapOf). */
    public static int dropMax(int inForceLo) {
        return Math.max(MAX[DROP], inForceLo);
    }

    /** Why a drop may not go from `wasLo` to `nowLo` on a step whose highest drop is `cap`
     *  (RunEdit#dropCapOf), or null - a drop may always come down. */
    public static String dropRefusal(int wasLo, int nowLo, int cap) {
        if (nowLo <= wasLo || nowLo <= cap) return null;
        return "The drop stops at " + Model.Fmt.p(cap) + " — the drop floor.";
    }

    /** Why a drop may not go from `wasLo` to `nowLo`, or null. */
    public static String dropRaiseRefusal(int wasLo, int nowLo) {
        if (nowLo <= wasLo || nowLo <= dropMax(wasLo)) return null;
        return "The drop stops at " + Model.Fmt.p(dropMax(wasLo)) + " — the drop floor.";
    }

    /** "Rest of this ramp" moved later steps whose pull stopped at the safety ceiling: the
     *  ramp is flatter there than its shape, and that is said, never left to be found. */
    public static String rampFlatSaid(int clamped, int ceilKpa) {
        if (clamped <= 0) return null;
        return "The rest of this ramp moved with it; " + (clamped == 1
            ? "one later step stops"
            : clamped + " later steps stop")
            + " at your ceiling, " + Model.Fmt.p(ceilKpa) + " — the ramp is flat there.";
    }

    /** "Rest of this ramp" moved later steps whose pull stopped at a limit under the ceiling -
     *  a hard limit, or the strip's usual one (review 2, finding 2): said like the ceiling's. */
    public static String rampLimitSaid(int clamped, int limitKpa) {
        if (clamped <= 0) return null;
        return "The rest of this ramp moved with it; " + (clamped == 1
            ? "one later step stops"
            : clamped + " later steps stop")
            + " at " + Model.Fmt.p(limitKpa) + ", as far as it goes for you "
            + "— the ramp is flat there.";
    }

    /** The page the pinned footer must leave above it, in dp. */
    public static final int MIN_PAGE_DP = 160;

    /** "THIS RAMP · −3.0 → −5.9 inHg": the pressures the ramp climbs between. */
    public static String rampHead(int fromKpa, int toKpa) {
        return "THIS RAMP · " + Model.Fmt.pBare(fromKpa) + " → " + Model.Fmt.p(toKpa);
    }

    /**
     * THE STRIP'S HEADER ON A RAMP'S STEP: where the whole ramp goes, not only the step playing.
     * The Pull cell shows the step's own pull - the ramp's start on its first step - and nothing
     * on the strip said where it climbs to (owner report: "Pull −8.0" on step 1 of a ramp to
     * −10.3). The first and last steps of the ramp occurrence at `idx`, as the plan stands (a
     * "+ step" or a Coming-steps change included), each held to the ceiling as the wire holds it.
     */
    public static String rampHeadAt(java.util.List<Model.Preset> plan, int idx, int ceilKpa) {
        if (plan == null || idx < 0 || idx >= plan.size()) return HEAD_RAMP_STEP;
        int[] kn = RunEdit.stepOfSet(plan, idx);
        int first = idx - (kn[0] - 1), last = first + kn[1] - 1;
        if (first < 0 || last >= plan.size() || last <= first) return HEAD_RAMP_STEP;
        return rampHead(RunEdit.clampUpper(plan.get(first).up, ceilKpa),
                        RunEdit.clampUpper(plan.get(last).up, ceilKpa));
    }

    /** "The drop stops at −5.3 inHg — the drop floor." for a drop at `value` asked to go up. */
    public static String dropFloorSaid(int value) {
        return "The drop stops at " + Model.Fmt.p(dropMax(value)) + " — the drop floor.";
    }

    /* ============================ TIME PER STEP, IN WHOLE CYCLES (0.10) ======================
     *
     * Every ramp step is whole cycles of its own hold and drop (Model.Set#rampCycles), so the
     * strip's "Time per step" on a step of more than one cycle moves by one whole cycle a tap -
     * the step playing by one cycle of what is in force, each later step by one of its own. A
     * 5 s tap left the step ending inside a hold, and the pump went straight on to the next
     * step's pull with no drop. Never under one cycle, never past a step's share of the hour. */

    /** The step playing's new length when Time per step moves one tap `dir` (−1 / +1): EXACTLY
     *  one cycle of `cycleSec` more or fewer than it has now. A step that ends as one of the
     *  pump's cycles ends still does - after a live hold / drop change mid-step its length is
     *  the part run plus whole cycles from where the pump started the new one, not a multiple
     *  of the cycle, and rounding it to one would move its end off the pump's (0.10 follow-up).
     *  On a step that is k whole cycles it is k ± 1 of them. Under one cycle comes back below
     *  it, for {@link #stepTimeRefusal} to refuse. */
    public static int stepTimeNext(int valueSec, int dir, int cycleSec) {
        int c = Math.max(1, cycleSec);
        return Math.max(0, Math.max(0, valueSec) + (dir < 0 ? -c : c));
    }

    /** A later step's new length, in ms, one whole cycle of its own longer or shorter. */
    public static long stepTimeNextMs(Model.Preset p, int dir) {
        int c = Math.max(1, p.uh + p.lh);
        return stepTimeNext(stepSec(p.durMs), dir, c) * 1000L;
    }

    /** "30:00 is the longest step — a ramp's hour, shared by its 2 steps." */
    public static String stepTimeLongest(int maxSec, int steps) {
        return Model.Fmt.t(maxSec) + " is the longest step — a ramp's hour, shared by its "
            + Math.max(2, steps) + " steps.";
    }

    /** Why a ramp of `steps` steps may not take a step from `valueSec` to `newSec` with a cycle
     *  of `cycleSec`, or null: never under one cycle, never past the step's share of the hour
     *  (Model.Set#rampStepMaxSec). A tap toward the range is never refused. */
    public static String stepTimeRefusal(int valueSec, int newSec, int cycleSec, int steps) {
        if (newSec == valueSec) return null;
        String len = LABEL[STEP_TIME];
        if (newSec < valueSec && newSec < Math.max(1, cycleSec)) return stepTooShort(cycleSec, len);
        int most = Model.Set.rampStepMaxSec(steps);
        if (newSec > valueSec && newSec > most) return stepTimeLongest(most, steps);
        return null;
    }

    /** The pull's limit now: 10.0 inHg, or the safety ceiling when that is lower. */
    public static int pullCeiling(int ceilKpa) {
        return Math.min(CEILING_KPA, Math.max(0, ceilKpa));
    }

    /**
     * WHY ONE TAP OF `delta` ON `field` FROM `value` IS REFUSED, or null when it may go.
     *
     * @param other      the partner pressure: the drop for PULL (−1 when the step has no
     *                   drop - a hold-only step's drop is wire filler and moves with the
     *                   pull), the pull for DROP; ignored otherwise
     * @param elapsedSec how much of the step playing has run, for the lengths (REST, WARM,
     *                   STEP_TIME): a length is never cut below that plus
     *                   {@link #LEFT_FLOOR_SEC}
     * @param ceilKpa    the safety ceiling (Settings), which the pull never passes either
     */
    public static String refusal(int field, int value, int delta, int other, int elapsedSec,
                                 int ceilKpa) {
        return refusal(field, value, delta, other, elapsedSec, ceilKpa, pullCeiling(ceilKpa));
    }

    /**
     * {@link #refusal} with the pull's limit given (0.10): {@link #pullLimit} - the strip's
     * usual 10.0 inHg, or past it as far as the person has confirmed once, never past the run's
     * hard limit.
     */
    public static String refusal(int field, int value, int delta, int other, int elapsedSec,
                                 int ceilKpa, int pullCapKpa) {
        if (field < 0 || field >= FIELDS) return "Nothing to change.";
        if (delta == 0) return null;
        int v = value + delta;
        boolean up = delta > 0;
        switch (field) {
            case PULL: {
                int ceil = Math.max(0, Math.min(pullCapKpa, ceilKpa));
                if (up && v > ceil) return mag(Model.Fmt.p(ceil)) + " is the ceiling.";
                if (!up && v < MIN[PULL]) return "That is the lowest.";
                if (!up && other >= 0 && v <= other) return "The pull stays above the drop.";
                // A drop above the floor stays 1.0 inHg under the pull (RunEdit#dropTopKpa).
                if (!up && RunEdit.dropPastGap(other, v)) return RunEdit.dropGapSaid();
                return null;
            }
            case DROP:
                if (!up && v < MIN[DROP]) return "Already a full vent.";
                // PAST THE FLOOR IS THE PERSON'S CALL (owner, 2026-09-30): the run screen asks
                // once (RunEdit#dropNeedsWarning) and it goes, up to 1.0 inHg under the pull
                // (RunEdit#dropTopKpa). Not knowing the pull, the floor holds as it did - said
                // as one: "That is the highest" read as if the drop had no room left anywhere.
                if (up && other < 0 && v > MAX[DROP]) return dropFloorSaid(value);
                if (up && other >= 0 && v >= other) return "The drop stays below the pull.";
                if (up && other >= 0 && RunEdit.dropPastGap(v, other)) return RunEdit.dropGapSaid();
                return null;
            case HOLD:
                if (!up && v < MIN[field]) return "That is the lowest.";
                if (up && v > MAX[field]) return "4:15 is the longest the pump takes.";
                return null;
            case STEP_TIME:
                if (!up && v < MIN[field]) return "That is the lowest.";
                if (up && v > MAX[field]) return stepTimeLongest(MAX[field], 2);
                if (!up && elapsedSec >= 0 && v < elapsedSec + LEFT_FLOOR_SEC)
                    return "Use Skip step to finish it now.";
                return null;
            case DROP_TIME:
                if (!up && v < MIN[DROP_TIME]) return "That is the lowest.";
                if (up && v > MAX[DROP_TIME]) return "That is the highest.";
                return null;
            case REST:
            case WARM:
                if (!up && v < MIN[field]) return "30 s is the shortest.";
                if (up && v > MAX[field]) return "10:00 is the longest.";
                if (!up && elapsedSec >= 0 && v < elapsedSec + LEFT_FLOOR_SEC)
                    return field == REST ? "Use End rest to finish it now."
                                         : "Use Skip warm-up to finish it now.";
                return null;
            default:
                return null;
        }
    }

    /** One tap's refusal with the field's own step. */
    public static String stepRefusal(int field, int value, int dir, int other, int elapsedSec,
                                     int ceilKpa) {
        if (field < 0 || field >= FIELDS) return "Nothing to change.";
        return refusal(field, value, dir < 0 ? -STEP[field] : STEP[field], other, elapsedSec,
                       ceilKpa);
    }

    /** The same with the pull's limit given ({@link #pullLimit}). */
    public static String stepRefusal(int field, int value, int dir, int other, int elapsedSec,
                                     int ceilKpa, int pullCapKpa) {
        if (field < 0 || field >= FIELDS) return "Nothing to change.";
        return refusal(field, value, dir < 0 ? -STEP[field] : STEP[field], other, elapsedSec,
                       ceilKpa, pullCapKpa);
    }

    /* ------------------------------------------ past the strip's usual 10.0 inHg (0.10) */

    /**
     * THE PULL'S LIMIT ON THE RUN SCREEN NOW (the owner's decision, 0.10: a programme cap is
     * warned once, your call). The strip's usual {@link #CEILING_KPA} (10.0 inHg), or past it
     * as far as the person has confirmed this run (`warnedKpa`); never past `hardKpa` - the
     * run's hard limit (the ceiling, and for a plan's routine "Most you will go to", 15 inHg
     * and a new person's first-month 6 inHg - Scale) - nor the ceiling.
     */
    public static int pullLimit(int ceilKpa, int hardKpa, int warnedKpa) {
        int hard = Math.max(0, Math.min(ceilKpa, hardKpa));
        return Math.min(hard, Math.max(CEILING_KPA, warnedKpa));
    }

    /**
     * DOES ONE "+" ON THE PULL, TO `next`, NEED THE ONE-TIME WARNING? When it is past the
     * strip's usual limit and past what the person has already confirmed, and still inside the
     * hard limit - past that it is refused, never asked. Null when it does not; else the words.
     */
    public static String pullWarning(int next, int ceilKpa, int hardKpa, int warnedKpa) {
        if (next <= pullLimit(ceilKpa, hardKpa, warnedKpa)) return null;
        if (next > Math.min(ceilKpa, hardKpa)) return null;
        return "Pull past " + mag(Model.Fmt.p(CEILING_KPA)) + "? That is past the strip's usual "
            + "limit - go there only if this pull suits you. You are asked once for each new "
            + "highest pull this run. Nothing goes past " + mag(Model.Fmt.p(
                Math.min(ceilKpa, hardKpa))) + ".";
    }

    /** The one-time warning's buttons. */
    public static String pullWarnGo(int next) {
        return "Pull to " + mag(Model.Fmt.p(next));
    }

    /**
     * "+30 s hold": the hold the set playing goes to - 30 s longer, never past 4:15 - or -1
     * when it is already there (the caller says {@link #refusal}'s words).
     */
    public static int plusHold(int uh) {
        if (uh >= MAX[HOLD]) return -1;
        return Math.min(MAX[HOLD], uh + PLUS_HOLD_SEC);
    }

    /** What "+30 s hold" says when it moved the hold to `newUh`. */
    public static String plusHoldSaid(int oldUh, int newUh) {
        return newUh - oldUh >= PLUS_HOLD_SEC ? "Hold is 30 s longer (4:15 at most)."
                                              : "Hold is 4:15, the longest the pump takes.";
    }

    /** Below this much left in the block, "+30 s hold" has nothing to lengthen. */
    public static final long PLUS_HOLD_MIN_LEFT_MS = 2000L;

    /**
     * WHY "+30 s hold" IS REFUSED NOW, or null - so it is never refused in silence, in the
     * drop phase or anywhere else: at 4:15 already, or with the block ending in a moment.
     */
    public static String plusHoldRefusal(int uh, long blockLeftMs) {
        if (plusHold(uh) < 0) return "4:15 is the longest the pump takes.";
        if (blockLeftMs < PLUS_HOLD_MIN_LEFT_MS)
            return "This block ends in a moment — there is no hold left to lengthen.";
        return null;
    }

    /**
     * WHAT "+30 s hold" SAYS WHEN IT GOES. The pump takes a change by starting the set again
     * from its hold - in the drop phase that means back up to the pull now, which is said, not
     * left to be felt. (When the change is in force the run says how far the block's end moved:
     * SessionActivity#setClockAfterChange.)
     */
    public static String plusHoldSaid(int oldUh, int newUh, boolean inDrop) {
        return HoldCarryOn.plusHoldSaid(Math.max(0, newUh - oldUh), inDrop, false, false);
    }

    /**
     * THE FIGURE A CELL SHOWS: a pressure without its unit ("−5.9"), a drop of nothing as
     * "vent", a drop time in seconds ("5 s"), every other length as m:ss.
     */
    public static String value(int field, int v) {
        switch (field) {
            case PULL: return Model.Fmt.pBare(v);
            case DROP: return v <= 0 ? "vent" : Model.Fmt.pBare(v);
            case DROP_TIME: return v + " s";
            default: return Model.Fmt.t(Math.max(0, v));
        }
    }

    /** "−10.0 inHg" → "10.0 inHg": the ceiling said as a depth. */
    static String mag(String p) {
        return p != null && p.startsWith("−") ? p.substring(1) : p;
    }
}
