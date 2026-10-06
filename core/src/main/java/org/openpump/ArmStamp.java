package org.openpump;

/**
 * WHAT A DELAYED START WAS MADE FOR - AND WHETHER THAT STILL HOLDS WHEN IT FIRES.
 *
 * A START that has to wait - behind a table rewrite (UPLOAD_SETTLE_MS), or for a dial to settle
 * - is posted, and fires later into a run that may have moved on. It used to ask only "is the
 * run live, and is planIdx a step of the plan". The safety review of refusal-2 (CRITICAL):
 * step N had been pushed out of the table; a tap about 1.2 s before the step ended lost its
 * `01`; the convergence rewrote the table and posted its START 600 ms later; the step's Advance
 * fired first; step N+1 was a rest, so the StopWork and the vent watch went out; then the
 * posted START fired - the run was live and N+1 was a step - and started slot 0, which was step
 * N, and sendStartSlot cancelled the rest's vent watch. The pump pulled step N's pressure
 * through the whole rest while the screen said REST, and nothing watched it.
 *
 * So every delayed START carries this, made when it was: the step it is for; the run's PHASE
 * then (a counter the run moves on at every step, every hold written, confirmed or released,
 * every rest, the run's end); the TABLE it names a slot of (a counter moved on at every table
 * written - or -1 for a START that names no slot of it, as a dialled change's settle, which
 * writes its own entry); and whether it is a Hold's own - one that takes the pump off a Hold
 * that may be up. When it fires it asks #stale, and does NOTHING unless every one still holds:
 * the run live, the same step, no rest, no Hold that may be up unless it is the Hold's own, the
 * same phase, the same table. WiringCheck invariant 148 pins that every posted START asks.
 * DelayedStartTest.
 */
public final class ArmStamp {

    public final int forIdx;
    public final int phase;
    public final int table;
    public final boolean forHold;

    /** @param forIdx the step it is made for (planIdx now);
     *  @param phase the run's phase now;
     *  @param table the table it names a slot of, or -1 when it names none;
     *  @param forHold it takes the pump off a Hold that may be up (a release, or a convergence
     *         written for one). */
    public ArmStamp(int forIdx, int phase, int table, boolean forHold) {
        this.forIdx = forIdx; this.phase = phase; this.table = table; this.forHold = forHold;
    }

    /**
     * Why the START this stands for must NOT be written now - or null when it still may be.
     *
     * @param live the run can still be commanded at planIdx (RunEdit#mayRearmRunningPreset);
     * @param planIdx the step playing now;
     * @param resting a rest is up - the plan's own or one the person inserted;
     * @param holdMayBeUp a Hold may be up on the pump (held, written and not ruled out, or
     *        released and the pump not yet known to be off it);
     * @param phase the run's phase now;
     * @param table the table written last.
     */
    public String stale(boolean live, int planIdx, boolean resting, boolean holdMayBeUp,
                        int phase, int table) {
        if (!live) return "the run can no longer be commanded";
        if (planIdx != forIdx)
            return "it was made for step " + (forIdx + 1) + " and step " + (planIdx + 1)
                + " is playing";
        if (resting) return "a rest is up";
        if (holdMayBeUp && !forHold) return "a hold may be up";
        if (phase != this.phase) return "the run has moved on since it was made";
        if (this.table >= 0 && table != this.table)
            return "the table was written again since - its slot is not the one it named";
        return null;
    }
}
