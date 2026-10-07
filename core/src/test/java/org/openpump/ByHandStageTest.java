package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A BY-HAND STAGE IS NAMED WHILE IT PLAYS, AND THE TUNICA RELEASE WAITS FOR DONE (the owner's
 * decision, 2026-10-07). The release used to be "timed only": at 5:00 the run went on to the
 * warm-up by itself, and every screen called it REST. Now its time is a guide - at 0:00 it says
 * "Done when you are" and waits, vented and uncommanded - and the run names it BY HAND. The
 * waiting itself lives in the app (SessionActivity's Advance, held by WiringCheck invariant 251);
 * what is pure is here.
 */
class ByHandStageTest {

    /** A length session that pulls: a length tube that fits, a girth tube, a measured girth
     *  (SelfTest#twoTubeModel's shape). */
    static Model puller() {
        Model m = new Model();
        Model.Reading r = new Model.Reading();
        r.ts = 1788440800000L;
        r.method = Model.Reading.METHOD_MSEG;
        r.gir = 12.7;
        m.measLog.all.add(r);
        Model.Cylinder l = new Model.Cylinder();
        l.id = "L"; l.label = "Length tube"; l.role = Model.Cylinder.ROLE_LENGTH;
        l.boreCm = 4.0; l.lengthCm = 23.0;
        Model.Cylinder g = new Model.Cylinder();
        g.id = "G"; g.label = "Girth tube"; g.boreCm = 4.5; g.lengthCm = 23.0;
        m.cylinders.add(l);
        m.cylinders.add(g);
        m.activeCylinder = 0;
        return m;
    }

    static Model.Routine lengthRoutine(Model m) {
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_LENGTH, Plan.L1, 1, 20.0, 2, m.ceilKpa, 0, null);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        assertNotNull(r, "the plan builds a length routine");
        return r;
    }

    @Test void aPlanLengthRoutineOpensWithAByHandStageThatAwaitsDone() {
        Model m = puller();
        Model.Routine r = lengthRoutine(m);
        Model.Stage first = r.stages.get(0);
        assertTrue(first.manual, "the first stage is done by hand: " + first.name);
        assertTrue(first.rest, "...vented, commanding nothing");
        assertTrue(first.awaitsDone(), "...and it ends when the person taps Done");
        assertEquals("BY HAND · Tunica release", ByHand.title(first.name));

        // IT REACHES THE PLAN THE RUN PLAYS: its one step is by hand, holds no pressure, and is
        // the release - a guide clock, then a wait - not the changeover, which waits from its
        // start.
        List<Model.Preset> plan = m.plan(r);
        Model.Preset p = plan.get(0);
        assertTrue(ByHand.is(p), "the first step is by hand");
        assertTrue(ByHand.waitsAfterClock(p), "...and waits for Done once its time is up");
        assertFalse(p.awaitAck, "...not frozen from its start: its 5:00 is shown as a guide");
        assertEquals(0, p.up, "nothing is commanded while it waits");
        assertEquals(0, p.lo);
        assertEquals(RxBuild.TUNICA_RELEASE_SEC * 1000L, p.durMs, "the guide is 5:00");
    }

    @Test void theChangeoverStillWaitsFromItsStartAndIsByHandToo() {
        Model m = puller();
        Model.Routine r = lengthRoutine(m);
        Model.Stage over = null;
        for (int i = 1; i < r.stages.size(); i++)
            if (r.stages.get(i).manual) over = r.stages.get(i);
        assertNotNull(over, "the session changes tubes before its expansion");
        assertTrue(over.awaitAck && over.awaitsDone());
        List<Model.Preset> plan = m.plan(r);
        int waitsFromStart = 0, waitsAfterClock = 0;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p.awaitAck) { waitsFromStart++; assertTrue(ByHand.is(p), "the changeover is by hand"); }
            if (ByHand.waitsAfterClock(p)) waitsAfterClock++;
        }
        assertEquals(1, waitsFromStart, "the changeover, and only it, waits from its start");
        assertEquals(1, waitsAfterClock, "the release, and only it, waits once its time is up");
        assertEquals("BY HAND · " + over.name, ByHand.title(over.name),
            "the changeover keeps its own sentence");
    }

    @Test void aPlainRestIsNotByHandAndStaysRest() {
        Model.Stage rest = Model.Stage.restOf("Rest", 120);
        assertFalse(rest.awaitsDone());
        Model m = new Model();
        Model.Routine r = new Model.Routine();
        r.id = m.newRoutineId();
        r.stages.add(rest);
        m.routines.add(r);
        Model.Preset p = m.plan(r).get(0);
        assertFalse(ByHand.is(p));
        assertFalse(ByHand.waitsAfterClock(p));
        RunLook.Now n = new RunLook.Now();
        n.armed = true; n.resting = true; n.restLeftMs = 10_000L; n.pullNext = false;
        assertEquals("REST · 0:10 LEFT", RunLook.statusLeft(n));
    }

    @Test void aReleaseSavedBeforeThisWaitsTooWithNothingMigrated() throws Exception {
        // A stage as an older install saved it: no new key, the same three flags.
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("name", "Tunica release — by hand");
        o.put("rest", true);
        o.put("restSec", 300);
        o.put("manual", true);
        o.put("ack", false);
        Model.Stage st = Model.Stage.fromJson(o);
        assertTrue(st.awaitsDone(), "derived from what was saved, so it needs no Migrate case");
        assertFalse(st.awaitAck, "and nothing saved changed");
    }

    @Test void theWordsTheRunUses() {
        assertEquals("Tunica release", ByHand.bare("Tunica release — by hand"));
        assertEquals("Tunica release (by hand)", ByHand.coming("Tunica release — by hand"));
        assertEquals("Pump vented · do it by hand now", ByHand.nowLine(false, false));
        assertEquals("Done when you are", ByHand.nowLine(false, true));
        assertTrue(ByHand.nowLine(true, false).contains("I’ve swapped"),
            "the changeover keeps its own instruction");
        assertEquals("By hand · Tunica release",
            ByHand.notification("Tunica release — by hand", false));
        assertEquals("By hand · Done when you are", ByHand.notification("x", true));
    }

    @Test void theStatusLineSaysByHandNeverRestAndWarnsOfNoPull() {
        RunLook.Now n = new RunLook.Now();
        n.armed = true; n.resting = true; n.byHand = true; n.pullNext = true;
        n.restLeftMs = 272_000L;
        assertEquals("BY HAND · 4:32 LEFT", RunLook.statusLeft(n));
        n.restLeftMs = 8_000L;
        assertFalse(RunLook.pullWarning(n), "nothing pulls at 0:00 - it waits for Done");
        assertEquals("BY HAND · 0:08 LEFT", RunLook.statusLeft(n));
        n.awaitingAck = true;
        assertEquals("BY HAND · DONE WHEN YOU ARE", RunLook.statusLeft(n));
        n.holding = true;
        assertEquals(RunLook.PAUSED, RunLook.statusLeft(n), "a pause is still said first");
    }

    @Test void theNotificationLeadsWithIt() {
        String text = Session.runNotificationText(false, "Length", 1, 14, "4:32", "", "");
        assertEquals("By hand · Tunica release · preset 1 of 14 · 4:32 left",
            Session.runNotificationPhase(false, "By hand · Tunica release", text));
        assertEquals(text, Session.runNotificationPhase(false, "", text), "any other step: unchanged");
        String discreet = Session.runNotificationText(true, "Length", 1, 14, "4:32", "", "");
        assertEquals(discreet, Session.runNotificationPhase(true, "By hand · x", discreet),
            "discreet says nothing about the session");
    }

    @Test void aCopiedStepStaysByHand() {
        Model m = puller();
        Model.Preset z = m.plan(lengthRoutine(m)).get(0);
        assertTrue(z.manual);
        assertTrue(RunEdit.copyPreset(z, 1000L).manual, "Undo's copy keeps it by hand");
    }
}
