package org.openpump;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * KEYS THIS VERSION DOES NOT KNOW ARE KEPT, NOT DROPPED (0.10, the owner's call).
 *
 * model.json is rebuilt from fields on every save, so a key the loading version never
 * read was simply gone after the next tap. A backup made by a NEWER app, opened in this
 * one, lost whatever that newer version had added - and restoring it into the newer app
 * later could not bring it back.
 *
 * So each object the model reads from - the top level, each reading, each photo - is read
 * through a {@link Reader}, which remembers every key the loader asked for. What was never
 * asked for is kept, value untouched, and {@link #putBack} writes it out again after the
 * fields, unless this version wrote that key itself.
 *
 * WHY "ASKED FOR" AND NOT A LIST OF KNOWN KEYS. The loader reads a key with a default when
 * it is absent, so every key it understands is asked for on every load - including legacy
 * keys it only migrates from, which must NOT be kept (a legacy key written back would be
 * migrated again on the next load). A hand-kept list would go stale the first time a field
 * was added and not listed; the loader's own reads cannot.
 *
 * org.json's typed getters (optString, optInt, optBoolean, optJSONArray, getString ...) all
 * go through opt(name) or get(name), so overriding those two, has and isNull sees every
 * read. UnknownKeysTest holds that for every key Model reads.
 */
public final class JsonKeep {
    private JsonKeep() { }

    /** A JSONObject that remembers which keys were asked for. */
    public static final class Reader extends JSONObject {
        private final Set<String> asked = new HashSet<String>();

        /** Parses `json` - the top level of model.json. */
        public Reader(String json) throws JSONException { super(json); }

        /** A shallow copy of `src` - a reading or a photo inside the parsed tree. */
        public Reader(JSONObject src) throws JSONException { super(src, keyNames(src)); }

        @Override public Object opt(String name) { asked.add(name); return super.opt(name); }
        @Override public Object get(String name) throws JSONException {
            asked.add(name);
            return super.get(name);
        }
        @Override public boolean has(String name) { asked.add(name); return super.has(name); }
        @Override public boolean isNull(String name) { asked.add(name); return super.isNull(name); }

        /** Every entry the loader never asked for, values as parsed; null when there are
         *  none, which is every file this version (or an older one) wrote.
         *  @param readOnABranch keys the loader reads only on some branch - a legacy key
         *      migrated from only when its successor is absent. They are this version's
         *      keys all the same and are never kept (UnknownKeysTest finds any that are
         *      missing from the list). */
        public JSONObject unasked(String... readOnABranch) {
            Set<String> known = new HashSet<String>(asked);
            for (int i = 0; i < readOnABranch.length; i++) known.add(readOnABranch[i]);
            JSONObject out = null;
            Iterator<String> it = keys();
            while (it.hasNext()) {
                String k = it.next();
                if (known.contains(k)) continue;
                try {
                    if (out == null) out = new JSONObject();
                    // super.opt: reading the value to keep it is not the loader asking.
                    out.put(k, super.opt(k));
                } catch (JSONException ignored) { }
            }
            return out;
        }
    }

    /** A Reader over `o`, or null for a null object. Never throws: a copy that fails
     *  keeps nothing, which is what the app did before. */
    public static Reader reader(JSONObject o) {
        if (o == null) return null;
        try { return new Reader(o); } catch (JSONException e) { return null; }
    }

    /** Writes the kept entries back into `o` after its fields - every one this version did
     *  not write itself, unchanged. A no-op for null. */
    public static void putBack(JSONObject o, JSONObject kept) throws JSONException {
        if (o == null || kept == null) return;
        Iterator<String> it = kept.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!o.has(k)) o.put(k, kept.opt(k));
        }
    }

    private static String[] keyNames(JSONObject o) {
        String[] out = new String[o.length()];
        Iterator<String> it = o.keys();
        int i = 0;
        while (it.hasNext() && i < out.length) out[i++] = it.next();
        return out;
    }
}
