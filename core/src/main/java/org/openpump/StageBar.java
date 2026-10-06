package org.openpump;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * THE STAGE BAR, as numbers - the one model behind every drawing of a routine's stages: the
 * Today card, the Library card and, live, the top of the run screen (0.10 run-screen
 * redesign). One segment per stage, SIZED BY TIME; a tick per set inside a work block; a rest
 * its own segment; and, on the run screen, how much of each stage is done.
 *
 * Everything here reads the PLAN the run is actually playing (Model.Preset#stageIdx, the same
 * key Model#stageDurationsMs sums), never a second guess at where the run is - defect #25's
 * rule. A stage skipped in Coming steps is no longer in the plan; its length comes from what
 * was taken out, so its segment keeps its place and is drawn hatched.
 */
public final class StageBar {
    private StageBar() { }

    /** A work block with more sets than this draws no ticks: they would merge into a smear. */
    public static final int MAX_TICKS = 40;

    public static final class Segment {
        /** Index into the routine's stages. */
        public int stage;
        /** RunLook kind. */
        public int kind;
        public String name = "";
        /** Its length, in ms: what the bar is divided by. */
        public long weightMs;
        /** How much of it is done, 0..1. */
        public float fill;
        /** The stage playing now. */
        public boolean current;
        /** Skipped in Coming steps: drawn hatched, never filled. */
        public boolean skipped;
        /** Changed in Coming steps: drawn with a small dot. */
        public boolean changed;
        /** Where each set boundary falls, as fractions 0..1 of the segment. */
        public float[] ticks = new float[0];
        /** R11-2: the stage (or piece of one) this segment is drawn for - one segment on the
         *  static bar, one part per set on the live one (#buildLive). Parts of one group are
         *  one "now" for the words under the bar. */
        public int group;
        /** R11-2: on the live bar, which set of its step this part is (1-based); 0 for a
         *  segment that is not one set. */
        public int set;
        /** The plan indices this segment covers, in order (empty for a skipped one). */
        int[] steps = new int[0];
    }

    /**
     * Builds the bar.
     *
     * @param stages      the routine's stages (names and kinds), may be null for none
     * @param plan        the plan as the run holds it now (skipped stages removed)
     * @param dispIdx     the preset playing, or -1 when nothing is (the static Today bar)
     * @param inPresetMs  how far into that preset the run is
     * @param skippedMs   per stage, the length taken out by a skip (0: not skipped); may be null
     * @param changed     per stage, whether Coming steps changed it; may be null
     * @param ticks       draw set ticks in work blocks
     */
    public static List<Segment> build(List<Model.Stage> stages, List<Model.Preset> plan,
                                      int dispIdx, long inPresetMs, long[] skippedMs,
                                      boolean[] changed, boolean ticks) {
        return build(stages, plan, dispIdx, inPresetMs, skippedMs, changed, ticks, null);
    }

    /**
     * The same, told which BLOCKS Coming steps skipped (`skippedBlocks`, by ComingSteps#key,
     * the presets each skip took out; may be null).
     *
     * EVERY SKIPPED BLOCK IS HATCHED WHEREVER IT SITS (the owner's pick on the leftovers,
     * option A; device check EMU9c). The bar draws stages, and a stage was hatched only when
     * every block of it was skipped - so a block skipped between two that still run left its
     * stage drawn whole, as if it would still play. Now a stage with blocks both skipped and
     * still to run is drawn in pieces, in the blocks' own order: each run of blocks still to
     * play one segment (filled and ticked as a stage is), each run of skipped blocks one
     * hatched segment sized by what was taken out. A stage with none skipped, or with all of
     * them skipped, is drawn exactly as before. Undo puts the block back in the plan and out
     * of `skippedBlocks`, so the stage is drawn whole again.
     */
    public static List<Segment> build(List<Model.Stage> stages, List<Model.Preset> plan,
                                      int dispIdx, long inPresetMs, long[] skippedMs,
                                      boolean[] changed, boolean ticks,
                                      Map<Integer, List<Model.Preset>> skippedBlocks) {
        List<Segment> out = new ArrayList<Segment>();
        int n = stages == null ? 0 : stages.size();
        if (n == 0 || plan == null) return out;
        long[] total = new long[n], done = new long[n];
        int curStage = (dispIdx >= 0 && dispIdx < plan.size()) ? plan.get(dispIdx).stageIdx : -1;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            int s = p.stageIdx;
            if (s < 0 || s >= n) continue;
            long d = Math.max(0L, p.durMs);
            total[s] += d;
            if (dispIdx < 0) continue;
            if (i < dispIdx) done[s] += d;
            else if (i == dispIdx) done[s] += Math.min(d, Math.max(0L, inPresetMs));
        }
        for (int s = 0; s < n; s++) {
            boolean sk = skippedMs != null && s < skippedMs.length && skippedMs[s] > 0;
            long w = sk ? skippedMs[s] : total[s];
            // An empty stage keeps its place on the static bar (Today and the Library draw it
            // at Insight#railShares' minimum share, so it shows it is there); the live bar
            // has nothing to fill in it.
            if (w <= 0 && dispIdx >= 0) continue;
            if (!sk && total[s] > 0 && dispIdx >= 0
                    && pieces(out, stages.get(s), s, plan, dispIdx, inPresetMs, curStage,
                              skippedBlocks, changed != null && s < changed.length && changed[s],
                              ticks))
                continue;
            Segment g = new Segment();
            g.stage = s;
            Model.Stage st = stages.get(s);
            g.kind = RunLook.kindOf(st);
            g.name = st == null || st.name == null ? "" : st.name;
            g.weightMs = w;
            g.skipped = sk;
            g.changed = changed != null && s < changed.length && changed[s];
            g.current = !sk && s == curStage;
            g.group = out.size();
            if (!sk) g.steps = stepsOf(plan, s, null);
            if (!sk && dispIdx >= 0) {
                // Stages the run has passed are whole; the one playing fills as it runs.
                if (curStage >= 0 && s < curStage) g.fill = 1f;
                else g.fill = total[s] <= 0 ? 0f : (float) Math.min(1.0, (double) done[s] / total[s]);
            }
            if (ticks && !sk && (g.kind == RunLook.WORK || g.kind == RunLook.FATIGUE
                                 || g.kind == RunLook.TRACTION))
                g.ticks = ticksOf(plan, s, total[s]);
            out.add(g);
        }
        return out;
    }

    /** A piece of a stage: the blocks (by place) it covers, whether they were skipped. */
    private static final class Piece {
        final List<Integer> pos = new ArrayList<Integer>();
        boolean skipped;
        long skippedMs;
    }

    /**
     * Stage `s` drawn in pieces when some of its blocks were skipped and some still run;
     * false (nothing added) when none of its blocks was skipped.
     */
    private static boolean pieces(List<Segment> out, Model.Stage st, int s,
                                  List<Model.Preset> plan, int dispIdx, long inPresetMs,
                                  int curStage, Map<Integer, List<Model.Preset>> skippedBlocks,
                                  boolean changed, boolean ticks) {
        if (skippedBlocks == null || skippedBlocks.isEmpty()) return false;
        // Every block of the stage, by its place: still in the plan (live) or taken out.
        TreeMap<Integer, Long> skippedAt = new TreeMap<Integer, Long>();
        for (Map.Entry<Integer, List<Model.Preset>> e : skippedBlocks.entrySet()) {
            int key = e.getKey().intValue();
            if (ComingSteps.stageOf(key) != s) continue;
            long len = Math.max(1L, ComingSteps.lengthOf(e.getValue()));
            Integer at = Integer.valueOf(ComingSteps.posOf(key));
            Long had = skippedAt.get(at);
            skippedAt.put(at, Long.valueOf(len + (had == null ? 0L : had.longValue())));
        }
        if (skippedAt.isEmpty()) return false;
        TreeMap<Integer, Boolean> all = new TreeMap<Integer, Boolean>();
        for (int i = 0; i < plan.size(); i++)
            if (plan.get(i).stageIdx == s) all.put(Integer.valueOf(plan.get(i).pos), Boolean.FALSE);
        for (Integer at : skippedAt.keySet())
            if (!all.containsKey(at)) all.put(at, Boolean.TRUE);
        // Runs of the same kind become one piece.
        List<Piece> ps = new ArrayList<Piece>();
        for (Map.Entry<Integer, Boolean> e : all.entrySet()) {
            boolean sk = e.getValue().booleanValue();
            Piece last = ps.isEmpty() ? null : ps.get(ps.size() - 1);
            if (last == null || last.skipped != sk) {
                last = new Piece();
                last.skipped = sk;
                ps.add(last);
            }
            last.pos.add(e.getKey());
            if (sk) last.skippedMs += skippedAt.get(e.getKey()).longValue();
        }
        int curPos = (dispIdx >= 0 && dispIdx < plan.size() && plan.get(dispIdx).stageIdx == s)
            ? plan.get(dispIdx).pos : Integer.MIN_VALUE;
        Segment lastLive = null;
        for (int k = 0; k < ps.size(); k++) {
            Piece pc = ps.get(k);
            Segment g = new Segment();
            g.stage = s;
            g.kind = RunLook.kindOf(st);
            g.name = st == null || st.name == null ? "" : st.name;
            g.skipped = pc.skipped;
            g.group = out.size();
            if (pc.skipped) {
                g.weightMs = pc.skippedMs;
                out.add(g);
                continue;
            }
            g.steps = stepsOf(plan, s, pc.pos);
            long total = 0, done = 0;
            for (int i = 0; i < plan.size(); i++) {
                Model.Preset p = plan.get(i);
                if (p.stageIdx != s || !pc.pos.contains(Integer.valueOf(p.pos))) continue;
                long d = Math.max(0L, p.durMs);
                total += d;
                if (i < dispIdx) done += d;
                else if (i == dispIdx) done += Math.min(d, Math.max(0L, inPresetMs));
            }
            g.weightMs = total;
            g.current = s == curStage && pc.pos.contains(Integer.valueOf(curPos));
            if (curStage >= 0 && s < curStage) g.fill = 1f;
            else g.fill = total <= 0 ? 0f : (float) Math.min(1.0, (double) done / total);
            if (ticks && (g.kind == RunLook.WORK || g.kind == RunLook.FATIGUE
                          || g.kind == RunLook.TRACTION))
                g.ticks = ticksOf(plan, s, total, pc.pos);
            out.add(g);
            lastLive = g;
        }
        // The stage's one "changed" dot, on the last of its pieces still to run.
        if (lastLive != null) lastLive.changed = changed;
        return true;
    }

    /**
     * THE BAR'S WORDS (0.10 final): "now: Work · next: Rest", or "now: Work · then the end".
     * Returns {the segment playing, the next one the run will play} as indices into `segs`,
     * -1 for none. A skipped segment is never "next": the run passes over it.
     */
    public static int[] nowAndNext(List<Segment> segs) {
        int cur = -1, next = -1;
        if (segs == null) return new int[] { cur, next };
        for (int i = 0; i < segs.size(); i++) if (segs.get(i).current) { cur = i; break; }
        // R11-2: on the live bar a block is one part per set - the next SET is not "next".
        if (cur >= 0)
            for (int i = cur + 1; i < segs.size(); i++)
                if (!segs.get(i).skipped && segs.get(i).group != segs.get(cur).group) {
                    next = i;
                    break;
                }
        return new int[] { cur, next };
    }

    /** The plan indices of stage `s` (only the blocks at the places `only`, when given). */
    private static int[] stepsOf(List<Model.Preset> plan, int s, List<Integer> only) {
        List<Integer> at = new ArrayList<Integer>();
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p.stageIdx != s) continue;
            if (only != null && !only.contains(Integer.valueOf(p.pos))) continue;
            at.add(Integer.valueOf(i));
        }
        int[] out = new int[at.size()];
        for (int i = 0; i < out.length; i++) out[i] = at.get(i).intValue();
        return out;
    }

    /* ============================ R11-2 THE LIVE BAR, SET BY SET ============================
     *
     * THE BAR IS THE RUN ACTUALLY PLAYING (the owner's pick, option A, a bug). The status line
     * said "set 7 of 8" and the clock 19:09 of 22:57 while the bar's last block showed 2 of 5
     * parts filled: the bar cut its blocks into sets by the presets as written (RunLook#setsIn
     * at the preset's own cycle), and the run counts sets its own way - a change in force
     * re-times a block's cycle and keeps its count (SetClock), and the day's build trims sets.
     * Now the run screen hands the bar the run's own count of every step's sets
     * (SessionActivity#setsOfStep, the count "set N of M" is made of) and the set playing with
     * how far into it the clock is, and every block of a work kind is drawn ONE PART PER SET:
     * parts before the set playing full, the set playing outlined and filled to its clock, the
     * rest empty. "Set N of M" is then the Nth work part, by construction (#setNumber is the
     * status line's own count). A warm-up, a rest, a ramp (steps, not sets) and a stitched hold
     * stay one part each and fill by time as before; a skipped block stays hatched where it
     * sits (0.10). */

    /**
     * The live bar.
     *
     * @param stepSets  per plan index, the sets the run counts for that step; 0 for a step
     *                  that is not counted in sets (a rest, a warm-up, a ramp's step)
     * @param setNow    the set of step `dispIdx` playing, 1-based (0: not counted)
     * @param setFrac   how far into that set the clock is, 0..1
     */
    public static List<Segment> buildLive(List<Model.Stage> stages, List<Model.Preset> plan,
                                          int dispIdx, long inPresetMs, int[] stepSets,
                                          int setNow, float setFrac, long[] skippedMs,
                                          boolean[] changed,
                                          Map<Integer, List<Model.Preset>> skippedBlocks) {
        return buildLive(stages, plan, dispIdx, inPresetMs, stepSets, setNow, setFrac,
                         skippedMs, changed, skippedBlocks, null);
    }

    /**
     * The same, told what Skip cut out of the steps it ended (`cutMs`, per plan index: the time
     * that step did not play, and the steps after it that went with it; null for none).
     *
     * FIX11 F4 - WHAT "SKIP THESE SETS" SKIPPED IS HATCHED, NOT DONE (device check EMU12 F4).
     * Skip rewrites the step it ends to what it delivered (the plan is the run's own account),
     * so the bar drew the block as finished: every part full, the 13 sets squeezed into the
     * time 8 of them took, and nothing hatched - and a skipped warm-up stayed solid blue. Now a
     * step cut short keeps its parts at their planned size: what ran is filled, the sets it did
     * not run are hatched where they sit, and a step drawn whole (a warm-up, a ramp) has its
     * unplayed time hatched after it - the 0.10 rule for a skip from Coming steps, for the Skip
     * button too.
     */
    public static List<Segment> buildLive(List<Model.Stage> stages, List<Model.Preset> plan,
                                          int dispIdx, long inPresetMs, int[] stepSets,
                                          int setNow, float setFrac, long[] skippedMs,
                                          boolean[] changed,
                                          Map<Integer, List<Model.Preset>> skippedBlocks,
                                          long[] cutMs) {
        List<Segment> whole = build(stages, plan, dispIdx, inPresetMs, skippedMs, changed, true,
                                    skippedBlocks);
        List<Segment> out = new ArrayList<Segment>();
        for (int gi = 0; gi < whole.size(); gi++) {
            Segment g = whole.get(gi);
            g.group = gi;
            List<Segment> parts = setParts(g, plan, dispIdx, inPresetMs, stepSets, setNow,
                                           setFrac, cutMs);
            if (parts != null) { out.addAll(parts); continue; }
            out.add(g);
            long cut = 0L;
            for (int k = 0; !g.skipped && k < g.steps.length; k++) cut += cutOf(cutMs, g.steps[k]);
            if (cut > 0L) out.add(hatched(g, cut));
        }
        return out;
    }

    private static long cutOf(long[] cutMs, int i) {
        return cutMs == null || i < 0 || i >= cutMs.length ? 0L : Math.max(0L, cutMs[i]);
    }

    /** A hatched part of `g`'s group, `ms` long: time a Skip took out of it. */
    private static Segment hatched(Segment g, long ms) {
        Segment h = new Segment();
        h.stage = g.stage;
        h.kind = g.kind;
        h.name = g.name;
        h.group = g.group;
        h.skipped = true;
        h.weightMs = ms;
        return h;
    }

    /**
     * Segment `g` as one part per set, or null when it stays whole: skipped, not a work kind,
     * no step in it counted in sets, or more parts than {@link #MAX_TICKS}.
     *
     * FIX11 F2/F3 - A STEP NOT COUNTED IN SETS IS ONE PART OF ITS GROUP, never a reason to draw
     * the whole group by its ticks. The fatigue block opens with a two-hold climb, built as a
     * ramp; one step with no count made the block fall back to the static bar's ticks and its
     * fill by time - 15 ticks against "set 1 of 13", and after "Adjust the running set > Rest
     * of this block" a longer preset re-ticked to 18 parts while the fill, by time over the new
     * length, went backwards (device check EMU12 F2, F3). The run screen now counts the climb's
     * holds as the block's sets (RunScreen#countSteps), so the block is 15 parts, set by set;
     * a step that still has no count (a work block's own climb, a stitched hold) is one part
     * filled by its clock.
     */
    private static List<Segment> setParts(Segment g, List<Model.Preset> plan, int dispIdx,
                                          long inPresetMs, int[] stepSets, int setNow,
                                          float setFrac, long[] cutMs) {
        if (g.skipped || g.steps.length == 0 || stepSets == null) return null;
        if (g.kind != RunLook.WORK && g.kind != RunLook.FATIGUE && g.kind != RunLook.TRACTION)
            return null;
        int count = 0;
        boolean counted = false;
        for (int k = 0; k < g.steps.length; k++) {
            int i = g.steps[k];
            if (i < 0 || i >= stepSets.length) return null;
            count += Math.max(1, stepSets[i]);
            if (stepSets[i] > 0) counted = true;
        }
        if (!counted || count > MAX_TICKS) return null;
        float f = Math.max(0f, Math.min(1f, setFrac));
        List<Segment> parts = new ArrayList<Segment>();
        for (int k = 0; k < g.steps.length; k++) {
            int i = g.steps[k];
            int n = stepSets[i];
            long d = Math.max(0L, plan.get(i).durMs);
            long cut = cutOf(cutMs, i);
            if (n <= 0) {
                // A step not counted in sets: one part, filled by its own clock.
                Segment pt = part(g, i, 0);
                pt.weightMs = d;
                if (dispIdx < 0 || i > dispIdx) pt.fill = 0f;
                else if (i < dispIdx) pt.fill = 1f;
                else {
                    pt.fill = d <= 0 ? 0f
                        : (float) Math.min(1.0, Math.max(0L, inPresetMs) / (double) d);
                    pt.current = true;
                }
                parts.add(pt);
                if (cut > 0L) parts.add(hatched(g, cut));
                continue;
            }
            // Cut short by Skip: the parts keep their planned size; what ran is filled.
            long planned = d + cut;
            long each = planned / n;
            for (int j = 1; j <= n; j++) {
                Segment pt = part(g, i, j);
                long from = each * (j - 1), to = j < n ? each * j : planned;
                pt.weightMs = to - from;
                if (cut > 0L) {
                    if (to <= d) pt.fill = 1f;
                    else if (from < d) pt.fill = (float) ((d - from) / (double) (to - from));
                    else { pt.fill = 0f; pt.skipped = true; }
                } else if (dispIdx < 0 || i > dispIdx) pt.fill = 0f;
                else if (i < dispIdx) pt.fill = 1f;
                else if (j < setNow) pt.fill = 1f;
                else if (j == setNow) { pt.fill = f; pt.current = true; }
                else pt.fill = 0f;
                parts.add(pt);
            }
        }
        // The group's one "changed" dot, on its last part.
        if (!parts.isEmpty()) parts.get(parts.size() - 1).changed = g.changed;
        return parts;
    }

    /** One part of `g`'s group: step `i`'s set `set` (0: the step as one part). */
    private static Segment part(Segment g, int i, int set) {
        Segment pt = new Segment();
        pt.stage = g.stage;
        pt.kind = g.kind;
        pt.name = g.name;
        pt.group = g.group;
        pt.set = set;
        pt.steps = new int[] { i };
        return pt;
    }

    /**
     * "SET N OF M" - which of the run's work sets is playing, numbered across the whole run as
     * a person counts them ("set 6 of 10" after five and a rest), and of how many. A block that
     * is not work (the fatigue block, traction) counts its own sets; a ramp's step is a step,
     * not a set ({0, 0}). The run screen's status line and the live bar both read this, so the
     * number said and the part outlined are one count.
     *
     * @param kinds     per plan index, the RunLook kind of its stage
     * @param ramp      per plan index, whether it is a step of a ramp
     * @param stepSets  per plan index, the sets the run counts (#buildLive)
     * @param k         the set playing in step `dispIdx`, 1-based
     * @param n         the sets step `dispIdx` has, as its clock counts them
     */
    public static int[] setNumber(int[] kinds, boolean[] ramp, int[] stepSets, int dispIdx,
                                  int k, int n) {
        return setNumber(kinds, ramp, stepSets, null, dispIdx, k, n);
    }

    /**
     * The same, told each step's stage (`stageOf`, per plan index; null: as above).
     *
     * FIX11 F2 - A BLOCK THAT IS NOT WORK COUNTS ALL ITS OWN SETS, ACROSS ITS STEPS: the fatigue
     * block is a two-hold climb and thirteen holds, and its count was the thirteen's alone
     * ("set 1 of 13" on the bar's third part, while the notice said 15 holds and the bar drew
     * 15). Counted over every step of the stage the block is in - its climb's holds included
     * when the caller counts them (a step flagged in `ramp` is a step, not a set) - "set N of
     * M" is the Nth part of the block: the bar's own count, one source.
     */
    public static int[] setNumber(int[] kinds, boolean[] ramp, int[] stepSets, int[] stageOf,
                                  int dispIdx, int k, int n) {
        if (dispIdx < 0 || dispIdx >= kinds.length) return new int[] { 0, 0 };
        if (ramp != null && dispIdx < ramp.length && ramp[dispIdx]) return new int[] { 0, 0 };
        if (k <= 0 || n <= 0) return new int[] { 0, 0 };
        if (kinds[dispIdx] != RunLook.WORK) {
            if (stageOf == null || dispIdx >= stageOf.length) return new int[] { k, n };
            int before = 0, total = 0;
            for (int i = 0; i < kinds.length; i++) {
                if (i >= stageOf.length || stageOf[i] != stageOf[dispIdx]
                        || kinds[i] != kinds[dispIdx]
                        || (ramp != null && i < ramp.length && ramp[i])) continue;
                int s = i == dispIdx ? n : (i < stepSets.length ? stepSets[i] : 0);
                if (i < dispIdx) before += s;
                total += s;
            }
            return new int[] { before + k, Math.max(total, before + k) };
        }
        int before = 0, total = 0;
        for (int i = 0; i < kinds.length; i++) {
            // A ramp's steps are steps, not sets (device check: "set 8 of 12" in a ramp).
            if (kinds[i] != RunLook.WORK || (ramp != null && i < ramp.length && ramp[i])) continue;
            int s = i == dispIdx ? n : (i < stepSets.length ? stepSets[i] : 0);
            if (i < dispIdx) before += s;
            total += s;
        }
        return new int[] { before + k, Math.max(total, before + k) };
    }

    /** The set boundaries inside stage `s`, as fractions of its length: every boundary
     *  between two presets that are whole repetitions, and every cycle boundary inside a
     *  preset the pump repeats. Empty when there would be more than {@link #MAX_TICKS}. */
    static float[] ticksOf(List<Model.Preset> plan, int s, long stageMs) {
        return ticksOf(plan, s, stageMs, null);
    }

    /** The same over the blocks at the places `only` (null: every block of the stage). */
    static float[] ticksOf(List<Model.Preset> plan, int s, long stageMs, List<Integer> only) {
        if (stageMs <= 0) return new float[0];
        List<Float> at = new ArrayList<Float>();
        long t = 0;
        boolean first = true;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p.stageIdx != s) continue;
            if (only != null && !only.contains(Integer.valueOf(p.pos))) continue;
            long d = Math.max(0L, p.durMs);
            if (!first && !plan.get(prevOf(plan, i, s, only)).cyclePart && t > 0 && t < stageMs)
                at.add((float) ((double) t / stageMs));
            first = false;
            long c = RunLook.cycleMs(p);
            int sets = RunLook.setsIn(p);
            for (int k = 1; k < sets; k++) {
                long x = t + k * c;
                if (x > 0 && x < t + d) at.add((float) ((double) x / stageMs));
            }
            t += d;
            if (at.size() > MAX_TICKS) return new float[0];
        }
        float[] f = new float[at.size()];
        for (int i = 0; i < f.length; i++) f[i] = at.get(i);
        return f;
    }

    private static int prevOf(List<Model.Preset> plan, int i, int s, List<Integer> only) {
        for (int j = i - 1; j >= 0; j--)
            if (plan.get(j).stageIdx == s
                    && (only == null || only.contains(Integer.valueOf(plan.get(j).pos))))
                return j;
        return i;
    }
}
