package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 O3 / O4 (the owner, 1 Oct 2026) - EVERY WEEK OFF IS PLAN-WIDE, AND A DECIDED ONE STARTS
 * TOMORROW. A week off now - option D's fell, the length offer's or the girth offer's week off -
 * starts the next calendar day (SessionActivity.StartDeloadTap(track, true): the window from
 * tomorrow's local midnight), so today's session still runs and counts. Whichever card took it,
 * it takes both tracks off together, and both tracks' block counters restart with it: the one
 * plan-wide cadence (TrainerTab#planTrainingWeeks from TrainerTab#deloadAnchorMs) and option
 * D's block (its flags, and "this block reached 2 %" from the window's end).
 */
class WeekOffPlanWideTest {

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    /** The owner, a fell (or an offer) answered on day 9 at 08:00 with "the week off". */
    private static Model weekOffFromTomorrow(long tap) {
        Model m = new LengthYear().setup().m;
        LengthYear.measured(m, day(2, 9), "PL", 3.0);
        LengthYear.measured(m, day(8, 9), "PG", 8.0);     // yesterday's girth session
        m.trainerLength.dAddedInBlock = true;
        m.trainerLength.dOfferedInBlock = true;
        long from = Deload.dayStartPlus(tap, 1);           // StartDeloadTap(track, true)
        Deload.remember(m, from, from + Plan.LAYOFF_MS);
        m.trainerFirstDeloadTaken = true;
        Deload.arm(m, from + Plan.LAYOFF_MS);
        return m;
    }

    private static Plan.Inputs girthInputs(Model m, long now) {
        // The plan-wide fields SessionActivity#buildTrackInputs gives every track.
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = m.trainerGirth.level;
        in.monthIndex = TrainerTab.monthIndexNow(m, now);
        in.ceilKpa = m.ceilKpa;
        in.pressureKpa = m.trainerGirth.pressureKpa;
        in.inDeloadWeek = TrainerTab.inDeloadWeek(m, now);
        in.accumulatedTrainingWeeks = TrainerTab.planTrainingWeeks(m,
            TrainerTab.deloadAnchorMs(m), now);
        in.firstDeloadPending = !m.trainerFirstDeloadTaken;
        return in;
    }

    @Test void itStartsTomorrowAndTodaysSessionCounts() {
        long tap = day(9, 8);
        Model m = weekOffFromTomorrow(tap);
        assertEquals(day(10, 0), Deload.startMs(m), "tomorrow's local midnight");
        assertFalse(TrainerTab.inDeloadWeek(m, day(9, 20)), "today is not the week off");
        Model.Sess today = LengthYear.measured(m, day(9, 10), "PL", 2.5);
        assertTrue(Meas.strainSession(m, today), "today's length session is read");
        assertEquals(2.5, Meas.strainPct(m, LengthTrack.METHOD, day(9, 20)), 1e-9);
    }

    @Test void bothTracksAreOffTogether() {
        Model m = weekOffFromTomorrow(day(9, 8));
        for (int d = 10; d <= 16; d++) {
            long now = day(d, 9);
            assertTrue(TrainerTab.inDeloadWeek(m, now), "day " + d);
            Plan.Decision l = Plan.evaluate(LengthYear.inputsFor(m, now));
            assertEquals(Plan.LENGTH_DELOAD_RULE, l.rule, "length rests, day " + d);
            Plan.Decision g = Plan.evaluate(girthInputs(m, now));
            assertEquals(Plan.ACTION_DELOAD, g.action, "girth too, day " + d);
        }
        assertFalse(TrainerTab.inDeloadWeek(m, day(17, 9)), "seven days");
    }

    @Test void bothTracksBlockCountersRestartTogether() {
        Model m = weekOffFromTomorrow(day(9, 8));
        // Option D's block: its flags, and "reached 2 %" from the window's end.
        assertFalse(m.trainerLength.dAddedInBlock);
        assertFalse(m.trainerLength.dOfferedInBlock);
        assertEquals(Deload.endMs(m), LengthTrack.blockStartMs(m));
        assertFalse(Meas.reachedSince(m, LengthTrack.METHOD, LengthTrack.blockStartMs(m),
            Plan.LENGTH_STRAIN_LO), "the 3 % before it is the old block's");
        // The one cadence, counted for both tracks from the same anchor.
        assertEquals(Deload.endMs(m), TrainerTab.deloadAnchorMs(m));
        assertEquals(0, girthInputs(m, day(18, 9)).accumulatedTrainingWeeks);
        assertEquals(0, LengthYear.inputsFor(m, day(18, 9)).accumulatedTrainingWeeks);
    }
}
