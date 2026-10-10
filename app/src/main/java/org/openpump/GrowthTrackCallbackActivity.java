package org.openpump;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

/** Exported callback: no history and no credential-bearing URI forwarded to another activity. */
public final class GrowthTrackCallbackActivity extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); SessionActivity.applySecureFlag(getWindow(), Store.load(this, false));
        LinearLayout body = Ui.col(this); body.setBackgroundColor(Ui.BG); body.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        Ui.head(this, body, "Connecting to GrowthTrack."); setContentView(body);
        String callback = getIntent().getDataString(); getIntent().setData(null);
        if (!Intent.ACTION_VIEW.equals(getIntent().getAction()) || callback == null) { finish(); return; }
        GrowthTrackManager.get(this).run(new Exchange(callback), new Completed());
    }
    private static final class Exchange implements GrowthTrackManager.Work {
        final String callback; Exchange(String callback) { this.callback = callback; }
        @Override public Object run(GrowthTrackClient client) throws Exception { client.callback(callback); return null; }
    }
    private final class Completed implements GrowthTrackManager.Listener {
        @Override public void done(Object result, String error) { runOnUiThread(new Delivered(error)); }
    }
    private final class Delivered implements Runnable {
        final String error; Delivered(String error) { this.error = error; }
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (GrowthTrackActivity.refuseHandOffUnderHold(GrowthTrackCallbackActivity.this)) return;
            Intent open = new Intent(GrowthTrackCallbackActivity.this, GrowthTrackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            open.putExtra("connection_message", error == null ? "GrowthTrack connection response received. No sessions were sent." : error);
            startActivity(open); finish();
        }
    }
}
