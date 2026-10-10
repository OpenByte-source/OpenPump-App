package org.openpump;

import java.util.ArrayList;
import java.util.List;

/** Reads confirmed command intervals; it does not infer measured pressure plateaus. */
final class GrowthTrackTiming {
    static final class Span {
        final AsRun.Row row; final long workMs, holdMs;
        Span(AsRun.Row row, long workMs, long holdMs) { this.row = row; this.workMs = workMs; this.holdMs = holdMs; }
    }
    static List<Span> read(AsRun run, Model.AsRunSnapshot snap, boolean length) {
        List<Span> out = new ArrayList<Span>();
        List<Model.Preset> planned = Model.planFromSnapshot(snap);
        long carryHold = 0, carryWork = 0;
        AsRun.Row previous = null, carryRow = null;
        for (AsRun.Row row : run.rows) {
            if (row.kind == AsRun.SKIPPED) continue;
            if (!length && row.span() > 0 && (row.kind == AsRun.HOLD || row.kind == AsRun.LOST))
                throw unknown("a pause or lost link does not retain the pressure-phase timeline");
            if (!row.hasValues() || row.span() == 0 || !row.confirmed || row.up <= 0) {
                if (carryHold > 0) throw unknown("a stitch continuation is missing or unconfirmed");
                continue;
            }
            if (length) { out.add(new Span(row, row.span(), 0)); continue; }
            if (row.uhWire <= 0 || row.lh < 0) throw unknown("a positive upper hold is unavailable");
            int part = part(row, planned);
            if (part < 0 && row.uhWire == Proto.WIRE_HOLD_MAX && row.lh == 1 && row.lo == row.up - 1)
                throw unknown("this older wire-sized hold has no verified stitch boundary");
            if (carryHold > 0 && (previous == null || row.t0 != previous.t1 || row.up != previous.up
                || row.stageIdx != previous.stageIdx || row.pos != previous.pos || !row.setId.equals(previous.setId)
                || row.ordinal != previous.ordinal + 1 || row.kind != AsRun.PLAN))
                throw unknown("a stitched hold was interrupted or changed");
            if (part == 1) {
                if (row.kind != AsRun.PLAN || row.uhWire != Proto.WIRE_HOLD_MAX || row.lh != 1
                    || row.lo != row.up - 1 || row.span() > row.uhWire * 1000L)
                    throw unknown("the delivered stitch no longer matches its recorded boundary");
                if (carryRow == null) carryRow = row;
                carryHold += row.span(); carryWork += row.span(); previous = row;
                continue;
            }
            long upper = row.uhWire * 1000L, release = row.lh * 1000L;
            if (release == 0) {
                // With no release the positive command is continuous across device repeats.
                out.add(new Span(carryRow == null ? row : carryRow, carryWork + row.span(), carryHold + row.span()));
            } else {
                long remaining = row.span();
                while (remaining > 0) {
                    long held = Math.min(remaining, upper);
                    long work = Math.min(remaining, upper + release);
                    out.add(new Span(carryRow == null ? row : carryRow, carryWork + work, carryHold + held));
                    carryHold = 0; carryWork = 0; carryRow = null;
                    remaining -= work;
                }
            }
            carryHold = 0; carryWork = 0; carryRow = null; previous = row;
        }
        // A stopped final chunk retains only its recorded, clipped hold duration.
        if (carryHold > 0) out.add(new Span(carryRow, carryWork, carryHold));
        return out;
    }
    private static int part(AsRun.Row row, List<Model.Preset> plan) {
        if (row.cyclePart >= 0) return row.cyclePart;
        if (row.kind != AsRun.PLAN) return -1;
        for (Model.Preset p : plan) if (p.stageIdx == row.stageIdx && p.pos == row.pos && p.ordinal == row.ordinal
            && p.setId.equals(row.setId) && p.uh == row.uhWire && p.uh == row.uhReq && p.lh == row.lh)
            return p.cyclePart ? 1 : 0;
        return -1;
    }
    private static IllegalArgumentException unknown(String reason) {
        return new IllegalArgumentException("Automatic technique unavailable: " + reason + ". This recording cannot be sent.");
    }
}
