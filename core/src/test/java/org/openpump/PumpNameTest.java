package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * A person reads "Epic Hydro PE Pump", never the name the pump broadcasts. Earlier versions
 * saved the broadcast name (ZD21_PUMP) as a remembered pump's default label; the display
 * rule shows that default as the model name without rewriting anyone's saved data, and a
 * name the user typed still shows exactly as typed.
 */
class PumpNameTest {

    private static final String MODEL = "Epic Hydro PE Pump";

    @Test
    void theModelNameIsTheRealOne() {
        assertEquals(MODEL, PumpMatch.MODEL_NAME);
    }

    @Test
    void aNeverRenamedDefaultReadsAsTheModelName() {
        assertEquals(MODEL, PumpMatch.displayName("ZD21_PUMP"));
        assertEquals(MODEL, PumpMatch.displayName("ZD21_PUMP_OTA"));
        // The scan filter ignores case, so the saved default was whatever case the pump sent.
        assertEquals(MODEL, PumpMatch.displayName("zd21_pump"));
        assertEquals(MODEL, PumpMatch.displayName(" ZD21_PUMP "));
    }

    @Test
    void aTypedNameShowsExactlyAsTyped() {
        assertEquals("Garage pump", PumpMatch.displayName("Garage pump"));
        assertEquals("My pump", PumpMatch.displayName("My pump"));
        assertEquals(MODEL, PumpMatch.displayName(MODEL));
        // Only the WHOLE name is the default: anything added to it was typed.
        assertEquals("ZD21_PUMP 2", PumpMatch.displayName("ZD21_PUMP 2"));
        assertEquals("my ZD21_PUMP", PumpMatch.displayName("my ZD21_PUMP"));
        assertEquals("ZD21", PumpMatch.displayName("ZD21"));
        assertEquals("", PumpMatch.displayName(""));
        assertNull(PumpMatch.displayName(null));
    }

    @Test
    void aNewPumpIsOfferedItsModelName() {
        assertEquals(MODEL, PumpMatch.defaultName("ZD21_PUMP"));
        assertEquals(MODEL, PumpMatch.defaultName("ZD21_PUMP_OTA "));
        assertEquals("My pump", PumpMatch.defaultName(null));
        assertEquals("My pump", PumpMatch.defaultName("   "));
        assertEquals("Other pump", PumpMatch.defaultName(" Other pump "));
    }

    @Test
    void theChipAndTheSheetUseTheRule() {
        assertEquals(MODEL, Conn.chipLabel("Connected", true, false, "ZD21_PUMP"));
        assertEquals(MODEL, Conn.sheetTitle(false, "ZD21_PUMP_OTA"));
        assertEquals("Garage pump", Conn.chipLabel("Connected", true, false, " Garage pump "));
        assertEquals("Garage pump", Conn.sheetTitle(false, "Garage pump"));
    }

    /** The scan filter lives in the app and keeps its own literals, exactly as broadcast.
     *  If they and the display rule's copy ever differ, a default saved under the new name
     *  would show raw. */
    @Test
    void theScanFilterAndTheDisplayRuleKnowTheSameNames() throws IOException {
        Path link = Paths.get("../app/src/main/java/org/openpump/PumpLink.java");
        assertTrue(Files.exists(link), "PumpLink.java not found from " + Paths.get("").toAbsolutePath());
        String src = new String(Files.readAllBytes(link), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("String\\[\\]\\s+NAMES\\s*=\\s*\\{([^}]*)\\}").matcher(src);
        assertTrue(m.find(), "PumpLink's NAMES filter not found");
        Matcher lit = Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));
        StringBuilder found = new StringBuilder();
        while (lit.find()) found.append(found.length() == 0 ? "" : ",").append(lit.group(1));
        assertEquals(String.join(",", PumpMatch.ADVERTISED_NAMES), found.toString());
    }
}
