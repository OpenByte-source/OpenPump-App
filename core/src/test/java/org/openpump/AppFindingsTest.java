package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/** APP.md proofs. Each test states the behaviour the app should have; each fails on pr1. */
class AppFindingsTest {
    private static AsRun run() {
        AsRun run = new AsRun(); run.rows.add(GrowthTrackIntegrationTest.row(AsRun.PLAN, 0, 120_000, 0)); return run;
    }

    /** F1: connected an hour ago, finish a session with no network (airplane mode, basement,
     *  captive portal). The filing path's startSync runs at once, with no connectivity check.
     *  The refresh request never left the phone (DNS failed), yet the connection is wiped,
     *  the queue is dropped, and the session can never sync (its marker names the dead grant). */
    @Test void sessionFinishedOfflineAnHourAfterConnectingStaysQueuedAndConnected() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        f.clock.time += 60 * 60_000L;                       // access token (<= 900 s) long expired
        assertTrue(f.client.recordCompleted(GrowthTrackIntegrationTest.session("sess1"), run(), new Model(), grant));
        f.http.responses.add(new UnknownHostException("no network"));
        assertThrows(IOException.class, f.client::syncPending);
        assertTrue(f.client.snapshot().connected, "an offline refresh disconnected the account: " + f.client.snapshot().message);
        assertEquals(1, f.client.snapshot().queued, "the offline session was dropped from the queue");
    }

    /** F1, same root: the JobService's onStopJob (network lost mid-request) calls cancel(),
     *  which aborts the in-flight refresh - and the abort is treated like an ambiguous refresh. */
    @Test void jobStoppedDuringRefreshDoesNotDisconnect() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        f.clock.time += 60 * 60_000L;
        assertTrue(f.client.recordCompleted(GrowthTrackIntegrationTest.session("sess1"), run(), new Model(), grant));
        f.http.beforeReturn = () -> f.client.cancelRequests();   // GrowthTrackManager.cancel() from onStopJob
        f.http.responses.add(new IOException("disconnected by cancel"));
        assertThrows(IOException.class, f.client::syncPending);
        assertTrue(f.client.snapshot().connected, "onStopJob during refresh disconnected the account: " + f.client.snapshot().message);
    }

    /** F2: STOP files part 1 (uploaded at once by the filing path's startSync). Resume, finish:
     *  SessionActivity drops part 1 from the log (RunParts.dropEarlier) and files a NEW record
     *  (new id "sess"+now) whose recordedStartMs/EndMs stay 0 because parts.playedMs != 0
     *  (SessionActivity ~27963). GrowthTrack keeps the partial part 1; the whole session never syncs. */
    @Test void resumedSessionReplacesTheUploadedFragment() throws Exception {
        GrowthTrackIntegrationTest.Fixture f = new GrowthTrackIntegrationTest.Fixture();
        String grant = f.client.snapshot().connectionId;
        f.http.acceptAll = true;
        Model.Sess part1 = GrowthTrackIntegrationTest.session("sess1700000000000");
        part1.completed = false;                                  // STOP
        assertTrue(f.client.recordCompleted(part1, run(), new Model(), grant));
        f.client.syncPending();
        assertTrue(f.client.receipt("op_sess1700000000000").startsWith("Received"));
        // What fileSession builds after Resume: folded time, new id, no wall-clock bounds.
        Model.Sess whole = GrowthTrackIntegrationTest.session("sess1700000400000");
        whole.durSec = 240; whole.recordedStartMs = 0; whole.recordedEndMs = 0;
        whole.growthTrackConnectionId = grant;
        boolean queued = f.client.recordCompleted(whole, run(), new Model(), grant);
        assertTrue(queued, "the completed session that replaced the uploaded fragment never syncs: "
            + f.client.receipt("op_sess1700000400000"));
    }
}
