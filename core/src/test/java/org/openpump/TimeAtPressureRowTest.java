package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TIME AT PRESSURE, ONE FIGURE EVERYWHERE. Found on an emulator run: the session summary had
 * no time-under-tension line at all, History's "Time at pressure" rounded the measured net
 * (2:37 for 156.716 s) and GrowthTrack was sent it rounded down (156 s). One formatter now
 * gives the summary and History the same row, in the same whole seconds GrowthTrack gets.
 */
class TimeAtPressureRowTest {
    static Model.Sess sess(double net, double gross, long dur) {
        Model.Sess s = new Model.Sess(); s.id = "sess1"; s.ts = 1_700_000_000_000L; s.durSec = dur;
        s.completed = true; s.recordedStartMs = s.ts; s.recordedEndMs = s.ts + dur * 1000L;
        s.netTupSec = net; s.grossTupSec = gross; s.peakKpa = 20.0; return s;
    }

    @Test void theRowIsTheNetInWholeSecondsAsSentOfTheGross() throws Exception {
        assertEquals("Time at pressure", Summary.TIME_AT_PRESSURE_LABEL);
        Model.Sess routine = sess(156.716, 225.441, 225);
        assertEquals("2:36 of 3:45   ·   70%", Summary.timeAtPressure(routine));
        Model.Sess manual = sess(83.692, 90.376, 90);
        assertEquals("1:23 of 1:30   ·   93%", Summary.timeAtPressure(manual));

        AsRun run = new AsRun(); AsRun.Row r = new AsRun.Row(); r.kind = AsRun.PLAN; r.t0 = 0; r.t1 = 225_000;
        r.setId = "set0"; r.up = 20; r.uhWire = 20; r.uhReq = 20; r.lh = 5; run.rows.add(r);
        int sent = GrowthTrackSession.draft(routine, run).payload().getInt("time_under_tension_seconds");
        assertEquals(156, sent, "GrowthTrack stays whole seconds");
        assertTrue(Summary.timeAtPressure(routine).startsWith(Model.Fmt.t(sent) + " of "),
            "the row shows the seconds GrowthTrack was sent");
    }

    @Test void noRowWithoutBothFigures() {
        assertNull(Summary.timeAtPressure(null));
        Model.Sess s = sess(60, 120, 120); s.netTupSec = null; assertNull(Summary.timeAtPressure(s));
        s = sess(60, 120, 120); s.grossTupSec = null; assertNull(Summary.timeAtPressure(s));
        s = sess(0, 0, 120); assertNull(Summary.timeAtPressure(s));
    }
}
