package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * t10 review C-F1 (R-40: "a re-measure replaces the day's pair") - THE CARD'S OWN "MEASURE
 * AGAIN". The over-6 % card opens Log a reading, which filed every reading unlinked, and the
 * length ladder reads only after-readings linked to their session's before-reading - so the
 * re-measure was ignored, the suspicious reading stayed the newest, and the card stayed up for
 * a week. An after-reading logged from that card on the day of a length session that pulled now
 * links to that session (Meas#linkRemeasure), and the day's newer pair REPLACES the suspicious
 * one. Through the log screen's own builder (Meas#buildLogReadings), not a filed session.
 */
class MeasureAgainLinkTest {

    private static long day(int d, int hour) {
        return LengthYear.t0() + d * LengthYear.DAY + hour * LengthYear.HOUR;
    }

    /** What Log a reading files for one at-rest row of `method`, phase After, at `ts`. */
    private static Model.Reading logged(int method, double len, long ts) {
        Double[] rows = new Double[Meas.LOG_ROWS.length];
        for (int i = 0; i < rows.length; i++)
            if (Meas.LOG_ROWS[i].method == method) rows[i] = Double.valueOf(len);
        List<Model.Reading> batch = Meas.buildLogReadings(rows, ts, "today", "m" + ts, "",
            Model.Reading.PHASE_POST);
        assertEquals(1, batch.size());
        return batch.get(0);
    }

    private static Model slipped() {
        Model m = new LengthYear().setup().m;
        LengthYear.measured(m, day(1, 9), "PL", 14.0);   // pre 16.0, after 18.24 at 9:50
        return m;
    }

    @Test void theReMeasureReplacesTheSuspiciousReading() {
        Model m = slipped();
        Model.Reading again = logged(LengthTrack.METHOD, 16.8, day(1, 12));   // 5 %
        assertTrue(Meas.linkRemeasure(m, again), "linked to the day's length session");
        assertEquals("pre" + day(1, 9), again.pairOf);
        m.measLog.log(again, null);
        assertEquals(5.0, Meas.strainPct(m, LengthTrack.METHOD, day(2, 8)), 1e-9);
        Plan.Decision d = Plan.evaluate(LengthYear.inputsFor(m, day(2, 8)));
        assertNotEquals(Plan.ACTION_REMEASURE, d.action, "the card is answered: " + d.rule);
        assertFalse(Meas.lastStrainHigh(m, LengthTrack.METHOD, Plan.LENGTH_STRAIN_HI));
    }

    @Test void unlinkedItWasIgnored() {
        // The bug: the same reading filed as the log screen filed it changes nothing.
        Model m = slipped();
        m.measLog.log(logged(LengthTrack.METHOD, 16.8, day(1, 12)), null);
        assertEquals(14.0, Meas.strainPct(m, LengthTrack.METHOD, day(2, 8)), 1e-9);
    }

    @Test void onlyTheSameDaysLengthSessionAndItsMethod() {
        Model m = slipped();
        assertFalse(Meas.linkRemeasure(m, logged(LengthTrack.METHOD, 16.8, day(2, 12))),
            "the next day there is no session to re-measure");
        assertFalse(Meas.linkRemeasure(m, logged(Model.Reading.METHOD_BPEL, 16.8, day(1, 12))),
            "another method is not this session's after-reading");
        assertFalse(Meas.linkRemeasure(m, logged(LengthTrack.METHOD, 16.8, day(1, 8))),
            "before the session ran");
        Model.Reading pre = logged(LengthTrack.METHOD, 16.8, day(1, 12));
        pre.phase = Model.Reading.PHASE_PRE;
        assertFalse(Meas.linkRemeasure(m, pre), "only an after-reading");
        // A girth session that day is not a length session to re-measure.
        Model g = new LengthYear().setup().m;
        LengthYear.measured(g, day(1, 9), "PG", 14.0);
        assertFalse(Meas.linkRemeasure(g, logged(LengthTrack.METHOD, 16.8, day(1, 12))));
    }
}
