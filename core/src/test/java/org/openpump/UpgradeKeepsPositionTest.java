package org.openpump;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 review D, F1 - "RUN THE SETUP" FROM THE UPGRADE CARD KEEPS THE PERSON'S VALUES (R-61:
 * "Your values are kept"). The setup is seeded with the plan as it stands; when every answer
 * that places the person - months, the two pressures, the girth style, the two tracks, new to
 * pumping, a recent layoff - is still the seeded one, Confirm writes the other answers only
 * and the position (level, week, pressure clock, earned and carried holds) stays. Any of them
 * changed, the person is placed from the answers, as a recalibration does.
 */
class UpgradeKeepsPositionTest {

    private static TrainerOnboard.Position seeded() {
        TrainerOnboard.Position p = new TrainerOnboard.Position();
        p.months = 7;
        p.girthKpa = 30.0;
        p.lengthKpa = 34.0;
        p.girthStyle = Plan.TRACK_GIRTH_INTERVAL;
        p.girthOn = true;
        p.lengthOn = true;
        p.isNew = false;
        p.layoff = false;
        return p;
    }

    @Test void unchangedAnswersKeepThePosition() {
        assertTrue(TrainerOnboard.positionKept(seeded(), seeded()));
        assertTrue(TrainerOnboard.positionKept(seeded(), seeded().copy()));
    }

    @Test void anyPositionAnswerChangedPlacesAgain() {
        TrainerOnboard.Position p = seeded();
        p.months = 8;
        assertFalse(TrainerOnboard.positionKept(seeded(), p), "months");
        p = seeded();
        p.girthKpa = 31.0;
        assertFalse(TrainerOnboard.positionKept(seeded(), p), "girth pressure");
        p = seeded();
        p.lengthKpa = 33.0;
        assertFalse(TrainerOnboard.positionKept(seeded(), p), "length pressure");
        p = seeded();
        p.girthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
        assertFalse(TrainerOnboard.positionKept(seeded(), p), "girth style");
        p = seeded();
        p.lengthOn = false;
        assertFalse(TrainerOnboard.positionKept(seeded(), p), "length switched off");
        p = seeded();
        p.girthOn = false;
        assertFalse(TrainerOnboard.positionKept(seeded(), p), "girth switched off");
        p = seeded();
        p.isNew = true;
        assertFalse(TrainerOnboard.positionKept(seeded(), p), "new to pumping");
        p = seeded();
        p.layoff = true;
        assertFalse(TrainerOnboard.positionKept(seeded(), p), "a recent layoff steps back");
    }

    /** With length off, its pressure answer is not asked and does not count; a length
     *  pressure never answered (no length position yet) is the same unanswered. */
    @Test void anUnaskedLengthAnswerDoesNotCount() {
        TrainerOnboard.Position a = seeded();
        a.lengthOn = false;
        a.lengthKpa = Double.NaN;
        TrainerOnboard.Position b = a.copy();
        b.lengthKpa = 30.0;
        assertTrue(TrainerOnboard.positionKept(a, b));
        TrainerOnboard.Position c = seeded();
        c.lengthKpa = Double.NaN;
        assertTrue(TrainerOnboard.positionKept(c, c.copy()));
    }

    @Test void noSeedIsNoKeep() {
        assertFalse(TrainerOnboard.positionKept(null, seeded()));
        assertFalse(TrainerOnboard.positionKept(seeded(), null));
    }
}
