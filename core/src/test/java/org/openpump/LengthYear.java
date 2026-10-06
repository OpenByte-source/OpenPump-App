package org.openpump;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * A YEAR OF THE LENGTH TRACK, walked the way the app walks it (t10, lane C) - the harness the
 * length-progression tests share. A split week (girth Monday / Wednesday / Friday, length
 * Tuesday / Thursday / Saturday, the editor model's "split" shape), 52 weeks from Monday
 * 5 October 2026, each morning:
 *
 *   - the plan-wide deload, as the first card of the morning: due -> tapped (StartDeloadTap);
 *   - a girth day files a girth session (it counts toward the cadence and the return taper);
 *   - a length day asks the length ladder - the app's own question: LengthTrack#fill and
 *     Plan#evaluate - and takes ONE change a morning the way the person would tap it
 *     (TrainerScreen's cards: LengthTrack#acceptSets / #acceptLoad / #offerAnswered /
 *     #fellAccepted), then files the length session; every `every`-th length session carries
 *     an after-session reading (a before-reading, and an after-reading linked to it) at the
 *     pattern's percentage.
 *
 * It records each week's strain sets, load and length pressure, and the week of every
 * option-D branch, cut, deload and load step, so a test states the year in the owner's terms.
 */
final class LengthYear {

    static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR, WEEK = 7 * DAY;
    static final int WEEKS = 52;
    static final double BORE = 4.5;

    static long t0() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.OCTOBER, 5, 0, 0, 0);   // a Monday
        return c.getTimeInMillis();
    }

    // ---- the profile ----
    int months = 7;
    boolean isNew = false;
    double lengthPlanKpa = Plan.LENGTH_SOFT_CAP_HI_KPA;   // 10 inHg, the owner's usual top
    double lengthOffsetKpa = 0.14;                        // the owner's -10.0 on the wire: 34
    double mostKpa = 41;                                  // the owner's -12 inHg
    double startLoadLb = Double.NaN;                      // NaN: the pull at the setup pressure
    int lengthLoadMode = Model.LENGTH_LOAD_SLOW;

    // ---- the readings pattern ----
    int every = 0;                 // a reading every N length sessions; 0 = none
    double strainPct = 3.0;        // the reading
    double latePct = Double.NaN;   // ...and from training week `lateWeek` of each block
    int lateWeek = 2;
    boolean takeOffer = false;     // D3: "Take a week off", else "Not now"
    boolean wholeWeeks = true;
    boolean debug = false;     // the regular deload taken on the Monday after it is due
    /** A one-off reading override: {week, dow, pct} - the session that day reads pct. */
    final List<double[]> overrides = new ArrayList<double[]>();

    // ---- what the year did ----
    final Model m = new Model();
    final List<Integer> d1Forward = new ArrayList<Integer>(), d1Anyway = new ArrayList<Integer>();
    final List<Integer> d2 = new ArrayList<Integer>(), d3 = new ArrayList<Integer>();
    final List<Integer> deloads = new ArrayList<Integer>();
    final List<Integer> cutWeeks = new ArrayList<Integer>(), remeasures = new ArrayList<Integer>();
    final List<Double> cutTo = new ArrayList<Double>();
    final List<Integer> loadWeeks = new ArrayList<Integer>();
    final List<Double> loadTo = new ArrayList<Double>();
    final List<String> events = new ArrayList<String>();
    final int[] sets = new int[WEEKS + 1];
    final double[] load = new double[WEEKS + 1];
    final double[] kpa = new double[WEEKS + 1];
    boolean past12Said;
    private int lengthRuns, readings;
    private long lastD1Block = -1L;

    LengthYear setup() {
        long t0 = t0();
        long now = t0 + 7 * HOUR;
        m.ceilKpa = 43;
        m.rxNewToPumping = isNew;
        m.trainerMonthsPumping = months;
        m.trainerMonthsAt = now;
        m.rxLengthMaxKpa = mostKpa;
        m.rxDropKpa = 3;
        m.clampAll();
        m.trainerGirthOn = true;
        m.trainerLengthOn = true;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = now;
        m.lengthLoadMode = lengthLoadMode;
        Model.Cylinder lt = new Model.Cylinder();
        lt.id = "L"; lt.label = "Length cylinder"; lt.role = Model.Cylinder.ROLE_LENGTH;
        lt.boreCm = BORE; lt.lengthCm = 23.0;
        Model.Cylinder gt = new Model.Cylinder();
        gt.id = "G"; gt.label = "Girth cylinder"; gt.boreCm = 5.0; gt.lengthCm = 23.0;
        m.cylinders.add(gt);
        m.cylinders.add(lt);
        Model.TrainerTrackState l = m.trainerLength;
        int month = TrainerTab.monthIndexNow(m, now);
        l.level = month >= 12 ? Plan.L4 : month >= 6 ? Plan.L3 : month >= 3 ? Plan.L2 : Plan.L1;
        l.setWorkingPressure(lengthPlanKpa, now);
        l.offsetKpa = lengthOffsetKpa;
        l.restartPressureClock(now);
        double lb = !Double.isNaN(startLoadLb) ? startLoadLb
            : Traction.loadLbAtBore(Math.round(lengthPlanKpa + lengthOffsetKpa), BORE);
        l.setLoadLb(lb, now);
        LengthTrack.atSetup(m, now);   // CommitOnboardTap
        blockFrom = now;
        routine("PG", Plan.TRACK_GIRTH_INTERVAL, false);
        routine("PL", Plan.TRACK_LENGTH, true);
        return this;
    }

    private void routine(String id, int track, boolean traction) {
        Model.Routine r = new Model.Routine();
        r.id = id; r.name = id; r.trainerTrack = track;
        Model.Stage s = new Model.Stage();
        s.traction = traction;
        r.stages.add(s);
        m.routines.add(r);
    }

    private static int dowOf(long now, long t0) {
        return (int) (((now - t0) % WEEK) / DAY);
    }

    /** Where the block `now` is in began: enrolment, then the end of each deload taken. */
    private long blockFrom;

    /** The training week of the block `now` is in - 1 for the first week after a deload. */
    private int blockWeek(long now) {
        // (+6 h: a week off that ends at 23:00 on the Sunday a clock change made of a
        // Monday midnight still hands over to that Monday.)
        long from = TrainerTab.mondayStartMs(blockFrom + 6 * HOUR);
        return (int) Math.round((TrainerTab.mondayStartMs(now) - from) / (double) WEEK) + 1;
    }

    private void tapDeload(long tap, int week) {
        long gap = Deload.askGapStartMs(m, tap);
        long last = TrainerTab.lastAnyPlanSessionMs(m);
        Deload.remember(m, tap, tap + Plan.LAYOFF_MS);
        m.trainerFirstDeloadTaken = true;
        Deload.refund(m.trainerGirth, tap, tap + Plan.LAYOFF_MS);
        m.returnAnchorMs = last;
        m.deloadAskAnchorMs = gap > 0L ? gap : last;
        Deload.arm(m, tap + Plan.LAYOFF_MS);
        deloads.add(Integer.valueOf(week));
        events.add("w" + week + " deload");
    }

    /** When a week off the length card brings forward starts: tomorrow, as the card says - or,
     *  with `wholeWeeks`, the next Monday, as the editor model's whole weeks have it. */
    private long weekOffFrom(long now) {
        if (!wholeWeeks) return Deload.dayStartPlus(now, 1);
        return Deload.dayStartPlus(TrainerTab.mondayStartMs(now), 7);   // a local Monday
    }

    Plan.Inputs inputs(long now) { return inputsFor(m, now); }

    /** SessionActivity#buildTrackInputs for the length track of `m` at `now`, the cylinder
     *  the 4.5 cm length tube. */
    static Plan.Inputs inputsFor(Model m, long now) {
        Model.TrainerTrackState st = m.trainerLength;
        int month = TrainerTab.monthIndexNow(m, now);
        Plan.Inputs in = new Plan.Inputs();
        in.track = Plan.TRACK_LENGTH;
        in.level = st.level;
        in.monthIndex = month;
        in.pressureKpa = st.pressureKpa;
        in.ceilKpa = m.ceilKpa;
        in.layoff = TrainerTab.layoff(m, Plan.TRACK_GIRTH_INTERVAL, now);
        in.inDeloadWeek = TrainerTab.inDeloadWeek(m, now);
        in.accumulatedTrainingWeeks = TrainerTab.planTrainingWeeks(m,
            TrainerTab.deloadAnchorMs(m), now);
        in.firstDeloadPending = !m.trainerFirstDeloadTaken;
        in.returnRunsUnder = TrainerTab.returnRunsUnder(m, now);
        // SessionActivity#buildTrackInputs's length block.
        LengthTrack.focusEnded(st, now);
        LengthTrack.startSlowClock(m, now);
        LengthTrack.fill(m, st, month, now, in);
        in.girthCm = 12.7;
        in.fitState = Traction.FIT_TRACTION;
        in.lengthTube = true;
        in.boreCm = BORE;
        return in;
    }

    LengthYear run() {
        long t0 = t0();
        Model.TrainerTrackState l = m.trainerLength;
        for (int week = 1; week <= WEEKS; week++) {
            for (int dow = 0; dow < 6; dow++) {
                long now = t0 + (week - 1) * WEEK + dow * DAY + 8 * HOUR;
                if (focusFromMs > 0L && now >= focusFromMs && l.focusBlockUntilMs == 0L)
                    l.focusBlockUntilMs = focusFromMs + Plan.GIRTH_FOCUS_WEEKS * WEEK;
                if (TrainerTab.inDeloadWeek(m, now)) { blockFrom = Deload.endMs(m); continue; }
                Deload.settle(m, Summary.dayNumber(now));
                int month = TrainerTab.monthIndexNow(m, now);
                // THE PLAN-WIDE DELOAD - the first card of the morning, on either track. Taken
                // at the start of the week after it comes due (`wholeWeeks`), as the editor
                // model's whole weeks take it: the app offers it the morning the fourth week
                // qualifies, which on a six-day split week is its Thursday, and "Not now"
                // until Monday is the person's to say.
                if ((dow == 0 || !wholeWeeks) && Plan.deloadDue(
                        TrainerTab.planTrainingWeeks(m, TrainerTab.deloadAnchorMs(m), now),
                        !m.trainerFirstDeloadTaken, month)) {
                    tapDeload(now - 30 * MIN, week);
                    continue;
                }
                long run = now + HOUR;
                if (dow % 2 == 0) {                       // girth: Mon / Wed / Fri
                    file("PG", run, false, Double.NaN);
                    Deload.onFiled(m, run, Plan.TRACK_GIRTH_INTERVAL, false, true);
                    continue;
                }
                morning(week, dow, now);
                if (TrainerTab.inDeloadWeek(m, now)) continue;   // a week off taken now
                lengthRuns++;
                double pct = Double.NaN;
                if (every > 0 && lengthRuns % every == 0) {
                    pct = !Double.isNaN(latePct) && blockWeek(now) >= lateWeek
                        ? latePct : strainPct;
                    for (int i = 0; i < overrides.size(); i++) {
                        double[] o = overrides.get(i);
                        if ((int) o[0] == week && (int) o[1] == dow) pct = o[2];
                    }
                }
                file("PL", run, true, pct);
                Deload.onFiled(m, run, Plan.TRACK_LENGTH, false, true);
            }
            sets[week] = l.strainSets;
            load[week] = l.loadLb;
            kpa[week] = l.pressureKpa + l.offsetKpa;
        }
        return this;
    }

    /** The length morning: the ladder asked, one change taken. */
    private void morning(int week, int dow, long now) {
        Model.TrainerTrackState l = m.trainerLength;
        for (int guard = 0; guard < 3; guard++) {
            Plan.Inputs in = inputs(now);
            Plan.Decision d = Plan.evaluate(in);
            if (debug) events.add("  w" + week + "/" + dow + " " + d.action + " " + d.rule
                + " strain=" + in.strainPct + " miss=" + in.strainMissDays + " reach="
                + in.blockHadReachLo + " added=" + in.dAddedInBlock + " off=" + in.dOfferedInBlock
                + " ret=" + in.returnRunsUnder + " acc=" + in.accumulatedTrainingWeeks);
            if (d.action == Plan.ACTION_LEVEL_UP && l.level < Plan.L4) {
                l.level++;                         // LevelUpTap's length branch
                events.add("w" + week + " length L" + l.level);
                continue;                          // the level is not the morning's change
            }
            if (d.action == Plan.ACTION_DELOAD && Plan.LENGTH_FELL_RULE.equals(d.rule)) {
                long block = Deload.endMs(m);
                if (block != lastD1Block) {
                    lastD1Block = block;
                    (in.deloadNextAnyway ? d1Anyway : d1Forward).add(Integer.valueOf(week));
                    events.add("w" + week + " D1 fell" + (in.deloadNextAnyway ? " (next anyway)"
                        : " (week off from tomorrow)"));
                }
                if (!in.deloadNextAnyway) {
                    tapDeload(weekOffFrom(now), week);
                    LengthTrack.fellAccepted(m, now);
                }
                return;
            }
            if (d.action == Plan.ACTION_OFFER_BREAK) {
                d3.add(Integer.valueOf(week));
                events.add("w" + week + " D3 offer" + (takeOffer ? " (week off)" : " (not now)"));
                LengthTrack.offerAnswered(m, now);
                if (takeOffer) tapDeload(weekOffFrom(now), week);
                return;
            }
            if (d.action == Plan.ACTION_ADD_VOLUME) {
                int to = Math.min(Plan.LENGTH_STRAIN_SETS_MAX,
                                  l.strainSets + Math.max(1, d.setsDelta));
                if (Plan.LENGTH_NEVER_RULE.equals(d.rule)) d2.add(Integer.valueOf(week));
                events.add("w" + week + " strain " + l.strainSets + "->" + to + " (" + d.rule + ")");
                LengthTrack.acceptSets(m, d.rule, to, now);
                return;
            }
            if (d.action == Plan.ACTION_RAISE_LOAD) {
                if (LengthTrack.past12Due(m, d.loadLb, now)) past12Said = true;
                loadWeeks.add(Integer.valueOf(week));
                loadTo.add(Double.valueOf(d.loadLb));
                events.add("w" + week + " load " + Traction.settingLb(l.loadLb) + "->"
                    + Traction.settingLb(d.loadLb) + " (" + d.rule + ")");
                LengthTrack.acceptLoad(m, d.rule, d.loadLb, d.pressureKpa, now);
                return;
            }
            if (d.action == Plan.ACTION_RAISE_PRESSURE) {
                l.setWorkingPressure(d.pressureKpa, now);
                events.add("w" + week + " length pressure " + Math.round(d.pressureKpa));
                return;
            }
            if (d.action == Plan.ACTION_REMEASURE) {
                if (Plan.LENGTH_CUT_RULE.equals(d.rule) && !Double.isNaN(d.loadLb)) {
                    cutWeeks.add(Integer.valueOf(week));
                    cutTo.add(Double.valueOf(d.loadLb));
                    events.add("w" + week + " cut to " + Traction.settingLb(d.loadLb));
                    LengthTrack.acceptLoad(m, d.rule, d.loadLb, d.pressureKpa, now);
                } else {
                    remeasures.add(Integer.valueOf(week));
                }
                return;
            }
            return;                                    // HOLD
        }
    }

    private void file(String rid, long ts, boolean length, double pct) {
        Model.Sess s = new Model.Sess();
        s.id = "s" + m.sessLog.all.size();
        s.routineId = rid;
        s.ts = ts;
        s.durSec = 45 * 60;
        s.completed = true;
        s.returnDay = Deload.reducedOn(m, ts);   // t10 O8: stamped at filing, as the app does
        if (length && !Double.isNaN(pct)) {
            readings++;
            Model.Reading pre = new Model.Reading();
            pre.id = "pre" + readings; pre.ts = ts - 5 * MIN; pre.method = LengthTrack.METHOD;
            pre.phase = Model.Reading.PHASE_PRE; pre.len = 16.0;
            Model.Reading post = new Model.Reading();
            post.id = "post" + readings; post.ts = ts + 50 * MIN; post.method = LengthTrack.METHOD;
            post.phase = Model.Reading.PHASE_POST; post.len = 16.0 * (1.0 + pct / 100.0);
            post.pairOf = pre.id;
            m.measLog.all.add(pre);
            m.measLog.all.add(post);
            s.afterBaseThisSession = true;
            s.afterBaseId = pre.id;
        }
        m.sessLog.file(s);
    }

    /** A focus block that starts on the first morning at or after this instant (0: none). */
    long focusFromMs;

    /**
     * ONE MEASURED LENGTH SESSION on `m` at `ts` (the routine `rid`), its after-reading
     * `pct` over its own before-reading, filed as the app files them - for the unit tests.
     * The session is returned so a test can make it one the ladder does not read.
     */
    static Model.Sess measured(Model m, long ts, String rid, double pct) {
        Model.Reading pre = new Model.Reading();
        pre.id = "pre" + ts; pre.ts = ts - 5 * MIN; pre.method = LengthTrack.METHOD;
        pre.phase = Model.Reading.PHASE_PRE; pre.len = 16.0;
        Model.Reading post = new Model.Reading();
        post.id = "post" + ts; post.ts = ts + 50 * MIN; post.method = LengthTrack.METHOD;
        post.phase = Model.Reading.PHASE_POST; post.len = 16.0 * (1.0 + pct / 100.0);
        post.pairOf = pre.id;
        m.measLog.all.add(pre);
        m.measLog.all.add(post);
        Model.Sess s = new Model.Sess();
        s.id = "s" + ts;
        s.routineId = rid;
        s.ts = ts;
        s.durSec = 45 * 60;
        s.completed = true;
        s.afterBaseThisSession = true;
        s.afterBaseId = pre.id;
        m.sessLog.file(s);
        return s;
    }

    /** The year's events, one per line - for a failing assertion's message. */
    String trace() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < events.size(); i++) b.append(events.get(i)).append('\n');
        return b.toString();
    }
}
