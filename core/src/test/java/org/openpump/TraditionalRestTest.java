package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import org.junit.jupiter.api.Test;

/**
 * t10 R-08 (R3) - TRADITIONAL RESTS ARE 30 s at every level, between holds and after the fatigue
 * block; the Rests picker still scales them (short max(30, 20) = 30 s, long 45 s). Interval
 * and hybrid rests are unchanged. The saved traditional rest setting is kept and no longer
 * read. The numbers are the editor's (t10/acc.js and the same model's traditional builds).
 */
class TraditionalRestTest {

    @Test void theRuleAtEveryLevel() {
        for (int lv = Plan.L1; lv <= Plan.L4; lv++) {
            assertEquals(30, Mint.restSecFor(Plan.TRACK_GIRTH_TRADITIONAL, lv), "L" + lv);
            assertEquals(Mint.REST_SEC, Mint.restSecFor(Plan.TRACK_GIRTH_INTERVAL, lv),
                         "interval unchanged at L" + lv);
        }
        Model m = owner();
        m.rxRestSecTrad = 240;                   // kept, no longer read
        assertEquals(30, m.restSecFor(Plan.TRACK_GIRTH_TRADITIONAL));
        assertEquals(180, m.restSecFor(Plan.TRACK_GIRTH_INTERVAL));
        m.programFor(Plan.TRACK_GIRTH_TRADITIONAL).rest = Model.Program.REST_SHORT;
        assertEquals(30, m.restSecFor(Plan.TRACK_GIRTH_TRADITIONAL), "short: max(30, 20)");
        m.programFor(Plan.TRACK_GIRTH_TRADITIONAL).rest = Model.Program.REST_LONG;
        assertEquals(45, m.restSecFor(Plan.TRACK_GIRTH_TRADITIONAL), "long: half again");
    }

    @Test void theSavedSettingMayBeThirty() {
        Model m = owner();
        m.rxRestSecTrad = 30;
        m.clampRxShape();
        assertEquals(30, m.rxRestSecTrad, "the clamp allows the R3 rest");
        m.rxRestSecTrad = 5;
        m.clampRxShape();
        assertEquals(30, m.rxRestSecTrad);
    }

    @Test void levelOneStartThreeHolds() {
        Model m = owner();
        Model.Routine r = build(m, trad(Plan.L1, 3, 17), day(m));
        assertEquals(21.25, minutes(m, r), 1e-9);
        int rests = 0;
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st.rest && !st.manual) { assertEquals(30, st.restSec); rests++; }
        }
        assertEquals(2, rests);
    }

    @Test void levelFourSevenHoldsRestThirtyAfterTheFatigueBlockToo() {
        Model m = owner();
        Model.Routine r = build(m, trad(Plan.L4, 7, 37), day(m));
        // EXPECTATION CHANGED (R11-5): 52.42 before - the fatigue block's 30 s holds add 25 s.
        assertEquals(52.83, minutes(m, r), 1e-9);
        int fat = stage(r, "fatigue");
        assertTrue(r.stages.get(fat + 1).rest);
        assertEquals(30, r.stages.get(fat + 1).restSec, "after the fatigue block");
    }

    @Test void levelTwoTheCarryReachesTheFiveMinuteHolds() {
        Model m = owner();
        Model.Routine r = build(m, trad(Plan.L2, 6, 30), day(m));
        assertEquals(list(28, 29, 30, 30, 30, 30), workPulls(m, r),
                     "the P2 carry, hold by hold, in the five-minute holds");
        assertEquals(38.00, minutes(m, r), 1e-9);
        for (Model.Preset p : m.plan(r))
            assertTrue(p.rest || p.uh <= Proto.WIRE_HOLD_MAX, "every hold fits the wire");
    }
}
