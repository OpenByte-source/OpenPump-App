package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import org.junit.jupiter.api.Test;

/**
 * t10 device walk M2 - "WHAT IT WRITES" SAYS THE WARM-UP THE ROUTINE GETS. The door's facts
 * (Model.Shape#summaryParts) still printed the pre-t10 "prime 4 min" from Model#rxWarmMin, a
 * field the trainer's build no longer reads (R-01). The warm-up fact now comes from the same
 * question the builder asks (RxBuild#p2Applies / Model#gentleWarmFor / the Program's "none").
 */
class ShapeSummaryWarmTest {

    private static String warmFact(Model m) {
        return Model.Shape.capture(m, "", "").summaryParts(m)[0];
    }

    @Test void p2IsTheFiveMinuteClimb() {
        Model m = owner();
        m.rxWarmMin = 4; m.rxWarmRamp = false;   // the old "prime 4 min"
        assertEquals("5-min climbing warm-up", warmFact(m));
    }

    @Test void theOldClimbChoiceIsStillP2() {
        Model m = owner();
        m.rxWarmRamp = true;
        m.programGirth.warm = Model.Program.WARM_RAMP;
        assertEquals("5-min climbing warm-up", warmFact(m));
    }

    @Test void marksEasilyIsTheGentleOne() {
        Model m = owner();
        m.marksEasily = true;
        assertEquals("gentle warm-up", warmFact(m));
    }

    @Test void noneIsNone() {
        Model m = owner();
        m.programGirth.warm = Model.Program.WARM_NONE;
        m.programLength.warm = Model.Program.WARM_NONE;
        assertEquals("no warm-up", warmFact(m));
    }

    @Test void tracksThatDifferAreNamedApart() {
        Model m = owner();
        m.trainerLengthOn = true;
        m.programGirth.warm = Model.Program.WARM_NONE;
        assertEquals("girth no warm-up, length 5-min climbing warm-up", warmFact(m));
    }

    @Test void neverThePrimeWords() {
        Model m = owner();
        String[] p = Model.Shape.capture(m, "", "").summaryParts(m);
        for (int i = 0; i < p.length; i++)
            assertTrue(!p[i].startsWith("prime") && !p[i].startsWith("climb "), p[i]);
    }
}
