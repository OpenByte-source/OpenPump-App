package org.openpump;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * (the incognito safety review, I1) THE LAUNCHER ENTRIES' TRAMPOLINE: SWITCHING THE ICON NEVER
 * TOUCHES THE RUNNING SCREEN.
 *
 * The home-screen icon is "OpenPump" or a disguise - "Fitness log", "Habits" or "Notes" - one
 * activity-alias each, and Settings › Privacy switches them by disabling all but one. Android then removes every task whose ROOT INTENT
 * names the disabled component: DONT_KILL_APP spares the process, not the task, and a task
 * keeps the alias it was opened by as its intent. When the aliases started SessionActivity
 * itself, the running screen's task was rooted by an alias - switching the icon finished the
 * screen, whose onDestroy sends the last stop and drops the vent watch, and tore down one-time
 * safety messages before anyone read them. Only the icon switch's wait (stillUnsafe) kept that
 * away from a live run.
 *
 * So every alias starts THIS. It shows nothing (Theme.NoDisplay), keeps no history, stays out
 * of Recents and has a task affinity of its own; it starts the one SessionActivity BY NAME,
 * with the launcher's own intent (as RunService#appIntent builds it: the task brought to the
 * front as it is, or the app started), and finishes. The screen's task is then rooted by
 * SessionActivity, which no alias switch can match; a switch removes only this trampoline's
 * empty task, and RunService#onTaskRemoved knows that one says nothing about a run. The wait is
 * kept all the same (WiringCheck invariant 198; OneScreenManifestTest pins the manifest half).
 *
 * It forwards nothing from the caller - it is exported, as a launcher entry must be - and
 * touches nothing else: no pump, no service, no model.
 */
public final class LauncherTrampoline extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent open = new Intent(this, SessionActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(open);
        } catch (Exception e) {
            android.util.Log.w("PumpLauncher", "could not open the app: " + e);
        }
        finish();
    }
}
