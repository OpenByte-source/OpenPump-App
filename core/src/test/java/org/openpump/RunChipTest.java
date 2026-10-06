package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * POLISH ITEM 11 - THE RUN SCREEN'S STATUS LINE SAYS "ON TARGET" WHEN IT IS.
 *
 * "Shortfall 0.0 inHg — below commanded" said the pump was short and not short at once.
 * A gap that rounds to nothing is on target; a real gap is said in plain words, with the
 * thing it is short of named for the phase the pump is in.
 */
class RunChipTest {

    private String unitBefore;

    @BeforeEach void inHg() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_INHG; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    private static final double IN = Model.Fmt.KPA_PER_INHG;

    /** A live, armed step with a reading. */
    private static RunChip live(double readingKpa, double targetKpa, int phase) {
        return RunChip.of(true, false, false, false, false, true, readingKpa, targetKpa, phase);
    }

    @Test
    void aGapThatRoundsToNothingIsOnTarget() {
        RunChip c = live(22.0, 22, RunChip.HOLD);
        assertEquals("On target", c.text);
        assertEquals(Look.SAFE, c.tone);
        assertEquals("On target", live(22.1, 22, RunChip.PULL).text,
            "0.1 kPa is 0.0 inHg - the screen would print zero, so it is on target");
    }

    @Test
    void aShortPullSaysHowFarInPlainWords() {
        RunChip c = live(22 - 1.8 * IN, 22, RunChip.PULL);
        assertEquals("1.8 inHg short of the pull it was asked for", c.text);
        assertEquals(Look.COMMANDED, c.tone);
    }

    @Test
    void theWordFollowsThePhase() {
        assertEquals("1.8 inHg short of the hold it was asked for",
            live(22 - 1.8 * IN, 22, RunChip.HOLD).text);
        // A drop comes DOWN to its pressure, so short of it is still above it.
        assertEquals("0.6 inHg short of the drop it was asked for",
            live(12 + 0.6 * IN, 12, RunChip.DROP).text);
        assertEquals("0.6 inHg past the drop it was asked for",
            live(12 - 0.6 * IN, 12, RunChip.DROP).text);
        assertEquals("0.9 inHg over the pull it was asked for",
            live(22 + 0.9 * IN, 22, RunChip.PULL).text);
    }

    @Test
    void kilopascalsRoundAtTheirOwnPrecision() {
        Model.Fmt.unit = Model.Fmt.U_KPA;
        assertEquals("0.1 kPa short of the hold it was asked for", live(21.9, 22, RunChip.HOLD).text);
        assertEquals("On target", live(21.97, 22, RunChip.HOLD).text);
    }

    @Test
    void everyOtherStateIsOnePlainSentence() {
        RunChip starting = RunChip.of(false, false, false, false, false, true, 5, 22, RunChip.PULL);
        assertEquals("Starting — nothing is commanded yet", starting.text);
        assertEquals(Look.DIM, starting.tone);

        RunChip notDown = RunChip.of(true, true, true, false, false, true, 9, 0, RunChip.PULL);
        assertEquals("Resting — the cuff has not come down yet", notDown.text);
        assertEquals(Look.COMMANDED, notDown.tone, "pressure may still be on: amber");
        RunChip vented = RunChip.of(true, true, false, true, false, true, 0.2, 0, RunChip.PULL);
        assertEquals("Resting — the cuff is vented", vented.text);
        assertEquals(Look.SAFE, vented.tone, "green only on evidence of the vent");
        RunChip unknown = RunChip.of(true, true, false, false, false, false, 0, 0, RunChip.PULL);
        assertEquals("Resting — nothing is being commanded", unknown.text);
        assertEquals(Look.DIM, unknown.tone);

        RunChip paused = RunChip.of(true, false, false, false, true, true, 22 - 1.8 * IN, 22,
                                    RunChip.PULL);
        assertEquals("Clock paused — 1.8 inHg short of the pull it was asked for", paused.text);
        assertEquals(Look.COMMANDED, paused.tone);
        RunChip pausedBlind = RunChip.of(true, false, false, false, true, false, 0, 22,
                                         RunChip.PULL);
        assertEquals("Clock paused — not at −6.5 inHg yet", pausedBlind.text);

        RunChip blind = RunChip.of(true, false, false, false, false, false, 0, 22, RunChip.HOLD);
        assertEquals("No reading from the pump right now", blind.text);
        assertEquals(Look.DIM, blind.tone);
    }

    @Test
    void theLineNeverWearsTheFaultColour() {
        // Red is for real faults - the lost link and the unconfirmed stop have their own
        // screens. Nothing this line says is one.
        double[] readings = { 0, 5, 12, 22, 30, 40 };
        for (double r : readings)
            for (int ph = RunChip.PULL; ph <= RunChip.DROP; ph++)
                assertNotEquals(Look.CRITICAL, live(r, 22, ph).tone);
    }
}
