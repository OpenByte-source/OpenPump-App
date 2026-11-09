package org.openpump;

import static org.junit.jupiter.api.Assertions.*;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * THE WAY BACK FROM A GROWTHTRACK SIGN-IN (the owner's pick A, 9 Nov 2026): where the screen
 * goes back to, the one-time landing page after the browser, what happens now, "Try again",
 * and setup's row. Fictional data and the fake transport only; nothing reaches GrowthTrack.
 */
class GrowthTrackReturnTest {

    private static GrowthTrackClient.Snapshot snap(boolean connected, boolean needsReconnect, boolean paused,
                                                   String account, String earlier) {
        return new GrowthTrackClient.Snapshot(connected, false, paused, needsReconnect, 0, 0, 0,
            "", connected || needsReconnect ? "conn1" : "", account, earlier, false);
    }

    /* ------------------------------------------------------------------ the origin */

    /** The opening names where it came from; the sign-in's return names none and keeps it. */
    @Test void theOriginIsKeptAcrossTheBrowserAndAProcessDeath() {
        int setup = GrowthTrackReturn.FROM_SETUP, settings = GrowthTrackReturn.FROM_SETTINGS, none = GrowthTrackReturn.FROM_NONE;
        // A fresh opening names its own, whatever was kept or stored before.
        assertEquals(setup, GrowthTrackReturn.origin(setup, settings, settings));
        assertEquals(settings, GrowthTrackReturn.origin(settings, setup, setup));
        // The sign-in's return to the screen still alive: the screen's own.
        assertEquals(setup, GrowthTrackReturn.origin(none, setup, settings));
        // ...to a new screen after the process went while the browser was up: the stored one.
        assertEquals(settings, GrowthTrackReturn.origin(none, none, settings));
        assertEquals(none, GrowthTrackReturn.origin(none, none, none));
        assertEquals(none, GrowthTrackReturn.origin(none, none, 7), "a stored value that is no origin is none");

        for (int f : new int[]{ none, setup, settings })
            assertEquals(f, GrowthTrackReturn.fromKey(GrowthTrackReturn.key(f)));
        assertEquals(none, GrowthTrackReturn.fromKey(null));
        assertEquals(none, GrowthTrackReturn.fromKey("today"));
    }

    /** The way-back button names where it goes. */
    @Test void theWayBackNamesWhereItGoes() {
        assertEquals("Back to setup", GrowthTrackReturn.backLabel(GrowthTrackReturn.FROM_SETUP));
        assertEquals("Back to Settings", GrowthTrackReturn.backLabel(GrowthTrackReturn.FROM_SETTINGS));
        assertEquals("Done", GrowthTrackReturn.backLabel(GrowthTrackReturn.FROM_NONE));
    }

    /* ------------------------------------------------------------------ the landing */

    /** After a sign-in this phone started: "You’re connected" only when the approval was
     *  stored; why not otherwise; nothing special when the outcome is not known. */
    @Test void theReturnLandsOnConnectedOnlyWhenTheExchangeSucceeded() {
        assertEquals(GrowthTrackPages.CONNECTED, GrowthTrackReturn.landing(GrowthTrackReturn.Outcome.connected()));
        assertEquals(GrowthTrackPages.NOT_CONNECTED, GrowthTrackReturn.landing(GrowthTrackReturn.Outcome.cancelled("x")));
        assertEquals(GrowthTrackPages.NOT_CONNECTED, GrowthTrackReturn.landing(GrowthTrackReturn.Outcome.refused("x")));
        assertEquals(0, GrowthTrackReturn.landing(null));

        assertEquals(GrowthTrackReturn.Outcome.CONNECTED, GrowthTrackReturn.Outcome.of(GrowthTrackClient.APPROVED, "Connected to …").kind);
        GrowthTrackReturn.Outcome declined = GrowthTrackReturn.Outcome.of(GrowthTrackClient.DECLINED,
            "GrowthTrack approval was declined. No sessions were sent.");
        assertEquals(GrowthTrackReturn.Outcome.CANCELLED, declined.kind);
        assertEquals("GrowthTrack approval was declined. No sessions were sent.", declined.note);
        assertEquals(GrowthTrackReturn.Outcome.REFUSED, GrowthTrackReturn.Outcome.of(GrowthTrackClient.NOT_APPROVED, "y").kind);
    }

    /** The landing pages stay what they are while the connection is read again, and Back on
     *  one (or on the screen opened from one) leaves. */
    @Test void aLandingPageStaysUntilThePersonLeavesIt() {
        GrowthTrackClient.Snapshot none = snap(false, false, false, "", "");
        GrowthTrackClient.Snapshot linked = snap(true, false, false, "", "");
        for (int land : new int[]{ GrowthTrackPages.CONNECTED, GrowthTrackPages.NOT_CONNECTED }) {
            assertEquals(land, GrowthTrackPages.pageFor(land, linked, false));
            assertEquals(land, GrowthTrackPages.pageFor(land, none, false));
            assertEquals(land, GrowthTrackPages.fromKey(GrowthTrackPages.key(land)), "kept across a recreate");
            assertEquals(GrowthTrackPages.FINISH, GrowthTrackPages.back(land, land, false));
            // "See connection details", then Back: the way back too, not a landing page again.
            assertEquals(GrowthTrackPages.FINISH, GrowthTrackPages.back(GrowthTrackPages.HOME, land, false));
        }
        // "Try again" from a change of account that did not go through: back to the screen.
        assertEquals(GrowthTrackPages.HOME, GrowthTrackPages.back(GrowthTrackPages.CONNECT, GrowthTrackPages.NOT_CONNECTED, true));
        assertEquals(GrowthTrackPages.FINISH, GrowthTrackPages.back(GrowthTrackPages.CONNECT, GrowthTrackPages.NOT_CONNECTED, false));
    }

    /** A sign-in that did not connect says so in the refusal's own plain words. */
    @Test void aFailedOrCancelledSignInSaysWhyInPlainWords() {
        GrowthTrackReturn.Outcome declined = GrowthTrackReturn.Outcome.cancelled("GrowthTrack approval was declined. No sessions were sent.");
        assertEquals("Sign-in cancelled", GrowthTrackReturn.failedTitle(declined));
        assertEquals("GrowthTrack approval was declined. No sessions were sent.", GrowthTrackReturn.failedWords(declined));
        GrowthTrackReturn.Outcome expired = GrowthTrackReturn.Outcome.refused("Connection request expired. Connect again");
        assertEquals("Not connected", GrowthTrackReturn.failedTitle(expired));
        assertEquals("Connection request expired. Connect again", GrowthTrackReturn.failedWords(expired));
        assertEquals("The sign-in did not finish. No sessions were sent.", GrowthTrackReturn.failedWords(GrowthTrackReturn.Outcome.refused(null)));
        assertEquals("The sign-in did not finish. No sessions were sent.", GrowthTrackReturn.failedWords(null));
    }

    /** "Try again" goes where the connection now needs it to. */
    @Test void tryAgainGoesWhereTheConnectionNeedsIt() {
        assertEquals(GrowthTrackReturn.TRY_CONNECT, GrowthTrackReturn.tryAgain(null));
        assertEquals(GrowthTrackReturn.TRY_CONNECT, GrowthTrackReturn.tryAgain(snap(false, false, false, "", "")));
        assertEquals(GrowthTrackReturn.TRY_SIGN_IN_AGAIN, GrowthTrackReturn.tryAgain(snap(false, true, false, "", "")));
        assertEquals(GrowthTrackReturn.TRY_SIGN_IN_AGAIN, GrowthTrackReturn.tryAgain(snap(true, false, true, "", "")));
        assertEquals(GrowthTrackReturn.TRY_CHANGE_ACCOUNT, GrowthTrackReturn.tryAgain(snap(true, false, false, "", "")));
    }

    /* ------------------------------------------------------------------ what happens now */

    @Test void whatHappensNowSaysWhichSessionsGoFromTheSwitches() {
        GrowthTrackSettings s = new GrowthTrackSettings();
        s.lengthPlan = true; s.manualRuns = false;
        assertEquals("Your finished girth plan and own-routine sessions go to this account.",
            GrowthTrackReturn.whatGoes(s, true, false, ""), "a plan the person does not train is not named");
        assertEquals("Your finished girth plan, length plan and own-routine sessions go to this account.",
            GrowthTrackReturn.whatGoes(s, true, true, GrowthTrackClient.EARLIER_NEW));
        s.manualRuns = true; s.stoppedEarly = true;
        assertEquals("Your finished own-routine and manual sessions go to this account. Sessions stopped early go too. "
            + "So do the earlier ones on this phone that can be sent.",
            GrowthTrackReturn.whatGoes(s, false, false, GrowthTrackClient.EARLIER_ALSO));
        s.ownRoutines = false; s.manualRuns = false;
        assertEquals("No kind of session is switched on, so nothing goes to this account yet.",
            GrowthTrackReturn.whatGoes(s, false, false, ""));
        s.on = false;
        assertEquals("Sending is switched off, so nothing goes to this account until you turn it on.",
            GrowthTrackReturn.whatGoes(s, true, true, ""));
        assertEquals("Change this any time in Settings › Connected apps.", GrowthTrackReturn.CHANGE_IT);
        assertTrue(GrowthTrackReturn.whatGoes(null, true, false, "").startsWith("Your finished girth plan, own-routine and manual"));
    }

    @Test void theConnectedPageShowsTheAccountOnlyWhenGrowthTrackGaveOne() {
        assertEquals("sam@example.com", GrowthTrackReturn.account(snap(true, false, false, " sam@example.com ", "")));
        assertNull(GrowthTrackReturn.account(snap(true, false, false, "", "")));
        assertNull(GrowthTrackReturn.account(null));
    }

    /* ------------------------------------------------------------------ setup's row */

    @Test void setupsRowShowsTheConnectionOrWhyNot() {
        GrowthTrackReturn.Row row = GrowthTrackReturn.setupRow(snap(true, false, false, "sam@example.com", ""), null);
        assertTrue(row.connected);
        assertEquals("✓ Connected · sam@example.com", row.text);
        assertEquals("✓ Connected", GrowthTrackReturn.setupRow(snap(true, false, false, "", ""), null).text);
        // Connected wins over an older refusal (a second try that went through).
        assertEquals("✓ Connected", GrowthTrackReturn.setupRow(snap(true, false, false, "", ""),
            GrowthTrackReturn.Outcome.cancelled("x")).text);

        GrowthTrackClient.Snapshot none = snap(false, false, false, "", "");
        assertEquals("Not connected · sign-in was cancelled", GrowthTrackReturn.setupRow(none,
            GrowthTrackReturn.Outcome.cancelled("GrowthTrack approval was declined. No sessions were sent.")).text);
        assertEquals("Not connected · sign-in was cancelled", GrowthTrackReturn.setupRow(none,
            GrowthTrackReturn.Outcome.cancelled("Connection cancelled. No sessions were sent.")).text);
        assertEquals("Not connected · the sign-in request expired", GrowthTrackReturn.setupRow(none,
            GrowthTrackReturn.Outcome.refused("Connection request expired. Connect again")).text);
        assertEquals("Not connected · could not complete the connection", GrowthTrackReturn.setupRow(none,
            GrowthTrackReturn.Outcome.refused("Could not complete the connection. Connect again; the authorization code will not be replayed.")).text);
        assertEquals("Not connected · GrowthTrack has not enabled this OpenPump client", GrowthTrackReturn.setupRow(none,
            GrowthTrackReturn.Outcome.refused("GrowthTrack has not enabled this OpenPump client. Contact the integration maintainer.")).text);
        assertEquals("Not connected · no browser is available on this phone", GrowthTrackReturn.setupRow(none,
            GrowthTrackReturn.Outcome.refused("No browser is available on this phone. Turn one on to sign in.")).text);
        assertFalse(GrowthTrackReturn.setupRow(none, GrowthTrackReturn.Outcome.refused("x")).connected);

        // Before any sign-in, and after one that connected but has since gone: the invitation.
        assertEquals(GrowthTrackReturn.SETUP_INVITE, GrowthTrackReturn.setupRow(null, null).text);
        assertEquals(GrowthTrackReturn.SETUP_INVITE, GrowthTrackReturn.setupRow(none, GrowthTrackReturn.Outcome.connected()).text);
        assertEquals("Keep your sessions in your own account too", GrowthTrackReturn.SETUP_INVITE);
    }

    /* ------------------------------------------------------------------ the client's answer */

    /** The client says what a return came to: stored, declined in the browser, or another
     *  GrowthTrack error - the landing page is decided from this, never from words. */
    @Test void theClientSaysWhatTheReturnCameTo() throws Exception {
        GrowthTrackIntegrationTest.Storage storage = new GrowthTrackIntegrationTest.Storage();
        GrowthTrackIntegrationTest.Http http = new GrowthTrackIntegrationTest.Http();
        GrowthTrackClient c = new GrowthTrackClient(storage, http, new GrowthTrackIntegrationTest.Clock());

        c.begin(GrowthTrackClient.EARLIER_NEW);
        assertEquals(GrowthTrackClient.DECLINED, c.callback(GrowthTrackProtocol.REDIRECT + "?error=access_denied&state=" + state(storage)));
        assertFalse(c.snapshot().connected);
        assertEquals(GrowthTrackReturn.Outcome.CANCELLED, GrowthTrackReturn.Outcome.of(GrowthTrackClient.DECLINED, c.snapshot().message).kind);

        c.begin(GrowthTrackClient.EARLIER_NEW);
        assertEquals(GrowthTrackClient.NOT_APPROVED, c.callback(GrowthTrackProtocol.REDIRECT + "?error=server_error&state=" + state(storage)));
        assertFalse(c.snapshot().connected);

        c.begin(GrowthTrackClient.EARLIER_NEW);
        http.responses.add(new GrowthTrackClient.Response(200, new JSONObject(GrowthTrackIntegrationTest.token(GrowthTrackIntegrationTest.R).body)
            .put("account", new JSONObject().put("id", "acct-1").put("email", "sam@example.com")).toString()));
        assertEquals(GrowthTrackClient.APPROVED, c.callback(GrowthTrackProtocol.REDIRECT + "?code=" + GrowthTrackIntegrationTest.CODE
            + "&state=" + state(storage)));
        assertTrue(c.snapshot().connected);
        assertEquals("✓ Connected · sam@example.com", GrowthTrackReturn.setupRow(c.snapshot(), GrowthTrackReturn.Outcome.connected()).text);
    }

    private static String state(GrowthTrackIntegrationTest.Storage storage) throws Exception {
        return storage.saved.getJSONObject("pending").getString("state");
    }
}
