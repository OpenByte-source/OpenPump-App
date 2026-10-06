package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * G2 (the owner's decision, 2026-10-01): CLIMB TO MY MAXIMUM. With a track's own "Most you
 * will go to" above its usual top, the plan's existing pressure step goes on to that maximum
 * instead of stopping at the top - never past the ceiling, 15 inHg or (length) the 15 lb load
 * limit; a new person's first month, no maximum, or one at the top changes nothing. On the
 * length track the pull follows the length pressure.
 *
 * The owner's profile: 7 months, girth L3 at 30 kPa with a most of 37 (-10.9 inHg), length
 * L3 at 33.9 + 0.14 offset with a most of 41 (-12.1), length cylinder 4.5 cm, ceiling 43.
 */
class ClimbToMaximumTest {

    private static final double USUAL = Plan.WORKING_CAP_KPA;   // 10 inHg, 33.86 kPa

    private static Model owner() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = 1L;
        m.rxNewToPumping = false;
        m.ceilKpa = 43;
        m.rxWorkMaxKpa = 37;
        m.rxLengthMaxKpa = 41;
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        m.trainerGirth.level = Plan.L3;
        m.trainerGirth.pressureKpa = 34;
        m.trainerLength.level = Plan.L3;
        m.trainerLength.pressureKpa = 33.9;
        m.trainerLength.offsetKpa = 0.14;
        m.trainerLength.loadLb = 12.1;
        Model.Cylinder lt = new Model.Cylinder();
        lt.id = "L"; lt.label = "Length cylinder"; lt.role = Model.Cylinder.ROLE_LENGTH;
        lt.boreCm = 4.5; lt.lengthCm = 23.0;
        Model.Cylinder gt = new Model.Cylinder();
        gt.id = "G"; gt.label = "Girth cylinder"; gt.boreCm = 5.0; gt.lengthCm = 23.0;
        m.cylinders.add(gt);
        m.cylinders.add(lt);
        return m;
    }

    @Test void theTopTheGirthPlanClimbsTo() {
        Model m = owner();
        assertTrue(Scale.climbs(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7));
        assertEquals(37, Scale.climbTopWholeKpa(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7));
        assertEquals(37.0, Scale.planTopKpa(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7), 1e-9);

        m.rxWorkMaxKpa = 34;                    // at the top as the wire carries it
        assertFalse(Scale.climbs(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7));
        assertEquals(USUAL, Scale.planTopKpa(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7), 1e-9);
        m.rxWorkMaxKpa = 0;                     // not set
        assertFalse(Scale.climbs(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7));

        m.rxWorkMaxKpa = 60;                    // past the ceiling and 15 inHg
        assertEquals(43, Scale.climbTopWholeKpa(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7),
            "never past the device ceiling");
        m.ceilKpa = 60;
        assertEquals(Plan.absoluteCapWholeKpa(),
            Scale.climbTopWholeKpa(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7),
            "never past 15 inHg");

        Model n = owner();
        n.rxNewToPumping = true;
        assertFalse(Scale.climbs(n, Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 0),
            "a new person's first month keeps its hard limits");
    }

    private static Plan.Inputs girthAt(double kpa, double climbTop) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.pressureKpa = kpa;
        in.ceilKpa = 43;
        in.netTupMin = Plan.netMilestoneMin(Plan.L3);
        in.trainingWeeksAtPressure = Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS;
        in.firstDeloadPending = false;
        in.climbTopKpa = climbTop;
        return in;
    }

    @Test void theGirthStepGoesOnToTheMaximum() {
        Plan.Decision at = Plan.evaluate(girthAt(34, Double.NaN));
        assertEquals(Plan.ACTION_HOLD, at.action, "without a maximum above it: held at the top");

        Plan.Decision up = Plan.evaluate(girthAt(34, 37));
        assertEquals(Plan.ACTION_RAISE_PRESSURE, up.action);
        assertEquals(37.0, up.pressureKpa, 1e-9, "one step, the last landing on the maximum");

        Plan.Inputs early = girthAt(34, 37);
        early.trainingWeeksAtPressure = 2;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(early).action,
            "the same 3-training-week step, not a faster one");

        Plan.Decision top = Plan.evaluate(girthAt(37, 37));
        assertEquals(Plan.ACTION_HOLD, top.action);
        assertTrue(top.reason.contains("most you said you will go to"), top.reason);
    }

    @Test void thePrescriptionCarriesTheClimbedFigure() {
        Model m = owner();
        Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 7,
            new Plan.Decision(Plan.ACTION_RAISE_PRESSURE, Plan.TAG_INFERRED, "", "", 37.0, 0));
        assertEquals(37, rx.pressureKpa, "past the usual 34, to the owner's -10.9 inHg");
        m.rxWorkMaxKpa = 0;
        Mint.Rx held = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 7,
            new Plan.Decision(Plan.ACTION_RAISE_PRESSURE, Plan.TAG_INFERRED, "", "", 37.0, 0));
        assertEquals(34, held.pressureKpa, "no maximum: the usual top, as before");
    }

    @Test void theLengthTopIsHeldToTheLoadLimitInTheLengthCylinder() {
        Model m = owner();
        assertTrue(Scale.climbs(m, Plan.TRACK_LENGTH, Plan.L3, 7));
        assertEquals(41, Scale.climbTopWholeKpa(m, Plan.TRACK_LENGTH, Plan.L3, 7));
        assertEquals(41 - 0.14, Scale.planTopKpa(m, Plan.TRACK_LENGTH, Plan.L3, 7), 1e-9);
        m.rxLengthMaxKpa = 48;
        int top = Scale.climbTopWholeKpa(m, Plan.TRACK_LENGTH, Plan.L3, 7);
        assertTrue(Traction.loadLbAtBore(top, 4.5) <= Scale.LOAD_HARD_MAX_LB + 1e-9,
            "the pull at the top stays under 15 lb");
        assertTrue(Traction.loadLbAtBore(top + 1, 4.5) > Scale.LOAD_HARD_MAX_LB);
    }

    private static Plan.Inputs lengthAt(double kpa, double loadLb, double climbTop) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.pressureKpa = kpa;
        in.ceilKpa = 43;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.loadLb = loadLb;
        in.strainSets = 2;
        in.trainingWeeksAtPressure = 1;          // a month since the last nudge
        in.firstDeloadPending = false;
        in.climbTopKpa = climbTop;
        return in;
    }

    @Test void theLengthPressureClimbsAndThePullFollows() {
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(lengthAt(33.9, 12.1, Double.NaN)).action,
            "no maximum above the top: no whole step fits under 10 inHg, as before");

        Plan.Decision d = Plan.evaluate(lengthAt(33.9, 12.1, 41 - 0.14));
        assertEquals(Plan.ACTION_RAISE_LOAD, d.action, "a load step, taken on the card");
        assertEquals(33.9 + Plan.STEP_HG_KPA, d.pressureKpa, 1e-9);
        // t10 R-43 (C12 + fix f): THE PULL FOLLOWS ONE STEP AT A TIME - toward the load the
        // whole-kPa pressure makes at the length cylinder's bore, by one pressure step's worth
        // there (or half a pound), never in one jump.
        double step = Math.max(Plan.LENGTH_LOAD_STEP_LB,
            Traction.loadLbAtBore(33.9 + Plan.STEP_HG_KPA, 4.5) - Traction.loadLbAtBore(33.9, 4.5));
        assertEquals(Math.min(Traction.loadLbAtBore(37, 4.5), 12.1 + step), d.loadLb, 1e-9,
            "the pull is the length pressure at the length cylinder's bore, a step at a time");
        assertTrue(d.loadLb > Traction.LOAD_MAX_LB, "past the usual 12 lb - only by the climb");

        double second = 33.9 + 2 * Plan.STEP_HG_KPA;
        Plan.Decision last = Plan.evaluate(lengthAt(d.pressureKpa, d.loadLb, 41 - 0.14));
        assertEquals(second, last.pressureKpa, 1e-9);
        assertEquals(41, Math.round(last.pressureKpa + 0.14),
            "the second step runs at the owner's -12.1 inHg");
        assertEquals(d.loadLb + step, last.loadLb, 1e-9, "one step more, not the whole way");
        assertTrue(last.loadLb < Traction.loadLbAtBore(41, 4.5));
        assertTrue(Scale.commandedLoadLb(last.loadLb, 0.14, 4.5, false, 7)
                   <= Scale.LOAD_HARD_MAX_LB + 1e-9, "and the pull stays under 15 lb");

        // At the maximum as the wire carries it the pressure holds - and the pull, still a
        // step behind it, catches up the next month (the pressure stays where it is).
        Plan.Decision catchUp = Plan.evaluate(lengthAt(second, last.loadLb, 41 - 0.14));
        assertEquals(Plan.ACTION_RAISE_LOAD, catchUp.action, catchUp.rule);
        assertEquals(second, catchUp.pressureKpa, 1e-9, "the pressure does not move");
        assertEquals(Traction.loadLbAtBore(41, 4.5), catchUp.loadLb, 1e-9);
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(lengthAt(second, catchUp.loadLb, 41 - 0.14))
            .action, "at the maximum, the pull caught up: hold");
        Plan.Inputs soon = lengthAt(33.9, 12.1, 41 - 0.14);
        soon.trainingWeeksAtPressure = 0;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(soon).action, "the track's own monthly step");

        Plan.Inputs noTube = lengthAt(33.9, 12.1, 41 - 0.14);
        noTube.lengthTube = false;
        Plan.Decision coda = Plan.evaluate(noTube);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, coda.action,
            "expansion only: the pressure climbs, there is no pull to follow it");
    }

    @Test void theLoadSteppedPastByTheClimbDoesNotStallIt() {
        // Twelve sets, low strain, the load past the 12 lb cap: the ladder's own load rung
        // holds, and the climb still runs.
        Plan.Inputs in = lengthAt(33.9 + Plan.STEP_HG_KPA, 13.4, 41 - 0.14);
        in.strainSets = Plan.LENGTH_STRAIN_SETS_MAX;
        in.strainPct = 1.5;                       // under option D's 2 %, never reached
        in.strainMissDays = Plan.LENGTH_MISS_DEBOUNCE_DAYS;
        assertEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(in).action);
        in.climbTopKpa = Double.NaN;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(in).action, "without the climb, as before");
    }

    @Test void theSetupSaysItOnceInPlainWords() {
        String g = Scale.climbWords(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7, 0.0, false, 37, 43, 0);
        assertTrue(g.contains("usual top of " + Model.Fmt.p(34)), g);
        assertTrue(g.contains("until it reaches " + Model.Fmt.p(37)), g);
        assertTrue(g.contains("every " + Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS), g);
        String l = Scale.climbWords(Plan.TRACK_LENGTH, Plan.L3, 7, 0.14, false, 41, 43, 4.5);
        assertTrue(l.contains("until it reaches " + Model.Fmt.p(41)), l);
        assertTrue(l.contains("pass the usual " + Traction.settingLb(12.0)), l);
        assertTrue(l.contains(Traction.settingLb(15.0)), l);
        assertEquals("", Scale.climbWords(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 7, 0.0, false,
                                          34, 43, 0), "at the top: nothing to say");
        assertEquals("", Scale.climbWords(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 0, 0.0, true,
                                          37, 43, 0), "a new person's first month");
    }
}
