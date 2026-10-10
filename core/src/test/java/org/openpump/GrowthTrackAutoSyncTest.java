package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Fictional completion events, encrypted-store seam and mocked HTTP only. */
class GrowthTrackAutoSyncTest {
    private final GrowthTrackIntegrationTest.Clock clock = new GrowthTrackIntegrationTest.Clock();
    private static AsRun run() {
        AsRun run = new AsRun(); run.rows.add(GrowthTrackIntegrationTest.row(AsRun.PLAN, 0, 120_000, 0)); return run;
    }
    private static boolean complete(GrowthTrackIntegrationTest.Fixture f, String id) throws Exception {
        return f.client.recordCompleted(GrowthTrackIntegrationTest.session(id), run(), new Model(), f.client.snapshot().connectionId);
    }
    @Test void connectedCompletionQueuesThenSyncsWithoutReviewOrSelection() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false);
        f.http.acceptAll = true;
        assertTrue(complete(f, "one")); assertEquals(1, f.client.snapshot().queued);
        f.client.syncPending(); assertEquals(0, f.client.snapshot().queued);
        assertEquals(1, f.http.requests.size()); assertTrue(f.client.receipt("op_one").startsWith("Received"));
    }
    @Test void everyDistinctCompletionAppendsEvenWithOtherSessionsPending() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false);
        assertTrue(complete(f, "one")); assertTrue(complete(f, "two")); assertEquals(2, f.client.snapshot().queued);
        f.http.acceptAll = true; f.client.syncPending();
        assertEquals(2, new JSONObject(f.http.requests.get(0).body).getJSONArray("sessions").length());
    }
    @Test void duplicateCompletionBeforeAndAfterReceiptNeverCreatesAnotherTransfer() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false); f.http.acceptAll = true;
        assertTrue(complete(f, "one")); assertFalse(complete(f, "one")); assertEquals(1, f.client.snapshot().queued);
        f.client.syncPending(); assertFalse(complete(f, "one")); f.client.syncPending(); assertEquals(1, f.http.requests.size());
    }
    @Test void disconnectedCompletionAndPreconnectionHistoryNeverQueue() throws Exception {
        GrowthTrackIntegrationTest.Storage store = new GrowthTrackIntegrationTest.Storage();
        GrowthTrackIntegrationTest.Http http = new GrowthTrackIntegrationTest.Http();
        GrowthTrackClient client = new GrowthTrackClient(store, http, clock);
        assertFalse(client.recordCompleted(GrowthTrackIntegrationTest.session("before"), run(), new Model(), ""));
        assertEquals(-1, client.automaticRetryAt()); client.syncPending(); assertTrue(http.requests.isEmpty());
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false);
        assertFalse(f.client.recordCompleted(GrowthTrackIntegrationTest.session("before"), run(), new Model(), ""));
        assertEquals(0, f.client.snapshot().queued);
    }
    @Test void offlineQueueRestartsAndRetriesAfterDurableBackoff() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false); complete(f, "one");
        f.http.responses.add(new IOException("fictional offline")); assertThrows(IOException.class, f.client::syncPending);
        long retryAt = f.client.automaticRetryAt(); assertTrue(retryAt > f.clock.now());
        GrowthTrackClient restarted = new GrowthTrackClient(f.storage, f.http, f.clock);
        assertEquals(retryAt, restarted.automaticRetryAt()); restarted.syncPending(); assertEquals(1, f.http.requests.size());
        f.clock.time = retryAt; f.http.acceptAll = true; restarted.syncPending();
        assertEquals(0, restarted.snapshot().queued); assertEquals(2, f.http.requests.size());
        assertEquals("op_one", new JSONObject(f.http.requests.get(1).body).getJSONArray("sessions").getJSONObject(0).getString("external_session_id"));
    }
    @Test void filingMarkerRecoversCompletionLostBeforeEncryptedQueueSave() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false);
        Model.Sess saved = GrowthTrackIntegrationTest.session("crash"); saved.growthTrackConnectionId = f.client.snapshot().connectionId;
        saved = Model.Sess.fromJson(saved.toJson());
        GrowthTrackClient restarted = new GrowthTrackClient(f.storage, f.http, f.clock);
        assertTrue(restarted.recordCompleted(saved, run(), new Model(), saved.growthTrackConnectionId));
        f.http.acceptAll = true; restarted.syncPending(); assertTrue(restarted.receipt("op_crash").startsWith("Received"));
    }
    @Test void relinkDiscardsQueueAndNeverClaimsOldOrDisconnectedCompletions() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false); String old = f.client.snapshot().connectionId;
        complete(f, "old"); f.client.begin(false); assertEquals(0, f.client.snapshot().queued);
        f.http.responses.add(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2, false));
        String state = f.storage.saved.getJSONObject("pending").getString("state");
        f.client.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE + "&state=" + state);
        assertNotEquals(old, f.client.snapshot().connectionId);
        assertFalse(f.client.recordCompleted(GrowthTrackIntegrationTest.session("old"), run(), new Model(), old));
        assertFalse(f.client.recordCompleted(GrowthTrackIntegrationTest.session("disconnected"), run(), new Model(), ""));
        assertEquals(0, f.client.snapshot().queued); assertTrue(complete(f, "new"));
    }
    @Test void disconnectDuringTransferStopsRemainingBatchesAndRecovery() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false);
        for (int i = 0; i < 101; i++) complete(f, "record" + i);
        String old = f.client.snapshot().connectionId; f.http.acceptAll = true;
        f.http.beforeReturn = () -> { try { f.client.disconnect(); } catch (IOException e) { throw new AssertionError(e); } };
        assertThrows(IOException.class, f.client::syncPending); assertEquals(1, f.http.requests.size());
        assertFalse(f.client.snapshot().connected); assertEquals(0, f.client.snapshot().queued);
        assertFalse(f.client.recordCompleted(GrowthTrackIntegrationTest.session("late"), run(), new Model(), old));
    }
    @Test void simulatedInvalidAndUnsupportedRecordsHaveDurableFailureStatuses() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false); String grant = f.client.snapshot().connectionId;
        Model.Sess sim = GrowthTrackIntegrationTest.session("sim"); sim.sim = true;
        assertFalse(f.client.recordCompleted(sim, run(), new Model(), grant));
        Model.Sess invalid = GrowthTrackIntegrationTest.session("invalid"); invalid.recordedEndMs = 0;
        assertFalse(f.client.recordCompleted(invalid, run(), new Model(), grant));
        assertFalse(f.client.recordCompleted(GrowthTrackIntegrationTest.session("unsupported"), null, new Model(), grant));
        assertEquals(0, f.client.snapshot().queued); assertTrue(f.http.requests.isEmpty());
        GrowthTrackClient restarted = new GrowthTrackClient(f.storage, f.http, f.clock);
        for (String id : new String[]{"sim", "invalid", "unsupported"}) assertTrue(restarted.receipt("op_" + id).startsWith("Not synced:"));
    }
    @Test void partialFailureRetainsOnlyTransientRecordAndRetriesAutomatically() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false);
        complete(f, "sent"); complete(f, "later"); complete(f, "bad");
        JSONArray rejected = new JSONArray().put(new JSONObject().put("external_session_id", "op_later").put("reason", "Insert failed"))
            .put(new JSONObject().put("external_session_id", "op_bad").put("reason", "Invalid fixture field"));
        f.http.responses.add(GrowthTrackIntegrationTest.ok(1, 0, rejected)); f.client.syncPending();
        assertEquals(1, f.client.snapshot().queued); assertTrue(f.client.receipt("op_bad").startsWith("Needs review"));
        assertFalse(complete(f, "bad")); f.clock.time = f.client.automaticRetryAt(); f.http.acceptAll = true; f.client.syncPending();
        assertEquals("op_later", new JSONObject(f.http.requests.get(1).body).getJSONArray("sessions").getJSONObject(0).getString("external_session_id"));
    }
    @Test void permissionFailurePausesRatherThanSpinningBackgroundRequests() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false); complete(f, "one");
        f.http.responses.add(new GrowthTrackClient.Response(403, "{}")); assertThrows(IOException.class, f.client::syncPending);
        assertTrue(f.client.snapshot().paused); assertEquals(-1, f.client.automaticRetryAt());
        f.client.syncPending(); assertEquals(1, f.http.requests.size()); assertEquals(1, f.client.snapshot().queued);
    }
    @Test void manualConsentFromOlderBuildMustReconnectBeforeAutoSync() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(false); f.storage.saved.remove("automatic_sync");
        GrowthTrackClient restarted = new GrowthTrackClient(f.storage, f.http, f.clock);
        assertFalse(restarted.snapshot().connected); assertEquals(-1, restarted.automaticRetryAt());
    }
}
