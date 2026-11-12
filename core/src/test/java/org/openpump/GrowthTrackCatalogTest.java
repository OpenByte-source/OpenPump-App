package org.openpump;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GrowthTrackCatalogTest {
    @Test void actualGtExportsParseWithoutLosingClassifications() throws Exception {
        try(InputStream stream=getClass().getResourceAsStream("/gt-templates.json")) {
            assertNotNull(stream,"Actual GT catalog fixture is required");
            JSONArray catalog=new JSONArray(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
            assertTrue(catalog.length()>0);
            for(int i=0;i<catalog.length();i++) {
                JSONObject envelope=catalog.getJSONObject(i);
                GrowthTrackRoutine r=GrowthTrackRoutine.parse(envelope);
                JSONArray steps=envelope.getJSONObject("routine").getJSONArray("steps");
                assertEquals(steps.length(),r.steps.size());
                for(int j=0;j<steps.length();j++) {
                    assertEquals(steps.getJSONObject(j).getString("method_slug"),r.steps.get(j).method);
                    assertEquals(steps.getJSONObject(j).getString("exercise_type"),r.steps.get(j).exerciseType);
                }
            }
        }
    }
}
