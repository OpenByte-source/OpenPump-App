package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.junit.jupiter.api.Test;

/**
 * A REJOINED RUN AND ITS DURATION ARE ON ONE CLOCK (run 4 of the live GrowthTrack test, 10 Nov
 * 2026, check 3).
 *
 * Starter Routine, the app force-stopped about 62 s in, rejoined, run to its end. The joined
 * recording (RunParts#recording) moves the rejoined part's rows by what the snapshot counted the
 * killed part as having played, in ms (61.973 s), so it ended at 242.866 s. The session's
 * duration added the parts in whole seconds, each truncated on its own (61 + 180 = 241), and
 * GrowthTrack's draft - which allows the recording one second past the duration, the whole
 * second the duration rounds down - refused it: "Not sent: The detailed recording has
 * inconsistent timing". Now the parts' played ms are added first and then counted in whole
 * seconds, as the recording counts them: 242 s, the recording inside it.
 *
 * The time the app was dead is in neither: both count only time played (the wall-clock start
 * and end, which span it, are sent beside them). Figures from the run 4 data copies; nothing
 * here can reach a network.
 */
class RejoinTimeBaseTest {
    private static final long START = 1_794_346_622_220L;     // run 4, check 3: the killed part's start

    /** Starter Routine's one step: "Starter Pull", 10/5 kPa, 30 s holds, 8 s drops. */
    private static AsRun.Row row(long t0, long t1) {
        AsRun.Row r = GrowthTrackIntegrationTest.row(AsRun.PLAN, t0, t1, 0);
        r.setId = "s5"; r.up = 10; r.upReq = 10; r.lo = 5; r.uhWire = 30; r.uhReq = 30; r.lh = 8;
        r.sp = 50; r.cyclePart = 0;
        return r;
    }

    private static AsRun part(long t0, long t1) {
        AsRun a = new AsRun("r3", "Starter Routine", null, false, 40);
        a.rows.add(row(t0, t1));
        return a;
    }

    /** fileSession's own order (SessionActivity#fileSession, #asRunFile), asrun.json by key. */
    private static Model.Sess file(Map<Long, AsRun> asrun, List<Model.Sess> log, RunParts prior,
                                   AsRun priorRows, AsRun live, long now, long elMs, boolean done) {
        Model.Sess earlier = RunParts.dropEarlier(log, prior);
        Model.Sess rec = GrowthTrackIntegrationTest.session("sess" + now);
        long el = elMs / 1000L;
        rec.ts = el > 0 ? now - el * 1000L : now;
        rec.durSec = el;
        rec.recordedStartMs = 0L;
        RunParts.foldTime(prior, rec, elMs);
        if (rec.recordedStartMs <= 0) rec.recordedStartMs = rec.ts;
        rec.recordedEndMs = now;
        rec.completed = done;
        rec.routineId = "r3"; rec.routineName = "Starter Routine";
        rec.netTupSec = null;
        live.close(elMs);
        AsRun earlierRun = priorRows != null ? priorRows
            : earlier == null || !earlier.hasAsRun ? null : asrun.get(earlier.ts);
        AsRun filed = RunParts.recording(prior, earlierRun, live);
        filed.ts = rec.ts;
        rec.hasAsRun = !filed.rows.isEmpty();
        asrun.put(rec.ts, filed);
        RunParts.foldFigures(prior, rec);
        log.add(rec);
        return rec;
    }

    private static long end(AsRun run) {
        long t = 0;
        for (AsRun.Row r : run.rows) t = Math.max(t, r.t1);
        return t;
    }

    /** What the outbox does with it: a draft, or the reason it is "Not sent". */
    private static int sentSeconds(Model.Sess s, AsRun run) throws Exception {
        JSONArray sets = GrowthTrackSession.draft(s, run).payload().getJSONArray("sets");
        int sum = 0;
        for (int i = 0; i < sets.length(); i++) sum += sets.getJSONObject(i).optInt("duration_seconds", 0);
        return sum;
    }

    @Test void aRunRejoinedAfterAKillIsSentWhole() throws Exception {
        Map<Long, AsRun> asrun = new HashMap<Long, AsRun>();
        List<Model.Sess> log = new ArrayList<Model.Sess>();
        // Killed: nothing filed, its row still open under the provisional key; the snapshot had
        // counted 61.973 s ("p3;1794346622220;61973;...;;;29.060").
        AsRun killed = part(839, -1L);
        RunParts prior = RunParts.sofar(null, START, 61_973L, null, null, 0.0, null, -1);
        prior.rejoins++;
        AsRun rows = RunParts.priorRows(killed, null);
        // Rejoined: the step again from 0:00, to its end; filed at 1794346925127.
        AsRun rejoined = part(960, 180_893);
        Model.Sess s = file(asrun, log, prior, rows, rejoined, 1_794_346_925_127L, 180_912L, true);
        AsRun filed = asrun.get(s.ts);
        int sent = sentSeconds(s, filed);     // run 4: "The detailed recording has inconsistent timing"

        assertEquals(START, s.ts);
        assertEquals(242_866, end(filed), "the joined recording, as run 4 filed it");
        assertEquals(242, s.durSec, "61.973 s + 180.912 s played: 242 s, not 61 + 180");
        assertTrue(end(filed) < (s.durSec + 1) * 1000L, "the recording inside the duration's last second");
        assertTrue(sent >= 240, "both parts' work goes to GrowthTrack: " + sent + " s of " + s.durSec);
    }

    @Test void aRejoinedRunStoppedAndResumedIsSentAtEachFiling() throws Exception {
        Map<Long, AsRun> asrun = new HashMap<Long, AsRun>();
        List<Model.Sess> log = new ArrayList<Model.Sess>();
        RunParts prior = RunParts.sofar(null, START, 61_973L, null, null, 0.0, null, -1);
        prior.rejoins++;
        // Rejoined, then STOP 95.8 s in: STOP files the session so far. Not resumed, this is
        // the one that is sent, once the offer ends.
        Model.Sess stopped = file(asrun, log, prior, RunParts.priorRows(part(839, -1L), null),
            part(960, 95_840), START + 200_000L, 95_840L, false);
        assertEquals(157, stopped.durSec, "61.973 + 95.840 s");
        assertTrue(sentSeconds(stopped, asrun.get(stopped.ts)) >= 150);
        // ...resumed after all, and finished: the one session again.
        RunParts back = RunParts.ofRecord(stopped);
        back.resumes++;
        Model.Sess whole = file(asrun, log, back, null, part(1_010, 120_640), START + 330_000L, 120_640L, true);
        assertEquals(1, log.size(), "one session");
        assertEquals(277, whole.durSec, "157 s filed at STOP + 120.640 s");
        assertTrue(end(asrun.get(whole.ts)) < (whole.durSec + 1) * 1000L);
        assertTrue(sentSeconds(whole, asrun.get(whole.ts)) >= 270);
    }

    @Test void aResumedRunKilledAndRejoinedIsSentWhole() throws Exception {
        // Run 4 check 1's shape: Warm + Build stopped at 1:31 and resumed; then killed 30.7 s
        // into the resumed part, rejoined, and finished.
        Map<Long, AsRun> asrun = new HashMap<Long, AsRun>();
        List<Model.Sess> log = new ArrayList<Model.Sess>();
        long t0 = 1_794_345_181_584L;
        Model.Sess first = file(asrun, log, null, null, part(919, 91_420), t0 + 91_000L, 91_420L, false);
        assertEquals(91, first.durSec);
        RunParts resumed = RunParts.ofRecord(first);
        resumed.resumes++;
        // The resumed part's snapshot, 30.7 s in, holds its rows joined (RejoinRunTap persists them).
        AsRun provisional = RunParts.recording(resumed, asrun.get(first.ts), part(991, -1L));
        RunParts prior = RunParts.sofar(resumed, t0 + 200_000L, 30_700L, null, null, 0.0, null, -1);
        prior.rejoins++;
        Model.Sess whole = file(asrun, log, prior, RunParts.priorRows(provisional, null),
            part(960, 422_400), t0 + 700_000L, 422_400L, true);
        assertEquals(1, log.size());
        assertEquals(544, whole.durSec, "91 s + 30.700 s + 422.400 s");
        assertTrue(end(asrun.get(whole.ts)) < (whole.durSec + 1) * 1000L);
        assertTrue(sentSeconds(whole, asrun.get(whole.ts)) >= 535);
    }

    @Test void aPlainResumeKeepsItsDuration() throws Exception {
        // Run 4 check 1 itself: STOP at 1:31, Resume, finished. The earlier part comes from its
        // record in whole seconds, so this path already counted one clock; it files as before.
        Map<Long, AsRun> asrun = new HashMap<Long, AsRun>();
        List<Model.Sess> log = new ArrayList<Model.Sess>();
        long t0 = 1_794_345_181_584L;
        Model.Sess first = file(asrun, log, null, null, part(919, 91_420), t0 + 91_000L, 91_420L, false);
        RunParts back = RunParts.ofRecord(first);
        back.resumes++;
        Model.Sess whole = file(asrun, log, back, null, part(991, 453_826), 1_794_345_766_549L, 453_826L, true);
        assertEquals(544, whole.durSec, "as run 4 filed it");
        assertEquals(544_826, end(asrun.get(whole.ts)), "as run 4 recorded it");
        assertTrue(sentSeconds(whole, asrun.get(whole.ts)) >= 535);
    }

    @Test void everyLeftoverOfEveryPartStaysInsideTheLastSecond() throws Exception {
        // Whatever the parts' leftover milliseconds, a rejoined run's recording ends inside its
        // duration's last second: the second the duration rounds down, which the draft allows.
        long[] leftovers = {0, 1, 499, 500, 973, 999};
        for (long a : leftovers) for (long b : leftovers) {
            Map<Long, AsRun> asrun = new HashMap<Long, AsRun>();
            List<Model.Sess> log = new ArrayList<Model.Sess>();
            RunParts prior = RunParts.sofar(null, START, 61_000L + a, null, null, 0.0, null, -1);
            prior.rejoins++;
            long elMs = 180_000L + b;
            Model.Sess s = file(asrun, log, prior, RunParts.priorRows(part(839, -1L), null),
                part(960, elMs), START + 400_000L, elMs, true);
            AsRun filed = asrun.get(s.ts);
            assertEquals((61_000L + a + elMs) / 1000L, s.durSec, a + "/" + b);
            assertTrue(end(filed) <= s.durSec * 1000L + 999L, a + "/" + b);
            sentSeconds(s, filed);
        }
    }

    @Test void theFilingFoldsThePartsMilliseconds() throws Exception {
        String sa = new String(Files.readAllBytes(Paths.get("../app/src/main/java/org/openpump/SessionActivity.java")),
            StandardCharsets.UTF_8);
        assertTrue(sa.contains("RunParts.foldTime(parts, rec, elMs);"),
            "fileSession folds this part's own elapsed ms, the same elMs asRunFile closes the recording at");
        assertTrue(sa.contains("asRunFile(rec, elMs, earlier, parts, partsRows)"));
    }
}
