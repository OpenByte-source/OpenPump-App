package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * THE REVIEWER'S FUZZ, KEPT: THE PUMP NEVER RUNS ABOVE WHAT THE SCREEN SAYS IT MAY BE AT.
 *
 * The safety review of refusal-2 ran an adversarial harness (scratchpad/refusal-rev2/h/Fuzz.java)
 * against the real LiveLink, ReplyQueue and PendingChange: a pump that carries its STARTs out in
 * order and answers them in order - on time, late (700 to 3300 ms), not at all, with an FD, or
 * with an FD that is lost - and glue that does what the run screen does with each outcome. Over
 * 20000 seeds it found 16 runs where, once everything was quiet, the pump ran above what the
 * screen showed (four of them after GIVE_UP, where not answering did not stick): seed 910 (a
 * 3.2 s answer shifted the pairing and committed a refused lowering on a borrowed `01`), seed
 * 16240 (an FD cleared "not answering", pump 26 and cells 18).
 *
 * Here it is a property of the build, on the simulated pump: SimPump carries the fates out
 * (#setRefusal, #setReplyLoss, #setReplyDelay - answers late but in order), with the table the
 * app keeps - a batch of steps and the override entry after it - so a refusal's table rewrite
 * and a carried adjustment are the real shapes. The breach is the reviewer's own: quiet, the
 * pump's pull is above the screen's; not answering, above the higher of the screen's and the
 * change that was lost.
 *
 * And it is known to be able to fail: the same property, with the answers' horizon cut back to
 * refusal-2's 2.8 s (window plus tombstone), finds the breaches the review found.
 */
class LiveLinkFuzzTest {

    static final long WINDOW = 800, HORIZON = 5000, UNSENT = 15000;
    /** Refusal-2's reach for a late answer: the 800 ms window and the 2 s tombstone. */
    static final long SHIPPED_REACH = 800 + 2000;
    static final int STEP_BATCH = 4;

    static final class Note {
        final int id; final String kind; final int value; final long sentAt;
        boolean done;
        Note(int id, String kind, int value, long sentAt) {
            this.id = id; this.kind = kind; this.value = value; this.sentAt = sentAt;
        }
    }

    /** One run: the pump, the link, and the run screen's side of it. */
    static final class Run {
        final Random rnd;
        final boolean silent;
        final int maxLate;
        final SimPump pump = new SimPump();
        final LiveLink<Note> link;
        final List<Note> notes = new ArrayList<Note>();
        final List<Integer> steps = new ArrayList<Integer>();
        final StringBuilder log = new StringBuilder();
        long now = 0;
        int ids = 0;
        int planIdx = 0, base = 0;
        int confirmed;
        boolean carry, overrideInTable, stopped;
        int lostUp = -1;
        int giveUps, stops, converges, orphans;
        /** The fate of the START being carried out now: {executes, answered, delayMs}. */
        int[] fate;

        Run(long seed, boolean silent, int maxLate, long horizon) {
            this.rnd = new Random(seed);
            this.silent = silent;
            this.maxLate = maxLate;
            this.link = new LiveLink<Note>(horizon, UNSENT, 16);
            pump.setRefusal(new SimPump.Refusal() {
                @Override public boolean refuse(int slot) { return fate != null && fate[0] == 0; }
            });
            pump.setReplyLoss(new SimPump.ReplyLoss() {
                @Override public boolean lose(int op, int status) {
                    return op == Proto.OP_START && fate != null && fate[1] == 0;
                }
            });
            pump.setReplyDelay(new SimPump.ReplyDelay() {
                @Override public long delayMs(int op, int status) {
                    return op == Proto.OP_START && fate != null ? fate[2] : 0;
                }
            });
            for (int i = 0; i < 64; i++) steps.add(18 + rnd.nextInt(14));
            steps.set(0, 24);
            confirmed = 24;
            upload(0);
            pump.write(Proto.startSlot(0));
            pump.drain();
        }

        /* ---- the pump's fates, as the reviewer drew them */

        int[] nextFate(boolean step) {
            int r = rnd.nextInt(100);
            int delay = rnd.nextInt(10) == 0 ? 700 + rnd.nextInt(maxLate) : 20 + rnd.nextInt(60);
            int[] f;
            if (r < 70) f = new int[]{ 1, 1, delay };
            else if (r < 88) f = new int[]{ 1, 0, 0 };            // done, its answer lost
            else if (r < 96) f = new int[]{ 0, 1, delay };        // refused: FD
            else if (r < 98) f = new int[]{ 0, 0, 0 };            // refused, the FD lost
            else f = silent ? new int[]{ 0, 0, 0 } : new int[]{ 1, 0, 0 };
            if (step && f[0] == 0) f = new int[]{ 1, f[1], f[2] };  // a step's START runs
            return f;
        }

        int pumpUp() { return pump.running() == null ? 0 : pump.running().upperKpa; }

        /* ---- the table: a batch of steps from `base`, then the override entry */

        void upload(int from) {
            for (int i = 0; i < Proto.SLOTS; i++) pump.write(Proto.deleteSlot(0));
            base = from;
            for (int k = 0; k < STEP_BATCH; k++)
                pump.write(Proto.addPreset(75, steps.get(from + k), 30, 10, 5));
            overrideInTable = false;
            link.tableRewritten();
        }

        void writeOverride(int up) {
            if (overrideInTable) pump.write(Proto.deleteSlot(STEP_BATCH));
            pump.write(Proto.addPreset(75, up, 30, 10, 5));
            overrideInTable = true;
        }

        Note start(String kind, int slot, int changeSeq, boolean converge, int value) {
            Note n = new Note(++ids, kind, value, now);
            link.startQueued(n, changeSeq, converge, false, now);
            link.written(n, now);
            notes.add(n);
            fate = nextFate(kind.equals("step"));
            pump.write(Proto.startSlot(slot));
            log.append(String.format("t=%d W#%d %s %d fate=%d/%d/%d pump=%d%n", now, n.id, kind,
                value, fate[0], fate[1], fate[2], pumpUp()));
            fate = null;
            return n;
        }

        /* ---- the person, and the routine */

        void tap(int up) {
            if (stopped || !link.mayWriteChange(now)) return;
            int seq = link.beginChange(PendingChange.EDIT, planIdx, LiveLinkTest.tuple(up), false, 0);
            writeOverride(up);
            start("change", STEP_BATCH, seq, false, up);
        }

        void nextStep() {
            if (stopped) return;
            planIdx++;
            confirmed = steps.get(planIdx);
            carry = false;
            lostUp = -1;
            if (planIdx >= base + STEP_BATCH) upload(planIdx);
            start("step", planIdx - base, -1, false, confirmed);
        }

        /** What was confirmed, again: the carried adjustment, or the step's own slot - and the
         *  table first when the pump refused something (the run screen's convergeOnConfirmed). */
        void writeConfirmed(boolean afterRefusal) {
            converges++;
            if (carry) {
                writeOverride(confirmed);
                start("converge", STEP_BATCH, -1, true, confirmed);
                return;
            }
            if (afterRefusal) upload(planIdx);
            start("converge", planIdx - base, -1, true, confirmed);
        }

        /* ---- what the run screen does with each outcome (changeOutcome) */

        void act(LiveLink.Outcome<Note> o) {
            switch (o.action) {
                case LiveLink.COMMIT:
                    confirmed = o.change.values[LiveEdit.UP];
                    carry = true;
                    log.append("  COMMIT ").append(confirmed).append('\n');
                    break;
                case LiveLink.REFUSED:
                case LiveLink.NOT_SENT:
                    log.append("  REFUSED ").append(o.change.values[LiveEdit.UP]).append('\n');
                    break;
                case LiveLink.CONVERGE:
                    if (stopped || o.forIdx != planIdx) { link.cancelConverge(); break; }
                    log.append("  CONVERGE ").append(o.attempt)
                       .append(o.afterRefusal ? " after a refusal" : "").append('\n');
                    writeConfirmed(o.afterRefusal);
                    break;
                case LiveLink.CONVERGED:
                    log.append("  CONVERGED\n");
                    break;
                case LiveLink.GIVE_UP:
                    giveUps++;
                    lostUp = Math.max(lostUp, o.change == null ? -1 : o.change.values[LiveEdit.UP]);
                    log.append("  GIVE_UP lost=").append(lostUp).append('\n');
                    break;
                case LiveLink.STOP:
                    stops++;
                    stopped = true;
                    link.cancelConverge();
                    pump.write(Proto.stop());
                    log.append("  STOP\n");
                    break;
                default:
                    if (o.token == null) orphans++;
            }
        }

        void run(long ms) {
            long end = now + ms;
            while (now < end) {
                now += 5;
                pump.tick(5);
                for (byte[] f : pump.drain()) {
                    if (f.length != 2 || (f[0] & 0xFF) != Proto.OP_START) continue;
                    boolean acked = f[1] == 0x01;
                    LiveLink.Outcome<Note> o = link.answer(acked, planIdx, now);
                    if (o.token != null) o.token.done = true;
                    log.append(String.format("t=%d ANS %s -> %s%n", now, acked ? "01" : "FD",
                        o.token == null ? "?" : "#" + o.token.id));
                    act(o);
                }
                for (int i = 0; i < notes.size(); i++) {
                    Note n = notes.get(i);
                    if (n.done || now - n.sentAt < WINDOW) continue;
                    n.done = true;
                    act(link.timedOut(n, planIdx, now));
                }
            }
        }

        /** The reviewer's breach, asked once everything is quiet. */
        String breach() {
            if (!link.mayWriteChange(now) && !link.notAnswering()) return null;   // not quiet
            if (pump.answersHeld() > 0) return null;
            if (link.notAnswering())
                return pumpUp() > Math.max(confirmed, lostUp)
                    ? "not answering: pump " + pumpUp() + " > may be " + Math.max(confirmed, lostUp)
                    : null;
            return pumpUp() > confirmed ? "pump " + pumpUp() + " > screen " + confirmed : null;
        }
    }

    /** One seed, as the reviewer ran it: thirty taps and steps, then twenty quiet seconds. */
    static String fuzz(long seed, boolean silent, int maxLate, long horizon, Run[] keep) {
        Run r = new Run(seed, silent, maxLate, horizon);
        if (keep != null) keep[0] = r;
        String b = null;
        for (int op = 0; op < 30 && b == null; op++) {
            int k = r.rnd.nextInt(10);
            if (k < 7) r.tap(18 + r.rnd.nextInt(14));
            else if (k < 9) r.nextStep();
            r.run(50 + r.rnd.nextInt(2500));
            b = r.breach();
            if (b != null) b = "op " + op + ": " + b;
        }
        if (b == null) { r.run(20000); b = r.breach(); }
        return b;
    }

    static final int SEEDS = 3000;

    @Test void thePumpNeverRunsAboveTheScreenWithAnswersLateLostOrRefused() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            Run[] keep = new Run[1];
            String b = fuzz(seed, false, 2600, HORIZON, keep);
            assertNull(b, "seed " + seed + ": " + b + "\n" + keep[0].log);
        }
    }

    @Test void norWhenAStartIsRefusedWithItsFdLost() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            Run[] keep = new Run[1];
            String b = fuzz(seed, true, 2600, HORIZON, keep);
            assertNull(b, "seed " + seed + " (silent): " + b + "\n" + keep[0].log);
        }
    }

    /* ============================================ the reviewer's own harness, as it was */

    /**
     * scratchpad/refusal-rev2/h/Fuzz.java, ported line for line: the same draws in the same
     * order, the same abstract pump (a START's value is what it runs; answers in order), the same
     * glue and the same breach. Only the pairing's API moved: an answer no longer names a
     * tombstone that ate it; a refused converging START asks for the table first (here a table
     * rewrite is only the call that says one was written); and STOP stops.
     */
    static final class Reviewer {
        static final class N { int id; long sentAt; boolean done; int value; String kind; }
        static final class Ans { long at; boolean ack; int forId;
            Ans(long a, boolean k, int f) { at = a; ack = k; forId = f; } }
        int pumpUp = 24; java.util.ArrayDeque<Ans> answers = new java.util.ArrayDeque<Ans>();
        long lastAnsAt = 0;
        final LiveLink<N> link;
        List<N> notes = new ArrayList<N>();
        int confirmed = 24, lostUp = -1, planIdx = 3, ids = 0; long now = 0;
        StringBuilder log = new StringBuilder();
        Random rnd; final int maxLate; final boolean allowSilentNoExec;
        boolean stopped;

        Reviewer(long seed, boolean silentNoExec, int maxLate, long horizon) {
            rnd = new Random(seed); allowSilentNoExec = silentNoExec; this.maxLate = maxLate;
            link = new LiveLink<N>(horizon, UNSENT, 16);
        }

        int[] nextFate() {
            int r = rnd.nextInt(100);
            int delay = rnd.nextInt(10) == 0 ? 700 + rnd.nextInt(maxLate) : 20 + rnd.nextInt(60);
            if (r < 70) return new int[]{1, 1, delay};
            if (r < 88) return new int[]{1, 0, 0};            // done, answer lost
            if (r < 96) return new int[]{0, 1, delay};        // refused FD
            if (r < 98) return new int[]{0, 0, 0};            // refused, FD lost
            return allowSilentNoExec ? new int[]{0, 0, 0} : new int[]{1, 0, 0};
        }

        N writeStart(int value, int changeSeq, boolean converge, String kind) {
            N n = new N(); n.id = ++ids; n.value = value; n.kind = kind;
            link.startQueued(n, changeSeq, converge, false, now);
            long wd = now + 5;
            link.written(n, wd); n.sentAt = wd;
            notes.add(n);
            int[] f = nextFate();
            if (kind.equals("step") && f[0] == 0) f = new int[]{1, f[1], f[2]};   // steps always run
            if (f[0] == 1) pumpUp = value;
            if (f[1] == 1) {
                long at = Math.max(lastAnsAt + 1, wd + f[2]);
                lastAnsAt = at;
                answers.add(new Ans(at, f[0] == 1, n.id));
            }
            log.append(String.format("t=%d W#%d %s %d fate=%s pump=%d%n", now, n.id, kind, value,
                java.util.Arrays.toString(f), pumpUp));
            return n;
        }

        boolean tap(int v) {
            if (!link.mayWriteChange(now)) return false;
            int[] vals = new int[5]; vals[0] = v;
            int seq = link.beginChange(PendingChange.EDIT, planIdx, vals, false, 0);
            writeStart(v, seq, false, "change");
            return true;
        }

        void nextStep(int v) {
            planIdx++; confirmed = v; lostUp = -1;
            writeStart(v, -1, false, "step");
        }

        void act(LiveLink.Outcome<N> o) {
            switch (o.action) {
                case LiveLink.COMMIT:
                    confirmed = o.change.values[0]; log.append("  COMMIT " + confirmed + "\n"); break;
                case LiveLink.REFUSED: case LiveLink.NOT_SENT:
                    log.append("  REFUSED " + o.change.values[0] + "\n"); break;
                case LiveLink.CONVERGE:
                    if (stopped || planIdx != o.forIdx) { link.cancelConverge(); break; }
                    log.append("  CONVERGE attempt " + o.attempt
                        + (o.afterRefusal ? " after a refusal" : "") + "\n");
                    if (o.afterRefusal) link.tableRewritten();
                    writeStart(confirmed, -1, true, "converge"); break;
                case LiveLink.CONVERGED: log.append("  CONVERGED\n"); break;
                case LiveLink.GIVE_UP:
                    lostUp = Math.max(lostUp, o.change == null ? -1 : o.change.values[0]);
                    log.append("  GIVE_UP lost=" + lostUp + "\n"); break;
                case LiveLink.STOP: pumpUp = 0; stopped = true; log.append("  STOP\n"); break;
                default: if (o.why != 0) log.append("  NONE why=" + o.why + "\n");
            }
        }

        void run(long ms) {
            long end = now + ms;
            while (now < end) {
                now += 5;
                while (!answers.isEmpty() && answers.peek().at <= now) {
                    Ans a = answers.poll();
                    LiveLink.Outcome<N> o = link.answer(a.ack, planIdx, now);
                    log.append(String.format("t=%d ANS %s (for #%d) -> %s%n", now,
                        a.ack ? "01" : "FD", a.forId, o.token == null ? "?" : "#" + o.token.id));
                    if (o.token != null) o.token.done = true;
                    act(o);
                }
                for (int i = 0; i < notes.size(); i++) {
                    N n = notes.get(i);
                    if (n.done || now - n.sentAt < WINDOW) continue;
                    n.done = true;
                    log.append(String.format("t=%d TIMEOUT #%d%n", now, n.id));
                    act(link.timedOut(n, planIdx, now));
                }
            }
        }

        String breach() {
            if (stopped) return null;                    // stopped and vented: nothing runs
            if (!link.mayWriteChange(now) && !link.notAnswering()) return null;   // not quiet
            if (!answers.isEmpty()) return null;
            if (link.notAnswering()) return pumpUp > Math.max(confirmed, lostUp)
                ? "deaf: pump " + pumpUp + " > may-be " + Math.max(confirmed, lostUp) : null;
            return pumpUp > confirmed ? "pump " + pumpUp + " > screen " + confirmed : null;
        }

        static String fuzz(long seed, boolean silent, int maxLate, long horizon,
                           StringBuilder[] log) {
            Reviewer f = new Reviewer(seed, silent, maxLate, horizon);
            String b = null;
            for (int op = 0; op < 30 && b == null; op++) {
                int k = f.rnd.nextInt(10);
                if (k < 7) f.tap(18 + f.rnd.nextInt(14));
                else if (k < 9) f.nextStep(18 + f.rnd.nextInt(14));
                f.run(50 + f.rnd.nextInt(2500));
                b = f.breach();
                if (b != null) b = "op " + op + ": " + b;
            }
            if (b == null) { f.run(20000); b = f.breach(); }
            if (log != null) log[0] = f.log;
            return b;
        }
    }

    @Test void theReviewersHarnessFindsNothingOverItsTwentyThousandSeeds() {
        // On refusal-2 it found 16 (x 2600) and 18 (silent 2600) - seeds 308, 910, 3205, 5604,
        // 6015, 8098, 10495, 10614, 11134, 12591, 14113, 15992, 16819, 18043, 18476, 19270.
        for (boolean silent : new boolean[]{ false, true }) {
            for (long seed = 1; seed <= 20000; seed++) {
                StringBuilder[] log = new StringBuilder[1];
                String b = Reviewer.fuzz(seed, silent, 2600, HORIZON, log);
                assertNull(b, "seed " + seed + (silent ? " (silent)" : "") + ": " + b + "\n"
                    + log[0]);
            }
        }
    }

    @Test void andItsBreachesComeBackWithRefusalTwosReach() {
        String first = null;
        for (long seed = 1; seed <= 20000 && first == null; seed++) {
            String b = Reviewer.fuzz(seed, false, 2600, SHIPPED_REACH, null);
            if (b != null) first = "seed " + seed + ": " + b;
        }
        assertNotNull(first, "the reviewer's harness must still be able to fail");
    }

    @Test void thePropertyCatchesAReachAsShortAsRefusalTwos() {
        // The same runs, with an answer's reach cut back to refusal-2's window and tombstone:
        // answers later than that are taken for a later START's, and the property must see it.
        String first = null;
        for (long seed = 1; seed <= 20000 && first == null; seed++) {
            String b = fuzz(seed, false, 2600, SHIPPED_REACH, null);
            if (b != null) first = "seed " + seed + ": " + b;
        }
        assertNotNull(first, "a property that cannot fail proves nothing");
    }

    /* ======================================== the answers the horizon cannot place */

    @Test void anAnswerLaterThanTheHorizonEndsWithThePumpAtTheScreen() {
        // Seed 910's shape, later still: a step's `01` comes 6 s after its START, past the
        // horizon; a lowering to 20 was written meanwhile and REFUSED, its FD after the late 01.
        // The late 01 lands where the lowering's own would: it is taken for it - that far past
        // the horizon nothing can tell them apart - and the FD after it is an orphan. The orphan
        // is doubt, and doubt converges on what the screen shows: the pump ends at the screen's
        // figure, not above it.
        Run r = new Run(1, false, 2600, HORIZON);
        r.run(100);
        r.fate = null;
        final long[] delays = { 6000, 50 };
        final int[] k = { 0 };
        r.pump.setReplyDelay(new SimPump.ReplyDelay() {
            @Override public long delayMs(int op, int status) {
                return op == Proto.OP_START && k[0] < delays.length ? delays[k[0]++] : 20;
            }
        });
        r.pump.setRefusal(new SimPump.Refusal() {
            @Override public boolean refuse(int slot) { return slot == STEP_BATCH && k[0] == 1; }
        });
        r.pump.setReplyLoss(null);
        // The step's START, answered 6 s late.
        r.planIdx++;
        r.confirmed = r.steps.get(r.planIdx);
        Note step = new Note(90, "step", r.confirmed, r.now);
        r.link.startQueued(step, -1, false, false, r.now);
        r.link.written(step, r.now);
        r.notes.add(step);
        r.pump.write(Proto.startSlot(r.planIdx - r.base));
        r.run(HORIZON + 200);
        assertTrue(r.link.mayWriteChange(r.now), "past the horizon: quiet");
        int before = r.confirmed;
        int lower = before - 3;
        int seq = r.link.beginChange(PendingChange.EDIT, r.planIdx, LiveLinkTest.tuple(lower), false, 0);
        r.writeOverride(lower);
        Note c = new Note(91, "change", lower, r.now);
        r.link.startQueued(c, seq, false, false, r.now);
        r.link.written(c, r.now);
        r.notes.add(c);
        r.pump.write(Proto.startSlot(STEP_BATCH));
        r.run(12000);
        assertEquals(r.confirmed, r.pumpUp(), "the pump ends at what the screen shows\n" + r.log);
        assertFalse(r.link.converging());
    }

    @Test void anOrphanAnswerMovesNothingOnItsOwn() {
        LiveLink<String> l = new LiveLink<String>(HORIZON, UNSENT, 16);
        LiveLink.Outcome<String> o = l.answer(true, 3, 100);
        assertNull(o.token);
        assertEquals(LiveLink.CONVERGE, o.action, "when in doubt, converge");
        assertEquals(3, o.forIdx);
        assertFalse(o.afterRefusal, "an ack in doubt: no table rewrite");
        l.cancelConverge();
        o = l.answer(false, 3, 200);
        assertEquals(LiveLink.CONVERGE, o.action);
        assertTrue(o.afterRefusal, "an FD in doubt: the table first");
    }
}
