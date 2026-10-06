package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * AUDIT D2, D3, D4, D12 (the owner's decisions, 0.10): WHAT THE BUILDER COMMANDS.
 *
 * A regression sweep through the app's own chain (Mint.prescribe -> RxBuild.routineFromRx ->
 * Model.plan) over the grid of track positions x work shapes x pressure biases x reductions,
 * plus the warm-up and Shape variants that change what the builder lays out. For every build:
 *
 *   D2  no bias undoes a reduction: on a reduced day every pull is at most the same build's
 *       unreduced pull less the day's cut, and lighter than it;
 *   D3  no work preset at the same pressure straight after a ramp's final step - the ramp's
 *       make-up is its own extra step, not a second set at its top;
 *   D12 the time the plan counts under pressure (PlannedTime, from the net's own line) is
 *       the routine's net target, wherever it has one;
 *   D4  the name, and the figure the Trainer card and row print (RxBuild#commandedKpa), are
 *       the figure the work commands;
 *   and the check pull is never above the routine's work peak - fresh, and after the plan
 *       rewrites a saved routine for a lighter day.
 *
 * AND D5, D6, D8, D9, D11 (the owner's decisions, 0.10): WHAT THE BUILDER LAYS OUT.
 *
 *   D5  no warm-up step at or above the work's first pull; a warm-up ramp ends about 1 inHg
 *       under it; every warm-up cycle drops as the work does (to the work's own drop, never
 *       above the dose floor);
 *   D6  every step of every set is whole cycles - ramps of work and of warm-up alike - so the
 *       count above is whole holds with no exception;
 *   D8  a pyramid peaks at its figure, two chunks included (on the last);
 *   D9  no ramp is flat;
 *   D11 the hybrid is the guidance's: the fatigue block, then its five-minute holds in place
 *       of the intervals, a rest between each, counted exactly as its target.
 *
 * Kept small enough to run in a second or two: a build is milliseconds.
 */
class TrainerBuildSweepTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final int[] SHAPES = { Model.Program.WORK_FIXED,
        Model.Program.WORK_RAMP_IN_SET, Model.Program.WORK_ASCENDING,
        Model.Program.WORK_PYRAMID };
    private static final String[] SHAPE_NAMES = { "fixed", "ramp", "ascending", "pyramid" };
    private static final int[] BIASES = { Model.Program.PRESS_GENTLE,
        Model.Program.PRESS_STANDARD, Model.Program.PRESS_FIRM };
    private static final String[] BIAS_NAMES = { "gentle", "standard", "firm" };
    private static final int NONE = 0, CUT = 1, TAPER1 = 2, TAPER2 = 3;
    private static final String[] RED_NAMES = { "none", "cylinder cut", "taper 1", "taper 2" };
    /** Shape variants: 0 plain, 1 ramp warm-up, 2 a 3-minute hold at L3, 3 blocks of three,
     *  4 retention, 5 split part 1 of 2, 6 split part 2 of 2, 7 hybrid (girth, L3+), 8 the
     *  Program's climbing warm-up with ten blocks of ten (the longest ramps), 9 a ramp whose
     *  climb is not counted (0.10), 10 marks easily - the gentle warm-up (0.10). */
    private static final int VARIANTS = 11;
    private static final String[] VARIANT_NAMES = { "plain", "ramp warm-up", "hold 180",
        "blocks of 3", "retention", "split 1/2", "split 2/2", "hybrid", "climb, blocks of 10",
        "only the work counts", "marks easily (gentle warm-up)" };

    private static final class Pos {
        final int track, level, week, carried; final double hg; final String name;
        Pos(String name, int track, int level, int week, double hg, int carried) {
            this.name = name; this.track = track; this.level = level; this.week = week;
            this.hg = hg; this.carried = carried;
        }
    }

    private static final Pos[] POSITIONS = {
        new Pos("girth interval L1 w1", Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 1, 6.0, 0),
        new Pos("girth interval L1 w10", Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 10, 7.0, 0),
        // Ten sets in one block: the ramp longer than the device's table (audit D6).
        new Pos("girth interval L1 w14", Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 14, 8.0, 0),
        new Pos("girth interval L2 w6", Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 6, 9.0, 0),
        new Pos("girth interval L3 carried 13", Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 1, 9.0, 13),
        new Pos("girth interval L4", Plan.TRACK_GIRTH_INTERVAL, Plan.L4, 1, 10.0, 0),
        new Pos("girth traditional L1", Plan.TRACK_GIRTH_TRADITIONAL, Plan.L1, 1, 6.0, 0),
        new Pos("girth traditional L3", Plan.TRACK_GIRTH_TRADITIONAL, Plan.L3, 1, 9.0, 0),
        new Pos("length L1 (no rack)", Plan.TRACK_LENGTH, Plan.L1, 1, 6.0, 0),
        new Pos("length L2 (no rack)", Plan.TRACK_LENGTH, Plan.L2, 1, 9.0, 0),
        new Pos("feeder L3", Plan.TRACK_FEEDER, Plan.L3, 0, 9.0, 0),
    };

    /** The length track's L1 position, the traction session's prescription. */
    private static final Pos LENGTH_L1 = POSITIONS[8];

    /* ------------------------------------------------------------------------- helpers */

    private static Model model(int reduction, long now) {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerMonthsPumping = 4;
        m.ceilKpa = 40;
        reduce(m, reduction, now);
        return m;
    }

    private static void reduce(Model m, int reduction, long now) {
        if (reduction == CUT) m.oversizeAccepted = true;
        if (reduction == TAPER1 || reduction == TAPER2) {
            Deload.arm(m, now - DAY);
            if (reduction == TAPER2) m.returnStep = 1;
        }
    }

    /** The model, shaped for one grid point. Returns {part, ofParts}. */
    private static int[] shape(Model m, Pos p, int work, int bias, int variant) {
        if (p.track == Plan.TRACK_GIRTH_TRADITIONAL)
            m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
        Model.Program prog = m.programFor(p.track);
        if (prog != null) { prog.work = work; prog.pressure = bias; }
        switch (variant) {
            case 1: m.rxWarmRamp = true; break;
            case 2: m.rxHoldSec = 180; break;
            case 3: m.rxSetsPerBlock = 3; break;
            case 4: m.rxRetention = true; m.rxRetentionMin = 5; break;
            case 5: return new int[]{ 1, 2 };
            case 6: return new int[]{ 2, 2 };
            case 7: m.trainerGirthHybrid = true; break;
            case 8:
                if (prog != null) prog.warm = Model.Program.WARM_RAMP;
                m.rxSetsPerBlock = 10;
                break;
            case 9: m.rampCountClimb = false; break;      // 0.10: the climb not counted
            case 10: m.marksEasily = true; break;         // 0.10: the gentle warm-up
            default: break;
        }
        return new int[]{ 0, 0 };
    }

    private static Mint.Rx rxFor(Model m, Pos p, long now) {
        return Mint.prescribe(p.track, p.level, p.week, p.hg * Plan.HG,
            TrainerTab.monthIndexNow(m, now), m.ceilKpa, p.carried, 0, null);
    }

    private static Model.Routine build(Model m, Mint.Rx rx, int[] part, long now) {
        return m.routine(RxBuild.routineFromRx(m, rx, part[0], part[1], RxBuild.Day.at(m, now)));
    }

    private static Model.Stage stageOf(Model.Routine r, Model.Preset p) {
        return p.stageIdx >= 0 && p.stageIdx < r.stages.size() ? r.stages.get(p.stageIdx) : null;
    }

    /** A preset of the WORK - counted work, not the warm-up, a rest, the fatigue block or
     *  the retention hold. */
    private static boolean work(Model.Routine r, Model.Preset p) {
        Model.Stage st = stageOf(r, p);
        return st != null && !p.rest && !st.rest && !st.outOfNet();
    }

    /** The deepest pull of the work. */
    private static int workPeak(Model m, Model.Routine r) {
        int pk = 0;
        for (Model.Preset p : m.plan(r)) if (work(r, p) && p.up > pk) pk = p.up;
        return pk;
    }

    /** The deepest pull of anything the routine commands, warm-up and fatigue included. */
    private static int anyPeak(Model m, Model.Routine r) {
        int pk = 0;
        for (Model.Preset p : m.plan(r)) if (!p.rest && p.up > pk) pk = p.up;
        return pk;
    }

    /** The first pull of whatever follows the warm-up - where the work starts. */
    private static int firstWorkPull(Model m, Model.Routine r) {
        for (Model.Preset p : m.plan(r)) {
            Model.Stage st = stageOf(r, p);
            if (st == null || p.rest || st.rest || st.colour == Model.STAGE_WARM) continue;
            return p.up;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * D5, D6, D9 - the layout rules every build keeps, warm-up and work alike. Returns the
     * number of ramps it walked.
     *
     *   every set that commands is whole cycles, and every ramp step is: no step cuts a hold
     *       short (D6 - a ramp of more cycles than the table has steps ends on a long step);
     *   no ramp is flat: its pull moves (D9) - or its holds do (t10 R-01: P2's reps grow
     *       30 -> 60 s, at one pressure where the work is under its start);
     *   no warm-up step pulls deeper than the work (t10 R-01: P2 ends AT the work, or where
     *       its 1 inHg steps reach; the gentle one at the work's first pull), and every warm-up
     *       cycle drops to the work's own drop - never above the dose floor, always under its
     *       pull (D5).
     */
    static int layoutRules(Model m, Model.Routine r, String what) {
        int ramps = 0;
        int first = firstWorkPull(m, r);
        int drop = Mint.clampDropKpa(m.rxDropKpa);
        boolean gentle = m.gentleWarmFor(r.trainerTrack);
        // The deepest pull of everything after the warm-up: what P2 may climb to (t10 R-01).
        int workTop = 0;
        for (Model.Preset q : m.plan(r)) {
            Model.Stage sq = stageOf(r, q);
            if (q.rest || sq == null || sq.colour == Model.STAGE_WARM) continue;
            workTop = Math.max(workTop, q.up);
        }
        for (Model.Stage st : r.stages) {
            if (st.rest) continue;
            boolean warm = st.colour == Model.STAGE_WARM;
            for (String id : st.setIds) {
                Model.Set s = m.set(id);
                if (s == null || s.rest) continue;
                String at = what + ": '" + s.name + "' in '" + st.name + "'";
                if (s.ramp) {
                    ramps++;
                    int n = Math.max(2, Math.min(Proto.SLOTS, s.steps));
                    // One pass of every step's own cycle (a ramp may walk its holds, t10 P2).
                    int pass = 0;
                    for (int i = 0; i < n; i++) pass += s.stepCycleSec(i, n);
                    assertEquals(0, s.dur % pass, at + ": " + s.dur + " s is not whole passes of "
                        + pass + " s - a step cut short");
                    assertTrue(s.endUp() != s.up || s.endUh() != s.uh,
                        at + ": a flat ramp at " + s.up);
                } else {
                    assertEquals(0, s.dur % s.cycle(), at + ": " + s.dur
                        + " s is not whole " + s.cycle() + " s cycles");
                }
                if (!warm) continue;
                int top = Math.max(s.up, s.endUp());
                /* 0.10 - THE GENTLE WARM-UP ENDS AT THE WORK (the owner's own rule for somebody
                 * who marks easily); every other warm-up ends under it (D5). */
                if (gentle) {
                    assertTrue(top <= first, at + ": the gentle warm-up pulls " + top
                        + " over the work's first pull " + first);
                } else {
                    assertTrue(top <= workTop, at + ": the warm-up pulls " + top
                        + " over the work's " + workTop);
                }
                if (s.lh > 0) {
                    assertTrue(s.lo <= drop && s.endLo() <= drop, at + ": the warm-up drops to "
                        + s.lo + "/" + s.endLo() + ", above the work's drop of " + drop);
                    assertTrue(s.lo < s.up && s.endLo() < s.endUp(), at + ": no drop at all");
                    assertTrue(s.lo <= Mint.DROP_MAX_KPA, at + ": a drop above the dose floor");
                }
            }
        }
        return ramps;
    }

    /**
     * t10 R-02 - WHERE A ROUTINE'S WORK PEAKS: at its figure - or, where a P2 warm-up stopped
     * short and the holds after it climb on +1 kPa a hold, as far as they get: a short session
     * (a split's second half, five expansion holds) can end under its figure.
     */
    static int carriedPeak(Model m, Model.Routine r, int said) {
        Model.Stage ws = warmStage(r);
        if (ws == null || m.gentleWarmFor(r.trainerTrack) || "Ramp-in".equals(ws.name))
            return said;
        int end = RxBuild.lastPullKpa(m, ws);
        if (end <= 0 || end >= said) return said;
        int holds = 0;
        for (Model.Preset q : m.plan(r)) {
            Model.Stage sq = stageOf(r, q);
            if (q.rest || q.cyclePart || sq == null || sq.rest || sq.retention
                    || sq.colour == Model.STAGE_WARM) continue;
            holds += Math.max(1, Manual.cycles(q.uh, q.lh, (int) (q.durMs / 1000)));
        }
        return Math.min(said, end + holds * Plan.P2_CARRY_KPA);
    }

    /** The deepest pull of everything after the warm-up - the work P2 climbs to. */
    static int workTopOf(Model m, Model.Routine r) {
        int top = 0;
        for (Model.Preset q : m.plan(r)) {
            Model.Stage sq = stageOf(r, q);
            if (q.rest || sq == null || sq.colour == Model.STAGE_WARM) continue;
            top = Math.max(top, q.up);
        }
        return top;
    }

    /** The routine's warm-up stage, or null. */
    static Model.Stage warmStage(Model.Routine r) {
        for (Model.Stage st : r.stages) if (st.colour == Model.STAGE_WARM) return st;
        return null;
    }

    /** A number of minutes as the name spells it: "2", "1.5". */
    private static String minutes(int sec) {
        double m = sec / 60.0;
        return Math.abs(m - Math.rint(m)) < 1e-9 ? String.valueOf((int) Math.rint(m))
                                                 : String.valueOf(m);
    }

    /**
     * 0.10 (the owner's decision) - A PLAN ROUTINE'S NAME STATES WHAT IT RUNS: the count of its
     * work holds - make-up cycles, a ramp's top step and a hybrid's holds included - and their
     * length, "N×Hmin @ ". A traction session is named for its load and has no count.
     */
    static void nameIsWhatRuns(Model m, Model.Routine r, String what) {
        if (Say.isTraction(r)) return;
        int cycles = 0, hold = -1;
        for (Model.Stage st : r.stages) {
            if (st.rest || st.outOfNet()) continue;
            for (String id : st.setIds) {
                Model.Set s = m.set(id);
                if (s == null || s.rest) continue;
                if (hold < 0) hold = s.uh;
                assertEquals(hold, s.uh, what + ": '" + s.name + "' holds " + s.uh
                    + " s where the work holds " + hold + " s");
                cycles += s.dur / s.cycle();
            }
        }
        assertTrue(cycles > 0, what + ": no work holds");
        String says = " · " + cycles + "×" + minutes(hold) + "min @ ";
        assertTrue(r.name.contains(says), what + ": named '" + r.name + "' but runs " + cycles
            + " holds of " + minutes(hold) + " min");
    }

    /**
     * 0.10 - AN ENABLED CHECK PULL NEVER ARMS ABOVE THE WORK: what goes on the wire
     * (Tau#commandedKpa, asked with the work peak as every arming site asks it), what START
     * states as the peak (Model#peak) and the words beside it (Model#checkPullSuffix). Returns
     * the work peak.
     */
    static int checkPullUnderTheWork(Model m, Model.Routine r, String what) {
        r.assess.on = true;                        // the person turned the check on
        int wp = m.workPeakKpa(r);
        int armed = Tau.commandedKpa(r, m.ceilKpa, wp);
        assertTrue(armed > 0, what + ": the check pull does not arm");
        assertTrue(armed <= wp, what + ": the check pull arms at " + armed + " over work of " + wp);
        assertEquals(wp, m.peak(r), what + ": START would state a peak above the work");
        assertEquals("", m.checkPullSuffix(r), what + ": a check pull said to be deeper");
        return wp;
    }

    /* ------------------------------------------------------------------------- the sweep */

    @Test
    void everyBuildCommandsWhatItSaysAndCountsWhatItAsks() {
        long now = System.currentTimeMillis();
        int n = 0, counted = 0, reducedPairs = 0, ramps = 0, layoutRamps = 0, hybrids = 0;
        int primeFallbacks = 0, lightChecks = 0;
        for (Pos p : POSITIONS)
            for (int si = 0; si < SHAPES.length; si++)
                for (int bi = 0; bi < BIASES.length; bi++)
                    for (int v = 0; v < VARIANTS; v++) {
                        // A variant the position cannot show is the plain build again.
                        if (v == 2 && !(p.track == Plan.TRACK_GIRTH_INTERVAL && p.level >= Plan.L3))
                            continue;
                        if (v == 3 && p.track != Plan.TRACK_GIRTH_INTERVAL) continue;
                        if ((v == 1 || v == 4 || v == 10) && p.track == Plan.TRACK_FEEDER) continue;
                        if (v == 9 && SHAPES[si] != Model.Program.WORK_RAMP_IN_SET) continue;
                        if (v >= 5 && p.track == Plan.TRACK_FEEDER) continue;
                        if (v == 7 && !(p.level >= Plan.L3 && (p.track == Plan.TRACK_GIRTH_INTERVAL
                                || p.track == Plan.TRACK_GIRTH_TRADITIONAL))) continue;
                        int fullPeak = -1;
                        for (int red = NONE; red <= TAPER2; red++) {
                            Model m = model(red, now);
                            int[] part = shape(m, p, SHAPES[si], BIASES[bi], v);
                            Mint.Rx rx = rxFor(m, p, now);
                            if (part[1] > 1 && rx.sets < 2) continue;
                            Model.Routine r = build(m, rx, part, now);
                            String what = p.name + " / " + SHAPE_NAMES[si] + " / " + BIAS_NAMES[bi]
                                + " / " + VARIANT_NAMES[v] + " / " + RED_NAMES[red];
                            assertNotNull(r, what);
                            List<Model.Preset> plan = m.plan(r);
                            int peak = workPeak(m, r);
                            assertTrue(peak > 0, what + ": no work");
                            n++;

                            /* D2 - no bias undoes the day's cut. The feeder's taper day is
                             * not modelled here (its prescription is its share of the day's
                             * REDUCED main pressure, which this one-prescription grid does
                             * not build), so the feeder's reduced day is the cylinder's. */
                            double cut = m.reductionKpa(now);
                            boolean feederTaper = p.track == Plan.TRACK_FEEDER && red >= TAPER1;
                            // The biased figure, asked of the bias itself (not the builder).
                            Model.Program prog = m.programFor(p.track);
                            int biased = prog == null ? rx.pressureKpa
                                : Mint.biasKpa(rx.pressureKpa, prog.pressure, rx.level,
                                    TrainerTab.monthIndexNow(m, now), m.ceilKpa);
                            if (red == NONE) {
                                fullPeak = anyPeak(m, r);
                            } else if (!feederTaper) {
                                assertTrue(cut > 0.0, what + ": the day cuts nothing");
                                // At most the full day's biased figure less the day's cut...
                                int bound = Mint.reducedKpa(biased, cut);
                                assertTrue(anyPeak(m, r) <= bound, what + ": pulls "
                                    + anyPeak(m, r) + " on a day that allows " + bound
                                    + " (the unreduced build pulls " + fullPeak + ")");
                                // ...and lighter than the full day's build (unless the full day
                                // itself already sat at or under that figure).
                                assertTrue(anyPeak(m, r) < fullPeak || fullPeak <= bound,
                                    what + ": no lighter than the full day (" + fullPeak
                                    + " -> " + anyPeak(m, r) + ")");
                                assertEquals(0.0, r.netTargetMin, 1e-9,
                                    what + ": a reduced session still asks for net");
                                reducedPairs++;
                            }

                            /* D3 - no work preset repeats a work ramp's final step. */
                            for (int i = 0; i + 1 < plan.size(); i++) {
                                Model.Preset a = plan.get(i), b = plan.get(i + 1);
                                Model.Set s = m.set(a.setId);
                                if (s == null || !s.ramp || !work(r, a)) continue;
                                int steps = Math.max(2, Math.min(Proto.SLOTS, s.steps));
                                if (a.ordinal != steps - 1) continue;
                                ramps++;
                                if (!work(r, b)) continue;
                                assertTrue(b.up != a.up,
                                    what + ": '" + b.label + "' holds " + b.up
                                    + " straight after the ramp's last step at " + a.up);
                                assertFalse(b.label != null && b.label.startsWith("Top-up"),
                                    what + ": a top-up set follows a ramp");
                            }

                            /* D5, D6, D9 - whole cycles, no flat ramp, a warm-up under the work. */
                            layoutRamps += layoutRules(m, r, what);

                            /* D11 - the hybrid is the guidance's. */
                            if (v == 7) hybrids += hybridRules(m, r, rx, part, what);

                            /* D12 - the plan counts exactly the target, where there is one -
                             * every build, long ramps included (D6: whole cycles everywhere). */
                            // (A feeder is not scored - Model#scoringFloorKpa - so it has no
                            // count to compare.)
                            if (r.netTargetMin > 0.0 && p.track != Plan.TRACK_FEEDER) {
                                double floor = m.scoringFloorKpa(r, now);
                                assertFalse(Double.isNaN(floor), what + ": not scored");
                                long got = PlannedTime.underPressureSec(m, r,
                                    floor - m.tupCountTolKpa(floor));
                                assertEquals(Math.round(r.netTargetMin * 60.0), got,
                                    what + ": the plan counts " + got + " s against a "
                                    + r.netTargetMin + " min target");
                                counted++;
                            }

                            /* D4 - the name and the card state what the work commands. */
                            int said = RxBuild.commanded(m, rx, RxBuild.Day.at(m, now)).pressureKpa;
                            assertEquals(said, RxBuild.commandedKpa(m, rx),
                                what + ": the card's figure is not the build's");
                            assertTrue(r.name.contains("@ " + Model.Fmt.p(said)),
                                what + ": named '" + r.name + "', commands " + Model.Fmt.p(said));
                            // Every shape peaks at its figure - a pyramid of two chunks too, on
                            // its last (D8).
                            assertEquals(carriedPeak(m, r, said), peak,
                                what + ": the work's peak is not its figure");

                            /* The check pull never exceeds the session. */
                            assertTrue(r.assess.kpa <= m.workPeakKpa(r), what + ": check pull "
                                + r.assess.kpa + " above the work " + m.workPeakKpa(r));

                            /* 0.10 follow-ups (the owner's rulings). */
                            // The name states the holds this routine runs, make-up included.
                            nameIsWhatRuns(m, r, what);
                            // The warm-up is there - a climb, or the short prime a climb that
                            // does not fit falls back to - wherever one is asked for and a prime
                            // can sit under the work (layoutRules holds every step under it).
                            boolean climbAsked = v == 1 || v == 8;
                            boolean warmAsked = p.track != Plan.TRACK_FEEDER
                                && !(part[1] > 1 && part[0] > 1 && !m.rxSplitWarmBoth);
                            Model.Stage warm = warmStage(r);
                            if (warmAsked && firstWorkPull(m, r) >= 2)
                                assertNotNull(warm, what + ": no warm-up before work starting at "
                                    + firstWorkPull(m, r));
                            // t10 R-01 (K19): the climbing warm-up is P2's on every day - a
                            // light one no longer falls back to a prime.
                            if (climbAsked && warm != null && !m.gentleWarmFor(r.trainerTrack)
                                    && m.set(warm.setIds.get(0)).name.startsWith("Warm-up to"))
                                primeFallbacks++;
                            // An enabled check pull never arms above the work - its 5 kPa floor
                            // included, on a day whose work sits under it.
                            if (checkPullUnderTheWork(m, r, what) < 5) lightChecks++;
                        }
                    }
        assertTrue(primeFallbacks > 20, "too few climbing warm-ups were P2's ("
            + primeFallbacks + ")");
        assertTrue(lightChecks > 20, "too few builds ran under the check pull's floor ("
            + lightChecks + ")");
        assertTrue(n > 1000, "the grid is smaller than it claims (" + n + ")");
        assertTrue(counted > 300, "too few builds had their count checked (" + counted + ")");
        assertTrue(reducedPairs > 700, "too few reduced days were weighed (" + reducedPairs + ")");
        assertTrue(ramps > 100, "too few work ramps were walked (" + ramps + ")");
        assertTrue(layoutRamps > ramps, "too few ramps had their layout checked (" + layoutRamps + ")");
        assertTrue(hybrids > 100, "too few hybrids were built (" + hybrids + ")");
    }

    /* ------------------------------------------ 0.10: every ramp and warm-up setting */

    /**
     * THE OWNER'S RAMP AND GENTLE WARM-UP DECISIONS, OVER EVERY SETTING (0.10): the Ramped work
     * shape at every track position, bias and reduction, under the ramp's start, short climb,
     * step, lighter days and counting - and the gentle warm-up on and off - against the limits
     * every build keeps and the rules these add:
     *
     *   every pull at most the ceiling, "Most you will go to" and 15 inHg; a new person's first
     *       month at most 6 inHg;
     *   no climbing step - of the work, or of the gentle warm-up - more than the person's step;
     *   every warm-up step at or under the work's first pull (the gentle one ending at it);
     *   the plan counts exactly its target, counting the climb or only the work;
     *   and every layout rule of the main sweep (whole cycles, no flat ramp, D3).
     */
    @Test
    void everyRampAndWarmUpSettingKeepsTheLimits() {
        long now = System.currentTimeMillis();
        Pos[] at = { POSITIONS[0], POSITIONS[2], POSITIONS[3], POSITIONS[5], POSITIONS[6],
                     POSITIONS[8], POSITIONS[9] };
        int[] pcts = { 60, 80, 95 };
        int[] shorts = { 0, 2, 3 };
        double[] steps = { 0.3, 1.0 };
        int n = 0, climbs = 0, gentle = 0, uncounted = 0;
        for (Pos p : at)
            for (int bi = 0; bi < BIASES.length; bi++)
                for (int red = NONE; red <= TAPER2; red++)
                    for (int limits = 0; limits < 3; limits++)
                        for (int pct : pcts) for (int sh : shorts) for (double st : steps)
                            for (int flags = 0; flags < 8; flags++) {
                                // A new person starts at Level 1 (TrainerTab#deriveGirth): the
                                // first-month limit is asked where it can apply.
                                if (limits == 2 && p.level != Plan.L1) continue;
                                Model m = model(red, now);
                                // Length's own most (Model#rxLengthMaxKpa) set apart from
                                // girth's, so each track is held to its own.
                                if (limits == 1) {
                                    m.ceilKpa = 30; m.rxWorkMaxKpa = 28; m.rxLengthMaxKpa = 27;
                                }
                                if (limits == 2) {
                                    m.rxNewToPumping = true;
                                    m.trainerMonthsPumping = 0;
                                    m.trainerMonthsAt = now;
                                }
                                int[] part = shape(m, p, Model.Program.WORK_RAMP_IN_SET,
                                                   BIASES[bi], 0);
                                m.rampStartPct = pct; m.rampShortSteps = sh; m.rampStepHg = st;
                                m.rampLighterDays = (flags & 1) == 0;
                                m.rampCountClimb = (flags & 2) == 0;
                                m.marksEasily = (flags & 4) != 0;
                                m.gentleWarmStepHg = st;
                                Mint.Rx rx = rxFor(m, p, now);
                                Model.Routine r = build(m, rx, part, now);
                                String what = p.name + " / " + BIAS_NAMES[bi] + " / "
                                    + RED_NAMES[red] + " / limits " + limits + " / " + pct
                                    + "% / short " + sh + " / " + st + " inHg / flags " + flags;
                                assertNotNull(r, what);
                                n++;
                                int month = TrainerTab.monthIndexNow(m, now);
                                int limit = Math.min(m.ceilKpa, (int) Math.floor(15.0 * Plan.HG));
                                double most = Scale.mostKpa(m, rx.track);
                                if (most > 0)
                                    limit = Math.min(limit, (int) Math.floor(most + 1e-9));
                                if (limits == 2 && month < 1)
                                    limit = Math.min(limit,
                                        (int) Math.floor(Plan.MONTH1_CAP_KPA + 1e-9));
                                List<Model.Preset> plan = m.plan(r);
                                int k = Ramp.stepKpa(st), first = firstWorkPull(m, r);
                                int prev = -1;
                                boolean prevWarm = false, prevFat = false;
                                boolean soft = m.gentleWarmFor(r.trainerTrack);
                                for (Model.Preset q : plan) {
                                    if (q.rest) { prev = -1; continue; }
                                    assertTrue(q.up <= limit, what + ": '" + q.label + "' pulls "
                                        + q.up + " over the limit " + limit);
                                    Model.Stage sq = stageOf(r, q);
                                    boolean warm = sq != null && sq.colour == Model.STAGE_WARM;
                                    boolean fat = sq != null && sq.fatigueBlock;
                                    if (warm) assertTrue(q.up <= (soft ? first : workTopOf(m, r)),
                                        what + ": a warm-up step at " + q.up + " over the work's "
                                        + (soft ? first : workTopOf(m, r)));
                                    // A climb never rises more than its step: the work's ramp
                                    // within a block, and the gentle warm-up's reps. (A rest, the
                                    // fatigue block and the warm-up's hand-over start afresh.)
                                    boolean sameClimb = warm && prevWarm ? soft
                                        : !warm && !prevWarm && !fat && !prevFat;
                                    if (prev >= 0 && q.up > prev && sameClimb)
                                        assertTrue(q.up - prev <= k, what + ": '" + q.label
                                            + "' rises " + (q.up - prev) + " kPa over a step of " + k);
                                    prev = q.up;
                                    prevWarm = warm;
                                    prevFat = fat;
                                    if (sq != null && sq.climb) uncounted++;
                                }
                                layoutRules(m, r, what);
                                if (r.netTargetMin > 0.0) {
                                    double floor = m.scoringFloorKpa(r, now);
                                    assertEquals(Math.round(r.netTargetMin * 60.0),
                                        PlannedTime.underPressureSec(m, r,
                                            floor - m.tupCountTolKpa(floor)),
                                        what + ": the plan does not count its target");
                                    // ...and the target with the climb minutes it leaves out is
                                    // never short of the plan's minutes: a climbing hold net
                                    // cannot count is not made up, it is credited (0.10, the
                                    // owner's decision; Routine#climbUnderLineMin).
                                    assertTrue(r.netTargetMin + r.climbUnderLineMin + 1e-9
                                        >= rx.netTargetMin, what + ": counts " + r.netTargetMin
                                        + " and credits " + r.climbUnderLineMin + " of the plan's "
                                        + rx.netTargetMin + " minutes");
                                }
                                for (Model.Stage sg : r.stages)
                                    for (String id : sg.setIds)
                                        if (m.set(id) != null && m.set(id).name.startsWith("Climb"))
                                            { climbs++; break; }
                                if (m.gentleWarmFor(r.trainerTrack) && warmStage(r) != null) gentle++;
                            }
        assertTrue(n > 10000, "the grid is smaller than it claims (" + n + ")");
        assertTrue(climbs > 3000, "too few builds climbed (" + climbs + ")");
        assertTrue(gentle > 3000, "too few gentle warm-ups were built (" + gentle + ")");
        assertTrue(uncounted > 1000, "too few uncounted climbs were walked (" + uncounted + ")");
    }

    /**
     * D11 - A HYBRID IS THE FATIGUE BLOCK, THEN FIVE-MINUTE HOLDS AT THE FIGURE WITH A REST
     * BETWEEN EACH, IN PLACE OF THE INTERVALS (the guidance's hybrid: six at L3, eight at L4).
     * On the traditional track, whose work already is five-minute holds, it is the track's own
     * prescription. Returns 1.
     */
    private static int hybridRules(Model m, Model.Routine r, Mint.Rx rx, int[] part, String what) {
        int holds = 0, extra = 0, blocks = 0;
        boolean sawFatigue = false, sawWork = false;
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st.rest || st.colour == Model.STAGE_WARM || st.retention) continue;
            if (st.fatigueBlock) {
                assertFalse(sawWork, what + ": the fatigue block runs after the work");
                sawFatigue = true;
                continue;
            }
            sawWork = true;
            blocks++;
            if (blocks > 1)
                assertTrue(i > 0 && r.stages.get(i - 1).rest, what + ": no rest before '"
                    + st.name + "'");
            for (String id : st.setIds) {
                Model.Set s = m.set(id);
                assertFalse(s.ramp, what + ": a hybrid hold is not a ramp");
                assertEquals(Mint.HOLD_TRADITIONAL_SEC, s.uh,
                    what + ": '" + s.name + "' holds " + s.uh + " s, not five minutes");
                if (s.name.startsWith("Top-up")) extra += s.dur / s.cycle();
                else holds += s.dur / s.cycle();
            }
        }
        assertEquals(rx.fatigue && m.programFor(rx.track).fatigue != Model.Program.FAT_OFF,
            sawFatigue, what + ": the fatigue block");
        int want;
        if (rx.track == Plan.TRACK_GIRTH_INTERVAL)
            // EXPECTATION CHANGED (R-27): the guidance's count under the session's time cap.
            want = Math.min(rx.level >= Plan.L4 ? RxBuild.HYBRID_HOLDS_L4 : RxBuild.HYBRID_HOLDS_L3,
                Plan.r2MaxHolds(rx.level, Mint.HOLD_TRADITIONAL_SEC,
                    Mint.r2FatSec(m, rx.track, rx.level)));
        else
            want = rx.sets;       // traditional: its own count of five-minute holds
        /* 0.10 (the owner's decision) - THE CARD AND THE ROW DESCRIBE THE HYBRID IT BUILDS: they
         * count RxBuild#runsAs, which must be the five-minute holds laid out (the whole
         * session's, before a split halves them), never the interval prescription. The name
         * states them too (nameIsWhatRuns, on every build). */
        Mint.Rx runs = RxBuild.runsAs(m, rx);
        assertEquals(want, runs.sets, what + ": the card counts " + runs.sets + " holds");
        assertEquals(Mint.HOLD_TRADITIONAL_SEC, runs.holdSec, what + ": the card's hold");
        assertEquals(rx.pressureKpa, runs.pressureKpa, what + ": the card's prescription");
        if (part[1] > 1) want = Mint.splitSets(want, part[0]);
        assertEquals(want, holds, what + ": " + holds + " five-minute holds");
        assertTrue(r.name.contains(" · " + (holds + extra) + "×5min @ "),
            what + ": the hybrid is named '" + r.name + "'");
        if (r.netTargetMin > 0.0)
            assertEquals((holds + extra) * 5.0, r.netTargetMin, 1e-9,
                what + ": the target is not the holds it lays out");
        return 1;
    }

    /* ------------------------------------------------ D3, by name: the owner's own session */

    @Test
    void theOwnersLengthSessionClimbsOnceWithNoTopUpAfterIt() {
        // Length L1, month 2 (8 hg) in the Ramped Program. 0.10 (the owner's ramp decisions):
        // the climb starts at 80 % of the work (22 under 27), a step of at most 1.0 inHg - 3 kPa -
        // a hold: 22, 25; then the block's holds are at 27, the make-up among them. No set
        // repeats the climb's last step and no "Top-up hold" follows it. (Until 0.10: one ramp
        // of six from the floor, 17, to 27, the make-up its sixth step - audit D3.)
        Model m = model(NONE, System.currentTimeMillis());
        m.programLength.work = Model.Program.WORK_RAMP_IN_SET;
        m.programLength.warm = Model.Program.WARM_RAMP;
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_LENGTH, Plan.L1, 5, 120, 0, 27, false, 10.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        java.util.List<Model.Set> ws = workSets(m, r);
        assertEquals(2, ws.size(), "the climb, then the holds at the work - nothing else");
        Model.Set climb = ws.get(0), work = ws.get(1);
        int cyc = climb.cycle();
        assertTrue(climb.ramp, "the climb is a ramp");
        assertEquals(22, climb.up, "from 80 % of the work");
        assertEquals(25, climb.up2, "to a step under it");
        assertEquals(2, climb.steps);
        assertEquals(2 * cyc, climb.dur, "one whole cycle a step");
        assertFalse(work.ramp);
        assertEquals(27, work.up, "then at the prescription");
        assertEquals(4, work.dur / cyc, "three holds and the one the climb owes");
        assertFalse(work.name.startsWith("Top-up"), "the make-up is more of the work, not a top-up");
        double floor = m.scoringFloorKpa(r, 0L);
        assertEquals(Math.round(r.netTargetMin * 60.0),
            PlannedTime.underPressureSec(m, r, floor - m.tupCountTolKpa(floor)),
            "the make-up step is in the target it is counted against");
        assertEquals(12.0, r.netTargetMin, 1e-9, "six holds, every one of them counted");
    }

    /* ------------------------------------------- D5, D6, D8, D9, D11 by name (0.10) */

    /** The work sets of a routine, in order: not the warm-up, a rest, the fatigue block or the
     *  retention hold. */
    private static java.util.List<Model.Set> workSets(Model m, Model.Routine r) {
        java.util.List<Model.Set> out = new java.util.ArrayList<Model.Set>();
        for (Model.Stage st : r.stages) {
            if (st.rest || st.outOfNet()) continue;
            for (String id : st.setIds) out.add(m.set(id));
        }
        return out;
    }

    private static int countedMatchesTarget(Model m, Model.Routine r, long now, String what) {
        double floor = m.scoringFloorKpa(r, now);
        assertEquals(Math.round(r.netTargetMin * 60.0),
            PlannedTime.underPressureSec(m, r, floor - m.tupCountTolKpa(floor)),
            what + ": the plan does not count its target");
        return 1;
    }

    @Test
    void aTenCycleRampClimbsOneWholeCycleAStepAndHoldsTheRestOnItsTopStep() {
        // Girth L1 week 14: ten sets in one block at 8 hg (27 kPa), Ramped - the block longer than
        // the device's nine-step table. 0.10: it climbs once, 22 and 25, one whole cycle a step,
        // and holds every other hold at 27 - the make-up the climb owes among them (the "top
        // step" of D6 is simply the block's work now).
        long now = System.currentTimeMillis();
        Model m = model(NONE, now);
        m.programGirth.work = Model.Program.WORK_RAMP_IN_SET;
        Mint.Rx rx = rxFor(m, POSITIONS[2], now);
        assertEquals(10, rx.sets);
        assertEquals(27, rx.pressureKpa);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        java.util.List<Model.Set> ws = workSets(m, r);
        assertEquals(2, ws.size(), "the climb and the work - nothing else");
        Model.Set climb = ws.get(0), top = ws.get(1);
        int cyc = climb.cycle();
        assertTrue(climb.ramp, "the climb is a ramp");
        assertTrue(climb.steps <= Proto.SLOTS, "inside the table");
        assertEquals(climb.steps * cyc, climb.dur, "one whole cycle a step");
        assertEquals(22, climb.up, "from 80 % of the work");
        assertTrue(climb.endUp() < 27 && 27 - climb.endUp() <= 3, "to a step under the top");
        assertFalse(top.ramp, "the work is one set");
        assertEquals(27, top.up, "at the prescription");
        assertEquals(0, top.dur % cyc, "whole cycles");
        // EXPECTATION CHANGED (t10 REAL-6): ten prescribed - two of them the climb - and the
        // one the climb owes no longer comes. Ten 2-minute holds are Level 1's 20-minute time
        // cap (R-27), and the make-up counts in it: the counted climb is time under pressure.
        assertEquals(10, climb.dur / cyc + top.dur / cyc);
        assertEquals(20.0, r.netTargetMin, 1e-9);
        countedMatchesTarget(m, r, now, "L1 w14 ramped");
        layoutRules(m, r, "L1 w14 ramped");
    }

    @Test
    void theClimbingWarmUpPickerBuildsP2AndItDropsAsTheWorkDoes() {
        // t10 R-01 (K19): every warm-up choice but "none" builds P2 - the climbing one too. The
        // owner's Length L1 Ramped session with no length cylinder: the expansion warms up to
        // its work (fix b), 12 kPa up a step of 1 inHg, holds 30 -> 60 s, each rep dropping to
        // the work's drop for the work's drop time.
        Model m = model(NONE, System.currentTimeMillis());
        m.programLength.work = Model.Program.WORK_RAMP_IN_SET;
        m.programLength.warm = Model.Program.WARM_RAMP;
        Mint.Rx rx = new Mint.Rx(Plan.TRACK_LENGTH, Plan.L1, 5, 120, 0, 27, false, 10.0);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        Model.Stage warm = r.stages.get(0);
        assertEquals(Model.STAGE_WARM, warm.colour);
        Model.Set w = m.set(warm.setIds.get(0));
        assertTrue(w.ramp);
        assertEquals(12, w.up, "from 3.5 inHg");
        assertEquals(27, w.endUp(), "to the work, 3 kPa a rep");
        assertEquals(30, w.uh, "the first rep holds 30 s");
        assertEquals(60, w.endUh(), "the last 60 s");
        assertEquals(22, firstWorkPull(m, r), "the work's own climb starts at 80 % of it");
        assertEquals(Mint.clampDropKpa(m.rxDropKpa), w.lo, "each rep drops as the work's does");
        assertEquals(Mint.DROP_SEC, w.lh, "for the work's drop time");
        assertEquals(300, w.dur, "about five minutes, to the second");
        layoutRules(m, r, "the owner's Length L1 Ramped session");
    }

    @Test
    void aPyramidOfTwoPeaksOnItsLastAndAnEvenOnePeaksInItsMiddle() {
        int pyr = Model.Program.WORK_PYRAMID;
        assertEquals(17, Mint.chunkUpKpa(pyr, 0, 2, 17, 27));
        assertEquals(27, Mint.chunkUpKpa(pyr, 1, 2, 17, 27), "two chunks: the last is the peak");
        int[] four = { 17, 27, 27, 17 }, five = { 17, 22, 27, 22, 17 };
        for (int i = 0; i < 4; i++) assertEquals(four[i], Mint.chunkUpKpa(pyr, i, 4, 17, 27));
        for (int i = 0; i < 5; i++) assertEquals(five[i], Mint.chunkUpKpa(pyr, i, 5, 17, 27));
        assertEquals(27, Mint.chunkUpKpa(pyr, 0, 1, 17, 27), "one chunk is the prescription");
        // Built: traditional L1 is two five-minute sets - floor, then the figure.
        long now = System.currentTimeMillis();
        Model m = model(NONE, now);
        m.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
        m.programGirth.work = pyr;
        Mint.Rx rx = rxFor(m, POSITIONS[6], now);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        java.util.List<Model.Set> ws = workSets(m, r);
        assertEquals(17, ws.get(0).up);
        assertEquals(rx.pressureKpa, ws.get(1).up, "the pyramid of two reaches its peak");
    }

    @Test
    void aShapeThatCannotActAtThisPrescriptionIsNamedSo() {
        long now = System.currentTimeMillis();
        Model m = model(NONE, now);
        Mint.Rx l1 = rxFor(m, POSITIONS[0], now);          // one block
        Mint.Rx l2 = rxFor(m, POSITIONS[3], now);          // two blocks of five
        assertTrue(RxBuild.workShapeActs(m, l1, Model.Program.WORK_FIXED));
        assertFalse(RxBuild.workShapeActs(m, l1, Model.Program.WORK_ASCENDING),
            "one block has nothing to climb across");
        assertFalse(RxBuild.workShapeActs(m, l1, Model.Program.WORK_PYRAMID));
        assertTrue(RxBuild.workShapeActs(m, l1, Model.Program.WORK_RAMP_IN_SET));
        assertTrue(RxBuild.workShapeActs(m, l2, Model.Program.WORK_ASCENDING));
        assertTrue(RxBuild.workShapeActs(m, l2, Model.Program.WORK_PYRAMID),
            "two blocks: the pyramid peaks on the second");
        // Under a Gentle bias from the plan's lowest L1 figure the work sits at the floor (6 inHg
        // less one). Until 0.10 a ramp from the floor was flat there (D9); a ramp now climbs
        // from 80 % of the work, so it acts.
        m.programGirth.pressure = Model.Program.PRESS_GENTLE;
        assertTrue(RxBuild.workShapeActs(m, l1, Model.Program.WORK_RAMP_IN_SET));
        // A traditional prescription is blocks of one hold: nothing climbs inside one hold.
        Model trad = model(NONE, now);
        trad.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
        assertFalse(RxBuild.workShapeActs(trad, rxFor(trad, POSITIONS[6], now),
            Model.Program.WORK_RAMP_IN_SET), "one hold a block cannot climb");
        // A lighter day the person runs flat (Model#rampLighterDays off) plays fixed holds.
        Model light = model(TAPER1, now);
        light.rampLighterDays = false;
        assertFalse(RxBuild.workShapeActs(light, rxFor(light, POSITIONS[0], now),
            Model.Program.WORK_RAMP_IN_SET), "a lighter day run flat");
        light.rampLighterDays = true;
        assertTrue(RxBuild.workShapeActs(light, rxFor(light, POSITIONS[0], now),
            Model.Program.WORK_RAMP_IN_SET), "a lighter day keeps its ramp by default");
        // The traction coda is one set: a pyramid cannot peak mid-way in it.
        Model t = rack(NONE, now);
        Mint.Rx lx = rxFor(t, LENGTH_L1, now);
        assertFalse(RxBuild.workShapeActs(t, lx, Model.Program.WORK_PYRAMID));
        assertTrue(RxBuild.workShapeActs(t, lx, Model.Program.WORK_ASCENDING));
        // Asking builds nothing into the model it was asked of.
        int routines = t.routines.size(), sets = t.sets.size();
        RxBuild.workShapeActs(t, lx, Model.Program.WORK_RAMP_IN_SET);
        assertEquals(routines, t.routines.size());
        assertEquals(sets, t.sets.size());
    }

    @Test
    void aRampWithNothingToClimbIsAFixedSet() {
        // 0.10: a Ramped build climbs from 80 % of the day's work, so what leaves it nothing to
        // climb is no longer the floor (D9's old case) but: a lighter day the person runs flat,
        // a start at the work (a work of 2 kPa starts at 2), or blocks of single holds.
        long now = System.currentTimeMillis();
        for (int red = NONE; red <= TAPER2; red++) {
            Model m = model(red, now);
            m.programGirth.work = Model.Program.WORK_RAMP_IN_SET;
            m.programGirth.pressure = Model.Program.PRESS_GENTLE;
            m.rampLighterDays = false;
            Mint.Rx rx = rxFor(m, POSITIONS[0], now);
            Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
            boolean climbs = false;
            for (Model.Set s : workSets(m, r)) {
                if (s.name.startsWith("Climb")) climbs = true;
                if (red != NONE)
                    assertFalse(s.ramp || s.name.startsWith("Climb"), RED_NAMES[red]
                        + ": '" + s.name + "' climbs on a lighter day run flat");
            }
            if (red == NONE) assertTrue(climbs, "a full day still climbs");
        }
        Model m = model(NONE, now);
        m.programGirth.work = Model.Program.WORK_RAMP_IN_SET;
        Model.Routine low = m.routine(RxBuild.routineFromRx(m,
            new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 5, 120, 0, 2, false, 10.0)));
        for (Model.Set s : workSets(m, low))
            assertFalse(s.ramp, "nothing to climb under 2 kPa: '" + s.name + "'");
    }

    /* ------------------------------------------- the owner's rulings on T1-T3 (0.10) */

    @Test
    void theWarmUpIsP2OnEveryDayAndNeverDeeperThanTheWork() {
        // t10 R-01: whatever the warm-up picker (the Shape screen's ramp toggle, the Program's
        // climb), whatever the day's cut and bias, the warm-up is P2's - six reps from 3.5 inHg
        // to the day's work, never past it - and a light day no longer falls back to a prime.
        long now = System.currentTimeMillis();
        int[] modes = { -1, Model.Program.WARM_RAMP, Model.Program.WARM_SHORT };
        int n = 0;
        for (int mode : modes)
            for (int red = NONE; red <= TAPER2; red++)
                for (int b : BIASES) {
                    Model m = model(red, now);
                    if (mode < 0) m.rxWarmRamp = true; else m.programGirth.warm = mode;
                    m.programGirth.pressure = b;
                    Mint.Rx rx = rxFor(m, POSITIONS[0], now);
                    Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                    String what = "girth L1 / warm " + mode + " / bias " + b + " / " + RED_NAMES[red];
                    Model.Stage warm = warmStage(r);
                    assertNotNull(warm, what + ": the warm-up vanished");
                    int work = workTopOf(m, r);
                    int[] want = RxBuild.p2Reps(work);
                    Model.Set w = m.set(warm.setIds.get(0));
                    assertEquals(want[0], w.up, what + ": P2's first rep");
                    assertEquals(want[want.length - 1], RxBuild.lastPullKpa(m, warm),
                                 what + ": P2's last rep");
                    assertTrue(RxBuild.lastPullKpa(m, warm) <= work, what + ": past the work");
                    layoutRules(m, r, what);
                    n++;
                }
        assertTrue(n >= 27, "the grid (" + n + ")");
    }

    @Test
    void aTractionSessionWarmsUpInTheTractionTubeToEightyPerCentOfItsPull() {
        // The pulls are the LOAD's: at every load, light or not, the warm-up is P2's, straight
        // after the release, in the traction tube, ending at 80 % of the pull (S18, t10 R-03).
        long now = System.currentTimeMillis();
        double[] loads = { 1.0, 2.0, 4.0 };
        for (double lb : loads)
            for (int red = NONE; red <= TAPER2; red++) {
                Model m = rack(red, now);
                m.trainerLength.loadLb = lb;
                m.programLength.warm = Model.Program.WARM_RAMP;
                Mint.Rx rx = rxFor(m, LENGTH_L1, now);
                Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                String what = "traction " + lb + " lb / " + RED_NAMES[red];
                assertTrue(Say.isTraction(r), what);
                Model.Stage warm = warmStage(r);
                assertNotNull(warm, what + ": the traction session opens on its first pull cold");
                assertEquals(1, r.stages.indexOf(warm), what + ": straight after the release");
                assertEquals(m.cylinderForWork(true, m.girthForTraction()), warm.cylinderId,
                    what + ": in the traction tube");
                int pull = 0;
                for (Model.Preset q : m.plan(r)) {
                    Model.Stage sq = stageOf(r, q);
                    if (!q.rest && sq != null && sq.traction) pull = Math.max(pull, q.up);
                }
                int[] want = RxBuild.p2Reps(RunShape.warmCapKpa(pull));
                assertEquals(want[want.length - 1], RxBuild.lastPullKpa(m, warm),
                    what + ": 80 % of the pull of " + pull);
                assertTrue(RxBuild.lastPullKpa(m, warm) <= pull, what + ": past the pull");
                layoutRules(m, r, what);
            }
    }

    @Test
    void aReducedTractionSessionAsksForNoNet() {
        long now = System.currentTimeMillis();
        for (int red = NONE; red <= TAPER2; red++) {
            Model m = rack(red, now);
            Mint.Rx rx = rxFor(m, LENGTH_L1, now);
            Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
            if (red == NONE)
                assertEquals(RxBuild.CODA_SETS * (RxBuild.CODA_HOLD_SEC / 60.0), r.netTargetMin,
                    1e-9, "a full day asks for the coda's net");
            else
                assertEquals(0.0, r.netTargetMin, 1e-9,
                    RED_NAMES[red] + ": a reduced day is scored as under-delivery");
        }
    }

    @Test
    void theCheckPullNeverArmsAboveAWorkUnderItsFloor() {
        // Girth L1 at 6 hg, Gentle, first taper day: the work runs about 3 kPa, under the check
        // pull's 5 kPa floor - which used to arm the enabled check above the whole session.
        // (At 7 hg until 0.10: Gentle was the band floor then; it is the plan less one inHg now,
        // so the plan's lowest L1 figure is the one whose Gentle taper day runs this light.)
        long now = System.currentTimeMillis();
        Model m = model(TAPER1, now);
        m.programGirth.pressure = Model.Program.PRESS_GENTLE;
        Mint.Rx rx = rxFor(m, POSITIONS[0], now);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        int wp = checkPullUnderTheWork(m, r, "Gentle taper L1");
        assertTrue(wp < 5, "the work sits under the floor here (" + wp + ")");
        assertEquals(wp, Tau.commandedKpa(r, m.ceilKpa, wp), "it arms at the work itself");
        // A routine somebody built by hand keeps what its editor allows.
        r.trainerTrack = Model.TRAINER_TRACK_NONE;
        assertEquals(Tau.commandedKpa(r, m.ceilKpa), Tau.commandedKpa(r, m.ceilKpa, wp));
    }

    @Test
    void theHybridIsTheGuidancesHybrid() {
        long now = System.currentTimeMillis();
        Pos[] at = { POSITIONS[4], POSITIONS[5] };
        int[] holds = { 5, 7 };          // EXPECTATION CHANGED (R-27): 6 / 8 under the cap
        for (int i = 0; i < at.length; i++) {
            Model m = model(NONE, now);
            m.trainerGirthHybrid = true;
            Mint.Rx rx = rxFor(m, at[i], now);
            Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
            java.util.List<String> order = new java.util.ArrayList<String>();
            for (Model.Stage st : r.stages)
                order.add(st.rest ? "rest" : st.colour == Model.STAGE_WARM ? "warm"
                    : st.fatigueBlock ? "fatigue" : "hold");
            java.util.List<String> want = new java.util.ArrayList<String>();
            want.add("warm"); want.add("fatigue"); want.add("rest");
            for (int h = 0; h < holds[i]; h++) { if (h > 0) want.add("rest"); want.add("hold"); }
            assertEquals(want, order, at[i].name + ": the fatigue block, then the holds, a rest between");
            for (Model.Set s : workSets(m, r)) {
                assertEquals(Mint.HOLD_TRADITIONAL_SEC, s.uh, "five-minute holds");
                assertEquals(s.cycle(), s.dur, "one hold a block");
                assertEquals(rx.pressureKpa, s.up, "at the prescription");
            }
            assertEquals(holds[i] * 5.0, r.netTargetMin, 1e-9, "the target is the holds");
            countedMatchesTarget(m, r, now, at[i].name + " hybrid");
            assertEquals(m.restSecFor(rx.track), r.stages.get(2).restSec, "the track's own rest");
        }
        // On the traditional track the work already is five-minute holds after the fatigue
        // block: the hybrid builds exactly what the track prescribes.
        Model a = model(NONE, now), b = model(NONE, now);
        a.trainerGirthStyle = b.trainerGirthStyle = Plan.TRACK_GIRTH_TRADITIONAL;
        b.trainerGirthHybrid = true;
        Mint.Rx rx = rxFor(a, POSITIONS[8 - 1], now);
        assertEquals(SavedMint.workPrint(a, a.routine(RxBuild.routineFromRx(a, rx))),
            SavedMint.workPrint(b, b.routine(RxBuild.routineFromRx(b, rx))));
    }

    @Test
    void aDoubledTractionCodaStepsInWholeCyclesAndCountsItsTarget() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (int c : SHAPES)
            for (int b : BIASES)
                for (int red = NONE; red <= TAPER2; red++) {
                    Model m = rack(red, now);
                    m.trainerLength.focusBlockUntilMs = now + 7 * DAY;
                    m.programLength.work = c;
                    m.programLength.pressure = b;
                    m.programLength.warm = Model.Program.WARM_RAMP;
                    Mint.Rx rx = rxFor(m, LENGTH_L1, now);
                    Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                    String what = "focus coda " + c + " / bias " + b + " / " + RED_NAMES[red];
                    layoutRules(m, r, what);
                    int cycles = 0;
                    for (Model.Set s : workSets(m, r)) cycles += s.dur / s.cycle();
                    assertEquals(RxBuild.CODA_SETS * 2, cycles, what + ": the doubled coda");
                    // Counted on a full day; a reduced day asks for no net at all (0.10).
                    if (red == NONE) countedMatchesTarget(m, r, now, what);
                    else assertEquals(0.0, r.netTargetMin, 1e-9,
                        what + ": a reduced girth-focus coda still asks for net");
                    checkPullUnderTheWork(m, r, what);
                    n++;
                }
        assertEquals(4 * 3 * 4, n);
    }

    /* --------------------------------------- D2 by name: Firm on a taper day, and the coda */

    @Test
    void firmOnATaperDayRunsTheBandTopLessTheCut() {
        long now = System.currentTimeMillis();
        for (int red = CUT; red <= TAPER2; red++) {
            Model full = model(NONE, now);
            full.programGirth.pressure = Model.Program.PRESS_FIRM;
            Mint.Rx rx = rxFor(full, POSITIONS[0], now);
            int top = workPeak(full, full.routine(RxBuild.routineFromRx(full, rx)));
            Model m = model(red, now);
            m.programGirth.pressure = Model.Program.PRESS_FIRM;
            Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
            double cut = m.reductionKpa(now);
            assertEquals(Mint.reducedKpa(top, cut), workPeak(m, r),
                RED_NAMES[red] + ": Firm is the band's top less the day's cut");
            assertTrue(workPeak(m, r) < top, RED_NAMES[red] + ": and lighter than the full day");
        }
    }

    private static Model rack(int reduction, long now) {
        Model m = model(reduction, now);
        Model.Reading r = new Model.Reading();
        r.ts = 1788440800000L;
        r.method = Model.Reading.METHOD_MSEG;
        r.gir = 12.7;
        m.measLog.all.add(r);
        Model.Cylinder l = new Model.Cylinder();
        l.id = "L"; l.label = "Length tube"; l.role = Model.Cylinder.ROLE_LENGTH; l.boreCm = 4.0; l.lengthCm = 23.0;
        Model.Cylinder g = new Model.Cylinder();
        g.id = "G"; g.label = "Girth tube"; g.boreCm = 4.5; g.lengthCm = 23.0;
        m.cylinders.add(l);
        m.cylinders.add(g);
        m.activeCylinder = 0;
        m.trainerLength.loadLb = 4.0;
        m.trainerLength.strainSets = 3;
        return m;
    }

    /** The coda's own blocks: the stage after the changeover, or the girth-focus coda. */
    private static int codaPeak(Model m, Model.Routine r) {
        int pk = 0;
        for (Model.Preset p : m.plan(r)) {
            Model.Stage st = stageOf(r, p);
            if (st == null || p.rest || st.rest || st.traction
                    || st.colour == Model.STAGE_WARM) continue;
            if (p.up > pk) pk = p.up;
        }
        return pk;
    }

    @Test
    void theTractionCodaIsBiasedFirstThenCutAndSaysSo() {
        long now = System.currentTimeMillis();
        int[] coda = { Model.Program.WORK_FIXED, Model.Program.WORK_RAMP_IN_SET,
                       Model.Program.WORK_ASCENDING };
        int n = 0;
        for (int c : coda)
            for (int b : BIASES) {
                int full = -1;
                for (int red = NONE; red <= TAPER2; red++) {
                    Model m = rack(red, now);
                    m.programLength.work = c;
                    m.programLength.pressure = b;
                    Mint.Rx rx = rxFor(m, LENGTH_L1, now);
                    Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
                    String what = "coda " + c + " / bias " + b + " / " + RED_NAMES[red];
                    assertTrue(Say.isTraction(r), what);
                    int pk = codaPeak(m, r);
                    int said = RxBuild.commandedKpa(m, rx);
                    assertEquals(said, pk, what + ": the coda does not run the card's figure");
                    boolean named = false;
                    for (Model.Stage st : r.stages)
                        if (st.name != null && st.name.endsWith("expansion at " + Model.Fmt.p(said)))
                            named = true;
                    assertTrue(named, what + ": the changeover names another pressure");
                    if (red == NONE) {
                        full = pk;
                        double floor = m.scoringFloorKpa(r, now);
                        assertEquals(Math.round(r.netTargetMin * 60.0),
                                PlannedTime.underPressureSec(m, r, floor - m.tupCountTolKpa(floor)),
                                what + ": the coda's count is not its target");
                    } else {
                        double cut = m.reductionKpa(now);
                        assertTrue(pk <= Mint.reducedKpa(full, cut) && pk < full,
                            what + ": the coda pulls " + pk + " against a full day's " + full);
                        // 0.10 - a reduced day asks for no net, the traction session's
                        // expansion included, so it is never scored as under-delivery.
                        assertEquals(0.0, r.netTargetMin, 1e-9,
                            what + ": a reduced traction session still asks for net");
                    }
                    checkPullUnderTheWork(m, r, what);
                    assertTrue(r.assess.kpa <= m.workPeakKpa(r), what + ": check pull above the work");
                    // D5, D6, D9: the warm-up in the traction tube ends under the first pull.
                    layoutRules(m, r, what);
                    n++;
                }
            }
        assertEquals(3 * 3 * 4, n);
    }

    /* ------------------------------------ the check pull comes down with a rewrite */

    @Test
    void aRewriteForALighterDayBringsTheCheckPullDownWithTheWork() {
        long now = System.currentTimeMillis();
        int n = 0, lowered = 0;
        for (Pos p : POSITIONS)
            for (int si = 0; si < SHAPES.length; si++)
                for (int bi = 0; bi < BIASES.length; bi++)
                    for (int red = CUT; red <= TAPER2; red++) {
                        Model m = model(NONE, now);
                        int[] part = shape(m, p, SHAPES[si], BIASES[bi], 0);
                        Mint.Rx rx = rxFor(m, p, now);
                        Model.Routine r = build(m, rx, part, now);
                        r.assess.on = true;          // the person turned the check on
                        int was = r.assess.kpa;
                        reduce(m, red, now);
                        String what = p.name + " / " + SHAPE_NAMES[si] + " / "
                            + BIAS_NAMES[bi] + " / " + RED_NAMES[red];
                        assertTrue(SavedMint.rewrite(m, r, rx, 0, 0), what);
                        int wp = m.workPeakKpa(r);
                        assertTrue(r.assess.kpa <= wp, what + ": the check pulls "
                            + r.assess.kpa + " over a rewritten work peak of " + wp);
                        assertTrue(Tau.commandedKpa(r, m.ceilKpa) <= Math.max(5, wp),
                            what + ": the armed check pull is above the work");
                        assertTrue(r.assess.kpa <= was, what + ": a rewrite raised the check");
                        if (r.assess.kpa < was) lowered++;
                        n++;
                    }
        assertEquals(POSITIONS.length * 4 * 3 * 3, n);
        assertTrue(lowered > 0, "no rewrite ever had a check pull to lower");
    }
}
