package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * S15 FOLLOW-UP (the owner's decision, 2026-09-26): THE ALTERNATE WEEK ASKS WHICH TRACK LEADS.
 *
 * Which track starts an alternate week is not in the material, so setup no longer decides it:
 * it asks "Start the week with Length or Girth?", with neither answer marked. Until it is
 * answered the week starts with length - the default the S15 build chose - and the question
 * stays on the screen.
 */
class WeekLeadTest {

    private static final int L = Schedule.PLAN_LENGTH, G = Schedule.PLAN_GIRTH,
                             A = Schedule.PLAN_ANY;

    @Test void girthCanLead() {
        assertArrayEquals(new int[]{ G, L, G, L, G, L, A },
            Schedule.alternatePlan(Schedule.alternateDays(), Schedule.PLAN_GIRTH));
        assertArrayEquals(new int[]{ L, G, L, G, L, G, A },
            Schedule.alternatePlan(Schedule.alternateDays(), Schedule.PLAN_LENGTH));
        boolean[] mwf = { true, false, true, false, true, false, false };
        assertArrayEquals(new int[]{ G, A, L, A, G, A, A },
            Schedule.alternatePlan(mwf, Schedule.PLAN_GIRTH));
    }

    @Test void unansweredStartsWithLengthAsBefore() {
        assertArrayEquals(Schedule.alternatePlan(Schedule.alternateDays()),
            Schedule.alternatePlan(Schedule.alternateDays(), Schedule.PLAN_ANY),
            "no answer yet: the S15 default, length first");
        assertArrayEquals(Schedule.alternatePlan(Schedule.alternateDays()),
            Schedule.alternatePlan(Schedule.alternateDays(), 99), "nonsense reads as no answer");
    }

    @Test void theWeekIsWrittenWithTheAnswer() {
        Schedule s = new Schedule();
        s.applyWeekShape(Schedule.SHAPE_ALTERNATE, Schedule.alternateDays(), Schedule.PLAN_GIRTH);
        assertArrayEquals(new int[]{ G, L, G, L, G, L, A }, s.plan);
        Schedule c = new Schedule();
        c.applyWeekShape(Schedule.SHAPE_COMBINED, Schedule.alternateDays(), Schedule.PLAN_GIRTH);
        assertArrayEquals(new int[]{ A, A, A, A, A, A, A }, c.plan,
            "the combined week has no lead - its plans are left as they were");
        Schedule d = new Schedule();
        d.applyWeekShape(Schedule.SHAPE_ALTERNATE, Schedule.alternateDays());
        assertArrayEquals(new int[]{ L, G, L, G, L, G, A }, d.plan, "the old form: length leads");
    }

    @Test void theQuestionNamesBothTracks() {
        String q = Schedule.ALTERNATE_LEAD_QUESTION;
        assertTrue(q.contains("Length") && q.contains("Girth"), q);
        assertTrue(q.endsWith("?"), q);
    }
}
