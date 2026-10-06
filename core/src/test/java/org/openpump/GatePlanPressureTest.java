package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * t10 fix round (review A, deviation D2) - THE L1 -> L2 GATE READS THE PLAN'S PRESSURE ONLY FOR
 * THE PLAN'S OWN ROUTINE AS THE PLAN BUILT IT.
 *
 * Lane A let a session whose peak missed 8 inHg count at the track's plan pressure, because a
 * P2 warm-up that stops under the work carries on through the holds after it, and a short
 * session can end under its own prescription (TrainerTab#gateHeldTrainingWeeks). That reading
 * is the plan's, so it is kept - but only where the session ran what the plan built: never a
 * session in a deload week (light work by design), never a routine the person lowered by hand,
 * never a routine of their own. Those are read at their peak, as before t10.
 */
class GatePlanPressureTest {

    private static final int TRAD = Plan.TRACK_GIRTH_TRADITIONAL;

    /** The person at Level 1 traditional, the plan at 27 kPa (8 inHg) since day 0, its own
     *  routine saved as the track's mint. */
    private static Model model() {
        Model m = PressureClockTest.model();
        m.trainerGirthStyle = TRAD;
        m.trainerMonthsPumping = 7;               // past the first month's 6 inHg
        Model.TrainerTrackState st = m.trainerGirth;
        st.level = Plan.L1;
        st.pressureKpa = 27;
        st.pressureSinceMs = PressureClockTest.at(0, 0);
        Mint.Rx rx = T10Fix.trad(Plan.L1, 3, 27);
        Model.Routine r = T10Fix.build(m, rx, T10Fix.day(m));
        T10Fix.asMint(m, st, rx, r);
        return m;
    }

    /** Two weeks of three sessions of `routineId`, each meeting its own minutes and peaking
     *  at 25 kPa - under 8 inHg, as a carried session can. */
    private static void twoWeeks(Model m, String routineId) {
        for (int w = 0; w < 2; w++)
            for (int d = 0; d <= 4; d += 2)
                PressureClockTest.file(m, routineId, PressureClockTest.at(7 * w + d, 9),
                                       15, 15, 25);
    }

    private static final long NOW = PressureClockTest.at(14, 8);

    @Test void thePlansOwnRoutineIsReadAtThePlansPressure() {
        Model m = model();
        twoWeeks(m, m.trainerGirth.lastMintId);
        assertEquals(2, TrainerTab.gateHeldTrainingWeeks(m, TRAD, NOW),
            "the plan's routine, carried under its pressure: two weeks at 8 inHg");
    }

    @Test void aRoutineLoweredByHandIsReadAtItsPeak() {
        Model m = model();
        Model.Routine r = m.routine(m.trainerGirth.lastMintId);
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st.rest || st.colour == Model.STAGE_WARM) continue;
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = m.set(st.setIds.get(j));
                if (s != null && s.up > 20) s.up = 18;
            }
        }
        twoWeeks(m, r.id);
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(m, TRAD, NOW),
            "lowered by hand: the peak is what ran, and it missed 8 inHg");
    }

    @Test void aRoutineOfTheirOwnIsReadAtItsPeak() {
        Model m = model();
        Model.Routine own = m.routine(m.trainerGirth.lastMintId).copy("r-own");
        own.trainerTrack = TRAD;
        m.routines.add(own);
        twoWeeks(m, own.id);
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(m, TRAD, NOW));
    }

    @Test void aDeloadWeekSessionIsReadAtItsPeak() {
        Model m = model();
        twoWeeks(m, m.trainerGirth.lastMintId);
        // The second week was a deload week: its light sessions do not hold the gate.
        Deload.remember(m, PressureClockTest.at(7, 0), PressureClockTest.at(14, 0));
        assertEquals(0, TrainerTab.gateHeldTrainingWeeks(m, TRAD, NOW),
            "a deload week's session is light by design, not the plan's pressure");
    }
}
