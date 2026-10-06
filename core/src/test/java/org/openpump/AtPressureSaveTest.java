package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * C7 - THE AT-PRESSURE CLOCK'S LENGTHENING IS NOT AN EDIT TO SAVE.
 *
 * With Set timing at "At pressure only", a set's countdown stands still while the cuff is
 * under the line, and the set runs that much longer (SessionActivity#tickTupTiming, through
 * setPresetDuration). The recording only saw the longer span: AsRun#blocks read any preset
 * that ran past its plan as an edited duration, so the summary offered "SAVE CHANGES" after
 * every such run, and saving wrote the STRETCHED lengths into the routine - lengthening it
 * for good, for something the user never changed.
 *
 * The clock now tells the recording how long it held each preset (AsRun#clockHeld, on the
 * open row), and the save layer counts a set on its own clock. What the user did - a pull,
 * drop, hold or power change, +30 s, an edited length, a skip - is still offered, and a save
 * keeps the edit at the set's ORIGINAL length.
 *
 * Fixture: "Warm + Build" - Work = [Warm (fixed 14/7, 30 s + 5 s, 60 %, 2:00 - a 35 s cycle),
 * Progressive Ramp (5 steps over 6:00)], Cool = [Pulse (26/12, 4 s + 3 s, 100 %, 4:00)].
 * Rows are placed by hand on the elapsed clock, in ms, the clock's held time recorded while
 * each row is open - exactly where the Activity records it.
 */
class AtPressureSaveTest {

    private String unitBefore;
    @BeforeEach void kpa() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_KPA; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    /* ------------------------------------------------------------------ fixtures */

    private static Model model() {
        Model m = new Model();
        m.ceilKpa = 40;
        m.sets.add(Model.Set.fixed("s1", "Warm", 14, 7, 30, 5, 60, 120));
        m.sets.add(Model.Set.ramp ("s4", "Progressive Ramp", 18, 9, 30, 5, 70,
                                                             34, 10, 45, 5, 100, 5, 360));
        m.sets.add(Model.Set.fixed("s3", "Pulse", 26, 12, 4, 3, 100, 240));
        Model.Routine r = new Model.Routine();
        r.id = "r1"; r.name = "Warm + Build";
        r.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[]{"s1", "s4"}));
        r.stages.add(Model.Stage.of("Cool", Model.STAGE_COOL, new String[]{"s3"}));
        m.routines.add(r);
        m.selected = "r1";
        return m;
    }

    private static AsRun recording(Model m) {
        Model.Routine r = m.routine("r1");
        AsRun run = new AsRun("r1", r.name, Model.AsRunSnapshot.of(r, m), false, m.ceilKpa);
        run.ts = 1755600000000L;
        return run;
    }

    private static Model.Sess filed(AsRun run) {
        Model.Sess s = new Model.Sess();
        s.id = "x"; s.ts = run.ts; s.routineId = run.routineId; s.routineName = run.routineName;
        s.completed = true; s.manual = false; s.asRunSnapshot = run.snap;
        return s;
    }

    /** One row carrying the preset's own values; the at-pressure clock held it `heldMs`
     *  while it was open. */
    private static AsRun.Row play(AsRun run, int kind, Model.Preset p, long t0, long t1,
                                  long heldMs) {
        AsRun.Row r = run.open(kind, t0, p, p.up, p.up, p.lo, p.uh, p.uh, p.lh, p.sp);
        if (heldMs > 0) assertTrue(run.clockHeld(heldMs));
        run.close(t1);
        return r;
    }

    /** A live adjustment's row under the preset's identity, held `heldMs` by the clock. */
    private static AsRun.Row adjusted(AsRun run, Model.Preset p, long t0, long t1,
                                      int up, int lo, long heldMs) {
        AsRun.Row r = run.open(AsRun.OVERRIDE, t0, p, up, up, lo, p.uh, p.uh, p.lh, p.sp);
        if (heldMs > 0) assertTrue(run.clockHeld(heldMs));
        run.close(t1);
        return r;
    }

    /** plan[from..to) exactly as planned from t, the clock holding each preset `heldMs`
     *  (so each runs that much longer); returns the elapsed clock after. */
    private static long playPlanned(AsRun run, List<Model.Preset> plan, int from, int to,
                                    long t, long heldMs) {
        for (int i = from; i < to; i++) {
            long len = plan.get(i).durMs + heldMs;
            play(run, AsRun.PLAN, plan.get(i), t, t + len, heldMs);
            t += len;
        }
        return t;
    }

    private static int firstOf(List<Model.Preset> plan, String setId) {
        for (int i = 0; i < plan.size(); i++) if (setId.equals(plan.get(i).setId)) return i;
        throw new AssertionError("no preset of " + setId);
    }

    private static List<AsRun.Block> ofSet(List<AsRun.Block> bs, String setId) {
        List<AsRun.Block> out = new ArrayList<AsRun.Block>();
        for (int i = 0; i < bs.size(); i++) if (setId.equals(bs.get(i).setId)) out.add(bs.get(i));
        return out;
    }

    private static AsRun.SetChange changeOf(List<AsRun.SetChange> cs, String setId) {
        for (int i = 0; i < cs.size(); i++) if (setId.equals(cs.get(i).setId)) return cs.get(i);
        throw new AssertionError("no set change for " + setId);
    }

    /** What the summary decides from a recording - prepareSaveCard's own three calls. */
    private static int offer(List<AsRun.Block> bs, Model m) {
        return AsRun.offerKind(bs, AsRun.evaluate(bs, m), false);
    }

    /* ------------------------------------------- the clock alone offers nothing */

    @Test
    void everySetHeldByTheClockIsNotAChange() {
        Model m = model();
        AsRun run = recording(m);
        List<Model.Preset> plan = m.plan(m.routine("r1"));
        // Every preset of the routine ran 40 s longer than planned, all of it the clock.
        playPlanned(run, plan, 0, plan.size(), 0L, 40000L);

        List<AsRun.Block> bs = AsRun.blocks(run, m);
        for (int i = 0; i < bs.size(); i++)
            assertTrue(bs.get(i).unchanged, "block " + i + " (" + bs.get(i).setName
                + ") ran as planned on its own clock - it must read unchanged");
        assertFalse(AsRun.differs(bs), "History's door: nothing differs from the plan");
        AsRun.Change c = AsRun.evaluate(bs, m);
        assertFalse(c.differs);
        assertFalse(c.overThreshold);
        assertEquals("", c.quietText, "no quiet line claiming an extension");
        assertEquals(AsRun.OFFER_NONE, offer(bs, m), "no SAVE CHANGES after such a run");
        List<AsRun.SetChange> cs = AsRun.setChanges(bs);
        for (int i = 0; i < cs.size(); i++)
            assertFalse(cs.get(i).changed, cs.get(i).setName + " is not a change to save");
    }

    @Test
    void aRampStepHeldByTheClockIsNotAChange() {
        Model m = model();
        AsRun run = recording(m);
        List<Model.Preset> plan = m.plan(m.routine("r1"));
        int r0 = firstOf(plan, "s4");
        long t = playPlanned(run, plan, 0, r0 + 2, 0L, 0L);
        // The ramp's third step: held 30 s under its pull.
        t = playPlanned(run, plan, r0 + 2, r0 + 3, t, 30000L);
        playPlanned(run, plan, r0 + 3, plan.size(), t, 0L);

        List<AsRun.Block> bs = AsRun.blocks(run, m);
        assertEquals(1, ofSet(bs, "s4").size(), "the ramp is one block, not split at the step");
        assertTrue(ofSet(bs, "s4").get(0).unchanged, "the ramp ran as planned on its own clock");
        assertEquals(AsRun.OFFER_NONE, offer(bs, m));
    }

    @Test
    void theClockHeldEitherSideOfAHoldIsStillNotAChange() {
        Model m = model();
        AsRun run = recording(m);
        List<Model.Preset> plan = m.plan(m.routine("r1"));
        Model.Preset p0 = plan.get(0);
        // 50 s of which the clock held 10; a 30 s HOLD; then 100 s of which it held 20.
        // On its own clock the set delivered 40 + 80 = 120 s: exactly its plan.
        play(run, AsRun.PLAN, p0, 0L, 50000L, 10000L);
        play(run, AsRun.HOLD, p0, 50000L, 80000L, 0L);
        play(run, AsRun.PLAN, p0, 80000L, 180000L, 20000L);
        playPlanned(run, plan, 1, plan.size(), 180000L, 0L);

        List<AsRun.Block> bs = AsRun.blocks(run, m);
        assertTrue(ofSet(bs, "s1").get(0).unchanged);
        assertEquals(AsRun.OFFER_NONE, offer(bs, m));
    }

    /* -------------------------------------------- what the user did stays offered */

    @Test
    void plusThirtyIsStillOfferedAndSavesThirtyNotTheClock() {
        Model m = model();
        AsRun run = recording(m);
        List<Model.Preset> plan = m.plan(m.routine("r1"));
        Model.Preset p0 = plan.get(0);
        // +30 s on Warm, and the clock held it 45 s more: 195 s delivered.
        run.open(AsRun.PLAN, 0L, p0, p0.up, p0.up, p0.lo, p0.uh, p0.uh, p0.lh, p0.sp);
        assertTrue(run.extend(30000L));
        assertTrue(run.clockHeld(45000L));
        run.close(195000L);
        playPlanned(run, plan, 1, plan.size(), 195000L, 0L);

        List<AsRun.Block> bs = AsRun.blocks(run, m);
        List<AsRun.Block> warm = ofSet(bs, "s1");
        assertEquals(1, warm.size());
        AsRun.Block b = warm.get(0);
        assertFalse(b.unchanged, "+30 s is the user's choice: it is offered");
        assertEquals(30000L, b.extendMs, "the extension is the +30 s, not +30 s and the clock");
        assertFalse(b.edited, "nothing ran past the plan, the +30 s and the clock");
        assertEquals(140, b.saveDurSec(0), "the block saves 150 s snapped to whole 35 s cycles");
        AsRun.Change c = AsRun.evaluate(bs, m);
        assertTrue(c.overThreshold);
        assertTrue(c.quietText.indexOf("extended Work by " + Model.Fmt.t(30)) >= 0, c.quietText);
        assertEquals(AsRun.OFFER_CARD, offer(bs, m));

        AsRun.SetChange sc = changeOf(AsRun.setChanges(bs), "s1");
        assertTrue(sc.changed);
        assertEquals(150, sc.saveDurSec, "the planned 2:00 and the +30 s");
        assertTrue(AsRun.extendsPlan(sc));
        AsRun.applyInPlace(sc, m, m.routine("r1"), false, m.ceilKpa);
        assertEquals(150, m.set("s1").dur, "the saved set is 2:30, not 3:15");
    }

    @Test
    void anEditInAHeldSetSavesTheEditAtTheOriginalLength() {
        Model m = model();
        AsRun run = recording(m);
        List<Model.Preset> plan = m.plan(m.routine("r1"));
        Model.Preset p0 = plan.get(0);
        // Warm as planned for 60 s (the clock held 20 of them), then the pull moved to 18 for
        // the rest: 105 s, of which the clock held 25. On its own clock: 40 + 80 = 120 s.
        play(run, AsRun.PLAN, p0, 0L, 60000L, 20000L);
        adjusted(run, p0, 60000L, 165000L, 18, 7, 25000L);
        playPlanned(run, plan, 1, plan.size(), 165000L, 0L);

        List<AsRun.Block> bs = AsRun.blocks(run, m);
        List<AsRun.Block> warm = ofSet(bs, "s1");
        assertEquals(2, warm.size(), "the planned part and the adjusted part");
        assertFalse(warm.get(0).edited || warm.get(1).edited,
                    "no length was edited - the clock's time is not an edit");
        assertEquals(0L, warm.get(0).extendMs + warm.get(1).extendMs);
        assertEquals(35, warm.get(0).saveDurSec(0), "40 s of its own clock: one cycle");
        assertEquals(70, warm.get(1).saveDurSec(0), "80 s of its own clock: two cycles");
        assertEquals(AsRun.OFFER_CARD, offer(bs, m), "the pull change is offered");

        AsRun.SetChange sc = changeOf(AsRun.setChanges(bs), "s1");
        assertTrue(sc.changed);
        assertEquals(18, sc.up, "the edit is kept");
        assertEquals(120, sc.saveDurSec, "at the set's own 2:00, not the 2:45 it took");
        assertFalse(AsRun.extendsPlan(sc), "the clock's time never lengthens the saved set");

        // Update in place: the edit lands, the length stays.
        AsRun.applyInPlace(sc, m, m.routine("r1"), false, m.ceilKpa);
        assertEquals(18, m.set("s1").up);
        assertEquals(120, m.set("s1").dur, "the routine keeps its ORIGINAL duration");

        // Save as a new routine: the copy carries the edit at the original length too.
        Model m2 = model();
        AsRun run2 = recording(m2);
        List<Model.Preset> plan2 = m2.plan(m2.routine("r1"));
        play(run2, AsRun.PLAN, plan2.get(0), 0L, 60000L, 20000L);
        adjusted(run2, plan2.get(0), 60000L, 165000L, 18, 7, 25000L);
        playPlanned(run2, plan2, 1, plan2.size(), 165000L, 0L);
        List<AsRun.SetChange> cs2 = AsRun.setChanges(AsRun.blocks(run2, m2));
        boolean[] use = new boolean[cs2.size()];
        for (int i = 0; i < use.length; i++) use[i] = cs2.get(i).changed;
        AsRun.Commit made = AsRun.buildNewRoutine(cs2, use, new boolean[cs2.size()], run2, m2,
                                                  "Warm + Build (as run)", m2.ceilKpa);
        assertNotNull(made.routineId);
        assertEquals(1, made.createdSetIds.size());
        Model.Set copy = m2.set(made.createdSetIds.get(0));
        assertEquals(18, copy.up);
        assertEquals(120, copy.dur, "the new routine's copy is 2:00 as well");
    }

    @Test
    void anEditedLengthInAHeldSetIsStillOfferedAtTheEditedLength() {
        Model m = model();
        AsRun run = recording(m);
        List<Model.Preset> plan = m.plan(m.routine("r1"));
        int pi = firstOf(plan, "s3");
        long t = playPlanned(run, plan, 0, pi, 0L, 0L);
        // The upcoming Pulse was edited from 4:00 to 5:00 mid-run, and the clock held it 30 s.
        play(run, AsRun.PLAN, plan.get(pi), t, t + 300000L + 30000L, 30000L);

        AsRun.Block b = ofSet(AsRun.blocks(run, m), "s3").get(0);
        assertFalse(b.unchanged, "an edited length is the user's change: it is offered");
        assertTrue(b.edited);
        assertEquals(60000L, b.extendMs, "the edit's minute, not the edit and the clock");
        AsRun.SetChange sc = changeOf(AsRun.setChanges(AsRun.blocks(run, m)), "s3");
        assertEquals(300, sc.saveDurSec, "saves at the edited 5:00, not 5:30");
        assertTrue(AsRun.extendsPlan(sc));
    }

    /* ---------------------------------------------- a skip, on the set's own clock */

    @Test
    void aSkipIsJudgedOnTheSetsOwnClock() {
        // Held 60 s, then skipped 70 s in: ten seconds of its two minutes - under half, so
        // the set was skipped, however long the clock had kept it running.
        Model m = model();
        AsRun run = recording(m);
        List<Model.Preset> plan = m.plan(m.routine("r1"));
        Model.Preset p0 = plan.get(0);
        run.open(AsRun.PLAN, 0L, p0, p0.up, p0.up, p0.lo, p0.uh, p0.uh, p0.lh, p0.sp);
        run.clockHeld(60000L);
        run.skip(70000L, p0.durMs);
        playPlanned(run, plan, 1, plan.size(), 70000L, 0L);
        List<AsRun.Block> warm = ofSet(AsRun.blocks(run, m), "s1");
        assertEquals(1, warm.size());
        assertTrue(warm.get(0).skipped, "10 s of 2:00 on its own clock is a skip");

        // Held 60 s, skipped 150 s in: 90 s of its own clock - cut short, and nothing longer.
        Model m2 = model();
        AsRun run2 = recording(m2);
        List<Model.Preset> plan2 = m2.plan(m2.routine("r1"));
        Model.Preset q0 = plan2.get(0);
        run2.open(AsRun.PLAN, 0L, q0, q0.up, q0.up, q0.lo, q0.uh, q0.uh, q0.lh, q0.sp);
        run2.clockHeld(60000L);
        run2.skip(150000L, q0.durMs);
        playPlanned(run2, plan2, 1, plan2.size(), 150000L, 0L);
        AsRun.Block b = ofSet(AsRun.blocks(run2, m2), "s1").get(0);
        assertFalse(b.skipped);
        assertTrue(b.cutMidRun, "90 s of 2:00 on its own clock was cut short");
        assertFalse(b.edited, "and was not lengthened");
        assertEquals(0L, b.extendMs);
    }

    /* ------------------------------------------------------- the recording keeps it */

    @Test
    void theHeldTimeSurvivesTheFileAndAnOldRowReadsNone() throws Exception {
        Model m = model();
        AsRun run = recording(m);
        List<Model.Preset> plan = m.plan(m.routine("r1"));
        playPlanned(run, plan, 0, plan.size(), 0L, 40000L);
        AsRun back = AsRun.fromJson(new JSONObject(run.toJson().toString()));
        assertEquals(run.rows.size(), back.rows.size());
        for (int i = 0; i < back.rows.size(); i++)
            assertEquals(40000L, back.rows.get(i).clockHeldMs, "row " + i);
        assertEquals(AsRun.OFFER_NONE, offer(AsRun.blocks(back, m), m),
                     "a recording reopened from History offers nothing either");

        // A row written before the clock was recorded: nothing held, as it always read.
        JSONObject old = run.rows.get(0).toJson();
        old.remove("clk");
        assertEquals(0L, AsRun.Row.fromJson(old).clockHeldMs);
    }
}
