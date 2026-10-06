package org.openpump;

/**
 * A LIVE CHANGE NEVER REPEATS THE HOLD UNDER WAY (owner report from a real Epic Hydro pump,
 * 0.10): "+30 s hold" 1:30 into a 2:00 hold gave a fresh 2:30 hold instead of 0:30 + 0:30
 * more. Pure: what to send, and how the time moves.
 *
 * WHY IT HAPPENED. The protocol has no "modify the running preset" (Proto: Add, Delete, Start).
 * A live change is an override entry STARTED, and a started entry begins its cycle from the
 * top - the upper phase (pull, then hold) for its whole `uh`, then the drop for `lh`. So an
 * entry written with the new hold (2:30) held 2:30 from the moment it was started, whatever
 * of the old hold had already run: the elapsed 1:30 was repeated.
 *
 * WHAT IS SENT NOW. The entry that carries the change on is written for the cycle under way:
 * its hold is what is LEFT of the new hold - the new hold less the part of the hold already
 * run (from the app's count of the pump's cycle, SetClock#inCycleMs), never under
 * {@link #MIN_LEFT_MS} - with the new pull, drop, drop time and speed. The pump is at pressure
 * already, so the upper phase is the rest of the hold. At that cycle's end:
 *   - a step that is ONE cycle (a Length ramp's 2:05 steps, a set's own repetition, the
 *     warm-up's prime) simply ends - the next step arms itself; nothing more is sent;
 *   - a block the pump repeats as ONE program would go on repeating that shortened (or
 *     lengthened) first hold, so the carried entry is written again with the full hold when
 *     the drop is over: a RE-ARM ({@link Plan#rearmInMs}). It restarts the upper phase of the
 *     next cycle a fraction of a second after it began - never a hold already under way -
 *     and the engine waits on the pressure while the pump is still seen dropping.
 *
 * DURING THE DROP there is no hold left to carry on, and starting an entry would cut the drop
 * short (a started entry always begins by pulling). So the change WAITS for the drop to end
 * ({@link #AFTER_DROP}, {@link Plan#waitMs}) and then goes in as the next hold. On a one-cycle
 * step the drop is the step's last part: nothing is changed, and that is said.
 *
 * THE TIME. Elapsed stays real. The step's end moves by exactly what the change adds: the
 * cycle under way ends (what is left of it + the change), and on a block the sets after it
 * each by the change in the cycle ({@link Plan#endMoveMs}). A continuous hold (no drop: no
 * sets to cut) keeps its end. A ramp's step that holds several cycles is planned here with its
 * end kept, and the engine then moves it to the cycle end nearest it ({@link Plan#rampWhole},
 * RunEdit#rampEndMoveMs, 0.10 follow-up): kept, it ended inside a hold.
 */
public final class HoldCarryOn {
    private HoldCarryOn() { }

    /** The hold under way is never left with less than this: "never less than a few seconds". */
    public static final long MIN_LEFT_MS = 3000L;
    /** The re-arm waits this long past the modelled end of the drop, so it lands at the start
     *  of the next pull - never inside the drop. */
    public static final long REARM_MARGIN_MS = 400L;
    /** The pressure may show the drop this much before the app's count says it is due (the
     *  pump's clock and the app's count differ by the moment the entry was really started):
     *  within it, the drop seen is believed; further out, it is not (a stuck reading must not
     *  hold a change back for a whole cycle). */
    public static final long EARLY_DROP_MS = 5000L;

    /** Carry the hold under way on to its new length. */
    public static final int CONTINUE = 1;
    /** The pump is in its drop: the change waits for the drop to end. */
    public static final int AFTER_DROP = 2;

    /** What to do with one live change. */
    public static final class Plan {
        public int kind;
        /** CONTINUE: the hold written for the cycle under way, whole seconds. */
        public int wireUh;
        /** CONTINUE: how far the step's end moves (ms, + later). */
        public long endMoveMs;
        /** CONTINUE: when the full hold must be written again, from the send (ms), or −1. */
        public long rearmInMs = -1L;
        /** CONTINUE: how long from the send until the cycle under way ends (ms). */
        public long cycleEndsInMs;
        /** AFTER_DROP: how long until the drop is over, plus the margin (ms). */
        public long waitMs;
        /** The step is one cycle: its end is this cycle's end. */
        public boolean oneCycle;
        /** The sets after the one under way take the new cycle too. */
        public boolean laterToo;
        /** A ramp's step of several cycles: its end moves to the cycle end nearest the end it
         *  had ({@link #endMoveMs}, RunEdit#rampEndMoveMs), set by the engine. */
        public boolean rampWhole;
    }

    /**
     * @param oldUh     the hold in force (s)
     * @param oldLh     the drop time in force (s)
     * @param newUh     the hold asked (s)
     * @param newLh     the drop time asked (s)
     * @param inCycleMs how far the pump is into its cycle (upper phase first), from the app's
     *                  count of it
     * @param seenDrop  the pressure shows the pump letting go (PhaseTracker DROP)
     * @param oneCycle  the step is exactly one cycle
     * @param setsLeft  the block's sets still to run, the one under way included (1 when the
     *                  step is one cycle or not counted)
     * @param keepEnd   the step keeps its end (a ramp's step of several cycles, a continuous hold)
     */
    public static Plan plan(int oldUh, int oldLh, int newUh, int newLh, long inCycleMs,
                            boolean seenDrop, boolean oneCycle, int setsLeft, boolean keepEnd) {
        Plan p = new Plan();
        p.oneCycle = oneCycle;
        long oldHold = Math.max(0, oldUh) * 1000L, oldCycle = oldHold + Math.max(0, oldLh) * 1000L;
        long in = Math.max(0L, inCycleMs);
        boolean drops = oldLh > 0;
        if (drops && (in >= oldHold || (seenDrop && in >= oldHold - EARLY_DROP_MS))) {
            p.kind = AFTER_DROP;
            long left = oldCycle - in;
            p.waitMs = Math.max(0L, left) + REARM_MARGIN_MS;
            return p;
        }
        p.kind = CONTINUE;
        // What has run of the hold under way - never more than the hold itself.
        long held = Math.min(in, oldHold);
        long leftOfNew = Math.max(MIN_LEFT_MS, Math.max(0, newUh) * 1000L - held);
        p.wireUh = (int) Math.min(Proto.WIRE_HOLD_MAX, (leftOfNew + 999L) / 1000L);
        long newCycle = (Math.max(0, newUh) + Math.max(0, newLh)) * 1000L;
        p.cycleEndsInMs = p.wireUh * 1000L + Math.max(0, newLh) * 1000L;
        long wasLeft = Math.max(0L, oldCycle - in);
        int later = Math.max(0, setsLeft - 1);
        p.laterToo = !oneCycle && later > 0;
        p.endMoveMs = keepEnd ? 0L
            : (p.cycleEndsInMs - wasLeft) + (p.laterToo ? later * (newCycle - oldCycle) : 0L);
        // A block the pump repeats: the full hold again once this cycle's drop is over.
        if (!oneCycle && newLh > 0 && p.wireUh != newUh)
            p.rearmInMs = p.cycleEndsInMs + REARM_MARGIN_MS;
        return p;
    }

    /** What "+30 s hold" (or `added` seconds of it) says as it goes. */
    public static String plusHoldSaid(int added, boolean inDrop, boolean laterToo, boolean oneCycle) {
        String amt = added >= 60 ? Model.Fmt.t(added) : added + " s";
        if (inDrop)
            return oneCycle ? "This step's hold is over — its drop is under way. Nothing was changed."
                            : "The drop is under way: the next hold is " + amt + " longer"
                              + (laterToo ? ", and each one after it." : ".");
        return "This hold runs " + amt + " longer, from where it is"
            + (laterToo ? ", and so does each one after it." : ".");
    }

    /**
     * RESUME AFTER A PAUSE (enterHold kept the pressure; the countdown waited). The pump held
     * through the pause, so the hold that was under way CARRIES ON with what was left of it -
     * the same plan as a change that changes nothing, from where the pause came - and the end
     * moves by the paused time only (the countdown's own wait). A pause in the DROP: the hold
     * was run, so the next hold starts as normal (AFTER_DROP).
     */
    public static Plan resume(int uh, int lh, long pausedInCycleMs, boolean oneCycle, int setsLeft,
                              boolean keepEnd) {
        return plan(uh, lh, uh, lh, pausedInCycleMs, false, oneCycle, setsLeft, keepEnd);
    }

    /** What Resume says. */
    public static String resumeSaid(Plan p) {
        if (p == null) return null;
        if (p.kind == CONTINUE)
            return "Resumed — the hold carries on from where it was: " + Model.Fmt.t(p.wireUh)
                + " left.";
        return p.oneCycle ? "Resumed — this step's hold was done; the next step follows."
                          : "Resumed — the drop was under way: the next hold starts now.";
    }

    /** What a Revert says: it puts the plan's own figures back, and the protocol can only
     *  START them - the one change that starts the set again from its hold. */
    public static final String REVERT_SAID =
        " — the pump starts this set again from its hold (a Revert is a restart).";

    /** A one-cycle step in its drop: why a change of it is refused. */
    public static final String ONE_CYCLE_DROP =
        "This step's hold is over — its drop is under way. Nothing was changed.";

    /** What an acknowledged carry-on says about the time. */
    public static String endSaid(Plan p, long stepMs, boolean warm) {
        if (p == null || p.kind != CONTINUE || p.endMoveMs == 0) return null;
        long mv = Math.abs(p.endMoveMs);
        String by = Model.Fmt.t((mv + 500L) / 1000L);
        if (p.oneCycle)
            return (warm ? "Warm-up step is now " : "Step is now ")
                + Model.Fmt.t((stepMs + 500L) / 1000L) + " — the hold carries on from where it was.";
        return "This block now ends " + by + (p.endMoveMs > 0 ? " later" : " sooner")
            + " — the hold under way carries on from where it was.";
    }
}
