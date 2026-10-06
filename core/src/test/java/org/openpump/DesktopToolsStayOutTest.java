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
 * RELEASE CHECKLIST B7: NO TEST OR DESKTOP CLASSES IN THE APK.
 *
 * The app compiles :core's main source set in, so anything in core/src/main/java ships in
 * the release APK. JournalCheck - a desktop tool that replays a session log from a file -
 * sat there and shipped. It now lives in core/src/test/java, where the journalCheck task and
 * JournalCheckTest already ran it from. A placement is what an innocent move undoes, so this
 * pins it, and the general rule behind it: no source the app ships has a desktop entry point
 * (a `static void main(`).
 */
class DesktopToolsStayOutTest {

    private static final Path CORE = Paths.get("src");
    private static final Path APP_MAIN = Paths.get("../app/src/main/java");
    private static final Pattern MAIN = Pattern.compile("\\bstatic\\s+void\\s+main\\s*\\(");

    @Test void journalCheckIsInTheTestSourceSetOnly() {
        assertTrue(Files.isDirectory(CORE.resolve("main/java")),
            "core sources not found from " + Paths.get("").toAbsolutePath());
        assertTrue(Files.isRegularFile(CORE.resolve("test/java/org/openpump/JournalCheck.java")),
            "JournalCheck is expected in core/src/test/java");
        assertFalse(Files.exists(CORE.resolve("main/java/org/openpump/JournalCheck.java")),
            "JournalCheck is back in core/src/main/java - it would ship in the release APK");
        assertFalse(Files.exists(APP_MAIN.resolve("org/openpump/JournalCheck.java")),
            "JournalCheck is in app/src/main/java - it would ship in the release APK");
    }

    @Test void nothingTheAppShipsHasADesktopEntryPoint() throws IOException {
        List<String> bad = new ArrayList<String>();
        scan(CORE.resolve("main/java"), bad);
        scan(APP_MAIN, bad);
        assertTrue(bad.isEmpty(), "a desktop entry point (static void main) in a source the "
            + "release APK compiles in - move it to a test source set: " + bad);
    }

    @Test void theRuleSeesWhatItIsFor() {
        assertTrue(MAIN.matcher("public static void main(String[] args) {").find());
        assertTrue(MAIN.matcher("static  void  main (String[] a)").find());
        assertFalse(MAIN.matcher("void mainScreen() { }").find());
        assertFalse(MAIN.matcher("static void remain(int x)").find());
    }

    private static void scan(Path dir, List<String> bad) throws IOException {
        assertTrue(Files.isDirectory(dir), dir + " not found from "
            + Paths.get("").toAbsolutePath());
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator)
                if (MAIN.matcher(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).find())
                    bad.add(p.getFileName().toString());
        }
    }
}
