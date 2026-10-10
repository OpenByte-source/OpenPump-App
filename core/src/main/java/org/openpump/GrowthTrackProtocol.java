package org.openpump;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** The public native-client contract. No partner key or GrowthTrack user JWT belongs here. */
public final class GrowthTrackProtocol {
    private GrowthTrackProtocol() { }
    public static final String API = "https://rxtqbktyeetmbigbpbdd.supabase.co/functions/v1";
    public static final String CLIENT_ID = "gtc_bfa5809efbd314f246dd5d0f";
    public static final String REDIRECT = "gt-openpump://oauth/callback";
    public static final String CONNECTED_APPS = "https://pe-growth-track.com/connected-apps";
    public static final String WRITE = "sessions:write", READ = "routines:read";
    public static final int MAX_BYTES = 1_000_000;
    // Below both the older pilot's 100 and the current handler's 500-session limit.
    public static final int BATCH_SIZE = 100;
    public static final long AUTH_LIFETIME_MS = 15 * 60_000L;

    public static final class Pending {
        public final String state, verifier, scope;
        public final long createdAt;
        Pending(String state, String verifier, String scope, long createdAt) {
            this.state = state; this.verifier = verifier; this.scope = scope; this.createdAt = createdAt;
        }
        public JSONObject json() throws JSONException {
            return new JSONObject().put("state", state).put("verifier", verifier)
                .put("scope", scope).put("created_at", createdAt);
        }
        public static Pending from(JSONObject o) throws JSONException {
            String state = o.getString("state"), verifier = o.getString("verifier");
            if (!state.matches("[a-f0-9]{64}") || !verifier.matches("[A-Za-z0-9._~-]{43,128}"))
                throw new JSONException("Invalid saved authorization");
            String scope = o.getString("scope");
            if (!WRITE.equals(scope) && !(WRITE + " " + READ).equals(scope))
                throw new JSONException("Invalid saved scope");
            return new Pending(state, verifier, scope, o.getLong("created_at"));
        }
        public String authorizeUrl() {
            Map<String, String> p = new LinkedHashMap<String, String>();
            p.put("response_type", "code"); p.put("client_id", CLIENT_ID); p.put("redirect_uri", REDIRECT);
            p.put("scope", scope); p.put("state", state); p.put("code_challenge", challenge(verifier));
            p.put("code_challenge_method", "S256");
            return API + "/oauth-authorize?" + form(p);
        }
    }

    public static Pending pending(boolean routines, long now) {
        SecureRandom random = new SecureRandom();
        byte[] a = new byte[32], b = new byte[32]; random.nextBytes(a); random.nextBytes(b);
        return new Pending(hex(a), hex(b), WRITE + (routines ? " " + READ : ""), now);
    }
    public static String challenge(String verifier) {
        if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}"))
            throw new IllegalArgumentException("Invalid PKCE verifier");
        return base64Url(digest(verifier));
    }
    public static String stableId(String id) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("This record has no stable session ID");
        return id.length() <= 125 ? "op_" + id : "op_" + hex(digest(id));
    }

    public static final class Callback {
        public final String code, error;
        Callback(String code, String error) { this.code = code; this.error = error; }
    }
    /** Validate the exact callback and state before consuming anything or making a request. */
    public static Callback callback(String value, Pending pending, long now) {
        if (pending == null) throw new IllegalArgumentException("No connection request is waiting");
        if (now < pending.createdAt || now - pending.createdAt > AUTH_LIFETIME_MS)
            throw new IllegalArgumentException("Connection request expired. Connect again");
        if (value == null || value.length() > 8192) throw new IllegalArgumentException("Invalid connection callback");
        try {
            URI u = new URI(value);
            if (!"gt-openpump".equals(u.getScheme()) || !"oauth".equals(u.getHost())
                    || !"/callback".equals(u.getRawPath()) || u.getPort() != -1
                    || u.getUserInfo() != null || u.getFragment() != null)
                throw new IllegalArgumentException("Unexpected connection callback");
            Map<String, String> p = new LinkedHashMap<String, String>();
            if (u.getRawQuery() == null) throw new IllegalArgumentException("Missing connection response");
            for (String pair : u.getRawQuery().split("&", -1)) {
                String[] parts = pair.split("=", 2);
                String key = decode(parts[0]), val = parts.length == 2 ? decode(parts[1]) : "";
                if (!("state".equals(key) || "code".equals(key) || "error".equals(key) || "error_description".equals(key))
                        || p.put(key, val) != null) throw new IllegalArgumentException("Invalid connection response parameters");
            }
            String state = p.get("state");
            if (state == null || !MessageDigest.isEqual(pending.state.getBytes(StandardCharsets.UTF_8),
                    state.getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("Connection response did not match this request");
            String code = p.get("code"), error = p.get("error");
            if ((code == null) == (error == null)) throw new IllegalArgumentException("Invalid connection response");
            if (code != null && !code.matches("gtc_[A-Za-z0-9_-]{20,256}"))
                throw new IllegalArgumentException("Invalid authorization code");
            if (error != null && !error.matches("[a-z_]{1,64}")) throw new IllegalArgumentException("Invalid authorization response");
            return new Callback(code, error);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid connection callback"); }
    }

    public static String form(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (out.length() != 0) out.append('&');
            out.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return out.toString();
    }
    private static String encode(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { throw new AssertionError(e); } }
    private static String decode(String s) { try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { throw new IllegalArgumentException("Invalid callback encoding"); } }
    private static byte[] digest(String s) { try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new AssertionError(e); } }
    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString(); }
    // java.util.Base64 is API 26; the application also supports Android 24 and 25.
    private static String base64Url(byte[] data) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder out = new StringBuilder(); int bits = 0, buffer = 0;
        for (byte b : data) { buffer = (buffer << 8) | (b & 255); bits += 8; while (bits >= 6) { bits -= 6; out.append(alphabet.charAt((buffer >>> bits) & 63)); } }
        if (bits > 0) out.append(alphabet.charAt((buffer << (6 - bits)) & 63));
        return out.toString();
    }
}
