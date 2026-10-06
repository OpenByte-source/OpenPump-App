package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToDoubleFunction;

import org.junit.jupiter.api.Test;

/**
 * A FALL THE PUMP MAKES BY ITSELF IS NOT A VENT.
 *
 * The run's Hold goes to the pump as the pull for 255 s, then a kPa under it for 1 s
 * (InRunHold, RunEdit#clampLower), so the pump itself lets the cuff fall a kPa every 256 s.
 * The vent watch counted a fall of 0.3 kPa, or half the vent rate since the stop was queued,
 * whichever was more, so a 1 kPa fall seen within 0.43 s of the stop passed. StepWindow keeps
 * a Hold limit's FIRST window clear of the step, but the retries open about 8 and 16 s later,
 * and a STOP pressed by hand can land anywhere. So when a StopWork was lost, the pump's own
 * step could read as its vent.
 *
 * A fall now counts only when it is at least Session#VENT_FALL_EVIDENCE_MIN_KPA AND half the
 * vent rate since the stop was queued. These walk the whole watch the way SessionActivity runs
 * it - three attempts, a 6 s window from each write, a check every 300 ms, 2 s between
 * attempts, frames stamped where they arrived - over a pump holding with its own step, and
 * over real vents: synthetic ones, the simulator's, and the ones in the owner's journal.
 */
class FallThresholdTest {

    private static final double RATE = 4.68;      // SessionActivity.VENT_RATE
    private static final long WINDOW = 6000L;     // SessionActivity.VENT_WATCH_WINDOW_MS
    private static final long FRESH = 600L;       // SessionActivity.TELEMETRY_FRESH_MS
    private static final long POLL = 300L;        // SessionActivity.VENT_WATCH_POLL_MS
    private static final long RETRY = 2000L;      // SessionActivity.AUTO_STOP_RETRY_MS
    private static final int ATTEMPTS = 3;        // SessionActivity.VENT_WATCH_MAX_ATTEMPTS
    private static final long FRAME_MS = SimPump.TELEM_PERIOD_MS;
    /** How long a StopWork's write takes to be reported done: the emulator's journals say
     *  "stop written +1..+9 ms". */
    private static final long WRITE_MS = 4L;
    private static final long T0 = 1_000_000L;    // elapsedRealtime() when the watch opens

    /* ================================================================== the harness */

    /** One telemetry frame: the pressure (0.0 = no measurement) and when it arrived, in ms
     *  from the watch opening. Each is handled as it arrives. */
    private static final class Frame {
        final double kpa; final long at;
        Frame(double kpa, long at) { this.kpa = kpa; this.at = at; }
    }

    /** SessionActivity's VentWatcher, field for field. */
    private static final class Watcher {
        final List<String> out = new ArrayList<>();
        final List<Long> opened = new ArrayList<>();
        final List<Frame> held = new ArrayList<>();
        int attempt;
        boolean active, written, done;
        long queued, sent;
        double lastRealSeen = Double.NaN, carried = Double.NaN, everReal = Double.NaN;

        void hold(Frame f) {
            if (f.kpa != 0.0) lastRealSeen = f.kpa;
            held.add(f);
        }

        void recompute(long now) {
            if (!active) return;
            int n = held.size();
            double[] k = new double[n];
            boolean[] r = new boolean[n];
            long[] a = new long[n];
            for (int i = 0; i < n; i++) {
                k[i] = held.get(i).kpa; r[i] = k[i] == 0.0; a[i] = T0 + held.get(i).at;
            }
            String v = Session.ventWatchResult(true, true, 0.0, RATE, T0 + queued,
                written ? T0 + sent : 0L, k, r, a, T0 + now, WINDOW, FRESH, carried, everReal);
            if (Session.VENT_STATE_SENT_UNVERIFIED.equals(v)) return;
            active = false;
            out.add(v + "#" + attempt + "@" + now);
            if (Session.ventedForGating(v)) done = true;
        }
    }

    /**
     * One whole watch, opened at 0: startVentWatch, then VentWatchTick every 300 ms, and
     * VentWatchRetry 2 s after an attempt answers UNCONFIRMED, up to three attempts. The frames
     * are what the pump sends whatever the watch does - every StopWork is LOST unless the frames
     * themselves vent. Returns each attempt's answer as "STATE#attempt@ms", then "EXHAUSTED@ms"
     * when the watch gives up and asks the person. It stops at the first vented answer.
     */
    private static Watcher watch(List<Frame> frames, long until) {
        Watcher w = new Watcher();
        int next = 0;
        Frame last = null;
        long writeAt = -1, tickAt = -1, retryAt = 0;
        while (!w.done) {
            long now = Long.MAX_VALUE;
            if (next < frames.size()) now = frames.get(next).at;
            if (retryAt >= 0) now = Math.min(now, retryAt);
            if (writeAt >= 0) now = Math.min(now, writeAt);
            if (tickAt >= 0) now = Math.min(now, tickAt);
            if (now == Long.MAX_VALUE || now > until) break;
            while (next < frames.size() && frames.get(next).at <= now && !w.done) {
                Frame f = frames.get(next++);                    // PumpLink -> onSample
                last = f;
                if (f.kpa != 0.0) w.everReal = f.kpa;            // lastRealKpaEver
                if (w.active) { w.hold(f); w.recompute(now); }
            }
            if (w.done) break;
            if (retryAt >= 0 && now >= retryAt) {                // startVentWatch / VentWatchRetry
                retryAt = -1;
                if (w.attempt >= ATTEMPTS) { w.out.add("EXHAUSTED@" + now); break; }
                w.attempt++;
                w.opened.add(now);
                w.active = true; w.written = false; w.queued = now;
                w.carried = w.lastRealSeen;
                w.held.clear();
                if (last != null) w.hold(last);                  // the frame in hand
                writeAt = now + WRITE_MS;
                w.recompute(now);
                tickAt = now + POLL;
            }
            if (writeAt >= 0 && now >= writeAt) {                // StopWritten
                w.sent = writeAt; w.written = true; writeAt = -1;
                w.recompute(now);
            }
            if (tickAt >= 0 && now >= tickAt) {                  // VentWatchTick
                w.recompute(now);
                if (w.done) break;
                if (w.active) tickAt = now + POLL;
                else { tickAt = -1; retryAt = now + RETRY; }
            }
        }
        return w;
    }

    /** Frames every 240 ms (the pump's ~4.16 Hz), one at `phase`, from `from` to `to`, of
     *  `kpa(t)` rounded to the 0.1 kPa the pump reports; under 0.05 is no measurement. */
    private static List<Frame> frames(LongToDoubleFunction kpa, long phase, long from, long to) {
        List<Frame> out = new ArrayList<>();
        long t = phase - Math.floorDiv(phase - from, FRAME_MS) * FRAME_MS;
        for (; t <= to; t += FRAME_MS) {
            double k = Math.round(kpa.applyAsDouble(t) * 10.0) / 10.0;
            out.add(new Frame(k < 0.05 ? 0.0 : k, t));
        }
        return out;
    }

    private static boolean vented(Watcher w) {
        for (String a : w.out) if (Session.ventedForGating(a.substring(0, a.indexOf('#') < 0
                ? a.indexOf('@') : a.indexOf('#')))) return true;
        return false;
    }

    private static long when(String answer) {
        return Long.parseLong(answer.substring(answer.indexOf('@') + 1));
    }

    /* ============================================ the pump's own step, under a lost stop */

    /** The pump holding `p` for the run's Hold, its step down beginning at `stepAt`: it lets
     *  the cuff fall at SimPump.DROP_KPA_PER_S to `under` kPa past its lower setpoint (a kPa
     *  under the pull, RunEdit#clampLower), stays there for the rest of the step's second
     *  (InRunHold.CHUNK_DIP_S), and pulls back at 2.4 kPa/s (SimPump's pull at 75 %). Every
     *  reading carries up to `jitter` kPa of noise, either way. */
    private static double holding(long t, double p, long stepAt, double under, double jitter) {
        double lo = RunEdit.clampLower((int) p, (int) p) - under;
        long dipEnd = stepAt + InRunHold.CHUNK_DIP_S * 1000L;
        double k;
        if (t < stepAt) k = p;
        else if (t < dipEnd) k = Math.max(lo, p - SimPump.DROP_KPA_PER_S * (t - stepAt) / 1000.0);
        else k = Math.min(p, lo + SimPump.PULL_KPA_PER_S_AT_FULL * 0.75 * (t - dipEnd) / 1000.0);
        long i = Math.floorDiv(t, FRAME_MS);
        int s = (int) Math.floorMod(i * 7 + 3, 3) - 1;          // -1, 0, +1, a fixed sequence
        return k + s * jitter;
    }

    /** When each attempt opens, from the opening, on a pump that never answers any of them:
     *  0, then about 8.3 and 16.6 s. */
    private static List<Long> attemptTimes(double p) {
        Watcher flat = watch(frames(t -> p, 0, -3000, 30000), 30000);
        assertEquals(3, flat.opened.size(), "three attempts on a pump that never vents: " + flat.out);
        return flat.opened;
    }

    /**
     * THE CASE THIS FIXES, at every attempt. The Hold is up at `p`; the StopWork and both retries
     * are LOST; the pump's own step down lands from just before the attempt's write to 1.5 s
     * after it, at every frame phase. Before this change the step read VENTED at each attempt
     * when its first frame arrived within about 0.4 s of that attempt's stop. Now every attempt
     * answers UNCONFIRMED and the watch asks the person.
     */
    @Test void aLostStopOverTheHoldsOwnStepDownIsNeverVentedOnAnyAttempt() {
        double[] pulls = { 7, 10, 20, 34, 40, 57 };
        double[][] shapes = { { 0.0, 0.0 }, { 0.3, 0.0 }, { 0.3, 0.3 } };   // {under, jitter}
        int runs = 0;
        double largestFall = 0;
        int[] ventedAt = new int[4];                 // by the attempt that read the step as a vent
        List<String> wrong = new ArrayList<>();
        for (double p : pulls) {
            List<Long> open = attemptTimes(p);
            for (double[] shape : shapes) {
                for (int k = 1; k <= 3; k++) {
                    for (long d = -300; d <= 1500; d += 20) {
                      for (long phase = 0; phase < FRAME_MS; phase += 80) {
                        long stepAt = open.get(k - 1) + WRITE_MS + d;
                        final double under = shape[0], jitter = shape[1];
                        List<Frame> fr = frames(t -> holding(t, p, stepAt, under, jitter),
                                                phase, -3000, 30000);
                        largestFall = Math.max(largestFall, largestFallWithin(fr, 1000));
                        Watcher w = watch(fr, 30000);
                        runs++;
                        String why = "a lost stop over the Hold at " + p + " kPa, its step (under "
                            + under + ", jitter " + jitter + ") " + d + " ms after attempt " + k
                            + "'s write, frames at phase " + phase + ": " + w.out;
                        boolean right = !vented(w) && w.out.size() == 4
                            && w.out.get(3).startsWith("EXHAUSTED@");
                        for (int a = 1; right && a <= 3; a++)
                            right = w.out.get(a - 1).startsWith(
                                Session.VENT_STATE_UNCONFIRMED + "#" + a + "@");
                        if (!right) {
                            String lastAnswer = w.out.get(w.out.size() - 1);
                            if (vented(w)) ventedAt[Integer.parseInt(lastAnswer.substring(
                                lastAnswer.indexOf('#') + 1, lastAnswer.indexOf('@')))]++;
                            if (wrong.size() < 3) wrong.add(why);
                        }
                      }
                    }
                }
            }
        }
        assertEquals(6 * 3 * 3 * 91 * 3, runs);
        assertTrue(wrong.isEmpty(), "of " + runs + " lost stops, the step read as the vent on "
            + "attempt 1: " + ventedAt[1] + ", attempt 2: " + ventedAt[2] + ", attempt 3: "
            + ventedAt[3] + "; for example " + wrong);
        assertTrue(largestFall >= 1.9 - 1e-9 && largestFall < Session.VENT_FALL_EVIDENCE_MIN_KPA,
            "the sweep made the pump's own falls as large as 1.9 kPa within a second (got "
            + largestFall + ")");
    }

    /** The largest fall from any frame to a later one within `ms` of it. */
    private static double largestFallWithin(List<Frame> fr, long ms) {
        double most = 0;
        for (int i = 0; i < fr.size(); i++)
            for (int j = i + 1; j < fr.size() && fr.get(j).at - fr.get(i).at <= ms; j++)
                if (fr.get(i).kpa != 0.0 && fr.get(j).kpa != 0.0)
                    most = Math.max(most, fr.get(i).kpa - fr.get(j).kpa);
        return most;
    }

    /**
     * The largest fall the owner's pump made by itself at one setpoint in the journal of
     * 2026-09-22: 41.8 to 39.9 kPa, settling from an overshoot at 40. It came over six seconds
     * there; here it comes as fast as a frame can show it, from 20 ms to 1 s after the write,
     * and the stop is lost. Before this change it read VENTED whenever it showed within about
     * 0.8 s of the stop.
     */
    @Test void theLargestFallThePumpMadeByItselfIsNotAVent() {
        List<String> wrong = new ArrayList<>();
        for (long d = 20; d <= 1000; d += 10) {
            final long at = WRITE_MS + d;
            List<Frame> fr = new ArrayList<>();
            for (long t = at - 9 * FRAME_MS; t <= 30000; t += FRAME_MS)
                fr.add(new Frame(t < at ? 41.8 : 39.9, t));
            Watcher w = watch(fr, 30000);
            if (vented(w) || !w.out.get(0).startsWith(Session.VENT_STATE_UNCONFIRMED + "#1@"))
                wrong.add("+" + d + " ms: " + w.out);
        }
        assertTrue(wrong.isEmpty(), "1.9 kPa of the pump's own, after a lost stop, read as a "
            + "vent " + wrong.size() + " times of 99, e.g. " + wrong.subList(0, Math.min(3, wrong.size())));
    }

    /** The edge, pinned from both sides: 2.4 kPa within the first second is not evidence; 2.5
     *  kPa is, when it also keeps up with half the vent rate. */
    @Test void theEdgeIsTwoAndAHalfKilopascals() {
        assertEquals(2.5, Session.VENT_FALL_EVIDENCE_MIN_KPA, 0.0);
        assertTrue(Session.VENT_FALL_EVIDENCE_MIN_KPA
            >= 2.5 * (20 - RunEdit.clampLower(20, 20)) - 1e-9,
            "at least two and a half of the Hold's steps");
        for (long d = 20; d <= 1000; d += 20) {
            final long at = WRITE_MS + d;
            List<Frame> below = new ArrayList<>(), edge = new ArrayList<>();
            for (long t = at - 9 * FRAME_MS; t <= 9000; t += FRAME_MS) {
                below.add(new Frame(t < at ? 20.0 : 17.6, t));
                edge.add(new Frame(t < at ? 20.0 : 17.5, t));
            }
            assertFalse(vented(watch(below, 9000)), "2.4 kPa at +" + d + " ms is not a vent");
            // By +1004 ms half the vent rate asks for 2.35 kPa, so 2.5 kPa meets both tests.
            assertEquals(Session.VENT_STATE_VENTED + "#1@" + at, watch(edge, 9000).out.get(0),
                "2.5 kPa at +" + d + " ms is a vent");
        }
    }

    /* ============================================================ real vents still count */

    /**
     * Synthetic vents at the measured rate (4.68 kPa/s), from the lowest pull a Hold is usually
     * at up to the ceiling's maximum, starting up to 300 ms after the write, at every frame
     * phase. Every one is VENTED on the first attempt, within 2.5 kPa's worth of venting (534 ms)
     * and a frame after the fall began.
     */
    @Test void aRealVentAtTheMeasuredRateStillConfirmsFromSevenKilopascalsUp() {
        double[] pulls = { 7, 8, 10, 12, 15, 17, 20, 25, 30, 34, 40, 50, 57 };
        for (double p : pulls)
            for (long s = 0; s <= 300; s += 20)
                for (long phase = 0; phase < FRAME_MS; phase += 30) {
                    final long from = WRITE_MS + s;
                    List<Frame> fr = frames(t -> t < from ? p : p - RATE * (t - from) / 1000.0,
                                            phase, -3000, 12000);
                    Watcher w = watch(fr, 12000);
                    String first = w.out.isEmpty() ? "none" : w.out.get(0);
                    String why = "a vent from " + p + " kPa starting " + s + " ms after the write, "
                        + "frames at phase " + phase + ": " + w.out;
                    assertTrue(first.startsWith(Session.VENT_STATE_VENTED + "#1@"), why);
                    long bound = from + (long) Math.ceil(Session.VENT_FALL_EVIDENCE_MIN_KPA / RATE
                        * 1000.0) + FRAME_MS;
                    assertTrue(when(first) <= bound, why + " - later than +" + bound + " ms");
                }
    }

    /**
     * The simulator's normal STOP, as the emulator runs it: a hold at the pull with equal
     * setpoints, then StopWork, which the simulator takes at once and vents at 4.68 kPa/s. It
     * is VENTED within a second (the third frame: 3.4 kPa down at 720 ms).
     */
    @Test void theSimulatorsNormalStopStillConfirmsWithinASecond() {
        for (int p : new int[]{ 10, 20, 40, 57 }) {
            SimPump pump = new SimPump();
            for (int i = 0; i < Proto.SLOTS; i++) pump.write(Proto.deleteSlot(0));
            pump.write(Proto.addPreset(100, p, 255, p, 1));
            pump.write(Proto.startSlot(0));
            long now = 0;
            boolean reached = false;
            List<Frame> fr = new ArrayList<>();
            while (!reached || now < 30000) {
                pump.tick(FRAME_MS); now += FRAME_MS;
                reached |= pump.pressureKpa() >= p;
                for (Proto.Sample s : pump.drainSamples()) fr.add(new Frame(s.noReading ? 0.0 : s.kpa, now));
            }
            long q = now;
            pump.write(Proto.stop());
            List<Frame> rel = new ArrayList<>();
            for (Frame f : fr) rel.add(new Frame(f.kpa, f.at - q));
            while (now < q + 12000) {
                pump.tick(FRAME_MS); now += FRAME_MS;
                for (Proto.Sample s : pump.drainSamples())
                    rel.add(new Frame(s.noReading ? 0.0 : s.kpa, now - q));
            }
            // The simulator took the StopWork before its next frame, so every frame after the
            // stop is after the write: WRITE_MS only moves the write inside the first frame gap.
            Watcher w = watch(rel, 12000);
            String first = w.out.isEmpty() ? "none" : w.out.get(0);
            assertTrue(first.startsWith(Session.VENT_STATE_VENTED + "#1@") && when(first) <= 1000,
                "the simulator's STOP from " + p + " kPa: " + w.out);
        }
    }

    /**
     * EVERY VENT FROM PRESSURE IN THE OWNER'S JOURNAL (session-20260922-151005, the real ZD21).
     *
     * That journal logs one pressure line about every 6 s, so no vent in it can be replayed
     * frame by frame. Each is rebuilt from what it does record: the reading before the stop,
     * the pump's ack of it, and the readings after. The fall is swept over every start (from
     * the ack) and every rate from the measured 4.68 up to 20 kPa/s (the journal's own vents
     * reach 36.6 to 4.7 kPa inside 2 s) that still passes through those readings; once at the
     * last reading the pump reports it one frame in four and no measurement otherwise. Every
     * rebuild is VENTED on the first attempt.
     *
     * Rows: {kPa before, ack ms, kPa after, at ms, (kPa midway, at ms)}. Journal line of the
     * StopWork in the comment; a vent where the app sent two stops is counted from the first.
     */
    private static final double[][] JOURNAL_VENTS = {
        { 16.4,  38,  4.0, 2484 },                  // line 33    user stopped
        { 34.3,   2,  2.9, 5765 },                  // line 1501  vent before the after-assessment
        { 24.0,   4,  2.1, 3232 },                  // line 1558  routine end (the run's peak)
        { 25.8,  11,  2.3, 6095 },                  // line 1634  vent before the before-assessment
        { 39.9,  13,  3.0, 3831 },                  // line 2233  rest step (+line 2236)
        { 28.8,  14,  2.7, 3366, 18.1, 2585 },      // line 2306  rest step
        { 40.2,  29,  3.7, 3518, 39.3, 1761 },      // line 2726  rest step
        { 36.6,  62,  4.7, 2014 },                  // line 3316  rest still under pressure
        { 40.1,  42,  8.1, 4888 },                  // line 3415  rest step (+line 3418)
        { 29.0,  24,  4.4, 3463 },                  // line 3596  rest step
        { 11.3,  18,  3.4, 2165 },                  // line 3646  rest step
        { 39.0,   8,  7.3, 3735 },                  // line 3792  rest step (+lines 3796, 3797)
        { 28.8,  29,  2.7, 3930 },                  // line 3837  rest step
        { 28.1,   1,  5.3, 4334 },                  // line 3883  user stopped
    };

    @Test void everyVentInTheOwnersJournalStillConfirms() {
        double[] rates = { RATE, 7.0, 10.0, 15.0, 20.0 };
        int rebuilt = 0;
        for (double[] v : JOURNAL_VENTS) {
            final double pre = v[0], post = v[2];
            long ack = (long) v[1], postAt = (long) v[3];
            int fits = 0;
            for (double r : rates) {
                for (long s = ack; s <= postAt; s += 20) {
                    final long from = s;
                    final double rate = r;
                    LongToDoubleFunction kpa = t ->
                        t < from ? pre : Math.max(post, pre - rate * (t - from) / 1000.0);
                    if (kpa.applyAsDouble(postAt) > post + 1e-9) continue;          // too slow
                    if (v.length > 4 && Math.abs(kpa.applyAsDouble((long) v[5]) - v[4]) > 1.0)
                        continue;                                                // not the midway reading
                    for (long phase = 0; phase < FRAME_MS; phase += 80) {
                        List<Frame> fr = new ArrayList<>();
                        long t = phase - 3 * FRAME_MS * 4;
                        int after = 0;
                        for (; t <= 12000; t += FRAME_MS) {
                            double k = Math.round(kpa.applyAsDouble(t) * 10.0) / 10.0;
                            if (t > from && k <= post) k = (after++ % 4 == 0) ? post : 0.0;
                            fr.add(new Frame(k, t));
                        }
                        Watcher w = watch(fr, 12000);
                        assertTrue(!w.out.isEmpty() && w.out.get(0).startsWith(
                                Session.VENT_STATE_VENTED + "#1@"),
                            "the journal's vent from " + pre + " kPa (" + post + " at +" + postAt
                            + " ms), falling from +" + s + " ms at " + r + " kPa/s, phase " + phase
                            + ": " + w.out);
                        fits++;
                    }
                }
            }
            assertTrue(fits > 0, "some fall fits the journal's vent from " + pre + " kPa");
            rebuilt += fits;
        }
        assertTrue(rebuilt > 1000, "rebuilt " + rebuilt);
    }
}
