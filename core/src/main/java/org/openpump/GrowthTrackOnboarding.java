package org.openpump;

/** A one-time invitation only. It grants no authority and never queues or starts a run. */
public final class GrowthTrackOnboarding {
    private GrowthTrackOnboarding() { }
    public static boolean shouldOffer(Model m, boolean runLive, boolean unreadable) {
        if (m.growthTrackOfferSeen || runLive || unreadable) return false;
        for (Model.Sess s : m.sessLog.all) if (!s.sim) return false;
        return true;
    }

    /** What happened to the invitation on setup's Ready step. */
    public static final int DRAWN = 0, TAPPED = 1, LEFT_STEP = 2;

    /**
     * WHEN IT COUNTS AS SEEN (the small setup bug): when the person acts on it or leaves the
     * step - never when it is merely drawn. Marking on drawing lost it for good to a process
     * death on that page, or to any redraw that made a new walk, before anyone had read it.
     */
    public static boolean marksSeen(int event) {
        return event == TAPPED || event == LEFT_STEP;
    }

    /** Records the invitation as seen (call on TAPPED or LEFT_STEP; see {@link #marksSeen}). */
    public static void presented(Model m) { m.growthTrackOfferSeen = true; }
}
