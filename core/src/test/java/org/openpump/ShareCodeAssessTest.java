package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONException;
import org.junit.jupiter.api.Test;

/**
 * M4 - A SHARE CODE IMPORTS THE TISSUE RESPONSE TEST AS IT WAS MADE.
 *
 * The owner's decision: the test is off by default for NEW routines only, and existing things
 * keep their setting. A share code is an existing routine, so its on/off comes in as the code
 * says, whenever it was made. For a short while (84fddb5) new v3 codes wrote packed version 2
 * and version-1 codes came in off; version 1 is written again, so a code works with every
 * build, and a version-2 code from that window still decodes, its bit kept.
 */
class ShareCodeAssessTest {

    /** A real v3 code from the build before the change (packed version 1): a routine named
     *  "Old code" whose test was on, 17 kPa, 55 %, 60 s, after only. */
    private static final String OLD_V3 = "PD3:EhPbGQgY29kZRRbniIiu3uTXADwIiuwuTaAwQoCHgeA=";

    private static Model source(boolean on) {
        Model src = new Model();
        src.ceilKpa = 40;
        src.sets.add(Model.Set.fixed("ow", "Warm", 12, 4, 40, 8, 60, 240));
        Model.Routine r = new Model.Routine();
        r.id = "src"; r.name = "Old code";
        r.assess.on = on; r.assess.kpa = 17; r.assess.sp = 55; r.assess.dur = 60;
        r.assess.when = Model.Assess.WHEN_AFTER;
        r.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "ow" }));
        src.routines.add(r);
        return src;
    }

    private static String withVersion(String code, int version) {
        byte[] b = java.util.Base64.getDecoder().decode(code.substring(4));
        b[0] = (byte) ((b[0] & 0x0F) | (version << 4));
        return "PD3:" + Model.Routine.b64EncodeForTest(b);
    }

    @Test void aCodeFromBeforeTheChangeComesInAsItWasMade() throws Exception {
        Model.Routine got = Model.Routine.fromShareCode(OLD_V3).routine;
        assertTrue(got.assess.on, "the code says on, so it comes in on");
        assertEquals(17, got.assess.kpa);
        assertEquals(55, got.assess.sp);
        assertEquals(60, got.assess.dur);
        assertEquals(Model.Assess.WHEN_AFTER, got.assess.when);
        assertEquals("Old code", got.name, "and the rest of an old code decodes as it always did");
        assertEquals(1, got.stages.size());
    }

    @Test void aCodeMadeNowKeepsWhatItCarries() throws Exception {
        Model on = source(true);
        assertTrue(Model.Routine.fromShareCode(on.routines.get(0).toShareCode(on))
                   .routine.assess.on);
        Model off = source(false);
        assertFalse(Model.Routine.fromShareCode(off.routines.get(0).toShareCode(off))
                    .routine.assess.on);
    }

    @Test void aCodeMadeNowIsTheSameStreamAsBefore() throws Exception {
        Model on = source(true);
        assertEquals(OLD_V3, on.routines.get(0).toShareCode(on),
            "packed version 1 again, byte for byte - a code made now imports on every build");
    }

    @Test void aVersionTwoCodeFromTheInterimBuildStillDecodes() throws Exception {
        String v2 = withVersion(OLD_V3, 2);
        Model.Routine got = Model.Routine.fromShareCode(v2).routine;
        assertTrue(got.assess.on, "its bit is kept");
        assertEquals(17, got.assess.kpa);
    }

    @Test void anUnknownPackedVersionIsStillRefused() {
        String v3 = withVersion(OLD_V3, 3);
        assertThrows(JSONException.class, () -> Model.Routine.fromShareCode(v3));
    }
}
