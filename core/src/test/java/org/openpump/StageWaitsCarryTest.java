package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import static org.openpump.T10Fix.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * A SHARE CODE AND A RESTORED VERSION KEEP THE STAGE WAITS.
 *
 * A length routine waits two ways: the tunica release waits for Done once its time is up
 * (Stage#manual), and the changeover waits for "I've swapped" from its start (Stage#awaitAck).
 * Neither a share code nor a version snapshot carried those flags, so a routine that arrived
 * either way ran its changeover as a plain timed rest and pulled again into whichever tube was
 * fitted. Now a code made by this build carries them, and what was made before is given them
 * back on read by the one rule the load repair uses (Model#giveStagesTheirWaits). Nothing that
 * arrives can start a pull the original waited for.
 */
class StageWaitsCarryTest {

    /* Made by the build before this change (0.10.1 before the fix), from #top on T10Fix#owner:
     * the length session - release, warm-up, fatigue, strain, the changeover, the expansion.
     * Neither carries a single stage flag. */
    static final String OLD_LENGTH_V3 = "PD3:HBUcmFpbmVyIMK3IExlbmd0aCBMMiDCtyB0cmFjdGlvbiBAIHVwIHRvIDEyLjIgbGIFHhas0qOrc0sbCQOTK2MrC5spBxQEoQMTyQNDC3MkolgLK7C5Npa6uBA6N5BxREkcFxgQNLckM7/x3s8A8CsrsLk2lrq4EDo3kHFESRwXGBA0tyQzhMDHgWW2GeAssMJYajMLo0s7qykDQ3tjI5wA8EyjMLo0s7qykDQ3tjIQYVuQMbY0trEQYcubBcAPAqXCAeBUsMNIgjMLo0s7qykDQ3tjIQYcuaAiAPAqWIwYpujkwtLcQNDe2MjnADwJ6m6OTC0txA0N7YyEGHLmoIgP8els5fqbuwuBA6N5A8t7q5EDO0uTo0EDG8tjS3MjK5EHFAShA3Mrw6EDS5kDK8ODC3ObS3txAwuhBxREkYmBcYEDS3Sg8ASivDgwtzm0t7dADwJyK8ODC3ObS3txA0N7YyAiDeAWWTiA=";
    static final String OLD_LENGTH_V2 = "PD-ROUTINE-2:eNq1lbFu2zAQhl+FuFkKSIqSHE1dWnTIUDQFOhQdaJm2hEiUQFJNjMBAx85FgTxK9+ZN8iTlybJjp3IMG+hkiTr+9/M74vc9SGuVtZB9uYdZZyATcQA3rYSM0wBsC1nif28LpSEDOXfKQACNf5nLyqrV1wC+QcYC0LJWvuKTkaVWhvz5Ta6UXriCXHF8cUbmrmw0eUO6lriGMH7BSTX1YtbJhVobMMo6yJzpVAB5UzXoJ2Q05ewy8j2scljne2Lhtcohiyh97t3pMpfEqEpJq8jT919kuiSF1DNYBRvx3vaOepRSQanYig8Uet29HVXjV3GtHTx432b71ci63Ti3rq9JcA/vN81NU3/skCD1B+4K1Ee4/mvqcXf4wFNfX/gHv1DWbWOcmm3lhxN+lqYO1/yefvycXFBS6vcLL1n6UrAsvRQi5ixNookIBbbyphhH4V4Xx4kN0c+HSuqN4w774pxraW4go6tdxIzTYwYO42U8mfAe5j5fwcf40nP40jG+yT98o2jgy+hhwO+kKxedIkVTzfDa5lVZT8njQzLKOA3jgTGfrBkzegLkYGDBJ+ewGF4GGOJEGOJUGI8PYoxBIkI2MFhrnsjg0EXb7W3PuF8sif8z1HXTcarRK1SvHUbkBmo8BjXlIX8BNToGtbdzjOpO712opwTuntyt7KNg6feRRWl82ufLqtQz/weA6avVnSOlJequldpi+kuHucHo+cGR8PiMYB6f62gyM354rq9E89vtGZHu6FTTMHox1WOR3Hs5NtRtZ8CSTfVfAHxbTA==";
    /* ...and a hand-built routine with no wait in it (a work stage and a plain rest), as that
     * build wrote it: a code for a routine with no wait is the same code, byte for byte. */
    static final String SMALL_V2 = "PD-ROUTINE-2:eNpVUEtuAyEMvUrlNZWATKcpl2iVLrqosqAzpBMFBsSnWUS5e22SIckKY7+P/U6gUzIpgfo+wVgiqO6FwSFoUJIzSAFUj+9xMjMo+PF5AgYe6522yZy3DP5ACQazdgYB73Z8GvxoEJSy/jUX3WhSvjIYDN568nkWsl/LFScXk+8WkB22HijW41rUCwTbVu3YplG70D4pV1BHJFnfXfRuU2h7jluVCZv1MHm5rFAhJOInLNYM9i74mM3YJK+nfenoUGCPA/BHkgoLsfKWqMjvw+q58QsJk6fT8QCKU2h03qcZSIDfHDzOz2zJK8fyEBd/leJtJVpc9zL9TWVDZPJY7P4BaS2Pzw==";
    static final String SMALL_V3 = "PD3:EhPbGQgY29kZQUeFsQiu3uTXADwIiuwuTaAwQoCHgeAikyubpKB4AA==";

    /** The owner's length session at a 34 kPa pull: the release waits after its clock, the
     *  changeover from its start. */
    static Model.Routine top(Model m) {
        Model.Routine r = traction(m, 34, 5, day(m));
        assertEquals(Plan.TRACK_LENGTH, r.trainerTrack, "fixture: a length mint");
        assertEquals(2, waits(r).size(), "fixture: the release and the changeover wait");
        return r;
    }

    static Model small(Model m) {
        m.ceilKpa = 40;
        m.sets.add(Model.Set.fixed("ow", "Warm", 12, 4, 40, 8, 60, 240));
        Model.Routine q = new Model.Routine();
        q.id = "src"; q.name = "Old code";
        q.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "ow" }));
        q.stages.add(Model.Stage.restOf("Rest", 60));
        m.routines.add(q);
        return m;
    }

    /** Every stage that waits, as "position|manual|awaitAck", in order. By position, not
     *  name: a v3 code trims a name to 63 bytes, which can cut the changeover's sentence. */
    static List<String> waits(Model.Routine r) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st.manual || st.awaitAck) out.add(i + "|" + st.manual + "|" + st.awaitAck);
        }
        return out;
    }

    /** The run plays each stage's steps with the waits the original's did: per stage, "from
     *  its start" / "after its clock" / "no wait". Per stage rather than step for step - a v3
     *  code packs a commanding set's own fields at their clamped widths, which can change how
     *  many steps it lays out, and that is not what this is about. */
    static List<String> planWaits(Model m, Model.Routine r) {
        String[] by = new String[r.stages.size()];
        for (Model.Preset p : m.plan(r)) {
            String w = p.awaitAck ? "start" : ByHand.waitsAfterClock(p) ? "clock" : "none";
            assertTrue(by[p.stageIdx] == null || by[p.stageIdx].equals(w), "one wait per stage");
            by[p.stageIdx] = w;
        }
        return java.util.Arrays.asList(by);
    }

    static void sameWaitsInThePlan(Model a, Model.Routine ra, Model b, Model.Routine rb) {
        List<String> wa = planWaits(a, ra);
        assertTrue(wa.contains("start") && wa.contains("clock"), "fixture: both kinds of wait");
        assertEquals(wa, planWaits(b, rb), "every stage's steps wait as the original's did");
    }

    static Model.Routine stripped(Model m, Model.Routine r) {
        Model.Routine c = r.copy(m.newRoutineId());
        // ...and the markers, which no code carried before 0.10.1 either (StageShapeCarryTest).
        for (Model.Stage st : c.stages) {
            st.manual = false; st.awaitAck = false;
            st.traction = false; st.fatigueBlock = false; st.retention = false; st.climb = false;
        }
        m.routines.add(c);
        return c;
    }

    static int packedVersion(String v3) {
        return (Base64.getDecoder().decode(v3.substring(4))[0] >> 4) & 0x0F;
    }

    static JSONObject v2Json(String v2) throws Exception {
        byte[] z = Base64.getDecoder().decode(v2.substring("PD-ROUTINE-2:".length()));
        java.util.zip.Inflater in = new java.util.zip.Inflater();
        in.setInput(z);
        byte[] buf = new byte[65536];
        int n = in.inflate(buf);
        in.end();
        return new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
    }

    /* ------------------------------------------------------------- share codes */

    @Test void aV3CodeKeepsEveryWait() throws Exception {
        Model m = owner();
        Model.Routine r = top(m);
        Model in = owner();
        Model.Routine got = in.importShareCode(r.toShareCode(m));
        assertEquals(waits(r), waits(got), "the release and the changeover wait as they did");
        sameWaitsInThePlan(m, r, in, got);
    }

    @Test void aV2CodeKeepsEveryWait() throws Exception {
        Model m = owner();
        Model.Routine r = top(m);
        Model in = owner();
        Model.Routine got = in.importShareCode(r.toShareCodeV2(m));
        assertEquals(waits(r), waits(got));
        sameWaitsInThePlan(m, r, in, got);
    }

    @Test void aCodeMadeBeforeThisStillDecodesAndWaits() throws Exception {
        Model m = owner();
        Model.Routine r = top(m);
        Model in = owner();
        Model.Routine v3 = in.importShareCode(OLD_LENGTH_V3);
        assertEquals(waits(r), waits(v3), "an old v3 code: the waits by the builder's names");
        sameWaitsInThePlan(m, r, in, v3);
        Model.Routine v2 = in.importShareCode(OLD_LENGTH_V2);
        assertEquals(waits(r), waits(v2), "an old v2 code too");
        sameWaitsInThePlan(m, r, in, v2);
    }

    @Test void aCodeWithNoWaitIsTheSameCodeAsBefore() throws Exception {
        Model m = small(new Model());
        Model.Routine q = m.routines.get(0);
        assertEquals(SMALL_V3, q.toShareCode(m), "v3, byte for byte");
        assertEquals(SMALL_V2, q.toShareCodeV2(m), "v2, byte for byte");
        Model.Routine got = new Model().importShareCode(SMALL_V3);
        assertTrue(waits(got).isEmpty(), "a plain rest in a hand-built routine is not made to wait");
        assertTrue(waits(new Model().importShareCode(SMALL_V2)).isEmpty());
    }

    @Test void aCodeWithWaitsIsOneAnOlderBuildRefusesRatherThanRunsWithoutThem() throws Exception {
        Model m = owner();
        Model.Routine r = top(m);
        // The length session also carries its pulls' markers since 0.10.1: version 4 and
        // document 3, which a build that cannot read the waits refuses just the same.
        assertEquals(4, packedVersion(r.toShareCode(m)),
            "packed version 4: a build that cannot read the waits says the code is unrecognised");
        assertEquals(3, v2Json(r.toShareCodeV2(m)).getInt("v"), "and v2's document says version 3");
        Model w = new Model();
        Model.Routine waits = StageShapeCarryTest.waitsOnly(w);
        assertEquals(3, packedVersion(waits.toShareCode(w)),
            "packed version 3: the waits and no marker");
        assertEquals(2, v2Json(waits.toShareCodeV2(w)).getInt("v"), "and v2's document says version 2");
        Model small = small(new Model());
        assertEquals(1, packedVersion(small.routines.get(0).toShareCode(small)),
            "a routine with no wait keeps version 1, which every build reads");
        assertEquals(1, v2Json(small.routines.get(0).toShareCodeV2(small)).getInt("v"));
    }

    @Test void aChangeoverRenamedInAnOldCodeStillWaitsByItsPlace() throws Exception {
        Model m = owner();
        Model.Routine r = top(m);
        Model.Routine c = stripped(m, r);
        int over = -1;
        for (int i = 0; i < r.stages.size(); i++) if (r.stages.get(i).awaitAck) over = i;
        c.stages.get(over).name = "Swap tubes";
        // Stripped of its flags, this is the code the build before this one wrote for it.
        String code = c.toShareCode(m);
        assertEquals(1, packedVersion(code), "fixture: a code with no flags, as an old build wrote");
        Model.Routine got = owner().importShareCode(code);
        Model.Stage st = got.stages.get(over);
        assertTrue(st.rest && st.manual && st.awaitAck,
            "the rest between the last pull and the expansion waits for \"I've swapped\"");
        Model.Routine got2 = owner().importShareCode(c.toShareCodeV2(m));
        assertTrue(got2.stages.get(over).awaitAck, "v2 too");
    }

    @Test void aCodeCannotMakeACommandingStageManual() throws Exception {
        // A hand-written code with the flags on a stage that commands: the flags only ever
        // land on a rest (a manual stage holds no sets and never reaches the pump).
        Model m = small(new Model());
        Model.Routine q = m.routines.get(0);
        JSONObject o = v2Json(q.toShareCodeV2(m));
        o.put("v", 2);
        o.getJSONArray("stages").getJSONObject(0).put("manual", true).put("ack", true);
        java.util.zip.Deflater z = new java.util.zip.Deflater();
        z.setInput(o.toString().getBytes(StandardCharsets.UTF_8));
        z.finish();
        byte[] buf = new byte[65536];
        int n = z.deflate(buf);
        z.end();
        String code = "PD-ROUTINE-2:" + Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(buf, n));
        Model.Routine got = new Model().importShareCode(code);
        assertFalse(got.stages.get(0).manual || got.stages.get(0).awaitAck);
        assertFalse(got.stages.get(0).setIds.isEmpty(), "its sets kept");
    }

    /* -------------------------------------------------------- version restores */

    @Test void aRestoredVersionKeepsEveryWait() throws Exception {
        Model m = owner();
        Model.Routine r = top(m);
        m.archiveRoutineVersion(r, Model.AsRunSnapshot.of(r, m));
        Model.Routine got = m.restoreRoutineVersion(r.id, 0);
        assertNotNull(got);
        assertEquals(waits(r), waits(got));
        sameWaitsInThePlan(m, r, m, got);
    }

    @Test void aVersionArchivedBeforeThisStillRestoresTheWaits() throws Exception {
        Model m = owner();
        Model.Routine r = top(m);
        // As the build before this one saved it: no stage flag in the snapshot.
        JSONObject j = Model.AsRunSnapshot.of(r, m).toJson();
        JSONArray st = j.getJSONArray("stages");
        for (int i = 0; i < st.length(); i++) { st.getJSONObject(i).remove("man"); st.getJSONObject(i).remove("ack"); }
        Model.AsRunSnapshot old = Model.AsRunSnapshot.fromJson(j);
        m.archiveRoutineVersion(r, old);
        Model.Routine got = m.restoreRoutineVersion(r.id, 0);
        assertEquals(waits(r), waits(got), "given back by the builder's names and the changeover's place");
        sameWaitsInThePlan(m, r, m, got);
    }

    @Test void theSnapshotCarriesTheWaitsThroughASave() throws Exception {
        Model m = owner();
        Model.Routine r = top(m);
        JSONObject j = Model.AsRunSnapshot.of(r, m).toJson();
        JSONArray st = j.getJSONArray("stages");
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage s = r.stages.get(i);
            assertEquals(s.manual, st.getJSONObject(i).optBoolean("man", false), "stage " + i);
            assertEquals(s.awaitAck, st.getJSONObject(i).optBoolean("ack", false), "stage " + i);
        }
        // A stage with no wait writes nothing new: an unflagged snapshot's file is unchanged.
        JSONObject plain = Model.AsRunSnapshot.of(small(new Model()).routines.get(0), null).toJson();
        assertFalse(plain.toString().contains("\"man\"") || plain.toString().contains("\"ack\""));
    }

    /* ------------------------------------------------------------ the load repair */

    @Test void theLoadRepairGivesEverySavedRoutineItsWaitsByTheSameRule() {
        Model m = owner();
        Model.Routine r = top(m);
        List<String> want = waits(r);
        // A length routine added from an old code carries no trainer track and no flag.
        r.trainerTrack = Model.TRAINER_TRACK_NONE;
        for (Model.Stage st : r.stages) { st.manual = false; st.awaitAck = false; }
        small(m);
        assertEquals(1, m.giveRoutinesTheirWaits(), "the length routine, not the hand-built one");
        assertEquals(want, waits(r), "one rule: the repair, the codes and the restore");
        assertTrue(waits(m.routine("src")).isEmpty(), "a plain rest the builder never made stays a rest");
        assertEquals(0, m.giveRoutinesTheirWaits(), "a second run changes nothing");
    }
}
