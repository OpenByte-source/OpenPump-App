package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * THE TISSUE ADAPTATION ASSESSMENT HAS TO BE ABLE TO START ON A VENTED PUMP.
 *
 * The owner's first real-hardware session (0.1.0, Epic Hydro PE Pump) measured the BEFORE pull and
 * then filed the AFTER one as {@link Tau#WHY_NO_START}: the vent before it never produced
 * a starting point. The same session ended "No pressure detected" — the pump streaming
 * 0.0, NO MEASUREMENT, on a live link — which is how this pump reports an open cuff. The
 * simulator does exactly the same (it vents to nothing and then reports 0), and on it
 * NEITHER pull ever started (B4).
 *
 * The arming rule only accepted a SETTLED REAL reading at or below
 * {@link Tau#ARM_BELOW_KPA}. A pump that stops reporting once it is open can only ever
 * offer one by luck, in the last second of the vent's tail, so the pull started or not
 * depending on where the frames fell — and never at all when the cuff was already open.
 *
 * These drive the arming decision ({@link Tau#armStartKpa}) and the pull
 * ({@link Tau#compute}) through a {@link SimPump} the way SessionActivity does: its own
 * StopWork, the vent watch's verdict (Session#ventResult), the 20 s vent window, then the
 * assessment preset.
 */
class AssessArmTest {

    /** SessionActivity's own numbers — Android code, so copied here under their names. */
    static final long ASSESS_VENT_WINDOW_MS = 20000L;   // SessionActivity.ASSESS_VENT_WINDOW_MS
    static final long VENT_WATCH_WINDOW_MS = 6000L;     // SessionActivity.VENT_WATCH_WINDOW_MS
    static final int ASSESS_HOLD_GUARD_S = 30;          // SessionActivity.ASSESS_HOLD_GUARD_S

    /** A simulated pump and everything it has said, on its own clock. */
    static final class Rig {
        final SimPump pump = new SimPump();
        long now = 0L;
        final List<Long> ts = new ArrayList<>();
        final List<Double> kpa = new ArrayList<>();
        final List<Boolean> nr = new ArrayList<>();
        double lastRealEver = Double.NaN;

        void tick() {
            pump.tick(SimPump.TELEM_PERIOD_MS);
            now += SimPump.TELEM_PERIOD_MS;
            for (Proto.Sample s : pump.drainSamples()) {
                ts.add(now); kpa.add(s.kpa); nr.add(s.noReading);
                if (!s.noReading) lastRealEver = s.kpa;
            }
        }

        void run(long ms) { long end = now + ms; while (now < end) tick(); }

        /** Session#freshBaselineKpa at this instant: the last frame, if it was real. */
        double freshBaseline() {
            int n = kpa.size();
            return (n == 0 || nr.get(n - 1)) ? Double.NaN : kpa.get(n - 1);
        }

        void writeTable(int sp, int up, int uh, int lo, int lh) {
            for (int i = 0; i < Proto.SLOTS; i++) pump.write(Proto.deleteSlot(0));
            pump.write(Proto.addPreset(sp, up, uh, lo, lh));
            pump.write(Proto.startSlot(0));
        }
    }

    static long[] longs(List<Long> l, int from) {
        long[] a = new long[l.size() - from];
        for (int i = 0; i < a.length; i++) a[i] = l.get(from + i);
        return a;
    }
    static double[] doubles(List<Double> l, int from) {
        double[] a = new double[l.size() - from];
        for (int i = 0; i < a.length; i++) a[i] = l.get(from + i);
        return a;
    }
    static boolean[] bools(List<Boolean> l, int from) {
        boolean[] a = new boolean[l.size() - from];
        for (int i = 0; i < a.length; i++) a[i] = l.get(from + i);
        return a;
    }

    /**
     * beginAssessment + VentWatcher + AssessVentTick: the app's own StopWork, the watch's
     * verdict on every frame (latched at the first answer, as the watcher latches), and the
     * arming decision on every frame, for the 20 s the app gives it. Returns the pressure
     * the pull is armed from, or NaN when the window ran out — which is the WHY_NO_START
     * the owner's session filed.
     */
    static double ventAndArm(Rig rig) {
        double pre = rig.freshBaseline();
        rig.pump.write(Proto.stop());
        long stopAt = rig.now;
        int from = rig.ts.size();
        String verdict = Session.VENT_STATE_SENT_UNVERIFIED;
        while (rig.now - stopAt < ASSESS_VENT_WINDOW_MS) {
            rig.tick();
            long[] ts = longs(rig.ts, from);
            double[] k = doubles(rig.kpa, from);
            boolean[] n = bools(rig.nr, from);
            if (Session.VENT_STATE_SENT_UNVERIFIED.equals(verdict)) {
                long[] el = new long[ts.length];
                for (int i = 0; i < ts.length; i++) el[i] = ts[i] - stopAt;
                verdict = Session.ventResult(true, true, pre, 0.0, SimPump.VENT_KPA_PER_S,
                        k, n, el, rig.now - stopAt, VENT_WATCH_WINDOW_MS, rig.lastRealEver);
            }
            double start = Tau.armStartKpa(ts, k, n, rig.now,
                    Session.ventedForGating(verdict), rig.lastRealEver);
            if (!Double.isNaN(start)) return start;
        }
        return Double.NaN;
    }

    /** armAssessmentPull + finishAssessment: the assessment preset, the window, Tau. */
    static Tau.Result pull(Rig rig, double startKpa, Model.Assess a) {
        rig.writeTable(a.sp, a.kpa, Math.min(255, a.dur + ASSESS_HOLD_GUARD_S), a.kpa, 1);
        long commandedAt = rig.now;
        int from = rig.ts.size();
        rig.run(a.dur * 1000L);
        return Tau.compute(longs(rig.ts, from), doubles(rig.kpa, from), bools(rig.nr, from),
                a.kpa, commandedAt, startKpa);
    }

    /** The guided start with its assist on (the default): pull to 17 kPa, hold it 2 s. */
    static void guidedStart(Rig rig) {
        rig.writeTable(100, Model.GUIDED_START_KPA, 255, Model.GUIDED_START_KPA, 1);
        while (rig.pump.pressureKpa() < Model.GUIDED_START_KPA) rig.tick();
        rig.run(Model.GUIDED_START_DWELL_MS);
    }

    /* ------------------------------------------------------------ the bug */

    @Test
    void theVentBeforeThePullArmsOnAPumpThatStopsReportingOnceOpen() {
        Rig rig = new Rig();
        guidedStart(rig);
        double start = ventAndArm(rig);
        assertFalse(Double.isNaN(start),
            "the vent watch evidenced the app's own StopWork and the pump then streamed NO "
            + "MEASUREMENT on a live link — that is a vented cuff, and the pull must start "
            + "from it instead of timing out into WHY_NO_START");
        assertEquals(Tau.VENTED_START_KPA, start, 1e-9,
            "and it starts from the open air, not from a reading nobody took");
    }

    @Test
    void theVentAfterTheRoutineArmsToo() {
        Rig rig = new Rig();
        rig.writeTable(75, 30, 60, 25, 5);            // the routine's last preset
        rig.run(120000L);
        assertTrue(rig.pump.pressureKpa() > 20.0, "the routine left the cuff under pressure");
        double start = ventAndArm(rig);
        assertEquals(Tau.VENTED_START_KPA, start, 1e-9,
            "the after-vent comes down from the routine's own pressure and arms the same way");
    }

    @Test
    void aCuffThatWasAlreadyOpenArmsToo() {
        // A routine whose last step is a REST: the rest already vented the cuff, so the
        // assessment's own StopWork lands on a pump that is streaming nothing but 0.0.
        Rig rig = new Rig();
        rig.writeTable(75, 30, 60, 25, 5);
        rig.run(60000L);
        rig.pump.write(Proto.stop());                 // the rest
        rig.run(60000L);
        double start = ventAndArm(rig);
        assertEquals(Tau.VENTED_START_KPA, start, 1e-9,
            "nothing is left to fall, the watch infers the vent from the silence after a low "
            + "last reading, and the pull starts from the open air");
    }

    /* ----------------------------------------------- B4: both ends, on the sim */

    @Test
    void onTheSimulatedPumpBothEndsMeasureATauAndTheyCompare() {
        Model.Assess a = new Model.Assess();          // 20 kPa, 60 %, 45 s, both
        Rig rig = new Rig();

        guidedStart(rig);
        double p0Before = ventAndArm(rig);
        assertFalse(Double.isNaN(p0Before), "the before-pull arms");
        Tau.Result before = pull(rig, p0Before, a);
        assertTrue(before.ok(), "the before-pull measures a τ, got: " + before.why);

        rig.writeTable(75, 30, 60, 25, 5);            // the routine
        rig.run(180000L);

        double p0After = ventAndArm(rig);
        assertFalse(Double.isNaN(p0After), "the after-pull arms");
        Tau.Result after = pull(rig, p0After, a);
        assertTrue(after.ok(), "the after-pull measures a τ, got: " + after.why);

        // A linear 1.92 kPa/s pull to 20 kPa crosses 63.2 % of it at about 6.6 s — the same
        // order as the owner's own hardware reading (6.7 s at this stimulus).
        assertTrue(before.tauSec > 5.5 && before.tauSec < 7.5, "plausible τ: " + before.tauSec);
        assertTrue(after.tauSec > 5.5 && after.tauSec < 7.5, "plausible τ: " + after.tauSec);
        assertTrue(Tau.comparablePulls(before.baselineKpa, before.peakKpa,
                                       after.baselineKpa, after.peakKpa),
            "both pulls start from the same vented state and reach the same top, so the "
            + "session has a Δτ rather than two numbers that cannot be compared");
        assertNotNull(Tau.deltaPct(before.tauSec, after.tauSec));
    }

    /* ------------------------------------- what must still NOT count as a start */

    /** Frames at the telemetry cadence: `real` real readings of `realKpa`, then `silent`
     *  NO MEASUREMENT frames. */
    static long[] tsOf(int n) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = 1000L + i * 240L;
        return t;
    }
    static double[] kpaOf(double realKpa, int real, int silent) {
        double[] k = new double[real + silent];
        for (int i = 0; i < real; i++) k[i] = realKpa - i * 1.1;
        return k;
    }
    static boolean[] nrOf(int real, int silent) {
        boolean[] b = new boolean[real + silent];
        for (int i = real; i < b.length; i++) b[i] = true;
        return b;
    }

    @Test
    void silenceRightAfterAHighReadingIsADropoutAtPressureNotAVent() {
        // 15.0, 13.9 — then nothing. A sensor dropout at pressure looks exactly like this,
        // and calling it the open air would measure a rise from a cuff still held at 13 kPa.
        long[] t = tsOf(2 + 20);
        double s = Tau.armStartKpa(t, kpaOf(15.0, 2, 20), nrOf(2, 20), t[t.length - 1],
                                    true, 13.9);
        assertTrue(Double.isNaN(s), "a last real reading above ARM_BELOW_KPA refuses");
    }

    @Test
    void noEvidencedVentNoStart() {
        long[] t = tsOf(1 + 20);
        double s = Tau.armStartKpa(t, kpaOf(1.0, 1, 20), nrOf(1, 20), t[t.length - 1],
                                    false, 1.0);
        assertTrue(Double.isNaN(s),
            "without the watch's verdict on the app's own StopWork, silence proves nothing");
    }

    @Test
    void aShortBurstOfNoMeasurementIsOrdinaryFrameLoss() {
        int burst = Session.VENT_INFER_MIN_FRAMES - 1;
        long[] t = tsOf(1 + burst);
        double s = Tau.armStartKpa(t, kpaOf(1.0, 1, burst), nrOf(1, burst), t[t.length - 1],
                                    true, 1.0);
        assertTrue(Double.isNaN(s), "fewer than VENT_INFER_MIN_FRAMES is not a vented state");
    }

    @Test
    void aLinkThatHasGoneQuietIsNotAVentedState() {
        long[] t = tsOf(1 + 20);
        double s = Tau.armStartKpa(t, kpaOf(1.0, 1, 20), nrOf(1, 20),
                                    t[t.length - 1] + Session.VENT_INFER_MAX_FRAME_GAP_MS + 1,
                                    true, 1.0);
        assertTrue(Double.isNaN(s), "the frames have to still be arriving NOW");
    }

    @Test
    void aGapInsideTheSilenceBreaksIt() {
        long[] t = tsOf(1 + 20);
        for (int i = 10; i < t.length; i++) t[i] += Session.VENT_INFER_MAX_FRAME_GAP_MS;
        double s = Tau.armStartKpa(t, kpaOf(1.0, 1, 20), nrOf(1, 20), t[t.length - 1],
                                    true, 1.0);
        assertTrue(Double.isNaN(s), "frames that stopped and restarted are not one run");
    }

    @Test
    void aPumpNeverPressurisedThisSessionIsAtTheOpenAir() {
        long[] t = tsOf(20);
        double s = Tau.armStartKpa(t, new double[20], nrOf(0, 20), t[t.length - 1],
                                    true, Double.NaN);
        assertEquals(Tau.VENTED_START_KPA, s, 1e-9,
            "no real reading at all this session, a live link and an inferred vent");
    }

    @Test
    void anEarlierHighReadingStillRefusesWhenTheWindowHasNone() {
        long[] t = tsOf(20);
        double s = Tau.armStartKpa(t, new double[20], nrOf(0, 20), t[t.length - 1],
                                    true, 20.0);
        assertTrue(Double.isNaN(s),
            "the last thing this pump measured, at any age, was 20 kPa — not the open air");
    }

    @Test
    void theSettledPathIsUnchanged() {
        long[] t = tsOf(2);
        assertEquals(1.3, Tau.armStartKpa(t, new double[]{1.4, 1.3}, new boolean[2],
                                           t[1], false, 1.3), 1e-9,
            "two real frames agreeing at or below ARM_BELOW_KPA still arm, from that reading");
        assertTrue(Double.isNaN(Tau.armStartKpa(t, new double[]{4.9, 4.9}, new boolean[2],
                                                 t[1], true, 4.9)),
            "and settled above the gate still waits");
    }

    /**
     * A LATE StopWork: the first two frames of the vent window still read the held
     * pressure, and they agree. The live tick looks at every new pair as it arrives, so it
     * arms at the bottom (1.3). The replay used to take the FIRST agreeing pair in the whole
     * window, stop there at 28.9 (above the gate), fall through to the vented path, find no
     * quiet run, and answer NaN - so step 1, P2 and these tests replayed a different
     * decision from the one the app makes.
     */
    @Test
    void aSettledPairAboveTheGateDoesNotHideTheOneBelowIt() {
        double[] k = { 29.0, 28.9, 24.0, 18.4, 12.8, 7.2, 2.9, 1.4, 1.3 };
        long[] t = tsOf(k.length);
        assertEquals(1.3, Tau.armStartKpa(t, k, new boolean[k.length], t[t.length - 1],
                                           true, 1.3), 1e-9,
            "the first agreeing pair AT OR BELOW ARM_BELOW_KPA arms, from its reading");
        assertTrue(Double.isNaN(Tau.armStartKpa(t, java.util.Arrays.copyOf(k, 7),
                                                 new boolean[7], t[6], true, 2.9)),
            "and before the bottom arrives, the pair at 29 kPa still arms nothing");
        assertEquals(1.3, Validate.baseline(t, k, new boolean[k.length]).armFromKpa, 1e-9,
            "step 1 / P2 replay the same decision");
    }

    @Test
    void aDropoutInsideTheLowPairIsSkippedNotBroken() {
        // 0.0 is NO MEASUREMENT: skipped, as the live tick skips it, so 1.4 and 1.3 either
        // side of it are still consecutive real frames.
        double[] k = { 29.0, 28.9, 1.4, 0.0, 1.3 };
        boolean[] n = { false, false, false, true, false };
        long[] t = tsOf(k.length);
        assertEquals(1.3, Tau.armStartKpa(t, k, n, t[t.length - 1], true, 1.3), 1e-9);
    }

    /* -------------------------------------------- the sentence the user reads */

    @Test
    void theRefusalIsOnePlainSentence() {
        assertEquals("The after-test couldn't start: the pump didn't report a pressure to "
                   + "measure from.", Tau.noStartSentence(true));
        assertEquals("The before-test couldn't start: the pump didn't report a pressure to "
                   + "measure from.", Tau.noStartSentence(false));
    }
}
