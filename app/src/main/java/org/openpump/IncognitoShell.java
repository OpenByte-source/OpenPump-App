package org.openpump;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * INCOGNITO (0.10) - THE ANDROID HALF: what the phone itself has to be told when a switch in
 * Settings › Privacy changes. The rules (which launcher entry, which features) are
 * {@link Incognito}'s, pure and tested; this only carries them out.
 *
 * Two component switches, both with DONT_KILL_APP so the process - and a run it may be
 * keeping - is never ended by them:
 *   - THE LAUNCHER: the "OpenPump" activity-alias of LauncherTrampoline and one for each
 *     disguise - "Fitness log", "Habits", "Notes" (Incognito#LAUNCHERS). Exactly one is
 *     enabled. The chosen one is enabled FIRST and only then every other one disabled, so a
 *     process that dies between the calls leaves two icons (the next apply fixes it), never
 *     none - an app with no launcher entry cannot be opened from the
 *     home screen at all (WiringCheck invariant 194). DONT_KILL_APP does NOT spare a TASK:
 *     Android removes every task whose root intent names the entry switched off (the
 *     incognito safety review, I1). That is why the entries are aliases of a trampoline that
 *     starts SessionActivity by name - the screen's task is never one of them - and why the
 *     one caller, SessionActivity#applyIncognito, still waits for a quiet moment (198).
 *   - THE WIDGET: its provider, disabled to take it off the home screen and out of the widget
 *     list. Its face is pushed idle first, so a launcher that keeps a last frame keeps one
 *     that says nothing about a run.
 *
 * Both are no-ops when the phone already agrees, so they are safe to call on every resume.
 */
public final class IncognitoShell {

    private IncognitoShell() { }

    /** Makes the enabled launcher entry the one for this identity (Incognito#identity) - every
     *  other one, the real one or another disguise's, off. Returns true when it had to change
     *  anything (the launcher then takes a few seconds to catch up). */
    public static boolean applyLauncher(Context c, int identity) {
        if (c == null) return false;
        PackageManager pm = c.getPackageManager();
        if (pm == null) return false;
        boolean changed = false;
        try {
            // FIRST the one to enable, THEN every other one off: never zero entries.
            String want = Incognito.launcherFor(identity);
            changed |= setEnabled(pm, c, want, true);
            for (int i = 0; i < Incognito.LAUNCHERS.length; i++) {
                String l = Incognito.LAUNCHERS[i];
                if (!Incognito.launcherEnabled(l, identity)) changed |= setEnabled(pm, c, l, false);
            }
        } catch (Exception e) {
            android.util.Log.w("PumpIncognito", "could not switch the launcher entry: " + e);
        }
        return changed;
    }

    /** Whether the launcher entry for this identity is the one - the only one - the phone has
     *  enabled now. */
    public static boolean launcherIs(Context c, int identity) {
        try {
            PackageManager pm = c.getPackageManager();
            for (int i = 0; i < Incognito.LAUNCHERS.length; i++) {
                String l = Incognito.LAUNCHERS[i];
                if (isEnabled(pm, c, l) != Incognito.launcherEnabled(l, identity)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ------------------------------------------------ each identity's own resources
     *
     * Incognito names them (pure, tested); these are the same rows as R ids, in the same order
     * as Incognito#DISGUISES - WiringCheck invariant 232 reads both tables and holds them
     * equal, so a disguise can never be drawn with another's icon. */

    /** Each disguise's launcher icon, in Incognito#DISGUISE_ICONS order. */
    private static final int[] DISGUISE_ICON_RES = {
        R.mipmap.ic_fitness, R.mipmap.ic_habits, R.mipmap.ic_notes
    };
    /** Each disguise's lock host, in Incognito#DISGUISE_LOCK_HOSTS order. */
    private static final Class<?>[] DISGUISE_LOCK_HOST = {
        LockHost.FitnessLog.class, LockHost.Habits.class, LockHost.Notes.class
    };

    /** Each disguise's splash theme (API 33+), in Incognito's index order. */
    private static final int[] DISGUISE_SPLASH = {
        R.style.SplashFitnessLog, R.style.SplashHabits, R.style.SplashNotes
    };

    /** The launcher icon for this identity - the one its alias shows. */
    public static int iconRes(int identity) {
        return Incognito.disguised(identity) ? DISGUISE_ICON_RES[identity] : R.mipmap.ic_launcher;
    }

    /** The activity the fingerprint or PIN prompt is asked from for this identity: its lock
     *  host (LockHost), or null - the app itself asks from its own screen. */
    public static Class<?> lockHost(int identity) {
        return Incognito.disguised(identity) ? DISGUISE_LOCK_HOST[identity] : null;
    }

    /** The identity the splash was last set for in this process; none yet. */
    private static int splashSetFor = Integer.MIN_VALUE;

    /**
     * THE SPLASH OF THE NEXT COLD START. Android draws the first frame from the screen's own
     * theme before any of this app's code runs - on Android 12 and up as its own splash, with
     * the app's real launcher icon unless told otherwise. From Android 13 an app may name the
     * theme that splash is drawn from, and Android keeps it for every later launch: while
     * disguised it is the disguise's (its icon on the app's dark ground), and as itself the
     * manifest's own again (ID_NULL). Below 13 there is no such call, so the manifest's splash
     * shows no logo there at all (values/styles.xml, values-v31) and can never contradict a
     * disguise. WiringCheck invariant 234.
     */
    public static void applySplash(android.app.Activity a, int identity) {
        if (a == null || android.os.Build.VERSION.SDK_INT < 33) return;
        if (identity == splashSetFor) return;   // asked on every resume; told once a process
        try {
            a.getSplashScreen().setSplashScreenTheme(Incognito.disguised(identity)
                ? DISGUISE_SPLASH[identity] : android.content.res.Resources.ID_NULL);
            splashSetFor = identity;
        } catch (Exception e) {
            android.util.Log.w("PumpIncognito", "could not set the splash: " + e);
        }
    }

    /** Takes the widget off the home screen (and out of the widget list), or puts it back in
     *  the list. Returns true when it had to change anything. */
    public static boolean applyWidget(Context c, boolean hide) {
        if (c == null) return false;
        try {
            PackageManager pm = c.getPackageManager();
            ComponentName w = new ComponentName(c, PumpWidgetProvider.class);
            int state = pm.getComponentEnabledSetting(w);
            boolean enabledNow = state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            if (enabledNow == !hide) return false;
            if (hide) pushWidgetIdle(c);
            pm.setComponentEnabledSetting(w,
                hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                     : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP);
            return true;
        } catch (Exception e) {
            android.util.Log.w("PumpIncognito", "could not switch the widget: " + e);
            return false;
        }
    }

    private static void pushWidgetIdle(Context c) {
        try {
            android.appwidget.AppWidgetManager mgr = android.appwidget.AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, PumpWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            android.widget.RemoteViews v = PumpWidgetProvider.prepareIdle(c);
            for (int i = 0; i < ids.length; i++) mgr.updateAppWidget(ids[i], v);
        } catch (Exception ignored) { }
    }

    /** The launcher entry's state as the phone has it: the manifest's default when nobody
     *  has set it (enabled for the real one only). */
    private static boolean isEnabled(PackageManager pm, Context c, String cls) {
        int s = pm.getComponentEnabledSetting(new ComponentName(c.getPackageName(), cls));
        if (s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
        if (s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            return Incognito.LAUNCHER_REAL.equals(cls);
        return false;
    }

    private static boolean setEnabled(PackageManager pm, Context c, String cls, boolean on) {
        if (isEnabled(pm, c, cls) == on) return false;
        pm.setComponentEnabledSetting(new ComponentName(c.getPackageName(), cls),
            on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
               : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
        return true;
    }
}
