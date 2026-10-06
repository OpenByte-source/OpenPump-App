package org.openpump;

/**
 * (re-review 4) WHAT AN INTENT ARRIVING AT THE ONE SCREEN MAY DO.
 *
 * SessionActivity is the one screen (launchMode singleTask, and a duplicate finishes itself
 * before it touches anything), so every way into the app arrives at the screen that may be
 * running a run or holding a cuff: the launcher icon, the run's and the hold's notices, the
 * widget, a reminder, and a launcher shortcut (through ShortcutTrampoline). What it may do is
 * decided here, and ScreenEntryTest pins it; WiringCheck invariant 109 holds the screen to it.
 *
 * Only a shortcut carries an action. With a run or hold live - anything that may have the cuff
 * under pressure (SessionActivity#stillUnsafe) - it is held back: it would otherwise take the
 * screen off the run ("Progress photos"), open a capture over a hold ("Log measurement"), or
 * change the selected routine and ask to start another ("Start last routine"). With nothing
 * live it is routed, and "Start last routine" still lands on START's own confirmation and
 * gate - a shortcut is one tap closer to the button, never a way round it.
 *
 * (re-review 5) AND NEVER PAST A LOCK. With the whole-app lock up - a cold open whose
 * challenge was cancelled leaves the lock card on screen - a shortcut is kept, not routed: the
 * areas' own locks stand down in whole-app mode, so "Progress photos" routed then opened the
 * photos over the card. It is applied only once the whole app is unlocked, and then it asks
 * again - the run gate, and the lock of the area it goes to.
 */
public final class ScreenEntry {

    private ScreenEntry() { }

    /** No action: the screen comes forward as it is. */
    public static final int NOTHING = 0;
    /** A shortcut with a run or hold live: not done, and said why. */
    public static final int HELD_BACK = 1;
    /** A shortcut with nothing live: routed to its screen. */
    public static final int ROUTE = 2;
    /** (re-review 5) A shortcut with the whole app locked: kept, and applied only after the
     *  unlock. Nothing is drawn; the lock card stays. */
    public static final int HELD_FOR_UNLOCK = 3;

    /**
     * @param action         the shortcut's action, or null for an arrival that carries none.
     * @param wholeAppLocked the whole-app lock is up (AppLock.isLocked(model, WHOLE_APP)).
     * @param busy           a run or hold is live, or a stop is unevidenced.
     */
    public static int onArrival(String action, boolean wholeAppLocked, boolean busy) {
        if (action == null) return NOTHING;
        if (wholeAppLocked) return HELD_FOR_UNLOCK;
        return busy ? HELD_BACK : ROUTE;
    }

    public static final String HELD_FOR_UNLOCK_WORDS =
        "Unlock the app first - the shortcut opens after that.";

    public static final String HELD_BACK_WORDS =
        "A run or hold is on, so the shortcut does nothing until it has ended.";
}
