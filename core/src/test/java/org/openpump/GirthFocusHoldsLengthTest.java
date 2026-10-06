package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 R-44 (C13): DURING THE 8-WEEK GIRTH-FOCUS BLOCK THE LENGTH LADDER HOLDS - no strain, load
 * or month-gate proposal; only safety and the deload tier apply. When the block ends the strain
 * clock restarts at its end, and the no-readings fallback counts from there.
 */
class GirthFocusHoldsLengthTest {

    @Test void aBlockFromWeekTenToWeekEighteenHoldsTheLadder() {
        LengthYear y = new LengthYear();
        y.every = 3;
        y.strainPct = 1.5;                                  // under-worked: a set every block
        y.focusFromMs = LengthYear.t0() + 9 * LengthYear.WEEK;   // Monday of week 10
        y.setup().run();
        for (int i = 0; i < y.events.size(); i++) {
            String e = y.events.get(i);
            int week = Integer.parseInt(e.substring(1, e.indexOf(' ')));
            if (week >= 10 && week < 18)
                assertTrue(e.endsWith(" deload"), "no length card in the block: " + e);
        }
        assertEquals(y.sets[9], y.sets[17], "no strain set added in the block\n" + y.trace());
        assertEquals(y.load[9], y.load[17], 1e-9, "no load step either");
        assertTrue(y.sets[52] > y.sets[17], "after it the ladder carries on");
    }

    @Test void theEngineHoldsWhateverElseIsTrue() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L1;
        in.monthIndex = 7;                                  // a month gate met
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.strainPct = 1.0;                                 // low for a week
        in.strainMissDays = 9;
        in.weeksWithoutReadings = 6;
        in.trainingWeeksAtPressure = 2;
        in.inGirthFocus = true;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_HOLD, d.action);
        assertEquals(Plan.LENGTH_FOCUS_HOLD_RULE, d.rule);
        in.inDeloadWeek = true;
        assertEquals(Plan.ACTION_DELOAD, Plan.evaluate(in).action, "the deload tier still applies");
        in.inDeloadWeek = false;
        in.redFlag = true;
        assertEquals(Plan.ACTION_STEP_BACK, Plan.evaluate(in).action, "and so does safety");
    }

    @Test void theClockRestartsOnTheBlocksLastDay() {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        long end = LengthYear.t0() + 17 * LengthYear.WEEK;
        st.focusBlockUntilMs = end;
        st.strainSinceMs = 1000L;
        assertFalse(LengthTrack.focusEnded(st, end - LengthYear.HOUR), "still in the block");
        assertTrue(LengthTrack.focusEnded(st, end + LengthYear.HOUR));
        assertEquals(end, st.strainSinceMs, "the strain clock stands at the block's end");
        assertFalse(LengthTrack.focusEnded(st, end + LengthYear.DAY), "once");
        // A later change to the work is not undone.
        st.setStrainSets(9, end + 3 * LengthYear.DAY);
        assertFalse(LengthTrack.focusEnded(st, end + 4 * LengthYear.DAY));
        assertEquals(end + 3 * LengthYear.DAY, st.strainSinceMs);
    }
}
