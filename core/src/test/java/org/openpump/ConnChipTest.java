package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The top bar's status chip and the sheet it opens: which words each link state earns.
 * The chip replaced a two-line status and a Disconnect button, so what it must never do is
 * say the address, or claim a connection the link has not reported.
 */
class ConnChipTest {

    private static final String MAC = "C4:7F:51:0A:22:9E";

    @Test
    void theSimulatorIsCalledWhatItIsNeverItsAddress() {
        String l = Conn.chipLabel("Simulated pump", true, true, null);
        assertEquals("Simulated pump", l);
        assertFalse(l.contains(PumpLinkAddress.SIM), "the placeholder address stays off the bar");
    }

    @Test
    void aRememberedPumpIsCalledByItsNameNotItsMac() {
        assertEquals("Epic Hydro PE Pump", Conn.chipLabel("Connected", true, false, "  Epic Hydro PE Pump "));
        assertFalse(Conn.chipLabel("Connected", true, false, "Epic Hydro PE Pump").contains(":"));
    }

    @Test
    void anUnrememberedPumpSaysConnected() {
        assertEquals("Connected", Conn.chipLabel("Connected", true, false, null));
        assertEquals("Connected", Conn.chipLabel("Connected", true, false, "   "));
    }

    @Test
    void notConnectedFollowsTheConnectionScreensReading() {
        assertEquals("Not connected", Conn.chipLabel("Disconnected", false, false, null));
        assertEquals("Not connected", Conn.chipLabel("Starting…", false, false, null));
        assertEquals("Not connected", Conn.chipLabel(null, false, false, null));
        assertEquals("Scanning…", Conn.chipLabel("Scanning…", false, false, null));
        assertEquals("Connecting…", Conn.chipLabel("Connecting…", false, false, null));
        assertEquals("Connecting…", Conn.chipLabel("Discovering…", false, false, null));
        assertEquals("Pump found", Conn.chipLabel("Pump found — name it?", false, false, null));
        assertEquals("Bluetooth off", Conn.chipLabel("Bluetooth off", false, false, null));
        assertEquals("Needs permission", Conn.chipLabel("permission denied", false, false, null));
        assertEquals("Not found", Conn.chipLabel("not found", false, false, null));
        assertEquals("No Bluetooth", Conn.chipLabel("no scanner", false, false, null));
        assertEquals("Search failed", Conn.chipLabel("scan failed 2", false, false, null));
        assertEquals("Search failed", Conn.chipLabel("no FFF0", false, false, null));
    }

    @Test
    void aNameIsNeverShownForALinkThatIsNotUp() {
        // The remembered name belongs to a CONNECTED pump; while it is still being found the
        // chip says what is happening, not whom it hopes to reach.
        assertEquals("Connecting…", Conn.chipLabel("Connecting…", false, false, "Epic"));
        // The simulator armed but not yet ready is not "Simulated pump" either.
        assertEquals("Not connected", Conn.chipLabel("Simulator armed", false, true, null));
    }

    @Test
    void theChipSaysWhoAndWhatATapDoes() {
        assertEquals("Pump: Simulated pump. Opens the connection",
                     Conn.chipSaid("Simulated pump", true));
        assertEquals("Pump: Not connected. Connects to the pump",
                     Conn.chipSaid("Not connected", false));
    }

    @Test
    void theSheetCarriesWhatLeftTheBar() {
        assertEquals("Simulated pump", Conn.sheetTitle(true, null));
        assertEquals("Epic Hydro PE Pump", Conn.sheetTitle(false, " Epic Hydro PE Pump"));
        assertEquals("Your pump", Conn.sheetTitle(false, ""));

        String sim = Conn.sheetCaption(true, PumpLinkAddress.SIM, "Simulated pump");
        assertTrue(sim.contains("SIMULATED"), "the simulator's caption says runs are marked");
        assertFalse(sim.contains(PumpLinkAddress.SIM));

        String real = Conn.sheetCaption(false, MAC, "Connected");
        assertTrue(real.startsWith(MAC), "a real pump's address moved into the sheet");
        assertTrue(real.endsWith("Connected"), "with the link's own state beside it");
        assertEquals("Connected", Conn.sheetCaption(false, null, "Connected"));
        assertEquals(MAC, Conn.sheetCaption(false, MAC, null));
    }

    /** PumpLink is Android; its simulated address is a constant worth pinning here. */
    private static final class PumpLinkAddress {
        static final String SIM = "simulated-pump";
    }
}
