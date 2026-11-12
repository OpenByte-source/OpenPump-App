package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import static org.openpump.T10Fix.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UNDO IN THE "USED IN" SCREEN PUTS EVERY ROUTINE BACK EXACTLY.
 *
 * Replacing or removing a set from the Used-in screen snapshots every routine's stages first
 * (SessionActivity#usedSnapshot, through Model#copyStages) so "Undo" can put them back. The
 * snapshot copied only each stage's name, colour and sets: Undo turned a rest stage into an
 * empty stage that runs nothing, dropped the release's wait for Done and the changeover's
 * wait for "I've swapped", and lost which tube a stage runs in and that a stage is length
 * work. Undo now puts every stage back as it was, field for field.
 */
class UsedUndoStagesTest {

    /** Every instance field of Stage, read reflectively, so a field added later is covered
     *  without anyone remembering to add it here. */
    static String fields(Model.Stage st) throws Exception {
        StringBuilder b = new StringBuilder();
        for (Field f : Model.Stage.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            b.append(f.getName()).append('=').append(f.get(st)).append(';');
        }
        return b.toString();
    }

    @Test void undoRestoresALengthRoutinesStagesExactly() throws Exception {
        Model m = owner();
        Model.Routine r = traction(m, 34, 5, day(m));
        List<Model.Stage> snap = Model.copyStages(r.stages);
        assertEquals(r.stages.size(), snap.size());
        for (int i = 0; i < r.stages.size(); i++)
            assertEquals(fields(r.stages.get(i)), fields(snap.get(i)), "stage " + i + ": " + r.stages.get(i).name);
        // ...and the run the restored routine plays waits where the original's did.
        Model.Routine back = new Model.Routine();
        back.id = "back";
        back.stages.addAll(snap);
        m.routines.add(back);
        assertEquals(StageWaitsCarryTest.planWaits(m, r), StageWaitsCarryTest.planWaits(m, back));
    }

    @Test void everyFieldOfAStageIsCopied() throws Exception {
        // Every flag on at once, sets included - not a shape the app builds, so the flags are
        // set by reflection (a manual stage with a set is what invariant 29 forbids in code).
        Model.Stage st = Model.Stage.of("Swap", 0x12345678, new String[]{ "x" });
        for (Field f : Model.Stage.class.getDeclaredFields())
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == boolean.class) {
                f.setAccessible(true);
                f.setBoolean(st, true);
            }
        st.cylinderId = "L";
        st.restSec = 777;
        Model.Stage c = Model.copyStages(java.util.Collections.singletonList(st)).get(0);
        assertEquals(fields(st), fields(c));
        assertNotSame(st, c);
        assertNotSame(st.setIds, c.setIds, "its own list: a later edit does not reach the snapshot");
        c.setIds.add("y");
        assertEquals(1, st.setIds.size());
    }
}
