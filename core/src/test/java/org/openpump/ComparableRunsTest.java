package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * THE CHART'S SMOOTHING AND THE "POST VS PRE" FIGURE COMPARE ONLY READINGS TAKEN THE SAME WAY.
 *
 * The data-integrity review (app-polish 2624f17), item 5:
 *   - smoothing, on by default, averaged across readings the Std line cannot compare (a
 *     reading at another vacuum, one with no hold recorded) - the line broke at the boundary
 *     but the average ran straight through it. It now restarts at every incomparable edge
 *     ({@link Meas#drawableEdges}), so each run is smoothed on its own;
 *   - "post vs pre (Std)" paired a day's pre and post readings without asking whether they
 *     were comparable. It now pairs only a comparable pair ({@link Model.Reading#comparable}).
 */
class ComparableRunsTest {

    private static final long DAY = 86400000L;

    private static Model.Reading std(long ts, double len, Double observed) {
        Model.Reading r = new Model.Reading();
        r.ts = ts; r.len = len; r.gir = 12;
        r.holdKpa = Double.valueOf(20.0);
        r.observedKpa = observed;
        return r;
    }

    @Test void smoothingRestartsAtEveryIncomparableEdge() {
        List<Model.Reading> pts = new ArrayList<Model.Reading>();     // oldest first
        pts.add(std(1 * DAY, 15.0, Double.valueOf(19.8)));
        pts.add(std(2 * DAY, 15.2, Double.valueOf(19.8)));
        pts.add(std(3 * DAY, 18.0, Double.valueOf(30.0)));          // another vacuum
        pts.add(std(4 * DAY, 18.4, Double.valueOf(30.0)));
        Model.Reading noHold = std(5 * DAY, 16.0, null);
        noHold.holdKpa = null;                                        // Std line, no hold
        pts.add(noHold);
        List<double[]> sm = Meas.smoothedWithinRuns(pts, Meas.SMOOTH_WINDOW);
        assertEquals(5, sm.size());
        assertEquals(15.0, sm.get(0)[1], 1e-9);
        assertEquals(15.1, sm.get(1)[1], 1e-9, "15.0 and 15.2, one run");
        assertEquals(18.0, sm.get(2)[1], 1e-9,
            "a new vacuum starts a new run - never averaged with the 19.8 kPa readings");
        assertEquals(18.2, sm.get(3)[1], 1e-9);
        assertEquals(16.0, sm.get(4)[1], 1e-9, "a reading with no hold is on its own");
        // Within one run it is the plain rolling average, unchanged.
        List<Model.Reading> one = pts.subList(0, 2);
        assertEquals(Meas.smoothed(one, 7).get(1)[1], Meas.smoothedWithinRuns(one, 7).get(1)[1],
            1e-9);
    }

    @Test void postVsPreOnTheStdLinePairsOnlyAComparablePair() {
        List<Model.Reading> w = new ArrayList<Model.Reading>();
        Model.Reading pre = std(10 * DAY, 16.0, Double.valueOf(19.8));
        pre.phase = Model.Reading.PHASE_PRE;
        Model.Reading post = std(10 * DAY + 3600000L, 17.6, Double.valueOf(30.0));
        post.phase = Model.Reading.PHASE_POST;                        // another vacuum
        w.add(pre); w.add(post);
        assertNull(Meas.prePostPct(w, Model.Reading.METHOD_STANDARDIZED),
            "19.8 kPa before and 30 kPa after are not the same measurement");
        post.observedKpa = Double.valueOf(19.9);
        Double pct = Meas.prePostPct(w, Model.Reading.METHOD_STANDARDIZED);
        assertNotNull(pct);
        assertEquals(10.0, pct.doubleValue(), 1e-9);
        // A post reading with no hold recorded is not paired with a standardised pre one.
        post.holdKpa = null; post.observedKpa = null;
        assertNull(Meas.prePostPct(w, Model.Reading.METHOD_STANDARDIZED));
    }
}
