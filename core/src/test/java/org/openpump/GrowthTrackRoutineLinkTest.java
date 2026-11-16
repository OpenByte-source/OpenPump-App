package org.openpump;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GrowthTrackRoutineLinkTest {
 @Test void acceptsOnlyUuidNavigation() {
  String id="11111111-1111-4111-8111-111111111111";
  String link="gt-openpump://routine/import?id="+id;
  assertEquals(id,GrowthTrackRoutineLink.parse(link));
  for(String bad:new String[]{null,"",link+"&token=secret",link+"#start",link+"&id="+id,
   "https://routine/import?id="+id,"gt-openpump://oauth/callback?id="+id,
   "gt-openpump://routine/import?id=../file", "gt-openpump://routine/import?id=%31"})
   assertNull(GrowthTrackRoutineLink.parse(bad));
 }
}
