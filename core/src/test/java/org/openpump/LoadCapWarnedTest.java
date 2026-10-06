package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE PLAN'S LOAD CAP FOR THE MONTH IS WARNED ONCE FOR SOMEBODY NOT NEW (plan simulator t8,
 * finding 1; the owner's rule: programme caps are warned once, your call - a new person's
 * first-month limits stay hard). A "0 months, not new" setup wrote its length answer as the
 * load - 5 to 10.4 lb from the first day - past the plan's month-0 cap of 4 lb, and setup
 * warned only past 12 lb. Scale#usualLoadCapLb, #pastUsualLoad(lb, month), #loadWarning(lb,
 * month) and the length offset's warning (#offsetWarning) now read the month's cap.
 */
class LoadCapWarnedTest {

    private String loadBefore, unitBefore;
    @BeforeEach void kg() {
        loadBefore = Model.Fmt.loadUnit; unitBefore = Model.Fmt.unit;
        Model.Fmt.loadUnit = Model.Fmt.L_KG; Model.Fmt.unit = Model.Fmt.U_INHG;
    }
    @AfterEach void restore() { Model.Fmt.loadUnit = loadBefore; Model.Fmt.unit = unitBefore; }

    @Test void theUsualCapIsTheMonths() {
        assertEquals(4.0, Scale.usualLoadCapLb(0), 1e-9, "the plan's first month");
        assertEquals(12.0, Scale.usualLoadCapLb(1), 1e-9);
        assertEquals(12.0, Scale.usualLoadCapLb(7), 1e-9);
        assertTrue(Scale.pastUsualLoad(5.0, 0));
        assertFalse(Scale.pastUsualLoad(4.0, 0), "at the cap is not past it");
        assertFalse(Scale.pastUsualLoad(5.0, 1));
        assertTrue(Scale.pastUsualLoad(12.2, 1));
    }

    @Test void notNewItIsWarnedNotHeld_newItIsHeld() {
        // The simulator's case: 0 months, not new, an answer of 10.4 lb.
        double saved = Scale.setupLoadLb(false, true, 10.4, 2.5);
        assertEquals(10.4, saved, 1e-9, "not new: the answer is the person's call");
        assertEquals(Scale.LOAD_HARD_MAX_LB, Scale.loadHardMaxLb(false, 0), 1e-9, "not hard");
        assertTrue(Scale.pastUsualLoad(saved, 0), "...so it is warned");
        assertEquals(Plan.LENGTH_LOAD_M1_MAX_LB, Scale.loadHardMaxLb(true, 0), 1e-9,
            "new to pumping, first month: hard, as it was");
    }

    @Test void theWarningNamesTheMonthsCapInTheLoadUnit() {
        String w = Scale.loadWarning(10.4, 0);
        assertTrue(w.contains("4.7 kg") && w.contains("1.8 kg") && w.contains("first month")
            && w.contains("6.8 kg"), w);
        assertFalse(w.contains(Model.Fmt.L_LB), w);
        String w1 = Scale.loadWarning(13.0, 3);
        assertTrue(w1.contains("5.4 kg") && w1.contains("own steps stop at"), w1);
        assertEquals(Scale.loadWarning(13.0), w1, "the twelve-pound words, as before");
    }

    @Test void theLengthOffsetsWarningNamesTheMonthsCapToo() {
        String m0 = Scale.offsetWarning(Plan.TRACK_LENGTH, Plan.L1, 0, 3.4, 41, 4.5, false);
        assertTrue(m0.contains("usual 1.8 kg"), m0);
        String m3 = Scale.offsetWarning(Plan.TRACK_LENGTH, Plan.L2, 3, 3.4, 41, 4.5, false);
        assertTrue(m3.contains("usual 5.4 kg"), m3);
    }
}
