package org.openpump;

/** A one-time invitation only. It grants no authority and never queues or starts a run. */
public final class GrowthTrackOnboarding {
    private GrowthTrackOnboarding() { }
    public static boolean shouldOffer(Model m, boolean runLive, boolean unreadable) {
        if (m.growthTrackOfferSeen || runLive || unreadable) return false;
        for (Model.Sess s : m.sessLog.all) if (!s.sim) return false;
        return true;
    }
    /** Mark when displayed: Back, dismissal or process death must not become repeated nags. */
    public static void presented(Model m) { m.growthTrackOfferSeen = true; }
}
