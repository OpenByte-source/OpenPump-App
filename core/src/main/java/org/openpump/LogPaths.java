package org.openpump;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What {@link LogProvider} is allowed to serve, expressed as pure rules so the desktop
 * self-test can actually execute them (LogProvider itself imports android.* and is
 * therefore invisible to test.sh's pure suite).
 *
 * WHY THIS EXISTS — the gap it closes, stated precisely so nobody re-widens it:
 *
 * LogProvider's original containment check was
 *
 *     base = canonical(getExternalFilesDir(null)) + separator;
 *     if (!canonical(candidate).startsWith(base)) return null;
 *
 * which guards escaping UP and out of the app's external files directory ("..") but does
 * NOT guard descending IN. Photos live at getExternalFilesDir(DIRECTORY_PICTURES), i.e.
 * &lt;external&gt;/Pictures/, which IS inside that base. Android's
 * {@code Uri.getLastPathSegment()} returns the DECODED last segment, so a single URI
 * segment carrying an encoded slash — {@code Pictures%2Freading-1-front.jpg} — decodes to
 * the relative path {@code Pictures/reading-1-front.jpg}, resolves to a real photo file,
 * and passes a startsWith() containment test unchanged. See
 * {@link #isDirectChildOf} for the assertion that pins this, which deliberately checks
 * BOTH that the new rule rejects that path and that the old one accepted it.
 *
 * Whether that was ever REACHABLE by another app is a separate question from whether it
 * should be possible at all; see this task's report. The provider is unexported and a
 * recipient holds a grant for the exact Uri the app passed, so the answer is "no known
 * reachable path" — but the app makes a flat promise in its own copy ("the debug log
 * never contains measurements or photos", SessionActivity's Settings and baseline
 * screens and CameraScreen's review screen) and a promise like that must be enforced
 * where the bytes are SERVED, not only where the caller happens to build a well-behaved
 * Uri. (The DATA export — ExportProvider, its own authority — is the deliberate
 * opposite: it exists to carry the user's measurements, on the user's explicit Export
 * action, and never serves through this rule.)
 *
 * The rule is therefore deliberately narrow, and narrow in two independent ways, so
 * neither one alone is load-bearing:
 *
 *   1. {@link #isLogName} — the requested name must be a BARE log filename: no separator
 *      of any kind (so a decoded {@code Pictures/...} is rejected before any file is
 *      touched), and matching the exact shape the app's two log writers produce. A photo
 *      name (reading-&lt;id&gt;-&lt;view&gt;.jpg) fails this on both counts.
 *   2. {@link #isDirectChildOf} — the resolved canonical file must sit DIRECTLY in one of
 *      the two log directories, not merely somewhere beneath it.
 *
 * The two log writers, both of which this file's rule must keep accepting:
 *   - MainActivity#openLog (FROZEN — the diagnostic console, in debug builds only):
 *     getExternalFilesDir(null)/pumpdebug-yyyyMMdd-HHmmss.txt
 *   - SessionActivity#openLog:
 *     getExternalFilesDir(DIRECTORY_DOCUMENTS)/session-yyyyMMdd-HHmmss.txt
 *     — which is a SUBDIRECTORY of the provider's old single base, and therefore was not
 *     servable at all before this task: the provider only ever looked in the root.
 */
public final class LogPaths {

    private LogPaths() { }

    /** The timestamp shape both writers stamp into a log filename. */
    public static final String STAMP = "yyyyMMdd-HHmmss";

    /** SessionActivity's logs — the session app. */
    public static final String SESSION_PREFIX = "session-";
    /** MainActivity's logs — the frozen diagnostic console. Its openLog() builds this
     *  literal; this constant must keep matching it, and SelfTest pins the exact name
     *  that code produces rather than trusting the two to stay in step by inspection. */
    public static final String DIAG_PREFIX = "pumpdebug-";

    public static final String SUFFIX = ".txt";

    /** The filename SessionActivity#openLog writes, so the writer and the rule that
     *  admits it cannot drift apart — the one is defined in terms of the other. */
    public static String sessionLogName(long ts) {
        return SESSION_PREFIX + new SimpleDateFormat(STAMP, Locale.US).format(new Date(ts))
             + SUFFIX;
    }

    /**
     * A single filename and nothing else: no '/' or '\', no NUL, not "." or "..", not
     * empty. This is what makes a decoded {@code Pictures/reading-1-front.jpg} fail
     * before any File is constructed from it.
     *
     * Both separators are rejected regardless of platform. File.separatorChar is '/' on
     * Android, but a rule that consults it would quietly stop rejecting '\' there, and a
     * name is untrusted input from a Uri, not something the local filesystem chose.
     */
    public static boolean isBareFileName(String name) {
        if (name == null || name.length() == 0) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\0') return false;
        }
        return !name.equals(".") && !name.equals("..");
    }

    /**
     * True only for a name one of this app's two log writers actually produces. Anything
     * else — a photo, the model JSON, a name merely ENDING in .txt — is not a log and is
     * not this provider's to serve.
     */
    public static boolean isLogName(String name) {
        if (!isBareFileName(name)) return false;
        String stem;
        if (name.startsWith(SESSION_PREFIX))   stem = name.substring(SESSION_PREFIX.length());
        else if (name.startsWith(DIAG_PREFIX)) stem = name.substring(DIAG_PREFIX.length());
        else return false;
        if (!stem.endsWith(SUFFIX)) return false;
        return isStamp(stem.substring(0, stem.length() - SUFFIX.length()));
    }

    /** yyyyMMdd-HHmmss: eight digits, a hyphen, six digits. */
    private static boolean isStamp(String s) {
        if (s.length() != STAMP.length()) return false;      // 15
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i == 8) { if (c != '-') return false; }
            else if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * Containment done the way the provider needs it: `file` must be an IMMEDIATE child
     * of `dir`, not merely somewhere underneath it.
     *
     * Both arguments are canonical paths (the caller resolves symlinks and ".." before
     * asking); `sep` is the platform separator, passed in rather than read from
     * File.separatorChar so this stays pure and so the self-test can exercise the
     * POSIX form the phone actually uses while running on a JVM whose separator is '\'.
     *
     * The difference from the old rule is exactly one level of directory, and that one
     * level is the entire photo library.
     */
    public static boolean isDirectChildOf(String dirCanonical, String fileCanonical, char sep) {
        if (dirCanonical == null || fileCanonical == null) return false;
        if (dirCanonical.length() == 0) return false;
        String base = (dirCanonical.charAt(dirCanonical.length() - 1) == sep)
                    ? dirCanonical : dirCanonical + sep;
        if (!fileCanonical.startsWith(base)) return false;
        String rest = fileCanonical.substring(base.length());
        return rest.length() > 0 && rest.indexOf(sep) < 0;
    }
}
