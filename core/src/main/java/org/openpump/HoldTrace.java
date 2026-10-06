package org.openpump;

/**
 * H1 (the re-review's concern 1) - A HOLD WHOSE PROCESS WAS KILLED OUTRIGHT IS SAID ON THE NEXT
 * LAUNCH.
 *
 * With a live hold in the foreground (HoldForeground), only an outright kill - a force stop,
 * kill -9 - can end the process under it. The system then removes the hold's notice with the
 * process, no callback runs, and nothing said anything: kill detection covers runs only, and
 * is kept that way. So a flag is persisted when a hold is armed and cleared on every way a
 * hold ends (its phase reaching NONE: a confirmed fall, the watch giving up, the person's own
 * eyes; a clean teardown of the service; a destroyed screen, which leaves its own note). The
 * next launch that finds it set with no hold of its own knows the process was killed under a
 * hold, and says so, plainly, once.
 *
 * IT ONLY INFORMS. Nothing about STOP, START's gate, the vent or any safety question reads it
 * (WiringCheck invariant 94). These are the pure decisions and the words (HoldTraceTest).
 */
public final class HoldTrace {

    private HoldTrace() { }

    /** Whether the flag should be set for a hold in this phase (HoldForeground#phase). */
    public static boolean live(int holdPhase) {
        return holdPhase != HoldForeground.NONE;
    }

    /** The flag is set and this process holds nothing: the process that held it ended with
     *  no callback. Asked once, on the first resume of a process. */
    public static boolean orphaned(boolean flagSet, boolean holdInThisProcess) {
        return flagSet && !holdInThisProcess;
    }

    /** At or below this the cuff reads as at rest: above telemetry jitter
     *  (Session#VENT_FALL_NOISE_FLOOR_KPA), well under the least pressure a hold commands. */
    public static final double REST_KPA = 1.0;

    /**
     * A new hold or run is starting while the message is still pending, and a fresh, real
     * reading shows the cuff at rest: what the message asks the person to check has been
     * checked by the pump, and the message is settled.
     */
    public static boolean restSettles(boolean pending, boolean freshReading, boolean noReading,
                                      double kpa) {
        return pending && freshReading && !noReading && kpa <= REST_KPA;
    }

    /** (the run-stop-watch follow-up) The trace is set for a hold AND for an ended run's stop
     *  not yet confirmed (HoldForeground#ventOwed: the service keeps both), so what is said is
     *  true of both - the pump was holding, or had been told to vent and was not yet seen to. */
    public static final String TITLE = "The app closed while the pump was holding or venting";
    /** Said plainly, and claims nothing it cannot know: nothing ran to send or see a stop. */
    public static final String MESSAGE = "The app was closed while the pump was holding or "
        + "before its vent was confirmed. Check the cuff is vented.";
}
