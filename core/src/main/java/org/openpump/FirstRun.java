package org.openpump;

/**
 * THE FIRST-RUN SETUP'S STATE - when it is owed, what starting it decides for the person, and
 * when it is over. Pure (no `import android`): the wizard screen is a view over this, and every
 * rule here is asserted by FirstRunTest rather than eyeballed on a phone.
 *
 * THREE STATES, stored as {@link Model#firstRun}: {@link #NOT_STARTED} (only
 * {@link Model#seed()} ever writes it), {@link #IN_PROGRESS} (written the moment the setup
 * opens, so a half-finished setup resumes instead of restarting) and {@link #DONE}. A model.json
 * with no key loads as DONE, because every phone that has one was set up long ago - the wizard
 * must never appear over somebody's existing data.
 *
 * WHY begin() WRITES ANYTHING. Every value the setup suggests already matches the seed (inHg, cm,
 * a 40 kPa ceiling, new to pumping, marks off, measuring every 5 sessions, incognito and lock
 * off, status-line colours, haptics on) EXCEPT the schedule, whose model default is all seven
 * days with reminders off - the default an upgrade needs so a streak does not move (see
 * {@link Schedule}). A new person is better served by the plan's own 3-a-week rhythm, so the
 * setup starts them on Monday, Wednesday and Friday at 19:00. Reminders stay off: turning them
 * on is a permission the person grants on purpose, on the setup's own switch.
 */
public final class FirstRun {

    private FirstRun() { }

    /** The three stored states of {@link Model#firstRun}. */
    public static final int NOT_STARTED = 0, IN_PROGRESS = 1, DONE = 2;

    /** The setup's suggested training hour (19:00) and days (Mon, Wed, Fri). */
    public static final int SUGGESTED_HOUR = 19;
    public static final int SUGGESTED_MINUTE = 0;

    /**
     * The measure-every-N-sessions figure the setup words its interval in: the seed's own
     * "every 5 sessions" ({@link Model.Meas}), so the label and the behaviour it describes are
     * one number.
     */
    public static final int MEASURE_EVERY_SESSIONS = 5;

    /** More than this many training days a week is a lot; fewer than {@link #DAYS_OK_MIN} is
     *  slow. Both bounds are the guidance's own three or four days (see the Plan's training week). */
    public static final int DAYS_OK_MAX = 4;
    public static final int DAYS_OK_MIN = Plan.TRAINING_WEEK_MIN_DAYS;

    /**
     * WHERE LEVEL 1 STARTS, in whole kPa: the plan's own Level 1 floor (5 hg, 16.93 kPa),
     * rounded because a ceiling lives in whole kPa. A ceiling below it makes every routine stop
     * short of its plan. TAKEN FROM {@link Plan#L1_FLOOR_KPA}, never written twice.
     */
    public static final int LEVEL1_START_KPA = (int) Math.round(Plan.L1_FLOOR_KPA);

    /**
     * THE HIGHEST PRESSURE ANY LEVEL PLANS FOR, in whole kPa: Levels 2 to 4 cap at the working
     * cap (10 hg, 33.86 kPa). A ceiling above it does nothing to a plan - the ceiling is only
     * ever a hard stop above that. TAKEN FROM {@link Plan#WORKING_CAP_KPA}.
     */
    public static final int TOP_LEVEL_KPA = (int) Math.round(Plan.WORKING_CAP_KPA);

    /** Should the setup open now? Never over a live run, never when the store could not be
     *  read (the model in hand is then a stand-in, and "not set up" is a fact about a phone's
     *  own file, not about a stand-in). */
    public static boolean shouldShow(Model m, boolean runLive, boolean storeUnreadable) {
        return m.firstRun != DONE && !runLive && !storeUnreadable;
    }

    /**
     * The setup opens. From NOT_STARTED it applies the suggested schedule and becomes
     * IN_PROGRESS; from IN_PROGRESS (the app was closed part way and reopened) it changes
     * NOTHING - the person's own choices so far are theirs, and re-applying the suggestion
     * would quietly undo them.
     */
    public static void begin(Model m) {
        if (m.firstRun != NOT_STARTED) return;
        Schedule s = m.sched;
        for (int i = 0; i < Schedule.DAYS; i++) s.days[i] = false;
        s.days[Schedule.MON] = true;
        s.days[Schedule.WED] = true;
        s.days[Schedule.FRI] = true;
        s.hour = SUGGESTED_HOUR;
        s.minute = SUGGESTED_MINUTE;
        s.remind = false;
        m.firstRun = IN_PROGRESS;
    }

    /** Where the setup's last button goes (#finishAction). */
    public static final int FINISH_STARTER = 0, FINISH_LIBRARY = 1, FINISH_TRAINER_OPEN = 2,
                            FINISH_TRAINER_ONBOARD = 3;

    /**
     * WHERE THE SETUP ENDS for the first-session choice `first` ("trainer", "starter", "own").
     *
     * THE TRAINER CHOICE ON AN ENROLLED PHONE JUST OPENS THE TRAINER (review L2). Setup can be
     * run again from Settings, and its default choice started a FRESH trainer onboarding - not
     * the recalibration an enrolled phone gets from the Trainer - which seeds the girth style
     * back to interval, turns length off and takes the pressure from the session log: finishing
     * it would quietly reset a plan somebody is months into. An enrolled phone opens the
     * Trainer as it stands (the choice is labelled so); a phone not yet enrolled is onboarded
     * as before. The Starter choice needs the Starter routine; without it, the trainer.
     */
    public static int finishAction(String first, boolean starterExists, boolean enrolled) {
        if ("starter".equals(first) && starterExists) return FINISH_STARTER;
        if ("own".equals(first)) return FINISH_LIBRARY;
        return enrolled ? FINISH_TRAINER_OPEN : FINISH_TRAINER_ONBOARD;
    }

    /** The setup is over, whichever way out the person took. */
    public static void finish(Model m) {
        m.firstRun = DONE;
    }

    /**
     * ABOUT HOW MANY DAYS APART "EVERY 5 SESSIONS" FALLS, for a week of `trainingDaysPerWeek`:
     * five sessions is 5/d weeks, so 5/d * 7 days, rounded, never below one day. Fewer than one
     * training day is read as one.
     */
    public static int measureEveryDays(int trainingDaysPerWeek) {
        int d = Math.max(1, trainingDaysPerWeek);
        return Math.max(1, (int) Math.round((double) MEASURE_EVERY_SESSIONS / d * 7));
    }

    /** Which word the training-days response uses: "many" (a warning), "few" or "ok". */
    public static String daysKey(int count) {
        if (count > DAYS_OK_MAX) return "many";
        if (count < DAYS_OK_MIN) return "few";
        return "ok";
    }

    /** Is a ceiling under where Level 1 starts - so sessions stop short of their plan? */
    public static boolean ceilingBelowLevel1(int ceilKpa) {
        return ceilKpa < LEVEL1_START_KPA;
    }

    /** Is a ceiling over the top of every plan - so the plan, not the ceiling, is the limit? */
    public static boolean ceilingAboveTop(int ceilKpa) {
        return ceilKpa > TOP_LEVEL_KPA;
    }
}
