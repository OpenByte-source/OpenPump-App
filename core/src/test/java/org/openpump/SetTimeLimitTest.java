package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToDoubleFunction;

import org.junit.jupiter.api.Test;

/**
 * D2 - A SET TIMED "AT PRESSURE ONLY" HAS A TIME LIMIT.
 *
 * The final safety review's blocking finding: in "At pressure only" a set had no time limit.
 * Level 1 commands 17 kPa and the set clock moves only while the pump reads at or above
 * 16.59 kPa; a pump that pulls once and coasts 0.033 - 0.1 kPa/s spends 4 - 12 s of each
 * 125 s cycle above that line, so 840 s of hold time takes 2.4 - 7 hours, and a pump that
 * settles below the line never finishes at all. Nothing ended the set; only the two-hour
 * stop did.
 *
 * The owner's rule: the clock stops lengthening a set once the set's wall time reaches
 *
 *     cap = max(planned, min(2 x planned, Plan.SET_ADVISORY_SEC))
 *
 * so a set is never cut below its plan, is lengthened to at most double, and never past
 * twenty minutes unless it was planned longer (then it is not lengthened at all). At the cap
 * the set ends the way any set ends, at its deadline. A +30 s extends the plan, so it raises
 * "planned"; a Hold is the user's own pause, so held time does not count.
 *
 * The rule is TupClock#capMs and TupClock.SetLimit; the whole-set loop below is
 * SessionActivity#tickTupTiming / #tickHold / #extendPreset in miniature, tick for tick.
 */
class SetTimeLimitTest {

    private static final long S = 1000L;
    /** SessionActivity.RUN_TICK_MS. */
    private static final long TICK_MS = 400L;
    /** Level 1's net line at the default 2 % counting tolerance: 16.93 - 0.34. */
    private static final double LINE = 16.594;
    private static final double AT = 17.0, BELOW = 16.3;
    /** No reading: the pump's 0.0, "no measurement". */
    private static final double BLIND = Double.NaN;

    /* ------------------------------------------------------------------ the rule */

    @Test
    void aShortSetMayBeLengthenedToDoubleItsPlan() {
        assertEquals(240 * S, TupClock.capMs(120 * S), "a two-minute set: at most four");
        assertEquals(120 * S, TupClock.stretchLeftMs(120 * S, 0L));
    }

    @Test
    void aLongerSetIsNeverLengthenedPastTwentyMinutes() {
        assertEquals(1200 * S, TupClock.capMs(875 * S), "the device's 14:35 set: 20:00");
        assertEquals(325 * S, TupClock.stretchLeftMs(875 * S, 0L));
    }

    @Test
    void aSetPlannedOverTwentyMinutesIsNotLengthenedAtAll() {
        assertEquals(1500 * S, TupClock.capMs(1500 * S), "never cut below its plan");
        assertEquals(0L, TupClock.stretchLeftMs(1500 * S, 0L), "and never lengthened");
    }

    @Test
    void theBoundariesAreExact() {
        assertEquals(1200L * S, TupClock.capMs(600 * S), "10:00 is where double meets 20:00");
        assertEquals(1200L * S - 2, TupClock.capMs(600 * S - 1));
        assertEquals(1200L * S, TupClock.capMs(600 * S + 1));
        assertEquals(1200L * S, TupClock.capMs(1200 * S), "20:00 exactly: not lengthened");
        assertEquals(0L, TupClock.stretchLeftMs(1200 * S, 0L));
        assertEquals(1200L * S + 1, TupClock.capMs(1200 * S + 1));
        assertEquals(1L, TupClock.stretchLeftMs(120 * S, 120 * S - 1), "a ms short of the cap");
        assertEquals(0L, TupClock.stretchLeftMs(120 * S, 120 * S), "at the cap");
        assertEquals(0L, TupClock.stretchLeftMs(120 * S, 500 * S), "and never below nothing");
        assertEquals(0L, TupClock.capMs(0L));
        assertEquals(0L, TupClock.stretchLeftMs(0L, 0L));
    }

    /* ---------------------------------------------------------- the running limit */

    @Test
    void theLimitGivesWhatIsLeftAndRefusesTheRest() {
        List<Model.Preset> plan = plan(fixed("s-a", 0, 120 * S));
        TupClock.SetLimit limit = new TupClock.SetLimit();
        limit.start(plan, 0);

        assertEquals(400L, limit.allow(plan, 0, 400L));
        assertFalse(limit.atLimit(plan, 0));
        assertEquals(119_600L, limit.allow(plan, 0, 200_000L), "what was left, and no more");
        assertEquals(80_400L, limit.cutMs(), "the rest was refused");
        assertTrue(limit.atLimit(plan, 0));
        assertEquals(0L, limit.allow(plan, 0, 400L), "at the limit nothing more is given");
        assertEquals(120_000L, limit.stretchedMs());
    }

    @Test
    void plusThirtySecondsExtendsThePlanAndSoTheLimit() {
        List<Model.Preset> shortPlan = plan(fixed("s-a", 0, 120 * S));
        TupClock.SetLimit shortSet = new TupClock.SetLimit();
        shortSet.start(shortPlan, 0);
        shortSet.allow(shortPlan, 0, 120 * S);
        assertTrue(shortSet.atLimit(shortPlan, 0));
        shortSet.extend(RunEdit.EXTEND_MS);
        assertEquals(30 * S, shortSet.allow(shortPlan, 0, 60 * S),
            "planned 2:30 may run 5:00: the +30 s is planned time, so it is doubled too");

        List<Model.Preset> longPlan = plan(fixed("s-b", 0, 900 * S));
        TupClock.SetLimit longSet = new TupClock.SetLimit();
        longSet.start(longPlan, 0);
        longSet.extend(RunEdit.EXTEND_MS);
        assertEquals(270 * S, longSet.allow(longPlan, 0, 600 * S),
            "planned 15:30 still stops at 20:00");
    }

    @Test
    void aRampsStepsShareOneLimitAndAPlusThirtyOnItsLastStepCounts() {
        List<Model.Preset> plan = plan(step("s-r", 0, 0, 120 * S), step("s-r", 0, 1, 120 * S),
                                       step("s-r", 0, 2, 120 * S), step("s-r", 0, 3, 120 * S));
        TupClock.SetLimit limit = new TupClock.SetLimit();
        limit.start(plan, 0);
        assertEquals(480 * S, limit.plannedMs(plan, 0), "the set is its four steps");
        assertEquals(300 * S, limit.allow(plan, 0, 300 * S));
        plan.get(0).durMs += 300 * S;                // setPresetDuration, as the run does
        limit.start(plan, 1);
        assertEquals(480 * S, limit.plannedMs(plan, 1),
            "the step the clock lengthened counts as it was planned, not as it ran");
        assertEquals(180 * S, limit.allow(plan, 1, 300 * S), "8:00 is the set's limit, not "
            + "each step's: one step's stretch is spent for the rest");
        limit.start(plan, 2);
        assertEquals(0L, limit.allow(plan, 2, 400L));
        plan.get(3).durMs += RunEdit.EXTEND_MS;      // +30 s lands on the set's final step
        assertEquals(510 * S, limit.plannedMs(plan, 2), "and the plan reads it where it lands");
        assertEquals(30 * S, limit.allow(plan, 2, 60 * S));
    }

    @Test
    void eachSetHasItsOwnLimitAndARestEndsOne() {
        List<Model.Preset> plan = plan(fixed("s-a", 0, 120 * S), fixed("s-a", 1, 120 * S),
                                       rest(), fixed("s-b", 3, 120 * S));
        TupClock.SetLimit limit = new TupClock.SetLimit();
        limit.start(plan, 0);
        assertEquals(120 * S, limit.allow(plan, 0, 500 * S));
        limit.start(plan, 1);
        assertEquals(120 * S, limit.allow(plan, 1, 500 * S),
            "the same set twice in a row is two sets (Preset#pos), each with its own limit");
        limit.start(plan, 2);
        assertEquals(0L, limit.allow(plan, 2, 400L), "a rest is never lengthened");
        limit.start(plan, 3);
        assertEquals(120 * S, limit.allow(plan, 3, 500 * S));
    }

    @Test
    void theSameStepStartedAgainKeepsItsLimit() {
        // A replay of the step already running - nothing does it today, but a future path
        // that re-armed a preset through resetTupWatch must not hand the set a fresh
        // allowance: by then the step's duration holds every push the clock made.
        List<Model.Preset> plan = plan(fixed("s-a", 0, 120 * S));
        TupClock.SetLimit limit = new TupClock.SetLimit();
        limit.start(plan, 0);
        limit.extend(RunEdit.EXTEND_MS);
        assertEquals(150 * S, limit.allow(plan, 0, 500 * S));
        plan.get(0).durMs += RunEdit.EXTEND_MS + 150 * S;   // setPresetDuration, as the run does
        assertTrue(limit.atLimit(plan, 0));

        limit.start(plan, 0);

        assertEquals(150 * S, limit.plannedMs(plan, 0),
            "planned is still 2:00 and the +30 s, not the duration the pushes grew");
        assertEquals(150 * S, limit.stretchedMs(), "what was stretched stays spent");
        assertEquals(0L, limit.allow(plan, 0, 400L), "so the cap is unchanged: 5:00");
        assertTrue(limit.reachedLimitAtEnd(plan, 0), "and the refused time is still known");
    }

    @Test
    void aPresetTheLimitWasNotStartedOnIsNotLengthened() {
        List<Model.Preset> plan = plan(fixed("s-a", 0, 120 * S), fixed("s-b", 1, 120 * S));
        TupClock.SetLimit limit = new TupClock.SetLimit();
        assertEquals(0L, limit.allow(plan, 0, 400L),
            "nothing started: the set runs by the clock rather than without a limit");
        limit.start(plan, 0);
        assertEquals(0L, limit.allow(plan, 1, 400L), "nor a preset it was not told about");
        limit.forget();
        assertEquals(0L, limit.allow(plan, 0, 400L), "and a run that ended forgets its sets");
    }

    @Test
    void aSetThatEndsShortOfItsTimeAtPressureIsSaidOnceAtItsEnd() {
        List<Model.Preset> plan = plan(step("s-r", 0, 0, 120 * S), step("s-r", 0, 1, 120 * S),
                                       fixed("s-b", 1, 120 * S));
        TupClock.SetLimit limit = new TupClock.SetLimit();
        limit.start(plan, 0);
        limit.allow(plan, 0, 300 * S);
        assertFalse(limit.reachedLimitAtEnd(plan, 0), "the set has a step still to run");
        limit.start(plan, 1);
        assertTrue(limit.reachedLimitAtEnd(plan, 1), "its last step ends at the limit");
        assertFalse(limit.reachedLimitAtEnd(plan, 1), "said once");
        limit.start(plan, 2);
        limit.allow(plan, 2, 60 * S);
        assertFalse(limit.reachedLimitAtEnd(plan, 2),
            "a set the clock lengthened within its limit finished its time: nothing to say");
    }

    /* ------------------------------------- what the summary says, from the record */

    @Test
    void theDeliveredCardSaysHowManySetsReachedTheirLimit() {
        Model m = new Model();
        assertEquals("2 sets reached their time limit: the pump didn't stay at pressure long "
            + "enough to finish them.", PlannedTime.rows(m, sess(2), true).limit);
        assertEquals("1 set reached its time limit: the pump didn't stay at pressure long "
            + "enough to finish it.", PlannedTime.rows(m, sess(1), true).limit);
        assertEquals(PlannedTime.rows(m, sess(2), true).limit,
            PlannedTime.rows(m, sess(2), false).limit,
            "a fact about this run, whatever Set timing says when the summary is reopened");
        assertEquals(null, PlannedTime.rows(m, sess(0), true).limit, "none reached it");
        assertEquals(null, PlannedTime.rows(m, sess(-1), true).limit,
            "a record filed before the limit existed says nothing it cannot know");
    }

    @Test
    void theCountSurvivesASaveAndAnOldRecordReadsNotRecorded() throws Exception {
        org.json.JSONObject o = sess(3).toJson();
        assertEquals(3, Model.Sess.fromJson(o).setsAtLimit, "survives a save");
        o.remove("tupLim");
        assertEquals(-1, Model.Sess.fromJson(o).setsAtLimit,
            "absent means not recorded, never 0");
    }

    private static Model.Sess sess(int atLimit) {
        Model.Sess s = new Model.Sess();
        s.id = "sess-d2";
        s.setsAtLimit = atLimit;
        return s;
    }

    /* ------------------------------------------------- one set, tick for tick */

    @Test
    void aPumpThatSettlesBelowTheLineEndsTheSetAtItsLimit() {
        One two = time(fixed("s-a", 0, 120 * S), t -> BELOW, -1, 0, -1);
        assertEquals(240 * S, two.wallMs, "a 2:00 set ends at 4:00");
        assertTrue(two.saidAtEnd, "and says why");

        One device = time(fixed("s-a", 0, 875 * S), t -> BELOW, -1, 0, -1);
        assertEquals(1200 * S, device.wallMs, "the device's 14:35 set ends at 20:00, not "
            + "at the two-hour stop");

        One long25 = time(fixed("s-a", 0, 1500 * S), t -> BELOW, -1, 0, -1);
        assertEquals(1500 * S, long25.wallMs, "a set planned 25:00 runs 25:00");
        assertEquals(0L, long25.pushedMs, "and is not lengthened at all");
    }

    @Test
    void theReviewsCoastingPumpEndsTheDevicesSetAtTwentyMinutesNotHoursLater() {
        // Pulls to 17 kPa once per 125 s cycle and coasts at the hardware's documented rates:
        // 0.033 kPa/s spends ~12 s of each cycle above the 16.59 line, 0.1 kPa/s ~4 s.
        for (double rate : new double[]{ 0.033, 0.1 }) {
            One ran = time(fixed("s-a", 0, 875 * S),
                           t -> AT - rate * ((t % 125_000L) / 1000.0), -1, 0, -1);
            assertEquals(1200 * S, ran.wallMs, "coasting at " + rate + " kPa/s: 20:00, where "
                + "840 s of hold time used to need 2.4 - 7 hours");
            assertTrue(ran.saidAtEnd, "and it says the set did not get its time at pressure");
        }
    }

    @Test
    void aPumpReadingNothingEndsTheSetAtItsLimitToo() {
        One blind = time(fixed("s-a", 0, 120 * S), t -> BLIND, -1, 0, -1);
        assertEquals(240 * S, blind.wallMs, "a sensor reading 0.0 no longer holds a set open");
        assertTrue(blind.saidAtEnd);
    }

    @Test
    void aSetThatReachesPressureRunsItsPlanOrItsTimeAtPressureAsBefore() {
        One at = time(fixed("s-a", 0, 120 * S), t -> AT, -1, 0, -1);
        assertEquals(120 * S, at.wallMs, "at pressure throughout: its plan");
        assertFalse(at.saidAtEnd);

        // At pressure two seconds in three: 120 s at pressure takes 180 s, inside the limit.
        One coasting = time(fixed("s-a", 0, 120 * S), t -> (t % 3000) < 2000 ? AT : BELOW,
                            -1, 0, -1);
        assertTrue(coasting.wallMs > 170 * S && coasting.wallMs < 190 * S,
            "lengthened to deliver its time at pressure: " + coasting.wallMs);
        assertEquals(0L, coasting.cutMs, "the limit took nothing from it");
        assertFalse(coasting.saidAtEnd);
    }

    @Test
    void theBoundaryIsTheCapExactly() {
        One exact = time(fixed("s-a", 0, 120 * S), t -> t <= 120 * S ? BELOW : AT, -1, 0, -1);
        assertEquals(240 * S, exact.wallMs, "a set that needs exactly its stretch gets it");
        assertEquals(0L, exact.cutMs);
        assertFalse(exact.saidAtEnd, "and finished its time at pressure");

        One over = time(fixed("s-a", 0, 120 * S), t -> t <= 120 * S + TICK_MS ? BELOW : AT,
                        -1, 0, -1);
        assertEquals(240 * S, over.wallMs, "one tick more below the line: still 4:00");
        assertEquals(TICK_MS, over.cutMs, "that tick is what the limit refused");
        assertTrue(over.saidAtEnd, "so it ended short of its time at pressure, and says so");
    }

    @Test
    void heldTimeDoesNotCountTowardTheLimit() {
        One held = time(fixed("s-a", 0, 120 * S), t -> BELOW, 30 * S, 60 * S, -1);
        assertEquals(60 * S, held.heldMs);
        assertEquals(240 * S + 60 * S, held.wallMs,
            "the user chose to hold; the minute held is theirs, on top of the 4:00");
    }

    @Test
    void plusThirtyRaisesTheLimitItBelongsTo() {
        One early = time(fixed("s-a", 0, 120 * S), t -> BELOW, -1, 0, 10 * S);
        assertEquals(300 * S, early.wallMs, "planned 2:30 now: at most 5:00");

        One late = time(fixed("s-a", 0, 120 * S), t -> BELOW, -1, 0, 200 * S);
        assertEquals(300 * S, late.wallMs, "whenever it is pressed");

        One longLate = time(fixed("s-a", 0, 900 * S), t -> BELOW, -1, 0, 1100 * S);
        assertEquals(1230 * S, longLate.wallMs, "the clock stopped at 20:00; the half-minute "
            + "the user then asked for is given");
        One longEarly = time(fixed("s-a", 0, 900 * S), t -> BELOW, -1, 0, 10 * S);
        assertEquals(1200 * S, longEarly.wallMs, "asked for before the limit: still 20:00");
    }

    /* ------------------------------------------------------------------ fixtures */

    /** What one set did. */
    private static final class One {
        long wallMs, pushedMs, cutMs, heldMs;
        boolean saidAtEnd;
    }

    /**
     * One set timed at pressure: SessionActivity#tickTupTiming every 400 ms (TupClock#tick,
     * its push limited by the set's limit, moving the deadline and the preset's duration);
     * a Hold from `holdFrom` for `holdFor` pushing the deadline by its own length and never
     * through the limit (tickHold); one +30 s at `extendAt` (extendPreset). The set ends when
     * the deadline is reached - the Advance - and `saidAtEnd` is what the Advance would say.
     */
    private static One time(Model.Preset p, LongToDoubleFunction kpaAt,
                            long holdFrom, long holdFor, long extendAt) {
        List<Model.Preset> plan = plan(p);
        TupClock.SetLimit limit = new TupClock.SetLimit();
        limit.start(plan, 0);
        One out = new One();
        long now = 0L, fireAt = p.durMs;
        boolean extended = false;
        // A set with no limit never ends when the pump stays below the line - the finding
        // itself - so the loop gives up at the two-hour stop and reports the wall time,
        // rather than hanging the build.
        while (now < fireAt && now < (long) Plan.GROSS_CAP_SEC * S) {
            long step = Math.min(TICK_MS, fireAt - now);
            now += step;
            if (!extended && extendAt >= 0 && now >= extendAt) {
                extended = true;
                fireAt += RunEdit.EXTEND_MS;
                p.durMs += RunEdit.EXTEND_MS;
                limit.extend(RunEdit.EXTEND_MS);
            }
            boolean holding = holdFrom >= 0 && now > holdFrom && now <= holdFrom + holdFor;
            if (holding) {
                fireAt += step;
                p.durMs += step;
                out.heldMs += step;
                continue;
            }
            double kpa = kpaAt.applyAsDouble(now);
            boolean reading = !Double.isNaN(kpa);
            TupClock.Step t = TupClock.tick(step, 0L, reading, reading ? kpa : 0.0, LINE, 0L);
            long push = limit.allow(plan, 0, t.pushMs);
            out.cutMs += t.pushMs - push;
            out.pushedMs += push;
            fireAt += push;
            p.durMs += push;
        }
        out.wallMs = now;
        out.saidAtEnd = limit.reachedLimitAtEnd(plan, 0);
        return out;
    }

    private static List<Model.Preset> plan(Model.Preset... ps) {
        List<Model.Preset> out = new ArrayList<Model.Preset>();
        for (Model.Preset p : ps) out.add(p);
        return out;
    }

    /** A plain hold with no drop, so every second of it is the hold half. */
    private static Model.Preset fixed(String setId, int pos, long durMs) {
        return step(setId, pos, 0, durMs);
    }

    private static Model.Preset step(String setId, int pos, int ordinal, long durMs) {
        Model.Preset p = new Model.Preset();
        p.up = 17; p.lo = 17; p.uh = 120; p.lh = 0; p.sp = 75;
        p.durMs = durMs;
        p.label = setId;
        p.stageIdx = 0;
        p.setId = setId;
        p.pos = pos;
        p.ordinal = ordinal;
        return p;
    }

    private static Model.Preset rest() {
        Model.Preset p = new Model.Preset();
        p.rest = true;
        p.durMs = 60 * S;
        p.label = "Rest";
        p.stageIdx = 0;
        p.setId = "s-rest";
        p.pos = 2;
        return p;
    }
}
