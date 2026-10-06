package org.openpump;

/**
 * C6 - "AT PRESSURE ONLY": ONE TICK OF A SET'S CLOCK (F12, Model#tupTiming).
 *
 * Found on the emulator: a Trainer routine of 7 two-minute holds at 5.0 inHg (14:35) run
 * with Set timing at "At pressure only" took 29:34, every set clock ran its full time at
 * pressure, and the summary said "time under pressure 7.5 min of 14.0 min planned · −47%".
 * Two measures of one thing had disagreed by half. AtPressureTimingTest reproduces it
 * against the simulated pump (net 472.8 s of 840 s, run 1777 s) and pins the agreement.
 *
 * TWO CAUSES, both in the clock, because the net is the plan's own definition and governs:
 *
 *   THE LINE. The clock asked Session#atOrAbove - the CONTROL band, 5 % under whatever was
 *   commanded (16.15 kPa at 17) - while the net counts from the level's floor less the
 *   user's counting tolerance (Model#tupCountTolKpa: 2 %, 16.59 kPa at Level 1). A pump
 *   pulls once and coasts (SimPump, Proto's hardware note), so every hold spent a stretch
 *   between the two lines that the clock called delivered and the net did not: 61 s of
 *   each 120 s hold against 29 s. Settings puts "What counts as at pressure" beside "Set
 *   timing" as the same subject seen from both ends; now it is the same line.
 *
 *   THE DROPS. A set's duration is its cycles, drops included, and a drop is never at the
 *   working pressure - so the clock paused through every planned drop and the set owed it
 *   again at pressure. A 10 s-on, 10 s-off pulse set ran twice its planned time under
 *   pressure. The plan (PlannedTime#underPressureSec) counts only the hold half and the net
 *   excludes the drop half (Session's dropPhase, F14). Now the clock moves through the
 *   drop half too, as far as the set PLANNED to drop and no further: a set lengthened by
 *   the clock runs more cycles, and those extra drops are not time under pressure either.
 *
 * So a set timed at pressure ends when its hold half has been at the net's line for its
 * planned time - which is exactly its share of the plan's time under pressure, and exactly
 * what the net counts. Where the plan does not score a run (a routine it did not write,
 * an out-of-net stage, a set commanded below the line) there is no net to agree with: the
 * set is timed against its own target, in the control band, as it always was.
 *
 * PURE, so the rule is driven through a whole simulated run on the desktop; the Activity
 * keeps only the bookkeeping (the deadline, the nudge, the screen's paused figure).
 */
public final class TupClock {
    private TupClock() { }

    /** What one tick did: how much of it the set's clock did NOT move (the deadline goes out
     *  by exactly this), and how much of the set's planned drop time it used. */
    public static final class Step {
        public final long pushMs, dropUsedMs;
        Step(long push, long drop) { pushMs = push; dropUsedMs = drop; }
    }

    /**
     * THE PRESSURE A READING HAS TO REACH for the set's clock to move.
     *
     * `netLineKpa` is the line this run's net is counted from (Model#scoringFloorKpa less
     * Model#tupCountTolKpa) when the preset is one the net counts - a scored run, outside a
     * rest and every out-of-net stage - and 0 otherwise. A set commanded at or above it is
     * timed from it, as the net counts it and as the plan planned it; a set commanded below
     * it (PlannedTime's "below the line: planned to count nothing") would never reach it and
     * would never end, so it keeps its own target.
     */
    public static double lineKpa(double commandedKpa, double netLineKpa) {
        if (netLineKpa > 0 && commandedKpa >= netLineKpa) return netLineKpa;
        return commandedKpa - Session.bandKpa(commandedKpa);
    }

    /**
     * A DROP SET ABOVE THE FLOOR IS KEPT OUT BY PHASE - AND ONLY SUCH A DROP CHANGES ANYTHING
     * (owner, 2026-09-30). A drop may now be set above RunEdit#DROP_FLOOR_KPA (the dose's own
     * floor), so the seconds in its drop half are left out of the dose by phase
     * (Session#noteSample's `dropPhase`) and that half is read from where the pump's cycle
     * last began (the set clock, {@link #readsSetClock}). A drop at or under the floor -
     * every trainer routine's, every run before this - is counted exactly as it always was:
     * the dose integrates whatever the samples read (the seconds the cuff takes to bleed
     * down from the pull into its drop included), and the drop half is read from the step's
     * arming, so its net and dose come out identical to the last bit (DropCountingParityTest).
     */
    public static boolean doseLeavesOut(boolean inDropHalf, int dropKpa) {
        return inDropHalf && dropKpa > RunEdit.DROP_FLOOR_KPA;
    }

    /** Does the drop half of a step whose drop is `dropKpa` read the set clock (where the
     *  pump's cycle last began) rather than the step's arming? Only above the floor - see
     *  {@link #doseLeavesOut}. */
    public static boolean readsSetClock(int dropKpa) {
        return dropKpa > RunEdit.DROP_FLOOR_KPA;
    }

    /**
     * HOW FAR INTO THE PRESET THE SET CLOCK PUTS `nowMs`, for a split over the last `stepMs`
     * (review M3, 2026-09-30): {@link #dropMsBetween} wants a running time, the set clock gives
     * how far into its cycle the pump is (SetClock#inCycleMs, under one cycle). Whole cycles in
     * front of it change nothing in the split - each cycle has the same halves - so enough are
     * added that the stretch `stepMs` back from it never starts before zero. With this the
     * at-pressure set clock splits a tick by the same clock the net marks a drop frame by
     * (#readsSetClock), where it used to read the arming stamp and the two could disagree.
     */
    public static long setClockIntoMs(long inCycleMs, long cycleMs, long stepMs) {
        long c = Math.max(1L, cycleMs);
        return Math.max(0L, inCycleMs) + c * (Math.max(0L, stepMs) / c + 1L);
    }

    /**
     * WHETHER `intoMs` INTO A PRESET FALLS IN THE DROP HALF OF ITS CYCLE (F14): (time into
     * the preset) mod (hold + drop) >= hold. A preset that does not drop - no drop dwell,
     * equal setpoints, a stitch chunk (RunEdit#holdOnly) - never does. The one split the net
     * recorder marks a drop frame by and this clock moves through a drop by, so the two can
     * never disagree about which half a second was in.
     */
    public static boolean inDrop(int uh, int lh, int lo, int up, boolean cyclePart, long intoMs) {
        if (RunEdit.holdOnly(lh, lo, up, cyclePart) || intoMs < 0) return false;
        long cycMs = (long) Math.max(1, uh + lh) * 1000L;
        return intoMs % cycMs >= (long) Math.max(0, uh) * 1000L;
    }

    /** The part of `durMs` a preset spends in the HOLD half of its cycles, by the same
     *  split - what PlannedTime#holdPartMs plans under pressure. */
    public static long holdPartMs(int uh, int lh, int lo, int up, boolean cyclePart, long durMs) {
        if (durMs <= 0) return 0L;
        if (RunEdit.holdOnly(lh, lo, up, cyclePart)) return durMs;
        long cycMs = (long) Math.max(1, uh + lh) * 1000L;
        long holdMs = (long) Math.max(0, uh) * 1000L;
        return (durMs / cycMs) * holdMs + Math.min(durMs % cycMs, holdMs);
    }

    /** The part of `durMs` a preset spends in the DROP half: the drop time the set planned,
     *  and so the most of its clock a drop may move. */
    public static long dropPartMs(int uh, int lh, int lo, int up, boolean cyclePart, long durMs) {
        return Math.max(0L, durMs - holdPartMs(uh, lh, lo, up, cyclePart, durMs));
    }

    /** How much of the stretch from `fromMs` to `toMs` into a preset falls in its drop half
     *  - the cumulative drop time at the end less that at the start, so a tick that straddles
     *  the boundary is split exactly where the cycle turns, not assigned whole to one side. */
    public static long dropMsBetween(int uh, int lh, int lo, int up, boolean cyclePart,
                                     long fromMs, long toMs) {
        long a = Math.max(0L, fromMs), b = Math.max(0L, toMs);
        if (b <= a) return 0L;
        return dropPartMs(uh, lh, lo, up, cyclePart, b) - dropPartMs(uh, lh, lo, up, cyclePart, a);
    }

    /**
     * ONE TICK OF `stepMs`, of which `dropMs` fell in the drop half (dropMsBetween).
     *
     * The drop part moves the clock through whatever is left of the set's planned drop time
     * (`dropLeftMs`) and is pushed for the rest, WHATEVER THE PRESSURE READS: the net never
     * counts a drop frame, so a drop can only ever be part of the set's schedule, never time
     * under pressure. The hold part moves only on a fresh reading at or above `lineKpa` - the
     * net's own test for a frame.
     */
    public static Step tick(long stepMs, long dropMs, boolean reading, double kpa,
                            double lineKpa, long dropLeftMs) {
        if (stepMs <= 0) return new Step(0L, 0L);
        long drop = Math.max(0L, Math.min(stepMs, dropMs));
        long hold = stepMs - drop;
        long through = Math.min(drop, Math.max(0L, dropLeftMs));
        boolean at = reading && kpa >= lineKpa;
        return new Step((drop - through) + (at ? 0L : hold), through);
    }
    /* ================================ D2 - A SET'S TIME LIMIT ============================
     *
     * THE FINAL SAFETY REVIEW'S BLOCKING FINDING. Timed at pressure, a set had no limit:
     * "NO CAP, as ruled". Level 1 commands 17 kPa, and the clock moves only while the pump
     * reads at or above the net's line, 16.59 kPa. A pump pulls once per cycle and coasts
     * down 0.033 - 0.1 kPa/s on hardware (PhaseTracker, Tau), so each 125 s cycle spends
     * about 4 - 12 s above the line: 840 s of hold time needs 70 - 210 cycles, 2.4 - 7 hours.
     * A pump that settles below the line never finishes a set. Nothing ended the set; only
     * the two-hour stop vented the cuff.
     *
     * THE OWNER'S RULE. The clock stops lengthening a set once its wall time reaches
     *
     *     cap = max(planned, min(2 x planned, Plan#SET_ADVISORY_SEC))
     *
     * so a set is never cut below its plan, is lengthened to at most double, and never past
     * the twenty minutes the guide gives as the longest a set should go, unless it was
     * planned longer, and then it is not lengthened at all. At the cap the set's deadline
     * simply stops moving and the set ends at it, the way every set ends; the net still
     * counts only what was delivered.
     *
     * WHAT "A SET" IS. The set occurrence the plan expanded - one preset for a fixed set, a
     * ramp's steps, a stitched hold's chunks and repetitions - by RunEdit#sameOccurrence, so a
     * ramp of eight two-minute steps shares one 20-minute limit rather than getting 4:00 per
     * step. "Planned" is the occurrence's steps as each started, plus every +30 s (the user
     * extends the plan, so the limit grows with it). What the clock itself pushed is the
     * "stretch" the limit bounds. A Hold, an inserted rest and a changeover push the deadline
     * too (tickHold), and none of them passes through here: those are pauses the user chose
     * or the plan wrote, not the clock waiting for pressure.
     */

    /** The longest a set planned at `plannedMs` may run in wall time while this clock
     *  lengthens it - the owner's rule, above. */
    public static long capMs(long plannedMs) {
        long p = Math.max(0L, plannedMs);
        long advisory = Plan.SET_ADVISORY_SEC * 1000L;
        return Math.max(p, Math.min(2L * p, advisory));
    }

    /** How much more the clock may lengthen a set planned at `plannedMs` that it has
     *  already lengthened by `stretchedMs`: never below nothing. */
    public static long stretchLeftMs(long plannedMs, long stretchedMs) {
        return Math.max(0L, capMs(plannedMs) - Math.max(0L, plannedMs)
                            - Math.max(0L, stretchedMs));
    }

    /** The planned length of the set occurrence running at `planIdx`: the planned time of its
     *  steps already played, the running step's own planned time, and every later step of
     *  the same occurrence as the plan holds it now (a +30 s on a ramp lands on its final
     *  step's duration, and is read there). */
    public static long setPlannedMs(java.util.List<Model.Preset> plan, int planIdx,
                                    long donePlannedMs, long curPlannedMs) {
        long ms = Math.max(0L, donePlannedMs) + Math.max(0L, curPlannedMs);
        int later = RunEdit.remainingStepsOfSet(plan, planIdx);
        for (int i = 1; i <= later; i++) ms += Math.max(0L, plan.get(planIdx + i).durMs);
        return ms;
    }

    /**
     * THE RUNNING SET'S LIMIT - the run keeps one (SessionActivity#setLimit) and tells it when
     * each preset starts (resetTupWatch), when +30 s is pressed on the running step
     * (extendPreset), and asks it for every push (tickTupTiming).
     *
     * ONLY ENDS THINGS SOONER. It never adds a push, never moves a deadline itself and
     * commands nothing: it can only give the clock less than it asked for. A preset it was
     * not told about is given nothing, so a path that forgot to start it runs that set by
     * the clock rather than without a limit.
     */
    public static final class SetLimit {
        /** A preset of the occurrence being timed, for RunEdit#sameOccurrence; null for
         *  none (no run, a rest). */
        private Model.Preset set;
        private int idx = -1;
        /** The planned time of the occurrence's steps already played. */
        private long donePlanMs;
        /** The running step's planned time: its duration as armed, plus each +30 s. */
        private long curPlanMs;
        /** What the clock has lengthened this set by, and what the limit refused it. */
        private long stretchedMs, cutMs;
        private boolean said;

        /** A run ended or began: no set is being timed. */
        public void forget() {
            set = null; idx = -1;
            donePlanMs = 0L; curPlanMs = 0L; stretchedMs = 0L; cutMs = 0L; said = false;
        }

        /** The preset at `i` has started. The next step of the same occurrence carries the
         *  set's plan and stretch on; the step already running, started again, changes
         *  nothing; anything else starts a set of its own, and a rest ends the one before it. */
        public void start(java.util.List<Model.Preset> plan, int i) {
            if (plan == null || i < 0 || i >= plan.size() || plan.get(i).rest) {
                forget();
                return;
            }
            Model.Preset p = plan.get(i);
            // THE SAME STEP AGAIN keeps everything. By then its duration holds every push the
            // clock made, so taking it as "planned" and starting the stretch from nothing
            // would hand the set a fresh, doubled allowance (SetTimeLimitTest).
            if (set != null && i == idx && RunEdit.sameOccurrence(set, p)) return;
            if (set != null && i > idx && RunEdit.sameOccurrence(set, p)) {
                donePlanMs += curPlanMs;
            } else {
                donePlanMs = 0L; stretchedMs = 0L; cutMs = 0L; said = false;
            }
            set = p; idx = i;
            curPlanMs = Math.max(0L, p.durMs);
        }

        /** A +30 s on the running step: planned time, so the limit grows with it. */
        public void extend(long ms) {
            if (set != null) curPlanMs += Math.max(0L, ms);
        }

        /** Coming steps took sets off the running step: planned time, so the limit shrinks
         *  with it - the clock's stretch allowance is counted from what is now planned.
         *  Never below nothing. */
        public void shorten(long ms) {
            if (set != null) curPlanMs = Math.max(0L, curPlanMs - Math.max(0L, ms));
        }

        /** The running set's planned length; 0 when `i` is not the step it was told of. */
        public long plannedMs(java.util.List<Model.Preset> plan, int i) {
            if (set == null || i != idx || plan == null || i < 0 || i >= plan.size()) return 0L;
            return setPlannedMs(plan, i, donePlanMs, curPlanMs);
        }

        /** How much more the clock may lengthen the set at `i`. */
        public long leftMs(java.util.List<Model.Preset> plan, int i) {
            if (set == null || i != idx) return 0L;
            return stretchLeftMs(plannedMs(plan, i), stretchedMs);
        }

        /** The clock wants to push the set at `i` out by `wantMs`: returns the part the limit
         *  gives, and remembers the rest as refused. */
        public long allow(java.util.List<Model.Preset> plan, int i, long wantMs) {
            long want = Math.max(0L, wantMs);
            long ok = Math.min(want, leftMs(plan, i));
            stretchedMs += ok;
            cutMs += want - ok;
            return ok;
        }

        /** Nothing is left: the set's clock no longer waits for pressure, and its deadline
         *  is where it will end. Also true for a set planned too long to lengthen. */
        public boolean atLimit(java.util.List<Model.Preset> plan, int i) {
            return set != null && i == idx && leftMs(plan, i) <= 0L;
        }

        /** True once, as the LAST step of a set ends at its deadline, when the limit refused
         *  the clock any time in that set: the set did not get its planned time at pressure.
         *  The Advance asks this, so a set ended by Skip or STOP is never said to have hit it. */
        public boolean reachedLimitAtEnd(java.util.List<Model.Preset> plan, int i) {
            if (set == null || i != idx || said || cutMs <= 0L) return false;
            if (RunEdit.remainingStepsOfSet(plan, i) > 0) return false;
            said = true;
            return true;
        }

        public long stretchedMs() { return stretchedMs; }
        public long cutMs() { return cutMs; }
    }
}
