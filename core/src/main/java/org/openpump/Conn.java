package org.openpump;

/**
 * The GUIDED-CONNECTION state machine (Task 18, Part One), made PURE (no `import android`)
 * so test.sh compiles it and SelfTest can assert on it — the same reason Nav, Summary and
 * A11y are pure. The Android side (SessionActivity#showConnect) owns the radar drawable,
 * the buttons and the actual link calls; every DECISION about which state a link condition
 * maps to, and what that state says and offers, lives here where a desktop JVM can check it.
 *
 * The input is the exact string PumpLink#onState reports (see PumpLink#state) plus two
 * booleans SessionActivity already tracks: whether the link says it is connected, and
 * whether any telemetry has actually arrived since it did. The second is what separates
 * CONNECTED from SILENT — "Connected" is a claim by the BLE stack, telemetry is evidence,
 * and BUILD.md already tells the user the two are not the same. This screen says it at the
 * moment it matters (a link that reports connected and then goes silent), not in a note on
 * a screen the user has left.
 *
 * The FACT that most confuses users — a connected BLE peripheral stops advertising, so only
 * one screen (this app, another app, or another phone) can hold the pump at a
 * time — is stated in the NOTHING_FOUND detail, at the point of failure, because "another
 * app or phone is holding it" is actionable and a note elsewhere is not.
 */
public final class Conn {
    private Conn() { }

    /* ---- the states, one per thing that can actually happen ------------------ */

    public static final int
        SEARCHING     = 1,   // scanning — the radar; motion communicates ongoing work
        CONNECTING    = 2,   // found, now connecting/discovering — still working
        CONNECTED     = 3,   // connected AND telemetry is flowing — confirm and leave
        SILENT        = 4,   // connected but NO telemetry — "Connected" is not evidence
        BT_OFF        = 5,   // the adapter is off — offer to turn it on
        PERM_DENIED   = 6,   // scanning was refused — explain WHICH permission and why
        NOTHING_FOUND = 7,   // scan timed out — the honest checklist
        SCAN_ERROR    = 8,   // the scan itself failed, or no usable service — retry
        NO_HARDWARE   = 9,   // no BLE scanner/adapter on this device at all
        OFFLINE       = 10,  // idle / disconnected — nothing tried yet, offer to search
        NAMING        = 11;  // scan stopped on an unknown device — the app is asking (T9)

    /* ---- what a state OFFERS, as an action code the Activity switches on ------ */

    public static final int
        ACT_NONE        = 0,
        ACT_SEARCH      = 1,   // (re)start a scan: link.close(); link.start()
        ACT_BT_SETTINGS = 2,   // send the user to enable Bluetooth
        ACT_APP_SETTINGS = 3,  // open this app's settings to grant a permission by hand
        ACT_GRANT       = 4,   // request the runtime permission again
        ACT_DISMISS     = 5;   // "Not now" — leave the connection screen

    /* ---- tone, mapped to Ui colours by the Activity (never a colour here) ----- */

    public static final int TONE_LIVE = 0, TONE_GOOD = 1, TONE_CRIT = 2;

    public static final class State {
        public int id;
        public int tone;
        public boolean radar;          // whether the searching radar animation is shown
        public String headline;
        public String detail;
        public String primary;         // primary button label, "" for none
        public int primaryAction;
        public String secondary;       // secondary button label, "" for none
        public int secondaryAction;

        State(int id, int tone, boolean radar, String headline, String detail,
              String primary, int primaryAction, String secondary, int secondaryAction) {
            this.id = id; this.tone = tone; this.radar = radar;
            this.headline = headline; this.detail = detail;
            this.primary = primary; this.primaryAction = primaryAction;
            this.secondary = secondary; this.secondaryAction = secondaryAction;
        }
    }

    /** The one fact worth stating exactly where it bites — see the class doc. */
    public static final String ONE_HOLDER =
        "a connected pump stops advertising, so only one screen can hold it — this app, "
        + "another app, or another phone.";

    /**
     * Map a link condition to the state the connection screen should show.
     *
     * `raw` is PumpLink#onState's string verbatim; `connected` is its companion boolean;
     * `telemetryArrived` is whether a real sample has been seen since this connection came
     * up (SessionActivity tracks it as lastSampleAt > connectedAt). Matching is by PREFIX,
     * without the trailing ellipsis PumpLink appends, so a copy tweak to "Scanning…" cannot
     * silently drop it into the default branch.
     */
    public static State classify(String raw, boolean connected, boolean telemetryArrived) {
        // Connected is decided FIRST and on the flag, not the string: a link can report
        // "Connected" and then fall silent, and that silence is the whole point of SILENT.
        if (connected) {
            if (telemetryArrived)
                return new State(CONNECTED, TONE_GOOD, false,
                    "Pump connected",
                    "Telemetry is arriving — the pump is talking to this screen.",
                    "Done", ACT_DISMISS, "", ACT_NONE);
            return new State(SILENT, TONE_CRIT, false,
                "Connected — but nothing is coming through",
                "The link reports connected, yet no telemetry has arrived. “Connected” "
                + "is the phone's BLE stack accepting the link, not proof the pump is sending. "
                + "Reconnect; if it stays silent, power-cycle the pump.",
                "Reconnect", ACT_SEARCH, "Not now", ACT_DISMISS);
        }

        String s = raw == null ? "" : raw.trim();

        if (s.startsWith("Bluetooth off"))
            return new State(BT_OFF, TONE_CRIT, false,
                "Bluetooth is off",
                "Finding your pump needs Bluetooth switched on. Turn it on, then search again.",
                "Turn on Bluetooth", ACT_BT_SETTINGS, "Not now", ACT_DISMISS);

        if (s.startsWith("permission"))
            return new State(PERM_DENIED, TONE_CRIT, false,
                "Permission needed to search",
                "Scanning for a Bluetooth device needs the Nearby-devices / Location "
                + "permission — Android requires it for ANY BLE scan on this Android version, "
                + "which surprises people. Nothing about where you are is used or stored. Grant "
                + "it to search; if the prompt no longer appears, enable it in app settings.",
                "Grant permission", ACT_GRANT, "Open app settings", ACT_APP_SETTINGS);

        if (s.startsWith("not found"))
            return new State(NOTHING_FOUND, TONE_CRIT, false,
                "Nothing found yet",
                "Check the pump is powered on and in range, and that it is not already "
                + "connected to another app or phone — " + ONE_HOLDER,
                "Search again", ACT_SEARCH, "Not now", ACT_DISMISS);

        if (s.startsWith("no scanner"))
            return new State(NO_HARDWARE, TONE_CRIT, false,
                "No Bluetooth on this device",
                "This phone reports no Bluetooth LE scanner, so it cannot search for the pump.",
                "", ACT_NONE, "Not now", ACT_DISMISS);

        // A raw scan failure code, or a device that connected but did not expose the pump's
        // service — both are "try again", worded so the user is not left on a dead radar.
        if (s.startsWith("scan failed") || s.startsWith("no FFF0"))
            return new State(SCAN_ERROR, TONE_CRIT, false,
                "The search hit an error",
                "The Bluetooth scan reported a problem (" + s + "). Searching again usually "
                + "clears it; if it does not, toggle Bluetooth off and on.",
                "Search again", ACT_SEARCH, "Not now", ACT_DISMISS);

        // T9 — the scan stopped on a device that matched the pump's name filter but not
        // any REMEMBERED address, and the app is asking whether to name/remember it or
        // connect just this once. The real choice is SessionActivity's own AlertDialog
        // (PumpLink#Listener.onUnknownPump) sitting on top of this card, not a button the
        // card itself draws — PRIMARY is left empty so nothing here duplicates or races
        // the dialog's own two buttons. SECONDARY still offers "Not now" so a user who
        // dismisses the dialog without choosing (back press, tap outside) is not left on a
        // dead card with no way off it, the same guarantee every other state on this
        // screen already gives. No radar: the scan itself has already stopped, waiting on
        // the dialog rather than still searching, and a spinning radar behind a decision
        // nobody has made yet would be exactly the kind of live-state-not-matching-the-
        // label bug T7's HOLD/RESUME fix exists to avoid.
        if (s.startsWith("Pump found"))
            return new State(NAMING, TONE_LIVE, false,
                "Pump found",
                "This pump has not been used with this app before — choose whether to "
                + "remember it.",
                "", ACT_NONE, "Not now", ACT_DISMISS);

        if (s.startsWith("Scanning"))
            return new State(SEARCHING, TONE_LIVE, true,
                "Looking for your pump",
                "Make sure it is switched on and nearby.",
                "", ACT_NONE, "Not now", ACT_DISMISS);

        if (s.startsWith("Connecting") || s.startsWith("Discovering"))
            return new State(CONNECTING, TONE_LIVE, true,
                "Pump found — connecting",
                "Setting up the link. This takes a moment.",
                "", ACT_NONE, "Not now", ACT_DISMISS);

        // "Disconnected", "Starting…", "" and anything else unrecognised: idle, offer to
        // search rather than leaving the user staring at a screen with nothing to do.
        return new State(OFFLINE, TONE_LIVE, false,
            "Not connected",
            "The pump is not connected to this screen. Search for it when it is powered on "
            + "and nearby.",
            "Search for the pump", ACT_SEARCH, "Not now", ACT_DISMISS);
    }

    /* ---- the top bar's status chip ------------------------------------------- */

    /**
     * THE WORDS ON THE TOP BAR'S STATUS CHIP.
     *
     * The bar used to print PumpLink's raw state with the address beside it ("● Simulated
     * pump / simulated-pump"): the same thing said twice, on two lines, next to a large
     * Disconnect button on every screen. The chip says ONE thing: which pump is connected,
     * or in a word or two why none is. The address and Disconnect move into the sheet the
     * chip opens ({@link #sheetTitle}, {@link #sheetCaption}).
     *
     * A REMEMBERED PUMP IS CALLED BY ITS NAME, never its MAC: `name` is the name it was
     * remembered under, null or blank for one connected without being remembered ("Connect
     * once"), shown through {@link PumpMatch#displayName} so a never-renamed default reads as
     * the model name. A simulated link is called what it is, whatever the address says.
     *
     * The not-connected words come from {@link #classify}, so the chip and the connection
     * screen can never disagree about what state the link is in.
     */
    public static String chipLabel(String raw, boolean connected, boolean simulated,
                                   String name) {
        if (connected) {
            if (simulated) return "Simulated pump";
            if (name != null && name.trim().length() > 0) return PumpMatch.displayName(name.trim());
            return "Connected";
        }
        switch (classify(raw, false, false).id) {
            case SEARCHING:     return "Scanning…";
            case CONNECTING:    return "Connecting…";
            case NAMING:        return "Pump found";
            case BT_OFF:        return "Bluetooth off";
            case PERM_DENIED:   return "Needs permission";
            case NOTHING_FOUND: return "Not found";
            case NO_HARDWARE:   return "No Bluetooth";
            case SCAN_ERROR:    return "Search failed";
            default:            return "Not connected";
        }
    }

    /** What the chip is called aloud: which pump, then what a tap does - the sheet with
     *  Disconnect inside when a pump is connected, the guided connection otherwise. */
    public static String chipSaid(String label, boolean connected) {
        return "Pump: " + A11y.collapse(label)
            + (connected ? ". Opens the connection" : ". Connects to the pump");
    }

    /** The sheet's heading: the pump by name, the simulator by what it is, and an
     *  unremembered pump plainly - its address is the caption underneath. */
    public static String sheetTitle(boolean simulated, String name) {
        if (simulated) return "Simulated pump";
        if (name != null && name.trim().length() > 0) return PumpMatch.displayName(name.trim());
        return "Your pump";
    }

    /** The sheet's one line under the heading. The simulator's is what it means for the
     *  runs (its address is a placeholder, not information); a real pump's is where the
     *  address went when it left the bar, with the link's own state beside it. */
    public static String sheetCaption(boolean simulated, String address, String raw) {
        if (simulated)
            return "Runs play out against a model of the pump, and every one is marked "
                + "SIMULATED.";
        String st = raw == null ? "" : raw.trim();
        if (address == null || address.trim().length() == 0) return st;
        return st.length() == 0 ? address.trim() : address.trim() + "  ·  " + st;
    }
}
