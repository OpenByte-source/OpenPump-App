package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * t10 R-40 - OPTION D (the owner, 1 Oct 2026): ONE AFTER-SESSION READING, JUDGED BY ITS
 * HISTORY. The length reading is one after-session pair - the after-reading of a length session
 * that pulled, against that session's own before-reading - with a target above 2 %. Under it for
 * a week the plan reads the BLOCK (the training since the last deload): it FELL (the block had a
 * reading at 2 % or over) -> the week off comes forward, no set; it NEVER REACHED -> +1 strain
 * set; it is STILL UNDER a week after that set -> the offer, once a block. Over 6 % it asks for
 * the measurement again, and two readings over it cut half a pound, one cut a week, never under
 * 5 lb. The 21-day fatigue deload is gone.
 *
 * The years are the owner's (7 months, length L3 at -10.0 inHg, Most -12, a 4.5 cm length
 * cylinder, 12.2 lb), a split week, a reading every 3 length sessions (LengthYear), against the
 * editor model's figures (t10/pull.js, VERIFY "Length readings · option D"). The regular deload
 * is taken on the Monday after it comes due, as the model's whole weeks have it.
 */
class OptionDTest {

    static LengthYear owner(int every, double pct, double late) {
        LengthYear y = new LengthYear();
        y.every = every;
        y.strainPct = pct;
        y.latePct = late;
        return y.setup().run();
    }

    private static List<Integer> weeks(Integer... w) { return Arrays.asList(w); }

    private static final List<Integer> REGULAR = weeks(5, 10, 15, 20, 25, 30, 35, 40, 45, 50);

    @Test void underWorkedAddsASetEachBlock() {
        LengthYear y = owner(3, 1.5, Double.NaN);
        // N24 (the owner, 2 Oct 2026): the low run restarts at each week off, so each block's
        // set comes a week into the block's own readings (8, not 7) and the run after it no
        // longer finishes before the next week off - the offer never comes (the editor's
        // model: D2 counts unchanged, D3 offers 5 -> 0).
        assertEquals(weeks(3, 8, 13, 18, 23, 28), y.d2, "D2 never reached, one set a block\n"
            + y.trace());
        assertEquals(6, y.sets[1]);
        assertEquals(12, y.sets[52], "6 -> 12 strain sets");
        assertTrue(y.d3.isEmpty(), "no D3: a week after each added set is the week off");
        assertEquals(REGULAR, y.deloads, "10 deloads, all on the regular cadence");
        assertTrue(y.d1Forward.isEmpty() && y.d1Anyway.isEmpty(), "it never reached: no D1");
    }

    @Test void tiredFallsEachBlockAndNoSetIsAdded() {
        // 3 % in each block's first training week, then 1.5 %.
        LengthYear y = owner(3, 3.0, 1.5);
        assertEquals(weeks(4, 9, 14, 19, 24, 29, 34, 39, 44, 49), y.d1Anyway,
            "D1 each block, each one with the regular week off next anyway\n" + y.trace());
        assertTrue(y.d1Forward.isEmpty());
        assertTrue(y.d2.isEmpty() && y.d3.isEmpty(), "no set is added");
        for (int w = 1; w <= 52; w++) assertEquals(6, y.sets[w], "6 strain sets all year");
        assertEquals(REGULAR, y.deloads);
    }

    @Test void tiredEverySessionBringsTheWeekOffForward() {
        LengthYear y = owner(1, 3.0, 1.5);
        assertEquals(13, y.d1Forward.size(), "13 D1 with the week off brought forward\n"
            + y.trace());
        assertTrue(y.d1Anyway.isEmpty());
        assertEquals(13, y.deloads.size(), "13 deloads - every one of them a D1");
        assertEquals(y.d1Forward, y.deloads);
        assertTrue(y.d2.isEmpty());
    }

    @Test void theWeekOffStartsTomorrowAsTheCardSays() {
        // The same tissue with the week off taken from the next morning (the card's words)
        // rather than the next Monday: every deload of the year is a D1 brought forward.
        LengthYear y = new LengthYear();
        y.every = 1; y.strainPct = 3.0; y.latePct = 1.5; y.wholeWeeks = false;
        y.setup().run();
        assertFalse(y.d1Forward.isEmpty(), y.trace());
        assertEquals(y.d1Forward, y.deloads, "every week off is the fall's\n" + y.trace());
        assertTrue(y.d2.isEmpty());
    }

    @Test void overTargetCutsToTheFloorAndHolds() {
        LengthYear y = owner(3, 7.0, Double.NaN);
        assertEquals(15, y.cutWeeks.size(), "15 cuts\n" + y.trace());
        double from = y.load[1];
        assertEquals(Traction.loadLbAtBore(34, LengthYear.BORE), from, 1e-9, "12.2 lb");
        // OPEN-5 (parity run 3): half a pound of the PULL, and the 5 lb floor is the pull's -
        // the plan's load plus the owner's offset (0.14 kPa, about 0.05 lb at the bore).
        double off = Scale.offsetLb(Scale.appliedOffsetKpa(y.m, Plan.TRACK_LENGTH, 7),
                                    LengthYear.BORE);
        double floor = Plan.LENGTH_LOAD_FLOOR_LB - off;
        for (int i = 0; i < y.cutTo.size(); i++) {
            double want = Math.max(Plan.LENGTH_LOAD_FLOOR_LB, from + off - 0.5 * (i + 1)) - off;
            assertEquals(want, y.cutTo.get(i).doubleValue(), 1e-9, "cut " + (i + 1));
        }
        assertEquals(floor, y.cutTo.get(14).doubleValue(), 1e-9);
        assertTrue(y.cutWeeks.get(0).intValue() <= 3, "the first cut a reading after the "
            + "first high: " + y.cutWeeks);
        assertTrue(y.cutWeeks.get(14).intValue() <= 40, "5.0 lb by about week 37: " + y.cutWeeks);
        for (int w = y.cutWeeks.get(14).intValue(); w <= 52; w++)
            assertEquals(floor, y.load[w], 1e-9, "then holds at a 5.0 lb pull");
        assertTrue(y.loadWeeks.isEmpty(), "no climb and no slow step while it reads high");
        assertTrue(y.d2.isEmpty() && y.d1Anyway.isEmpty() && y.d1Forward.isEmpty());
        assertEquals(REGULAR, y.deloads);
    }

    /* ---- the readings, one at a time -------------------------------------------------- */

    private static Model ownerModel() { return new LengthYear().setup().m; }

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    private static Plan.Decision eval(Model m, long now) {
        return Plan.evaluate(LengthYear.inputsFor(m, now));
    }

    @Test void aHighReadingThenAReMeasureInsideTheWindowCutsNothing() {
        Model m = ownerModel();
        LengthYear.measured(m, day(1, 9), "PL", 14.0);
        Plan.Decision first = eval(m, day(3, 8));
        assertEquals(Plan.ACTION_REMEASURE, first.action);
        assertEquals(Plan.LENGTH_HIGH_RULE, first.rule);
        assertEquals("Over 6% after the session: measure again.", first.reason);
        assertTrue(Double.isNaN(first.loadLb), "one high reading proposes no cut");
        // The re-measure the same day REPLACES the day's pair - not averaged (9.5 %).
        LengthYear.measured(m, day(1, 12), "PL", 5.0);
        assertEquals(5.0, Meas.strainPct(m, LengthTrack.METHOD, day(3, 8)), 1e-9);
        assertFalse(eval(m, day(3, 8)).action == Plan.ACTION_REMEASURE);
        // And on another day: a 14 % then a 5 % reading is no confirmation either.
        Model n = ownerModel();
        LengthYear.measured(n, day(1, 9), "PL", 14.0);
        LengthYear.measured(n, day(3, 9), "PL", 5.0);
        assertFalse(Meas.strainHighConfirmed(n, LengthTrack.METHOD, Plan.LENGTH_STRAIN_HI, 0L));
        assertFalse(eval(n, day(5, 8)).action == Plan.ACTION_REMEASURE);
    }

    @Test void twoHighsCutOnceAndNotAgainInsideAWeek() {
        Model m = ownerModel();
        double from = m.trainerLength.loadLb;
        LengthYear.measured(m, day(1, 9), "PL", 7.0);
        LengthYear.measured(m, day(3, 9), "PL", 7.0);
        Plan.Decision cut = eval(m, day(5, 8));
        assertEquals(Plan.ACTION_REMEASURE, cut.action);
        assertEquals(Plan.LENGTH_CUT_RULE, cut.rule);
        assertEquals(from - 0.5, cut.loadLb, 1e-9);
        assertTrue(cut.reason.startsWith("Over 6% again: lower the load to "), cut.reason);
        LengthTrack.acceptLoad(m, cut.rule, cut.loadLb, cut.pressureKpa, day(5, 8));
        assertEquals(day(5, 8), m.trainerLength.lastCutMs);
        assertEquals(1, m.trainerLength.cutsInRow);
        // The cut is new work: one more high asks again, it does not cut.
        LengthYear.measured(m, day(5, 9), "PL", 7.0);
        assertEquals(Plan.LENGTH_HIGH_RULE, eval(m, day(6, 8)).rule);
        // Two more highs confirm it again - but inside the week of the last cut it holds.
        LengthYear.measured(m, day(6, 9), "PL", 7.0);
        Plan.Decision wait = eval(m, day(8, 8));
        assertEquals(Plan.ACTION_HOLD, wait.action);
        assertEquals(Plan.LENGTH_CUT_WAIT_RULE, wait.rule);
        LengthYear.measured(m, day(11, 9), "PL", 7.0);
        Plan.Decision next = eval(m, day(12, 8));
        assertEquals(Plan.LENGTH_CUT_RULE, next.rule, "a week on, the second cut");
        LengthTrack.acceptLoad(m, next.rule, next.loadLb, next.pressureKpa, day(12, 8));
        assertEquals(2, m.trainerLength.cutsInRow, "two cuts in a row: the card asks the "
            + "person to check for slippage and how they measure");
        assertTrue(Plan.LENGTH_CUTS_CHECK_WORDS.contains("slipping"));
    }

    @Test void readingsThatDoNotCount() {
        // t10 R-24, the length side: only a length session that pulled reads.
        Model m = ownerModel();
        LengthYear.measured(m, day(1, 9), "PG", 1.0);                  // around a girth session
        LengthYear.measured(m, day(2, 9), "PL", 1.0).shape = "x";      // expansion only
        LengthYear.measured(m, day(3, 9), "PL", 1.0).completed = false; // stopped early
        LengthYear.measured(m, day(4, 9), "PL", 1.0).sim = true;       // the simulated pump
        Model.Reading pre = new Model.Reading();                       // no session at all
        pre.id = "lone"; pre.ts = day(5, 9); pre.method = LengthTrack.METHOD;
        pre.phase = Model.Reading.PHASE_PRE; pre.len = 16.0;
        Model.Reading post = new Model.Reading();
        post.id = "lonePost"; post.ts = day(5, 10); post.method = LengthTrack.METHOD;
        post.phase = Model.Reading.PHASE_POST; post.len = 16.16;
        m.measLog.all.add(pre);
        m.measLog.all.add(post);
        assertTrue(Meas.strainReadings(m, LengthTrack.METHOD, 0L).isEmpty(),
            "none of them is a reading the length ladder acts on");
        assertNull(Meas.strainPct(m, LengthTrack.METHOD, day(6, 8)));
        // ...but each still shows the person measures: the no-readings count restarts.
        assertEquals(post.ts, Meas.lastPairedMs(m.measLog, LengthTrack.METHOD));
        // A pulling length session's pair is the one that counts.
        LengthYear.measured(m, day(6, 9), "PL", 3.0);
        assertEquals(3.0, Meas.strainPct(m, LengthTrack.METHOD, day(7, 8)), 1e-9);
    }

    @Test void aDeloadStartsANewBlock() {
        Model m = ownerModel();
        LengthYear.measured(m, day(8, 9), "PL", 3.0);
        m.trainerLength.dAddedInBlock = true;
        m.trainerLength.dOfferedInBlock = true;
        Deload.remember(m, day(10, 8), day(17, 8));
        assertFalse(m.trainerLength.dAddedInBlock);
        assertFalse(m.trainerLength.dOfferedInBlock);
        // ...and a reading at 2 % before it is the old block's.
        assertTrue(Meas.reachedSince(m, LengthTrack.METHOD, 0L, Plan.LENGTH_STRAIN_LO));
        assertFalse(Meas.reachedSince(m, LengthTrack.METHOD, LengthTrack.blockStartMs(m),
            Plan.LENGTH_STRAIN_LO));
    }

    @Test void theWordsAreTheOwners() {
        // Polish TR-31: sentences, "2%" with no space, and no question the buttons ask.
        assertEquals("Your length reading stayed under 2% for a week, after reaching 2% earlier "
            + "in this block. The week off starts tomorrow; no set is added",
            Plan.lengthFellWords(false));
        assertTrue(Plan.lengthFellWords(true).endsWith("The week off is next anyway; no set is added"));
        assertEquals("Length under 2% since the last week off. Add a strain set: 6 → 7",
            Plan.lengthNeverWords(6));
        assertEquals("Still under 2% a week after the added set.", Plan.LENGTH_STILL_UNDER_WORDS);
        assertEquals(2.0, Plan.LENGTH_STRAIN_LO, 0.0);
        assertEquals(6.0, Plan.LENGTH_STRAIN_HI, 0.0);
    }
}
