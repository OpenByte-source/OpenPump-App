package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * GIRTH WORK RUNS IN A CYLINDER MARKED FOR GIRTH WHEN ONE IS LISTED (review M4, the
 * controller's ruling 2026-09-30): a girth routine and a length session's expansion coda - the
 * active one if it is marked for girth, else the first that fits the logged girth, else the
 * first marked for girth; the active cylinder only when none is (Model#girthCylinderForCoda).
 */
class GirthWorkCylinderTest {

    static Model rack(String... roles) {
        Model m = new Model();
        m.ceilKpa = 43;
        for (int i = 0; i < roles.length; i++) {
            Model.Cylinder c = new Model.Cylinder("Tube " + (i + 1), 5.0 + 0.2 * i, 23);
            c.id = "c" + (i + 1);
            c.role = roles[i];
            m.cylinders.add(c);
        }
        return m;
    }

    @Test void theActiveLengthTubeIsNotWhereGirthWorkRuns() {
        Model m = rack(Model.Cylinder.ROLE_GIRTH, Model.Cylinder.ROLE_LENGTH);
        m.activeCylinder = 1;                          // "Used for: Length" on the active tube
        assertEquals("c1", m.girthCylinderForCoda(0), "the girth tube, not the active one");
        m.activeCylinder = 0;
        assertEquals("c1", m.girthCylinderForCoda(0));
    }

    @Test void theActiveGirthTubeFirstThenTheFirstMarkedForGirth() {
        Model m = rack(Model.Cylinder.ROLE_LENGTH, Model.Cylinder.ROLE_GIRTH,
                       Model.Cylinder.ROLE_GIRTH);
        m.activeCylinder = 2;
        assertEquals("c3", m.girthCylinderForCoda(0), "the active one, marked for girth");
        m.activeCylinder = 0;                          // the length tube is active
        assertEquals("c2", m.girthCylinderForCoda(0), "the first marked for girth");
    }

    @Test void onlyALengthTubeFallsBackToTheActiveOne() {
        Model m = rack(Model.Cylinder.ROLE_LENGTH);
        m.activeCylinder = 0;
        assertEquals("", m.girthCylinderForCoda(0), "none marked for girth: the active one runs it");
    }

    @Test void theCodaNamesTheGirthTubeWhenTheLengthTubeIsActive() {
        Model m = rack(Model.Cylinder.ROLE_GIRTH, Model.Cylinder.ROLE_LENGTH);
        m.activeCylinder = 1;
        m.rxNewToPumping = false;
        m.trainerMonthsPumping = 7;
        m.trainerMonthsAt = System.currentTimeMillis();
        m.trainerLengthOn = true;
        m.trainerLength.loadLb = 6.0;
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_LENGTH, Plan.L3, 4, 120, 60, 30, false, 8.0,
                                 Mint.POWER_PCT);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        boolean coda = false;
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if ("Expansion".equals(st.name)) { coda = true; assertEquals("c1", st.cylinderId); }
            if (st.traction) assertEquals("c2", st.cylinderId);
        }
        assertEquals(true, coda);
    }
}
