package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * THE LOAD UNIT (owner, 2026-09-30): pounds or kilos, chosen beside the pressure and size
 * units, defaulting with the size unit (inches -> lb, cm -> kg). Everything stays in pounds
 * underneath; every user-visible load is drawn by one formatter (Model.Fmt#load).
 */
class LoadUnitTest {

    @AfterEach void units() {
        Model.Fmt.unit = Model.Fmt.U_INHG;
        Model.Fmt.sizeUnit = Model.Fmt.S_CM;
        Model.Fmt.loadUnit = Model.Fmt.L_LB;
    }

    @Test void poundsAsTheyAlwaysWere() {
        Model.Fmt.loadUnit = Model.Fmt.L_LB;
        assertEquals("11.8 lb", Traction.settingLb(11.8));
        assertEquals("12 lb", Traction.settingLb(12.0));
        assertEquals("up to 2.5 lb", Traction.boundLb(2.5));
        assertEquals("≤11.8 lb", Traction.boundLbShort(11.8));
        assertEquals(0.5, Model.Fmt.loadStepLb(), 1e-12);
    }

    @Test void kilosThroughTheSameFormatter() {
        Model.Fmt.loadUnit = Model.Fmt.L_KG;
        assertEquals("5.4 kg", Traction.settingLb(11.8));
        assertEquals("5.4 kg", Traction.settingLb(Traction.LOAD_MAX_LB), "the usual 12 lb");
        assertEquals("6.8 kg", Traction.settingLb(Scale.LOAD_HARD_MAX_LB), "the hard 15 lb");
        assertEquals("up to 1.1 kg", Traction.boundLb(2.5));
        assertEquals("≤5.4 kg", Traction.boundLbShort(11.8));
        assertEquals("1.0 kg", Model.Fmt.load(Model.Fmt.LB_PER_KG),
            "always one decimal in kilos (the device walk, E-M3)");
        assertEquals("5.0 kg", Model.Fmt.load(5.0 * Model.Fmt.LB_PER_KG));
        Model.Fmt.loadUnit = Model.Fmt.L_LB;
        assertEquals("12 lb", Model.Fmt.load(12.0), "pounds as they were");
        Model.Fmt.loadUnit = Model.Fmt.L_KG;
        assertEquals(0.5 * Model.Fmt.LB_PER_KG, Model.Fmt.loadStepLb(), 1e-12,
            "the stepper moves half a kilo");
    }

    @Test void theWarningsAndTheLimitsSpeakTheUnit() {
        Model.Fmt.loadUnit = Model.Fmt.L_KG;
        String w = Scale.loadWarning(13.0);
        assertTrue(w.contains("5.9 kg") && w.contains("5.4 kg") && w.contains("6.8 kg"), w);
        assertTrue(!w.contains(Model.Fmt.L_LB), w);
        String r = RoutineOffset.warning(Plan.TRACK_LENGTH, 0, 20, 0, false);
        assertTrue(r != null && r.contains("6.8 kg") && !r.contains(Model.Fmt.L_LB), r);
    }

    @Test void aRoutineNameStatesTheLoadInTheUnit() {
        Model.Fmt.loadUnit = Model.Fmt.L_KG;
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_LENGTH, Plan.L2, 3, 300, 30, 30, false, 0.0);
        String name = Mint.tractionName(rx, 11.8, 4.5, 43, false);
        assertTrue(name.endsWith(" kg") && name.contains("traction @ up to "), name);
    }

    @Test void theDefaultFollowsTheSizeUnit() {
        assertEquals(Model.Fmt.L_LB, Model.Fmt.defaultLoadUnit(Model.Fmt.S_IN));
        assertEquals(Model.Fmt.L_KG, Model.Fmt.defaultLoadUnit(Model.Fmt.S_CM));
        assertEquals(Model.Fmt.L_KG, new Model().loadUnit, "a new phone measures in cm");
        String base = "{\"ceil\":43,\"unit\":\"inHg\",\"sets\":[],\"routines\":[]";
        assertEquals("lb", Model.fromJson(base + ",\"sizeUnit\":\"in\"}").loadUnit);
        assertEquals("kg", Model.fromJson(base + ",\"sizeUnit\":\"cm\"}").loadUnit);
        assertEquals("kg", Model.fromJson(base + "}").loadUnit);
        assertEquals("lb", Model.fromJson(base + ",\"sizeUnit\":\"cm\",\"loadUnit\":\"lb\"}")
            .loadUnit, "a chosen unit is kept whatever the size unit");
        Model m = new Model();
        m.sizeUnit = "in";
        m.loadUnit = "stone";
        m.clampAll();
        assertEquals("lb", m.loadUnit, "a garbage unit gets the size unit's");
    }

    /** The owner's profile: a 4.5 cm length cylinder at -9.7 inHg (33 kPa on the wire). */
    @Test void theOwnersStepThreeInKilosAndPounds() {
        double lb = TrainerOnboard.linkedLoadLb(33, 4.5);
        assertEquals("−9.7 inHg", Model.Fmt.p(33));
        Model.Fmt.loadUnit = Model.Fmt.L_LB;
        assertEquals("11.8 lb", Traction.settingLb(lb));
        assertEquals("12 lb", Traction.settingLb(
            TrainerOnboard.bumpLoadLb(lb, 1, Model.Fmt.loadStepLb())));
        Model.Fmt.loadUnit = Model.Fmt.L_KG;
        assertEquals("5.4 kg", Traction.settingLb(lb));
        double up = TrainerOnboard.bumpLoadLb(lb, 1, Model.Fmt.loadStepLb());
        assertEquals("5.5 kg", Traction.settingLb(up), "up a half kilo, onto the grid");
        assertEquals("5.0 kg", Traction.settingLb(
            TrainerOnboard.bumpLoadLb(lb, -1, Model.Fmt.loadStepLb())), "and down");
        // And the pressure that load is held as, back in the pressure field.
        assertEquals("−10.0 inHg", Model.Fmt.p(TrainerOnboard.kpaForLinkedLoad(up, 4.5)));
    }
}
