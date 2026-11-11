package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * THE PEAK IS THE ROUTINE'S, NOT THE START CHECK'S (the live GrowthTrack test, 9 Nov 2026,
 * findings 3 and 10).
 *
 * Every session in the live test asked for less than -5.0 inHg filed a peak of 17.0 kPa
 * (5.02 inHg): the start check's pull ("Pull to -5.0 inHg"), still on the cuff when the first
 * step began and held through that step's first upper hold. Starter Routine (-3.0 inHg) and the
 * length session (-1.8 inHg) both sent pressure_peak_inhg 5.02.
 *
 * The frames below follow the simulated pump of that run (SimPump): the guided pull holds
 * 17.0 kPa, the first step's START does not lower it, the hold coasts, the drop takes the cuff
 * down to the step's lower pressure and the next pull goes only to the step's own.
 */
class RoutinePeakTest {
    private static final long GAP = 2_000L;

    /** One frame, fed exactly as SessionActivity#onSample feeds it. */
    private static void frame(Session s, long t, double kpa, double stepKpa) {
        s.noteSample(t, kpa, false, GAP);
        s.noteRoutinePeak(kpa, false, stepKpa);
    }

    @Test void aMinusThreeRoutineAfterTheStartCheckPeaksAtItsOwnPressure() {
        Session s = new Session();
        s.beginRun(0L);
        double step = 10.0;                              // -3.0 inHg, the routine's own top
        long t = 0;
        for (int i = 0; i < 120; i++) frame(s, t += 250, 17.0 - 0.0035 * i, step);   // the first hold, coasting
        for (double k = 16.5; k >= 3.4; k -= 1.2) frame(s, t += 250, k, step);       // the first drop
        for (double k = 3.4; k <= 10.2; k += 0.8) frame(s, t += 250, k, step);       // its own pull
        for (int i = 0; i < 120; i++) frame(s, t += 250, 10.2, step);                // its own hold
        assertEquals(17.0, s.observedPeakKpa(), 1e-9, "every reading the run saw, as before");
        assertNotNull(s.routinePeakKpa());
        assertEquals(10.2, s.routinePeakKpa(), 1e-9,
            "the routine's peak is its own pull, not the -5.0 inHg left on the cuff by the start check");
    }

    @Test void stoppedDuringThePullItInheritedTheStepStillReachedWhatItAskedFor() {
        Session s = new Session();
        s.beginRun(0L);
        for (int i = 0; i < 40; i++) frame(s, 250L * i, 17.0, 10.0);
        assertEquals(10.0, s.routinePeakKpa(), 1e-9,
            "the cuff was at least at the step's pressure; nothing above it was the routine's");
    }

    @Test void aStepAskingForMoreThanTheStartCheckCountsFromItsFirstReading() {
        Session s = new Session();
        s.beginRun(0L);
        long t = 0;
        frame(s, t += 250, 17.0, 28.0);                  // a manual run at -8.3 inHg
        for (double k = 17.0; k <= 28.3; k += 0.7) frame(s, t += 250, k, 28.0);
        assertEquals(s.observedPeakKpa(), s.routinePeakKpa(), 1e-9, "its own pull, overshoot included");
    }

    @Test void aStartCheckInTheMiddleOfTheRunIsNotTheRoutinesPeak() {
        Session s = new Session();
        s.beginRun(0L);
        long t = 0;
        // A step done by hand, the pump vented.
        for (int i = 0; i < 20; i++) frame(s, t += 250, 0.3, 0.0);
        // The start check after it, pulling to -5.0 inHg: not the routine's.
        for (int i = 0; i < 20; i++) { s.noteSample(t += 250, 17.0, false, GAP); s.peakCarriedIn(); }
        // The length step, -1.8 inHg, started with the pull still on the cuff.
        for (int i = 0; i < 20; i++) frame(s, t += 250, 17.0, 6.0);
        for (double k = 16.0; k >= 3.0; k -= 1.0) frame(s, t += 250, k, 6.0);
        for (int i = 0; i < 20; i++) frame(s, t += 250, 6.1, 6.0);
        assertEquals(17.0, s.observedPeakKpa(), 1e-9);
        assertEquals(6.1, s.routinePeakKpa(), 1e-9, "the length session's own pressure");
    }

    @Test void noReadingAndNoRunCountNothing() {
        Session s = new Session();
        s.noteRoutinePeak(20.0, false, 20.0);            // before any run
        s.beginRun(0L);
        s.noteRoutinePeak(0.0, true, 20.0);              // 0.0 is no measurement
        assertNull(s.routinePeakKpa());
        s.noteRoutinePeak(12.0, false, 20.0);
        assertEquals(12.0, s.routinePeakKpa(), 1e-9);
        s.beginRun(1_000L);
        assertNull(s.routinePeakKpa(), "a new run never inherits the last one's peak");
    }
}
