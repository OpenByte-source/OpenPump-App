package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * KEEP UNKNOWN FIELDS (0.10). A key this version does not know - written by a newer app -
 * survives a load and a save with its value unchanged: at the top of model.json, inside a
 * reading, inside a photo (JsonKeep). And no key this version reads is ever "kept" as
 * unknown: a legacy key written back would be migrated again on every load.
 */
class UnknownKeysTest {

    private static final String FUTURE_OBJ = "{\"a\":[1,\"two\",{\"b\":true,\"n\":null}],\"c\":\"é ✓\"}";

    /** A model with one reading carrying a front photo, as JSON. */
    private static JSONObject modelWithPhoto() throws Exception {
        Model m = Model.seed();
        Model.Reading r = new Model.Reading();
        r.id = "m1000"; r.ts = 1000L; r.len = 15.5; r.method = Model.Reading.METHOD_BPEL;
        r.state = Model.Reading.stateForMethod(r.method);
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = "/x/reading-m1000-front.jpg"; p.ts = 1000L;
        r.photoFront = p; r.photo = true;
        m.measLog.all.add(r);
        return new JSONObject(m.toJson());
    }

    private static JSONObject reading0(JSONObject model) {
        return model.optJSONArray("measLog").optJSONObject(0);
    }

    private static JSONObject photo0(JSONObject model) {
        return reading0(model).optJSONArray("photoFront").optJSONObject(0);
    }

    /** The value's own serialised text - what "unchanged" is measured in. */
    private static String text(Object v) {
        return v instanceof JSONObject || v instanceof JSONArray ? v.toString() : String.valueOf(v);
    }

    @Test void aNewerAppsKeysSurviveASaveAtEveryLevel() throws Exception {
        JSONObject in = modelWithPhoto();
        in.put("zTop", new JSONObject(FUTURE_OBJ));
        in.put("zTopNum", 42);
        reading0(in).put("zReading", new JSONArray("[\"x\",2.5,false]"));
        reading0(in).put("zReadingStr", "a \"quoted\" value");
        photo0(in).put("zPhoto", new JSONObject("{\"deep\":{\"deeper\":[]}}"));

        String before = in.toString();
        JSONObject orig = new JSONObject(before);
        // Twice: a kept key must survive being kept.
        JSONObject out = new JSONObject(Model.fromJson(Model.fromJson(before).toJson()).toJson());

        assertEquals(text(orig.opt("zTop")), text(out.opt("zTop")));
        assertEquals(text(orig.opt("zTopNum")), text(out.opt("zTopNum")));
        assertEquals(text(reading0(orig).opt("zReading")), text(reading0(out).opt("zReading")));
        assertEquals(text(reading0(orig).opt("zReadingStr")), text(reading0(out).opt("zReadingStr")));
        assertEquals(text(photo0(orig).opt("zPhoto")), text(photo0(out).opt("zPhoto")));
        // Byte for byte in the saved file, too: the value's serialised text is in it.
        String saved = Model.fromJson(before).toJson();
        assertTrue(saved.contains("\"zTop\":" + orig.opt("zTop").toString()), saved);
        assertTrue(saved.contains("\"zPhoto\":{\"deep\":{\"deeper\":[]}}"));
    }

    @Test void ourOwnFieldsStillWinAndStillChange() throws Exception {
        JSONObject in = modelWithPhoto();
        in.put("zTop", "kept");
        Model m = Model.fromJson(in.toString());
        m.ceilKpa = 33;
        m.measLog.all.get(0).note = "edited";
        JSONObject out = new JSONObject(m.toJson());
        assertEquals(33, out.optInt("ceil"));
        assertEquals("edited", reading0(out).optString("note"));
        assertEquals("kept", out.optString("zTop"));
    }

    @Test void anEditedCopyKeepsTheReadingsUnknownKeys() throws Exception {
        JSONObject in = modelWithPhoto();
        reading0(in).put("zReading", "kept");
        Model.Reading r = Model.fromJson(in.toString()).measLog.all.get(0);
        Model.Reading c = r.copy();
        c.note = "a new note";
        assertEquals("kept", c.toJson().optString("zReading"));
    }

    @Test void filesThisVersionWroteKeepNothing() throws Exception {
        String own = modelWithPhoto().toString();
        Model m = Model.fromJson(own);
        assertNull(m.unknown, "every top-level key this version writes is one it reads");
        assertNull(m.measLog.all.get(0).unknown);
        assertNull(m.measLog.all.get(0).photoFront.unknown);
        assertEquals(new JSONObject(own).length(), new JSONObject(m.toJson()).length());
    }

    @Test void aNewReadingHasNoUnknownKeys() throws Exception {
        Model.Reading r = new Model.Reading();
        r.id = "m1";
        assertNull(r.unknown);
        assertFalse(r.toJson().has("unknown"));
    }

    /**
     * EVERY KEY THE LOADER NAMES IS ASKED FOR - so none is ever mistaken for a newer app's
     * and written back. Read from Model.java itself: each literal (or String constant) key a
     * fromJson reads from its object, put into an otherwise real file, must not come back
     * in `unknown`. A key read only on some branch would show up here.
     */
    @Test void noKeyTheLoaderReadsIsKeptAsUnknown() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get("src/main/java/org/openpump/Model.java")),
                                StandardCharsets.UTF_8);
        Set<String> top = keysReadIn(src, "public static Model fromJson(String s)");
        Set<String> reading = keysReadIn(src, "public static Reading fromJson(JSONObject src)");
        Set<String> photo = keysReadIn(src, "public static Photo fromJson(JSONObject src)");
        assertTrue(top.size() > 100 && reading.size() >= 20 && photo.size() >= 15,
            "the scan found " + top.size() + "/" + reading.size() + "/" + photo.size());

        JSONObject in = modelWithPhoto();
        fillMissing(in, top);
        fillMissing(reading0(in), reading);
        fillMissing(photo0(in), photo);
        Model m = Model.fromJson(in.toString());
        assertNotNull(m.measLog.all.get(0).photoFront, "the filled file still loads");
        assertNoneKept(m.unknown, top, "top level");
        assertNoneKept(m.measLog.all.get(0).unknown, reading, "reading");
        assertNoneKept(m.measLog.all.get(0).photoFront.unknown, photo, "photo");
    }

    private static void fillMissing(JSONObject o, Set<String> keys) throws Exception {
        for (String k : keys) if (!o.has(k)) o.put(k, "0");
    }

    private static void assertNoneKept(JSONObject kept, Set<String> read, String where) {
        if (kept == null) return;
        Iterator<String> it = kept.keys();
        while (it.hasNext()) {
            String k = it.next();
            assertFalse(read.contains(k), where + ": \"" + k + "\" is read by the loader but "
                + "was kept as unknown - it would be written back and read again");
        }
    }

    /** The keys `o.<opt|get|has|isNull>(...)` names in the method that starts at `sig`. */
    private static Set<String> keysReadIn(String src, String sig) {
        int at = src.indexOf(sig);
        assertTrue(at >= 0, "Model.java has no " + sig);
        int open = src.indexOf('{', at), depth = 0, end = open;
        for (int i = open; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) { end = i; break; }
        }
        String body = src.substring(open, end)
            .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Map<String, String> consts = new java.util.HashMap<String, String>();
        Matcher c = Pattern.compile("static final String (\\w+)\\s*=\\s*\"([^\"]*)\"").matcher(src);
        while (c.find()) consts.put(c.group(1), c.group(2));
        Set<String> out = new LinkedHashSet<String>();
        Matcher m = Pattern.compile(
            "\\bo\\.(?:opt\\w*|get\\w*|has|isNull)\\(\\s*(?:\"([^\"]*)\"|([A-Z_][A-Z0-9_]*))")
            .matcher(body);
        while (m.find()) {
            String k = m.group(1) != null ? m.group(1) : consts.get(m.group(2));
            if (k != null) out.add(k);
        }
        return out;
    }
}
