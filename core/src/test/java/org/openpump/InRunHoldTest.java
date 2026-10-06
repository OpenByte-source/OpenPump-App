package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * THE HOLD BUTTON DURING A ROUTINE HAS THE SAME LIMIT AS EVERY OTHER HOLD (the owner's
 * decision). It used to have no limit of its own: only STOP, the link watchdog and the two-hour
 * stop ended it. It now vents after "Vent any hold after at most" (Model#holdMaxSec, 60-900 s,
 * 5:00 by default), counted on the hold's monotonic clock, and the run ends as a STOP would.
 */
class InRunHoldTest {

    @Test
    void theLimitIsTheSettingClampedToItsOwnRange() {
        assertEquals(300, InRunHold.limitSec(300));
        assertEquals(60, InRunHold.limitSec(60));
        assertEquals(900, InRunHold.limitSec(900));
        assertEquals(Model.END_HOLD_MIN_SEC, InRunHold.limitSec(5),
            "a hand-edited file cannot make the hold end before the setting's own floor");
        assertEquals(Model.END_HOLD_MAX_SEC, InRunHold.limitSec(100000),
            "nor let it run past the setting's ceiling");
    }

    @Test
    void theDeadlineIsCountedFromTheHoldOnTheHoldsClock() {
        assertEquals(1000L + 300000L, InRunHold.endsAt(1000L, 300));
        assertEquals(1000L + 60000L, InRunHold.endsAt(1000L, 1));
    }

    @Test
    void theTimeLeftCountsDownAndStopsAtZero() {
        long ends = InRunHold.endsAt(0L, 300);
        assertEquals(300000L, InRunHold.leftMs(ends, 0L));
        assertEquals(28000L, InRunHold.leftMs(ends, 272000L));
        assertEquals(0L, InRunHold.leftMs(ends, 400000L));
        assertEquals(0L, InRunHold.leftMs(0L, 5L), "no hold, no time left");
    }

    @Test
    void theLimitActsOnlyOnALiveHoldOfALiveRunOverALiveLink() {
        long ends = InRunHold.endsAt(0L, 60);
        assertTrue(InRunHold.limitReached(true, true, false, ends, 60000L), "at the limit");
        assertTrue(InRunHold.limitReached(true, true, false, ends, 61000L), "past it");
        assertFalse(InRunHold.limitReached(true, true, false, ends, 59999L), "just before it");
        assertFalse(InRunHold.limitReached(true, false, false, ends, 61000L),
            "released (Resume) - the limit went with the hold");
        assertFalse(InRunHold.limitReached(false, true, false, ends, 61000L), "no run");
        assertFalse(InRunHold.limitReached(true, true, true, ends, 61000L),
            "over a lost link the link-loss auto-stop owns the pump and has vented it; "
            + "finishing the run from here would replace its watch (as the two-hour stop)");
        assertFalse(InRunHold.limitReached(true, true, false, 0L, 61000L), "no limit armed");
    }

    @Test
    void theCountdownReadsLikeTheMeasurementHolds() {
        assertEquals("vents in 4:32", InRunHold.ventsIn(271200L));
        assertEquals("vents in 0:01", InRunHold.ventsIn(1L), "rounded up, never 0:00 early");
        assertEquals("vents in 0:00", InRunHold.ventsIn(0L));
    }

    @Test
    void theLimitsSentenceSaysWhatHappenedAndClaimsNoMore() {
        String s = InRunHold.limitSentence(300);
        assertEquals("The hold reached its limit (5:00), so the run stopped and the pump is "
            + "venting.", s);
        String l = s.toLowerCase(Locale.US);
        assertFalse(l.contains("vented") || l.contains("safe"),
            "the vent is commanded, not yet seen: the summary says when it is");
        assertTrue(InRunHold.limitSentence(60).contains("(1:00)"));
    }

    @Test
    void theRunsNotificationSaysWhenTheHoldVents() {
        assertEquals("preset 2 of 6 · holding, vents in 4:32 · 18.0 kPa",
            Session.runNotificationText(false, "Grow", 2, 6, "1:30", "18.0 kPa", "vents in 4:32"),
            "while the Hold is up the frozen preset countdown gives way to the hold's own");
        assertEquals(Session.runNotificationText(false, "Grow", 2, 6, "1:30", "18.0 kPa"),
            Session.runNotificationText(false, "Grow", 2, 6, "1:30", "18.0 kPa", ""),
            "no hold: as before");
        assertEquals("Paused · ends in 4:32",
            Session.runNotificationText(true, "Grow", 2, 6, "1:30", "18.0 kPa", "vents in 4:32"),
            "discreet stays discreet: the Hold's limit, and no name, preset or pressure");
    }

    /* ---- (the safety review) the reason stays, and 255 s cannot be the limit ------------ */

    @Test
    void theReasonForTheStopIsOneLineThatStays() {
        assertEquals("Stopped: the hold reached its limit (5:00)", RunStopReason.holdLimit(300));
        assertEquals("Stopped: the hold reached its limit (1:00)", RunStopReason.holdLimit(60));
        assertEquals("Stopped: the two-hour limit", RunStopReason.TWO_HOURS);
    }

    @Test
    void theRecordKeepsTheFactAndTheLineIsSaidFromIt() {
        assertEquals("Stopped: the hold reached its limit (5:00)",
            RunStopReason.line(RunStopReason.WHY_HOLD_LIMIT, 300));
        assertEquals("Stopped: the hold reached its limit (4:30)",
            RunStopReason.line(RunStopReason.WHY_HOLD_LIMIT, 255), "said as the limit that ran");
        assertEquals("Stopped: the two-hour limit",
            RunStopReason.line(RunStopReason.WHY_TWO_HOURS, 0));
        assertEquals("Stopped: the pump refused the pressure on screen, twice - the cuff was vented",
            RunStopReason.line(RunStopReason.WHY_PUMP_REFUSED, 0), "the review of refusal-2");
        assertNull(RunStopReason.line(RunStopReason.WHY_NONE, 300), "STOP, the plan's end: none");
        // R11-6 gave 9 a meaning (WHY_NO_HOLD): a newer build's code is now 99.
        assertNull(RunStopReason.line(99, 300), "a code from a newer build: nothing guessed");
    }

    @Test
    void aStoppedSessionFilesItsReasonAndAnOldOneHasNone() throws Exception {
        Model.Sess s = new Model.Sess();
        s.id = "s1";
        s.stopWhy = RunStopReason.WHY_HOLD_LIMIT;
        s.stopLimSec = 300;
        Model.Sess back = Model.Sess.fromJson(s.toJson());
        assertEquals(RunStopReason.WHY_HOLD_LIMIT, back.stopWhy);
        assertEquals(300, back.stopLimSec);
        Model.Sess old = new Model.Sess();
        old.id = "s0";
        org.json.JSONObject o = old.toJson();
        assertFalse(o.has("stWhy"), "a session with no reason writes no key");
        Model.Sess oldBack = Model.Sess.fromJson(o);
        assertEquals(RunStopReason.WHY_NONE, oldBack.stopWhy);
        assertNull(RunStopReason.line(oldBack.stopWhy, oldBack.stopLimSec));
    }

    @Test
    void theLimitIsAlwaysAWholeHalfMinuteSoItCannotMeetThePumpsOwnStepDown() {
        // The Hold's preset holds 255 s at the pull, then 1 s a kPa lower. A limit of exactly
        // 255 s put its StopWork on the pump's own step down, and a lost stop could read as a
        // vent. Settings steps in 30 s; a hand-edited or restored file is rounded the same way.
        assertEquals(270, Model.roundHoldMaxSec(255));
        assertEquals(240, Model.roundHoldMaxSec(254));
        assertEquals(60, Model.roundHoldMaxSec(1));
        assertEquals(900, Model.roundHoldMaxSec(899));
        assertEquals(900, Model.roundHoldMaxSec(100000));
        assertEquals(300, Model.roundHoldMaxSec(300));
        for (int s = 0; s <= 1000; s++) {
            int r = Model.roundHoldMaxSec(s);
            assertEquals(0, r % 30, s + " -> " + r);
            assertTrue(r != 255, s + " -> 255");
            assertEquals(r, InRunHold.limitSec(s), "the Hold's limit is the rounded setting");
        }
    }

    @Test
    void aSavedOrRestoredFileWithAnOddLimitLoadsRounded() throws Exception {
        // A library's shape ("sets", "routines") so the load is a real one and not a re-seed.
        Model m = Model.fromJson("{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],"
            + "\"endHoldMaxSec\":255}");
        assertEquals(270, m.holdMaxSec, "a saved 255 loads as 270");
        assertEquals(240, Model.fromJson("{\"sets\":[],\"routines\":[],\"endHoldMaxSec\":250}")
            .holdMaxSec, "a saved 250 loads as 240");
        m.holdMaxSec = 255;
        m.clampAll();
        assertEquals(270, m.holdMaxSec, "clampAll rounds it too");
    }
}
