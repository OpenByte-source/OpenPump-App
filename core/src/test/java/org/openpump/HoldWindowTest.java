package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * M1 - THE HOLD'S TWO MINUTES, COUNTED UNTIL SAVE; "HOLD AGAIN"; AND WHAT A CAPTURE IS AFTER
 * A SKIP OR THE LIMIT (the owner's items 4 and 5, and the C8 follow-up).
 *
 * The two minutes used to be checked only when the hold screen was left: waiting 2:01 there
 * counted as not standardised, while 4:30 of typing on the next screen, under the same hold,
 * still counted. The owner chose "two minutes, counted until Save". {@link HoldWindow#state}
 * is the one verdict every capture screen draws and every save files, asked at the moment it
 * is needed rather than remembered from when the hold screen was left.
 */
class HoldWindowTest {

    private static final int SEC = 30;
    private static final long NOW = 1_000_000L;

    /* ------------------------------------------------------------- the state */

    @Test void aServedDwellInsideTheTwoMinutesIsStandardised() {
        int s = HoldWindow.state(true, false, 30_000L, SEC, NOW + 60_000L, NOW);
        assertEquals(HoldWindow.OPEN, s);
        assertTrue(HoldWindow.standardised(s), "saved now, it is a standardised reading");
        assertFalse(HoldWindow.atRest(s));
    }

    @Test void theWindowIsCountedUntilSaveNotUntilTheHoldScreenIsLeft() {
        // The dwell completed; the person left the hold screen with a minute to spare and is
        // still typing. The window keeps running on the capture screen, whichever screen it
        // is: at Save, 2:01 after the dwell, the reading is held past the two minutes.
        long windowEnds = NOW + 60_000L;
        assertEquals(HoldWindow.OPEN, HoldWindow.state(true, false, 30_000L, SEC, windowEnds, NOW),
            "leaving the hold screen inside the window: still inside it");
        assertEquals(HoldWindow.LAPSED,
            HoldWindow.state(true, false, 30_000L, SEC, windowEnds, NOW + 61_000L),
            "the same capture saved a minute and a second later: the two minutes have passed");
        assertEquals(HoldWindow.LAPSED,
            HoldWindow.state(true, false, 30_000L, SEC, windowEnds, windowEnds),
            "the window closes AT its end, as Session#holdFinishedAt says");
    }

    @Test void heldWithoutTheDwellIsShort() {
        // "Measure now" on the end-of-run hold, tapped before the pump had held 30 s.
        assertEquals(HoldWindow.SHORT, HoldWindow.state(true, false, 12_000L, SEC, 0L, NOW));
        assertEquals(HoldWindow.SHORT, HoldWindow.state(true, false, 0L, SEC, 0L, NOW));
        assertFalse(HoldWindow.standardised(HoldWindow.SHORT));
        assertFalse(HoldWindow.atRest(HoldWindow.SHORT), "the pump is still holding: not at rest");
    }

    @Test void noHoldOnTheCuffIsAtRestWhateverTheDwellSaid() {
        // Skipped (and vented), ended by the limit, released from the notification, or never
        // held at all: a served dwell and an open window say nothing once the pump has vented.
        int s = HoldWindow.state(false, false, 30_000L, SEC, NOW + 60_000L, NOW);
        assertEquals(HoldWindow.AT_REST, s);
        assertTrue(HoldWindow.atRest(s));
        assertFalse(HoldWindow.standardised(s),
            "a hold vented before Save is never filed as held");
        assertEquals(HoldWindow.AT_REST, HoldWindow.state(false, false, 0L, SEC, 0L, NOW));
    }

    @Test void onlyOpenIsStandardisedAndOnlyAtRestIsAtRest() {
        int[] all = { HoldWindow.SHORT, HoldWindow.OPEN, HoldWindow.LAPSED, HoldWindow.AT_REST,
                      HoldWindow.VENTING };
        for (int i = 0; i < all.length; i++) {
            assertEquals(all[i] == HoldWindow.OPEN, HoldWindow.standardised(all[i]));
            assertEquals(all[i] == HoldWindow.AT_REST, HoldWindow.atRest(all[i]));
        }
    }

    @Test void theStateFilesTheOneMethodDecision() {
        // What each state saves, through the same Meas#captureMethod the screens draw from.
        int chosen = Model.Reading.METHOD_BPSSL;
        assertEquals(Model.Reading.METHOD_STANDARDIZED, HoldWindow.method(HoldWindow.OPEN, chosen));
        assertEquals(chosen, HoldWindow.method(HoldWindow.AT_REST, chosen),
            "after a skip or the limit the reading is at rest, filed under the method chosen");
        assertEquals(Model.Reading.METHOD_STANDARDIZED, HoldWindow.method(HoldWindow.LAPSED, chosen),
            "held past the two minutes: kept on its own, on the Std line with no hold recorded");
        assertEquals(Model.Reading.METHOD_STANDARDIZED, HoldWindow.method(HoldWindow.SHORT, chosen));
    }

    @Test void theWindowLeftIsWholeAndNeverNegative() {
        assertEquals(60_000L, HoldWindow.windowLeftMs(NOW + 60_000L, NOW));
        assertEquals(0L, HoldWindow.windowLeftMs(NOW - 1L, NOW));
        assertEquals(0L, HoldWindow.windowLeftMs(0L, NOW), "no window opened: none left");
    }

    /* ------------------------------------------------ the vent, before it is confirmed */

    @Test void aSentVentIsNotAtRestUntilThePumpReportsTheFall() {
        // Skip, the limit or the notification sent the stop: the hold is no longer live, and
        // until telemetry shows the fall (SAFETY.md #2) the reading is neither held nor at
        // rest - it is not filed at all.
        int s = HoldWindow.state(false, true, 30_000L, SEC, NOW + 60_000L, NOW);
        assertEquals(HoldWindow.VENTING, s);
        assertFalse(HoldWindow.atRest(s), "not at rest before the vent is confirmed");
        assertFalse(HoldWindow.standardised(s), "and never standardised");
        assertFalse(HoldWindow.mayFile(s), "nothing is saved while the vent is unconfirmed");
        assertEquals(HoldWindow.AT_REST, HoldWindow.state(false, false, 30_000L, SEC,
            NOW + 60_000L, NOW), "once confirmed, at rest");
    }

    @Test void aLiveHoldIsNeverVenting() {
        // Only a hold that is no longer live can be waiting on its vent.
        assertEquals(HoldWindow.OPEN, HoldWindow.state(true, true, 30_000L, SEC, NOW + 1L, NOW));
    }

    @Test void everyOtherStateMayBeFiled() {
        int[] files = { HoldWindow.SHORT, HoldWindow.OPEN, HoldWindow.LAPSED, HoldWindow.AT_REST };
        for (int i = 0; i < files.length; i++) assertTrue(HoldWindow.mayFile(files[i]));
    }

    /* ------------------------------------------------ time left to measure */

    @Test void theTimeLeftToMeasureIsTheSoonerOfTheWindowAndTheLimit() {
        // After Hold again the limit can be 30 s away while the window says two minutes.
        assertEquals(30_000L, HoldWindow.measureLeftMs(NOW + 120_000L, NOW + 30_000L, NOW),
            "the limit vents first, so that is the time left to measure");
        assertEquals(60_000L, HoldWindow.measureLeftMs(NOW + 60_000L, NOW + 200_000L, NOW),
            "the window closes first");
        assertEquals(60_000L, HoldWindow.measureLeftMs(NOW + 60_000L, 0L, NOW),
            "no limit armed: the window alone");
        assertEquals(0L, HoldWindow.measureLeftMs(0L, NOW + 30_000L, NOW),
            "no window: nothing left to measure standardised");
    }

    /* ------------------------------------------------ an inferred vent is not "confirmed" */

    @Test void anInferredVentIsSaidAsWhatItIs() {
        // "No pressure detected" counts as vented for every gate (the pump is still talking
        // and reports nothing to measure), but it is never announced as a confirmed vent -
        // the app's one rule for that state (SessionActivity#ventSayHead).
        String[] said = { HoldWindow.skippedSentence(HoldWindow.VENT_INFERRED),
                          HoldWindow.limitSentence(HoldWindow.VENT_INFERRED),
                          HoldWindow.ventedToast(HoldWindow.VENT_INFERRED) };
        for (int i = 0; i < said.length; i++) {
            String s = said[i].toLowerCase(java.util.Locale.ROOT);
            assertTrue(s.contains("no vacuum"), "\"" + said[i] + "\" says what the pump reads");
            assertFalse(s.contains("vented") || s.contains("confirmed"),
                "\"" + said[i] + "\" announces an inferred vent as confirmed");
        }
        assertTrue(HoldWindow.ventedToast(HoldWindow.VENT_REPORTED).contains("confirmed"),
            "a reported fall is a confirmed vent, and says so");
        assertTrue(HoldWindow.ventedToast(HoldWindow.VENT_BY_EYE).startsWith("You confirmed"));
    }

    /* ------------------------------------------------ a typed value and the hold */

    @Test void aTypedValueAppliesOnlyToTheHoldItWasTypedUnder() {
        // The typed-value box can outlive the hold: the limit fires while it is open. A
        // number typed under a hold is never applied to the at-rest capture that follows.
        assertTrue(HoldWindow.typedStillApplies(HoldWindow.OPEN, HoldWindow.OPEN));
        assertTrue(HoldWindow.typedStillApplies(HoldWindow.AT_REST, HoldWindow.AT_REST));
        assertFalse(HoldWindow.typedStillApplies(HoldWindow.OPEN, HoldWindow.VENTING),
            "opened under the hold, Set after the limit vented it: refused");
        assertFalse(HoldWindow.typedStillApplies(HoldWindow.OPEN, HoldWindow.AT_REST));
        assertFalse(HoldWindow.typedStillApplies(HoldWindow.LAPSED, HoldWindow.AT_REST));
        assertFalse(HoldWindow.typedStillApplies(HoldWindow.OPEN, HoldWindow.LAPSED),
            "any change of what the capture is refuses: the person types it again");
    }

    /* ------------------------------------------------------------- hold again */

    private static int again(boolean holding, int gate, boolean link, double held, int ceil,
                             long limitLeftMs) {
        return HoldWindow.againVerdict(holding, gate, link, held, ceil, limitLeftMs, SEC);
    }

    @Test void holdAgainContinuesALiveHoldWithTimeLeftToServeItAndMeasure() {
        assertEquals(HoldWindow.AGAIN_OK,
            again(true, Handoff.START_OK, true, 20.0, 57, 120_000L));
        // Exactly the dwell and the measuring minute left is enough; a millisecond less is not.
        long need = SEC * 1000L + HoldWindow.AGAIN_MEASURE_MS;
        assertEquals(HoldWindow.AGAIN_OK, again(true, Handoff.START_OK, true, 20.0, 57, need));
        assertEquals(HoldWindow.AGAIN_TOO_LATE,
            again(true, Handoff.START_OK, true, 20.0, 57, need - 1L),
            "the limit is never extended, so a hold again that could not finish is not offered");
    }

    @Test void holdAgainNeverStartsPressure() {
        // Nothing on the cuff - vented, released, ended by the limit: there is no hold to
        // continue, and a new one starts only through startStd and its START gate.
        assertEquals(HoldWindow.AGAIN_NOT_HOLDING,
            again(false, Handoff.START_OK, true, 20.0, 57, 120_000L));
        assertEquals(HoldWindow.AGAIN_NOT_HOLDING,
            again(false, Handoff.START_STOP_UNCONFIRMED, false, 20.0, 57, 0L),
            "not holding wins over every other answer");
    }

    @Test void holdAgainAsksTheSameStartGateAsTheFirstHold() {
        int[] refusals = { Handoff.START_BACK_TO_RUN, Handoff.START_GETTING_READY,
                           Handoff.START_STOP_UNCONFIRMED, Handoff.START_STILL_UNSAFE };
        for (int i = 0; i < refusals.length; i++)
            assertEquals(HoldWindow.AGAIN_GATED,
                again(true, refusals[i], true, 20.0, 57, 120_000L),
                "whatever the START gate refuses (" + refusals[i] + "), hold again refuses too");
    }

    @Test void holdAgainNeedsTheLinkAndAPressureInsideTodaysCeiling() {
        assertEquals(HoldWindow.AGAIN_NO_LINK,
            again(true, Handoff.START_OK, false, 20.0, 57, 120_000L));
        assertEquals(HoldWindow.AGAIN_OVER_CEILING,
            again(true, Handoff.START_OK, true, 20.0, 15, 120_000L),
            "a hold above the ceiling as it stands now is never prolonged");
        assertEquals(HoldWindow.AGAIN_OK,
            again(true, Handoff.START_OK, true, 20.0, 20, 120_000L),
            "at the ceiling is inside it");
    }

    @Test void everyRefusalHasASentence() {
        int[] all = { HoldWindow.AGAIN_NOT_HOLDING, HoldWindow.AGAIN_GATED,
                      HoldWindow.AGAIN_NO_LINK, HoldWindow.AGAIN_OVER_CEILING,
                      HoldWindow.AGAIN_TOO_LATE };
        for (int i = 0; i < all.length; i++) {
            String s = HoldWindow.againRefusalSentence(all[i]);
            assertTrue(s != null && s.length() > 10, "refusal " + all[i] + " says why");
        }
        assertEquals(null, HoldWindow.againRefusalSentence(HoldWindow.AGAIN_OK));
    }

    /* ------------------------------------------------------------- words */

    @Test void theWordsFollowTheOwnersRule() {
        String[] said = {
            HoldWindow.LAPSED_TITLE, HoldWindow.lapsedSentence("−5.9 inHg", SEC),
            HoldWindow.shortTitle(SEC), HoldWindow.shortSentence("−5.9 inHg", SEC),
            HoldWindow.skippedSentence(HoldWindow.VENT_REPORTED),
            HoldWindow.limitSentence(HoldWindow.VENT_REPORTED),
            HoldWindow.skippedSentence(HoldWindow.VENT_INFERRED),
            HoldWindow.limitSentence(HoldWindow.VENT_INFERRED),
            HoldWindow.skippedSentence(HoldWindow.VENT_BY_EYE),
            HoldWindow.limitSentence(HoldWindow.VENT_BY_EYE),
            HoldWindow.VENTING_SENTENCE, HoldWindow.keptSnack(true), HoldWindow.keptSnack(false),
        };
        String[] banned = { "flag", "not standardised", "no longer standardised",
                            "not comparable", "won't count", "anyway" };
        for (int i = 0; i < said.length; i++)
            for (int j = 0; j < banned.length; j++)
                assertFalse(said[i].toLowerCase(java.util.Locale.ROOT).contains(banned[j]),
                    "\"" + said[i] + "\" says \"" + banned[j] + "\"");
        assertTrue(HoldWindow.lapsedSentence("−5.9 inHg", SEC).contains("hold again"),
            "the lapsed card names the way back");
        assertTrue(HoldWindow.skippedSentence(HoldWindow.VENT_REPORTED).contains("vented"),
            "after a skip, once the pump reported the fall, the screen says the pump vented");
        assertTrue(HoldWindow.skippedSentence(HoldWindow.VENT_BY_EYE).contains("you confirmed"),
            "confirmed by the person's eyes, it says so - not that the pump reported it");
        assertFalse(HoldWindow.VENTING_SENTENCE.contains("vented"),
            "while the vent is being confirmed nothing claims it has happened");
    }
}
