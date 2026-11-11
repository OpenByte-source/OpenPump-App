package org.openpump;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * WHICH SESSIONS GO TO GROWTHTRACK - one stored setting, shown on the connect page and on the
 * GrowthTrack screen alike (owner decisions 3 and 4). Lives in model.json: none of it is a
 * credential, and the eligibility filter runs on the main thread from the Model.
 *
 * The master switch stops all sending without disconnecting. The four type switches pick
 * what kind of session is sent; "stopped early" is OFF by default (a stopped run usually
 * either resumes - and is then sent whole - or was a misfire).
 *
 * Applied twice, with one rule: a session filed while its switch is off is never marked for
 * the account (GrowthTrackOutbox#markerFor), and a session waiting to be sent whose switch is
 * turned off is held back until it is turned on again (GrowthTrackOutbox#take).
 */
public final class GrowthTrackSettings {
    public static final int KIND_GIRTH_PLAN = 1, KIND_LENGTH_PLAN = 2, KIND_OWN = 3, KIND_MANUAL = 4;

    public boolean on = true;
    public boolean girthPlan = true, lengthPlan = true, ownRoutines = true, manualRuns = true;
    public boolean stoppedEarly = false;

    public GrowthTrackSettings copy() {
        GrowthTrackSettings c = new GrowthTrackSettings();
        c.on = on; c.girthPlan = girthPlan; c.lengthPlan = lengthPlan; c.ownRoutines = ownRoutines;
        c.manualRuns = manualRuns; c.stoppedEarly = stoppedEarly;
        return c;
    }

    /** Which switch a session falls under. The FROZEN track of its recording answers first
     *  (what the routine was when it ran); an old recording falls back to the routine as it
     *  stands, and a routine the plan never marked is the person's own. */
    public static int kindOf(Model.Sess s, AsRun run, Model m) {
        if (s == null) return KIND_OWN;
        if (s.manual) return KIND_MANUAL;
        Model.AsRunSnapshot snap = run != null && run.snap != null ? run.snap : s.asRunSnapshot;
        int track = snap != null ? snap.trainerTrack : -1;
        if (track < 0) {
            Model.Routine r = m == null ? null : m.routine(s.routineId);
            track = r == null ? Model.TRAINER_TRACK_NONE : r.trainerTrack;
        }
        if (track == Plan.TRACK_LENGTH) return KIND_LENGTH_PLAN;
        if (track == Plan.TRACK_GIRTH_INTERVAL || track == Plan.TRACK_GIRTH_TRADITIONAL || track == Plan.TRACK_FEEDER)
            return KIND_GIRTH_PLAN;
        return KIND_OWN;
    }

    /** Whether the GrowthTrack screen shows a plan track's switch (decision 3: hidden for a
     *  track the person does not train) - on when the plan runs it now, or did for a session
     *  still on the phone. */
    public static boolean showsPlanSwitch(Model m, int kind) {
        if (m == null) return false;
        if (kind == KIND_GIRTH_PLAN && m.trainerEnrolled) return true;
        if (kind == KIND_LENGTH_PLAN && m.trainerEnrolled && m.trainerLengthOn) return true;
        for (Model.Sess s : m.sessLog.all) if (s != null && !s.sim && kindOf(s, null, m) == kind) return true;
        return false;
    }

    /** Null when this session may be sent under these settings, else why not (plain words). */
    public String refusal(Model.Sess s, AsRun run, Model m) {
        if (s == null) return "No session";
        if (s.sim) return "Practice runs are never sent";
        switch (held(s, run, m)) {
            case HELD_OFF: return "Sending to GrowthTrack is switched off";
            case HELD_STOPPED: return "Stopped early, and sessions stopped early are switched off";
            case HELD_GIRTH: return "Girth plan sessions are switched off";
            case HELD_LENGTH: return "Length plan sessions are switched off";
            case HELD_MANUAL: return "Manual runs are switched off";
            case HELD_OWN: return "Your own routines are switched off";
            default: return null;
        }
    }

    /**
     * WHY A SESSION WAS FILED UNMARKED (the live GrowthTrack test, 9 Nov 2026): kept on the
     * record (Model.Sess#gtHeldWhy), because a switch turned back on later does not send it, and
     * "Sent and waiting sessions" must say which switch it was. The first is the phone not being
     * connected; the rest are {@link #refusal}'s switches. 0: none, or a record filed before
     * the reason was kept.
     */
    public static final int HELD_NONE = 0, HELD_NOT_CONNECTED = 1, HELD_OFF = 2, HELD_STOPPED = 3,
        HELD_GIRTH = 4, HELD_LENGTH = 5, HELD_OWN = 6, HELD_MANUAL = 7;

    /** Which switch holds this (real) session back under these settings, or HELD_NONE. */
    public int held(Model.Sess s, AsRun run, Model m) {
        if (s == null || s.sim) return HELD_NONE;
        if (!on) return HELD_OFF;
        if (!s.completed && !stoppedEarly) return HELD_STOPPED;
        switch (kindOf(s, run, m)) {
            case KIND_GIRTH_PLAN: return girthPlan ? HELD_NONE : HELD_GIRTH;
            case KIND_LENGTH_PLAN: return lengthPlan ? HELD_NONE : HELD_LENGTH;
            case KIND_MANUAL: return manualRuns ? HELD_NONE : HELD_MANUAL;
            default: return ownRoutines ? HELD_NONE : HELD_OWN;
        }
    }

    /** "Not sent: ..." for a session filed unmarked, in the words of the switch as the
     *  GrowthTrack screen labels it, and as it was when the session was filed. Null for
     *  HELD_NONE (an old record: the caller keeps its general line). */
    public static String filedWhy(int held) {
        switch (held) {
            case HELD_NOT_CONNECTED: return "filed while not connected";
            case HELD_OFF: return "sending to GrowthTrack was switched off";
            case HELD_STOPPED: return "stopped early, and “Include sessions stopped early” was off";
            case HELD_GIRTH: return "“Girth plan” was off";
            case HELD_LENGTH: return "“Length plan” was off";
            case HELD_OWN: return "“My own routines” was off";
            case HELD_MANUAL: return "“Manual runs” was off";
            default: return null;
        }
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("on", on).put("girth", girthPlan).put("length", lengthPlan)
            .put("own", ownRoutines).put("manual", manualRuns).put("stopped", stoppedEarly);
    }

    /** MIGRATION: absent on every model.json written before it (0.10.0, and Karl's branch,
     *  which sent everything): the defaults, which send every type except stopped-early. */
    public static GrowthTrackSettings fromJson(JSONObject o) {
        GrowthTrackSettings s = new GrowthTrackSettings();
        if (o == null) return s;
        s.on = o.optBoolean("on", true);
        s.girthPlan = o.optBoolean("girth", true);
        s.lengthPlan = o.optBoolean("length", true);
        s.ownRoutines = o.optBoolean("own", true);
        s.manualRuns = o.optBoolean("manual", true);
        s.stoppedEarly = o.optBoolean("stopped", false);
        return s;
    }
}
