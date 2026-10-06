package org.openpump;

import java.util.Calendar;
import java.util.Locale;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * THE TRAINING SCHEDULE — which weekdays this user trains on, at what hour, and whether
 * a reminder should fire. Pure (no `import android`), so test.sh compiles it into the
 * desktop self-test: every decision below is asserted rather than eyeballed on a device.
 *
 * It exists because the STREAK was rebased onto it (Summary#of). A streak that counts
 * consecutive CALENDAR days punishes a Mon/Wed/Fri user for the Tuesday they were never
 * meant to train on, so the schedule is not decoration — it is the definition of what
 * "missed" means. Which is exactly why it lives here, next to the arithmetic it feeds,
 * rather than as three loose fields on a settings screen.
 *
 * THE MIGRATION DEFAULT IS ALL SEVEN DAYS. A model.json written before this class
 * existed carries no schedule key at all, and {@link #fromJson}(null) must return a
 * schedule on which every day trains — because under an all-days schedule the rebased
 * streak reduces exactly to the old consecutive-calendar-day rule, so an existing user's
 * streak does not move on the upgrade. SelfTest asserts that equivalence against the
 * original fixtures rather than trusting the argument.
 *
 * DAY OF WEEK IS LOCAL. It is derived from {@link Summary#dayNumber}, which is built
 * from a local {@link Calendar} Y/M/D and never from `ts / 86400000` — the same rule,
 * for the same reason: a UTC divide puts an evening session on the wrong day in every
 * zone that is not UTC, and here that would make the app fire a reminder, or break a
 * streak, on a day the user does not train.
 */
public final class Schedule {

    /** Index 0 is MONDAY, index 6 is SUNDAY — the order the settings toggles are drawn
     *  in and the order {@link #DAY_ABBR} is written in. */
    public static final int MON = 0, TUE = 1, WED = 2, THU = 3, FRI = 4, SAT = 5, SUN = 6;
    public static final int DAYS = 7;

    public static final String[] DAY_ABBR =
        { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
    public static final String[] DAY_LETTER = { "M", "T", "W", "T", "F", "S", "S" };

    /** One flag per weekday, indexed MON..SUN. Defaults to ALL TRUE — see the migration
     *  note in the class comment; a brand-new install and an upgraded old file must land
     *  on the same schedule, or the upgrade would change the streak. */
    public final boolean[] days = new boolean[DAYS];

    /**
     * WHAT EACH DAY RUNS - the weekly planner (R5).
     *
     * {@link #days} says WHETHER you train on a weekday. This says WHAT, and the two are
     * deliberately separate: turning a day off should not silently forget that it was your
     * length day, and a plan on a rest day is simply not consulted.
     *
     * PLAN_ANY IS THE DEFAULT AND THE MIGRATION VALUE, and it is not a placeholder - it is
     * "whatever the plan is running", which is exactly what every day did before this
     * existed. A file written without a plan key must behave identically to one written
     * with seven ANYs, or the upgrade would quietly re-schedule somebody's week.
     *
     * PLAN_BOTH is the one that needs the order setting ({@link Model#rxLengthFirst}): it
     * means both tracks in the same day, and which comes first is a real preference rather
     * than an implementation detail - girth first is the harder work while fresh, length
     * first is the longer, gentler one while patient.
     */
    public static final int PLAN_ANY = 0, PLAN_GIRTH = 1, PLAN_LENGTH = 2, PLAN_BOTH = 3;
    /** A4 - "nothing today", reachable ONLY as a today-only override. A weekday says whether
     *  it trains with its own flag in {@link #days}; a rest inside the plan array would be
     *  the same fact spelled twice, and two spellings of one fact drift. */
    public static final int PLAN_REST = 4;
    public static final int PLANS = 5;
    /** How many the weekly cycler steps through: the four a repeating week can hold.
     *  {@link #PLAN_REST} is deliberately outside it - a rest weekday is the day toggle. */
    public static final int PLANS_WEEKLY = 4;

    /** One plan per weekday, indexed MON..SUN. All {@link #PLAN_ANY} by default. */
    public final int[] plan = new int[DAYS];

    /**
     * A4 - TODAY ONLY, without editing the week.
     *
     * The repeating week is the right shape for a plan and the wrong shape for a Tuesday you
     * decide to do differently. Editing Tuesday to change one Tuesday changes every Tuesday,
     * and then you have to remember to change it back - which nobody does.
     *
     * The override is stamped with the day it was made for and is simply not consulted on any
     * other day, so it expires by arithmetic rather than by something having to clear it at
     * midnight. 0 means none.
     */
    public int overrideDayKey = 0;
    public int overridePlan = PLAN_ANY;

    public void setOverride(int dayKey, int planValue) {
        overrideDayKey = dayKey;
        overridePlan = (planValue >= 0 && planValue < PLANS) ? planValue : PLAN_ANY;
    }

    public void clearOverride() { overrideDayKey = 0; overridePlan = PLAN_ANY; }

    /** Whether the day an instant falls on is running something other than its usual. */
    public boolean overriddenAt(long ts) {
        return overrideDayKey != 0 && overrideDayKey == PhotoCalendar.dayKey(ts);
    }

    /** What the LOCAL date an instant falls on is planned to run. A rest day answers
     *  {@link #PLAN_ANY} rather than its stored plan: nothing is planned for a day you do
     *  not train on, and returning the leftover would make callers ask twice.
     *
     *  A TODAY-ONLY OVERRIDE WINS over both, including over a rest day - deciding to train on
     *  a rest day is exactly the kind of on-the-spot change this exists for.
     *
     *  t10 R-60: the week is the one {@link #longDaysInForce} gives ({@link #trainsOn},
     *  {@link #planOn}) - under "Alternate days, each track 3 days a week" girth Mon/Wed/Fri
     *  and length Tue/Thu/Sat, under "Alternate on my days" the person's days in turn. */
    public int planAt(long ts) {
        if (overriddenAt(ts)) return overridePlan;
        int w = weekdayAt(ts);
        return trainsOn(w) ? planOn(w) : PLAN_ANY;
    }

    /* ------------------------------------------------------------ week shapes */

    /**
     * S15 (the owner's decision, 2026-09-26): THE TWO WEEK SHAPES SETUP OFFERS when the length
     * track is on. The guidance allows either - length days and girth days in turn, or
     * both combined.
     *
     * COMBINED is what setup always wrote: the days picked, each left at its plan (PLAN_ANY,
     * "Plan's choice", on a new install) - both tracks on every training day.
     * ALTERNATE sets each picked day's plan automatically ({@link #alternatePlan}).
     *
     * Nothing about the shape is stored: it is the per-day plan it writes, which is already
     * saved and already read by Up next, the miss policy and the reminders.
     */
    public static final int SHAPE_COMBINED = 0, SHAPE_ALTERNATE = 1;

    /** {@link #alternatePlan(boolean[], int)} with length leading - the default. */
    public static int[] alternatePlan(boolean[] days) {
        return alternatePlan(days, PLAN_LENGTH);
    }

    /**
     * THE PICKED DAYS, LENGTH AND GIRTH IN TURN, Monday first, starting with `lead`. A day not
     * picked is left at {@link #PLAN_ANY} - a plan on a rest day is never consulted.
     *
     * WHICH TRACK LEADS IS THE PERSON'S ANSWER (the owner's decision, 2026-09-26): it is NOT IN
     * THE GUIDANCE, so setup asks {@link #ALTERNATE_LEAD_QUESTION} with neither answer marked.
     * `lead` is {@link #PLAN_LENGTH} or {@link #PLAN_GIRTH}; anything else - no answer yet -
     * starts with length, the default the S15 build chose: length is the every-other-day
     * track (four days a week in the guidance) and girth runs three to five, so an odd number
     * of days gives the extra one to length.
     */
    public static int[] alternatePlan(boolean[] days, int lead) {
        int[] out = new int[DAYS];
        if (days == null || days.length != DAYS) return out;          // all PLAN_ANY
        boolean length = lead != PLAN_GIRTH;
        for (int i = 0; i < DAYS; i++) {
            if (!days[i]) continue;
            out[i] = length ? PLAN_LENGTH : PLAN_GIRTH;
            length = !length;
        }
        return out;
    }

    /** What setup asks when the alternate week is picked (the owner's decision, 2026-09-26). */
    public static final String ALTERNATE_LEAD_QUESTION = "Start the week with Length or Girth?";

    /**
     * THE DAYS THE ALTERNATE SHAPE STARTS FROM: Monday to Saturday. Alternated, that is
     * length 3 days and girth 3 - length at the guidance's note that some need only three
     * days, girth inside its three to five - and 6 training days, the top of the four to six
     * it gives beginners, with Sunday for rest. The
     * three Mon/Wed/Fri days the combined shape starts from would alternate to one girth day.
     */
    public static boolean[] alternateDays() {
        return new boolean[]{ true, true, true, true, true, true, false };
    }

    /** Writes a week shape: the days picked (copied), and for {@link #SHAPE_ALTERNATE} the
     *  per-day plan {@link #alternatePlan} derives. {@link #SHAPE_COMBINED} leaves every
     *  day's plan as it was - which is what setup always did. */
    public void applyWeekShape(int shape, boolean[] picked) {
        applyWeekShape(shape, picked, PLAN_LENGTH);
    }

    /** The same, with the track the person chose to lead an alternate week
     *  ({@link #alternatePlan(boolean[], int)}); a combined week has no lead. */
    public void applyWeekShape(int shape, boolean[] picked, int lead) {
        if (picked == null || picked.length != DAYS) return;
        for (int i = 0; i < DAYS; i++) days[i] = picked[i];
        if (shape == SHAPE_ALTERNATE) {
            int[] p = alternatePlan(picked, lead);
            for (int i = 0; i < DAYS; i++) plan[i] = p[i];
        }
    }

    /* ------------------------------------------------------ long training days */

    /**
     * LONG TRAINING DAYS (t10 R-60, the owner's decision, 1 Oct 2026) - how the two tracks
     * share the week when both are on. A Trainer page setting, also asked by the setup's week
     * step. With one track on it is hidden and acts as {@link #LONG_COMBINED} (K21).
     *
     *   {@link #LONG_SPLIT} - "Alternate days, each track 3 days a week": girth Mon/Wed/Fri,
     *       length Tue/Thu/Sat, Sunday a rest day.
     *   {@link #LONG_ALTERNATE} - "Alternate on my days": the tracks take turns across the
     *       person's own days.
     *   {@link #LONG_CAP90} - "Same days, stop growing at 90 min": both tracks each day; the
     *       volume waits once a step would take the day past 90 minutes (TrainerTab#heldAt90).
     *   {@link #LONG_COMBINED} - "Same days, as now": both tracks each day, as before.
     *
     * THE DAYS FOLLOW IT ({@link #planAt}, {@link #isTrainingDay}, {@link #countForTrack}
     * and every reader of {@link #trainsOn} / {@link #planOn}), and the stored {@link #days}
     * and {@link #plan} stay the person's own: "Alternate days, each track 3 days a week"
     * runs Monday to Saturday whatever is ticked, and the ticks are there again for the
     * three choices that use them. Nothing is rewritten to make a choice true, so changing
     * it back loses nothing.
     *
     * DEFAULTS. A new setup writes {@link #LONG_DAYS_NEW_SETUP} (SPLIT). An old file is
     * decided by {@link #longDaysFor} from the week it already has (K18, K22), so an upgrader
     * keeps the days they ran; a fresh {@code Schedule} - the all-days, "plan's choice"
     * migration default - is what that gives it, {@link #LONG_COMBINED}.
     */
    public static final int LONG_SPLIT = 0, LONG_ALTERNATE = 1, LONG_CAP90 = 2, LONG_COMBINED = 3;
    /** What a new setup starts with (R-60): alternate days, each track 3 days a week. */
    public static final int LONG_DAYS_NEW_SETUP = LONG_SPLIT;

    /** The person's Long training days, one of the four above. */
    public int longDays = LONG_COMBINED;

    /**
     * WHAT THE SCHEDULE CANNOT KNOW BY ITSELF, AND IS TOLD (t10 R-60). Not saved: they are the
     * model's facts, copied in by {@link #follow} wherever the model is bounded
     * (Model#clampAll, so on every load) and wherever they change (the setup, "Length
     * first", a schedule save).
     *
     * {@link #bothTracks} - the plan runs both tracks (enrolled, girth on and length on). Off,
     * every Long training days acts as {@link #LONG_COMBINED} (K21): with one track there is
     * nothing to alternate, and a schedule with no trainer behind it is the person's own
     * week. FALSE BY DEFAULT, so a schedule nobody told anything reads exactly as it did
     * before the setting existed.
     *
     * {@link #lengthLeads} - "Length first" (Model#rxLengthFirst), which leads an
     * "Alternate on my days" week.
     */
    public boolean bothTracks = false;
    public boolean lengthLeads = true;

    /** Copies in the model's two facts above. */
    public void follow(boolean bothTracksOn, boolean lengthFirst) {
        bothTracks = bothTracksOn;
        lengthLeads = lengthFirst;
    }

    /** The Long training days the week runs by: the person's, with both tracks on; "as now"
     *  otherwise (K21). */
    public int longDaysInForce() {
        return bothTracks ? longDays : LONG_COMBINED;
    }

    /** "Alternate days, each track 3 days a week" for one weekday: girth Monday, Wednesday
     *  and Friday, length Tuesday, Thursday and Saturday, Sunday at rest (PLAN_ANY, never
     *  consulted). */
    public static int splitPlan(int weekday) {
        return splitPlan(weekday, false);
    }

    /** {@link #splitPlan(int)}, or with the two tracks' days swapped when `lengthFirst`:
     *  length Monday, Wednesday and Friday, girth Tuesday, Thursday and Saturday. */
    public static int splitPlan(int weekday, boolean lengthFirst) {
        if (weekday < MON || weekday >= SUN) return PLAN_ANY;
        boolean girth = weekday % 2 == 0;
        if (lengthFirst) girth = !girth;
        return girth ? PLAN_GIRTH : PLAN_LENGTH;
    }

    /**
     * WHETHER THE SPLIT WEEK KEEPS LENGTH ON MONDAY, WEDNESDAY AND FRIDAY: the stored week is
     * exactly Monday to Saturday alternating with length first - what the old setup's
     * "Alternate days" (whose default lead was length) and the old 90-minute switch wrote, and
     * what the migration maps to {@link #LONG_SPLIT} (K22). Those people keep each track on
     * the days it already had; a week stored any other way runs girth first, as the setting
     * says. The days and plan are the person's own and are never rewritten for this.
     */
    public boolean splitLengthFirst() {
        return java.util.Arrays.equals(days, alternateDays())
            && alternatesOn(days, plan, PLAN_LENGTH);
    }

    /** Whether weekday `w` (MON..SUN) trains, as the week in force has it: Monday to
     *  Saturday under {@link #LONG_SPLIT}, the person's own days otherwise. */
    public boolean trainsOn(int w) {
        if (w < 0 || w >= DAYS) return false;
        if (longDaysInForce() == LONG_SPLIT) return w != SUN;
        return days[w];
    }

    /** What weekday `w` runs, as the week in force has it - {@link #splitPlan} under
     *  {@link #LONG_SPLIT}, the person's days in turn under {@link #LONG_ALTERNATE} (led by
     *  {@link #alternateLead}), the day's own plan otherwise. Read for a training day; a day
     *  that does not train answers its stored plan, which is not consulted. */
    public int planOn(int w) {
        if (w < 0 || w >= DAYS) return PLAN_ANY;
        switch (longDaysInForce()) {
            case LONG_SPLIT:
                return splitPlan(w, splitLengthFirst());
            case LONG_ALTERNATE:
                return days[w] ? alternatePlan(days, alternateLead())[w] : plan[w];
            default:
                return plan[w];
        }
    }

    /**
     * THE TRACK THAT LEADS AN "ALTERNATE ON MY DAYS" WEEK (t10 O2, the owner's decision, 1 Oct
     * 2026): the stored week's own lead ({@link #storedLead}) - an upgrader's old alternate
     * week, whose lead was only ever written into each day's plan, runs as it was and nothing
     * moves, and a day ticked or unticked later keeps the same track leading - and
     * {@link #lengthLeads} ("Length first") otherwise, as for a week set up under the setting.
     * A lead the person chooses later is written by {@link #leadWith}, so it is theirs.
     */
    public int alternateLead() {
        int stored = storedLead();
        if (stored != PLAN_ANY) return stored;
        return lengthLeads ? PLAN_LENGTH : PLAN_GIRTH;
    }

    /**
     * The lead an alternation stored in the per-day plan has: the days whose plan names girth
     * or length, Monday first, taking turns - two of them at least, as
     * {@link #longDaysFor} asks of an alternate week - led by the first of them.
     * {@link #PLAN_ANY} when the plan holds no such alternation.
     */
    public int storedLead() {
        int first = PLAN_ANY, prev = PLAN_ANY, named = 0;
        for (int i = 0; i < DAYS; i++) {
            int p = plan[i];
            if (p != PLAN_GIRTH && p != PLAN_LENGTH) continue;
            if (p == prev) return PLAN_ANY;
            if (first == PLAN_ANY) first = p;
            prev = p;
            named++;
        }
        return named >= 2 ? first : PLAN_ANY;
    }

    /**
     * THE PERSON CHOSE WHICH TRACK LEADS ("Length first", the setup's lead answer): told to the
     * week, and an alternation stored under "Alternate on my days" rewritten to it over the
     * days ticked - so the choice is not overruled by the old lead {@link #alternateLead}
     * keeps (O2). Any other week's plan is the person's own and is not touched. The caller
     * saves.
     */
    public void leadWith(boolean lengthFirst) {
        lengthLeads = lengthFirst;
        if (longDays != LONG_ALTERNATE || storedLead() == PLAN_ANY) return;
        int[] p = alternatePlan(days, lengthFirst ? PLAN_LENGTH : PLAN_GIRTH);
        for (int i = 0; i < DAYS; i++) plan[i] = p[i];
    }

    /** {@link #trainsOn} for the whole week, MON..SUN. */
    public boolean[] daysInForce() {
        boolean[] out = new boolean[DAYS];
        for (int i = 0; i < DAYS; i++) out[i] = trainsOn(i);
        return out;
    }

    /** {@link #planOn} for the whole week, MON..SUN. */
    public int[] planInForce() {
        int[] out = new int[DAYS];
        for (int i = 0; i < DAYS; i++) out[i] = planOn(i);
        return out;
    }

    /** Whether the per-day plans are the setting's rather than the person's: under either
     *  alternate choice, in force (Settings shows "Set by Long training days"). */
    public boolean planSetByLongDays() {
        int ld = longDaysInForce();
        return ld == LONG_SPLIT || ld == LONG_ALTERNATE;
    }

    /**
     * THE MIGRATION MAPPING (R-60, K22): what a week saved before the setting existed already
     * was.
     *  - Monday to Saturday trained, Sunday not, the days alternating girth and length (either
     *    track first) - what the setup's "Alternate days" and the 90-minute card's switch
     *    write ({@link #alternateDays}, {@link #alternatePlan}) - is {@link #LONG_SPLIT}.
     *  - Two or more days alternating girth and length on any other days is
     *    {@link #LONG_ALTERNATE}.
     *  - Anything else ("the plan's choice", Both, a mix, or one day) is {@link #LONG_COMBINED}.
     */
    public static int longDaysFor(boolean[] days, int[] plan) {
        if (days == null || plan == null || days.length != DAYS || plan.length != DAYS)
            return LONG_COMBINED;
        int ticked = 0;
        for (int i = 0; i < DAYS; i++) if (days[i]) ticked++;
        if (ticked < 2) return LONG_COMBINED;
        if (!alternatesOn(days, plan, PLAN_LENGTH) && !alternatesOn(days, plan, PLAN_GIRTH))
            return LONG_COMBINED;
        return java.util.Arrays.equals(days, alternateDays()) ? LONG_SPLIT : LONG_ALTERNATE;
    }

    /** Whether every trained day's plan is what {@link #alternatePlan} gives with `lead`. */
    private static boolean alternatesOn(boolean[] days, int[] plan, int lead) {
        int[] want = alternatePlan(days, lead);
        for (int i = 0; i < DAYS; i++) if (days[i] && plan[i] != want[i]) return false;
        return true;
    }

    public static String planLabel(int p) {
        switch (p) {
            case PLAN_GIRTH:  return "Girth";
            case PLAN_LENGTH: return "Length";
            case PLAN_BOTH:   return "Both";
            case PLAN_REST:   return "Rest";
            default:          return "Plan’s choice";
        }
    }

    /** The local hour and minute a reminder is due, 24h. 19:00 by default. */
    public int hour = 19, minute = 0;

    /** Reminder notifications. OPT-IN: false on a fresh install and false on every old
     *  file, because a notification the user never asked for is not a default anyone is
     *  entitled to set for them. */
    public boolean remind = false;

    public Schedule() {
        for (int i = 0; i < DAYS; i++) days[i] = true;
    }

    /** The migration default, named so call sites read as what they mean. */
    public static Schedule allDays() { return new Schedule(); }

    public Schedule copy() {
        Schedule s = new Schedule();
        s.setFrom(this);
        return s;
    }

    /** Takes every figure of `o` - the days, each day's plan (so the week's shape and which
     *  track leads it), today's override, the reminder time and whether it reminds - into this
     *  schedule, in place (the object others hold stays the one they hold). */
    public void setFrom(Schedule o) {
        if (o == null) return;
        for (int i = 0; i < DAYS; i++) { days[i] = o.days[i]; plan[i] = o.plan[i]; }
        overrideDayKey = o.overrideDayKey; overridePlan = o.overridePlan;
        hour = o.hour; minute = o.minute; remind = o.remind;
        longDays = o.longDays;
        bothTracks = o.bothTracks; lengthLeads = o.lengthLeads;
    }

    /** The same week as `o`, figure for figure ({@link #setFrom}'s figures). */
    public boolean sameAs(Schedule o) {
        if (o == null) return false;
        for (int i = 0; i < DAYS; i++)
            if (days[i] != o.days[i] || plan[i] != o.plan[i]) return false;
        return overrideDayKey == o.overrideDayKey && overridePlan == o.overridePlan
            && hour == o.hour && minute == o.minute && remind == o.remind
            && longDays == o.longDays;
    }

    /* --------------------------------------------------------------- weekdays */

    /**
     * The weekday index (MON=0..SUN=6) of a day number from {@link Summary#dayNumber}.
     *
     * A Julian day number advances by exactly one per local calendar day, so its residue
     * mod 7 is the weekday — and the phase happens to land Monday on 0: JDN 2440592 is
     * 1970-01-05, a Monday, and 2440592 % 7 == 0. That is arithmetic, not a coincidence
     * to be trusted, so SelfTest cross-checks this against Calendar.DAY_OF_WEEK on real
     * dates including a leap day rather than only against itself.
     */
    public static int weekdayOf(long dayNumber) {
        int w = (int) (dayNumber % 7);
        return w < 0 ? w + 7 : w;
    }

    /** The weekday index of the LOCAL day an instant falls on. */
    public static int weekdayAt(long ts) { return weekdayOf(Summary.dayNumber(ts)); }

    /** Whether the given day number is a training day. */
    public boolean trainsOnDay(long dayNumber) {
        return trainsOn(weekdayOf(dayNumber));
    }

    /** Whether the LOCAL date an instant falls on is a training day. */
    public boolean isTrainingDay(long ts) { return trainsOnDay(Summary.dayNumber(ts)); }

    /** Whether the day `now` falls on is a scheduled training day. */
    public boolean isTodayTraining(long now) { return isTrainingDay(now); }

    /** How many weekdays are selected. 0 means the schedule is EMPTY, which every caller
     *  has to handle explicitly: no reminders fire, and the streak has no scheduled day
     *  to count (see {@link Summary}). */
    public int count() {
        int n = 0;
        for (int i = 0; i < DAYS; i++) if (trainsOn(i)) n++;
        return n;
    }

    public boolean any() { return count() > 0; }

    /**
     * HOW MANY SELECTED DAYS ACTUALLY ASK FOR THIS TRACK.
     *
     * {@link #count} counts every selected weekday, whatever it is scheduled to run. The miss
     * policy compared that against girth sessions, so on a six-day schedule of three girth
     * and three length days a user who did every girth day they were asked for was charged
     * three misses. And the default schedule is ALL SEVEN - chosen for a streak-migration
     * reason, never as a prescription - so a compliant five-day trainer was charged two
     * misses every week, for ever.
     */
    public int countForTrack(int track) {
        int n = 0;
        for (int i = 0; i < DAYS; i++) if (trainsTrackOn(i, track)) n++;
        return n;
    }

    /** Whether weekday `w` is a training day that runs `track` (either girth style for a girth
     *  track). PLAN_ANY is "whatever the plan is running", so it counts for either track -
     *  that is what it meant before per-day plans existed and what an untouched file still
     *  means. PLAN_BOTH asks for both. Otherwise the day names its own track. */
    public boolean trainsTrackOn(int w, int track) {
        if (!trainsOn(w)) return false;
        int pl = planOn(w);
        boolean girth = (track != Plan.TRACK_LENGTH);
        return pl == PLAN_ANY || pl == PLAN_BOTH
            || (girth && pl == PLAN_GIRTH) || (!girth && pl == PLAN_LENGTH);
    }

    /** The last weekday (0 = Monday .. 6 = Sunday) the week runs `track` on; -1 when none.
     *  Week B (TrainingWeek): two full sessions count only once this day is done. */
    public int lastWeekdayFor(int track) {
        for (int i = DAYS - 1; i >= 0; i--) if (trainsTrackOn(i, track)) return i;
        return -1;
    }

    /**
     * The next training DAY NUMBER strictly after `fromDayNumber`, or -1 when no day is
     * selected at all. Never returns the day it was given: "next" means next, and the
     * caller that wants "today if today counts" asks {@link #trainsOnDay} first — one
     * question each, so neither can be mistaken for the other.
     */
    public long nextTrainingDay(long fromDayNumber) {
        if (!any()) return -1;
        for (int i = 1; i <= DAYS; i++)
            if (trainsOnDay(fromDayNumber + i)) return fromDayNumber + i;
        return -1;   // unreachable while any() holds — a week always contains every weekday
    }

    /** The next training day number at or after `fromDayNumber` (today counts), or -1. */
    public long trainingDayOnOrAfter(long fromDayNumber) {
        if (!any()) return -1;
        for (int i = 0; i <= DAYS; i++)
            if (trainsOnDay(fromDayNumber + i)) return fromDayNumber + i;
        return -1;
    }

    /* ------------------------------------------------------------ fire instants */

    /**
     * The next `count` reminder instants strictly after `now` — the exact local
     * milliseconds an alarm should fire at, given this schedule and this hour.
     *
     * This is the part of the notification feature that can be wrong SILENTLY: an alarm
     * set for a day the user does not train, or set for a time already past so it fires
     * immediately, is not visible in any screenshot. So the arithmetic is here and
     * asserted, and the AlarmManager wiring is reduced to "set an alarm at the number
     * this returned".
     *
     * Empty schedule → an empty array, never "today at the hour": nothing is scheduled,
     * so nothing is due. STRICTLY after `now` on purpose — an alarm at exactly now, or a
     * minute ago, would fire the instant it is set, which is how a "reminder" turns into
     * a notification the user gets every time they open Settings.
     */
    public long[] nextFires(long now, int count) {
        if (count <= 0 || !any()) return new long[0];
        long[] out = new long[count];
        int found = 0;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        // Up to a fortnight of candidate days per wanted instant is more than enough: the
        // sparsest possible non-empty schedule is one day a week.
        for (int i = 0; i <= 7 * (count + 1) && found < count; i++) {
            Calendar d = (Calendar) c.clone();
            d.add(Calendar.DAY_OF_MONTH, i);
            long t = d.getTimeInMillis();
            if (t > now && trainsOnDay(Summary.dayNumber(t))) out[found++] = t;
        }
        if (found == count) return out;
        long[] trimmed = new long[found];
        System.arraycopy(out, 0, trimmed, 0, found);
        return trimmed;
    }

    /**
     * The next local instant at `hour`:`minute` STRICTLY AFTER `now` — today's if it has
     * not passed, otherwise tomorrow's.
     *
     * Static and schedule-independent, because its one caller (the MEASUREMENT reminder,
     * Reminders#rescheduleMeas) has no weekdays to consult: the measurement cadence counts
     * sessions and hours, so the alarm is checked daily and the cadence decides at the
     * moment it arrives. It lives here, next to {@link #nextFires}, because it is the same
     * kind of arithmetic and fails the same silent way — an instant at or before `now`
     * fires the moment the alarm is set, which is how a "reminder" becomes a notification
     * every time Settings is opened.
     *
     * A day is advanced through {@link Calendar}, never by adding 86400000: across a
     * daylight-saving boundary a fixed-millisecond day lands an hour out, and the whole
     * point of this is to arrive at the hour the user chose.
     */
    public static long nextDailyAt(long now, int hour, int minute) {
        int h = hour < 0 ? 0 : (hour > 23 ? 23 : hour);
        int mi = minute < 0 ? 0 : (minute > 59 ? 59 : minute);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, mi);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** The single next reminder instant after `now`, or -1 when nothing is scheduled. */
    public long nextFire(long now) {
        long[] f = nextFires(now, 1);
        return f.length == 0 ? -1 : f[0];
    }

    /* ------------------------------------------------------------------- words */

    /** "19:00" — the hour as the settings row and the nudge print it. */
    public String hhmm() {
        return String.format(Locale.US, "%02d:%02d", hour, minute);
    }

    /** "Mon, Wed, Fri" / "Every day" / "No days selected" — one derivation so Settings
     *  and the schedule's accessible name cannot describe the same schedule differently. */
    public String daysLine() {
        int n = count();
        if (n == 0) return "No days selected";
        if (n == DAYS) return "Every day";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DAYS; i++) {
            if (!trainsOn(i)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(DAY_ABBR[i]);
        }
        return sb.toString();
    }

    /** The one sentence a screen reader hears for the whole seven-toggle row. */
    public String said() {
        return "Training days: " + daysLine() + ". Training time " + hhmm()
             + ". Reminders " + (remind ? "on" : "off") + ".";
    }

    /* -------------------------------------------------------------- next due */

    /**
     * TODAY's one-line next-due nudge: "Next session: today at 19:00" / "Tomorrow (Wed)" /
     * "Today — done". Derived from the schedule and the session log together, so it can
     * never claim a session is due on a day that is not scheduled, and never nags for one
     * that has already been filed.
     *
     * `doneToday` is the caller's answer to "has a completed routine session been filed on
     * the local day `now` falls on" — {@link Summary#advancesStreak} negated, which is the
     * single gate the whole app already uses for that question.
     */
    public String nextDueLine(long now, boolean doneToday) {
        if (!any()) return "No training days set — choose them in Settings";
        long today = Summary.dayNumber(now);
        if (trainsOnDay(today)) {
            if (doneToday) return "Today — done ✓";
            return "Next session: today at " + hhmm();
        }
        long next = nextTrainingDay(today);
        String when = (next == today + 1) ? "tomorrow" : DAY_ABBR[weekdayOf(next)];
        return "Next session: " + when + " (" + DAY_ABBR[weekdayOf(next)] + ") at " + hhmm();
    }

    /* -------------------------------------------------------------- clamp/json */

    /** The seven-day flags as the seven characters they are persisted as — "1111111" is
     *  every day, "1010100" is Mon/Wed/Fri. */
    public String mask() {
        StringBuilder sb = new StringBuilder(DAYS);
        for (int i = 0; i < DAYS; i++) sb.append(days[i] ? '1' : '0');
        return sb.toString();
    }

    /** A schedule from that same seven-character mask — the inverse of {@link #mask},
     *  used by SelfTest to state a fixture's week in one literal. */
    public static Schedule ofMask(String m) {
        Schedule s = new Schedule();
        if (m != null && m.length() == DAYS)
            for (int i = 0; i < DAYS; i++) s.days[i] = m.charAt(i) == '1';
        return s;
    }

    /** Bounds applied at WRITE time, and a load is a write — the same rule
     *  {@link Model#clampAll} states. A hand-edited or corrupted file cannot produce an
     *  hour of 30 that then gets printed against a row claiming 0–23. */
    public void clamp() {
        hour = hour < 0 ? 0 : (hour > 23 ? 23 : hour);
        minute = minute < 0 ? 0 : (minute > 59 ? 59 : minute);
        // A reminder with no day to fire on is not a reminder. Turned off rather than
        // left true-but-inert, so the settings row says what is actually true.
        if (!any()) remind = false;
        // A Long training days that is none of the four is read as "as now": the one that
        // changes nobody's days.
        if (longDays < LONG_SPLIT || longDays > LONG_COMBINED) longDays = LONG_COMBINED;
    }

    private String planMask() {
        StringBuilder b = new StringBuilder(DAYS);
        for (int i = 0; i < DAYS; i++) b.append((char) ('0' + plan[i]));
        return b.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("days", mask());
        o.put("plan", planMask());
        o.put("ovrDay", overrideDayKey);
        o.put("ovrPlan", overridePlan);
        o.put("hour", hour);
        o.put("min", minute);
        o.put("remind", remind);
        o.put("longDays", longDays);
        return o;
    }

    /**
     * fromJson(null) is the MIGRATION: all seven days, 19:00, reminders off. A missing
     * key means "never chose", and for the schedule the only choice that leaves an
     * existing streak untouched is every day.
     */
    public static Schedule fromJson(JSONObject o) {
        Schedule s = new Schedule();
        if (o == null) return s;
        // Seven characters, '1' = trains. A STRING rather than a JSON array of booleans
        // because the desktop org.json shim has no optBoolean(int) — the same reason
        // Model wraps its nested objects in one-element arrays. Anything that is not
        // exactly seven characters is not a schedule this app wrote, so it falls back to
        // the migration default rather than to a partially-decoded week.
        String d = o.optString("days", "");
        if (d.length() == DAYS)
            for (int i = 0; i < DAYS; i++) s.days[i] = d.charAt(i) == '1';
        // Seven digits, one per day, same shape and same reason as the day mask. Absent -
        // and anything that is not exactly seven valid digits - means all PLAN_ANY, which
        // is what every day did before the planner existed.
        String p = o.optString("plan", "");
        if (p.length() == DAYS) {
            for (int i = 0; i < DAYS; i++) {
                int v = p.charAt(i) - '0';
                s.plan[i] = (v >= 0 && v < PLANS) ? v : PLAN_ANY;
            }
        }
        // An override from a day that has passed is simply never consulted again, so it
        // needs no expiry: planAt asks whether it is for TODAY before reading it.
        s.overrideDayKey = o.optInt("ovrDay", 0);
        int op = o.optInt("ovrPlan", PLAN_ANY);
        s.overridePlan = (op >= 0 && op < PLANS) ? op : PLAN_ANY;
        s.hour = o.optInt("hour", 19);
        s.minute = o.optInt("min", 0);
        s.remind = o.optBoolean("remind");
        // MIGRATION (t10 R-60): absent on every older week - what that week already was
        // (longDaysFor). clamp() reads a hand-edited value outside the four as "as now".
        s.longDays = o.has("longDays") ? o.optInt("longDays", LONG_COMBINED)
                                       : longDaysFor(s.days, s.plan);
        s.clamp();
        return s;
    }
}
