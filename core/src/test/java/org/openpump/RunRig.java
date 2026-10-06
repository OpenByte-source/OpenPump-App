package org.openpump;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A RUN, ON THE SIMULATED PUMP, MADE OF THE RUN SCREEN'S OWN DECISIONS - and no kinder than the
 * Activity that carries them out.
 *
 * Everything the run screen DECIDES comes from RunControl and LiveLink - the same objects
 * SessionActivity calls: what an answer or a silence does, what a convergence writes, whether
 * the table is written first, the stamp of a START posted behind it and what a stale one does,
 * whether an edit of the table may go, a Revert, a Resume, a rest's end, a reconnect, the hold's
 * limit, what the pump may be running. What is left here is what the Activity only CARRIES OUT,
 * the way it does (the review of refusal-4: a rig whose revert cleared the carry itself hid a
 * Revert that left the pump at 30 kPa under a screen saying 24):
 *   - the carry is ovStage (carryOn, for its stage) apart from the carried values, which a
 *     Revert does not clear; armCarry and armRoutineSlot refuse what they refuse in the app;
 *   - the table as uploadBatch writes it (a batch from a step, never past a rest, the override
 *     entry after it), rewriteTableKeepingCountdown's re-posted Advance;
 *   - the inserted rest (a watched STOP; its end resumes after the vent, the table first);
 *   - +30 s's refresh of the carried entry; the Hold with its limit;
 *   - the link lost: nothing commanded, the sheets closed, the auto-stop after AUTO_STOP_MS
 *     (a watched STOP nothing but STOP or its vent may settle), the reconnect's three choices.
 * SimPump's link carries frames out one at a time; its answers come late, never, or as
 * `2C FD`; and its table can be one Add short (#loseAdd), as the owner's pump's was.
 *
 * #breach says whether the pump did what the screen says it may not: pulls above what the screen
 * shows once things are quiet, or above the "may be at" figure while not answering; runs in a rest
 * after its STOP, or after the run stopped; holds with no limit armed; took a START while the
 * link-loss auto-stop's STOP was unsettled; or had its table written by an edit while a START
 * could still be answered.
 */
final class RunRig implements RunControl.Run<RunRig.Note> {

    static final long WINDOW = 800, HORIZON = 5000, UNSENT = 15000, FRAME_MS = 20, TICK = 10;
    static final long LIMIT_MS = 15000, AUTO_STOP_MS = 6000;
    static final int BATCH = 4;

    static final class Note {
        final int id; final String kind; final int up;
        long sentAt = -1;
        boolean done;
        Note(int id, String kind, int up) { this.id = id; this.kind = kind; this.up = up; }
        @Override public String toString() { return "#" + id + " " + kind + " " + up; }
    }

    /** A step of the plan; steps of one stage share a carried adjustment. */
    static final class Step {
        int up; final boolean rest; final long durMs; int stage;
        Step(int up, boolean rest, long durMs) { this(up, rest, durMs, -1); }
        Step(int up, boolean rest, long durMs, int stage) {
            this.up = up; this.rest = rest; this.durMs = durMs; this.stage = stage;
        }
    }

    /** The fate of a START as the pump carries it out: {executes, answered, delayMs}. */
    interface Fates { int[] next(Note n); }

    private static final class Job {
        long at; final Runnable r; final long seq;
        Job(long at, Runnable r, long seq) { this.at = at; this.r = r; this.seq = seq; }
    }

    final SimPump pump = new SimPump();
    final LiveLink<Note> link = new LiveLink<Note>(HORIZON, UNSENT, 16);
    final RunControl<Note> ctl = new RunControl<Note>(link, this);
    final List<Step> steps;
    final List<Job> jobs = new ArrayList<Job>();
    final ArrayDeque<Note> inFlight = new ArrayDeque<Note>();
    final List<Note> notes = new ArrayList<Note>();
    final StringBuilder log = new StringBuilder();
    Fates fates;
    long now, jobSeq;
    int ids;
    int planIdx = 0, batchBase = -1, batchCount = 0;
    boolean running = true, stopped, resting, restingNow, restRearmPending, ventWatch,
        overrideInTable, settling;
    /** ovStage != -1 (carryOn, for carryStage), and the carried values, which outlive it. */
    boolean carryOn;
    int carryStage = -1, carryUp = -1;
    boolean holding;
    int holdAt;
    long limitEndsAt;
    Runnable advance, restEnd;
    int[] fate;
    long stopCarriedAt = -1;
    /** The link: lost (the Link Lost screen), the auto-stop's STOP out and unsettled, the
     *  reconnect prompt up. */
    boolean linkLost, autoStopWatch, awaitingChoice;
    /** This loss's auto-stop fired (lostAutoStopStarted): a Resume puts the step back as
     *  planned, with no carried adjustment. */
    boolean autoStopFired;
    long lostAt;
    /** The Adds (counted from the run's first, 1-based) the pump answers and does not store -
     *  its table one entry short, as the owner's was. */
    final java.util.Set<Integer> lostAdds = new java.util.HashSet<Integer>();
    int adds;
    /** Edits of the table that bypass the gate (a test of the stale stamp alone). */
    boolean ungatedEdits;
    /** The person can reach only what the screen shows: no adjust sheet over a rest or Link
     *  Lost (closeRunSheets). False: RunControl alone must refuse. */
    boolean uiReach = true;
    /** The breaches are judged (false while a test's table is shifted and nothing has shown
     *  it yet - no design can see an ACK of the wrong entry). */
    boolean checking = true;
    String breach;
    int converges, giveUps, pumpStops, limitStops, staleStarts, editsRefused, editsDone, holds,
        reverts, revertsRefused, restsInserted, extendsDone, losses, autoStops, resumesAfterStop;

    RunRig(List<Step> steps, Fates fates) {
        this.steps = steps;
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).stage < 0) steps.get(i).stage = i;
        this.fates = fates;
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
        pump.setAddLoss(new SimPump.AddLoss() {
            @Override public boolean lose(byte[] frame) {
                if (!lostAdds.contains(++adds)) return false;
                say("the pump did not store Add " + adds + ": its table is one entry short");
                return true;
            }
        });
        pump.setFrameMs(FRAME_MS);
        pump.setOnCarriedOut(new SimPump.CarriedOut() {
            @Override public void carriedOut(byte[] f) {
                int op = f[2] & 0xFF;
                if (op == Proto.OP_START) {
                    Note n = inFlight.pollFirst();
                    fate = n == null ? null : RunRig.this.fates.next(n);
                    if (n != null) { n.sentAt = now; link.written(n, now); }
                    say("carried out START " + n + (fate == null ? "" : " fate=" + fate[0] + "/"
                        + fate[1] + "/" + fate[2]));
                } else if (op == Proto.OP_STOP) {
                    stopCarriedAt = now;
                }
            }
        });
        // The run begins as the Activity's does: the first batch, the settle, the first step.
        upload(0);
        settling = true;
        schedAdvance(0, RunControl.SETTLE_MS);
    }

    /** The Add `n` of the run (1-based, as the pump carries them out) is answered and not
     *  stored. */
    void loseAdd(int n) { lostAdds.add(n); }

    /* ================================================================ RunControl.Run */

    @Override public int planIdx() { return planIdx; }
    @Override public boolean live() { return running && !stopped; }
    /** canCommandNow: no rest, no inserted rest, not over Link Lost or the reconnect prompt. */
    @Override public boolean commandable() {
        return live() && !resting && !restingNow && !linkLost && !awaitingChoice;
    }
    @Override public boolean resting() { return resting || restingNow; }
    @Override public boolean holding() { return holding; }
    @Override public boolean stepIsRest() { return steps.get(planIdx).rest; }
    /** overrideCarriesNow: ovStage names the stage playing now. */
    @Override public boolean carries() { return carryOn && carryStage == steps.get(planIdx).stage; }

    @Override public int routineSlot() {
        return planIdx >= batchBase && planIdx < batchBase + batchCount ? planIdx - batchBase : -1;
    }

    @Override public int confirmedUp() { return carries() ? carryUp : steps.get(planIdx).up; }

    @Override public void rewriteTable(int from) {
        say("rewrite the table from step " + from);
        rewriteKeeping(from);
    }

    /** armRoutineSlot: refused where the app refuses it. */
    @Override public boolean startSlot(int slot, int kind, String why) {
        if (!live() || !commandable() || steps.get(planIdx).rest) return false;
        int up = slot >= 0 && slot < batchCount ? steps.get(batchBase + slot).up : -1;
        String k = kind == RunControl.CONVERGE ? "converge" : kind == RunControl.RESUME ? "resume"
            : "revert";
        return writeStart(k, slot, -1, kind == RunControl.CONVERGE, up) != null;
    }

    /** armCarry: nothing when the adjustment no longer carries (the review of refusal-4). */
    @Override public boolean startCarry(int kind, String why) {
        if (!live() || !commandable() || !carries()) return false;
        writeOverride(carryUp, 30);
        return writeStart(kind == RunControl.CONVERGE ? "converge" : "resume",
            RunEdit.overrideSlotAt(batchCount), -1, kind == RunControl.CONVERGE, carryUp) != null;
    }

    @Override public void post(Runnable r, long ms) { jobs.add(new Job(now + ms, r, jobSeq++)); }

    @Override public boolean putInForce(LiveLink.Outcome<Note> o) {
        PendingChange.Change c = o.change;
        if (c.kind == PendingChange.HOLD) {
            if (!commandable()) { say("hold acked as the run moved on"); return false; }
            holding = true;
            holdAt = c.holdAt;
            ctl.holdConfirmed();
            if (limitEndsAt == 0) limitEndsAt = now + LIMIT_MS;
            say("HOLDING at " + holdAt);
            return true;
        }
        carryUp = c.values[LiveEdit.UP];
        carryOn = true;
        carryStage = steps.get(planIdx).stage;
        say("COMMIT " + carryUp);
        return true;
    }

    @Override public void notTaken(LiveLink.Outcome<Note> o) {
        PendingChange.Change c = o.change;
        say((o.action == LiveLink.REFUSED ? "REFUSED " : "NOT SENT ")
            + (c.kind == PendingChange.HOLD ? "hold " + c.holdAt : "" + c.values[LiveEdit.UP]));
    }

    @Override public void holdLimitGoes(String why) {
        if (limitEndsAt != 0) say("the hold's limit goes: " + why);
        limitEndsAt = 0;
    }

    @Override public void stopRun() {
        pumpStops++;
        say("STOP: the pump refused what was confirmed on a fresh table");
        stop();
    }

    @Override public void carryEnds() { carryOn = false; say("the carry ends"); }

    @Override public void holdDown() { holding = false; say("HOLDING comes down"); }

    @Override public void resuming(String why) { say("resume: " + why); }

    @Override public void tell(int what, LiveLink.Outcome<Note> o, String detail) {
        switch (what) {
            case RunControl.SAY_CONVERGING: converges++; say("CONVERGE " + (o == null ? "" : o.attempt
                + (o.afterRefusal ? " table first" : ""))); break;
            case RunControl.SAY_GIVE_UP: giveUps++; say("GIVE_UP"); break;
            case RunControl.SAY_NOT_WRITTEN: staleStarts++; say("posted START not written: " + detail); break;
            default: say("tell " + what + (detail == null ? "" : " " + detail));
        }
    }

    /* ================================================================ the Activity's writes */

    /** Over a lost link nothing arrives; the frame is dropped (PumpLink.close). */
    void write(byte[] f) {
        if (linkLost) { say("frame dropped: the link is down"); return; }
        pump.write(f);
    }

    void upload(int from) {
        for (int i = 0; i < Proto.SLOTS; i++) write(Proto.deleteSlot(0));
        batchBase = from;
        batchCount = 0;
        for (int k = from; k < steps.size() && batchCount < BATCH && !steps.get(k).rest; k++) {
            Step s = steps.get(k);
            write(Proto.addPreset(75, s.up, 30, Math.max(1, s.up - 5), 5));
            batchCount++;
        }
        overrideInTable = false;
        ctl.tableWritten();
    }

    /** rewriteTableKeepingCountdown: the table from `from`, and the Advance to the next step put
     *  back for the time the pending one had left. */
    void rewriteKeeping(int from) {
        long at = -1;
        for (int i = 0; i < jobs.size(); i++) if (jobs.get(i).r == advance) at = jobs.get(i).at;
        upload(from);
        if (at >= 0) {
            for (int i = 0; i < jobs.size(); i++) if (jobs.get(i).r == advance) { jobs.remove(i); break; }
            schedAdvance(planIdx + 1, Math.max(RunEdit.MIN_REPOST_MS, at - now));
        }
    }

    void writeOverride(int up, int uh) {
        if (overrideInTable) write(Proto.deleteSlot(batchCount));
        write(Proto.addPreset(75, up, uh, Math.max(1, up - 1), uh == 255 ? 1 : 5));
        overrideInTable = true;
    }

    /** sendStartSlot: never while the link-loss auto-stop's STOP is unsettled (SAFETY
     *  invariant 4, WiringCheck 158); otherwise it cancels the vent watch as it arms. */
    Note writeStart(String kind, int slot, int changeSeq, boolean converge, int up) {
        if (autoStopWatch) {
            say("START refused: the link-loss auto-stop's STOP is unsettled");
            return null;
        }
        ventWatch = false;
        Note n = new Note(++ids, kind, up);
        link.startQueued(n, changeSeq, converge, false, up, now);
        notes.add(n);
        if (linkLost) {
            say("START " + n + " dropped: the link is down");
            link.neverWritten(n, planIdx);
            n.done = true;
            return n;
        }
        inFlight.addLast(n);
        pump.write(Proto.startSlot(slot));
        say("write START slot " + slot + " " + n);
        return n;
    }

    /** finishSession: STOP's path - the stop, watched; the run over. */
    void stop() {
        pump.write(Proto.stop());
        stopCarriedAt = -1;
        stopped = true; running = false; holding = false; limitEndsAt = 0;
        autoStopWatch = false;
        awaitingChoice = false;
        ctl.runEnded();
    }

    /* ================================================================ the sequencing */

    void schedAdvance(final int idx, long ms) {
        Runnable a = new Runnable() {
            @Override public void run() { if (this != advance) return; playPreset(idx); }
        };
        advance = a;
        post(a, ms);
    }

    /** exitHold: RunControl releases it, or stands it down. */
    void exitHold(boolean resume) {
        if (!holding) {
            if (!resume && link.holdUnsettled()) ctl.holdReleased();
            return;
        }
        if (resume) ctl.release("hold"); else ctl.standDown();
    }

    /** endInsertedRest: its end resumes through RunControl - after the vent, the table first -
     *  but never onto a lost link. */
    void endInsertedRest(boolean resume) {
        if (!restingNow) return;
        restingNow = false;
        ctl.restChanged();
        restEnd = null;
        if (!resume || !running || linkLost || awaitingChoice) return;
        say("inserted rest ends: resume");
        ctl.resume("rest", true);
    }

    void playPreset(int idx) {
        if (stopped || linkLost) return;
        settling = false;
        ctl.stepChanged();
        if (ctl.holdMayBeUp()) exitHold(false);
        endInsertedRest(false);
        if (idx >= steps.size()) { say("END"); stop(); return; }
        Step s = steps.get(idx);
        if (idx >= batchBase + batchCount && !s.rest) {
            say("the table first (a new batch)");
            upload(idx); settling = true; schedAdvance(idx, RunControl.SETTLE_MS); return;
        }
        if (!s.rest && !restRearmPending && ctl.tableFirst()) {
            say("the table first (in doubt)");
            upload(idx); settling = true; schedAdvance(idx, RunControl.SETTLE_MS); return;
        }
        planIdx = idx;
        boolean carries = carryOn && carryStage == s.stage;
        resting = s.rest;
        if (!s.rest && restRearmPending) {
            restRearmPending = false;
            say("the table first (out of a rest)");
            upload(idx); settling = true; schedAdvance(idx, RunControl.SETTLE_MS); return;
        }
        if (s.rest) {
            restRearmPending = true;
            carryOn = false;
            stopCarriedAt = -1;
            write(Proto.stop());
            ventWatch = true;
            say("REST: STOP, vent watch armed");
        } else if (carries) {
            writeOverride(carryUp, 30);
            writeStart("carry", RunEdit.overrideSlotAt(batchCount), -1, false, carryUp);
        } else {
            carryOn = false;
            writeStart("step", idx - batchBase, -1, false, s.up);
        }
        schedAdvance(idx + 1, s.durMs);
    }

    /* ================================================================ the person */

    boolean mayChange() { return commandable() && !ctl.holdMayBeUp() && ctl.mayWriteChange(now); }

    void tap(int up) {
        if (!mayChange()) return;
        int seq = link.beginChange(PendingChange.EDIT, planIdx, LiveLinkTest.tuple(up), false, 0);
        writeOverride(up, 30);
        writeStart("change", RunEdit.overrideSlotAt(batchCount), seq, false, up);
    }

    void hold() {
        if (!commandable() || link.holdUnsettled() || !ctl.mayWriteChange(now)) return;
        int at = Math.max(5, Math.min(40, (int) Math.round(pump.pressureKpa())));
        int seq = link.beginChange(PendingChange.HOLD, planIdx, null, false, at);
        writeOverride(at, 255);
        writeStart("hold", RunEdit.overrideSlotAt(batchCount), seq, false, at);
        limitEndsAt = now + LIMIT_MS;                      // bounded from the write
        ctl.holdWritten();
        holds++;
    }

    /** HoldTap's release: never refused - it is the way out of a hold. */
    void release() { if (holding) exitHold(true); }

    /** The adjust sheet's Revert: RunControl's to decide (revertOverride). */
    void revert() {
        if (!live()) return;
        if (uiReach && (restingNow || resting || linkLost || awaitingChoice)) return;
        String waits = ctl.revert(now);
        if (waits != null) { revertsRefused++; say("PERSON: revert - waits: " + waits); return; }
        reverts++;
        say("PERSON: revert");
    }

    /** RunScreen#insertRest: a watched STOP; the rest's end resumes. */
    void insertRest(long ms) {
        if (!commandable() || restingNow || resting) return;
        if (ctl.holdMayBeUp()) exitHold(false);
        restingNow = true;
        ctl.restChanged();
        restRearmPending = true;
        stopCarriedAt = -1;
        write(Proto.stop());
        ventWatch = true;
        restsInserted++;
        say("PERSON: rest inserted");
        final Runnable[] self = new Runnable[1];
        self[0] = new Runnable() {
            @Override public void run() { if (restEnd == self[0]) endInsertedRest(true); }
        };
        restEnd = self[0];
        post(self[0], ms);
    }

    /** +30 s: the carried entry refreshed - only when RunControl says the table may be. */
    void extend() {
        if (!live() || linkLost || awaitingChoice) return;
        boolean refresh = carries() && !ctl.holdMayBeUp();
        if (refresh && ctl.editWaits(now) != null) { editsRefused++; return; }
        extendsDone++;
        if (refresh && ctl.mayEditTable(now) && commandable()) {
            say("PERSON: +30 s refresh");
            writeOverride(carryUp, 30);
            writeStart("refresh", RunEdit.overrideSlotAt(batchCount), -1, false, -1);
        }
    }

    /** The routine offset (or Edit upcoming): the later steps change, the table is written from
     *  the next step - only when RunControl says it may. */
    void editTable(Random rnd, boolean all) {
        if (!commandable() || planIdx + 1 >= steps.size()) return;
        // As the run screen asks: the edit's sheet says why it waits (RunControl#editWaits), and
        // +30 s's refresh asks mayEditTable - the two are one gate.
        String waits = ctl.editWaits(now);
        if ((waits == null) != ctl.mayEditTable(now))
            fail("editWaits and mayEditTable disagree: " + waits);
        if (!ungatedEdits && waits != null) { editsRefused++; return; }
        // THE GATE'S OWN CONTRACT, asked independently of it: nothing may be answered, nothing
        // waits to be written, no convergence, no hold that may be up.
        if (!ungatedEdits && (!link.mayWriteChange(now) || postedWaiting() || holding
                || link.holdUnsettled()))
            fail("a table edit went out while a START could still be answered or was waiting");
        for (int k = planIdx + 1; k < steps.size(); k++) {
            if (steps.get(k).rest || (!all && k > planIdx + 1)) continue;
            steps.get(k).up = Math.max(12, Math.min(34, steps.get(k).up + rnd.nextInt(5) - 2));
        }
        editsDone++;
        say("PERSON: edit the table from step " + (planIdx + 1));
        rewriteKeeping(planIdx + 1);
    }

    /** The link drops: the Link Lost screen (every sheet closed), the step's and the rest's
     *  timers taken down, the auto-stop after AUTO_STOP_MS. */
    void loseLink() {
        if (!live() || linkLost || awaitingChoice) return;
        linkLost = true;
        autoStopFired = false;
        lostAt = now;
        losses++;
        for (int i = jobs.size() - 1; i >= 0; i--)
            if (jobs.get(i).r == advance || jobs.get(i).r == restEnd) jobs.remove(i);
        say("LINK LOST");
    }

    /** Telemetry is back: the reconnect prompt (the auto-stop's watch still running if its STOP
     *  has not been seen to vent - a STOP that reached nothing is sent again now). */
    void regainLink() {
        if (!linkLost) return;
        linkLost = false;
        awaitingChoice = true;
        if (autoStopWatch) { pump.write(Proto.stop()); stopCarriedAt = -1; say("auto-stop: STOP again"); }
        say("RECONNECTED: the prompt");
    }

    /** "Resume at step N" - offered only while no auto-stop's STOP is unsettled (after an
     *  auto-stop, once its vent is seen after reconnecting); RunControl writes what is
     *  confirmed, the table first. False when it is not offered. */
    boolean reconnectResume() {
        if (!awaitingChoice || autoStopWatch) return false;
        awaitingChoice = false;
        if (ctl.holdMayBeUp()) exitHold(false);
        endInsertedRest(false);
        say("PERSON: resume at step " + (planIdx + 1) + (autoStopFired ? " (after the auto-stop)" : ""));
        if (autoStopFired) resumesAfterStop++;
        ctl.reconnect(autoStopFired);
        schedAdvance(planIdx + 1, 4000);
        return true;
    }

    /** "End": STOP's path. */
    void reconnectEnd() { if (awaitingChoice) { say("PERSON: end"); stop(); } }

    boolean postedWaiting() {
        for (int i = 0; i < jobs.size(); i++)
            if (jobs.get(i).r instanceof RunControl.Posted) return true;
        return false;
    }

    /* ================================================================ time */

    void run(long ms) {
        long end = now + ms;
        while (now < end && breach == null) tick();
    }

    void tick() {
        now += TICK;
        pump.tick(TICK);
        // HOLDING and an inserted rest freeze the countdown (tickHold).
        if (holding || restingNow)
            for (int i = 0; i < jobs.size(); i++) if (jobs.get(i).r == advance) jobs.get(i).at += TICK;
        while (true) {
            Job due = null;
            for (int i = 0; i < jobs.size(); i++) {
                Job j = jobs.get(i);
                if (j.at <= now && (due == null || j.at < due.at || (j.at == due.at && j.seq < due.seq)))
                    due = j;
            }
            if (due == null) break;
            jobs.remove(due);
            due.r.run();
        }
        for (byte[] f : pump.drain()) {
            if (f.length != 2 || (f[0] & 0xFF) != Proto.OP_START) continue;
            if (linkLost) { say("answer lost: the link is down"); continue; }
            boolean acked = f[1] == 0x01;
            LiveLink.Outcome<Note> o = link.answer(acked, planIdx, now);
            say("ANSWER " + (acked ? "01" : "FD") + " -> " + (o.token == null ? "?" : o.token)
                + " action " + o.action);
            if (o.token != null) o.token.done = true;
            ctl.handle(o);
        }
        for (int i = 0; i < notes.size(); i++) {
            Note n = notes.get(i);
            if (n.done && n.sentAt >= 0 && now - n.sentAt > HORIZON) { notes.remove(i--); continue; }
            if (n.done || n.sentAt < 0 || now - n.sentAt < WINDOW) continue;
            n.done = true;
            LiveLink.Outcome<Note> o = link.timedOut(n, planIdx, now);
            if (o.action != LiveLink.NONE) say("WINDOW " + n + " action " + o.action);
            ctl.handle(o);
        }
        // A rest's watched vent: the fall evidenced.
        if ((resting || restingNow) && ventWatch && stopCarriedAt >= 0 && pump.pressureKpa() < 0.3) {
            ventWatch = false;
            say("vent evidenced");
            ctl.vented();
        }
        // The link-loss auto-stop: its STOP, watched; its vent seen ends the run (finishSession).
        if (linkLost && !autoStopWatch && now - lostAt >= AUTO_STOP_MS) {
            autoStopWatch = true;
            autoStopFired = true;
            autoStops++;
            say("AUTO-STOP: STOP sent, watched");
        }
        // Its vent seen after reconnecting: the watch is settled by the evidence and the prompt
        // offers Resume too (the owner's decision); nothing else settles it.
        if (autoStopWatch && !linkLost && stopCarriedAt >= 0 && pump.pressureKpa() < 0.3) {
            say("auto-stop: vent evidenced - Resume is offered");
            autoStopWatch = false;
            ctl.vented();
        }
        // The hold's limit (checkRunHoldLimit) - frozen, as everything, over Link Lost.
        if (!linkLost && InRunHold.limitReached(live(), ctl.holdMayBeUp(), false, limitEndsAt, now)) {
            limitStops++;
            say("the hold's limit: STOP");
            stop();
        }
        check();
    }

    /* ================================================================ the breaches */

    /** An answer no START written can be for - an orphan - handled as the run screen does. */
    void strayAnswer(boolean acked) {
        LiveLink.Outcome<Note> o = link.answer(acked, planIdx, now);
        say("STRAY ANSWER " + (acked ? "01" : "FD") + " -> action " + o.action);
        ctl.handle(o);
    }

    int pumpUp() { return pump.running() == null ? 0 : pump.running().upperKpa; }

    int screenUp() { return holding ? holdAt : (resting || restingNow) ? 0 : confirmedUp(); }

    /** No write, answer or START in the way, no pause, no hold that may be up (its limit bounds
     *  it), the link up: what the pump runs must be what the screen shows. */
    boolean quiet() {
        return live() && ctl.mayWriteChange(now) && !postedWaiting() && pump.inFlight() == 0
            && pump.answersHeld() == 0 && !resting && !restingNow && !link.holdUnsettled()
            && !settling && !linkLost && !awaitingChoice;
    }

    boolean noneWithinHorizon() {
        for (int i = 0; i < notes.size(); i++) {
            Note n = notes.get(i);
            if (n.sentAt < 0 || now - n.sentAt <= HORIZON) return false;
        }
        return pump.inFlight() == 0 && pump.answersHeld() == 0;
    }

    void check() {
        if (breach != null || !checking) return;
        SimPump.Slot run = pump.running();
        if ((resting || restingNow) && stopCarriedAt >= 0 && run != null && pump.inFlight() == 0)
            fail("a START runs in the rest: pump " + run.upperKpa);
        else if (stopped && stopCarriedAt >= 0 && run != null && pump.inFlight() == 0)
            fail("the run stopped and the pump runs " + run.upperKpa);
        else if (run != null && run.upperHoldS == 255 && limitEndsAt == 0 && !stopped)
            fail("the pump holds at " + run.upperKpa + " with no limit armed");
        else if (link.notAnswering() && live() && !linkLost && pumpUp() > ctl.mayBeUp())
            fail("not answering: pump " + pumpUp() + " > may be " + ctl.mayBeUp());
        else if (!link.notAnswering() && quiet() && pumpUp() > screenUp())
            fail("quiet: pump " + pumpUp() + " > screen " + screenUp());
    }

    void fail(String what) {
        if (breach == null) breach = "t=" + now + " " + what;
    }

    void say(String s) { log.append("t=").append(now).append(' ').append(s).append('\n'); }

    /* ================================================================ fates and plans */

    /** The reviewer's fates, with a step's, a revert's START refused only with its FD delivered
     *  (a silent refusal of those cannot be told from a lost answer, and no design could). */
    static Fates reviewerFates(final Random rnd, final boolean silent, final int maxLate) {
        return new Fates() {
            @Override public int[] next(Note n) {
                int r = rnd.nextInt(100);
                int delay = rnd.nextInt(10) == 0 ? 700 + rnd.nextInt(maxLate) : 20 + rnd.nextInt(60);
                int[] f;
                if (r < 70) f = new int[]{ 1, 1, delay };
                else if (r < 88) f = new int[]{ 1, 0, 0 };
                else if (r < 96) f = new int[]{ 0, 1, delay };
                else if (r < 98) f = new int[]{ 0, 0, 0 };
                else f = silent ? new int[]{ 0, 0, 0 } : new int[]{ 1, 0, 0 };
                boolean mayBeSilent = !n.kind.equals("step") && !n.kind.equals("revert");
                if (f[0] == 0 && f[1] == 0 && !mayBeSilent) f = new int[]{ 1, 0, 0 };
                return f;
            }
        };
    }

    /** A pump that never refuses: answers on time, late (up to 3.3 s) or not at all. A STOP of
     *  the pump's own there is a healthy run stopped for nothing. */
    static Fates healthyFates(final Random rnd) {
        return new Fates() {
            @Override public int[] next(Note n) {
                int k = rnd.nextInt(100);
                if (k < 75) return new int[]{ 1, 1, 20 + rnd.nextInt(60) };
                if (k < 88) return new int[]{ 1, 1, 700 + rnd.nextInt(2600) };
                return new int[]{ 1, 0, 0 };
            }
        };
    }

    /** Steps and rests; a stage runs one or more steps (a carried adjustment lasts for it). */
    static List<Step> plan(Random rnd, int n) {
        List<Step> steps = new ArrayList<Step>();
        int stage = 0;
        for (int i = 0; i < n; i++) {
            boolean rest = i > 0 && rnd.nextInt(100) < 12 && !steps.get(i - 1).rest;
            if (i > 0 && (rest || steps.get(i - 1).rest || rnd.nextInt(3) == 0)) stage++;
            steps.add(new Step(rest ? 0 : 18 + rnd.nextInt(14), rest,
                rest ? 3000 + rnd.nextInt(3000) : 3000 + rnd.nextInt(6000), stage));
        }
        return steps;
    }
}
