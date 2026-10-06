package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import org.junit.jupiter.api.Test;

/**
 * FIX11 F1 (device check EMU12, High) - "Undo this change" on a plan rewrite notice put back
 * the old routine's stages, whose sets the rewrite had already deleted (Model#gcOrphanSets's
 * rule, in SavedMint#rewrite): the routine came back with 0 cycles and START offered to run
 * it. Undo now restores a complete routine - its sets kept with the notice - and the hold
 * length the change came from; a notice that cannot be restored whole is refused, and START
 * never offers a routine with no hold in it.
 */
class PlanUndoTest {

    /** The owner at Level 3 with a 45 s fatigue block saved as the plan's routine, and the
     *  notice the round-11 rewrite to 30 s writes - captured as SessionActivity#applyPlanTo
     *  captures it, before the rewrite. */
    private static final class Rewritten {
        Model m;
        Model.Routine r;
        Model.PlanNotice n;
        int cyclesBefore;
        java.util.List<String> oldIds = new java.util.ArrayList<String>();
    }

    private static Rewritten rewritten(boolean keepSets) throws Exception {
        Rewritten w = new Rewritten();
        Model m = owner();
        m.rxFatigueHoldSec = 45;
        Mint.Rx rx = girth(Plan.L3, 10, 30);
        Model.Routine r = build(m, rx, day(m));
        asMint(m, m.trainerGirth, rx, r);
        m.selected = r.id;
        w.cyclesBefore = m.workCycles(r);
        for (int i = 0; i < r.stages.size(); i++) w.oldIds.addAll(r.stages.get(i).setIds);
        Model.PlanNotice n = new Model.PlanNotice();
        n.track = Plan.TRACK_GIRTH_INTERVAL;
        n.routineId = r.id;
        n.prevRoutine = r.toJson().toString();
        if (keepSets) {
            n.prevSets = PlanUndo.setsJson(m, r);
            n.prevSig = m.trainerGirth.lastMintSig;
        }
        // The choice: 30 s, and the plan rewrites the routine in place.
        m.rxFatigueHoldSec = 30;
        assertTrue(SavedMint.rewrite(m, r, rx, 0, 0));
        m.planNotice = n;
        w.m = m; w.r = r; w.n = n;
        return w;
    }

    @Test
    void theRewriteDeletesTheOldSetsSoTheOldUndoLeftARoutineOfNoCycles() throws Exception {
        Rewritten w = rewritten(false);
        int gone = 0;
        for (int i = 0; i < w.oldIds.size(); i++) if (w.m.set(w.oldIds.get(i)) == null) gone++;
        assertTrue(gone > 0, "the old shape's plan-written sets are deleted by the rewrite");
        // What Undo used to do: the stages alone.
        Model.Routine back = Model.Routine.fromJson(new org.json.JSONObject(w.n.prevRoutine));
        w.r.stages.clear();
        w.r.stages.addAll(back.stages);
        assertEquals(0, w.m.workCycles(w.r), "the device check's 0 cycles");
        assertTrue(NothingToRun.is(w.m, w.r), "and START now refuses it rather than run it");
    }

    @Test
    void undoPutsBackAWholeRoutineThatRuns() throws Exception {
        Rewritten w = rewritten(true);
        assertEquals(PlanUndo.RESTORED, PlanUndo.restore(w.m, w.n));
        assertEquals(0, PlanUndo.missingSets(w.m, w.r), "every set it names is there");
        assertEquals(w.cyclesBefore, w.m.workCycles(w.r), "the cycles it ran before the change");
        assertFalse(NothingToRun.is(w.m, w.r));
        java.util.List<Integer> fat = holdSecs(w.m, w.r, stage(w.r, "fatigue"));
        assertEquals(10, fat.size(), "10 x 45 s, as it was");
        assertEquals(45, fat.get(0).intValue());
    }

    @Test
    void undoPutsTheSettingItReversesBack() throws Exception {
        Rewritten w = rewritten(true);
        assertEquals(30, w.m.rxFatigueHoldSec);
        PlanUndo.restore(w.m, w.n);
        assertEquals(45, w.m.rxFatigueHoldSec, "the fatigue holds back at 45 s");
        // So today's plan builds exactly the routine put back - nothing is offered again.
        Model.Routine fresh = build(w.m, girth(Plan.L3, 10, 30), day(w.m));
        assertEquals(SavedMint.workPrint(w.m, fresh), SavedMint.workPrint(w.m, w.r));
    }

    @Test
    void anUpgradersRoutineFromBeforeHoldLengthsGoesBackTo45Seconds() {
        Model m = owner();
        m.rxFatigueHoldSec = 30;
        String sig = Mint.signature(girth(Plan.L3, 10, 30), m.mintShapeTag(
            Plan.TRACK_GIRTH_INTERVAL, Plan.L3, NOW)).replace(":F30", "");
        PlanUndo.restoreSettings(m, sig);
        assertEquals(Mint.HOLD_FATIGUE_SEC, m.rxFatigueHoldSec, "no F token: built at 45 s");
        m.rxHoldSec = 90;
        PlanUndo.restoreSettings(m, sig.replace(":H0", ":H150"));
        assertEquals(150, m.rxHoldSec, "the work hold it was built with");
        PlanUndo.restoreSettings(m, "");
        assertEquals(150, m.rxHoldSec, "no signature: nothing moves");
    }

    @Test
    void aNoticeThatKeptNoSetsIsRefusedAndTheRoutineStaysRunnable() throws Exception {
        Rewritten w = rewritten(false);
        String printNow = SavedMint.workPrint(w.m, w.r);
        int setsNow = w.m.sets.size();
        assertEquals(PlanUndo.INCOMPLETE, PlanUndo.restore(w.m, w.n));
        assertEquals(printNow, SavedMint.workPrint(w.m, w.r), "the working routine is untouched");
        assertEquals(setsNow, w.m.sets.size(), "nothing half-restored");
        assertTrue(w.m.workCycles(w.r) > 0);
        assertEquals(30, w.m.rxFatigueHoldSec, "and the setting is left as it is");
    }

    @Test
    void startSaysWhyARoutineHasNothingToRun() {
        Model m = owner();
        Model.Routine r = new Model.Routine();
        r.id = "rb";
        r.name = "Girth L3";
        r.stages.add(Model.Stage.of("Work 1", Model.STAGE_WORK, new String[] { "gone1", "gone2" }));
        r.stages.add(Model.Stage.restOf("Rest", 180));
        m.routines.add(r);
        assertTrue(NothingToRun.is(m, r));
        assertEquals("Girth L3 has nothing to run", NothingToRun.title(r));
        assertEquals("It has no holds to run: 2 of its sets are missing, so it would run only "
            + "its rests. Rebuild it from the plan to get the plan's routine back. Nothing runs "
            + "until then.", NothingToRun.why(m, r, true));
        assertTrue(NothingToRun.why(m, r, false).endsWith(
            "Open it in Routines and add sets to its stages. Nothing runs until then."));
        Mint.Rx rx = girth(Plan.L3, 10, 30);
        assertFalse(NothingToRun.is(m, build(m, rx, day(m))), "a built routine runs");
    }

    @Test
    void theNoticeKeepsItsSetsThroughASave() throws Exception {
        Rewritten w = rewritten(true);
        Model back = Model.fromJson(w.m.toJson());
        assertEquals(w.n.prevSets, back.planNotice.prevSets);
        assertEquals(w.n.prevSig, back.planNotice.prevSig);
        assertEquals(PlanUndo.RESTORED, PlanUndo.restore(back, back.planNotice));
        assertEquals(w.cyclesBefore, back.workCycles(back.routine(w.r.id)));
        org.json.JSONObject old = w.n.toJson();
        old.remove("prevSets");
        old.remove("prevSig");
        Model.PlanNotice o = Model.PlanNotice.fromJson(old);
        assertEquals("", o.prevSets, "an older notice kept no sets");
        assertEquals("", o.prevSig);
    }
}
