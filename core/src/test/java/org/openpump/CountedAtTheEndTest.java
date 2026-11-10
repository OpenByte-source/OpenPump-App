package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * A RESUMED ROUTINE COUNTED ONLY AT ITS END COUNTS WHOLE (follow-up, 9 Nov 2026). A routine
 * the plan did not write is timed against its own pull; the plan's figure - its net against
 * the girth line "Count this toward training?" measures by - was measured only for the part
 * that filed last, so counting a stopped-and-resumed session at its end counted that part
 * alone. Now each part's worth is kept beside its own-pull time and carried to the end.
 */
class CountedAtTheEndTest {
    @Test void theWholeSessionsWorthIsWhatCountingUses() {
        Model.Sess first = new Model.Sess();
        first.id = "p1"; first.ts = 1_000_000L; first.durSec = 90; first.completed = false;
        first.netTupSec = Double.valueOf(70.0); first.tupOwnPull = true;   // its own pull
        first.countWorthSec = Double.valueOf(40.0);                       // against the girth line
        RunParts prior = RunParts.ofRecord(first); prior.resumes++;
        assertEquals(40.0, prior.worthSec, 1e-9, "STOP carries the first part's worth");
        assertEquals(65.0, RunParts.wholeWorth(prior, 25.0), 1e-9,
            "counted at the end: 40 before STOP + 25 after, the whole session");
        assertEquals(25.0, RunParts.wholeWorth(null, 25.0), 1e-9, "a run in one part: its own");
        RunParts unmeasured = new RunParts();
        assertEquals(25.0, RunParts.wholeWorth(unmeasured, 25.0), 1e-9, "nothing kept before: this part's");
    }

    @Test void aKillKeepsItTooThroughTheSnapshot() {
        RunParts sofar = RunParts.sofar(null, 1_000L, 60_000L, null, null, 0.0, null, -1);
        RunParts.addWorth(sofar, null, Double.valueOf(30.0));
        RunParts back = RunParts.fromCode(sofar.code());
        assertNotNull(back);
        assertEquals(30.0, back.worthSec, 1e-6);
        RunParts p2 = RunParts.fromCode("p2;1000;2000;;;0.000;;-1;0;1;sessA;sessA");
        assertNotNull(p2, "a snapshot written before reads");
        assertTrue(Double.isNaN(p2.worthSec), "as not measured");
    }

    @Test void theRecordKeepsItAndOldRecordsReadAsNotMeasured() throws Exception {
        Model.Sess s = new Model.Sess(); s.id = "x"; s.ts = 1L; s.countWorthSec = Double.valueOf(12.5);
        assertEquals(12.5, Model.Sess.fromJson(s.toJson()).countWorthSec, 1e-9);
        Model.Sess old = Model.Sess.fromJson(new org.json.JSONObject("{\"id\":\"o\",\"ts\":\"1\",\"dur\":\"60\"}"));
        assertNull(old.countWorthSec);
        assertFalse(old.toJson().has("worth"));
    }

    @Test void theFilingAndTheSnapshotUseIt() throws Exception {
        String sa = new String(Files.readAllBytes(Paths.get("../app/src/main/java/org/openpump/SessionActivity.java")),
            StandardCharsets.UTF_8);
        assertTrue(sa.contains("RunParts.wholeWorth(parts, tup[0])"), "the question counts the whole session");
        assertTrue(sa.contains("rec.countWorthSec = "), "and the record keeps it for a later Resume");
        assertTrue(sa.contains("RunParts.addWorth(parts, runPrior,"), "and the snapshot for a kill");
    }
}
