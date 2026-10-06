package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;

import org.junit.jupiter.api.Test;

/**
 * 0.10 - THE SAME DAY, READ FROM WHAT TODAY FILED (TrainerTab's side of {@link SameDay}):
 * the other track's last end for the warm-up ask (S04), which rules shape a run (S05, S06),
 * the pull that starts the soft gap (S09), the feeder's gap from the main session (S10), the
 * day's minutes (S07), the girth-focus pause (S14) and the coda's sets counted toward the
 * girth work (S06). Built on the app's own "what ran today" - the session log - and nothing
 * persisted beside it.
 */
class SameDayLogTest {

    private static final long MIN = 60_000L;
    /** Local noon on a fixed day, so no assertion straddles a midnight. */
    private static final long NOW;
    static {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.SEPTEMBER, 15, 12, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        NOW = c.getTimeInMillis();
    }

    private static Model model() {
        Model m = new Model();
        m.ceilKpa = 40;
        m.trainerEnrolled = true;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerGirth.level = Plan.L2;
        return m;
    }

    private static Model.Routine girthMint(Model m, int sets) {
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, Plan.L2, sets, 120, 180, 27, false,
                                 sets * 2.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        r.assess.on = true;
        m.trainerGirth.lastMintId = r.id;
        return r;
    }

    private static Model.Routine lengthMint(Model m) {
        m.trainerLengthOn = true;
        Model.Reading g = new Model.Reading();
        g.ts = NOW - 30L * 86_400_000L;
        g.method = Model.Reading.METHOD_MSEG;
        g.gir = 12.7;
        m.measLog.all.add(g);
        Model.Cylinder lt = new Model.Cylinder();
        lt.id = "L"; lt.label = "Length tube"; lt.role = Model.Cylinder.ROLE_LENGTH; lt.boreCm = 4.0; lt.lengthCm = 23.0;
        Model.Cylinder gt = new Model.Cylinder();
        gt.id = "G"; gt.label = "Girth tube"; gt.boreCm = 4.5; gt.lengthCm = 23.0;
        m.cylinders.add(lt);
        m.cylinders.add(gt);
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_LENGTH, Plan.L1, 1, 6.0 * Plan.HG, 2,
                                    m.ceilKpa, 0, null);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        r.assess.on = true;
        m.trainerLength.lastMintId = r.id;
        return r;
    }

    /** A session of `r` that ENDED at `endMs`, `durMin` long, filed newest-first. */
    private static Model.Sess filed(Model m, Model.Routine r, long endMs, int durMin) {
        Model.Sess s = new Model.Sess();
        s.durSec = durMin * 60;
        s.ts = endMs - s.durSec * 1000L;
        s.id = "sess" + s.ts;
        s.routineId = r.id;
        s.routineName = r.name;
        s.completed = true;
        s.dayKey = PhotoCalendar.dayKey(s.ts);
        m.sessLog.file(s);
        return s;
    }

    @Test void theWarmUpIsAskedAboutAfterTheOtherTrackOnly() {
        Model m = model();
        Model.Routine g = girthMint(m, 12);
        Model.Routine l = lengthMint(m);
        filed(m, l, NOW - 12 * MIN, 40);
        assertEquals(12, TrainerTab.warmSkipAskMinutes(m, g, NOW), "length ran 12 minutes ago");
        assertEquals(-1, TrainerTab.warmSkipAskMinutes(m, l, NOW),
            "a second length session is not a both-tracks question");
        assertEquals(-1, TrainerTab.warmSkipAskMinutes(m, g, NOW + 49 * MIN),
            "past the owner's hour it is not asked");
    }

    @Test void theDaysOwnRulesShapeTheSecondSession() {
        Model m = model();
        Model.Routine g = girthMint(m, 12);
        Model.Routine l = lengthMint(m);
        RunShape.Choice first = TrainerTab.dayChoice(m, g, NOW, false, false);
        assertFalse(first.noTissueTest, "the day's first session keeps its test");
        assertEquals(0, first.girthSetsOff,
            "\"whatever you are enrolled in\" does not promise a length session: no sets off yet");
        assertFalse(first.skipWarm, "nothing ran today: the warm-up stays");
        for (int i = 0; i < m.sched.plan.length; i++) m.sched.plan[i] = Schedule.PLAN_BOTH;
        // t10 R-06 (R1): with a length cylinder the length session drops its expansion on a
        // day of both tracks, so girth gives up nothing for it.
        assertEquals(0, TrainerTab.dayChoice(m, g, NOW, false, false).girthSetsOff,
            "a day planned as both tracks, length pulls: girth keeps every set");
        for (int i = 0; i < m.sched.plan.length; i++) m.sched.plan[i] = Schedule.PLAN_ANY;

        filed(m, l, NOW - 12 * MIN, 40);
        RunShape.Choice second = TrainerTab.dayChoice(m, g, NOW, false, false);
        assertTrue(second.noTissueTest, "the day's second session skips the test");
        assertTrue(second.skipWarm, "t10 P4: length ended 12 minutes ago - the warm-up goes");
        assertTrue(second.girthAfterLength, "t10 R4: girth follows length");
        assertEquals(0, second.girthSetsOff, "R1: nothing given up with a length cylinder");

        // A girth routine somebody built themselves keeps its sets: they are theirs.
        Model.Routine own = g.copy("r-own");
        own.trainerTrack = Plan.TRACK_GIRTH_INTERVAL;
        m.routines.add(own);
        assertEquals(0, TrainerTab.dayChoice(m, own, NOW, false, false).girthSetsOff);
    }

    @Test void lengthAfterGirthIsWarnedAndMayRunExpansionOnly() {
        Model m = model();
        Model.Routine g = girthMint(m, 12);
        Model.Routine l = lengthMint(m);
        assertFalse(TrainerTab.girthFirstWarning(m, l, NOW), "nothing ran yet");
        filed(m, g, NOW - 5 * MIN, 30);
        assertTrue(TrainerTab.girthFirstWarning(m, l, NOW));
        RunShape.Choice c = TrainerTab.dayChoice(m, l, NOW, false, true);
        assertTrue(c.expansionOnly, "the person's choice");
        assertFalse(c.skipRelease, "the hand release is kept unless the person says otherwise");
        assertTrue(TrainerTab.dayChoice(m, l, NOW, false, true, true).skipRelease,
            "S08 follow-up: skipped on the answer, for a run that is expansion only");
        assertFalse(TrainerTab.dayChoice(m, l, NOW, false, false, true).skipRelease,
            "never for a run that still pulls, whatever was answered");
        assertTrue(c.noTissueTest, "and it is the day's second session");
        assertEquals(0, c.girthSetsOff, "a length session gives up no girth sets");
    }

    @Test void theGapFollowsAPullAndEndsWhenNumbnessCleared() {
        Model m = model();
        girthMint(m, 12);
        Model.Routine l = lengthMint(m);
        Model.Sess pull = filed(m, l, NOW - 10 * MIN, 40);
        assertEquals(pull, TrainerTab.lastPullToday(m, NOW));
        assertEquals(TrainerTab.endMs(pull) + 40 * MIN, TrainerTab.pullGapUntil(m, NOW));
        UpNext up = TrainerTab.upNextNow(m, NOW, false);
        assertEquals(UpNext.GIRTH, up.what, "girth is still NAMED - the gap is soft");
        assertEquals(TrainerTab.endMs(pull) + 40 * MIN, up.fromMs, "...from HH:MM");
        m.pullClearedSessId = pull.id;
        assertEquals(0L, TrainerTab.pullGapUntil(m, NOW), "\"It cleared\" ends the wait");
        assertEquals(0L, TrainerTab.upNextNow(m, NOW, false).fromMs);

        Model m2 = model();
        girthMint(m2, 12);
        Model.Routine l2 = lengthMint(m2);
        filed(m2, l2, NOW - 10 * MIN, 20).shape = "x";
        assertNull(TrainerTab.lastPullToday(m2, NOW), "an expansion-only run pulled nothing");
        assertEquals(0L, TrainerTab.pullGapUntil(m2, NOW));
    }

    @Test void theFeederWaitsFourHoursFromTheMainSession() {
        Model m = model();
        m.trainerGirth.level = Plan.L3;                     // feeders are L3+
        Model.Routine g = girthMint(m, 12);
        Model.Set fs = Model.Set.fixed("s-feed", "Feeder", 20, 10, 120, 10, 60, 650);
        m.sets.add(fs);
        Model.Routine feeder = m.newRoutine("r-feed", "Feeder");
        feeder.trainerTrack = Plan.TRACK_FEEDER;
        feeder.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "s-feed" }));
        m.routines.add(feeder);
        m.trainerFeederMintId = feeder.id;
        for (int i = 0; i < m.sched.plan.length; i++) m.sched.plan[i] = Schedule.PLAN_GIRTH;

        Model.Sess main = filed(m, g, NOW - 60 * MIN, 30);
        long due = TrainerTab.endMs(main) + 4 * 60 * MIN;
        assertEquals(due, TrainerTab.feederFromMs(m, NOW),
            "four hours from the END of the main session, not \"due now\"");
        assertFalse(TrainerTab.feederReady(m, NOW));
        UpNext up = TrainerTab.upNextNow(m, NOW, false);
        assertEquals(UpNext.FEEDER, up.what, "named, with its time - a tap overrides the wait");
        assertEquals(due, up.fromMs);
        assertTrue(TrainerTab.feederReady(m, due));
        assertEquals(0L, TrainerTab.upNextNow(m, due, false).fromMs, "due now");
    }

    @Test void theDaysMinutesAreGirthAndLengthOnly() {
        Model m = model();
        Model.Routine g = girthMint(m, 12);
        Model.Routine l = lengthMint(m);
        Model.Routine feeder = m.newRoutine("r-feed", "Feeder");
        feeder.trainerTrack = Plan.TRACK_FEEDER;
        m.routines.add(feeder);
        filed(m, l, NOW - 120 * MIN, 40);
        filed(m, g, NOW - 60 * MIN, 30);
        filed(m, feeder, NOW - 5 * MIN, 10);
        assertEquals(70 * 60L, TrainerTab.daySessionSec(m, NOW), "the feeder is not counted");
    }

    @Test void aGirthFocusBlockPausesGirthOnToday() {
        Model m = model();
        girthMint(m, 12);
        Model.Routine l = lengthMint(m);
        m.trainerLength.focusBlockUntilMs = NOW + 20L * 86_400_000L;
        assertTrue(TrainerTab.girthPausedNow(m, NOW));
        UpNext up = TrainerTab.upNextNow(m, NOW, false);
        assertEquals(UpNext.LENGTH, up.what);
        assertTrue(up.why.contains("paused"), up.why);
        filed(m, l, NOW - 10 * MIN, 30);
        UpNext after = TrainerTab.upNextNow(m, NOW, false);
        assertEquals(UpNext.NOTHING, after.what, "girth is not offered during the block");
        m.trainerLengthOn = false;
        assertFalse(TrainerTab.girthPausedNow(m, NOW), "no length track, no pause");
    }

    @Test void theCodasSetsCountTowardTheGirthWorkWhenLengthRan() {
        Model m = model();
        Model.Routine g = girthMint(m, 12);
        Model.Routine l = lengthMint(m);
        Model.Sess s = filed(m, g, NOW - 60 * MIN, 35);
        s.shape = "g5:10.0";
        s.netTargetMin = Double.valueOf(14.0);
        s.netTupSec = Double.valueOf(13.0 * 60.0);
        TrainerTab.NetPairs alone = TrainerTab.recentTrackedNets(m, Plan.TRACK_GIRTH_INTERVAL, 3);
        assertEquals(13.0, alone.nets[0], 1e-9,
            "no length session that day: nothing to credit, judged as run");
        assertEquals(14.0, alone.targets[0], 1e-9);

        /* THE LENGTH SESSION MUST HAVE DONE THE WORK (the 0.10 safety review): the gate reads
         * the lowest recent net, so a credit for a coda that never ran would move somebody up
         * on work they did not do. Aborted, simulated, or with its expansion not delivered:
         * no credit, each on its own. */
        Model.Sess aborted = filed(m, l, NOW - 150 * MIN, 5);
        aborted.completed = false;
        aborted.expansionDone = false;          // stopped in the tunica release
        assertEquals(14.0, TrainerTab.recentTrackedNets(m, Plan.TRACK_GIRTH_INTERVAL, 3)
            .targets[0], 1e-9, "a length session stopped in its release earns no credit");
        aborted.expansionDone = true;
        assertEquals(14.0, TrainerTab.recentTrackedNets(m, Plan.TRACK_GIRTH_INTERVAL, 3)
            .targets[0], 1e-9, "...nor does one that did not complete, whatever it recorded");
        m.sessLog.all.remove(aborted);

        Model.Sess sim = filed(m, l, NOW - 150 * MIN, 40);
        sim.expansionDone = true;
        sim.sim = true;
        assertEquals(14.0, TrainerTab.recentTrackedNets(m, Plan.TRACK_GIRTH_INTERVAL, 3)
            .targets[0], 1e-9, "a session on the simulated pump earns no credit");
        sim.sim = false;
        sim.expansionDone = false;
        assertEquals(14.0, TrainerTab.recentTrackedNets(m, Plan.TRACK_GIRTH_INTERVAL, 3)
            .targets[0], 1e-9, "a completed run whose expansion was not delivered earns none");
        sim.expansionDone = true;               // completed, real, expansion delivered
        TrainerTab.NetPairs both = TrainerTab.recentTrackedNets(m, Plan.TRACK_GIRTH_INTERVAL, 3);
        assertEquals(24.0, both.targets[0], 1e-9, "the five sets count toward the girth work");
        assertEquals(13.0 * 24.0 / 14.0, both.nets[0], 1e-9,
            "credited at the rate the girth session delivered its own");
        assertEquals(alone.nets[0] / alone.targets[0], both.nets[0] / both.targets[0], 1e-9,
            "so under-delivery reads exactly the same");
    }
}
