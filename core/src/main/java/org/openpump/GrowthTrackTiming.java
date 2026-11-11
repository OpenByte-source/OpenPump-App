package org.openpump;

import java.util.ArrayList;
import java.util.List;

/** Reads command intervals; it does not infer measured pressure plateaus. */
final class GrowthTrackTiming {
    /** Whole-row roles: a warm-up or traction row is one span, never split into cycles. */
    static final int WORK = 0, WARM = 1, LENGTH = 2;
    /**
     * A LEFTOVER SHORTER THAN ONE SECOND IS NOT A SET (bug 4). A step ends on
     * Handler.postDelayed, which is never early, and each row is restamped at its START ack,
     * so a row that ran a whole number of cycles is routinely some ms long. GrowthTrack's
     * durations are whole seconds; a sub-second remainder joins the cycle before it instead of
     * becoming a "milking" set of null seconds. A real interrupted last cycle (1 s or more)
     * still keeps its own clipped hold.
     */
    static final long LEFTOVER_MS = 999L;
    /** The app's own timer tolerance for a step ending late (bug 3: a 255 s stitch chunk is
     *  never exactly 255 000 ms on a device). */
    static final long STITCH_TOL_MS = AsRun.CUT_SHORT_TOL_MS;

    static final class Span {
        final AsRun.Row row; final long workMs, holdMs; final int role;
        Span(AsRun.Row row, long workMs, long holdMs, int role) { this.row = row; this.workMs = workMs; this.holdMs = holdMs; this.role = role; }
    }
    interface Roles { int of(AsRun.Row row); }

    static List<Span> read(AsRun run, Model.AsRunSnapshot snap, Roles roles, boolean includeUnconfirmed) {
        List<Span> out = new ArrayList<Span>();
        List<Model.Preset> planned = Model.planFromSnapshot(snap);
        long carryHold = 0, carryWork = 0;
        AsRun.Row previous = null, carryRow = null;
        int inPart = run.rows.isEmpty() ? 0 : run.rows.get(0).part;
        for (AsRun.Row row : run.rows) {
            if (row.kind == AsRun.SKIPPED) continue;
            // A STOP BETWEEN PARTS (AsRun#join) is a gap like a Hold: a hold the STOP cut is
            // sent as far as it got, and the part after Resume starts its own.
            if (row.part != inPart) {
                if (carryHold > 0) out.add(new Span(carryRow, carryWork, carryHold, WORK));
                carryHold = 0; carryWork = 0; carryRow = null; previous = null;
                inPart = row.part;
            }
            // A Hold or a Bluetooth dropout (bug 15) is a gap, like a rest: it ends any stitch
            // in progress (that hold is sent as far as it got) and is itself never work.
            if (row.span() > 0 && (row.kind == AsRun.HOLD || row.kind == AsRun.LOST || row.kind == AsRun.VENT)) {
                if (carryHold > 0) out.add(new Span(carryRow, carryWork, carryHold, WORK));
                carryHold = 0; carryWork = 0; carryRow = null; previous = null;
                continue;
            }
            // An unacknowledged write (bug 16) still ran in the common case ("the real pump
            // often carries a START out without answering it"), so it is work when the pump is
            // otherwise proven present; a session with no acknowledged row at all sends nothing.
            boolean usable = row.hasValues() && row.span() > 0 && (row.confirmed || includeUnconfirmed) && row.up > 0;
            if (!usable) {
                if (carryHold > 0) out.add(new Span(carryRow, carryWork, carryHold, WORK));
                carryHold = 0; carryWork = 0; carryRow = null; previous = null;
                continue;
            }
            int role = roles.of(row);
            if (role != WORK) {
                if (carryHold > 0) out.add(new Span(carryRow, carryWork, carryHold, WORK));
                carryHold = 0; carryWork = 0; carryRow = null;
                out.add(new Span(row, row.span(), 0, role)); previous = row; continue;
            }
            if (row.uhWire <= 0 || row.lh < 0) throw unknown("a positive upper hold is unavailable");
            int part = part(row, planned);
            if (part < 0 && row.uhWire == Proto.WIRE_HOLD_MAX && row.lh == 1 && row.lo == row.up - 1)
                throw unknown("this older wire-sized hold has no verified stitch boundary");
            if (carryHold > 0 && (previous == null || Math.abs(row.t0 - previous.t1) > STITCH_TOL_MS || row.up != previous.up
                || row.stageIdx != previous.stageIdx || row.pos != previous.pos || !row.setId.equals(previous.setId)
                || row.ordinal != previous.ordinal + 1 || row.kind != AsRun.PLAN))
                throw unknown("a stitched hold was interrupted or changed");
            if (part == 1) {
                if (row.kind != AsRun.PLAN || row.uhWire != Proto.WIRE_HOLD_MAX || row.lh != 1
                    || row.lo != row.up - 1 || row.span() > row.uhWire * 1000L + STITCH_TOL_MS)
                    throw unknown("the delivered stitch no longer matches its recorded boundary");
                if (carryRow == null) carryRow = row;
                carryHold += row.span(); carryWork += row.span(); previous = row;
                continue;
            }
            long upper = row.uhWire * 1000L, release = row.lh * 1000L;
            if (release == 0) {
                // With no release the positive command is continuous across device repeats.
                out.add(new Span(carryRow == null ? row : carryRow, carryWork + row.span(), carryHold + row.span(), WORK));
            } else {
                long remaining = row.span();
                while (remaining > 0) {
                    long held = Math.min(remaining, upper);
                    long work = Math.min(remaining, upper + release);
                    if (remaining - work > 0 && remaining - work <= LEFTOVER_MS) work = remaining;
                    out.add(new Span(carryRow == null ? row : carryRow, carryWork + work, carryHold + held, WORK));
                    carryHold = 0; carryWork = 0; carryRow = null;
                    remaining -= work;
                }
            }
            carryHold = 0; carryWork = 0; carryRow = null; previous = row;
        }
        // A stopped final chunk retains only its recorded, clipped hold duration.
        if (carryHold > 0) out.add(new Span(carryRow, carryWork, carryHold, WORK));
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
