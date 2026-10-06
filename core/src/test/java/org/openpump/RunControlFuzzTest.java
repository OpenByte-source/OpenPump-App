package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * THE RUN SCREEN'S OWN DECISIONS, FUZZED ON THE SIMULATED PUMP.
 *
 * The safety review of refusal-3: LiveLinkFuzzTest drove the real pairing but a REPLICA of the
 * run screen - no stamps, no settle, no hold, no limit, step STARTs never refused - and seven of
 * the round's fixes could be reverted without it noticing. Here RunRig makes every decision
 * through RunControl and LiveLink, the objects SessionActivity calls, and the person does what
 * the review did by hand: taps and holds and releases, the routine offset and Edit upcoming
 * tapped inside a settle window, reverts, rests - while the pump answers late (in order), not
 * at all, or `2C FD`, a step's START included.
 *
 * The breaches (RunRig#check): quiet, the pump pulls above what the screen shows; not answering,
 * above the "may be at" figure; a START runs in a rest after its STOP, or after the run stopped;
 * the pump holds with no limit armed; an edit wrote the table while a START could still be
 * answered or waited to be written.
 *
 * The review of refusal-4 added what its own Activity-faithful rig did: Reverts, inserted rests
 * and +30 s inside the settle windows, answers no START can be for, the link lost and regained
 * - and a pump that never refuses, on which the run must never stop itself.
 */
class RunControlFuzzTest {

    static final int SEEDS = 1500;

    /** One run, as the reviewers drew it: fifty things the person does, then quiet. */
    static RunRig fuzz(long seed, boolean silent, boolean ungated) {
        Random rnd = new Random(seed);
        return drive(new RunRig(RunRig.plan(rnd, 24), RunRig.reviewerFates(
            new Random(seed * 31 + 7), silent, 2600)), rnd, ungated);
    }

    /** The same person, on a pump that never refuses (its answers late or lost). */
    static RunRig healthy(long seed) {
        Random rnd = new Random(seed);
        return drive(new RunRig(RunRig.plan(rnd, 24), RunRig.healthyFates(
            new Random(seed * 131 + 3))), rnd, false);
    }

    static RunRig drive(RunRig r, Random rnd, boolean ungated) {
        r.ungatedEdits = ungated;
        r.run(1500);
        for (int op = 0; op < 50 && r.breach == null && !r.stopped; op++) {
            int k = rnd.nextInt(100);
            if (k < 30) r.tap(18 + rnd.nextInt(14));
            else if (k < 40) r.hold();
            else if (k < 50) r.release();
            else if (k < 60) r.editTable(rnd, rnd.nextBoolean());
            else if (k < 72) r.revert();
            else if (k < 78) r.insertRest(2000 + rnd.nextInt(4000));
            else if (k < 84) r.extend();
            else if (k < 88 && r.noneWithinHorizon()) r.strayAnswer(rnd.nextInt(3) != 0);
            else if (k < 90) {
                // the link lost - for less than the auto-stop, or longer - then the prompt
                r.loseLink();
                r.run(rnd.nextBoolean() ? 1000 + rnd.nextInt(4000) : 7000 + rnd.nextInt(3000));
                r.revert();                         // an adjust sheet left open over it, or not
                r.regainLink();
                // answered at once, or once an auto-stop's vent may have been seen
                r.run(rnd.nextBoolean() ? 200 + rnd.nextInt(800) : 6000 + rnd.nextInt(3000));
                if (!r.reconnectResume()) r.reconnectEnd();
            }
            if (rnd.nextInt(3) == 0) {
                // inside a settle window: whatever a posted START is waiting on
                r.run(100 + rnd.nextInt(450));
                int j = rnd.nextInt(4);
                if (j == 0) r.revert();
                else if (j == 1) r.editTable(rnd, true);
                else if (j == 2) r.tap(18 + rnd.nextInt(14));
                else r.extend();
            }
            r.run(50 + rnd.nextInt(2500));
        }
        if (r.breach == null && !r.stopped) r.run(20000);
        return r;
    }

    private static void assertNoBreach(boolean silent, boolean ungated) {
        for (long seed = 1; seed <= SEEDS; seed++) {
            RunRig r = fuzz(seed, silent, ungated);
            assertNull(r.breach, "seed " + seed + (silent ? " (silent)" : "")
                + (ungated ? " (ungated edits)" : "") + ": " + r.breach + "\n" + r.log);
        }
    }

    @Test void theRunNeverPutsThePumpAboveTheScreenInARestOrWithoutItsLimit() {
        assertNoBreach(false, false);
    }

    @Test void norWhenAStartIsRefusedWithItsFdLost() {
        assertNoBreach(true, false);
    }

    @Test void norWhenNothingIsHiddenBehindTheSheetsAndTheSheetsAreLeftOpen() {
        // The person reaches only what the screen shows (uiReach); here RunControl alone must
        // refuse - a Revert over a rest, over Link Lost, over the reconnect prompt.
        for (long seed = 1; seed <= SEEDS / 3; seed++) {
            Random rnd = new Random(seed);
            RunRig r = new RunRig(RunRig.plan(rnd, 24), RunRig.reviewerFates(
                new Random(seed * 31 + 7), false, 2600));
            r.uiReach = false;
            drive(r, rnd, false);
            assertNull(r.breach, "seed " + seed + " (no UI reach): " + r.breach + "\n" + r.log);
        }
    }

    @Test void aPumpThatNeverRefusesIsNeverStoppedForNothing() {
        // The review of refusal-4: whatever the rig's person does, a pump that takes every
        // START (its answers late or lost) never sees the run stop itself, nor a breach.
        for (long seed = 1; seed <= SEEDS; seed++) {
            RunRig r = healthy(seed);
            assertNull(r.breach, "seed " + seed + " (healthy): " + r.breach + "\n" + r.log);
            assertEquals(0, r.pumpStops, "seed " + seed + ": a healthy run stopped\n" + r.log);
        }
    }

    @Test void norWhenATableIsWrittenUnderAStartWaitingToBeWritten() {
        // The gate held open: every edit goes, inside settle windows too - the stale stamp alone
        // must keep the pump where the screen says (the review of refusal-3, CRITICAL (a)).
        assertNoBreach(false, true);
    }

    @Test void theFuzzReachesWhatItIsFor() {
        // A property that never meets its cases proves nothing: over the seeds, the runs meet
        // convergences, the pump's own STOP, GIVE_UP, stale STARTs written again, edits held
        // back, holds and their limits.
        int converges = 0, stops = 0, giveUps = 0, stale = 0, refused = 0, holds = 0, limits = 0;
        int reverts = 0, revertsRefused = 0, rests = 0, losses = 0, autoStops = 0, resumed = 0;
        for (long seed = 1; seed <= 300; seed++) {
            RunRig r = fuzz(seed, false, false);
            converges += r.converges; stops += r.pumpStops; giveUps += r.giveUps;
            refused += r.editsRefused; holds += r.holds; limits += r.limitStops;
            reverts += r.reverts; revertsRefused += r.revertsRefused; rests += r.restsInserted;
            losses += r.losses; autoStops += r.autoStops; resumed += r.resumesAfterStop;
        }
        for (long seed = 1; seed <= 300; seed++) stale += fuzz(seed, false, true).staleStarts;
        assertTrue(converges > 100 && giveUps > 3 && stops > 0 && refused > 50 && holds > 50
                && stale > 5 && reverts > 100 && revertsRefused > 5 && rests > 50 && losses > 10
                && autoStops > 3 && resumed > 3,
            "converges " + converges + " stops " + stops + " giveUps " + giveUps + " stale "
            + stale + " refused edits " + refused + " holds " + holds + " limits " + limits
            + " reverts " + reverts + " refused " + revertsRefused + " rests " + rests
            + " link losses " + losses + " auto-stops " + autoStops + " resumed after " + resumed);
    }
}
