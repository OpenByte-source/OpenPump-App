package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * t10 device walk M6 - TWELVE POUNDS IS NOT THE PLAN'S CAP. Setup step 4 said "The 5.4 kg cap
 * is what limits the load here" and the length detail "Ceiling: 5.4 kg · the plan's cap",
 * next to a proposed 5.5 kg start and pulls reaching 6.6 kg. The plan's limit is 15 lb
 * (R-43, A9); passing 12 lb before month 12 is warned once. In the person's load unit.
 */
class LoadLimitWordsTest {

    @AfterEach void lb() { Model.Fmt.loadUnit = Model.Fmt.L_LB; }

    @Test void theOwnersLimitIsFifteenNotTwelve() {
        Model.Fmt.loadUnit = Model.Fmt.L_KG;   // kg, as the owner's profile
        // Owner: length cylinder 4.5 cm, ceiling 12.7 inHg (43 kPa), month 7, not new.
        assertEquals(15.0, Scale.loadLimitLb(false, 7, 4.5, 43), 1e-9);
        assertEquals("the plan's limit", Scale.loadLimitWhy(false, 7, 4.5, 43));
        String line = Scale.loadLimitLine(false, 7, 4.5, 43);
        assertTrue(line.contains("limit of 6.8 kg"), line);
        assertTrue(line.contains("Past 5.4 kg before month 12 is warned once"), line);
        assertFalse(line.contains("cap"), line);
    }

    @Test void aLowCeilingIsTheDevice() {
        assertTrue(Scale.loadLimitIsDevice(false, 7, 4.5, 20));
        assertEquals("your device ceiling", Scale.loadLimitWhy(false, 7, 4.5, 20));
        assertTrue(Scale.loadLimitLb(false, 7, 4.5, 20) < 15.0);
        assertTrue(Scale.loadLimitLine(false, 7, 4.5, 20)
            .startsWith("The pump’s ceiling is what limits the load here, before the "
                + "plan’s 15 lb limit."));
    }

    @Test void aNewPersonsFirstMonthIsHardAtFour() {
        assertEquals(4.0, Scale.loadLimitLb(true, 0, 4.5, 43), 1e-9);
        assertEquals("the plan's first-month limit", Scale.loadLimitWhy(true, 0, 4.5, 43));
        assertEquals("", Scale.loadUsualNote(true, 0), "four is hard: nothing to warn");
        assertEquals("Past 4 lb in your first month is warned once — your call.",
            Scale.loadUsualNote(false, 0));
        assertEquals("", Scale.loadUsualNote(false, 12), "A9 warns before month 12 only");
    }
}
