package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A RUN WHOSE DROPS SIT AT OR UNDER THE COUNTING LINE IS COUNTED TO THE BIT AS IT ALWAYS WAS
 * (coordinator follow-up to the 2026-09-30 drop change).
 *
 * The drop may now be set above 10 kPa, and such a drop is left out of the dose by phase and
 * read from the set clock (TupClock#doseLeavesOut, #readsSetClock). Every trainer routine's
 * drop (3 kPa) and every run before the change sits at or under 10 kPa, and for those nothing
 * may move: not the dose, not the net, not the level gates fed by it.
 *
 * THE REFERENCE VALUES WERE COMPUTED AT 1960ade (the commit before the drop change) by this
 * same fixture fed through that commit's own API - Session#noteSample without a phase, the net
 * recorder marked by TupClock#inDrop from the step's arming, as SessionActivity did then - and
 * are pinned here as exact doubles. Each recorded run: six 60 s + 10 s cycles at 4 Hz, a 3 s
 * rise into each hold, a 2 s bleed from the pull into each drop (drop-half samples that still
 * read above 10 kPa - the old dose counted them, and still does), noise on the hold and a
 * no-reading sample every 97th.
 *
 * WHAT A PHASE EXCLUSION APPLIED TO EVERY DROP WOULD HAVE DONE: lowered the dose of these
 * runs by the bleed-down and the hold-to-drop boundary - see the last test. It is not applied
 * to them.
 */
class DropCountingParityTest {

    static final long FRAME = 250L;
    static final int UH = 60, LH = 10;
    private static final int[][] RUNS = { {30, 3}, {30, 10}, {20, 0}, {34, 5} };

    /** At 1960ade: {dose kPa·s, net s, gross s} per run, in RUNS order. */
    private static final double[][] REF = {
        { 0x1.ab0fb32d33fb3p12, 0x1.5bcp8, 0x1.9fcp8 },
        { 0x1.afea81e03a6ep12, 0x1.5ecp8, 0x1.9fcp8 },
        { 0x1.a086110512a14p11, 0x1.56p8, 0x1.9fcp8 },
        { 0x1.01cf844144a82p13, 0x1.5d4p8, 0x1.9fcp8 },
    };
    /** At 1960ade: the gate's per-session net signal over those four runs (12 min planned). */
    private static final double REF_MILESTONE = 0x1.6cccccccccccdp2;

    /** {ts, kpa, noReading(0/1), inDrop(0/1)} for one recorded run. */
    static double[][] run(int pull, int drop, int cycles) {
        int n = (int) (cycles * (UH + LH) * 1000L / FRAME) + 1;
        double[][] r = new double[4][n];
        for (int i = 0; i < n; i++) {
            long t = i * FRAME;
            long into = t % ((UH + LH) * 1000L);
            double k;
            if (into < 3000) k = drop + (pull - drop) * into / 3000.0;
            else if (into < UH * 1000L) k = pull + 0.4 * Math.sin(t / 900.0) - 0.3;
            else if (into < UH * 1000L + 2000) k = pull - (pull - drop) * (into - UH * 1000L) / 2000.0;
            else k = Math.max(0.0, drop + 0.2 * Math.sin(t / 500.0));
            boolean nr = i % 97 == 50;
            r[0][i] = t; r[1][i] = nr ? 0.0 : k; r[2][i] = nr ? 1 : 0;
            r[3][i] = TupClock.inDrop(UH, LH, drop, pull, false, t) ? 1 : 0;
        }
        return r;
    }

    /** The run counted as the app now counts it: the dose told the phase where the app tells
     *  it (TupClock#doseLeavesOut), the net marked by the drop half. `everyDrop` marks the
     *  dose's drop half whatever the drop - what the app does NOT do, for the comparison. */
    static double[] counted(int pull, int drop, boolean everyDrop) {
        double[][] r = run(pull, drop, 6);
        Session s = new Session();
        s.beginRun(0L);
        for (int i = 0; i < r[0].length; i++) {
            long t = (long) r[0][i];
            boolean nr = r[2][i] != 0, inDrop = r[3][i] != 0;
            s.noteSample(t, r[1][i], nr, 3000L,
                         everyDrop ? inDrop : TupClock.doseLeavesOut(inDrop, drop));
            s.noteHoldFrame(t, r[1][i], nr, pull, Session.HOLD_PHASE_HOLD, 0, false, inDrop);
        }
        double[] tup = s.netGrossTupSec(Plan.L1_FLOOR_KPA);
        return new double[]{ s.deliveredDoseKpaS(), tup[0], tup[1] };
    }

    @Test
    void dropsAtOrUnderTheLineCountExactlyAsBefore() {
        for (int k = 0; k < RUNS.length; k++) {
            int pull = RUNS[k][0], drop = RUNS[k][1];
            assertFalse(TupClock.readsSetClock(drop),
                "a drop of " + drop + " kPa reads its half from the step's arming, as before");
            double[] c = counted(pull, drop, false);
            String at = pull + "/" + drop + " kPa";
            assertEquals(Double.doubleToLongBits(REF[k][0]), Double.doubleToLongBits(c[0]),
                at + ": dose " + c[0] + " against " + REF[k][0]);
            assertEquals(Double.doubleToLongBits(REF[k][1]), Double.doubleToLongBits(c[1]),
                at + ": net " + c[1] + " against " + REF[k][1]);
            assertEquals(Double.doubleToLongBits(REF[k][2]), Double.doubleToLongBits(c[2]),
                at + ": gross");
        }
    }

    @Test
    void theLevelGatesReadTheSameMinutes() {
        double[] nets = new double[RUNS.length];
        for (int k = 0; k < RUNS.length; k++) nets[k] = counted(RUNS[k][0], RUNS[k][1], false)[1] / 60.0;
        TrainerTab.NetSignals g = TrainerTab.netSignals(nets, 12.0);
        assertEquals(Double.doubleToLongBits(REF_MILESTONE), Double.doubleToLongBits(g.milestoneNet),
            "the per-session minutes the gate counts");
        // The rest of the signal as 1960ade gave it for these four runs against 12 min.
        assertTrue(g.hasEnough);
        assertTrue(g.underDelivery);
        assertFalse(g.ownTargetsMet);
        // ...and a recorded session filed with it reads the same minutes back.
        Model.Sess s = new Model.Sess();
        s.netTupSec = Double.valueOf(REF[0][1]);
        java.util.List<Model.Sess> log = new java.util.ArrayList<Model.Sess>();
        log.add(s);
        assertEquals(REF[0][1] / 60.0, TrainerTab.recentNetMin(log, 1)[0], 0.0);
    }

    @Test
    void theTrainersOwnDropIsUnderTheLine() {
        assertFalse(TupClock.doseLeavesOut(true, new Model().rxDropKpa));
        assertFalse(TupClock.doseLeavesOut(true, Mint.DROP_KPA));
        assertFalse(TupClock.doseLeavesOut(true, RunEdit.DROP_FLOOR_KPA));
        assertTrue(TupClock.doseLeavesOut(true, RunEdit.DROP_FLOOR_KPA + 1));
        assertFalse(TupClock.doseLeavesOut(false, 26), "the hold half is never left out");
    }

    /** Why the exclusion is held to drops above the line: applied to every drop, it would have
     *  taken the bleed-down and the hold-to-drop boundary out of these runs' dose. Net is the
     *  same either way - it has left the drop half out by phase since F14. */
    @Test
    void aPhaseExclusionOnEveryDropWouldHaveLoweredTheirDose() {
        for (int k = 0; k < RUNS.length; k++) {
            double[] every = counted(RUNS[k][0], RUNS[k][1], true);
            assertTrue(every[0] < REF[k][0] - 1.0, RUNS[k][0] + "/" + RUNS[k][1] + ": "
                + every[0] + " against " + REF[k][0]);
            assertEquals(REF[k][1], every[1], 0.0);
        }
    }
}
