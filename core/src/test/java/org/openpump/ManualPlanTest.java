package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A MANUAL RUN LASTS AS LONG AS IT SAYS IT WILL.
 *
 * From a device log: "RUN Manual run — 1 presets, 12:00" at 15:29:43, then "COMPLETE after
 * 0:03 — preset 1/1" 2.4 s later, with no skip and no stop in between. The run header's
 * duration comes from the routine; what the clock actually runs on is the plan's preset.
 * If those two disagree the run ends the moment the shorter one is up.
 */
class ManualPlanTest {

    private static Model modelWithManual(int durSec, int uh, int lh) {
        Model m = new Model();
        m.ceilKpa = 40;
        Model.Set src = Model.Set.fixed("manual", "Manual run", 34, 18, uh, lh, 75, durSec);
        m.adhoc.clear();
        m.adhoc.add(Manual.ephemeral(src, m.ceilKpa));
        return m;
    }

    @Test
    void theOnePresetCarriesTheWholeDuration() {
        Model m = modelWithManual(720, 120, 0);
        List<Model.Preset> plan = m.plan(Manual.routine(m.ceilKpa));
        long total = 0;
        for (Model.Preset p : plan) total += p.durMs;
        assertEquals(720000L, total, "the plan must run for the 12:00 the header promises");
    }

    @Test
    void theRoutineAndThePlanAgreeAboutHowLongItIs() {
        Model m = modelWithManual(360, 30, 5);
        Model.Routine r = Manual.routine(m.ceilKpa);
        long planned = 0;
        for (Model.Preset p : m.plan(r)) planned += p.durMs;
        assertEquals(m.routineSec(r) * 1000L, planned,
            "the header's duration and the clock's duration are the same run");
    }
}
