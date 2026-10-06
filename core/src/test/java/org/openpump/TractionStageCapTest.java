package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A TRACTION STAGE'S LIVE LIMIT IS THE TUBE IT PULLS IN (review I1, 2026-09-30). The run's cap
 * on a traction pull was worked out at Model#lengthBoreCm - the cylinder marked for length NOW
 * - so a tube re-marked for girth, or deleted, left the saved routine's pulls with the pressure
 * limit alone: the 15 lb load limit and a new person's first-month 4 lb went with it, and the
 * run screen's "≤ x" readout went blank. Scale#stagePullCapKpa (what SessionActivity
 * #runHardKpaAt asks for a traction stage) and Scale#stageBoreCm (what the readout converts at).
 */
class TractionStageCapTest {

    static final long NOW = System.currentTimeMillis();

    /** The review probe's person: ceiling 43 kPa, seven months, not new; one length tube of
     *  4.5 cm ("c1") and a girth tube of 5.0 cm ("g1"). */
    static Model probe() {
        Model m = new Model();
        m.ceilKpa = 43;
        m.rxNewToPumping = false;
        m.trainerMonthsPumping = 7;
        m.trainerMonthsAt = NOW;
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = NOW;
        Model.Cylinder g = new Model.Cylinder("Girth", 5.0, 23); g.id = "g1";
        Model.Cylinder l = new Model.Cylinder("Length", 4.5, 23); l.id = "c1";
        m.cylinders.add(g);
        m.cylinders.add(l);
        m.activeCylinder = 0;
        return m;
    }

    /** A traction stage pulling in `cylId`, as RxBuild#tractionStage names it. */
    static Model.Stage pull(String cylId) {
        Model.Stage st = new Model.Stage();
        st.traction = true;
        st.cylinderId = cylId;
        return st;
    }

    @Test void inItsLengthTubeTheLoadLimitAtThatBore() {
        Model m = probe();
        int at45 = Scale.pullCapKpa(m, 4.5, NOW);
        assertEquals(41, at45, "the probe's own figure: 15 lb at a 4.5 cm bore");
        assertEquals(at45, Scale.stagePullCapKpa(m, pull("c1"), 30, NOW),
            "a length tube: its load limit, not the saved pull");
        assertEquals(4.5, Scale.stageBoreCm(m, pull("c1")), 1e-9);
    }

    @Test void reMarkedForGirthTheLoadLimitStaysAndTheSavedPullBounds() {
        Model m = probe();
        m.cylinderById("c1").role = Model.Cylinder.ROLE_GIRTH;   // Settings › Used for: Girth
        assertEquals(0.0, m.lengthBoreCm(), 1e-9, "no length tube any more");
        assertEquals(43, Scale.pullCapKpa(m, m.lengthBoreCm(), NOW),
            "the old road: the ceiling alone (the review's probe)");
        int cap = Scale.stagePullCapKpa(m, pull("c1"), 30, NOW);
        assertEquals(30, cap, "never raised past the routine's saved pull");
        assertTrue(cap <= Scale.pullCapKpa(m, 4.5, NOW), "nor past the load limit in its tube");
        // A saved pull above the load limit in its tube is held to the load limit.
        assertEquals(41, Scale.stagePullCapKpa(m, pull("c1"), 43, NOW));
        assertEquals(4.5, Scale.stageBoreCm(m, pull("c1")), 1e-9,
            "the readout converts at the tube it pulls in - it does not go blank");
    }

    @Test void itsTubeDeletedTheWidestListedBoreAndTheSavedPull() {
        Model m = probe();
        m.cylinders.remove(m.cylinderById("c1"));
        assertEquals(5.0, Scale.stageBoreCm(m, pull("c1")), 1e-9,
            "the widest tube listed: the most restrictive load limit");
        int at50 = Scale.pullCapKpa(m, 5.0, NOW);
        assertTrue(at50 < 41, "15 lb is less pressure in a wider tube");
        assertEquals(at50, Scale.stagePullCapKpa(m, pull("c1"), 43, NOW));
        assertEquals(30, Scale.stagePullCapKpa(m, pull("c1"), 30, NOW));
        // No rack at all: the saved pull bounds it - never the pressure limit alone.
        m.cylinders.clear();
        assertEquals(0.0, Scale.stageBoreCm(m, pull("c1")), 1e-9);
        assertEquals(30, Scale.stagePullCapKpa(m, pull("c1"), 30, NOW));
    }

    @Test void twoLengthTubesTheStagesOwnBoreNotTheActiveOnes() {
        Model m = probe();
        Model.Cylinder wide = new Model.Cylinder("Length wide", 5.2, 23); wide.id = "c2";
        m.cylinders.add(wide);
        m.activeCylinder = 1;                          // the 4.5 is the length tube of the moment
        assertEquals(4.5, m.lengthBoreCm(), 1e-9);
        assertEquals(Scale.pullCapKpa(m, 5.2, NOW), Scale.stagePullCapKpa(m, pull("c2"), 43, NOW),
            "the stage pulls in the 5.2: its limit, lower than the 4.5's");
        assertTrue(Scale.stagePullCapKpa(m, pull("c2"), 43, NOW) < Scale.pullCapKpa(m, 4.5, NOW));
    }

    @Test void aNewPersonsFirstMonthFourPoundsHoldsWhateverTheRole() {
        Model m = probe();
        m.rxNewToPumping = true;
        m.trainerMonthsPumping = 0;
        assertTrue(Scale.isNew(m));
        int four = Scale.pullCapKpa(m, 4.5, NOW);
        assertTrue(four < 20, "4 lb at 4.5 cm, well under the first-month pressure: " + four);
        assertEquals(four, Scale.stagePullCapKpa(m, pull("c1"), 30, NOW));
        m.cylinderById("c1").role = Model.Cylinder.ROLE_GIRTH;
        assertEquals(four, Scale.stagePullCapKpa(m, pull("c1"), 30, NOW),
            "re-marked: still 4 lb in the tube it pulls in");
        m.cylinders.remove(m.cylinderById("c1"));
        assertEquals(Scale.pullCapKpa(m, 5.0, NOW), Scale.stagePullCapKpa(m, pull("c1"), 30, NOW),
            "deleted: 4 lb at the widest tube listed");
    }

    @Test void aStageNamingNoTubeIsTheActiveOnes() {
        Model m = probe();
        m.activeCylinder = 1;                          // the length tube
        assertEquals(41, Scale.stagePullCapKpa(m, pull(""), 30, NOW));
        m.activeCylinder = 0;                          // the girth tube: not a length one
        assertEquals(30, Scale.stagePullCapKpa(m, pull(""), 30, NOW));
        assertEquals(5.0, Scale.stageBoreCm(m, pull("")), 1e-9);
    }
}
