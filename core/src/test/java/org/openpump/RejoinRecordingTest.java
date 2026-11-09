package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * A RUN REJOINED AFTER THE APP WAS KILLED KEEPS ITS FIRST PART'S ROWS (follow-up to the live
 * GrowthTrack test, 9 Nov 2026).
 *
 * A killed process files nothing. Its rows are in asrun.json under the run's provisional key -
 * written on every note, its start, which the run snapshot keeps - with the last row still open.
 * The rejoined part recorded under a key of its own and filed only itself, so the first part's
 * rows were orphaned (trimmed after 90 days) and the summary, History and GrowthTrack had only
 * the part after the rejoin. Now the rejoin reads them back, the rejoined part persists them
 * joined with its own (so a second kill keeps both), and the filing files them all.
 * Recording only: nothing here or in the wiring it checks reaches the pump.
 */
class RejoinRecordingTest {
    private static AsRun.Row row(long t0, long t1, int pos) {
        AsRun.Row r = GrowthTrackIntegrationTest.row(AsRun.PLAN, t0, t1, pos);
        r.cyclePart = 0;
        return r;
    }

    @Test void theKilledPartsRowsAreJoinedAndSurviveASecondKill() throws Exception {
        Map<Long, JSONObject> asrun = new HashMap<Long, JSONObject>();   // asrun.json, by key
        long start1 = 1_790_000_000_000L;
        // Part 1, killed 70 s in: its last row still open; the snapshot last counted 60 s.
        AsRun p1 = new AsRun();
        p1.rows.add(row(900, 40_000, 0));
        p1.rows.add(row(40_000, -1, 1));
        asrun.put(start1, p1.toJson());
        RunParts prior = RunParts.sofar(null, start1, 60_000L, null, null, 0.0, null, -1);
        prior.rejoins++;

        // Rejoin: the rows read back from the key the snapshot names.
        AsRun rows = RunParts.priorRows(AsRun.fromJson(asrun.get(start1)), null);
        assertEquals(2, rows.rows.size());
        AsRun p2 = new AsRun();
        p2.rows.add(row(800, 30_000, 1));
        long start2 = start1 + 200_000L;
        AsRun persisted = RunParts.recording(prior, rows, p2);       // what part 2 persists
        asrun.put(start2, persisted.toJson());
        asrun.remove(start1);
        assertEquals(3, persisted.rows.size());
        assertEquals(60_000, persisted.rows.get(1).t1, "the open row closed where the snapshot had got to");
        assertEquals(60_800, persisted.rows.get(2).t0);
        assertEquals(1, persisted.rows.get(2).part);

        // Killed again 35 s into part 2; rejoined: the key now holds both parts.
        RunParts prior2 = RunParts.sofar(prior, start2, 35_000L, null, null, 0.0, null, -1);
        prior2.rejoins++;
        AsRun rows2 = RunParts.priorRows(AsRun.fromJson(asrun.get(start2)), null);
        AsRun p3 = new AsRun();
        p3.rows.add(row(700, 60_700, 2));
        AsRun filed = RunParts.recording(prior2, rows2, p3);
        assertEquals(4, filed.rows.size());
        assertEquals(2, filed.rows.get(3).part);
        for (int i = 1; i < filed.rows.size(); i++)
            assertTrue(filed.rows.get(i).t0 >= filed.rows.get(i - 1).t1, "time-ordered at " + i);

        // Filed as one session (95 s played before, 60 s now): every part's work is sent.
        Model.Sess s = GrowthTrackIntegrationTest.session("sess-rejoined");
        s.ts = start1; s.recordedStartMs = start1; s.durSec = 155; s.recordedEndMs = start2 + 400_000L;
        JSONArray sets = GrowthTrackSession.draft(s, filed).payload().getJSONArray("sets");
        int sum = 0;
        for (int i = 0; i < sets.length(); i++) sum += sets.getJSONObject(i).getInt("duration_seconds");
        assertTrue(sum >= 145, "all three parts' work (148 s recorded): " + sum + " s of 155");
    }

    @Test void theProvisionalRowsWinAndAFiledPartIsTheFallBack() {
        AsRun provisional = new AsRun(); provisional.rows.add(row(0, 1000, 0));
        AsRun filed = new AsRun(); filed.rows.add(row(0, 2000, 0));
        assertSame(provisional, RunParts.priorRows(provisional, filed), "the killed part's own copy holds the most");
        assertSame(filed, RunParts.priorRows(new AsRun(), filed), "after STOP, or a screen that filed, the record's");
        assertSame(filed, RunParts.priorRows(null, filed));
        assertNull(RunParts.priorRows(null, new AsRun()), "nothing to carry");
    }

    @Test void theRunCarriesTheRowsThroughTheRejoinAndTheFiling() throws Exception {
        String sa = new String(Files.readAllBytes(Paths.get("../app/src/main/java/org/openpump/SessionActivity.java")),
            StandardCharsets.UTF_8);
        assertTrue(sa.contains("spot.rowsKey = started;"), "the snapshot names where the killed part's rows are");
        assertTrue(sa.contains("runPriorRows = RunParts.priorRows("), "the rejoin reads them back");
        assertTrue(sa.contains("RunParts.recording(runPrior, runPriorRows, asRun)"),
            "the rejoined part persists them with its own");
        assertTrue(sa.contains("asRunFile(rec, elMs, earlier, parts, partsRows)"), "and files them");
    }
}
