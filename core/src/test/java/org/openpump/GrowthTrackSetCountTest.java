package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;

/**
 * ONE SET RUN IS ONE SET SENT. Found on an emulator run: a two-set own routine (-15.0 cmHg
 * for 2:00, then -9.8 for 1:45, both 30 s hold / 5 s release) reached GrowthTrack as "RIP,
 * 3 sets", and a 1:30 manual run (80 s hold, 5 s release) as "Interval pumping 1 set" plus
 * "Milking 1 set 0m". A set whose time is not a whole number of cycles ends part-way into
 * its last cycle; that shortened hold was classified on its own and sent as an extra set.
 * The cycle the set's own clock ended is the same work as the cycles before it. The rows
 * below are the recording from that run.
 */
class GrowthTrackSetCountTest {
    static Model.Sess session(long durSec) {
        Model.Sess s = new Model.Sess(); s.id = "sess1"; s.ts = 1_700_000_000_000L; s.durSec = durSec;
        s.routineName = "New routine"; s.completed = true; s.recordedStartMs = s.ts; s.recordedEndMs = s.ts + durSec * 1000L + 500L;
        s.peakKpa = 20.0; s.netTupSec = 0.5 * durSec; return s;
    }
    static AsRun.Row row(long t0, long t1, int pos, Model.Set set) {
        AsRun.Row r = new AsRun.Row(); r.kind = AsRun.PLAN; r.t0 = t0; r.t1 = t1; r.pos = pos; r.setId = set.id; r.cyclePart = 0;
        r.up = set.up; r.upReq = set.up; r.lo = set.lo; r.uhWire = set.uh; r.uhReq = set.uh; r.lh = set.lh; r.sp = set.sp; return r;
    }
    static AsRun run(Model model, Model.Stage stage, AsRun.Row... rows) {
        Model.Routine routine = new Model.Routine(); routine.id = "r1"; routine.stages.add(stage);
        AsRun run = new AsRun(); run.snap = Model.AsRunSnapshot.of(routine, model);
        for (AsRun.Row r : rows) run.rows.add(r);
        return run;
    }

    @Test void aTwoSetRoutineIsTwoSetsOfItsOwnTechnique() throws Exception {
        Model model = new Model();
        Model.Set a = Model.Set.fixed("sa", "New set", 20, 10, 30, 5, 75, 120);
        Model.Set b = Model.Set.fixed("sb", "New set", 13, 10, 30, 5, 75, 105);
        model.sets.add(a); model.sets.add(b);
        Model.Stage stage = new Model.Stage(); stage.name = "Work"; stage.setIds.add(a.id); stage.setIds.add(b.id);
        AsRun run = run(model, stage, row(984, 121_075, 0, a), row(121_075, 225_906, 1, b));
        GrowthTrackSession.Draft d = GrowthTrackSession.draft(session(225), run);
        assertEquals(2, d.parts.size(), "two sets ran, two sets are sent");
        JSONArray sets = d.payload().getJSONArray("sets");
        assertEquals("rip", sets.getJSONObject(0).getString("method"));
        assertEquals(120, sets.getJSONObject(0).getInt("duration_seconds"));
        assertEquals("rip", sets.getJSONObject(1).getString("method"));
        assertEquals(104, sets.getJSONObject(1).getInt("duration_seconds"));
    }

    @Test void aManualRunIsOneSetAtItsCommandedHold() throws Exception {
        Model model = new Model();
        Model.Set m = Model.Set.fixed("manual", "Manual run", 16, 12, 80, 5, 75, 90);
        model.sets.add(m);
        Model.Stage stage = new Model.Stage(); stage.name = "Manual"; stage.setIds.add(m.id);
        AsRun run = run(model, stage, row(915, 90_758, 0, m));
        GrowthTrackSession.Draft d = GrowthTrackSession.draft(session(90), run);
        assertEquals(1, d.parts.size(), "no 0-minute milking tail");
        JSONArray sets = d.payload().getJSONArray("sets");
        assertEquals("interval_pumping", sets.getJSONObject(0).getString("method"));
        assertEquals(89, sets.getJSONObject(0).getInt("duration_seconds"));
    }

    /** A set stopped part-way is unchanged: its interrupted hold is sent as far as it got,
     *  with the technique of what actually ran (the owner's earlier rule). */
    @Test void aSetStoppedPartWayStillSendsItsInterruptedHoldAsItRan() throws Exception {
        Model model = new Model();
        Model.Set a = Model.Set.fixed("sa", "New set", 20, 10, 30, 5, 75, 120);
        model.sets.add(a);
        Model.Stage stage = new Model.Stage(); stage.name = "Work"; stage.setIds.add(a.id);
        Model.Sess s = session(76); s.completed = false;
        AsRun run = run(model, stage, row(0, 75_000, 0, a));
        GrowthTrackSession.Draft d = GrowthTrackSession.draft(s, run);
        assertEquals(2, d.parts.size());
        assertEquals("rip", d.parts.get(0).method); assertEquals(70_000, d.parts.get(0).recordedMillis);
        assertEquals("milking", d.parts.get(1).method); assertEquals(5_000, d.parts.get(1).holdMillis);
    }
}
