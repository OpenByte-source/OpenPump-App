package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A YEAR OF THE PLAN OVER THE SETUP ANSWERS (0.10) - the regression sweep for the three
 * Level 1 stalls {@link YearStallsTest} pins one by one: no combination of answers stays at
 * Level 1 all year because the length track took five girth sets, because "8.0 inHg" was
 * stored as 27 kPa, or because the length track had no cylinder that pulls.
 *
 * Every combination of experience (new at 0 months, 0 / 2 / 6 / 12 months), the girth
 * working-pressure answer (14, 17, 20, 23, 27, 29 kPa), girth style and the length track
 * (off, on without a pulling cylinder, on with a length tube) is set up the way the setup's
 * Confirm writes it and walked for 52 weeks of Monday / Wednesday / Friday mornings the way
 * the app walks them: Deload.settle, the week table, Plan.evaluate on each track, a due
 * deload tapped, a met gate accepted, the prescription on the person's scale
 * (TrainerTab#trackRx) saved as the plan's figure (TrainerTab#savedPlanKpa), the day's
 * routines built (RxBuild), START shaping them (TrainerTab#dayChoice, RunShape - length
 * first) and the sessions filed. The Android glue is ported from the app, as the 52-week
 * simulation that found the stalls did; the Program is standard, so nothing but these causes
 * is on trial.
 *
 * AND THE RAMPED PRESET (0.10, the owner's decisions): interval girth with its work Ramped
 * (and length's too, as the preset sets both), with the length track off and on, under each of
 * the Program's pressures - a Firm bias from a low answer puts a ramp's first climbing hold
 * under the level's counting line, which is not made up but credited to the level at the rate
 * the session delivered its target (Routine#climbUnderLineMin, TrainerTab#creditedNet, filed
 * here as the app files it). It used to
 * stay at Level 1 all year with length on: the five holds a both-tracks day gave to length
 * were the block at the working pressure, so the girth session never reached the plan's 8 inHg
 * and the gate's pressure condition failed. The holds now come out spread across the session
 * and every session keeps a hold at its working pressure (RunShape#trimGirthWork), and the
 * Ramped years are held to the same rules as the fixed ones.
 */
class YearStallsSweepTest {

    static final long HOUR = 3_600_000L, DAY = 24 * HOUR, WEEK = 7 * DAY, MIN = 60_000L;
    static final int WEEKS = 52;
    static final int[] MONTHS = { 0, 0, 2, 6, 12 };
    static final boolean[] NEW = { true, false, false, false, false };
    static final int[] KPA = { 14, 17, 20, 23, 27, 29 };
    static final int[] STYLES = { Plan.TRACK_GIRTH_INTERVAL, Plan.TRACK_GIRTH_TRADITIONAL };
    static final int LEN_OFF = 0, LEN_PLAIN = 1, LEN_TRACTION = 2;

    static long t0() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.OCTOBER, 5, 0, 0, 0);   // a Monday
        return c.getTimeInMillis();
    }

    /** One combination's year: each track's level at the end of each week. */
    static final class Year {
        final int[] girth = new int[WEEKS], length = new int[WEEKS];
        int startLevel;
        String key;
        int firstWeekAt(int[] lv, int level) {
            for (int i = 0; i < lv.length; i++) if (lv[i] >= level) return i + 1;
            return 0;
        }
    }

    /** The app's morning, ported (see the class comment). */
    static final class Walk {
        final Model m = new Model();
        final int style, len;
        final long t0;

        Walk(int exp, int kpa, int style, int len, long t0) {
            this(exp, kpa, style, len, t0, false, Model.Program.PRESS_STANDARD);
        }

        /** `ramped`: the Ramped preset's work (and warm-up) on both tracks' Programs, under the
         *  Program pressure `bias`. */
        Walk(int exp, int kpa, int style, int len, long t0, boolean ramped, int bias) {
            this.style = style;
            this.len = len;
            this.t0 = t0;
            long now = t0 + 7 * HOUR;
            m.ceilKpa = 40;
            m.rxNewToPumping = NEW[exp];
            m.trainerMonthsPumping = MONTHS[exp];
            m.trainerMonthsAt = now;
            m.rxDropKpa = 3;
            m.clampAll();
            // CommitOnboardTap: the position, the plan's figure and the answer's offset.
            TrainerTab.Derived g = TrainerTab.deriveGirth(style, MONTHS[exp], kpa,
                m.rxNewToPumping, m.ceilKpa, m.rxWorkMaxKpa);
            m.trainerGirthStyle = style;
            m.trainerLengthOn = len != LEN_OFF;
            Model.TrainerTrackState gs = m.trainerGirth;
            gs.level = g.level;
            gs.weekIndex = g.weekIndex;
            double gPlan = Scale.setupPlanKpa(style, g.level, g.monthIndex, g.pressureKpa);
            gs.setWorkingPressure(gPlan, now);
            gs.offsetKpa = Scale.offsetFromAnswer(kpa, gPlan);
            gs.restartPressureClock(now);
            gs.weekBaseIndex = g.weekIndex;
            gs.weekBaseMs = now;
            gs.yieldSinceMs = now;
            if (len != LEN_OFF) {
                // The length step's default: the girth answer within the length step's cap.
                int month = Plan.monthIndex(MONTHS[exp] * 4);
                double lengthKpa = Math.min(kpa, Plan.startCapKpa(m.rxNewToPumping, month,
                    m.ceilKpa, m.rxWorkMaxKpa));
                TrainerTab.Derived l = TrainerTab.deriveLength(MONTHS[exp], lengthKpa,
                    m.rxNewToPumping, m.ceilKpa, m.rxWorkMaxKpa);
                Model.TrainerTrackState ls = m.trainerLength;
                ls.level = l.level;
                ls.weekIndex = l.weekIndex;
                double lPlan = Scale.setupPlanKpa(Plan.TRACK_LENGTH, l.level, l.monthIndex,
                                                  l.pressureKpa);
                ls.setWorkingPressure(lPlan, now);
                ls.offsetKpa = Scale.offsetFromAnswer(lengthKpa, lPlan);
                ls.restartPressureClock(now);
                ls.weekBaseIndex = l.weekIndex;
                ls.weekBaseMs = now;
                ls.loadLb = Plan.LENGTH_LOAD_START_LB;
            }
            m.trainerGirthOn = true;
            m.programGirth = new Model.Program();
            m.programLength = new Model.Program();
            if (ramped) {
                // The "Ramped" preset, as setup writes it (TrainerScreen#OnboardPresetTap).
                m.programGirth.work = m.programLength.work = Model.Program.WORK_RAMP_IN_SET;
                m.programGirth.warm = m.programLength.warm = Model.Program.WARM_RAMP;
            }
            m.programGirth.pressure = m.programLength.pressure = bias;
            boolean[] days = new boolean[7];
            days[0] = days[2] = days[4] = true;
            m.sched.applyWeekShape(Schedule.SHAPE_COMBINED, days, Schedule.PLAN_ANY);
            m.sched.hour = 19;
            m.trainerEnrolled = true;
            m.seedTraditionalRest(gs.level);
            m.trainerEnrolledAt = now;
            if (len == LEN_TRACTION) {
                // A girth tube and a length tube for a 12.7 cm erect girth, logged.
                m.cylinders.add(new Model.Cylinder("girth tube", 4.60, 23.0));
                m.cylinders.add(new Model.Cylinder("length tube", 4.10, 23.0));
                m.ensureCylinderIds();
                m.activeCylinder = 0;
                Model.Reading r = new Model.Reading();
                r.id = "rg0"; r.ts = now - HOUR; r.gir = 12.7;
                r.method = Model.Reading.METHOD_MSEG;
                m.measLog.all.add(r);
            }
            marker("PG", style);
            marker("PL", Plan.TRACK_LENGTH);
            gs.lastMintId = "PG";
            m.trainerLength.lastMintId = "PL";
        }

        void marker(String id, int track) {
            if (m.routine(id) != null) return;
            Model.Routine r = new Model.Routine();
            r.id = id; r.trainerTrack = track; r.name = id;
            m.routines.add(r);
        }

        /** SessionActivity#evalTrack / #buildTrackInputs. */
        Plan.Decision[] dOut = new Plan.Decision[1];
        Mint.Rx eval(int track, Model.TrainerTrackState st, long now) {
            int month = TrainerTab.monthIndexNow(m, now);
            Plan.Inputs in = new Plan.Inputs();
            in.track = track;
            in.level = st.level;
            in.monthIndex = month;
            in.pressureKpa = st.pressureKpa;
            in.ceilKpa = m.ceilKpa;
            in.layoff = TrainerTab.layoff(m, style, now);
            in.inDeloadWeek = TrainerTab.inDeloadWeek(m, now);
            in.accumulatedTrainingWeeks = TrainerTab.planTrainingWeeks(m,
                TrainerTab.deloadAnchorMs(m), now);
            in.firstDeloadPending = !m.trainerFirstDeloadTaken;
            in.redFlag = m.trainerState == Model.TRAINER_STATE_SAFETY_FLAG;
            in.returnRunsUnder = TrainerTab.returnRunsUnder(m, now);
            if (track == Plan.TRACK_LENGTH) {
                in.hasYieldData = false;
                in.trainingWeeksAtPressure = TrainerTab.monthsBetween(st.lastMintMs, now);
                in.strainPct = Double.NaN;
                in.fatiguePct = Double.NaN;
                double gir = m.girthForTraction();
                in.girthCm = gir;
                in.fitState = m.tractionFit(gir);
                in.loadLb = st.loadLb;
                in.strainSets = st.strainSets;
                in.inGirthFocus = st.inGirthFocus(now);
                in.lengthTrainingWeeks = TrainerTab.accumulatedTrainingWeeks(m,
                    Plan.TRACK_LENGTH, m.trainerEnrolledAt, now);
            } else {
                TrainerTab.fillGirthInputs(m, track, st, month, now, in);
            }
            Plan.Decision d = Plan.evaluate(in);
            dOut[0] = d;
            return TrainerTab.trackRx(m, track, st, month, d);
        }

        /** What applyPlanTo / saveMint write when the prescription changed. */
        void apply(int track, Model.TrainerTrackState st, Mint.Rx rx, Plan.Decision d,
                   long now) {
            String sig = Mint.signature(rx, m.mintShapeTag(track, st.level, now));
            if (sig.equals(st.lastMintSig)) return;
            st.lastMintSig = sig;
            st.lastMintMs = now;
            st.setWorkingPressure(TrainerTab.savedPlanKpa(m, track, rx, false, now), now);
            Mint.commitYield(st, track, d, now);
        }

        /** StartDeloadTap -> recordDeload. */
        void tapDeload(long tap) {
            long gap = Deload.askGapStartMs(m, tap);
            long last = TrainerTab.lastAnyPlanSessionMs(m);
            Deload.remember(m, tap, tap + Plan.LAYOFF_MS);
            m.trainerFirstDeloadTaken = true;
            Deload.refund(m.trainerGirth, tap, tap + Plan.LAYOFF_MS);
            m.returnAnchorMs = last;
            m.deloadAskAnchorMs = gap > 0L ? gap : last;
            Deload.arm(m, tap + Plan.LAYOFF_MS);
        }

        void file(String rid, long ts, Model.Routine built, int scale, String shape,
                  boolean isLength) {
            Model.Sess s = new Model.Sess();
            s.id = "s" + m.sessLog.all.size();
            s.routineId = rid;
            s.ts = ts;
            s.durSec = m.durationMs(built) / 1000L;
            s.completed = true;
            s.netTupSec = Double.valueOf(built.netTargetMin * 60.0);
            s.netTargetMin = Double.valueOf(built.netTargetMin);
            if (built.netTargetMin > 0.0) s.climbUnderLineMin = built.climbUnderLineMin;
            s.peakKpa = Double.valueOf(m.workPeakKpa(built));
            s.shape = shape;
            s.expansionDone = isLength;
            s.scaleKpa = scale;
            m.sessLog.file(s);
        }

        Year run() {
            Year y = new Year();
            y.startLevel = m.trainerGirth.level;
            int[] dows = { 0, 2, 4 };
            Model.TrainerTrackState g = m.trainerGirth, l = m.trainerLength;
            for (int week = 1; week <= WEEKS; week++) {
                for (int dow : dows) {
                    long now = t0 + (week - 1) * WEEK + dow * DAY + 8 * HOUR;
                    if (TrainerTab.inDeloadWeek(m, now)) continue;
                    Deload.settle(m, Summary.dayNumber(now));
                    if (style == Plan.TRACK_GIRTH_INTERVAL) {
                        int weeks = TrainerTab.accumulatedTrainingWeeks(m, style,
                            g.weekBaseMs, now);
                        weeks = Math.max(0, weeks - g.weekRepeats);
                        int eff = Mint.advanceFromAnchor(style, g.level, g.weekBaseIndex,
                            weeks, g.weekIndex);
                        if (eff > g.weekIndex) g.weekIndex = eff;
                    }
                    Mint.Rx rg = null;
                    boolean deload = false;
                    for (int guard = 0; guard < 6; guard++) {
                        rg = eval(style, g, now);
                        Plan.Decision d = dOut[0];
                        if (d.action == Plan.ACTION_DELOAD) {
                            tapDeload(now - 30 * MIN);
                            deload = true;
                            break;
                        }
                        if (d.action == Plan.ACTION_LEVEL_UP && g.level < Plan.L4) {
                            Mint.crossGirthLevel(g, style, g.level + 1, now);   // LevelUpTap
                            g.lastMintId = "PG";
                            continue;
                        }
                        break;
                    }
                    if (deload) {
                        Mint.Rx r2 = eval(style, g, now);
                        apply(style, g, r2, dOut[0], now);
                        if (len != LEN_OFF) {
                            Mint.Rx r3 = eval(Plan.TRACK_LENGTH, l, now);
                            apply(Plan.TRACK_LENGTH, l, r3, dOut[0], now);
                        }
                        continue;
                    }
                    apply(style, g, rg, dOut[0], now);

                    Mint.Rx rl = null;
                    if (len != LEN_OFF) {
                        for (int guard = 0; guard < 8; guard++) {
                            rl = eval(Plan.TRACK_LENGTH, l, now);
                            Plan.Decision d = dOut[0];
                            if (d.action == Plan.ACTION_LEVEL_UP && l.level < Plan.L4) {
                                // LevelUpTap's length branch: the level, and nothing else.
                                l.level = l.level + 1;
                                l.lastMintSig = ""; l.lastMintMs = 0L;
                                continue;
                            }
                            if (d.action == Plan.ACTION_ADD_VOLUME) {
                                l.strainSets = Math.min(Plan.LENGTH_STRAIN_SETS_MAX,
                                    l.strainSets + Math.max(1, d.setsDelta));
                                l.lastMintSig = "";
                                continue;
                            }
                            if (d.action == Plan.ACTION_RAISE_LOAD) {
                                l.loadLb = d.loadLb;
                                l.lastMintSig = "";
                                continue;
                            }
                            break;
                        }
                        apply(Plan.TRACK_LENGTH, l, rl, dOut[0], now);
                    }

                    // The day's routines, as the saved routine is rewritten this morning.
                    m.routines.clear();
                    m.sets.clear();
                    RxBuild.Day today = RxBuild.Day.at(m, now);
                    boolean lengthRuns = len != LEN_OFF && !l.resting(now)
                        && !Deload.lengthRests(m, now);
                    Model.Routine lr = null;
                    if (lengthRuns) {
                        lr = m.routine(RxBuild.routineFromRx(m, rl, 0, 0, today));
                        lr.id = "PL";
                    }
                    Model.Routine gr = m.routine(RxBuild.routineFromRx(m, rg, 0, 0, today));
                    gr.id = "PG";
                    marker("PL", Plan.TRACK_LENGTH);
                    g.lastMintId = "PG";

                    // START and file: length first (Model#rxLengthFirst), then girth.
                    long tRun = now + HOUR;
                    if (lr != null) {
                        RunShape.Built bl = RunShape.build(m, lr,
                            TrainerTab.dayChoice(m, lr, tRun, false, false));
                        file("PL", tRun, bl.routine, lr.trainerScaleKpa, "", true);
                        Deload.onFiled(m, tRun, Plan.TRACK_LENGTH, false, true);
                        tRun += m.durationMs(bl.routine) + 30 * MIN;
                    }
                    RunShape.Built bg = RunShape.build(m, gr,
                        TrainerTab.dayChoice(m, gr, tRun, false, false));
                    file("PG", tRun, bg.routine, gr.trainerScaleKpa, bg.code(), false);
                    Deload.onFiled(m, tRun, style, false, true);
                }
                y.girth[week - 1] = g.level;
                y.length[week - 1] = len == LEN_OFF ? 0 : l.level;
            }
            return y;
        }
    }

    static final String[] BIAS_NAMES = { "gentle", "standard", "firm" };

    static String key(int exp, int kpa, int style, int len, boolean ramped, int bias) {
        return "exp " + (NEW[exp] ? "new " : "") + MONTHS[exp] + " months, " + kpa + " kPa, "
            + (style == Plan.TRACK_GIRTH_INTERVAL ? "interval" : "traditional")
            + (ramped ? " Ramped " + BIAS_NAMES[bias] : "") + ", length "
            + (len == LEN_OFF ? "off" : len == LEN_PLAIN ? "plain" : "traction");
    }

    @Test void noSetupStaysAtLevelOneForTheseThreeReasons() {
        long t0 = t0();
        int walked = 0, lengthOnCompared = 0, placed27 = 0, plainCompared = 0, rampedYears = 0;
        List<String> stalls = new ArrayList<String>();
        for (int exp = 0; exp < MONTHS.length; exp++) {
            for (int kpa : KPA) {
                for (int sv = 0; sv < STYLES.length + 3; sv++) {
                    // The two styles with fixed holds, then interval girth Ramped (0.10) under
                    // each Program pressure: gentle, standard, firm.
                    boolean ramped = sv >= STYLES.length;
                    int style = ramped ? Plan.TRACK_GIRTH_INTERVAL : STYLES[sv];
                    int bias = ramped ? sv - STYLES.length : Model.Program.PRESS_STANDARD;
                    Year[] y = new Year[3];
                    for (int len = LEN_OFF; len <= LEN_TRACTION; len++) {
                        y[len] = new Walk(exp, kpa, style, len, t0, ramped, bias).run();
                        y[len].key = key(exp, kpa, style, len, ramped, bias);
                        walked++;
                        if (ramped) rampedYears++;
                    }
                    int monthsLevel = TrainerTab.deriveGirth(style, MONTHS[exp], 29.0,
                        NEW[exp], 40, 0).monthsImpliedLevel;

                    /* 2. "8.0 inHg" (27 kPa) - or anything at or above it - starts where the
                     *    months answer puts it. */
                    if (kpa >= 27) {
                        for (int len = LEN_OFF; len <= LEN_TRACTION; len++)
                            assertEquals(monthsLevel, y[len].startLevel, y[len].key
                                + ": an answer at the Level 2 floor starts at the months' level");
                        if (kpa == 27 && monthsLevel >= Plan.L2 && !ramped) placed27++;
                    }

                    /* 1. The length track on never holds girth at Level 1 where girth alone
                     *    moves up: the same year, the gate in the same week or within the
                     *    week of a deload's shift. */
                    int alone = y[LEN_OFF].firstWeekAt(y[LEN_OFF].girth, Plan.L2);
                    for (int len = LEN_PLAIN; len <= LEN_TRACTION; len++) {
                        int with = y[len].firstWeekAt(y[len].girth, Plan.L2);
                        if (alone > 0) {
                            lengthOnCompared++;
                            if (with == 0 || with > alone + 2)
                                stalls.add(y[len].key + ": girth Level 2 in week " + with
                                    + ", week " + alone + " with length off");
                        }
                    }

                    /* 3. Length without a pulling cylinder moves up on the calendar exactly as
                     *    it does with one, and never stays at Level 1 once month 3 is behind. */
                    plainCompared++;
                    for (int w = 0; w < WEEKS; w++)
                        if (y[LEN_PLAIN].length[w] != y[LEN_TRACTION].length[w])
                            stalls.add(y[LEN_PLAIN].key + ": length Level "
                                + y[LEN_PLAIN].length[w] + " in week " + (w + 1)
                                + ", Level " + y[LEN_TRACTION].length[w] + " with a length tube");
                    for (int len = LEN_PLAIN; len <= LEN_TRACTION; len++)
                        if (y[len].length[WEEKS - 1] == Plan.L1)
                            stalls.add(y[len].key + ": length at Level 1 all year");

                    /* And the plain statement: nothing here starts at Level 1 and is still
                     *    there at week 52. */
                    for (int len = LEN_OFF; len <= LEN_TRACTION; len++)
                        if (y[len].girth[WEEKS - 1] == Plan.L1)
                            stalls.add(y[len].key + ": girth at Level 1 all year");
                }
            }
        }
        System.out.println("YEAR SWEEP: " + walked + " years walked (" + rampedYears
            + " Ramped), " + lengthOnCompared
            + " length-on years against their length-off twin, " + placed27
            + " setups at 27 kPa with months at Level 2+, " + plainCompared
            + " plain-length years against the length tube");
        assertEquals(450, walked);
        assertEquals(270, rampedYears, "every Ramped setup, length off and on, every pressure");
        assertEquals(4, placed27, "6 and 12 months, both styles");
        assertTrue(lengthOnCompared > 100, "enough years to say something: " + lengthOnCompared);
        assertTrue(stalls.isEmpty(), stalls.size() + " stall(s):\n" + String.join("\n", stalls));
    }
}
