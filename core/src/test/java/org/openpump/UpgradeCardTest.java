package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 R-61 - "THE PLAN CHANGED — RUN THE TRAINER SETUP AGAIN": one card for a plan set up
 * before this version. "Later" puts it off to the next app start, at most three times; after
 * that it is a row in "Where I am"; a setup run again answers it.
 */
class UpgradeCardTest {

    private static final String OLD_ENROLLED =
        "{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],\"trainerOn\":true,"
        + "\"trainerMonths\":7,\"trainerLengthOn\":true,"
        + "\"trainerGirth\":[{\"level\":3,\"week\":0,\"pressureKpa\":\"30.0\"}],"
        + "\"trainerLength\":[{\"level\":2,\"week\":0,\"pressureKpa\":\"34.0\"}]}";

    @Test void theWordsAreTheDecidedOnes() {
        // Polish TR-2 / NEW-1 / F4.
        assertEquals("The plan has new choices", PlanCards.UPGRADE_TITLE);
        assertEquals("Your values are kept. The setup asks three new questions: Long training "
            + "days, Length load after month 3 and When girth follows length.",
            PlanCards.UPGRADE_TEXT);
        assertEquals(PlanCards.LEN_LOAD_ROW, PlanCards.LEN_LOAD_ROW_NAME);
        assertEquals(PlanCards.R4_ROW, PlanCards.R4_ROW_NAME);
        assertEquals("Answer 3 new questions", PlanCards.UPGRADE_ANSWER);
        assertEquals("Run the full setup", PlanCards.UPGRADE_RUN);
        assertEquals("Later", PlanCards.UPGRADE_LATER);
        assertEquals("The plan has new choices \u00b7 Answer 3 new questions",
            PlanCards.UPGRADE_NAV);
    }

    /** EMU9 M3: the card counts the questions the short path really asks. */
    @Test void theCardSaysTheRealNumberOfQuestions() {
        Model girthOnly = new Model();
        girthOnly.trainerEnrolled = true; girthOnly.trainerGirthOn = true;
        girthOnly.trainerLengthOn = false;
        assertEquals(0, PlanCards.newQuestionCount(girthOnly));
        assertEquals("See what changed", PlanCards.upgradeAnswer(girthOnly));
        assertTrue(PlanCards.upgradeShort(girthOnly).contains("None of the new questions applies"));

        Model both = new Model();
        both.trainerEnrolled = true; both.trainerGirthOn = true; both.trainerLengthOn = true;
        int n = PlanCards.newQuestionCount(both);
        assertEquals(both.lengthPulls() ? 3 : 2, n, "Length load only with a length cylinder");
        assertEquals("Answer " + n + " new questions", PlanCards.upgradeAnswer(both));
        assertEquals(PlanCards.UPGRADE_TITLE + " · Answer " + n + " new questions",
            PlanCards.upgradeNav(both));
        if (!both.lengthPulls()) {
            assertEquals("Your values are kept. Two new questions apply to the tracks you run.",
                PlanCards.upgradeShort(both));
            assertEquals("Your values are kept. The new questions that apply: Long training days "
                + "and When girth follows length.", PlanCards.upgradeText(both));
        }
    }

    @Test void anUpgraderSeesTheCardOnceAndTheSetupAnswersIt() {
        Model m = Model.fromJson(OLD_ENROLLED);
        assertTrue(m.planChangedT10Due());
        assertTrue(PlanCards.upgradeCardShown(m, false), "the card shows");
        assertFalse(PlanCards.upgradeRowShown(m, false), "not twice on one page");
        m.answerPlanT10Setup();                    // the full setup's Confirm
        assertFalse(m.planChangedT10Due());
        assertFalse(PlanCards.upgradeCardShown(m, false));
        assertFalse(PlanCards.upgradeRowShown(m, false));
        assertFalse(Model.fromJson(m.toJson()).planChangedT10Due(), "and stays answered");
    }

    @Test void laterPutsItOffThreeTimesThenItIsARow() {
        Model m = Model.fromJson(OLD_ENROLLED);
        for (int i = 0; i < Model.PLAN_T10_LATER_MAX; i++) {
            assertTrue(PlanCards.upgradeCardShown(m, false), "start " + (i + 1));
            m.answerPlanT10Later();
            assertFalse(PlanCards.upgradeCardShown(m, true), "hidden until the next start");
            assertTrue(PlanCards.upgradeRowShown(m, true), "...and in Where I am meanwhile");
            m = Model.fromJson(m.toJson());        // the next app start
        }
        assertFalse(PlanCards.upgradeCardShown(m, false), "three Laters: no more card");
        assertTrue(PlanCards.upgradeRowShown(m, false), "a row in Where I am");
    }

    @Test void aNewSetupAndAPlainFileOweNothing() {
        Model fresh = new Model();
        fresh.trainerEnrolled = true;
        assertFalse(PlanCards.upgradeCardShown(fresh, false));
        assertFalse(PlanCards.upgradeRowShown(fresh, false));
        Model plain = Model.fromJson("{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],"
            + "\"routines\":[]}");
        assertFalse(PlanCards.upgradeRowShown(plain, false));
    }
}
