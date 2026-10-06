package org.openpump;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * S19 - THE "READY TO PROGRESS?" STEP-UP OFFER STAYS OFF TRAINER ROUTINES.
 *
 * The trainer already runs its own progression (Plan#evaluate: level, yield, deload). A
 * routine still carrying a {@link Model.Routine#trainerTrack} is one the trainer governs;
 * SessionActivity#stepUpCard's own loop must skip it (WiringCheck pins that it does), and
 * this pins the pure predicate it skips on.
 */
class ProgressionTest {

    private static Model.Routine routine(int track) {
        Model.Routine r = new Model.Routine();
        r.id = "r-1";
        r.trainerTrack = track;
        return r;
    }

    @Test
    void aPlainRoutineIsEligible() {
        assertTrue(Progression.eligibleForStepUp(routine(Model.TRAINER_TRACK_NONE)));
    }

    @Test
    void aGirthTrainerRoutineIsNotEligible() {
        assertFalse(Progression.eligibleForStepUp(routine(Plan.TRACK_GIRTH_INTERVAL)));
    }

    @Test
    void aLengthTrainerRoutineIsNotEligible() {
        assertFalse(Progression.eligibleForStepUp(routine(Plan.TRACK_LENGTH)));
    }

    @Test
    void aFeederTrainerRoutineIsNotEligible() {
        assertFalse(Progression.eligibleForStepUp(routine(Plan.TRACK_FEEDER)));
    }

    @Test
    void aNullRoutineIsNotEligible() {
        assertFalse(Progression.eligibleForStepUp(null));
    }

    @Test
    void aStepUpCopyStartsUnmarkedAndSoStaysEligibleForItsOwnNextOffer() {
        // The one thing S19 must NOT do: make a stepped-up copy ineligible for a future
        // offer of its own. stepUpCopy already starts trainerTrack unmarked (Model.java);
        // this just pins that eligibleForStepUp reads that the same way copy() does.
        Model.Routine copy = routine(Model.TRAINER_TRACK_NONE);
        assertTrue(Progression.eligibleForStepUp(copy));
    }
}
