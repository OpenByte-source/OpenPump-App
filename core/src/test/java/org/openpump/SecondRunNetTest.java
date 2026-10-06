package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * D2 FOLLOW-UP - A SECOND RUN IN ONE SESSION IS COUNTED BY ITS OWN FRAMES.
 *
 * Session#beginRun cleared every per-frame list the net is counted from except one: the
 * mark that a frame belonged to an out-of-net stage (the trainer's fatigue block, a
 * retention stage). So from the second run in one process the mask no longer lined up with
 * the frames - it still began with the first run's marks - and the net excluded or kept the
 * wrong seconds. The app keeps one Session for the life of the Activity, so every run after
 * the first was exposed.
 */
class SecondRunNetTest {

    private static final long FRAME_MS = 240L;
    private static final double FLOOR = 16.93;

    private static void frames(Session s, long from, long to, double kpa, boolean outOfNet) {
        for (long t = from; t <= to; t += FRAME_MS)
            s.noteHoldFrame(t, kpa, false, 17, Session.HOLD_PHASE_HOLD, 0, outOfNet, false);
    }

    @Test
    void aSecondRunIsNotMaskedByTheFirstRunsOutOfNetFrames() {
        Session s = new Session();
        s.beginRun(0L);
        frames(s, 0L, 300_000L, 17.0, true);      // a whole run in an out-of-net stage
        double firstNet = s.netGrossTupSec(FLOOR)[0];
        assertEquals(0.0, firstNet, 1e-9, "out of net: counted in gross only");

        s.beginRun(1_000_000L);
        frames(s, 1_000_000L, 1_300_000L, 17.0, false);   // five minutes of work at pressure
        double[] tup = s.netGrossTupSec(FLOOR);
        assertEquals(tup[1], tup[0], 1e-9,
            "every second of the second run's work is at pressure and in the net - it read "
            + tup[0] + " s of " + tup[1] + " s");
    }

    @Test
    void theSameRunAloneCountsTheSame() {
        Session s = new Session();
        s.beginRun(1_000_000L);
        frames(s, 1_000_000L, 1_300_000L, 17.0, false);
        double[] tup = s.netGrossTupSec(FLOOR);
        assertEquals(300.0, tup[0], 1e-9, "the control: five minutes, all of it net");
    }
}
