package org.openpump;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * THE DIAGNOSTIC CONSOLE IS NOT IN THE DOWNLOADABLE APP.
 *
 * The owner's decision (docs/design/driver-seam.md, question 9). MainActivity keeps its own
 * Bluetooth connection and sends raw frames outside every safety check, so it is left out of
 * the release APK and kept in the debug builds developers make themselves. What decides that
 * is where the files sit: Gradle puts app/src/debug into debug builds only. A placement is
 * exactly what an innocent move undoes, so this pins it:
 *   1. the class is in app/src/debug/java, and not in app/src/main/java;
 *   2. its activity is declared in the debug manifest, and not in the main one;
 *   3. no main source names it as a Java symbol. Main code reaches it by a class-name
 *      string, behind BuildConfig.DEBUG (SessionActivity#hasConsole), because a symbol would
 *      not compile in release - and the easy "fix" for that, a stub class in main, would put
 *      a console door back into the release APK.
 * The release APK itself was checked by listing its classes when the move landed; see
 * docs/release-checklist.md.
 */
class ConsoleIsDebugOnlyTest {

    private static final Path SRC = Paths.get("../app/src");
    private static final String CONSOLE = "MainActivity";

    @Test void theConsoleClassIsInTheDebugSourceSetOnly() {
        assertTrue(Files.isDirectory(SRC), "app sources not found from "
            + Paths.get("").toAbsolutePath());
        assertTrue(Files.isRegularFile(SRC.resolve("debug/java/org/openpump/" + CONSOLE + ".java")),
            "the diagnostic console is expected in app/src/debug/java");
        assertFalse(Files.exists(SRC.resolve("main/java/org/openpump/" + CONSOLE + ".java")),
            "the diagnostic console is back in app/src/main/java - it would ship in the "
            + "release APK");
    }

    @Test void onlyTheDebugManifestDeclaresIt() throws IOException {
        String main = withoutXmlComments(read(SRC.resolve("main/AndroidManifest.xml")));
        String debug = withoutXmlComments(read(SRC.resolve("debug/AndroidManifest.xml")));
        assertFalse(main.contains(CONSOLE),
            "the main manifest declares the diagnostic console - it would ship in the release APK");
        assertTrue(debug.contains("android:name=\"org.openpump." + CONSOLE + "\""),
            "the debug manifest no longer declares the diagnostic console");
    }

    @Test void noMainSourceNamesTheConsoleAsASymbol() throws IOException {
        List<String> bad = new ArrayList<String>();
        Pattern symbol = Pattern.compile("\\b" + CONSOLE + "\\b");
        try (Stream<Path> files = Files.walk(SRC.resolve("main/java"))) {
            for (Path p : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                if (symbol.matcher(codeOnly(read(p))).find()) bad.add(p.getFileName().toString());
            }
        }
        assertTrue(bad.isEmpty(), "main code names the diagnostic console as a symbol, which "
            + "only compiles if the console ships in release: " + bad);
    }

    @Test void theRuleSeesWhatItIsFor() {
        Pattern symbol = Pattern.compile("\\b" + CONSOLE + "\\b");
        assertTrue(symbol.matcher(codeOnly("startActivity(new Intent(this, MainActivity.class));")).find());
        assertTrue(symbol.matcher(codeOnly("MainActivity m = null;")).find());
        assertFalse(symbol.matcher(codeOnly("String c = \"org.openpump.MainActivity\";")).find());
        assertFalse(symbol.matcher(codeOnly("// MainActivity is the console\n/* MainActivity */")).find());
        assertFalse(symbol.matcher(codeOnly("char q = '\"'; String s = \"MainActivity\";")).find());
        assertFalse(withoutXmlComments("<!-- MainActivity lives in src/debug -->").contains(CONSOLE));
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String withoutXmlComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    /** Java source with its comments, string literals and char literals removed. */
    static String codeOnly(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) { if (s.charAt(j) == '\\') j++; j++; }
                b.append(' ');
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
}
