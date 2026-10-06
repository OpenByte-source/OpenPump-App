package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * V1 - WHAT COUNTS AS A VENT, WHEN A FRAME IS STAMPED WHERE IT ARRIVED.
 *
 * The vent watch used to stamp each telemetry frame when the UI thread got round to it.
 * PumpLink posts every frame from the Bluetooth callback thread to the UI thread, so when
 * the UI thread stalls - the very thing that makes the pre-stop reading stale - the frames
 * the pump sent during the stall are all handled in a burst just after the stop is queued,
 * stamped a few milliseconds after it, and look like evidence about the stop. They are not:
 * they were measured before it.
 *
 * {@link Session#ventWatchResult} takes every frame with the time it ARRIVED at the phone
 * and the time the stop's write actually COMPLETED, and holds to three rules:
 *   - only a frame that arrived after the write completed is evidence of anything;
 *   - the baseline a fall is measured from is the last frame that arrived before it, and
 *     only if that frame is real and fresh - never a reading from inside the window;
 *   - a rise is never a fall.
 * With no valid baseline it falls back to the unchanged inference.
 *
 * Each case is walked the way the app walks it: frames are handed over only once the UI
 * thread has handled them, and the verdict is asked every 10 ms.
 */
class VentArrivalTest {

    private static final double RATE = 4.68;      // SessionActivity.VENT_RATE
    private static final long WINDOW = 6000L;     // SessionActivity.VENT_WATCH_WINDOW_MS
    private static final long FRESH = 600L;       // SessionActivity.TELEMETRY_FRESH_MS
    private static final long T0 = 1_000_000L;    // the wall clock when the stop is queued

    /** One frame: its pressure (0.0 = no measurement), when it arrived at the phone, and
     *  when the UI thread handled it. Times are relative to the stop being queued. */
    private static final class Frame {
        final double kpa; final long arrived, handled;
        Frame(double k, long a, long h) { kpa = k; arrived = a; handled = h; }
    }

    /** One attempt of the watch as the UI thread sees it. */
    private static final class Watch {
        final List<Frame> frames = new ArrayList<>();
        long writtenAt = 0, writtenHandledAt = Long.MAX_VALUE;
        boolean written = false;
        double carried = Double.NaN;
        boolean linkReady = true, txOk = true;

        /** The frame in hand when the stop was queued. */
        Watch seed(double kpa, long arrived) { return frame(kpa, arrived, arrived); }
        Watch frame(double kpa, long arrived, long handled) {
            frames.add(new Frame(kpa, arrived, handled));
            return this;
        }
        Watch frame(double kpa, long at) { return frame(kpa, at, at); }
        /** The stop's write completed at `at` (where it completed) and the UI thread
         *  learned of it at `handled`. */
        Watch written(long at, long handled) {
            written = true; writtenAt = at; writtenHandledAt = handled; return this;
        }

        /** The verdict at `now`, from exactly what had been handled by then. Frames are
         *  added in the order the UI thread handles them, which is the buffer's order. */
        String at(long now) {
            List<Frame> seen = new ArrayList<>();
            for (Frame f : frames) if (f.handled <= now) seen.add(f);
            double[] k = new double[seen.size()];
            boolean[] n = new boolean[seen.size()];
            long[] a = new long[seen.size()];
            double ever = Double.NaN;             // SessionActivity.lastRealKpaEver
            for (int i = 0; i < seen.size(); i++) {
                k[i] = seen.get(i).kpa; n[i] = k[i] == 0.0; a[i] = T0 + seen.get(i).arrived;
                if (!n[i]) ever = k[i];
            }
            long sent = (written && writtenHandledAt <= now) ? T0 + writtenAt : 0L;
            return Session.ventWatchResult(linkReady, txOk, 0.0, RATE, T0, sent, k, n, a,
                                           T0 + now, WINDOW, FRESH, carried, ever);
        }

        /** The first instant (10 ms steps) at which the verdict is `state`, or -1. */
        long firstAt(String state, long until) {
            for (long t = 0; t <= until; t += 10) if (state.equals(at(t))) return t;
            return -1;
        }

        /** The attempt's first answer - the first verdict that is not SENT_UNVERIFIED - with
         *  its time, as "STATE@ms"; "none" when it is still waiting at `until`. This is the
         *  answer the watch acts on: it stops polling there and either opens the gates or
         *  retries the stop. */
        String firstAnswer(long until) {
            for (long t = 0; t <= until; t += 10) {
                String v = at(t);
                if (!Session.VENT_STATE_SENT_UNVERIFIED.equals(v)) return v + "@" + t;
            }
            return "none";
        }

        void neverVented(long until, String why) {
            for (long t = 0; t <= until; t += 10) {
                String v = at(t);
                assertFalse(Session.ventedForGating(v),
                    why + " - but the watch said " + v + " at +" + t + " ms");
            }
        }
    }

    /** A cuff holding 20 kPa and leaking 0.25 kPa/s, the round-2 leak, from -1.6 s. */
    private static double leak(long t) { return 20.0 - 0.25 * (t + 1600) / 1000.0; }

    /** A pump venting from 20 kPa at the measured rate from `from`, reporting no
     *  measurement once it is open. */
    private static double venting(long t, long from) {
        double k = 20.0 - RATE * Math.max(0L, t - from) / 1000.0;
        return k < 0.05 ? 0.0 : Math.round(k * 10.0) / 10.0;
    }

    /* =================================== (a) the pre-stop burst, handled after the stop */

    @Test void aStallBurstFromBeforeTheStopIsNeverItsFall() {
        // The safety review's sequence. The UI thread stalls 1.6 s; the hold limit queues its
        // StopWork at 0 and the stack takes it at +3 ms - but it is LOST, and the pump goes
        // on holding with its cuff leak. The six frames it sent during the stall (20.0 down
        // to 19.6) are handled at +51..+56 ms, just after the stop was queued.
        Watch w = new Watch().seed(20.0, -1600);
        for (int i = 1; i <= 6; i++) { long a = -1600 + 240L * i; w.frame(leak(a), a, 50 + i); }
        w.written(3, 57);
        for (long t = 80; t <= 13000; t += 240) w.frame(leak(t), t);
        w.neverVented(13000, "frames measured before the stop are not evidence about it, and a "
            + "0.25 kPa/s leak after a lost stop is not a vent");
        assertEquals(Session.VENT_STATE_UNCONFIRMED, w.at(6010),
            "a lost stop is reported when the window closes - six seconds after the write");
    }

    @Test void aFallThatHappenedBeforeTheStopIsNotCreditedToIt() {
        // The same stall over a routine's release phase: the pump dropped from 26 to 20 kPa
        // (5 kPa/s) BEFORE the stop and settled there, and those frames are handled after
        // it. The stop is lost and the pump holds 20. A rule that took the first frame
        // handled after the stop as its baseline would read 4.8 kPa of "fall" in a few
        // milliseconds (8772fb6's hazard).
        Watch w = new Watch().seed(26.0, -1600);
        for (int i = 1; i <= 6; i++) {
            long a = -1600 + 240L * i;
            w.frame(Math.max(20.0, 26.0 - 5.0 * (a + 1600) / 1000.0), a, 50 + i);
        }
        w.written(3, 57);
        for (long t = 80; t <= 13000; t += 240) w.frame(20.0, t);
        w.neverVented(13000, "a drop the pump made before the stop went out proves nothing about "
            + "the stop");
    }

    /* ============================================================ (b) a rise is not a fall */

    @Test void aRiseIsNeverReadAsAFall() {
        // The reading before the stop is 2 s stale, so there is no valid baseline. The
        // window then holds 19.2 at 240 ms and 19.9 after it. Measured against the window's
        // own newest reading, 19.2 had "fallen" 0.7 - past the 0.56 required at 240 ms.
        Watch w = new Watch().seed(19.5, -2000).written(2, 2);
        w.frame(19.2, 240).frame(19.9, 480);
        for (long t = 720; t <= 8000; t += 240) w.frame(19.9, t);
        w.neverVented(8000, "a pressure that went UP after the stop is not a vent");
        assertEquals(Session.VENT_STATE_UNCONFIRMED, w.at(6100));
    }

    @Test void aPumpThatIgnoredTheStopAndToppedUpIsNeverVented() {
        // Stale reading before the stop; the pump never acted on it and pulls back up at
        // 1.5 kPa/s. Every later frame is higher than every earlier one.
        Watch w = new Watch().seed(18.0, -1500).written(2, 2);
        for (long t = 240; t <= 9000; t += 240) w.frame(Math.min(25.0, 18.0 + 1.5 * t / 1000.0), t);
        w.neverVented(9000, "a pump topping up after a stop has not vented");
        // ...and with a FRESH baseline too: a rise from it is still no fall.
        Watch f = new Watch().seed(18.0, -100).written(2, 2);
        for (long t = 240; t <= 9000; t += 240) f.frame(Math.min(25.0, 18.0 + 1.5 * t / 1000.0), t);
        f.neverVented(9000, "a rise from a fresh baseline is not a fall either");
    }

    /* =========================== (c) a real vent whose pre-stop reading LOOKED stale */

    @Test void aStallBeforeTheStopStillLeavesAFreshBaseline() {
        // On hardware the stall does not stop frames ARRIVING. The frames sent during it are
        // handled after the stop was queued but stamped where they arrived, so the last one
        // (at -160 ms) is a fresh baseline and the real vent is proved within a second.
        Watch w = new Watch().seed(20.0, -1600);
        for (int i = 1; i <= 6; i++) w.frame(20.0, -1600 + 240L * i, 50 + i);
        w.written(3, 57);
        for (long t = 80; t <= 9000; t += 240) w.frame(venting(t, 3), t);
        long vented = w.firstAt(Session.VENT_STATE_VENTED, 9000);
        assertTrue(vented > 0 && vented <= 1000, "proved from the fresh baseline (got +" + vented + ")");
        assertEquals(-1, w.firstAt(Session.VENT_STATE_UNCONFIRMED, vented),
            "and never doubted before that");
    }

    /**
     * The measurement study's hold-limit run on the simulator. The reading before the stop was
     * 0.7 s old because the simulator makes no frames while the UI thread is busy, then the fall
     * came at the pump's rate. With no reading from before the stop there is nothing to measure
     * a fall from, and from 20 kPa the inference needs about 7.4 s. So the first answer is
     * UNCONFIRMED when the window closes: the user is warned and the stop is sent again.
     *
     * That is the direction chosen after f1e399d was reverted. A false UNCONFIRMED costs a
     * warning and a harmless second StopWork. Holding the window open for a fall the watch
     * could see, which is what f1e399d did, also held it open for a fall the stop did not
     * cause (the test below).
     */
    @Test void theStudysVentWithNoBaselineIsAnsweredUnconfirmedNotGuessed() {
        double[] kpa = { 18.6, 17.5, 16.4, 15.3, 14.1, 13.0, 11.9, 10.8, 9.6, 8.5, 7.4, 6.3, 5.2,
                         4.0, 2.9, 1.8, 0.7 };
        long[] ms = { 650, 880, 1120, 1370, 1610, 1850, 2100, 2340, 2590, 2830, 3080, 3320,
                      3560, 3800, 4040, 4290, 4530 };
        Watch w = new Watch().seed(19.8, -700).written(0, 650);
        for (int i = 0; i < kpa.length; i++) w.frame(kpa[i], ms[i]);
        for (long t = 4770; t <= 12000; t += 240) w.frame(0.0, t);      // open: no measurement
        assertEquals(Session.VENT_STATE_UNCONFIRMED + "@6000", w.firstAnswer(12000),
            "no reading from before the stop, so no fall can be proved, and the window closes on "
            + "time rather than waiting on what the watch can see");
    }

    /* ================= the safety review of f1e399d: a release the stop did not cause */

    /**
     * The reviewer's case, swept (scratchpad/v1rev Rev.java, Rev2.java). The reading before the
     * stop is stale, and the StopWork is LOST. Between 0 and 2.4 s after the write, the pump's
     * own program releases at the vent's own rate: a routine's drop, or the cylinder pulled off.
     * Once the reading is below `floor` it is reported as 0.0. Nothing the watch holds was caused
     * by the stop.
     *
     * With f1e399d the watch held its window open for that fall and the inference completed
     * behind it: VENTED_INFERRED at +6.5 to +9.4 s, no retry, and the gates opened. Now the
     * watch answers by the time the window closes, and never with an inference it would have had
     * to wait past the window for. Where the pump never reports a real reading at or under
     * 0.3 kPa (floor 1 kPa and up), that answer is UNCONFIRMED, so the stop is sent again.
     *
     * Where it does (floor 0.05 kPa), a real 0.3 kPa is a measured arrival at atmosphere. That
     * was VENTED before any of this, with or without the patience, and it stays so. The cuff is
     * open at that moment, but a program that ignored the stop can pull again. That residual is
     * the same as the one for a release against a fresh baseline, and the report lists it.
     */
    @Test void aReleaseTheStopDidNotCauseIsAnsweredWhenTheWindowCloses() {
        long closes = 2 + WINDOW;
        double[] floors = { 0.05, 1.0, 2.0, 3.0 };
        for (double floor : floors) {
            for (long start = 0; start <= 2400; start += 200) {
                Watch w = new Watch().seed(20.0, -2000).written(2, 2);
                for (long t = 240; t <= 16000; t += 240) {
                    double k = 20.0 - RATE * Math.max(0L, t - start) / 1000.0;
                    w.frame(k < floor ? 0.0 : Math.round(k * 10.0) / 10.0, t);
                }
                String first = w.firstAnswer(16000);
                String why = "a lost stop, and the pump's own release from +" + start + " ms "
                    + "(readings under " + floor + " kPa reported as 0.0) - got " + first;
                assertTrue(!"none".equals(first)
                        && Long.parseLong(first.substring(first.indexOf('@') + 1)) <= closes + 10,
                    "the watch answers by the time its window closes: " + why);
                assertFalse(first.startsWith(Session.VENT_STATE_VENTED_INFERRED + "@"),
                    "never inferred vented from a fall the stop did not cause: " + why);
                if (floor >= 1.0)
                    assertTrue(first.startsWith(Session.VENT_STATE_UNCONFIRMED + "@"),
                        "with no real reading at atmosphere, the answer is UNCONFIRMED and the "
                        + "stop is retried: " + why);
            }
        }
    }

    /* ======================================= the safety review: a clock stepped backwards */

    /** The review's case B, as it physically happens: the pump holding, creeping from `before`
     *  (19.5 in the review) to 20.0 kPa. The last frame handled before a 2 s UI stall arrived at
     *  -2000 ms, and eight more
     *  arrived during the stall, handled at +21..+28 ms. The StopWork was queued at 0 and written
     *  at +5, and it was LOST, so the pump went on holding 20.0. Every frame and the write are
     *  stamped by `clock`, which maps a physical instant to the stamp a clock would give it.
     *  Returns the watch's verdict at physical `now`. */
    private static String caseB(java.util.function.LongUnaryOperator clock, long now) {
        return caseB(clock, now, 19.5);
    }

    private static String caseB(java.util.function.LongUnaryOperator clock, long now,
                                double before) {
        List<long[]> at = new ArrayList<>();             // {arrived, handled}
        List<Double> kpa = new ArrayList<>();
        at.add(new long[]{ -2000, -2000 }); kpa.add(before);
        for (int i = 1; i <= 8; i++) { at.add(new long[]{ -2000 + 240L * i, 20 + i }); kpa.add(20.0); }
        for (long t = 240; t <= 9000; t += 240) { at.add(new long[]{ t, t }); kpa.add(20.0); }
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < at.size(); i++) if (at.get(i)[1] <= now) seen.add(i);
        double[] k = new double[seen.size()];
        boolean[] n = new boolean[seen.size()];
        long[] a = new long[seen.size()];
        for (int j = 0; j < seen.size(); j++) {
            int i = seen.get(j);
            k[j] = kpa.get(i); n[j] = false; a[j] = T0 + clock.applyAsLong(at.get(i)[0]);
        }
        long sent = now >= 5 ? T0 + clock.applyAsLong(5) : 0L;
        return Session.ventWatchResult(true, true, 0.0, RATE, T0 + clock.applyAsLong(0), sent,
                                       k, n, a, T0 + clock.applyAsLong(now), WINDOW, FRESH,
                                       Double.NaN, 20.0);
    }

    @Test void aClockSteppedBackwardsCannotReorderBeforeAndAfterTheWrite() {
        // SystemClock.elapsedRealtime(), which is what PumpLink and the VentWatcher stamp with
        // (WiringCheck invariant 81): no step, so every stamp is the physical order.
        java.util.function.LongUnaryOperator monotonic = t -> t;
        for (long now = 0; now <= 9000; now += 10)
            assertFalse(Session.ventedForGating(caseB(monotonic, now)),
                "a lost stop over a holding pump is not a vent (monotonic stamps, at +" + now + ")");
        assertEquals(Session.VENT_STATE_UNCONFIRMED, caseB(monotonic, 6010));
        // ...and a pump topping up faster, from 17.0, is no more a vent: a rise is not a fall.
        for (long now = 0; now <= 9000; now += 10)
            assertFalse(Session.ventedForGating(caseB(monotonic, now, 17.0)),
                "a lost stop over a pump topping up is not a vent (monotonic, at +" + now + ")");

        // CONTROL: the same events on a wall clock that stepped back 2.1 s at -1900 ms, just
        // after the first reading arrived. That old reading's stamp now lies after the write, and
        // it reads as a fall from the 20.0 that arrived in the stall - VENTED at +30 ms, as the
        // review found with 19.5. This is why no time the watch compares may come from the wall
        // clock. The review's 0.5 kPa is now under Session.VENT_FALL_EVIDENCE_MIN_KPA, so the
        // control reads the pump topping up from 17.0: 3 kPa, read backwards, still passes.
        java.util.function.LongUnaryOperator steppedBack = t -> t < -1900 ? t : t - 2100;
        assertEquals(Session.VENT_STATE_VENTED, caseB(steppedBack, 30, 17.0),
            "the control: with wall-clock stamps the step makes the lost stop read VENTED");
        assertFalse(Session.ventedForGating(caseB(steppedBack, 30)),
            "the review's own 0.5 kPa is no longer a fall that counts, whatever the clock");
    }

    /* ============================================================ the rules, one at a time */

    @Test void nothingCountsUntilTheStopHasActuallyGoneOut() {
        // The StopWork waited 1.5 s behind other writes. Meanwhile the routine's own release
        // took the cuff from 20 to 13.3 kPa - a vent-sized fall, before the stop left the
        // phone. It is not the stop's doing; once the write completes those frames are the
        // BASELINE, and a pump holding 13.3 after it has not vented.
        Watch w = new Watch().seed(20.0, -100);
        for (long t = 240; t <= 1440; t += 240) w.frame(20.0 - 5.0 * t / 1000.0 + 0.5, t);
        w.written(1500, 1500);
        for (long t = 1680; t <= 9000; t += 240) w.frame(13.3, t);
        w.neverVented(9000, "a fall before the write completed is not evidence about it");
        assertEquals(Session.VENT_STATE_SENT_UNVERIFIED, w.at(1440),
            "while the write has not gone out, nothing is decided");
    }

    @Test void aWriteDoneFromBeforeTheStopWasQueuedIsNotItsWrite() {
        // The safety review's finding 3, at the rule. A late write-done for the PREVIOUS frame
        // retired the StopWork with that frame's completion time, 200 ms before the stop was
        // even queued. The pump was in its own release (20 to 19 kPa) just before the stop,
        // and the stop was lost. Taking -200 as the write, the 19.0 that arrived at -60 read
        // as a fall after it: VENTED. A write cannot complete before its stop was queued, so
        // that report is not this stop's; the watch waits for its own, and without one it
        // answers UNCONFIRMED when the window closes.
        Watch w = new Watch().seed(20.0, -300).frame(19.0, -60, -60).written(-200, 1);
        for (long t = 180; t <= 8000; t += 240) w.frame(19.0, t);
        w.neverVented(8000, "a completion time from before the stop was queued is not the stop's");
        assertEquals(Session.VENT_STATE_UNCONFIRMED, w.at(6000));
    }

    @Test void aWriteThatNeverWentOutIsUnconfirmedWhenTheWindowCloses() {
        Watch w = new Watch().seed(20.0, -100);
        for (long t = 240; t <= 8000; t += 240) w.frame(venting(t, 0), t);
        assertEquals(Session.VENT_STATE_SENT_UNVERIFIED, w.at(5990));
        assertEquals(Session.VENT_STATE_UNCONFIRMED, w.at(6000),
            "a StopWork dropped before it was written proves nothing, however the pressure reads");
        w.neverVented(8000, "no write, no evidence");
    }

    @Test void aStaleBaselineIsNoBaseline() {
        // Round 2's exploit: the link-loss auto-stop, 11 s after the last frame. The leak
        // banked in the gap (2.75 kPa) must not be read as a fall once telemetry returns.
        Watch w = new Watch().seed(20.0, -11000).written(2, 2);
        for (long t = 1000; t <= 9000; t += 240) w.frame(17.25 - 0.25 * (t - 1000) / 1000.0, t);
        w.neverVented(9000, "leak banked during an unobserved gap is not a fall");
        // ...and one a hair too old is refused the same way: FRESH is measured to the send.
        // (3 kPa at 240 ms: past Session.VENT_FALL_EVIDENCE_MIN_KPA, so only the age decides.)
        Watch h = new Watch().seed(20.0, -601).written(0, 0).frame(17.0, 240);
        assertEquals(Session.VENT_STATE_SENT_UNVERIFIED, h.at(240),
            "a reading 601 ms before the write completed is past TELEMETRY_FRESH_MS");
        Watch ok = new Watch().seed(20.0, -600).written(0, 0).frame(17.0, 240);
        assertEquals(Session.VENT_STATE_VENTED, ok.at(240), "...and 600 ms is within it");
    }

    @Test void aNoMeasurementFrameIsNeverABaseline() {
        Watch w = new Watch().seed(20.0, -300).frame(0.0, -60, -60).written(2, 2);
        w.frame(15.0, 1000).frame(10.0, 2000);
        assertEquals(Session.VENT_STATE_SENT_UNVERIFIED, w.at(2000),
            "the last frame before the send was a 0.0, so no fall can be measured");
    }

    @Test void theInferenceIsReachedWithNoBaselineAndRefusedAtPressure() {
        // A retry on a vented pump: nothing but 0.0 before and after the send, and 2 kPa the
        // last thing an earlier attempt really saw.
        Watch w = new Watch().seed(0.0, -100).written(2, 2);
        w.carried = 2.0;
        for (long t = 240; t <= 6000; t += 240) w.frame(0.0, t);
        long inferred = w.firstAt(Session.VENT_STATE_VENTED_INFERRED, 6000);
        assertTrue(inferred > 0 && inferred < 6000, "inferred vented (at +" + inferred + ")");
        // ...and the same run after 18 kPa was the last real reading is a dropout at pressure.
        Watch h = new Watch().seed(0.0, -100).written(2, 2);
        h.carried = 18.0;
        for (long t = 240; t <= 8000; t += 240) h.frame(0.0, t);
        h.neverVented(8000, "a pump last measured at 18 kPa is never inferred vented");
    }

    @Test void aFreshBaselineStillConfirmsAVentPromptly() {
        Watch w = new Watch().seed(20.0, -120).written(3, 3);
        for (long t = 120; t <= 6000; t += 240) w.frame(venting(t, 3), t);
        long vented = w.firstAt(Session.VENT_STATE_VENTED, 6000);
        assertTrue(vented > 0 && vented <= 600, "proved at +" + vented + " ms");
    }

    @Test void aStopThatWasNotWrittenIsUnconfirmedAtOnce() {
        Watch w = new Watch().seed(20.0, -100).written(2, 2);
        for (long t = 240; t <= 3000; t += 240) w.frame(venting(t, 2), t);
        w.txOk = false;
        assertEquals(Session.VENT_STATE_UNCONFIRMED, w.at(3000));
        w.txOk = true; w.linkReady = false;
        assertEquals(Session.VENT_STATE_UNCONFIRMED, w.at(3000));
    }

    /* ================================================== end to end, through the simulator */

    /** The simulator driven the way SessionActivity drives it, on its own clock: every
     *  frame is stamped as it is emitted, and a StopWork's write is done when the simulator
     *  takes it. */
    private static final class SimRig {
        final SimPump pump = new SimPump();
        long now = 0;
        double lastKpa = Double.NaN;
        long lastAt = 0;
        /** Tick once, handing the frame to `w` (stamped relative to its stop at `q`). */
        void tick(Watch w, long q) {
            pump.tick(SimPump.TELEM_PERIOD_MS);
            now += SimPump.TELEM_PERIOD_MS;
            for (Proto.Sample s : pump.drainSamples()) {
                lastKpa = s.kpa; lastAt = now;
                if (w != null) w.frame(s.kpa, now - q);
            }
        }
        /** Queue a StopWork now: a watch seeded with the frame in hand, written at once. A
         *  `lost` stop is written by the phone and never delivered - what a radio dropping the
         *  frame does, and what the debug build's SimStopLoss does. The simulator itself vents
         *  on every StopWork it receives. */
        Watch stop(boolean lost) {
            Watch w = new Watch().seed(lastKpa, lastAt - now).written(0, 0);
            if (!lost) pump.write(Proto.stop());
            return w;
        }
    }

    @Test void aLostStopOnTheSimulatorIsNotConfirmedAndItsRetryIs() {
        SimRig r = new SimRig();
        for (int i = 0; i < Proto.SLOTS; i++) r.pump.write(Proto.deleteSlot(0));
        r.pump.write(Proto.addPreset(100, 20, 255, 20, 1));
        r.pump.write(Proto.startSlot(0));
        while (r.pump.pressureKpa() < 20.0) r.tick(null, 0);
        for (int i = 0; i < 20; i++) r.tick(null, 0);            // holding at 20 kPa

        // Attempt 1: the StopWork is written, and lost on its way to the pump.
        long q1 = r.now;
        Watch a1 = r.stop(true);
        assertTrue(r.pump.pressureKpa() > 19.0 && !r.pump.isVenting(), "the stop was lost");
        for (int i = 0; i < 30; i++) r.tick(a1, q1);
        a1.neverVented(r.now - q1, "a StopWork the pump never received is not a vent");
        assertEquals(Session.VENT_STATE_UNCONFIRMED, a1.at(WINDOW),
            "it is reported when the window closes");

        // Attempt 2, the retry: this one lands, and the vent is proved.
        long q2 = r.now;
        Watch a2 = r.stop(false);
        assertTrue(r.pump.isVenting(), "the retry was not lost");
        for (int i = 0; i < 30; i++) r.tick(a2, q2);
        long vented = a2.firstAt(Session.VENT_STATE_VENTED, r.now - q2);
        assertTrue(vented > 0 && vented <= 1000, "the retry's vent is proved (at +" + vented + ")");
    }
}
