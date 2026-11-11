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
 * COUNTED, THEN RESUMED, COUNTS ONCE (follow-up to the live GrowthTrack test, 9 Nov 2026).
 *
 * STOP files the first part and asks "Count this toward training?"; the Resume offer now waits
 * for the answer. Answered Count, the part's record carries the girth track and its net time.
 * Resumed and finished, the one session replaced that record without the count, and the
 * question was asked again for the resumed part alone: the first part's counted time was lost.
 */
class CountedThenResumedTest {
    private static Model.Sess part(String id, long ts, long durSec) {
        Model.Sess s = new Model.Sess();
        s.id = id; s.ts = ts; s.durSec = durSec; s.completed = false; s.routineId = "r1";
        return s;
    }

    /** fileSession's order: drop the earlier record, stamp, the count question's step, fold, file. */
    private static boolean[] fileResumed(Model.SessLog log, RunParts prior, Model.Sess rec, double partNet) {
        Model.Sess earlier = RunParts.dropEarlier(log.all, prior);
        RunParts.foldTime(prior, rec);
        rec.netTupSec = null;                          // a routine the plan did not write
        boolean carried = RunParts.carryCount(earlier, prior, rec, partNet);
        boolean asked = !carried && partNet > 0;       // pendingCountSessId = rec.id
        RunParts.foldFigures(prior, rec);
        log.file(rec);
        return new boolean[]{carried, asked};
    }

    @Test void aCountedFirstPartKeepsItsCountAndTheWholeSessionCountsOnce() {
        Model.SessLog log = new Model.SessLog();
        Model.Sess first = part("p1", 1_000_000L, 90);
        log.file(first);
        RunParts prior = RunParts.ofRecord(first); prior.resumes++;   // taken at STOP, before the answer
        // The answer: Count (CountTowardTrainingTap).
        first.countedTrack = Plan.TRACK_GIRTH_INTERVAL;
        first.netTupSec = Double.valueOf(40.0);

        Model.Sess whole = part("p2", 1_200_000L, 300);
        boolean[] r = fileResumed(log, prior, whole, 25.0);
        assertTrue(r[0], "the count is carried");
        assertFalse(r[1], "and the question is not asked again for the resumed part alone");
        assertEquals(1, log.all.size(), "one session, the first part's record replaced");
        assertEquals(Plan.TRACK_GIRTH_INTERVAL, whole.countedTrack);
        assertNotNull(whole.netTupSec);
        assertEquals(65.0, whole.netTupSec, 1e-9, "both parts' net, once: 40 counted + 25 resumed");
    }

    @Test void aSecondStopAndResumeStillCountsOnce() {
        Model.SessLog log = new Model.SessLog();
        Model.Sess first = part("p1", 1_000_000L, 90);
        first.countedTrack = Plan.TRACK_GIRTH_INTERVAL; first.netTupSec = Double.valueOf(40.0);
        log.file(first);
        RunParts p1 = RunParts.ofRecord(first); p1.resumes++;
        Model.Sess second = part("p2", 1_200_000L, 300);
        fileResumed(log, p1, second, 25.0);
        // STOP again after the count was carried: this time the record already holds it.
        RunParts p2 = RunParts.ofRecord(second); p2.resumes++;
        Model.Sess third = part("p3", 1_600_000L, 100);
        boolean[] r = fileResumed(log, p2, third, 10.0);
        assertTrue(r[0]);
        assertEquals(1, log.all.size());
        assertEquals(75.0, third.netTupSec, 1e-9, "40 + 25 + 10, nothing twice");
    }

    @Test void anUncountedFirstPartIsAskedAboutAsBefore() {
        Model.SessLog log = new Model.SessLog();
        Model.Sess first = part("p1", 1_000_000L, 90);
        log.file(first);
        RunParts prior = RunParts.ofRecord(first); prior.resumes++;
        Model.Sess whole = part("p2", 1_200_000L, 300);
        boolean[] r = fileResumed(log, prior, whole, 25.0);
        assertFalse(r[0]);
        assertTrue(r[1], "the whole session is asked about, as it was");
        assertEquals(Model.TRAINER_TRACK_NONE, whole.countedTrack);
        assertNull(whole.netTupSec, "nothing counted until the answer");
    }

    @Test void theFilingCarriesTheCountBeforeItAsks() throws Exception {
        String sa = new String(Files.readAllBytes(Paths.get("../app/src/main/java/org/openpump/SessionActivity.java")),
            StandardCharsets.UTF_8);
        int carry = sa.indexOf("RunParts.carryCount(earlier, parts, rec,");
        int ask = sa.indexOf("pendingCountSessId = rec.id;");
        assertTrue(carry > 0 && ask > carry, "fileSession carries a counted first part before it would ask");
    }
}
