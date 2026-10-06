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
 * V1 - THE "LOSE THE NEXT STOPWORK" TEST HOOK IS NOT IN THE DOWNLOADABLE APP.
 *
 * The hook makes the simulator lose a StopWork on purpose, so the vent watch can be seen
 * refusing to confirm a stop the pump never received (VentArrivalTest drives the same thing
 * through SimPump#loseNextStops). It is a developer's tool, and a release APK must not carry
 * it, for the same reason it must not carry the diagnostic console. It is placed the same
 * way, and this pins that placement as ConsoleIsDebugOnlyTest pins the console's:
 *   1. the class is in app/src/debug/java, and not in app/src/main/java;
 *   2. no main source names it as a Java symbol, so the release build cannot compile
 *      against it and nothing adds a stub for it to main. PumpLink reaches it by a
 *      class-name string, behind BuildConfig.DEBUG;
 *   3. its flag file's name appears in no main source, so a release APK holds no trace of
 *      how to trigger it.
 */
class SimStopLossIsDebugOnlyTest {

    private static final Path SRC = Paths.get("../app/src");
    private static final String HOOK = "SimStopLoss";
    private static final String FLAG = "sim-lose-next-stop";

    @Test void theHookIsInTheDebugSourceSetOnly() {
        assertTrue(Files.isDirectory(SRC), "app sources not found from "
            + Paths.get("").toAbsolutePath());
        assertTrue(Files.isRegularFile(SRC.resolve("debug/java/org/openpump/" + HOOK + ".java")),
            "the lost-StopWork hook is expected in app/src/debug/java");
        assertFalse(Files.exists(SRC.resolve("main/java/org/openpump/" + HOOK + ".java")),
            "the lost-StopWork hook is in app/src/main/java - it would ship in the release APK");
    }

    @Test void noMainSourceNamesItOrItsFlag() throws IOException {
        List<String> symbol = new ArrayList<String>();
        List<String> flag = new ArrayList<String>();
        Pattern named = Pattern.compile("\\b" + HOOK + "\\b");
        try (Stream<Path> files = Files.walk(SRC.resolve("main/java"))) {
            for (Path p : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String src = read(p);
                if (named.matcher(ConsoleIsDebugOnlyTest.codeOnly(src)).find())
                    symbol.add(p.getFileName().toString());
                if (src.contains(FLAG)) flag.add(p.getFileName().toString());
            }
        }
        assertTrue(symbol.isEmpty(), "main code names the lost-StopWork hook as a symbol, which "
            + "only compiles if the hook ships in release: " + symbol);
        assertTrue(flag.isEmpty(), "main code carries the hook's flag file name, so a release "
            + "APK would say how to trigger it: " + flag);
    }

    @Test void theSimulatorItselfCannotLoseAStop() throws IOException {
        String sim = ConsoleIsDebugOnlyTest.codeOnly(new String(Files.readAllBytes(
            Paths.get("src/main/java/org/openpump/SimPump.java")), StandardCharsets.UTF_8));
        assertFalse(Pattern.compile("\\b(stopsToLose|loseNextStops?)\\b").matcher(sim).find(),
            "SimPump (core/src/main, shipped in every APK) has a way to ignore a StopWork again");
        SimPump p = new SimPump();
        for (int i = 0; i < Proto.SLOTS; i++) p.write(Proto.deleteSlot(0));
        p.write(Proto.addPreset(100, 20, 255, 20, 1));
        p.write(Proto.startSlot(0));
        while (p.pressureKpa() < 20.0) p.tick(SimPump.TELEM_PERIOD_MS);
        p.write(Proto.stop());
        assertTrue(p.isVenting(), "every StopWork the simulator receives vents it");
    }

    @Test void pumpLinkReachesItOnlyInADebugBuild() throws IOException {
        String link = ConsoleIsDebugOnlyTest.codeOnly(
            read(SRC.resolve("main/java/org/openpump/PumpLink.java")));
        assertTrue(link.contains("BuildConfig.DEBUG"),
            "PumpLink no longer asks BuildConfig.DEBUG before it reaches for the hook");
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
