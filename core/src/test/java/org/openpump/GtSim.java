package org.openpump;

import java.util.List;

/**
 * BUG HUNT HELPER (not part of the PR). Records a run the way SessionActivity does it:
 * one PLAN row per preset (SessionActivity#playPreset: asRun.open(PLAN, asRunNow(), p,
 * planUp(p), p.up, planLo(p), p.uh, p.uh, p.lh, p.sp)), one REST row per rest / by-hand
 * preset (asRun.open(REST, asRunNow(), p, 0,0,0,0,0,0,0)), each row closed where the next
 * opens, the last one at the filed elapsed (asRunFile: asRun.close(elapsedMs)). The record
 * is stamped the way fileSession stamps it. Fictional data only; nothing here can reach a
 * network.
 */
final class GtSim {
    static final long START = 1_790_000_000_000L;   // a wall-clock instant, fictional

    final Model model;
    final Model.Routine routine;
    final List<Model.Preset> plan;
    final AsRun run;
    long t;                 // the run's elapsed clock, ms
    /** How late each START is acknowledged after its step's time is up, ms (the app timer's
     *  own lateness: AsRun.CUT_SHORT_TOL_MS says "within tens of ms"). */
    long jitterMs;

    GtSim(Model model, Model.Routine routine) {
        this.model = model; this.routine = routine;
        this.plan = model.plan(routine);
        this.run = new AsRun(routine.id, routine.name, Model.AsRunSnapshot.of(routine, model),
                             false, model.ceilKpa);
    }

    static int planUp(Model m, Model.Preset p) { return RunEdit.clampUpper(p.up, m.ceilKpa); }
    static int planLo(Model m, Model.Preset p) { return RunEdit.dropKept(p, p.lo, planUp(m, p)); }

    AsRun.Row open(Model.Preset p) {
        if (p.rest) return run.open(AsRun.REST, t, p, 0, 0, 0, 0, 0, 0, 0);
        return run.open(AsRun.PLAN, t, p, planUp(model, p), p.up, planLo(model, p),
                        p.uh, p.uh, p.lh, p.sp);
    }

    /** Plays presets [from, to) to their full time. */
    GtSim play(int from, int to) {
        for (int i = from; i < to && i < plan.size(); i++) {
            open(plan.get(i));
            t += plan.get(i).durMs + jitterMs;
        }
        return this;
    }
    GtSim playAll() { return play(0, plan.size()); }

    /** Files it: closes the recording and stamps a record as fileSession does. */
    Model.Sess file(boolean aborted) {
        run.close(t);
        Model.Sess s = new Model.Sess();
        long now = START + t;
        s.id = "sess" + now;
        s.routineId = routine.id; s.routineName = routine.name;
        s.durSec = t / 1000L;
        s.ts = START;
        s.recordedStartMs = START; s.recordedEndMs = now;
        s.completed = !aborted;
        s.peakKpa = 20.0;
        s.hasAsRun = !run.rows.isEmpty();
        s.asRunSnapshot = run.snap;
        run.ts = s.ts;
        return s;
    }

    static String describe(org.json.JSONObject payload) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append(payload.getString("routine_name")).append(" total=").append(payload.getInt("total_duration_seconds"));
        if (payload.has("method")) b.append(" method=").append(payload.getString("method"));
        org.json.JSONArray sets = payload.optJSONArray("sets");
        if (sets != null) for (int i = 0; i < sets.length(); i++) {
            org.json.JSONObject s = sets.getJSONObject(i);
            b.append("\n   #").append(s.getInt("set_index")).append(' ').append(s.getString("method"))
             .append(' ').append(s.isNull("duration_seconds") ? "null" : String.valueOf(s.getInt("duration_seconds")));
        }
        return b.toString();
    }
}
