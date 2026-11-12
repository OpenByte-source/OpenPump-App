package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * "+ NEW SET" FROM A STAGE ADDS THE SET TO THAT STAGE. Found on an emulator run: a set made
 * with the stage picker's "+ New set" and saved was in the library but not in the stage, and
 * had to be picked from the list afterwards. The picker's Add and the new set's Save now go
 * through one Model#addSetsToStage, which records the routine version as Add always did.
 */
class NewSetJoinsStageTest {
    static Model model() {
        Model m = new Model(); m.sets.clear(); m.routines.clear();
        Model.Set a = Model.Set.fixed("sa", "First", 20, 10, 30, 5, 75, 120); m.sets.add(a);
        Model.Routine r = new Model.Routine(); r.id = "r1"; r.name = "New routine";
        Model.Stage w = new Model.Stage(); w.name = "Work"; w.setIds.add(a.id); r.stages.add(w);
        Model.Stage x = new Model.Stage(); x.name = "Second"; r.stages.add(x);
        m.routines.add(r); return m;
    }

    @Test void theNewSetLandsInTheStageItWasMadeFrom() {
        Model m = model();
        Model.Set fresh = Model.Set.fresh(m.newSetId(), m.ceilKpa); m.sets.add(fresh);
        int history = m.routine("r1").history.size();
        assertEquals(1, m.addSetsToStage("r1", 1, Collections.singletonList(fresh.id)));
        assertEquals(Arrays.asList(fresh.id), m.routine("r1").stages.get(1).setIds);
        assertEquals(Arrays.asList("sa"), m.routine("r1").stages.get(0).setIds, "other stages untouched");
        assertEquals(history + 1, m.routine("r1").history.size(), "a shape change cuts a version, as Add does");
    }

    @Test void nothingIsAddedWhereThereIsNothingToAddTo() {
        Model m = model();
        assertEquals(0, m.addSetsToStage("r1", 1, Collections.singletonList("gone")), "a set that no longer exists");
        assertEquals(0, m.addSetsToStage("r1", 5, Collections.singletonList("sa")), "a stage that no longer exists");
        assertEquals(0, m.addSetsToStage("nope", 0, Collections.singletonList("sa")), "a routine that no longer exists");
        assertEquals(0, m.routine("r1").stages.get(1).setIds.size());
        assertEquals(0, m.routine("r1").history.size(), "no version for an edit that did not happen");
    }
}
