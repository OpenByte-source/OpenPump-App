package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Library's routine tiles: a label agrees with the number over it, and a routine that has
 * never run says so in words.
 */
class LibraryTilesTest {

    @Test
    void oneStageIsSingular() {
        assertEquals("Stage", Say.countLabel(1, "Stage", "Stages"));
        assertEquals("Stages", Say.countLabel(2, "Stage", "Stages"));
        assertEquals("Stages", Say.countLabel(0, "Stage", "Stages"));
        assertEquals("Run", Say.countLabel(1, "Run", "Runs"));
    }

    @Test
    void aRoutineNeverRunSaysSo() {
        assertEquals("None yet", Say.runsFigure(0));
        assertEquals("None yet", Say.runsFigure(-1));
        assertEquals("1", Say.runsFigure(1));
        assertEquals("12", Say.runsFigure(12));
    }

    @Test
    void aPressureSplitsIntoItsNumberAndItsUnit() {
        // The tile sets the unit BESIDE the number, so the split has to be clean.
        assertEquals("−6.5", Say.valueWordOf("−6.5 inHg"));
        assertEquals("inHg", Say.unitWordOf("−6.5 inHg"));
        assertEquals("11:30", Say.valueWordOf("11:30"));
        assertEquals("", Say.unitWordOf("11:30"));
    }
}
