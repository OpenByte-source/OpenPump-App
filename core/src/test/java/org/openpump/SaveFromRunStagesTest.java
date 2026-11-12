package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import static org.openpump.T10Fix.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * "UPDATE" AND "SAVE AS COPY" FROM A RUN KEEP THE STAGES WITHOUT SETS, AND THEIR WAITS.
 *
 * The summary's save offers rebuild the routine's stages from the run (AsRun#commit for
 * Update and New routine, AsRun#buildNewRoutine for Save as copy). They rebuilt each stage
 * from its name, colour and sets only, and left out any stage with no sets "so no empty stage
 * is left behind" - which is every rest stage. The tunica release and the changeover vanished
 * from the saved routine, so the expansion followed the pulls with no tube swap; and every
 * other stage lost its length marker and its cylinder. Now a stage is rebuilt as it was, with
 * only its sets replaced, and a rest stays where it was, with its wait.
 */
class SaveFromRunStagesTest {

    static Model.Routine lengthRoutine(Model m) {
        Model.Routine r = traction(m, 34, 5, day(m));
        m.selected = r.id;
        return r;
    }

    static AsRun runOf(Model m, Model.Routine r) {
        AsRun run = new AsRun(r.id, r.name, Model.AsRunSnapshot.of(r, m), false, m.ceilKpa);
        run.ts = 1755600000000L;
        return run;
    }

    static Model.Sess filed(AsRun run) {
        Model.Sess s = new Model.Sess();
        s.id = "x"; s.ts = run.ts; s.routineId = run.routineId; s.routineName = run.routineName;
        s.completed = true; s.manual = false; s.asRunSnapshot = run.snap;
        return s;
    }

    /** Each stage as the run sees it: name, rest and its time, the waits, the length marker
     *  and the cylinder (sets are compared apart: a save may replace them). */
    static List<String> shape(Model.Routine r) {
        List<String> out = new ArrayList<String>();
        for (Model.Stage st : r.stages)
            out.add(st.name + "|rest=" + st.rest + (st.rest ? "/" + st.restSec : "")
                + "|man=" + st.manual + "|ack=" + st.awaitAck + "|trac=" + st.traction
                + "|fat=" + st.fatigueBlock + "|cyl=" + st.cylinderId);
        return out;
    }

    @Test void saveAsCopyKeepsTheReleaseAndTheChangeoverAndTheirWaits() {
        Model m = owner();
        Model.Routine r = lengthRoutine(m);
        List<String> want = shape(r);
        AsRun.Commit c = AsRun.buildNewRoutine(new ArrayList<AsRun.SetChange>(), null, null,
                                               runOf(m, r), m, "Copy", m.ceilKpa);
        assertNotNull(c.routineId);
        Model.Routine got = m.routine(c.routineId);
        assertEquals(want, shape(got), "every stage, rests and waits included");
        assertEquals(StageWaitsCarryTest.planWaits(m, r), StageWaitsCarryTest.planWaits(m, got),
            "the copy waits where the original does");
    }

    @Test void updateKeepsTheReleaseAndTheChangeoverAndTheirWaits() {
        Model m = owner();
        Model.Routine r = lengthRoutine(m);
        List<String> want = shape(r);
        List<String> plan = StageWaitsCarryTest.planWaits(m, r);
        AsRun run = runOf(m, r);
        List<AsRun.Block> blocks = AsRun.blocks(run, m);
        AsRun.SaveDraft d = AsRun.draft(blocks, m, filed(run));
        assertTrue(d.canUpdate, "fixture: Update is on offer (" + d.cantUpdateWhy + ")");
        d.mode = AsRun.MODE_UPDATE;
        AsRun.Commit c = AsRun.commit(d, blocks, run, m);
        assertEquals(r.id, c.routineId);
        assertEquals(want, shape(m.routine(r.id)), "every stage, rests and waits included");
        assertEquals(plan, StageWaitsCarryTest.planWaits(m, m.routine(r.id)));
    }

    @Test void aNewRoutineFromTheRunKeepsThemToo() {
        Model m = owner();
        Model.Routine r = lengthRoutine(m);
        List<String> want = shape(r);
        AsRun run = runOf(m, r);
        List<AsRun.Block> blocks = AsRun.blocks(run, m);
        AsRun.SaveDraft d = AsRun.draft(blocks, m, filed(run));
        d.mode = AsRun.MODE_ROUTINE;
        d.routineName = "New";
        AsRun.Commit c = AsRun.commit(d, blocks, run, m);
        assertTrue(c.routineCreated);
        assertEquals(want, shape(m.routine(c.routineId)));
    }

    @Test void aSnapshotMadeBeforeTheWaitsWereKeptStillGivesThemBack() throws Exception {
        Model m = owner();
        Model.Routine r = lengthRoutine(m);
        List<String> want = shape(r);
        AsRun run = runOf(m, r);
        org.json.JSONObject j = run.snap.toJson();
        org.json.JSONArray st = j.getJSONArray("stages");
        for (int i = 0; i < st.length(); i++) { st.getJSONObject(i).remove("man"); st.getJSONObject(i).remove("ack"); }
        run.snap = Model.AsRunSnapshot.fromJson(j);
        AsRun.Commit c = AsRun.buildNewRoutine(new ArrayList<AsRun.SetChange>(), null, null,
                                               run, m, "Copy", m.ceilKpa);
        assertEquals(want, shape(m.routine(c.routineId)));
    }

    @Test void aWholeRoutineOfDroppedWorkIsStillRefusedThoughItsRestsRemain() {
        // The rule "never file a routine with nothing in it" is about what runs: a routine
        // left with only its rests runs nothing, so it is still refused.
        Model m = owner();
        Model.Routine r = lengthRoutine(m);
        AsRun run = runOf(m, r);
        List<AsRun.Block> blocks = AsRun.blocks(run, m);
        AsRun.SaveDraft d = AsRun.draft(blocks, m, filed(run));
        d.mode = AsRun.MODE_UPDATE;
        for (int i = 0; i < d.dropStage.length; i++)
            if (i < r.stages.size() && !r.stages.get(i).rest) d.dropStage[i] = true;
        assertTrue(AsRun.emptiesRoutine(d, blocks, m));
    }
}
