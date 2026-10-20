package org.openpump;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.TimeZone;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The GrowthTrack screens' pure rules (app phase, owner decisions 1-5 and 7 and the UI bugs):
 * the words of every state, where Back goes, the earlier-sessions question, the sign-in
 * return's outcomes, when the setup offer counts as seen, and the browser step's return entry
 * under each identity.
 */
class GrowthTrackScreensTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(UTC);
        c.clear();
        c.set(y, mo - 1, d, h, mi);
        return c.getTimeInMillis();
    }

    private static GrowthTrackClient.Snapshot snap(boolean connected, boolean needsReconnect, boolean paused,
                                                   int queued, long lastSent, String message, String account, String earlier) {
        return new GrowthTrackClient.Snapshot(connected, false, paused, needsReconnect, queued, 0, lastSent,
            message, connected || needsReconnect ? "conn1" : "", account, earlier, false);
    }

    private static final long NOW = at(2026, 10, 19, 20, 0);

    @Test void whenSaysTodayYesterdayOrTheDate() {
        assertEquals("today 18:42", GrowthTrackWords.when(at(2026, 10, 19, 18, 42), NOW, UTC));
        assertEquals("yesterday 07:05", GrowthTrackWords.when(at(2026, 10, 18, 7, 5), NOW, UTC));
        assertEquals("12 Oct 18:42", GrowthTrackWords.when(at(2026, 10, 12, 18, 42), NOW, UTC));
        assertEquals("12 Oct 2025", GrowthTrackWords.when(at(2025, 10, 12, 18, 42), NOW, UTC));
    }

    /** Decision 2: one row, three states - the dot and the words change, nothing else. */
    @Test void theSettingsRowHasItsThreeStates() {
        GrowthTrackWords.Line on = GrowthTrackWords.settingsRow(
            snap(true, false, false, 0, at(2026, 10, 19, 18, 42), "", "", ""), true, NOW, UTC);
        assertEquals(GrowthTrackWords.DOT_ON, on.dot);
        assertEquals("Connected · last sent today 18:42", on.text);

        GrowthTrackWords.Line waiting = GrowthTrackWords.settingsRow(
            snap(true, false, false, 2, at(2026, 10, 19, 18, 42), "", "", ""), true, NOW, UTC);
        assertEquals(GrowthTrackWords.DOT_WAITING, waiting.dot);
        assertEquals("2 waiting · sends when online", waiting.text);

        GrowthTrackWords.Line off = GrowthTrackWords.settingsRow(null, true, NOW, UTC);
        assertEquals(GrowthTrackWords.DOT_OFF, off.dot);
        assertEquals("Not connected", off.text);
        assertEquals("Not connected", GrowthTrackWords.settingsRow(
            snap(false, false, false, 0, 0, "Not connected", "", ""), true, NOW, UTC).text);
    }

    @Test void theSettingsRowSaysWhenItNeedsASignInOrIsSwitchedOff() {
        assertEquals("Connected · nothing sent yet", GrowthTrackWords.settingsRow(
            snap(true, false, false, 0, 0, "", "", ""), true, NOW, UTC).text);
        GrowthTrackWords.Line again = GrowthTrackWords.settingsRow(snap(false, true, false, 3, 0, "", "", ""), true, NOW, UTC);
        assertEquals(GrowthTrackWords.DOT_WAITING, again.dot);
        assertEquals("3 waiting · sign in again to send", again.text);
        GrowthTrackWords.Line masterOff = GrowthTrackWords.settingsRow(snap(true, false, false, 0, 0, "", "", ""), false, NOW, UTC);
        assertEquals(GrowthTrackWords.DOT_OFF, masterOff.dot);
        assertEquals("Connected · sending is off", masterOff.text);
    }

    /** Decision 3: the card's line - last sent and nothing waiting, a waiting count, or why. */
    @Test void theStatusCardLineSaysWhatIsHappening() {
        assertEquals("Last sent today 18:42 · nothing waiting", GrowthTrackWords.statusLine(
            snap(true, false, false, 0, at(2026, 10, 19, 18, 42), "", "", ""), true, NOW, UTC).text);
        assertEquals("Nothing sent yet · nothing waiting", GrowthTrackWords.statusLine(
            snap(true, false, false, 0, 0, "", "", ""), true, NOW, UTC).text);
        assertEquals("2 waiting · sends when online", GrowthTrackWords.statusLine(
            snap(true, false, false, 2, 0, "", "", ""), true, NOW, UTC).text);
        GrowthTrackWords.Line off = GrowthTrackWords.statusLine(snap(true, false, false, 2, 0, "", "", ""), false, NOW, UTC);
        assertEquals("Sending is off · nothing is sent", off.text);
        assertEquals(GrowthTrackWords.DOT_OFF, off.dot);
        assertEquals("Sign in again to keep sending · 1 waiting", GrowthTrackWords.statusLine(
            snap(false, true, false, 1, 0, "", "", ""), true, NOW, UTC).text);
        assertEquals("Sending paused · sign in again to restore it", GrowthTrackWords.statusLine(
            snap(true, false, true, 1, 0, "", "", ""), true, NOW, UTC).text);
    }

    /** Decision 7: results and errors in plain words inside the card, and nothing that only
     *  repeats the status line. */
    @Test void theLatestResultIsInPlainWordsAndNeverRepeatsTheStatusLine() {
        assertEquals("", GrowthTrackWords.latest(null));
        assertEquals("", GrowthTrackWords.latest("Not connected"));
        assertEquals("", GrowthTrackWords.latest("2 sessions waiting"));
        assertEquals("", GrowthTrackWords.latest("1 session waiting"));
        assertEquals("", GrowthTrackWords.latest("Waiting for GrowthTrack approval in your browser"));
        assertEquals("", GrowthTrackWords.latest("Sessions are securely queued. Automatic retry is waiting for the connection or retry delay."));
        assertEquals("Last send: 3 sessions received.", GrowthTrackWords.latest("2 accepted, 1 already present, 0 rejected."));
        assertEquals("Last send: 1 session received, 1 refused. See Sent and waiting sessions.",
            GrowthTrackWords.latest("1 accepted, 0 already present, 1 rejected."));
        assertEquals("GrowthTrack approval was declined. No sessions were sent.",
            GrowthTrackWords.latest("GrowthTrack approval was declined. No sessions were sent."));
    }

    @Test void theAccountIsShownOnlyWhenTheSignInGaveOne() {
        assertNull(GrowthTrackWords.account(snap(true, false, false, 0, 0, "", "", "")));
        assertNull(GrowthTrackWords.account(snap(true, false, false, 0, 0, "", "  ", "")));
        assertEquals("sam@example.com", GrowthTrackWords.account(snap(true, false, false, 0, 0, "", "sam@example.com", "")));
    }

    @Test void theListRowSaysItsCounts() {
        assertEquals("3 sent · 1 waiting · 2 not sent", GrowthTrackWords.listFacts(3, 1, 2));
        assertEquals("1 waiting", GrowthTrackWords.listFacts(0, 1, 0));
        assertEquals("No sessions yet", GrowthTrackWords.listFacts(0, 0, 0));
        Model.Sess s = new Model.Sess();
        s.routineName = "Girth L2"; s.ts = at(2026, 10, 12, 7, 30);
        assertEquals("Girth L2 · 12 Oct 07:30", GrowthTrackWords.sessionTitle(s, NOW, UTC));
        // A manual run as History files it ("man"), built from its JSON: the stage-flag wiring
        // rule reads an assignment of that flag as a Stage's.
        Model.Sess run;
        try { run = Model.Sess.fromJson(s.toJson().put("man", true)); }
        catch (Exception e) { throw new AssertionError(e); }
        run.routineName = null;
        assertEquals("Manual run · 12 Oct 07:30", GrowthTrackWords.sessionTitle(run, NOW, UTC));
    }

    /** Bug 19: Erase reports done only with the GrowthTrack erase's answer, and says a failure. */
    @Test void eraseSaysWhetherTheGrowthTrackConnectionWentToo() {
        assertEquals("All data deleted", GrowthTrackWords.eraseResult(true, null));
        assertEquals("Deleted — some files could not be removed", GrowthTrackWords.eraseResult(false, null));
        String failed = GrowthTrackWords.eraseResult(true, "Could not remove the GrowthTrack encryption key");
        assertTrue(failed.startsWith("All data deleted. "));
        assertTrue(failed.contains("GrowthTrack connection could not be removed"));
    }

    /** The page a screen opens on, and Back: the page it was opened on leaves (setup's row
     *  opens the connect page, and Back from it is setup again). */
    @Test void backFromThePageTheScreenOpenedOnLeavesIt() {
        GrowthTrackClient.Snapshot none = snap(false, false, false, 0, 0, "", "", "");
        GrowthTrackClient.Snapshot linked = snap(true, false, false, 0, 0, "", "", "");
        assertEquals(GrowthTrackPages.CONNECT, GrowthTrackPages.pageFor(GrowthTrackPages.HOME, none, false));
        assertEquals(GrowthTrackPages.CONNECT, GrowthTrackPages.pageFor(0, null, false));
        assertEquals(GrowthTrackPages.HOME, GrowthTrackPages.pageFor(0, linked, false));
        assertEquals(GrowthTrackPages.HOME, GrowthTrackPages.pageFor(GrowthTrackPages.CONNECT, linked, false));
        assertEquals(GrowthTrackPages.CONNECT, GrowthTrackPages.pageFor(GrowthTrackPages.CONNECT, linked, true));
        assertEquals(GrowthTrackPages.LIST, GrowthTrackPages.pageFor(GrowthTrackPages.LIST, linked, false));
        assertEquals(GrowthTrackPages.CONNECT, GrowthTrackPages.pageFor(GrowthTrackPages.LIST, none, false));

        assertEquals(GrowthTrackPages.FINISH, GrowthTrackPages.back(GrowthTrackPages.CONNECT, GrowthTrackPages.CONNECT, false));
        assertEquals(GrowthTrackPages.FINISH, GrowthTrackPages.back(GrowthTrackPages.HOME, GrowthTrackPages.HOME, false));
        assertEquals(GrowthTrackPages.HOME, GrowthTrackPages.back(GrowthTrackPages.LIST, GrowthTrackPages.HOME, false));
        assertEquals(GrowthTrackPages.HOME, GrowthTrackPages.back(GrowthTrackPages.CONNECT, GrowthTrackPages.HOME, true));
        assertEquals(GrowthTrackPages.FINISH, GrowthTrackPages.back(GrowthTrackPages.HOME, GrowthTrackPages.CONNECT, false));
        for (int p : new int[]{ GrowthTrackPages.CONNECT, GrowthTrackPages.HOME, GrowthTrackPages.LIST })
            assertEquals(p, GrowthTrackPages.fromKey(GrowthTrackPages.key(p)));
        assertEquals(0, GrowthTrackPages.fromKey("about"));
    }

    private static GrowthTrackOutbox.Earlier earlier(int sendable, int total) {
        return new GrowthTrackOutbox.Earlier(sendable, total, new ArrayList<Model.Sess>());
    }

    /** Decision 4: asked once per account, with the honest count; "also" only when some can go. */
    @Test void theEarlierSessionsQuestionIsAskedOncePerAccountAndOnlyWhenThereAreSome() {
        assertFalse(GrowthTrackPages.asksEarlier(earlier(0, 0), null, false));
        assertTrue(GrowthTrackPages.asksEarlier(earlier(12, 14), null, false));
        assertTrue(GrowthTrackPages.asksEarlier(earlier(12, 14), snap(false, false, false, 0, 0, "", "", ""), false));
        GrowthTrackClient.Snapshot answered = snap(true, false, false, 0, 0, "", "", GrowthTrackClient.EARLIER_NEW);
        assertFalse(GrowthTrackPages.asksEarlier(earlier(12, 14), answered, false));
        assertTrue(GrowthTrackPages.asksEarlier(earlier(12, 14), answered, true), "a change of account is a new account");
        assertEquals("12 of 14 can be sent", earlier(12, 14).line());

        assertEquals("", GrowthTrackPages.earlierAnswer(false, GrowthTrackClient.EARLIER_ALSO, earlier(12, 14)));
        assertEquals(GrowthTrackClient.EARLIER_ALSO, GrowthTrackPages.earlierAnswer(true, GrowthTrackClient.EARLIER_ALSO, earlier(12, 14)));
        assertEquals(GrowthTrackClient.EARLIER_NEW, GrowthTrackPages.earlierAnswer(true, GrowthTrackClient.EARLIER_ALSO, earlier(0, 14)));
        assertEquals(GrowthTrackClient.EARLIER_NEW, GrowthTrackPages.earlierAnswer(true, GrowthTrackClient.EARLIER_NEW, earlier(12, 14)));
        assertEquals(GrowthTrackClient.EARLIER_NEW, GrowthTrackPages.earlierAnswer(true, null, earlier(12, 14)));
    }

    /** Bug 9: never over a live run; nothing at all when no sign-in waits; else shown. */
    @Test void theSignInReturnNeverCoversALiveRunAndAnswersOnlyItsOwnRequest() {
        assertEquals(GrowthTrackPages.RETURN_HEADLESS, GrowthTrackPages.onReturn(true, true));
        assertEquals(GrowthTrackPages.RETURN_HEADLESS, GrowthTrackPages.onReturn(true, false));
        assertEquals(GrowthTrackPages.RETURN_IGNORE, GrowthTrackPages.onReturn(false, false));
        assertEquals(GrowthTrackPages.RETURN_SHOW, GrowthTrackPages.onReturn(false, true));
    }

    /** The setup offer counts as seen when the person acts or leaves the step, never drawn. */
    @Test void theSetupOfferIsSeenWhenThePersonActsOrLeavesNotWhenDrawn() {
        assertFalse(GrowthTrackOnboarding.marksSeen(GrowthTrackOnboarding.DRAWN));
        assertTrue(GrowthTrackOnboarding.marksSeen(GrowthTrackOnboarding.TAPPED));
        assertTrue(GrowthTrackOnboarding.marksSeen(GrowthTrackOnboarding.LEFT_STEP));
    }

    /** Bug 10, the browser step: one return entry per identity, exactly one for each. */
    @Test void theSignInReturnCarriesTheIdentityItIsShownAs() {
        assertEquals(1 + Incognito.DISGUISES.length, Incognito.RETURNS.length);
        assertEquals(Incognito.RETURN_REAL, Incognito.RETURNS[0]);
        int[] ids = { Incognito.REAL, Incognito.FITNESS_LOG, Incognito.HABITS, Incognito.NOTES };
        for (int id : ids) {
            int on = 0;
            for (String r : Incognito.RETURNS) if (Incognito.returnEnabled(r, id)) on++;
            assertEquals(1, on, "identity " + id);
        }
        assertEquals(Incognito.RETURN_REAL, Incognito.returnFor(Incognito.REAL));
        assertEquals("org.openpump.GrowthTrackReturnNotes", Incognito.returnFor(Incognito.NOTES));
    }
}
