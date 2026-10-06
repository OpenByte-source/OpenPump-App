package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A LENGTH SESSION'S EXPANSION CODA IS HELD TO THE LOWER OF GIRTH'S AND LENGTH'S MAXIMUM
 * (review I3, the controller's ruling 2026-09-30 - the safer reading). The coda runs in the
 * GIRTH cylinder, so it expands girth tissue; capped by length's maximum alone, the review's
 * probe built `Expansion [g1] up=40` for an owner whose girth maximum is 37. Scale#workHardKpa,
 * in the builder (RxBuild#tractionRoutineFromRx, #holdToHardLimits) and the run
 * (SessionActivity#runHardKpaAt for a stage that does not pull).
 */
class CodaGirthCapTest {

    /** The review probe's owner: girth max 37 (−11 inHg), length max 41 (−12), a 5.0 girth
     *  tube and a 4.5 length tube, seven months, not new. */
    static Model owner() {
        Model m = new Model();
        m.ceilKpa = 43;
        m.rxNewToPumping = false;
        m.trainerMonthsPumping = 7;
        m.trainerMonthsAt = System.currentTimeMillis();
        m.rxWorkMaxKpa = 37;
        m.rxLengthMaxKpa = 41;
        Model.Cylinder g = new Model.Cylinder("Girth", 5.0, 23); g.id = "g1"; m.cylinders.add(g);
        Model.Cylinder l = new Model.Cylinder("Length", 4.5, 23); l.id = "l1"; m.cylinders.add(l);
        m.trainerLengthOn = true;
        m.trainerLength.loadLb = 11.8;
        m.trainerLength.strainSets = 3;
        return m;
    }

    @Test void theLimitIsTheLowerOfTheTwo() {
        Model m = owner();
        assertEquals(41, Scale.hardKpa(m, Plan.TRACK_LENGTH, 7));
        assertEquals(37, Scale.workHardKpa(m, Plan.TRACK_LENGTH, 7), "girth's binds the coda");
        assertEquals(37, Scale.workHardKpa(m, Plan.TRACK_GIRTH_INTERVAL, 7));
        m.rxWorkMaxKpa = 0;                            // girth's unset: the ceiling's
        assertEquals(41, Scale.workHardKpa(m, Plan.TRACK_LENGTH, 7));
        m.rxWorkMaxKpa = 42;
        assertEquals(41, Scale.workHardKpa(m, Plan.TRACK_LENGTH, 7), "length's when lower");
    }

    @Test void theProbesCodaIsBuiltUnderGirthsMaximum() {
        Model m = owner();
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_LENGTH, Plan.L3, 4, 120, 60, 40, false, 8.0,
                                 Mint.POWER_PCT);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        boolean sawCoda = false, sawPull = false;
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st.rest) continue;
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = m.set(st.setIds.get(j));
                if (s == null || s.rest) continue;
                int peak = s.ramp ? Math.max(s.up, s.up2) : s.up;
                if (st.traction) {
                    sawPull = true;
                    assertTrue(peak <= Scale.pullCapKpa(m, 4.5, System.currentTimeMillis()));
                } else {
                    if ("g1".equals(st.cylinderId)) sawCoda = true;
                    assertTrue(peak <= 37, st.name + " " + s.name + " up=" + peak
                        + " - past girth's maximum");
                }
            }
        }
        assertTrue(sawCoda, "the expansion coda, in the girth tube");
        assertTrue(sawPull, "and the pulls, in the length tube");
        assertTrue(r.name.length() > 0);
    }

    @Test void aSavedCodaAboveGirthsMaximumIsHeldDown() {
        Model m = owner();
        Model.Routine r = new Model.Routine();
        r.trainerTrack = Plan.TRACK_LENGTH;
        Model.Set coda = Model.Set.fixed(m.newSetId(), "Expansion hold", 40, 10, 120, 30, 50, 600);
        m.sets.add(coda);
        Model.Stage st = Model.Stage.of("Expansion", Model.STAGE_WORK, new String[]{ coda.id });
        st.cylinderId = "g1";
        r.stages.add(st);
        int n = RxBuild.holdToHardLimits(m, r, Plan.TRACK_LENGTH, System.currentTimeMillis());
        assertEquals(1, n);
        assertEquals(37, coda.up, "the lower of the two maxima");
    }
}
