package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * A REJOINED RUN IS ONE SESSION (device check EMU9c, seen-not-counted 1; the owner's pick,
 * option A). After "Rejoin the run" the summary read "Duration 0:44 of 40:55 planned" and
 * Today's "Last session" 0:44: the five minutes played before the app closed were nowhere.
 * Both parts now count as one session - the time, the time at pressure the plan reads, the
 * dose, the peak and the cycles - and the summary says "Rejoined once after the app closed".
 * Resume after STOP has the same gap and the same answer. When the first part was already
 * filed (STOP files it; so does a screen destroyed mid-run), the one record replaces it, so
 * nothing counts twice.
 */
class RunPartsTest {

    private static Model.Sess part(String id, long ts, long durSec, Double net, Double gross,
                                   double dose, Double peak, int cycles) {
        Model.Sess s = new Model.Sess();
        s.id = id; s.ts = ts; s.durSec = durSec;
        s.netTupSec = net; s.grossTupSec = gross;
        s.doseKpaS = dose; s.peakKpa = peak; s.cyclesDone = cycles;
        return s;
    }

    /* ------------------------------------------- the device case: killed, rejoined */

    @Test void theAppClosedMidRunAndTheRejoinCountsBothParts() {
        // What the snapshot held when the process died: 5:07 played, from its start.
        RunParts before = RunParts.sofar(null, 1_000_000L, 307_000L, 200.0, 250.0, 1000.0,
                                         30.0, 4);
        RunParts back = RunParts.fromCode(before.code());
        assertNotNull(back, "it survives the snapshot");
        back.rejoins++;                                   // "Rejoin the run"
        // The part after the rejoin, filed as it always was: 0:44 from its own start.
        Model.Sess rec = part("sess9", 2_000_000L, 44, 20.0, 30.0, 100.0, 28.0, 1);
        RunParts.foldTime(back, rec);
        RunParts.foldFigures(back, rec);
        assertEquals(351L, rec.durSec, "5:07 + 0:44");
        assertEquals(1_000_000L, rec.ts, "the session started when the first part did");
        assertEquals(220.0, rec.netTupSec.doubleValue(), 1e-9, "time at pressure, both parts");
        assertEquals(280.0, rec.grossTupSec.doubleValue(), 1e-9);
        assertEquals(1100.0, rec.doseKpaS, 1e-9);
        assertEquals(30.0, rec.peakKpa.doubleValue(), 1e-9, "the higher peak of the two");
        assertEquals(5, rec.cyclesDone);
        assertEquals(1, rec.rejoins);
        assertEquals("Rejoined once after the app closed", RunParts.line(rec.rejoins, rec.resumes));
    }

    /* --------------------------------- the first part already filed: never twice */

    @Test void aFirstPartAlreadyFiledIsReplacedNotCountedAgain() {
        Model.SessLog log = new Model.SessLog();
        Model.Sess older = part("sess1", 500L, 120, 50.0, 60.0, 10.0, 20.0, 2);
        Model.Sess first = part("sess2", 1_000_000L, 300, 100.0, 120.0, 500.0, 29.0, 3);
        log.file(older);
        log.file(first);
        RunParts prior = RunParts.ofRecord(first);
        prior.resumes++;                                  // STOP, then Resume
        Model.Sess rec = part("sess3", 1_400_000L, 60, 10.0, 12.0, 50.0, 31.0, 1);
        Model.Sess dropped = RunParts.dropEarlier(log.all, prior);
        assertSame(first, dropped);
        RunParts.foldTime(prior, rec);
        RunParts.foldFigures(prior, rec);
        log.file(rec);
        assertEquals(2, log.all.size(), "one session for the two parts, the older one kept");
        long total = 0;
        for (int i = 0; i < log.all.size(); i++) total += log.all.get(i).durSec;
        assertEquals(120 + 300 + 60, total, "nothing counted twice");
        assertEquals(360L, rec.durSec);
        assertEquals(110.0, rec.netTupSec.doubleValue(), 1e-9);
        assertEquals(1_000_000L, rec.ts);
        assertEquals(1, rec.resumes);
        assertEquals("Resumed once after STOP", RunParts.line(0, rec.resumes));
    }

    @Test void aRejoinOfARejoinKeepsTheFiledRecordAndTheCounts() {
        Model.Sess first = part("sess2", 1_000L, 300, 100.0, 120.0, 0.0, null, 3);
        RunParts p = RunParts.ofRecord(first);
        p.resumes++;
        // The resumed part runs 90 s more, then the app closes: the snapshot's figures.
        RunParts snap = RunParts.sofar(p, 9_000L, 90_000L, 30.0, 40.0, 0.0, null, 1);
        RunParts back = RunParts.fromCode(snap.code());
        back.rejoins++;
        assertEquals("sess2", back.sessId, "the record to replace travels with the snapshot");
        assertEquals(1_000L, back.startTs);
        assertEquals(390_000L, back.playedMs);
        assertEquals(130.0, back.netSec, 1e-9);
        assertEquals(4, back.cycles);
        assertEquals("Rejoined once after the app closed · Resumed once after STOP",
            RunParts.line(back.rejoins, back.resumes));
    }

    @Test void figuresNobodyRecordedStayUnrecorded() {
        RunParts p = RunParts.sofar(null, 1_000L, 60_000L, null, null, 0.0, null, -1);
        Model.Sess rec = part("s", 70_000L, 10, null, null, 0.0, null, -1);
        RunParts.foldTime(p, rec);
        RunParts.foldFigures(p, rec);
        assertNull(rec.netTupSec, "unknown plus unknown is unknown, never 0");
        assertNull(rec.peakKpa);
        assertEquals(-1, rec.cyclesDone);
        assertEquals(70L, rec.durSec);
    }

    @Test void noPriorChangesNothing() {
        Model.Sess rec = part("s", 70_000L, 10, 5.0, 6.0, 1.0, 2.0, 1);
        RunParts.foldTime(null, rec);
        RunParts.foldFigures(null, rec);
        assertEquals(10L, rec.durSec);
        assertEquals(70_000L, rec.ts);
        assertEquals(0, rec.rejoins);
        assertNull(RunParts.line(0, 0));
        assertNull(RunParts.dropEarlier(new Model.SessLog().all, null));
    }

    @Test void theLineCountsWhenMoreThanOnce() {
        assertEquals("Rejoined 2 times after the app closed", RunParts.line(2, 0));
        assertEquals("Resumed 3 times after STOP", RunParts.line(0, 3));
    }

    @Test void anOddOrMissingSnapshotReadsAsNoPriorPart() {
        assertNull(RunParts.fromCode(""));
        assertNull(RunParts.fromCode(null));
        assertNull(RunParts.fromCode("garbage;1;2"));
    }

    @Test void theCountsSurviveASaveAndAnOldRecordReadsNone() throws Exception {
        Model.Sess old = Model.Sess.fromJson(new Model.Sess().toJson());
        assertEquals(0, old.rejoins);
        assertEquals(0, old.resumes);
        assertFalse(new Model.Sess().toJson().has("rejn"), "an unjoined record is as it was");
        old.rejoins = 2; old.resumes = 1;
        Model.Sess back = Model.Sess.fromJson(old.toJson());
        assertEquals(2, back.rejoins);
        assertEquals(1, back.resumes);
    }

    /* ----------------------------------------------------------- the wiring */

    private static String code(String file) throws Exception {
        return NoBookNamesTest.stripComments(new String(Files.readAllBytes(
            Paths.get("../app/src/main/java/org/openpump/" + file)), StandardCharsets.UTF_8));
    }

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

    @Test void theRunCarriesItsEarlierPartsToTheFiling() throws Exception {
        String sa = code("SessionActivity.java");
        String file = body(sa, "private void fileSession(");
        assertTrue(file.contains("RunParts.dropEarlier("), "a first part already filed is replaced");
        assertTrue(file.contains("RunParts.foldTime(") && file.contains("RunParts.foldFigures("));
        assertTrue(body(sa, "private void saveRunSnapshot()").contains("RunParts.sofar("),
            "the snapshot keeps what was played, for a death with no filing");
        assertTrue(body(sa, "private boolean offerRunSnapshot()").contains("RunParts.fromCode("));
        assertTrue(body(sa, "private final class RejoinRunTap").contains("runPrior = spot.prior"));
        assertTrue(body(sa, "private void stopWithResumeOffer()").contains("RunParts.ofRecord("));
        assertTrue(body(sa, "private void startSession(").contains("runPrior = null"),
            "a fresh run carries no earlier part");
        assertTrue(code("SummaryScreen.java").contains("RunParts.line("),
            "the summary says it was rejoined");
    }
}
