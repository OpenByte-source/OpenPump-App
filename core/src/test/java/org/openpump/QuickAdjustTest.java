package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE RUN SCREEN'S − / + STRIP (0.10 final): the step of one tap, the range each figure stays
 * inside, the owner-approved words a tap at a limit is told, and what the cells print - the
 * approved mock's lim() and cell(), rule for rule. And the exact-size live tap the strip
 * sends through (LiveEdit#tapBy).
 */
class QuickAdjustTest {

    private String unitWas;

    @BeforeEach void inHg() { unitWas = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_INHG; }
    @AfterEach void back() { Model.Fmt.unit = unitWas; }

    private static String no(int field, int value, int dir, int other, int elapsed) {
        return QuickAdjust.stepRefusal(field, value, dir, other, elapsed, 40);
    }

    @Test
    void theStepsAreTheApprovedOnes() {
        assertArrayEquals(new int[] { 1, 5, 1, 1, 15, 5, 15 }, QuickAdjust.STEP,
            "pull ±1 kPa, hold ±5 s, drop ±1 kPa, drop time ±1 s, rest ±15 s, step ±5 s, "
            + "warm-up ±15 s");
    }

    @Test
    void thePullStaysBetween12And34KpaAndAboveTheDrop() {
        assertNull(no(QuickAdjust.PULL, 20, 1, 5, -1));
        assertEquals("10.0 inHg is the ceiling.", no(QuickAdjust.PULL, 34, 1, 5, -1));
        assertEquals("That is the lowest.", no(QuickAdjust.PULL, 12, -1, 5, -1));
        assertEquals("The pull stays above the drop.", no(QuickAdjust.PULL, 13, -1, 12, -1));
        // A hold-only step's drop is wire filler: it moves with the pull and never stops it.
        assertNull(no(QuickAdjust.PULL, 20, -1, -1, -1));
        // The safety ceiling below 10.0 inHg is the one said.
        assertEquals("8.9 inHg is the ceiling.",
            QuickAdjust.stepRefusal(QuickAdjust.PULL, 30, 1, 5, -1, 30));
        assertNull(QuickAdjust.stepRefusal(QuickAdjust.PULL, 29, 1, 5, -1, 30));
    }

    /* 0.10 (the owner's decision): the strip's 10.0 inHg is a programme cap - past it is
     * asked once for each new highest pull this run; the hard limit is refused as ever. */
    @Test
    void pastTenInHgIsAskedOnceAndTheHardLimitIsRefused() {
        assertEquals(34, QuickAdjust.pullLimit(57, 50, 0), "nothing confirmed: the usual 34");
        assertEquals(40, QuickAdjust.pullLimit(57, 50, 40), "confirmed to 40: 40");
        assertEquals(36, QuickAdjust.pullLimit(57, 36, 40), "never past the hard limit");
        assertEquals(20, QuickAdjust.pullLimit(57, 20, 0), "a new first month: 6 inHg");
        assertEquals(30, QuickAdjust.pullLimit(30, 50, 45), "never past the ceiling");
        assertNull(QuickAdjust.pullWarning(34, 57, 50, 0), "up to 34 is the strip's own");
        assertTrue(QuickAdjust.pullWarning(35, 57, 50, 0) != null, "35 asks");
        assertNull(QuickAdjust.pullWarning(35, 57, 50, 35), "confirmed, not asked again");
        assertTrue(QuickAdjust.pullWarning(36, 57, 50, 35) != null, "a new highest asks");
        assertNull(QuickAdjust.pullWarning(51, 57, 50, 0), "past the hard limit: refused");
        assertNull(QuickAdjust.pullWarning(21, 57, 20, 0), "past the first month's 6: refused");
        assertEquals("That is the lowest.",
            QuickAdjust.stepRefusal(QuickAdjust.PULL, 12, -1, 5, -1, 57, 40));
        assertNull(QuickAdjust.stepRefusal(QuickAdjust.PULL, 38, 1, 5, -1, 57, 40));
        assertEquals("11.8 inHg is the ceiling.",
            QuickAdjust.stepRefusal(QuickAdjust.PULL, 40, 1, 5, -1, 57, 40));
    }

    @Test
    void theDropStaysBetweenAVentAndOneInchUnderThePull() {
        assertEquals("Already a full vent.", no(QuickAdjust.DROP, 0, -1, 20, -1));
        // Past 10 kPa is the person's call since 2026-09-30 (the run screen asks once); 1.0
        // inHg under the pull is the limit, said as one - not "the highest".
        assertNull(no(QuickAdjust.DROP, 10, 1, 20, -1));
        assertEquals(RunEdit.dropGapSaid(), no(QuickAdjust.DROP, 16, 1, 20, -1));
        // Not knowing the pull, the floor stands as it did.
        assertEquals("The drop stops at " + Model.Fmt.p(10) + " — the drop floor.",
            no(QuickAdjust.DROP, 10, 1, -1, -1));
        assertEquals("The drop stays below the pull.", no(QuickAdjust.DROP, 5, 1, 6, -1));
        assertNull(no(QuickAdjust.DROP, 5, 1, 20, -1));
        assertNull(no(QuickAdjust.DROP, 1, -1, 20, -1));
    }

    @Test
    void theTimesStayInTheirRanges() {
        assertEquals("That is the lowest.", no(QuickAdjust.HOLD, 10, -1, 0, -1));
        assertEquals("4:15 is the longest the pump takes.", no(QuickAdjust.HOLD, 255, 1, 0, -1));
        assertNull(no(QuickAdjust.HOLD, 60, 1, 0, -1));
        assertEquals("That is the lowest.", no(QuickAdjust.DROP_TIME, 0, -1, 0, -1));
        assertEquals("That is the highest.", no(QuickAdjust.DROP_TIME, 30, 1, 0, -1));
        assertEquals("That is the lowest.", no(QuickAdjust.STEP_TIME, 15, -1, 0, -1));
        // A STEP IS THE APP'S CLOCK (0.10): 255 s is one hold or one drop on the wire, never a
        // step. The strip's outer bound is the two-step ramp's share of the hour; each ramp is
        // held to its own share (stepTimeRefusal, below).
        assertNull(no(QuickAdjust.STEP_TIME, 255, 1, 0, -1), "4:15 is not a step's limit");
        assertEquals("30:00 is the longest step — a ramp's hour, shared by its 2 steps.",
            no(QuickAdjust.STEP_TIME, 1800, 1, 0, -1));
    }

    @Test
    void aRestIsHalfAMinuteToTenAndNeverCutUnderWhatHasRun() {
        assertEquals("30 s is the shortest.", no(QuickAdjust.REST, 30, -1, 0, 0));
        assertEquals("10:00 is the longest.", no(QuickAdjust.REST, 600, 1, 0, 0));
        // 100 s into a 2:00 rest: 1:45 would leave 5 s, 1:44 would not be allowed.
        assertNull(no(QuickAdjust.REST, 120, -1, 0, 100));
        assertEquals("Use End rest to finish it now.", no(QuickAdjust.REST, 120, -1, 0, 101));
        assertEquals("Use Skip warm-up to finish it now.", no(QuickAdjust.WARM, 120, -1, 0, 101));
        assertEquals("Use Skip step to finish it now.", no(QuickAdjust.STEP_TIME, 40, -1, 0, 31));
        assertNull(no(QuickAdjust.STEP_TIME, 40, -1, 0, 30));
    }

    @Test
    void aWarmUpIsHalfAMinuteToTen() {
        assertEquals("30 s is the shortest.", no(QuickAdjust.WARM, 30, -1, 0, 0));
        assertNull(no(QuickAdjust.WARM, 45, -1, 0, 0));
        assertEquals("10:00 is the longest.", no(QuickAdjust.WARM, 600, 1, 0, 0));
        assertNull(no(QuickAdjust.WARM, 585, 1, 0, 0));
        assertNull(no(QuickAdjust.WARM, 900, -1, 0, 0), "a longer planned warm-up can come down");
    }

    @Test
    void aDropDialledToNothingCanAlwaysBeRaisedAgain() {
        // The owner's report: "reducing DROP to 0 greys out the drop +/- and they can't be
        // increased again". A step BUILT with a drop keeps its drop keys live at 0 s and at a
        // full vent: the lock reads what it was built as, and the strip's own ranges allow +.
        Model.Preset p = new Model.Preset();
        p.up = 20; p.lo = 5; p.uh = 60; p.lh = 5; p.sp = 75; p.durMs = 325_000L;
        RunEdit.asBuilt(p);
        p.lh = 0; p.lo = 0;                        // dialled down live
        assertFalse(RunEdit.dropLocked(p));
        assertNull(no(QuickAdjust.DROP_TIME, 0, 1, 0, -1));
        assertNull(no(QuickAdjust.DROP, 0, 1, 20, -1));
        LiveEdit e = new LiveEdit();
        int[] inForce = tuple(20, 0, 60, 0, 75);
        int[] work = tuple(0, 0, 0, 0, 0);
        assertEquals(LiveEdit.MOVED, e.tapBy(work, LiveEdit.LH, 1, inForce, 40,
            RunEdit.dropLocked(p), 2, 0L));
        assertEquals(1, work[LiveEdit.LH]);
        assertEquals(LiveEdit.MOVED, e.tapBy(work, LiveEdit.LO, 1, inForce, 40,
            RunEdit.dropLocked(p), 2, 10L));
        assertEquals(1, work[LiveEdit.LO]);
    }

    @Test
    void aFigureOutsideTheRangeCanAlwaysBeBroughtBackIn() {
        // A 5 s hold from an old set: − is refused, + is not.
        assertEquals("That is the lowest.", no(QuickAdjust.HOLD, 5, -1, 0, -1));
        assertNull(no(QuickAdjust.HOLD, 5, 1, 0, -1));
        // A drop at 1.0 inHg under its pull: + refused, − allowed.
        assertEquals(RunEdit.dropGapSaid(), no(QuickAdjust.DROP, 26, 1, 30, -1));
        assertNull(no(QuickAdjust.DROP, 26, -1, 30, -1));
        // A pull of 10 kPa: − refused, + allowed.
        assertNull(no(QuickAdjust.PULL, 10, 1, 3, -1));
        // A 12:00 rest: + refused, − allowed.
        assertNull(no(QuickAdjust.REST, 720, -1, 0, 0));
    }

    @Test
    void aRampStartsOnThisStepOnlyAndSaysTheLaterStepsKeepTheirPlan() {
        assertTrue(QuickAdjust.scopeStepOnlyAtStart(true));
        assertFalse(QuickAdjust.scopeStepOnlyAtStart(false));
        assertEquals("Rest of this ramp", QuickAdjust.scopeNames(true)[0]);
        assertEquals("This step only", QuickAdjust.scopeNames(true)[1]);
        assertEquals("Rest of this block", QuickAdjust.scopeNames(false)[0]);
        assertEquals("This set only", QuickAdjust.scopeNames(false)[1]);
        assertTrue(QuickAdjust.rampSaid(true, 3).contains("later steps keep their plan"));
        assertTrue(QuickAdjust.rampSaid(false, 3).contains("rest of this ramp moved"));
        // The ramp's last step: nothing comes after it, nothing to say about it.
        assertNull(QuickAdjust.rampSaid(true, 0));
        assertNull(QuickAdjust.rampSaid(false, 0));
    }

    @Test
    void theStripLeavesThePinnedFooterWhenItWouldSqueezeThePage() {
        // A 700 px screen, a footer of 480 px: 220 px of page is enough for 160.
        assertTrue(QuickAdjust.fitsPinned(700, 480, 160));
        // A short screen at a large font: 560 px, footer 460 px - 100 px of page is not.
        assertFalse(QuickAdjust.fitsPinned(560, 460, 160));
        // Not laid out yet: no decision.
        assertTrue(QuickAdjust.fitsPinned(0, 460, 160));
        assertTrue(QuickAdjust.fitsPinned(560, 0, 160));
    }

    @Test
    void plusThirtyHoldStopsAt415() {
        assertEquals(90, QuickAdjust.plusHold(60));
        assertEquals(255, QuickAdjust.plusHold(240));
        assertEquals(-1, QuickAdjust.plusHold(255));
        assertEquals("Hold is 30 s longer (4:15 at most).", QuickAdjust.plusHoldSaid(60, 90));
        assertEquals("Hold is 4:15, the longest the pump takes.", QuickAdjust.plusHoldSaid(240, 255));
    }

    @Test
    void plusThirtyHoldIsNeverRefusedInSilence() {
        assertNull(QuickAdjust.plusHoldRefusal(60, 90_000L));
        assertEquals("4:15 is the longest the pump takes.", QuickAdjust.plusHoldRefusal(255, 90_000L));
        assertEquals("This block ends in a moment — there is no hold left to lengthen.",
            QuickAdjust.plusHoldRefusal(60, 1_500L));
        // The hold under way carries on (HoldCarryOn); in the drop, the next hold is the longer.
        assertEquals("The drop is under way: the next hold is 30 s longer.",
            QuickAdjust.plusHoldSaid(60, 90, true));
        assertEquals("This hold runs 30 s longer, from where it is.",
            QuickAdjust.plusHoldSaid(60, 90, false));
    }

    @Test
    void theCellsPrintWhatTheMockPrints() {
        assertEquals("−5.9", QuickAdjust.value(QuickAdjust.PULL, 20));
        assertEquals("vent", QuickAdjust.value(QuickAdjust.DROP, 0));
        assertEquals("−1.5", QuickAdjust.value(QuickAdjust.DROP, 5));
        assertEquals("1:00", QuickAdjust.value(QuickAdjust.HOLD, 60));
        assertEquals("5 s", QuickAdjust.value(QuickAdjust.DROP_TIME, 5));
        assertEquals("1:40", QuickAdjust.value(QuickAdjust.REST, 100));
        assertEquals("Pull to · target", QuickAdjust.LABEL[QuickAdjust.PULL]);
        assertEquals("Hold time", QuickAdjust.LABEL[QuickAdjust.HOLD]);
        assertEquals("THIS RAMP · −3.0 → −5.9 inHg", QuickAdjust.rampHead(10, 20));
        assertEquals("THIS SET · CHANGES APPLY NOW", QuickAdjust.HEAD_WORK);
        assertEquals("THIS REST · CUFF VENTED", QuickAdjust.HEAD_REST);
        assertEquals("THIS RAMP STEP · CHANGES APPLY NOW", QuickAdjust.HEAD_RAMP_STEP);
        assertEquals("WARM-UP · CHANGES APPLY NOW", QuickAdjust.HEAD_WARM);
    }

    /* ---------------------------------------------------- the exact-size live tap */

    private static int[] tuple(int up, int lo, int uh, int lh, int sp) {
        int[] t = new int[LiveEdit.FIELDS];
        t[LiveEdit.UP] = up; t[LiveEdit.LO] = lo; t[LiveEdit.UH] = uh;
        t[LiveEdit.LH] = lh; t[LiveEdit.SP] = sp;
        return t;
    }

    @Test
    void anExactTapMovesByItsOwnSizeAndStacksOnThePendingTarget() {
        LiveEdit e = new LiveEdit();
        int[] inForce = tuple(20, 5, 60, 5, 75);
        int[] work = tuple(0, 0, 0, 0, 0);
        assertEquals(LiveEdit.MOVED, e.tapBy(work, LiveEdit.UP, 1, inForce, 40, false, 3, 0L));
        assertEquals(21, work[LiveEdit.UP], "seeded from what is in force, then +1 kPa");
        assertEquals(LiveEdit.MOVED, e.tapBy(work, LiveEdit.UP, 1, inForce, 40, false, 3, 10L));
        assertEquals(22, work[LiveEdit.UP], "the second tap builds on the pending target");
        assertEquals(LiveEdit.MOVED, e.tapBy(work, LiveEdit.UH, 30, inForce, 40, false, 3, 20L));
        assertEquals(90, work[LiveEdit.UH]);
        assertEquals(LiveEdit.MOVED, e.tapBy(work, LiveEdit.LH, -1, inForce, 40, false, 3, 30L));
        assertEquals(4, work[LiveEdit.LH]);
        assertEquals(22, e.shown(LiveEdit.UP, work, inForce, 3));
    }

    @Test
    void anExactTapStillPassesTheWiresLimits() {
        LiveEdit e = new LiveEdit();
        int[] inForce = tuple(30, 5, 250, 5, 75);
        int[] work = tuple(0, 0, 0, 0, 0);
        assertEquals(LiveEdit.AT_LIMIT, e.tapBy(work, LiveEdit.UP, 1, inForce, 30, false, 1, 0L),
            "never past the safety ceiling");
        assertEquals(LiveEdit.MOVED, e.tapBy(work, LiveEdit.UH, 30, inForce, 30, false, 1, 0L));
        assertEquals(255, work[LiveEdit.UH], "clamped to the wire's 255 s");
        assertEquals(LiveEdit.LOCKED, e.tapBy(work, LiveEdit.LO, 1, inForce, 30, true, 1, 0L));
        assertEquals(LiveEdit.LOCKED, e.tapBy(work, LiveEdit.LH, 1, inForce, 30, true, 1, 0L));
    }

    @Test
    void theOldTapIsTheExactTapWithTheDisplayStep() {
        LiveEdit a = new LiveEdit(), b = new LiveEdit();
        int[] inForce = tuple(20, 5, 60, 5, 75);
        int[] wa = tuple(0, 0, 0, 0, 0), wb = tuple(0, 0, 0, 0, 0);
        a.tap(wa, LiveEdit.UP, 1, inForce, 40, false, 2, 0L);
        b.tapBy(wb, LiveEdit.UP, Model.Fmt.nudgeKpa(20, 1) - 20, inForce, 40, false, 2, 0L);
        assertArrayEquals(wa, wb);
    }

    @Test
    void chartFirstOnRampsIsAboutRampStepsAndTheWarmUpOnly() {
        int w = Model.RUN_STRIP_RAMP_FIRST;
        assertTrue(QuickAdjust.chartFirst(w, QuickAdjust.MODE_RAMP));
        assertTrue(QuickAdjust.chartFirst(w, QuickAdjust.MODE_WARM));
        assertFalse(QuickAdjust.chartFirst(w, QuickAdjust.MODE_WORK));
        assertFalse(QuickAdjust.chartFirst(w, QuickAdjust.MODE_REST));
        assertFalse(QuickAdjust.chartFirst(w, QuickAdjust.MODE_NONE));
        // The other two answers never put the chart first, whatever is playing.
        for (int mode = QuickAdjust.MODE_NONE; mode <= QuickAdjust.MODE_WARM; mode++) {
            assertFalse(QuickAdjust.chartFirst(Model.RUN_STRIP_PINNED, mode));
            assertFalse(QuickAdjust.chartFirst(Model.RUN_STRIP_UNDER_CHART, mode));
        }
    }

    @Test
    void theStripSitsInThePageExactlyWhereTheOldTwoAnswersPutIt() {
        for (int mode = QuickAdjust.MODE_NONE; mode <= QuickAdjust.MODE_WARM; mode++) {
            assertFalse(QuickAdjust.stripInPage(Model.RUN_STRIP_PINNED, mode, false),
                "Pinned: the footer");
            assertTrue(QuickAdjust.stripInPage(Model.RUN_STRIP_PINNED, mode, true),
                "Pinned, squeezed off a short screen: under the chart");
            assertTrue(QuickAdjust.stripInPage(Model.RUN_STRIP_UNDER_CHART, mode, false),
                "Under the chart: always the page");
        }
    }

    @Test
    void chartFirstKeepsTheStripPinnedOnEveryOtherStep() {
        int w = Model.RUN_STRIP_RAMP_FIRST;
        assertTrue(QuickAdjust.stripInPage(w, QuickAdjust.MODE_RAMP, false));
        assertTrue(QuickAdjust.stripInPage(w, QuickAdjust.MODE_WARM, false));
        assertFalse(QuickAdjust.stripInPage(w, QuickAdjust.MODE_WORK, false));
        assertFalse(QuickAdjust.stripInPage(w, QuickAdjust.MODE_REST, false));
        assertFalse(QuickAdjust.stripInPage(w, QuickAdjust.MODE_NONE, false));
        assertTrue(QuickAdjust.stripInPage(w, QuickAdjust.MODE_WORK, true),
            "a screen too short for the pinned footer still moves it into the page");
    }

    @Test
    void theStoredChoiceKeepsItsOldValuesAndNeverLoadsAsAnUnknownOne() {
        assertEquals(Model.RUN_STRIP_PINNED, Model.clampRunStripWhere(0));
        assertEquals(Model.RUN_STRIP_UNDER_CHART, Model.clampRunStripWhere(1));
        assertEquals(Model.RUN_STRIP_RAMP_FIRST, Model.clampRunStripWhere(2));
        assertEquals(Model.RUN_STRIP_PINNED, Model.clampRunStripWhere(3));
        assertEquals(Model.RUN_STRIP_PINNED, Model.clampRunStripWhere(-1));
        assertEquals(3, QuickAdjust.WHERE_NAMES.length);
        assertEquals(3, QuickAdjust.WHERE_SAYS.length);
        assertEquals("Chart first on ramps", QuickAdjust.WHERE_NAMES[Model.RUN_STRIP_RAMP_FIRST]);
    }
}
