package org.openpump;
/** UUID-only navigation, never credentials or a command to operate hardware. */
public final class GrowthTrackRoutineLink {
 private GrowthTrackRoutineLink() {}
 public static String parse(String url) {
  if(url==null || !url.matches("gt-openpump://routine/import\\?id=[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return null;
  return url.substring(url.indexOf("id=")+3).toLowerCase(java.util.Locale.ROOT);
 }
}
