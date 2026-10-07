package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * THE ROUTINE AS IT RUNS TODAY - a copy, for one run, of a saved routine with the parts a
 * both-tracks day leaves out taken out (0.10, the "same day" group; {@link SameDay} decides).
 *
 * THE SAVED ROUTINE IS NEVER CHANGED. What comes out is a new Routine object carrying the
 * saved one's id, so the run files against the routine the person knows and every count of
 * "what ran today" keeps working - but it is not in the library, nothing saves it, and the
 * next START builds it again from the saved routine as it stands then. A set that has to be
 * shortened is copied into {@link Model#adhoc}, the same transient list the manual run's set
 * lives in, under an id no library set can have ({@link #SET_PREFIX}); nothing persists it.
 *
 * FOUR CHANGES, EACH ONLY EVER A REMOVAL:
 *
 *  - SKIP THE WARM-UP (S04; t10 R-05, P4) - its stage goes, when the other track's session
 *    ended within the half hour (SameDay#WARM_SKIP_WINDOW_MS) - the plan's rule, not a
 *    question any more.
 *  - NO TISSUE TEST (S05) - the routine's assessment is switched off for the run.
 *  - FEWER GIRTH SETS (S06) - whole cycles come out of the girth work SPREAD ACROSS THE
 *    SESSION (the owner, 0.10): from every block, climbing holds and working holds alike, so
 *    the session keeps its shape and is shorter. Never the last cycle, and always at least one
 *    hold at the working pressure.
 *  - EXPANSION ONLY (S08) - the traction blocks and the tube changeover go, and the warm-up
 *    moves to the expansion's cylinder, capped at the expansion's pressure. Only when the
 *    person chose it at START.
 *
 * t10 - THE DAY'S OWN BUILD OF THE PLAN'S ROUTINE (R-05, R-06, R-07). A day of both tracks
 * also asks for things a removal cannot make: girth after length leads in with a ramp-in or
 * ramped first holds in place of its fatigue block, and a warm-up skipped takes its carry ramp
 * with it. For the plan's own routine, exactly as the plan built it, the run is the builder's
 * own build of it for the day (RxBuild#dayRun), copied in here; the person's own routine keeps
 * what they made, and only the removals apply (the warm-up, a day of both tracks' swap and
 * expansion).
 *
 * NOTHING HERE CAN RAISE A PRESSURE. A copied set is shortened or capped, never deepened, and
 * every copy is clamped to the ceiling the way every set is; the check pull is re-capped at
 * the (possibly lower) work peak. RunShapeTest holds all of this over the app's own builders.
 *
 * PURE, like RxBuild: no Android, so the desktop harness builds the same run the phone does.
 */
public final class RunShape {
    private RunShape() { }

    /** The id prefix of a set this class copied for one run. Library ids are "s"-prefixed and
     *  a rest stage's synthetic id is "stage-rest:", so nothing real can collide with it. */
    public static final String SET_PREFIX = "today:";

    /* ------------------------------------------------------------------ the choice */

    /**
     * WHAT TODAY ASKS OF THIS RUN - two of the four are the person's answers at START
     * (skipWarm, expansionOnly), two are the day's own rules (noTissueTest, girthSetsOff).
     * Written as a short code on the session it shaped (Model.Sess#shape) and on the run's
     * snapshot, so a rejoin rebuilds exactly the run that was confirmed.
     */
    public static final class Choice {
        public boolean skipWarm;
        public boolean noTissueTest;
        public int girthSetsOff;
        public boolean expansionOnly;
        /** t10 R-06 (R1) - both tracks run today: a length traction run ends after its strain
         *  holds - no tube swap, no expansion. The day's rule (TrainerTab#dayChoice). */
        public boolean bothTracks;
        /** t10 R-07 (R4) - girth follows a length session filed today: no fatigue block, led
         *  in as the person chose (Model#girthAfterLength). The day's rule. */
        public boolean girthAfterLength;
        /** S08 (the owner's follow-up, 2026-09-26) - skip the hand tunica release of a run
         *  that is expansion only: the person's answer at START, and meaningless without
         *  expansion only, where the release still prepares for the pulls. */
        public boolean skipRelease;

        public boolean any() {
            return skipWarm || noTissueTest || girthSetsOff > 0 || expansionOnly || skipRelease
                || bothTracks || girthAfterLength;
        }

        public Choice copy() {
            Choice c = new Choice();
            c.skipWarm = skipWarm; c.noTissueTest = noTissueTest;
            c.girthSetsOff = girthSetsOff; c.expansionOnly = expansionOnly;
            c.skipRelease = skipRelease;
            c.bothTracks = bothTracks; c.girthAfterLength = girthAfterLength;
            return c;
        }

        /** "w,t,g5,x,r,b,a" - what is asked, in a fixed order. "" when nothing is. */
        public String code() {
            StringBuilder b = new StringBuilder();
            if (skipWarm) tok(b, "w");
            if (noTissueTest) tok(b, "t");
            if (girthSetsOff > 0) tok(b, "g" + girthSetsOff);
            if (expansionOnly) tok(b, "x");
            if (skipRelease) tok(b, "r");
            if (bothTracks) tok(b, "b");
            if (girthAfterLength) tok(b, "a");
            return b.toString();
        }

        /** Reads {@link #code} and {@link Built#code}: a "g5:10.0" token asks for five sets
         *  (the minutes after the colon are the record's, not the choice's). Unknown tokens
         *  are ignored, so a code written by a later version never shapes anything it does
         *  not understand. */
        public static Choice fromCode(String code) {
            Choice c = new Choice();
            if (code == null || code.length() == 0) return c;
            String[] toks = code.split(",");
            for (int i = 0; i < toks.length; i++) {
                String t = toks[i].trim();
                if (t.equals("w")) c.skipWarm = true;
                else if (t.equals("t")) c.noTissueTest = true;
                else if (t.equals("x")) c.expansionOnly = true;
                else if (t.equals("r")) c.skipRelease = true;
                else if (t.equals("b")) c.bothTracks = true;
                else if (t.equals("a")) c.girthAfterLength = true;
                else if (t.startsWith("g") && t.length() > 1) {
                    int colon = t.indexOf(':');
                    String n = colon < 0 ? t.substring(1) : t.substring(1, colon);
                    try { c.girthSetsOff = Math.max(0, Integer.parseInt(n)); }
                    catch (NumberFormatException e) { c.girthSetsOff = 0; }
                }
            }
            return c;
        }
    }

    private static void tok(StringBuilder b, String t) {
        if (b.length() > 0) b.append(',');
        b.append(t);
    }

    /* ------------------------------------------------------------------ the build */

    /** What the build did - which can be less than was asked (a routine with no warm-up has
     *  none to skip; a ramp block is only ever removed whole). */
    public static final class Built {
        public final Model.Routine routine;
        public boolean warmSkipped, testSkipped, expansionOnly, releaseSkipped;
        /** t10 R-06: the swap and the expansion went (a day of both tracks). */
        public boolean codaDropped;
        /** t10 R-07: girth after length - the person's choice (Model#R4_WARM, #R4_SETS,
         *  #R4_NONE) in place of the fatigue block; -1 when it did not apply. */
        public int afterLength = -1;
        /** Work cycles taken off the girth work, and the net minutes they were asked for. */
        public int setsTaken;
        public double minutesTaken;
        /** The minutes of the climbing holds under the counting line among them (0.10): in
         *  no target, so not in {@link #minutesTaken}, but the plan's holds all the same - the
         *  day's length session covers them as it covers the rest (TrainerTab#creditedNet). */
        public double climbMinutesTaken;

        Built(Model.Routine r) { routine = r; }

        public boolean changed() {
            return warmSkipped || testSkipped || expansionOnly || releaseSkipped || setsTaken > 0
                || codaDropped || afterLength >= 0;
        }

        /** The record filed with the session: "w,t,g5:10.0,x,r,b,a". "" for a run as saved. */
        public String code() {
            StringBuilder b = new StringBuilder();
            if (warmSkipped) tok(b, "w");
            if (testSkipped) tok(b, "t");
            if (setsTaken > 0) tok(b, "g" + setsTaken + ":" + Math.round(minutesTaken * 10.0) / 10.0);
            if (expansionOnly) tok(b, "x");
            if (releaseSkipped) tok(b, "r");
            if (setsTaken > 0 && climbMinutesTaken > 0.0)
                tok(b, "u" + Math.round(climbMinutesTaken * 10.0) / 10.0);
            if (codaDropped) tok(b, "b");
            if (afterLength >= 0) tok(b, "a");
            return b.toString();
        }
    }

    /**
     * The net minutes a filed session's shape took off the girth work ("g5:10.0" is 10.0), or
     * 0 - what TrainerTab credits back when the day's length session did run.
     */
    public static double minutesTaken(String code) {
        if (code == null || code.length() == 0) return 0.0;
        String[] toks = code.split(",");
        for (int i = 0; i < toks.length; i++) {
            String t = toks[i].trim();
            int colon = t.indexOf(':');
            if (!t.startsWith("g") || colon < 0) continue;
            try { return Math.max(0.0, Double.parseDouble(t.substring(colon + 1))); }
            catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }

    /**
     * The minutes of climbing holds under the counting line a filed session's shape took off
     * the girth work ("u2.0" is 2.0), or 0 - credited with {@link #minutesTaken}, on the same
     * condition, though they were never in the target (Built#climbMinutesTaken).
     */
    public static double climbMinutesTaken(String code) {
        if (code == null || code.length() == 0) return 0.0;
        String[] toks = code.split(",");
        for (int i = 0; i < toks.length; i++) {
            String t = toks[i].trim();
            if (!t.startsWith("u") || t.length() < 2) continue;
            return Model.clampClimbUnder(Model.parseDoubleOr(t.substring(1), 0.0));
        }
        return 0.0;
    }

    /** Whether a filed session's shape ran without its traction ("x"). */
    public static boolean ranExpansionOnly(String code) {
        return Choice.fromCode(code).expansionOnly;
    }

    /**
     * The run for {@code base} under {@code c}. With nothing to change it is {@code base}
     * itself - the same object, exactly as START has always run it.
     */
    public static Built build(Model m, Model.Routine base, Choice c) {
        if (m == null || base == null) return new Built(base);
        forgetCopies(m);
        if (c == null || !c.any()) return new Built(base);

        /* t10 - THE PLAN'S OWN ROUTINE, BUILT FOR THE DAY (R-05/R-06/R-07): the builder's own
         * build for a day of both tracks, girth after length or no warm-up, copied in here -
         * never deeper than the routine as saved. The person's own routine: null, and only
         * the removals below. */
        RxBuild.DayRun day = (c.skipWarm || c.bothTracks || c.girthAfterLength)
            ? RxBuild.dayRun(m, base, c.bothTracks && !c.expansionOnly, c.girthAfterLength,
                             c.skipWarm)
            : null;
        /* ...NEVER DEEPER THAN THE PLAN'S OWN WORK (parity run 3, OPEN-1). Asked of the saved
         * peak, a P2 carry that stops short of the work (6 holds 28..33 at 34) refused the
         * day's build whose dropped warm-up took the carry with it: the lowered holds stayed,
         * no ramp-in led in and the session never reached its 34. */
        if (day != null && day.scratch.peak(day.routine) > Math.max(m.peak(base),
                day.planPeakKpa)) day = null;
        Model.Routine r = day != null ? runOf(m, base, day) : base.copy(base.id);
        r.name = base.name;
        r.trainerTrack = base.trainerTrack;
        r.trainerLevel = base.trainerLevel;
        r.trainerScaleKpa = base.trainerScaleKpa;
        r.netTargetMin = day != null ? day.routine.netTargetMin : base.netTargetMin;
        r.climbUnderLineMin = base.climbUnderLineMin;
        r.mintedAs = base.mintedAs;
        r.noSaveOffer = base.noSaveOffer;
        r.mark = base.mark;
        r.star = base.star;
        r.runs = base.runs;
        r.done = base.done;
        Built b = new Built(r);
        if (day != null) {
            b.warmSkipped = c.skipWarm && hasWarmUp(base);
            b.codaDropped = c.bothTracks && !c.expansionOnly && hasSwap(base) && !hasSwap(r);
            if (c.girthAfterLength && (hasFatigue(base) != hasFatigue(r) || hasRampIn(r)))
                b.afterLength = m.girthAfterLength;
        }

        // S08 FIRST: which cylinder and pressure the warm-up belongs to depends on it.
        if (c.expansionOnly && Say.isTraction(base)) b.expansionOnly = toExpansionOnly(m, r);
        // ...and the hand release, only when the pulls it prepares for are gone, and only on
        // the person's answer (the owner's follow-up on S08, 2026-09-26).
        if (c.skipRelease && b.expansionOnly) b.releaseSkipped = dropRelease(r);

        if (c.skipWarm && day == null) {
            for (int i = r.stages.size() - 1; i >= 0; i--) {
                if (isWarmUp(r.stages.get(i))) { r.stages.remove(i); b.warmSkipped = true; }
            }
        }
        // R-06 on the person's own traction routine: the swap and the expansion only go.
        if (c.bothTracks && day == null && !c.expansionOnly && Say.isTraction(base))
            b.codaDropped = dropCoda(r);

        if (c.noTissueTest && r.assess != null && r.assess.on) {
            r.assess.on = false;
            b.testSkipped = true;
        }

        if (c.girthSetsOff > 0 && TrainerTab.isGirthTrack(base.trainerTrack))
            trimGirthWork(m, r, c.girthSetsOff, b);

        // With no expansion left there is no girth tube to check in (RxBuild, R-06).
        if (b.codaDropped && r.assess != null) r.assess.on = false;
        // THE CHECK PULL NEVER EXCEEDS THE SESSION - the builders' own rule, asked again of
        // the run as it now is: taking the traction out can lower the work peak.
        int pk = m.workPeakKpa(r);
        if (pk > 0 && r.assess != null) r.assess.kpa = Math.min(r.assess.kpa, pk);

        return b.changed() ? b : new Built(base);
    }

    /** The day's build of `base` (RxBuild#dayRun) as this run: its stages under `base`'s id,
     *  every set it commands copied out of the scratch model into the transient list, clamped
     *  to the ceiling - and the check pull `base` carries, never deeper than before. */
    private static Model.Routine runOf(Model m, Model.Routine base, RxBuild.DayRun day) {
        Model.Routine r = day.routine.copy(base.id);
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st.rest) continue;
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = day.scratch.set(st.setIds.get(j));
                if (s == null) continue;
                Model.Set t = copyForRun(m, s);
                t.clamp(m.ceilKpa);
                st.setIds.set(j, t.id);
            }
        }
        if (base.assess != null && r.assess != null) {
            r.assess.on = base.assess.on && r.assess.on;
            r.assess.kpa = Math.min(base.assess.kpa, r.assess.kpa);
            r.assess.sp = base.assess.sp;
            r.assess.dur = base.assess.dur;
            r.assess.when = base.assess.when;
        }
        return r;
    }

    /** Whether the routine has a tube swap - a traction session's changeover. */
    static boolean hasSwap(Model.Routine r) {
        if (r == null) return false;
        for (int i = 0; i < r.stages.size(); i++)
            if (r.stages.get(i).rest && r.stages.get(i).awaitAck) return true;
        return false;
    }

    /** Whether the routine has a fatigue block that commands (girth's, or the pulls'). */
    static boolean hasFatigue(Model.Routine r) {
        if (r == null) return false;
        for (int i = 0; i < r.stages.size(); i++)
            if (r.stages.get(i).fatigueBlock && !r.stages.get(i).setIds.isEmpty()) return true;
        return false;
    }

    /** Whether the routine leads in with R4's ramp-in (RxBuild#r4RampIn). */
    static boolean hasRampIn(Model.Routine r) {
        if (r == null) return false;
        for (int i = 0; i < r.stages.size(); i++)
            if (isWarmUp(r.stages.get(i)) && "Ramp-in".equals(r.stages.get(i).name)) return true;
        return false;
    }

    /**
     * R-06 ON A ROUTINE THE PERSON SHAPED: a traction session's tube swap, and the expansion
     * after it (and a climb in front of the expansion), go - the pulls and everything before
     * them stay as the person made them. Only ever a removal. False when there is no swap.
     */
    private static boolean dropCoda(Model.Routine r) {
        int swap = -1;
        for (int i = 0; i < r.stages.size(); i++)
            if (r.stages.get(i).rest && r.stages.get(i).awaitAck) { swap = i; break; }
        if (swap < 0) return false;
        for (int i = r.stages.size() - 1; i >= swap; i--) {
            Model.Stage st = r.stages.get(i);
            if (st.traction || st.retention) continue;
            r.stages.remove(i);
        }
        return true;
    }

    /** Drops the sets an earlier build copied - they belong to a run that is not this one. */
    public static void forgetCopies(Model m) {
        if (m == null) return;
        for (int i = m.adhoc.size() - 1; i >= 0; i--) {
            Model.Set s = m.adhoc.get(i);
            if (s != null && s.id != null && s.id.startsWith(SET_PREFIX)) m.adhoc.remove(i);
        }
    }

    /**
     * The hand tunica release, as the length builder writes it: a REST stage done by hand,
     * vented, uncommanded (RxBuild#tractionRoutineFromRx, block 1), with no awaitAck - it
     * waits for Done only once its guide time is up (ByHand#waitsAfterClock). The tube-swap
     * stage is manual too, but waits for its acknowledgement from its start; it is not this.
     */
    public static boolean isRelease(Model.Stage st) {
        return st != null && st.rest && st.manual && !st.awaitAck;
    }

    /** Whether the routine has a hand release the run could leave out. */
    public static boolean hasRelease(Model.Routine r) {
        if (r == null) return false;
        for (int i = 0; i < r.stages.size(); i++) if (isRelease(r.stages.get(i))) return true;
        return false;
    }

    /** Removes the hand release - a vented stage that commands nothing, so taking it out
     *  changes nothing the pump is told, only the minutes before the first pull of air. */
    private static boolean dropRelease(Model.Routine r) {
        boolean gone = false;
        for (int i = r.stages.size() - 1; i >= 0; i--)
            if (isRelease(r.stages.get(i))) { r.stages.remove(i); gone = true; }
        return gone;
    }

    /** A warm-up stage, as every builder writes one: the warm colour, commanding something. */
    public static boolean isWarmUp(Model.Stage st) {
        return st != null && !st.rest && !st.manual && st.colour == Model.STAGE_WARM;
    }

    /** Whether the routine opens with a warm-up the run could skip. */
    public static boolean hasWarmUp(Model.Routine r) {
        if (r == null) return false;
        for (int i = 0; i < r.stages.size(); i++) if (isWarmUp(r.stages.get(i))) return true;
        return false;
    }

    /** The work cycles in a routine's net-counting stages - "sets" as the girth plan counts
     *  them (a block of ten 2-minute intervals is ten). */
    public static int workSets(Model m, Model.Routine r) {
        if (m == null || r == null) return 0;
        int n = 0;
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st.rest || st.outOfNet()) continue;
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = m.set(st.setIds.get(j));
                if (s == null || s.rest) continue;
                n += s.dur / Math.max(1, s.uh + s.lh);
            }
        }
        return n;
    }

    /* ------------------------------------------------------------------ the changes */

    private static boolean toExpansionOnly(Model m, Model.Routine r) {
        int coda = -1;
        for (int i = r.stages.size() - 1; i >= 0; i--) {
            Model.Stage st = r.stages.get(i);
            if (!st.rest && !st.traction && !st.manual && !st.retention
                    && st.colour == Model.STAGE_WORK && !st.setIds.isEmpty()) { coda = i; break; }
        }
        if (coda < 0) return false;             // nothing to run in its place: leave it whole
        Model.Stage codaSt = r.stages.get(coda);
        int codaKpa = 0;
        for (int j = 0; j < codaSt.setIds.size(); j++) {
            Model.Set s = m.set(codaSt.setIds.get(j));
            if (s != null && !s.rest) codaKpa = Math.max(codaKpa, s.ramp ? Math.max(s.up, s.up2) : s.up);
        }
        if (codaKpa <= 0) return false;
        boolean removed = false;
        for (int i = r.stages.size() - 1; i >= 0; i--) {
            Model.Stage st = r.stages.get(i);
            // The pulls, and the stop to swap tubes that only exists to follow them.
            if (st.traction || (st.rest && st.awaitAck)) { r.stages.remove(i); removed = true; }
        }
        if (!removed) return false;
        // THE WARM-UP WARMS UP FOR THE WORK IT PRECEDES, in that work's tube (RxBuild's own
        // rule) - which is now the expansion. It was built for the pull: at a heavy load its
        // eased cycle is the pull less 3 hg, about 30 kPa in front of an expansion near 20,
        // so the hardest thing in the run would be its warm-up. Capped for the expansion.
        int cap = warmCapKpa(codaKpa);
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (!isWarmUp(st)) continue;
            st.cylinderId = codaSt.cylinderId;
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = m.set(st.setIds.get(j));
                if (s == null || s.rest) continue;
                if (peakOf(s) <= cap) continue;
                Model.Set t = copyForRun(m, s);
                t.up = Math.min(t.up, cap);
                t.up2 = Math.min(t.up2, cap);
                if (t.lo >= t.up) t.lo = Math.max(0, t.up - 1);
                if (t.lo2 >= t.up2) t.lo2 = Math.max(0, t.up2 - 1);
                // A repetition of its own is a pull of its own, and is capped the same way.
                for (int k = 0; k < t.reps.size(); k++) {
                    Model.Set.Rep rp = t.reps.get(k);
                    rp.up = Math.min(rp.up, cap);
                    rp.lo = Math.max(0, Math.min(rp.lo, rp.up - 1));
                }
                t.clamp(m.ceilKpa);
                st.setIds.set(j, t.id);
            }
        }
        return true;
    }

    /**
     * How far a warm-up in front of the expansion may pull: 80 % of the expansion's pressure,
     * and never the whole of it. The same 80 % S18 puts on the length warm-up at low loads
     * (RxBuild#warmupStage) - "clearly lighter than the work" - so an expansion under the
     * prime's 4 inHg is not warmed up for by a hold as deep as itself. NOT IN THE MATERIAL:
     * the guides describe manual prep before the device, not a pump prime (the owner's ruling
     * on S18, 2026-09-26, applied here by the 0.10 safety review).
     */
    static int warmCapKpa(int workKpa) {
        return Math.max(1, (int) Math.round(workKpa * WARM_CAP_FRAC));
    }

    /** S18's fraction of the work a length warm-up may reach. */
    static final double WARM_CAP_FRAC = 0.8;

    /** The deepest pull a set commands: both ends of a ramp, and every repetition's own. */
    static int peakOf(Model.Set s) {
        int top = s.ramp ? Math.max(s.up, s.up2) : s.up;
        for (int k = 0; k < s.reps.size(); k++) top = Math.max(top, s.reps.get(k).up);
        return top;
    }

    /**
     * S06 - WHETHER A LENGTH RUN DELIVERED ITS EXPANSION, the work a girth session's dropped
     * sets are credited against (TrainerTab#recentTrackedNets). Every planned cycle of the
     * run's last expansion stage must be in the recording, and none of it skipped: a run
     * stopped in the tunica release, or with its expansion skipped, did none of that work
     * (the 0.10 safety review). Counted from what was recorded, rounded to the whole cycle,
     * against what the run planned for that stage.
     */
    public static boolean expansionDelivered(Model m, Model.Routine run, List<AsRun.Block> blocks) {
        if (m == null || run == null || blocks == null) return false;
        int coda = -1;
        for (int i = run.stages.size() - 1; i >= 0; i--) {
            Model.Stage st = run.stages.get(i);
            if (!st.rest && !st.traction && !st.manual && !st.retention
                    && st.colour == Model.STAGE_WORK && !st.setIds.isEmpty()) { coda = i; break; }
        }
        if (coda < 0) return false;
        int planned = 0;
        List<Model.Preset> plan = m.plan(run);
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p == null || p.rest || p.cyclePart || p.stageIdx != coda) continue;
            planned += Manual.cycles(p.uh, p.lh, (int) (p.durMs / 1000));
        }
        if (planned <= 0) return false;
        int got = 0;
        for (int i = 0; i < blocks.size(); i++) {
            AsRun.Block b = blocks.get(i);
            if (b == null || b.stageIdx != coda) continue;
            if (b.skipped) return false;
            int cyc = b.uh + b.lh;
            if (cyc <= 0 || b.up == b.lo) continue;       // a rest, or a stitch chunk
            got += (int) Math.round(b.spanMs / 1000.0 / cyc);
        }
        return got >= planned;
    }

    /** One hold of the girth work, where it is in the routine and what it pulls. */
    private static final class Hold {
        final int stage, set, pull;
        /** Whether a hold of this set can be taken out on its own: every hold of a plain set -
         *  a working hold, or a climb's single hold. A RAMP IS NOT CUT PART-WAY: taking its
         *  lowest steps would start the copy higher than the saved set starts, and a run's copy
         *  only ever lowers a pressure (WiringCheck invariant 161); taking its top steps would
         *  leave a climb that jumps more than its step to the work. A set with repetitions of
         *  its own is not cut either. */
        final boolean cuttable;
        boolean kept;
        Hold(int stage, int set, int pull, boolean cuttable) {
            this.stage = stage; this.set = set; this.pull = pull; this.cuttable = cuttable;
        }
    }

    /**
     * S06, 0.10 - THE HOLDS A BOTH-TRACKS DAY GIVES TO LENGTH, SPREAD ACROSS THE SESSION (the
     * owner's decision). They used to come off the end, the last block first - which, on a
     * Ramped session, took exactly the block at the working pressure and left a girth session
     * that never commanded the plan's pressure, so the gate's pressure condition failed every
     * both-tracks day and the track stayed at Level 1 all year (Round 2, concern 1).
     *
     * Now `want` holds are taken EVENLY across the work, in order - from climbing holds and
     * working holds alike - so each block keeps its shape and is shorter:
     *   - every block keeps its last hold (the one at its top), and the session always keeps a
     *     hold at its working pressure, so the level gate's pressure condition can pass;
     *   - a climb's single hold (a block after a rest) can go like any other; a climb of
     *     several steps is a ramp, and a ramp is never cut part-way (Hold#cuttable);
     *   - never the last cycle (the caller's `want`).
     * Only when every block's last hold would have to go too is a block emptied - and then its
     * rest goes with it, as before.
     *
     * THE MINUTES TAKEN ARE THE COUNTED ONES: a climbing hold under the line net counts from
     * was never in the target, so taking it neither shortens the target nor earns the credit
     * the dropped minutes get back (TrainerTab#creditedNet) as counted minutes. It leaves the
     * run's own uncounted climb minutes (Routine#climbUnderLineMin) for the record's own "u"
     * token, credited with the rest when the day's length session delivered.
     */
    private static void trimGirthWork(Model m, Model.Routine r, int asked, Built b) {
        int want = Math.min(asked, workSets(m, r) - 1);   // never the last cycle
        if (want <= 0) return;
        List<Hold> holds = new ArrayList<Hold>();
        int peak = 0;
        for (int si = 0; si < r.stages.size(); si++) {
            Model.Stage st = r.stages.get(si);
            if (st.rest || st.outOfNet()) continue;
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = m.set(st.setIds.get(j));
                if (s == null || s.rest) continue;
                if (s.ramp) {
                    List<Model.Preset> lad = s.ladder();
                    for (int i = 0; i < lad.size(); i++) {
                        holds.add(new Hold(si, j, lad.get(i).up, false));
                        peak = Math.max(peak, lad.get(i).up);
                    }
                } else {
                    int n = s.dur / Math.max(1, s.cycle());
                    for (int i = 0; i < n; i++) holds.add(new Hold(si, j, s.up, s.reps.isEmpty()));
                    peak = Math.max(peak, s.up);
                }
            }
        }
        if (holds.isEmpty()) return;
        // The hold at the working pressure the session keeps: its last one there.
        for (int i = holds.size() - 1; i >= 0; i--)
            if (holds.get(i).pull == peak) { holds.get(i).kept = true; break; }
        // Every block keeps its last hold and the top of its climb, while there are enough
        // others to take; then its last hold only; then only the session's working hold.
        List<Hold> free = candidates(holds, 2);
        if (free.size() < want) free = candidates(holds, 1);
        if (free.size() < want) free = candidates(holds, 0);
        if (free.size() < want) want = free.size();
        if (want <= 0) return;
        // EVENLY: the middle of each of `want` equal shares of the holds that may go.
        java.util.Map<String, Integer> take = new java.util.HashMap<String, Integer>();
        double countFrom = countFromKpa(m, r);
        double takenSec = 0.0, underSec = 0.0;
        int taken = 0;
        for (int k = 0; k < want; k++) {
            int idx = (int) Math.floor((k + 0.5) * free.size() / want);
            Hold h = free.get(Math.min(free.size() - 1, idx));
            String key = h.stage + ":" + h.set;
            Integer was = take.get(key);
            take.put(key, Integer.valueOf(was == null ? 1 : was.intValue() + 1));
            Model.Set s = m.set(r.stages.get(h.stage).setIds.get(h.set));
            if (h.pull >= countFrom) takenSec += s.uh;
            else underSec += s.uh;
            taken++;
        }
        // Apply, set by set, from the end so the indexes stay true while sets are removed.
        for (int si = r.stages.size() - 1; si >= 0; si--) {
            Model.Stage st = r.stages.get(si);
            if (st.rest || st.outOfNet()) continue;
            for (int j = st.setIds.size() - 1; j >= 0; j--) {
                Integer k = take.get(si + ":" + j);
                if (k == null || k.intValue() <= 0) continue;
                Model.Set s = m.set(st.setIds.get(j));
                Model.Set t = shortened(m, s, k.intValue());
                if (t == null) st.setIds.remove(j);
                else st.setIds.set(j, t.id);
            }
            if (st.setIds.isEmpty()) {
                r.stages.remove(si);
                // ...and the rest in front of it, which now separates nothing.
                if (si - 1 >= 0 && r.stages.get(si - 1).rest && !r.stages.get(si - 1).manual) {
                    r.stages.remove(si - 1);
                    si--;
                } else if (si < r.stages.size() && r.stages.get(si).rest
                        && !r.stages.get(si).manual
                        && (si - 1 < 0 || r.stages.get(si - 1).colour == Model.STAGE_WARM)) {
                    /* t10 parity run 2, A-2: ...OR, WITH NONE IN FRONT, THE REST BEHIND IT. The
                     * first block follows what leads in directly - the R4 ramp-in, which climbs
                     * to the work (R-07), or a warm-up - so when it went, the rest after it was
                     * left between that lead-in and the next block: a hybrid trimmed to one
                     * hold ran three minutes of nothing before it. A fatigue block keeps its
                     * rest: that one stands in front of the first block and goes with it above. */
                    r.stages.remove(si);
                }
            }
        }
        b.setsTaken = taken;
        b.minutesTaken = takenSec / 60.0;
        b.climbMinutesTaken = underSec / 60.0;
        if (b.setsTaken > 0 && r.netTargetMin > 0.0)
            r.netTargetMin = Math.max(0.0, r.netTargetMin - b.minutesTaken);
        // ...and a climbing hold under the line that went is no longer this run's own
        // (Routine#climbUnderLineMin): it is credited with the rest the day's length session
        // covers ("u" in the record), only when that session delivered (TrainerTab#creditedNet).
        r.climbUnderLineMin = Math.max(0.0, r.climbUnderLineMin - underSec / 60.0);
    }

    /** The holds that may go: cuttable and not the kept one - and with `keep` 1 not the last
     *  hold of its block, with 2 not the top of its block's climb either (the last hold under
     *  the block's working pressure), so a block keeps its shape: a climb, then its work. */
    private static List<Hold> candidates(List<Hold> holds, int keep) {
        List<Hold> out = new ArrayList<Hold>();
        for (int i = 0; i < holds.size(); i++) {
            Hold h = holds.get(i);
            if (!h.cuttable || h.kept) continue;
            boolean lastOfBlock = i + 1 >= holds.size() || holds.get(i + 1).stage != h.stage;
            if (keep >= 1 && lastOfBlock) continue;
            if (keep >= 2 && topOfClimb(holds, i)) continue;
            out.add(h);
        }
        return out;
    }

    /** Whether hold `i` is the top of its block's climb: under the block's highest pull, and
     *  every later hold of the block at that pull. */
    private static boolean topOfClimb(List<Hold> holds, int i) {
        Hold h = holds.get(i);
        int top = 0;
        for (int j = 0; j < holds.size(); j++)
            if (holds.get(j).stage == h.stage) top = Math.max(top, holds.get(j).pull);
        if (h.pull >= top) return false;
        for (int j = i + 1; j < holds.size() && holds.get(j).stage == h.stage; j++)
            if (holds.get(j).pull != top) return false;
        return i + 1 < holds.size() && holds.get(i + 1).stage == h.stage;
    }

    /** Plain set `s` with `k` of its holds taken out, as a copy for this run - or null when
     *  none is left (the whole set goes). Only its length changes. */
    private static Model.Set shortened(Model m, Model.Set s, int k) {
        int cyc = Math.max(1, s.cycle());
        int n = s.dur / cyc;
        if (k >= n) return null;
        Model.Set t = copyForRun(m, s);
        t.dur = (n - k) * cyc;
        t.clamp(m.ceilKpa);
        return t;
    }

    /** The line this routine's net counts from, less its tolerance (Model#scoringFloorKpa on a
     *  day that asks for net: no cut) - a hold under it counts nothing. Minus infinity for a
     *  routine the plan does not score. */
    private static double countFromKpa(Model m, Model.Routine r) {
        if (!TrainerTab.isGirthTrack(r.trainerTrack)) return Double.NEGATIVE_INFINITY;
        int level = r.trainerLevel > 0 ? r.trainerLevel : m.trainerGirth.level;
        double floor = Model.scaledLevelFloorKpa(r, level);
        return floor - m.tupCountTolKpa(floor);
    }

    private static Model.Set copyForRun(Model m, Model.Set s) {
        Model.Set t = s.copy(SET_PREFIX + s.id);
        t.name = s.name;
        t.fromPlan = s.fromPlan;
        m.adhoc.add(t);
        return t;
    }

    /* ------------------------------------------------------------------ what it says */

    /**
     * WHAT THE PRE-RUN BOX SAYS about a run built here - one plain line per change, then that
     * the saved routine is untouched. Empty for a run as saved.
     *
     * @param setsBefore      the girth work's sets before the build (for "12 → 7")
     * @param warmAgoMin      minutes since the other track ended, or -1
     * @param otherTrack      "Length" / "Girth", the track that ran
     */
    public static List<String> lines(Built b, int setsBefore, int warmAgoMin, String otherTrack) {
        List<String> out = new ArrayList<String>();
        if (b == null || !b.changed()) return out;
        if (b.expansionOnly)
            out.add("Expansion only — no traction today, as you chose.");
        if (b.releaseSkipped)
            out.add("No hand release — you chose to skip it before the expansion.");
        if (b.warmSkipped)
            out.add(warmSkippedLine(warmAgoMin, otherTrack));
        if (b.codaDropped)
            // NEW-18 (the owner's words).
            out.add("Ends after the strain holds — no expansion at the end today. Your girth "
                + "session covers it, so you change cylinders once, between the two sessions.");
        if (b.afterLength == Model.R4_WARM)
            out.add("Length ran first: no fatigue block — a short climbing warm-up leads into "
                + "your pressure instead.");
        else if (b.afterLength == Model.R4_SETS)
            out.add("Length ran first: no fatigue block — your first " + Plan.R4_RAMP_SETS
                + " holds climb to your pressure, and count.");
        else if (b.afterLength == Model.R4_NONE)
            out.add("Length ran first: no fatigue block today.");
        if (b.testSkipped)
            out.add("No tissue response test — it runs in the day’s first session only.");
        if (b.setsTaken > 0)
            out.add(b.setsTaken + (b.setsTaken == 1 ? " set" : " sets") + " fewer"
                + (setsBefore > b.setsTaken
                   ? " (" + setsBefore + " → " + (setsBefore - b.setsTaken) + ")" : "")
                + " — the length session’s expansion covers them today.");
        out.add("This run only — the saved routine is unchanged.");
        return out;
    }

    /**
     * t10 R-05 (P4) - THE START LINE FOR A SKIPPED WARM-UP: "No warm-up: your length session
     * ended 10 min ago" (or "your girth session"). `otherTrack` is the track that ran ("Length"
     * / "Girth"); without it, or without the minutes, the plain line.
     */
    public static String warmSkippedLine(int warmAgoMin, String otherTrack) {
        if (warmAgoMin < 0 || otherTrack == null || otherTrack.length() == 0)
            return "No warm-up: the other session ended within the half hour.";
        return "No warm-up: your " + otherTrack.toLowerCase() + " session ended "
            + (warmAgoMin <= 0 ? "just now" : warmAgoMin + " min ago") + ".";
    }

    static String agoWords(int min) {
        if (min <= 0) return "just now";
        return min + (min == 1 ? " minute ago" : " minutes ago");
    }
}
