package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * S12 (the owner's decision, 2026-09-26): LENGTH TRAINING COUNTS TOWARD THE PLAN-WIDE DELOAD
 * CADENCE.
 *
 * The deload window is shared by both tracks, but the weeks that bring it due were counted on
 * the girth track alone while girth was on. Somebody training length four days and girth two
 * never brought a deload due. A week now qualifies when three or more of its days carried a
 * trainer session on ANY track - girth in either style, or length. A feeder is a top-up, not
 * a training day, and a manual cycle is not the plan, so neither counts.
 */
class PlanWideCadenceTest {

    /** Monday 2026-09-07 at `hour`:00 local, plus `day` days. */
    private static long at(int day, int hour) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7, hour, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, day);
        return c.getTimeInMillis();
    }

    private static Model model() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerLengthOn = true;
        m.trainerGirthOn = true;
        m.trainerEnrolledAt = at(0, 0);
        String[][] rs = { { "g", "" + Plan.TRACK_GIRTH_INTERVAL },
                          { "t", "" + Plan.TRACK_GIRTH_TRADITIONAL },
                          { "l", "" + Plan.TRACK_LENGTH },
                          { "f", "" + Plan.TRACK_FEEDER },
                          { "own", "" + Model.TRAINER_TRACK_NONE } };
        for (String[] x : rs) {
            Model.Routine r = new Model.Routine();
            r.id = x[0];
            r.trainerTrack = Integer.parseInt(x[1]);
            m.routines.add(r);
        }
        return m;
    }

    private static void file(Model m, String routineId, long ts, boolean manual) {
        Model.Sess s = new Model.Sess();
        s.id = "s" + m.sessLog.all.size();
        s.routineId = routineId;
        s.ts = ts;
        s.manual = manual;
        m.sessLog.all.add(0, s);
    }

    @Test void lengthDaysAndGirthDaysTogetherMakeAWeek() {
        Model m = model();
        file(m, "g", at(0, 9), false);            // Mon girth
        file(m, "l", at(1, 9), false);            // Tue length
        file(m, "l", at(3, 9), false);            // Thu length
        long now = at(6, 20);
        assertEquals(0, TrainerTab.accumulatedTrainingWeeks(m, Plan.TRACK_GIRTH_INTERVAL,
            m.trainerEnrolledAt, now), "the girth track alone had one day - no week");
        assertEquals(1, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, now),
            "three days on any track is a qualifying week");
    }

    @Test void aLengthOnlyWeekCountsWhileGirthIsOn() {
        Model m = model();
        for (int d = 0; d < 7; d += 2) file(m, "l", at(d, 9), false);   // 4 length days
        long now = at(7 * 3 + 6, 20);
        for (int w = 1; w < 4; w++)
            for (int d = 0; d < 7; d += 2) file(m, "l", at(7 * w + d, 9), false);
        assertEquals(4, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, now),
            "four weeks of length every other day brings the deload due as the guidance says");
        assertEquals(0, TrainerTab.accumulatedTrainingWeeks(m, Plan.TRACK_GIRTH_INTERVAL,
            m.trainerEnrolledAt, now), "which the girth-only count never did");
    }

    @Test void bothTracksOnTheSameDayIsOneDay() {
        Model m = model();
        file(m, "g", at(0, 9), false);
        file(m, "l", at(0, 18), false);
        file(m, "g", at(2, 9), false);
        file(m, "l", at(2, 18), false);
        assertEquals(0, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, at(6, 20)),
            "two days, however many sessions, is two days");
    }

    @Test void eitherGirthStyleCounts() {
        Model m = model();
        file(m, "g", at(0, 9), false);
        file(m, "t", at(2, 9), false);
        file(m, "l", at(4, 9), false);
        assertEquals(1, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, at(6, 20)));
    }

    @Test void feedersManualCyclesAndOwnRoutinesDoNotCount() {
        Model m = model();
        file(m, "g", at(0, 9), false);
        file(m, "f", at(1, 9), false);             // a feeder is a top-up
        file(m, "l", at(2, 9), true);              // a manual cycle is not the plan
        file(m, "own", at(3, 9), false);           // the user's own routine
        assertEquals(0, TrainerTab.planTrainingWeeks(m, m.trainerEnrolledAt, at(6, 20)));
    }

    @Test void theFeedersEngineReadsThePlanWideCount() {
        Model m = model();
        m.trainerGirth.level = Plan.L3;
        file(m, "g", at(0, 9), false);
        file(m, "l", at(2, 9), false);
        file(m, "l", at(4, 9), false);
        long now = at(6, 20);
        assertEquals(1, TrainerTab.feederInputs(m, now).accumulatedTrainingWeeks,
            "the feeder pauses for a deload on the same count the tracks use");
    }

    @Test void nothingBeforeTheAnchorAndNothingWithoutOne() {
        Model m = model();
        file(m, "g", at(0, 9), false);
        file(m, "l", at(2, 9), false);
        file(m, "l", at(4, 9), false);
        assertEquals(0, TrainerTab.planTrainingWeeks(m, at(7, 0), at(13, 20)),
            "weeks before the anchor are not walked");
        assertEquals(0, TrainerTab.planTrainingWeeks(m, 0L, at(6, 20)), "no anchor, no count");
        assertEquals(0, TrainerTab.planTrainingWeeks(null, at(0, 0), at(6, 20)));
    }
}
