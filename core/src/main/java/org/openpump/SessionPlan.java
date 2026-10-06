package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * M1 - WHAT TODAY'S SESSION INVOLVES, SAID BEFORE START (the owner's item 14).
 *
 * With everything on, START leads through a hold, a measurement, a release, a pull to
 * pressure and a tissue response test before the first set - three to five minutes and
 * several pressure cycles - and nothing said so beforehand. The START confirm now carries one
 * box: the steps that will run before the first set, how long they roughly take, and what
 * runs after the last set.
 *
 * THE SAME INPUTS THE FLOW BRANCHES ON, read by the caller from the same places the flow
 * reads them (beginSession, beginRunFlow, finishSession), in the flow's own order:
 *   measDue     - model.measLog.due(...): is a measurement asked for this session?
 *   stdOn       - model.std.on: is it taken under the standardisation hold?
 *   guided      - model.guidedStart: the pull to pressure (it replaces the seal check);
 *   seal        - model.sealBeforeRoutine: the seal check, when the guided start is off;
 *   testBefore / testAfter - Tau.runsBefore / runsAfter for the routine;
 *   endHold     - the routine ends into a hold for the after measurement.
 * Nothing here commands, or changes, any pressure: it only says what will happen.
 *
 * Pure, so SessionPlanTest holds it.
 */
public final class SessionPlan {
    private SessionPlan() { }

    /** The tissue test's name, as the owner chose it (item 10). */
    public static final String TEST_NAME = "tissue response test";

    /* ROUGH ON PURPOSE, and said as "about". Each is the typical time on the simulated pump
     * and in the measurement study (STUDY-measure-report, section 2), not a bound:
     *   - the pull to the hold pressure before its count starts: ~12 s (42 s from the tap
     *     to "Hold complete" with a 30 s count);
     *   - measuring: 30-90 s, more with photos;
     *   - the release: 5-10 s, then a tap;
     *   - the guided start's pull to its pressure and two seconds held: ~15 s;
     *   - the seal check: its window is at most 25 s, or the plateau plus a 10 s coast;
     *   - the tissue test's vent to a known start: 3-15 s, before its own pull. */
    static final int HOLD_PULL_SEC = 12;
    static final int MEASURE_SEC   = 60;
    static final int RELEASE_SEC   = 10;
    static final int GUIDED_SEC    = 15;
    static final int SEAL_SEC      = 30;
    static final int TEST_VENT_SEC = 10;

    /** The steps before the first set and after the last, in order, and the rough time the
     *  ones before take. */
    public static final class Steps {
        public final List<String> before = new ArrayList<String>();
        public final List<String> after = new ArrayList<String>();
        public int beforeSec;
    }

    public static Steps of(boolean measDue, boolean stdOn, int stdSec, boolean guided,
                           boolean seal, boolean testBefore, boolean testAfter,
                           int testDurSec, boolean endHold) {
        Steps s = new Steps();
        // beginSession: the hold, then the measurement, then the release - all three only
        // when a measurement is due, the hold and release only when it is standardised.
        if (measDue && stdOn) {
            s.before.add(stdSec + " s hold");
            s.beforeSec += HOLD_PULL_SEC + stdSec;
        }
        if (measDue) {
            s.before.add("measure");
            s.beforeSec += MEASURE_SEC;
        }
        if (measDue && stdOn) {
            s.before.add("release");
            s.beforeSec += RELEASE_SEC;
        }
        // beginRunFlow: the guided start replaces the seal check when it is on.
        if (guided) {
            s.before.add("pull to pressure");
            s.beforeSec += GUIDED_SEC;
        } else if (seal) {
            s.before.add("seal check");
            s.beforeSec += SEAL_SEC;
        }
        if (testBefore) {
            s.before.add(TEST_NAME);
            s.beforeSec += TEST_VENT_SEC + testDurSec;
        }
        // After the last set: the after test, then the routine's end into a hold.
        if (testAfter) s.after.add(TEST_NAME);
        if (endHold) s.after.add("a hold for the after measurement");
        return s;
    }

    /** "30 s hold → measure → release"; said plainly when nothing comes before the first
     *  set. */
    public static String chain(List<String> steps) {
        if (steps == null || steps.isEmpty()) return "Nothing — the first set starts straight away";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) b.append(" → ");
            b.append(steps.get(i));
        }
        return b.toString();
    }

    /** "about 3 min", "under a minute", or "" when there is nothing to time. */
    public static String about(int sec) {
        if (sec <= 0) return "";
        if (sec < 45) return "under a minute";
        return "about " + (int) Math.floor(sec / 60.0 + 0.5) + " min";
    }
}
