package org.openpump;

import java.util.List;

/**
 * "READY TO PROGRESS?" (T1 A, dist/round7-options.html's decisions list — "after 5 clean
 * ≥95% completions, one-tap suggested step-up (creates a copy)") — the per-routine
 * clean-streak trigger and the "already offered" identity behind Today's step-up card.
 *
 * WHY THIS IS ITS OWN FILE, NOT PART OF Milestones.java. Milestones.java's own class doc
 * states, plainly, that nothing in that file is earned by running the pump deeper — "an
 * app built to escalate a physical stimulus applied to a person, dressed as a game" — and
 * every existing consistency-triggered thing in that file (the schedule-kept milestones,
 * the weekly insight) pays out in an acknowledgment, a badge, a line of text. None of them
 * offers to raise the physical intensity itself. T1 is the first case in this app where a
 * consistency signal triggers exactly that kind of offer, styled with the same reward-card
 * visual language Milestones.java's own cards use. A version of this file's logic that
 * lived inside Milestones.java would make that file's own stated principle — "nothing here
 * is earned by depth" — no longer literally true of everything written in it, whatever the
 * doc comment around it claimed. Kept apart instead, so Milestones.java's own class doc
 * stays true of everything actually inside that file, and this file carries its own,
 * separately-reasoned justification for the one thing it is allowed to do that
 * Milestones.java's own design deliberately refuses.
 *
 * T1 A ITSELF IS NOT OPEN FOR RENEGOTIATION HERE — it is a locked, adopted product
 * decision in round7-options.html's own decisions list, made before this stage's
 * execution began. What this file resolves is narrower: WHERE the logic behind it lives,
 * so it can exist without quietly contradicting a principle stated elsewhere.
 *
 * WHAT KEEPS THIS DEFENSIBLE AS A DELIBERATE, NAMED EXCEPTION RATHER THAN A QUIET
 * REVERSAL of Milestones.java's own "not by depth" principle:
 *   - the TRIGGER is CONSISTENCY, never a reading of pressure, dose or peak — {@link
 *     #cleanStreak} counts a run of sessions that each cleared {@link #STEP_UP_MIN_RATIO}
 *     of what was PLANNED, never how deep or how long any of them actually ran;
 *   - the ACTION it feeds is a single fixed, small, capped step ({@link
 *     Model.Set#STEP_UP_KPA}, reclamped to the exact ceiling the set editor itself
 *     enforces — see {@link Model.Set#stepUp}), never a dial and never an escalating
 *     series the app pushes on its own;
 *   - it is OFFERED once per qualifying streak and does nothing unless tapped —
 *     declining, or simply not tapping, is the default outcome and leaves the original
 *     routine completely untouched.
 * This is a judgement call, not a settled fact, recorded here for whoever next has to
 * decide whether the trade stands.
 *
 * "5 CLEAN COMPLETIONS IN A ROW" IS READ OFF Sess#presetsDone/#presetsPlanned — the same
 * ratio SessionActivity's session-detail sheet already prints as a percentage — never off
 * Sess#completed, which asks a different question (did the run stop early) than "how much
 * of what was planned actually landed" does.
 *
 * WHY THE WALK STARTS AT log.get(0): {@code Model.SessLog#file} inserts at index 0, so the
 * log is already newest-first — walking it in its own order from the front IS walking in
 * reverse chronological order, the same fact {@link Milestones#isNewHoldRecord} already
 * relies on. A MANUAL session ({@code Sess#manual}) is skipped, never counted and never a
 * break — the same exclusion {@code Summary#of} and the calendar streak already apply,
 * because a manual cycle is not a completion of whatever routine id happens to be on the
 * record. A session for a DIFFERENT routine id is likewise skipped, not a break —
 * "filtered by routineId" per the task's own spec, so alternating between two routines
 * never resets either one's count.
 *
 * NO `import android` — a pure derivation over {@link Model.Sess}, so test.sh compiles it
 * into the desktop self-test exactly like {@link Milestones} itself already is.
 */
public final class Progression {
    private Progression() { }

    public static final int STEP_UP_STREAK_LEN = 5;

    /** The completion ratio a session must clear to count toward
     *  {@link #STEP_UP_STREAK_LEN} — the exact 0.95 the task spec states, read against
     *  {@code Sess#presetsDone}/{@code Sess#presetsPlanned}. */
    public static final double STEP_UP_MIN_RATIO = 0.95;

    /** {@link #STEP_UP_MIN_RATIO} restated as whole percent (95) — computed once via
     *  Math.round rather than compared as a double on every call, so a session sitting
     *  at EXACTLY 95% can never be excluded by binary floating-point's own inability to
     *  represent 0.95 exactly (0.95 as a double is not precisely 19/20, and comparing
     *  presetsDone directly against presetsPlanned * 0.95 risked landing a hair on
     *  either side of a real 19-of-20 session depending on rounding direction — the
     *  exact class of bug a whole-percent, integer cross-multiplication below avoids
     *  entirely). */
    private static final long STEP_UP_MIN_PCT = Math.round(STEP_UP_MIN_RATIO * 100.0);

    /** Whether `s` clears {@link #STEP_UP_MIN_RATIO} of its planned presets — false,
     *  never a crash, when presetsPlanned is 0 (nothing was ever planned, so nothing
     *  was cleared). Compares by exact integer cross-multiplication
     *  (`presetsDone * 100 >= 95 * presetsPlanned`) rather than dividing or comparing
     *  against a double ratio, so a session sitting at exactly the 95% line is decided
     *  the same way every time, with no floating-point rounding either side of it. */
    private static boolean cleanCompletion(Model.Sess s) {
        return s != null && s.presetsPlanned > 0
            && (long) s.presetsDone * 100L >= STEP_UP_MIN_PCT * (long) s.presetsPlanned;
    }

    /**
     * The length of the CURRENT unbroken run of clean completions for `routineId`,
     * walking `log` from its own front (newest first) and stopping at the first
     * SAME-routine session that misses {@link #STEP_UP_MIN_RATIO} — see this file's own
     * class doc for why a different routine's session, or a manual run, is skipped
     * rather than treated as a break. 0 for a null/blank routineId or an empty log,
     * never a crash.
     */
    public static int cleanStreak(List<Model.Sess> log, String routineId) {
        if (log == null || routineId == null || routineId.length() == 0) return 0;
        int n = 0;
        for (int i = 0; i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s == null || s.manual || !routineId.equals(s.routineId)) continue;
            if (!cleanCompletion(s)) break;
            n++;
        }
        return n;
    }

    /**
     * The timestamp of the OLDEST session in the run {@link #cleanStreak} counts for
     * `routineId` — the identity {@link Model#stepUpKey} keys the one-time card's shown
     * state on. STABLE while the streak keeps growing: a new clean session landing at
     * the FRONT of the log does not move this value, because the walk below still stops
     * at the same break (or the same end of log) it always did — it only changes once
     * the run this reads has actually broken and a fresh one has started. 0 when the
     * streak is empty (no session meeting the ratio has ever been filed for this
     * routine at all).
     */
    public static long cleanStreakStartTs(List<Model.Sess> log, String routineId) {
        if (log == null || routineId == null || routineId.length() == 0) return 0L;
        long start = 0L;
        for (int i = 0; i < log.size(); i++) {
            Model.Sess s = log.get(i);
            if (s == null || s.manual || !routineId.equals(s.routineId)) continue;
            if (!cleanCompletion(s)) break;
            start = s.ts;
        }
        return start;
    }

    /**
     * S19 (owner ruling, 2026-09-26) - WHETHER `r` MAY EVER BE OFFERED THIS CARD AT ALL.
     *
     * A trainer-minted routine already has a progression authority: the ladder in {@link
     * Plan#evaluate}, walked from the Trainer tab, moves its pressure, its sets and its
     * level on its own signals (yield, strain, fatigue, the calendar). This file's own
     * class doc explains why T1 is allowed to exist beside that at all - CONSISTENCY,
     * never depth, triggers it - but consistency triggering a SECOND, uncoordinated nudge
     * on a pull the trainer is already governing is exactly the "second progression path"
     * that reasoning does not cover. A routine still carrying a track ({@link
     * Model.Routine#trainerTrack} != {@link Model#TRAINER_TRACK_NONE}) is one the trainer
     * governs, so it gets no offer here.
     *
     * Reads the one field that already answers it rather than a list of routine ids: a
     * routine's copies start unmarked ({@code trainerTrack} is deliberately never
     * inherited by {@link Model.Routine#copy} or {@link Model.Routine#stepUpCopy} - see
     * either's own doc) whether or not this method exists, so nothing here changes what a
     * stepped-up COPY is eligible for next.
     *
     * False for a null routine, never a crash.
     */
    public static boolean eligibleForStepUp(Model.Routine r) {
        return r != null && r.trainerTrack == Model.TRAINER_TRACK_NONE;
    }
}
