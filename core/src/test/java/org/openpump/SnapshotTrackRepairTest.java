package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import static org.openpump.T10Fix.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * A SNAPSHOT'S OWN TRAINER TRACK SAYS THE BUILDER MADE IT (the owner, 2026-11-11).
 *
 * A version restore and a save from a run rebuild a routine from a snapshot
 * (Model.AsRunSnapshot), which records the routine's trainer track as it was when the run
 * started - set only on a trainer mint. A snapshot made before the waits were kept, of a mint
 * whose release and changeover the person had renamed, carries none of the builder's words; its
 * track still says the builder made it, so it gets its waits back. A snapshot of a routine the
 * person built has no track, and is still never given a wait.
 */
class SnapshotTrackRepairTest {

    /** A mint with both of the builder's words renamed away. */
    static Model.Routine renamedMint(Model m) {
        Model.Routine r = traction(m, 34, 5, day(m));
        assertNotEquals(Model.TRAINER_TRACK_NONE, r.trainerTrack, "fixture: a mint");
        m.selected = r.id;
        r.stages.get(0).name = "Release";
        for (Model.Stage st : r.stages) if (st.awaitAck) st.name = "Swap tubes";
        assertFalse(Plan.hasBuilderWords(r.stages), "fixture: no builder words left");
        return r;
    }

    /** What the rule can give back here: the changeover's wait, found by its place before
     *  the expansion. A release renamed away from the builder's name is not known by name
     *  (BuilderOnlyRepairTest), mint or not. */
    static List<String> changeoverOnly(Model.Routine r) {
        List<String> out = new ArrayList<String>();
        for (String w : StageWaitsCarryTest.waits(r)) if (w.endsWith("|true|true")) out.add(w);
        assertEquals(1, out.size(), "fixture: one changeover");
        return out;
    }

    /** The snapshot as a build before the waits were kept saved it. */
    static Model.AsRunSnapshot oldSnapshot(Model.Routine r, Model m) throws Exception {
        JSONObject j = Model.AsRunSnapshot.of(r, m).toJson();
        JSONArray st = j.getJSONArray("stages");
        for (int i = 0; i < st.length(); i++) { st.getJSONObject(i).remove("man"); st.getJSONObject(i).remove("ack"); }
        return Model.AsRunSnapshot.fromJson(j);
    }

    @Test void aRestoredOldVersionOfARenamedMintWaits() throws Exception {
        Model m = owner();
        Model.Routine r = renamedMint(m);
        List<String> want = changeoverOnly(r);
        m.archiveRoutineVersion(r, oldSnapshot(r, m));
        Model.Routine got = m.restoreRoutineVersion(r.id, 0);
        assertEquals(want, StageWaitsCarryTest.waits(got), "the snapshot's track says the builder made it");
    }

    @Test void aSaveAsCopyFromAnOldRunOfARenamedMintWaits() throws Exception {
        Model m = owner();
        Model.Routine r = renamedMint(m);
        List<String> want = changeoverOnly(r);
        AsRun run = new AsRun(r.id, r.name, oldSnapshot(r, m), false, m.ceilKpa);
        run.ts = 1755600000000L;
        // The routine as it stands lent nothing: its own flags are gone too (an old import).
        for (Model.Stage st : r.stages) { st.manual = false; st.awaitAck = false; }
        AsRun.Commit c = AsRun.buildNewRoutine(new ArrayList<AsRun.SetChange>(), null, null,
                                               run, m, "Copy", m.ceilKpa);
        assertEquals(want, StageWaitsCarryTest.waits(m.routine(c.routineId)));
    }

    @Test void aPersonsRoutineSnapshotIsStillNeverGivenAWait() throws Exception {
        Model m = owner();
        Model.Routine r = BuilderOnlyRepairTest.personsOwn(m);
        String before = BuilderOnlyRepairTest.flags(r);
        Model.AsRunSnapshot snap = oldSnapshot(r, m);
        assertEquals(Model.TRAINER_TRACK_NONE, snap.trainerTrack, "fixture: no track");
        m.archiveRoutineVersion(r, snap);
        assertEquals(before, BuilderOnlyRepairTest.flags(m.restoreRoutineVersion(r.id, 0)));
        AsRun run = new AsRun(r.id, r.name, oldSnapshot(r, m), false, m.ceilKpa);
        AsRun.Commit c = AsRun.buildNewRoutine(new ArrayList<AsRun.SetChange>(), null, null,
                                               run, m, "Copy", m.ceilKpa);
        assertEquals(before, BuilderOnlyRepairTest.flags(m.routine(c.routineId)));
    }
}
