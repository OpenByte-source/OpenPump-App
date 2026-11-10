package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * TIME UNDER TENSION FOR OWN ROUTINES AND MANUAL RUNS (the live GrowthTrack test, finding 4;
 * the owner's pick B, 9 Nov 2026): the time the measured pressure was held at or beyond each
 * step's OWN commanded pull, judged with the plan's own tolerance and exclusions. Seven of the
 * live test's eight sessions had no time under tension at all: only a plan session is scored
 * against a track floor, and every routine and manual run filed null.
 */
class OwnPullTupTest {
    private static final double FRAC = 0.02;          // Model#tupCountPct's default, 2 %

    /** A frame every 250 ms, recorded as SessionActivity#recordHoldFrame records it. */
    private static long frames(Session s, long t, int n, double kpa, double commanded,
                               boolean outOfNet, boolean drop) {
        for (int i = 0; i < n; i++) {
            t += 250;
            s.noteHoldFrame(t, kpa, false, commanded, Session.HOLD_PHASE_HOLD, 0, outOfNet, drop, commanded > 0);
        }
        return t;
    }

    @Test void heldAtEachStepsOwnPullCountsTheHoldsOnly() {
        Session s = new Session();
        s.beginRun(0L);
        long t = 0;
        t = frames(s, t, 120, 9.9, 10.0, false, false);  // 30 s held at -3.0 inHg (within 2 %)
        t = frames(s, t, 40, 3.5, 10.0, false, true);    // 10 s drop: never
        t = frames(s, t, 40, 0.3, 0.0, false, false);    // 10 s rest, nothing commanded: never
        t = frames(s, t, 80, 8.0, 10.0, false, false);   // 20 s under the step's pull: never
        t = frames(s, t, 120, 19.7, 20.0, false, false); // a live change to -5.9: its own line
        t = frames(s, t, 40, 17.0, 20.0, false, false);  // well under that line: never
        t = frames(s, t, 40, 9.0, 8.0, true, false);     // a warm-up stage: never, as for the plan
        double[] tup = s.ownPullTupSec(FRAC);
        assertEquals(60.0, tup[0], 0.3, "30 s at -3.0 and 30 s at -5.9, each against its own pull");
        assertEquals(s.netGrossTupSec(10.0, 0.2)[1], tup[1], 1e-9, "gross is the plan's gross");
    }

    @Test void aStartCheckMidRunIsNotTimeUnderTension() {
        Session s = new Session();
        s.beginRun(0L);
        long t = 0;
        for (int i = 0; i < 40; i++) {                   // the guided pull after a step by hand
            t += 250;
            s.noteHoldFrame(t, 17.0, false, 6.0, Session.HOLD_PHASE_HOLD, 1, false, false, true);
            s.noteStartCheckFrame();
        }
        t = frames(s, t, 40, 6.0, 6.0, false, false);    // the step's own 10 s
        assertEquals(10.0, s.ownPullTupSec(FRAC)[0], 0.3);
    }

    @Test void theToleranceIsThePlansCountingThreshold() {
        Session s = new Session();
        s.beginRun(0L);
        frames(s, 0, 40, 19.0, 20.0, false, false);      // 5 % under
        assertEquals(0.0, s.ownPullTupSec(0.02)[0], 1e-9, "outside a 2 % threshold");
        assertEquals(10.0, s.ownPullTupSec(0.05)[0], 0.3, "inside a 5 % one");
    }

    @Test void aSessionFiledThisWayIsMarkedAndNeverMovesThePlan() throws Exception {
        Model.Sess s = new Model.Sess();
        s.id = "sess1"; s.ts = 1L; s.durSec = 180; s.netTupSec = Double.valueOf(120.0); s.tupOwnPull = true;
        Model.Sess back = Model.Sess.fromJson(s.toJson());
        assertTrue(back.tupOwnPull);
        Model.Sess old = Model.Sess.fromJson(new org.json.JSONObject("{\"id\":\"sess2\",\"ts\":\"1\",\"dur\":\"60\"}"));
        assertFalse(old.tupOwnPull);
        assertFalse(old.toJson().has("tupOwn"));
        // The plan's figure: null for a session timed against its own pull, unless counted.
        assertNull(Summary.planNetSec(back), "an own routine's time never reads as the level's");
        back.countedTrack = Plan.TRACK_GIRTH_INTERVAL; back.tupOwnPull = false;
        assertEquals(120.0, Summary.planNetSec(back), 1e-9, "counted, it is the plan's again");
        // The summary's level line is a plan line: not for an own routine's time.
        Model m = new Model();
        PlannedTime.Rows rows = PlannedTime.rows(m, s, true);
        assertNull(rows.levelLabel, "no level target under an own routine's time");
        assertEquals(PlannedTime.LABEL_UNDER_PRESSURE, rows.tupLabel, "\"time under pressure\" fits a routine too");
    }

    @Test void theFilingComputesItForRoutinesAndManualRunsOnly() throws Exception {
        String sa = new String(Files.readAllBytes(Paths.get("../app/src/main/java/org/openpump/SessionActivity.java")),
            StandardCharsets.UTF_8);
        assertTrue(sa.contains("session.ownPullTupSec(model.tupCountTolKpa(1.0))"), "the plan's tolerance, as a fraction");
        assertTrue(sa.contains("rec.tupOwnPull = ownPull;"), "the record says how it was counted");
        assertTrue(sa.contains("session.noteStartCheckFrame()"), "start checks are marked");
        assertTrue(sa.contains("Summary.planNetSec(filedSession)"), "\"Toward the next level\" reads the plan's figure");
    }
}
