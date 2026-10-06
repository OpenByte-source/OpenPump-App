package org.openpump;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * NO BOOK IS NAMED, QUOTED OR CITED BY LINE - NOT IN THE APP, NOT IN ITS SOURCE.
 *
 * The training material this app follows is third-party and copyrighted. The owner's rules
 * (2026-09-26 for the app's text, 2026-10-06 for the published source): the source is stated
 * once, as the publicly available guidance on PE; every user-visible string says "the
 * guidance"; and no file the project publishes names a book, uses a book's file name, points
 * at the private folder the books are kept in, or cites a line of one. A rule is paraphrased,
 * never quoted.
 *
 * Two scans:
 *   - every string literal (comments stripped) and every resource line of the app and the
 *     core may not say "book" at all, or carry a line citation;
 *   - every text file the project publishes - main, debug and test sources, resources, the
 *     build files and the CI workflows - may not, in a comment or anywhere else.
 * This file is the one exemption from the second scan: it has to spell the patterns out to
 * find them.
 */
class NoBookNamesTest {

    /** A book by any name, a book's file name, the private folder, a line citation. */
    private static final Pattern BOOK = Pattern.compile(
        "(?i)\\bbooks?\\b|\\bbook'?s\\b|(complete|beginner'?s'?) guides?"
        + "|\\bbd-[a-z]|\\bpe-complete|docs[/\\\\]source"
        + "|\\b(complete|girth|length|beginners?|big):\\d|\\blines? \\d+(-\\d+)? of\\b"
        + "|\\bguide §");

    /** Line numbers in the "~L10" / "L1000" form. Case-sensitive, and only read in code and
     *  build files: a drawable's path data is full of L12-style commands. */
    private static final Pattern LINE_REF = Pattern.compile("~\\s*L\\d|\\bL\\d{2,5}\\b");

    /** Does this text name, cite or point at a source? For the tests that check one line. */
    static boolean namesASource(String s) {
        return BOOK.matcher(s).find() || LINE_REF.matcher(s).find();
    }

    private static final Path[] ROOTS = {
        Paths.get("src/main"), Paths.get("../app/src/main"), Paths.get("../app/src/debug") };

    /** Everything published that is not documentation (docs/ and the *.md files have their
     *  own owner) - the working directory is core/. */
    private static final Path[] PUBLISHED = {
        Paths.get("src"), Paths.get("../app/src"), Paths.get("../.github"),
        Paths.get("../gradle"), Paths.get("build.gradle.kts"), Paths.get("../app/build.gradle.kts"),
        Paths.get("../build.gradle.kts"), Paths.get("../settings.gradle.kts"),
        Paths.get("../gradle.properties") };

    private static final Set<String> BINARY = new HashSet<String>(Arrays.asList(
        "png", "jpg", "jpeg", "webp", "gif", "ico", "jar", "jks", "keystore", "zip", "ttf", "otf"));

    private static final Set<String> CODE = new HashSet<String>(Arrays.asList(
        "java", "kt", "kts", "gradle", "properties", "toml", "yml", "yaml", "pro", "json", "txt"));

    /** Comments blanked, string and char literals kept - enough of a lexer for this. */
    static String stripComments(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (s.startsWith("//", i)) {
                int j = s.indexOf('\n', i);
                i = j < 0 ? n : j;
            } else if (s.startsWith("/*", i)) {
                int j = s.indexOf("*/", i + 2);
                String gone = s.substring(i, j < 0 ? n : j + 2);
                for (int k = 0; k < gone.length(); k++) if (gone.charAt(k) == '\n') b.append('\n');
                i = j < 0 ? n : j + 2;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) { if (s.charAt(j) == '\\') j++; j++; }
                b.append(s, i, Math.min(n, j + 1));
                i = j + 1;
            } else { b.append(c); i++; }
        }
        return b.toString();
    }

    static List<String> offences(String file, String src) {
        List<String> out = new ArrayList<String>();
        String code = stripComments(src);
        Matcher m = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"").matcher(code);
        while (m.find()) {
            String lit = m.group(1);
            if (lit.toLowerCase().contains("logbook")) continue;
            if (namesASource(lit)) out.add(file + ": \"" + lit + "\"");
        }
        return out;
    }

    /** Every offending line of one published file, comments included. */
    static List<String> fileOffences(String file, String text, boolean code) {
        List<String> out = new ArrayList<String>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (BOOK.matcher(line).find() || (code && LINE_REF.matcher(line).find()))
                out.add(file + ":" + (i + 1) + ": " + line.trim());
        }
        return out;
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase();
    }

    @Test void theCheckerCatchesABookName() {
        assertTrue(!offences("T", "String s = \"as the green book has it\";").isEmpty());
        assertTrue(!offences("T", "String s = \"leaves the book's plan\";").isEmpty());
        assertTrue(offences("T", "// the book says\nString s = \"the guidance\";").isEmpty());
        assertTrue(offences("T", "String s = \"a controller and a logbook\";").isEmpty());
    }

    @Test void theSourceCheckerCatchesNamesFoldersAndLineCitations() {
        String[] bad = {
            "// as the book says", "/* as the complete guide puts it */",
            " * see the beginners' guide", " * bd-example-2.0", " * pe-complete-x",
            " * a file under docs/source", " * (girth:100)", " * (complete:10-12)",
            " * same passage ~L10", " * (L1000)", " * line 10 of the guide",
            " * Both books ask for it" };
        for (String s : bad)
            assertTrue(!fileOffences("T.java", s, true).isEmpty(), "not caught: " + s);
        String[] fine = {
            " * the guidance caps a single set at 20 minutes, ideally 10 or less",
            " * Plan.L1, L1_GATE_NET_MIN and Level 3", " * a controller and a logbook",
            " * the bookkeeping is done", " * the camera's alignment guide",
            " * (Tau.java:15-20)", " * length 3 days and girth 3" };
        for (String s : fine)
            assertTrue(fileOffences("T.java", s, true).isEmpty(), "caught wrongly: " + s);
        // A drawable's path commands are not line citations.
        assertTrue(fileOffences("ic.xml", "android:pathData=\"M4,12L12,4L20,12\"", false)
            .isEmpty());
    }

    @Test void noUserVisibleStringNamesABook() throws Exception {
        List<String> bad = new ArrayList<String>();
        int files = 0;
        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    String name = p.toString();
                    if (!Files.isRegularFile(p)) continue;
                    String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    if (name.endsWith(".java")) {
                        files++;
                        bad.addAll(offences(name, src));
                    } else if (name.endsWith(".xml")) {
                        files++;
                        for (String line : src.split("\n"))
                            if (BOOK.matcher(line).find()) bad.add(name + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(files > 100, "sources not found from " + Paths.get("").toAbsolutePath());
        assertTrue(bad.isEmpty(), "book names in user-visible strings: " + bad);
    }

    /**
     * THE PUBLISHED SOURCE NAMES NO BOOK, POINTS AT NO PRIVATE FOLDER AND CITES NO LINE - in
     * comments too. A rule the code follows is paraphrased and called the guidance.
     */
    @Test void noPublishedFileNamesABookOrCitesALine() throws Exception {
        List<String> bad = new ArrayList<String>();
        int files = 0;
        for (Path root : PUBLISHED) {
            if (!Files.exists(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    if (p.getFileName().toString().equals("NoBookNamesTest.java")) continue;
                    String e = ext(p);
                    if (BINARY.contains(e)) continue;
                    byte[] raw = Files.readAllBytes(p);
                    boolean binary = false;
                    for (byte b : raw) if (b == 0) { binary = true; break; }
                    if (binary) continue;
                    files++;
                    bad.addAll(fileOffences(p.toString(),
                        new String(raw, StandardCharsets.UTF_8), CODE.contains(e)));
                }
            }
        }
        assertTrue(files > 400, "published files not found from "
            + Paths.get("").toAbsolutePath() + " (" + files + ")");
        assertTrue(bad.isEmpty(), bad.size() + " published lines name a book, the private "
            + "folder or a source line - paraphrase, say the guidance:\n" + String.join("\n", bad));
    }

    /** The 0.10.0 release notes too. The 0.9.0 lines are history and are left as written. */
    @Test void theNextReleaseNotesNameNoBook() throws Exception {
        String log = new String(Files.readAllBytes(Paths.get("../CHANGELOG.md")),
                                StandardCharsets.UTF_8);
        int from = log.indexOf("## [0.10.0]"), to = log.indexOf("## [0.9.0]");
        assertTrue(from >= 0 && to > from, "0.10.0 section not found");
        Matcher m = BOOK.matcher(log.substring(from, to));
        boolean found = m.find();
        assertTrue(!found, "a book named in the 0.10.0 notes: " + (found ? m.group() : ""));
    }
}
