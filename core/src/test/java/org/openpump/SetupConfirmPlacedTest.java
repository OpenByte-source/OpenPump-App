package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * FIX11 F5 / F6 (device check EMU12, Low) - the setup's Confirm screen says where each track
 * starts, level AND week, and what placed it. It showed "Level L1" beside "Month 7" (the week
 * the minutes placed it at appeared only on the Trainer, and the months answer read as a
 * contradiction), and the length minutes answer placed the strain sets without a word.
 */
class SetupConfirmPlacedTest {

    @Test
    void theLevelAndWeekAndWhatPlacedIt() {
        assertEquals("Level 1, week 6 — closest to your 20 min",
            TrainerOnboard.placedAt(Plan.L1, 6, 20, false));
        assertEquals("Level 1, week 1 — new to pumping",
            TrainerOnboard.placedAt(Plan.L1, 1, 0, true));
        assertEquals("Level 1, week 1 — you don't do this track yet",
            TrainerOnboard.placedAt(Plan.L1, 1, 0, false));
        assertEquals("Level 3, week 1", TrainerOnboard.placedAt(Plan.L3, 1,
            Placement.NOT_ANSWERED, false), "placed by the months: no answer to name");
        assertEquals("Level 3 — closest to your 45 min",
            TrainerOnboard.placedAt(Plan.L3, 0, 45, false), "no week where the level has none");
        assertEquals("Level 2", TrainerOnboard.levelWords(Plan.L2));
        assertEquals("Level and week", TrainerOnboard.PLACED_ROW);
        assertEquals("Months pumping", TrainerOnboard.MONTHS_ROW);
    }

    @Test
    void lengthSaysWhatTheMinutesPlaced() {
        // EMU13: one minutes figure besides the answer - the placed session's, as the plan
        // writes it - never "closest" for a session longer than the answer.
        assertEquals("You answered 30 min: length starts at 4 strain sets, a 28.4-min session "
            + "— the closest without going over.",
            TrainerOnboard.lengthPlacedLine(30, true, 4, 28.4));
        assertEquals("You answered 30 min: length starts at 2 strain sets, its shortest "
            + "session, 45.1 min.", TrainerOnboard.lengthPlacedLine(30, true, 2, 45.1));
        assertEquals("You don't do this track yet, so it starts at the beginning.",
            TrainerOnboard.lengthPlacedLine(0, true, 2, 0));
        assertEquals("Your 30-min answer places length by its strain sets, and with no length "
            + "cylinder to pull with there are none: your months place it.",
            TrainerOnboard.lengthPlacedLine(30, false, 2, 10));
        assertEquals("", TrainerOnboard.lengthPlacedLine(Placement.NOT_ANSWERED, true, 2, 0));
    }

    @Test
    void theConfirmScreenStatesThemAndNoLongerSaysLevelL1BesideMonth() throws Exception {
        String ts = NoBookNamesTest.stripComments(new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("../app/src/main/java/org/openpump/TrainerScreen.java")),
            "UTF-8"));
        int at = ts.indexOf("private void buildOnboardConfirm(");
        int end = ts.indexOf("static final String TIME_AT_PRESSURE_ROW", at);
        assertTrue(at > 0 && end > at);
        String confirm = ts.substring(at, end);
        assertTrue(confirm.contains("TrainerOnboard.placedAt("), "the level and week, placed");
        assertTrue(confirm.contains("TrainerOnboard.MONTHS_ROW"));
        assertTrue(confirm.contains("lengthPlacedLine()"), "length's Placed by line");
        int len = confirm.indexOf("if (a.trainerOnboardLengthOn)");
        assertTrue(len > 0);
        assertFalse(confirm.substring(len).contains("TIME_AT_PRESSURE_ROW"),
            "EMU13: no third minutes figure in the length block");
        assertTrue(ts.contains("Placement.lengthSessionMin(scratch, a.trainerOnboardMonths"),
            "the placed line's minutes by the placement's own definition");
        assertFalse(confirm.contains("\"Month\""), "no bare \"Month\" beside a level");
        assertFalse(confirm.contains("TrainerTab.levelLabel(girth.level)"),
            "no \"Level L1\" on the girth row");
    }
}
