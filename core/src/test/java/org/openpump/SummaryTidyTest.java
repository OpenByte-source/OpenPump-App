package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * M4 - THE SUMMARY'S FILED LINE AND THE SESSIONS LIST SHOW A TAG IN NEUTRAL WORDS (the
 * owner's item 17, with the equal-methods rule). The stored tag is data and never changes;
 * only "assessed — not like-for-like", which ranked a pair taken two ways as a failure, is
 * shown as what it is. The Activity half (no file path on the summary, no "Measure now" on a
 * summary reopened from History) is WiringCheck invariant 68.
 */
class SummaryTidyTest {

    @Test void aPairTakenTwoWaysIsShownAsThat() {
        assertEquals("measured two ways", Summary.tagShown(Summary.TAG_NOT_LIKE));
        assertEquals("SIMULATED  ·  measured two ways",
            Summary.tagShown("SIMULATED  ·  " + Summary.TAG_NOT_LIKE),
            "a simulated run keeps its mark");
    }

    @Test void everyOtherTagIsShownAsFiled() {
        assertEquals(Summary.TAG_STOPPED, Summary.tagShown(Summary.TAG_STOPPED));
        assertEquals(Summary.TAG_NO_AFTER, Summary.tagShown(Summary.TAG_NO_AFTER));
        assertEquals("Δ length +0.40 cm", Summary.tagShown("Δ length +0.40 cm"));
        assertEquals("", Summary.tagShown(null));
    }
}
