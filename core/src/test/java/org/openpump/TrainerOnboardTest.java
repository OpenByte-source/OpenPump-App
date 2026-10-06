package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * THE TRAINER'S OWN SETUP, its pure half (TrainerOnboard): what counts as already set and how
 * it is summarised.
 */
class TrainerOnboardTest {

    @AfterEach void units() {
        Model.Fmt.unit = Model.Fmt.U_INHG;
        Model.Fmt.sizeUnit = Model.Fmt.S_CM;
    }

    /* ---------------------------------------------------------------- already set */

    @Test void theUntouchedDefaultWeekIsNotAChoice() {
        assertFalse(TrainerOnboard.weekChosen(new Schedule(), false));
        assertFalse(TrainerOnboard.weekChosen(null, false));
    }

    @Test void anEnrolledWeekIsAlwaysSet() {
        assertTrue(TrainerOnboard.weekChosen(new Schedule(), true));
    }

    @Test void theFirstRunSetupsWeekReadsAsChosen() {
        Model m = Model.seed();
        FirstRun.begin(m);
        assertTrue(TrainerOnboard.weekChosen(m.sched, false), "Mon/Wed/Fri from the setup");
    }

    @Test void anyDepartureFromTheDefaultIsAChoice() {
        Schedule s = new Schedule();
        s.days[Schedule.SUN] = false;
        assertTrue(TrainerOnboard.weekChosen(s, false), "a day off");
        s = new Schedule();
        s.hour = 7;
        assertTrue(TrainerOnboard.weekChosen(s, false), "another hour");
        s = new Schedule();
        s.minute = 30;
        assertTrue(TrainerOnboard.weekChosen(s, false), "another minute");
        s = new Schedule();
        s.remind = true;
        assertTrue(TrainerOnboard.weekChosen(s, false), "reminders on");
    }

    @Test void theWeekSummarySaysDaysTimeAndReminders() {
        boolean[] mwf = { true, false, true, false, true, false, false };
        assertEquals("Mon, Wed, Fri · 19:00 · reminders on",
            TrainerOnboard.weekSummary(mwf, 19, 0, true));
        assertEquals("Every day · 07:30 · reminders off",
            TrainerOnboard.weekSummary(new boolean[]{ true, true, true, true, true, true, true },
                7, 30, false));
        // Polish SU-13: a run of days is one run.
        assertEquals("Mon\u2013Sat · 19:00 · reminders off",
            TrainerOnboard.weekSummary(new boolean[]{ true, true, true, true, true, true, false },
                19, 0, false));
        assertEquals("Tue, Thu, Sat · 19:00 · reminders off",
            TrainerOnboard.weekSummary(new boolean[]{ false, true, false, true, false, true, false },
                19, 0, false));
        assertEquals("Mon, Tue, Fri\u2013Sun · 19:00 · reminders off",
            TrainerOnboard.weekSummary(new boolean[]{ true, true, false, false, true, true, true },
                19, 0, false));
    }

    @Test void theRackSummaryNamesTheOneInUse() {
        List<Model.Cylinder> r = new ArrayList<Model.Cylinder>();
        assertEquals("", TrainerOnboard.cylindersSummary(r, 0));
        assertEquals("", TrainerOnboard.cylindersSummary(null, 0));
        r.add(new Model.Cylinder("Main cylinder", 5.5, 23.5));
        assertEquals("1 cylinder · Main cylinder in use", TrainerOnboard.cylindersSummary(r, 0));
        r.add(new Model.Cylinder("", 4.5, 20));
        assertEquals("2 cylinders · Cylinder 2 in use", TrainerOnboard.cylindersSummary(r, 1));
        assertEquals("2 cylinders · Main cylinder in use",
            TrainerOnboard.cylindersSummary(r, 9), "an out-of-range index reads as the first");
    }

    /* ---------------------------------------------------------------- load <-> pressure */

    /* ONE VALUE IN TWO FIELDS (owner, 2026-09-30): the length load and the length pressure,
     * linked through the length cylinder's bore. The owner's profile: a 4.5 cm length
     * cylinder, length working pressure -9.7 inHg (33 kPa on the wire). */

    @Test void theOwnersPressureIsItsLoadInTheLengthCylinder() {
        double lb = TrainerOnboard.linkedLoadLb(33, 4.5);
        assertEquals(11.80, lb, 0.01);
        assertEquals("−9.7 inHg", Model.Fmt.p(33));
        assertEquals("11.8 lb", Traction.settingLb(lb));
        assertEquals("Load and pressure are one figure, worked out at your length cylinder's "
            + "4.5 cm inside diameter.", TrainerOnboard.linkedBasis(4.5));
        assertEquals(33, TrainerOnboard.kpaForLinkedLoad(lb, 4.5), 1e-9, "and back");
    }

    @Test void noLengthCylinderNoLoad() {
        assertEquals(0.0, TrainerOnboard.linkedLoadLb(33, 0), 0.0);
        assertEquals(0.0, TrainerOnboard.linkedLoadLb(0, 4.5), 0.0);
        assertTrue(TrainerOnboard.NO_LENGTH_CYLINDER.contains("once a length cylinder is listed"));
    }

    @Test void theLoadStepsOntoItsGrid() {
        double step = TrainerOnboard.LOAD_STEP_LB;
        assertEquals(12.0, TrainerOnboard.bumpLoadLb(11.8, 1, step), 1e-9, "up to a round figure");
        assertEquals(11.5, TrainerOnboard.bumpLoadLb(11.8, -1, step), 1e-9, "down to one");
        assertEquals(12.5, TrainerOnboard.bumpLoadLb(12.0, 1, step), 1e-9, "then whole steps");
        assertEquals(11.5, TrainerOnboard.bumpLoadLb(12.0, -1, step), 1e-9);
        assertEquals(12.5, TrainerOnboard.bumpLoadLb(11.9999999, 1, step), 1e-9,
            "a figure a hair under the grid is on it, so up is the next step");
    }

    @Test void aSteppedLoadIsHeldAsThePressureThatMakesIt() {
        double kpa = TrainerOnboard.kpaForLinkedLoad(12.0, 4.5);
        assertEquals(12.0, TrainerOnboard.linkedLoadLb(kpa, 4.5), 1e-9,
            "the two fields cannot disagree");
        assertEquals("12 lb", Traction.settingLb(TrainerOnboard.linkedLoadLb(kpa, 4.5)));
    }

    @Test void aNewPersonIsToldThePlansOwnStartingLoad() {
        assertEquals("New to pumping: your pulls start at the plan's own 2.5 lb and step up "
            + "from there.", TrainerOnboard.newLoadNote());
    }

    @Test void confirmWritesTheLoadThatMakesTheAnsweredPull() {
        // No offset: the stored load IS the pressure's load.
        double lb = Scale.setupLinkedAnswerLb(33, 0, 4.5, true, 2.5);
        assertEquals(TrainerOnboard.linkedLoadLb(33, 4.5), lb, 1e-9);
        // An answer 1 inHg above the plan's figure: the offset carries that part, and the
        // builder adds it back, so the pull is still the answer's load.
        double off = Model.Fmt.KPA_PER_INHG;
        double plan = Scale.setupLinkedAnswerLb(33, off, 4.5, true, 2.5);
        assertEquals(TrainerOnboard.linkedLoadLb(33, 4.5),
            Scale.commandedLoadLb(plan, off, 4.5, false, 6), 1e-9);
        // No length cylinder: nothing to read a load from.
        assertEquals(Plan.LENGTH_LOAD_START_LB, Scale.setupLinkedAnswerLb(33, 0, 0, true, 7.0),
            0.0, "a first enrolment starts at the plan's load");
        assertEquals(7.0, Scale.setupLinkedAnswerLb(33, 0, 0, false, 7.0), 0.0,
            "a recalibration keeps the load on file");
        // And Confirm's own bounds still hold it.
        assertEquals(15.0, Scale.setupLoadLb(false, true,
            Scale.setupLinkedAnswerLb(50, 0, 4.5, true, 2.5), 2.5), 0.0);
    }

    @Test void whereIAmStatesThePullTheLengthPressureMakes() {
        Model m = new Model();
        m.trainerLengthOn = true;
        m.rxNewToPumping = false;
        m.cylinders.add(new Model.Cylinder("Length cylinder", 4.5, 23.0));
        m.ensureCylinderIds();
        long now = System.currentTimeMillis();
        double off = Model.Fmt.KPA_PER_INHG;
        m.trainerLength.offsetKpa = off;
        m.trainerLength.loadLb = Scale.setupLinkedAnswerLb(33, off, 4.5, true, 2.5);
        m.trainerMonthsPumping = 7;
        m.trainerMonthsAt = now;
        String[] w = Say.whereIAmParts(m, now);
        assertEquals("length " + Traction.settingLb(TrainerOnboard.linkedLoadLb(33, 4.5)),
            w[w.length - 1], "the plan's load with the offset on it: the pressure's load");
    }

    /* ---------------------------------------------------------------- the routines offered */

    @Test void aPlainStartingRoutineIsOffered() {
        assertTrue(TrainerOnboard.offerable(Plan.TRACK_GIRTH_INTERVAL, Plan.ACTION_HOLD, true, false));
        assertTrue(TrainerOnboard.offerable(Plan.TRACK_LENGTH, Plan.ACTION_HOLD, true, false));
        assertTrue(TrainerOnboard.offerable(Plan.TRACK_GIRTH_TRADITIONAL,
            Plan.ACTION_ADD_VOLUME, true, false), "a girth set step is saved like any routine");
        assertTrue(TrainerOnboard.offerable(Plan.TRACK_FEEDER, Plan.ACTION_FEEDER_SUGGEST, true, false));
    }

    @Test void nothingWithoutAPrescription() {
        assertFalse(TrainerOnboard.offerable(Plan.TRACK_GIRTH_INTERVAL, Plan.ACTION_HOLD, false, false));
        assertFalse(TrainerOnboard.offerable(Plan.TRACK_FEEDER, Plan.ACTION_FEEDER_SUGGEST, false, false));
    }

    @Test void aDecisionThatAsksItsOwnQuestionIsNotOffered() {
        int[] asks = { Plan.ACTION_CEILING_DEADLOCK, Plan.ACTION_RAISE_LOAD, Plan.ACTION_REMEASURE,
            Plan.ACTION_GIRTH_FOCUS, Plan.ACTION_DELOAD, Plan.ACTION_LEVEL_UP,
            Plan.ACTION_STEP_BACK };
        for (int i = 0; i < asks.length; i++) {
            assertFalse(TrainerOnboard.offerable(Plan.TRACK_GIRTH_INTERVAL, asks[i], true, false), "" + asks[i]);
            assertFalse(TrainerOnboard.offerable(Plan.TRACK_LENGTH, asks[i], true, false), "" + asks[i]);
        }
        assertFalse(TrainerOnboard.offerable(Plan.TRACK_LENGTH, Plan.ACTION_ADD_VOLUME, true, false),
            "length's add-volume is a strain-set question");
        assertFalse(TrainerOnboard.offerable(Plan.TRACK_FEEDER, Plan.ACTION_FEEDER_PAUSED, true, false));
        assertFalse(TrainerOnboard.offerable(Plan.TRACK_FEEDER, Plan.ACTION_DISABLED, true, false));
    }

    @Test void rowsAreNamedByTrackAndLevel() {
        assertEquals("Girth · interval · L1",
            TrainerOnboard.offerName(Plan.TRACK_GIRTH_INTERVAL, Plan.L1));
        assertEquals("Length · L2", TrainerOnboard.offerName(Plan.TRACK_LENGTH, Plan.L2));
        assertEquals("Feeder", TrainerOnboard.offerName(Plan.TRACK_FEEDER, Plan.L3));
        assertEquals("R", TrainerOnboard.offerLine("R", false));
        assertEquals("R · saved in two parts", TrainerOnboard.offerLine("R", true));
        // Polish SU-17: no "Routine:" on every row.
        assertEquals("Release, then 6 holds", TrainerOnboard.offerLine("Routine: release, then 6 holds",
            false));
    }

    @Test void theSaveButtonSaysHowMany() {
        assertEquals("Save it", TrainerOnboard.saveLabel(1, 1));
        assertEquals("Save", TrainerOnboard.saveLabel(0, 1));
        assertEquals("Save all", TrainerOnboard.saveLabel(3, 3));
        assertEquals("Save 2 ticked", TrainerOnboard.saveLabel(2, 3));
        assertEquals("Save", TrainerOnboard.saveLabel(0, 3));
        assertEquals("Save your first routine", TrainerOnboard.offerTitle(1));
        assertEquals("Save your routines", TrainerOnboard.offerTitle(2));
        assertTrue(TrainerOnboard.offerIntro(3, 3).indexOf("3 routines") > 0);
        assertTrue(TrainerOnboard.offerIntro(2, 1).indexOf("1 routine to save") > 0);
        assertTrue(TrainerOnboard.offerIntro(2, 0).indexOf("Nothing new to save") > 0);
        assertTrue(TrainerOnboard.offerIntro(1, 1).indexOf("the routine it wrote") > 0);
        assertTrue(TrainerOnboard.savedLine(2).startsWith("2 routines saved"));
        // Polish SU-18: no "three days a week" (wrong under alternate days), a real dash.
        assertEquals("3 routines saved — the first is loaded.", TrainerOnboard.savedLine(3));
        assertTrue(TrainerOnboard.savedLine(1).indexOf("three days") < 0);
        assertTrue(TrainerOnboard.offerIntro(3, 3).indexOf("Untick") < 0);
    }

    @Test void theSplitRuleIsSaveMints() {
        assertTrue(Mint.splitsInTwo(true, 2, Plan.TRACK_GIRTH_INTERVAL, false));
        assertFalse(Mint.splitsInTwo(false, 8, Plan.TRACK_GIRTH_INTERVAL, false), "not asked for");
        assertFalse(Mint.splitsInTwo(true, 1, Plan.TRACK_GIRTH_INTERVAL, false), "one set");
        assertFalse(Mint.splitsInTwo(true, 8, Plan.TRACK_FEEDER, false), "the feeder");
        assertFalse(Mint.splitsInTwo(true, 8, Plan.TRACK_LENGTH, true), "a traction session");
        assertTrue(Mint.splitsInTwo(true, 8, Plan.TRACK_LENGTH, false), "expansion length");
    }

    /* ---------------------------------------------------------------- fixes after the emulator */

    @Test void aRestingTrackIsNeverOffered() {
        assertFalse(TrainerOnboard.offerable(Plan.TRACK_LENGTH, Plan.ACTION_HOLD, true, true),
            "a dated length break saves nothing");
        assertFalse(TrainerOnboard.offerable(Plan.TRACK_GIRTH_INTERVAL, Plan.ACTION_HOLD, true, true));
        assertTrue(TrainerOnboard.offerable(Plan.TRACK_LENGTH, Plan.ACTION_HOLD, true, false));
    }

    @Test void aRestingRowSaysUntilWhenAndIsNotSaveable() {
        assertEquals(TrainerOnboard.ROW_RESTING,
            TrainerOnboard.rowState(Plan.TRACK_LENGTH, Plan.ACTION_HOLD, true, true, false));
        assertEquals(TrainerOnboard.ROW_RESTING,
            TrainerOnboard.rowState(Plan.TRACK_LENGTH, Plan.ACTION_HOLD, true, true, true),
            "the rest is the answer whatever else is true");
        assertEquals("Resting until Oct 9",
            TrainerOnboard.rowReason(TrainerOnboard.ROW_RESTING, "Oct 9", Plan.ACTION_HOLD));
    }

    @Test void everyEnabledTrackGetsARowThatSaysWhy() {
        assertEquals(TrainerOnboard.ROW_SAVE,
            TrainerOnboard.rowState(Plan.TRACK_GIRTH_INTERVAL, Plan.ACTION_HOLD, true, false, false));
        assertEquals(TrainerOnboard.ROW_SAVED,
            TrainerOnboard.rowState(Plan.TRACK_GIRTH_INTERVAL, Plan.ACTION_HOLD, true, false, true));
        assertEquals("Already saved, no change",
            TrainerOnboard.rowReason(TrainerOnboard.ROW_SAVED, null, Plan.ACTION_HOLD));
        assertEquals(TrainerOnboard.ROW_ASKS,
            TrainerOnboard.rowState(Plan.TRACK_GIRTH_INTERVAL, Plan.ACTION_LEVEL_UP, true, false, false));
        assertTrue(TrainerOnboard.rowReason(TrainerOnboard.ROW_ASKS, null, Plan.ACTION_LEVEL_UP)
            .indexOf("level up") > 0);
        assertEquals(TrainerOnboard.ROW_NONE,
            TrainerOnboard.rowState(Plan.TRACK_LENGTH, Plan.ACTION_HOLD, false, false, false));
        assertEquals(TrainerOnboard.ROW_NONE,
            TrainerOnboard.rowState(Plan.TRACK_FEEDER, Plan.ACTION_FEEDER_PAUSED, true, false, false));
    }

    @Test void aTableDeloadWeekIsLabelled() {
        assertTrue(TrainerOnboard.tableDeloadNote(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 17)
            .startsWith("Week 17 is a deload week"));
        assertEquals("", TrainerOnboard.tableDeloadNote(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 16));
        assertEquals("", TrainerOnboard.tableDeloadNote(Plan.TRACK_LENGTH, Plan.L1, 17));
        assertEquals("", TrainerOnboard.tableDeloadNote(Plan.TRACK_GIRTH_TRADITIONAL, Plan.L1, 5));
        assertEquals("", TrainerOnboard.tableDeloadNote(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 5));
        assertEquals("", TrainerOnboard.tableDeloadNote(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 99));
    }

    @Test void theWeekIsTheSchedulesNotThree() {
        Schedule s = new Schedule();
        for (int i = 0; i < Schedule.DAYS; i++) s.days[i] = false;
        s.days[Schedule.MON] = s.days[Schedule.TUE] = s.days[Schedule.WED] = s.days[Schedule.FRI] = true;
        assertEquals("Run them on your 4 days a week: Mon, Tue, Wed, Fri.",
            TrainerOnboard.weekRule(s, true, true, Plan.TRACK_GIRTH_INTERVAL, true));
        assertEquals("Run it on your 4 days a week: Mon, Tue, Wed, Fri.",
            TrainerOnboard.weekRule(s, true, false, Plan.TRACK_GIRTH_INTERVAL, false));
        s.plan[Schedule.MON] = Schedule.PLAN_LENGTH;
        s.plan[Schedule.WED] = Schedule.PLAN_LENGTH;
        s.plan[Schedule.TUE] = Schedule.PLAN_GIRTH;
        s.plan[Schedule.FRI] = Schedule.PLAN_GIRTH;
        assertEquals("Girth on 2 days and length on 2 days a week (Mon, Tue, Wed, Fri).",
            TrainerOnboard.weekRule(s, true, true, Plan.TRACK_GIRTH_INTERVAL, true));
        // Week B (2026-10-03): the rule in its own words, both figures in it.
        assertTrue(TrainerOnboard.weekRuleWhy().startsWith(Say.WEEK_RULE));
        assertTrue(Say.WEEK_RULE.contains(Plan.TRAINING_WEEK_FULL_SESSIONS + " full sessions"));
        assertTrue(Say.WEEK_RULE.contains(Plan.TRAINING_WEEK_MIN_DAYS + " shorter"));
    }

    /* ------------------------------------------ length's maximum starts from girth's */

    /** The owner's profile: girth's most −11 inHg, length's own −12. */
    private static final double GIRTH_MAX = 11 * Model.Fmt.KPA_PER_INHG,
                                LENGTH_MAX = 12 * Model.Fmt.KPA_PER_INHG;

    @Test void anUntouchedLengthMaximumShowsGirthsWhileLengthIsOn() {
        assertEquals(GIRTH_MAX, TrainerOnboard.lengthMaxSeed(true, false, 0, GIRTH_MAX), 1e-9,
            "a new setup with length on: girth's answer, not 'not set'");
        assertEquals(GIRTH_MAX + 3, TrainerOnboard.lengthMaxSeed(true, false, GIRTH_MAX,
            GIRTH_MAX + 3), 1e-9, "and it follows girth's while nobody has stepped it");
        assertEquals(0.0, TrainerOnboard.lengthMaxSeed(true, false, 0, 0), 1e-9,
            "girth's unset: length's unset too");
        assertEquals(0.0, TrainerOnboard.lengthMaxSeed(false, false, GIRTH_MAX, GIRTH_MAX), 1e-9,
            "length off: nothing saved for a track nobody turned on");
    }

    @Test void aLengthMaximumThePersonSetIsKept() {
        assertEquals(LENGTH_MAX, TrainerOnboard.lengthMaxSeed(true, true, LENGTH_MAX, GIRTH_MAX),
            1e-9, "stepped to −12: kept whatever girth's is");
        assertEquals(0.0, TrainerOnboard.lengthMaxSeed(true, true, 0, GIRTH_MAX), 1e-9,
            "stepped back to 'not set': kept as not set");
        assertEquals(LENGTH_MAX, TrainerOnboard.lengthMaxSeed(false, true, LENGTH_MAX, GIRTH_MAX),
            1e-9);
    }

    @Test void aSavedLengthMaximumIsTheirsAndAnUnsetOneFollowsGirthWhenLengthComesOn() {
        // A recalibration (the Trainer page's way to turn length on later) starts from the file.
        assertTrue(TrainerOnboard.lengthMaxTouched(LENGTH_MAX), "saved: the person's own");
        assertFalse(TrainerOnboard.lengthMaxTouched(0), "never set: still the seed's");
        Model m = new Model();
        m.rxWorkMaxKpa = GIRTH_MAX;
        m.rxLengthMaxKpa = 0;
        boolean touched = TrainerOnboard.lengthMaxTouched(m.rxLengthMaxKpa);
        assertEquals(GIRTH_MAX, TrainerOnboard.lengthMaxSeed(true, touched, m.rxLengthMaxKpa,
            m.rxWorkMaxKpa), 1e-9, "length turned on later: girth's, as in a new setup");
        // An older file's own migration is untouched: its length maximum is the shared answer.
        Model old = Model.fromJson("{\"ceil\":43,\"sets\":[],\"routines\":[],\"rxMax\":\"37.2\"}");
        assertEquals(old.rxWorkMaxKpa, old.rxLengthMaxKpa, 1e-9);
    }

    /** t10 device walk: under "Alternate days, each track 3 days a week" the week runs Monday to
     *  Saturday whatever is ticked - so the summary does not say the ticked days are kept. */
    @Test void aWeekSetByLongTrainingDaysSaysSo() {
        boolean[] tts = Schedule.ofMask("0101010").days;            // Tue/Thu/Sat ticked
        Schedule split = new Schedule();
        System.arraycopy(tts, 0, split.days, 0, Schedule.DAYS);
        split.longDays = Schedule.LONG_SPLIT;
        split.bothTracks = true;
        String said = TrainerOnboard.weekKept(tts, split.daysInForce());
        assertFalse(said.startsWith("Kept as it is"), said);
        assertTrue(said.contains(LongDays.ROW), said);
        assertEquals(TrainerOnboard.WEEK_KEPT, TrainerOnboard.weekKept(tts, tts.clone()),
            "the person's own days: kept, as before");
    }
}
