package org.openpump;

/**
 * Stage H, Task 4 — PURE prescription→routine mapping for the Trainer tab's suggestion
 * cards and their "Save routine" mint. No {@code import android}, so test.sh auto-discovers
 * this file exactly like {@link Plan} and {@link TrainerTab} and compiles it into the
 * desktop self-test.
 *
 * WHAT LIVES HERE. Plan.java (Task 1) says WHAT should change ({@link Plan#evaluate} →
 * a {@link Plan.Decision}); Model (Task 2) holds the {@link Model.Routine}/{@link Model.Set}
 * a run actually commands. This file is the bridge: it turns a track's current position
 * plus the engine's decision into a concrete {@link Rx} PRESCRIPTION — sets × hold × rest ×
 * pressure — and then into the exact {@link Model.Set}/{@link Model.Stage} field values a
 * minted routine must carry so that WHAT THE RUN COMMANDS AND WHAT {@code netTupSec}
 * ATTRIBUTES agree with WHAT THE ENGINE PRESCRIBED. The Android side (SessionActivity)
 * builds the Model objects from {@link SetSpec}; the arithmetic that decides the values is
 * all here, where a desktop assertion can pin it.
 *
 * THE ATTRIBUTION CHAIN (the correctness point the task hinges on):
 *   prescription  : N interval sets, each a {@code holdSec} hold at {@code pressureKpa}.
 *   Set fields    : {@link #workSet} → up = pressureKpa, uh = holdSec, lo = a below-floor
 *                   drop, lh = a short drop time, dur = one hold+drop cycle.
 *   what runs     : Model#plan expands each of the N set references into one preset that
 *                   pulls to {@code up} and holds {@code uh} — N holds of holdSec at
 *                   pressureKpa (Model.Set#say: "Pull to up and hold uh, drop for lh").
 *   what nets     : Session#noteHoldFrame counts a frame iff measured ≥ level floor AND the
 *                   stage is not the fatigue block AND it is not in the drop half of the
 *                   cycle — so the drops (by phase, wherever the person set them) and the
 *                   fatigue stage never count, and the net a full run delivers is
 *                   N × holdSec = {@code netTargetMin} minutes, the same figure the guide's
 *                   week table prints. The fatigue block ({@link Model.Stage#fatigueBlock})
 *                   is separately counted, exactly as Plan#fatigueBlockPresent requires.
 */
public final class Mint {

    /**
     * R1 - IS THIS SAVE TWO ROUTINES? The person asked for split sessions (Model#rxSplit), the
     * prescription has two sets or more to halve, and it is neither the feeder (ten minutes,
     * already the short session a split exists to produce) nor a traction session (its five
     * blocks are a sequence, and half of one is not a smaller one). The one rule, asked by
     * SessionActivity#saveMint when it files and by the trainer setup's offer when it says
     * "in two parts" (0.10), so the two cannot disagree.
     */
    public static boolean splitsInTwo(boolean rxSplit, int sets, int track, boolean traction) {
        return rxSplit && sets >= 2 && track != Plan.TRACK_FEEDER && !traction;
    }
    private Mint() { }

    /** Interval holds are 2-minute (the guidance: sets at 2-min holds); the traditional
     *  style and length L-B are 5-minute holds (the guidance). Length L-A is 2-min. */
    public static final int HOLD_INTERVAL_SEC = 120;
    public static final int HOLD_TRADITIONAL_SEC = 300;
    public static final int HOLD_LENGTH_SEC = 120;
    /** The fatigue block is 10 sets × 30–60 s (the guidance); 45 s is the mid-band. */
    public static final int HOLD_FATIGUE_SEC = 45;
    /** Feeder is a 10-minute session (the guidance) — 5 × 2-min holds nets 10 min. */
    public static final int FEEDER_SETS = 5;

    /** The brief drop between holds — a few seconds vented well below every level floor, so
     *  those frames never count toward net (Session#noteHoldFrame's floor gate excludes
     *  them). 5 kPa sits under the L1 floor (16.93) and the L2–4 floor (27.09) both. */
    public static final int DROP_KPA = 5;

    /**
     * THE USUAL HIGHEST DROP - past it the person is asked once, then it is their call (owner,
     * 2026-09-30, final; RunEdit#dropNeedsWarning). It was a hard bound, for the reasons below,
     * and the second of them no longer rests on the drop's pressure: a drop set above this is
     * kept out of net and of dose BY PHASE (Session#noteHoldFrame and Session#noteSample's
     * `dropPhase`), never by where it reads. What the person may set is {@link #DROP_PREF_MAX_KPA},
     * and each hold's drop stays 1.0 inHg under its pull ({@link #dropFor}). The trainer's own
     * default ({@link #DROP_KPA}, Model#rxDropKpa's 3) is under it as ever.
     *
     * THE REASON THERE WAS A BOUND AT ALL.
     *
     * The drop is not just "a bit less pressure": it is what makes an interval an interval,
     * and the app relies on those frames NOT counting toward net (Session#noteHoldFrame's
     * floor gate excludes everything under DOSE_FLOOR_KPA, and every level floor sits above
     * this). A preference allowed to climb into the floor band would quietly turn the drop
     * into more work at a lower pressure, and the net figure would count it.
     *
     * DERIVED FROM THE DOSE FLOOR, not picked. Session#DOSE_FLOOR_KPA is the pressure dose
     * counts from - the app's integral is (P - floor)dt - so a drop AT that floor
     * contributes exactly nothing, and a drop above it would start adding dose for seconds
     * the person is resting. A hand-picked 12 was the first version of this line and the
     * self-test caught it: 12 is under every level floor (16.93 at L1, 27.09 above it) so
     * NET stayed true, but it sits above the dose floor of 10 and dose would have counted
     * it. One of the two figures had to be derived from the other, and this is the one.
     */
    public static final int DROP_MAX_KPA = (int) Session.DOSE_FLOOR_KPA;

    /** THE HIGHEST DROP A PREFERENCE MAY BE SAVED AT: 1.0 inHg under the highest pull anything
     *  may prescribe (Plan#ABSOLUTE_CAP_KPA, RunEdit#dropTopKpa). Each hold's own pull then
     *  holds its drop lower ({@link #dropFor}). */
    public static final int DROP_PREF_MAX_KPA = RunEdit.dropTopKpa((int) Plan.ABSOLUTE_CAP_KPA);

    /** A preferred drop, held to what may be saved — {@link #DROP_PREF_MAX_KPA}, past the
     *  usual {@link #DROP_MAX_KPA} as the person's own call. 0 is a real answer ("all the way
     *  off"). A drop already saved is kept as it is. */
    public static int clampDropKpa(int wantKpa) {
        return wantKpa < 0 ? 0 : (wantKpa > DROP_PREF_MAX_KPA ? DROP_PREF_MAX_KPA : wantKpa);
    }

    /** The preferred drop for a hold pulling to `pullKpa`: {@link #clampDropKpa}, and above
     *  the usual top 1.0 inHg under that pull (RunEdit#dropUnder). Under the pull itself is
     *  each builder's own `min(drop, pull - 1)`, as it always was. */
    public static int dropFor(int wantKpa, int pullKpa) {
        return RunEdit.dropUnder(clampDropKpa(wantKpa), pullKpa);
    }

    public static final int DROP_SEC = 5;
    /**
     * SETS PER WORK BLOCK - how many cycles run before the prescribed rest, or 0 for "one
     * unbroken block".
     *
     * The L2 week table's own event cell says "rest 3-5 min every 5 sets begins" at week 18,
     * and it never reached a routine: {@link Rx#restSec} was computed for every non-length
     * track and consumed only inside the L3+ fatigue branch, so at L1 and L2 it was
     * calculated and dropped. A prescribed L2 routine of ten sets ran thirty minutes
     * continuously, with only the 60 s intra-cycle drop.
     *
     * WHY A COUNT RATHER THAN A FLAG: wiring the field up would not have been enough. The
     * work block is one set of N cycles (audit F1), so there is no seam every five sets to
     * put a rest into - the mint has to build the blocks for the rests to sit between.
     *
     * L1 IS UNBROKEN, deliberately. Its table carries no rest event; the rest arrives with
     * week 18, which is where the guide introduces it.
     */
    public static final int SETS_PER_BLOCK = 5;

    public static int setsPerBlock(int track, int level) {
        /* A TRADITIONAL SET IS ITS OWN BLOCK. The guidance rests between its 5-minute holds
         * (2 minutes at Level 1), and a rest can only fall at a block boundary - so with 0 here the whole
         * prescription became ONE unbroken block and the rest between the sets was never
         * emitted at all. restSecFor's 120 s was computed on every traditional mint and
         * thrown away, which is why the spec's own number appeared to be implemented and
         * reached nothing. */
        if (track == Plan.TRACK_GIRTH_TRADITIONAL) return 1;
        if (track != Plan.TRACK_GIRTH_INTERVAL) return 0;
        return level >= Plan.L2 ? SETS_PER_BLOCK : 0;
    }

    /**
     * HOW A SET COUNT DIVIDES INTO BLOCKS - {5,5} for ten, {5,5,3} for thirteen.
     *
     * The remainder is its own final block rather than being spread: the guidance rests
     * after every five sets, so five is the unit and what is left over is what is left over.
     * Evening them out into {4,5,4} would run a block the prescription never names.
     */
    public static int[] workBlocks(int sets, int per) {
        if (sets < 1) sets = 1;
        if (per < 1 || sets <= per) return new int[]{ sets };
        int n = (sets + per - 1) / per;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = Math.min(per, sets - i * per);
        return out;
    }

    /** Rest between blocks, and between the fatigue block and the work block, seconds
     *  (the guide: three to five minutes of rest).
     *  The fatigue block runs FIRST — spec: "fatigue (10 × 30–60 s) then main". */
    public static final int REST_SEC = 180;
    /** Fixed power for a minted set — the app's own default working power. */
    public static final int POWER_PCT = 75;

    /* ===================================================================== *
     *  PRESCRIPTION VALUE TYPE                                               *
     * ===================================================================== */

    /** One concrete prescription: the sets × hold × rest × pressure a routine must encode.
     *  {@code pressureKpa} is ALREADY triple-clamped (see {@link #clampPressureKpa}); the
     *  Android builder additionally runs {@link Model.Set#clamp} as the belt-and-braces
     *  third clamp the plan's ceiling ruling requires. */
    public static final class Rx {
        public final int track;
        public final int level;
        public final int sets;          // main interval sets (excludes the fatigue block)
        public final int holdSec;       // per-set hold
        public final int restSec;       // rest between the fatigue block and the work block
        public final int pressureKpa;   // triple-clamped working pressure, int kPa
        public final boolean fatigue;   // L3+ carries a separately-counted fatigue block
        public final double netTargetMin;
        /** How hard the pump pulls for the sets built from this prescription. The plan's own
         *  {@link #POWER_PCT} unless the day is a gentle one — see {@link #gentle}. */
        public final int powerPct;
        /**
         * HOW FAR {@link #pressureKpa} SITS FROM THE PLAN'S OWN FIGURE because of the person's
         * offset, whole kPa, as it landed after the limits (Scale#scaledKpa less
         * Scale#planOwnKpa; 0.10). The plan's own figure is {@code pressureKpa - offKpa}: what
         * a save writes back as the track's working pressure, and where the routine's scale is
         * measured from (Model.Routine#trainerScaleKpa). Carried by every copy of a
         * prescription; 0 for one on the plan's own figure.
         */
        public final int offKpa;
        /**
         * THE PLAN'S OWN FIGURE BEFORE THE WIRE ROUNDED IT, kPa (t10 REAL-15) - what the plan's
         * step asked for (30 + 1 inHg is 33.39), held to the plan's top. NaN where the
         * prescription did not come from the plan's figure (a copy, an adjustment, a feeder).
         * Read through {@link #planFigureKpa}.
         */
        public final double planExactKpa;

        Rx(int track, int level, int sets, int holdSec, int restSec, int pressureKpa,
           boolean fatigue, double netTargetMin) {
            this(track, level, sets, holdSec, restSec, pressureKpa, fatigue, netTargetMin,
                 POWER_PCT);
        }

        Rx(int track, int level, int sets, int holdSec, int restSec, int pressureKpa,
           boolean fatigue, double netTargetMin, int powerPct) {
            this(track, level, sets, holdSec, restSec, pressureKpa, fatigue, netTargetMin,
                 powerPct, 0);
        }

        Rx(int track, int level, int sets, int holdSec, int restSec, int pressureKpa,
           boolean fatigue, double netTargetMin, int powerPct, int offKpa) {
            this(track, level, sets, holdSec, restSec, pressureKpa, fatigue, netTargetMin,
                 powerPct, offKpa, Double.NaN);
        }

        Rx(int track, int level, int sets, int holdSec, int restSec, int pressureKpa,
           boolean fatigue, double netTargetMin, int powerPct, int offKpa,
           double planExactKpa) {
            this.track = track; this.level = level; this.sets = sets; this.holdSec = holdSec;
            this.restSec = restSec; this.pressureKpa = pressureKpa; this.fatigue = fatigue;
            this.netTargetMin = netTargetMin; this.powerPct = powerPct; this.offKpa = offKpa;
            this.planExactKpa = planExactKpa;
        }

        /** The plan's own figure this prescription was scaled from, kPa. */
        public int planKpa() { return pressureKpa - offKpa; }

        /**
         * THE PLAN'S OWN FIGURE AS THE PLAN KEEPS IT (t10 REAL-15, the parity run): the exact
         * figure where the whole kPa this prescription carries is that figure rounded, else
         * the whole kPa ({@link #planKpa}) - a hard limit held it, or it is a copy. What a save
         * writes back as the track's working pressure (TrainerTab#savedPlanKpa). Writing back
         * the whole kPa lost the fraction at every rewrite: the owner's +1 inHg steps went
         * 30 -> 33 -> 36 where the plan's own steps are 30 -> 33.4 -> 36.8, the 37 the editor's
         * model reaches at week 8.
         */
        public double planFigureKpa() {
            if (!Double.isNaN(planExactKpa) && Math.round(planExactKpa) == planKpa())
                return planExactKpa;
            return planKpa();
        }
        public double holdMin() { return holdSec / 60.0; }
    }

    /* ===================================================================== *
     *  THE TRIPLE CLAMP (plan's ceiling ruling)                             *
     * ===================================================================== */

    /**
     * The mint pressure must be ≤ the level/month band cap AND ≤ the absolute 15 hg
     * (50.80 kPa) AND ≤ the user's device ceiling — a triple clamp, before the routine's own
     * {@link Model.Set#clamp} runs a fourth time as belt-and-braces. {@link Plan#pressureCapKpa}
     * already folds the band cap and the absolute cap together (it returns {@code min(band,
     * ABSOLUTE)}); the {@code Math.min} with {@link Plan#ABSOLUTE_CAP_KPA} here is therefore
     * redundant but STATED, so the "15 hg — never" ruling is visible at this call site and a
     * future change to pressureCapKpa cannot silently drop it. Returns an int because
     * {@link Model.Set} stores pressure as int kPa; never below 1 (a set's floor).
     */
    public static int clampPressureKpa(double wantKpa, int level, int monthIndex, int ceilKpa) {
        double bandCap = Plan.pressureCapKpa(level, monthIndex);
        double capped = Math.min(wantKpa, Math.min(bandCap, Plan.ABSOLUTE_CAP_KPA));
        int asInt = (int) Math.round(capped);
        /* ...AND THE ROUNDING MAY NOT LIFT IT BACK OVER. 15 hg is 50.7958 kPa and rounds to
         * 51, which is 15.06 hg - so the clamp that exists to enforce "never exceeded" was
         * the thing exceeding it. Plan#absoluteCapWholeKpa carries the reasoning. */
        int hardMax = Plan.absoluteCapWholeKpa();
        if (asInt > hardMax) asInt = hardMax;
        if (asInt > ceilKpa) asInt = ceilKpa;   // device ceiling — the third clamp
        if (asInt < 1) asInt = 1;
        return asInt;
    }

    /* ===================================================================== *
     *  BASELINE + DECISION → PRESCRIPTION                                    *
     * ===================================================================== */

    /** The baseline set count for a track/level/week BEFORE any decision delta — read from
     *  the guide's own week table where one exists (girth-interval L1/L2), else derived from
     *  the level's net milestone (net = sets × 2-min, so sets = milestone/2) for the
     *  table-less levels (L3/L4 interval), the traditional track's growth by the week at its
     *  level (Plan#traditionalSets, 0.10), or the static spec for length. */
    public static int baseSets(int track, int level, int weekIndex) {
        return baseSets(track, level, weekIndex, 0);
    }

    /** {@link #baseSets(int,int,int)} with a CARRIED set count for the table-less levels
     *  (L3/L4): when a level-up carried the achieved volume (guide "volume carries"), the L3/L4
     *  base is the larger of that carried count and the milestone-derived floor, so a graduate
     *  never drops below the volume they were already running. {@code carriedSets} 0 = no carry
     *  (the plain milestone base). Ignored for L1/L2 (their table row governs) and length. */
    public static int baseSets(int track, int level, int weekIndex, int carriedSets) {
        if (track == Plan.TRACK_GIRTH_INTERVAL) {
            Plan.Week[] table = (level == Plan.L1) ? Plan.GIRTH_INTERVAL_L1
                              : (level == Plan.L2) ? Plan.GIRTH_INTERVAL_L2 : null;
            if (table != null) {
                Plan.Week w = Plan.rowForOrdinal(table, weekIndex);
                if (w != null && !w.deload) return w.sets;
                // A deload row prescribes no work — carry the PRECEDING training week's set
                // count (the one just completed) rather than 0. Scan back from this row.
                int idx = (weekIndex >= 1 && weekIndex <= table.length) ? weekIndex - 1
                          : table.length - 1;
                for (int i = Math.min(idx, table.length - 1); i >= 0; i--) {
                    if (!table[i].deload) return table[i].sets;
                }
                return 1;
            }
            // L3/L4 interval: no table — the net-milestone floor, or the carried volume if a
            // level-up carried a higher set count into this level.
            int milestoneBase = (int) Math.round(Plan.netMilestoneMin(level) / 2.0);
            return carriedSets > 0 ? Math.max(carriedSets, milestoneBase) : milestoneBase;
        }
        // TRADITIONAL GROWS AS THE GUIDANCE DOES (0.10): 3 -> 6 at Level 1, 6, 6 -> 8 at
        // Level 3, 8 - by its week at the level. A carried count does not reach it: a level
        // crossing keeps the total as yield sets (carryYieldAcrossLevel).
        if (track == Plan.TRACK_GIRTH_TRADITIONAL) return Plan.traditionalSets(level, weekIndex);
        if (track == Plan.TRACK_LENGTH) return Plan.LENGTH_A_SETS;
        int milestoneBase = (int) Math.round(Plan.netMilestoneMin(level) / 2.0);
        return carriedSets > 0 ? Math.max(carriedSets, milestoneBase) : milestoneBase;
    }

    /**
     * ...UNDER A TRADITIONAL BUILD-UP (the half start, 0.10): the level's count, held to the
     * build-up's count at this week ({@link #buildUpSets}; 0 = none) until it reaches it.
     * Every other track, and a traditional one with no build-up, is the count above. The
     * yield floor reads this same figure, so a floor can never pull a build-up up to the
     * level's count.
     */
    public static int baseSets(int track, int level, int weekIndex, int carriedSets,
                               int buildUp) {
        int count = baseSets(track, level, weekIndex, carriedSets);
        if (track != Plan.TRACK_GIRTH_TRADITIONAL || buildUp <= 0) return count;
        return Math.min(count, buildUp);
    }

    /**
     * THE BUILD-UP'S COUNT NOW (the half start, Plan#traditionalHalfStart): the count it started
     * from plus one hold for every {@link Plan#TRAD_BUILD_WEEKS_PER_HOLD} counted weeks of the
     * track's week position since - 0 where the track has none (interval, length, Level 1, and
     * every traditional track made since the growth).
     */
    public static int buildUpSets(int track, Model.TrainerTrackState st) {
        if (st == null || track != Plan.TRACK_GIRTH_TRADITIONAL || st.buildSets <= 0) return 0;
        int weeks = Math.max(0, Math.max(1, st.weekIndex) - st.buildWeek);
        return st.buildSets + weeks / Plan.TRAD_BUILD_WEEKS_PER_HOLD;
    }

    /** Whether the track is still building up: its build-up is under the level's count. */
    public static boolean buildingUp(int track, Model.TrainerTrackState st) {
        int b = buildUpSets(track, st);
        return b > 0 && b < baseSets(track, st.level, st.weekIndex, st.carriedSets);
    }

    /** The week position at which the build-up reaches the most its level grows to - how far
     *  the week must be able to advance at a level whose own growth stops sooner (Level 2 and 4
     *  do not grow at all). 0 where there is no build-up. */
    public static int buildUpTopWeek(int track, Model.TrainerTrackState st) {
        if (buildUpSets(track, st) <= 0) return 0;
        int short_ = Math.max(0, Plan.traditionalTopSets(st.level) - st.buildSets);
        return Math.max(1, st.buildWeek + short_ * Plan.TRAD_BUILD_WEEKS_PER_HOLD);
    }

    /** No build-up: a placement the person answered for (setup), or a style switch. */
    public static void endBuildUp(Model.TrainerTrackState st) {
        if (st == null) return;
        st.buildSets = 0; st.buildWeek = 0; st.buildSaid = true;
    }

    /**
     * A BUILD-UP FROM `ran` HOLDS AT THE TRACK'S CURRENT LEVEL AND WEEK - or none, where `ran`
     * already reaches the level's count. With one, the yield sets are folded into it (the count
     * is what the person runs); without, the total they ran is kept, as a level crossing keeps
     * it (any part above the level's count stays as kept yield). `phase` is a counted week that
     * has not yet added a hold (0 or 1), carried so the next hold comes on the build-up's own
     * cadence. Returns whether a build-up is running.
     */
    public static boolean buildUpFrom(Model.TrainerTrackState st, int track, int ran, int phase,
                               long nowMs) {
        int count = baseSets(track, st.level, st.weekIndex, st.carriedSets);
        if (track != Plan.TRACK_GIRTH_TRADITIONAL || ran >= count) {
            endBuildUp(st);
            st.yieldSets = Math.max(0, ran - count);
            st.yieldSinceMs = nowMs;
            return false;
        }
        st.buildSets = ran;
        int carried = Math.max(0, Math.min(Plan.TRAD_BUILD_WEEKS_PER_HOLD - 1, phase));
        st.buildWeek = Math.max(0, Math.max(1, st.weekIndex) - carried);
        st.yieldSets = 0;
        st.yieldSinceMs = nowMs;
        return true;
    }

    /**
     * The calendar-advanced week-table ordinal: the base (onboarding) ordinal moved forward
     * by the training weeks accumulated since, capped at the table's last row. The
     * girth-interval L1/L2 tables advance, and so does traditional girth's week at Levels 1 and
     * 3 (0.10: its holds grow by it, Plan#traditionalSets), capped at the week that adds its
     * last hold (Plan#traditionalTopWeek); length and the other levels return the base
     * unchanged. Landing on a deload row is harmless — {@link #baseSets}
     * carries the preceding training week's set count — and running PAST the table end is
     * capped at the last row, so the L1 ceiling prescription (10 sets) simply repeats until
     * the level GATE (net + pressure, evaluated separately from actuals) promotes to L2. The
     * advance therefore climbs the guide's scheduled set increases WITHOUT ever skipping the
     * gate that stops a level (level is never changed here).
     */
    public static int advancedWeekIndex(int track, int level, int baseOrdinal, int weeksToAdd) {
        if (track == Plan.TRACK_GIRTH_TRADITIONAL) {
            int top = Plan.traditionalTopWeek(level);
            if (top <= 1) return baseOrdinal;
            return Math.min(Math.max(1, baseOrdinal) + Math.max(0, weeksToAdd), top);
        }
        if (track != Plan.TRACK_GIRTH_INTERVAL) return baseOrdinal;
        Plan.Week[] table = (level == Plan.L1) ? Plan.GIRTH_INTERVAL_L1
                          : (level == Plan.L2) ? Plan.GIRTH_INTERVAL_L2 : null;
        if (table == null) return baseOrdinal;
        int base = Math.max(1, baseOrdinal);
        int target = base + Math.max(0, weeksToAdd);
        return Math.min(target, table.length);
    }

    /**
     * The MONOTONIC advance from a STABLE anchor: {@code max(currentWeekIndex,
     * advancedWeekIndex(base, weeksSinceBase))}. Pure and idempotent — called repeatedly with
     * the SAME {@code baseOrdinal}/{@code weeksSinceBase} it returns the same value (it never
     * advances on its own), and it only ever moves the position forward. The caller must feed
     * a FIXED anchor ({@code baseOrdinal}, and the {@code weeksSinceBase} computed from a fixed
     * {@code weekBaseMs}) — feeding back the previous result as the base is the compounding
     * bug this signature is shaped to prevent.
     */
    public static int advanceFromAnchor(int track, int level, int baseOrdinal,
                                        int weeksSinceBase, int currentWeekIndex) {
        int eff = advancedWeekIndex(track, level, baseOrdinal, weeksSinceBase);
        return Math.max(currentWeekIndex, eff);
    }

    /**
     * {@link #advanceFromAnchor(int,int,int,int,int)} for a track, from its own anchor - and on
     * past where its level's growth stops while a traditional build-up still needs the weeks
     * (the half start, 0.10: Level 2 and 4 do not grow, and Level 3's last hold can come before
     * the build-up reaches it), up to the week the build-up reaches the level's most
     * ({@link #buildUpTopWeek}). The same counted weeks, so the build-up adds its holds on the
     * plan's own count.
     */
    public static int advanceFromAnchor(int track, Model.TrainerTrackState st,
                                        int weeksSinceBase) {
        int eff = advancedWeekIndex(track, st.level, st.weekBaseIndex, weeksSinceBase);
        int buildTop = buildUpTopWeek(track, st);
        if (buildTop > 0) {
            int top = Math.max(buildTop, Plan.traditionalTopWeek(st.level));
            eff = Math.max(eff, Math.min(Math.max(1, st.weekBaseIndex)
                + Math.max(0, weeksSinceBase), top));
        }
        return Math.max(st.weekIndex, eff);
    }

    private static int holdSecFor(int track) {
        if (track == Plan.TRACK_GIRTH_TRADITIONAL) return HOLD_TRADITIONAL_SEC;
        return HOLD_INTERVAL_SEC;    // interval, length L-A, feeder all 2-min holds
    }

    /**
     * R1 - HOW A SET COUNT DIVIDES BETWEEN TWO SESSIONS.
     *
     * The FIRST part takes the larger half. An odd prescription has to put its spare set
     * somewhere, and the first session of the day is the one more likely to happen - the
     * whole reason for splitting is that the second half is at risk.
     */
    public static int splitSets(int sets, int part) {
        if (sets < 2) return part == 1 ? Math.max(1, sets) : 0;
        int first = (sets + 1) / 2;
        return part == 1 ? first : sets - first;
    }

    /**
     * R6 - A LONGER HOLD, AND FEWER OF THEM, FOR THE SAME NET.
     *
     * The engine prescribes a flat two-minute hold at every level while the guide gives L3
     * a range of one to three minutes. Since the plan is scored on NET TIME AT PRESSURE and
     * not on set count, a longer hold with proportionally fewer sets delivers exactly the
     * prescription - which is the net-not-sets principle the guide states and the app had
     * only ever implied.
     *
     * THE NET IS THE INVARIANT AND THE SETS ARE DERIVED FROM IT. That ordering is the
     * safety property: sets x hold is recomputed to hit the SAME netTargetMin, so a longer
     * hold can never be a way to do less. Asking for three minutes where two were
     * prescribed buys fewer, longer sets and not a shorter session.
     *
     * ONLY WHERE THE GUIDE GIVES A RANGE. L1 and L2 have a fixed two-minute hold in their
     * own week tables, and honouring a preference there would be inventing a range the
     * source does not have. {@code want} of 0 always means "the level's own".
     */
    public static int holdSecWanted(int track, int level, int want) {
        int base = holdSecFor(track);
        if (want <= 0) return base;
        if (track != Plan.TRACK_GIRTH_INTERVAL) return base;
        if (level < Plan.L3) return base;                 // no range to honour
        return want < 60 ? 60 : (want > 180 ? 180 : want);
    }

    /**
     * The set count that reaches {@code netMin} at {@code holdSec} - the other half of
     * {@link #holdSecWanted}. At least one: a prescription of no sets is not a prescription.
     */
/**
     * Q1 - A PRESCRIPTION, LOWERED, ASKING FOR NO NET.
     *
     * The pressure comes down by {@code cutKpa} and <b>the net target goes to zero</b>, and
     * the second half is the part that matters. A reduced session sits below the level floor
     * by design, so it produces no net whatever it does; leaving the target at its full
     * figure would have every reduced session score as a total failure, and three failures
     * propose stepping the level back - so following the petechiae advice correctly would
     * demote you for it. Zero is not a failure, it is "this session was not asked for net",
     * and TrainerTab#recentTrackedNets leaves those out of the window entirely.
     *
     * THE SETS AND HOLDS ARE UNTOUCHED. You do the same work, at a lower pressure. That is
     * what the source asks for and it is the whole of the change.
     *
     * IT LIVES HERE rather than on the screen because it is arithmetic on a prescription,
     * which is what this class is - and because arithmetic that decides what pressure the
     * pump is commanded to should be asserted on a desk, not discovered on a cuff.
     */
    /**
     * THE SAME PRESCRIPTION WITH A DIFFERENT NUMBER OF CYCLES - what is LEFT of one.
     *
     * The constructor is package-private and stays that way: an Rx is something the plan
     * writes, not something a caller invents. This is the one case where a caller legitimately
     * has both halves of the answer already - the prescription, and how much of it did not
     * happen - and it recomputes the net target from the cycles rather than taking it on
     * trust, so a remainder cannot claim the whole session's target.
     */
    public static Rx remainderOf(Rx rx, int cyclesLeft) {
        if (rx == null) return null;
        int n = Math.max(1, cyclesLeft);
        return new Rx(rx.track, rx.level, n, rx.holdSec, rx.restSec, rx.pressureKpa,
                      false, rx.netTargetMin <= 0.0 ? 0.0 : n * (rx.holdSec / 60.0),
                      rx.powerPct, rx.offKpa);
    }

    public static Rx reduce(Rx rx, double cutKpa) {
        if (rx == null || cutKpa <= 0.0) return rx;
        int lowered = reducedKpa(rx.pressureKpa, cutKpa);
        if (lowered >= rx.pressureKpa) return rx;
        return new Rx(rx.track, rx.level, rx.sets, rx.holdSec, rx.restSec,
                      lowered, rx.fatigue, 0.0, rx.powerPct, rx.offKpa);
    }

    /** The whole kPa a pressure is commanded at once `cutKpa` comes off it - {@link #reduce}'s
     *  own arithmetic, for a caller holding a pressure rather than a prescription (asking
     *  where a saved routine actually holds). Never raises a pressure. */
    public static int reducedKpa(int kpa, double cutKpa) {
        if (cutKpa <= 0.0) return kpa;
        int lowered = (int) Math.round(Math.max(MIN_REDUCED_KPA, kpa - cutKpa));
        return lowered >= kpa ? kpa : lowered;
    }

    /** The floor a reduction may not go under. Below this the pump is not doing anything to
     *  the tissue and the session is a pretence rather than a precaution. */
    public static final double MIN_REDUCED_KPA = 2.0;

    /**
     * THE SAME PRESCRIPTION, PULLED SLOWER. Nothing but the speed changes: the pressure, the
     * sets and the holds are whatever was asked for. The guidance names pumping up too
     * fast as a cause of the marks the gentle return exists to avoid, so a day that comes
     * down in pressure comes down in speed too.
     */
    public static Rx gentle(Rx rx) {
        if (rx == null || rx.powerPct == Plan.RETURN_POWER_PCT) return rx;
        return new Rx(rx.track, rx.level, rx.sets, rx.holdSec, rx.restSec, rx.pressureKpa,
                      rx.fatigue, rx.netTargetMin, Plan.RETURN_POWER_PCT, rx.offKpa);
    }

        public static int setsForNet(double netMin, int holdSec) {
        if (holdSec <= 0) return 1;
        int n = (int) Math.round(netMin * 60.0 / holdSec);
        return n < 1 ? 1 : n;
    }

    /**
     * The full prescription for a track at its current position, AFTER applying the engine's
     * decision. {@code statePressureKpa} is the track's current working pressure; the
     * decision's own pressure (a RAISE / FEEDER derivation) overrides it when the decision
     * carries one. Set deltas (ADD/REDUCE volume) adjust the base set count; PAUSE and every
     * non-volume action leave it. The result's pressure is triple-clamped here so the
     * signature and the mint agree on the clamped value.
     */
    public static Rx prescribe(int track, int level, int weekIndex, double statePressureKpa,
                               int monthIndex, int ceilKpa, Plan.Decision d) {
        return prescribe(track, level, weekIndex, statePressureKpa, monthIndex, ceilKpa, 0, d);
    }

    /** {@link #prescribe(int,int,int,double,int,int,Plan.Decision)} with the CARRIED set count
     *  a level-up preserved into a table-less level (guide "volume carries"). */
    public static Rx prescribe(int track, int level, int weekIndex, double statePressureKpa,
                               int monthIndex, int ceilKpa, int carriedSets, Plan.Decision d) {
        return prescribe(track, level, weekIndex, statePressureKpa, monthIndex, ceilKpa,
                         carriedSets, 0, d);
    }

    /**
     * ...and with the YIELD SETS the track keeps (Model.TrainerTrackState#yieldSets): the base
     * count plus the kept adjustment, plus any yield change this decision proposes that has not
     * been kept yet. Only girth reads it; a yield change never takes the count under
     * {@link #yieldFloorSets}.
     */
    public static Rx prescribe(int track, int level, int weekIndex, double statePressureKpa,
                               int monthIndex, int ceilKpa, int carriedSets, int yieldSets,
                               Plan.Decision d) {
        return prescribe(track, level, weekIndex, statePressureKpa, monthIndex, ceilKpa,
                         carriedSets, yieldSets, d, Scale.Limits.NONE);
    }

    /**
     * ...AND ON THE PERSON'S OWN SCALE (0.10): the plan's figure - its own, or the decision's -
     * held to the track's usual top as the plan holds it, plus the track's offset where it
     * applies (so never above the effective top), then the hard limits: the ceiling, "Most you
     * will go to", 15 inHg and a new person's first-month 6 inHg (Scale). The result carries
     * how far the offset moved it (Rx#offKpa), so the plan's own figure is never lost.
     * {@link Scale.Limits#NONE} is the plan's own figure against the ceiling and 15 inHg alone,
     * exactly what this did before the offset existed.
     */
    public static Rx prescribe(int track, int level, int weekIndex, double statePressureKpa,
                               int monthIndex, int ceilKpa, int carriedSets, int yieldSets,
                               Plan.Decision d, Scale.Limits lim) {
        return prescribe(track, level, weekIndex, statePressureKpa, monthIndex, ceilKpa,
                         carriedSets, yieldSets, d, lim, 0);
    }

    /** ...AND UNDER A TRADITIONAL BUILD-UP (the half start, 0.10): `buildUp` is the build-up's
     *  count now ({@link #buildUpSets}; 0 = none), which holds the base and the yield floor to
     *  it ({@link #baseSets(int,int,int,int,int)}). */
    public static Rx prescribe(int track, int level, int weekIndex, double statePressureKpa,
                               int monthIndex, int ceilKpa, int carriedSets, int yieldSets,
                               Plan.Decision d, Scale.Limits lim, int buildUp) {
        return prescribe(track, level, weekIndex, statePressureKpa, monthIndex, ceilKpa,
                         carriedSets, yieldSets, d, lim, buildUp, -1);
    }

    /** ...AND UNDER THE SESSION'S TIME CAP (R-27, the owner's decision, 2026-10-01): never more
     *  girth holds than fit under the level's 20 / 30 / 36 / 44 minutes with `fatSec` of
     *  fatigue block ({@link #r2FatSec}; -1 = the level's standard block). The holds past it
     *  are the plan's (Plan#r2Step turns them into pressure); they are not written. */
    public static Rx prescribe(int track, int level, int weekIndex, double statePressureKpa,
                               int monthIndex, int ceilKpa, int carriedSets, int yieldSets,
                               Plan.Decision d, Scale.Limits lim, int buildUp, int fatSec) {
        if (lim == null) lim = Scale.Limits.NONE;
        int hard = Scale.hardKpa(ceilKpa, lim.mostKpa, lim.newToPumping, monthIndex);
        double off = Scale.appliedOffsetKpa(lim.offsetKpa, lim.newToPumping, monthIndex);
        if (track == Plan.TRACK_FEEDER) {
            double want = (d != null && !Double.isNaN(d.pressureKpa))
                ? d.pressureKpa : Plan.feederPressureKpa(statePressureKpa);
            int plan = Scale.planOwnKpa(want, track, level, monthIndex, hard);
            int p = Scale.scaledKpa(want, off, track, level, monthIndex, hard);
            return new Rx(track, level, FEEDER_SETS, HOLD_INTERVAL_SEC, 0, p, false,
                FEEDER_SETS * (HOLD_INTERVAL_SEC / 60.0), POWER_PCT, p - plan);
        }

        int holdSec = holdSecFor(track);
        int base = baseSets(track, level, weekIndex, carriedSets, buildUp);
        boolean girth = track == Plan.TRACK_GIRTH_INTERVAL
            || track == Plan.TRACK_GIRTH_TRADITIONAL;
        int sets = base + (girth ? yieldSets : 0);
        double wantPressure = statePressureKpa;

        /* R-25 (t10 fix, review B F5): A HYBRID'S STEP MOVES ITS OWN HOLDS (TrainerTrackState
         * #hybridYield, which RxBuild#hybridRx reads), never the hidden interval count. Moving it
         * here for one evaluation changed the signature twice - a rewrite with the step, and
         * another the next morning putting the count back - for a routine that ran the same. */
        if (d != null) {
            switch (d.action) {
                case Plan.ACTION_ADD_VOLUME:
                case Plan.ACTION_REDUCE_VOLUME:
                    if (!d.hybrid) sets += d.setsDelta;
                    break;
                case Plan.ACTION_PAUSE_VOLUME:
                    sets += d.setsDelta;          // L1/L2: the kept yield sets come off
                    break;
                case Plan.ACTION_RAISE_PRESSURE:
                    if (!Double.isNaN(d.pressureKpa)) wantPressure = d.pressureKpa;
                    break;
                default:
                    break;   // HOLD / PAUSE_VOLUME / DELOAD / STEP_BACK: no set/pressure move
            }
        }
        if (girth && sets < base) sets = Math.max(sets,
            yieldFloorSets(track, level, weekIndex, carriedSets, buildUp));
        // R-25 (A3): traditional never runs past its level's top (6 / 6 / 7 / 9).
        if (track == Plan.TRACK_GIRTH_TRADITIONAL)
            sets = Math.min(sets, Math.max(base, Plan.traditionalTop(level)));
        // R-27: never more holds than the session's time cap lets run.
        if (girth) sets = Math.min(sets, Plan.r2MaxHolds(level, holdSec,
            fatSec >= 0 ? fatSec : standardFatSec(level)));
        if (sets < 1) sets = 1;

        // G2: the plan's figure is held to its own top - the usual top, or the climb's when
        // the person's maximum is above it (Scale#planTopKpa).
        double planTop = Scale.planTopKpa(track, level, monthIndex, ceilKpa, lim);
        int plan = Scale.planOwnKpa(wantPressure, planTop, hard);
        int p = Scale.scaledKpa(wantPressure, off, planTop, hard);
        boolean fatigue = (track != Plan.TRACK_LENGTH) && Plan.fatigueBlockPresent(level);
        int restSec = restSecFor(track, level);
        double net = sets * (holdSec / 60.0);
        // REAL-15: the plan's figure as the plan holds it, before the wire's whole kPa.
        return new Rx(track, level, sets, holdSec, restSec, p, fatigue, net, POWER_PCT,
                      p - plan, Math.min(wantPressure, planTop));
    }

    /**
     * THE FEWEST SETS A YIELD CHANGE MAY LEAVE: the table's own row at Levels 1 and 2 and on
     * traditional girth (a yield cut never removes the table's sets), and the level's own
     * minimum at Levels 3 and 4 (10 sets at L3, 14 at L4 - the milestone's minutes in 2-minute
     * holds), which is below a carried count, so the carried extra can come off.
     */
    public static int yieldFloorSets(int track, int level, int weekIndex, int carriedSets) {
        return yieldFloorSets(track, level, weekIndex, carriedSets, 0);
    }

    /** ...under a traditional build-up: the build-up's count, never the level's (the floor is
     *  the base, and the base is held to the build-up) - so a yield cut, or the clamp in
     *  {@link #totalSets}, can never pull a build-up straight up to the level's count. */
    public static int yieldFloorSets(int track, int level, int weekIndex, int carriedSets,
                                     int buildUp) {
        int base = baseSets(track, level, weekIndex, carriedSets, buildUp);
        if (track == Plan.TRACK_GIRTH_INTERVAL && level >= Plan.L3)
            return Math.min(base, (int) Math.round(Plan.netMilestoneMin(level) / 2.0));
        return base;
    }

    /** The girth track's whole set count at its position: the base plus the kept yield sets,
     *  never under the floor. What a level crossing keeps unchanged. */
    public static int totalSets(int track, Model.TrainerTrackState st) {
        int b = buildUpSets(track, st);
        int base = baseSets(track, st.level, st.weekIndex, st.carriedSets, b);
        int total = base + st.yieldSets;
        // R-25 (A3): traditional's holds stop at the level's top, its calendar's included.
        if (track == Plan.TRACK_GIRTH_TRADITIONAL)
            total = Math.min(total, Math.max(base, Plan.traditionalTop(st.level)));
        return Math.max(total,
                        yieldFloorSets(track, st.level, st.weekIndex, st.carriedSets, b));
    }

    /** R-27: the fatigue block's seconds the time cap counts at `level` - the standard 10 x
     *  45 s from Level 3, none under it. */
    public static int standardFatSec(int level) {
        return Plan.fatigueBlockPresent(level) ? Plan.FATIGUE_BLOCK_SETS * HOLD_FATIGUE_SEC : 0;
    }

    /** R-27: ...as the person's Program builds it - half again when extended, none when off
     *  (RxBuild's fatigue block, the same arithmetic). */
    public static int r2FatSec(Model m, int track, int level) {
        if (!Plan.fatigueBlockPresent(level)) return 0;
        Model.Program prog = m == null ? null : m.programFor(track);
        int mode = prog == null ? Model.Program.FAT_STANDARD : prog.fatigue;
        if (mode == Model.Program.FAT_OFF) return 0;
        int n = Plan.FATIGUE_BLOCK_SETS;
        if (mode == Model.Program.FAT_EXTENDED) n = n * 3 / 2;
        return n * HOLD_FATIGUE_SEC;
    }

    /** R-25: whether the girth track runs the hybrid at its level - the interval track from
     *  Level 3 with the hybrid chosen (RxBuild#hybridApplies asks the same of a prescription). */
    public static boolean hybridOn(Model m, int track, Model.TrainerTrackState st) {
        return m != null && st != null && m.trainerGirthHybrid && st.level >= Plan.L3
            && track == Plan.TRACK_GIRTH_INTERVAL;
    }

    /**
     * KEEPS A YIELD CHANGE (the owner's decision, 2026-09-26) - called where the plan's change
     * reaches your routine: the in-place rewrite (SessionActivity#applyPlanTo) and a Save
     * (SessionActivity#saveMint). Three low readings in a row keep +1 set (L1/L2) or +2
     * (L3/L4); three high ones keep 2 fewer at L3/L4, or at L1/L2 take off whatever yield had
     * added. After any change the streak starts again: only readings from later sessions
     * count, so the next change needs three NEW readings. Girth tracks only - a length
     * "add volume" is the ladder's strain sets. Returns whether anything moved.
     */
    public static boolean commitYield(Model.TrainerTrackState st, int track, Plan.Decision d,
                                      long nowMs) {
        if (st == null || d == null) return false;
        if (track != Plan.TRACK_GIRTH_INTERVAL && track != Plan.TRACK_GIRTH_TRADITIONAL)
            return false;
        // R-23: the offer, answered whichever way - the pending add goes, the streak restarts.
        if (d.action == Plan.ACTION_OFFER_BREAK) return answerYieldOffer(st, nowMs);
        boolean kept = false;
        /* R-27: AN APPLIED TIME-CAP CONVERSION KEEPS ITS HIGH-WATER MARK (the same holds never
         * convert twice) and what it still owes, which rides on the next pressure steps. Its
         * r2AddHolds are the plan's own count the step added past the cap - kept, never run. */
        if (d.r2()) {
            st.r2ExHolds = Math.max(st.r2ExHolds, d.r2ExHolds);
            st.r2PendKpa = Math.max(0, d.r2PendKpa);
            kept = true;
        }
        int add;
        switch (d.action) {
            case Plan.ACTION_ADD_VOLUME:    add = Math.max(0, d.setsDelta); break;
            case Plan.ACTION_REDUCE_VOLUME: add = Math.min(0, d.setsDelta); break;
            case Plan.ACTION_PAUSE_VOLUME:  add = 0; break;
            default:                        add = d.r2() ? Math.max(0, d.r2AddHolds) : 0; break;
        }
        int was = st.yieldSets, wasHy = st.hybridYield;
        if (d.hybrid) {
            // R-25: the hybrid's own holds, 6 / 8 by level, up to its top of 8.
            int base = st.level >= Plan.L4 ? Plan.HYBRID_L4_HOLDS : Plan.HYBRID_L3_HOLDS;
            int room = Math.max(0, Plan.HYBRID_TOP_HOLDS - base);
            st.hybridYield = Math.max(0, Math.min(room, wasHy + add));
        } else if (d.action == Plan.ACTION_PAUSE_VOLUME) {
            st.yieldSets = was > 0 ? 0 : was;           // the added sets come off
        } else if (add != 0) {
            int b = buildUpSets(track, st);
            int base = baseSets(track, st.level, st.weekIndex, st.carriedSets, b);
            int floor = yieldFloorSets(track, st.level, st.weekIndex, st.carriedSets, b);
            st.yieldSets = add > 0 ? was + add : Math.max(floor - base, was + add);
        }
        boolean moved = st.yieldSets != was || st.hybridYield != wasHy;
        if (moved) {
            st.yieldSinceMs = nowMs;
            /* R-23: AN ADD THE YIELD ASKED FOR WAITS TO BE JUDGED - pending until a reading in
             * the target, a level-up or the offer's answer; three more low readings meanwhile
             * bring the offer, never another add. */
            if (add > 0 && d.rule != null && d.rule.contains(Plan.LOW_YIELD_RULE))
                st.addPending = true;
        }
        /* t10 parity run 2, O-1: THE MORNING'S ONE CHANGE (TrainerTrackState#planChangeMs) - the
         * holds moved, the time cap's conversion was kept, or the pressure stepped. Not a high
         * arm's restart alone, which changes no work, and never the offer answered above. */
        if (moved || kept || d.action == Plan.ACTION_RAISE_PRESSURE) st.planChangeMs = nowMs;
        // R-21 (C2): a high-yield arm that changed nothing fell through - its streak restarts.
        if (d.restartsYield) {
            st.yieldSinceMs = nowMs;
            moved = true;
        }
        return moved || kept;
    }

    /**
     * KEEPS WHAT A DECISION CHANGES ONLY IN THE PLAN'S OWN COUNT (t10 fix, review B F2) - called
     * where the plan's change would reach your routine but the routine does not change
     * (SessionActivity#applyPlanTo, the signature unchanged). A volume step the time cap makes
     * run-neutral - a cut from 7 holds to 6 when 5 run either way, an add past the cap already
     * converted, a hybrid's own hold - used to be kept only by a rewrite, which never came: the
     * same decision every morning, and the pressure step behind it waiting for a Save that
     * changed nothing. Kept here as a rewrite would keep it (commitYield): the volume steps,
     * the cap's bookkeeping (Decision#r2) and a high arm that fell through (restartsYield).
     * Never the offer of a break - that is the person's to answer. Girth only; returns whether
     * anything moved (the caller saves).
     */
    public static boolean commitUnchanged(Model.TrainerTrackState st, int track, Plan.Decision d,
                                          long nowMs) {
        if (d == null) return false;
        boolean volume = d.action == Plan.ACTION_ADD_VOLUME
            || d.action == Plan.ACTION_REDUCE_VOLUME || d.action == Plan.ACTION_PAUSE_VOLUME;
        if (!volume && !d.r2() && !d.restartsYield) return false;
        return commitYield(st, track, d, nowMs);
    }

    /**
     * R-23 - THE OFFER ANSWERED ("Take a week off", "4 weeks of length focus" or "Not now"):
     * the pending add is cleared and the streak restarts, so the next offer or add needs
     * three new readings. The answer's own effect (the deload, the girth pause) is the
     * caller's. Returns true (the caller saves).
     */
    public static boolean answerYieldOffer(Model.TrainerTrackState st, long nowMs) {
        if (st == null) return false;
        st.addPending = false;
        st.yieldSinceMs = nowMs;
        return true;
    }

    /**
     * A LEVEL CROSSING KEEPS THE SET COUNT (the owner's decision, 2026-09-26: "keep total sets
     * unchanged across the level-up"). Called once the level, week and carried sets have moved,
     * with the total the track ran before. Where the new level reads a carried count (interval
     * L3/L4) the caller has already folded the yield sets into it, and this leaves none over;
     * where it has a table (L2) or a fixed start (traditional), the difference is kept as the
     * new level's yield sets. Never under the new level's floor. The streak restarts: the new
     * level has its own yield targets.
     */
    public static void carryYieldAcrossLevel(Model.TrainerTrackState st, int track,
                                             int totalBefore, long nowMs) {
        int b = buildUpSets(track, st);
        int base = baseSets(track, st.level, st.weekIndex, st.carriedSets, b);
        int floor = yieldFloorSets(track, st.level, st.weekIndex, st.carriedSets, b);
        st.yieldSets = Math.max(floor - base, totalBefore - base);
        /* THE STREAK STILL RESTARTS AT THE CROSSING - from the level's anchor
         * (TrainerTrackState#weekBaseMs, which the crossing sets; TrainerTab#fillGirthInputs
         * counts readings after it) - but the volume clock does not (R-26, fix d): yieldSinceMs
         * also times the no-readings fallback, and a fallback that was due waits only for the
         * next morning, never four more weeks. */
    }

    /**
     * TRADITIONAL GIRTH'S WEEKS START COUNTING ONCE (0.10): a girth track saved before its
     * holds grew by the week ({@link Model.TrainerTrackState#weekGrowth} false - an older file)
     * starts its level's growth from the level's first week, now. Never from where the weeks
     * since its old anchor would put it: somebody who ran 2 holds for months has not built up
     * to 6, and the guidance adds one hold at a time. Interval girth's table always advanced;
     * it is only marked. Returns whether anything moved (the caller saves).
     *
     * ABOVE LEVEL 1, THE HALF START (the owner's decision, 0.10, final): the level's first week
     * is 6 or 8 holds, three or four times the old rule's 2, so the track builds up to it
     * instead - from half the level's count (Plan#traditionalHalfStart: 3 at Level 2, 4 at
     * Levels 3 and 4) or from what it runs now (the old 2 plus its kept yield sets) if that is
     * more, one hold every Plan#TRAD_BUILD_WEEKS_PER_HOLD counted weeks (Mint#buildUpSets),
     * until it reaches the level's count; the notice says so once (Model buildSaid). A track
     * already running the level's count or more keeps what it runs. Level 1 starts at 3 as
     * everybody does.
     */
    public static boolean startWeekGrowth(int girthStyle, Model.TrainerTrackState st, long nowMs) {
        if (st == null || st.weekGrowth) return false;
        st.weekGrowth = true;
        if (girthStyle == Plan.TRACK_GIRTH_TRADITIONAL) {
            st.weekIndex = 1; st.weekBaseIndex = 1; st.weekBaseMs = nowMs;
            if (st.level >= Plan.L2) {
                int ranNow = Plan.TRAD_OLD_SETS + Math.max(0, st.yieldSets);
                if (buildUpFrom(st, girthStyle,
                        Math.max(Plan.traditionalHalfStart(st.level), ranNow), 0, nowMs))
                    st.buildSaid = false;
            }
        }
        return true;
    }

    /**
     * A GIRTH LEVEL CROSSING, ACCEPTED - what "Move to Level N" does to the track (moved here
     * from TrainerScreen's LevelUpTap so the worked timeline crosses levels the way the app
     * does). The set count carries (carryYieldAcrossLevel); L2 starts its own week table at
     * the 8 hg floor; L3 and L4 carry the volume and the working pressure.
     *
     * THE PRESSURE COUNT RESTARTS ONLY WHEN THE PRESSURE CHANGES (the owner's decision,
     * 2026-09-27). Every crossing used to restart it, even L2 -> L3 and L3 -> L4 where the
     * figure carries, so the first step at a new level waited three more counting weeks for
     * no change to the tissue. The pressure is written through setWorkingPressure, which
     * restarts the count on a different whole kPa and leaves it running on the same one; a
     * crossing that does not move the pressure does not touch the count at all. The next
     * step still asks the NEW level's milestone, cap, gentle-return and deload holds.
     */
    /**
     * THE WEEK ANCHOR, ONCE (moved from TrainerScreen#advanceWeekIndexes): a legacy track saved
     * before the anchor existed has its base frozen at the current position and `now`, so no
     * since-enrolment total is ever recomputed onto an already-advanced weekIndex. Returns
     * whether it set anything (the caller saves).
     *
     * Parity run 3, OPEN-7: AN ANCHOR ALREADY DATED IS NEVER MOVED. A base index of 0 with a
     * date (what a crossing used to leave) took the date to the next draw, so the level's
     * anchor - "levelled up today" (Plan.Inputs#levelUpToday) - moved a training morning on,
     * and the fallback, the pressure and the volume steps waited a morning longer.
     */
    public static boolean anchorWeek(Model.TrainerTrackState g, long now) {
        if (g == null) return false;
        boolean set = false;
        if (g.weekBaseIndex <= 0) { g.weekBaseIndex = Math.max(1, g.weekIndex); set = true; }
        if (g.weekBaseMs <= 0L) { g.weekBaseMs = now; set = true; }
        return set;
    }

    public static void crossGirthLevel(Model.TrainerTrackState st, int track, int nextLevel,
                                       long nowMs) {
        /* R-26 (fix d, the owner's decision, 2026-10-01): THE SETS CARRIED INTO A LEVEL ARE
         * CAPPED AT ITS TOP on entry (14 at Level 3, 18 at Level 4; traditional 6 / 7 / 9) -
         * the owner's tops are tops. */
        int totalBefore = Math.min(totalSets(track, st), Plan.volumeTopSets(track, nextLevel));
        // R-27: the new level has its own cap - nothing converted, nothing owed. R-23: a level-up
        // clears a pending add. R-25: the hybrid's kept holds start again at the new level's
        // count (6 -> 8).
        st.r2ExHolds = 0;
        st.r2PendKpa = 0;
        st.addPending = false;
        st.hybridYield = 0;
        // A TRADITIONAL BUILD-UP UNDER WAY (the half start) - and the counted week, if any, that
        // has not yet added its hold (read before the week moves).
        boolean building = buildingUp(track, st);
        int phase = building ? Math.max(0, Math.max(1, st.weekIndex) - st.buildWeek)
                               % Plan.TRAD_BUILD_WEEKS_PER_HOLD : 0;
        boolean said = st.buildSaid;
        endBuildUp(st);
        if (nextLevel == Plan.L2) {
            st.level = Plan.L2;
            st.weekIndex = 1; st.weekBaseIndex = 1; st.weekBaseMs = nowMs;
            /* L2 STARTS AT THE HIGHER OF ITS 8 HG FLOOR AND THE LEVEL 1 PLAN PRESSURE (APP-Q1,
             * the owner's decision in parity run 5): a level-up never lowers the pressure. A
             * Level 1 that climbed past its usual top toward "Most you will go to" (G2) went
             * down to 27 kPa on the day it moved up. The Level 1 figure is already within the
             * ceiling and "Most" (the climb's top), and Level 2's caps are no lower than Level
             * 1's, so it stays inside the new level's bounds; the prescription clamps as ever. */
            st.setWorkingPressure(Math.max(Plan.L234_FLOOR_KPA, st.pressureKpa), nowMs);
            st.carriedSets = 0;                                   // L2 has its own table
        } else if (nextLevel == Plan.L4) {
            // L3 -> L4: the volume carried into L3 carries again; L3 and L4 share the 10 hg
            // cap, so the working pressure carries unchanged.
            st.level = Plan.L4;
            // OPEN-7: a real anchor (index 1, as the first draw made it), dated today.
            st.weekIndex = 0; st.weekBaseIndex = 1; st.weekBaseMs = nowMs;
            st.carriedSets = totalBefore;                         // yield included
        } else {
            // L2 -> L3: the L2 end volume carries, kept yield included; pressure carries.
            st.level = Plan.L3;
            st.weekIndex = 0; st.weekBaseIndex = 1; st.weekBaseMs = nowMs;   // OPEN-7
            st.carriedSets = totalBefore;
        }
        // TRADITIONAL COUNTS ITS WEEKS AT THE NEW LEVEL FROM THE FIRST (0.10): Level 3's holds
        // grow from 6 by its week there (Plan#traditionalSets), anchored at the crossing.
        if (track == Plan.TRACK_GIRTH_TRADITIONAL && nextLevel != Plan.L2) {
            st.weekIndex = 1; st.weekBaseIndex = 1; st.weekBaseMs = nowMs;
        }
        carryYieldAcrossLevel(st, track, totalBefore, nowMs);
        /* ...LANDS ON THE NEW LEVEL'S COUNT, AS ANY CROSSING DOES, UNLESS THAT IS MORE THAN THE
         * BUILD-UP ALLOWS (the owner's decision, 0.10). A build-up adds one hold at a time, on its
         * own cadence; a crossing is not one of its weeks, so any rise at all is more than it
         * allows. Then the build-up goes on at the new level from the count run now, with the
         * counted week that had not yet added its hold carried - the next hold comes when it
         * would have. A new level whose count is no more than that is landed on as before. */
        if (building) {
            // (Its notice, if it has not been said yet, still is.)
            if (buildUpFrom(st, track, totalBefore, phase, nowMs)) st.buildSaid = said;
        }
        // The old level's mint pointer goes, so the new level's starting routine is offered.
        st.lastMintId = ""; st.lastMintSig = ""; st.lastMintMs = 0L;
    }

    /* ===================================================================== *
     *  PRESCRIPTION → SET FIELD VALUES (the mint's building blocks)          *
     * ===================================================================== */

    /** The concrete {@link Model.Set} field values one interval set carries. Pure so a
     *  desktop test can assert they match the prescription. */
    public static final class SetSpec {
        public final int up, lo, uh, lh, sp, dur;
        SetSpec(int up, int lo, int uh, int lh, int sp, int dur) {
            this.up = up; this.lo = lo; this.uh = uh; this.lh = lh; this.sp = sp; this.dur = dur;
        }
    }

    /**
     * THE REST A PRESCRIPTION ASKS FOR, by track AND level.
     *
     * A length prescription has no rest between its blocks - it is one continuous set - and
     * that is unchanged. What is new is that TRADITIONAL girth at L1 gets two minutes rather
     * than the interval rest: a traditional set is a long hold and a beginner needs longer
     * between them than somebody several levels in, and one number was covering both.
     *
     * One place, so the figure cannot differ between the prescription and the routine built
     * from it.
     */
    public static int restSecFor(int track, int level) {
        if (track == Plan.TRACK_LENGTH) return 0;
        // t10 R-08 (the owner's R3, 1 Oct 2026): traditional rests are 30 s at every level -
        // between holds and after the fatigue block. It was 120 s at L1 and 180 s above.
        if (track == Plan.TRACK_GIRTH_TRADITIONAL) return Plan.TRAD_REST_SEC;
        return REST_SEC;
    }

    /** One main work interval: pull to the prescribed pressure, hold {@code holdSec}, drop
     *  well below the floor for a few seconds. {@code dur} is exactly one hold+drop cycle so
     *  each of the N references in the work stage runs exactly one hold. */
    /* ================================ THE PROGRAM'S HAND ON A MINT (wave 3b) ======= */

    /**
     * PRESSURE BIAS (owner ruling Q3, redesigned in 0.10): gentle is the prescription less
     * one inHg, firm the prescription plus one, standard the prescription as-is - see the
     * track-aware form below. A girth track's rule.
     */
    public static int biasKpa(int prescribedKpa, int programPressure, int level,
                              int monthIndex, int ceilKpa) {
        return biasKpa(prescribedKpa, programPressure, Plan.TRACK_GIRTH_INTERVAL, level,
                       monthIndex, ceilKpa);
    }

    /**
     * THE BIAS, REDESIGNED (the owner's decision, 0.10): gentle is the plan less one inHg,
     * firm the plan plus one, standard the plan - offsets, not the band's floor and top. With
     * no personal offset and no stated maximum, for `track` at `level`: Scale#biasedKpa against
     * the track's usual top and the ceiling. The build asks Scale with the person's own limits
     * (RxBuild#personalKpa); this is the same rule for a caller with none.
     */
    public static int biasKpa(int prescribedKpa, int programPressure, int track, int level,
                              int monthIndex, int ceilKpa) {
        int hard = Scale.hardKpa(ceilKpa, 0.0, false, monthIndex);
        int top = Scale.effectiveTopWholeKpa(track, level, monthIndex, 0.0, false);
        return Math.min(hard, Scale.biasedKpa(prescribedKpa, programPressure, top, hard));
    }

    /**
     * COMPENSATION TO THE SAME TARGET (owner ruling Q4). Work below the prescribed
     * pressure counts pro-rata — a minute at 80%% of the prescription is 0.8 effective
     * minutes — and the mint adds whole cycles at the prescription until the block's
     * effective volume reaches what the plan asked for. Returns the extra cycles.
     */
    public static int compensationCycles(double effectiveSec, int targetSec, int cycleSec) {
        if (cycleSec <= 0 || effectiveSec >= targetSec) return 0;
        return (int) Math.ceil((targetSec - effectiveSec) / cycleSec);
    }

    /** One chunk's effective seconds: its duration weighted by pressure/prescribed
     *  (a ramp weighs its average). Never above its own duration. */
    public static double effectiveSec(int durSec, int upKpa, int up2Kpa, boolean ramp,
                                      int prescribedKpa) {
        if (prescribedKpa <= 0) return durSec;
        double up = ramp ? (upKpa + up2Kpa) / 2.0 : upKpa;
        return durSec * Math.min(1.0, up / prescribedKpa);
    }

    /** The chunk-by-chunk pull for the ASCENDING and PYRAMID work shapes: a climb from
     *  the band floor to the prescription (ascending), or up and back (pyramid, peak =
     *  the prescription, in the middle; with two chunks, on the last). FIXED and RAMP_IN_SET chunks sit at the
     *  prescription (a ramp-in-set carries its climb inside the set). */
    public static int chunkUpKpa(int workShape, int idx, int total, int floorKpa,
                                 int prescribedKpa) {
        if (total <= 1 || (workShape != Model.Program.WORK_ASCENDING
                        && workShape != Model.Program.WORK_PYRAMID))
            return prescribedKpa;
        int lo = Math.max(2, Math.min(floorKpa, prescribedKpa));
        double f = idx / (double) (total - 1);
        /* D8 - A PYRAMID OF TWO PEAKS ON ITS LAST CHUNK (the owner's decision). The triangle
         * below is 0 at both ends, so two chunks sat at the floor and the peak never came:
         * the picker said "pyramid" and the work ran lighter than the figure it was named for.
         * Two chunks cannot go up AND back, so they go up - the same as ascending. */
        if (workShape == Model.Program.WORK_ASCENDING || total == 2)
            return (int) Math.round(lo + (prescribedKpa - lo) * f);
        /* AND AN EVEN COUNT PEAKS ON ITS TWO MIDDLE CHUNKS. The triangle's apex fell between
         * them, so four chunks topped out two-thirds of the way up and never reached the
         * prescription either. Measured in chunks from the nearer end, over the middle's
         * distance: identical to the triangle for every odd count. */
        int fromEnd = Math.min(idx, total - 1 - idx);
        double tri = Math.min(1.0, fromEnd / (double) ((total - 1) / 2));
        return (int) Math.round(lo + (prescribedKpa - lo) * tri);
    }

    public static SetSpec workSet(Rx rx) {
        return workSet(rx, DROP_KPA);
    }

    /** {@link #workSet(Rx)} with the person's own preferred drop rather than the default
     *  {@link #DROP_KPA}. Held to {@link #dropFor} here rather than trusting the caller,
     *  because this is the one place the figure becomes a set: within what may be saved, and
     *  1.0 inHg under this prescription's pull once above the usual top. */
    public static SetSpec workSet(Rx rx, int dropKpa) {
        int uh = rx.holdSec;
        return new SetSpec(rx.pressureKpa, dropFor(dropKpa, rx.pressureKpa), uh, DROP_SEC,
                           rx.powerPct, uh + DROP_SEC);
    }

    /* ---- TRACTION: a length HOLD, not a cycle -------------------------------------- */

    /** The fatigue block's holds in a length session - ten of these, sixty seconds each. */
    public static final int TRACTION_FATIGUE_HOLD_SEC = 60;
    /** A strain hold: five minutes at the governed load. */
    public static final int TRACTION_STRAIN_HOLD_SEC = 300;
    /** The gaps between them. Short between fatigue holds, half a minute between strains. */
    public static final int TRACTION_FATIGUE_REST_SEC = 10;
    public static final int TRACTION_STRAIN_REST_SEC = 30;

    /**
     * A TRACTION SET: pull to the pressure that produces the governed load, and HOLD.
     *
     * NO DROP, and that is the whole difference from {@link #workSet}. The drop-and-repump is
     * a girth technique - it is what makes an interval an interval - and a traction set that
     * cycled would be a girth set wearing a length label, doing girth work to tissue the plan
     * is trying to lengthen. So {@code lo} is the same pressure as {@code up} and {@code lh}
     * is zero: nothing to drop to and no time spent dropping. A WiringCheck invariant holds
     * that open, because the flag on the stage and the shape of the set have to agree and
     * nothing else in the app would notice if they stopped.
     *
     * The pressure is NOT the prescription's working pressure: it is whatever
     * {@link Traction#kpaForLbAtBore} says produces {@code loadLb} in the length cylinder of
     * this bore (owner, 2026-09-30: by the bore, never a girth), rounded to the whole kPa the
     * wire takes. Rounded ONCE, here, so what is displayed is what is sent.
     * Long holds stitch through the existing WIRE_HOLD_MAX machinery like any other hold.
     *
     * THE BETWEEN-HOLD GAPS ARE NOT IN HERE. An earlier comment claimed they "ride inside the
     * set, at zero pressure" - they cannot, because lh is 0 and there is therefore no low
     * phase to ride in. RxBuild#tractionStage emits them as real rest sets between the holds.
     */
    /** Wave 3a (owner ruling): the gap between traction holds is a RELEASE TO 0 inside
     *  the repetition — {pull, hold, drop to 0, gap} — so one set carries the whole
     *  block and the device cycles it. Never a partial drop: lo is 0 by construction
     *  (WiringCheck invariant 28's new rule), because a partial-pressure cycle would be
     *  girth work wearing a length label. */
    public static SetSpec tractionSet(double loadLb, double boreCm, int holdSec,
                                      int gapSec, int ceilKpa) {
        int p = clampTractionKpa(Traction.kpaForLbAtBore(loadLb, boreCm), ceilKpa);
        return new SetSpec(p, 0, holdSec, gapSec, POWER_PCT, holdSec + gapSec);
    }

    /**
     * TRACTION'S OWN CLAMP, and the band cap is deliberately NOT in it.
     *
     * {@link #clampPressureKpa} folds in Plan#pressureCapKpa - the girth levels' monthly
     * pressure band, month one at 6 hg and a soft cap at 8-10. That band governs the
     * EXPANSION CODA and nothing else: it is a statement about how hard to inflate tissue,
     * and a traction block is not inflating anything. Clamping the traction pressure with it
     * would silently cap the LOAD at whatever pounds that pressure happens to make at this
     * user girth - a limit nobody wrote and no card could explain.
     *
     * What does bind traction is the load ladder's own cap in POUNDS, applied upstream in
     * Plan#loadCapLb, plus the two hard limits that bind everything: the absolute 15 hg and
     * the user's device ceiling. Those two are here.
     */
    static int clampTractionKpa(double wantKpa, int ceilKpa) {
        double capped = Math.min(wantKpa, Plan.ABSOLUTE_CAP_KPA);
        int asInt = (int) Math.round(capped);
        // Same correction as clampPressureKpa above, and it matters more here: a traction
        // prescription asks for whatever pressure makes the governed LOAD, so it reaches the
        // absolute cap far more often than a girth band ever does.
        int hardMax = Plan.absoluteCapWholeKpa();
        if (asInt > hardMax) asInt = hardMax;
        if (asInt > ceilKpa) asInt = ceilKpa;
        if (asInt < 1) asInt = 1;
        return asInt;
    }

    /**
     * The load a traction set will ACTUALLY deliver, pounds - which is not always the load it
     * was asked for. The command is rounded to a whole kPa and clamped to the device ceiling,
     * and both of those move the load; a card that printed the requested figure would be
     * stating a number the tissue never feels.
     */
    public static double deliveredLoadLb(double loadLb, double boreCm, int ceilKpa) {
        return Traction.loadLbAtBore(
            clampTractionKpa(Traction.kpaForLbAtBore(loadLb, boreCm), ceilKpa), boreCm);
    }

    /** One fatigue-block interval: a short 45 s hold at the same pressure. The STAGE it sits
     *  in is flagged {@link Model.Stage#fatigueBlock} so its frames are excluded from net. */
    public static SetSpec fatigueSet(Rx rx) {
        return fatigueSet(rx, HOLD_FATIGUE_SEC);
    }

    /** R11-5: the fatigue hold at the person's own length (Model#rxFatigueHoldSec, 30-60 s). */
    public static SetSpec fatigueSet(Rx rx, int holdSec) {
        int h = holdSec <= 0 ? HOLD_FATIGUE_SEC : holdSec;
        return new SetSpec(rx.pressureKpa, DROP_KPA, h, DROP_SEC, rx.powerPct, h + DROP_SEC);
    }

    /**
     * R11-5 - THE FATIGUE BLOCK KEEPS ITS MINUTES. The block is `baseHolds` holds of
     * {@link #HOLD_FATIGUE_SEC} (10, or 15 extended); at another hold length it runs the same
     * minutes at pressure in whole holds - 15 of 30 s, 8 of 60 s - never none.
     */
    public static int fatigueHolds(int baseHolds, int holdSec) {
        if (holdSec <= 0 || holdSec == HOLD_FATIGUE_SEC) return Math.max(1, baseHolds);
        long n = Math.round(baseHolds * (double) HOLD_FATIGUE_SEC / holdSec);
        return (int) Math.max(1L, n);
    }

    /* ===================================================================== *
     *  NAME + SIGNATURE (idempotency)                                        *
     * ===================================================================== */

    /** The mint name — level + params, NEVER a week number, so a routine lives for weeks
     *  (round-2 ruling). E.g. "Trainer · Girth L2 · 12×2min @ 8.0 inHg" in the user's
     *  display unit (via {@link Model.Fmt#mag}). */
    /** What every name this class mints begins with. Named, because it is the only thing
     *  that tells an app-written label apart from one a person chose. */
    public static final String MINT_NAME_PREFIX = "Trainer · ";

    /**
     * IS THIS NAME THE APP'S OR THE USER'S?
     *
     * A prescription rewritten in place keeps its id and its name, and keeping the name is
     * right for a routine somebody has named themselves - it is the thing they recognise on
     * Today. It is wrong for the label this class wrote, because that label STATES the
     * prescription: sets, hold and pressure, or a load in pounds. Left alone through a
     * rewrite it goes on stating the old one, and a routine called "5×2min @ 5.9 inHg"
     * that now pulls at 2.6 lb is not a familiar name, it is a false one.
     */
    public static boolean isMintedName(String name) {
        return name != null && name.startsWith(MINT_NAME_PREFIX);
    }

    public static String name(Rx rx) {
        return name(rx, rx.sets);
    }

    /**
     * THE NAME OF A ROUTINE THAT RUNS `holds` WORK HOLDS - the prescription's own count, or
     * more where the builder added make-up cycles (a Gentle bias, a ramp's climb). The builder
     * names what it laid out (the owner's decision, 0.10): a routine called "5×2min" that runs
     * seven two-minute holds states a session the pump is not told to run.
     */
    public static String name(Rx rx, int holds) {
        String hold = trimMin(rx.holdMin());
        // POLISH #6: the SIGNED figure, same formatter as the card's PEAK tile — the
        // title said "@ 5.0 inHg" while the tile beside it said "−5.0 inHg".
        return "Trainer · " + trackName(rx.track) + " " + TrainerTab.levelLabel(rx.level)
            + " · " + Math.max(1, holds) + "×" + hold + "min @ " + Model.Fmt.p(rx.pressureKpa);
    }

    /**
     * A TRACTION SESSION'S NAME states the LOAD, because that is what it is governed by.
     *
     * The girth name ends in a pressure because a girth set IS a pressure; a length session's
     * pressure is an implementation detail of producing pounds, and two users at the same
     * load run different pressures. The figure quoted is the DELIVERED load - what the
     * rounded, clamped command actually produces - so the name cannot promise a number the
     * tissue never feels.
     */
    public static String tractionName(Rx rx, double loadLb, double boreCm, int ceilKpa,
                                      boolean girthFocus) {
        if (girthFocus)
            return "Trainer · Length " + TrainerTab.levelLabel(rx.level)
                 + " · girth focus · expansion only";
        double got = deliveredLoadLb(loadLb, boreCm, ceilKpa);
        return "Trainer · Length " + TrainerTab.levelLabel(rx.level)
             + " · traction @ " + Traction.boundLb(got);
    }

    /** One decimal, and no trailing ".0" on a whole number of pounds. */
    static String trimLb(double lb) {
        double r = Math.round(lb * 10.0) / 10.0;
        long whole = (long) r;
        return (Math.abs(r - whole) < 1e-9) ? String.valueOf(whole) : String.valueOf(r);
    }

    private static String trimMin(double m) {
        if (Math.abs(m - Math.rint(m)) < 1e-9) return String.valueOf((int) Math.rint(m));
        return String.valueOf(m);
    }

    private static String trackName(int track) {
        switch (track) {
            case Plan.TRACK_GIRTH_INTERVAL: return "Girth";
            case Plan.TRACK_GIRTH_TRADITIONAL: return "Girth·trad";
            case Plan.TRACK_LENGTH: return "Length";
            case Plan.TRACK_FEEDER: return "Feeder";
            default: return "?";
        }
    }

    /**
     * The prescription's IDENTITY — track, level, sets, pressure, hold and fatigue, joined
     * into a stable string. Two prescriptions with the same signature are the same routine;
     * a save is idempotent per track-week because the signature is compared, not the name
     * (the user may rename a mint — the marker is a track-id flag, never a name match) and
     * not the routine's live content (the user may edit it out of band; gates read actuals,
     * so a stale mint still counts by what it delivered). Deliberately EXCLUDES restSec —
     * rest is recovery, not a gate input, and folding it in would re-mint on a cosmetic-only
     * change.
     */
    public static String signature(Rx rx) {
        return rx.track + "|" + rx.level + "|" + rx.sets + "|" + rx.pressureKpa + "|"
            + rx.holdSec + "|" + (rx.fatigue ? 1 : 0);
    }

    /**
     * THE SAME IDENTITY, PLUS WHAT SHAPE THE ROUTINE IS BUILT IN.
     *
     * A prescription says how hard and how many; it does not say whether the session is the
     * plain expansion one or the five-block traction one, because that is a fact about the
     * RACK. See {@link Model#tractionShapeTag} for why leaving it out was silent and total.
     *
     * AN EMPTY TAG APPENDS NOTHING - not even a separator - so every signature that has no
     * shape to declare is byte-for-byte the string this method produced before the shape
     * existed. That is the whole reason the tag is empty rather than "none": a stored
     * signature from an older install still matches, and nothing re-offers that should not.
     */
    public static String signature(Rx rx, String shapeTag) {
        String base = signature(rx);
        return (shapeTag == null || shapeTag.length() == 0) ? base : base + "|" + shapeTag;
    }

    /**
     * THE PRESCRIPTION A SIGNATURE WAS MINTED FROM - its first six fields, as {@link #signature}
     * writes them. Null for anything that is not one: empty (cleared), short or malformed.
     *
     * What a routine with no track state of its own - the feeder - is compared against to tell
     * a user's edit from the plan's own shape. A main track rebuilds the same answer from its
     * stored pressure; the feeder's pressure is derived from the girth track's, which may have
     * moved since, so the signature stored beside the routine is the only record of what the
     * routine was minted as.
     */
    public static Rx rxFromSignature(String sig) {
        if (sig == null || sig.length() == 0) return null;
        String[] f = sig.split("\\|", -1);
        if (f.length < 6) return null;
        try {
            int track = Integer.parseInt(f[0]), level = Integer.parseInt(f[1]);
            int sets = Integer.parseInt(f[2]), kpa = Integer.parseInt(f[3]);
            int hold = Integer.parseInt(f[4]), fatigue = Integer.parseInt(f[5]);
            if (sets < 1 || kpa < 1 || hold <= 0) return null;
            /* The offset it was scaled by (0.10) is recorded to the hundredth of a kPa
             * (Scale#offsetTag); the whole kPa it moved this figure by is not, and is taken as
             * that offset rounded - what it is unless a hard limit bound the figure. It reaches
             * only the rebuilt routine's scale stamp, never a pressure. */
            int off = (int) Math.round(Scale.offsetOfSig(sig));
            return new Rx(track, level, sets, hold, 0, kpa, fatigue == 1, sets * (hold / 60.0),
                          POWER_PCT, off);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A save is a no-op mint (open the existing one) when a routine already carries this
     *  signature AND still exists in the library. A deleted mint ({@code routineExists ==
     *  false}) re-offers, exactly as the plan's audit ruling accepts ("delete just means the
     *  suggestion re-offers; suggestions are stateless"). */
    public static boolean alreadyMinted(String storedSig, String currentSig,
                                        boolean routineExists) {
        // An "Adjust first..." save has answered the same offer (#answers): the person's own
        // sets and pressure are a record of what was built, not a new offer.
        return routineExists && answers(storedSig, currentSig);
    }

    /* ===================================================================== *
     *  "ADJUST FIRST..." - THE PERSON'S OWN SETS AND PRESSURE (0.10)         *
     * ===================================================================== */

    /*
     * THE OWNER'S RULING: THE PRESSURE A PERSON SETS IS THEIRS. A Firm or Gentle bias lands on
     * the plan's own prescription, never on a pressure somebody chose in "Adjust first..." -
     * it used to, so Firm put a pressure the person had lowered straight back up to the band's
     * top, and Gentle pulled a raised one down to the floor. The day's cut and a gentle day's
     * slower pull still apply, as to any trainer routine.
     *
     * AND AN ADJUSTED SAVE IS STILL THE PLAN'S ROUTINE: the plan keeps it current (a taper
     * step) and does not read it as edited. That needs the stored signature to say what was
     * built, so a fresh build of it reproduces it (SavedMint#edited). The prescription's six
     * fields stay the OFFER's - it is what stops the same offer being made twice - and one more
     * segment records the adjustment: "ADJ<sets>", and ":<kPa>" when the pressure is the
     * person's. An unadjusted save carries no segment, so every signature stored before this
     * existed reads exactly as it did.
     *
     * THE SAVE ANSWERS TWO PRESCRIPTIONS (#answers): the offer it adjusted, and the same
     * prescription at the person's pressure - which is what the plan prescribes next, because
     * the track's working pressure advances to what was saved. Answering only the second would
     * let an offer the person turned down by keeping their pressure (a raise, with its clock
     * still running) come straight back and rewrite the routine to it.
     */
    private static final String ADJ_TAG = "ADJ";

    /** What an "Adjust first..." save built: its sets, and - where `ownKpa` - the pressure the
     *  person set, in place of the prescription's. */
    public static final class Adjust {
        public final int sets;
        public final boolean ownKpa;
        /** The person's pressure, kPa; 0 when the pressure is the plan's. */
        public final int kpa;
        Adjust(int sets, boolean ownKpa, int kpa) {
            this.sets = sets; this.ownKpa = ownKpa; this.kpa = ownKpa ? kpa : 0;
        }
        /** The segment that records it. */
        public String tag() { return ADJ_TAG + sets + (ownKpa ? ":" + kpa : ""); }
        /** `rx` as this adjustment builds it. */
        public Rx on(Rx rx) { return adjusted(rx, sets, ownKpa ? kpa : rx.pressureKpa); }
    }

    /**
     * THE PRESCRIPTION AS "ADJUST FIRST..." BUILDS IT: `sets` and `kpa` in place of the plan's,
     * the net target recomputed from the sets (sets x hold, as the plan counts). `rx` itself
     * when neither moved. Whether `kpa` is the person's - and so takes no bias - is the
     * build's to be told (RxBuild.Day#ownPressure).
     */
    public static Rx adjusted(Rx rx, int sets, int kpa) {
        if (rx == null || (sets == rx.sets && kpa == rx.pressureKpa)) return rx;
        int n = Math.max(1, sets);
        // The plan's exact figure rides along while the pressure is the plan's (REAL-15).
        return new Rx(rx.track, rx.level, n, rx.holdSec, rx.restSec, kpa, rx.fatigue,
                      n * (rx.holdSec / 60.0), rx.powerPct, rx.offKpa,
                      kpa == rx.pressureKpa ? rx.planExactKpa : Double.NaN);
    }

    /**
     * THE SIGNATURE TO STORE FOR AN "ADJUST FIRST..." SAVE of the offer signed `sig`: the offer's
     * own signature and the adjustment's segment - the sets that were built, and the person's
     * pressure where it is theirs (`ownKpa`). `sig` itself (any older segment taken off) when
     * nothing was adjusted.
     */
    public static String adjustedSignature(String sig, int sets, int kpa, boolean ownKpa) {
        String base = withoutAdjust(sig);
        String[] f = base.split("\\|", -1);
        if (f.length < 6) return base;
        boolean setsMoved;
        try {
            setsMoved = Integer.parseInt(f[2]) != sets;
        } catch (NumberFormatException e) {
            return base;
        }
        if (!ownKpa && !setsMoved) return base;
        return base + "|" + new Adjust(Math.max(1, sets), ownKpa, kpa).tag();
    }

    /** The adjustment a stored signature records, or null for a signature that has none
     *  (every unadjusted save, and every signature stored before 0.10). */
    public static Adjust adjustOf(String sig) {
        if (sig == null) return null;
        String[] segs = sig.split("\\|", -1);
        for (int i = 6; i < segs.length; i++) {
            String s = segs[i];
            if (!isAdjustSegment(s)) continue;
            String[] f = s.substring(ADJ_TAG.length()).split(":", -1);
            try {
                int n = Integer.parseInt(f[0]);
                int k = f.length > 1 ? Integer.parseInt(f[1]) : 0;
                if (n < 1 || (f.length > 1 && k < 1)) return null;
                return new Adjust(n, f.length > 1, k);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** The signature with its adjust segment taken out: the offer the save answered. */
    public static String withoutAdjust(String sig) {
        if (sig == null) return "";
        if (sig.indexOf(ADJ_TAG) < 0) return sig;
        String[] segs = sig.split("\\|", -1);
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < segs.length; i++) {
            if (i >= 6 && isAdjustSegment(segs[i])) continue;
            if (!first) b.append('|');
            b.append(segs[i]);
            first = false;
        }
        return b.toString();
    }

    /**
     * THE SAME OFFER AT THE PERSON'S OWN PRESSURE - what the plan prescribes once the track's
     * working pressure has moved to what they saved: the stored offer with its pressure field
     * set to theirs. {@link #withoutAdjust} where the pressure was not theirs.
     */
    public static String adjustedIdentity(String sig) {
        String base = withoutAdjust(sig);
        Adjust a = adjustOf(sig);
        if (a == null || !a.ownKpa) return base;
        String[] f = base.split("\\|", -1);
        if (f.length < 6) return base;
        f[3] = String.valueOf(a.kpa);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < f.length; i++) { if (i > 0) b.append('|'); b.append(f[i]); }
        return b.toString();
    }

    /** Does the save signed `storedSig` answer the prescription signed `currentSig`? - the
     *  offer itself, or (after "Adjust first...") the same offer at the person's pressure. */
    public static boolean answers(String storedSig, String currentSig) {
        if (storedSig == null || currentSig == null || storedSig.length() == 0) return false;
        return withoutAdjust(storedSig).equals(currentSig)
            || adjustedIdentity(storedSig).equals(currentSig);
    }

    /** "ADJ", one to three digits, and optionally ":" and one to three more - nothing else, so
     *  a cylinder id or a shape tag can never be read as one. */
    private static boolean isAdjustSegment(String s) {
        if (s == null || !s.startsWith(ADJ_TAG)) return false;
        String[] f = s.substring(ADJ_TAG.length()).split(":", -1);
        if (f.length > 2) return false;
        for (int i = 0; i < f.length; i++) {
            if (f[i].length() < 1 || f[i].length() > 3) return false;
            for (int j = 0; j < f[i].length(); j++)
                if (!Character.isDigit(f[i].charAt(j))) return false;
        }
        return true;
    }

    /* ===================================================================== *
     *  DECISION-HISTORY DEDUP                                                *
     * ===================================================================== */

    /**
     * Whether a decision shown at render should be RECORDED into the bounded history, or is
     * the same event already sitting at the front for this track (a decision is an event;
     * the same unchanged decision across many renders is ONE history entry). Compares the
     * fired action, the exact rule text and the provenance tag against the most recent
     * recorded decision for the track. A different action/rule/tag is a new event; an
     * identical one is a redraw and must not double-record.
     */
    public static boolean shouldRecord(Model.TrainerDecision latestForTrack, int action,
                                       int tag, String rule) {
        if (latestForTrack == null) return true;
        boolean same = latestForTrack.action == action
            && latestForTrack.tag == tag
            && eqStr(latestForTrack.rule, rule);
        return !same;
    }

    private static boolean eqStr(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * HOW MANY WORKING CYCLES A SAVED ROUTINE STILL RUNS at the prescribed pressure and hold
     * - the count that decides whether it is still the prescription.
     *
     * COUNTED IN CYCLES, NOT IN SETS OR PRESETS, and that distinction is the whole reason
     * this exists. The mint stopped writing one set per repetition long ago (audit F1: ten
     * identical "Work hold" rows for one instruction) and now writes ONE set whose duration
     * is sets x cycle. Anything that counts objects therefore counts 1 where the
     * prescription said 10, and every saved prescription reads as edited the instant it is
     * saved - which is what happened.
     *
     * A cycle is hold plus drop, so the count is the set's duration divided by its own
     * cycle. That is the mint's own arithmetic, inverted. It gives the same answer for the
     * old one-set-per-rep shape (each set is one cycle), for today's chunked shape, and for
     * a block long enough to have been split across several chunks - so the check does not
     * have to know which shape it is looking at, and cannot break the next time that
     * changes.
     *
     * READ FROM THE SETS, not from the presets they expand into: a hold longer than the wire
     * allows is STITCHED into several shorter presets at expansion time, none of which holds
     * for the prescribed length, so a preset-level count would report every long-hold
     * prescription as edited. The stored set still says what was asked for.
     *
     * A WARM-UP AND A FATIGUE BLOCK CONTRIBUTE NOTHING. The first is a ramp and is skipped
     * outright; the second holds for a different length and fails the match. Somebody who
     * eases into the pressure has not stopped following the plan.
     */
    public static int cyclesAt(java.util.List<Model.Set> sets, int kpa, int holdSec) {
        if (sets == null) return 0;
        int n = 0;
        for (int i = 0; i < sets.size(); i++) {
            Model.Set s = sets.get(i);
            if (s == null || s.rest || s.ramp) continue;
            if (s.up != kpa || s.uh != holdSec) continue;
            // THROUGH Manual#cycles, the rule the run screen's own planned/delivered
            // counts already use. Two implementations of "how many cycles is this" would
            // eventually disagree, and the one place they would disagree is a routine the
            // Trainer calls edited while the run screen counts it as prescribed.
            n += Manual.cycles(s.uh, s.lh, s.dur);
        }
        return n;
    }

    /**
     * HOW MANY OF THESE PRESETS ARE A HOLD AT THE PRESCRIBED PRESSURE, for the prescribed
     * length. Kept for what it measures - what the pump would be SENT - which is a different
     * question from {@link #cyclesAt}'s, and is the one an as-run check wants.
     *
     * A prescription that was saved and then EDITED used to be indistinguishable from one
     * still being followed: the signature records what was OFFERED, not what the routine
     * currently does, so a routine quietly running one set short kept reporting itself as
     * the plan while delivering less net than the plan was scoring it against.
     *
     * Counted over the presets the routine would actually SEND rather than over its stages,
     * so an edit anywhere - a set deleted, a pressure nudged, a hold shortened - shows up in
     * one place. A warm-up ramp contributes nothing, because its presets are at other
     * pressures; that is what makes it a warm-up rather than work.
     */
    public static int holdsAt(java.util.List<Model.Preset> plan, int kpa, int holdSec) {
        if (plan == null) return 0;
        int n = 0;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p != null && p.up == kpa && p.uh == holdSec) n++;
        }
        return n;
    }
}
