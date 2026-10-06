package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * THE LENGTH CYLINDER (owner, 2026-09-30): a cylinder the person MARKS for length pulls
 * straight away, with no erect girth logged; once one is, the fit check warns and no longer
 * stops it. The load and the pressure convert by that cylinder's BORE, never by a girth.
 */
class LengthCylinderTest {

    private static final double HG = Model.Fmt.KPA_PER_INHG;

    /** The owner's rack: Girth 5.0 cm and Length 4.5 cm, both 23 cm. */
    private static Model owner() {
        Model m = new Model();
        m.ceilKpa = 43;
        m.trainerEnrolled = true;
        m.trainerLengthOn = true;
        m.cylinders.add(new Model.Cylinder("Girth cylinder", 5.0, 23.0));
        m.cylinders.add(new Model.Cylinder("Length cylinder", 4.5, 23.0));
        m.ensureCylinderIds();
        m.activeCylinder = 0;
        return m;
    }

    private static Model.Routine lengthRoutine(Model m) {
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_LENGTH, Plan.L1, 1, 6.0 * Plan.HG, 2,
                                    m.ceilKpa, 0, null);
        return m.routine(RxBuild.routineFromRx(m, rx));
    }

    private static void logErectGirth(Model m, double cm) {
        Model.Reading g = new Model.Reading();
        g.ts = 1788440800000L;
        g.method = Model.Reading.METHOD_MSEG;
        g.gir = cm;
        m.measLog.all.add(g);
    }

    /* ------------------------------------------------------------------ the conversion */

    @Test
    void theBoreConvertsBothWays() {
        // 4.5 cm is 1.772 in across: pi/4 * 1.772^2 = 2.465 in^2, so 1.211 lb per inHg.
        double kpa = 9.7 * HG;
        double lb = Traction.loadLbAtBore(kpa, 4.5);
        assertEquals(11.744, lb, 0.005, "4.5 cm bore at -9.7 inHg");
        assertEquals(kpa, Traction.kpaForLbAtBore(lb, 4.5), 1e-9, "and back");
        assertEquals(9.7 * HG, Traction.kpaForLbAtBore(11.744, 4.5), 0.02);
        // The same physics as a girth of pi * bore - the bore stands where the tissue stood.
        assertEquals(Traction.loadLb(kpa, Math.PI * 4.5), lb, 1e-9);
        // No bore, nothing to convert at - never a divide by zero.
        assertEquals(0.0, Traction.kpaForLbAtBore(5.0, 0), 0.0);
        assertEquals(0.0, Traction.loadLbAtBore(kpa, 0), 0.0);
    }

    @Test
    void theModelConvertsAtTheLengthCylindersBoreEvenWithAGirthLogged() {
        Model m = owner();
        assertEquals(4.5, m.lengthBoreCm(), 1e-9);
        long now = System.currentTimeMillis();
        int before = Scale.pullKpa(m, now);
        logErectGirth(m, 12.7);
        assertEquals(before, Scale.pullKpa(m, now), "a girth reading does not move the pull");
        assertEquals(Mint.clampTractionKpa(Traction.kpaForLbAtBore(
            Scale.pullLoadLb(m, now), 4.5), Scale.pullCapKpa(m, 4.5, now)),
            Scale.pullKpa(m, now));
    }

    @Test
    void theFifteenPoundLimitIsHeldAtTheBore() {
        Model m = owner();
        long now = System.currentTimeMillis();
        // 15 lb in a 4.5 cm bore needs 12.4 inHg - under the 43 kPa ceiling, so it binds.
        int cap = Scale.pullCapKpa(m, m.lengthBoreCm(), now);
        assertEquals((int) Math.floor(Traction.kpaForLbAtBore(15.0, 4.5) + 1e-9), cap);
        assertTrue(Traction.loadLbAtBore(cap, 4.5) <= 15.0 + 1e-9);
        // And never past the ceiling, whatever the load.
        m.ceilKpa = 30;
        assertTrue(Scale.pullCapKpa(m, m.lengthBoreCm(), now) <= 30);
    }

    /* -------------------------------------------------------------- the pull rule */

    @Test
    void aLengthMarkedCylinderPullsWithNoGirthLogged() {
        Model m = owner();
        assertEquals(0.0, m.girthForTraction(), 0.0, "no erect girth logged");
        assertTrue(m.lengthPulls());
        Model.Routine r = lengthRoutine(m);
        assertTrue(Say.isTraction(r), "the traction session, not expansion only");
        // Its pulls run in the length cylinder, its coda in the girth one.
        String lId = m.cylinders.get(1).id, gId = m.cylinders.get(0).id;
        boolean pullInLength = false, codaInGirth = false;
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st.traction && lId.equals(st.cylinderId)) pullInLength = true;
            if ("Expansion".equals(st.name) && gId.equals(st.cylinderId)) codaInGirth = true;
        }
        assertTrue(pullInLength, "the pulls name the length cylinder");
        assertTrue(codaInGirth, "the coda names the girth cylinder, with no girth to fit by");
        assertTrue(m.tractionShapeTag(System.currentTimeMillis()).length() > 0);
        assertEquals("", m.lengthFitWarning(), "no girth, no fit to warn about");
    }

    @Test
    void aBadFitWarnsAndStillPulls() {
        Model m = owner();
        // A 12.7 cm girth is 4.04 cm across: a 4.5 cm bore is 11 % over - a girth fit, not a
        // traction one.
        logErectGirth(m, 12.7);
        int band = m.tractionFit(m.girthForTraction());
        assertFalse(Traction.pulls(band), "the fit band is not traction");
        assertTrue(Say.isTraction(lengthRoutine(m)), "and it still pulls");
        String warn = m.lengthFitWarning();
        assertTrue(warn.contains("looser than a traction fit"), warn);
        assertTrue(warn.contains("still pulls"), warn);
        // Too tight warns too.
        assertTrue(Model.lengthFitWarning(Traction.FIT_TOO_TIGHT).contains("tighter"));
        assertEquals("", Model.lengthFitWarning(Traction.FIT_TRACTION));
    }

    @Test
    void theCodaNeverRunsInTheLengthCylinder() {
        Model m = owner();
        // At this girth the 4.5 cm length tube reads as a girth fit, and it is the active one.
        logErectGirth(m, 12.7);
        m.activeCylinder = 1;
        assertEquals(Traction.FIT_GIRTH_IDEAL, Traction.fit(4.5, 12.7));
        assertEquals(m.cylinders.get(0).id, m.girthCylinderForCoda(12.7),
            "the coda runs in the girth cylinder, not the tube marked for length");
        assertEquals(m.cylinders.get(1).id, m.lengthCylinderId());
    }

    @Test
    void noLengthCylinderRunsExpansionOnlyAsBefore() {
        Model m = owner();
        m.cylinders.get(1).role = Model.Cylinder.ROLE_GIRTH;
        logErectGirth(m, Math.PI * 4.5);     // the 4.5 cm tube would fit as traction
        assertEquals(Traction.FIT_TRACTION, Traction.fit(4.5, m.girthForTraction()));
        assertFalse(m.lengthPulls());
        assertFalse(Say.isTraction(lengthRoutine(m)),
            "a close-fitting tube that is not marked for length does not pull");
        assertEquals("", m.tractionShapeTag(System.currentTimeMillis()));
    }

    @Test
    void theActiveLengthCylinderWinsElseTheFirst() {
        Model m = owner();
        m.cylinders.add(new Model.Cylinder("Second length", 4.0, 20.0));
        m.ensureCylinderIds();
        assertEquals(m.cylinders.get(1).id, m.lengthCylinderId(), "the first length one");
        m.activeCylinder = 2;
        assertEquals(m.cylinders.get(2).id, m.lengthCylinderId(), "the active one when it is");
        assertEquals(4.0, m.lengthBoreCm(), 1e-9);
    }

    @Test
    void theLadderPullsForALengthTubeWhateverItsFit() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L1;
        in.monthIndex = 4;
        in.ceilKpa = 43;
        in.fitState = Traction.FIT_TOO_LOOSE;
        in.lengthTube = true;
        in.boreCm = 4.5;
        Plan.Decision d = Plan.evaluate(in);
        assertFalse(d.reason != null && d.reason.contains("expansion only"),
            "a marked length cylinder is not answered as expansion only: " + d.reason);
        // The ceiling at the bore: 43 kPa in 4.5 cm pulls 15.4 lb, so the plan's 12 binds.
        assertEquals(12.0, Plan.loadCapLb(in), 1e-9);
        in.ceilKpa = 30;
        assertEquals(Traction.loadLbAtBore(30, 4.5), Plan.loadCapLb(in), 1e-9);
    }

    /* ---------------------------------------------------------------- the role itself */

    @Test
    void theRoleIsReadFromTheNameWhenAFileHasNone() throws Exception {
        assertEquals("length", Model.Cylinder.fromJson(new JSONObject(
            "{\"label\":\"Length cylinder\",\"bore\":\"4.5\",\"len\":\"23\"}")).role);
        assertEquals("length", Model.Cylinder.fromJson(new JSONObject(
            "{\"label\":\"old LENGTH tube\",\"bore\":\"4.5\",\"len\":\"23\"}")).role);
        assertEquals("girth", Model.Cylinder.fromJson(new JSONObject(
            "{\"label\":\"Main cylinder\",\"bore\":\"5\",\"len\":\"23\"}")).role);
        assertEquals("girth", Model.Cylinder.fromJson(new JSONObject(
            "{\"label\":\"Length cylinder\",\"role\":\"girth\",\"bore\":\"4.5\",\"len\":\"23\"}"))
            .role, "a saved role is kept, whatever the name");
        Model.Cylinder c = new Model.Cylinder("Travel cylinder", 5.0, 20.0);
        c.role = Model.Cylinder.ROLE_LENGTH;
        assertEquals("length", Model.Cylinder.fromJson(c.toJson()).role, "and survives a save");
        c.role = "nonsense";
        c.clamp();
        assertEquals("girth", c.role, "an unknown role falls back to the name");
    }

    @Test
    void theCardLineLeadsWithTheRole() {
        String was = Model.Fmt.sizeUnit;
        try {
            Model.Fmt.sizeUnit = Model.Fmt.S_CM;
            assertEquals("Length · 4.5 cm inside · 23.0 cm long",
                SetupText.cylinderLine(Model.Cylinder.ROLE_LENGTH, 4.5, 23.0));
            assertEquals("Girth · 5.0 cm inside · 23.0 cm long",
                SetupText.cylinderLine(Model.Cylinder.ROLE_GIRTH, 5.0, 23.0));
        } finally {
            Model.Fmt.sizeUnit = was;
        }
    }
}
