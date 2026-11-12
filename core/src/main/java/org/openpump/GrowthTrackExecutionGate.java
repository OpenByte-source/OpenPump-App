package org.openpump;

/** Device capability preflight, run before any pump-side effect or table upload. */
public final class GrowthTrackExecutionGate {
    private GrowthTrackExecutionGate() { }
    public static void requireSupported(GrowthTrackRoutine routine, boolean continuityVerified,
            boolean timedRampSupported, int minimumPhaseSeconds, boolean tensionSupported) {
        for(int i=0;i<routine.steps.size();i++) {
            GrowthTrackRoutine.Step s=routine.steps.get(i);
            if(!continuityVerified && (s.confirmation || (i<routine.steps.size()-1 && s.restSeconds==0)))
                throw new IllegalArgumentException("Device pressure continuity is not verified");
            if(s.confirmation) continue;
            if(!continuityVerified && s.phases.size()>1)
                throw new IllegalArgumentException("Device pattern transitions are not verified");
            for(GrowthTrackRoutine.Phase p:s.phases) {
                if(p.seconds<minimumPhaseSeconds)
                    throw new IllegalArgumentException("Phase is shorter than supported device timing");
                if(p.reachSeconds!=null&&!timedRampSupported)
                    throw new IllegalArgumentException("Device does not support timed pressure targets");
                if(p.tensionLbs>0&&!tensionSupported)
                    throw new IllegalArgumentException("Device cannot apply tension");
            }
        }
    }
}
