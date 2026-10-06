package org.openpump;

/**
 * FIX11 F1 - START NEVER OFFERS TO RUN A ROUTINE WITH NO HOLDS IN IT.
 *
 * After the plan notice's Undo put back stages whose sets the rewrite had deleted, Today showed
 * "0 cycles" and START asked "This RUNS THE PUMP: 0 cycles, 7:30" - the rests, and nothing to
 * hold. START already refused a routine with no presets at all; a routine whose only presets
 * are rests (every set it names gone, or never added) slipped past that, because a rest is a
 * preset. The question START asks now is the one the confirm states: how many working cycles
 * it runs (Model#workCycles, the count Today and the confirm print). None, and it says why
 * instead of offering to start - and, for a routine the plan wrote, offers to rebuild it from
 * the plan.
 */
public final class NothingToRun {
    private NothingToRun() { }

    /** True when `r` would command no hold at all: no working cycle in it. */
    public static boolean is(Model m, Model.Routine r) {
        return m != null && r != null && m.workCycles(r) == 0;
    }

    public static final String REBUILD = "Rebuild from the plan";

    /** "Girth L3 has nothing to run". */
    public static String title(Model.Routine r) {
        String nm = r == null || r.name == null || r.name.trim().length() == 0
            ? "This routine" : r.name.trim();
        return nm + " has nothing to run";
    }

    /**
     * Why, in words: the sets it names that are gone, or that it has none - and what to do,
     * `planOwned` when the plan wrote it (rebuild from the plan) or the person did (Routines).
     */
    public static String why(Model m, Model.Routine r, boolean planOwned) {
        int missing = PlanUndo.missingSets(m, r);
        StringBuilder b = new StringBuilder("It has no holds to run");
        if (missing > 0)
            b.append(": ").append(missing).append(missing == 1 ? " of its sets is" : " of its sets are")
             .append(" missing, so it would run only its rests");
        b.append(". ");
        b.append(planOwned
            ? "Rebuild it from the plan to get the plan's routine back. Nothing runs until then."
            : "Open it in Routines and add sets to its stages. Nothing runs until then.");
        return b.toString();
    }
}
