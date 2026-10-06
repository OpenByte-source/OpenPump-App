package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * R11-4 - THE BRAKE WHILE IT IS WORKING (the owner's pick, option A, and "let the user say if he
 * wants to progress when it's time"). While a track's readings are on target and its at-rest
 * average over the last 4 counted training weeks is above the 4 before, the calendar steps come
 * half as often: girth pressure every 6 training weeks instead of 3, the length climb every 2
 * months, the slow load step every 4 training weeks instead of 2. When the normal step would
 * have been due the Trainer card offers it ("Step up now"). Nothing is ever added because of
 * gains; gains that stall bring the normal pace back.
 */
class GainBrakeTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    /* ------------------------------------------------------------ the at-rest series */

    private static List<Double> series(double... v) {
        List<Double> s = new ArrayList<Double>();
        for (double x : v) s.add(Double.valueOf(x));
        return s;
    }

    @Test
    void gainingIsTheLastFourWeeksAboveTheFourBefore() {
        assertTrue(GainBrake.gaining(series(13.0, 13.0, 13.1, 13.0, 13.1, 13.2, 13.2, 13.3)));
        assertFalse(GainBrake.gaining(series(13.0, 13.1, 13.2, 13.3, 13.3, 13.2, 13.1, 13.0)),
            "the same average: stalled, the normal pace");
        assertFalse(GainBrake.gaining(series(13.2, 13.2, 13.2, 13.2, 13.0, 13.1, 13.0, 13.1)),
            "falling");
        assertFalse(GainBrake.gaining(series(13.0, 13.1, 13.2, 13.3, 13.4, 13.5, 13.6)),
            "fewer than 8 weeks: not yet known");
        assertTrue(GainBrake.gaining(series(12.0, 12.0, 13.0, 13.0, 13.1, 13.1, 13.2, 13.2,
            13.3, 13.3)), "only the last 8 are read");
    }

    /** A model with three girth sessions in each of `weeks` weeks from `t0` and one at-rest
     *  standardised girth reading per week, `cm[i]` in week i, plus a swollen post reading. */
    private static Model history(long t0, double[] cm) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = t0;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        Model.Routine r = new Model.Routine();
        r.id = "rg";
        r.name = "Girth";
        r.trainerTrack = Plan.TRACK_GIRTH_INTERVAL;
        m.routines.add(r);
        for (int w = 0; w < cm.length; w++) {
            long mon = TrainerTab.mondayStartMs(t0) + w * 7 * DAY;
            for (int d = 0; d < 5; d += 2) {
                Model.Sess s = new Model.Sess();
                s.ts = mon + d * DAY + 19 * 3600000L;
                s.durSec = 1800;
                s.id = "s" + s.ts;
                s.routineId = "rg";
                s.routineName = "Girth";
                s.completed = true;
                s.dayKey = PhotoCalendar.dayKey(s.ts);
                m.sessLog.file(s);
            }
            Model.Reading at = new Model.Reading();
            at.ts = mon + 3600000L * 18;
            at.method = Model.Reading.METHOD_STANDARDIZED;
            at.phase = Model.Reading.PHASE_PRE;
            at.gir = cm[w];
            at.len = 18.0;
            m.measLog.all.add(at);
            Model.Reading post = new Model.Reading();
            post.ts = at.ts + 3600000L * 2;
            post.method = Model.Reading.METHOD_STANDARDIZED;
            post.phase = Model.Reading.PHASE_POST;
            post.gir = cm[w] + 1.5;
            post.len = 18.5;
            m.measLog.all.add(post);
        }
        return m;
    }

    @Test
    void theSeriesIsOneAtRestValuePerCountedWeek() {
        long t0 = TrainerTab.mondayStartMs(1_790_000_000_000L);
        double[] cm = { 13.0, 13.0, 13.1, 13.0, 13.1, 13.2, 13.2, 13.3 };
        Model m = history(t0, cm);
        long now = TrainerTab.mondayStartMs(t0) + cm.length * 7 * DAY + DAY;
        List<Double> s = GainBrake.atRestSeries(m, Plan.TRACK_GIRTH_INTERVAL, now);
        assertEquals(cm.length, s.size(), "one value a counted week; the post readings out");
        for (int i = 0; i < cm.length; i++) assertEquals(cm[i], s.get(i).doubleValue(), 1e-9);
        assertTrue(GainBrake.gaining(s));
        List<Double> l = GainBrake.atRestSeries(m, Plan.TRACK_LENGTH, now);
        assertEquals(0, l.size(), "no counted length weeks: no length series");
    }

    @Test
    void notOnAReturnDayOrTheGentleWeek() {
        assertTrue(GainBrake.active(true, true, false));
        assertFalse(GainBrake.active(true, false, false), "readings off target");
        assertFalse(GainBrake.active(false, true, false), "no rise");
        assertFalse(GainBrake.active(true, true, true), "a return day, the gentle week");
    }

    /* --------------------------------------------------------------- the girth step */

    private static Plan.Inputs girth(int weeks) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_TRADITIONAL;
        in.level = Plan.L1;
        in.monthIndex = 0;
        in.pressureKpa = 20;
        in.ceilKpa = 43;
        in.ownTargetsMet = true;
        in.trainingWeeksAtPressure = weeks;
        in.firstDeloadPending = true;
        in.accumulatedTrainingWeeks = 3;
        in.newToPumping = false;
        return in;
    }

    @Test
    void girthPressureStepsEverySixTrainingWeeksWhileGaining() {
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(girth(3)).action, "the normal 3");
        Plan.Inputs b = girth(3);
        b.gainBrake = true;
        Plan.Decision d = Plan.evaluate(b);
        assertEquals(Plan.ACTION_HOLD, d.action, "gaining: the step waits");
        assertEquals(GainBrake.KIND_PRESSURE, GainBrake.kindOf(d));
        Plan.Inputs five = girth(5);
        five.gainBrake = true;
        assertEquals(GainBrake.KIND_PRESSURE, GainBrake.kindOf(Plan.evaluate(five)));
        Plan.Inputs six = girth(6);
        six.gainBrake = true;
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(six).action, "6 weeks: the step");
        Plan.Inputs up = girth(3);
        up.gainBrake = true;
        up.brakeStepUp = true;
        Plan.Decision n = Plan.evaluate(up);
        assertEquals(Plan.ACTION_RAISE_PRESSURE, n.action, "Step up now: the normal step");
        assertEquals(20 + Plan.STEP_HG_KPA, n.pressureKpa, 1e-9);
        Plan.Inputs two = girth(2);
        two.gainBrake = true;
        assertEquals(-1, GainBrake.kindOf(Plan.evaluate(two)),
            "nothing offered before the normal step would be due");
    }

    /* ------------------------------------------------------------- the length steps */

    private static Plan.Inputs length(int months) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L3;
        in.monthIndex = 7;
        in.pressureKpa = 27;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.loadLb = 8.0;
        in.strainSets = 6;
        in.trainingWeeksAtPressure = months;
        return in;
    }

    @Test
    void theLengthClimbComesEveryTwoMonthsWhileGaining() {
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(length(1)).action);
        Plan.Inputs b = length(1);
        b.gainBrake = true;
        Plan.Decision d = Plan.evaluate(b);
        assertEquals(Plan.ACTION_HOLD, d.action);
        assertEquals(GainBrake.KIND_CLIMB, GainBrake.kindOf(d));
        Plan.Inputs two = length(2);
        two.gainBrake = true;
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(two).action);
        Plan.Inputs up = length(1);
        up.gainBrake = true;
        up.brakeStepUp = true;
        assertEquals(Plan.ACTION_RAISE_PRESSURE, Plan.evaluate(up).action);
    }

    private static Plan.Inputs slow() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L2;
        in.monthIndex = 4;
        in.pressureKpa = 30;
        in.ceilKpa = 43;
        in.firstDeloadPending = false;
        in.lengthTube = true;
        in.boreCm = 4.5;
        in.loadLb = 3.0;
        in.strainSets = 6;
        in.lengthLoadMode = Model.LENGTH_LOAD_SLOW;
        in.slowLoadDue = true;
        return in;
    }

    @Test
    void theSlowLoadStepComesEveryFourTrainingWeeksWhileGaining() {
        assertEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(slow()).action);
        Plan.Inputs b = slow();
        b.gainBrake = true;
        b.slowLoadBraked = true;
        Plan.Decision d = Plan.evaluate(b);
        assertEquals(Plan.ACTION_HOLD, d.action);
        assertEquals(GainBrake.KIND_SLOW, GainBrake.kindOf(d));
        Plan.Inputs up = slow();
        up.gainBrake = true;
        up.slowLoadBraked = true;
        up.brakeStepUpSlow = true;
        assertEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(up).action);
        Plan.Inputs four = slow();
        four.gainBrake = true;
        assertEquals(Plan.ACTION_RAISE_LOAD, Plan.evaluate(four).action, "4 weeks: not braked");
        assertTrue(GainBrake.slowBraked(true, 2) && GainBrake.slowBraked(true, 3));
        assertFalse(GainBrake.slowBraked(true, 4));
        assertFalse(GainBrake.slowBraked(false, 2));
    }

    @Test
    void neverAddsAnythingBecauseOfGains() {
        // Off the step, a gaining track gets exactly what it would get otherwise.
        Plan.Inputs a = girth(1), b = girth(1);
        b.gainBrake = true;
        assertEquals(Plan.evaluate(a).action, Plan.evaluate(b).action);
        assertEquals(Plan.evaluate(a).rule, Plan.evaluate(b).rule);
    }

    /* -------------------------------------------------------------------- the card */

    @Test
    void theCardSaysWhenAndOffersTheStep() {
        assertEquals("You're gaining, so the next step waits until Mon 26 Oct. Step up now?",
            GainBrake.offerWords("Mon 26 Oct"));
        assertEquals("You're gaining, so the next step waits until Mon 26 Oct.",
            GainBrake.waitWords("Mon 26 Oct"));
        assertEquals("Step up now", GainBrake.STEP_UP);
        assertEquals("Wait", GainBrake.WAIT);
    }

    @Test
    void anAnswerHoldsForItsStepOnly() {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.setWorkingPressure(30, 1000L);
        long key = GainBrake.clockKey(st, GainBrake.KIND_PRESSURE);
        assertFalse(GainBrake.answered(st, key));
        GainBrake.answer(st, key, GainBrake.ANSWER_STEP_UP);
        assertTrue(GainBrake.answered(st, key));
        assertTrue(GainBrake.steppedUp(st, key));
        st.setWorkingPressure(33, 5000L);   // the step applied: a new clock
        long next = GainBrake.clockKey(st, GainBrake.KIND_PRESSURE);
        assertFalse(GainBrake.answered(st, next), "the next step is asked afresh");
        assertFalse(GainBrake.steppedUp(st, next));
    }

    @Test
    void theAnswerIsSavedAndAnOldFileHasNone() throws Exception {
        Model m = new Model();
        assertEquals(-1L, m.trainerGirth.brakeKey);
        assertEquals(GainBrake.ANSWER_NONE, m.trainerGirth.brakeAnswer);
        m.trainerGirth.brakeKey = 1234L;
        m.trainerGirth.brakeAnswer = GainBrake.ANSWER_WAIT;
        Model back = Model.fromJson(m.toJson());
        assertEquals(1234L, back.trainerGirth.brakeKey);
        assertEquals(GainBrake.ANSWER_WAIT, back.trainerGirth.brakeAnswer);
        assertEquals(-1L, Model.fromJson(new Model().toJson()).trainerLength.brakeKey);
    }
}
