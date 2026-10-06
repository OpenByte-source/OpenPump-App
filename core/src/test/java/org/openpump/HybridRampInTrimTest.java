package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import org.junit.jupiter.api.Test;

/**
 * t10 parity run 2, A-2: A HYBRID GIRTH RUN AFTER AN EXPANSION-ONLY LENGTH SESSION (R4 ramp-in
 * plus the 2A trim, R-06/R-07) left a 3-minute rest between the ramp-in and its one remaining
 * hold. The trim took the first hold and the rests in front of the others, but the first hold
 * had no rest in front of it - it followed the ramp-in directly - so the rest after it stayed,
 * separating nothing: Ramp-in -> Rest 180 s -> the 5-minute hold, 11.58 min where the editor
 * runs 8.58. The ramp-in climbs to the work (R-07); no rest follows it.
 */
class HybridRampInTrimTest {

    @Test void theRampInLeadsStraightIntoTheHoldThatIsLeft() {
        Model m = owner();                       // no length cylinder: expansion-only length
        m.trainerLengthOn = true;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerGirthHybrid = true;
        Mint.Rx g = girth(Plan.L3, 14, 30);
        asMint(m, m.trainerGirth, g, build(m, g, day(m)));
        Mint.Rx l = length(34);
        asMint(m, m.trainerLength, l, build(m, l, day(m)));
        Model.Routine length = SecondSessionTest.lengthSaved(m);
        assertFalse(Say.isTraction(length), "the expansion session");
        filed(m, length, NOW - 10 * MIN, 16);
        Model.Routine girth = SecondSessionTest.girthSaved(m);
        RunShape.Choice c = TrainerTab.dayChoice(m, girth, NOW, false, false);
        assertTrue(c.girthAfterLength, "length ran first");
        assertEquals(4, c.girthSetsOff, "2A: the trim stays - its five holds less one");
        Model.Routine r = RunShape.build(m, girth, c).routine;
        assertEquals(repeat(30, 1), workPulls(m, r), "the hybrid's holds run one");
        int in = stage(r, "rampin");
        assertEquals(0, in, "the ramp-in leads");
        assertFalse(r.stages.get(in + 1).rest, "and the hold follows it, no rest between");
        for (int i = 0; i < r.stages.size(); i++)
            assertFalse(r.stages.get(i).rest, "no rest is left separating nothing: stage " + i);
    }
}
