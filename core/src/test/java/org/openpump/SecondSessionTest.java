package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * t10 R-05 (P4) - THE SECOND SESSION WITHIN 30 MINUTES HAS NO WARM-UP: skipped by the plan
 * (TrainerTab#dayChoice), not offered. Length second keeps its strain and fatigue holds (fix
 * g); girth second keeps its fatigue block unless girth follows length (R-07). Beyond 30
 * minutes both warm up. The run is the builder's own build for the day (RunShape, RxBuild
 * #dayRun): no warm-up and no carry ramp after it.
 */
class SecondSessionTest {

    /** The owner's day: L3 girth at 30 kPa, 14 sets, and length pulling 34 kPa, 6 strain sets,
     *  both saved as the plan's mints. */
    static Model ownerDay() {
        Model m = owner();
        rack(m);
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerLength.loadLb = Traction.loadLbAtBore(34, 4.5);
        m.trainerLength.strainSets = 6;
        m.trainerLength.level = Plan.L2;
        Mint.Rx g = girth(Plan.L3, 14, 30);
        asMint(m, m.trainerGirth, g, build(m, g, day(m)));
        Mint.Rx l = length(34);
        asMint(m, m.trainerLength, l, build(m, l, day(m)));
        return m;
    }

    static String json(Model.Routine r) {
        try { return r.toJson().toString(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static Model.Routine girthSaved(Model m) { return m.routine(m.trainerGirth.lastMintId); }

    static Model.Routine lengthSaved(Model m) { return m.routine(m.trainerLength.lastMintId); }

    @Test void theFixtureIsTheOwnersDay() {
        Model m = ownerDay();
        assertTrue(Say.isTraction(lengthSaved(m)), "the length mint pulls");
        // EXPECTATION CHANGED (R11-5): 51.50 before - the fatigue block's 30 s holds add 25 s.
        assertEquals(51.92, minutes(m, girthSaved(m)), 1e-9);
        assertEquals(67.08, minutes(m, lengthSaved(m)), 1e-9);
    }

    @Test void theWindowIsHalfAnHourAndAutomatic() {
        assertEquals(30L * 60_000L, SameDay.WARM_SKIP_WINDOW_MS);
        assertTrue(SameDay.warmSkipAuto(NOW - 30 * MIN, NOW));
        assertFalse(SameDay.warmSkipAuto(NOW - 31 * MIN, NOW));
        assertFalse(SameDay.warmSkipAuto(0L, NOW), "nothing filed today");
        assertEquals(10, SameDay.warmSkipAskMinutes(NOW - 10 * MIN, NOW));
    }

    @Test void girthTenMinutesAfterLengthHasNoWarmUp() {
        Model m = ownerDay();
        filed(m, lengthSaved(m), NOW - 10 * MIN, 55);
        Model.Routine saved = girthSaved(m);
        RunShape.Choice c = TrainerTab.dayChoice(m, saved, NOW, false, false);
        assertTrue(c.skipWarm, "skipped by the plan, nobody asked");
        RunShape.Built b = RunShape.build(m, saved, c);
        assertTrue(b.warmSkipped);
        assertEquals(-1, stage(b.routine, "warm"), "no warm-up stage");
        assertEquals(10, TrainerTab.warmSkipAskMinutes(m, saved, NOW));
        List<String> said = RunShape.lines(b, 14, 10, "Length");
        assertEquals("No warm-up: your length session ended 10 min ago.", said.get(0));
    }

    @Test void thirtyOneMinutesAfterBothWarmUp() {
        Model m = ownerDay();
        filed(m, lengthSaved(m), NOW - 31 * MIN, 55);
        Model.Routine saved = girthSaved(m);
        RunShape.Choice c = TrainerTab.dayChoice(m, saved, NOW, false, false);
        assertFalse(c.skipWarm);
        RunShape.Built b = RunShape.build(m, saved, c);
        assertTrue(stage(b.routine, "warm") >= 0, "the warm-up is there");
        assertFalse(b.warmSkipped);
    }

    @Test void lengthTenMinutesAfterGirthKeepsItsHoldsWithNoWarmUpAndNoCoda() {
        Model m = ownerDay();
        filed(m, girthSaved(m), NOW - 10 * MIN, 52);
        Model.Routine saved = lengthSaved(m);
        RunShape.Choice c = TrainerTab.dayChoice(m, saved, NOW, false, false);
        assertTrue(c.skipWarm);
        assertTrue(c.bothTracks, "R-06: both tracks today");
        RunShape.Built b = RunShape.build(m, saved, c);
        Model.Routine r = b.routine;
        assertTrue(RunShape.hasRelease(r), "the hand release stays");
        assertEquals(-1, stage(r, "warm"));
        assertEquals(repeat(34, 10), pulls(m, r, stage(r, "fatigue")),
                     "fatigue 10 x 60 s at 34 - nothing carried with no warm-up before it");
        assertEquals(repeat(34, 6), pulls(m, r, stage(r, "strain")), "strain 6 x 300 s");
        assertEquals(-1, stage(r, "swap"), "no swap");
        assertTrue(workPulls(m, r).isEmpty(), "and no expansion");
        assertTrue(b.warmSkipped && b.codaDropped);
        assertEquals(49.67, minutes(m, r), 1e-9);
        assertNotNull(r.assess);
        assertFalse(r.assess.on, "no girth tube to check in");
    }

    @Test void theSavedRoutinesAreUntouchedAndNothingDeeperRuns() {
        Model m = ownerDay();
        filed(m, lengthSaved(m), NOW - 10 * MIN, 55);
        Model.Routine saved = girthSaved(m);
        String before = json(saved);
        int sets = m.sets.size();
        RunShape.Built b = RunShape.build(m, saved, TrainerTab.dayChoice(m, saved, NOW, false,
                                                                         false));
        assertEquals(sets, m.sets.size(), "nothing added to the library");
        assertEquals(before, json(saved));
        assertTrue(m.peak(b.routine) <= m.peak(saved));
        // A rejoin rebuilds the same run from the code the run carries.
        int presets = m.plan(b.routine).size();
        long ms = m.durationMs(b.routine);
        RunShape.Built again = RunShape.build(m, saved, RunShape.Choice.fromCode(
            TrainerTab.dayChoice(m, saved, NOW, false, false).code()));
        assertEquals(presets, m.plan(again.routine).size());
        assertEquals(ms, m.durationMs(again.routine));
    }
}
