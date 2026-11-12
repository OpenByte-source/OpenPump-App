package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import static org.openpump.T10Fix.*;
import org.junit.jupiter.api.Test;

/**
 * A ROUTINE ALREADY IN THE LIBRARY GETS ITS PULLS' MARKERS BACK WHEN THE APP OPENS.
 *
 * A length session added from a share code (or restored from a version) before the markers
 * travelled sits in the library with its Fatigue holds and Strain holds as plain girth work.
 * The load (and a backup restore) gives them back by the same builder-only rule the old-code
 * import uses (Model#giveStagesTheirMarks), as it gives the waits back: only a routine the
 * trainer made, only ever adding a marker. A routine that is already right, and a routine the
 * person built - whatever its stages are called - come through the load byte for byte.
 */
class LoadMarksRepairTest {

    /** The length session as an old code put it in the library: no track, no marker. */
    static Model.Routine importedBeforeTheFix(Model m) throws Exception {
        Model.Routine got = m.importShareCode(StageWaitsCarryTest.OLD_LENGTH_V3);
        for (Model.Stage st : got.stages) { st.traction = false; st.fatigueBlock = false; }
        assertEquals(Model.TRAINER_TRACK_NONE, got.trainerTrack, "fixture: a code carries no track");
        return got;
    }

    /** A routine the person built, shaped like the builder's: the pulls' names, an expansion -
     *  but none of the builder's own words (the release's name, the changeover's sentence). */
    static Model.Routine lookalike(Model m) {
        m.sets.add(Model.Set.fixed("lp", "Pull", 20, 0, 60, 30, 60, 600));
        Model.Routine q = new Model.Routine();
        q.id = "look"; q.name = "Mine";
        q.stages.add(Model.Stage.restOf("Release", 60));
        q.stages.add(Model.Stage.of(Plan.FATIGUE_NAME, Model.STAGE_WORK, new String[]{ "lp" }));
        q.stages.add(Model.Stage.of(Plan.STRAIN_NAME, Model.STAGE_WORK, new String[]{ "lp" }));
        q.stages.add(Model.Stage.restOf("Swap tubes", 120));
        q.stages.add(Model.Stage.of(Plan.CODA_NAME, Model.STAGE_WORK, new String[]{ "lp" }));
        m.routines.add(q);
        assertFalse(Plan.hasBuilderWords(q.stages), "fixture: none of the builder's words");
        return q;
    }

    @Test void anImportedLengthSessionGetsItsPullsBackOnLoad() throws Exception {
        Model src = owner();
        Model.Routine mint = StageShapeCarryTest.lengthMint(src);
        Model m = owner();
        Model.Routine r = importedBeforeTheFix(m);
        assertEquals(1, m.giveRoutinesTheirMarks());
        assertEquals(StageShapeCarryTest.marks(mint), StageShapeCarryTest.marks(r),
            "the Fatigue and Strain holds are length holds again, the fatigue block marked");
        assertEquals(0, m.giveRoutinesTheirMarks(), "a second load changes nothing");
    }

    @Test void aMintWhoseWordsWereRenamedIsKnownByItsTrack() {
        Model m = owner();
        Model.Routine r = StageShapeCarryTest.lengthMint(m);
        String want = StageShapeCarryTest.marks(r).toString();
        r.stages.get(0).name = "Release";
        for (Model.Stage st : r.stages) {
            if (st.awaitAck) st.name = "Swap tubes";
            st.traction = false; st.fatigueBlock = false;
        }
        assertFalse(Plan.hasBuilderWords(r.stages), "fixture: no builder words left");
        assertEquals(1, m.giveRoutinesTheirMarks());
        assertEquals(want, StageShapeCarryTest.marks(r).toString());
    }

    @Test void aCorrectRoutineAndAPersonsLookalikeAreByteForByteAfterTheLoad() throws Exception {
        Model m = owner();
        StageShapeCarryTest.lengthMint(m);                 // a trainer routine, already right
        Model.Routine imported = importedBeforeTheFix(m);
        m.giveRoutinesTheirMarks();                       // ...and one repaired before
        Model.Routine look = lookalike(m);
        StageShapeCarryTest.everyMarker(m);                // a person's own markers
        String before = m.toJson();
        assertEquals(0, m.giveRoutinesTheirMarks(), "nothing to give");
        assertEquals(before, m.toJson(), "every routine byte for byte");
        for (Model.Stage st : look.stages) assertFalse(st.traction || st.fatigueBlock,
            "a routine the person built is never given a marker");
        assertNotNull(imported);
    }

    @Test void theRepairDoesNotChangeWhatARunsComparisonReports() throws Exception {
        Model m = owner();
        Model.Routine r = importedBeforeTheFix(m);
        Model.AsRunSnapshot snap = Model.AsRunSnapshot.of(r, m);
        Model.AsRunSnapshot old = StageShapeCarryTest.oldSnapshot(r, m);
        assertTrue(snap.matches(r, m) && old.matches(r, m), "fixture: both match");
        assertEquals(1, m.giveRoutinesTheirMarks());
        assertTrue(snap.matches(r, m), "a run before the repair is not \"routine edited\"");
        assertTrue(old.matches(r, m), "nor is one recorded before the shape was kept");
        assertTrue(snap.matchesStructure(r) && old.matchesStructure(r));
    }
}
