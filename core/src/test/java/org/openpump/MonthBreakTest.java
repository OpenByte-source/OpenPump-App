package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * t10-K - THE MONTH-12 BREAK AND THE WAY BACK (the owner's decisions of 2 Oct 2026, BREAK-SPEC
 * B1-B5). The owner a year in: girth interval Level 3 at 37 kPa, length Level 3 at 14.7 lb in
 * the 4.5 cm length cylinder, girth Monday / Wednesday / Friday and length Tuesday / Thursday /
 * Saturday from Monday 5 October 2026, a week off in the fifth week. The break is taken on the
 * Monday of the seventh week, after that morning's girth session.
 */
class MonthBreakTest {

    static final long HOUR = LengthYear.HOUR, DAY = LengthYear.DAY;

    static long day(int d, int hour) { return LengthYear.t0() + d * DAY + hour * HOUR; }

    /** The Monday the break is taken (week 7), after its girth session. */
    static final int TAP_DAY = 42;

    static Model owner() {
        LengthYear y = new LengthYear();
        y.months = 11;                     // month 12 by the seventh week
        y.mostKpa = 0;                     // no "Most" for length: the climb is the ceiling's
        y.startLoadLb = 14.7;
        y.lengthLoadMode = Model.LENGTH_LOAD_AFTER12;
        y.setup();
        Model m = y.m;
        m.rxWorkMaxKpa = 38;               // "Most" for girth: the owner's 37 is in reach
        Model.TrainerTrackState g = m.trainerGirth;
        g.level = Plan.L3;
        g.weekIndex = 20; g.weekBaseIndex = 20; g.weekBaseMs = day(0, 7);
        g.setWorkingPressure(37, day(0, 7));
        g.carriedSets = 12;
        g.yieldSets = 2;
        m.trainerFirstDeloadTaken = true;
        Deload.remember(m, day(28, 0), day(35, 0));     // the fifth week off
        for (int w = 0; w < 6; w++) {
            if (w == 4) continue;
            for (int d = 0; d <= 4; d += 2) girth(m, day(7 * w + d, 9));
            for (int d = 1; d <= 5; d += 2) length(m, day(7 * w + d, 9), Double.NaN);
        }
        girth(m, day(TAP_DAY, 9));                       // the tap's own morning
        return m;
    }

    static void girth(Model m, long ts) { girth(m, ts, 40.0); }

    static void girth(Model m, long ts, double targetMin) {
        Model.Sess s = new Model.Sess();
        s.id = "g" + ts;
        s.routineId = "PG";
        s.ts = ts;
        s.durSec = 45 * 60;
        s.completed = true;
        s.netTupSec = 40.0 * 60.0;
        s.netTargetMin = targetMin;
        s.peakKpa = 37.0;
        m.sessLog.file(s);
    }

    static void length(Model m, long ts, double pct) {
        if (!Double.isNaN(pct)) { LengthYear.measured(m, ts, "PL", pct); return; }
        Model.Sess s = new Model.Sess();
        s.id = "l" + ts;
        s.routineId = "PL";
        s.ts = ts;
        s.durSec = 45 * 60;
        s.completed = true;
        m.sessLog.file(s);
    }

    /** The girth inputs as the app assembles them (SessionActivity#buildTrackInputs). */
    static Plan.Inputs girthIn(Model m, long now) {
        MonthBreak.settle(m, now);                       // settleTaper, as every draw does
        int track = Plan.TRACK_GIRTH_INTERVAL;
        Model.TrainerTrackState st = m.trainerGirth;
        int month = TrainerTab.monthIndexNow(m, now);
        Plan.Inputs in = new Plan.Inputs();
        in.track = track; in.level = st.level; in.monthIndex = month;
        in.pressureKpa = st.pressureKpa; in.ceilKpa = m.ceilKpa;
        in.layoff = TrainerTab.layoff(m, track, now);
        in.inDeloadWeek = TrainerTab.inDeloadWeek(m, now);
        in.accumulatedTrainingWeeks = TrainerTab.planTrainingWeeks(m,
            TrainerTab.deloadAnchorMs(m), now);
        in.firstDeloadPending = !m.trainerFirstDeloadTaken;
        in.redFlag = m.trainerState == Model.TRAINER_STATE_SAFETY_FLAG;
        in.returnRunsUnder = TrainerTab.returnRunsUnder(m, now);
        TrainerTab.fillGirthInputs(m, track, st, month, now, in);
        return in;
    }

    static Plan.Decision girthAt(Model m, long now) { return Plan.evaluate(girthIn(m, now)); }

    static Plan.Inputs lengthIn(Model m, long now) {
        MonthBreak.settle(m, now);
        return LengthYear.inputsFor(m, now);
    }

    static Plan.Decision lengthAt(Model m, long now) { return Plan.evaluate(lengthIn(m, now)); }

    /* =========================== B1 - one break, both tracks =========================== */

    @Test void theBreakRestsBothTracksFromTomorrowForFourWeeks() {
        Model m = owner();
        long tap = day(TAP_DAY, 10);
        assertEquals(Plan.ACTION_LEVEL_UP, girthAt(m, tap).action, "the month-12 card is up");
        MonthBreak.take(m, tap);
        // Today's session counts: nothing rests yet.
        assertTrue(MonthBreak.pending(m, tap));
        assertFalse(MonthBreak.on(m, tap));
        assertTrue(TrainerTab.lengthLive(m, day(TAP_DAY, 18)), "length still runs today");
        assertFalse(TrainerTab.girthPausedNow(m, tap));
        // Dated: from tomorrow's midnight, four weeks, the same return day for both tracks.
        assertEquals(Deload.dayStartPlus(tap, 1), m.breakFromMs);
        assertEquals(Deload.dayStartPlus(tap, 1 + 28), MonthBreak.returnMs(m));
        long tomorrow = day(TAP_DAY + 1, 8);
        assertTrue(MonthBreak.on(m, tomorrow));
        assertFalse(TrainerTab.lengthLive(m, tomorrow), "length rests");
        assertTrue(TrainerTab.girthPausedNow(m, tomorrow), "girth rests");
        assertEquals(UpNext.NOTHING, TrainerTab.upNextNow(m, tomorrow, false).what,
            "Up next offers no plan session");
        Plan.Decision g = girthAt(m, tomorrow), l = lengthAt(m, tomorrow);
        assertEquals(Plan.ACTION_HOLD, g.action);
        assertEquals(MonthBreak.BREAK_RULE, g.rule);
        assertEquals(Plan.ACTION_HOLD, l.action);
        assertEquals(MonthBreak.BREAK_RULE, l.rule);
        assertEquals(MonthBreak.BREAK_RULE,
            Plan.evaluate(TrainerTab.feederInputs(m, tomorrow)).rule, "the feeders rest too");
        long back = MonthBreak.returnMs(m);
        assertTrue(MonthBreak.on(m, back - 1L), "right up to the return day");
        assertFalse(MonthBreak.on(m, back), "and the date ends it - no tap needed");
        assertTrue(TrainerTab.lengthLive(m, back + HOUR));
        assertFalse(TrainerTab.girthPausedNow(m, back + HOUR));
        // From either card it is the same break: one label, both tracks named.
        assertTrue(MonthBreak.ARM_LABEL.contains("both tracks"), MonthBreak.ARM_LABEL);
        assertFalse(MonthBreak.armOffered(m), "and it is not offered again");
    }

    @Test void theBreakIsNotALayoffAMissedWeekOrADeloadCount() {
        Model m = owner();
        long tap = day(TAP_DAY, 10);
        MonthBreak.take(m, tap);
        MonthBreak.settle(m, day(TAP_DAY + 1, 8));
        long back = MonthBreak.returnMs(m) + 2 * HOUR;
        assertFalse(Deload.layoff(m, back), "four weeks away the plan gave is not a layoff");
        assertEquals(0L, Deload.askGapStartMs(m, back), "nor asked about as a deload");
        assertNotEquals(Plan.ACTION_STEP_BACK, girthAt(m, back).action);
        assertNotEquals(Plan.ACTION_STEP_BACK, lengthAt(m, back).action);
        for (long w = TrainerTab.mondayStartMs(tap) + 7 * DAY; w < MonthBreak.returnMs(m);
             w += 7 * DAY)
            assertTrue(MonthBreak.touchesWeek(m, w, w + 7 * DAY), "no week of it is missed");
        // The deload cadence counts again from the RETURN DAY (B1; the coordinator's ruling of
        // 2 Oct, round 3 follow-up) - the gentle week is a training week for it. Girth's own
        // clocks still restart at the gentle week's end (#clocks).
        assertEquals(MonthBreak.returnMs(m), TrainerTab.deloadAnchorMs(m));
        assertFalse(TrainerTab.cadenceDeloadDue(m, back));
        // A real week away AFTER it is still a layoff.
        assertTrue(Deload.layoff(m, MonthBreak.returnMs(m) + 8 * DAY));
    }

    @Test void comeBackNowEndsItAtTheTapAndBeforeItStartsItIsNotTaken() {
        Model m = owner();
        long tap = day(TAP_DAY, 10);
        MonthBreak.take(m, tap);
        MonthBreak.comeBackNow(m, day(TAP_DAY, 20));
        assertFalse(MonthBreak.taken(m), "changed its mind the same day: no break");
        assertTrue(MonthBreak.armOffered(m));
        MonthBreak.take(m, tap);
        MonthBreak.settle(m, day(TAP_DAY + 1, 8));
        long early = day(TAP_DAY + 12, 15);
        MonthBreak.comeBackNow(m, early);
        assertEquals(early, MonthBreak.returnMs(m));
        assertFalse(MonthBreak.on(m, early));
        assertTrue(MonthBreak.gentleOn(m, early), "B3 applies from that day");
        assertEquals(Deload.dayStartPlus(early, 7), MonthBreak.gentleEndMs(m));
        assertEquals(early, m.trainerLength.strainSinceMs, "length readings count from it");
        assertTrue(TrainerTab.lengthLive(m, early + HOUR));
    }

    /* =========================== B2 - girth position ================================== */

    @Test void girthComesBackAtLevelThreeWhereItWasAndLevelFourAfterFourWeeks() {
        Model m = owner();
        Model.TrainerTrackState g = m.trainerGirth;
        int week = g.weekIndex, base = g.weekBaseIndex;
        int yield = g.yieldSets, carried = g.carriedSets;
        long baseMs = g.weekBaseMs;
        MonthBreak.take(m, day(TAP_DAY, 10));
        long back = MonthBreak.returnMs(m);
        Plan.Decision first = girthAt(m, back + 8 * HOUR);
        assertEquals(Plan.L3, g.level, "Level 3, not Level 2");
        assertEquals(week, g.weekIndex);
        assertEquals(base, g.weekBaseIndex);
        assertEquals(baseMs, g.weekBaseMs, "the week anchor kept");
        assertEquals(yield, g.yieldSets, "the holds kept");
        assertEquals(carried, g.carriedSets);
        assertTrue(g.pressureKpa <= 37.0 + 1e-9, "never higher than before");
        assertNotEquals(Plan.ACTION_LEVEL_UP, first.action, "not on the return day");
        // Girth Monday / Wednesday / Friday back (the gentle week's at 60 %, asking no minutes).
        int offeredWeek = -1;
        for (int w = 0; w < 6 && offeredWeek < 0; w++) {
            long monday = back + w * 7 * DAY;
            /* The deload cadence counts from the return day (B1, round 3 follow-up), so the
             * morning the 4 weeks are counted is also the morning the week off comes due - the
             * deload tier answers first, as ever. The gate is what is asked here. */
            Plan.Inputs gin = girthIn(m, monday + 8 * HOUR);
            gin.accumulatedTrainingWeeks = 0;            // the gate, the week off aside
            if (w > 0 && Plan.evaluate(gin).action == Plan.ACTION_LEVEL_UP) offeredWeek = w;
            for (int d = 0; d <= 4; d += 2) {
                long ts = monday + d * DAY + 9 * HOUR;
                girth(m, ts, MonthBreak.gentleOn(m, ts) ? 0.0 : 40.0);
            }
            for (int d = 1; d <= 5; d += 2) length(m, monday + d * DAY + 9 * HOUR, Double.NaN);
        }
        assertEquals(4, offeredWeek, "Level 4 comes again after 4 counted training weeks back");
        assertEquals("month 12, and your sessions hold the volume — move up to Level 4",
            MonthBreak.levelUpWords(m, Plan.TRACK_GIRTH_INTERVAL, Plan.L4, "x"),
            "and the card no longer offers the break");
    }

    /* =========================== B3 - the gentle week ================================= */

    @Test void theGentleWeekRunsSixtyPercentWithTheMarksShapeAndDayEightIsFull() {
        Model m = owner();
        assertFalse(m.marksEasily, "somebody who does not mark");
        Deload.arm(m, day(TAP_DAY, 9));                 // a gentle return still open
        MonthBreak.take(m, day(TAP_DAY, 10));
        long back = MonthBreak.returnMs(m), now = back + 8 * HOUR;
        Plan.Inputs in = girthIn(m, now);
        assertFalse(Deload.armed(m), "the short gentle return is not stacked on it");
        assertTrue(MonthBreak.gentleOn(m, now));
        assertEquals(22, MonthBreak.gentleKpa(m), "60 % of 37, whole kPa");
        Model.TrainerTrackState g = m.trainerGirth;
        int month = TrainerTab.monthIndexNow(m, now);
        Plan.Decision d = Plan.evaluate(in);
        Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, g, month, d);
        assertEquals(22, RxBuild.commanded(m, rx, RxBuild.Day.at(m, now)).pressureKpa,
            "the session runs at 22 kPa");
        assertEquals(37.0, g.pressureKpa, 1e-9, "the working pressure itself is kept");
        assertTrue(m.gentleWarmFor(Plan.TRACK_GIRTH_INTERVAL), "the marks warm-up, girth");
        assertTrue(m.gentleWarmFor(Plan.TRACK_LENGTH), "and length");
        assertTrue(m.mintShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, now).contains("MB22"));
        // No yield, fallback, time-cap, pressure or volume decision in the week.
        assertTrue(in.gentleReturnOpen && in.returnRunsUnder && !in.hasYieldData);
        assertTrue(d.action == Plan.ACTION_HOLD, d.rule);
        // Feeders: three quarters of the 60 % figure.
        assertEquals(22.0, TrainerTab.feederInputs(m, now).mainPressureKpa, 1e-9);
        // Day 8: the person's own shape and full working pressure.
        long day8 = back + 7 * DAY + 8 * HOUR;
        girthIn(m, day8);
        assertFalse(MonthBreak.gentleOn(m, day8));
        assertFalse(m.gentleWarmFor(Plan.TRACK_GIRTH_INTERVAL));
        assertEquals(37, RxBuild.commanded(m, rx, RxBuild.Day.at(m, day8)).pressureKpa);
        assertFalse(m.mintShapeTag(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, day8).contains("MB"));
    }

    @Test void aRoutineSavedInTheGentleWeekIsStillThePlansAfterIt() {
        Model m = owner();
        MonthBreak.take(m, day(TAP_DAY, 10));
        long back = MonthBreak.returnMs(m), now = back + 8 * HOUR;
        MonthBreak.settle(m, now);
        Model.TrainerTrackState g = m.trainerGirth;
        int month = TrainerTab.monthIndexNow(m, now);
        Mint.Rx rx = TrainerTab.trackRx(m, Plan.TRACK_GIRTH_INTERVAL, g, month, null);
        String sig = Mint.signature(rx, m.mintShapeTag(Plan.TRACK_GIRTH_INTERVAL, g.level, now));
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx, 0, 0, RxBuild.Day.at(m, now)));
        assertEquals(22, m.workPeakKpa(r), "saved at the gentle week's figure");
        long day8 = back + 7 * DAY + 8 * HOUR;
        MonthBreak.settle(m, day8);
        assertFalse(SavedMint.edited(m, r, sig, 0, 0, now, rx, day8),
            "the plan's own routine, so the full week rewrites it");
        // ...and one saved before the break is the plan's own in the gentle week.
        Model n = owner();
        long before = day(TAP_DAY, 8);
        MonthBreak.settle(n, before);
        Model.TrainerTrackState gn = n.trainerGirth;
        Mint.Rx rxn = TrainerTab.trackRx(n, Plan.TRACK_GIRTH_INTERVAL, gn,
            TrainerTab.monthIndexNow(n, before), null);
        String sign = Mint.signature(rxn,
            n.mintShapeTag(Plan.TRACK_GIRTH_INTERVAL, gn.level, before));
        Model.Routine rn = n.routine(RxBuild.routineFromRx(n, rxn, 0, 0,
            RxBuild.Day.at(n, before)));
        MonthBreak.take(n, day(TAP_DAY, 10));
        long gentle = MonthBreak.returnMs(n) + 8 * HOUR;
        MonthBreak.settle(n, gentle);
        assertFalse(SavedMint.edited(n, rn, sign, 0, 0, before, rxn, gentle));
    }

    @Test void noLengthLoadStepOfAnyKindInTheGentleWeek() {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.gentleWeek = true;
        in.loadLb = 11.0;
        Plan.Decision up = MonthBreak.lengthAfter(in, new Plan.Decision(Plan.ACTION_RAISE_LOAD,
            Plan.TAG_INFERRED, Plan.LENGTH_SLOW_LOAD_RULE, "x", Double.NaN, 0, 11.5));
        assertEquals(Plan.ACTION_HOLD, up.action);
        assertEquals(MonthBreak.GENTLE_LOAD_RULE, up.rule);
        Plan.Decision cut = MonthBreak.lengthAfter(in, new Plan.Decision(Plan.ACTION_REMEASURE,
            Plan.TAG_INFERRED, Plan.LENGTH_CUT_RULE, "x", Double.NaN, 0, 10.5));
        assertEquals(Plan.ACTION_REMEASURE, cut.action, "a high reading asks again");
        assertTrue(Double.isNaN(cut.loadLb), "and the cut waits for the week to end");
        // The length month gate waits for the gentle week to end, and comes the day after it.
        Model m = owner();
        MonthBreak.take(m, day(TAP_DAY, 10));
        long back = MonthBreak.returnMs(m);
        Plan.Inputs gentle = lengthIn(m, back + 8 * HOUR);
        assertTrue(gentle.gentleWeek && gentle.returnRunsUnder);
        Plan.Decision wait = Plan.evaluate(gentle);
        assertNotEquals(Plan.ACTION_LEVEL_UP, wait.action, wait.rule);
        for (int d = 0; d < 7; d += 2) length(m, back + d * DAY + 9 * HOUR, Double.NaN);
        Plan.Inputs full = lengthIn(m, back + 7 * DAY + 8 * HOUR);
        assertFalse(full.gentleWeek || full.returnRunsUnder || full.l4Waits);
        Plan.Decision lv = Plan.evaluate(full);
        assertEquals(Plan.ACTION_LEVEL_UP, lv.action, lv.rule);
        assertEquals("month 12 — move up to Level 4, or take a girth block instead",
            MonthBreak.levelUpWords(m, Plan.TRACK_LENGTH, Plan.L4, lv.reason));
    }

    /* =========================== B4 - the length load ================================= */

    @Test void lengthComesBackAtThreeQuartersAndRemembersTheLoad() {
        assertEquals(11.0, MonthBreak.backLoadLb(14.7), 1e-9, "14.7 -> 11.0");
        assertEquals(11.0, MonthBreak.backLoadLb(Traction.loadLbAtBore(41, 4.5)), 1e-9);
        assertEquals(5.0, MonthBreak.backLoadLb(6.0), 1e-9, "never under the 5 lb floor");
        assertEquals(3.0, MonthBreak.backLoadLb(3.0), 1e-9, "and never above what it was");
        Model m = owner();
        Model.TrainerTrackState l = m.trainerLength;
        int sets = l.strainSets, level = l.level;
        MonthBreak.take(m, day(TAP_DAY, 10));
        assertEquals(14.7, l.loadLb, 1e-9, "today's session keeps its load");
        MonthBreak.settle(m, day(TAP_DAY + 1, 8));
        assertEquals(11.0, l.loadLb, 1e-9);
        assertEquals(14.7, l.climbTargetLb, 1e-9, "the climb-back target");
        assertEquals(sets, l.strainSets, "strain sets kept");
        assertEquals(level, l.level, "the length level kept - not Level 2 week 1");
        // Once: a later settle does not cut again.
        assertFalse(MonthBreak.settle(m, day(TAP_DAY + 2, 8)));
        assertEquals(11.0, l.loadLb, 1e-9);
    }

    /* =========================== B5 - the climb back ================================== */

    /** The length mornings back from the break, a reading every length session at `pct` (the
     *  first one at `first`), each morning's change taken as the person would. */
    static final class Back {
        final List<Double> climb = new ArrayList<Double>();
        final List<String> rules = new ArrayList<String>();
        final Model m;
        long firstStepMs = Long.MAX_VALUE;
        Back(Model m) { this.m = m; }

        Back run(int weeks, double first, double pct, double highFrom, int highWeek) {
            long back = MonthBreak.returnMs(m);
            int n = 0;
            for (int w = 0; w < weeks; w++)
                for (int d = 1; d <= 5; d += 2) {
                    long morning = TrainerTab.mondayStartMs(back) + (w * 7 + d) * DAY + 8 * HOUR;
                    if (morning < back) continue;
                    Plan.Decision dec = lengthAt(m, morning);
                    rules.add(dec.rule);
                    if (Plan.LENGTH_DELOAD_RULE.equals(dec.rule)) continue;   // the week off
                    if (Plan.isCadenceDeload(dec)) {
                        // The regular week off, from tomorrow (this morning's session counts).
                        long from = Deload.dayStartPlus(morning, 1);
                        Deload.remember(m, from, from + Plan.LAYOFF_MS);
                    }
                    if (dec.action == Plan.ACTION_RAISE_LOAD) {
                        if (MonthBreak.CLIMB_RULE.equals(dec.rule)) {
                            climb.add(Double.valueOf(dec.loadLb));
                            firstStepMs = Math.min(firstStepMs, morning);
                        }
                        LengthTrack.acceptLoad(m, dec.rule, dec.loadLb, dec.pressureKpa, morning);
                    } else if (dec.action == Plan.ACTION_REMEASURE && !Double.isNaN(dec.loadLb)) {
                        LengthTrack.acceptLoad(m, dec.rule, dec.loadLb, dec.pressureKpa, morning);
                    }
                    double p = n++ == 0 ? first : (w >= highWeek ? highFrom : pct);
                    length(m, morning + HOUR, p);
                }
            return this;
        }
    }

    @Test void underTwoPercentWeeksClimbHalfTheGapAndNeverPastTheTarget() {
        Model m = owner();
        MonthBreak.take(m, day(TAP_DAY, 10));
        // A first reading at 3 % (the block reached), then under 2 % every session.
        // (14 weeks: the cadence's week off now falls 4 training weeks after the return day.)
        Back b = new Back(m).run(14, 3.0, 1.5, 1.5, 99);
        List<Double> want = new ArrayList<Double>();
        double[] steps = { 12.9, 13.8, 14.3, 14.5, 14.6, 14.7 };
        for (int i = 0; i < steps.length; i++) want.add(Double.valueOf(steps[i]));
        assertEquals(want, b.climb, "half the gap each week under 2 %: " + b.rules);
        for (int i = 0; i < b.climb.size(); i++)
            assertTrue(b.climb.get(i).doubleValue() <= 14.7 + 1e-9, "never past the target");
        assertEquals(14.7, m.trainerLength.loadLb, 1e-9);
        assertEquals(0.0, m.trainerLength.climbTargetLb, 1e-9, "the climb back ends there");
        // No option-D card while climbing back: no fell week off, no D2 set, no D3 offer.
        int i = 0;
        for (; i < b.rules.size(); i++) {
            String r = b.rules.get(i);
            assertNotEquals(Plan.LENGTH_FELL_RULE, r, "no D1 at " + i + ": " + b.rules);
            assertNotEquals(Plan.LENGTH_NEVER_RULE, r, "no D2 at " + i + ": " + b.rules);
            assertNotEquals(Plan.LENGTH_STILL_UNDER_RULE, r, "no D3 at " + i + ": " + b.rules);
            if (MonthBreak.CLIMB_RULE.equals(r) && b.climb.size() > 0
                    && i == b.rules.lastIndexOf(MonthBreak.CLIMB_RULE)) break;
        }
        assertTrue(b.firstStepMs >= MonthBreak.gentleEndMs(m), "no step in the gentle week");
        // "Most you will go to" for length caps it too: 41 kPa at the 4.5 cm bore is 14.66 lb.
        m.rxLengthMaxKpa = 41;
        assertEquals(Traction.loadLbAtBore(41, 4.5), MonthBreak.climbCapLb(m, 14.7), 1e-9);
    }

    @Test void aConfirmedCutEndsTheClimbBack() {
        Model m = owner();
        MonthBreak.take(m, day(TAP_DAY, 10));
        // Under 2 % for the first weeks back, then over 6 % from the fourth.
        Back b = new Back(m).run(6, 1.5, 1.5, 7.0, 3);
        assertTrue(b.climb.size() >= 1, "it was climbing: " + b.rules);
        assertTrue(b.rules.contains(Plan.LENGTH_CUT_RULE), "a confirmed cut: " + b.rules);
        assertEquals(0.0, m.trainerLength.climbTargetLb, 1e-9, "the cut ends the climb back");
    }

    /* =========================== B6 - saved data and upgraders ======================== */

    @Test void anOldLengthRestEndsOnItsDateWithNothingToClimbBack() {
        Model m = owner();
        Model.TrainerTrackState l = m.trainerLength;
        long tap = day(TAP_DAY, 10);
        l.restUntilMs = tap + Plan.LENGTH_BREAK_WEEKS * 7L * DAY;   // the old length arm
        Model back = Model.fromJson(m.toJson());
        assertFalse(MonthBreak.taken(back), "no month-12 break on an old file");
        assertFalse(TrainerTab.lengthLive(back, tap + DAY));
        assertTrue(TrainerTab.lengthLive(back, back.trainerLength.restUntilMs + HOUR),
            "it ends on its date, as before");
        MonthBreak.settle(back, back.trainerLength.restUntilMs + HOUR);
        assertEquals(14.7, back.trainerLength.loadLb, 1e-9, "B4 is not applied to it");
        assertEquals(0.0, back.trainerLength.climbTargetLb, 1e-9);
    }

    @Test void theBreakSurvivesASaveAndAHandEditedOneIsNone() {
        Model m = owner();
        MonthBreak.take(m, day(TAP_DAY, 10));
        MonthBreak.settle(m, day(TAP_DAY + 1, 8));
        Model back = Model.fromJson(m.toJson());
        assertEquals(m.breakFromMs, back.breakFromMs);
        assertEquals(m.breakUntilMs, back.breakUntilMs);
        assertTrue(back.breakStarted);
        assertEquals(14.7, back.trainerLength.climbTargetLb, 1e-9);
        back.breakUntilMs = back.breakFromMs - 1L;
        back.trainerLength.climbTargetLb = 40.0;
        back.clampAll();
        assertFalse(MonthBreak.taken(back));
        assertEquals(Scale.LOAD_HARD_MAX_LB, back.trainerLength.climbTargetLb, 1e-9);
    }
}
