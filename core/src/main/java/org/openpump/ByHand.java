package org.openpump;

/**
 * A STEP DONE BY HAND, NAMED WHILE IT PLAYS (the owner's decision, 2026-10-07).
 *
 * A length session that pulls opens with the tunica release, done by hand with the pump vented,
 * and may change tubes before its expansion. Both are manual REST stages (Model.Stage#manual),
 * and the run screen used to draw every rest the same way: the title "REST", the routine line
 * without the stage's name, Coming steps "Rest". So the one step that asks the person to DO
 * something was the one step the screen did not name. These are the words it uses instead -
 * pure, so they are tested, and the run screen, the status line, the notification and the
 * screen reader all read them from here.
 *
 * AND THE RELEASE WAITS FOR DONE. Its five minutes are a guide: the clock counts them down, and
 * at 0:00 the run does not move on to the warm-up by itself - it says "Done when you are" and
 * keeps waiting, with the pump vented and nothing commanded, until the person taps Done (which
 * they may do at any time). The changeover waits from its start as it always has (its
 * Model.Stage#awaitAck); the release is told apart from it by having no such flag, so no saved
 * field changes and a routine saved before this waits too.
 *
 * A plain rest is none of this: it stays "REST".
 */
public final class ByHand {
    private ByHand() { }

    /** The word the run uses for a by-hand step, where it used to say REST. */
    public static final String WORD = "BY HAND";
    /** What a stage name carries to say it is done by hand - dropped where WORD says it. */
    public static final String SUFFIX = " — by hand";
    /** The NOW card's line while the release plays. */
    public static final String VENTED_LINE = "Pump vented · do it by hand now";
    /** ...and once its guide time has run out. */
    public static final String WHEN_READY = "Done when you are";
    /** The changeover's line: its own instruction, with the same first fact. */
    public static final String SWAP_LINE =
        "Pump vented · swap the cylinder, then press “I’ve swapped”";
    /** The button that ends a by-hand step whose time is a guide. */
    public static final String DONE = "Done ›";
    /** The − / + strip's heading over a by-hand step. */
    public static final String HEAD = "BY HAND · CUFF VENTED";

    /* ---- NO CONTROL OR MESSAGE SAYS "REST" DURING IT (the coordinator's round 2) ---- */

    /** The +30 s button over a by-hand step: the time it adds to, unnamed. */
    public static final String PLUS = "+30 s";
    /** ...and what a screen reader says for it. */
    public static final String PLUS_SAID = "Thirty seconds more on this step done by hand";
    /** The − / + strip's length cell over a by-hand step ("Rest length" elsewhere). */
    public static final String STRIP_LABEL = "Time";
    /** ...and its name with "Less" / "More". */
    public static final String STRIP_SPOKEN = "time";
    /** Pause, greyed over a by-hand step, as a screen reader says it. */
    public static final String PAUSE_SAID = "Pause, not available by hand: the pump is vented.";

    /** What a tap on Pause says over a by-hand step: there is nothing to pause, and what ends
     *  it - Done for the release, I've swapped for the changeover. */
    public static String pauseTap(boolean swap) {
        return swap ? "The pump is vented \u2014 tap \u201cI\u2019ve swapped\u201d once the cylinder is changed."
                    : "The pump is vented \u2014 tap Done when you\u2019re finished.";
    }

    /** A rest's refusal, said for a by-hand step: its End rest is Done. Anything else as is. */
    public static String reword(String why) {
        if (why == null) return null;
        return why.replace("Use End rest to finish", "Use Done to finish");
    }

    /** The home-screen widget's name for the run: the by-hand step while one plays ("By hand ·
     *  Tunica release"), the routine's otherwise. Discreet: never the step. */
    public static String widgetName(String name, String phase, boolean discreet) {
        String p = phase == null ? "" : phase.trim();
        if (discreet || p.length() == 0) return name;
        // The changeover's notification line is too long for the widget's (E2-3).
        return SWAP_WAITING.equals(p) ? SWAP_WIDGET : p;
    }

    /** The widget's changeover line - short enough for its one line (E2-3). */
    public static final String SWAP_WIDGET = "By hand · swap cylinder · tap I’ve swapped";

    /* ---- THE START CHECK WAITS FOR THE FIRST PRESSURE (the owner's answer on H-2) ---- */

    /** Does `r` open with a step done by hand (its first stage a manual rest)? */
    public static boolean opensByHand(Model.Routine r) {
        if (r == null || r.stages.isEmpty()) return false;
        Model.Stage st = r.stages.get(0);
        return st != null && st.rest && st.manual;
    }

    /**
     * WHETHER THE START CHECK MOVES TO THE FIRST PRESSURE (the owner, 2026-10-07): a routine
     * that opens with a step done by hand starts straight on that step with the pump
     * uncommanded - no pull before a vented release - and the start check (the guided start,
     * or the seal check where it is on) runs when Done is tapped, right before the first
     * pressure. Only when a check would run at all (`guided` or `sealBefore`), and never with a
     * before-assessment due (`assessBefore`), whose own pull comes before stage 1 as it always
     * has. Every other routine starts exactly as before.
     */
    public static boolean deferStartCheck(Model.Routine r, boolean guided, boolean sealBefore,
                                          boolean assessBefore) {
        return opensByHand(r) && (guided || sealBefore) && !assessBefore;
    }

    /** With the check deferred, is `p` the step it must run before - the first that is not a
     *  vented rest (the first that can command pressure)? */
    public static boolean startCheckBefore(boolean deferred, Model.Preset p) {
        return deferred && p != null && !p.rest;
    }

    /**
     * THE START CHECK'S WORDS, MID-RUN (the coordinator, 2026-10-07): run after Done, the
     * routine has already started - it is the pump that starts once the cuff holds. At a
     * normal start (`midRun` false) every sentence is returned exactly as it is.
     */
    public static String startWords(String text, boolean midRun) {
        if (!midRun || text == null) return text;
        return text.replace("Before the routine starts", "Before the pump starts")
                   .replace("The routine starts", "The pump starts")
                   .replace("a routine that runs anyway", "a pump that pulls anyway")
                   .replace("start the routine anyway", "start the pump anyway")
                   .replace("starts the routine with no seal verdict",
                            "starts the pump with no seal verdict")
                   .replace("Continue to session", "Continue")
                   // E2-5: mid-run a minute unanswered ends the run, and it is filed.
                   .replace("the pump is released.", "the run ends and is saved.")
                   .replace("this start ends.", "the run ends and is saved.");
    }

    /**
     * IS A WAIT BY HAND LATE YET (E2-2)? Only once its planned time has passed: the
     * changeover's 2:00 and the release's 5:00 are the plan, and a wait inside them is not the
     * run running late - the "+m:ss", the Time cell and the predicted end hold still until
     * `plannedEndAt`.
     */
    public static boolean waitIsLate(long now, long plannedEndAt) {
        return now >= plannedEndAt;
    }

    /** "Time left" in whole seconds for a by-hand step that waits (E3-1): rounded up while its
     *  planned time runs, as every countdown here is, and 0 - never 0:01 - once that time has
     *  passed (`late`), however the held clock's few milliseconds fall. */
    public static int timeLeftSec(long leftMs, boolean late) {
        if (late) return 0;
        return (int) ((Math.max(0L, leftMs) + 999L) / 1000L);
    }

    /** What a run stopped at the start check after Done files as its minutes by hand (E3-2):
     *  the run's clock when Done was tapped - the by-hand step alone, never the check's own
     *  seconds - at least 1; 0 for any other ending. */
    public static long byHandStopSec(boolean aborted, boolean stoppedAtCheck, long secAtDone) {
        return aborted && stoppedAtCheck ? Math.max(1L, secAtDone) : 0L;
    }

    /** The Start confirm's first line for a routine that opens by hand (E2-1): what really
     *  comes first. `before` is what runs ahead of the routine (a measurement), "" for none,
     *  with its rough time already in it. */
    public static String startConfirmFirst(String stageName, String before) {
        String b = before == null ? "" : before.trim();
        String step = bare(stageName);
        if (step.length() == 0) step = "A step";
        String hand = step + ", by hand — the pump starts after you press Done";
        return "First: " + (b.length() == 0 ? hand : b + ", then " + hand);
    }

    /** What the summary says of a run stopped at the start check after its by-hand step
     *  (E2-4): where it stopped, and the minutes by hand - never "ran exactly to plan", and no
     *  peak set against nothing asked. */
    public static String stoppedAtCheck(long byHandSec) {
        return "Stopped at the start check after the step done by hand — "
            + Model.Fmt.t(byHandSec) + " by hand, with nothing commanded by the routine.";
    }

    /** Has nothing before step `idx` of `plan` been able to command pressure (every step
     *  before it a vented rest)? A run rejoined there has not had its first pressure - nor,
     *  when it opened by hand, its start check. */
    public static boolean noPressureBefore(java.util.List<Model.Preset> plan, int idx) {
        if (plan == null) return true;
        for (int i = 0; i < idx && i < plan.size(); i++)
            if (plan.get(i) != null && !plan.get(i).rest) return false;
        return true;
    }

    /** Is `p` a step done by hand - vented, commanding nothing? */
    public static boolean is(Model.Preset p) {
        return p != null && p.rest && p.manual;
    }

    /**
     * Is `p` THE RELEASE: a by-hand step whose time is a guide, that waits for Done once its
     * clock reaches 0:00? The changeover (Preset#awaitAck) is by hand too, but waits from its
     * start, so it is not this.
     */
    public static boolean waitsAfterClock(Model.Preset p) {
        return is(p) && !p.awaitAck;
    }

    /** "Tunica release — by hand" is "Tunica release": the title already says BY HAND. A name
     *  without the suffix (the changeover's sentence) is kept whole. */
    public static String bare(String name) {
        String n = name == null ? "" : name.trim();
        if (n.endsWith(SUFFIX)) n = n.substring(0, n.length() - SUFFIX.length()).trim();
        return n;
    }

    /** The NOW card's title: "BY HAND · Tunica release", "BY HAND · Swap to your girth
     *  cylinder — next is expansion at …"; "BY HAND" alone for a stage with no name. */
    public static String title(String name) {
        String n = bare(name);
        return n.length() == 0 ? WORD : WORD + " · " + n;
    }

    /** Coming steps' name for it: "Tunica release (by hand)". */
    public static String coming(String name) {
        String n = bare(name);
        return (n.length() == 0 ? "Step" : n) + " (by hand)";
    }

    /** The NOW card's line under the time: the release's own, or "Done when you are" once its
     *  guide time is up; the changeover's instruction for the changeover. */
    public static String nowLine(boolean swap, boolean timeUp) {
        return nowLine(swap, timeUp, true);
    }

    /* ---- "VENTED" ONLY ONCE THE PUMP HAS SHOWN IT (the device walk's H-6) ---- */

    /** What a by-hand step says until the reading confirms the vent: never "vented" first. */
    public static final String VENTING_LINE = "Venting…";

    /** ...the line, told whether the vent is CONFIRMED by the pump's reading: "Venting…"
     *  until it is, whatever the step. */
    public static String nowLine(boolean swap, boolean timeUp, boolean ventConfirmed) {
        if (!ventConfirmed) return VENTING_LINE;
        if (swap) return SWAP_LINE;
        return timeUp ? WHEN_READY : VENTED_LINE;
    }

    /** The strip's heading over a by-hand step: "BY HAND · VENTING" until the vent is
     *  confirmed, then HEAD. */
    public static String head(boolean ventConfirmed) {
        return ventConfirmed ? HEAD : WORD + " · VENTING";
    }

    /** The routine line's state: " · vented" only once confirmed. */
    public static String kickerState(boolean stillUp, boolean ventConfirmed) {
        return stillUp ? " · still under pressure"
                       : ventConfirmed ? " · vented" : " · venting";
    }

    /** Pause's tap, told whether the vent is confirmed: "The pump is venting — …" until it is. */
    public static String pauseTap(boolean swap, boolean ventConfirmed) {
        String s = pauseTap(swap);
        return ventConfirmed ? s : s.replace("The pump is vented", "The pump is venting");
    }

    /** Pause, greyed, as a screen reader says it - "venting" until the vent is confirmed. */
    public static String pauseSaid(boolean ventConfirmed) {
        return ventConfirmed ? PAUSE_SAID : PAUSE_SAID.replace("is vented", "is venting");
    }

    /** Done's screen-reader text - "venting" until the vent is confirmed. */
    public static String doneSaid(boolean ventConfirmed) {
        return "Done. The pump is " + (ventConfirmed ? "vented" : "venting")
            + " and nothing is commanded while you do this by hand; the next step starts "
            + "when you press this.";
    }

    /** The status line: "BY HAND · 4:32 LEFT", then "BY HAND · DONE WHEN YOU ARE". */
    public static String status(long leftMs, boolean timeUp) {
        return status(leftMs, timeUp, false);
    }

    /** ...and where the line is too narrow for the wait's words, "BY HAND · TAP DONE". */
    public static String status(long leftMs, boolean timeUp, boolean narrow) {
        if (timeUp) return WORD + " · "
            + (narrow ? "TAP DONE" : WHEN_READY.toUpperCase(java.util.Locale.US));
        return WORD + " · " + RunLook.left(leftMs) + " LEFT";
    }

    /** The strip's length cell once the release's time is up: what is left, 0:00, and fixed
     *  (the device walk's H-7) - the wait itself is the NOW card's "so far". */
    public static final String STRIP_TIME_UP = "Time left";
    /** The notification's and the widget's words while the changeover waits (H-5): no frozen
     *  "2:00 left", the same thing the screen says. */
    public static final String SWAP_WAITING =
        "By hand · Change cylinder · waiting — tap “I’ve swapped”";

    /** The notification's lead: "By hand · Tunica release", or "By hand · Done when you are"
     *  once the guide time is up. */
    public static String notification(String name, boolean timeUp) {
        return notification(name, timeUp, false);
    }

    /** ...and the changeover, which waits from its start: SWAP_WAITING. */
    public static String notification(String name, boolean timeUp, boolean swap) {
        if (swap) return SWAP_WAITING;
        if (timeUp) return "By hand · " + WHEN_READY;
        String n = bare(name);
        return n.length() == 0 ? "By hand" : "By hand · " + n;
    }
}
