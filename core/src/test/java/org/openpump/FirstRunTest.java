package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * THE FIRST-RUN SETUP'S STATE (0.10): who is owed it, what starting it decides, when it is over.
 * The one outcome that must never happen is an existing phone meeting the wizard, so the load
 * rule is held here as hard as the state machine.
 */
class FirstRunTest {

    private static Model fresh() { return Model.seed(); }

    @Test void onlyTheSeedIsNotStarted() {
        assertEquals(FirstRun.NOT_STARTED, Model.seed().firstRun);
        assertEquals(FirstRun.DONE, new Model().firstRun);
    }

    @Test void aFileWithoutTheKeyLoadsAsDone() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(Model.seed().toJson());
        o.remove("firstRun");
        assertEquals(FirstRun.DONE, Model.fromJson(o.toString()).firstRun);
    }

    @Test void theStateSurvivesASaveInEachOfTheThreeValues() {
        for (int v = FirstRun.NOT_STARTED; v <= FirstRun.DONE; v++) {
            Model m = fresh();
            m.firstRun = v;
            assertEquals(v, Model.fromJson(m.toJson()).firstRun);
        }
    }

    @Test void anOutOfRangeValueLoadsInsideTheThreeStates() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(Model.seed().toJson());
        o.put("firstRun", 9);
        assertEquals(FirstRun.DONE, Model.fromJson(o.toString()).firstRun);
        o.put("firstRun", -4);
        assertEquals(FirstRun.NOT_STARTED, Model.fromJson(o.toString()).firstRun);
    }

    @Test void shouldShowIsOwedAndNotBlocked() {
        Model m = fresh();
        assertTrue(FirstRun.shouldShow(m, false, false));
        assertFalse(FirstRun.shouldShow(m, true, false), "never over a live run");
        assertFalse(FirstRun.shouldShow(m, false, true), "never over an unreadable store");
        m.firstRun = FirstRun.IN_PROGRESS;
        assertTrue(FirstRun.shouldShow(m, false, false), "a half-done setup resumes");
        m.firstRun = FirstRun.DONE;
        assertFalse(FirstRun.shouldShow(m, false, false));
    }

    @Test void beginFromNotStartedAppliesTheSuggestedWeek() {
        Model m = fresh();
        m.sched.remind = true;             // begin decides this, whatever it was
        FirstRun.begin(m);
        assertEquals(FirstRun.IN_PROGRESS, m.firstRun);
        assertEquals("1010100", m.sched.mask());
        assertEquals(19, m.sched.hour);
        assertEquals(0, m.sched.minute);
        assertFalse(m.sched.remind);
    }

    @Test void beginWhenResumedChangesNothing() {
        Model m = fresh();
        FirstRun.begin(m);
        m.sched.days[Schedule.TUE] = true;   // the person's own edit, mid-setup
        m.sched.hour = 7;
        m.sched.remind = true;
        String before = m.toJson();
        FirstRun.begin(m);
        assertEquals(FirstRun.IN_PROGRESS, m.firstRun);
        assertEquals(before, m.toJson());
    }

    @Test void beginNeverTouchesADonePhone() {
        Model m = fresh();
        m.firstRun = FirstRun.DONE;
        String before = m.toJson();
        FirstRun.begin(m);
        assertEquals(before, m.toJson());
    }

    @Test void finishIsDoneFromAnyState() {
        for (int v = FirstRun.NOT_STARTED; v <= FirstRun.DONE; v++) {
            Model m = fresh();
            m.firstRun = v;
            FirstRun.finish(m);
            assertEquals(FirstRun.DONE, m.firstRun);
        }
    }

    @Test void measureEveryDaysFollowsTheTrainingWeek() {
        assertEquals(12, FirstRun.measureEveryDays(3));
        assertEquals(5, FirstRun.measureEveryDays(7));
        assertEquals(35, FirstRun.measureEveryDays(1));
        assertEquals(35, FirstRun.measureEveryDays(0), "no days is read as one");
        assertEquals(35, FirstRun.measureEveryDays(-2));
        assertEquals(9, FirstRun.measureEveryDays(4));   // 8.75
        assertEquals(7, FirstRun.measureEveryDays(5));
    }

    @Test void daysKeyBoundaries() {
        assertEquals("few", FirstRun.daysKey(0));
        assertEquals("few", FirstRun.daysKey(2));
        assertEquals("ok", FirstRun.daysKey(3));
        assertEquals("ok", FirstRun.daysKey(4));
        assertEquals("many", FirstRun.daysKey(5));
        assertEquals("many", FirstRun.daysKey(7));
    }

    @Test void theLevelValuesComeFromThePlan() {
        assertEquals(17, FirstRun.LEVEL1_START_KPA);
        assertEquals(34, FirstRun.TOP_LEVEL_KPA);
        assertTrue(Math.abs(FirstRun.LEVEL1_START_KPA - Plan.L1_FLOOR_KPA) < 0.5);
        assertTrue(Math.abs(FirstRun.TOP_LEVEL_KPA - Plan.WORKING_CAP_KPA) < 0.5);
        // The two ends of the ceiling's own range must straddle both, or a branch is unreachable.
        assertTrue(7 < FirstRun.LEVEL1_START_KPA);
        assertTrue(57 > FirstRun.TOP_LEVEL_KPA);
    }

    @Test void theCeilingBranchesSitOnTheirBoundaries() {
        assertTrue(FirstRun.ceilingBelowLevel1(FirstRun.LEVEL1_START_KPA - 1));
        assertFalse(FirstRun.ceilingBelowLevel1(FirstRun.LEVEL1_START_KPA));
        assertFalse(FirstRun.ceilingAboveTop(FirstRun.TOP_LEVEL_KPA));
        assertTrue(FirstRun.ceilingAboveTop(FirstRun.TOP_LEVEL_KPA + 1));
        // The seed's own 40 kPa is above the top: the setup opens on the "plans never go
        // above" line, which is true and worth saying.
        assertTrue(FirstRun.ceilingAboveTop(fresh().ceilKpa));
    }
}
