package org.openpump;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The building rules, checked on every build. The core must stay free of Android so it
 * runs on any JVM (and, later, on other phone platforms). A class that needs Android
 * belongs in the app module.
 */
class ArchitectureTest {

    private static final Path CORE_MAIN = Paths.get("src/main/java");

    @Test
    void coreNeverImportsAndroid() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CORE_MAIN)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.startsWith("import android")) {
                        offenders.add(CORE_MAIN.relativize(f) + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
            "core/ must not import Android — move these classes to app/ instead:\n"
                + String.join("\n", offenders));
    }
}
