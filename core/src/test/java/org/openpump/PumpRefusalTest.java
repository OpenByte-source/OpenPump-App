package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * THE SIMULATOR BEHAVES AS THE REAL PUMP DOES WHEN IT REFUSES.
 *
 * The owner's real session journal (22 Sep, a Trainer routine, lines ~1640-1880) shows three
 * behaviours of the Epic Hydro PE Pump (ZD21) that the simulator did not have. Replaying every
 * table write and START in that journal against a model of the pump explains every START reply
 * in it: 280 replied STARTs, 23 of them refused, 0 mismatches. That holds only if the pump
 * behaves as follows. A model that stores an all-zero preset mismatches all 23 refusals.
 *   1. An Add whose five bytes are all zero is NOT stored. The app's Rest placeholder
 *      `66 2A 2B 00 00 00 00 00` therefore left the table one entry short.
 *   2. A START of an empty slot is answered `2C FD`, a refusal, and changes nothing. The
 *      pump goes on with whatever it was running.
 *   3. Deleting the entry the pump is running does not stop it. It keeps cycling that step
 *      (release check H13, answered by the same journal).
 *
 * The replay below is that journal's warm-up, its frames re-typed without any identifier.
 * The Rest placeholder shifts the table. Each of four raises Adds its override at index 7
 * and STARTs slot 8, which is empty, and each is refused. The app wrote every refused raise
 * into the ramp's remaining step anyway, so step 4 started at 37 kPa instead of 29. The cuff
 * went from 24 to 36.4 kPa within 7 s.
 */
class PumpRefusalTest {

    private static final int ADD = Proto.OP_ADD, DEL = Proto.OP_DELETE;

    private static byte[] add(int... b) {
        return new byte[]{ 0x66, 0x2A, (byte) ADD, (byte) b[0], (byte) b[1], (byte) b[2],
                           (byte) b[3], (byte) b[4] };
    }
    private static byte[] del0() { return Proto.deleteSlot(0); }
    private static byte[] start(int s) { return Proto.startSlot(s); }

    /** Every START reply the pump has queued, as "2C 01" / "2C FD", oldest first. */
    private static List<String> replies(SimPump p) {
        List<String> out = new ArrayList<>();
        for (byte[] f : p.drain())
            if (f.length == 2 && (f[0] & 0xFF) == Proto.OP_START)
                out.add(String.format("%02X %02X", f[0] & 0xFF, f[1] & 0xFF));
        return out;
    }

    /** A table of the given presets, then START slot 0, run until it holds. */
    private static SimPump running(int[]... presets) {
        SimPump p = new SimPump();
        for (int i = 0; i < Proto.SLOTS; i++) p.write(del0());
        for (int[] b : presets) p.write(add(b));
        p.write(start(0));
        p.tick(20_000);
        p.drain();
        return p;
    }

    /* ============================================================ the three behaviours */

    @Test void anAllZeroPresetIsNotStored() {
        SimPump p = new SimPump();
        p.write(add(0xBF, 29, 30, 24, 5));
        p.write(add(0, 0, 0, 0, 0));
        p.write(add(0xBF, 27, 120, 10, 5));
        assertEquals(2, p.slotCount(), "the Rest placeholder takes no table entry");
        assertEquals(27, p.slot(1).upperKpa, "so the preset after it is at index 1, not 2");
    }

    @Test void startingAnEmptySlotIsRefusedAndChangesNothing() {
        SimPump p = running(new int[]{ 0xB3, 24, 30, 19, 5 });
        double before = p.pressureKpa();
        p.write(start(8));
        assertEquals(List.of("2C FD"), replies(p), "an empty slot is refused, `2C FD`");
        assertTrue(p.isRunning(), "and the pump goes on with what it was running");
        p.tick(2_000);
        assertTrue(Math.abs(p.pressureKpa() - before) < 6.0,
            "at the same pressure: " + before + " -> " + p.pressureKpa());
        p.write(start(0));
        assertEquals(List.of("2C 01"), replies(p), "a stored slot is acknowledged `2C 01`");
    }

    @Test void deletingTheRunningEntryKeepsItCycling() {
        SimPump p = running(new int[]{ 0xB3, 24, 30, 19, 5 });
        for (int i = 0; i < Proto.SLOTS; i++) p.write(del0());
        assertEquals(0, p.slotCount());
        assertTrue(p.isRunning(), "H13: the pump keeps cycling the step whose entry went");
        assertFalse(p.isVenting(), "it does not vent");
        double lo = 99, hi = 0;
        for (int i = 0; i < 200; i++) {
            p.tick(240);
            lo = Math.min(lo, p.pressureKpa()); hi = Math.max(hi, p.pressureKpa());
        }
        assertTrue(hi > 22 && lo > 15, "still cycling 24/19 kPa: " + lo + ".." + hi);
    }

    /* ============================================== the owner's journal, replayed */

    /** The 22 Sep warm-up: the batch with its Rest placeholder, steps 1-3, and four raises
     *  of step 3, each with its "This set" rewrite of the ramp's remaining step. Returns every
     *  START reply, in order. Times are ms from the batch's first frame, as journaled. */
    private static List<String> replayJournal(SimPump p, double[] pressureAt, long[] at) {
        List<String> starts = new ArrayList<>();
        long[] clock = { 0 };
        Replay r = new Replay(p, clock, starts, pressureAt, at);
        // +0: the first batch - nine deletes, eight adds, a Rest placeholder among them.
        for (int i = 0; i < Proto.SLOTS; i++) r.send(0, del0());
        r.send(48, add(0x99, 15, 30, 10, 5));
        r.send(48, add(0xA6, 20, 30, 15, 5));
        r.send(49, add(0xB3, 24, 30, 19, 5));
        r.send(50, add(0xBF, 29, 30, 24, 5));
        r.send(50, add(0xBF, 29, 45, 5, 5));
        r.send(51, add(0, 0, 0, 0, 0));                  // the Rest placeholder
        r.send(51, add(0xBF, 27, 120, 10, 5));
        r.send(52, add(0xBF, 28, 120, 10, 5));
        r.send(594, start(0));                           // Warm-up 1/4
        r.send(7_204, start(1));                         // skipped to 2/4
        r.send(11_300, start(2));                        // skipped to 3/4: 24/19 kPa
        // Four raises of step 3, 26, 28, 30 and 32 kPa: the override Add, START 8, and
        // the ramp's remaining step rewritten with the raise in it (29 -> 31, 33, 35, 37).
        int[] ups = { 26, 28, 30, 32 };
        int[] step4 = { 31, 33, 35, 37 };
        long[] t = { 20_521, 21_447, 22_323, 23_241 };
        for (int k = 0; k < 4; k++) {
            r.send(t[k], add(0xB3, ups[k], 30, 19, 5));
            r.send(t[k] + 16, start(8));
            for (int i = 0; i < Proto.SLOTS; i++) r.send(t[k] + 18, del0());
            r.send(t[k] + 46, add(0xBF, step4[k], 30, 24, 5));
            r.send(t[k] + 47, add(0xBF, 29, 45, 5, 5));
            r.send(t[k] + 47, add(0, 0, 0, 0, 0));
            r.send(t[k] + 48, add(0xBF, 27, 120, 10, 5));
            r.send(t[k] + 48, add(0xBF, 28, 120, 10, 5));
            r.send(t[k] + 48, add(0xBF, 28, 120, 10, 5));
            r.send(t[k] + 49, add(0xBF, 29, 120, 10, 5));
            r.send(t[k] + 49, add(0xBF, 29, 120, 10, 5));
        }
        r.send(75_022, start(0));                        // step 4, from the rewritten table
        r.until(83_000);
        return starts;
    }

    /** Feeds frames at their journaled times, ticking the simulator in between and noting
     *  each START's reply and the pressure at the asked instants. */
    private static final class Replay {
        final SimPump p; final long[] clock; final List<String> starts;
        final double[] pressureAt; final long[] at;
        Replay(SimPump p, long[] c, List<String> s, double[] pa, long[] a) {
            this.p = p; clock = c; starts = s; pressureAt = pa; at = a;
        }
        void until(long t) {
            while (clock[0] < t) {
                long step = Math.min(10, t - clock[0]);
                p.tick(step);
                long was = clock[0];
                clock[0] += step;
                for (int i = 0; i < at.length; i++)
                    if (at[i] > was && at[i] <= clock[0]) pressureAt[i] = p.pressureKpa();
                for (byte[] f : p.drain())
                    if (f.length == 2 && (f[0] & 0xFF) == Proto.OP_START)
                        starts.add(String.format("%02X %02X", f[0] & 0xFF, f[1] & 0xFF));
            }
        }
        void send(long t, byte[] frame) { until(t); p.write(frame); }
    }

    @Test void theJournalsRaisesAreRefusedAndStep4JumpsToTheRefusedPressure() {
        SimPump p = new SimPump();
        long[] at = { 20_000, 50_000, 74_990, 82_000 };
        double[] kpa = new double[at.length];
        List<String> starts = replayJournal(p, kpa, at);
        assertEquals(List.of("2C 01", "2C 01", "2C 01", "2C FD", "2C FD", "2C FD", "2C FD", "2C 01"),
            starts, "START 0, 1, 2 acknowledged; every raise's START 8 refused, as journaled; "
            + "step 4's START acknowledged");
        assertTrue(kpa[0] > 20 && kpa[0] < 26, "step 3 ran at 24 kPa before the raises: " + kpa[0]);
        assertTrue(kpa[1] > 15 && kpa[1] < 26,
            "after the refusals the cuff stayed on step 3's 24/19 kPa, its table entry "
            + "deleted under it (H13): " + kpa[1]);
        assertTrue(kpa[3] > 34,
            "step 4 was started from a table carrying all four refused raises, 37 kPa where "
            + "the plan said 29 - the journal's 24 -> 36.4 kPa: " + kpa[3]);
    }

    @Test void withoutThePlaceholderTheSameRaiseIsAcknowledged() {
        // The same batch with the Rest left out, as the app now writes it (it ends the batch at
        // the rest): five entries, so the override lands at index 5 and START 5 is acked.
        SimPump p = new SimPump();
        for (int i = 0; i < Proto.SLOTS; i++) p.write(del0());
        p.write(add(0x99, 15, 30, 10, 5));
        p.write(add(0xA6, 20, 30, 15, 5));
        p.write(add(0xB3, 24, 30, 19, 5));
        p.write(add(0xBF, 29, 30, 24, 5));
        p.write(add(0xBF, 29, 45, 5, 5));
        p.write(start(2));
        p.tick(12_000);
        p.drain();
        p.write(add(0xB3, 26, 30, 19, 5));
        p.write(start(p.slotCount() - 1));
        assertEquals(List.of("2C 01"), replies(p));
        p.tick(4_000);
        assertTrue(p.pressureKpa() > 25, "the raise took: " + p.pressureKpa());
    }

    /* ==================================================== what the app reads of a reply */

    @Test void protoReadsARefusal() {
        byte[] fd = { 0x2C, (byte) 0xFD };
        assertTrue(Proto.isRefusal(fd, Proto.OP_START));
        assertFalse(Proto.isAck(fd, Proto.OP_START));
        assertFalse(Proto.isRefusal(new byte[]{ 0x2C, 0x01 }, Proto.OP_START));
        assertFalse(Proto.isRefusal(fd, Proto.OP_ADD), "a refusal is of one command");
        assertNotNull(Proto.parse(fd) == null ? "" : null, "and it is never telemetry");
        assertArrayEquals(new byte[]{ 0x2C, (byte) Proto.REFUSED }, fd);
    }
}
