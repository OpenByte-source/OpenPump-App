package org.openpump;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Everything Today and the session summary claim, derived from the sessions that were
 * actually filed — Task 11. Pure (no `import android`), so test.sh compiles it into the
 * desktop self-test and every number below is asserted on rather than eyeballed.
 *
 * The rule this class exists to enforce
 * (.superpowers/sdd/2026-08-17-pump-beta/task-11-addendum.md): every number on the
 * summary and on Today is derived from what a session ACTUALLY DELIVERED — from the
 * filed records, or from wall-clock elapsed — never from a routine's configuration and
 * never from a count multiplied by an assumed constant. The prototype's Today read
 * TOTAL TIME as `sessions x 11 minutes` (defect #06), so halving every routine changed
 * nothing on screen; here totalSec is the sum of the durations actually filed.
 *
 * Two further defects live in here as invariants rather than as comments:
 *
 *   #04 — the streak is counted PER DAY. A second completed session on a day that has
 *   already been counted does not advance it. advancesStreak() is the ONE gate; the
 *   summary asks it before filing to word its message, and Stats#streak (computed from
 *   the records afterwards) is what the whole app then displays. SelfTest asserts the
 *   two can never disagree: streak(log + a completion at t) always equals
 *   streak(log) + (advancesStreak(log, t) ? 1 : 0).
 *
 *   #30 — a delta is coloured by its SIGN. tone() never returns TONE_GOOD for a session
 *   whose measured change was negative, so a shrink cannot reappear painted green.
 */
public final class Summary {
    private Summary() { }

    /** Days on Today's strip and in the week ring — the prototype's 7-dot row. */
    public static final int WEEK_DAYS = 7;

    /* ------------------------------------------------------------------ days */

    /**
     * The LOCAL calendar day `ts` falls on, as a day number that can be compared and
     * subtracted (consecutive days differ by exactly 1).
     *
     * Deliberately NOT `ts / 86400000`: that is a UTC bucket, so in any zone east or
     * west of UTC an evening session lands on the wrong day, and across a DST change
     * the bucket width stops being a day at all — either of which silently breaks a
     * streak the user actually earned. Calendar gives the local Y/M/D; the standard
     * Fliegel-Van Flandern formula turns that into a Julian day number, which is exact
     * integer arithmetic with no offset or DST term in it.
     */
    public static long dayNumber(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return dayNumber(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                         c.get(Calendar.DAY_OF_MONTH));
    }

    /** Julian day number for a proleptic-Gregorian Y/M/D (month 1-12). */
    public static long dayNumber(int y, int m, int d) {
        long a = (14 - m) / 12;
        long yy = y + 4800 - a;
        long mm = m + 12 * a - 3;
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045;
    }

    /* ----------------------------------------------------------------- stats */

    /** Everything Today displays, all of it derived from the filed sessions. */
    public static final class Stats {
        /** Consecutive days, ending today or yesterday, with at least one COMPLETED
         *  session. 0 when neither today nor yesterday has one — a streak is only
         *  "alive" while it could still be extended today. */
        public int streak;
        /** The longest such run anywhere in the history, never below `streak`. */
        public int best;
        /** How many of the last 7 days (today included) have a completed session —
         *  the week ring's numerator. */
        public int weekDays;
        /** One flag per day of the strip; index 6 is TODAY, index 0 is six days ago. */
        public final boolean[] days = new boolean[WEEK_DAYS];
        /** Which of those same seven days are SCHEDULED training days. The ring draws a
         *  scheduled-but-missed day differently from a day that was never a training day
         *  at all — an unscheduled day is not a failure and must not be drawn as one. */
        public final boolean[] scheduled = new boolean[WEEK_DAYS];
        /** How many of the seven are scheduled — the ring's denominator, in place of a
         *  flat 7 that would report a Mon/Wed/Fri user as 3 of 7 every perfect week. */
        public int scheduledDays;
        /** The index into {@link #days} of the ONE missed scheduled day the current
         *  streak's rest day is protecting, or -1 when no rest day is in play (or the
         *  protected day is older than the strip). Drawn distinctly: it is neither a done
         *  day nor a break, and the user is entitled to see which day it spent. */
        public int protectedIdx = -1;
        /** Whether the live streak has spent its one rest day. */
        public boolean restUsed;
        /** Completed sessions, and sessions that were stopped early (attempts). */
        public int completed, attempts;
        /** The sum of every filed duration — completions AND attempts, because this
         *  claims to measure time spent, not sessions finished. Never a count times a
         *  constant (defect #06). */
        public long totalSec;
        /** completed / (completed + attempts), as a percentage, or -1 when nothing has
         *  been filed at all — a percentage of nothing is not 0%, it is unknown. */
        public int completionPct;
    }

    /**
     * The old call, and the definition of the migration: NO SCHEDULE means all seven days
     * are training days, under which the rebased streak below reduces exactly to the
     * consecutive-calendar-day rule this used to implement. Every existing streak fixture
     * still calls this, so the equivalence is asserted by the suite that was already
     * there rather than by a new one written to agree with the new code.
     */
    public static Stats of(List<Model.Sess> log, long now) {
        return of(log, now, Schedule.allDays());
    }

    public static Stats of(List<Model.Sess> log, long now, Schedule sched) {
        return of(log, now, sched, NO_DAY, NO_DAY);
    }

    /**
     * STAGE H TASK 5 — the deload-widened counterpart. {@code deloadStartDay}/
     * {@code deloadEndDay} (day numbers, end EXCLUSIVE, from {@link TrainerTab#deloadDayRange})
     * name a stretch of days that must read as REST rather than a miss — the plan's own
     * ruling: "a deload must never read as a broken streak". Any {@code deloadStartDay >=
     * deloadEndDay} (the {@link #NO_DAY}/{@link #NO_DAY} default the 3-arg overload passes,
     * or any other empty range) matches nothing, so every existing caller of the 3-arg
     * overload is byte-for-byte unaffected.
     *
     * Deliberately REUSES the exact "not a training day" skip the schedule rebase already
     * has (Stage B), rather than inventing a second kind of protected day: a deload day is
     * folded into the SAME branch an unscheduled Tuesday already takes in
     * {@link #streakEndingAt}/{@link #bestRun} — neither adds to the streak nor breaks it —
     * and {@link Stats#scheduled} is false for it too, so the week ring draws it exactly like
     * a day that was never a training day (the ring's existing, already-audited "not a
     * failure" visual), never inventing a new glyph.
     */
    public static Stats of(List<Model.Sess> log, long now, Schedule sched,
                            long deloadStartDay, long deloadEndDay) {
        if (sched == null) sched = Schedule.allDays();
        Stats s = new Stats();
        long today = dayNumber(now);
        List<Long> doneDays = completedDays(log);

        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess e = log.get(i);
            if (e == null) continue;
            // A MANUAL run (Task 18) is filed and visible in History, but it is NOT a routine
            // session: it must not move the session counts, the total time, the completion
            // percentage or (via completedDays below) the streak and the week ring. So it is
            // skipped here entirely — Stats#of(log) equals Stats#of(log without the manual
            // rows), which is the whole "does not corrupt the statistics" guarantee.
            if (e.manual) continue;
            if (e.completed) s.completed++; else s.attempts++;
            s.totalSec += Math.max(0L, e.durSec);
        }
        int filed = s.completed + s.attempts;
        s.completionPct = filed == 0 ? -1 : (int) Math.round(100.0 * s.completed / filed);

        // The strip and the ring: the seven days ending today.
        for (int i = 0; i < WEEK_DAYS; i++) {
            long day = today - (WEEK_DAYS - 1 - i);
            /* THE NUMERATOR AND THE DENOMINATOR ASK THE SAME QUESTION.
             *
             * weekDays counted every day trained; scheduledDays counted only the days the
             * schedule asked for. On an all-seven-day week against a three-day plan the ring
             * read "7 of 3" - and, worse, a session on an off day was credited as schedule
             * compliance. A day trained off-plan is training; it is not a scheduled day
             * kept. */
            s.days[i] = doneDays.contains(Long.valueOf(day));
            s.scheduled[i] = sched.trainsOnDay(day) && !inDeload(day, deloadStartDay, deloadEndDay);
            if (s.scheduled[i]) s.scheduledDays++;
            if (s.days[i] && s.scheduled[i]) s.weekDays++;
        }

        Streak run = streakEndingAt(doneDays, today, sched, deloadStartDay, deloadEndDay);
        s.streak = run.count;
        s.restUsed = run.protectedDay != NO_DAY;
        if (s.restUsed) {
            long off = today - run.protectedDay;
            if (off >= 0 && off < WEEK_DAYS) s.protectedIdx = (int) (WEEK_DAYS - 1 - off);
        }
        s.best = bestRun(doneDays, sched, deloadStartDay, deloadEndDay);
        if (s.best < s.streak) s.best = s.streak;
        return s;
    }

    /** Whether day number {@code d} falls in [{@code startDay}, {@code endDay}) — the deload
     *  widening's one predicate, shared by the streak walk and the week-ring's scheduled[]
     *  flags so the two can never disagree about which days were resting. An empty or
     *  inverted range ({@code startDay >= endDay}) matches nothing, which is what every
     *  non-deload caller passes. */
    private static boolean inDeload(long d, long startDay, long endDay) {
        return startDay < endDay && d >= startDay && d < endDay;
    }

    /**
     * HRS IN WINDOW — the sum of every filed session's duration whose timestamp is at or
     * after `cutoffMs`, in seconds. Stage B task 10's period-scoped counterpart to
     * {@link Stats#totalSec}: the TRENDS tab's HRS tile needs "how many hours in the
     * selected 6W/3M/1Y/All window", and totalSec cannot answer that — it is summed over
     * the WHOLE history, with no period boundary at all.
     *
     * `cutoffMs` is meant to be {@link Meas#periodCutoff}'s own answer for whichever
     * period pill is selected — the SAME boundary the trend chart's readings are
     * filtered by, so the HRS tile and the LEN/GIR tiles beside it can never silently
     * disagree about what "this period" spans. This method only sums; it does not derive
     * the cutoff itself, so there is exactly one place that arithmetic lives.
     *
     * The exact same duration rule {@link #of} uses for the all-time total: a MANUAL run
     * (Task 18) is not a routine session and contributes nothing (this counts training
     * time, not every second the pump happened to be attached), and a negative durSec —
     * should never occur, but {@link #of} guards it too — contributes 0 rather than
     * being subtracted.
     */
    public static long totalSecInWindow(List<Model.Sess> log, long cutoffMs) {
        long sum = 0;
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess e = log.get(i);
            if (e == null || e.manual) continue;
            if (e.ts < cutoffMs) continue;
            sum += Math.max(0L, e.durSec);
        }
        return sum;
    }

    /** {@link #completedDays}, exposed for {@link Milestones} — which has to ask the SAME
     *  question ("which local days carry a trained routine session") and must not answer
     *  it with a second walk that could disagree about manual runs or stopped attempts. */
    public static List<Long> completedDayNumbers(List<Model.Sess> log) {
        return completedDays(log);
    }

    /** Distinct local day numbers carrying at least one TRAINED routine session
     *  ({@link #trainedDay}): one that finished, or one stopped early after at least a minute
     *  of real pressure. A seconds-long attempt, or one with no reading from the pump, never
     *  lights a day; a manual run never does. (The name is older than the rule: this said
     *  "completed" and "a stopped session does not count", which stopped being true when
     *  trainedDay counted a stopped session that delivered real pressure. The summary's
     *  Streak row asks the same rule, through {@link #countsForStreak}.) */
    private static List<Long> completedDays(List<Model.Sess> log) {
        List<Long> out = new ArrayList<Long>();
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess e = log.get(i);
            // A manual run never lights a day (see of() above): it is not a routine
            // completion, so it can neither start nor extend a streak.
            if (e == null || e.manual || !trainedDay(e)) continue;
            Long d = Long.valueOf(dayNumber(e.ts));
            if (!out.contains(d)) out.add(d);
        }
        return out;
    }

    /** A routine day is trained when the run finished OR it delivered real pressure for a
     *  meaningful stretch — a session stopped early after 8 real minutes is training the
     *  streak must not deny. A no-reading / seconds-long attempt still does not count. */
    public static final long TRAINED_MIN_SEC = 60;

    /** WHETHER THIS SESSION COUNTS FOR THE STREAK - the rule {@link #completedDays} walks,
     *  asked of one session, so the summary's "Streak" row can never say "Not counted" for a
     *  session the streak counted: a routine session that finished, or one stopped early
     *  after at least {@link #TRAINED_MIN_SEC} of real pressure. A manual run never does. */
    public static boolean countsForStreak(Model.Sess e) {
        return e != null && !e.manual && trainedDay(e);
    }
    static boolean trainedDay(Model.Sess e) {
        return e.completed || (e.peakKpa != null && e.durSec >= TRAINED_MIN_SEC);
    }

    /** "no such day" for {@link Streak#protectedDay} — never 0, which is a real (if
     *  absurdly old) day number and would read as a protected day in 4713 BC. */
    public static final long NO_DAY = Long.MIN_VALUE;

    /** A streak and, if one was spent, WHICH day the rest day is covering. */
    public static final class Streak {
        public int count;
        public long protectedDay = NO_DAY;
    }

    /**
     * THE STREAK, REBASED ONTO THE TRAINING SCHEDULE.
     *
     * It used to count consecutive CALENDAR days with a completed session. That punishes
     * the user for the schedule they chose: a Mon/Wed/Fri trainer breaks their streak
     * every Tuesday by doing exactly what they planned. So the walk below steps back a
     * day at a time from today and:
     *
     *   · a day that is NOT scheduled is SKIPPED — it neither adds to the streak nor
     *     breaks it. It was never a day anything was owed on.
     *   · a scheduled day with a completed session ADDS one.
     *   · TODAY, when it is scheduled and not yet done, is skipped like an unscheduled
     *     day. The streak is alive until the day ends; it is not a miss at 09:00.
     *   · a scheduled day with no session is a MISS. The FIRST miss of a run spends the
     *     ONE rest day: it does not add, and it does not break. A second miss breaks.
     *
     * The rest day is per streak-RUN, not per week or per month: once the streak breaks,
     * the next run starts with its protection intact, because the walk simply stops and
     * the next call starts a fresh one. That is the whole reset rule, and it is a
     * property of the walk rather than a stored counter that could drift.
     *
     * With ALL SEVEN DAYS scheduled — the migration default — the first three bullets
     * collapse to the old rule exactly, and the fourth is the only behavioural change.
     * SelfTest asserts that on the original fixtures.
     *
     * The walk is bounded by the OLDEST completed day: past it every remaining day is a
     * miss, so nothing beyond it can extend anything, and an empty log terminates
     * immediately instead of walking back to the Julian epoch.
     */
    private static Streak streakEndingAt(List<Long> doneDays, long today, Schedule sched,
                                         long deloadStartDay, long deloadEndDay) {
        Streak out = new Streak();
        if (doneDays.isEmpty()) return out;
        long oldest = doneDays.get(0).longValue();
        for (int i = 1; i < doneDays.size(); i++)
            if (doneDays.get(i).longValue() < oldest) oldest = doneDays.get(i).longValue();

        boolean rest = false;
        for (long d = today; d >= oldest; d--) {
            // A deload day is folded into the SAME "not a training day" skip an unscheduled
            // day already takes — Stage H's widening of this exact branch, not a second
            // mechanism: it neither adds to the streak nor breaks it, and does not spend the
            // one rest day below.
            if (!sched.trainsOnDay(d) || inDeload(d, deloadStartDay, deloadEndDay)) continue;
            if (doneDays.contains(Long.valueOf(d))) { out.count++; continue; }
            if (d == today) continue;                            // still live, not a miss
            if (!rest) { rest = true; out.protectedDay = d; continue; }
            break;
        }
        // A protection spent with nothing behind it protects nothing: it can only mean the
        // walk found a miss and then ran out of record. Reported as unspent, so the ring
        // does not mark a rest day on a streak of 0.
        if (out.count == 0) { out.protectedDay = NO_DAY; }
        return out;
    }

    /**
     * The longest run anywhere in the record, under the same rules — scheduled days only,
     * one rest day per run.
     *
     * Walked FORWARD across the whole span the record covers, because a run's protection
     * has to be spent in the same direction the run is counted: it is one rest day per
     * run, and which day it covers depends on where the run started. `best` is taken as
     * the running maximum rather than at each break, so a run that is still open at the
     * end of the record is not lost.
     */
    private static int bestRun(List<Long> doneDays, Schedule sched, long deloadStartDay,
                               long deloadEndDay) {
        if (doneDays.isEmpty()) return 0;
        long lo = doneDays.get(0).longValue(), hi = lo;
        for (int i = 1; i < doneDays.size(); i++) {
            long d = doneDays.get(i).longValue();
            if (d < lo) lo = d;
            if (d > hi) hi = d;
        }
        int best = 0, n = 0;
        boolean rest = false;
        for (long d = lo; d <= hi; d++) {
            // Same widening as streakEndingAt's identical branch, above.
            if (!sched.trainsOnDay(d) || inDeload(d, deloadStartDay, deloadEndDay)) continue;
            if (doneDays.contains(Long.valueOf(d))) {
                n++;
                if (n > best) best = n;
            } else if (n > 0 && !rest) {
                rest = true;              // this run spends its one rest day here
            } else {
                n = 0; rest = false;      // broken — the next run starts protected again
            }
        }
        return best;
    }

    /**
     * Whether a session completed at `now` will ADVANCE the day streak — false when a
     * completed session has already been filed today.
     *
     * Defect #04: the prototype's summary painted `STREAK + 1` unconditionally while
     * finishSession's day gate refused to grant it, so the second session of a day
     * promised a streak the app then did not produce. This is that gate, and it is the
     * only one: the summary calls it (before filing) purely to word its message, and
     * the number it displays afterwards is Stats#streak recomputed from the records.
     * SelfTest pins the two together.
     */
    public static boolean advancesStreak(List<Model.Sess> log, long now) {
        return advancesStreak(log, now, Schedule.allDays());
    }

    /**
     * The same gate, asked of a SCHEDULE — and the schedule adds a second way for the
     * answer to be no: a completion filed on a day that is not a training day does not
     * advance the streak, because the streak counts scheduled days.
     *
     * That is not a punishment for training on a rest day. The session is still filed,
     * still counted, still in the total time and still in the week's numbers; it is only
     * the RUN OF SCHEDULED DAYS that it cannot extend, because a Tuesday is not one. The
     * alternative — letting any day extend it — is the rule that was just replaced, one
     * direction over.
     *
     * With the all-days migration default `trainsOn` is always true, so this reduces to
     * the original one-line gate and the pinned invariant is unchanged.
     */
    public static boolean advancesStreak(List<Model.Sess> log, long now, Schedule sched) {
        return advancesStreak(log, now, sched, NO_DAY, NO_DAY);
    }

    /**
     * ...AND A DELOAD WEEK IS NOT A STREAK DAY.
     *
     * Summary.of widens the streak walk across a prescribed deload - the days are skipped,
     * neither breaking a run nor extending it. This predicate did not know deloads existed,
     * so the post-session summary told somebody their deload session had advanced a streak
     * that the very next render left exactly where it was.
     */
    public static boolean advancesStreak(List<Model.Sess> log, long now, Schedule sched,
                                         long deloadStartDay, long deloadEndDay) {
        if (sched == null) sched = Schedule.allDays();
        long today = dayNumber(now);
        if (!sched.trainsOnDay(today)) return false;
        if (inDeload(today, deloadStartDay, deloadEndDay)) return false;
        return !completedDays(log).contains(Long.valueOf(today));
    }

    /**
     * Whether the streak Today displays was ADVANCED BY TODAY — that is, today already
     * carries a completed session, so the number on screen is one this day earned.
     *
     * This is the ONLY thing the streak card's celebratory accent (the "+1 today" pill) is
     * allowed to be driven by. It used to drive a green TONE as well; green is reserved for
     * a telemetry verdict app-wide, so the streak now wears lime whether or not today
     * advanced it and the pill alone carries the fact. It is the exact complement of
     * {@link #advancesStreak} asked of the same log and the same instant: advancesStreak
     * says "a completion filed now WOULD advance it", so its negation says "one already
     * did". SelfTest pins the two together, because a celebration that could disagree
     * with the gate that grants the streak is decoration, and decoration is what this
     * app's colour rules exist to keep off the screen.
     *
     * Reads days[WEEK_DAYS - 1] — index 6 is today (see Stats#days) — and not `streak`
     * alone, because a streak of N is also displayed on the morning after the Nth day,
     * when nothing has been earned yet.
     */
    public static boolean advancedToday(Stats s) {
        // AND scheduled: on a day that is not a training day a completion is filed and
        // counted, but it does not extend the run of scheduled days, so the card must not
        // put a "+1 today" pill on a streak that did not move. Under the all-days default
        // scheduled[6] is always true and this is the original one-line test.
        return s != null && s.days[WEEK_DAYS - 1] && s.scheduled[WEEK_DAYS - 1];
    }

    /**
     * The honest one-line state under the big streak number on Today — pure, so the exact
     * words are pinned by SelfTest rather than eyeballed on a device.
     *
     * Four states, and each says something TRUE and useful rather than cheering. A live
     * streak short of the record says how far short it is; a live streak that IS the
     * record says so; a dead streak with a record behind it points at today; and a user
     * who has never completed anything is told what starts one, not that they are on 0.
     */
    public static String streakLine(Stats s) {
        if (s == null) return "";
        if (s.streak <= 0)
            return s.best > 0 ? "best " + s.best + "  ·  start a new one today"
                              : "no completed session yet — the first one starts it";
        if (s.best > s.streak)
            return "best " + s.best + "  ·  " + (s.best - s.streak) + " to beat it";
        return "a new personal best";
    }

    /**
     * The ONE sentence a screen reader hears for the whole composed streak graphic — the
     * number, the ring and the seven day dots are a single Canvas with no text in it, so
     * without this they are silent, and split across children they would be a stream of
     * bullets. Pure for the same reason streakLine is: the words are the interface.
     */
    public static String streakSaid(Stats s) {
        if (s == null) return "";
        // "of N days this week" is the SCHEDULED count, not a flat 7: a Mon/Wed/Fri user
        // who trained every scheduled day has had a perfect week, and "3 of 7" would call
        // it a 43% one. Under the all-days migration default this is 7, which is why the
        // original wording still holds for an upgraded file.
        return s.streak + " day streak, best " + s.best + ", " + s.weekDays + " of "
             + s.scheduledDays + " days this week"
             + (s.restUsed ? ", rest day spent" : "")
             + (advancedToday(s) ? ", advanced today" : "");
    }

    /* ------------------------------------------------------------------ tags */

    public static final String TAG_STOPPED  = "stopped early";
    /**
     * "no AFTER-MEASUREMENT was logged" — the tape measure, not the tissue adaptation
     * assessment Task 15 built. It used to be spelled "no assessment", and once both
     * features shipped the summary could print that within a few lines of a tau result,
     * with "assessment" meaning two different things on one screen. The CONSTANT is
     * renamed and the DISPLAY STRING reworded; the old string is kept below purely as a
     * migration key, because this value is persisted verbatim in every History row ever
     * filed and a rename with no migration would silently reinterpret them all.
     */
    public static final String TAG_NO_AFTER = "no after-measurement";
    /** The pre-Task-14 spelling of TAG_NO_AFTER, as it sits in already-filed rows.
     *  Model.Sess.fromJson maps it forward; nothing else may use it. */
    public static final String LEGACY_TAG_NO_ASSESSMENT = "no assessment";
    public static final String TAG_NOT_LIKE  = "assessed — not like-for-like";
    /** M4 - how TAG_NOT_LIKE is SHOWN. See {@link #tagShown}. */
    public static final String TAG_NOT_LIKE_SHOWN = "measured two ways";

    /**
     * M4 - THE WORDS A FILED TAG IS SHOWN IN. The stored tag is data - History reads it back
     * and every row filed so far carries it - so it is never rewritten. But "assessed — not
     * like-for-like" ranks a before/after pair taken two ways as a failure, which the owner's
     * rule forbids (at rest and standardised are equal ways to measure; STUDY-19 R22), so the
     * summary and the Sessions list show it as what it is. Every other tag is shown as filed.
     */
    public static String tagShown(String tag) {
        if (tag == null) return "";
        return tag.replace(TAG_NOT_LIKE, TAG_NOT_LIKE_SHOWN);
    }
    /** A MANUAL run's History tag (Task 18) — it says what actually happened, so a manual
     *  cycle is never mistaken for a routine session in the History list. A stopped manual
     *  run still says "manual" first; it was a manual cycle whether or not it ran to the end. */
    public static final String TAG_MANUAL = "manual run";
    public static final String TAG_MANUAL_STOPPED = "manual — stopped early";

    /** The tag a manual session files with — the manual counterpart of {@link #tag}. Kept
     *  separate from tag() because a manual run carries no after-measurement branch and must
     *  never be described as a routine completion. */
    public static String manualTag(boolean completed) {
        return completed ? TAG_MANUAL : TAG_MANUAL_STOPPED;
    }

    /**
     * One decision about what this session WAS, used by the summary chip and by the
     * History row so the two can never describe the same session differently (the
     * prototype's sessTag, same purpose).
     *
     * `afterLen`/`afterGir` are the after-session measurement deltas in cm, or null
     * when no after-reading was taken — this app's only "assessment" of a session's
     * effect. `comparable` is Model.Reading.comparable()'s verdict on the before/after
     * pair: a delta measured under different hold conditions is reported as exactly
     * that, never folded into a clean signed number (the same rule the after screen
     * itself applies).
     */
    public static String tag(boolean completed, Double afterLen, Double afterGir,
                              boolean comparable) {
        if (!completed) return TAG_STOPPED;
        if (afterLen == null && afterGir == null) return TAG_NO_AFTER;
        if (!comparable) return TAG_NOT_LIKE;
        StringBuilder sb = new StringBuilder();
        if (afterLen != null) sb.append("Δ length ").append(cm(afterLen.doubleValue()));
        if (afterGir != null) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("girth ").append(cm(afterGir.doubleValue()));
        }
        return sb.toString();
    }

    /** A signed length change, in the DISPLAY size unit. Always carries its sign, so a
     *  bare magnitude can never be read as an improvement it was not.
     *
     *  It takes centimetres — everything in this app stores centimetres — and hands the
     *  formatting to Model.Fmt#lenDelta, the one place that knows what unit the user
     *  chose. The name is kept: every call site means "the change, as a length", and
     *  renaming forty of them would say nothing this comment does not. */
    public static String cm(double v) {
        return Model.Fmt.lenDelta(v);
    }

    /* ------------------------------------------- tissue adaptation assessment */

    /*
     * THE NAMING COLLISION, now resolved rather than merely noted. The tag above used to
     * be TAG_NO_ASSESSMENT / "no assessment" and meant "no AFTER-MEASUREMENT was logged";
     * the TISSUE ADAPTATION ASSESSMENT below is a different thing entirely (a pump
     * measurement, not a tape measure). Both appear in the app, and the summary and the
     * History row could print the tape-measure sense within a few lines of a tau result.
     * The tag is now TAG_NO_AFTER / "no after-measurement", migrated forward on load
     * (Model.Sess.fromJson) so already-filed rows are not reinterpreted, and every
     * string below still names the pump measurement with tau.
     */

    /**
     * What a session's tissue adaptation assessment produced, as a short tag for the
     * summary chip and the History row — one decision, so the two screens can never
     * describe the same session differently.
     *
     * "" means the assessment never ran for this session (it was off, or the session
     * ended before it could). Anything else is derived from the taus actually recorded:
     * a Delta tau only when BOTH ends produced a number, and otherwise a bare tau that
     * says out loud that it has nothing to compare against — never a delta against
     * nothing.
     */
    public static String tauTag(Model.Sess s) {
        // M4 - IN WORDS, NOT SYMBOLS. "Δτ +9%" and "τ 1.4 s — not comparable" are now
        // "Fill time 1.4 s → 1.5 s · +9 %" and "… · not compared" (TauSay#tag). What each
        // case CLAIMS is unchanged: a change only for a matched pair, the two numbers without
        // one for a pair that did not match (never "not measurable", which would be untrue),
        // a bare end that says it has nothing to compare with, and (C8) a skip that reads
        // "skipped" rather than claiming the test failed to measure.
        return TauSay.tag(s);
    }

    /**
     * C8, M4 - THE NAME OF HISTORY'S ROW: the name the summary's own card carries. C8 made it
     * the summary's symbol "τ" because "Tissue response" contradicted the summary's caption
     * ("not a tissue measurement"). The owner has since named the test the "Tissue response
     * test", and the summary card, its screens and this row all carry that one name - the
     * rule C8 set, one name on both screens, is what stays.
     */
    public static final String TAU_DETAIL_LABEL = TauSay.NAME;

    /**
     * C8 - THE VALUE OF HISTORY'S τ ROW, or null when the assessment never ran.
     *
     * It printed τ as a clock rounded to whole seconds ("0:07 → 0:07" for 6.6 s and 6.8 s)
     * with no Δτ, and only when both ends had a number. Now it is the summary's own
     * formatting ({@link Tau#fmtTau}, one decimal) with the same Δτ the summary and the
     * Sessions row state ({@link Tau#sessionDeltaPct} - the one decision), and it says
     * what happened at an end that produced no number instead of leaving the row out.
     */
    public static String tauDetail(Model.Sess s) {
        // M4: the same words the summary states - the change and, with it, whether that
        // change is within normal variation (TauSay#detail).
        return TauSay.detail(s);
    }

    /**
     * C8 - WHAT THE SUMMARY'S PULL CHART SHOWS, naming the session's own pull duration. It
     * hard-coded "45 s", the default, whatever the routine's test was set to; a session filed
     * without its duration (0) names none rather than a made-up one.
     *
     * M4: the chart now has a seconds axis and a legend, so this is no longer a note printed
     * under it - it is the tail of what the chart says to a screen reader, where the fact
     * that the axis stops at the top (not at the end of the pull) still has to be told.
     */
    public static String tauChartNote(boolean bothPulls, int assessDurSec) {
        String ran = assessDurSec > 0 ? " ran " + assessDurSec + " s;" : " ran;";
        return bothPulls
            ? "Before dashed, after solid, each timed from the moment it was commanded. Each pull"
              + ran + " the chart ends once both had reached the top."
            : "The one pull this session measured, timed from the moment it was commanded. It"
              + ran + " the chart ends once it had reached the top.";
    }

    /** The most recently filed session for `routineId` that carried an assessment
     *  stimulus, or null. The log is newest-first, so this is the first hit. Used to
     *  answer "is what the routine is set to NOW comparable with what it was measured
     *  under before?" — asked at the point of change, and again on any trend. */
    public static Model.Sess lastAssessed(List<Model.Sess> log, String routineId) {
        for (int i = 0; log != null && i < log.size(); i++) {
            Model.Sess e = log.get(i);
            if (e == null || e.assessDurSec <= 0) continue;
            if (routineId != null && !routineId.equals(e.routineId)) continue;
            return e;
        }
        return null;
    }

    /* ------------------------------------------------------------------ tone */

    /** How a filed session should be coloured. The UI maps these onto Ui colours; the
     *  DECISION is here so the summary and the History row cannot disagree. */
    public static final int TONE_GOOD = 1, TONE_WARN = 2, TONE_DIM = 3, TONE_CRIT = 4;

    /**
     * Defect #30: a delta is coloured by its SIGN. A session whose measured change was
     * negative can never come back TONE_GOOD, whatever screen re-prints it — the
     * prototype's summary took the after screen's already-coloured string and re-painted
     * the whole thing green, so a shrink shown in red on one screen turned into an
     * improvement on the next.
     */
    public static int tone(Model.Sess s) {
        if (s == null) return TONE_DIM;
        if (!s.completed) return TONE_WARN;
        if (s.afterLenCm == null && s.afterGirCm == null) return TONE_DIM;
        if (!s.afterComparable) return TONE_DIM;
        return deltaTone(s);
    }

    /**
     * How the AFTER-DELTA LINE should be coloured — a different question from tone()
     * above, which colours the SESSION.
     *
     * tone() answers "how did this session go", and a session that was stopped early is
     * TONE_WARN whatever it measured. That is right for the chip and the History bar,
     * and wrong for the delta line, which the summary's own comment says is "coloured by
     * its own SIGN": routed through tone(), a stopped session's shrink came out amber
     * instead of red, because the !completed branch returned before the sign was ever
     * looked at. Two of Task 11's deferred Minors are this one root cause.
     *
     * So this deliberately does NOT consider `completed`. A measured shrink is a shrink
     * whether or not the routine finished, and defect #30's rule — a negative delta can
     * never be painted as an improvement — has to hold on every screen that prints one.
     */
    public static int deltaTone(Model.Sess s) {
        if (s == null) return TONE_DIM;
        if (s.afterLenCm == null && s.afterGirCm == null) return TONE_DIM;
        if (!s.afterComparable) return TONE_DIM;
        boolean anyNegative = (s.afterLenCm != null && s.afterLenCm.doubleValue() < 0)
                           || (s.afterGirCm != null && s.afterGirCm.doubleValue() < 0);
        return anyNegative ? TONE_CRIT : TONE_GOOD;
    }

    /* ------------------------------------------------------------- delivered */

    /**
     * The deepest pressure actually COMMANDED across the presets that were started —
     * plan[0..throughIdx], clamped by the safety ceiling exactly as uploadBatch() does
     * before it writes them. This is the only figure on the summary that comes from
     * configuration, and it is labelled "commanded" wherever it is shown: it is the
     * reference the OBSERVED peak (Session#observedPeakKpa) is compared against, never
     * a stand-in for it. Returns 0 when nothing was started.
     */
    public static int commandedPeakKpa(List<Model.Preset> plan, int throughIdx, int ceilKpa) {
        int pk = 0;
        if (plan == null) return 0;
        for (int i = 0; i <= throughIdx && i < plan.size(); i++) {
            int up = Math.min(plan.get(i).up, ceilKpa);
            if (up > pk) pk = up;
        }
        return pk;
    }

    /**
     * The same figure, INCLUDING the tissue adaptation assessment's after-pull when one
     * actually armed inside the tracked run.
     *
     * The after-pull runs between Session#beginRun and Session#endRun, so its telemetry
     * flows through noteSample() and raises the session's OBSERVED peak. Comparing that
     * observed peak against a commanded peak walked from `plan` alone printed an
     * overshoot that never happened — "Peak observed 20.0 kPa against 14.0 kPa commanded
     * — +6.0 kPa" on a routine whose sets never exceed 14 — in the one card whose whole
     * promise is that every number in it is one the session actually produced. The two
     * sides of that comparison must span the same stretch of pump activity.
     *
     * `assessArmedKpa` is 0 unless the after-pull was armed: a routine with the
     * assessment configured but stopped before it ran contributes nothing, because
     * nothing was commanded.
     */
    public static int commandedPeakKpa(List<Model.Preset> plan, int throughIdx, int ceilKpa,
                                        int assessArmedKpa) {
        int pk = commandedPeakKpa(plan, throughIdx, ceilKpa);
        return assessArmedKpa > pk ? assessArmedKpa : pk;
    }

    /**
     * The same figure again, now also including the pressure this app commanded BEFORE
     * the tracked run began and left standing when it did.
     *
     * FINAL REVIEW, Important — the same defect the 4-arg overload above was added to
     * fix, left live for the two phases that PRECEDE the run. Nothing vents between the
     * seal check and Session#beginRun: finishSealCheck's own doc says it deliberately
     * does not vent, and finishAssessment's says the BEFORE pull does not vent
     * afterwards. So the first frames after beginRun() read the residual — the seal
     * check's min(20, ceiling), or the before-pull's own hold — and Session#noteSample
     * raises observedPeakKpa from them, while the reference they are printed against was
     * walked from `plan` alone. The Noticed card then reported an overshoot the pump
     * never performed: "Peak observed 20.0 kPa against 14.0 kPa commanded — +6.0 kPa" on
     * a routine whose sets never exceed 14, and "+12.0 kPa" on any early abort at
     * defaults, where presetsDone stops at the warm-up.
     *
     * `carriedInKpa` is that residual: it is a pressure this app COMMANDED during this
     * attempt, so it belongs on the commanded side of a comparison whose whole promise is
     * that both sides span the same stretch of pump activity. It is 0 when the run never
     * started, because then nothing carried into anything.
     *
     * The default configuration was clean only by coincidence — Assess.fresh sets
     * kpa = min(20, ceiling) and the seal check defaults to 20, so the after-pull's
     * armed pressure happened to equal both residuals. Raising the assessment pressure
     * masked the defect rather than avoiding it.
     */
    public static int commandedPeakKpa(List<Model.Preset> plan, int throughIdx, int ceilKpa,
                                        int assessArmedKpa, int carriedInKpa) {
        int pk = commandedPeakKpa(plan, throughIdx, ceilKpa, assessArmedKpa);
        return carriedInKpa > pk ? carriedInKpa : pk;
    }

    /* ------------------------------------------- what the summary says, from the record */

    /** Why a filed session has no peak (Model.Sess#noPeakWhy). UNKNOWN is every session
     *  filed before the reason was kept. */
    public static final int NO_PEAK_UNKNOWN = 0, NO_PEAK_NEVER_RAN = 1,
                            NO_PEAK_NO_READINGS = 2, NO_PEAK_NO_MEASUREMENT = 3;

    /**
     * The Delivered card's line where a peak would be, for a session that has none - read
     * from the record, so a summary reopened from History gives the reason ITS session had,
     * not the last live run's.
     *
     * Three different claims, because an attempt abandoned in the seal check never began
     * the run (so no sample was attributed to it, though the seal check's readings were on
     * screen a moment earlier), a run can see no telemetry at all, and a run can see
     * readings none of which carried a pressure. A session filed before the reason was kept
     * gets the one sentence true of all three.
     */
    public static String noPeakLine(Model.Sess s) {
        switch (s == null ? NO_PEAK_UNKNOWN : s.noPeakWhy) {
            case NO_PEAK_NEVER_RAN:
                return "the routine never started, so nothing was delivered — the seal check's "
                     + "readings are not attributed to a session";
            case NO_PEAK_NO_READINGS:
                return "the pump sent no readings during this session, so there is "
                     + "nothing to report as delivered";
            case NO_PEAK_NO_MEASUREMENT:
                return "no reading during this session carried a measurement, so there is "
                     + "nothing to report as delivered";
            default:
                return "no measured pressure was recorded for this session, so there is "
                     + "nothing to report as delivered";
        }
    }

    /**
     * The summary's "Noticed" card - every number in it one this session actually produced.
     * The observed peak is compared with what the pump was ASKED FOR (the commanded peak,
     * {@link #commandedPeakKpa}), never with a routine's configured target dressed up as an
     * outcome, and each part of that figure that did not come from the routine's own sets is
     * named, so a reader does not charge it to them.
     *
     * READ FROM THE RECORD, like the rest of the summary: the comparison used to be made
     * with figures the Activity held for the run that last filed, so a summary reopened from
     * History set its own peak against another session's. A session filed before the
     * commanded peak was kept states its peak and compares it with nothing.
     */
    public static String noticed(Model.Sess s, boolean nothingDelivered) {
        if (nothingDelivered)
            return "None of the routine ran, so there is nothing to tune. If this ended "
                 + "during the seal check, that check did put pressure on the cuff — it just "
                 + "isn't part of what the routine delivered.";
        // E2-4: a run stopped at the start check after its step by hand asked for nothing of
        // its own - said as that, never a peak set against "0.0 asked".
        if (s != null && s.byHandStopSec > 0) return ByHand.stoppedAtCheck(s.byHandStopSec);
        if (s == null || s.peakKpa == null)
            return "The pump sent no readings, so there is nothing to compare with what it "
                 + "was asked for. Check the connection before the next session.";
        double peak = s.peakKpa.doubleValue();
        if (s.cmdPeakKpa == null)
            return "Peak reached " + Model.Fmt.p(peak) + ". What the pump was asked for was "
                 + "not kept with sessions this old, so there is nothing to compare it with.";
        double asked = s.cmdPeakKpa.doubleValue();
        // "Asked for", not "commanded": the same comparison, in the words of the person
        // who set the pump going rather than of the code that drove it.
        String text = "Peak reached " + Model.Fmt.p(peak) + ", against "
                    + Model.Fmt.p(asked) + " the pump was asked for — "
                    + Model.Fmt.d(peak - asked) + ".";
        if (asked - peak > 1.0)
            text += " Sustained shortfall usually means the seal, not the pump: check the "
                  + "cuff position, or lower the target for the next run.";
        // Named, because both sides of the comparison include it and a reader otherwise
        // attributes the whole figure to the routine's own sets.
        // M4: the test by its name (TauSay.NAME), where it read "after-assessment pull".
        if (s.afterPullKpa > 0)
            text += "\nBoth figures include the tissue response test's after-pull at "
                  + Model.Fmt.p(s.afterPullKpa) + ", which ran inside this session.";
        // Same reason, for the pressure already on the cuff when the run started - stated
        // whenever it is what SET the commanded figure, which is exactly when a reader would
        // otherwise take it for one of the routine's own targets.
        if (s.carriedInKpa > 0 && s.carriedInKpa >= asked)
            text += "\nBoth figures include the " + Model.Fmt.p(s.carriedInKpa)
                  + " already on the cuff when the routine started — the "
                  + (s.carriedFromPull ? "tissue response test's before-pull" : "seal check")
                  + " does not vent on its way out.";
        if (s.noReadSamples > 0)
            text += "\n" + s.noReadSamples + " reading"
                  + (s.noReadSamples == 1 ? "" : "s") + " from the pump had no "
                  + "pressure in " + (s.noReadSamples == 1 ? "it and was" : "them and were")
                  + " left out of the dose — none was counted as 0 kPa.";
        return text;
    }
}
