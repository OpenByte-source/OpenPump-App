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
        if (!on) return "Sending to GrowthTrack is switched off";
        if (!s.completed && !stoppedEarly) return "Stopped early, and sessions stopped early are switched off";
        switch (kindOf(s, run, m)) {
            case KIND_GIRTH_PLAN: return girthPlan ? null : "Girth plan sessions are switched off";
            case KIND_LENGTH_PLAN: return lengthPlan ? null : "Length plan sessions are switched off";
            case KIND_MANUAL: return manualRuns ? null : "Manual runs are switched off";
            default: return ownRoutines ? null : "Your own routines are switched off";
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
