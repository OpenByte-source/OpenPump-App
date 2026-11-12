package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import static org.openpump.T10Fix.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * A RESTORED VERSION KEEPS EACH STAGE AS THE ROUTINE HAD IT.
 *
 * A stage is more than its name and sets: its colour (a warm-up is the warm-up by it), the
 * length marker (Stage#traction), the fatigue, retention and climb markers, and the cylinder
 * it runs in. "Restore as copy" rebuilt every stage as a plain cool stage with none of them,
 * so a restored trainer routine's warm-up played as a plain ramp, its work came out of net
 * and its connected-app labels came out wrong. Now the snapshot records the whole shape and
 * the restore puts it back. A snapshot made before is given back what it does say - its
 * recorded role, and the builder's own pulls in a routine the builder made - and the shape
 * is never compared, so no run reads as "routine edited" because of it.
 */
class StageShapeCarryTest {

    /** The new snapshot keys this change writes - removed to make a snapshot as an older
     *  build saved it. */
    static final String[] SHAPE_KEYS = { "col", "trac", "fat", "ret", "climb", "cyl" };

    static Model.Routine lengthMint(Model m) {
        Model.Routine r = traction(m, 34, 5, day(m));
        assertEquals(Plan.TRACK_LENGTH, r.trainerTrack, "fixture: a length mint");
        int pulls = 0;
        for (Model.Stage st : r.stages) if (st.traction) pulls++;
        assertEquals(2, pulls, "fixture: the Fatigue and Strain holds pull");
        return r;
    }

    /** A routine the person built, with every marker on one stage or another: a warm-up, a
     *  pull with the fatigue marker in a named cylinder, a counted-nothing climb, a retention
     *  hold, a rest that waits, and a plain stage in a colour of its own. */
    static Model.Routine everyMarker(Model m) {
        m.ceilKpa = 40;
        m.sets.add(Model.Set.fixed("a", "Warm", 12, 4, 40, 8, 60, 240));
        m.sets.add(Model.Set.fixed("b", "Pull", 20, 0, 60, 30, 60, 600));
        m.sets.add(Model.Set.fixed("c", "Hold", 10, 4, 60, 10, 60, 300));
        Model.Routine r = new Model.Routine();
        r.id = "mine"; r.name = "Mine";
        r.stages.add(Model.Stage.of("Warm-up", Model.STAGE_WARM, new String[]{ "a" }));
        Model.Stage pull = Model.Stage.of("Pulls", Model.STAGE_WORK, new String[]{ "b" });
        pull.traction = true; pull.fatigueBlock = true; pull.cylinderId = "cylL";
        r.stages.add(pull);
        Model.Stage climb = Model.Stage.of("Climb", Model.STAGE_WORK, new String[]{ "c" });
        climb.climb = true;
        r.stages.add(climb);
        Model.Stage ret = Model.Stage.of("Retention", Model.STAGE_COOL, new String[]{ "c" });
        ret.retention = true;
        r.stages.add(ret);
        Model.Stage rest = Model.Stage.restOf("Swap", 60);
        rest.manual = true; rest.awaitAck = true;
        r.stages.add(rest);
        r.stages.add(Model.Stage.of("Plain", 0xFF5B9CFF, new String[]{ "c" }));
        m.routines.add(r);
        return r;
    }

    /** A routine with a wait and no marker. */
    static Model.Routine waitsOnly(Model m) {
        m.ceilKpa = 40;
        m.sets.add(Model.Set.fixed("ow", "Warm", 12, 4, 40, 8, 60, 240));
        Model.Routine q = new Model.Routine();
        q.id = "src"; q.name = "Waits only";
        q.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "ow" }));
        Model.Stage rest = Model.Stage.restOf("Swap", 60);
        rest.manual = true; rest.awaitAck = true;
        q.stages.add(rest);
        m.routines.add(q);
        return q;
    }

    /** Each stage's shape, without its cylinder (a phone's own rack id; see #cylinder). */
    static List<String> marks(Model.Routine r) {
        List<String> out = new ArrayList<String>();
        for (Model.Stage st : r.stages)
            out.add("col=" + Integer.toHexString(st.colour) + "|rest=" + st.rest
                + "|man=" + st.manual + "|ack=" + st.awaitAck + "|trac=" + st.traction
                + "|fat=" + st.fatigueBlock + "|ret=" + st.retention + "|climb=" + st.climb);
        return out;
    }

    /** ...and with it. */
    static List<String> shape(Model.Routine r) {
        List<String> out = marks(r);
        for (int i = 0; i < r.stages.size(); i++) out.set(i, out.get(i) + "|cyl=" + r.stages.get(i).cylinderId);
        return out;
    }

    static Model.AsRunSnapshot oldSnapshot(Model.Routine r, Model m) throws Exception {
        JSONObject j = Model.AsRunSnapshot.of(r, m).toJson();
        JSONArray st = j.getJSONArray("stages");
        for (int i = 0; i < st.length(); i++) for (String k : SHAPE_KEYS) st.getJSONObject(i).remove(k);
        return Model.AsRunSnapshot.fromJson(j);
    }

    static Model.AsRunSnapshot throughJson(Model.AsRunSnapshot s) throws Exception {
        return Model.AsRunSnapshot.fromJson(new JSONObject(s.toJson().toString()));
    }

    /* -------------------------------------------------------- version restores */

    @Test void aRestoredVersionKeepsEveryStageAsItWas() throws Exception {
        Model m = new Model();
        Model.Routine r = everyMarker(m);
        m.archiveRoutineVersion(r, throughJson(Model.AsRunSnapshot.of(r, m)));
        Model.Routine got = m.restoreRoutineVersion(r.id, 0);
        assertNotNull(got);
        assertEquals(shape(r), shape(got),
            "colour, the length, fatigue, retention and climb markers, the cylinder and the waits");
    }

    @Test void aRestoredLengthMintKeepsItsWarmUpAndItsPulls() throws Exception {
        Model m = owner();
        Model.Routine r = lengthMint(m);
        m.archiveRoutineVersion(r, throughJson(Model.AsRunSnapshot.of(r, m)));
        Model.Routine got = m.restoreRoutineVersion(r.id, 0);
        assertEquals(shape(r), shape(got));
        for (int i = 0; i < r.stages.size(); i++)
            assertEquals(Model.AsRunSnapshot.roleOf(r.stages.get(i)),
                         Model.AsRunSnapshot.roleOf(got.stages.get(i)),
                         "the connected-app role of stage " + i);
    }

    @Test void anOldVersionFallsBackOnTheRoleItRecorded() throws Exception {
        // A routine the person built: the recorded role says which stage was the warm-up and
        // which pulled - nothing else is inferred, and no name is read.
        Model m = new Model();
        Model.Routine r = everyMarker(m);
        m.archiveRoutineVersion(r, oldSnapshot(r, m));
        Model.Routine got = m.restoreRoutineVersion(r.id, 0);
        assertEquals(Model.STAGE_WARM, got.stages.get(0).colour, "the warm-up, by its role");
        assertTrue(got.stages.get(1).traction, "the pull, by its role");
        assertFalse(got.stages.get(1).fatigueBlock, "a person's routine: no marker guessed");
        assertEquals(Model.STAGE_REST, got.stages.get(4).colour, "a rest is drawn as a rest");
        for (int i = 0; i < r.stages.size(); i++)
            assertEquals(Model.AsRunSnapshot.roleOf(r.stages.get(i)),
                         Model.AsRunSnapshot.roleOf(got.stages.get(i)), "role of stage " + i);
    }

    @Test void anOldVersionOfALengthMintGetsItsPullsBack() throws Exception {
        Model m = owner();
        Model.Routine r = lengthMint(m);
        m.archiveRoutineVersion(r, oldSnapshot(r, m));
        Model.Routine got = m.restoreRoutineVersion(r.id, 0);
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage a = r.stages.get(i), b = got.stages.get(i);
            assertEquals(a.traction, b.traction, "the length marker of stage " + i);
            assertEquals(a.fatigueBlock, b.fatigueBlock, "the fatigue marker of stage " + i);
            assertEquals(a.colour == Model.STAGE_WARM, b.colour == Model.STAGE_WARM, "warm-up " + i);
            assertEquals(Model.AsRunSnapshot.roleOf(a), Model.AsRunSnapshot.roleOf(b), "role " + i);
        }
    }

    @Test void theShapeDoesNotMakeARunReadAsEdited() throws Exception {
        Model m = new Model();
        Model.Routine r = everyMarker(m);
        Model.AsRunSnapshot now = throughJson(Model.AsRunSnapshot.of(r, m));
        Model.AsRunSnapshot old = oldSnapshot(r, m);
        assertTrue(now.matches(r, m) && old.matches(r, m), "fixture: both match");
        // A recoloured stage is decoration, never an edit - as it always was.
        r.stages.get(5).colour = Model.STAGE_COOL;
        assertTrue(now.matches(r, m), "a recolour is not an edit");
        assertTrue(old.matches(r, m), "an old recording still matches");
    }

    @Test void aSnapshotWritesOnlyTheMarkersAStageHas() throws Exception {
        Model m = new Model();
        Model.Routine q = waitsOnly(m);
        String j = Model.AsRunSnapshot.of(q, m).toJson().toString();
        for (String k : new String[]{ "trac", "fat", "ret", "climb", "cyl" })
            assertFalse(j.contains("\"" + k + "\""), "an unmarked stage writes no " + k);
    }

    @Test void saveAsCopyFromARunOfADeletedRoutineKeepsTheShape() {
        // Save as copy rebuilds from the snapshot; with the routine gone there is no live
        // stage to borrow the markers from - the snapshot has them now.
        Model m = owner();
        Model.Routine r = lengthMint(m);
        List<String> want = shape(r);
        AsRun run = SaveFromRunStagesTest.runOf(m, r);
        m.routines.remove(r);
        AsRun.Commit c = AsRun.buildNewRoutine(new ArrayList<AsRun.SetChange>(), null, null,
                                               run, m, "Copy", m.ceilKpa);
        assertNotNull(c.routineId);
        assertEquals(want, shape(m.routine(c.routineId)));
    }
}
