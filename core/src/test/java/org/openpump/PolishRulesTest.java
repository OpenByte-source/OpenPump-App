package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE PURE HALF OF THE CARD, NUMBER AND SETTINGS POLISH.
 *
 * Decisions the screens make that need no device to check. Each is a rule the owner
 * approved from a mock, so each is pinned here rather than left to a view builder no test
 * can run.
 */
class PolishRulesTest {

    @Test
    void onlyAWarningOrAFaultEarnsTheColouredEdge() {
        assertTrue(Look.earnsSpine(Look.CRITICAL), "red: unsafe or failed");
        assertTrue(Look.earnsSpine(Look.COMMANDED), "amber: the pump may be under pressure");
        assertFalse(Look.earnsSpine(Look.ACCENT), "lime is action, not a warning");
        assertFalse(Look.earnsSpine(Look.BODY), "violet is a domain, not a warning");
        assertFalse(Look.earnsSpine(Look.SAFE), "a confirmed vent is not a warning");
        assertFalse(Look.earnsSpine(Look.TEXT), "a neutral card");
        assertFalse(Look.earnsSpine(Look.DIM), "a neutral card");
        assertFalse(Look.earnsSpine(0), "no tone at all");
    }

    @Test
    void theStopLabelIsReadableOnItsRed() {
        assertTrue(Look.meetsAA(Look.ON_RED, Look.CRITICAL, false),
            "the run screen's STOP label clears 4.5:1 on the red");
        assertFalse(Look.meetsAA(Look.TEXT, Look.CRITICAL, false),
            "which the white label it replaced did not");
    }

    @Test
    void aGateBarFillsByMagnitudeSoNegativePressureStillCounts() {
        assertEquals(0.625, Look.barFill(-5.0, -8.0), 1e-9, "−5.0 of −8.0 inHg is 62%");
        assertEquals(0.03, Look.barFill(0.6, 20.0), 1e-9, "0.6 of 20.0 min");
        assertEquals(0.0, Look.barFill(0, 2), 1e-9, "no weeks held yet");
        assertEquals(1.0, Look.barFill(3, 2), 1e-9, "past the target is full, never more");
        assertEquals(1.0, Look.barFill(0, 0), 1e-9, "a target of nothing is already met");
        assertEquals(0.0, Look.barFill(Double.NaN, 8), 1e-9, "no figure fills nothing");
    }

    @Test
    void thePlanBlueIsTheFirstBlockBlueNotASeventh() {
        assertEquals(Look.BLOCKS[0], Look.PLAN_BLUE);
        assertEquals(Look.PLAN_BLUE & 0x00FFFFFF, Look.BLUE_DIM & 0x00FFFFFF,
            "BLUE_DIM is this blue diluted");
    }

    @Test
    void aRangeSaysItsUnitOnceWhenBothEndsShareIt() {
        assertEquals("−2.1 to −16.8 inHg", Say.rangeWords("−2.1 inHg", "−16.8 inHg"));
        assertEquals("7 to 57 kPa", Say.rangeWords("7 kPa", "57 kPa"));
        assertEquals("5 s to 2 min", Say.rangeWords("5 s", "2 min"),
            "different units stay whole");
        assertEquals("3 to 12", Say.rangeWords("3", "12"), "no unit to share");
    }

    @Test
    void aJumpChipIsOneShortLineAndNamesItsCategory() {
        assertEquals("Schedule", Say.jumpLabel("Training schedule"));
        assertEquals("Run behaviour", Say.jumpLabel("How a run behaves"));
        assertEquals("Device", Say.jumpLabel("Device & developer"));
        assertEquals("Pump", Say.jumpLabel("Pump"));
        assertEquals("Session", Say.jumpLabel("Session"));
        assertEquals("Measurements", Say.jumpLabel("Measurements"));
        assertEquals("App lock", Say.jumpLabel("App lock"));
        assertEquals("", Say.jumpLabel(null));
    }
}
