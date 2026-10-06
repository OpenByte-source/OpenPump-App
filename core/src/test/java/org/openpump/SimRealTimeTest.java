package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE SIMULATOR KEEPS REAL TIME ON A BUSY PHONE.
 *
 * PumpLink advanced the simulated pump a fixed 240 ms each time its timer fired, and the
 * timer runs on the UI thread. Under load it fired about every 420 ms, so the simulator ran
 * at about 0.57x real time and a 120 s hold took about 211 s. It now advances by the real
 * monotonic time that passed (SimPump#realStepMs), capped so a long stall cannot jump it.
 * The simulator only: nothing sent to a real pump reads this.
 */
class SimRealTimeTest {

    @Test void aStepIsTheRealTimeThatPassed() {
        assertEquals(240, SimPump.realStepMs(10_000, 10_240));
        assertEquals(420, SimPump.realStepMs(10_000, 10_420));
        assertEquals(0, SimPump.realStepMs(10_000, 10_000));
    }

    @Test void aLongStallIsCappedAtAboutTwoSeconds() {
        assertEquals(2_000, SimPump.MAX_REAL_STEP_MS);
        assertEquals(SimPump.MAX_REAL_STEP_MS, SimPump.realStepMs(0, 60_000));
        assertEquals(SimPump.MAX_REAL_STEP_MS, SimPump.realStepMs(0, 2_001));
    }

    @Test void theClockNeverRunsBackwards() {
        assertEquals(0, SimPump.realStepMs(5_000, 4_000));
    }

    /** A 20 s hold, the timer firing every 420 ms of real time. With real-time steps the
     *  pump leaves its hold when it would on the wall clock, as it does when the timer keeps
     *  its 240 ms; with the old fixed 240 ms per firing it left about 0.57x as fast. */
    @Test void aSlowTimerNoLongerSlowsTheSimulator() {
        long ideal = realMsToLeaveTheHold(240, true);
        long real = realMsToLeaveTheHold(420, true);
        long fixed = realMsToLeaveTheHold(420, false);
        assertTrue(Math.abs(real - ideal) <= 500,
            "real-time steps: the hold ends at " + real + " ms, as on time (" + ideal + " ms)");
        assertTrue(fixed > ideal * 1.6,
            "the old fixed step ran slow: " + fixed + " ms against " + ideal + " ms");
    }

    /** Real ms, the timer firing every `firingMs`, until a pump pulled to 20 kPa and held for
     *  20 s falls away from its hold - the step either the real time that passed or, as it
     *  was, a fixed period. */
    private static long realMsToLeaveTheHold(long firingMs, boolean realSteps) {
        SimPump pump = new SimPump();
        pump.write(Proto.addPreset(100, 20, 20, 5, 1));
        pump.write(Proto.startSlot(0));
        pump.drain();
        long last = 0;
        boolean reached = false;
        for (long now = firingMs; now <= 600_000; now += firingMs) {
            pump.tick(realSteps ? SimPump.realStepMs(last, now) : SimPump.TELEM_PERIOD_MS);
            last = now;
            pump.drain();
            double k = pump.pressureKpa();
            if (k >= 19.5) reached = true;
            else if (reached && k < 18.0) return now;
        }
        throw new AssertionError("the pump never left its hold");
    }
}
