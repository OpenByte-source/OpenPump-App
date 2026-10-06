package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE BUILDER'S ANSWERS, REMEMBERED (BuildCache) - and never stale.
 *
 * The Trainer tab froze for about 650 ms on every visit: the card's and the row's count
 * (RxBuild#holdsRun), the Program picker's labels (RxBuild#workShapeActs) and the row's
 * "edited" (SavedMint#edited) each built routines in a copy of the model on every draw. They
 * are remembered per input state now. Pinned: a remembered answer is the fresh build's; a
 * setting changed (saved or not), a save, an edit to the saved routine and another model all
 * ask again; and a second draw with nothing changed builds nothing.
 */
class BuildCacheTest {

    private static Model model() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerMonthsPumping = 4;
        m.ceilKpa = 40;
        return m;
    }

    private static Mint.Rx rx(Model m, long now) {
        return Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 1, 9.0 * Plan.HG,
            TrainerTab.monthIndexNow(m, now), m.ceilKpa, 13, 0, null);
    }

    /** The count a fresh build names - built outside the cache, into a copy. */
    private static int fresh(Model m, Mint.Rx rx) {
        Model s = SavedMint.scratchOf(m);
        Model.Routine r = s.routine(RxBuild.routineFromRx(s, rx));
        int n = 0;
        for (Model.Stage st : r.stages) {
            if (st.rest || st.outOfNet()) continue;
            for (String id : st.setIds) {
                Model.Set x = s.set(id);
                if (x != null && !x.rest) n += x.dur / x.cycle();
            }
        }
        return n;
    }

    private static void program(Model m, int work, int pressure) {
        Model.Program p = m.programFor(Plan.TRACK_GIRTH_INTERVAL);
        p.work = work;
        p.pressure = pressure;
    }

    @Test
    void aRememberedCountIsTheFreshBuildsAndASettingChangeAsksAgain() {
        BuildCache.clear();
        long now = System.currentTimeMillis();
        Model m = model();
        Mint.Rx rx = rx(m, now);
        int first = RxBuild.holdsRun(m, rx, RxBuild.Day.today(m));
        assertEquals(fresh(m, rx), first);
        long b = BuildCache.builds();
        assertEquals(first, RxBuild.holdsRun(m, rx, RxBuild.Day.today(m)));
        assertEquals(b, BuildCache.builds(), "asked again with nothing changed, it built");

        // A setting the build reads, changed and NOT saved: a new question, the new answer.
        program(m, Model.Program.WORK_FIXED, Model.Program.PRESS_GENTLE);
        int gentle = RxBuild.holdsRun(m, rx, RxBuild.Day.today(m));
        assertEquals(fresh(m, rx), gentle);
        assertNotEquals(first, gentle, "Gentle's make-up cycles are more holds");
        assertEquals(b + 1, BuildCache.builds());
        m.trainerGirthHybrid = true;
        assertEquals(fresh(m, rx), RxBuild.holdsRun(m, rx, RxBuild.Day.today(m)));
        m.trainerGirthHybrid = false;
        m.rxHoldSec = 180;
        assertEquals(fresh(m, rx), RxBuild.holdsRun(m, rx, RxBuild.Day.today(m)));

        // A lighter day: the day state is in the key.
        m.oversizeAccepted = true;
        assertEquals(fresh(m, rx), RxBuild.holdsRun(m, rx, RxBuild.Day.today(m)));

        // A save moves the model on: asked again, same answer.
        long c = BuildCache.builds();
        m.changed();
        assertEquals(fresh(m, rx), RxBuild.holdsRun(m, rx, RxBuild.Day.today(m)));
        assertEquals(c + 1, BuildCache.builds(), "a save did not ask again");

        // Another model - a copy read back - is never answered from this one's.
        Model copy = SavedMint.scratchOf(m);
        assertNotEquals(m.instanceId, copy.instanceId);
        long d = BuildCache.builds();
        RxBuild.holdsRun(copy, rx, RxBuild.Day.today(copy));
        assertEquals(d + 1, BuildCache.builds());
    }

    @Test
    void theShapeLabelsAndTheEditedAnswerAreRememberedAndNeverStale() {
        BuildCache.clear();
        long now = System.currentTimeMillis();
        Model m = model();
        program(m, Model.Program.WORK_FIXED, Model.Program.PRESS_STANDARD);
        Mint.Rx rx = rx(m, now);
        boolean[] acts = new boolean[4];
        for (int s = 0; s < 4; s++) acts[s] = RxBuild.workShapeActs(m, rx, s);
        // A Gentle bias leaves a ramp nothing to climb: the ramp's label changes, and is asked.
        program(m, Model.Program.WORK_FIXED, Model.Program.PRESS_GENTLE);
        BuildCache.clear();
        boolean rampGentleFresh = RxBuild.workShapeActs(m, rx, Model.Program.WORK_RAMP_IN_SET);
        program(m, Model.Program.WORK_FIXED, Model.Program.PRESS_STANDARD);
        for (int s = 0; s < 4; s++) assertEquals(acts[s], RxBuild.workShapeActs(m, rx, s));
        program(m, Model.Program.WORK_FIXED, Model.Program.PRESS_GENTLE);
        assertEquals(rampGentleFresh,
            RxBuild.workShapeActs(m, rx, Model.Program.WORK_RAMP_IN_SET), "a stale label");
        program(m, Model.Program.WORK_FIXED, Model.Program.PRESS_STANDARD);

        // The saved routine's "edited": an edit to it - not yet saved - is a new question.
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        String sig = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
        assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, null, now));
        assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, null, now));
        Model.Set last = null;
        for (Model.Stage st : r.stages)
            if (!st.rest && st.colour == Model.STAGE_WORK && !st.fatigueBlock)
                last = m.set(st.setIds.get(st.setIds.size() - 1));
        last.uh += 10;
        assertTrue(SavedMint.edited(m, r, sig, 0, 0, now, null, now), "a stale 'not edited'");
        last.uh -= 10;
        assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, null, now));
    }

    /** What one Trainer draw asks of the builder: the card's and the row's counts, the row's
     *  "edited" and whether the track speaks, and the Program picker's four labels. */
    private static void draw(Model m, Mint.Rx rx, Model.Routine saved, String sig, long now) {
        RxBuild.holdsRun(m, rx, RxBuild.Day.today(m));
        RxBuild.holdsRun(m, rx, RxBuild.Day.today(m).adjusted(null));
        SavedMint.edited(m, saved, sig, 0, 0, now, null, now);
        SavedMint.edited(m, saved, sig, 0, 0, now, null, now);
        for (int s = 0; s < 4; s++) RxBuild.workShapeActs(m, rx, s);
    }

    @Test
    void aSecondDrawWithNothingChangedBuildsNothing() {
        BuildCache.clear();
        long now = System.currentTimeMillis();
        Model m = model();
        program(m, Model.Program.WORK_RAMP_IN_SET, Model.Program.PRESS_GENTLE);
        Mint.Rx rx = rx(m, now);
        Model.Routine saved = m.routine(RxBuild.routineFromRx(m, rx));
        String sig = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, now));
        long before = BuildCache.builds();
        draw(m, rx, saved, sig, now);
        assertTrue(BuildCache.builds() > before, "the first draw built nothing");
        long after = BuildCache.builds();
        draw(m, rx, saved, sig, now);
        assertEquals(after, BuildCache.builds(), "a second draw with nothing changed built "
            + (BuildCache.builds() - after) + " time(s)");
        // A save between the draws: asked again.
        m.changed();
        draw(m, rx, saved, sig, now);
        assertTrue(BuildCache.builds() > after);
    }
}
