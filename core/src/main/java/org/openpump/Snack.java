package org.openpump;

/** Pure timing constants for the snackbar — no `import android`, so test.sh's desktop
 *  harness picks this up and SelfTest can pin the two hold durations. The view itself is
 *  built by Ui.snack(); this file exists only so the numbers are testable without a
 *  device. */
public final class Snack {
    private Snack() { }

    /** How long a plain, no-action snackbar stays before it auto-hides. */
    public static final int HOLD_MS = 3200;
    /** How long a snackbar carrying an action (e.g. "UNDO") stays — longer, because the
     *  round-2/round-3 decisions specify a 5-6s undo window on every actionable one. */
    public static final int HOLD_MS_ACTIONABLE = 5000;
    /** D2 - how long the sentence explaining a stop made in someone's ABSENCE stays: a
     *  question nobody answered, a guided start nobody came back to. They were not looking
     *  when it happened, so a bar gone in seconds would explain it to nobody; it stays until
     *  they tap OK, another bar replaces it, ANY OTHER SCREEN IS ENTERED (so it can never sit
     *  over the next run's STOP - SessionActivity#dropUntilSeen), or half an hour passes. */
    public static final int HOLD_MS_UNTIL_SEEN = 30 * 60 * 1000;
    /** Device check EMU10 (Low) - how long a snackbar that carried an action keeps its place
     *  after it times out, invisible and swallowing taps with its action switched off. A tap
     *  aimed at "Resume" a moment too late used to land on the summary underneath and record
     *  an answer ("Too much") nobody chose. */
    public static final int LATE_TAP_GUARD_MS = 1500;
}
