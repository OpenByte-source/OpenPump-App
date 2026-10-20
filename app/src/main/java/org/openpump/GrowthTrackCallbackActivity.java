package org.openpump;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * THE SIGN-IN'S WAY BACK (bugs 9 and 10). Reached through one of the identity-wearing return
 * entries (Incognito#RETURNS) - an exported, browsable link any app or page could fire at any
 * moment, so it is built to cost nothing and cover nothing:
 *  - it draws nothing (a translucent theme and no content) and parses no model on the main
 *    thread;
 *  - while a run is live or anything is unsafe it never comes up over the run screen: the code
 *    is exchanged with no screen and it finishes at once (GrowthTrackPages#onReturn);
 *  - otherwise it asks the worker - the encrypted store only, no network - whether a sign-in
 *    this phone started is waiting. Not: it finishes and nothing runs. Waiting: the exchange is
 *    queued, the GrowthTrack screen is brought up to show it, and it finishes;
 *  - it always finishes, and a slow answer never keeps it up (QUICK_MS).
 * No credential-bearing URI is forwarded to another activity: the data is taken and cleared.
 */
public final class GrowthTrackCallbackActivity extends Activity {
    /** The longest the return waits for the local "is a sign-in waiting?" answer. */
    private static final long QUICK_MS = 3000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String callback;
    private boolean answered;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent in = getIntent();
        callback = in == null ? null : in.getDataString();
        if (in != null) in.setData(null);
        if (saved != null || in == null || !Intent.ACTION_VIEW.equals(in.getAction()) || callback == null) { finish(); return; }
        GrowthTrackManager manager = GrowthTrackManager.get(this);
        if (GrowthTrackPages.onReturn(GrowthTrackActivity.doorShut(null), true) == GrowthTrackPages.RETURN_HEADLESS) {
            // Never over a live run: the approval still completes, with nothing on screen.
            manager.exchange(callback, null);
            finish();
            return;
        }
        manager.signInWaiting(new Waiting());
        main.postDelayed(new GiveUp(), QUICK_MS);
    }

    private final class Waiting implements GrowthTrackManager.Listener, Runnable {
        private boolean waiting;
        @Override public void done(Object result, String error) {
            waiting = Boolean.TRUE.equals(result);
            main.post(this);
        }
        @Override public void run() {
            if (answered) return;
            answered = true;
            String code = callback;
            callback = null;
            int step = GrowthTrackPages.onReturn(GrowthTrackActivity.doorShut(null), waiting);
            GrowthTrackManager manager = GrowthTrackManager.get(GrowthTrackCallbackActivity.this);
            if (step != GrowthTrackPages.RETURN_IGNORE) manager.exchange(code, null);
            if (step == GrowthTrackPages.RETURN_SHOW && !isFinishing()) show();
            finish();
        }
    }

    /** The GrowthTrack screen, to show the sign-in's result. The exchange was queued first:
     *  the worker is serial, so what the screen reads next is the exchange's result. */
    private void show() {
        if (GrowthTrackActivity.doorShut(null)) return;
        startActivity(new Intent(this, GrowthTrackActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(GrowthTrackActivity.EXTRA_AFTER_SIGN_IN, true));
    }

    /** The local answer is late (the worker is busy with a send): the exchange is queued
     *  behind it with no screen, and the return goes - the GrowthTrack screen shows the
     *  result the next time it is opened. */
    private final class GiveUp implements Runnable {
        @Override public void run() {
            if (answered || isFinishing()) return;
            answered = true;
            if (callback != null) GrowthTrackManager.get(GrowthTrackCallbackActivity.this).exchange(callback, null);
            callback = null;
            finish();
        }
    }
}
