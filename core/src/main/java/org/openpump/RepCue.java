package org.openpump;

/**
 * WHICH REPETITION OF A ROUTINE SHOULD SOUND, and whether this one does — the whole
 * decision behind the run screen's repetition cue, kept PURE (no `import android`) so
 * test.sh compiles it and SelfTest can pin its edges. That is the same split {@link
 * Haptic} and {@link Tone} already draw: the question here, the ToneGenerator call in
 * SessionActivity#playTone.
 *
 * WHAT A REPETITION IS HERE. The plan's own cycles, by the rule that prints "cycles X of Y"
 * on the summary: every preset contributes {@link Manual#cycles}, a rest contributes none,
 * and a stitch chunk contributes none because it is PART of a repetition rather than one
 * (Model.Preset#cyclePart). A ten-interval block is ten repetitions whether the plan wrote
 * it as ten presets or as one set that runs for the whole block.
 *
 * IT IS DELIBERATELY THE SUMMARY'S RULE AND NOT THE NET-WORK ONE. Net counting (Model#
 * setsInPreset) drops the warm-up, the fatigue block and the retention hold, because a
 * YIELD TARGET must not measure work it does not govern. But the number a person reads off
 * their own summary counts all of them, and a cue is answering that person's question: a
 * routine whose summary says 14 must not refuse "Repetition 12" as out of range.
 *
 * THE SCHEDULE IS FROZEN WHEN THE RUN BEGINS, from the ROUTINE rather than from the live
 * playback list: a hold, a +30 s and pressure-gated timing all lengthen the preset that is
 * playing, and a resume rewrites the presets it skipped to zero. A denominator read fresh
 * from any of that grows or shrinks under the cue, which is what moved "the last
 * repetition" out from under it and made it fire once per cycle for a whole pause.
 *
 * AND THE POSITION IS COUNTED, NOT RECONSTRUCTED. {@link #under} is given the time the run
 * has actually spent ARMED inside the preset — accumulated a heartbeat at a time by the
 * caller — never a length minus a deadline. The difference is the whole defect class the
 * first cut shipped: a deadline belongs to the table upload for 600 ms after a rest (so the
 * subtraction read as the end of the next block), a rejoin sets it fresh (so it read as that
 * preset's last repetition), an edit to an upcoming preset moves it, and +30 s walks it
 * backwards. Armed time can do none of those things: it only ever adds, and only while the
 * pump is actually being driven through this preset — not through a hold, not through a rest
 * the user inserted, and not through the settle in which a table is rewritten.
 *
 * WHY A SEPARATE PREFERENCE FROM {@link Model#toneCues}. The three tone cues mark TIME —
 * a preset's last three seconds, a preset changing, the run ending — and somebody who
 * wants to know only "this is the last one" should not have to take the other three to get
 * it. So this is its own setting with its own value, and {@link Tone#shouldSound} is asked
 * with THIS preference rather than the tone-cue toggle. The ringer and Do Not Disturb gate
 * still applies unchanged: it is the phone's request for quiet, and it outranks both.
 *
 * THE VALUE IS ONE INT because the choice is one choice: {@link #OFF}, {@link #LAST}, or a
 * repetition number. A sentinel rather than a second boolean field, so a save can never
 * hold "on, but at no repetition" or "at repetition 4, but off".
 */
public final class RepCue {
    private RepCue() { }

    /** No cue. The default, and what every save written before this feature loads as. */
    public static final int OFF = 0;

    /** Cue on the routine's LAST repetition — the one most people want and the one whose
     *  number they cannot know in advance, since it differs per routine and per level. */
    public static final int LAST = -1;

    /** "No repetition is the target" — {@link #targetRep}'s answer when nothing should
     *  ever sound. Not a valid repetition: repetitions are numbered from 1. */
    public static final int NONE = 0;

    /** The largest repetition a person can ask for, and the bound that keeps a hand-edited
     *  save from holding a number no interface offered. Two digits covers the routines the
     *  trainer writes; a hand-built routine of short cycles CAN hold more than ninety-nine
     *  repetitions, and on one of those the numbers past this bound are unreachable —
     *  {@link #LAST} still names its end exactly. */
    public static final int MAX = 99;

    /** A stored value, made safe. Anything outside OFF / LAST / 1..MAX becomes OFF rather
     *  than a silent surprise: a save file is user-editable, and the only honest reading of
     *  a value this class cannot name is "no cue was chosen". */
    public static int clampPref(int pref) {
        if (pref == LAST) return LAST;
        if (pref >= 1 && pref <= MAX) return pref;
        return OFF;
    }

    /**
     * The repetition this preference points at for a routine of `plannedTotal`
     * repetitions, or {@link #NONE} when nothing should sound.
     *
     * A ROUTINE OF ONE REPETITION HAS NO "which one" TO ANNOUNCE, and a cue on it would
     * fire at the moment the work starts — the same reason the run screen's own row prints
     * a plain cycle rather than "REP 1 OF 1". So one repetition, or none at all, is
     * silence.
     *
     * A NUMBER PAST THE END IS SILENCE, not the end. Asked for repetition 12 of a
     * ten-repetition routine, the honest answer is that it never arrives; sounding on the
     * tenth instead would be this class answering a question it was not asked, and the
     * person who wanted the last one has {@link #LAST} to say so exactly.
     */
    public static int targetRep(int pref, int plannedTotal) {
        if (plannedTotal <= 1) return NONE;
        int p = clampPref(pref);
        if (p == OFF) return NONE;
        if (p == LAST) return plannedTotal;
        return p <= plannedTotal ? p : NONE;
    }

    /**
     * Should the cue fire on this tick?
     *
     * @param pref          the stored preference (OFF / LAST / a number)
     * @param repNow        the repetition now under way, 1-based
     * @param plannedTotal  how many the routine holds
     * @param lastCued      the target this run has already sounded, or {@link #NONE}
     *
     * EXACTLY AT, NOT AT OR PAST. A tone whose whole meaning is "this is repetition 3"
     * must not sound while the run is at repetition 12, and it could: SKIPPING a preset
     * jumps the position over the target in one step, which is a thing the run screen
     * offers a button for. So the target has to be the repetition UNDER WAY.
     *
     * The caller owns the other half of that rule: when the position has PASSED the target
     * without this returning true, it latches the target silently, so the moment is spent
     * rather than saved up to be announced somewhere it would be wrong. See
     * SessionActivity#tickRepCue, which is the only caller.
     *
     * ONCE PER RUN. `lastCued` is the debounce, exactly as {@link Haptic#markFor} takes the
     * mark it last ticked. The position only ever moves FORWARD — armed time accumulates,
     * and a skip moves to a later preset — so one remembered target is enough for a whole
     * run. Nothing reads a deadline, which is what used to let it move both ways.
     */
    public static boolean due(int pref, int repNow, int plannedTotal, int lastCued) {
        int target = targetRep(pref, plannedTotal);
        return target != NONE && repNow == target && lastCued < target;
    }

    /**
     * HOW MANY REPETITIONS THE ROUTINE HAS FINISHED before `presetIdx` — the caller's
     * answer when nothing is under way (a rest, a stitch chunk, a preset not yet armed).
     * A latch set to this can never announce a repetition already behind you, and can never
     * suppress one still ahead.
     */
    public static int before(int[] cyclesPerPreset, int presetIdx) {
        int n = 0;
        if (cyclesPerPreset == null) return 0;
        int end = presetIdx > cyclesPerPreset.length ? cyclesPerPreset.length : presetIdx;
        for (int i = 0; i < end; i++)
            if (cyclesPerPreset[i] > 0) n += cyclesPerPreset[i];
        return n;
    }

    /**
     * HOW MANY REPETITIONS THE ROUTINE HOLDS, from the frozen per-preset counts. Negative
     * entries (which nothing writes, but an array is an array) count as none rather than
     * subtracting from the total.
     */
    public static int total(int[] cyclesPerPreset) {
        int n = 0;
        if (cyclesPerPreset == null) return 0;
        for (int i = 0; i < cyclesPerPreset.length; i++)
            if (cyclesPerPreset[i] > 0) n += cyclesPerPreset[i];
        return n;
    }

    /**
     * THE REPETITION NOW UNDER WAY, 1-based, or {@link #NONE} when none is.
     *
     * NONE is a real answer, not a failure: a rest, a stitch chunk and anything outside the
     * plan are all moments when the routine is not performing a repetition, and a cue that
     * spoke then would announce one that has not started. The caller's rule is simply
     * "NONE means say nothing".
     *
     * @param cyclesPerPreset  frozen per-preset counts; 0 for a rest or a stitch chunk
     * @param presetIdx        the preset playing
     * @param armedMs          how long the run has actually been ARMED inside that preset,
     *                         accumulated by the caller. Never a length minus a deadline:
     *                         see the class doc for the four ways that reconstruction broke
     * @param cycleSec         that preset's own cycle in seconds (hold + drop), floored at 1
     */
    public static int under(int[] cyclesPerPreset, int presetIdx, long armedMs, int cycleSec) {
        if (cyclesPerPreset == null || presetIdx < 0 || presetIdx >= cyclesPerPreset.length)
            return NONE;
        int here = cyclesPerPreset[presetIdx];
        if (here <= 0) return NONE;
        long cycleMs = Math.max(1L, (long) cycleSec) * 1000L;
        long e = armedMs < 0 ? 0L : armedMs;
        long inside = e / cycleMs;
        /* THE REMAINDER OF THE LAST CYCLE IS STILL THE LAST CYCLE - the same clamp the run
         * screen's REP row applies to its own count. The two NUMBERS still differ, and are
         * meant to: that row says "REP 3 OF 10" WITHIN the preset on screen, this says which
         * repetition of the whole routine you are on. Same clamp, different question. */
        if (inside > here - 1) inside = here - 1;
        return before(cyclesPerPreset, presetIdx) + (int) inside + 1;
    }

    /** What the stored value is called on the Settings row and in the spoken name for it.
     *  Here rather than in the screen because {@link #clampPref}'s reading of an unknown
     *  value has to be the one the row prints — a row saying "Repetition 400" over a
     *  preference that is actually OFF would be the screen and the model disagreeing. */
    public static String label(int pref) {
        int p = clampPref(pref);
        if (p == OFF) return "Off";
        if (p == LAST) return "Last repetition";
        return "Repetition " + p;
    }
}
