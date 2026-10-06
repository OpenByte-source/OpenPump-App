package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * t10 parity run 2, A-5: A LENGTH CUT CONFIRMED JUST BEFORE A WEEK OFF IS NOT LOST OVER IT.
 * Two readings over 6 % confirmed the cut, the second on the last length session before the
 * week off. The next morning the ladder looks is the first one back, nine days on - and the
 * reading it acts on is "at most a week old" (Meas#STRAIN_WINDOW_DAYS), so the confirmed cut
 * had gone stale and three more sessions ran at the uncut load. The week off does not age the
 * reading: no session ran in it, so it is still the newest word on the work. The cut applies at
 * the first length session back.
 */
class CutOverWeekOffTest {

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    /** The owner: two readings over 6 % (days 1 and 3), the second just before a week off. */
    private static Model confirmedThenWeekOff(boolean weekOff) {
        Model m = new LengthYear().setup().m;
        LengthYear.measured(m, day(1, 9), "PL", 7.0);
        LengthYear.measured(m, day(3, 9), "PL", 7.0);
        if (weekOff) {
            // The answer, as SessionActivity's DeloadStartPick records it (recordDeload).
            Deload.remember(m, day(4, 0), day(4, 0) + Plan.LAYOFF_MS);
            m.trainerFirstDeloadTaken = true;
            Deload.arm(m, day(4, 0) + Plan.LAYOFF_MS);
        }
        return m;
    }

    @Test void theCutAppliesAtTheFirstLengthSessionBack() {
        Model m = confirmedThenWeekOff(true);
        Plan.Decision d = Plan.evaluate(LengthYear.inputsFor(m, day(12, 8)));
        assertEquals(Plan.LENGTH_CUT_RULE, d.rule, "nine days on, a week of them off: " + d.rule);
    }

    @Test void withoutAWeekOffNineDaysIsStillStale() {
        Model m = confirmedThenWeekOff(false);
        Plan.Decision d = Plan.evaluate(LengthYear.inputsFor(m, day(12, 8)));
        assertNotEquals(Plan.LENGTH_CUT_RULE, d.rule,
            "two training weeks without a reading: the reading is no longer the work's word");
    }
}
