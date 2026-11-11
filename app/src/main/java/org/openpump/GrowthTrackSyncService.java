package org.openpump;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

/** Android network-constrained retry. No UI, new OAuth grant, or pump access. */
public final class GrowthTrackSyncService extends JobService {
    private JobParameters active;
    private GrowthTrackManager.SyncRequest request;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Override public boolean onStartJob(JobParameters params) {
        active = params;
        request = GrowthTrackManager.get(this).startSync(new Finished(params), android.os.Build.VERSION.SDK_INT >= 28 ? params.getNetwork() : null);
        return true;
    }
    private final class Finished implements GrowthTrackManager.Listener {
        final JobParameters params; Finished(JobParameters params) { this.params = params; }
        @Override public void done(Object result, String error) {
            // The worker needs jobFinished to precede replacement scheduling. Android's
            // JobService API accepts this callback from the worker; no view is touched.
            jobFinished(params, false);
            main.post(new Clear(params));
        }
    }
    private final class Clear implements Runnable {
        final JobParameters params; Clear(JobParameters params) { this.params = params; }
        @Override public void run() { if (active == params) active = null; }
    }
    @Override public boolean onStopJob(JobParameters params) {
        if (active == params) { active = null; GrowthTrackManager.get(this).cancelSync(request); request = null; }
        return true;
    }
}
