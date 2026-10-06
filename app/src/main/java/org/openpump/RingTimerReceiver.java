package org.openpump;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The ring timer's alarm arriving, the phone coming back from a reboot, and exact alarms
 * being allowed - the three times {@link RingTimer} is reached from outside the app. Its own
 * receiver, not ReminderReceiver: that one loads the whole saved model and re-lines-up every
 * reminder, and a "take it off" must not wait on, or fail with, any of that.
 */
public final class RingTimerReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context c, Intent i) {
        if (c == null || i == null) return;
        String action = i.getAction();
        if (RingTimer.ACTION_RING.equals(action)) {
            RingTimer.fire(c, i.getLongExtra(RingTimer.EXTRA_ENDS_AT, 0L));
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            RingTimer.afterBoot(c);
        } else if (AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) {
            RingTimer.upgrade(c);
        }
    }
}
