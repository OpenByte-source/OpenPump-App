package org.openpump;

import java.util.List;

/**
 * THE TRAINER'S OWN SETUP ("Trainer setup n/6"), the parts of it that are words and rules
 * rather than views - pure, so they are pinned by TrainerOnboardTest.
 *
 * WHAT IS ALREADY SET IS SAID TO BE SET (0.10, from the owner's phone test). The first-run
 * setup asks for the cylinders and the week, and the trainer's setup asks again straight
 * after it. Asking again is fine; asking as if nothing had been answered is not - the week
 * step used to open on Mon/Wed/Fri at 19:00 whatever the person had just chosen. A step
 * whose answer is already on the model now opens on that answer, says it is set, and
 * offers an edit.
 */
public final class TrainerOnboard {
    private TrainerOnboard() { }

    /** The pinned step bar's name, and what it says in place of "n/6" on the confirmation. */
    public static final String BAR_TITLE = "Trainer setup";
    public static final String BAR_CONFIRM = "Confirm";

    /* ============================================================ already set */

    /** The small label over an answer the person already gave. */
    public static final String ALREADY_SET = "Already set";
    /** Under the week's summary: nothing changes unless they change it. */
    public static final String WEEK_KEPT = "Kept as it is unless you change it here.";
    /** The week's edit affordance. */
    public static final String WEEK_EDIT = "Edit days and time";

    /**
     * t10 device walk - UNDER THE WEEK'S SUMMARY, WHAT WILL APPLY. "Alternate days, each track 3
     * days a week" runs Monday to Saturday whatever days are ticked, so the days summarised are
     * not the person's - and "kept as it is" said they were. Where the days in force (`inForce`)
     * are the days ticked (`ticked`), #WEEK_KEPT; otherwise that Long training days sets them,
     * and that the time and reminders are kept.
     */
    public static String weekKept(boolean[] ticked, boolean[] inForce) {
        if (java.util.Arrays.equals(ticked, inForce)) return WEEK_KEPT;
        return "Set by " + LongDays.ROW + ": these days, whatever is ticked. Your time and "
            + "reminders are kept unless you change them here.";
    }
    /** Under the rack's summary. */
    public static final String CYL_KEPT = "Kept as it is. Edit a cylinder below, or add one.";

    /**
     * HAS THE PERSON ALREADY CHOSEN A WEEK? True once enrolled (the trainer wrote it), and
     * true for any schedule that is not the app's own untouched default - every day, 19:00,
     * no reminders - which is what an old file loads with and what nobody picked. The
     * first-run setup starts a new phone on Mon/Wed/Fri and shows it to the person, so a
     * phone that walked the setup reads as chosen.
     */
    public static boolean weekChosen(Schedule s, boolean enrolled) {
        if (enrolled) return true;
        if (s == null) return false;
        Schedule untouched = new Schedule();
        return !(s.count() == Schedule.DAYS && !s.remind
                 && s.hour == untouched.hour && s.minute == untouched.minute);
    }

    /* ============================================ the upgrade card's setup (t10 R-61) */

    /**
     * THE ANSWERS THAT PLACE THE PERSON - what Confirm derives the position from (level, week,
     * working pressure and its clock, earned and carried holds): the months, the two pressure
     * answers, the girth style, which tracks run, new to pumping and a recent layoff. Every
     * other answer (the variants, the programs, the rack, the maximums, the week) is written
     * without moving the position.
     */
    public static final class Position {
        public int months, girthStyle;
        public double girthKpa, lengthKpa;
        public boolean girthOn, lengthOn, isNew, layoff;
        /** R11-3: the minutes answered for each track's current session (Placement). */
        public int girthMin = Placement.NOT_ANSWERED, lengthMin = Placement.NOT_ANSWERED;

        public Position copy() {
            Position p = new Position();
            p.months = months; p.girthStyle = girthStyle;
            p.girthMin = girthMin; p.lengthMin = lengthMin;
            p.girthKpa = girthKpa; p.lengthKpa = lengthKpa;
            p.girthOn = girthOn; p.lengthOn = lengthOn; p.isNew = isNew; p.layoff = layoff;
            return p;
        }
    }

    /**
     * t10 review D, F1 - "YOUR VALUES ARE KEPT" (R-61): the setup the upgrade card runs was
     * seeded with the plan as it stands (`seeded`); when every answer that places the person is
     * still that seed (`now`), Confirm keeps the position and writes the other answers only.
     * Re-deriving it would drop the holds a person earned through the gates, restart the
     * pressure clock and could place them a level lower than they reached - for an upgrader
     * who only came to see the new choices. Any of them changed, the person is placed from the
     * answers, as a recalibration does. A layoff answered is a step back, never a keep. The
     * length pressure counts only with length on (it is not asked otherwise); two unanswered
     * ones are the same answer.
     */
    public static boolean positionKept(Position seeded, Position now) {
        if (seeded == null || now == null || now.layoff) return false;
        if (seeded.months != now.months || seeded.girthStyle != now.girthStyle
                || seeded.girthOn != now.girthOn || seeded.lengthOn != now.lengthOn
                || seeded.isNew != now.isNew || seeded.girthMin != now.girthMin)
            return false;
        if (now.lengthOn && seeded.lengthMin != now.lengthMin) return false;
        if (!sameAnswer(seeded.girthKpa, now.girthKpa)) return false;
        return !now.lengthOn || sameAnswer(seeded.lengthKpa, now.lengthKpa);
    }

    private static boolean sameAnswer(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) return Double.isNaN(a) && Double.isNaN(b);
        return Math.abs(a - b) < 1e-6;
    }

    /* ============================================ R11-3: placed by the session's length */

    public static final String SESSION_ROW_GIRTH = "Your girth session now";
    public static final String SESSION_ROW_LENGTH = "Your length session now";
    public static final String SESSION_NOTE =
        "How long your session of this track is now. The plan starts you there.";
    public static final String SESSION_INFO_TITLE = "Where the plan starts you";
    public static final String SESSION_INFO =
        "The plan starts each track at the level and week whose session, as the plan writes it, "
        + "is the closest to yours without being longer. 0 means you don't do this track yet: "
        + "it starts at Level 1, week 1.\n\nLeft at Skip, your months pumping place it, as "
        + "before. Your months still decide when Levels 3 and 4 open (month 6 and month 12), "
        + "and your pressure answers set the pressure.";
    public static final String SESSION_NEW =
        "New to pumping: each track starts at Level 1, week 1.";

    /** The stepper's value: "Skip", "I don't do this yet", "35 min". */
    public static String sessionValue(int min) {
        if (min < 0) return "Skip";
        if (min == 0) return "I don't do this yet";
        return min + " min";
    }

    /** The next answer a − or + gives: Skip, 0, then 5-minute steps to 180. */
    public static int stepSessionMin(int min, int dir) {
        if (dir < 0) return min <= 0 ? Placement.NOT_ANSWERED : Math.max(0, min - 5);
        if (min < 0) return 0;
        return Math.min(180, min + 5);
    }

    /** The confirmation's line for a placed track: "Placed by your 20-min session: its closest
     *  is 19.6 min." - or "" where it was placed by the months. */
    public static String placedLine(int answerMin, double sessionMin) {
        if (answerMin < 0) return "";
        if (answerMin == 0) return "You don't do this track yet, so it starts at the beginning.";
        return "Placed by your " + answerMin + "-min session: the plan's closest is "
            + Say.fmtMin(sessionMin) + " min.";
    }

    /* FIX11 F5 / F6 (device check EMU12) - THE CONFIRM SCREEN SAYS WHERE EACH TRACK STARTS, LEVEL
     * AND WEEK, AND WHAT PLACED IT. It said "Level L1" over "Month 7": the week the minutes
     * placed it at (week 6) showed only on the Trainer afterwards, and "Month 7" beside an L1
     * placement read as a contradiction - it is the months-pumping answer, which only opens
     * Levels 3 and 4. And the length answer placed the strain sets without a word. */

    /** The row the placed level and week are stated on. */
    public static final String PLACED_ROW = "Level and week";
    /** The months answer's row - what it is, not a "Month" beside a level. */
    public static final String MONTHS_ROW = "Months pumping";

    /** "Level 1". */
    public static String levelWords(int level) {
        return "Level " + Math.max(1, level);
    }

    /**
     * Where a track starts, as the confirm screen states it: "Level 1, week 6 — closest to your
     * 20 min", "Level 1, week 1 — new to pumping", "Level 1, week 1 — you don't do this track
     * yet", or, placed by the months, "Level 3, week 1". The week only where the level has one
     * (`week` above 0).
     */
    public static String placedAt(int level, int week, int answerMin, boolean isNew) {
        StringBuilder b = new StringBuilder(levelWords(level));
        if (week > 0) b.append(", week ").append(week);
        if (isNew) b.append(" — new to pumping");
        else if (answerMin == 0) b.append(" — you don't do this track yet");
        else if (answerMin > 0) b.append(" — closest to your ").append(answerMin).append(" min");
        return b.toString();
    }

    /**
     * The length track's placed line - ONE minutes figure besides the answer (device recheck
     * EMU13: "30 min", "the plan's closest is 45.1 min" and "10 min at pressure" read as three
     * answers to one question). What was answered, and what the plan placed: its strain sets and
     * that session's minutes as the plan writes it (Placement#lengthSessionMin, the definition
     * the placement compares with):
     *   "You answered 30 min: length starts at 4 strain sets, a 28.4-min session - the closest
     *    without going over."
     *   "You answered 30 min: length starts at 2 strain sets, its shortest session, 45.1 min."
     * The girth line's words for 0, why it is not placed without a length cylinder, and "" when
     * it was not answered.
     */
    public static String lengthPlacedLine(int answerMin, boolean pulls, int strainSets,
                                          double sessionMin) {
        if (answerMin < 0) return "";
        if (answerMin == 0) return "You don't do this track yet, so it starts at the beginning.";
        if (!pulls) return "Your " + answerMin + "-min answer places length by its strain sets, "
            + "and with no length cylinder to pull with there are none: your months place it.";
        String sets = strainSets + (strainSets == 1 ? " strain set" : " strain sets");
        String head = "You answered " + answerMin + " min: length starts at " + sets + ", ";
        if (sessionMin <= answerMin + 1e-9)
            return head + "a " + Say.fmtMin(sessionMin) + "-min session — the closest "
                + "without going over.";
        return head + "its shortest session, " + Say.fmtMin(sessionMin) + " min.";
    }

    /** What the confirmation says instead of a derived position when it is kept. */
    public static final String POSITION_KEPT = "Your position is kept";
    public static final String POSITION_KEPT_NOTE = "Your answers about where you are did not "
        + "change, so your level, week, pressure and the holds you have earned stay as they are. "
        + "Change one of those answers to be placed from your answers again.";

    /** "Mon, Wed, Fri · 19:00 · reminders on" - the week as the step will write it. */
    public static String weekSummary(boolean[] days, int hour, int minute, boolean remind) {
        Schedule t = new Schedule();
        for (int i = 0; i < Schedule.DAYS; i++) t.days[i] = days != null && i < days.length && days[i];
        t.hour = hour;
        t.minute = minute;
        return daysRuns(t) + " · " + t.hhmm() + " · "
            + (remind ? "reminders on" : "reminders off");
    }

    /** The week step's summary row's label (polish SU-13). */
    public static final String WEEK_ROW = "Days and time";

    /** The days as a person reads a run of them (polish SU-13): "Mon–Sat", "Mon, Wed, Fri",
     *  "Mon–Wed, Sat"; Schedule#daysLine's words for every day and none. Three or more days in
     *  a row are one run; two stay a list. */
    public static String daysRuns(Schedule s) {
        if (s == null) return "";
        int n = s.count();
        if (n == 0 || n == Schedule.DAYS) return s.daysLine();
        String[] abbr = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < Schedule.DAYS) {
            if (!s.days[i]) { i++; continue; }
            int j = i;
            while (j + 1 < Schedule.DAYS && s.days[j + 1]) j++;
            if (b.length() > 0) b.append(", ");
            if (j - i >= 2) b.append(abbr[i]).append('\u2013').append(abbr[j]);
            else {
                b.append(abbr[i]);
                if (j > i) b.append(", ").append(abbr[j]);
            }
            i = j + 1;
        }
        return b.toString();
    }

    /** "2 cylinders · Main cylinder in use", or "" for an empty rack. An unnamed cylinder is
     *  called by its place, as Settings calls it. */
    public static String cylindersSummary(List<Model.Cylinder> cyls, int active) {
        if (cyls == null || cyls.isEmpty()) return "";
        int n = cyls.size();
        int at = active < 0 || active >= n ? 0 : active;
        Model.Cylinder c = cyls.get(at);
        String name = c == null || c.label == null || c.label.trim().length() == 0
            ? "Cylinder " + (at + 1) : c.label.trim();
        return (n == 1 ? "1 cylinder" : n + " cylinders") + " · " + name + " in use";
    }

    /* ============================================================ load <-> pressure */

    /*
     * THE LENGTH LOAD AND THE LENGTH PRESSURE ARE ONE VALUE, SHOWN IN TWO FIELDS (owner,
     * 2026-09-30). Typing or stepping either sets the other, through the length cylinder's
     * BORE (Traction#loadLbAtBore / #kpaForLbAtBore) - the one fixed dimension in the system.
     * The step used to show a load stepper, a pressure stepper and an "erect girth for the
     * conversion" stepper: three numbers that could disagree, and a load answer that did not
     * move when the pressure did. The step holds the PRESSURE (the answer every derivation
     * reads); the load is that pressure in the length cylinder, and a load typed in is held as
     * the pressure that makes it. With no length cylinder listed yet there is nothing to
     * convert at, so the pressure is asked alone and a line says where the load will appear.
     */

    /** The load field's label, beside the pressure's ("Length working pressure"). */
    public static final String LOAD_LABEL = "Length load";
    /** Said in place of the load while no cylinder is marked for length - the trainer's own
     *  cylinder step is the next one, and the first-run setup normally listed them already. */
    public static final String NO_LENGTH_CYLINDER = "The load shows here once a length "
        + "cylinder is listed \u2014 the next step lists your cylinders.";
    /** The load stepper's step in pounds (the stepper this replaced stepped half a pound);
     *  in kilos it is half a kilo (Model.Fmt#loadStepLb, which the step's stepper reads). */
    public static final double LOAD_STEP_LB = 0.5;
    /** The least load the field goes to - the floor the setup's load answer always had. */
    public static final double LOAD_MIN_LB = 1.0;

    /** "Load and pressure are one figure, worked out at your length cylinder's 4.5 cm inside
     *  diameter." - the one line that names what the two are converted at. */
    public static String linkedBasis(double boreCm) {
        return "Load and pressure are one figure, worked out at your length cylinder's "
            + Model.Fmt.len(boreCm) + " inside diameter.";
    }

    /** What a new person is told instead of a load field: the plan starts the pulls at its own
     *  load (Scale#setupLoadLb ignores a load answer with New to pumping on). */
    public static String newLoadNote() {
        return "New to pumping: your pulls start at the plan's own "
            + Traction.settingLb(Plan.LENGTH_LOAD_START_LB) + " and step up from there.";
    }

    /** The load the length pressure makes in the length cylinder - 0 with no bore. */
    public static double linkedLoadLb(double kpa, double boreCm) {
        if (boreCm <= 0 || kpa <= 0) return 0.0;
        return Traction.loadLbAtBore(kpa, boreCm);
    }

    /**
     * ONE STEP OF THE LOAD FIELD, onto the step's grid: up from 11.8 is 12.0, down is 11.5, so
     * a load that came from a pressure lands on a round figure at the first tap and then moves
     * by whole steps. `stepLb` is the step in pounds.
     */
    public static double bumpLoadLb(double lb, int dir, double stepLb) {
        if (stepLb <= 0) return lb;
        double units = lb / stepLb;
        double k = dir > 0 ? Math.floor(units + 1e-6) + 1 : Math.ceil(units - 1e-6) - 1;
        return k * stepLb;
    }

    /** The pressure that makes this load in the length cylinder - unrounded, so the field
     *  shows the load that was asked for (the pump's whole kPa is taken where it is sent). */
    public static double kpaForLinkedLoad(double lb, double boreCm) {
        return Traction.kpaForLbAtBore(lb, boreCm);
    }

    /* ============================================================ length's own maximum */

    /*
     * LENGTH'S "MOST YOU WILL GO TO" STARTS FROM GIRTH'S (owner, 2026-09-30). c43c430 gave length
     * its own maximum (Model#rxLengthMaxKpa), but a new setup left it "not set", so somebody who
     * set only girth's had length capped by the ceiling alone. While the length track is on and
     * its maximum has not been touched, the length field shows girth's answer - a real value,
     * editable; once the person steps it, theirs is kept whatever girth's does. A file's saved
     * answers are not touched (Model#fromJson's migration is as it was): a length maximum already
     * saved counts as touched, so a recalibration keeps it.
     */

    /** Is a length maximum this setup starts from the person's own - one already saved? */
    public static boolean lengthMaxTouched(double savedLengthMaxKpa) {
        return savedLengthMaxKpa > 0;
    }

    /**
     * THE LENGTH MAXIMUM THE SETUP SHOWS AND SAVES: the person's own once touched; untouched,
     * girth's maximum while the length track is on (0, "not set", when girth's is), and 0 with
     * length off - so a length maximum is never saved for a track nobody turned on.
     */
    public static double lengthMaxSeed(boolean lengthOn, boolean touched, double lengthMaxKpa,
                                       double girthMaxKpa) {
        if (touched) return lengthMaxKpa;
        return lengthOn ? Math.max(0.0, girthMaxKpa) : 0.0;
    }

    /* ============================================================ the routines it offers */

    /*
     * EVERY ROUTINE THE SETUP JUST WROTE IS OFFERED (0.10). The end of the trainer's setup used
     * to offer the girth routine alone, though the plan had written a length (or traction)
     * routine and, from Level 3, a feeder too - those waited on the Trainer tab for somebody
     * to find them. The offer now lists each, ticked, with Save all; nothing is saved without
     * the person's tap, and each goes through the Trainer card's own save.
     */

    /**
     * WOULD THE TRAINER'S OWN CARD OFFER TO SAVE THIS? The same answer the card gives: a
     * prescription, and a decision that is an offer to save rather than a question of its own
     * - a level to cross, a load to raise, a deload to start, a re-measure, a girth block, a
     * step back or a ceiling that blocks. The feeder is offered only when suggested.
     */
    public static boolean offerable(int track, int action, boolean hasRx, boolean resting) {
        if (!hasRx) return false;
        /* A DATED REST IS AN ANSWER ALREADY GIVEN (the safety review, 0.10): a track on a
         * dated break offers nothing and saves nothing until it ends - the rule the Trainer's
         * own rows (lengthSpeaks), the plan's rewrites (syncPlanRoutines) and TrainerTab
         * #lengthLive keep. Somebody who pauses and re-enrols inside a length break must not
         * be handed a ticked length routine, selected, during it. The rest stays on record. */
        if (resting) return false;
        if (track == Plan.TRACK_FEEDER) return action == Plan.ACTION_FEEDER_SUGGEST;
        switch (action) {
            case Plan.ACTION_CEILING_DEADLOCK:
            case Plan.ACTION_RAISE_LOAD:
            case Plan.ACTION_REMEASURE:
            case Plan.ACTION_GIRTH_FOCUS:
            case Plan.ACTION_DELOAD:
            case Plan.ACTION_LEVEL_UP:
            case Plan.ACTION_STEP_BACK:
            case Plan.ACTION_FEEDER_SUGGEST:
            case Plan.ACTION_FEEDER_PAUSED:
            case Plan.ACTION_DISABLED:
                return false;
            case Plan.ACTION_ADD_VOLUME:
                return track != Plan.TRACK_LENGTH;   // length's is a strain-set question
            default:
                return true;
        }
    }

    /** What a row of the closing sheet is: one to save, or one that says why not. */
    public static final int ROW_SAVE = 0, ROW_SAVED = 1, ROW_RESTING = 2, ROW_ASKS = 3,
                            ROW_NONE = 4;

    /**
     * EVERY ENABLED TRACK GETS A ROW (0.10, the owner's check on the emulator): the person
     * sees the whole plan, and a track with nothing to save says why - saved already and
     * unchanged, resting, asking its own question on the Trainer, or nothing prescribed - in
     * a row that is not ticked and cannot be. Resting is asked first: a dated rest is the
     * answer whatever else the track would say.
     */
    public static int rowState(int track, int action, boolean hasRx, boolean resting,
                               boolean alreadySaved) {
        if (resting) return ROW_RESTING;
        if (!hasRx) return ROW_NONE;
        if (!offerable(track, action, true, false))
            return track == Plan.TRACK_FEEDER ? ROW_NONE : ROW_ASKS;
        return alreadySaved ? ROW_SAVED : ROW_SAVE;
    }

    /** The line a row that cannot be saved shows in place of its routine. `restUntil` is the
     *  rest's end as the app prints dates; `action` the decision the Trainer is asking about. */
    public static String rowReason(int state, String restUntil, int action) {
        switch (state) {
            case ROW_SAVED: return "Already saved, no change";
            case ROW_RESTING:
                return restUntil == null || restUntil.length() == 0 ? "Resting"
                    : "Resting until " + restUntil;
            case ROW_ASKS:
                return "The Trainer has a question for this track first ("
                    + Say.actionLabel(action) + ") \u2014 answer it there";
            default: return "Nothing to save right now";
        }
    }

    /**
     * THE GUIDANCE'S TABLE MAY PRINT THIS WEEK AS A DELOAD ROW (interval girth, L1 and L2) -
     * the table is reference, and the engine's own deload is usage-linked, so the routine is
     * the last training week's (Mint#baseSets). Said on the row, so a routine offered in a
     * week the table calls a deload is labelled as that week's. "" when it is not one.
     */
    public static String tableDeloadNote(int track, int level, int weekIndex) {
        if (track != Plan.TRACK_GIRTH_INTERVAL) return "";
        Plan.Week[] table = level == Plan.L1 ? Plan.GIRTH_INTERVAL_L1
                          : level == Plan.L2 ? Plan.GIRTH_INTERVAL_L2 : null;
        if (table == null || weekIndex < 1 || weekIndex > table.length) return "";
        Plan.Week w = table[weekIndex - 1];
        if (!w.deload) return "";
        return "Week " + w.num + " is a deload week in the guidance\u2019s table. This is its "
            + "routine: your last training week\u2019s sets, until the plan\u2019s own deload "
            + "comes due.";
    }

    /**
     * THE WEEK THE ROUTINES ARE RUN ON, from the schedule itself (0.10: the sheet said "THREE
     * DAYS a week" over a four-day week). With both tracks on and the days split between
     * them, each track's own count; otherwise the training days. `many` for "them".
     */
    public static String weekRule(Schedule s, boolean girthOn, boolean lengthOn, int girthTrack,
                                  boolean many) {
        if (s == null || s.count() == 0) return "Choose your training days in Settings.";
        int n = s.count();
        if (girthOn && lengthOn) {
            int g = s.countForTrack(girthTrack), l = s.countForTrack(Plan.TRACK_LENGTH);
            if (g != n || l != n)
                return "Girth on " + days(g) + " and length on " + days(l) + " a week ("
                    + s.daysLine() + ").";
        }
        return "Run " + (many ? "them" : "it") + " on your " + days(n) + " a week: "
            + s.daysLine() + ".";
    }

    private static String days(int n) { return n + (n == 1 ? " day" : " days"); }

    /** How a week counts (TrainingWeek), in the plan's own figures. */
    public static String weekRuleWhy() {
        return Say.WEEK_RULE + " A full session is one that delivered what its routine "
            + "asked; two runs in one day are one session, and one long session never counts "
            + "on its own. Two full sessions count once the week's days for that track are "
            + "done. Change the days in Settings \u203a Training schedule.";
    }

    /** "Girth · interval · L1" - a row's name. The feeder has no level of its own. */
    public static String offerName(int track, int level) {
        return TrainerTab.trackLabel(track)
            + (track == Plan.TRACK_FEEDER ? "" : " · " + TrainerTab.levelLabel(level));
    }

    /** A row's one line: the routine as the card describes it, and the split if it is one. */
    public static String offerLine(String routineLine, boolean twoParts) {
        String s = routineWords(routineLine);
        return twoParts ? s + " · saved in two parts" : s;
    }

    /** A routine line without its "Routine:" lead-in (polish SU-17): the sheet said it on
     *  every row, under a title that already says what the rows are. Sentence case. */
    public static String routineWords(String routineLine) {
        String s = routineLine == null ? "" : routineLine.trim();
        if (s.startsWith("Routine:")) s = s.substring("Routine:".length()).trim();
        return Say.capitalise(s);
    }

    public static String offerTitle(int total) {
        return total == 1 ? "Save your first routine" : "Save your routines";
    }

    /** The sheet's opening line, for `rows` tracks of which `saveable` have a routine to
     *  save. The title (#offerTitle) counts the rows: one track enabled is the only singular. */
    public static String offerIntro(int rows, int saveable) {
        if (saveable <= 0)
            return "Your plan is ready. Nothing new to save right now; each track says why:";
        if (rows == 1)
            return "Your plan is ready. Here is the routine it wrote for where you are:";
        return "Your plan is ready. It wrote " + saveable
            + (saveable == 1 ? " routine" : " routines") + " to save for where you are.";
    }

    /** The save button for `ticked` of `total`: all of them, some, one, or none. */
    public static String saveLabel(int ticked, int total) {
        if (total <= 1) return ticked >= 1 ? "Save it" : "Save";
        if (ticked >= total) return "Save all";
        if (ticked <= 0) return "Save";
        return "Save " + ticked + " ticked";
    }

    /** After saving: how many, and that the first is the one Today now runs. It names no
     *  days: "three days a week" was wrong under alternate days (polish SU-18), and the week
     *  is on the sheet that was just closed. */
    public static String savedLine(int saved) {
        return saved <= 1 ? "Routine saved — it is loaded."
            : saved + " routines saved — the first is loaded.";
    }

    /** The snack's action after saving (polish SU-18): sentence case, like every other. */
    public static final String OPEN_TODAY = "Open Today";
}
