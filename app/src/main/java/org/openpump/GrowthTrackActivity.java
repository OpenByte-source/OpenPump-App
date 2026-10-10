package org.openpump;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional account connection and automatic-sync status. This activity never owns a pump link. */
public final class GrowthTrackActivity extends Activity {
    private static final int UNLOCK = 721;
    private GrowthTrackManager manager;
    private Model model;
    private LinearLayout body;
    private GrowthTrackClient.Snapshot snapshot;
    private final Map<String, String> receipts = new LinkedHashMap<String, String>();
    private String onlyId, page = "home", message = "";
    private boolean busy, unlocked, askingUnlock;
    private JSONArray library;
    private JSONObject routine;

    /** Main-thread observation only: refusing this handoff never commands or stops hardware. */
    static boolean refuseHandOffUnderHold(Activity from) {
        SessionActivity owner = SessionActivity.liveForDataHandoff();
        if ((owner != null && owner.stillUnsafe()) || RunService.isRunning()) {
            if (owner != null) owner.toast("Vent the pump before opening GrowthTrack or its browser");
            return true;
        }
        return false;
    }
    static void open(SessionActivity owner, Model.Sess session) {
        if (refuseHandOffUnderHold(owner)) return;
        Intent intent = new Intent(owner, GrowthTrackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (session != null) intent.putExtra("session_id", session.id);
        owner.startActivity(intent);
    }
    static final class OpenTap implements View.OnClickListener {
        private final SessionActivity owner; private final Model.Sess session;
        OpenTap(SessionActivity owner, Model.Sess session) { this.owner = owner; this.session = session; }
        @Override public void onClick(View v) { open(owner, session); }
    }
    static final class InformationTap implements View.OnClickListener {
        final SessionActivity owner;
        InformationTap(SessionActivity owner) { this.owner = owner; }
        @Override public void onClick(View view) {
            if (refuseHandOffUnderHold(owner)) return;
            owner.startActivity(new Intent(owner, GrowthTrackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("information", true));
        }
    }
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); manager = GrowthTrackManager.get(this); model = Store.load(this, false);
        SessionActivity.applySecureFlag(getWindow(), model);
        onlyId = b == null ? getIntent().getStringExtra("session_id") : null;
        if (getIntent().getBooleanExtra("information", false)) page = "about";
        message = getIntent().getStringExtra("connection_message"); if (message == null) message = "";
        buildRoot();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); model = Store.load(this, false);
        SessionActivity.applySecureFlag(getWindow(), model);
        page = "home"; onlyId = intent.getStringExtra("session_id");
        if (intent.getBooleanExtra("information", false)) page = "about";
        message = intent.getStringExtra("connection_message"); if (message == null) message = "";
        refresh();
    }
    private void buildRoot() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        body = Ui.col(this); int padding = Ui.dp(this, Look.S5); body.setPadding(padding, padding, padding, padding);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); setContentView(scroll);
    }
    @Override protected void onResume() {
        super.onResume(); model = Store.load(this, false); SessionActivity.applySecureFlag(getWindow(), model);
        if (needsUnlock() && !unlocked) { lockScreen(); return; } refresh();
    }
    private boolean needsUnlock() { return model.appLockOn && (model.appLockWholeApp || model.appLockSettings || model.appLockSessions); }
    private void lockScreen() {
        body.removeAllViews(); Ui.head(this, body, "GrowthTrack"); Ui.note(this, body, "Unlock to manage your connection and view sync status.");
        button("Unlock", new Tap(Action.UNLOCK)); button("Back", new Tap(Action.FINISH));
    }
    private void askUnlock() {
        if (refuseHandOffUnderHold(this)) return;
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        Intent intent = keyguard == null ? null : keyguard.createConfirmDeviceCredentialIntent("Unlock", "Review your connected app and sessions");
        if (intent == null) { Ui.note(this, body, "Set a device PIN, pattern or password before opening this locked area."); return; }
        askingUnlock = true; startActivityForResult(intent, UNLOCK);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == UNLOCK) { askingUnlock = false; unlocked = result == RESULT_OK; if (unlocked) refresh(); else lockScreen(); }
    }
    @Override protected void onStop() {
        super.onStop();
        if (RunService.isRunning()) { library = null; routine = null; } // Private previews yield; automatic sync never owns the pump.
        if (!askingUnlock) { unlocked = false; if (needsUnlock()) body.removeAllViews(); }
        library = null; routine = null;
        if ("library".equals(page) || "routine".equals(page)) { page = "home"; body.removeAllViews(); }
    }
    private void refresh() {
        if (needsUnlock() && !unlocked) return;
        List<String> ids = new ArrayList<String>(); for (Model.Sess s : model.sessLog.all) ids.add(s.id);
        manager.run(new ReadState(ids), new Completed(null, true, false));
    }
    private static final class StateView {
        final GrowthTrackClient.Snapshot snapshot; final Map<String, String> receipts;
        StateView(GrowthTrackClient.Snapshot snapshot, Map<String, String> receipts) { this.snapshot = snapshot; this.receipts = receipts; }
    }
    private static final class ReadState implements GrowthTrackManager.Work {
        final List<String> ids; ReadState(List<String> ids) { this.ids = ids; }
        @Override public Object run(GrowthTrackClient client) {
            Map<String, String> statuses = new LinkedHashMap<String, String>();
            for (String id : ids) try { statuses.put(id, client.receipt(GrowthTrackProtocol.stableId(id))); } catch (IllegalArgumentException ignored) { }
            return new StateView(client.snapshot(), statuses);
        }
    }
    private void work(GrowthTrackManager.Work task, String next) { busy = true; render(); manager.run(task, new Completed(next, false, false)); }
    private final class Completed implements GrowthTrackManager.Listener {
        final String next; final boolean readState, reset;
        Completed(String next, boolean readState, boolean reset) { this.next = next; this.readState = readState; this.reset = reset; }
        @Override public void done(Object result, String error) { runOnUiThread(new Delivered(result, error, next, readState, reset)); }
    }
    private final class Delivered implements Runnable {
        final Object result; final String error, next; final boolean readState, reset;
        Delivered(Object result, String error, String next, boolean readState, boolean reset) { this.result = result; this.error = error; this.next = next; this.readState = readState; this.reset = reset; }
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (readState) {
                if (result instanceof StateView) { StateView state = (StateView) result; snapshot = state.snapshot; receipts.clear(); receipts.putAll(state.receipts); }
                if (error != null) message = error; if (!needsUnlock() || unlocked) render(); return;
            }
            busy = false;
            if (error != null) message = error; else { message = reset ? "Disconnected on this device." : ""; if (next != null) page = next; }
            if (reset) { snapshot = null; library = null; routine = null; }
            if (result instanceof String && error == null) {
                if (hasWindowFocus() && (!needsUnlock() || unlocked)) openBrowser((String) result);
                else message = "Connection prepared. Return and tap Connect to open your browser.";
            }
            if (result instanceof JSONObject && error == null) {
                JSONObject json = (JSONObject) result;
                // No private response cache survives backgrounding or a locked screen.
                if (hasWindowFocus() && (!needsUnlock() || unlocked)) {
                    if (json.optJSONArray("routines") != null) library = json.optJSONArray("routines"); else routine = json.optJSONObject("routine");
                } else page = "home";
            }
            refresh();
        }
    }
    private enum Operation { BEGIN, CANCEL_AUTH, ROUTINES }
    private static final class Task implements GrowthTrackManager.Work {
        final Operation operation; final boolean read; final String id; final List<JSONObject> payloads;
        Task(Operation operation) { this(operation, false, null, null); }
        Task(Operation operation, boolean read, String id, List<JSONObject> payloads) { this.operation = operation; this.read = read; this.id = id; this.payloads = payloads; }
        @Override public Object run(GrowthTrackClient client) throws Exception {
            switch (operation) {
                case BEGIN: return client.begin(read);
                case CANCEL_AUTH: client.cancelAuthorization(); break;
                case ROUTINES: return client.routines(id);
                default: throw new IllegalStateException();
            }
            return null;
        }
    }
    private void render() {
        if (needsUnlock() && !unlocked) { lockScreen(); return; }
        body.removeAllViews(); Ui.header(this, body, "GrowthTrack", new Tap(Action.BACK));
        if (!message.isEmpty()) Ui.noteInfo(this, body, "Connection update", "GrowthTrack", message);
        if (busy) Ui.note(this, body, "Working.");
        if ("status".equals(page)) statusScreen();
        else if ("library".equals(page)) libraryScreen(); else if ("routine".equals(page)) routineScreen();
        else if ("about".equals(page)) informationScreen(); else homeScreen();
    }
    private void homeScreen() {
        Ui.head(this, body, "Your GrowthTrack account");
        button("About GrowthTrack and this connection", new Tap(Action.ABOUT));
        if (snapshot == null) Ui.note(this, body, "Reading connection state.");
        else Ui.noteInfo(this, body, snapshot.connected ? "Connected" : snapshot.pending ? "Waiting for browser approval" : "Not connected", "Connection status", snapshot.message);
        Ui.noteInfo(this, body, "Connected: new sessions sync automatically.", "Connecting your account",
            "Connection enables automatic transfer of every new real finished session to the account you approve in GrowthTrack. Disconnected sessions and earlier history stay on your phone. There is no per-session selection or Send step. Unsupported recordings show an explicit sync error.");
        CheckBox read = check("Also allow reading my GrowthTrack routines", snapshot != null && snapshot.routines);
        button(snapshot != null && snapshot.connected ? "Reconnect / change account" : "Connect to GrowthTrack", new ConnectTap(read));
        if (snapshot != null && snapshot.pending) button("Cancel connection request", new Tap(Action.CANCEL_AUTH));
        if (snapshot != null && snapshot.connected) {
            button("Session sync status", new Tap(Action.STATUS));
            if (snapshot.queued > 0) {
                Ui.note(this, body, snapshot.queued + " sessions are pending. OpenPump retries them automatically when the connection is available.");
                long wait = Math.max(0, snapshot.retryAt - System.currentTimeMillis());
                Button retry = button(snapshot.paused ? "Reconnect to restore sync" : wait > 0 ? "Retry in " + ((wait + 999) / 1000) + " seconds" : "Retry sync", new Tap(Action.RETRY));
                retry.setEnabled(!busy && wait == 0 && !snapshot.paused);
                if (wait > 0) body.postDelayed(new RetryDisplay(), Math.min(wait + 100, 60_000));
            }
            button("Preview GrowthTrack routines", new Tap(Action.LIBRARY));
        }
        button("Manage access in GrowthTrack", new Tap(Action.MANAGE)); button("Disconnect on this device", new Tap(Action.DISCONNECT));
    }
    private final class RetryDisplay implements Runnable {
        @Override public void run() { if (!isFinishing() && !isDestroyed() && hasWindowFocus() && "home".equals(page)) refresh(); }
    }
    private final class ConnectTap implements View.OnClickListener {
        final CheckBox read; ConnectTap(CheckBox read) { this.read = read; }
        @Override public void onClick(View view) {
            dress(Ui.dialog(GrowthTrackActivity.this).setTitle("Connect to GrowthTrack?")
                .setMessage("Choose your own GrowthTrack account in the system browser. OpenPump can add training sessions" + (read.isChecked() ? " and read your routine library" : "") + ". Every new real finished session syncs automatically to this account. Earlier history is not uploaded. It cannot read measurements or session history. Reconnecting discards pending transfers for the previous approval.")
                .setPositiveButton("Continue", new Decision(Action.CONNECT, read.isChecked(), null)).setNegativeButton("Cancel", null).show());
        }
    }
    private enum Action { UNLOCK, FINISH, BACK, CONNECT, CANCEL_AUTH, STATUS, RETRY, LIBRARY, MANAGE, DISCONNECT, RESET, LIBRARY_BACK, ABOUT, PROJECT, HOME }
    private final class Tap implements View.OnClickListener {
        final Action action; Tap(Action action) { this.action = action; }
        @Override public void onClick(View view) { act(action, false, null); }
    }
    private final class Decision implements DialogInterface.OnClickListener {
        final Action action; final boolean read; final List<JSONObject> payloads;
        Decision(Action action, boolean read, List<JSONObject> payloads) { this.action = action; this.read = read; this.payloads = payloads; }
        @Override public void onClick(DialogInterface dialog, int which) { act(action, read, payloads); }
    }
    private void act(Action action, boolean read, List<JSONObject> payloads) {
        if (busy && action != Action.BACK) return;
        switch (action) {
            case ABOUT: page = "about"; render(); break;
            case PROJECT: openBrowser("https://pe-growth-track.com/"); break;
            case HOME: page = "home"; render(); break;
            case UNLOCK: askUnlock(); break;
            case FINISH: finish(); break;
            case BACK: onBackPressed(); break;
            case CONNECT: work(new Task(Operation.BEGIN, read, null, null), "home"); break;
            case CANCEL_AUTH: work(new Task(Operation.CANCEL_AUTH), "home"); break;
            case STATUS: page = "status"; refresh(); break;
            case RETRY: busy = true; render(); manager.startSync(new Completed("home", false, false)); break;
            case LIBRARY:
                if (snapshot == null || !snapshot.routines) { message = "Reconnect with the routine permission selected to preview your library."; render(); }
                else work(new Task(Operation.ROUTINES), "library"); break;
            case MANAGE: openBrowser(GrowthTrackProtocol.CONNECTED_APPS); break;
            case DISCONNECT:
                dress(Ui.dialog(this).setTitle("Disconnect GrowthTrack?")
                    .setMessage("Remove tokens and stop automatic sync and discard pending transfers on this device. Sessions already received remain in GrowthTrack. Also remove OpenPump from GrowthTrack Connected apps to revoke its server approval.")
                    .setPositiveButton("Disconnect", new Decision(Action.RESET, false, null)).setNegativeButton("Cancel", null).show()); break;
            case RESET: busy = true; render(); manager.reset(new Completed("home", false, true)); break;
            case LIBRARY_BACK: routine = null; page = "library"; render(); break;
            default: throw new IllegalStateException();
        }
    }
    private void dress(AlertDialog dialog) { Ui.dress(this, dialog); SessionActivity.applySecureFlag(dialog.getWindow(), model); }
    private void informationScreen() {
        Ui.head(this, body, "GrowthTrack, by your choice");
        Ui.note(this, body, "GrowthTrack is a separate PE progress tracker for recording training sessions, routines and measurements in your own account.");
        Ui.noteInfo(this, body, "Connect now or later in Settings.", "How to connect",
            "Visit GrowthTrack to create or sign in to your account. Back here, choose Connect and approve OpenPump in your system browser. Check the account shown there. You can decline or cancel and keep using OpenPump.");
        Ui.noteInfo(this, body, "Connect once; every new session syncs.", "What leaves your phone",
            "Your connection authorizes automatic transfer of every real finished session recorded while connected. Sync includes IDs, original times, routine names, fixed automatic technique labels, set durations and recorded peak/net time. Photos, measurements and notes stay out. Earlier history is not uploaded. Offline sessions retry automatically; an unrepresentable recording shows why it cannot sync.");
        Ui.noteInfo(this, body, "Routine previews are optional.", "Routine permission",
            "With extra permission you can read your GrowthTrack routine library. These are previews only; they do not import executable pump routines or start the pump.");
        Ui.noteInfo(this, body, "You control disconnection.", "Remove access",
            "Disconnect clears local tokens and pending sends. Remove OpenPump in GrowthTrack Connected apps to revoke server approval. Records already sent remain in your GrowthTrack account.");
        button("Visit GrowthTrack", new Tap(Action.PROJECT));
        button("Connection and session controls", new Tap(Action.HOME));
        button("Back to OpenPump", new Tap(Action.FINISH));
    }
    private void statusScreen() {
        Ui.head(this, body, "Session sync status");
        Ui.noteInfo(this, body, "New sessions sync automatically while connected.", "Session sync", "Every new real finished session syncs while connected. No sessions are selected individually. Earlier or disconnected history is not uploaded.");
        int shown = 0;
        for (Model.Sess session : model.sessLog.all) {
            if (onlyId != null && !onlyId.equals(session.id)) continue;
            shown++;
            String status = receipts.getOrDefault(session.id, "");
            if (session.sim) status = "Not synced: simulated recording";
            else if (status.startsWith("Received")) { /* Keep confirmed receipts visible after access expires. */ }
            else if (session.growthTrackConnectionId.isEmpty()) status = "Not synced: recorded before connection or while disconnected";
            else if (snapshot == null || !snapshot.connected || !session.growthTrackConnectionId.equals(snapshot.connectionId))
                status = "Previous or disconnected approval: current sync stopped; previously sent records may remain in that account";
            else if (status.isEmpty()) status = "Waiting for automatic sync";
            Ui.noteInfo(this, body, session.routineName == null ? "OpenPump session" : session.routineName, "Session sync", status);
        }
        if (shown == 0) Ui.note(this, body, "There are no recorded sessions here.");
        Ui.noteInfo(this, body, "Technique labels are fixed.", "Automatic technique rules",
            "Positive confirmed holds up to 6 seconds are Milking; above 6 through 30 are RIP; above 30 through 120 are Interval pumping; longer holds are Static pumping. Release and rest do not determine the label. The recorded Length track takes precedence. Unsupported timing or missing original timestamps are reported, never invented.");
        button("Refresh status", new Tap(Action.STATUS));
    }
    private void libraryScreen() {
        Ui.head(this, body, "GrowthTrack routine library"); Ui.note(this, body, "Read-only previews. A recipe's intensity hint is not an OpenPump machine setting.");
        if (library == null) Ui.note(this, body, "Refresh to read the library again.");
        else for (int i = 0; i < library.length(); i++) {
            JSONObject item = library.optJSONObject(i); if (item != null) button(item.optString("name", "Routine") + " / " + item.optString("access") + " / " + item.optInt("step_count") + " steps", new RoutineTap(item.optString("id")));
        }
        button("Refresh library", new Tap(Action.LIBRARY));
    }
    private final class RoutineTap implements View.OnClickListener {
        final String id; RoutineTap(String id) { this.id = id; }
        @Override public void onClick(View v) { work(new Task(Operation.ROUTINES, false, id, null), "routine"); }
    }
    private void routineScreen() {
        if (routine == null) { Ui.note(this, body, "This preview is no longer available. Refresh the library."); return; }
        Ui.head(this, body, routine.optString("name", "GrowthTrack routine")); Ui.note(this, body, "Revision " + routine.optString("revision") + " / " + routine.optString("access"));
        JSONArray steps = routine.optJSONArray("steps");
        if (steps != null) for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i); if (step == null) continue;
            Ui.fieldLabel(this, body, step.optInt("position", i + 1) + ". " + step.optString("exercise_name", "Exercise"), null);
            Ui.note(this, body, step.optInt("sets", 1) + " sets / " + (step.isNull("work_seconds_per_set") ? "untimed / repetitions" : step.optInt("work_seconds_per_set") + " seconds per set"));
            Ui.note(this, body, "Rest: " + step.optInt("rest_seconds_between_sets", 0) + " seconds between sets" + (i < steps.length() - 1 ? " and before the next step" : "; no trailing rest"));
            if (!step.isNull("reps")) Ui.note(this, body, "Repetitions: " + step.optInt("reps"));
            if (!step.isNull("intensity_hint")) Ui.noteInfo(this, body, "Author's intensity guidance", "Intensity hint", step.optString("intensity_hint"));
            if (step.optBoolean("heat") || step.optBoolean("vibration")) Ui.note(this, body, "Author suggests " + (step.optBoolean("heat") ? "heat " : "") + (step.optBoolean("vibration") ? "vibration" : ""));
            if ("own".equals(routine.optString("access")) && !step.isNull("notes")) Ui.noteInfo(this, body, "Your step notes", "Notes", step.optString("notes"));
        }
        Ui.noteInfo(this, body, "This preview cannot start an OpenPump routine.", "Device settings still need your review",
            "Blocks are already expanded in this order. This device-neutral recipe does not supply the vacuum, release and cycle settings needed for a runnable OpenPump routine. Review and enter those settings in OpenPump's editor; this preview never starts hardware or silently substitutes values.");
        button("Back to library", new Tap(Action.LIBRARY_BACK));
    }
    private CheckBox check(String text, boolean checked) {
        CheckBox box = new CheckBox(this); box.setText(text); box.setTextColor(Ui.TEXT); box.setTextSize(Look.SP_BODY); box.setMinHeight(Ui.dp(this, 48)); box.setChecked(checked); box.setEnabled(!busy);
        body.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); return box;
    }
    private Button button(String text, View.OnClickListener listener) { Button b = Ui.flat(this, body, text); b.setMinimumHeight(Ui.dp(this, 48)); b.setEnabled(!busy); b.setOnClickListener(listener); return b; }
    private void openBrowser(String url) {
        if (refuseHandOffUnderHold(this)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException e) { message = "No system browser is available. Enable a browser to connect."; manager.run(new Task(Operation.CANCEL_AUTH), new Completed(null, true, false)); render(); }
    }
    @Override public void onBackPressed() {
        if (!"home".equals(page)) { page = "home"; render(); } else super.onBackPressed();
    }
}
