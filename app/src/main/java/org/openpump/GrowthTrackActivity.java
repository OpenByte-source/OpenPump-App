package org.openpump;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * THE OPTIONAL GROWTHTRACK CONNECTION'S SCREEN (owner decisions 3, 4, 5 and 7, 19 Oct 2026).
 * Three pages in one screen, built from the app's own blocks:
 *  - the CONNECT page, before the browser: "Sessions to send" (the one stored setting the
 *    GrowthTrack page shows too), "Already on this phone" (only new sessions, or also the
 *    earlier ones that can be sent, asked once per account), what is never sent, and "Sign in
 *    to GrowthTrack";
 *  - the GROWTHTRACK page: the status card (the mark, "Sending to GrowthTrack", the account,
 *    the master switch, the status line, and the latest result or error in plain words), then
 *    "Which sessions", "Sent and waiting sessions", and a quiet Change account / red Disconnect;
 *  - the LIST of sent and waiting sessions.
 *
 * WHAT IT MAY TOUCH. It never owns a pump link, never starts or stops anything on the pump,
 * and is never open while a run is live or anything is unsafe: every way in and every launch
 * out asks {@link #doorShut} (WiringCheck invariant 252), and a run starting behind it closes
 * it. Its Model is the one screen's own (GrowthTrackManager#model), so a switch saved here is
 * never overwritten by the next save there.
 *
 * PRIVACY (bugs 10 and 11). The app lock's Settings and Sessions areas (and the whole-app lock)
 * gate it, asked under the disguise through LockHost (invariant 233); Recents shows the
 * disguise's name and icon; the browser's way back wears the identity
 * (IncognitoShell#applyReturn); the window and every dialog carry FLAG_SECURE under Hide from
 * recent apps (Ui.dress, invariant 253), and a dialog never outlives the screen going under
 * its lock.
 */
public final class GrowthTrackActivity extends Activity {
    static final String EXTRA_PAGE = "org.openpump.GT_PAGE";
    static final String EXTRA_AFTER_SIGN_IN = "org.openpump.GT_AFTER_SIGN_IN";
    private static final int REQ_UNLOCK = 721, REQ_UNLOCK_HOST = 722;
    private static final TimeZone ZONE = TimeZone.getDefault();

    private GrowthTrackManager manager;
    private Model model;
    private LinearLayout body;
    private GrowthTrackClient.Snapshot snapshot;
    private final Map<String, GrowthTrackClient.SessionState> states = new HashMap<String, GrowthTrackClient.SessionState>();
    /** The page shown, and the page this screen was opened on (Back there leaves). */
    private int page, entry;
    /** The connect page is for "Change account" (a new account), not a first connection. */
    private boolean changing;
    /** The connect page's "Already on this phone" answer. */
    private String earlierPick = GrowthTrackClient.EARLIER_NEW;
    /** A local word for the person (no browser, ...), shown with the latest result. */
    private String localNote = "";
    private boolean busy, afterSignIn, resumed, readOnce;
    /** This screen started something of its own (the browser, the lock prompt): its onStop is
     *  not the person leaving, so the lock stays as it is. */
    private boolean ownLaunch;

    /* ======================================================================= the door */

    /**
     * THE DOOR (bug 9, invariant 252): no GrowthTrack screen opens, and none stays, while a run
     * is live or anything about the pump is unsafe - a hold on the cuff, a check, a stop not yet
     * confirmed (SessionActivity#stillUnsafe). Main-thread observation only: it never commands
     * or stops hardware. `from` hears why, when it is the one screen.
     */
    static boolean doorShut(Activity from) {
        SessionActivity owner = SessionActivity.liveForDataHandoff();
        boolean shut = RunService.isRunning() || (owner != null && owner.stillUnsafe());
        if (shut && from instanceof SessionActivity) ((SessionActivity) from).toast("Finish or stop the current step first");
        return shut;
    }

    /** From the one screen: Settings' row, setup's row. `page` 0 decides from the connection. */
    static void open(SessionActivity owner, int page) {
        if (doorShut(owner)) return;
        Intent intent = new Intent(owner, GrowthTrackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_PAGE, GrowthTrackPages.key(page));
        owner.startActivity(intent);
        // This app's own screen, not the person leaving: the areas they unlocked stay open.
        owner.ownLaunchPending = true;
    }

    static final class OpenTap implements View.OnClickListener {
        private final SessionActivity owner; private final int page;
        OpenTap(SessionActivity owner, int page) { this.owner = owner; this.page = page; }
        @Override public void onClick(View v) { open(owner, page); }
    }

    /** The GrowthTrack mark: "GT" on a lime-tinted square, `sizeDp` across. */
    static TextView mark(Activity a, int sizeDp) {
        TextView m = new TextView(a);
        m.setText("GT");
        m.setTextColor(Ui.ACCENT);
        m.setTextSize(sizeDp >= 30 ? Look.SP_CAPTION : Look.SP_MICRO);
        m.setTypeface(m.getTypeface(), android.graphics.Typeface.BOLD);
        m.setGravity(Gravity.CENTER);
        m.setBackground(Ui.roundRect(a, Look.ACCENT_DIM, sizeDp >= 30 ? 8 : 6));
        m.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, sizeDp), Ui.dp(a, sizeDp)));
        Ui.decorative(m);
        return m;
    }

    /** A status dot: lime on, amber waiting (the owner's pick, decision 2), grey off. */
    static View dot(Activity a, int state) {
        View d = new View(a);
        int c = state == GrowthTrackWords.DOT_ON ? Ui.ACCENT : state == GrowthTrackWords.DOT_WAITING ? Ui.CMD : Ui.FAINT;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(c);
        d.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 8), Ui.dp(a, 8));
        lp.rightMargin = Ui.dp(a, Look.S2);
        d.setLayoutParams(lp);
        Ui.decorative(d);
        return d;
    }

    /* ======================================================================= lifecycle */

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        manager = GrowthTrackManager.get(this);
        model = manager.model();
        applyLook();
        buildRoot();
        if (doorShut(null)) { finish(); return; }
        if (saved != null) {
            page = saved.getInt("page", 0); entry = saved.getInt("entry", 0);
            changing = saved.getBoolean("changing", false);
            String pick = saved.getString("earlier");
            if (pick != null) earlierPick = pick;
        } else {
            arrive(getIntent());
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (doorShut(null)) { finish(); return; }
        arrive(intent);
    }

    /** An opening: the page asked for (decided once the connection is read), and whether it
     *  is the sign-in's return. */
    private void arrive(Intent intent) {
        afterSignIn = intent != null && intent.getBooleanExtra(EXTRA_AFTER_SIGN_IN, false);
        int wanted = GrowthTrackPages.fromKey(intent == null ? null : intent.getStringExtra(EXTRA_PAGE));
        page = wanted; entry = 0; changing = false; localNote = "";
        if (afterSignIn) busy = true;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page); out.putInt("entry", entry);
        out.putBoolean("changing", changing); out.putString("earlier", earlierPick);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true; ownLaunch = false;
        model = manager.model();
        applyLook();
        if (doorShut(null)) { finish(); return; }
        if (locked()) { lockScreen(); return; }
        refresh();
    }

    @Override protected void onPause() { super.onPause(); resumed = false; }

    @Override protected void onStop() {
        super.onStop();
        // Nothing of this screen is left over a run, or readable behind its lock.
        Ui.dismissDialogOf(this);
        if (ownLaunch || isChangingConfigurations()) return;
        // The person left: the app lock's areas close again, as when the one screen is left.
        AppLock.onLeaveForeground();
        if (RunService.isRunning() || locked()) body.removeAllViews();
    }

    /** The window flag under Hide from recent apps, and the name and icon in Recents. */
    private void applyLook() {
        SessionActivity.applySecureFlag(getWindow(), model);
        int identity = Incognito.identity(model);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28)
                setTaskDescription(new android.app.ActivityManager.TaskDescription(
                    Incognito.shownName(identity), IncognitoShell.iconRes(identity)));
            else
                setTaskDescription(new android.app.ActivityManager.TaskDescription(
                    Incognito.shownName(identity), Incognito.disguised(identity)
                        ? android.graphics.BitmapFactory.decodeResource(getResources(), IncognitoShell.iconRes(identity))
                        : null));
        } catch (Exception ignored) { }
    }

    /** Every dialog's window too - Ui.dress hands it here. */
    void applySecureFlag(android.view.Window w) { SessionActivity.applySecureFlag(w, model); }

    private void buildRoot() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        body = Ui.col(this); int padding = Ui.dp(this, Look.S5); body.setPadding(padding, padding, padding, padding);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    /* ======================================================================= the lock */

    /** The app lock's areas this screen shows: Settings (where it lives) and Sessions (the
     *  list), and the whole-app lock. */
    private boolean locked() {
        return model != null && (AppLock.isLocked(model, AppLock.WHOLE_APP) || AppLock.isLocked(model, AppLock.SETTINGS)
            || AppLock.isLocked(model, AppLock.SESSIONS));
    }

    private void unlocked() {
        AppLock.markUnlocked(AppLock.SETTINGS);
        AppLock.markUnlocked(AppLock.SESSIONS);
        if (AppLock.wholeAppOn(model)) AppLock.markUnlocked(AppLock.WHOLE_APP);
        refresh();
    }

    /** One lock card, drawn fresh each time - never a pile of notes. */
    private void lockScreen() {
        body.removeAllViews();
        Ui.header(this, body, Incognito.shownName(Incognito.identity(model)), new Tap(Action.FINISH));
        Ui.note(this, body, "Unlock to open this page.");
        Ui.big(this, body, "Unlock", Ui.ACCENT).setOnClickListener(new Tap(Action.UNLOCK));
    }

    /**
     * THE PROMPT, ASKED UNDER THE DISGUISE (bug 10, invariant 233): newer Android draws the
     * asking activity's name and icon on the PIN screen, so under a disguise it is asked from
     * the disguise's lock host; as itself, from here. A phone with no screen lock is never a
     * dead end: the page opens, as the one screen's lock does.
     */
    private void askUnlock() {
        if (doorShut(null)) return;
        Class<?> host = IncognitoShell.lockHost(Incognito.identity(model));
        if (host != null) {
            Intent ask = new Intent(this, host);
            ask.putExtra(LockHost.EXTRA_TITLE, "Unlock " + AppLock.scopeLabel(AppLock.SETTINGS));
            ask.putExtra(LockHost.EXTRA_MODE, LockHost.MODE_CREDENTIAL);
            ask.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            ownLaunch = true;
            startActivityForResult(ask, REQ_UNLOCK_HOST);
            return;
        }
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        Intent ask = keyguard == null || !keyguard.isDeviceSecure() ? null
            : keyguard.createConfirmDeviceCredentialIntent("Unlock " + AppLock.scopeLabel(AppLock.SETTINGS), null);
        if (ask == null) { unlocked(); return; }
        ownLaunch = true;
        startActivityForResult(ask, REQ_UNLOCK);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_UNLOCK && request != REQ_UNLOCK_HOST) return;
        if (result == RESULT_OK || (request == REQ_UNLOCK_HOST && result == LockHost.RESULT_NO_LOCK)) unlocked();
        else lockScreen();
    }

    /* ======================================================================= reading */

    private void refresh() {
        if (locked()) { lockScreen(); return; }
        List<String> ids = new ArrayList<String>();
        for (Model.Sess s : model.sessLog.all) {
            String id = GrowthTrackOutbox.localId(s);
            if (id != null) ids.add(id);
        }
        if (!readOnce) render();
        manager.run(new ReadState(ids), new Completed(Result.READ));
    }

    private static final class StateView {
        final GrowthTrackClient.Snapshot snapshot; final Map<String, GrowthTrackClient.SessionState> states;
        StateView(GrowthTrackClient.Snapshot snapshot, Map<String, GrowthTrackClient.SessionState> states) {
            this.snapshot = snapshot; this.states = states;
        }
    }

    private static final class ReadState implements GrowthTrackManager.Work {
        final List<String> ids; ReadState(List<String> ids) { this.ids = ids; }
        @Override public Object run(GrowthTrackClient client) {
            Map<String, GrowthTrackClient.SessionState> out = new HashMap<String, GrowthTrackClient.SessionState>();
            for (String id : ids) out.put(id, client.state(id));
            return new StateView(client.snapshot(), out);
        }
    }

    private enum Result { READ, SIGN_IN, DONE, RESET }

    private final class Completed implements GrowthTrackManager.Listener {
        final Result kind; Completed(Result kind) { this.kind = kind; }
        @Override public void done(Object result, String error) { runOnUiThread(new Delivered(kind, result, error)); }
    }

    private final class Delivered implements Runnable {
        final Result kind; final Object result; final String error;
        Delivered(Result kind, Object result, String error) { this.kind = kind; this.result = result; this.error = error; }
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (doorShut(null)) { finish(); return; }
            switch (kind) {
                case READ:
                    readOnce = true;
                    if (result instanceof StateView) {
                        StateView v = (StateView) result;
                        boolean wasLinked = snapshot != null && (snapshot.connected || snapshot.needsReconnect);
                        snapshot = v.snapshot; states.clear(); states.putAll(v.states);
                        boolean linked = snapshot.connected || snapshot.needsReconnect;
                        if (afterSignIn) {
                            // The sign-in's return: the exchange ran first on the serial worker.
                            afterSignIn = false; busy = false;
                            if (snapshot.connected) { changing = false; manager.startSync(model, new Completed(Result.DONE)); }
                        } else if (wasLinked && !linked) changing = false;
                    } else if (error != null) localNote = error;
                    settlePage();
                    if (!locked()) render();
                    return;
                case SIGN_IN:
                    busy = false;
                    if (result instanceof String && error == null) {
                        // Bug 2: the browser opens on every tap - asked whether this screen is
                        // in front, never whether its window has focus (a dialog closing, or
                        // the button's own press, held it for a moment).
                        if (resumed && !locked()) openBrowser((String) result);
                        else localNote = "Tap Sign in to GrowthTrack again to open your browser.";
                    } else if (error != null) localNote = error;
                    refresh();
                    return;
                case RESET:
                    busy = false;
                    snapshot = null; changing = false; page = GrowthTrackPages.CONNECT;
                    if (entry != GrowthTrackPages.CONNECT) entry = GrowthTrackPages.CONNECT;
                    localNote = error == null ? "Disconnected on this phone. Sessions already sent stay in your GrowthTrack account." : error;
                    refresh();
                    return;
                default:
                    busy = false;
                    if (error != null) localNote = error;
                    refresh();
            }
        }
    }

    /** The page for what is now known, and the page Back leaves from. */
    private void settlePage() {
        int next = GrowthTrackPages.pageFor(page, snapshot, changing);
        if (entry == 0 || entry == page && next != page) entry = next;
        page = next;
    }

    /* ======================================================================= drawing */

    private void render() {
        if (locked()) { lockScreen(); return; }
        body.removeAllViews();
        if (!readOnce) {
            Ui.header(this, body, "GrowthTrack", new Tap(Action.BACK));
            Ui.note(this, body, busy ? "Finishing your sign-in…" : "Reading the connection…");
            return;
        }
        if (page == GrowthTrackPages.LIST) listPage();
        else if (page == GrowthTrackPages.HOME) homePage();
        else connectPage();
    }

    private GrowthTrackSettings send() {
        if (model.gtSend == null) model.gtSend = new GrowthTrackSettings();
        return model.gtSend;
    }

    private String latest() {
        if (!localNote.isEmpty()) return localNote;
        return snapshot == null ? "" : GrowthTrackWords.latest(snapshot.message);
    }

    private TextView caption(ViewGroup parent, String text, int colour) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(colour);
        t.setTextSize(Look.SP_CAPTION);
        t.setPadding(0, Ui.dp(this, Look.S1), 0, Ui.dp(this, Look.S1));
        if (parent != null) parent.addView(t);
        return t;
    }

    /** The type switches - one stored setting (GrowthTrackSettings), the same on both pages;
     *  a plan's switch only for a track the person trains. */
    private void typeSwitches(ViewGroup parent) {
        GrowthTrackSettings s = send();
        if (GrowthTrackSettings.showsPlanSwitch(model, GrowthTrackSettings.KIND_GIRTH_PLAN))
            Ui.kvRow(this, parent, "Girth plan", s.girthPlan, new Toggle(Toggle.GIRTH));
        if (GrowthTrackSettings.showsPlanSwitch(model, GrowthTrackSettings.KIND_LENGTH_PLAN))
            Ui.kvRow(this, parent, "Length plan", s.lengthPlan, new Toggle(Toggle.LENGTH));
        Ui.kvRow(this, parent, "My own routines", s.ownRoutines, new Toggle(Toggle.OWN));
        Ui.kvRow(this, parent, "Manual runs", s.manualRuns, new Toggle(Toggle.MANUAL));
        Ui.kvRow(this, parent, "Include sessions stopped early", s.stoppedEarly, new Toggle(Toggle.STOPPED));
    }

    private static final String ABOUT = "GrowthTrack is a separate progress tracker for PE training, in "
        + "your own account. Connected, OpenPump sends each finished session you choose here: when it "
        + "ran, the routine's name, its sets and their times. Measurements, photos, notes and practice "
        + "runs are never sent. You sign in, or make an account, in your browser. You can switch "
        + "sending off, change account or disconnect at any time; sessions already sent stay in your "
        + "GrowthTrack account.";

    /** Decision 4: one page before the browser, three blocks, then Sign in. */
    private void connectPage() {
        Ui.header(this, body, "GrowthTrack", new Tap(Action.BACK));
        Ui.head(this, body, "Connect GrowthTrack");
        Ui.noteInfo(this, body, "Choose what goes to your account. You can change it any time.", "About GrowthTrack", ABOUT);
        String word = latest();
        if (!word.isEmpty()) caption(body, word, Ui.TEXT);
        if (snapshot != null && snapshot.pending) {
            LinearLayout w = new LinearLayout(this);
            w.setOrientation(LinearLayout.HORIZONTAL); w.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = caption(null, "Waiting for your sign-in in the browser.", Ui.DIM);
            w.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            w.addView(Ui.textLink(this, "Cancel sign-in", !busy, new Tap(Action.CANCEL_SIGN_IN)));
            body.addView(w);
        }

        LinearLayout types = Ui.cardGroup(this, body, null, null);
        Ui.microLabel(this, types, "Sessions to send", Ui.DIM);
        typeSwitches(types);

        GrowthTrackOutbox.Earlier earlier = earlier();
        if (GrowthTrackPages.asksEarlier(earlier, snapshot, changing)) {
            LinearLayout already = Ui.cardGroup(this, body, null, null);
            Ui.microLabel(this, already, "Already on this phone", Ui.DIM);
            boolean also = GrowthTrackClient.EARLIER_ALSO.equals(GrowthTrackPages.earlierAnswer(true, earlierPick, earlier));
            View.OnClickListener[] taps = { new Pick(GrowthTrackClient.EARLIER_NEW),
                earlier.sendable > 0 ? new Pick(GrowthTrackClient.EARLIER_ALSO) : null };
            Ui.choiceList(this, already, new String[]{ "Only new sessions", "Also send earlier ones · " + earlier.line() },
                null, also ? 1 : 0, taps);
        }

        TextView never = new TextView(this);
        never.setText("Never sent: measurements, photos, notes, practice runs.");
        never.setTextColor(Ui.DIM);
        never.setTextSize(Look.SP_CAPTION);
        int pad = Ui.dp(this, Look.S3);
        never.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable dash = new android.graphics.drawable.GradientDrawable();
        dash.setCornerRadius(Ui.dp(this, Look.R_CTRL));
        dash.setStroke(Math.max(1, Ui.dp(this, 1)), Look.CTRL_EDGE, Ui.dp(this, 4), Ui.dp(this, 3));
        never.setBackground(dash);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = Ui.dp(this, Look.S2);
        body.addView(never, nl);

        Button signIn = Ui.big(this, body, busy ? "Opening your browser…" : "Sign in to GrowthTrack", Ui.ACCENT);
        signIn.setOnClickListener(new Tap(Action.SIGN_IN));
        Ui.setEnabled(this, signIn, !busy, Ui.ACCENT);
    }

    /** "N of M can be sent" - the main thread reads the recordings (GrowthTrackOutbox#earlier). */
    private GrowthTrackOutbox.Earlier earlier() {
        return GrowthTrackOutbox.earlier(model, new GrowthTrackOutbox.AsRunLookup() {
            @Override public AsRun asRun(Model.Sess s) { return Store.loadAsRun(GrowthTrackActivity.this, s.ts); }
        });
    }

    /** Decision 3: the status card, the switches, the list, then the quiet actions. */
    private void homePage() {
        long now = System.currentTimeMillis();
        GrowthTrackSettings s = send();
        Ui.header(this, body, "GrowthTrack", new Tap(Action.BACK));

        LinearLayout card = Ui.cardGroup(this, body, null, null);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(Ui.dp(this, 48));
        TextView m = mark(this, 34);
        ((LinearLayout.LayoutParams) m.getLayoutParams()).rightMargin = Ui.dp(this, Look.S3);
        top.addView(m);
        LinearLayout who = Ui.col(this);
        TextView title = new TextView(this);
        title.setText("Sending to GrowthTrack");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(Look.SP_HEADING);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        who.addView(title);
        String account = GrowthTrackWords.account(snapshot);
        if (account != null) caption(who, account, Ui.DIM);
        top.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View sw = Ui.switchView(this, s.on);
        LinearLayout.LayoutParams swl = new LinearLayout.LayoutParams(Ui.dp(this, Ui.SWITCH_W), Ui.dp(this, Ui.SWITCH_H));
        swl.leftMargin = Ui.dp(this, Look.S3);
        top.addView(sw, swl);
        Ui.group(top, "Sending to GrowthTrack" + (account == null ? "" : ", " + account) + ": " + (s.on ? "on" : "off"));
        top.setSelected(s.on);
        top.setOnClickListener(new Toggle(Toggle.MASTER));
        card.addView(top);

        GrowthTrackWords.Line line = GrowthTrackWords.statusLine(snapshot, s.on, now, ZONE);
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(0, Ui.dp(this, Look.S2), 0, 0);
        status.addView(dot(this, line.dot));
        TextView st = caption(null, line.text, Ui.DIM);
        status.addView(st, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(status);
        String word = latest();
        if (!word.isEmpty()) caption(card, word, Ui.TEXT);
        if (snapshot != null && snapshot.pending) {
            LinearLayout w = new LinearLayout(this);
            w.setOrientation(LinearLayout.HORIZONTAL); w.setGravity(Gravity.CENTER_VERTICAL);
            w.addView(caption(null, "Waiting for your sign-in in the browser.", Ui.DIM),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            w.addView(Ui.textLink(this, "Cancel sign-in", !busy, new Tap(Action.CANCEL_SIGN_IN)));
            card.addView(w);
        }
        if (snapshot != null && (snapshot.needsReconnect || snapshot.paused)) {
            Button again = Ui.secondary(this, card, busy ? "Opening your browser…" : "Sign in again");
            again.setOnClickListener(new Tap(Action.SIGN_IN_AGAIN));
            Ui.setEnabled(this, again, !busy, Ui.SURFHI);
        } else if (snapshot != null && snapshot.connected && snapshot.queued > 0 && s.on) {
            LinearLayout w = new LinearLayout(this);
            w.setGravity(Gravity.RIGHT);
            w.addView(Ui.textLink(this, "Send now", !busy, new Tap(Action.SEND_NOW)));
            card.addView(w);
        }

        TextView which = Ui.microLabel(this, null, "Which sessions", Ui.DIM);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = Ui.dp(this, Look.S2); wl.bottomMargin = Ui.dp(this, Look.S2);
        body.addView(which, wl);
        LinearLayout types = Ui.cardGroup(this, body, null, null);
        LinearLayout block = Ui.col(this);
        types.addView(block, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        typeSwitches(block);
        // Master off: the type switches dim, kept as they are (decision 3).
        Ui.dependentBlock(this, block, s.on);

        Ui.navCard(this, body, "Sent and waiting sessions", new Tap(Action.LIST));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.addView(Ui.textLink(this, "Change account", !busy, new Tap(Action.CHANGE_ACCOUNT)));
        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, Look.S5), 1));
        TextView disconnect = Ui.textLink(this, "Disconnect", !busy, new Tap(Action.DISCONNECT));
        if (!busy) disconnect.setTextColor(Ui.CRIT);
        actions.addView(disconnect);
        body.addView(actions);
    }

    /** "Sent and waiting sessions": each session as Sent, Waiting, or Not sent and why. */
    private void listPage() {
        Ui.header(this, body, "Sent and waiting sessions", new Tap(Action.BACK));
        long now = System.currentTimeMillis();
        int sent = 0, waiting = 0, not = 0, shown = 0;
        LinearLayout list = Ui.cardGroup(this, body, null, null);
        for (Model.Sess s : model.sessLog.all) {
            if (s == null) continue;
            String id = GrowthTrackOutbox.localId(s);
            GrowthTrackOutbox.Status status = GrowthTrackOutbox.statusOf(s, snapshot, id == null ? null : states.get(id), model);
            if (status.state == GrowthTrackOutbox.Status.SENT) sent++;
            else if (status.state == GrowthTrackOutbox.Status.WAITING) waiting++;
            else not++;
            if (shown++ >= 300) continue;
            int colour = status.state == GrowthTrackOutbox.Status.SENT ? Ui.ACCENT
                : status.state == GrowthTrackOutbox.Status.WAITING ? Ui.CMD : Ui.DIM;
            Ui.kvRow(this, list, GrowthTrackWords.sessionTitle(s, now, ZONE), status.text, colour, null);
        }
        if (shown == 0) Ui.note(this, list, "No sessions on this phone yet.");
        else caption(list, GrowthTrackWords.listFacts(sent, waiting, not), Ui.DIM);
    }

    /* ======================================================================= actions */

    private enum Action { UNLOCK, FINISH, BACK, SIGN_IN, SIGN_IN_AGAIN, CANCEL_SIGN_IN, SEND_NOW, LIST, CHANGE_ACCOUNT, DISCONNECT, RESET }

    private final class Tap implements View.OnClickListener {
        final Action action; Tap(Action action) { this.action = action; }
        @Override public void onClick(View view) { act(action); }
    }

    private final class Decision implements DialogInterface.OnClickListener {
        final Action action; Decision(Action action) { this.action = action; }
        @Override public void onClick(DialogInterface dialog, int which) { act(action); }
    }

    private final class Pick implements View.OnClickListener {
        final String choice; Pick(String choice) { this.choice = choice; }
        @Override public void onClick(View v) { earlierPick = choice; render(); }
    }

    /** One switch of the one stored setting: saved, then a pass so held-back sessions go or
     *  wait accordingly (GrowthTrackSettings). */
    private final class Toggle implements View.OnClickListener {
        static final int MASTER = 0, GIRTH = 1, LENGTH = 2, OWN = 3, MANUAL = 4, STOPPED = 5;
        final int which; Toggle(int which) { this.which = which; }
        @Override public void onClick(View v) {
            if (busy || doorShut(null)) return;
            GrowthTrackSettings s = send();
            switch (which) {
                case MASTER: s.on = !s.on; break;
                case GIRTH: s.girthPlan = !s.girthPlan; break;
                case LENGTH: s.lengthPlan = !s.lengthPlan; break;
                case OWN: s.ownRoutines = !s.ownRoutines; break;
                case MANUAL: s.manualRuns = !s.manualRuns; break;
                default: s.stoppedEarly = !s.stoppedEarly; break;
            }
            Store.save(GrowthTrackActivity.this, model);
            render();
            if (snapshot != null && (snapshot.connected || snapshot.needsReconnect))
                manager.startSync(model, new Completed(Result.DONE));
        }
    }

    private void act(Action action) {
        if (busy && action != Action.BACK && action != Action.FINISH) return;
        switch (action) {
            case UNLOCK: askUnlock(); break;
            case FINISH: finish(); break;
            case BACK: onBackPressed(); break;
            case LIST: page = GrowthTrackPages.LIST; render(); break;
            case CHANGE_ACCOUNT: changing = true; page = GrowthTrackPages.CONNECT; localNote = ""; render(); break;
            case SIGN_IN: signIn(false); break;
            case SIGN_IN_AGAIN: signIn(true); break;
            case CANCEL_SIGN_IN:
                busy = true; render();
                manager.run(new CancelSignIn(), new Completed(Result.DONE)); break;
            case SEND_NOW: busy = true; render(); manager.startSync(model, new Completed(Result.DONE)); break;
            case DISCONNECT: {
                AlertDialog d = Ui.dialog(this).setTitle("Disconnect GrowthTrack?")
                    .setMessage("OpenPump stops sending and forgets this account on this phone. Sessions already "
                        + "sent stay in your GrowthTrack account. To remove OpenPump's access there too, remove it "
                        + "under Connected apps in GrowthTrack.")
                    .setPositiveButton("Disconnect", new Decision(Action.RESET)).setNegativeButton("Cancel", null).show();
                Ui.dress(this, d);
                Ui.dangerPositive(d);
                break;
            }
            case RESET: busy = true; render(); manager.reset(new Completed(Result.RESET)); break;
            default: throw new IllegalStateException();
        }
    }

    /** Sign in: the connect page's choices carried into the request; "Sign in again" keeps
     *  the account and asks nothing. The browser opens when the request is made (bug 2). */
    private void signIn(boolean again) {
        if (doorShut(null) || locked()) return;
        String choice = "";
        if (!again) {
            GrowthTrackOutbox.Earlier e = earlier();
            choice = GrowthTrackPages.earlierAnswer(GrowthTrackPages.asksEarlier(e, snapshot, changing), earlierPick, e);
            Store.save(this, model);   // the switches as chosen on this page
        }
        busy = true; localNote = ""; render();
        manager.run(new Begin(again ? "" : choice, !again && changing), new Completed(Result.SIGN_IN));
    }

    private static final class Begin implements GrowthTrackManager.Work {
        final String choice; final boolean change;
        Begin(String choice, boolean change) { this.choice = choice; this.change = change; }
        @Override public Object run(GrowthTrackClient client) throws Exception {
            return change ? client.beginChangeAccount(choice) : client.begin(choice);
        }
    }

    private static final class CancelSignIn implements GrowthTrackManager.Work {
        @Override public Object run(GrowthTrackClient client) throws Exception { client.cancelAuthorization(); return null; }
    }

    /** The browser step: the identity's own way back first (bug 10), then the browser. */
    private void openBrowser(String url) {
        if (doorShut(null)) return;
        IncognitoShell.applyReturn(this, Incognito.identity(model));
        try {
            ownLaunch = true;
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            ownLaunch = false;
            localNote = "No browser is available on this phone. Turn one on to sign in.";
            manager.run(new CancelSignIn(), null);
            render();
        }
    }

    @Override public void onBackPressed() {
        int to = GrowthTrackPages.back(page, entry, changing);
        if (to == GrowthTrackPages.FINISH || busy && page == entry) { super.onBackPressed(); return; }
        if (page == GrowthTrackPages.CONNECT) changing = false;
        page = to; localNote = "";
        render();
    }
}
