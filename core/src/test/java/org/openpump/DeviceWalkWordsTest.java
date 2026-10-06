package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * t10 device walk, the small words: "1 months ago", "5 / 3 days", the marks setting named two
 * ways ("I mark or bruise easily" in the setup, "I bruise or spot easily" on the Trainer), the
 * start confirm counting "presets" where Today counts cycles, and one load shown at two
 * roundings.
 */
class DeviceWalkWordsTest {

    @Test void monthsAreCountedInEnglish() {
        assertEquals("this month", Say.monthsAgo(0));
        assertEquals("1 month ago", Say.monthsAgo(1));
        assertEquals("7 months ago", Say.monthsAgo(7));
    }

    @Test void aFullWeekIsNotFiveOfThree() {
        // Week B (2026-10-03): the plan-wide figure; once the week counts it says so alone.
        TrainingWeek.PlanWeek one = TrainingWeek.planWeek(null,
            new java.util.ArrayList<Model.Sess>(), new java.util.ArrayList<Model.Sess>(),
            true, false);
        assertEquals("0 of 2 full sessions", Say.planWeekShort(one));
        TrainingWeek.PlanWeek both = TrainingWeek.planWeek(null,
            new java.util.ArrayList<Model.Sess>(), new java.util.ArrayList<Model.Sess>(),
            true, true);
        assertEquals("Girth 0/2 · Length 0/2", Say.planWeekShort(both));
        both.qualifies = true;
        assertEquals("Counted", Say.planWeekShort(both));
    }

    /** App and core string literals (comments stripped, NoBookNamesTest's lexer). */
    private static List<String> literalsMatching(String needle) throws Exception {
        List<String> hits = new ArrayList<String>();
        Path[] roots = { Paths.get("src/main"), Paths.get("../app/src/main") };
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    if (!p.toString().endsWith(".java")) continue;
                    String code = NoBookNamesTest.stripComments(
                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                    if (code.contains(needle)) hits.add(p.getFileName().toString());
                }
            }
        }
        return hits;
    }

    @Test void theMarksSettingHasOneName() throws Exception {
        assertEquals("I mark or bruise easily", SetupText.MARKS_LABEL);
        assertTrue(literalsMatching("bruise or spot").isEmpty(),
            "the Trainer's old label: " + literalsMatching("bruise or spot"));
        List<String> own = literalsMatching("\"I mark or bruise easily\"");
        assertEquals(1, own.size(), "written once, in SetupText: " + own);
        assertEquals("SetupText.java", own.get(0));
    }

    @Test void theStartConfirmCountsCycles() throws Exception {
        assertTrue(literalsMatching("presets + \" presets, \"").isEmpty(),
            "START's confirm counts cycles, as Today's card does");
        assertEquals(1, literalsMatching("This RUNS THE PUMP: \" + cycles").size());
    }

    @Test void theLengthLoadIsShownAsDelivered() {
        Model m = new Model();
        m.trainerLengthOn = true;
        m.trainerLength.loadLb = 10.0;
        // No length cylinder: the planned load.
        assertEquals(Scale.pullLoadLb(m, 0L), Scale.shownLoadLb(m, 0L), 1e-9);
    }
}
