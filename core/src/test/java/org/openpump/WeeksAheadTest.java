package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * "THE NEXT WEEKS" SHOWS WHAT THE ROUTINE RUNS (TrainerTab#weeksAhead) - the owner's standing
 * decision. Reported from a device: an interval girth track at Level 3, working at −8.9 inHg,
 * was shown Level 1's table ("5 sets · 10.0 min · −5.0 inHg", "the month-1 pressure ceiling")
 * under "L3 · week 1", and its lane said "6 sets at wk 3" over a routine of 10×2min.
 */
class WeeksAheadTest {

    static final long NOW = 1_780_000_000_000L;

    @AfterEach void units() { Model.Fmt.unit = Model.Fmt.U_INHG; }

    /** The setup's girth placement, as CommitOnboardTap writes it: the level and week the
     *  answers derive, the plan's figure for the level and the answer kept as the offset. */
    static Model enrolled(boolean newToPumping, int months, int girthKpa, double maxKpa) {
        Model.Fmt.unit = Model.Fmt.U_INHG;
        Model m = new Model();
        m.ceilKpa = 43;                       // 12.7 inHg, the owner's ceiling
        m.rxNewToPumping = newToPumping;
        m.trainerMonthsPumping = months;
        m.trainerMonthsAt = NOW;
        m.trainerEnrolledAt = NOW;
        m.rxWorkMaxKpa = maxKpa;
        m.clampAll();
        TrainerTab.Derived g = TrainerTab.deriveGirth(Plan.TRACK_GIRTH_INTERVAL, months, girthKpa,
            newToPumping, m.ceilKpa, m.rxWorkMaxKpa);
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerGirthOn = true;
        Model.TrainerTrackState gs = m.trainerGirth;
        gs.level = g.level;
        gs.weekIndex = g.weekIndex;
        double plan = Scale.setupPlanKpa(Plan.TRACK_GIRTH_INTERVAL, g.level, g.monthIndex,
                                         g.pressureKpa);
        gs.setWorkingPressure(plan, NOW);
        gs.offsetKpa = Scale.offsetFromAnswer(girthKpa, plan);
        gs.weekBaseIndex = g.weekIndex;
        gs.weekBaseMs = NOW;
        m.trainerEnrolled = true;
        return m;
    }

    /** The owner's profile: 7 months, not new, girth interval at −9 inHg (30 kPa on the
     *  wire, −8.9 shown), own maximum −11 inHg (37 kPa). */
    static Model owner() { return enrolled(false, 7, 30, 37); }

    /** What the offer card prints for the track today (SessionActivity#rxLine): the
     *  prescription at its position, as the builder commands it. */
    static int cardKpa(Model m) {
        Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth,
            TrainerTab.monthIndexNow(m, NOW), null);
        return RxBuild.commanded(m, rx, RxBuild.Day.at(m, NOW)).pressureKpa;
    }

    @Test void levelThreeShowsItsOwnPrescriptionNeverLevelOnesRows() {
        Model m = owner();
        assertEquals(Plan.L3, m.trainerGirth.level, "7 months at −8.9 is Level 3");
        List<TrainerTab.AheadRow> rows =
            TrainerTab.weeksAhead(m, Plan.TRACK_GIRTH_INTERVAL, NOW, 6);
        assertEquals(1, rows.size(), "no table, so one row: this week's real prescription");
        TrainerTab.AheadRow r = rows.get(0);
        assertEquals(0, r.from, "not a week of any table");
        assertEquals("now", r.label());
        assertEquals(10, r.holds, "Level 3's 20 minutes in 2-minute holds");
        assertEquals(120, r.holdSec);
        assertEquals(30, r.kpa, "the person's own pressure, −8.9 inHg");
        assertTrue(r.fatigue, "Level 3 leads with the fatigue block");
        assertEquals(cardKpa(m), r.kpa, "the same figure the offer card and the routine carry");
        assertEquals("10×2min  ·  20.0 min  ·  " + Model.Fmt.p(30) + "  ·  + fatigue block",
            r.value());
        assertFalse(r.value().contains("5×2min"), "never Level 1's first row");
        String next = TrainerTab.noTableNextWords(m, NOW);
        assertTrue(next.contains("No week table at Level 3"), next);
        assertTrue(next.contains("month 12"), "the Level 3 gate, as Plan keeps it");
        // G2 (the owner's decision, 2026-10-01): the owner's most, −10.9 inHg, is above the
        // level's 10 inHg top, so the step climbs to it and the card says so.
        assertTrue(next.contains("up to " + Model.Fmt.p(37) + "."),
            "up to the owner's own maximum: " + next);
        Model usual = owner();
        usual.rxWorkMaxKpa = 0;
        String held = TrainerTab.noTableNextWords(usual, NOW);
        assertTrue(held.contains("up to " + Model.Fmt.p(34) + "."),
            "with no maximum set, the level's top, 10 inHg: " + held);
    }

    @Test void theLaneCaptionSaysWhatLevelThreeRuns() {
        assertEquals("10×2min now", TrainerTab.nextChangeShort(owner(), NOW),
            "it said Level 1's \"6 sets at wk 3\"");
    }

    @Test void theOffsetAndTheProgramBiasAreInTheFigure() {
        Model m = enrolled(false, 7, 30, 37);
        m.trainerGirth.offsetKpa = 2.0;              // plan +2 kPa
        m.programGirth.pressure = Model.Program.PRESS_FIRM;
        TrainerTab.AheadRow r = TrainerTab.weeksAhead(m, Plan.TRACK_GIRTH_INTERVAL, NOW, 6).get(0);
        assertEquals(cardKpa(m), r.kpa, "the scaled, biased, limited figure the card states");
        assertTrue(r.kpa > 30, "above the plan's own figure");
        assertTrue(r.kpa <= 37, "never past the person's own maximum");
    }

    @Test void levelOneAtMonthZeroIsTheTableAsTheRoutineRunsIt() {
        Model m = enrolled(true, 0, 17, 0);         // new, −5.0 inHg
        assertEquals(Plan.L1, m.trainerGirth.level);
        List<TrainerTab.AheadRow> rows =
            TrainerTab.weeksAhead(m, Plan.TRACK_GIRTH_INTERVAL, NOW, 6);
        assertEquals(6, rows.size());
        TrainerTab.AheadRow a = rows.get(0), b = rows.get(1), c = rows.get(2), dl = rows.get(3),
            e = rows.get(4), f = rows.get(5);
        assertEquals("wk 1–2  ·  now", a.label());
        assertEquals("5×2min  ·  10.0 min  ·  " + Model.Fmt.p(17), a.value());
        assertEquals(cardKpa(m), a.kpa, "this week is the routine as it stands");
        assertEquals(3, b.from);
        assertEquals(6, b.holds);
        assertEquals(17, b.kpa, "the table's 5 inHg is already reached");
        assertEquals(4, c.from);
        assertEquals(20, c.kpa, "the table's 6 inHg, held to a new person's first-month "
            + "limit in whole kPa (Plan#followL1TablePressure, Scale#hardKpa)");
        assertTrue(dl.deload);
        assertEquals(5, dl.from);
        assertEquals("deload — instead of 6×2min", dl.value());
        assertEquals("wk 6–7", e.label(), "the same work collapses into one row");
        assertEquals(7, e.holds);
        assertEquals(20, e.kpa, "still inside the first month: the 6 inHg limit holds");
        assertEquals(8, f.from);
        assertEquals(8, f.holds);
        assertEquals("6×2min at wk 3", TrainerTab.nextChangeShort(m, NOW));
        assertTrue(TrainerTab.weeksAheadNote(m).contains("if each week's sessions hold"));
    }

    @Test void levelTwoReadsItsOwnRowsOnTheTablesScale() {
        Model m = enrolled(false, 5, 28, 0);        // 20 weeks: Level 2, row 3 (week 20)
        assertEquals(Plan.L2, m.trainerGirth.level);
        List<TrainerTab.AheadRow> rows =
            TrainerTab.weeksAhead(m, Plan.TRACK_GIRTH_INTERVAL, NOW, 6);
        assertFalse(rows.isEmpty());
        assertEquals(TrainerTab.tableWeekNum(Plan.L2, m.trainerGirth.weekIndex), rows.get(0).from);
        for (int i = 0; i < rows.size(); i++)
            assertTrue(rows.get(i).from >= 18, "never a Level 1 week at Level 2");
        assertEquals(cardKpa(m), rows.get(0).kpa);
    }

    @Test void theProjectionIsTheTracksOwnPrescriptionAtItsOwnPosition() {
        Model m = enrolled(true, 0, 17, 0);
        m.trainerGirth.yieldSets = 1;
        int month = TrainerTab.monthIndexNow(m, NOW);
        Mint.Rx own = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth, month, null);
        Mint.Rx at = TrainerTab.trackRxAt(m, Plan.TRACK_GIRTH_INTERVAL, m.trainerGirth,
            m.trainerGirth.weekIndex, m.trainerGirth.pressureKpa, month, null);
        assertEquals(Mint.signature(own, ""), Mint.signature(at, ""));
        assertEquals(6, own.sets, "the kept yield set rides along on both");
    }

    @Test void otherTracksHaveNoRows() {
        Model m = owner();
        assertTrue(TrainerTab.weeksAhead(m, Plan.TRACK_LENGTH, NOW, 6).isEmpty());
        assertTrue(TrainerTab.weeksAhead(m, Plan.TRACK_GIRTH_TRADITIONAL, NOW, 6).isEmpty());
    }
}
