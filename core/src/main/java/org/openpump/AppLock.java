package org.openpump;

/**
 * Task 6 (S16+/S17, dist/round6-options.html) — scoped app lock: WHICH areas of the app
 * challenge with biometric/device-PIN before showing their content, and for how long a
 * pass on that challenge stays trusted.
 *
 * PURE ON PURPOSE — no android imports, so this is one of test.sh's auto-discovered pure
 * sources and SelfTest.java exercises it on the desktop JVM like everything else in that
 * suite. The actual BiometricPrompt / KeyguardManager challenge is Android API surface
 * that cannot run there at all; it lives beside the rest of this app's OS-integration code
 * in SessionActivity.java (search that file for "TASK 6"), which calls into the decision
 * logic here rather than re-deriving it. This is the same split the codebase already draws
 * elsewhere — Compare.java's pure verdict vs. CompareScreen.java's rendering, Gallery.java's
 * pure day-grouping vs. the tiles SessionActivity draws from it — applied to the one part
 * of THIS feature that is decidable without an Activity: which scopes exist, the decision
 * of whether a given scope is gated right now, and the transient "already unlocked this
 * app-session" state that decision reads.
 *
 * WHAT "SCOPE" MEANS: one of the six rows the S16+ mock draws — a master switch
 * (WHOLE_APP is drawn as a row too, not a separate concept) plus four areas. See
 * Model.java's appLockOn/appLockWholeApp/appLockPhotos/appLockMeasurements/
 * appLockSessions/appLockSettings fields for the persisted configuration these decisions
 * are made from, including the documented judgment call on appLockOn's default.
 */
public final class AppLock {
    private AppLock() { }

    public static final int WHOLE_APP    = 0;
    public static final int PHOTOS       = 1;
    public static final int MEASUREMENTS = 2;
    public static final int SESSIONS     = 3;
    public static final int SETTINGS     = 4;
    private static final int SCOPE_COUNT = 5;

    /**
     * "Unlocked this app-session" — TRANSIENT, per the S16+ mock's own wording ("stays
     * open until the app leaves the foreground"). A static array, not a Model field: it
     * must survive an Activity recreate (a locale change, say — SessionActivity absorbs
     * those via configChanges rather than being destroyed, but the field would not
     * survive being an instance field either way given how many other places already
     * assume a fresh construction is a fresh Activity) yet must NEVER be written to disk,
     * and must read as "nothing unlocked yet" on a genuinely fresh process start — which a
     * plain static field gives for free (it starts all-false on class load) and a
     * persisted one would not.
     *
     * Index WHOLE_APP is the one this array's own clearing method ({@link
     * #onLeaveForeground}) deliberately never resets — see that method's doc for why.
     */
    private static final boolean[] unlockedThisSession = new boolean[SCOPE_COUNT];

    /** Every other method here asserts this first, so a stray int reads as a crash rather
     *  than silently deciding "not locked" for a scope that does not exist. */
    private static void checkScope(int scope) {
        if (scope < 0 || scope >= SCOPE_COUNT)
            throw new IllegalArgumentException("not an AppLock scope: " + scope);
    }

    /** The mock's own row label, verbatim (dist/round6-options.html, section "S16+") — the
     *  one source both the Settings toggle row and the locked-screen placeholder draw
     *  their text from, so the two can never describe the same scope two different ways. */
    public static String scopeLabel(int scope) {
        checkScope(scope);
        switch (scope) {
            case WHOLE_APP:      return "Whole app";
            case PHOTOS:         return "Photos & compare";
            case MEASUREMENTS:   return "Measurements & goals";
            case SESSIONS:       return "Session history";
            default:             return "Settings & data export";   // SETTINGS
        }
    }

    /** This scope's PERSISTED configuration toggle, straight off the model — WHOLE_APP
     *  included, since the mock draws it as one more row, not as a separate flag. */
    public static boolean scopeToggle(Model m, int scope) {
        checkScope(scope);
        switch (scope) {
            case WHOLE_APP:      return m.appLockWholeApp;
            case PHOTOS:         return m.appLockPhotos;
            case MEASUREMENTS:   return m.appLockMeasurements;
            case SESSIONS:       return m.appLockSessions;
            default:             return m.appLockSettings;          // SETTINGS
        }
    }

    /** Writes one scope's configuration toggle back onto the model. Callers still have to
     *  persist the model themselves (Store.save) — this only sets the field, matching
     *  every other Settings toggle in SessionActivity, none of which save through a
     *  central setter either. */
    public static void setScopeToggle(Model m, int scope, boolean on) {
        checkScope(scope);
        switch (scope) {
            case WHOLE_APP:      m.appLockWholeApp = on;      break;
            case PHOTOS:         m.appLockPhotos = on;        break;
            case MEASUREMENTS:   m.appLockMeasurements = on;  break;
            case SESSIONS:       m.appLockSessions = on;      break;
            default:             m.appLockSettings = on;      break;   // SETTINGS
        }
    }

    /**
     * IS `scope` GATED RIGHT NOW — the one question every gated screen asks before it
     * draws anything it is meant to protect. True only when ALL of: the master switch
     * ({@link Model#appLockOn}) is on, this scope's own configuration says it should be
     * guarded, and this particular scope has not already been passed earlier in the
     * current app-session.
     *
     * WHOLE-APP SUPERSEDENCE — the mock's own "Whole-app ON greys the per-area rows":
     * when {@link Model#appLockWholeApp} is on, the four AREA scopes (PHOTOS/
     * MEASUREMENTS/SESSIONS/SETTINGS) never gate independently, regardless of their own
     * toggle state — the cold-open challenge already stood between the user and
     * everything the moment the app opened, so asking again per-area would be a second
     * prompt for a question the first one already answered. This is also why the Settings
     * UI greys those four rows when whole-app is on: their own state still exists
     * underneath (a later whole-app-off does not silently turn them all on), it simply
     * cannot do anything while whole-app is active. WHOLE_APP asks the opposite question
     * of itself and this branch does not apply to it.
     */
    public static boolean isLocked(Model m, int scope) {
        checkScope(scope);
        // INCOGNITO'S APP LOCK (0.10) is this same whole-app lock, turned on from Settings ›
        // Privacy (Model#incognitoLock): it gates the cold open exactly as the switch here
        // does, and supersedes the four areas the same way. It never turns the scoped
        // lock's own switches on, so turning incognito off leaves them as they were.
        boolean whole = wholeAppOn(m);
        if (!m.appLockOn && !whole) return false;
        if (scope == WHOLE_APP) return whole && !unlockedThisSession[WHOLE_APP];
        if (whole) return false;
        return scopeToggle(m, scope) && !unlockedThisSession[scope];
    }

    /** Whether the whole-app gate is on: the App lock card's own switches, or incognito's. */
    public static boolean wholeAppOn(Model m) {
        return (m.appLockOn && m.appLockWholeApp) || m.incognitoLock;
    }

    /** Record a successful challenge — the ONLY way {@link #isLocked} can start returning
     *  false for a scope that was gated a moment ago. */
    public static void markUnlocked(int scope) {
        checkScope(scope);
        unlockedThisSession[scope] = true;
    }

    /** True once {@link #markUnlocked} has been called for `scope` since the last time it
     *  was cleared — a plain accessor, mainly for tests; {@link #isLocked} is what the app
     *  itself asks. */
    public static boolean isUnlockedThisSession(int scope) {
        checkScope(scope);
        return unlockedThisSession[scope];
    }

    /**
     * "The app left the foreground" (the S16+ mock's own wording) for the four AREA
     * scopes — called from SessionActivity#onStop, this codebase's own established
     * discriminator for "really gone" (over onPause, which fires for a system dialog that
     * never actually leaves the app foregrounded — see onStop()'s own long-standing
     * comment on ownLaunchPending/userLeaveHint for why the pump-safety logic already
     * treats onPause as too eager a signal, and see SessionActivity's own call site for
     * why THIS call is additionally skipped while ownLaunchPending is set: re-locking
     * Photos every time this app's own camera capture or its share sheet returns would
     * not be a handoff to anyone and matches nothing the mock is guarding against).
     *
     * WHOLE_APP IS DELIBERATELY NEVER RESET HERE. The mock draws a real distinction
     * between the two: "'Whole app' gates the cold open; OTHERWISE each area prompts on
     * first entry per app-session, then stays open until it leaves the foreground" — the
     * "otherwise" marks the re-locking rule as belonging to the four areas, not to
     * whole-app, which reads as a single one-time gate at launch rather than a recurring
     * one. A process that has already passed it stays trusted until the process itself
     * ends — a fresh {@link #isLocked} call after a genuine process restart reads
     * `unlockedThisSession[WHOLE_APP]` as false again for free, since a static field
     * starts false on class load, with no explicit reset needed for that case.
     */
    public static void onLeaveForeground() {
        for (int s = 0; s < SCOPE_COUNT; s++)
            if (s != WHOLE_APP) unlockedThisSession[s] = false;
    }

    /**
     * A genuinely fresh state, WHOLE_APP included — unlike {@link #onLeaveForeground}.
     * Two callers: SelfTest, between assertions that must not see an earlier assertion's
     * markUnlocked leak in (this state is a bare static array, so nothing else resets it
     * between test cases the way a fresh Model would); and SessionActivity's "Erase this
     * app's data" flow, which already re-locks the unrelated developer-options gate
     * (devUnlocked) the same way — "the door closes behind an erase, like a fresh launch"
     * applies here too, and WHOLE_APP is exactly the one flag onLeaveForeground() cannot
     * reach to do it.
     */
    /**
     * (the incognito safety review, M5) QUICK HIDE RE-ARMS THE WHOLE-APP LOCK. The whole-app
     * lock is a one-time gate at the cold open (onLeaveForeground never resets it), so after a
     * quick hide the app came back to anyone holding the phone. Now quick hide - and only quick
     * hide (WiringCheck invariant 209) - arms it again when it is on; the way back asks
     * relockAsks whether it may be put in front of the person.
     */
    public static void relockWholeApp(Model m) {
        if (m != null && wholeAppOn(m)) unlockedThisSession[WHOLE_APP] = false;
    }

    /**
     * WHETHER THE WAY BACK FROM A QUICK HIDE ASKS FOR THE UNLOCK NOW. Only when the lock is
     * armed (`locked`: isLocked(m, WHOLE_APP)) and nothing about the pump is live or owed:
     * `unsafe` (SessionActivity#stillUnsafe - a run, a hold, a check, a stop not yet confirmed)
     * is false, the hold's phase (HoldForeground) is NONE, and no safety question - the vent's
     * give-up, the killed-run, run-ended or orphaned-hold message - is in front of the person.
     * Otherwise the lock stands down for this return: it is never put in front of the run
     * screen, a hold, an unconfirmed stop or a safety question, and never between the person
     * and STOP.
     */
    public static boolean relockAsks(boolean locked, boolean unsafe, int holdPhase,
                                     boolean safetyQuestionUp) {
        return locked && !unsafe && holdPhase == HoldForeground.NONE && !safetyQuestionUp;
    }

    public static void clearAllSessionState() {
        for (int s = 0; s < SCOPE_COUNT; s++) unlockedThisSession[s] = false;
    }
}
