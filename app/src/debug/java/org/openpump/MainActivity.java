package org.openpump;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * OpenPump - diagnostic BLE client for the Epic Hydro PE Pump (ZD21_PUMP) and PE06_Scale.
 *
 * Runs on the phone next to the real hardware and logs EVERYTHING, so the reverse
 * engineered spec can be checked against ground truth and the open probes settled.
 *
 * Logs:
 *   - every scan result: advertised name, RSSI, service UUIDs, raw AD payload
 *   - the FULL GATT tree with real property bits            (probe P12)
 *   - MTU negotiation result, flagged against the 207 floor history needs
 *   - every TX frame as hex, with a decode
 *   - every RX frame as hex, decoded under BOTH pressure hypotheses  (probe P1)
 *   - live telemetry cadence in Hz                          (probe P2)
 *   - 204-byte history chunks and their first bytes         (settles HISTORY.md 3)
 *   - a one-tap StopWork vent test                          (the safety-critical probe)
 *
 * Everything is written to a timestamped file under getExternalFilesDir():
 *   adb pull /sdcard/Android/data/org.openpump/files/
 *
 * SAFETY: nothing actuates the pump unless a button is pressed, and the actuating
 * buttons require explicit confirmation. RELEASE is always one tap away.
 */
public class MainActivity extends Activity {

    private static final String TAG = "OpenPump";

    private static final UUID SVC  = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID CH_W = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID CH_N = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // Matched exactly as advertised. Both ZD21 names are the Epic Hydro PE Pump, treated alike.
    private static final String[] NAMES = {
        "ZD21_PUMP", "ZD21_PUMP_OTA", "PE06_Scale", "PE06_Scalse"
    };

    // action codes for the button dispatcher
    private static final int A_SCAN = 0, A_DISC = 1, A_PRESETS = 2, A_HISTORY = 3,
        A_VERSION = 4, A_SETTIME = 5, A_START0 = 6, A_STOP = 7, A_RELEASE = 8,
        A_VENTTEST = 9, A_UPLOAD = 10, A_SHARE = 11, A_WHATSAPP = 12,
        A_CONNADDR = 13, A_SCANALL = 14, A_FULLTEST = 15, A_CAPTEST = 16,
        A_MASTER = 17, A_RUNROUTINE = 18, A_PRE_RAMP = 19,
        A_PRE_BURST = 20, A_PRE_WAVE = 21, A_TOGGLE_EDIT = 22;

    /** Dev PC running serve.py. Editable in the UI; this is only the default. */
    private static final String DEFAULT_PC = "";
    private android.widget.EditText pcField;

    /** Known pump MAC. Connecting by address needs no scan permission. */
    private static final String DEFAULT_MAC = "";
    private android.widget.EditText macField;
    private TextView statusView, pressureView, progressView;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chWrite, chNotify;

    private TextView logView;
    private ScrollView scroll;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private OutputStreamWriter logFile;
    private String logPath = "(none)";

    private int rxCount = 0;
    private long firstRx = 0, lastRx = 0;
    private int historyChunks = 0;
    private final List<String> historyFirstBytes = new ArrayList<String>();
    private boolean scanning = false;
    private boolean scanAll = false;
    private int connectAttempt = 0;
    private String connectMac = null;

    /** Device Information Service - reveals the actual silicon and firmware. */
    private static final String[][] DIS = {
        {"00002a29", "manufacturer"}, {"00002a24", "model"},
        {"00002a25", "serial"},       {"00002a26", "firmware_rev"},
        {"00002a27", "hardware_rev"}, {"00002a28", "software_rev"},
        {"00002a23", "system_id"},    {"00002a50", "pnp_id"},
    };
    private int disIndex = -1;

    // ---- self-test runner state ----
    private boolean testRunning = false;
    private int testStep = 0;
    private final List<String> testResults = new ArrayList<String>();
    private int stepRxStart = 0;
    private double stepPmax = -1, stepPmin = 1e9;
    private String stepMode = "";
    private final java.util.Set<Integer> stepAcks = new java.util.HashSet<Integer>();
    private final List<String> lastDump = new ArrayList<String>();
    private boolean dumpSeen = false;
    private int histSeen = 0;
    private boolean versionSeen = false, setTimeAckSeen = false;
    private long decayT0 = 0; private double decayP0 = -1, decayPend = -1;
    private long decayTend = 0;

    private final SimpleDateFormat stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // ------------------------------------------------------------ dispatcher

    private class Click implements View.OnClickListener {
        private final int action;
        Click(int a) { this.action = a; }
        @Override public void onClick(View v) { doAction(action); }
    }

    private void doAction(int a) {
        switch (a) {
            case A_SCAN:    startScan(false); break;
            case A_DISC:    disconnect(); break;
            case A_PRESETS: tx(cmd(0x29), "List"); break;
            case A_HISTORY: tx(cmd(0x24), "GetHistory"); break;
            case A_VERSION: txVersion(); break;
            case A_SETTIME: txSetTime(); break;
            case A_STOP:    tx(cmd(0x2D), "StopWork"); break;
            case A_RELEASE: release(); break;
            case A_START0:
                confirm("Start preset slot 0?",
                    "This ACTUATES the pump. The device must not be attached to a person.",
                    A_START0);
                break;
            case A_UPLOAD:   uploadLog(); break;
            case A_CONNADDR: connectByAddress(); break;
            case A_SCANALL:  startScan(true); break;
            case A_RUNROUTINE:
                confirm("Run this routine?",
                    "Runs your edited routine on the pump. BENCH ONLY unless you "
                    + "know exactly what these settings do.",
                    A_RUNROUTINE);
                break;
            case A_PRE_RAMP:  applyShape(0); break;
            case A_PRE_BURST: applyShape(1); break;
            case A_PRE_WAVE:  applyShape(2); break;
            case A_TOGGLE_EDIT:
                editorPanel.setVisibility(
                    editorPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                break;
            case A_MASTER:
                confirm("Run the COMPLETE TEST?",
                    "About 6 minutes, fully automatic: protocol checks, then physical "
                    + "characterisation, then a feature feasibility matrix.\n\n"
                    + "BENCH ONLY. Nothing attached to a person. The port MUST be "
                    + "SEALED or the measurements mean nothing.",
                    A_MASTER);
                break;
            case A_CAPTEST:
                confirm("Run the CAPABILITY TEST?",
                    "~3 minutes. Runs the pump at several speeds and cycle settings "
                    + "to measure how fast it can build and shed pressure. "
                    + "BENCH ONLY, port SEALED, nothing attached to a person.",
                    A_CAPTEST);
                break;
            case A_FULLTEST:
                confirm("Run the FULL SELF-TEST?",
                    "~2 minutes. Writes a test preset, RUNS THE PUMP, stops it, measures "
                    + "the decay, then deletes the preset and releases. "
                    + "BENCH ONLY - the device must not be attached to a person, and the "
                    + "port should be SEALED or the decay measurement means nothing.",
                    A_FULLTEST);
                break;
            case A_SHARE:    shareLog(false); break;
            case A_WHATSAPP: shareLog(true); break;
            case A_VENTTEST:
                confirm("Run the StopWork vent test?",
                    "Pulls a vacuum on slot 0, waits 6 s, sends StopWork, then logs the "
                    + "decay for 30 s. Answers whether StopWork vents or holds pressure.",
                    A_VENTTEST);
                break;
        }
    }

    /** Second stage of a confirmed action. */
    private void doConfirmed(int a) {
        if (a == A_START0) {
            tx(new byte[]{0x66, 0x2A, 0x2C, 0x00}, "Start slot 0");
        } else if (a == A_VENTTEST) {
            stopVentTest();
        } else if (a == A_FULLTEST) {
            startFullTest();
        } else if (a == A_CAPTEST) {
            startCapTest();
        } else if (a == A_MASTER) {
            startMaster();
        } else if (a == A_RUNROUTINE) {
            startRoutine();
        }
    }

    private class Confirmed implements DialogInterface.OnClickListener {
        private final int action;
        Confirmed(int a) { this.action = a; }
        @Override public void onClick(DialogInterface d, int w) { doConfirmed(action); }
    }

    private void confirm(String title, String msg, int action) {
        new AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("RUN", new Confirmed(action))
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Deferred TX, used for the timed probe sequences. */
    private class Later implements Runnable {
        private final byte[] frame; private final String what; private final int note;
        Later(byte[] f, String w, int n) { frame = f; what = w; note = n; }
        @Override public void run() {
            if (note == 1) log("t+6s   sending StopWork - WATCH THE PRESSURE TRACE");
            if (note == 2) {
                log("t+36s  vent test over.");
                log("       Slow steady decay  => StopWork HOLDS pressure:"
                    + " leak-decay cycling is possible, and 'stop' is NOT a release.");
                log("       Immediate collapse => StopWork VENTS:"
                    + " good emergency release, leak-decay cycling impossible.");
                return;
            }
            if (frame != null) tx(frame, what);
        }
    }

    // ------------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Paint the system bars the same dark ground as the app body, so there is no
        // visible seam between the status/nav bars and the content below them.
        getWindow().setStatusBarColor(Look.GROUND);
        getWindow().setNavigationBarColor(Look.GROUND);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            // Look.GROUND is dark, so icons stay LIGHT (the default) — no
            // SYSTEM_UI_FLAG_LIGHT_STATUS_BAR is added.
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(10, 10, 10, 10);
        root.setBackgroundColor(Color.rgb(18, 18, 22));

        // ---------- status card: what is happening, right now ----------
        statusView = new TextView(this);
        statusView.setTextSize(15f);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setTextColor(Color.rgb(255, 190, 60));
        statusView.setPadding(14, 12, 14, 4);
        statusView.setText("NOT CONNECTED");
        root.addView(statusView);

        pressureView = new TextView(this);
        pressureView.setTextSize(34f);
        pressureView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pressureView.setTextColor(Color.rgb(120, 230, 160));
        pressureView.setPadding(14, 0, 14, 2);
        pressureView.setText("--.- kPa");
        root.addView(pressureView);

        progressView = new TextView(this);
        progressView.setTextSize(12f);
        progressView.setTextColor(Color.rgb(170, 180, 200));
        progressView.setPadding(14, 0, 14, 10);
        progressView.setText("Press CONNECT to begin");
        root.addView(progressView);

        // ---------- primary action ----------
        Button master = new Button(this);
        master.setText("RUN COMPLETE TEST");
        master.setTextSize(17f);
        master.setTypeface(Typeface.DEFAULT_BOLD);
        master.setBackgroundColor(Color.rgb(40, 165, 90));
        master.setTextColor(Color.WHITE);
        master.setPadding(8, 22, 8, 22);
        master.setOnClickListener(new Click(A_MASTER));
        root.addView(master, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---------- connect / stop ----------
        LinearLayout r1 = new LinearLayout(this);
        Button cn = bigBtn("CONNECT", A_SCAN, Color.rgb(55, 120, 210));
        Button st = bigBtn("STOP + RELEASE", A_RELEASE, Color.rgb(200, 45, 45));
        r1.addView(cn); r1.addView(st);
        root.addView(r1);

        // ---------- send results ----------
        LinearLayout r2 = new LinearLayout(this);
        Button wa = bigBtn("SEND RESULTS (WhatsApp)", A_WHATSAPP, Color.rgb(37, 170, 90));
        r2.addView(wa);
        root.addView(r2);

        // ---------- advanced, small ----------
        TextView adv = new TextView(this);
        adv.setText("advanced");
        adv.setTextSize(10f);
        adv.setTextColor(Color.rgb(120, 128, 145));
        adv.setPadding(14, 12, 14, 2);
        root.addView(adv);

        LinearLayout r3 = new LinearLayout(this);
        r3.addView(btn("PRESETS", A_PRESETS));
        r3.addView(btn("HISTORY", A_HISTORY));
        r3.addView(btn("VERSION", A_VERSION));
        r3.addView(btn("TIME", A_SETTIME));
        r3.addView(btn("DISC", A_DISC));
        root.addView(r3);

        LinearLayout r4 = new LinearLayout(this);
        r4.addView(btn("PROTOCOL ONLY", A_FULLTEST));
        r4.addView(btn("CAPABILITY ONLY", A_CAPTEST));
        r4.addView(btn("SCAN ALL", A_SCANALL));
        root.addView(r4);

        LinearLayout r5 = new LinearLayout(this);
        macField = new android.widget.EditText(this);
        macField.setText(DEFAULT_MAC);
        macField.setTextSize(9f);
        macField.setTextColor(Color.rgb(200, 205, 215));
        r5.addView(macField, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
        r5.addView(btn("CONN ADDR", A_CONNADDR));
        root.addView(r5);

        LinearLayout rEd = new LinearLayout(this);
        rEd.addView(btn("SHOW / HIDE ROUTINE EDITOR", A_TOGGLE_EDIT));
        root.addView(rEd);

        initStages();
        View ed = buildEditor();
        ed.setVisibility(View.GONE);
        root.addView(ed);

        LinearLayout r6 = new LinearLayout(this);
        pcField = new android.widget.EditText(this);
        pcField.setText(DEFAULT_PC);
        pcField.setTextSize(9f);
        pcField.setTextColor(Color.rgb(200, 205, 215));
        r6.addView(pcField, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
        r6.addView(btn("UPLOAD", A_UPLOAD));
        r6.addView(btn("SHARE", A_SHARE));
        root.addView(r6);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(8f);
        logView.setTextColor(Color.rgb(175, 182, 195));
        logView.setTextIsSelectable(true);
        scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshEditor();

        openLog();
        String build = "?";
        try {
            android.content.pm.PackageInfo pi =
                getPackageManager().getPackageInfo(getPackageName(), 0);
            build = pi.versionName + " (code " + pi.versionCode + ")";
        } catch (Exception ignored) { }
        log("OpenPump BUILD " + build);
        log("Android SDK " + Build.VERSION.SDK_INT
            + " on " + Build.MANUFACTURER + " " + Build.MODEL);
        log("Log file: " + logPath);

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = (bm == null) ? null : bm.getAdapter();
        if (adapter == null) {
            log("!! No Bluetooth adapter");
        } else if (!adapter.isEnabled()) {
            log("!! Bluetooth is OFF - enable it, then press SCAN+CONNECT");
        }
        requestPerms();
    }

    private Button bigBtn(String label, int action, int bg) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(bg);
        b.setTextColor(Color.WHITE);
        b.setPadding(6, 16, 6, 16);
        b.setOnClickListener(new Click(action));
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    /** Live status card. Called from anywhere; marshals to the UI thread. */
    private void setStatus(final String status, final String progress) {
        ui.post(new Runnable() { @Override public void run() {
            if (status != null) statusView.setText(status);
            if (progress != null) progressView.setText(progress);
        } });
    }

    private void setPressure(final double kpa, final String mode) {
        ui.post(new Runnable() { @Override public void run() {
            pressureView.setText(String.format(Locale.US, "%.1f kPa   %s",
                kpa, (mode == null ? "" : mode)));
        } });
    }

    private Button btn(String label, int action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(8f);
        b.setPadding(2, 2, 2, 2);
        b.setOnClickListener(new Click(action));
        // Equal weight so a row always fits the screen. With wrap_content the
        // rightmost buttons (SEND TO WHATSAPP among them) fall off the edge.
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void requestPerms() {
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) {
            log("Requesting permissions: " + need);
            requestPermissions(need.toArray(new String[0]), 1);
        }
    }

    // --------------------------------------------------------------- logging

    private void openLog() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir != null && !dir.exists()) dir.mkdirs();
            String name = "pumpdebug-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".txt";
            File f = new File(dir, name);
            logFile = new OutputStreamWriter(new FileOutputStream(f, true),
                                             StandardCharsets.UTF_8);
            logPath = f.getAbsolutePath();
        } catch (Exception e) {
            logPath = "(failed: " + e + ")";
        }
    }

    private class Append implements Runnable {
        private final String line;
        Append(String l) { line = l; }
        @Override public void run() {
            logView.append(line + "\n");
            scroll.post(new ScrollDown());
        }
    }

    private class ScrollDown implements Runnable {
        @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
    }

    private void log(String s) {
        String line = stamp.format(new Date()) + "  " + s;
        Log.d(TAG, s);
        try {
            if (logFile != null) { logFile.write(line + "\n"); logFile.flush(); }
        } catch (Exception ignored) { }
        ui.post(new Append(line));
    }

    private static String hex(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) sb.append(String.format("%02X ", b[i]));
        return sb.toString().trim();
    }

    private static String ascii(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++)
            sb.append((b[i] >= 32 && b[i] < 127) ? (char) b[i] : '.');
        return sb.toString();
    }

    // ------------------------------------------------------------------ scan

    private class ScanTimeout implements Runnable {
        @Override public void run() {
            if (scanning) {
                stopScan();
                log("scan timeout - no matching device");
                log("  A generic scanner seeing ZD21_PUMP while this does not means");
                log("  scanning is permission-blocked here. Two things to try:");
                log("   1. CONNECT ADDR - connects by MAC, needs no scan permission");
                log("   2. SCAN ALL     - unfiltered; if the pump shows up there,");
                log("                     the name filter is at fault, not permissions");
            }
        }
    }

    /** Log everything that governs whether a BLE scan can return results. */
    private void scanDiagnostics() {
        log("--- scan preconditions ---");
        log("  bt enabled       : " + (adapter != null && adapter.isEnabled()));
        if (Build.VERSION.SDK_INT >= 31) {
            log("  BLUETOOTH_SCAN   : " + permState(Manifest.permission.BLUETOOTH_SCAN));
            log("  BLUETOOTH_CONNECT: " + permState(Manifest.permission.BLUETOOTH_CONNECT));
        }
        log("  FINE_LOCATION    : " + permState(Manifest.permission.ACCESS_FINE_LOCATION));
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                getSystemService(LOCATION_SERVICE);
            boolean gps = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
            boolean net = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
            log("  location service : gps=" + gps + " network=" + net
                + ((gps || net) ? "" : "   <-- OFF: blocks BLE scan on many builds"));
        } catch (Exception e) {
            log("  location service : ? (" + e + ")");
        }
        log("--------------------------");
    }

    private void startScan(boolean unfiltered) {
        if (adapter == null || !adapter.isEnabled()) { toast("Enable Bluetooth first"); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { log("!! No LE scanner"); return; }
        if (scanning) { log("already scanning"); return; }

        scanDiagnostics();

        List<ScanFilter> filters = null;
        if (!unfiltered) {
            filters = new ArrayList<ScanFilter>();
            for (int i = 0; i < NAMES.length; i++)
                filters.add(new ScanFilter.Builder().setDeviceName(NAMES[i]).build());
            log("=== SCAN start, name filters: " + Arrays.toString(NAMES) + " ===");
        } else {
            // No filters: shows EVERY advertiser. If the pump appears here but not
            // in the filtered scan, the name match is the problem, not permissions.
            log("=== SCAN ALL start (no filters - logging every advertiser) ===");
        }
        ScanSettings st = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        scanning = true;
        scanAll = unfiltered;
        try {
            scanner.startScan(filters, st, scanCb);
        } catch (SecurityException e) {
            log("!! SecurityException on startScan: " + e.getMessage());
            log("   -> permission missing. Grant Nearby devices in app settings.");
            scanning = false; return;
        }
        ui.postDelayed(new ScanTimeout(), 12000);
    }

    /**
     * Connect straight to a known MAC, with no scan at all.
     * Needs only BLUETOOTH_CONNECT, so it works even when scanning is blocked.
     */
    private void connectByAddress() {
        if (adapter == null || !adapter.isEnabled()) { toast("Enable Bluetooth first"); return; }
        String mac = macField.getText().toString().trim().toUpperCase(Locale.US);
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            log("!! not a valid MAC: " + mac); toast("Bad MAC"); return;
        }
        log("=== CONNECT BY ADDRESS " + mac + " (no scan) ===");
        if (Build.VERSION.SDK_INT >= 31)
            log("  BLUETOOTH_CONNECT: " + permState(Manifest.permission.BLUETOOTH_CONNECT));
        try {
            connectAddr(mac, 0);
        } catch (Exception e) {
            log("!! getRemoteDevice failed: " + e);
        }
    }

    private void stopScan() {
        if (!scanning || scanner == null) return;
        scanning = false;
        try { scanner.stopScan(scanCb); } catch (SecurityException ignored) { }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            String name = "";
            try {
                if (r.getScanRecord() != null && r.getScanRecord().getDeviceName() != null)
                    name = r.getScanRecord().getDeviceName();
                else if (r.getDevice().getName() != null)
                    name = r.getDevice().getName();
            } catch (SecurityException ignored) { }

            StringBuilder sb = new StringBuilder();
            sb.append("FOUND '").append(name).append("' @ ")
              .append(r.getDevice().getAddress()).append(" rssi=").append(r.getRssi());
            if (r.getScanRecord() != null) {
                if (r.getScanRecord().getServiceUuids() != null)
                    sb.append(" svc=").append(r.getScanRecord().getServiceUuids());
                byte[] raw = r.getScanRecord().getBytes();
                if (raw != null) sb.append("\n           adv_raw=").append(hex(raw));
            }
            log(sb.toString());
            if (scanAll) return;      // survey mode: log everything, connect to nothing
            stopScan();
            connect(r.getDevice());
        }
        @Override public void onScanFailed(int code) {
            scanning = false;
            log("!! scan failed, code=" + code);
        }
    };

    // --------------------------------------------------------------- connect

    /**
     * Connect by address, on the MAIN thread, after a settle delay.
     *
     * All three details matter and each alone causes status=133:
     *  - the scan callback runs on a binder thread; connectGatt must not be
     *    called from it
     *  - the stack needs a moment after the scan stops before a connect
     *    (a short pause is enough on most phones; 500 ms is safer on Samsung)
     *  - a BluetoothDevice from a ScanResult can be stale; re-resolving the
     *    address through the adapter avoids that
     */
    private void connect(BluetoothDevice dev) {
        connectAddr(dev.getAddress(), 0);
    }

    private class ConnectLater implements Runnable {
        private final String mac; private final int attempt;
        ConnectLater(String m, int a) { mac = m; attempt = a; }
        @Override public void run() { doConnect(mac, attempt); }
    }

    private void connectAddr(String mac, int attempt) {
        connectMac = mac;
        long delay = (attempt == 0) ? 500 : (600L * (attempt + 1));
        log("=== CONNECT " + mac + " in " + delay + "ms (attempt "
            + (attempt + 1) + "/3, main thread) ===");
        ui.postDelayed(new ConnectLater(mac, attempt), delay);
    }

    private void doConnect(String mac, int attempt) {
        closeGatt();
        connectAttempt = attempt;
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(mac);
            log("connectGatt autoConnect=false TRANSPORT_LE");
            gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) log("!! connectGatt returned null");
        } catch (SecurityException e) {
            log("!! SecurityException on connectGatt: " + e.getMessage());
        } catch (Exception e) {
            log("!! connectGatt failed: " + e);
        }
    }

    /** Always close before reconnecting; a leaked client interface causes 133. */
    private void closeGatt() {
        if (gatt != null) {
            try { gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
        chWrite = null; chNotify = null;
    }

    private void disconnect() {
        if (gatt == null) return;
        log("=== DISCONNECT ===");
        try { gatt.disconnect(); } catch (SecurityException ignored) { }
        closeGatt();
    }

    private class CadenceCheck implements Runnable {
        @Override public void run() {
            if (rxCount == 0)
                log("[probe P2] NO unsolicited telemetry 5 s after subscribe."
                    + " For the pump this contradicts the static analysis.");
            else
                log("[probe P2] " + rxCount + " frame(s) in the first 5 s"
                    + " - the device streams on subscribe, unprompted.");
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {

        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            log("onConnectionStateChange status=" + status + " newState=" + newState
                + (newState == 2 ? " CONNECTED" : (newState == 0 ? " DISCONNECTED" : "")));
            if (status != 0) {
                setStatus("CONNECT FAILED", "status " + status + " - retrying");
                log("!! status " + status
                    + (status == 133 ? " = GATT_ERROR (generic connect failure)" : ""));
                closeGatt();
                if (connectAttempt < 2 && connectMac != null) {
                    log("   retrying with a longer settle delay ...");
                    connectAddr(connectMac, connectAttempt + 1);
                } else {
                    log("   3 attempts failed. Most likely causes, in order:");
                    log("    1. Another app that talks to the pump is holding the connection.");
                    log("       Force-stop it (Settings > Apps > that app > Force stop),");
                    log("       and turn off its background activity, then retry.");
                    log("    2. The pump is already connected to another phone/tablet.");
                    log("    3. Pump asleep - press its button to wake it, then retry.");
                    log("    4. Stale bond: Settings > Bluetooth > forget ZD21_PUMP.");
                }
                return;
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                setStatus("CONNECTED", "Ready - tap RUN COMPLETE TEST");
                log("requesting MTU 240");
                try { g.requestMtu(240); } catch (SecurityException ignored) { }
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            log("onMtuChanged mtu=" + mtu + " status=" + status
                + (mtu >= 207 ? "  >=207: 204-byte history packets are possible"
                              : "  <207: 204-byte history CANNOT arrive"));
            try { g.discoverServices(); } catch (SecurityException ignored) { }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            log("onServicesDiscovered status=" + status);
            log("===== FULL GATT TREE AS THE DEVICE PRESENTS IT (probe P12) =====");
            List<BluetoothGattService> svcs = g.getServices();
            for (int i = 0; i < svcs.size(); i++) {
                BluetoothGattService s = svcs.get(i);
                log("  service " + s.getUuid());
                List<BluetoothGattCharacteristic> cs = s.getCharacteristics();
                for (int j = 0; j < cs.size(); j++) {
                    BluetoothGattCharacteristic c = cs.get(j);
                    log("    char " + c.getUuid() + " props=0x"
                        + Integer.toHexString(c.getProperties())
                        + " [" + propNames(c.getProperties()) + "]");
                    List<BluetoothGattDescriptor> ds = c.getDescriptors();
                    for (int k = 0; k < ds.size(); k++)
                        log("      desc " + ds.get(k).getUuid());
                }
            }
            log("================================================================");

            BluetoothGattService svc = g.getService(SVC);
            if (svc == null) { log("!! service FFF0 NOT FOUND"); return; }
            chWrite  = svc.getCharacteristic(CH_W);
            chNotify = svc.getCharacteristic(CH_N);
            log("write char FFF1 present: " + (chWrite != null));
            log("notify char FFF4 present: " + (chNotify != null));
            if (chNotify == null) return;

            try {
                boolean ok = g.setCharacteristicNotification(chNotify, true);
                log("setCharacteristicNotification -> " + ok);
                BluetoothGattDescriptor d = chNotify.getDescriptor(CCCD);
                if (d == null) { log("!! no CCCD (0x2902) on FFF4"); return; }
                int p = chNotify.getProperties();
                boolean notify = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                byte[] val = notify
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                log("writing CCCD " + hex(val) + (notify ? "  (NOTIFY)" : "  (INDICATE)"));
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, val);
                } else {
                    d.setValue(val);
                    g.writeDescriptor(d);
                }
            } catch (SecurityException e) {
                log("!! SecurityException enabling notifications: " + e.getMessage());
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g,
                BluetoothGattDescriptor d, int status) {
            log("onDescriptorWrite " + d.getUuid() + " status=" + status
                + (status == 0 ? "  SUBSCRIBED - telemetry should now start unprompted" : ""));
            firstRx = 0; rxCount = 0;
            ui.postDelayed(new CadenceCheck(), 5000);
            disIndex = 0;
            readNextDis(g);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g,
                BluetoothGattCharacteristic c, int status) {
            log("onCharacteristicWrite " + short16(c.getUuid()) + " status=" + status);
        }

        @Override public void onCharacteristicRead(BluetoothGatt g,
                BluetoothGattCharacteristic c, byte[] value, int status) {
            if (disIndex >= 0) onDisRead(g, c, value, status);
        }

        @Override public void onCharacteristicRead(BluetoothGatt g,
                BluetoothGattCharacteristic c, int status) {
            if (disIndex >= 0) onDisRead(g, c, c.getValue(), status);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g,
                BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v != null) onFrame(c.getUuid(), v);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g,
                BluetoothGattCharacteristic c, byte[] value) {
            onFrame(c.getUuid(), value);
        }
    };

    /** Read the DIS characteristics one at a time; BLE allows only one in flight. */
    private void readNextDis(BluetoothGatt g) {
        if (disIndex < 0 || disIndex >= DIS.length) {
            if (disIndex >= DIS.length) {
                log("===== END DEVICE INFORMATION =====");
                disIndex = -1;
            }
            return;
        }
        BluetoothGattService dis = g.getService(
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"));
        if (dis == null) { log("no 0x180A service"); disIndex = -1; return; }
        if (disIndex == 0) log("===== DEVICE INFORMATION (0x180A) =====");
        BluetoothGattCharacteristic c = dis.getCharacteristic(
            UUID.fromString(DIS[disIndex][0] + "-0000-1000-8000-00805f9b34fb"));
        if (c == null) { disIndex++; readNextDis(g); return; }
        try {
            if (!g.readCharacteristic(c)) { disIndex++; readNextDis(g); }
        } catch (SecurityException e) { disIndex = -1; }
    }

    private void onDisRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v, int status) {
        String label = "?";
        String u = c.getUuid().toString().substring(0, 8);
        for (int i = 0; i < DIS.length; i++) if (DIS[i][0].equals(u)) label = DIS[i][1];
        if (status == 0 && v != null)
            log(String.format(Locale.US, "  %-14s %-24s  hex %s",
                label, "'" + ascii(v).trim() + "'", hex(v)));
        else
            log("  " + label + " read failed status=" + status);
        disIndex++;
        readNextDis(g);
    }

    private static String propNames(int p) {
        StringBuilder sb = new StringBuilder();
        if ((p & 0x02) != 0) sb.append("READ ");
        if ((p & 0x04) != 0) sb.append("WRITE_NR ");
        if ((p & 0x08) != 0) sb.append("WRITE ");
        if ((p & 0x10) != 0) sb.append("NOTIFY ");
        if ((p & 0x20) != 0) sb.append("INDICATE ");
        return sb.toString().trim();
    }

    private static String short16(UUID u) {
        return "0x" + u.toString().substring(4, 8).toUpperCase(Locale.US);
    }

    // ------------------------------------------------------------- rx decode

    private void onFrame(UUID from, byte[] v) {
        long now = System.currentTimeMillis();
        rxCount++;
        if (firstRx == 0) firstRx = now;
        long gap = (lastRx == 0) ? 0 : (now - lastRx);
        lastRx = now;

        StringBuilder sb = new StringBuilder();
        sb.append("RX ").append(short16(from)).append(" len=").append(v.length);
        if (gap > 0) sb.append(" (+").append(gap).append("ms)");
        sb.append("\n           hex: ").append(hex(v));
        if (v.length <= 64) sb.append("\n           txt: ").append(ascii(v));
        observe(v);          // feed the self-test / capability runners
        sb.append("\n           ").append(decode(v));

        if (rxCount % 10 == 0 && firstRx != now) {
            double hz = rxCount * 1000.0 / (now - firstRx);
            sb.append(String.format(Locale.US,
                "\n           [cadence] %.2f Hz over %d frames", hz, rxCount));
        }
        log(sb.toString());
    }

    /** Feed the self-test runner whatever this frame reveals. */
    private void observe(byte[] v) {
        if (v == null || v.length == 0) return;
        int op = v[0] & 0xFF;
        if (v.length == 204) { histSeen++; return; }
        if (op == 0x5A) { setTimeAckSeen = true; return; }
        if (op == 0xF0) { versionSeen = true; return; }
        if (v.length == 2 && (v[1] & 0xFF) == 0x01) {
            stepAcks.add(Integer.valueOf(op)); return;
        }
        if (op == 0x29) {
            dumpSeen = true; lastDump.clear();
            for (int i = 1; i + 5 <= v.length; i += 5) {
                int sp = v[i] & 0xFF;
                lastDump.add(((sp * 100 + 127) / 255) + "%|" + (v[i+1] & 0xFF) + "|"
                    + (v[i+2] & 0xFF) + "|" + (v[i+3] & 0xFF) + "|" + (v[i+4] & 0xFF));
            }
            return;
        }
        if (op == 0x23) {
            String txt = new String(v, StandardCharsets.US_ASCII);
            String[] p = txt.split(",");
            if (p.length >= 4) {
                stepMode = p[0].replace("#", "");
                try {
                    double kpa = Math.abs(Integer.parseInt(p[1].trim())) / 10.0;
                    lastKpa = kpa;
                    if (kpa > stepPmax) stepPmax = kpa;
                    if (kpa < stepPmin) stepPmin = kpa;
                    // during the decay window, remember the last non-zero reading
                    if (testRunning && testStep == T_DECAY && kpa > 0) {
                        decayPend = kpa; decayTend = System.currentTimeMillis();
                    }
                    setPressure(kpa, stepMode);
                    if (sealRunning && sealSeries.size() < 400) {
                        sealSeries.add(new long[]{
                            System.currentTimeMillis() - sealT0, (long) (kpa * 100)});
                    }
                    if (capRunning && capSeries.size() < 900) {
                        capSeries.add(new long[]{
                            System.currentTimeMillis() - capT0, (long) (kpa * 100)});
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
    }

    private String decode(byte[] v) {
        if (v.length == 0) return "EMPTY frame";
        int op = v[0] & 0xFF;

        if (v.length == 204) {
            historyChunks++;
            historyFirstBytes.add(String.format("0x%02X", op));
            StringBuilder s = new StringBuilder("HISTORY chunk #" + historyChunks
                + " first byte=0x" + String.format("%02X", op));
            if (historyChunks % 3 == 0) {
                s.append("\n           [HISTORY.md 3] first bytes: ").append(historyFirstBytes);
                boolean all24 = true;
                for (int i = 0; i < historyFirstBytes.size(); i++)
                    if (!historyFirstBytes.get(i).equals("0x24")) all24 = false;
                s.append(all24
                    ? "\n           >> ALL 0x24: the opcode IS inside the buffer, so the"
                      + " app's 12-byte header parse is misaligned (Reading A)"
                    : "\n           >> not all 0x24: no opcode prefix, header starts at"
                      + " offset 0 (Reading B)");
            }
            return s.toString();
        }

        if (op == 0x23) {
            String txt = new String(v, StandardCharsets.US_ASCII);
            String[] p = txt.split(",");
            if (p.length >= 4) {
                try {
                    int raw = Integer.parseInt(p[1].trim());
                    int sp = Integer.parseInt(p[2].trim());
                    return String.format(Locale.US,
                        "TELEMETRY ascii mode=%s | [probe P1] raw=%d -> %.1f kPa if deci"
                        + "  OR  %d kPa if whole | speed=%d -> %d%%",
                        p[0].replace("#", ""), raw, Math.abs(raw) / 10.0,
                        Math.abs(raw), sp, (sp * 100 + 127) / 255);
                } catch (NumberFormatException ignored) { }
            }
            String body = txt.substring(1).trim();
            try {
                double d = Double.parseDouble(body);
                return String.format(Locale.US,
                    "TENSION ascii '%s' x0.1 = %.1f kg (%.2f lb)",
                    body, d * 0.1, d * 0.1 * 2.2046226218);
            } catch (NumberFormatException e) {
                return "0x23 ASCII unparsed: " + txt;
            }
        }

        if (op == 0x25 && v.length >= 5) {
            int be = ((v[2] & 0xFF) << 8) | (v[3] & 0xFF);
            return String.format(Locale.US,
                "TELEMETRY binary mode=%d pressure_BE=%d kPa speed=%d -> %d%%",
                (int) v[1], be, v[4] & 0xFF, ((v[4] & 0xFF) * 100 + 127) / 255);
        }

        if (op == 0x29) {
            if (v.length == 1) return "WORK-MODE DUMP: empty, no presets configured";
            StringBuilder sb = new StringBuilder("WORK-MODE DUMP ("
                + ((v.length - 1) / 5) + " slot(s))");
            for (int i = 1; i + 5 <= v.length; i += 5) {
                sb.append(String.format(Locale.US,
                    "\n           slot%d: speed=%d(%d%%) upper=%dkPa/%ds lower=%dkPa/%ds",
                    (i - 1) / 5, v[i] & 0xFF, ((v[i] & 0xFF) * 100 + 127) / 255,
                    v[i+1] & 0xFF, v[i+2] & 0xFF, v[i+3] & 0xFF, v[i+4] & 0xFF));
            }
            return sb.toString();
        }

        if (op == 0x5A)
            return "SET-TIME ACK (the app accepts it: "
                 + (v.length == 4 && v[3] == 1) + ")";

        if (op == 0xF0)
            return "VERSION RESPONSE payload='"
                 + ascii(Arrays.copyOfRange(v, 1, v.length)) + "'";

        if (v.length == 2 && (v[1] & 0xFF) == 0x01) {
            String name = (op == 0x2B) ? "Add"
                        : (op == 0x2C) ? "Start"
                        : (op == 0x2D) ? "StopWork"
                        : (op == 0x2A) ? "Delete" : ("op 0x" + String.format("%02X", op));
            return "ACK " + name + " status=OK  (<opcode> 01)";
        }
        return "!! UNKNOWN opcode 0x" + String.format("%02X", op)
             + " - undocumented frame type, this is worth capturing";
    }

    // ------------------------------------------------------------------- tx

    private static byte[] cmd(int op) { return new byte[]{0x66, 0x2A, (byte) op}; }

    private void tx(byte[] frame, String what) {
        if (gatt == null || chWrite == null) { toast("Not connected"); return; }
        log("TX " + what + ": " + hex(frame));
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int r = gatt.writeCharacteristic(chWrite, frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                if (r != BluetoothGatt.GATT_SUCCESS) log("   writeCharacteristic -> " + r);
            } else {
                chWrite.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                chWrite.setValue(frame);
                boolean ok = gatt.writeCharacteristic(chWrite);
                if (!ok) log("   writeCharacteristic returned false");
            }
        } catch (SecurityException e) {
            log("!! SecurityException on write: " + e.getMessage());
        }
    }

    private void txVersion() {
        byte[] s = "getversion".getBytes(StandardCharsets.US_ASCII);
        byte[] f = new byte[2 + s.length];
        f[0] = 0x66; f[1] = 0x2A;
        System.arraycopy(s, 0, f, 2, s.length);
        tx(f, "GetVersion");
    }

    private void txSetTime() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        int y = c.get(Calendar.YEAR);
        tx(new byte[]{0x66, 0x2A, 0x25,
            (byte) (y & 0xFF), (byte) ((y >> 8) & 0xFF),
            (byte) (c.get(Calendar.MONTH) + 1), (byte) c.get(Calendar.DAY_OF_MONTH),
            (byte) c.get(Calendar.HOUR_OF_DAY), (byte) c.get(Calendar.MINUTE),
            (byte) c.get(Calendar.SECOND)}, "SetTime UTC");
    }

    /** Release = commanded zero-pressure program, then StopWork. */
    private void release() {
        log("=== RELEASE: zero-pressure program, then StopWork ===");
        tx(new byte[]{0x66, 0x2A, 0x2B, (byte) 0x73, 0x00, 0x00, 0x00, 0x00},
           "add zero preset");
        ui.postDelayed(new Later(new byte[]{0x66, 0x2A, 0x2C, 0x00},
                                 "start zero preset", 0), 400);
        ui.postDelayed(new Later(cmd(0x2D), "StopWork", 0), 900);
    }

    /**
     * Probe: does StopWork vent, or only stop the motor?
     * Decides the safety design AND whether leak-decay cycling is possible.
     */
    private void stopVentTest() {
        log("=== STOP-VENT TEST ===");
        log("t+0s   starting slot 0 to build vacuum");
        tx(new byte[]{0x66, 0x2A, 0x2C, 0x00}, "Start slot 0");
        ui.postDelayed(new Later(cmd(0x2D), "StopWork", 1), 6000);
        ui.postDelayed(new Later(null, null, 2), 36000);
    }

    // ------------------------------------------------------- log delivery

    /**
     * POST the current log file to serve.py on the dev PC.
     * Needs the phone and PC on the same LAN. Runs off the UI thread.
     */
    private void uploadLog() {
        final String host = pcField.getText().toString().trim();
        if (host.length() == 0) { toast("Enter pc-ip:port"); return; }
        final String path = logPath;
        log("=== UPLOAD LOG -> http://" + host + "/upload ===");
        new Thread(new Runnable() { @Override public void run() {
            java.io.RandomAccessFile raf = null;
            java.net.HttpURLConnection c = null;
            try {
                java.io.File f = new java.io.File(path);
                byte[] buf = new byte[(int) f.length()];
                raf = new java.io.RandomAccessFile(f, "r");
                raf.readFully(buf);

                java.net.URL u = new java.net.URL("http://" + host + "/upload?name="
                    + java.net.URLEncoder.encode(f.getName(), "UTF-8"));
                c = (java.net.HttpURLConnection) u.openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setFixedLengthStreamingMode(buf.length);
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                java.io.OutputStream os = c.getOutputStream();
                os.write(buf); os.flush(); os.close();
                int code = c.getResponseCode();
                log("upload HTTP " + code + " (" + buf.length + " bytes)");
                if (code != 200)
                    log("   is serve.py running on the PC? same Wi-Fi? VPN off?");
            } catch (Exception e) {
                log("!! upload failed: " + e);
                log("   check: serve.py running, same Wi-Fi, VPN off, correct ip:port");
            } finally {
                try { if (raf != null) raf.close(); } catch (Exception ignored) { }
                if (c != null) c.disconnect();
            }
        } }).start();
    }

    /**
     * Append a self-contained diagnostic footer, then hand the file to WhatsApp
     * (or the full share sheet). The footer matters: whoever reads this log must
     * be able to work from it alone, with nothing recalled from the session.
     */
    private void shareLog(boolean whatsappFirst) {
        try {
            writeSummary();
            if (logFile != null) logFile.flush();

            java.io.File f = new java.io.File(logPath);
            // content:// via our own provider. file:// throws FileUriExposedException
            // on API 24+, and WhatsApp will not attach it.
            android.net.Uri uri = new android.net.Uri.Builder()
                .scheme("content").authority(LogProvider.AUTHORITY)
                .appendPath(f.getName()).build();

            android.content.Intent i = new android.content.Intent(
                android.content.Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            i.putExtra(android.content.Intent.EXTRA_SUBJECT, "OpenPump " + f.getName());
            i.putExtra(android.content.Intent.EXTRA_TEXT, "OpenPump log " + f.getName());
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (whatsappFirst) {
                i.setPackage("com.whatsapp");
                try {
                    startActivity(i);
                    log("shared to WhatsApp: " + f.getName() + " (" + f.length() + " bytes)");
                    return;
                } catch (android.content.ActivityNotFoundException nf) {
                    log("WhatsApp not installed, falling back to the share sheet");
                    i.setPackage(null);
                }
            }
            startActivity(android.content.Intent.createChooser(i, "Send log"));
            log("share sheet opened for " + f.getName() + " (" + f.length() + " bytes)");
        } catch (Exception e) {
            log("!! share failed: " + e);
        }
    }

    /**
     * Everything a reader needs that is not already obvious from the frame log:
     * build identity, device, permission and adapter state, connection state,
     * traffic counts, cadence, and the current answer to each open probe.
     */
    private void writeSummary() {
        log("");
        log("================ SESSION SUMMARY ================");
        String vn = "?"; int vc = -1;
        try {
            android.content.pm.PackageInfo pi =
                getPackageManager().getPackageInfo(getPackageName(), 0);
            vn = pi.versionName; vc = pi.versionCode;
        } catch (Exception ignored) { }
        log("build            : " + vn + " (code " + vc + ")");
        log("device           : " + Build.MANUFACTURER + " " + Build.MODEL
            + "  Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        log("time             : " + new java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));
        log("bt adapter       : " + (adapter == null ? "none"
            : (adapter.isEnabled() ? "enabled" : "DISABLED")));
        if (Build.VERSION.SDK_INT >= 31) {
            log("perm BT_SCAN     : " + permState(Manifest.permission.BLUETOOTH_SCAN));
            log("perm BT_CONNECT  : " + permState(Manifest.permission.BLUETOOTH_CONNECT));
        } else {
            log("perm FINE_LOC    : " + permState(Manifest.permission.ACCESS_FINE_LOCATION));
        }
        log("gatt             : " + (gatt == null ? "not connected" : "connected"));
        log("write char FFF1  : " + (chWrite != null));
        log("notify char FFF4 : " + (chNotify != null));
        log("frames received  : " + rxCount);
        if (rxCount > 1 && lastRx > firstRx) {
            double hz = rxCount * 1000.0 / (lastRx - firstRx);
            log(String.format(Locale.US, "cadence          : %.2f Hz over %.1f s",
                hz, (lastRx - firstRx) / 1000.0));
        } else {
            log("cadence          : n/a (need >1 frame)");
        }
        log("history chunks   : " + historyChunks
            + (historyFirstBytes.isEmpty() ? "" : "  first bytes " + historyFirstBytes));
        log("");
        log("OPEN PROBES - what this session answered:");
        log("  P1 pressure scale : see any 'TELEMETRY ascii' line above; it prints");
        log("                      the value under BOTH deci-kPa and whole-kPa.");
        log("  P2 cadence        : " + (rxCount == 0
            ? "NO telemetry received"
            : rxCount + " frames, see cadence above"));
        log("  P12 GATT tree     : " + (chNotify != null
            ? "captured above under FULL GATT TREE"
            : "not captured - never reached service discovery"));
        log("  history alignment : " + (historyChunks == 0
            ? "no 204-byte chunks seen"
            : "first bytes " + historyFirstBytes));
        log("================ END OF SUMMARY ================");
        log("");
    }

    private String permState(String p) {
        try {
            return (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED)
                ? "granted" : "DENIED";
        } catch (Exception e) { return "?"; }
    }


    // ==================================================================
    //  FULL SELF-TEST
    //  Scripted sequence exercising every command, each step CHECKING its own
    //  result, so one run yields a complete pass/fail verdict.
    // ==================================================================

    private static final int T_BASELINE=0, T_DUMP1=1, T_ADD=2, T_VERIFY_ADD=3,
        T_START=4, T_LOAD=5, T_STOP=6, T_DECAY=7, T_DELETE=8, T_VERIFY_DEL=9,
        T_VERSION=10, T_SETTIME=11, T_HISTORY=12, T_RELEASE=13, T_DONE=14;

    // Test preset written during the run then deleted. Deliberately distinctive.
    private static final int TP_SPEED_PCT = 60, TP_UPPER = 20, TP_UHOLD = 5,
                             TP_LOWER = 8,  TP_LHOLD = 3;
    private int dumpCountBefore = -1;
    private double lastKpa = -1;

    private class AddTestPreset implements Runnable {
        @Override public void run() {
            tx(new byte[]{0x66, 0x2A, 0x2B, (byte) speedPctToCode(TP_SPEED_PCT),
                (byte) TP_UPPER, (byte) TP_UHOLD, (byte) TP_LOWER, (byte) TP_LHOLD},
                "Add(test)");
        }
    }

    private class TestTick implements Runnable {
        @Override public void run() { runTestStep(); }
    }

    private void startFullTest() {
        if (gatt == null || chWrite == null) { toast("Connect first"); return; }
        if (testRunning) { log("self-test already running"); return; }
        testRunning = true; testStep = 0; testResults.clear();
        dumpCountBefore = -1; histSeen = 0;
        versionSeen = false; setTimeAckSeen = false;
        decayP0 = -1; decayPend = -1;
        log("");
        log("################################################################");
        log("#              PUMPDEBUG FULL SELF-TEST                        #");
        log("################################################################");
        beginStep();
    }

    private void beginStep() {
        stepRxStart = rxCount; stepPmax = -1; stepPmin = 1e9;
        stepMode = ""; stepAcks.clear(); dumpSeen = false;
        lastDump.clear();
        setStatus(null, "Phase 1 - protocol step " + (testStep + 1) + " of " + T_DONE);
        long dwell = doTestAction(testStep);
        ui.postDelayed(new TestTick(), dwell);
    }

    private long doTestAction(int step) {
        switch (step) {
        case T_BASELINE:
            log("");
            log("[1] BASELINE - 6 s idle, expect unprompted telemetry");
            return 6000;
        case T_DUMP1:
            log("");
            log("[2] GET WORK MODES - baseline preset table");
            tx(cmd(0x29), "List"); return 3000;
        case T_ADD:
            log("");
            log("[3] ADD WORK MODE - test preset " + TP_SPEED_PCT + "% "
                + TP_UPPER + "/" + TP_UHOLD + "s " + TP_LOWER + "/" + TP_LHOLD + "s");
            log("      clearing the table first: a full table (9 slots) makes the");
            log("      device silently discard the write with no error");
            clearAllSlots();
            ui.postDelayed(new AddTestPreset(), 2200);
            return 7000;
        case T_VERIFY_ADD:
            log("");
            log("[4] VERIFY ADD - re-read the table, expect the test preset");
            tx(cmd(0x29), "List"); return 3000;
        case T_START:
            log("");
            log("[5] START PRESET slot 0 - PUMP RUNS NOW");
            tx(new byte[]{0x66, 0x2A, 0x2C, 0x00}, "Start slot 0"); return 3000;
        case T_LOAD:
            log("");
            log("[6] UNDER LOAD - 12 s, measuring pressure and cadence");
            return 12000;
        case T_STOP:
            log("");
            log("[7] STOP WORK");
            decayP0 = (lastKpa >= 0 ? lastKpa : -1);
            decayT0 = System.currentTimeMillis();
            tx(cmd(0x2D), "StopWork"); return 2000;
        case T_DECAY:
            log("");
            log("[8] DECAY - 20 s. Sealed port: fast collapse = VENTS,"
                + " slow exponential = HOLDS");
            return 20000;
        case T_DELETE:
            log("");
            log("[9] DELETE WORK MODE slot 0");
            tx(new byte[]{0x66, 0x2A, 0x2A, 0x00}, "Delete(0)"); return 4000;
        case T_VERIFY_DEL:
            log("");
            log("[10] VERIFY DELETE - re-read the table");
            tx(cmd(0x29), "List"); return 3000;
        case T_VERSION:
            log("");
            log("[11] GET VERSION - a 0xF0 reply if the pump answers; no answer is known");
            txVersion(); return 4000;
        case T_SETTIME:
            log("");
            log("[12] SET TIME - expect 5A A5 25 01, or nothing");
            txSetTime(); return 4000;
        case T_HISTORY:
            log("");
            log("[13] GET HISTORY - expect 204-byte chunks, or nothing");
            tx(cmd(0x24), "GetHistory"); return 8000;
        case T_RELEASE:
            log("");
            log("[14] RELEASE - zero-pressure program then stop");
            release(); return 4000;
        default:
            return 0;
        }
    }

    private void rec(String name, boolean pass, String detail) {
        testResults.add((pass ? "PASS  " : "FAIL  ") + name + "  :: " + detail);
        log("      " + (pass ? "PASS" : "FAIL") + " - " + detail);
    }

    private void runTestStep() {
        int rx = rxCount - stepRxStart;
        switch (testStep) {
        case T_BASELINE:
            rec("telemetry_unprompted", rx > 0,
                rx + " frames in 6 s, mode='" + stepMode + "'");
            rec("cadence", rx >= 12, String.format(Locale.US, "%.2f Hz", rx / 6.0));
            break;
        case T_DUMP1:
            dumpCountBefore = lastDump.size();
            rec("get_work_modes", dumpSeen, dumpSeen
                ? (lastDump.size() + " slots: " + lastDump) : "no 0x29 dump received");
            break;
        case T_ADD:
            rec("add_work_mode_ack", stepAcks.contains(Integer.valueOf(0x2B)) || dumpSeen,
                stepAcks.contains(Integer.valueOf(0x2B)) ? "ACK 2B 01"
                    : (dumpSeen ? "answered with a full dump" : "no ack seen"));
            break;
        case T_VERIFY_ADD: {
            String want = TP_SPEED_PCT + "%|" + TP_UPPER + "|" + TP_UHOLD + "|"
                        + TP_LOWER + "|" + TP_LHOLD;
            boolean found = false;
            for (int i = 0; i < lastDump.size(); i++)
                if (lastDump.get(i).contains(want)) found = true;
            rec("preset_roundtrip", found,
                found ? ("test preset read back exactly: " + want)
                      : ("wanted " + want + " but table = " + lastDump));
            break; }
        case T_START:
            rec("start_preset_ack",
                stepAcks.contains(Integer.valueOf(0x2C)) || "AUTO".equals(stepMode),
                "acks=" + stepAcks + " mode='" + stepMode + "'");
            break;
        case T_LOAD:
            rec("pressure_under_load", stepPmax > 0,
                String.format(Locale.US,
                    "max %.1f kPa, min %.1f kPa, %d frames (%.2f Hz)",
                    stepPmax, (stepPmin > 1e8 ? 0 : stepPmin), rx, rx / 12.0));
            rec("pressure_scale_sane", stepPmax <= 101.0,
                String.format(Locale.US,
                    "%.1f kPa deci-scaled; raw %.0f would be impossible as whole kPa",
                    stepPmax, stepPmax * 10));
            rec("mode_is_auto", "AUTO".equals(stepMode), "mode='" + stepMode + "'");
            break;
        case T_STOP:
            rec("stop_ack",
                stepAcks.contains(Integer.valueOf(0x2D)) || "Manual".equals(stepMode),
                "acks=" + stepAcks + " mode='" + stepMode + "'");
            break;
        case T_DECAY: {
            String verdict;
            boolean ok = decayP0 > 1 && decayPend >= 0;
            if (!ok) {
                verdict = "INCONCLUSIVE - no pressure held at StopWork; seal the port";
            } else {
                double dt = (decayTend - decayT0) / 1000.0;
                double rate = (decayP0 - decayPend) / (dt > 0 ? dt : 1);
                verdict = String.format(Locale.US,
                    "%.1f -> %.1f kPa in %.1f s = %.2f kPa/s : %s",
                    decayP0, decayPend, dt, rate,
                    rate > 3.0 ? "FAST => StopWork VENTS"
                               : "SLOW => StopWork HOLDS, leak-decay cycling possible");
            }
            rec("stopwork_vents_or_holds", ok, verdict);
            break; }
        case T_DELETE:
            rec("delete_work_mode_ack",
                stepAcks.contains(Integer.valueOf(0x2A)) || dumpSeen, "acks=" + stepAcks);
            break;
        case T_VERIFY_DEL:
            rec("delete_took_effect", dumpSeen,
                "table now " + lastDump.size() + " slots (was " + dumpCountBefore
                + "): " + lastDump);
            break;
        case T_VERSION:
            rec("get_version", versionSeen, versionSeen
                ? "0xF0 response received"
                : "no response - no answer to this has been seen");
            break;
        case T_SETTIME:
            rec("set_time_ack", setTimeAckSeen, setTimeAckSeen
                ? "5A A5 25 01" : "no ack - the app never sends SetTime to a pump");
            break;
        case T_HISTORY:
            rec("get_history", histSeen > 0, histSeen > 0
                ? (histSeen + " chunks, first bytes " + historyFirstBytes)
                : "no 204-byte chunks - history may be unimplemented on this device");
            break;
        case T_RELEASE:
            rec("release", true, String.format(Locale.US,
                "final pressure %.1f kPa", (lastKpa < 0 ? 0 : lastKpa)));
            break;
        }

        testStep++;
        if (testStep >= T_DONE) { finishTest(); return; }
        beginStep();
    }

    private void finishTest() {
        testRunning = false;
        log("");
        log("################################################################");
        log("#                    SELF-TEST RESULTS                         #");
        log("################################################################");
        int pass = 0;
        for (int i = 0; i < testResults.size(); i++) {
            log("  " + testResults.get(i));
            if (testResults.get(i).startsWith("PASS")) pass++;
        }
        log("");
        log("  " + pass + " / " + testResults.size() + " checks passed");
        log("################################################################");
        writeSummary();
        log("Protocol phase complete.");
        masterAdvance(1);
    }

    private static int speedPctToCode(int pct) {
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return (pct * 255 + 50) / 100;
    }


    // ==================================================================
    //  CAPABILITY / CHARACTERISATION TEST
    //  Measures what the pump can physically DO: how fast it builds and sheds
    //  vacuum, what each speed code is worth, the minimum cycle period, and how
    //  it behaves at parameter values outside what OpenPump normally sends.
    //  Output feeds routine/feature design directly.
    // ==================================================================

    private boolean capRunning = false;
    private int capStep = 0;
    private final List<String> capResults = new ArrayList<String>();
    // per-step time series (ms since step start, kPa)
    private final List<long[]> capSeries = new ArrayList<long[]>();
    private long capT0 = 0;

    /** speedPct, upperKpa, upperHold, lowerKpa, lowerHold, dwellMs, label */
    private static final Object[][] CAP_PLAN = {
        // ---- SPEED LADDER: is speed a real axis, and how far down does it go?
        // Long dwells: a clean first-order fit needs many samples at 4 Hz.
        {100, 25, 0, 0, 0, 22000, "speed 100% @25 - reference rise"},
        {90,  25, 0, 0, 0, 22000, "speed 90% @25"},
        {75,  25, 0, 0, 0, 22000, "speed 75% @25"},
        {60,  25, 0, 0, 0, 22000, "speed 60% @25"},
        {45,  25, 0, 0, 0, 22000, "speed 45% @25 (app floor)"},
        {30,  25, 0, 0, 0, 22000, "speed 30% BELOW floor"},
        {15,  25, 0, 0, 0, 22000, "speed 15% BELOW floor"},
        {8,   25, 0, 0, 0, 22000, "speed 8% - stall threshold?"},

        // ---- PRESSURE LADDER: work curve and the true ceiling
        {100,  5, 0, 0, 0, 16000, "target 5 kPa - low-end accuracy"},
        {100, 10, 0, 0, 0, 18000, "target 10 kPa"},
        {100, 20, 0, 0, 0, 20000, "target 20 kPa"},
        {100, 30, 0, 0, 0, 24000, "target 30 kPa"},
        {100, 40, 0, 0, 0, 28000, "target 40 kPa - approaching the ceiling"},
        {100, 55, 0, 0, 0, 32000, "target 55 kPa - max in the app envelope"},

        // ---- HOLD / COAST: leak rate vs depth (StopWork vents, so this is
        //      the only way to see a true leak)
        {100, 15, 60, 0, 0, 40000, "hold @15 for 60s - coast/leak at low depth"},
        {100, 30, 60, 0, 0, 45000, "hold @30 for 60s - coast/leak at high depth"},

        // ---- FALL: clean descent curve
        {100, 30, 2, 0, 30, 40000, "pull 30 then bleed to 0, long lower hold"},

        // ---- CYCLE LIMITS
        {100, 20, 0, 10, 0, 30000, "band 20/10, no holds - min cycle period"},
        {100, 12, 0, 4, 0, 30000, "band 12/4, no holds - faster cycle?"},
        {100, 20, 1, 19, 1, 24000, "narrow band 20/19 - micro-ripple"},
    };


    /**
     * Empty the device preset table.
     *
     * Delete compacts the table, so deleting index 0 nine times removes
     * every entry. This is required before any Add whose slot position
     * matters, because Add APPENDS - it takes no index - and the app can only
     * start a preset by index.
     */
    private void clearAllSlots() {
        for (int i = 0; i < MAX_SLOTS_DEV; i++) {
            ui.postDelayed(new DeleteZero(), i * 200L);
        }
    }

    private static final int MAX_SLOTS_DEV = 9;

    private class DeleteZero implements Runnable {
        @Override public void run() {
            tx(new byte[]{0x66, 0x2A, 0x2A, 0x00}, "Delete(0) [clear]");
        }
    }

    private class CapTick implements Runnable {
        @Override public void run() { runCapStep(); }
    }

    private void startCapTest() {
        if (gatt == null || chWrite == null) { toast("Connect first"); return; }
        if (capRunning) { log("capability test already running"); return; }
        capRunning = true; capStep = 0; capResults.clear();
        log("");
        log("################################################################");
        log("#           PUMP CAPABILITY / CHARACTERISATION TEST            #");
        log("#  Measures rise/fall rates, speed response, cycle limits.      #");
        log("#  Port MUST be sealed or every number here is meaningless.     #");
        log("################################################################");
        beginCapStep();
    }

    private void beginCapStep() {
        if (capStep >= CAP_PLAN.length) { finishCapTest(); return; }
        Object[] c = CAP_PLAN[capStep];
        int sp = ((Integer) c[0]).intValue(), up = ((Integer) c[1]).intValue();
        int uh = ((Integer) c[2]).intValue(), lo = ((Integer) c[3]).intValue();
        int lh = ((Integer) c[4]).intValue();
        long dwell = ((Integer) c[5]).longValue();

        capSeries.clear();
        capT0 = System.currentTimeMillis();
        log("");
        log("[C" + (capStep + 1) + "/" + CAP_PLAN.length + "] " + c[6]);
        setStatus(null, "Phase 2 - step " + (capStep + 1) + " of " + CAP_PLAN.length
            + ": " + c[6]);
        log("      preset: speed=" + sp + "% (code " + speedPctToCode(sp) + ")  "
            + up + "kPa/" + uh + "s  " + lo + "kPa/" + lh + "s");

        // Empty the whole table first. Deleting slot 0 repeatedly works because
        // deletion compacts: each delete shifts the rest down. Only with an
        // empty table does an appended preset become slot 0, which is what
        // 0x2C 00 will start.
        clearAllSlots();
        ui.postDelayed(new CapWrite(sp, up, uh, lo, lh), 2200);
        ui.postDelayed(new CapTick(), dwell);
    }

    private class CapWrite implements Runnable {
        private final int sp, up, uh, lo, lh;
        CapWrite(int a, int b, int c, int d, int e) { sp=a; up=b; uh=c; lo=d; lh=e; }
        @Override public void run() {
            tx(new byte[]{0x66, 0x2A, 0x2B, (byte) speedPctToCode(sp),
                (byte) up, (byte) uh, (byte) lo, (byte) lh}, "Add(cap)");
            ui.postDelayed(new CapStart(), 700);
        }
    }

    private class CapStart implements Runnable {
        @Override public void run() {
            capT0 = System.currentTimeMillis();
            capSeries.clear();
            tx(new byte[]{0x66, 0x2A, 0x2C, 0x00}, "Start slot 0");
        }
    }

    /**
     * Analyse this step's pressure curve and, crucially, DUMP THE RAW SERIES.
     * On-device maths is a convenience; the raw samples let the curve be fitted
     * properly off-device, which is what actually pins down the physical limits.
     */
    private void runCapStep() {
        tx(cmd(0x2D), "StopWork");

        int n = capSeries.size();
        Object[] c = CAP_PLAN[capStep];
        int targetUpper = ((Integer) c[1]).intValue();

        // ---- raw series, machine-parseable ----
        StringBuilder sb = new StringBuilder();
        sb.append("CAPDATA step=C").append(capStep + 1)
          .append(" speed=").append(((Integer) c[0]).intValue())
          .append(" upper=").append(targetUpper)
          .append(" uhold=").append(((Integer) c[2]).intValue())
          .append(" lower=").append(((Integer) c[3]).intValue())
          .append(" lhold=").append(((Integer) c[4]).intValue())
          .append(" n=").append(n).append(" series=");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(capSeries.get(i)[0]).append(':')
              .append(String.format(Locale.US, "%.1f", capSeries.get(i)[1] / 100.0));
        }
        log(sb.toString());

        // ---- metrics ----
        double pmax = 0;
        for (int i = 0; i < n; i++) {
            double v = capSeries.get(i)[1] / 100.0;
            if (v > pmax) pmax = v;
        }

        long deadMs = -1;                 // command -> first pressure movement
        long t63 = -1, t90 = -1;          // first-order time constant, and 90%
        double riseRate = 0, fallRate = 0;
        for (int i = 0; i < n; i++) {
            double v = capSeries.get(i)[1] / 100.0;
            long t = capSeries.get(i)[0];
            if (deadMs < 0 && v > 0.3) deadMs = t;
            if (t63 < 0 && pmax > 0.5 && v >= 0.632 * pmax) t63 = t;
            if (t90 < 0 && pmax > 0.5 && v >= 0.9 * pmax) t90 = t;
            if (i > 0) {
                double p0 = capSeries.get(i-1)[1] / 100.0;
                double dt = (t - capSeries.get(i-1)[0]) / 1000.0;
                if (dt > 0 && dt <= 1.5) {
                    double r = (v - p0) / dt;
                    if (r > riseRate) riseRate = r;
                    if (-r > fallRate) fallRate = -r;
                }
            }
        }

        // steady state = mean of the last third of the window
        double ss = 0; int ssn = 0;
        for (int i = (2 * n) / 3; i < n; i++) { ss += capSeries.get(i)[1] / 100.0; ssn++; }
        if (ssn > 0) ss /= ssn;

        double overshoot = (targetUpper > 0 && pmax > targetUpper)
            ? (pmax - targetUpper) * 100.0 / targetUpper : 0;
        double reached = (targetUpper > 0) ? pmax * 100.0 / targetUpper : 0;

        // cycle structure
        int peaks = 0; boolean above = false;
        long firstPeak = -1, lastPeak = -1;
        for (int i = 0; i < n; i++) {
            double v = capSeries.get(i)[1] / 100.0;
            if (!above && pmax > 0.5 && v >= 0.6 * pmax) {
                above = true; peaks++;
                if (firstPeak < 0) firstPeak = capSeries.get(i)[0];
                lastPeak = capSeries.get(i)[0];
            } else if (above && v < 0.4 * pmax) above = false;
        }
        double period = (peaks > 1 && lastPeak > firstPeak)
            ? (lastPeak - firstPeak) / 1000.0 / (peaks - 1) : -1;

        String r = String.format(Locale.US,
            "C%-2d %-40s peak %5.1f (%3.0f%% of target) ss %5.1f | rise %6.2f fall %6.2f kPa/s"
            + " | dead %5s t63 %5s t90 %5s s | over %3.0f%% | cycles %d period %s",
            capStep + 1, String.valueOf(c[6]), pmax, reached, ss, riseRate, fallRate,
            (deadMs >= 0 ? String.format(Locale.US, "%.2f", deadMs / 1000.0) : "-"),
            (t63 >= 0 ? String.format(Locale.US, "%.2f", t63 / 1000.0) : "-"),
            (t90 >= 0 ? String.format(Locale.US, "%.2f", t90 / 1000.0) : "-"),
            overshoot, peaks,
            (period > 0 ? String.format(Locale.US, "%.2f", period) : "-"));
        capResults.add(r);
        if (pmax > mPeakOverall) mPeakOverall = pmax;
        if (riseRate > mMaxRise) mMaxRise = riseRate;
        if (fallRate > mMaxFall) mMaxFall = fallRate;
        if (deadMs >= 0 && (mDead < 0 || deadMs / 1000.0 < mDead)) mDead = deadMs / 1000.0;
        if (period > 0 && (mMinPeriod < 0 || period < mMinPeriod)) mMinPeriod = period;
        // Key off the plan contents, not the index, so the plan can be edited
        // without silently mis-assigning metrics.
        int planSpeed = ((Integer) c[0]).intValue();
        int planUHold = ((Integer) c[2]).intValue();
        int planLower = ((Integer) c[3]).intValue();
        int planUpper = ((Integer) c[1]).intValue();
        if (planSpeed == 45 && planUHold == 0) mRise45 = riseRate;
        if (planSpeed == 100 && planUHold == 0 && planUpper == 25) mRise100 = riseRate;
        if (planSpeed == 30) mPeak25 = pmax;
        if (planSpeed == 8) mPeak10 = pmax;
        if (planUHold >= 60) { mHoldPeak = pmax; mHoldSS = ss; }
        if (planUpper - planLower == 1) mRipplePeak = pmax;
        if (planUHold == 0 && planLower > 0 && period > 0
            && (mBurstPeriod < 0 || period < mBurstPeriod)) mBurstPeriod = period;
        log("      peak " + String.format(Locale.US, "%.1f", pmax) + " kPa ("
            + String.format(Locale.US, "%.0f", reached) + "% of the "
            + targetUpper + " kPa target), rise "
            + String.format(Locale.US, "%.2f", riseRate) + " kPa/s, fall "
            + String.format(Locale.US, "%.2f", fallRate) + " kPa/s");
        if (pmax < 0.5)
            log("      !! no pressure developed - port unsealed, or the motor did not run");
        else if (reached < 70)
            log("      !! never reached target - pump at its limit, or leaking");

        capStep++;
        ui.postDelayed(new CapNext(), 3000);
    }

    private class CapNext implements Runnable {
        @Override public void run() { beginCapStep(); }
    }

    private void finishCapTest() {
        capRunning = false;
        tx(cmd(0x2D), "StopWork");
        log("");
        log("################################################################");
        log("#              CAPABILITY TEST RESULTS                         #");
        log("################################################################");
        for (int i = 0; i < capResults.size(); i++) log("  " + capResults.get(i));
        log("");
        log("  Read as: rise/fall = fastest observed dP/dt, which bounds how");
        log("  quickly any routine can change pressure. t90 = time to 90% of peak.");
        log("  period = measured cycle time, the floor on burst-train rate.");
        log("################################################################");
        log("Capability phase complete.");
        masterAdvance(2);
    }


    // ==================================================================
    //  MASTER RUN - one click, everything, ending in a feature matrix
    //  Phase 1 protocol correctness -> Phase 2 physical characterisation
    //  -> Phase 3 feature feasibility verdicts.
    // ==================================================================

    private boolean masterRunning = false;

    // metrics harvested from the capability phase, used to judge features
    private double mMaxRise = 0, mMaxFall = 0, mMinPeriod = -1, mDead = -1;
    private double mRise45 = 0, mRise100 = 0, mPeak25 = 0, mPeak10 = 0;
    private double mHoldPeak = 0, mHoldSS = 0, mRipplePeak = 0, mBurstPeriod = -1;
    private double mPeakOverall = 0;

    private void startMaster() {
        if (gatt == null || chWrite == null) { toast("Connect first"); return; }
        if (masterRunning || testRunning || capRunning) { log("already running"); return; }
        masterRunning = true;
        mMaxRise = mMaxFall = 0; mMinPeriod = -1; mDead = -1;
        mRise45 = mRise100 = mPeak25 = mPeak10 = 0;
        mHoldPeak = mHoldSS = mRipplePeak = 0; mBurstPeriod = -1; mPeakOverall = 0;
        setStatus("RUNNING COMPLETE TEST", "Phase 0 of 3 - seal check");
        log("");
        log("################################################################");
        log("#  COMPLETE TEST                                               #");
        log("#  seal check -> protocol -> capability -> feature matrix      #");
        log("#  approx 7 minutes. Port must stay SEALED throughout.         #");
        log("################################################################");
        startSealCheck();
    }

    /** Called at the end of each phase to chain the next one. */
    private void masterAdvance(int phaseJustFinished) {
        if (!masterRunning) return;
        if (phaseJustFinished == 1) {
            setStatus("RUNNING COMPLETE TEST", "Phase 2 of 3 - physical characterisation");
            ui.postDelayed(new Runnable() { @Override public void run() {
                startCapTest();
            } }, 3000);
        } else if (phaseJustFinished == 2) {
            setStatus("RUNNING COMPLETE TEST", "Phase 3 of 3 - feature matrix");
            ui.postDelayed(new Runnable() { @Override public void run() {
                printFeatureMatrix();
            } }, 1500);
        }
    }

    private String verdict(boolean ok) { return ok ? "READY  " : "BLOCKED"; }

    private void row(String feature, String requires, String measured, boolean ok, String note) {
        log(String.format(Locale.US, "  %-28s %-22s %-16s %s  %s",
            feature, requires, measured, verdict(ok), note));
    }

    /**
     * Turn the measured physics into per-feature go/no-go calls.
     * Every verdict traces to a number measured in this same run.
     */
    private void printFeatureMatrix() {
        masterRunning = false;
        log("");
        log("################################################################");
        log("#         FEATURE FEASIBILITY MATRIX (measured, not assumed)   #");
        log("################################################################");
        log(String.format(Locale.US, "  %-28s %-22s %-16s %s  %s",
            "FEATURE", "REQUIRES", "MEASURED", "VERDICT", "NOTE"));
        log("  " + "-------------------------------------------------------------"
            + "------------------------");

        log("  ENVIRONMENT (Phase 0): seal " + sealVerdict
            + ", ambient " + fmt(sealZero) + " kPa, leak "
            + (sealLeakRate >= 0 ? fmt(sealLeakRate) + " kPa/s" : "n/a")
            + ", retained " + fmt(sealRetained) + "%");
        log("");
        boolean sealed = mPeakOverall > 3;
        if (!sealed) {
            log("  !! Peak pressure was only "
                + String.format(Locale.US, "%.1f", mPeakOverall)
                + " kPa - the port was not sealed.");
            log("  !! Every verdict below is unreliable. Seal and re-run.");
        }

        row("Progressive ramp", "rise rate", fmt(mMaxRise) + " kPa/s",
            mMaxRise > 0.5,
            mMaxRise > 0 ? ("0->20 kPa in >= " + fmt(20 / mMaxRise) + " s") : "");

        row("Fast descent / sawtooth", "fall rate", fmt(mMaxFall) + " kPa/s",
            mMaxFall > 0.5,
            mMaxFall > 0 ? ("20->0 kPa in >= " + fmt(20 / mMaxFall) + " s") : "");

        boolean burstOk = mBurstPeriod > 0 && mBurstPeriod < 8;
        row("Burst train / intervals", "cycle period",
            (mBurstPeriod > 0 ? fmt(mBurstPeriod) + " s" : "not measured"), burstOk,
            mBurstPeriod > 0 ? ("max " + fmt(1 / mBurstPeriod) + " Hz") : "");

        boolean speedAxis = mRise45 > 0 && (mRise100 / Math.max(0.01, mRise45)) > 1.3;
        row("Per-stage speed control", "speed changes rate",
            fmt(mRise45) + "->" + fmt(mRise100), speedAxis,
            speedAxis ? "speed is a real axis" : "saturated - speed barely matters");

        boolean sub = mPeak25 > 1;
        row("Sub-45% speed (gentle)", "motor runs at 25%",
            fmt(mPeak25) + " kPa", sub,
            sub ? "~114 extra codes usable" : "app floor of 45% is real");

        boolean vlow = mPeak10 > 1;
        row("Ultra-low speed 10%", "motor runs at 10%",
            fmt(mPeak10) + " kPa", vlow, vlow ? "very gentle onset possible" : "stalls");

        double drift = mHoldPeak - mHoldSS;
        boolean coasts = mHoldPeak > 1 && drift > 1.5;
        row("Leak-decay cycling", "coasts during hold",
            "drift " + fmt(drift) + " kPa", coasts,
            coasts ? "pressure decays -> coast-hold viable"
                   : "firmware regulates -> use valve cycling instead");

        boolean ripple = mRipplePeak > 1;
        row("Micro-ripple hold", "1 kPa band works",
            fmt(mRipplePeak) + " kPa", ripple,
            ripple ? "near-constant hold achievable" : "band too narrow for firmware");

        boolean dose = rxCount > 50;
        row("Dose-based termination", "telemetry cadence",
            fmt(cadenceHz()) + " Hz", dose,
            "integration error ~ 1/(2*rate)");

        boolean servo = mDead >= 0 && mDead < 3;
        row("Closed-loop servo", "command dead time",
            (mDead >= 0 ? fmt(mDead) + " s" : "not measured"), servo,
            servo ? "outer loop can react" : "too slow for tight control");

        row("Safety supervisor", "stop reachable", "acks OK", true,
            "over-pressure abort + watchdog + dead-man");

        log("");
        log("  PHYSICAL LIMITS SUMMARY");
        log("    peak pressure reached   " + fmt(mPeakOverall) + " kPa");
        log("    max rise rate           " + fmt(mMaxRise) + " kPa/s");
        log("    max fall rate           " + fmt(mMaxFall) + " kPa/s");
        log("    min cycle period        "
            + (mMinPeriod > 0 ? fmt(mMinPeriod) + " s" : "not measured"));
        log("    command dead time       "
            + (mDead >= 0 ? fmt(mDead) + " s" : "not measured"));
        log("    telemetry cadence       " + fmt(cadenceHz()) + " Hz");
        log("################################################################");
        writeSummary();
        setStatus("COMPLETE TEST FINISHED",
            "Tap SEND RESULTS to deliver the log");
        log("DONE. Tap SEND RESULTS (WhatsApp) to deliver this log.");
    }

    private double cadenceHz() {
        return (rxCount > 1 && lastRx > firstRx)
            ? rxCount * 1000.0 / (lastRx - firstRx) : 0;
    }

    private static String fmt(double d) {
        return String.format(Locale.US, "%.2f", d);
    }


    // ==================================================================
    //  PHASE 0 - SEAL CHECK / ENVIRONMENT BASELINE
    //  Establishes whether the rig can hold vacuum at all, and records the
    //  baseline the rest of the run is interpreted against. Gates everything:
    //  an open port makes every later measurement meaningless, so the run
    //  aborts here rather than burning six minutes producing noise.
    // ==================================================================

    private static final int SEAL_TARGET_KPA = 15;
    private boolean sealRunning = false;
    private int sealPhase = 0;
    private final List<long[]> sealSeries = new ArrayList<long[]>();
    private long sealT0 = 0;
    private double sealPeak = 0, sealLeakRate = -1, sealRetained = 0, sealZero = -1;
    private String sealVerdict = "not run";

    private class SealTick implements Runnable {
        private final int phase;
        SealTick(int p) { phase = p; }
        @Override public void run() { sealStep(phase); }
    }

    private void startSealCheck() {
        sealRunning = true; sealPhase = 0;
        sealSeries.clear();
        sealPeak = 0; sealLeakRate = -1; sealRetained = 0; sealZero = -1;
        sealVerdict = "not run";
        log("");
        log("=================================================================");
        log(" PHASE 0 - SEAL CHECK AND ENVIRONMENT BASELINE");
        log("=================================================================");
        setStatus(null, "Phase 0 - seal check");
        sealStep(0);
    }

    private void sealStep(int phase) {
        sealPhase = phase;
        switch (phase) {
        case 0:
            log("[S1] settling at ambient for 5 s (zero baseline)");
            tx(cmd(0x2D), "StopWork");
            sealSeries.clear();
            sealT0 = System.currentTimeMillis();
            ui.postDelayed(new SealTick(1), 5000);
            break;

        case 1: {
            double sum = 0; int n = 0;
            for (int i = 0; i < sealSeries.size(); i++) { sum += sealSeries.get(i)[1] / 100.0; n++; }
            sealZero = (n > 0) ? sum / n : 0;
            log("      ambient baseline = " + fmt(sealZero) + " kPa over " + n + " frames");
            log("[S2] pulling to " + SEAL_TARGET_KPA + " kPa at 100% to test the seal");
            clearAllSlots();
            ui.postDelayed(new SealTick(2), 2300);
            break; }

        case 2:
            // upper=target, no holds, lower=0 so it simply pulls
            tx(new byte[]{0x66, 0x2A, 0x2B, (byte) 0xFF,
                (byte) SEAL_TARGET_KPA, 0x00, 0x00, 0x00}, "Add(seal)");
            ui.postDelayed(new SealTick(3), 800);
            break;

        case 3:
            sealSeries.clear();
            sealT0 = System.currentTimeMillis();
            tx(new byte[]{0x66, 0x2A, 0x2C, 0x00}, "Start(seal)");
            ui.postDelayed(new SealTick(4), 12000);
            break;

        case 4: {
            for (int i = 0; i < sealSeries.size(); i++) {
                double v = sealSeries.get(i)[1] / 100.0;
                if (v > sealPeak) sealPeak = v;
            }
            log("      peak reached = " + fmt(sealPeak) + " kPa (target "
                + SEAL_TARGET_KPA + ")");
            // StopWork VENTS (measured 2026-08-16: 4.68 kPa/s), so it cannot be
            // used to measure a leak - it would measure the valve. Instead run a
            // preset with a LONG upperHold: the firmware coasts during the hold
            // (C6 confirmed pressure decays rather than being topped up), so the
            // decay observed there is the true leak.
            log("[S3] holding with a long upperHold; the firmware coasts, so this");
            log("     measures the real LEAK, not the vent");
            clearAllSlots();
            ui.postDelayed(new SealTick(6), 2300);
            break; }

        case 6:
            // upper just above current, very long hold, lower 0 so no bleed is
            // commanded during the window
            tx(new byte[]{0x66, 0x2A, 0x2B, (byte) 0xFF,
                (byte) SEAL_TARGET_KPA, (byte) 60, 0x00, 0x00}, "Add(hold)");
            ui.postDelayed(new SealTick(7), 800);
            break;

        case 7:
            tx(new byte[]{0x66, 0x2A, 0x2C, 0x00}, "Start(hold)");
            // let it reach target, then start the measurement window
            ui.postDelayed(new SealTick(8), 8000);
            break;

        case 8:
            sealSeries.clear();
            sealT0 = System.currentTimeMillis();
            log("      measurement window open (20 s)");
            ui.postDelayed(new SealTick(5), 20000);
            break;

        case 5: {
            // leak rate from the decay window
            double p0 = -1, p1 = -1; long t0 = 0, t1 = 0;
            for (int i = 0; i < sealSeries.size(); i++) {
                double v = sealSeries.get(i)[1] / 100.0;
                if (v > 0) {
                    if (p0 < 0) { p0 = v; t0 = sealSeries.get(i)[0]; }
                    p1 = v; t1 = sealSeries.get(i)[0];
                }
            }
            double dt = (t1 - t0) / 1000.0;
            if (p0 > 0 && dt > 1) {
                sealLeakRate = (p0 - p1) / dt;
                sealRetained = (sealPeak > 0) ? 100.0 * p1 / sealPeak : 0;
            }
            log("      hold-window decay " + fmt(p0) + " -> " + fmt(p1)
                + " kPa over " + fmt(dt) + " s (motor idle, valve shut)");
            log("      leak rate = " + (sealLeakRate >= 0 ? fmt(sealLeakRate) + " kPa/s" : "n/a")
                + ", retained " + fmt(sealRetained) + "% of peak");

            if (sealPeak < 3) {
                sealVerdict = "OPEN";           // never built pressure at all
            } else if (sealLeakRate < 0) {
                // reached pressure but the decay window captured nothing usable
                sealVerdict = "PARTIAL";
            } else if (sealLeakRate < 0.25) {
                sealVerdict = "GOOD";
            } else if (sealLeakRate < 1.0) {
                sealVerdict = "MARGINAL";
            } else {
                sealVerdict = "LEAKY";
            }

            log("");
            log("  SEAL VERDICT: " + sealVerdict);
            log("    ambient zero    " + fmt(sealZero) + " kPa");
            log("    peak achieved   " + fmt(sealPeak) + " / " + SEAL_TARGET_KPA + " kPa");
            log("    leak rate       " + (sealLeakRate >= 0 ? fmt(sealLeakRate) + " kPa/s" : "n/a"));
            log("    retained 15 s   " + fmt(sealRetained) + "%");
            if ("GOOD".equals(sealVerdict))
                log("    -> rig holds vacuum. All later measurements are valid.");
            else if ("MARGINAL".equals(sealVerdict)) {
                log("    -> holds, but leaks. Rise/fall rates valid; hold and");
                log("       leak-decay results will be pessimistic.");
            } else
                log("    -> CANNOT hold vacuum. Later measurements would be noise.");
            log("=================================================================");

            sealRunning = false;
            tx(cmd(0x2D), "StopWork");

            // Only a rig that never built pressure is hopeless. PARTIAL still
            // yields valid rise-rate data, so it must not abort the run.
            boolean ok = !"OPEN".equals(sealVerdict);
            if ("LEAKY".equals(sealVerdict))
                log("    NOTE: leaky but usable - rise rates valid, hold results"
                    + " will read pessimistic");
            if (masterRunning && !ok) {
                masterRunning = false;
                setStatus("ABORTED - BAD SEAL",
                    "peak " + fmt(sealPeak) + " kPa, leak " + fmt(sealLeakRate) + " kPa/s");
                log("");
                log("  RUN ABORTED after Phase 0: the rig cannot hold vacuum.");
                log("  Block the outlet airtight and run again. Nothing below this");
                log("  point would have been measurable.");
                writeSummary();
            } else if (masterRunning) {
                setStatus("RUNNING COMPLETE TEST", "Phase 1 of 3 - protocol correctness");
                ui.postDelayed(new Runnable() { @Override public void run() {
                    startFullTest();
                } }, 2500);
            }
            break; }
        }
    }


    // ==================================================================
    //  ROUTINE EDITOR
    //  Every parameter of every stage is adjustable: pressure band, hold
    //  times, motor speed, stage duration, rounds and rest. A live curve
    //  preview shows the commanded profile before anything is sent.
    // ==================================================================

    private static final int MAX_STAGES = 8;   // slot 8 reserved for release
    private final int[][] stages = new int[MAX_STAGES][6];  // up, lo, uh, lh, spd, secs
    private int stageCount = 3;
    private int stageSel = 0;
    private int routineRounds = 1, routineRest = 30;

    private LinearLayout editorPanel;
    private TextView stageLabel;
    private CurveView curveView;
    private final TextView[] paramValue = new TextView[6];

    private static final String[] P_NAME = {
        "Upper kPa", "Lower kPa", "Upper hold s", "Lower hold s", "Speed %", "Duration s"
    };
    private static final int[] P_MIN  = {1, 0, 0, 0,  5,  5};
    private static final int[] P_MAX  = {57, 56, 255, 255, 100, 1800};
    private static final int[] P_STEP = {1, 1, 1, 1, 5, 5};

    private void initStages() {
        // a sane default: three ascending stages
        for (int i = 0; i < MAX_STAGES; i++) {
            stages[i][0] = 12 + i * 4;              // upper
            stages[i][1] = Math.max(0, 6 + i * 4);  // lower
            stages[i][2] = 3;                       // upper hold
            stages[i][3] = 2;                       // lower hold
            stages[i][4] = 60 + i * 5;              // speed
            stages[i][5] = 120;                     // duration
        }
    }

    /** Simple line plot of the commanded profile: upper/lower band per stage. */
    private class CurveView extends View {
        private final android.graphics.Paint pUp = new android.graphics.Paint();
        private final android.graphics.Paint pLo = new android.graphics.Paint();
        private final android.graphics.Paint pAx = new android.graphics.Paint();
        private final android.graphics.Paint pTx = new android.graphics.Paint();
        private final android.graphics.Paint pSel = new android.graphics.Paint();

        CurveView(android.content.Context c) {
            super(c);
            pUp.setColor(Color.rgb(120, 230, 160)); pUp.setStrokeWidth(4f);
            pLo.setColor(Color.rgb(90, 150, 240));  pLo.setStrokeWidth(3f);
            pAx.setColor(Color.rgb(70, 74, 86));    pAx.setStrokeWidth(1f);
            pTx.setColor(Color.rgb(150, 158, 175)); pTx.setTextSize(18f);
            pSel.setColor(Color.rgb(60, 60, 30));
        }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            int w = getWidth(), h = getHeight();
            cv.drawColor(Color.rgb(26, 26, 32));
            if (stageCount < 1) return;

            int maxK = 10;
            for (int i = 0; i < stageCount; i++) maxK = Math.max(maxK, stages[i][0]);
            maxK = ((maxK / 10) + 1) * 10;

            for (int g = 0; g <= maxK; g += 10) {
                float y = h - 18 - (h - 34) * g / (float) maxK;
                cv.drawLine(0, y, w, y, pAx);
                cv.drawText(g + "", 2, y - 2, pTx);
            }

            float sw = w / (float) stageCount;
            for (int i = 0; i < stageCount; i++) {
                float x0 = i * sw, x1 = x0 + sw;
                if (i == stageSel) cv.drawRect(x0, 0, x1, h, pSel);
                float yu = h - 18 - (h - 34) * stages[i][0] / (float) maxK;
                float yl = h - 18 - (h - 34) * stages[i][1] / (float) maxK;
                cv.drawLine(x0 + 2, yu, x1 - 2, yu, pUp);
                cv.drawLine(x0 + 2, yl, x1 - 2, yl, pLo);
                // connect stage to stage so the ramp shape is visible
                if (i > 0) {
                    float pyu = h - 18 - (h - 34) * stages[i-1][0] / (float) maxK;
                    cv.drawLine(x0, pyu, x0 + 2, yu, pUp);
                }
                cv.drawText("S" + (i + 1), x0 + 4, h - 3, pTx);
                cv.drawText(stages[i][4] + "%", x0 + 4, 16, pTx);
            }
        }
    }

    private class ParamBtn implements View.OnClickListener {
        private final int idx, dir;
        ParamBtn(int i, int d) { idx = i; dir = d; }
        @Override public void onClick(View v) {
            int[] st = stages[stageSel];
            int nv = st[idx] + dir * P_STEP[idx];
            nv = Math.max(P_MIN[idx], Math.min(P_MAX[idx], nv));
            // keep the band coherent: lower must stay below upper
            if (idx == 0 && nv <= st[1]) st[1] = Math.max(0, nv - 1);
            if (idx == 1 && nv >= st[0]) nv = Math.max(0, st[0] - 1);
            st[idx] = nv;
            refreshEditor();
        }
    }

    private class StageNav implements View.OnClickListener {
        private final int what;   // 0 prev, 1 next, 2 add, 3 del
        StageNav(int w) { what = w; }
        @Override public void onClick(View v) {
            if (what == 0 && stageSel > 0) stageSel--;
            else if (what == 1 && stageSel < stageCount - 1) stageSel++;
            else if (what == 2 && stageCount < MAX_STAGES) {
                System.arraycopy(stages[stageCount - 1], 0, stages[stageCount], 0, 6);
                stageCount++; stageSel = stageCount - 1;
            } else if (what == 3 && stageCount > 1) {
                stageCount--; if (stageSel >= stageCount) stageSel = stageCount - 1;
            }
            refreshEditor();
        }
    }

    private class RoundBtn implements View.OnClickListener {
        private final int which, dir;   // 0 rounds, 1 rest
        RoundBtn(int w, int d) { which = w; dir = d; }
        @Override public void onClick(View v) {
            if (which == 0) routineRounds = Math.max(1, Math.min(20, routineRounds + dir));
            else routineRest = Math.max(0, Math.min(600, routineRest + dir * 15));
            refreshEditor();
        }
    }

    private void refreshEditor() {
        int[] st = stages[stageSel];
        stageLabel.setText("Stage " + (stageSel + 1) + " of " + stageCount
            + "     rounds " + routineRounds + "     rest " + routineRest + "s"
            + "     total ~" + (totalSeconds() / 60) + "m" + (totalSeconds() % 60) + "s");
        for (int i = 0; i < 6; i++) paramValue[i].setText(P_NAME[i] + ":  " + st[i]);
        curveView.invalidate();
    }

    private int totalSeconds() {
        int t = 0;
        for (int i = 0; i < stageCount; i++) t += stages[i][5];
        return t * routineRounds + routineRest * (routineRounds - 1);
    }

    private View buildEditor() {
        editorPanel = new LinearLayout(this);
        editorPanel.setOrientation(LinearLayout.VERTICAL);
        editorPanel.setPadding(6, 6, 6, 6);

        TextView title = new TextView(this);
        title.setText("ROUTINE EDITOR");
        title.setTextSize(13f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(255, 190, 60));
        editorPanel.addView(title);

        curveView = new CurveView(this);
        editorPanel.addView(curveView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 260));

        stageLabel = new TextView(this);
        stageLabel.setTextSize(11f);
        stageLabel.setTextColor(Color.rgb(200, 206, 218));
        stageLabel.setPadding(4, 6, 4, 6);
        editorPanel.addView(stageLabel);

        LinearLayout nav = new LinearLayout(this);
        nav.addView(navBtn("< STAGE", 0));
        nav.addView(navBtn("STAGE >", 1));
        nav.addView(navBtn("+ ADD", 2));
        nav.addView(navBtn("- DEL", 3));
        editorPanel.addView(nav);

        for (int i = 0; i < 6; i++) {
            LinearLayout row = new LinearLayout(this);
            Button minus = new Button(this);
            minus.setText("-"); minus.setTextSize(14f);
            minus.setOnClickListener(new ParamBtn(i, -1));
            minus.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            paramValue[i] = new TextView(this);
            paramValue[i].setTextSize(12f);
            paramValue[i].setTextColor(Color.rgb(220, 226, 238));
            paramValue[i].setPadding(8, 12, 8, 12);
            paramValue[i].setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 3f));
            Button plus = new Button(this);
            plus.setText("+"); plus.setTextSize(14f);
            plus.setOnClickListener(new ParamBtn(i, 1));
            plus.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(minus); row.addView(paramValue[i]); row.addView(plus);
            editorPanel.addView(row);
        }

        LinearLayout rr = new LinearLayout(this);
        rr.addView(roundBtn("ROUNDS -", 0, -1));
        rr.addView(roundBtn("ROUNDS +", 0, 1));
        rr.addView(roundBtn("REST -", 1, -1));
        rr.addView(roundBtn("REST +", 1, 1));
        editorPanel.addView(rr);

        LinearLayout act = new LinearLayout(this);
        Button run = bigBtn("RUN THIS ROUTINE", A_RUNROUTINE, Color.rgb(40, 165, 90));
        act.addView(run);
        editorPanel.addView(act);

        LinearLayout pre = new LinearLayout(this);
        pre.addView(btn("PRESET: RAMP", A_PRE_RAMP));
        pre.addView(btn("PRESET: BURST", A_PRE_BURST));
        pre.addView(btn("PRESET: WAVE", A_PRE_WAVE));
        editorPanel.addView(pre);

        return editorPanel;
    }

    private Button navBtn(String t, int what) {
        Button b = new Button(this);
        b.setText(t); b.setTextSize(9f);
        b.setOnClickListener(new StageNav(what));
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private Button roundBtn(String t, int which, int dir) {
        Button b = new Button(this);
        b.setText(t); b.setTextSize(9f);
        b.setOnClickListener(new RoundBtn(which, dir));
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    /** Shape presets, applied to the current stage set. */
    private void applyShape(int shape) {
        for (int i = 0; i < stageCount; i++) {
            float f = (stageCount > 1) ? i / (float) (stageCount - 1) : 0;
            if (shape == 0) {                      // progressive ramp
                stages[i][0] = Math.round(12 + f * 30);
                stages[i][1] = Math.max(0, stages[i][0] - 2);
                stages[i][2] = 4; stages[i][3] = 2;
                stages[i][4] = Math.round(55 + f * 45);
            } else if (shape == 1) {               // burst / interval
                stages[i][0] = Math.round(18 + f * 14);
                stages[i][1] = Math.max(0, stages[i][0] - 10);
                stages[i][2] = 1; stages[i][3] = 1;
                stages[i][4] = 100;
            } else {                               // wave: alternating deep/shallow
                boolean deep = (i % 2 == 0);
                stages[i][0] = deep ? 30 : 15;
                stages[i][1] = deep ? 22 : 12;
                stages[i][2] = deep ? 5 : 2;
                stages[i][3] = 2;
                stages[i][4] = deep ? 100 : 60;
            }
            stages[i][5] = 120;
        }
        refreshEditor();
        log("shape applied: " + (shape == 0 ? "ramp" : shape == 1 ? "burst" : "wave"));
    }

    // ---------------- routine execution ----------------

    private boolean routineRunning = false;
    private int rtStage = 0, rtRound = 0;
    private boolean rtResting = false;

    private class RoutineTick implements Runnable {
        @Override public void run() { routineStep(); }
    }

    private void startRoutine() {
        if (gatt == null || chWrite == null) { toast("Connect first"); return; }
        if (routineRunning) { log("routine already running"); return; }
        routineRunning = true; rtStage = 0; rtRound = 0; rtResting = false;
        log("");
        log("=================================================================");
        log(" RUNNING USER ROUTINE: " + stageCount + " stages x " + routineRounds
            + " rounds, rest " + routineRest + "s, total ~" + totalSeconds() + "s");
        for (int i = 0; i < stageCount; i++)
            log(String.format(Locale.US,
                "   S%d  %d/%d kPa  %d/%d s hold  speed %d%%  for %d s",
                i + 1, stages[i][0], stages[i][1], stages[i][2], stages[i][3],
                stages[i][4], stages[i][5]));
        log("=================================================================");
        setStatus("RUNNING ROUTINE", "stage 1 of " + stageCount);
        routineStep();
    }

    private void routineStep() {
        if (!routineRunning) return;

        if (rtResting) {
            rtResting = false;
            rtStage = 0; rtRound++;
            if (rtRound >= routineRounds) { finishRoutine("completed"); return; }
        }

        if (rtStage >= stageCount) {
            if (rtRound + 1 >= routineRounds) { finishRoutine("completed"); return; }
            log("--- round " + (rtRound + 1) + " done, resting " + routineRest + "s ---");
            setStatus("RUNNING ROUTINE", "rest " + routineRest + "s");
            tx(cmd(0x2D), "StopWork (rest)");
            rtResting = true;
            ui.postDelayed(new RoutineTick(), routineRest * 1000L);
            return;
        }

        int[] st = stages[rtStage];
        log(String.format(Locale.US,
            "[round %d/%d stage %d/%d] %d/%d kPa, %d/%d s, %d%%, %d s",
            rtRound + 1, routineRounds, rtStage + 1, stageCount,
            st[0], st[1], st[2], st[3], st[4], st[5]));
        setStatus("RUNNING ROUTINE", "round " + (rtRound + 1) + "/" + routineRounds
            + "  stage " + (rtStage + 1) + "/" + stageCount);

        // Add appends and delete compacts, so the table must be emptied for the
        // new stage to become slot 0.
        clearAllSlots();
        ui.postDelayed(new RoutineWrite(st), 2200);
        ui.postDelayed(new RoutineTick(), st[5] * 1000L);
        rtStage++;
    }

    private class RoutineWrite implements Runnable {
        private final int[] st;
        RoutineWrite(int[] a) { st = a; }
        @Override public void run() {
            tx(new byte[]{0x66, 0x2A, 0x2B, (byte) speedPctToCode(st[4]),
                (byte) st[0], (byte) st[2], (byte) st[1], (byte) st[3]},
                "Add(stage)");
            ui.postDelayed(new Runnable() { @Override public void run() {
                tx(new byte[]{0x66, 0x2A, 0x2C, 0x00}, "Start(stage)");
            } }, 700);
        }
    }

    private void finishRoutine(String why) {
        routineRunning = false;
        tx(cmd(0x2D), "StopWork");
        log("=== routine " + why + " ===");
        setStatus("ROUTINE FINISHED", why);
        writeSummary();
    }

    private class Toaster implements Runnable {
        private final String msg;
        Toaster(String m) { msg = m; }
        @Override public void run() {
            Ui.say(MainActivity.this, msg, false);
        }
    }

    private void toast(String s) { ui.post(new Toaster(s)); }

    @Override protected void onDestroy() {
        super.onDestroy();
        /* (re-review 5) THE STOP LEAVES BEFORE THE LINK DOES. SessionActivity is singleTask, so
         * any entry into the app clears this console above it - and this disconnected with the
         * pump possibly still running a program started here. As PumpLink#stopThenClose does:
         * StopWork first, and the link closed only after the stop's window (LastStop.MAX_MS),
         * so a write in flight is not dropped by close() (WiringCheck invariant 121). */
        final BluetoothGatt g = gatt;
        if (g != null && chWrite != null) {
            tx(cmd(0x2D), "StopWork (console closed)");
            ui.postDelayed(new CloseLater(g), LastStop.MAX_MS);
        } else if (g != null) {
            new CloseLater(g).run();
        }
        try { if (logFile != null) logFile.close(); } catch (Exception ignored) { }
    }

    /** The console's link, let go after its last stop's window. */
    private static final class CloseLater implements Runnable {
        private final BluetoothGatt g;
        CloseLater(BluetoothGatt g) { this.g = g; }
        @Override public void run() {
            try { g.disconnect(); g.close(); } catch (SecurityException ignored) { }
        }
    }
}
