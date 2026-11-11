package org.openpump;

/**
 * WHERE THE GROWTHTRACK SCREEN GOES (owner decisions 3 and 4; the small UI bugs). Pure, so
 * Back, the page after a sign-in and the sign-in return's three outcomes are pinned by tests:
 *  - the page a screen opens on: the connect page while not connected (or for "Change
 *    account"), else the GrowthTrack screen;
 *  - Back on the page the screen was opened on leaves it - so the connect page opened from
 *    setup's row goes straight back to setup, never deeper into the screen;
 *  - the earlier-sessions question is asked once per account, and "also" only when some can be
 *    sent;
 *  - the sign-in return (an exported link any app could fire) finishes at once while a run is
 *    live, does nothing at all when no sign-in is waiting, and only then shows the screen.
 */
public final class GrowthTrackPages {
    private GrowthTrackPages() { }

    public static final int FINISH = 0, CONNECT = 1, HOME = 2, LIST = 3;
    /** The sign-in return's landing pages (GrowthTrackReturn#landing): "You’re connected",
     *  or why not with "Try again". Shown once, on the return, and kept as they are until
     *  the person leaves them. */
    public static final int CONNECTED = 4, NOT_CONNECTED = 5;

    /** The saved form of a page (instance state, the opening intent). */
    public static String key(int page) {
        switch (page) {
            case CONNECT: return "connect";
            case LIST: return "list";
            case HOME: return "home";
            case CONNECTED: return "connected";
            case NOT_CONNECTED: return "not-connected";
            default: return "";
        }
    }

    /** A saved page back; 0 (decide from the connection) for anything else. */
    public static int fromKey(String key) {
        if ("connect".equals(key)) return CONNECT;
        if ("list".equals(key)) return LIST;
        if ("home".equals(key)) return HOME;
        if ("connected".equals(key)) return CONNECTED;
        if ("not-connected".equals(key)) return NOT_CONNECTED;
        return 0;
    }

    private static boolean linked(GrowthTrackClient.Snapshot s) {
        return s != null && (s.connected || s.needsReconnect);
    }

    /** The page for this connection: asked-for `wanted` when it still fits, else connect or home. */
    public static int pageFor(int wanted, GrowthTrackClient.Snapshot s, boolean changingAccount) {
        if (wanted == CONNECTED || wanted == NOT_CONNECTED) return wanted;
        if (wanted == CONNECT) return linked(s) && !changingAccount ? HOME : CONNECT;
        if (!linked(s)) return CONNECT;
        return wanted == LIST ? LIST : HOME;
    }

    /** Back: the page the screen was opened on leaves - back to where it was opened from,
     *  in that screen's own task (GrowthTrackReturn) - and so does a landing page and the
     *  screen opened from one; the list returns to the screen, and the connect page opened by
     *  "Change account" returns to it too. */
    public static int back(int page, int entry, boolean changingAccount) {
        if (page == entry) return FINISH;
        if (page == LIST) return HOME;
        if (page == CONNECT && changingAccount) return HOME;
        return FINISH;
    }

    /** Whether the connect page asks "Already on this phone": there are earlier sessions and
     *  this account has not answered (a change of account is a new account). */
    public static boolean asksEarlier(GrowthTrackOutbox.Earlier e, GrowthTrackClient.Snapshot s, boolean changingAccount) {
        if (e == null || e.total <= 0) return false;
        if (changingAccount || s == null) return true;
        return s.earlierChoice == null || s.earlierChoice.isEmpty();
    }

    /** What the sign-in carries: "" when not asked, "also" only when chosen and some can go. */
    public static String earlierAnswer(boolean asked, String picked, GrowthTrackOutbox.Earlier e) {
        if (!asked) return "";
        if (GrowthTrackClient.EARLIER_ALSO.equals(picked) && e != null && e.sendable > 0) return GrowthTrackClient.EARLIER_ALSO;
        return GrowthTrackClient.EARLIER_NEW;
    }

    /** The sign-in return's outcomes (bug 9). */
    public static final int RETURN_HEADLESS = 1, RETURN_IGNORE = 2, RETURN_SHOW = 3;

    /**
     * A live run (or anything still unsafe) is never covered: the exchange runs with no screen
     * and the return finishes at once. With no sign-in waiting the link is not ours to answer -
     * nothing runs and nothing opens. Otherwise the exchange runs and the screen shows it.
     */
    public static int onReturn(boolean doorShut, boolean signInWaiting) {
        if (doorShut) return RETURN_HEADLESS;
        return signInWaiting ? RETURN_SHOW : RETURN_IGNORE;
    }
}
