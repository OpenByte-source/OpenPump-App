package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * t10 D0 - THE INTERFACES THE LANES BUILD ON: the new saved fields (their defaults for a new
 * setup and for an upgrader, a save and a load, a hand-edited file), Long training days and
 * its migration mapping, the new Plan constants and Inputs fields, and the one 90-minute
 * check. None of it changes what the plan does yet.
 */
class T10InterfacesTest {

    /** A file from before t10, with a trainer set up: none of the new keys. */
    private static final String OLD_ENROLLED =
        "{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],\"trainerOn\":true,"
        + "\"trainerMonths\":7,\"trainerLengthOn\":true,"
        + "\"trainerGirth\":[{\"level\":3,\"week\":0,\"pressureKpa\":\"30.0\"}],"
        + "\"trainerLength\":[{\"level\":2,\"week\":0,\"pressureKpa\":\"34.0\"}]}";

    /** The same file with no trainer set up. */
    private static final String OLD_PLAIN =
        "{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],\"routines\":[]}";

    /* ---- the saved settings ----------------------------------------------------------- */

    @Test void aNewModelHasTheOwnersDefaultsAndOwesNothing() {
        Model m = new Model();
        assertEquals(Model.R4_WARM, m.girthAfterLength);
        assertEquals(Model.LENGTH_LOAD_SLOW, m.lengthLoadMode);
        assertEquals(Model.DROP_LOWERED_NONE, m.dropLoweredSaidKpa);
        assertTrue(m.planT10Seen);
        assertEquals(0, m.planT10Laters);
        m.trainerEnrolled = true;
        assertFalse(m.planChangedT10Due(), "a setup under the new rules owes no card");
        assertEquals("planT10Seen", Model.PLAN_CHANGED_T10_KEY);
    }

    @Test void anOldFileLoadsTheDefaults() {
        Model m = Model.fromJson(OLD_ENROLLED);
        assertEquals(Model.R4_WARM, m.girthAfterLength);
        assertEquals(Model.LENGTH_LOAD_SLOW, m.lengthLoadMode);
        assertEquals(-1, m.dropLoweredSaidKpa);
        assertEquals(Schedule.LONG_COMBINED, m.sched.longDays, "no week saved: as now");
    }

    @Test void theSettingsSurviveASave() {
        Model m = Model.fromJson(OLD_ENROLLED);
        m.girthAfterLength = Model.R4_NONE;
        m.lengthLoadMode = Model.LENGTH_LOAD_AFTER12;
        m.dropLoweredSaidKpa = 26;
        m.sched.longDays = Schedule.LONG_CAP90;
        Model back = Model.fromJson(m.toJson());
        assertEquals(Model.R4_NONE, back.girthAfterLength);
        assertEquals(Model.LENGTH_LOAD_AFTER12, back.lengthLoadMode);
        assertEquals(26, back.dropLoweredSaidKpa);
        assertEquals(Schedule.LONG_CAP90, back.sched.longDays);
    }

    @Test void handEditedSettingsAreHeld() throws Exception {
        JSONObject o = new JSONObject(Model.fromJson(OLD_ENROLLED).toJson());
        o.put("r4Mode", 7);
        o.put("lenLoad", -2);
        o.put("dropSaid", 999);
        o.put("planT10Later", 50);
        Model m = Model.fromJson(o.toString());
        assertEquals(Model.GIRTH_AFTER_LENGTH_DEFAULT, m.girthAfterLength);
        assertEquals(Model.LENGTH_LOAD_DEFAULT, m.lengthLoadMode);
        assertEquals(Model.DROP_LOWERED_NONE, m.dropLoweredSaidKpa);
        assertEquals(Model.PLAN_T10_LATER_MAX, m.planT10Laters);
    }

    /* ---- the upgrade card's marker ----------------------------------------------------- */

    @Test void anUpgradedTrainerOwesTheCardUntilTheSetupRuns() {
        Model m = Model.fromJson(OLD_ENROLLED);
        assertFalse(m.planT10Seen);
        assertTrue(m.planChangedT10Due());
        assertTrue(m.planChangedT10Card());
        Model saved = Model.fromJson(m.toJson());
        assertTrue(saved.planChangedT10Due(), "a save made before the answer keeps it owed");
        saved.answerPlanT10Setup();
        Model after = Model.fromJson(saved.toJson());
        assertFalse(after.planChangedT10Due(), "settled for good");
    }

    @Test void aFileWithNoTrainerOwesNothing() {
        Model m = Model.fromJson(OLD_PLAIN);
        assertTrue(m.planT10Seen);
        m.trainerEnrolled = true;   // enrolling later is a new setup
        assertFalse(m.planChangedT10Due());
    }

    @Test void laterPutsTheCardOffThreeTimesThenItIsARow() {
        Model m = Model.fromJson(OLD_ENROLLED);
        for (int i = 0; i < 5; i++) m.answerPlanT10Later();
        assertEquals(Model.PLAN_T10_LATER_MAX, m.planT10Laters);
        Model back = Model.fromJson(m.toJson());
        assertTrue(back.planChangedT10Due(), "still owed");
        assertFalse(back.planChangedT10Card(), "but no longer as a card");
    }

    /* ---- the tracks' new state --------------------------------------------------------- */

    @Test void anOldTrackLoadsWithNothingPending() {
        Model m = Model.fromJson(OLD_ENROLLED);
        Model.TrainerTrackState[] ts = { m.trainerGirth, m.trainerLength };
        for (int i = 0; i < ts.length; i++) {
            Model.TrainerTrackState t = ts[i];
            assertFalse(t.addPending);
            assertEquals(0, t.hybridYield);
            assertEquals(0, t.r2ExHolds);
            assertEquals(0, t.r2PendKpa);
            assertFalse(t.dAddedInBlock);
            assertFalse(t.dOfferedInBlock);
            assertFalse(t.warned12);
            assertEquals(Model.TrainerTrackState.SLOW_LOAD_NONE, t.slowLoadWeeks);
            assertEquals(0L, t.lastCutMs);
            assertEquals(0, t.cutsInRow);
        }
        assertFalse(new Model().trainerLength.handedOver, "a new track has not handed over");
    }

    @Test void anUpgraderPastMonthThreeIsHandedOverAlready() {
        assertTrue(Model.fromJson(OLD_ENROLLED).trainerLength.handedOver, "7 months in");
        assertFalse(Model.fromJson(OLD_ENROLLED.replace("\"trainerMonths\":7",
                                                        "\"trainerMonths\":1"))
                         .trainerLength.handedOver, "month 1, nothing elapsed");
        assertFalse(Model.fromJson(OLD_PLAIN).trainerLength.handedOver, "no trainer");
        assertFalse(Model.fromJson(OLD_ENROLLED.replace("\"week\":0,\"pressureKpa\":\"34.0\"",
                "\"week\":0,\"pressureKpa\":\"34.0\",\"handedOver\":false"))
                .trainerLength.handedOver, "a saved answer is kept");
    }

    @Test void theTrackStateSurvivesASave() {
        Model m = Model.fromJson(OLD_ENROLLED);
        Model.TrainerTrackState g = m.trainerGirth, l = m.trainerLength;
        g.addPending = true; g.hybridYield = 2; g.r2ExHolds = 3; g.r2PendKpa = 4;
        l.dAddedInBlock = true; l.dOfferedInBlock = true; l.handedOver = false;
        l.warned12 = true; l.slowLoadWeeks = 9; l.lastCutMs = 1788520000000L; l.cutsInRow = 2;
        Model back = Model.fromJson(m.toJson());
        Model.TrainerTrackState bg = back.trainerGirth, bl = back.trainerLength;
        assertTrue(bg.addPending);
        assertEquals(2, bg.hybridYield);
        assertEquals(3, bg.r2ExHolds);
        assertEquals(4, bg.r2PendKpa);
        assertTrue(bl.dAddedInBlock);
        assertTrue(bl.dOfferedInBlock);
        assertFalse(bl.handedOver, "a saved false is not re-migrated");
        assertTrue(bl.warned12);
        assertEquals(9, bl.slowLoadWeeks);
        assertEquals(1788520000000L, bl.lastCutMs);
        assertEquals(2, bl.cutsInRow);
    }

    @Test void handEditedTrackStateIsHeld() throws Exception {
        JSONObject o = new JSONObject(Model.fromJson(OLD_ENROLLED).toJson());
        JSONObject g = o.getJSONArray("trainerGirth").getJSONObject(0);
        g.put("hybridYield", 40); g.put("r2ExHolds", -3); g.put("r2PendKpa", 999);
        JSONObject l = o.getJSONArray("trainerLength").getJSONObject(0);
        l.put("slowLoadWk", -9); l.put("lastCut", "-5"); l.put("cutsInRow", -1);
        Model m = Model.fromJson(o.toString());
        assertEquals(Model.TrainerTrackState.HYBRID_YIELD_MAX, m.trainerGirth.hybridYield);
        assertEquals(0, m.trainerGirth.r2ExHolds);
        assertEquals(50, m.trainerGirth.r2PendKpa, "15 inHg, whole kPa");
        assertEquals(-1, m.trainerLength.slowLoadWeeks);
        assertEquals(0L, m.trainerLength.lastCutMs);
        assertEquals(0, m.trainerLength.cutsInRow);
    }

    @Test void theStrainClockRestartsWithoutMovingTheWork() {
        Model.TrainerTrackState t = new Model.TrainerTrackState();
        int sets = t.strainSets;
        double lb = t.loadLb;
        t.restartStrainClock(5000L);
        assertEquals(5000L, t.strainSinceMs);
        assertEquals(sets, t.strainSets);
        assertEquals(lb, t.loadLb, 1e-9);
    }

    /* ---- Long training days ------------------------------------------------------------ */

    private static int[] plan(String digits) {
        int[] p = new int[Schedule.DAYS];
        for (int i = 0; i < Schedule.DAYS; i++) p[i] = digits.charAt(i) - '0';
        return p;
    }

    @Test void theMigrationMapsAnAlternatingMondayToSaturdayToSplit() {
        boolean[] monSat = Schedule.alternateDays();
        assertEquals(Schedule.LONG_SPLIT, Schedule.longDaysFor(monSat, plan("1212120")),
            "GLGLGL-");
        assertEquals(Schedule.LONG_SPLIT, Schedule.longDaysFor(monSat, plan("2121210")),
            "LGLGLG-");
        assertEquals(Schedule.LONG_SPLIT, Schedule.longDaysFor(monSat,
            Schedule.alternatePlan(monSat, Schedule.PLAN_GIRTH)), "what the setup writes");
    }

    @Test void otherAlternatingDaysAreAlternateAndEverythingElseCombined() {
        boolean[] tts = Schedule.ofMask("0101010").days;
        assertEquals(Schedule.LONG_ALTERNATE, Schedule.longDaysFor(tts, plan("0201020")));
        boolean[] all = Schedule.ofMask("1111111").days;
        assertEquals(Schedule.LONG_ALTERNATE, Schedule.longDaysFor(all, plan("1212121")),
            "Sunday trained too: not the split week");
        assertEquals(Schedule.LONG_COMBINED, Schedule.longDaysFor(all, plan("0000000")),
            "all the plan's choice");
        assertEquals(Schedule.LONG_COMBINED, Schedule.longDaysFor(all, plan("3333333")),
            "all both");
        assertEquals(Schedule.LONG_COMBINED,
            Schedule.longDaysFor(Schedule.alternateDays(), plan("1122120")), "not alternating");
        assertEquals(Schedule.LONG_COMBINED,
            Schedule.longDaysFor(Schedule.ofMask("0100000").days, plan("0100000")), "one day");
        assertEquals(Schedule.LONG_COMBINED, Schedule.longDaysFor(null, null));
    }

    @Test void longDaysLoadsSavesAndIsHeld() throws Exception {
        Schedule old = Schedule.fromJson(new JSONObject("{\"days\":\"1111110\",\"plan\":\"1212120\"}"));
        assertEquals(Schedule.LONG_SPLIT, old.longDays);
        assertEquals(Schedule.LONG_COMBINED, Schedule.fromJson(null).longDays);
        assertEquals(Schedule.LONG_COMBINED, new Schedule().longDays);
        assertEquals(Schedule.LONG_SPLIT, Schedule.LONG_DAYS_NEW_SETUP);
        old.longDays = Schedule.LONG_CAP90;
        assertEquals(Schedule.LONG_CAP90, Schedule.fromJson(old.toJson()).longDays,
            "a saved value is kept, not re-mapped");
        JSONObject hand = old.toJson();
        hand.put("longDays", 9);
        assertEquals(Schedule.LONG_COMBINED, Schedule.fromJson(hand).longDays);
        hand.put("longDays", -4);
        assertEquals(Schedule.LONG_COMBINED, Schedule.fromJson(hand).longDays);
    }

    @Test void aCopyCarriesLongDays() {
        Schedule s = new Schedule();
        s.longDays = Schedule.LONG_ALTERNATE;
        Schedule c = s.copy();
        assertEquals(Schedule.LONG_ALTERNATE, c.longDays);
        assertTrue(c.sameAs(s));
        c.longDays = Schedule.LONG_SPLIT;
        assertFalse(c.sameAs(s));
    }

    /* ---- Plan ------------------------------------------------------------------------- */

    @Test void theNewActionAndRuleIdsAreTheirOwn() {
        int[] old = { Plan.ACTION_HOLD, Plan.ACTION_ADD_VOLUME, Plan.ACTION_RAISE_PRESSURE,
            Plan.ACTION_DELOAD, Plan.ACTION_STEP_BACK, Plan.ACTION_CEILING_DEADLOCK,
            Plan.ACTION_FEEDER_SUGGEST, Plan.ACTION_FEEDER_PAUSED, Plan.ACTION_DISABLED,
            Plan.ACTION_PAUSE_VOLUME, Plan.ACTION_REDUCE_VOLUME, Plan.ACTION_LEVEL_UP,
            Plan.ACTION_RAISE_LOAD, Plan.ACTION_GIRTH_FOCUS, Plan.ACTION_REMEASURE };
        assertEquals(15, Plan.ACTION_OFFER_BREAK);
        for (int i = 0; i < old.length; i++) assertNotEquals(Plan.ACTION_OFFER_BREAK, old[i]);
        String[] rules = { Plan.R2_RULE, Plan.YIELD_OFFER_RULE, Plan.LENGTH_FELL_RULE,
            Plan.LENGTH_STILL_UNDER_RULE, Plan.LENGTH_HANDOVER_RULE, Plan.LENGTH_SLOW_LOAD_RULE,
            Plan.HELD_AT_90_RULE, Plan.VOLUME_TOP_RULE, Plan.NO_READINGS_RULE,
            Plan.LENGTH_DELOAD_RULE };
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < rules.length; i++) {
            assertTrue(rules[i].length() > 0);
            assertTrue(seen.add(rules[i]), "each rule id is its own: " + rules[i]);
        }
        assertEquals(3, Plan.R4_RAMP_SETS);
        assertEquals(90, Plan.HELD_AT_90_MIN);
        assertEquals(330, Plan.HELD_AT_90_STRAIN_SET_SEC);
    }

    @Test void theNewInputsDefaultOff() {
        Plan.Inputs in = new Plan.Inputs();
        assertFalse(in.levelUpToday);
        assertFalse(in.heldAt90);
        assertFalse(in.addPending);
        assertEquals(0, in.r2ExHolds);
        assertEquals(0, in.r2PendKpa);
        assertEquals(0, in.hybridYield);
        assertFalse(in.blockHadReachLo);
        assertFalse(in.dAddedInBlock);
        assertFalse(in.dOfferedInBlock);
        assertFalse(in.strainHighConfirmed);
        assertFalse(in.lastStrainHigh);
        assertEquals(-1, in.daysSinceLastCut);
        assertTrue(in.handedOver, "nothing to hand over");
        assertFalse(in.slowLoadDue);
        assertEquals(Model.LENGTH_LOAD_AFTER12, in.lengthLoadMode, "the load as before");
    }

    /* ---- the 90-minute check ------------------------------------------------------------ */

    /** Monday 2026-09-07 at 12:00 local. */
    private static long monday() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7, 12, 0, 0);
        return c.getTimeInMillis();
    }

    private static Model.Routine routine(Model m, String id, int track, int min) {
        Model.Routine r = new Model.Routine();
        r.id = id;
        r.name = id;
        r.trainerTrack = track;
        r.stages.add(Model.Stage.restOf("Rest", min * 60));
        m.routines.add(r);
        return r;
    }

    /** Both tracks at L3 with feeders, a both-tracks day of 50 + 38 = 88 minutes (the
     *  feeders' 20 not counted), under "Same days, stop growing at 90 min". */
    private static Model cap90() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        m.trainerGirth.level = Plan.L3;
        m.trainerGirth.lastMintId = routine(m, "g", Plan.TRACK_GIRTH_INTERVAL, 50).id;
        m.trainerLength.lastMintId = routine(m, "l", Plan.TRACK_LENGTH, 38).id;
        m.trainerFeederMintId = routine(m, "f", Plan.TRACK_FEEDER, 10).id;
        m.sched = Schedule.ofMask("0101010");
        m.sched.longDays = Schedule.LONG_CAP90;
        return m;
    }

    @Test void aStepPastNinetyMinutesHolds() {
        Model m = cap90();
        long now = monday();
        assertTrue(TrainerTab.heldAt90(m, Plan.TRACK_GIRTH_INTERVAL, 492, now),
            "88 min + 2 sets of 2:03 = 96.2");
        assertTrue(TrainerTab.heldAt90(m, Plan.TRACK_LENGTH, Plan.HELD_AT_90_STRAIN_SET_SEC, now),
            "88 + 5.5");
        assertFalse(TrainerTab.heldAt90(m, Plan.TRACK_GIRTH_INTERVAL, 120, now), "90 exactly");
        assertTrue(TrainerTab.heldAt90(m, Plan.TRACK_GIRTH_INTERVAL, 121, now), "just past");
    }

    @Test void theCheckIsOnlyForCap90AndADayOfBoth() {
        long now = monday();
        int[] others = { Schedule.LONG_SPLIT, Schedule.LONG_ALTERNATE, Schedule.LONG_COMBINED };
        for (int i = 0; i < others.length; i++) {
            Model m = cap90();
            m.sched.longDays = others[i];
            assertFalse(TrainerTab.heldAt90(m, Plan.TRACK_GIRTH_INTERVAL, 600, now));
        }
        Model one = cap90();
        one.trainerLengthOn = false;
        assertFalse(TrainerTab.heldAt90(one, Plan.TRACK_GIRTH_INTERVAL, 600, now),
            "one track on is as now");
        Model m = cap90();
        assertFalse(TrainerTab.heldAt90(m, Plan.TRACK_FEEDER, 600, now), "no feeder step");
        assertFalse(TrainerTab.heldAt90(m, Plan.TRACK_GIRTH_INTERVAL, 0, now), "adds nothing");
        assertFalse(TrainerTab.heldAt90(null, Plan.TRACK_GIRTH_INTERVAL, 600, now));
    }
}
