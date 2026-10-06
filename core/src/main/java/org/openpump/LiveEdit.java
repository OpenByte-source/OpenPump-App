package org.openpump;

/**
 * THE RUN SCREEN'S − / + CELLS AS A PENDING TARGET (bug B1).
 *
 * WHAT WAS WRONG. A tap wrote the working copy (SessionActivity's ov* fields) and armed a
 * 400 ms settle; the settle handed the copy to the pump. Three things followed from how the
 * pieces were joined, and the emulator's journal caught all three:
 *
 *   1. THE CELL LAGGED. Nothing repainted on a tap, so the new figure waited for the next
 *      400 ms tick - "the tap didn't take".
 *   2. THE CELL WENT BACK. The settle cleared its "pending" mark BEFORE the write, and the
 *      write repainted the screen BEFORE the app recorded the new value as in force - so for
 *      up to one tick the cell showed the old value again, without "sending…"
 *      (`UI pull=13.9 "−4.1 inHg"` straight after the OVERRIDE line).
 *   3. A TAP COULD BE LOST. Whether a tap stepped from the pending target or reseeded from
 *      what was in force was decided by a CLOCK (RunEdit#nudgeShouldReseed: 400 ms since the
 *      last change), not by whether the target had actually been handed over. The settle
 *      job is posted for exactly 400 ms and runs later whenever the main thread is busy
 *      (the 25 fps trace, the tick), so a tap in that gap reseeded from the pre-tap figure
 *      and overwrote the first tap: two taps, one step.
 *
 * THE RULE NOW. The working copy IS the target. A tap steps from it whenever an edit is
 * pending for the step playing (dialled, or sent and not yet acknowledged), and reseeds
 * from what is in force only when nothing is. The cell shows the target the instant it is
 * tapped and keeps showing it - with the pending style - until the pump acknowledges the
 * write that carries it. Intermediate targets are coalesced by the settle (only the last
 * is written), never lost. Every target passes the limits the wire is written with (the
 * ceiling, drop under pull, the seconds and speed ranges) and the hold-only lock, one tap
 * at a time. A write the app refuses, or one it cannot send, ends the edit: the cell
 * returns to what is really in force and the caller says why.
 *
 * AND A WRITE THE PUMP DOES NOT TAKE ENDS IT THE SAME WAY (the pump-refusal fix). A write the
 * pump refused (`2C FD`), or never answered, used to keep its value as in force and only say
 * "not confirmed". The owner's pump refused four raises in a row, and the app went on
 * counting, carrying and shifting a ramp by values the pump never ran. Now only the ack puts a
 * change in force (PendingChange, in the caller). {@link #failed} ends the edit, and the cell
 * shows what is really in force.
 *
 * A CHANGE DIALLED WHILE A WRITE IS ON ITS WAY IS BUILT ON ONE OF TWO THINGS, and that write's
 * answer decides whether it still stands:
 *   - on the write's own target (a tap while it is sending): it stands only if the pump takes
 *     that write - refused, or overtaken, the dial goes with it;
 *   - on what was confirmed (the dial on top was refused, and a new one began): it stands only
 *     if the pump does NOT take the write - taken, the dial is built on figures the pump has
 *     since left, and a − on one field would put another back up. It is not sent until the
 *     answer comes (#waitsOnAnswer).
 * Nothing dialled is ever sent on a base the pump is not running.
 *
 * WHAT THIS DOES NOT DO. It commands nothing. It holds no copy of the pump's state: the
 * caller passes what is in force (the carried adjustment, or the running preset as it was
 * clamped when sent) and the working copy, and writes through its own single path
 * (SessionActivity#applyOverride). Pure, so the rules above are tested without a device.
 *
 * Field numbers are the run screen's own (RunScreen.OvSlide) for the five things a preset
 * is on the wire.
 */
public final class LiveEdit {

    public static final int UP = 0, LO = 1, UH = 2, SP = 3, LH = 4;
    public static final int FIELDS = 5;

    /** One tap of HOLD or DROP TIME, in seconds, and of SPEED, in per-cent. Pressures step
     *  through Model.Fmt#nudgeKpa, one display-unit step the wire can express. */
    public static final int SECONDS_STEP = 5, SPEED_STEP = 5;

    /** What a tap did. */
    public static final int MOVED = 0, AT_LIMIT = 1, LOCKED = 2;

    private static final int IDLE = 0, DIALLING = 1, SENT = 2;

    private int phase = IDLE;
    /** The plan index the edit was dialled for; an edit never outlives its step. */
    private int forIdx = -1;
    /** What was in force when the edit began - the cells whose target differs are pending. */
    private final int[] base = new int[FIELDS];
    private long lastChangeAt = 0;
    /** The number of the last write handed over, so a late ack cannot end a newer target. */
    private int seq = 0;
    /** Is the target being dialled now built on a write still on its way? A change dialled on
     *  top of a sent target starts from that target; if the pump then refuses it, what was
     *  dialled on top of it is dropped with it (#failed). */
    private boolean onUnanswered;
    /** ...or on what was confirmed, begun while a write for the same step was on its way. */
    private boolean onConfirmedWhileOpen;
    /** Is the last write handed over (`seq`) still unanswered, and for which step? */
    private boolean writeOpen;
    private int writeFor = -1;
    /** Did the last answer end a change that was still being dialled? */
    private boolean droppedDial;

    /** Is an edit pending for this step - dialled, or sent and not yet acknowledged? */
    public boolean pendingAt(int planIdx) {
        return phase != IDLE && forIdx == planIdx;
    }

    /** Must the working copy be seeded from what is in force before this step is edited?
     *  Only when nothing is pending for it: a pending target is what the next tap builds on. */
    public boolean needsSeed(int planIdx) {
        return !pendingAt(planIdx);
    }

    /**
     * What an edit of this step STARTS FROM, as a fresh copy: the working copy while an edit
     * is pending for the step, else what is in force. Every editor that sets one field and
     * sends all five starts here - a slider, the speed bar, a typed figure, Apply now - so
     * a working copy left from another step never rides along with it. (The adjust sheet
     * seeded only when it opened; left open across a step change, moving its drop slider
     * wrote the previous step's pull.) {@link #tap} applies the same rule in place.
     */
    public int[] startFrom(int[] work, int[] inForce, int planIdx) {
        return (needsSeed(planIdx) ? inForce : work).clone();
    }

    /**
     * IS A VIEW OF ABSOLUTE FIGURES STILL TRUE? The adjust sheet's sliders, a typed figure
     * and the speed bar each set an ABSOLUTE figure, picked against what the view SHOWED.
     * startFrom keeps the figures the gesture did not touch right; it cannot make the one it
     * did touch right when the picture it was picked from is of something else. Seen in
     * review: a sheet drawn on a −3.8 inHg step and left open into a −2.1 one - "a bit to the
     * left" on the pull slider sent −3.6, a gesture toward less pull that RAISED the pump.
     * The same after a refusal on one step: the sheet still shows the target that was never
     * sent. So an absolute-figure gesture is taken only while the view is CURRENT - drawn
     * for the step playing (`viewIdx` == `planIdx`) and showing exactly what an edit would
     * start from now (`now`: {@link #startFrom}, or the ramp's end for the end controls).
     * Otherwise the caller refuses it, redraws the view and says so; nothing is sent.
     */
    public static boolean viewCurrent(int viewIdx, int[] viewShows, int planIdx, int[] now) {
        return viewIdx == planIdx && viewShows != null && now != null
            && java.util.Arrays.equals(viewShows, now);
    }

    /** The value one tap moves a field to, before any limit. */
    public static int stepped(int field, int value, int dir) {
        switch (field) {
            case UP: case LO: return Model.Fmt.nudgeKpa(value, dir);
            case UH: case LH: return value + dir * SECONDS_STEP;
            default:          return value + dir * SPEED_STEP;
        }
    }

    /**
     * Puts one field of `w` to `raw` under every limit the wire is written with - the pull
     * under the ceiling and the drop strictly under the pull (uploadBatch's two lines,
     * RunEdit#clampUpper/clampLower), the hold 1..255 s, the drop time 0..255 s, the speed
     * 0..100 %. Moving the pull re-clamps the drop under it.
     */
    public static void set(int[] w, int field, int raw, int ceilKpa) {
        if (field == UP) {
            w[UP] = RunEdit.clampUpper(Math.max(0, raw), ceilKpa);
            w[LO] = RunEdit.clampLower(w[LO], w[UP]);
        } else if (field == LO) {
            w[LO] = RunEdit.clampLower(Math.max(0, raw), w[UP]);
        } else if (field == UH) {
            w[UH] = Math.max(1, Math.min(255, raw));
        } else if (field == LH) {
            // 0 s is a real drop time - "do not dwell at the bottom" - and the wire carries it.
            w[LH] = RunEdit.clampSeconds(raw);
        } else {
            w[SP] = RunEdit.clampSpeed(raw);
        }
    }

    /**
     * ONE TAP of − (dir −1) or + (dir +1) on `field`. Seeds `work` from `inForce` when
     * nothing is pending for this step, then steps it in place. Returns LOCKED for a drop
     * control on a hold-only preset and AT_LIMIT when a limit left the figure where it was
     * (the caller says which limit); in both cases nothing is dialled.
     */
    public int tap(int[] work, int field, int dir, int[] inForce, int ceilKpa,
                   boolean dropLocked, int planIdx, long now) {
        if (dropLocked && (field == LO || field == LH)) return LOCKED;
        int from = needsSeed(planIdx) ? inForce[field] : work[field];
        return tapBy(work, field, stepped(field, from, dir) - from, inForce, ceilKpa,
                     dropLocked, planIdx, now);
    }

    /**
     * ONE TAP OF AN EXACT SIZE - the run screen's − / + strip (0.10): the pull and the drop
     * by 1 kPa, the hold by 5 s, the drop time by 1 s, "+30 s hold" by 30 s (QuickAdjust).
     * Everything else is {@link #tap}'s: seeds `work` from `inForce` when nothing is pending
     * for this step, steps it in place under {@link #set}'s limits (the ceiling, the drop under
     * the pull, the seconds' ranges), and returns LOCKED for a drop control on a hold-only
     * step and AT_LIMIT when a limit left the figure where it was - nothing dialled in either.
     */
    public int tapBy(int[] work, int field, int delta, int[] inForce, int ceilKpa,
                     boolean dropLocked, int planIdx, long now) {
        if (dropLocked && (field == LO || field == LH)) return LOCKED;
        if (needsSeed(planIdx)) System.arraycopy(inForce, 0, work, 0, FIELDS);
        int before = work[field];
        if (delta == 0) return AT_LIMIT;
        int[] next = work.clone();
        set(next, field, before + delta, ceilKpa);
        if (next[field] == before) return AT_LIMIT;
        System.arraycopy(next, 0, work, 0, FIELDS);
        edited(inForce, planIdx, now);
        return MOVED;
    }

    /**
     * The working copy was changed for this step - by a tap, a slider or a typed figure.
     * Starts an edit (remembering what was in force) or continues the pending one; a new
     * change on top of a write already sent makes the target pending again.
     */
    public void edited(int[] inForce, int planIdx, long now) {
        if (!pendingAt(planIdx)) {
            System.arraycopy(inForce, 0, base, 0, FIELDS);
            forIdx = planIdx;
            onUnanswered = false;
            onConfirmedWhileOpen = writeOpen && writeFor == planIdx;
        } else if (phase == SENT) {
            onUnanswered = true;
        }
        phase = DIALLING;
        lastChangeAt = now;
    }

    /** Has the target stopped moving for the settle window, so it may be written? */
    public boolean settled(long now) {
        return RunEdit.debounceSettled(lastChangeAt, now, RunEdit.OVERRIDE_DEBOUNCE_MS);
    }

    /** How long to wait before the target will have settled. */
    public long settleDelay(long now) {
        return RunEdit.debounceDelay(lastChangeAt, now, RunEdit.OVERRIDE_DEBOUNCE_MS);
    }

    /** Is a change being dialled for this step that has not been handed over yet? */
    public boolean dialling(int planIdx) {
        return phase == DIALLING && forIdx == planIdx;
    }

    /** The step the pending target was dialled for, or −1. */
    public int dialledFor() {
        return phase == IDLE ? -1 : forIdx;
    }

    /**
     * The target is being handed to the pump now. Returns the write's number for
     * {@link #acked}/{@link #failed}, or −1 when nothing was dialled for this step.
     * The edit stays pending - the cell keeps the target - until the ack.
     */
    public int sending(int planIdx) {
        if (!pendingAt(planIdx)) return -1;
        phase = SENT;
        onUnanswered = false;
        onConfirmedWhileOpen = false;
        writeOpen = true;
        writeFor = planIdx;
        return ++seq;
    }

    /** Must the change being dialled for this step wait for the answer to a write still on its
     *  way before it is sent? True when it was begun on what was confirmed while that write was
     *  out: which base it stands on is not known until the pump answers. */
    public boolean waitsOnAnswer(int planIdx) {
        return phase == DIALLING && forIdx == planIdx && onConfirmedWhileOpen;
    }

    /** The pump acknowledged write `s`, and it went in force. Ends the edit if nothing newer
     *  was dialled since; true when the edit ended. */
    public boolean acked(int s) {
        return acked(s, true);
    }

    /**
     * The pump acknowledged write `s`. `tookEffect`: the caller put it in force - false when a
     * later START overtook it, or its step had ended (PendingChange#acked). True when the edit
     * ended: the write itself was the edit, or a change still being dialled stood on a base the
     * pump is not running (#lastAnswerDroppedADial - the caller cancels its settle and says so).
     */
    public boolean acked(int s, boolean tookEffect) {
        droppedDial = false;
        if (s != seq) return false;
        writeOpen = false;
        if (phase == SENT) { phase = IDLE; return true; }
        if (phase != DIALLING) return false;
        boolean stale = onUnanswered ? !tookEffect : onConfirmedWhileOpen && tookEffect;
        onUnanswered = false;
        onConfirmedWhileOpen = false;
        if (!stale) return false;          // built on what the pump is running now: it stands
        phase = IDLE;
        droppedDial = true;
        return true;
    }

    /** Did the last {@link #acked}/{@link #failed} end a change that was being dialled? */
    public boolean lastAnswerDroppedADial() {
        return droppedDial;
    }

    /**
     * The pump REFUSED write `s` (`2C FD`), or never answered it. It is not in force - the
     * caller did not put it there (PendingChange) - so the edit ends and the cell shows what
     * really is, and the caller says why. True when it applied; a stale write's answer, after a
     * newer target, changes nothing.
     *
     * AND A CHANGE DIALLED ON TOP OF IT GOES WITH IT. A tap while a write is on its way starts
     * from that write's target. Sent after a refusal, a − tap on a refused raise would still
     * raise what the pump runs: the pump never left the old figure. So that dial ends too, and
     * the caller cancels its settle - nothing built on a change the pump refused is sent.
     */
    public boolean failed(int s) {
        droppedDial = false;
        if (s != seq) return false;
        writeOpen = false;
        if (phase == SENT || (phase == DIALLING && onUnanswered)) {
            droppedDial = phase == DIALLING;
            phase = IDLE;
            onUnanswered = false;
            return true;
        }
        onConfirmedWhileOpen = false;      // built on what is still in force: it stands
        return false;
    }

    /** The write was refused or could not be sent, or the edit was superseded (a hold, a
     *  revert, a reshape, the reconnect prompt, the step ending). The cell goes back to what
     *  is in force. EVERY place that cancels the settle job must call this (or reset()):
     *  this class cannot see a job being cancelled, and an edit left DIALLING with no job
     *  shows "sending…" for a target nobody will send - and the next tap builds on it, so a
     *  − could raise the pressure (D1; WiringCheck invariant 17). */
    public void refused() {
        phase = IDLE;
        onUnanswered = false;
        onConfirmedWhileOpen = false;
    }

    /** A new run: nothing pending. */
    public void reset() {
        phase = IDLE; forIdx = -1; lastChangeAt = 0; onUnanswered = false;
        onConfirmedWhileOpen = false; writeOpen = false; writeFor = -1; droppedDial = false;
    }

    /** The figure a cell shows: the target while an edit is pending for this step, else
     *  what is in force. */
    public int shown(int field, int[] work, int[] inForce, int planIdx) {
        return pendingAt(planIdx) ? work[field] : inForce[field];
    }

    /** Does this cell wear the pending style? Only a field the edit actually changed. */
    public boolean pending(int field, int[] work, int planIdx) {
        return pendingAt(planIdx) && work[field] != base[field];
    }

}
