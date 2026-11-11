package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * "SENT AND WAITING SESSIONS" NAMES THE REAL REASON (the live GrowthTrack test, 9 Nov 2026,
 * finding 8). With the phone connected throughout, a session stopped early (its switch off) and
 * a manual run filed while "Manual runs" was off both read "Not sent: filed while not connected
 * or switched off". The record kept no reason, only an empty marker. Now the reason it was
 * filed unmarked is kept on it (Model.Sess#gtHeldWhy) and the list says which switch it was -
 * as it was when the session was filed, even after that switch is turned back on.
 */
class NotSentReasonTest {
    private static final long T0 = 1_794_241_509_381L;

    private static Model.Sess session(String id) {
        Model.Sess s = GrowthTrackIntegrationTest.session(id);
        s.ts = T0; s.recordedStartMs = T0; s.durSec = 69; s.recordedEndMs = T0 + 69_000L;
        return s;
    }

    private static Model.Sess manual(String id) throws Exception {
        return Model.Sess.fromJson(session(id).toJson().put("man", true));
    }

    private static String listed(Model.Sess s, Model m) {
        return GrowthTrackOutbox.statusOf(s, null, null, m).text;
    }

    @Test void stoppedEarlyWhileConnectedSaysTheStoppedEarlySwitch() {
        Model m = new Model();
        Model.Sess e = session("sess-e"); e.completed = false;
        GrowthTrackOutbox.file(e, "conn", m, null);
        assertEquals("", e.growthTrackConnectionId);
        assertEquals("Not sent: stopped early, and “Include sessions stopped early” was off", listed(e, m));
    }

    @Test void aManualRunFiledWhileManualRunsWasOffSaysSoEvenAfterItIsTurnedOn() throws Exception {
        Model m = new Model(); m.gtSend.manualRuns = false;
        Model.Sess g = manual("sess-g");
        GrowthTrackOutbox.file(g, "conn", m, null);
        assertEquals("Not sent: “Manual runs” was off", listed(g, m));
        m.gtSend.manualRuns = true;
        assertEquals("Not sent: “Manual runs” was off", listed(g, m),
            "turning it on later does not send it, and the line still says why");
    }

    @Test void theOtherSwitchesAndTheMasterSwitch() {
        Model m = new Model();
        m.gtSend.ownRoutines = false;
        Model.Sess own = session("own"); GrowthTrackOutbox.file(own, "conn", m, null);
        assertEquals("Not sent: “My own routines” was off", listed(own, m));
        m.gtSend.on = false;
        Model.Sess off = session("off"); GrowthTrackOutbox.file(off, "conn", m, null);
        assertEquals("Not sent: sending to GrowthTrack was switched off", listed(off, m));
    }

    @Test void filedWhileNotConnectedStillSaysSo() {
        Model m = new Model();
        Model.Sess s = session("away");
        GrowthTrackOutbox.file(s, "", m, null);
        assertEquals("Not sent: filed while not connected", listed(s, m));
    }

    @Test void aSentSessionCarriesNoReasonAndOldRecordsKeepTheirLine() throws Exception {
        Model m = new Model();
        Model.Sess ok = session("ok");
        GrowthTrackOutbox.file(ok, "conn", m, null);
        assertEquals("conn", ok.growthTrackConnectionId);
        assertEquals(0, ok.gtHeldWhy);
        assertFalse(ok.toJson().has("gtWhy"), "nothing new written for a marked session");
        Model.Sess old = Model.Sess.fromJson(new org.json.JSONObject("{\"id\":\"sess1\",\"ts\":\"1\",\"dur\":\"60\"}"));
        assertEquals(0, old.gtHeldWhy);
        assertEquals("Not sent: filed while not connected or switched off", listed(old, m),
            "a record filed before the reason was kept says what it always said");
        Model.Sess practice = session("practice"); practice.sim = true;
        GrowthTrackOutbox.file(practice, "conn", m, null);
        assertEquals("Not sent: practice run", listed(practice, m));
        Model.Sess back = Model.Sess.fromJson(session("x").toJson().put("gtWhy", GrowthTrackSettings.HELD_MANUAL));
        assertEquals(GrowthTrackSettings.HELD_MANUAL, back.gtHeldWhy);
    }
}
