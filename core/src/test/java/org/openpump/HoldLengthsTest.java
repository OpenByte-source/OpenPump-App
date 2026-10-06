package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openpump.T10Fix.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * R11-5 - HOLD LENGTHS (the owner's pick, option A): Trainer › What it writes › Hold lengths.
 * Each stage the guidance gives a range for gets a choice inside that range - the fatigue
 * block's holds 30 to 60 s (30 s by default, for everyone: the owner asked), the interval work
 * holds 1 to 3 min (the level's own 2 min by default) and the rest between blocks 3 to 5 min
 * (3 min by default). The plan keeps its minutes at pressure: a shorter hold runs more of
 * them, a longer one fewer, in whole holds.
 */
class HoldLengthsTest {

    @Test
    void theFatigueHoldsAre30SecondsForEveryoneByDefault() {
        assertEquals(30, new Model().rxFatigueHoldSec);
        assertEquals(Model.FATIGUE_HOLD_DEFAULT_SEC, new Model().rxFatigueHoldSec);
    }

    @Test
    void theFatigueBlockKeepsItsMinutesInWholeHolds() {
        // 10 x 45 s today: the same 7.5 minutes at 30 s is 15 holds, at 60 s 8 (7.5 rounded).
        assertEquals(15, Mint.fatigueHolds(10, 30));
        assertEquals(10, Mint.fatigueHolds(10, 45));
        assertEquals(8, Mint.fatigueHolds(10, 60));
        assertEquals(23, Mint.fatigueHolds(15, 30), "extended: 15 x 45 s, 22.5 rounded");
        assertEquals(1, Mint.fatigueHolds(1, 60), "never none");
    }

    @Test
    void theOwnersL3SessionRunsFifteenThirtySecondFatigueHolds() {
        Model m = owner();
        Model.Routine r = build(m, girth(Plan.L3, 14, 30), day(m));
        int fs = stage(r, "fatigue");
        List<Integer> holds = holdSecs(m, r, fs);
        assertEquals(15, holds.size());
        for (int i = 0; i < holds.size(); i++) assertEquals(30, holds.get(i).intValue());
        assertEquals(28.0, r.netTargetMin, 1e-9, "the counted work is unchanged");
        // 45 s chosen: the block as it was.
        Model m45 = owner();
        m45.rxFatigueHoldSec = 45;
        Model.Routine r45 = build(m45, girth(Plan.L3, 14, 30), day(m45));
        assertEquals(10, holdSecs(m45, r45, stage(r45, "fatigue")).size());
        assertEquals(45, holdSecs(m45, r45, stage(r45, "fatigue")).get(0).intValue());
    }

    @Test
    void shorterWorkHoldsRunMoreOfThemForTheSameMinutes() {
        Model m = owner();
        m.rxHoldSec = 90;
        Model.Routine r = build(m, girth(Plan.L3, 10, 30), day(m));
        List<Integer> ws = workStages(r);
        int holds = 0;
        for (int i = 0; i < ws.size(); i++) {
            List<Integer> h = holdSecs(m, r, ws.get(i).intValue());
            for (int k = 0; k < h.size(); k++) assertEquals(90, h.get(k).intValue());
            holds += h.size();
        }
        assertEquals(13, holds, "20 min at 1:30 holds is 13 whole holds (13.3)");
    }

    @Test
    void theRestBetweenBlocksIsThreeToFiveMinutes() {
        Model m = new Model();
        m.rxRestSec = 120;
        m.clampRxShape();
        assertEquals(180, m.rxRestSec, "under the guidance's 3 minutes: 3 minutes");
        m.rxRestSec = 400;
        m.clampRxShape();
        assertEquals(300, m.rxRestSec);
        m.rxFatigueHoldSec = 10;
        m.clampRxShape();
        assertEquals(30, m.rxFatigueHoldSec);
        m.rxFatigueHoldSec = 90;
        m.clampRxShape();
        assertEquals(60, m.rxFatigueHoldSec);
        Model o = owner();
        o.rxRestSec = 240;
        Model.Routine r = build(o, girth(Plan.L3, 14, 30), day(o));
        boolean four = false;
        for (int i = 0; i < r.stages.size(); i++)
            if (r.stages.get(i).rest && o.durationMs(singleStage(o, r, i)) == 240_000L) four = true;
        assertTrue(four, "the rests are the 4 minutes chosen");
    }

    private static Model.Routine singleStage(Model m, Model.Routine r, int i) {
        Model.Routine one = new Model.Routine();
        one.stages.add(r.stages.get(i));
        return one;
    }

    @Test
    void theChoicesAreTheGuidanceRanges() {
        assertEquals(3, Model.FATIGUE_HOLD_CHOICES.length);
        assertEquals(30, Model.FATIGUE_HOLD_CHOICES[0]);
        assertEquals(60, Model.FATIGUE_HOLD_CHOICES[Model.FATIGUE_HOLD_CHOICES.length - 1]);
        assertEquals(60, Model.WORK_HOLD_CHOICES[0]);
        assertEquals(180, Model.WORK_HOLD_CHOICES[Model.WORK_HOLD_CHOICES.length - 1]);
        assertEquals(180, Model.BLOCK_REST_CHOICES[0]);
        assertEquals(300, Model.BLOCK_REST_CHOICES[Model.BLOCK_REST_CHOICES.length - 1]);
    }

    @Test
    void theRowsSayWhatEachChoiceDoes() {
        assertEquals("15 holds · the block's minutes kept (the default)",
            PlanCards.fatigueHoldEffects()[0]);
        assertEquals("8 holds · the block's minutes kept", PlanCards.fatigueHoldEffects()[2]);
        assertEquals("20 holds where 2:00 runs 10 · the same minutes",
            PlanCards.workHoldEffects()[0]);
        assertEquals("13 holds where 2:00 runs 10 · the same minutes",
            PlanCards.workHoldEffects()[1]);
        assertEquals("The level's own (the default)", PlanCards.workHoldEffects()[2]);
        assertEquals("30 s", PlanCards.fatigueHoldLabels()[0]);
        assertEquals("1:30", PlanCards.workHoldLabels()[1]);
        assertEquals("4:30", PlanCards.blockRestLabels()[3]);
        assertEquals(2, PlanCards.holdIndex(Model.WORK_HOLD_CHOICES, 0), "0 is the level's 2:00");
        assertEquals(0, PlanCards.holdIndex(Model.BLOCK_REST_CHOICES, 180));
    }

    @Test
    void theRewriteSaysTheFatigueHoldsChanged() {
        Model m = owner();
        Mint.Rx rx = girth(Plan.L3, 10, 30);
        long now = System.currentTimeMillis();
        String sig = Mint.signature(rx, m.mintShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, now));
        String was = sig.replace(":F30", ":F45");
        assertEquals(Say.FATIGUE_HOLDS_HEAD, Say.shapeChange(was, sig));
        assertEquals("The fatigue block is now 15 holds of 30 s — the same minutes at "
            + "pressure. You can pick 30, 45 or 60 s in What it writes › Hold lengths.",
            Say.shapeChangeDetail(m, was, sig));
        assertEquals(null, Say.shapeChange(sig, sig), "nothing moved, nothing said");
    }

    @Test
    void anOldFileLoadsWithThirtySecondFatigueHoldsAndAThreeMinuteRest() throws Exception {
        Model m = new Model();
        org.json.JSONObject o = new org.json.JSONObject(m.toJson());
        o.remove("rxFatHold");
        o.put("rxRestSec", 120);
        Model back = Model.fromJson(o.toString());
        assertEquals(30, back.rxFatigueHoldSec);
        assertEquals(180, back.rxRestSec);
        Model mine = new Model();
        mine.rxFatigueHoldSec = 60;
        assertEquals(60, Model.fromJson(mine.toJson()).rxFatigueHoldSec, "a choice is kept");
    }

    @Test
    void theShapeTagSaysTheFatigueHoldAndAnOldTagRebuildsTheOldBlock() {
        Model m = owner();
        String tag = m.rxShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L3);
        assertTrue(tag.contains(":F30"), tag);
        assertTrue(!m.rxShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L2).contains(":F"),
            "no fatigue block under Level 3, so nothing to say");
        assertTrue(!m.rxShapeTag(Plan.TRACK_LENGTH, Plan.L3).contains(":F"));
    }
}
