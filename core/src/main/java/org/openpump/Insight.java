package org.openpump;

import java.util.List;

/**
 * The derivations the redesigned Today, Progress and Library screens draw pictures from —
 * kept PURE (no `import android`) so test.sh compiles them and SelfTest asserts on them.
 * Each is a small piece of arithmetic that a Canvas or a row of Views then renders, and
 * each is the kind of thing that is silently wrong for months if it lives inside an
 * onDraw no harness can run:
 *
 *   1. STAGE RAIL PROPORTIONS — the segment widths on the routine card and the run screen.
 *      A rail whose segments are equal-width is not a rail, it is a decoration; the whole
 *      claim it makes is "this is how the routine's time is shared out".
 *
 *   2. EXPANSION PER SESSION — after minus before, per session, and which one was the best.
 *      This is a body measurement and it is the only number on Progress the user cannot
 *      get anywhere else, so it must never be invented: a session with no after-reading
 *      contributes NOTHING rather than a zero.
 *
 *   3. THE CONSISTENCY GRID — one cell per day of a month, each carrying what that day
 *      WAS: trained, a rest day, a missed training day, not a training day, today, or
 *      still to come. A grid that painted an unscheduled day as a miss would be accusing
 *      the user of failing to do something they never planned to do.
 *
 *   4. THE DOSE HEATMAP (Stage D task 3, S7 A) — the SAME month grid, one cell per day,
 *      but painted by how much was delivered that day rather than by schedule compliance:
 *      a day's FILL is {@link #doseTier}, a magnitude read against the heaviest day the
 *      log has ever recorded; a day's OUTLINE, drawn by the screen from #3's own
 *      {@link #monthRoles}, is a separate fact about the SCHEDULE. The two are allowed to
 *      disagree — a stopped-early attempt still delivers real dose on a day #3 calls
 *      MISSED — because they are answering two different questions about the same day.
 *
 *   5. THE INSIGHTS CARD (Stage D task 3, S8 A) — up to three plain-language lines, each
 *      gated on its own named minimum-sample floor so a card that cannot say something
 *      honestly says nothing instead of guessing from two data points. Recomputed fresh
 *      on every render (no persisted "as of" state): everything it reads is already an
 *      in-memory list, so there is nothing a background job would buy that asking again
 *      does not already give for free.
 */
public final class Insight {
    private Insight() { }

    /* ============================================== 1. STAGE RAIL PROPORTIONS ==== */

    /**
     * The rail's segment weights: each stage's share of the routine's total time, summing
     * to 1. A stage with no duration still gets MIN_SHARE so it is visible — a routine
     * with an empty stage should show that the stage is there, not silently omit it — and
     * the rest are scaled into what is left so the total is still exactly 1.
     *
     * An empty or all-zero input divides the width equally, which is the only honest
     * picture of "these exist and nothing is known about their lengths".
     */
    public static final float MIN_SHARE = 0.04f;

    public static float[] railShares(long[] stageDurMs) {
        int n = (stageDurMs == null) ? 0 : stageDurMs.length;
        if (n == 0) return new float[0];
        float[] out = new float[n];
        long total = 0;
        int zeros = 0;
        for (int i = 0; i < n; i++) {
            long d = stageDurMs[i] > 0 ? stageDurMs[i] : 0;
            total += d;
            if (d <= 0) zeros++;
        }
        if (total <= 0) {
            for (int i = 0; i < n; i++) out[i] = 1f / n;
            return out;
        }
        float reserved = MIN_SHARE * zeros;
        if (reserved > 0.9f) reserved = 0.9f;               // never starve the real stages
        float rest = 1f - reserved;
        for (int i = 0; i < n; i++) {
            long d = stageDurMs[i] > 0 ? stageDurMs[i] : 0;
            out[i] = (d <= 0) ? (zeros > 0 ? reserved / zeros : 0f)
                              : rest * (float) (d / (double) total);
        }
        return out;
    }

    /* ---- which colour a stage's rail segment wears --------------------------- */

    /** Rail roles. The colours themselves live in Look; this decides which ROLE a stage
     *  plays, so the mapping is asserted rather than being a chain of string compares
     *  buried in a view builder. */
    public static final int ROLE_WARM = 0;   // lime  — getting ready
    public static final int ROLE_WORK = 1;   // AMBER — the working pressure
    public static final int ROLE_COOL = 2;   // green — coming down

    /**
     * A stage's rail role, from its name and its position. The NAME decides it when it
     * says so — "warm-up", "cool down" — because that is the user's own statement of what
     * the stage is for; otherwise position does, with the first stage warming and the last
     * cooling only when there are at least three, since a two-stage routine is work with a
     * warm-up rather than work with nothing in the middle.
     *
     * AMBER IS THE DEFAULT, and deliberately: an unlabelled stage in the middle of a
     * routine is the working pressure, and the rail's amber must not be reserved for
     * stages whose names happen to match a keyword.
     */
    public static int railRole(String stageName, int index, int count) {
        String n = stageName == null ? "" : stageName.toLowerCase(java.util.Locale.US);
        if (n.contains("warm")) return ROLE_WARM;
        if (n.contains("cool") || n.contains("wind down") || n.contains("down")) return ROLE_COOL;
        if (count >= 2 && index == 0) return ROLE_WARM;
        if (count >= 3 && index == count - 1) return ROLE_COOL;
        return ROLE_WORK;
    }

    /* ============================================== 2. EXPANSION PER SESSION ==== */

    /**
     * One session's expansion, or null when it has none. after MINUS before is what the
     * session recorded in afterLenCm — a NUMBER, not a formatted string, so the caller can
     * colour it by its own sign.
     *
     * A session with no after-reading returns null and is DROPPED from the chart rather
     * than plotted as zero. Zero is a measured result — "the session changed nothing" —
     * and a missing measurement is not that; a bar chart that drew them the same would
     * report unmeasured sessions as failures.
     */
    public static Double expansionCm(Model.Sess s) {
        if (s == null) return null;
        return s.afterLenCm;
    }

    /** Every session that HAS an expansion, oldest first, as a plain array for the bars. */
    public static double[] expansionSeries(List<Model.Sess> newestFirst) {
        int n = 0;
        if (newestFirst != null)
            for (int i = 0; i < newestFirst.size(); i++)
                if (expansionCm(newestFirst.get(i)) != null) n++;
        double[] out = new double[n];
        int k = n - 1;                                   // fill backwards: input is newest first
        if (newestFirst != null)
            for (int i = 0; i < newestFirst.size() && k >= 0; i++) {
                Double d = expansionCm(newestFirst.get(i));
                if (d != null) out[k--] = d.doubleValue();
            }
        return out;
    }

    /** The index of the BEST expansion in a series, or -1 for an empty one. Ties go to the
     *  EARLIEST, so a repeated best does not keep moving the highlight forward as more
     *  sessions match it. */
    public static int bestIndex(double[] series) {
        if (series == null || series.length == 0) return -1;
        int best = 0;
        for (int i = 1; i < series.length; i++)
            if (series[i] > series[best]) best = i;
        return best;
    }

    /** The bar height for a value, 0..1, against the largest MAGNITUDE in the series — so
     *  a negative session (it happens) is drawn as far below the line as an equal positive
     *  is above it. A series of all zeros draws nothing rather than dividing by zero. */
    public static float barFraction(double value, double maxAbs) {
        if (maxAbs <= 0) return 0f;
        double f = value / maxAbs;
        if (f > 1) f = 1;
        if (f < -1) f = -1;
        return (float) f;
    }

    public static double maxAbs(double[] series) {
        double m = 0;
        if (series != null)
            for (int i = 0; i < series.length; i++)
                if (Math.abs(series[i]) > m) m = Math.abs(series[i]);
        return m;
    }

    /* ============================================== 3. THE CONSISTENCY GRID ===== */

    /** What one day of the month WAS. */
    public static final int DAY_FUTURE   = 0;   // not yet — drawn as nothing
    public static final int DAY_OFF      = 1;   // never a training day; struck through
    public static final int DAY_DONE     = 2;   // trained; lime
    public static final int DAY_MISSED   = 3;   // a training day with nothing on it; red
    public static final int DAY_REST     = 4;   // a training day the rest day is protecting

    /**
     * The role of each day in a month, oldest first.
     *
     * `dayNumbers` are Summary#dayNumber values for the days to draw, `doneDays` the day
     * numbers that have a completed session, `today` the current day number.
     *
     * THE RULE THAT MATTERS: a day the schedule never claimed is DAY_OFF, not DAY_MISSED.
     * A Mon/Wed/Fri user has four non-training days a week, and a grid that painted them
     * red would report a perfect month as a disaster. A day in the FUTURE is likewise not
     * a miss — nothing can be missed that has not happened — and TODAY is only a miss once
     * it is over, so today with nothing on it is drawn as a training day still open.
     */
    public static int[] monthRoles(long[] dayNumbers, java.util.Set<Long> doneDays,
                                   Schedule sched, long today, long restProtectedDay) {
        int n = (dayNumbers == null) ? 0 : dayNumbers.length;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            long d = dayNumbers[i];
            boolean done = doneDays != null && doneDays.contains(Long.valueOf(d));
            boolean trains = sched == null || sched.trainsOnDay(d);
            if (done) { out[i] = DAY_DONE; continue; }
            if (d > today) { out[i] = DAY_FUTURE; continue; }
            if (!trains) { out[i] = DAY_OFF; continue; }
            if (d == restProtectedDay) { out[i] = DAY_REST; continue; }
            // Today is a training day that is still OPEN, not a day that was missed.
            out[i] = (d == today) ? DAY_FUTURE : DAY_MISSED;
        }
        return out;
    }

    /**
     * STAGE H TASK 5 — the deload-widened counterpart. Every role is computed exactly as
     * above, then any day that landed {@link #DAY_MISSED} AND falls within
     * [{@code deloadStartDay}, {@code deloadEndDay}) (from {@link TrainerTab#deloadDayRange})
     * is reclassified {@link #DAY_REST} — the plan's own ruling that a deload week must never
     * read as missed. A day that is DONE stays DONE (a session logged during deload still logs
     * normally, per the plan's misuse rules); OFF/FUTURE/the single protected day are
     * untouched. An empty/inverted range ({@code deloadStartDay >= deloadEndDay}) changes
     * nothing, so the 5-arg overload above (and every existing caller) is unaffected.
     */
    public static int[] monthRoles(long[] dayNumbers, java.util.Set<Long> doneDays,
                                   Schedule sched, long today, long restProtectedDay,
                                   long deloadStartDay, long deloadEndDay) {
        int[] out = monthRoles(dayNumbers, doneDays, sched, today, restProtectedDay);
        if (deloadStartDay < deloadEndDay) {
            for (int i = 0; i < out.length; i++) {
                long d = dayNumbers[i];
                if (out[i] == DAY_MISSED && d >= deloadStartDay && d < deloadEndDay)
                    out[i] = DAY_REST;
            }
        }
        return out;
    }

    /**
     * POLISH #3 (wave-4 sweep): a day BEFORE the plan existed on this phone cannot have
     * been missed. The plan's week position backdates the schedule (a fresh install at
     * week 12 "schedules" days from before the app was set up), so a fresh install
     * opened on two weeks of red. Pre-`sinceDay` MISSED days are reclassified OFF;
     * sinceDay 0 (never enrolled) clamps nothing. Applied AFTER roles are computed so
     * every other rule stays exactly what it was.
     */
    public static int[] clampRolesSince(int[] roles, long[] dayNumbers, long sinceDay) {
        if (roles != null && dayNumbers != null && sinceDay > 0)
            for (int i = 0; i < roles.length && i < dayNumbers.length; i++)
                if (roles[i] == DAY_MISSED && dayNumbers[i] < sinceDay) roles[i] = DAY_OFF;
        return roles;
    }

    /** How many of the drawn days were scheduled and how many of those were done —
     *  the grid's caption, counted from the same roles the cells are painted from so the
     *  words and the picture cannot disagree. */
    public static int countRole(int[] roles, int role) {
        int n = 0;
        if (roles != null)
            for (int i = 0; i < roles.length; i++) if (roles[i] == role) n++;
        return n;
    }

    /* ============================================== 4. THE DOSE HEATMAP (S7 A) ==== */

    /**
     * A day cell's dose tier — how heavy that day reads, relative to the heaviest single
     * day this app has EVER recorded ({@link #maxEverDailyDoseKpaS}), the same "fraction
     * of the largest magnitude" idiom {@link #maxAbs}/{@link #barFraction} already use for
     * the expansion chart above rather than an absolute kPa·s cutoff nobody could justify
     * across different users' own pressure ceilings. Read against the WHOLE log, not just
     * the visible month, so shading stays comparable across months: grading a quiet
     * month's heaviest day against only itself would call it "HIGH" even next to a busier
     * month it does not come close to.
     */
    public static final int DOSE_NONE = 0;   // nothing filed that day
    public static final int DOSE_LOW  = 1;
    public static final int DOSE_MED  = 2;
    public static final int DOSE_HIGH = 3;

    /**
     * One day's total dose — {@link Model.Sess#doseKpaS} summed across EVERY session filed
     * on it, manual runs and stopped attempts included: this is what was physically
     * delivered that day, a fact about the body, never a routine-completion statistic.
     * {@link #monthRoles}'s DAY_DONE/DAY_MISSED — driven off `.completed` — answers THAT
     * question separately and colours a day's OUTLINE, never its fill; the two may
     * disagree (see this file's own class doc) and that is correct, not a bug. A session
     * with no real reading contributes 0 (`doseKpaS` is already 0 for one), which is
     * right: nothing measurable was delivered.
     */
    public static double dayDoseKpaS(List<Model.Sess> log, long dayNumber) {
        double sum = 0;
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s == null || Summary.dayNumber(s.ts) != dayNumber) continue;
            sum += Math.max(0.0, s.doseKpaS);
        }
        return sum;
    }

    /** Every day in `dayNumbers`, its own dose, in the same order — one scan of the log
     *  per grid rather than one per cell. */
    public static double[] dailyDoses(long[] dayNumbers, List<Model.Sess> log) {
        int n = dayNumbers == null ? 0 : dayNumbers.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = dayDoseKpaS(log, dayNumbers[i]);
        return out;
    }

    /** The heaviest single day's dose ANYWHERE in the log — the denominator every cell's
     *  {@link #doseTier} is read against. 0 when nothing has ever been filed. */
    public static double maxEverDailyDoseKpaS(List<Model.Sess> log) {
        java.util.Map<Long, Double> byDay = new java.util.HashMap<Long, Double>();
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s == null) continue;
            Long d = Long.valueOf(Summary.dayNumber(s.ts));
            Double prior = byDay.get(d);
            byDay.put(d, Double.valueOf((prior == null ? 0.0 : prior.doubleValue())
                                         + Math.max(0.0, s.doseKpaS)));
        }
        double max = 0;
        java.util.Iterator<Double> it = byDay.values().iterator();
        while (it.hasNext()) {
            double v = it.next().doubleValue();
            if (v > max) max = v;
        }
        return max;
    }

    /** The tier `dayDoseKpaS` earns against `maxDoseKpaS`. Thirds of the max — there is no
     *  locked mock shade count to match (S7 A's own decision-list entry names no shade
     *  scheme), so this is the plainest defensible split, an even three-way share of
     *  "the heaviest day this app has ever recorded". The boundary is INCLUSIVE downward
     *  (`<=`), so a day sitting exactly on a third or exactly on two-thirds stays in the
     *  lower tier rather than tipping into the next one on a rounding coin flip. */
    public static int doseTier(double dayDoseKpaS, double maxDoseKpaS) {
        if (dayDoseKpaS <= 0 || maxDoseKpaS <= 0) return DOSE_NONE;
        double f = dayDoseKpaS / maxDoseKpaS;
        if (f > 1) f = 1;
        if (f <= 1.0 / 3.0) return DOSE_LOW;
        if (f <= 2.0 / 3.0) return DOSE_MED;
        return DOSE_HIGH;
    }

    /** Every day's tier, in one call — {@link #dailyDoses} then {@link #doseTier} against
     *  the log's own all-time max, so the caller (and every cell) reads the same
     *  denominator rather than each recomputing it. */
    public static int[] doseTiers(long[] dayNumbers, List<Model.Sess> log) {
        double max = maxEverDailyDoseKpaS(log);
        double[] doses = dailyDoses(dayNumbers, log);
        int[] out = new int[doses.length];
        for (int i = 0; i < doses.length; i++) out[i] = doseTier(doses[i], max);
        return out;
    }

    /**
     * The session a tap on a dose-heatmap day opens — the session sitting FIRST in `log`
     * for that day, which is the NEWEST one filed on it, exactly the "newest wins" rule
     * {@link PhotoCalendar#cells} documents at length for the identical one-cell/many-
     * candidates situation. `log` is {@link Model.SessLog#all}'s own newest-first order,
     * so the first match already is the answer — no re-sort needed. Null when nothing was
     * filed that day, which is nothing for a tap to open.
     */
    public static Model.Sess sessionOnDay(List<Model.Sess> log, long dayNumber) {
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s != null && Summary.dayNumber(s.ts) == dayNumber) return s;
        }
        return null;
    }

    /**
     * A dose-heatmap cell's accessibility name — every fact its fill and outline draw,
     * said outright, the same discipline {@link PhotoCalendar#cellName} already applies to
     * the photo grid's own cells. A FUTURE day says nothing about dose (nothing has
     * happened yet to have any); every other day names its schedule state, if it has one
     * worth naming, THEN its dose tier — both said, never one implying the other, because
     * (see this file's own class doc) a MISSED day can still carry real dose from an
     * uncompleted or manual session and the two facts must not be allowed to contradict
     * each other by only one of them being spoken.
     */
    public static String doseCellName(int day, String monthName, int tier, int role) {
        StringBuilder b = new StringBuilder();
        b.append(day).append(' ').append(monthName);
        if (role == DAY_FUTURE) { b.append(", not yet"); return b.toString(); }
        if (role == DAY_MISSED) b.append(", missed scheduled day");
        else if (role == DAY_OFF) b.append(", not a training day");
        else if (role == DAY_REST) b.append(", rest day");
        if (tier == DOSE_NONE) b.append(", no session");
        else if (tier == DOSE_LOW) b.append(", light session");
        else if (tier == DOSE_MED) b.append(", moderate session");
        else b.append(", heavy session");
        return b.toString();
    }

    /* ============================================== 5. THE INSIGHTS CARD (S8 A) ==== */

    /**
     * The trailing window every line below reads over — four weeks, exactly (28 = 4×7), so
     * ANY fixed weekly schedule contributes an exact multiple of its own weekly count to
     * {@link #CONSISTENCY_MIN_SCHED_DAYS}'s denominator with no partial-week remainder to
     * round away, whichever weekday `now` happens to fall on. Wide enough that even a
     * once-a-week schedule clears its own floor by the fourth week; narrow enough that the
     * card is still reporting on NOW, not on the user's whole history.
     */
    public static final int INSIGHT_WINDOW_DAYS = 28;

    /** LINE 1 — CONSISTENCY. Needs at least this many SCHEDULED days inside the window
     *  before it speaks. Four, not three: with a 28-day/4-week window every fixed weekly
     *  schedule contributes an exact multiple of 4 (28 ÷ 7), so 4 is the smallest floor a
     *  once-a-week schedule can ever actually reach — a floor of 3 would be unreachable by
     *  design for that schedule and untestable at its own boundary. */
    public static final int CONSISTENCY_MIN_SCHED_DAYS = 4;

    /** LINE 2 — TREND. Needs at least this many COLD (pre) readings inside the window: two
     *  points are a single before/after pair, not a trend — {@link Meas#deltaFor}'s own
     *  newest-minus-oldest already draws exactly that line without ever asking whether the
     *  "trend" it names was more than one measurement wide. */
    public static final int TREND_MIN_READINGS = 3;

    /** LINE 3 — SESSION QUALITY. Needs at least this many non-manual sessions inside the
     *  window: a completion rate computed from one or two sessions swings between 0% and
     *  100% on the very next session filed and says nothing honest about "quality" yet. */
    public static final int QUALITY_MIN_SESSIONS = 3;

    /**
     * "Trained N of M scheduled days over the last 28 days (P%)." Only SCHEDULED days that
     * were also trained count toward N — never a raw count of every day trained, which
     * could exceed M (and read as a nonsense >100%) for anyone who trains on an off day —
     * so N is always ≤ M by construction. Null below {@link #CONSISTENCY_MIN_SCHED_DAYS}.
     */
    public static String consistencyLine(List<Model.Sess> log, Schedule sched, long now) {
        return consistencyLine(log, sched, now, 0L);
    }

    /** POLISH #3: `sinceDay` bounds the window at enrolment — days before the plan
     *  existed are neither scheduled nor missable. 0 = no bound (the old behaviour). */
    public static String consistencyLine(List<Model.Sess> log, Schedule sched, long now,
                                         long sinceDay) {
        if (sched == null) sched = Schedule.allDays();
        long today = Summary.dayNumber(now);
        long lo = today - (INSIGHT_WINDOW_DAYS - 1);
        if (sinceDay > 0 && sinceDay > lo) lo = sinceDay;
        int schedDays = 0;
        for (long d = lo; d <= today; d++) if (sched.trainsOnDay(d)) schedDays++;
        if (schedDays < CONSISTENCY_MIN_SCHED_DAYS) return null;

        java.util.Set<Long> done = new java.util.HashSet<Long>();
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess s = log.get(i);
            // THE SAME TRAINED-DAY RULE THE STREAK AND THE WEEK RING USE (audit A6). This
            // line used to test `completed` outright, so a stopped-but-substantial session
            // advanced the streak while this sentence called the same day untrained \u2014 three
            // numbers on one screen, each counting differently. Summary.trainedDay is the
            // single answer; nothing here may ask the question its own way again.
            if (s == null || s.manual || !Summary.trainedDay(s)) continue;
            long d = Summary.dayNumber(s.ts);
            if (d < lo || d > today || !sched.trainsOnDay(d)) continue;
            done.add(Long.valueOf(d));
        }
        int pct = (int) Math.round(100.0 * done.size() / schedDays);
        return "Trained " + done.size() + " of " + schedDays + " scheduled days over the "
             + "last " + INSIGHT_WINDOW_DAYS + " days (" + pct + "%).";
    }

    /**
     * "Length/Girth is growing fastest right now (+X cm over the last 28 days)." Reads
     * {@link Meas#deltaFor} over this file's own window and picks whichever COLD-reading
     * delta is LARGER (i.e. growing faster), comparing the signed values directly — never
     * their magnitudes, which would let a shrinking dimension "win" over a growing one
     * just because it moved further in the wrong direction; when neither grew (both ≤ 0,
     * or absent) it says so plainly rather than crowning a "winner" between two numbers
     * that are not actually growth. Null below {@link #TREND_MIN_READINGS} cold readings
     * inside the window — a delta between exactly two points is a before/after pair
     * wearing a trend's language — OR when NEITHER metric has a comparable pair at all
     * (deltaFor's Double.NaN: a window of readings can be full without a single one of
     * them having measured the same metric twice — e.g. every reading is a different
     * at-rest protocol).
     */
    public static String trendLine(Model.MeasLog measLog, long now) {
        List<Model.Reading> window = Meas.preOf(Meas.windowFor(measLog, INSIGHT_WINDOW_DAYS, now));
        if (window.size() < TREND_MIN_READINGS) return null;
        double[] delta = Meas.deltaFor(measLog, INSIGHT_WINDOW_DAYS, now);
        double lenD = delta[0], girD = delta[1];
        boolean haveLen = !Double.isNaN(lenD), haveGir = !Double.isNaN(girD);
        if (!haveLen && !haveGir) return null;
        boolean lenGrew = haveLen && lenD > 0;
        boolean girGrew = haveGir && girD > 0;
        if (!lenGrew && !girGrew)
            return "Little change in length or girth over the last " + INSIGHT_WINDOW_DAYS
                 + " days.";
        // At least one of the two GREW past this point. Comparing the signed values
        // directly — not their magnitudes — guarantees the winner named below is always
        // the positive one; an absent metric (haveLen/haveGir false) never wins, exactly
        // as if it had not grown.
        boolean lenFaster = lenGrew && (!girGrew || lenD >= girD);
        return (lenFaster ? "Length" : "Girth") + " is growing fastest right now ("
             + Summary.cm(lenFaster ? lenD : girD) + " over the last " + INSIGHT_WINDOW_DAYS
             + " days).";
    }

    /**
     * "P% of sessions ran to completion over the last 28 days (C of N)." A MANUAL run is
     * excluded — it is an ephemeral cycle, not a routine session, the same exclusion
     * {@link Summary#of} and {@link Milestones.Week} both already apply — so this reports
     * on the sessions a routine actually asked for. Null below {@link
     * #QUALITY_MIN_SESSIONS}.
     */
    public static String qualityLine(List<Model.Sess> log, long now) {
        long today = Summary.dayNumber(now);
        long lo = today - (INSIGHT_WINDOW_DAYS - 1);
        int n = 0, completed = 0;
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s == null || s.manual) continue;
            long d = Summary.dayNumber(s.ts);
            if (d < lo || d > today) continue;
            n++;
            if (s.completed) completed++;
        }
        if (n < QUALITY_MIN_SESSIONS) return null;
        int pct = (int) Math.round(100.0 * completed / n);
        return pct + "% of sessions ran to completion over the last " + INSIGHT_WINDOW_DAYS
             + " days (" + completed + " of " + n + ").";
    }

    /** The up-to-three lines the insights card actually shows, in the order S8 A's own
     *  candidate list names them: consistency, trend, quality. Whichever have not cleared
     *  their own floor are simply absent — never a placeholder, never a zero-filled guess. */
    public static List<String> insightLines(List<Model.Sess> sessLog, Schedule sched,
                                             Model.MeasLog measLog, long now) {
        return insightLines(sessLog, sched, measLog, now, 0L);
    }

    /** POLISH #3: with the enrolment bound threaded to the consistency line. */
    public static List<String> insightLines(List<Model.Sess> sessLog, Schedule sched,
                                             Model.MeasLog measLog, long now, long sinceDay) {
        List<String> out = new java.util.ArrayList<String>();
        String c = consistencyLine(sessLog, sched, now, sinceDay);
        if (c != null) out.add(c);
        String t = trendLine(measLog, now);
        if (t != null) out.add(t);
        String q = qualityLine(sessLog, now);
        if (q != null) out.add(q);
        return out;
    }
}
