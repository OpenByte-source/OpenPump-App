package org.openpump;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.RemoteViews;

/**
 * THE BACKGROUND HANDLE FOR A LIVE RUN — a foreground service that exists so that leaving
 * the app is no longer the same act as giving up.
 *
 * WHY THIS EXISTS AT ALL. Until this class, {@link SessionActivity#onStop} treated every
 * exit from the foreground as an abandonment: Home, Recents, a quick-switch, an incoming
 * call — each one aborted the routine and vented. That rule was not paranoia, it was the
 * only honest option available, and its reasoning is worth restating because this class
 * changes the CONCLUSION without changing the PREMISE. The premise was: a backgrounded
 * Activity cannot be relied on to keep driving the pump — its timers are throttled, its
 * process is a candidate for death, and there is no visible handle by which the user could
 * stop a routine they can no longer see. Leaving a person under vacuum in that state is
 * worse than aborting a session they will have to repeat.
 *
 * A FOREGROUND SERVICE CHANGES THE PREMISE. With one running the process is not an
 * ordinary background candidate, a partial wakelock keeps the CPU alive through the
 * screen going off, and — the part that actually matters for safety — the run has a
 * VISIBLE HANDLE: an ongoing notification, like a phone timer, that says what is running,
 * which preset of how many, how long is left, what the cuff pressure is, and carries
 * STOP and HOLD buttons. The user can end the run — or pause it — from anywhere on the
 * phone, without finding the app again. That is the whole justification. The rule is not
 * "backgrounding is now fine"; it is "backgrounding is fine WHILE THERE IS A HANDLE", and
 * onStop() enforces exactly that: if this service is not running, the old abort-and-vent
 * rule applies unchanged. There is no path that leaves a live run with neither UI nor
 * service.
 *
 * WHAT THIS CLASS IS NOT. It is NOT a second owner of the GATT connection. It never
 * touches {@link PumpLink}, never builds a {@link Proto} frame and never transmits
 * anything. The Activity keeps owning the link, the session model, the vent watch and
 * every timer, exactly as before — this is a LIFECYCLE AND NOTIFICATION SHELL wrapped
 * around it. Two GATT owners in one process is the shape of a whole new class of defect
 * (two vent watches, two stop retries, two disagreeing ideas of what the pump is doing),
 * and none of the safety machinery in this app is written to survive it. The STOP button
 * therefore does not stop the pump itself: it calls back into the Activity's own
 * finishSession(true) chokepoint — the identical path the on-screen STOP button, the
 * header back button and the link-loss auto-stop already use — so a stop from the
 * notification files the attempt and arms the retrying, telemetry-confirmed StopWork in
 * exactly one place.
 *
 * THE HOLD BUTTON (T7, dist/round7-options.html) is the same shape and does not touch the
 * pump either: it calls back into the Activity's own hold toggle, the identical branch
 * {@code HoldTap} already runs on screen ({@code if (holding) exitHold(true); else
 * enterHold();}) — never a second, parallel decision about whether the cuff is being
 * held. A hold or a resume from the notification is therefore indistinguishable from one
 * on screen, for the same reason a stop from the notification is indistinguishable from
 * the on-screen STOP.
 *
 * WHAT IT READS AND HOW OFTEN. Nothing is pushed IN. The service holds a {@link Live}
 * back-reference to the Activity and PULLS the five display values once a second
 * (refreshLiveSnapshot()), then re-posts the notification from that one pull. One
 * ticker, in one place, so the notification cannot drift out of step with the screen the
 * way two independently-updated copies of the same numbers always eventually do. The
 * same tick asks {@link Live#stillLive()}, and the service STOPS ITSELF the moment the
 * answer is no — the Activity is not required to remember to shut it down, which is the
 * failure mode that would otherwise leave a "preset 3 of 6" notification pinned to the
 * status bar over a finished session.
 *
 * THE WIDGET (Stage D task 10, N2) IS PUSHED OUT, not pulled from. Once the same tick has
 * refreshed its own snapshot for the notification, it hands that snapshot to
 * {@link PumpWidgetProvider#prepareLive} and pushes the result straight through
 * AppWidgetManager#updateAppWidget — never back through {@link Live}, and never anything
 * the widget has to ask the Activity for. That distinction matters: {@link Live} is
 * implemented by the Activity, and while an ordinary background (Home, Recents) leaves
 * the Activity object alive to answer it, a widget that queried Live directly would carry
 * the same "Activity may not exist" fragility this service exists to route around in the
 * first place. Reading the widget's values off this service's own just-refreshed fields
 * keeps the widget exactly as independent of the Activity as the notification already is.
 *
 * THE TASK SWIPE, STATED HONESTLY. When the user swipes the app away from Recents,
 * {@link #onTaskRemoved} runs and the Activity is destroyed. The Activity's onDestroy()
 * sends its last-resort Proto.stop() as it dies — that is unchanged and it is the only
 * thing in this app that can send anything at that moment. This service deliberately does
 * NOT attempt its own GATT write to "make sure": it has no link, no baseline, no vent
 * watcher and no way to confirm anything, so opening a second connection from a service
 * whose process is being torn down would be a new and far more dangerous path than the
 * one it was trying to protect. What it does instead is TELL THE TRUTH: it posts one
 * high-priority notification saying the run was interrupted and that the pump was told to
 * stop, and stops itself. If Android killed the process without running either callback
 * — which it is permitted to do — that run is lost exactly as it was before this class
 * existed. That residual is not closed here and is not claimed to be.
 *
 * NOTIFICATIONS AND PERMISSION. The channel is IMPORTANCE_LOW: an ongoing run is
 * information, not an alert, and it must never make a sound. POST_NOTIFICATIONS is a
 * runtime permission on API 33+, already declared for the training reminder and already
 * requested through Settings' reminder toggle. If it is NOT granted, startForeground()
 * still succeeds and the service still runs — Android suppresses the notification's
 * VISIBILITY, it does not refuse the foreground start — so the user gets a warning toast
 * saying the run will continue but no notification will show. That is a degraded handle,
 * not an absent one (the app is still resumable from the launcher), and it is the reason
 * the toast is worded as a warning rather than a note.
 */
public final class RunService extends Service {

    /**
     * What the service needs from the Activity, and the two things it is allowed to ask it
     * to do. Deliberately tiny and deliberately PULL-shaped: the service asks, the
     * Activity answers from the same fields the run screen renders from, so there is one
     * source for both. Nothing here touches the pump.
     */
    public interface Live {
        /** Is any pump-commanding activity still in progress — a run, a seal check, an
         *  assessment, a validation routine or a self-test? False ends the service. */
        boolean stillLive();
        /** The routine's name, or the name of whatever else is driving the pump. */
        String liveName();
        /** P2 - release an outstanding HOLD, from its notification. The same vent the
         *  on-screen control performs; nothing is written to the pump from the service. */
        void releaseHoldFromNotification();
        /** H1 (the safety review) - where a measurement hold stands, for this service's
         *  lifetime ONLY (HoldForeground#phase): HELD, VENTING and UNCONFIRMED (the watch gave
         *  up, nobody has seen it yet) keep the service in the foreground; UNCONFIRMED_SEEN lets
         *  it go but keeps its notice; NONE does not. Deliberately not part of stillLive():
         *  that is "a run", and onStop, kill detection and the widget all read it as one. */
        int holdPhase();
        /** (the safety review of the in-run Hold's limit) Why the run stopped - "Stopped: the
         *  hold reached its limit (5:00)" - while its own stop is being confirmed; null for
         *  every other stop. The venting notice leads with it (HoldForeground#ventingText). */
        String liveStopReason();
        /** 1-based preset index, or 0 when the phase has no preset sequence. */
        int livePresetIndex();
        /** Number of presets in the plan, or 0 when there is no plan. */
        int livePresetTotal();
        /** Time left in the current preset, already formatted; "" when there is none. */
        String liveCountdown();
        /** (the owner's decision) While the run's Hold is up, when it vents ("vents in
         *  4:32", InRunHold#ventsIn); "" when no Hold is up. */
        String liveHoldVentsIn();
        /** Cuff pressure in the display unit, already formatted; "" when unknown. */
        String livePressure();
        /** A step done by hand, named for the notification's lead ("By hand · Tunica release",
         *  ByHand#notification); "" for every other step (the owner's decision, 2026-10-07). */
        String livePhase();
        /** True while the run is held right now (the user's pause) — read fresh each
         *  tick, like every other Live value, so the notification's HOLD/RESUME action
         *  label never claims the opposite of what tapping it would actually do. */
        boolean liveHolding();
        /** True when "Discreet notifications" is on (Settings › Privacy; T17 before it). The
         *  notification's title and body become "Session running" / "12:30 left" instead of
         *  the routine's name and the changing numbers, and the lock screen shows "Contents
         *  hidden" — read fresh each tick, like every other Live value, so flipping the
         *  toggle mid-run takes effect on the very next tick without restarting the service.
         *  STOP and HOLD stay either way. */
        boolean discreetMode();
        /** Incognito (0.10): "Safety warnings on the lock screen" - the safety notices keep
         *  their plain words once unlocked and show neutral ones while locked (option 3). */
        boolean neutralSafetyNotices();
        /** The STOP button was pressed in the notification. Routes to the Activity's own
         *  finishSession(true) chokepoint — never to a write from here. */
        void stopFromNotification();
        /** The HOLD button was pressed in the notification (T7). Routes to the exact same
         *  toggle {@code HoldTap} runs on screen — {@code if (holding) exitHold(true); else
         *  enterHold();} — never a parallel path and never a write from here. */
        void holdFromNotification();
    }

    public static final String CHANNEL_ID = "live-run";
    public static final String CHANNEL_NAME = "Live run";
    public static final String CHANNEL_INTERRUPTED_ID = "run-interrupted";
    public static final String CHANNEL_INTERRUPTED_NAME = "Interrupted run";

    /**
     * (the incognito safety review, C1) THE SAFETY ALERTS' OWN CHANNEL: HIGH, with sound and
     * vibration - "Vent not confirmed", "The app closed during a hold" and the ring timer.
     *
     * The first two used to be posted on the hold's LOW, silent channel, and "Vent not
     * confirmed" with setOnlyAlertOnce, which silences an update whatever the channel: no
     * sound, no vibration, no heads-up, the shade's Silent group - and on some phones nothing
     * in the status bar or on the lock screen at all. That was already true in 0.9.0 (the
     * notification's STOP pressed from a pocket, the hold's limit, the lost-link stop with the
     * screen off), and quick hide's STOP, its default, made it the ordinary path: the app is
     * hidden the moment the stop is sent, and the one word that the vent was never confirmed
     * was said silently. A stop that cannot be confirmed must reach the person.
     *
     * A NEW ID ON PURPOSE. An app cannot raise an existing channel's importance (creating it
     * again can only lower it), and a deleted channel comes back with its old settings. So the
     * hold's channel stays as it is, for the held countdown and the venting notice -
     * information, never an alarm - and the alerts are posted here (WiringCheck invariant 197).
     */
    public static final String CHANNEL_ALERT_ID = "safety-alert";
    public static final String CHANNEL_ALERT_NAME = "Safety alerts";

    /**
     * P2 - THE HOLD'S OWN NOTIFICATION, AND (H1, the safety review) THE HOLD'S FOREGROUND.
     *
     * A run has had a handle outside the app since RunService existed. A hold needs one for
     * the same reason: the screen is not always in sight while the cuff is at vacuum.
     *
     * A LIVE HOLD KEEPS THIS SERVICE IN THE FOREGROUND, with its partial wakelock, so the
     * process survives and the hold's limit fires on time (SAFETY.md #5: leaving a screen
     * never orphans a pump under pressure). The limit is a tick in this process. Without a
     * foreground service, a routine that ended into a hold with the app in the background, or
     * a screen that went off with the app on top before another app (the lock-screen camera,
     * an incoming call) came to the front with no callback to vent it, left the hold in a
     * cached process Android may end: no limit, a frozen notice, a RELEASE with nothing behind
     * it. This note used to say the hold was deliberately NOT a foreground service; the
     * safety review overruled that, and this is the reasoning now.
     *
     * THE HOLD IS A TERM OF ITS OWN (HoldForeground#phase, read through Live#holdPhase), and
     * only this service's LIFETIME reads it. It is not in liveWork(): onStop asks
     * `liveWork() && isRunning()` to decide that leaving is not an abandonment, so every exit
     * from the app still vents a hold; `foreground` (isRunning) and `alive` (the widget, kill
     * detection) keep meaning "a run" - hold mode never sets either.
     *
     * ONE NOTICE, NOT TWO. In hold mode the hold's own notification IS the foreground
     * notification (same id), and it says what is true: "Pressure held at X / Vents on its own
     * in m:ss" with RELEASE while the hold is held - a countdown this process now lives to
     * keep - and "Venting the pump / Waiting for it to report the pressure falling" while its
     * vent is confirmed (hideHoldNotification cannot take a foreground notification down, and
     * the process has to live to confirm the fall). While a run is in the foreground - the
     * moment a routine ends into a hold, or a run starts from a pre-session hold - the run's
     * handle protects the process and the hold's notice is a plain one; the Tick hands over.
     *
     * What this notice must never do is promise what cannot happen (HoldNotice). Its countdown
     * and its RELEASE are true only while the screen that holds the pump is alive in this
     * process; when there is none, it is replaced by one that says so (closedHoldNotice),
     * never left frozen and never left with a button that does nothing.
     */
    public static final String CHANNEL_HOLD_ID = "live-hold";
    public static final String CHANNEL_HOLD_NAME = "Pressure held";
    public static final String ACTION_RELEASE = "org.openpump.HOLD_RELEASE";
    /** H1 - start (or keep) this service in the foreground for a hold. */
    public static final String ACTION_HOLD_FG = "org.openpump.HOLD_FOREGROUND";
    private static final int NOTIFICATION_HOLD_ID = 4714;
    /** The closed notice's own id: a foreground notification's id is cancelled with its
     *  service when the process ends, and the closed notice must outlive exactly that. Not
     *  4717, which Reminders' other-track reminder posts under: a reminder firing would have
     *  replaced the only word left about a hold, or been replaced by it (re-review 3). */
    private static final int NOTIFICATION_CLOSED_ID = 4720;

    /** The running service in this process, or null. Main thread only; set in onCreate and
     *  cleared in onDestroy, so the Activity's calls reach it without a second start. */
    private static RunService instance;
    /** True while the hold's notification is this service's foreground notification. */
    private static boolean holdForeground;
    /** Which hold notice is up (HoldForeground's phases; NONE for none), so the Tick redraws
     *  it only when the phase moves on - and (C1) so "Vent not confirmed" rings once per
     *  give-up: posted over anything else it rings, posted again over itself it does not
     *  (HoldForeground#unconfirmedRings). */
    private static int shownNotice = HoldForeground.NONE;
    /** (the second re-review) The "couldn't confirm" notice was left up with no service behind
     *  it - the person has the give-up in front of them. It goes when the hold is over
     *  (holdSettled), or when a retry's foreground replaces it (same id). */
    private static boolean unconfirmedLeft;
    /** The last hold notice drawn, so the service can start its foreground with it. */
    private static String holdPressure = "";
    private static long holdLeftSec;

    /** The held notice: the countdown and RELEASE - and, discreet (incognito), "Session
     *  running / Ends on its own in 4:32" with the lock screen hidden, RELEASE still on both. */
    private static Notification buildHold(Context c, String pressure, long leftSec) {
        Intent rel = new Intent(c, RunService.class);
        rel.setAction(ACTION_RELEASE);
        PendingIntent relPi = PendingIntent.getService(c, 4715, rel,
            Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT);
        boolean discreet = discreetNow(c);
        Notification.Builder b = builder(c, CHANNEL_HOLD_ID, Notification.PRIORITY_LOW);
        b.setContentTitle(discreet ? Incognito.RUN_TITLE : HoldForeground.heldTitle(pressure))
         .setContentText(discreet ? Incognito.heldText(leftSec) : HoldForeground.heldText(leftSec))
         .setSmallIcon(discreet ? R.drawable.ic_notification : android.R.drawable.stat_sys_warning)
         .setOngoing(true)
         .setOnlyAlertOnce(true);
        Notification.Action release = new Notification.Action.Builder(null, "RELEASE", relPi).build();
        b.addAction(release);
        if (discreet) discreetOnLockScreen(c, b, CHANNEL_HOLD_ID, R.drawable.ic_notification,
                                           release);
        else b.setVisibility(Notification.VISIBILITY_PUBLIC);
        return b.build();
    }

    /* ============================================================ INCOGNITO (0.10)
     *
     * WHAT THE NOTICES MAY SAY WHERE OTHERS CAN SEE THEM - the owner's decisions of 2026-09-26.
     *
     * DISCREET NOTIFICATIONS (the run's and the hold's own): "Session running" and the time
     * left, never a pressure, a routine's or a track's name; PRIVATE on the lock screen, with a
     * public version that reads "Contents hidden". STOP and HOLD, and a hold's RELEASE, are on
     * BOTH - the private one and the lock-screen one (WiringCheck invariants 190, 191): the lock
     * screen shows the same buttons, only the words go.
     *
     * SAFETY NOTICES (the vent not confirmed, the venting notice that carries a link-lost or a
     * limit's auto-stop, the app closed during a hold, the interrupted run, the ring timer):
     * option 3. Their own words, channel, priority, icon and tap are exactly what they were -
     * plain the moment the phone is unlocked - and with "Safety warnings on the lock screen" on,
     * the lock screen shows "Session needs attention now / Unlock to see what to do". The
     * neutral words go into the public version and nowhere else (invariant 192).
     *
     * HONEST LIMIT, said in Settings too: the lock screen shows a public version only when the
     * phone is set to hide sensitive notification content there; set to show everything, it
     * shows the private one - which for a discreet notice is already neutral, and for a safety
     * notice is its plain warning.
     *
     * With no screen in this process (a notice posted by a process that restarted) the saved
     * settings are read, so the words do not fall back to the plain ones for want of a screen.
     */

    /** "Discreet notifications", from the live screen; without one, as the last screen had
     *  them (M2); in a process that never had a screen, the saved settings. */
    static boolean discreetNow(Context c) {
        Live l = live;
        if (l != null) return l.discreetMode();
        Boolean last = lastDiscreet;
        if (last != null) return last.booleanValue();
        return savedPrivacy(c)[0];
    }

    /** "Safety warnings on the lock screen", the same way. */
    static boolean neutralSafetyNow(Context c) {
        Live l = live;
        if (l != null) return l.neutralSafetyNotices();
        Boolean last = lastNeutral;
        if (last != null) return last.booleanValue();
        return savedPrivacy(c)[1];
    }

    /**
     * (the incognito safety review, M2) THE TWO SWITCHES AS THE LAST SCREEN IN THIS PROCESS HAD
     * THEM, kept as it is set live and as it goes. Without them, a notice posted with no screen
     * - "the run was interrupted" in onTaskRemoved, after onDestroy has let the screen go -
     * waited for the whole model to be read and parsed on the main thread (Store.load), while
     * the process was being torn down, and rewrote Store's process-wide load-failure fields
     * from a service. Only a process that never had a screen (a RELEASE tap that started one)
     * still reads the saved settings.
     */
    private static Boolean lastDiscreet, lastNeutral;

    private static void rememberPrivacy(Live l) {
        if (l == null) return;
        try {
            lastDiscreet = Boolean.valueOf(l.discreetMode());
            lastNeutral = Boolean.valueOf(l.neutralSafetyNotices());
        } catch (Exception ignored) { }
    }

    private static boolean[] savedPrivacy(Context c) {
        try {
            Model m = c == null ? null : Store.load(c, false);
            if (m != null) return new boolean[]{ m.discreetNotifications, m.neutralSafetyNotices };
        } catch (Exception ignored) { }
        return new boolean[]{ false, false };
    }

    /** A builder on `channel` (API 26+), or at `priority` below it. */
    static Notification.Builder builder(Context c, String channel, int priority) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(c, channel);
        return new Notification.Builder(c).setPriority(priority);
    }

    /** A discreet notice's lock-screen version: "Contents hidden", with the same buttons. */
    static void discreetOnLockScreen(Context c, Notification.Builder b, String channel,
                                     int icon, Notification.Action... keep) {
        Notification.Builder p = builder(c, channel, Notification.PRIORITY_LOW);
        p.setContentText(Incognito.HIDDEN).setSmallIcon(icon).setShowWhen(false);
        for (int i = 0; i < keep.length; i++) p.addAction(keep[i]);
        b.setVisibility(Notification.VISIBILITY_PRIVATE);
        b.setPublicVersion(p.build());
    }

    /** A safety notice's lock screen: left exactly as its caller set it, or, with the neutral
     *  words chosen, a public version that says only that it needs attention now. The notice
     *  itself - words, channel, priority, tap - is never touched here. */
    static void safetyOnLockScreen(Context c, Notification.Builder b, String channel,
                                   int priority, PendingIntent tap) {
        if (!neutralSafetyNow(c)) return;
        Notification.Builder p = builder(c, channel, priority);
        p.setContentTitle(Incognito.SAFETY_LOCKED_TITLE)
         .setContentText(Incognito.SAFETY_LOCKED_TEXT)
         .setSmallIcon(android.R.drawable.stat_sys_warning);
        if (tap != null) p.setContentIntent(tap);
        b.setVisibility(Notification.VISIBILITY_PRIVATE);
        b.setPublicVersion(p.build());
    }

    /** (the incognito safety review, M1) The venting notice's lock screen: left as its caller
     *  set it, or, with the neutral words chosen, a public version that says only that the
     *  session is ending - calm, because it is not an alarm. The notice itself is untouched. */
    static void calmOnLockScreen(Context c, Notification.Builder b, String channel,
                                 PendingIntent tap) {
        if (!neutralSafetyNow(c)) return;
        Notification.Builder p = builder(c, channel, Notification.PRIORITY_LOW);
        p.setContentTitle(Incognito.ENDING_LOCKED_TITLE)
         .setSmallIcon(R.drawable.ic_notification)
         .setShowWhen(false);
        if (tap != null) p.setContentIntent(tap);
        b.setVisibility(Notification.VISIBILITY_PRIVATE);
        b.setPublicVersion(p.build());
    }

    /** (re-review 3, B) Opens the app - where the vent is watched, retried and settled. The
     *  hold's own notices carry it: a "disconnect the tubing" notice that goes nowhere when
     *  tapped leaves the person with no way to the give-up's answers. */
    private static final int REQ_OPEN_HOLD = 4719;

    /**
     * (re-review 3, found on the emulator) THE APP, BROUGHT BACK AS IT WAS - the launcher's own
     * intent. Every tap into the app (the run's and the hold's notices, the closed notice, the
     * widget, the reminders) used FLAG_ACTIVITY_CLEAR_TOP, and SessionActivity's launch mode is
     * standard: CLEAR_TOP on a standard activity already in its task FINISHES it and makes a new
     * one. That screen is the one that owns the link, so a tap on "Vent not confirmed" destroyed
     * it - its onDestroy sent the last stop and dropped the vent watch and its question - and a
     * tap on the run's own notification would end the run the same way. The launcher intent
     * brings the task to the front untouched, or starts the app when nothing is running
     * (WiringCheck invariant 106).
     *
     * (re-review 4) That alone brought the task back only when the task had been opened with
     * this same intent: after a shortcut launch it stacked a SECOND SessionActivity, which took
     * this service away from the running one. SessionActivity is singleTask now, so this - and
     * every other entry - reaches the one screen through onNewIntent (invariant 108).
     */
    public static Intent appIntent(Context c) {
        Intent open = new Intent(c, SessionActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return open;
    }

    private static PendingIntent openApp(Context c, int req) {
        return PendingIntent.getActivity(c, req, appIntent(c),
            Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The venting notice: what is happening, nothing claimed, and nothing to press - and
     *  (the safety review) why the run stopped, when a limit stopped it. It is on the hold's
     *  quiet channel, and it is NOT a safety alert (the incognito safety review, M1): every
     *  STOP, release and run end shows it for a few seconds, so with the neutral lock-screen
     *  words chosen it reads "Session ending" there (calmOnLockScreen), never the alarm's "needs
     *  attention now" - a word shown on every ordinary stop is a word people learn to ignore.
     *  The alarm is "Vent not confirmed", which follows it when the fall is never seen. */
    private static Notification buildVenting(Context c) {
        Live l = live;
        String text = HoldForeground.ventingText(l == null ? null : l.liveStopReason());
        PendingIntent tap = openApp(c, REQ_OPEN_HOLD);
        Notification.Builder b = builder(c, CHANNEL_HOLD_ID, Notification.PRIORITY_LOW);
        b.setContentTitle(HoldForeground.VENTING_TITLE)
         .setContentText(text)
         .setStyle(new Notification.BigTextStyle().bigText(text))
         .setContentIntent(tap)
         .setSmallIcon(android.R.drawable.stat_sys_warning)
         .setOngoing(true)
         .setOnlyAlertOnce(true);
        b.setVisibility(Notification.VISIBILITY_PUBLIC);
        // Incognito: plain once unlocked; with the neutral words chosen, calm on the lock screen.
        calmOnLockScreen(c, b, CHANNEL_HOLD_ID, tap);
        return b.build();
    }

    /**
     * (the second re-review) The watch gave up: what is true, what to do, nothing to press -
     * and (re-review 3, B) a tap opens the app, where the give-up is answered (invariant 106).
     *
     * (the incognito safety review, C1) A SAFETY ALERT: the alert channel (HIGH, sound and
     * vibration), PRIORITY_HIGH with the default sound and vibration below API 26, and the
     * alarm category, so Do Not Disturb's "alarms" lets it through. It rings as it replaces the
     * quiet venting notice on the same id - nothing here says "only alert once" to that - and
     * once per give-up: posted again over itself (the service handing it over, keeping it,
     * leaving it) it updates without a second ring (HoldForeground#unconfirmedRings). A retry
     * puts the venting notice back, and the next give-up rings again.
     */
    private static Notification buildUnconfirmed(Context c) {
        PendingIntent tap = openApp(c, REQ_OPEN_HOLD);
        Notification.Builder b = builder(c, CHANNEL_ALERT_ID, Notification.PRIORITY_HIGH);
        b.setContentTitle(HoldForeground.UNCONFIRMED_TITLE)
         .setContentText(HoldForeground.UNCONFIRMED_TEXT)
         .setStyle(new Notification.BigTextStyle().bigText(HoldForeground.UNCONFIRMED_TEXT))
         .setContentIntent(openApp(c, REQ_OPEN_HOLD))
         .setSmallIcon(android.R.drawable.stat_sys_warning)
         .setCategory(Notification.CATEGORY_ALARM)
         .setDefaults(Notification.DEFAULT_ALL)
         .setOngoing(true);
        if (!HoldForeground.unconfirmedRings(shownNotice)) b.setOnlyAlertOnce(true);
        b.setVisibility(Notification.VISIBILITY_PUBLIC);
        // Incognito: a safety notice - plain once unlocked, neutral on the lock screen if chosen.
        safetyOnLockScreen(c, b, CHANNEL_ALERT_ID, Notification.PRIORITY_HIGH, tap);
        return b.build();
    }

    /** The notice a hold in this phase is shown with. */
    private static Notification buildFor(Context c, int phase) {
        if (phase == HoldForeground.VENTING) return buildVenting(c);
        if (phase == HoldForeground.UNCONFIRMED || phase == HoldForeground.UNCONFIRMED_SEEN)
            return buildUnconfirmed(c);
        return buildHold(c, holdPressure, holdLeftSec);
    }

    /** Posts or updates the hold notification. `left` is the seconds until it vents itself.
     *  In hold mode this updates the foreground notification in place (same id). */
    public static void showHoldNotification(Context c, String pressure, long leftSec) {
        if (c == null) return;
        holdPressure = pressure == null ? "" : pressure;
        holdLeftSec = leftSec;
        try {
            ensureChannels(c);
            NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(NOTIFICATION_HOLD_ID, buildHold(c, holdPressure, leftSec));
            holdPostedHere = true;
            shownNotice = HoldForeground.HELD;
            unconfirmedLeft = false;
        } catch (Exception ignored) { }
    }

    /**
     * H1 (the safety review) - BRING THIS SERVICE UP, IN THE FOREGROUND, FOR A HOLD, or keep
     * it up for one as a run hands over to it. Called by armStdHold (a tap: the screen is in
     * front, or the run's own foreground is up at a routine's end, so Android 12+'s limits on
     * starting a foreground service from the background do not apply) and by syncRunService
     * when a run ends into a hold, and (the second re-review) by every vent watch a hold's
     * stop is retried under - "Keep trying", START's re-check - so every retry runs in the
     * foreground. A refusal is logged and leaves the hold as it was before: its limit in the
     * Activity, its notice a plain one.
     *
     * THE RUNNING SERVICE IS REACHED DIRECTLY ONLY WHEN IT IS NOT STOPPING (the second
     * re-review): a hold armed between the Tick's stopSelf() and onDestroy was handed to a
     * service on its way out and got no foreground. Otherwise the service is started
     * properly, and the system gives the start to a new instance once the old one is gone.
     */
    public static void keepForHold(Context c) {
        if (c == null) return;
        Live l = live;
        int phase = l == null ? HoldForeground.NONE : l.holdPhase();
        if (!HoldForeground.keepsService(false, phase)) return;
        RunService s = instance;
        if (s != null && !s.stopping) {
            s.enterHoldMode(buildFor(c, phase));
            return;
        }
        Intent i = new Intent(c, RunService.class);
        i.setAction(ACTION_HOLD_FG);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            android.util.Log.w("PumpRunService", "could not start the hold's foreground: " + e);
        }
    }

    /** Takes it down. Idempotent, and safe to call when none was ever posted. In hold mode
     *  the notice is this service's foreground notification, which cannot be cancelled while
     *  it holds the foreground - and the hold's vent may still be being confirmed - so it
     *  says so, and the service decides after this turn whether anything is left to keep it
     *  (a vent block sends its stop after it takes the notice down). */
    public static void hideHoldNotification(Context c) {
        if (c == null) return;
        RunService s = instance;
        if (s != null && holdForeground) {
            s.showVenting();
            s.ui.post(s.holdRecheck);
            return;
        }
        try {
            NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_HOLD_ID);
            holdPostedHere = false;
            unconfirmedLeft = false;
            shownNotice = HoldForeground.NONE;
        } catch (Exception ignored) { }
    }

    /** The venting notice, as the foreground notification (same id). */
    private void showVenting() {
        showNotice(HoldForeground.VENTING);
    }

    /** The notice for `phase` on the hold's id (the foreground one in hold mode). */
    private void showNotice(int phase) {
        try {
            NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_HOLD_ID, buildFor(this, phase));
            shownNotice = phase == HoldForeground.UNCONFIRMED_SEEN
                ? HoldForeground.UNCONFIRMED : phase;
        } catch (Exception ignored) { }
    }

    /**
     * (the second re-review) THE SERVICE GOES, THE NOTICE THAT TELLS THE TRUTH STAYS. The
     * person has the give-up in front of them and no retry runs, so nothing is left for the
     * foreground to keep - but the hold is not over, and "couldn't confirm the pump vented -
     * disconnect the tubing" stays up until a confirmed vent, the person's eyes, or a retry.
     * The foreground notice is DETACHED, not removed, so it outlives the service.
     */
    private void leaveUnconfirmedNotice() {
        showNotice(HoldForeground.UNCONFIRMED);
        if (holdForeground) {
            try {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH);
                else stopForeground(false);
            } catch (Exception ignored) { }
            holdForeground = false;
        }
        unconfirmedLeft = true;
        holdPostedHere = true;
    }

    /** (the second re-review) The hold is over - a confirmed vent, the person's eyes: a
     *  "couldn't confirm" notice left behind goes, and a service still up re-asks at once. */
    public static void holdSettled(Context c) {
        if (c == null) return;
        RunService s = instance;
        if (s != null && holdForeground) { s.ui.post(s.holdRecheck); return; }
        if (!unconfirmedLeft) return;
        try {
            NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_HOLD_ID);
        } catch (Exception ignored) { }
        unconfirmedLeft = false;
        holdPostedHere = false;
        shownNotice = HoldForeground.NONE;
    }

    /** (the second re-review) The watch gave up and no service holds the hold's notice (its
     *  foreground was refused, or the person has already seen the give-up): the notice that
     *  tells the truth is put up, plainly. */
    public static void holdUnconfirmed(Context c) {
        if (c == null || holdForeground || shownNotice == HoldForeground.UNCONFIRMED) return;
        try {
            ensureChannels(c);
            NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_HOLD_ID, buildUnconfirmed(c));
            shownNotice = HoldForeground.UNCONFIRMED;
            unconfirmedLeft = true;
            holdPostedHere = true;
        } catch (Exception ignored) { }
    }

    /**
     * (the second re-review) The Activity's last stop is being flushed (PumpLink.stopThenClose,
     * up to LastStop.MAX_MS): in hold mode the foreground is kept for that window, so the
     * process is not left as an empty one while the stop leaves the phone, and then the
     * service goes. With no hold foreground it goes at once, as before.
     */
    public static void stopAfterLastStop(Context c) {
        RunService s = instance;
        if (s != null && holdForeground && !s.stopping) {
            s.lingerUntil = android.os.SystemClock.elapsedRealtime() + LastStop.MAX_MS;
            s.ui.removeCallbacks(s.lingerStop);
            s.ui.postDelayed(s.lingerStop, LastStop.MAX_MS);
            return;
        }
        ensureStopped(c);
    }

    /** The service is on its way out (stopSelf called): keepForHold must start a new one. */
    private boolean stopping;
    /** Until when (elapsedRealtime) the service stays for a destroyed screen's last stop. */
    private long lingerUntil;
    private final Runnable lingerStop = new Runnable() {
        @Override public void run() { stopping = true; stopSelf(); }
    };

    /**
     * H1 - HOLD MODE: the hold's notice becomes this service's foreground notification, with
     * the connectedDevice type the run's own uses (the permission is already declared and the
     * service already typed in the manifest). While a run is live its handle protects the
     * process, and this does nothing; when a run hands over, the run's handle goes - the
     * widget is told the run is idle and `foreground`/`alive` stop claiming one.
     */
    private void enterHoldMode(Notification n) {
        Live l = live;
        if (l != null && l.stillLive()) return;
        boolean wasRun = foreground || alive;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_HOLD_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_HOLD_ID, n);
            }
            holdForeground = true;
            holdPostedHere = true;
            unconfirmedLeft = false;
            Live lp = live;
            int ph = lp == null ? HoldForeground.NONE : lp.holdPhase();
            shownNotice = ph == HoldForeground.UNCONFIRMED_SEEN ? HoldForeground.UNCONFIRMED : ph;
        } catch (Exception e) {
            holdForeground = false;
            holdRefused = true;
            android.util.Log.w("PumpRunService", "the hold's foreground was refused: " + e);
        }
        if (wasRun) {
            foreground = false;
            alive = false;
            pushWidgetIdle();
        }
        if (!ticking) { ticking = true; ui.postDelayed(tick, TICK_MS); }
    }

    /** A hold foreground that was refused is not retried every second from the background. */
    private static boolean holdRefused;

    /** Re-asks, one turn after a hold notice was taken down, whether the service still has a
     *  reason to live (HoldForeground#keepsService) - so the venting notice is not left up
     *  over a hold that is over. */
    private final Runnable holdRecheck = new Runnable() {
        @Override public void run() {
            Live l = live;
            if (l == null && android.os.SystemClock.elapsedRealtime() < lingerUntil) return;
            boolean run = l != null && l.stillLive();
            int hold = l == null ? HoldForeground.NONE : l.holdPhase();
            if (HoldForeground.keepsService(run, hold)) return;
            if (HoldForeground.noticeStays(hold)) leaveUnconfirmedNotice();
            stopping = true;
            stopSelf();
        }
    };

    /** H1 - whether a hold in THIS process posted the hold notice now in the tray. Set by
     *  showHoldNotification, cleared by hideHoldNotification and closedHoldNotice. An ongoing
     *  hold notice in the tray while this is false was left by a process that ended without
     *  taking it down (replaceStaleHold). */
    private static boolean holdPostedHere;
    private static final int REQ_OPEN_CLOSED = 4716;

    /**
     * H1 - THE NOTICE THAT REPLACES A HOLD NOTICE WHOSE RELEASE CAN REACH NOTHING.
     *
     * It takes the hold notice's place (the hold notice is cancelled; the closed notice has an
     * id of its own, so the system's removal of a foreground notice as a process ends cannot
     * take it too). No action, no countdown, not ongoing: it says what is true and what to do
     * (HoldNotice), and it can be dismissed. Tapping it opens the app. `toldStop` when a stop
     * was queued on the way out (the crash guard), which is then all it claims - that the app
     * tried; nothing was left to see whether it left the phone, let alone landed.
     *
     * (the incognito safety review, C1) A SAFETY ALERT, on the alert channel: it is the last
     * word about a cuff that may still be under vacuum, and nothing is left to say another.
     */
    public static void closedHoldNotice(Context c, boolean toldStop) {
        if (c == null) return;
        try {
            ensureChannels(c);
            NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String text = toldStop ? HoldNotice.CLOSED_TOLD_STOP : HoldNotice.CLOSED_UNKNOWN;
            PendingIntent tap = openApp(c, REQ_OPEN_CLOSED);
            Notification.Builder b = builder(c, CHANNEL_ALERT_ID, Notification.PRIORITY_HIGH);
            b.setContentTitle(HoldNotice.CLOSED_TITLE)
             .setContentText(text)
             .setStyle(new Notification.BigTextStyle().bigText(text))
             .setSmallIcon(android.R.drawable.stat_sys_warning)
             .setContentIntent(tap)
             .setCategory(Notification.CATEGORY_ALARM)
             .setDefaults(Notification.DEFAULT_ALL)
             .setAutoCancel(true)
             .setOngoing(false);
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
            // Incognito: a safety notice - plain once unlocked, neutral on the lock screen.
            safetyOnLockScreen(c, b, CHANNEL_ALERT_ID, Notification.PRIORITY_HIGH, tap);
            nm.cancel(NOTIFICATION_HOLD_ID);
            nm.notify(NOTIFICATION_CLOSED_ID, b.build());
            holdPostedHere = false;
            // The hold's notice is gone: a later "Vent not confirmed" is a new word, and rings.
            shownNotice = HoldForeground.NONE;
        } catch (Exception ignored) { }
    }

    /**
     * H1 - called as the app's screen is created. An ONGOING hold notice in the tray that no
     * hold in this process posted was left by a process that ended without taking it down:
     * its countdown has stopped and its RELEASE reaches nothing. It is replaced by the notice
     * that says so. (The replacement is not ongoing, so it is never replaced twice.)
     */
    public static void replaceStaleHold(Context c) {
        if (c == null || Build.VERSION.SDK_INT < 23) return;
        try {
            NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            boolean inTray = false;
            android.service.notification.StatusBarNotification[] active =
                nm.getActiveNotifications();
            for (int i = 0; active != null && i < active.length; i++)
                if (active[i].getId() == NOTIFICATION_HOLD_ID && active[i].isOngoing())
                    inTray = true;
            if (HoldNotice.stale(inTray, holdPostedHere)) closedHoldNotice(c, false);
        } catch (Exception ignored) { }
    }

    public static final String ACTION_STOP = "org.openpump.RUN_STOP";
    public static final String ACTION_HOLD = "org.openpump.RUN_HOLD";

    /** The interrupted-run wording, kept as a constant so the notification and anything
     *  describing it cannot drift apart. It states what the app DID (a stop was sent), not
     *  what the pump did — nothing here can confirm the latter. */
    public static final String INTERRUPTED_TITLE = "The run was interrupted";
    public static final String INTERRUPTED_TEXT =
        "The pump was told to stop. If pressure does not clear, disconnect the tubing at the cuff.";

    private static final int NOTIFICATION_ID = 4712;
    /** Not 4713: Reminders posts its measurement reminder under that id, and a reminder
     *  firing would have replaced "the run was interrupted" in the tray. */
    private static final int NOTIFICATION_INTERRUPTED_ID = 4721;
    private static final int REQ_OPEN = 4712;
    private static final int REQ_STOP = 4713;
    private static final int REQ_HOLD = 4714;
    private static final long TICK_MS = 1000L;

    /** The Activity's back-reference. Static because a Service is constructed by the
     *  framework and cannot be handed anything at construction; same process throughout,
     *  and cleared by the Activity in onDestroy so a dead Activity is never called into. */
    private static Live live;
    /** True only once startForeground() has actually SUCCEEDED. This is the fact
     *  onStop() consults, and it must never be optimistic: a service that failed to go
     *  foreground is not a handle, and the old abort rule has to apply. */
    private static boolean foreground;
    /** True from the top of onStartCommand's normal (non-STOP) path through onDestroy(),
     *  in THIS process — set just BEFORE goForeground() so that call's own immediate
     *  widget push (see its comment) already reads true, and static because
     *  {@link PumpWidgetProvider} has no service instance to ask. Deliberately NOT the
     *  same thing as {@link #foreground}/{@link #isRunning()}: that tracks only whether
     *  startForeground() itself succeeded, and a vendor ROM refusing the foreground type
     *  must not ALSO make a placed widget lie about a run that is still genuinely live.
     *  Defaults false in a freshly-restarted process exactly like foreground does, which
     *  is what lets {@link PumpWidgetProvider#buildViews} self-heal a persisted "RUNNING"
     *  snapshot a killed process never got the chance to correct — see that class's own
     *  comment. */
    private static boolean alive;

    public static void setLive(Live l) { live = l; rememberPrivacy(l); }
    public static void clearLive(Live l) {
        if (live != l) return;
        rememberPrivacy(l);   // (M2) the words a notice posted after the screen goes will use
        live = null;
    }

    /** Is there a visible handle on the run right now? The single question onStop() asks
     *  before deciding whether leaving the app is still an abandonment. */
    public static boolean isRunning() { return foreground; }

    /** Is RunService, in this process, actually alive and pulling {@link Live} right
     *  now — regardless of whether the ongoing notification itself managed to show? See
     *  {@link #alive}'s own comment for why this is not {@link #isRunning()}. */
    public static boolean isAlive() { return alive; }

    /* ============================== Stage E — task 3 (T18) ====================
     *
     * BATTERY-KILLER PROTECTION (dist/round7-options.html, T18 A): "service-killed
     * detection re-offers [the exemption flow]". Everything above this point (foreground,
     * alive) is IN-MEMORY and therefore useless for detecting a kill by definition — a
     * process the OS killed outright starts its next life with both back at their default
     * false, indistinguishable from an install that has simply never run anything. What
     * this section adds is the one bit of state that DOES survive: a SharedPreferences
     * flag SessionActivity's own syncRunService() sets the moment it believes a run is
     * live, and clears the moment that SAME method sees the run end through any real stop
     * path (finishSession, StopWork confirmed, abortValidation/abortSelfTest, the
     * notification's own STOP). If the flag is still true the NEXT time this is read —
     * in a process that may not even be the one that set it — no clean stop ran, which
     * combined with {@link #isAlive} being false in THIS process is the signature
     * {@link Session#killedMidRun} tests for. See that method's own doc for the full
     * reasoning, including why the comparison is against isAlive() and not isRunning().
     *
     * FINAL REVIEW FIX: syncRunService()'s else-branch is not the only way this service's
     * teardown reaches {@link #alive} going false — {@link #onDestroy} also runs from the
     * Tick's own stopSelf() safety net (Live gone or stillLive() false) and from
     * onTaskRemoved's stopSelf() (the task-swipe narration), neither of which goes back
     * through SessionActivity at all. Both are genuinely clean, app-initiated-or-still-
     * executing teardowns — never the silent process kill this flag exists to catch — so
     * {@link #onDestroy} ALSO calls {@link #markRunCleanlyStopped} unconditionally, at its
     * top, alongside syncRunService()'s own call. Without that, all three paths left the
     * flag true after a run that had, in fact, ended cleanly: the next resume misreported
     * a kill that never happened, and on the onTaskRemoved path specifically, contradicted
     * the "pump was told to stop" notification that same teardown had just posted. A
     * genuine kill never runs onDestroy at all, so this cannot mask the real thing —
     * {@link Session#killedMidRun}'s dialog now fires ONLY for "no callback ran at all",
     * exactly what {@link SessionActivity#showKilledMidRunDialog} already claimed.
     *
     * A SEPARATE prefs file from PumpWidgetProvider's "pump_widget", on purpose: this flag
     * answers a different question (did the run end cleanly, for SessionActivity's own
     * kill-detection) from what that one answers (what should the widget currently show),
     * and the two must not be able to drift into a single "state" that means two things at
     * once.
     */
    private static final String KILL_PREFS = "pump_run_state";
    private static final String KEY_BELIEVED_ACTIVE = "believed_active";
    /** Set by {@link #onTaskRemoved} when the task went away WITH A RUN LIVE, read and
     *  cleared by the Activity on its next resume.
     *
     *  WHY IT IS A SEPARATE FLAG FROM {@link #KEY_BELIEVED_ACTIVE}. A task removal is not
     *  the silent process kill {@link Session#killedMidRun} detects - onTaskRemoved RAN,
     *  the Activity's onDestroy sent its last-resort stop, and the interruption
     *  notification went out - so it is a CLEAN teardown by that method's definition and
     *  correctly clears the believed-active flag. But it is not necessarily something the
     *  user DID: several vendor ROMs remove a background task on their own after a few
     *  minutes, which is indistinguishable here from a deliberate swipe and produces the
     *  same lost run. Recording it lets the app say what happened next time it is opened
     *  instead of leaving a notification as the only account of it. */
    private static final String KEY_TASK_REMOVED_LIVE = "task_removed_live";

    /** Called from {@link SessionActivity#syncRunService()}'s "a live-work state just
     *  started" branch, alongside {@link #ensureStarted}. Idempotent — called again on
     *  every subsequent state change within the same run, same as ensureStarted is. */
    public static void markRunBelievedActive(Context c) {
        if (c == null) return;
        try {
            c.getSharedPreferences(KILL_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BELIEVED_ACTIVE, true).apply();
        } catch (Exception ignored) { }
    }

    /** Called from {@link SessionActivity#syncRunService()}'s "no live-work state is left"
     *  branch, alongside {@link #ensureStopped} — the "a clean stop was recorded" moment
     *  {@link Session#killedMidRun}'s doc refers to. Also called once a detected kill has
     *  actually been surfaced to the user, so the same interruption is never reported
     *  twice. ALSO called, idempotently, from the top of {@link #onDestroy} itself — see
     *  that method's own comment — so every path that tears this service down clears the
     *  flag, not only the one SessionActivity happens to route through. */
    public static void markRunCleanlyStopped(Context c) {
        if (c == null) return;
        try {
            c.getSharedPreferences(KILL_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_BELIEVED_ACTIVE, false).apply();
        } catch (Exception ignored) { }
    }

    /** The persisted half of {@link Session#killedMidRun}'s `believedActive` input — see
     *  that method's doc for what "true" here does and does not mean. Defaults false: an
     *  install that has never started a run, or one read before this flag existed, must
     *  never report a kill it has no evidence for. */
    /** Records that the task was removed while something was still driving the pump. */
    public static void markTaskRemovedLive(Context c) {
        if (c == null) return;
        try {
            c.getSharedPreferences(KILL_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TASK_REMOVED_LIVE, true).apply();
        } catch (Exception ignored) { }
    }

    /** True when a run ended because the task went away. Cleared by
     *  {@link #clearTaskRemovedLive} once it has been surfaced, so one interruption is
     *  never reported twice. */
    public static boolean taskRemovedLive(Context c) {
        if (c == null) return false;
        try {
            return c.getSharedPreferences(KILL_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TASK_REMOVED_LIVE, false);
        } catch (Exception e) { return false; }
    }

    public static void clearTaskRemovedLive(Context c) {
        if (c == null) return;
        try {
            c.getSharedPreferences(KILL_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TASK_REMOVED_LIVE, false).apply();
        } catch (Exception ignored) { }
    }

    /* ================== H1 (the re-review's concern 1) - THE HOLD'S OWN TRACE ==========
     *
     * Kill detection above covers runs, and is kept that way. A HOLD killed with its process
     * - with the hold in the foreground, only a force stop or kill -9 can do it - left nothing
     * behind: the system removes the hold's notice with the process. So this flag is written
     * when a hold is armed and cleared on every way a hold ends, and the next launch that
     * finds it with no hold of its own says so (SessionActivity#checkHoldTrace, HoldTrace).
     * It only informs: nothing about STOP, START or the vent reads it (invariant 94). Same
     * prefs file as the run's flag, its own key. */
    private static final String KEY_HOLD_LIVE = "hold_live";
    /** What was last written, so the Tick writes only on a change; null before any write in
     *  this process - a fresh process always writes. */
    private static Boolean holdLiveWritten;

    /** Set when a hold is armed (committed: on disk before anything can end the process);
     *  cleared (applied) when it ends. */
    public static void markHoldLive(Context c, boolean on) {
        if (c == null) return;
        if (holdLiveWritten != null && holdLiveWritten.booleanValue() == on) return;
        try {
            android.content.SharedPreferences.Editor e =
                c.getSharedPreferences(KILL_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_HOLD_LIVE, on);
            if (on) e.commit(); else e.apply();
            holdLiveWritten = Boolean.valueOf(on);
        } catch (Exception ignored) { }
    }

    /** The persisted flag, for the one launch check that reads it. False when unreadable:
     *  a message with no evidence behind it is not one to give. */
    public static boolean holdWasLive(Context c) {
        if (c == null) return false;
        try {
            return c.getSharedPreferences(KILL_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_HOLD_LIVE, false);
        } catch (Exception e) { return false; }
    }

    public static boolean runBelievedActive(Context c) {
        if (c == null) return false;
        try {
            return c.getSharedPreferences(KILL_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BELIEVED_ACTIVE, false);
        } catch (Exception e) { return false; }
    }

    /**
     * Bring the service up if it is not already. Idempotent, and it swallows every
     * failure: a background-start restriction or a vendor ROM refusing the foreground type
     * must not take the run down with an exception — it must leave {@link #isRunning()}
     * false so onStop() falls back to aborting, which is the safe answer.
     */
    public static void ensureStarted(Context c) {
        if (c == null || foreground) return;
        Intent i = new Intent(c, RunService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            android.util.Log.w("PumpRunService", "could not start the run service: " + e);
        }
    }

    /** Take the service down. Safe to call when it is not up. */
    public static void ensureStopped(Context c) {
        if (c == null) return;
        // (the safety review of the in-run Hold's limit) THE INSTANCE IS ON ITS WAY OUT FROM
        // HERE, and says so: a keepForHold arriving before its onDestroy used to find it "not
        // stopping", hand it the hold's foreground and lose it with the service. Now it
        // starts the service properly, as after the Tick's own stopSelf().
        RunService s = instance;
        if (s != null) s.stopping = true;
        try {
            c.stopService(new Intent(c, RunService.class));
        } catch (Exception ignored) { }
    }

    private PowerManager.WakeLock wake;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Tick();
    private boolean ticking;

    /** The one pull of {@link Live}'s five values, refreshed by {@link #refreshLiveSnapshot()}
     *  and read from here by both {@link #build()} (the notification) and the widget push
     *  ({@link #pushWidget()}) — see the class comment's "WHAT IT READS AND HOW OFTEN" and
     *  "THE WIDGET" sections for why this exists instead of each consumer calling {@link Live}
     *  on its own. */
    private String snapName = "";
    private int snapIdx;
    private int snapTotal;
    private String snapLeft = "";
    private String snapPress = "";
    /** NOT one of the five above and not pushed to the widget — the widget has no
     *  routine-name/pressure text to neutralise in the first place. Pulled in the same
     *  breath purely so {@link #build()} never has to call back into {@link Live} a
     *  second time for the same instant (T17). See {@link Live#discreetMode()}. */
    private boolean snapDiscreet;
    /** Also not one of the five and also not pushed to the widget — pulled purely so
     *  {@link #build()} can label the HOLD action "HOLD" or "RESUME" to match what
     *  tapping it will actually do, the same reason {@link #snapDiscreet} exists. See
     *  {@link Live#liveHolding()}. */
    private boolean snapHolding;
    private String snapHoldVentsIn = "";
    /** Live#livePhase, pulled in the same breath for the same reason; not for the widget. */
    private String snapPhase = "";

    /** Pull {@link Live}'s five values exactly once into this service's own fields. Called
     *  at the top of every tick and before the very first notification is built
     *  ({@link #goForeground()}), so build() and the widget push always agree with each
     *  other and neither ever calls into Live a second time for the same instant. */
    private void refreshLiveSnapshot() {
        Live l = live;
        snapName  = l == null ? "" : l.liveName();
        snapIdx   = l == null ? 0  : l.livePresetIndex();
        snapTotal = l == null ? 0  : l.livePresetTotal();
        snapLeft  = l == null ? "" : l.liveCountdown();
        snapPress = l == null ? "" : l.livePressure();
        snapDiscreet = l != null && l.discreetMode();
        snapHolding = l != null && l.liveHolding();
        snapHoldVentsIn = l == null ? "" : l.liveHoldVentsIn();
        snapPhase = l == null ? "" : l.livePhase();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        // PARTIAL only: the CPU must keep running so the Activity's Handler ticks keep
        // sequencing the routine and the vent watch keeps retrying, but the screen is the
        // user's business — a run continues perfectly well with the phone in a pocket.
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pumpdebug:run");
                wake.setReferenceCounted(false);
                wake.acquire();
            }
        } catch (Exception e) {
            // A run without a wakelock is a run that may be throttled, not a run that must
            // be refused: the notification handle and the Activity's own machinery are
            // both still there. Logged, not fatal.
            android.util.Log.w("PumpRunService", "no wakelock: " + e);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        /* A BUTTON ON A NOTIFICATION MUST NOT LEAVE A SERVICE RUNNING BEHIND IT.
         *
         * These three actions are delivered by PendingIntent.getService, which STARTS this
         * service if it is not already up - and onCreate takes a PARTIAL_WAKE_LOCK. The
         * standardisation hold posts a notification with RELEASE on it and deliberately
         * does NOT run the service, so tapping it started one that nothing would ever stop:
         * a wake lock held until the process died, on a phone with no run in progress.
         *
         * Remembered before the branches, because each of them returns. */
        boolean wasAlive = alive;
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // The STOP button. Note what this does NOT do: it does not write to the pump.
            // It hands the decision to the Activity's one filing-and-venting chokepoint,
            // so a stop from the notification is indistinguishable from a stop on screen.
            Live l = live;
            if (l != null) l.stopFromNotification();
            if (!wasAlive && !holdForeground) stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RELEASE.equals(intent.getAction())) {
            // P2 - the RELEASE button. Same shape as STOP: nothing is written to the pump
            // from here, the Activity's own vent path does it.
            //
            // H1 - AND WHEN THERE IS NO SCREEN TO DO IT, IT SAYS SO. With no live screen in
            // this process - the process ended and this tap started a new one - the tap did
            // nothing, under a notice still promising "Vents on its own". It cannot reach the
            // pump from here (this service owns no link, by design), so the notice is
            // replaced by one that says that and what to do.
            //
            // H1 (the safety review) - A HOLD'S FOREGROUND STAYS UP FOR ITS VENT: the tap
            // starts the vent this service must live to see confirmed; the Tick lets it go
            // once HoldForeground says nothing is left.
            Live l = live;
            if (HoldNotice.onRelease(l != null) == HoldNotice.RELEASE_IN_APP) {
                l.releaseHoldFromNotification();
            } else {
                if (holdForeground) {
                    try {
                        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE);
                        else stopForeground(true);
                    } catch (Exception ignored) { }
                    holdForeground = false;
                }
                closedHoldNotice(this, false);
            }
            if (!wasAlive && !holdForeground) stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_HOLD.equals(intent.getAction())) {
            // The HOLD button (T7). Same shape as STOP above: nothing is written to the
            // pump from here. It hands the decision to the Activity's own toggle — the
            // exact branch HoldTap runs on screen — so a hold or a resume from the
            // notification is indistinguishable from one on screen.
            Live l = live;
            if (l != null) l.holdFromNotification();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_HOLD_FG.equals(intent.getAction())) {
            // H1 (the safety review) - started for a hold (keepForHold, with no service up).
            // A run that is live has its own handle - the ordinary start below - and either
            // way startForeground is called, as a foreground-service start requires.
            //
            // (the second re-review) A START WITH NO SCREEN BEHIND IT STOPS. A redelivered
            // hold start, or one that arrives after the hold ended, meets the start's own
            // contract and goes - and touches nothing the next launch reads: the hold's trace
            // is the Activity's to clear, and with no screen there is nobody to clear it.
            Live l = live;
            if (!(l != null && l.stillLive())) {
                int phase = l == null ? HoldForeground.NONE : l.holdPhase();
                if (HoldForeground.holdStartEnters(l != null, phase)) {
                    enterHoldMode(buildFor(this, phase));
                } else {
                    quietForegroundThenGo();
                    stopping = true;
                    stopSelf();
                }
                return START_NOT_STICKY;
            }
        }
        // Set BEFORE goForeground(), not after: goForeground() pushes the widget's first
        // frame immediately (see its own comment), and that push's "is this genuinely a
        // live run" cross-check (PumpWidgetProvider#buildViews) reads isAlive() — a
        // widget instance placed while a run is already active must not be told "idle"
        // for the one tick this would otherwise cost it.
        alive = true;
        goForeground();
        if (!ticking) { ticking = true; ui.postDelayed(tick, TICK_MS); }
        // NOT sticky: if the process died, the Activity — and with it the link, the plan
        // and the session — died too. A service restarted alone would show a countdown for
        // a run that no longer exists, which is the exact false claim this whole class is
        // meant to avoid making.
        return START_NOT_STICKY;
    }

    /** The foreground-service start's contract, met and let go at once - for a start that has
     *  nothing to keep (a stop without it would crash the app on its way out). */
    private void quietForegroundThenGo() {
        if (foreground || holdForeground) return;
        try {
            ensureChannels(this);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_HOLD_ID);
            else b = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
            // (incognito, 0.10) it is up for a moment only, but a moment in the shade is
            // enough to be read: with discreet notifications on it says what the run's
            // discreet notice says, never the app's real name.
            boolean discreet = discreetNow(this);
            b.setContentTitle(discreet ? Incognito.RUN_TITLE : "OpenPump")
             .setSmallIcon(discreet ? R.drawable.ic_notification
                                    : android.R.drawable.stat_sys_warning);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_QUIET_ID, b.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_QUIET_ID, b.build());
            }
            if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) { }
    }
    private static final int NOTIFICATION_QUIET_ID = 4718;

    /** startForeground(), with the API-34 type argument, recording whether it worked. */
    private void goForeground() {
        try {
            ensureChannels(this);
            refreshLiveSnapshot();
            Notification n = build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            foreground = true;
            // H1 - a hold's foreground notice, if there was one, gave way to the run's (a
            // new foreground id cancels the old one); the hold's notice is a plain one again.
            holdForeground = false;
        } catch (Exception e) {
            // Left false on purpose — see the field's own comment. onStop() will treat
            // leaving as an abandonment, which is what it did before this class existed.
            foreground = false;
            android.util.Log.w("PumpRunService", "startForeground refused: " + e);
        }
        // Pushed here too, not only from Tick: a widget instance placed while a run is
        // already active (or one the launcher re-binds after this service restarts)
        // should see the live tile on the very first frame, not up to TICK_MS late.
        pushWidget();
    }

    private final class Tick implements Runnable {
        @Override public void run() {
            Live l = live;
            // (the second re-review) A destroyed screen's last stop is being flushed: the
            // foreground stays for its window (stopAfterLastStop), then lingerStop lets go.
            if (l == null && android.os.SystemClock.elapsedRealtime() < lingerUntil) {
                ui.postDelayed(this, TICK_MS);
                return;
            }
            boolean run = l != null && l.stillLive();
            int hold = l == null ? HoldForeground.NONE : l.holdPhase();
            // H1 - the hold's trace follows its phase: every way a hold ends reaches NONE. Only
            // with a live screen to ask - with none, the trace is the next launch's to read.
            if (l != null) markHoldLive(RunService.this, HoldTrace.live(hold));
            if (!HoldForeground.keepsService(run, hold)) {
                // The Activity says nothing is driving the pump any more - no run, no hold
                // being held, retried or waiting to be told. Stand down rather than wait to be
                // told twice: an ongoing notification outliving the thing it describes is a lie
                // the user has no way to correct.
                //
                // (the second re-review) BUT NOT THE NOTICE OF A HOLD THAT GOES ON: a give-up
                // the person has in front of them keeps its "couldn't confirm" notice, detached.
                if (HoldForeground.noticeStays(hold)) leaveUnconfirmedNotice();
                stopping = true;
                stopSelf();
                return;
            }
            if (!run) {
                /* H1 (the safety review) - A HOLD, AND NO RUN: the hold's foreground. The
                 * hand-over from a run that ended into a hold lands here if syncRunService
                 * has not already made it; the venting notice replaces the held one once the
                 * hold's stop is sent. The Activity's own tick keeps the held countdown
                 * current (same id). Nothing here is the widget's or kill detection's. */
                if (!holdForeground && !holdRefused)
                    enterHoldMode(buildFor(RunService.this, hold));
                else if (holdForeground && hold != HoldForeground.HELD && hold != shownNotice)
                    showNotice(hold);    // venting, or the watch gave up: say which
                ui.postDelayed(this, TICK_MS);
                return;
            }
            refreshLiveSnapshot();
            if (foreground) {
                try {
                    NotificationManager nm = (NotificationManager)
                        getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.notify(NOTIFICATION_ID, build());
                } catch (SecurityException ignored) {
                    // POST_NOTIFICATIONS revoked mid-run. The service keeps running — the
                    // run is not made safer by ending it abruptly — and the app is still
                    // resumable from the launcher.
                } catch (Exception ignored) { }
            }
            pushWidget();
            ui.postDelayed(this, TICK_MS);
        }
    }

    /**
     * Pushes the snapshot {@link #refreshLiveSnapshot()} just refreshed out to every
     * placed widget instance, straight through AppWidgetManager — see the class
     * comment's "THE WIDGET" section. Called from both {@link #goForeground()} (so a
     * widget instance placed while a run is already active sees it on the first frame,
     * not up to TICK_MS late) and {@link Tick#run()} (every second after). Cheap when
     * nothing is placed: one getAppWidgetIds() call and nothing else, so a run with no
     * widget on the launcher costs this nothing extra on top of what the notification
     * tick already did.
     */
    private void pushWidget() {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(this, PumpWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            // A step done by hand is named on the widget too (ByHand), never while discreet.
            RemoteViews views = PumpWidgetProvider.prepareLive(
                this, ByHand.widgetName(snapName, snapPhase, snapDiscreet), snapIdx, snapTotal,
                snapLeft, snapPress);
            for (int id : ids) mgr.updateAppWidget(id, views);
        } catch (Exception ignored) {
            // A widget push failing must never take the run itself, or even the
            // notification, down with it — the launcher surface is strictly optional.
        }
    }

    /** The counterpart to {@link #pushWidget()}, called once the run actually ends
     *  ({@link #onDestroy()}) so a placed widget does not keep counting down for a
     *  session that is over. */
    private void pushWidgetIdle() {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(this, PumpWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews views = PumpWidgetProvider.prepareIdle(this);
            for (int id : ids) mgr.updateAppWidget(id, views);
        } catch (Exception ignored) { }
    }

    /**
     * The ongoing notification. Ongoing + LOW importance + no sound: a phone timer, not an
     * alert. Tapping the body opens the app; the two actions are STOP and HOLD (T7).
     */
    private Notification build() {
        // Reads refreshLiveSnapshot()'s cached fields, not Live directly — see the class
        // comment's "WHAT IT READS AND HOW OFTEN". Every caller of build() refreshes the
        // snapshot immediately before calling it (goForeground(), Tick.run()).
        String name  = snapName;
        int idx      = snapIdx;
        int total    = snapTotal;
        String left  = snapLeft;
        String press = snapPress;
        boolean discreet = snapDiscreet;
        boolean holding = snapHolding;

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        // (re-review 3) back to the running screen as it is - never a new one (appIntent).
        PendingIntent tap = openApp(this, REQ_OPEN);

        Intent stop = new Intent(this, RunService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, REQ_STOP, stop, flags);

        // HOLD (T7) — built the identical way STOP is, immediately above: same Intent
        // targeting this service, same PendingIntent.getService pattern, same flags. Only
        // the action string and the request code differ, so the two PendingIntents are
        // never confused with each other.
        Intent hold = new Intent(this, RunService.class);
        hold.setAction(ACTION_HOLD);
        PendingIntent holdPi = PendingIntent.getService(this, REQ_HOLD, hold, flags);

        Notification.Builder b = builder(this, CHANNEL_ID, Notification.PRIORITY_LOW);
        b.setContentTitle(Session.runNotificationTitle(discreet, name))
         .setContentText(Session.runNotificationPhase(discreet, snapPhase,
             Session.runNotificationText(discreet, name, idx, total, left, press,
                                         snapHoldVentsIn)))
         .setSmallIcon(R.drawable.ic_notification)
         .setContentIntent(tap)
         .setOngoing(true)
         .setOnlyAlertOnce(true)
         .setShowWhen(false);
        // STOP, in EVERY mode (incognito, 0.10: "STOP · vent now", the owner's words): never
        // behind a discreet branch (WiringCheck invariant 190).
        b.addAction(new Notification.Action.Builder(
            null, Incognito.STOP_LABEL, stopPi).build());
        // The label matches what tapping it will actually do, the same toggle the
        // on-screen button's own text follows (holdBtn: holding ? "Resume" : "Pause",
        // 0.10 final) — never a fixed "PAUSE" that would claim the opposite of the real
        // effect while a hold is already in force.
        Notification.Action holdAction = new Notification.Action.Builder(
            null, holding ? "RESUME" : "PAUSE", holdPi).build();
        b.addAction(holdAction);
        // Discreet: the lock screen reads "Contents hidden" - and keeps STOP and HOLD, so a
        // locked phone can still stop the pump in one tap (invariant 191).
        if (discreet) {
            discreetOnLockScreen(this, b, CHANNEL_ID, R.drawable.ic_notification,
                new Notification.Action.Builder(null, Incognito.STOP_LABEL_LOCKED, stopPi).build(),
                holdAction);
        } else {
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        return b.build();
    }

    /**
     * THE TASK SWIPE. Read this class's own header before changing anything here: the
     * service does NOT try to reach the pump from this callback. The Activity's onDestroy()
     * is what sends the last-resort stop, and it runs as the task goes away. All this can
     * honestly do is say so, once, at a priority the user will actually see — and then get
     * out of the way rather than keep an ongoing "preset 3 of 6" notification pinned to a
     * run whose process is being torn down.
     */
    @Override public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        /* (the incognito safety review, I1) ONLY THE ONE SCREEN'S TASK. Android calls this for
         * ANY of the app's tasks that goes - and switching a launcher entry off removes the
         * task that entry rooted, which is the launcher trampoline's own, empty one (the
         * shortcuts' trampoline's too, when it is trimmed). Nothing ran in it: "the run was
         * interrupted" would be false, and stopping here would take away the foreground a run
         * or a vent is kept by. The screen's task is rooted by SessionActivity, by name. */
        android.content.ComponentName root = rootIntent == null ? null : rootIntent.getComponent();
        if (!Incognito.isScreenTask(root == null ? null : root.getClassName())) {
            android.util.Log.i("PumpRunService", "a task that held no screen was removed ("
                + root.getClassName() + ") - nothing to say");
            return;
        }
        // Recorded BEFORE the notification, because the notification is best-effort and
        // this is the part the app can still act on when it is next opened. Only when
        // something was actually live: a task swiped away with nothing running is an
        // ordinary way to close an app and has nothing to report.
        Live l = live;
        boolean wasLive = alive || (l != null && l.stillLive());
        /* H1 (the second re-review) - A HOLD, NOT A RUN. "The run was interrupted / The pump
         * was told to stop" is a run's claim. For a hold, what is true is that the Activity's
         * onDestroy is sending its last stop (PumpLink.stopThenClose), a write the pump never
         * acknowledges: the app TRIED. The service is not stopped here - onDestroy keeps its
         * foreground for the stop's window (stopAfterLastStop) and then it goes. */
        if (holdForeground && !wasLive) {
            closedHoldNotice(this, true);
            return;
        }
        if (wasLive) markTaskRemovedLive(this);
        try {
            ensureChannels(this);
            NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                Notification.Builder b =
                    builder(this, CHANNEL_INTERRUPTED_ID, Notification.PRIORITY_HIGH);
                b.setContentTitle(INTERRUPTED_TITLE)
                 .setContentText(INTERRUPTED_TEXT)
                 .setStyle(new Notification.BigTextStyle().bigText(INTERRUPTED_TEXT))
                 // DELIBERATE: the stock warning triangle, not this app's own icon. A
                 // high-importance interruption/safety notice needs to read as visually
                 // distinct from the calm branded ongoing-run icon at a glance — do not
                 // "fix" this in a future icon-consistency sweep.
                 .setSmallIcon(android.R.drawable.stat_sys_warning)
                 .setAutoCancel(true);
                // Incognito: a safety notice - plain once unlocked, neutral on the lock screen.
                safetyOnLockScreen(this, b, CHANNEL_INTERRUPTED_ID, Notification.PRIORITY_HIGH,
                                   null);
                nm.notify(NOTIFICATION_INTERRUPTED_ID, b.build());
            }
        } catch (Exception ignored) {
            // Nothing here may prevent the stopSelf below.
        }
        stopping = true;
        stopSelf();
    }

    @Override public void onDestroy() {
        // FINAL REVIEW FIX (Finding 1): every teardown of this service — not only the one
        // syncRunService()'s else-branch drives — ends up here, including the Tick safety
        // net's own stopSelf() and onTaskRemoved's stopSelf(). None of those is the silent
        // process kill {@link Session#killedMidRun} exists to catch (a genuine kill never
        // runs onDestroy at all), so clearing the flag here cannot mask a real one — it
        // only stops these ordinary, non-kill teardowns from being misreported as one on
        // the next resume. Idempotent, same as syncRunService()'s own call. See this
        // class's header comment and markRunCleanlyStopped()'s own doc for the full case.
        markRunCleanlyStopped(this);
        // H1 - and the hold's trace, when a live screen says the hold is over: a clean teardown
        // is never the kill it exists to report. Not unconditionally (the second re-review):
        // the service can go while the hold goes on - a give-up the person has seen, a start
        // with no screen - and then the next launch must still be able to say so.
        Live lt = live;
        if (lt != null && !HoldTrace.live(lt.holdPhase())) markHoldLive(this, false);
        foreground = false;
        ticking = false;
        alive = false;
        // H1 - the hold's foreground notice goes with the service (stopForeground below); a
        // detached "couldn't confirm" one stays (unconfirmedLeft).
        if (holdForeground) { holdPostedHere = false; shownNotice = HoldForeground.NONE; }
        holdForeground = false;
        holdRefused = false;
        ui.removeCallbacks(holdRecheck);
        ui.removeCallbacks(lingerStop);
        if (instance == this) instance = null;
        ui.removeCallbacks(tick);
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) { }
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) { }
        wake = null;
        // The run this service was a handle for is over, one way or another (finished,
        // stopped from the notification, or the task-swipe path above already ran and
        // called stopSelf()). Whatever a placed widget was last shown, it must not keep
        // showing it now.
        pushWidgetIdle();
        super.onDestroy();
    }

    /**
     * The channels, created idempotently. The live one is IMPORTANCE_LOW — an ongoing run
     * is a status, and a status that beeps once a second would be intolerable and would
     * teach the user to mute the app. The interrupted one is HIGH because it is the only
     * thing that will ever be said about a run the user destroyed by swiping it away. The
     * safety alerts' is HIGH with sound and vibration, for the same reason (C1, and
     * CHANNEL_ALERT_ID's note): a vent never confirmed must reach the person.
     */
    public static void ensureChannels(Context c) {
        if (c == null || Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm =
            (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel live = new NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        live.setDescription("Shows a run, self-test or check that is still going, with a STOP button.");
        live.setShowBadge(false);
        live.setSound(null, null);
        live.enableVibration(false);
        nm.createNotificationChannel(live);
        NotificationChannel bad = new NotificationChannel(
            CHANNEL_INTERRUPTED_ID, CHANNEL_INTERRUPTED_NAME, NotificationManager.IMPORTANCE_HIGH);
        bad.setDescription("Says so when a run ended because the app was closed.");
        bad.setShowBadge(true);
        nm.createNotificationChannel(bad);

        // P2 - LOW, like the run's own: a held cuff is information the person already knows
        // about, not an alarm. It must never make a sound.
        NotificationChannel hold = new NotificationChannel(
            CHANNEL_HOLD_ID, CHANNEL_HOLD_NAME, NotificationManager.IMPORTANCE_LOW);
        hold.setDescription("Shows that the pump is holding pressure, with a RELEASE button.");
        hold.setShowBadge(false);
        hold.setSound(null, null);
        hold.enableVibration(false);
        nm.createNotificationChannel(hold);

        // (the incognito safety review, C1) HIGH, the default notification sound and a
        // vibration: "Vent not confirmed", "The app closed during a hold", the ring timer.
        NotificationChannel alert = new NotificationChannel(
            CHANNEL_ALERT_ID, CHANNEL_ALERT_NAME, NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("Rings and vibrates when the app can't confirm the pump vented, "
            + "when it closed during a hold, and when the ring timer is up.");
        alert.setShowBadge(true);
        alert.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
            new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{ 0L, 600L, 250L, 600L, 250L, 600L });
        alert.enableLights(true);
        nm.createNotificationChannel(alert);
    }
}
