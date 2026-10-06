package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * THE PHASE COMES FROM THE PRESSURE, NOT FROM A CLOCK.
 *
 * Each case drives a synthetic pump trace through the tracker at the hardware's ~4 Hz and
 * checks the phase it reports against the phase the trace was built from. The traces model
 * what the hardware logs show: a speed-dependent pull that may stall short of the target
 * when the seal gives out, a coast of about 4 kPa over a long hold, a fast drop, a dwell,
 * and telemetry jitter.
 */
class PhaseTrackerTest {

    /** What the trace generator was doing at each sample. */
    enum Truth { PULLING, HOLD, DROP }

    static final class Sample { long t; double kpa; Truth truth; }

    /**
     * One pump, cycling: pull at pullRate (kPa/s) up to min(up, sealMax), hold holdS seconds
     * (coasting), drop at 5 kPa/s to lo, dwell dropS seconds, repeat. The hold timer starts
     * when the pull ends, as the pressure shows it on hardware.
     */
    static List<Sample> trace(int up, int lo, int holdS, int dropS, double pullRate,
                              double sealMax, double seconds, boolean dropsAtAll) {
        List<Sample> out = new ArrayList<>();
        double kpa = 0, dt = 0.24;               // ~4.16 Hz
        Truth st = Truth.PULLING; double inPhase = 0;
        double top = Math.min(up, sealMax);
        java.util.Random jitter = new java.util.Random(7);
        for (double t = 0; t < seconds; t += dt) {
            inPhase += dt;
            switch (st) {
                case PULLING:
                    kpa = Math.min(top, kpa + pullRate * dt);
                    if (kpa >= top - 0.01) { st = Truth.HOLD; inPhase = 0; }
                    break;
                case HOLD:
                    kpa = Math.max(0, kpa - 0.03 * dt);           // the coast
                    if (dropsAtAll && inPhase >= holdS) { st = Truth.DROP; inPhase = 0; }
                    break;
                case DROP:
                    kpa = Math.max(lo, kpa - 5.0 * dt);
                    if (inPhase >= dropS + (top - lo) / 5.0) { st = Truth.PULLING; inPhase = 0; }
                    break;
            }
            Sample s = new Sample();
            s.t = Math.round(t * 1000);
            s.kpa = Math.max(0.1, kpa + (jitter.nextDouble() - 0.5) * 0.2);   // +-0.1 kPa
            s.truth = st;
            out.add(s);
        }
        return out;
    }

    /** Fraction of samples, after a short settling allowance per transition, where tracker == truth. */
    static double agreement(PhaseTracker tr, List<Sample> tr_, long graceMs) {
        long lastChange = 0; Truth prev = null; int ok = 0, counted = 0;
        for (Sample s : tr_) {
            tr.sample(s.t, s.kpa);
            if (s.truth != prev) { lastChange = s.t; prev = s.truth; }
            if (s.t - lastChange < graceMs) continue;
            counted++;
            if (tr.phase().name().equals(s.truth.name())) ok++;
        }
        return counted == 0 ? 1 : (double) ok / counted;
    }

    @Test
    void anOrdinaryCycleIsFollowedPhaseForPhase() {
        PhaseTracker tr = new PhaseTracker();
        tr.start(0, 31, 5, true);
        List<Sample> t = trace(31, 5, 20, 5, 3.0, 99, 180, true);
        assertTrue(agreement(tr, t, 2500) > 0.97, "the tracker follows pull, hold and drop");
    }

    @Test
    void aSealThatStallsShortIsAHoldShortOfTheTargetAndNeverADrop() {
        // The device report: the target was -9.2 inHg (31 kPa), the seal topped out near 28.8,
        // the pump never let go, and the screen said DROP. It must say HOLD, short of target.
        PhaseTracker tr = new PhaseTracker();
        tr.start(0, 31, 5, true);
        List<Sample> t = trace(31, 5, 20, 5, 3.0, 28.8, 120, false);
        for (Sample s : t) tr.sample(s.t, s.kpa);
        assertEquals(PhaseTracker.Phase.HOLD, tr.phase());
        assertTrue(tr.shortOfTarget(), "and it says it is short");
        assertTrue(tr.line(t.get(t.size() - 1).t, 20, 5, "-9.2 inHg").contains("short of -9.2 inHg"));
    }

    @Test
    void aLongHoldsCoastIsNotADrop() {
        PhaseTracker tr = new PhaseTracker();
        tr.start(0, 20, 17, true);                       // a narrow band makes this the hard case
        List<Sample> t = trace(20, 17, 150, 5, 3.0, 99, 140, true);
        for (Sample s : t) tr.sample(s.t, s.kpa);
        assertEquals(PhaseTracker.Phase.HOLD, tr.phase(), "4 kPa of coast over minutes is still a hold");
    }

    @Test
    void aNarrowBandsRealDropIsStillSeen() {
        PhaseTracker tr = new PhaseTracker();
        tr.start(0, 20, 17, true);
        List<Sample> t = trace(20, 17, 15, 6, 3.0, 99, 90, true);
        assertTrue(agreement(tr, t, 2500) > 0.95);
    }

    @Test
    void aHoldOnlyPresetNeverClaimsADrop() {
        PhaseTracker tr = new PhaseTracker();
        tr.start(0, 31, 30, false);
        // Even a fast fall (a leak, a lifted cylinder) is not the pump's drop on a hold-only preset.
        long t = 0;
        for (int i = 0; i < 60; i++) { tr.sample(t, Math.min(31, i * 1.0)); t += 240; }
        for (int i = 0; i < 20; i++) { tr.sample(t, 31 - i * 1.2); t += 240; }
        assertFalse(tr.phase() == PhaseTracker.Phase.DROP);
    }

    @Test
    void aSlowPumpSpeedStillComesBackFromTheDrop() {
        PhaseTracker tr = new PhaseTracker();
        tr.start(0, 31, 5, true);
        List<Sample> t = trace(31, 5, 20, 5, 0.32, 99, 400, true);   // 10 % speed
        assertTrue(agreement(tr, t, 4000) > 0.95);
    }

    @Test
    void noMeasurementIsIgnored() {
        PhaseTracker tr = new PhaseTracker();
        tr.start(0, 31, 5, true);
        for (int i = 0; i < 40; i++) tr.sample(i * 240L, i * 0.9);
        PhaseTracker.Phase before = tr.phase();
        tr.sample(10_000, 0.0);                          // 0 is "no measurement", not ambient
        tr.sample(10_240, Double.NaN);
        assertEquals(before, tr.phase());
    }

    @Test
    void aRestartBeginsAgainFromThePull() {
        PhaseTracker tr = new PhaseTracker();
        tr.start(0, 31, 5, true);
        for (int i = 0; i < 80; i++) tr.sample(i * 240L, Math.min(31, i * 1.0));
        assertEquals(PhaseTracker.Phase.HOLD, tr.phase());
        tr.start(30_000, 25, 5, true);                   // a skip or a re-armed live change
        assertEquals(PhaseTracker.Phase.PULLING, tr.phase());
        assertEquals(0, tr.phaseElapsedMs(30_000));
    }
    /* ---- PAST THE PLAN, SAID PLAINLY ----------------------------------------------------
     * "HOLD · 3:02 of 2:00" read as a slip. A pump short of target can genuinely hold too
     * long on real hardware, so the overrun stays - worded as what it is. */

    @Test void aHoldPastItsPlanSaysByHowMuch() {
        PhaseTracker t = new PhaseTracker();
        t.start(0, 17, 5, true);
        for (long ms = 0; ms <= 182_000; ms += 240) t.sample(ms, 17.0);
        assertEquals("HOLD · 3:02 · 1:02 past 2:00", t.line(182_000, 120, 5, "5.0 inHg"));
    }

    @Test void aHoldWithinItsPlanReadsAsBefore() {
        PhaseTracker t = new PhaseTracker();
        t.start(0, 17, 5, true);
        for (long ms = 0; ms <= 60_000; ms += 240) t.sample(ms, 17.0);
        assertEquals("HOLD · 1:00 of 2:00", t.line(60_000, 120, 5, "5.0 inHg"));
        for (long ms = 60_240; ms <= 120_000; ms += 240) t.sample(ms, 17.0);
        assertEquals("HOLD · 2:00 of 2:00", t.line(120_000, 120, 5, "5.0 inHg"),
            "exactly at the plan is not past it");
    }

    @Test void aDropPastItsPlanSaysByHowMuch() {
        PhaseTracker t = new PhaseTracker();
        t.start(0, 17, 5, true);
        long ms = 0;
        for (; ms <= 20_000; ms += 240) t.sample(ms, 17.0);
        // Fall fast to the low setpoint and sit there.
        double k = 17.0;
        for (; k > 5.0; ms += 240) { k = Math.max(5.0, k - 1.2); t.sample(ms, k); }
        long dropAt = ms;
        for (; ms <= dropAt + 8_000; ms += 240) t.sample(ms, 5.0);
        String line = t.line(ms - 240, 120, 5, "5.0 inHg");
        assertTrue(line.startsWith("DROP · "), line);
        assertTrue(line.endsWith(" past 0:05"), line);
        assertFalse(line.contains(" of "), line);
    }
}
