package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * R11-3 - PLACED BY THE SESSION YOU RUN NOW (the owner's pick, option A: "just positioning them
 * based on how long their current routine is, not with any specifics").
 *
 * The setup used to place each track mainly by the months pumped, and two people with seven
 * months can be running very different sessions. It now asks, per track, one question - how
 * long your current session of that track is, in minutes - and places the track at the level
 * and week whose session, as the plan writes it (that track's own minutes: the routine the plan
 * would build there, warm-up, fatigue block and rests included), is the closest to the answer
 * without going above it.
 *
 *  - 0 ("I don't do this yet") or new to pumping: Level 1, week 1 (girth), the calendar's first
 *    strain sets (length).
 *  - An answer under every session the plan writes: the first one.
 *  - An answer past the top: the highest level the months allow.
 *  - Unanswered: the months placement, as before.
 *
 * THE MONTHS STAY FOR THE GATES THE GUIDANCE TIES TO THEM: Level 3 needs month 6 and Level 4
 * month 12, so they cap the girth candidates; length keeps its month table's level and is placed
 * by its strain sets, which is what its session's length grows by. A working pressure under the
 * Level 2 floor (8 inHg) keeps girth at Level 1, as the months placement does. The pressure and
 * the load still come from the setup's own answers (TrainerTab#deriveGirthAt).
 *
 * The candidates are built in a SCRATCH model the caller hands over (never the person's own):
 * the setup's answers so far, through the same Mint#prescribe and RxBuild path the plan uses,
 * so the minutes compared are the minutes the plan will write. Pure: no Android.
 */
public final class Placement {
    private Placement() { }

    /** The minutes question left unanswered: placed by the months, as before. */
    public static final int NOT_ANSWERED = -1;

    /** A girth spot: the level and its week index (TrainerTab.Derived#weekIndex's meaning), and
     *  the minutes of the session the plan writes there. */
    public static final class Spot {
        public final int level, week;
        public final double minutes;
        Spot(int level, int week, double minutes) {
            this.level = level; this.week = week; this.minutes = minutes;
        }
    }

    /** The highest girth level the months allow: Level 4 from month 12, Level 3 from month 6,
     *  else Level 2; Level 1 with a working pressure under the Level 2 floor. */
    public static int maxGirthLevel(int months, double pressureKpa) {
        if (!Plan.pressureReachedKpa(pressureKpa, Plan.L234_FLOOR_KPA)) return Plan.L1;
        int month = Plan.monthIndex(Math.max(0, months) * 4);
        if (month >= Plan.L3_GATE_MONTH) return Plan.L4;
        if (month >= Plan.L2_GATE_MONTH) return Plan.L3;
        return Plan.L2;
    }

    /**
     * Where the girth track starts for an answer of `answerMin` minutes, or null when it is not
     * answered (the months placement applies). `scratch` is the setup's scratch model (the
     * person's Programs, hold lengths, marks and limits as answered); it is written into.
     */
    public static Spot girth(Model scratch, int style, int months, boolean isNew,
                             double pressureKpa, double statedMaxKpa, int answerMin) {
        if (answerMin == NOT_ANSWERED || answerMin < 0) return null;
        if (isNew || answerMin == 0) return new Spot(Plan.L1, 1, 0.0);
        List<Spot> c = girthCandidates(scratch, style, months, isNew, pressureKpa, statedMaxKpa);
        return pick(c, answerMin);
    }

    /** The closest to `answerMin` without going above it; the first when every one is above;
     *  the last (the highest the months allow) when none is. */
    static Spot pick(List<Spot> c, double answerMin) {
        if (c.isEmpty()) return new Spot(Plan.L1, 1, 0.0);
        Spot best = null;
        boolean all = true;
        for (int i = 0; i < c.size(); i++) {
            Spot s = c.get(i);
            if (s.minutes <= answerMin + 1e-9) {
                if (best == null || s.minutes > best.minutes + 1e-9) best = s;
            } else all = false;
        }
        if (all) return c.get(c.size() - 1);
        return best == null ? c.get(0) : best;
    }

    /**
     * Every place the girth track can start, in the plan's order, with its session's minutes:
     * interval - Level 1's training weeks, Level 2's, then Level 3 and Level 4; traditional -
     * Level 1's weeks 1 to 12 (its holds grow by the week), then Level 2, 3 and 4 at their first
     * week - each level only as the months allow.
     */
    public static List<Spot> girthCandidates(Model scratch, int style, int months, boolean isNew,
                                             double pressureKpa, double statedMaxKpa) {
        List<Spot> out = new ArrayList<Spot>();
        int maxLv = maxGirthLevel(months, pressureKpa);
        boolean trad = style == Plan.TRACK_GIRTH_TRADITIONAL;
        if (trad) {
            for (int w = 1; w <= 12; w++)
                out.add(girthSpot(scratch, style, months, isNew, pressureKpa, statedMaxKpa, 1, w));
            for (int lv = Plan.L2; lv <= maxLv; lv++)
                out.add(girthSpot(scratch, style, months, isNew, pressureKpa, statedMaxKpa, lv, 1));
            return out;
        }
        for (int i = 0; i < Plan.GIRTH_INTERVAL_L1.length; i++)
            if (!Plan.GIRTH_INTERVAL_L1[i].deload && Plan.GIRTH_INTERVAL_L1[i].sets > 0)
                out.add(girthSpot(scratch, style, months, isNew, pressureKpa, statedMaxKpa,
                                  Plan.L1, i + 1));
        if (maxLv >= Plan.L2)
            for (int i = 0; i < Plan.GIRTH_INTERVAL_L2.length; i++)
                if (!Plan.GIRTH_INTERVAL_L2[i].deload && Plan.GIRTH_INTERVAL_L2[i].sets > 0)
                    out.add(girthSpot(scratch, style, months, isNew, pressureKpa, statedMaxKpa,
                                      Plan.L2, i + 1));
        for (int lv = Plan.L3; lv <= maxLv; lv++)
            out.add(girthSpot(scratch, style, months, isNew, pressureKpa, statedMaxKpa, lv, 0));
        return out;
    }

    /** The session the plan writes at (`level`, `week`), in minutes - as the setup's Confirm
     *  writes the position (TrainerTab#deriveGirthAt, the plan's figure for the level). */
    private static Spot girthSpot(Model scratch, int style, int months, boolean isNew,
                                  double pressureKpa, double statedMaxKpa, int level, int week) {
        TrainerTab.Derived d = TrainerTab.deriveGirthAt(style, months, pressureKpa, isNew,
            scratch.ceilKpa, statedMaxKpa, level, week);
        double plan = Scale.setupPlanKpa(style, level, d.monthIndex, d.pressureKpa, isNew);
        scratch.trainerGirth.level = level;
        scratch.trainerGirth.weekIndex = week;
        scratch.trainerGirth.setWorkingPressure(plan, 1L);
        scratch.trainerGirth.offsetKpa = 0.0;
        Mint.Rx rx = Mint.prescribe(style, level, week, plan, d.monthIndex, scratch.ceilKpa,
            0, 0, null, Scale.limitsOf(scratch, style), 0, Mint.r2FatSec(scratch, style, level));
        Model.Routine r = scratch.routine(RxBuild.routineFromRx(scratch, rx));
        double min = r == null ? 0.0 : scratch.routineSec(r) / 60.0;
        return new Spot(level, week, min);
    }

    /**
     * The strain sets the length track starts at for an answer of `answerMin` minutes: the most
     * whose length session, as the plan writes it, is not longer than the answer - the
     * calendar's first count for 0 or somebody new, or below every session. -1 when it is not
     * answered or there is no length cylinder to pull with (the strain sets do not shape an
     * expansion session): the setup's own count applies (LengthTrack#atSetup). `scratch` has
     * the length position written as Confirm writes it (level, pressure, load); it is written
     * into.
     */
    public static int lengthStrainSets(Model scratch, boolean isNew, int months, int answerMin) {
        if (answerMin == NOT_ANSWERED || answerMin < 0) return -1;
        if (scratch == null || !scratch.lengthPulls()) return -1;
        if (isNew || answerMin == 0) return Plan.LENGTH_STRAIN_SETS_START;
        int best = Plan.LENGTH_STRAIN_SETS_START;
        for (int n = Plan.LENGTH_STRAIN_SETS_START; n <= Plan.LENGTH_STRAIN_SETS_MAX; n++) {
            if (lengthSessionMin(scratch, months, n) <= answerMin + 1e-9) best = n;
        }
        return best;
    }

    /**
     * The length session the plan writes with `strainSets` strain sets, in minutes - the one
     * definition the placement compares the answer with (#lengthStrainSets), and the figure the
     * setup's Confirm states beside it (FIX11, device recheck EMU13). `scratch` has the length
     * position written as Confirm writes it; its strain sets are set to `strainSets`.
     */
    public static double lengthSessionMin(Model scratch, int months, int strainSets) {
        if (scratch == null) return 0.0;
        int month = Plan.monthIndex(Math.max(0, months) * 4);
        Model.TrainerTrackState st = scratch.trainerLength;
        st.setStrainSets(strainSets, 1L);
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_LENGTH, st.level, st.weekIndex,
            st.pressureKpa, month, scratch.ceilKpa, 0, 0, null,
            Scale.limitsOf(scratch, Plan.TRACK_LENGTH));
        Model.Routine r = scratch.routine(RxBuild.routineFromRx(scratch, rx));
        double min = r == null ? 0.0 : scratch.routineSec(r) / 60.0;
        if (r != null) scratch.routines.remove(r);
        return min;
    }

    /** The training weeks the length calendar starts from for `strainSets` placed: its strain
     *  sets go on from the count placed (one more every Plan#LENGTH_STRAIN_ADD_WEEKS training
     *  weeks), not from the calendar's first two. */
    public static int strainCalendarWeeks(int strainSets) {
        return Math.max(0, strainSets - Plan.LENGTH_STRAIN_SETS_START)
            * Plan.LENGTH_STRAIN_ADD_WEEKS;
    }
}
