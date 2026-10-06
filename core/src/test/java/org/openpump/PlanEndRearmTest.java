package org.openpump;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A REST THAT OUTLIVES THE PLAN MAY NOT PUT THE LAST SET BACK ON THE PUMP -
 * {@link RunEdit#mayRearmRunningPreset}.
 *
 * The sequence, as SessionActivity walks it: the last set of a routine that has an
 * after-test is playing; the user taps Rest (the rest vents the cuff and posts its own
 * end for 30 s later); then Skip. Skip reaches playPreset(plan.size()), which returns into
 * endOfPlan() BEFORE the line that stood an inserted rest down, so the rest's end stays
 * posted. endOfPlan() hands the pump to the after-assessment, which keeps `running` true
 * and never moves planIdx off the last set. Thirty seconds later the rest's end fires and
 * asks the only question the resume path asked - RunEdit.canOverride(running, planIdx,
 * size) - and gets YES: it rewrites the table and starts the last set's slot over the
 * assessment's vent or pull, cancelling the assessment's vent watch as it arms.
 *
 * The Activity is not executable here (test.sh compiles only android-free sources), so
 * this pins the DECISION the re-arm sites ask, and nothing more.
 *
 * SAID PLAINLY, because a review found it: THIS TEST WOULD STILL PASS WITH playPreset
 * REVERTED, or with any site asking the question and ignoring it. The regression guard for
 * the sequence itself is WiringCheck invariant 114, which fails the build unless playPreset
 * stands the rest and the hold down before it can return into endOfPlan(), unless every
 * re-arm site (resumeRunningPreset, revertOverride, RestArm, RevertArm, extendPreset,
 * resumeAfterReconnect) asks RunEdit.mayRearmRunningPreset WITH assessBusy() before it
 * arms, and unless both doors into a rest (RestNowTap, insertRest) ask
 * commandFreezeReason() before they open or vent anything. Its own self-tests show each
 * reviewed shape failing.
 */
class PlanEndRearmTest {

    /** A two-set routine, as Pulse Session is: index 1 is the last set. */
    private static final int SIZE = 2;
    private static final int LAST = SIZE - 1;

    @Test
    void theRestEndingDuringTheAfterAssessmentRearmsNothing() {
        // The state at the moment the rest's end fires, field for field.
        boolean running = true;          // the after-assessment runs inside the run
        int planIdx = LAST;              // playPreset returned before assigning planIdx
        boolean assessOwnsPump = true;   // assessVenting, then assessing

        assertTrue(RunEdit.canOverride(running, planIdx, SIZE),
            "the old question says a preset is playing - that is the defect: it cannot see "
            + "that the plan has ended into the assessment");
        assertFalse(RunEdit.mayRearmRunningPreset(running, assessOwnsPump, planIdx, SIZE),
            "the assessment owns the pump: nothing of the run's may be armed over it");
    }

    @Test
    void aPauseInsideTheRunStillResumes() {
        // A rest or a hold ending on the last set BEFORE the plan ends is an ordinary resume.
        assertTrue(RunEdit.mayRearmRunningPreset(true, false, LAST, SIZE), "last set");
        assertTrue(RunEdit.mayRearmRunningPreset(true, false, 0, SIZE), "first set");
    }

    @Test
    void aRunThatHasEndedRearmsNothing() {
        // The rule RestArm and RevertArm already had (`if (!running) return;`), kept.
        assertFalse(RunEdit.mayRearmRunningPreset(false, false, LAST, SIZE),
            "finishSession has run: nothing is armed after the end of a run");
        assertFalse(RunEdit.mayRearmRunningPreset(false, true, LAST, SIZE),
            "and not while an assessment is still stood up either");
    }

    @Test
    void noIndexOutsideThePlan() {
        assertFalse(RunEdit.mayRearmRunningPreset(true, false, -1, SIZE),
            "the settle before the first set: nothing is playing");
        assertFalse(RunEdit.mayRearmRunningPreset(true, false, SIZE, SIZE),
            "past the last set");
    }

    @Test
    void theBeforeAssessmentOwnsThePumpToo() {
        // The before-pull runs with `running` set by beginRunFlow and whatever planIdx the
        // last run left. Nothing of a run may be armed over it either.
        assertFalse(RunEdit.mayRearmRunningPreset(true, true, 0, SIZE),
            "the before-pull owns the pump");
    }
}
