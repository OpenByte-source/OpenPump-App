package org.openpump;

/**
 * INCOGNITO (0.10, the owner's decisions of 2026-09-26) - THE PURE HALF.
 *
 * Settings › Privacy. Every feature has its OWN switch ("allow me to select which incognito
 * features I want"), and a master "Incognito mode" switch turns the person's CHOSEN set on
 * and off together. A feature can also be on without the master.
 *
 * HOW THE MASTER AND THE SWITCHES FIT (one rule, stated once, pinned in IncognitoTest):
 *   - Each feature's switch is the feature's state. What the app does reads only that.
 *   - The chosen set ({@link Model#incognitoSet}) is what the master turns on and off.
 *   - Master ON turns the set on. The first time, before anything was chosen, the set is
 *     {@link #FIRST_SET} minus whatever was already on by itself (so a switch the person had
 *     on before stays theirs, and is not taken away again by master OFF).
 *   - Master OFF turns the set off. Anything on outside the set stays on.
 *   - A switch flipped WHILE the master is on is the person choosing: it joins or leaves the
 *     set. A switch flipped while the master is off is that feature on its own.
 *
 * WHAT NONE OF THIS TOUCHES (SAFETY.md): what the pump is sent, when, or how a run stops.
 * STOP is on every run notification in every mode, safety notices keep their plain words
 * once the phone is unlocked, and quick hide's STOP goes through the one STOP path the
 * notification's own STOP uses (WiringCheck invariants 190-199 and 209).
 */
public final class Incognito {

    private Incognito() { }

    /* ------------------------------------------------------------------ the features */

    /** The home-screen icon and name are a disguise - "Fitness log", "Habits" or "Notes", the
     *  person's choice (a launcher activity-alias each; see THE DISGUISES). */
    public static final int ICON = 1;
    /** The run and hold notifications say "Session running" and hide on the lock screen. */
    public static final int NOTIFICATIONS = 1 << 1;
    /** Safety notices read neutral words on the lock screen, plain once unlocked. */
    public static final int SAFETY_WORDS = 1 << 2;
    /** FLAG_SECURE: blank in recent apps, and no screenshots or screen recording. */
    public static final int RECENTS = 1 << 3;
    /** Double-tap the top bar to leave to the home screen. */
    public static final int QUICK_HIDE = 1 << 4;
    /** Reminders say only "Reminder". */
    public static final int REMINDERS = 1 << 5;
    /** The whole-app lock (AppLock#WHOLE_APP), on through incognito. */
    public static final int APP_LOCK = 1 << 6;
    /** The home-screen widget is taken off the home screen and out of the widget list. */
    public static final int WIDGET = 1 << 7;

    /** Every feature, in the order Settings draws them. */
    public static final int[] FEATURES = {
        ICON, NOTIFICATIONS, SAFETY_WORDS, RECENTS, QUICK_HIDE, REMINDERS, APP_LOCK, WIDGET
    };
    public static final int ALL = ICON | NOTIFICATIONS | SAFETY_WORDS | RECENTS | QUICK_HIDE
        | REMINDERS | APP_LOCK | WIDGET;
    /** What the master brings the first time: everything but RECENTS, which also blocks
     *  screenshots and so is the owner's "its own switch, default OFF". */
    public static final int FIRST_SET = ALL & ~RECENTS;

    /** Whether feature `f` is on. */
    public static boolean on(Model m, int f) {
        if (m == null) return false;
        switch (f) {
            case ICON:          return m.disguiseIcon;
            case NOTIFICATIONS: return m.discreetNotifications;
            case SAFETY_WORDS:  return m.neutralSafetyNotices;
            case RECENTS:       return m.secureWindow;
            case QUICK_HIDE:    return m.quickHide;
            case REMINDERS:     return m.discreetReminders;
            case APP_LOCK:      return m.incognitoLock;
            case WIDGET:        return m.hideWidget;
            default: throw new IllegalArgumentException("not an incognito feature: " + f);
        }
    }

    /** Sets feature `f`'s own switch - nothing else. The caller saves the model and applies
     *  what the phone has to be told (the launcher, the window, the widget). */
    static void put(Model m, int f, boolean v) {
        switch (f) {
            case ICON:          m.disguiseIcon = v; break;
            case NOTIFICATIONS: m.discreetNotifications = v; break;
            case SAFETY_WORDS:  m.neutralSafetyNotices = v; break;
            case RECENTS:       m.secureWindow = v; break;
            case QUICK_HIDE:    m.quickHide = v; break;
            case REMINDERS:     m.discreetReminders = v; break;
            case APP_LOCK:      m.incognitoLock = v; break;
            case WIDGET:        m.hideWidget = v; break;
            default: throw new IllegalArgumentException("not an incognito feature: " + f);
        }
    }

    /** Every feature that is on, as bits. */
    public static int mask(Model m) {
        int out = 0;
        for (int i = 0; i < FEATURES.length; i++) if (on(m, FEATURES[i])) out |= FEATURES[i];
        return out;
    }

    /** The master switch. See the class note for the rule. */
    public static void setMaster(Model m, boolean turnOn) {
        int set = m.incognitoSet & ALL;
        if (turnOn) {
            if (set == 0) set = FIRST_SET & ~mask(m);
            if (set == 0) set = FIRST_SET;
            m.incognitoSet = set;
            for (int i = 0; i < FEATURES.length; i++)
                if ((set & FEATURES[i]) != 0) put(m, FEATURES[i], true);
            m.incognito = true;
        } else {
            for (int i = 0; i < FEATURES.length; i++)
                if ((set & FEATURES[i]) != 0) put(m, FEATURES[i], false);
            m.incognito = false;
        }
    }

    /** One feature's switch. Under the master it is the person choosing the set. */
    public static void setFeature(Model m, int f, boolean v) {
        put(m, f, v);
        if (m.incognito) {
            if (v) m.incognitoSet = (m.incognitoSet & ALL) | f;
            else m.incognitoSet = (m.incognitoSet & ALL) & ~f;
        }
    }

    /** The feature's short name, for the master card's "Covers" line. */
    public static String shortName(int f) {
        switch (f) {
            case ICON:          return "icon";
            case NOTIFICATIONS: return "notifications";
            case SAFETY_WORDS:  return "lock-screen warnings";
            case RECENTS:       return "recent apps";
            case QUICK_HIDE:    return "quick hide";
            case REMINDERS:     return "reminders";
            case APP_LOCK:      return "app lock";
            case WIDGET:        return "widget";
            default: throw new IllegalArgumentException("not an incognito feature: " + f);
        }
    }

    /** What the master turns on and off, said in words: "Covers icon, notifications and
     *  widget." - or, before anything was chosen, what the first turn-on brings. */
    public static String coversLine(Model m) {
        int set = m.incognitoSet & ALL;
        if (set == 0) set = FIRST_SET & ~mask(m);
        if (set == 0) set = FIRST_SET;
        StringBuilder sb = new StringBuilder();
        int n = 0, total = Integer.bitCount(set);
        for (int i = 0; i < FEATURES.length; i++) {
            if ((set & FEATURES[i]) == 0) continue;
            if (n > 0) sb.append(n == total - 1 ? " and " : ", ");
            sb.append(shortName(FEATURES[i]));
            n++;
        }
        return "Covers " + sb + ".";
    }

    /* ------------------------------------------------------------------ the launcher */

    /*
     * THE DISGUISES (0.10; the owner, 2026-09-28: "add 2 more options of the incognito mode app
     * name and logo"). With ICON on, the app shows itself as ONE of three plain utility apps,
     * the person's choice (Model#disguiseAs); with it off, as itself. Everything that says
     * which - the launcher entry, the name, the icon, the shortcuts, the lock's host - is one
     * row of the tables below, indexed by the disguise, so a fourth is one more row and nothing
     * is special-cased. An IDENTITY is what the app is shown as right now: REAL, or a disguise
     * (identity(Model)). WiringCheck invariants 194 and 232-234 and OneScreenManifestTest hold
     * the manifest, the resources and the Android half to these tables.
     */

    /** The app as itself: "OpenPump" and its own logo. */
    public static final int REAL = -1;
    public static final int FITNESS_LOG = 0;
    public static final int HABITS = 1;
    public static final int NOTES = 2;
    /** Every disguise, in the order the choice sheet draws them. */
    public static final int[] DISGUISES = { FITNESS_LOG, HABITS, NOTES };
    /** The disguise an old save, a fresh install and a stray value get: the one 0.10 had. */
    public static final int DISGUISE_DEFAULT = FITNESS_LOG;

    /** How each disguise is saved (Model#toJson "disguiseAs") - words, not the index, so a
     *  reordering here never turns one person's disguise into another. */
    static final String[] DISGUISE_KEYS = { "fitness", "habits", "notes" };
    /** The home-screen name of each (its alias's android:label). */
    public static final String[] DISGUISE_NAMES = { "Fitness log", "Habits", "Notes" };
    /** What each looks like, under its name in the choice sheet. */
    public static final String[] DISGUISE_LOOKS = {
        "A pulse line on teal", "A check mark on green", "A lined page on yellow"
    };
    /** Each one's launcher entry (an activity-alias of LauncherTrampoline). */
    public static final String[] DISGUISE_LAUNCHERS = {
        "org.openpump.LauncherFitnessLog", "org.openpump.LauncherHabits",
        "org.openpump.LauncherNotes"
    };
    /** Each one's launcher icon, a mipmap - and its round form, the same name + "_round". */
    public static final String[] DISGUISE_ICONS = { "ic_fitness", "ic_habits", "ic_notes" };
    /** Each one's static shortcuts (res/xml): the same three, with its own icon. */
    public static final String[] DISGUISE_SHORTCUTS = {
        "shortcuts_fitness", "shortcuts_habits", "shortcuts_notes"
    };
    /** Each one's lock host - an activity declared with the disguise's own icon and name that
     *  the fingerprint or PIN prompt is asked from, so the phone's prompt names and pictures
     *  the disguise, never the real app (SessionActivity#challengeUnlock, invariant 233). */
    public static final String[] DISGUISE_LOCK_HOSTS = {
        "org.openpump.LockHost$FitnessLog", "org.openpump.LockHost$Habits",
        "org.openpump.LockHost$Notes"
    };

    /** The real launcher entry - the only one the manifest enables. */
    public static final String LAUNCHER_REAL = "org.openpump.LauncherOpenPump";
    public static final String REAL_NAME = "OpenPump";
    public static final String REAL_ICON = "ic_launcher";
    public static final String REAL_SHORTCUTS = "shortcuts";

    /** Every launcher entry: the real one first, then each disguise's. Exactly one is enabled
     *  at a time; the manifest enables the real one. */
    public static final String[] LAUNCHERS = launchers();

    private static String[] launchers() {
        String[] out = new String[1 + DISGUISES.length];
        out[0] = LAUNCHER_REAL;
        for (int i = 0; i < DISGUISES.length; i++) out[1 + i] = DISGUISE_LAUNCHERS[DISGUISES[i]];
        return out;
    }

    /** A stored disguise, held to the ones that exist; anything else is the default. */
    public static int disguiseChoice(int stored) {
        return stored >= 0 && stored < DISGUISE_NAMES.length ? stored : DISGUISE_DEFAULT;
    }

    /** The saved word for a disguise. */
    public static String disguiseKey(int disguise) {
        return DISGUISE_KEYS[disguiseChoice(disguise)];
    }

    /** A saved word back to its disguise. Missing - every save written before the choice
     *  existed, so a person with the disguise on keeps "Fitness log" - or unknown (a newer
     *  app's) is the default. */
    public static int disguiseFromKey(String key) {
        if (key != null)
            for (int i = 0; i < DISGUISE_KEYS.length; i++)
                if (DISGUISE_KEYS[i].equals(key.trim())) return i;
        return DISGUISE_DEFAULT;
    }

    /** What the app is shown as now: REAL with the icon switch off, else the chosen disguise. */
    public static int identity(Model m) {
        if (m == null || !m.disguiseIcon) return REAL;
        return disguiseChoice(m.disguiseAs);
    }

    /** Whether `identity` is a disguise; anything else, REAL included, is the app itself. */
    public static boolean disguised(int identity) {
        return identity >= 0 && identity < DISGUISE_NAMES.length;
    }

    /** The launcher entry for this identity. */
    public static String launcherFor(int identity) {
        return disguised(identity) ? DISGUISE_LAUNCHERS[identity] : LAUNCHER_REAL;
    }

    /** Whether `launcher` is the one to enable for this identity - true for exactly one of
     *  {@link #LAUNCHERS}, whatever the identity. */
    public static boolean launcherEnabled(String launcher, int identity) {
        return launcherFor(identity).equals(launcher);
    }

    /** The name the app shows for itself: its own top bar, recent apps, its lock. */
    public static String shownName(int identity) {
        return disguised(identity) ? DISGUISE_NAMES[identity] : REAL_NAME;
    }

    /** The launcher icon (a mipmap's name) for this identity. */
    public static String iconName(int identity) {
        return disguised(identity) ? DISGUISE_ICONS[identity] : REAL_ICON;
    }

    /** The static shortcuts file (res/xml) this identity's launcher entry carries. */
    public static String shortcutsFor(int identity) {
        return disguised(identity) ? DISGUISE_SHORTCUTS[identity] : REAL_SHORTCUTS;
    }

    /** The activity the fingerprint or PIN prompt is asked from: a disguise's lock host, or
     *  null for the app itself, which asks from its own screen as it always has. */
    public static String lockHostFor(int identity) {
        return disguised(identity) ? DISGUISE_LOCK_HOSTS[identity] : null;
    }

    /** The name a backup is offered to be saved under: it lands in the phone's files, where
     *  anyone browsing them reads it, so under a disguise it is the disguise's -
     *  "notes-backup.zip" - never the word "pump". Restore reads the contents, not the name. */
    public static String backupFileName(int identity) {
        return (disguised(identity) ? DISGUISE_KEYS[identity] : "pump") + "-backup.zip";
    }

    /** The subject line a data export is handed to the share sheet with. */
    public static String exportSubject(int identity) {
        return disguised(identity) ? "Data export" : "Pump data export";
    }

    /** The icon sheet's pick: the app itself (the icon switch off; the chosen disguise is kept
     *  for next time) or a disguise (chosen, and the switch on). Through setFeature, so under
     *  the master it is the person choosing the set, exactly as a switch is. */
    public static void choose(Model m, int identity) {
        if (disguised(identity)) {
            m.disguiseAs = identity;
            setFeature(m, ICON, true);
        } else {
            setFeature(m, ICON, false);
        }
    }

    /** The one screen, by name. Every way into the app starts it by this name - the launcher
     *  entries through LauncherTrampoline, the shortcuts through ShortcutTrampoline, the
     *  notices and the widget directly - so it is the root of the task a run lives in. */
    public static final String SCREEN_CLASS = "org.openpump.SessionActivity";

    /**
     * (the incognito safety review, I1) WHETHER A TASK ANDROID REMOVED WAS THE ONE SCREEN'S.
     *
     * Switching a launcher entry off removes every task whose root intent names it, and
     * Android then tells the running service (onTaskRemoved) - for ANY of the app's tasks.
     * The launcher entries are aliases of a trampoline that shows nothing and finishes, so the
     * task they root holds no screen: its removal says nothing about a run, and must not post
     * "the run was interrupted" or stop the service a run or a vent is kept by. The same holds
     * for the shortcuts' trampoline. `rootClass` is the removed task's root component's class;
     * unknown (null or empty) counts as the screen's, so the notice that tells the truth about
     * a run is never skipped for want of a name.
     */
    public static boolean isScreenTask(String rootClass) {
        return rootClass == null || rootClass.isEmpty() || SCREEN_CLASS.equals(rootClass);
    }

    /* ------------------------------------------------------------------ quick hide */

    /** What a double-tap does during a run, the person's choice (A, B, C). */
    public static final int QH_LEAVE = 0;
    public static final int QH_HOLD = 1;
    public static final int QH_STOP = 2;
    /** The owner's default: C - leave and STOP, through the normal vent path. */
    public static final int QH_DEFAULT = QH_STOP;

    /** A stored choice, held to the three that exist; anything else is the default. */
    public static int quickHideChoice(int stored) {
        return stored == QH_LEAVE || stored == QH_HOLD || stored == QH_STOP ? stored : QH_DEFAULT;
    }

    /** What the double-tap does now. */
    public static final int DO_LEAVE = 0;
    /** Ask for the run's Hold (with its limit), the way the notification's HOLD does, then
     *  leave. */
    public static final int DO_HOLD_THEN_LEAVE = 1;
    /** STOP through the notification's own STOP path (the vent watch confirms it), then
     *  leave. */
    public static final int DO_STOP_THEN_LEAVE = 2;

    /**
     * The quick-hide decision.
     *
     * Outside live work it just leaves (and leaving does what leaving always does: a hold on
     * the cuff is vented on the way out, SessionActivity#onStop). During live work it is the
     * person's choice. B, pause, exists only for a routine run that is not already held - the
     * run's Hold; with a Hold already up, B just leaves (the Hold and its limit stay). A self-
     * test or a validation run has no Hold, so B STOPs it: the safe side of "pause".
     */
    public static int quickHide(int choice, boolean liveWork, boolean routineRun,
                                boolean holding) {
        if (!liveWork) return DO_LEAVE;
        int c = quickHideChoice(choice);
        if (c == QH_LEAVE) return DO_LEAVE;
        if (c == QH_STOP) return DO_STOP_THEN_LEAVE;
        if (!routineRun) return DO_STOP_THEN_LEAVE;
        return holding ? DO_LEAVE : DO_HOLD_THEN_LEAVE;
    }

    /* ---- quick hide B's pause, once asked (the incognito safety review, I2) ----
     *
     * The person chose "leave and hold" over "leave, the run keeps going": while the app is
     * hidden the pump is not to go on working on them. When the Hold cannot go up - a rest
     * between sets, a frozen phase, no live reading, the pump busy or not answering, the pump
     * refusing it, or the run moving on as it is acknowledged - STOP is the closest safe
     * outcome to what they asked; carrying on, unwatched, is the one they turned down. The same
     * rule as a self-test or validation run, which cannot be held: the safe side of pause is
     * STOP. SessionActivity#quickHide asks pauseAsked right after asking for the Hold, and a
     * pause that waits on the pump ends through pauseAnswered (WiringCheck invariant 199). */

    /** The run is paused: a Hold is up (HOLDING). */
    public static final int PAUSED = 0;
    /** The Hold was written and the pump has not said yet: the pause waits on its answer. */
    public static final int PAUSE_WAITS = 1;
    /** No Hold is up or coming: STOP, through the notification's own STOP. */
    public static final int PAUSE_STOPS = 2;

    /** Right after quick hide asked for the Hold: `holdUp` a Hold is up now, `holdWritten`
     *  this ask wrote one the pump has not answered yet. */
    public static int pauseAsked(boolean holdUp, boolean holdWritten) {
        if (holdUp) return PAUSED;
        return holdWritten ? PAUSE_WAITS : PAUSE_STOPS;
    }

    /** The pump's word on the Hold a pause waits on - or that Hold no longer possibly up. Only
     *  HOLDING keeps the pause; refused, never written, undone for want of an answer, the pump
     *  not answering, or taken as the run moved on: STOP. */
    public static int pauseAnswered(boolean holdUp) {
        return holdUp ? PAUSED : PAUSE_STOPS;
    }

    /** Two taps on the top bar count as one double-tap when the second lands within the
     *  platform's double-tap timeout of the first. A first tap (prev <= 0) never does. */
    public static boolean isDoubleTap(long prevTapMs, long nowMs, long timeoutMs) {
        if (prevTapMs <= 0L) return false;
        long gap = nowMs - prevTapMs;
        return gap >= 0L && gap <= timeoutMs;
    }

    /* ------------------------------------------------------------------ the words */

    /** The discreet run and hold notifications' title. */
    public static final String RUN_TITLE = "Session running";
    /** The lock-screen version of a discreet notification. */
    public static final String HIDDEN = "Contents hidden";
    /** A safety notice on the lock screen (option 3): neutral, and still urgent - the safety
     *  alerts ring and vibrate (RunService#CHANNEL_ALERT_ID). */
    public static final String SAFETY_LOCKED_TITLE = "Session needs attention now";
    public static final String SAFETY_LOCKED_TEXT = "Unlock to see what to do.";
    /** (the incognito safety review, M1) The ordinary venting notice on the lock screen - every
     *  STOP, release and run end shows it for a few seconds. Calm, so the alarm's words are kept
     *  for an alarm and never learned as noise. */
    public static final String ENDING_LOCKED_TITLE = "Session ending";
    /** A discreet reminder, all of it. */
    public static final String REMINDER_TITLE = "Reminder";
    /** STOP, on every run notification, in every mode. */
    public static final String STOP_LABEL = "STOP · vent now";
    /** STOP on the lock-screen version: the same button, without the word "vent". */
    public static final String STOP_LABEL_LOCKED = "STOP";

    /**
     * The discreet run notification's body: "12:30 left", or, while the run's Hold is up,
     * "Paused · ends in 4:32" (its limit ends the run) - never a pressure, a routine's name,
     * a track or a preset. `holdVentsIn` is the plain line's InRunHold#ventsIn, "" when no
     * Hold is up.
     */
    public static String runText(String countdown, String holdVentsIn) {
        String hold = holdVentsIn == null ? "" : holdVentsIn.trim();
        if (hold.startsWith(InRunHold.VENTS_IN)) {
            String t = hold.substring(InRunHold.VENTS_IN.length()).trim();
            return t.isEmpty() ? "Paused" : "Paused · ends in " + t;
        }
        if (!hold.isEmpty()) return "Paused";
        String left = countdown == null ? "" : countdown.trim();
        return left.isEmpty() ? "In progress" : left + " left";
    }

    /** The discreet measurement-hold notice's body: when it ends on its own. */
    public static String heldText(long leftSec) {
        long s = Math.max(0L, leftSec);
        return "Ends on its own in " + s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }
}
