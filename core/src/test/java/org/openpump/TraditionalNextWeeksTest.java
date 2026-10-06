package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * t10 device walk M4 - "THE NEXT WEEKS" SAYS THE HOLDS THE ROUTINE RUNS. Traditional L3 week 2
 * said "6 five-minute holds after the fatigue block. The next is added at week 5." while the
 * routine ran 5: R-27 caps a session's time under pressure (L3 36 min, the 7.5-min fatigue
 * block included, so 5 five-minute holds) and turns the holds past it into pressure. The line
 * now takes the same cap Mint#prescribe writes with (Plan#r2MaxHolds), so line and routine
 * agree.
 */
class TraditionalNextWeeksTest {

    private static final int STD_FAT_L3 = Mint.standardFatSec(Plan.L3);

    @Test void levelThreeWeekTwoIsTheFiveThatRun() {
        String said = TrainerTab.traditionalWeeksSaid(Plan.L3, 2, STD_FAT_L3);
        assertEquals("Level 3, week 2: 5 five-minute holds after the fatigue block, the most its "
            + "36-minute cap lets run: the plan raises the pressure in place of more holds.",
            said);
        // ...and it is the count the plan writes.
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_GIRTH_TRADITIONAL, Plan.L3, 2, 30, 7, 43, 0, 0,
                                    null);
        assertEquals(5, rx.sets);
    }

    @Test void withNoFatigueBlockLevelThreeStillGrows() {
        assertEquals("Level 3, week 2: 6 five-minute holds. The next is added at week 5.",
            TrainerTab.traditionalWeeksSaid(Plan.L3, 2, 0).replace(" after the fatigue block", ""));
    }

    @Test void levelOneStopsAtItsTwentyMinutes() {
        assertEquals("Level 1, week 1: 3 five-minute holds. The next is added at week 5.",
            TrainerTab.traditionalWeeksSaid(Plan.L1, 1, 0));
        assertEquals("Level 1, week 5: 4 five-minute holds, the most its 20-minute cap lets "
            + "run: the plan raises the pressure in place of more holds.",
            TrainerTab.traditionalWeeksSaid(Plan.L1, 5, 0));
        assertEquals(TrainerTab.traditionalWeeksSaid(Plan.L1, 5, 0),
            TrainerTab.traditionalWeeksSaid(Plan.L1, 13, 0).replace("week 13", "week 5"));
    }

    @Test void levelsTwoAndFour() {
        assertTrue(TrainerTab.traditionalWeeksSaid(Plan.L2, 3, 0)
            .endsWith("6 five-minute holds, the same every week."));
        assertEquals("Level 4: 7 five-minute holds after the fatigue block, the most its "
            + "44-minute cap lets run: the plan raises the pressure in place of more holds.",
            TrainerTab.traditionalWeeksSaid(Plan.L4, 1, Mint.standardFatSec(Plan.L4)));
    }

    @Test void aBuildUpIsCappedTheSameWay() {
        Model.TrainerTrackState st = new Model.TrainerTrackState();
        st.level = Plan.L4; st.weekIndex = 3; st.buildSets = 4; st.buildWeek = 1;
        String said = TrainerTab.traditionalWeeksSaid(st, Mint.standardFatSec(Plan.L4));
        assertTrue(said.startsWith("Level 4, week 3: "), said);
        assertTrue(said.contains("of the level's 7 five-minute holds"), said);
    }
}
