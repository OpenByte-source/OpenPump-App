package org.openpump;

/**
 * THE SESSION'S PEAK IS THE ROUTINE'S OWN (the live GrowthTrack test, 9 Nov 2026).
 *
 * Before a routine starts, the app pulls the cuff to the start check's pressure ("Ready when
 * you are · Pull to -5.0 inHg") and the routine starts once it has held there for two seconds.
 * Nothing vents in between: the routine's first step starts with that pull still on the cuff,
 * and the pump does not release it until that step's first drop. So every session asked for
 * less than the start check recorded the start check's pressure as its peak - a -3.0 inHg
 * routine and a -1.8 inHg length session both filed 17.0 kPa (5.02 inHg), and that was the
 * summary's "Peak reached" and GrowthTrack's pressure_peak_inhg.
 *
 * So the peak is measured only from the routine's own steps:
 *  - nothing while a start check runs (a seal check or the guided pull, before the routine or
 *    in the middle of it after a step done by hand) - {@link #carriedIn};
 *  - from a step's start, the pressure still on the cuff from that check is not the step's:
 *    until the cuff has come down to what the step asks for - the step's first release takes
 *    it there at the latest, its lower pressure being under its upper one - a reading counts
 *    only up to what the step asked for: the cuff was at least there, and nothing above it
 *    was the routine's;
 *  - from then on, every real reading counts as it always did (an overshoot still shows).
 *
 * A reading of 0.0 is no measurement and never a peak (Proto.Sample). Bookkeeping only:
 * nothing here reaches the pump. The start check's own readings stay what they were wherever
 * they are shown or used (its verdict, the trace, the dose).
 */
public final class RunPeak {
    private boolean ownPressure;
    private boolean saw;
    private double peak;

    /** A new run: nothing measured, and whatever is on the cuff is not yet the routine's. */
    public void reset() {
        ownPressure = false;
        saw = false;
        peak = 0.0;
    }

    /** A start check is running (or has just put its pull on the cuff): its readings are not
     *  the routine's, and neither is the pressure it leaves for the step after it. */
    public void carriedIn() {
        ownPressure = false;
    }

    /** One reading while a routine step plays; `stepKpa` is what the step asks the pump for
     *  (after the ceiling and any live change). */
    public void note(double kpa, boolean noReading, double stepKpa) {
        if (noReading || Double.isNaN(kpa)) return;
        if (!ownPressure && kpa <= stepKpa) ownPressure = true;
        double counted = kpa;
        if (!ownPressure) {
            if (stepKpa <= 0.0) return;          // a step that asks for nothing has no peak
            counted = Math.min(kpa, stepKpa);
        }
        if (!saw || counted > peak) { peak = counted; saw = true; }
    }

    /** The peak, or null when no reading of the routine's own counted. */
    public Double peakKpa() {
        return saw ? Double.valueOf(peak) : null;
    }
}
