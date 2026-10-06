package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * C5 - A SUMMARY REOPENED FROM HISTORY SHOWS ITS OWN SESSION, NOT THE LAST LIVE RUN.
 *
 * Found on a device: a summary reopened from History read "cycles X of Y planned" from the
 * most recent LIVE run. The cycles row counted the Activity's recording and plan, which the
 * reopen never reset; the Noticed card set the session's peak against the live run's
 * commanded one, and the no-peak line gave the live run's reason. The Activity half is held
 * by WiringCheck invariant 115. This pins the other half: everything those lines say is now
 * derived from the filed record alone, so two sessions can never share a figure, and a
 * session filed before the figures were kept says nothing rather than something wrong.
 */
class ReopenedSummaryTest {

    private String unitBefore;
    @BeforeEach void kpa() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_KPA; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    private static Model.Sess filed(String id, int planned, int done) {
        Model.Sess s = new Model.Sess();
        s.id = id;
        s.durSec = 600;
        s.completed = done >= planned;
        s.presetsDone = 3;
        s.cyclesPlanned = planned;
        s.cyclesDone = done;
        return s;
    }

    /** A session as a phone that predates C5 saved it: none of the new keys. */
    private static Model.Sess oldRecord() throws Exception {
        return Model.Sess.fromJson(new JSONObject("{\"id\":\"sess-old\",\"rid\":\"r1\","
            + "\"ts\":\"1000\",\"dur\":\"540\",\"done\":true,\"peak\":\"20.0\",\"pd\":4,"
            + "\"pp\":4}"));
    }

    /* ------------------------------------------------------------------ the cycles row */

    @Test void theCyclesRowIsTheRecordsOwn() {
        assertEquals("8 of 14 planned",
            PlannedTime.rows(new Model(), filed("a", 14, 8), false).cycles);
        assertEquals("8 of 14 planned",
            PlannedTime.rows(new Model(), filed("a", 14, 8), true).cycles,
            "the same in either Set timing count - cycles are cycles");
    }

    @Test void aReopenedSessionShowsItsOwnCyclesNotTheLiveRuns() {
        // The device case: a stopped session (8 of 14), then a live run that went to plan
        // (39 of 39). Each summary states its own run, whichever was filed last.
        Model m = new Model();
        Model.Sess older = filed("older", 14, 8);
        Model.Sess live = filed("live", 39, 39);
        assertEquals("39 of 39 planned", PlannedTime.rows(m, live, false).cycles);
        assertEquals("8 of 14 planned", PlannedTime.rows(m, older, false).cycles);
    }

    @Test void aSealCheckAbortStillReadsNoneOfItsPlan() {
        // What the live summary printed before C5 for an attempt stopped before its first
        // cycle: a recording with nothing in it counts 0, the plan still counts its cycles.
        assertEquals("0 of 14 planned",
            PlannedTime.rows(new Model(), filed("a", 14, 0), false).cycles);
    }

    @Test void halfARecordComparesNothing() {
        assertNull(PlannedTime.rows(new Model(), filed("a", 14, -1), false).cycles,
            "no recording to count: no row, never '- of 14'");
        assertNull(PlannedTime.rows(new Model(), filed("a", 0, 8), false).cycles,
            "no plan recorded: nothing to compare with");
    }

    @Test void anOldRecordShowsNoCyclesRatherThanSomeoneElses() throws Exception {
        Model.Sess s = oldRecord();
        assertEquals(0, s.cyclesPlanned);
        assertEquals(-1, s.cyclesDone);
        assertNull(PlannedTime.rows(new Model(), s, false).cycles);
    }

    /* --------------------------------------------------------------- the Noticed card */

    /** The Noticed card's words exactly as SummaryScreen composed them before C5, from the
     *  live Activity fields - kept here so the live summary is proven unchanged. M4 renamed
     *  the test in them ("the tissue response test's after-pull", where it read "the
     *  after-assessment pull"); the composition this pins is otherwise the same. */
    private static String before(double peak, int cmd, int afterPull, int carriedIn,
                                 boolean fromPull, int noReads) {
        double shortfall = cmd - peak;
        String text = "Peak reached " + Model.Fmt.p(peak) + ", against "
             + Model.Fmt.p(cmd) + " the pump was asked for — "
             + Model.Fmt.d(peak - cmd) + ".";
        if (shortfall > 1.0)
            text += " Sustained shortfall usually means the seal, not the pump: check the "
                  + "cuff position, or lower the target for the next run.";
        if (afterPull > 0)
            text += "\nBoth figures include the tissue response test's after-pull at "
                  + Model.Fmt.p(afterPull) + ", which ran inside this session.";
        if (carriedIn > 0 && carriedIn >= cmd)
            text += "\nBoth figures include the " + Model.Fmt.p(carriedIn)
                  + " already on the cuff when the routine started — the "
                  + (fromPull ? "tissue response test's before-pull" : "seal check")
                  + " does not vent on its way out.";
        if (noReads > 0)
            text += "\n" + noReads + " reading"
                  + (noReads == 1 ? "" : "s") + " from the pump had no "
                  + "pressure in " + (noReads == 1 ? "it and was" : "them and were")
                  + " left out of the dose — none was counted as 0 kPa.";
        return text;
    }

    private static Model.Sess noticedRecord(double peak, int cmd, int afterPull, int carriedIn,
                                            boolean fromPull, int noReads) {
        Model.Sess s = filed("n", 10, 10);
        s.peakKpa = Double.valueOf(peak);
        s.cmdPeakKpa = Double.valueOf(cmd);
        s.afterPullKpa = afterPull;
        s.carriedInKpa = carriedIn;
        s.carriedFromPull = fromPull;
        s.noReadSamples = noReads;
        return s;
    }

    @Test void theLiveNoticedWordingIsUnchanged() {
        int[][] cases = {
            // peak x10, cmd, afterPull, carriedIn, fromPull (0/1), noReads
            { 200, 24, 0, 0, 0, 0 },      // a shortfall
            { 239, 24, 0, 0, 0, 0 },      // within a kPa
            { 250, 20, 20, 0, 0, 1 },     // the after-pull, and one reading with no pressure
            { 200, 20, 0, 20, 0, 0 },     // carried in from the seal check
            { 200, 22, 0, 22, 1, 3 },     // carried in from the before-pull, three no-reads
            { 150, 24, 0, 12, 1, 0 },     // carried in, but below the routine's own ask
        };
        for (int i = 0; i < cases.length; i++) {
            int[] c = cases[i];
            double peak = c[0] / 10.0;
            assertEquals(before(peak, c[1], c[2], c[3], c[4] == 1, c[5]),
                Summary.noticed(noticedRecord(peak, c[1], c[2], c[3], c[4] == 1, c[5]), false),
                "case " + i);
        }
        // ...in the unit the device shows, too.
        Model.Fmt.unit = Model.Fmt.U_INHG;
        assertEquals(before(20.0, 22, 0, 22, true, 3),
            Summary.noticed(noticedRecord(20.0, 22, 0, 22, true, 3), false));
    }

    @Test void eachSessionIsSetAgainstItsOwnAsk() {
        Model.Sess older = noticedRecord(20.0, 24, 0, 0, false, 0);
        Model.Sess live = noticedRecord(28.0, 28, 0, 0, false, 0);
        assertTrue(Summary.noticed(older, false).startsWith(
            "Peak reached 20.0 kPa, against 24.0 kPa the pump was asked for — −4.0 kPa."));
        assertTrue(Summary.noticed(live, false).startsWith(
            "Peak reached 28.0 kPa, against 28.0 kPa the pump was asked for — +0.0 kPa."));
    }

    @Test void anOldRecordStatesItsPeakAndComparesNothing() throws Exception {
        String t = Summary.noticed(oldRecord(), false);
        assertEquals("Peak reached 20.0 kPa. What the pump was asked for was not kept with "
            + "sessions this old, so there is nothing to compare it with.", t);
        assertFalse(t.contains("asked for —"));
    }

    @Test void nothingDeliveredAndNoPeakReadTheRecord() {
        assertTrue(Summary.noticed(filed("a", 10, 0), true).startsWith("None of the routine ran"));
        Model.Sess noPeak = filed("a", 10, 4);
        assertTrue(Summary.noticed(noPeak, false).startsWith("The pump sent no readings"));
        assertTrue(Summary.noticed(null, false).startsWith("The pump sent no readings"));
    }

    /* ---------------------------------------------------------------- the no-peak line */

    @Test void theNoPeakLineGivesTheRecordsOwnReason() {
        Model.Sess s = filed("a", 10, 0);
        s.noPeakWhy = Summary.NO_PEAK_NEVER_RAN;
        assertTrue(Summary.noPeakLine(s).startsWith("the routine never started"));
        s.noPeakWhy = Summary.NO_PEAK_NO_READINGS;
        assertTrue(Summary.noPeakLine(s).startsWith("the pump sent no readings"));
        s.noPeakWhy = Summary.NO_PEAK_NO_MEASUREMENT;
        assertTrue(Summary.noPeakLine(s).startsWith("no reading during this session carried"));
    }

    @Test void anOldRecordsNoPeakLineClaimsNoReason() throws Exception {
        Model.Sess s = oldRecord();
        s.peakKpa = null;
        String t = Summary.noPeakLine(s);
        assertEquals("no measured pressure was recorded for this session, so there is "
            + "nothing to report as delivered", t);
        assertEquals(t, Summary.noPeakLine(null));
    }

    /* ------------------------------------------------------------------ persistence */

    @Test void everyFiledFigureSurvivesASave() throws Exception {
        Model.Sess s = noticedRecord(20.0, 22, 20, 22, true, 3);
        s.cyclesPlanned = 14;
        s.cyclesDone = 8;
        s.noPeakWhy = Summary.NO_PEAK_NO_MEASUREMENT;
        Model.Sess back = Model.Sess.fromJson(s.toJson());
        assertEquals(14, back.cyclesPlanned);
        assertEquals(8, back.cyclesDone);
        assertEquals(22.0, back.cmdPeakKpa.doubleValue(), 0.0);
        assertEquals(20, back.afterPullKpa);
        assertEquals(22, back.carriedInKpa);
        assertTrue(back.carriedFromPull);
        assertEquals(3, back.noReadSamples);
        assertEquals(Summary.NO_PEAK_NO_MEASUREMENT, back.noPeakWhy);
        assertEquals(Summary.noticed(s, false), Summary.noticed(back, false),
            "the reopened summary says what the live one said");
    }

    @Test void anOldRecordReadsNotRecordedThroughout() throws Exception {
        Model.Sess s = oldRecord();
        assertNull(s.cmdPeakKpa);
        assertEquals(0, s.afterPullKpa);
        assertEquals(0, s.carriedInKpa);
        assertFalse(s.carriedFromPull);
        assertEquals(0, s.noReadSamples);
        assertEquals(Summary.NO_PEAK_UNKNOWN, s.noPeakWhy);
        // ...and saving it keeps it that way: "not recorded" is not turned into a zero.
        Model.Sess back = Model.Sess.fromJson(s.toJson());
        assertNull(back.cmdPeakKpa);
        assertEquals(-1, back.cyclesDone);
        assertEquals(0, back.cyclesPlanned);
    }
}
