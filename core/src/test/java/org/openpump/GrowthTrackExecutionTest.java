package org.openpump;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GrowthTrackExecutionTest {
    @Test void restRequiresVerifiedReleaseAndElapsedTime() throws Exception {
        JSONObject f=GrowthTrackRoutineTest.fixture();
        JSONObject s=f.getJSONObject("routine").getJSONArray("steps").getJSONObject(0);
        s.put("rest_seconds_between_sets",5);
        f.getJSONObject("routine").getJSONArray("steps").put(new JSONObject(s.toString()).put("position",2));
        GrowthTrackExecution e=new GrowthTrackExecution(GrowthTrackRoutine.parse(f));
        assertThrows(IllegalStateException.class,()->e.start(0,false));
        e.start(0,true);e.advanceAcknowledged(60000);
        assertEquals(GrowthTrackExecution.State.RELEASE_REQUIRED,e.state());
        assertThrows(IllegalStateException.class,()->e.resumeAfterRest(90000,true));
        e.releaseVerified(61000);
        assertFalse(e.restDue(65999)); assertTrue(e.restDue(66000));
        assertThrows(IllegalStateException.class,()->e.resumeAfterRest(66000,false));
        e.resumeAfterRest(66000,true);
        assertEquals(1,e.stepIndex());assertEquals(GrowthTrackExecution.State.RUNNING,e.state());
        e.stop();e.releaseVerified(67000);assertEquals(GrowthTrackExecution.State.STOPPED,e.state());
    }
    @Test void equipmentWaitDoesNotAutoConfirmAtTimeout() throws Exception {
        JSONObject f=GrowthTrackRoutineTest.fixture();
        f.getJSONArray("required_capabilities").put("confirm");
        JSONObject s=f.getJSONObject("routine").getJSONArray("steps").getJSONObject(0);
        s.remove("pattern");s.put("role","confirm").put("work_seconds_per_set",0);
        GrowthTrackExecution e=new GrowthTrackExecution(GrowthTrackRoutine.parse(f));e.start(0,true);
        assertFalse(e.confirmationNeedsRelease(599999));assertTrue(e.confirmationNeedsRelease(600000));
        e.confirmationReleaseVerified();assertEquals(GrowthTrackExecution.State.CONFIRM,e.state());
        assertFalse(e.confirmationNeedsRelease(999999));
        e.confirm(1000000,true);e.releaseVerified(1000001);
        assertEquals(GrowthTrackExecution.State.STOPPED,e.state());
    }
}
