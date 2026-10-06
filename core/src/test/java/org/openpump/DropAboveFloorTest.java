package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A DROP BETWEEN HOLDS MAY GO ABOVE −3.0 inHg (owner, 2026-09-30, final).
 *
 * The drop was capped at 10 kPa (−3.0 inHg) on every road that sets it. Now:
 *   - THE CAP: a drop may go up to 1.0 inHg under that hold's pull - drop <= pull − 4 kPa (1.0
 *     inHg is 3.39 kPa; the wire is whole kPa, so the gap rounds UP to 4), never at or above
 *     the pull. A drop at or under 10 kPa keeps the old rule (under the pull) alone.
 *   - THE WARNING: from at or under 10 kPa to above it is asked once, then it is the person's
 *     call. 0 is still "all the way off"; a saved drop is never brought back down to 10.
 *   - THE COUNTING: drop seconds never count as work whatever the drop's pressure - net and
 *     dose leave the drop out BY PHASE, not by pressure.
 *
 * The owner's profile: girth −9 inHg (30 kPa on the wire), length −10 (34 kPa).
 */
class DropAboveFloorTest {

    private String unitBefore;
    @BeforeEach void inHg() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_INHG; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    private static final int GIRTH = 30, LENGTH = 34;

    /* ------------------------------------------------------------------ the cap rule */

    @Test
    void aDropMayGoToOneInchOfMercuryUnderItsPull() {
        assertEquals(4, RunEdit.DROP_GAP_KPA, "1.0 inHg = 3.39 kPa, rounded UP to whole kPa");
        assertEquals(26, RunEdit.dropTopKpa(GIRTH), "girth at −9: the drop may reach 26 kPa");
        assertEquals(30, RunEdit.dropTopKpa(LENGTH), "length at −10: 30 kPa");
        assertEquals(RunEdit.DROP_FLOOR_KPA, RunEdit.dropTopKpa(12),
            "under a low pull the old 10 kPa still stands (and clampLower keeps it under it)");
        assertEquals(26, RunEdit.dropUnder(29, GIRTH), "above the floor: held 4 under the pull");
        assertEquals(20, RunEdit.dropUnder(20, GIRTH), "inside the gap: as set");
        assertEquals(10, RunEdit.dropUnder(10, 11), "at or under the floor: untouched");
        assertEquals(0, RunEdit.dropUnder(0, GIRTH), "0 is a real answer, all the way off");
    }

    @Test
    void thePreferenceIsKeptAndEachHoldsPullHoldsIt() {
        assertEquals(20, Mint.clampDropKpa(20), "a saved 20 kPa is never brought back to 10");
        assertEquals(0, Mint.clampDropKpa(-3));
        assertEquals(RunEdit.dropTopKpa((int) Plan.ABSOLUTE_CAP_KPA), Mint.clampDropKpa(999),
            "what may be saved: 1.0 inHg under the highest pull anything prescribes");
        assertEquals(20, Mint.dropFor(20, GIRTH));
        assertEquals(26, Mint.dropFor(40, GIRTH), "held 1.0 inHg under this hold's pull");
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 1, 30.0, 0, 40, null);
        Mint.SetSpec w = Mint.workSet(rx, 99);
        assertEquals(RunEdit.dropTopKpa(rx.pressureKpa), w.lo, "the work set's drop");
        assertTrue(w.lo <= w.up - RunEdit.DROP_GAP_KPA);
        // The trainer's own default is as it was.
        assertEquals(3, new Model().rxDropKpa);
        assertEquals(Mint.DROP_KPA, Mint.workSet(rx).lo);
        // A saved drop above the floor survives the write-time clamps and a round trip.
        Model m = new Model();
        m.rxDropKpa = 20;
        m.clampAll();
        assertEquals(20, m.rxDropKpa, "clampAll keeps it");
        assertEquals(20, Model.fromJson(m.toJson()).rxDropKpa, "and so does a save");
    }

    @Test
    void comingStepsRaisesADropPastTheFloorUpToTheGap() {
        assertNull(ComingSteps.dropRefusal(10, 11, GIRTH), "past the floor: asked, not refused");
        assertNull(ComingSteps.dropRefusal(25, 26, GIRTH));
        assertEquals(RunEdit.dropGapSaid(), ComingSteps.dropRefusal(26, 27, GIRTH));
        assertEquals("The drop stays below the pull.", ComingSteps.dropRefusal(11, 12, 12));
        assertNull(ComingSteps.dropRefusal(26, 25, GIRTH), "coming down is always allowed");

        Model.Preset p = built(GIRTH, 3, 5);
        assertEquals(RunEdit.DROP_FLOOR_KPA, p.loMax, "built under the floor: the floor");
        ComingSteps.applyDrop(p, 20);
        assertEquals(20, p.lo, "the person's 20 kPa, not the floor");
        assertEquals(20, RunEdit.capDrop(p, 20), "and the wire keeps it (its highest rose)");
        ComingSteps.applyDrop(p, 29);
        assertEquals(26, p.lo, "never past 1.0 inHg under the pull");
        ComingSteps.applyDrop(p, 0);
        assertEquals(0, p.lo, "all the way off is still a drop");
    }

    @Test
    void theStripRaisesTheDropAndHoldsThePullAboveIt() {
        int drop = QuickAdjust.DROP, pull = QuickAdjust.PULL;
        assertNull(QuickAdjust.refusal(drop, 10, 1, GIRTH, -1, 57), "past 10: asked, not refused");
        assertNull(QuickAdjust.refusal(drop, 25, 1, GIRTH, -1, 57));
        assertEquals(RunEdit.dropGapSaid(), QuickAdjust.refusal(drop, 26, 1, GIRTH, -1, 57));
        assertEquals("The drop stays below the pull.", QuickAdjust.refusal(drop, 11, 1, 12, -1, 57));
        // The pull may not come down to within the gap of a drop above the floor...
        assertEquals(RunEdit.dropGapSaid(), QuickAdjust.refusal(pull, 30, -1, 27, -1, 57));
        assertNull(QuickAdjust.refusal(pull, 31, -1, 26, -1, 57));
        // ...and with a drop at or under the floor the old rule stands.
        assertNull(QuickAdjust.refusal(pull, 14, -1, 10, -1, 57));
    }

    @Test
    void aRaiseAllowedOnAStepCarriesThroughTheShiftAndNoSideEffectRaisesIt() {
        List<Model.Preset> plan = ramp(20, 5, 34, 8, 4);
        // "Rest of this ramp" with the drop raised by 20: held at the floor, as before.
        new SetShift().shift(plan, 0, RunEdit.remainingStepsOfSet(plan, 0),
            new int[]{ 20, 5, 60, 5, 60 }, new int[]{ 20, 25, 60, 5, 60 }, 57);
        for (int k = 1; k < plan.size(); k++)
            assertTrue(plan.get(k).lo <= RunEdit.DROP_FLOOR_KPA, "a side effect never passes 10");
        // The person raised it on the strip: their drop is allowed on the set's steps.
        plan = ramp(20, 5, 34, 8, 4);
        for (Model.Preset p : plan) RunEdit.allowDrop(p, 25);
        new SetShift().shift(plan, 0, RunEdit.remainingStepsOfSet(plan, 0),
            new int[]{ 20, 5, 60, 5, 60 }, new int[]{ 20, 25, 60, 5, 60 }, 57);
        for (int k = 1; k < plan.size(); k++) {
            Model.Preset p = plan.get(k);
            assertTrue(p.lo > RunEdit.DROP_FLOOR_KPA, "step " + k + " keeps the raise: " + p.lo);
            assertTrue(p.lo <= Math.max(RunEdit.DROP_FLOOR_KPA, p.up - RunEdit.DROP_GAP_KPA),
                "step " + k + " stays 1.0 inHg under its pull");
        }
        // A step that only holds is never raised.
        Model.Preset hold = built(GIRTH, GIRTH - 1, 0);
        int was = hold.loMax;
        RunEdit.allowDrop(hold, 25);
        assertEquals(was, hold.loMax);
    }

    /* ------------------------------------------------------------- the warning */

    @Test
    void theWarningIsAskedOnceFromTheFloorUp() {
        assertTrue(RunEdit.dropNeedsWarning(10, 11, false), "from −3.0 to past it: asked");
        assertTrue(RunEdit.dropNeedsWarning(3, 20, false));
        assertFalse(RunEdit.dropNeedsWarning(10, 11, true), "confirmed this run: not again");
        assertFalse(RunEdit.dropNeedsWarning(11, 12, false), "already above: it was asked");
        assertFalse(RunEdit.dropNeedsWarning(3, 10, false), "up to −3.0 is never asked");
        assertFalse(RunEdit.dropNeedsWarning(12, 11, false), "never on the way down");
        assertEquals("A drop above −3.0 inHg is a smaller release between holds — the "
            + "guidance keeps it at −3.0 inHg or less. Keep it?", RunEdit.dropWarning());
        // The rule as the owner set it (1.0 inHg), in the person's unit - never the rounded
        // wire figure (4 kPa reads 1.2 inHg).
        assertEquals("The drop stays at least 1.0 inHg under the pull.", RunEdit.dropGapSaid());
        Model.Fmt.unit = Model.Fmt.U_KPA;
        assertEquals("The drop stays at least 3.4 kPa under the pull.", RunEdit.dropGapSaid());
        Model.Fmt.unit = Model.Fmt.U_INHG;
    }

    /* ------------------------------------------------- the counting excludes the drop */

    private static final long FRAME_MS = 250L;

    /** One cycle: `holdS` s at the pull, `dropS` s at the drop, every frame told its phase. */
    private static long cycle(Session s, long t, int holdS, double pull, int dropS, double drop,
                              boolean markDrop) {
        for (long end = t + holdS * 1000L; t < end; t += FRAME_MS) {
            s.noteSample(t, pull, false, 3000L, false);
            s.noteHoldFrame(t, pull, false, pull, Session.HOLD_PHASE_HOLD, 0, false, false);
        }
        for (long end = t + dropS * 1000L; t < end; t += FRAME_MS) {
            // The dose is told the drop half where the app tells it: a drop above the floor.
            s.noteSample(t, drop, false, 3000L, TupClock.doseLeavesOut(markDrop, (int) drop));
            s.noteHoldFrame(t, drop, false, pull, Session.HOLD_PHASE_HOLD, 0, false, markDrop);
        }
        return t;
    }

    @Test
    void dropSecondsNeverCountAsWorkWhateverTheDropsPressure() {
        double floor = Plan.L1_FLOOR_KPA;            // 16.93 - a 26 kPa drop is well above it
        Session high = new Session();
        high.beginRun(0L);
        long t = 0L;
        for (int c = 0; c < 4; c++) t = cycle(high, t, 60, GIRTH, 10, 26, true);
        high.noteSample(t, GIRTH, false, 3000L, false);
        high.noteHoldFrame(t, GIRTH, false, GIRTH, Session.HOLD_PHASE_HOLD, 0, false, false);

        Session low = new Session();
        low.beginRun(0L);
        t = 0L;
        for (int c = 0; c < 4; c++) t = cycle(low, t, 60, GIRTH, 10, 3, true);
        low.noteSample(t, GIRTH, false, 3000L, false);
        low.noteHoldFrame(t, GIRTH, false, GIRTH, Session.HOLD_PHASE_HOLD, 0, false, false);

        double[] tup = high.netGrossTupSec(floor, 0.0);
        assertEquals(4 * 60.0, tup[0], 1.0, "net: the four holds, not one drop second");
        assertEquals(4 * 70.0, tup[1], 1.0, "gross is sealed time - the drops are in it");
        assertEquals(low.netGrossTupSec(floor, 0.0)[0], tup[0], 1e-9,
            "a drop at 26 kPa nets exactly what a drop at 3 kPa does");
        // The dose: the 26 kPa drop's seconds add nothing (left out by phase). What is left
        // between the two runs is the one sample interval at each turn - the hold-to-drop one
        // the 3 kPa run counts as it always did, the drop-to-hold one each counts from what
        // its first sample read: 2.5 and 2.5 against 0 and 4.5 kPa·s a cycle.
        assertEquals(low.deliveredDoseKpaS() - 4 * 0.5, high.deliveredDoseKpaS(), 1e-6,
            "and adds no dose: the drop is left out by phase, not by where it reads");
        assertTrue(high.deliveredDoseKpaS() > 0, "the holds' dose is there");

        // The control: the same run with the drop NOT marked would have counted it.
        Session unmarked = new Session();
        unmarked.beginRun(0L);
        t = 0L;
        for (int c = 0; c < 4; c++) t = cycle(unmarked, t, 60, GIRTH, 10, 26, false);
        assertTrue(unmarked.netGrossTupSec(floor, 0.0)[0] > tup[0] + 30,
            "unmarked, 26 kPa is above the floor and would count - the phase is what keeps it out");
        assertTrue(unmarked.deliveredDoseKpaS() > high.deliveredDoseKpaS() + 100);
    }

    @Test
    void thePlannedFiguresCountTheHoldHalfWhateverTheDrop() {
        Model.Set lowDrop = Model.Set.fixed("a", "Work", GIRTH, 3, 60, 10, 75, 70 * 4);
        Model.Set highDrop = Model.Set.fixed("b", "Work", GIRTH, 26, 60, 10, 75, 70 * 4);
        assertEquals(Model.estDoseKpaS(lowDrop), Model.estDoseKpaS(highDrop), 1e-9,
            "the estimated dose counts the pull's share of the cycle only");
        assertEquals(TupClock.holdPartMs(60, 10, 3, GIRTH, false, 280_000L),
            TupClock.holdPartMs(60, 10, 26, GIRTH, false, 280_000L),
            "the planned time under pressure is the hold half, whatever the drop");
        assertTrue(TupClock.inDrop(60, 10, 26, GIRTH, false, 65_000L),
            "a 26 kPa drop is still a drop to the clock");
    }

    /* ------------------------------------------------------------------- helpers */

    private static Model.Preset built(int up, int lo, int lh) {
        Model.Set s = Model.Set.fixed("F", "Fixed", up, lo, 60, lh, 60, (60 + lh) * 3);
        Model.Preset p = s.ladder().get(0);
        p.setId = "F";
        return RunEdit.asBuilt(p);
    }

    private static List<Model.Preset> ramp(int up, int lo, int up2, int lo2, int steps) {
        Model.Set s = Model.Set.ramp("R", "Ramp", up, lo, 60, 5, 60, up2, lo2, 60, 5, 80,
                                     steps, steps * 130);
        List<Model.Preset> plan = new ArrayList<Model.Preset>(s.ladder());
        for (Model.Preset p : plan) { p.setId = "R"; p.pos = 0; p.stageIdx = 0; RunEdit.asBuilt(p); }
        return plan;
    }
}
