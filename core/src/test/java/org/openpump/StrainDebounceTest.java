package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ONE STRAIN STEP PER WINDOW. The length ladder adds a strain set (rung 5) or, with the sets
 * spent, half a pound (rung 6) after {@link Plan#LENGTH_MISS_DEBOUNCE_DAYS} of low strain.
 * The miss count ran over the whole reading history and nothing restarted it when a step
 * was taken, so the week that earned one set went on asking: the next training morning
 * offered another, and the owner's Tue/Thu/Sat week with a reading every fifth session at a
 * steady 3% went from 2 to 12 strain sets in eight weeks (the length session 46 -> 101 min).
 * The count now runs only over readings taken after the length work last changed
 * (TrainerTrackState#strainSinceMs), so each step needs a fresh low week of its own.
 */
class StrainDebounceTest {

    private static final long DAY = 86400000L;
    private static final long HOUR = 3600000L;
    private static final int BPSSL = Model.Reading.METHOD_BPSSL;

    /** A Monday at local midnight - the readings pair by LOCAL day (PhotoCalendar.dayKey). */
    private static long monday() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.MARCH, 2, 0, 0, 0);
        return c.getTimeInMillis();
    }

    private static Model.Reading rd(long ts, int phase, double cm) {
        Model.Reading r = new Model.Reading();
        r.ts = ts; r.method = BPSSL; r.phase = phase; r.len = cm;
        return r;
    }

    /** One length sitting: cold length before, 1.5% longer after - under the 2% floor (t10
     *  option D; it was 3% under the old 4%). */
    private static void sitting(Model.MeasLog log, long ts) {
        log.all.add(rd(ts, Model.Reading.PHASE_PRE, 16.0));
        log.all.add(rd(ts + 50 * 60000L, Model.Reading.PHASE_POST, 16.0 * 1.015));
    }

    /** The owner's length track as the app asks the ladder about it (SessionActivity's
     *  length inputs): L3, month 7, the Length cylinder marked, ceiling 43. */
    private static Plan.Inputs inputs(Model.TrainerTrackState st, Model.MeasLog log,
                                      long now, boolean clock) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.pressureKpa = 34;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.trainingWeeksAtPressure = 0;      // the coda's creep is not what is under test
        in.fitState = Traction.FIT_TRACTION;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.girthCm = 12.7;
        Double s = Meas.strainPct(log, BPSSL, now);
        Double f = Meas.fatiguePct(log, BPSSL, now);
        in.strainPct = s == null ? Double.NaN : s.doubleValue();
        in.fatiguePct = f == null ? Double.NaN : f.doubleValue();
        in.strainMissDays = clock
            ? Meas.strainMissDays(log, BPSSL, Plan.LENGTH_STRAIN_LO, st.strainSinceMs, now)
            : Meas.strainMissDays(log, BPSSL, Plan.LENGTH_STRAIN_LO, now);
        in.loadLb = st.loadLb;
        in.strainSets = st.strainSets;
        return in;
    }

    /** What the owner's weeks did: Tue/Thu/Sat, length then girth each day, a sitting at
     *  every fifth run (the app's default cadence) - so a length sitting every fifth training
     *  day. Each morning the ladder is asked and whatever it offers is accepted at once, then
     *  asked again (the Trainer redraws on the tap). Returns the times steps were taken. */
    private static List<Long> run(Model.TrainerTrackState st, Model.MeasLog log, int weeks,
                                  boolean clock, int[] reoffers) {
        List<Long> steps = new ArrayList<Long>();
        long mon = monday();
        int runs = 0;
        int[] days = { 1, 3, 5 };                        // Tue, Thu, Sat
        for (int w = 0; w < weeks; w++) {
            for (int d = 0; d < days.length; d++) {
                long day = mon + (w * 7L + days[d]) * DAY;
                long morning = day + 8 * HOUR;
                Plan.Decision dec = Plan.evaluate(inputs(st, log, morning, clock));
                if (accept(st, dec, morning)) {
                    steps.add(Long.valueOf(morning));
                    Plan.Decision again = Plan.evaluate(inputs(st, log, morning + 60000L, clock));
                    if (again.action == Plan.ACTION_ADD_VOLUME
                            || again.action == Plan.ACTION_RAISE_LOAD) reoffers[0]++;
                }
                // The length run, then the girth run; a sitting on every fifth.
                runs++;
                if (runs % 5 == 0) sitting(log, day + 18 * HOUR);
                runs++;                                  // girth: its sitting is not BPSSL
            }
        }
        return steps;
    }

    private static boolean accept(Model.TrainerTrackState st, Plan.Decision dec, long now) {
        if (dec.action == Plan.ACTION_ADD_VOLUME) {
            st.setStrainSets(Math.min(Plan.LENGTH_STRAIN_SETS_MAX,
                                      st.strainSets + Math.max(1, dec.setsDelta)), now);
            return true;
        }
        if (dec.action == Plan.ACTION_RAISE_LOAD) {
            st.setLoadLb(dec.loadLb, now);
            return true;
        }
        return false;
    }

    private static Model.TrainerTrackState owner(int sets, double lb) {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L3;
        st.strainSets = sets;
        st.loadLb = lb;
        return st;
    }

    /** Each step has its own week: the first BPSSL reading after the previous step is at
     *  least the debounce old when the next is taken. */
    private static void assertOnePerWindow(List<Long> steps, Model.MeasLog log) {
        for (int i = 1; i < steps.size(); i++) {
            long prev = steps.get(i - 1).longValue(), at = steps.get(i).longValue();
            long first = Long.MAX_VALUE;
            for (int j = 0; j < log.all.size(); j++) {
                long ts = log.all.get(j).ts;
                if (ts > prev && ts < first) first = ts;
            }
            assertTrue(first < at, "step " + i + " was taken with no reading since step "
                + (i - 1));
            assertTrue(at - first >= Plan.LENGTH_MISS_DEBOUNCE_DAYS * DAY,
                "step " + i + " came " + (at - first) / DAY + " days after the first reading "
                + "on the new work - under the " + Plan.LENGTH_MISS_DEBOUNCE_DAYS + "-day window");
        }
    }

    @Test void theOwnersEightWeeksAddOneSetPerWindow() {
        // THE BUG, as the simulator saw it: counted over the whole history.
        Model.TrainerTrackState was = owner(2, 12.1);
        int[] wasAgain = { 0 };
        List<Long> wasSteps = run(was, new Model.MeasLog(), 8, false, wasAgain);
        assertTrue(wasAgain[0] > 0, "without the clock, the tap's own redraw offers another set");
        assertTrue(was.strainSets >= 8, "...and the sets run away: 2 -> " + was.strainSets);

        // THE FIX: only readings since the work last changed.
        Model.TrainerTrackState st = owner(2, 12.1);
        Model.MeasLog log = new Model.MeasLog();
        int[] again = { 0 };
        List<Long> steps = run(st, log, 8, true, again);
        assertEquals(0, again[0], "a step taken is never offered again on the same look");
        assertTrue(steps.size() >= 1, "the low strain still adds a set - the rule is not lost");
        assertOnePerWindow(steps, log);
        assertTrue(st.strainSets <= 4, "eight weeks of a reading every fifth training day is a "
            + "few windows, not ten: 2 -> " + st.strainSets);
        assertTrue(st.strainSets < was.strainSets);
        assertEquals(steps.get(steps.size() - 1).longValue(), st.strainSinceMs,
            "the clock stands at the last step");
    }

    @Test void theLoadStepKeepsTheSameWindow() {
        // Sets spent, load under its cap: rung 6, one half-pound per window.
        Model.TrainerTrackState st = owner(Plan.LENGTH_STRAIN_SETS_MAX, 10.0);
        Model.MeasLog log = new Model.MeasLog();
        int[] again = { 0 };
        List<Long> steps = run(st, log, 12, true, again);
        assertEquals(0, again[0], "a load step is never offered again on the same look");
        assertTrue(steps.size() >= 1, "the load still rises on low strain");
        assertOnePerWindow(steps, log);
        assertTrue(st.loadLb <= 12.0 + 1e-9, "never past the plan's cap");
    }

    @Test void theMissCountReadsOnlyTheNewWork() {
        long now = monday() + 30 * DAY + 2 * HOUR;   // later in the day than any sitting
        Model.MeasLog log = new Model.MeasLog();
        sitting(log, now - 20 * DAY - 2 * HOUR);
        sitting(log, now - 10 * DAY - 2 * HOUR);
        assertEquals(20, Meas.strainMissDays(log, BPSSL, Plan.LENGTH_STRAIN_LO, now),
            "from the whole history, the run is twenty days old");
        assertEquals(20, Meas.strainMissDays(log, BPSSL, Plan.LENGTH_STRAIN_LO, 0L, now),
            "0 is every reading, as before the clock");
        assertEquals(10, Meas.strainMissDays(log, BPSSL, Plan.LENGTH_STRAIN_LO,
                                             now - 15 * DAY, now),
            "a change fifteen days ago leaves only the reading after it");
        assertEquals(0, Meas.strainMissDays(log, BPSSL, Plan.LENGTH_STRAIN_LO,
                                            now - 5 * DAY, now),
            "nothing measured since the change: nothing to debounce yet");
    }

    @Test void onlyAChangeRestartsTheClock() {
        Model.TrainerTrackState st = owner(3, 10.0);
        st.setStrainSets(3, 100L);
        assertEquals(0L, st.strainSinceMs, "the same count written again is no change");
        st.setStrainSets(4, 200L);
        assertEquals(200L, st.strainSinceMs);
        st.setLoadLb(10.0, 300L);
        assertEquals(200L, st.strainSinceMs, "nor the same load");
        st.setLoadLb(10.5, 400L);
        assertEquals(400L, st.strainSinceMs, "a heavier pull is new work");
        st.setLoadLb(10.0, 500L);
        assertEquals(500L, st.strainSinceMs, "and so is a lighter one");
    }

    @Test void theClockIsSavedAndAnOldFileCountsEveryReading() throws Exception {
        Model m = new Model();
        assertEquals(0L, m.trainerLength.strainSinceMs);
        m.trainerLength.setStrainSets(5, 1788440800000L);
        Model back = Model.fromJson(m.toJson());
        assertEquals(1788440800000L, back.trainerLength.strainSinceMs);
        assertEquals(5, back.trainerLength.strainSets);
        org.json.JSONObject o = m.trainerLength.toJson();
        o.remove("strainSince");
        assertEquals(0L, Model.TrainerTrackState.fromJson(o).strainSinceMs,
            "a file from before the clock: every reading counts");
        assertNotEquals(0L, Model.TrainerTrackState.fromJson(m.trainerLength.toJson())
            .strainSinceMs);
        back.trainerLength.strainSinceMs = -5L;
        back.clampAll();
        assertEquals(0L, back.trainerLength.strainSinceMs, "a negative clock is every reading");
    }
}
