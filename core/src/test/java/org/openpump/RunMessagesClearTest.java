package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Device check EMU9b, the three low findings.
 *
 *   N2 - "Skipped …" / "Put back — this run only." sat over STOP for its whole hold: every
 *        Skip and Undo redraws the run screen, the new footer had no height yet, and the
 *        snack was placed with no inset and never moved.
 *   N3 - a skipped card in Coming steps lost its own name and length: a skipped ramp and a
 *        skipped "Fatigue hold" both read as the stage ("Fatigue block", 0:00), and two
 *        skipped blocks of sets both read "Sets 1–5".
 *   N4 - "Hold complete — measure now" was a system toast, drawn at the foot of the after
 *        form over its Save button. Then (leftovers, option A) not a message at all: the
 *        hold screen's title says it.
 */
class RunMessagesClearTest {

    private static String code(String file) throws Exception {
        return NoBookNamesTest.stripComments(new String(Files.readAllBytes(
            Paths.get("../app/src/main/java/org/openpump/" + file)), StandardCharsets.UTF_8));
    }

    /** The body of the first method or class named `name` (brace-matched). */
    private static String body(String code, String head) {
        int at = code.indexOf(head);
        assertTrue(at >= 0, "found: " + head);
        int open = code.indexOf('{', at), depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return code.substring(open, i + 1);
        }
        return code.substring(open);
    }

    /* ------------------------------------------------------------------ N2 */

    @Test void aRunScreenMessageFollowsTheFooterItWasShownOver() throws Exception {
        String sa = code("SessionActivity.java");
        String toast = body(sa, "void toast(String s)");
        int snack = toast.indexOf("Ui.snack(this, rootFrame, s, runFooterInsetPx());");
        int hold = toast.indexOf("Ui.holdAboveFooter(rootFrame);");
        assertTrue(snack >= 0 && hold > snack,
            "the run screen's message is marked to follow the footer, whatever inset it had");
        String inset = body(sa, "int runFooterInsetPx()");
        assertTrue(inset.contains("return runFooterLastInsetPx;"),
            "a footer not laid out yet (the redraw a Skip makes) reads the last inset, not 0");
        assertTrue(body(code("Ui.java"), "public static void holdAboveFooter(")
            .contains("setTag(SNACK_ABOVE)"));
    }

    /* ------------------------------------------------------------------ N3 */

    private static Model.Preset step(int stage, int pos, String set, int up, long ms) {
        Model.Preset p = new Model.Preset();
        p.up = up; p.lo = 3; p.uh = 30; p.lh = 5; p.sp = 60; p.durMs = ms;
        p.stageIdx = stage; p.pos = pos; p.setId = set;
        return p;
    }

    @Test void aSkippedRampKeepsItsShapeAndLength() {
        List<Model.Preset> taken = new ArrayList<Model.Preset>();
        taken.add(step(1, 0, "climb", 12, 50_000L));
        taken.add(step(1, 0, "climb", 15, 50_000L));
        int key = ComingSteps.key(1, 0);
        ComingSteps.Shape sh = ComingSteps.skippedShape(taken, key, true);
        assertEquals(ComingSteps.SHAPE_RAMP, sh.kind, "still a ramp: \"Ramp · 2 steps\"");
        assertEquals(2, sh.steps);
        assertEquals(100_000L, sh.ms, "its own length, not 0:00");
    }

    @Test void skippedBlocksOfSetsAreNamedByTheirOwnCountNeverARange() {
        assertEquals("5 sets", ComingSteps.skippedSetsName(5));
        assertEquals("1 set", ComingSteps.skippedSetsName(1));
        assertFalse(ComingSteps.skippedSetsName(5).startsWith("Sets "),
            "a range belongs to the sets that will run, not to ones skipped");
        assertNotEquals("Sets 1–5", ComingSteps.skippedSetsName(5));
    }

    @Test void theSheetReadsASkippedCardFromWhatTheSkipTookOut() throws Exception {
        String rs = code("RunScreen.java");
        assertFalse(rs.contains("shapeOf(a.comingSkipped.get(key), s, false)"),
            "a skipped ramp read as not-a-ramp, and was named by its stage");
        assertTrue(rs.contains("ComingSteps.skippedShape(a.comingSkipped.get(key), s, a.comingIsRamp(s))"));
        assertTrue(rs.contains("ComingSteps.skippedSetsName("));
        assertTrue(body(rs, "private String blockSetName(").contains("src"),
            "a skipped block's set name is read from its own presets");
        String isRamp = body(code("SessionActivity.java"), "boolean comingIsRamp(int si)");
        assertTrue(isRamp.contains("comingSkipped"), "a skipped block is still asked about");
    }

    /* ------------------------------------------------------------------ N4 */

    /* Owner's pick on the leftovers (option A): no passing message at all. The hold
     * screen's own title turns from "Standardising" into "Hold complete — measure now"
     * when the count is done, and the "Held for 30 s" card stays under it. */
    @Test void theHoldScreenTitleSaysTheCountIsDone() {
        assertEquals("Standardising", HoldWindow.screenTitle(false, false, false));
        assertEquals("Hold complete — measure now", HoldWindow.screenTitle(true, false, false));
        assertEquals("Preview complete", HoldWindow.screenTitle(true, true, false));
        assertEquals("Standardising", HoldWindow.screenTitle(false, true, false));
        // The two minutes passed: "measure now" is no longer true; the card says time's up.
        assertEquals("Standardising", HoldWindow.screenTitle(true, false, true));
    }

    @Test void holdCompleteIsNoLongerAPassingMessage() throws Exception {
        String sa = code("SessionActivity.java");
        assertFalse(sa.contains("\"Hold complete — measure now\""),
            "the words live in HoldWindow's title, never in a toast or sayTop of their own");
        assertFalse(sa.contains("sayTop(stdPreview ? \"Preview complete\""));
        assertFalse(sa.contains("sayTop(finished ?"), "Measure now › says nothing more");
        String tick = body(sa, "private final class StdTick");
        assertTrue(tick.contains("if (!stdHoldTitleDone())"),
            "the count's end retitles the hold screen; only with none up is it said");
        assertTrue(body(sa, "private boolean stdHoldTitleDone()")
            .contains("currentScreen != Nav.SCR_HOLD"));
        assertTrue(body(sa, "private void showStdHold()").contains("HoldWindow.screenTitle("),
            "a redraw after the count keeps the done title");
        // The other two exits still say what they measured.
        assertTrue(sa.contains("\"Measuring as it is, held past the two minutes\""));
        assertTrue(sa.contains("\"Measuring before the count finished — the pump keeps holding\""));
        assertTrue(body(code("Ui.java"), "public static void sayTop(").contains("sayInApp("),
            "those are drawn by the app under the top bar, clear of the form's buttons");
    }
}
