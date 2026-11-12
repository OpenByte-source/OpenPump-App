package org.openpump;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GrowthTrackStaticRunTest {
    @Test void staticAdapterPreservesDurationAndNeverRoundsPressureUp() throws Exception {
        JSONObject f=GrowthTrackRoutineTest.fixture();
        JSONObject s=f.getJSONObject("routine").getJSONArray("steps").getJSONObject(0);
        s.put("method_slug","static_pumping").put("work_seconds_per_set",180);
        Model.Set set=GrowthTrackStaticRun.prepare(GrowthTrackRoutine.parse(f),5);
        assertEquals(180,set.dur);assertEquals(180,set.uh);assertEquals(0,set.lh);
        assertEquals(5,set.up);assertEquals(100,set.sp);
        s.put("work_seconds_per_set",600);
        assertThrows(IllegalArgumentException.class,()->GrowthTrackStaticRun.prepare(GrowthTrackRoutine.parse(f),20));
    }
    @Test void complexImportRefusedBeforeAnyPumpAccess() throws Exception {
        assertThrows(IllegalArgumentException.class,()->GrowthTrackStaticRun.prepare(GrowthTrackRoutine.parse(GrowthTrackRoutineTest.fixture()),20));
    }
}
