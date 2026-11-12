package org.openpump;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GrowthTrackExecutionGateTest {
    @Test void unsupportedFeaturesFailBeforeHostCanArm() throws Exception {
        JSONObject f=GrowthTrackRoutineTest.fixture();
        GrowthTrackRoutine r=GrowthTrackRoutine.parse(f);
        assertThrows(IllegalArgumentException.class,()->GrowthTrackExecutionGate.requireSupported(r,false,false,30,false));
        f.getJSONObject("routine").getJSONArray("steps").getJSONObject(0).getJSONArray("pattern").getJSONObject(0).put("reach_seconds",2);
        GrowthTrackRoutine timed=GrowthTrackRoutine.parse(f);
        assertThrows(IllegalArgumentException.class,()->GrowthTrackExecutionGate.requireSupported(timed,true,false,1,false));
    }
    @Test void unknownActuatorCannotBeSilentlyDropped() throws Exception {
        JSONObject f=GrowthTrackRoutineTest.fixture();
        f.getJSONObject("routine").getJSONArray("steps").getJSONObject(0).getJSONArray("pattern").getJSONObject(0).put("clamp_psi",5);
        assertThrows(IllegalArgumentException.class,()->GrowthTrackRoutine.parse(f));
    }
}
