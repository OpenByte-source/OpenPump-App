package org.openpump;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.UUID;

/**
 * The BLE link to a ZD21 pump: the Epic Hydro PE Pump, which advertises itself as ZD21_PUMP.
 *
 * The connect sequence here is not arbitrary — each detail cost a build to find, and
 * each alone produces status=133:
 *   - connectGatt must NOT run on the scan callback's binder thread
 *   - the stack needs a settle delay after the scan stops (500 ms on Samsung)
 *   - a BluetoothDevice from a ScanResult goes stale; re-resolve the address
 *   - close the previous gatt before every attempt
 */
public final class PumpLink {

    private static final UUID SVC  = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID CH_W = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID CH_N = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // Exactly as the Epic Hydro PE Pump broadcasts them; PumpMatch.ADVERTISED_NAMES must match.
    private static final String[] NAMES = {"ZD21_PUMP", "ZD21_PUMP_OTA"};

    public interface Listener {
        void onLog(String line);
        void onState(String state, boolean connected);
        /**
         * One telemetry sample, delivered on the main thread like every Listener call, with
         * `arrivedAt`: when it ARRIVED at the phone, read on the thread that received it (the
         * GATT callback thread; the simulator's tick), before the hop to the main thread. It is
         * SystemClock.elapsedRealtime(), never the wall clock: the vent watch compares it with
         * times taken on other threads, and a wall clock stepped back between two of them
         * reorders them (WiringCheck invariant 81).
         *
         * V1 - WHY THE STAMP TRAVELS WITH IT. When the main thread stalls, every frame the
         * pump sent meanwhile is handled in one burst afterwards; stamped on handling, a
         * reading taken before a stop looks like one taken after it, and the vent watch
         * credited it as evidence. Anything that asks WHEN a reading was taken relative to a
         * command - the vent watch above all (WiringCheck invariant 80) - uses `arrivedAt`.
         */
        void onSample(Proto.Sample s, long arrivedAt);
        /**
         * A notification that is NOT telemetry — an ack, or a work-mode dump (opcode
         * 0x29). Until Task 16 these were logged as hex and discarded, which was fine
         * while nothing needed to read one; the hardware validation routine's preset
         * round-trip needs the dump's actual BYTES to compare against what it sent.
         *
         * Delivered on the main thread like onSample. The listener's journal records it
         * (as ACK or RX), so it is no longer also logged here as hex.
         */
        void onFrame(byte[] raw);
        /**
         * A frame has just been handed to the Bluetooth stack (or to the simulator) - the
         * moment the old "TX what: hex" log line marked. Reported only for a write the stack
         * ACCEPTED, so a refused write that is retried is one command in the journal, not
         * two. Delivered on the main thread like every other Listener call.
         */
        void onSent(byte[] frame, String what);
        /**
         * T9 — a scan window closed on a device that matched the name filter but is not on
         * the remembered list ({@link #setKnownAddresses}) — never connected before, or
         * connected once without being remembered — AND {@link PumpMatch#shouldPromptUnknown}
         * says this one earns the interactive ask (see {@link #decide}'s own doc for the two
         * cases it does not: nothing remembered yet at all, or a scan started while {@link
         * #setAllowNamingPrompt} says the pump may still be under command). The scan is
         * already stopped (this is not "still searching, also here's a candidate"); the
         * listener decides whether to remember it (then call {@link #connect}) or connect
         * this one time without remembering (call {@link #connect} directly). `rssi` is
         * included only for a caller that wants to say how strong the signal was; it plays
         * no further part once this fires. Delivered on the main thread like every other
         * Listener call.
         */
        void onUnknownPump(String address, String name, int rssi);
        /**
         * FIX ROUND (review Findings 1 & 2) — {@link PumpMatch#shouldPromptUnknown} said NO
         * to the interactive ask for a device that still matched the name filter and is
         * still not remembered, so PumpLink is connecting to it anyway (immediately after
         * this dispatch — see {@link #decide}) and needs the listener to persist it, the
         * same fact {@link #onUnknownPump}'s "Remember" choice would have persisted, minus
         * the dialog. The listener's ENTIRE job here is `Model#rememberPump(address, name)`
         * plus saving — nothing about the connection itself is this call's business, and
         * nothing here should show any UI. Delivered on the main thread like every other
         * Listener call.
         */
        void onAutoRememberPump(String address, String name);
    }

    private final Context ctx;
    private final Listener out;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chWrite, chNotify;
    private boolean scanning = false, ready = false;
    private String mac = null;

    /* ==================================================================== SIMULATED PUMP
     *
     * THE SEAM IS HERE, INSIDE THE TRANSPORT, and it is here rather than one level up on
     * purpose. Extracting an interface and standing a second implementation beside this
     * class would put a fork in the path the vent-confirmation wiring runs through - the
     * one part of this app where a mistake reaches a person. With the seam at the bottom,
     * EVERY line above it is exercised unmodified: the write queue, the ack pairing, the
     * vent watch, the stop retry, the summary. Nothing about the app changes; only what is
     * on the other end of the wire.
     *
     * WHAT IT IS FOR: five things that could only ever be checked with a cuff attached -
     * the warm-up's shape, a both-tracks day flipping over, the resume card's two answers,
     * a reduced session's actual commanded pressure, and the rests, which is the one that
     * bit before. None of those is about Bluetooth, and all of them were gated behind it.
     *
     * IT IS NOT A PUMP AND MUST NEVER BE MISTAKEN FOR ONE. The address says so, the state
     * line says so, and SessionActivity paints a banner that cannot be dismissed while it
     * is on. A simulated session is filed like a real one - it has to be, or the plan
     * behaviour these tests exist to check would not happen - and it carries a flag saying
     * what it was, so a history can never quietly contain runs that never happened.
     */
    private SimPump sim;
    private boolean simMode = false;

    /** The address a simulated link reports. Not a MAC, and deliberately not MAC-shaped:
     *  anything that stores or compares addresses should be visibly wrong if it ever sees
     *  this, rather than plausibly wrong. */
    public static final String SIM_ADDRESS = "simulated-pump";

    /** How often the simulated link delivers telemetry - the device's own cadence, so the
     *  app's sampling, charting and dose integration see the rate they were written for. */
    private static final long SIM_TICK_MS = SimPump.TELEM_PERIOD_MS;

    /**
     * Turn the simulator on or off. Always tears the current link down first: switching
     * between a real pump and a simulated one with a connection still open is the one way
     * this could deliver a frame to the wrong place.
     */
    public void setSimulated(boolean on) {
        if (simMode == on) return;
        close();
        simMode = on;
        state(on ? "Simulator armed" : "Disconnected");
        log(on ? "=== SIMULATED PUMP ON - no Bluetooth, no cuff ==="
               : "=== simulated pump off ===");
    }

    public boolean isSimulated() { return simMode; }

    /**
     * V1 - A DEBUG BUILD'S TEST HOOK MAY TAKE A FRAME BEFORE THE SIMULATOR GETS IT, which is how
     * a StopWork is lost on its way to check by hand that the vent watch does not confirm a stop
     * the pump never received. Which frame, and when, is decided in app/src/debug (SimStopLoss),
     * so a release APK carries neither the decision nor how to trigger it; this knows nothing
     * about a StopWork (WiringCheck invariant 91). It is reached by class-name string - the
     * diagnostic console's own pattern (SessionActivity#hasConsole) - only when
     * BuildConfig.DEBUG, and only from tx()'s simulator branch, so a real pump never passes
     * through it. SimStopLossIsDebugOnlyTest pins the placement.
     */
    private static final String SIM_HOOK_CLASS = "org.openpump.SimStopLoss";

    private boolean debugSimTakes(byte[] frame) {
        try {
            Object taken = Class.forName(SIM_HOOK_CLASS)
                .getMethod("takes", Context.class, byte[].class)
                .invoke(null, ctx, frame);
            if (!Boolean.TRUE.equals(taken)) return false;
            log("=== SIMULATED PUMP: a debug test hook took this frame - it never reaches the "
                + "simulator ===");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;       // no hook in this build, or it failed: the frame is delivered
        }
    }

    /** The same debug hook, on the way back: may it lose this ANSWER from the simulator, the
     *  command having been done (a real pump's lost reply)? Only in a debug build, only from
     *  the simulator's drain; decided in app/src/debug (SimStopLoss). */
    private boolean debugSimLosesAnswer(byte[] frame) {
        try {
            Object lost = Class.forName(SIM_HOOK_CLASS)
                .getMethod("answerLost", Context.class, byte[].class)
                .invoke(null, ctx, frame);
            if (!Boolean.TRUE.equals(lost)) return false;
            log("=== SIMULATED PUMP: a debug test hook lost this answer - the pump did the "
                + "command ===");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;       // no hook in this build, or it failed: the answer is delivered
        }
    }

    /** When the simulator last advanced (SystemClock.elapsedRealtime). */
    private long simLastMs;

    /* THE SIMULATOR ADVANCES BY THE REAL TIME THAT PASSED (SimPump#realStepMs), not a fixed
     * SIM_TICK_MS per firing: this timer runs on the UI thread, and under load it fired
     * about every 420 ms, which ran the simulator at 0.57x - a 120 s hold took 211 s.
     * SIM_TICK_MS is still how often it ASKS. The simulator only (WiringCheck 128). */
    private final Runnable simTick = new Runnable() {
        @Override public void run() {
            if (!simMode || sim == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            sim.tick(SimPump.realStepMs(simLastMs, now));
            simLastMs = now;
            java.util.List<byte[]> frames = sim.drain();
            for (int i = 0; i < frames.size(); i++) {
                // A DEBUG BUILD MAY LOSE AN ANSWER HERE, the simulator having done the command -
                // as the real pump does (the owner's journal: 343 STARTs, 319 answers). The
                // decision is the debug hook's (SimStopLoss#answerLost); a release build
                // compiles this away.
                if (BuildConfig.DEBUG && debugSimLosesAnswer(frames.get(i))) continue;
                handle(frames.get(i));
            }
            ui.postDelayed(this, SIM_TICK_MS);
        }
    };

    /** The simulated equivalent of scan-connect-discover-subscribe, which is four real
     *  steps and no simulated ones: there is nothing to find and nothing to negotiate. */
    private void simStart() {
        sim = new SimPump();
        ready = true;
        mac = SIM_ADDRESS;
        state("Simulated pump");
        log("=== SIMULATED LINK READY (" + SIM_ADDRESS + ") ===");
        ui.removeCallbacks(simTick);
        simLastMs = android.os.SystemClock.elapsedRealtime();
        ui.postDelayed(simTick, SIM_TICK_MS);
    }

    /* --------------------------------------------------------- T9 · named pump memory
     * Addresses this app has been told to remember, normalised (PumpMatch.norm) — handed
     * in by the caller (SessionActivity, from Model#knownPumpAddresses) before start(), so
     * a scan already knows what it is looking for rather than PumpLink reaching into Model
     * itself (PumpLink stays free of any app-domain import, same as it always has been).
     *
     * FIX ROUND, CORRECTING A FALSE CLAIM this doc comment made in the first round: it used
     * to say an empty set here means "every match falls through to the unknown-pump prompt,
     * which is exactly a fresh install's existing behaviour" — that is NOT what a fresh
     * install did before T9. The pre-T9 code connected to the FIRST matching scan result
     * with zero prompt, ever. An empty set now means the OPPOSITE of prompting: {@link
     * PumpMatch#shouldPromptUnknown} treats "nothing remembered yet at all" as its own
     * silent-and-remember case (see {@link #decide}), which is what actually reproduces the
     * old zero-friction behaviour for a genuinely fresh install — the naming prompt only
     * ever fires from the SECOND distinct device an install sees onward. */
    private java.util.Set<String> knownAddresses = new java.util.HashSet<String>();

    public void setKnownAddresses(java.util.Set<String> addrs) {
        knownAddresses = addrs == null ? new java.util.HashSet<String>() : addrs;
    }

    /**
     * FIX ROUND (review Finding 2) — whether a scan starting right now may interrupt with
     * the naming prompt at all. The caller (SessionActivity) sets this fresh before every
     * {@link #start()}, from `!stillUnsafe()` — false whenever the pump may still be under
     * command or a stop is outstanding and unconfirmed, which is precisely the window
     * {@code showLinkLost()}'s alarm screen exists for and precisely the moment fast
     * reconnect matters most (the same priority Stage D Task 5's reconnect work already
     * established). Defaults true — the ordinary case, so a caller that forgets to set it
     * gets the safe-for-UX-but-not-for-urgency default rather than a silently-suppressed
     * prompt nobody asked to suppress. See {@link PumpMatch#shouldPromptUnknown}.
     */
    private boolean allowNamingPrompt = true;

    public void setAllowNamingPrompt(boolean allow) { allowNamingPrompt = allow; }

    /** Devices matching the name filter seen THIS scan, keyed by address, most-recent RSSI
     *  kept — a plain (not thread-safe) map, safe only because every write and every read
     *  happens from inside a Runnable posted to `ui` (see onScanResult's own note). Order
     *  is insertion order (first-sighted first), which {@link PumpMatch#pickKnown}'s and
     *  {@link PumpMatch#unknowns}'s tie-breaks rely on. */
    private final java.util.LinkedHashMap<String, PumpMatch.Seen> scanSeen =
            new java.util.LinkedHashMap<String, PumpMatch.Seen>();
    /** Whether the short collection window's timer has been armed for the scan in
     *  progress — armed on the FIRST matching sighting, not on start(), so the window is
     *  measured from "something plausible showed up" rather than ticking down uselessly
     *  while nothing has answered yet (the outer 15 s {@link ScanTimeout} still owns "truly
     *  nothing out there"). */
    private boolean scanWindowArmed = false;
    /** How long to keep collecting sightings after the first one, before deciding —
     *  long enough to catch a second nearby KNOWN pump (a BLE advertising interval can run
     *  past a second on its own), needed only to disambiguate between several of those. A
     *  scan where AT MOST ONE pump is remembered in total does not wait this long at all —
     *  seeing that one pump IS the whole answer, whatever its signal. With two or more
     *  remembered, this always runs to completion, so pickKnown's RSSI comparison gets
     *  every one of them that showed up in time — see {@link PumpMatch#canExitEarly} (its
     *  own doc has the full correction history) and the early-exit check in {@link
     *  #onScanMatch}. */
    static final long SCAN_WINDOW_MS = 2500;
    private final Runnable scanWindowElapsed = new Runnable() {
        @Override public void run() {
            if (!scanning) return;   // decided already (an early exit, or a race with close())
            decide();
        }
    };

    /* -------------------------------------------------------------- write queue
     * Android's GATT stack accepts ONE outstanding write at a time: writeCharacteristic()
     * called while a previous write is still in flight returns false and the frame is
     * simply dropped. The frozen diagnostic console spaced every write with postDelayed and
     * so never hit this; this app fired uploadBatch's 9 deletes + 9 addPresets back-to-back
     * (18 writes in one loop), so on the real device most of them were dropped — the pump
     * got a fragment of its slot table, startSlot targeted nothing, and it sat idle. The
     * same drop swallowed Proto.stop() whenever anything else was in flight, which is why
     * "vent not confirmed" persisted: the stop never reached the pump.
     *
     * Every frame now goes through this queue: enqueue, and drain ONE at a time, sending the
     * next only after onCharacteristicWrite confirms the previous (or a short timeout fires,
     * so a stack that never calls back cannot wedge the pump). Order is preserved, which the
     * protocol depends on (Delete compacts, Add appends — order IS slot order). */
    private final java.util.ArrayDeque<byte[]> writeQ = new java.util.ArrayDeque<byte[]>();
    private final java.util.ArrayDeque<String> writeQWhat = new java.util.ArrayDeque<String>();
    /** Parallel to writeQ: the caller's write-done callback for each queued frame, or a
     *  shared no-op for the frames that want none (ArrayDeque refuses null elements). Runs
     *  on the UI thread once THAT frame's write completes or its timeout fires — the
     *  as-run recorder's provisional "this left the phone" stamp (the pump's own ack is
     *  the boundary when it is observed; see SessionActivity.onFrame). */
    private final java.util.ArrayDeque<Written> writeQDone = new java.util.ArrayDeque<Written>();
    private static final Written NO_CALLBACK = new Written() { @Override public void written(long at) { } };
    private boolean writeInFlight = false;
    /** The callback belonging to the frame in flight right now; NO_CALLBACK when none. */
    private Written writeInFlightDone = NO_CALLBACK;
    /** How long one write may stay in flight before the queue advances past it. Package-
     *  visible because the as-run recorder waits 2× this for the pump's ack after write-done
     *  before it records a change as unconfirmed. */
    static final long WRITE_TIMEOUT_MS = 400;
    private final Runnable writeTimeout = new Runnable() {
        @Override public void run() {
            if (!writeInFlight) return;
            log("!! write ack timed out — advancing the queue");
            finishWrite(SystemClock.elapsedRealtime());
        }
    };

    public PumpLink(Context c, Listener l) {
        ctx = c; out = l;
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
    }

    public boolean isReady() {
        if (simMode) return sim != null;
        return ready && gatt != null && chWrite != null;
    }
    public String  address() { return mac; }

    /* ------------------------------------------------- listener dispatch (main thread)
     * EVERY Listener call leaves this class from inside a Runnable posted to `ui`, and
     * nowhere else — onUnknownPump and onAutoRememberPump (T9) below keep the same rule,
     * via UnknownOut/AutoRememberOut. These two (log/state) used to call the Listener
     * DIRECTLY, and both are reached
     * from the BluetoothGattCallback — connectGatt is called without a Handler, so AOSP
     * runs those callbacks inline on the BLE binder thread, inside a try/catch that
     * SWALLOWS whatever they throw. SessionActivity.onState touches views, so
     * setVisibility() → requestLayout() → ViewRootImpl.checkThread() threw
     * CalledFromWrongThreadException where nobody could see it. Two symptoms on real
     * hardware: the guided-connect screen froze on "Discovering…" forever (the state
     * bookkeeping after the throw never ran), and the half-completed requestLayout() left
     * PFLAG_FORCE_LAYOUT set up the ancestor chain with no traversal scheduled, so every
     * later requestLayout() short-circuited at the root and new views measured 0×0 — the
     * run screen rendered blank until a background/resume forced a traversal.
     *
     * ALWAYS post, even when already on the main thread. Running inline when
     * Looper.myLooper() == getMainLooper() would save one loop iteration but reorder the
     * log: a line logged inline from a main-thread path would jump ahead of lines already
     * queued from the binder thread, and the log is the only evidence we get from a real
     * device. One uniform queue keeps emission order equal to call order.
     *
     * The state string and isReady() are captured HERE, on the calling thread, so the pair
     * cannot skew: without that, a state("Connected") posted before close() would run its
     * isReady() after the teardown and report "Connected, not ready".
     *
     * close() deliberately does NOT drop posted dispatches — same as Deliver and the
     * write-done post. `out` is final and lives as long as this PumpLink, so a late line
     * or state simply arrives; a stale "Connected" cannot outlive close() because the
     * connected flag was already frozen at post time. */
    private void log(String s) { ui.post(new LogOut(s)); }
    private void sent(byte[] frame, String what) { ui.post(new SentOut(frame, what)); }
    private void state(String s) { ui.post(new StateOut(s, isReady())); }
    private void unknownPump(String address, String name, int rssi) {
        ui.post(new UnknownOut(address, name, rssi));
    }
    private void autoRemember(String address, String name) {
        ui.post(new AutoRememberOut(address, name));
    }

    private final class LogOut implements Runnable {
        private final String s;
        LogOut(String s) { this.s = s; }
        @Override public void run() { out.onLog(s); }
    }

    private final class SentOut implements Runnable {
        private final byte[] frame; private final String what;
        SentOut(byte[] f, String w) { frame = f; what = w; }
        @Override public void run() { out.onSent(frame, what); }
    }

    private final class UnknownOut implements Runnable {
        private final String address, name; private final int rssi;
        UnknownOut(String a, String n, int r) { address = a; name = n; rssi = r; }
        @Override public void run() { if (!lastStopOnly) out.onUnknownPump(address, name, rssi); }
    }

    private final class AutoRememberOut implements Runnable {
        private final String address, name;
        AutoRememberOut(String a, String n) { address = a; name = n; }
        @Override public void run() { if (!lastStopOnly) out.onAutoRememberPump(address, name); }
    }

    private final class StateOut implements Runnable {
        private final String s; private final boolean connected;
        StateOut(String s, boolean c) { this.s = s; this.connected = c; }
        @Override public void run() { if (!lastStopOnly) out.onState(s, connected); }
    }

    /* ----------------------------------------------------------------- scan */

    public void start() {
        // H1 - NEVER REOPENED. A link kept only for a destroyed screen's last stop serves no
        // screen; a new connection from it would take the pump back from the live one.
        if (lastStopOnly) { log("!! start refused - this link is only for its last stop"); return; }
        // THE SIMULATOR NEVER TOUCHES THE ADAPTER, so it works with Bluetooth off, with no
        // permission granted, and on a device that has no radio at all - which is most of
        // the point.
        if (simMode) { simStart(); return; }
        if (adapter == null || !adapter.isEnabled()) {
            state("Bluetooth off");
            log("!! adapter null or disabled");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { state("no scanner"); return; }
        // Stop any scan already in flight before starting a new one. Without this a
        // second start() inside the 15 s scan window — a reconnect, an onResume, a tab
        // switch — hits SCAN_FAILED_ALREADY_STARTED (code 1). stopScan on a scan that is
        // not running is harmless (wrapped in its own try/catch).
        stopScan();
        // A FRESH scan starts with a fresh collection window (T9) — leftover sightings
        // from whatever scan just ended must never leak into this one's RSSI comparison,
        // and an armed-but-never-fired window from a scan that ended some other way
        // (close(), the 15 s timeout) must not fire late into this one either.
        scanSeen.clear();
        scanWindowArmed = false;
        ui.removeCallbacks(scanWindowElapsed);
        /* AND THE 15-SECOND TIMEOUT, which was a fresh anonymous object every scan and was
         * therefore never cancellable. The one from the PREVIOUS scan went on running and
         * fired into the scan that had replaced it - reporting "not found" against a window
         * a second old. Every other per-scan timer on this line is already removed; this was
         * the one that was not, because nothing held a reference to it. */
        ui.removeCallbacks(scanTimeout);
        try {
            scanning = true;
            state("Scanning…");
            log("=== SCAN start ===");
            ScanSettings st = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, st, scanCb);
            ui.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        } catch (SecurityException e) {
            log("!! scan SecurityException: " + e.getMessage());
            state("permission denied");
        }
    }

    private final class ScanTimeout implements Runnable {
        @Override public void run() {
            if (!scanning) return;
            stopScan();
            log("scan timed out after 15 s — pump on? already paired to another phone?");
            state("not found");
        }
    }

    private void stopScan() {
        scanning = false;
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (SecurityException ignored) { }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            if (!scanning || r == null || r.getDevice() == null) return;
            String n = null;
            android.bluetooth.le.ScanRecord rec = r.getScanRecord();
            if (rec != null) n = rec.getDeviceName();      // ADVERTISED name — present for a never-bonded pump
            if (n == null) { try { n = r.getDevice().getName(); } catch (SecurityException ignored) { } }  // cached/bonded name
            if (n == null) return;
            boolean matches = false;
            for (int i = 0; i < NAMES.length; i++)
                if (NAMES[i].equalsIgnoreCase(n)) { matches = true; break; }
            if (!matches) return;
            final String address = PumpMatch.norm(r.getDevice().getAddress());
            final String name = n;
            final int rssi = r.getRssi();
            log("found " + name + " " + address + "  rssi " + rssi);
            // Hop to main IMMEDIATELY, before touching anything T9 added (scanSeen,
            // scanWindowArmed, deciding, connecting) — onScanResult runs on the BLE binder
            // thread (connectGatt is called without a Handler; see the class doc), and
            // scanSeen is a plain, not-thread-safe map. log()/state() already keep this
            // discipline for every dispatch OUT of this class; this is the same discipline
            // applied to state coming IN from the binder thread.
            ui.post(new ScanMatch(address, name, rssi));
        }
        @Override public void onScanFailed(int code) {
            scanning = false;
            log("!! scan failed code=" + code);
            state("scan failed " + code);
        }
    };

    private final class ScanMatch implements Runnable {
        private final String address, name; private final int rssi;
        ScanMatch(String a, String n, int r) { address = a; name = n; rssi = r; }
        @Override public void run() { onScanMatch(address, name, rssi); }
    }

    /**
     * One name-filtered sighting, on the main thread. Records it, then either ends the
     * collection window EARLY — {@link PumpMatch#canExitEarly}: at most one pump remembered
     * in total AND that one has been seen, whatever its signal — or arms the window's timer
     * on the FIRST sighting of this scan, so a second known candidate (when two or more ARE
     * remembered) gets a fair chance to answer too, genuinely disambiguating between them,
     * before {@link #decide} runs. See {@link #canExitEarly}'s own doc for why the earlier
     * version of this check (seen-count alone, no regard for how many are remembered) made
     * that disambiguation unreachable for anyone with 2+ remembered pumps.
     */
    private void onScanMatch(String address, String name, int rssi) {
        if (!scanning) return;   // a late callback racing stopScan()/close() — ignore it
        scanSeen.put(address, new PumpMatch.Seen(address, name, rssi));
        if (PumpMatch.canExitEarly(
                new java.util.ArrayList<PumpMatch.Seen>(scanSeen.values()), knownAddresses)) {
            decide();
            return;
        }
        if (!scanWindowArmed) {
            scanWindowArmed = true;
            ui.postDelayed(scanWindowElapsed, SCAN_WINDOW_MS);
        }
    }

    /**
     * The window has closed (early exit or the timer) — pick a winner. A REMEMBERED
     * address with the strongest RSSI among any matches wins outright and connects with
     * no further ceremony, exactly as the very first match used to.
     *
     * Otherwise, if only unknown devices answered, the strongest of THOSE either gets the
     * one-time naming prompt (T9) — {@link Listener#onUnknownPump}, never more than one
     * dialog per decision even when a second stranger also answered this window (see
     * {@link PumpMatch#strongest}'s own doc) — or, when {@link PumpMatch#shouldPromptUnknown}
     * says no (review Findings 1 & 2: nothing remembered yet at all, or the caller flagged
     * this scan as one that must not be interrupted), is connected to and remembered
     * SILENTLY instead — {@link Listener#onAutoRememberPump} then {@link #connect}, no
     * dialog, exactly the pre-T9 zero-friction behaviour for a fresh install, or the
     * uninterrupted reconnect a run in progress needs.
     */
    private void decide() {
        ui.removeCallbacks(scanWindowElapsed);
        java.util.List<PumpMatch.Seen> seen =
                new java.util.ArrayList<PumpMatch.Seen>(scanSeen.values());
        stopScan();
        scanSeen.clear();
        scanWindowArmed = false;

        PumpMatch.Seen best = PumpMatch.pickKnown(seen, knownAddresses);
        if (best != null) {
            log("known pump matched: " + best.address + "  rssi " + best.rssi);
            connect(best.address, 0);
            return;
        }
        java.util.List<PumpMatch.Seen> unk = PumpMatch.unknowns(seen, knownAddresses);
        if (unk.isEmpty()) return;  // guarded: the window only arms after a match, so
                                    // reaching here with nothing collected should not happen
        PumpMatch.Seen ask = PumpMatch.strongest(unk);
        if (!PumpMatch.shouldPromptUnknown(allowNamingPrompt, knownAddresses)) {
            log("pump " + ask.address + " (" + ask.name + ")  rssi " + ask.rssi
                + " — auto-remembering silently ("
                + (knownAddresses.isEmpty() ? "nothing remembered yet" : "prompt suppressed")
                + ")");
            autoRemember(ask.address, ask.name);
            connect(ask.address, 0);
            return;
        }
        log("unknown pump " + ask.address + " (" + ask.name + ")  rssi " + ask.rssi
            + " — asking whether to remember it");
        state("Pump found — name it?");
        unknownPump(ask.address, ask.name, ask.rssi);
    }

    /* -------------------------------------------------------------- connect */

    /** The scan's own 15-second deadline, held rather than allocated, so start() and
     *  close() can take it down the way they take down every other pending callback. */
    private final Runnable scanTimeout = new ScanTimeout();

    /** How long a scan is given before it reports nothing found. */
    static final long SCAN_TIMEOUT_MS = 15000L;

    /** The delayed connect, held for the same reason: close() must be able to cancel it. */
    private Runnable pendingConnect;

    public void connect(String address, int attempt) {
        // H1 - NEVER REOPENED (the safety review): a status-133 disconnect inside the last
        // stop's window scheduled a reconnect, whose close() cancelled the close check and
        // whose connectGatt opened a new connection for a screen that no longer exists.
        if (lastStopOnly) { log("!! connect refused - this link is only for its last stop"); return; }
        mac = address;
        long delay = (attempt == 0) ? 500 : 600L * (attempt + 1);
        state("Connecting…");
        log("=== CONNECT " + address + " in " + delay + " ms (attempt "
            + (attempt + 1) + "/3, main thread) ===");
        /* HELD, so close() can cancel it. A connect is posted half a second out and up to
         * 1.8 s on a retry; close() cancelled every other pending callback and not this one,
         * so a deliberate teardown was followed by a reconnect - a GATT connection leaked
         * past onDestroy, and the pump taken back from whatever had it next. */
        ui.removeCallbacks(pendingConnect);
        pendingConnect = new ConnectLater(address, attempt);
        ui.postDelayed(pendingConnect, delay);
    }

    private final class ConnectLater implements Runnable {
        private final String a; private final int n;
        ConnectLater(String a, int n) { this.a = a; this.n = n; }
        @Override public void run() {
            // H1 (the second re-review) - a retry posted BEFORE the last stop: its close()
            // would cancel the close check and its connectGatt reopen the link.
            if (lastStopOnly) return;
            close();
            try {
                BluetoothDevice d = adapter.getRemoteDevice(a);    // re-resolve, never reuse
                gatt = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) log("!! connectGatt returned null");
            } catch (SecurityException e) {
                log("!! connect SecurityException: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                log("!! bad MAC: " + a);
            }
            attempt = n;
        }
    }

    private int attempt = 0;

    public void close() {
        ready = false;
        // The simulated pump goes with the link it was the other end of. Its ticker is
        // stopped FIRST, so nothing can deliver a frame from a pump that no longer exists.
        ui.removeCallbacks(simTick);
        sim = null;
        stopScan();                 // a full teardown stops the scan too, so a following
                                    // start() cannot collide with a scan left in flight.
        // Same reasoning as the write queue below: a collection window mid-teardown has
        // nothing left to decide FOR (the gatt it might have connected is gone either
        // way), so it is dropped rather than left to fire late into whatever comes next.
        // start() already does this too; repeating it here means close() alone (no
        // following start()) also leaves nothing armed.
        scanSeen.clear();
        scanWindowArmed = false;
        ui.removeCallbacks(scanWindowElapsed);
        ui.removeCallbacks(scanTimeout);
        // ...and a connect that has not fired yet. See connect() for what it did when it
        // survived: a teardown followed by a reconnection nobody asked for.
        ui.removeCallbacks(pendingConnect);
        pendingConnect = null;
        /* BUT THE FRAME IN FLIGHT WAS HANDED TO THE STACK, AND MAY HAVE LEFT (the review of
         * refusal-2, LOW). Its write-done went with the rest, so a START the pump may have
         * carried out read as "never reached the pump" - NOT_SENT, a certain "not taken" -
         * when whether it went is unknown. It is reported written, now: its note then waits for
         * an answer and, with none, is unknown and converges, as for any START whose answer
         * never came. Run here, before the queue goes, so the teardown's expiry of unwritten
         * notes (asRunExpireUnsent) finds it written. */
        if (writeInFlight && writeInFlightDone != NO_CALLBACK) {
            Written mayHaveLeft = writeInFlightDone;
            writeInFlightDone = NO_CALLBACK;
            log("the frame in flight at the teardown may have left - reported written");
            try { mayHaveLeft.written(SystemClock.elapsedRealtime()); }
            catch (RuntimeException e) { log("!! write-done callback threw: " + e); }
        }
        // A queued frame cannot survive a teardown — the gatt it was for is gone. Drop the
        // queue and its timeout so a reconnect starts clean and never replays stale frames.
        // The write-done callbacks go with their frames, un-run: a frame that never left
        // the phone has no "sent" moment to report, and the recorder's own expiry covers it.
        writeQ.clear(); writeQWhat.clear(); writeQDone.clear();
        writeInFlight = false;
        writeInFlightDone = NO_CALLBACK;
        writeAttempts = 0;
        ui.removeCallbacks(writeTimeout);
        ui.removeCallbacks(lastStopCheck);
        if (gatt != null) {
            try { gatt.close(); } catch (SecurityException ignored) { }
            gatt = null;
        }
        chWrite = null; chNotify = null;
    }

    public void disconnect() {
        if (gatt != null) {
            try { gatt.disconnect(); } catch (SecurityException ignored) { }
        }
        close();
        state("Disconnected");
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {

        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            log("onConnectionStateChange status=" + status + " newState=" + newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                state("Discovering…");
                try { g.discoverServices(); } catch (SecurityException ignored) { }
            } else {
                ready = false;
                state("Disconnected");
                if (status == 133 && attempt < 2 && !lastStopOnly) {
                    log("status=133 — retrying");
                    connect(mac, attempt + 1);
                }
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            log("onServicesDiscovered status=" + status);
            BluetoothGattService svc = g.getService(SVC);
            if (svc == null) { log("!! service FFF0 not found"); state("no FFF0"); return; }
            chWrite  = svc.getCharacteristic(CH_W);
            chNotify = svc.getCharacteristic(CH_N);
            if (chNotify == null) { log("!! FFF4 missing"); return; }
            try {
                g.setCharacteristicNotification(chNotify, true);
                BluetoothGattDescriptor d = chNotify.getDescriptor(CCCD);
                if (d == null) { log("!! no CCCD on FFF4"); return; }
                int p = chNotify.getProperties();
                byte[] val = ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, val);
                else { d.setValue(val); g.writeDescriptor(d); }
            } catch (SecurityException e) {
                log("!! notify SecurityException: " + e.getMessage());
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g,
                                                BluetoothGattDescriptor d, int status) {
            log("onDescriptorWrite status=" + status
                + (status == 0 ? "  SUBSCRIBED — telemetry starts unprompted" : ""));
            if (status == 0) { ready = true; state("Connected"); }
        }

        /** The stack confirms one write has left the phone — release the queue for the
         *  next. Fired on the binder thread; hop to main, where the queue lives. The time is
         *  read HERE, where the write completed, not when main gets to it (V1: the vent watch
         *  counts only frames that arrived after its StopWork went out). */
        @Override public void onCharacteristicWrite(BluetoothGatt g,
                                                    BluetoothGattCharacteristic c, int status) {
            final long at = SystemClock.elapsedRealtime();
            // ...and WHICH write it is: the token of the write in flight as this fires. Android
            // does not say; WritePairing decides on the main thread whether it still is.
            final int token = inFlightToken;
            if (status != 0) log("!! onCharacteristicWrite status=" + status);
            ui.post(new Runnable() { @Override public void run() { onWriteDone(token, at); } });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g,
                                                      BluetoothGattCharacteristic c) {
            handle(c.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g,
                                                      BluetoothGattCharacteristic c, byte[] v) {
            handle(v);
        }
    };

    private void handle(final byte[] v) {
        // V1: THE ARRIVAL STAMP, read here, on the thread the frame arrived on, before it
        // waits in the main thread's queue behind whatever is stalling it. See
        // Listener#onSample.
        final long arrivedAt = SystemClock.elapsedRealtime();
        if (v == null || v.length == 0) return;
        final Proto.Sample s = Proto.parse(v);
        ui.post(new Deliver(v, s, arrivedAt));
    }

    private final class Deliver implements Runnable {
        private final byte[] raw; private final Proto.Sample s; private final long arrivedAt;
        Deliver(byte[] r, Proto.Sample x, long at) { raw = r; s = x; arrivedAt = at; }
        @Override public void run() {
            // H1 - telemetry still arriving in the last stop's few hundred milliseconds would
            // reach a screen that no longer exists; the stop needs no answer from it.
            if (lastStopOnly) return;
            if (s != null) { out.onSample(s, arrivedAt); return; }
            out.onFrame(raw);
        }
    }

    /* ---------------------------------------------------------------- write */

    /** Enqueue a frame for serialised delivery. Returns true when the frame was ACCEPTED
     *  into the queue with a live link — the same contract callers already had ("the write
     *  was issued"), now honoured for every frame instead of silently only the first of a
     *  burst. A write is still not a vent; telemetry is the only evidence of that. */
    public boolean tx(byte[] frame, String what) {
        return tx(frame, what, (Written) null);
    }

    /**
     * A frame's write has completed, and WHEN, on SystemClock.elapsedRealtime() like every
     * time this class hands on: `at` is read where the completion happened -
     * the stack's onCharacteristicWrite (the GATT callback thread), the WRITE_TIMEOUT_MS
     * timeout that advances past a stack that never called back, or the simulator taking
     * the frame - never when the main thread got round to running this. Delivered on the
     * main thread. A frame dropped before it was written never reports.
     *
     * V1: the vent watch's StopWork uses this, so that only telemetry that ARRIVED after the
     * stop left the phone is counted as evidence about it (Session#ventWatchResult).
     */
    public interface Written { void written(long at); }

    /** A Runnable write-done callback, for the callers that do not need the time. */
    private static final class RunOnWritten implements Written {
        private final Runnable r;
        RunOnWritten(Runnable x) { r = x; }
        @Override public void written(long at) { r.run(); }
    }

    /** Hands a completion time taken elsewhere to a Written, on the main thread. */
    private static final class WrittenOut implements Runnable {
        private final Written w; private final long at;
        WrittenOut(Written x, long t) { w = x; at = t; }
        @Override public void run() { w.written(at); }
    }

    /**
     * {@link #tx(byte[], String)} with a write-done callback: `onSent` runs on the UI thread
     * when THIS frame's write completes — the stack's onCharacteristicWrite, or the
     * WRITE_TIMEOUT_MS timeout that advances the queue past a stack that never called back.
     * Either way the frame was handed to the stack; what it does NOT mean is that the pump
     * acted on it (a write is not a vent, and not a start either — the pump's own ack frame
     * is that evidence, and SessionActivity.onFrame pairs it). A frame dropped before it
     * was written (close(), link gone) never reports. Returns false, and never runs
     * onSent, when there is no link to queue it for.
     */
    public boolean tx(byte[] frame, String what, Runnable onSent) {
        return tx(frame, what, onSent == null ? null : new RunOnWritten(onSent));
    }

    /** {@link #tx(byte[], String, Runnable)}, told WHEN the write completed - see
     *  {@link Written}. */
    public boolean tx(byte[] frame, String what, Written onWritten) {
        // H1 - NOTHING AFTER THE LAST STOP. A callback the dying screen left posted must not
        // queue a frame behind it: a start written after the stop would re-arm the pump.
        if (lastStopOnly) { log("!! refused after the last stop: " + what); return false; }
        if (simMode) {
            if (sim == null) { log("!! tx with no simulated link: " + what); return false; }
            sent(frame, what);
            if (!(BuildConfig.DEBUG && debugSimTakes(frame))) sim.write(frame);
            // The simulator has the frame now: this is its write completing, and every frame
            // it emits from here on arrives after it.
            final long at = SystemClock.elapsedRealtime();
            // THE ANSWER COMES BACK THROUGH THE SAME DOOR AS A REAL ONE - the tick drains
            // it and delivers it as a notification - so the ack pairing, the vent watch and
            // the stop retry all see exactly the shape they see on hardware. Returning it
            // from here instead would let code pass that could never work on a real pump.
            if (onWritten != null) ui.post(new WrittenOut(onWritten, at));
            return true;
        }
        if (gatt == null || chWrite == null) { log("!! tx with no link: " + what); return false; }
        writeQ.addLast(frame);
        writeQWhat.addLast(what);
        writeQDone.addLast(onWritten == null ? NO_CALLBACK : onWritten);
        drainWrites();
        return true;
    }

    /* ==================================================== H1 - THE LAST STOP LEAVES FIRST
     *
     * SessionActivity#onDestroy is the last chance to send anything, and it used to tx() its
     * stop and close() in the same main-thread turn. close() drops every frame still queued,
     * so a stop waiting behind a write in flight never left the phone: the pump went on
     * holding while the next screen had no hold on record and said nothing.
     *
     * stopThenClose sends the stop ALONE - every frame still waiting is dropped first, so
     * nothing the dying screen queued can reach the pump after it or instead of it - and
     * keeps the link until that frame's own write has completed (the stack's
     * onCharacteristicWrite, or the write timeout that advances past a stack that never
     * answers) plus a short grace for the radio, and never longer than a bound (LastStop,
     * LastStopTest). From the moment it is called the link exists only for that stop: tx()
     * refuses, and telemetry and state stop reaching the listener, whose screen is gone. It
     * is never reopened: start(), connect() and the status-133 retry all refuse.
     *
     * WHAT IT CANNOT PROMISE: a write-without-response has no acknowledgement from the pump,
     * so "written" means the phone's Bluetooth stack took it, not that the pump acted on it.
     * It is the best a screen that is going away can do; the telemetry confirmation needs a
     * screen still watching. And a process killed outright inside the window takes the link
     * with it - by then the frame is already with the stack, unless a write was in flight
     * ahead of it.
     */
    /** Volatile: the GATT callback thread reads it before a status-133 retry. */
    private volatile boolean lastStopOnly;
    private long lastStopQueuedAt;
    private long lastStopWrittenAt = LastStop.NOT_WRITTEN;
    private final Runnable lastStopCheck = new LastStopCheck();

    /** Returns whether the stop was queued onto a live link. The link closes itself either
     *  way - at once when there was nothing to send on. */
    public boolean stopThenClose(byte[] stop, String what) {
        if (lastStopOnly) return false;
        // (the second re-review) a connect already posted - a status-133 retry - goes too.
        ui.removeCallbacks(pendingConnect);
        pendingConnect = null;
        int dropped = writeQ.size();
        writeQ.clear(); writeQWhat.clear(); writeQDone.clear();
        if (dropped > 0)
            log("last stop: " + dropped + " queued frame(s) dropped - the stop goes alone");
        lastStopOnly = true;
        // THE ONE MONOTONIC CLOCK this class stamps every time with (elapsedRealtime, V1), so
        // the stop's write-done time and its queued time are read off the same clock.
        lastStopQueuedAt = SystemClock.elapsedRealtime();
        lastStopWrittenAt = LastStop.NOT_WRITTEN;
        boolean queued;
        if (simMode) {
            queued = sim != null;
            if (queued) {
                sent(stop, what);
                sim.write(stop);
                lastStopWrittenAt = lastStopQueuedAt;      // the simulator takes it at once
            }
        } else if (gatt == null || chWrite == null) {
            queued = false;
        } else {
            writeQ.addLast(stop);
            writeQWhat.addLast(what);
            writeQDone.addLast(new LastStopWritten());
            queued = true;
        }
        if (!queued) {
            log("!! last stop: no link to send it on - " + what);
            close();
            return false;
        }
        scheduleLastStopCheck();
        drainWrites();
        return true;
    }

    private void scheduleLastStopCheck() {
        long at = LastStop.closeAt(lastStopQueuedAt, lastStopWrittenAt,
                                   LastStop.GRACE_MS, LastStop.MAX_MS);
        ui.removeCallbacks(lastStopCheck);
        ui.postDelayed(lastStopCheck, Math.max(0L, at - SystemClock.elapsedRealtime()));
    }

    /** The stop's own write has completed, at `at` (elapsedRealtime, read where it completed
     *  - see Written): the link may go a grace later. */
    private final class LastStopWritten implements Written {
        @Override public void written(long at) {
            lastStopWrittenAt = at;
            scheduleLastStopCheck();
        }
    }

    private final class LastStopCheck implements Runnable {
        @Override public void run() {
            if (!lastStopOnly) return;
            long now = SystemClock.elapsedRealtime();
            if (!LastStop.mayClose(now, lastStopQueuedAt, lastStopWrittenAt,
                                   LastStop.GRACE_MS, LastStop.MAX_MS)) {
                scheduleLastStopCheck();
                return;
            }
            log(lastStopWrittenAt == LastStop.NOT_WRITTEN
                ? "!! last stop not reported written within " + LastStop.MAX_MS
                  + " ms - closing the link anyway"
                : "last stop written - closing the link");
            close();
        }
    }

    /** Send the head of the queue if nothing is in flight. Called on enqueue, on write ack,
     *  and on ack timeout. Runs on the main thread (all callers are). */
    private void drainWrites() {
        if (writeInFlight || writeQ.isEmpty()) return;
        if (gatt == null || chWrite == null) {
            // Link vanished with frames queued: drop them, say so once. Anything that mattered
            // (a stop) has its own retry via the vent watch; do not pretend they were sent.
            log("!! link gone with " + writeQ.size() + " frame(s) queued — dropped");
            writeQ.clear(); writeQWhat.clear(); writeQDone.clear();
            return;
        }
        byte[] frame = writeQ.pollFirst();
        String what = writeQWhat.pollFirst();
        Written done = writeQDone.pollFirst();
        if (done == null) done = NO_CALLBACK;
        boolean issued;
        // WHICH WRITE THIS IS, and when it was handed over - set before the stack can answer.
        inFlightToken = inFlightToken == Integer.MAX_VALUE ? 1 : inFlightToken + 1;
        inFlightIssuedAt = SystemClock.elapsedRealtime();
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                issued = gatt.writeCharacteristic(chWrite, frame,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        == android.bluetooth.BluetoothStatusCodes.SUCCESS;
            } else {
                chWrite.setValue(frame);
                chWrite.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                issued = gatt.writeCharacteristic(chWrite);
            }
        } catch (SecurityException e) {
            log("!! tx SecurityException: " + e.getMessage());
            issued = false;
        }
        if (!issued) {
            /* THE COMMENT SAID "ONCE". THERE WAS NO COUNT.
             *
             * A frame the stack keeps refusing went back to the head of the queue for ever,
             * with writeInFlight held true - so nothing behind it could ever be sent either,
             * and tx() went on returning true onto a queue that would never drain. Three
             * attempts, then it is dropped with a record, which is what the rest of this
             * method already does for a link that has vanished. */
            if (++writeAttempts >= WRITE_MAX_ATTEMPTS) {
                log("!! dropping " + what + " — the stack refused it "
                    + WRITE_MAX_ATTEMPTS + " times");
                writeAttempts = 0;
                writeInFlight = false;
                writeInFlightDone = NO_CALLBACK;
                ui.removeCallbacks(writeTimeout);
                drainWrites();
                return;
            }
            log("!! write not accepted by the stack — will retry: " + what);
            writeQ.addFirst(frame); writeQWhat.addFirst(what); writeQDone.addFirst(done);
            writeInFlight = true;
            writeInFlightDone = NO_CALLBACK;
            ui.removeCallbacks(writeTimeout);
            ui.postDelayed(writeTimeout, WRITE_TIMEOUT_MS);
            return;
        }
        sent(frame, what);
        writeAttempts = 0;      // it left the phone; the count is about ONE frame's refusals
        writeInFlight = true;
        writeInFlightDone = done;
        ui.removeCallbacks(writeTimeout);
        ui.postDelayed(writeTimeout, WRITE_TIMEOUT_MS);
    }

    /** How many times the stack may refuse one frame before it is dropped with a record.
     *  Unbounded, this wedged the whole queue and tx() went on reporting success. */
    private static final int WRITE_MAX_ATTEMPTS = 3;

    /** Refusals of the frame currently at the head. Reset when one is issued, and by
     *  close(), so a teardown never carries a count into the next link. */
    private int writeAttempts;

    /** Called from the GATT callback when the stack confirms a write left the phone; `at`
     *  is when it did and `token` which write was in flight then, both read on the callback
     *  thread. A report for a write the timeout already retired must not retire the next one:
     *  that is how a StopWork was once given the previous frame's completion time. */
    private void onWriteDone(int token, long at) {
        if (!writeInFlight) return;   // a late callback for a write the timeout already advanced past
        if (!WritePairing.isOwn(token, inFlightToken, at, inFlightIssuedAt)) {
            log("!! a late write-done for an earlier frame - ignored");
            return;
        }
        finishWrite(at);
    }

    /** The write in flight: its token (never 0 once one is issued) and when it was handed to
     *  the stack, set just BEFORE writeCharacteristic so no report for it can predate them.
     *  Volatile because onCharacteristicWrite reads the token on the callback thread. */
    private volatile int inFlightToken = 0;
    private volatile long inFlightIssuedAt = 0;

    /** The one place a write in flight is RETIRED — by the stack's callback or by the
     *  timeout — so the frame's write-done callback runs exactly once either way, and the
     *  queue advances after it (a callback that enqueues another frame lands behind, in
     *  order). */
    private void finishWrite(long at) {
        ui.removeCallbacks(writeTimeout);
        writeInFlight = false;
        Written done = writeInFlightDone;
        writeInFlightDone = NO_CALLBACK;
        try { done.written(at); } catch (RuntimeException e) { log("!! write-done callback threw: " + e); }
        drainWrites();
    }
}
