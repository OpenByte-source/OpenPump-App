package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE POLISH ROUND'S SHARED HELPERS (t10 polish, P0): the pure halves of what every lane
 * builds on - the label ink on a filled button (SYS-1), one sentence (SYS-6), the a/an rule
 * (SYS-6), a routine's display title (TD-4 / LB-4), one shape formatter and the percent
 * (SYS-13), the destructive label (SYS-12), the value row's snack (SYS-3) and the one-card-
 * at-a-time queue (TR-1). The Android halves are pinned by WiringCheck invariant 250.
 */
class PolishHelpersTest {

    private String unitWas;

    @BeforeEach void inHg() { unitWas = Model.Fmt.unit; Model.Fmt.unit = "inHg"; }

    @AfterEach void restore() { Model.Fmt.unit = unitWas; }

    /* ---- SYS-1: the ink on a filled button --------------------------------------------- */

    @Test void aRedButtonGetsTheDarkInkAndClearsAa() {
        assertEquals(Look.ON_RED, Look.inkOn(Look.CRITICAL));
        assertTrue(Look.meetsAA(Look.inkOn(Look.CRITICAL), Look.CRITICAL, false),
            "the red button's label clears 4.5:1, not 3.0:1");
        assertFalse(Look.meetsAA(Look.TEXT, Look.CRITICAL, false), "TEXT on red is the bug");
    }

    @Test void everyLightFillKeepsItsInkAndDarkFillsKeepText() {
        assertEquals(Look.ON_AMBER, Look.inkOn(Look.COMMANDED));
        assertEquals(Look.ON_GREEN, Look.inkOn(Look.SAFE));
        assertEquals(Look.ON_ACCENT, Look.inkOn(Look.ACCENT));
        assertEquals(Look.ON_BODY, Look.inkOn(Look.BODY));
        assertEquals(Look.TEXT, Look.inkOn(Look.SURFACEHI));
        assertEquals(Look.TEXT, Look.inkOn(Look.SURFACE));
        int[] fills = { Look.COMMANDED, Look.SAFE, Look.ACCENT, Look.BODY, Look.CRITICAL,
                        Look.SURFACE, Look.SURFACEHI };
        for (int i = 0; i < fills.length; i++)
            assertTrue(Look.meetsAA(Look.inkOn(fills[i]), fills[i], true),
                "fill " + Integer.toHexString(fills[i]) + " has a readable label");
    }

    /* ---- SYS-6: one sentence ------------------------------------------------------------ */

    @Test void aSentenceIsCapitalisedAndStoppedOnce() {
        assertEquals("Month 12 and your sessions carry on.",
            Say.sentence("month 12 and your sessions carry on"));
        assertEquals("It carries either way.", Say.sentence("It carries either way."));
        assertEquals("It carries either way.", Say.sentence("It carries either way.."),
            "a doubled stop from a join is folded back");
        assertEquals("Wait for it...", Say.sentence("Wait for it..."), "an ellipsis stays");
        assertEquals("Ready?", Say.sentence("  ready?  "));
        assertEquals("Choose one:", Say.sentence("choose one:"));
        assertEquals("Next …", Say.sentence("next …"));
        assertEquals("He said “stop.”", Say.sentence("he said “stop.”"),
            "punctuation inside a closing quote counts");
        assertEquals("−8.9 inHg is the pull.", Say.sentence("−8.9 inHg is the pull"));
        assertEquals("", Say.sentence(null));
        assertEquals("", Say.sentence("   "));
    }

    @Test void theArticleFollowsHowTheNumberIsSaid() {
        assertEquals("an", Say.aOrAn(8));
        assertEquals("an", Say.aOrAn(11));
        assertEquals("an", Say.aOrAn(18));
        assertEquals("an", Say.aOrAn(80));
        assertEquals("an", Say.aOrAn(800));
        assertEquals("an", Say.aOrAn(11000));
        assertEquals("a", Say.aOrAn(1));
        assertEquals("a", Say.aOrAn(4));
        assertEquals("a", Say.aOrAn(12));
        assertEquals("a", Say.aOrAn(110));
        assertEquals("a", Say.aOrAn(180));
        assertEquals("a", Say.aOrAn(100));
        assertEquals("Start an 8-week girth block",
            "Start " + Say.aOrAn(8) + " 8-week girth block");
        assertEquals("an", Say.aOrAn("8-week"));
        assertEquals("a", Say.aOrAn("12-lb"));
        assertEquals("an", Say.aOrAn("extra week"));
        assertEquals("a", Say.aOrAn("deload"));
    }

    /* ---- TD-4 / LB-4: the display title ------------------------------------------------- */

    @Test void aPlanRoutineIsTitledByTrackAndLevel() {
        assertEquals("Girth · Level 3",
            Say.routineDisplayTitle("Trainer · Girth L3 · 10×2min @ −8.9 inHg"));
        assertEquals("Traditional girth · Level 2",
            Say.routineDisplayTitle("Trainer · Girth·trad L2 · 5×5min @ −8.9 inHg"));
        assertEquals("Length · Level 2",
            Say.routineDisplayTitle("Trainer · Length L2 · traction @ 30 lb"));
        assertEquals("Length · Level 2 · girth focus · expansion only",
            Say.routineDisplayTitle("Trainer · Length L2 · girth focus · expansion only"),
            "what tells two routines apart stays");
    }

    @Test void aRoutineTheUserNamedIsShownAsNamed() {
        assertEquals("My evening pump", Say.routineDisplayTitle("My evening pump"));
        assertEquals("", Say.routineDisplayTitle((String) null));
        Model.Routine r = new Model.Routine();
        r.name = "Trainer · Girth L1 · 10×2min @ −5.0 inHg";
        assertEquals("Girth · Level 1", Say.routineDisplayTitle(r));
        assertEquals("Trainer · Girth L1 · 10×2min @ −5.0 inHg", r.name,
            "display only - the stored name is unchanged");
        assertEquals("", Say.routineDisplayTitle((Model.Routine) null));
    }

    @Test void theMintsOwnFormComesBackAsATitle() {
        // Mint#name's shape: prefix, track and level, then the prescription.
        String name = Mint.MINT_NAME_PREFIX + "Girth L4 · 12×2.5min @ −10.0 inHg";
        assertTrue(Mint.isMintedName(name), name);
        assertEquals("Girth · Level 4", Say.routineDisplayTitle(name));
    }

    /* ---- SYS-13: one shape --------------------------------------------------------------- */

    @Test void oneShapeEverywhere() {
        assertEquals("10 × 2 min", Model.Fmt.shape(10, 120));
        assertEquals("10 × 60 s", Model.Fmt.shape(10, 60));
        assertEquals("6 × 90 s", Model.Fmt.shape(6, 90));
        assertEquals("5 × 5 min", Model.Fmt.shape(5, 300));
        assertEquals("4 × 2.5 min", Model.Fmt.shape(4, 150));
        assertEquals("3 × 2 min 15 s", Model.Fmt.shape(3, 135));
        assertEquals("1 × 30 s", Model.Fmt.shape(0, 30), "a count under 1 is said as 1");
        assertEquals("10 × 2 min at −8.9 inHg", Model.Fmt.shape(10, 120, 30),
            "the minus is U+2212, from Fmt.p");
        assertFalse(Model.Fmt.shape(10, 120, 30).contains("-"), "never an ASCII hyphen");
    }

    @Test void aPercentHasNoSpace() {
        assertEquals("6%", Model.Fmt.pct(6));
        assertEquals("75%", Model.Fmt.pct(75.0));
        assertEquals("6.5%", Model.Fmt.pct(6.5));
        assertEquals("6%", Model.Fmt.pct(6.04));
    }

    /* ---- SYS-12 / SYS-3: the words on a danger button and after a choice ---------------- */

    @Test void aConfirmingDeleteEndsInAnEllipsisOnce() {
        assertEquals("Erase all data…", Say.dangerLabel("Erase all data", true));
        assertEquals("Erase all data…", Say.dangerLabel("Erase all data…", true));
        assertEquals("Erase all data...", Say.dangerLabel("Erase all data...", true));
        assertEquals("Delete", Say.dangerLabel("Delete…", false));
        assertEquals("Delete", Say.dangerLabel("Delete", false));
    }

    @Test void theChoiceSnackSaysRowAndOption() {
        assertEquals("Long training days: Alternate on my days",
            Say.choiceChanged("Long training days", " Alternate on my days "));
    }

    /* ---- TR-1: one decision card at a time ----------------------------------------------- */

    @Test void theMostUrgentCardShowsAndTheRestWait() {
        DecisionQueue q = new DecisionQueue()
            .set(DecisionQueue.UPGRADE, true).set(DecisionQueue.DELOAD_ASK, true);
        assertEquals(DecisionQueue.DELOAD_ASK, q.first());
        assertEquals(2, q.count());
        assertFalse(q.decisionShownAbove(DecisionQueue.DELOAD_ASK), "the deload question is the card");
        assertTrue(q.decisionShownAbove(DecisionQueue.UPGRADE), "the upgrade waits as a row");
        assertEquals("1 OF 2", q.position(DecisionQueue.DELOAD_ASK));
        assertEquals("", q.position(DecisionQueue.UPGRADE));
    }

    @Test void theOrderIsSafetyThenDeloadQuestionThenDueThenUpgrade() {
        DecisionQueue q = new DecisionQueue();
        for (int k = 0; k < DecisionQueue.KINDS; k++) q.set(k, true);
        assertEquals(DecisionQueue.SAFETY, q.first());
        assertEquals("1 OF 4", q.position(DecisionQueue.SAFETY));
        assertTrue(q.decisionShownAbove(DecisionQueue.DELOAD_ASK));
        assertTrue(q.decisionShownAbove(DecisionQueue.DELOAD_DUE));
        q.set(DecisionQueue.SAFETY, false).set(DecisionQueue.DELOAD_ASK, false);
        assertEquals(DecisionQueue.DELOAD_DUE, q.first());
        assertFalse(q.decisionShownAbove(DecisionQueue.DELOAD_DUE));
        assertTrue(q.decisionShownAbove(DecisionQueue.UPGRADE));
    }

    @Test void aLoneCardHasNoCountAndAnEmptyQueueShowsNothing() {
        DecisionQueue q = new DecisionQueue().set(DecisionQueue.UPGRADE, true);
        assertEquals("", q.position(DecisionQueue.UPGRADE));
        assertFalse(q.decisionShownAbove(DecisionQueue.UPGRADE));
        DecisionQueue empty = new DecisionQueue();
        assertEquals(-1, empty.first());
        assertEquals(0, empty.count());
        assertFalse(empty.decisionShownAbove(DecisionQueue.UPGRADE));
        assertFalse(empty.waiting(99));
    }

    @Test void theQueueReadsTheSameTestsTheCardsAsk() {
        Model m = new Model();
        DecisionQueue none = DecisionQueue.at(m, System.currentTimeMillis(), false);
        assertEquals(0, none.count(), "a person not on the plan has nothing to decide");
        m.trainerState = Model.TRAINER_STATE_SAFETY_FLAG;
        assertTrue(DecisionQueue.at(m, System.currentTimeMillis(), false)
            .waiting(DecisionQueue.SAFETY));
        m.trainerState = Model.TRAINER_STATE_NORMAL;
        m.trainerEnrolled = true;
        m.planT10Seen = false;
        long now = System.currentTimeMillis();
        assertEquals(PlanCards.upgradeCardShown(m, false),
            DecisionQueue.at(m, now, false).waiting(DecisionQueue.UPGRADE));
        assertFalse(DecisionQueue.at(m, now, true).waiting(DecisionQueue.UPGRADE),
            "\"Later\" this run puts the upgrade away");
        assertEquals(Deload.askGapStartMs(m, now) > 0L,
            DecisionQueue.at(m, now, false).waiting(DecisionQueue.DELOAD_ASK));
        assertEquals(TrainerTab.cadenceDeloadDue(m, now),
            DecisionQueue.at(m, now, false).waiting(DecisionQueue.DELOAD_DUE));
        assertFalse(DecisionQueue.at(null, now, false).waiting(DecisionQueue.SAFETY));
    }
}
