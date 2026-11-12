package org.openpump;
/** Narrow ZD21 adapter. No arbitrary patterns or inferred hardware capabilities. */
public final class GrowthTrackStaticRun {
    private GrowthTrackStaticRun() { }
    public static Model.Set prepare(GrowthTrackRoutine r,int ceilingKpa) {
        if(r.steps.size()!=1) throw new IllegalArgumentException("This device build can run only a single static step");
        GrowthTrackRoutine.Step s=r.steps.get(0);
        if(s.confirmation||!"static_pumping".equals(s.method)||s.phases.size()!=1)
            throw new IllegalArgumentException("This device build supports static pumping only");
        GrowthTrackRoutine.Phase p=s.phases.get(0);
        if(p.reachSeconds!=null||p.tensionLbs!=0||s.seconds<121||s.seconds>255||s.seconds!=Math.rint(s.seconds))
            throw new IllegalArgumentException("Use a 121–255 second static step, maximum speed, without tension");
        int pressure=(int)Math.floor(Math.min(p.pressureInhg*3.38639,Math.min(57,ceilingKpa)));
        if(pressure<1) throw new IllegalArgumentException("Pressure is below this device's supported resolution");
        return Model.Set.fixed(Manual.ID,r.name,pressure,0,(int)s.seconds,0,100,(int)s.seconds);
    }
}
