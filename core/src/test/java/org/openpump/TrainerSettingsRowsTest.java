package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 R-63 - THE TRAINER PAGE'S THREE ROWS: Long training days, Length load after month 3 and
 * When girth follows length. Each cycles through its values in the app's order and words,
 * shows only where its tracks are on, and its value survives a save; an old file gets the
 * defaults (slow, warm-up, and Long training days by the R-60 migration).
 */
class TrainerSettingsRowsTest {

    private static final String OLD_ENROLLED =
        "{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],\"trainerOn\":true,"
        + "\"trainerMonths\":7,\"trainerLengthOn\":true,"
        + "\"sched\":[{\"days\":\"0101010\",\"plan\":\"0000000\"}],"
        + "\"trainerGirth\":[{\"level\":3,\"week\":0,\"pressureKpa\":\"30.0\"}],"
        + "\"trainerLength\":[{\"level\":2,\"week\":0,\"pressureKpa\":\"34.0\"}]}";

    @Test void longTrainingDaysCyclesThroughItsFourAndSaves() {
        Model m = Model.fromJson(OLD_ENROLLED);
        assertEquals(Schedule.LONG_COMBINED, m.sched.longDays, "an upgrader keeps as now (K18)");
        assertTrue(LongDays.shown(m));
        int[] order = { Schedule.LONG_SPLIT, Schedule.LONG_ALTERNATE, Schedule.LONG_CAP90,
                        Schedule.LONG_COMBINED };
        for (int i = 0; i < order.length; i++) {
            LongDays.choose(m, LongDays.next(m.sched.longDays));
            assertEquals(order[i], m.sched.longDays);
            Model back = Model.fromJson(m.toJson());
            assertEquals(order[i], back.sched.longDays, "saved: " + LongDays.label(order[i]));
            assertEquals(order[i], back.sched.longDaysInForce(), "and in force after a load");
        }
    }

    @Test void lengthLoadCyclesAndShowsOnlyWithALengthCylinder() {
        Model m = Model.fromJson(OLD_ENROLLED);
        assertEquals(Model.LENGTH_LOAD_SLOW, m.lengthLoadMode, "the owner's default");
        assertEquals("Length load after month 3", PlanCards.LEN_LOAD_ROW);
        assertEquals("Small steps over time", PlanCards.lenLoadLabel(m.lengthLoadMode));
        assertEquals("+0.5 lb every 2 training weeks toward 5 lb, then 12 lb, while strain "
            + "sets are under 12.", PlanCards.lenLoadEffect(Model.LENGTH_LOAD_SLOW));
        assertEquals(PlanCards.LEN_LOAD_EFFECTS[0], PlanCards.lenLoadEffect(Model.LENGTH_LOAD_SLOW),
            "the same words as the table, in pounds");
        // In kilos when the load unit is kilos, as every other load figure is (device walk).
        String was = Model.Fmt.loadUnit;
        try {
            Model.Fmt.loadUnit = Model.Fmt.L_KG;
            assertEquals("+0.2 kg every 2 training weeks toward 2.3 kg, then 5.4 kg, while "
                + "strain sets are under 12.", PlanCards.lenLoadEffect(Model.LENGTH_LOAD_SLOW));
        } finally {
            Model.Fmt.loadUnit = was;
        }
        m.lengthLoadMode = PlanCards.nextLenLoad(m.lengthLoadMode);
        assertEquals(Model.LENGTH_LOAD_AFTER12, m.lengthLoadMode);
        assertEquals("Only after 12 strain sets", PlanCards.lenLoadLabel(m.lengthLoadMode));
        assertEquals("The load moves only once you are at 12 strain sets.",
            PlanCards.lenLoadEffect(m.lengthLoadMode));
        assertEquals(Model.LENGTH_LOAD_AFTER12, Model.fromJson(m.toJson()).lengthLoadMode);
        assertEquals(Model.LENGTH_LOAD_SLOW, PlanCards.nextLenLoad(m.lengthLoadMode));
        // Shown exactly where there is a length cylinder to pull with.
        assertEquals(m.lengthPulls(), PlanCards.lenLoadShown(m));
        m.trainerLengthOn = false;
        assertFalse(PlanCards.lenLoadShown(m), "length off");
    }

    @Test void whenGirthFollowsLengthCyclesItsThreeAndSaves() {
        Model m = Model.fromJson(OLD_ENROLLED);
        assertEquals(Model.R4_WARM, m.girthAfterLength, "the default");
        assertEquals("When girth follows length", PlanCards.R4_ROW);
        String[] labels = { "2-minute climbing warm-up", "First 3 holds climb", "Nothing" };
        int[] order = { Model.R4_WARM, Model.R4_SETS, Model.R4_NONE };
        for (int i = 0; i < order.length; i++) {
            assertEquals(order[i], m.girthAfterLength);
            assertEquals(labels[i], PlanCards.r4Label(m.girthAfterLength));
            assertEquals(order[i], Model.fromJson(m.toJson()).girthAfterLength);
            m.girthAfterLength = PlanCards.nextR4(m.girthAfterLength);
        }
        assertEquals(Model.R4_WARM, m.girthAfterLength, "round again");
        assertEquals("Your first 3 holds climb to your pressure and count.",
            PlanCards.r4Effect(Model.R4_SETS));
        assertEquals(Plan.R4_RAMP_SETS, 3);
        assertTrue(PlanCards.r4Shown(m));
        m.trainerLengthOn = false;
        assertFalse(PlanCards.r4Shown(m), "shown only with both tracks on");
    }

    @Test void oneTrackHidesTheTwoBothTrackRows() {
        Model m = Model.fromJson(OLD_ENROLLED);
        m.trainerGirthOn = false;
        assertFalse(LongDays.shown(m));
        assertFalse(PlanCards.r4Shown(m));
    }

    /** Polish SYS-3: the rows open lists, so a value and its place in the list must agree both
     *  ways, and every option carries its own effect line. */
    @Test void theListsPlacesAndValuesAgree() {
        for (int v = Schedule.LONG_SPLIT; v <= Schedule.LONG_COMBINED; v++)
            assertEquals(LongDays.label(v), LongDays.LABELS[LongDays.index(v)]);
        assertEquals(LongDays.LABELS.length, LongDays.effects(new Schedule()).length);
        for (int i = 0; i < PlanCards.LEN_LOAD_LABELS.length; i++)
            assertEquals(i, PlanCards.lenLoadIndex(PlanCards.lenLoadValue(i)));
        assertEquals(PlanCards.LEN_LOAD_LABELS.length, PlanCards.lenLoadEffects().length);
        for (int v = Model.R4_WARM; v <= Model.R4_NONE; v++)
            assertEquals(PlanCards.r4Label(v), PlanCards.R4_LABELS[PlanCards.r4Index(v)]);
        assertEquals("Small steps over time", PlanCards.LEN_LOAD_LABELS[0]);
        assertEquals("Same days, no limit", LongDays.LABELS[Schedule.LONG_COMBINED]);
    }
}
