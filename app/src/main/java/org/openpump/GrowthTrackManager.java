package org.openpump;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One serial worker per process: rotating refresh credentials can never race. */
final class GrowthTrackManager {
    interface Work { Object run(GrowthTrackClient client) throws Exception; }
    interface Listener { void done(Object result, String error); }
    static final class SyncRequest { volatile boolean stopped; }
    private volatile SyncRequest activeSync;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final int SYNC_JOB = 72041;
    private static GrowthTrackManager instance;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Context context;
    private final GrowthTrackStore store;
    private final GrowthTrackHttp http = new GrowthTrackHttp();
    private volatile GrowthTrackClient client;
    private volatile boolean blocked;
    private volatile String connectedGrant = "";
    /** The worker's last word on finished records (GrowthTrackClient#settled), read on the
     *  main thread when the next snapshot is taken; null until the client has been read. */
    private volatile java.util.Map<String, Integer> settled;
    static synchronized GrowthTrackManager get(Context c) { if (instance == null) instance = new GrowthTrackManager(c.getApplicationContext()); return instance; }
    private GrowthTrackManager(Context c) { context = c; store = new GrowthTrackStore(c); }
    private GrowthTrackClient client() throws IOException {
        if (client == null) client = new GrowthTrackClient(store, http, new GrowthTrackClient.Clock() { @Override public long now() { return System.currentTimeMillis(); } });
        return client;
    }
    /** Main-thread observation only; this non-secret connection id is frozen in the filed
     *  record (GrowthTrackOutbox#markerFor). A connection whose credential GrowthTrack refused
     *  still marks: those sessions go once the person reconnects. */
    String connectionId() { return blocked ? "" : connectedGrant; }
    private void cacheConnection(GrowthTrackClient ready) {
        GrowthTrackClient.Snapshot state = ready.snapshot();
        connectedGrant = !blocked && (state.connected || state.needsReconnect) ? state.connectionId : "";
        settled = ready.settled();
    }
    void run(final Work work, final Listener listener) { dispatch(work, listener, null); }
    private void dispatch(final Work work, final Listener listener, final SyncRequest request) {
        final long ticket = http.ticket();
        worker.execute(new Runnable() { @Override public void run() {
            Object result = null; String error = null; GrowthTrackClient ready = null;
            try {
                if (request != null && request.stopped) throw new IOException("Background sync was stopped");
                activeSync = request;
                if (blocked) throw new IOException("Connection cleanup is in progress");
                http.enter(ticket); ready = client(); http.enter(ticket); cacheConnection(ready);
                if (request != null && request.stopped) throw new IOException("Background sync was stopped");
                http.useNetwork(null); result = work.run(ready);
            }
            catch (GrowthTrackClient.Problem e) { error = e.getMessage(); }
            catch (IOException e) { error = e.getMessage(); }
            catch (Exception e) { error = "Could not complete the GrowthTrack operation. No credential details are logged."; }
            http.useNetwork(null); activeSync = null;
            if (ready != null) cacheConnection(ready);
            // Finish a running JobService before scheduling its next one-shot job.
            if (listener != null) listener.done(result, error);
            if (request != null && !request.stopped && ready != null && !blocked) {
                try { http.enter(ticket); schedule(ready); } catch (IOException ignored) { }
            }
        } });
    }
    /**
     * MAIN THREAD. One sync pass. The worker never reads Store (bug 8: Store's statics are
     * single-threaded); the pass works from a snapshot of the Model and its recordings taken
     * here (GrowthTrackOutbox#take). Before the client has been read once there is no
     * connection id to take it for, so the first pass reads the client and comes back here.
     */
    SyncRequest startSync(final Listener listener) { return startSync(Store.load(context, false), listener, null); }
    SyncRequest startSync(Model model, final Listener listener) { return startSync(model, listener, null); }
    SyncRequest startSync(final Listener listener, final android.net.Network network) { return startSync(Store.load(context, false), listener, network); }
    SyncRequest startSync(final Model model, final Listener listener, final android.net.Network network) {
        final SyncRequest request = new SyncRequest();
        if (settled == null) {
            dispatch(new Work() { @Override public Object run(GrowthTrackClient ready) { return null; } }, new Listener() {
                @Override public void done(Object result, final String error) {
                    main.post(new Runnable() { @Override public void run() {
                        if (request.stopped) { if (listener != null) listener.done(null, "Background sync was stopped"); return; }
                        if (settled == null) { if (listener != null) listener.done(null, error); return; }
                        sync(model, listener, network, request);
                    } });
                }
            }, null);
            return request;
        }
        sync(model, listener, network, request);
        return request;
    }
    private void sync(Model model, Listener listener, final android.net.Network network, SyncRequest request) {
        final GrowthTrackOutbox box = GrowthTrackOutbox.take(model, connectionId(), new GrowthTrackOutbox.AsRunLookup() {
            @Override public AsRun asRun(Model.Sess s) { return Store.loadAsRun(context, s.ts); }
        }, settled);
        dispatch(new Work() { @Override public Object run(GrowthTrackClient ready) throws Exception {
            GrowthTrackClient.Snapshot state = ready.snapshot();
            if (!state.connected && !state.needsReconnect) return null;
            http.useNetwork(network); ready.prepareAutomaticSync();
            ready.sync(box); return null;
        } }, listener, request);
    }
    /** History > Delete: a waiting session is withdrawn before it can be sent (bug 6). The
     *  listener gets GrowthTrackClient#withdraw's answer (an Integer). */
    void withdraw(final String sessionId, Listener listener) {
        dispatch(new Work() { @Override public Object run(GrowthTrackClient ready) throws Exception {
            return ready.withdraw(sessionId);
        } }, listener, null);
    }
    private JobScheduler jobs() { return (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE); }
    private void schedule(final GrowthTrackClient ready) {
        final long due = ready.automaticRetryAt(); final long ticket = http.ticket();
        // jobFinished posts its completion on the main looper. Schedule after that
        // message so replacing this job ID cannot stop the job that just finished.
        main.post(new Runnable() { @Override public void run() {
            if (blocked) return;
            try { http.enter(ticket); } catch (IOException ignored) { return; }
            JobScheduler scheduler = jobs();
            if (due < 0) { if (scheduler != null) scheduler.cancel(SYNC_JOB); return; }
            int result = JobScheduler.RESULT_FAILURE;
            try {
                if (scheduler != null) result = scheduler.schedule(new JobInfo.Builder(SYNC_JOB, new ComponentName(context, GrowthTrackSyncService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(Math.max(1000L, due - System.currentTimeMillis()))
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true).build());
            } catch (RuntimeException ignored) { /* A scheduler refusal remains visible in sync status. */ }
            if (result != JobScheduler.RESULT_SUCCESS) worker.execute(new Runnable() { @Override public void run() {
                if (!blocked && client == ready) try { ready.backgroundUnavailable(); } catch (IOException ignored) { }
            } });
        } });
    }
    void cancelSync(SyncRequest request) {
        if (request == null) return; request.stopped = true;
        // A stopped queued job must not cancel an unrelated OAuth/routine operation.
        if (activeSync == request) cancel();
    }
    void cancel() { GrowthTrackClient c = client; if (c != null) c.cancelRequests(); http.cancel(); }
    void reset(final Listener listener) {
        blocked = true; connectedGrant = ""; cancel(); JobScheduler scheduler = jobs(); if (scheduler != null) scheduler.cancel(SYNC_JOB);
        worker.execute(new Runnable() { @Override public void run() {
            String error = null;
            try { store.erase(); client = null; blocked = false; }
            catch (IOException e) { error = e.getMessage(); } // Keep requests blocked until reset succeeds.
            if (listener != null) listener.done(null, error);
        } });
    }
    static synchronized void eraseWithAppData(Context c) { get(c).reset(null); }
}
