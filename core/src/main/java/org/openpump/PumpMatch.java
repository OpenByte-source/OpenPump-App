package org.openpump;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picking the pump to auto-connect to out of ONE scan's worth of results (T9, named pump
 * memory) — kept PURE (no `import android`) so test.sh compiles it and SelfTest can assert
 * on it directly, on the desktop JVM, with no BLE stack or scan callback in the loop.
 * {@link PumpLink} owns the actual scan and feeds what it collects through {@link #pickKnown}
 * and {@link #unknowns}, when to stop collecting early through {@link #canExitEarly}, and
 * whether an unknown device gets the interactive prompt at all through {@link
 * #shouldPromptUnknown} — every DECISION lives here, the same split {@link Conn} keeps
 * between "what a link condition maps to" (pure) and "what draws it" (PumpLink/
 * SessionActivity).
 */
public final class PumpMatch {
    private PumpMatch() { }

    /**
     * One device seen during a scan window: its address, its most recently reported RSSI
     * (dBm — negative, and CLOSER TO ZERO is the stronger signal, e.g. -50 beats -80), and
     * the name it advertised under (kept only so a naming prompt can show it — never
     * compared).
     */
    public static final class Seen {
        public final String address;
        public final String name;
        public final int rssi;
        public Seen(String address, String name, int rssi) {
            this.address = address; this.name = name; this.rssi = rssi;
        }
    }

    /**
     * FIX ROUND (review Finding 3, CORRECTED after a second review pass) — whether the
     * collection window can end EARLY given what has been seen SO FAR. Replaces an earlier
     * fixed-dBm-threshold rule ("strong enough, stop waiting"), which made a WEAK-signal
     * single known pump — a phone across the room mid-reconnect, exactly the case fast
     * reconnect matters most for — sit out the full window for no reason: there was nothing
     * else to compare it against, only a number it had not cleared.
     *
     * THE CORRECTION: the first version of this fix returned true the moment exactly one
     * known address had been SEEN so far this scan, with no regard for how many are
     * actually REMEMBERED (`knownAddresses.size()`). Traced precisely on re-review: this
     * class's own dispatch pipeline has no batching and no delay between a sighting and
     * {@link PumpLink#decide} — the first known pump sighted always fires `decide()` before
     * a second could ever be recorded, deterministically, for ANYONE with 2+ remembered
     * pumps both broadcasting at scan start. That made {@link #pickKnown}'s RSSI comparison
     * — correctly built and unit-tested in isolation — UNREACHABLE from the real scan flow,
     * silently defeating T9's own "strongest known auto-picked" spec line for exactly the
     * "household with two units" case the decision text names.
     *
     * THE FIX: gate on how many pumps are remembered in total, not how many have been seen
     * so far. With AT MOST ONE pump remembered, there is only ever one candidate this scan
     * could possibly disambiguate between, so seeing it is exiting early on purpose — the
     * latency fix this method exists for. With TWO OR MORE remembered, this never exits
     * early at all: the window always runs to completion, so {@link #pickKnown} gets every
     * one of them that showed up in that time to actually compare RSSI across, exactly as
     * it did before this task's window/early-exit machinery existed.
     */
    public static boolean canExitEarly(List<Seen> seen, Set<String> knownAddresses) {
        if (seen == null || knownAddresses == null) return false;
        if (knownAddresses.size() > 1) return false;   // 2+ remembered: always run the full window
        for (int i = 0; i < seen.size(); i++) {
            Seen s = seen.get(i);
            if (s != null && s.address != null && knownAddresses.contains(s.address)) return true;
        }
        return false;
    }

    /**
     * FIX ROUND (review Findings 1 & 2) — whether a device that matched the name filter but
     * not any remembered address should get the interactive naming prompt at all, or should
     * instead be connected to and remembered SILENTLY. False in either of two cases, both
     * carved out so the naming prompt earns its one interruption rather than spending it
     * somewhere friction was never supposed to exist:
     *
     * <ul>
     * <li>`knownAddresses` is EMPTY — nothing has ever been remembered, so this is the very
     * first pump this install has ever seen. Prompting here would be new friction a fresh
     * install never had before T9 (the pre-T9 code connected to the first match with zero
     * prompt); only the SECOND distinct device onward is a real choice between "this one" and
     * "the one I already use".</li>
     * <li>`allowNamingPrompt` is false — the caller (PumpLink, told by SessionActivity via
     * {@link PumpLink#setAllowNamingPrompt}) is scanning while the pump may still be under
     * command or a stop is outstanding and unconfirmed. Reconnecting in that window is the
     * one moment fast reconnect matters most, the same priority Stage D Task 5's reconnect
     * work already established, and an unasked-for AlertDialog is not "fast".</li>
     * </ul>
     *
     * Either way the caller still connects and still remembers the pump (silently) — this
     * only decides whether a DIALOG is shown, never whether the connection or the memory
     * happens.
     */
    public static boolean shouldPromptUnknown(boolean allowNamingPrompt, Set<String> knownAddresses) {
        return allowNamingPrompt && knownAddresses != null && !knownAddresses.isEmpty();
    }

    /**
     * Among everything seen this window, the strongest-signal device whose address is on
     * the remembered list — RSSI compared numerically (dBm is negative, so a strictly
     * GREATER value is the stronger signal). Ties — two remembered pumps reporting the
     * identical dBm figure — keep whichever appears FIRST in `seen`, so the result never
     * depends on how a caller's map happens to iterate; {@link PumpLink} feeds this in
     * first-sighted order for exactly that reason. Returns null when nothing seen matches a
     * known address at all — the caller's cue to fall through to {@link #unknowns}.
     */
    public static Seen pickKnown(List<Seen> seen, Set<String> knownAddresses) {
        if (seen == null || knownAddresses == null) return null;
        Seen best = null;
        for (int i = 0; i < seen.size(); i++) {
            Seen s = seen.get(i);
            if (s == null || s.address == null) continue;
            if (!knownAddresses.contains(s.address)) continue;
            if (best == null || s.rssi > best.rssi) best = s;
        }
        return best;
    }

    /**
     * Every device seen this window whose address is NOT on the remembered list, one entry
     * per address (a device re-advertises many times a second; the SAME stranger must not
     * count as several). Order follows `seen`'s own order — first-sighted first — so a
     * caller picking "the one to ask about" via {@link #strongest} is choosing among a
     * stable list, not one shuffled by a HashMap's iteration.
     */
    public static List<Seen> unknowns(List<Seen> seen, Set<String> knownAddresses) {
        List<Seen> out = new ArrayList<Seen>();
        if (seen == null) return out;
        Set<String> already = new HashSet<String>();
        for (int i = 0; i < seen.size(); i++) {
            Seen s = seen.get(i);
            if (s == null || s.address == null) continue;
            if (knownAddresses != null && knownAddresses.contains(s.address)) continue;
            if (already.add(s.address)) out.add(s);
        }
        return out;
    }

    /**
     * The strongest signal in a list — used to pick WHICH unknown device gets the one-time
     * naming prompt when more than one stranger answers the same scan (almost never in
     * practice, but two ZD21 pumps (Epic Hydro PE Pumps) in one room is not impossible).
     * Null in, null out; an empty list also answers null. Ties keep the first, for the
     * same reason {@link #pickKnown} does.
     */
    public static Seen strongest(List<Seen> seen) {
        if (seen == null) return null;
        Seen best = null;
        for (int i = 0; i < seen.size(); i++) {
            Seen s = seen.get(i);
            if (s == null) continue;
            if (best == null || s.rssi > best.rssi) best = s;
        }
        return best;
    }

    /** UPPERCASE, trimmed — the one canonical form an address is compared and stored in
     *  throughout T9, on both the {@link Model#knownPumps} side and here, so a lookup can
     *  never miss a match on case or stray whitespace alone. Every real
     *  BluetoothDevice#getAddress() already comes back upper-case; this only guards the
     *  theoretical case a stack does not, and keeps this pure class the single place that
     *  says so rather than repeating the rule at every call site. */
    public static String norm(String address) {
        return address == null ? "" : address.trim().toUpperCase(Locale.US);
    }

    /* ------------------------------------------------------------- the pump's name */

    /** The pump's model name: what a person reads, wherever the app names the pump. */
    public static final String MODEL_NAME = "Epic Hydro PE Pump";

    /**
     * What the Epic Hydro PE Pump broadcasts over Bluetooth: ZD21_PUMP, and ZD21_PUMP_OTA,
     * a second name the pump may advertise. OpenPump accepts both and treats them the same;
     * what makes a pump advertise the second is unknown (docs/protocols/zd21.md). PumpLink's
     * scan filter keeps its own copy of these two literals, exactly as
     * broadcast; PumpNameTest reads PumpLink's source and fails if the two ever differ.
     */
    static final String[] ADVERTISED_NAMES = {"ZD21_PUMP", "ZD21_PUMP_OTA"};

    /**
     * THE ONE DISPLAY RULE for a remembered pump's saved name. Earlier versions saved the
     * pump's broadcast name as its default label, so a saved name that is exactly one of
     * {@link #ADVERTISED_NAMES} is that default, never renamed, and reads as the model name.
     * Anything else is a name the user typed and shows exactly as typed. Case and outer
     * spaces are ignored in the comparison because the scan filter ignores case, so the
     * default was whatever case the pump sent. Nothing is rewritten in the saved data: old
     * saves keep their value and this covers them. Null stays null; callers keep their own
     * words for "no name".
     */
    public static String displayName(String saved) {
        if (saved == null) return null;
        return isAdvertisedName(saved) ? MODEL_NAME : saved;
    }

    /**
     * The label a newly found pump is offered, and saved under when it is remembered without
     * asking: its model name rather than its broadcast name, so new saves are clean. Blank
     * gets the same generic label {@link Model#rememberPump} falls back to.
     */
    public static String defaultName(String advertised) {
        String n = advertised == null ? "" : advertised.trim();
        return n.length() == 0 ? "My pump" : displayName(n);
    }

    static boolean isAdvertisedName(String name) {
        if (name == null) return false;
        String n = name.trim();
        for (int i = 0; i < ADVERTISED_NAMES.length; i++)
            if (ADVERTISED_NAMES[i].equalsIgnoreCase(n)) return true;
        return false;
    }
}
