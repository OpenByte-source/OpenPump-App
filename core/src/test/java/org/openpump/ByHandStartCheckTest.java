package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * THE DEVICE WALK'S ROUND (EMUHAND, 2026-10-07) and THE OWNER'S ANSWER ON H-2.
 *
 * H-2: a routine that opens with a step done by hand starts straight on it with the pump
 * uncommanded; the start check runs when Done is tapped, right before the first pressure.
 * The rule is ByHand#deferStartCheck / #startCheckBefore; the run's wiring (beginRunFlow,
 * playPreset, the check's own pass handing back to the run) is WiringCheck invariant 251 (e).
 * H-1, H-3, H-4, H-5, H-7: the words and the fold below.
 */
class ByHandStartCheckTest {

    @Test void aLengthRoutineThatOpensByHandDefersItsStartCheckToTheWarmUp() {
        Model m = ByHandStageTest.puller();
        Model.Routine r = ByHandStageTest.lengthRoutine(m);
        assertTrue(ByHand.opensByHand(r));
        assertTrue(ByHand.deferStartCheck(r, true, false, false), "the guided start waits");
        assertTrue(ByHand.deferStartCheck(r, false, true, false), "so does the seal check");
        assertFalse(ByHand.deferStartCheck(r, false, false, false), "no check, nothing to move");
        assertFalse(ByHand.deferStartCheck(r, true, false, true),
            "a before-assessment's pull keeps the start as it was");
        // The check runs before the first step that can command pressure: the pump warm-up,
        // straight after the release - never before the release itself.
        List<Model.Preset> plan = m.plan(r);
        assertFalse(ByHand.startCheckBefore(true, plan.get(0)), "not before the release");
        int first = -1;
        for (int i = 0; i < plan.size() && first < 0; i++)
            if (ByHand.startCheckBefore(true, plan.get(i))) first = i;
        assertEquals(1, first, "before the step after the release");
        assertTrue(RunShape.isWarmUp(r.stages.get(plan.get(first).stageIdx)), "the warm-up");
        assertFalse(ByHand.startCheckBefore(false, plan.get(first)), "only when deferred");
        // A rejoin at the release or at the warm-up has had no pressure - the check still
        // runs; past the warm-up it was had.
        assertTrue(ByHand.noPressureBefore(plan, 0));
        assertTrue(ByHand.noPressureBefore(plan, first));
        assertFalse(ByHand.noPressureBefore(plan, first + 1));
    }

    @Test void aRoutineThatDoesNotOpenByHandStartsAsBefore() {
        Model m = new Model();
        Model.Set s = Model.Set.fixed(m.newSetId(), "Work", 20, 10, 60, 5, 50, 600);
        m.sets.add(s);
        Model.Routine r = new Model.Routine();
        r.id = m.newRoutineId();
        r.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ s.id }));
        m.routines.add(r);
        assertFalse(ByHand.opensByHand(r));
        assertFalse(ByHand.deferStartCheck(r, true, true, false));
        // A plain rest first is not by hand either.
        Model.Routine rr = new Model.Routine();
        rr.stages.add(Model.Stage.restOf("Rest", 60));
        assertFalse(ByHand.deferStartCheck(rr, true, false, false));
        assertFalse(ByHand.opensByHand(null));
        assertFalse(ByHand.opensByHand(new Model.Routine()));
    }

    @Test void theChipUnderTheChartSaysByHandNeverResting() {
        // H-1: RunChip's sentence over a step done by hand; a plain rest keeps its own.
        RunChip vented = RunChip.of(true, true, false, true, false, true, 0, 0, RunChip.PULL, true);
        assertEquals("By hand — the pump is vented", vented.text);
        assertEquals(Look.SAFE, vented.tone);
        RunChip notDown = RunChip.of(true, true, true, false, false, true, 9, 0, RunChip.PULL, true);
        assertEquals("By hand — the pump has not come down yet", notDown.text);
        assertEquals(Look.COMMANDED, notDown.tone);
        assertEquals("Resting — the cuff is vented",
            RunChip.of(true, true, false, true, false, true, 0, 0, RunChip.PULL).text);
        assertFalse(RunChip.of(true, true, false, false, false, false, 0, 0, RunChip.PULL, true)
            .text.toLowerCase(java.util.Locale.US).contains("rest"));
    }

    @Test void theLinesSayShortWhereTheyDoNotFit() {
        // H-4: the changeover's line and the by-hand wait's have short forms.
        RunLook.Now n = new RunLook.Now();
        n.armed = true; n.resting = true; n.awaitingAck = true;
        assertEquals(RunLook.SWAP, RunLook.statusLeft(n));
        n.narrow = true;
        assertEquals("CHANGE CYLINDER", RunLook.statusLeft(n));
        n.byHand = true;
        assertEquals("BY HAND · TAP DONE", RunLook.statusLeft(n));
        n.narrow = false;
        assertEquals("BY HAND · DONE WHEN YOU ARE", RunLook.statusLeft(n));
    }

    @Test void aWaitByHandIsFoldedIntoThePlanOnceItEnds() {
        // H-4: the "+m:ss" a wait added comes out when Done or I've swapped ends it.
        assertEquals(30_000L, RunEdit.foldWait(197_000L, 167_000L), "a pause's own share stays");
        assertEquals(0L, RunEdit.foldWait(167_000L, 167_000L));
        assertEquals(0L, RunEdit.foldWait(10_000L, 167_000L), "never below nothing");
        assertEquals("47:12 / 66:50", RunEdit.routineElapsedLine(2_832_000L, 4_010_000L,
            RunEdit.foldWait(167_000L, 167_000L)));
    }

    @Test void theStartCheckAfterDoneSaysThePumpStartsAndAtAStartItsOwnWords() {
        String line = "The routine starts once the cuff has held this pressure for 2 seconds.";
        assertEquals(line, ByHand.startWords(line, false), "a normal start: today's words");
        assertEquals("The pump starts once the cuff has held this pressure for 2 seconds.",
            ByHand.startWords(line, true));
        assertEquals("Before the pump starts", ByHand.startWords("Before the routine starts", true));
        String[] all = {
            "Pump by hand to that number and hold it. The routine starts once it has stayed there "
                + "for 2 seconds.",
            "Pulling to that pressure now. The routine starts on its own once the cuff has held "
                + "it for two seconds, not when the pull is sent, so a seal that will not take "
                + "never turns into a routine that runs anyway.",
            "— reseat it and it will start on its own, or start the routine anyway and pull",
            "Skipping starts the routine with no seal verdict — the hold now on the cuff is "
                + "not vented first, exactly as \"Continue to session\" would leave it.",
            "Continue to session" };
        for (int i = 0; i < all.length; i++) {
            String mid = ByHand.startWords(all[i], true);
            assertFalse(mid.contains("routine") || mid.contains("session"), mid);
            assertEquals(all[i], ByHand.startWords(all[i], false));
        }
    }

    @Test void aByHandStepNeverSaysVentedBeforeThePumpShowsIt() {
        // H-6: "Venting…" until the reading confirms the vent, for both by-hand steps.
        assertEquals("Venting…", ByHand.nowLine(false, false, false));
        assertEquals("Venting…", ByHand.nowLine(true, false, false));
        assertEquals("Venting…", ByHand.nowLine(false, true, false));
        assertEquals(ByHand.VENTED_LINE, ByHand.nowLine(false, false, true));
        assertEquals(ByHand.SWAP_LINE, ByHand.nowLine(true, false, true));
        assertEquals("BY HAND · VENTING", ByHand.head(false));
        assertEquals(ByHand.HEAD, ByHand.head(true));
        assertEquals(" · venting", ByHand.kickerState(false, false));
        assertEquals(" · vented", ByHand.kickerState(false, true));
        assertEquals(" · still under pressure", ByHand.kickerState(true, true));
        String[] before = { ByHand.nowLine(false, false, false), ByHand.nowLine(true, false, false),
            ByHand.head(false), ByHand.kickerState(false, false), ByHand.pauseTap(false, false),
            ByHand.pauseTap(true, false), ByHand.pauseSaid(false), ByHand.doneSaid(false) };
        for (int i = 0; i < before.length; i++)
            assertFalse(before[i].toLowerCase(java.util.Locale.US).matches(".*\\bvented\\b.*"),
                "\"" + before[i] + "\" says vented before it is");
        assertTrue(ByHand.pauseTap(false, true).contains("is vented"));
        assertTrue(ByHand.doneSaid(true).contains("is vented"));
        // The chip, told the same: not vented, not "the pump is vented".
        assertFalse(RunChip.of(true, true, false, false, false, true, 4, 0, RunChip.PULL, true)
            .text.contains("vented"));
    }

    @Test void theDeviceRechecksPolish() throws Exception {
        // E2-1: the Start confirm says what really comes first.
        assertEquals("First: Tunica release, by hand — the pump starts after you press Done",
            ByHand.startConfirmFirst("Tunica release — by hand", ""));
        assertEquals("First: measure (about 1 min), then Tunica release, by hand — the pump "
            + "starts after you press Done",
            ByHand.startConfirmFirst("Tunica release — by hand", "measure (about 1 min)"));
        // E2-2: a wait is late only once its planned time has passed.
        assertFalse(ByHand.waitIsLate(1_000L, 120_000L));
        assertTrue(ByHand.waitIsLate(120_000L, 120_000L));
        // E2-3: the widget's changeover line is the short one; the notification keeps its own.
        assertEquals("By hand · swap cylinder · tap I’ve swapped",
            ByHand.widgetName("Length L3", ByHand.SWAP_WAITING, false));
        assertEquals("Length L3", ByHand.widgetName("Length L3", ByHand.SWAP_WAITING, true));
        // E2-4: a run stopped at the start check after its by-hand step.
        Model.Sess s = new Model.Sess();
        s.peakKpa = Double.valueOf(5.8);
        s.cmdPeakKpa = Double.valueOf(0.0);
        s.presetsDone = 1;
        s.byHandStopSec = 320L;
        String said = Summary.noticed(s, false);
        assertEquals("Stopped at the start check after the step done by hand — 5:20 by hand, "
            + "with nothing commanded by the routine.", said);
        assertFalse(said.contains("asked") || said.contains("to plan"), said);
        assertEquals(320L, Model.Sess.fromJson(s.toJson()).byHandStopSec);
        s.byHandStopSec = 0L;
        assertFalse(s.toJson().has("bhChk"), "0 is not written");
        assertTrue(Summary.noticed(s, false).contains("asked for"), "any other run: as before");
        // E2-5: a minute unanswered mid-run ends the run, and it is saved.
        assertEquals("With no answer in a minute, the run ends and is saved.",
            ByHand.startWords("With no answer in a minute, this start ends.", true));
        assertEquals("With no answer in a minute, the run ends and is saved.",
            ByHand.startWords("With no answer in a minute, the pump is released.", true));
        assertEquals("With no answer in a minute, this start ends.",
            ByHand.startWords("With no answer in a minute, this start ends.", false));
    }

    @Test void theThirdChecksTwoFixes() {
        // E3-1: "Time left" rounds up while the planned time runs, and is 0:00 once it passed -
        // the held clock's few milliseconds ahead never read as 0:01.
        assertEquals(120, ByHand.timeLeftSec(119_400L, false));
        assertEquals(1, ByHand.timeLeftSec(250L, false));
        assertEquals(0, ByHand.timeLeftSec(250L, true), "late: 0:00, not 0:01");
        assertEquals(0, ByHand.timeLeftSec(-40L, false));
        // E3-2: the minutes by hand are the clock at Done, not the check's seconds after it.
        assertEquals(300L, ByHand.byHandStopSec(true, true, 300L));
        assertEquals(1L, ByHand.byHandStopSec(true, true, 0L), "at least a second");
        assertEquals(0L, ByHand.byHandStopSec(true, false, 300L), "not stopped at the check");
        assertEquals(0L, ByHand.byHandStopSec(false, true, 300L), "not stopped at all");
    }

    @Test void theChangeoverWaitSaysWaitingNotATime() {
        // H-5: the notification and widget lead while the changeover waits.
        String said = ByHand.notification("Swap to your girth cylinder — next is x", false, true);
        assertEquals(ByHand.SWAP_WAITING, said);
        assertTrue(said.contains("waiting — tap “I’ve swapped”"));
        assertEquals("By hand · Tunica release",
            ByHand.notification("Tunica release — by hand", false, false));
        // H-7: the strip's cell once the release's time is up.
        assertEquals("Time left", ByHand.STRIP_TIME_UP);
    }
}
