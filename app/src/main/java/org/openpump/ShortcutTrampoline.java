package org.openpump;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * (re-review 4) THE SHORTCUTS' TRAMPOLINE: A LONG-PRESS NEVER ENDS A RUN.
 *
 * Android starts a static launcher shortcut with FLAG_ACTIVITY_NEW_TASK |
 * FLAG_ACTIVITY_CLEAR_TASK. Aimed at SessionActivity, that cleared the running screen's task -
 * and SessionActivity#onDestroy sent the last stop, so "Log measurement" tapped during a run
 * ended it. res/xml/shortcuts.xml aims here instead. This activity has its own taskAffinity
 * (the manifest), so the clear takes only this activity's own task; it shows nothing, keeps no
 * history and stays out of Recents.
 *
 * It does one thing: hands the shortcut's action to the one SessionActivity (launchMode
 * singleTask - an existing screen gets it through onNewIntent, where ScreenEntry holds it back
 * while a run or hold is live) and finishes. It never clears that screen's task, never touches
 * the pump, the service or the model (WiringCheck invariant 118).
 */
public final class ShortcutTrampoline extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        String action = in == null ? null : in.getStringExtra(SessionActivity.EXTRA_SHORTCUT);
        Intent forward = new Intent(this, SessionActivity.class);
        forward.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (action != null) forward.putExtra(SessionActivity.EXTRA_SHORTCUT, action);
        try {
            startActivity(forward);
        } catch (Exception e) {
            android.util.Log.w("PumpShortcut", "could not open the app: " + e);
        }
        finish();
    }
}
