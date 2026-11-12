package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import static org.openpump.T10Fix.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * THE WAITS ARE GIVEN BACK ONLY TO WHAT THE TRAINER BUILT (the owner, 2026-11-11).
 *
 * The one rule (Model#giveStagesTheirWaits) gives a routine that lost its stage flags its
 * waits back - the release's for Done, the changeover's for "I've swapped". It runs when the
 * app opens, on a backup restore, and on an old share code or version. It must touch only a
 * routine the trainer's builder made, never one the person built: a routine is the builder's
 * when it is still a trainer mint (Routine#trainerTrack), or when it carries the builder's own
 * words - a rest named "Tunica release — by hand", or a rest whose name is the builder's
 * changeover sentence ("Swap to your girth cylinder — next is expansion at ..."). A routine
 * the person built, however it is shaped, is left exactly as it is.
 */
class BuilderOnlyRepairTest {

    /** A person's own two-tube routine shaped like the builder's: pulls, a rest, then a stage
     *  named Expansion - but in the person's own words. */
    static Model.Routine personsOwn(Model m) {
        m.sets.add(Model.Set.fixed("p1", "Pull", 30, 0, 300, 30, 75, 990));
        m.sets.add(Model.Set.fixed("p2", "Expand", 34, 0, 90, 30, 75, 600));
        Model.Routine r = new Model.Routine();
        r.id = "own"; r.name = "My two tubes";
        r.stages.add(Model.Stage.restOf("Tunica release", 300));
        r.stages.add(Model.Stage.of("Strain holds", Model.STAGE_WORK, new String[]{ "p1" }));
        r.stages.add(Model.Stage.restOf("Swap now", 120));
        r.stages.add(Model.Stage.of("Expansion", Model.STAGE_WORK, new String[]{ "p2" }));
        m.routines.add(r);
        return r;
    }

    static String flags(Model.Routine r) {
        StringBuilder b = new StringBuilder();
        for (Model.Stage st : r.stages) b.append(st.manual).append('/').append(st.awaitAck).append(' ');
        return b.toString();
    }

    @Test void theLoadRepairLeavesAPersonsOwnRoutineAlone() {
        Model m = owner();
        Model.Routine r = personsOwn(m);
        String before = flags(r);
        assertEquals(0, m.giveRoutinesTheirWaits(), "nothing the person built is changed");
        assertEquals(before, flags(r));
    }

    @Test void anOldShareCodeOfAPersonsOwnRoutineComesInAsItWasMade() throws Exception {
        Model m = owner();
        Model.Routine r = personsOwn(m);
        String before = flags(r);
        assertEquals(before, flags(owner().importShareCode(r.toShareCode(m))), "v3");
        assertEquals(before, flags(owner().importShareCode(r.toShareCodeV2(m))), "v2");
    }

    @Test void anOldVersionOfAPersonsOwnRoutineRestoresAsItWas() throws Exception {
        Model m = owner();
        Model.Routine r = personsOwn(m);
        String before = flags(r);
        m.archiveRoutineVersion(r, Model.AsRunSnapshot.of(r, m));
        assertEquals(before, flags(m.restoreRoutineVersion(r.id, 0)));
    }

    @Test void aBuilderRoutineIsStillRepairedUnmarkedOrRenamed() {
        Model m = owner();
        Model.Routine r = traction(m, 34, 5, day(m));
        List<String> want = StageWaitsCarryTest.waits(r);
        // Unmarked (a share code or a restored copy carries no track) and stripped of its flags.
        r.trainerTrack = Model.TRAINER_TRACK_NONE;
        for (Model.Stage st : r.stages) { st.manual = false; st.awaitAck = false; }
        assertTrue(Model.isBuilderRoutine(r), "the builder's own words");
        assertEquals(1, m.giveRoutinesTheirWaits());
        assertEquals(want, StageWaitsCarryTest.waits(r));
        // The changeover renamed: the release's name still says the builder made it, and the
        // changeover is found by its place.
        for (Model.Stage st : r.stages) { st.manual = false; st.awaitAck = false; }
        int over = Integer.parseInt(want.get(want.size() - 1).split("\\|")[0]);
        r.stages.get(over).name = "Swap tubes";
        assertEquals(1, m.giveRoutinesTheirWaits());
        assertEquals(want, StageWaitsCarryTest.waits(r));
        // The release renamed instead: the changeover's sentence says it.
        Model m2 = owner();
        Model.Routine r2 = traction(m2, 34, 5, day(m2));
        r2.trainerTrack = Model.TRAINER_TRACK_NONE;
        for (Model.Stage st : r2.stages) { st.manual = false; st.awaitAck = false; }
        r2.stages.get(0).name = "Release";
        assertEquals(1, m2.giveRoutinesTheirWaits());
        assertTrue(r2.stages.get(over).awaitAck, "the changeover waits");
        assertFalse(r2.stages.get(0).manual, "a release the person renamed is not known by name");
    }

    @Test void aTrainerMintIsTheBuildersWhateverItsNames() {
        Model m = owner();
        Model.Routine r = traction(m, 34, 5, day(m));
        assertNotEquals(Model.TRAINER_TRACK_NONE, r.trainerTrack, "fixture: a mint");
        for (Model.Stage st : r.stages) { st.manual = false; st.awaitAck = false; }
        r.stages.get(0).name = "Release";
        int over = -1;
        for (int i = 0; i < r.stages.size(); i++) if (r.stages.get(i).rest && i > 0) over = i;
        r.stages.get(over).name = "Swap tubes";
        assertTrue(Model.isBuilderRoutine(r));
        assertEquals(1, m.giveRoutinesTheirWaits());
        assertTrue(r.stages.get(over).awaitAck, "the changeover, by its place in a mint");
    }

    @Test void aPersonsRoutineIsNotTheBuilders() {
        Model m = owner();
        assertFalse(Model.isBuilderRoutine(personsOwn(m)));
    }
}
