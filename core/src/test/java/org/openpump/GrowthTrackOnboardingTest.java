package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GrowthTrackOnboardingTest {
    @Test void freshSetupCanOfferWithoutGrantingUploadingOrChangingFirstRun() {
        Model m = Model.seed(); int first = m.firstRun;
        assertTrue(GrowthTrackOnboarding.shouldOffer(m, false, false));
        GrowthTrackOnboarding.presented(m); assertEquals(first, m.firstRun); assertTrue(m.sessLog.all.isEmpty());
        assertFalse(GrowthTrackOnboarding.shouldOffer(m, false, false));
    }
    @Test void skipBackCancelAndRepeatedLaunchDoNotRepeatOffer() throws Exception {
        for (String exit : new String[]{"skip", "back", "cancel", "close", "connect"}) {
            Model m = Model.seed(); GrowthTrackOnboarding.presented(m);
            Model restored = Model.fromJson(m.toJson());
            assertFalse(GrowthTrackOnboarding.shouldOffer(restored, false, false), exit);
            assertEquals(FirstRun.NOT_STARTED, restored.firstRun);
        }
    }
    @Test void existingUsersAndARealSessionAreNeverInterrupted() throws Exception {
        assertFalse(GrowthTrackOnboarding.shouldOffer(Model.fromJson("{\"sets\":[],\"routines\":[]}"), false, false));
        Model m = Model.seed(); Model.Sess s = new Model.Sess(); s.sim = true; m.sessLog.all.add(s);
        assertTrue(GrowthTrackOnboarding.shouldOffer(m, false, false));
        s.sim = false; assertFalse(GrowthTrackOnboarding.shouldOffer(m, false, false));
    }
    @Test void activeRunOrUnreadableStoreDefersOfferWithoutConsumingIt() {
        Model m = Model.seed(); assertFalse(GrowthTrackOnboarding.shouldOffer(m, true, false));
        assertFalse(GrowthTrackOnboarding.shouldOffer(m, false, true)); assertFalse(m.growthTrackOfferSeen);
    }
    @Test void completingSetupAfterSkipStillUsesExistingStarterGateAndMigrationDefault() throws Exception {
        Model m = Model.seed(); GrowthTrackOnboarding.presented(m); FirstRun.finish(m);
        assertEquals(FirstRun.FINISH_STARTER, FirstRun.finishAction("starter", true, false));
        assertEquals(FirstRun.DONE, m.firstRun); assertTrue(Model.fromJson("{\"sets\":[],\"routines\":[]}").growthTrackOfferSeen);
        assertTrue(Model.fromJson(m.toJson()).growthTrackOfferSeen);
    }
}
