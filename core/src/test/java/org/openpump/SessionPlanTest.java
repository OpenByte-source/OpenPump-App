package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * M1 - WHAT TODAY'S SESSION INVOLVES, SAID BEFORE START (the owner's item 14).
 *
 * With everything on, START leads through a hold, a measurement, a release, a pull to
 * pressure and a tissue response test before the first set - minutes of it, and nothing said
 * so beforehand. {@link SessionPlan#of} lists the steps that will actually run, in the order
 * the flow runs them, from the same settings the flow reads, and roughly how long they take.
 */
class SessionPlanTest {

    private static final String TEST = "tissue response test";

    private static SessionPlan.Steps plan(boolean measDue, boolean stdOn, boolean guided,
                                          boolean seal, boolean before, boolean after,
                                          boolean endHold) {
        return SessionPlan.of(measDue, stdOn, 30, guided, seal, before, after, 45, endHold);
    }

    @Test void everythingOnIsListedInTheOrderTheFlowRunsIt() {
        SessionPlan.Steps s = plan(true, true, true, false, true, true, false);
        assertEquals(Arrays.asList("30 s hold", "measure", "release", "pull to pressure", TEST),
                     s.before);
        assertEquals("30 s hold → measure → release → pull to pressure → "
                     + TEST, SessionPlan.chain(s.before));
        assertEquals(Arrays.asList(TEST), s.after);
    }

    @Test void stepsThatWillNotRunAreLeftOut() {
        // Nothing due: no hold, no measurement, no release.
        SessionPlan.Steps nothingDue = plan(false, true, true, false, true, false, false);
        assertEquals(Arrays.asList("pull to pressure", TEST), nothingDue.before);
        assertTrue(nothingDue.after.isEmpty());
        // The hold off: the measurement is taken at rest, and there is nothing to release.
        SessionPlan.Steps atRest = plan(true, false, false, false, false, false, false);
        assertEquals(Arrays.asList("measure"), atRest.before);
        // The seal check instead of the guided start; the guided start wins when both are on,
        // exactly as beginRunFlow decides.
        assertEquals(Arrays.asList("seal check"),
                     plan(false, false, false, true, false, false, false).before);
        assertEquals(Arrays.asList("pull to pressure"),
                     plan(false, false, true, true, false, false, false).before);
    }

    @Test void theTestRunsWhereTheRoutineSaysItDoes() {
        assertEquals(Arrays.asList(TEST), plan(false, false, false, false, false, true, false).after);
        assertTrue(plan(false, false, false, false, true, false, false).after.isEmpty());
        assertEquals(Arrays.asList(TEST),
                     plan(false, false, false, false, true, false, false).before);
    }

    @Test void theEndOfRunHoldIsNamedAfterTheLastSet() {
        SessionPlan.Steps s = plan(false, false, false, false, false, true, true);
        assertEquals(Arrays.asList(TEST, "a hold for the after measurement"), s.after);
    }

    @Test void theTimeIsFromTheRealSettings() {
        // Default: 30 s hold (+ the pull to it), measuring, the release, the guided pull and
        // a 45 s test (+ its vent). Rough on purpose, and said as "about".
        SessionPlan.Steps all = plan(true, true, true, false, true, true, false);
        assertEquals("about 3 min", SessionPlan.about(all.beforeSec));
        SessionPlan.Steps longer = SessionPlan.of(true, true, 60, true, false, true, false, 180,
                                                  false);
        assertTrue(longer.beforeSec > all.beforeSec + 150,
            "a longer hold and a longer test take longer (" + longer.beforeSec + " vs "
            + all.beforeSec + ")");
        assertEquals(0, plan(false, false, false, false, false, false, false).beforeSec);
    }

    @Test void aboutReadsAsMinutesOrLess() {
        assertEquals("under a minute", SessionPlan.about(40));
        assertEquals("about 1 min", SessionPlan.about(60));
        assertEquals("about 1 min", SessionPlan.about(89));
        assertEquals("about 2 min", SessionPlan.about(90));
        assertEquals("about 4 min", SessionPlan.about(240));
        assertEquals("", SessionPlan.about(0), "nothing before the first set: no time to say");
    }

    @Test void nothingBeforeTheFirstSetIsSaidPlainly() {
        SessionPlan.Steps none = plan(false, false, false, false, false, false, false);
        assertTrue(none.before.isEmpty());
        assertFalse(SessionPlan.chain(none.before).isEmpty(),
            "an empty plan still says something: the first set starts straight away");
    }
}
