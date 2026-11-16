package org.openpump;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GrowthTrackRoutineTest {
    static JSONObject fixture() throws Exception { return new JSONObject("{\"format\":\"gt-routine\",\"schema_version\":1,\"execution_contract\":\"openpump-pattern-v1\",\"required_capabilities\":[\"pattern\"],\"routine\":{\"id\":\"11111111-1111-4111-8111-111111111111\",\"name\":\"Test\",\"steps\":[{\"position\":1,\"method_slug\":\"rip\",\"exercise_name\":\"RIP\",\"exercise_type\":\"girth\",\"sets\":1,\"rest_seconds_between_sets\":0,\"work_seconds_per_set\":60,\"pattern\":[{\"duration_seconds\":10,\"pressure_inhg\":2}]}]}}"); }
    @Test void acceptsPatternWithoutInventingRamp() throws Exception {
        GrowthTrackRoutine r=GrowthTrackRoutine.parse(fixture());
        assertEquals(2,r.steps.get(0).phases.get(0).pressureInhg);
        assertNull(r.steps.get(0).phases.get(0).reachSeconds);
        assertThrows(UnsupportedOperationException.class,()->r.steps.clear());
    }
    @Test void refusesUnknownCapabilitiesAndNegativeTargets() throws Exception {
        JSONObject f=fixture(); f.getJSONArray("required_capabilities").put("magic");
        assertThrows(IllegalArgumentException.class,()->GrowthTrackRoutine.parse(f));
        JSONObject g=fixture(); g.getJSONObject("routine").getJSONArray("steps").getJSONObject(0).getJSONArray("pattern").getJSONObject(0).put("pressure_inhg",-1);
        assertThrows(IllegalArgumentException.class,()->GrowthTrackRoutine.parse(g));
    }
    @Test void rejectsUnnegotiatedStepControls() throws Exception {
        for(String role:new String[]{"work","fatigue","retention","pause","skip"}) {
            JSONObject f=fixture();
            f.getJSONObject("routine").getJSONArray("steps").getJSONObject(0).put("role",role);
            assertThrows(IllegalArgumentException.class,()->GrowthTrackRoutine.parse(f));
        }
        for(String field:new String[]{"repeat","pressure_inhg","rest_seconds","auto_start","heat"}) {
            JSONObject f=fixture();
            f.getJSONObject("routine").getJSONArray("steps").getJSONObject(0).put(field,1);
            assertThrows(IllegalArgumentException.class,()->GrowthTrackRoutine.parse(f));
        }
        JSONObject grouped=fixture();grouped.getJSONObject("routine").getJSONArray("steps").getJSONObject(0).put("sets",2);
        assertThrows(IllegalArgumentException.class,()->GrowthTrackRoutine.parse(grouped));
        JSONObject confirm=fixture();confirm.getJSONArray("required_capabilities").put("confirm");
        JSONObject step=confirm.getJSONObject("routine").getJSONArray("steps").getJSONObject(0);
        step.remove("pattern");step.put("role","confirm").put("work_seconds_per_set",0).put("rest_seconds_between_sets",5);
        assertThrows(IllegalArgumentException.class,()->GrowthTrackRoutine.parse(confirm));
    }}
