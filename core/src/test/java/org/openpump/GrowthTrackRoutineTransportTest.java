package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Fictional payloads and mock transport only; downloads never start a pump. */
class GrowthTrackRoutineTransportTest {
    static final String ID = "11111111-1111-4111-8111-111111111111";
    private void upgrade(GrowthTrackIntegrationTest.Fixture f) throws Exception {
        String conn = f.client.snapshot().connectionId;
        String url = f.client.beginWithRoutines();
        assertTrue(url.contains("routines%3Aread"));
        GrowthTrackProtocol.Pending.from(f.storage.saved.getJSONObject("pending"));
        JSONObject token = new JSONObject(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2).body)
            .put("scope", GrowthTrackProtocol.WITH_ROUTINES);
        f.http.responses.add(new GrowthTrackClient.Response(200, token.toString()));
        f.client.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE
            + "&state=" + f.storage.saved.getJSONObject("pending").getString("state"));
        assertEquals(conn, f.client.snapshot().connectionId);
        f.http.requests.clear();
    }
    private JSONObject envelope() throws Exception {
        return new JSONObject().put("format", "gt-routine").put("schema_version", 1);
    }
    @Test void routineConsentCannotBypassInitialSessionConnectionDisclosure() throws Exception {
        GrowthTrackIntegrationTest.Storage store = new GrowthTrackIntegrationTest.Storage();
        GrowthTrackIntegrationTest.Http http = new GrowthTrackIntegrationTest.Http();
        GrowthTrackClient c = new GrowthTrackClient(store, http, new GrowthTrackIntegrationTest.Clock());
        assertThrows(IOException.class, c::beginWithRoutines);
        assertFalse(c.snapshot().pending); assertTrue(http.requests.isEmpty());
    }
    @Test void readingNeedsAdditionalConsentAndCancelPreservesConnection() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        assertFalse(f.client.canReadRoutines());
        assertThrows(IOException.class, f.client::listExecutableRoutines);
        assertTrue(f.http.requests.isEmpty());
        String conn = f.client.snapshot().connectionId;
        f.client.beginWithRoutines(); f.client.cancelAuthorization();
        assertEquals(conn, f.client.snapshot().connectionId); assertTrue(f.client.snapshot().connected);
        assertFalse(f.client.canReadRoutines());
        assertEquals(GrowthTrackProtocol.WRITE, GrowthTrackProtocol.pending(1).scope);
    }
    @Test void listFiltersLegacyAndDownloadUsesExactNegotiatedPath() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); upgrade(f);
        JSONArray rows = new JSONArray().put(new JSONObject().put("id", "legacy"))
            .put(new JSONObject().put("id", ID).put("name", "Fictional test")
            .put("execution_contract", GrowthTrackProtocol.EXECUTION_CONTRACT));
        f.http.responses.add(new GrowthTrackClient.Response(200, envelope().put("routines", rows).toString()));
        assertEquals(1, f.client.listExecutableRoutines().length());
        assertEquals(GrowthTrackProtocol.ROUTINES_PATH, f.http.requests.get(0).path);
        JSONObject exported = envelope().put("execution_contract", GrowthTrackProtocol.EXECUTION_CONTRACT)
            .put("routine", new JSONObject().put("id", ID));
        f.http.responses.add(new GrowthTrackClient.Response(200, exported.toString()));
        assertEquals(ID, f.client.downloadExecutableRoutine(ID).getJSONObject("routine").getString("id"));
        assertEquals(GrowthTrackProtocol.ROUTINES_PATH + "&id=" + ID, f.http.requests.get(1).path);
        assertNull(f.http.requests.get(1).body);
        assertThrows(IOException.class, () -> f.client.downloadExecutableRoutine(ID + "&evil=1"));
        assertEquals(2, f.http.requests.size());
    }
    @Test void refusedRoutineScopeDoesNotPauseSessionUploads() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); upgrade(f);
        f.http.responses.add(new GrowthTrackClient.Response(403, "{}"));
        assertThrows(IOException.class, f.client::listExecutableRoutines);
        assertFalse(f.client.snapshot().paused); assertTrue(f.client.snapshot().connected);
    }
    @Test void mismatchedDownloadedIdentityAndSchemaAreRejected() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); upgrade(f);
        f.http.responses.add(new GrowthTrackClient.Response(200, envelope()
            .put("execution_contract", GrowthTrackProtocol.EXECUTION_CONTRACT)
            .put("routine", new JSONObject().put("id", "different")).toString()));
        assertThrows(IOException.class, () -> f.client.downloadExecutableRoutine(ID));
        f.http.responses.add(new GrowthTrackClient.Response(200, envelope().put("schema_version", 2).toString()));
        assertThrows(IOException.class, f.client::listExecutableRoutines);
    }
    @Test void refreshedTokenLosingReadScopeDoesNotDownloadOrPauseUploads() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); upgrade(f);
        f.clock.time += 900_000L;
        JSONObject token = new JSONObject(GrowthTrackIntegrationTest.token("gtr_" + "t".repeat(43)).body);
        f.http.responses.add(new GrowthTrackClient.Response(200, token.toString()));
        assertThrows(IOException.class, f.client::listExecutableRoutines);
        assertEquals(1, f.http.requests.size()); assertEquals("/oauth-token", f.http.requests.get(0).path);
        assertFalse(f.client.canReadRoutines()); assertFalse(f.client.snapshot().paused);
    }
    @Test void unrequestedReadPermissionIsRejectedWithoutReplacingWorkingTokens() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String conn = f.client.snapshot().connectionId;
        f.client.begin();
        JSONObject token = new JSONObject(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R2).body)
            .put("scope", GrowthTrackProtocol.WITH_ROUTINES);
        f.http.responses.add(new GrowthTrackClient.Response(200, token.toString()));
        assertThrows(IOException.class, () -> f.client.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE
            + "&state=" + f.storage.saved.getJSONObject("pending").getString("state")));
        assertEquals(conn, f.client.snapshot().connectionId); assertTrue(f.client.snapshot().connected);
        assertFalse(f.client.canReadRoutines());
    }
    @Test void readingConsentDoesNotResumePausedSessionTransfers() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        f.approve("fictional-paused");
        f.http.responses.add(new GrowthTrackClient.Response(403, "{}"));
        assertThrows(IOException.class, f.client::syncPending);
        assertTrue(f.client.snapshot().paused);
        upgrade(f);
        assertTrue(f.client.snapshot().paused); assertTrue(f.client.canReadRoutines());
        assertEquals(1, f.client.snapshot().queued);
    }
    @Test void ordinaryReconnectRetainsPreviouslyApprovedReadingButChangeAccountDoesNot() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture(); upgrade(f);
        f.client.begin(); assertEquals(GrowthTrackProtocol.WITH_ROUTINES, f.storage.saved.getJSONObject("pending").getString("scope"));
        f.client.cancelAuthorization(); f.client.beginChangeAccount(GrowthTrackClient.EARLIER_NEW);
        assertEquals(GrowthTrackProtocol.WRITE, f.storage.saved.getJSONObject("pending").getString("scope"));
    }
}
