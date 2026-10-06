package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M4 - THE TISSUE RESPONSE TEST IN WORDS (the owner's item 10) AND OVER TIME (item 12).
 *
 * The summary card was headed "Δτ +4%" with no word on whether a change that size meant
 * anything, although Tau's own maths says under about 4 % is what setup alone can produce.
 * These pin the words the screens now use: the test's name, the two fill times, the change,
 * and which side of normal variation it sits - never which way is better.
 */
class TauSayTest {

    private String unitBefore;
    @BeforeEach void kpa() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_KPA; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    private static Model.Sess pair(double before, double after) {
        Model.Sess s = new Model.Sess();
        s.assessKpa = 20; s.assessSp = 60; s.assessDurSec = 45;
        s.tauBeforeSec = Double.valueOf(before); s.tauAfterSec = Double.valueOf(after);
        s.tauBeforeWhy = Tau.OK; s.tauAfterWhy = Tau.OK;
        s.tauBeforeP0Kpa = Double.valueOf(0.0); s.tauAfterP0Kpa = Double.valueOf(0.0);
        s.tauBeforePeakKpa = Double.valueOf(20.0); s.tauAfterPeakKpa = Double.valueOf(20.0);
        return s;
    }

    /* ------------------------------------------------------------------ the name */

    @Test void theTestIsCalledWhatTheOwnerCalledIt() {
        assertEquals("Tissue response test", TauSay.NAME);
        assertEquals(TauSay.NAME, Summary.TAU_DETAIL_LABEL,
            "History's row carries the summary card's name - one name on both screens");
        assertEquals("Step 5 of 8  ·  Tissue response test",
            Nav.stepLine(Nav.steps(true, true, true, true), Nav.STEP_ASSESS_BEFORE));
    }

    /* ------------------------------------------------------------- normal variation */

    @Test void normalVariationIsTheFourPercentTauAlreadyStated() {
        assertEquals(4, Tau.NOISE_PCT);
        // The worst case a matched pair can carry from setup alone: the start tolerance's
        // ~3.8 %/kPa and the top's ~5.6 %/kPa (Tau's own measured figures), about 3.6 %.
        double worst = Tau.PAIR_MATCH_TOL_KPA * 3.8 + Tau.PAIR_TOP_TOL_KPA * 5.6;
        assertTrue(Tau.NOISE_PCT >= worst,
            "normal variation is rounded UP from what setup can produce (" + worst + ")");
    }

    @Test void theBandIsDecidedOnThePrintedPercent() {
        assertEquals(TauSay.BAND_WITHIN, TauSay.band(3.4));
        assertEquals(TauSay.BAND_EDGE, TauSay.band(3.6),
            "3.6 prints as +4 %, and a \"+4 %\" is never called under 4 %");
        assertEquals(TauSay.BAND_EDGE, TauSay.band(-5.4));
        assertEquals(TauSay.BAND_BEYOND, TauSay.band(5.6));
        assertEquals(TauSay.BAND_BEYOND, TauSay.band(-9));
        assertFalse(TauSay.atOrPastNoise(3.4));
        assertTrue(TauSay.atOrPastNoise(-3.6), "Progress's blue is \"4 % or more\" either way");
    }

    @Test void aPercentHasItsSignAndTheScreensSpace() {
        assertEquals("+4 %", TauSay.pct(4.48));
        assertEquals("−8 %", TauSay.pct(-7.6));
        assertEquals("0 %", TauSay.pct(0.3));
    }

    /* ------------------------------------------------------------- the summary card */

    @Test void theDrawnCaseReadsAsDrawn() {
        Model.Sess s = pair(6.7, 7.0);                  // +4.48 %
        assertEquals("Fill time 6.7 s → 7.0 s", TauSay.title(s));
        assertEquals("+4 %, about the size of normal variation. Repeat it before reading "
            + "anything into it.", TauSay.verdict(s), "the owner's approved drawing, verbatim");
    }

    @Test void aSmallChangeSaysWhatWithinNoiseMeans() {
        String v = TauSay.verdict(pair(6.6, 6.7));      // +1.5 %
        assertTrue(v.startsWith("+2 %, within normal variation."), v);
        assertTrue(v.indexOf("under 4 %") >= 0, "it names the size of normal variation: " + v);
        assertTrue(v.indexOf("doesn't show anything on its own") >= 0, v);
    }

    @Test void aLargeChangeIsCalledLargerAndNothingMore() {
        String v = TauSay.verdict(pair(1.36, 1.48));    // +8.8 %
        assertEquals("+9 %, more than the normal variation between two pulls (under 4 %).", v);
        String down = TauSay.verdict(pair(7.4, 6.9));   // -6.8 %
        assertTrue(down.startsWith("−7 %, more than"), down);
    }

    @Test void noSentenceSaysWhichWayIsBetter() {
        double[][] cases = { {6.6, 6.7}, {6.7, 7.0}, {1.36, 1.48}, {7.4, 6.9}, {5.0, 3.0} };
        for (int i = 0; i < cases.length; i++) {
            Model.Sess s = pair(cases[i][0], cases[i][1]);
            String all = (TauSay.title(s) + TauSay.verdict(s) + TauSay.tag(s)
                + TauSay.detail(s)).toLowerCase(Locale.US);
            for (String w : new String[]{ "better", "worse", "improv", "good", "bad" })
                assertTrue(all.indexOf(w) < 0, "\"" + w + "\" in: " + all);
        }
        assertTrue(TauSay.about(20, 60, 45).indexOf("doesn't say whether a longer or shorter")
            >= 0, "and the explanation says so in as many words");
    }

    @Test void aPairThatDidNotMatchKeepsItsPressures() {
        Model.Sess s = pair(6.6, 6.8);
        s.tauAfterP0Kpa = Double.valueOf(2.2);
        assertNull(Tau.sessionDeltaPct(s));
        assertEquals("Fill time 6.6 s → 6.8 s", TauSay.title(s));
        String v = TauSay.verdict(s);
        assertTrue(v.startsWith("No change is worked out"), v);
        assertTrue(v.indexOf("started 0.0 kPa → 2.2 kPa") >= 0, v);
        assertTrue(v.indexOf("reached 20.0 kPa → 20.0 kPa") >= 0, v);
    }

    @Test void oneEndSaysWhichAndWhyTheOtherHasNone() {
        Model.Sess after = pair(1, 6.7);
        after.tauBeforeSec = null; after.tauBeforeWhy = Tau.WHY_SKIPPED;
        assertEquals("Fill time 6.7 s after the routine", TauSay.title(after));
        assertEquals("Nothing to compare it with: the before-test was skipped.",
                     TauSay.verdict(after));
        Model.Sess before = pair(6.6, 1);
        before.tauAfterSec = null; before.tauAfterWhy = Tau.WHY_NO_START;
        assertEquals("Fill time 6.6 s before the routine", TauSay.title(before));
        assertTrue(TauSay.verdict(before).indexOf("the after-test couldn't start") >= 0);
        Model.Sess ranOut = pair(6.6, 1);
        ranOut.tauAfterSec = null; ranOut.tauAfterWhy = "";
        assertEquals("Nothing to compare it with: there's no after-test this session.",
                     TauSay.verdict(ranOut));
    }

    @Test void noNumberAtAllSaysWhatHappened() {
        Model.Sess skipped = pair(1, 1);
        skipped.tauBeforeSec = null; skipped.tauAfterSec = null;
        skipped.tauBeforeWhy = Tau.WHY_SKIPPED; skipped.tauAfterWhy = Tau.WHY_SKIPPED;
        assertEquals("Skipped this session", TauSay.title(skipped));
        assertTrue(TauSay.verdict(skipped).endsWith("never a made-up one."));
        Model.Sess refused = pair(1, 1);
        refused.tauBeforeSec = null; refused.tauAfterSec = null;
        refused.tauBeforeWhy = Tau.WHY_SKIPPED; refused.tauAfterWhy = Tau.WHY_STILL_CLIMBING;
        assertEquals("No fill time this session", TauSay.title(refused));
        assertEquals("The before-test was skipped. The after-test gave no fill time: the pull "
            + "was still rising when the test ended, so there was no top to time it against. A "
            + "longer test duration would help.", TauSay.verdict(refused));
    }

    @Test void aTestThatNeverRanSaysNothing() {
        Model.Sess none = new Model.Sess();
        assertFalse(TauSay.ran(none));
        assertEquals("", TauSay.title(none));
        assertEquals("", TauSay.verdict(none));
        assertEquals("", TauSay.tag(none));
        assertNull(TauSay.detail(none));
    }

    @Test void everyRefusalHasPlainWords() {
        String[] whys = { Tau.WHY_NO_READING, Tau.WHY_GAP, Tau.WHY_STILL_CLIMBING,
            Tau.WHY_TOO_FEW, Tau.WHY_LOW_PLATEAU, Tau.WHY_HOT_START, Tau.WHY_NO_START,
            Tau.WHY_SHORT_PULL, Tau.WHY_OVERSHOT, Tau.WHY_LINK_LOST, Tau.WHY_SKIPPED,
            Tau.WHY_NO_RISE };
        for (String w : whys) {
            String r = TauSay.reason(w);
            assertTrue(r.length() > 0 && !"not measured".equals(r), w);
            for (String jargon : new String[]{ "pressure of zero", "stimulus", "rise curve", "known start",
                                               "telemetry", "interpolate", "crossing", "τ" })
                assertTrue(r.indexOf(jargon) < 0, w + " says \"" + jargon + "\": " + r);
        }
    }

    /* ------------------------------------------------------------- History, Sessions */

    @Test void historyAndTheSessionsListSayTheSame() {
        Model.Sess s = pair(6.6, 6.7);
        assertEquals("6.6 s → 6.7 s  ·  +2 %, within normal variation", TauSay.detail(s));
        assertEquals("Fill time 6.6 s → 6.7 s  ·  +2 %", TauSay.tag(s));
        assertEquals(TauSay.detail(s), Summary.tauDetail(s));
        assertEquals(TauSay.tag(s), Summary.tauTag(s));
    }

    /* -------------------------------------------------------------- over time (12) */

    private static Model.Sess at(long ts, double b, double a, int sp) {
        Model.Sess s = pair(b, a);
        s.ts = ts; s.assessSp = sp;
        return s;
    }

    @Test void theTrendIsOldestFirstAndLeavesOutSessionsWithNoChange() {
        List<Model.Sess> log = new ArrayList<Model.Sess>();     // newest first, as stored
        log.add(at(5, 6.0, 6.6, 60));                           // +10 %
        Model.Sess skipped = new Model.Sess();
        skipped.ts = 4; skipped.assessDurSec = 45; skipped.tauBeforeWhy = Tau.WHY_SKIPPED;
        log.add(skipped);                                       // no change: left out
        Model.Sess off = new Model.Sess(); off.ts = 3;          // test off: left out
        log.add(off);
        log.add(at(2, 6.0, 5.94, 60));                          // -1 %
        List<TauSay.Point> t = TauSay.trend(log);
        assertEquals(2, t.size(), "a missing result is not a measured 0 %");
        assertEquals(2L, t.get(0).ts);
        assertEquals(-1.0, t.get(0).pct, 1e-9);
        assertEquals(5L, t.get(1).ts);
        assertFalse(t.get(1).breakBefore, "same test settings: joined");
    }

    @Test void theTrendBreaksWhereTheTestSettingsChanged() {
        List<Model.Sess> log = new ArrayList<Model.Sess>();
        log.add(at(3, 6.0, 6.1, 70));
        log.add(at(2, 6.0, 6.2, 60));
        log.add(at(1, 6.0, 6.3, 60));
        List<TauSay.Point> t = TauSay.trend(log);
        assertFalse(t.get(0).breakBefore);
        assertFalse(t.get(1).breakBefore);
        assertTrue(t.get(2).breakBefore, "the speed changed from 60 % to 70 %");
        String said = TauSay.trendSaid(t);
        assertTrue(said.indexOf("3 sessions") >= 0, said);
        assertTrue(said.indexOf("changed once") >= 0, said);
    }

    @Test void theTrendKeepsTheNewestSessions() {
        List<Model.Sess> log = new ArrayList<Model.Sess>();
        for (int i = 40; i >= 1; i--) log.add(at(i, 6.0, 6.1, 60));
        List<TauSay.Point> t = TauSay.trend(log);
        assertEquals(TauSay.TREND_MAX, t.size());
        assertEquals(40L, t.get(t.size() - 1).ts, "the newest is last");
        assertEquals(21L, t.get(0).ts);
    }
}
