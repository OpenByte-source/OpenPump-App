package org.openpump;

/**
 * EVERY WORD OF THE FIRST-RUN SETUP, in one place and in the owner's chosen versions - so the
 * screen owns layout and nothing else, and a test can read every line the wizard can ever say.
 * Pure (no `import android`). The strings with a runtime part are small functions here, not
 * concatenations in the screen, for the same reason: what is said is decided in one file.
 *
 * Three kinds of line, as the screens use them: a WHY (behind the small "i"), a RESPONSE (under
 * a control, once the person has acted) and a WARN (a response in the amber box). Apostrophes and
 * quotes are the typographic ones (U+2019, U+201C/D) that the rest of the app prints.
 *
 * No line here names a source: where the plan's numbers come from is said once, in the docs.
 */
public final class SetupText {

    private SetupText() { }

    /* ------------------------------------------------------------------ the frame */

    public static final String BACK = "‹ Back";
    public static final String NEXT = "Next ›";
    public static final String LEGEND = "marks our suggestion";

    /** Step titles and the question under each, screens 2 to 5 (1 and 6 have their own). */
    public static final String UNITS_TITLE = "Units and safety";
    public static final String UNITS_QUESTION =
        "How should numbers read, and what is the most the pump may pull?";
    public static final String YOU_TITLE = "You and your cylinder";
    public static final String YOU_QUESTION = "What am I pumping with, and how does my body react?";
    public static final String ROUTINE_TITLE = "Your routine";
    public static final String ROUTINE_QUESTION = "When do I train, and how will I see progress?";
    public static final String PRIVACY_TITLE = "Privacy and feel";
    public static final String PRIVACY_QUESTION = "Who can see it, and how should a run feel?";
    public static final String READY_TITLE = "Ready";

    /* -------------------------------------------------------------------- welcome */

    public static final String WELCOME_TITLE = "Welcome to OpenPump";
    public static final String WELCOME_LINE =
        "Your pump sessions, training and progress, all on this phone.";
    public static final String WELCOME_WHY = "Takes about 2 minutes. Everything can be changed later.";
    public static final String BEFORE_TITLE = "Before you start";
    /** Each bullet is a bold lead and the rest of the sentence, drawn as one line. */
    public static final String BEFORE_1_LEAD = "Not a medical device.";
    public static final String BEFORE_1_REST =
        " It runs your pump and keeps count; it doesn’t diagnose or treat anything.";
    public static final String BEFORE_2_LEAD = "Stop if it hurts,";
    public static final String BEFORE_2_REST = " goes numb or looks discoloured.";
    public static final String BEFORE_3_LEAD = "STOP always vents,";
    public static final String BEFORE_3_REST =
        " and the pump never passes the ceiling you set next.";
    public static final String AGREE = "I’ve read this and I use the app at my own risk";

    /* ------------------------------------------------------------- units and safety */

    public static final String PRESSURE_LABEL = "Pressure";
    public static final String SIZE_LABEL = "Measurements";
    public static final String SIZE_CM = "cm";
    public static final String SIZE_IN = "inches";
    /** The load unit's row (2026-09-30): pounds or kilos, defaulting with the size unit. */
    public static final String LOAD_LABEL = "Load";
    public static final String LOAD_LB = "lb";
    public static final String LOAD_KG = "kg";
    public static final String CEILING_LABEL = "Safety ceiling";

    public static final String UNIT_WHY =
        "This is the unit for every pressure in the app. Most pump gauges read in inHg, so that’s "
        + "our suggestion, but pick whatever your own gauge shows so the numbers match what you see.";

    /** Under the pressure setting, once a unit is tapped. `unit` is {@link Model.Fmt}'s id. */
    public static String unitResponse(String unit) {
        if (Model.Fmt.U_INHG.equals(unit)) return "Matches most gauges.";
        if (Model.Fmt.U_CMHG.equals(unit)) return "About 2.5 × the inHg numbers.";
        if (Model.Fmt.U_KPA.equals(unit)) return "Matches the pump’s manual.";
        return "";
    }

    public static final String CEILING_WHY =
        "Think of it as a hard stop. The pump won’t go past it, whatever a routine or a tap asks for.";

    /** The ceiling row's subtitle: always shown, two branches. Pressures follow the unit. */
    public static String ceilingSubtitle(int ceilKpa) {
        if (FirstRun.ceilingBelowLevel1(ceilKpa)) return "Below Level 1 · sessions stop here";
        // Above the plan's usual top it is still only a hard stop: plans keep to the usual tops
        // unless you choose higher, warned once, and each track has its own "Most you will go
        // to" - so "plans never go above" was no longer true (device walk, E-M2).
        return "A hard stop the pump never passes";
    }

    /** The amber warning, only below Level 1; "" otherwise (show no box). */
    public static String ceilingWarning(int ceilKpa) {
        if (!FirstRun.ceilingBelowLevel1(ceilKpa)) return "";
        return "That’s below where Level 1 starts (" + Model.Fmt.p(FirstRun.LEVEL1_START_KPA)
            + "), so sessions will stop here. You can raise it any time.";
    }

    /* ------------------------------------------------------------ you and cylinder */

    public static final String EXP_LABEL = "How much have you pumped?";
    public static final String EXP_NEW = "New";
    public static final String EXP_SOME = "Some";
    public static final String EXP_EXP = "Experienced";

    /** Under the experience control, always shown. `choice` is "new", "some" or "exp". */
    public static String experienceResponse(String choice) {
        if ("new".equals(choice))
            return "No problem! The trainer starts low and adds a little time each week.";
        if ("some".equals(choice))
            return "Great. Next, the trainer asks what pressure you usually use and starts from there.";
        if ("exp".equals(choice))
            return "Nice. The trainer asks your usual pressure and how long you’ve been at it, "
                + "and may start you a level higher.";
        return "";
    }

    public static final String CYL_LABEL = "Your cylinder";
    public static final String CYL_LABEL_MANY = "Your cylinders";
    public static final String CYL_WHY =
        "You can skip this and add it later. With the size, the trainer can warn you if a cylinder "
        + "is too big for you. You can add more than one.";
    public static final String CYL_SAVED =
        "Nice. The trainer will warn you if this one is too big for you.";

    public static final String MARKS_LABEL = "I mark or bruise easily";
    public static final String MARKS_SUBTITLE = "Little red dots or bruises after pumping";
    /* 0.10 - WHAT THE ANSWER DOES, said plainly: the gentle warm-up (the owner's own rule for
     * this app, never the guidance's) and the gentle return after a week or more away (the
     * return taper it arms by itself). Nothing else "starts gentler", so nothing else is
     * claimed. */
    public static final String MARKS_WHY =
        "Some people get little red dots or bruises more easily than others. If that’s you, turn "
        + "this on. The trainer’s warm-up then starts lower and slower and climbs a little each "
        + "rep to your working pressure.";
    public static final String MARKS_ON =
        "Got it. The trainer’s warm-up will start lower and slower and climb a little each rep "
        + "to your working pressure, and after a week or more away it brings you back at a lower "
        + "pressure for two training days.";

    /** Under "I mark or bruise easily" in the trainer's own setup, on and off. */
    public static final String TRAINER_MARKS_ON =
        "On: the warm-up starts lower and slower and climbs a little each rep to your working "
        + "pressure. After a week or more away, the first two training days back run lower.";
    public static final String TRAINER_MARKS_OFF =
        "Off: the usual warm-up, and no automatic easing back after a week away.";

    /** The Trainer page's gentle warm-up card, in one line. */
    public static final String GENTLE_WARM_FACE =
        "The warm-up starts lower and slower and climbs a little each rep to your working "
        + "pressure.";

    /* ------------------------------------------------------------------- routine */

    public static final String DAYS_WHY =
        "Your body adapts on the days you rest, not only on the days you train. That’s why most "
        + "plans use 3 or 4 training days a week.";

    /** "Training days · 3 a week". */
    public static String daysLabel(int count) {
        return "Training days · " + count + " a week";
    }

    /** After the days are changed; the "many" one is drawn as a warning. */
    public static String daysResponse(String key) {
        if ("many".equals(key)) return "That’s a lot! Rest days are when the gains happen.";
        if ("few".equals(key))
            return "That works. Just expect slower progress with fewer than 3 days.";
        if ("ok".equals(key)) return "Nice balance. That fits the plan well.";
        return "";
    }

    public static final String TIME_LABEL = "Time";
    public static final String REMIND_LABEL = "Remind me";
    public static final String REMIND_OFF = "Android will ask to allow notifications";
    public static final String REMIND_WHY =
        "Reminders are just a nudge. Whatever you choose, the Today screen always shows what’s due, "
        + "so turning them off won’t make you miss a session.";

    /** "Mon, Wed, Fri at 19:00" when reminders are on, else the permission hint. */
    public static String remindersSubtitle(Schedule s) {
        if (!s.remind) return REMIND_OFF;
        return s.daysLine() + " at " + s.hhmm();
    }

    public static final String MEASURE_LABEL = "Ask me to measure";
    public static final String MEASURE_EVERY_5 = "Every 5";
    public static final String MEASURE_WEEK = "Week";
    public static final String MEASURE_NEVER = "Never";
    public static final String MEASURE_WHY =
        "Measurements are how you’ll see progress over the weeks. Asking at a steady interval keeps "
        + "the numbers comparable; the label shows roughly how often that is with your training days.";

    /** The label; picking Every 5 adds "about every N days". `seg` is a Model.Meas.CAD_* value. */
    public static String measureLabel(int seg, int trainingDays) {
        if (seg != Model.Meas.CAD_SESSIONS) return MEASURE_LABEL;
        return MEASURE_LABEL + " · about every " + FirstRun.measureEveryDays(trainingDays) + " days";
    }

    /** Under the control after a pick; "" for Every 5 (no box). */
    public static String measureResponse(int seg) {
        if (seg == Model.Meas.CAD_WEEK)
            return "We’ll ask at the 1st and 4th session of each training week.";
        if (seg == Model.Meas.CAD_NEVER)
            return "We won’t ask. You can log a measurement any time from Today.";
        return "";
    }

    /* ------------------------------------------------------------------- privacy */

    public static final String HOME_TITLE = "On your home screen";
    public static final String HOME_LINE = "How the app looks to anyone who sees your phone.";
    public static final String HOME_REAL_CAPTION = "Real name";
    public static final String HOME_DISGUISE_CAPTION = "Disguise";
    public static final String NOTIF_TITLE = "Its notifications";

    /** The preview's pressure, as the mock draws it: 5.9 inHg (its time left is 1:29). */
    public static final double PREVIEW_KPA = 5.9 * Model.Fmt.KPA_PER_INHG;

    /** The tile's name: OpenPump for the real icon, else the disguise's own. `disguise` is an
     *  {@link Incognito} id, {@link Incognito#REAL} for none. */
    public static String notificationTitle(int disguise) {
        if (disguise < 0 || disguise >= Incognito.DISGUISE_NAMES.length) return "OpenPump";
        return Incognito.DISGUISE_NAMES[disguise];
    }

    /** The preview's body: real shows set, pressure (in the chosen unit) and time left;
     *  disguised shows only the neutral title and time left. */
    public static String notificationBody(boolean disguised) {
        if (disguised) return Incognito.RUN_TITLE + " · 1:29 left";
        return "Set 2 of 5 · " + Model.Fmt.p(PREVIEW_KPA) + " · 1:29 left";
    }

    /** The line under the preview. */
    public static String notificationLine(boolean disguised) {
        if (disguised)
            return "Disguised: notifications just say “Session running” and the time left, "
                + "so a glance gives nothing away.";
        return "Notifications show your set, pressure and time left.";
    }

    public static final String OPEN_TITLE = "Who can open it";
    public static final String OPEN_ANYONE = "Anyone using my phone";
    public static final String OPEN_ANYONE_LINE = "Opens straight away";
    public static final String OPEN_ONLY_ME = "Only me";
    public static final String OPEN_ONLY_ME_LINE = "Fingerprint or PIN each time it opens";
    public static final String OPEN_WHY =
        "Your phone’s lock protects the phone; this protects the app. With it on, anyone who picks "
        + "up your unlocked phone still needs your fingerprint or PIN to open it.";
    public static final String OPEN_NEEDS_LOCK =
        "You’ll need a screen lock on your phone first. Set one in your phone’s Settings, "
        + "then choose this again.";

    /* ---------------------------------------------------------------- during a run */

    public static final String RUN_TITLE = "During a run";
    public static final String COLOURS_LABEL = "Run colours";
    public static final String COLOURS_LINE = "Status line";
    public static final String COLOURS_TINT = "Line + tint";
    public static final String COLOURS_OFF = "Off";
    public static final String COLOURS_WHY =
        "Colours help you tell the steps apart at a glance, even from across the room.";
    public static final String HAPTIC_LABEL = "Haptic ticks";
    public static final String HAPTIC_SUBTITLE = "A tick in the last 3 s of each step";
    public static final String HAPTIC_WHY =
        "A little tick before each step ends, so you don’t have to watch the timer.";
    public static final String HAPTIC_OFF =
        "No ticks, so you’ll want to keep an eye on the timer.";

    /* ----------------------------------------------------------------------- ready */

    public static final String SUMMARY_TITLE = "Your setup";
    public static final String BATTERY_LABEL = "Keep timers and reminders on time";
    public static final String BATTERY_SUBTITLE = "So the “time to vent” alert is never late";
    public static final String BATTERY_ALLOW = "Allow";
    /** Open by default. */
    public static final String BATTERY_WHY =
        "Android sometimes pauses apps to save battery, which can delay the “time to vent” "
        + "alert. Allowing it keeps that alert on time.";
    public static final String BATTERY_DONE = "Done. Alerts and reminders will arrive right on time.";

    public static final String FIRST_TITLE = "Your first session";
    public static final String FIRST_TRAINER = "Set up the trainer";
    /** The same choice on a phone already enrolled in the trainer (review L2): it opens the
     *  trainer as it stands, and never starts it over. */
    public static final String FIRST_TRAINER_ENROLLED = "Open the trainer";
    public static final String FIRST_STARTER = "Start the Starter routine";
    public static final String FIRST_OWN = "I’ll build my own";

    /** Under the "first session" options, always shown. `choice`: "trainer", "starter" or
     *  "own"; `starterMinutes` is the Starter routine's real length in whole minutes. */
    public static String firstResponse(String choice, int starterMinutes) {
        return firstResponse(choice, starterMinutes, false);
    }

    /** The same, on a phone already enrolled in the trainer when `enrolled`. */
    public static String firstResponse(String choice, int starterMinutes, boolean enrolled) {
        if ("trainer".equals(choice) && enrolled)
            return "Opens the Trainer. Your plan and your routines stay as they are.";
        if ("trainer".equals(choice))
            return "Six quick questions, and the trainer builds today’s session for you.";
        if ("starter".equals(choice)) return starterResponse(starterMinutes);
        if ("own".equals(choice))
            return "Opens the Library so you can build your own routine from scratch.";
        return "";
    }

    public static String starterResponse(int minutes) {
        return "A gentle " + minutes + "-minute routine to get to know your pump. "
            + "You can set up the trainer after.";
    }

    /** The last screen's button. */
    public static String finalButton(String choice) {
        return finalButton(choice, false);
    }

    /** The same, on a phone already enrolled in the trainer when `enrolled`. */
    public static String finalButton(String choice, boolean enrolled) {
        if ("trainer".equals(choice) && enrolled) return "Open the trainer ›";
        if ("trainer".equals(choice)) return "Set up the trainer ›";
        if ("starter".equals(choice)) return "Start the Starter routine ›";
        if ("own".equals(choice)) return "Open the Library ›";
        return NEXT;
    }

    /* ------------------------------------------------------------ screen furniture */
    /* The words the screens draw around the owner's texts: the step letters, the cylinder
     * card and its editor, the run preview, the Ready summary. Here for the same reason as
     * the rest - one file says everything the setup can say. */

    /** Each step's letter in its lime square (Units, Cylinder, Routine, Privacy). */
    public static final String UNITS_MARK = "U";
    public static final String YOU_MARK = "C";
    public static final String ROUTINE_MARK = "R";
    public static final String PRIVACY_MARK = "P";

    /** Added to what a screen reader says about the suggested choice. */
    public static final String SUGGESTED_SAID = ", suggested";
    /** Starts what a screen reader says about an "i": "About Safety ceiling". */
    public static final String ABOUT_SAID = "About ";
    public static final String AGREE_ON_SAID = ", ticked";
    public static final String AGREE_OFF_SAID = ", not ticked";

    public static final String LOWER_SAID = "Lower";
    public static final String HIGHER_SAID = "Higher";
    public static final String EARLIER_SAID = "Earlier";
    public static final String LATER_SAID = "Later";

    public static final String CYL_ADD_FIRST = "Add your cylinder";
    public static final String CYL_ADD_FIRST_LINE = "A name and two sizes, about 30 seconds";
    public static final String CYL_ADD_MORE = "Add another cylinder";
    public static final String CYL_ADD_MORE_LINE = "For a second size, or a length cylinder";
    public static final String CYL_ACTIVE = "Active";
    public static final String CYL_EDIT = "Edit";

    /** A saved cylinder's line on its card: "5.0 cm inside · 23.0 cm long", in the size unit. */
    public static String cylinderLine(double boreCm, double lengthCm) {
        return Model.Fmt.len(boreCm) + " inside · " + Model.Fmt.len(lengthCm) + " long";
    }

    /** The same line with what the cylinder is used for in front (owner, 2026-09-30):
     *  "Length · 4.5 cm inside · 23.0 cm long". */
    public static String cylinderLine(String role, double boreCm, double lengthCm) {
        return (Model.Cylinder.ROLE_LENGTH.equals(role) ? ED_ROLE_LENGTH : ED_ROLE_GIRTH)
            + " · " + cylinderLine(boreCm, lengthCm);
    }

    /** The cylinder editor. */
    public static final String ED_ADD_TITLE = "Add a cylinder";
    public static final String ED_EDIT_TITLE = "Edit cylinder";
    public static final String ED_NAME = "Name";
    public static final String ED_NAME_HINT = "e.g. Main cylinder";
    public static final String ED_FIRST_NAME = "Main cylinder";
    public static final int ED_NAME_MAX = 30;
    /** The quick names, each "… cylinder". */
    public static final String[] ED_QUICK = { "Main", "Girth", "Length", "Travel" };
    public static final String ED_QUICK_SUFFIX = " cylinder";
    public static final String ED_BORE = "Inside diameter (across the opening)";
    public static final String ED_LENGTH = "Length (flange to end)";
    public static final String ED_EXACT = "Exact";
    /** WHAT IT IS USED FOR (owner, 2026-09-30): a cylinder marked Length is the one the length
     *  track pulls in, and its bore is what the load and the pressure convert at. */
    public static final String ED_USED_FOR = "Used for";
    public static final String ED_ROLE_GIRTH = "Girth";
    public static final String ED_ROLE_LENGTH = "Length";
    public static final String ED_ROLE_LENGTH_NOTE = "The length track pulls in this "
        + "cylinder, and the load is worked out from its inside diameter.";
    public static final String ED_DRAWING_SAID = "Where to measure: the inside diameter across "
        + "the opening, and the length from the base of the flange to the end";
    public static final String ED_DRAWING_INSIDE = "inside";
    public static final String ED_DRAWING_LENGTH = "length";
    public static final String ED_SMALLER_SAID = "Smaller";
    public static final String ED_BIGGER_SAID = "Bigger";
    public static final String ED_SHORTER_SAID = "Shorter";
    public static final String ED_LONGER_SAID = "Longer";
    public static final String ED_CANCEL = "Cancel";
    public static final String ED_ADD = "Add cylinder";
    public static final String ED_SAVE = "Save";
    public static final String ED_REMOVE = "Remove";
    /** Asked before Remove where the rack may already have readings (the trainer's setup):
     *  Settings' own question and reason. */
    public static final String ED_REMOVE_ASK = "Remove this cylinder?";
    public static final String ED_REMOVE_WHY = "Readings already taken keep the dimensions "
        + "they were taken with. Only future ones are affected.";
    /** Said on a card that a tap puts in use (the trainer's setup). */
    public static final String CYL_PICK_SAID = "Double tap to use this one";

    /** The run colours' line beside its preview, and the preview's own words. */
    public static final String COLOURS_NOTE =
        "How much colour a run shows. The preview changes as you tap.";
    public static final String PREVIEW_SAID = "Preview of the run screen";
    public static final String PREVIEW_STEP = "WORK · SET 2";
    public static final String PREVIEW_LEFT = "1:29";
    public static final String PREVIEW_CLOCK = "0:48";
    /** The notification preview's time. */
    public static final String NOTIF_NOW = "now";

    /** Under each first-session choice. */
    public static final String FIRST_TRAINER_LINE = "Six questions";
    public static final String FIRST_TRAINER_ENROLLED_LINE = "Your plan as it stands";
    public static final String FIRST_STARTER_LINE = "Short and gentle";
    public static final String FIRST_OWN_LINE = "Library › New routine";

    /** The Ready summary: a row per choice. */
    public static final String SUM_UNITS = "Units";
    public static final String SUM_CEILING = "Ceiling";
    public static final String SUM_PUMPING = "Pumping";
    public static final String SUM_CYLINDER = "Cylinder";
    public static final String SUM_TRAINING = "Training";
    public static final String SUM_MEASURE = "Measure";
    public static final String SUM_PRIVACY = "Privacy";
    public static final String SUM_RUN = "Run";
    public static final String SUM_NO_CYLINDER = "Not yet";
    public static final String SUM_SOME = "Some experience";
    public static final String SUM_REMINDERS = "reminders";
    public static final String SUM_NOT_DISGUISED = "Not disguised";
    public static final String SUM_LOCK = "lock";
    public static final String SUM_NO_COLOURS = "No colours";
    public static final String SUM_TICKS = "ticks";

    /** The summary's measure row, as the cadence stands. */
    public static String measureSummary(Model.Meas m) {
        int seg = m.cadenceSegment();
        if (seg == Model.Meas.CAD_WEEK) return "1st and 4th session of each week";
        if (seg == Model.Meas.CAD_NEVER) return MEASURE_NEVER;
        if (seg == Model.Meas.CAD_HOURS) return "Every " + m.hours + " h";
        int n = m.everySessions();
        return n == 1 ? "Every session" : "Every " + n + " sessions";
    }
}
