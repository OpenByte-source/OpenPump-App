package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SKIPPING A BLOCK IN COMING STEPS (device check EMU9 H1): the sheet is redrawn after every
 * skip, and a skipped card is still drawn - greyed, in its place - with the figures it had.
 * Its presets are out of the plan by then, so a card that read them from the plan found no
 * block (ComingSteps#blockOf, -1) and the run screen crashed on that index with the pump
 * running. Each card must read the presets it really has (ComingSteps#cardPresets), and the
 * list and the run order must stay the same through a skip, an Undo and a put-back - in the
 * warm-up, a set range, and during a rest.
 */
class ComingSkipCardsTest {

    /* ------------------------------------------------------------- the routine */

    private static Model.Preset work(int stage, int pos, String setId, int up, int uh, int lh,
                                     long durMs, String label) {
        Model.Preset p = new Model.Preset();
        p.up = up; p.lo = 3; p.uh = uh; p.lh = lh; p.sp = 60;
        p.durMs = durMs;
        p.stageIdx = stage; p.pos = pos; p.setId = setId;
        p.label = label;
        return p;
    }

    private static Model.Preset block(int stage, int pos, String setId, int sets, String label) {
        return work(stage, pos, setId, 20, 60, 5, sets * 65_000L, label);
    }

    private static Model.Preset rest(int stage, int sec) {
        Model.Preset p = new Model.Preset();
        p.rest = true;
        p.durMs = sec * 1000L;
        p.stageIdx = stage;
        p.label = "Rest";
        return p;
    }

    /** Warm-up (3 climbing steps) · Fatigue hold (a 3-step climb, then 5 sets) · Rest 3:00 ·
     *  Work (sets 1–5, then sets 6–10) - the shape of the routine on the device. */
    private static List<Model.Preset> plan() {
        List<Model.Preset> p = new ArrayList<Model.Preset>();
        for (int k = 0; k < 3; k++) p.add(work(0, 0, "warm", 10 + 3 * k, 20, 5, 75_000L, "Warm-up " + (k + 1) + "/3"));
        for (int k = 0; k < 3; k++) p.add(work(1, 0, "climb", 12 + 3 * k, 30, 5, 70_000L, "Fatigue hold · climb " + (k + 1) + "/3"));
        p.add(block(1, 1, "fat", 5, "Fatigue hold"));
        p.add(rest(2, 180));
        p.add(block(3, 0, "w1", 5, "Work"));
        p.add(block(3, 1, "w2", 5, "Work"));
        return p;
    }

    /* ------------------------------------------------------------- the checks */

    /** Every card the sheet draws, from the step playing on, reads a block where its shape says
     *  it is one - the line fillComingSteps / comingSub crashed on - and the cards come in run
     *  order, skipped ones in their places. */
    private static void cardsConsistent(List<Model.Preset> plan, int planIdx,
                                        Map<Integer, List<Model.Preset>> skipped,
                                        List<Integer> expectOrder) {
        List<Integer> cards = ComingSteps.blocksFrom(plan, planIdx, skipped.keySet());
        assertEquals(expectOrder, cards, "the cards, in run order, skipped ones in place");
        for (int i = 0; i < cards.size(); i++) {
            int key = cards.get(i).intValue();
            List<Model.Preset> src = ComingSteps.cardPresets(plan, skipped, key);
            // The shape the card is drawn with: a skipped one keeps the figures it had
            // (RunScreen: comingOrig, or the presets taken out) - a block stays a block.
            ComingSteps.Shape sh = ComingSteps.shapeOf(skipped.containsKey(cards.get(i))
                ? skipped.get(cards.get(i)) : plan, key, false);
            if (sh.kind == ComingSteps.SHAPE_BLOCK) {
                int bi = ComingSteps.blockOf(src, key);
                assertTrue(bi >= 0 && bi < src.size(), "card " + Integer.toHexString(key)
                    + " (" + (skipped.containsKey(cards.get(i)) ? "skipped" : "to run")
                    + ") finds its block");
                assertTrue(ComingSteps.inBlock(src.get(bi), key));
            }
            if (skipped.containsKey(cards.get(i)))
                assertSame(skipped.get(cards.get(i)), src, "a skipped card reads what was taken out");
        }
        // The plan never runs a block out of order from the step playing on.
        for (int i = Math.max(1, planIdx + 1); i < plan.size(); i++) {
            int a = ComingSteps.key(plan.get(i - 1)), b = ComingSteps.key(plan.get(i));
            assertTrue(a == b || ComingSteps.after(b, a), "run order at " + i);
        }
    }

    private static List<Integer> keysFrom(List<Model.Preset> plan, int planIdx) {
        return ComingSteps.blocksFrom(plan, planIdx, null);
    }

    /** Coming steps' skip, as SessionActivity#comingSkip does it. */
    private static void skip(List<Model.Preset> plan, int planIdx,
                             Map<Integer, List<Model.Preset>> skipped, int key) {
        assertNull(ComingSteps.skipRefusal(stages(), plan, key, planIdx));
        skipped.put(Integer.valueOf(key), ComingSteps.removeStage(plan, key, planIdx));
    }

    /** ...and its put-back. */
    private static void putBack(List<Model.Preset> plan, int planIdx,
                                Map<Integer, List<Model.Preset>> skipped, int key) {
        List<Model.Preset> back = skipped.remove(Integer.valueOf(key));
        plan.addAll(ComingSteps.reinsertAt(plan, key, planIdx), back);
    }

    private static List<Model.Stage> stages() {
        List<Model.Stage> s = new ArrayList<Model.Stage>();
        s.add(Model.Stage.of("Warm-up", Model.STAGE_WARM, new String[0]));
        s.add(Model.Stage.of("Fatigue hold", Model.STAGE_WORK, new String[0]));
        s.add(Model.Stage.restOf("Rest", 180));
        s.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[0]));
        return s;
    }

    /* ------------------------------------------------------------- the cases */

    /** crash2: during the warm-up, the climb skipped, then the fatigue block. */
    @Test void warmUpSkipsTheClimbThenTheBlock() {
        List<Model.Preset> plan = plan();
        List<Model.Preset> before = new ArrayList<Model.Preset>(plan);
        Map<Integer, List<Model.Preset>> skipped = new HashMap<Integer, List<Model.Preset>>();
        int planIdx = 1;
        List<Integer> order = keysFrom(plan, planIdx);
        skip(plan, planIdx, skipped, ComingSteps.key(1, 0));
        cardsConsistent(plan, planIdx, skipped, order);
        skip(plan, planIdx, skipped, ComingSteps.key(1, 1));
        cardsConsistent(plan, planIdx, skipped, order);
        putBack(plan, planIdx, skipped, ComingSteps.key(1, 1));
        cardsConsistent(plan, planIdx, skipped, order);
        putBack(plan, planIdx, skipped, ComingSteps.key(1, 0));
        cardsConsistent(plan, planIdx, skipped, order);
        assertEquals(before, plan, "put back exactly as it was");
    }

    /** crash1: Skip warm-up, its Undo, then the fatigue block skipped in Coming steps. */
    @Test void skipWarmUpUndoThenSkipTheBlock() {
        List<Model.Preset> plan = plan();
        int planIdx = 0;
        // Skip warm-up (SessionActivity#skipRestOf(true)): the rest of the stage taken out,
        // the step playing ended, the next one starts.
        Model.Preset cur = plan.get(planIdx);
        List<Model.Preset> taken = new ArrayList<Model.Preset>();
        while (planIdx + 1 < plan.size() && plan.get(planIdx + 1).stageIdx == cur.stageIdx)
            taken.add(plan.remove(planIdx + 1));
        planIdx++;
        Model.Preset playing = plan.get(planIdx);
        // Undo (SessionActivity#undoSkip): put back in front of the step playing, which is
        // then skipped through the ordinary road and runs again whole after them.
        List<Model.Preset> back = RunEdit.undoSkipSteps(cur, 30_000L, taken, playing);
        plan.addAll(planIdx + 1, back);
        planIdx++;
        assertEquals("Warm-up 1/3", plan.get(planIdx).label, "the warm-up runs on from where it was cut");
        assertEquals("Warm-up 2/3", plan.get(planIdx + 1).label);
        assertEquals("Warm-up 3/3", plan.get(planIdx + 2).label);
        assertEquals(playing.label, plan.get(planIdx + 3).label, "then the step that had started, whole");

        Map<Integer, List<Model.Preset>> skipped = new HashMap<Integer, List<Model.Preset>>();
        List<Integer> order = keysFrom(plan, planIdx);
        skip(plan, planIdx, skipped, ComingSteps.key(1, 1));
        cardsConsistent(plan, planIdx, skipped, order);
        putBack(plan, planIdx, skipped, ComingSteps.key(1, 1));
        cardsConsistent(plan, planIdx, skipped, order);
    }

    /** crash3: during the first rest, one tap on Skip for "Sets 1–5". */
    @Test void duringTheRestSkipASetRange() {
        List<Model.Preset> plan = plan();
        List<Model.Preset> before = new ArrayList<Model.Preset>(plan);
        Map<Integer, List<Model.Preset>> skipped = new HashMap<Integer, List<Model.Preset>>();
        int planIdx = 7;
        assertTrue(plan.get(planIdx).rest);
        List<Integer> order = keysFrom(plan, planIdx);
        skip(plan, planIdx, skipped, ComingSteps.key(3, 0));
        cardsConsistent(plan, planIdx, skipped, order);
        // The rest's own line now leads to the sets still to run.
        assertEquals(ComingSteps.key(3, 1), ComingSteps.key(plan.get(planIdx + 1)));
        skip(plan, planIdx, skipped, ComingSteps.key(3, 1));
        cardsConsistent(plan, planIdx, skipped, order);
        assertEquals(planIdx + 1, plan.size(), "nothing after the rest runs");
        putBack(plan, planIdx, skipped, ComingSteps.key(3, 0));
        putBack(plan, planIdx, skipped, ComingSteps.key(3, 1));
        cardsConsistent(plan, planIdx, skipped, order);
        assertEquals(before, plan, "put back exactly as it was");
    }
}
