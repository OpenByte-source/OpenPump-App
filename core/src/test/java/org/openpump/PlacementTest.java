package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * R11-3 - PLACED BY HOW LONG YOUR CURRENT SESSION IS (the owner's pick, option A). One question
 * per track, in minutes; the track starts at the level and week whose session, as the plan
 * writes it, is the closest to the answer without going above it. 0 or new to pumping: Level 1,
 * week 1. Past the top: the highest level the months allow. Unanswered: the months, as before.
 */
class PlacementTest {

    private static final double HG = Plan.HG;

    /** The owner at setup: 7 months, not new, interval girth at 9 inHg. */
    private static Model scratch() {
        Model m = owner();
        m.rxNewToPumping = false;
        return m;
    }

    @Test
    void somebodyNewStartsAtLevelOneWeekOne() {
        Placement.Spot s = Placement.girth(scratch(), Plan.TRACK_GIRTH_INTERVAL, 7, true,
            9 * HG, 0, 45);
        assertEquals(Plan.L1, s.level);
        assertEquals(1, s.week);
        Placement.Spot z = Placement.girth(scratch(), Plan.TRACK_GIRTH_INTERVAL, 7, false,
            9 * HG, 0, 0);
        assertEquals(Plan.L1, z.level, "0 - I don't do this yet");
        assertEquals(1, z.week);
        assertNull(Placement.girth(scratch(), Plan.TRACK_GIRTH_INTERVAL, 7, false, 9 * HG, 0,
            Placement.NOT_ANSWERED), "unanswered: the months place it, as before");
    }

    @Test
    void twentyMinutesOfGirthIsTheClosestSessionNotAboveIt() {
        List<Placement.Spot> c = Placement.girthCandidates(scratch(), Plan.TRACK_GIRTH_INTERVAL,
            7, false, 9 * HG, 0);
        Placement.Spot s = Placement.girth(scratch(), Plan.TRACK_GIRTH_INTERVAL, 7, false,
            9 * HG, 0, 20);
        assertTrue(s.minutes <= 20.0, "never above the answer: " + s.minutes);
        for (int i = 0; i < c.size(); i++)
            assertTrue(c.get(i).minutes <= s.minutes + 1e-9 || c.get(i).minutes > 20.0,
                "nothing closer under 20 min: L" + c.get(i).level + " w" + c.get(i).week
                + " " + c.get(i).minutes);
        assertEquals(Plan.L1, s.level, "a 20-minute session is a Level 1 week");
        // The owner's own answers: Level 1's week 6, 7 holds - 19.6 minutes as the plan writes
        // it (week 7 is the same session; the earlier week is taken).
        assertEquals(6, s.week);
        assertEquals(19.58, Math.round(s.minutes * 100.0) / 100.0, 1e-9);
        assertEquals(s.level, Placement.girth(scratch(), Plan.TRACK_GIRTH_INTERVAL, 7, false,
            9 * HG, 0, 20).level, "the same answer, the same place");
    }

    @Test
    void theCandidatesAreThePlansOwnWeeksInOrder() {
        List<Placement.Spot> c = Placement.girthCandidates(scratch(), Plan.TRACK_GIRTH_INTERVAL,
            7, false, 9 * HG, 0);
        // Level 1's 13 training weeks, Level 2's 12, then Level 3 (month 7 allows it, not 4).
        assertEquals(13 + 12 + 1, c.size());
        assertEquals(Plan.L1, c.get(0).level);
        assertEquals(1, c.get(0).week);
        assertEquals(Plan.L3, c.get(c.size() - 1).level);
        assertTrue(c.get(0).minutes < c.get(12).minutes, "Level 1's sessions grow");
    }

    @Test
    void pastTheTopIsTheHighestLevelTheMonthsAllow() {
        assertEquals(Plan.L3, Placement.girth(scratch(), Plan.TRACK_GIRTH_INTERVAL, 7, false,
            9 * HG, 0, 600).level, "month 7: Level 3");
        assertEquals(Plan.L4, Placement.girth(scratch(), Plan.TRACK_GIRTH_INTERVAL, 13, false,
            9 * HG, 0, 600).level, "month 13: Level 4");
        Placement.Spot two = Placement.girth(scratch(), Plan.TRACK_GIRTH_INTERVAL, 3, false,
            9 * HG, 0, 600);
        assertEquals(Plan.L2, two.level, "month 3: Level 2, its last week");
        assertEquals(Plan.GIRTH_INTERVAL_L2.length, two.week);
        assertEquals(Plan.L1, Placement.girth(scratch(), Plan.TRACK_GIRTH_INTERVAL, 13, false,
            7 * HG, 0, 600).level, "under 8 inHg: Level 1, as the months placement");
    }

    @Test
    void anAnswerUnderEverySessionIsTheFirst() {
        Placement.Spot s = Placement.girth(scratch(), Plan.TRACK_GIRTH_INTERVAL, 7, false,
            9 * HG, 0, 1);
        assertEquals(Plan.L1, s.level);
        assertEquals(1, s.week);
    }

    @Test
    void traditionalIsPlacedByItsWeeksAtLevelOne() {
        List<Placement.Spot> c = Placement.girthCandidates(scratch(),
            Plan.TRACK_GIRTH_TRADITIONAL, 7, false, 9 * HG, 0);
        assertEquals(12 + 2, c.size(), "Level 1 weeks 1-12, then Levels 2 and 3");
        Placement.Spot s = Placement.girth(scratch(), Plan.TRACK_GIRTH_TRADITIONAL, 7, false,
            9 * HG, 0, 40);
        assertTrue(s.minutes <= 40.0);
    }

    @Test
    void seventyMinutesOfLengthIsItsStrainSets() {
        Model m = scratch();
        rack(m);
        m.trainerLength.level = Plan.L3;
        m.trainerLength.setWorkingPressure(10 * HG, 1L);
        m.trainerLength.loadLb = 8.0;
        int n = Placement.lengthStrainSets(m, false, 7, 70);
        assertTrue(n >= Plan.LENGTH_STRAIN_SETS_START && n <= Plan.LENGTH_STRAIN_SETS_MAX, "" + n);
        double at = minutesAt(m, n), next = n < Plan.LENGTH_STRAIN_SETS_MAX
            ? minutesAt(m, n + 1) : Double.MAX_VALUE;
        assertTrue(at <= 70.0, "never above the answer: " + at);
        assertTrue(next > 70.0, "the next strain set would be: " + next);
        assertEquals(6, n, "the owner's length cylinder at 8 lb: 6 strain sets");
        assertEquals(Plan.LENGTH_STRAIN_SETS_START, Placement.lengthStrainSets(m, true, 7, 70),
            "new: the calendar's first");
        assertEquals(-1, Placement.lengthStrainSets(m, false, 7, Placement.NOT_ANSWERED));
        Model noCyl = scratch();
        noCyl.trainerLengthOn = true;
        assertEquals(-1, Placement.lengthStrainSets(noCyl, false, 7, 70),
            "no cylinder to pull with: strain sets do not shape the session");
    }

    private static double minutesAt(Model m, int n) {
        Model.TrainerTrackState st = m.trainerLength;
        st.setStrainSets(n, 1L);
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_LENGTH, st.level, st.weekIndex, st.pressureKpa,
            Plan.monthIndex(7 * 4), m.ceilKpa, 0, 0, null, Scale.limitsOf(m, Plan.TRACK_LENGTH));
        return m.routineSec(m.routine(RxBuild.routineFromRx(m, rx))) / 60.0;
    }

    @Test
    void theLengthCalendarGoesOnFromTheCountPlaced() {
        assertEquals(9, Placement.strainCalendarWeeks(5));
        assertEquals(0, Placement.strainCalendarWeeks(2));
        assertEquals(5, Plan.calendarStrainSets(0 + Placement.strainCalendarWeeks(5)));
        assertEquals(6, Plan.calendarStrainSets(3 + Placement.strainCalendarWeeks(5)),
            "three training weeks on: one more");
    }

    @Test
    void theAnswersAreKeptAndAnOldFileHasNone() throws Exception {
        Model m = new Model();
        assertEquals(Placement.NOT_ANSWERED, m.trainerGirthSessMin);
        assertEquals(Placement.NOT_ANSWERED, m.trainerLengthSessMin);
        assertEquals(0, m.trainerLength.strainCalWeeks);
        m.trainerGirthSessMin = 35;
        m.trainerLengthSessMin = 0;
        m.trainerLength.strainCalWeeks = 9;
        Model back = Model.fromJson(m.toJson());
        assertEquals(35, back.trainerGirthSessMin);
        assertEquals(0, back.trainerLengthSessMin);
        assertEquals(9, back.trainerLength.strainCalWeeks);
        org.json.JSONObject o = new org.json.JSONObject(new Model().toJson());
        o.remove("gSessMin");
        o.remove("lSessMin");
        Model old = Model.fromJson(o.toString());
        assertEquals(Placement.NOT_ANSWERED, old.trainerGirthSessMin);
        assertEquals(Placement.NOT_ANSWERED, old.trainerLengthSessMin);
    }

    @Test
    void theQuestionsWords() {
        assertEquals("Skip", TrainerOnboard.sessionValue(Placement.NOT_ANSWERED));
        assertEquals("I don't do this yet", TrainerOnboard.sessionValue(0));
        assertEquals("35 min", TrainerOnboard.sessionValue(35));
        assertEquals(0, TrainerOnboard.stepSessionMin(Placement.NOT_ANSWERED, +1));
        assertEquals(5, TrainerOnboard.stepSessionMin(0, +1));
        assertEquals(Placement.NOT_ANSWERED, TrainerOnboard.stepSessionMin(0, -1));
        assertEquals(Placement.NOT_ANSWERED, TrainerOnboard.stepSessionMin(-1, -1));
        assertEquals(180, TrainerOnboard.stepSessionMin(180, +1));
        assertEquals("Placed by your 20-min session: the plan's closest is 19.6 min.",
            TrainerOnboard.placedLine(20, 19.583));
        assertEquals("", TrainerOnboard.placedLine(Placement.NOT_ANSWERED, 0));
    }

    @Test
    void anUpgradersPositionIsKeptUnlessTheyAnswerTheMinutes() {
        TrainerOnboard.Position seeded = new TrainerOnboard.Position();
        seeded.months = 7; seeded.girthKpa = 9 * HG; seeded.girthOn = true;
        seeded.girthMin = Placement.NOT_ANSWERED; seeded.lengthMin = Placement.NOT_ANSWERED;
        TrainerOnboard.Position now = seeded.copy();
        assertTrue(TrainerOnboard.positionKept(seeded, now));
        now.girthMin = 30;
        assertFalse(TrainerOnboard.positionKept(seeded, now), "answered: placed from it");
    }
}
