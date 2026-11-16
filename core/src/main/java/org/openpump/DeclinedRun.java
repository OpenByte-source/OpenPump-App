package org.openpump;

import java.util.List;

/**
 * A RUN THE APP LOST, NOT REJOINED, IS A SESSION STOPPED EARLY (run 4 of the live GrowthTrack
 * test, 10 Nov 2026, check 4; the owner's decision).
 *
 * A process killed mid-run files nothing; the run snapshot keeps what it had played
 * ({@link RunParts#sofar}) and the run its rows, under its provisional key. "Neither" and "Stop
 * the pump" filed nothing either, so the work was lost from History and from GrowthTrack. Now
 * the snapshot's part is filed as a session stopped early, as STOP files one nobody counted:
 * its played time on the recording's clock ({@link RunParts#foldTime}), its figures, its rows,
 * any earlier parts folded in and their filed record replaced ({@link RunParts#dropEarlier}).
 * The screen files it once, when the offer is put; a rejoin then replaces it with the one
 * session, as it replaces the part a destroyed screen filed ({@link #learn}). A run no rejoin
 * is offered for - a manual run, its routine deleted or edited shorter, the snapshot too old -
 * is filed the same way, on the next start, under the routine {@link #unoffered} names.
 *
 * Bookkeeping only: nothing here reaches the pump or waits for anything.
 */
public final class DeclinedRun {
    private DeclinedRun() { }
    /** Copy only the trusted local run-snapshot marker before filing or export. */
    public static void restoreImportedMethod(Model.Sess record, boolean importedStatic) {
        if (record != null && importedStatic) record.gtImportedMethod = "static_pumping";
    }

    /** The record that already holds everything the snapshot played, or null: the part a
     *  destroyed screen filed (the snapshot learnt it), or one this filed before its
     *  snapshot's clearing was lost with the process - a manual run's too, which learns no id:
     *  the record that starts when it did. Then nothing more is filed. */
    public static Model.Sess covering(List<Model.Sess> log, RunParts sofar) {
        if (log == null || sofar == null) return null;
        long sec = sofar.playedMs / 1000L;
        for (Model.Sess s : log) {
            if (s == null || s.durSec < sec) continue;
            if (sofar.sessId != null && sofar.sessId.length() > 0 && sofar.sessId.equals(s.id)) return s;
            if (sofar.startTs > 0 && s.ts == sofar.startTs) return s;
        }
        return null;
    }

    /** The routine a run no rejoin is offered for is filed under (the owner's decision, 11 Nov
     *  2026): a manual run's ephemeral one, the routine as it stands (too old to offer, or
     *  edited shorter), or - deleted - none, named as fileSession names it. */
    public static Model.Routine unoffered(Model m, String routineId, boolean manual) {
        if (manual) return Manual.routine(m.ceilKpa);
        Model.Routine r = m.routine(routineId);
        if (r != null) return r;
        Model.Routine gone = new Model.Routine();
        gone.id = "";
        gone.name = "(routine deleted)";
        return gone;
    }

    /** The earlier parts' filed record, which the one session replaces; null when none. */
    public static Model.Sess earlier(List<Model.Sess> log, RunParts sofar) {
        if (log == null || sofar == null || sofar.sessId == null || sofar.sessId.length() == 0) return null;
        for (Model.Sess s : log) if (s != null && sofar.sessId.equals(s.id)) return s;
        return null;
    }

    /** The rows the run persisted, closed where the snapshot had counted - as a rejoin closes
     *  them (AsRun#join) - so they end inside the filed duration. A copy; null stays null. */
    public static AsRun closed(AsRun rows, long playedMs) {
        if (rows == null) return null;
        AsRun out = new AsRun(rows.routineId, rows.routineName, rows.snap, rows.manual, rows.ceilKpa);
        out.ts = rows.ts;
        out.manualSet = rows.manualSet;
        long off = Math.max(0L, playedMs);
        for (AsRun.Row r : rows.rows) {
            AsRun.Row c = r.copy();
            if (c.t0 > off) c.t0 = off;
            if (c.t1 < 0L || c.t1 > off) c.t1 = Math.max(c.t0, off);
            out.rows.add(c);
        }
        return out;
    }

    /** The offer's figures learn the record filed for them, so a rejoin replaces it. */
    public static void learn(RunParts sofar, Model.Sess rec) {
        if (sofar == null || rec == null) return;
        sofar.sessId = rec.id;
        if (sofar.syncId == null || sofar.syncId.length() == 0) sofar.syncId = rec.syncId != null
            && rec.syncId.length() > 0 ? rec.syncId : rec.id;
    }

    /**
     * The session stopped early, built from the snapshot (`sofar`, before the offer counts a
     * rejoin) and the rows `closed` returned; null when it played nothing - under a second, or
     * no rows. `lastAt` is when the run last wrote its snapshot (0 unknown), `presetsDone` the
     * steps reached. Not filed here: the screen files it, with the taper's step, the filing
     * marker and a sync, and leaves the routine's done count and the measurement cadence alone,
     * as for any STOP.
     */
    public static Model.Sess record(Model m, Model.Routine r, RunParts sofar, Model.Sess earlier,
                                    AsRun rows, long lastAt, long now, boolean sim, String shape,
                                    int presetsDone, int presetsPlanned) {
        if (m == null || r == null || sofar == null || rows == null || rows.rows.isEmpty()) return null;
        Model.Sess rec = new Model.Sess();
        rec.id = "sess" + now;
        rec.ts = now;
        rec.durSec = 0;
        // The parts' played time on the recording's clock, the first part's start and id.
        RunParts.foldTime(sofar, rec, 0L);
        if (rec.durSec < 1) return null;
        if (rec.recordedStartMs <= 0) rec.recordedStartMs = rec.ts;
        rec.recordedEndMs = Math.max(lastAt, rec.recordedStartMs + sofar.playedMs);
        rec.completed = false;
        rec.manual = Manual.ID.equals(r.id);      // a manual run's own routine (#unoffered)
        rec.stopWhy = RunStopReason.WHY_APP_CLOSED;
        rec.routineId = r.id;
        rec.routineName = r.name;
        rec.presetsDone = Math.max(0, presetsDone);
        rec.presetsPlanned = Math.max(0, presetsPlanned);
        rec.dayKey = PhotoCalendar.dayKey(rec.ts);
        RunParts.foldFigures(sofar, rec);
        // As the snapshot counted it: a plan session against its floor, anything else against
        // each step's own pull, with what it would be worth counted beside it.
        boolean own = r.trainerTrack == Model.TRAINER_TRACK_NONE;
        rec.tupOwnPull = own && rec.netTupSec != null;
        if (own && !Double.isNaN(sofar.worthSec)) rec.countWorthSec = Double.valueOf(sofar.worthSec);
        double countFromKpa = 0.0;
        if (!own) {
            double floorKpa = m.scoringFloorKpa(r, rec.ts);
            if (!Double.isNaN(floorKpa)) countFromKpa = floorKpa - m.tupCountTolKpa(floorKpa);
        }
        // A first part the person counted keeps its count; the rest is not asked about.
        if (earlier != null && earlier.countedTrack != Model.TRAINER_TRACK_NONE) {
            rec.countedTrack = earlier.countedTrack;
            rec.netTupSec = earlier.netTupSec;
            rec.tupOwnPull = false;
        }
        PlannedTime.stamp(m, r, rec, countFromKpa);
        RunParts.carryBefore(earlier, rec);
        rec.tag = rec.manual ? Summary.manualTag(false) : Summary.tag(false, null, null, false);
        rec.sim = sim;
        if (sim) rec.tag = "SIMULATED  ·  " + rec.tag;
        if (r.netTargetMin >= 0.0) rec.netTargetMin = Double.valueOf(r.netTargetMin);
        if (r.trainerTrack != Model.TRAINER_TRACK_NONE) rec.scaleKpa = r.trainerScaleKpa;
        if (r.netTargetMin > 0.0) rec.climbUnderLineMin = Model.clampClimbUnder(r.climbUnderLineMin);
        rec.shape = shape == null || rec.manual ? "" : shape;
        rec.returnDay = !rec.manual && Deload.reducedOn(m, rec.ts);
        rec.hasAsRun = true;
        rec.asRunSnapshot = rows.snap;
        try {
            rec.differs = AsRun.differs(AsRun.blocks(rows, m));
        } catch (RuntimeException e) {
            rec.differs = false;     // the label only; the session is filed without it
        }
        return rec;
    }
}
