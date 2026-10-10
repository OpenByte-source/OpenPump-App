package org.openpump;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** An allowlist mapper, deliberately independent of Model's broad persistence/export JSON. */
public final class GrowthTrackSession {
    private GrowthTrackSession() { }
    public static final String[] METHODS = {
        "static_pumping", "interval_pumping", "rip", "sleeved_pumping", "length_pumping",
        "milking", "high_pressure_milking", "pac", "airlock_clamping", "clamping", "hard_clamping",
        "soft_clamping", "hypoxic_clamping", "manual_clamping", "clamped_ulis", "ulis",
        "hanging", "static_hanging", "interval_hanging", "bundle_hanging", "fulcrum_hanging", "rapid_interval_hanging",
        "extending", "static_extending", "interval_extending", "bundle_extending", "rapid_interval_vacuum_extending",
        "jelqing", "manuals", "fowfers", "bundled_stretching", "mandingo_stretch", "fulcrum_bends",
        "semi_erect_bends", "vibra_tugging", "kegels", "reverse_kegels", "angion_1", "angion_2", "angion_3", "angion_4",
        "sabre", "warm_up", "massage", "edging", "ads", "bfr", "fire_goat_rolls", "tunica_shears"
    };
    public static boolean method(String slug) { for (String m : METHODS) if (m.equals(slug)) return true; return false; }

    /** Positive UPPER hold duration, excluding release/rest. Classify before any wire rounding. */
    public static String methodForHoldMillis(double holdMs) {
        if (!Double.isFinite(holdMs) || holdMs <= 0) throw new IllegalArgumentException("A positive recorded hold duration is required");
        if (holdMs <= 6000) return "milking";
        if (holdMs <= 30000) return "rip";
        if (holdMs <= 120000) return "interval_pumping";
        return "static_pumping";
    }
    public static String methodLabel(String method) {
        if ("milking".equals(method)) return "Milking";
        if ("rip".equals(method)) return "RIP";
        if ("interval_pumping".equals(method)) return "Interval pumping";
        if ("static_pumping".equals(method)) return "Static pumping";
        if ("length_pumping".equals(method)) return "Length pumping";
        return method.replace('_', ' ');
    }

    public static final class Part {
        public final String label, method;
        public final int seconds;
        public final long recordedMillis, holdMillis;
        Part(String label, long recordedMillis, long holdMillis, String method) {
            this.label = label; this.recordedMillis = recordedMillis; this.seconds = (int) (recordedMillis / 1000L);
            this.holdMillis = holdMillis; this.method = method;
        }
    }
    public static final class Draft {
        public final String id, name, startedAt, completedAt;
        public final int seconds;
        public final Double observedPeakInhg;
        public final Integer tensionSeconds;
        public final List<Part> parts = new ArrayList<Part>();
        public final List<String> warnings = new ArrayList<String>();
        private String wholeMethod;
        public String methodForWholeSession() { return wholeMethod; }
        Draft(Model.Sess s) {
            if (s == null || s.sim) throw new IllegalArgumentException("Simulated sessions cannot be sent to GrowthTrack");
            id = GrowthTrackProtocol.stableId(s.id);
            if (s.recordedStartMs <= 0 || s.recordedEndMs <= s.recordedStartMs || s.durSec < 1 || s.durSec > 86400
                || s.durSec * 1000L > s.recordedEndMs - s.recordedStartMs + 1000L)
                throw new IllegalArgumentException("Original wall-clock session boundaries are unavailable or inconsistent; older or rejoined history cannot be sent");
            name = s.routineName == null || s.routineName.trim().isEmpty() ? "OpenPump session" : s.routineName.trim();
            if (name.length() > 200) throw new IllegalArgumentException("Routine name is longer than GrowthTrack's 200-character limit");
            seconds = (int) s.durSec;
            // Only explicitly recorded wall-clock facts, never the ambiguous legacy day/key stamp.
            startedAt = iso(s.recordedStartMs); completedAt = iso(s.recordedEndMs);
            double peak = s.peakKpa == null ? Double.NaN : s.peakKpa / 3.38639;
            observedPeakInhg = Double.isFinite(peak) && peak >= 0 && peak <= 29.92 ? peak : null;
            if (s.peakKpa != null && observedPeakInhg == null) warnings.add("The implausible observed peak is omitted.");
            // Gross is sealed elapsed time INCLUDING releases. Net is the saved measured,
            // phase-filtered training time; never substitute gross or commanded duty cycle.
            double tup = s.netTupSec == null ? Double.NaN : s.netTupSec;
            tensionSeconds = Double.isFinite(tup) && tup >= 1 && tup <= seconds ? (int) Math.floor(tup) : null;
            if (s.netTupSec != null && tensionSeconds == null) warnings.add("The unavailable or inconsistent measured net time under pressure is omitted.");
            if (!s.completed) warnings.add("This was stopped early. Only its recorded elapsed work is sent.");
        }
        public JSONObject payload() throws JSONException {
            if (parts.isEmpty() && wholeMethod == null) throw new IllegalArgumentException("No classifiable recorded work exists for this session");
            JSONObject o = new JSONObject().put("external_session_id", id).put("started_at", startedAt)
                .put("completed_at", completedAt).put("routine_name", name).put("total_duration_seconds", seconds);
            if (observedPeakInhg != null) o.put("pressure_peak_inhg", observedPeakInhg);
            if (tensionSeconds != null) o.put("time_under_tension_seconds", tensionSeconds);
            if (wholeMethod != null) { o.put("method", wholeMethod); return o; }
            JSONArray sets = new JSONArray();
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                JSONObject set = new JSONObject().put("set_index", i + 1).put("method", part.method);
                // GT v1 only accepts whole positive seconds. Null preserves a subsecond
                // segment's technique without fabricating a second of delivered work.
                set.put("duration_seconds", part.seconds > 0 ? part.seconds : JSONObject.NULL);
                sets.put(set);
            }
            o.put("sets", sets);
            return o;
        }
    }

    public static Draft draft(Model.Sess s, AsRun run) {
        return draft(s, run, null);
    }
    public static Draft draft(Model.Sess s, AsRun run, Model model) {
        Draft draft = new Draft(s);
        Model.AsRunSnapshot snap = run != null && run.snap != null ? run.snap : s.asRunSnapshot;
        boolean length = snap != null && snap.trainerTrack == Plan.TRACK_LENGTH;
        // Legacy snapshots have no frozen marker. Only use a structured marker on a
        // routine whose full saved structure/values still match, never a free-text name.
        if (snap != null && snap.trainerTrack < 0 && model != null) {
            Model.Routine routine = model.routine(s.routineId);
            length = routine != null && routine.trainerTrack == Plan.TRACK_LENGTH && snap.matches(routine, model);
        }
        if (run == null || run.rows.isEmpty()) {
            if (length) {
                draft.wholeMethod = "length_pumping";
                draft.warnings.add("The recorded Length track labels this sitting. No detailed set breakdown exists; only saved session elapsed/net figures are sent, with no invented work set.");
                return draft;
            }
            throw new IllegalArgumentException("Detailed hold recording is unavailable; an automatic technique cannot be determined");
        }
        LinkedHashMap<String, Long> durations = new LinkedHashMap<String, Long>();
        LinkedHashMap<String, String> names = new LinkedHashMap<String, String>();
        LinkedHashMap<String, Long> holds = new LinkedHashMap<String, Long>();
        LinkedHashMap<String, String> methods = new LinkedHashMap<String, String>();
        long previousEnd = 0;
        for (AsRun.Row row : run.rows) {
            if (row.kind == AsRun.SKIPPED) continue;
            if (row.t0 < 0 || row.t1 < row.t0 || row.t0 < previousEnd || row.t1 > s.durSec * 1000L + 1000L)
                throw new IllegalArgumentException("The detailed recording has inconsistent timing");
            previousEnd = row.t1;
            if (!row.hasValues() || row.span() == 0) continue;
            if (!row.confirmed) { draft.warnings.add("An unconfirmed pump change is excluded from the set breakdown."); continue; }
            if (row.up <= 0) { draft.warnings.add("A zero-pressure part is excluded from the work breakdown."); continue; }
        }
        for (GrowthTrackTiming.Span span : GrowthTrackTiming.read(run, snap, length)) {
            AsRun.Row row = span.row;
            long holdMs = span.holdMs;
            String method = length ? "length_pumping" : methodForHoldMillis(holdMs);
            String key = row.stageIdx + ":" + row.pos + ":" + row.setId + ":" + method + ":" + holdMs;
            Long before = durations.get(key);
            durations.put(key, (before == null ? 0L : before) + span.workMs);
            String label = "Recorded set " + (row.pos + 1);
            if (snap != null && row.stageIdx >= 0 && row.stageIdx < snap.stages.size()) {
                Model.AsRunSnapshot.StageSnap stage = snap.stages.get(row.stageIdx);
                Model.AsRunSnapshot.SetSnap set = stage.setAt(row.pos);
                label = stage.name + (set != null && set.name != null && !set.name.isEmpty() ? " / " + set.name : "");
            }
            names.put(key, label);
            holds.put(key, holdMs); methods.put(key, method);
        }
        int total = 0;
        for (String key : durations.keySet()) {
            int seconds = (int) (durations.get(key) / 1000L);
            draft.parts.add(new Part(names.get(key), durations.get(key), holds.get(key), methods.get(key))); total += seconds;
        }
        if (draft.parts.size() > 500 || total > draft.seconds) throw new IllegalArgumentException("The recorded set breakdown exceeds GrowthTrack's limits");
        if (draft.parts.isEmpty()) throw new IllegalArgumentException("No confirmed positive-pressure work exists to send");
        draft.warnings.add(length ? "The structured Length track overrides hold-duration labels for every part." : "Techniques use confirmed command intervals, clipped to delivered recording time and joined across verified stitch boundaries: up to 6 s Milking; up to 30 s RIP; up to 120 s Interval pumping; longer Static pumping. Release/rest do not affect this label.");
        draft.warnings.add("Set durations retain recorded work spans, including their release halves. Separate rest and interrupted periods are excluded. Measured net time under pressure excludes release/rest; gross sealed time is not substituted.");
        draft.warnings.add("Command phases are reconstructed from confirmed holds/releases and elapsed spans, not sampled pressure plateaus. Ambiguous paused, lost-link or unverified stitched recordings are unavailable for automatic export. No measured plateau or pressure average is invented. GrowthTrack's whole-second durations round down; subsecond set duration is left unknown.");
        return draft;
    }
    private static String iso(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new java.util.Date(millis));
    }
}
