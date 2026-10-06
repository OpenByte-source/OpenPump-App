package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * THE WHOLE-ROUTINE OFFSET: WHO TAKES IT, AND A COUNTER THAT TELLS THE TRUTH.
 *
 * From a real pump (owner report): on a Length routine the offset sheet's − and + looked
 * dead. Every tap was refused and the refusal went to a snackbar under the sheet. The owner
 * keeps the refusal, so it is now one check, RoutineOffset#refusal, which the sheet asks
 * before it draws its buttons and the Activity asks before it moves anything.
 *
 * The counter moved on taps that changed nothing, and that was a way past the trainer's cap:
 * a counter walked down over steps that could not go lower left headroom a later + spent on
 * real steps. Separately, a ramp step-count change rebuilds the ramp's tail between the
 * running step (not offset) and the ramp's end (offset), so the new steps carry only part of
 * the counter, and a later + measured against the counter took them past the cap. Both are
 * closed: the counter moves only when a pull did, and the cap is held per step
 * (Model.Preset#offsetKpa).
 */
class RoutineOffsetTest {

    private static final int CEIL = 40;
    private static final int TRAINER_CAP = RunEdit.routineOffsetCapKpa(true, false);

    // ---- who takes the offset -------------------------------------------------------------

    /* 0.10 (the owner's decision: programme caps are warned once, your call). A Length
     * routine used to be refused outright here - that refusal is now a one-time warning. */
    @Test
    void aLengthRoutineIsWarnedOnceNotRefusedAndTheWarningSaysWhy() {
        assertNull(RoutineOffset.refusal(Plan.TRACK_LENGTH), "a Length routine is not refused");
        String why = RoutineOffset.warning(Plan.TRACK_LENGTH, 0, 1, 0, false);
        assertNotNull(why, "its first + asks");
        assertTrue(why.contains("load"), "it names what usually sets a Length pull: " + why);
        assertTrue(why.contains("Trainer"), "it says where the load is raised: " + why);
        assertTrue(why.contains("expansion"), "the expansion moves with it: " + why);
        assertTrue(why.contains("15 lb"), "the load's hard limit is said: " + why);
        assertNull(RoutineOffset.warning(Plan.TRACK_LENGTH, 0, 1, 1, false),
            "confirmed once, not asked again");
        assertNotNull(RoutineOffset.warning(Plan.TRACK_LENGTH, 1, 1, 1, false),
            "a new highest offset asks again");
        assertNull(RoutineOffset.warning(Plan.TRACK_LENGTH, 0, -1, 0, false), "down is free");
        assertNull(RoutineOffset.warning(Plan.TRACK_LENGTH, 0, 1, 0, true),
            "a reduced day asks nothing - it takes no offset up at all");
        assertEquals(0, RunEdit.routineOffsetCapKpa(Plan.TRACK_LENGTH, true, 9),
            "a reduced day's nothing stays hard whatever was confirmed");
        assertEquals(0, RunEdit.routineOffsetCapKpa(Plan.TRACK_LENGTH, false, 0));
        assertEquals(3, RunEdit.routineOffsetCapKpa(Plan.TRACK_LENGTH, false, 3));
    }

    @Test
    void theTrainersStepIsWarnedOncePastItNeverRefused() {
        int step = Model.Set.STEP_UP_KPA;
        assertNull(RoutineOffset.warning(Plan.TRACK_GIRTH_INTERVAL, 0, step, 0, false),
            "within the plan's own step nothing is asked");
        String ask = RoutineOffset.warning(Plan.TRACK_GIRTH_INTERVAL, step, 1, 0, false);
        assertNotNull(ask, "past it, asked");
        assertTrue(ask.contains("ceiling"), "the hard limits are said: " + ask);
        assertNull(RoutineOffset.warning(Plan.TRACK_GIRTH_INTERVAL, step, 1, step + 1, false));
        assertEquals(step, RunEdit.routineOffsetCapKpa(Plan.TRACK_GIRTH_INTERVAL, false, 0));
        assertEquals(step + 4, RunEdit.routineOffsetCapKpa(Plan.TRACK_GIRTH_INTERVAL, false,
                                                           step + 4));
        assertEquals(RunEdit.routineOffsetCapKpa(true, false),
            RunEdit.routineOffsetCapKpa(Plan.TRACK_GIRTH_INTERVAL, false, 0),
            "nothing confirmed: the cap the trainer always had");
        assertNull(RoutineOffset.warning(Model.TRAINER_TRACK_NONE, 50, 1, 0, false),
            "a Library routine answers to the ceiling alone");
    }

    @Test
    void eachStepStopsAtItsOwnHardLimit() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        plan.add(step(20, 14, 0, "a", 0, 0));
        plan.add(step(30, 0, 0, "pull", 0, 1));
        plan.add(step(20, 3, 0, "coda", 0, 2));
        boolean[] work = { false, true, true };
        int[] limits = { 40, 31, 21 };
        RoutineOffset.Result r = RoutineOffset.apply(plan, 1, work, 3, 0, 10, CEIL, limits);
        assertEquals(31, plan.get(1).up, "the pull stops at its own limit");
        assertEquals(21, plan.get(2).up, "the expansion at its own");
        assertEquals(2, r.changed);
    }

    @Test
    void girthFeederAndLibraryRoutinesAreNotRefused() {
        assertNull(RoutineOffset.refusal(Plan.TRACK_GIRTH_INTERVAL), "girth, interval");
        assertNull(RoutineOffset.refusal(Plan.TRACK_GIRTH_TRADITIONAL), "girth, traditional");
        assertNull(RoutineOffset.refusal(Plan.TRACK_FEEDER), "the feeder");
        assertNull(RoutineOffset.refusal(Model.TRAINER_TRACK_NONE), "a Library routine");
    }

    @Test
    void theRoutineBeingRunIsAskedTheSameQuestion() {
        Model.Routine length = new Model.Routine();
        length.trainerTrack = Plan.TRACK_LENGTH;
        assertNull(RoutineOffset.refusal(length), "0.10: warned once, not refused");
        Model.Routine girth = new Model.Routine();
        girth.trainerTrack = Plan.TRACK_GIRTH_INTERVAL;
        assertNull(RoutineOffset.refusal(girth));
        assertNull(RoutineOffset.refusal(new Model.Routine()), "a Library routine names no track");
        assertNull(RoutineOffset.refusal((Model.Routine) null), "no routine, nothing to refuse");
    }

    // ---- the counter ------------------------------------------------------------------------

    @Test
    void withNoWorkStepsLeftATapDoesNotMoveTheCounter() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        plan.add(step(20, 14, 0, "a", 0, 0));
        plan.add(step(8, 4, 1, "cool", 0, 0));
        plan.add(step(6, 2, 1, "cool", 0, 1));
        boolean[] work = { true, false, false };   // only the running step was work

        RoutineOffset.Result up = RoutineOffset.apply(plan, 1, work, 1, 0, Integer.MAX_VALUE, CEIL);
        assertEquals(0, up.changed);
        assertEquals(0, up.applied);
        assertEquals(0, up.counter, "a + over nothing does not move the counter");
        assertEquals(RoutineOffset.NO_WORK_LEFT, up.note, "and the sheet is told why");

        RoutineOffset.Result down = RoutineOffset.apply(plan, 1, work, -1, 0, Integer.MAX_VALUE, CEIL);
        assertEquals(0, down.counter, "nor does a −");
        assertEquals(RoutineOffset.NO_WORK_LEFT, down.note);

        // The last step playing: nothing after it at all.
        RoutineOffset.Result end = RoutineOffset.apply(plan, 3, work, 1, 0, Integer.MAX_VALUE, CEIL);
        assertEquals(0, end.counter);
        assertEquals(RoutineOffset.NO_WORK_LEFT, end.note);

        assertEquals(8, plan.get(1).up, "nothing that is not work was touched");
        assertEquals(6, plan.get(2).up);
    }

    @Test
    void aTapThatMovesAPullMovesTheCounterAndTheDropStaysUnderIt() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        plan.add(step(20, 14, 0, "a", 0, 0));
        plan.add(step(20, 19, 0, "a", 0, 1));
        plan.add(step(8, 4, 1, "cool", 0, 0));
        boolean[] work = { true, true, false };

        RoutineOffset.Result r = RoutineOffset.apply(plan, 1, work, -2, 0, Integer.MAX_VALUE, CEIL);
        assertEquals(1, r.changed);
        assertEquals(-2, r.counter);
        assertNull(r.note, "an ordinary tap has nothing to say");
        assertEquals(18, plan.get(1).up);
        assertEquals(14, plan.get(1).lo, "the drop re-clamps under the moved pull - a drop above "
            + "the floor 1.0 inHg under it (RunEdit#dropKept, review I5)");
        assertEquals(-2, plan.get(1).offsetKpa, "the step records what the offset did to it");
        assertEquals(20, plan.get(0).up, "the running step is not moved");
        assertEquals(8, plan.get(2).up, "the cool-down is not moved");
    }

    @Test
    void aPullThatCannotMoveLeavesTheCounterWhereItIs() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        plan.add(step(10, 6, 0, "a", 0, 0));
        plan.add(step(0, 0, 0, "a", 0, 1));
        plan.add(step(CEIL, 30, 1, "b", 0, 0));
        boolean[] low = { false, true, false };
        boolean[] high = { false, false, true };

        RoutineOffset.Result down = RoutineOffset.apply(plan, 1, low, -1, 0, Integer.MAX_VALUE, CEIL);
        assertEquals(0, down.counter, "a pull at zero goes no lower, and the counter stays");
        assertEquals(RoutineOffset.AT_BOTTOM, down.note);
        assertEquals(0, plan.get(1).up);

        RoutineOffset.Result up = RoutineOffset.apply(plan, 1, high, 1, 0, Integer.MAX_VALUE, CEIL);
        assertEquals(0, up.counter, "a pull at the ceiling goes no higher, and the counter stays");
        assertEquals(RoutineOffset.AT_TOP, up.note);
        assertEquals(CEIL, plan.get(2).up);
    }

    // ---- the cap ----------------------------------------------------------------------------

    @Test
    void theTrainerCapTrimsATapAndSaysSo() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        plan.add(step(20, 14, 0, "a", 0, 0));
        plan.add(step(20, 14, 0, "a", 0, 1));
        boolean[] work = { true, true };

        RoutineOffset.Result r = RoutineOffset.apply(plan, 1, work, 3, 0, TRAINER_CAP, CEIL);
        assertEquals(TRAINER_CAP, r.applied, "trimmed to the plan's own step");
        assertEquals(TRAINER_CAP, r.counter);
        assertEquals(20 + TRAINER_CAP, plan.get(1).up);
        assertEquals(RoutineOffset.capped(TRAINER_CAP), r.note, "and the sheet says it was capped");

        RoutineOffset.Result again = RoutineOffset.apply(plan, 1, work, 1, r.counter, TRAINER_CAP, CEIL);
        assertEquals(0, again.changed);
        assertEquals(TRAINER_CAP, again.counter);
        assertEquals(RoutineOffset.capped(TRAINER_CAP), again.note);
        assertEquals(20 + TRAINER_CAP, plan.get(1).up);
    }

    @Test
    void aReducedDayTakesNoUpwardOffsetButMayGoDown() {
        int cap = RunEdit.routineOffsetCapKpa(true, true);
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        plan.add(step(20, 14, 0, "a", 0, 0));
        plan.add(step(20, 14, 0, "a", 0, 1));
        boolean[] work = { true, true };

        RoutineOffset.Result up = RoutineOffset.apply(plan, 1, work, 1, 0, cap, CEIL);
        assertEquals(0, up.counter);
        assertEquals(RoutineOffset.REDUCED_DAY, up.note);
        assertEquals(20, plan.get(1).up);

        RoutineOffset.Result down = RoutineOffset.apply(plan, 1, work, -1, 0, cap, CEIL);
        assertEquals(-1, down.counter, "downward is always free");
        assertEquals(19, plan.get(1).up);
    }

    /** The counter walked down over steps that could not go lower used to be headroom. */
    @Test
    void aCounterThatCouldNotMoveGivesNoHeadroomUnderTheCap() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        plan.add(step(10, 6, 0, "a", 0, 0));
        plan.add(step(1, 0, 0, "a", 0, 1));
        plan.add(step(1, 0, 0, "a", 0, 2));
        boolean[] work = { true, true, true };
        List<Model.Preset> rx = copy(plan);

        int counter = 0;
        for (int i = 0; i < 5; i++)
            counter = RoutineOffset.apply(plan, 1, work, -1, counter, TRAINER_CAP, CEIL).counter;
        assertEquals(-1, counter, "only the first − moved anything");
        for (int i = 0; i < 10; i++)
            counter = RoutineOffset.apply(plan, 1, work, 1, counter, TRAINER_CAP, CEIL).counter;
        assertEquals(TRAINER_CAP, counter);
        assertWithinCap(plan, rx, 1, TRAINER_CAP);
        assertEquals(1 + TRAINER_CAP, plan.get(1).up, "the cap is reached, and not passed");
    }

    /**
     * THE CAP HOLDS ACROSS A STEP-COUNT CHANGE. A trainer run on a ramp: −5 while its second
     * step plays, then the ramp re-divided into six steps, then + until the counter tops out.
     * Measured against the counter alone, the rebuilt steps (which carry only part of the −5)
     * would have run up to 6 kPa over what the plan prescribes at that point of the ramp.
     */
    @Test
    void theCapHoldsAcrossAStepCountChange() {
        List<Model.Preset> plan = ramp();
        List<Model.Preset> rx = copy(plan);        // the prescription, never offset
        Model.Preset cur = plan.get(0);

        int counter = 0;
        for (int i = 0; i < 5; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), -1, counter, TRAINER_CAP, CEIL).counter;
        assertEquals(-5, counter);

        resize(plan, cur, 6);
        resize(rx, rx.get(0), 6);                  // the same recount of the prescription
        assertEquals(rx.size(), plan.size());

        for (int i = 0; i < 12; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), 1, counter, TRAINER_CAP, CEIL).counter;
        assertEquals(TRAINER_CAP, counter);
        assertWithinCap(plan, rx, 1, TRAINER_CAP);
        assertEquals(rx.get(plan.size() - 1).up + TRAINER_CAP, plan.get(plan.size() - 1).up,
            "the later work set still reaches the cap - the rule trims, it does not freeze");
    }

    /** The same the other way: + to the cap, recount, then down and back up again. */
    @Test
    void theCapHoldsAcrossAStepCountChangeFromAboveToo() {
        List<Model.Preset> plan = ramp();
        List<Model.Preset> rx = copy(plan);
        Model.Preset cur = plan.get(0);

        int counter = 0;
        for (int i = 0; i < 3; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), 1, counter, TRAINER_CAP, CEIL).counter;
        assertEquals(TRAINER_CAP, counter);
        resize(plan, cur, 5);
        resize(rx, rx.get(0), 5);
        assertWithinCap(plan, rx, 1, TRAINER_CAP);
        for (int i = 0; i < 3; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), -1, counter, TRAINER_CAP, CEIL).counter;
        for (int i = 0; i < 10; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), 1, counter, TRAINER_CAP, CEIL).counter;
        assertEquals(TRAINER_CAP, counter);
        assertWithinCap(plan, rx, 1, TRAINER_CAP);
    }

    /**
     * THE RECOUNT CARRIES THE OFFSET WHEN THE RUNNING STEP HAS ONE (safety review of 1af2c85,
     * finding 3: nothing tested the offsetKpa resizeRemaining gives the steps it rebuilds).
     * +2 while the first step plays, so the second carries it; the second starts; −4; the
     * ramp re-divided into eight; then + six times. The rebuilt steps run from the running
     * step (+2) to the end (−2), and must not be given a fresh +2 on top of what they carry.
     */
    @Test
    void theCapHoldsWhenTheRecountComesAfterTheNextStepStarts() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        int[] ups = { 10, 13, 17, 20, 23, 26 };
        for (int i = 0; i < ups.length; i++) plan.add(step(ups[i], ups[i] - 4, 0, "r", 0, i));
        List<Model.Preset> rx = copy(plan);

        int counter = 0;
        for (int i = 0; i < 2; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), 1, counter, TRAINER_CAP, CEIL).counter;
        assertEquals(2, plan.get(1).offsetKpa, "the second step carries the +2");
        // The second step starts playing: the offset now reaches from index 2.
        for (int i = 0; i < 4; i++)
            counter = RoutineOffset.apply(plan, 2, allWork(plan), -1, counter, TRAINER_CAP, CEIL).counter;
        assertEquals(-2, counter);
        resizeAt(plan, 1, 8);
        resizeAt(rx, 1, 8);                        // the prescription, recounted from its own step
        assertEquals(rx.size(), plan.size());
        for (int i = 0; i < 6; i++)
            counter = RoutineOffset.apply(plan, 2, allWork(plan), 1, counter, TRAINER_CAP, CEIL).counter;
        assertEquals(TRAINER_CAP, counter);
        assertWithinCap(plan, rx, 2, TRAINER_CAP);
    }

    /**
     * A RESHAPE WRITES THE PULL OUTRIGHT, SO THE STEP KEEPS NO HEADROOM FROM AN EARLIER −
     * (safety review of 1af2c85, the Medium): a trainer ramp 10/13/17/20 on its first step,
     * − ten times (3/7/10, offsetKpa −10), the end reshaped one step lower (9/9/8), then +
     * twelve times. The −10 used to count as headroom: 21/21/20 against 13/17/20, 8 over a
     * 2 kPa cap.
     */
    @Test
    void aReshapeLeavesNoHeadroomFromAnEarlierMinus() {
        List<Model.Preset> plan = ramp4();
        List<Model.Preset> rx = copy(plan);

        int counter = 0;
        for (int i = 0; i < 10; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), -1, counter, TRAINER_CAP, CEIL).counter;
        assertEquals(3, plan.get(1).up);
        assertEquals(10, plan.get(3).up);
        assertEquals(-10, plan.get(3).offsetKpa);

        Model.Preset cur = plan.get(0), end = plan.get(3);
        int endUp = end.up - 2;
        RunEdit.reshapeRemaining(plan, 0, cur.up, cur.lo, cur.uh, cur.lh, cur.sp,
            endUp, RunEdit.clampLower(end.lo, endUp), end.uh, end.lh, end.sp, CEIL);
        assertEquals(9, plan.get(1).up);
        assertEquals(9, plan.get(2).up);
        assertEquals(8, plan.get(3).up);
        for (int k = 1; k < plan.size(); k++)
            assertTrue(plan.get(k).offsetKpa >= 0, "step " + k + " keeps no negative headroom");

        for (int i = 0; i < 12; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), 1, counter, TRAINER_CAP, CEIL).counter;
        assertWithinCap(plan, rx, 1, TRAINER_CAP);
        assertEquals(8 + TRAINER_CAP, plan.get(3).up, "+ still goes the cap above the reshaped end");
    }

    /** The upcoming-step editor: − four times, one step typed back to its prescription, then
     *  + six times ended 6 kPa over the plan (safety review of 1af2c85, Low 1). The editor
     *  writes through RunEdit#writePull, as the Activity does. */
    @Test
    void aStepTypedBackToItsPrescriptionKeepsNoHeadroom() {
        List<Model.Preset> plan = ramp4();
        List<Model.Preset> rx = copy(plan);
        int counter = 0;
        for (int i = 0; i < 4; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), -1, counter, TRAINER_CAP, CEIL).counter;
        RunEdit.writePull(plan.get(2), rx.get(2).up);
        assertEquals(0, plan.get(2).offsetKpa);
        for (int i = 0; i < 6; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), 1, counter, TRAINER_CAP, CEIL).counter;
        assertWithinCap(plan, rx, 1, TRAINER_CAP);
    }

    /** The ramp's "This set" shift writes the pull from its own base: a − offset shifted back
     *  up keeps no headroom from the − either. */
    @Test
    void aShiftBackUpKeepsNoHeadroom() {
        List<Model.Preset> plan = ramp4();
        List<Model.Preset> rx = copy(plan);
        int counter = 0;
        for (int i = 0; i < 4; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), -1, counter, TRAINER_CAP, CEIL).counter;
        int[] before = { 10, 6, 20, 60, 5 }, after = before.clone();
        after[LiveEdit.UP] += 4;
        new SetShift().shift(plan, 0, 3, before, after, CEIL);
        assertEquals(rx.get(3).up, plan.get(3).up, "the shift put the ramp back");
        for (int i = 0; i < 6; i++)
            counter = RoutineOffset.apply(plan, 1, allWork(plan), 1, counter, TRAINER_CAP, CEIL).counter;
        assertWithinCap(plan, rx, 1, TRAINER_CAP);
    }

    @Test
    void aPullWrittenWhereItWasKeepsItsRecord() {
        Model.Preset p = step(16, 12, 0, "a", 0, 0);
        p.offsetKpa = -4;
        RunEdit.writePull(p, 16);
        assertEquals(-4, p.offsetKpa, "nothing moved, so the headroom is still real");
        p.offsetKpa = 2;
        RunEdit.writePull(p, 10);
        assertEquals(2, p.offsetKpa, "a + already spent stays spent");
        RunEdit.writePull(p, 18);
        assertEquals(18, p.up);
        p.offsetKpa = -4;
        RunEdit.writePull(p, 14);
        assertEquals(0, p.offsetKpa, "written outright, the − is gone");
        assertEquals(14, p.up);
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** The review's ramp: 10/13/17/20, its first step playing, nothing after it. */
    private static List<Model.Preset> ramp4() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        int[] ups = { 10, 13, 17, 20 };
        for (int i = 0; i < ups.length; i++) plan.add(step(ups[i], ups[i] - 4, 0, "r", 0, i));
        return plan;
    }

    private static void resizeAt(List<Model.Preset> plan, int idx, int steps) {
        Model.Preset cur = plan.get(idx);
        int d = RunEdit.resizeRemaining(plan, idx, RunEdit.clampUpper(cur.up, CEIL),
            RunEdit.clampLower(cur.lo, cur.up), cur.uh, cur.lh, cur.sp, steps, CEIL);
        assertTrue(d != 0, "the recount happened");
    }

    /** A work ramp playing its first step (10) with three to come (13, 17, 20), then a
     *  fixed work set at 20 in the next stage. */
    private static List<Model.Preset> ramp() {
        List<Model.Preset> plan = new ArrayList<Model.Preset>();
        plan.add(step(10, 6, 0, "r", 0, 0));
        plan.add(step(13, 9, 0, "r", 0, 1));
        plan.add(step(17, 13, 0, "r", 0, 2));
        plan.add(step(20, 16, 0, "r", 0, 3));
        plan.add(step(20, 16, 1, "f", 0, 0));
        return plan;
    }

    private static void resize(List<Model.Preset> plan, Model.Preset cur, int steps) {
        int d = RunEdit.resizeRemaining(plan, 0, RunEdit.clampUpper(cur.up, CEIL),
            RunEdit.clampLower(cur.lo, cur.up), cur.uh, cur.lh, cur.sp, steps, CEIL);
        assertTrue(d != 0, "the recount happened");
    }

    private static boolean[] allWork(List<Model.Preset> plan) {
        boolean[] w = new boolean[plan.size()];
        for (int i = 0; i < w.length; i++) w[i] = true;
        return w;
    }

    private static void assertWithinCap(List<Model.Preset> plan, List<Model.Preset> rx,
                                        int from, int cap) {
        for (int k = from; k < plan.size(); k++)
            assertTrue(plan.get(k).up - rx.get(k).up <= cap, "step " + k + " runs at "
                + plan.get(k).up + " against a prescription of " + rx.get(k).up
                + " - more than " + cap + " over");
    }

    private static List<Model.Preset> copy(List<Model.Preset> plan) {
        List<Model.Preset> out = new ArrayList<Model.Preset>();
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            Model.Preset q = step(p.up, p.lo, p.stageIdx, p.setId, p.pos, p.ordinal);
            q.durMs = p.durMs;
            out.add(q);
        }
        return out;
    }

    private static Model.Preset step(int up, int lo, int stage, String setId, int pos, int ord) {
        Model.Preset p = new Model.Preset();
        p.up = up; p.lo = lo; p.uh = 20; p.lh = 5; p.sp = 60;
        p.durMs = 60000L;
        p.stageIdx = stage; p.setId = setId; p.pos = pos; p.ordinal = ord;
        p.label = setId;
        return p;
    }
}
