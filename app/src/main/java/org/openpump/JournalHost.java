package org.openpump;

import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AbsSeekBar;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The Android half of the {@link Journal}: where touches, dialogs, the heartbeat and the
 * file live. Everything that decides what a line says is in the pure core; this only
 * observes the view tree and moves text to disk.
 *
 * TOUCHES are seen before any view gets them (SessionActivity#dispatchTouchEvent, and a
 * wrapped Window.Callback on every dressed dialog), so a tap on a DISABLED control - which
 * no click listener ever hears about - is recorded like any other. On the way down the
 * deepest control under the finger is found and the screen's signature taken; on the way up
 * one TAP line is written, and 800 ms later the signature is taken again so the watcher can
 * tell a tap that changed nothing from one that did.
 *
 * THE FILE is written on one background thread from a buffer drained about once a second,
 * and synchronously (waiting on that thread) before an export, on pause and on a crash.
 */
final class JournalHost {

    static final long TICK_MS = 500;
    static final long FLUSH_MS = 1000;
    static final long AFTER_TAP_MS = 800;
    static final long NAMES_MS = 10000;

    final Journal j;
    private final SessionActivity a;
    private final Writer out;
    private final ExecutorService io;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int slop;
    private final int[] loc = new int[2];
    private final List<View> dialogs = new ArrayList<View>();
    private long lastFlush, lastNames;
    private boolean closed;

    // the touch in progress
    private View downView;
    private boolean downControl, downInDialog;
    private float downX, downY;
    private int downSig;

    static long now() { return SystemClock.elapsedRealtime(); }

    JournalHost(SessionActivity a, Writer out) {
        this.a = a;
        this.out = out;
        this.j = new Journal(now());
        this.io = Executors.newSingleThreadExecutor();
        this.slop = ViewConfiguration.get(a).getScaledTouchSlop();
        ui.postDelayed(tick, TICK_MS);
    }

    /* ================================================================ the heartbeat */

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            long t = now();
            j.nav(t, a.currentScreen);
            boolean chart = a.currentScreen == Nav.SCR_RUN && a.ecgRunning && a.journalResumed;
            j.tick(t, chart);
            if (t - lastNames >= NAMES_MS) { lastNames = t; names(); }
            if (t - lastFlush >= FLUSH_MS) flush();
            ui.postDelayed(this, TICK_MS);
        }
    };

    /** Register every user-authored string the model holds with the redactor. */
    void names() {
        if (a.model != null) j.redact.registerModel(a.model);
    }

    /* ==================================================================== the file */

    /** Hand what is buffered to the writer thread. */
    void flush() {
        lastFlush = now();
        final String s = j.drain();
        if (s.length() == 0 || out == null || io.isShutdown()) return;
        io.execute(new Write(s));
    }

    /** Flush and wait for it to reach the file (export, pause, crash). */
    void flushNow() {
        flush();
        if (out == null || io.isShutdown()) return;
        try { io.submit(new Write("")).get(2, TimeUnit.SECONDS); } catch (Exception ignored) { }
    }

    /** The end of the log: the summary, then the last flush, then the writer thread. */
    void close(String why) {
        if (closed) return;
        closed = true;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(after);
        j.summary(now(), why);
        flushNow();
        io.shutdown();
    }

    private final class Write implements Runnable {
        private final String s;
        Write(String s) { this.s = s; }
        @Override public void run() {
            synchronized (out) {
                try {
                    if (s.length() > 0) out.write(s);
                    out.flush();
                } catch (Exception ignored) { }
            }
        }
    }

    /* ================================================================== touches */

    /** Every touch in the activity or in a dressed dialog, before it is dispatched. */
    void touch(MotionEvent ev, View root, boolean inDialog) {
        if (closed || root == null) return;
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            ui.removeCallbacks(after);
            int x = (int) ev.getRawX(), y = (int) ev.getRawY();
            View deepest = hit(root, x, y);
            View c = deepest;
            while (c != null && !actionable(c)) {
                Object p = c.getParent();
                c = (p instanceof View && c != root) ? (View) p : null;
            }
            downControl = c != null;
            downView = c != null ? c : deepest;
            downInDialog = inDialog;
            downX = ev.getRawX();
            downY = ev.getRawY();
            // A text field's effect is focus and a keyboard - another window, not this
            // screen - so a tap on one is recorded but not checked for an effect.
            downSig = downControl && downView.isEnabled() && !selected(downView)
                && !(downView instanceof EditText) ? signature() : 0;
        } else if (act == MotionEvent.ACTION_UP) {
            View v = downView;
            downView = null;
            if (v == null) return;
            boolean seek = v instanceof AbsSeekBar;
            float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
            if (!seek && dx * dx + dy * dy > (float) slop * slop) return;     // a scroll
            String val = seek ? String.valueOf(((ProgressBar) v).getProgress()) : null;
            // Off any control, only a text the finger was actually on names the spot.
            String label = downControl ? describe(v)
                : v instanceof TextView && !(v instanceof EditText) ? describe(v) : "empty area";
            j.tap(now(), label, v.isEnabled(), downControl, downInDialog, val, downSig);
            if (downSig != 0) ui.postDelayed(after, AFTER_TAP_MS);
        } else if (act == MotionEvent.ACTION_CANCEL) {
            downView = null;
        }
    }

    private final Runnable after = new Runnable() {
        @Override public void run() { if (!closed) j.tapAfter(now(), signature()); }
    };

    /** The back key, wherever it is pressed. */
    void back() { if (!closed) j.back(now()); }

    /** The deepest visible view containing a screen point. */
    private View hit(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE) return null;
        v.getLocationOnScreen(loc);
        if (x < loc[0] || y < loc[1] || x >= loc[0] + v.getWidth() || y >= loc[1] + v.getHeight())
            return null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View h = hit(g.getChildAt(i), x, y);
                if (h != null) return h;
            }
        }
        return v;
    }

    private static boolean actionable(View v) {
        return v.isClickable() || v.isLongClickable() || v instanceof AbsSeekBar
            || v instanceof EditText || v instanceof CompoundButton;
    }

    /** A bar tab or chip that is already the selected one: tapping it again is allowed to
     *  change nothing, so it is not checked for an effect. */
    private static boolean selected(View v) {
        if (v.isSelected()) return true;
        CharSequence cd = v.getContentDescription();
        if (cd == null) return false;
        String s = cd.toString();
        return s.endsWith(", selected");
    }

    /**
     * What a person would call a control: its spoken name, else its text, else the first
     * text inside it, else its class. A text field is described by its hint and length -
     * never by what was typed into it.
     */
    static String describe(View v) {
        if (v instanceof EditText) {
            EditText e = (EditText) v;
            return Redact.field(e.getHint(), e.getText() == null ? 0 : e.getText().length());
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) return cd.toString();
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) return t.toString();
        }
        String inner = firstText(v, 0);
        return inner != null ? inner : v.getClass().getSimpleName();
    }

    private static String firstText(View v, int depth) {
        if (!(v instanceof ViewGroup) || depth > 4) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c instanceof EditText) continue;
            if (c instanceof TextView) {
                CharSequence t = ((TextView) c).getText();
                if (t != null && t.length() > 0) return t.toString();
            }
            String s = firstText(c, depth + 1);
            if (s != null) return s;
        }
        return null;
    }

    /**
     * A cheap fingerprint of everything visible: texts, enabled/checked/selected states and
     * the view count, over the activity and every showing dialog, plus whether this window
     * has focus (a platform picker or share sheet takes it). On screens with a live readout
     * any text containing a digit is left out, or the ticking countdown would make every tap
     * look effective.
     */
    int signature() {
        boolean live = liveScreen(a.currentScreen);
        int h = a.hasWindowFocus() ? 17 : 19;
        View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
        if (decor != null) h = walk(decor, h, live);
        for (int i = 0; i < dialogs.size(); i++) h = walk(dialogs.get(i), h * 31 + 3, live);
        return h == 0 ? 1 : h;
    }

    private static int walk(View v, int h, boolean skipDigits) {
        if (v.getVisibility() != View.VISIBLE) return h * 31 + 7;
        h = h * 31 + (v.isEnabled() ? 1 : 2) + (v.isSelected() ? 4 : 0) + (v.isActivated() ? 8 : 0)
            + (v.isFocused() ? 16 : 0);
        if (v instanceof CompoundButton) h = h * 31 + (((CompoundButton) v).isChecked() ? 1 : 0);
        if (v instanceof AbsSeekBar) h = h * 31 + ((AbsSeekBar) v).getProgress();
        if (v instanceof EditText) {
            CharSequence t = ((EditText) v).getText();
            h = h * 31 + (t == null ? 0 : t.length());
        } else if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && !(skipDigits && hasDigit(t))) h = h * 31 + t.toString().hashCode();
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            h = h * 31 + g.getChildCount();
            for (int i = 0; i < g.getChildCount(); i++) h = walk(g.getChildAt(i), h, skipDigits);
        }
        return h;
    }

    private static boolean hasDigit(CharSequence t) {
        for (int i = 0; i < t.length(); i++) { char c = t.charAt(i); if (c >= '0' && c <= '9') return true; }
        return false;
    }

    /** Screens whose text changes by itself - a countdown, a live pressure, a radar. */
    static boolean liveScreen(int s) {
        switch (s) {
            case Nav.SCR_RUN: case Nav.SCR_HOLD: case Nav.SCR_SEAL: case Nav.SCR_RELEASE:
            case Nav.SCR_ASSESS: case Nav.SCR_GUIDED: case Nav.SCR_CONNECT:
            case Nav.SCR_VALIDATE_RUN: case Nav.SCR_DIAGNOSTICS: case Nav.SCR_LINK_LOST:
            case Nav.SCR_BASELINE: case Nav.SCR_CAMERA: case Nav.SCR_MEASURE_AFTER:
                return true;
            default:
                return false;
        }
    }

    /* ================================================================== dialogs */

    /** Called from Ui.dress: record the dialog's opening, closing and every touch in it. */
    void watch(Dialog d) {
        if (d == null || closed) return;
        Window w = d.getWindow();
        if (w == null) return;
        Window.Callback cb = w.getCallback();
        if (cb == null || cb instanceof DialogWatch) return;
        w.setCallback(new DialogWatch(cb, w));
    }

    private final class DialogWatch implements Window.Callback {
        private final Window.Callback in;
        private final Window w;
        DialogWatch(Window.Callback in, Window w) { this.in = in; this.w = w; }

        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            touch(e, w.getDecorView(), true);
            return in.dispatchTouchEvent(e);
        }
        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) back();
            return in.dispatchKeyEvent(e);
        }
        @Override public void onAttachedToWindow() {
            in.onAttachedToWindow();
            View decor = w.getDecorView();
            if (!dialogs.contains(decor)) dialogs.add(decor);
            if (!closed) {
                // The decor nests the dialog's content a few levels deep, so the search
                // starts with a larger depth allowance than a control's label gets.
                String title = firstText(decor, -8);
                j.dialog(now(), true, title == null ? "" : title);
            }
        }
        @Override public void onDetachedFromWindow() {
            in.onDetachedFromWindow();
            dialogs.remove(w.getDecorView());
            if (!closed) j.dialog(now(), false, null);
        }

        @Override public boolean dispatchKeyShortcutEvent(KeyEvent e) { return in.dispatchKeyShortcutEvent(e); }
        @Override public boolean dispatchTrackballEvent(MotionEvent e) { return in.dispatchTrackballEvent(e); }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent e) { return in.dispatchGenericMotionEvent(e); }
        @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent e) {
            return in.dispatchPopulateAccessibilityEvent(e);
        }
        @Override public View onCreatePanelView(int f) { return in.onCreatePanelView(f); }
        @Override public boolean onCreatePanelMenu(int f, Menu m) { return in.onCreatePanelMenu(f, m); }
        @Override public boolean onPreparePanel(int f, View v, Menu m) { return in.onPreparePanel(f, v, m); }
        @Override public boolean onMenuOpened(int f, Menu m) { return in.onMenuOpened(f, m); }
        @Override public boolean onMenuItemSelected(int f, MenuItem i) { return in.onMenuItemSelected(f, i); }
        @Override public void onWindowAttributesChanged(WindowManager.LayoutParams p) { in.onWindowAttributesChanged(p); }
        @Override public void onContentChanged() { in.onContentChanged(); }
        @Override public void onWindowFocusChanged(boolean f) { in.onWindowFocusChanged(f); }
        @Override public void onPanelClosed(int f, Menu m) { in.onPanelClosed(f, m); }
        @Override public boolean onSearchRequested() { return in.onSearchRequested(); }
        @Override public boolean onSearchRequested(SearchEvent e) { return in.onSearchRequested(e); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c) {
            return in.onWindowStartingActionMode(c);
        }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c, int type) {
            return in.onWindowStartingActionMode(c, type);
        }
        @Override public void onActionModeStarted(ActionMode m) { in.onActionModeStarted(m); }
        @Override public void onActionModeFinished(ActionMode m) { in.onActionModeFinished(m); }
    }
}
