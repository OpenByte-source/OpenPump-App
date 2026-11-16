package org.openpump;

/** Monotonic pattern playhead. The host owns all I/O, ceiling checks and vent confirmation.
 * A target is emitted only after explicit start/advance acknowledgement; never auto-resume. */
public final class GrowthTrackExecution {
    public enum State { READY, RUNNING, CONFIRM, RESTING, RELEASE_REQUIRED, STOPPED }
    public final GrowthTrackRoutine routine;
    private State state=State.READY;
    private int step, phase;
    private long phaseAt, workAt, confirmAt;
    private boolean timeoutReleased;
    private long restUntil;
    private double pendingRest;
    private boolean ending;
    public GrowthTrackExecution(GrowthTrackRoutine routine) { this.routine=routine; }
    public State state() { return state; }
    public int stepIndex() { return step; }
    public GrowthTrackRoutine.Phase target() {
        return state==State.RUNNING?routine.steps.get(step).phases.get(phase):null;
    }
    public void start(long now, boolean safeToStart) {
        if(state!=State.READY||!safeToStart) throw new IllegalStateException("Start gate is closed");
        enter(now);
    }
    private void enter(long now) {
        if(step>=routine.steps.size()) {ending=true;state=State.RELEASE_REQUIRED; return;}
        GrowthTrackRoutine.Step s=routine.steps.get(step);
        phase=0; phaseAt=workAt=now;
        if(s.confirmation) {state=State.CONFIRM;confirmAt=now;timeoutReleased=false;}
        else state=State.RUNNING;
    }
    /** Delayed ticks never skip unobserved physical phases. Host must acknowledge every change. */
    public boolean phaseDue(long now) {
        if(state!=State.RUNNING) return false;
        GrowthTrackRoutine.Step s=routine.steps.get(step);
        return now-workAt>=s.seconds*1000 || now-phaseAt>=s.phases.get(phase).seconds*1000;
    }
    public void advanceAcknowledged(long now) {
        if(!phaseDue(now)) throw new IllegalStateException("Phase not due");
        GrowthTrackRoutine.Step s=routine.steps.get(step);
        if(now-workAt>=s.seconds*1000) {
            step++; pendingRest=s.restSeconds; ending=step>=routine.steps.size();
            if(s.restSeconds>0||step>=routine.steps.size()) state=State.RELEASE_REQUIRED;
            else enter(now);
        } else { phase=(phase+1)%s.phases.size();phaseAt=now; }
    }
    public boolean confirmationNeedsRelease(long now) {
        return state==State.CONFIRM&&!timeoutReleased&&now-confirmAt>=600000;
    }
    public void confirmationReleaseVerified() {
        if(state!=State.CONFIRM) throw new IllegalStateException("Not awaiting confirmation");
        timeoutReleased=true;
    }
    public void confirm(long now,boolean safeToStart) {
        if(state!=State.CONFIRM||!safeToStart) throw new IllegalStateException("Confirmation gate closed");
        step++;enter(now);
    }
    public void stop() {ending=true; state=State.RELEASE_REQUIRED;}
    /** A timed rest starts only after the release is evidenced, never at the Stop write. */
    public void releaseVerified(long now) {
        if(state!=State.RELEASE_REQUIRED) throw new IllegalStateException("No pending release");
        if(ending) {state=State.STOPPED;return;}
        restUntil=now+(long)(pendingRest*1000);state=State.RESTING;
    }
    public boolean restDue(long now) {return state==State.RESTING&&now>=restUntil;}
    public void resumeAfterRest(long now,boolean safeToStart) {
        if(!restDue(now)||!safeToStart) throw new IllegalStateException("Rest/start gate closed");
        enter(now);
    }
    public void stopVerified() {
        if(state!=State.RELEASE_REQUIRED) throw new IllegalStateException("No pending release");
        state=State.STOPPED;
    }
}
