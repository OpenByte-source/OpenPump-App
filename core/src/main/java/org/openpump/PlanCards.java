package org.openpump;

/**
 * THE TRAINER PAGE'S NEW WORDS AND THE SMALL RULES BEHIND THEM (t10 R-61 .. R-64): the upgrade
 * card, the lowered-drop notice, the two settings rows beside Long training days ({@link
 * LongDays}), and the buttons of the decision cards lanes B and C decide.
 *
 * The decisions' own sentences are the deciding lane's (each Decision#reason); what lives here
 * is what the card adds around them - its buttons, and the lines the card itself owns. One
 * place, so SetupTextTest-style tests can pin them, and the screen only arranges them.
 *
 * PURE: the model in, words out. Asserted in TrainerSettingsRowsTest, UpgradeCardTest,
 * DropLoweredNoticeTest and OfferBreakDecisionTest.
 */
public final class PlanCards {
    private PlanCards() { }

    /* ---- R-61 · the upgrade card ------------------------------------------------------- */

    /* Polish TR-2 / NEW-1: the title says what happened, not the instruction its button
     * repeats; the body names the three rows by their exact labels (behind the card's \u24d8);
     * and the three new questions can be answered on their own (F4), with the full setup a
     * secondary. */
    public static final String UPGRADE_TITLE = "The plan has new choices";
    public static final String UPGRADE_SHORT = "Your values are kept. The setup asks three new "
        + "questions.";
    public static final String UPGRADE_TEXT = "Your values are kept. The setup asks three new "
        + "questions: " + LongDays.ROW + ", " + PlanCards.LEN_LOAD_ROW_NAME + " and "
        + PlanCards.R4_ROW_NAME + ".";
    /** The short path (F4): the three questions on one screen, then Confirm. */
    public static final String UPGRADE_ANSWER = "Answer 3 new questions";
    /** The full six-step setup, every value kept unless changed. */
    public static final String UPGRADE_RUN = "Run the full setup";
    public static final String UPGRADE_LATER = "Later";
    /** The card as a one-line row while a more urgent card shows (polish TR-1). */
    public static final String UPGRADE_NAV = UPGRADE_TITLE + " \u00b7 " + UPGRADE_ANSWER;
    /** "Where I am"'s row once the card is put off (polish TR-3). */
    public static final String UPGRADE_ROW = "Answer the plan\u2019s new questions";
    public static final String UPGRADE_ROW_SUB = "Runs the setup again \u2014 your values are kept";
    /** The short path's screen. */
    public static final String NEW_QUESTIONS_TITLE = "The plan\u2019s new questions";
    public static final String NEW_QUESTIONS_NONE = "None of the new questions applies to the "
        + "tracks you run. Confirm and the card is answered.";

    /* THE REAL NUMBER (device check EMU9 M3): the card promised three questions and the short
     * path asked as many as apply - none for a girth-only plan, two with length on and no
     * length cylinder yet. The card, its row and its ⓘ now count what the short path asks. */

    /** How many of the three new questions apply to `m` - the ones the short path asks. */
    public static int newQuestionCount(Model m) {
        if (m == null) return 0;
        return (LongDays.shown(m) ? 1 : 0) + (lenLoadShown(m) ? 1 : 0) + (r4Shown(m) ? 1 : 0);
    }

    /** "Answer 2 new questions", "Answer 1 new question"; with none to answer, the page only
     *  confirms the card. */
    public static String upgradeAnswer(Model m) {
        int n = newQuestionCount(m);
        if (n <= 0) return "See what changed";
        return "Answer " + n + (n == 1 ? " new question" : " new questions");
    }

    /** WEEK B (the owner's decision of 2026-10-03): the upgrade card says once that the week
     *  count changed. Weeks are re-read from the session log (TrainingWeek), so an upgrader's
     *  past weeks of two full sessions a track count from the first render after the update. */
    public static final String WEEKS_NOW_COUNT = "Weeks with 2 full sessions per track now count.";
    /** The same, as the Trainer's one-time notice says it to a person past the card. */
    public static final String WEEKS_NOW_COUNT_PLAN =
        "Weeks with 2 full sessions per track now count toward your plan.";

    /** The card's line: what the short path asks, said with its real number. */
    public static String upgradeShort(Model m) {
        int n = newQuestionCount(m);
        if (n <= 0) return "Your values are kept. None of the new questions applies to the "
            + "tracks you run.";
        return "Your values are kept. " + (n == 1 ? "One new question applies" : (n == 2
            ? "Two" : "Three") + " new questions apply") + " to the tracks you run.";
    }

    /** The card's ⓘ: the rows that apply, by their exact labels. */
    public static String upgradeText(Model m) {
        java.util.List<String> rows = new java.util.ArrayList<String>();
        if (LongDays.shown(m)) rows.add(LongDays.ROW);
        if (lenLoadShown(m)) rows.add(LEN_LOAD_ROW_NAME);
        if (r4Shown(m)) rows.add(R4_ROW_NAME);
        if (rows.isEmpty()) return upgradeShort(m) + " Confirm and the card is answered.";
        StringBuilder b = new StringBuilder("Your values are kept. The new ")
            .append(rows.size() == 1 ? "question that applies: " : "questions that apply: ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) b.append(i == rows.size() - 1 ? " and " : ", ");
            b.append(rows.get(i));
        }
        return b.append('.').toString();
    }

    /** The card as a one-line row while a more urgent card shows. */
    public static String upgradeNav(Model m) {
        return UPGRADE_TITLE + " · " + upgradeAnswer(m);
    }

    /** Whether the Trainer page shows the card now: owed (Model#planChangedT10Card, which
     *  counts the "Later"s) and not put off already since this app start. */
    public static boolean upgradeCardShown(Model m, boolean laterThisRun) {
        return m != null && !laterThisRun && m.planChangedT10Card();
    }

    /** Whether "Where I am" carries it as a row: owed, and not on the page as the card. */
    public static boolean upgradeRowShown(Model m, boolean laterThisRun) {
        return m != null && m.planChangedT10Due() && !upgradeCardShown(m, laterThisRun);
    }

    /* ---- R-62 · a saved drop lowered ---------------------------------------------------- */

    /** "Your drop between holds was lowered to −7.7 inHg: it can be at most 1.0 inHg under
     *  your pressure." In the person's display unit. */
    public static String dropLoweredLine(int loweredKpa) {
        // One wording with the run screen's (polish NEW-15): "under your pull".
        return "Your drop between holds was lowered to " + Model.Fmt.p(loweredKpa) + ": it can "
            + "be at most " + Model.Fmt.dMag(RunEdit.DROP_GAP_SAID_KPA) + " under your pull.";
    }

    /** The drop a routine pulling to `pullKpa` builds with the saved one (Mint#dropFor), when
     *  that is lower than the saved drop; -1 when the saved drop is used as it is. */
    public static int droppedTo(int savedKpa, int pullKpa) {
        int want = Mint.clampDropKpa(savedKpa);
        int got = Mint.dropFor(savedKpa, pullKpa);
        return got < want ? got : -1;
    }

    /**
     * THE NOTICE, ONCE PER LOWERED VALUE (R-62): the line when a build at `pullKpa` lowers the
     * saved drop to a value not already said, and the value recorded as said
     * ({@link Model#dropLoweredSaidKpa}); "" otherwise. The caller saves.
     */
    public static String dropLoweredNotice(Model m, int pullKpa) {
        if (m == null) return "";
        int to = droppedTo(m.rxDropKpa, pullKpa);
        if (to < 0 || to == m.dropLoweredSaidKpa) return "";
        m.dropLoweredSaidKpa = to;
        return dropLoweredLine(to);
    }

    /* ---- R-63 · the Trainer page's two other rows ------------------------------------- */

    public static final String LEN_LOAD_ROW = "Length load after month 3";
    /** The row's name as the upgrade card says it (a constant, so the card's sentence is
     *  initialised whatever order the fields are read in). */
    static final String LEN_LOAD_ROW_NAME = "Length load after month 3";
    /** In Model's order: LENGTH_LOAD_SLOW, LENGTH_LOAD_AFTER12. */
    public static final String[] LEN_LOAD_LABELS = {
        "Small steps over time",
        "Only after 12 strain sets",
    };
    public static final String[] LEN_LOAD_EFFECTS = {
        "+0.5 lb every 2 training weeks toward 5 lb, then 12 lb, while strain sets are under 12.",
        "The load moves only once you are at 12 strain sets.",
    };

    /** Shown with the length track on and a length cylinder to pull with. */
    public static boolean lenLoadShown(Model m) {
        return m != null && m.trainerLengthOn && m.lengthPulls();
    }

    public static int nextLenLoad(int v) {
        return v == Model.LENGTH_LOAD_SLOW ? Model.LENGTH_LOAD_AFTER12 : Model.LENGTH_LOAD_SLOW;
    }

    /** A value's place in {@link #LEN_LOAD_LABELS}, and back. */
    public static int lenLoadIndex(int v) { return v == Model.LENGTH_LOAD_AFTER12 ? 1 : 0; }

    public static int lenLoadValue(int index) {
        return index == 1 ? Model.LENGTH_LOAD_AFTER12 : Model.LENGTH_LOAD_SLOW;
    }

    /** Both effect lines, in {@link #LEN_LOAD_LABELS}' order, in the person's load unit. */
    public static String[] lenLoadEffects() {
        return new String[]{ lenLoadEffect(Model.LENGTH_LOAD_SLOW),
                             lenLoadEffect(Model.LENGTH_LOAD_AFTER12) };
    }

    public static String lenLoadLabel(int v) {
        return LEN_LOAD_LABELS[v == Model.LENGTH_LOAD_AFTER12 ? 1 : 0];
    }

    /** The effect line, in the person's load unit (t10 device walk: the slow step's line read
     *  "+0.5 lb ... toward 5 lb, then 12 lb" while every other load figure was in kg). The
     *  #LEN_LOAD_EFFECTS wording, with its loads through Traction#settingLb. */
    public static String lenLoadEffect(int v) {
        if (v == Model.LENGTH_LOAD_AFTER12) return LEN_LOAD_EFFECTS[1];
        return "+" + Traction.settingLb(Plan.LENGTH_LOAD_STEP_LB) + " every 2 training weeks "
            + "toward " + Traction.settingLb(Plan.LENGTH_LOAD_L1_TARGET_LB) + ", then "
            + Traction.settingLb(Plan.LENGTH_LOAD_MAX_LB) + ", while strain sets are under "
            + Plan.LENGTH_STRAIN_SETS_MAX + ".";
    }

    public static final String R4_ROW = "When girth follows length";
    static final String R4_ROW_NAME = "When girth follows length";
    /** In Model's order: R4_WARM, R4_SETS, R4_NONE. */
    public static final String[] R4_LABELS = {
        "2-minute climbing warm-up",
        "First " + Plan.R4_RAMP_SETS + " holds climb",
        "Nothing",
    };
    public static final String[] R4_EFFECTS = {
        "About 2 minutes of short holds climbing to your pressure, in place of the fatigue block.",
        "Your first " + Plan.R4_RAMP_SETS + " holds climb to your pressure and count.",
        "No fatigue block and nothing in its place.",
    };

    /** Shown with both tracks on. */
    public static boolean r4Shown(Model m) {
        return m != null && m.trainerGirthOn && m.trainerLengthOn;
    }

    private static int r4(int v) {
        return (v < Model.R4_WARM || v > Model.R4_NONE) ? Model.R4_WARM : v;
    }

    public static int nextR4(int v) {
        return (r4(v) + 1) % R4_LABELS.length;
    }

    public static String r4Label(int v) { return R4_LABELS[r4(v)]; }

    /** A value's place in {@link #R4_LABELS} (Model's R4_WARM .. R4_NONE are 0 .. 2). */
    public static int r4Index(int v) { return r4(v); }

    public static String r4Effect(int v) { return R4_EFFECTS[r4(v)]; }

    /* ---- R-64 · the decision cards' own parts ------------------------------------------ */

    public static final String NOT_NOW = "Not now";
    public static final String TAKE_WEEK_OFF = "Take a week off";
    /** The weeks the girth offer's length focus pauses girth for (R-23). */
    public static final int LENGTH_FOCUS_WEEKS = 4;
    public static final String LENGTH_FOCUS = LENGTH_FOCUS_WEEKS + " weeks of length focus";
    /** Says how long (polish TR-31): "8-week girth block". */
    public static final String GIRTH_BLOCK = Plan.GIRTH_FOCUS_WEEKS + "-week girth block";
    /** The girth offer's title (polish TR-25): the question its buttons answer. */
    public static String offerTitle(boolean lengthFocusShown) {
        return lengthFocusShown ? "A week off, or length focus?" : "Time for a week off?";
    }
    /** "Start an 8-week girth block" - the article as the number is said (polish TR-26). */
    public static String girthBlockStart(String verb) {
        return verb + " " + Say.aOrAn(Plan.GIRTH_FOCUS_WEEKS) + " " + GIRTH_BLOCK;
    }
    /** The over-6% card's explanation behind its \u24d8 (polish TR-30): the strain check's own
     *  window, in its own words. */
    public static String strainWindowWords() {
        return "What the " + Mint.trimLb(Plan.LENGTH_STRAIN_HI) + "% is: your after-session "
            + "length reading, against its before-reading. The window is "
            + Mint.trimLb(Plan.LENGTH_STRAIN_LO) + "\u2013" + Mint.trimLb(Plan.LENGTH_STRAIN_HI)
            + "%. Over it, the plan asks you to measure again before anything is lowered, "
            + "because one high reading can be a mis-measure.";
    }
    public static final String START_WEEK_OFF = "Start the week off";

    /** What the girth offer's length focus is said as, once taken. */
    public static String lengthFocusTaken(String untilLabel) {
        return "Girth paused for " + LENGTH_FOCUS_WEEKS + " weeks"
            + (untilLabel == null || untilLabel.length() == 0 ? "" : ", until " + untilLabel)
            + " — length runs as planned";
    }

    /* ---- t10 REAL-1 · the cadence deload asks when it starts ----------------------------- */

    public static final String DELOAD_DUE_TITLE = "Time for a deload week";
    public static final String DELOAD_FROM_TOMORROW = "From tomorrow";
    public static final String DELOAD_FROM_MONDAY = "From next Monday";
    public static final String DELOAD_PICK_DAY = "Pick a day";
    public static final String DELOAD_PICK_TITLE = "Start the deload week on";
    public static final String DELOAD_CHOOSE = "Choose when it starts";
    public static final String DELOAD_NOT_NOW_LINE = "Not now — asked again tomorrow";
    /** The trainer reminder while the question waits. */
    public static final String DELOAD_DUE_REMIND = "Choose when it starts. Today’s session still runs.";

    /** The question: the weeks trained, what the week off is, and that today still runs. */
    public static String deloadDueText(int weeks, boolean first) {
        // Polish TR-5: one name for it ("deload week", not also "week off"), and the question
        // last, where its answers are.
        return weeks + " training weeks "
            + (first ? "since the plan began" : "since your last deload")
            + ". A deload week rests both tracks for 7 days. Today’s session still runs. "
            + "When should it start?";
    }

    /** One of the dialog's three answers, the day it lands on named: "From tomorrow · Fri 2 Oct".
     *  A picked day has no date until it is picked. */
    public static String deloadStartItem(int choice, long startMs) {
        if (choice == Deload.START_PICK) return DELOAD_PICK_DAY + "…";
        return (choice == Deload.START_MONDAY ? DELOAD_FROM_MONDAY : DELOAD_FROM_TOMORROW)
            + " · " + Say.dayLabel(startMs);
    }

    /** A day on the picker, `days` days ahead: tomorrow says so. */
    public static String deloadPickItem(int days, long startMs) {
        return days == 1 ? "Tomorrow · " + Say.dayLabel(startMs) : Say.dayLabel(startMs);
    }

    /** What is said once it is answered: its first and LAST day (the end is exclusive). */
    public static String deloadSetLine(long startMs, long endMs) {
        return "Deload week " + Say.dayLabel(startMs) + " to "
            + Say.dayLabel(Deload.dayStartPlus(endMs - 1L, 0)) + " — both tracks rest";
    }

    /** Whether a decision is the girth or length offer of a break (R-23, R-40 D3). */
    public static boolean isOfferBreak(Plan.Decision d) {
        return d != null && d.action == Plan.ACTION_OFFER_BREAK;
    }

    /** Whether a decision is option D's "it fell" - the week off brought forward (R-40 D1). */
    public static boolean isLengthFell(Plan.Decision d) {
        return d != null && d.action == Plan.ACTION_DELOAD
            && Plan.LENGTH_FELL_RULE.equals(d.rule);
    }

    /** Whether a decision is the "stop growing at 90 min" hold (R-60) - lane B's own test
     *  (Plan#isHeldAt90): a girth hold's rule starts with the id and names the step it held. */
    public static boolean isHeldAt90(Plan.Decision d) {
        return Plan.isHeldAt90(d);
    }

    /* ---- R11-5 · Hold lengths (Trainer › What it writes) ------------------------------- */

    public static final String HOLDS_CARD = "Hold lengths";
    public static final String FAT_HOLD_ROW = "Fatigue holds";
    public static final String WORK_HOLD_ROW = "Work holds";
    public static final String BLOCK_REST_ROW = "Rest between blocks";
    public static final String STRAIN_HOLD_ROW = "Length strain holds";
    public static final String TRAD_HOLD_ROW = "Traditional holds";
    public static final String HOLDS_NOTE =
        "Shorter holds run more of them: the minutes at pressure stay the plan's.";
    public static final String HOLDS_INFO_TITLE = "How hold lengths work";
    public static final String HOLDS_INFO =
        "Each choice stays inside the guidance's range: fatigue holds 30 to 60 s, work holds "
        + "1 to 3 minutes, the rest between blocks 3 to 5 minutes.\n\n"
        + "The plan keeps its minutes at pressure. A shorter hold runs more holds and a longer "
        + "one fewer, in whole holds, and never past your level's top, the session's time cap or "
        + "the 90-minute day.";
    public static final String WORK_HOLD_LATER = "2:00 (set by the level)";
    public static final String WORK_HOLD_LATER_INFO =
        "Levels 1 and 2 run the 2-minute hold their own table specifies, with no range to move "
        + "within. From Level 3, work holds can be 1 to 3 minutes.";
    public static final String STRAIN_HOLD_VALUE = "Set by the phase";
    public static final String STRAIN_HOLD_INFO =
        "The length strain holds are set by the length phase you are in. The guidance gives "
        + "them no range, so there is nothing to choose here.";
    public static final String TRAD_HOLD_INFO =
        "Traditional girth holds are five minutes. The guidance gives them no range, so there "
        + "is nothing to choose here.";

    /** "30 s", "45 s", "60 s". */
    public static String[] fatigueHoldLabels() {
        String[] out = new String[Model.FATIGUE_HOLD_CHOICES.length];
        for (int i = 0; i < out.length; i++) out[i] = Model.FATIGUE_HOLD_CHOICES[i] + " s";
        return out;
    }

    /** "15 holds · the block's minutes kept (the default)". */
    public static String[] fatigueHoldEffects() {
        String[] out = new String[Model.FATIGUE_HOLD_CHOICES.length];
        for (int i = 0; i < out.length; i++) {
            int s = Model.FATIGUE_HOLD_CHOICES[i];
            out[i] = Mint.fatigueHolds(Plan.FATIGUE_BLOCK_SETS, s) + " holds · the block's "
                + "minutes kept" + (s == Model.FATIGUE_HOLD_DEFAULT_SEC ? " (the default)" : "");
        }
        return out;
    }

    /** "1:00" ... "3:00". */
    public static String[] workHoldLabels() {
        String[] out = new String[Model.WORK_HOLD_CHOICES.length];
        for (int i = 0; i < out.length; i++) out[i] = Model.Fmt.t(Model.WORK_HOLD_CHOICES[i]);
        return out;
    }

    /** "20 holds where 2:00 runs 10" - the same minutes. */
    public static String[] workHoldEffects() {
        String[] out = new String[Model.WORK_HOLD_CHOICES.length];
        for (int i = 0; i < out.length; i++) {
            int s = Model.WORK_HOLD_CHOICES[i];
            out[i] = s == Mint.HOLD_INTERVAL_SEC ? "The level's own (the default)"
                : Mint.setsForNet(10 * (Mint.HOLD_INTERVAL_SEC / 60.0), s)
                  + " holds where 2:00 runs 10 · the same minutes";
        }
        return out;
    }

    public static String[] blockRestLabels() {
        String[] out = new String[Model.BLOCK_REST_CHOICES.length];
        for (int i = 0; i < out.length; i++) out[i] = Model.Fmt.t(Model.BLOCK_REST_CHOICES[i]);
        return out;
    }

    public static String[] blockRestEffects() {
        String[] out = new String[Model.BLOCK_REST_CHOICES.length];
        for (int i = 0; i < out.length; i++)
            out[i] = i == 0 ? "The guidance's shortest (the default)"
                : i == out.length - 1 ? "The guidance's longest" : "Between the two";
        return out;
    }

    /** The place of `v` in `choices`, or -1. The work hold's 0 is the level's own 2:00. */
    public static int holdIndex(int[] choices, int v) {
        int want = v == 0 && choices == Model.WORK_HOLD_CHOICES ? Mint.HOLD_INTERVAL_SEC : v;
        for (int i = 0; i < choices.length; i++) if (choices[i] == want) return i;
        return -1;
    }

    /* The 90-minute hold's card (R-60, R-64). */
    public static final String HELD90_TITLE = LongDays.ROW;
    public static final String HELD90_SWITCH = "Switch to alternate days";
    public static final String HELD90_KEEP = "Keep as is";

    /** The card's text: girth's is lane B's (Plan#HELD_AT_90_WORDS); length says strain sets
     *  for holds. */
    public static String heldAt90Text(int track) {
        if (track != Plan.TRACK_LENGTH) return Plan.HELD_AT_90_WORDS;
        return "Your both-tracks day would pass " + Plan.HELD_AT_90_MIN + " min with more "
            + "strain sets, so the plan holds here. Alternate days keep each session near an "
            + "hour.";
    }
}
