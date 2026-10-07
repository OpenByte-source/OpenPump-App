package org.openpump;

/**
 * The decidable half of the gym-timer run screen's SKIP / EXTEND / EDIT-UPCOMING
 * controls, kept PURE (no `import android`) so test.sh compiles it and SelfTest asserts
 * on it. Every number those three controls hand back to the existing sequencing —
 * which preset plays next, how long the current one has left after an extension, what
 * duration a skipped preset actually delivered, whether a preset may be edited at all,
 * and where uploadBatch() must restart after an edit — is computed here and nowhere
 * else. SessionActivity does the pump talking; it does not do this arithmetic twice.
 *
 * WHY A SEPARATE CLASS. The run screen already learned defect #25's lesson once: two
 * independent derivations of "how far along is this" disagreed, and the fix was to make
 * every element read ONE elapsed value. Skip and extend add a second way for the plan's
 * timeline to move — the app's idea of a preset's duration must keep matching what the
 * preset actually delivered, or refreshRunScreen()'s elapsed-to-index mapping drifts
 * away from the index playPreset() is really on. Putting that arithmetic in one pure
 * place is what makes it assertable at all.
 *
 * THE CLAMP RULE IS uploadBatch()'s, RESTATED. An edited upcoming preset is written to
 * the device by the same uploadBatch() that writes every other preset, so it is clamped
 * by the same two lines: the upper setpoint to the safety ceiling, and the lower to
 * strictly below whatever the upper became. {@link #clampUpper}/{@link #clampLower} are
 * those two lines, extracted so the editor can show the user the value that will
 * actually be sent rather than the one they typed. They must stay identical to
 * uploadBatch()'s — see SelfTest, which pins both against the same ceiling.
 */
public final class RunEdit {
    private RunEdit() { }

    /**
     * THE STRIP CELL'S NAME WITH ITS UNIT (polish RN-4): "Pull to (inHg)", "Drop to (inHg)" -
     * the figure under it is a bare number, so the pressure cells say which unit it is in, in
     * the unit the display is set to. The other cells keep QuickAdjust's own names.
     */
    public static String stripLabel(int field) {
        if (field == QuickAdjust.PULL) return "Pull to (" + Model.Fmt.unitWord() + ")";
        if (field == QuickAdjust.DROP) return "Drop to (" + Model.Fmt.unitWord() + ")";
        return field >= 0 && field < QuickAdjust.LABEL.length ? QuickAdjust.LABEL[field] : "";
    }

    /** A preset, field for field, with its own length - Undo's copy (polish RN-9). */
    public static Model.Preset copyPreset(Model.Preset z, long durMs) {
        Model.Preset p = new Model.Preset();
        writePull(p, z.up);
        p.lo = z.lo; p.uh = z.uh; p.lh = z.lh; p.sp = z.sp;
        p.durMs = durMs;
        p.label = z.label;
        p.cyclePart = z.cyclePart;
        p.builtHoldOnly = z.builtHoldOnly;
        p.loMax = z.loMax;
        p.added = z.added;
        p.stageIdx = z.stageIdx;
        p.setId = z.setId;
        p.ordinal = z.ordinal;
        p.pos = z.pos;
        p.awaitAck = z.awaitAck;
        p.rest = z.rest;
        p.manual = z.manual;
        p.offsetKpa = z.offsetKpa;
        p.src = z.src;
        return p;
    }

    /** How long after a skip its Undo is still taken (polish RN-9): the snack's own hold,
     *  and a little more for a thumb already on its way. */
    public static final long SKIP_UNDO_MS = 7000L;

    /** For this long after the Skip button stops saying "Undo skip" it takes no tap (device
     *  check EMU9 H2): a thumb on its way to Undo must not skip the step the run is now on. */
    public static final long SKIP_LATE_TAP_MS = 1500L;

    /** Is a tap on the Skip button at `now` still a late tap meant for Undo, which went at
     *  `undoGoneAt` (0: never offered)? */
    public static boolean skipLateTapGuarded(long undoGoneAt, long now) {
        return undoGoneAt > 0L && now >= undoGoneAt && now - undoGoneAt < SKIP_LATE_TAP_MS;
    }

    /**
     * WHAT "UNDO" PUTS BACK AFTER SKIP THESE SETS (polish RN-9, the owner's option A).
     *
     * The skip took the steps after the one playing out of the plan and ended the one playing
     * early; the step after them then started. Undo cannot un-start that step, so it puts the
     * sets back IN FRONT OF IT and ends it the way a skip does: what is returned is inserted
     * after the step playing - what was left of the skipped step (its unplayed time), the steps
     * taken out, then the step playing again in full - and the caller then skips the step
     * playing, the ordinary road. Net: the block runs on from where it was cut, and the step
     * that had just begun runs again whole after it. Nothing here touches the pump.
     *
     * @param leftMs  the skipped step's unplayed time; under a second it is not put back.
     * @param taken   the steps the skip took out, in order.
     * @param playing the step playing now (the one after them), copied whole.
     */
    public static java.util.List<Model.Preset> undoSkipSteps(Model.Preset skipped, long leftMs,
            java.util.List<Model.Preset> taken, Model.Preset playing) {
        java.util.List<Model.Preset> out = new java.util.ArrayList<Model.Preset>();
        if (skipped != null && leftMs >= 1000L) out.add(copyPreset(skipped, leftMs));
        if (taken != null) out.addAll(taken);
        if (out.isEmpty()) return out;          // nothing to put back: no step is replayed
        if (playing != null) out.add(copyPreset(playing, Math.max(1000L, playing.durMs)));
        return out;
    }

    /** The adjust sheet's slider names (polish RN-7): the words the strip and the Library
     *  use - never the lowercase "pull / drop / speed" it had of its own. */
    public static final String SHEET_PULL = "Pull to", SHEET_DROP = "Drop to",
        SHEET_HOLD = "Hold", SHEET_DROP_TIME = "Drop time", SHEET_POWER = "Suction power";

    /** One tap of "+30 s". Wall-clock milliseconds, like every other timer in this app. */
    public static final long EXTEND_MS = 30000L;

    /** The shortest duration a skipped preset may be recorded as having delivered.
     *  Model.Set#ladder() already floors every generated preset at 1000 ms, so a plan
     *  never contains anything shorter; a skip must not push one below that floor and
     *  invent a zero-length stage the live-stage maths cannot land on. */
    public static final long MIN_PRESET_MS = 1000L;

    /* ------------------------------------------------------------------ skip */

    /**
     * The index SKIP hands to playPreset(). Deliberately the SAME `idx + 1` the normal
     * Advance uses — skip is not a second sequencing path, it is the existing one fired
     * early — so a skip at the end of a batch still lands on playPreset()'s own
     * `idx >= batchBase + SLOTS` re-upload branch, and a skip past the last preset still
     * lands on endOfPlan(). Neither case is special-cased here, and neither should be.
     */
    public static int nextIndexAfterSkip(int planIdx) {
        return planIdx + 1;
    }

    /**
     * True when there is a preset actually playing that could be skipped. planIdx is -1
     * from startSession() until the first Advance fires (the 600 ms window where the
     * batch is still being written), and the controls must be inert for that stretch:
     * "skip" before anything has started would arm slot 1 with slot 0 never played.
     */
    public static boolean canSkip(boolean running, int planIdx, int planSize) {
        return running && planIdx >= 0 && planIdx < planSize;
    }

    /** Extending has exactly the same precondition as skipping: something must be
     *  playing for "the current preset" to name anything. Kept as its own method so a
     *  later change to one cannot silently move the other. */
    public static boolean canExtend(boolean running, int planIdx, int planSize) {
        return canSkip(running, planIdx, planSize);
    }

    /**
     * What the skipped preset ACTUALLY delivered, in ms — the plan's record of it is
     * rewritten to this so the run's timeline stays true. Derived from the wall clock
     * (`presetFireAt - now` is what is left, so the rest is what ran), never from a
     * count of ticks.
     *
     * Clamped to [MIN_PRESET_MS, originalDurMs]: a skip cannot make a preset longer than
     * it was scheduled for, and a skip in the first second records the floor rather than
     * zero. A late tick (now past presetFireAt) therefore records the full duration,
     * which is the truth — the preset ran its course and the advance simply had not
     * fired yet.
     */
    public static long deliveredMsOnSkip(long originalDurMs, long presetFireAt, long now) {
        long ran = originalDurMs - (presetFireAt - now);
        if (ran < MIN_PRESET_MS) ran = MIN_PRESET_MS;
        if (ran > originalDurMs) ran = originalDurMs;
        return ran;
    }

    /* ---------------------------------------------------------------- extend */

    /**
     * THE EXTEND BUTTON ON A RAMP'S STEP OF MORE THAN ONE CYCLE ADDS ONE WHOLE CYCLE (0.10,
     * owner follow-up): `cycleSec` - the step's hold + drop, as in force - so a step that ended
     * after a drop still ends after one, and the pump never goes from a cut hold straight into
     * the next step's pull. The button says what it adds ({@link #rampExtendLabel}): "+0:42
     * step", never "+30 s" it does not add. A cycle of nothing is taken as 1 s.
     */
    public static long rampExtendMs(int cycleSec) {
        return Math.max(1, cycleSec) * 1000L;
    }

    /** "+0:42 step": what the extend button adds on a ramp's step of more than one cycle. */
    public static String rampExtendLabel(int cycleSec) {
        return "+" + Model.Fmt.t(Math.max(1, cycleSec)) + " step";
    }

    /** Its words for a screen reader: "One more cycle on this step of the ramp, 0:42". */
    public static String rampExtendSaid(int cycleSec) {
        return "One more cycle on this step of the ramp, " + Model.Fmt.t(Math.max(1, cycleSec));
    }

    /* ================= A RAMP STEP KEEPS WHOLE CYCLES AFTER A LIVE HOLD / DROP CHANGE (0.10)
     *
     * Every ramp step is whole cycles of its own hold and drop (Model.Set#rampCycles), and the
     * step's length is the app's clock: the pump repeats hold, drop, hold... until the app
     * STARTs the next step. A live change of the hold or the drop time on a step of several
     * cycles used to keep the step's length ("a ramp keeps its time per step"): with a new
     * cycle the step then ended part-way through a hold, and the pump went from that cut hold
     * straight into the next step's pull with no drop - the very fault the whole-cycle rule
     * closed. So after any live change the step playing ends as one of the pump's cycles ends:
     *   - the cycle under way (carried on, or started again) ends where the pump's own count
     *     says, and each cycle after it takes the new hold + drop;
     *   - the step's end is the cycle end NEAREST the end it had (a tie goes to the fewer
     *     cycles - never more time under pressure than was asked), never before the cycle under
     *     way is over (never fewer than one cycle), and never further out than the room it is
     *     given (the two-hour stop, the step's share of the hour) - fewer cycles then;
     *   - every later step of the ramp whose cycle moved with it ("Rest of this ramp") is put
     *     on the whole cycles of its own new hold + drop nearest its length, at least one,
     *     within its share of the hour and the two-hour stop.
     * The run's remaining time and "run ends in" read the same durations, so they follow. */

    /**
     * How far the step playing's end moves (ms, + later) so it ends as a cycle ends: the cycle
     * under way ends in `firstEndsInMs`, each after it is `cycleMs`, and the step has `leftMs`
     * left as it stands. The cycle end nearest `leftMs` (ties to fewer), never before the
     * cycle under way ends, and - past the one under way - never more than `growMaxMs` later.
     * A step that does not cycle (`cycleMs` 0) keeps its end.
     */
    public static long rampEndMoveMs(long firstEndsInMs, long cycleMs, long leftMs, long growMaxMs) {
        if (cycleMs <= 0L) return 0L;
        long first = Math.max(0L, firstEndsInMs);
        long left = Math.max(0L, leftMs);
        long m = 0L;
        if (left > first) m = (long) Math.ceil((left - first) / (double) cycleMs - 0.5);
        long move = first + m * cycleMs - left;
        while (m > 0 && move > Math.max(0L, growMaxMs)) { m--; move -= cycleMs; }
        return move;
    }

    /** A step's length in whole cycles of `cycleSec` nearest `stepMs` (ties to fewer), at least
     *  one, and no more cycles than fit in `maxMs` (when it is above 0 and one cycle fits). */
    public static long rampStepWholeMs(long stepMs, int cycleSec, long maxMs) {
        long c = Math.max(1, cycleSec) * 1000L;
        long k = Math.max(1L, (long) Math.ceil(Math.max(0L, stepMs) / (double) c - 0.5));
        while (k > 1 && maxMs > 0 && k * c > maxMs) k--;
        return k * c;
    }

    /** The same, never longer than it is unless one cycle makes it so: floored. */
    public static long rampStepWholeFloorMs(long stepMs, int cycleSec) {
        long c = Math.max(1, cycleSec) * 1000L;
        return Math.max(1L, Math.max(0L, stepMs) / c) * c;
    }

    /** The most the step playing (at `idx`) may grow: what the two-hour stop leaves, and what
     *  its ramp's share of the hour (Model.Set#rampStepMaxSec) leaves it; never below 0. */
    public static long rampGrowMaxMs(java.util.List<Model.Preset> plan, int idx) {
        if (plan == null || idx < 0 || idx >= plan.size()) return 0L;
        long room = ComingSteps.CAP_MS - ComingSteps.totalMs(plan);
        long share = Model.Set.rampStepMaxSec(stepOfSet(plan, idx)[1]) * 1000L - plan.get(idx).durMs;
        return Math.max(0L, Math.min(room, share));
    }

    /**
     * THE LATER STEPS OF THE RAMP PLAYING (idx+1 .. idx+left) PUT ON WHOLE CYCLES of their own
     * hold + drop, as they stand - after "Rest of this ramp" moved their figures, a reshape or a
     * recount. A step that is exactly one cycle keeps its length (its own rule,
     * QuickAdjust#oneCycle). Each goes to the whole cycles nearest its length, at least one,
     * within its share of the hour; when that would take the plan past the two-hour stop
     * (`roomMs` is what is left of it), each is floored instead. Durations only - the app's
     * clock. Returns how much longer the plan became (ms, − shorter).
     */
    public static long wholeCyclesLater(java.util.List<Model.Preset> plan, int idx, int left,
                                        long roomMs) {
        if (plan == null || idx < 0 || left <= 0) return 0L;
        long most = Model.Set.rampStepMaxSec(stepOfSet(plan, idx)[1]) * 1000L;
        int last = Math.min(plan.size() - 1, idx + left);
        long[] to = new long[last + 1];
        long grow = 0L;
        for (int k = idx + 1; k <= last; k++) {
            Model.Preset p = plan.get(k);
            to[k] = QuickAdjust.oneCycle(p.uh, p.lh, p.durMs) ? p.durMs
                  : rampStepWholeMs(p.durMs, p.uh + p.lh, most);
            grow += to[k] - p.durMs;
        }
        if (grow > Math.max(0L, roomMs)) {
            grow = 0L;
            for (int k = idx + 1; k <= last; k++) {
                Model.Preset p = plan.get(k);
                if (!QuickAdjust.oneCycle(p.uh, p.lh, p.durMs))
                    to[k] = rampStepWholeFloorMs(p.durMs, p.uh + p.lh);
                grow += to[k] - p.durMs;
            }
        }
        for (int k = idx + 1; k <= last; k++) plan.get(k).durMs = Math.max(1000L, to[k]);
        return grow;
    }

    /** What an acknowledged hold / drop-time change on a ramp's step of several cycles says
     *  about the step's time, or null when it did not move a whole second. */
    public static String rampEndSaid(long moveMs) {
        long s = (Math.abs(moveMs) + 500L) / 1000L;
        if (s <= 0) return null;
        return "This step now ends " + Model.Fmt.t(s) + (moveMs > 0 ? " later" : " sooner")
            + " — whole cycles of the new hold and drop, ending on a drop.";
    }

    /** The current preset's new wall-clock end after one "+30 s". */
    public static long extendedFireAt(long presetFireAt, long extendMs) {
        return presetFireAt + extendMs;
    }

    /** The new duration to record against the extended preset, so presetDurMs and the
     *  plan keep agreeing with what the pump is really being asked to do. */
    public static long extendedDurMs(long durMs, long extendMs) {
        return durMs + extendMs;
    }

    /**
     * The delay pendingAdvance must be re-posted with after an extension: time from now
     * to the NEW fire time. Floored at MIN_REPOST_MS rather than at zero — a zero-delay
     * post from a tap that landed a millisecond before the old deadline would advance
     * under the user's finger, and the existing reconnect resume floors its own re-post
     * at 200 ms for the same reason.
     */
    public static long repostDelayAfterExtend(long presetFireAt, long now, long extendMs) {
        long d = extendedFireAt(presetFireAt, extendMs) - now;
        return d < MIN_REPOST_MS ? MIN_REPOST_MS : d;
    }

    /** The floor tickRun()'s reconnect resume already uses for the same re-post. */
    public static final long MIN_REPOST_MS = 200L;

    /* -------------------------------------------------------- edit upcoming */

    /**
     * MAY THIS PRESET BE EDITED? The user's explicit decision, encoded once: only a
     * preset that has NOT STARTED. `idx > planIdx` is the whole rule and it is exactly
     * the rule the brief states — the currently-pressurized preset (idx == planIdx) is
     * never edited live, and a preset already played (idx < planIdx) is history, not a
     * plan. planIdx == -1 (nothing started yet) therefore makes every preset editable,
     * which is correct: nothing is under command from the plan at that moment.
     *
     * Note what is NOT a term here: whether the preset is already uploaded to the
     * device's 9-slot table. That question does not decide whether the edit is ALLOWED,
     * it decides whether the table must be rewritten afterwards — see
     * {@link #reuploadFromAfterEdit}.
     */
    public static boolean isEditable(int idx, int planIdx, int planSize) {
        return idx > planIdx && idx >= 0 && idx < planSize;
    }

    /**
     * Where uploadBatch() must restart so an edited upcoming preset actually reaches the
     * pump — or -1 when the edit needs no upload at all.
     *
     * THE 9-SLOT TABLE. uploadBatch(from) clears the table and writes presets
     * [from, from+SLOTS) into slots [0, SLOTS). An edit to a preset inside that window
     * changed a value the device is ALREADY HOLDING, so the table is now stale and must
     * be rewritten; an edit beyond the window touches a preset the device has not been
     * told about yet, and the batch that eventually carries it will pick the new value
     * up on its own.
     *
     * WHY IT RESTARTS AT THE NEXT PRESET, NOT AT THE EDITED ONE. Rewriting the table
     * renumbers the slots — the preset at `from` becomes slot 0 — so batchBase must move
     * with it, and the app must keep playing from a slot that still means what it meant a
     * moment ago. The CURRENT preset is playing out of a slot in the table being cleared.
     * So the re-upload starts at planIdx + 1: the first preset that has not started. The
     * device keeps executing the preset it was started on (a Delete removes a table
     * entry, not the cycle already running), the countdown for it is untouched, and when
     * pendingAdvance fires, playPreset(planIdx + 1) finds the rewritten table with
     * batchBase == planIdx + 1 and starts slot 0 of it. That is the same arithmetic
     * playPreset() already does at a batch boundary; nothing new is invented for it.
     *
     * Returns -1 when the edited preset lies beyond the uploaded window (nothing to
     * redo), or when there is nothing upcoming left to upload.
     */
    public static int reuploadFromAfterEdit(int editedIdx, int planIdx, int batchBase,
                                            int slots, int planSize) {
        int firstUpcoming = planIdx + 1;
        if (firstUpcoming < 0) firstUpcoming = 0;
        if (firstUpcoming >= planSize) return -1;          // nothing left to play
        if (editedIdx < firstUpcoming) return -1;          // not an upcoming preset at all
        if (editedIdx >= batchBase + slots) return -1;     // beyond the uploaded window
        return firstUpcoming;
    }

    /* ----------------------------------------------------------- the clamps */

    /** uploadBatch()'s first clamp: no setpoint above the safety ceiling, ever. */
    public static int clampUpper(int up, int ceilKpa) {
        return Math.min(up, ceilKpa);
    }

    /** uploadBatch()'s second clamp: the lower setpoint is strictly below the upper as
     *  the upper ACTUALLY ENDED UP — clamped against the already-clamped upper, not
     *  against the value the user typed. A zero upper leaves the lower at zero, which is
     *  the one case where they are equal and the only one uploadBatch() permits. */
    public static int clampLower(int lo, int clampedUpper) {
        return Math.min(lo, clampedUpper > 0 ? clampedUpper - 1 : 0);
    }

    /* ================================================================= OVERRIDE
     *
     * THE LIVE OVERRIDE — editing the preset that is RUNNING.
     *
     * THE HARDWARE FACT THIS IS BUILT ON. The ZD21 (Epic Hydro PE Pump) commands this app
     * knows (docs/protocols/zd21.md: Add, Delete, Start, Stop, List, Set time) include NO
     * "modify the running preset" command and no pause. So a live edit cannot be an edit at
     * all — it has to be a SUPERSEDE: write the adjusted preset into a free slot and start
     * that slot, and the pump jumps from the preset it was running to the new one. This app
     * does that through the same two chokepoints its routine already uses — uploadBatch()'s
     * clamps and sendStartSlot()'s cancelVentWatch() — and invents no new send path.
     *
     * THE SLOT ARITHMETIC, WHICH IS THE AWKWARD PART. The device table holds SLOTS (9)
     * entries. Add APPENDS to the end; Delete COMPACTS everything above the deleted index
     * down by one. uploadBatch() previously filled all nine, leaving nowhere for an
     * override to land. So the routine batch is now SLOTS - 1 (see {@link #routineBatchSize}),
     * and the override is whatever Add appends after the routine entries — index
     * {@link #overrideSlotIndex}, which is always the LAST occupied entry.
     *
     * WHY THE LAST ENTRY IS THE ONLY SAFE CHOICE. Delete compacts, so deleting any entry
     * BELOW the routine presets would renumber every routine slot and silently repoint
     * batchBase arithmetic at the wrong preset. Deleting the LAST entry shifts nothing at
     * all ({@link #deleteShiftsNothing}), so the previous override can be cleared between
     * adjustments without the routine table moving underneath the run. That is the whole
     * reason the override lives at the top and not at slot 0.
     *
     * ORDER OF OPERATIONS. Delete-the-old FIRST, then Add, then Start. Deleting the old
     * override last, once the new START is acknowledged, would leave it in the table while
     * the new one is added: with a batch of SLOTS-1 routine presets that stale override
     * would push the new Add to index SLOTS — off the end. The
     * pump keeps executing the cycle it was started on when its table entry is deleted (the
     * same fact uploadBatch() has always relied on), so nothing is released in the gap.
     * PumpLink.tx is a serialised, acked write queue, so the three frames land in order
     * without this code re-implementing an ack chain.
     */

    /** The number of ROUTINE presets one batch may carry: one fewer than the device's
     *  table, so the top entry is always free for an override to be appended into. */
    public static int routineBatchSize(int slots) {
        return slots - 1;
    }

    /**
     * WHERE A BATCH STARTING AT `from` ENDS (exclusive): at the plan's end, at the eight entries
     * the table gives the routine, or AT THE FIRST REST, whichever comes first. A rest is never
     * written.
     *
     * The run used to write a rest into its batch as an all-zero preset, so that "the slot a
     * preset plays out of is idx - batchBase" stayed trivial. The real pump does not store an
     * all-zero preset. The owner's journal of 22 Sep is explained entry for entry only by a
     * table that never held one (PumpRefusalTest). So every entry after the rest sat one index
     * lower than the app believed. The live override was Added at index 7, and its START 8 was
     * refused, `2C FD`: a raise the screen had taken, on a pump that never moved.
     *
     * Ending the batch at the rest costs nothing that was ever used. Nothing after a rest was
     * started from its batch, because playPreset rewrites the table on the way out of every
     * rest. The app's count of what it wrote (the caller's batchCount) is now the pump's own:
     * the override slot is that count (overrideSlotAt), and a routine slot outside it is not
     * in the table (routineSlotOrReupload). RestBatchTest replays the owner's routine through
     * the simulated pump. WiringCheck invariant 141 pins that uploadBatch writes exactly
     * this range.
     */
    public static int batchEnd(java.util.List<Model.Preset> plan, int from, int slots) {
        int n = plan == null ? 0 : plan.size();
        if (from < 0) from = 0;
        int end = Math.min(n, from + routineBatchSize(slots));
        for (int i = from; i < end; i++)
            if (plan.get(i) != null && plan.get(i).rest) return i;
        return end;
    }

    /** The table index an override Add lands in: the number of routine entries the last batch
     *  actually wrote, because Add appends. See batchEnd for why that count, and not a
     *  figure worked out from the plan's length, is the one the pump agrees with. */
    public static int overrideSlotAt(int batchCount) {
        return Math.max(0, batchCount);
    }

    /** Is there a free entry for an override after the `batchCount` routine entries written
     *  (and the previous override, while it is still there)? */
    public static boolean overrideSlotFree(int batchCount, int slots, boolean overrideStillInTable) {
        return Math.max(0, batchCount) + (overrideStillInTable ? 1 : 0) < slots;
    }

    /** {@link #routineSlotOrReupload(int, int)}, bounded by what the batch actually wrote: a
     *  preset at or past batchBase + batchCount is not in the table (it was past a rest, or
     *  past the batch), so -1, and the caller rewrites the table from it before starting. */
    public static int routineSlotOrReupload(int planIdx, int batchBase, int batchCount) {
        int s = routineSlotOrReupload(planIdx, batchBase);
        return s >= 0 && s < batchCount ? s : -1;
    }

    /** How many routine presets a batch starting at `from` actually writes. */
    public static int routineEntriesInBatch(int from, int planSize, int slots) {
        int left = planSize - from;
        if (left < 0) left = 0;
        int cap = routineBatchSize(slots);
        return left < cap ? left : cap;
    }

    /**
     * The table index an override Add lands in — which is simply the number of routine
     * entries in the table, because Add appends. Never SLOTS-1 by assumption: a short
     * final batch of three presets puts the override at index 3, not at 8, and starting
     * slot 8 there would start nothing.
     *
     * Precondition: no PREVIOUS override is still in the table. That is why every
     * adjustment deletes the old one before adding the new — see the class note.
     */
    public static int overrideSlotIndex(int from, int planSize, int slots) {
        return routineEntriesInBatch(from, planSize, slots);
    }

    /**
     * IS A FREE OVERRIDE SLOT AVAILABLE? With routineBatchSize == slots-1 this is true by
     * construction, and it is written out as a real question anyway so that the run screen's
     * refusal (applyOverride: nothing is sent, and the person is told why) has something to
     * ask, and so that a future change to the batch size fails here rather than by writing
     * off the end of the table.
     *
     * `overrideStillInTable` is the previous override that has NOT yet been deleted; while
     * one is present it occupies the slot the next Add would want.
     */
    public static boolean overrideSlotAvailable(int from, int planSize, int slots,
                                                boolean overrideStillInTable) {
        int used = routineEntriesInBatch(from, planSize, slots)
                 + (overrideStillInTable ? 1 : 0);
        return used < slots;
    }

    /**
     * Does deleting table entry `index` from a table of `count` entries shift any other
     * entry? Delete COMPACTS, so only the LAST entry can be removed without renumbering
     * the ones below it. The override lives there for exactly this reason, and this is the
     * assertion that pins it.
     */
    public static boolean deleteShiftsNothing(int index, int count) {
        return index == count - 1 && index >= 0;
    }

    /** May the running preset be overridden at all? The mirror of {@link #isEditable}:
     *  that one is "has NOT started", this one is "IS running right now". */
    public static boolean canOverride(boolean running, int planIdx, int planSize) {
        return running && planIdx >= 0 && planIdx < planSize;
    }

    /**
     * MAY THE RUN PUT ITS OWN PRESET BACK ON THE PUMP? Asked by every path that re-arms the
     * preset playing now: a hold or an inserted rest ending (resumeRunningPreset), Revert,
     * and the two arms posted behind a table rewrite's settle (RestArm, RevertArm).
     *
     * {@link #canOverride} alone is not enough, because the AFTER-ASSESSMENT RUNS INSIDE THE
     * RUN: `running` stays true until the session is filed, and planIdx still names the last
     * set, since playPreset returns into endOfPlan() before it assigns planIdx. So after the
     * plan had ended canOverride still said a preset was playing, and a rest's end posted
     * before the Skip that ended the plan rewrote the table and started the last set over
     * the assessment's vent or pull. While an assessment owns the pump nothing of the run's
     * is armed; `running` false (the run is over) refuses as it always did.
     */
    public static boolean mayRearmRunningPreset(boolean running, boolean assessOwnsPump,
                                                int planIdx, int planSize) {
        return !assessOwnsPump && canOverride(running, planIdx, planSize);
    }

    /**
     * D2 - HAS THE APP TOLD THE PUMP TO HOLD PRESSURE RIGHT NOW? A run is live and a step that
     * commands pressure is in force: a preset, or a Hold on one.
     *
     * Asked for the two-hour stop (Plan#GROSS_CAP_SEC), which counts a frame reading 0.0 -
     * "no measurement" - or no frame at all exactly as a real reading while this is true.
     * The app cannot tell a dead sensor from a vented cuff, so while it has commanded
     * pressure it must assume the pressure is there. It is false where the cuff has been
     * vented on purpose - a rest step (`presetIsRest`, `resting`), an inserted rest
     * (`restingNow`), a changeover waiting for its acknowledgement (`awaitingAck`) - and
     * while an assessment owns the pump (`assessOwnsPump`): its pull and its vent wait end
     * on their own timers, and the run's frame recorder does not record them. After STOP,
     * a confirmed link-loss vent or the plan's end, `running` is false.
     */
    public static boolean commandingPressure(boolean running, boolean assessOwnsPump,
                                             boolean resting, boolean restingNow,
                                             boolean awaitingAck, boolean presetIsRest) {
        return running && !assessOwnsPump && !resting && !restingNow && !awaitingAck
            && !presetIsRest;
    }

    /* ------------------------------------------------- the countdown is preserved */

    /**
     * THE OVERRIDE INHERITS THE RUNNING PRESET'S REMAINING TIME, because an adjustment is a
     * change of PRESSURE, not a restart of the step. presetFireAt is not touched by an
     * override, so this is just "what was already left", stated as a function so the
     * assertion that the countdown does not reset has something to bind to.
     *
     * Floored at zero — the last few hundred ms before the advance lands read 0, never a
     * negative time — and rounded UP to whole seconds for the wire, because a hold of 0 s
     * on the device means "no hold", and an override applied with 400 ms left must still
     * command a hold rather than a no-op.
     */
    public static long overrideRemainingMs(long presetFireAt, long now) {
        long left = presetFireAt - now;
        return left < 0 ? 0 : left;
    }

    /** That remaining time as the whole seconds the ADD frame carries (0..255, the wire's
     *  range). Rounded up, and floored at 1 for any non-zero remainder. */
    public static int overrideHoldSeconds(long presetFireAt, long now) {
        long ms = overrideRemainingMs(presetFireAt, now);
        long s = (ms + 999) / 1000;
        if (s < 1) s = 1;
        if (s > 255) s = 255;
        return (int) s;
    }

    /** True when an override left presetFireAt exactly where it was — the property the
     *  whole "the countdown does not reset" rule reduces to. Kept as a named predicate so
     *  SelfTest asserts the RULE rather than an incidental equality. */
    public static boolean countdownPreserved(long fireAtBefore, long fireAtAfter) {
        return fireAtBefore == fireAtAfter;
    }

    /* ------------------------------------------------------------- the debounce */

    /**
     * How long after the last slider movement the settled value is sent. Without a debounce
     * a slider dragged across its range would issue a delete/add/start triplet per pixel
     * into a serialised write queue, and the pump would chase values the user never meant
     * to command.
     */
    public static final long OVERRIDE_DEBOUNCE_MS = 400L;

    /** Has the slider settled? True once `debounceMs` has passed with no further change. */
    public static boolean debounceSettled(long lastChangeAt, long now, long debounceMs) {
        return lastChangeAt > 0 && (now - lastChangeAt) >= debounceMs;
    }

    /**
     * May a SETTLED slider value be applied? Not while a hold is up.
     *
     * A hold is an override (the held pressure, and 1 kPa below it) occupying the same
     * table entry, so a
     * debounced adjustment landing after the hold began replaced the hold on the pump while
     * `holding` stayed true: the screen said HOLDING, the countdown stayed frozen, and the
     * cuff was cycling the adjustment. enterHold cancels the pending job, and this refuses
     * one that was already in flight. The user's own model is served by the simple rule —
     * they can adjust again once they resume.
     */
    public static boolean debouncedApplyAllowed(boolean holding) {
        return !holding;
    }

    /**
     * ...AND IS IT STILL THE SAME STEP? The other thing that can change under a settle.
     *
     * `dialledForIdx` is the plan index that was playing when the slider was last moved;
     * `playingIdx` is the one playing now. A settle is only ever applied to the step it was
     * dialled for.
     *
     * The debounce is 400 ms of deliberate delay between a finger and the wire, and nothing
     * connected the two ends of it. Nudge the pull up in the last moments of a set and the
     * set ends first: applyOverride then wrote those figures onto the NEXT step and recorded
     * them as the adjustment carrying for the rest of ITS stage — so a pressure chosen for
     * a 20-second girth pulse could be applied to, and carried across, a length hold. The
     * user's evidence for what they had asked for was a sheet showing the figure, and the
     * pump doing something else with it one step later.
     *
     * -1 for `dialledForIdx` means no settle is pending and nothing may be applied; the two
     * indices being equal is the only case that may.
     */
    public static boolean debouncedStillSameStep(int dialledForIdx, int playingIdx) {
        return dialledForIdx >= 0 && dialledForIdx == playingIdx;
    }

    /** The delay a debounce job is (re-)posted with after a change at `lastChangeAt`,
     *  given it is being posted at `now`. Never negative. */
    public static long debounceDelay(long lastChangeAt, long now, long debounceMs) {
        long d = lastChangeAt + debounceMs - now;
        return d < 0 ? 0 : d;
    }

    /**
     * NO LONGER ASKED BY THE RUN SCREEN (bug B1) — LiveEdit#needsSeed replaced it. A clock
     * cannot say whether the pending target has been handed over: the settle job is posted
     * for exactly this window and runs late whenever the main thread is busy, so a tap that
     * landed after the window but before the job reseeded from the pre-tap figure and lost
     * the first tap. The rule is now "reseed only when nothing is pending". This function
     * and its SelfTest pins are left as they were; removing them is a separate decision.
     *
     * S15 — SHOULD A ±1 kPa NUDGE CHIP RESEED ITS WORKING COPY from what is actually IN
     * FORCE (loadOverrideFromRunning() — the carried tuple, or the running preset's own),
     * or keep building on the value already sitting there from a still-in-flight previous
     * tap?
     *
     * The override SHEET never has to ask this: it seeds ONCE, when it opens, and every
     * slider move after that mutates the already-seeded copy in place — there is no
     * second entry point competing to reseed it mid-drag. A chip has no such "session":
     * it is tapped directly on the NOW tile, with nothing open to mark when editing
     * started. So it needs an explicit rule for exactly the case the sheet never meets —
     * a SECOND tap landing before the FIRST one has reached the pump.
     *
     * Reseeding UNCONDITIONALLY on every tap is the wrong rule: loadOverrideFromRunning()
     * reads carryUp/carryLo (what the pump was last GIVEN) or the plan, never the pending
     * working copy, so a reseed mid-debounce would silently overwrite the first tap's own
     * +1/-1 with the STALE pre-tap figure the wire has not been told to leave yet — two
     * quick taps of "+1" would net +1, not +2, with nothing on screen ever explaining why.
     *
     * True (reseed) whenever nothing has changed yet ({@code lastChangeAt == 0}, no chip
     * or slider has touched the working copy at all this run) or the previous change has
     * already SETTLED ({@link #debounceSettled}) — i.e. it has already reached the pump
     * (or, for a change abandoned by a revert/hold, been superseded by that path's own
     * reseed — see RunEdit's callers), so "what is in force" now already reflects it.
     * False only inside the live debounce window of an unsettled prior tap.
     */
    public static boolean nudgeShouldReseed(long lastChangeAt, long now, long debounceMs) {
        return lastChangeAt == 0 || debounceSettled(lastChangeAt, now, debounceMs);
    }

    /* ------------------------------------------------------------------- HOLD */

    /**
     * THE HOLD SETPOINT — the user's "pause", which the hardware cannot give them.
     *
     * StopWork VENTS at 4.68 kPa/s; there is no pause command. So a hold is an override at
     * the pressure the cuff is reading right now: this is its UPPER setpoint. It is asked
     * for with the lower equal, but the override writer keeps every lower strictly below its
     * upper ({@link #clampLower}), so on the wire it is this pressure for 255 s, then 1 kPa
     * below it for 1 s - not the equal-setpoint preset sendHoldPreset() sends for the
     * standardisation hold. Rounded to whole kPa because the wire's
     * setpoints are whole kPa, clamped to the safety ceiling like every other setpoint, and
     * floored at 1: a setpoint of 0 is "no pressure", which would be a vent wearing the
     * word "hold".
     *
     * `observedKpa` must be a REAL reading. A no-reading sample is 0.0 and means NO
     * MEASUREMENT — {@link #canHoldAt} is the question the caller asks first.
     */
    public static int holdSetpointKpa(double observedKpa, int ceilKpa) {
        int k = (int) Math.round(observedKpa);
        if (k < 1) k = 1;
        return clampUpper(k, ceilKpa);
    }

    /** May a hold be commanded from this reading? Only from a real one: 0.0 is NO
     *  MEASUREMENT, and holding "at nothing" would command a value the app invented. */
    public static boolean canHoldAt(double observedKpa, boolean noReading) {
        return !noReading && observedKpa > 0.0;
    }

    /**
     * HOW THE FROZEN COUNTDOWN STAYS HONEST. A hold really does add time to the step, so
     * rather than stopping the clock (which would put `elapsed` and the plan's durations
     * into the disagreement defect #25 was), every tick of a hold pushes BOTH presetFireAt
     * and the preset's recorded duration forward by the time that tick covered. The
     * countdown then reads the same number all the way through the hold — frozen, as the
     * user sees it — while elapsed, presetDurMs and stageDurMs all keep describing a run
     * that genuinely is that much longer. The dose counts it, which is right: a hold is
     * pressure delivered.
     *
     * Clamped to [0, MAX_HELD_STEP_MS] so a tick delayed by a sleeping process cannot add
     * an arbitrary chunk to the plan in one go.
     */
    public static final long MAX_HELD_STEP_MS = 5000L;

    public static long heldAdvanceMs(long lastTickAt, long now) {
        long d = now - lastTickAt;
        if (d < 0) d = 0;
        if (d > MAX_HELD_STEP_MS) d = MAX_HELD_STEP_MS;
        return d;
    }

    /* --------------------------------------- THE OVERRIDE CARRIES WITHIN THE STAGE */

    /**
     * DOES A LIVE ADJUSTMENT CARRY INTO THIS PRESET? An adjustment applied from the run
     * screen is in force for the rest of the STAGE it was applied in — across that stage's
     * remaining presets and every step of a ramp inside it — until the user reverts or the
     * stage ends. It used to die at the next preset boundary, which made a ramp un-adjustable
     * in practice: the user's change was silently undone a few seconds later by the next
     * step starting on the routine's own slot.
     *
     * `ovStage` is the stage index the adjustment was applied in, or -1 when none is in
     * force. `presetStage` is the stage index of the preset about to play (or playing). The
     * adjustment carries only when both name the SAME stage — and -1 never carries, so a
     * cleared adjustment cannot match a preset that happens to carry no stage either
     * (Model.Preset#stageIdx defaults to 0 on a freshly built preset, but the guard is on
     * the override side: "none" must never equal "something").
     */
    public static boolean overrideCarries(int ovStage, int presetStage) {
        return ovStage >= 0 && ovStage == presetStage;
    }

    /**
     * DOES IT CARRY INTO THE PRESET AT THIS INDEX — the stage rule, BOUNDED.
     *
     * A reshape of the running ramp replaces the remaining steps with the user's new tail,
     * so the carry must not flatten them. It used to be dropped outright (ovStage = -1),
     * and that made the app lie about the step still playing: the pump keeps cycling the
     * CARRIED preset out of its table entry (a reshape rewrites from planIdx + 1 and never
     * re-arms the running step), while the kicker dropped "· adjusted", a +30 s stopped
     * refreshing the truncated wire hold, and the sheet re-seeded from the plan's speed.
     *
     * So the carry is BOUNDED instead of dropped: `boundIdx` is the last plan index it may
     * still reach — the running index at the moment of the reshape — and -1 means unbounded
     * (an ordinary adjustment, which carries for the whole stage). The running step keeps
     * telling the truth; planIdx + 1 onwards, the reshaped steps, play as plan.
     *
     * `presetStage` still has to match: the bound narrows the stage rule, it never widens
     * it, so no ovStage of -1 and no other stage can carry because of a bound.
     */
    public static boolean carryActiveAt(int ovStage, int presetStage, int planIdx, int boundIdx) {
        if (!overrideCarries(ovStage, presetStage)) return false;
        return boundIdx < 0 || planIdx <= boundIdx;
    }

    /**
     * WHICH SLOT A HOLD RELEASES INTO. A hold is itself an override — it sits in the
     * table's override entry — so releasing it must put the pump back on whatever was in
     * force BEFORE the hold: the carried adjustment when one carries into this preset
     * (`overrideSlot`, which the caller has to re-WRITE before starting, because the hold
     * preset is what currently occupies it), else the routine's own slot for the preset
     * (`routineSlot`, a plain start). Stated as a function so the decision is asserted
     * rather than re-derived at the call site.
     */
    public static int resumeSlotAfterHold(boolean carries, int routineSlot, int overrideSlot) {
        return carries ? overrideSlot : routineSlot;
    }

    /**
     * THE ROUTINE SLOT OF THE PRESET PLAYING NOW — or -1 when it is no longer in the table.
     *
     * The table holds presets [batchBase, batchBase + batch), so the running preset's slot
     * is `planIdx - batchBase`. That arithmetic has one hole: an edit to an upcoming preset
     * rewrites the table FROM planIdx + 1 ({@link #reuploadFromAfterEdit}), which moves
     * batchBase past the preset that is still playing. The pump keeps cycling it (a Delete
     * removes a table entry, not the cycle already running), so the run is unaffected —
     * until something wants to START that preset's routine slot again: a revert, a hold's
     * release, a reconnect's re-assert. `planIdx - batchBase` is then -1, and
     * Proto.startSlot clamps -1 to slot 0, which after the rewrite is the NEXT preset —
     * started early, silently, with the countdown still describing the current one.
     *
     * So the answer is explicit: the slot when the preset is in the table, -1 when it is
     * not, and the caller's safe move on -1 is to put the table back (re-upload from
     * planIdx, so the running preset is slot 0 again) before starting anything. The caller
     * must never hand -1 to sendStartSlot.
     */
    public static int routineSlotOrReupload(int planIdx, int batchBase) {
        if (planIdx < 0 || batchBase < 0) return -1;
        return planIdx >= batchBase ? planIdx - batchBase : -1;
    }

    /* ============================================ RESHAPING A RUNNING RAMP =========
     *
     * THE PROBLEM THE CARRY DOES NOT SOLVE. A live adjustment (see overrideCarries) is one
     * preset held in force for the rest of the stage, so adjusting mid-RAMP FLATTENS the
     * rest of it: every remaining step plays the single carried preset and the climb the
     * user chose the set for stops climbing. That is right for a fixed set — "make it
     * gentler for the rest of this stage" — and wrong for a ramp, where the thing the user
     * wants to move is usually the END, not the whole tail.
     *
     * WHAT A RESHAPE IS INSTEAD. An edit to the PLAN, of exactly the kind
     * {@link #isEditable} already permits: the remaining steps of the running set are
     * re-interpolated FROM WHERE THE RAMP ACTUALLY IS RIGHT NOW to the new end, and the
     * table is rewritten from planIdx + 1 by the same rewriteTableKeepingCountdown() an
     * upcoming-preset edit uses. The RUNNING step is never touched — isEditable's rule,
     * restated in the loop bound below — so nothing jumps under the user, and the reshaped
     * steps play as ordinary PLAN rows because that is what they now are.
     *
     * WHY IT IS RE-ANCHORED. Interpolating from the SET'S original start would make every
     * remaining step jump back down toward a beginning the run has already left; the ramp
     * would visibly go backwards to honour a change to its end. Anchoring at the values in
     * force NOW means the tail is monotone toward the new end from where the user is
     * standing: raising the end climbs, lowering the end descends, and neither ever crosses
     * the current values on the way.
     *
     * THE ANCHOR IS PASSED IN, not read from the plan, because the values in force may not
     * be the plan's: while an adjustment carries into the running step, what the pump was
     * given is the carry* snapshot, and reshaping away from the plan's untouched numbers
     * would put a step of the tail below where the cuff actually is.
     */

    /**
     * HOW MANY STEPS OF THE RUNNING SET OCCURRENCE ARE STILL TO COME. Counts forward from
     * planIdx while the plan stays inside the same (stageIdx, pos, setId) occurrence —
     * `pos` included because a stage may hold the SAME set id twice in a row and a reshape
     * must not bleed out of the occurrence being run (Model.Preset#pos exists for exactly
     * this distinction). 0 for a fixed set, whose ladder is one preset, and 0 on the last
     * step of a ramp — so "is this a ramp with something left to reshape?" is simply
     * `remainingStepsOfSet(...) > 0` and needs no second look at Set#ramp.
     */
    public static int remainingStepsOfSet(java.util.List<Model.Preset> plan, int planIdx) {
        if (plan == null || planIdx < 0 || planIdx >= plan.size()) return 0;
        Model.Preset cur = plan.get(planIdx);
        int n = 0;
        for (int i = planIdx + 1; i < plan.size(); i++) {
            if (!sameOccurrence(plan.get(i), cur)) break;
            n++;
        }
        return n;
    }

    /**
     * WHICH STEP OF ITS SET OCCURRENCE the preset at `planIdx` is, and of how many - the run
     * screen's "RAMP · STEP 3 OF 5" (0.10). Counted over the same occurrence
     * {@link #remainingStepsOfSet} counts forward in, so the two cannot disagree. {0, 0} when
     * there is no such preset.
     */
    public static int[] stepOfSet(java.util.List<Model.Preset> plan, int planIdx) {
        if (plan == null || planIdx < 0 || planIdx >= plan.size()) return new int[] { 0, 0 };
        Model.Preset cur = plan.get(planIdx);
        int before = 0;
        for (int i = planIdx - 1; i >= 0; i--) {
            if (!sameOccurrence(plan.get(i), cur)) break;
            before++;
        }
        return new int[] { before + 1, before + 1 + remainingStepsOfSet(plan, planIdx) };
    }

    /**
     * THE END THE RUNNING RAMP IS HEADING FOR NOW, as a LiveEdit tuple: its last remaining
     * step, clamped the way it is sent (the pull under the ceiling, the drop under the pull,
     * the seconds and the speed in range). Null when no step of the set remains. The adjust
     * sheet's END controls are a copy of this, read when the sheet is drawn; they act only
     * while it is still this (safety review of D1, finding 2 - a sheet left open into
     * another ramp, or across a "This set" shift, reshaped toward an end the ramp no longer
     * had).
     */
    public static int[] endTuple(java.util.List<Model.Preset> plan, int planIdx, int ceilKpa) {
        int steps = remainingStepsOfSet(plan, planIdx);
        if (steps <= 0) return null;
        Model.Preset last = plan.get(planIdx + steps);
        int[] t = new int[LiveEdit.FIELDS];
        t[LiveEdit.UP] = clampUpper(last.up, ceilKpa);
        t[LiveEdit.LO] = dropKept(last, last.lo, t[LiveEdit.UP]);   // as it is sent
        t[LiveEdit.UH] = clampSeconds(last.uh);
        t[LiveEdit.LH] = clampSeconds(last.lh);
        t[LiveEdit.SP] = clampSpeed(last.sp);
        return t;
    }

    /** Wave 2 §4: the scope switch resets at every set-occurrence boundary, and the
     *  ramp shift snapshots per occurrence — both need this test, so it is public. */
    public static boolean sameOccurrence(Model.Preset a, Model.Preset b) {
        if (a.stageIdx != b.stageIdx || a.pos != b.pos) return false;
        return a.setId == null ? b.setId == null : a.setId.equals(b.setId);
    }

    /**
     * RESHAPE THE REMAINING STEPS. Mutates every preset of the running set occurrence
     * AFTER planIdx so that the tail interpolates evenly from the anchor (the values in
     * force on the running step) to the new end, and returns how many steps were changed.
     *
     * The k-th remaining step of m gets f = k/m, so the LAST remaining step lands exactly
     * on the requested end and a single remaining step IS the end — no fencepost that
     * leaves the ramp finishing one interval short of what the sheet promised.
     *
     * DURATIONS ARE NOT TOUCHED. A reshape changes pressure, hold and speed; the length of
     * the run is Skip and +30 s, the same separation the override sheet draws. Leaving
     * durMs alone is also what keeps the caller out of setPresetDuration(): nothing here
     * can put the plan, presetDurMs and stageDurMs into disagreement because nothing here
     * changes what they describe.
     *
     * EVERY WRITTEN VALUE IS CLAMPED, through the same {@link #clampUpper}/{@link
     * #clampLower} uploadBatch() writes with — the reshaped steps go to the pump through
     * uploadBatch(), so a step above the ceiling would be silently lowered on the wire and
     * the plan would then describe something the device was never given.
     */
    public static int reshapeRemaining(java.util.List<Model.Preset> plan, int planIdx,
                                       int anchorUp, int anchorLo, int anchorUh,
                                       int anchorLh, int anchorSp,
                                       int endUp, int endLo, int endUh, int endLh, int endSp,
                                       int ceilKpa) {
        int m = remainingStepsOfSet(plan, planIdx);
        for (int k = 1; k <= m; k++) {
            double f = (double) k / (double) m;
            Model.Preset p = plan.get(planIdx + k);
            int up = clampUpper(Math.max(0, lerp(anchorUp, endUp, f)), ceilKpa);
            // Written outright: through writePull, so the step keeps no offset headroom it no
            // longer has (invariant 151).
            writePull(p, up);
            // Never past the step's own highest drop (the floor, or its planned drop): an
            // anchor or an end raised above it is spread no further (Preset#loMax) - and above
            // the floor never within 1.0 inHg of the step's pull (dropKept).
            int lh = clampSeconds(lerp(anchorLh, endLh, f));
            p.lo = dropKept(p, capDrop(p, Math.max(0, lerp(anchorLo, endLo, f))), up);
            p.uh = clampSeconds(lerp(anchorUh, endUh, f));
            p.lh = lh;
            p.sp = clampSpeed(lerp(anchorSp, endSp, f));
        }
        return m;
    }

    /**
     * WAVE-4 WRAP (owner: "build it next, accept the risk") — the ramp step-COUNT
     * handle. Replaces the remaining steps of the running RAMP occurrence with
     * `newSteps` fresh steps interpolated from the anchor tuple to the occurrence's
     * CURRENT end values, the remaining wall time split evenly (the last step absorbs
     * the rounding, every step keeps a 1 s floor). This is the ONE edit that RESIZES
     * the plan mid-run; the caller owns rebuilding every plan-sized structure and
     * rewriting the table (SessionActivity#resizeRemainingRamp). Returns the change
     * in plan size — 0 when refused (no steps left, a no-op count, or count < 1).
     */
    public static int resizeRemaining(java.util.List<Model.Preset> plan, int planIdx,
                                      int anchorUp, int anchorLo, int anchorUh,
                                      int anchorLh, int anchorSp,
                                      int newSteps, int ceilKpa) {
        int m = remainingStepsOfSet(plan, planIdx);
        if (m <= 0 || newSteps < 1 || newSteps == m) return 0;
        Model.Preset cur = plan.get(planIdx);
        Model.Preset end = plan.get(planIdx + m);
        long totalMs = 0;
        // The new steps' highest drop: the highest the steps they replace had - never above.
        int loCap = -1;
        for (int k = 1; k <= m; k++) {
            Model.Preset old = plan.get(planIdx + k);
            totalMs += old.durMs;
            loCap = Math.max(loCap, old.loMax >= 0 ? old.loMax : dropCapOf(null, old.lo));
        }
        for (int k = m; k >= 1; k--) plan.remove(planIdx + k);
        long per = totalMs / newSteps, acc = 0;
        for (int k = 1; k <= newSteps; k++) {
            double f = (double) k / (double) newSteps;
            Model.Preset p = new Model.Preset();
            int up = clampUpper(Math.max(0, lerp(anchorUp, end.up, f)), ceilKpa);
            p.up = up;
            p.loMax = loCap;
            p.builtHoldOnly = cur.builtHoldOnly && end.builtHoldOnly;   // (as below)
            int lh = clampSeconds(lerp(anchorLh, end.lh, f));
            p.lo = dropKept(p, capDrop(p, Math.max(0, lerp(anchorLo, end.lo, f))), up);
            p.uh = clampSeconds(lerp(anchorUh, end.uh, f));
            p.lh = lh;
            p.sp = clampSpeed(lerp(anchorSp, end.sp, f));
            p.stageIdx = end.stageIdx; p.pos = end.pos; p.setId = end.setId;
            // BUILT AS ITS RAMP WAS, not as the figures just interpolated: the anchor is
            // what is in force, and a drop time dialled to 0 s would otherwise make a
            // recounted step "hold only" (#dropLocked). Hold-only only when the ramp was
            // built without a drop at both ends it now runs between.
            p.builtHoldOnly = cur.builtHoldOnly && end.builtHoldOnly;
            // THE OFFSET THE NEW STEP CARRIES, interpolated like its pull and ROUNDED UP, so
            // it is never less than what the whole-routine offset really put on it: the
            // trainer's cap is held against this, step by step (RoutineOffset#apply).
            p.offsetKpa = (int) Math.ceil(cur.offsetKpa
                + (end.offsetKpa - cur.offsetKpa) * f - 1e-9);
            // Ordinals continue past the played steps. They may run past the library
            // ladder's own count — AsRun then reads those rows as off-plan, which is
            // honest: a recounted tail IS the user's edit.
            p.ordinal = cur.ordinal + k;
            p.durMs = k == newSteps ? Math.max(1000L, totalMs - acc) : Math.max(1000L, per);
            acc += p.durMs;
            p.label = end.label;
            plan.add(planIdx + k, p);
        }
        return newSteps - m;
    }

    /**
     * THE ONE WAY A MID-RUN EDIT OTHER THAN THE WHOLE-ROUTINE OFFSET WRITES A STEP'S PULL
     * (WiringCheck invariant 151): the reshape, the ramp's "This set" shift (SetShift) and the
     * upcoming-step editor.
     *
     * A pull written outright is no longer what the offset left, so a NEGATIVE offsetKpa is
     * headroom the step no longer has: it goes to 0, and the trainer's cap then counts from
     * the new figure (RoutineOffset#apply). A positive one is kept - the step has already had
     * what the cap allows. A write that leaves the pull where it was changes nothing, and the
     * step keeps its record exactly. (Safety review of 1af2c85: − ten times, then a reshape to
     * a lower end, kept offsetKpa at −10, and twelve + ran the tail 8 kPa over a 2 kPa cap.)
     */
    public static void writePull(Model.Preset p, int up) {
        if (up != p.up && p.offsetKpa < 0) p.offsetKpa = 0;
        p.up = up;
    }

    /**
     * A PLAN ROUTINE'S RUN HELD TO TODAY'S HARD LIMITS, AT START (review 2, finding 1). A saved
     * routine is held to the limits it was BUILT under; lowering "Most you will go to", turning
     * on "New to pumping" or a routine saved by 0.9 left pulls above today's limit, and nothing
     * at START looked. Every commanding preset whose pull is above `limits[k]` (the run's hard
     * limit for plan index k - SessionActivity#runHardLimits) is lowered to it, its drop kept
     * under it; nothing is ever raised. Returns {how many were lowered, the highest pull one
     * was lowered to} - {0, 0} when none was.
     */
    public static int[] capToLimits(java.util.List<Model.Preset> plan, int[] limits) {
        int n = 0, to = 0;
        if (plan == null || limits == null) return new int[]{ 0, 0 };
        for (int k = 0; k < plan.size() && k < limits.length; k++) {
            Model.Preset p = plan.get(k);
            if (p == null || p.rest || limits[k] <= 0 || p.up <= limits[k]) continue;
            writePull(p, limits[k]);
            p.lo = dropKept(p, p.lo, p.up);       // the gap under the lowered pull (I5)
            n++;
            to = Math.max(to, p.up);
        }
        return new int[]{ n, to };
    }

    /** The wire's hold range: whole seconds, 0..255, both holds (Proto#addPreset). */
    public static int clampSeconds(int s) { return s < 0 ? 0 : (s > 255 ? 255 : s); }

    /** The motor speed the override path permits — the same 0..100 the sliders clamp to. */
    public static int clampSpeed(int pct) { return pct < 0 ? 0 : (pct > 100 ? 100 : pct); }

    private static int lerp(int from, int to, double f) {
        return (int) Math.round(from + (to - from) * f);
    }

    /* ------------------------------------------ the at-pressure clock (wave 1 §2) */

    /**
     * MAY tickTupTiming PUSH THE DEADLINE? Exactly its existing early-return ladder,
     * plus the term the race was missing: the preset must actually be ARMED
     * (armedIdx == planIdx). For 600 ms after a rest, a rejoin or a batch boundary the
     * deadline is the table upload's settle, the pending Advance is what will arm the
     * preset, and cancelling it to push the clock is how "At pressure only" waited
     * forever on a vented cuff.
     */
    public static boolean setClockMayPush(boolean tupTiming, boolean running, int planIdx,
            int armedIdx, boolean rest, boolean holding, boolean restingNow) {
        if (!tupTiming || !running) return false;
        if (planIdx < 0) return false;
        if (rest || holding || restingNow) return false;
        return armedIdx == planIdx;
    }

    /* ---------------------------------------------- routine offsets (wave 2 §4) ------ */

    /** Is a preset of this stage WORK, for the routine offset's purposes? Owner ruling:
     *  work sets only — never a rest, the warm-up, the fatigue block or the retention
     *  hold. Colour is the stage's own declaration (Model.STAGE_WORK). */
    public static boolean workStage(int colour, boolean rest, boolean fatigueBlock,
                                    boolean retention) {
        return !rest && !fatigueBlock && !retention && colour == Model.STAGE_WORK;
    }

    /**
     * THE MOST a run-time routine offset may ADD, in kPa (downward is always free).
     * Owner rulings: never above the trainer's own cap — the plan's progression step
     * (Model.Set.STEP_UP_KPA, 2 kPa) is the most a session may run ahead of its
     * prescription — and never undoing a gentle-day or deload cut (those days add
     * nothing at all). A hand-built routine answers to the ceiling alone.
     */
    public static int routineOffsetCapKpa(boolean trainerRoutine, boolean cutInForce) {
        if (!trainerRoutine) return Integer.MAX_VALUE;
        return cutInForce ? 0 : Model.Set.STEP_UP_KPA;
    }

    /**
     * THE USUAL CAP ON A RUN'S OFFSET for a routine of `trainerTrack` (0.10): the plan's own
     * step (Model.Set#STEP_UP_KPA) for girth and the feeder, nothing for a length routine
     * (its pulls come from the load - it used to be refused outright), no cap for a Library
     * routine. Past it is warned once (RoutineOffset#warning), never refused.
     */
    public static int usualRoutineOffsetCapKpa(int trainerTrack) {
        if (trainerTrack == Model.TRAINER_TRACK_NONE) return Integer.MAX_VALUE;
        return trainerTrack == Plan.TRACK_LENGTH ? 0 : Model.Set.STEP_UP_KPA;
    }

    /**
     * THE CAP A RUN'S OFFSET IS HELD TO NOW (0.10): the usual cap, raised as far as the person
     * has confirmed this run (`warnedKpa`, RoutineOffset#warning) - and still nothing at all on
     * a day with a cut in force (a gentle-day or deload cut is never undone: the rule stays
     * hard). A Library routine answers to the ceiling alone.
     */
    public static int routineOffsetCapKpa(int trainerTrack, boolean cutInForce, int warnedKpa) {
        if (trainerTrack == Model.TRAINER_TRACK_NONE) return Integer.MAX_VALUE;
        if (cutInForce) return 0;
        return Math.max(usualRoutineOffsetCapKpa(trainerTrack), warnedKpa);
    }

    /* ------------------------------------------------- hold-only presets (wave 2 §1) */

    /**
     * DOES THIS PRESET GENUINELY DROP? False three ways (wave 2 §1):
     * no drop dwell at all (lh <= 0 — the prime, traction holds, retention); a stitch
     * chunk (cyclePart — its lo=up−1, lh=1 are wire filler, and showing the pull figure
     * as DROP was the stitched-hold lie); or lo >= up, which is not a drop whatever the
     * dwell says. The DROP cell then reads "Hold only" and its controls lock — a tap
     * that changed ovLo with no visible feedback was worse than no control.
     */
    public static boolean holdOnly(int lh, int lo, int up, boolean cyclePart) {
        return lh <= 0 || cyclePart || lo >= up;
    }

    /**
     * DO THE DROP CONTROLS LOCK? Only when the preset was BUILT without a drop - read from
     * the preset as designed, never from the live edit. Lowering DROP TIME to 0 during a run
     * is a legitimate setting (the pump holds straight through), and it used to lock both
     * drop rows as "hold only", leaving no control that could raise it again.
     *
     * "As designed" is the stamp {@link #asBuilt} left, NOT the preset's figures. Those are
     * the run's plan, and edits write into it: "This set" on a ramp shifts the remaining
     * steps (SetShift), the Reshape and the step count rewrite them. Reading the figures,
     * a drop time dialled to 0 s in "This set" arrived on the next step as lh = 0 and locked
     * it - the same dead end, one step later, for the rest of the run (reported from a
     * device on the 0.9.0 review build). A stitch chunk is locked on its own mark as well.
     */
    public static boolean dropLocked(Model.Preset designed) {
        return designed == null || designed.cyclePart || designed.builtHoldOnly;
    }

    /**
     * COMING STEPS RE-DESIGNS A LATER STEP (0.10 final) - the one stamp of what a preset was
     * built as outside the plan's own build, and it is a build: a later hold-only block given
     * a drop in Coming steps is now designed WITH one (so the − / + strip and the adjust sheet
     * leave its drop controls live when it plays), its Undo puts back what it was built as, and
     * a ramp re-spread in Coming steps builds each new step as its ends were. Only ComingSteps
     * calls this, and only on a step that has not started.
     */
    public static void redesigned(Model.Preset p, boolean builtHoldOnly) {
        p.builtHoldOnly = builtHoldOnly;
    }

    /**
     * STAMPS WHAT A PRESET WAS BUILT AS - with or without a drop ({@link #holdOnly} on the
     * figures it was built with) - into Preset#builtHoldOnly, and returns it. Called where
     * a run's presets are built (Model#plan) and nowhere an edit writes a preset's figures,
     * so {@link #dropLocked} keeps answering for the design whatever is dialled later.
     */
    public static Model.Preset asBuilt(Model.Preset p) {
        p.builtHoldOnly = holdOnly(p.lh, p.lo, p.up, p.cyclePart);
        p.loMax = Math.max(DROP_FLOOR_KPA, Math.max(0, p.lo));
        return p;
    }

    /* ------------------------------------------ "+ step": ONE MORE STEP ON THE RAMP (0.10) */

    /** Why the ramp playing (at `planIdx`) may not take one more step, or null: the pump's
     *  table (ComingSteps#RAMP_STEPS_MAX steps), each step's share of the hour once the ramp
     *  has one more step (Model.Set#rampStepMaxSec(n + 1) - the added step and every step
     *  from the one playing on; 0.10 follow-up: "+ step" took a ramp past its per-step bound,
     *  and Time per step then refused + on it), and the two-hour stop. */
    public static String addStepRefusal(java.util.List<Model.Preset> plan, int planIdx) {
        if (plan == null || planIdx < 0 || planIdx >= plan.size()) return "Nothing is playing.";
        int n = stepOfSet(plan, planIdx)[1];
        if (n >= ComingSteps.RAMP_STEPS_MAX)
            return "The pump takes at most " + ComingSteps.RAMP_STEPS_MAX + " steps.";
        long added = addedStepMs(plan, planIdx);
        String share = addStepShareRefusal(plan, planIdx, n, added);
        if (share != null) return share;
        return ComingSteps.addRefusal(ComingSteps.totalMs(plan), added);
    }

    /** "+ step" past a step's share of the hour, or null: the added step (`addedMs`) and each
     *  step from the one playing to the ramp's last against Model.Set#rampStepMaxSec(n + 1). */
    static String addStepShareRefusal(java.util.List<Model.Preset> plan, int planIdx, int n,
                                      long addedMs) {
        int most = Model.Set.rampStepMaxSec(n + 1);
        boolean over = QuickAdjust.stepSec(addedMs) > most;
        int rem = remainingStepsOfSet(plan, planIdx);
        for (int k = planIdx; !over && k <= planIdx + rem && k < plan.size(); k++)
            over = QuickAdjust.stepSec(plan.get(k).durMs) > most;
        if (!over) return null;
        return "With one more step, " + QuickAdjust.stepTimeLongest(most, n + 1)
            + " Shorten Time per step first.";
    }

    /** How long an added step runs: the ramp's WHOLE CYCLES a step - the last step's, when it
     *  has not started (the step playing may have been re-timed), else the others' - of the
     *  added step's own cycle (it is a copy of the last step, so the last step's hold + drop).
     *  A step is never cut inside a hold, and is never less than one cycle (0.10: every ramp
     *  step is whole cycles, Model.Set#rampCycles). */
    public static long addedStepMs(java.util.List<Model.Preset> plan, int planIdx) {
        int rem = remainingStepsOfSet(plan, planIdx);
        Model.Preset z = plan.get(planIdx + rem);
        int cycles;
        if (rem > 0) {
            cycles = ComingSteps.rampCycles(plan, planIdx + rem, 1);
        } else {
            int k = stepOfSet(plan, planIdx)[0];
            cycles = k > 1 ? ComingSteps.rampCycles(plan, planIdx - (k - 1), k - 1)
                           : ComingSteps.rampCycles(plan, planIdx, 1);
        }
        return Math.max(1000L, (long) Math.max(1, cycles) * ComingSteps.cycleSecOf(z) * 1000L);
    }

    /**
     * ONE MORE STEP ON THE RAMP PLAYING, AT ITS TOP (the strip's "+ step", owner request):
     * a copy of its last step - pull (under the ceiling), drop (held to that step's highest),
     * hold, drop time, speed - with the ramp's time per step, appended after it and marked
     * `added`. The steps' labels say the new count. Returns the new step's index, or −1.
     * Nothing else moves: the steps already run and the one playing are untouched.
     */
    public static int addRampStep(java.util.List<Model.Preset> plan, int planIdx, int ceilKpa) {
        if (addStepRefusal(plan, planIdx) != null) return -1;
        int rem = remainingStepsOfSet(plan, planIdx);
        int last = planIdx + rem;
        Model.Preset z = plan.get(last);
        Model.Preset p = new Model.Preset();
        writePull(p, clampUpper(Math.max(0, z.up), ceilKpa));
        p.loMax = z.loMax;
        p.lo = dropKept(z, capDrop(z, Math.max(0, z.lo)), p.up);
        p.uh = clampSeconds(z.uh); p.lh = clampSeconds(z.lh); p.sp = clampSpeed(z.sp);
        p.durMs = Math.max(1000L, addedStepMs(plan, planIdx));
        p.stageIdx = z.stageIdx; p.pos = z.pos; p.setId = z.setId;
        p.builtHoldOnly = z.builtHoldOnly;
        p.offsetKpa = z.offsetKpa;
        p.ordinal = z.ordinal + 1;
        p.added = true;
        p.label = z.label;
        plan.add(last + 1, p);
        relabelRamp(plan, planIdx);
        return last + 1;
    }

    /** Why "− step" may not take a step out, or null: only a step the person added, and only
     *  before it starts - never the step playing, never a step already run. */
    public static String removeStepRefusal(java.util.List<Model.Preset> plan, int planIdx) {
        int rem = remainingStepsOfSet(plan, planIdx);
        if (rem <= 0) return "The ramp's last step is playing — it can't be taken out.";
        if (!plan.get(planIdx + rem).added)
            return "Only a step you added, not yet started, can be taken out.";
        return null;
    }

    /** Takes the added last step of the ramp playing out; its index, or −1. */
    public static int removeAddedRampStep(java.util.List<Model.Preset> plan, int planIdx) {
        if (removeStepRefusal(plan, planIdx) != null) return -1;
        int last = planIdx + remainingStepsOfSet(plan, planIdx);
        plan.remove(last);
        relabelRamp(plan, planIdx);
        return last;
    }

    /** "Ramp 3/5" for every step of the ramp occurrence at `planIdx`, as it now stands. */
    static void relabelRamp(java.util.List<Model.Preset> plan, int planIdx) {
        int[] kn = stepOfSet(plan, planIdx);
        int first = planIdx - (kn[0] - 1);
        for (int k = 0; k < kn[1]; k++) {
            Model.Preset p = plan.get(first + k);
            p.label = ComingSteps.stepLabelBase(p.label) + " " + (k + 1) + "/" + kn[1];
        }
    }

    /* ------------------------------------------------ THE DROP FLOOR, ON EVERY ROAD (0.10) */

    /**
     * THE DROP FLOOR: a drop above 10 kPa (−3.0 inHg) is a smaller release between holds. No
     * edit of the run ever raises a step's drop past this BY ITSELF, or past the step's own
     * planned drop when that is higher (Preset#loMax). Owner report from a real pump: a ramp
     * step showed "Drop to −7.1 inHg" (24 kPa) under a −11.5 pull after the adjust sheet's drop
     * and the ramp's reshape had moved it.
     *
     * NO LONGER A HARD CAP (owner, 2026-09-30, final): the person may set a drop above it, up
     * to {@link #DROP_GAP_KPA} under the hold's pull ({@link #dropTopKpa}) - warned once
     * ({@link #dropNeedsWarning}), then it is their call. What they set is allowed for the
     * steps it was set on ({@link #allowDrop}); the roads that move a drop as a side effect (a
     * reshape, a re-spread, a recount) still stop at the stamp. The drop's seconds never count
     * as work whatever its pressure: every counter excludes the drop by PHASE, not by pressure
     * (Session#noteSample, Session#tupMs, TupClock).
     */
    public static final int DROP_FLOOR_KPA = 10;

    /**
     * HOW FAR UNDER ITS PULL A DROP ABOVE THE FLOOR MUST STAY: 1.0 inHg (3.39 kPa), rounded UP
     * to the wire's whole kPa - 4. A drop at or under the floor keeps the old rule alone (under
     * the pull, RunEdit#clampLower), so nothing planned before this moves.
     */
    public static final int DROP_GAP_KPA = 4;

    /** THE GAP AS THE OWNER SET IT, for the words: 1.0 inHg, in the person's own unit (1.0 inHg,
     *  3.4 kPa, 2.5 cmHg). What is enforced is {@link #DROP_GAP_KPA}, the wire's whole kPa over
     *  it; a message names the rule, never the rounded figure. */
    public static final double DROP_GAP_SAID_KPA = Model.Fmt.KPA_PER_INHG;

    /** The highest drop a hold pulling to `up` may have: the floor, or {@link #DROP_GAP_KPA}
     *  under the pull when that is higher. (Still strictly under the pull - clampLower.) */
    public static int dropTopKpa(int up) {
        return Math.max(DROP_FLOOR_KPA, up - DROP_GAP_KPA);
    }

    /** `lo` held to {@link #dropTopKpa} of `up` - a drop at or under the floor is untouched. */
    public static int dropUnder(int lo, int up) {
        return lo <= DROP_FLOOR_KPA ? lo : Math.min(lo, dropTopKpa(up));
    }

    /** {@link #dropUnder} for a step that drops (a drop time of `lh`); a step that only holds
     *  keeps its wire filler (`lo` just under the pull - {@link #holdOnly}) as it is. Asked by
     *  every road that writes a later step's drop under a pull it may have moved. */
    public static int dropHeld(int lo, int up, int lh, boolean cyclePart) {
        return holdOnly(lh, lo, up, cyclePart) ? lo : dropUnder(lo, up);
    }

    /**
     * THE DROP RULE, ONE FUNCTION FOR EVERY ROAD THAT PRODUCES OR CHANGES A STEP'S PULL OR
     * DROP (review I4/I5, the controller's ruling 2026-09-30): a drop above the floor
     * ({@link #DROP_FLOOR_KPA} - only the person puts one there) stays {@link #DROP_GAP_KPA}
     * under the pull, in whole kPa; a drop at or under the floor keeps the rule it always had,
     * strictly under the pull ({@link #clampLower}), so nothing planned before moves. A step
     * that only holds (`holds`: no drop time, or a stitch chunk - {@link #holdOnly}) keeps its
     * wire filler just under the pull. `up` is the pull as it will be sent.
     *
     * The gap used to be held on the four roads the first fix patched and nowhere else: the
     * warm-up's "First cycle at" ease cycle took the work's drop under its own, lower pull - 1
     * kPa under it in every Standard warm-up with a drop above the floor - and the routine
     * offset, a lowered ceiling (Model.Set#clamp) and a capped pull left the drop where it was
     * under a pull they had lowered. Unlike {@link #dropHeld}, a drop at or above a lowered
     * pull is still a drop here: that is exactly the step whose gap closed.
     */
    public static int dropKept(int lo, int up, boolean holds) {
        return clampLower(holds || !dropPastGap(lo, up) ? lo : dropTopKpa(up), up);
    }

    /**
     * {@link #dropKept} for a step of a run: it holds when it was BUILT without a drop
     * (Preset#builtHoldOnly - the prime, a hold set) or is a stitch chunk - read from the design,
     * never from a drop time dialled since, as {@link #dropLocked} is: a drop time nudged to 0
     * would otherwise let the drop back up to the pull, a minus that raised it, and nudged back
     * up would bring it down again (SetShiftTest's property).
     */
    public static int dropKept(Model.Preset p, int lo, int up) {
        return dropKept(lo, up, p != null && (p.cyclePart || p.builtHoldOnly));
    }

    /** Is `lo` a drop above the floor closer than {@link #DROP_GAP_KPA} under a pull of `up` -
     *  what {@link #dropKept} lowers, and what a road that refuses instead of lowering (the
     *  strip, the adjust sheet's apply, Coming steps) refuses? */
    public static boolean dropPastGap(int lo, int up) {
        return lo > DROP_FLOOR_KPA && lo > dropTopKpa(up);
    }

    /**
     * WARNED ONCE, YOUR CALL: a drop raised from at or under the floor to above it asks once -
     * not when the drop is already above it (that was asked, or the routine was built so), not
     * when `warned` (the run's own record that it was confirmed), and never on the way down.
     */
    public static boolean dropNeedsWarning(int fromLo, int toLo, boolean warned) {
        return !warned && toLo > DROP_FLOOR_KPA && fromLo <= DROP_FLOOR_KPA && toLo > fromLo;
    }

    /** The one-time warning's title, words and buttons. */
    public static final String DROP_WARN_TITLE = "A higher drop?";
    public static String dropWarning() {
        return "A drop above " + Model.Fmt.p(DROP_FLOOR_KPA) + " is a smaller release between "
            + "holds — the guidance keeps it at " + Model.Fmt.p(DROP_FLOOR_KPA)
            + " or less. Keep it?";
    }
    public static final String DROP_WARN_GO = "Keep it", DROP_WARN_BACK = "Go back";

    /** Why a drop may not go up to `newLo` under a pull of `up`: past {@link #dropTopKpa}. */
    public static String dropGapSaid() {
        return "The drop stays at least " + Model.Fmt.dMag(DROP_GAP_SAID_KPA) + " under the pull.";
    }

    /**
     * A DROP THE PERSON RAISED ON THIS STEP, ALLOWED THERE: the step's highest drop (its stamp)
     * rises to `lo` - held to {@link #dropTopKpa} of its pull - so the wire and every road that
     * asks the stamp keep what was set. Only the roads where the person sets the drop itself
     * call this; nothing raises a stamp as a side effect. A step that does not drop, or is not
     * stamped, is left as it is.
     */
    public static void allowDrop(Model.Preset p, int lo) {
        if (p == null || p.loMax < 0 || holdOnly(p.lh, p.lo, p.up, p.cyclePart)) return;
        p.loMax = Math.max(p.loMax, dropUnder(lo, p.up));
    }

    /** `lo` held to the step's highest drop (Preset#loMax), or `lo` when it is not stamped. */
    public static int capDrop(Model.Preset p, int lo) {
        if (p == null || p.loMax < 0) return lo;
        return Math.min(lo, p.loMax);
    }

    /** The highest drop a step may be given: its stamp, or - unstamped - the floor or what it
     *  has now, the higher. */
    public static int dropCapOf(Model.Preset p, int inForceLo) {
        if (p != null && p.loMax >= 0) return p.loMax;
        return Math.max(DROP_FLOOR_KPA, inForceLo);
    }

    /* ----------------------------------------------- +30 s reports the RUN (wave 1 §3) */

    /**
     * HOW LONG THE WHOLE RUN HAS LEFT: what remains of the playing preset
     * (presetFireAt − now, floored at 0) plus every later preset's duration. The
     * after-assessment window is the CALLER's to add (SessionActivity#runRemainingMsNow)
     * — it is routine state, and this stays a pure function of the plan's clock.
     */
    public static long runRemainingMs(long presetFireAt, long now,
                                      long[] presetDurMs, int planIdx) {
        long left = presetFireAt - now;
        if (left < 0) left = 0;
        if (presetDurMs != null)
            for (int i = planIdx + 1; i < presetDurMs.length; i++) left += presetDurMs[i];
        return left;
    }

    /** The +30 s toast: the run's remaining time and NOTHING else — never a preset
     *  label ("3:18 left in Manual run 4/9" read as a wrong time in the wrong scope). */
    public static String extendToastText(long runLeftMs) {
        return "+30 s — " + runEndsLine(runLeftMs);
    }

    /** The persistent line by the big countdown, fed by the same figure as the toast. */
    public static String runEndsLine(long runLeftMs) {
        return "run ends in " + Model.Fmt.t(runLeftMs / 1000);
    }

    /**
     * S03 - THE SAME LINE, LABELLING THE AFTER-TEST WHEN THIS RUN HAS ONE.
     *
     * "run ends in" (runRemainingMsNow) adds the after-assessment's length {@link
     * Tau#trackedAssessSec}; the ROUTINE card's own total ({@link
     * SessionActivity#paintRoutineStrip}, via {@link #routineElapsedLine}) does not - it is
     * the plan's stage-by-stage total, and the after-test is not one of the routine's
     * stages. Two "time left" figures on one screen that differ by a fixed amount and say
     * nothing about it read as a disagreement; owner ruling (2026-09-26) was to keep both
     * figures and name the difference here rather than fold the test into one of them.
     *
     * `afterTestSec` <= 0 (no after-test, or it runs before rather than after) leaves this
     * identical to {@link #runEndsLine(long)}.
     */
    public static String runEndsLine(long runLeftMs, int afterTestSec) {
        String base = runEndsLine(runLeftMs);
        return afterTestSec > 0
            ? base + " (" + Model.Fmt.t(afterTestSec) + " of that is a tissue test after)"
            : base;
    }

    /* ----------------------------------------------------- S01: the ROUTINE card's total */

    /**
     * S01 - THE ROUTINE CARD, READ THE WAY THE NOW CARD ALREADY READS ONE SET.
     *
     * {@link SessionActivity#paintRoutineStrip} sums stageDurMs for its total, and
     * stageDurMs grows every time Hold, an inserted rest, a changeover wait or "at
     * pressure only" timing pushes a preset's duration forward ({@link
     * SessionActivity#tickHold}; the at-pressure push in {@code tickTupTiming}) - so that
     * total climbed while the run played, exactly the "reads as a set getting longer"
     * defect the NOW card ({@link RunScreen}'s {@code nowSub}) was fixed for, on this card
     * only.
     *
     * `growingTotalMs` IS that climbing sum; `addedMs` is the run-level accumulator of just
     * those four pushes ({@code SessionActivity#routineAddedMs} - a +30 s extend or a skip's
     * shortening change `growingTotalMs` too, but are never added to `addedMs`, so they fall
     * out of `plannedMs` for free rather than needing their own bookkeeping that could drift
     * from this one). `doneMs` is left alone - it is the marker's own position
     * (Trace#routineFraction) times the growing total, i.e. genuine elapsed time, and this
     * function does not touch what "done" means.
     */
    /**
     * HOW MUCH OF A STEP IS DONE, for the NOW card's "x of y" (0.10 final, device check: "4:45
     * of 4:44", and an elapsed that jumped 30 s): the step's length less what is left of it on
     * the run's own clock - so the two are one statement, time added to a step goes to its
     * length and never to what is done, and "done" never passes the length. `totalMs` is what
     * the card reports against (the step's length, or its promise under "at pressure only").
     */
    public static long doneOfStepMs(long totalMs, long presetFireAt, long now) {
        long left = Math.max(0L, presetFireAt - now);
        long done = totalMs - left;
        return done < 0 ? 0L : Math.min(done, Math.max(0L, totalMs));
    }

    public static String routineElapsedLine(long doneMs, long growingTotalMs, long addedMs) {
        long plannedMs = growingTotalMs - addedMs;
        if (plannedMs < 0) plannedMs = 0;
        String base = Model.Fmt.t(doneMs / 1000) + " / " + Model.Fmt.t(plannedMs / 1000);
        return addedMs >= 1000 ? base + " +" + Model.Fmt.t(addedMs / 1000) : base;
    }

    /** A PUSH MOVES WHEN, NEVER WHICH. Trivial on purpose: the rule every re-post site
     *  binds to is "the index re-posted is the index the pending Advance already
     *  carries", never a recomputed planIdx + 1 — recomputing is how a push inside the
     *  settle skipped the arming step. */
    public static int repostIndex(int pendingIdx) {
        return pendingIdx;
    }

    /**
     * WHICH PRESET THE SCREEN IS TALKING ABOUT — one answer, for every reader.
     *
     * The run PLAYS `planIdx`: playPreset starts it, the scheduler advances it, and the
     * pump was given that preset's numbers. The wall clock is a SECOND derivation of the
     * same thing (elapsed against the running durations), and the two part company
     * exactly where it hurts — at a preset boundary, all through a paused clock, and for
     * the settle after a skip, a rest or a +30 s.
     *
     * Reported from a device: the card read "Warm-up to -8.6 inHg" while the paused-clock
     * line and the dashed band quoted the NEXT preset's -10.9, the band drew itself as a
     * vertical hatch between the two levels (consecutive samples landing on either side
     * of the boundary), and a rest was measured against a pull nobody was commanding.
     *
     * The armed index is the one the pump actually holds, so it wins whenever there is
     * one. The wall-clock index stays as the fallback for the handful of hundred
     * milliseconds between presets, when nothing is armed and the screen still has to
     * name something.
     */
    /**
     * HOW EARLY AN ADVANCE MAY ARRIVE AND STILL BE ITS OWN — the slack between the
     * deadline a preset was scheduled against and the moment its Advance actually runs.
     * Generous enough to cover a late handler, a settle boundary and the rounding in
     * every re-post site; far short of any real step.
     */
    public static final long ADVANCE_SLACK_MS = 1500L;

    /**
     * IS THIS ADVANCE FIRING BEFORE THE STEP IT WOULD END IS DUE?
     *
     * Six places re-post the pending Advance, each against `presetFireAt`. An Advance that
     * runs while that deadline is still comfortably in the future did not come from any of
     * them — it is a stale post, and acting on it ends the step (and, on the last one, the
     * whole run) early. From a device log: "RUN Manual run — 1 presets, 12:00" followed
     * 2.4 s later by "COMPLETE after 0:03 — preset 1/1", with no skip and no stop between
     * them.
     *
     * Answering "yes" costs nothing: the caller re-posts against the real deadline, which
     * is the schedule that was wanted in the first place.
     */
    public static boolean advanceTooEarly(long presetFireAt, long now) {
        return presetFireAt - now > ADVANCE_SLACK_MS;
    }

    public static int liveIndex(boolean armed, int planIdx, int wallIdx, int planSize) {
        if (armed && planIdx >= 0 && planIdx < planSize) return planIdx;
        if (wallIdx >= 0 && wallIdx < planSize) return wallIdx;
        return -1;
    }
}
