package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import org.junit.jupiter.api.Test;

/**
 * t10 fix round (review A F2, merge open item 3) - THE MANUAL RUN'S WARM-UP SETTINGS STAY THE
 * MANUAL RUN'S.
 *
 * Since t10 a plan routine warms up with P2 (R-01), or with the gentle warm-up for somebody who
 * marks or bruises easily (K3); neither reads the old warm-up steppers (Model#rxWarmMin,
 * #rxPrimeKpa, #rxEaseHg, #rxWarmSteps, #rxWarmRamp), which now shape only a manual run's
 * warm-up. So:
 * <ul>
 * <li>a warm-up length of 0 does not take the gentle warm-up away from somebody who marks
 *     (it used to hand them P2 and its carry, and the P2-style ramp-in after length);</li>
 * <li>moving those steppers does not move a plan routine's signature (it used to re-sign every
 *     plan routine: a rewrite and a notice for an identical build).</li>
 * </ul>
 * A routine saved before t10 is still rebuilt the old way, where the warm-up length did decide
 * (Model#legacyT10), so an upgrader's saved routine is still read as the plan's.
 */
class ManualWarmSettingsTest {

    private static Model marks(int warmMin) {
        Model m = owner();
        m.marksEasily = true;
        m.rxWarmMin = warmMin;
        return m;
    }

    @Test void warmUpLengthZeroKeepsTheGentleWarmUp() {
        Model zero = marks(0);
        Model four = marks(4);
        assertTrue(zero.gentleWarmFor(Plan.TRACK_GIRTH_INTERVAL), "K3: marks -> gentle");
        assertTrue(zero.gentleWarmFor(Plan.TRACK_LENGTH));
        assertFalse(RxBuild.p2Applies(zero, Plan.TRACK_GIRTH_INTERVAL), "not P2");
        Mint.Rx rx = girth(Plan.L2, 12, 30);
        Model.Routine a = build(zero, rx, day(zero));
        Model.Routine b = build(four, rx, day(four));
        assertEquals(SavedMint.workPrint(four, b), SavedMint.workPrint(zero, a),
            "the gentle warm-up, whatever the manual run's warm-up length");
        assertFalse(zero.set(a.stages.get(0).setIds.get(0)).name.startsWith("Warm-up to"),
            "not P2's reps");
        // ...and after length the ramp-in is the marks version: 3 reps.
        Model.Routine r4 = build(zero, girth(Plan.L3, 14, 30),
                                 GirthAfterLengthTest.afterLength(zero));
        assertEquals(list(24, 27, 30), pulls(zero, r4, stage(r4, "rampin")));
    }

    @Test void theProgramsNoneStillMeansNone() {
        Model m = marks(4);
        m.programGirth.warm = Model.Program.WARM_NONE;
        assertFalse(m.gentleWarmFor(Plan.TRACK_GIRTH_INTERVAL));
        assertFalse(marks(4).gentleWarmFor(Plan.TRACK_FEEDER), "the feeder has none");
        Model off = owner();
        off.rxWarmMin = 0;
        assertFalse(off.gentleWarmFor(Plan.TRACK_GIRTH_INTERVAL), "no answer, no gentle");
    }

    @Test void theManualSteppersDoNotMoveAPlanSignature() {
        int[] tracks = { Plan.TRACK_GIRTH_INTERVAL, Plan.TRACK_GIRTH_TRADITIONAL,
                         Plan.TRACK_LENGTH };
        for (int mk = 0; mk < 2; mk++)
            for (int t = 0; t < tracks.length; t++)
                for (int i = 0; i < 6; i++) {
                    Model base = owner();
                    Model moved = owner();
                    base.marksEasily = mk == 1;
                    moved.marksEasily = mk == 1;
                    switch (i) {
                        case 0: moved.rxWarmMin = 0; break;
                        case 1: moved.rxWarmMin = 9; break;
                        case 2: moved.rxPrimeKpa = 17.0; break;
                        case 3: moved.rxEaseHg = 1.5; break;
                        case 4: moved.rxWarmRamp = true; break;
                        default: moved.rxWarmRamp = true; moved.rxWarmSteps = 7; break;
                    }
                    String where = "track " + tracks[t] + ", stepper " + i + ", marks " + mk;
                    assertEquals(base.mintShapeTag(tracks[t], Plan.L3, NOW),
                                 moved.mintShapeTag(tracks[t], Plan.L3, NOW), where);
                }
        // The gentle warm-up's own settings still do.
        Model a = marks(4);
        Model b = marks(4);
        b.gentleWarmSpeedPct = 45;
        assertNotEquals(a.mintShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, NOW),
                        b.mintShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, NOW));
    }

    @Test void aSignatureReadsBackAndIsThePlansOwn() {
        Model m = marks(0);
        m.rxPrimeKpa = 17.0;
        Mint.Rx rx = girth(Plan.L2, 12, 30);
        String sig = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, NOW));
        Model.Routine r = build(m, rx, day(m));
        assertFalse(SavedMint.edited(m, r, sig, 0, 0, NOW, null, NOW),
            "the plan's own routine, read back from its signature");
    }

    @Test void aRoutineSavedBeforeT10IsStillRebuiltTheOldWay() {
        // The old build: a warm-up length of 0 meant no warm-up at all, marks or not.
        Model s = marks(0);
        s.legacyT10 = true;
        assertFalse(s.gentleWarmFor(Plan.TRACK_GIRTH_INTERVAL));
        s.rxWarmMin = 4;
        assertTrue(s.gentleWarmFor(Plan.TRACK_GIRTH_INTERVAL));
    }
}
