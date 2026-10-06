package org.openpump;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * THE DEBUG LOG NEVER CARRIES A MEASUREMENT - checked in the app's source, where the
 * promise could be broken, rather than only in the redactor that is the last line of it.
 *
 * Two static rules over app/src/main/java:
 *   1. The measurement and photo screens do not write to the debug log at all: no log(),
 *      no journal. What they show reaches the log only through a tap label or a toast,
 *      which the journal masks on those screens (Redact#maskedScreen).
 *   2. Nowhere in the app does a log() or journal call carry a reading's figures or a
 *      photo's file: no `.len`, `.gir`, `.holdKpa` of a reading, no `.path`, no measLog.
 * A match is a statement that starts a log/journal call and, before its semicolon, names
 * one of those. Crude on purpose: it can only err towards refusing.
 */
class JournalPrivacyTest {

    private static final Path APP = Paths.get("../app/src/main/java/org/openpump");

    private static final String[] MEASUREMENT_FILES = {
        "MeasureScreens.java", "CameraScreen.java", "CompareScreen.java", "Photos.java",
        "CaptureProvider.java", "ExportProvider.java" };

    private static final Pattern LOG_CALL = Pattern.compile(
        "(?<![A-Za-z0-9_.])(?:a\\.)?(?:log|journalSnack)\\s*\\(|\\bjournal\\(\\)|\\bjh\\s*\\.\\s*j\\s*\\.");
    private static final Pattern FIGURES = Pattern.compile(
        "\\.(?:len|gir|holdKpa|path)\\b|measLog|photoFront|photoSide|photoTop");

    @Test void measurementScreensDoNotLog() throws IOException {
        List<String> bad = new ArrayList<String>();
        for (String f : MEASUREMENT_FILES) {
            Path p = APP.resolve(f);
            if (!Files.exists(p)) continue;
            String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            Matcher m = LOG_CALL.matcher(stripComments(src));
            while (m.find()) bad.add(f + ": " + snippet(stripComments(src), m.start()));
        }
        assertTrue(bad.isEmpty(), "measurement/photo code writes to the debug log:\n" + String.join("\n", bad));
    }

    @Test void noLogCallCarriesAReadingOrAPhoto() throws IOException {
        assertTrue(Files.isDirectory(APP), "app sources not found from " + Paths.get("").toAbsolutePath());
        List<String> bad = new ArrayList<String>();
        try (Stream<Path> files = Files.list(APP)) {
            for (Path p : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                // No exemption for the diagnostic console any more: it moved to
                // app/src/debug/java, outside this folder, when it left the release APK.
                String src = stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                Matcher m = LOG_CALL.matcher(src);
                while (m.find()) {
                    int end = src.indexOf(';', m.end());
                    if (end < 0) continue;
                    String stmt = src.substring(m.start(), end);
                    if (FIGURES.matcher(stmt).find()) bad.add(p.getFileName() + ": " + snippet(src, m.start()));
                }
            }
        }
        assertTrue(bad.isEmpty(), "a debug-log call carries measurement or photo data:\n" + String.join("\n", bad));
    }

    @Test void theRuleSeesWhatItIsFor() {
        // The checker's own teeth: the shapes it must refuse, and one it must pass.
        assertTrue(hits("a.log(\"saved \" + r.len + \" cm\");"));
        assertTrue(hits("log(\"photo at \" + p.path);"));
        assertTrue(hits("jh.j.app(t, \"n=\" + model.measLog.all.size());"));
        assertTrue(!hits("log(\"!! could not delete \" + path + \": \" + e);"));
    }

    private static boolean hits(String stmt) {
        Matcher m = LOG_CALL.matcher(stmt);
        if (!m.find()) return false;
        int end = stmt.indexOf(';', m.end());
        return FIGURES.matcher(stmt.substring(m.start(), end < 0 ? stmt.length() : end)).find();
    }

    /** Comments removed (strings kept), so prose about "the photo path" is not code. */
    static String stripComments(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' ) {
                int j = i + 1;
                while (j < n && s.charAt(j) != '"') { if (s.charAt(j) == '\\') j++; j++; }
                b.append(s, i, Math.min(n, j + 1));
                i = j + 1;
            } else if (c == '\'' ) {
                int j = i + 1;
                while (j < n && s.charAt(j) != '\'') { if (s.charAt(j) == '\\') j++; j++; }
                b.append(s, i, Math.min(n, j + 1));
                i = j + 1;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else { b.append(c); i++; }
        }
        return b.toString();
    }

    private static String snippet(String src, int at) {
        int e = src.indexOf('\n', at);
        return src.substring(at, e < 0 ? src.length() : e).trim();
    }
}
