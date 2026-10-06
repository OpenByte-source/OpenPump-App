package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 - A ROUTINE THE PLAN SAVED BEFORE THE t10 BUILD is the plan's, and is rewritten. Every
 * girth and length signature now states the t10 build token (Model#T10_BUILD_TOKEN); one
 * without it is rebuilt the old way (Model#legacyT10: the old warm-up, the old traditional
 * rests, no carry, no trim), so a routine saved then reads as the plan's own - not as the
 * person's edit - and the new build reaches it as a rewrite with a notice, as the 0.10 ramps
 * did (RampClimbTest).
 */
class T10UpgradeRoutineTest {

    private static Mint.Rx rx(Model m, int track, int level) {
        return Mint.prescribe(track, level, 1, 8.0 * Plan.HG, 2, m.ceilKpa, 0, null);
    }

    @Test void aRoutineSavedBeforeT10IsThePlansAndIsRewritten() {
        long now = System.currentTimeMillis();
        int[] tracks = { Plan.TRACK_GIRTH_INTERVAL, Plan.TRACK_GIRTH_TRADITIONAL,
                         Plan.TRACK_LENGTH };
        int[] levels = { Plan.L1, Plan.L3 };
        for (int track : tracks) for (int level : levels) {
            Model m = new Model();
            Mint.Rx rx = rx(m, track, level);
            String sig = Mint.signature(rx, m.mintShapeTag(track, level, now));
            // Before t10 a model at every default declared no shape at all.
            String oldSig = Mint.signature(rx, "");
            String where = "track " + track + " L" + level;
            assertTrue(SavedMint.shapeToken(sig, 'V') != null, where + ": today's carries it");
            assertNull(SavedMint.shapeToken(oldSig, 'V'));
            // What the plan built before t10: a scratch model the old way, as SavedMint
            // rebuilds it from the old signature.
            Model old = SavedMint.scratchOf(m);
            old.legacyT10 = true;
            old.rxFatigueHoldSec = Mint.HOLD_FATIGUE_SEC;   // before R11-5 the block was 45 s
            Model.Routine was = old.routine(RxBuild.routineFromRx(old, rx));
            assertFalse(SavedMint.edited(old, was, oldSig, 0, 0, now, null, now),
                where + ": the old build is the plan's own under its old signature");
            assertTrue(SavedMint.edited(old, was, sig, 0, 0, now, null, now),
                where + ": and is not today's build");
            // Today's build, under today's signature, is the plan's.
            Model.Routine fresh = m.routine(RxBuild.routineFromRx(m, rx));
            assertFalse(SavedMint.edited(m, fresh, sig, 0, 0, now, null, now), where);
            // The rewrite says what changed.
            assertEquals(Say.T10_BUILD_HEAD, Say.shapeChange(oldSig, sig), where);
            String words = Say.shapeChangeDetail(m, oldSig, sig);
            assertTrue(words.contains("30 s growing to 60 s"), where + ": " + words);
            assertEquals(track == Plan.TRACK_GIRTH_TRADITIONAL,
                words.contains("Rests between holds are 30 s"), where + ": " + words);
            assertNull(Say.shapeChange(sig, sig), "nothing moved, nothing said");
        }
    }

    @Test void theOldBuildIsTheOldWarmUpAndTheOldRests() {
        Model m = new Model();
        Mint.Rx rx = rx(m, Plan.TRACK_GIRTH_TRADITIONAL, Plan.L3);
        Model old = SavedMint.scratchOf(m);
        old.legacyT10 = true;
        Model.Routine was = old.routine(RxBuild.routineFromRx(old, rx));
        Model.Routine fresh = m.routine(RxBuild.routineFromRx(m, rx));
        // The old warm-up is the stage named "Warm-up" (a prime and an eased cycle); P2's is
        // named for where it gets to.
        assertEquals("Warm-up", was.stages.get(0).name);
        assertTrue(fresh.stages.get(0).name.startsWith("Warm-up to "), fresh.stages.get(0).name);
        // Traditional rests: the old setting (120 s / 180 s), now 30 s.
        int oldRest = -1, newRest = -1;
        for (Model.Stage st : was.stages) if (st.rest && !st.manual) { oldRest = st.restSec; break; }
        for (Model.Stage st : fresh.stages) if (st.rest && !st.manual) { newRest = st.restSec; break; }
        assertEquals(old.rxRestSecTrad, oldRest);
        assertEquals(Plan.TRAD_REST_SEC, newRest);
    }

    @Test void theFeedersSignatureDoesNotMove() {
        Model m = new Model();
        assertEquals("", m.rxShapeTag(Plan.TRACK_FEEDER, Plan.L3),
            "the feeder's build did not change, so nothing re-offers it");
    }
}
