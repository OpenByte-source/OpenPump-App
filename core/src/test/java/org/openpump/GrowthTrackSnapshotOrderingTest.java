package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Mock HTTP proves resumed snapshots cannot be lost behind an earlier upload receipt. */
class GrowthTrackSnapshotOrderingTest {
    private Model.Sess session(int revision, int seconds) {
        Model.Sess s = GrowthTrackIntegrationTest.session("stable-update");
        s.resumes = revision; s.durSec = seconds; s.recordedEndMs = s.recordedStartMs + seconds * 1000L;
        return s;
    }
    private AsRun run(int seconds) {
        AsRun r = new AsRun(); r.rows.add(GrowthTrackIntegrationTest.row(AsRun.PLAN, 0, seconds * 1000L, 0)); return r;
    }
    @Test void newerSnapshotWaitsForInFlightUploadThenRemainsQueuedAndStaleReplayIsRejected() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String conn = f.client.snapshot().connectionId;
        assertTrue(f.client.recordCompleted(session(0,120),run(120),new Model(),conn));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), updating = new CountDownLatch(1);
        f.http.acceptAll = true;
        f.http.beforeReturn = () -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture upload not released"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var sending = pool.submit(() -> { f.client.sendApproved(); return null; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var update = pool.submit(() -> { updating.countDown(); return f.client.recordCompleted(session(1,180),run(180),new Model(),conn); });
            assertTrue(updating.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> update.get(50, TimeUnit.MILLISECONDS), "the upload and update share one serialized lock");
            release.countDown(); sending.get(5,TimeUnit.SECONDS); assertTrue(update.get(5,TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
        assertEquals(1,f.client.snapshot().queued,"the earlier receipt must not erase the newer snapshot");
        f.http.beforeReturn = null;
        f.client.syncPending();
        assertEquals(2,f.http.requests.size());
        assertEquals(120,new JSONObject(f.http.requests.get(0).body).getJSONArray("sessions").getJSONObject(0).getInt("total_duration_seconds"));
        assertEquals(180,new JSONObject(f.http.requests.get(1).body).getJSONArray("sessions").getJSONObject(0).getInt("total_duration_seconds"));
        assertFalse(f.client.recordCompleted(session(0,120),run(120),new Model(),conn));
        assertEquals(0,f.client.snapshot().queued);
    }
    @Test void reconnectCannotReplaceNewerCarriedSnapshotWithOlderPayload() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String conn = f.client.snapshot().connectionId;
        assertTrue(f.client.recordCompleted(session(1,180),run(180),new Model(),conn));
        f.client.begin(); f.http.responses.add(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2));
        f.client.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE
            + "&state=" + f.storage.saved.getJSONObject("pending").getString("state"));
        assertFalse(f.client.recordCompleted(session(0,120),run(120),new Model(),conn));
        assertEquals(180,f.storage.saved.getJSONArray("approved").getJSONObject(0).getInt("total_duration_seconds"));
        assertEquals(1,f.storage.saved.getJSONObject("queue_meta").getJSONObject("op_stable-update").getInt("rev"));
    }
}
