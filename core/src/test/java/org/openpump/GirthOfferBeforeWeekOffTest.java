package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 parity run 2, A-4: GIRTH "STILL LOW" AT THE LEVEL 4 TOP, WITH A WEEK OFF ANSWERED FOR A
 * LATER DAY. An add ran the holds into the L4 top (18); three readings under the target
 * followed, and the owner's rule (R-23) offers a week off or 4 weeks of length focus. The app
 * answered "at pressure cap" every morning instead and offered only after the week off: the
 * cadence's week off had been answered for the Monday after, which arms the gentle return at
 * the answer (SessionActivity#recordDeload), and R-26's "not on the return days" read the days
 * BEFORE the week off as return days. They are not (REAL-5: they run at the working pressure
 * and count), so the offer - and the add and the fallback with it - is not held by them.
 */
class GirthOfferBeforeWeekOffTest {

    /** L4 interval at its top, an add pending, three lows - the offer is due. */
    private static Plan.Inputs stillLowAtTheTop() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = Plan.L4;
        in.monthIndex = 14;
        in.weekIndex = 1;
        in.ceilKpa = 43;
        in.pressureKpa = 34;
        in.firstDeloadPending = false;
        in.hasYieldData = true;
        in.consecutiveLowYield = 3;
        in.addPending = true;
        in.workSets = Plan.GIRTH_INTERVAL_L4_TOP_SETS;
        return in;
    }

    @Test void theOfferIsMadeOnTheDaysBeforeTheWeekOff() {
        Plan.Inputs in = stillLowAtTheTop();
        assertEquals(Plan.ACTION_OFFER_BREAK, Plan.evaluate(in).action, "due on an ordinary day");
        // The week off answered for Monday: the taper is armed from the answer.
        in.gentleReturnOpen = true;
        in.returnRunsUnder = true;
        in.weekOffAhead = true;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_OFFER_BREAK, d.action,
            "the week off has not begun: not a return day - " + d.rule);
    }

    @Test void aRealReturnDayStillWaits() {
        Plan.Inputs in = stillLowAtTheTop();
        in.gentleReturnOpen = true;
        in.returnRunsUnder = true;
        assertNotEquals(Plan.ACTION_OFFER_BREAK, Plan.evaluate(in).action,
            "R-26: on the return days after the week off the offer waits for a full day");
    }

    @Test void theInputsSayWhenTheWeekOffIsStillAhead() {
        long day = 86400000L;
        long now = System.currentTimeMillis();
        long monday = Deload.dayStartPlus(now, 2);
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = now - 400L * day;
        m.trainerGirth.level = Plan.L4;
        // The answer, as SessionActivity's DeloadStartPick records it (recordDeload).
        Deload.remember(m, monday, monday + Plan.LAYOFF_MS);
        m.trainerFirstDeloadTaken = true;
        Deload.arm(m, monday + Plan.LAYOFF_MS);
        Plan.Inputs in = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 14, now, in);
        assertFalse(in.gentleReturnOpen, "armed at the answer, but not yet open (follow-up)");
        assertTrue(in.weekOffAhead, "but the week off is still ahead");
        Plan.Inputs back = new Plan.Inputs();
        TrainerTab.fillGirthInputs(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, 14,
                                   monday + Plan.LAYOFF_MS + 9L * 3600000L, back);
        assertTrue(back.gentleReturnOpen);
        assertFalse(back.weekOffAhead, "the first day back is a return day");
    }
}
