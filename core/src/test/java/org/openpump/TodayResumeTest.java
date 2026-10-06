package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Today's resume card: a title, one line saying what is left, and the loaded run as one
 * short row. The words are built here, so what they must never do is repeat the numbers
 * the line under them already prints, claim a total that cannot contain what is left, or
 * describe the warm-up or the finisher as the work.
 */
class TodayResumeTest {

    @Test
    void aMintedNameLosesItsPrescriptionAndKeepsItsTrackAndLevel() {
        assertEquals("Trainer · Girth L1",
            Say.mintTitle("Trainer · Girth L1 · 10×2min @ −5.0 inHg"));
        // Older mints wrote the pressure unsigned; a hold can be fractional.
        assertEquals("Trainer · Girth L3",
            Say.mintTitle("Trainer · Girth L3 · 8×1.5min @ 5.0 inHg"));
    }

    @Test
    void theRestIsDroppedButAPartIsKept() {
        assertEquals("Trainer · Girth L1",
            Say.mintTitle("Trainer · Girth L1 · 10×2min @ −5.0 inHg"
                + "  ·  the rest"));
        assertEquals("Trainer · Girth L2 · part 1 of 2",
            Say.mintTitle("Trainer · Girth L2 · 6×2min @ −6.1 inHg"
                + "  ·  part 1 of 2"));
    }

    @Test
    void aTrackWrittenWithADotIsOnePartAndATractionNameIsKept() {
        assertEquals("Trainer · Girth·trad L2",
            Say.mintTitle("Trainer · Girth·trad L2 · 5×3min @ −6.0 inHg"));
        String traction = "Trainer · Length L2 · traction @ 30 lb";
        assertEquals(traction, Say.mintTitle(traction));
    }

    @Test
    void aNameThePersonChoseIsNeverTrimmed() {
        assertEquals("Evening · slow · 10×2min @ −5.0 inHg",
            Say.mintTitle("Evening · slow · 10×2min @ −5.0 inHg"));
        assertEquals("My routine", Say.mintTitle("My routine"));
        assertEquals("", Say.mintTitle(null));
    }

    @Test
    void whatIsLeftIsSaidInPlainTerms() {
        assertEquals("5 of 10 cycles left · 2 min each at −5.0 inHg",
            Say.leftToRun(5, 10, 120, "−5.0 inHg"));
        assertEquals("1 of 10 cycles left · 1.5 min each at −20.0 kPa",
            Say.leftToRun(1, 10, 90, "−20.0 kPa"));
        assertEquals("3 of 8 cycles left · 45 s each",
            Say.leftToRun(3, 8, 45, null));
    }

    @Test
    void aTotalThatCannotHoldWhatIsLeftIsNotPrinted() {
        assertEquals("12 cycles left · 2 min each at −5.0 inHg",
            Say.leftToRun(12, 10, 120, "−5.0 inHg"));
        assertEquals("1 cycle left", Say.leftToRun(1, 0, 0, ""));
        assertEquals("4 cycles left · at −5.0 inHg",
            Say.leftToRun(4, 0, 0, "−5.0 inHg"));
    }

    @Test
    void theWorkIsTheIntervalBlockNotTheWarmUpTheFinisherOrOneLongHold() {
        Model m = new Model();
        m.ceilKpa = 40;
        Model.Set warm = Model.Set.fixed("w", "Warm-up", 10, 5, 30, 10, 75, 160);
        Model.Set fat = Model.Set.fixed("f", "Fatigue hold", 17, 10, 45, 15, 75, 600);
        Model.Set trad = Model.Set.fixed("t", "Traditional hold", 17, 10, 600, 15, 75, 615);
        Model.Set work = Model.Set.fixed("k", "Work", 17, 10, 120, 15, 75, 1350);
        Model.Set hold = Model.Set.fixed("c", "Retention", 12, 12, 300, 0, 75, 300);
        m.sets.add(warm); m.sets.add(fat); m.sets.add(trad); m.sets.add(work); m.sets.add(hold);

        Model.Routine r = new Model.Routine();
        r.stages.add(Model.Stage.of("Warm-up", Model.STAGE_WARM, new String[]{ "w" }));
        Model.Stage fs = Model.Stage.of("Fatigue block", Model.STAGE_WORK, new String[]{ "f" });
        fs.fatigueBlock = true;
        r.stages.add(fs);
        r.stages.add(Model.Stage.restOf("Rest", 180));
        r.stages.add(Model.Stage.of("Traditional hold", Model.STAGE_WORK, new String[]{ "t" }));
        r.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ "k" }));
        r.stages.add(Model.Stage.of("Retention", Model.STAGE_COOL, new String[]{ "c" }));

        Model.Set main = m.mainWorkSet(r);
        assertSame(work, main, "the ten-cycle interval block is the work");
        assertEquals(120, main.uh);
        assertEquals(17, main.up);
    }

    @Test
    void aSetListedManyTimesCountsEveryTime() {
        Model m = new Model();
        m.ceilKpa = 40;
        Model.Set once = Model.Set.fixed("t", "Long hold", 17, 10, 600, 15, 75, 615);
        Model.Set tenTimes = Model.Set.fixed("k", "Interval", 17, 10, 120, 15, 75, 135);
        m.sets.add(once); m.sets.add(tenTimes);
        Model.Routine r = new Model.Routine();
        String[] ids = new String[11];
        ids[0] = "t";
        for (int i = 1; i < ids.length; i++) ids[i] = "k";
        r.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, ids));
        assertSame(tenTimes, m.mainWorkSet(r));
    }

    @Test
    void aRoutineWithNoWorkHasNoMainSet() {
        Model m = new Model();
        Model.Routine r = new Model.Routine();
        r.stages.add(Model.Stage.restOf("Rest", 60));
        assertNull(m.mainWorkSet(r));
        assertNull(m.mainWorkSet(null));
    }
}
