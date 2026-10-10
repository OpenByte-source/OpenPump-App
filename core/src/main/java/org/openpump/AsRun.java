package org.openpump;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * WHAT A RUN ACTUALLY DID, and what can be saved out of it — the pure heart of the
 * "save what you ran" card (docs/superpowers/specs/2026-08-19-block-save-design.md, §2–§5).
 *
 * Four layers, each a function of the one below it, none of which knows a screen exists:
 *
 *  1. ROWS (§2) — the recording. SessionActivity opens one {@link Row} per thing it puts on
 *     the pump (a planned preset, a live adjustment, a hold, a lost link, the auto-stop vent)
 *     and closes it when the next thing starts. Times are on the session's elapsed clock.
 *  2. BLOCKS (§3) — {@link #blocks}: the rows of each planned set, grouped and split into the
 *     pieces a person would recognise: "the ramp as far as it got", "the adjusted part",
 *     "the set exactly as planned". A block is the unit the card shows and the unit a save
 *     turns into a set.
 *  3. THRESHOLD (§4) — {@link #evaluate}: does this run differ from its plan enough to put the
 *     card in front of someone, or only enough for one quiet sentence?
 *  4. DRAFT + COMMIT (§5) — {@link #draft} suggests what to save (names, on/off, mode) and
 *     {@link #commit} applies a draft to the Model: new sets, a re-pointed or new routine.
 *
 * Pure on purpose — no android import — so every rule here is pinned by SelfTest on the
 * desktop (test.sh auto-discovers this file). Nothing in this class writes to the pump,
 * touches a file, or knows about a View.
 */
public final class AsRun {

    /* ====================================================================== §2 rows */

    /** Row kinds. A PLAN row is the routine's own slot playing; OVERRIDE is the user's live
     *  adjustment (sendOverridePreset from the run screen, and every later step of the stage
     *  it carries into); HOLD / LOST / VENT are pauses and breaks that carry no new set
     *  values; SKIPPED is a preset the user skipped before half of it was delivered (§3). */
    public static final int PLAN = 0, OVERRIDE = 1, HOLD = 2, LOST = 3, VENT = 4, SKIPPED = 5;

    /**
     * A REST entry of the routine (Model.Set#rest) playing: the cuff is deliberately
     * vented and the run is simply waiting. Like HOLD and LOST it carries NO set values,
     * because nothing was commanded - so it never contributes to a block's values, never
     * splits one, and its span counts as PAUSED time.
     *
     * WHY IT IS ITS OWN KIND AND NOT REUSED HOLD. A HOLD is the user pressing pause on a
     * preset that is still under pressure; a REST is the routine's own step, at zero. They
     * are excluded from blocks by the same rules, but they are different facts about what
     * happened, and a card that told someone they had held at pressure when the routine had
     * actually vented them would be describing a different session.
     *
     * MIGRATION: no recording written before this exists can contain a 6, and fromJson
     * defaults an unknown kind to PLAN exactly as it always did.
     */
    public static final int REST = 6;

    /** One thing that was put on the pump, from t0 until t1 on the session's elapsed clock.
     *  Pressures are kPa AFTER the ceiling (what actually went on the wire); upReq is what was
     *  ASKED for, so a block can say "limited by the ceiling" (§5) without consulting a
     *  ceiling that may since have moved. uhWire is the hold seconds actually written (an
     *  adjustment's hold is capped to the seconds left when it is applied); uhReq is the hold
     *  the user asked for, and is what identity and the threshold use (§3, §4). */
    public static final class Row {
        public int kind;
        /** Start / end, ms on the session's elapsed clock. t1 is -1 while the row is open. */
        public long t0, t1 = -1L;
        public int stageIdx;
        public String setId = "";
        /** Position of the set within its stage (Preset#pos) and position of the preset
         *  within the set's ladder (Preset#ordinal). */
        public int pos, ordinal;
        /** Frozen stitch boundary: -1 on old recordings, 0 a complete preset, 1 a
         * continuation chunk. Data only; never changes how a preset is driven. */
        public int cyclePart = -1;
        public int up, upReq, lo, uhWire, uhReq, lh, sp;
        /** False when the pump never acknowledged the write this row records (§2) — the
         *  card then says "the pump didn't confirm this change". */
        public boolean confirmed = true;
        /** Explicit "+30 s" extensions recorded against this row, in ms (see
         *  {@link AsRun#extend}). Part of the recording, not inferred from timing, so the
         *  threshold's "extend ≥ 30 s" (§4) is exact rather than jittered. */
        public long extendMs;
        /** C7 - how long the "at pressure only" set clock HELD this row's preset, in ms:
         *  time the cuff spent under the line, which the clock added to the preset's length
         *  (SessionActivity#tickTupTiming). Recorded like extendMs, not inferred from timing,
         *  because it is the one lengthening nobody chose: that timing mode runs a set until
         *  it has delivered its time, so the extra is how the set was timed, not a change to
         *  it. {@link AsRun#blocks} takes it out of every length a save could write. */
        public long clockHeldMs;

        /** Delivered span of this row in ms — 0 while it is open. */
        public long span() { return t1 < t0 ? 0L : t1 - t0; }
        /** Whether this row carries set values (a PLAN or OVERRIDE row). */
        public boolean hasValues() { return kind == PLAN || kind == OVERRIDE; }
        /** The identity §3 splits on: (up, lo, uhReq, lh, sp). uhWire is deliberately NOT
         *  part of it — two nudges to the same values with different seconds left are one
         *  block. */
        public boolean sameValues(Row o) {
            return up == o.up && lo == o.lo && uhReq == o.uhReq && lh == o.lh && sp == o.sp;
        }
        public boolean sameValues(Model.Preset p) {
            return up == p.up && lo == p.lo && uhReq == p.uh && lh == p.lh && sp == p.sp;
        }
        /** Same (stage, position, set, ordinal) — the same preset occurrence. */
        public boolean samePreset(Row o) {
            return stageIdx == o.stageIdx && pos == o.pos && ordinal == o.ordinal
                && eq(setId, o.setId);
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("k", kind);
            // Longs go through strings, like every long Model writes — the desktop org.json
            // shim has no optLong, and one reader for both JVMs is the point of the shim.
            o.put("t0", String.valueOf(t0)); o.put("t1", String.valueOf(t1));
            o.put("st", stageIdx); o.put("pos", pos); o.put("ord", ordinal);
            if (cyclePart >= 0) o.put("cyclePart", cyclePart);
            o.put("sid", setId == null ? "" : setId);
            o.put("up", up); o.put("upr", upReq); o.put("lo", lo);
            o.put("uhw", uhWire); o.put("uhr", uhReq); o.put("lh", lh); o.put("sp", sp);
            o.put("conf", confirmed);
            o.put("ext", String.valueOf(extendMs));
            o.put("clk", String.valueOf(clockHeldMs));
            return o;
        }

        public static Row fromJson(JSONObject o) {
            Row r = new Row();
            r.kind = o.optInt("k", PLAN);
            r.t0 = parseLong(o.optString("t0", "0"), 0L);
            r.t1 = parseLong(o.optString("t1", "-1"), -1L);
            r.stageIdx = o.optInt("st", 0); r.pos = o.optInt("pos", 0); r.ordinal = o.optInt("ord", 0);
            int part = o.optInt("cyclePart", -1);
            r.cyclePart = part == 0 || part == 1 ? part : -1;
            r.setId = o.optString("sid", "");
            r.up = o.optInt("up", 0);
            // upReq absent (a row written before it existed) → what went on the wire: no
            // claim of a ceiling limit the recording never made.
            r.upReq = o.optInt("upr", r.up);
            r.lo = o.optInt("lo", 0);
            r.uhReq = o.optInt("uhr", 0);
            r.uhWire = o.optInt("uhw", r.uhReq);
            r.lh = o.optInt("lh", 0); r.sp = o.optInt("sp", 0);
            r.confirmed = o.optBoolean("conf", true);
            r.extendMs = parseLong(o.optString("ext", "0"), 0L);
            // MIGRATION (C7): absent on every row written before the clock was recorded -
            // nothing held, which is how those rows have always been read.
            r.clockHeldMs = parseLong(o.optString("clk", "0"), 0L);
            return r;
        }
    }

    /* ---------------------------------------------------------------- the recording */

    /** The rows, in the order they were opened. */
    public final List<Row> rows = new ArrayList<Row>();
    /** The routine that was run (Manual.ID for a manual run) and its name at run start. */
    public String routineId = "", routineName = "";
    /** The routine's structure at run start — Model.AsRunSnapshot (Task 1). Null only for a
     *  recording that predates it; Task 5 snapshots manual runs too (their one-stage
     *  wrapper), so the stage name reaches the card the same way. */
    public Model.AsRunSnapshot snap;
    /**
     * THE CEILING IN FORCE WHEN THE RUN STARTED, kPa — 0 on a recording written before this
     * existed (and then the Model's current ceiling stands in, which is what that recording
     * has always been read with).
     *
     * The planned ladder is the set's values under THIS ceiling, not under whatever the
     * ceiling has become since: lowering the ceiling after a run must not rewrite what the
     * card says was planned, and must not turn an untouched occurrence into an "adjusted"
     * one. {@link #saveCeilKpa} is the other half — what a save is allowed to write.
     */
    public int ceilKpa;
    public boolean manual;
    /** The Sess.ts this run is filed under — the key of asrun.json and the value
     *  {@link #commit} stamps into Set#fromRun. 0 until the recorder knows it. */
    public long ts;
    /** MANUAL RUNS ONLY: the ephemeral set as it was STARTED (Manual#ephemeral), because
     *  model.adhoc is cleared at filing and the summary — where the card lives — comes after
     *  filing, so the planned values of a manual run are resolvable from nowhere else. Null
     *  for a routine run. When absent on a manual run, {@link #blocks} falls back to the first
     *  PLAN row's values as a fixed plan, which is right for a fixed manual cycle and the best
     *  available guess for a ramp one. */
    public Model.Set manualSet;

    public AsRun() { }

    public AsRun(String routineId, String routineName, Model.AsRunSnapshot snap, boolean manual) {
        this(routineId, routineName, snap, manual, 0);
    }

    public AsRun(String routineId, String routineName, Model.AsRunSnapshot snap, boolean manual,
                 int ceilKpa) {
        this.routineId = routineId == null ? "" : routineId;
        this.routineName = routineName == null ? "" : routineName;
        this.snap = snap;
        this.manual = manual;
        this.ceilKpa = ceilKpa;
    }

    /** The ceiling the run's own ladder is derived under: the recorded one, or the Model's
     *  current one for a recording that never carried it. */
    public int runCeilKpa(Model m) {
        return ceilKpa > 0 ? ceilKpa : (m == null ? 57 : m.ceilKpa);
    }

    /**
     * The ceiling a SAVE out of this run writes under: never above the ceiling in force NOW.
     * A set written above the current ceiling would not survive the next load (Model#clampAll
     * re-clamps every set), so a save that promised it would be lying about tomorrow; the
     * card says plainly what the lower ceiling will do instead — {@link #ceilingNowCaption}.
     */
    public static int saveCeilKpa(AsRun run, Model m) {
        int now = m == null ? 57 : m.ceilKpa;
        int at = run == null ? 0 : run.ceilKpa;
        return at > 0 ? Math.min(at, now) : now;
    }

    /** What a block's pull would be SAVED as under `ceilNow` — Set#clamp's own upper rule. */
    public static int saveUpKpa(int up, int ceilNow) {
        int cap = Math.min(57, ceilNow);
        if (cap < 1) cap = 1;
        return up > cap ? cap : (up < 1 ? 1 : up);
    }

    /** The card's line for a block the ceiling has come down under since the run: "" when it
     *  has not. Says the number the save will actually write, so nothing is lowered in
     *  silence. Pressures through Fmt — never a literal unit. */
    public static String ceilingNowCaption(Block b, int ceilNow) {
        if (b == null) return "";
        int top = Math.max(b.up, b.up2);
        int saved = saveUpKpa(top, ceilNow);
        if (saved >= top) return "";
        return "limited by the ceiling — this saves at " + Model.Fmt.p(saved);
    }

    /** The row still open (t1 < 0), or null. At most one row is ever open: {@link #open}
     *  closes the previous one at the new row's t0, so rows never overlap. */
    public Row openRow() {
        for (int i = rows.size() - 1; i >= 0; i--) if (rows.get(i).t1 < 0) return rows.get(i);
        return null;
    }

    /** The last row opened, open or closed, or null. */
    public Row lastRow() { return rows.isEmpty() ? null : rows.get(rows.size() - 1); }

    /**
     * Opens a row at t0 for the given preset (stage / position / set / ordinal read off the
     * Preset's plan() stamps), closing whatever row was open at the same t0 — the new thing
     * starting IS the old thing ending, so the recorder never has to remember to close.
     * Pressures are the ceiling-limited values that went on the wire; upReq is what was asked.
     */
    public Row open(int kind, long t0, Model.Preset p,
                    int up, int upReq, int lo, int uhWire, int uhReq, int lh, int sp) {
        Row row = open(kind, t0, p == null ? 0 : p.stageIdx, p == null ? 0 : p.pos,
                    p == null ? "" : p.setId, p == null ? 0 : p.ordinal,
                    up, upReq, lo, uhWire, uhReq, lh, sp);
        if (p != null && row.hasValues()) row.cyclePart = p.cyclePart ? 1 : 0;
        return row;
    }

    /** {@link #open(int, long, Model.Preset, int, int, int, int, int, int, int)} with the
     *  identity spelled out. */
    public Row open(int kind, long t0, int stageIdx, int pos, String setId, int ordinal,
                    int up, int upReq, int lo, int uhWire, int uhReq, int lh, int sp) {
        close(t0);
        Row r = new Row();
        r.kind = kind; r.t0 = t0; r.t1 = -1L;
        r.stageIdx = stageIdx; r.pos = pos; r.setId = setId == null ? "" : setId; r.ordinal = ordinal;
        r.up = up; r.upReq = upReq; r.lo = lo; r.uhWire = uhWire; r.uhReq = uhReq; r.lh = lh; r.sp = sp;
        rows.add(r);
        return r;
    }

    /** Closes the open row at t1 (never before its own t0). Returns it, or null if nothing
     *  was open. */
    public Row close(long t1) {
        Row r = openRow();
        if (r == null) return null;
        r.t1 = Math.max(r.t0, t1);
        return r;
    }

    /**
     * The user SKIPPED the preset playing now, at t (§3). Closes the open row, then decides
     * on the whole preset occurrence (every PLAN/OVERRIDE row with the same stage / position
     * / set / ordinal — a preset resumed after a hold is still one preset): delivered under
     * HALF of what it would have run (plannedMs plus any +30 s recorded on it) → all of those
     * rows become SKIPPED, so the set yields no value block and the card can offer "drop it";
     * half or more → the rows stay as they are and the block reads "cut short". Returns the
     * row that was closed, or null if nothing was open.
     *
     * Half is asked of the set's OWN clock (C7): time the at-pressure clock held it
     * ({@link Row#clockHeldMs}) is not progress through it, so a set held a minute and
     * skipped ten seconds into its own two minutes was skipped, not "cut short".
     */
    public Row skip(long t, long plannedMs) {
        Row cur = close(t);
        if (cur == null) return null;
        long delivered = 0, extend = 0, paused = 0, held = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!r.samePreset(cur)) continue;
            held += r.clockHeldMs;
            if (r.hasValues()) { delivered += r.span(); extend += r.extendMs; }
            // A dropout, a vent the run survived and a hold are time this preset SPENT but
            // did not deliver. Counting them as missing delivery made a four-second
            // Bluetooth gap read as "you skipped it" — see isCut.
            else if (r.kind == HOLD || r.kind == LOST || r.kind == VENT || r.kind == REST)
                paused += r.span();
        }
        if ((delivered + paused - held) * 2 < plannedMs + extend) {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (r.hasValues() && r.samePreset(cur)) r.kind = SKIPPED;
            }
        }
        return cur;
    }

    /** A "+30 s" against the preset playing now — recorded on the open row. Returns false
     *  (and records nothing) when no row is open. */
    public boolean extend(long ms) {
        Row r = openRow();
        if (r == null) return false;
        r.extendMs += Math.max(0L, ms);
        return true;
    }

    /** C7 - the at-pressure set clock held the preset playing now for `ms` more, lengthening
     *  it: recorded on the open row ({@link Row#clockHeldMs}). Returns false (and records
     *  nothing) when no row is open. */
    public boolean clockHeld(long ms) {
        Row r = openRow();
        if (r == null) return false;
        r.clockHeldMs += Math.max(0L, ms);
        return true;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("rid", routineId == null ? "" : routineId);
        o.put("rname", routineName == null ? "" : routineName);
        o.put("manual", manual);
        o.put("ts", String.valueOf(ts));
        o.put("ceil", ceilKpa);
        // One-element arrays for the nested objects — the one shape both the phone's org.json
        // and the desktop shim decode with the same code (no optJSONObject(String) there).
        if (snap != null) { JSONArray a = new JSONArray(); a.put(snap.toJson()); o.put("snap", a); }
        if (manualSet != null) { JSONArray a = new JSONArray(); a.put(manualSet.toJson()); o.put("mset", a); }
        JSONArray rs = new JSONArray();
        for (int i = 0; i < rows.size(); i++) rs.put(rows.get(i).toJson());
        o.put("rows", rs);
        return o;
    }

    public static AsRun fromJson(JSONObject o) {
        AsRun a = new AsRun();
        if (o == null) return a;
        a.routineId = o.optString("rid", "");
        a.routineName = o.optString("rname", "");
        a.manual = o.optBoolean("manual", false);
        a.ts = parseLong(o.optString("ts", "0"), 0L);
        // MIGRATION: absent on every recording written before the run's own ceiling was
        // recorded. 0 means "not recorded", and runCeilKpa then falls back to the Model's
        // current ceiling — exactly how those recordings have always been read.
        a.ceilKpa = o.optInt("ceil", 0);
        JSONArray sa = o.optJSONArray("snap");
        a.snap = sa != null && sa.length() > 0 ? Model.AsRunSnapshot.fromJson(sa.optJSONObject(0)) : null;
        JSONArray ma = o.optJSONArray("mset");
        a.manualSet = ma != null && ma.length() > 0 && ma.optJSONObject(0) != null
                    ? Model.Set.fromJson(ma.optJSONObject(0)) : null;
        JSONArray rs = o.optJSONArray("rows");
        if (rs != null) for (int i = 0; i < rs.length(); i++) {
            JSONObject ro = rs.optJSONObject(i);
            if (ro != null) a.rows.add(Row.fromJson(ro));
        }
        return a;
    }

    /* ==================================================================== §3 blocks */

    public static final int FIXED = 0, RAMP = 1;

    /** Under this many ms of difference a preset's delivered time is "as planned": the app
     *  timer ends a preset within tens of ms of its duration; two seconds is far beyond any
     *  jitter and far below the smallest deliberate change (the editor moves in 30 s). */
    public static final long CUT_SHORT_TOL_MS = 2000L;

    /**
     * One piece of the run as the card shows it and as a save turns it into a set.
     *
     * The brief's fields, plus what the later layers need carried along: the stage and set
     * names (so the quiet line can say "Work" without a second lookup), the planned step's
     * values this block is compared against (pUp..pSp — manual runs have no resolvable set
     * after filing), the delivered span, the explicit extension, and the flags the card's
     * captions are made of (cutShort, edited, onPlan, hasOverride, ceilingLimited, confirmed).
     *
     * A SKIPPED block (skipped == true) is a PLACEHOLDER for a set every preset of which was
     * skipped (§3): it carries the set's planned values for display, is never saveable, and
     * exists so the routine card can show "drop <set> from <stage>" in plan order. It is not
     * a value block — "skip < 50 % → no block" in §3 is about value blocks.
     */
    public static final class Block {
        public int idx;
        public int kind;
        public boolean unchanged, tooShort, skipped, confirmed = true;
        public String setId = "";
        public int stageIdx, pos;
        /** First row's start, last row's end (holds inside the block lie within). */
        public long t0, t1;
        /** Delivered ms — PLAN/OVERRIDE spans only; HOLD and LOST time is excluded (§3). */
        public long spanMs;
        public int up, lo, uh, lh, sp, up2, lo2, uh2, lh2, sp2, steps;
        /** FIXED blocks: whole cycles delivered, floor(spanMs / ((uh + lh) s)). 0 for RAMP. */
        public int cycles;
        /** Some row asked for more pressure than the ceiling allowed (upReq > up). */
        public boolean ceilingLimited;
        /** FIXED: its last preset delivered less than planned (+ extensions); RAMP: fewer
         *  steps started than the set has. */
        public boolean cutShort;
        /** Explicit "+30 s" extensions inside this block, plus any overrun of a preset beyond
         *  its planned length by more than CUT_SHORT_TOL_MS (an edited upcoming duration). The
         *  at-pressure clock's held time is not an overrun (C7): see clockHeldMs. */
        public long extendMs;
        /** C7 - how much of spanMs the at-pressure set clock held this block's presets
         *  ({@link Row#clockHeldMs}, value rows only). Delivered, so spanMs keeps it and the
         *  cycles delivered count it; never a length the user chose, so nothing a save writes
         *  does ({@link #saveDurSec}, {@link AsRun#setChanges}). */
        public long clockHeldMs;
        /** Contains a PLAN row whose values differ from its planned step — an upcoming preset
         *  the user edited mid-run (§4: always over the threshold). */
        public boolean edited;
        /** Contains an OVERRIDE row. */
        public boolean hasOverride;
        /** The block's values ARE the planned step's values; it is a separate block only
         *  because its set was split around an adjustment (the "· in" part, §5). */
        public boolean onPlan;
        /** The ladder step this block began in, and the planned values of that step (valid
         *  when hasPlan). For a RAMP block these are the step it started with. */
        public int firstOrdinal;
        public boolean hasPlan;
        public int pUp, pLo, pUh, pLh, pSp;
        /** Presets of this block's SET occurrence that were SKIPPED (§3 partial skip inside a
         *  ramp) — the same number on every block of that set; 0 normally. */
        public int skippedSteps;
        /** Whether the one cut-short preset this block ends on was the LAST preset of the
         *  whole run: a run stopped early cuts its final preset by definition, and that is
         *  "stopped early" (§3), not a change to save. */
        public boolean cutIsRunEnd;
        /** A preset of this block's SET OCCURRENCE was cut short (a skip at ≥ 50 %, §3, or
         *  an edited shorter duration) and the run went on after it — the "cut short" the
         *  quiet line reports. The same on every block of the occurrence; never set by the
         *  run simply ending. */
        public boolean cutMidRun;
        /** The planned set's own duration in seconds (0 when there is no plan) — what an
         *  UNCHANGED block saves with (a manual cycle saved as a copy is the cycle as
         *  configured, not its delivered time snapped). */
        public int planDurSec;
        public String stageName = "", setName = "";
        public boolean manual;

        /** What this block would save as, in seconds, before Set#clamp's floor — whole
         *  cycles for FIXED (§3 snap DOWN), the delivered span for RAMP — on the set's OWN
         *  clock: the at-pressure clock's held time is how the set was timed, and saving it
         *  would lengthen the routine for good (C7). */
        public int saveDurSec(long extraSpanMs) {
            long total = ownSpanMs() + Math.max(0L, extraSpanMs);
            if (kind == RAMP) return (int) (total / 1000L);
            long cyc = cycleMs();
            return (int) ((total / cyc) * cyc / 1000L);
        }
        /** One cycle in ms, never 0 (Set#cycle's own floor of one second). */
        public long cycleMs() { return Math.max(1, uh + lh) * 1000L; }
        /** The delivered span less the at-pressure clock's held time: the block's length on
         *  its set's own clock (C7). Equal to spanMs in a run timed "By the clock". */
        public long ownSpanMs() { return Math.max(0L, spanMs - clockHeldMs); }
    }

    /** Rows of one set OCCURRENCE — (stageIdx, pos, setId) — in order, pauses attached. */
    private static final class Group {
        int stageIdx, pos; String setId = "";
        final List<Row> rows = new ArrayList<Row>();
        boolean same(Row r) { return stageIdx == r.stageIdx && pos == r.pos && eq(setId, r.setId); }
    }

    /** A run of rows that will become one block while a group is being split. */
    private static final class Builder {
        boolean seg;                    // true: on-plan ladder segment; false: off-plan value run
        final List<Row> rows = new ArrayList<Row>();
        int firstOrd, lastOrd, distinctOrds;
    }

    /**
     * The blocks of a run, in time order (§3). Rules, in the order they are applied:
     *  - rows are grouped by set occurrence (stage, position in stage, set id); HOLD / LOST /
     *    VENT rows attach to the occurrence playing when they happened;
     *  - an occurrence whose value rows (PLAN/OVERRIDE) all equal its planned ladder, with no
     *    OVERRIDE, no SKIPPED, no extension, every step started (or, for the run's last set,
     *    a prefix of them — the run simply ended) and no preset cut short other than the run's
     *    final one, is UNCHANGED: one block, keeps the set id, nothing to save;
     *  - an occurrence every preset of which was SKIPPED yields one skipped placeholder;
     *  - otherwise the occurrence splits: on-plan rows at consecutive ladder steps form a
     *    RAMP block (the steps that STARTED; end = the last started step's values) — or a
     *    FIXED block when only one step started; off-plan rows split into maximal runs of
     *    equal (up, lo, uhReq, lh, sp); HOLD and LOST never split; VENT and SKIPPED always do;
     *  - FIXED blocks snap to whole cycles and are tooShort under one; RAMP blocks carry
     *    steps = started count and dur = delivered span (ladder() evens the steps out).
     * Never merges across occurrences (two identical consecutive sets stay two blocks).
     * The planned set is the Model's current set by id (Task 5's snapshot answers whether
     * the routine was edited since); for a manual run it is {@link AsRun#manualSet}.
     */
    public static List<Block> blocks(AsRun run, Model m) {
        List<Block> out = new ArrayList<Block>();
        if (run == null || m == null) return out;
        List<Group> groups = new ArrayList<Group>();
        Group cur = null;
        for (int i = 0; i < run.rows.size(); i++) {
            Row r = run.rows.get(i);
            if (r.hasValues() || r.kind == SKIPPED) {
                if (cur == null || !cur.same(r)) {
                    cur = new Group();
                    cur.stageIdx = r.stageIdx; cur.pos = r.pos; cur.setId = r.setId == null ? "" : r.setId;
                    groups.add(cur);
                }
                cur.rows.add(r);
            } else if (cur != null) {
                cur.rows.add(r);
            }
        }
        // The final value row of the whole run: the preset it belongs to is the one the run
        // ended on, and a cut there is "stopped early", not a change.
        Row runEnd = null;
        for (int i = run.rows.size() - 1; i >= 0 && runEnd == null; i--)
            if (run.rows.get(i).hasValues()) runEnd = run.rows.get(i);

        for (int gi = 0; gi < groups.size(); gi++) {
            Group g = groups.get(gi);
            boolean lastGroup = gi == groups.size() - 1;
            // The PLAN is the run's own, not the Library's today: the snapshot's values under
            // the ceiling that was in force at run start. Only a recording without one falls
            // back to the live set.
            Model.Set set = plannedSet(run, m, g);
            List<Model.Preset> ladder = set == null ? null : ladderOf(set, run.runCeilKpa(m));
            String stageName = stageName(run, m, g.stageIdx);
            Model.Set live = m.set(g.setId);
            String setName = live != null && live.name != null ? live.name
                           : (set == null || set.name == null ? "" : set.name);

            List<Row> valueRows = new ArrayList<Row>();
            int skippedRows = 0;
            boolean anyOverride = false;
            long extendTotal = 0;
            for (int i = 0; i < g.rows.size(); i++) {
                Row r = g.rows.get(i);
                if (r.hasValues()) { valueRows.add(r); extendTotal += r.extendMs; if (r.kind == OVERRIDE) anyOverride = true; }
                else if (r.kind == SKIPPED) skippedRows++;
            }
            // Distinct skipped presets (ordinals) in this occurrence.
            int skippedSteps = 0;
            List<Integer> seenSkip = new ArrayList<Integer>();
            for (int i = 0; i < g.rows.size(); i++) {
                Row r = g.rows.get(i);
                if (r.kind == SKIPPED && !seenSkip.contains(Integer.valueOf(r.ordinal))) {
                    seenSkip.add(Integer.valueOf(r.ordinal)); skippedSteps++;
                }
            }

            if (valueRows.isEmpty()) {
                if (skippedRows > 0) {
                    Block b = new Block();
                    b.skipped = true; b.kind = set != null && set.ramp ? RAMP : FIXED;
                    fillPlanValues(b, set);
                    b.setId = g.setId; b.stageIdx = g.stageIdx; b.pos = g.pos;
                    b.t0 = g.rows.get(0).t0; b.t1 = endOf(g.rows);
                    for (int i = 0; i < g.rows.size(); i++) if (g.rows.get(i).kind == SKIPPED) b.spanMs += g.rows.get(i).span();
                    b.skippedSteps = skippedSteps;
                    b.planDurSec = set == null ? 0 : set.dur;
                    b.stageName = stageName; b.setName = setName; b.manual = run.manual;
                    b.hasPlan = set != null;
                    if (ladder != null && !ladder.isEmpty()) { Model.Preset p0 = ladder.get(0); b.pUp = p0.up; b.pLo = p0.lo; b.pUh = p0.uh; b.pLh = p0.lh; b.pSp = p0.sp; }
                    b.confirmed = true;
                    out.add(b);
                }
                continue;
            }

            // THE SET WAS DELETED FROM THE LIBRARY SINCE THE RUN. A routine occurrence with
            // no plan to compare against has no ladder, so every row is "off plan" and the
            // splitter below would make each value row its own block — one occurrence
            // becoming "Set · adjusted", "(2)", "(3)", … , auto-opening the card and
            // splicing that pile of junk sets into a New-routine commit. There is nothing
            // to compare here, so we do not compare: ONE block for the whole occurrence,
            // hasPlan false, named from the stage it ran in ("<Stage> · as run"). Manual
            // runs are unaffected — plannedSet synthesises a plan for those.
            if (ladder == null && !run.manual) {
                Block b = new Block();
                Row first = valueRows.get(0);
                b.kind = FIXED;
                b.setId = g.setId; b.stageIdx = g.stageIdx; b.pos = g.pos;
                b.t0 = valueRows.get(0).t0; b.t1 = endOf(g.rows);
                for (int i = 0; i < valueRows.size(); i++) {
                    Row r = valueRows.get(i);
                    b.spanMs += r.span();
                    b.clockHeldMs += r.clockHeldMs;
                    b.extendMs += r.extendMs;
                    if (r.upReq > r.up) b.ceilingLimited = true;
                    if (!r.confirmed) b.confirmed = false;
                    if (r.kind == OVERRIDE) b.hasOverride = true;
                }
                b.up = first.up; b.lo = first.lo; b.uh = first.uhReq; b.lh = first.lh; b.sp = first.sp;
                b.up2 = b.up; b.lo2 = b.lo; b.uh2 = b.uh; b.lh2 = b.lh; b.sp2 = b.sp;
                b.firstOrdinal = first.ordinal;
                b.cycles = (int) (b.spanMs / b.cycleMs());
                b.tooShort = b.ownSpanMs() < b.cycleMs();
                b.hasPlan = false;
                b.skippedSteps = skippedSteps;
                b.planDurSec = 0;
                b.stageName = stageName;
                // The name the card offers comes from the stage, not from a set that no
                // longer exists: draft() reads setName and appends " · as run".
                b.setName = stageName;
                b.manual = false;
                out.add(b);
                continue;
            }

            // Per-preset delivery inside this occurrence: delivered, extended and PAUSED ms
            // by ordinal. Paused time (a hold, a dropout, a vent the run survived) is time
            // the preset spent without delivering: it is neither delivery nor a shortfall,
            // and counting it as a shortfall is what made a four-second Bluetooth gap read
            // as "you cut Work short".
            //
            // AND THE AT-PRESSURE CLOCK'S HELD TIME (C7), the fourth figure. With Set timing
            // at "At pressure only" a preset runs until it has delivered its time, so it
            // runs longer by however long the cuff sat under the line - and that surplus read
            // as an edited duration: "SAVE CHANGES" after every such run, and a save that
            // wrote the stretched length into the routine. It is how the set was timed, not
            // a change to it, so it is part of the plan here, the way +30 s is.
            List<Integer> ords = new ArrayList<Integer>();
            List<long[]> perOrd = new ArrayList<long[]>();     // {delivered, extend, paused, held}
            for (int i = 0; i < valueRows.size(); i++) {
                Row r = valueRows.get(i);
                int at = ords.indexOf(Integer.valueOf(r.ordinal));
                if (at < 0) { ords.add(Integer.valueOf(r.ordinal)); perOrd.add(new long[]{0L, 0L, 0L, 0L}); at = ords.size() - 1; }
                perOrd.get(at)[0] += r.span();
                perOrd.get(at)[1] += r.extendMs;
                perOrd.get(at)[3] += r.clockHeldMs;
            }
            for (int i = 0; i < g.rows.size(); i++) {
                Row r = g.rows.get(i);
                if (r.kind != HOLD && r.kind != LOST && r.kind != VENT && r.kind != REST)
                    continue;
                int at = ords.indexOf(Integer.valueOf(r.ordinal));
                if (at >= 0) { perOrd.get(at)[2] += r.span(); perOrd.get(at)[3] += r.clockHeldMs; }
            }
            // Overrun beyond plan + explicit extension + the clock's held time counts as an
            // edited duration.
            long overrunTotal = 0;
            for (int i = 0; i < ords.size(); i++) {
                long planned = plannedMs(ladder, ords.get(i).intValue());
                if (planned > 0) {
                    long over = perOrd.get(i)[0] - (planned + perOrd.get(i)[1] + perOrd.get(i)[3]);
                    if (over >= CUT_SHORT_TOL_MS) overrunTotal += over;
                }
            }

            boolean allOnPlan = ladder != null;
            boolean anyEdited = false;
            if (ladder != null) for (int i = 0; i < valueRows.size(); i++) {
                Row r = valueRows.get(i);
                boolean on = onPlan(r, ladder);
                if (!on) { allOnPlan = false; if (r.kind == PLAN) anyEdited = true; }
            }
            boolean ordsCover = false;
            if (ladder != null) {
                boolean all = true, prefix = true;
                for (int k = 0; k < ladder.size(); k++) {
                    boolean present = ords.contains(Integer.valueOf(k));
                    if (!present) all = false;
                    if (!present) { for (int k2 = k + 1; k2 < ladder.size(); k2++) if (ords.contains(Integer.valueOf(k2))) prefix = false; break; }
                }
                ordsCover = all || (lastGroup && prefix);
            }
            boolean nonFinalCut = false;
            for (int i = 0; i < ords.size(); i++) {
                int ord = ords.get(i).intValue();
                if (isCut(ladder, ord, perOrd.get(i)) && !(runEnd != null && runEnd.stageIdx == g.stageIdx
                        && runEnd.pos == g.pos && eq(runEnd.setId, g.setId) && runEnd.ordinal == ord))
                    nonFinalCut = true;
            }

            boolean unchanged = ladder != null && !anyOverride && skippedRows == 0 && extendTotal == 0
                             && overrunTotal == 0 && allOnPlan && ordsCover && !nonFinalCut;

            if (unchanged) {
                Block b = new Block();
                b.unchanged = true;
                b.kind = set.ramp ? RAMP : FIXED;
                fillPlanValues(b, set);
                b.setId = g.setId; b.stageIdx = g.stageIdx; b.pos = g.pos;
                b.t0 = valueRows.get(0).t0; b.t1 = endOf(valueRows);
                for (int i = 0; i < valueRows.size(); i++) {
                    Row r = valueRows.get(i);
                    b.spanMs += r.span();
                    b.clockHeldMs += r.clockHeldMs;
                    if (r.upReq > r.up) b.ceilingLimited = true;
                    if (!r.confirmed) b.confirmed = false;
                }
                b.cycles = b.kind == FIXED ? (int) (b.spanMs / b.cycleMs()) : 0;
                b.steps = b.kind == RAMP ? ladder.size() : 0;
                b.onPlan = true; b.hasPlan = true;
                b.firstOrdinal = valueRows.get(0).ordinal;
                Model.Preset pf = ladder.get(Math.min(Math.max(0, b.firstOrdinal), ladder.size() - 1));
                b.pUp = pf.up; b.pLo = pf.lo; b.pUh = pf.uh; b.pLh = pf.lh; b.pSp = pf.sp;
                // The run's final preset may still have been cut (stopped early): say so,
                // without it being a change.
                if (b.kind == RAMP) b.cutShort = ords.size() < ladder.size();
                else { int at = ords.indexOf(Integer.valueOf(b.firstOrdinal)); b.cutShort = at >= 0 && isCut(ladder, b.firstOrdinal, perOrd.get(at)); }
                b.cutIsRunEnd = b.cutShort;
                b.planDurSec = set.dur;
                b.stageName = stageName; b.setName = setName; b.manual = run.manual;
                out.add(b);
                continue;
            }

            // CHANGED: split. Walk the occurrence's rows in order; HOLD/LOST are skipped over
            // (they neither split nor count), VENT and SKIPPED flush the current builder.
            List<Builder> builders = new ArrayList<Builder>();
            Builder bld = null;
            for (int i = 0; i < g.rows.size(); i++) {
                Row r = g.rows.get(i);
                // REST joins HOLD and LOST here: it neither splits a block nor counts
                // toward one. A rest is a step of the routine that commands nothing, so
                // there are no values for it to contribute and no change for it to mark.
                if (r.kind == HOLD || r.kind == LOST || r.kind == REST) continue;
                // A VENT splits only when it actually ENDED the run. The link-loss auto-stop
                // opens a VENT row six seconds into a dropout, and the run then resumes when
                // the link returns before the fall is ever observed: that vent is a pause the
                // run survived, and treating it as a hard boundary split an untouched set in
                // two. "It ended the run" is asked of the recording itself — a later PLAN or
                // OVERRIDE row for the same preset occurrence is the run carrying on.
                if (r.kind == VENT && !ventEndedRun(run, r)) continue;
                if (r.kind == VENT || r.kind == SKIPPED) { if (bld != null) builders.add(bld); bld = null; continue; }
                boolean on = ladder != null && onPlan(r, ladder);
                if (on) {
                    if (bld != null && bld.seg && (r.ordinal == bld.lastOrd || r.ordinal == bld.lastOrd + 1)) {
                        if (r.ordinal != bld.lastOrd) bld.distinctOrds++;
                        bld.lastOrd = r.ordinal; bld.rows.add(r);
                    } else {
                        if (bld != null) builders.add(bld);
                        bld = new Builder(); bld.seg = true; bld.firstOrd = bld.lastOrd = r.ordinal; bld.distinctOrds = 1; bld.rows.add(r);
                    }
                } else {
                    if (bld != null && !bld.seg && r.sameValues(bld.rows.get(0))) {
                        bld.rows.add(r); bld.lastOrd = r.ordinal;
                    } else {
                        if (bld != null) builders.add(bld);
                        bld = new Builder(); bld.seg = false; bld.firstOrd = bld.lastOrd = r.ordinal; bld.distinctOrds = 1; bld.rows.add(r);
                    }
                }
            }
            if (bld != null) builders.add(bld);

            for (int bi = 0; bi < builders.size(); bi++) {
                Builder bb = builders.get(bi);
                Block b = new Block();
                Row first = bb.rows.get(0), last = bb.rows.get(bb.rows.size() - 1);
                b.setId = g.setId; b.stageIdx = g.stageIdx; b.pos = g.pos;
                b.t0 = first.t0; b.t1 = endOf(bb.rows);
                for (int i = 0; i < bb.rows.size(); i++) {
                    Row r = bb.rows.get(i);
                    b.spanMs += r.span();
                    b.clockHeldMs += r.clockHeldMs;
                    b.extendMs += r.extendMs;
                    if (r.upReq > r.up) b.ceilingLimited = true;
                    if (!r.confirmed) b.confirmed = false;
                    if (r.kind == OVERRIDE) b.hasOverride = true;
                    if (r.kind == PLAN && ladder != null && !onPlan(r, ladder)) b.edited = true;
                }
                b.firstOrdinal = first.ordinal;
                b.hasPlan = ladder != null;
                if (ladder != null) {
                    Model.Preset pf = ladder.get(Math.min(Math.max(0, first.ordinal), ladder.size() - 1));
                    b.pUp = pf.up; b.pLo = pf.lo; b.pUh = pf.uh; b.pLh = pf.lh; b.pSp = pf.sp;
                }
                if (bb.seg && bb.distinctOrds >= 2) {
                    // A ramp block over the steps that started: start = first started step's
                    // planned values, end = LAST started step's planned values (§3).
                    b.kind = RAMP;
                    Model.Preset ps = ladder.get(bb.firstOrd), pe = ladder.get(bb.lastOrd);
                    b.up = ps.up; b.lo = ps.lo; b.uh = ps.uh; b.lh = ps.lh; b.sp = ps.sp;
                    b.up2 = pe.up; b.lo2 = pe.lo; b.uh2 = pe.uh; b.lh2 = pe.lh; b.sp2 = pe.sp;
                    b.steps = bb.distinctOrds;
                    b.cycles = 0;
                    b.onPlan = true;
                    b.cutShort = bb.distinctOrds < ladder.size();
                } else {
                    b.kind = FIXED;
                    b.up = first.up; b.lo = first.lo; b.uh = first.uhReq; b.lh = first.lh; b.sp = first.sp;
                    b.up2 = b.up; b.lo2 = b.lo; b.uh2 = b.uh; b.lh2 = b.lh; b.sp2 = b.sp;
                    b.steps = 0;
                    b.cycles = (int) (b.spanMs / b.cycleMs());
                    // Under one cycle of the set's OWN clock: what a save could make of it.
                    b.tooShort = b.ownSpanMs() < b.cycleMs();
                    b.onPlan = bb.seg;
                    int at = ords.indexOf(Integer.valueOf(last.ordinal));
                    b.cutShort = at >= 0 && isCut(ladder, last.ordinal, perOrd.get(at));
                    b.cutIsRunEnd = b.cutShort && runEnd != null && runEnd.stageIdx == g.stageIdx
                                 && runEnd.pos == g.pos && eq(runEnd.setId, g.setId) && runEnd.ordinal == last.ordinal;
                }
                // Overrun of an edited duration is folded into the block that played it.
                for (int i = 0; i < bb.rows.size(); i++) {
                    int at = ords.indexOf(Integer.valueOf(bb.rows.get(i).ordinal));
                    if (at < 0) continue;
                    long planned = plannedMs(ladder, bb.rows.get(i).ordinal);
                    if (planned <= 0) continue;
                    long over = perOrd.get(at)[0] - (planned + perOrd.get(at)[1] + perOrd.get(at)[3]);
                    if (over >= CUT_SHORT_TOL_MS && bb.rows.get(i) == lastRowOfOrdinal(valueRows, bb.rows.get(i).ordinal)) {
                        b.extendMs += over; b.edited = true;
                    }
                }
                b.skippedSteps = skippedSteps;
                b.cutMidRun = nonFinalCut;
                b.planDurSec = set == null ? 0 : set.dur;
                b.stageName = stageName; b.setName = setName; b.manual = run.manual;
                out.add(b);
            }
        }
        for (int i = 0; i < out.size(); i++) out.get(i).idx = i;
        return out;
    }

    /** Whether any block differs from the plan (§3: any set not UNCHANGED, any skip — an
     *  extension or an edit already makes its set not-unchanged). */
    public static boolean differs(List<Block> blocks) {
        if (blocks == null) return false;
        for (int i = 0; i < blocks.size(); i++) if (!blocks.get(i).unchanged) return true;
        return false;
    }

    /** A stage is SKIPPED when every set of it was skipped (§3) — at least one block in the
     *  stage, all of them skipped placeholders. */
    public static boolean stageSkipped(List<Block> blocks, int stageIdx) {
        boolean any = false;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (b.stageIdx != stageIdx) continue;
            any = true;
            if (!b.skipped) return false;
        }
        return any;
    }

    /* ---------------------------------------------------- what the card / History show */

    /** History-row door (§5): nothing, "save as you ran it ▸", "save a copy as a set ▸",
     *  "saved ▸". Pure so the row and the card cannot disagree. */
    public static final int HIST_NONE = 0, HIST_SAVE_AS_RUN = 1, HIST_SAVE_COPY = 2, HIST_SAVED = 3;

    /** Which door a History row wears — from the Sess ALONE. {@code s.differs} is the
     *  answer {@link #differs} gave at filing time, persisted so the History list never
     *  has to load a recording to label a row (it re-renders on every tap, up to 40 rows).
     *  A saved run shows "saved" whatever else is true; a manual run that ran exactly as
     *  configured keeps "save a copy" forever; an unchanged routine run has no door. */
    public static int historyLabel(Model.Sess s) {
        if (s == null || !s.hasAsRun) return HIST_NONE;
        if (s.savedAt > 0) return HIST_SAVED;
        if (s.differs) return HIST_SAVE_AS_RUN;
        return s.manual ? HIST_SAVE_COPY : HIST_NONE;
    }

    /**
     * The words on that door. A SAVED row says "saved · save more ▸" and opens the CARD, not
     * the Library: one partial save used to close the door forever — savedAt was stamped by
     * ANY commit, the row then only led to the Library and both card entry points returned
     * early — so a run saved a block at a time could never be finished. The Library is one
     * tap away inside the card ("Saved ✓ · … · open ▸").
     */
    public static String historyDoorText(int kind) {
        if (kind == HIST_SAVED) return "saved · save more ▸";
        if (kind == HIST_SAVE_COPY) return "save a copy as a set ▸";
        if (kind == HIST_SAVE_AS_RUN) return "save as you ran it ▸";
        return "";
    }

    /**
     * Task 14 (D4): whether this recording shows a LINK-LOSS AUTO-STOP — at least one VENT
     * row anywhere in it. SessionActivity#asRunPause(VENT) is the one call site that ever
     * opens one, reached only from the link-loss auto-stop path; a normal end of routine and
     * the user's own STOP never open one. This is deliberately NOT the live vent watch's
     * telemetry-confirmed verdict (Session#ventResult / SessionActivity#sessionVentState,
     * "vent confirmed" vs "vent NOT confirmed"): that verdict is never written back to the
     * filed Sess — SessionActivity#fileSession logs sessionVentState and nothing more, and
     * SessionVentUpdate only ever updates the live summary screen's own TextView in place —
     * so it is gone the instant the activity moves off the summary, for the session that just
     * finished as much as for any older one. A reopened History row has no way to know
     * whether ITS closing stop was ever confirmed; whether the recording had to auto-stop on
     * a lost link is the one honest "vent" fact that survives, because it is a fact about the
     * RECORDING rather than about telemetry nobody kept.
     */
    public static boolean hadAutoStopVent(AsRun run) {
        if (run == null) return false;
        for (int i = 0; i < run.rows.size(); i++) if (run.rows.get(i).kind == VENT) return true;
        return false;
    }

    /**
     * Whether there is nothing left to save out of this run: every SAVABLE block already
     * carries the name it was saved as. A run with no savable block at all (every one
     * unchanged, or skipped) is finished by the same rule. This — not "savedAt > 0" — is
     * what collapses the card to its one saved line.
     */
    public static boolean allSaved(SaveDraft d, List<Block> blocks) {
        if (d == null || blocks == null) return true;
        for (int i = 0; i < blocks.size() && i < d.blocks.size(); i++) {
            Block b = blocks.get(i);
            if (b.skipped) continue;
            if (b.unchanged && !d.manual) continue;
            if (d.blocks.get(i).savedAs == null) return false;
        }
        return true;
    }

    /** What the summary shows after a run (§4): nothing, the quiet line, the full card, or
     *  the one-question skip-only card. */
    public static final int OFFER_NONE = 0, OFFER_QUIET = 1, OFFER_CARD = 2, OFFER_SKIP_ONLY = 3;

    /** True when the only thing that differs from the plan is skipped sets — every
     *  non-unchanged block is a skipped placeholder (and there is at least one). */
    public static boolean skipOnly(List<Block> blocks) {
        boolean any = false;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (b.unchanged) continue;
            if (!b.skipped) return false;
            any = true;
        }
        return any;
    }

    /** §4's decision: no difference → nothing; an opted-out routine → nothing (History keeps
     *  the door); under the threshold → the quiet line; over → the card, or the one-question
     *  card when only skips differ. */
    public static int offerKind(List<Block> blocks, Change c, boolean routineOptedOut) {
        if (blocks == null || c == null || !c.differs) return OFFER_NONE;
        if (routineOptedOut) return OFFER_NONE;
        if (!c.overThreshold) return OFFER_QUIET;
        return skipOnly(blocks) ? OFFER_SKIP_ONLY : OFFER_CARD;
    }

    /** The number of sets a draft will create: on, not merged, savable (not skipped; not an
     *  unchanged block of a routine run). */
    public static int setsToSave(SaveDraft d, List<Block> blocks) {
        int n = 0;
        for (int i = 0; i < blocks.size() && i < d.blocks.size(); i++) {
            Block b = blocks.get(i);
            DraftBlock db = d.blocks.get(i);
            if (!db.on || db.mergedInto >= 0 || b.skipped) continue;
            if (b.unchanged && !d.manual) continue;
            n++;
        }
        return n;
    }

    /** True when any drop is ticked in the draft — a stage or a skipped set removed, a
     *  structural change to the routine that is a save even with no set selected. */
    public static boolean structuralChange(SaveDraft d) {
        for (int i = 0; i < d.dropStage.length; i++) if (d.dropStage[i]) return true;
        for (int i = 0; i < d.blocks.size(); i++) if (d.blocks.get(i).drop) return true;
        return false;
    }

    /**
     * Whether the card's primary button does anything (§5) — pure, so the enable rule and
     * its pin cannot drift apart:
     *  - manual / Sets only: at least one set to save;
     *  - Update routine: a set, a structural change (drop), or a RENAME — a non-blank
     *    routine name that differs from the routine's current name is a save of its own;
     *  - New routine: a set or a structural change — never enabled with nothing selected,
     *    where a stray tap would file a plain duplicate of the plan;
     *  - never, in any mode, when the draft would leave the routine with NO STAGES AT ALL
     *    (see {@link #emptiesRoutine}).
     */
    public static boolean saveEnabled(SaveDraft d, List<Block> blocks, Model m) {
        if (emptiesRoutine(d, blocks, m)) return false;
        int n = setsToSave(d, blocks);
        if (d.manual || d.mode == MODE_SETS_ONLY) return n > 0;
        boolean structural = structuralChange(d);
        if (d.mode == MODE_UPDATE) {
            Model.Routine r = m.routine(d.routineId);
            boolean rename = r != null && d.routineName != null
                && d.routineName.trim().length() > 0 && !d.routineName.trim().equals(r.name);
            return n > 0 || structural || rename;
        }
        return n > 0 || structural;
    }

    /**
     * Whether applying this draft would leave the routine with ZERO stages — a run where
     * every stage was skipped and every drop ticked. applyToStages already refuses to emit
     * an INDIVIDUAL empty stage; nothing checked the whole result, so such a draft emptied
     * the routine outright and Today then said "That routine has no usable sets" about a
     * routine the user still believes in. The card disables its primary with a caption
     * rather than letting the tap through, and {@link #commit} refuses the same draft
     * before it touches anything.
     *
     * Only MODE_UPDATE can be judged here: a New routine is spliced from the RUN's
     * snapshot, which a draft alone does not carry — commit checks that one itself.
     */
    public static boolean emptiesRoutine(SaveDraft d, List<Block> blocks, Model m) {
        if (d == null || blocks == null || m == null) return false;
        if (d.manual || d.mode != MODE_UPDATE) return false;
        Model.Routine r = m.routine(d.routineId);
        if (r == null || r.stages.isEmpty()) return false;
        return applyToStages(r.stages, blocks, d, wouldBeIds(d, blocks)).isEmpty();
    }

    /** The per-block "this becomes a new set" decision of {@link #commit}'s step 1, as
     *  markers rather than real ids — so the splice can be simulated without creating
     *  anything. Must stay in step with commit's own filter. */
    private static String[] wouldBeIds(SaveDraft d, List<Block> blocks) {
        String[] newId = new String[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            DraftBlock db = i < d.blocks.size() ? d.blocks.get(i) : null;
            if (db == null || !db.on || db.mergedInto >= 0 || b.skipped) continue;
            if (b.unchanged && !d.manual) continue;
            newId[i] = "would-be-" + i;
        }
        return newId;
    }

    /** Fold a too-short block into `target`, or unfold it when it is already there. Folding
     *  turns the block off (it saves as part of the target); UNFOLDING restores the draft's
     *  own default, {@code !tooShort} — never leaves the block silently off. Pure, so the
     *  tap and the pin cannot disagree. */
    public static void toggleMerge(SaveDraft d, List<Block> blocks, int idx, int target) {
        DraftBlock db = d.blocks.get(idx);
        db.mergedInto = db.mergedInto == target ? -1 : target;
        db.on = db.mergedInto >= 0 ? false : !blocks.get(idx).tooShort;
    }

    /**
     * The "Work will be: …" preview (§5): the names the stage would hold after the draft is
     * applied, in order, or null when that is exactly the plan (every set keeps its id,
     * nothing dropped). `plannedIds` is the stage as the run started (the snapshot's list).
     * A dropped stage returns an empty list. Names: a new set's draft name; a kept id's
     * library name (the snapshot's id when the set is gone).
     */
    public static List<String> stagePreview(List<Block> blocks, SaveDraft d, int stageIdx,
                                            List<String> plannedIds, Model m) {
        List<String> out = new ArrayList<String>();
        if (stageIdx < d.dropStage.length && d.dropStage[stageIdx]) return out;
        boolean changed = false;
        for (int j = 0; j < plannedIds.size(); j++) {
            String setId = plannedIds.get(j);
            boolean dropped = false;
            List<String> ons = new ArrayList<String>();
            for (int k = 0; k < blocks.size() && k < d.blocks.size(); k++) {
                Block b = blocks.get(k);
                if (b.stageIdx != stageIdx || b.pos != j || !eq(b.setId, setId)) continue;
                DraftBlock db = d.blocks.get(k);
                if (b.skipped) { if (db.drop) dropped = true; continue; }
                if (b.unchanged && !d.manual) continue;
                if (db.on && db.mergedInto < 0) ons.add(db.name);
            }
            if (dropped) { changed = true; continue; }
            if (ons.isEmpty()) {
                Model.Set s = m.set(setId);
                out.add(s == null ? setId : s.name);
            } else { changed = true; out.addAll(ons); }
        }
        return changed ? out : null;
    }

    /** The set occurrences of one planned stage position that RAN — in block order. */
    public static List<Block> blocksAt(List<Block> blocks, int stageIdx, int pos) {
        List<Block> out = new ArrayList<Block>();
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (b.stageIdx == stageIdx && b.pos == pos) out.add(b);
        }
        return out;
    }

    /* ------------------------------------------------------------- block helpers */

    /**
     * WHAT WAS PLANNED for this occurrence: the run's own snapshot of the set at (stage,
     * position), because that is a fact about the run and cannot be edited from under it.
     * Falls back to the Library's set only for a recording that carries no set values (the
     * shape every recording had before this existed), and to the manual run's own ephemeral
     * set for a manual run.
     */
    private static Model.Set plannedSet(AsRun run, Model m, Group g) {
        Model.Set s = m.set(g.setId);
        // A set DELETED from the library since the run still has no plan — see blocks()'s
        // own note on that shape. The snapshot answers the different question: the set is
        // still there, and this is what it SAID when the run started.
        if (s != null) {
            if (run.snap != null && g.stageIdx >= 0 && g.stageIdx < run.snap.stages.size()) {
                Model.AsRunSnapshot.StageSnap st = run.snap.stages.get(g.stageIdx);
                Model.AsRunSnapshot.SetSnap ss = st.setAt(g.pos);
                if (ss != null && eq(ss.id, g.setId)) return ss.toSet();
            }
            return s;
        }
        if (run.manual) {
            if (run.manualSet != null) return run.manualSet;
            // No record of the started manual set: the first PLAN row is the closest thing
            // to "what was planned" a fixed manual cycle has.
            for (int i = 0; i < g.rows.size(); i++) {
                Row r = g.rows.get(i);
                if (r.kind == PLAN) {
                    long span = 0;
                    for (int j = 0; j < g.rows.size(); j++) if (g.rows.get(j).hasValues()) span += g.rows.get(j).span();
                    return Model.Set.fixed(g.setId, "Manual run", r.up, r.lo, r.uhReq, r.lh, r.sp,
                                           (int) Math.max(30L, span / 1000L));
                }
            }
        }
        return null;
    }

    /** The set's ladder after the ceiling, computed on a copy so the library is not
     *  mutated by a derivation. */
    private static List<Model.Preset> ladderOf(Model.Set s, int ceilKpa) {
        Model.Set c = s.copy(s.id);
        c.name = s.name; c.fromRun = s.fromRun;
        c.clamp(ceilKpa);
        return c.ladder();
    }

    private static boolean onPlan(Row r, List<Model.Preset> ladder) {
        if (r.ordinal < 0 || r.ordinal >= ladder.size()) return false;
        return r.sameValues(ladder.get(r.ordinal));
    }

    private static long plannedMs(List<Model.Preset> ladder, int ord) {
        if (ladder == null || ord < 0 || ord >= ladder.size()) return 0L;
        return ladder.get(ord).durMs;
    }

    /**
     * delivered + PAUSED + tolerance < planned + extension + HELD.
     *
     * The paused term is the fix for "a Bluetooth dropout is recorded as you cut Work
     * short": delivery stops for the length of a LOST / VENT / HOLD row but the plan's
     * clock does not, so a preset that spent four of its two minutes waiting for the link
     * has delivered 1:56 against a 2:00 plan and is not a cut at all. A real skip still
     * is one: there is no paused time to make up the difference.
     *
     * The held term (C7) is the at-pressure clock's: a set held a minute and skipped at
     * 2:30 delivered 1:30 of its own two minutes - cut short, though it ran longer than 2:00.
     */
    private static boolean isCut(List<Model.Preset> ladder, int ord, long[] deliveredExtendPausedHeld) {
        long planned = plannedMs(ladder, ord);
        if (planned <= 0) return false;
        long[] d = deliveredExtendPausedHeld;
        long paused = d.length > 2 ? d[2] : 0L;
        long held = d.length > 3 ? d[3] : 0L;
        return d[0] + paused + CUT_SHORT_TOL_MS < planned + d[1] + held;
    }

    /**
     * Whether this VENT row is the END OF THE RUN rather than a pause the run survived:
     * nothing was put back on the pump after it, anywhere in the recording.
     *
     * Deliberately asked of the WHOLE recording, not of the vent's own preset occurrence: a
     * vent six seconds into a dropout at ramp step 2, with the link back and step 3 playing
     * after it, is a run that carried on — and asking only about step 2 would have made the
     * vent a boundary INSIDE the ramp, splitting a block that never changed.
     */
    private static boolean ventEndedRun(AsRun run, Row v) {
        boolean valuesAfter = false;
        for (int i = run.rows.size() - 1; i >= 0; i--) {
            Row r = run.rows.get(i);
            if (r == v) return !valuesAfter;
            if (r.hasValues()) valuesAfter = true;
        }
        return true;
    }

    private static Row lastRowOfOrdinal(List<Row> valueRows, int ord) {
        for (int i = valueRows.size() - 1; i >= 0; i--) if (valueRows.get(i).ordinal == ord) return valueRows.get(i);
        return null;
    }

    private static long endOf(List<Row> rs) {
        long t = 0;
        for (int i = 0; i < rs.size(); i++) { Row r = rs.get(i); long e = r.t1 < 0 ? r.t0 : r.t1; if (e > t) t = e; }
        return t;
    }

    private static void fillPlanValues(Block b, Model.Set s) {
        if (s == null) return;
        b.up = s.up; b.lo = s.lo; b.uh = s.uh; b.lh = s.lh; b.sp = s.sp;
        b.up2 = s.endUp(); b.lo2 = s.endLo(); b.uh2 = s.endUh(); b.lh2 = s.endLh(); b.sp2 = s.endSp();
        b.steps = s.ramp ? s.steps : 0;
    }

    private static String stageName(AsRun run, Model m, int stageIdx) {
        if (run.snap != null && stageIdx >= 0 && stageIdx < run.snap.stages.size())
            return run.snap.stages.get(stageIdx).name;
        Model.Routine r = m.routine(run.routineId);
        if (r != null && stageIdx >= 0 && stageIdx < r.stages.size()) {
            String n = r.stages.get(stageIdx).name;
            return n == null ? "" : n;
        }
        return run.manual ? "Manual" : "";
    }

    /* ================================================================= §4 threshold */

    /** "+30 s" — the one extension the run screen offers; any recorded extension is at
     *  least this, and at least this is always over the threshold (§4). */
    public static final long EXTEND_OVER_MS = 30000L;
    /** An upper-hold change has to move at least this many seconds AS WELL AS the relative
     *  percentage (§4) — 20 % of a three-second hold is not a change worth asking about. */
    public static final int HOLD_MIN_DELTA_S = 5;

    /** How one block differs from its planned step. */
    public static final class BlockChange {
        public int blockIdx, stageIdx;
        public String stageName = "";
        /** Signed deltas, block minus plan: kPa, kPa, s, s, percentage points. */
        public int dUp, dLo, dUh, dLh, dSp;
        public long extendMs;
        public boolean skipped, edited, cutShort, over;
        /** A pressure / power / hold value actually moved (or the plan is unknown). */
        public boolean nudge;
        /** Why it is over the threshold, for the card — "", or e.g. "pressure". */
        public String why = "";
    }

    /** What {@link #evaluate} decides. */
    public static final class Change {
        public boolean differs, overThreshold;
        /** The quiet line (§4) — "" when nothing differs. Unit words through Fmt only. */
        public String quietText = "";
        public BlockChange[] changes = new BlockChange[0];
    }

    /**
     * The threshold (§4). Each non-unchanged block is compared with the PLANNED step it
     * started in — never with the previous block:
     *  - pressure: |Δup| or |Δlo| ≥ offerPressureKpa, and ≠ 0 (so a threshold of 0 means
     *    "any change at all asks" rather than "everything asks");
     *  - power: |Δsp| ≥ offerHoldSpeedPct percentage points;
     *  - holds: |Δ| relative to the planned hold ≥ offerHoldSpeedPct AND ≥ 5 s (the spec names
     *    the upper hold; the lower is held to the same bar, since nothing else speaks for it);
     *  - a skipped set or a skipped step: over when offerOnSkip;
     *  - an extension (≥ 30 s — every recorded one is) or an edited upcoming preset: always;
     *  - a block whose set no longer exists: always (nothing to compare, the person decides).
     * The quiet line follows §4's examples: "You nudged Work by 0.3 inHg" / "You nudged Work
     * twice, Cool once" / "You extended Work by 0:30" / manual "You changed it twice mid-run";
     * a set adjusted to its own planned values reads "back to planned".
     */
    public static Change evaluate(List<Block> blocks, Model m) {
        Change c = new Change();
        if (blocks == null || blocks.isEmpty()) return c;
        List<BlockChange> list = new ArrayList<BlockChange>();
        boolean manual = blocks.get(0).manual;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (b.unchanged) continue;
            BlockChange bc = new BlockChange();
            bc.blockIdx = b.idx; bc.stageIdx = b.stageIdx; bc.stageName = b.stageName;
            bc.skipped = b.skipped; bc.edited = b.edited; bc.extendMs = b.extendMs; bc.cutShort = b.cutMidRun;
            if (b.skipped) {
                bc.over = m.offerOnSkip; bc.why = bc.over ? "skipped" : "";
                list.add(bc);
                continue;
            }
            if (b.hasPlan) {
                bc.dUp = b.up - b.pUp; bc.dLo = b.lo - b.pLo; bc.dUh = b.uh - b.pUh;
                bc.dLh = b.lh - b.pLh; bc.dSp = b.sp - b.pSp;
            }
            bc.nudge = !b.hasPlan || bc.dUp != 0 || bc.dLo != 0 || bc.dUh != 0 || bc.dLh != 0 || bc.dSp != 0 || b.edited;
            String why = "";
            if (!b.hasPlan) why = "no plan";
            else if (b.edited) why = "edited";
            else if (b.extendMs >= EXTEND_OVER_MS) why = "extended";
            else if (pressureOver(bc.dUp, m.offerPressureKpa) || pressureOver(bc.dLo, m.offerPressureKpa)) why = "pressure";
            else if (Math.abs(bc.dSp) >= m.offerHoldSpeedPct) why = "power";
            else if (holdOver(bc.dUh, b.pUh, m.offerHoldSpeedPct) || holdOver(bc.dLh, b.pLh, m.offerHoldSpeedPct)) why = "hold";
            else if (b.skippedSteps > 0 && m.offerOnSkip) why = "skipped";
            bc.over = why.length() > 0;
            bc.why = why;
            list.add(bc);
        }
        c.changes = list.toArray(new BlockChange[list.size()]);
        c.differs = differs(blocks);
        for (int i = 0; i < c.changes.length; i++) if (c.changes[i].over) c.overThreshold = true;
        c.quietText = quietText(blocks, c.changes, manual);
        return c;
    }

    private static boolean pressureOver(int deltaKpa, double thresholdKpa) {
        if (deltaKpa == 0) return false;
        return Math.abs((double) deltaKpa) >= thresholdKpa;
    }

    private static boolean holdOver(int deltaS, int plannedS, int pct) {
        int d = Math.abs(deltaS);
        if (d < HOLD_MIN_DELTA_S) return false;
        if (plannedS <= 0) return true;
        return d * 100 >= pct * plannedS;
    }

    /** The quiet line, §4. Composed of clauses per stage in plan order: nudges ("by <size>"
     *  for a single one; "twice", "once" for several), extensions, skips, cut-shorts. */
    private static String quietText(List<Block> blocks, BlockChange[] changes, boolean manual) {
        if (changes.length == 0) return "";
        if (manual) {
            int valueBlocks = 0;
            long extend = 0; boolean cut = false;
            for (int i = 0; i < blocks.size(); i++) {
                Block b = blocks.get(i);
                if (!b.skipped) valueBlocks++;
            }
            for (int i = 0; i < changes.length; i++) { extend += changes[i].extendMs; if (changes[i].cutShort) cut = true; }
            int n = valueBlocks - 1;
            // times(): "once" / "twice" / "3×" — the same words every other count in
            // this file uses. "You changed it 1× mid-run" was the only place that counted
            // like a machine.
            if (n >= 1) return "You changed it " + times(n) + " mid-run";
            if (extend > 0) return "You extended it by " + Model.Fmt.t(extend / 1000L);
            if (cut) return "You cut it short";
            return "You changed it mid-run";
        }
        // Per stage, in order of first appearance.
        List<Integer> stages = new ArrayList<Integer>();
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < changes.length; i++) {
            Integer s = Integer.valueOf(changes[i].stageIdx);
            if (!stages.contains(s)) { stages.add(s); names.add(changes[i].stageName); }
        }
        int[] nudges = new int[stages.size()];
        long[] extend = new long[stages.size()];
        boolean[] skip = new boolean[stages.size()], cut = new boolean[stages.size()], touched = new boolean[stages.size()];
        BlockChange only = null; int totalNudges = 0;
        for (int i = 0; i < changes.length; i++) {
            BlockChange bc = changes[i];
            int at = stages.indexOf(Integer.valueOf(bc.stageIdx));
            touched[at] = true;
            if (bc.skipped) { skip[at] = true; continue; }
            if (bc.nudge) { nudges[at]++; totalNudges++; only = bc; }
            extend[at] += bc.extendMs;
            if (bc.cutShort) cut[at] = true;
            Block b = blocks.get(bc.blockIdx);
            if (b.skippedSteps > 0) skip[at] = true;
        }
        List<String> clauses = new ArrayList<String>();
        if (totalNudges == 1) {
            clauses.add("nudged " + only.stageName + " by " + nudgeSize(only));
        } else if (totalNudges > 1) {
            StringBuilder sb = new StringBuilder("nudged ");
            boolean first = true;
            for (int i = 0; i < stages.size(); i++) {
                if (nudges[i] == 0) continue;
                if (!first) sb.append(", ");
                first = false;
                sb.append(names.get(i)).append(' ').append(times(nudges[i]));
            }
            clauses.add(sb.toString());
        }
        for (int i = 0; i < stages.size(); i++)
            if (extend[i] > 0) clauses.add("extended " + names.get(i) + " by " + Model.Fmt.t(extend[i] / 1000L));
        for (int i = 0; i < stages.size(); i++)
            if (skip[i]) clauses.add((stageSkipped(blocks, stages.get(i).intValue()) ? "skipped " : "skipped part of ") + names.get(i));
        for (int i = 0; i < stages.size(); i++)
            if (cut[i]) clauses.add("cut " + names.get(i) + " short");
        // A stage that was touched but moved nothing: adjusted back to its own plan.
        for (int i = 0; i < stages.size(); i++)
            if (touched[i] && nudges[i] == 0 && extend[i] == 0 && !skip[i] && !cut[i])
                clauses.add("nudged " + names.get(i) + ", back to planned");
        if (clauses.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("You ");
        for (int i = 0; i < clauses.size(); i++) { if (i > 0) sb.append(", "); sb.append(clauses.get(i)); }
        return sb.toString();
    }

    private static String times(int n) { return n == 1 ? "once" : (n == 2 ? "twice" : n + "×"); }

    /** The size of a single nudge: the larger pressure move through Fmt.mag, else the power
     *  in percentage points, else the hold in m:ss. */
    private static String nudgeSize(BlockChange bc) {
        int dp = Math.max(Math.abs(bc.dUp), Math.abs(bc.dLo));
        if (dp != 0) return Model.Fmt.mag(dp);
        if (bc.dSp != 0) return Math.abs(bc.dSp) + "%";
        int dh = Math.max(Math.abs(bc.dUh), Math.abs(bc.dLh));
        if (dh != 0) return Model.Fmt.t(dh);
        return "a little";
    }

    /* ============================================================ §5 draft + commit */

    /** Save modes. SETS_ONLY never touches a routine; UPDATE re-points the run's routine
     *  (routine runs only); ROUTINE creates a NEW routine — the "New routine" choice of a
     *  routine run, the "One routine" choice of a manual run. */
    public static final int MODE_SETS_ONLY = 0, MODE_UPDATE = 1, MODE_ROUTINE = 2;

    /** One block's line on the card. */
    public static final class DraftBlock {
        /** Saved as a set when the draft is committed. Meaningless for an unchanged or
         *  skipped block (never saved). */
        public boolean on;
        public String name = "";
        /** Index of the neighbour a too-short block is folded into (its span extends the
         *  neighbour's), or -1. A merged block is not saved on its own. */
        public int mergedInto = -1;
        /** SKIPPED placeholders only: "drop <set> from <stage>" (§5), unchecked by default. */
        public boolean drop;
        /** The name of a set this block was ALREADY saved as, from an earlier commit of the
         *  same run (Set#fromRun matches and the values match), or null. Such a block starts
         *  off (§5 reopen). */
        public String savedAs;
    }

    /** What the card edits and {@link #commit} applies. */
    public static final class SaveDraft {
        public int mode = MODE_SETS_ONLY;
        public String routineName = "";
        public final List<DraftBlock> blocks = new ArrayList<DraftBlock>();
        /** Per stage of the planned routine: remove the stage (a SKIPPED stage, §5). */
        public boolean[] dropStage = new boolean[0];
        public boolean manual;
        public String routineId = "";
        /** Whether UPDATE is on offer at all: a completed routine run whose routine is still
         *  exactly as it was (§5). When false, {@link #cantUpdateWhy} says why. */
        public boolean canUpdate;
        public String cantUpdateWhy = "";
        /** The routine still exists (false → only ROUTINE / SETS_ONLY). */
        public boolean routineExists;
    }

    /**
     * A draft for these blocks (§5): suggested names, on-by-default, mode, routine name.
     *  - routine run, changed block: on; name "<Set> · adjusted", or "<Set> · in" for the
     *    planned part (a ramp's steps that started, or a fixed set's unchanged stretch around
     *    an adjustment); unchanged blocks off with the set's own name; skipped placeholders
     *    off, drop unchecked; too-short blocks off; already-saved blocks off, savedAs set;
     *  - manual run: every value block on — an unchanged manual block is still a copy worth
     *    saving ("save a copy as a set") — named "Manual · block N";
     *  - duplicate suggestions, among themselves and against the library, get " (2)", " (3)";
     *  - never a pressure number in a name;
     *  - mode: UPDATE when the routine can be updated, else SETS_ONLY; manual: SETS_ONLY;
     *  - routine name: UPDATE → the routine's current name (a rename); otherwise
     *    {@link #suggestedRoutineName}.
     * `s` is the filed session (null before filing: treated as completed and unsaved).
     */
    public static SaveDraft draft(List<Block> blocks, Model m, Model.Sess s) {
        SaveDraft d = new SaveDraft();
        boolean manual = !blocks.isEmpty() && blocks.get(0).manual;
        d.manual = manual;
        d.routineId = s != null && s.routineId != null ? s.routineId : "";
        Model.Routine r = manual ? null : m.routine(d.routineId);
        d.routineExists = r != null;
        if (manual) { d.cantUpdateWhy = "a manual run has no routine to update"; }
        else if (r == null) { d.cantUpdateWhy = "this routine no longer exists"; }
        else if (s != null && !s.completed) { d.cantUpdateWhy = "the run was stopped early"; }
        else if (s != null && s.asRunSnapshot == null) { d.cantUpdateWhy = "this run was recorded without its routine"; }
        else if (s != null && !snapshotMatches(s.asRunSnapshot, m)) { d.cantUpdateWhy = "the routine was edited since this run"; }
        else d.canUpdate = true;
        d.mode = d.canUpdate ? MODE_UPDATE : MODE_SETS_ONLY;
        d.routineName = suggestedRoutineName(d.mode, s, m);
        int stageCount = 0;
        if (s != null && s.asRunSnapshot != null) stageCount = s.asRunSnapshot.stages.size();
        for (int i = 0; i < blocks.size(); i++) stageCount = Math.max(stageCount, blocks.get(i).stageIdx + 1);
        d.dropStage = new boolean[stageCount];

        List<String> taken = new ArrayList<String>();
        for (int i = 0; i < m.sets.size(); i++) taken.add(m.sets.get(i).name);
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            DraftBlock db = new DraftBlock();
            if (b.skipped || (b.unchanged && !manual)) {
                db.name = b.setName;
                db.on = false;
                d.blocks.add(db);
                continue;
            }
            String base;
            if (manual) base = "Manual · block " + (i + 1);
            // A block with no plan on a routine run is an occurrence whose set was deleted
            // from the library: there is nothing it was "adjusted" from, and its setName is
            // the stage it ran in (see blocks()).
            else if (!b.hasPlan) base = (b.setName.length() == 0 ? "Set" : b.setName) + " · as run";
            else base = (b.setName.length() == 0 ? "Set" : b.setName) + (isInPart(blocks, b) ? " · in" : " · adjusted");
            db.name = unique(base, taken);
            taken.add(db.name);
            db.on = !b.tooShort;
            db.savedAs = alreadySavedAs(b, m, s);
            if (db.savedAs != null) db.on = false;
            d.blocks.add(db);
        }
        return d;
    }

    /** The routine name the card prefills for a mode (§5): UPDATE → the current name (a
     *  rename); ROUTINE → "<name> (as run)" for a routine run, "Manual run" for a manual
     *  one, made unique among routines; SETS_ONLY → "". */
    public static String suggestedRoutineName(int mode, Model.Sess s, Model m) {
        if (mode == MODE_SETS_ONLY) return "";
        String base = s == null || s.routineName == null ? "" : s.routineName;
        Model.Routine r = s == null || s.routineId == null ? null : m.routine(s.routineId);
        if (mode == MODE_UPDATE) return r == null ? base : (r.name == null ? "" : r.name);
        List<String> taken = new ArrayList<String>();
        for (int i = 0; i < m.routines.size(); i++) taken.add(m.routines.get(i).name);
        if (s != null && s.manual) return unique(base.length() == 0 ? "Manual run" : base, taken);
        return unique((base.length() == 0 ? "Routine" : base) + " (as run)", taken);
    }

    /** "<Set> · in" (§5) is the PLANNED part of a set that was split around an adjustment:
     *  a ramp's steps that started, or a fixed set's planned stretch beside another block of
     *  the same occurrence. A planned-valued block standing alone (a set extended, or cut
     *  short and nothing else) is an adjustment of its length — "· adjusted". */
    private static boolean isInPart(List<Block> blocks, Block b) {
        if (!b.onPlan) return false;
        if (b.kind == RAMP) return true;
        for (int i = 0; i < blocks.size(); i++) {
            Block o = blocks.get(i);
            if (o == b || o.skipped) continue;
            if (o.stageIdx == b.stageIdx && o.pos == b.pos && eq(o.setId, b.setId)) return true;
        }
        return false;
    }

    /** base, or base " (2)", " (3)", … — the first not in `taken`. */
    private static String unique(String base, List<String> taken) {
        if (!taken.contains(base)) return base;
        for (int n = 2; ; n++) {
            String cand = base + " (" + n + ")";
            if (!taken.contains(cand)) return cand;
        }
    }

    /**
     * A set of the library whose fromRun is this session's ts and whose values are this
     * block's - the block was saved before. Null when not.
     *
     * THE SAVED SET WAS CLAMPED ON ITS WAY IN. setFromBlock ends with Set#clamp(ceilKpa),
     * so a block run at 40 kPa and saved is stored at 40 - and if the ceiling is lowered
     * afterwards, nothing changes about the stored set, but an EXACT comparison against the
     * block stopped matching and the card offered to save the very same block a second
     * time, producing a duplicate library row per visit. A clamp can only ever move a
     * pressure DOWN, so the pressures are compared as bounds and everything a clamp does
     * not touch is still compared exactly.
     */
    private static String alreadySavedAs(Block b, Model m, Model.Sess s) {
        if (s == null || s.savedAt <= 0) return null;
        for (int i = 0; i < m.sets.size(); i++) {
            Model.Set x = m.sets.get(i);
            if (x.fromRun != s.ts) continue;
            boolean ramp = b.kind == RAMP;
            if (x.ramp != ramp) continue;
            if (x.up > b.up || x.lo > b.lo) continue;
            if (x.uh != b.uh || x.lh != b.lh || x.sp != b.sp) continue;
            if (ramp && (x.up2 > b.up2 || x.lo2 > b.lo2)) continue;
            if (ramp && (x.uh2 != b.uh2 || x.lh2 != b.lh2 || x.sp2 != b.sp2 || x.steps != b.steps)) continue;
            return x.name;
        }
        return null;
    }

    /**
     * Where a too-short block may be folded (§3): a FIXED neighbour (the previous first, then
     * the next) of the SAME set occurrence, itself savable, that differs from it only by
     * sub-threshold nudges. Returns the neighbour's index or -1. Never across set boundaries,
     * never into an unchanged or skipped block, never when the values differ by more than
     * the threshold — a short block that is a genuinely different thing stays its own.
     */
    public static int mergeTarget(List<Block> blocks, int idx, Model m) {
        if (idx < 0 || idx >= blocks.size()) return -1;
        Block b = blocks.get(idx);
        if (!b.tooShort || b.skipped) return -1;
        int[] cands = { idx - 1, idx + 1 };
        for (int c = 0; c < cands.length; c++) {
            int j = cands[c];
            if (j < 0 || j >= blocks.size()) continue;
            Block n = blocks.get(j);
            if (n.stageIdx != b.stageIdx || n.pos != b.pos || !eq(n.setId, b.setId)) continue;
            if (n.kind != FIXED || n.tooShort || n.skipped || n.unchanged) continue;
            if (pressureOver(n.up - b.up, m.offerPressureKpa) || pressureOver(n.lo - b.lo, m.offerPressureKpa)) continue;
            if (Math.abs(n.sp - b.sp) >= m.offerHoldSpeedPct) continue;
            if (holdOver(n.uh - b.uh, b.uh, m.offerHoldSpeedPct) || holdOver(n.lh - b.lh, b.lh, m.offerHoldSpeedPct)) continue;
            return j;
        }
        return -1;
    }

    /** Whether the routine a snapshot was taken of still stands exactly as it was (§5) —
     *  its stages, its set ids AND every one of those sets' values. A null snapshot never
     *  matches: there is nothing to compare against. */
    public static boolean snapshotMatches(Model.AsRunSnapshot snap, Model m) {
        if (snap == null || m == null) return false;
        return snap.matches(m.routine(snap.routineId), m);
    }

    /** What {@link #commit} did. */
    public static final class Commit {
        public final List<String> createdSetIds = new ArrayList<String>();
        /** The routine updated or created, or null for SETS_ONLY. */
        public String routineId;
        public boolean routineCreated;
    }

    /**
     * Applies a draft (§5). In order:
     *  - UPDATE only: the routine must still exist and match the run's snapshot, else
     *    IllegalStateException("edited") BEFORE anything is touched;
     *  - every on, savable block (not unchanged on a routine run, not skipped, not merged into
     *    a neighbour) becomes a new set under Model#newSetId: the draft's name, the block's
     *    values, Set#fromRun = the run's ts, duration from the block (FIXED: whole cycles,
     *    merged neighbours' spans added; RAMP: steps and delivered span), through Set#clamp;
     *  - UPDATE: each stage's list is rebuilt in position order — an unchanged set, or a changed
     *    set all of whose blocks are off, keeps its original id; a changed set with blocks on
     *    is REPLACED at its position by those blocks' new sets, in order; a skipped set with
     *    "drop" checked is removed; a stage with dropStage set, or left empty, is removed
     *    (never an empty stage); a non-empty draft routineName renames the routine;
     *  - ROUTINE, routine run: a NEW routine with the structure the run started with (the
     *    snapshot) and the same changes applied, named from the draft;
     *  - ROUTINE, manual run: a NEW routine with one stage "Work" holding the on-blocks' new
     *    sets in order;
     *  - SETS_ONLY: the routine is not touched.
     */
    public static Commit commit(SaveDraft d, List<Block> blocks, AsRun run, Model m) {
        Commit out = new Commit();
        if (d == null || blocks == null || run == null || m == null) return out;
        Model.Routine target = null;
        if (d.mode == MODE_UPDATE) {
            target = m.routine(run.routineId);
            if (target == null || run.snap == null || !snapshotMatches(run.snap, m))
                throw new IllegalStateException("edited");
            // A draft that would leave the routine with no stages at all is refused BEFORE
            // anything is created — the card's primary is already disabled for it
            // (saveEnabled), so reaching here means the draft changed under us.
            if (emptiesRoutine(d, blocks, m)) throw new IllegalStateException("empty");
        }
        if (d.mode == MODE_ROUTINE && !run.manual) {
            // Same rule for a NEW routine, whose stages come from the run's snapshot: never
            // file a routine with nothing in it.
            if (applyToStages(snapStages(run, m), blocks, d, wouldBeIds(d, blocks)).isEmpty())
                throw new IllegalStateException("empty");
        }
        // 1. sets
        String[] newId = new String[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            DraftBlock db = i < d.blocks.size() ? d.blocks.get(i) : null;
            if (db == null || !db.on || db.mergedInto >= 0 || b.skipped) continue;
            if (b.unchanged && !run.manual) continue;
            long extra = 0;
            // A folded neighbour adds its length on the set's own clock, like the block (C7).
            for (int j = 0; j < blocks.size() && j < d.blocks.size(); j++)
                if (d.blocks.get(j).mergedInto == i) extra += blocks.get(j).ownSpanMs();
            // The RUN's ceiling, never above the one in force now (saveCeilKpa): a lowered
            // ceiling is announced on the card by ceilingNowCaption rather than applied in
            // silence, and a raised one never re-inflates what the run actually delivered.
            Model.Set s = setFromBlock(b, db.name, extra, run.ts, m.newSetId(), saveCeilKpa(run, m));
            m.sets.add(s);
            out.createdSetIds.add(s.id);
            newId[i] = s.id;
        }
        // 2. routine
        if (d.mode == MODE_UPDATE) {
            List<Model.Stage> rebuilt = applyToStages(target.stages, blocks, d, newId);
            target.stages.clear();
            target.stages.addAll(rebuilt);
            if (d.routineName != null && d.routineName.trim().length() > 0) target.name = d.routineName.trim();
            out.routineId = target.id;
            out.routineCreated = false;
        } else if (d.mode == MODE_ROUTINE) {
            String name = d.routineName == null || d.routineName.trim().length() == 0
                        ? suggestedRoutineName(MODE_ROUTINE, sessLike(run), m) : d.routineName.trim();
            Model.Routine nr = m.newRoutine(m.newRoutineId(), name);
            if (run.manual) {
                List<String> ids = new ArrayList<String>();
                for (int i = 0; i < newId.length; i++) if (newId[i] != null) ids.add(newId[i]);
                nr.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, ids.toArray(new String[ids.size()])));
            } else {
                // The structure the run STARTED with — the snapshot — not whatever the routine
                // has become since; colours borrowed from the current routine where it still
                // lines up, assessment copied like Routine#copy does.
                Model.Routine cur = m.routine(run.routineId);
                List<Model.Stage> src = snapStages(run, m);
                if (cur != null && cur.assess != null) {
                    Model.Assess a = new Model.Assess();
                    a.on = cur.assess.on; a.kpa = cur.assess.kpa; a.sp = cur.assess.sp;
                    a.dur = cur.assess.dur; a.when = cur.assess.when;
                    nr.assess = a;
                }
                nr.stages.addAll(applyToStages(src, blocks, d, newId));
            }
            m.routines.add(nr);
            out.routineId = nr.id;
            out.routineCreated = true;
        }
        return out;
    }

    /** The structure a NEW routine is spliced from: the one the run STARTED with (the
     *  snapshot), falling back to the routine as it stands now, colours borrowed from the
     *  current routine where they still line up. */
    private static List<Model.Stage> snapStages(AsRun run, Model m) {
        Model.Routine cur = m.routine(run.routineId);
        List<Model.Stage> src = new ArrayList<Model.Stage>();
        if (run.snap != null) {
            for (int i = 0; i < run.snap.stages.size(); i++) {
                Model.AsRunSnapshot.StageSnap ss = run.snap.stages.get(i);
                int colour = cur != null && i < cur.stages.size() ? cur.stages.get(i).colour : Model.STAGE_WORK;
                src.add(Model.Stage.of(ss.name, colour, ss.setIds.toArray(new String[ss.setIds.size()])));
            }
        } else if (cur != null) {
            for (int i = 0; i < cur.stages.size(); i++) {
                Model.Stage st = cur.stages.get(i);
                src.add(Model.Stage.of(st.name, st.colour, st.setIds.toArray(new String[st.setIds.size()])));
            }
        }
        return src;
    }

    /** The splice (§5): stages rebuilt with the draft applied; see {@link #commit}. */
    private static List<Model.Stage> applyToStages(List<Model.Stage> stages, List<Block> blocks, SaveDraft d, String[] newId) {
        List<Model.Stage> out = new ArrayList<Model.Stage>();
        for (int i = 0; i < stages.size(); i++) {
            if (i < d.dropStage.length && d.dropStage[i]) continue;
            Model.Stage st = stages.get(i);
            List<String> ids = new ArrayList<String>();
            for (int j = 0; j < st.setIds.size(); j++) {
                String setId = st.setIds.get(j);
                boolean skippedDropped = false;
                List<String> ons = new ArrayList<String>();
                for (int k = 0; k < blocks.size(); k++) {
                    Block b = blocks.get(k);
                    if (b.stageIdx != i || b.pos != j || !eq(b.setId, setId)) continue;
                    if (b.skipped) {
                        if (k < d.blocks.size() && d.blocks.get(k).drop) skippedDropped = true;
                        continue;
                    }
                    if (newId[k] != null) ons.add(newId[k]);
                }
                if (skippedDropped) continue;
                if (ons.isEmpty()) ids.add(setId);      // unchanged, never ran, or all off: keeps its id
                else ids.addAll(ons);
            }
            if (ids.isEmpty()) continue;                // never leave an empty stage behind
            out.add(Model.Stage.of(st.name, st.colour, ids.toArray(new String[ids.size()])));
        }
        return out;
    }

    private static Model.Set setFromBlock(Block b, String name, long extraSpanMs, long fromRun, String id, int ceilKpa) {
        // An unchanged block (a manual cycle saved as a copy) is the set as configured, its
        // own duration included; a changed block is what was delivered.
        int dur = b.unchanged && b.planDurSec > 0 ? b.planDurSec : b.saveDurSec(extraSpanMs);
        Model.Set s;
        if (b.kind == RAMP)
            s = Model.Set.ramp(id, name, b.up, b.lo, b.uh, b.lh, b.sp, b.up2, b.lo2, b.uh2, b.lh2, b.sp2, b.steps, dur);
        else
            s = Model.Set.fixed(id, name, b.up, b.lo, b.uh, b.lh, b.sp, dur);
        s.fromRun = fromRun;
        s.clamp(ceilKpa);
        return s;
    }

    /** Enough of a Sess for {@link #suggestedRoutineName} when commit has only the run. */
    private static Model.Sess sessLike(AsRun run) {
        Model.Sess s = new Model.Sess();
        s.routineId = run.routineId; s.routineName = run.routineName; s.manual = run.manual;
        return s;
    }

    /* ================================== item 11: one-screen "Save changes" ======== */

    /**
     * ONE SET OCCURRENCE of the run — (stageIdx, pos) — collapsed to its FINAL values,
     * for the one-screen "Save changes" diff. This is the aggregation the owner asked
     * for in item 11: the user thinks in sets, not in the timeline fragments blocks()
     * produces, so the "· in" part of a split set disappears here and only the values
     * the set ENDED on are offered. Pure derivation over {@link #blocks}'s output;
     * blocks() itself is untouched — it stays the recording truth.
     */
    public static final class SetChange {
        public int stageIdx, pos;
        public String setId = "";
        public String stageName = "", setName = "";
        /** Every preset of the occurrence was skipped — the "remove it" row. */
        public boolean skipped;
        /** The occurrence differs from its plan (values, length, an edit, a partial
         *  skip) — the rows the diff shows with a ✓. */
        public boolean changed;
        public boolean hasPlan;
        /** The planned step's values (the first step for a ramp). */
        public int pUp, pLo, pUh, pLh, pSp;
        /** The FINAL ran values: the last off-plan value block of the occurrence, or
         *  the planned values when every block stayed on plan (an extension, a cut). */
        public int up, lo, uh, lh, sp;
        /** Seconds across the whole occurrence on the set's OWN clock — what a save carries:
         *  delivered, less the at-pressure clock's held time (C7), which is how the set was
         *  timed and never lengthens what is saved. */
        public int saveDurSec;
        public int planDurSec;
        public boolean manual;
        /**
         * ITEM 11b (owner pick 3B): the ramp END pull the save screen's graph handles set,
         * kPa — or MIN_VALUE when untouched, and then the ramp's end stays as stored.
         * Only a ramp set ever reads it; {@link #applyInPlace} and
         * {@link #buildNewRoutine} write it into Set#up2.
         */
        public int rampEndUp = Integer.MIN_VALUE;
    }

    /**
     * ITEM 11a (wave-4 ruling): an EXTENDED run writes its longer time into the set on
     * save. Longer only — a cut-short run never shrinks the plan — and beyond the 2 s
     * timer tolerance, so jitter never rewrites a duration.
     */
    public static boolean extendsPlan(SetChange c) {
        return c != null && c.planDurSec > 0
            && c.saveDurSec >= c.planDurSec + (int) (CUT_SHORT_TOL_MS / 1000L);
    }

    /**
     * The blocks folded to one entry per set occurrence, in time order. The final
     * values are the LAST block that is a genuine departure from the plan (!onPlan);
     * an occurrence whose blocks are all on-plan (extended, cut short, or unchanged)
     * keeps its planned values. A run's "Work · in" fragment is thereby collapsed
     * away: the whole occurrence reads planned → final.
     */
    public static List<SetChange> setChanges(List<Block> blocks) {
        List<SetChange> out = new ArrayList<SetChange>();
        if (blocks == null) return out;
        int i = 0;
        while (i < blocks.size()) {
            Block first = blocks.get(i);
            int j = i;
            Block lastOff = null, anyValue = null;
            boolean anySkipped = false, anyChanged = false;
            long span = 0;
            while (j < blocks.size()) {
                Block b = blocks.get(j);
                if (b.stageIdx != first.stageIdx || b.pos != first.pos
                        || !eq(b.setId, first.setId)) break;
                if (b.skipped) anySkipped = true;
                else {
                    anyValue = b;
                    span += b.ownSpanMs();
                    if (!b.onPlan && !b.unchanged) lastOff = b;
                    if (!b.unchanged) anyChanged = true;
                }
                j++;
            }
            SetChange c = new SetChange();
            c.stageIdx = first.stageIdx; c.pos = first.pos; c.setId = first.setId;
            c.stageName = first.stageName; c.setName = first.setName;
            c.manual = first.manual;
            c.skipped = anyValue == null && anySkipped;
            c.changed = anyChanged;
            c.hasPlan = first.hasPlan;
            c.pUp = first.pUp; c.pLo = first.pLo; c.pUh = first.pUh;
            c.pLh = first.pLh; c.pSp = first.pSp;
            Block src = lastOff != null ? lastOff : (anyValue != null ? anyValue : first);
            if (lastOff == null && c.hasPlan && !c.manual) {
                // Every block on plan: the occurrence's values ARE the planned ones.
                c.up = c.pUp; c.lo = c.pLo; c.uh = c.pUh; c.lh = c.pLh; c.sp = c.pSp;
            } else {
                c.up = src.up; c.lo = src.lo; c.uh = src.uh; c.lh = src.lh; c.sp = src.sp;
            }
            c.saveDurSec = (int) (span / 1000L);
            c.planDurSec = first.planDurSec;
            out.add(c);
            i = j;
        }
        return out;
    }

    /**
     * ITEM 11's Update semantics: write the change INTO the set, in place — no more
     * "· adjusted" copies spliced into the routine. Two shapes:
     *  - onlyHere false ("change it everywhere", or the set is used only by this
     *    routine): the five final values are written into the existing set, which
     *    KEEPS ITS ID — every routine that uses it sees the change;
     *  - onlyHere true ("only here — makes a copy"): the set is copied under a fresh
     *    id, the copy gets the values, and the id is swapped at THIS one occurrence
     *    of THIS routine only. The original set is untouched.
     * Returns the id now standing at the occurrence (the set's own, or the copy's),
     * or null when the set no longer exists. Always through Set#clamp.
     */
    public static String applyInPlace(SetChange c, Model m, Model.Routine r,
                                      boolean onlyHere, int ceilKpa) {
        if (c == null || m == null || r == null) return null;
        Model.Set src = m.set(c.setId);
        if (src == null) return null;
        if (!onlyHere) {
            src.up = c.up; src.lo = c.lo; src.uh = c.uh; src.lh = c.lh; src.sp = c.sp;
            if (src.ramp && c.rampEndUp != Integer.MIN_VALUE) src.up2 = c.rampEndUp;
            if (extendsPlan(c)) src.dur = c.saveDurSec;
            src.clamp(ceilKpa);
            return src.id;
        }
        Model.Set copy = src.copy(m.newSetId());
        List<String> taken = new ArrayList<String>();
        for (int i = 0; i < m.sets.size(); i++) taken.add(m.sets.get(i).name);
        copy.name = unique(copy.name, taken);
        copy.up = c.up; copy.lo = c.lo; copy.uh = c.uh; copy.lh = c.lh; copy.sp = c.sp;
        if (copy.ramp && c.rampEndUp != Integer.MIN_VALUE) copy.up2 = c.rampEndUp;
        if (extendsPlan(c)) copy.dur = c.saveDurSec;
        copy.clamp(ceilKpa);
        m.sets.add(copy);
        // Swap at the ONE occurrence: this routine, this stage, this position.
        if (c.stageIdx >= 0 && c.stageIdx < r.stages.size()) {
            Model.Stage st = r.stages.get(c.stageIdx);
            if (c.pos >= 0 && c.pos < st.setIds.size() && eq(st.setIds.get(c.pos), c.setId)) {
                st.setIds.set(c.pos, copy.id);
                return copy.id;
            }
            int at = st.setIds.indexOf(c.setId);
            if (at >= 0) { st.setIds.set(at, copy.id); return copy.id; }
        }
        for (int i = 0; i < r.stages.size(); i++) {
            int at = r.stages.get(i).setIds.indexOf(c.setId);
            if (at >= 0) { r.stages.get(i).setIds.set(at, copy.id); return copy.id; }
        }
        return copy.id;
    }

    /**
     * ITEM 11d: a "Try this set" run writing its final values back into the TRIED set —
     * the same writes as {@link #applyInPlace}'s everywhere branch, with no routine
     * involved (a try has none). Duration follows the 11a rule ({@link #extendsPlan});
     * a dragged ramp end (11b/3B) lands in up2. Always through Set#clamp.
     */
    public static boolean applyToSet(SetChange c, Model.Set s, int ceilKpa) {
        if (c == null || s == null) return false;
        s.up = c.up; s.lo = c.lo; s.uh = c.uh; s.lh = c.lh; s.sp = c.sp;
        if (s.ramp && c.rampEndUp != Integer.MIN_VALUE) s.up2 = c.rampEndUp;
        if (extendsPlan(c)) s.dur = c.saveDurSec;
        s.clamp(ceilKpa);
        return true;
    }

    /**
     * Remove a SKIPPED occurrence from its stage — and the stage itself when that
     * leaves it empty (a routine never carries an empty stage). Returns whether
     * anything was removed. Callers removing several apply them in DESCENDING
     * (stageIdx, pos) order, so earlier indices stay valid.
     */
    public static boolean dropSkipped(SetChange c, Model m, Model.Routine r) {
        if (c == null || r == null) return false;
        if (c.stageIdx < 0 || c.stageIdx >= r.stages.size()) return false;
        Model.Stage st = r.stages.get(c.stageIdx);
        if (c.pos >= 0 && c.pos < st.setIds.size() && eq(st.setIds.get(c.pos), c.setId))
            st.setIds.remove(c.pos);
        else if (!st.setIds.remove(c.setId)) return false;
        if (st.setIds.isEmpty()) r.stages.remove(c.stageIdx);
        return true;
    }

    /**
     * How many occurrences the routine would keep after applying these drops — the
     * "never file an empty routine" guard, asked BEFORE anything is touched.
     */
    public static int occurrencesLeft(List<SetChange> cs, boolean[] drop, Model.Routine r) {
        if (r == null) return 0;
        int total = 0;
        for (int i = 0; i < r.stages.size(); i++) total += r.stages.get(i).setIds.size();
        if (cs != null && drop != null)
            for (int i = 0; i < cs.size() && i < drop.length; i++)
                if (drop[i] && cs.get(i).skipped) total--;
        return total;
    }

    /**
     * ITEM 11's "Save as a new routine": the planned stages copied, with each USED
     * changed set replaced by a NEW COPY carrying the final values (fromRun stamped),
     * dropped skipped occurrences omitted, and emptied stages omitted. A manual run
     * becomes one "Work" stage of new sets. Returns a Commit whose routineId is null
     * when nothing would give the routine a single stage — then NOTHING was created.
     */
    public static Commit buildNewRoutine(List<SetChange> cs, boolean[] use, boolean[] drop,
                                         AsRun run, Model m, String name, int ceilKpa) {
        Commit out = new Commit();
        if (cs == null || run == null || m == null) return out;
        List<String> taken = new ArrayList<String>();
        for (int i = 0; i < m.sets.size(); i++) taken.add(m.sets.get(i).name);
        List<Model.Set> made = new ArrayList<Model.Set>();
        if (run.manual) {
            List<String> ids = new ArrayList<String>();
            for (int i = 0; i < cs.size(); i++) {
                SetChange c = cs.get(i);
                if (c.skipped || (use != null && i < use.length && !use[i])) continue;
                int dur = Math.max(30, c.saveDurSec > 0 ? c.saveDurSec : c.planDurSec);
                Model.Set s = Model.Set.fixed(m.newSetId(),
                        unique(ids.isEmpty() ? "Manual · as run" : "Manual · as run", taken),
                        c.up, c.lo, c.uh, c.lh, c.sp, dur);
                taken.add(s.name);
                s.fromRun = run.ts;
                s.clamp(ceilKpa);
                made.add(s);
                ids.add(s.id);
            }
            if (ids.isEmpty()) return out;
            for (int i = 0; i < made.size(); i++) { m.sets.add(made.get(i)); out.createdSetIds.add(made.get(i).id); }
            Model.Routine nr = m.newRoutine(m.newRoutineId(), name);
            nr.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, ids.toArray(new String[ids.size()])));
            m.routines.add(nr);
            out.routineId = nr.id;
            out.routineCreated = true;
            return out;
        }
        List<Model.Stage> src = snapStages(run, m);
        List<Model.Stage> built = new ArrayList<Model.Stage>();
        for (int i = 0; i < src.size(); i++) {
            Model.Stage st = src.get(i);
            List<String> ids = new ArrayList<String>();
            for (int j = 0; j < st.setIds.size(); j++) {
                String id = st.setIds.get(j);
                int at = -1;
                for (int k = 0; k < cs.size(); k++) {
                    SetChange c = cs.get(k);
                    if (c.stageIdx == i && c.pos == j && eq(c.setId, id)) { at = k; break; }
                }
                if (at >= 0 && cs.get(at).skipped) {
                    if (drop != null && at < drop.length && drop[at]) continue;
                    ids.add(id);
                    continue;
                }
                if (at >= 0 && cs.get(at).changed && use != null && at < use.length && use[at]) {
                    SetChange c = cs.get(at);
                    Model.Set base = m.set(id);
                    Model.Set ns;
                    if (base != null) {
                        ns = base.copy(m.newSetId());
                        ns.name = unique(base.name, taken);
                    } else {
                        int dur = Math.max(30, c.saveDurSec > 0 ? c.saveDurSec : c.planDurSec);
                        ns = Model.Set.fixed(m.newSetId(),
                                unique((c.setName.length() == 0 ? "Set" : c.setName), taken),
                                c.up, c.lo, c.uh, c.lh, c.sp, dur);
                    }
                    taken.add(ns.name);
                    ns.up = c.up; ns.lo = c.lo; ns.uh = c.uh; ns.lh = c.lh; ns.sp = c.sp;
                    if (ns.ramp && c.rampEndUp != Integer.MIN_VALUE) ns.up2 = c.rampEndUp;
                    if (extendsPlan(c)) ns.dur = c.saveDurSec;
                    ns.fromRun = run.ts;
                    ns.clamp(ceilKpa);
                    made.add(ns);
                    ids.add(ns.id);
                    continue;
                }
                ids.add(id);
            }
            if (ids.isEmpty()) continue;
            built.add(Model.Stage.of(st.name, st.colour, ids.toArray(new String[ids.size()])));
        }
        if (built.isEmpty()) return out;
        for (int i = 0; i < made.size(); i++) { m.sets.add(made.get(i)); out.createdSetIds.add(made.get(i).id); }
        Model.Routine nr = m.newRoutine(m.newRoutineId(), name);
        nr.stages.addAll(built);
        m.routines.add(nr);
        out.routineId = nr.id;
        out.routineCreated = true;
        return out;
    }

    /* ----------------------------------------------------------------------- util */

    private static boolean eq(String a, String b) { return a == null ? b == null || b.length() == 0 : (b == null ? a.length() == 0 : a.equals(b)); }

    private static long parseLong(String s, long d) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return d; }
    }
}
