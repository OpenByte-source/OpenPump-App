package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 O5 / O8 (the owner, 1 Oct 2026) - A LENGTH READING TAKEN ON A REDUCED RETURN DAY after a
 * deload. Length runs its full load on those days (the taper reaches the coda only), so the
 * reading COUNTS (O5): it is the reading the ladder acts on, over 6 % asks again, and it counts
 * for "this block reached 2 %". But it does NOT count toward the one-week low streak (O8): it
 * neither starts, extends nor breaks it, so on its own it can never add a set or bring the
 * offer. Which day was reduced is filed on the session (Sess#returnDay), from the taper the day
 * ran under (Deload#reducedOn).
 */
class ReturnDayLengthReadingTest {

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    private static Model owner() { return new LengthYear().setup().m; }

    private static Model.Sess reduced(Model m, long ts, double pct) {
        Model.Sess s = LengthYear.measured(m, ts, "PL", pct);
        s.returnDay = true;
        return s;
    }

    private static Plan.Decision eval(Model m, long now) {
        return Plan.evaluate(LengthYear.inputsFor(m, now));
    }

    @Test void theDayIsReducedWhileTheTaperCutsIt() {
        Model m = owner();
        long tap = day(10, 8);
        Deload.remember(m, tap, tap + Plan.LAYOFF_MS);
        Deload.arm(m, tap + Plan.LAYOFF_MS);
        long back = day(17, 9);
        assertTrue(Deload.reducedOn(m, back), "the first training day back runs under");
        Deload.onFiled(m, back);
        assertTrue(Deload.reducedOn(m, day(18, 9)), "and the second");
        Deload.onFiled(m, day(18, 9));
        assertFalse(Deload.reducedOn(m, day(19, 9)), "the third is a full day");
        assertFalse(Deload.reducedOn(owner(), day(3, 9)), "no taper armed, no reduced day");
    }

    @Test void itCountsAsTheReadingTheLadderActsOn() {
        // O5: over 6 % on a return day asks for the measurement again.
        Model m = owner();
        reduced(m, day(1, 9), 9.0);
        assertEquals(9.0, Meas.strainPct(m, LengthTrack.METHOD, day(2, 8)), 1e-9);
        assertEquals(Plan.LENGTH_HIGH_RULE, eval(m, day(2, 8)).rule);
        // ...and for "this block reached 2 %".
        Model n = owner();
        reduced(n, day(1, 9), 3.0);
        assertTrue(Meas.reachedSince(n, LengthTrack.METHOD, LengthTrack.blockStartMs(n),
            Plan.LENGTH_STRAIN_LO));
    }

    @Test void aLowReturnDayReadingDoesNotStartTheStreak() {
        Model m = owner();
        reduced(m, day(1, 9), 1.5);
        LengthYear.measured(m, day(3, 9), "PL", 1.5);
        Plan.Inputs in = LengthYear.inputsFor(m, day(9, 8));
        assertEquals(5, in.strainMissDays, "counted from the first full day's low, not the "
            + "return day's");
        assertFalse(Plan.LENGTH_NEVER_RULE.equals(Plan.evaluate(in).rule), "no set yet");
        LengthYear.measured(m, day(10, 9), "PL", 1.5);
        assertEquals(Plan.LENGTH_NEVER_RULE, eval(m, day(11, 8)).rule,
            "a week after the full day's low, the set");
        // On its own a low return-day reading never adds a set.
        Model n = owner();
        reduced(n, day(1, 9), 1.5);
        assertEquals(0, LengthYear.inputsFor(n, day(12, 8)).strainMissDays);
    }

    @Test void aReturnDayReadingDoesNotExtendOrBreakTheStreak() {
        // Lows on full days 1 and 3; a return day at 3 % on day 5 neither ends the run nor
        // joins it - and it is this block's reading at 2 %, so a week of lows reads as FELL.
        Model m = owner();
        LengthYear.measured(m, day(1, 9), "PL", 1.5);
        LengthYear.measured(m, day(3, 9), "PL", 1.5);
        reduced(m, day(5, 9), 3.0);
        Plan.Inputs in = LengthYear.inputsFor(m, day(9, 8));
        assertTrue(in.blockHadReachLo, "the return day's 3 % reached 2 % this block");
        assertEquals(7, in.strainMissDays, "the run of lows from day 1 goes on");
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.LENGTH_FELL_RULE, d.rule, "fell: the week off comes forward");
        // And a low return-day reading after the run does not extend it past its own reading.
        Model n = owner();
        LengthYear.measured(n, day(1, 9), "PL", 3.0);
        reduced(n, day(3, 9), 1.5);
        assertEquals(0, LengthYear.inputsFor(n, day(12, 8)).strainMissDays);
    }

    @Test void theRecordKeepsIt() throws Exception {
        Model.Sess s = new Model.Sess();
        s.id = "s1"; s.ts = 1788440800000L; s.routineId = "PL";
        assertFalse(Model.Sess.fromJson(s.toJson()).returnDay);
        s.returnDay = true;
        assertTrue(Model.Sess.fromJson(s.toJson()).returnDay);
    }
}
