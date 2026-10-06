package org.openpump;

/**
 * THE RUN SCREEN'S STATUS LINE - a dot and one plain sentence under the chart (polish
 * item 11).
 *
 * It used to read "Shortfall 0.0 inHg — below commanded": the pump short and not short in
 * one breath, because the test was ±0.05 kPa while the screen prints tenths of an inch. A
 * gap that PRINTS as zero is now on target. A real gap is said in words, with the thing it
 * is short of named for the phase the pump is in - the pull while it climbs, the hold once
 * it is there, the drop while it comes down (a drop is short of its pressure while still
 * above it). The number is Model.Fmt#dMag, the magnitude, because the words already say
 * which way.
 *
 * The other states keep their meaning and their priority, each as one sentence: starting
 * (nothing armed yet), resting (vented, or not yet down), the paused set clock, and no
 * reading. The dot's colour follows the app's rules: green for on target and for a vent the
 * telemetry has evidenced, amber while a pressure is being chased or may still be on, grey
 * where nothing is being judged. Red is never used here - the real faults (a lost link, an
 * unconfirmed stop) have screens of their own.
 *
 * Pure: the caller resolves the phase and the pressure the step asked for; this only words it.
 */
public final class RunChip {

    /** The phase the gap is measured in - the word for what was asked. */
    public static final int PULL = 0, HOLD = 1, DROP = 2;

    /** The dot's colour, a Look colour. */
    public final int tone;
    /** The sentence. */
    public final String text;

    private RunChip(int tone, String text) { this.tone = tone; this.text = text; }

    /**
     * @param armed       a preset is armed (false in the settle after a rest, a rejoin or a
     *                    batch boundary, when the table is being rewritten)
     * @param resting     a rest is playing, planned or inserted
     * @param restNotDown resting, with evidence the cuff has NOT come down
     * @param vented      resting, with evidence the cuff HAS come down
     * @param clockPaused the set's clock is paused because the cuff is not at pressure
     * @param hasReading  a live reading is in hand
     * @param readingKpa  that reading
     * @param targetKpa   what the step asked for in this phase (the pull, the hold's own
     *                    pressure, or the drop)
     * @param phase       PULL, HOLD or DROP
     */
    public static RunChip of(boolean armed, boolean resting, boolean restNotDown,
                             boolean vented, boolean clockPaused, boolean hasReading,
                             double readingKpa, double targetKpa, int phase) {
        if (!armed)
            return new RunChip(Look.DIM, "Starting — nothing is commanded yet");
        if (resting) {
            // A rest commands nothing, so it cannot be short of anything.
            if (restNotDown)
                return new RunChip(Look.COMMANDED, "Resting — the cuff has not come down yet");
            if (vented)
                return new RunChip(Look.SAFE, "Resting — the cuff is vented");
            return new RunChip(Look.DIM, "Resting — nothing is being commanded");
        }
        if (clockPaused) {
            // The paused clock outranks the gap: a countdown that has visibly stopped is the
            // most urgent thing on the screen to explain. The gap is still said - "how far
            // under" decides whether to reseat or wait.
            double gap = readingKpa - targetKpa;
            if (hasReading && !roundsToZero(gap))
                return new RunChip(Look.COMMANDED, "Clock paused — " + gapWords(gap, phase));
            return new RunChip(Look.COMMANDED,
                "Clock paused — not at " + Model.Fmt.p(targetKpa) + " yet");
        }
        if (!hasReading)
            return new RunChip(Look.DIM, "No reading from the pump right now");
        double gap = readingKpa - targetKpa;
        if (roundsToZero(gap)) return new RunChip(Look.SAFE, "On target");
        return new RunChip(Look.COMMANDED, gapWords(gap, phase));
    }

    /** Does the gap print as zero in the display unit? Then the screen calls it on target. */
    static boolean roundsToZero(double gapKpa) {
        return Model.Fmt.dMag(gapKpa).startsWith("0.0 ");
    }

    /** "1.8 inHg short of the pull it was asked for", and its siblings. `gap` is reading
     *  minus target, in kPa: a pull or a hold is short while the reading is under it; a
     *  drop comes down, so it is short while the reading is still above it. */
    static String gapWords(double gap, int phase) {
        String word = phase == DROP ? "drop" : phase == HOLD ? "hold" : "pull";
        String how;
        if (phase == DROP) how = gap > 0 ? "short of" : "past";
        else how = gap < 0 ? "short of" : "over";
        return Model.Fmt.dMag(gap) + " " + how + " the " + word + " it was asked for";
    }
}
