package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * FIVE TRAINER TEXTS THAT DISAGREED WITH THE CODE (the owner's decision, 2026-09-26; words
 * only, no behaviour). Each old sentence is pinned absent and the new one present, and the
 * high-yield rule is checked against each level's own upper target.
 */
class TrainerWordingTest {

    private static final Path APP = Paths.get("../app/src/main/java/org/openpump");

    private static String app(String file) throws Exception {
        Path p = APP.resolve(file);
        assertTrue(Files.isRegularFile(p), "not found: " + p.toAbsolutePath());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    static Plan.Decision high(int level) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = level;
        in.monthIndex = 1;
        in.weekIndex = 12;
        in.hasYieldData = true;
        in.consecutiveHighYield = 3;
        in.yieldSets = 1;                   // R-21: a pause with something to take back
        in.firstDeloadPending = false;
        return Plan.evaluate(in);
    }

    @Test void theHighYieldRuleNamesTheLevelsOwnUpperTarget() {
        // EXPECTATION CHANGED (R-20: Level 1 has no high arm; R-22: the target is 6-12 %).
        assertTrue(high(Plan.L1).action != Plan.ACTION_PAUSE_VOLUME);
        assertEquals("yield > 12% consistently -> pause volume additions", high(Plan.L2).rule);
        assertEquals("yield > 12% consistently -> reduce volume", high(Plan.L3).rule);
        assertEquals("12%", Plan.pct(Plan.yieldTargetHi(Plan.L1)));
        assertEquals("2.5%", Plan.pct(2.5));
    }

    @Test void setupNoLongerCallsLengthStatic() throws Exception {
        String s = app("TrainerScreen.java");
        assertFalse(s.contains("Length is static"));
        // Polish SU-16: "and no yield gate" was jargon; the sentence ends at the steps.
        assertTrue(s.contains("Length has its own levels and load steps."));
        assertFalse(s.contains("load steps, and no yield gate"));
    }

    @Test void atRestReadingsAreSaidToCount() throws Exception {
        String s = app("TrainerScreen.java");
        assertFalse(s.contains("A tracked reading is one taken under the standardisation hold"));
        assertTrue(s.contains("standardised or at rest"));
        // ...which is what the count does: an at-rest girth method measures girth.
        assertTrue(Model.Reading.methodMeasuresGirth(Model.Reading.METHOD_MSEG));
        assertTrue(Model.Reading.methodMeasuresGirth(Model.Reading.METHOD_STANDARDIZED));
    }

    // EXPECTATION CHANGED (R-28, Y-M1: the 4 hours run from the length session's end).
    @Test void afterOtherWorkIsFromTheEnd() throws Exception {
        String s = app("SummaryScreen.java");
        assertFalse(s.contains("a length session started less than"));
        assertTrue(s.contains("a length session ended less than"));
    }

    @Test void recalibrateSaysWhatItPrefills() throws Exception {
        String s = app("TrainerScreen.java");
        assertFalse(s.contains("calendar months since you first enrolled. "));
        assertFalse(s.contains("months since you first enrolled"));
        assertTrue(s.contains("Pre-filled with your months in total: what you answered at setup"));
        // ...and the pre-fill is that figure (the owner's decision, 2026-09-27).
        String act = app("SessionActivity.java");
        assertTrue(act.contains("trainerOnboardMonths = TrainerTab.recalibrateMonths(model, "));
        assertFalse(act.contains("trainerOnboardMonths = TrainerTab.monthsBetween("));
    }

    @Test void recalibratePrefillsTheRealMonthsTotal() {
        long day = 24L * 3600000L;
        long now = 1_790_000_000_000L;
        Model m = new Model();
        m.trainerEnrolledAt = now - 61 * day;             // two months ago
        m.trainerMonthsAt = now - 61 * day;
        m.trainerMonthsPumping = 12;                      // "12 months" at setup
        assertEquals(14, TrainerTab.recalibrateMonths(m, now), "12 answered + 2 since");
        assertEquals(TrainerTab.monthIndexNow(m, now), TrainerTab.recalibrateMonths(m, now),
            "the same figure every month gate reads");
        m.trainerMonthsAt = now - 20 * day;               // answered again 20 days ago
        assertEquals(12, TrainerTab.recalibrateMonths(m, now), "an unfinished month adds none");
        m.trainerMonthsPumping = 400;
        assertEquals(360, TrainerTab.recalibrateMonths(m, now), "inside the question's range");
        assertEquals(0, TrainerTab.recalibrateMonths(new Model(), now));
    }
}
