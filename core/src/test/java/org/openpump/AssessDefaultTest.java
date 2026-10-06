package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * M4 - THE TISSUE RESPONSE TEST IS OFF BY DEFAULT FOR NEW ROUTINES ONLY (the owner's
 * decision on item 13).
 *
 * A routine made from now on - in Library, by the Trainer, a manual run, the hardware
 * self-test - starts with the test off: two extra pulls and about 1:30 a session are the
 * person's to choose. A routine that already exists keeps whatever it had: a saved file, a
 * backup and a share code all load exactly as they were made, and a routine saved before the
 * test existed loads as it always did.
 */
class AssessDefaultTest {

    @Test void aNewRoutineRunsNoTest() {
        Model.Routine r = new Model.Routine();
        assertFalse(r.assess.on);
        assertFalse(Tau.runsBefore(r));
        assertFalse(Tau.runsAfter(r));
        assertEquals(0, Tau.assessDur(r), "and adds nothing to its duration");
        assertEquals(0, Tau.commandedKpa(r, 40), "and commands no pull");
        assertFalse(Model.Assess.fresh(40).on, "a trainer mint's test is off too");
    }

    @Test void aRoutineSavedBeforeTheChangeKeepsItsSetting() throws Exception {
        Model.Assess on = Model.Assess.fromJson(new JSONObject(
            "{\"on\":true,\"kpa\":17,\"sp\":55,\"dur\":60,\"when\":\"after\"}"));
        assertTrue(on.on, "saved on: loads on - an existing routine keeps its setting");
        assertEquals(17, on.kpa);
        assertEquals(55, on.sp);
        assertEquals(60, on.dur);
        assertEquals(Model.Assess.WHEN_AFTER, on.when);
        assertFalse(Model.Assess.fromJson(new JSONObject(
            "{\"on\":false,\"kpa\":20,\"sp\":60,\"dur\":45,\"when\":\"both\"}")).on,
            "saved off: loads off");
        assertTrue(Model.Assess.fromJson(null).on,
            "no block at all (saved before the test existed): loads as it always did, on");
        assertTrue(Model.Assess.fromJson(new JSONObject("{\"kpa\":20}")).on,
            "a block with no \"on\" key: as it always did");
    }

    @Test void aChoiceMadeOnThisBuildIsKept() throws Exception {
        Model.Assess on = new Model.Assess();
        on.on = true;
        assertTrue(Model.Assess.fromJson(on.toJson()).on);
        Model.Assess off = new Model.Assess();
        assertFalse(Model.Assess.fromJson(off.toJson()).on,
            "a new routine saved off reloads off - \"on\" is always written");
    }

    @Test void aShareCodeCarriesTheSendersSwitch() throws Exception {
        Model src = Model.seed();
        Model.Routine r = src.routines.get(0);
        r.assess.on = true;
        Model.Routine got = Model.Routine.fromShareCode(r.toShareCode(src)).routine;
        assertTrue(got.assess.on, "a v3 code carries the switch as a bit");
        r.assess.on = false;
        assertFalse(Model.Routine.fromShareCode(r.toShareCode(src)).routine.assess.on);
        r.assess.on = true;
        assertTrue(Model.Routine.fromShareCode(r.toShareCodeV2(src)).routine.assess.on,
            "a v2 code carries it in its JSON");
    }
}
