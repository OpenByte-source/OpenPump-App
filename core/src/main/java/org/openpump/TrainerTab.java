package org.openpump;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;

/**
 * Stage H, Task 3 — PURE support logic for the Trainer tab's onboarding and its main view's
 * per-track "this week" read from the logs. No {@code import android}, so test.sh
 * auto-discovers this file exactly like {@link Plan} and compiles it into the desktop
 * self-test.
 *
 * WHAT LIVES HERE VS {@link Plan}. Plan.java (Task 1) owns every progression RULE — gates,
 * floors, caps, debounce, cadence — and Task 3 is explicitly READ-ONLY against it: nothing
 * below re-derives a Plan rule. What Plan's own public surface does not offer is a way to
 * turn a self-reported "how long have you been pumping / what pressure are you running"
 * pair of answers into a starting table position, or a way to read {@link Model#sessLog}/
 * {@link Model#measLog} into the numbers the main view's "this week" line shows — Plan is
 * hand ed numbers, it never goes looking for them in a log. That plumbing lives here,
 * built entirely out of Plan's own public queries plus plain arithmetic over Model's
 * already-pure data classes.
 */
public final class TrainerTab {

    /**
     * PASS H - the local day a session happened on: the one IT recorded, or derived from its
     * timestamp when it predates that.
     *
     * This is what decides whether a week has enough training days to advance a level, so a
     * phone that changes timezone must not silently move a session between weeks. Deriving
     * is the fallback rather than the rule, and it is what every old record gets - which is
     * exactly what it got before, so nothing changes for data already filed.
     */
    static int sessionDay(Model.Sess s) {
        if (s == null) return 0;
        return s.dayKey != 0 ? s.dayKey : PhotoCalendar.dayKey(s.ts);
    }
    private TrainerTab() { }

    /* ===================================================================== *
     *  ONBOARDING — deriving a starting position from two self-reported      *
     *  answers, with the conflict/conservative-default rule the plan's own   *
     *  Task 3 line requires.                                                 *
     * ===================================================================== */

    /** One track's derived starting position, plus the trail back to the answers that
     *  produced it — every field on the confirm screen must be traceable to one of these. */
    public static final class Derived {
        public final int level;
        /** 1-based position within the level's own week table; 0 when the track/level has
         *  no table (traditional girth, or length — both static/table-less tracks). */
        public final int weekIndex;
        public final int monthIndex;
        public final int engine;           // Plan.ENGINE_CALENDAR / ENGINE_METRIC
        public final double floorKpa;
        /** The pressure this track actually starts at — the reported answer, clamped into
         *  the derived level's own band. */
        public final double pressureKpa;
        public final double netTargetMin;  // Plan.netMilestoneMin(level) — what to reach next
        /** True when the months answer and the pressure answer implied DIFFERENT levels —
         *  the plan's own Task 3 line: show both readings, default to the lower one. */
        public final boolean conflict;
        /** What the MONTHS answer alone implied, before the pressure answer's conservative
         *  cap was applied — the "both readings" half of a conflict banner. */
        public final int monthsImpliedLevel;
        /** Whether the pressure answer was the one that pulled the level down. */
        public final boolean pressureCapped;

        Derived(int level, int weekIndex, int monthIndex, int engine, double floorKpa,
                double pressureKpa, double netTargetMin, boolean conflict,
                int monthsImpliedLevel, boolean pressureCapped) {
            this.level = level; this.weekIndex = weekIndex; this.monthIndex = monthIndex;
            this.engine = engine; this.floorKpa = floorKpa; this.pressureKpa = pressureKpa;
            this.netTargetMin = netTargetMin; this.conflict = conflict;
            this.monthsImpliedLevel = monthsImpliedLevel; this.pressureCapped = pressureCapped;
        }
    }

    /**
     * Derives the GIRTH track's starting position (interval or traditional style) from the
     * two onboarding answers.
     *
     * MONTHS -> a level, via the same gates Plan.java's evaluate() reads (month 6 -> L2/L3
     * boundary handled by the L2->L3 gate reading "volume carries"; month 12 -> the L3->L4
     * gate). Below month 6, interval style additionally reads its own week table length to
     * split L1 from L2 (traditional has no such table, so it stays L1 until month 6; its
     * own week at the level - what its holds grow by, 0.10 - is read from the months too).
     *
     * PRESSURE -> a CAP on that level, never a promotion: a reported pressure inside the L1
     * band (below the L2-4 floor) caps the derived level at L1 regardless of what the
     * months answer implied (pressure cannot IMPLY L2+ on its own, since L2/L3/L4 share one
     * floor — it can only rule OUT L1 having been left behind). This is the plan's own
     * "conflict -> more conservative (lower) level" rule, made concrete: the lower reading
     * always wins the level, and the conflict is reported so the confirm screen can name
     * both answers.
     */
    public static Derived deriveGirth(int girthStyle, int monthsPumping, double pressureKpa) {
        return deriveGirth(girthStyle, monthsPumping, pressureKpa, true,
                           Plan.ABSOLUTE_CAP_KPA, 0);
    }

    /**
     * {@link #deriveGirth(int,int,double)} WITH THE PERSON'S OWN ANSWERS: whether they are
     * new to pumping, the device ceiling, and the maximum they said they are willing to go
     * to (0 = not stated).
     *
     * The three-argument form above is the beginner reading, and it is what the app did for
     * everybody: {@link Plan#pressureCapKpa} applies the month-1 cap at L1 whenever the
     * month index is 0, which it is for every plan on the day it is created. Somebody who
     * has pumped for years and is simply starting a PLAN is not in their first month of
     * pumping, and the app now has a way to know the difference instead of inferring it.
     */
    public static Derived deriveGirth(int girthStyle, int monthsPumping, double pressureKpa,
                                       boolean newToPumping, double ceilKpa,
                                       double statedMaxKpa) {
        return deriveGirthAt(girthStyle, monthsPumping, pressureKpa, newToPumping, ceilKpa,
                             statedMaxKpa, 0, 0);
    }

    /**
     * R11-3 - THE SAME, AT A PLACED LEVEL AND WEEK (`placedLevel` > 0): the level and week come
     * from the length of the person's current session (Placement#girth), not from the months;
     * the pressure is held to that level's band exactly as a months placement's is. With
     * `placedLevel` 0 this is {@link #deriveGirth}.
     */
    public static Derived deriveGirthAt(int girthStyle, int monthsPumping, double pressureKpa,
                                        boolean newToPumping, double ceilKpa,
                                        double statedMaxKpa, int placedLevel, int placedWeek) {
        int months = Math.max(0, monthsPumping);
        int effectiveWeeks = months * 4;
        int monthIdx = Plan.monthIndex(effectiveWeeks);

        int monthsLevel;
        if (monthIdx >= Plan.L3_GATE_MONTH) monthsLevel = Plan.L4;
        else if (monthIdx >= Plan.L2_GATE_MONTH) monthsLevel = Plan.L3;
        else if (girthStyle == Plan.TRACK_GIRTH_INTERVAL
                 && effectiveWeeks > Plan.GIRTH_INTERVAL_L1.length) monthsLevel = Plan.L2;
        else monthsLevel = Plan.L1;

        /* IN WHOLE kPa, AS THE ANSWER IS STORED (0.10, the owner's decision): "8.0 inHg" is 27
         * kPa, and read against 8 hg's exact 27.09 it sat "below the floor" - so somebody at 6
         * or 12 months answering 8.0 was placed at Level 1 by a fraction no pump command can
         * carry. The floor is reached as the Level 1 gate's 8 hg is (Plan#pressureReachedKpa). */
        boolean pressureCapped = !Plan.pressureReachedKpa(pressureKpa, Plan.L234_FLOOR_KPA);
        int level = pressureCapped ? Math.min(monthsLevel, Plan.L1) : monthsLevel;
        boolean conflict = level != monthsLevel;

        int weekIdx = 0;
        if (girthStyle == Plan.TRACK_GIRTH_INTERVAL) {
            if (level == Plan.L1) {
                weekIdx = clampI(effectiveWeeks, 1, Plan.GIRTH_INTERVAL_L1.length);
            } else if (level == Plan.L2) {
                weekIdx = clampI(effectiveWeeks - Plan.GIRTH_INTERVAL_L1.length,
                                  1, Plan.GIRTH_INTERVAL_L2.length);
            }
        } else if (girthStyle == Plan.TRACK_GIRTH_TRADITIONAL) {
            /* TRADITIONAL STARTS AT ITS LEVEL'S FIRST WEEK (t10 REAL-10, the parity run; the
             * editor's model and the spec's "Traditional L1 start, 3 holds"): its holds grow by
             * the weeks TRAINED on the plan at the level (Plan#traditionalSets), and a setup has
             * trained none of them yet. The position used to be read from the months answer as
             * the interval table's is, so two months of pumping started Level 1 at four holds
             * and seven months started Level 3 at its top - where the first low-yield step found
             * no room and offered a break instead of the hold it adds (REAL-7). */
            weekIdx = 1;
        }
        if (placedLevel > 0) {
            // R11-3: placed by the session's length. The months still say what they implied
            // (monthsImpliedLevel), but the placement is not a conflict between two answers.
            level = placedLevel;
            weekIdx = placedWeek;
            conflict = false;
        }

        double floor = Plan.floorKpa(level);
        /* THE MONTH-1 CAP IS A FACT ABOUT THE PERSON, NOT THE PLAN. Asking
         * pressureCapKpa for month 0 is what pinned an experienced person's answer to 6 hg
         * on the day they enrolled; for somebody not new to pumping the band cap for their
         * level is the right bound, and the month-1 rule simply does not apply. Everything
         * else in startCapKpa — the absolute 15 hg, the device ceiling, their own stated
         * maximum — applies either way, and is folded in beside it. */
        double bandCap = Plan.pressureCapKpa(level, newToPumping ? monthIdx
                                                                 : Math.max(1, monthIdx));
        double cap = Math.min(bandCap,
            Plan.startCapKpa(newToPumping, monthIdx, ceilKpa, statedMaxKpa));
        // A cap under the level's own floor would invert the clamp and hand back the cap as
        // a "start". The floor is what the level MEANS, so a maximum below it is the answer
        // that has to give, and the confirm screen's conflict note already exists to say so.
        // An answer that has reached the floor in whole kPa (above) is AT it, and is kept as
        // given rather than lifted by the fraction to the floor's exact figure - which would
        // leave a -0.09 kPa "offset" behind (Scale#offsetFromAnswer) for an answer that is the
        // plan's own. Below the floor in whole kPa, the floor still lifts it, as before.
        double floorAt = Plan.pressureReachedKpa(pressureKpa, floor)
            ? Math.min(floor, pressureKpa) : floor;
        double startPressure = clampD(pressureKpa, Math.min(floorAt, cap),
                                      Math.max(floorAt, cap));

        return new Derived(level, weekIdx, monthIdx, Plan.engineFor(monthIdx), floor,
            startPressure, Plan.netMilestoneMin(level), conflict, monthsLevel, pressureCapped);
    }

    /**
     * Derives the LENGTH track's starting position — no week table and no yield gate
     * (Plan.evaluateLength's own doc): its level from the months answer by its calendar
     * gates, and a starting pressure, capped by the guide's month-1 (<=6 hg) then soft-cap (8-10 hg) rule
     * (Plan.LENGTH_MONTH1_CAP_KPA / LENGTH_SOFT_CAP_HI_KPA), never above the girth working
     * pressure the onboarding answer already gave (this app asks for ONE working-pressure
     * answer; a fresh length track cannot reasonably start ABOVE the pressure the user just
     * said they run at). {@code level} is the length track's own calendar level for the months
     * answer ({@link #lengthLevelForMonth}): Plan.evaluateLength's month gates read it, so a
     * level left at L1 for an experienced person was a level-up question waiting on day one.
     */
    public static Derived deriveLength(int monthsPumping, double girthPressureKpa) {
        return deriveLength(monthsPumping, girthPressureKpa, true, Plan.ABSOLUTE_CAP_KPA, 0);
    }

    /**
     * {@link #deriveLength(int,double)} WITH THE PERSON'S OWN ANSWERS — and this is the
     * method the reported defect was about. A length working pressure of 11 inHg came back
     * as 5.9: the guide's month-1 length cap is 6 hg, the plan was in month 0 because it had
     * just been created, and the answer was simply overwritten.
     *
     * THE LENGTH BAND IS STILL A BAND. For somebody new to pumping both length caps stand
     * exactly as the guide writes them — 6 hg for the first month, the 8-10 hg soft cap
     * after. For somebody who is not, they are what the guide calls them: SOFT. The answer
     * stands, {@link Plan#pastUsualPressure} is what says it is above the usual band, and
     * the only hard limits left are the ones that are hard for everybody.
     */
    public static Derived deriveLength(int monthsPumping, double girthPressureKpa,
                                        boolean newToPumping, double ceilKpa,
                                        double statedMaxKpa) {
        int months = Math.max(0, monthsPumping);
        int effectiveWeeks = months * 4;
        int monthIdx = Plan.monthIndex(effectiveWeeks);
        double cap = Plan.startCapKpa(newToPumping, monthIdx, ceilKpa, statedMaxKpa);
        if (newToPumping) {
            cap = Math.min(cap, monthIdx < 1 ? Plan.LENGTH_MONTH1_CAP_KPA
                                             : Plan.LENGTH_SOFT_CAP_HI_KPA);
        }
        double startPressure = Math.min(Math.max(0.0, girthPressureKpa), cap);
        int level = lengthLevelForMonth(monthIdx);
        return new Derived(level, effectiveWeeks, monthIdx, Plan.engineFor(monthIdx), 0.0,
            startPressure, Plan.LENGTH_A_NET_MIN, false, level, false);
    }

    /**
     * THE LENGTH LEVEL THE MONTHS ANSWER PLACES, by the length track's own gates - calendar
     * months 3, 6 and 12 (Plan#lengthGateMetNextLevel), walked from Level 1 as the plan would
     * have walked them.
     *
     * It was Level 1 for everybody: the comment above said length had no levels, which stopped
     * being true when its calendar levels came in. Seven months of pumping was placed at Level
     * 1 and offered "month 3 reached - move up to Level 2" on the first screen after setup, in
     * place of a routine. Placed by the same gates, the next one falls at its own month, read
     * from the same month index every gate reads (TrainerTab#monthIndexNow: the months answered
     * plus the months since). At and after month 12 the track starts at Level 4, as the girth
     * track does at its month-12 crossing (deriveGirth): the fork's other arms are answers to a
     * crossing, and somebody placed past it has made none.
     */
    static int lengthLevelForMonth(int monthIndex) {
        int level = Plan.L1;
        for (int guard = 0; guard < 4; guard++) {
            int next = Plan.lengthGateMetNextLevel(level, monthIndex);
            if (next == 0) break;
            level = next;
        }
        return level;
    }

    /** "L1".."L4" — the confirm screen's and the main view's shared level label. */
    public static String levelLabel(int level) {
        switch (level) {
            case Plan.L1: return "L1";
            case Plan.L2: return "L2";
            case Plan.L3: return "L3";
            case Plan.L4: return "L4";
            default: return "L?";
        }
    }

    /** The track's own short name, shared by onboarding and the main view. */
    public static String trackLabel(int track) {
        switch (track) {
            case Plan.TRACK_GIRTH_INTERVAL: return "Girth · interval";
            case Plan.TRACK_GIRTH_TRADITIONAL: return "Girth · traditional";
            case Plan.TRACK_LENGTH: return "Length";
            // The feeder was never asked for by name here, because nothing until now put a
            // feeder BESIDE the other tracks - it had its own card with its own title. The
            // suggestion rows do, and an unnamed row reading "?" is the result.
            case Plan.TRACK_FEEDER: return "Feeder";
            default: return "?";
        }
    }

    public static String engineLabel(int engine) {
        return engine == Plan.ENGINE_METRIC ? "metric engine" : "calendar engine";
    }

    private static int clampI(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /* ---- TRADITIONAL GIRTH GROWS BY THE WEEK (0.10, Plan#traditionalSets) - said ---------- */

    /** "Level 1, week 1: 3 five-minute holds. The next is added at week 5." - where traditional
     *  girth is in its level's growth, from its week there, with the standard fatigue block. */
    public static String traditionalWeeksSaid(int level, int weekIndex) {
        return traditionalWeeksSaid(level, weekIndex, Mint.standardFatSec(level));
    }

    /**
     * ...with the person's own fatigue block (`fatSec`, Mint#r2FatSec): THE HOLDS THE ROUTINE
     * RUNS (t10 device walk M4). The level's growth (Plan#traditionalSets) is held to the
     * session's time cap the way Mint#prescribe writes it (R-27, Plan#r2MaxHolds - L3's 36
     * minutes with the 7.5-minute fatigue block leave 5 five-minute holds), so the line and the
     * routine cannot disagree. Where the cap stops the growth the line says so, and that the
     * plan raises the pressure in place of more holds, rather than naming a week whose hold
     * would never run.
     */
    public static String traditionalWeeksSaid(int level, int weekIndex, int fatSec) {
        int w = Math.max(1, weekIndex), grown = Plan.traditionalSets(level, w);
        int cap = traditionalCapHolds(level, fatSec), sets = Math.min(grown, cap);
        String at = "Level " + level + (Plan.traditionalTopWeek(level) > 1 ? ", week " + w : "")
            + ": " + sets + " five-minute holds" + (level >= Plan.L3 ? " after the fatigue block" : "");
        int top = Plan.traditionalTopWeek(level);
        int next = w + Plan.TRAD_WEEKS_PER_HOLD - ((w - 1) % Plan.TRAD_WEEKS_PER_HOLD);
        boolean capped = grown > cap
            || (top > 1 && w < top && Plan.traditionalSets(level, next) > cap);
        if (capped) return at + capWords(level);
        if (top <= 1) return at + ", the same every week.";
        if (w >= top) return at + ", the most this level adds.";
        return at + ". The next is added at week " + next + ".";
    }

    /** The most five-minute holds a traditional session at `level` runs under its time cap
     *  with a `fatSec` fatigue block - the cap Mint#prescribe writes with. */
    static int traditionalCapHolds(int level, int fatSec) {
        return Plan.r2MaxHolds(level, Mint.HOLD_TRADITIONAL_SEC, Math.max(0, fatSec));
    }

    /** ", the most its 36-minute cap lets run: the plan raises the pressure in place of more
     *  holds." (R-27) */
    private static String capWords(int level) {
        return ", the most its " + Plan.r2CapMin(level) + "-minute cap lets run: the plan "
            + "raises the pressure in place of more holds.";
    }

    /** #traditionalWeeksSaid(Model.TrainerTrackState, int) with the standard fatigue block. */
    public static String traditionalWeeksSaid(Model.TrainerTrackState st) {
        return traditionalWeeksSaid(st, Mint.standardFatSec(st == null ? Plan.L1 : st.level));
    }

    /**
     * ...for the track as it stands: under a build-up (the half start, 0.10), where it is in the
     * build-up and the week its next hold comes - "Level 2, week 3: 4 of the level's 6
     * five-minute holds, building up. The next is added at week 5." - else the line above. Both
     * counts held to the session's time cap, as the routine is (M4).
     */
    public static String traditionalWeeksSaid(Model.TrainerTrackState st, int fatSec) {
        int track = Plan.TRACK_GIRTH_TRADITIONAL;
        if (!Mint.buildingUp(track, st)) return traditionalWeeksSaid(st.level, st.weekIndex, fatSec);
        int w = Math.max(1, st.weekIndex), cap = traditionalCapHolds(st.level, fatSec);
        int sets = Math.min(cap,
            Mint.baseSets(track, st.level, w, st.carriedSets, Mint.buildUpSets(track, st)));
        int count = Math.min(cap, Mint.baseSets(track, st.level, w, st.carriedSets));
        // A build-up already at the cap runs what the level runs: the line above says it.
        if (sets >= count) return traditionalWeeksSaid(st.level, st.weekIndex, fatSec);
        String at = "Level " + st.level + ", week " + w + ": " + sets + " of the level's " + count
            + " five-minute holds" + (st.level >= Plan.L3 ? " after the fatigue block" : "");
        // The next week whose build-up count is higher (the week position moves one counted
        // week at a time, so this is where the plan's own count puts it).
        int next = w + Plan.TRAD_BUILD_WEEKS_PER_HOLD
            - (Math.max(0, w - st.buildWeek) % Plan.TRAD_BUILD_WEEKS_PER_HOLD);
        return at + ", building up. The next is added at week " + next + ".";
    }

    /** What the Trainer's note adds under a build-up - "" otherwise. */
    public static String traditionalBuildUpWords(Model.TrainerTrackState st) {
        if (!Mint.buildingUp(Plan.TRACK_GIRTH_TRADITIONAL, st)) return "";
        return "\n\n" + BUILD_UP_WORDS;
    }

    /** The build-up, in full (the half start, 0.10). */
    static final String BUILD_UP_WORDS =
        "Your traditional routine was set up before this growth, so it builds up to your level's "
        + "count instead of jumping to it: it started at half the level's count, or at what you "
        + "were running if that was more, and adds one five-minute hold every "
        + Plan.TRAD_BUILD_WEEKS_PER_HOLD + " training weeks, counted the same way, until it "
        + "reaches the level's count. From then on it follows the level as above. Moving up a "
        + "level during the build-up carries on building from where you are.";

    /** How traditional girth grows, in full - the Trainer's note behind the line above. */
    public static final String TRADITIONAL_GROWTH_WORDS =
        "Traditional girth grows as the guidance does. Level 1 starts at "
        + Plan.TRAD_L1_START_SETS + " five-minute holds and adds one every "
        + Plan.TRAD_WEEKS_PER_HOLD + " training weeks at the level, up to " + Plan.TRAD_L1_TOP_SETS
        + " (30 minutes). Level 2 runs " + Plan.TRAD_L2_SETS + ". Level 3 puts the fatigue "
        + "intervals first and grows from " + Plan.TRAD_L3_START_SETS + " the same way, up to "
        + Plan.TRAD_L3_TOP_SETS + " (35 minutes). Level 4 runs " + Plan.TRAD_L4_SETS
        + ", and readings can take it to " + Plan.TRAD_L4_TOP_SETS + ".\n\n"
        + "Each session's time under pressure is capped, the fatigue block included: "
        + Plan.R2_CAP_MIN[0] + ", " + Plan.R2_CAP_MIN[1] + ", " + Plan.R2_CAP_MIN[2] + " and "
        + Plan.R2_CAP_MIN[3] + " minutes at Levels 1 to 4. Holds past the cap are not added: "
        + "the plan raises the pressure instead, so the routine can run fewer holds than the "
        + "level's count.\n\n"
        + "A training week counts with " + Plan.TRAINING_WEEK_FULL_SESSIONS
        + " full sessions in it, or " + Plan.TRAINING_WEEK_MIN_DAYS + " shorter days, the same "
        + "count the interval table moves on; a week the plan repeats "
        + "after missed sessions does not move it. Holds your readings add come on top, and the "
        + "pressure steps, rests and deloads are as before.";
    private static double clampD(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /* ===================================================================== *
     *  ONBOARDING PRE-FILL — suggested answers from existing logs (plan       *
     *  round-3 ruling: "onboarding pre-fills from logs when history exists"). *
     * ===================================================================== */

    /** A suggested onboarding answer pair, or a null field where the log has nothing to
     *  suggest — never a fabricated 0, matching this app's own "unknown is never zero"
     *  rule (the same one {@link Model.Sess#netTupSec}'s doc states). */
    public static final class Prefill {
        public final Integer monthsPumping;
        public final Double pressureKpa;
        Prefill(Integer monthsPumping, Double pressureKpa) {
            this.monthsPumping = monthsPumping; this.pressureKpa = pressureKpa;
        }
    }

    /**
     * Suggests onboarding answers from the existing session log: MONTHS from the span since
     * the EARLIEST logged (non-manual) plan session to now, PRESSURE from the peak of the
     * MOST RECENT one — "where you are now", not an average blurred across a history that
     * may have started much lower. The plan's own round-3 wording is "the claim wins, the
     * data informs": this never writes into onboarding state itself, it only hands the
     * caller a suggestion to pre-fill an editable field with.
     */
    public static Prefill prefillFromLogs(List<Model.Sess> sessions, long nowMs) {
        if (sessions == null || sessions.isEmpty()) return new Prefill(null, null);
        long earliest = Long.MAX_VALUE;
        boolean anyLogged = false;
        Double recentPressure = null;
        // sessLog.all is newest-first (SessLog#file inserts at 0), so the FIRST non-manual
        // session with a real peak is the most recent one — recentPressure is set once.
        for (int i = 0; i < sessions.size(); i++) {
            Model.Sess s = sessions.get(i);
            if (s == null || s.manual) continue;
            anyLogged = true;
            if (s.ts < earliest) earliest = s.ts;
            if (recentPressure == null && s.peakKpa != null) recentPressure = s.peakKpa;
        }
        if (!anyLogged) return new Prefill(null, null);
        return new Prefill(Integer.valueOf(monthsBetween(earliest, nowMs)), recentPressure);
    }

    /**
     * Whole calendar months (a 30.44-day average) between two instants, rounded to the
     * nearest month. {@code fromMs <= 0} answers 0 — "never happened" (never enrolled,
     * never logged) carries no duration, never a fabricated one. {@code toMs} before
     * {@code fromMs} clamps the span to 0 rather than going negative.
     *
     * THE ONE PLACE every calendar-months-elapsed figure in the Trainer tab computes
     * through — {@link #prefillFromLogs} (span since the earliest session) and the main
     * view's "Enrolled: N months ago" / the Recalibrate seed (span since
     * {@code trainerEnrolledAt}) all read this, so the 30.44-day average is stated once
     * and the three answers can never quietly drift apart.
     *
     * NOT a substitute for {@link Plan#monthIndex} — that is effective TRAINING weeks
     * over 4 (deload weeks count, empty weeks do not), the guide's own gate arithmetic,
     * and nothing in the persisted Model stores the running total it needs (Task 2's own
     * note: that walk is left to Task 4, at evaluation time, from the logs). This is a
     * smaller, calendar-clock claim instead — always correct, always available, and
     * never fed to a gate.
     */
    /**
     * WHOLE MONTHS ELAPSED, FLOORED - what a GATE or a CAP must read.
     *
     * {@link #monthsBetween} ROUNDS, which is right for the one thing it is for: telling a
     * person their programme is "about six months" old, and seeding an onboarding prefill
     * from an existing enrolment. It is wrong for a gate. Half of 30.44 days is 15.22, so a
     * rounded monthIndex reached 1 on DAY SIXTEEN - and with it the length level gates at
     * months 3/6/12, the switch from the calendar ladder to the metric one at
     * LENGTH_METRICS_FROM_MONTH, the first-month pressure cap and the first-month LOAD cap.
     * Somebody seventeen days into the programme was told the metrics had opened and had
     * their month-one cap lifted, on a body that had done a fortnight's work.
     *
     * A month that has not finished has not elapsed. Flooring is also the only reading under
     * which monthIndex 0 means "still in the first month", which is exactly what every cap
     * written as `monthIndex &lt; 1` already assumes.
     */
    /**
     * THE MONTH THE PLAN IS IN — the one answer every gate, cap, engine choice and header
     * asks for, and the only place the person's own answer is combined with the calendar.
     *
     * months pumping when they last answered + whole months since they answered it.
     *
     * Every one of those readers used to call {@link #monthsElapsed} against
     * {@code trainerEnrolledAt} alone, which says "months since this plan started" — 0 on
     * the day of enrolment for somebody who has pumped for years. The wizard asked them
     * how long they had been pumping and then dropped the answer, so an experienced person
     * was held at the beginner's month-1 cap and told they were in their first month.
     *
     * A file saved before the answer was kept has {@code trainerMonthsAt == 0}: it falls
     * back to the enrolment date, which is exactly what it did before.
     */
    public static int monthIndexNow(Model m, long nowMs) {
        if (m == null) return 0;
        int answered = Math.max(0, m.trainerMonthsPumping);
        long from = m.trainerMonthsAt > 0L ? m.trainerMonthsAt : m.trainerEnrolledAt;
        return answered + monthsElapsed(from, nowMs);
    }

    /** RECALIBRATE'S MONTHS PRE-FILL: the real total - the months answered at setup plus
     *  the whole months since ({@link #monthIndexNow}), inside the question's 0-360 range.
     *  It was the months since enrolment alone, which left out everything answered at
     *  setup (the owner's decision, 2026-09-27). */
    public static int recalibrateMonths(Model m, long nowMs) {
        return Math.max(0, Math.min(360, monthIndexNow(m, nowMs)));
    }

    public static int monthsElapsed(long fromMs, long toMs) {
        if (fromMs <= 0L) return 0;
        long spanMs = Math.max(0L, toMs - fromMs);
        return (int) Math.floor(spanMs / (30.44 * 24.0 * 60.0 * 60.0 * 1000.0));
    }

    /** ABOUT how many months, ROUNDED - for prose and for the onboarding prefill. Never for
     *  a gate or a cap: see {@link #monthsElapsed}. */
    public static int monthsBetween(long fromMs, long toMs) {
        if (fromMs <= 0L) return 0;
        long spanMs = Math.max(0L, toMs - fromMs);
        return (int) Math.round(spanMs / (30.44 * 24.0 * 60.0 * 60.0 * 1000.0));
    }

    /* ===================================================================== *
     *  "THIS WEEK" — computed from the logs, per track, every render (no      *
     *  stored counters). Calendar weeks, Monday-anchored (plan round-3        *
     *  revision), matching Schedule.DAY_ABBR's own Monday-first convention.   *
     * ===================================================================== */

    public static final int TRACKED_TARGET_DEFAULT = 2;

    public static final class WeekStatus {
        /** Distinct DAYS this calendar week that carried a qualifying session on this
         *  track — Plan's own "frequency counted in days, not sessions" rule. */
        public final int trainedDays;
        public final boolean weekQualifies;      // TrainingWeek's rule (W1), this week so far
        /** The week by that rule: days, the days that ran, and the sessions' worth of the
         *  plan delivered - what the card's progress line says. */
        public TrainingWeek.Tally tally = new TrainingWeek.Tally();
        public final int sessionCount;           // raw session count, informational
        public final int trackedCount;           // measLog readings this week matching the track
        public final int trackedTarget;
        public final double netDeliveredMin;
        public final double netTargetMin;
        /** Whether netTargetMin came from a real week-table row (a live prescription) or
         *  fell back to the level's net MILESTONE (no table at this level/style — L3+,
         *  traditional and length all have none) — the two need different copy: "target"
         *  for a real weekly prescription, "toward the milestone" for a gate reading. */
        public final boolean hasTargetTable;

        WeekStatus(int trainedDays, boolean weekQualifies, int sessionCount, int trackedCount,
                   int trackedTarget, double netDeliveredMin, double netTargetMin,
                   boolean hasTargetTable) {
            this.trainedDays = trainedDays; this.weekQualifies = weekQualifies;
            this.sessionCount = sessionCount; this.trackedCount = trackedCount;
            this.trackedTarget = trackedTarget; this.netDeliveredMin = netDeliveredMin;
            this.netTargetMin = netTargetMin; this.hasTargetTable = hasTargetTable;
        }
    }

    /**
     * This track's status for the calendar week containing {@code nowMs}, read from
     * {@code model.sessLog}/{@code model.measLog}. A session counts toward this track when
     * its routine (resolved live via {@link Model#routine}) carries this exact
     * {@link Model.Routine#trainerTrack} marker — a deleted routine's past sessions simply
     * stop attributing (the same "delete just means the suggestion re-offers; suggestions
     * are stateless" tolerance the plan's own audit ruling accepts for a marked routine).
     * A measurement counts as "tracked" for GIRTH when {@link Model.Reading#methodMeasuresGirth}
     * is true of it, for LENGTH when {@link Model.Reading#methodMeasuresLength} is —
     * STANDARDIZED readings measure both, exactly as those methods already define.
     */
    public static WeekStatus weekStatus(Model model, int track, int level, int weekIndex,
                                        long nowMs) {
        long weekStart = mondayStartMs(nowMs);
        long weekEnd = weekStart + 7L * 24L * 60L * 60L * 1000L;

        HashSet<Integer> days = new HashSet<Integer>();
        int sessionCount = 0;
        double netMin = 0.0;
        List<Model.Sess> sess = model.sessLog.all;
        for (int i = 0; i < sess.size(); i++) {
            Model.Sess s = sess.get(i);
            if (s == null) continue;
            if (s.ts < weekStart || s.ts >= weekEnd) continue;
            // F3b — TWO WAYS FOR WORK TO BELONG TO THIS TRACK, and they are not the same
            // kind of belonging. A session run from a routine MARKED for the track is a
            // training session: it is a trained day, it is a session count, and its net
            // time counts. A session the person COUNTED afterwards (a set tried once, a
            // manual cycle) contributes its net time and nothing else — see
            // Model.Sess#countedTrack for why. Without that separation a two-minute
            // one-off would read as a training day, and the person would be punished for
            // logging honest extra work.
            boolean onPlan = !s.manual && sessionOnTrack(model, s, track);
            boolean counted = !onPlan && s.countedTrack == track;
            if (!onPlan && !counted) continue;
            if (onPlan) {
                sessionCount++;
                days.add(Integer.valueOf(sessionDay(s)));
            }
            if (s.netTupSec != null) netMin += s.netTupSec.doubleValue() / 60.0;
        }

        int trackedCount = 0;
        List<Model.Reading> reads = model.measLog.all;
        for (int i = 0; i < reads.size(); i++) {
            Model.Reading r = reads.get(i);
            if (r == null || r.ts < weekStart || r.ts >= weekEnd) continue;
            boolean matches = (track == Plan.TRACK_LENGTH)
                ? Model.Reading.methodMeasuresLength(r.method)
                : Model.Reading.methodMeasuresGirth(r.method);
            if (matches) trackedCount++;
        }

        double netTarget;
        boolean hasTable;
        if (track == Plan.TRACK_LENGTH) {
            netTarget = Plan.LENGTH_A_NET_MIN;
            hasTable = false;
        } else {
            Plan.Week w = weekTableRow(track, level, weekIndex);
            if (w != null && !w.deload) { netTarget = w.netTupMin; hasTable = true; }
            else { netTarget = Plan.netMilestoneMin(level); hasTable = false; }
        }

        TrainingWeek.Tally tally = trackTally(model, track,
            weekSessions(model, track, weekStart, weekEnd, false), weekStart, nowMs);
        WeekStatus ws = new WeekStatus(days.size(), tally.qualifies, sessionCount,
            trackedCount, TRACKED_TARGET_DEFAULT, netMin, netTarget, hasTable);
        ws.tally = tally;
        return ws;
    }

    private static boolean sessionOnTrack(Model model, Model.Sess s, int track) {
        Model.Routine r = model.routine(s.routineId);
        return r != null && r.trainerTrack == track;
    }

    /* ===================================================================== *
     *  ENGINE INPUTS — the log-derived signals Task 4 feeds Plan.evaluate.   *
     *  Pure over Model; computed at evaluation time, never stored (plan:     *
     *  "gates read actuals", Task 2's note that these counters are Task 4's   *
     *  to derive from the logs).                                             *
     * ===================================================================== */

    /** One safety cap on the week-by-week walk below — ten years of weeks, so a corrupt
     *  future timestamp can never spin it. */
    private static final int MAX_WEEK_WALK = 520;

    /**
     * The plan-wide count of QUALIFYING training weeks (≥3 trained days on the track) in the
     * calendar weeks between {@code fromMs} and {@code nowMs} — the engine's
     * {@code accumulatedTrainingWeeks}, which drives the usage-linked deload cadence (round-2:
     * "deload is usage-linked — due after 3 accumulated training weeks since the last deload;
     * inactivity doesn't burn deloads"). {@code fromMs} is the anchor: the enrollment instant,
     * or the end of the most recent deload week when one has been taken. Empty weeks add
     * nothing (the count advances only on real training), so time off never accrues a deload.
     */
    public static int accumulatedTrainingWeeks(Model model, int track, long fromMs, long nowMs) {
        return qualifyingWeeks(model, track, fromMs, nowMs);
    }

    /**
     * THE PLAN-WIDE DELOAD CADENCE (S12, the owner's decision 2026-09-26): qualifying weeks
     * between {@code fromMs} and {@code nowMs}, where a week qualifies when three or more of
     * its days carried a trainer session on ANY track - girth in either style, or length.
     *
     * The deload window is one for both tracks, but the weeks that brought it due were
     * counted on the girth track alone while girth was on: somebody training length four
     * days and girth two never brought a deload due, although length was being trained at
     * the guidance's own length frequency. The guidance asks for a week off every four weeks
     * of TRAINING, on either track. A feeder is a top-up, not a training day (the
     * owner's ruling, 2026-09-16), and a manual cycle is not the plan, so neither counts -
     * the same "any track" {@link #lastAnyPlanSessionMs} already asks for a layoff.
     *
     * NOT IN THE GUIDANCE: how to count weeks when two tracks are trained. The union of the
     * days is the owner's choice (2026-09-26); a day with both tracks is one day.
     */
    public static int planTrainingWeeks(Model model, long fromMs, long nowMs) {
        return qualifyingWeeks(model, ANY_TRACK, fromMs, nowMs);
    }

    /**
     * t10 REAL-1 - THE CADENCE DELOAD IS DUE AND NOT YET ANSWERED: the plan-wide weeks since the
     * last deload's end (or enrolment) have reached this cycle's count (Plan#deloadDue, the rule
     * the tracks' own evaluation applies), with no week off on record from now on - one answered
     * for a later day moves the anchor to its end, so it is not due again. Not while the
     * plan-wide safety flag stands or a layoff is open (the plan steps back first), nor while
     * the plan is paused.
     *
     * Until it is answered nothing re-anchors: the deload stays due, and the next cycle counts
     * from the end of the week off the person picks (Deload#dueStartMs).
     */
    public static boolean cadenceDeloadDue(Model m, long nowMs) {
        if (m == null || !m.trainerEnrolled) return false;
        if (m.trainerState == Model.TRAINER_STATE_SAFETY_FLAG) return false;
        if (inDeloadWeek(m, nowMs)) return false;
        // A week or more away is asked about first ("Were you on a deload?"), and steps back.
        if (Deload.layoff(m, nowMs)) return false;
        return Plan.deloadDue(cadenceWeeks(m, nowMs),
            !m.trainerFirstDeloadTaken, monthIndexNow(m, nowMs));
    }

    /**
     * THE CADENCE'S WEEKS AS THE QUESTION COUNTS THEM: the plan-wide weeks since the anchor, from
     * the sessions filed BEFORE TODAY. The deload comes due the morning after the session that
     * made the block's last week count, so the question is put before that morning's session -
     * which runs and counts - and not the evening the week qualified, when "from tomorrow" would
     * take away the day the editor's model trains (N4).
     */
    public static int cadenceWeeks(Model m, long nowMs) {
        if (m == null) return 0;
        return qualifyingWeeks(m, ANY_TRACK, deloadAnchorMs(m), nowMs, Deload.dayStartMs(nowMs));
    }

    /** Whether the plan asks when the due deload starts at `nowMs`: it is due, and "Not now"
     *  was not the answer today ({@link Model#deloadDueAskedDay}) - asked again next morning. */
    public static boolean deloadStartAskDue(Model m, long nowMs) {
        return cadenceDeloadDue(m, nowMs) && m.deloadDueAskedDay != Summary.dayNumber(nowMs);
    }

    /** {@link #qualifyingWeeks}'s "any trainer track" - girth either style, or length. */
    private static final int ANY_TRACK = -1;

    private static int qualifyingWeeks(Model model, int track, long fromMs, long nowMs) {
        return qualifyingWeeks(model, track, fromMs, nowMs, Long.MAX_VALUE);
    }

    /** The same, counting only sessions filed before {@code beforeMs}. Each week is judged by
     *  the one rule ({@link TrainingWeek}): a track's own by W1, the plan-wide one by W2. */
    private static int qualifyingWeeks(Model model, int track, long fromMs, long nowMs,
                                       long beforeMs) {
        if (model == null || fromMs <= 0L || nowMs < fromMs) return 0;
        long weekMs = 7L * 24L * 60L * 60L * 1000L;
        int count = 0, guard = 0;
        for (long start = mondayStartMs(fromMs); start <= nowMs && guard < MAX_WEEK_WALK;
             start += weekMs, guard++) {
            long end = start + weekMs;
            // t10 REAL-1: the plan-wide cadence counts from the anchor itself - the ACTUAL
            // end of the last week off - not from the Monday of its week: a light session
            // inside the week off is not the new cycle's training day.
            // Parity run 3, A3-1: A TRACK'S OWN WEEKS TOO. The L1 -> L2 crossing anchors
            // Level 2's table on its day, and that week's earlier sessions ran at Level 1:
            // they are not a Level 2 week (the pressure clock already counts so).
            long from = Math.max(start, fromMs), to = Math.min(end, beforeMs);
            long asOf = Math.min(nowMs, beforeMs);
            boolean counts = track == ANY_TRACK
                ? planWeek(model, from, to, start, end, asOf).qualifies
                : trackTally(model, track, weekSessions(model, track, from, to, false), start,
                             asOf).qualifies;
            if (counts) count++;
        }
        return count;
    }

    /** R11-4 - the counted weeks of `track` from `fromMs` to `nowMs`, as their Monday starts,
     *  oldest first: the weeks {@link #accumulatedTrainingWeeks} counts (GainBrake's series). */
    public static List<Long> countedWeekStarts(Model model, int track, long fromMs, long nowMs) {
        List<Long> out = new java.util.ArrayList<Long>();
        if (model == null || fromMs <= 0L || nowMs < fromMs) return out;
        long weekMs = 7L * 24L * 60L * 60L * 1000L;
        int guard = 0;
        for (long start = mondayStartMs(fromMs); start <= nowMs && guard < MAX_WEEK_WALK;
             start += weekMs, guard++) {
            long from = Math.max(start, fromMs);
            if (trackTally(model, track, weekSessions(model, track, from, start + weekMs, false),
                           start, nowMs).qualifies)
                out.add(Long.valueOf(start));
        }
        return out;
    }

    /**
     * DOES A TRACK'S ROW SHOW ON THE TRAINER TAB? (TrainerScreen#trackSpeaks; the edited-routine
     * question is asked after, by the screen.) An event always shows; a hold shows when its
     * prescription is not the saved routine's (`alreadyMinted` false).
     *
     * FIX11 (device recheck EMU13, High) - A STEP THAT WAITS ALWAYS SHOWS. The brake's decision
     * is a hold (GainBrake#hold), so with the routine saved and unedited the row was hidden -
     * and the "You're gaining... Step up now?" offer, and after Wait the "...waits until <date>"
     * line, are drawn only inside that row. They never appeared in normal use. Girth and length
     * alike.
     */
    public static boolean rowShows(Plan.Decision d, boolean alreadyMinted) {
        if (d == null) return false;
        if (d.action != Plan.ACTION_HOLD) return true;
        if (GainBrake.kindOf(d) >= 0) return true;
        return !alreadyMinted;
    }

    /** R11-4 - girth's readings on target: the newest counted yield reading of the track
     *  inside the level's window (the readings a yield streak counts, #yieldStreaks). False
     *  with none. */
    public static boolean latestYieldOnTarget(Model model, int girthTrack, int level) {
        if (model == null) return false;
        double lo = Plan.yieldTargetLo(level), hi = Plan.yieldTargetHi(level);
        List<Model.Sess> sess = model.sessLog.all;   // newest-first
        for (int i = 0; i < sess.size(); i++) {
            Model.Sess s = sess.get(i);
            if (s == null || s.manual || !sessionOnTrack(model, s, girthTrack)) continue;
            Double y = acuteGirthYieldPct(s);
            if (y == null || baselineAfterOtherWork(model, s) || yieldLeftOut(model, s)) continue;
            return y.doubleValue() >= lo && y.doubleValue() <= hi;
        }
        return false;
    }

    /**
     * THE COUNTED SESSIONS OF ONE TRACK in [fromMs, toMs): filed by the plan (not a manual
     * cycle), from a routine marked for the track - {@link #GIRTH_EITHER} for girth in either
     * style - and, with {@code skipDeload}, none run inside a deload (the pressure clock's own
     * exclusion). What every week walk below hands {@link TrainingWeek}.
     */
    static List<Model.Sess> weekSessions(Model model, int track, long fromMs, long toMs,
                                         boolean skipDeload) {
        List<Model.Sess> out = new java.util.ArrayList<Model.Sess>();
        if (model == null || toMs <= fromMs) return out;
        List<Model.Sess> sess = model.sessLog.all;
        for (int i = 0; i < sess.size(); i++) {
            Model.Sess s = sess.get(i);
            if (s == null || s.manual) continue;
            if (s.ts < fromMs || s.ts >= toMs) continue;
            boolean on = track == GIRTH_EITHER
                ? sessionOnTrack(model, s, Plan.TRACK_GIRTH_INTERVAL)
                  || sessionOnTrack(model, s, Plan.TRACK_GIRTH_TRADITIONAL)
                : sessionOnTrack(model, s, track);
            if (!on) continue;
            if (skipDeload && Deload.touches(model, s.ts, s.ts + 1L)) continue;   // run in a deload
            out.add(s);
        }
        return out;
    }

    /** {@link #weekSessions}'s "girth in either style" - the plan-wide week's girth track. */
    private static final int GIRTH_EITHER = -2;

    /**
     * W2 - THE PLAN-WIDE WEEK over the calendar week [weekStart, weekEnd), counting the sessions
     * in [fromMs, toMs). A track is ACTIVE in a week when it is on, it is not resting the week
     * by the plan's own instruction (girth's dated rest, or a girth-focus block on the length
     * track), and it had been started: a session on it was filed before the week ended. So a
     * track turned on later does not make the weeks before it fail, and one that was never run
     * leaves the other to count alone.
     */
    static TrainingWeek.PlanWeek planWeek(Model model, long fromMs, long toMs, long weekStart,
                                          long weekEnd, long evalMs) {
        boolean g = model.trainerGirthOn && startedBefore(model, GIRTH_EITHER, weekEnd)
            && !girthRestTouchesWeek(model, weekStart, weekEnd)
            && !(model.trainerLengthOn && SameDay.pauseTouchesWeek(
                    model.trainerLength.focusBlockStartMs(), model.trainerLength.focusBlockUntilMs,
                    weekStart, weekEnd));
        boolean l = model.trainerLengthOn && startedBefore(model, Plan.TRACK_LENGTH, weekEnd);
        List<Model.Sess> gs = weekSessions(model, GIRTH_EITHER, fromMs, toMs, false);
        List<Model.Sess> ls = weekSessions(model, Plan.TRACK_LENGTH, fromMs, toMs, false);
        return TrainingWeek.planWeek(model, gs, ls, g, l,
            volumeFromMs(model, model.trainerGirthStyle, gs, weekStart),
            volumeFromMs(model, Plan.TRACK_LENGTH, ls, weekStart), evalMs);
    }

    /** W1 on one track's week (TrainingWeek#tally), its rule (b) open from
     *  {@link #volumeFromMs}, read as at {@code evalMs}. */
    static TrainingWeek.Tally trackTally(Model model, int track, List<Model.Sess> week,
                                         long weekStart, long evalMs) {
        return TrainingWeek.tally(model, week, track == Plan.TRACK_LENGTH,
            volumeFromMs(model, track, week, weekStart), evalMs);
    }

    /**
     * WHEN TWO FULL SESSIONS MAY COUNT A WEEK (the owner's decision of 2026-10-03): once no more
     * of the track's scheduled days are left in it - its last scheduled day of the week
     * ({@link Schedule#lastWeekdayFor}) has had a session on the track (from that session), or
     * has passed (from the next morning). So a week that still has a third day scheduled counts
     * on that third session, exactly as before, and never sooner. With no day of the track on
     * the schedule nothing more is to come, so from the week's start.
     */
    static long volumeFromMs(Model model, int track, List<Model.Sess> week, long weekStart) {
        Schedule sc = model == null ? null : model.sched;
        int last = sc == null ? -1 : sc.lastWeekdayFor(track);
        if (last < 0) return weekStart;
        // Noon of the day, then its midnight: a clock change cannot move it a day.
        long lastDay = Deload.dayStartMs(weekStart + last * 86400000L + 12L * 3600000L);
        long nextDay = Deload.dayStartMs(lastDay + 36L * 3600000L);
        long from = nextDay;
        for (int i = 0; i < week.size(); i++) {
            long ts = week.get(i).ts;
            if (ts >= lastDay && ts < from) from = ts;
        }
        return from;
    }

    private static boolean startedBefore(Model model, int track, long endMs) {
        return !weekSessions(model, track, Long.MIN_VALUE + 1, endMs, false).isEmpty();
    }

    /** THIS WEEK'S PLAN-WIDE WEEK, so far - the This week cards' figure. */
    public static TrainingWeek.PlanWeek planWeekNow(Model model, long nowMs) {
        long start = mondayStartMs(nowMs);
        long end = start + 7L * 24L * 60L * 60L * 1000L;
        if (model == null) return TrainingWeek.planWeek(null,
            new java.util.ArrayList<Model.Sess>(), new java.util.ArrayList<Model.Sess>(),
            false, false);
        return planWeek(model, start, end, start, end, nowMs);
    }

    /** THIS WEEK ON ONE TRACK, so far, by the same rule - the track's own This week card. */
    public static TrainingWeek.Tally weekTally(Model model, int track, long nowMs) {
        long start = mondayStartMs(nowMs);
        long end = start + 7L * 24L * 60L * 60L * 1000L;
        return trackTally(model, track, weekSessions(model, track, start, end, false), start,
                          nowMs);
    }

    /**
     * THE PRESSURE CLOCK: counting weeks on this track AT the current working pressure - the
     * figure a pressure step waits on (Plan#PRESSURE_RAISE_EVERY_TRAINING_WEEKS; the owner's
     * decision, 2026-09-26). {@code fromMs} is {@link Model.TrainerTrackState#pressureSinceMs}.
     *
     * Three things separate it from {@link #accumulatedTrainingWeeks}, and each one only
     * ever makes it count LESS:
     *
     *   ONLY SESSIONS FROM THE CHANGE ON. A week whose Monday ran at the old pressure and
     *   whose Wednesday and Friday ran at the new one has two days at this pressure, not
     *   three, so it does not count.
     *
     *   NOTHING RUN INSIDE A DELOAD COUNTS ({@link Deload#touches}): a deload week's light
     *   sessions are not training days at this pressure, so the deload week can never be a
     *   counting week however much was logged in it. The days after it - the gentle return
     *   included - are training days like any other; the step itself still waits for the
     *   return to close (Plan.Inputs#gentleReturnOpen).
     *
     *   NO CLOCK, NO WEEKS: 0 when the pressure was never set.
     */
    public static int weeksAtPressure(Model model, int track, long fromMs, long nowMs) {
        if (model == null || fromMs <= 0L || nowMs < fromMs) return 0;
        long weekMs = 7L * 24L * 60L * 60L * 1000L;
        int count = 0, guard = 0;
        for (long start = mondayStartMs(fromMs); start <= nowMs && guard < MAX_WEEK_WALK;
             start += weekMs, guard++) {
            List<Model.Sess> week = weekSessions(model, track, Math.max(start, fromMs),
                                                 start + weekMs, true);
            if (trackTally(model, track, week, start, nowMs).qualifies) count++;
        }
        return count;
    }

    /**
     * A PAUSED PLAN CAN BE RESUMED: set up once (trainerEnrolledAt), and not enrolled now -
     * the plan's own pause, or an upgrader's month-12 break from before t10-K, which paused it
     * with the re-entry position already written (the break is MonthBreak's, dated, now).
     */
    public static boolean canResume(Model m) {
        return m != null && !m.trainerEnrolled && m.trainerEnrolledAt > 0L;
    }

    /**
     * "RESUME WHERE I WAS" (the owner's decision, 2026-09-27). The pause promised "pick up
     * where you stopped" and there was no way to: coming back meant setting up again, which
     * re-derives a position from the answers. This keeps every track's position exactly:
     * level, week (and its anchor), and working pressure - and the working pressure is NEVER
     * HIGHER than it was, bounded by today's level/month cap as well, so a resume can only
     * ever keep or lower it. The gentle return is armed from now, so the first days back
     * run under the working pressure whatever the length of the pause.
     *
     * THE STEP-BACK RULES STILL APPLY. Nothing here records a deload window or touches the
     * session log, so a pause longer than the layoff rule (Plan#LAYOFF_MS) is still a layoff
     * to TrainerTab#layoff and the plan still offers its step-back; a resume is not a way
     * around it. After an upgrader's old month-12 break the position resumed is the one that
     * break wrote (Level 2, week 1, 8 hg).
     */
    public static void resumePlan(Model m, long nowMs) {
        if (!canResume(m)) return;
        int month = monthIndexNow(m, nowMs);
        // Enrolled again first: today's cap is the person's (REAL-14, Scale#isNew reads an
        // enrolled plan's answer), so a new person's first month is still held to 6 inHg.
        m.trainerEnrolled = true;
        Model.TrainerTrackState g = m.trainerGirth;
        if (g != null) {
            // G2: the climb's top when the person's maximum is above the usual one.
            double cap = Scale.planTopKpa(m, m.trainerGirthStyle, g.level, month);
            double keep = Math.min(g.pressureKpa, Math.floor(cap + 1e-9));
            if (keep < g.pressureKpa) g.setWorkingPressure(keep, nowMs);
        }
        // The length track's pressure is its ladder's (a pull, not an inflation) and stays
        // exactly as it was: never raised here, and not re-capped against a girth cap.
        Deload.arm(m, nowMs);
        // t10 review D, F2: the week is told at once - a split or alternate week is back in
        // force with the plan, not at the next start. The caller saves as a schedule edit.
        LongDays.follow(m);
    }

    /**
     * THE PLAN PAUSES (Pause): the position is kept as it is and
     * only enrolment stops. The week is told at once (t10 review D, F2): paused, there is no
     * plan running two tracks, so the week is the person's own days (K21) - for Up next, the
     * miss policy and the reminders alike. The caller saves as a schedule edit (the reminders).
     */
    public static void pausePlan(Model m) {
        if (m == null) return;
        m.trainerEnrolled = false;
        LongDays.follow(m);
    }

    /**
     * WHETHER THE GENTLE RETURN STILL RUNS UNDER THE WORKING PRESSURE on or after the day of
     * {@code nowMs}: armed, and its reduced steps not all run (Deload#finished). What a
     * level-up proposal waits on (Plan.Inputs#returnRunsUnder; the owner's decision,
     * 2026-09-27) - on the first full day the taper is finished, so the proposal comes that
     * morning, before the full-pressure session that closes the return.
     */
    public static boolean returnRunsUnder(Model model, long nowMs) {
        long day = Summary.dayNumber(nowMs);
        // ...and not before a week off booked for a later day has begun: those days are
        // ordinary training days (Deload#notBegunOn, REAL-5; round 3 follow-up).
        return Deload.armed(model) && !Deload.finished(model, day)
            && !Deload.notBegunOn(model, day);
    }

    /**
     * THE GIRTH TRACK'S OWN ENGINE SIGNALS, every one walked from the logs at `now` - moved
     * here from SessionActivity#buildTrackInputs so the harness asks the engine exactly the
     * question the app does (the worked-timeline scenario drives it). The plan-wide fields
     * (cadence, layoff, deload week, safety flag) stay with the caller.
     */
    public static void fillGirthInputs(Model model, int track, Model.TrainerTrackState state,
                                       int monthIndex, long now, Plan.Inputs in) {
        // Q5 - the week the yield tier opens at L1 is the TRACK's own position, never a
        // calendar week.
        in.weekIndex = Math.max(1, state.weekIndex);
        // REAL-14 - the first month's 6 inHg cap binds only somebody new to pumping.
        in.newToPumping = Scale.isNew(model);
        // Only readings since the last yield change it made (the streak restarts), and the
        // sets it has kept - what a high-yield pause at L1/L2 takes back off.
        YieldStreaks ys = yieldStreaks(model, track, state.level,
            yieldStreakSinceMs(model, track, state, now));
        in.yieldSets = state.yieldSets;
        // G4 - the work holds a volume step is held to the level's top against.
        in.workSets = Mint.totalSets(track, state);
        /* t10 (the owner's decisions, 2026-10-01) - lane B's signals.
         * R-23: a low-yield add waits to be judged; a reading inside the target since clears it.
         * R-25: a hybrid's own holds are what its steps move.
         * R-27: the hold and fatigue block the time cap counts, and what was converted / owed.
         * R-26: a level-up (or a new placement) this morning - the level's anchor is today.
         * R-60: the step's holds would take a both-tracks day past 90 minutes (CAP90 only). */
        /* (t10 fix, review B F4) ...AND STAYS CLEARED: the reading is written off the saved flag,
         * not only this evaluation's. The streak window restarts with any applied change, and
         * the in-target reading behind it then no longer counts here - so a pending add
         * cleared only for the evaluation came back, and three lows brought the offer again
         * instead of the add. (Saved with the next save; until then each evaluation finds the
         * same reading and clears it the same way.) */
        if (state.addPending && ys.inWindow) state.addPending = false;
        in.addPending = state.addPending;
        // The merge's open item 5: girth rests for the offer's 4 weeks of length focus.
        in.girthResting = state.resting(now);
        boolean hybrid = Mint.hybridOn(model, track, state);
        in.hybridYield = state.hybridYield;
        in.hybridHolds = hybrid ? Plan.hybridHolds(state.level, state.hybridYield) : 0;
        boolean fiveMin = hybrid || track == Plan.TRACK_GIRTH_TRADITIONAL;
        in.r2HoldSec = fiveMin ? Mint.HOLD_TRADITIONAL_SEC : Mint.HOLD_INTERVAL_SEC;
        in.r2FatSec = Mint.r2FatSec(model, track, state.level);
        in.r2ExHolds = state.r2ExHolds;
        in.r2PendKpa = state.r2PendKpa;
        in.levelUpToday = state.weekBaseMs > 0L
            && PhotoCalendar.dayKey(state.weekBaseMs) == PhotoCalendar.dayKey(now);
        // t10 parity run 2, O-1: the plan changed the girth work today, or the level moved -
        // one change a girth morning.
        in.girthChangedToday = in.levelUpToday || (state.planChangeMs > 0L
            && state.planChangeMs <= now
            && PhotoCalendar.dayKey(state.planChangeMs) == PhotoCalendar.dayKey(now));
        int stepHolds = fiveMin ? 1 : (state.level >= Plan.L3 ? 2 : 1);
        // A3-2: what the step adds as it will run - a new block's rest included.
        in.heldAt90 = heldAt90(model, track, girthStepSec(model, track, state, stepHolds), now);
        // G3 - training weeks with no yield reading since the last volume change.
        in.weeksWithoutReadings = weeksWithoutReadings(model, track,
            lastYieldReadingMs(model, track), state.yieldSinceMs, now);
        in.hasYieldData = ys.hasData;
        in.consecutiveLowYield = ys.consecutiveLow;
        in.consecutiveHighYield = ys.consecutiveHigh;
        // THE PRESSURE CLOCK - weeks since the working pressure last changed, never since
        // the routine was last rewritten (a taper step is a rewrite; it is not a new
        // pressure).
        in.trainingWeeksAtPressure = weeksAtPressure(model, track, state.pressureSinceMs, now);
        // R11-4 - the brake: on target and rising, the pressure step comes every 6 weeks.
        in.gainBrake = GainBrake.on(model, track,
            latestYieldOnTarget(model, track, state.level), now);
        in.brakeStepUp = GainBrake.steppedUp(state,
            GainBrake.clockKey(state, GainBrake.KIND_PRESSURE));
        // G2 - the top the plan's step climbs to when "Most you will go to" is above the
        // usual top; the usual top otherwise.
        in.climbTopKpa = Scale.planTopKpa(model, track, state.level, monthIndex);
        // AND NO STEP WHILE THE GENTLE RETURN IS OPEN: the return is still bringing the
        // tissue back to this pressure, so a step lands only once it has closed.
        // ...BUT THE DAYS BEFORE A WEEK OFF ANSWERED FOR A LATER DAY ARE NOT ITS RETURN (t10
        // parity run 2, A-4; REAL-5): ordinary training days - no step, offer or level-up
        // waits on them (the round 3 follow-up: the pressure step and the level-ups too).
        in.weekOffAhead = Deload.notBegunOn(model, Summary.dayNumber(now));
        in.gentleReturnOpen = Deload.armed(model) && !in.weekOffAhead;
        // PER-SESSION net (fix round 3): the MIN over the last few tracked sessions
        // ("consistently reached"), never the weekly sum; and the persistent under-delivery
        // step-back from the same per-session nets vs the planned per-session net.
        // (Under a traditional build-up, its own count: the minutes it actually asks for.)
        // (Capped with the person's own fatigue block, as the run is - review B F3.)
        double planned = Mint.prescribe(track, state.level, state.weekIndex,
            state.pressureKpa, monthIndex, model.ceilKpa, state.carriedSets, state.yieldSets,
            null, Scale.Limits.NONE, Mint.buildUpSets(track, state),
            Mint.r2FatSec(model, track, state.level)).netTargetMin;
        // Q2 - each session against the target IT was prescribed, not against the current
        // one. A split half asks for half; a reduced session asks for none.
        NetPairs np = recentTrackedNets(model, track, NET_CONSISTENCY_N);
        NetSignals ns = netSignals(np.nets, np.targets, planned);
        in.netTupMin = ns.milestoneNet;
        in.underDelivery = ns.underDelivery;
        // Traditional's time condition: each session held its OWN planned minutes.
        in.ownTargetsMet = ns.ownTargetsMet;
        // Level 1's table pressure asks the same of the sessions run SINCE the working
        // pressure last changed - a step is earned at the pressure it steps from.
        NetPairs at = recentTrackedNets(model, track, NET_CONSISTENCY_N, state.pressureSinceMs);
        in.ownTargetsMetAtPressure = netSignals(at.nets, at.targets, planned).ownTargetsMet;
        in.targetHitFraction = recentTargetHitFraction(model, track);
        in.gateHeldTrainingWeeks = gateHeldTrainingWeeks(model, track, now);
        // t10-K: the month-12 break and its gentle week, last - it overrides the above.
        MonthBreak.fill(model, track, now, in);
    }

    /**
     * HOW EARLY THE TARGET HAS BEEN COMING, over the last {@link Plan#EARLY_TARGET_DEBOUNCE}
     * tracked sessions of this track - the MAXIMUM of their fractions, which is the one that
     * makes the rule debounce. (Moved from SessionActivity unchanged.)
     *
     * Maximum and not minimum: the rule fires on a SMALL fraction, so taking the largest of
     * the recent ones means every one of them must be small for it to fire. NaN until there
     * are that many sessions carrying a check at all - absence of data is a hold.
     */
    public static double recentTargetHitFraction(Model model, int track) {
        int seen = 0;
        double worst = Double.NaN;
        for (int i = 0; i < model.sessLog.all.size(); i++) {
            Model.Sess ss = model.sessLog.all.get(i);
            if (ss.manual) continue;
            Model.Routine r = model.routine(ss.routineId);
            if (r == null || r.trainerTrack != track) continue;
            double f = ss.targetHitFraction();
            if (Double.isNaN(f)) return Double.NaN;   // an unchecked session breaks the run
            worst = Double.isNaN(worst) ? f : Math.max(worst, f);
            if (++seen >= Plan.EARLY_TARGET_DEBOUNCE) return worst;
        }
        return Double.NaN;                            // not enough sessions to say
    }

    /**
     * HOW LONG THE L1 GATE'S CONDITIONS HAVE BOTH BEEN TRUE, in training weeks - walked from
     * the session log, never stamped (moved from SessionActivity unchanged). The clock starts
     * at the OLDEST session in the unbroken recent run that met both conditions: net at or
     * above the gate's minimum, and prescribed pressure at 8 hg.
     *
     * TRADITIONAL GIRTH'S NET CONDITION IS ITS OWN (the owner's decision, 2026-09-27;
     * Plan#gateL1toL2Traditional): the session delivered at least the minutes IT was
     * prescribed. A session with no recorded target cannot say, so it ends the run - the
     * conservative reading.
     *
     * A SESSION ASKED FOR NO MINUTES AT PRESSURE NEITHER HOLDS NOR BREAKS THE RUN (the owner's
     * decision, 2026-09-27, that a level-up waits for the gentle return and comes on its
     * first full day). The return's reduced days are prescribed below the level floor and
     * asked for no net; they are skipped here as {@link #recentTrackedNets} skips them. If
     * they broke the run, waiting for the full day would cost two more weeks at the gate.
     * The weeks they fall in still count only as training weeks, as before.
     */
    public static int gateHeldTrainingWeeks(Model model, int track, long now) {
        long since = 0L;
        Model.TrainerTrackState st = track == Plan.TRACK_LENGTH
            ? model.trainerLength : model.trainerGirth;
        for (int i = 0; i < model.sessLog.all.size(); i++) {
            Model.Sess ss = model.sessLog.all.get(i);
            if (ss.manual) continue;
            Model.Routine r = model.routine(ss.routineId);
            if (r == null || r.trainerTrack != track) continue;
            if (ss.netTargetMin != null && ss.netTargetMin.doubleValue() <= 0.0) continue;
            Double netSec = ss.netTupSec;
            boolean metNet;
            if (track == Plan.TRACK_GIRTH_TRADITIONAL) {
                Double tgt = ss.netTargetMin;
                metNet = netSec != null && tgt != null && tgt.doubleValue() > 0.0
                    && netSec.doubleValue() / 60.0 + 1e-9 >= tgt.doubleValue();
            } else {
                /* THE CODA'S SETS COUNT HERE TOO (0.10, the owner's decision). On a both-tracks
                 * day START gives five girth sets to the length session that ran first (S06),
                 * and the pressure steps already read that session with them credited back
                 * (recentTrackedNets). The gate read the shortened minutes against its 20 and
                 * never passed, so interval girth with the length track on stayed at Level 1
                 * all year. Credited exactly as the steps credit it (creditedNet): only when
                 * the day's length session really delivered its expansion, and at the rate
                 * the girth session delivered its own sets - a short session stays short.
                 * Traditional's condition above is its own target and is left as it was. */
                metNet = netSec != null
                    && creditedNet(model, ss)[0] + 1e-9 >= Plan.L1_GATE_NET_MIN;
            }
            /* ON THE PLAN'S SCALE (0.10): the gate asks whether the PLAN'S figure reached 8 inHg,
             * so a session run under a personal offset or the Program's gentle or firm is read
             * back to the plan's figure first (Sess#scaleKpa) - an offset neither earns a level
             * early nor stalls one. */
            boolean met8 = Plan.pressureReachedHg(ss.peakKpa == null ? 0
                : Scale.onPlanScaleKpa(ss.peakKpa.doubleValue(), ss.scaleKpa),
                Plan.L1_GATE_PRESSURE_HG);
            /* t10 R-02 - THE PRESCRIBED PRESSURE, WHERE THE PEAK FALLS SHORT OF IT. A P2 warm-up
             * that stops under the work carries on through the holds after it at +1 kPa a
             * hold, so a short session can end under its own prescription (three five-minute
             * holds at 34 kPa run 28, 29, 30) and its peak never reads 8 inHg - a Level 1 that
             * never reaches its gate. A session run at the track's current plan pressure (filed
             * since it took that figure) is read at that figure, as the guidance's gate and the
             * editor's model read it (the plan's pressure).
             *
             * t10 fix (review A D2) - ONLY WHERE THE SESSION RAN WHAT THE PLAN BUILT
             * (#ranPlansBuild): not in a deload week, whose light work is the week's point, and
             * not a routine lowered by hand or of the person's own. Those are read at their
             * peak, as they always were. */
            if (!met8 && ss.ts >= st.pressureSinceMs && st.pressureSinceMs > 0L
                    && ranPlansBuild(model, st, ss, r, now))
                // (The whole kPa the plan's figure commands - it is kept exact, REAL-15.)
                met8 = Plan.pressureReachedHg(Math.round(st.pressureKpa),
                                              Plan.L1_GATE_PRESSURE_HG);
            if (!metNet || !met8) break;          // the run of qualifying sessions ends here
            since = ss.ts;
        }
        if (since <= 0L) return 0;
        return accumulatedTrainingWeeks(model, track, since, now);
    }

    /**
     * t10 fix (review A D2) - WHETHER SESSION `ss` RAN THE PLAN'S OWN ROUTINE AS THE PLAN BUILT
     * IT, so the L1 gate may read it at the plan's pressure (#gateHeldTrainingWeeks):
     * <ul>
     * <li>not inside a deload week (Deload#touches) - the week's sessions are light by design;</li>
     * <li>its routine is the track's saved plan routine (either half of a split) - not one the
     *     person built or copied;</li>
     * <li>that routine is not a taper step's lowered build (Deload#stepHg) and reads as the
     *     plan's, not as edited (SavedMint#edited: a pressure lowered by hand is an edit).</li>
     * </ul>
     * A routine the plan has since replaced cannot be compared with its build any more, so it
     * is not read this way either - the conservative answer.
     */
    static boolean ranPlansBuild(Model model, Model.TrainerTrackState st, Model.Sess ss,
                                 Model.Routine r, long now) {
        if (model == null || st == null || ss == null || r == null || r.id == null) return false;
        if (Deload.touches(model, ss.ts, ss.ts + 1L)) return false;
        boolean split = partsOf(model, st) == 2;
        int part;
        if (r.id.equals(st.lastMintId)) part = split ? 1 : 0;
        else if (split && r.id.equals(st.lastMintId2)) part = 2;
        else return false;
        if (Deload.stepHg(st.lastMintSig) > 0.0) return false;
        return !SavedMint.edited(model, r, st.lastMintSig, part, split ? 2 : 0, st.lastMintMs,
                                 null, now);
    }

    /**
     * DISTINCT TRAINED DAYS on one track inside one week - the same walk
     * {@link #accumulatedTrainingWeeks} does per week, exposed so the miss policy counts the
     * same thing every other rule in this file counts.
     *
     * The miss policy used to count session ROWS with no track filter and no day
     * de-duplication, and compare that against a count of scheduled WEEKDAYS. Two units, one
     * subtraction. A split prescription files two rows for one day, so somebody who trained
     * twice looked like they trained twice as often; a mixed girth-and-length schedule
     * counted length sessions against girth's scheduled days.
     */
    public static int trainedDaysOnTrack(Model model, int track, long startMs, long endMs) {
        if (model == null) return 0;
        return TrainingWeek.tally(model, weekSessions(model, track, startMs, endMs, false),
                                  track == Plan.TRACK_LENGTH).days;
    }

    /** The timestamp of the most recent (non-manual) plan session on this track, or 0 when
     *  the track has never been trained — the layoff detector's input. */
    public static long lastPlanSessionMs(Model model, int track) {
        if (model == null) return 0L;
        long best = 0L;
        List<Model.Sess> sess = model.sessLog.all;
        for (int i = 0; i < sess.size(); i++) {
            Model.Sess s = sess.get(i);
            if (s == null || s.manual) continue;
            if (!sessionOnTrack(model, s, track)) continue;
            if (s.ts > best) best = s.ts;
        }
        return best;
    }

    /** A ≥7-day layoff on this track: the track has been trained at least once and the time
     *  since — with any recorded {@link Deload} window inside that gap carved out, so a rest
     *  the plan itself granted is never charged back as time away — is now {@link
     *  Plan#LAYOFF_MS} or more. Delegates to {@link Deload#layoff}, so this and the "was that
     *  a deload?" prompt can never disagree about what counts as a layoff. A never-trained
     *  track is NOT a layoff — its card offers the starting routine, it does not step back
     *  from nothing. */
    public static boolean layoff(Model model, int track, long nowMs) {
        return Deload.layoff(model, nowMs);
    }

    /**
     * THE MOST RECENT SESSION ON *ANY* TRACK (audit A17).
     *
     * Layoff is plan-wide by ruling — §7c: a layoff steps back all tracks — and that part is
     * deliberate and unchanged. What was wrong was the evidence it read: it asked only the
     * GIRTH track, so someone training length faithfully while resting girth was ruled to
     * have stopped training altogether, and the plan stepped their length track back for
     * work they had actually done. Length could then neither progress nor take a deload.
     *
     * A layoff is an absence of TRAINING, so any tracked session is evidence against one.
     * The cadence it feeds stays plan-wide; only the question changes, from "have you
     * trained girth lately" to "have you trained lately".
     */
    public static long lastAnyPlanSessionMs(Model model) {
        if (model == null) return 0L;
        long best = 0L;
        List<Model.Sess> sess = model.sessLog.all;
        for (int i = 0; i < sess.size(); i++) {
            Model.Sess s = sess.get(i);
            if (s == null || s.manual) continue;
            if (!onAnyTrack(model, s)) continue;
            if (s.ts > best) best = s.ts;
        }
        return best;
    }

    /** True when this session was run from a routine marked for ANY track — girth in either
     *  style, or length. Feeder is excluded for the same reason it is excluded from the
     *  Net-TUP counters: it is topping-up, not a training session. */
    private static boolean onAnyTrack(Model model, Model.Sess s) {
        return sessionOnTrack(model, s, Plan.TRACK_GIRTH_INTERVAL)
            || sessionOnTrack(model, s, Plan.TRACK_GIRTH_TRADITIONAL)
            || sessionOnTrack(model, s, Plan.TRACK_LENGTH);
    }

    /**
     * The anchor the plan-wide deload cadence counts training weeks from: the end of the last
     * deload when one has been taken - never before enrolment, so a reported deload cannot make
     * the cadence count weeks from before the plan began - else enrolment itself. Moved here
     * from SessionActivity so the feeder's inputs, which read it, can be pure.
     */
    public static long deloadAnchorMs(Model m) {
        // t10-K (B1): after the month-12 break the cadence counts from the return day.
        long brk = MonthBreak.cadenceFromMs(m);
        if (m.trainerLastDeloadMs > 0L)
            return Math.max(brk, Math.max(Deload.endMs(m), m.trainerEnrolledAt));
        return Math.max(brk, m.trainerEnrolledAt);
    }

    /**
     * THE FEEDER'S ENGINE INPUTS at `nowMs` - exactly what SessionActivity#evalFeeder hands
     * Plan#evaluate, pure so the harness asks the Trainer's own question.
     *
     * THE MAIN PRESSURE IS THE ONE THE DAY RUNS AT. A feeder takes its share of the main
     * pressure, and on a day the gentle return reduces, that is the REDUCED main pressure
     * (Deload#mainKpaOn; the owner's ruling, 2026-09-16): three quarters of it, not the feeder's
     * normal figure less the cut. RxBuild#routineFromRx does not cut a feeder again on such a
     * day, so this is the one place the ruling is applied.
     */
    public static Plan.Inputs feederInputs(Model m, long nowMs) {
        Model.TrainerTrackState g = m.trainerGirth;
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_FEEDER;
        in.level = g.level;
        // THE ENGINE'S month, which every gate and cap below reads - floored, so a gate
        // cannot open on day sixteen of the month before it.
        in.monthIndex = monthIndexNow(m, nowMs);
        in.newToPumping = Scale.isNew(m);   // REAL-14
        /* THE MAIN PRESSURE ON THE PERSON'S SCALE (0.10): a feeder is three quarters of what the
         * girth work really runs at, so it takes girth's offset with it - once, here. */
        double main = Deload.mainKpaOn(m,
            g.pressureKpa + Scale.appliedOffsetKpa(m, Plan.TRACK_GIRTH_INTERVAL, in.monthIndex),
            nowMs);
        // t10-K (B3): in the gentle week after the month-12 break, a share of the 60 % figure.
        main = MonthBreak.feederMainKpa(m, main, nowMs);
        in.pressureKpa = main;
        in.mainPressureKpa = main;             // feeder is 70-80% of the main pressure
        in.ceilKpa = m.ceilKpa;
        in.inDeloadWeek = inDeloadWeek(m, nowMs);
        // S12: the plan-wide count, the same one the tracks' own engine reads.
        in.accumulatedTrainingWeeks = planTrainingWeeks(m, deloadAnchorMs(m), nowMs);
        in.firstDeloadPending = !m.trainerFirstDeloadTaken;
        in.redFlag = m.trainerState == Model.TRAINER_STATE_SAFETY_FLAG;
        MonthBreak.fill(m, Plan.TRACK_FEEDER, nowMs, in);   // t10-K (B1)
        return in;
    }

    /**
     * HOW MANY PLAN SESSIONS were filed in [fromMs, toMs): non-manual, on any track - the
     * {@link #lastAnyPlanSessionMs} definition, so the deload report's count and the layoff it
     * corrects agree about what a plan session is. Sessions, not days: a split day is two.
     */
    public static int planSessionsBetween(Model model, long fromMs, long toMs) {
        if (model == null) return 0;
        int n = 0;
        List<Model.Sess> sess = model.sessLog.all;
        for (int i = 0; i < sess.size(); i++) {
            Model.Sess s = sess.get(i);
            if (s == null || s.manual) continue;
            if (s.ts < fromMs || s.ts >= toMs) continue;
            if (onAnyTrack(model, s)) n++;
        }
        return n;
    }

    /** The feeder's prescription from {@link #feederInputs} and the engine's decision on them. */
    public static Mint.Rx feederRx(Model m, Plan.Inputs in, Plan.Decision d) {
        return Mint.prescribe(Plan.TRACK_FEEDER, in.level, 0, in.mainPressureKpa, in.monthIndex,
                              m.ceilKpa, 0, 0, d, Scale.limitsOf(m, Plan.TRACK_FEEDER));
    }

    /**
     * THE TRACK'S WORKING PRESSURE A SAVE WRITES BACK (0.10) - the plan's own figure, never the
     * scaled one. The prescription's own save: the plan's figure it was scaled from
     * (Mint.Rx#planKpa). A pressure the person set in "Adjust first..." (`ownKpa`): that
     * pressure less the offset that applies now, so the next prescription - the plan's figure
     * plus the same offset - is their pressure again and answers the save (Mint#answers).
     */
    public static double savedPlanKpa(Model m, int track, Mint.Rx use, boolean ownKpa,
                                      long nowMs) {
        if (use == null) return 0.0;
        // The plan's figure as the plan holds it, not rounded at every rewrite (REAL-15).
        if (!ownKpa) return use.planFigureKpa();
        return use.pressureKpa - Scale.appliedOffsetKpa(m, track, monthIndexNow(m, nowMs));
    }

    /**
     * A TRACK'S PRESCRIPTION AT ITS POSITION, on the person's own scale - what the Trainer
     * offers, saves and keeps current (SessionActivity#evalTrack): the plan's figure (the
     * track's working pressure, or the decision's) plus the track's offset, within the
     * effective top and the hard limits (Mint#prescribe with Scale#limitsOf). The kept yield
     * sets ride along on girth; Mint ignores them for any other track. So does a traditional
     * build-up's count (the half start, 0.10: Mint#buildUpSets), which holds the holds to it.
     * And the time cap (R-27) counts the person's own fatigue block (Mint#r2FatSec: extended,
     * standard or off) - the block the builder runs and the plan counts (review B F3).
     */
    public static Mint.Rx trackRx(Model m, int track, Model.TrainerTrackState state,
                                  int monthIndex, Plan.Decision d) {
        return Mint.prescribe(track, state.level, state.weekIndex, state.pressureKpa,
            monthIndex, m.ceilKpa, state.carriedSets, state.yieldSets, d,
            Scale.limitsOf(m, track), Mint.buildUpSets(track, state),
            Mint.r2FatSec(m, track, state.level));
    }

    /**
     * {@link #trackRx} WITH THE TRACK AT ANOTHER WEEK AND ANOTHER PLAN FIGURE - the same
     * prescription (the same call, with the same kept yield sets, carried sets, build-up,
     * offset and limits), asked of a position the track has not reached yet. What "The next
     * weeks" projects with ({@link #weeksAhead}), so a row there is what trackRx will write
     * when the track gets there, never a second calculation of it.
     */
    public static Mint.Rx trackRxAt(Model m, int track, Model.TrainerTrackState state,
                                    int weekIndex, double planKpa, int monthIndex,
                                    Plan.Decision d) {
        return Mint.prescribe(track, state.level, weekIndex, planKpa,
            monthIndex, m.ceilKpa, state.carriedSets, state.yieldSets, d,
            Scale.limitsOf(m, track), Mint.buildUpSets(track, state),
            Mint.r2FatSec(m, track, state.level));
    }

    /* ---- yield debounce (unblocks ADD_VOLUME / PAUSE_VOLUME / REDUCE_VOLUME) ---- */

    /** The consecutive-below and consecutive-above yield run lengths off the newest tracked
     *  girth sessions, plus whether any yield data exists at all. */
    public static final class YieldStreaks {
        public final int consecutiveLow;   // newest run of sessions below the level's yield-lo
        public final int consecutiveHigh;  // newest run of sessions above the level's yield-hi
        public final boolean hasData;      // at least one acute girth-yield session exists
        /** R-23: a counted reading inside the target since the streak's start. */
        public final boolean inWindow;
        YieldStreaks(int lo, int hi, boolean has) {
            this(lo, hi, has, false);
        }
        YieldStreaks(int lo, int hi, boolean has, boolean inWindow) {
            this.consecutiveLow = lo; this.consecutiveHigh = hi; this.hasData = has;
            this.inWindow = inWindow;
        }
    }

    /**
     * The engine's yield debounce inputs, walked from the session log newest→oldest over the
     * girth track. Each session's ACUTE girth yield is {@code (post − pre)/pre × 100} from the
     * session-linked pre/post girth ({@link Model.Sess#afterGirCm} is that delta,
     * {@link Model.Sess#afterGirAbsCm} the post), counted only when the baseline was taken
     * THIS session ({@link Model.Sess#afterBaseThisSession}) — a stale weeks-old baseline is a
     * cumulative change, not this session's acute swelling response, and must never drive a
     * volume decision. A session with no girth after-reading carries no yield (the field is
     * null — the Stage G method-gating already ensured a non-girth reading never populated it)
     * and is transparently skipped: absence of data leaves the counters at 0, so the engine
     * correctly HOLDs rather than inventing a change. {@code consecutiveLow}/{@code High} are
     * the leading runs below the level's yield-lo / above its yield-hi target (Plan owns the
     * ×3 threshold via {@link Plan#debounceTriggered}).
     */
    public static YieldStreaks yieldStreaks(Model model, int girthTrack, int level) {
        return yieldStreaks(model, girthTrack, level, 0L);
    }

    /** {@link #yieldStreaks(Model, int, int)} counting only sessions filed after
     *  {@code sinceMs} - the streak restarts after every yield change it caused
     *  (Model.TrainerTrackState#yieldSinceMs), so the next change needs three new readings. */
    public static YieldStreaks yieldStreaks(Model model, int girthTrack, int level, long sinceMs) {
        if (model == null) return new YieldStreaks(0, 0, false);
        double lo = Plan.yieldTargetLo(level), hi = Plan.yieldTargetHi(level);
        int low = 0, high = 0;
        boolean has = false, lowActive = true, highActive = true, inWindow = false;
        List<Model.Sess> sess = model.sessLog.all;   // newest-first
        for (int i = 0; i < sess.size(); i++) {
            Model.Sess s = sess.get(i);
            if (s == null || s.manual) continue;
            if (!sessionOnTrack(model, s, girthTrack)) continue;
            if (s.ts <= sinceMs) break;               // before the streak restarted
            Double y = acuteGirthYieldPct(s);
            if (y == null) continue;                  // not a yield-bearing session — skip
            // S13 (c): a baseline taken on tissue the length coda had already expanded reads
            // the yield low; left out, as a session with no yield is.
            if (baselineAfterOtherWork(model, s)) continue;
            // R-24 (C5): readings that neither count toward a streak nor break it.
            if (yieldLeftOut(model, s)) continue;
            has = true;
            double yp = y.doubleValue();
            if (yp >= lo && yp <= hi) inWindow = true;   // R-23: clears a pending add
            if (lowActive) { if (yp < lo) low++; else lowActive = false; }
            if (highActive) { if (yp > hi) high++; else highActive = false; }
        }
        return new YieldStreaks(low, high, has, inWindow);
    }

    /**
     * R-24 (C5, the owner's decision, 2026-10-01): A GIRTH READING THAT NEITHER COUNTS TOWARD A
     * YIELD STREAK NOR BREAKS IT - a pair taken under other conditions than its baseline
     * (Sess#afterComparable false), a session stopped early, a reduced session that asked for
     * no time at pressure (the return days after a deload: Sess#netTargetMin 0), and anything
     * run inside a deload. It still shows the person measures, so it resets the no-readings
     * fallback ({@link #lastYieldReadingMs} keeps it).
     *
     * A SESSION ENDED AT THE TARGET IS NOT STOPPED EARLY (the owner's O6, 2026-10-01; review B
     * G1): "Finish here" files the session as not completed, but the mid-run check had found
     * the yield target (Sess#targetHitAtSet) - its reading counts. Leaving them out kept the
     * lows and lost the readings at and above the target.
     */
    public static boolean yieldLeftOut(Model model, Model.Sess s) {
        if (s == null) return true;
        if (!s.afterComparable || (!s.completed && s.targetHitAtSet <= 0)) return true;
        if (s.netTargetMin != null && s.netTargetMin.doubleValue() <= 0.0) return true;
        return Deload.touches(model, s.ts, s.ts + 1L);
    }

    /**
     * WHERE THE YIELD STREAK STARTS: the last yield change (TrainerTrackState#yieldSinceMs);
     * the level's anchor (#weekBaseMs - a level crossing, or a new placement, starts the
     * streak afresh: the new level is judged on its own readings); and at Level 1 the morning
     * its yield tier opened, week 10 (R-28, Y-M2) - three lows in weeks 7-9 are not a
     * decision on week 10's first morning.
     */
    static long yieldStreakSinceMs(Model model, int track, Model.TrainerTrackState st,
                                   long now) {
        long since = Math.max(st.yieldSinceMs, st.weekBaseMs);
        if (track == Plan.TRACK_GIRTH_INTERVAL && st.level <= Plan.L1)
            since = Math.max(since, l1YieldOpenedMs(model, track, st, now));
        return since;
    }

    /**
     * Y-M2: WHEN LEVEL 1'S YIELD TIER OPENED - the start of the week the track's position
     * reached week {@link Plan#YIELD_FROM_WEEK_L1}, walked from its anchor as the position
     * advances (one per qualifying training week); the anchor itself when it was placed at or
     * past it; `now` when the position has not reached it yet (nothing counts).
     */
    static long l1YieldOpenedMs(Model model, int track, Model.TrainerTrackState st, long now) {
        if (model == null || st == null) return 0L;
        if (st.weekIndex < Plan.YIELD_FROM_WEEK_L1) return now;
        int base = st.weekBaseIndex > 0 ? st.weekBaseIndex : st.weekIndex;
        if (base >= Plan.YIELD_FROM_WEEK_L1 || st.weekBaseMs <= 0L) return st.weekBaseMs;
        long weekMs = 7L * 24L * 60L * 60L * 1000L;
        int pos = base, guard = 0;
        for (long start = mondayStartMs(st.weekBaseMs); start <= now && guard < MAX_WEEK_WALK;
             start += weekMs, guard++) {
            // The moment the week counts (TrainingWeek: its third day, or two full sessions once
            // the track's last scheduled day is done) is the moment the position moves on.
            TrainingWeek.Tally t = trackTally(model, track,
                weekSessions(model, track, start, start + weekMs, false), start, now);
            if (t.qualifies && ++pos >= Plan.YIELD_FROM_WEEK_L1) return t.qualifiedAtMs;
        }
        return st.weekBaseMs;
    }

    /**
     * S13 (c) (the owner's decision, 2026-09-26): HOW SOON AFTER A LENGTH SESSION A GIRTH
     * BASELINE IS STILL "AFTER OTHER WORK". 4 hours.
     *
     * NOT IN THE GUIDANCE: how long the length coda's expansion lasts, or how far apart two
     * sessions must be for the second's baseline to be cold. The only same-day spacing the
     * guidance gives between sessions is the feeder's - four to six hours from the other
     * sessions - and this takes its lower bound. The figure was chosen when S13
     * was built (2026-09-26), for the owner to confirm.
     */
    public static final long AFTER_OTHER_WORK_MS = 4L * 60L * 60L * 1000L;

    /**
     * S13 (c): WHETHER THIS GIRTH SESSION'S BASELINE WAS TAKEN AFTER OTHER WORK - within
     * {@link #AFTER_OTHER_WORK_MS} after a length session was filed.
     *
     * Girth yield is how much thicker the tissue is after a girth session,
     * measured here from a baseline taken in the same session. After a length session the
     * coda has already expanded the tissue, so that baseline starts high and the yield reads
     * low - and three low readings add girth sets (Plan's yield debounce).
     * Such a session is marked, and left out of the yield streak ({@link #yieldStreaks}).
     *
     * Derived from the log, never stored: a session with no baseline of its own
     * ({@link Model.Sess#afterBaseThisSession} false) has nothing to mark, and only a length
     * session run from the plan - not a manual cycle, not a feeder - is the other work.
     *
     * G1 (the owner's decision, 2026-10-01): A BASELINE TAKEN BEFORE THE LENGTH SESSION IS NOT
     * AFTER IT. A length session counts only when it started at or before the baseline (a
     * session's ts is its start, A20) - so the girth before reading asked at the length
     * session's START ({@link #earlyGirthBaseline}) predates it and the pair counts, while a
     * baseline genuinely taken after the length work is still left out.
     */
    public static boolean baselineAfterOtherWork(Model model, Model.Sess s) {
        if (model == null || s == null || !s.afterBaseThisSession || s.afterBaseTs <= 0L)
            return false;
        Model.Routine own = model.routine(s.routineId);
        if (own == null || !isGirthTrack(own.trainerTrack)) return false;   // a GIRTH baseline
        long base = s.afterBaseTs;
        List<Model.Sess> sess = model.sessLog.all;
        for (int i = 0; i < sess.size(); i++) {
            Model.Sess o = sess.get(i);
            if (o == null || o == s || o.manual) continue;
            // R-28 (Y-M1): the 4 hours run from the length session's END - the coda's
            // expansion is fresh when it finishes, not when it starts.
            long end = o.ts + Math.max(0L, o.durSec) * 1000L;
            if (o.ts > base || base - end >= AFTER_OTHER_WORK_MS) continue;
            if (sessionOnTrack(model, o, Plan.TRACK_LENGTH)) return true;
        }
        return false;
    }

    /* ---- G1: the girth before reading, taken before the length session ---- */

    /**
     * G1 - WHETHER THE GIRTH SESSION IS STILL TO RUN TODAY: girth enrolled, not paused for a
     * girth-focus block, not on a dated rest, nothing of it filed today, and a day that is
     * not a rest day nor a length-only one.
     */
    public static boolean girthLaterToday(Model model, long now) {
        if (model == null || !model.trainerEnrolled || !model.trainerGirthOn) return false;
        if (girthPausedNow(model, now) || model.trainerGirth.resting(now)) return false;
        if (trackRunsToday(model, model.trainerGirthStyle, now) > 0) return false;
        int plan = dayPlanNow(model, now);
        return plan != Schedule.PLAN_REST && plan != Schedule.PLAN_LENGTH;
    }

    /**
     * G1 - WHETHER THE MEASUREMENT CADENCE WILL ASK AT TODAY'S GIRTH SESSION, once the length
     * session being started is counted: its count one more ({@link Model.MeasLog#due}, the
     * sessions mode), and the girth track's own week position (S13 a).
     */
    public static boolean measureDueAtGirth(Model model, long now) {
        if (model == null || model.meas == null) return false;
        Model.Meas next = new Model.Meas();
        next.mode = model.meas.mode;
        next.n = model.meas.n;
        next.hours = model.meas.hours;
        next.sinceN = model.meas.sinceN + 1;
        next.sinceH = model.meas.sinceH;
        return model.measLog.due(next,
            model.sessionsThisTrainingWeek(now, model.trainerGirthStyle));
    }

    /**
     * G1 - WHETHER THIS START ASKS FOR THE DAY'S GIRTH BEFORE READING AHEAD OF THE LENGTH
     * SESSION ({@link SameDay#girthBeforeFirst}): `r` is a length routine, the girth session
     * is still to run today, the before reading measures girth (the standardisation hold -
     * at rest the session screens take length only), and a measurement is due now
     * (`cadenceDue`) or will be at the girth session.
     */
    public static boolean girthBeforeFirst(Model model, Model.Routine r, long now,
                                           boolean cadenceDue) {
        if (model == null || r == null) return false;
        boolean length = r.trainerTrack == Plan.TRACK_LENGTH;
        if (!length) return false;
        return SameDay.girthBeforeFirst(true, girthLaterToday(model, now),
            model.std != null && model.std.on, cadenceDue, measureDueAtGirth(model, now));
    }

    /**
     * G1 - THE BEFORE READING A GIRTH SESSION STARTING NOW TAKES AS ITS OWN, when it was taken
     * ahead of today's length session: the newest before reading (the one the after reading
     * is diffed against, Model.MeasLog#latestPre), it measures girth, it is today's, a length
     * session has started since it, and no girth session has. Null otherwise - the session
     * then asks for its own, as it always did.
     */
    public static Model.Reading earlyGirthBaseline(Model model, Model.Routine r, long now) {
        if (model == null || r == null || !isGirthTrack(r.trainerTrack)) return null;
        Model.Reading pre = model.measLog.latestPre();
        if (pre == null || !pre.measuredGirth() || pre.ts > now) return null;
        int today = PhotoCalendar.dayKey(now);
        if (PhotoCalendar.dayKey(pre.ts) != today) return null;
        boolean lengthSince = false;
        List<Model.Sess> sess = model.sessLog.all;
        for (int i = 0; i < sess.size(); i++) {
            Model.Sess s = sess.get(i);
            if (s == null || s.manual || s.ts < pre.ts) continue;
            Model.Routine sr = model.routine(s.routineId);
            if (sr == null) continue;
            if (isGirthTrack(sr.trainerTrack)) return null;          // already used by girth
            if (sr.trainerTrack == Plan.TRACK_LENGTH && sessionDay(s) == today)
                lengthSince = true;
        }
        return lengthSince ? pre : null;
    }

    /**
     * G1 - WHETHER START ASKS FOR A BEFORE READING AT ALL: never when a girth session has its
     * before reading from ahead of the length session ({@link #earlyGirthBaseline}); else the
     * cadence, or the girth before asked first ({@link #girthBeforeFirst}).
     */
    public static boolean measureAtStart(Model model, Model.Routine r, long now,
                                         boolean cadenceDue) {
        if (earlyGirthBaseline(model, r, now) != null) return false;
        return cadenceDue || girthBeforeFirst(model, r, now, cadenceDue);
    }

    /* ---- G3: the time-based fallback when nothing is measured ---- */

    /**
     * G3 - THE NEWEST GIRTH SESSION WHOSE YIELD COUNTS - the pair {@link #yieldStreaks} reads
     * (a this-session pre/post girth, not taken after other work) - its ts, or 0 for none.
     */
    public static long lastYieldReadingMs(Model model, int girthTrack) {
        if (model == null) return 0L;
        List<Model.Sess> sess = model.sessLog.all;
        long best = 0L;
        for (int i = 0; i < sess.size(); i++) {
            Model.Sess s = sess.get(i);
            if (s == null || s.manual || s.ts <= best) continue;
            if (!sessionOnTrack(model, s, girthTrack)) continue;
            if (acuteGirthYieldPct(s) == null || baselineAfterOtherWork(model, s)) continue;
            best = s.ts;
        }
        return best;
    }

    /**
     * G3 (the owner's decision, 2026-10-01) - TRAINING WEEKS WITH NO QUALIFYING READING on
     * `track`, counted as the plan counts weeks at a pressure ({@link #weeksAtPressure}:
     * weeks of three or more training days on the track, a session in a deload never
     * counting), from the latest of the newest qualifying reading (`lastReadingMs`), the last
     * change to the track's volume (`sinceMs` - the yield or strain clock, which an accepted
     * fallback step restarts too) and enrolment.
     */
    public static int weeksWithoutReadings(Model model, int track, long lastReadingMs,
                                           long sinceMs, long now) {
        if (model == null) return 0;
        long from = Math.max(Math.max(lastReadingMs, sinceMs), model.trainerEnrolledAt);
        return weeksAtPressure(model, track, from, now);
    }

    /** G3 - the length track's count: its readings are the strain rung's (BPSSL pairs,
     *  Meas#lastPairedMs), its clock the strain clock. */
    public static int lengthWeeksWithoutReadings(Model model, long now) {
        if (model == null) return 0;
        return weeksWithoutReadings(model, Plan.TRACK_LENGTH,
            Meas.lastPairedMs(model.measLog, Model.Reading.METHOD_BPSSL),
            model.trainerLength.strainSinceMs, now);
    }

    /** One session's ACUTE girth yield percent, or null when it carries no like-for-like
     *  this-session pre/post girth pair (unknown ≠ failure ≠ zero — the session simply is not
     *  a tracked yield session). */
    public static Double acuteGirthYieldPct(Model.Sess s) {
        if (s == null || s.afterGirCm == null || s.afterGirAbsCm == null) return null;
        if (!s.afterBaseThisSession) return null;     // acute only — pre & post both this session
        double post = s.afterGirAbsCm.doubleValue();
        double delta = s.afterGirCm.doubleValue();
        double pre = post - delta;
        if (pre <= 0.0) return null;
        return Double.valueOf(delta / pre * 100.0);
    }

    /* ---- per-session net signals (fix round 3: the milestone/gate is PER-SESSION) ---- */

    /** The number of recent tracked sessions the per-session net signals debounce over — the
     *  same ×3 consistency the yield debounce uses ({@link Plan#YIELD_DEBOUNCE}). */
    public static final int NET_CONSISTENCY_N = Plan.YIELD_DEBOUNCE;
    /** Under-delivery fires only when even the BEST of the recent tracked sessions delivered
     *  below this fraction of the planned per-session net — a genuine "plan ahead of the
     *  tissue" shortfall, not a near-miss (the guidance). */
    public static final double UNDER_DELIVERY_FRAC = 0.75;

    /** The per-session Net TUP (minutes) of the most recent {@code count} TRACKED sessions on
     *  the girth track (a session with a real {@link Model.Sess#netTupSec}), newest-first — a
     *  session that never tracked carries null net and is skipped (unknown ≠ 0). Fewer than
     *  {@code count} tracked sessions returns a shorter array; the caller treats "not enough"
     *  as no signal (absence of data = hold). */
    /**
     * Q2 - THE RECENT SESSIONS AS (delivered, asked-for) PAIRS.
     *
     * {@link #recentTrackedNetsMin} returns only what was delivered, which forced every
     * caller to compare it against ONE planned figure - the current prescription's. That is
     * wrong whenever a past session was asked for something else, and two ordinary things
     * make it so:
     *
     *   A SPLIT HALF delivers half the net and was judged against the whole prescription.
     *   A REDUCED SESSION - the return-from-a-week-off pressure, deliberately below the
     *   level floor - delivers no net at all and was judged against a full target.
     *
     * Three of those in a row tripped persistent under-delivery and proposed a step-back, so
     * following the precaution correctly cost you a level. This pairs each net with the
     * target that session was actually prescribed.
     *
     * A SESSION ASKED FOR ZERO NET IS NOT IN THE WINDOW AT ALL. It was never expected to
     * deliver, so counting it as a failure is the bug, and counting it as a success would be
     * a different lie. It is skipped, and the window fills from the sessions before it.
     */
    public static final class NetPairs {
        public final double[] nets;      // delivered, minutes, newest-first
        public final double[] targets;   // asked for, minutes; NaN = unknown (pre-Q2)
        NetPairs(double[] n, double[] t) { this.nets = n; this.targets = t; }
        public int size() { return nets.length; }
    }

    /** How many of the last {@link #NET_CONSISTENCY_N} scored sessions on the track
     *  delivered at least their own recorded target - what the traditional gate card shows
     *  (Plan#gateL1toL2Traditional). A session with no recorded target does not count. */
    public static int ownTargetsHeldOfLast(Model model, int girthTrack) {
        NetPairs np = recentTrackedNets(model, girthTrack, NET_CONSISTENCY_N);
        int held = 0;
        for (int i = 0; i < np.size(); i++)
            if (!Double.isNaN(np.targets[i]) && np.targets[i] > 0.0
                    && np.nets[i] + 1e-9 >= np.targets[i]) held++;
        return held;
    }

    public static NetPairs recentTrackedNets(Model model, int girthTrack, int count) {
        return recentTrackedNets(model, girthTrack, count, Long.MIN_VALUE);
    }

    /** {@link #recentTrackedNets(Model,int,int)} counting only sessions started at or after
     *  {@code sinceMs} - the ones run at the current working pressure. */
    public static NetPairs recentTrackedNets(Model model, int girthTrack, int count,
                                             long sinceMs) {
        if (model == null || count <= 0) return new NetPairs(new double[0], new double[0]);
        java.util.ArrayList<Double> ns = new java.util.ArrayList<Double>();
        java.util.ArrayList<Double> ts = new java.util.ArrayList<Double>();
        List<Model.Sess> sess = model.sessLog.all;   // newest-first
        for (int i = 0; i < sess.size() && ns.size() < count; i++) {
            Model.Sess s = sess.get(i);
            if (s == null || s.manual) continue;
            if (s.ts < sinceMs) continue;              // before the pressure last changed
            if (!sessionOnTrack(model, s, girthTrack)) continue;
            if (s.netTupSec == null) continue;        // never tracked - unknown, not zero
            double tgt = s.netTargetMin == null ? Double.NaN : s.netTargetMin.doubleValue();
            if (!Double.isNaN(tgt) && tgt <= 0.0) continue;   // asked for nothing - not judged
            double[] c = creditedNet(model, s);
            ns.add(Double.valueOf(c[0]));
            ts.add(Double.valueOf(c[1]));
        }
        double[] a = new double[ns.size()], b = new double[ts.size()];
        for (int i = 0; i < a.length; i++) { a[i] = ns.get(i).doubleValue();
                                             b[i] = ts.get(i).doubleValue(); }
        return new NetPairs(a, b);
    }

    /**
     * ONE TRACKED GIRTH SESSION AS {delivered, asked-for} MINUTES, with the sets it gave up to
     * the day's length session credited back - what the pressure steps
     * ({@link #recentTrackedNets}) and the Level 1 gate ({@link #gateHeldTrainingWeeks}) both
     * read, so the two cannot judge the same session differently. Asked-for is NaN when the
     * session recorded no target (pre-Q2). The caller has checked {@code netTupSec} is set.
     *
     * S06 - THE CODA'S SETS COUNT TOWARD THE GIRTH WORK (the owner's decision of 2026-09-26:
     * "keep the length coda and take 5 sets off the girth work"). A girth session that gave
     * up sets to the day's length session delivered, by design, less than its level asks of
     * a session - and the level's gate reads the MINIMUM of the last few, so without this
     * every both-tracks day would hold the girth track where it is. Credited ONLY when that
     * day's length session really delivered its expansion - completed, on a real pump, every
     * cycle of it (codaRanOn; the 0.10 safety review) - and at the rate the girth session
     * delivered its own sets (delivered and asked-for scaled together, so under-delivery
     * reads the same).
     */
    static double[] creditedNet(Model model, Model.Sess s) {
        double net = s.netTupSec.doubleValue() / 60.0;
        double tgt = s.netTargetMin == null ? Double.NaN : s.netTargetMin.doubleValue();
        if (Double.isNaN(tgt) || tgt <= 0.0) return new double[]{ net, tgt };
        /* 0.10 - A RAMP'S CLIMBING HOLDS UNDER THE COUNTING LINE (the owner's decision). A
         * Ramped block climbs from 80 % of the day's working pressure, and a counted climb hold
         * under the level's line is run, counts nothing, is not made up and is not in the
         * session's target - so the session is no longer than fixed holds. They are the plan's
         * prescribed holds all the same, so the level reads a session that delivered its own
         * target as the plan's minutes: credited at the rate the session delivered its target,
         * exactly as the sets below are, so a short session stays short and under-delivery
         * reads the same. Without this a Firm bias from a low answer (the plan's 17, the
         * person's 14) counted 18 of Level 1's 20 minutes every session and never moved. */
        double credit = Model.clampClimbUnder(s.climbUnderLineMin);
        // S06 - the sets given to the day's length session: its counted minutes, and any climb
        // holds under the line among them ("u"), only when that session delivered.
        double given = RunShape.minutesTaken(s.shape) + RunShape.climbMinutesTaken(s.shape);
        if (given > 0.0 && codaRanOn(model, sessionDay(s))) credit += given;
        if (credit > 0.0) {
            net = net * (tgt + credit) / tgt;
            tgt = tgt + credit;
        }
        return new double[]{ net, tgt };
    }

    /**
     * S06 - whether a length session under the local day {@code dayKey} did the expansion a
     * girth session's dropped sets are credited against: filed by a real run (not manual, not
     * on the simulated pump), COMPLETED, and with its expansion delivered in full
     * (Model.Sess#expansionDone). A length session stopped in its tunica release, or run on
     * the simulator, did none of that work, and the level gate reads the lowest of the recent
     * nets - crediting it would move somebody up on work that was never done (the 0.10
     * safety review).
     */
    public static boolean codaRanOn(Model model, int dayKey) {
        if (model == null) return false;
        for (int i = 0; i < model.sessLog.all.size(); i++) {
            Model.Sess ss = model.sessLog.all.get(i);
            if (ss == null || ss.manual || sessionDay(ss) != dayKey) continue;
            if (!ss.completed || ss.sim || !ss.expansionDone) continue;
            Model.Routine r = model.routine(ss.routineId);
            if (r != null && r.trainerTrack == Plan.TRACK_LENGTH) return true;
        }
        return false;
    }

    public static double[] recentTrackedNetsMin(Model model, int girthTrack, int count) {
        return recentNetsMin(model, girthTrack, count, false);
    }

    /** {@link #recentTrackedNetsMin} as the Level 1 gate reads them: each with the sets it
     *  gave up to the day's length session credited back ({@link #creditedNet}) - what the
     *  gate card's "Net per session" shows, so the card and the gate agree (0.10). */
    public static double[] recentCreditedNetsMin(Model model, int girthTrack, int count) {
        return recentNetsMin(model, girthTrack, count, true);
    }

    private static double[] recentNetsMin(Model model, int girthTrack, int count,
                                          boolean credited) {
        if (model == null || count <= 0) return new double[0];
        java.util.ArrayList<Double> out = new java.util.ArrayList<Double>();
        List<Model.Sess> sess = model.sessLog.all;   // newest-first
        for (int i = 0; i < sess.size() && out.size() < count; i++) {
            Model.Sess s = sess.get(i);
            if (s == null || s.manual) continue;
            if (!sessionOnTrack(model, s, girthTrack)) continue;
            if (s.netTupSec == null) continue;        // never tracked — unknown, not zero
            out.add(Double.valueOf(credited ? creditedNet(model, s)[0]
                                            : s.netTupSec.doubleValue() / 60.0));
        }
        double[] a = new double[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i).doubleValue();
        return a;
    }

    /** The two PER-SESSION net signals the engine needs, derived (purely) from the recent
     *  tracked per-session nets and the planned per-session net. */
    public static final class NetSignals {
        /** The per-session net to feed {@link Plan.Inputs#netTupMin}: the MINIMUM over the
         *  last {@link #NET_CONSISTENCY_N} tracked sessions, so {@link Plan#netMilestoneReached}
         *  fires only when EVERY one of them reached the milestone (per-session, consistent —
         *  never a weekly sum). 0 when there is not enough data (→ engine holds). */
        public final double milestoneNet;
        /** Persistent under-delivery: at least {@link #NET_CONSISTENCY_N} tracked sessions AND
         *  even the best of them delivered below {@link #UNDER_DELIVERY_FRAC} of the plan. */
        public final boolean underDelivery;
        public final boolean hasEnough;
        /** Each of the last {@link #NET_CONSISTENCY_N} tracked sessions delivered at least
         *  the minutes IT was prescribed (its own target; the planned figure where none was
         *  recorded). What traditional girth's pressure step asks (Plan.Inputs#ownTargetsMet).
         *  False when there are not that many - absence of data is a hold. */
        public final boolean ownTargetsMet;
        NetSignals(double m, boolean u, boolean e) { this(m, u, e, false); }
        NetSignals(double m, boolean u, boolean e, boolean own) {
            this.milestoneNet = m; this.underDelivery = u; this.hasEnough = e;
            this.ownTargetsMet = own;
        }
    }

    /** Derives {@link NetSignals} from the recent per-session nets (newest-first) and the
     *  planned per-session net — pure, so the per-session gating semantics are SelfTestable. */
    public static NetSignals netSignals(double[] recentNetsNewestFirst,
                                        double plannedPerSessionMin) {
        return netSignals(recentNetsNewestFirst, null, plannedPerSessionMin);
    }

    /**
     * Q2 - the same signals, judging each session against the target IT was prescribed.
     *
     * {@code targetsNewestFirst} runs parallel to the nets; NaN in a slot means that
     * session's target was never recorded (everything filed before Q2 existed), and those
     * fall back to {@code plannedPerSessionMin} - the old comparison, so an existing history
     * behaves on the upgrade exactly as it did before it.
     *
     * UNDER-DELIVERY STILL NEEDS EVERY ONE OF THEM TO FALL SHORT. The rule is unchanged:
     * even the best of the window has to be under {@link #UNDER_DELIVERY_FRAC} of what was
     * asked. Only the thing it is measured against has changed, from one figure to each
     * session's own.
     */
    public static NetSignals netSignals(double[] recentNetsNewestFirst,
                                        double[] targetsNewestFirst,
                                        double plannedPerSessionMin) {
        int n = recentNetsNewestFirst == null ? 0 : recentNetsNewestFirst.length;
        if (n < NET_CONSISTENCY_N) return new NetSignals(0.0, false, false);
        double min = Double.POSITIVE_INFINITY;
        double bestFrac = Double.NEGATIVE_INFINITY;
        boolean anyTarget = false;
        boolean allOwn = true;
        for (int i = 0; i < NET_CONSISTENCY_N; i++) {
            double v = recentNetsNewestFirst[i];
            if (v < min) min = v;
            double tgt = (targetsNewestFirst != null && i < targetsNewestFirst.length
                          && !Double.isNaN(targetsNewestFirst[i]))
                       ? targetsNewestFirst[i] : plannedPerSessionMin;
            if (tgt > 0.0) {
                anyTarget = true;
                double frac = v / tgt;
                if (frac > bestFrac) bestFrac = frac;
                if (v + 1e-9 < tgt) allOwn = false;
            } else {
                allOwn = false;                      // nothing asked of it - nothing held
            }
        }
        boolean under = anyTarget && bestFrac < UNDER_DELIVERY_FRAC;
        return new NetSignals(min, under, true, allOwn);
    }

    /* ================================================================== R5: what to run */

    /** Whether a track is one of the two girth styles. The style is a setting; "girth" is
     *  the track, and every count here is about the track. */
    public static boolean isGirthTrack(int track) {
        return track == Plan.TRACK_GIRTH_INTERVAL || track == Plan.TRACK_GIRTH_TRADITIONAL;
    }

    /** How many routines a track's prescription is currently written as: two when it is
     *  split into halves, one otherwise. A split day is not finished until both halves have
     *  run, and asking the routine list rather than the setting means a prescription written
     *  before the split was turned on still counts as the one routine it actually is. */
    public static int partsOf(Model model, Model.TrainerTrackState st) {
        if (model == null || st == null) return 1;
        return (st.lastMintId2 != null && st.lastMintId2.length() > 0
                && model.routine(st.lastMintId2) != null) ? 2 : 1;
    }

    /** How many sessions of a track are already filed under today's local day. Counted from
     *  the SESSION LOG rather than from a flag set at the end of a run: a flag would have to
     *  be cleared at midnight by something, and nothing here runs at midnight.
     *
     *  IT DOES NOT CARE HOW THE ROUTINE WAS CHOSEN. Picking the length routine out of the
     *  library by hand files a length session exactly as accepting the plan's offer does, so
     *  running a track out of order simply works - the day's remaining work is derived from
     *  what was filed, never from what was suggested. */
    public static int trackRunsToday(Model model, int track, long now) {
        if (model == null) return 0;
        int today = PhotoCalendar.dayKey(now);
        int n = 0;
        for (int i = 0; i < model.sessLog.all.size(); i++) {
            Model.Sess ss = model.sessLog.all.get(i);
            if (ss == null || ss.manual) continue;
            if (sessionDay(ss) != today) continue;
            Model.Routine r = model.routine(ss.routineId);
            if (r == null) continue;
            int tk = r.trainerTrack;
            boolean hit = (track == Plan.TRACK_LENGTH) ? tk == Plan.TRACK_LENGTH
                                                       : isGirthTrack(tk);
            if (hit) n++;
        }
        return n;
    }

    /**
     * t10 fix (review A F4) - WHETHER A FILED SESSION RAN: the routine got underway and did
     * real work - it finished, or it delivered real pressure for a meaningful stretch
     * (Summary#trainedDay, the streak's own rule), with time on the clock. An attempt
     * abandoned at the seal check files 0:00, nothing delivered and no peak, by design; it is
     * kept in History and in {@link #trackRunsToday}'s tally, but it is not "the other track's
     * session".
     *
     * THE DAY'S AUTOMATIC RULES READ THIS, because since t10 they act without asking: the
     * warm-up that goes after the other track (P4), the fatigue block girth gives up after
     * length (R4), the coda length gives up after girth (R1), the one tissue test a day (S05)
     * and the feeders that follow girth (R-09). An attempt that never ran must not take a
     * warm-up or a fatigue block away from the session that really is the day's first.
     */
    public static boolean ran(Model.Sess ss) {
        return ss != null && !ss.manual && ss.durSec > 0 && Summary.trainedDay(ss);
    }

    /** Whether a session of the track (either girth style counts as girth) RAN today
     *  ({@link #ran}) - the day's "the other track ran today". */
    public static boolean ranToday(Model model, int track, long now) {
        if (model == null) return false;
        int today = PhotoCalendar.dayKey(now);
        for (int i = 0; i < model.sessLog.all.size(); i++) {
            Model.Sess ss = model.sessLog.all.get(i);
            if (!ran(ss) || sessionDay(ss) != today) continue;
            Model.Routine r = model.routine(ss.routineId);
            if (r == null) continue;
            boolean hit = (track == Plan.TRACK_LENGTH) ? r.trainerTrack == Plan.TRACK_LENGTH
                                                       : isGirthTrack(r.trainerTrack);
            if (hit) return true;
        }
        return false;
    }

    /** The feeder is READY only when it is eligible, its routine exists, and its gap has
     *  elapsed - four hours from the last feeder and, since t10 (A6), from the END of today's
     *  girth session ({@link SameDay#feederFromMs}, S10). */
    public static boolean feederReady(Model model, long now) {
        if (!feederOn(model, now)) return false;
        return feederFromMs(model, now) <= now;
    }

    /** The feeder is part of today at all: eligible, its routine in the library, fewer than
     *  today's two filed - and (t10 R-09, A6) a GIRTH day: today's girth session has run. The
     *  feeders follow the girth work, 4-6 h after it (Plan#FEEDER_DAYS_WORDS); a day of length
     *  only, or a rest day, has none. Whether it is due YET is {@link #feederFromMs}. */
    public static boolean feederOn(Model model, long now) {
        if (model == null || !Plan.feederEligible(model.trainerGirth.level)) return false;
        String fid = model.trainerFeederMintId;
        if (fid == null || fid.length() == 0 || model.routine(fid) == null) return false;
        // A girth session that RAN (#ran): an attempt aborted at the seal check is not one.
        if (!ranToday(model, model.trainerGirthStyle, now)) return false;
        return feedersToday(model, now) < Plan.FEEDER_PER_DAY;
    }

    /** How many feeders are filed under today. */
    public static int feedersToday(Model model, long now) {
        return (int) feederTally(model, now)[0];
    }

    /** {count, start of the latest} of today's feeder sessions - the feeder's own clock has
     *  always run from a feeder's START, and still does. */
    private static long[] feederTally(Model model, long now) {
        long[] out = new long[]{ 0L, 0L };
        if (model == null) return out;
        String fid = model.trainerFeederMintId;
        if (fid == null || fid.length() == 0) return out;
        int today = PhotoCalendar.dayKey(now);
        for (int i = 0; i < model.sessLog.all.size(); i++) {
            Model.Sess ss = model.sessLog.all.get(i);
            if (ss == null || ss.manual || !fid.equals(ss.routineId)) continue;
            if (sessionDay(ss) != today) continue;
            out[0]++;
            if (ss.ts > out[1]) out[1] = ss.ts;
        }
        return out;
    }

    /**
     * S10 - WHEN THE NEXT FEEDER MAY START: 0 for now, a time for a wait to show,
     * {@link Long#MAX_VALUE} when today's are done. Measured from the latest of the last
     * feeder and the END of today's GIRTH session (t10 R-09, A6: 4-6 h after the girth work;
     * a length session is not what the feeder follows) - {@link SameDay#feederFromMs}.
     */
    public static long feederFromMs(Model model, long now) {
        if (model == null) return Long.MAX_VALUE;
        long[] t = feederTally(model, now);
        long girthEnd = lastTrackEndMs(model, model.trainerGirthStyle, now);
        long from = SameDay.feederFromMs((int) t[0], t[1], girthEnd);
        return from == Long.MAX_VALUE || from > now ? from : 0L;
    }

    /* ===================================================================== *
     *  0.10 - THE SAME DAY (S04-S10, S14): what today has already filed,     *
     *  read for SameDay's rules. Nothing here is persisted; every answer is  *
     *  derived from the session log on each call, like trackRunsToday.       *
     * ===================================================================== */

    /** When a session ENDED: its start plus the duration it filed. The start is the day it
     *  belongs to (audit A20); the end is when its tissue stopped being worked. */
    public static long endMs(Model.Sess ss) {
        if (ss == null) return 0L;
        return ss.ts + Math.max(0, ss.durSec) * 1000L;
    }

    /** The latest end among today's sessions of a track that RAN (either girth style counts
     *  as girth, as {@link #trackRunsToday} counts them), or 0 when none ran. An attempt
     *  aborted at the seal check is not one ({@link #ran}, review A F4): it ended nothing a
     *  second session could follow without its warm-up. */
    public static long lastTrackEndMs(Model model, int track, long now) {
        if (model == null) return 0L;
        int today = PhotoCalendar.dayKey(now);
        long last = 0L;
        for (int i = 0; i < model.sessLog.all.size(); i++) {
            Model.Sess ss = model.sessLog.all.get(i);
            if (!ran(ss)) continue;
            if (sessionDay(ss) != today) continue;
            Model.Routine r = model.routine(ss.routineId);
            if (r == null) continue;
            boolean hit = (track == Plan.TRACK_LENGTH) ? r.trainerTrack == Plan.TRACK_LENGTH
                                                       : isGirthTrack(r.trainerTrack);
            if (hit) last = Math.max(last, endMs(ss));
        }
        return last;
    }

    /** The other half of a both-tracks day: length for a girth track, girth for length. */
    public static int otherTrack(Model model, int track) {
        return track == Plan.TRACK_LENGTH
            ? (model == null ? Plan.TRACK_GIRTH_INTERVAL : model.trainerGirthStyle)
            : Plan.TRACK_LENGTH;
    }

    /** Whether the length track is running today at all: enrolled, not on a dated rest, and
     *  (S11) not in a deload week, which is a week off length - no session, so no traction. */
    public static boolean lengthLive(Model model, long now) {
        return model != null && model.trainerLengthOn && !model.trainerLength.resting(now)
            && !Deload.lengthRests(model, now)
            && !MonthBreak.on(model, now);          // t10-K (B1): the break rests both tracks
    }

    /** S14 - the girth track is paused while the length track runs a girth-focus block; and
     *  (t10 R-23) while the girth offer's "4 weeks of length focus" rests it (a dated girth
     *  rest, PlanCards#LENGTH_FOCUS_WEEKS). */
    public static boolean girthPausedNow(Model model, long now) {
        return model != null
            && (SameDay.girthPaused(lengthLive(model, now), model.trainerLength.inGirthFocus(now))
                || model.trainerGirth.resting(now)
                || MonthBreak.on(model, now));      // t10-K (B1): the break rests both tracks
    }

    /** t10 R-23 - the girth offer's "4 weeks of length focus" taken at `now`: a dated girth
     *  rest of {@link PlanCards#LENGTH_FOCUS_WEEKS}, its start kept (O7). The caller saves. */
    public static void startGirthRest(Model m, long now) {
        if (m == null) return;
        m.trainerGirth.restFromMs = now;
        m.trainerGirth.restUntilMs = now + PlanCards.LENGTH_FOCUS_WEEKS * 7L * 86400000L;
    }

    /**
     * t10 O7 (review D, F3) - "COME BACK TO GIRTH NOW": the rest ENDS AT THE TAP rather than
     * being wiped. Its end moves to `now`, so girth is offered at once (the rest no longer runs,
     * Model.TrainerTrackState#resting), and its start and that end still say which weeks it
     * touched - so the week it ended in is not judged a missed girth week for taking the way
     * back the app offered. A rest not running is left as it is. The caller saves.
     */
    public static void endGirthRest(Model m, long now) {
        if (m == null || !m.trainerGirth.resting(now)) return;
        m.trainerGirth.restUntilMs = now;
    }

    /**
     * WHETHER THE GIRTH REST TOUCHED THE WEEK [weekStartMs, weekEndMs) - such a week is not one
     * of missed girth sessions, as a deload week is not (the miss policy). The rest runs from
     * its kept start to its end, ended early or not (O7); a rest saved before its start was
     * kept counts back its {@link PlanCards#LENGTH_FOCUS_WEEKS} from the end, as it always did.
     */
    public static boolean girthRestTouchesWeek(Model m, long weekStartMs, long weekEndMs) {
        if (m == null) return false;
        Model.TrainerTrackState g = m.trainerGirth;
        if (g.restUntilMs <= 0L) return false;
        long from = g.restFromMs > 0L ? g.restFromMs
            : g.restUntilMs - PlanCards.LENGTH_FOCUS_WEEKS * 7L * 86400000L;
        return SameDay.pauseTouchesWeek(from, g.restUntilMs, weekStartMs, weekEndMs);
    }

    /**
     * t10 fix (review A F1) - WHETHER TODAY'S PLAN RUNS BOTH TRACKS: the day's plan runs girth
     * and length ({@link DayLength#planRunsGirth}, {@link DayLength#planRunsLength} - the plan's
     * choice and Both alike), the girth track is in the day (enrolled, on, not paused:
     * DayLength#girthLive, the figure the day's minutes read) and the length track is running
     * today ({@link #lengthLive}: not resting, not in a deload week).
     *
     * "SAME DAYS, AS NOW" IS A DAY OF BOTH. PLAN_ANY used to be read as no promise of a length
     * session (0.10, 2026-09-26), so a length session run first on it kept its swap and coda
     * while DayLength counted the day without them. Since t10 the plan's choice is what "Same
     * days, as now" means - both tracks on each ticked day (R-60, K18), the week of every
     * upgrader - and Up next puts both forward on it. One rule now, read by the run's day flags
     * (#dayChoice: R1, R4, the girth trim), DayLength and the 90-minute hold alike.
     */
    public static boolean planRunsBothToday(Model model, long now) {
        if (model == null) return false;
        int p = dayPlanNow(model, now);
        return DayLength.planRunsGirth(p) && DayLength.planRunsLength(p)
            && DayLength.girthLive(model, now) && lengthLive(model, now);
    }

    /**
     * S06 - WHETHER TODAY RUNS BOTH TRACKS, seen from girth: a length session RAN today
     * ({@link #ranToday}), or the day's plan runs both ({@link #planRunsBothToday}).
     */
    public static boolean bothTracksToday(Model model, long now) {
        if (model == null) return false;
        return SameDay.bothTracksDay(ranToday(model, Plan.TRACK_LENGTH, now),
            planRunsBothToday(model, now), lengthLive(model, now));
    }

    /** {@link #bothTracksToday}, seen from `track`: the other track ran today, or the day's
     *  plan runs both. From length, the girth session that ran is the other half. */
    public static boolean bothTracksToday(Model model, int track, long now) {
        if (model == null) return false;
        if (track != Plan.TRACK_LENGTH) return bothTracksToday(model, now);
        return ranToday(model, model.trainerGirthStyle, now) || planRunsBothToday(model, now);
    }

    /**
     * t10 R-60 - "SAME DAYS, STOP GROWING AT 90 MIN": WHETHER A VOLUME STEP ON `track` THAT
     * ADDS `addSec` SECONDS WOULD TAKE THE BOTH-TRACKS DAY PAST {@link Plan#HELD_AT_90_MIN}.
     * The caller sets Plan.Inputs#heldAt90 from it, and the step holds with
     * Plan#HELD_AT_90_RULE.
     *
     * The day is girth plus length as their saved routines run, feeders left out
     * ({@link DayLength#parts} for a day of both). `addSec` is what the step adds: holds x
     * (hold + drop) for girth, strain sets x {@link Plan#HELD_AT_90_STRAIN_SET_SEC} for length.
     *
     * Only under {@link Schedule#LONG_CAP90}, only for a girth or length step, only when the
     * day really runs both tracks (one track on is "as now", K21), and only for a step that
     * adds something. Pure: it reads the model and changes nothing.
     */
    /**
     * Parity run 3, A3-2 - WHAT `holds` MORE GIRTH HOLDS ADD TO THE DAY, seconds, as the run
     * will have them (R-60's check, #heldAt90): each hold and its drop, the rest before each
     * block they open (interval holds rest every few sets, Mint#workBlocks; a traditional or
     * hybrid hold is its own block) - and only the holds that run under the session's time cap
     * (R-27: past it the plan's holds are pressure, not time). The editor's model rebuilds the
     * routine to ask the same; counting the holds and drops alone let a step that opened a
     * block take a day past 90 minutes.
     */
    public static int girthStepSec(Model m, int track, Model.TrainerTrackState st, int holds) {
        if (m == null || st == null || holds <= 0) return 0;
        boolean hybrid = Mint.hybridOn(m, track, st);
        boolean fiveMin = hybrid || track == Plan.TRACK_GIRTH_TRADITIONAL;
        int holdSec = fiveMin ? Mint.HOLD_TRADITIONAL_SEC : Mint.HOLD_INTERVAL_SEC;
        int n = hybrid ? Plan.hybridHolds(st.level, st.hybridYield) : Mint.totalSets(track, st);
        int cap = m.legacyT10 ? Integer.MAX_VALUE
            : Plan.r2MaxHolds(st.level, holdSec, Mint.r2FatSec(m, track, st.level));
        int from = Math.min(n, cap), to = Math.min(n + holds, cap);
        if (to <= from) return 0;
        int per = fiveMin ? 1 : Mint.setsPerBlock(track, st.level);
        if (per > 0 && !fiveMin) per = m.rxSetsPerBlock;
        int blocks = Mint.workBlocks(to, per).length - Mint.workBlocks(Math.max(1, from), per).length;
        return (to - from) * (holdSec + Mint.DROP_SEC) + Math.max(0, blocks) * m.restSecFor(track);
    }

    public static boolean heldAt90(Model m, int track, int addSec, long now) {
        if (m == null || m.sched == null || m.sched.longDays != Schedule.LONG_CAP90) return false;
        if (track == Plan.TRACK_FEEDER || addSec <= 0) return false;
        long[] p = DayLength.parts(m, Schedule.PLAN_BOTH, now);
        if (p[0] <= 0L || p[1] <= 0L) return false;
        return p[0] + p[1] + addSec > Plan.HELD_AT_90_MIN * 60L;
    }

    /** S09 - the session filed today that PULLED most recently: its routine has traction
     *  blocks and it was not run as expansion only. Null when none. */
    public static Model.Sess lastPullToday(Model model, long now) {
        if (model == null) return null;
        int today = PhotoCalendar.dayKey(now);
        Model.Sess best = null;
        for (int i = 0; i < model.sessLog.all.size(); i++) {
            Model.Sess ss = model.sessLog.all.get(i);
            if (ss == null || ss.manual || sessionDay(ss) != today) continue;
            if (!Say.isTraction(model.routine(ss.routineId))) continue;
            if (RunShape.ranExpansionOnly(ss.shape)) continue;
            if (best == null || endMs(ss) > endMs(best)) best = ss;
        }
        return best;
    }

    /** S09 - until when the next track waits after today's last pull; 0 for no wait. */
    public static long pullGapUntil(Model model, long now) {
        Model.Sess pull = lastPullToday(model, now);
        if (pull == null) return 0L;
        boolean cleared = model.pullClearedSessId != null
                       && model.pullClearedSessId.equals(pull.id);
        return SameDay.pullGapUntil(endMs(pull), cleared, now);
    }

    /** S07 - the routine clock, in seconds, of today's girth and length sessions. Feeders
     *  are left out (SameDay#DAY_BUDGET_MIN says why). */
    public static long daySessionSec(Model model, long now) {
        if (model == null) return 0L;
        int today = PhotoCalendar.dayKey(now);
        long sec = 0L;
        for (int i = 0; i < model.sessLog.all.size(); i++) {
            Model.Sess ss = model.sessLog.all.get(i);
            if (ss == null || ss.manual || sessionDay(ss) != today) continue;
            Model.Routine r = model.routine(ss.routineId);
            if (r == null) continue;
            if (r.trainerTrack == Plan.TRACK_LENGTH || isGirthTrack(r.trainerTrack))
                sec += Math.max(0, ss.durSec);
        }
        return sec;
    }

    /** {@link #dayChoice(Model, Model.Routine, long, boolean, boolean, boolean)} with the hand
     *  release kept. */
    public static RunShape.Choice dayChoice(Model model, Model.Routine r, long now,
                                            boolean skipWarmSaid, boolean expansionOnlySaid) {
        return dayChoice(model, r, now, skipWarmSaid, expansionOnlySaid, false);
    }

    /**
     * WHAT TODAY TAKES OUT OF A RUN OF {@code r} - the day's own rules (S05, S06) plus the
     * answers only the person gives at START (S04's warm-up, S08's expansion only and, with
     * it, S08's hand release), which the caller passes in. Nothing is asked of a routine that
     * is not on a plan track.
     */
    public static RunShape.Choice dayChoice(Model model, Model.Routine r, long now,
                                            boolean skipWarmSaid, boolean expansionOnlySaid,
                                            boolean skipReleaseSaid) {
        RunShape.Choice c = new RunShape.Choice();
        if (model == null || r == null) return c;
        int tk = r.trainerTrack;
        boolean girth = isGirthTrack(tk);
        if (!girth && tk != Plan.TRACK_LENGTH) return c;
        int other = otherTrack(model, tk);
        /* t10 R-05 (P4) - THE WARM-UP GOES BY ITSELF when the other track's session ended
         * within the half hour (SameDay#warmSkipAuto): skipped, not offered. */
        c.skipWarm = (skipWarmSaid
            || SameDay.warmSkipAuto(lastTrackEndMs(model, other, now), now))
            && RunShape.hasWarmUp(r);
        c.expansionOnly = expansionOnlySaid && tk == Plan.TRACK_LENGTH && Say.isTraction(r);
        // The hand release goes only with the pulls it prepares for, and only on the answer.
        c.skipRelease = skipReleaseSaid && c.expansionOnly && RunShape.hasRelease(r);
        // The other track's session that RAN (#ran): an aborted attempt tested nothing.
        c.noTissueTest = SameDay.tissueTestSkipped(ranToday(model, other, now),
                                                   Tau.runsBefore(r) || Tau.runsAfter(r));
        /* S06 - ONLY THE PLAN'S OWN GIRTH ROUTINE, and only the part that closes the day's
         * girth work (the second half of a split): the sets are the plan's to count, and a
         * routine somebody built themselves is theirs.
         *
         * t10 R-06 (R1, 2A) - AND ONLY WITH EXPANSION-ONLY LENGTH. With a length cylinder the
         * length session drops its expansion on a day of both tracks (#bothTracks below), so
         * girth gives up nothing for it; with no cylinder the length session IS expansion, and
         * girth keeps giving up its 5 holds (at least one left) - the owner's 2A. */
        if (girth && isDaysGirthMint(model, r) && bothTracksToday(model, now)
                && !model.lengthPulls())
            c.girthSetsOff = SameDay.girthSetsOff(RunShape.workSets(model, r));
        /* t10 R-06 (R1) - A DAY OF BOTH TRACKS: the length session that pulls ends after its
         * strain holds - no swap, no expansion. Either order: girth already filed today, or
         * the day runs both. Not when the person chose expansion only (S08), which IS the
         * expansion. The plan's own length routine only (RunShape keeps a person's pulls).
         * The day is #bothTracksToday's - the plan's choice ("Same days, as now") as much as
         * Both (review A F1). */
        if (tk == Plan.TRACK_LENGTH && Say.isTraction(r) && !c.expansionOnly
                && r.id != null && r.id.equals(model.trainerLength.lastMintId)
                && bothTracksToday(model, Plan.TRACK_LENGTH, now))
            c.bothTracks = true;
        /* t10 R-07 (R4) - GIRTH AFTER LENGTH: a length session RAN today before this girth
         * run (#ranToday - an attempt aborted at the seal check is not one, review A F4), so
         * the day is one of both, the fatigue block goes and the person's choice leads in. */
        if (girth && isDaysGirthMint(model, r) && ranToday(model, Plan.TRACK_LENGTH, now))
            c.girthAfterLength = true;
        return c;
    }

    /** Whether {@code r} is the plan's girth routine that closes the day's girth work. */
    public static boolean isDaysGirthMint(Model model, Model.Routine r) {
        if (model == null || r == null || r.id == null) return false;
        Model.TrainerTrackState st = model.trainerGirth;
        String last = partsOf(model, st) == 2 ? st.lastMintId2 : st.lastMintId;
        return r.id.equals(last);
    }

    /** t10 R-05 (P4) - minutes since the other track's session ended, when this run's warm-up
     *  goes by itself (within the half hour); -1 when it stays (SameDay#warmSkipAskMinutes). */
    public static int warmSkipAskMinutes(Model model, Model.Routine r, long now) {
        if (model == null || r == null || !RunShape.hasWarmUp(r)) return -1;
        int tk = r.trainerTrack;
        if (!isGirthTrack(tk) && tk != Plan.TRACK_LENGTH) return -1;
        return SameDay.warmSkipAskMinutes(lastTrackEndMs(model, otherTrack(model, tk), now), now);
    }

    /** S08 - a length session that pulls, about to start after girth ran today. */
    public static boolean girthFirstWarning(Model model, Model.Routine r, long now) {
        if (model == null || r == null || r.trainerTrack != Plan.TRACK_LENGTH) return false;
        return SameDay.girthFirstWarning(
            ranToday(model, model.trainerGirthStyle, now), Say.isTraction(r));
    }

    /**
     * The live inputs, handed to {@link UpNext#pick} - which is pure, and asserted there.
     *
     * IT LIVES HERE rather than on the screen that draws it because the alarm receiver asks
     * the same question three hours later, and two copies of "is a track still outstanding"
     * is exactly the kind of pair that drifts into disagreeing.
     */
    public static UpNext upNextNow(Model model, long now, boolean remainder) {
        if (model == null) return UpNext.pick(Schedule.PLAN_ANY, false, true, true,
                                              false, false, false);
        boolean gDone = trackRunsToday(model, model.trainerGirthStyle, now)
                        >= partsOf(model, model.trainerGirth);
        boolean lDone = trackRunsToday(model, Plan.TRACK_LENGTH, now)
                        >= partsOf(model, model.trainerLength);
        /* A TRACK ON A DATED REST IS NOT ON TODAY. It is still ENROLLED - the level, the
         * load and the history all stand - so the flag alone would go on putting "length
         * first today" in front of somebody who has just been told to take four weeks off. */
        boolean lengthLive = lengthLive(model, now);
        // 0.10 - the girth-focus pause (S14), the soft wait after a pull (S09) and the
        // feeder's gap from the main session (S10), each SameDay's rule over today's log,
        // beside the feeder's rest-day setting (S16).
        return UpNext.pick(dayPlanNow(model, now), lengthLive,
                           gDone, lDone, model.rxLengthFirst, remainder,
                           girthPausedNow(model, now), pullGapUntil(model, now),
                           feederOn(model, now), feederFromMs(model, now),
                           feederRestDaysOn(model), girthFinishedToday(model, now));
    }

    /**
     * t10 device walk - WHETHER TODAY'S GIRTH WORK IS DONE: a girth session filed today that
     * finished, or ended at its target ("Finish here" once the mid-run check found it, O6:
     * Sess#targetHitAtSet). A session stopped part-way may still have run (#ran) - the feeder
     * follows it - but "the work is done" is not said of it.
     */
    public static boolean girthFinishedToday(Model model, long now) {
        if (model == null) return false;
        int today = PhotoCalendar.dayKey(now);
        for (int i = 0; i < model.sessLog.all.size(); i++) {
            Model.Sess ss = model.sessLog.all.get(i);
            if (ss == null || ss.manual || sessionDay(ss) != today) continue;
            if (!ss.completed && ss.targetHitAtSet <= 0) continue;
            Model.Routine r = model.routine(ss.routineId);
            if (r != null && isGirthTrack(r.trainerTrack)) return true;
        }
        return false;
    }

    /**
     * S13 (a): THE TRACK THE MEASUREMENT CADENCE COUNTS when nothing has been started yet -
     * the measurement reminder asks it at its hour. The one Up next would start: a plan track
     * when enrolled and something is next, else {@link Model#TRAINER_TRACK_NONE}, which
     * keeps the old count of every session.
     */
    public static int cadenceTrackNow(Model model, long now) {
        if (model == null || !model.trainerEnrolled) return Model.TRAINER_TRACK_NONE;
        return UpNext.trackOf(upNextNow(model, now, false).what, model.trainerGirthStyle);
    }

    /**
     * WHAT TODAY IS PLANNED TO RUN, with a rest day answered as {@link Schedule#PLAN_REST}.
     *
     * AND A REST DAY IS A REST DAY. planAt() answers what a day RUNS; whether today is a
     * training day at all is a separate flag, and UpNext was never shown it - so on a
     * scheduled rest weekday Today printed "Rest day" and "Girth is what today is for" on the
     * same screen. An override still counts as a deliberate yes.
     *
     * One derivation, because UpNext and the feeder's card both ask "is today a rest day"
     * and must not answer it differently.
     */
    public static int dayPlanNow(Model model, long now) {
        if (model == null || model.sched == null) return Schedule.PLAN_ANY;
        return (model.sched.overriddenAt(now) || model.sched.isTrainingDay(now))
            ? model.sched.planAt(now) : Schedule.PLAN_REST;
    }

    /**
     * S16 - WHETHER THE FEEDER STANDS DOWN TODAY: today is a rest day (a rest weekday, or a
     * rest chosen for today) and the person has left {@link Model#feederRestDays} at its
     * default, training days only. The one rule UpNext and the card both read.
     */
    public static boolean feederOffToday(Model model, long now) {
        /* t10 R-09 (A6) - FEEDERS ARE GIRTH DAYS ONLY: "feeder on rest days too" is disabled.
         * Its saved value is kept, and read as false (#feederRestDaysOn). */
        if (model == null || feederRestDaysOn(model)) return false;
        return dayPlanNow(model, now) == Schedule.PLAN_REST;
    }

    /** t10 R-09 (A6) - whether "feeder on rest days too" is in force: never, since the feeders
     *  follow the girth session. The setting stays saved (Model#feederRestDays) and hidden. */
    public static boolean feederRestDaysOn(Model model) {
        return false;
    }

    /**
     * S16 - THE FEEDER'S DAYS, IN WORDS, NAMING BOTH READINGS (the owner's decision,
     * 2026-09-26). The guidance differs with itself: its girth routines place feeder sets on
     * training days, four to six hours from the other sessions; elsewhere it allows light
     * pumping at reduced pressure on rest days. The setting chooses which is
     * followed, and the card says which one that is and what the other says.
     */
    public static String feederDaysNote(boolean restDays) {
        if (restDays)
            return "Rest days too, as one part of the guidance allows; another keeps feeder "
                + "sets to training days.";
        return "Training days only, as one part of the guidance has it; another also allows "
            + "rest days.";
    }

    /**
     * S16 FOLLOW-UP (the owner's decision, 2026-09-26): THE ONE QUESTION an existing trainer is
     * asked after the update ({@link Model#feederDaysAskDue}) - short, plain, both guides named.
     */
    public static final String FEEDER_DAYS_ASK_TITLE = "Feeders on rest days?";
    public static final String FEEDER_DAYS_ASK_TEXT = "One part of the guidance says training "
        + "days only; another allows light pumping on rest days.";

    /** The same, in full - behind the note's info, where the paragraph belongs. */
    public static String feederDaysWhy() {
        return "The guidance differs on this. One part puts feeder sets on training days, four "
            + "to six hours from your other sessions. Another also allows light pumping at "
            + "reduced pressure on rest days. The switch below chooses which one the plan "
            + "follows; training days only is the default.";
    }

    /** The most recent recorded decision for a track, or null — the dedup input for the
     *  history list ({@link Mint#shouldRecord}). Newest-first list, so the first match wins. */
    public static Model.TrainerDecision latestDecisionForTrack(Model model, int track) {
        if (model == null) return null;
        for (int i = 0; i < model.trainerDecisions.size(); i++) {
            Model.TrainerDecision d = model.trainerDecisions.get(i);
            if (d != null && d.track == track) return d;
        }
        return null;
    }

    /** The current week-table row for a track/level/weekIndex, or null when that
     *  track/level has no table (traditional girth, L3+, length) — {@link Plan#rowForOrdinal}
     *  already handles table exhaustion (repeats the last training week) for the tables
     *  that do exist. */
    private static Plan.Week weekTableRow(int track, int level, int weekIndex) {
        if (track == Plan.TRACK_GIRTH_INTERVAL) {
            if (level == Plan.L1) return Plan.rowForOrdinal(Plan.GIRTH_INTERVAL_L1, weekIndex);
            if (level == Plan.L2) return Plan.rowForOrdinal(Plan.GIRTH_INTERVAL_L2, weekIndex);
        }
        return null;
    }

    /** Monday 00:00 (local time) of the calendar week containing {@code ts} — this app's own
     *  week-start convention ({@code Schedule.DAY_ABBR} is Monday-first; SessionActivity's
     *  own weekday index maps Sunday to the last slot). Plan round-3 revision: calendar
     *  weeks, not rolling-7-from-enrollment. */
    public static long mondayStartMs(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        int dow = c.get(Calendar.DAY_OF_WEEK);           // SUNDAY=1 .. SATURDAY=7
        int back = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        c.add(Calendar.DAY_OF_MONTH, -back);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /* ===================================================================== *
     *  STAGE H TASK 5 — OUTSIDE-TAB HOOKS' PURE LOGIC: deload-day detection   *
     *  (shared by the confirm-start readiness ask, the streak/heatmap        *
     *  widening, and the Schedule reminder reword) and the trainer-reminder  *
     *  no-duplicate-notifications predicate.                                 *
     * ===================================================================== */

    /**
     * Whether {@code now} falls inside the CURRENT/most recent deload window — {@link
     * Deload#inWindow} asked here for callers that already have the enrollment check on hand.
     * A tap deload still runs the {@link Plan#LAYOFF_MS} seven days this comment used to
     * promise everywhere, but a REPORTED deload carries its own end ({@link
     * Model#trainerLastDeloadEndMs}), so the window can now be shorter or longer than that —
     * {@link Deload} is the one place that decides which. The single source of truth for "is
     * a deload in progress right now", reused by SessionActivity's own render-time check
     * (which now simply calls this) and by every outside-tab hook below — never a second
     * definition of the same window.
     *
     * False whenever the user is not enrolled or has never taken a deload: a deload can only
     * be "in progress" for a plan that has actually started one.
     */
    public static boolean inDeloadWeek(Model m, long now) {
        return m != null && m.trainerEnrolled && Deload.inWindow(m, now);
    }

    /**
     * The day-number range [start, endExclusive) of the current/most recent deload week, in
     * {@link Summary#dayNumber} terms — the SAME local-calendar-day arithmetic the streak and
     * consistency/dose-heatmap surfaces already key on, so a deload day can never land on a
     * different calendar day there than it does anywhere else in the app.
     *
     * {0, 0} (an empty, non-matching range — {@code start >= end}) when the user is not
     * enrolled or has never taken a deload: every caller below treats an empty range as "no
     * deload in effect", the same sentinel {@link Summary}'s own deload-aware overloads
     * already default to, so this can be passed unconditionally without an enrollment check
     * at every call site.
     *
     * Deliberately NOT anchored to `now`: it names the window itself, not whether `now` sits
     * inside it (see {@link #inDeloadWeek} for that question) — a caller building a 7/30-day
     * strip of PAST days (the consistency card, the dose heatmap) needs the window's dates
     * even after it has ended, not only while it is still current.
     */
    public static long[] deloadDayRange(Model m) {
        if (m == null || !m.trainerEnrolled || m.trainerLastDeloadMs <= 0L)
            return new long[]{ 0L, 0L };
        long start = Summary.dayNumber(Deload.startMs(m));
        long end = Summary.dayNumber(Deload.endMs(m));
        return new long[]{ start, end };
    }

    /**
     * ROUND-2 RULING — "no duplicate notifications: trainer's training-day reminder
     * auto-suppresses when Schedule's session reminder is enabled." Pure decision, so it can
     * be pinned by SelfTest independently of whether/when the alarm itself is ever wired
     * (Task 5's report records that firing is deferred; this predicate is the ready answer
     * for when it is).
     *
     * Suppressed when the toggle is off (nothing to fire), OR when the Schedule's own "train
     * today" reminder already covers the same moment ({@code sched.remind} AND at least one
     * day selected — an enabled reminder with no days picked fires nothing, so it must not
     * suppress the trainer's own reminder either).
     */
    public static boolean trainerTrainingDayShouldFire(Model m) {
        if (m == null || !m.trainerRemindTrainingDay) return false;
        if (m.sched != null && m.sched.remind && m.sched.any()) return false;
        return true;
    }

    /* ================= ARRANGEMENT B - WHAT THE NEW BLOCKS NEED =====================
     *
     * Three questions the redrawn Trainer asks that nothing answered before, all of them
     * arithmetic over the session log, and all of them here rather than in the Activity so
     * the desktop harness can hold them to account.
     */

    /**
     * HOURS SINCE THE LAST FILED SESSION, or -1 when there has never been one.
     *
     * The readiness line's whole content. Rounded DOWN, because "36 hours" reading as 35 is
     * a rounding artefact and reading as 37 is a claim about rest that has not happened yet.
     */
    public static long hoursSinceLastSession(java.util.List<Model.Sess> log, long nowMs) {
        if (log == null || log.isEmpty()) return -1L;
        long newest = 0L;
        for (int i = 0; i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s != null && s.ts > newest) newest = s.ts;
        }
        if (newest <= 0L || nowMs <= newest) return 0L;
        return (nowMs - newest) / 3600000L;
    }

    /**
     * THE LAST N SESSIONS' DELIVERED NET, newest LAST so it reads left to right like time.
     *
     * Missing net comes back as -1 rather than 0: a session filed before net was recorded
     * delivered an unknown amount, and drawing it as a zero bar would accuse somebody of a
     * session they may well have completed.
     */
    public static double[] recentNetMin(java.util.List<Model.Sess> log, int n) {
        int want = Math.max(1, n);
        double[] out = new double[want];
        for (int i = 0; i < want; i++) out[i] = -1;
        if (log == null) return out;
        int taken = 0;
        for (int i = 0; i < log.size() && taken < want; i++) {
            Model.Sess s = log.get(i);
            if (s == null) continue;
            out[want - 1 - taken] = s.netTupSec == null ? -1
                                  : s.netTupSec.doubleValue() / 60.0;
            taken++;
        }
        return out;
    }

    /**
     * ONE CELL PER DAY for the consistency grid, oldest first, as delivered net minutes -
     * or 0 for a day with no session.
     *
     * DAYS, NOT SESSIONS, and two sessions on one day add up into that day: the plan counts
     * days, so a grid that drew a second session as a second cell would picture a rule the
     * app does not use.
     */
    public static double[] dailyNetMin(java.util.List<Model.Sess> log, int todayKey, int days) {
        int want = Math.max(1, days);
        double[] out = new double[want];
        if (log == null) return out;
        for (int i = 0; i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s == null) continue;
            int back = daysBetweenKeys(sessionDay(s), todayKey);
            if (back < 0 || back >= want) continue;
            double min = s.netTupSec == null ? 0 : s.netTupSec.doubleValue() / 60.0;
            out[want - 1 - back] += min;
        }
        return out;
    }

    /**
     * WHOLE DAYS BETWEEN TWO yyyymmdd KEYS, or -1 when either is not a real key.
     *
     * Through Calendar rather than by subtracting the integers: 20240301 minus 20240228 is
     * 73, not 2, and a grid built on that arithmetic would put February's sessions in the
     * wrong cells every leap year and most months besides.
     */
    public static int daysBetweenKeys(int fromKey, int toKey) {
        if (fromKey <= 0 || toKey <= 0) return -1;
        java.util.Calendar a = java.util.Calendar.getInstance();
        java.util.Calendar b = java.util.Calendar.getInstance();
        setKey(a, fromKey);
        setKey(b, toKey);
        long ms = b.getTimeInMillis() - a.getTimeInMillis();
        return (int) Math.round(ms / 86400000.0);
    }

    private static void setKey(java.util.Calendar c, int key) {
        c.clear();
        c.set(key / 10000, ((key / 100) % 100) - 1, key % 100, 12, 0, 0);
    }

    /**
     * HOW DARK A GRID CELL IS, 0 to 4, from the minutes delivered that day.
     *
     * Fixed thresholds rather than a scale over the person's own range: a scale that
     * normalises to your best week makes a good week look average as soon as you have a
     * better one, which is the opposite of what a consistency picture is for.
     */
    public static int heatStep(double netMin) {
        if (netMin <= 0) return 0;
        if (netMin < 6) return 1;
        if (netMin < 12) return 2;
        if (netMin < 20) return 3;
        return 4;
    }

    /* ================= THE WEEKS TABLE, COLLAPSED ==================================
     *
     * The table printed one row per week, and most weeks repeat the week before them: six
     * rows to say "five sets, then six". Runs of identical weeks now become one row saying
     * how long the run lasts, so the same six rows carry twelve weeks and every row that IS
     * there is a row where something happens.
     */

    /** One row of the collapsed table: a span of weeks that are all the same. */
    /**
     * THE LAST WEEK NUMBER OF A LEVEL'S TABLE, or 0 where the level has none.
     *
     * The denominator in "week 2 of 17". L1 and L2 have tables and therefore an end; L3 and
     * L4 are open-ended by design (their volume carries and their length is decided by the
     * metric engine), so they get no denominator rather than an invented one.
     */
    public static int levelLastWeek(int level) {
        Plan.Week[] table = level == Plan.L1 ? Plan.GIRTH_INTERVAL_L1
                          : level == Plan.L2 ? Plan.GIRTH_INTERVAL_L2 : null;
        if (table == null || table.length == 0) return 0;
        return table[table.length - 1].num;
    }

    /**
     * THE TABLE'S OWN WEEK NUMBER FOR A POSITION.
     *
     * A track's weekIndex is the 1-based row within ITS LEVEL'S table (Mint#baseSets,
     * Plan#rowForOrdinal): Level 2 starts again at 1. The tables number their rows on one
     * scale - Level 1 is weeks 1-17, Level 2 weeks 18-32 - and the Trainer's week card, its
     * header and the position line read that scale. Reading a Level 2 position as a week
     * number showed Level 1's rows to somebody at Level 2. Past the end of a table, the
     * last row's number (the last training row repeats there). Levels without a table keep
     * their index as it is.
     */
    public static int tableWeekNum(int level, int weekIndex) {
        Plan.Week[] table = level == Plan.L1 ? Plan.GIRTH_INTERVAL_L1
                          : level == Plan.L2 ? Plan.GIRTH_INTERVAL_L2 : null;
        int idx = Math.max(1, weekIndex);
        if (table == null || table.length == 0) return idx;
        return table[Math.min(idx, table.length) - 1].num;
    }

    /**
     * WHAT THE WEEKS TABLE'S HEADER SAYS ABOUT WHERE YOU ARE.
     *
     * Reported from a device: the table said "wk 1-2 . now" to somebody six months into using
     * the app, and nothing on the screen could reconcile the two. The number was RIGHT - a
     * plan week advances when a week COUNTS, three training days, so six months of two-session
     * weeks genuinely is week 2 - and it was unexplained, which is a different defect and the
     * one worth fixing.
     *
     * So the header gives the number a denominator and an account of the missing time:
     * "Level 1 . week 2 of 17 . 9 weeks counted". The third figure is where six months went.
     *
     * COUNTED, not missed. The same fact can be phrased as "17 weeks did not count", and that
     * is a scoreboard of failure on a screen somebody opened to see what to do today. Nine
     * weeks counted is the identical number from the side that is true and useful.
     */
    public static String weeksHeadline(int level, int weekIndex, int countedWeeks) {
        int last = levelLastWeek(level);
        String s = levelLabel(level) + "  ·  week " + Math.max(1, weekIndex);
        if (last > 0) s += " of " + last;
        if (countedWeeks > 0)
            s += "  ·  " + countedWeeks + (countedWeeks == 1 ? " week counted"
                                                                 : " weeks counted");
        return s;
    }

    /**
     * How far through the level, 0..1 - the header bar's fill.
     *
     * 0 for a level with no table: a bar with no end to measure against would be drawing a
     * fraction of an unknown, which is worse than drawing nothing.
     */
    public static double levelProgress(int level, int weekIndex) {
        int last = levelLastWeek(level);
        if (last <= 0) return 0;
        Plan.Week[] table = level == Plan.L1 ? Plan.GIRTH_INTERVAL_L1 : Plan.GIRTH_INTERVAL_L2;
        int first = table[0].num;
        int span = last - first;
        if (span <= 0) return 0;
        double f = (double) (Math.max(first, Math.min(last, weekIndex)) - first) / span;
        return f < 0 ? 0 : f > 1 ? 1 : f;
    }

    /**
     * THE GUIDE'S OWN EVENT CELL, in the reader's language and unit.
     *
     * {@link Plan.Week#event} carried the guide's Event column, transcribed faithfully, and
     * NOTHING IN THE APP READ IT. The weeks table could show that week 20 adds a set and
     * never say why, which is the one thing a plan table exists to do.
     *
     * TRANSLATED RATHER THAN PRINTED. The stored strings are the guide's shorthand - "GATE:
     * Net 20 AND 8 hg -> L2" - written in hg because the guide is written in hg. Printing
     * them verbatim would put a hardcoded unit back into the one table that was deliberately
     * routed through {@link Model.Fmt}, so anything naming a pressure is rebuilt through the
     * formatter and the rest is expanded into words.
     *
     * AN UNKNOWN STRING FALLS THROUGH VERBATIM. A future table row with an event nobody has
     * translated yet should show the guide's own words, not vanish.
     *
     * Returns "" for events worth no row: a deload's "counters frozen" (the row already says
     * deload) and week 1's "Start" (the table starting at the start is not news).
     */
    public static String eventText(Plan.Week w) {
        if (w == null || w.event == null) return "";
        String e = w.event.trim();
        if (e.length() == 0 || w.deload) return "";
        if (e.equals("Start")) return "";
        if (e.equals("counters frozen")) return "";
        if (e.equals("+1 set (14-day cadence)")) return "+1 set · the 14-day cadence";
        if (e.equals("+1 set / 14 days")) return "+1 set · the 14-day cadence";
        if (e.equals("month-1 ceiling")) return "the month-1 pressure ceiling";
        if (e.equals("yield tracking begins")) return "yield tracking begins";
        if (e.equals("~3% ref")) return "about 3% yield is the reference here";
        if (e.equals("Net TUP milestone -> pressure phase"))
            return "net milestone reached — pressure starts moving";
        if (e.equals("+1 hg after 3 training wks at 7"))
            return "pressure steps up after 3 training weeks";
        if (e.equals("GATE: Net 20 AND 8 hg -> L2"))
            return "gate: net 20 min AND " + Model.Fmt.p(8.0 * Plan.HG) + ", in one week";
        if (e.equals("enter Level 2")) return "enter Level 2";
        if (e.equals("rest 3-5 min every 5 sets begins"))
            return "rest 3–5 min every 5 sets begins";
        if (e.equals("month-6 gate approaching")) return "the month-6 gate is close";
        if (e.equals("30 min reached - no more sets"))
            return "30 min at pressure — the sets stop adding";
        if (e.equals("GATE: month 6 AND 30 min -> L3"))
            return "gate: month 6 AND 30 min in each of the last 3 sessions";
        return e;
    }

    /** Every translated event inside a span, in week order, skipping the ones that translate
     *  to nothing. A span is several weeks collapsed into one row, so it can carry more than
     *  one - and dropping all but the first would hide a change. */
    public static java.util.List<String> eventsIn(java.util.List<Plan.Week> all,
                                                  int from, int to) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (all == null) return out;
        for (int i = 0; i < all.size(); i++) {
            Plan.Week w = all.get(i);
            if (w == null || w.num < from || w.num > to) continue;
            String s = eventText(w);
            if (s.length() > 0) out.add(s);
        }
        return out;
    }

    public static final class Span {
        public final int from, to, sets;
        public final double netMin, hgLo, hgHi;
        public final boolean deload;
        Span(int from, int to, int sets, double netMin, double hgLo, double hgHi,
             boolean deload) {
            this.from = from; this.to = to; this.sets = sets; this.netMin = netMin;
            this.hgLo = hgLo; this.hgHi = hgHi; this.deload = deload;
        }
        /** "wk 6" or "wk 6-7" - a span of one does not print a range. */
        public String label() {
            return from == to ? "wk " + from : "wk " + from + "\u2013" + to;
        }
    }

    /**
     * COLLAPSE A WEEK TABLE INTO SPANS, from `fromWeek` onward, at most `maxRows` of them.
     *
     * Two weeks join a span when their sets, net target and pressure bounds all match. A
     * DELOAD never joins anything, not even another deload: they are separate events a
     * fortnight apart, and printing "wk 5-9 deload" would describe a five-week holiday
     * nobody has been given.
     *
     * The current week is included rather than skipped. A table that starts at next week
     * gives the reader nothing to compare against - which is why the built one opened at
     * "wk 2" and left you to remember what wk 1 had been.
     */
    public static java.util.List<Span> collapseWeeks(java.util.List<Plan.Week> table,
                                                     int fromWeek, int maxRows) {
        java.util.List<Span> out = new java.util.ArrayList<Span>();
        if (table == null) return out;
        int i = 0;
        while (i < table.size() && out.size() < Math.max(1, maxRows)) {
            Plan.Week w = table.get(i);
            if (w == null || w.num < fromWeek) { i++; continue; }
            if (w.deload) {
                out.add(new Span(w.num, w.num, 0, 0, 0, 0, true));
                i++;
                continue;
            }
            int j = i + 1;
            while (j < table.size()) {
                Plan.Week n = table.get(j);
                if (n == null || n.deload) break;
                if (n.sets != w.sets) break;
                if (Math.abs(n.netTupMin - w.netTupMin) > 1e-9) break;
                if (Math.abs(n.pressHgLo - w.pressHgLo) > 1e-9) break;
                if (Math.abs(n.pressHgHi - w.pressHgHi) > 1e-9) break;
                j++;
            }
            out.add(new Span(w.num, table.get(j - 1).num, w.sets, w.netTupMin,
                             w.pressHgLo, w.pressHgHi, false));
            i = j;
        }
        return out;
    }

    /** The sets a deload replaces - what the week before it was doing. 0 when unknown,
     *  which is only possible for a deload in the first row of a table. */
    public static int deloadReplaces(java.util.List<Plan.Week> table, int deloadWeekNum) {
        if (table == null) return 0;
        int best = 0;
        for (int i = 0; i < table.size(); i++) {
            Plan.Week w = table.get(i);
            if (w == null || w.deload) continue;
            if (w.num < deloadWeekNum) best = w.sets;
        }
        return best;
    }

    /* ===================================================================== *
     *  THE NEXT WEEKS, AS THE ROUTINE WILL RUN THEM                         *
     * ===================================================================== */

    /** One calendar week, the least time a counted training week can take. */
    static final long WEEK_MS = 7L * 24L * 60L * 60L * 1000L;

    /**
     * ONE ROW OF "THE NEXT WEEKS" - what the routine is written with over a span of weeks: its
     * holds and their length, the pressure it runs at, and whether a fatigue block leads it.
     * `from`/`to` are the tables' own week numbers (Level 2 is weeks 18-32), 0 at a level with
     * no table. A deload row carries the holds of the week before it, which it replaces.
     */
    public static final class AheadRow {
        public final int from, to;
        public final boolean deload;
        public final boolean now;
        public final int holds, holdSec, kpa;
        public final boolean fatigue;
        AheadRow(int from, int to, boolean deload, boolean now, int holds, int holdSec,
                 int kpa, boolean fatigue) {
            this.from = from; this.to = to; this.deload = deload; this.now = now;
            this.holds = holds; this.holdSec = holdSec; this.kpa = kpa; this.fatigue = fatigue;
        }
        /** "10×2min" - the holds as the routine's own name counts them (Mint#name). */
        public String holdsText() {
            String h = holdSec % 60 == 0 ? String.valueOf(holdSec / 60) : Say.fmtMin(holdSec / 60.0);
            return Math.max(1, holds) + "×" + h + "min";
        }
        /** The minutes at pressure those holds make. */
        public double netMin() { return Math.max(1, holds) * (holdSec / 60.0); }
        /** "wk 6", "wk 6–7", or "now" at a level with no table. */
        public String label() {
            String l = from <= 0 ? "now" : from == to ? "wk " + from : "wk " + from + "–" + to;
            return from > 0 && now ? l + "  ·  now" : l;
        }
        /** "10×2min  ·  20.0 min  ·  −8.9 inHg  ·  + fatigue block", or the deload's line. */
        public String value() {
            if (deload) return "deload — instead of " + holdsText();
            return holdsText() + "  ·  " + Say.fmtMin(netMin()) + " min  ·  "
                + Model.Fmt.p(kpa) + (fatigue ? "  ·  + fatigue block" : "");
        }
        boolean sameWork(AheadRow o) {
            return o != null && !o.deload && !deload && o.holds == holds
                && o.holdSec == holdSec && o.kpa == kpa && o.fatigue == fatigue;
        }
    }

    /**
     * "THE NEXT WEEKS" FOR A GIRTH INTERVAL TRACK, in the figures its routine will be written
     * with - the owner's standing decision: what the card shows is what the routine runs.
     *
     * IT PRINTED THE GUIDANCE'S TABLE, and read the wrong one. A level with no table (3 and 4)
     * passed its own week number to the master plan and was shown Level 1's rows - "5 sets,
     * 10 min, −5.0 inHg" to somebody whose routine was 10×2min at −8.9. And at the levels that
     * have a table it printed the table's raw pressure, where the routine runs the person's own
     * (the plan's figure plus their offset, their Program's bias, within their limits).
     *
     * NOW EVERY FIGURE IS THE PRESCRIPTION'S. Each week is {@link #trackRxAt} at that position
     * - the function the routine itself is written from - and then what the builder makes of
     * it on that day: the holds it lays out (RxBuild#holdsRun, the hybrid's and a chosen hold
     * length's included), and the pressure it commands (RxBuild#commanded: the bias, the
     * hard limits, that day's cut). The day is the earliest a week can be reached - one
     * calendar week per counted week - so the month that week's limits are read for is the
     * soonest it can be ({@link #monthIndexNow}).
     *
     * THE ONE RULE THAT MOVES THE PLAN'S FIGURE WITH THE TABLE is Level 1's volume phase: the
     * working pressure follows the table's row, one step a week at most, once each of the last
     * three sessions held its planned minutes (Plan#followL1TablePressure). It is asked of
     * every week ahead, assuming those sessions hold - which {@link #weeksAheadNote} says. The
     * current week is the track as it stands: anything the plan has already decided is in it.
     *
     * A LEVEL WITH NO TABLE gets one row, the current week's real prescription, and never
     * another level's rows: nothing on a calendar moves it, so there is nothing true to print
     * ahead of it ({@link #noTableNextWords} says what does move it). Weeks with the same work
     * collapse into one row; a deload never joins one ({@link #collapseWeeks}'s rule).
     */
    public static List<AheadRow> weeksAhead(Model m, int track, long nowMs, int maxRows) {
        List<AheadRow> out = new java.util.ArrayList<AheadRow>();
        if (m == null || track != Plan.TRACK_GIRTH_INTERVAL) return out;
        Model.TrainerTrackState st = m.trainerGirth;
        Plan.Week[] table = st.level == Plan.L1 ? Plan.GIRTH_INTERVAL_L1
                          : st.level == Plan.L2 ? Plan.GIRTH_INTERVAL_L2 : null;
        if (table == null) {
            out.add(aheadRow(m, track, st, st.weekIndex, st.pressureKpa, null, nowMs, 0, true));
            return out;
        }
        int cur = clampI(Math.max(1, st.weekIndex), 1, table.length);
        int cap = Math.max(1, maxRows);
        double plan = st.pressureKpa;
        AheadRow open = null, last = null;
        for (int ord = cur; ord <= table.length; ord++) {
            Plan.Week w = table[ord - 1];
            long at = nowMs + (ord - cur) * WEEK_MS;
            if (w.deload) {
                if (open != null) { out.add(open); open = null; }
                if (out.size() >= cap) break;
                AheadRow was = last != null ? last
                    : aheadRow(m, track, st, ord, plan, null, at, w.num, ord == cur);
                out.add(new AheadRow(w.num, w.num, true, ord == cur, was.holds, was.holdSec,
                                     was.kpa, was.fatigue));
                if (out.size() >= cap) break;
                continue;
            }
            Plan.Decision d = null;
            if (ord > cur && st.level == Plan.L1) {
                d = l1TableStep(m, ord, plan, monthIndexNow(m, at));
                if (d != null) plan = d.pressureKpa;
            }
            AheadRow row = aheadRow(m, track, st, ord, plan, d, at, w.num, ord == cur);
            last = row;
            if (open != null && open.sameWork(row)) {
                open = new AheadRow(open.from, w.num, false, open.now, open.holds, open.holdSec,
                                    open.kpa, open.fatigue);
                continue;
            }
            if (open != null) { out.add(open); open = null; }
            if (out.size() >= cap) break;
            open = row;
        }
        if (open != null && out.size() < cap) out.add(open);
        return out;
    }

    /** One week's row: the track's prescription at `weekIndex` with `planKpa` as the plan's
     *  figure (and `d`, a step the plan takes that week), as the builder runs it on `atMs`. */
    private static AheadRow aheadRow(Model m, int track, Model.TrainerTrackState st,
                                     int weekIndex, double planKpa, Plan.Decision d, long atMs,
                                     int weekNum, boolean now) {
        Mint.Rx rx = trackRxAt(m, track, st, weekIndex, planKpa, monthIndexNow(m, atMs), d);
        RxBuild.Day day = RxBuild.Day.at(m, atMs);
        Mint.Rx runs = RxBuild.runsAs(m, rx);
        // The hold length the builder lays out: the hybrid's own, else the person's chosen hold
        // where the level gives a range (RxBuild#reshapeToHold) - as RxBuild#build does.
        int holdSec = runs != rx ? runs.holdSec : RxBuild.reshapeToHold(m, rx).holdSec;
        int holds = RxBuild.holdsRun(m, rx, day);
        if (holds <= 0) holds = runs.sets;
        Mint.Rx c = RxBuild.commanded(m, rx, day);
        return new AheadRow(weekNum, weekNum, false, now, holds, holdSec,
                            c == null ? rx.pressureKpa : c.pressureKpa, rx.fatigue);
    }

    /** Level 1's table step for the week at `ord`, from the plan's figure `planKpa`, assuming
     *  the week's sessions hold their planned minutes - Plan#followL1TablePressure itself, so
     *  every condition it keeps (the month's cap, one step at most, never down, the ceiling)
     *  is kept here. Null where it would not step. */
    private static Plan.Decision l1TableStep(Model m, int ord, double planKpa, int month) {
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_GIRTH_INTERVAL;
        in.level = Plan.L1;
        in.weekIndex = ord;
        in.pressureKpa = planKpa;
        in.monthIndex = month;
        in.newToPumping = Scale.isNew(m);   // REAL-14: the month's cap is the person's
        in.ceilKpa = m.ceilKpa;
        in.ownTargetsMetAtPressure = true;
        in.gentleReturnOpen = false;
        Plan.Decision d = Plan.followL1TablePressure(in);
        if (d == null || d.action != Plan.ACTION_RAISE_PRESSURE || Double.isNaN(d.pressureKpa))
            return null;
        return d;
    }

    /**
     * WHAT THE CARD SAYS UNDER ITS ROWS - which of them is an assumption. At Level 1 the
     * pressure ahead follows the table only if the sessions hold; elsewhere on a table it is
     * the pressure the routine runs now, which the plan's own steps move when they come.
     */
    public static String weeksAheadNote(Model m) {
        if (m == null) return "";
        int level = m.trainerGirth.level;
        String own = "Every figure is what your routine is written with that week: your own "
            + "pressure, your Program and your limits.";
        if (level == Plan.L1)
            return own + " The pressure ahead follows the guidance's Level 1 figures, one step a "
                + "week at most, if each week's sessions hold their planned minutes; a week that "
                + "does not keeps the pressure where it is.";
        return own + " The pressure is what your routine runs now; the plan's own pressure "
            + "steps move it when they come.";
    }

    /**
     * A LEVEL WITH NO TABLE (3 and 4): what moves the routine on from the row above, from the
     * plan's own rules only (Plan#evaluate's volume tier, its pressure step and the Level 3
     * gate), with the figures they use - so the card says plainly what changes next instead
     * of printing a table that does not exist.
     */
    public static String noTableNextWords(Model m, long nowMs) {
        if (m == null) return "";
        int level = m.trainerGirth.level;
        int track = Plan.TRACK_GIRTH_INTERVAL;
        int month = monthIndexNow(m, nowMs);
        // G2: up to the person's own maximum when it is above the usual top - the climb.
        int top = Math.min(Scale.climbTopWholeKpa(m, track, level, month),
                           Scale.hardKpa(m, track, month));
        String s = "No week table at Level " + level + ", so the routine stays as above until "
            + "one of the plan's rules moves it. " + Plan.YIELD_TARGET_WORDS + ". Sets: 2 more "
            + "after " + Plan.YIELD_DEBOUNCE
            + " tracked sessions in a row under " + Plan.pct(Plan.yieldTargetLo(level))
            + " yield or after " + Plan.NO_READINGS_WEEKS + " training weeks with nothing "
            + "measured, up to " + Plan.volumeTopSets(track, level) + "; 2 fewer after "
            + Plan.YIELD_DEBOUNCE + " over "
            + Plan.pct(Plan.yieldTargetHi(level)) + ". Pressure: "
            + Model.Fmt.dMag(Plan.STEP_HG_KPA) + " more after "
            + Plan.PRESSURE_RAISE_EVERY_TRAINING_WEEKS + " training weeks at the same pressure "
            + "while each session holds " + Say.fmtMin(Plan.netMilestoneMin(level))
            + " min, up to " + Model.Fmt.p(top) + ".";
        if (level == Plan.L3)
            s += " Level " + Plan.L4 + " is offered from month " + Plan.L3_GATE_MONTH
                + " once each of the last 3 sessions holds "
                + Say.fmtMin(Plan.levelExitNetMin(Plan.L3)) + " min.";
        return s;
    }

    /**
     * THE GIRTH LANE'S CAPTION - the next change in what the routine runs, read from the same
     * rows as "The next weeks" ({@link #weeksAhead}): "7×2min at wk 6", "−6.0 inHg at wk 4",
     * "deload wk 5". It read the guidance's table by a week number, so at Level 3 it said
     * "6 sets at wk 3" - Level 1's row - over a routine of 10×2min. A level with no table says
     * what runs now ("10×2min now"); a table with nothing left to change, "climbing".
     */
    public static String nextChangeShort(Model m, long nowMs) {
        List<AheadRow> rows = weeksAhead(m, Plan.TRACK_GIRTH_INTERVAL, nowMs, 6);
        if (rows.isEmpty()) return "climbing";
        AheadRow here = rows.get(0);
        if (here.from <= 0) return here.holdsText() + " now";
        for (int i = 1; i < rows.size(); i++) {
            AheadRow r = rows.get(i);
            if (r.deload) return "deload wk " + r.from;
            if (r.holds != here.holds || r.holdSec != here.holdSec)
                return r.holdsText() + " at wk " + r.from;
            if (r.kpa != here.kpa) return Model.Fmt.p(r.kpa) + " at wk " + r.from;
        }
        return "climbing";
    }
}
