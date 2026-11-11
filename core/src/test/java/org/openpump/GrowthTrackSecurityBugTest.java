package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Security bug hunt: each test states the behaviour that SHOULD hold. A failure is the bug.
 *  Fictional fixtures and mocked transport only; nothing here can reach a server. */
class GrowthTrackSecurityBugTest {
    private static AsRun run() {
        AsRun run = new AsRun(); run.rows.add(GrowthTrackIntegrationTest.row(AsRun.PLAN, 0, 120_000, 0)); return run;
    }
    private static boolean complete(GrowthTrackIntegrationTest.Fixture f, String id) throws Exception {
        return f.client.recordCompleted(GrowthTrackIntegrationTest.session(id), run(), new Model(), f.client.snapshot().connectionId);
    }

    /** S1a. A session finished in airplane mode, 16 minutes after connecting. The access
     *  token (900 s) has run out, so the first request of the sync is the refresh, which
     *  cannot leave the phone. Nothing reached the server: the link and the queue must survive. */
    @Test void s1a_offlineWithExpiredAccessTokenMustKeepConnectionAndQueue() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        f.clock.time += 16 * 60_000L;
        assertTrue(complete(f, "airplane"));
        f.http.responses.add(new IOException("fictional: no network"));
        try { f.client.syncPending(); } catch (IOException expected) { }
        assertEquals("/oauth-token", f.http.requests.get(0).path);
        assertTrue(f.client.snapshot().connected, "offline refresh attempt disconnected the account: " + f.client.snapshot().message);
        assertEquals(1, f.client.snapshot().queued, "the finished session was dropped from the queue");
    }

    /** S1b. The token endpoint answers 503 (or 429). The refresh credential was not consumed. */
    @Test void s1b_busyTokenEndpointMustKeepConnectionAndQueue() throws Exception {
        for (int status : new int[]{503, 429, 500}) {
            GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
            f.clock.time += 16 * 60_000L;
            assertTrue(complete(f, "busy"));
            f.http.responses.add(new GrowthTrackClient.Response(status, "", "30"));
            try { f.client.syncPending(); } catch (IOException expected) { }
            assertTrue(f.client.snapshot().connected, status + " from /oauth-token disconnected the account");
            assertEquals(1, f.client.snapshot().queued, status + " from /oauth-token dropped the queue");
        }
    }

    /** S1c. Android stops the background job (network lost, quota, Doze) while the refresh is
     *  in flight: GrowthTrackSyncService.onStopJob -> manager.cancel() -> cancelRequests().
     *  An ordinary scheduler event must not end the user's connection. */
    @Test void s1c_jobStoppedDuringRefreshMustKeepConnectionAndQueue() throws Exception {
        final GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        f.clock.time += 16 * 60_000L;
        assertTrue(complete(f, "stopped"));
        f.http.beforeReturn = new Runnable() { @Override public void run() { f.client.cancelRequests(); } };
        f.http.responses.add(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2));
        try { f.client.syncPending(); } catch (IOException expected) { }
        assertTrue(f.client.snapshot().connected, "a stopped job disconnected the account: " + f.client.snapshot().message);
        assertEquals(1, f.client.snapshot().queued, "a stopped job dropped the queue");
    }

    /** S1d. After the forced disconnect, the session carries the old grant marker. Reconnecting
     *  (even to the same account) makes a new grant, so the record can never be sent. */
    @Test void s1d_sessionLostToOfflineRefreshIsNeverSentAfterReconnect() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        Model.Sess filed = GrowthTrackIntegrationTest.session("lost"); filed.growthTrackConnectionId = grant;
        f.clock.time += 16 * 60_000L;
        assertTrue(f.client.recordCompleted(filed, run(), new Model(), grant));
        f.http.responses.add(new IOException("fictional: no network"));
        try { f.client.syncPending(); } catch (IOException expected) { }
        // The user reconnects in the browser, same account.
        f.client.begin();
        String state = f.storage.saved.getJSONObject("pending").getString("state");
        f.http.responses.add(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2));
        f.client.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE + "&state=" + state);
        // What GrowthTrackManager.startSync does at the next launch: only same-grant markers.
        boolean queued = f.client.snapshot().connectionId.equals(filed.growthTrackConnectionId)
            && f.client.recordCompleted(filed, run(), new Model(), filed.growthTrackConnectionId);
        assertTrue(queued, "the session recorded while connected is permanently excluded from sync");
    }

    /** S2. GrowthTrackHttp converts IOException only. An unchecked exception from the platform
     *  HTTP stack during refresh escapes refresh()'s catch: the "refreshing" marker stays
     *  saved with the tokens, and the next attempt sends the same refresh credential again
     *  (the replay the design forbids); a restart then disconnects. */
    @Test void s2_uncheckedTransportFailureDuringRefreshMustNotReplayOrStrandTheMarker() throws Exception {
        final GrowthTrackIntegrationTest.Storage storage = new GrowthTrackIntegrationTest.Storage();
        final GrowthTrackIntegrationTest.Clock clock = new GrowthTrackIntegrationTest.Clock();
        final java.util.List<String> refreshBodies = new java.util.ArrayList<String>();
        final boolean[] explode = {false};
        GrowthTrackClient.Transport http = new GrowthTrackClient.Transport() {
            @Override public GrowthTrackClient.Response request(String method, String path, Map<String, String> headers, String body) throws IOException {
                if (path.equals("/oauth-token") && body.contains("refresh_token")) {
                    refreshBodies.add(body);
                    if (explode[0]) throw new IllegalStateException("fictional platform HTTP stack failure");
                }
                if (path.equals("/oauth-token")) try { return GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R); } catch (Exception e) { throw new IOException(); }
                throw new IOException("fictional offline");
            }
        };
        GrowthTrackClient client = new GrowthTrackClient(storage, http, clock);
        client.begin();
        client.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE + "&state=" + storage.saved.getJSONObject("pending").getString("state"));
        assertTrue(client.recordCompleted(GrowthTrackIntegrationTest.session("x"), run(), new Model(), client.snapshot().connectionId));
        clock.time += 16 * 60_000L; explode[0] = true;
        assertThrows(IllegalStateException.class, client::syncPending);
        boolean strandedMarker = storage.saved.optBoolean("refreshing") && storage.saved.has("tokens");
        try { client.syncPending(); } catch (RuntimeException | IOException again) { }
        boolean replayed = refreshBodies.size() == 2 && refreshBodies.get(0).equals(refreshBodies.get(1));
        boolean survivesRestart = new GrowthTrackClient(storage, http, clock).snapshot().connected;
        assertEquals("stranded=false replayed=false survivesRestart=true",
            "stranded=" + strandedMarker + " replayed=" + replayed + " survivesRestart=" + survivesRestart);
    }

    /** S3. The OAuth state value becomes the permanent connection_id and is written into
     *  every filed session in model.json, and so into Backup & restore zips. */
    @Test void s3_oauthStateMustNotBecomeAPersistentIdentifierInModelJson() throws Exception {
        GrowthTrackIntegrationTest.Storage storage = new GrowthTrackIntegrationTest.Storage();
        GrowthTrackIntegrationTest.Http http = new GrowthTrackIntegrationTest.Http();
        GrowthTrackClient client = new GrowthTrackClient(storage, http, new GrowthTrackIntegrationTest.Clock());
        String url = client.begin();
        String state = url.replaceAll(".*[?&]state=([a-f0-9]{64}).*", "$1");
        http.responses.add(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R));
        client.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE + "&state=" + state);
        Model.Sess filed = GrowthTrackIntegrationTest.session("s"); filed.growthTrackConnectionId = client.snapshot().connectionId;
        JSONObject inModelJson = filed.toJson();
        assertFalse(inModelJson.toString().contains(state), "the browser-visible OAuth state is stored in plain model.json / backups");
    }

    /** S4. An expired request can no longer be completed, yet it stays "waiting" forever. */
    @Test void s4_expiredAuthorizationRequestMustNotStayPending() throws Exception {
        GrowthTrackIntegrationTest.Storage storage = new GrowthTrackIntegrationTest.Storage();
        GrowthTrackIntegrationTest.Http http = new GrowthTrackIntegrationTest.Http();
        GrowthTrackIntegrationTest.Clock clock = new GrowthTrackIntegrationTest.Clock();
        GrowthTrackClient client = new GrowthTrackClient(storage, http, clock);
        client.begin();
        String state = storage.saved.getJSONObject("pending").getString("state");
        clock.time += GrowthTrackProtocol.AUTH_LIFETIME_MS + 1;
        assertThrows(IOException.class, () -> client.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE + "&state=" + state));
        assertFalse(storage.saved.has("pending"), "the PKCE verifier and state of a dead request are kept indefinitely");
        assertFalse(client.snapshot().pending, "the screen keeps saying it is waiting for browser approval");
    }
}
