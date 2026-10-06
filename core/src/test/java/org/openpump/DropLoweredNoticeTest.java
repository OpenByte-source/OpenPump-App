package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * t10 R-62 - A SAVED DROP THE PULL LOWERS IS SAID, ONCE PER LOWERED VALUE: the build holds
 * the drop between holds 1.0 inHg under the pull (Mint#dropFor), and that used to happen
 * without a word.
 */
class DropLoweredNoticeTest {

    private String unitWas;

    @BeforeEach void inHg() { unitWas = Model.Fmt.unit; Model.Fmt.unit = "inHg"; }

    @AfterEach void restore() { Model.Fmt.unit = unitWas; }

    @Test void aDropOfEightUnderAThirtyKpaPullIsLoweredAndSaidOnce() {
        Model m = new Model();
        m.rxDropKpa = 27;                                     // 8.0 inHg
        assertEquals(26, Mint.dropFor(27, 30), "the build uses 26");
        assertEquals(26, PlanCards.droppedTo(27, 30));
        String said = PlanCards.dropLoweredNotice(m, 30);
        assertEquals("Your drop between holds was lowered to −7.7 inHg: it can be at "
            + "most 1.0 inHg under your pull.", said);
        assertEquals(26, m.dropLoweredSaidKpa, "recorded as said");
        assertEquals("", PlanCards.dropLoweredNotice(m, 30), "a second build says nothing");
        assertEquals(26, Model.fromJson(m.toJson()).dropLoweredSaidKpa, "and it is saved");
    }

    @Test void aNewLoweredValueIsSaidAgainAndAnUnloweredDropNever() {
        Model m = new Model();
        m.rxDropKpa = 27;
        PlanCards.dropLoweredNotice(m, 30);
        assertEquals(25, PlanCards.droppedTo(27, 29));
        assertEquals(PlanCards.dropLoweredLine(25), PlanCards.dropLoweredNotice(m, 29));
        assertEquals(-1, PlanCards.droppedTo(27, 40), "far under the pull: kept");
        assertEquals("", PlanCards.dropLoweredNotice(m, 40));
        assertEquals(-1, PlanCards.droppedTo(8, 12), "at the floor nothing is lowered");
    }
}
