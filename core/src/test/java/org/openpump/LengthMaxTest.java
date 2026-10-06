package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * LENGTH HAS ITS OWN "MOST YOU WILL GO TO" (Model#rxLengthMaxKpa, Scale#mostKpa). One answer
 * served both tracks, so length stopped at girth's maximum: the setup's length step said
 * "Where you are now stops at −10.6 inHg" to somebody who runs length at −10 with a most of
 * −12, and girth at −9 with a most of −11. Girth (and the feeder, which is girth's) keeps
 * rxWorkMaxKpa; every length cap reads length's own.
 */
class LengthMaxTest {

    static final long NOW = 1_780_000_000_000L;

    @AfterEach void units() { Model.Fmt.unit = Model.Fmt.U_INHG; }

    /** The owner's limits: ceiling 12.7 inHg, girth most −11 (37 kPa), length most −12
     *  (41 kPa); seven months, not new; length on and running at `lengthKpa`. */
    static Model owner(int lengthKpa) {
        Model m = new Model();
        m.ceilKpa = 43;
        m.rxNewToPumping = false;
        m.trainerMonthsPumping = 7;
        m.trainerMonthsAt = NOW;
        m.trainerEnrolledAt = NOW;
        m.rxWorkMaxKpa = 37;
        m.rxLengthMaxKpa = 41;
        m.clampAll();
        m.trainerEnrolled = true;
        m.trainerLengthOn = true;
        TrainerTab.Derived l = TrainerTab.deriveLength(7, lengthKpa, false, m.ceilKpa,
                                                       m.rxLengthMaxKpa);
        double plan = Scale.setupPlanKpa(Plan.TRACK_LENGTH, l.level, l.monthIndex, l.pressureKpa);
        m.trainerLength.level = l.level;
        m.trainerLength.weekIndex = l.weekIndex;
        m.trainerLength.setWorkingPressure(plan, NOW);
        m.trainerLength.offsetKpa = Scale.offsetFromAnswer(lengthKpa, plan);
        return m;
    }

    @Test void eachTrackReadsItsOwnMaximum() {
        Model m = owner(34);
        assertEquals(41.0, Scale.mostKpa(m, Plan.TRACK_LENGTH));
        assertEquals(37.0, Scale.mostKpa(m, Plan.TRACK_GIRTH_INTERVAL));
        assertEquals(37.0, Scale.mostKpa(m, Plan.TRACK_GIRTH_TRADITIONAL));
        assertEquals(37.0, Scale.mostKpa(m, Plan.TRACK_FEEDER), "the feeder is girth's");
        assertEquals(41, Scale.hardKpa(m, Plan.TRACK_LENGTH, 7));
        assertEquals(37, Scale.hardKpa(m, Plan.TRACK_GIRTH_INTERVAL, 7));
        assertEquals(41, Scale.pullHardKpa(m, Plan.TRACK_LENGTH));
        assertEquals(41.0, Scale.limitsOf(m, Plan.TRACK_LENGTH).mostKpa);
        assertEquals(37.0, Scale.limitsOf(m, Plan.TRACK_GIRTH_INTERVAL).mostKpa);
        assertEquals(37.0, Scale.limitsOf(m, Plan.TRACK_FEEDER).mostKpa);
        assertEquals(41, Scale.pullCapKpa(m, 0, NOW), "a traction pull is length work");
    }

    @Test void theSetupsLengthCapIsLengthsOwn() {
        // "Where you are now stops at" on the length step: Plan#startCapKpa with length's most.
        double cap = Plan.startCapKpa(false, 7, 43, 41);
        assertEquals(41.0, cap, 1e-9);
        assertTrue(cap > 37, "no longer girth's −11");
        // An answer of −11.5 (39 kPa) is kept, where girth's most used to hold it at 37.
        assertEquals(39.0, TrainerTab.deriveLength(7, 39, false, 43, 41).pressureKpa, 1e-9);
        assertEquals(37.0, TrainerTab.deriveLength(7, 39, false, 43, 37).pressureKpa, 1e-9);
    }

    @Test void aLengthRoutineRunsPastGirthsMostUpToItsOwn() {
        Model m = owner(39);                           // length at −11.5 inHg
        int month = TrainerTab.monthIndexNow(m, NOW);
        Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_LENGTH, m.trainerLength, month, null);
        assertEquals(39, rx.pressureKpa, "past girth's 37, under length's 41");
        assertEquals(39, RxBuild.commanded(m, rx, RxBuild.Day.at(m, NOW)).pressureKpa,
            "and as the builder commands it");
        m.rxLengthMaxKpa = 38;
        Mint.Rx held = TrainerTab.trackRx(m, Plan.TRACK_LENGTH, m.trainerLength, month, null);
        assertEquals(38, held.pressureKpa, "length's own most binds length");
    }

    @Test void girthKeepsItsOwnMaximum() {
        Model m = owner(34);
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerGirth.level = Plan.L3;
        m.trainerGirth.setWorkingPressure(Plan.WORKING_CAP_KPA, NOW);
        m.trainerGirth.offsetKpa = 4.0;                // 38 kPa asked
        int month = TrainerTab.monthIndexNow(m, NOW);
        Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, month, null);
        assertEquals(37, rx.pressureKpa, "girth stops at girth's most, not length's");
        assertEquals(37, RxBuild.commanded(m, rx, RxBuild.Day.at(m, NOW)).pressureKpa);
    }

    @Test void anUnsetLengthMostLeavesTheOtherLimits() {
        Model m = owner(34);
        m.rxLengthMaxKpa = 0;
        assertEquals(43, Scale.hardKpa(m, Plan.TRACK_LENGTH, 7), "the ceiling binds then");
        assertEquals(37, Scale.hardKpa(m, Plan.TRACK_GIRTH_INTERVAL, 7));
    }
}
