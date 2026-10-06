package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Polish lane P2 (Today, the run screen, the dialogs): the pure pieces it added - the strip's
 * unit labels, Skip's Undo, the feeder's time line - and the words the screens now say, pinned
 * where they are written so they cannot drift back.
 */
class PolishP2Test {

    @AfterEach void unitBack() { Model.Fmt.unit = Model.Fmt.U_INHG; }

    /* ---- RN-4: the strip's pressure cells say their unit ---------------------------- */

    @Test void theStripsPressureCellsNameTheirUnit() {
        Model.Fmt.unit = Model.Fmt.U_INHG;
        assertEquals("Pull to (inHg)", RunEdit.stripLabel(QuickAdjust.PULL));
        assertEquals("Drop to (inHg)", RunEdit.stripLabel(QuickAdjust.DROP));
        Model.Fmt.unit = Model.Fmt.U_KPA;
        assertEquals("Pull to (kPa)", RunEdit.stripLabel(QuickAdjust.PULL));
        assertEquals("Hold time", RunEdit.stripLabel(QuickAdjust.HOLD),
            "the other cells keep QuickAdjust's own names");
        assertEquals("Drop time", RunEdit.stripLabel(QuickAdjust.DROP_TIME));
        assertEquals("", RunEdit.stripLabel(-1));
    }

    @Test void theAdjustSheetUsesTheAppsNames() {
        assertEquals("Suction power", RunEdit.SHEET_POWER);
        assertEquals("Pull to", RunEdit.SHEET_PULL);
        assertEquals("Drop to", RunEdit.SHEET_DROP);
    }

    /* ---- RN-9: Undo after Skip these sets ------------------------------------------- */

    private static Model.Preset preset(String label, int up, long durMs) {
        Model.Preset p = new Model.Preset();
        p.up = up; p.lo = 10; p.uh = 120; p.lh = 5; p.sp = 75;
        p.durMs = durMs; p.label = label; p.stageIdx = 2; p.setId = "s1"; p.pos = 1;
        p.ordinal = 3; p.offsetKpa = -2; p.loMax = 12; p.builtHoldOnly = true;
        return p;
    }

    @Test void aCopyIsTheSameStepWithItsOwnLength() {
        Model.Preset z = preset("Work 1", 30, 600000L);
        z.cyclePart = true; z.added = true;
        Model.Preset c = RunEdit.copyPreset(z, 42000L);
        assertNotSame(z, c);
        assertEquals(42000L, c.durMs);
        assertEquals(z.up, c.up); assertEquals(z.lo, c.lo); assertEquals(z.uh, c.uh);
        assertEquals(z.lh, c.lh); assertEquals(z.sp, c.sp);
        assertEquals(z.label, c.label); assertEquals(z.stageIdx, c.stageIdx);
        assertEquals(z.setId, c.setId); assertEquals(z.pos, c.pos); assertEquals(z.ordinal, c.ordinal);
        assertEquals(z.offsetKpa, c.offsetKpa, "the offset it carried stays counted");
        assertEquals(z.loMax, c.loMax); assertEquals(z.builtHoldOnly, c.builtHoldOnly);
        assertEquals(z.awaitAck, c.awaitAck); assertEquals(z.cyclePart, c.cyclePart);
        assertEquals(z.added, c.added); assertEquals(z.rest, c.rest);
    }

    @Test void undoPutsTheSetsBackInFrontOfTheStepThatStarted() {
        Model.Preset cut = preset("Work 1", 30, 600000L);
        Model.Preset t1 = preset("Work 1", 30, 300000L);
        Model.Preset t2 = preset("Work 1", 30, 300000L);
        Model.Preset rest = preset("Rest", 0, 180000L);
        List<Model.Preset> taken = new ArrayList<Model.Preset>();
        taken.add(t1); taken.add(t2);
        List<Model.Preset> back = RunEdit.undoSkipSteps(cut, 250000L, taken, rest);
        assertEquals(4, back.size(), "what was left of the cut step, the two taken out, the rest");
        assertEquals(250000L, back.get(0).durMs, "the cut step's unplayed time only");
        assertNotSame(cut, back.get(0));
        assertSame(t1, back.get(1));
        assertSame(t2, back.get(2));
        assertEquals("Rest", back.get(3).label);
        assertEquals(180000L, back.get(3).durMs, "the step that had started runs again whole");
        assertNotSame(rest, back.get(3));
    }

    @Test void undoLeavesOutAnUnplayedSliverAndNeverReplaysForNothing() {
        Model.Preset cut = preset("Warm-up", 20, 60000L);
        Model.Preset next = preset("Fatigue block", 28, 500000L);
        List<Model.Preset> back = RunEdit.undoSkipSteps(cut, 400L, new ArrayList<Model.Preset>(), next);
        assertTrue(back.isEmpty(), "under a second left and nothing taken out: nothing to put back");
        back = RunEdit.undoSkipSteps(cut, 30000L, null, next);
        assertEquals(2, back.size());
        assertEquals(30000L, back.get(0).durMs);
        assertEquals("Fatigue block", back.get(1).label);
    }

    @Test void undoIsOfferedOnlyBriefly() {
        assertTrue(RunEdit.SKIP_UNDO_MS >= Snack.HOLD_MS_ACTIONABLE,
            "Undo is still taken for as long as its snack shows");
        assertTrue(RunEdit.SKIP_UNDO_MS <= 10000L, "and not long after");
    }

    /** EMU9 H2: Undo is the Skip button's other name; a late tap meant for it skips nothing. */
    @Test void aLateTapMeantForUndoSkipsNothing() {
        long gone = 1_000_000L;
        assertFalse(RunEdit.skipLateTapGuarded(0L, gone), "never offered: Skip is Skip");
        assertTrue(RunEdit.skipLateTapGuarded(gone, gone), "the moment Undo goes");
        assertTrue(RunEdit.skipLateTapGuarded(gone, gone + 1000L), "a thumb already on its way");
        assertFalse(RunEdit.skipLateTapGuarded(gone, gone + RunEdit.SKIP_LATE_TAP_MS),
            "then Skip again");
        assertTrue(RunEdit.SKIP_LATE_TAP_MS <= 2000L, "never long enough to be a dead control");
    }

    /* ---- NEW-16: the feeder before its window --------------------------------------- */

    @Test void aWaitIsSaidInHoursAndMinutes() {
        assertEquals("", UpNext.waitWords(0L));
        assertEquals("1 min", UpNext.waitWords(1000L), "rounded up to the minute");
        assertEquals("25 min", UpNext.waitWords(25L * 60000L));
        assertEquals("1 h", UpNext.waitWords(60L * 60000L));
        assertEquals("3 h 50 min", UpNext.waitWords((3L * 60L + 50L) * 60000L));
    }

    @Test void theFeedersTimeIsTheHeadline() {
        assertEquals("Feeder from 10:14 · in 3 h 50 min",
            UpNext.feederFromLine("10:14", (3L * 60L + 50L) * 60000L));
        assertEquals("Feeder from 10:14", UpNext.feederFromLine("10:14", 0L));
        assertEquals("Start the feeder early", UpNext.FEEDER_EARLY);
        assertFalse(UpNext.FEEDER_RULE.contains(" - "), "a dash, never a hyphen");
        assertTrue(UpNext.FEEDER_RULE.startsWith("Feeders go 4–6 h after the day’s main session."));
    }

    /* ---- the words on the screens, pinned where they are written ----------------------- */

    private static List<String> filesWith(String needle) throws Exception {
        List<String> hits = new ArrayList<String>();
        Path[] roots = { Paths.get("src/main"), Paths.get("../app/src/main") };
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                Iterator<Path> it = files.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    if (!p.toString().endsWith(".java")) continue;
                    String code = NoBookNamesTest.stripComments(
                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                    if (code.contains(needle)) hits.add(p.getFileName().toString());
                }
            }
        }
        return hits;
    }

    @Test void endSessionAsksAndNeverLooksLikeBack() throws Exception {
        assertTrue(filesWith("\"‹ End session\"").isEmpty(), "it looked like a Back link");
        assertEquals(1, filesWith("\"End session…\"").size());
        assertEquals(1, filesWith("\"End this session?\"").size());
        assertEquals(1, filesWith("\"The cuff vents now.\"").size());
        assertEquals(1, filesWith("\"Stopped and vented at step \"").size());
    }

    @Test void twoVentLabelsOnly() throws Exception {
        assertTrue(filesWith("\"STOP — end and vent\"").isEmpty());
        assertTrue(filesWith("\"Cancel — end and vent\"").isEmpty());
        assertFalse(filesWith("\"STOP · vent now\"").isEmpty());
        assertFalse(filesWith("\"Cancel and vent\"").isEmpty());
        assertTrue(filesWith("0xFF210605").isEmpty(), "STOP's ink is Look.ON_RED");
    }

    @Test void theDeloadSheetAsksOnlyTheQuestion() throws Exception {
        assertEquals(1, filesWith("\"When should the deload week start?\"").size());
        assertFalse(filesWith("counters frozen").contains("SessionActivity.java"),
            "history says the plan's week count pauses");
    }

    @Test void theUpdateSheetIsSingularAndHasNoInternalFigure() throws Exception {
        assertEquals(1, filesWith("\"Your routine was updated\"").size());
        assertTrue(filesWith("\"Net target \"").isEmpty());
        assertTrue(filesWith("is already updated and still selected").isEmpty());
    }

    @Test void theRunScreenSaysEachThingOnce() throws Exception {
        assertTrue(filesWith("\"NET \"").isEmpty(), "the net row in words");
        assertTrue(filesWith("\"Routine offset —\"").isEmpty(), "None, never a dash");
        assertTrue(filesWith("sb.append(\"now: \")").isEmpty(), "only Next: under the bar");
        assertEquals(1, filesWith("\"Skip these sets\"").size());
        assertEquals(1, filesWith("\"+30 s warm-up\"").size());
    }
}
