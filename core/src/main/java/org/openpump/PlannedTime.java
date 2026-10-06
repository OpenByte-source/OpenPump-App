package org.openpump;

import java.util.List;

/**
 * B3 - WHAT A SESSION'S TIME IS COMPARED WITH, AND IN WHICH COUNT.
 *
 * Found on a device: a 9:30 routine's summary read "net at pressure 1.4 min of 20.0 min
 * planned · −93%". The 20 minutes was the training LEVEL's per-session goal
 * ({@link Plan#netMilestoneMin}), and nothing that routine had planned - so a routine that
 * did what it said read as a 93 % failure, and one that ran over could read as a shortfall.
 *
 * TWO QUESTIONS, TWO LINES. "Did this session deliver what its routine planned?" is
 * answered against the routine; "how does it sit against my level?" is answered against the
 * level, on its own line, and that line is never called "planned".
 *
 * AND THE PLAN IS COUNTED THE WAY THE SESSION WAS. Set timing (Settings › How a run behaves)
 * has two counts:
 *   - BY THE CLOCK, a set runs its minutes on the wall clock. The comparison is the elapsed
 *     against the routine's duration - rests, warm-up and the after-assessment pull in both,
 *     because the elapsed is measured across all of them. It sits on the duration row, which
 *     already names that count; a second "time" row would print the same 8:21 twice.
 *   - AT PRESSURE ONLY, a set's clock moves only while the cuff is at pressure. The
 *     comparison is the net time under pressure the session delivered against the time the
 *     routine planned at its working pressure: the hold half of each work cycle, outside
 *     rests and outside every stage net does not count ({@link Model.Stage#outOfNet}), at a
 *     pressure net counts - the same exclusions {@link Session#netGrossTupSec} applies to
 *     what was delivered. C6: in a session the plan scores, "at pressure" for the set clock
 *     IS the net's own test - the same line, the same drop half ({@link TupClock}) - so a
 *     run whose sets all finished delivers this plan, and the row reads as planned.
 *
 * WHERE THERE IS NO UNDER-PRESSURE FIGURE there is nothing to compare in that count. Net is
 * recorded only for a session the plan scores (a Trainer routine, or a one-off counted toward
 * training), so a routine outside the plan states the clock comparison, which is the one
 * count it has, under the row that says it is the clock.
 *
 * PURE, so the arithmetic that decides what a summary claims is pinned by PlannedTimeTest
 * rather than by eye on a phone.
 */
public final class PlannedTime {
    private PlannedTime() { }

    /** The summary rows, with the words each one uses. A null label means "no such row". */
    public static final class Rows {
        /** The duration row's value: the clock, with the clock plan beside it when the clock
         *  is the count being compared. */
        public String duration;
        /** The under-pressure comparison, when that is the count. */
        public String tupLabel, tupValue;
        /** The level's own goal, stated once, when the session was scored against a level. */
        public String levelLabel, levelValue;
        /** The cycles row's value - "8 of 14 planned" - or null when the record does not
         *  hold both halves. */
        public String cycles;
        /** D2 - "2 sets reached their time limit: …", or null when none did or the record
         *  does not say (Sess#setsAtLimit). */
        public String limit;
        /** Which count the comparison is in, and its figures, for the reader and the tests.
         *  plannedSec <= 0 means there was nothing to compare against. */
        public boolean underPressure;
        public double gotSec, plannedSec;
        /** The difference, rounded; null when there is no comparison. */
        public Integer pct;
    }

    public static final String LABEL_UNDER_PRESSURE = "time under pressure";

    /* ============================================================ what a routine plans */

    /**
     * THE CLOCK PLAN, in seconds: every preset the routine sends, rests and warm-up included,
     * plus the after-assessment pull. The before-pull runs outside Session#beginRun, so it is
     * in neither this nor the elapsed; the after-pull runs inside, so it is in both -
     * {@link Tau#trackedAssessSec} is that one decision. (The vent before the after-pull is
     * inside the elapsed and in no plan: it ends when the pump reports the cuff open, which
     * nobody can plan - up to {@link Tau#ASSESS_VENT_WINDOW_MS}, which is what
     * {@link #CLOCK_SLACK_SEC} allows a finished run.)
     */
    public static long clockSec(Model m, Model.Routine r) {
        if (m == null || r == null) return 0L;
        return m.durationMs(r) / 1000 + Tau.trackedAssessSec(r);
    }

    /**
     * THE UNDER-PRESSURE PLAN, in seconds: the hold half of every commanding preset outside a
     * rest and outside an out-of-net stage, whose pressure reaches the line net is counted
     * from. Read off a fresh {@link Model#plan} of the routine, never the run's live list,
     * which +30 s and live edits rewrite as the run goes - the plan is what the routine asked
     * for, so an extension reads as the over-run it is.
     *
     * `countFromKpa` IS THAT LINE: the floor this session's net is scored against, less the
     * counting tolerance (Session#netGrossTupSec counts a frame at or above exactly that).
     * A hold planned below it can never count, however well it runs - found on the device:
     * Pulse Session's 14 kPa "Gentle Hold" under Level 1's 16.9 kPa floor would have made a
     * run that did exactly what it planned read "2.2 min of 5.6 min planned · −61%".
     * 0 counts every hold, for a session nothing scores.
     *
     * For a Trainer routine this is its prescribed net ({@link Model.Routine#netTargetMin}):
     * N holds of holdSec at the level's pressure, the warm-up, the fatigue block and the
     * drops out of both.
     */
    public static long underPressureSec(Model m, Model.Routine r, double countFromKpa) {
        if (m == null || r == null) return 0L;
        List<Model.Preset> plan = m.plan(r);
        long ms = 0L;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p == null || p.rest) continue;
            Model.Stage st = p.stageIdx >= 0 && p.stageIdx < r.stages.size()
                           ? r.stages.get(p.stageIdx) : null;
            if (st != null && (st.rest || st.outOfNet())) continue;
            if (p.up < countFromKpa) continue;       // below the line: planned to count nothing
            ms += holdPartMs(p);
        }
        return Math.round(ms / 1000.0);
    }

    /**
     * THE CLOCK TIME A FINISHED RUN SPENDS THAT NO PLAN CAN HOLD, in seconds: the vent before
     * the after-assessment pull (it ends when the pump reports the cuff open - at most
     * {@link Tau#ASSESS_VENT_WINDOW_MS}) and the timer's own settling ({@link
     * AsRun#CUT_SHORT_TOL_MS}). A clock over-run no larger than this reads "as planned":
     * found on the device, a Pulse Session that ran exactly to plan read "9:00 of 8:45
     * planned · +3%", the fifteen seconds being the after-test's vent. It is under the
     * smallest deliberate lengthening (+30 s), so a real extension still shows. A SHORTFALL
     * is never absorbed - stopping early is the person's doing, and it is stated.
     */
    public static final long CLOCK_SLACK_SEC =
        (Tau.ASSESS_VENT_WINDOW_MS + AsRun.CUT_SHORT_TOL_MS) / 1000L;

    /**
     * The part of ONE preset spent at its working pressure, in ms: the hold half of each
     * pull-and-drop cycle, the same split SessionActivity#recordHoldFrame marks a frame as a
     * drop by ((time into the preset) mod (hold + drop) >= hold). A preset whose setpoints are
     * equal never drops - a stitched chunk of a long hold - so all of it is held.
     *
     * C6 - THE SPLIT IS TupClock's, called rather than copied: the at-pressure set clock
     * moves through a drop by it and the net marks a drop frame by it, so the plan, the
     * clock and the net cannot divide one preset three ways.
     */
    public static long holdPartMs(Model.Preset p) {
        if (p == null || p.rest || p.durMs <= 0) return 0L;
        return TupClock.holdPartMs(p.uh, p.lh, p.lo, p.up, p.cyclePart, p.durMs);
    }

    /** Copies what the routine planned onto the record at filing time - the only moment the
     *  routine is certainly the one that ran - with the under-pressure half counted from
     *  the line this session's net is (see {@link #underPressureSec}). A run with no
     *  routine plans nothing, and the record says so: 0 and null, never a guess. */
    public static void stamp(Model m, Model.Routine r, Model.Sess rec, double countFromKpa) {
        if (rec == null) return;
        rec.plannedSec = clockSec(m, r);
        rec.plannedTupSec = r == null || m == null ? null
                          : Double.valueOf(underPressureSec(m, r, countFromKpa));
    }

    /** The under-pressure plan a record carries: its own, or - on a session filed before
     *  that was recorded - the net its Trainer routine prescribed, which is the same figure.
     *  null when neither is known. */
    static Double plannedTupSec(Model.Sess s) {
        if (s.plannedTupSec != null) return s.plannedTupSec;
        if (s.netTargetMin != null) return Double.valueOf(s.netTargetMin.doubleValue() * 60.0);
        return null;
    }

    /* ================================================================== the summary rows */

    /**
     * The Delivered card's duration row, the under-pressure row and the level line for a
     * filed session, in the count `tupTiming` names (Model#tupTiming: true = at pressure
     * only). Reads the RECORD only, so a summary reopened from History compares the run
     * with the routine as it was, not as it has since been edited.
     *
     * THE COUNT IS THE SETTING AS IT STANDS, not a fact filed with the session: it is how the
     * person has chosen to count time, and each row names its own count, so a summary
     * reopened after the setting changed is still plain about what it compares.
     */
    public static Rows rows(Model m, Model.Sess s, boolean tupTiming) {
        Rows out = new Rows();
        if (s == null) return out;
        String clock = Model.Fmt.t(s.durSec);
        Double plannedTup = plannedTupSec(s);
        if (tupTiming && s.netTupSec != null) {
            double got = s.netTupSec.doubleValue();
            out.underPressure = true;
            out.gotSec = got;
            out.duration = clock;
            out.tupLabel = LABEL_UNDER_PRESSURE;
            out.tupValue = Say.fmtMin(got / 60.0) + " min";
            if (plannedTup != null && plannedTup.doubleValue() > 0) {
                double planned = plannedTup.doubleValue();
                out.plannedSec = planned;
                out.pct = Integer.valueOf(pct(got, planned));
                out.tupValue += " of " + Say.fmtMin(planned / 60.0) + " min planned"
                             + SEP + diff(out.pct.intValue());
            }
        } else {
            out.gotSec = s.durSec;
            out.duration = clock;
            if (s.plannedSec > 0) {
                out.plannedSec = s.plannedSec;
                long over = s.durSec - s.plannedSec;
                out.pct = Integer.valueOf(over > 0 && over <= CLOCK_SLACK_SEC ? 0
                                          : pct(s.durSec, s.plannedSec));
                out.duration += " of " + Model.Fmt.t(s.plannedSec) + " planned"
                             + SEP + diff(out.pct.intValue());
            }
        }
        out.cycles = cycles(s);
        out.limit = limit(s);
        levelLine(m, s, out);
        return out;
    }

    /**
     * D2 - THE SETS THAT ENDED AT THEIR TIME LIMIT, from the record (Sess#setsAtLimit): the
     * run screen's own sentence, counted, so it is never lost under the summary that the last
     * set ends into. A fact about the run, not the Set timing setting as it stands, and
     * nothing at all for a record that does not say (-1) or a run where none did (0).
     */
    static String limit(Model.Sess s) {
        if (s == null || s.setsAtLimit <= 0) return null;
        if (s.setsAtLimit == 1)
            return "1 set reached its time limit: the pump didn't stay at pressure long "
                 + "enough to finish it.";
        return s.setsAtLimit + " sets reached their time limit: the pump didn't stay at "
             + "pressure long enough to finish them.";
    }

    /**
     * O6 / C5 - THE CYCLES ROW, planned against delivered, from the record: both counts are
     * filed with the session (Model.Sess#cyclesPlanned / #cyclesDone), so a summary reopened
     * from History states ITS OWN run. It used to be read from the Activity, which holds the
     * last LIVE run's recording and plan, and a reopened summary showed that run's cycles.
     *
     * Only when both halves are known: "— of 14" would be a report about a recording that
     * does not exist, and a session filed before the counts were kept says nothing.
     */
    static String cycles(Model.Sess s) {
        if (s == null || s.cyclesDone < 0 || s.cyclesPlanned <= 0) return null;
        /* C6 - A RUN TIMED AT PRESSURE LEGITIMATELY RUNS MORE CYCLES THAN IT PLANNED. The
         * pump goes on cycling while a set waits for pressure, so a set that took twice its
         * time delivered twice its cycles; "13 of 7 planned" would read as an over-run, and
         * the old "13 of 13" counted the lengthened sets as the plan. Said only when the
         * at-pressure clock did hold this run's sets (Sess#tupPausedSec, a fact filed with
         * the run, not the setting as it stands): a run by the clock keeps "X of Y planned",
         * and so does a record filed before it was kept, with the counts it was filed with. */
        if (s.tupPausedSec > 0 && s.cyclesDone > s.cyclesPlanned)
            return s.cyclesDone + " (" + s.cyclesPlanned
                 + " planned — sets ran longer to reach their time at pressure)";
        return s.cyclesDone + " of " + s.cyclesPlanned + " planned";
    }

    /**
     * O6 - THE CYCLES A PLAN COMMANDS, summed over its presets: Manual#cycles of each, a rest
     * commanding none and a stitch chunk being part of a repetition, not one
     * (Preset#cyclePart). Moved from SessionActivity#plannedCycles, which counted the run's
     * LIVE plan at filing - lengthened by then by the at-pressure clock and +30 s - so it is
     * now asked once, of the plan as the run starts (C6).
     */
    public static int planCycles(List<Model.Preset> plan) {
        int n = 0;
        if (plan == null) return 0;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p == null || p.rest || p.cyclePart) continue;
            n += Manual.cycles(p.uh, p.lh, (int) (p.durMs / 1000));
        }
        return n;
    }

    /**
     * THE LEVEL'S GOAL, ON ITS OWN LINE - where the old row put it on the "planned" side of a
     * percentage. It stays on the summary because it answers a real question (T1: a routine
     * that quietly never reaches its level's goal should not stay invisible until a gate
     * fails), stated as what it is: the goal of the level this session was scored against,
     * per session, and always "under pressure", because that is the only way the plan counts
     * it - even when the session itself is being compared by the clock.
     *
     * Only for a session the plan scored (one with a net figure). The level is the one
     * STAMPED ON THE ROUTINE where there is one, the track's current level only where nothing
     * better is knowable - the derivation fileSession scored the net with. A length session
     * is stated against the length track's goal, which has no levels to climb.
     */
    private static void levelLine(Model m, Model.Sess s, Rows out) {
        if (m == null || s.netTupSec == null) return;
        Model.Routine r = s.routineId == null ? null : m.routine(s.routineId);
        if (r != null && r.trainerTrack == Plan.TRACK_LENGTH) {
            out.levelLabel = "length target";
            out.levelValue = Say.fmtMin(Plan.LENGTH_A_NET_MIN) + " min under pressure per session";
            return;
        }
        Model.TrainerTrackState st = m.trainerGirth;
        int level = r != null && r.trainerLevel > 0 ? r.trainerLevel
                  : (st != null ? st.level : 0);
        if (level <= 0) return;
        double goal = Plan.netMilestoneMin(level);
        if (goal <= 0) return;
        out.levelLabel = "level " + level + " target";
        out.levelValue = Say.fmtMin(goal) + " min under pressure per session";
    }

    private static final String SEP = "   ·   ";

    static int pct(double got, double planned) {
        return (int) Math.round((got - planned) / planned * 100.0);
    }

    /** "+12%", "−12%" (a true minus), or "as planned" when it rounds to nothing - "+0%"
     *  reads as a claim that something was added. */
    static String diff(int pct) {
        if (pct == 0) return "as planned";
        return (pct > 0 ? "+" : "−") + Math.abs(pct) + "%";
    }
}
