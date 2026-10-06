package org.openpump;

/**
 * WHICH SET OF THE BLOCK IS PLAYING, AND HOW MANY IT HAS, as the pump runs it (0.10 final, the
 * owner-approved mock's reanchor(): the set count stays, the set under way keeps its number).
 *
 * A block of sets is ONE preset the pump repeats on its own clock: hold, drop, hold, drop. The
 * app cannot see that clock; it counts from the moments it knows the pump's cycle began. Three
 * things start it again, and each is told to this clock by the engine as it happens:
 *
 *   - A LIVE CHANGE that goes in force (the − / + strip, the More sheet, "+30 s hold", a
 *     Revert, a convergence). The protocol has no "modify the running preset": a change is a
 *     slot STARTED, so the pump begins the set under way again, from its hold. The set keeps
 *     its number ({@link #restart}).
 *   - A PAUSE: the pump holds, and no set is counted ({@link #pause}); Resume starts the slot
 *     again and the paused set carries on, from its hold ({@link #resume}).
 *   - A new step: set 1 ({@link #start}).
 *
 * THE BLOCK KEEPS ITS SETS. When a change alters the cycle (the hold or the drop time), the
 * block's end moves so that every set still to run - the one under way, which the pump has
 * started again, included - runs whole at the new cycle: {@link #retimeMs}. That is the sets
 * left times the change in the cycle, plus the part of the set under way already run. The
 * engine moves the clock by it the way +30 s moves it (SessionActivity#commitEdit).
 *
 * Pure: nothing here is sent to the pump.
 */
public final class SetClock {

    private int idx = -1;
    private int sets;
    private long cycleMs;
    private long anchorAt;
    private int anchorSet = 1;
    private boolean paused;
    private int pausedSet;
    /** How far into its cycle the pump was when the pause came (HoldCarryOn: Resume carries
     *  the hold under way on from there). */
    private long pausedInCycle;

    /** A step began: set 1 of `sets`, the pump's cycle `cycleMs` long, from `now`. A step
     *  that does not cycle is told `sets` = 0 and `cycleMs` = 0: nothing is counted. */
    public void start(int planIdx, long now, long cycleMs, int sets) {
        idx = planIdx;
        this.sets = Math.max(0, sets);
        this.cycleMs = Math.max(0L, cycleMs);
        anchorAt = now;
        anchorSet = 1;
        paused = false;
        pausedSet = 0;
    }

    /** The step the count is for, or -1. */
    public int forIdx() { return idx; }

    /** Does it count sets for step `planIdx`? */
    public boolean counts(int planIdx) { return idx >= 0 && idx == planIdx && sets > 0; }

    /**
     * The pump started the set under way again - a live change in force, a convergence, a
     * Revert - now with a cycle of `newCycleMs`. The set keeps its number; the count goes on
     * from here at the new cycle. A paused count stays paused (the engine resumes it).
     */
    public void restart(long now, long newCycleMs) {
        if (idx < 0) return;
        if (!paused) anchorSet = set(now);
        anchorAt = now;
        if (newCycleMs > 0) cycleMs = newCycleMs;
    }

    /**
     * The pump was started on an entry that CARRIES ON the set under way (HoldCarryOn): the set
     * keeps its number and its place, and ends at `setEndsAt`; the sets after it run at
     * `newCycleMs`. Nothing starts again.
     */
    public void carryOn(long now, long setEndsAt, long newCycleMs) {
        if (idx < 0) return;
        if (!paused) anchorSet = set(now);
        if (newCycleMs > 0) cycleMs = newCycleMs;
        anchorAt = cycleMs > 0 ? setEndsAt - cycleMs : now;
    }

    /** The run paused on this step: the set playing now is held, and nothing is counted. */
    public void pause(long now) {
        if (idx < 0 || paused) return;
        pausedSet = set(now);
        pausedInCycle = inCycleMs(now);
        paused = true;
    }

    /** How far into its cycle the pump was when the pause came (0 when not paused). */
    public long pausedInCycleMs() { return paused ? pausedInCycle : 0L; }

    /**
     * RESUME CARRIES THE HOLD UNDER WAY ON (HoldCarryOn): the pump held the pressure through the
     * pause, so the paused set keeps its number and its place - it ends at `setEndsAt` - and
     * the sets after it run at `cycleMs`. Nothing starts again.
     */
    public void resumeCarryOn(long now, long setEndsAt, long cycleMs) {
        if (idx < 0) return;
        if (paused) anchorSet = Math.max(1, pausedSet);
        paused = false;
        if (cycleMs > 0) this.cycleMs = cycleMs;
        anchorAt = this.cycleMs > 0 ? setEndsAt - this.cycleMs : now;
    }

    /** A pause that came in the DROP: that set's hold was run, so the NEXT set starts now,
     *  from its hold. */
    public void resumeNext(long now, long cycleMs) {
        if (idx < 0) return;
        int next = (paused ? Math.max(1, pausedSet) : set(now)) + 1;
        anchorSet = sets > 0 ? Math.min(next, sets) : next;
        paused = false;
        anchorAt = now;
        if (cycleMs > 0) this.cycleMs = cycleMs;
    }

    /** The slot was started again after a pause (or a rest the user inserted): the paused
     *  set carries on, from its hold, at `cycleMs`. Without a pause it is a restart. */
    public void resume(long now, long cycleMs) {
        if (idx < 0) return;
        if (paused) {
            anchorSet = Math.max(1, pausedSet);
            paused = false;
            anchorAt = now;
            if (cycleMs > 0) this.cycleMs = cycleMs;
            return;
        }
        restart(now, cycleMs);
    }

    public boolean paused() { return paused; }

    /** The set playing at `now`, 1..sets (1 when nothing is counted). */
    public int set(long now) {
        if (paused) return Math.max(1, pausedSet);
        int k = anchorSet;
        if (cycleMs > 0 && now > anchorAt) k += (int) ((now - anchorAt) / cycleMs);
        return sets > 0 ? Math.min(Math.max(1, k), sets) : Math.max(1, k);
    }

    /** The sets the block has - the same all through it. */
    public int sets() { return sets; }

    /** The sets still to run, the one under way included. */
    public int setsLeft(long now) {
        return sets > 0 ? Math.max(1, sets - set(now) + 1) : 0;
    }

    /** How far into its cycle the pump is at `now`. */
    public long inCycleMs(long now) {
        if (paused) return 0L;
        long el = Math.max(0L, now - anchorAt);
        return cycleMs > 0 ? el % cycleMs : el;
    }

    /** The cycle counted now, ms. */
    public long cycleMs() { return cycleMs; }

    /**
     * HOW FAR THE BLOCK'S END MOVES when a change starts the set under way again at a cycle of
     * `newCycleMs`, with `setsLeft` sets to run (that one included) and `leftMs` left on the
     * block: every set left runs whole - {@code setsLeft x newCycle - left}. Positive moves the
     * end out (a longer hold), negative in (a shorter one).
     */
    public static long retimeMs(int setsLeft, long newCycleMs, long leftMs) {
        if (setsLeft <= 0 || newCycleMs <= 0) return 0L;
        return (long) setsLeft * newCycleMs - Math.max(0L, leftMs);
    }

    /** A new run: nothing counted. */
    public void reset() {
        idx = -1; sets = 0; cycleMs = 0L; anchorAt = 0L; anchorSet = 1; paused = false;
        pausedSet = 0; pausedInCycle = 0L;
    }
}
