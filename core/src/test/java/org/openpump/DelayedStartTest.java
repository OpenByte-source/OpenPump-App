package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A START THAT WAITED MUST NOT LAND ON A STEP IT WAS NOT MADE FOR.
 *
 * The safety review of refusal-2 (CRITICAL), replayed on the simulated pump with the link's own
 * pace: step N has been pushed out of the table (an edit rewrote it from N+1; the pump cycles N
 * out of its deleted entry, H13). The person taps + about 1.2 s before step N ends, and that
 * START's `01` is lost. 800 ms later the change converges: what was confirmed is step N's own
 * slot, which is not in the table, so the table is rewritten from N and the START posted 600 ms
 * behind it (UPLOAD_SETTLE_MS). Step N's Advance fires first. N+1 is a rest: the StopWork and
 * the vent watch go out. Then the posted START fires - asked only "is the run live, is planIdx a
 * step" (RunEdit#mayRearmRunningPreset) - starts slot 0, which is step N, and sendStartSlot
 * cancels the rest's vent watch. The pump pulls step N's pressure through the whole rest while
 * the screen says REST, and nothing watches it.
 *
 * With the stamp (ArmStamp) the posted START asks whether the step, the phase, the rest and the
 * Hold are still what it was made for, and does nothing when they are not.
 */
class DelayedStartTest {

    static final long WINDOW = 800, HORIZON = 5000, UNSENT = 15000;
    static final long SETTLE = 600;          // SessionActivity.UPLOAD_SETTLE_MS
    static final long FRAME_MS = 30;         // the link carries one frame out at a time
    static final long STEP_MS = 20_000;
    static final int N = 0;                  // step N; N+1 is a rest; N+2 a step

    /** A posted runnable, due at `at`. */
    static final class Job { final long at; final Runnable r; Job(long a, Runnable x) { at = a; r = x; } }

    /** The run screen's side of the sequence, in the fewest lines that do what it does. */
    static final class Rig {
        final boolean stamped;
        final SimPump pump = new SimPump();
        final LiveLink<Object> link = new LiveLink<Object>(HORIZON, UNSENT, 16);
        final List<Job> jobs = new ArrayList<Job>();
        final int[] planUp = { 24, 0, 20 };
        final boolean[] planRest = { false, true, false };
        long now = 0;
        int planIdx = N, batchBase, phase, table;
        boolean running = true, resting, ventWatch, holding;
        boolean loseNextStartAnswer;
        final List<String> log = new ArrayList<String>();
        final List<Object> notes = new ArrayList<Object>();
        final List<Long> sentAt = new ArrayList<Long>();

        Rig(boolean stamped) {
            this.stamped = stamped;
            pump.setReplyLoss(new SimPump.ReplyLoss() {
                @Override public boolean lose(int op, int status) {
                    if (op != Proto.OP_START || !loseNextStartAnswer) return false;
                    loseNextStartAnswer = false;
                    return true;
                }
            });
            // Step N is playing out of slot 0...
            for (int i = 0; i < Proto.SLOTS; i++) pump.write(Proto.deleteSlot(0));
            pump.write(Proto.addPreset(75, planUp[N], 30, 10, 5));
            pump.write(Proto.startSlot(0));
            // ...and an edit rewrote the table from N+1: N is no longer in it (H13 keeps it
            // cycling). The rest's all-zero entry is not stored; N+2 lands in slot 0.
            upload(N + 1);
            pump.setFrameMs(FRAME_MS);
            pump.drain();
            post(STEP_MS, new Advance(N + 1));
        }

        void upload(int from) {
            for (int i = 0; i < Proto.SLOTS; i++) pump.write(Proto.deleteSlot(0));
            for (int k = from; k < planUp.length; k++)
                pump.write(planRest[k] ? Proto.addPreset(0, 0, 0, 0, 0)
                                       : Proto.addPreset(75, planUp[k], 30, 10, 5));
            batchBase = from;
            table++;
            link.tableRewritten();
        }

        void post(long delay, Runnable r) { jobs.add(new Job(now + delay, r)); }

        /* ---- the writers: sendStartSlot cancels the vent watch, as every arming does */

        Object sendStartSlot(int slot, int changeSeq, boolean converge) {
            ventWatch = false;
            Object note = new Object();
            link.startQueued(note, changeSeq, converge, false, now);
            link.written(note, now);
            notes.add(note);
            sentAt.add(now);
            pump.write(Proto.startSlot(slot));
            log.add("t=" + now + " START slot " + slot);
            return note;
        }

        /* ---- the person */

        void tapPlus(int up) {
            loseNextStartAnswer = true;
            int seq = link.beginChange(PendingChange.EDIT, planIdx, LiveLinkTest.tuple(up), false, 0);
            pump.write(Proto.addPreset(75, up, 30, 10, 5));             // the override entry
            int stored = 0;                                             // after the batch
            for (int k = batchBase; k < planUp.length; k++) if (!planRest[k]) stored++;
            sendStartSlot(stored, seq, false);
        }

        /* ---- the sequencing */

        final class Advance implements Runnable {
            final int idx;
            Advance(int i) { idx = i; }
            @Override public void run() {
                phase++;
                planIdx = idx;
                resting = planRest[idx];
                if (resting) {
                    // THE REST VENTS through the one stop path, and is watched.
                    pump.write(Proto.stop());
                    ventWatch = true;
                    log.add("t=" + now + " REST: StopWork, vent watch armed");
                }
            }
        }

        /** The posted START of a convergence (SessionActivity.RestArm). */
        final class RestArm implements Runnable {
            final int slot;
            final ArmStamp stamp;
            RestArm(int s) { slot = s; stamp = new ArmStamp(planIdx, phase, table, false); }
            @Override public void run() {
                boolean live = RunEdit.mayRearmRunningPreset(running, false, planIdx, planUp.length);
                if (stamped) {
                    String why = stamp.stale(live, planIdx, resting, holding || link.holdUnsettled(),
                                             phase, table);
                    if (why != null) {
                        link.cancelConverge();
                        log.add("t=" + now + " the posted START does nothing: " + why);
                        return;
                    }
                } else if (!live) {
                    link.cancelConverge();
                    return;
                }
                sendStartSlot(slot, -1, true);
            }
        }

        /** convergeOnConfirmed: step N's own slot is not in the table - rewrite, and post. */
        void act(LiveLink.Outcome<Object> o) {
            if (o.action != LiveLink.CONVERGE) return;
            if (resting || planIdx != o.forIdx) { link.cancelConverge(); return; }
            log.add("t=" + now + " CONVERGE: rewrite from " + planIdx + ", START in " + SETTLE + " ms");
            upload(planIdx);
            post(SETTLE, new RestArm(0));
        }

        void run(long ms) {
            long end = now + ms;
            while (now < end) {
                now += 10;
                pump.tick(10);
                for (int i = 0; i < jobs.size(); ) {
                    Job j = jobs.get(i);
                    if (j.at <= now) { jobs.remove(i); j.r.run(); } else i++;
                }
                for (byte[] f : pump.drain())
                    if (f.length == 2 && (f[0] & 0xFF) == Proto.OP_START)
                        act(link.answer(f[1] == 0x01, planIdx, now));
                for (int i = 0; i < notes.size(); i++) {
                    if (sentAt.get(i) < 0 || now - sentAt.get(i) < WINDOW) continue;
                    sentAt.set(i, -1L);
                    act(link.timedOut(notes.get(i), planIdx, now));
                }
            }
        }

        int pumpUp() { return pump.running() == null ? 0 : pump.running().upperKpa; }
    }

    @Test void aConvergingStartPostedBehindARewriteDoesNotStartStepNInTheRest() {
        for (boolean stamped : new boolean[]{ false, true }) {
            Rig r = new Rig(stamped);
            r.run(STEP_MS - 1200);
            assertEquals(24, r.pumpUp(), "step N cycles out of its deleted entry");
            r.tapPlus(26);
            r.run(1200 + 100);                    // the window, the convergence, the Advance
            assertEquals(N + 1, r.planIdx, "the rest is playing");
            assertTrue(r.resting);
            r.run(8000);
            String seq = String.join("\n", r.log);
            if (!stamped) {
                // As shipped: the posted START lands on the rest.
                assertEquals(24, r.pumpUp(), "shipped: the pump pulls step N through the rest\n" + seq);
                assertFalse(r.ventWatch, "shipped: and the rest's vent watch was cancelled\n" + seq);
            } else {
                assertNull(r.pump.running(), "nothing runs in the rest\n" + seq);
                assertTrue(r.pump.pressureKpa() < 1.0, "the cuff vented: " + r.pump.pressureKpa());
                assertTrue(r.ventWatch, "and the vent watch still watches it\n" + seq);
                assertFalse(r.link.converging(), "the convergence ended with its step");
                assertTrue(seq.contains("the posted START does nothing"), seq);
            }
        }
    }

    /* ======================================================= the stamp on its own */

    @Test void aStampHoldsOnlyForTheStepPhaseTableAndStateItWasMadeFor() {
        ArmStamp s = new ArmStamp(4, 7, 3, false);
        assertNull(s.stale(true, 4, false, false, 7, 3), "all as it was: it may be written");
        assertNotNull(s.stale(false, 4, false, false, 7, 3), "the run can no longer be commanded");
        assertNotNull(s.stale(true, 5, false, false, 7, 3), "the next step");
        assertNotNull(s.stale(true, 4, true, false, 7, 3), "a rest is up");
        assertNotNull(s.stale(true, 4, false, true, 7, 3), "a hold may be up");
        assertNotNull(s.stale(true, 4, false, false, 8, 3), "the run moved on and came back");
        assertNotNull(s.stale(true, 4, false, false, 7, 4), "its slot is of a table now gone");
    }

    @Test void aStartThatNamesNoSlotIsBlindToTheTable() {
        // A dialled change's settle writes its own entry and START: a new table changes nothing
        // it names.
        ArmStamp settle = new ArmStamp(4, 7, -1, false);
        assertNull(settle.stale(true, 4, false, false, 7, 12));
        assertNotNull(settle.stale(true, 4, false, true, 7, 12), "but never over a hold");
    }

    @Test void aHoldsOwnReleaseMayBeWrittenWhileTheHoldMayBeUp() {
        // Resume writes the START that takes the pump off the Hold: until its ack the Hold may be
        // up, and that START is exactly the one that must go.
        ArmStamp release = new ArmStamp(4, 9, 2, true);
        assertNull(release.stale(true, 4, false, true, 9, 2));
        assertNotNull(release.stale(true, 4, true, true, 9, 2), "but never into a rest");
    }
}
