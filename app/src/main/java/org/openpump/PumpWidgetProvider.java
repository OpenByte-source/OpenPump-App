package org.openpump;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

/**
 * THE HOME-SCREEN WIDGET (Stage D task 10, N2 — dist/round5-options.html #n2, decision
 * "B — LIVE 4x2 TILE"): "RUNNING · <name>" / "<time> left" over an amber progress bar
 * over "<pressure>" / "step <i> of <n>" while a run is live, the streak line (fix round,
 * see {@link #buildViews}'s idle branch) over "Tap to open" otherwise.
 *
 * WHO WRITES, WHO READS. This class never asks anything about a live run itself — no
 * {@code RunService.Live}, no reference to {@link SessionActivity} beyond the tap
 * target. {@link RunService}'s own {@code Tick} calls {@link #prepareLive} once a second
 * while a run is live, with the exact same name/index/total/countdown/pressure snapshot
 * it just used to rebuild the ongoing notification (see RunService's own class comment
 * for why that snapshot is pulled once and shared, not re-pulled per consumer), and
 * {@code onDestroy} calls {@link #prepareIdle} once the run ends. Both persist the
 * snapshot to {@link #PREFS} before rendering, which is what makes this class read
 * correctly even when RunService never gets to call it at all — a widget instance placed
 * while a run is already active, a device reboot, or the manifest's own 30-minute
 * {@code updatePeriodMillis} floor all land in {@link #onUpdate}, which renders from the
 * persisted snapshot rather than from nothing.
 *
 * WHY THE PERSISTED SNAPSHOT IS CROSS-CHECKED, NOT TRUSTED OUTRIGHT. {@link #buildViews}
 * only believes the persisted "a run is live" flag when {@link RunService#isAlive()}
 * agrees. RunService.onDestroy is not guaranteed to run — the same residual its own class
 * comment documents for a task-swipe that kills the process outright — so a snapshot
 * left mid-write by a dead process must not pin a "RUNNING" tile to a run that is
 * actually over. RunService's static `alive` field (what isAlive() reads) starts false
 * in any process, including one the system spins back up just to deliver this broadcast,
 * so the cross-check self-heals the moment RunService itself is not the thing that
 * answers. This is deliberately {@link RunService#isAlive()}, not
 * {@link RunService#isRunning()}: isRunning() tracks only whether the ongoing
 * notification's startForeground() call itself succeeded, and a vendor ROM refusing the
 * foreground type must not ALSO make the widget lie about a run that is still genuinely
 * live — see RunService's own comment on both fields.
 */
public final class PumpWidgetProvider extends AppWidgetProvider {

    private static final String PREFS = "pump_widget";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_NAME = "name";
    private static final String KEY_IDX = "idx";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_LEFT = "left";
    private static final String KEY_PRESS = "press";

    private static final int REQ_OPEN = 4714;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] appWidgetIds) {
        RemoteViews views = buildViews(ctx);
        for (int id : appWidgetIds) mgr.updateAppWidget(id, views);
    }

    /** The last widget instance was just removed. Clears the persisted snapshot so a
     *  stale "RUNNING" state from this placement can never leak into whatever gets
     *  placed next — the next placement starts from onUpdate's own idle-safe default
     *  (SharedPreferences.getBoolean(KEY_RUNNING, false)) either way, this just makes
     *  that the ACTUAL state on disk too rather than a leftover nothing reads yet. */
    @Override
    public void onDisabled(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Exception ignored) { }
    }

    /**
     * Called by RunService's Tick, once a second, with the same snapshot it just used to
     * rebuild the ongoing notification. Persists it and returns the rendered
     * RemoteViews — the caller (RunService) pushes it to every placed widget instance via
     * its own AppWidgetManager.updateAppWidget call, so a service with nothing to render
     * (no widget placed) never has to build one.
     */
    static RemoteViews prepareLive(Context ctx, String name, int idx, int total,
                                    String left, String press) {
        saveSnapshot(ctx, true, name, idx, total, left, press);
        return buildViews(ctx);
    }

    /** Called by RunService.onDestroy once the run ends, so a widget does not keep
     *  showing a countdown for a session that is over. */
    static RemoteViews prepareIdle(Context ctx) {
        saveSnapshot(ctx, false, "", 0, 0, "", "");
        return buildViews(ctx);
    }

    private static void saveSnapshot(Context ctx, boolean running, String name, int idx,
                                      int total, String left, String press) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, running)
                .putString(KEY_NAME, name)
                .putInt(KEY_IDX, idx)
                .putInt(KEY_TOTAL, total)
                .putString(KEY_LEFT, left)
                .putString(KEY_PRESS, press)
                .apply();
        } catch (Exception ignored) { }
    }

    /**
     * Renders from the persisted snapshot (see the class comment for who writes it and
     * why {@link RunService#isAlive()} gets the final say over the "running" flag).
     * Static and package-private so both {@link #onUpdate} and RunService's push path
     * share exactly one rendering path — two independently-maintained copies of "how the
     * widget looks" is how a live tile and an idle tile drift out of sync with each
     * other.
     */
    static RemoteViews buildViews(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean running = RunService.isAlive() && p.getBoolean(KEY_RUNNING, false);

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_pump);
        v.setInt(R.id.widget_root, "setBackgroundColor", Look.SURFACE);

        // (re-review 3) the app as it is - never a new screen over a live run (appIntent).
        Intent open = RunService.appIntent(ctx);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        v.setOnClickPendingIntent(R.id.widget_root,
            PendingIntent.getActivity(ctx, REQ_OPEN, open, flags));

        if (running) {
            String name = p.getString(KEY_NAME, "");
            int idx = p.getInt(KEY_IDX, 0);
            int total = p.getInt(KEY_TOTAL, 0);
            String left = p.getString(KEY_LEFT, "");
            String press = p.getString(KEY_PRESS, "");

            v.setTextViewText(R.id.widget_status, Session.widgetStatusLine(name));
            v.setTextColor(R.id.widget_status, Look.COMMANDED);

            // Look.DIM, not Look.FAINT: both are "08:12 left" / "step 3 of 5" — full
            // caption phrases the user reads as prose, not a bare label or unit, which is
            // FAINT's own stricter documented scope ("labels and units ONLY" — Look.java's
            // class doc). DIM is the app's general secondary colour and is what a widget
            // this small (10.5sp) needs for guaranteed AA contrast on normal-sized text.
            v.setTextViewText(R.id.widget_time, Session.widgetTimeLeft(left));
            v.setTextColor(R.id.widget_time, Look.DIM);

            v.setViewVisibility(R.id.widget_progress, View.VISIBLE);
            v.setProgressBar(R.id.widget_progress, 100,
                Session.widgetProgressPercent(idx, total), false);

            v.setViewVisibility(R.id.widget_detail_row, View.VISIBLE);
            v.setTextViewText(R.id.widget_pressure, Session.widgetPressureLine(press));
            v.setTextColor(R.id.widget_pressure, Look.COMMANDED);
            v.setTextViewText(R.id.widget_step, Session.widgetStepLine(idx, total));
            v.setTextColor(R.id.widget_step, Look.DIM);
        } else {
            // Idle: the streak line (dist/round5-options.html #n2's own cap text —
            // "idle it shows streak + one-tap START"). Store.load(Context) (pre-existing,
            // static, read-only — Store.java) and Summary.of(log, now, sched) (pre-existing,
            // pure — Summary.java) are neither one a file the concurrently-running
            // routine-share-code task touches (that task is Model.java + SessionActivity's
            // Library screen only), so pulling the streak in here carries no merge-conflict
            // exposure at all — reading Model off disk is not the same as editing
            // Model.java's own text. Reuses Summary.streakLine(Stats) VERBATIM, the exact
            // pure/tested string SessionActivity's own Today screen already draws for this
            // (see kvRow's DAY-STREAK caption), coloured Look.DIM to match how THAT string
            // is coloured there (Ui.DIM — the raw streak NUMBER goes lime when positive,
            // but this narrative caption is drawn in the app's secondary colour regardless
            // of the count, and this widget follows the same rule rather than inventing a
            // new one). THE ONE REMAINING SIMPLIFICATION: the mock's "one-tap START" is not
            // built — it needs a decided default routine/preset with no single obvious
            // answer, a product-scope question rather than a file-conflict one, so a tap
            // still just opens the app, same as the running state.
            String streak = "";
            try {
                // A WIDGET CANNOT WARN, so it must not put an unreadable file aside -
                // that would spend the one warning on a refresh nobody is watching.
                Model m = Store.load(ctx, false);
                if (m != null) {
                    // STAGE H TASK 5 — same deload widening as every other Summary.of call
                    // site (SessionActivity's Today/Trends/dose-heatmap/summary): a deload
                    // week must never read as a broken streak, and this widget is one more
                    // surface the streak appears on. TrainerTab.deloadDayRange is pure (no
                    // `import android`) and takes only the already-loaded model plus
                    // timestamps, so it is safe to call from this BroadcastReceiver/
                    // RemoteViews context — {0,0} (a no-op) for every non-trainer user, so
                    // this is unchanged for the population the feature does not touch.
                    long now = System.currentTimeMillis();
                    long[] deloadDays = TrainerTab.deloadDayRange(m);
                    Summary.Stats st = Summary.of(m.sessLog.all, now, m.sched,
                        deloadDays[0], deloadDays[1]);
                    streak = Summary.streakLine(st);
                }
            } catch (Exception ignored) {
                // A widget render must never crash the broadcast — falls back to "" below.
            }
            v.setTextViewText(R.id.widget_status,
                streak.length() == 0 ? "Not running" : streak);
            v.setTextColor(R.id.widget_status, Look.DIM);

            v.setTextViewText(R.id.widget_time, "Tap to open");
            // DIM: an instruction is prose, and FAINT clears AA only as large text - which
            // is the whole reason its doc restricts it to labels and unit words.
            v.setTextColor(R.id.widget_time, Look.DIM);

            v.setViewVisibility(R.id.widget_progress, View.GONE);
            v.setViewVisibility(R.id.widget_detail_row, View.GONE);
        }
        return v;
    }
}
