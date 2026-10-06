package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A BATCH ENDS AT A REST, SO THE APP'S SLOT NUMBERS ARE THE PUMP'S.
 *
 * The run wrote a Rest step into its batch as an all-zero preset, so that "the slot a preset
 * plays out of is idx - batchBase" stayed trivial. The real pump does not store an all-zero
 * preset (PumpRefusalTest). So every entry after the rest sat one index lower than the app
 * believed, the live override was Added at index 7, and its START 8 was refused, `2C FD`.
 * Nothing after a rest in a batch was ever started from that batch anyway: the app rewrites the
 * table on the way out of every rest. So a batch now ENDS at the rest (RunEdit#batchEnd), the
 * rest is never written, and the override's slot is the count actually written.
 *
 * The owner's routine is replayed through the simulated pump, which now stores what the real one
 * stores. Every start the app issues, and the override after it, is acknowledged.
 */
class RestBatchTest {

    private static Model.Preset work(int up, int lo, int uh, int lh, int sp) {
        Model.Preset p = new Model.Preset();
        p.up = up; p.lo = lo; p.uh = uh; p.lh = lh; p.sp = sp; p.durMs = 30_000;
        return p;
    }
    private static Model.Preset rest() {
        Model.Preset p = new Model.Preset();
        p.rest = true; p.durMs = 60_000;
        return p;
    }

    /** The owner's 22 Sep Trainer routine, as the plan the app expanded: a warm-up ramp of
     *  four, a fatigue hold, a rest, five work holds, a rest, five more. */
    private static List<Model.Preset> journalPlan() {
        List<Model.Preset> plan = new ArrayList<>();
        plan.add(work(15, 10, 30, 5, 60));
        plan.add(work(20, 15, 30, 5, 65));
        plan.add(work(24, 19, 30, 5, 70));
        plan.add(work(29, 24, 30, 5, 75));
        plan.add(work(29, 5, 45, 5, 75));
        plan.add(rest());
        for (int i = 0; i < 5; i++) plan.add(work(27 + (i + 1) / 2, 10, 120, 5, 75));
        plan.add(rest());
        for (int i = 0; i < 5; i++) plan.add(work(27 + (i + 1) / 2, 10, 120, 5, 75));
        return plan;
    }

    @Test void aBatchEndsAtTheFirstRestAndNeverWritesIt() {
        List<Model.Preset> plan = journalPlan();
        assertEquals(5, RunEdit.batchEnd(plan, 0, Proto.SLOTS), "0..4, and the rest at 5 is not in it");
        assertEquals(5, RunEdit.batchEnd(plan, 5, Proto.SLOTS), "a batch at a rest is empty");
        assertEquals(11, RunEdit.batchEnd(plan, 6, Proto.SLOTS), "6..10, up to the next rest");
        assertEquals(17, RunEdit.batchEnd(plan, 12, Proto.SLOTS), "12..16, to the plan's end");
        List<Model.Preset> noRest = new ArrayList<>();
        for (int i = 0; i < 20; i++) noRest.add(work(20, 10, 30, 5, 60));
        assertEquals(8, RunEdit.batchEnd(noRest, 0, Proto.SLOTS),
            "no rest: eight, the ninth entry kept for the override");
        for (int from = 0; from < plan.size(); from++)
            for (int i = from; i < RunEdit.batchEnd(plan, from, Proto.SLOTS); i++)
                assertTrue(!plan.get(i).rest, "no batch holds a rest (from " + from + ")");
    }

    @Test void theOverrideSlotIsTheCountWritten() {
        assertEquals(5, RunEdit.overrideSlotAt(5));
        assertTrue(RunEdit.overrideSlotFree(8, Proto.SLOTS, false));
        assertTrue(!RunEdit.overrideSlotFree(8, Proto.SLOTS, true), "a stale override fills it");
        assertTrue(!RunEdit.overrideSlotFree(9, Proto.SLOTS, false));
        assertEquals(2, RunEdit.routineSlotOrReupload(4, 2, 3));
        assertEquals(-1, RunEdit.routineSlotOrReupload(5, 2, 3), "past what the batch wrote");
        assertEquals(-1, RunEdit.routineSlotOrReupload(1, 2, 3), "before it");
    }

    /** Writes a batch as uploadBatch does and returns how many entries it wrote. */
    private static int writeBatch(SimPump p, List<Model.Preset> plan, int from) {
        for (int i = 0; i < Proto.SLOTS; i++) p.write(Proto.deleteSlot(0));
        int end = RunEdit.batchEnd(plan, from, Proto.SLOTS);
        for (int i = from; i < end; i++) {
            Model.Preset x = plan.get(i);
            int up = RunEdit.clampUpper(x.up, 57);
            p.write(Proto.addPreset(x.sp, up, x.uh, RunEdit.clampLower(x.lo, up), x.lh));
        }
        return end - from;
    }

    private static String startReply(SimPump p) {
        String r = "none";
        for (byte[] f : p.drain())
            if (f.length == 2 && (f[0] & 0xFF) == Proto.OP_START)
                r = String.format("%02X %02X", f[0] & 0xFF, f[1] & 0xFF);
        return r;
    }

    @Test void everyStartAndEveryOverrideIsAcknowledgedAcrossTheRoutine() {
        List<Model.Preset> plan = journalPlan();
        SimPump p = new SimPump();
        int batchBase = 0, count = writeBatch(p, plan, 0);
        boolean restRearm = false;
        for (int idx = 0; idx < plan.size(); idx++) {
            Model.Preset x = plan.get(idx);
            if (x.rest) { p.write(Proto.stop()); p.tick(5_000); p.drain(); restRearm = true; continue; }
            if (restRearm || idx >= batchBase + count) {
                batchBase = idx; count = writeBatch(p, plan, idx); restRearm = false;
            }
            p.write(Proto.startSlot(idx - batchBase));
            p.tick(3_000);
            assertEquals("2C 01", startReply(p), "step " + (idx + 1) + " starts");
            // A live raise of this step: the override Add lands at the count written, and its
            // START is that slot - acknowledged, where the old batches got `2C FD` after a rest.
            p.write(Proto.addPreset(x.sp, RunEdit.clampUpper(x.up + 2, 57), x.uh, x.lo, x.lh));
            p.write(Proto.startSlot(RunEdit.overrideSlotAt(count)));
            p.tick(3_000);
            assertEquals("2C 01", startReply(p), "a raise of step " + (idx + 1) + " is taken");
            p.write(Proto.deleteSlot(RunEdit.overrideSlotAt(count)));
            p.drain();
        }
    }
}
