package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.junit.jupiter.api.Test;

/**
 * S11 (the owner's decision, 2026-09-26): THE DELOAD REACHES THE LENGTH TRACK - NO TRACTION
 * DURING THE DELOAD WEEK, NORMAL LOAD AFTERWARDS.
 *
 * The deload was plan-wide, but the only thing it did to a session was the taper's 4 hg then
 * 2 hg cut, and that cut reaches the length coda and never the traction blocks, whose pressure
 * comes from the load. So a deload week still pulled at full load. The guidance describes the
 * week as rest: complete rest or pulse stretches, rest or light retention only, complete rest
 * on training days, one week off in every four. None of it is a pump session, so the length
 * track
 * offers no session in the week at all - not an expansion-only one either.
 */
class DeloadLengthTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    /** Monday 2026-09-07 at 10:00 local, plus `day` days. */
    private static long at(int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 7, 10, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, day);
        return c.getTimeInMillis();
    }

    /** Enrolled in both tracks, every day a both-tracks day, a deload taken on day 0. */
    private static Model inDeload() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerLengthOn = true;
        m.rxLengthFirst = true;
        for (int i = 0; i < Schedule.DAYS; i++) {
            m.sched.days[i] = true;
            m.sched.plan[i] = Schedule.PLAN_BOTH;
        }
        m.trainerLastDeloadMs = at(0) - 3600000L;     // tapped an hour before day 0
        Deload.arm(m, Deload.endMs(m));
        return m;
    }

    private static Model.Routine routine(boolean traction) {
        Model.Routine r = new Model.Routine();
        r.id = traction ? "pull" : "plain";
        r.trainerTrack = Plan.TRACK_LENGTH;
        Model.Stage st = new Model.Stage();
        st.name = traction ? "Strain holds" : "Expansion";
        st.traction = traction;
        r.stages.add(st);
        return r;
    }

    @Test void theLengthTrackRestsForTheWeekAndOnlyTheWeek() {
        Model m = inDeload();
        assertTrue(Deload.lengthRests(m, at(0)), "day 0");
        assertTrue(Deload.lengthRests(m, at(6)), "day 6");
        assertFalse(Deload.lengthRests(m, at(7)), "the week is over");
        m.trainerEnrolled = false;
        assertFalse(Deload.lengthRests(m, at(3)), "no plan, no deload");
        assertFalse(Deload.lengthRests(new Model(), at(3)), "no deload ever taken");
        assertFalse(Deload.lengthRests(null, at(3)));
    }

    @Test void upNextOffersNoLengthSessionInTheWeek() {
        Model m = inDeload();
        UpNext in = TrainerTab.upNextNow(m, at(2), false);
        assertEquals(UpNext.GIRTH, in.what,
            "a both-tracks day in the deload week offers girth, never length first");
        m.sched.setOverride(PhotoCalendar.dayKey(at(3)), Schedule.PLAN_LENGTH);
        assertNotEquals(UpNext.LENGTH, TrainerTab.upNextNow(m, at(3), false).what,
            "not even on a length day");
        assertEquals(UpNext.LENGTH, TrainerTab.upNextNow(m, at(8), false).what,
            "and after the week it is back, first as the person ordered it");
    }

    @Test void theEngineSaysRestForLengthAndCitesTheMaterial() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = Plan.L1;
        in.inDeloadWeek = true;
        Plan.Decision d = Plan.evaluate(in);
        assertEquals(Plan.ACTION_DELOAD, d.action);
        assertEquals(Plan.TAG_SOURCE, d.tag, "the week's rest is stated in the material");
        assertTrue(d.reason.contains("no traction"), d.reason);
        // The rule is shown in the decision history, so it says "the guidance" and names no
        // source (the owner's rule, 2026-09-26); the rule is paraphrased in Plan's comment.
        assertTrue(d.rule.contains("the guidance") && !NoBookNamesTest.namesASource(d.rule), d.rule);

        Plan.Inputs g = new Plan.Inputs();
        g.track = Plan.TRACK_GIRTH_INTERVAL;
        g.level = Plan.L1;
        g.inDeloadWeek = true;
        Plan.Decision gd = Plan.evaluate(g);
        assertEquals(Plan.ACTION_DELOAD, gd.action, "girth's deload is unchanged");
        assertEquals(Plan.TAG_INFERRED, gd.tag);
        assertFalse(gd.reason.contains("traction"));
    }

    @Test void startingARoutineThatPullsInTheWeekIsNamedOnTheConfirm() {
        Model m = inDeload();
        String note = Deload.startNote(m, routine(true), at(2));
        assertTrue(note.contains("no traction"), note);
        assertTrue(note.contains("the guidance") && !NoBookNamesTest.namesASource(note), note);
        assertEquals("", Deload.startNote(m, routine(false), at(2)), "nothing pulls, nothing said");
        assertEquals("", Deload.startNote(m, routine(true), at(9)), "after the week, nothing said");
        assertEquals("", Deload.startNote(m, null, at(2)));
    }

    /** The two-tube rack SelfTest's length session is built on: a 12.7 cm girth, a 4.0 cm
     *  bore that pulls and a 4.5 cm bore that fits. */
    private static Model rack() {
        Model m = new Model();
        Model.Reading r = new Model.Reading();
        r.ts = 1788440800000L;
        r.method = Model.Reading.METHOD_MSEG;
        r.gir = 12.7;
        m.measLog.all.add(r);
        Model.Cylinder l = new Model.Cylinder();
        l.id = "L"; l.label = "Length tube"; l.role = Model.Cylinder.ROLE_LENGTH; l.boreCm = 4.0; l.lengthCm = 23.0;
        Model.Cylinder g = new Model.Cylinder();
        g.id = "G"; g.label = "Girth tube"; g.boreCm = 4.5; g.lengthCm = 23.0;
        m.cylinders.add(l);
        m.cylinders.add(g);
        m.activeCylinder = 0;
        m.trainerLength.loadLb = 4.0;
        m.trainerLength.strainSets = 3;
        return m;
    }

    private static int[] pullAndCoda(Model m) {
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_LENGTH, Plan.L1, 1, 6.0 * Plan.HG, 2,
                                    m.ceilKpa, 0, null);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        int pull = -1, coda = -1;
        for (Model.Stage st : r.stages) {
            if (st.setIds.isEmpty()) continue;
            Model.Set s = m.set(st.setIds.get(0));
            if (st.traction && st.name.startsWith("Strain")) pull = s.up;
            if ("Expansion".equals(st.name)) coda = s.up;
        }
        return new int[]{ pull, coda };
    }

    @Test void afterTheWeekTheLoadIsTheNormalLoad() {
        int[] normal = pullAndCoda(rack());
        Model back = rack();
        Deload.arm(back, System.currentTimeMillis() - DAY);   // a taper step in force today
        assertTrue(back.gentleNow(System.currentTimeMillis()));
        int[] firstDayBack = pullAndCoda(back);
        assertTrue(normal[0] > 0 && normal[1] > 0, "both blocks were found");
        assertEquals(normal[0], firstDayBack[0],
            "the strain pull comes from the load alone - normal load, no taper on traction");
        assertTrue(firstDayBack[1] < normal[1], "the coda still takes the taper's cut");
    }
}
