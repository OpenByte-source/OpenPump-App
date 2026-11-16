package org.openpump;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GrowthTrackImportedProvenanceTest {
 @Test void persistsTypedMethodAndRejectsUnknown() throws Exception {
  Model.Sess s=new Model.Sess();s.gtImportedMethod="static_pumping";
  assertEquals("static_pumping",Model.Sess.fromJson(s.toJson()).gtImportedMethod);
  assertNull(Model.Sess.fromJson(new JSONObject()).gtImportedMethod);
  assertNull(Model.Sess.fromJson(new JSONObject().put("gtImportedMethod","invented")).gtImportedMethod);
 }
 @Test void abortedStaticNeverBecomesMilking() throws Exception {
  Model.Sess s=new Model.Sess();s.id="test-static";s.routineName="Imported static";
  s.recordedStartMs=1700000000000L;s.recordedEndMs=s.recordedStartMs+5000;s.durSec=5;
  s.gtImportedMethod="static_pumping";s.completed=false;
  GrowthTrackSession.Draft d=GrowthTrackSession.draft(Model.Sess.fromJson(s.toJson()),null);
  assertEquals("static_pumping",d.methodForWholeSession());
  assertEquals(5,d.seconds);assertNull(d.observedPeakInhg);
  JSONObject payload=d.payload();assertEquals("static_pumping",payload.getString("method"));
  assertEquals(5,payload.getInt("total_duration_seconds"));
  assertFalse(payload.has("sets"));
  assertFalse(payload.has("pressure_peak_inhg"));
 }
 @Test void coldDeclinedPartRetainsStaticBeforeExport() throws Exception {
  Model.Sess recovered=new Model.Sess();recovered.id="cold-static";recovered.routineName="Recovered";
  recovered.recordedStartMs=1700000000000L;recovered.recordedEndMs=recovered.recordedStartMs+5000;
  recovered.durSec=5;recovered.completed=false;
  DeclinedRun.restoreImportedMethod(recovered,true);
  Model.Sess persisted=Model.Sess.fromJson(recovered.toJson());
  JSONObject payload=GrowthTrackSession.draft(persisted,null).payload();
  assertEquals("static_pumping",payload.getString("method"));
  assertEquals(5,payload.getInt("total_duration_seconds"));
  assertFalse(payload.has("sets"));
  Model.Sess legacy=new Model.Sess();DeclinedRun.restoreImportedMethod(legacy,false);
  assertNull(legacy.gtImportedMethod);
 } @Test void completedImportedStaticExportsObservedPressureAndDuration() throws Exception {
  Model.Sess s=new Model.Sess();s.id="completed-static";s.routineName="Imported static";
  s.recordedStartMs=1700000000000L;s.recordedEndMs=s.recordedStartMs+130000;
  s.durSec=130;s.completed=true;s.gtImportedMethod="static_pumping";
  s.peakKpa=Double.valueOf(20.31834); // Observed telemetry: 6 inHg, not the commanded target.
  JSONObject payload=GrowthTrackSession.draft(Model.Sess.fromJson(s.toJson()),null).payload();
  assertEquals("static_pumping",payload.getString("method"));
  assertEquals(130,payload.getInt("total_duration_seconds"));
  assertEquals(6.0,payload.getDouble("pressure_peak_inhg"),0.001);
  assertFalse(payload.has("sets"));
 } @Test void reconnectRestartFilesImportedPartButNeverUsesNativeRestart() throws Exception {
  java.nio.file.Path root=java.nio.file.Paths.get("").toAbsolutePath();
  while(root!=null&&!java.nio.file.Files.exists(root.resolve("app/src/main/java/org/openpump/SessionActivity.java"))) root=root.getParent();
  assertNotNull(root);
  String source=java.nio.file.Files.readString(root.resolve("app/src/main/java/org/openpump/SessionActivity.java"));
  String restart=source.substring(source.indexOf("private final class ReconnectChoiceTap"),source.indexOf("private final class ReconnectChoiceTap")+9000);
  int capture=restart.indexOf("boolean wasImportedStatic = growthTrackStaticRun;");
  int file=restart.indexOf("finishSession(true);",capture);
  int gate=restart.indexOf("if (wasImportedStatic)",file);
  int stop=restart.indexOf("return;",gate);
  int nativeRestart=restart.indexOf("if (wasManual) beginEphemeralRun",gate);
  assertTrue(capture>=0&&file>capture&&gate>file&&stop>gate&&nativeRestart>stop);
 }}
