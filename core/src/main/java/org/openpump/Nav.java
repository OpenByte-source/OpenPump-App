package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * The navigation model, made PURE (no `import android`) so test.sh compiles it and
 * SelfTest can assert on it — the whole point of Task 17's "put the decidable parts in a
 * pure class so the harness reaches them". An Android app with no XML layouts has all of
 * its navigation expressed as method calls that a desktop JVM cannot run; everything about
 * WHERE a screen sits and WHETHER a move between screens is safe is decidable from state
 * alone, and it lives here where it can be tested rather than in SessionActivity where it
 * cannot.
 *
 * Four things are modelled, exactly the four the brief names:
 *   1. which of the four bottom-bar destinations owns which screen (destinationOf);
 *   2. which screens are INSIDE the session flow, where the bar is absent (inSessionFlow);
 *   3. whether a given move is a GIVE-UP that must ask the safety question (isGiveUp);
 *   4. the computed step sequence for a settings/cadence state, and its total (steps/…).
 *
 * (4) is the highest-value part: it is the number the user reads on the run screen's step
 * indicator ("Step 4 of 5"), it depends on four independent inputs, and getting it wrong
 * tells a person "3 of 5" when there are six steps. It is computed ONCE up front so the
 * count can never change underneath the user mid-run — a moving count is worse than none.
 *
 * Nothing here commands the pump or decides safety; SessionActivity still owns
 * stillUnsafe() and every vent. This only says which destination a screen belongs to and
 * whether a move needs the gate SessionActivity already implements.
 */
public final class Nav {
    private Nav() { }

    /* ---- the five persistent destinations (the bottom bar) ------------------ */
    /* STAGE H TASK 3 adds TRAINER as the 5th — the progression guide's own tab (plan §UX
     * "New: Trainer tab (5th bottom-nav destination)"). Appended after the original four
     * rather than inserted, so DEST_COUNT growing from 4 to 5 is the only renumbering. */

    public static final int TODAY = 0, LIBRARY = 1, PROGRESS = 2, SETTINGS = 3, TRAINER = 4;
    public static final int DEST_COUNT = 5;

    /** The bar's labels, in bar order. Also the contentDescription root each tab exposes
     *  to accessibility (SessionActivity appends ", selected"/", not selected"). */
    public static final String[] DEST_LABEL =
        { "Today", "Library", "Progress", "Settings", "Trainer" };

    /**
     * THE ORDER THE BAR DRAWS THEM IN, which is no longer the order they are numbered in.
     *
     * Settings sat fourth because it was the fourth destination the app ever had, and
     * Trainer was appended after it. That put the one tab you touch least between two you
     * touch daily, and left the app's newest surface at the far end where nothing else
     * lives. Settings is the drawer you open occasionally; it belongs at the end.
     *
     * The IDS ARE UNTOUCHED. They are switch labels, map keys and screen tags all over the
     * app, so renumbering them to reorder a row of buttons would be five files of risk for
     * a visual change. The bar iterates this instead - the one place that cares about
     * position now says so out loud.
     */
    public static final int[] BAR_ORDER = { TODAY, LIBRARY, PROGRESS, TRAINER, SETTINGS };

    /* ---- every screen, tagged to a destination or to the flow ---------------- */
    /* The ids are stable small ints so a screen can carry its own id and ask Nav which
     * destination should light up, and whether the bar should be shown at all. */

    public static final int
        // TODAY
        SCR_TODAY          = 0,
        SCR_MANUAL         = 1,   // the Manual-run editor (Task 18) — a Today front door
        SCR_LOG_READING    = 3,   // the standalone "Log a reading" front door (Task 19) —
                                  // reachable from Today AND Progress; no routine, no
                                  // session. In its AT-REST mode it commands no pressure and
                                  // keeps the tab bar; its STANDARDISE-FIRST mode reuses the
                                  // hold, so the capture screen there is entered via enterFlow
                                  // (bar absent) exactly like the session baseline.
        // The guided CONNECTION screen (Task 18). Deliberately NOT tagged to any
        // destination below: it is a modal-ish overlay reached from the header's pump
        // chip, the Settings Device section and the link-lost screen, and it removes the
        // tab bar itself (destinationOf returns -1, so inSessionFlow is true) so its "Not
        // now" is the one way out. It commands no pressure, so leaving it is always safe.
        SCR_CONNECT        = 2,
        // LIBRARY — the routines/sets place and every editor pushed within it
        SCR_LIBRARY        = 10,
        SCR_ROUTINES       = 11,
        SCR_SETS           = 12,
        SCR_ROUT_EDIT      = 13,
        SCR_STAGE_EDIT     = 14,
        SCR_SET_EDIT       = 15,
        SCR_PICKER         = 16,
        SCR_ASSESS_EDIT    = 17,
        SCR_USED_IN        = 18,  // Library › set › Used in — every OCCURRENCE of one set
                                  // across the routines, with the switches / replace tools.
                                  // A Library screen with a Back arrow, NOT session flow:
                                  // nothing here commands the pump, leaving is no give-up.
        // PROGRESS — the three past-tense screens, unified
        SCR_PROGRESS       = 20,
        SCR_SESSION_HIST   = 21,
        SCR_MEAS_HIST      = 22,
        SCR_COMPARE        = 23,
        SCR_MEAS_EDIT      = 24,
        SCR_SAVE_CARD      = 25,   // the full-screen "save what you ran" card opened from a
                                   // History row — a Progress screen with a Back arrow, NOT a
                                   // session-flow screen: the run is over, leaving it is no give-up
        SCR_EXPORT         = 26,   // the Export sheet (§8 #9) — period/format/photos, then the
                                   // share chooser. A Progress screen with a Back arrow; it
                                   // commands nothing, leaving it is no give-up
        SCR_GALLERY        = 27,   // the photo gallery — every photo, grouped by day. A
                                   // Progress screen with a Back arrow; it reads files and
                                   // deletes them at the user's explicit request, and
                                   // commands nothing
        SCR_PHOTO          = 28,   // one photo full-size, with its reading's numbers and the
                                   // Delete button. Pushed from the gallery, returns to it
        // SETTINGS — configuration plus the Device section's tools
        SCR_SETTINGS       = 30,
        SCR_DIAGNOSTICS    = 31,
        SCR_VALIDATE_INTRO = 32,
        SCR_HELP           = 33,  // "How this app works" — one scrollable screen of prose,
                                  // opened from the top of Settings. A Settings screen with
                                  // a Back arrow: it reads nothing, writes nothing and
                                  // commands nothing, so leaving it is never a give-up.
        // TRAINER (Stage H Task 3) — one screen id for the whole tab: the un-enrolled
        // intro card, the onboarding step sequence and the enrolled main view all render
        // through this single destination, exactly as SCR_SAVE_CARD covers every step of
        // the review wizard. None of the three commands the pump, so a tab switch away
        // mid-onboarding is never a give-up.
        SCR_TRAINER        = 40,
        // THE SESSION FLOW — the bar is ABSENT for every one of these
        SCR_HOLD           = 50,
        SCR_BASELINE       = 51,
        SCR_CAMERA         = 52,
        SCR_RELEASE        = 53,
        SCR_SEAL           = 54,
        SCR_ASSESS         = 55,
        SCR_RUN            = 56,
        SCR_SUMMARY        = 57,
        SCR_MEASURE_AFTER  = 58,
        SCR_LINK_LOST      = 59,
        SCR_VALIDATE_RUN   = 60,
        // The guided-start screen ("Ready when you are"). A run's OWN screen: running is
        // already true underneath it (beginRunFlow), so a stop there must take the screen
        // rather than offer a dialog over its frozen body — same family as 99df148's
        // SCR_ASSESS. In the flow like SCR_HOLD: destinationOf -1, parentOf NO_PARENT,
        // both via the default branches.
        SCR_GUIDED         = 61,
        // THE FIRST-RUN SETUP (0.10): six steps, shown once to a new phone before Today. No
        // destination (destinationOf -1), so no tab lights and the bar is hidden: the setup's
        // Next and Back are the way through it. Its Back walks the steps and, from the first,
        // leaves the app with the setup still owed (parentOf QUIT). It commands no pressure.
        SCR_SETUP          = 62;

    /**
     * Which bottom-bar destination owns a screen, or -1 when the screen is inside the
     * session flow (where there is no bar). This is what decides which tab lights up when
     * a pushed editor is on screen: the set editor is Library's, the compare screen is
     * Progress's, so the bar keeps telling the truth about where the user is even three
     * pushes deep.
     */
    public static int destinationOf(int screen) {
        switch (screen) {
            case SCR_TODAY: case SCR_MANUAL: case SCR_LOG_READING:
                return TODAY;
            case SCR_LIBRARY: case SCR_ROUTINES: case SCR_SETS: case SCR_ROUT_EDIT:
            case SCR_STAGE_EDIT: case SCR_SET_EDIT: case SCR_PICKER: case SCR_ASSESS_EDIT:
            case SCR_USED_IN:
                return LIBRARY;
            case SCR_PROGRESS: case SCR_SESSION_HIST: case SCR_MEAS_HIST:
            case SCR_COMPARE: case SCR_MEAS_EDIT: case SCR_SAVE_CARD: case SCR_EXPORT:
            case SCR_GALLERY: case SCR_PHOTO:
                return PROGRESS;
            case SCR_SETTINGS: case SCR_DIAGNOSTICS: case SCR_VALIDATE_INTRO:
            case SCR_HELP:
                return SETTINGS;
            case SCR_TRAINER:
                return TRAINER;
            default:
                return -1;      // in the flow
        }
    }

    /* ---- where BACK goes ---------------------------------------------------- */

    /** {@link #parentOf}'s answer for TODAY: there is nothing above it, so Back leaves the
     *  app. The one screen in the whole app where Back is allowed to quit. */
    public static final int QUIT = -1;
    /** {@link #parentOf}'s answer for a session-flow screen: this model does NOT decide
     *  where Back goes there. Those screens are owned by the give-up gate, which vents and
     *  files an abort, and nothing here may quietly navigate past it. */
    public static final int NO_PARENT = -2;

    /**
     * WHERE BACK GOES from a screen, as a pure function of which screen it is.
     *
     * The defect this exists to fix: Back on any screen outside a live run fell through to
     * super.onBackPressed(), which finishes the single Activity — so Back from the set
     * editor, from Used-in, from a report, from Settings, from anywhere at all, QUIT THE
     * APP. There is one Activity and every "screen" is a re-render of one body view, so
     * there was no system back stack to walk; this table is that stack, derived rather
     * than accumulated so it can never desynchronise from where the user actually is.
     *
     * Three tiers, and they are the three the brief names:
     *   · a SUB-SCREEN returns to the screen that pushes it (set editor → Sets, Used-in →
     *     that set's editor, stage editor → its routine, save card / export → History);
     *   · a TAB ROOT returns to TODAY, which is the app's home and the only place a person
     *     can be said to have "finished";
     *   · TODAY returns {@link #QUIT}.
     *
     * Session-flow screens answer {@link #NO_PARENT}. They are not part of this model:
     * SessionActivity's give-up gate handles Back there and this must never be able to
     * route around it.
     */
    public static int parentOf(int screen) {
        switch (screen) {
            case SCR_TODAY: case SCR_SETUP:
                return QUIT;
            // Today's own front doors, and the guided connect overlay — all one level down.
            case SCR_MANUAL: case SCR_LOG_READING: case SCR_CONNECT:
                return SCR_TODAY;
            // The five tab roots (Today's own is handled above — this is the other four:
            // Library, Progress, Settings, Trainer). SCR_SESSION_HIST is Progress's
            // rendered root (showProgress renders the session history), so it is a root
            // here and not a sub-screen.
            case SCR_LIBRARY: case SCR_ROUTINES: case SCR_SETS:
            case SCR_PROGRESS: case SCR_SESSION_HIST: case SCR_SETTINGS:
            case SCR_TRAINER:
                return SCR_TODAY;
            // Library's pushed editors.
            case SCR_SET_EDIT:    return SCR_SETS;
            case SCR_USED_IN:     return SCR_SET_EDIT;
            case SCR_ROUT_EDIT:   return SCR_ROUTINES;
            case SCR_STAGE_EDIT:  return SCR_ROUT_EDIT;
            case SCR_PICKER:      return SCR_STAGE_EDIT;
            case SCR_ASSESS_EDIT: return SCR_ROUT_EDIT;
            // Progress's pushed screens.
            case SCR_MEAS_HIST:   return SCR_PROGRESS;
            case SCR_COMPARE:     return SCR_PROGRESS;
            case SCR_MEAS_EDIT:   return SCR_MEAS_HIST;
            case SCR_SAVE_CARD:   return SCR_SESSION_HIST;
            case SCR_EXPORT:      return SCR_SESSION_HIST;
            // The gallery hangs off Progress; one photo hangs off the gallery, so Back from
            // a photo returns to the grid it was opened from rather than to the whole
            // Progress screen — a delete confirmed on the photo must land the user back
            // where the deleted tile was, so they can see it is gone.
            case SCR_GALLERY:     return SCR_SESSION_HIST;
            case SCR_PHOTO:       return SCR_GALLERY;
            // Settings' Device section.
            case SCR_DIAGNOSTICS: case SCR_VALIDATE_INTRO: case SCR_HELP:
                return SCR_SETTINGS;
            default:
                return NO_PARENT;
        }
    }

    /** True when Back from this screen navigates within the app rather than leaving it.
     *  Exactly "it has a parent that is a screen" — TODAY and the flow screens do not. */
    public static boolean backNavigates(int screen) {
        int p = parentOf(screen);
        return p != QUIT && p != NO_PARENT;
    }

    /** True when a screen is part of a live session flow — the tab bar must be ABSENT
     *  (removed, not disabled) for exactly these, so the flow's single exit through the
     *  give-up path is unmistakable and nobody wanders into Library mid-run. */
    public static boolean inSessionFlow(int screen) {
        return destinationOf(screen) < 0;
    }

    /**
     * Whether a move is a GIVE-UP — a navigation away from the current screen that
     * relinquishes the ability to keep commanding or warning about the pump, and so must
     * ask the same question onBackPressed()/onStop() ask before it is allowed.
     *
     * The rule is deliberately simple and state-driven: ANY move that actually leaves the
     * current screen while the pump may be unsafe is a give-up. Staying put (dest already
     * current, and not leaving the flow) is not a move and not a give-up. The tab bar is
     * absent during the flow, so the reachable case in practice is a tab tap while an
     * earlier session's vent is still unevidenced — but modelling it on `stillUnsafe`
     * rather than on "am I in the flow" is what makes it belt-and-braces: every new door
     * the bar adds is gated by the same fact, not by where the door happens to be.
     */
    public static boolean isGiveUp(int fromScreen, int toDest, boolean stillUnsafe) {
        if (!stillUnsafe) return false;
        boolean leaving = inSessionFlow(fromScreen) || destinationOf(fromScreen) != toDest;
        return leaving;
    }

    /**
     * WHETHER A RUN'S END TAKES THE SCREEN - or is only offered, in a dialog, over wherever the
     * person is.
     *
     * Offering is right for somebody who has wandered off to read a list. It is wrong for
     * anybody still standing on one of the RUN'S OWN screens, because those stop the moment
     * the run does: left up, they freeze on their last frame, and a frozen screen of a live
     * run cannot be told from a live one.
     *
     * THREE OF THEM. The run screen; its link-lost alarm state, seen on a device reading "the
     * pump may still be running" beside an idle pump; and the AFTER-ASSESSMENT, which is the
     * run's last step and reaches the run's end however it ends - measured, skipped, or
     * refused because the vent never settled.
     *
     * That third one was missing, and it was found on a device: the run ended on the
     * assessment screen, "Session complete" was offered over it, and "Not now" uncovered a
     * countdown frozen at "1 s", a "Skip the assessment" with nothing left to skip, no tab bar
     * (the assessment is a step flow, so the finished-run bar the dialog promised is never
     * drawn), and a Back that left the app. The only way out was "Cancel - end and vent",
     * which stopped an already-vented pump a second time to get there.
     *
     * Holding for a measurement takes the screen from anywhere, and is decided at the call
     * site (SessionActivity#endOfRunHandoff), because it also decides whether work in
     * progress on another screen has to be protected first. This answers only "is this one
     * of the run's own screens".
     *
     * D2 - AND THE SEAL CHECK (the final safety review). It runs with `running` true, and a
     * run ending on it - its result left unanswered for a minute, a link loss, a STOP - left
     * the screen up under a dismissible "Session stopped" dialog; after "Not now" its
     * "Continue to session" and "Reseat and re-check" commanded the pump outside any attempt.
     */
    public static boolean watchesTheRun(int screen) {
        return screen == SCR_RUN || screen == SCR_LINK_LOST || screen == SCR_ASSESS
            || screen == SCR_GUIDED || screen == SCR_SEAL;
    }

    /* ---- the computed step sequence ----------------------------------------- */

    /** The step ids, in the fixed order they can occur in a flow. A step's POSITION in a
     *  particular run is its index within {@link #steps}; these are just identities. */
    public static final int
        STEP_HOLD          = 0,   // standardisation hold — conditional
        STEP_BASELINE      = 1,   // baseline measurement — conditional
        STEP_RELEASE       = 2,   // release gate — conditional
        STEP_SEAL          = 3,   // seal check — ALWAYS
        STEP_ASSESS_BEFORE = 4,   // tissue assessment, before — conditional
        STEP_RUN           = 5,   // the routine — ALWAYS
        STEP_ASSESS_AFTER  = 6,   // tissue assessment, after — conditional
        STEP_SUMMARY       = 7;   // the summary — ALWAYS

    /** The word shown for each step in the indicator, matching the mockup's "Running"
     *  wording. Both test steps read the test's own name - they are the same act at two
     *  ends of the run, and their position in the sequence already says which. (M4: it
     *  was "Assessment"; the owner named the test the "Tissue response test".) */
    private static final String[] STEP_LABEL = {
        "Standardise", "Baseline", "Release", "Seal check",
        TauSay.NAME, "Running", TauSay.NAME, "Summary"
    };

    public static String stepLabel(int stepId) {
        return (stepId < 0 || stepId >= STEP_LABEL.length) ? "" : STEP_LABEL[stepId];
    }

    /**
     * The ordered steps of a session flow for a given settings/cadence state — the real
     * sequence, computed before the run starts so the indicator's total is fixed.
     *
     * The four inputs are exactly the four the real flow branches on, and each is read
     * from the same place SessionActivity reads it:
     *   measDue      — model.measLog.due(model.meas): is a baseline asked for this session?
     *   stdOn        — model.std.on: is the standardisation hold enabled?
     *   assessBefore — Tau.runsBefore(routine): does the tissue assessment run before?
     *   assessAfter  — Tau.runsAfter(routine): does it run after?
     *
     * The ORDER mirrors the code exactly: beginSession() runs the hold, then the baseline,
     * then the release gate (all three only when a measurement is due), before
     * beginRunFlow() reaches the seal check; the before-assessment sits between the seal
     * check and the run; the after-assessment between the run and the summary. The hold
     * and the release gate BOTH need measDue as well as stdOn, because std.on with no
     * measurement due skips the whole measure branch (beginSession goes straight to
     * beginRunFlow) — a std hold exists only to standardise a measurement.
     *
     * NOTE ON CAMERA. The brief lists "camera" among the conditional steps. It is
     * deliberately NOT a numbered step here: photo capture is a modal, re-entrant
     * sub-screen launched from the baseline screen (its Front/Side slots are shown
     * regardless of any setting, and can be entered, retaken and left any number of
     * times), so numbering it would make the total change underneath the user on every
     * retake — the one thing the brief says is worse than no indicator at all. It is part
     * of the Baseline step, not a step of its own.
     */
    public static int[] steps(boolean measDue, boolean stdOn,
                              boolean assessBefore, boolean assessAfter) {
        List<Integer> s = new ArrayList<Integer>(8);
        if (measDue && stdOn) s.add(Integer.valueOf(STEP_HOLD));
        if (measDue)          s.add(Integer.valueOf(STEP_BASELINE));
        if (measDue && stdOn) s.add(Integer.valueOf(STEP_RELEASE));
        s.add(Integer.valueOf(STEP_SEAL));                          // always
        if (assessBefore)     s.add(Integer.valueOf(STEP_ASSESS_BEFORE));
        s.add(Integer.valueOf(STEP_RUN));                           // always
        if (assessAfter)      s.add(Integer.valueOf(STEP_ASSESS_AFTER));
        s.add(Integer.valueOf(STEP_SUMMARY));                       // always
        int[] out = new int[s.size()];
        for (int i = 0; i < out.length; i++) out[i] = s.get(i).intValue();
        return out;
    }

    /** The total number of steps in the flow — the "of N" the user reads. */
    public static int total(boolean measDue, boolean stdOn,
                            boolean assessBefore, boolean assessAfter) {
        return steps(measDue, stdOn, assessBefore, assessAfter).length;
    }

    /** The 1-based position of a step id within a computed sequence, or -1 if that step
     *  is not part of this flow. This is the "Step X" the indicator shows. */
    public static int positionOf(int[] steps, int stepId) {
        if (steps == null) return -1;
        for (int i = 0; i < steps.length; i++) if (steps[i] == stepId) return i + 1;
        return -1;
    }

    /** The indicator's whole line for a step: "Step 4 of 5 · Running". Returns "" if the
     *  step is not in this flow, so a screen that is somehow off-sequence shows nothing
     *  rather than a wrong number. */
    public static String stepLine(int[] steps, int stepId) {
        int pos = positionOf(steps, stepId);
        if (pos < 0) return "";
        return "Step " + pos + " of " + steps.length + "  ·  " + stepLabel(stepId);
    }

    /* ---- C8: where a standardisation hold's time limit leaves the user ---------- */

    /** The routine ended into a hold and the summary owns it: redraw the summary. */
    public static final int LIMIT_SUMMARY    = 0;
    /** A capture screen (the log door's held capture, the baseline, the after screen):
     *  stay, keep everything typed, redraw it saying the hold ended and the reading will be
     *  saved at rest. */
    public static final int LIMIT_KEEP       = 1;
    /** The hold screen itself: leave it the way "Skip the hold" does, for its own next
     *  screen - with nothing typed yet, there is nothing there to keep. */
    public static final int LIMIT_LEAVE_HOLD = 2;
    /** A Settings preview: records nothing, so back to Settings. */
    public static final int LIMIT_PREVIEW    = 3;
    /** Anywhere else - the camera, the release gate, Today: the vent is the only change. */
    public static final int LIMIT_STAY       = 4;

    /**
     * C8 - WHERE THE HOLD LIMIT LEAVES THE USER.
     *
     * Every standardisation hold vents itself at the hold limit. What happened next used to
     * be one call, renderSummary(), written for the routine that ends INTO a hold - the
     * only hold whose screen is the summary. Every other hold is on the way to a reading:
     * from "Log a reading" and the pre-session hold and baseline the limit opened a
     * previous session's summary, "Session stopped", with that session's own questions
     * live - and the numbers being typed were gone (study problem 2, reproduced on the
     * emulator). The vent is unchanged; only the landing is decided here.
     *
     * `cameraShowing` wins over everything: the capture flow draws into the same body as
     * the screen that opened it, so redrawing that screen would throw the photo in hand
     * away. The capture screen redraws itself when the camera hands back, and says then
     * that the hold has ended.
     *
     * Pure, so HoldLimitLandingTest can hold the decision; WiringCheck invariant 52 holds
     * the Activity to asking it.
     */
    public static int holdLimitLanding(int screen, boolean cameraShowing, boolean preview) {
        if (cameraShowing) return LIMIT_STAY;
        switch (screen) {
            case SCR_SUMMARY:
                return LIMIT_SUMMARY;
            case SCR_HOLD:
                return preview ? LIMIT_PREVIEW : LIMIT_LEAVE_HOLD;
            case SCR_LOG_READING: case SCR_BASELINE: case SCR_MEASURE_AFTER:
                return LIMIT_KEEP;
            default:
                return LIMIT_STAY;
        }
    }
}
