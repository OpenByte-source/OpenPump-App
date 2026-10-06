package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * C8 - τ IN HISTORY SAYS WHAT THE SUMMARY SAYS (study problem 8, the parts that were wrong).
 *
 *   - The session detail sheet printed "Tissue response 0:07 → 0:07": τ as clock time,
 *     rounded to whole seconds (6.6 s and 6.8 s both "0:07"), no Δτ, and a name the app's
 *     own caption denies ("a relative index of fill rate ... not a tissue measurement").
 *     {@link Summary#tauDetail} is the row's value now, in the summary's own units and terms.
 *   - A deliberate skip of the test read "τ not measurable" in History - a claim that it
 *     was tried and failed. {@link Summary#tauTag} says "τ skipped".
 *   - The chart note hard-coded "45 s" whatever duration the routine's test is set to.
 *     {@link Summary#tauChartNote} states the session's own duration.
 *
 * M4 (the owner's items 10 and 11): the words are TauSay's now - the test is the "Tissue
 * response test", τ is its "fill time", and a change says whether it is within normal
 * variation. What each case below protects is unchanged: one decimal, the summary's own
 * change or "not compared", an end that produced no number stated rather than hidden, a skip
 * that reads as a skip, and one name on both screens.
 */
class TauHistoryTest {

    private static Model.Sess assessed() {
        Model.Sess s = new Model.Sess();
        s.assessKpa = 20; s.assessSp = 60; s.assessDurSec = 45;
        return s;
    }

    private static Model.Sess pair(double before, double after, double p0After) {
        Model.Sess s = assessed();
        s.tauBeforeSec = Double.valueOf(before); s.tauAfterSec = Double.valueOf(after);
        s.tauBeforeWhy = Tau.OK; s.tauAfterWhy = Tau.OK;
        s.tauBeforeP0Kpa = Double.valueOf(2.0); s.tauAfterP0Kpa = Double.valueOf(p0After);
        s.tauBeforePeakKpa = Double.valueOf(20.0); s.tauAfterPeakKpa = Double.valueOf(20.0);
        return s;
    }

    /* ------------------------------------------------------------ the detail row */

    @Test void theDetailRowIsSecondsWithOneDecimalAndTheDelta() {
        Model.Sess s = pair(6.6, 6.8, 2.0);
        String v = Summary.tauDetail(s);
        double d = Tau.sessionDeltaPct(s).doubleValue();
        assertEquals("6.6 s → 6.8 s  ·  " + TauSay.pct(d) + ", " + TauSay.changeWords(d), v,
            "the summary's own formatting - 6.6 and 6.8 were both \"0:07\" before");
        assertEquals("6.6 s → 6.8 s  ·  +3 %, within normal variation", v);
        assertTrue(v.indexOf("0:07") < 0);
    }

    @Test void aPairThatIsNotComparableSaysSoRatherThanADelta() {
        assertEquals("6.6 s → 6.8 s  ·  not compared", Summary.tauDetail(pair(6.6, 6.8, 4.2)),
            "pulls that started 2.2 kPa apart have no change, as on the summary");
    }

    @Test void oneEndOrNoneIsStatedNotHidden() {
        Model.Sess after = assessed();
        after.tauAfterSec = Double.valueOf(6.7); after.tauAfterWhy = Tau.OK;
        assertEquals("after 6.7 s  ·  nothing to compare with", Summary.tauDetail(after));
        Model.Sess before = assessed();
        before.tauBeforeSec = Double.valueOf(6.6); before.tauBeforeWhy = Tau.OK;
        assertEquals("before 6.6 s  ·  nothing to compare with", Summary.tauDetail(before));
        Model.Sess skipped = assessed();
        skipped.tauBeforeWhy = Tau.WHY_SKIPPED; skipped.tauAfterWhy = Tau.WHY_SKIPPED;
        assertEquals("skipped", Summary.tauDetail(skipped));
        Model.Sess refused = assessed();
        refused.tauBeforeWhy = Tau.WHY_SKIPPED; refused.tauAfterWhy = Tau.WHY_NO_READING;
        assertEquals("no fill time", Summary.tauDetail(refused));
        assertNull(Summary.tauDetail(assessed()), "an assessment that never ran has no row");
    }

    @Test void theRowIsNamedAsTheSummaryNamesIt() {
        // M4: C8 pinned the symbol "τ" and forbade "tissue", because the summary called the
        // test by that symbol and its caption said "not a tissue measurement". The owner has
        // since named the test the "Tissue response test"; the summary card is headed with
        // that name, and the rule kept is C8's own - History's row carries the same name.
        assertEquals(TauSay.NAME, Summary.TAU_DETAIL_LABEL, "the summary card's own name");
        assertEquals("Tissue response test", Summary.TAU_DETAIL_LABEL);
    }

    /* ------------------------------------------------------------ a skip is a skip */

    @Test void aDeliberateSkipOfBothEndsReadsSkipped() {
        Model.Sess s = assessed();
        s.tauBeforeWhy = Tau.WHY_SKIPPED; s.tauAfterWhy = Tau.WHY_SKIPPED;
        assertEquals("Tissue response test skipped", Summary.tauTag(s),
            "skipping the test is not the test failing to measure");
    }

    @Test void aSkipWhereTheOtherEndNeverRanReadsSkipped() {
        Model.Sess s = assessed();
        s.tauBeforeWhy = Tau.WHY_SKIPPED;
        assertEquals("Tissue response test skipped", Summary.tauTag(s));
    }

    @Test void aRealRefusalStillReadsNotMeasurable() {
        Model.Sess s = assessed();
        s.tauBeforeWhy = Tau.WHY_SKIPPED; s.tauAfterWhy = Tau.WHY_NO_READING;
        assertEquals("Tissue response test: no fill time", Summary.tauTag(s),
            "one end skipped and the other refused: the refusal is the true headline");
    }

    /* ------------------------------------------------------------ the chart note */

    @Test void theChartNoteStatesTheSessionsOwnDuration() {
        assertTrue(Summary.tauChartNote(true, 60).indexOf("60 s") >= 0,
            "a 60 s test says 60 s (got: " + Summary.tauChartNote(true, 60) + ")");
        assertTrue(Summary.tauChartNote(false, 30).indexOf("30 s") >= 0);
        assertTrue(Summary.tauChartNote(true, 60).indexOf("45 s") < 0);
        assertTrue(Summary.tauChartNote(true, 0).indexOf(" s window") < 0,
            "a session filed without its duration names none rather than a made-up one");
    }
}
