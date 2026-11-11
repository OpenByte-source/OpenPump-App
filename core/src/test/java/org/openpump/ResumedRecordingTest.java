package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * STOP, THEN RESUME, KEEPS THE WORK BEFORE STOP (the live GrowthTrack test of 9 Nov 2026,
 * session f).
 *
 * asrun.json is keyed by the session's start. STOP files the first part under its start; the
 * resumed session files under the SAME start (RunParts#foldTime), and its recording replaced the
 * first part's. Session f: filed at 542 s, its recording 7:33 long (the part after Resume), so
 * GrowthTrack was sent sets for 449 s and the summary's save card and History's detail showed
 * only the second part.
 *
 * The fix: the one session files both parts' recordings (RunParts#recording, AsRun#join), the
 * first part's rows as recorded, the resumed part's after them on the session's clock.
 * Fictional records only; nothing here can reach a network.
 */
class ResumedRecordingTest {
    private static final long START = 1_794_246_991_084L;

    private static AsRun.Row row(long t0, long t1, int pos, int ord, String set, int up, int lo, int uh, int lh) {
        AsRun.Row r = new AsRun.Row();
        r.kind = AsRun.PLAN; r.t0 = t0; r.t1 = t1; r.stageIdx = 0; r.pos = pos; r.ordinal = ord;
        r.setId = set; r.up = up; r.upReq = up; r.lo = lo; r.uhWire = uh; r.uhReq = uh; r.lh = lh;
        r.sp = 60; r.cyclePart = 0;
        return r;
    }

    /** The first part: "Gentle Hold" (40 s holds, 8 s drops) until STOP at 1:29. */
    private static AsRun firstPart() {
        AsRun a = new AsRun("r1", "Warm + Build", null, false, 40);
        a.rows.add(row(980, 89_432, 0, 0, "s1", 14, 7, 40, 8));
        a.ts = START;
        return a;
    }

    /** The part after Resume, as session f recorded it: Gentle Hold again, then the ramp. */
    private static AsRun resumedPart() {
        AsRun a = new AsRun("r1", "Warm + Build", null, false, 40);
        a.rows.add(row(1_027, 241_013, 0, 0, "s1", 14, 7, 40, 8));
        a.rows.add(row(241_013, 276_079, 1, 0, "s4", 18, 9, 30, 5));
        a.rows.add(row(276_079, 314_970, 1, 1, "s4", 22, 9, 34, 5));
        a.rows.add(row(314_970, 357_968, 1, 2, "s4", 26, 10, 38, 5));
        a.rows.add(row(357_968, 404_054, 1, 3, "s4", 30, 10, 41, 5));
        a.rows.add(row(404_054, 453_977, 1, 4, "s4", 34, 10, 45, 5));
        return a;
    }

    private static Model.Sess filedFirst() {
        Model.Sess s = new Model.Sess();
        s.id = "sess1794247079084"; s.ts = START; s.durSec = 89; s.completed = false;
        s.routineName = "Warm + Build"; s.routineId = "r1"; s.peakKpa = 14.0; s.cyclesDone = 1;
        s.recordedStartMs = START; s.recordedEndMs = 1_794_247_079_084L; s.hasAsRun = true;
        return s;
    }

    /** fileSession's own order for the resumed part, with asrun.json as a map by ts. */
    private static Model.Sess fileResumed(Map<Long, AsRun> asrun, List<Model.Sess> log, RunParts prior,
                                          AsRun live, long now, long elMs) {
        Model.Sess earlier = RunParts.dropEarlier(log, prior);
        Model.Sess rec = new Model.Sess();
        long el = elMs / 1000L;
        rec.id = "sess" + now; rec.ts = now - el * 1000L; rec.durSec = el; rec.completed = true;
        rec.routineName = "Warm + Build"; rec.routineId = "r1"; rec.peakKpa = 34.0;
        RunParts.foldTime(prior, rec);
        rec.recordedEndMs = now;
        live.close(elMs);
        AsRun filed = RunParts.recording(prior, earlier == null ? null : asrun.get(earlier.ts), live);
        filed.ts = rec.ts;
        rec.hasAsRun = !filed.rows.isEmpty();
        asrun.put(rec.ts, filed);
        RunParts.foldFigures(prior, rec);
        log.add(rec);
        return rec;
    }

    @Test void theResumedSessionsRecordingKeepsThePartBeforeStop() throws Exception {
        Map<Long, AsRun> asrun = new HashMap<Long, AsRun>();
        Model.Sess first = filedFirst();
        asrun.put(first.ts, firstPart());
        List<Model.Sess> log = new ArrayList<Model.Sess>(Arrays.asList(first));
        RunParts prior = RunParts.ofRecord(first); prior.resumes++;
        AsRun live = resumedPart();

        Model.Sess whole = fileResumed(asrun, log, prior, live, 1_794_247_536_111L, 453_977L);
        assertEquals(542, whole.durSec);
        assertEquals(START, whole.ts, "one key for the one session: the first part's start");
        AsRun filed = asrun.get(whole.ts);
        assertNotNull(filed);
        assertEquals(7, filed.rows.size(), "both parts' rows: 1 before STOP, 6 after Resume");
        // The first part keeps its own times; the resumed part follows it on the session clock.
        assertEquals(980, filed.rows.get(0).t0);
        assertEquals(89_000, filed.rows.get(0).t1, "closed at the whole seconds the part was filed for");
        assertEquals(0, filed.rows.get(0).part);
        assertEquals(89_000 + 1_027, filed.rows.get(1).t0);
        assertEquals(89_000 + 453_977, filed.rows.get(6).t1);
        for (int i = 1; i < filed.rows.size(); i++) {
            assertEquals(1, filed.rows.get(i).part);
            assertTrue(filed.rows.get(i).t0 >= filed.rows.get(i - 1).t1, "time-ordered, no overlap at row " + i);
        }
        // The live part's own recording is untouched (its cycles are counted from it alone).
        assertEquals(1_027, live.rows.get(0).t0);
        assertEquals(0, live.rows.get(0).part);

        // GrowthTrack: the same id and start as before (bug 5), now with both parts' work.
        whole.recordedStartMs = whole.recordedStartMs > 0 ? whole.recordedStartMs : START;
        JSONObject p = GrowthTrackSession.draft(whole, filed).payload();
        assertEquals("op_sess1794247079084", p.getString("external_session_id"));
        assertEquals(542, p.getInt("total_duration_seconds"));
        int sum = 0; JSONArray sets = p.getJSONArray("sets");
        for (int i = 0; i < sets.length(); i++) if (!sets.getJSONObject(i).isNull("duration_seconds"))
            sum += sets.getJSONObject(i).getInt("duration_seconds");
        assertTrue(sum >= 535 && sum <= 542, "sets cover both parts: " + sum + " s of 542 (was 449 with the first part lost)");

        // What the app used to file - the resumed part alone - is the live test's shortfall.
        JSONObject before = GrowthTrackSession.draft(whole, resumedPart()).payload();
        int lost = 0; JSONArray b = before.getJSONArray("sets");
        for (int i = 0; i < b.length(); i++) lost += b.getJSONObject(i).getInt("duration_seconds");
        assertTrue(lost < 455, "the resumed part alone covers " + lost + " s");
    }

    @Test void aRecordingInOnePartFilesAsItAlwaysDid() {
        AsRun live = resumedPart();
        assertSame(live, RunParts.recording(null, null, live), "no earlier part: this part, unchanged");
        RunParts rejoinedAfterDeath = new RunParts();
        rejoinedAfterDeath.playedMs = 60_000L;
        assertSame(live, RunParts.recording(rejoinedAfterDeath, null, live),
            "no filed recording to join: this part, unchanged");
    }

    @Test void thePartNumberRoundTripsAndOldRowsReadAsOnePart() throws Exception {
        AsRun.Row old = AsRun.Row.fromJson(new JSONObject("{\"k\":0,\"t0\":\"0\",\"t1\":\"1000\"}"));
        assertEquals(0, old.part);
        assertFalse(old.toJson().has("part"), "a one-part row is written as before");
        AsRun joined = AsRun.join(firstPart(), resumedPart(), 89_000L);
        AsRun back = AsRun.fromJson(joined.toJson());
        assertEquals(1, back.rows.get(6).part);
        assertEquals(joined.rows.get(6).t1, back.rows.get(6).t1);
    }

    /** The save card and History read blocks: a step played again after Resume is a second
     *  occurrence stopped by STOP, not one step that ran long - nothing to save. */
    @Test void aStepPlayedAgainAfterResumeIsNotALongerStep() {
        Model m = Model.seed();
        Model.Routine r = m.routines.get(0);
        GtSim one = new GtSim(m, r);
        one.open(one.plan.get(0));
        one.t += one.plan.get(0).durMs / 3;               // STOP a third of the way in
        one.run.close(one.t);
        GtSim two = new GtSim(m, r);
        two.playAll();
        two.run.close(two.t);
        assertFalse(AsRun.differs(AsRun.blocks(two.run, m)), "the resumed part alone ran as planned");
        AsRun joined = AsRun.join(one.run, two.run, one.t / 1000L * 1000L);
        assertFalse(AsRun.differs(AsRun.blocks(joined, m)),
            "both parts: the first stopped early, the second as planned - no change to offer");
    }
}
