package org.openpump;

/**
 * The two decisions behind handing the pump's BLE link to the diagnostic console, kept
 * pure so test.sh can execute them — SessionActivity, where the handoff actually lives,
 * carries `import android` and is never compiled by the assertion harness.
 *
 * Both decisions are one-liners. They are here rather than inline precisely BECAUSE they
 * are one-liners: each was previously written inline, each was wrong, and neither failure
 * was visible in anything the harness could run.
 *
 * WHY (a) EXISTS — {@link #needsStop}. The gate was `running` alone. onDestroy() already
 * states the rule that misses: "the hold and the run are independent (heldKpa can be
 * outstanding on its own, from the standardisation flow, with no run active at all)".
 * Settings' "Preview the hold ›" is exactly that path — exitStdHold() deliberately leaves
 * heldKpa set until telemetry PROVES the vent (S1), so the user can be standing on Today
 * with the cuff still under vacuum, tap Diagnostics, and have the link released without a
 * question asked. "Nothing is running" is not the same claim as "nothing is under vacuum",
 * and this function is where the difference is written down.
 *
 * WHY (b) EXISTS — {@link #next}. "Stop and open" called finishSession(true) and then, in
 * the SAME main-thread turn, link.disconnect() -> gatt.close(). PumpLink#tx writes
 * WRITE_TYPE_NO_RESPONSE: the bytes are QUEUED in the BLE stack, not transmitted, and
 * close() discards a queued write. So the user tapped a button that says "Stop", believed
 * the session was stopped, and could be left with a running pump and no link to it. The
 * link must stay up until the stop is CONFIRMED — Task 10's rule that a write is not a
 * vent (see Session#ventResult) binds here the same as everywhere else.
 *
 * The ordering trap in {@link #next} is real and is the reason it is a function rather
 * than a chain of ifs at the call site: SessionActivity's VentWatcher reports
 * resolved() == true for BOTH outcomes — VENTED and UNCONFIRMED — since it means "the
 * window produced a definite answer". Test resolved() first and a perfectly good vent is
 * put to the user as a failure. VENTED is checked first here, and SelfTest pins that with
 * next(pending, vented=true, resolved=true), the exact state a successful vent produces.
 */
public final class Handoff {

    private Handoff() { }

    /**
     * Is pressure COMMANDED right now? A run, a seal check, a standardisation hold, or a
     * tissue adaptation assessment pull.
     *
     * This is only half the safety question, and fix round 2 exists because round 1 asked
     * it as though it were the whole of it. It describes what the app has told the pump to
     * do; it says nothing about a stop already issued and never evidenced. Use
     * {@link #stillUnsafe} for "may the pump be under vacuum" — this one only for wording
     * a dialog that has to distinguish "stop the session" from "vent the hold".
     *
     * `assessing` (Task 15) is TODAY implied by `running`: the assessment only ever runs
     * inside the run flow, which sets `running` true from beginRunFlow() onwards. It is
     * named as its own term anyway, and asked here rather than ORed on at one call site,
     * for the reason round 2 exists — a gate fed by a flag that only one path remembers
     * to add is not a gate. Nothing enforces `assessing implies running`, and a caller
     * that asks this predicate directly must get the right answer without also having to
     * know which other flags happen to be set alongside it.
     *
     * `validating` (Task 16) is the hardware validation routine — the one phase in this
     * app that commands the pump UNATTENDED, at up to the configured ceiling, for minutes
     * at a time, with `running` false throughout (it is not a session and files nothing).
     * It is a term HERE rather than ORed on at SessionActivity#stillUnsafe for the reason
     * the paragraph above gives, and the reason the Task 15 merge learned the hard way: a
     * term added at one call site is a second copy of the rule in a second place, and the
     * next caller to ask this predicate directly gets an answer that is quietly missing a
     * way the pump can be under pressure. Adding it here means Back, Home/Recents,
     * onDestroy and the Diagnostics handoff all learned about the routine at once.
     */
    public static boolean needsStop(boolean running, boolean sealChecking,
                                    boolean holdOutstanding, boolean assessing,
                                    boolean validating) {
        return running || sealChecking || holdOutstanding || assessing || validating;
    }

    /**
     * THE one source of truth for "a stop was issued and telemetry has not evidenced the
     * fall" — the state every unsafe-release path found in round 2 turned out to share.
     *
     * Round 1 carried this in a boolean that exactly ONE code path ever set (the handoff's
     * own ASK branch), so the ordinary route missed it completely. A flag only one writer
     * sets is not a source of truth; it is a record of one path's opinion.
     *
     *   stopOutstanding  a stop has been issued and nothing has SETTLED the question
     *                    since. Set when a vent watch is armed; cleared only by the two
     *                    things that genuinely settle it without evidence — see below.
     *                    Without this term the predicate reads TRUE on a freshly launched
     *                    app that has never commanded anything.
     *   vented           telemetry evidenced the fall. The only thing that ends this
     *                    honestly.
     *
     * ROUND 3 REMOVED A THIRD TERM, and the removal IS the fix. It used to also take
     * `dismissed` — "cancelVentWatch() ran" — on the reasoning that a cancel means either
     * re-pressurising or a human abandoning the stop. But cancelVentWatch() had come to
     * mean three different things:
     *
     *   1. "I am about to re-pressurise, so this watch is moot"  — settles it: a new,
     *      deliberate pressure state exists, and the commanded terms of {@link #stillUnsafe}
     *      take over from this one.
     *   2. "a human knowingly abandoned this stop"               — settles it: they were
     *      told exactly what was unproven and chose to proceed.
     *   3. "stop polling"                                        — settles NOTHING. The
     *      pump's state is exactly what it was; only the app stopped looking.
     *
     * Treating (3) as safe is what let exitRelease() discard the evidence and then bail to
     * an idle Today screen with the cuff still at the hold pressure. Worse than a race:
     * `!link.isReady()` is simultaneously why the vent could not be evidenced AND why the
     * run refused to start, so the two are positively correlated. `dismissed` is therefore
     * no longer a term at all, and SessionActivity spells the difference in the method
     * names — cancelVentWatch() for (1) and (2), stopVentPollingKeepingEvidence() for (3).
     *
     * Note what is ALSO not a term: whether the window has RESOLVED. An unevidenced stop
     * counts from the instant it is issued, not from the moment the window gives up.
     *
     * ── WHAT NO ASSERTION IN THIS FILE CAN PIN ──────────────────────────────────────
     * These parameters are correct; whether SessionActivity still FEEDS them correctly is
     * not visible from here, and round 3 proved that empirically rather than in the
     * abstract. A reviewer appended `&& ventWatcher.resolved()` to
     * SessionActivity#ventUnevidenced's call of this method — reintroducing the
     * mid-window release hole in the app's actual gate — and got 585 passed, 0 failed,
     * WIRING OK and a clean build. Round 2's suite carried an assertion claiming to guard
     * exactly that; it was a restatement of the line above it and could never have failed.
     *
     * ROUND 4 widens that admission, because round 3's own wording was too narrow. It
     * conceded that this predicate's CALL SITE is unguarded, and left the impression that
     * the round-3 fix itself was covered. It is not — it is unguarded in exactly the same
     * way, and the re-review demonstrated it with two more mutations, each of which left
     * the suite fully green:
     *
     *   - swapping stopVentPollingKeepingEvidence() back to cancelVentWatch() at
     *     SessionActivity#exitRelease — i.e. reinstating the original Critical;
     *   - zeroing ventStopOutstanding inside stopVentPollingKeepingEvidence() — i.e.
     *     keeping the honest NAME while restoring the dishonest BEHAVIOUR.
     *
     * The second is the more instructive: the fix's whole substance is which of two
     * similarly-named methods a call site picks and what one of them does to one field,
     * and NO assertion over this file can see either. What is machine-checked here is the
     * shape of the rule. What is not machine-checked is every part of the app that decides
     * when to apply it.
     *
     * So: nothing here guards the CALL SITES — not this predicate's, and not the
     * settle-vs-stand-down choice the wiring makes on every path. test.sh compiles only
     * sources with no `import android` line, and desktoptest/WiringCheck.java (the one
     * thing that could see them) is out of scope for this task and routed onward. They are
     * guarded by review and by this paragraph, and by nothing else. Anyone editing
     * SessionActivity#ventUnevidenced, cancelVentWatch, stopVentPollingKeepingEvidence, or
     * any call site of the latter two is changing unguarded safety logic.
     * ────────────────────────────────────────────────────────────────────────────────
     */
    public static boolean ventUnevidenced(boolean stopOutstanding, boolean vented) {
        return stopOutstanding && !vented;
    }

    /**
     * MAY THE PUMP BE UNDER VACUUM? The whole question, and the only one a path that is
     * about to give up the ability to stop the pump is allowed to ask.
     *
     * Two call sites, deliberately identical, because they are this app's two ways of
     * losing that ability:
     *
     *   - handOffToDiagnostics(), which releases the GATT link to the console. After
     *     link.close() nothing can be sent, and an outstanding vent watch degrades to a
     *     dead timer retrying forever over a null gatt.
     *   - onDestroy(), whose last-resort stop is the final chance to send anything at all.
     *     Round 1 had it asking needsStop's three flags, so a run that ended with its vent
     *     unevidenced — `running` already cleared by finishSession, heldKpa null because
     *     it belongs to the hold and not the routine — exited having sent nothing and
     *     logged nothing about it.
     *
     * `unevidenced` must come from {@link #ventUnevidenced}, never from a flag one path
     * happens to set. That distinction is the entire content of fix round 2.
     */
    public static boolean stillUnsafe(boolean running, boolean sealChecking,
                                      boolean holdOutstanding, boolean assessing,
                                      boolean validating, boolean unevidenced) {
        return needsStop(running, sealChecking, holdOutstanding, assessing, validating)
            || unevidenced;
    }

    /**
     * Is a diagnostics handoff still waiting on ITS OWN vent watch?
     *
     * Round 1 tracked this in a plain boolean, set when the handoff began and cleared only
     * by the two branches that finish it. Every other flow that cancels or supersedes a
     * vent watch — eleven call sites — dropped the handoff's callback without touching
     * that boolean, stranding it true for the life of the Activity and degrading the
     * Diagnostics button to a toast asserting a stop that was not happening. The recovery
     * route the app's own dialog names ("the console can then connect and stop the pump
     * directly") became unreachable until the process was killed.
     *
     * Derived rather than tracked: a handoff is pending exactly while its own update
     * object is still the live watch's callback AND that watch has not been dismissed. A
     * supersede reassigns the callback, a cancel sets dismissed — both self-clear, and
     * there is no flag left to strand.
     */
    public static boolean handoffPending(boolean haveHandoffWatch, boolean isLiveWatch,
                                         boolean dismissed) {
        return haveHandoffWatch && isLiveWatch && !dismissed;
    }

    /** Keep waiting: the evidence window has not produced an answer yet. */
    public static final int WAIT = 0;
    /** The vent is confirmed from telemetry — release the link and open the console. */
    public static final int RELEASE = 1;
    /** The window closed with no evidence of a fall — put the choice to the user. */
    public static final int ASK = 2;

    /**
     * What a vent-watch update means for a handoff in progress.
     *
     * @param pending  is a handoff actually waiting on this watch? Once the decision has
     *                 been made or handed to the user this is false, and every later
     *                 update is a no-op for the handoff — a watch that keeps retrying
     *                 underneath a dialog must never open the console behind the user's
     *                 back after they chose to stay.
     * @param vented   telemetry has evidenced the fall (VentWatcher#vented).
     * @param resolved the window produced a definite answer — TRUE FOR VENTED TOO
     *                 (VentWatcher#resolved), which is why `vented` is tested first.
     */
    public static int next(boolean pending, boolean vented, boolean resolved) {
        if (!pending) return WAIT;
        if (vented)   return RELEASE;
        if (resolved) return ASK;
        return WAIT;
    }

    /* ---------------------------------------------------- the START gate (wave 1 §1d) */

    public static final int START_OK = 0;
    /** "A session is already running — back to it". */
    public static final int START_BACK_TO_RUN = 1;
    /** "A session is getting ready — finish that first". */
    public static final int START_GETTING_READY = 2;
    /** "The last stop is not confirmed yet" — RECOVERABLE: re-check + (once the watch is
     *  exhausted) the user's own eyes, inline. */
    public static final int START_STOP_UNCONFIRMED = 3;
    /** Pressure is (or may be) commanded — a hold, a validation, an assessment. Not
     *  recoverable from the START button; whichever phase owns it has its own exit. */
    public static final int START_STILL_UNSAFE = 4;

    /** Which refusal (if any) START answers with, and which recovery actions the refusal
     *  offers. A value object, so the three START doors (routine confirm, the confirm
     *  funnel, the manual run) cannot each re-derive half the rule. */
    public static final class StartGate {
        public final int refusal;
        public final boolean offerRecheck;
        public final boolean offerSeenVented;
        StartGate(int refusal, boolean offerRecheck, boolean offerSeenVented) {
            this.refusal = refusal;
            this.offerRecheck = offerRecheck;
            this.offerSeenVented = offerSeenVented;
        }
    }

    /**
     * THE START-GATE DECISION, extracted from confirmStart/showStartConfirm/
     * confirmManualRun so SelfTest can pin every case — the three doors used to carry
     * three hand-copied fragments of it, and the fragments are how an app-restart-only
     * refusal shipped.
     *
     * `commanded` is "pressure is commanded by something other than the run itself":
     * Handoff.needsStop with `running` excluded, because a live run has its own refusal.
     * The recoverable case is UNEVIDENCED AND NOT COMMANDED: a stop went out, telemetry
     * never confirmed the fall, and nothing has commanded pressure since — a fresh
     * StopWork into that pump is harmless (finishSession's own doc argues exactly this),
     * so the honest answer is to look again, not to refuse forever.
     *
     * `offerSeenVented` only once `ventExhausted`: the same rule addVentSeenBtn states —
     * offering a human override while the app is still gathering evidence invites it to
     * be used instead of the evidence.
     */
    /**
     * D2 - IS A MEASUREMENT HOLD STILL COMMANDING PRESSURE, for the START gate?
     *
     * A hold's pressure (Session#heldKpa) is cleared only by telemetry showing the fall.
     * Once its vent has been SENT and not confirmed (`ventUnevidenced`), what is outstanding
     * is a stop, not a hold - START's recoverable case, which re-checks and, once the watch
     * has given up, offers the person's own eyes. Counted as "commanded" it was the one
     * refusal START cannot recover from, and only an app restart let a run start
     * (HoldReleaseGateTest). A hold with no stop sent for it is still holding.
     */
    public static boolean holdCommanded(boolean holdOutstanding, boolean ventUnevidenced) {
        return holdOutstanding && !ventUnevidenced;
    }

    public static StartGate startRefusal(boolean running, boolean guidedStarting,
            boolean sealChecking, boolean assessBusy, boolean ventUnevidenced,
            boolean commanded, boolean ventExhausted) {
        if (running) return new StartGate(START_BACK_TO_RUN, false, false);
        if (guidedStarting || sealChecking || assessBusy)
            return new StartGate(START_GETTING_READY, false, false);
        if (commanded) return new StartGate(START_STILL_UNSAFE, false, false);
        if (ventUnevidenced)
            return new StartGate(START_STOP_UNCONFIRMED, true, ventExhausted);
        return new StartGate(START_OK, false, false);
    }
}
