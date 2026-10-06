package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * LEVEL 2 STEPS AT 20 MINUTES (the owner's decision, 2026-09-26). The guidance raises Level 2's
 * pressure once a session holds 20 minutes while it builds towards 30. The app waited for 30,
 * which the Level 2 week table reached only at its end (it then stopped at 26 minutes; since
 * the owner's 2026-09-27 decision it runs on to 30). The cap is unchanged.
 */
class LevelTwoMilestoneTest {

    static Plan.Inputs l2(double net, double kpa) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = Plan.L2;
        in.monthIndex = 5;
        in.weekIndex = 1;
        in.pressureKpa = kpa;
        in.netTupMin = net;
        in.trainingWeeksAtPressure = 3;
        in.firstDeloadPending = false;
        return in;
    }

    @Test void twentyMinutesIsTheMilestone() {
        assertEquals(20.0, Plan.netMilestoneMin(Plan.L2), 1e-9);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(l2(20.0, 27)).action);
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(l2(19.5, 27)).action);
    }

    @Test void everyRowOfTheTableReachesIt() {
        for (Plan.Week w : Plan.GIRTH_INTERVAL_L2)
            if (!w.deload) assertEquals(true, w.netTupMin >= Plan.netMilestoneMin(Plan.L2),
                "week " + w.num);
    }

    @Test void theCapIsStillTenInHg() {
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(l2(20.0, 34)).action);
        Plan.Decision d = Plan.evaluate(l2(20.0, 33));
        assertEquals(Plan.ACTION_RAISE_PRESSURE, d.action);
        assertEquals(34, Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 1, 33, 5, 40, d)
            .pressureKpa, "rounded to the 10 hg cap, never past it");
        Plan.Inputs gentle = l2(20.0, 27);
        gentle.gentleReturnOpen = true;
        assertEquals(Plan.ACTION_HOLD, Plan.evaluate(gentle).action);
    }
}
