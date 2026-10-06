package org.openpump;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WHERE A RUN WAS, IN TERMS A REBUILD CAN FIND (device check EMU9b N1).
 *
 * A rejoin after the process died rebuilds the run from the saved routine and its day's
 * choice (SessionActivity#RejoinRunTap) - but the run it rejoins had been edited "this run
 * only": Coming steps skipped later blocks, the Skip button took the rest of a block out, an
 * Undo put copies back. Its plan index was counted in THAT list, so applied to the rebuild it
 * named a different step - a step the person had skipped, which then played again - and the
 * dialog's "step 7 of 13" counted the unedited plan.
 *
 * WHAT IS KEPT, AND WHY SO LITTLE. Not the presets (a rejoin still plays what the routine
 * says, never a stored copy of it): the step playing, as its place in the plan AS BUILT
 * (Model.Preset#src), and the skips - the blocks Coming steps took out and the as-built steps
 * the Skip button took out. The rebuild is put back into that shape, and the index read from
 * it. Every other "this run only" change (a set count, a hold, a ramp recount) was never
 * carried by a rejoin and is not now: those are figures, and the routine's are the ones a
 * fresh start of the step plays.
 *
 * AN OLD SNAPSHOT, or a routine edited since (its plan no longer the size it was built at),
 * keeps the old reading: the index, as it was.
 */
public final class RunRejoin {
    private RunRejoin() { }

    /** Stamps each step with its place in the plan as built. */
    public static void stamp(List<Model.Preset> plan) {
        for (int i = 0; plan != null && i < plan.size(); i++) plan.get(i).src = i;
    }

    /** The as-built steps a Skip took out, added to `taken`. */
    public static void took(Set<Integer> taken, List<Model.Preset> out) {
        for (int i = 0; out != null && i < out.size(); i++)
            if (out.get(i).src >= 0) taken.add(Integer.valueOf(out.get(i).src));
    }

    /** ...and the ones its Undo put back, taken off it again. */
    public static void gaveBack(Set<Integer> taken, List<Model.Preset> back) {
        for (int i = 0; back != null && i < back.size(); i++)
            taken.remove(Integer.valueOf(back.get(i).src));
    }

    /** The snapshot's half of it. */
    public static final class Spot {
        /** The run's own index: the old snapshot's whole reading, and the fall-back. */
        public int legacyIdx = -1;
        /** The as-built step this one is found from, or −1 (the first of its block). */
        public int src = -1;
        /** How many steps past `src` (or past its block's first) it is, in its block. */
        public int ahead;
        /** Its block (ComingSteps#key), −1 when unknown. */
        public int block = -1;
        /** The size of the plan as built, −1 when unknown. */
        public int built = -1;
        /** Blocks Coming steps skipped. */
        public final List<Integer> skipped = new ArrayList<Integer>();
        /** As-built steps the Skip button took out. */
        public final List<Integer> taken = new ArrayList<Integer>();
        /** The run's earlier parts - what they played, and their filed record if any - so the
         *  rejoined run files as ONE session (RunParts); null for none known. */
        public RunParts prior;

        /** Whether this snapshot can be read in the run's own shape. */
        public boolean known() { return built > 0 && block >= 0; }

        public String skippedCsv() { return csv(skipped); }
        public String takenCsv() { return csv(taken); }

        /** Read back from what the snapshot holds; a key it does not hold reads −1 / "". */
        public static Spot read(int legacyIdx, int src, int ahead, int block, int built,
                                String skippedCsv, String takenCsv) {
            Spot s = new Spot();
            s.legacyIdx = legacyIdx;
            s.src = src;
            s.ahead = Math.max(0, ahead);
            s.block = block;
            s.built = built;
            s.skipped.addAll(ints(skippedCsv));
            s.taken.addAll(ints(takenCsv));
            return s;
        }
    }

    /** Where the run at `planIdx` of its own (edited) plan is. */
    public static Spot of(List<Model.Preset> plan, int planIdx, int builtSize,
                          Collection<Integer> skippedBlocks, Collection<Integer> takenSrc) {
        Spot s = new Spot();
        s.legacyIdx = planIdx;
        s.built = builtSize;
        if (skippedBlocks != null) s.skipped.addAll(skippedBlocks);
        if (takenSrc != null) s.taken.addAll(takenSrc);
        if (plan == null || planIdx < 0 || planIdx >= plan.size()) return s;
        s.block = ComingSteps.key(plan.get(planIdx));
        // Back through the block to the nearest step that has an as-built place - the step
        // itself, unless an edit built it - and count the steps past it.
        int j = planIdx;
        while (j > 0 && plan.get(j).src < 0 && ComingSteps.inBlock(plan.get(j - 1), s.block)) j--;
        s.src = plan.get(j).src;
        s.ahead = planIdx - j;
        return s;
    }

    /** The rebuild, in the run's shape. */
    public static final class Rebuilt {
        /** Where to resume; −1 when nothing fits. */
        public int idx = -1;
        /** The plan's size after the skips: "of Y". */
        public int size;
        /** The blocks taken out, by key - Coming steps' own record, so it can put one back. */
        public final Map<Integer, List<Model.Preset>> skipped =
            new HashMap<Integer, List<Model.Preset>>();
        /** The as-built steps taken out by the Skip button's record. */
        public final List<Integer> taken = new ArrayList<Integer>();
    }

    /**
     * Puts `fresh` - the run rebuilt and {@link #stamp}ed - back into the shape `s` describes
     * and says where to resume in it. Mutates `fresh`. The step resumed is never taken out.
     */
    public static Rebuilt apply(List<Model.Preset> fresh, Spot s) {
        Rebuilt out = new Rebuilt();
        out.size = fresh == null ? 0 : fresh.size();
        out.idx = s == null ? -1 : s.legacyIdx;
        if (fresh == null || s == null || !s.known() || fresh.size() != s.built) return out;
        int at = -1;
        if (s.src >= 0 && s.src < fresh.size()) {
            if (ComingSteps.inBlock(fresh.get(s.src), s.block)) at = s.src;
        } else if (s.src < 0) {
            for (int i = 0; i < fresh.size() && at < 0; i++)
                if (ComingSteps.inBlock(fresh.get(i), s.block)) at = i;
        }
        if (at < 0) return out;                       // not this routine's shape: the index
        for (int k = 0; k < s.ahead && at + 1 < fresh.size()
                && ComingSteps.inBlock(fresh.get(at + 1), s.block); k++) at++;
        Model.Preset playing = fresh.get(at);
        for (int i = fresh.size() - 1; i >= 0; i--) {
            Model.Preset p = fresh.get(i);
            if (p != playing && s.taken.contains(Integer.valueOf(p.src))) {
                fresh.remove(i);
                if (!out.taken.contains(Integer.valueOf(p.src))) out.taken.add(Integer.valueOf(p.src));
            }
        }
        for (int k = 0; k < s.skipped.size(); k++) {
            int key = s.skipped.get(k).intValue();
            if (key == s.block) continue;
            List<Model.Preset> gone = new ArrayList<Model.Preset>();
            for (int i = fresh.size() - 1; i >= 0; i--)
                if (ComingSteps.inBlock(fresh.get(i), key)) gone.add(0, fresh.remove(i));
            if (!gone.isEmpty()) out.skipped.put(Integer.valueOf(key), gone);
        }
        out.idx = fresh.indexOf(playing);
        out.size = fresh.size();
        return out;
    }

    static String csv(Collection<Integer> v) {
        StringBuilder b = new StringBuilder();
        if (v == null) return "";
        for (Integer i : v) {
            if (i == null) continue;
            if (b.length() > 0) b.append(',');
            b.append(i.intValue());
        }
        return b.toString();
    }

    /** "3,65537" read back; anything that is not a whole number is left out. */
    static List<Integer> ints(String csv) {
        List<Integer> out = new ArrayList<Integer>();
        if (csv == null || csv.trim().length() == 0) return out;
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) {
            try {
                out.add(Integer.valueOf(Integer.parseInt(parts[i].trim())));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }
}
