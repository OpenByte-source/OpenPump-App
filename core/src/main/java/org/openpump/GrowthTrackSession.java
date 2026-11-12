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
        Draft(Model.Sess s) { this(s, s == null ? null : new long[]{s.recordedStartMs, s.recordedEndMs}); }
        Draft(Model.Sess s, long[] bounds) {
            if (s == null || s.sim) throw new IllegalArgumentException("Simulated sessions cannot be sent to GrowthTrack");
            id = GrowthTrackProtocol.stableId(syncId(s));
            if (bounds == null || bounds[0] <= 0 || bounds[1] <= bounds[0] || s.durSec < 1 || s.durSec > 86400
                || s.durSec * 1000L > bounds[1] - bounds[0] + 1000L)
                throw new IllegalArgumentException("Original wall-clock session boundaries are unavailable or inconsistent; older or rejoined history cannot be sent");
            name = routineName(s.routineName);
            seconds = (int) s.durSec;
            // Only recorded wall-clock facts (or ones derived from the record's own recording,
            // see bounds()), never the ambiguous legacy day/key stamp alone.
            startedAt = iso(bounds[0]); completedAt = iso(bounds[1]);
            double peak = s.peakKpa == null ? Double.NaN : s.peakKpa / 3.38639;
            // Two decimals: the reading is a gauge figure, not a 16-digit fact (small fix).
            if (Double.isFinite(peak)) peak = Math.round(peak * 100.0) / 100.0;
            observedPeakInhg = Double.isFinite(peak) && peak >= 0 && peak <= 29.92 ? peak : null;
            if (s.peakKpa != null && observedPeakInhg == null) warnings.add("The implausible observed peak is omitted.");
            // Gross is sealed elapsed time INCLUDING releases. Net is the saved measured,
            // phase-filtered training time; never substitute gross or commanded duty cycle.
            double tup = s.netTupSec == null ? Double.NaN : s.netTupSec;
            tensionSeconds = Double.isFinite(tup) && tup >= 1 && tup <= seconds ? Integer.valueOf(Summary.wholeSeconds(tup)) : null;
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
        Draft draft = new Draft(s, bounds(s, run));
        // A stopped imported static run remains static, even when shorter than a native hold threshold.
        // Send only saved elapsed time and observed telemetry; never synthesize a delivered target.
        if ("static_pumping".equals(s.gtImportedMethod)) {
            draft.wholeMethod = s.gtImportedMethod;
            return draft;
        }
        final Model.AsRunSnapshot snap = run != null && run.snap != null ? run.snap : s.asRunSnapshot;
        boolean length = snap != null && snap.trainerTrack == Plan.TRACK_LENGTH;
        // Legacy snapshots have no frozen marker. Only use a structured marker on a
        // routine whose full saved structure/values still match, never a free-text name.
        Model.Routine routine = model == null ? null : model.routine(s.routineId);
        final boolean live = snap != null && routine != null && snap.matches(routine, model);
        if (snap != null && snap.trainerTrack < 0 && live)
            length = routine.trainerTrack == Plan.TRACK_LENGTH;
        if (run == null || run.rows.isEmpty()) {
            if (length) {
                draft.wholeMethod = "length_pumping";
                draft.warnings.add("The recorded Length track labels this sitting. No detailed set breakdown exists; only saved session elapsed/net figures are sent, with no invented work set.");
                return draft;
            }
            throw new IllegalArgumentException("Detailed hold recording is unavailable; an automatic technique cannot be determined");
        }
        // STAGE ROLES, NOT THE TRACK (bugs 7, 17). A Length session's girth expansion is girth
        // work and its warm-up is a warm-up; only Stage#traction is length work. The frozen
        // role answers; an old snapshot falls back to a routine that still matches it; with
        // neither, a recorded Length track keeps the whole-sitting label it always had.
        final int fallback = length ? GrowthTrackTiming.LENGTH : GrowthTrackTiming.WORK;
        final Model.Routine matching = live ? routine : null;
        GrowthTrackTiming.Roles roles = new GrowthTrackTiming.Roles() {
            @Override public int of(AsRun.Row row) {
                int role = Model.AsRunSnapshot.ROLE_UNKNOWN;
                if (snap != null && row.stageIdx >= 0 && row.stageIdx < snap.stages.size())
                    role = snap.stages.get(row.stageIdx).role;
                if (role == Model.AsRunSnapshot.ROLE_UNKNOWN && matching != null
                        && row.stageIdx >= 0 && row.stageIdx < matching.stages.size())
                    role = Model.AsRunSnapshot.roleOf(matching.stages.get(row.stageIdx));
                if (role == Model.AsRunSnapshot.ROLE_TRACTION) return GrowthTrackTiming.LENGTH;
                if (role == Model.AsRunSnapshot.ROLE_WARM) return GrowthTrackTiming.WARM;
                if (role == Model.AsRunSnapshot.ROLE_WORK) return GrowthTrackTiming.WORK;
                return fallback;
            }
        };
        LinkedHashMap<String, Long> durations = new LinkedHashMap<String, Long>();
        LinkedHashMap<String, String> names = new LinkedHashMap<String, String>();
        LinkedHashMap<String, Long> holds = new LinkedHashMap<String, Long>();
        LinkedHashMap<String, String> methods = new LinkedHashMap<String, String>();
        long previousEnd = 0;
        boolean anyConfirmed = false;
        for (AsRun.Row row : run.rows) {
            if (row.kind == AsRun.SKIPPED) continue;
            if (row.t0 < 0 || row.t1 < row.t0 || row.t0 < previousEnd || row.t1 > s.durSec * 1000L + 1000L)
                throw new IllegalArgumentException("The detailed recording has inconsistent timing");
            previousEnd = row.t1;
            if (!row.hasValues() || row.span() == 0) continue;
            if (row.confirmed && row.up > 0) anyConfirmed = true;
            if (!row.confirmed) draft.warnings.add("A pump change the pump did not acknowledge is sent as it was commanded.");
            if (row.up <= 0) { draft.warnings.add("A zero-pressure part is excluded from the work breakdown."); continue; }
        }
        for (GrowthTrackTiming.Span span : GrowthTrackTiming.read(run, snap, roles, anyConfirmed)) {
            AsRun.Row row = span.row;
            long holdMs = span.holdMs;
            String method = span.role == GrowthTrackTiming.LENGTH ? "length_pumping"
                : span.role == GrowthTrackTiming.WARM ? "warm_up" : methodForHoldMillis(holdMs);
            // A warm-up stage is one warm_up set however many steps its ramp climbs.
            String key = span.role == GrowthTrackTiming.WARM ? row.stageIdx + ":warm_up"
                : row.stageIdx + ":" + row.pos + ":" + row.setId + ":" + method + ":" + holdMs;
            Long before = durations.get(key);
            durations.put(key, (before == null ? 0L : before) + span.workMs);
            String label = "Recorded set " + (row.pos + 1);
            if (snap != null && row.stageIdx >= 0 && row.stageIdx < snap.stages.size()) {
                Model.AsRunSnapshot.StageSnap stage = snap.stages.get(row.stageIdx);
                Model.AsRunSnapshot.SetSnap set = stage.setAt(row.pos);
                label = span.role == GrowthTrackTiming.WARM ? stage.name
                    : stage.name + (set != null && set.name != null && !set.name.isEmpty() ? " / " + set.name : "");
            }
            if (!names.containsKey(key)) names.put(key, label);
            holds.put(key, holdMs); methods.put(key, method);
        }
        int total = 0;
        for (String key : durations.keySet()) {
            int seconds = (int) (durations.get(key) / 1000L);
            draft.parts.add(new Part(names.get(key), durations.get(key), holds.get(key), methods.get(key))); total += seconds;
        }
        if (draft.parts.size() > 500 || total > draft.seconds) throw new IllegalArgumentException("The recorded set breakdown exceeds GrowthTrack's limits");
        if (draft.parts.isEmpty()) throw new IllegalArgumentException("No confirmed positive-pressure work exists to send");
        draft.warnings.add("Techniques: a warm-up stage is warm_up and a traction stage is length_pumping; other work uses its command hold: up to 6 s Milking; up to 30 s RIP; up to 120 s Interval pumping; longer Static pumping. Release/rest do not affect this label.");
        draft.warnings.add("Set durations retain recorded work spans, including their release halves. Separate rest, held and lost-link periods are excluded. Measured net time under pressure excludes release/rest; gross sealed time is not substituted.");
        draft.warnings.add("Command phases are reconstructed from holds/releases and elapsed spans, not sampled pressure plateaus. No measured plateau or pressure average is invented. GrowthTrack's whole-second durations round down; a sub-second remainder joins the cycle before it, and the last cycle a set's own time cuts short joins that set.");
        return draft;
    }

    /** The external id's base: a resumed or rejoined session keeps its first part's id
     *  (Sess#syncId, carried by RunParts#foldTime) so it replaces that part on GrowthTrack. */
    public static String syncId(Model.Sess s) {
        return s.syncId != null && !s.syncId.isEmpty() ? s.syncId : s.id;
    }

    /** GrowthTrack's 200-character limit, kept without splitting a character in two. */
    static String routineName(String raw) {
        String name = raw == null || raw.trim().isEmpty() ? "OpenPump session" : raw.trim();
        if (name.length() <= 200) return name;
        int cut = 200;
        if (Character.isHighSurrogate(name.charAt(cut - 1))) cut--;
        return name.substring(0, cut).trim();
    }

    /**
     * THE SESSION'S WALL-CLOCK START AND END, or null when they cannot be known.
     *
     * Recorded bounds win. Without them (every 0.10.0 session, and a resumed one before its
     * end was stamped) they are derived only from the record's OWN facts and only when they
     * agree with each other: the id is "sess" + the filing instant (the end) on every release,
     * the ts is the start since audit A20 (and a rejoined/resumed session's first part's
     * start), and the record must have its own recording (rows, keyed by that ts). A release
     * that stamped ts at the end gives a span shorter than the session, which fails the check.
     */
    public static long[] bounds(Model.Sess s, AsRun run) {
        if (s == null) return null;
        if (s.recordedStartMs > 0 && s.recordedEndMs > s.recordedStartMs) return new long[]{s.recordedStartMs, s.recordedEndMs};
        long end = s.recordedEndMs > 0 ? s.recordedEndMs : filedAt(s.id);
        long start = s.recordedStartMs > 0 ? s.recordedStartMs : s.ts;
        if (end <= 0 || start <= 0 || end <= start || s.durSec < 1) return null;
        if (run == null || run.rows.isEmpty() || (run.ts != 0 && run.ts != s.ts)) return null;
        long span = end - start, dur = s.durSec * 1000L;
        if (span + 1000L < dur || span > dur + 24L * 3600_000L) return null;
        return new long[]{start, end};
    }
    /** The filing instant a "sess" + epoch-ms id carries, or 0. */
    static long filedAt(String id) {
        if (id == null || !id.matches("sess\\d{12,13}")) return 0;
        try { return Long.parseLong(id.substring(4)); } catch (NumberFormatException e) { return 0; }
    }
    private static String iso(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new java.util.Date(millis));
    }
}
