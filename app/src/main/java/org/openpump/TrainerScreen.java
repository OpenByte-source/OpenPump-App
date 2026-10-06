package org.openpump;

import android.widget.FrameLayout;
import java.text.SimpleDateFormat;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** THE TRAINER TAB - the plan's own screen: its position, its cards, its decisions.
 *  Lifted out of SessionActivity verbatim - see
 *  docs/superpowers/plans/2026-09-07-sessionactivity-split.md. A screen class
 *  renders into the Activity's own body column and owns no state. */
final class TrainerScreen {
    private final SessionActivity a;

    TrainerScreen(SessionActivity a) { this.a = a; }

    /** Not one of the Trainer's views (the setup, the intro). */
    private static final int VIEW_OTHER = -100;
    /** The view drawn last, and the Trainer's own scroll offset when a sub-page opened. */
    private int drawnView = VIEW_OTHER - 1;
    private int mainScrollY;

    private final class ScrollTo implements Runnable {
        private final int y;
        private int tries;
        ScrollTo(int y) { this.y = y; }
        @Override public void run() {
            if (a.bodyScroll == null) return;
            // Once the page just drawn is laid out: a scroll clamps to the height it has.
            if (y > 0 && a.body.isLayoutRequested() && tries++ < 10) { a.bodyScroll.postDelayed(this, 16); return; }
            a.bodyScroll.scrollTo(0, y);
        }
    }

    /** The Trainer tab's single funnel — mirrors showHome()/showProgress()'s own shape. */
    void showTrainer() {
        // The day's step first, as Today does: the sync compares signatures that carry it.
        a.settleTaper(System.currentTimeMillis());
        a.syncPlanRoutines();
        // ARRIVING FROM ANOTHER TAB LANDS ON THE MAIN VIEW, never inside a track detail
        // or the settings sheet left open last time (F4). Same rule Progress follows for
        // its own tab state: a sub-view is where you went, not where you live.
        /* BOTH ARRIVAL RESETS, HERE, WHILE currentScreen STILL SAYS WHERE WE CAME FROM.
         *
         * The open-track reset lived in renderTrainerMain - which runs AFTER enterDest has
         * already set currentScreen to TRAINER, so its own test could never be true and a
         * row opened by a tap stayed open across every later visit to the tab, ignoring
         * whatever the plan had to say. */
        boolean arriving = Nav.destinationOf(a.currentScreen) != Nav.TRAINER;
        if (arriving) {
            a.trainerView = a.TRAINER_VIEW_MAIN;
            a.trainerOpenTrack = 0;
        }
        // F5 - "Adjust how it runs" lands on the card, not on a landing page. Read AFTER
        // the reset above, because the reset is what it exists to override, and cleared as
        // it is read so a later visit to the tab lands where a tab visit should.
        if (a.trainerLandOnShape) {
            a.trainerLandOnShape = false;
            a.trainerView = a.TRAINER_VIEW_SHAPE;
        }
        // A SUB-PAGE OPENS AT ITS TOP (device check EMU9 M1): it is drawn into the same scroll
        // as the Trainer, which kept the Trainer's offset, so "What it writes" opened part-way
        // down with its title and ‹ off screen. Back on the Trainer puts it where it was.
        int view = a.trainerOnboardActive || !a.model.trainerEnrolled ? VIEW_OTHER : a.trainerView;
        if (arriving) { mainScrollY = 0; drawnView = VIEW_OTHER - 1; }
        if (view != drawnView && a.bodyScroll != null) {
            if (drawnView == a.TRAINER_VIEW_MAIN) mainScrollY = a.bodyScroll.getScrollY();
            a.bodyScroll.post(new ScrollTo(view == a.TRAINER_VIEW_MAIN ? mainScrollY : 0));
        }
        drawnView = view;
        a.body.removeAllViews();
        a.enterDest(Nav.SCR_TRAINER);
        if (a.trainerOnboardActive) { renderTrainerOnboard(); return; }
        if (!a.model.trainerEnrolled) { renderTrainerIntro(); return; }
        renderTrainerMain();
    }

    /** Un-enrolled main view: the onboarding entry card, and nothing else (the edge
     *  state the plan's Task 3 line names explicitly) - or, for a paused plan, the way
     *  back to it (TrainerTab#canResume). */
    private void renderTrainerIntro() {
        Ui.head(a, a.body, "Trainer");
        if (TrainerTab.canResume(a.model)) { renderTrainerPaused(); return; }
        LinearLayout g = Ui.cardGroup(a, a.body, "Set up the trainer", null);
        Ui.noteInfo(a, g,
            "Answers a few short questions, then shows your position and this week's targets.",
            "What setup does",
            "Answers a few short questions about where you are now, then shows "
            + "your position and this week's targets for each track you run. Nothing here "
            + "saves a routine or changes what Today shows — that stays a separate, "
            + "deliberate step.");
        Button start = Ui.big(a, a.body, "Get started", Ui.ACCENT);
        start.setOnClickListener(new StartOnboardTap(false));

        // The considered entrance: the same first-paint cascade Today/Library/Progress/
        // Settings use (staggerBodyIn).
        a.staggerBodyIn();
    }

    /**
     * LEAVING THE PLAN (audit A27). trainerEnrolled was set true at onboarding and was
     * never written false anywhere in the app: there was no pause, no exit, no way to stop
     * short of Delete all data. Recalibrate was the only control offered, and repositioning
     * is not the same request as stopping.
     *
     * PAUSE, NOT ERASE. Every track's level, week and pressure stays exactly where it is,
     * and so does the decision history — coming back is "Resume", not a fresh onboarding,
     * and it re-derives nothing. Only the enrolment flag moves, so the tab falls back to its
     * own not-enrolled face and the reminders stop having a plan to speak for.
     */
    /**
     * Changing girth style WITHOUT losing where you are (audit A28). Plan.switchTrackMapping
     * is the engine's own statement of what carries: the level and the month stay, and only
     * the prescription remaps to the other style's tables. Recalibrate, the only route that
     * existed, throws the real position away and re-derives one from the months-and-pressure
     * answers \u2014 which is right for repositioning and wrong for this.
     */
    private final class SwitchGirthStyleTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            final int from = a.model.trainerGirthStyle;
            final int to = from == Plan.TRACK_GIRTH_INTERVAL
                ? Plan.TRACK_GIRTH_TRADITIONAL : Plan.TRACK_GIRTH_INTERVAL;
            Ui.dress(a, Ui.dialog(a)
                .setTitle(to == Plan.TRACK_GIRTH_INTERVAL
                    ? "Switch to interval girth?" : "Switch to traditional girth?")
                .setMessage("Your level and month carry over unchanged. Only the weekly "
                    + "prescription changes, to the other style's tables. This is not a "
                    + "recalibration \u2014 nothing is re-derived from your onboarding answers.")
                .setPositiveButton("Switch", new SwitchGirthStyleConfirm(from, to))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class SwitchGirthStyleConfirm implements DialogInterface.OnClickListener {
        private final int from, to;
        SwitchGirthStyleConfirm(int f, int t) { from = f; to = t; }
        @Override public void onClick(DialogInterface d, int w) {
            Model.TrainerTrackState g = a.model.trainerGirth;
            Plan.Decision dec = Plan.switchTrackMapping(from, to, g.level,
                Plan.monthIndex(g.weekIndex));
            a.model.trainerGirthStyle = to;
            // The week index is a position within the OLD style's table and means nothing in
            // the new one; the level and the pressure are what carry. Traditional has no week
            // table at all, so it starts the new style at its first week.
            g.weekIndex = 1;
            g.weekBaseIndex = 1;
            g.weekBaseMs = System.currentTimeMillis();
            g.carriedSets = 0;
            // A traditional build-up (the half start) was that style's: the new one has none.
            Mint.endBuildUp(g);
            // Yield sets were earned on the other style's sessions: they do not carry.
            g.yieldSets = 0;
            g.yieldSinceMs = g.weekBaseMs;
            Model.TrainerDecision rec = new Model.TrainerDecision();
            rec.track = to;
            rec.action = Plan.ACTION_HOLD;
            rec.tag = dec.tag;
            rec.rule = dec.rule;
            rec.reason = dec.reason;
            rec.ts = System.currentTimeMillis();
            rec.state = Model.TrainerDecision.STATE_ACCEPTED;
            a.model.addTrainerDecision(rec);
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, "Style switched \u2014 level carried");
            showTrainer();
        }
    }

    /** A decision, in full: which rule fired, when, and what was done about it (E5). */
    private final class DecisionDetailTap implements View.OnClickListener {
        private final Model.TrainerDecision d;
        DecisionDetailTap(Model.TrainerDecision dec) { d = dec; }
        @Override public void onClick(View v) {
            StringBuilder b = new StringBuilder();
            b.append(d.reason == null ? "(no reason recorded)" : Say.sentence(d.reason))
             .append("\n\n");
            // The engine's rule text is for a developer (polish TR-8).
            if (a.devUnlocked && d.rule != null && d.rule.length() > 0)
                b.append("Rule: ").append(d.rule).append("\n");
            b.append("Track: ").append(TrainerTab.trackLabel(d.track))
             .append("\n");
            b.append(Say.decisionAnswerLine(d.state)).append("\n");
            b.append("When: ").append(a.dayLabel(d.ts));
            Ui.dress(a, Ui.dialog(a)
                .setTitle(Say.capitalise(Say.actionLabel(d.action)))
                .setMessage(b.toString())
                .setPositiveButton("Close", null)
                .show());
        }
    }

    /**
     * A PAUSED PLAN (the owner's decision, 2026-09-27): "Resume where I was" keeps the
     * position and arms the gentle return (TrainerTab#resumePlan); "Set up again" is the
     * full setup, which places you from your answers.
     */
    private void renderTrainerPaused() {
        LinearLayout g = Ui.cardGroup(a, a.body, "Your plan is paused", null);
        // The buttons are the card's (polish TR-23): they were drawn below it, outside it.
        Ui.noteInfo(a, g,
            "What resuming does",
            "What resuming does",
            "Resume where you were, with a gentle return, or set up again.\n\nResume keeps " + pausedPosition() + ". The pressure is never higher than when "
            + "you paused. The first days back run lighter (the gentle return). If you have "
            + "been away a week or more, the plan still offers to step you back. Set up "
            + "again asks the setup questions and places you from your answers.");
        Button resume = Ui.big(a, g, "Resume where I was", Ui.ACCENT);
        resume.setOnClickListener(new ResumePlanTap());
        Button again = Ui.secondary(a, g, "Set up again");
        again.setOnClickListener(new StartOnboardTap(false));
        a.staggerBodyIn();
    }

    /** The girth position if girth is on, else the length one - what a resume keeps. */
    private String pausedPosition() {
        Model.TrainerTrackState st = a.model.trainerGirthOn
            ? a.model.trainerGirth : a.model.trainerLength;
        return TrainerTab.levelLabel(st.level) + ", week " + Math.max(1, st.weekIndex)
            + ", at " + Model.Fmt.p(Math.round(st.pressureKpa));
    }

    private final class ResumePlanTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.running) { a.refuse("Resuming the plan"); return; }
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Resume where you were?")
                .setMessage("Your plan comes back at " + pausedPosition() + ", never at a "
                    + "higher pressure. The first days back run lighter, as after a deload. "
                    + "If you have been away a week or more, the plan will also offer to step "
                    + "you back, as it does after any layoff.")
                .setPositiveButton("Resume", new ResumePlanConfirm())
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class ResumePlanConfirm implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            TrainerTab.resumePlan(a.model, System.currentTimeMillis());
            // resumePlan tells the week; saved as a schedule edit, so the reminders follow the
            // week back in force (review D, F2).
            a.schedSaved();
            Ui.snack(a, a.rootFrame, "Plan resumed — the first days back run lighter");
            showTrainer();
        }
    }

    private final class PausePlanTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Pause the plan?")
                .setMessage("Your level, week and pressure are kept exactly as they are, and "
                    + "so is your decision history. Nothing is deleted. Come back whenever "
                    + "you like with \u201cResume where I was\u201d: you pick up where you "
                    + "stopped, with a gentle return.")
                .setPositiveButton("Pause", new PausePlanConfirm())
                .setNegativeButton("Keep going", null)
                .show());
        }
    }

    private final class PausePlanConfirm implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            // The week is told at once and saved as a schedule edit (review D, F2).
            TrainerTab.pausePlan(a.model);
            a.schedSaved();
            Ui.snack(a, a.rootFrame, "Plan paused — your position is kept");
            showTrainer();
        }
    }

    private final class StartOnboardTap implements View.OnClickListener {
        private final boolean recalibrate;
        StartOnboardTap(boolean r) { recalibrate = r; }
        @Override public void onClick(View v) {
            /* NOT REFUSED DURING A RUN - TAKEN AND HELD.
             *
             * Recalibrating rewrites the whole plan: the level, the position, the working
             * pressure. It never touches the pump, so by the rule at lockedDuringRun it has
             * no business being refused. But the routine playing right now was minted from
             * the OLD plan, and a session should be filed against the plan it began under,
             * not one that arrived while it was in the air.
             *
             * So the request is remembered, the app says plainly that it is waiting, and the
             * run finishes untouched. It is then OFFERED rather than launched: onboarding is
             * several screens, and landing in it uninvited on the far side of a summary
             * would read as the app losing its place. */
            /* ONLY A RECALIBRATION IS DEFERRED. This same tap is also the intro card's
             * "Get started", and holding THAT would have promised a "recalibration" to
             * somebody who has no plan yet, and then offered it back through a card whose
             * button recalibrates - turning a first enrolment into a recalibration of
             * nothing. A first set-up mid-run is refused instead: onboarding is several
             * screens behind enterFlow, which would bury the run that is playing. */
            if (a.running && !recalibrate) { a.refuse("Setting the plan up"); return; }
            if (a.running) {
                a.model.pendingRecalibrate = true;
                Store.save(a, a.model);
                Ui.snack(a, a.rootFrame,
                    "Recalibration is set for after this run. This session still counts "
                  + "toward the plan you started it on.",
                    "Back to run", a.new BackToRunTap(), Snack.HOLD_MS_ACTIONABLE);
                showTrainer();
                return;
            }
            a.model.pendingRecalibrate = false;
            a.startTrainerOnboard(recalibrate);
        }
    }

    /**
     * The onboarding step sequence — the same step-navigation shape Stage C's review
     * wizard established (showReviewWizard(): a header naming the step, an N-segment
     * progress bar, the step's own panel, a Back/Next row until the last step, whose
     * own primary button lives inside its panel instead of a Next).
     */
    private void renderTrainerOnboard() {
        a.backAction = new OnboardBackTap();
        // Q17: RECALIBRATE repositions only — months, pressure, tracks. The variants,
        // rack and week keep their rooms; a full setup asks all six.
        int n = a.trainerRecalibrating ? 3 : 6;
        // Wave 3b: lazily seed the wizard-local Program copies and the week working set.
        if (a.trainerOnboardProgG == null) {
            a.trainerOnboardProgG = cloneProgram(a.model.programGirth);
            a.trainerOnboardProgL = cloneProgram(a.model.programLength);
        }
        if (a.trainerOnboardDays == null) {
            a.trainerOnboardDays = new boolean[7];
            /* A WEEK ALREADY CHOSEN IS THE WEEK (0.10): the first-run setup, or Settings, may
             * have set it minutes ago, and this step used to replace it with Mon/Wed/Fri at
             * 19:00 without a word. It now starts from the model and says so. */
            a.trainerOnboardWeekWasSet =
                TrainerOnboard.weekChosen(a.model.sched, a.model.trainerEnrolled);
            if (a.trainerOnboardWeekWasSet) {
                System.arraycopy(a.model.sched.days, 0, a.trainerOnboardDays, 0, 7);
                a.trainerOnboardHour = a.model.sched.hour;
                a.trainerOnboardRemind = a.model.sched.remind;
            } else {
                // The setup sheet has always SAID "three days a week" while the silent
                // default trained all seven — Mon/Wed/Fri is the sheet's own claim.
                // POLISH #1: Schedule's week is MON=0..SUN=6 (Schedule#weekdayOf), so
                // Mon/Wed/Fri is 0/2/4 — the old 1/3/5 wrote Tue/Thu/Sat while the
                // wizard's Sun-first labels showed Mon/Wed/Fri ticked.
                a.trainerOnboardDays[0] = a.trainerOnboardDays[2] = a.trainerOnboardDays[4] = true;
            }
        }
        /* t10 R-60 - LONG TRAINING DAYS, seeded once per setup: the choice already made once
         * enrolled (a rerun keeps the person's values), the new setup's default otherwise. */
        if (a.trainerOnboardLongDays < 0)
            a.trainerOnboardLongDays = a.model.trainerEnrolled ? a.model.sched.longDays
                                                               : Schedule.LONG_DAYS_NEW_SETUP;
        if (a.trainerOnboardStep < 0) a.trainerOnboardStep = 0;
        boolean confirm = a.trainerOnboardStep >= n;
        // LENGTH'S MAXIMUM STARTS FROM GIRTH'S (owner, 2026-09-30): untouched, it follows girth's
        // answer while the length track is on - on every step, so Confirm saves what was shown.
        a.trainerOnboardLengthMaxKpa = TrainerOnboard.lengthMaxSeed(a.trainerOnboardLengthOn,
            a.trainerOnboardLengthMaxTouched, a.trainerOnboardLengthMaxKpa, a.trainerOnboardMaxKpa);

        /* THE BAR AND BACK/NEXT STAY PUT WHILE THE STEP SCROLLS (0.10), as they do in the
         * first-run setup the owner liked: pinned around the scrolling body through
         * SessionActivity#pinStepChrome, not drawn in it. enterDest takes them away before
         * every draw of the Trainer, so leaving the setup by any door leaves them behind. */
        /* CONFIRM SITS WHERE "NEXT" SAT (polish SU-15): it was a lime button inside the card,
         * below a long scroll, while every other step's way forward is the pinned footer. */
        a.pinStepChrome(kit().stepBar(TrainerOnboard.BAR_TITLE, a.trainerOnboardStep, n,
                            TrainerOnboard.BAR_CONFIRM),
                        kit().footer(SetupText.BACK, true, new OnboardBackTap(),
                            confirm ? onboardConfirmLabel() : SetupText.NEXT, true,
                            confirm ? (View.OnClickListener) new CommitOnboardTap()
                                    : new OnboardNextTap()));
        if (a.trainerRecalibrating)
            Ui.note(a, a.body, "Recalibrating · your current answers are filled in");

        LinearLayout panel = Ui.cardGroup(a, a.body,
            trainerStepTitle(a.trainerOnboardStep, confirm), null);
        if (confirm) buildOnboardConfirm(panel);
        else buildOnboardStep(panel, a.trainerOnboardStep);

        a.staggerBodyIn();
    }

    /** The setup's last button, in the footer (polish SU-15). */
    private String onboardConfirmLabel() {
        return a.trainerRecalibrating && !onboardKeepsPosition() ? "Save recalibration"
                                                                 : "Confirm position";
    }

    private String trainerStepTitle(int step, boolean confirm) {
        if (confirm) return "Your starting point";
        switch (step) {
            case 0: return "Months pumping";
            case 1: return "Current working pressure";
            case 2: return "Tracks";
            case 3: return "Your cylinders";
            case 4: return "How your routines are built";
            default: return "Your week";
        }
    }

    private void buildOnboardStep(LinearLayout panel, int step) {
        switch (step) {
            case 0: buildOnboardStep0(panel); break;
            case 1: buildOnboardStep1(panel); break;
            case 3: buildOnboardCylinders(panel); break;
            case 4: buildOnboardProgram(panel); break;
            case 5: buildOnboardWeek(panel); break;
            case 2:
                buildOnboardStep2(panel);
                // The length pressure lives on the SAME step as the length switch, not on a
                // fourth one: it is a detail of a track you have just turned on, and asking
                // it on its own screen would make a question appear and disappear as the
                // switch is tried.
                buildOnboardLengthPressure(panel);
                break;
            default: break;
        }
    }

    private void buildOnboardStep0(LinearLayout panel) {
        Ui.noteInfo(a, panel, "How many months you have been pumping in total.",
            "Months pumping",
            "How many months you have been pumping in total. It places you on the plan’s "
            + "calendar instead of starting over at week one.");
        Ui.stepperRow(a, panel, "Months (0–360)",
            String.valueOf(a.trainerOnboardMonths),
            new BumpOnboardMonths(-1), new BumpOnboardMonths(+1));
        if (a.trainerOnboardPrefilled) {
            Ui.note(a, panel, a.trainerRecalibrating
                ? "Pre-filled with your months in total: what you answered at setup plus the "
                  + "whole months since. Change it if it is off."
                : "Pre-filled from your session log — change it if it is off.");
        } else if (!a.trainerRecalibrating) {
            Ui.note(a, panel, "No sessions logged yet, so this starts blank.");
        }
        onboardDetectedStats(panel);
        // Wave 3b (Q13a): the source steps a returning trainee back 1-2 weeks.
        Ui.kvRow(a, panel, "Any break of a week or more recently?", a.trainerOnboardLayoff,
            new ToggleOnboardLayoffTap());

    }

    /**
     * WHAT THE LOG ALREADY KNOWS (audit E6).
     *
     * Onboarding asks a person to place themselves from memory while the app is sitting on
     * the evidence: how often they have actually trained lately, and at what pressure. A
     * pre-filled number with no working shown is a number you cannot check, so it gets
     * accepted or overridden on a hunch \u2014 and the whole plan is built on top of it.
     *
     * Stated as an observation, never as a correction: the person may have trained
     * elsewhere, or paused, or be starting again after a year. The figures cover the last
     * four weeks, which is the window short enough to describe NOW rather than a history.
     */
    private void onboardDetectedStats(LinearLayout panel) {
        long now = System.currentTimeMillis();
        long since = now - 28L * 24L * 60L * 60L * 1000L;
        int sessions = 0;
        double peakSum = 0;
        int peakN = 0;
        java.util.HashSet<Long> days = new java.util.HashSet<Long>();
        for (int i = 0; i < a.model.sessLog.all.size(); i++) {
            Model.Sess s = a.model.sessLog.all.get(i);
            if (s == null || s.manual || s.ts < since) continue;
            if (!Summary.trainedDay(s)) continue;
            sessions++;
            days.add(Long.valueOf(Summary.dayNumber(s.ts)));
            if (s.peakKpa != null) { peakSum += s.peakKpa.doubleValue(); peakN++; }
        }
        if (sessions == 0) return;
        StringBuilder b = new StringBuilder("Your last four weeks: ");
        b.append(sessions).append(sessions == 1 ? " session" : " sessions")
         .append(" on ").append(days.size()).append(days.size() == 1 ? " day" : " days");
        if (peakN > 0)
            b.append(", peaking around ").append(Model.Fmt.p(peakSum / peakN));
        b.append('.');
        Ui.note(a, panel, b.toString());
        // F11 - the same third signal the position card shows, offered HERE too, because
        // this is the screen where a person places themselves from memory. Peak pressure
        // says how hard the sessions were; this says how much of them there actually was.
        double volMin = typicalTrackedNetMin(a.model.trainerGirthStyle);
        if (volMin > 0)
            Ui.note(a, panel, "A typical tracked session in that time delivered "
                + Say.fmtMin(volMin) + " min of net time at pressure.");
    }

    private void buildOnboardStep1(LinearLayout panel) {
        /* NEW TO PUMPING IS ASKED FIRST, because it is the only thing that decides whether
         * the beginner caps apply to every answer under it — and because the app used to
         * infer it from the months answer, which is a question about the PLAN. Somebody who
         * has pumped for years and is starting a plan today is in month 0 of a plan and not
         * in their first month of pumping; the two used to be one field, and the result was
         * an 11 inHg answer coming back as 5.9. */
        Ui.kvRow(a, panel, "New to pumping", a.trainerOnboardNew, new ToggleOnboardNewTap());
        Ui.note(a, panel, a.trainerOnboardNew
            ? "The beginner caps apply \u2014 at most "
              + Model.Fmt.p(Plan.MONTH1_CAP_KPA) + " for the first month."
            : "Your answers below set your pressure. " + Model.Fmt.p(Plan.ABSOLUTE_CAP_KPA)
              + " and your ceiling are never passed.");

        Ui.noteInfo(a, panel, "The pressure you actually run at now, in your own display unit.",
            "Why the steps are uneven", STEPS_UNEVEN);
        // The card's title names the field (polish SU-3): no second label.
        Ui.stepperRow(a, panel, "",
            Model.Fmt.p(a.trainerOnboardPressureKpa),
            new BumpOnboardPressure(-1), new BumpOnboardPressure(+1));
        /* KEPT, AND NOW TRUE (0.10, bug a): the answer used to be clamped into the level's
         * band under a note saying "Kept, not corrected". Above or below the plan's figure for
         * the level, it is kept as the track's own offset (Scale#setupAnswerNote). */
        double[] gs = onboardGirthSplit();
        String gNote = Scale.setupAnswerNote(gs[0], gs[1], a.trainerOnboardNew, onboardMonth());
        if (gNote.length() > 0) Ui.note(a, panel, gNote);

        Ui.stepperRow(a, panel, "Most you will go to",
            a.trainerOnboardMaxKpa > 0 ? Model.Fmt.p(a.trainerOnboardMaxKpa) : Ui.NOT_SET,
            new BumpOnboardMax(-1), new BumpOnboardMax(+1), "maximum pressure");
        // Girth's (and the feeder's) - length asks its own on the tracks step.
        if (a.trainerOnboardMaxKpa > 0)
            Ui.note(a, panel,
                "Nothing the trainer prescribes for girth goes above this. Length asks its own.");
        else
            Ui.noteInfo(a, panel, "Optional. Set it and nothing prescribed for girth goes "
                + "above it.", "Most you will go to",
                "Optional. Set it and nothing prescribed for girth goes above it; unset, the "
                + "band for your level caps it. Length asks its own.");

        // A DROP ABOVE THE USUAL TOP STAYS 1.0 inHg UNDER THE PULL (owner, 2026-09-30): held
        // under the working pressure just answered, as the plan's own holds will hold it.
        int dropWas = a.trainerOnboardDropKpa;
        a.trainerOnboardDropKpa = RunEdit.dropUnder(a.trainerOnboardDropKpa, onboardDropPullKpa());
        // t10 R-62 - ...AND SAID when it lowers the answer, once: the next draw finds it held.
        if (a.trainerOnboardDropKpa < dropWas)
            Ui.say(a, PlanCards.dropLoweredLine(a.trainerOnboardDropKpa), false);
        Ui.stepperRow(a, panel, "Drop between holds",
            Model.Fmt.p(a.trainerOnboardDropKpa),
            new BumpOnboardDrop(-1), new BumpOnboardDrop(+1), "preferred drop pressure");
        Ui.noteInfo(a, panel,
            "Where the cuff falls back to \u2014 not how far it falls.",
            "The drop between holds",
            "This is the pressure the cuff falls back TO between holds, not how far it "
            + "falls from the pull. It is usually " + Model.Fmt.p(Mint.DROP_MAX_KPA)
            + " or less; higher is a smaller release between holds, and you are asked once "
            + "before it goes there. It always stays at least "
            + Model.Fmt.dMag(RunEdit.DROP_GAP_SAID_KPA) + " under each hold's pull. The seconds "
            + "between holds are never counted as time at pressure however you set this "
            + "\u2014 raising it makes the release smaller, not the session longer.");
        // Wave 3b (Q12): a fact about the body, asked with the pressures. It arms the
        // return taper and the larger-cylinder reduction.
        Ui.kvRow(a, panel, SetupText.MARKS_LABEL, a.trainerOnboardMarks,
            new ToggleOnboardMarksTap());
        Ui.note(a, panel, a.trainerOnboardMarks ? SetupText.TRAINER_MARKS_ON
                                                : SetupText.TRAINER_MARKS_OFF);

    }

    /** The stepper's hint (polish NEW-21): the grid is the pump's whole kPa, so the inHg steps
     *  are not even. Said behind the \u24d8 on the line above the pressure stepper. */
    static final String STEPS_UNEVEN =
        "Steps follow the pump\u2019s whole-kPa settings, so they aren\u2019t even in inHg.";

    private final class ToggleOnboardNewTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.trainerOnboardNew = !a.trainerOnboardNew;
            showTrainer();
        }
    }

    /** The MAXIMUM stepper. Steps through the same display-unit ladder every other pressure
     *  control uses, and has one extra position below the floor: OFF ("not set"), which is
     *  what 0 means everywhere this answer is read. Girth's ("Most you will go to" on the
     *  pressure step), or with `length` the length track's own on the length step - the
     *  same answer asked of length work (Model#rxLengthMaxKpa). */
    private final class BumpOnboardMax implements View.OnClickListener {
        private final int dir;
        private final boolean length;
        BumpOnboardMax(int d) { this(d, false); }
        BumpOnboardMax(int d, boolean len) { dir = d; length = len; }
        @Override public void onClick(View v) {
            int hard = (int) Math.round(Math.min(Plan.ABSOLUTE_CAP_KPA, a.model.ceilKpa));
            int lo = (int) Math.round(Plan.L1_BAND_LO_KPA);
            double now = length ? a.trainerOnboardLengthMaxKpa : a.trainerOnboardMaxKpa;
            if (now <= 0) {
                // From OFF, up lands on the working pressure itself - the answer somebody
                // who is setting a maximum almost always means, and never below the floor.
                if (dir > 0) setMax(Say.clampI((int) Math.round(length ? lengthAnswerKpa()
                                               : a.trainerOnboardPressureKpa), lo, hard));
                showTrainer();
                return;
            }
            int cur = (int) Math.round(now);
            // On the setup's one grid, up and down alike (Model.Fmt#gridStepWholeKpa, E-M5).
            int want = Model.Fmt.gridStepWholeKpa(cur, dir);
            if (want < lo) { setMax(0); showTrainer(); return; }  // back to OFF
            if (want > hard) {
                a.toast(want > Plan.ABSOLUTE_CAP_KPA
                    ? "The absolute limit is " + Model.Fmt.p(Plan.ABSOLUTE_CAP_KPA)
                    : "That is your device ceiling");
                return;
            }
            setMax(want);
            showTrainer();
        }
        private void setMax(double kpa) {
            if (length) {
                a.trainerOnboardLengthMaxKpa = kpa;
                // Stepped: the person's own from here, whatever girth's does (lengthMaxSeed).
                a.trainerOnboardLengthMaxTouched = true;
            } else a.trainerOnboardMaxKpa = kpa;
        }
    }

    /** The pull the setup's drop is held under: the girth working pressure answered on the
     *  same step, whole kPa (the wire's). Each routine's own pull holds it again (Mint#dropFor). */
    private int onboardDropPullKpa() {
        return (int) Math.round(a.trainerOnboardPressureKpa);
    }

    /** The drop stepper, 1 kPa a tap: down to 0 ("all the way off"), up past the usual
     *  Mint#DROP_MAX_KPA after one warning (RunEdit#dropNeedsWarning - a drop already above
     *  it was asked), never past 1.0 inHg under the working pressure (RunEdit#dropTopKpa). */
    private final class BumpOnboardDrop implements View.OnClickListener {
        private final int dir;
        BumpOnboardDrop(int d) { dir = d; }
        @Override public void onClick(View v) {
            int top = Math.min(Mint.DROP_PREF_MAX_KPA, RunEdit.dropTopKpa(onboardDropPullKpa()));
            int was = a.trainerOnboardDropKpa;
            int want = Say.clampI(was + dir, 0, Math.max(0, top));
            if (want == was && dir > 0) {
                a.toast(RunEdit.dropGapSaid());
                return;
            }
            if (dir > 0 && RunEdit.dropNeedsWarning(was, want, false)) {
                Ui.dress(a, Ui.dialog(a)
                    .setTitle(RunEdit.DROP_WARN_TITLE)
                    .setMessage(RunEdit.dropWarning())
                    .setPositiveButton(RunEdit.DROP_WARN_GO, new ConfirmOnboardDrop(want))
                    .setNegativeButton(RunEdit.DROP_WARN_BACK, null)
                    .show());
                return;
            }
            a.trainerOnboardDropKpa = want;
            showTrainer();
        }
    }

    /** The drop warning answered "keep it": the drop goes above the usual top - the answer
     *  itself, saved above it, is what says it was asked. */
    private final class ConfirmOnboardDrop implements DialogInterface.OnClickListener {
        private final int want;
        ConfirmOnboardDrop(int w) { want = w; }
        @Override public void onClick(DialogInterface d, int w) {
            a.trainerOnboardDropKpa = want;
            showTrainer();
        }
    }

    /** Shown only when the length track is on - there is no sense asking for the working
     *  pressure of a track that is not being run. */
    private void buildOnboardLengthPressure(LinearLayout panel) {
        if (!a.trainerOnboardLengthOn) return;
        int month = Plan.monthIndex(Math.max(0, a.trainerOnboardMonths) * 4);
        double cap = onboardLengthCapKpa(month);
        if (Double.isNaN(a.trainerOnboardLengthKpa))
            a.trainerOnboardLengthKpa = Math.min(a.trainerOnboardPressureKpa, cap);
        /* RE-LINKED WHEN THE CAP MOVES (review M5): a length cylinder added or changed on the
         * cylinders step brings in the 15 lb load limit, and an answer made before it - −12.7
         * inHg with no tube listed - would show a load past 15 lb beside a note saying it stops
         * lower, while Confirm saved 15 lb. Held to the cap as the step is drawn, and read
         * through it everywhere else (lengthAnswerKpa), so the field and Confirm agree. */
        else if (a.trainerOnboardLengthKpa > cap) a.trainerOnboardLengthKpa = cap;
        Ui.noteInfo(a, panel,
            "Length work has its own pressure, and it is a lower band than girth.",
            "Length pressure by month",
            "Length work has its own pressure, and it is a lower band than "
            + "girth: the guidance holds it at or under " + Model.Fmt.p(Plan.LENGTH_MONTH1_CAP_KPA)
            + " for the first month, then lets it creep about "
            + Model.Fmt.p(Plan.STEP_HG_KPA) + " a month to a soft cap of "
            + Model.Fmt.p(Plan.LENGTH_SOFT_CAP_LO_KPA) + "–"
            + Model.Fmt.p(Plan.LENGTH_SOFT_CAP_HI_KPA) + ".");
        Ui.stepperRow(a, panel, "Length working pressure",
            Model.Fmt.p(a.trainerOnboardLengthKpa),
            new BumpOnboardLengthPressure(-1), new BumpOnboardLengthPressure(+1),
            "length working pressure");
        /* THE LENGTH LOAD AND THE LENGTH PRESSURE ARE ONE VALUE IN TWO FIELDS (owner,
         * 2026-09-30). The load is the pressure above in the length cylinder, converted by its
         * bore (TrainerOnboard#linkedLoadLb); stepping the load sets the pressure that makes
         * it. The "erect girth for the conversion" question is gone - the bore is known. Somebody
         * new to pumping starts at the plan's own load whatever is answered (Scale#setupLoadLb),
         * so they are told that instead of being given a field Confirm would ignore. With no
         * length cylinder yet there is nothing to convert at: the pressure alone, and a line. */
        double bore = a.model.lengthBoreCm();
        if (bore <= 0) {
            Ui.note(a, panel, TrainerOnboard.NO_LENGTH_CYLINDER);
        } else if (a.trainerOnboardNew) {
            Ui.note(a, panel, TrainerOnboard.newLoadNote());
        } else {
            Ui.stepperRow(a, panel, TrainerOnboard.LOAD_LABEL,
                Traction.settingLb(onboardLinkedLoadLb()),
                new BumpOnboardLinkedLoad(-1), new BumpOnboardLinkedLoad(+1),
                "length load");
            Ui.noteInfo(a, panel, "One figure, shown as a load and as a pressure.",
                "Length load", TrainerOnboard.linkedBasis(bore));
        }
        /* LENGTH'S OWN MAXIMUM (the owner's report: length at -10 with a most of -12, girth at
         * -9 with a most of -11). One answer served both tracks, so length stopped at girth's
         * most. The same question with the same meaning (optional, 0 = not set), asked of
         * length work, and it is what the cap in the note under it reads. */
        Ui.stepperRow(a, panel, "Most you will go to",
            a.trainerOnboardLengthMaxKpa > 0 ? Model.Fmt.p(a.trainerOnboardLengthMaxKpa)
                                             : Ui.NOT_SET,
            new BumpOnboardMax(-1, true), new BumpOnboardMax(+1, true),
            "length maximum pressure");
        Ui.note(a, panel, a.trainerOnboardLengthMaxKpa > 0
            ? "Nothing the trainer prescribes for length goes above this."
              + (a.trainerOnboardLengthMaxTouched ? ""
                 : " It starts at your girth maximum; change it and yours is kept.")
            : "Optional, for length alone. Set it and nothing prescribed for length goes "
              + "above it; unset, the length band caps it.");
        // THE EXPANSION PART RUNS IN THE GIRTH CYLINDER, so girth's maximum binds it as well
        // (Scale#workHardKpa - review I3, the lower of the two).
        // ONE LINE AND ITS \u24d8 (polish S-setup-3): the figure stays on the face; which limits
        // make it, and the girth maximum's hold on the expansion part, are behind the \u24d8.
        String stops = "Where you are now stops at " + Model.Fmt.p(cap)
            + (a.trainerOnboardNew && month < 1 ? " for a first month." : ".");
        StringBuilder why = new StringBuilder();
        if (!(a.trainerOnboardNew && month < 1))
            why.append("Where you are now stops at ").append(Model.Fmt.p(cap))
               .append(" \u2014 the absolute limit, your device ceiling, your own maximum")
               .append(pullsHere() ? " and the " + Traction.settingLb(Scale.LOAD_HARD_MAX_LB)
                                     + " load limit" : "")
               .append(", whichever is lowest.");
        if (a.trainerOnboardMaxKpa > 0)
            why.append(why.length() > 0 ? " " : "")
               .append("The expansion part of a length session runs in your girth "
                + "cylinder, so it also stays at or under your girth maximum of "
                + Model.Fmt.p(a.trainerOnboardMaxKpa) + ".");
        if (why.length() > 0)
            Ui.noteInfo(a, panel, stops, "Where you are now stops", why.toString());
        else Ui.note(a, panel, stops);
        // Kept as length's own offset above or below the plan's length figure (0.10, bug a).
        double[] ls = onboardLengthSplit();
        String lNote = Scale.setupAnswerNote(ls[0], ls[1], a.trainerOnboardNew, month);
        if (lNote.length() > 0) Ui.note(a, panel, lNote);
    }

    /** The month the setup's answers put the plan in. */
    private int onboardMonth() {
        return Plan.monthIndex(Math.max(0, a.trainerOnboardMonths) * 4);
    }

    /**
     * THE GIRTH ANSWER, SPLIT (0.10): {the plan's figure for the level, the person's offset,
     * the level}. The plan starts at what the setup derives for the level (TrainerTab
     * #deriveGirth, never above the usual top); whatever the answer is above or below that is
     * kept as the track's offset (Scale#offsetFromAnswer) instead of being clamped away.
     */
    private double[] onboardGirthSplit() {
        TrainerTab.Derived g = onboardGirth(girthBasisKpa(), a.model.ceilKpa,
                                            a.trainerOnboardMaxKpa);
        double plan = Scale.setupPlanKpa(a.trainerOnboardGirthStyle, g.level, g.monthIndex,
                                         g.pressureKpa, a.trainerOnboardNew);
        return new double[]{ plan, Scale.offsetFromAnswer(a.trainerOnboardPressureKpa, plan),
                             g.level };
    }

    /** The same for the length answer (the girth answer where length was not asked). */
    private double[] onboardLengthSplit() {
        TrainerTab.Derived l = TrainerTab.deriveLength(a.trainerOnboardMonths, lengthBasisKpa(),
            a.trainerOnboardNew, a.model.ceilKpa, a.trainerOnboardLengthMaxKpa);
        double plan = Scale.setupPlanKpa(Plan.TRACK_LENGTH, l.level, l.monthIndex, l.pressureKpa,
                                         a.trainerOnboardNew);
        return new double[]{ plan, Scale.offsetFromAnswer(lengthAnswerKpa(), plan), l.level };
    }

    /**
     * THE FIGURE THE GIRTH POSITION IS DERIVED FROM (review 2, finding 3): the answer, or - a
     * recalibration's answer left as it was pre-filled (the plan's figure plus the offset) -
     * the plan's figure under it, so the level, the plan's figure and the offset come out as
     * they were (Scale#setupBasisKpa). Every derivation of the setup's girth position reads
     * this; the offset is always the answer less the plan's figure it gives.
     */
    private double girthBasisKpa() {
        return Scale.setupBasisKpa(a.trainerOnboardPressureKpa, a.trainerOnboardPrefillG,
                                   a.trainerOnboardKeptOffG);
    }

    /** The length answer (the girth answer where length was not asked) - held to the length
     *  step's cap as it stands now (review M5: a length cylinder listed after the answer brings
     *  the 15 lb load limit with it), so Confirm writes what the step shows. */
    private double lengthAnswerKpa() {
        double v = Double.isNaN(a.trainerOnboardLengthKpa) ? a.trainerOnboardPressureKpa
                                                           : a.trainerOnboardLengthKpa;
        if (Double.isNaN(a.trainerOnboardLengthKpa) || !a.trainerOnboardLengthOn) return v;
        return Math.min(v, onboardLengthCapKpa(onboardMonth()));
    }

    /** {@link #girthBasisKpa} for the length answer. */
    private double lengthBasisKpa() {
        return Scale.setupBasisKpa(lengthAnswerKpa(), a.trainerOnboardPrefillL,
                                   a.trainerOnboardKeptOffL);
    }

    /** THE LENGTH STEP'S CAP, in one place, so the note and the stepper cannot disagree
     *  about it (they were two copies of the same expression and one of them had to be
     *  changed twice). Mirrors TrainerTab#deriveLength exactly: the guide's length band for
     *  somebody new to pumping, and for everybody else only the limits that are hard for
     *  everybody. */
    private double onboardLengthCapKpa(int month) {
        /* THE HARD LIMITS ONLY (0.10): an answer above the length band is kept as length's
         * offset, warned once at Confirm, so the band's soft cap no longer stops the stepper.
         * A new person's first month keeps its 6 inHg (startCapKpa folds it in). */
        double cap = Plan.startCapKpa(a.trainerOnboardNew, month, a.model.ceilKpa,
                                      a.trainerOnboardLengthMaxKpa);
        /* AND THE HARD LOAD LIMIT, where the pressure IS the load (owner, 2026-09-30): the
         * pressure that pulls 15 lb in the length cylinder, floored to the whole kPa the pump
         * takes (Scale#pullCapKpa's own rule). */
        if (pullsHere())
            cap = Math.min(cap, Math.floor(TrainerOnboard.kpaForLinkedLoad(
                Scale.LOAD_HARD_MAX_LB, a.model.lengthBoreCm()) + 1e-9));
        return cap;
    }

    /** The step's load is linked to its pressure: a length cylinder is listed and the person
     *  is not new to pumping (whose pulls start at the plan's own load). */
    private boolean pullsHere() {
        return a.model.lengthBoreCm() > 0 && !a.trainerOnboardNew;
    }

    /** The load the step's length pressure makes in the length cylinder. */
    private double onboardLinkedLoadLb() {
        return TrainerOnboard.linkedLoadLb(lengthAnswerKpa(), a.model.lengthBoreCm());
    }

    /**
     * THE LOAD FIELD, LINKED (owner, 2026-09-30): a step of the load is held as the pressure
     * that makes it in the length cylinder, so the two fields cannot disagree. Within the
     * stepper's old 1 lb floor and the hard 15 lb limit, and the same pressure cap the
     * pressure field stops at (the ceiling, your own maximum, the absolute limit).
     */
    private final class BumpOnboardLinkedLoad implements View.OnClickListener {
        private final int dir;
        BumpOnboardLinkedLoad(int d) { dir = d; }
        @Override public void onClick(View v) {
            double bore = a.model.lengthBoreCm();
            if (bore <= 0) return;
            // Half a pound, or half a kilo in kilos (Model.Fmt#loadStepLb).
            double want = TrainerOnboard.bumpLoadLb(onboardLinkedLoadLb(), dir,
                                                    Model.Fmt.loadStepLb());
            if (want < TrainerOnboard.LOAD_MIN_LB - 1e-9) {
                a.toast("That is the lightest load the plan starts from");
                return;
            }
            if (want > Scale.LOAD_HARD_MAX_LB + 1e-9) {
                a.toast("The load limit is " + Traction.settingLb(Scale.LOAD_HARD_MAX_LB));
                return;
            }
            double kpa = TrainerOnboard.kpaForLinkedLoad(want, bore);
            if (kpa > onboardLengthCapKpa(onboardMonth()) + 1e-9) {
                a.toast("That is the highest this device and your own maximum allow");
                return;
            }
            a.trainerOnboardLengthKpa = kpa;
            a.trainerOnboardLoadOwn = true;
            showTrainer();
        }
    }

    private final class BumpOnboardLengthPressure implements View.OnClickListener {
        private final int dir;
        BumpOnboardLengthPressure(int d) { dir = d; }
        @Override public void onClick(View v) {
            int month = Plan.monthIndex(Math.max(0, a.trainerOnboardMonths) * 4);
            int cap = (int) Math.round(onboardLengthCapKpa(month));
            int cur = (int) Math.round(Double.isNaN(a.trainerOnboardLengthKpa)
                                       ? a.trainerOnboardPressureKpa : a.trainerOnboardLengthKpa);
            int want = Say.clampI(Model.Fmt.gridStepWholeKpa(cur, dir), 0, cap);   // E-M5
            if (want == cur && dir > 0) {
                a.toast(a.trainerOnboardNew && month < 1
                    ? "That is the length cap for a first month of pumping"
                    : "That is the highest this device and your own maximum allow");
                return;
            }
            a.trainerOnboardLengthKpa = want;
            a.trainerOnboardLoadOwn = true;
            showTrainer();
        }
    }

    private void buildOnboardStep2(LinearLayout panel) {
        Ui.noteInfo(a, panel, "Girth first, then length.", "Tracks",
            "Girth style is one or the other. Length is a separate, optional "
            + "track that can run alongside either style.");
        /* GROUPED BY TRACK (polish SU-5): the girth switches under GIRTH, the length ones under
         * LENGTH - they were one mixed list, and in a recalibration "Girth track" sat below the
         * length switch. The style is a segmented pair, one or the other, not two text bullets. */
        Ui.microLabel(a, panel, "Girth", Ui.DIM);
        Ui.segmented(a, panel, new String[]{ "Interval", "Traditional" }, null,
            a.trainerOnboardGirthStyle == Plan.TRACK_GIRTH_TRADITIONAL ? 1 : 0,
            new View.OnClickListener[]{
                new SetOnboardGirthStyleTap(Plan.TRACK_GIRTH_INTERVAL),
                new SetOnboardGirthStyleTap(Plan.TRACK_GIRTH_TRADITIONAL) });
        // Wave 3b (Q8): a length-only plan.
        if (a.trainerOnboardLengthOn)
            Ui.kvRow(a, panel, "Girth track", a.trainerOnboardGirthOn,
                new ToggleOnboardGirthOnTap());
        // Wave 3b (Q7a): HYBRID, the guidance's traditional-plus-interval mix, from L3.
        Ui.kvRow(a, panel, "Hybrid mix (from Level 3)", a.trainerOnboardHybrid,
            new ToggleOnboardHybridTap());
        // Wave 3b (Q7b): the L4 fork, at setup rather than buried in Settings.
        Ui.kvRow(a, panel, SHEARS_LABEL, a.trainerOnboardL4B, new ToggleOnboardL4BTap());
        // Wave 3b (Q9): the feeder is a lifestyle choice - two extra sessions a day.
        Ui.kvRow(a, panel, "Add feeder sessions at Level 3", a.trainerOnboardFeeder,
            new ToggleOnboardFeederTap());
        // R11-3: where the girth track starts - by the length of the session run now.
        if (a.trainerOnboardGirthOn || !a.trainerOnboardLengthOn) sessionQuestion(panel, true);
        Ui.microLabel(a, panel, "Length", Ui.DIM);
        Ui.kvRow(a, panel, "Length track (optional)", a.trainerOnboardLengthOn,
            new ToggleOnboardLengthTap());
        if (a.trainerOnboardLengthOn) sessionQuestion(panel, false);
        /* Wave 3b (Q13c): an experienced length user starts at their own load - asked now as
         * the length load field beside the length pressure, the two linked through the length
         * cylinder's bore (owner, 2026-09-30; buildOnboardLengthPressure). The "erect girth
         * for the conversion" question this step asked went with it. */
    }

    /* R11-3 - ONE QUESTION PER TRACK: how long its session is now, in minutes (Skip, 0 "I don't
     * do this yet", then 5-minute steps). Somebody new to pumping is not asked: each track
     * starts at Level 1, week 1. */
    private void sessionQuestion(LinearLayout panel, boolean girth) {
        if (a.trainerOnboardNew) {
            if (girth) Ui.note(a, panel, TrainerOnboard.SESSION_NEW);
            return;
        }
        int min = girth ? a.trainerOnboardGirthMin : a.trainerOnboardLengthMin;
        String row = girth ? TrainerOnboard.SESSION_ROW_GIRTH : TrainerOnboard.SESSION_ROW_LENGTH;
        Ui.stepperRow(a, panel, row, TrainerOnboard.sessionValue(min),
            new BumpOnboardSessionMin(girth, -1), new BumpOnboardSessionMin(girth, +1));
        Ui.noteInfo(a, panel, TrainerOnboard.SESSION_NOTE, TrainerOnboard.SESSION_INFO_TITLE,
            TrainerOnboard.SESSION_INFO);
    }

    private final class BumpOnboardSessionMin implements View.OnClickListener {
        private final boolean girth;
        private final int dir;
        BumpOnboardSessionMin(boolean g, int d) { girth = g; dir = d; }
        @Override public void onClick(View v) {
            if (girth)
                a.trainerOnboardGirthMin = TrainerOnboard.stepSessionMin(a.trainerOnboardGirthMin, dir);
            else
                a.trainerOnboardLengthMin = TrainerOnboard.stepSessionMin(a.trainerOnboardLengthMin, dir);
            placedFor = null;
            showTrainer();
        }
    }

    /* R11-3 - THE SETUP'S GIRTH POSITION, PLACED: by the minutes answered (Placement#girth,
     * built in a scratch model of the setup's answers), else by the months as before. Every
     * place the setup derives the girth position reads this, so the confirmation, the preview
     * and Confirm agree. The placement is kept for the answers it was made from. */
    private String placedFor;
    private Placement.Spot placedSpot;

    private Placement.Spot onboardGirthSpot() {
        int min = a.trainerOnboardNew ? 0 : a.trainerOnboardGirthMin;
        if (min == Placement.NOT_ANSWERED) return null;
        String key = a.trainerOnboardGirthStyle + "|" + a.trainerOnboardMonths + "|"
            + a.trainerOnboardNew + "|" + girthBasisKpa() + "|" + a.trainerOnboardMaxKpa + "|"
            + min + "|" + a.trainerOnboardHybrid + "|" + a.trainerOnboardMarks + "|"
            + programKey(a.trainerOnboardProgG);
        if (key.equals(placedFor)) return placedSpot;
        Model scratch = onboardScratch();
        Placement.Spot s = scratch == null ? null
            : Placement.girth(scratch, a.trainerOnboardGirthStyle, a.trainerOnboardMonths,
                a.trainerOnboardNew, girthBasisKpa(), a.trainerOnboardMaxKpa, min);
        placedFor = key;
        placedSpot = s;
        return s;
    }

    private static String programKey(Model.Program p) {
        return p == null ? "" : p.warm + "." + p.work + "." + p.pressure + "." + p.rest + "."
            + p.fatigue;
    }

    /** The setup's girth position (R11-3: placed by the minutes when answered). */
    private TrainerTab.Derived onboardGirth(double basisKpa, double ceilKpa, double maxKpa) {
        Placement.Spot s = onboardGirthSpot();
        return TrainerTab.deriveGirthAt(a.trainerOnboardGirthStyle, a.trainerOnboardMonths,
            basisKpa, a.trainerOnboardNew, ceilKpa, maxKpa,
            s == null ? 0 : s.level, s == null ? 0 : s.week);
    }

    /** R11-3 - the strain sets the length track is placed at for the minutes answered, in the
     *  scratch model with its length position written; -1 when not placed. */
    private int onboardStrainSets(Model scratch) {
        int min = a.trainerOnboardNew ? 0 : a.trainerOnboardLengthMin;
        return Placement.lengthStrainSets(scratch, a.trainerOnboardNew, a.trainerOnboardMonths,
                                          min);
    }

    /**
     * Step 4 — derived-position confirmation. Every line traces to an answer: level/
     * engine/month to the months answer, floor/starting-pressure to the level and the
     * pressure answer, Net TUP target to the level. When the two answers disagree
     * (conflict), BOTH readings are stated and the confirm screen has already resolved
     * to the more conservative (lower) level — see {@link TrainerTab#deriveGirth}.
     */
    private void buildOnboardConfirm(LinearLayout panel) {
        /* t10 review D, F1 - THE UPGRADE CARD'S SETUP WITH THE POSITION ANSWERS UNCHANGED: the
         * position is kept, and said so, rather than a derived one Confirm would not write. */
        if (onboardKeepsPosition()) {
            Ui.sectionHead(a, panel, TrainerOnboard.POSITION_KEPT, Ui.ACCENT);
            if (a.model.trainerGirthOn)
                Ui.kvRow(a, panel, TrainerTab.trackLabel(a.model.trainerGirthStyle),
                    positionLine(a.model.trainerGirth), Ui.TEXT, null);
            if (a.model.trainerLengthOn)
                Ui.kvRow(a, panel, TrainerTab.trackLabel(Plan.TRACK_LENGTH),
                    positionLine(a.model.trainerLength), Ui.TEXT, null);
            Ui.note(a, panel, TrainerOnboard.POSITION_KEPT_NOTE);
            // "Confirm position" is the pinned footer's (polish SU-15).
            return;
        }
        TrainerTab.Derived girth = onboardGirth(girthBasisKpa(), a.model.ceilKpa,
                                                a.trainerOnboardMaxKpa);

        /* YOUR STARTING POINT, IN PLAIN WORDS (polish SU-14): it read as engine output -
         * "Derived position", an Engine row, "Floor", "Net TUP target" and the answers each row
         * came from. The same figures, named as the rest of the app names them; the engine
         * stage is not shown, and the deload spacing is behind its \u24d8. */
        Ui.sectionHead(a, panel, TrainerTab.trackLabel(a.trainerOnboardGirthStyle), Ui.ACCENT);
        /* FIX11 F5 - THE LEVEL AND THE WEEK IT STARTS AT, and what placed it: "Level 1, week 6
         * — closest to your 20 min" (the Trainer's "L1 · wk 6"), never "Level L1" over a
         * "Month 7" that read as a contradiction - that row is the months answer, named so. */
        Placement.Spot spot = onboardGirthSpot();
        int girthMin = a.trainerOnboardNew ? 0 : a.trainerOnboardGirthMin;
        Ui.kvRow(a, panel, TrainerOnboard.PLACED_ROW, TrainerOnboard.placedAt(girth.level,
            girth.weekIndex, spot == null ? Placement.NOT_ANSWERED : girthMin,
            a.trainerOnboardNew), Ui.TEXT, null);
        // R11-3: said where the minutes placed it.
        if (spot != null && !a.trainerOnboardNew) {
            String placed = TrainerOnboard.placedLine(a.trainerOnboardGirthMin, spot.minutes);
            if (placed.length() > 0) Ui.note(a, panel, placed);
        }
        Ui.kvRow(a, panel, TrainerOnboard.MONTHS_ROW, String.valueOf(a.trainerOnboardMonths),
            Ui.TEXT, null);
        Ui.kvRow(a, panel, "Lowest pressure", Model.Fmt.p(girth.floorKpa), Ui.TEXT, null);
        // The plan's figure and the person's own, as Confirm writes them (0.10).
        double[] gs = onboardGirthSplit();
        Ui.kvRow(a, panel, "Starts at", Model.Fmt.p(gs[0]), Ui.TEXT, null);
        Ui.kvRow(a, panel, "My pressure", myPressureValue(gs[1]), Ui.TEXT, null);
        Ui.kvRow(a, panel, TIME_AT_PRESSURE_ROW, Say.gateNum(Math.round(girth.netTargetMin * 10.0)
            / 10.0) + " min", Ui.TEXT, null);
        Ui.noteInfo(a, panel, "Deload weeks", "Deload weeks", deloadCadenceLine());
        if (girth.conflict) {
            Ui.note(a, panel, "Your months answer alone would place you at "
                + TrainerOnboard.levelWords(girth.monthsImpliedLevel) + "; your pressure answer "
                + "sits in the Level 1 band. Showing both — starting at the more careful "
                + TrainerOnboard.levelWords(girth.level) + ".");
        }
        // A recalibration that would LOWER the currently-saved level is the case most
        // likely to be an accident (a fresh-blank field, a mistyped months answer) rather
        // than a deliberate step-back — say so plainly before the save button, whether or
        // not the conflict check above already fired (it does not always: a low months
        // answer alone can undercut a genuinely higher saved level with no pressure
        // disagreement to trigger it).
        // t10 R-61: a full setup run again from the upgrade card is the same case - the plan is
        // enrolled and its answers were seeded from it - so it is said there too.
        if ((a.trainerRecalibrating || a.model.trainerEnrolled)
                && girth.level < a.model.trainerGirth.level) {
            Ui.note(a, panel, "This moves you from " + TrainerOnboard.levelWords(a.model.trainerGirth.level)
                + " to " + TrainerOnboard.levelWords(girth.level) + " — check the answers above "
                + "before saving if that is not what you meant.");
        }

        if (a.trainerOnboardLengthOn) {
            TrainerTab.Derived length = TrainerTab.deriveLength(
                a.trainerOnboardMonths, lengthBasisKpa(),
                a.trainerOnboardNew, a.model.ceilKpa, a.trainerOnboardLengthMaxKpa);
            Ui.sectionHead(a, panel, TrainerTab.trackLabel(Plan.TRACK_LENGTH), Ui.ACCENT);
            // Length's own calendar level for the months answer (TrainerTab#deriveLength).
            Ui.kvRow(a, panel, "Level", TrainerOnboard.levelWords(length.level), Ui.TEXT, null);
            // FIX11 F6: and what the minutes answer placed - its strain sets - said here.
            String lp = lengthPlacedLine();
            if (lp.length() > 0) Ui.note(a, panel, lp);
            double[] ls = onboardLengthSplit();
            Ui.kvRow(a, panel, "Starts at", Model.Fmt.p(ls[0]), Ui.TEXT, null);
            Ui.kvRow(a, panel, "My pressure", myPressureValue(ls[1]), Ui.TEXT, null);
            /* FIX11 (device recheck EMU13) - NO SECOND MINUTES FIGURE: the expansion part's
             * time at pressure (10 min) sat under "30 min answered" and "a 45.1-min session",
             * three minutes figures for one track. The placed line above says the session's
             * minutes; the time-at-pressure target is the Trainer's to show. */
            // It said "static - no level, no yield gate": length has had its own calendar
            // levels and load ladder since S20. "No yield gate" was jargon (polish SU-16).
            Ui.noteInfo(a, panel, "Length has its own levels and load steps.", "The length track",
                "Its levels come with the calendar, at months 3, 6 and 12. Its traction load "
                + "steps up on a calendar before month 3 and by your strain readings after it. "
                + "The expansion part's pressure creeps about 1 hg a month toward the "
                + "length band's usual top of " + Model.Fmt.p(Plan.LENGTH_SOFT_CAP_HI_KPA)
                + "; an answer above or below the plan is kept as your own pressure, plan "
                + "plus or minus, and moves your traction pulls with it.");
        }

        /* t10 fix (review D deviation 7) - A RECALIBRATION THAT TURNS LENGTH ON brings the saved
         * Long training days into force (one track on, it was hidden and acted as "as now",
         * K21); a recalibration has no week step, so the confirm says it here. */
        if (a.trainerRecalibrating) {
            String ld = LongDays.lengthOnLine(a.model, a.trainerOnboardLengthOn,
                                              a.trainerOnboardGirthOn);
            if (ld.length() > 0) Ui.note(a, panel, ld);
        }

        // "Confirm position" / "Save recalibration" is the pinned footer's (polish SU-15).
    }

    /** FIX11 F6 - the length track's "Placed by" line for the minutes answered, from the
     *  routine Confirm will build (its strain sets placed, Placement#lengthStrainSets). */
    private String lengthPlacedLine() {
        int min = a.trainerOnboardNew ? 0 : a.trainerOnboardLengthMin;
        if (min < 0 || a.trainerOnboardNew) return "";
        try {
            Model scratch = onboardScratch();
            if (scratch == null) return "";
            // The length position and the strain sets placed, as Confirm writes them...
            onboardRx(scratch, false);
            int sets = scratch.trainerLength.strainSets;
            // ...and that session's minutes by the placement's own definition (EMU13).
            double sess = Placement.lengthSessionMin(scratch, a.trainerOnboardMonths, sets);
            return TrainerOnboard.lengthPlacedLine(min, scratch.lengthPulls(), sets, sess);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The row every setup and Trainer screen names a session's target by (polish CP-2). */
    static final String TIME_AT_PRESSURE_ROW = "Time at pressure per session";

    /** "My pressure"'s value in a row: "Plan’s own" for none, else "Plan +1.0 inHg"
     *  (polish SU-14, TR-18 - Scale#offsetText is the sentence form). */
    static String myPressureValue(double offsetKpa) {
        String t = Scale.offsetText(offsetKpa);
        return "the plan".equals(t) ? PLAN_OWN : Say.capitalise(t);
    }

    /** The Tracks step's shears row and What it writes' (polish TR-20). */
    static final String SHEARS_LABEL = "Shears during rests (from Level 4)";

    /** What a value the plan sets for itself reads as (polish TR-19). */
    static final String PLAN_OWN = "Plan\u2019s own";

    /** Back from step 1 asks before it leaves (polish SU-19): with the tab bar hidden, this
     *  is the setup's one way out. Nothing is written until Confirm, so leaving loses only
     *  the answers on screen. */
    private final class OnboardBackTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.trainerOnboardStep > 0) { a.trainerOnboardStep--; showTrainer(); return; }
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Leave the setup?")
                .setMessage("Nothing is saved until you confirm the last step.")
                .setPositiveButton("Leave", new LeaveOnboardTap())
                .setNegativeButton("Stay", null)
                .show());
        }
    }

    private final class LeaveOnboardTap implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            a.trainerOnboardActive = false;
            showTrainer();
        }
    }

    private final class OnboardNextTap implements View.OnClickListener {
        @Override public void onClick(View v) { a.trainerOnboardStep++; showTrainer(); }
    }

    private final class BumpOnboardMonths implements View.OnClickListener {
        private final int dir;
        BumpOnboardMonths(int d) { dir = d; }
        @Override public void onClick(View v) {
            a.trainerOnboardMonths = Say.clampI(a.trainerOnboardMonths + dir, 0, 360);
            showTrainer();
        }
    }

    /** Steps by the same "1 kPa, or the kPa equivalent of one inHg" rule every other
     *  pressure stepper in this app uses (stepKpa, Settings' own BumpCeil). */
    private final class BumpOnboardPressure implements View.OnClickListener {
        private final int dir;
        BumpOnboardPressure(int d) { dir = d; }
        @Override public void onClick(View v) {
            int cur = (int) Math.round(a.trainerOnboardPressureKpa);
            // On the setup's one grid, up and down alike (Model.Fmt#gridStepWholeKpa, E-M5).
            a.trainerOnboardPressureKpa = Say.clampI(Model.Fmt.gridStepWholeKpa(cur, dir), 0,
                (int) Math.round(Plan.ABSOLUTE_CAP_KPA));
            showTrainer();
        }
    }

    private final class SetOnboardGirthStyleTap implements View.OnClickListener {
        private final int style;
        SetOnboardGirthStyleTap(int s) { style = s; }
        @Override public void onClick(View v) { a.trainerOnboardGirthStyle = style; showTrainer(); }
    }

    private final class ToggleOnboardLengthTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.trainerOnboardLengthOn = !a.trainerOnboardLengthOn;
            showTrainer();
        }
    }

    /** Writes the derived state into Model's trainer fields, sets trainerEnrolled and
     *  (only on the very first enrolment — never on a recalibration) trainerEnrolledAt,
     *  and saves — the same "flip the field(s), Store.save, redraw" shape every Settings
     *  toggle in this app already follows (ToggleAppLockOn). */

    private static Model.Program cloneProgram(Model.Program src) {
        Model.Program p = new Model.Program();
        p.warm = src.warm; p.work = src.work; p.pressure = src.pressure;
        p.rest = src.rest; p.fatigue = src.fatigue;
        return p;
    }

    private final class ToggleOnboardLayoffTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.trainerOnboardLayoff = !a.trainerOnboardLayoff; showTrainer(); }
    }
    private final class ToggleOnboardMarksTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.trainerOnboardMarks = !a.trainerOnboardMarks; showTrainer(); }
    }
    private final class ToggleOnboardHybridTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.trainerOnboardHybrid = !a.trainerOnboardHybrid; showTrainer(); }
    }
    private final class ToggleOnboardGirthOnTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.trainerOnboardGirthOn && !a.trainerOnboardLengthOn) {
                a.toast("Turn the length track on first \u2014 a plan needs one track");
                return;
            }
            a.trainerOnboardGirthOn = !a.trainerOnboardGirthOn; showTrainer(); }
    }
    private final class ToggleOnboardL4BTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.trainerOnboardL4B = !a.trainerOnboardL4B; showTrainer(); }
    }
    private final class ToggleOnboardFeederTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.trainerOnboardFeeder = !a.trainerOnboardFeeder; showTrainer(); }
    }
    /*
     * S17 - THE 15 lb CEILING HERE IS A DELIBERATE SOURCE CONFLICT, KEPT.
     *
     * The guidance puts the vacuum-hanger cap at 12 lb ({@link Traction#LOAD_MAX_LB} /
     * {@link Traction#LOAD_ABS_LB}, which the plan's own {@link Plan#loadCapPolicyLb} reads
     * from) - elsewhere the guidance states 15 lb, but only from month 12 on. An
     * experienced user answering "current length load" here may genuinely already be past
     * 12, and clamping their own answer down to a ladder figure they have already
     * outrun would be the app overriding a fact rather than governing a prescription.
     * Owner ruling (2026-09-26): keep the wider 15 lb allowance onboarding has always had,
     * rather than clamp it to 12 to match the policy cap. The saved value is NOT re-clamped
     * where the traction routine is built (RxBuild/Mint read it as given) - Traction's own
     * per-session ceiling (loadCapLb/maxLoadLb) still governs what any one session actually
     * commands, whatever was typed here. (The load is now the linked length load field -
     * BumpOnboardLinkedLoad - with the same 1 and 15 lb bounds.)
     */
    /**
     * THE LOAD CONFIRM WRITES (review 2, finding 5): the answer, for somebody not new to
     * pumping; with "New to pumping" on the answer is ignored - the plan's starting load on a
     * first enrolment, the load on file on a recalibration (Scale#setupLoadLb). The preview
     * and the warning read the same figure.
     */
    private double onboardLoadLb() {
        /* THE LINKED VALUE (owner, 2026-09-30): the length pressure answered, as a load in the
         * length cylinder, less the part the length offset carries (the builder adds it back),
         * so the pull the plan builds is the pressure answered (Scale#setupLinkedAnswerLb). */
        boolean first = !a.model.trainerEnrolled;
        double off = a.trainerOnboardLengthOn ? onboardLengthSplit()[1] : 0.0;
        double answer = Scale.setupLinkedAnswerLb(lengthAnswerKpa(), off,
            a.model.lengthBoreCm(), first, a.model.trainerLength.loadLb);
        return Scale.setupLoadLb(a.trainerOnboardNew, first, answer,
                                 a.model.trainerLength.loadLb);
    }

    /** Setup step 4 \u2014 the rack, inside setup (Q10): the length track cannot pull
     *  without a cylinder that fits, and the oversize cut needs a bore. */
    private void buildOnboardCylinders(LinearLayout panel) {
        Ui.noteInfo(a, panel,
            "Skippable \u2014 but length work cannot pull without a listed cylinder.",
            "Why the rack matters",
            "Several per category is fine, and the rack lives in Settings \u203a Session "
            + "afterwards. The length track runs expansion-only until a cylinder marked "
            + "for length is listed, and the oversize reduction needs a bore."
            + (a.trainerOnboardLengthOn
               ? " With length on, take a baseline girth and BPSSL measurement after "
                 + "setup \u2014 the ladder reads both." : ""));
        /* THE FIRST-RUN SETUP'S RACK, the same code (0.10, the owner's preference): a card
         * per cylinder with an Edit, a dashed Add, and the bottom sheet with its drawing of
         * where to measure and its size chips. It replaced Settings' rows, which had in turn
         * replaced a line of text that minted every tube at 5.0 x 23.0 cm. A change redraws
         * the wizard rather than navigating away. Here a card also puts its tube in use, and
         * Remove asks first - this rack may already have readings beside it. */
        // A rack already listed - in the first-run setup, or in Settings - is said to be set:
        // only the cylinders listed BEFORE this setup (device check EMU9 M6 - one just added
        // here was "Already set · Kept as it is").
        int before = Math.min(a.trainerOnboardCylsBefore, a.model.cylinders.size());
        if (before > 0) {
            int act = a.model.activeCylinderIndex();
            alreadySet(panel, TrainerOnboard.cylindersSummary(
                a.model.cylinders.subList(0, before), act < before ? act : -1),
                TrainerOnboard.CYL_KEPT);
        }
        kit().rack(panel, new RackRedraw(), true, true);
        // What the setup does not show and this step needs: what each tube is FOR at the
        // logged girth - a length tube's pull and its binding limit, an oversize girth tube's
        // reduction (Traction#fit, the rack's own lines).
        // Read against the setup's own answers - the months, new to pumping - not the plan on
        // file, which a first setup has not written yet (device walk, E-I1).
        /* ONE LINE PER CYLINDER, THE MATHS BEHIND ITS \u24d8 (polish SU-7 / NEW-19): the notes
         * ran to four grey paragraphs after the list. Drawn by the app's own fit notes, then
         * each cylinder's paragraphs folded into one line - its first sentence - and a \u24d8
         * holding all of them, word for word; "tube" said as "cylinder". */
        LinearLayout fit = Ui.col(a);
        a.cylinderFitNotes(fit, onboardScratch());
        foldFitNotes(fit, panel);
    }

    /** Moves `from`'s views into `to`: a label stays a label; a run of notes after it becomes
     *  one noteInfo (the first sentence, and the run in full behind the \u24d8). */
    private void foldFitNotes(LinearLayout from, LinearLayout to) {
        String title = "This cylinder";
        StringBuilder run = new StringBuilder();
        while (from.getChildCount() > 0) {
            View v = from.getChildAt(0);
            from.removeViewAt(0);
            boolean label = v instanceof TextView && ((TextView) v).getLetterSpacing() > 0f;
            if (!(v instanceof TextView) || label) {
                flushFitRun(to, title, run);
                if (label) title = String.valueOf(v.getContentDescription());
                to.addView(v);
                continue;
            }
            String s = cylinderWord(String.valueOf(((TextView) v).getText()));
            if (run.length() > 0) run.append("\n\n");
            run.append(s);
        }
        flushFitRun(to, title, run);
    }

    private void flushFitRun(LinearLayout to, String title, StringBuilder run) {
        if (run.length() == 0) return;
        String all = run.toString();
        String first = firstSentence(all);
        if (first.equals(all)) Ui.note(a, to, all);
        else Ui.noteInfo(a, to, first, title, all);
        run.setLength(0);
    }

    /** "cylinder", never "tube", in what the setup shows (polish SU-7). */
    static String cylinderWord(String s) {
        return s == null ? "" : s.replace(" tubes", " cylinders").replace(" tube", " cylinder");
    }

    /** The text up to its first full stop or semicolon, as a sentence. */
    static String firstSentence(String s) {
        if (s == null) return "";
        int end = s.length();
        int p = s.indexOf(". "), q = s.indexOf("; "), n = s.indexOf('\n');
        if (p >= 0) end = Math.min(end, p);
        if (q >= 0) end = Math.min(end, q);
        if (n >= 0) end = Math.min(end, n);
        return end >= s.length() ? s : Say.sentence(s.substring(0, end));
    }

    /** Redraws the wizard after a change to the rack. */
    private final class RackRedraw implements SetupKit.RackListener {
        @Override public void rackChanged(boolean saved) { showTrainer(); }
    }

    /** The first-run setup's controls, shared (SetupKit). */
    private SetupKit kit;

    private SetupKit kit() {
        if (kit == null) kit = new SetupKit(a);
        return kit;
    }


    /** Setup step 5 \u2014 the Program (Q2/Q15): presets first, then the pickers, then
     *  a live preview of the first routine exactly as the plan would write it. */
    private void buildOnboardProgram(LinearLayout panel) {
        Ui.note(a, panel, "Pick a starting style, then change any row below.");
        /* THE PRESETS SHOW WHICH ONE IS IN FORCE (polish TR-19 / SU-9): four equal buttons with
         * no chosen state became a radio list. A row changed by hand matches none of them, and
         * then none is chosen (the "Custom" case). */
        View.OnClickListener[] presets = new View.OnClickListener[PRESET_NAMES.length];
        for (int i = 0; i < presets.length; i++) presets[i] = new OnboardPresetTap(i);
        Ui.choiceList(a, panel, PRESET_NAMES, null, onboardPreset(), presets);
        Model scratch = onboardScratch();
        if (a.trainerOnboardGirthOn) {
            Ui.microLabel(a, panel, "GIRTH", Ui.DIM);
            programPickers(panel, a.trainerOnboardProgG, true, scratch);
        }
        if (a.trainerOnboardLengthOn) {
            Ui.microLabel(a, panel, "LENGTH", Ui.DIM);
            programPickers(panel, a.trainerOnboardProgL, false, scratch);
        }
        Ui.microLabel(a, panel, "PREVIEW", Ui.DIM);
        Ui.note(a, panel, programPreview());
    }

    /** The setup's four starting styles (polish TR-19: "Guidance standard" is "Standard"). */
    private static final String[] PRESET_NAMES = { "Standard", "Gentle start", "Ramped",
        "Time-saver" };

    /** The preset both Programs are set to now, or -1 when a row was changed by hand (or the
     *  two tracks differ) - OnboardPresetTap's own table, read back. */
    private int onboardPreset() {
        for (int w = 0; w < PRESET_NAMES.length; w++) {
            Model.Program want = presetProgram(w);
            if (sameProgram(want, a.trainerOnboardProgG)
                    && (!a.trainerOnboardLengthOn || sameProgram(want, a.trainerOnboardProgL)))
                return w;
        }
        return -1;
    }

    private static boolean sameProgram(Model.Program x, Model.Program y) {
        return x != null && y != null && x.warm == y.warm && x.work == y.work
            && x.pressure == y.pressure && x.rest == y.rest && x.fatigue == y.fatigue;
    }

    /** What preset `which` sets a Program to (OnboardPresetTap writes exactly this). */
    private static Model.Program presetProgram(int which) {
        Model.Program p = new Model.Program();
        p.warm = Model.Program.WARM_STANDARD; p.work = Model.Program.WORK_FIXED;
        p.pressure = Model.Program.PRESS_STANDARD;
        p.rest = Model.Program.REST_STANDARD;
        p.fatigue = Model.Program.FAT_STANDARD;
        if (which == 1) { p.pressure = Model.Program.PRESS_GENTLE;
                          p.rest = Model.Program.REST_LONG; }
        if (which == 2) { p.work = Model.Program.WORK_RAMP_IN_SET;
                          p.warm = Model.Program.WARM_RAMP; }
        if (which == 3) { p.rest = Model.Program.REST_SHORT; }
        return p;
    }

    // Sentence case (polish TR-19): values read "Standard", "Fixed holds".
    private static final String[] WARM_NAMES = { "None \u2014 leaves the guidance's plan",
        "Short (2 min)", "Standard", "Climb (ramp)" };
    private static final String[] WORK_NAMES = { "Fixed holds", "Ramp in each set",
        "Ascending", "Pyramid" };

    /** The pressure picker's values: Scale#biasName in sentence case, the plan's own figure
     *  said as "Plan’s own" (polish TR-19). */
    private static String pressureName(int bias) {
        return bias == Model.Program.PRESS_STANDARD ? PLAN_OWN : Say.capitalise(Scale.biasName(bias));
    }

    private static String[] pressureNames() {
        return new String[]{ pressureName(0), pressureName(1), pressureName(2) };
    }

    /** The work-shape names as the row lists them (#workLabel each). */
    private static String[] workLabels(Model m, Mint.Rx rx) {
        String[] out = new String[WORK_NAMES.length];
        for (int i = 0; i < out.length; i++)
            out[i] = m == null ? WORK_NAMES[i] : workLabel(i, m, rx);
        return out;
    }
    /* The pressure picker's names are Scale#biasName (0.10): "gentle (plan -1.0 inHg)",
     * "standard (the plan)", "firm (plan +1.0 inHg)" in the person's own unit - what each
     * does, now that gentle and firm are one inHg off the plan rather than the band's ends. */

    /**
     * AUDIT D8 - A SHAPE THAT CANNOT ACT SAYS SO. The picker offers every shape whatever the
     * prescription, and some build exactly the fixed holds at it: ascending and pyramid at one
     * block (Level 1, length), a ramp under a Gentle bias (nothing between the floor and the
     * figure to climb), a pyramid on a traction coda (one set cannot peak mid-way). The choice
     * used to be taken and silently do nothing. The builder answers (RxBuild#workShapeActs,
     * a scratch build of today's prescription in the shape and in fixed holds), and a shape
     * that changes nothing today is labelled so - it is still offered, since the plan's next
     * block count may be one it acts at.
     */
    private static String workLabel(int work, Model m, Mint.Rx rx) {
        String name = WORK_NAMES[work];
        return rx == null || RxBuild.workShapeActs(m, rx, work) ? name
            : name + " \u2014 runs as fixed holds today";
    }

    /** Today's prescription for the room's girth or length picker. */
    private Mint.Rx roomRx(boolean girth) {
        int track = girth ? a.model.trainerGirthStyle : Plan.TRACK_LENGTH;
        Model.TrainerTrackState st = girth ? a.model.trainerGirth : a.model.trainerLength;
        SessionActivity.TrainerEval e = a.evalTrack(track, st, System.currentTimeMillis());
        return e == null ? null : e.rx;
    }
    private static final String[] REST_NAMES = { "Short", "Standard", "Long" };
    private static final String[] FAT_NAMES = { "Standard", "Extended",
        "Off \u2014 leaves the guidance's plan" };

    /** The room's live pickers: same rows as setup's, writing the MODEL directly and
     *  letting the plan rewrite its routines (auto-apply, notice + Undo). Each is a value row
     *  that opens its list (polish SYS-3 / TR-19); it cycled on tap with no sign it could. */
    private void programRoomPickers(LinearLayout panel, Model.Program pr, boolean girth) {
        Ui.choiceRow(a, panel, "Warm-up", WARM_NAMES, null, pr.warm, a.rootFrame,
            new RoomProgChoice(pr, 0));
        Ui.choiceRow(a, panel, "Work sets", workLabels(a.model, roomRx(girth)), null, pr.work,
            a.rootFrame, new RoomProgChoice(pr, 1));
        Ui.choiceRow(a, panel, "Pressure", pressureNames(), null, pr.pressure, a.rootFrame,
            new RoomProgChoice(pr, 2));
        if (girth)
            Ui.choiceRow(a, panel, "Rests", REST_NAMES, null, pr.rest, a.rootFrame,
                new RoomProgChoice(pr, 3));
        Ui.choiceRow(a, panel, "Fatigue block", FAT_NAMES, null, pr.fatigue, a.rootFrame,
            new RoomProgChoice(pr, 4));
        if (pr.leavesTheGuidance())
            Ui.microLabel(a, panel, "Leaves the guidance's plan", Ui.CMD);
    }

    /** Item 7: the shears toggle in ITS room. Same write ToggleSupersetRests made from
     *  Settings; the redraw is this screen's. Words-only option, so no re-sign / resync. */
    private final class ToggleShearsRoomTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.rxSupersetRests = !a.model.rxSupersetRests;
            Store.save(a, a.model);
            showTrainer();
        }
    }

    /** Writes one Program field - the value a row's list picked (or Undo put back). */
    private static void setProgField(Model.Program pr, int field, int v) {
        switch (field) {
            case 0: pr.warm = Say.clampI(v, 0, 3); break;
            case 1: pr.work = Say.clampI(v, 0, 3); break;
            case 2: pr.pressure = Say.clampI(v, 0, 2); break;
            case 3: pr.rest = Say.clampI(v, 0, 2); break;
            default: pr.fatigue = Say.clampI(v, 0, 2); break;
        }
    }

    /** A room picker's choice: the same write the cycling tap made, then the plan rewrites. */
    private final class RoomProgChoice implements Ui.Choice {
        private final Model.Program pr;
        private final int field;
        RoomProgChoice(Model.Program p, int f) { pr = p; field = f; }
        @Override public void choose(int i) {
            setProgField(pr, field, i);
            Store.save(a, a.model);
            a.syncPlanRoutines();   // Q14 — rewrite at once, notice + Undo
            showTrainer();
        }
    }

    private void programPickers(LinearLayout panel, Model.Program pr, boolean girth,
                                Model scratch) {
        // The setup writes nothing until Confirm, so its rows take no Undo snack.
        Ui.choiceRow(a, panel, "Warm-up", WARM_NAMES, null, pr.warm, null,
            new ProgChoice(pr, 0));
        Mint.Rx rx = scratch == null ? null : onboardRx(scratch, girth);
        Ui.choiceRow(a, panel, "Work sets", workLabels(scratch, rx), null, pr.work, null,
            new ProgChoice(pr, 1));
        Ui.choiceRow(a, panel, "Pressure", pressureNames(), null, pr.pressure, null,
            new ProgChoice(pr, 2));
        if (girth)   // the length gaps are the prescription's own (wave 3a ruling)
            Ui.choiceRow(a, panel, "Rests", REST_NAMES, null, pr.rest, null,
                new ProgChoice(pr, 3));
        Ui.choiceRow(a, panel, "Fatigue block", FAT_NAMES, null, pr.fatigue, null,
            new ProgChoice(pr, 4));
    }

    /** A setup picker's choice: the setup's own copy, redrawn. */
    private final class ProgChoice implements Ui.Choice {
        private final Model.Program pr;
        private final int field;
        ProgChoice(Model.Program p, int f) { pr = p; field = f; }
        @Override public void choose(int i) {
            setProgField(pr, field, i);
            showTrainer();
        }
    }

    private final class OnboardPresetTap implements View.OnClickListener {
        private final int which;
        OnboardPresetTap(int w) { which = w; }
        @Override public void onClick(View v) {
            Model.Program[] both = { a.trainerOnboardProgG, a.trainerOnboardProgL };
            Model.Program want = presetProgram(which);
            for (int i = 0; i < both.length; i++) {
                Model.Program p = both[i];
                p.warm = want.warm; p.work = want.work; p.pressure = want.pressure;
                p.rest = want.rest; p.fatigue = want.fatigue;
            }
            if (which == 1) a.trainerOnboardMarks = true;
            a.toast(PRESET_NAMES[which] + " applied \u2014 change any row below");
            showTrainer();
        }
    }

    /** The first routine as the current answers would mint it \u2014 built into a
     *  SCRATCH model so nothing is saved, through the same RxBuild path the real mint
     *  uses. */
    private String programPreview() { return programPreview(null); }

    /** The model as the setup's answers so far would make it - a SCRATCH copy, never saved:
     *  the Programs, the hybrid and the marks as picked. Null when it cannot be made. */
    private Model onboardScratch() {
        try {
            Model scratch = Model.fromJson(a.model.toJson().toString());
            scratch.programGirth = cloneProgram(a.trainerOnboardProgG);
            scratch.programLength = cloneProgram(a.trainerOnboardProgL);
            scratch.trainerGirthHybrid = a.trainerOnboardHybrid;
            scratch.marksEasily = a.trainerOnboardMarks;
            /* THE LIMITS AS CONFIRM WILL WRITE THEM (0.10): new to pumping, the months answer
             * dated now and "Most you will go to" - so the preview is held to the same hard
             * limits the plan's routines will be (Scale#hardKpa). */
            scratch.rxNewToPumping = a.trainerOnboardNew;
            scratch.trainerEnrolled = true;
            scratch.trainerMonthsPumping = Math.max(0, a.trainerOnboardMonths);
            scratch.trainerMonthsAt = System.currentTimeMillis();
            scratch.rxWorkMaxKpa = a.trainerOnboardMaxKpa;
            scratch.rxLengthMaxKpa = a.trainerOnboardLengthMaxKpa;
            return scratch;
        } catch (Exception e) {
            return null;
        }
    }

    /** The first prescription the setup's answers give the girth or the length track, with
     *  that track's position written into `scratch` as the plan would write it. */
    private Mint.Rx onboardRx(Model scratch, boolean girth) {
        int month = Plan.monthIndex(Math.max(0, a.trainerOnboardMonths) * 4);
        if (girth) {
            TrainerTab.Derived g = onboardGirth(girthBasisKpa(), scratch.ceilKpa,
                                                a.trainerOnboardMaxKpa);
            scratch.trainerGirth.level = g.level;
            scratch.trainerGirth.weekIndex = g.weekIndex;
            // The answer as the plan's figure and the person's offset (0.10), exactly as
            // Confirm writes them, so the preview is the routine that will be built.
            double[] gs = onboardGirthSplit();
            scratch.trainerGirth.setWorkingPressure(gs[0], 1L);
            scratch.trainerGirth.offsetKpa = gs[1];
            // (The time cap with the person's own fatigue block, as the run - review B F3.)
            return Mint.prescribe(a.trainerOnboardGirthStyle, g.level, g.weekIndex,
                gs[0], month, scratch.ceilKpa, 0, 0, null,
                Scale.limitsOf(scratch, a.trainerOnboardGirthStyle), 0,
                Mint.r2FatSec(scratch, a.trainerOnboardGirthStyle, g.level));
        }
        TrainerTab.Derived l = TrainerTab.deriveLength(a.trainerOnboardMonths, lengthBasisKpa(),
            a.trainerOnboardNew, scratch.ceilKpa, a.trainerOnboardLengthMaxKpa);
        scratch.trainerLength.level = l.level;
        scratch.trainerLength.weekIndex = l.weekIndex;
        double[] ls = onboardLengthSplit();
        scratch.trainerLength.setWorkingPressure(ls[0], 1L);
        scratch.trainerLength.offsetKpa = ls[1];
        scratch.trainerLength.loadLb = onboardLoadLb();
        // ...and the strain sets Confirm writes (t10 R-45, LengthTrack#atSetup; R11-3, placed).
        LengthTrack.atSetup(scratch, System.currentTimeMillis());
        LengthTrack.placedAt(scratch, onboardStrainSets(scratch), System.currentTimeMillis());
        return Mint.prescribe(Plan.TRACK_LENGTH, l.level, l.weekIndex, ls[0], month,
            scratch.ceilKpa, 0, 0, null, Scale.limitsOf(scratch, Plan.TRACK_LENGTH));
    }

    /** The same preview; `totalSec`, when given, receives the two routines' planned seconds
     *  added together - what one day of both tracks runs to (S15's combined week). */
    private String programPreview(long[] totalSec) {
        try {
            Model scratch = onboardScratch();
            if (scratch == null) return "Preview unavailable";
            StringBuilder out = new StringBuilder();
            if (a.trainerOnboardGirthOn) {
                Mint.Rx rx = onboardRx(scratch, true);
                Model.Routine r = scratch.routine(RxBuild.routineFromRx(scratch, rx));
                if (totalSec != null) totalSec[0] += scratch.routineSec(r);
                out.append("Girth: ").append(r.stages.size()).append(" stages  \u00b7  ")
                   .append(Model.Fmt.t(scratch.routineSec(r))).append("  \u00b7  peak ")
                   .append(Model.Fmt.p(scratch.workPeakKpa(r)));
                if (a.trainerOnboardProgG.leavesTheGuidance())
                    out.append("  \u00b7  leaves the guidance's plan");
            }
            if (a.trainerOnboardLengthOn) {
                if (out.length() > 0) out.append("\n");
                Mint.Rx rx = onboardRx(scratch, false);
                Model.Routine r = scratch.routine(RxBuild.routineFromRx(scratch, rx));
                if (totalSec != null) totalSec[0] += scratch.routineSec(r);
                out.append("Length: ").append(r.stages.size()).append(" stages  \u00b7  ")
                   .append(Model.Fmt.t(scratch.routineSec(r))).append("  \u00b7  peak ")
                   .append(Model.Fmt.p(scratch.workPeakKpa(r)));
                if (a.trainerOnboardProgL.leavesTheGuidance())
                    out.append("  \u00b7  leaves the guidance's plan");
            }
            return out.length() == 0 ? "Turn a track on to preview its routine."
                                     : out.toString();
        } catch (Exception e) {
            return "Preview unavailable \u2014 " + e.getClass().getSimpleName();
        }
    }

    /** Setup step 6 — the week (Q11): the sheet says three days a week; this makes
     *  the schedule say the same thing instead of silently training all seven. */
    private void buildOnboardWeek(LinearLayout panel) {
        /* t10 R-60 - WITH BOTH TRACKS ON, LONG TRAINING DAYS IS ASKED HERE, the four choices the
         * Trainer page's row cycles (LongDays): alternate days, each track 3 days a week (a new
         * setup's default); alternate on my days; same days, stopping growth at 90 minutes;
         * and same days as now. It replaces S15's two shapes, which were the first two. The
         * week each one gives is previewed from the days below (onboardWeekPreview). */
        boolean shapes = weekShapeOffered();
        int ld = shapes ? a.trainerOnboardLongDays : Schedule.LONG_COMBINED;
        boolean split = ld == Schedule.LONG_SPLIT;
        boolean alt = ld == Schedule.LONG_ALTERNATE;
        Schedule preview = onboardWeekPreview(ld);
        long[] daySecs = shapes ? onboardDaySecs(!split && !alt) : new long[3];
        if (shapes) {
            /* F1 - THE DAYS ALREADY PICKED ARE SAID ABOVE THE CHOICE (polish): the first-run
             * setup asked for them minutes ago, and the default choice below runs Monday to
             * Saturday instead - its effect line says so. */
            boolean[] mine = a.trainerOnboardDays;
            if (a.trainerOnboardWeekWasSet)
                Ui.kvRow(a, panel, "Your days", onboardDaysWords(mine), Ui.TEXT, null);
            Ui.sectionHead(a, panel, LongDays.ROW, Ui.TEXT);
            /* REAL RADIO ROWS (polish SU-10): the four were outlined buttons starting with a text
             * bullet, alike chosen or not, with one effect line after all four. Each now says
             * its own effect under its label, and the chosen one is filled and checked. */
            String[] effects = new String[LongDays.LABELS.length];
            View.OnClickListener[] picks = new View.OnClickListener[LongDays.LABELS.length];
            for (int v = Schedule.LONG_SPLIT; v <= Schedule.LONG_COMBINED; v++) {
                effects[v] = LongDays.effect(a.model.sched, v)
                    + (v == Schedule.LONG_SPLIT && a.trainerOnboardWeekWasSet
                       && !java.util.Arrays.equals(mine, onboardWeekPreview(v).daysInForce())
                       ? " Uses Mon\u2013Sat instead of your days." : "");
                picks[v] = new SetOnboardLongDaysTap(v);
            }
            Ui.choiceList(a, panel, LongDays.LABELS, effects, ld, picks);
            Ui.noteInfo(a, panel, "How a week of both tracks is counted", LongDays.ROW,
                LONG_DAYS_WHY);
            if (alt) {
                /* S15 FOLLOW-UP (the owner's decision, 2026-09-26): WHICH TRACK LEADS IS ASKED.
                 * It is not in the material, so it is the person's: neither answer is marked
                 * until they pick one. t10: the answer is "Length first" (Model#rxLengthFirst),
                 * which leads an "Alternate on my days" week; until it is given, that order
                 * leads, and it is said. */
                Ui.sectionHead(a, panel, Schedule.ALTERNATE_LEAD_QUESTION, Ui.TEXT);
                /* THE ORDER THAT APPLIES IS SHOWN CHOSEN (polish SU-11): unanswered, the week
                 * runs with the stored lead, else length first (Schedule#alternateLead) - that
                 * one is drawn selected, display only; nothing is written until Confirm. */
                boolean lengthLeads = preview.alternateLead() == Schedule.PLAN_LENGTH;
                Ui.segmented(a, panel, new String[]{ "Length", "Girth" }, null,
                    lengthLeads ? 0 : 1,
                    new View.OnClickListener[]{
                        new SetOnboardLeadTap(Schedule.PLAN_LENGTH),
                        new SetOnboardLeadTap(Schedule.PLAN_GIRTH) });
                Ui.note(a, panel, lengthLeads ? "Length starts the week. Pick girth to swap."
                                              : "Girth starts the week. Pick length to swap.");
                redLine(panel, onboardShortLine(preview));
            }
            if (split || alt) {
                long day = DayLength.setupDaySec(daySecs[0], daySecs[1], true);
                if (DayLength.reaches(day))
                    longDayNotice(panel, "Your longest training day will be about "
                        + DayLength.minutes(day) + " minutes.", "", DayLength.WHY_ONE);
            } else {
                /* THE DAY AS THE PLAN RUNS IT (t10 device walk M3): girth and length each as a
                 * day of both builds them (DayLength#bothDayFlags - no coda, girth after length
                 * without its fatigue block, no second warm-up), warm-ups and rests included,
                 * the feeders not in it - the same figure the Trainer page's notice and the
                 * 90-minute check give. Said ONCE: as the notice's headline when it reaches
                 * ninety minutes, else as a line. The choice that shortens it is the first
                 * option above ("Alternate days, each track 3 days a week", K10), so there is
                 * no second button for it here. */
                long day = DayLength.setupDaySec(daySecs[0], daySecs[1], false);
                if (DayLength.reaches(day)) {
                    longDayNotice(panel, DayLength.headline(-1, true, day),
                        DayLength.partsLine(daySecs), DayLength.WHY_BOTH);
                } else if (day > 0) {
                    Ui.note(a, panel, "Both tracks on each training day: about "
                        + DayLength.minutes(day) + " min a day.");
                }
            }
        }
        /* ALREADY SET, AND SAID SO (0.10): a week chosen in the first-run setup or Settings
         * opens as that week - summarised, kept unless changed - with one edit that opens the
         * days, the time and the reminders below. The days said are the ones the choice above
         * runs. */
        if (a.trainerOnboardWeekWasSet && !a.trainerOnboardWeekEdit) {
            /* ONE ROW, NOT A BLOCK (polish SU-13): a lime ALREADY SET label over a 22sp summary
             * outweighed the question above it. The days the choice runs, the time and the
             * reminders, as a row that opens the editor. */
            LinearLayout row = Ui.kvRow(a, panel, TrainerOnboard.WEEK_ROW,
                TrainerOnboard.weekSummary(preview.daysInForce(), a.trainerOnboardHour,
                    a.model.sched.minute, a.trainerOnboardRemind),
                Ui.TEXT, new EditOnboardWeekTap());
            chevron(row);
            return;
        }
        if (split) {
            // "WHATEVER DAYS ARE TICKED": this choice is its own week, so there are no days to
            // tick here - they stay as they were for the other three choices.
            Ui.note(a, panel, LongDays.weekLine(preview) + ". Sunday is a rest day.");
        } else {
            Ui.note(a, panel, "The plan scores the days you pick. Track-per-day and one-off "
                + "overrides live in Settings › Training schedule.");
            // POLISH #1: the labels follow Schedule's own MON=0..SUN=6 order — the Sun-first
            // list made row i label one day and write another.
            String[] names = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
            for (int i = 0; i < 7; i++) {
                String label = names[i];
                if (alt && a.trainerOnboardDays[i])
                    label += "  ·  " + Schedule.planLabel(preview.planOn(i));
                Ui.kvRow(a, panel, label, a.trainerOnboardDays[i], new OnboardDayTap(i));
            }
        }
        Ui.stepperRow(a, panel, "Time", String.format(java.util.Locale.US, "%02d:00",
            a.trainerOnboardHour), new OnboardHourTap(-1), new OnboardHourTap(1));
        Ui.kvRow(a, panel, "Reminders", a.trainerOnboardRemind, new OnboardRemindTap());
    }

    /** The week the setup's answers give under Long training days `longDays`: the days
     *  picked, both tracks on, led by the lead answered - else, under "Alternate on my days",
     *  by the week's own stored lead where it still alternates these days (O2), else by
     *  "Length first" - as Confirm writes it (Schedule#alternateLead, #leadWith). */
    private Schedule onboardWeekPreview(int longDays) {
        Schedule s = new Schedule();
        if (a.trainerOnboardDays != null)
            System.arraycopy(a.trainerOnboardDays, 0, s.days, 0, Schedule.DAYS);
        if (longDays == Schedule.LONG_ALTERNATE)
            System.arraycopy(a.model.sched.plan, 0, s.plan, 0, Schedule.DAYS);
        s.longDays = longDays;
        s.follow(weekShapeOffered(), onboardLengthLeads());
        if (onboardLeadAnswered()) s.leadWith(onboardLengthLeads());
        return s;
    }

    /** Whether the setup's lead question was answered (Length or Girth). */
    private boolean onboardLeadAnswered() {
        return a.trainerOnboardAltLead == Schedule.PLAN_LENGTH
            || a.trainerOnboardAltLead == Schedule.PLAN_GIRTH;
    }

    /** Which track leads an alternate week at Confirm: the setup's answer, else the order
     *  already set for a day of both. */
    private boolean onboardLengthLeads() {
        if (a.trainerOnboardAltLead == Schedule.PLAN_LENGTH) return true;
        if (a.trainerOnboardAltLead == Schedule.PLAN_GIRTH) return false;
        return a.model.rxLengthFirst;
    }

    /** The answers that place the person, as this setup has them (TrainerOnboard.Position). */
    private TrainerOnboard.Position onboardPosition() {
        TrainerOnboard.Position p = new TrainerOnboard.Position();
        p.months = a.trainerOnboardMonths;
        p.girthStyle = a.trainerOnboardGirthStyle;
        p.girthKpa = a.trainerOnboardPressureKpa;
        p.lengthKpa = a.trainerOnboardLengthKpa;
        p.girthOn = a.trainerOnboardGirthOn;
        p.lengthOn = a.trainerOnboardLengthOn;
        p.isNew = a.trainerOnboardNew;
        p.layoff = a.trainerOnboardLayoff;
        p.girthMin = a.trainerOnboardGirthMin;
        p.lengthMin = a.trainerOnboardLengthMin;
        return p;
    }

    /** t10 review D, F1 - whether Confirm keeps the position: the upgrade card's setup, with
     *  every answer that places the person still as it was seeded (TrainerOnboard#positionKept).
     *  The confirmation then shows the position kept, and Confirm writes the other answers. */
    private boolean onboardKeepsPosition() {
        return a.trainerOnboardUpgrade && !a.trainerRecalibrating
            && TrainerOnboard.positionKept(a.trainerOnboardSeed, onboardPosition());
    }

    /** "L3, week 2, at −8.9 inHg" - a track's position as it stands. */
    private static String positionLine(Model.TrainerTrackState st) {
        return TrainerTab.levelLabel(st.level) + ", week " + Math.max(1, st.weekIndex)
            + ", at " + Model.Fmt.p(Math.round(st.pressureKpa));
    }

    /** The red line for the setup's week (LongDays#shortLine over the preview). */
    private String onboardShortLine(Schedule preview) {
        Model probe = new Model();
        probe.trainerEnrolled = true;
        probe.trainerGirthOn = true;
        probe.trainerLengthOn = true;
        probe.trainerGirthStyle = a.trainerOnboardGirthStyle;
        probe.sched = preview;
        return LongDays.shortLine(probe);
    }

    /** R-60's WARNING: a fact the week cannot work under, on the face. A callout on SURFHI
     *  with a ⚠ in amber and the words in TEXT (polish SU-12) - it was red body text, and red
     *  is kept for unsafe or failed; a week whose plan will not move is neither. Nothing is
     *  drawn for "". */
    private void redLine(LinearLayout panel, String line) {
        if (line == null || line.length() == 0) return;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        // Top-aligned, not baseline-aligned (device check EMU9 M5): the larger ⚠ moved the
        // text's baseline down and its last line was cut off at the box's edge.
        box.setBaselineAligned(false);
        box.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL));
        box.setPadding(Ui.dp(a, Look.S4), Ui.dp(a, Look.S3), Ui.dp(a, Look.S4),
                       Ui.dp(a, Look.S3));
        TextView g = new TextView(a);
        g.setText("\u26a0");
        g.setTextColor(Ui.CMD);
        g.setTextSize(Look.SP_BODY);
        g.setPadding(0, 0, Ui.dp(a, Look.S3), 0);
        Ui.decorative(g);
        box.addView(g);
        TextView t = new TextView(a);
        t.setText(line);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(Look.SP_CAPTION);
        box.addView(t, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, Look.S2);
        lp.bottomMargin = Ui.dp(a, Look.S4);
        panel.addView(box, lp);
    }

    /** The chevron icon at a row's end: the row opens something (polish SYS-5). */
    private void chevron(LinearLayout row) {
        android.widget.ImageView ch = Ui.iconView(a, R.drawable.ic_chevron_right, Ui.DIM, 20);
        ((LinearLayout.LayoutParams) ch.getLayoutParams()).leftMargin = Ui.dp(a, Look.S2);
        row.addView(ch);
    }

    /** "Tue, Thu, Sat" - the days the setup has ticked (TrainerOnboard#daysRuns). */
    private static String onboardDaysWords(boolean[] days) {
        Schedule s = new Schedule();
        for (int i = 0; i < Schedule.DAYS; i++)
            s.days[i] = days != null && i < days.length && days[i];
        return TrainerOnboard.daysRuns(s);
    }

    /** Long training days' reasons, behind the \u24d8 in the setup and on the Trainer's sheet. */
    static final String LONG_DAYS_WHY = "The guidance keeps a day of both tracks under "
        + DayLength.NOTICE_MIN + " minutes and allows girth and length on alternate days "
        + "instead. " + Say.WEEK_RULE;

    /** {girth, length, feeders} seconds of one day of the routines the setup's answers build,
     *  as DayLength counts a day: each track's routine, and the feeders when they are asked
     *  for and the girth level has them. Zeros when a preview cannot be built. */
    private long[] onboardDaySecs(boolean bothTracks) {
        long[] out = new long[3];
        try {
            Model scratch = onboardScratch();
            if (scratch == null) return out;
            boolean both = bothTracks && a.trainerOnboardGirthOn && a.trainerOnboardLengthOn;
            if (a.trainerOnboardGirthOn) {
                Mint.Rx rx = onboardRx(scratch, true);
                out[0] = onboardRunSec(scratch, rx, false, both);
                if (a.trainerOnboardFeeder && Plan.feederEligible(rx.level)) {
                    int month = Plan.monthIndex(Math.max(0, a.trainerOnboardMonths) * 4);
                    Mint.Rx f = Mint.prescribe(Plan.TRACK_FEEDER, rx.level, 0,
                        scratch.trainerGirth.pressureKpa, month, scratch.ceilKpa, 0, 0, null,
                        Scale.limitsOf(scratch, Plan.TRACK_FEEDER));
                    out[2] = Plan.FEEDER_PER_DAY
                        * scratch.routineSec(scratch.routine(RxBuild.routineFromRx(scratch, f)));
                }
            }
            if (a.trainerOnboardLengthOn) {
                Mint.Rx rx = onboardRx(scratch, false);
                out[1] = onboardRunSec(scratch, rx, true, both);
            }
        } catch (Exception e) {
            return new long[3];
        }
        return out;
    }

    /** One setup routine's seconds as its day runs it: on a day of both tracks built for that
     *  day (DayLength#bothDayFlags - the flags the Trainer page's day takes), else as saved. */
    private static long onboardRunSec(Model scratch, Mint.Rx rx, boolean isLength,
                                      boolean bothTracks) {
        RxBuild.Day day = RxBuild.Day.today(scratch);
        if (bothTracks) {
            boolean[] f = DayLength.bothDayFlags(isLength, scratch.rxLengthFirst);
            day = day.sameDay(f[0], f[1], f[2]);
        }
        return scratch.routineSec(scratch.routine(RxBuild.routineFromRx(scratch, rx, 0, 0, day)));
    }

    /** The long-day notice's words, in the setup's week step: the figure as a heading, what
     *  the day is made of, and why it is said. */
    private void longDayNotice(LinearLayout panel, String headline, String parts, String why) {
        TextView head = new TextView(a);
        head.setText(headline);
        head.setTextColor(Ui.TEXT);
        head.setTextSize(Look.SP_HEADING);
        head.setPadding(0, Ui.dp(a, Look.S3), 0, Ui.dp(a, Look.S1));
        panel.addView(head);
        if (parts != null && parts.length() > 0) Ui.note(a, panel, parts);
        Ui.note(a, panel, why);
    }

    /** S15: the week shapes are offered only when both tracks are on - with one track there
     *  is nothing to alternate - and only in a full setup (a recalibration has no week step). */
    private boolean weekShapeOffered() {
        return a.trainerOnboardLengthOn && a.trainerOnboardGirthOn && !a.trainerRecalibrating;
    }

    /** t10 R-60: picks Long training days in the setup's week step. The days picked are kept
     *  as they are - every choice but the first runs on them, and the first runs Monday to
     *  Saturday whatever is ticked. */
    private final class SetOnboardLongDaysTap implements View.OnClickListener {
        private final int longDays;
        SetOnboardLongDaysTap(int v) { longDays = v; }
        @Override public void onClick(View v) {
            a.trainerOnboardLongDays = longDays;
            showTrainer();
        }
    }

    /** S15 follow-up: the person's answer to which track leads an alternate week. */
    private final class SetOnboardLeadTap implements View.OnClickListener {
        private final int lead;
        SetOnboardLeadTap(int l) { lead = l; }
        @Override public void onClick(View v) {
            a.trainerOnboardAltLead = lead;
            showTrainer();
        }
    }

    private final class OnboardDayTap implements View.OnClickListener {
        private final int day;
        OnboardDayTap(int d) { day = d; }
        @Override public void onClick(View v) {
            int on = 0;
            for (int i = 0; i < 7; i++) if (a.trainerOnboardDays[i]) on++;
            if (a.trainerOnboardDays[day] && on == 1) {
                a.toast("A plan needs at least one training day");
                return;
            }
            a.trainerOnboardDays[day] = !a.trainerOnboardDays[day];
            showTrainer();
        }
    }
    private final class OnboardHourTap implements View.OnClickListener {
        private final int dir;
        OnboardHourTap(int d) { dir = d; }
        @Override public void onClick(View v) {
            a.trainerOnboardHour = (a.trainerOnboardHour + dir + 24) % 24; showTrainer(); }
    }
    /** THE REMINDER SWITCH ASKS WHERE SETTINGS' ASKS (0.10): turning it on on Android 13+
     *  without the permission asks for POST_NOTIFICATIONS at the tap, and only a grant turns
     *  it on (SessionActivity#onNotificationPermissionResult). Confirm writes it through
     *  schedSaved, which reschedules the alarm. */
    private final class OnboardRemindTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (!a.trainerOnboardRemind && !a.remindersAllowed()) {
                a.pendingOnboardRemindGrant = true;
                a.requestPermissions(new String[]{ "android.permission.POST_NOTIFICATIONS" },
                                     SessionActivity.REQ_POST_NOTIFICATIONS);
                return;
            }
            a.trainerOnboardRemind = !a.trainerOnboardRemind; showTrainer(); }
    }

    private final class EditOnboardWeekTap implements View.OnClickListener {
        @Override public void onClick(View v) { a.trainerOnboardWeekEdit = true; showTrainer(); }
    }

    /** "ALREADY SET", the answer on file, and what happens to it - the face a step shows when
     *  the person has answered it before (0.10). */
    private void alreadySet(LinearLayout panel, String summary, String kept) {
        Ui.microLabel(a, panel, TrainerOnboard.ALREADY_SET.toUpperCase(Locale.US), Ui.ACCENT);
        TextView t = new TextView(a);
        t.setText(summary);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(Look.SP_BODY);
        t.setPadding(0, Ui.dp(a, Look.S1), 0, 0);
        panel.addView(t);
        Ui.note(a, panel, kept);
    }

    private final class CommitOnboardTap implements View.OnClickListener {
        /** The one-time warning has been answered "keep" (or there was none to ask). */
        private final boolean warned;
        CommitOnboardTap() { this(false); }
        CommitOnboardTap(boolean w) { warned = w; }
        @Override public void onClick(View v) {
            /* WARNED ONCE, YOUR CALL (0.10): an answer that sets an offset above the plan, or a
             * load past the usual 12 lb, is asked about once before anything is written. */
            if (!warned && askBeforeCommit()) return;
            /* t10 review D, F1 - "YOUR VALUES ARE KEPT": the upgrade card's setup with every
             * answer that places the person as it was seeded keeps the position - the months
             * and their date, level, week, pressure and its clock, earned and carried holds,
             * the length load and its strain sets - and writes the other answers only. Decided
             * before anything is written. */
            boolean keep = onboardKeepsPosition();
            /* THE THREE ANSWERS GO IN FIRST, because the derivations below are computed
             * WITH them and everything that reads them later reads the model, not this
             * screen's copy. */
            a.model.rxNewToPumping = a.trainerOnboardNew;
            // R11-3: the minutes answered, kept for a setup run again (and an upgrader's keep).
            a.model.trainerGirthSessMin = a.trainerOnboardGirthMin;
            a.model.trainerLengthSessMin = a.trainerOnboardLengthOn ? a.trainerOnboardLengthMin
                                                                     : Placement.NOT_ANSWERED;
            /* AND THE MONTHS ANSWER IS KEPT, with the date it was given. Step 0 asks how
             * long you have been pumping in total; it used to shape the starting level and
             * week here and then be forgotten, so from the next screen on the plan's month
             * was months-since-enrolment — zero on the day you enrol, however long you have
             * actually trained. TrainerTab#monthIndexNow adds the two. */
            if (!keep) {
                a.model.trainerMonthsPumping = Math.max(0, a.trainerOnboardMonths);
                a.model.trainerMonthsAt = System.currentTimeMillis();
            }
            a.model.rxWorkMaxKpa = a.trainerOnboardMaxKpa;
            a.model.rxLengthMaxKpa = a.trainerOnboardLengthMaxKpa;
            a.model.rxDropKpa = a.trainerOnboardDropKpa;
            a.model.clampAll();
            a.model.trainerGirthStyle = a.trainerOnboardGirthStyle;
            a.model.trainerLengthOn = a.trainerOnboardLengthOn;
            long nowMs = System.currentTimeMillis();
            if (!keep) {
                // R11-3: placed by the minutes answered, else by the months as before.
                TrainerTab.Derived girth = onboardGirth(girthBasisKpa(), a.model.ceilKpa,
                                                        a.model.rxWorkMaxKpa);
                a.model.trainerGirth.level = girth.level;
                a.model.trainerGirth.weekIndex = girth.weekIndex;
                // SETUP STARTS THE PRESSURE CLOCK, whatever the figure: a step is counted from
                // the weeks run at the pressure this position prescribes.
                // THE PLAN'S FIGURE, AND THE ANSWER KEPT AS THE OFFSET (0.10, bug a): the answer
                // above or below the plan's figure for the level is the person's own pressure.
                double[] gs = onboardGirthSplit();
                a.model.trainerGirth.setWorkingPressure(gs[0], nowMs);
                a.model.trainerGirth.offsetKpa = gs[1];
                a.model.trainerGirth.restartPressureClock(nowMs);
                // The stable anchor for calendar week-table advancement (re-anchored on every
                // recalibrate, so weeks trained under an old position never advance a new one).
                a.model.trainerGirth.weekBaseIndex = girth.weekIndex;
                a.model.trainerGirth.weekBaseMs = nowMs;
                // A position set now counts its weeks from here (0.10, Mint#startWeekGrowth).
                a.model.trainerGirth.weekGrowth = true;
                // ...from the answers, as a new person's is: no build-up (the half start is for a
                // routine that predates the growth, Mint#startWeekGrowth).
                Mint.endBuildUp(a.model.trainerGirth);
                // AND THE CARRIED VOLUME (audit A18). carriedSets is the achieved volume an
                // L2->L3 promotion hands forward, and recalibrate rewrote every other field of
                // the position while leaving it untouched — so a value from an unrelated earlier
                // cycle leaked into Mint.baseSets if the new position landed on a table-less
                // level, inflating the prescription with volume this position never earned.
                a.model.trainerGirth.carriedSets = 0;
                // ...and no yield sets from a position this one replaces.
                a.model.trainerGirth.yieldSets = 0;
                a.model.trainerGirth.yieldSinceMs = nowMs;
                if (a.trainerOnboardLengthOn) {
                    // THE ANSWER, not the girth number. deriveLength still applies the
                    // guide's own cap to whatever it is given, so a typed length pressure is
                    // held to the same band a derived one was.
                    TrainerTab.Derived length = TrainerTab.deriveLength(
                        a.trainerOnboardMonths, lengthBasisKpa(),
                        a.model.rxNewToPumping, a.model.ceilKpa, a.model.rxLengthMaxKpa);
                    a.model.trainerLength.level = length.level;
                    a.model.trainerLength.weekIndex = length.weekIndex;
                    double[] ls = onboardLengthSplit();
                    a.model.trainerLength.setWorkingPressure(ls[0], nowMs);
                    a.model.trainerLength.offsetKpa = ls[1];
                    a.model.trainerLength.restartPressureClock(nowMs);
                    a.model.trainerLength.weekBaseIndex = length.weekIndex;
                    a.model.trainerLength.weekBaseMs = nowMs;
                    a.model.trainerLength.carriedSets = 0;   // same reason as girth, above
                }
            }
            /* WAVE 3b — the rest of the wizard's answers land together. */
            a.model.marksEasily = a.trainerOnboardMarks;
            a.model.trainerGirthOn = a.trainerOnboardGirthOn || !a.trainerOnboardLengthOn;
            a.model.trainerGirthHybrid = a.trainerOnboardHybrid;
            a.model.rxSupersetRests = a.trainerOnboardL4B;
            a.model.trainerFeederOptIn = a.trainerOnboardFeeder;
            a.model.programGirth = cloneProgram(a.trainerOnboardProgG);
            a.model.programLength = cloneProgram(a.trainerOnboardProgL);
            // S17 - SAME 15 lb CEILING AS THE STEPPER (BumpOnboardLoadTap's own doc): the
            // guidance's 12 lb hanger cap and its later 15 lb (month 12+) disagree, and
            // this is the wider figure, kept deliberately rather than reclamped to the
            // plan's 12 lb policy cap. Not re-clamped again when a traction routine is
            // minted (RxBuild/Mint) - Traction's per-session ceiling governs there.
            // REVIEW 2, FINDING 5: an answer hidden behind "New to pumping" is IGNORED - the
            // question and its 12 lb warning are asked only of somebody not new, and a 13 lb
            // answer given before New was turned on was saved unwarned and pulled from month 2.
            // The load is the length position's (from its pressure answer): kept with it (F1).
            if (a.trainerOnboardLengthOn && !keep) {
                a.model.trainerLength.setLoadLb(onboardLoadLb(), nowMs);
                // t10 R-45 (L1): from month 3 the guidance's 6 strain sets, handed over;
                // before it the calendar's 2 - and no block or cut record from an older plan.
                LengthTrack.atSetup(a.model, nowMs);
                /* R11-3 - OR THE STRAIN SETS THE MINUTES PLACE: worked out in a scratch copy
                 * of the model as it now stands (the length position just written), and the
                 * calendar goes on from them. */
                try {
                    Model sc = Model.fromJson(a.model.toJson());
                    LengthTrack.placedAt(a.model, onboardStrainSets(sc), nowMs);
                } catch (Exception ignored) { }
            }
            if (a.trainerOnboardDays != null) {
                // S15: the days. t10 R-60: the week's shape is Long training days now (below),
                // and each day's own plan stays the person's - so the days alone are written,
                // which is what setup always did for a week of both on the same days.
                a.model.sched.applyWeekShape(Schedule.SHAPE_COMBINED, a.trainerOnboardDays,
                                             a.trainerOnboardAltLead);
                a.model.sched.hour = a.trainerOnboardHour;
                // Only as far as the phone lets it post: a switch never claims a reminder the
                // system will not deliver (the Settings rule).
                a.model.sched.remind = a.trainerOnboardRemind && a.remindersAllowed();
            }
            /* t10 R-60, R-61 - A FULL SETUP WRITES LONG TRAINING DAYS: the choice its week step
             * showed (seeded with the person's own once enrolled, the new setup's default
             * otherwise - with one track on it is kept and acts as "same days", K21). An
             * "Alternate on my days" lead answered here is "Length first", which leads it. And
             * a full setup is what the upgrade card asked for, so it is answered. A
             * recalibration has no week step and leaves all of it as it is. */
            if (!a.trainerRecalibrating) {
                int ld = a.trainerOnboardLongDays >= 0 ? a.trainerOnboardLongDays
                                                       : Schedule.LONG_DAYS_NEW_SETUP;
                boolean leadAnswered = weekShapeOffered() && ld == Schedule.LONG_ALTERNATE
                    && onboardLeadAnswered();
                if (leadAnswered)
                    a.model.rxLengthFirst = a.trainerOnboardAltLead == Schedule.PLAN_LENGTH;
                LongDays.choose(a.model, ld);
                /* O2 - UNANSWERED, AN UPGRADER'S OWN ALTERNATE WEEK KEEPS ITS LEAD (the stored
                 * plan, Schedule#alternateLead); answered, the answer is theirs and the stored
                 * week follows it. */
                if (leadAnswered) a.model.sched.leadWith(a.model.rxLengthFirst);
                a.model.answerPlanT10Setup();
            }
            // Q13a: a recent layoff steps the starting position back two weeks — the
            // source's own step-back, applied to whichever tracks enrolled.
            if (a.trainerOnboardLayoff) {
                a.model.trainerGirth.weekIndex =
                    Math.max(0, a.model.trainerGirth.weekIndex - 2);
                a.model.trainerGirth.weekBaseIndex = a.model.trainerGirth.weekIndex;
                if (a.trainerOnboardLengthOn) {
                    a.model.trainerLength.weekIndex =
                        Math.max(0, a.model.trainerLength.weekIndex - 2);
                    a.model.trainerLength.weekBaseIndex = a.model.trainerLength.weekIndex;
                }
            }
            boolean firstEnrol = !a.model.trainerEnrolled;
            a.model.trainerEnrolled = true;
            /* SEED the traditional rest from the level being enrolled at, and only onto the
             * value nobody chose - so an untouched install runs the spec's 120 s at L1 and
             * 180 s above, while somebody who has moved that stepper keeps what they set. */
            a.model.seedTraditionalRest(a.model.trainerGirth.level);
            if (a.model.trainerEnrolledAt == 0L)
                a.model.trainerEnrolledAt = System.currentTimeMillis();
            Store.save(a, a.model);
            /* THE WEEK'S ALARM, as Settings sets it (0.10): the reminder used to be written
             * straight into the schedule and never scheduled. schedSaved clamps, saves and
             * reschedules - the path every schedule edit in Settings reaches. */
            if (a.model.sched.remind) Reminders.ensureChannel(a);
            a.schedSaved();
            a.trainerOnboardActive = false;
            showTrainer();
            /* THE LAST STEP OF SETTING UP IS HAVING SOMETHING TO RUN.
             *
             * Onboarding used to end at "Position saved" - which is true, and leaves a person
             * with a plan, no routine, and no idea that saving one is the next thing. The
             * offer follows immediately on a FIRST enrolment, with the prescription already
             * written and one button. A recalibration does not get it: that person already
             * has a routine, and the ordinary change path handles what moved. */
            if (firstEnrol) offerFirstRoutines();
            else Ui.snack(a, a.rootFrame,
                keep ? "Saved \u2014 your position is kept" : "Recalibrated");
        }
    }

    /**
     * THE ONE-TIME WARNING BEFORE SETUP WRITES ANYTHING (0.10): a girth or length answer above
     * the plan's figure (an offset past the usual top - Scale#needsWarning, against what the
     * person has already confirmed for that track) and a traction load past the usual 12 lb
     * (past the load already on file). True when it asked; Confirm then runs again, warned.
     */
    private boolean askBeforeCommit() {
        int month = onboardMonth();
        int hard = Scale.pullHardKpa(a.model.ceilKpa, a.trainerOnboardMaxKpa);
        // Length's own maximum binds length (Model#rxLengthMaxKpa).
        int hardL = Scale.pullHardKpa(a.model.ceilKpa, a.trainerOnboardLengthMaxKpa);
        StringBuilder b = new StringBuilder();
        double[] gs = onboardGirthSplit();
        // Only an offset that takes the top past the usual one in whole kPa (E-I2: never one at
        // the usual top, "plan +0.0").
        boolean needG = (a.trainerOnboardGirthOn || !a.trainerOnboardLengthOn)
            && Scale.needsWarning(gs[1], a.model.trainerGirth.warnedOffsetKpa)
            && Scale.pastUsualTop(a.trainerOnboardGirthStyle, (int) gs[2], month, gs[1]);
        if (needG)
            b.append(Scale.offsetWarning(a.trainerOnboardGirthStyle, (int) gs[2], month, gs[1],
                hard, 0, a.trainerOnboardNew));
        double[] ls = a.trainerOnboardLengthOn ? onboardLengthSplit() : null;
        boolean needL = ls != null
            && Scale.needsWarning(ls[1], a.model.trainerLength.warnedOffsetKpa)
            && Scale.pastUsualTop(Plan.TRACK_LENGTH, (int) ls[2], month, ls[1]);
        if (needL) {
            if (b.length() > 0) b.append("\n\n");
            b.append(Scale.offsetWarning(Plan.TRACK_LENGTH, (int) ls[2], month, ls[1], hardL,
                a.model.lengthBoreCm(), a.trainerOnboardNew));
        }
        // The load Confirm will write (onboardLoadLb - never a hidden answer: review 2,
        // finding 5), past the usual 12 lb and past the load already on file.
        // THE PULL IT BUILDS: the load with the length offset back on it in pounds - the
        // length pressure answered, in the length cylinder (owner, 2026-09-30).
        double load = onboardLoadLb();
        if (ls != null) load += Scale.offsetLb(ls[1], a.model.lengthBoreCm());
        // Past the plan's usual load for the month the answers put the plan in - a first
        // month's 4 lb as well as the usual 12 (plan simulator t8, finding 1).
        // Only a load the person set: one the app proposed from the plan is not "past" anything
        // they chose (device check EMU9 M7).
        boolean needLoad = a.trainerOnboardLengthOn && !a.trainerOnboardNew
            && a.trainerOnboardLoadOwn
            && Scale.pastUsualLoad(load, month)
            && load > Scale.pullLoadLb(a.model, System.currentTimeMillis()) + 1e-9;
        if (needLoad) {
            if (b.length() > 0) b.append("\n\n");
            b.append(Scale.loadWarning(load, month));
        }
        /* G2 (the owner's decision, 2026-10-01) - "MOST YOU WILL GO TO" ABOVE THE USUAL TOP:
         * the plan climbs to it, and the person is told so here, once - a maximum already on
         * file is not asked about again, a new or higher one is (the offset's own rule). */
        boolean first = !a.model.trainerEnrolled;
        if ((a.trainerOnboardGirthOn || !a.trainerOnboardLengthOn)
                && (first || a.trainerOnboardMaxKpa > a.model.rxWorkMaxKpa + 1e-6)) {
            String g = Scale.climbWords(a.trainerOnboardGirthStyle, (int) gs[2], month, gs[1],
                a.trainerOnboardNew, a.trainerOnboardMaxKpa, a.model.ceilKpa, 0);
            if (g.length() > 0) {
                if (b.length() > 0) b.append("\n\n");
                b.append(g);
            }
        }
        if (ls != null
                && (first || a.trainerOnboardLengthMaxKpa > a.model.rxLengthMaxKpa + 1e-6)) {
            String l = Scale.climbWords(Plan.TRACK_LENGTH, (int) ls[2], month, ls[1],
                a.trainerOnboardNew, a.trainerOnboardLengthMaxKpa, a.model.ceilKpa,
                a.model.lengthPulls() ? a.model.lengthBoreCm() : 0);
            if (l.length() > 0) {
                if (b.length() > 0) b.append("\n\n");
                b.append(l);
            }
        }
        if (b.length() == 0) return false;
        Ui.dress(a, Ui.dialog(a)
            .setTitle(Scale.WARN_TITLE)
            .setMessage(b.toString())
            .setPositiveButton("Keep my answers",
                new ConfirmOnboardWarning(needG ? gs[1] : -1, needL ? ls[1] : -1))
            .setNegativeButton("Change them", null)
            .show());
        return true;
    }

    /** The warning answered "keep": remembered for each track it named, then Confirm runs. */
    private final class ConfirmOnboardWarning implements DialogInterface.OnClickListener {
        private final double girthOff, lengthOff;
        ConfirmOnboardWarning(double g, double l) { girthOff = g; lengthOff = l; }
        @Override public void onClick(DialogInterface d, int w) {
            if (girthOff > 0) a.model.trainerGirth.warnedOffsetKpa =
                Scale.confirmed(girthOff, a.model.trainerGirth.warnedOffsetKpa);
            if (lengthOff > 0) a.model.trainerLength.warnedOffsetKpa =
                Scale.confirmed(lengthOff, a.model.trainerLength.warnedOffsetKpa);
            new CommitOnboardTap(true).onClick(null);
        }
    }

    /**
     * THE FOURTH SHEET: here is what the plan wrote, and one button that saves it.
     *
     * Every figure on it is the prescription the plan has just written from the answers given
     * - so it is not a demonstration, it is the thing that will be run. The week it is run on
     * shares the sheet - the person's own days, from the schedule - because it is the one
     * condition everything else in the plan is built on, and this is the only moment it can
     * be said before it matters.
     *
     * EVERY ENABLED TRACK, NOT THE FIRST (0.10, the owner's phone test and the emulator check):
     * it offered the girth routine alone, though the plan had also written a length or traction
     * routine and, from Level 3, a feeder. Each enabled track is a row. One with a routine to
     * save is ticked - its name, its routine line, "saved in two parts" where split sessions
     * are on, and a note when the guidance's table calls this week a deload. One with nothing
     * to save says why - saved already and unchanged, resting until a date, a question of its
     * own on the Trainer, nothing prescribed - and cannot be ticked. The button saves all the
     * ticked ones; nothing is saved without that tap.
     *
     * Skipping is a real answer: the rows on the Trainer are where each offer lives
     * afterwards, and nothing is broken by starting without them.
     */
    private void offerFirstRoutines() {
        long now = System.currentTimeMillis();
        RoutineOffer offer = new RoutineOffer();
        // Girth first, then length, then the feeder: the first saved is the one selected, and
        // a length-only plan's first routine IS the length routine (wave 3b, Q8).
        if (a.model.trainerGirthOn)
            offer.consider(a.model.trainerGirthStyle, a.model.trainerGirth, now);
        if (a.model.trainerLengthOn)
            offer.consider(Plan.TRACK_LENGTH, a.model.trainerLength, now);
        if (a.model.trainerFeederOptIn && Plan.feederEligible(a.model.trainerGirth.level))
            offer.consider(Plan.TRACK_FEEDER, a.model.trainerGirth, now);
        if (offer.size() == 0) {
            Ui.snack(a, a.rootFrame, "Position saved");
            return;
        }
        offer.show();
    }

    /** A track's dated rest, as the offer asks it. The feeder has none of its own. */
    private static boolean restingFor(int track, Model.TrainerTrackState st, long now) {
        return track != Plan.TRACK_FEEDER && st != null && st.resting(now);
    }

    /** Every enabled track, one row each, and which rows are ticked. */
    private final class RoutineOffer {
        final List<Integer> tracks = new ArrayList<Integer>();
        final List<Integer> states = new ArrayList<Integer>();
        final List<String> names = new ArrayList<String>();
        final List<String> lines = new ArrayList<String>();
        final List<String> notes = new ArrayList<String>();
        boolean[] ticked = new boolean[0];
        final List<TextView> boxes = new ArrayList<TextView>();
        AlertDialog dialog;

        int size() { return tracks.size(); }

        boolean saveable(int i) { return states.get(i).intValue() == TrainerOnboard.ROW_SAVE; }

        int saveableCount() {
            int n = 0;
            for (int i = 0; i < size(); i++) if (saveable(i)) n++;
            return n;
        }

        /** Adds the track's row: its routine when its card would offer a save now and it is
         *  not saved already, otherwise the reason there is nothing to save. */
        void consider(int track, Model.TrainerTrackState st, long now) {
            SessionActivity.TrainerEval e = evalFor(track, st, now);
            boolean feeder = track == Plan.TRACK_FEEDER;
            boolean resting = restingFor(track, st, now);
            boolean hasRx = e != null && e.decision != null && e.rx != null;
            int action = e == null || e.decision == null ? Plan.ACTION_HOLD : e.decision.action;
            boolean saved = false;
            if (hasRx) {
                String id = feeder ? a.model.trainerFeederMintId : st.lastMintId;
                String sig = feeder ? a.model.trainerFeederMintSig : st.lastMintSig;
                saved = Mint.alreadyMinted(sig, e.sig, a.model.routine(id) != null);
            }
            int state = TrainerOnboard.rowState(track, action, hasRx, resting, saved);
            String note = feeder ? ""
                : TrainerOnboard.tableDeloadNote(track, st.level, st.weekIndex);
            String name = TrainerOnboard.offerName(track, st.level)
                + (note.length() > 0 ? " · deload week" : "");
            String line;
            if (state == TrainerOnboard.ROW_SAVE) {
                boolean twoParts = false;
                if (!feeder) {
                    // What saveMint will build from the sheet's own starting figures, asked
                    // the same way it asks (Mint#splitsInTwo).
                    Mint.Rx use = RxBuild.adjustedRx(a.model, e.rx,
                        RxBuild.adjustStartSets(a.model, e.rx),
                        RxBuild.adjustStartKpa(a.model, e.rx));
                    boolean traction = use.track == Plan.TRACK_LENGTH
                        && a.model.tractionShapeTag(now).length() > 0;
                    twoParts = Mint.splitsInTwo(a.model.rxSplit, use.sets, use.track, traction);
                }
                line = TrainerOnboard.offerLine(a.rxLine(e.rx, 0), twoParts);
            } else {
                line = TrainerOnboard.rowReason(state,
                    resting ? a.dayLabel(st.restUntilMs) : null, action);
                if (state != TrainerOnboard.ROW_SAVED) note = "";
            }
            tracks.add(Integer.valueOf(track));
            states.add(Integer.valueOf(state));
            names.add(name);
            lines.add(line);
            notes.add(note);
        }

        int tickedCount() {
            int n = 0;
            for (int i = 0; i < ticked.length; i++) if (ticked[i]) n++;
            return n;
        }

        void show() {
            ticked = new boolean[size()];
            for (int i = 0; i < ticked.length; i++) ticked[i] = saveable(i);
            int can = saveableCount();
            LinearLayout col = Ui.col(a);
            // THE SHEET'S OWN SIDE PADDING, as every sheet in the app has it (the cylinder
            // editor's): the intro, the rows and the week line never touch the screen's edges.
            int pad = Ui.dp(a, Look.S5);
            col.setPadding(pad, Ui.dp(a, Look.S1), pad, pad);
            Ui.note(a, col, TrainerOnboard.offerIntro(size(), can));
            for (int i = 0; i < size(); i++) row(col, i);
            Ui.noteInfo(a, col,
                TrainerOnboard.weekRule(a.model.sched, a.model.trainerGirthOn,
                    a.model.trainerLengthOn, a.model.trainerGirthStyle, can > 1),
                "When a week counts",
                TrainerOnboard.weekRuleWhy());
            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(col);
            AlertDialog.Builder b = Ui.dialog(a)
                .setTitle(TrainerOnboard.offerTitle(size()))
                .setView(sv)
                .setCancelable(true);
            if (can > 0) {
                b.setPositiveButton(TrainerOnboard.saveLabel(can, can), new SaveOfferedTap(this));
                b.setNegativeButton("Not now", null);
            } else {
                b.setNegativeButton("Close", null);
            }
            dialog = b.show();
            Ui.sheet(a, dialog);
            // The setup's last screen: no app header while it shows (SessionActivity#hideTopBarWhile).
            a.hideTopBarWhile(dialog);
            refresh();
        }

        /** One track: a tick box, its name and its line - a routine's row a 56 dp target
         *  that ticks and unticks it; a row with nothing to save, quiet and inert. */
        private void row(LinearLayout col, int i) {
            boolean can = saveable(i);
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(Ui.dp(a, 56));
            r.setPadding(Ui.dp(a, 12), Ui.dp(a, Look.S3), Ui.dp(a, 12), Ui.dp(a, Look.S3));
            r.setBackground(Ui.roundRect(a, can ? Ui.SURFHI : Ui.SURF, Look.R_CARD));
            TextView box = new TextView(a);
            box.setGravity(Gravity.CENTER);
            box.setTextSize(Look.SP_CHIP);
            box.setTextColor(Look.ON_ACCENT);
            Ui.decorative(box);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24));
            bl.rightMargin = Ui.dp(a, 12);
            r.addView(box, bl);
            boxes.add(box);
            LinearLayout words = Ui.col(a);
            TextView name = new TextView(a);
            name.setText(names.get(i));
            name.setTextColor(can ? Ui.TEXT : Ui.DIM);
            name.setTextSize(Look.SP_BODY);
            Ui.medium(name);
            words.addView(name);
            TextView line = new TextView(a);
            line.setText(lines.get(i));
            line.setTextColor(Ui.DIM);
            line.setTextSize(Look.SP_CAPTION);
            words.addView(line);
            if (notes.get(i).length() > 0) {
                TextView note = new TextView(a);
                note.setText(notes.get(i));
                note.setTextColor(Ui.DIM);
                note.setTextSize(Look.SP_CAPTION);
                note.setPadding(0, Ui.dp(a, Look.S1), 0, 0);
                words.addView(note);
            }
            r.addView(words, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (can) {
                r.setClickable(true);
                r.setFocusable(true);
                r.setOnClickListener(new ToggleOfferTap(this, i));
                r.setOnTouchListener(new Ui.Press());
            } else {
                Ui.group(r, names.get(i) + ". " + lines.get(i)
                    + (notes.get(i).length() > 0 ? ". " + notes.get(i) : ""));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(a, Look.S2);
            col.addView(r, lp);
        }

        /** Every box as ticked, every row saying so, and the button saying how many. */
        void refresh() {
            for (int i = 0; i < boxes.size(); i++) {
                TextView box = boxes.get(i);
                boolean on = ticked[i];
                boolean can = saveable(i);
                box.setText(on ? "✓" : "");
                android.graphics.drawable.GradientDrawable g = Ui.roundRect(a,
                    on ? Ui.ACCENT : android.graphics.Color.TRANSPARENT, Look.R_CTRL);
                g.setStroke(Math.max(1, Ui.dp(a, 2)), on ? Ui.ACCENT : can ? Ui.DIM : Ui.LINE);
                box.setBackground(g);
                if (!can) continue;
                View r = (View) box.getParent();
                r.setSelected(on);
                r.setContentDescription(A11y.state(names.get(i), on) + ". " + lines.get(i)
                    + (notes.get(i).length() > 0 ? ". " + notes.get(i) : ""));
            }
            if (dialog == null) return;
            Button ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (ok == null) return;
            int n = tickedCount();
            ok.setText(TrainerOnboard.saveLabel(n, saveableCount()));
            // Dialog positives are dressed with a state list (Ui#dress): disabled greys it, no
            // alpha (polish SYS-10).
            ok.setEnabled(n > 0);
        }
    }

    private SessionActivity.TrainerEval evalFor(int track, Model.TrainerTrackState st, long now) {
        return track == Plan.TRACK_FEEDER ? a.evalFeeder(now) : a.evalTrack(track, st, now);
    }

    private final class ToggleOfferTap implements View.OnClickListener {
        private final RoutineOffer offer;
        private final int i;
        ToggleOfferTap(RoutineOffer o, int i) { offer = o; this.i = i; }
        @Override public void onClick(View v) {
            if (!offer.saveable(i)) return;
            offer.ticked[i] = !offer.ticked[i];
            offer.refresh();
        }
    }

    /**
     * Saving through the SAME paths the Trainer's own cards use - saveMint for a track (its
     * sheet's starting figures, the split, the signature, the working pressure, the decision
     * accepted), fileFeeder for the feeder - so a routine created here is indistinguishable
     * from one saved later. Each is evaluated afresh at the tap, its rest asked again (a
     * resting track saves nothing, whatever the sheet showed). And the first saved is
     * SELECTED, because a plan that has done all its work and left Today pointing at nothing
     * has not finished.
     */
    private final class SaveOfferedTap implements DialogInterface.OnClickListener {
        private final RoutineOffer offer;
        SaveOfferedTap(RoutineOffer o) { offer = o; }
        @Override public void onClick(DialogInterface d, int w) {
            long now = System.currentTimeMillis();
            String first = null;
            int saved = 0;
            for (int i = 0; i < offer.size(); i++) {
                if (!offer.ticked[i] || !offer.saveable(i)) continue;
                int track = offer.tracks.get(i).intValue();
                Model.TrainerTrackState st = track == Plan.TRACK_LENGTH
                    ? a.model.trainerLength : a.model.trainerGirth;
                SessionActivity.TrainerEval e = evalFor(track, st, now);
                if (e == null || e.decision == null
                        || !TrainerOnboard.offerable(track, e.decision.action, e.rx != null,
                                                     restingFor(track, st, now)))
                    continue;
                String id = track == Plan.TRACK_FEEDER ? fileFeeder(e, now)
                    : a.saveMint(track, e, RxBuild.adjustStartSets(a.model, e.rx),
                                 RxBuild.adjustStartKpa(a.model, e.rx), false);
                if (first == null) first = id;
                saved++;
            }
            if (first == null) { showTrainer(); return; }
            a.model.selected = first;
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, TrainerOnboard.savedLine(saved), TrainerOnboard.OPEN_TODAY,
                a.new GoDestTap(Nav.TODAY), Snack.HOLD_MS_ACTIONABLE);
            showTrainer();
        }
    }

    /**
     * THE RECALIBRATION THAT IS WAITING - shown in both of its states, because "we took your
     * request" and "it is ready now" are different facts and a person who asked deserves to
     * see which one is true.
     *
     * DURING the run it is a quiet line: it explains the wait and says the running session
     * still counts toward the plan it started on, which is the part somebody would otherwise
     * have to guess at. AFTER the run it becomes a button, because now the only thing left
     * is for them to press it.
     *
     * It carries its own way out. A person may change their mind while the run plays, and
     * the alternative to offering that here is a flag they can only clear by going through
     * the whole of onboarding.
     */
    private void pendingRecalibrateCard() {
        if (!a.model.pendingRecalibrate) return;
        if (a.running) {
            Ui.note(a, a.body, "Recalibration is waiting for this run to finish. The "
                + "session playing now still counts toward the plan you started it on.");
            Button cancel = Ui.secondary(a, a.body, "Cancel the recalibration");
            cancel.setOnClickListener(new CancelPendingRecalTap());
            return;
        }
        Ui.note(a, a.body, "The recalibration you asked for during your last run is ready.");
        Button go = Ui.big(a, a.body, "Recalibrate now", Ui.ACCENT);
        go.setOnClickListener(new StartOnboardTap(true));
        Button cancel = Ui.secondary(a, a.body, "Not now");
        cancel.setOnClickListener(new CancelPendingRecalTap());
    }

    private final class CancelPendingRecalTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.pendingRecalibrate = false;
            Store.save(a, a.model);
            a.toast("Recalibration cancelled");
            showTrainer();
        }
    }

    /* ===================== THE TRAINER, ARRANGED =====================================
     *
     * It was nine cards in one grey column: state line, suggestion, feeder, length, tracks,
     * week, last decision, settings. Every one the same size in the same colour, so nothing
     * said which of them you act on - and the answer to "what do I do today" sat in the
     * middle of the pile rather than at the top of it.
     *
     * THE SHAPE NOW: one thing to do, one picture of how it has been going, and two named
     * doors holding everything else.
     *
     * A closed fold still says something. The headline sits ON the shut row - "deload next
     * week" - so somebody who never opens it still learns the one fact from behind it worth
     * having, and neither door is blind.
     *
     * NOTHING WAS DELETED to achieve this. Every card that existed still exists; the folds
     * decide when it is on screen.
     */
    private void renderTrainerMain() {
        advanceWeekIndexes(System.currentTimeMillis());
        if (a.trainerView == VIEW_NEW_QUESTIONS)      { renderNewQuestions();       return; }
        if (a.trainerView == a.TRAINER_VIEW_TRACK)    { renderTrainerTrackDetail(); return; }
        if (a.trainerView == a.TRAINER_VIEW_WHERE)    { renderTrainerWhere();       return; }
        if (a.trainerView == a.TRAINER_VIEW_SHAPE)    { renderTrainerShape();       return; }
        if (a.trainerView == a.TRAINER_VIEW_WHEN)     { renderTrainerWhen();        return; }

        a.trainerPrimaryTaken = false;
        // (The arrival reset for trainerOpenTrack is in showTrainer, where currentScreen
        // still says where we came from - see the note there.)
        trainerHeader();
        pendingRecalibrateCard();
        // t10-K: the month-12 break - coming, running, or its gentle week.
        new BreakCards(a, this).cards();
        // THE SAME QUESTION, WHERE THE PENALTY IS APPLIED: applyMissPolicy charges the rested
        // week when this screen draws, so the answer belongs on this screen too.
        a.deloadAskCard(a.body, true);
        // t10 REAL-1: the due deload's question - its card, and the dialog once a morning.
        a.deloadDueCard(a.body, true);
        a.offerDeloadStart();
        // A WAY OUT THAT NEEDS NO ROUTINE (audit A7) - see ReadyToResumeTap. Above
        // everything, because a safety hold makes the rest of the screen irrelevant.
        if (a.model.trainerState == Model.TRAINER_STATE_SAFETY_FLAG) {
            Button ready = Ui.big(a, a.body, "I am ready to resume", Ui.ACCENT);
            ready.setContentDescription("I am ready to resume. Reports an all-good readiness "
                + "check and lifts the safety hold.");
            ready.setOnClickListener(a.new ReadyToResumeTap());
        }
        /* t10 R-61 - THE PLAN HAS NEW CHOICES. ONE DECISION CARD AT A TIME (polish TR-1): the
         * upgrade is the least urgent of the decision cards (DecisionQueue), so it is drawn
         * after them - as its card when nothing more urgent shows, else as one line under them.
         * It used to sit on top as a second lime primary over "Were you on a deload?". */
        upgradeCard();
        weeksBNotice();

        // THE WEEK LEADS. It is the unit the gate counts in and the one thing still
        // changeable today, so it gets the largest figure on the page.
        thisWeekCard();

        // ...AND HOW LONG A DAY OF IT IS, where a day reaches ninety minutes (DayLength).
        dayLengthCard();
        // ...or where "stop growing at 90 min" is holding a track there (t10 R-60).
        heldAt90Card();

        // HOW IT HAS BEEN GOING, in one row, because it answers "is this working".
        recentSessionsCard();

        // WHERE YOU ARE, per track.
        trackLanes();

        // ...AND WHY ONE OF THEM IS QUIET, where it is resting.
        lengthRestingCard();
        // ...or where a girth-focus block has paused the girth track (S14).
        girthPausedCard();

        // WHAT ADVANCEMENT NEEDS, as two figures over two thresholds.
        gateCard();

        /* THE SCHEDULE IS ON THE PAGE NOW, not behind a door. The "What is coming" fold held
         * exactly this table and nothing else, so it was a second door to one room - and a
         * fold whose contents are the page's main reference is a fold that hides the reason
         * somebody opened the tab. */
        trainerWeeksAhead(a.model.trainerGirthStyle);

        suggestionRows();

        if (anySessionFiled())
            trainerFold(a.FOLD_HISTORY, "How it has been going", Say.historySummary(TrainerTab.dailyNetMin(a.model.sessLog.all,
                PhotoCalendar.dayKey(System.currentTimeMillis()), 182)));
        if (anySessionFiled() && a.trainerFoldOpen == a.FOLD_HISTORY) {
            consistencyGridCard();
            trainerWeekCard(a.model.trainerGirthStyle, a.model.trainerGirth);
            Model.TrainerDecision last = null;
            for (int i = 0; i < a.model.trainerDecisions.size() && last == null; i++)
                if (a.model.trainerDecisions.get(i) != null) last = a.model.trainerDecisions.get(i);
            if (last != null) {
                Button lastRow = Ui.flat(a, a.body, "Last: " + Say.actionLabel(last.action)
                    + " \u00b7 " + Say.decisionStateLabel(last.state));
                lastRow.setOnClickListener(new DecisionDetailTap(last));
            }
        }

        trainerFold(a.FOLD_STEER, "Steer the plan", steerSummary());
        if (a.trainerFoldOpen == a.FOLD_STEER) steerCards();

        /* THREE ROOMS, NAMED BY THE QUESTION THEY ANSWER, and each door says what is true
         * inside it before it is opened.
         *
         * This was two abstract rows - "How routines are shaped" and "Plan settings" - and
         * the plan's schedule and ceiling were in another tab with nothing here pointing at
         * them. A door that only names a room has to be opened to be useful; one that
         * carries the room's current state answers most of the questions it was going to be
         * opened for. The facts wrap and are never cut (Ui#doorRow).
         *
         * Nothing behind them changed: every control that was reachable before still is. */
        Ui.doorRow(a, a.body, "Where I am", Say.whereIAmParts(a.model),
            new TrainerViewTap(a.TRAINER_VIEW_WHERE, 0));
        shapeDoorRow();
        Ui.doorRow(a, a.body, "When it runs", Say.whenItRunsParts(a.model),
            new TrainerViewTap(a.TRAINER_VIEW_WHEN, 0));
    }

    /* ------------------------------------------------- t10 R-61: the upgrade card */

    /** "Later" on the upgrade card, for this app start: the card stays away until the next
     *  start (a process, not a save - Model#planT10Laters counts the three that are allowed). */
    static boolean planT10LaterThisRun = false;

    /**
     * THE PLAN CHANGED - RUN THE TRAINER SETUP AGAIN (t10 R-61, A7). One card for a plan set
     * up before this version: the rules changed under it, the values are kept, and the setup
     * is where the new choices are (Long training days, length load, what follows a length
     * session). Nothing else is announced. "Later" puts it off to the next app start, three
     * times at most; after that it is a row in "Where I am".
     */
    private void upgradeCard() {
        DecisionQueue q = DecisionQueue.at(a.model, System.currentTimeMillis(),
                                           planT10LaterThisRun);
        if (!q.waiting(DecisionQueue.UPGRADE)) return;
        // A MORE URGENT CARD IS SHOWING: this one waits as a single row under it (TR-1).
        if (q.decisionShownAbove(DecisionQueue.UPGRADE)) {
            Ui.navCard(a, a.body, PlanCards.upgradeNav(a.model), new UpgradeAnswerTap());
            return;
        }
        LinearLayout g = Ui.cardGroup(a, a.body, PlanCards.UPGRADE_TITLE, null, Ui.ACCENT);
        String pos = q.position(DecisionQueue.UPGRADE);
        if (pos.length() > 0) Ui.microLabel(a, g, pos, Ui.DIM);
        // A note like every other card's, the three rows' names behind its \u24d8 (TR-2).
        Ui.noteInfo(a, g, PlanCards.upgradeShort(a.model), PlanCards.UPGRADE_TITLE,
            PlanCards.upgradeText(a.model));
        Ui.note(a, g, PlanCards.WEEKS_NOW_COUNT);   // week B (2026-10-03)
        Button answer = Ui.big(a, g, PlanCards.upgradeAnswer(a.model), Ui.ACCENT);
        answer.setOnClickListener(new UpgradeAnswerTap());
        Ui.secondary(a, g, PlanCards.UPGRADE_RUN).setOnClickListener(new UpgradeCardTap(true));
        Ui.secondary(a, g, PlanCards.UPGRADE_LATER).setOnClickListener(new UpgradeCardTap(false));
    }

    /* ------------------------------------- F4: the plan's three new questions, alone */

    /** The short path's view: not one of SessionActivity's TRAINER_VIEW_* (0..4), and reset to
     *  the main view by every arrival from another tab, as they are (showTrainer). */
    static final int VIEW_NEW_QUESTIONS = 90;

    /** The short path's answers while it is open - written only by its Confirm. */
    private int nqLongDays = -1, nqLenLoad = -1, nqR4 = -1;

    /**
     * F4 - THE THREE NEW QUESTIONS ON ONE SCREEN (polish). An upgrader answered them by walking
     * all six setup steps, about twelve taps, to reach three rows. Here they are together: Long
     * training days (both tracks on), and the two What it writes rows that apply - each the
     * same choice the Trainer page's own row makes, written by the same writes, on Confirm.
     * Nothing else is touched; the position and every other value are kept. The full setup
     * stays on the card as "Run the full setup".
     */
    private void renderNewQuestions() {
        a.header(a.body, PlanCards.NEW_QUESTIONS_TITLE, new TrainerBackTap());
        Ui.note(a, a.body, "Your values are kept.");
        boolean ld = LongDays.shown(a.model), len = PlanCards.lenLoadShown(a.model),
                r4 = PlanCards.r4Shown(a.model);
        if (ld) {
            LinearLayout c = Ui.cardGroup(a, a.body, LongDays.ROW, null);
            View.OnClickListener[] picks = new View.OnClickListener[LongDays.LABELS.length];
            for (int i = 0; i < picks.length; i++) picks[i] = new NewQuestionTap(0, i);
            Ui.choiceList(a, c, LongDays.LABELS, LongDays.effects(a.model.sched),
                LongDays.index(nqLongDays), picks);
            Ui.noteInfo(a, c, "How a week of both tracks is counted", LongDays.ROW,
                LONG_DAYS_WHY);
        }
        if (len || r4) {
            LinearLayout c = Ui.cardGroup(a, a.body, "What it writes", null);
            if (len)
                Ui.choiceRow(a, c, PlanCards.LEN_LOAD_ROW, PlanCards.LEN_LOAD_LABELS,
                    PlanCards.lenLoadEffects(), PlanCards.lenLoadIndex(nqLenLoad), null,
                    new NewQuestionChoice(1));
            if (r4)
                Ui.choiceRow(a, c, PlanCards.R4_ROW, PlanCards.R4_LABELS, PlanCards.R4_EFFECTS,
                    PlanCards.r4Index(nqR4), null, new NewQuestionChoice(2));
        }
        if (!ld && !len && !r4) Ui.note(a, a.body, PlanCards.NEW_QUESTIONS_NONE);
        Button ok = Ui.big(a, a.body, "Confirm", Ui.ACCENT);
        ok.setOnClickListener(new ConfirmNewQuestionsTap());
        Ui.secondary(a, a.body, PlanCards.UPGRADE_RUN).setOnClickListener(new UpgradeCardTap(true));
        a.staggerBodyIn();
    }

    /** Opens the short path, its answers seeded from the plan as it stands. */
    private final class UpgradeAnswerTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            nqLongDays = a.model.sched.longDays;
            nqLenLoad = a.model.lengthLoadMode;
            nqR4 = a.model.girthAfterLength;
            a.trainerView = VIEW_NEW_QUESTIONS;
            showTrainer();
        }
    }

    /** A pick on the short path: held, not written, until Confirm. */
    private void pickNewQuestion(int which, int index) {
        if (which == 0) nqLongDays = index;
        else if (which == 1) nqLenLoad = PlanCards.lenLoadValue(index);
        else nqR4 = Model.R4_WARM + index;
        showTrainer();
    }

    private final class NewQuestionTap implements View.OnClickListener {
        private final int which, index;
        NewQuestionTap(int w, int i) { which = w; index = i; }
        @Override public void onClick(View v) { pickNewQuestion(which, index); }
    }

    private final class NewQuestionChoice implements Ui.Choice {
        private final int which;
        NewQuestionChoice(int w) { which = w; }
        @Override public void choose(int i) { pickNewQuestion(which, i); }
    }

    /** Confirm: the answers through the Trainer rows' own writes, and the card answered. */
    private final class ConfirmNewQuestionsTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (LongDays.shown(a.model) && nqLongDays >= 0) LongDays.choose(a.model, nqLongDays);
            if (PlanCards.lenLoadShown(a.model) && nqLenLoad >= 0)
                a.model.lengthLoadMode = nqLenLoad;
            if (PlanCards.r4Shown(a.model) && nqR4 >= 0) a.model.girthAfterLength = nqR4;
            a.model.clampRxShape();
            a.model.answerPlanT10Setup();
            a.schedSaved();          // clamps, saves, and moves the reminders with the week
            a.syncPlanRoutines();    // the routines rewritten where the answers changed them
            a.trainerView = a.TRAINER_VIEW_MAIN;
            Ui.snack(a, a.rootFrame, "Your answers are saved \u2014 everything else is kept");
            showTrainer();
        }
    }

    /**
     * The upgrade card's two answers. RUN THE SETUP is the FULL setup - a recalibration has no
     * week step, and the week step is where Long training days is asked - seeded with the
     * plan as it stands, as a recalibration is (SessionActivity#startTrainerOnboard), so every
     * value is kept unless changed. Its Confirm answers the card (CommitOnboardTap).
     * LATER counts one of the three and hides the card until the next app start.
     */
    private final class UpgradeCardTap implements View.OnClickListener {
        private final boolean run;
        UpgradeCardTap(boolean r) { run = r; }
        @Override public void onClick(View v) {
            if (!run) {
                a.model.answerPlanT10Later();
                planT10LaterThisRun = true;
                Store.save(a, a.model);
                showTrainer();
                return;
            }
            // As StartOnboardTap refuses a first setup during a run: several screens of setup
            // over a run that is playing would bury it.
            if (a.running) { a.refuse("Setting the plan up"); return; }
            a.model.pendingRecalibrate = false;
            a.startTrainerOnboard(true);       // every answer from the plan as it stands
            a.trainerRecalibrating = false;    // ...and the whole setup, week step included
            /* "YOUR VALUES ARE KEPT" (review D, F1): the answers that place the person are
             * remembered as seeded, and Confirm keeps the position while they still are
             * (onboardKeepsPosition). A layoff is no part of the plan as it stands. */
            a.trainerOnboardLayoff = false;
            a.trainerOnboardUpgrade = true;
            a.trainerOnboardSeed = onboardPosition();
            showTrainer();
        }
    }

    /**
     * THE DELOAD CADENCE, IN WORDS, ONCE.
     *
     * Two screens printed their own version of this and both stopped being true when
     * Plan#DELOAD_BACK_TO_FOUR_MONTH arrived: they said "every three after the first", which
     * the engine runs only until month four and then stops. A rule with two descriptions is
     * a rule with two chances to go stale, and it took both.
     *
     * Every figure is read from the constants the decision itself uses, so the sentence
     * cannot drift from the behaviour again without the numbers moving with it.
     */
    static String deloadCadenceLine() {
        // Polish SU-14: plain words for "usage-linked" and "the dated table".
        return "Deload weeks: after your first " + Plan.DELOAD_FIRST_AFTER_TRAINING_WEEKS
            + " training weeks, then every " + Plan.DELOAD_AFTER_TRAINING_WEEKS
            + " until month " + Plan.DELOAD_BACK_TO_FOUR_MONTH + ", then every "
            + Plan.DELOAD_FIRST_AFTER_TRAINING_WEEKS + ". Weeks you don\u2019t train don\u2019t "
            + "count.";
    }

    /**
     * THE TITLE LINE, EARNING ITSELF. It said "Trainer" - the one line every screen gets for
     * free, spent on a word already printed in the tab bar an inch below it. It now carries
     * the position as well, so the level and the week are readable before anything is read.
     */
    private void trainerHeader() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(a);
        t.setText("Trainer");
        t.setTextColor(Ui.TEXT);
        t.setTextSize(Look.SP_TITLE);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String state = trainerStateChip();
        if (state.length() > 0) {
            TextView chip = new TextView(a);
            chip.setText(state);
            chip.setTextColor(trainerStateChipColour());
            chip.setTextSize(Look.SP_CAPTION);
            chip.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_PILL));
            chip.setPadding(Ui.dp(a, 8), Ui.dp(a, 3), Ui.dp(a, 8), Ui.dp(a, 3));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.rightMargin = Ui.dp(a, 7);
            row.addView(chip, cp);
        }

        TextView pos = new TextView(a);
        pos.setText(Say.trainerPosition(a.model.trainerGirth.level, a.model.trainerGirthStyle,
                Math.max(1, a.model.trainerGirth.weekIndex)));
        pos.setTextColor(Ui.DIM);
        pos.setTextSize(Look.SP_CAPTION);
        Ui.tabular(pos);
        row.addView(pos);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 6);
        lp.bottomMargin = Ui.dp(a, 8);
        a.body.addView(row, lp);
        Ui.group(row, "Trainer. " + A11y.collapse(Say.trainerPosition(a.model.trainerGirth.level, a.model.trainerGirthStyle,
                Math.max(1, a.model.trainerGirth.weekIndex)))
            + (trainerStateChip().length() > 0 ? ". " + trainerStateChip() : "") + ".");
    }

    /** "L1 &middot; wk 4 of 26", or just the level where there is no week table to count in. */
    /**
     * HAS THIS TRACK ANYTHING NEW TO SAY THIS WEEK?
     *
     * The test is the same one the card itself applies: a prescription already minted for
     * this signature is not news. A track with nothing new keeps its card - it just keeps it
     * behind the schedule door instead of at the top of the screen.
     */
    /**
     * THE PRESSURE THIS TRACK WOULD ACTUALLY BE PRESCRIBED RIGHT NOW.
     *
     * A track carries the pressure it is working toward; the prescription carries the one the
     * guide allows this month. In the first month of a length track those differ by a lot,
     * and any screen that prints one beside the other has to know which it is printing.
     * Falls back to the stored figure when there is no prescription to read - a hold week
     * still has a working pressure.
     */
    private int prescribedPressureKpa(int track, Model.TrainerTrackState state) {
        SessionActivity.TrainerEval e = a.evalTrack(track, state, System.currentTimeMillis());
        if (e != null && e.rx != null && e.rx.pressureKpa > 0) return e.rx.pressureKpa;
        return (int) Math.round(state.pressureKpa);
    }

    private boolean trackSpeaks(int track, Model.TrainerTrackState state) {
        SessionActivity.TrainerEval e = a.evalTrack(track, state, System.currentTimeMillis());
        if (e == null || e.decision == null) return false;
        Model.Routine saved = a.model.routine(state.lastMintId);
        boolean exists = saved != null;
        // An event, a step that waits (FIX11, EMU13: the brake's offer lives in this row), or
        // a prescription that is not the saved routine's.
        if (TrainerTab.rowShows(e.decision, Mint.alreadyMinted(state.lastMintSig, e.sig, exists)))
            return true;
        // ...and a saved routine the person has edited is an offer again - asked of the
        // builder (SavedMint#edited): is it what the plan built from its own signature?
        return a.mintEdited(track, state);
    }

    private boolean lengthSpeaks() {
        // A DATED REST IS THE ONE ANSWER THE PLAN HAS ALREADY BEEN GIVEN. Offering a
        // prescription during it would be the app asking again for a decision it was told.
        if (a.model.trainerLength.resting(System.currentTimeMillis())) return false;
        return a.model.trainerLengthOn && trackSpeaks(Plan.TRACK_LENGTH, a.model.trainerLength);
    }

    private boolean feederSpeaks() {
        if (!a.model.trainerFeederOptIn) return false;   // wave 3b (Q9)
        if (!Plan.feederEligible(a.model.trainerGirth.level)) return false;
        SessionActivity.TrainerEval e = a.evalFeeder(System.currentTimeMillis());
        if (e == null || e.decision == null) return false;
        if (e.decision.action != Plan.ACTION_FEEDER_SUGGEST) return false;
        boolean exists = a.model.routine(a.model.trainerFeederMintId) != null;
        return !Mint.alreadyMinted(a.model.trainerFeederMintSig, e.sig, exists);
    }

    /** The save button for a prescription card: loud for the first, quiet for the rest. */
    private Button trainerSaveButton(LinearLayout g, String label) {
        if (!a.trainerPrimaryTaken) {
            a.trainerPrimaryTaken = true;
            return Ui.big(a, g, label, Ui.ACCENT);
        }
        return Ui.secondary(a, g, label);
    }

    /**
     * THE PRESCRIPTION ROWS: one per track, each opening its own full card.
     *
     * A prescription is an offer, and an offer does not need a full-width button on a week
     * when it is offering what it offered last week. The row says which track, what it would
     * run, and whether that is new; the card behind it is unchanged - same save, same adjust,
     * same reasoning, same everything - and one tap away.
     *
     * ONE OPEN AT A TIME, the rule the folds and the Settings categories already follow.
     * A track with something NEW opens itself, because a changed prescription is the one
     * thing on this screen worth interrupting for.
     */
    /**
     * THE OFFERS - AND ONLY WHERE THERE IS SOMETHING TO OFFER.
     *
     * A prescription that has been SAVED has nothing left to say. It used to keep a row on
     * this screen for ever, reading "no change - keep running X", which is a sentence about
     * a decision that was made weeks ago sitting in the place reserved for decisions that
     * have not been. The routine is in the library; the library is where it lives.
     *
     * IT COMES BACK on any of three events, which are the three ways the offer becomes live
     * again: the plan prescribes something different, the saved routine is DELETED, or the
     * saved routine is EDITED until it no longer matches what was prescribed. The third is
     * the one nothing detected before - and it is the one that silently stalls a gate, since
     * a routine quietly running one set short delivers less net than the plan is scoring it
     * against.
     *
     * When no track has anything to offer the whole section is absent. A heading over
     * nothing is furniture.
     */
    private void suggestionRows() {
        // A girth rest (t10 R-23's length focus) is an answer already given, as a length rest is.
        boolean girth = !a.model.trainerGirth.resting(System.currentTimeMillis())
            && trackSpeaks(a.model.trainerGirthStyle, a.model.trainerGirth);
        boolean feeder = Plan.feederEligible(a.model.trainerGirth.level) && feederSpeaks();
        boolean length = lengthSpeaks();
        if (!girth && !feeder && !length) return;
        /* A REAL HEADING OVER THE ONLY ACTION ON THE PAGE. It was set in the smallest,
         * faintest type the app has, over the one section that asks anything of anybody. */
        // The section heading every other section has (polish TR-14 / SYS-11), at the card
        // grid's own inset - it was a bare heading at another.
        Ui.categoryHeader(a, a.body, "\u2261", "This week\u2019s routines", Ui.DIM);
        /* ONLY THE FIRST OPENS ITSELF. Every row here has something new by definition now,
         * so "open the new one" would open all of them on a fresh plan - which is the stack
         * of cards this whole arrangement exists to prevent. The first is the one a person
         * reads first; the rest are one tap each. */
        boolean first = true;
        if (girth)  { suggestionRow(a.model.trainerGirthStyle, a.model.trainerGirth, first); first = false; }
        if (feeder) { suggestionRow(Plan.TRACK_FEEDER, null, first); first = false; }
        if (length) { suggestionRow(Plan.TRACK_LENGTH, a.model.trainerLength, first); }
    }

    private void suggestionRow(int track, Model.TrainerTrackState state, boolean autoOpen) {
        boolean open = a.trainerOpenTrack == track || (a.trainerOpenTrack == 0 && autoOpen);
        boolean fresh = true;   // every row drawn here has something to offer
        /* AN OFFER IS NOT A DOOR. The prescription rows and the fold rows were the same
         * object - label left, grey figure right, chevron - so the eye could not tell
         * something being offered from somewhere to go. The offers get a card; the plain
         * rows stay plain, and now mean one thing. */
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 11), Ui.dp(a, 9), Ui.dp(a, 11), Ui.dp(a, 9));
        /* THE OPENED ROUTINE IS DRAWN INSIDE ITS OWN CARD (polish TR-14): it was a separate
         * card under the row, at another inset. The row's head is the tap target, so a tap in
         * the opened part never shuts it. */
        LinearLayout head = Ui.col(a);
        head.setMinimumHeight(Ui.dp(a, 48));
        card.addView(head);

        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView nm = new TextView(a);
        nm.setText(TrainerTab.trackLabel(track));
        nm.setTextColor(Ui.TEXT);
        nm.setTextSize(Look.SP_BODY);
        nm.setTypeface(null, android.graphics.Typeface.BOLD);
        line.addView(nm, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // ONE CHEVRON (polish SYS-5): the icon, turned down while shut and up while open, in
        // place of the "\u25be" / "\u25b4" text glyphs.
        android.widget.ImageView chev = Ui.iconView(a, R.drawable.ic_chevron_right, Ui.DIM, 20);
        chev.setRotation(open ? 270f : 90f);
        line.addView(chev);
        head.addView(line);

        // The tag ("New", "Edited") in lime, the shape in DIM (polish TR-15).
        TextView val = new TextView(a);
        val.setText(tagged(suggestionSummary(track, state, fresh)));
        val.setTextColor(Ui.DIM);
        val.setTextSize(Look.SP_CAPTION);
        Ui.tabular(val);
        val.setPadding(0, Ui.dp(a, 2), 0, 0);
        head.addView(val);

        // THE RESOLVED FLAG, not the field: with trainerOpenTrack still at its 0 sentinel
        // the row the plan auto-opened compared unequal and the first tap "opened" what was
        // already open, so it took two taps to shut.
        head.setOnClickListener(new TrainerTrackOpenTap(track, open));
        head.setOnTouchListener(new Ui.Press());
        head.setContentDescription(TrainerTab.trackLabel(track) + ". "
            + suggestionSummary(track, state, fresh) + ". "
            + (open ? "Open." : "Closed.") + " Tap to " + (open ? "close." : "open."));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.dp(a, 6);
        a.body.addView(card, clp);
        if (!open) return;
        openRowHost = card;
        try {
            if (track == Plan.TRACK_FEEDER) trainerFeederCard();
            else trainerSuggestionCard(track, state);
        } finally {
            openRowHost = null;
        }
    }

    /** The routine row the opened card draws into (TR-14); null draws a card of its own. */
    private LinearLayout openRowHost;

    /** The opened routine's part: a rule and its title inside the row's card - or, with no
     *  row to draw into, a card as before. */
    private LinearLayout openRowSection(String title, String sub) {
        LinearLayout host = openRowHost;
        if (host == null) return Ui.cardGroup(a, a.body, title, sub);
        Ui.divider(a, host);
        cardHeading(host, title);
        if (sub != null && sub.length() > 0) Ui.note(a, host, sub);
        return host;
    }

    /** What a shut row says. The figures, so a person can decide whether to open it at all -
     *  and the word NEW where there is something new, which is the only reason to. */
    private String suggestionSummary(int track, Model.TrainerTrackState state, boolean fresh) {
        long now = System.currentTimeMillis();
        SessionActivity.TrainerEval e = track == Plan.TRACK_FEEDER ? a.evalFeeder(now) : a.evalTrack(track, state, now);
        if (e == null || e.rx == null) return fresh ? "something new" : "no change";
        // S11: in the deload week the length row offers nothing to run - it says the week.
        if (track == Plan.TRACK_LENGTH && Deload.lengthRests(a.model, now))
            return "Deload week \u00b7 rest, no traction";
        /* FIX11 (EMU13) - A STEP THAT WAITS SAYS SO ON THE SHUT ROW: the offer, or the date. */
        GainBrake.Card brake = state == null ? null
            : GainBrake.card(a.model, track, state, e.decision, now);
        if (brake != null)
            return brake.offer ? TAG_NEW + TAG_SEP + GainBrake.ROW_OFFER : GainBrake.rowWait(brake);
        String what = tractionSummary(e.rx);
        if (what == null) {
            /* D4b - the figure the routine runs at, the card's own (RxBuild#commandedKpa). And
             * the holds it runs (0.10): a hybrid's five-minute holds (RxBuild#runsAs), and an
             * "Adjust first..." save's own sets and pressure - no bias on a pressure the person
             * set - while today's prescription is still the one it adjusted (SavedMint#carried). */
            String stored = state == null ? "" : state.lastMintSig;
            Mint.Adjust kept = SavedMint.carried(stored, e.sig);
            Mint.Rx built = SavedMint.carriedRx(e.rx, e.sig, stored);
            Mint.Rx runs = RxBuild.runsAs(a.model, built, kept != null);
            /* THE COUNT IS THE ROUTINE'S NAME'S (the owner's ruling, 0.10): the holds it runs,
             * make-up cycles included - asked of the builder (RxBuild#holdsRun) - so a Gentle
             * row cannot say 5x2min over a routine named and run as 7x2min. */
            int holds = RxBuild.holdsRun(a.model, built,
                RxBuild.Day.today(a.model).adjusted(kept));
            // One shape, written one way (polish SYS-13): "10 × 2 min at −8.9 inHg".
            what = Model.Fmt.shape(holds > 0 ? holds : runs.sets,
                (int) Math.round(runs.holdMin() * 60.0),
                RxBuild.commandedKpa(a.model, runs, kept != null && kept.ownKpa));
        }
        /* WHY IT IS ASKING AGAIN. "New" is right for a fresh prescription and wrong for one
         * you saved and then edited - that is not the plan changing its mind, it is the plan
         * noticing your routine no longer runs what it asked for, and the two deserve
         * different words. */
        if (state != null && a.mintEdited(track, state))
            return TAG_EDITED + TAG_SEP + what;
        return (fresh ? TAG_NEW + TAG_SEP : "") + what;
    }

    /** A routine row's tags, in sentence case (polish TR-15). */
    static final String TAG_NEW = "New", TAG_EDITED = "Edited", TAG_SEP = " \u00b7 ";

    /** The summary with its leading tag in lime - the rest keeps the view's own colour. */
    private static CharSequence tagged(String summary) {
        String tag = summary.startsWith(TAG_NEW + TAG_SEP) ? TAG_NEW
                   : summary.startsWith(TAG_EDITED + TAG_SEP) ? TAG_EDITED : null;
        if (tag == null) return summary;
        android.text.SpannableString s = new android.text.SpannableString(summary);
        s.setSpan(new android.text.style.ForegroundColorSpan(Ui.ACCENT), 0, tag.length(),
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private final class TrainerTrackOpenTap implements View.OnClickListener {
        private final int track;
        private final boolean wasOpen;
        TrainerTrackOpenTap(int t, boolean open) { track = t; wasOpen = open; }
        @Override public void onClick(View v) {
            a.trainerOpenTrack = wasOpen ? -1 : track;
            showTrainer();
        }
    }

    /** Has anything ever been filed? The test behind every empty state on this screen. */
    private boolean anySessionFiled() {
        return a.model.sessLog != null && !a.model.sessLog.all.isEmpty();
    }


    /**
     * THE WEEK, AS THE PLAN COUNTS IT.
     *
     * The page counted training days for a fold headline and never showed the week itself -
     * which is the one figure the gate is measured in AND the only one still changeable
     * today. Seven cells, the count in the largest type on the screen, and a chip saying what
     * it is for.
     *
     * A DELOAD WEEK IS NOT A SHORTFALL. During one the count still shows, because doing less
     * is the instruction - but it says so instead of implying you are two days behind.
     */
    private void thisWeekCard() {
        // Week B (2026-10-03): the plan's own week (TrainingWeek) - 2 full sessions a track, or
        // 3 shorter days - not a bare count of days.
        TrainingWeek.PlanWeek pw = TrainerTab.planWeekNow(a.model, System.currentTimeMillis());
        boolean deload = a.deloadNow();
        // The deload branch was violet - see trainerStateChipColour: nothing on this card
        // is measured, so it cannot wear the body-measurement colour.
        LinearLayout g = Ui.cardGroup(a, a.body, "This week", null,
            deload ? Ui.TEXT : Ui.ACCENT);

        TextView big = new TextView(a);
        big.setText(Say.planWeekShort(pw));
        big.setTextColor(deload ? Ui.TEXT : pw.qualifies ? Ui.ACCENT : Ui.TEXT);
        big.setTextSize(Look.SP_TITLE);
        Ui.tabular(big);   // read-only: the sans face with tabular digits (Look's type scale)
        g.addView(big);

        a.weekDayStrip(g, 30);

        if (deload)
            Ui.note(a, g, "A deload week. It does not need to count, and missing sessions "
                + "costs nothing.");
        else if (pw.qualifies) Ui.note(a, g, "This week counts toward your level.");
        /* THE TILES' KEY, AND THE RULE BEHIND A \u24d8 (polish TR-9): the letters on the tiles
         * were explained only in Settings; how a week counts is a rule, not the state. */
        Ui.noteInfo(a, g, a.model.trainerGirthOn && a.model.trainerLengthOn
                ? "G girth \u00b7 L length" : "How a week counts", "This week",
            Say.WEEK_RULE + " A full session delivered what its routine asked. Two runs in one "
            + "day are one session. " + Say.WEEK_WHEN);
        // SHOWN, not only said (device check EMU11): the per-track line - full sessions, days,
        // and while two full ones wait, why - under the figure.
        if (!deload && Say.planWeekLineShown(pw)) Ui.note(a, g, Say.planWeekLine(pw));
        Ui.group(g, Say.planWeekLine(pw));
    }

    /**
     * WHAT ADVANCEMENT NEEDS: each condition as "value / target" with a thin bar under it
     * (Ui#progressRow), and the sentence that makes them a gate rather than a set of bars.
     *
     * The bars are there so how far along each condition is reads at a glance, without
     * doing the division. The sentence stays because all of them must be true in the SAME
     * week: every progress bar in every app gets this wrong by drawing one number, and
     * somebody who hits twenty minutes at a shallow pressure would reasonably expect to
     * have arrived.
     */
    private void gateCard() {
        Model.TrainerTrackState st = a.model.trainerGirth;
        if (st.level >= Plan.L4) return;
        if (!anySessionFiled()) return;
        // POLISH #8: "Gate to", because a card titled "Level 2" under a header saying L1
        // read as a contradiction about where you are.
        // Polish TR-12: "Next: Level 4" says where it leads in the words the header uses.
        LinearLayout g = Ui.cardGroup(a, a.body,
            "Next: Level " + (st.level + 1), null, Ui.DIM);

        /* EVERY LEVEL'S OWN GATE, NOT LEVEL 1'S PRINTED THREE TIMES.
         *
         * This card drew the net milestone and the 8 hg working pressure whatever level the
         * track was on - but those two are the L1 gate alone. L2 and L3 are month gates
         * (Plan#gateL2toL3, Plan#gateL3toL4: month 6 and month 12; since 0.10 with the
         * level's exit volume too, Plan#levelVolumeHeld), so somebody at Level 2
         * was shown two thresholds they had usually already passed, told they had not
         * crossed, and given no sight of the one thing that actually decides it. */
        if (st.level == Plan.L1) {
            if (a.model.trainerGirthStyle == Plan.TRACK_GIRTH_TRADITIONAL) {
                // Traditional's gate asks each session for ITS OWN planned minutes
                // (Plan#gateL1toL2Traditional), never the interval track's 20.
                int held = TrainerTab.ownTargetsHeldOfLast(a.model,
                                                           Plan.TRACK_GIRTH_TRADITIONAL);
                int need = TrainerTab.NET_CONSISTENCY_N;
                gateFirst(Ui.progressRow(a, g, "Sessions at their planned time",
                    String.valueOf(held), String.valueOf(need),
                    Look.barFill(held, need), held >= need));
            } else {
                double netNow = typicalGateNetMin(a.model.trainerGirthStyle);
                double netNeed = Plan.netMilestoneMin(st.level);
                gateFirst(Ui.progressRow(a, g, TIME_AT_PRESSURE_ROW,
                    Say.fmtMin(netNow), Say.fmtMin(netNeed) + " min",
                    Look.barFill(netNow, netNeed), netNow >= netNeed));
            }
            int kpaNow = (int) Math.round(st.pressureKpa);
            int kpaNeed = (int) Math.round(Plan.L1_GATE_PRESSURE_HG * Plan.HG);
            // The figure bare, the unit once after the target: "−5.0 / −8.0 inHg". The bar
            // fills by magnitude (Look#barFill), so a deeper negative is further along.
            Ui.progressRow(a, g, "Working pressure",
                Say.valueWordOf(Model.Fmt.p(kpaNow)), Model.Fmt.p(kpaNeed),
                Look.barFill(kpaNow, kpaNeed), kpaNow >= kpaNeed);
            int wkNow = TrainerTab.gateHeldTrainingWeeks(a.model, a.model.trainerGirthStyle,
                                                         System.currentTimeMillis());
            int wkNeed = Plan.L1_GATE_HOLD_TRAINING_WEEKS;
            Ui.progressRow(a, g, "Weeks holding both",
                String.valueOf(wkNow), String.valueOf(wkNeed),
                Look.barFill(wkNow, wkNeed), wkNow >= wkNeed);
            Ui.note(a, g, "All three, in the same week. Reaching one without the others "
                + "does not cross the gate.");
            return;
        }

        int monthNow = TrainerTab.monthIndexNow(a.model, System.currentTimeMillis());
        int monthNeed = st.level == Plan.L2 ? Plan.L2_GATE_MONTH : Plan.L3_GATE_MONTH;
        gateFirst(Ui.progressRow(a, g, "Month",
            String.valueOf(monthNow), String.valueOf(monthNeed),
            Look.barFill(monthNow, monthNeed), monthNow >= monthNeed));
        /* AND THE VOLUME (the owner's decision, 2026-09-27; Plan#levelVolumeHeld): each of
         * the last 3 scored sessions held the minutes the level is left with - traditional
         * girth its own planned minutes. The row shows the least of those three, which is
         * the figure the gate reads. */
        if (a.model.trainerGirthStyle == Plan.TRACK_GIRTH_TRADITIONAL) {
            int held = TrainerTab.ownTargetsHeldOfLast(a.model, Plan.TRACK_GIRTH_TRADITIONAL);
            int need = TrainerTab.NET_CONSISTENCY_N;
            Ui.progressRow(a, g, "Sessions at their planned time",
                String.valueOf(held), String.valueOf(need),
                Look.barFill(held, need), held >= need);
        } else {
            TrainerTab.NetPairs np = TrainerTab.recentTrackedNets(a.model,
                Plan.TRACK_GIRTH_INTERVAL, TrainerTab.NET_CONSISTENCY_N);
            double netNow = TrainerTab.netSignals(np.nets, np.targets, 0.0).milestoneNet;
            double netNeed = Plan.levelExitNetMin(st.level);
            Ui.progressRow(a, g, "Least of your last 3",
                Say.fmtMin(netNow), Say.fmtMin(netNeed) + " min",
                Look.barFill(netNow, netNeed), netNow + 1e-9 >= netNeed);
        }
        if (st.level == Plan.L2)
            Ui.noteInfo(a, g, "Needs both: month " + monthNeed + ", and sessions that hold the "
                + "volume.", "Next: Level " + (st.level + 1),
                "Both: the month, and sessions that hold the volume. What you have built "
                + "carries into the next level.");
        else
            Ui.noteInfo(a, g, "Needs both: month " + monthNeed + ", and sessions that hold the "
                + "volume.", "Next: Level " + (st.level + 1),
                "Needs both: month " + monthNeed + ", and sessions that hold the volume. At "
                + "month " + monthNeed + " you choose: move up, or take a break and come back a "
                + "level lower.");
    }

    /** The first condition's rule sits a step below the card title, not against it. */
    private void gateFirst(View row) {
        if (row != null && row.getLayoutParams() instanceof LinearLayout.LayoutParams)
            ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = Ui.dp(a, Look.S3);
    }

    /** What the steering door says while shut. */
    private String steerSummary() {
        // WHAT IS ACTUALLY BEHIND THE FOLD. It said "pause, deload" - and the pause moved to
        // Where I am (renderTrainerWhere), so a closed fold was naming a button it no longer
        // holds. What it holds: the deload, and the report that steps the plan back.
        if (a.deloadNow()) return "in a deload";
        long dlStart = Deload.startMs(a.model);
        if (dlStart > System.currentTimeMillis())
            return "deload from " + Say.dayLabel(dlStart);   // t10 REAL-1: answered, to come
        return "deload, something\u2019s off";
    }

    /**
     * THE CONTROLS THAT KEEP A PLAN LIVEABLE, gathered behind one door.
     *
     * They existed, scattered: pausing was at the bottom of plan settings, a deload could
     * only be started from a suggestion card that appears when the plan decides, and undo
     * existed nowhere. A plan you cannot steer is one people abandon rather than adjust.
     */
    private void steerCards() {
        LinearLayout g = Ui.cardGroup(a, a.body, "Steer the plan", null, Ui.DIM);
        long dlStart = Deload.startMs(a.model);
        if (!a.deloadNow() && dlStart > System.currentTimeMillis())
            // t10 REAL-1: answered for a later day - said, and "now" still brings it forward.
            Ui.note(a, g, "Your deload week starts " + Say.dayLabel(dlStart) + ".");
        if (!a.deloadNow()) {
            Button dl = Ui.secondary(a, g, "Take a deload week now");
            dl.setContentDescription("Start a deload week now. The plan\u2019s week count pauses "
                + "and the week does not need to count.");
            dl.setOnClickListener(a.new StartDeloadTap(a.model.trainerGirthStyle));
        } else {
            Ui.note(a, g, "You are in a deload week. It ends on its own.");
        }
        // Doors carry the chevron icon, not a "\u203a" in their label (polish SYS-5).
        LinearLayout rep = Ui.navCard(a, g, "I already took a deload", new ReportDeloadTap());
        rep.setContentDescription("I already took a deload. Pick the days you rested; they "
            + "count as planned rest rather than missed training.");
        Model.PlanNotice n = a.model.planNotice;
        if (n != null && !n.ran && n.prevRoutine.length() > 0) {
            Button undo = Ui.flat(a, g, "Undo: " + n.headline);
            undo.setOnClickListener(new UndoNoticeFromTrainerTap());
        }
        /* B2 - THE PERMANENT DOOR TO THE READINESS REPORT.
         *
         * The four override toggles - turtling, an EQ drop, soreness, numbness - apply a
         * plan-wide step-back the moment one is tapped, and until now the ONLY way to reach
         * them was to tap START on a routine and answer "Something's off" to the pre-run ask.
         * Two things were wrong with that. Reporting numbness required BEGINNING a session you
         * had no intention of running; and once the pre-run ask stopped appearing on ordinary
         * days (SessionActivity#readinessWouldMatter), the ordinary day is precisely when a
         * problem first shows up and there would have been nowhere to say so.
         *
         * HERE, beside the deload, because it is the same kind of act: telling the plan to
         * back off. (The pause that used to sit here too now lives only under Where I am -
         * see renderTrainerWhere.) Steering it is what this card is for, and a report is the one
         * form of steering the app takes on your word alone.
         */
        LinearLayout off = Ui.navCard(a, g, "Something\u2019s off", new OpenReadinessReportTap());
        off.setContentDescription("Something's off. Report turtling, an erection-quality "
            + "drop, soreness or numbness — anything reported steps the plan back straight "
            + "away and never blocks a session.");
        Ui.noteInfo(a, g,
            a.model.trainerState == Model.TRAINER_STATE_SAFETY_FLAG
                ? "A report is standing — the plan is held back until you say all good before "
                  + "a session."
                : "Turtling, an EQ drop, soreness or numbness. Reporting steps the plan back "
                  + "right away.",
            "Reporting how you feel",
            "Turtling, an erection-quality drop lasting more than six hours, soreness or "
            + "numbness. Tapping any of them steps the plan back immediately — it does not "
            + "wait for your next session, and it never blocks you from running one.\n\nIt "
            + "clears from the other side. Once something is reported, the readiness check "
            + "appears before your next session and answering \u2018all good\u2019 there is what "
            + "lifts it. Numbness additionally needs a week off before it will clear, per the "
            + "guidance.\n\nThis used to be reachable only by tapping START and answering the "
            + "pre-run check, so saying you were sore meant beginning a session you did not "
            + "intend to run.");
    }

    private final class UndoNoticeFromTrainerTap implements View.OnClickListener {
        @Override public void onClick(View v) { a.new UndoPlanNoticeTap().onClick(null, 0); }
    }

    private final class ReportDeloadTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            long now = System.currentTimeMillis();
            long to = Deload.dayStartPlus(now, -1);              // yesterday
            a.openDeloadSheet(Deload.dayStartPlus(to, -6), to);  // a week, always valid
        }
    }

    /** A fold, drawn as the doors under it are (polish TR-16): the title, its headline
     *  under it, and the chevron icon - turned down while shut and up while open - in place
     *  of a grey label, a value and a tiny "\u25be". */
    private void trainerFold(int which, String title, String headline) {
        boolean open = a.trainerFoldOpen == which;
        LinearLayout row = Ui.doorRow(a, a.body, title,
            headline == null || headline.length() == 0 ? new String[0] : new String[]{ headline },
            new TrainerFoldTap(which));
        View ch = row.getChildAt(row.getChildCount() - 1);
        if (ch != null) ch.setRotation(open ? 270f : 90f);
        row.setContentDescription(title + ". " + (headline == null ? "" : headline + ". ")
            + (open ? "Open." : "Closed.") + " Tap to " + (open ? "close." : "open."));
    }

    private final class TrainerFoldTap implements View.OnClickListener {
        private final int which;
        TrainerFoldTap(int w) { which = w; }
        @Override public void onClick(View v) {
            a.trainerFoldOpen = a.trainerFoldOpen == which ? a.FOLD_NONE : which;
            showTrainer();
        }
    }

    /** What the history door says while shut. */
    /**
     * What the history door says while shut - and it has to be a FACT, not a label. "Last 8
     * sessions" describes the contents; "38 days trained" is a reason to open it, and is
     * worth having even if you never do.
     */
    /**
     * 02 - THE READINESS LINE: how long you have rested, and whether anything is holding you
     * back, in one sentence above the order.
     *
     * Silent about soreness when none was reported. "No soreness" every day for a year
     * teaches a person to stop reading the line that will one day say something else.
     */
    /** How long since the last session, as a phrase short enough for a card subtitle -
     *  empty when there has never been one, because "no sessions yet" beside a prescription
     *  is a fact about the log, not about how rested you are. */
    /**
     * 03 - THE LAST EIGHT SESSIONS delivered net, as bars against this level net target.
     *
     * A session with no recorded net is drawn as a GAP, not as a zero: sessions filed before
     * net was recorded delivered an unknown amount, and a zero bar would accuse somebody of a
     * session they may well have finished.
     */
    private void recentSessionsCard() {
        double[] mins = TrainerTab.recentNetMin(a.model.sessLog.all, 8);
        boolean any = false;
        for (int i = 0; i < mins.length; i++) if (mins[i] >= 0) any = true;
        if (!any) return;
        double target = Math.max(1.0, Plan.netMilestoneMin(a.model.trainerGirth.level));
        // The plain card title, as every other card has it (polish TR-10).
        LinearLayout g = Ui.cardGroup(a, a.body, "Recent sessions", null);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        int full = 0, counted = 0;
        for (int i = 0; i < mins.length; i++) {
            View bar = new View(a);
            double frac = mins[i] < 0 ? 0 : Math.min(1.2, mins[i] / target);
            int h = Ui.dp(a, (int) Math.max(3, Math.round(4 + frac * 30)));
            /* THE BAR AGREES WITH THE LINE ABOVE IT, AND WITH THE CAPTION.
             *
             * Two defects in one expression. It coloured on frac >= 0.9 while the rule is
             * drawn at the target itself, so a bar below the line could be painted as though
             * it had reached it - and the caption counted the same 90 %. And the two tints
             * were CMD_DIM and ACCENT_DIM: amber is reserved for a pump that may be under
             * pressure, which a filed session is not, and both are translucent enough to
             * composite under 1.5:1 on this card's own ground.
             *
             * Graduated lime instead - the palette this app already uses for "how much was
             * delivered" - composited over the surface so it is opaque where it is drawn. */
            int col = mins[i] < 0 ? Ui.SURFHI
                    : i == mins.length - 1 ? Ui.ACCENT
                    : mins[i] >= target ? Look.over(Look.DOSE_TIER_MED, Look.SURFACE)
                    : Look.over(Look.DOSE_TIER_LOW, Look.SURFACE);
            bar.setBackground(Ui.roundRect(a, col, Look.R_CTRL));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
            lp.rightMargin = Ui.dp(a, 3);
            row.addView(bar, lp);
            if (mins[i] >= 0) { counted++; if (mins[i] >= target) full++; }
        }
        /* THE TARGET, DRAWN. The caption says six of eight reached it; without a line the
         * picture cannot show which six, so the reader has to take the sentence on trust
         * beside a chart that could have told them. A bar is built as 4dp plus its fraction
         * of 30dp, so the target sits at exactly 34dp - the same arithmetic, not a guess. */
        FrameLayout barBox = new FrameLayout(a);
        barBox.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View rule = new View(a);
        rule.setBackgroundColor(Ui.DIM);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 1));
        tlp.gravity = Gravity.BOTTOM;
        tlp.bottomMargin = Ui.dp(a, 34);
        barBox.addView(rule, tlp);
        /* THE LINE CARRIES ITS OWN NUMBER. A rule with no figure beside it is a claim the
         * reader has to take from the caption; with the minutes on it, "6 reached the line"
         * can be checked against the picture. */
        TextView tick = new TextView(a);
        tick.setText(Say.gateNum(Math.round(target * 10.0) / 10.0) + " min");
        tick.setTextColor(Ui.DIM);
        tick.setTextSize(Look.SP_MICRO);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        klp.bottomMargin = Ui.dp(a, 35);
        barBox.addView(tick, klp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 36));
        rlp.bottomMargin = Ui.dp(a, 5);
        g.addView(barBox, rlp);
        Ui.group(row, "The last " + counted + " sessions. " + full + " met the net target.");
        // Polish TR-10: the figure in plain words; how to read the bars behind the \u24d8.
        Ui.noteInfo(a, g, full + " of your last " + counted + " sessions reached "
            + Say.gateNum(Math.round(target * 10.0) / 10.0) + " min at pressure.",
            "Recent sessions", "Taller bars mean more time at pressure.");
    }

    /**
     * 06 - SIX MONTHS OF DAYS, one cell each, darker for more delivered.
     *
     * DAYS, NOT SESSIONS: two sessions in a day add into one cell, because the gate counts
     * days, and a grid that drew them separately would picture a rule the app does not use.
     */
    private void consistencyGridCard() {
        final int PER_ROW = 26, ROWS = 7, DAYS = PER_ROW * ROWS;
        double[] mins = TrainerTab.dailyNetMin(a.model.sessLog.all,
            PhotoCalendar.dayKey(System.currentTimeMillis()), DAYS);
        int trained = 0;
        for (int i = 0; i < mins.length; i++) if (mins[i] > 0) trained++;
        if (trained == 0) return;
        LinearLayout g = Ui.cardGroup(a, a.body, "Six months of days", null, Ui.DIM);
        int[] shade = { Ui.SURFHI, 0xFF2B3A16, 0xFF456219, 0xFF6F9C22, Ui.ACCENT };
        for (int r = 0; r < ROWS; r++) {
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < PER_ROW; c++) {
                View cell = new View(a);
                cell.setBackground(Ui.roundRect(a,
                    shade[TrainerTab.heatStep(mins[r * PER_ROW + c])], Look.R_CTRL));
                LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, Ui.dp(a, 8), 1f);
                lp.rightMargin = Ui.dp(a, 2);
                lp.bottomMargin = Ui.dp(a, 2);
                line.addView(cell, lp);
            }
            g.addView(line);
        }
        Ui.noteInfo(a, g,
            trained + " days trained in the last six months.",
            "How the grid counts days",
            trained + " days trained in the last six months. Darker is more time "
            + "under pressure. Two sessions in one day count as one day, which is the rule the "
            + "plan itself uses.");
    }

    /**
     * 20 - THE TWO TRACKS AS LANES. Girth carries a week count and a level; length carries a
     * pressure and nothing else, because it HAS nothing else - it is static by design.
     *
     * They used to get one identical row each, which said the same thing about two tracks
     * that mean opposite things by it.
     */
    private void trackLanes() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        /* BOTH LANES ARE THE SAME HEIGHT, whatever their captions say. With WRAP_CONTENT the
         * length lane grew to four lines while the girth lane stayed at two, and two cards
         * side by side at different heights read as one card and one afterthought - which is
         * the opposite of what a pair of lanes is for. MATCH_PARENT inside a horizontal row
         * takes the height of the taller. */
        LinearLayout girth = trackLane(a.model.trainerGirthStyle, a.model.trainerGirth);
        LinearLayout.LayoutParams gp =
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (a.model.trainerLengthOn) gp.rightMargin = Ui.dp(a, 6);
        row.addView(girth, gp);
        if (a.model.trainerLengthOn)
            row.addView(trackLane(Plan.TRACK_LENGTH, a.model.trainerLength),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 6);
        lp.bottomMargin = Ui.dp(a, 4);
        a.body.addView(row, lp);
    }

    /**
     * THE NEXT CHANGE IN WHAT THE GIRTH ROUTINE RUNS, as a caption: "7×2min at wk 6",
     * "deload wk 5", "10×2min now" (TrainerTab#nextChangeShort, read from the same rows as
     * "The next weeks"). It read the master plan by the level's own week number, so at
     * Level 3 it said Level 1's "6 sets at wk 3" over a routine of 10×2min. Traditional
     * girth has no week rows, and a lane must not imply a schedule that does not exist.
     */
    private String nextChangeShort() {
        if (a.model.trainerGirthStyle != Plan.TRACK_GIRTH_INTERVAL) return "climbing";
        return TrainerTab.nextChangeShort(a.model, System.currentTimeMillis());
    }

    private LinearLayout trackLane(int track, Model.TrainerTrackState state) {
        boolean length = track == Plan.TRACK_LENGTH;
        LinearLayout col = Ui.col(a);
        col.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, col, Ui.ELEV_CARD);
        col.setPadding(Ui.dp(a, 11), Ui.dp(a, 9), Ui.dp(a, 11), Ui.dp(a, 10));
        col.setMinimumHeight(Ui.dp(a, 48));

        TextView name = new TextView(a);
        name.setText(TrainerTab.trackLabel(track));
        name.setTextColor(Ui.TEXT);
        name.setTextSize(Look.SP_BODY);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(name);

        /* THE LANE SHOWS WHAT THE PLAN WILL ACTUALLY PRESCRIBE, which early on is not the
         * same as the pressure stored against the track.
         *
         * The screenshots caught this: the lane read 9.4 inHg while the length card beside it
         * prescribed 5.9. Both figures were right - the stored one is where the track is
         * headed, the prescribed one is held under the guide's first-month cap - and the
         * screen stated them a centimetre apart with no hint that they were different
         * questions. A lane that disagrees with the card next to it is worse than either
         * number alone. */
        /* THE PRESSURE, NOT THE WEEK. The week number is in the title line an inch above;
         * printing it again here spends the lane's largest type on something already read.
         * The pressure is what this track is actually doing. */
        TextView big = new TextView(a);
        big.setText(Model.Fmt.p(prescribedPressureKpa(track, state)));
        /* NOT VIOLET. Look reserves it for the body-measurement domain - the tape measure
         * and the photographs - and this is a pump pressure. It was chosen because it read
         * nicely against the lime, which is exactly the reasoning that rule exists to
         * refuse. DIM: a figure that is not a state, on a track that is not progressing. */
        // Both in TEXT (polish TR-11): lime is for actions, and a DIM length read as off.
        big.setTextColor(Ui.TEXT);
        big.setTextSize(Look.SP_TITLE);
        Ui.tabular(big);   // prescribed, not live: the sans face with tabular digits
        col.addView(big);

        TextView sub = new TextView(a);
        int want = (int) Math.round(state.pressureKpa);
        int now = prescribedPressureKpa(track, state);
        /* A CAPTION, NOT A PARAGRAPH. This ran to four lines explaining the first-month cap,
         * which is a rule about the plan and belongs with the plan - the lane's job is to say
         * where the track IS. The full sentence lives in the length track's own detail. */
        sub.setText(length
            ? lengthLaneCaption(state, now, want)
            // The LEVEL is already in the title line an inch above; repeating it here
            // spends the lane caption on what the eye has just read. The pressure and the
            // direction are what this lane knows that the header does not.
            // ...and the caption looks FORWARD, which is the one thing neither the header
            // nor the big figure can say.
            : nextChangeShort());
        sub.setTextColor(Ui.DIM);
        sub.setTextSize(Look.SP_CAPTION);
        sub.setPadding(0, Ui.dp(a, 2), 0, 0);
        col.addView(sub);

        col.setOnClickListener(new TrainerViewTap(a.TRAINER_VIEW_TRACK, track));
        col.setContentDescription(TrainerTab.trackLabel(track) + ". "
            + (length ? "Static track at " + Model.Fmt.p(Math.round(state.pressureKpa))
                      : TrainerTab.levelLabel(state.level) + ", week "
                        + Math.max(1, state.weekIndex))
            + ". Opens this track detail.");
        return col;
    }

    /** Everything about one track: position, the week, what the next step needs, weeks
     *  ahead, and the lossless style switch. All of it was on the main scroll before. */
    private void renderTrainerTrackDetail() {
        int track = a.trainerViewTrack;
        Model.TrainerTrackState state = track == Plan.TRACK_LENGTH
            ? a.model.trainerLength : a.model.trainerGirth;
        a.header(a.body, TrainerTab.trackLabel(track), new TrainerBackTap());
        // LOAD LEADS ON THE LENGTH TRACK. The card below states the pressure first, which is
        // the right lead for girth work and the wrong one here: a length session is governed
        // by pounds, and two people at the same load run different pressures. So the load
        // card goes above it, and the pressure keeps its place as the coda's number.
        if (track == Plan.TRACK_LENGTH) {
            focusResultCard(state, System.currentTimeMillis());
            trainerLoadCard(state);
        }
        trainerPositionCard(track, state);
        trainerWeekCard(track, state);
        // O8 — ASKED ON EVERY TRACK, answered on every track. The week table only exists
        // for interval girth, and the other two used to get nothing at all: not a table,
        // not a line saying why there is no table, just blank space where the card was on
        // the track next door. Blank space reads as a bug. It is now the method's own job
        // to say what it can and cannot show.
        trainerWeeksAhead(track);
        // T2 - the level's own numbers, explained where they are printed. A floor, a net
        // target and a gate are three different kinds of claim and the screen states all
        // three as bare figures.
        trainerWorkingPressureCard(track, state);
        /* R2/R3/R4/R6 - THE SHAPE CONTROLS ARE STILL ONE COPY, and they are no longer
         * HERE. The old note was right that drawing them under each track would be three
         * copies of one setting and three places to disagree - and wrong to conclude that
         * the girth track was therefore the place for them. It made them look like girth's
         * settings, left the length track with no sign they exist, and produced the owner's
         * report: "I never saw the routine builder with the variant selection."
         *
         * They live at TRAINER_VIEW_SHAPE now. Every track shows the same door to the same
         * room, which is one copy AND a way in. */
        shapeDoorRow();
        LinearLayout gNums = Ui.cardGroup(a, a.body, "What these numbers mean", null, Ui.DIM);
        trainerFigureNote(gNums, state.level);
        if (track == a.model.trainerGirthStyle) {
            Button styleSwap = Ui.flat(a, a.body,
                a.model.trainerGirthStyle == Plan.TRACK_GIRTH_INTERVAL
                    ? "Switch to traditional girth" : "Switch to interval girth");
            styleSwap.setOnClickListener(new SwitchGirthStyleTap());
        }
    }

    /**
     * HOW ROUTINES ARE SHAPED — the variants, in one place, reachable from everywhere they
     * apply.
     *
     * {@link #rxShapeCards} is unchanged and is still called exactly once; what changed is
     * WHERE from. It used to be drawn inside the girth track's detail and nowhere else, so
     * the controls that decide the shape of every prescription looked like one track's
     * settings, the length track never showed them at all, and the Library — where the
     * words "routine builder" point — had no route to them. The owner's report was that
     * they had never seen them.
     */
    private void renderTrainerShape() {
        a.header(a.body, "What it writes", new TrainerBackTap());
        // SAID ONCE, AT THE TOP, because it is the fact the doors promise and the reason
        // this is not filed under a track: every track's prescription is shaped by these.
        Ui.noteInfo(a, a.body,
            "About these settings",
            "What it writes",
            "These shape every routine the trainer writes \u2014 both tracks.\n\n"
            + "The warm-up, the prime, the ease-in, the retention hold, the hold length, the "
            + "rests and the block shape are the variants: they decide the shape of what the "
            + "trainer writes, on the girth track and the length track alike.\n\n"
            + "They are one set of settings, not one per track \u2014 three copies of them "
            + "would be three places for them to disagree about a single prescription.\n\n"
            + "None of them changes what the plan asks of you. The sets, the hold and the "
            + "pressure are the plan's, and they are decided from your position and your "
            + "history. These decide what is built around them.");
        /* WAVE 3b — the PROGRAM pickers, per track, above the fine steppers. Changing
         * one re-signs the mint and syncPlanRoutines rewrites the plan's routines at
         * once, with the Today notice and Undo (Q14). */
        LinearLayout pg = Ui.cardGroup(a, a.body, "Program — girth", null);
        programRoomPickers(pg, a.model.programGirth, true);
        // ITEM 7 (wave 4): the L4 shears option lives HERE and only here — it shapes what
        // the trainer writes, so its home is this room, not Settings (the mirror there is
        // gone). Offered at every level; the note says when it starts doing anything.
        Ui.kvRow(a, pg, SHEARS_LABEL, a.model.rxSupersetRests, new ToggleShearsRoomTap());
        // Polish TR-20: no level code or option code in the label, and a real dash.
        Ui.noteInfo(a, pg, a.model.trainerGirth != null && a.model.trainerGirth.level >= Plan.L4
                ? "Rest cards will say to shear."
                : "Starts at Level 4 \u2014 nothing changes before then.",
            "Shears during rests",
            "A Level 4 intensification: the rest between sets becomes a set of manual shears "
            + "rather than only a rest. The app's part is saying when \u2014 it commands "
            + "nothing different and the pump does exactly what it did.\n\n"
            + "It changes the words on the rest card and nothing else, and only on routines "
            + "written at Level 4.");
        if (a.model.trainerLengthOn) {
            LinearLayout pl = Ui.cardGroup(a, a.body, "Program — length", null);
            programRoomPickers(pl, a.model.programLength, false);
            // t10 R-46, R-63 - how the traction load moves after month 3, while the strain sets
            // are under 12: only where there is a length cylinder to pull with.
            // A value row that opens both options with their effects (polish NEW-3, SYS-3).
            if (PlanCards.lenLoadShown(a.model))
                Ui.choiceRow(a, pl, PlanCards.LEN_LOAD_ROW, PlanCards.LEN_LOAD_LABELS,
                    PlanCards.lenLoadEffects(), PlanCards.lenLoadIndex(a.model.lengthLoadMode),
                    a.rootFrame, new LenLoadChoice());
        }
        rampsCard();
        rxShapeCards();
    }

    /* ------------------------------------------------------------ the ramps (0.10) */

    static final int RAMP_START = 0, RAMP_SHORT = 1, RAMP_STEP = 2;
    static final int RAMP_LIGHTER = 0, RAMP_COUNT = 1;

    /** Whether a track the plan runs has its work sets Ramped - where the Ramps card shows. */
    private boolean rampedTrack(boolean girth) {
        if (girth) return a.model.trainerGirthOn
            && a.model.programGirth.work == Model.Program.WORK_RAMP_IN_SET;
        return a.model.trainerLengthOn
            && a.model.programLength.work == Model.Program.WORK_RAMP_IN_SET;
    }

    /**
     * 0.10 - THE RAMPS (the owner's decisions): how a Ramped block climbs, shown while a track's
     * work sets are "ramp in each set". One set of settings for both tracks, like every setting
     * in this room. A change rewrites the plan's routines at once, with the notice and Undo
     * (syncPlanRoutines), exactly as a Program picker does.
     */
    private void rampsCard() {
        boolean g = rampedTrack(true), l = rampedTrack(false);
        if (!g && !l) return;
        a.model.clampRxShape();
        LinearLayout rc = Ui.cardGroup(a, a.body, "Ramps", null, Ui.ACCENT);
        Ui.stepperRow(a, rc, "Start at (share of your working pressure)", a.model.rampStartPct + "%",
            new BumpRamp(RAMP_START, -1), new BumpRamp(RAMP_START, +1), "ramp start");
        Ui.stepperRow(a, rc, "After a rest, climb", shortClimbWords(a.model.rampShortSteps),
            new BumpRamp(RAMP_SHORT, -1), new BumpRamp(RAMP_SHORT, +1), "climb after a rest");
        Ui.stepperRow(a, rc, "Each hold climbs",
            "up to " + Model.Fmt.mag(a.model.rampStepHg * Model.Fmt.KPA_PER_INHG),
            new BumpRamp(RAMP_STEP, -1), new BumpRamp(RAMP_STEP, +1), "ramp step");
        Ui.kvRow(a, rc, "Lighter days keep the ramp", a.model.rampLighterDays,
                 new ToggleRamp(RAMP_LIGHTER));
        Ui.kvRow(a, rc, "Count the climbing holds", a.model.rampCountClimb,
                 new ToggleRamp(RAMP_COUNT));
        // THE LIVE PREVIEW, at today's working pressure for each Ramped track.
        if (g) rampPreview(rc, "Girth", true);
        if (l) rampPreview(rc, "Length", false);
        Ui.noteInfo(a, rc, a.model.rampCountClimb
                ? "The climbing holds count as work. One under the line your time is counted "
                  + "from isn't made up, but your level still credits it."
                : "Only holds at your working pressure count. The climb comes before them and "
                  + "nothing is made up for it.",
            "How a ramp runs",
            "The first block climbs all the way: it starts at the share of the day's working "
            + "pressure set above \u2014 your pressure after your own offset, gentle or firm, "
            + "and any lighter day \u2014 and each hold climbs no more than the step, until it "
            + "reaches your working pressure. The rest of the block holds there.\n\n"
            + "After a rest, a block climbs only a little: the number of steps set above, the "
            + "last of them at your working pressure. None starts straight at it.\n\n"
            + "Lighter days keep the ramp and climb to the lighter pressure. Turn it off and a "
            + "lighter day runs fixed holds.\n\n"
            + "Counting. On, the climbing holds are part of the block's holds, and a counted "
            + "climbing hold's shortfall under your working pressure is made up with extra "
            + "holds at it. A hold under the line your time is counted from counts nothing: "
            + "it is not made up and not in the target, so the session is about as long as "
            + "fixed holds. Your level credits it at the rate you delivered the target, so a "
            + "full session reads as the plan's minutes. Off, only holds at your working "
            + "pressure count: the block keeps all its holds there and the climb comes first, "
            + "not counted.\n\n"
            + "Every change rewrites the trainer's routines at once, with a note on Today "
            + "and Undo.");
    }

    /** "none — starts at your working pressure", "2 steps", "3 steps". */
    static String shortClimbWords(int steps) {
        return steps <= 1 ? "none \u2014 starts at your working pressure" : steps + " steps";
    }

    /** One track's preview line, at today's working pressure. */
    private void rampPreview(LinearLayout into, String track, boolean girth) {
        Mint.Rx rx = roomRx(girth);
        if (rx == null) return;
        int work = RxBuild.commandedKpa(a.model, rx);
        Ui.note(a, into, track + " today \u2014 " + Ramp.previewLine(work, a.model.rampStartPct,
            a.model.rampShortSteps, a.model.rampStepHg));
    }

    private final class BumpRamp implements View.OnClickListener {
        private final int what, dir;
        BumpRamp(int w, int d) { what = w; dir = d; }
        @Override public void onClick(View v) {
            switch (what) {
                case RAMP_START: a.model.rampStartPct += dir * 5; break;
                case RAMP_SHORT:
                    // 1 would be the arrival alone - the same as none - so the stepper skips it.
                    int n = a.model.rampShortSteps <= 1 ? 0 : a.model.rampShortSteps;
                    n += dir;
                    if (n == 1) n = dir > 0 ? 2 : 0;
                    a.model.rampShortSteps = n;
                    break;
                case RAMP_STEP: a.model.rampStepHg += dir * 0.1; break;
                default: return;
            }
            a.model.clampRxShape();
            Store.save(a, a.model);
            a.syncPlanRoutines();   // rewrite at once, notice + Undo
            showTrainer();
        }
    }

    private final class ToggleRamp implements View.OnClickListener {
        private final int what;
        ToggleRamp(int w) { what = w; }
        @Override public void onClick(View v) {
            if (what == RAMP_LIGHTER) a.model.rampLighterDays = !a.model.rampLighterDays;
            else a.model.rampCountClimb = !a.model.rampCountClimb;
            Store.save(a, a.model);
            a.syncPlanRoutines();   // rewrite at once, notice + Undo
            showTrainer();
        }
    }

    /* ---------------------------------------------------- the gentle warm-up (0.10) */

    static final int GENTLE_START = 0, GENTLE_SPEED = 1, GENTLE_STEP = 2;

    /**
     * 0.10 - THE GENTLE WARM-UP, shown while "I mark or bruise easily" is on: where it starts,
     * how fast, and how much it climbs a rep, with a live preview at today's first work hold.
     * The app's own warm-up for people who mark - the page never calls it the guidance's.
     */
    private void gentleWarmCard() {
        if (!a.model.marksEasily) return;
        a.model.clampRxShape();
        LinearLayout gw = Ui.cardGroup(a, a.body, "Gentle warm-up", null, Ui.ACCENT);
        // The stored figure, as the prime's own stepper shows it (4.0 inHg is 13.55 kPa; the
        // preview below says what the pump is sent, in whole kPa).
        Ui.stepperRow(a, gw, "Starts at", Model.Fmt.p(a.model.gentleWarmStartKpa),
            new BumpGentle(GENTLE_START, -1), new BumpGentle(GENTLE_START, +1),
            "gentle warm-up start pressure");
        Ui.stepperRow(a, gw, "Starting speed", a.model.gentleWarmSpeedPct + "%",
            new BumpGentle(GENTLE_SPEED, -1), new BumpGentle(GENTLE_SPEED, +1),
            "gentle warm-up starting speed");
        Ui.stepperRow(a, gw, "Each rep climbs",
            "up to " + Model.Fmt.mag(a.model.gentleWarmStepHg * Model.Fmt.KPA_PER_INHG),
            new BumpGentle(GENTLE_STEP, -1), new BumpGentle(GENTLE_STEP, +1),
            "gentle warm-up step");
        if (a.model.trainerGirthOn) gentlePreview(gw, "Girth", true);
        if (a.model.trainerLengthOn) gentlePreview(gw, "Length", false);
        Ui.noteInfo(a, gw,
            SetupText.GENTLE_WARM_FACE,
            "The gentle warm-up",
            "Because you mark or bruise easily, the warm-up is this one, whatever warm-up "
            + "shape is set below. It starts at the pressure and speed above and climbs rep "
            + "by rep to your working pressure \u2014 the first hold of your work \u2014 never "
            + "more than the step above in one rep, the speed rising evenly to your work's own. "
            + "If your working pressure is at or under the start, it starts there instead, "
            + "never above it.\n\n"
            + "It is this app's own warm-up for people who mark easily. On the length track it "
            + "stops at 80% of the pull or the expansion it leads into, as every length "
            + "warm-up does.\n\n"
            + "Each rep is a 25-second hold and a short drop. Like any warm-up it is out of "
            + "your net and changes no target. The warm-up's length, below, still decides "
            + "whether there is one: none means none.");
    }

    /** The highest the gentle warm-up's start may be set: today's working pressure on the
     *  tracks the plan runs (the higher of the two), or the absolute limit with neither. */
    private int gentleStartMaxKpa() {
        int most = 0;
        for (int i = 0; i < 2; i++) {
            boolean girth = i == 0;
            if (girth ? !a.model.trainerGirthOn : !a.model.trainerLengthOn) continue;
            Mint.Rx rx = roomRx(girth);
            if (rx != null) most = Math.max(most, RxBuild.commandedKpa(a.model, rx));
        }
        return most > 0 ? most : (int) Math.floor(Model.GENTLE_START_KPA_MAX);
    }

    /** One track's gentle warm-up, as today's routine would run it. */
    private void gentlePreview(LinearLayout into, String track, boolean girth) {
        Mint.Rx rx = roomRx(girth);
        if (rx == null) return;
        String line = RxBuild.gentleWarmLine(a.model, rx);
        if (line.length() > 0) Ui.note(a, into, track + " today \u2014 " + line);
    }

    private final class BumpGentle implements View.OnClickListener {
        private final int what, dir;
        BumpGentle(int w, int d) { what = w; dir = d; }
        @Override public void onClick(View v) {
            switch (what) {
                case GENTLE_START: {
                    // One unit of what is on screen, landing on a whole kPa (Fmt#stepWholeKpa),
                    // from 2.0 inHg (clampRxShape) up to today's working pressure: a start over
                    // it would start at it anyway, so "+" stops there.
                    int next = Model.Fmt.stepWholeKpa(
                        (int) Math.round(a.model.gentleWarmStartKpa), dir);
                    if (dir > 0 && next > gentleStartMaxKpa()) break;
                    a.model.gentleWarmStartKpa = next;
                    break;
                }
                case GENTLE_SPEED: a.model.gentleWarmSpeedPct += dir * 5; break;
                case GENTLE_STEP: a.model.gentleWarmStepHg += dir * 0.1; break;
                default: return;
            }
            a.model.clampRxShape();
            Store.save(a, a.model);
            a.syncPlanRoutines();   // rewrite at once, notice + Undo
            showTrainer();
        }
    }

    /** The door, drawn identically wherever it appears, so the same room is never described
     *  two different ways. Kept to one method for that reason rather than three call sites
     *  each writing their own label. */
    private void shapeDoorRow() {
        // THE SAME FACTS THE SAVED-SHAPE ROWS PRINT (Model.Shape#summaryParts), taken from
        // the settings in force right now - so this door and the "Saved shapes" list inside
        // the room can never describe one shape two ways.
        Ui.doorRow(a, a.body, "What it writes",
            Model.Shape.capture(a.model, "", "").summaryParts(a.model),
            new TrainerViewTap(a.TRAINER_VIEW_SHAPE, 0));
    }

    /** The plan's own machinery, off the daily scroll. */
    /**
     * WHERE I AM - what was "Plan settings", named for the question it answers.
     *
     * The position leads, one door per track, each carrying its own facts and opening that
     * track's detail. Then the two things that CHANGE a position - recalibrating it and
     * pausing it - and the history of how it got here.
     *
     * THE ONE PAUSE. steerCards() drew a second "Pause the plan" with the same listener; two
     * doors to one action is how a reader learns not to trust the map. Pausing is a
     * statement about where you are, so this is the copy that stays. The reminders that used
     * to sit here moved to "When it runs", which is what they are about.
     */
    private void renderTrainerWhere() {
        a.header(a.body, "Where I am", new TrainerBackTap());
        Model.TrainerTrackState g = a.model.trainerGirth;
        String[] girth = a.model.trainerGirthStyle == Plan.TRACK_GIRTH_INTERVAL
            ? new String[]{ TrainerTab.levelLabel(g.level),
                            "wk " + Math.max(1, g.weekIndex),
                            Model.Fmt.p(Math.round(g.pressureKpa)) }
            : new String[]{ TrainerTab.levelLabel(g.level), Model.Fmt.p(Math.round(g.pressureKpa)) };
        Ui.doorRow(a, a.body, TrainerTab.trackLabel(a.model.trainerGirthStyle), girth,
            new TrainerViewTap(a.TRAINER_VIEW_TRACK, a.model.trainerGirthStyle));
        if (a.model.trainerLengthOn) {
            Model.TrainerTrackState l = a.model.trainerLength;
            // Named by its LOAD: the figure that governs a length session (see its own card).
            Ui.doorRow(a, a.body, TrainerTab.trackLabel(Plan.TRACK_LENGTH),
                new String[]{ TrainerTab.levelLabel(l.level),
                    Traction.settingLb(Scale.shownLoadLb(a.model, System.currentTimeMillis())) },
                new TrainerViewTap(a.TRAINER_VIEW_TRACK, Plan.TRACK_LENGTH));
        }
        // Doors with the chevron icon, not a text "\u203a" (polish SYS-5).
        Ui.doorRow(a, a.body, "Recalibrate my position", new String[0],
            new StartOnboardTap(true));
        // t10 R-61 - the upgrade card, once put off three times (or for this app start), stays
        // here as a row - named by what it does, not the card's headline (polish TR-3).
        if (PlanCards.upgradeRowShown(a.model, planT10LaterThisRun))
            Ui.doorRow(a, a.body, PlanCards.UPGRADE_ROW, new String[]{ PlanCards.UPGRADE_ROW_SUB },
                new UpgradeCardTap(true));
        trainerDecisionHistory();
        /* A DANGER BUTTON THAT SAYS IT ASKS (polish TR-36), centred, with the one fact that
         * makes it safe to press. */
        Button pause = Ui.danger(a, a.body, "Pause the plan", true);
        pause.setContentDescription("Pause the plan. Nothing is lost; it stops advancing "
            + "until you come back.");
        pause.setOnClickListener(new PausePlanTap());
        Ui.note(a, a.body, "You can resume where you were.");
    }

    /**
     * WHEN IT RUNS - the schedule, the ceiling and the run behaviour the plan works inside.
     *
     * THESE ARE LINKS, NOT CONTROLS. Every one of them is a Settings value that Today, the
     * run screen and the reminders also read, and a second editor for it here would be two
     * places to change one number. So each row states the value and opens Settings on the
     * category that owns it (SessionActivity#openSettingsCategory). Before this room existed
     * nothing in the Trainer pointed at any of them.
     *
     * The trainer's OWN reminders are the exception, because they are the Trainer's: their
     * one copy lives here.
     */
    private void renderTrainerWhen() {
        a.header(a.body, "When it runs", new TrainerBackTap());
        /* t10 R-60, R-63 - LONG TRAINING DAYS IS THE TRAINER'S OWN, so it is a control here
         * rather than a link: how the two tracks share the week. Above the schedule's rows,
         * which it decides between. Hidden with one track on (K21). */
        if (LongDays.shown(a.model)) {
            int ld = a.model.sched.longDays;
            /* A VALUE ROW THAT OPENS ITS LIST (polish TR-22): it cycled through four choices on
             * tap, rewriting the routines each time, with no chevron and no way back but three
             * more taps. The list shows each choice's effect; a pick says what changed, with
             * Undo. */
            Ui.choiceRow(a, a.body, LongDays.ROW, LongDays.LABELS,
                LongDays.effects(a.model.sched), LongDays.index(ld), a.rootFrame,
                new LongDaysChoice());
            Ui.note(a, a.body, LongDays.effect(a.model.sched, ld));
            Ui.noteInfo(a, a.body, "How a week of both tracks is counted", LongDays.ROW,
                LONG_DAYS_WHY);
            redLine(a.body, LongDays.shortLine(a.model));
        }
        Ui.noteInfo(a, a.body, "These live in Settings.", "When it runs",
            "These live in Settings \u2014 open one to change it there, and "
            + "it changes everywhere.");
        Ui.doorRow(a, a.body, "Training days", new String[]{ a.model.sched.daysLine() },
            a.new OpenSettingsCategoryTap("Training schedule"));
        Ui.doorRow(a, a.body, "Training time",
            new String[]{ a.model.sched.hhmm(),
                          "notifications " + (a.model.sched.remind ? "on" : "off") },
            a.new OpenSettingsCategoryTap("Training schedule"));
        Ui.doorRow(a, a.body, "Safety ceiling",
            new String[]{ Model.Fmt.p(a.model.ceilKpa) },
            a.new OpenSettingsCategoryTap("Pump"));
        Ui.doorRow(a, a.body, "How a run behaves",
            new String[]{ "guided start " + (a.model.guidedStart ? "on" : "off"),
                          a.model.tupTiming ? "timed at pressure" : "timed by the clock" },
            a.new OpenSettingsCategoryTap("How a run behaves"));
        remindersBlock();
    }

    /** WEEKS AHEAD (audit A29) \u2014 Plan.intervalMasterPlan, written and never called. */
    private void trainerWeeksAhead(int track) {
        /* THE CADENCE EXPLANATION LIVES BEHIND THE INFO BUTTON, which is what an info
         * button is for. It was a paragraph in body type under the table - reference
         * material at the size of the figures it explains. */
        /* A - THE HEADER GIVES THE WEEK NUMBER A DENOMINATOR AND AN ACCOUNT.
         *
         * Reported from a device: "wk 1-2 . now" to somebody six months into the app, with
         * nothing on screen able to reconcile the two. The number is right - a plan week
         * advances when a week COUNTS - and it was unexplained, which is the actual defect.
         *
         * COUNTED FROM ENROLMENT, on this track, by the same walk the deload cadence uses.
         * Not a second definition of a training week: one rule, asked over a different span.
         */
        long wkNow = System.currentTimeMillis();
        int counted = a.model.trainerEnrolledAt > 0
            ? TrainerTab.accumulatedTrainingWeeks(a.model, track, a.model.trainerEnrolledAt, wkNow)
            : 0;
        int lvl = track == Plan.TRACK_LENGTH ? a.model.trainerLength.level
                                             : a.model.trainerGirth.level;
        int wkIdx = track == Plan.TRACK_LENGTH ? a.model.trainerLength.weekIndex
                                               : a.model.trainerGirth.weekIndex;
        /* THE TABLE'S OWN WEEK NUMBER. weekIndex is the row within the level's own table -
         * Level 2 starts again at 1 - and everything on this card reads the tables' one
         * scale (Level 2 is weeks 18-32), so a Level 2 position is translated before it is
         * drawn or compared. It showed Level 1's rows at Level 2. */
        if (track == Plan.TRACK_GIRTH_INTERVAL) wkIdx = TrainerTab.tableWeekNum(lvl, wkIdx);
        /* E - AND THE ANSWER LIVES BEHIND ONE \u24d8. The sheet explained the collapsing and the
         * deloads and not the one thing a reader is most likely to be puzzled by. Polish TR-13:
         * the title's round info button and the note's \u24d8 were two on one card; the sheet now
         * rides the note's \u24d8 (weeksWhy), and the title has none. */
        String weeksWhy = "Why does this say week " + Math.max(1, wkIdx) + "?\n\n"
            + "A plan week advances when a week counts \u2014 two full sessions, or three "
            + "shorter days. A week with less does not move the plan on, so the number here "
            + "counts training weeks, not weeks on the calendar.\n\n"
            + (a.model.trainerEnrolledAt > 0
                ? "You have had the plan " + weeksSinceEnrol(wkNow)
                  + (weeksSinceEnrol(wkNow) == 1 ? " week and " : " weeks and ")
                  + counted + " of them counted.\n\n"
                : "")
            + "That is the plan waiting rather than anything going wrong: it advances at the "
            + "pace the work is actually done at, which is the whole reason it does not run "
            + "ahead of you.\n\n"
            + "The plan's own weeks for your style, from where you are now, in what your "
            + "routine runs each week. Weeks that are "
            + "the same are shown as one row, so every row here is a week where "
            + "something changes.\n\nA deload is part of the plan rather than a gap "
            + "in it: the plan\u2019s week count pauses, the week does not need three training "
            + "days, and nothing is lost.\n\n" + trainerStateLine();
        LinearLayout gAhead = Ui.cardGroup(a, a.body, "The next weeks",
            track == Plan.TRACK_GIRTH_INTERVAL
                ? TrainerTab.weeksHeadline(lvl, wkIdx, counted) : null, Ui.DIM);
        if (track == Plan.TRACK_GIRTH_INTERVAL) levelProgressBar(gAhead, lvl, wkIdx);
        // O8 - SAY WHAT CANNOT BE SHOWN. Only interval girth has a week table:
        // Plan.intervalMasterPlan is the only one there is. That is a real difference
        // between the tracks, not an omission - so the fix is to state it on the tracks
        // that have none, never to invent a table for them and never to leave the space
        // blank, which is how a difference starts reading as a missing feature.
        if (track == Plan.TRACK_LENGTH) {
            Ui.noteInfo(a, gAhead,
                "No week table on this track.",
                "Why no week table",
                "No week table on this track. Length moves on months and "
                + "on what your readings actually show, not on a weekly schedule \u2014 the next "
                + "change comes from a measurement, so there is nothing to print ahead of it.");
            return;
        }
        if (track != Plan.TRACK_GIRTH_INTERVAL) {
            // TRADITIONAL GROWS BY THE WEEK (0.10): where it is and when the next hold comes.
            Ui.noteInfo(a, gAhead,
                TrainerTab.traditionalWeeksSaid(a.model.trainerGirth, Mint.r2FatSec(a.model,
                    Plan.TRACK_GIRTH_TRADITIONAL, a.model.trainerGirth.level)),
                "How traditional grows",
                TrainerTab.TRADITIONAL_GROWTH_WORDS
                    + TrainerTab.traditionalBuildUpWords(a.model.trainerGirth)
                    + "\n\n" + weeksWhy);
            return;
        }
        java.util.List<Plan.Week> all = Plan.intervalMasterPlan();
        /* THE WEEKS AS THE ROUTINE WILL RUN THEM (TrainerTab#weeksAhead).
         *
         * It printed the guidance's table: at Level 3 and 4, which have none, Level 1's rows by
         * the level's own week number, and everywhere the table's raw pressure where the
         * routine runs the person's own. Every figure now comes from the prescription the
         * routine is written from, at that week - collapsed, in the person's unit, the current
         * week in it. A level with no table shows the current week's real prescription and
         * says what moves it, never another level's rows.
         */
        java.util.List<TrainerTab.AheadRow> rows = TrainerTab.weeksAhead(a.model, track, wkNow, 6);
        for (int i = 0; i < rows.size(); i++) {
            TrainerTab.AheadRow r = rows.get(i);
            // THE EVENT GOES ABOVE ITS ROW, not below it. kvRow draws a divider AFTER
            // itself, so a line placed after the row sat on the far side of that rule and
            // read as belonging to the NEXT week - the one thing an annotation must not do.
            // Above, it also reads in the right order: here is what changes, then the week
            // it changes to. Only a row of a table has the table's events.
            if (r.from > 0) a.weekEventRows(gAhead, all, r.from, r.to);
            // What the routine runs that week - holds, minutes, pressure; nothing measured.
            Ui.kvRow(a, gAhead, r.label(), r.value(), r.now ? Ui.ACCENT : Ui.TEXT, null);
        }
        if (rows.isEmpty()) {
            Ui.noteInfo(a, gAhead, "Past the end of this level's table — the next step is a "
                + "level change, not another table row.", "The next weeks", weeksWhy);
        } else if (TrainerTab.levelLastWeek(lvl) <= 0) {
            Ui.noteInfo(a, gAhead,
                "No week table at this level — your routine stays as above until a rule "
                + "moves it.",
                "What changes next",
                TrainerTab.noTableNextWords(a.model, wkNow) + "\n\n" + weeksWhy);
        } else {
            Ui.noteInfo(a, gAhead, TrainerTab.weeksAheadNote(a.model), "The next weeks",
                weeksWhy);
        }
    }

    /** Whole weeks since enrolment - the figure the info sheet sets the counted weeks
     *  against, so both numbers in "26 weeks and 9 of them counted" come from one clock. */
    private int weeksSinceEnrol(long now) {
        if (a.model.trainerEnrolledAt <= 0 || now <= a.model.trainerEnrolledAt) return 0;
        return (int) ((now - a.model.trainerEnrolledAt) / (7L * 24L * 60L * 60L * 1000L));
    }

    /**
     * HOW FAR THROUGH THE LEVEL, as a bar - the same fact the header states in words.
     *
     * A number alone never says how far along it is, and "week 2 of 17" is a distance the eye
     * reads faster than the arithmetic. Two weighted views rather than a custom View: it is a
     * proportion of a row, which is what layout weights are for.
     *
     * NOTHING IS DRAWN for a level with no table (L3/L4). A bar with no end to measure
     * against would be a fraction of an unknown, which is worse than no bar.
     *
     * SILENT TO A READER. The header above says the same thing in words, and a bar that
     * announced itself again would make one fact into two.
     */
    private void levelProgressBar(LinearLayout into, int level, int weekIndex) {
        int last = TrainerTab.levelLastWeek(level);
        if (last <= 0) return;
        double f = TrainerTab.levelProgress(level, weekIndex);

        LinearLayout track = new LinearLayout(a);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(Ui.roundRect(a, Ui.SURFHI, 3));
        View done = new View(a);
        done.setBackground(Ui.roundRect(a, Ui.ACCENT, 3));
        track.addView(done, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, (float) f));
        track.addView(new View(a), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, (float) (1.0 - f)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 5));
        tlp.topMargin = Ui.dp(a, 8);
        into.addView(track, tlp);

        LinearLayout ends = new LinearLayout(a);
        ends.setOrientation(LinearLayout.HORIZONTAL);
        TextView here = Ui.microLabel(a, null, "wk " + Math.max(1, weekIndex), Ui.FAINT);
        ends.addView(here, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView end = Ui.microLabel(a, null,
            "wk " + last + (level == Plan.L1 ? "  \u00b7  " + TrainerTab.levelLabel(Plan.L2) : ""), Ui.FAINT);
        end.setGravity(Gravity.RIGHT);
        ends.addView(end, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Ui.dp(a, 3);
        elp.bottomMargin = Ui.dp(a, 4);
        into.addView(ends, elp);
        // The header above already says "week 2 of 17" in words; the bar repeats it for
        // the eye alone, so it is skipped rather than made into a wordless stop.
        Ui.decorative(track);
        Ui.decorative(ends);
    }

    /** F11 - the MIDDLE of the recent tracked per-session nets, in minutes, or 0 when
     *  there is nothing tracked to take a middle of. The middle and not the mean, so one
     *  outlying session does not describe the rest; 0 means unknown, which is not a claim
     *  that nothing was delivered. */
    private double typicalTrackedNetMin(int track) {
        return middleOf(TrainerTab.recentTrackedNetsMin(a.model, track, 8));
    }

    /** ...the same middle as the Level 1 gate reads the sessions: with the sets a
     *  both-tracks day gave to the length session, and a ramp's climbing holds under the
     *  counting line, credited back (TrainerTab#recentCreditedNetsMin), so the gate card never
     *  shows 10 of 20 on a gate the credited sessions have met (0.10). */
    private double typicalGateNetMin(int track) {
        return middleOf(TrainerTab.recentCreditedNetsMin(a.model, track, 8));
    }

    private static double middleOf(double[] nets) {
        if (nets.length == 0) return 0;
        double[] own = new double[nets.length];
        System.arraycopy(nets, 0, own, 0, nets.length);
        java.util.Arrays.sort(own);
        return own.length % 2 == 1 ? own[own.length / 2]
                                 : (own[own.length / 2 - 1] + own[own.length / 2]) / 2.0;
    }

    /**
     * MY PRESSURE - "plan ± x" (the owner's decision, 0.10). The card that used to move the
     * track's working pressure itself now moves the person's OFFSET from it: the plan's own
     * figure goes on stepping up exactly as it did (every step still happens, on top of the
     * offset), and every routine for the track runs at that figure plus this.
     *
     * WHY THE PLAN'S FIGURE IS NO LONGER A STEPPER. Moving it restarted the pressure clock and
     * was clamped into the level's band, so somebody above the band was silently held to its
     * top, and a "+" on a figure already over the cap wrote the cap - a "+" that lowered the
     * pressure. The offset is kept as it is set; the level's top moves with it; the hard
     * limits - the ceiling, "Most you will go to", 15 inHg, a new person's first month - do
     * not (Scale). An offset past the usual top is warned once (Scale#needsWarning).
     *
     * A change rewrites the track's saved routines at once, with the notice and Undo, like
     * any plan change (the offset is in the signature - Model#mintShapeTag).
     */
    private void trainerWorkingPressureCard(int track, Model.TrainerTrackState state) {
        long now = System.currentTimeMillis();
        int month = TrainerTab.monthIndexNow(a.model, now);
        int hard = Scale.hardKpa(a.model, track, month);
        // G2: against the plan figure's own top - the climb's, past the usual one, when the
        // person's maximum is above it.
        double planTop = Scale.planTopKpa(a.model, track, state.level, month);
        int plan = Scale.planOwnKpa(state.pressureKpa, planTop, hard);
        double applied = Scale.appliedOffsetKpa(a.model, track, month);
        int mine = Scale.scaledKpa(state.pressureKpa, applied, planTop, hard);
        LinearLayout g = Ui.cardGroup(a, a.body, "My pressure", null, Ui.ACCENT);
        Ui.kvRow(a, g, "The plan's pressure", Model.Fmt.p(plan), Ui.TEXT, null);
        Ui.stepperRow(a, g, "My pressure",
            Scale.offsetText(state.offsetKpa) + "  \u00b7  " + Model.Fmt.p(mine),
            new BumpTrackOffset(track, -1), new BumpTrackOffset(track, +1),
            "my pressure for " + TrainerTab.trackLabel(track));
        String why = "Every routine for this track runs at the plan's pressure plus this, and "
            + "the plan keeps stepping up underneath it. The level's top moves with it too, so "
            + "the steps land where they always would, shifted by your offset. Going past the "
            + "usual top is asked once. Nothing ever goes past " + Model.Fmt.p(hard)
            + " \u2014 your ceiling, the most you said you will go to, and "
            + Model.Fmt.p(Plan.ABSOLUTE_CAP_KPA) + " stay hard. Below the plan, your time still "
            + "counts: the line net time counts from moves down with you, and the plan's "
            + "levels still read the plan's own figure. Changing it rewrites this track's "
            + "saved routines, and you can undo that.";
        Ui.noteInfo(a, g, "Your routines run at the plan's pressure plus this.",
            "My pressure", why);
        if (Scale.newMonthOne(Scale.isNew(a.model), month) && state.offsetKpa != 0.0)
            Ui.note(a, g, "Your first month runs at the plan's own pressure; this starts in "
                + "your second month.");
    }

    /** −/+ on "My pressure": the offset one step (0.1 of your unit, a whole kPa in kPa). */
    private final class BumpTrackOffset implements View.OnClickListener {
        private final int track, dir;
        BumpTrackOffset(int t, int d) { track = t; dir = d; }
        @Override public void onClick(View v) {
            Model.TrainerTrackState st = Scale.stateOf(a.model, track);
            long now = System.currentTimeMillis();
            double want = Scale.stepOffset(st.offsetKpa, dir, Scale.offsetStepKpa());
            if (want == st.offsetKpa) {
                // Silent refusal reads as a dead button.
                a.toast(dir > 0 ? "That is as far above the plan as this goes"
                                : "That is as far below the plan as this goes");
                return;
            }
            int month = TrainerTab.monthIndexNow(a.model, now);
            int hard = Scale.hardKpa(a.model, track, month);
            // (In a new person's first month the offset waits for month two, so it may be set.)
            if (dir > 0 && !Scale.newMonthOne(Scale.isNew(a.model), month)
                    && Scale.scaledKpa(st.pressureKpa,
                    Scale.appliedOffsetKpa(a.model, track, month),
                    Scale.planTopKpa(a.model, track, st.level, month), hard)
                    >= hard) {
                // A "+" that could not move anything is refused - and says which limit.
                a.toast(hardWhy(track, hard));
                return;
            }
            if (Scale.needsWarning(want, st.warnedOffsetKpa)
                    && Scale.pastUsualTop(track, st.level, month, want)) {
                double gir = track == Plan.TRACK_LENGTH
                    && a.model.tractionShapeTag(now).length() > 0 ? a.model.lengthBoreCm() : 0;
                Ui.dress(a, Ui.dialog(a)
                    .setTitle(Scale.WARN_TITLE)
                    .setMessage(Scale.offsetWarning(track, st.level, month, want,
                        Scale.pullHardKpa(a.model, track), gir, Scale.isNew(a.model)))
                    .setPositiveButton(Scale.warnGo(want), new ConfirmTrackOffset(track, want))
                    .setNegativeButton(Scale.WARN_KEEP, null)
                    .show());
                return;
            }
            setTrackOffset(track, want);
        }
    }

    /** The warning, answered "go": remembered for this track, then set. */
    private final class ConfirmTrackOffset implements DialogInterface.OnClickListener {
        private final int track; private final double want;
        ConfirmTrackOffset(int t, double w) { track = t; want = w; }
        @Override public void onClick(DialogInterface d, int w) {
            Model.TrainerTrackState st = Scale.stateOf(a.model, track);
            st.warnedOffsetKpa = Scale.confirmed(want, st.warnedOffsetKpa);
            setTrackOffset(track, want);
        }
    }

    /** Sets the offset and lets the plan rewrite its routines at once, notice and Undo. */
    private void setTrackOffset(int track, double want) {
        Scale.stateOf(a.model, track).offsetKpa = Scale.clampOffset(want);
        Store.save(a, a.model);
        a.syncPlanRoutines();
        showTrainer();
    }

    /** Which hard limit a pressure has reached on `track`, in words - its own maximum. */
    private String hardWhy(int track, int hard) {
        if (hard >= a.model.ceilKpa) return "That is your safety ceiling";
        double most = Scale.mostKpa(a.model, track);
        if (most > 0 && hard >= (int) Math.floor(most + 1e-9))
            return "That is the most you said you will go to";
        if (hard >= Plan.absoluteCapWholeKpa()) return "That is the absolute limit";
        return "That is the first month's limit";
    }

    private final class TrainerViewTap implements View.OnClickListener {
        private final int view, track;
        TrainerViewTap(int v, int t) { view = v; track = t; }
        @Override public void onClick(View v) {
            a.trainerView = view;
            if (track != 0) a.trainerViewTrack = track;
            showTrainer();
        }
    }

    private final class TrainerBackTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.trainerView = a.TRAINER_VIEW_MAIN;
            showTrainer();
        }
    }

    /**
     * THE PLAN'S OWN STATE, appended to the readiness line - and ONLY when it has something
     * to say.
     *
     * The old screen printed trainerStateLine() every week, which meant the three states
     * that matter - frozen in a deload, stepped back a level, held by a safety flag - shared
     * a slot with a paragraph about the deload cadence that never changed. A line that is
     * usually boilerplate is a line people stop reading, and these three are exactly the
     * ones that must be read.
     *
     * So the standing explanation moved behind the "What is coming" door with the rest of
     * the schedule, and this says nothing at all on a normal week.
     */
    /**
     * THE PLAN'S STATE AS A CHIP, beside the position in the title line - and empty on a
     * normal week.
     *
     * Restructuring this screen has now twice dropped the three states that matter: frozen
     * in a deload, stepped back a level, held by a safety flag. They kept riding on whatever
     * line happened to be at the top, and every reorder moved that line. In the header they
     * sit beside the position, which is the one thing on this screen that can never be
     * anywhere else.
     *
     * SHORT, because the full sentence for each is behind the schedule door where the rest
     * of the plan's explanation lives. This is the flag; that is the reason.
     */
    private String trainerStateChip() {
        switch (a.model.trainerState) {
            case Model.TRAINER_STATE_FROZEN_DELOAD: return "deload week";
            case Model.TRAINER_STATE_STEP_BACK:     return "stepped back";
            case Model.TRAINER_STATE_SAFETY_FLAG:   return "on hold";
            default: return "";
        }
    }

    /** The colour that state deserves. Amber for a hold, because it is the one that stops a
     *  session; violet for a deload, the app's body/rest colour; DIM for a step back, which
     *  is a position, not an alarm. */
    private int trainerStateChipColour() {
        switch (a.model.trainerState) {
            case Model.TRAINER_STATE_SAFETY_FLAG:   return Ui.CMD;
            // A DELOAD IS NOT A BODY MEASUREMENT. Violet is the tape measure and the
            // photographs; a paused week is a plan state, and DIM is what a quiet state
            // wears everywhere else in this app.
            case Model.TRAINER_STATE_FROZEN_DELOAD: return Ui.DIM;
            default: return Ui.DIM;
        }
    }

    private String trainerStateLine() {
        switch (a.model.trainerState) {
            case Model.TRAINER_STATE_FROZEN_DELOAD:
                return "Deload week — the plan\u2019s week count pauses for 7 days. Light or no "
                    + "pumping.";
            case Model.TRAINER_STATE_STEP_BACK:
                return "Stepped back — the plan went back a level or a week.";
            case Model.TRAINER_STATE_SAFETY_FLAG:
                return "Safety flag active — on hold until an all-good readiness report.";
            default:
                return deloadCadenceLine();
        }
    }

    /** The month a calendar gate is reached on, at the current rate - which for a gate
     *  counted in months IS the current rate, since months pass whatever you do. */
    private String monthGateWhen(int monthsSoFar, int need) {
        if (monthsSoFar >= need) return "";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.MONTH, need - monthsSoFar);
        return "About " + new SimpleDateFormat("MMM yyyy", Locale.US).format(c.getTime())
             + ", since months pass whether you train or not.";
    }

    /**
     * THE LENGTH TRACK'S LOAD CARD - what the tissue is actually being asked for.
     *
     * The figure that leads is the DELIVERED load: the command is rounded to a whole kPa and
     * clamped to the device ceiling, and both move it, so the requested figure is not the one
     * anybody feels. The commanded pressure is small print underneath, because it is an
     * implementation detail of producing pounds.
     *
     * WITH NO CYLINDER THAT PULLS there is no load and the card says so plainly instead of
     * printing a number derived from a tube that inflates - which is the same thing the
     * ladder's first rung says, in the same words, so the card and the decision cannot
     * disagree about whether this track is pulling today.
     */
    /**
     * WHAT THE LENGTH LANE SAYS IT IS DOING.
     *
     * It said "static by design" - which was true of a track whose only move was a pressure
     * creep, and became false the moment the track started governing a load. A lane that
     * describes the old behaviour of a track that has changed is worse than a blank one: it
     * is a confident wrong answer, and it sat one tap above the card that contradicts it.
     *
     * The month cap still leads when it binds, because "why is it lower than the plan says"
     * is the more urgent question. Otherwise the lane says what the track is actually doing:
     * a load, if there is a tube that can pull, and expansion if there is not.
     */
    private String lengthLaneCaption(Model.TrainerTrackState state, int nowKpa, int wantKpa) {
        /* THE REST LEADS. Every other caption describes what the track is doing this week,
         * and a resting track is not doing any of them - a lane that said "pulling up to
         * 2.6 lb" through four weeks off would be describing a session nobody is running. */
        if (state.resting(System.currentTimeMillis()))
            return "resting until " + a.dayLabel(state.restUntilMs);
        if (nowKpa < wantKpa)
            return "capped this month  \u00b7  aiming " + Model.Fmt.p(wantKpa);
        // A cylinder MARKED for length pulls, girth or none (owner, 2026-09-30).
        if (!a.model.lengthPulls())
            return "expansion only  \u00b7  no length cylinder listed";
        if (state.inGirthFocus(System.currentTimeMillis())) return "girth focus";
        return "pulling " + Traction.boundLb(Scale.deliveredLoadLb(a.model, state.loadLb,
                                                                   System.currentTimeMillis()));
    }

    private void trainerLoadCard(Model.TrainerTrackState state) {
        long now = System.currentTimeMillis();
        // THE LENGTH CYLINDER AND ITS BORE (owner, 2026-09-30): the tube the person marked
        // for length pulls with or without a logged girth, and the load converts at its bore.
        Model.Cylinder tube = a.model.lengthCylinder();
        double bore = a.model.lengthBoreCm();

        /* LEVEL AND MONTH IN THE HEADER. The month is the RAW index, so it reads the same
         * way every gate below it does - "metrics open at month 3" and a header that counted
         * from one would put two incompatible month numbers on one card. */
        int monthNow = TrainerTab.monthIndexNow(a.model, now);
        LinearLayout g = Ui.cardGroup(a, a.body, "Traction load  \u00b7  "
            + TrainerTab.levelLabel(state.level) + "  \u00b7  month " + monthNow,
            null, Ui.ACCENT);

        if (tube == null) {
            Ui.note(a, g, "No cylinder is marked for length, so this runs as expansion only. "
                + "Mark one \u201cUsed for: Length\u201d in your rack.");
            return;
        }
        // The fit check, with a girth logged: it warns, it does not stop the pull.
        String fitWarn = a.model.lengthFitWarning();
        if (fitWarn.length() > 0) Ui.note(a, g, fitWarn);
        if (state.inGirthFocus(now)) {
            long daysLeft = Math.max(0,
                (state.focusBlockUntilMs - now + 86399999L) / 86400000L);
            Ui.noteInfo(a, g,
                "Girth focus for another " + daysLeft + " days \u2014 the traction blocks are paused and the expansion work is doubled.",
                "What girth focus does",
                "Girth focus for another " + daysLeft + " days \u2014 the traction "
                + "blocks are paused and the expansion work is doubled. It ends on its own; "
                + "nothing is asked of you.");
        }

        // THE PULL AS BUILT (0.10): the ladder's load with the length offset in pounds, within
        // the pull's hard limits - the figure the session commands (Scale#pullKpa).
        double delivered = Scale.deliveredLoadLb(a.model, state.loadLb, now);
        int kpa = Scale.pullKpa(a.model, now);
        Plan.Inputs probe = new Plan.Inputs();
        probe.track = Plan.TRACK_LENGTH;
        // A CAP, so the FLOORED count - see TrainerTab#monthsElapsed.
        probe.monthIndex = TrainerTab.monthIndexNow(a.model, now);
        probe.ceilKpa = a.model.ceilKpa;
        probe.boreCm = bore;
        double cap;

        // A SETTING, NOT A PUMP UNDER PRESSURE (polish TR-17): amber is kept for a pump that
        // may be under pressure, and this is a static load - a callout on SURFHI, the figure in
        // TEXT.
        Ui.chip(a, g, Traction.boundLb(delivered),
            Model.Fmt.p(kpa) + " in " + a.cylinderNameOf(tube), Ui.SURFHI, Ui.ACCENT);
        /* ONE LENGTH OFFSET, IN POUNDS (0.10, the owner): the pulls move with "My pressure"
         * as the expansion part does - the plan's load plus the offset at this girth. */
        double lenOff = Scale.appliedOffsetKpa(a.model, Plan.TRACK_LENGTH, monthNow);
        // "Plan’s own" with no offset; the offset only when it is not zero (polish TR-18).
        double dLb = lenOff == 0.0 ? 0.0 : Scale.offsetLb(lenOff, bore);
        Ui.kvRow(a, g, "My pressure", "the plan".equals(Scale.offsetText(lenOff)) ? PLAN_OWN
            : myPressureValue(lenOff) + "  ·  " + (dLb >= 0 ? "+" : "−")
              + Traction.settingLb(Math.abs(dLb)) + " on the plan's "
              + Traction.settingLb(state.loadLb), Ui.TEXT, null);

        // WHICH CAP BINDS. On a low device ceiling the device runs out before the plan's
        // twelve pounds does, and the card must say which one somebody is up against -
        // otherwise "why will it not go higher" has no answer on the screen that raises it.
        /* AGAINST THE PLAN'S OWN CAP, which in month one is four pounds rather than twelve.
         * Comparing the device against the flat twelve made the card blame "your device
         * ceiling" for a first-month limit the PLAN had set, and point somebody at a Settings
         * number that would not have moved it. */
        /* t10 device walk M6 - THE LIMIT, NOT TWELVE CALLED A CAP. The plan's usual twelve
         * pounds is passed by a climb (R-43), warned once before month 12; the limit is fifteen
         * (a new person's first month, four), or the pump's reach in this tube where that is
         * lower. One wording with the setup's rack (Scale#loadLimitLine). */
        boolean isNew = Scale.isNew(a.model);
        cap = Scale.loadLimitLb(isNew, probe.monthIndex, bore, a.model.ceilKpa);
        Ui.kvRow(a, g, "Limit", Traction.settingLb(cap) + "  \u00b7  "
            + Scale.loadLimitWhy(isNew, probe.monthIndex, bore, a.model.ceilKpa),
            Ui.TEXT, null);
        String usual = Scale.loadUsualNote(isNew, probe.monthIndex);
        if (usual.length() > 0) Ui.note(a, g, usual);
        /* THE BAR, to the limit that actually binds - not to a fixed twelve, so the picture
         * and the "Limit" row above it are telling the same story. */
        // (The bar runs to the pull itself, should one ever be past it, rather than off its end.)
        a.meterRow(g, delivered, Math.max(cap, delivered), Double.NaN, Double.NaN, Ui.ACCENT,
            "Load " + Traction.boundLb(delivered) + " against a limit of "
            + Traction.settingLb(cap));

        Ui.kvRow(a, g, "Strain sets", state.strainSets + " of "
            + Plan.LENGTH_STRAIN_SETS_MAX, Ui.TEXT, null);

        // THE SIGNAL, with its window named. A bare percentage with no window is a number
        // nobody can check.
        /* t10 R-40 / R-24 - THE READING THE PLAN ACTS ON (Meas#strainPct(Model, ...)): the newest
         * after-session pair linked to a length traction session that ran its strain block,
         * within the week - not an average of any pairs. The fatigue row is gone: option D
         * replaced the 21-day fatigue rung, so that figure no longer drives anything. */
        /* ...AS THE PLAN READS IT (LengthTrack#strainActedOn, the round 3 follow-up): a lone
         * reading from before the last week off is no longer the figure (N24), so it is not
         * shown as one - the row says no reading has come since the week off. */
        int lm = Model.Reading.METHOD_BPSSL;
        Double strain = LengthTrack.strainActedOn(a.model, now);
        boolean beforeOff = strain == null && Meas.strainPct(a.model, lm, now) != null;
        Ui.kvRow(a, g, "Strain", beforeOff ? "Not measured since your week off"
            : bandLine(strain, Plan.LENGTH_STRAIN_LO, Plan.LENGTH_STRAIN_HI,
                       Meas.STRAIN_WINDOW_DAYS),
            strain == null ? Ui.DIM : Ui.TEXT, null);
        if (strain != null)
            /* BODY VIOLET WHETHER OR NOT IT IS IN BAND, with the verdict carried by the
             * words on the row above. Red in this app means the pump is in a state nobody
             * has confirmed safe; spending it on a tape-measure reading that the plan
             * answers with "measure again" would be the dilution the colour rules forbid. */
            a.meterRow(g, strain.doubleValue(), a.STRAIN_AXIS_PCT,
                Plan.LENGTH_STRAIN_LO, Plan.LENGTH_STRAIN_HI, Look.BODY,
                "Strain " + Mint.trimLb(strain.doubleValue()) + " percent, against a window of "
                + Mint.trimLb(Plan.LENGTH_STRAIN_LO) + " to "
                + Mint.trimLb(Plan.LENGTH_STRAIN_HI));

        // ---- WHAT MOVES NEXT, and what it is waiting for -------------------------------
        Ui.kvRow(a, g, "Next change", nextLengthChange(state, monthNow), Ui.DIM, null);

        Ui.noteInfo(a, g, "How the load is worked out",
            "Traction load",
            "The load is what the tissue feels; the pressure is how it is made.\n\n"
            + "A cylinder under vacuum is a piston: the force on the tissue is the pressure "
            + "times the cross-section, and the cross-section is your length cylinder's "
            + "bore. So the same pressure pulls a different load in a different cylinder, and "
            + "the plan governs the load rather than the pressure.\n\n"
            + "The figure is an upper bound, not a measurement. It is what the vacuum can "
            + "pull in your length cylinder once the command has been rounded to the whole "
            + "kPa the pump takes and clamped to your ceiling. Friction at the seal and the tissue's own "
            + "compliance both subtract from it, neither is visible to the pump, and nothing "
            + "here measures what is left.\n\n"
            + "So use it to compare one of your own sessions with another, never yourself "
            + "with anybody else.\n\n"
            + "Sets rise to " + Plan.LENGTH_STRAIN_SETS_MAX + " before the load moves at all: "
            + "volume is the cheaper stimulus and it is spent first.");
    }

    /**
     * THE ONE LINE THAT SAYS WHAT HAPPENS NEXT - the thing a card full of current values
     * cannot say, and the question somebody actually has.
     *
     * It names the rung that is closest to firing rather than every rung: a list of
     * conditions is a specification, and this is a card.
     */
    private String nextLengthChange(Model.TrainerTrackState state, int monthNow) {
        long now = System.currentTimeMillis();
        if (state.inGirthFocus(now)) {
            long days = Math.max(0, (state.focusBlockUntilMs - now + 86399999L) / 86400000L);
            return "girth block ends in " + days + " days";
        }
        if (monthNow < Plan.LENGTH_METRICS_FROM_MONTH) {
            int weeks = TrainerTab.accumulatedTrainingWeeks(
                a.model, Plan.TRACK_LENGTH, a.model.trainerEnrolledAt, now);
            int nextSetWk = ((weeks / Plan.LENGTH_STRAIN_ADD_WEEKS) + 1)
                            * Plan.LENGTH_STRAIN_ADD_WEEKS;
            int nextLbWk = ((weeks / Plan.LENGTH_LOAD_STEP_WEEKS) + 1)
                           * Plan.LENGTH_LOAD_STEP_WEEKS;
            int soonest = Math.min(nextSetWk, nextLbWk);
            String what = nextLbWk <= nextSetWk
                ? "+" + Traction.settingLb(Plan.LENGTH_LOAD_STEP_LB) : "+1 strain set";
            return what + " after " + (soonest - weeks) + " more training "
                 + (soonest - weeks == 1 ? "week" : "weeks");
        }
        return "on your strain and fatigue readings";
    }

    private String bandLine(Double pct, double lo, double hi, int windowDays) {
        String window = " (" + windowDays + "d)";
        if (pct == null) return "Not measured in the last " + windowDays + " days";
        String band = Double.isNaN(hi)
            ? " \u00b7 floor " + Mint.trimLb(lo) + "%"
            : " \u00b7 window " + Mint.trimLb(lo) + "\u2013" + Mint.trimLb(hi) + "%";
        return Mint.trimLb(pct.doubleValue()) + "%" + window + band;
    }

    private void trainerPositionCard(int track, Model.TrainerTrackState state) {
        /* THE ENGINE'S MONTH, FLOORED - not the rounded one this card used to read.
         *
         * monthsBetween ROUNDS: at two months and sixteen days it answers three. Every gate
         * in Plan reads monthsElapsed, which floors, and the two disagree for the last
         * fifteen days of every month - so the "Months on plan" row ticked green and stayed
         * green for a fortnight while the engine went on holding, with nothing on the screen
         * able to explain the gap.
         *
         * The prose row keeps the rounded figure: "enrolled about three months ago" is what
         * a person means by it, and it decides nothing. */
        long nowMs = System.currentTimeMillis();
        int monthsSinceEnroll = TrainerTab.monthIndexNow(a.model, nowMs);
        int monthsApprox = TrainerTab.monthsBetween(a.model.trainerEnrolledAt, nowMs);
        LinearLayout g = Ui.cardGroup(a, a.body, "Where you are  \u00b7  "
            + TrainerTab.trackLabel(track), null);
        Ui.kvRow(a, g, "Level", TrainerTab.levelLabel(state.level), Ui.TEXT, null);
        Ui.kvRow(a, g, "Enrolled", Say.monthsAgo(monthsApprox), Ui.TEXT, null);
        Ui.kvRow(a, g, "Working pressure", a.model.commandedPair(state.pressureKpa),
                 Ui.TEXT, null);

        /* F11 - TRAINING VOLUME, BESIDE MONTHS AND PRESSURE. The card placed you by how
         * long you have been enrolled and what pressure you run at. Both are real, and
         * both can be true of someone who has trained twice this month. Net TUP is the
         * third fact, and it is the only one of the three that describes what was actually
         * delivered.
         *
         * IT IS AN OBSERVATION, NOT A GATE. Nothing is decided from this row: the gates
         * below are unchanged and still say exactly what they say. It is here because the
         * question "why has it not moved me up" is often answered by a number the app had
         * and never showed. */
        double volMin = typicalTrackedNetMin(track);
        // The row carries its own \u24d8 (polish TR-18): no line under it.
        Ui.kvInfoRow(a, g, "Training volume", volMin > 0 ? Say.fmtMin(volMin)
                + " min at pressure, typical session" : "No sessions yet",
            volMin > 0 ? Ui.TEXT : Ui.DIM, "Training volume",
            "Training volume is what you delivered, not what was prescribed.\n\n"
            + "The middle value of your recent tracked sessions\u2019 time at pressure \u2014 "
            + "time actually at or above target, fatigue stages excluded. The middle rather "
            + "than the average "
            + "so one very long or very short session does not move it. It decides nothing "
            + "on its own; the gates above are what move you. It is here because months on "
            + "plan and working pressure can both look right on someone who has trained "
            + "twice this month, and this is the figure that says so.");
        // WHAT THE NEXT STEP ACTUALLY NEEDS (audit E2). The engine already computes every one
        // of these to make its decision; none were ever shown, so "why has it not moved me up
        // yet" had no answer on the screen that raises the question. Each row states the
        // requirement and where you stand against it.
        //
        // LIME, NEVER GREEN, for a met requirement. Green in this app means telemetry-confirmed
        // safe and nothing else; a training gate confirms nothing about the pump. Lime is
        // progress, which is exactly what this is.
        /* T4 - THE GATES GET THEIR OWN CARD. They were rows at the bottom of the card that
         * describes where you are, which made them look like more of the same - and they are
         * the opposite thing: that card is your position, this one is the distance left. */
        g = Ui.cardGroup(a, a.body, "What moves you on", null, Ui.ACCENT);
        if (state.level == Plan.L1) {
            double[] nets = TrainerTab.recentTrackedNetsMin(a.model, track, 3);
            double netMin = Double.NaN;
            for (int i = 0; i < nets.length; i++)
                if (Double.isNaN(netMin) || nets[i] < netMin) netMin = nets[i];
            boolean netOk = !Double.isNaN(netMin) && netMin >= Plan.L1_GATE_NET_MIN;
            if (Double.isNaN(netMin))
                Ui.kvRow(a, g, "Time at pressure", "No sessions yet", Ui.DIM, null);
            else
                a.gateRow(g, "Time at pressure", netMin, Plan.L1_GATE_NET_MIN, "min",
                        netOk ? null : "Earned by training, so it has no date \u2014 it moves "
                                     + "when you do.");
            double hg = state.pressureKpa / Model.Fmt.KPA_PER_INHG;
            boolean pressOk = hg + 0.05 >= Plan.L1_GATE_PRESSURE_HG;
            a.gateRow(g, "Working pressure", hg, Plan.L1_GATE_PRESSURE_HG, "hg", null);
        } else if (state.level == Plan.L2 || state.level == Plan.L3) {
            int need = state.level == Plan.L2 ? Plan.L2_GATE_MONTH : Plan.L3_GATE_MONTH;
            boolean monthOk = monthsSinceEnroll >= need;
            a.gateRow(g, "Months on plan", monthsSinceEnroll, need, "months",
                    monthGateWhen(monthsSinceEnroll, need));
            /* D3 / G8 - THE L3 GATE IS A CHOICE, AND THE APP WAS ANNOUNCING IT AS AN EVENT.
             *
             * Past twelve months the guide offers two ways forward and this screen presented
             * one. Somebody standing at the gate could not see the decision they were
             * supposed to be making - and it is the only place in the plan where what
             * happens next is genuinely up to them rather than derived from their numbers.
             *
             * Shown as it APPROACHES, not only once it opens. A choice you find out about on
             * the day is not one you have had time to think about, and the two paths differ
             * in what they ask of the next several months rather than of the next session. */
            if (state.level == Plan.L3)
                Ui.noteInfo(a, g, monthOk
                        ? "You are at the gate. There are two ways on from here."
                        : "There are two ways on from this level \u2014 worth knowing early.",
                    "The two paths past Level 3",
                    "This is the one gate in the plan where the next step is a choice rather "
                    + "than a consequence. Both are legitimate and the plan does not prefer "
                    + "one; they ask for different things."
                    + "\n\n"
                    + "Carry on as you are. The same shape of session at the same "
                    + "level, indefinitely. Volume and pressure stay where they are, and you "
                    + "keep whatever you have built. Chosen by most people who are content "
                    + "with where they have got to and want to hold it rather than push."
                    + "\n\n"
                    + "Go on to Level 4. Longer sessions, and the fatigue block "
                    + "becomes a permanent part of them rather than an addition. It asks for "
                    + "materially more time each week, and it is the point past which "
                    + "recovery matters more than effort \u2014 a missed rest day costs more here "
                    + "than it did at Level 3."
                    + "\n\n"
                    + "What the app does either way: nothing, until you say. It will not "
                    + "advance you past this gate on its own, and it will keep writing Level "
                    + "3 routines for as long as you stay \u2014 staying is not a failure to "
                    + "advance, and nothing on this screen will start calling it one.");
        }
        if (track == Plan.TRACK_GIRTH_INTERVAL
                && (state.level == Plan.L1 || state.level == Plan.L2)) {
            Ui.kvRow(a, g, "Week (this level's table)", String.valueOf(state.weekIndex),
                Ui.TEXT, null);
        }

        /* G3 - SHORT EXPLAINERS, ATTACHED TO THE NUMBER. Each figure above was stated and
         * never explained, so the card answered "where am I" and refused "what is this".
         * One or two sentences each, in the app's existing noteInfo shape so the short
         * form sits on the screen and the long form is one tap away rather than four lines
         * of prose pushing the figures off it.
         *
         * THEIR OWN CARD, since the gates got theirs. Left where they were they read as part
         * of "What moves you on", which holds one kind of thing - a requirement with a
         * distance to it - and a definition is not that. */
        g = Ui.cardGroup(a, a.body, "What these words mean", null, Ui.DIM);
        Ui.noteInfo(a, g, "Level is how the plan treats you, not how long you have been at it.",
            "Level",
            "Level is what the plan currently asks of you: how much work, at what pressure, "
            + "and which gates have to be met before it asks for more. It moves when those "
            + "gates are met, which is why two people enrolled on the same day can sit at "
            + "different levels. Time on the plan is a separate figure, shown as Enrolled.");
        Ui.noteInfo(a, g, "Working pressure is what your sets are prescribed at, not a ceiling.",
            "Working pressure",
            "The pressure this track's work is written for. It is not a limit \u2014 the safety "
            + "ceiling in Settings is the limit, and it is enforced before every write. "
            + "Working pressure rises when a gate is met, never because a session went well.");
        Ui.noteInfo(a, g, "A \u201cnext step needs\u201d row is a gate, and every gate must pass.",
            "What the next step needs",
            "Each of these rows is a condition on moving up, shown with where you stand "
            + "against it. All of them count, not one or another: the step happens when all "
            + "of them read met, and a met one is marked with a tick. Nothing here is a score "
            + "and nothing here is a deadline \u2014 a gate that is not met simply has not been "
            + "met yet.");
    }

    /**
     * "This week", computed from the logs via {@link TrainerTab#weekStatus} — never a
     * stored counter (plan's own main-view line). A track with insufficient log data
     * says what it is waiting on rather than showing a blank or a wrong number (Plan's
     * own "absence = hold, state why" philosophy, reused here in the copy).
     */
    private void trainerWeekCard(int track, Model.TrainerTrackState state) {
        TrainerTab.WeekStatus ws = TrainerTab.weekStatus(
            a.model, track, state.level, state.weekIndex, System.currentTimeMillis());
        LinearLayout g = Ui.cardGroup(a, a.body, "This week", TrainerTab.trackLabel(track));

        String daysLine = ws.sessionCount == 0
            ? "Waiting on a logged session this week"
            : "Sessions: " + Say.weekProgress(ws.tally);
        Ui.note(a, g, daysLine);

        String trackedLine = ws.trackedCount == 0
            ? "Waiting on a tracked reading this week"
            : "Tracked " + ws.trackedCount + "/" + ws.trackedTarget;
        Ui.note(a, g, trackedLine);

        String netLine;
        // The delivered figure is a WEEK TOTAL (all sessions summed); the target is a
        // PER-SESSION figure (one session's sets × hold). Label both so the week total is
        // never read as a single session 3× over the target (fix round 3, I2).
        if (ws.sessionCount == 0) {
            netLine = "No time at pressure yet this week";
        } else if (ws.hasTargetTable) {
            netLine = "Time at pressure " + Say.fmtMin(ws.netDeliveredMin) + " min this week "
                + "in all · " + Say.fmtMin(ws.netTargetMin) + " min a session target";
        } else {
            netLine = "Time at pressure " + Say.fmtMin(ws.netDeliveredMin) + " min this week "
                + "in all · toward the " + Say.fmtMin(ws.netTargetMin) + " min a session "
                + "milestone";
        }
        Ui.note(a, g, netLine);

        /* G3 - the three figures this card is made of, each explained where it is stated. */
        Ui.noteInfo(a, g, "Time at pressure is time actually at pressure, not time the "
            + "session ran.",
            "Time at pressure",
            "Time at pressure counts only the seconds telemetry reported you at or above "
            + "the target, so a leak, a slow pull or a cuff that never sealed all reduce it "
            + "while the clock on the wall says the same thing. It also excludes any stage "
            + "the plan marked as fatigue work. It is the figure the gates are judged on, "
            + "because it is the only one that describes what your tissue actually got.");
        // It said only standardised readings count; TrainerTab#weekStatus counts every
        // reading that measures the track (Model.Reading#methodMeasuresGirth / Length), the
        // at-rest girth and length methods included.
        Ui.noteInfo(a, g, "A tracked reading is any reading this week that measures this "
            + "track, standardised or at rest.",
            "Tracked",
            "The count is of the readings you logged this week that measure this track \u2014 "
            + "girth for girth, length for length \u2014 taken under the standardisation hold or "
            + "at rest. Both kinds count here. They are only ever compared with readings "
            + "taken the same way: a reading at rest is never measured against one taken at "
            + "pressure.");
        Ui.noteInfo(a, g, Say.WEEK_RULE,
            "Sessions this week",
            "A week counts toward the plan with " + Plan.TRAINING_WEEK_FULL_SESSIONS
            + " full sessions on this track, or " + Plan.TRAINING_WEEK_MIN_DAYS
            + " days of shorter ones. A full session is one that delivered what its routine "
            + "asked that day; two short ones add up, two runs in one day are one session, and "
            + "one very long session does not stand in for a week: the plan is asking for "
            + "regular exposure. Two full sessions count once this track's training days for "
            + "the week are done, so a week with a third day still to come counts on that "
            + "third day.");
    }

    /**
     * Advances the girth-interval week-table position as training weeks accumulate, so the
     * guide's own scheduled set increases (L1/L2 tables) actually reach a user who keeps
     * logging — {@code state.weekIndex} was otherwise frozen at the onboarding week. The
     * advance is recomputed from the STABLE anchor {@code weekBaseIndex + trainingWeeksSince
     * (weekBaseMs)} (never a running total written back into weekIndex, which would re-add on
     * every render), so it is idempotent and monotonic; it never changes LEVEL, so it cannot
     * skip the level gate ({@link Mint#advancedWeekIndex} caps at the last table row). A track
     * upgrading from before the anchor existed (weekBaseMs 0) has its anchor INITIALIZED ONCE
     * here — at its current weekIndex and now, never re-read from the just-written weekIndex
     * each render (which would re-add the whole since-enrollment total every frame and race
     * the position to the table cap in a handful of tab visits). After that one-time init the
     * legacy track advances incrementally from a stable anchor exactly like a fresh onboarder,
     * with no retroactive jump. Persisted when the anchor is initialized or the position moves.
     *
     * TRADITIONAL GIRTH TOO (0.10): its holds grow by its week at the level
     * (Plan#traditionalSets), counted exactly as the interval table's position is - qualifying
     * training weeks on its own track since the anchor, less the repeated weeks. A traditional
     * track saved before that starts its level's growth once, from the first week now
     * (Mint#startWeekGrowth).
     */
    private void advanceWeekIndexes(long now) {
        int style = a.model.trainerGirthStyle;
        if (style != Plan.TRACK_GIRTH_INTERVAL && style != Plan.TRACK_GIRTH_TRADITIONAL) return;
        Model.TrainerTrackState g = a.model.trainerGirth;
        boolean init = Mint.startWeekGrowth(style, g, now);
        // One-time anchor init for a pre-anchor (legacy) track - core's (Mint#anchorWeek),
        // which never moves an anchor already dated (parity run 3, OPEN-7).
        if (Mint.anchorWeek(g, now)) init = true;
        // JUDGED BEFORE THE ADVANCE IS COMPUTED, so a repeat earned by last week is already
        // in weekRepeats when the subtraction below reads it - otherwise the position would
        // move on this render and step back on the next.
        applyMissPolicy(now);
        int weeks = TrainerTab.accumulatedTrainingWeeks(a.model, style, g.weekBaseMs, now);
        /* A REPEATED WEEK IS SUBTRACTED FROM THE ADVANCE, never rewound from the position.
         *
         * `weeks` is recomputed from the anchor on every render, so this subtraction is
         * idempotent: apply it a thousand times and the answer does not move. Decrementing
         * weekIndex instead would apply once PER RENDER, and somebody would watch their week
         * walk backwards. Floored at zero so a large repeat count cannot push the advance
         * negative and read as a week before the anchor.
         */
        weeks = Math.max(0, weeks - g.weekRepeats);
        // (From the track's own anchor - and on past its level's last hold while a traditional
        // build-up still needs the weeks: Mint#buildUpTopWeek.)
        int eff = Mint.advanceFromAnchor(style, g, weeks);
        if (eff > g.weekIndex || init) {
            g.weekIndex = eff;
            Store.save(a, a.model);
        }
    }

    /**
     * WHAT THE MISSED SESSIONS ADD UP TO, applied once per week rather than per render.
     *
     * NEVER A CATCH-UP. Missed work is missed - the tissue did not do it and cannot do it
     * twice as fast next week - so the only answers are to carry on, to repeat the week that
     * did not happen, or, after a whole week gone, to re-enter one week behind. All three go
     * through the SAME weekRepeats the orange card uses, which is subtracted from a
     * recomputed advance and is therefore idempotent by construction.
     *
     * Guarded on the week having actually turned over: the policy is about a week that has
     * finished, and asking it mid-week would repeat a week for sessions still to come.
     */
    private void applyMissPolicy(long now) {
        // Both girth styles' weeks move the plan on (0.10: traditional's holds grow by them).
        int style = a.model.trainerGirthStyle;
        if (style != Plan.TRACK_GIRTH_INTERVAL && style != Plan.TRACK_GIRTH_TRADITIONAL) return;
        Model.TrainerTrackState g = a.model.trainerGirth;
        long weekStart = TrainerTab.mondayStartMs(now);
        long lastWeekStart = weekStart - 7L * 86400000L;
        if (g.missCheckedWeekMs >= lastWeekStart) return;      // already judged that week
        boolean firstEver = g.missCheckedWeekMs <= 0L;
        g.missCheckedWeekMs = lastWeekStart;
        /* AN UPGRADE DOES NOT JUDGE A WEEK THAT PREDATES THE RULE.
         *
         * The absent key reads as 0, and 0 is older than any real week - so without this the
         * very first check after an update would look at the week just gone and could add a
         * repeat for sessions somebody missed before the app had any opinion about it. The
         * first check therefore only RECORDS where it is; judging starts with the next week,
         * which is the first one lived under the rule. */
        if (firstEver) { Store.save(a, a.model); return; }

        /* THE SCHEDULE'S GIRTH DAYS, not all seven weekdays.
         *
         * Schedule#count counts every selected day whatever it runs, and the DEFAULT schedule
         * is all seven - chosen for a streak-migration reason, never as a prescription, and
         * trainer onboarding never touches it. So a compliant five-day L1 trainer produced
         * scheduled=7, trained=5, missed=2, and a weekRepeat EVERY WEEK. Since the advance
         * also grows by exactly one qualifying week, the difference was constant: the week
         * index never moved again, for anybody, and the plan silently stopped progressing. */
        int scheduled = a.model.sched.countForTrack(style);
        if (scheduled <= 0) { Store.save(a, a.model); return; }

        /* AND TRAINED DAYS ON THE SAME TRACK, counted the way every other rule in this app
         * counts them. It used to count session ROWS with no track filter and no day
         * de-duplication, then subtract that from a count of DAYS - two units in one
         * subtraction. A split prescription files two rows for one day (so twice the credit),
         * and a length session counted against a girth day. */
        int trained = TrainerTab.trainedDaysOnTrack(a.model, style, lastWeekStart, weekStart);

        /* A WEEK THE PLAN ITSELF ASKED YOU TO REST IS NOT A MISSED WEEK.
         *
         * A deload is prescribed rest and a safety flag is a prescribed stop; charging a
         * repeat for either would have the plan penalise its own instruction, and the deload
         * one is worse than it sounds - the deload is the thing that ENDS the accumulation
         * that made it due, so the charge lands every time somebody does as they are told.
         * The deload window runs from the tap to {@link Deload#endMs} - a rolling seven days
         * for a tap deload, or whatever length a REPORTED one says - so it is forgiven
         * whenever it TOUCHES the judged week at all: a partial overlap still means the plan
         * told somebody to ease off inside it, and prorating a rest is not a thing this app
         * does. */
        long deloadFrom = a.model.trainerLastDeloadMs;
        boolean deloadTouched = deloadFrom > 0L
            && deloadFrom < weekStart && Deload.endMs(a.model) > lastWeekStart;
        boolean flagged = a.model.trainerState == Model.TRAINER_STATE_SAFETY_FLAG;
        /* S14 - NOR IS A WEEK THE GIRTH TRACK WAS PAUSED FOR A GIRTH-FOCUS BLOCK: the length
         * session's doubled expansion was the girth work of that week, by the plan's own
         * instruction (SameDay#girthPaused). Forgiven whenever the block touches it, as a
         * deload is. */
        Model.TrainerTrackState len = a.model.trainerLength;
        boolean pausedTouched = a.model.trainerLengthOn && SameDay.pauseTouchesWeek(
            len.focusBlockStartMs(), len.focusBlockUntilMs, lastWeekStart, weekStart);
        // t10 R-23 - nor one the girth offer's length focus rested (a dated girth rest), from
        // its start to its end - ended early by "Come back to girth now" or not (O7).
        if (TrainerTab.girthRestTouchesWeek(a.model, lastWeekStart, weekStart))
            pausedTouched = true;
        // t10-K (B1) - nor a week the month-12 break touched: both tracks rested by the plan.
        if (MonthBreak.touchesWeek(a.model, lastWeekStart, weekStart)) pausedTouched = true;
        if (deloadTouched || flagged || pausedTouched) { Store.save(a, a.model); return; }

        int missed = Math.max(0, scheduled - trained);
        // A WHOLE WEEK GONE is no trained day at all, which is a break rather than a hard
        // week - and coming back where you left off is how people get hurt on the first
        // session back.
        int policy = Plan.missPolicy(missed, trained == 0 ? 1 : 0);
        if (policy == Plan.MISS_REPEAT_WEEK) {
            Deload.recordCharge(g, lastWeekStart, 1);
        } else if (policy == Plan.MISS_STEP_BACK_WEEK) {
            // One week behind: the same arithmetic a repeat uses, twice - the advance loses
            // the week that did not happen AND the one before it, which is what re-entering
            // a week back means. Recorded as one charge of two, so a deload reported
            // afterwards gives back exactly what this took.
            Deload.recordCharge(g, lastWeekStart, 2);
        }
        Store.save(a, a.model);
    }

    /**
     * WHAT THE BLOCK ACHIEVED, said once when it ends.
     *
     * A block that ends silently is one nobody learns anything from - the whole reason to run
     * one is to find out whether the tissue filled. Scored against the spec's fixed 6-8 %,
     * from the girth readings taken while it ran.
     */
    private void focusResultCard(Model.TrainerTrackState state, long now) {
        if (!state.focusBlockJustEnded(now)) return;
        long start = state.focusBlockStartMs();
        int days = (int) Math.max(1, (now - start) / 86400000L);
        /* SCORED ON GIRTH, which is what the block is for. prePostPct measures a LENGTH
         * percentage; handed a girth method every reading's `len` is zero, every pair was
         * skipped, and the answer was always null - so the card told somebody who had
         * logged eight weeks of girth readings that none had been logged. */
        Double got = Meas.prePostPctGirth(
            Meas.windowFor(a.model.measLog, days, now), Model.Reading.METHOD_MSEG);

        LinearLayout g = Ui.cardGroup(a, a.body, "Your girth block has ended", null, Ui.ACCENT);
        if (got == null) {
            Ui.noteInfo(a, g,
                "The " + Plan.GIRTH_FOCUS_WEEKS + " weeks are up and the traction work is back.",
                "Why there is no score",
                "The " + Plan.GIRTH_FOCUS_WEEKS + " weeks are up and the "
                + "traction work is back. No girth readings were logged during the block, so "
                + "there is nothing to score it against \u2014 which is not a failure, just a "
                + "gap in the record.");
        } else {
            boolean met = Plan.focusBlockScored(got.doubleValue());
            Ui.noteInfo(a, g,
                "The " + Plan.GIRTH_FOCUS_WEEKS + " weeks are up and the traction work is back. Your girth sessions expanded by " + Mint.trimLb(got.doubleValue()) + "% over the block.",
                "How the block scored",
                "The " + Plan.GIRTH_FOCUS_WEEKS + " weeks are up and the "
                + "traction work is back. Your girth sessions expanded by "
                + Mint.trimLb(got.doubleValue()) + "% over the block \u2014 "
                + (met ? "inside the " + Mint.trimLb(Plan.GIRTH_FOCUS_YIELD_LO) + "\u2013"
                         + Mint.trimLb(Plan.GIRTH_FOCUS_YIELD_HI) + "% the block was aiming "
                         + "for."
                       : "under the " + Mint.trimLb(Plan.GIRTH_FOCUS_YIELD_LO) + "% the block "
                         + "was aiming for."));
        }
        Button b = Ui.flat(a, g, "Got it");
        b.setOnClickListener(new FocusResultSeenTap());
    }

    /** Week B (2026-10-03): one line, once, for a person set up before the week count changed
     *  who has already answered the upgrade card (which carries the same line). */
    private void weeksBNotice() {
        if (!a.model.weeksBNoticeDue()) return;
        LinearLayout g = Ui.cardGroup(a, a.body, "How weeks count", null, Ui.ACCENT);
        Ui.note(a, g, PlanCards.WEEKS_NOW_COUNT_PLAN);
        Button b = Ui.flat(a, g, "Got it");
        b.setContentDescription("Got it. Hides this note.");
        b.setOnClickListener(new WeeksBSeenTap());
    }

    private final class WeeksBSeenTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.weeksBSeen = true;
            Store.save(a, a.model);
            showTrainer();
        }
    }

    private final class FocusResultSeenTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.trainerLength.focusBlockSeenMs = a.model.trainerLength.focusBlockUntilMs;
            Store.save(a, a.model);
            showTrainer();
        }
    }

    /**
     * THE LENGTH TRACK IS RESTING, AND THIS IS THE ONLY THING THAT SAYS SO.
     *
     * Everything else about a rest is an absence - no row, nothing on Today, a lane caption
     * an inch high. An absence is indistinguishable from a bug, and the one question
     * somebody has four weeks after taking a break is when it ends. So: the date, what it
     * does not touch, and a way to end it early that is a tap rather than an archaeology
     * expedition through Settings.
     */
    private void lengthRestingCard() {
        if (!a.model.trainerLengthOn) return;
        long now = System.currentTimeMillis();
        Model.TrainerTrackState st = a.model.trainerLength;
        if (!st.resting(now)) return;
        long days = Math.max(0, (st.restUntilMs - now + 86399999L) / 86400000L);
        LinearLayout g = Ui.cardGroup(a, a.body, "Length is resting", null);
        Ui.note(a, g, "Until " + a.dayLabel(st.restUntilMs) + " \u2014 " + days
            + (days == 1 ? " day" : " days") + " to go. Your girth work is untouched and "
            + "runs as it always did.");
        Ui.note(a, g, "Nothing resumes by itself: on that date the length track starts "
            + "offering again, and resting longer is simply not running it.");
        Button back = Ui.secondary(a, g, "Come back to length now");
        back.setOnClickListener(new EndLengthRestTap());
    }

    /**
     * S14 - THE GIRTH TRACK IS PAUSED FOR THE BLOCK, and says so where the length rest says
     * it rests. The guidance's girth focus is a switch, not a second session: the length
     * session doubles its expansion, and that is the girth work
     * of those weeks. Today does not offer girth, the week is not a missed one, and a girth
     * session started anyway still runs and still counts.
     */
    private void girthPausedCard() {
        long now = System.currentTimeMillis();
        if (!TrainerTab.girthPausedNow(a.model, now)) return;
        if (MonthBreak.on(a.model, now)) return;      // t10-K: the break's own card says it
        /* t10 R-23 - OR FOR THE GIRTH OFFER'S LENGTH FOCUS: a dated girth rest, said with its
         * date and a way back, as the length rest is. */
        Model.TrainerTrackState gs = a.model.trainerGirth;
        if (gs.resting(now)) {
            long left = Math.max(0, (gs.restUntilMs - now + 86399999L) / 86400000L);
            // Polish TR-24: its own title (two cards were both "Girth is paused"), and the way
            // back asks first, as length's does.
            LinearLayout r = Ui.cardGroup(a, a.body, "Girth is paused for length focus", null);
            Ui.note(a, r, "For " + PlanCards.LENGTH_FOCUS + ", until "
                + a.dayLabel(gs.restUntilMs) + " — " + left
                + (left == 1 ? " day" : " days") + " to go.");
            Ui.noteInfo(a, r, "Length runs as planned.", "Girth is paused for length focus",
                "Length runs as planned. These weeks are not counted as missed.");
            Button back = Ui.secondary(a, r, "Come back to girth now");
            back.setOnClickListener(new AskEndGirthRestTap());
            return;
        }
        Model.TrainerTrackState st = a.model.trainerLength;
        long days = Math.max(0, (st.focusBlockUntilMs - now + 86399999L) / 86400000L);
        LinearLayout g = Ui.cardGroup(a, a.body, "Girth is paused", null);
        Ui.note(a, g, "For the girth-focus block, until " + a.dayLabel(st.focusBlockUntilMs)
            + " \u2014 " + days + (days == 1 ? " day" : " days") + " to go.");
        Ui.noteInfo(a, g,
            "The length session's doubled expansion is the girth work of these weeks.",
            "Why girth is paused",
            "The length session's doubled expansion is the girth work of these weeks, so "
            + "Today does not offer a girth session beside it. Nothing is lost: these weeks "
            + "are not counted as missed, and the girth track picks up where it is when the "
            + "block ends. A girth session you start anyway still runs and counts.");
    }

    /**
     * A TRAINING DAY OF NINETY MINUTES OR MORE, SAID WHILE IT CAN STILL BE PLANNED AROUND (the
     * owner's decision, 2026-09-30). The day is every routine the plan runs on it - girth,
     * length and the feeders, warm-ups and rests included (DayLength). Where that day runs
     * both tracks, one tap moves the week to girth and length on alternate days, the shape the
     * setup already offers. Nothing changes without the tap. The advisory's own off switch
     * (Model#dayBudgetAdvisory) turns this off too: it is the same ninety minutes.
     */
    private void dayLengthCard() {
        long now = System.currentTimeMillis();
        if (!DayLength.noticeDue(a.model, now)) return;
        int w = DayLength.longestWeekday(a.model, now);
        long sec = DayLength.longestDaySec(a.model, now);
        boolean every = DayLength.everyDayAsLong(a.model, sec, now);
        boolean offer = DayLength.alternateOffered(a.model, now);
        LinearLayout g = Ui.cardGroup(a, a.body, "A long training day", null, Ui.ACCENT);
        TextView head = new TextView(a);
        head.setText(DayLength.headline(w, every, sec));
        head.setTextColor(Ui.TEXT);
        head.setTextSize(Look.SP_HEADING);
        head.setPadding(0, Ui.dp(a, Look.S1), 0, Ui.dp(a, Look.S1));
        g.addView(head);
        Ui.note(a, g, DayLength.partsLine(DayLength.parts(a.model, a.model.sched.planOn(w), now)));
        Ui.note(a, g, offer ? DayLength.WHY_BOTH : DayLength.WHY_ONE);
        if (!offer) return;
        Button sw = Ui.big(a, g, DayLength.SWITCH_LABEL, Ui.ACCENT);
        sw.setContentDescription(DayLength.SWITCH_LABEL + ". " + DayLength.switchNote(a.model));
        sw.setOnClickListener(new SwitchAlternateTap());
        Ui.note(a, g, DayLength.switchNote(a.model));
    }

    /** The one tap that changes the week: girth and length on alternate days, led by the
     *  person's own order for a day of both. Saved as every schedule edit is (schedSaved:
     *  clamp, save, reminders), and said. */
    /** The 90-minute card's one tap: the week to alternate days, saved as every schedule edit
     *  is, and said with an Undo that puts the week back exactly as it was (0.10). */
    private final class SwitchAlternateTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            Schedule was = a.model.sched.copy();
            DayLength.switchToAlternate(a.model);
            a.schedSaved();
            Schedule wrote = a.model.sched.copy();
            showTrainer();
            // After the redraw, so the bar sits over the page it speaks for.
            Ui.snack(a, a.rootFrame, DayLength.switchedLine(a.model), Ui.UNDO,
                new UndoAlternateTap(was, wrote), Snack.HOLD_MS_ACTIONABLE);
        }
    }

    /** UNDO on the switch's message: the week as it was before the tap - days, time, shape and
     *  lead - while the schedule is still the one the switch wrote (DayLength#undoAlternate). */
    private final class UndoAlternateTap implements View.OnClickListener {
        private final Schedule was, wrote;
        UndoAlternateTap(Schedule was, Schedule wrote) { this.was = was; this.wrote = wrote; }
        @Override public void onClick(View v) {
            if (!DayLength.undoAlternate(a.model, was, wrote)) {
                Ui.say(a, DayLength.UNDO_STALE, true);
                return;
            }
            a.schedSaved();
            Ui.say(a, DayLength.UNDONE, false);
            showTrainer();
        }
    }

    /** Ends the girth offer's length focus early (t10 R-23, O7): the dated girth rest ends now
     *  (TrainerTab#endGirthRest - its start and end kept, so the weeks it touched are never
     *  charged as missed) and girth is offered again from where it is. */
    private final class EndGirthRestTap implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            TrainerTab.endGirthRest(a.model, System.currentTimeMillis());
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, "Girth is back");
            showTrainer();
        }
    }

    /** "Come back to girth now" asks first, as "Come back to length now" does (TR-24). */
    private final class AskEndGirthRestTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Come back to girth now?")
                .setMessage("The length-focus weeks end today.")
                .setPositiveButton("Come back", new EndGirthRestTap())
                .setNegativeButton("Keep length focus", null)
                .show());
        }
    }

    private final class EndLengthRestTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Come back to length?")
                .setMessage("The rest ends now and the length track starts offering again "
                    + "from where it is. Nothing about your girth work changes.")
                .setPositiveButton("Come back", new EndLengthRestConfirm())
                .setNegativeButton("Keep resting", null)
                .show());
        }
    }

    private final class EndLengthRestConfirm implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            a.model.trainerLength.restUntilMs = 0L;
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, "Length is back");
            showTrainer();
        }
    }

    /**
     * The girth/length suggestion card. Renders one of four faces, then records the decision
     * into history (deduped): a ceiling-deadlock message (no mint); a deload prompt (consumes
     * the deload, no routine mint); the quiet "no change" line when the prescription is
     * unchanged and a mint is live; or a Save-routine offer for a changed prescription (or the
     * very first routine, when nothing is minted yet).
     */
    private void trainerSuggestionCard(int track, Model.TrainerTrackState state) {
        long now = System.currentTimeMillis();
        SessionActivity.TrainerEval e = a.evalTrack(track, state, now);
        recordDecisionIfNew(track, e.decision, now);

        /* THE READINESS LINE LIVES IN THIS CARD'S SUBTITLE, for the girth track only.
         *
         * It was a grey note ABOVE the card, which reads as a caption for the screen rather
         * than as part of the instruction - so the merge was nominal. How long you have
         * rested is a qualifier on what to run, and it belongs in the same breath as it. */
        /* THE CARD IS NAMED FOR ITS TRACK, not for the week. Three cards titled "This
         * week" with the track relegated to a subtitle is three headings that do not
         * distinguish anything - the screenshots had two of them one above the other. The
         * track is what tells them apart, so the track is the title. */
        String sub = "This week";
        if (track == a.model.trainerGirthStyle) {
            String rest = Say.rested(TrainerTab.hoursSinceLastSession(
                    a.model.sessLog.all, System.currentTimeMillis()));
            if (rest.length() > 0) sub = sub + "  \u00b7  " + rest;
        }
        /* THE CARD DOES NOT REPEAT THE ROW ABOVE IT. Expanding "Girth - interval" produced
         * a card headed "Girth - interval" one line under the row that named it: the same
         * words twice with nothing in between. The row is the heading; the card says what
         * it is for. */
        LinearLayout g = openRowSection(sub, null);

        if (e.decision.action == Plan.ACTION_CEILING_DEADLOCK) {
            double nextStep = state.pressureKpa + Plan.STEP_HG_KPA;
            Ui.note(a, g, "Your device ceiling (" + Model.Fmt.p(a.model.ceilKpa)
                + ") is below the plan's next step (" + Model.Fmt.p(nextStep)
                + "). Raise it in Settings to progress, or hold here — the plan won't touch "
                + "your ceiling.");
            a.tagNote(g, e.decision.tag);
            return;
        }

        /* ---- THE LENGTH LADDER'S THREE ANSWERS ------------------------------------- *
         *  Each is a PROPOSAL with a button, like every other decision this card renders:
         *  nothing below changes anything until the user says so, and every one of them
         *  offers a way to decline that does not mean "ask me again tomorrow".            */

        // RAISE THE LOAD. Half a pound, only ever after the sets are spent, and the figure
        // quoted is what the rounded command will actually DELIVER rather than what was
        // asked for - the two differ by up to a rounding step and only one of them is felt.
        if (e.decision.action == Plan.ACTION_RAISE_LOAD) {
            double want = e.decision.loadLb;
            // What the pull will be with the length offset on it, within its limits (0.10).
            double got = Scale.deliveredLoadLb(a.model, want, System.currentTimeMillis());
            Ui.note(a, g, Say.sentence(e.decision.reason));
            a.tagNote(g, e.decision.tag);
            // t10 R-43 (A9) - A STEP PAST 12 lb BEFORE MONTH 12 IS SAID, once: the person's
            // call, under the plan's 15 lb. Answered by the tap below (LengthTrack#acceptLoad
            // sets warned12), so the words are said for this step and not again. A callout
            // with an amber \u26a0 (polish NEW-13), so it is not lost under the button.
            if (track == Plan.TRACK_LENGTH && LengthTrack.past12Due(a.model, want,
                    System.currentTimeMillis()))
                redLine(g, Plan.lengthPast12Words());
            // G2 - a climb past the usual top moves the length pressure too, and the pull
            // follows it: the button names both.
            double climbKpa = e.decision.pressureKpa;
            // Polish TR-27: "Raise to 12.5 lb" - the bare figure (it read "Go to up to").
            String label = Double.isNaN(climbKpa) ? "Raise to " + Traction.settingLb(got)
                : "Go to " + Model.Fmt.p(Math.round(climbKpa + Scale.appliedOffsetKpa(a.model,
                    track, TrainerTab.monthIndexNow(a.model, System.currentTimeMillis()))))
                  + " (" + Traction.boundLb(got) + ")";
            Button b = Ui.big(a, g, label, Ui.ACCENT);
            b.setOnClickListener(new RaiseLoadTap(track, want, climbKpa, e.decision.rule));
            Ui.secondary(a, g, PlanCards.NOT_NOW).setOnClickListener(new DismissDecisionTap(track));
            return;
        }

        // ADD A STRAIN SET. Volume before load, so this is the rung that fires first and
        // the one most people will ever see.
        if (e.decision.action == Plan.ACTION_ADD_VOLUME && track == Plan.TRACK_LENGTH) {
            int next = Math.min(Plan.LENGTH_STRAIN_SETS_MAX,
                                state.strainSets + Math.max(1, e.decision.setsDelta));
            Ui.note(a, g, Say.sentence(e.decision.reason));
            a.tagNote(g, e.decision.tag);
            Button b = Ui.big(a, g, "Go to " + next + " strain sets", Ui.ACCENT);
            b.setOnClickListener(new AddStrainSetTap(track, next, e.decision.rule));
            Ui.secondary(a, g, PlanCards.NOT_NOW).setOnClickListener(new DismissDecisionTap(track));
            return;
        }

        // RE-MEASURE BEFORE CUTTING. A high reading is the one signal the app will not act
        // on by itself: a single mis-measure looks exactly like it, and acting would walk
        // the load down on its own. So the first answer is to measure again; only a CONFIRMED
        // high - a second reading over the window (t10 R-40, C10) - offers the lower load,
        // never under 5 lb (A1), and the reduction is then the user's own answer rather than
        // the app's. The words are the decision's own (Plan#LENGTH_HIGH_WORDS ...).
        if (e.decision.action == Plan.ACTION_REMEASURE) {
            boolean cut = Plan.LENGTH_CUT_RULE.equals(e.decision.rule)
                && !Double.isNaN(e.decision.loadLb);
            String say = e.decision.reason;
            if (cut && state.cutsInRow >= Plan.LENGTH_CUTS_CHECK)
                say = say + " " + Plan.LENGTH_CUTS_CHECK_WORDS;
            // Polish TR-30: what the 6% is, behind the \u24d8; the yes-answer a centred
            // secondary under "Measure again"; and a way out, as every other card has.
            Ui.noteInfo(a, g, Say.sentence(say), "What the "
                + Mint.trimLb(Plan.LENGTH_STRAIN_HI) + "% reading means",
                PlanCards.strainWindowWords());
            a.tagNote(g, e.decision.tag);
            Button b = Ui.big(a, g, "Measure again", Ui.ACCENT);
            // C-F1: the re-measure is filed with the day's length session and replaces the
            // reading it checks (Meas#linkRemeasure).
            b.setOnClickListener(a.new MeasureAgainTap());
            if (cut) {
                // R-43: the cut also lowers a climbed length pressure (the decision's kPa).
                Button lower = Ui.secondary(a, g, "Lower to "
                    + Traction.settingLb(e.decision.loadLb));
                lower.setOnClickListener(new RaiseLoadTap(track, e.decision.loadLb,
                    e.decision.pressureKpa, e.decision.rule));
            }
            Ui.secondary(a, g, PlanCards.NOT_NOW).setOnClickListener(new DismissDecisionTap(track));
            return;
        }

        // A GIRTH-FOCUS BLOCK. Temporary, with an end date, and it states the WHY in the
        // user's own numbers - a proposal that only said "focus on girth" would be asking
        // somebody to take the app's word for a diagnosis about their own body.
        if (e.decision.action == Plan.ACTION_GIRTH_FOCUS) {
            Ui.note(a, g, Say.sentence(divergenceLine() + " " + e.decision.reason));
            a.tagNote(g, e.decision.tag);
            Button b = Ui.big(a, g, PlanCards.girthBlockStart("Start"), Ui.ACCENT);
            b.setOnClickListener(new StartGirthFocusTap(track));
            Ui.secondary(a, g, PlanCards.NOT_NOW).setOnClickListener(new DismissDecisionTap(track));
            return;
        }

        /* t10 R-23 / R-40 D3 - THE OFFER OF A BREAK. An added volume that did not help is not
         * added to again: the plan asks instead. Girth: a week off, or four weeks of length
         * focus (girth paused while length runs as planned). Length: a week off, or a girth
         * block. "Not now" answers it too - girth holds, the length ladder carries on. */
        if (PlanCards.isOfferBreak(e.decision)) {
            boolean length = track == Plan.TRACK_LENGTH;
            // Polish TR-25: the girth offer says its question as a heading - the card was
            // titled "This week" whatever it asked.
            if (!length) cardHeading(g, PlanCards.offerTitle(a.model.trainerLengthOn));
            Ui.note(a, g, Say.sentence(e.decision.reason));
            a.tagNote(g, e.decision.tag);
            Button off = Ui.big(a, g, PlanCards.TAKE_WEEK_OFF, Ui.ACCENT);
            off.setOnClickListener(new OfferBreakTap(track, OFFER_WEEK_OFF));
            // Length focus only means something with the length track on to run.
            if (length || a.model.trainerLengthOn) {
                Button other = Ui.secondary(a, g, length ? PlanCards.GIRTH_BLOCK
                                                         : PlanCards.LENGTH_FOCUS);
                other.setOnClickListener(new OfferBreakTap(track, OFFER_OTHER));
            }
            Ui.secondary(a, g, PlanCards.NOT_NOW)
              .setOnClickListener(new OfferBreakTap(track, OFFER_NOT_NOW));
            return;
        }

        if (e.decision.action == Plan.ACTION_DELOAD) {
            String dr = Say.sentence(e.decision.reason);
            int cut = PlanCards.isLengthFell(e.decision) ? dr.indexOf(". ") : -1;
            // D1: what happens stays on the face; the reason behind it, behind the \u24d8.
            if (cut > 0)
                Ui.noteInfo(a, g, dr.substring(cut + 2), "Why the week off comes early",
                    dr.substring(0, cut + 1));
            else Ui.note(a, g, dr);
            a.tagNote(g, e.decision.tag);
            // Only offer to START a deload when one is due; during the deload week itself the
            // message stands alone (tapping again would needlessly re-anchor the cadence).
            // t10 REAL-1: the cadence deload asks when it starts - one plan-wide question, in
            // its own card above (deloadDueCard); this track's card only says why.
            if (Plan.isCadenceDeload(e.decision)) return;
            if (!a.inDeloadWeek(now)) {
                // t10 R-40 D1: a length reading that fell brings the week off forward, said so.
                boolean fell = PlanCards.isLengthFell(e.decision);
                Button b = Ui.big(a, g, fell ? PlanCards.START_WEEK_OFF : "Start deload week",
                    Ui.ACCENT);
                b.setOnClickListener(fell ? new FellWeekOffTap(track)
                                          : a.new StartDeloadTap(track));
                Ui.secondary(a, g, PlanCards.NOT_NOW)
                  .setOnClickListener(new DismissDecisionTap(track));
            }
            return;
        }

        /* LEVEL UP - a met gate: propose crossing to the next level (propose-confirm;
         * nothing changes until the user accepts).
         *
         * A7 - AT MONTH 12 BOTH ANSWERS ARE ON THE CARD. The guide gives that crossing two:
         * continue on girth, or take a four-to-six week break and re-enter lower. Only the
         * first is a level-up, so for a long time only the first had a button and the other
         * lived in a sentence - which is a fork with one door. A fork with one door is not a
         * fork, so the break now sets the pause and remembers the level to come back at,
         * rather than leaving somebody to find Steer the plan and work out the arithmetic. */
        if (e.decision.action == Plan.ACTION_LEVEL_UP) {
            int nextLevel = state.level + 1;
            // t10-K: after the month-12 break the card no longer names it.
            String lw = Say.sentence(MonthBreak.levelUpWords(a.model, track, nextLevel,
                                                             e.decision.reason));
            /* AT MONTH 12 THE CHOICES ARE THE BUTTONS (polish, info moves): the sentence that
             * lists them goes behind the \u24d8, its first clause stays. */
            int lc = firstClauseEnd(lw);
            if (nextLevel == Plan.L4 && lc > 0)
                Ui.noteInfo(a, g, Say.sentence(lw.substring(0, lc)),
                    track == Plan.TRACK_LENGTH ? "Month 12 \u00b7 length" : "Month 12", lw);
            else Ui.note(a, g, lw);
            a.tagNote(g, e.decision.tag);
            // "Move to Level 4", as the text says it (polish TR-29), not "Move to L4".
            Button b = Ui.big(a, g, "Move to Level " + nextLevel, Ui.ACCENT);
            b.setOnClickListener(new LevelUpTap(track, nextLevel));
            if (nextLevel == Plan.L4) {
                /* THE FORK HAS THREE ARMS, and the middle one was missing. The spec's
                 * month-12 crossing is "continue / girth focus / >=4 wk off"; only the length
                 * track's crossing offers all three, because a girth-focus block is a
                 * LENGTH-track mode - pausing the pulls while the expansion coda doubles -
                 * and means nothing on the girth track's own crossing. */
                if (track == Plan.TRACK_LENGTH) {
                    // No "\u25b8" on an action, and the article as the number is said (TR-26).
                    Button foc = Ui.secondary(a, g, PlanCards.girthBlockStart("Take"));
                    foc.setOnClickListener(new StartGirthFocusTap(track));
                }
                /* t10-K (B1): ONE BREAK, BOTH TRACKS, from either card - and once: after a
                 * break the month-12 card offers Level 4 (and length its girth block) only. */
                if (MonthBreak.armOffered(a.model)) {
                    Button brk = Ui.secondary(a, g, noGlyph(MonthBreak.ARM_LABEL));
                    brk.setOnClickListener(new BreakCards(a, this).new TakeTap());
                }
            }
            return;
        }

        // STEP BACK (RED safety flag / layoff / persistent under-delivery) — the plan is
        // stepping back, so never offer to save a FORWARD routine here (M2): show the hold
        // state only. The plan-wide status line above already names an active safety flag.
        if (e.decision.action == Plan.ACTION_STEP_BACK) {
            Ui.note(a, g, Say.sentence(e.decision.reason));
            a.tagNote(g, e.decision.tag);
            return;
        }

        /* R11-4 - GAINING, SO THE STEP WAITS: when the normal step would have been due the card
         * says until when, and offers it - "Step up now" gives the normal step (proposed and
         * confirmed as every step is), "Wait" keeps the slower pace. Asked once for each step
         * (GainBrake#clockKey); answered Wait, the card only says when. */
        GainBrake.Card brake = GainBrake.card(a.model, track, state, e.decision, now);
        if (brake != null) {
            Ui.note(a, g, brake.words);
            if (brake.offer) {
                a.tagNote(g, e.decision.tag);
                Button up = Ui.big(a, g, GainBrake.STEP_UP, Ui.ACCENT);
                up.setOnClickListener(new BrakeAnswerTap(track, brake.kind,
                                                         GainBrake.ANSWER_STEP_UP));
                Ui.secondary(a, g, GainBrake.WAIT).setOnClickListener(
                    new BrakeAnswerTap(track, brake.kind, GainBrake.ANSWER_WAIT));
                return;
            }
        }

        boolean exists = a.model.routine(state.lastMintId) != null;
        boolean already = Mint.alreadyMinted(state.lastMintSig, e.sig, exists);
        boolean isSuggestion = e.decision.action != Plan.ACTION_HOLD;

        if (already) {
            Model.Routine r = a.model.routine(state.lastMintId);
            // The routine as a card names it (polish TD-4): "Girth · Level 3".
            String nm = (r != null && r.name != null) ? Say.routineDisplayTitle(r)
                                                      : "your routine";
            // A PAUSE_VOLUME with no yield sets kept leaves the set count, so its signature is
            // unchanged — surface its reason here rather than silently swallowing the event.
            if (e.decision.action == Plan.ACTION_PAUSE_VOLUME) {
                Ui.note(a, g, Say.sentence(Say.sentence(e.decision.reason) + " Keep running "
                    + nm));
                a.tagNote(g, e.decision.tag);
            } else if (Plan.atVolumeTop(e.decision)) {
                // G4 - a volume step was due and the level's top stopped it: said, not hidden.
                Ui.note(a, g, Say.sentence(Say.sentence(e.decision.reason) + " Keep running "
                    + nm));
            } else if (holdSays(e.decision)) {
                // t10 - a hold with its own words (the girth time cap, R-27; the length cut's
                // week and the no-readings top, R-40 / R-47): said, not "No change".
                Ui.note(a, g, Say.sentence(e.decision.reason) + " Keep running " + nm
                    + " for now.");
                a.tagNote(g, e.decision.tag);
            } else {
                Ui.note(a, g, "No change — keep running " + nm + ".");
                String wait = waitingLine(track, state, now);
                if (wait != null) Ui.note(a, g, wait);
            }
            // t10 R-04: a saved routine the two-hour trim shortened says so.
            String trim = RxBuild.trimmedNote(r);
            if (trim.length() > 0) Ui.note(a, g, Say.sentence(trim));
            // A door, with the chevron icon (polish SYS-5).
            Ui.navCard(a, g, "Open routine", new OpenMintTap(state.lastMintId));
            return;
        }

        // A changed prescription, or the first routine for a track with nothing minted yet.
        // "Measuring lets the plan adjust to you" is the reason why, behind the \u24d8 (polish,
        // the no-readings card); the question stays on the face.
        // Say.sentence folds the doubled stop whySentence's own "." leaves on a reason that
        // already has one (polish TR-28).
        String why = Say.sentence(a.whySentence(track, state, e, isSuggestion));
        String measuring = "Measuring lets the plan adjust to you.";
        int mk = why.indexOf(" " + measuring.substring(0, measuring.length() - 1));
        if (mk > 0)
            Ui.noteInfo(a, g, why.substring(0, mk).trim(), track == Plan.TRACK_LENGTH
                ? "No length readings" : "No girth readings", measuring);
        else Ui.note(a, g, why);
        a.tagNote(g, e.decision.tag);
        Ui.note(a, g, TrainerOnboard.routineWords(
            a.rxLine(e.rx, e.decision == null ? 0 : e.decision.setsDelta)));
        // t10 R-01 / R-04 - the routine Save builds: its P2 warm-up, and the two-hour trim.
        String[] more = RxBuild.cardLines(a.model, e.rx);
        if (more[0].length() > 0) Ui.note(a, g, Say.sentence(more[0]));
        if (more[1].length() > 0) Ui.note(a, g, Say.sentence(more[1]));
        Button save = trainerSaveButton(g, "Save routine");
        /* ONE PRIMARY, THEN TWO LINKS ON ONE LINE. Save, Adjust and Not now were three
         * full-width controls stacked down the card - a form, when the card is asking one
         * question. Two of the three are rarely wanted, and a rarely-wanted control the same
         * size as the wanted one makes the wanted one harder to find.
         *
         * D2's rule is unchanged: adjusting is an ordinary answer to an offer, not an
         * advanced escape from one, so it stays visible rather than moving behind anything. */
        Button[] links = Ui.row(a, g,
            new String[]{ "Adjust first…", "Not now" },
            new View.OnClickListener[]{ new AdjustMintTap(track),
                                        new DismissDecisionTap(track) });
        if (links != null && links.length == 2) {
            links[0].setContentDescription("Change the set count or the working pressure "
                + "before saving this routine");
            links[1].setContentDescription("Dismiss this suggestion for now");
        }
        save.setOnClickListener(new SaveMintTap(track));
    }

    /** The feeder card (the guidance) — its own routine at 70–80% of the main pressure, re-offered
     *  whenever the main pressure changes (the one exception to mints-only-on-own-change). */
    /**
     * T2 - the explanations for the plan's own numbers, attached where each is printed
     * rather than gathered on a page nobody opens. Each says what the figure IS, what it is
     * measured against, and - where it matters - what it is NOT.
     */
    private void trainerFigureNote(LinearLayout g, int level) {
        Ui.noteInfo(a, g, "What these numbers mean.",
            "The level's own numbers", "The floor (" + Model.Fmt.p(Plan.floorKpa(level))
            + ") is the pressure a reading has to reach before a second of it counts as time "
            + "under pressure. It is a property of the level, not of a routine: two routines "
            + "at this level are scored against the same floor however deep each one pulls."
            + "\n\nThe time-at-pressure target (" + Say.fmtMin(Plan.netMilestoneMin(level))
            + " min) is how "
            + "much time at or above that floor a session is meant to deliver. Net excludes "
            + "the drops between holds, and it excludes the fatigue block, because neither is "
            + "work at the prescribed pressure \u2014 which is why net is always less than the "
            + "time a routine takes."
            + "\n\nA gate is the pair of conditions that has to hold for a level to advance, "
            + "and it is checked over a week rather than a session: enough training days, and "
            + "enough delivered net across them. One short session does not fail anything.");
    }

    /**
     * WHERE YOU ARE IN THE FEEDER DAY - the cadence, which was implemented nowhere.
     *
     * The pressure was right (three quarters of main) and the volume was right (5 x 2 min),
     * and "2x/day, 4-6 h apart" existed only as a subtitle. A person following it had no way
     * to know whether they had done one, when the next was allowed, or that it continues on
     * rest days - which is the one thing about the feeder that surprises people.
     *
     * A LINE, NOT A REMINDER. A second daily notification is a thing to offer rather than to
     * assume, and the position is what is actually useful: how many, and when the next may
     * be taken.
     *
     * COUNTED IN THE LOCAL DAY, from the feeder routine's own sessions - so a feeder run and
     * a main session are never confused for each other.
     */
    private void feederTodayLine(LinearLayout g, long now) {
        String fid = a.model.trainerFeederMintId;
        if (fid == null || fid.length() == 0 || a.model.routine(fid) == null) return;
        int today = PhotoCalendar.dayKey(now);
        int done = 0;
        long last = 0L;
        for (int i = 0; i < a.model.sessLog.all.size(); i++) {
            Model.Sess ss = a.model.sessLog.all.get(i);
            if (ss == null || ss.manual || !fid.equals(ss.routineId)) continue;
            if (TrainerTab.sessionDay(ss) != today) continue;
            done++;
            if (ss.ts > last) last = ss.ts;
        }
        /* S10 - THE GAP RUNS FROM THE MAIN SESSION TOO (the owner's decision of 2026-09-26):
         * four hours from the last feeder AND from the end of today's last girth or length
         * session (TrainerTab#feederFromMs). It used to count between feeders only, so the
         * first one was "due now" the moment the main work was filed. */
        long from = TrainerTab.feederFromMs(a.model, now);
        int st = from == Long.MAX_VALUE ? Plan.FEEDER_DONE
               : from > 0L ? Plan.FEEDER_TOO_SOON : Plan.FEEDER_DUE;
        String say;
        // S16: on a rest day, with the feeder set to training days only, nothing is due.
        boolean off = TrainerTab.feederOffToday(a.model, now);
        if (off && st != Plan.FEEDER_DONE) {
            say = done + " of 2 today · rest day, not due";
        } else if (st == Plan.FEEDER_DONE) {
            say = "2 of 2 today · done";
        } else if (st == Plan.FEEDER_TOO_SOON) {
            say = done + " of 2 today · next from " + a.timeLabel(from);
        } else {
            say = done + " of 2 today · due now";
        }
        TextView t = new TextView(a);
        t.setText(say);
        t.setTextColor(st == Plan.FEEDER_DONE || off ? Ui.DIM : Ui.ACCENT);
        t.setTextSize(Look.SP_BODY);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        g.addView(t);
        Ui.group(g, "Feeder: " + say + ".");
        // S10 - THE WAIT IS SHOWN, AND A TAP OVERRIDES IT: the same confirm START shows.
        if (st == Plan.FEEDER_TOO_SOON && !off) {
            // Polish TR-33 / NEW-16: one sentence with Today's, behind the \u24d8; and no
            // navigation chevron on a button that starts the pump.
            Ui.noteInfo(a, g, "Starting sooner is your call.", "When the feeder runs",
                FEEDER_SPACING);
            Button now2 = Ui.secondary(a, g, "Start the feeder now");
            now2.setOnClickListener(a.new StartRoutineTap(fid));
        }
    }

    /**
     * WHICH DAYS THE FEEDER RUNS ON. t10 R-09 (A6): girth days only, 4-6 h after the girth
     * session (Plan#FEEDER_DAYS_WORDS). The S16 "Feeder on rest days too" switch reaches no
     * build any more (TrainerTab#feederRestDaysOn reads false), so it is not shown; its saved
     * value is kept. The same line sits on the Shape screen, where it can be found once the
     * offer on this card has been saved and the card is gone.
     */
    private void feederDaysRow(LinearLayout g) {
        Ui.note(a, g, Plan.FEEDER_DAYS_WORDS + ".");
    }

    private void trainerFeederCard() {
        long now = System.currentTimeMillis();
        SessionActivity.TrainerEval e = a.evalFeeder(now);
        recordDecisionIfNew(Plan.TRACK_FEEDER, e.decision, now);

        LinearLayout g = openRowSection("Feeder", "10 min ×2/day");
        feederTodayLine(g, now);
        feederDaysRow(g);

        if (e.decision.action == Plan.ACTION_FEEDER_PAUSED) {
            Ui.note(a, g, Say.sentence(e.decision.reason + " — feeder paused"));
            a.tagNote(g, e.decision.tag);
            return;
        }
        if (e.decision.action != Plan.ACTION_FEEDER_SUGGEST) return;   // disabled (below L3)

        boolean exists = a.model.routine(a.model.trainerFeederMintId) != null;
        boolean already = Mint.alreadyMinted(a.model.trainerFeederMintSig, e.sig, exists);
        if (already) {
            Model.Routine r = a.model.routine(a.model.trainerFeederMintId);
            String nm = (r != null && r.name != null) ? Say.routineDisplayTitle(r)
                                                      : "your feeder routine";
            Ui.note(a, g, "No change — keep running " + nm + ".");
            Ui.navCard(a, g, "Open routine", new OpenMintTap(a.model.trainerFeederMintId));
            return;
        }
        // THE MAIN PRESSURE THE DAY RUNS AT, which on a reduced day is the one the feeder's
        // figure is three quarters of - printing the plan's own beside it would not add up.
        Ui.note(a, g, "Feeder pressure follows your main pressure (" + Model.Fmt.p(
            Deload.mainKpaOn(a.model, a.model.trainerGirth.pressureKpa, now)) + " → "
            + Model.Fmt.p(e.rx.pressureKpa) + ").");
        // T2 - THE 75% WAS A NUMBER WITH NO REASON BESIDE IT. Every other figure this app
        // shows can be interrogated where it appears; this one could not.
        Ui.noteInfo(a, g, "A feeder runs at three quarters of your working pressure.",
            "Why 75%", "A feeder session is extra volume between your real sessions, and its "
            + "job is to keep tissue under load without adding to the fatigue the main work "
            + "is already producing. Three quarters of the working pressure is the guidance's "
            + "own figure for that: deep enough to count as time under pressure, shallow "
            + "enough that two of them a day do not compete with the session they are meant "
            + "to support.\n\nIt follows your main pressure rather than being set on its own, "
            + "so raising one raises the other and the relationship between them cannot drift.");
        a.tagNote(g, e.decision.tag);
        Ui.note(a, g, TrainerOnboard.routineWords(
            a.rxLine(e.rx, e.decision == null ? 0 : e.decision.setsDelta)));
        Button save = trainerSaveButton(g, "Save routine");
        Ui.flat(a, g, "Not now").setOnClickListener(
            new DismissDecisionTap(Plan.TRACK_FEEDER));
        save.setOnClickListener(new SaveFeederTap());
    }

    /** The short chip for a traction session - the load, because that is what governs it,
     *  and the strain sets, because that is what moves first. Null when this prescription
     *  is not going to be a traction session. */
    private String tractionSummary(Mint.Rx rx) {
        if (!a.willPull(rx)) return null;
        Model.TrainerTrackState st = a.model.trainerLength;
        if (st.inGirthFocus(System.currentTimeMillis())) return "Girth focus \u00b7 expansion only";
        // "6 × 5 min up to 4.5 kg" (polish SYS-13).
        return Model.Fmt.shape(st.strainSets, Mint.TRACTION_STRAIN_HOLD_SEC) + " "
             + Traction.boundLb(
                   Scale.deliveredLoadLb(a.model, st.loadLb, System.currentTimeMillis()));
    }

    /** What a holding track is waiting on, stated plainly (meta-rule: every non-count is
     *  visible with its reason). Null when nothing is being awaited. */
    private String waitingLine(int track, Model.TrainerTrackState state, long now) {
        TrainerTab.WeekStatus ws = TrainerTab.weekStatus(a.model, track, state.level,
            state.weekIndex, now);
        if (!ws.weekQualifies) {
            return "Needs " + Plan.TRAINING_WEEK_FULL_SESSIONS + " full sessions (or "
                + Plan.TRAINING_WEEK_MIN_DAYS + " days) this week to advance \u2014 "
                + Say.weekProgress(ws.tally) + ".";
        }
        if (state.level >= Plan.L3 && ws.trackedCount == 0) {
            return "Waiting on tracked girth readings to read yield.";
        }
        return null;
    }

    /**
     * THE WARM-UP A PRESCRIBED ROUTINE OPENS WITH, or null where one would be wrong.
     *
     * A four-step ramp from half the working pressure up to it, four minutes in all - the
     * same shape the old standalone offer built, now written into the routine that needs it
     * rather than into whichever routine happened to be selected when a card was tapped.
     *
     * NOT FOR THE FEEDER. It already runs at 70-80% of the main pressure, twice a day, and
     * ramping into that would be warming up to a warm-up.
     *
     * NOT AT L1. The first level's working pressure is where somebody starts, so there is
     * nothing to be eased into, and half of an already-gentle pressure is below what the
     * pump will hold.
     */
    /**
     * HOW YOU RUN IT - the shape controls, on the Trainer's own plan settings.
     *
     * They sit together and under one heading because they are one idea: the plan owns the
     * numbers, you own the shape. Each row says what it does NOT change, because the first
     * question anybody sensible asks of a control on a training plan is whether using it is
     * cheating.
     *
     * Every change re-mints nothing on its own. The next prescription is written in the new
     * shape; the routines already in the library are left exactly as they are, which is the
     * same rule every other plan change follows.
     */
    private void rxShapeCards() {
        a.model.clampRxShape();
        /* NO HEADING OF ITS OWN ANY MORE. This opened with "How you run it" and a note, which
         * was right while it was a section part-way down the girth track's detail. It is
         * called from one place now - renderTrainerShape - and that screen is already headed
         * and already says what these are for, so a second title here is two names for one
         * room. The one sentence the old note had that the new one lacked moved up with it. */
        shapesCard();

        /* Q1 - THE MARKING SETTINGS SIT ABOVE THE WARM-UP because the warm-up is the first
         * thing they change, and because everything below this card is a preference while
         * this one is a statement about your body. */
        LinearLayout mk = Ui.cardGroup(a, a.body, "If you mark easily", null, Ui.TEXT);
        Ui.kvRow(a, mk, SetupText.MARKS_LABEL, a.model.marksEasily,
                 new ToggleMarksEasily());
        if (a.model.marksEasily) {
            Ui.kvRow(a, mk, "I\u2019m using the larger cylinder", a.model.bigCylinder,
                     a.new ToggleBigCylinder());
        }
        long shapeNow = System.currentTimeMillis();
        // WHAT IS IN FORCE, row and face alike (Say#taperRow, Say#taperMarkFace): once the taper
        // is spent, a cylinder's own 2 hg is what applies, and neither may say "full pressure"
        // or "not added" while it does.
        if (Deload.armed(a.model)) {
            Ui.kvRow(a, mk, Say.taperTitle(a.model, shapeNow), Say.taperRow(a.model, shapeNow),
                     Ui.TEXT, null);
        }
        /* WHAT IS ACTUALLY IN FORCE, which is a different question from whether the toggle is
         * on. The taper is armed for anybody who reports a deload, so a face line that read
         * the toggle alone told somebody with it off that nothing was reduced - printed
         * directly under a row saying today runs 4 hg under. A card cannot contradict its own
         * state row; the armed taper is the fact, so the armed taper leads. */
        String mkFace;
        if (Deload.armed(a.model)) {
            mkFace = Say.taperMarkFace(a.model, shapeNow);
        } else if (a.model.marksEasily) {
            mkFace = "The warm-up starts lower and slower. "
                   + "Coming back from a week off runs " + Say.hgUnder(Plan.returnTaperHg(0))
                   + " under, then " + Say.hgUnder(Plan.returnTaperHg(1)) + " under"
                   + (a.model.bigCylinder ? ", and the larger cylinder runs "
                      + Say.hgUnder(Plan.BIG_CYLINDER_HG) + " under" : "") + ".";
        } else {
            mkFace = "Off. Your prescription is written at the pressure the plan asked for.";
        }
        Ui.noteInfo(a, mk, mkFace,
            "If you mark easily",
            "Petechiae are the small red spots that come from pumping harder or colder than "
            + "the tissue is ready for. The guidance for anybody who gets them has four "
            + "parts, and two of them are already in every routine for everybody:\n\n"
            + "\u2022 A warm-up before the work \u2014 built, always on.\n"
            + "\u2022 The first cycle 3 hg under target \u2014 built, always on.\n\n"
            + "With this on, this app goes further than that, by its own rule: the warm-up "
            + "is the gentle one set below \u2014 lower and slower to start, climbing a little "
            + "each rep to your working pressure \u2014 in place of the prime and the eased "
            + "cycle.\n\n"
            + "None of that reduces the work the plan asked for. The other two do change your "
            + "prescribed pressure, so they are here:\n\n"
            + "\u2022 Back after a week or more off, or after a deload you reported: "
            + Say.hgUnder(Plan.returnTaperHg(0)) + " under for the full session on your first "
            + "training day back, " + Say.hgUnder(Plan.returnTaperHg(1)) + " under on the next, "
            + "then full pressure \u2014 and the pump pulls slower on those days. Every session "
            + "on one day shares that day's pressure.\n\n"
            + "\u2022 The larger cylinder: " + Say.hgUnder(Plan.BIG_CYLINDER_HG) + " under, for "
            + "as long as it is the one you are in. Your net counts from the same amount "
            + "lower, because a wider cylinder reaches the same tissue at less pressure "
            + "\u2014 if the prescription dropped and the line did not, every session in "
            + "that cylinder would score zero and no gate would ever open.\n\n"
            + "A reduced session is not scored against you. It asks for no net, and a "
            + "session that was not asked for net is left out of the delivery check rather "
            + "than counted as a failure. Before this, three of them in a row proposed "
            + "stepping you back a level for following the advice correctly.");

        // 0.10 - the gentle warm-up's own settings, under the answer that brings it in.
        gentleWarmCard();

        /* WHAT THESE REACH, said once at the top of the section.
         *
         * A feeder session is built by neither warmupStage nor retentionStage - both return
         * null for TRACK_FEEDER on their first line - and it takes its holds and its rests
         * from its own table. So every setting below governs the girth and length sessions
         * and none of them touches a feeder one. The screen cannot hide them per track,
         * because there is one set of these settings and the feeder is never the only track
         * being run; so it says so instead. */
        Ui.noteInfo(a, a.body, "These shape your girth and length sessions.",
            "What these shape",
            "These shape your girth and length sessions. A feeder session is "
            + "built to its own fixed shape and is not affected by anything here.");

        LinearLayout w = Ui.cardGroup(a, a.body, "Warm-up", null, Ui.ACCENT);
        /* t10 R-01 - THE PLAN'S SESSIONS WARM UP BY THE PLAN'S OWN RULE (P2): about five minutes
         * of short holds climbing to the work. Its shape is set by the plan - the Program's
         * warm-up picker can turn it off ("none"), and "I mark or bruise easily" swaps in the
         * gentle one. The old climb, steps and eased first cycle reach no trainer build any
         * more (RxBuild#warmupStage) and nothing else reads them, so they are not shown; their
         * saved values are kept. What is left here is a manual run's own warm-up
         * (RxBuild#manualWarmUp reads its length and the pressure it is held at). */
        if (a.model.gentleWarmFor(a.model.trainerGirthStyle)) {
            // 0.10: the gentle warm-up above is the one that runs; its shape is set there.
            Ui.note(a, w, "You mark or bruise easily, so the gentle warm-up above is the one "
                + "that runs. Its shape is set there.");
        } else {
            Ui.kvRow(a, w, "Plan sessions", "Set by the plan", Ui.DIM, null);
        }
        Ui.kvRow(a, w, "Warm up a manual run too", a.model.rxManualWarm,
                 new ToggleManualWarm());
        if (a.model.rxManualWarm) {
            Ui.stepperRow(a, w, "Manual run \u00b7 length",
                a.model.rxWarmMin == 0 ? "none" : a.model.rxWarmMin + " min",
                new BumpShape(a.SHAPE_WARM_MIN, -1), new BumpShape(a.SHAPE_WARM_MIN, +1),
                "warm-up length");
            if (a.model.rxWarmMin > 0)
                Ui.stepperRow(a, w, "Manual run \u00b7 held at",
                    Model.Fmt.p(a.model.rxPrimeKpa),
                    new BumpShape(a.SHAPE_PRIME, -1), new BumpShape(a.SHAPE_PRIME, +1),
                    "prime pressure");
        }
        Ui.noteInfo(a, w, "Plan sessions warm up with about 5 minutes of short holds that "
            + "climb to your pressure.",
            "How the warm-up is shaped",
            "Plan sessions \u2014 about 5 minutes of short holds from "
            + Model.Fmt.p(Plan.P2_START_KPA) + ", each a little "
            + "deeper than the last, growing from 30 s to 60 s, up to your pressure. A length "
            + "session's warm-up ends at 80% of its pull. When the second session of a day "
            + "starts within half an hour of the first, it has no warm-up.\n\nA manual run "
            + "\u2014 a flat hold at a low pressure for the length you set, when the switch "
            + "above is on.\n\nNeither counts as training volume. Warm-up stages are excluded "
            + "from your net by rule, so the plan still asks for exactly what it asked for.");

        LinearLayout h = Ui.cardGroup(a, a.body, "Retention hold", null, Ui.TEXT);
        Ui.kvRow(a, h, "Hold at the end", a.model.rxRetention, a.new ToggleRetention());
        if (a.model.rxRetention) {
            Ui.stepperRow(a, h, "Held at", Model.Fmt.p(a.model.rxRetentionKpa),
                new BumpShape(a.SHAPE_RET_KPA, -1), new BumpShape(a.SHAPE_RET_KPA, +1),
                "retention pressure");
            Ui.stepperRow(a, h, "For", a.model.rxRetentionMin + " min",
                new BumpShape(a.SHAPE_RET_MIN, -1), new BumpShape(a.SHAPE_RET_MIN, +1),
                "retention length");
        }
        Ui.noteInfo(a, h, "Held at about the pressure a natural erection sits at, "
            + "instead of going straight to nothing.",
            "The retention hold",
            "The cuff is held at roughly 3 to 5 inHg after the work, doing the job a "
            + "constriction ring would.\n\nIt does not count toward your net, and that is "
            + "deliberate rather than an oversight. Net is time at or above your level's "
            + "floor, and Level 1's floor is 5 inHg \u2014 the top of this band. Counted, a "
            + "ten-minute retention would add ten minutes of \u2018net at pressure\u2019 to a session "
            + "that did no more work, and the gate deciding when you move up would be "
            + "reading a number the plan never asked you to earn.");

        holdLengthsCard();

        LinearLayout rr = Ui.cardGroup(a, a.body, "Rests", null, Ui.DIM);
        /* t10 R-08 - TRADITIONAL RESTS ARE THE PLAN'S: 30 s at every level (Model#restSecFor),
         * the Rests picker still scaling them. The old stepper (rxRestSecTrad) reaches no build
         * any more, so it is a value row; its saved value is kept. */
        /* ONE ROW, ITS REASONS BEHIND ITS \u24d8 (polish TR-34): the 30 s row sat beside a line
         * repeating it and a "3 to 5 minutes" line that is interval girth's alone. */
        Ui.kvInfoRow(a, rr, a.model.trainerGirthStyle == Plan.TRACK_GIRTH_TRADITIONAL
                ? "Rest between holds" : "Traditional girth \u00b7 rest between holds",
            Model.Fmt.t(a.model.restSecFor(Plan.TRACK_GIRTH_TRADITIONAL))
                + " (set by the plan)", Ui.DIM, "Why two rest numbers",
            "Traditional girth rests " + Plan.TRAD_REST_SEC + " s between its five-minute "
            + "holds, set by the plan. Interval work gets 3 minutes, and that one is yours to "
            + "change.\n\nRest spacing applies to interval girth work. A traditional set "
            + "is its own block, and is rested after every one.");
        /* SPACING IS THE INTERVAL TRACK'S OWN RANGE TOO - RxBuild consults it only for
         * TRACK_GIRTH_INTERVAL, because a traditional set is its own block by definition and
         * merging two would delete the rest the guide asks for between them. Shown where it
         * applies; said, rather than silently absent, where it does not. */
        if (a.model.trainerGirthStyle == Plan.TRACK_GIRTH_INTERVAL) {
            Ui.stepperRow(a, rr, "Rest every", a.model.rxSetsPerBlock + " sets",
                new BumpShape(a.SHAPE_BLOCK, -1), new BumpShape(a.SHAPE_BLOCK, +1),
                "sets between rests");
            Ui.noteInfo(a, rr,
                "The guidance asks for 3 to 5 minutes every 5 sets.",
                "How rests are spaced",
                "The guidance asks for 3 to 5 minutes every 5 sets. More rests is "
                + "not less work \u2014 the cycles are the same either way, the recovery is "
                + "just more frequent. Level 1 runs unbroken whatever this says.");
        }

        LinearLayout sp = Ui.cardGroup(a, a.body, "Split the session", null, Ui.DIM);
        Ui.kvRow(a, sp, "Two parts", a.model.rxSplit, new ToggleSplit(false));
        if (a.model.rxSplit)
            Ui.kvRow(a, sp, "Warm up in both", a.model.rxSplitWarmBoth,
                     new ToggleSplit(true));
        Ui.noteInfo(a, sp,
            "The plan writes two routines, each with half the work and half the net target.",
            "How a split session counts",
            "The plan writes two routines, each with half the work and half "
            + "the net target. Run both on one day and it counts as one training day; run "
            + "them on different days and it counts as two \u2014 you were under pressure "
            + "on two days, and the plan counts days.");

        if (a.model.trainerLengthOn) {
            LinearLayout bt = Ui.cardGroup(a, a.body, "Both tracks in a day", null, Ui.DIM);
            /* Y-3 (the owner's option B): the card stays under an alternate week, and says
             * whose days it is about. */
            Ui.note(a, bt, "These apply on days you run both tracks.");
            Ui.kvRow(a, bt, "Length first", a.model.rxLengthFirst, new ToggleLengthFirst());
            /* S08 - THE DEFAULT IS THE GUIDANCE'S, and the note now says so: on a combined
             * day the guidance does length first, then girth (or length in the morning,
             * girth in the evening). Girth first stays a choice, with its one real cost said.
             * Polish TR-21: it also picks which track starts an alternating week, and the days
             * that run both are Long training days', not Settings'. */
            Ui.noteInfo(a, bt,
                "On a day that runs both, Today offers this one first. It also decides which "
                + "track starts an alternating week.",
                "Which track comes first",
                "On a day that runs both, this is the one Today offers first "
                + "\u2014 and the other is offered as soon as it is filed. It also decides "
                + "which track starts an alternating week. Which days run both is set by "
                + "Long training days (Trainer \u203a When it runs).\n\n" + SameDay.ORDER_NOTE);
            if (!a.model.rxLengthFirst)
                Ui.note(a, bt, "Girth first: before a length session that pulls, the confirm "
                    + "says what that does to the pull and offers the expansion only.");
            /* t10 R-07, R-63 - WHAT TAKES THE FATIGUE BLOCK'S PLACE when girth runs after
             * length the same day: the person's choice, with both tracks on. */
            // A value row that opens the three with their effects (polish NEW-4, SYS-3).
            if (PlanCards.r4Shown(a.model))
                Ui.choiceRow(a, bt, PlanCards.R4_ROW, PlanCards.R4_LABELS, PlanCards.R4_EFFECTS,
                    PlanCards.r4Index(a.model.girthAfterLength), a.rootFrame, new R4Choice());
            /* S07 - THE DAY'S NINETY MINUTES. The day's total is always shown; this is only
             * whether the advisory line is (the owner's decision of 2026-09-26). */
            Ui.kvRow(a, bt, "Say when a day passes " + SameDay.DAY_BUDGET_MIN + " min",
                     a.model.dayBudgetAdvisory, new ToggleDayBudget());
            // Polish NEW-5: how this switch and "stop growing at 90 min" relate.
            Ui.noteInfo(a, bt,
                "With \u201c" + LongDays.LABELS[Schedule.LONG_CAP90] + "\u201d the plan does "
                    + "it for you; this switch only warns.",
                "The day's minutes",
                "The guidance keeps a day of both tracks under " + SameDay.DAY_BUDGET_MIN
                + " minutes. Today counts the minutes of "
                + "the day's girth and length sessions (feeders apart), and before a session "
                + "that would take the day past it, the confirm says so. It never stops "
                + "anything.");
        }

        LinearLayout rw = Ui.cardGroup(a, a.body, "Finishing later", null, Ui.DIM);
        Ui.kvRow(a, rw, "Warm up again first", a.model.rxResumeWarm, new ToggleResumeWarm());
        Ui.noteInfo(a, rw,
            "A session that stopped early can be finished the same day.",
            "Finishing a session later",
            "A session that stopped early can be finished the same day. Hours "
            + "have passed by then, so it warms up again before picking up \u2014 and the "
            + "warm-up is an ordinary stage, so Skip still skips it.");

        // S16: the feeder's days, where they can be found once the feeder's offer is saved.
        if (a.model.trainerFeederOptIn) {
            LinearLayout fd = Ui.cardGroup(a, a.body, "Feeder days", null, Ui.DIM);
            feederDaysRow(fd);
        }

    }

    /* R11-5 - HOLD LENGTHS (the owner's pick, option A): one card, a choice row per stage the
     * guidance gives a range for, each limited to that range - the fatigue block's holds (30 to
     * 60 s, 30 s by default), the interval work holds (1 to 3 min, from Level 3: Mint
     * #holdSecWanted - L1 and L2 run their table's hold) and the rest between blocks (3 to 5
     * min). The length strain holds and traditional's 5-minute holds have no range in the
     * guidance: shown fixed, with why behind the ⓘ. A choice rewrites the plan's routines at
     * once, with the notice and Undo (syncPlanRoutines), as a Program picker does. It replaces
     * the "Hold length" stepper (L3+) and the Rests card's rest stepper, the same two settings
     * (rxHoldSec, rxRestSec). */
    private void holdLengthsCard() {
        LinearLayout hl = Ui.cardGroup(a, a.body, PlanCards.HOLDS_CARD, null, Ui.ACCENT);
        Ui.choiceRow(a, hl, PlanCards.FAT_HOLD_ROW, PlanCards.fatigueHoldLabels(),
            PlanCards.fatigueHoldEffects(),
            PlanCards.holdIndex(Model.FATIGUE_HOLD_CHOICES, a.model.rxFatigueHoldSec),
            a.rootFrame, new HoldChoice(HOLD_FAT));
        int lvl = a.model.trainerGirth.level;
        boolean interval = a.model.trainerGirthStyle == Plan.TRACK_GIRTH_INTERVAL;
        if (interval && lvl >= Plan.L3) {
            Ui.choiceRow(a, hl, PlanCards.WORK_HOLD_ROW, PlanCards.workHoldLabels(),
                PlanCards.workHoldEffects(),
                PlanCards.holdIndex(Model.WORK_HOLD_CHOICES, a.model.rxHoldSec),
                a.rootFrame, new HoldChoice(HOLD_WORK));
        } else if (interval) {
            Ui.kvInfoRow(a, hl, PlanCards.WORK_HOLD_ROW, PlanCards.WORK_HOLD_LATER, Ui.DIM,
                PlanCards.WORK_HOLD_ROW, PlanCards.WORK_HOLD_LATER_INFO);
        }
        Ui.choiceRow(a, hl, PlanCards.BLOCK_REST_ROW, PlanCards.blockRestLabels(),
            PlanCards.blockRestEffects(),
            PlanCards.holdIndex(Model.BLOCK_REST_CHOICES, a.model.rxRestSec),
            a.rootFrame, new HoldChoice(HOLD_REST));
        if (a.model.trainerLengthOn)
            Ui.kvInfoRow(a, hl, PlanCards.STRAIN_HOLD_ROW, PlanCards.STRAIN_HOLD_VALUE, Ui.DIM,
                PlanCards.STRAIN_HOLD_ROW, PlanCards.STRAIN_HOLD_INFO);
        if (a.model.trainerGirthStyle == Plan.TRACK_GIRTH_TRADITIONAL || a.model.trainerGirthHybrid)
            Ui.kvInfoRow(a, hl, PlanCards.TRAD_HOLD_ROW, Model.Fmt.t(Mint.HOLD_TRADITIONAL_SEC),
                Ui.DIM, PlanCards.TRAD_HOLD_ROW, PlanCards.TRAD_HOLD_INFO);
        Ui.noteInfo(a, hl, PlanCards.HOLDS_NOTE, PlanCards.HOLDS_INFO_TITLE, PlanCards.HOLDS_INFO);
    }

    private static final int HOLD_FAT = 0, HOLD_WORK = 1, HOLD_REST = 2;

    private final class HoldChoice implements Ui.Choice {
        private final int what;
        HoldChoice(int w) { what = w; }
        @Override public void choose(int i) {
            if (what == HOLD_FAT && i >= 0 && i < Model.FATIGUE_HOLD_CHOICES.length)
                a.model.rxFatigueHoldSec = Model.FATIGUE_HOLD_CHOICES[i];
            else if (what == HOLD_WORK && i >= 0 && i < Model.WORK_HOLD_CHOICES.length)
                a.model.rxHoldSec = Model.WORK_HOLD_CHOICES[i];
            else if (what == HOLD_REST && i >= 0 && i < Model.BLOCK_REST_CHOICES.length)
                a.model.rxRestSec = Model.BLOCK_REST_CHOICES[i];
            else return;
            a.model.clampRxShape();
            Store.save(a, a.model);
            a.syncPlanRoutines();
            showTrainer();
        }
    }

    private final class ClearTodayPlan implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.sched.clearOverride();
            Store.save(a, a.model);
            a.showHome();
        }
    }

    private final class BumpShape implements View.OnClickListener {
        private final int what, dir;
        BumpShape(int w, int d) { what = w; dir = d; }
        @Override public void onClick(View v) {
            switch (what) {
                case SessionActivity.SHAPE_WARM_MIN:   a.model.rxWarmMin += dir; break;
                case SessionActivity.SHAPE_WARM_STEPS: a.model.rxWarmSteps += dir; break;
                case SessionActivity.SHAPE_RET_KPA:
                    // Through Fmt, so a tap moves one unit of whatever is on screen and
                    // lands on a whole kPa the wire can express.
                    a.model.rxRetentionKpa =
                        Model.Fmt.stepWholeKpa((int) Math.round(a.model.rxRetentionKpa), dir);
                    break;
                case SessionActivity.SHAPE_RET_MIN:    a.model.rxRetentionMin += dir; break;
                case SessionActivity.SHAPE_PRIME:
                    a.model.rxPrimeKpa =
                        Model.Fmt.stepWholeKpa((int) Math.round(a.model.rxPrimeKpa), dir);
                    break;
                case SessionActivity.SHAPE_EASE:       a.model.rxEaseHg += dir * 0.5; break;
                case SessionActivity.SHAPE_REST:       a.model.rxRestSec += dir * 30; break;
                case SessionActivity.SHAPE_REST_TRAD:  a.model.rxRestSecTrad += dir * 30; break;
                case SessionActivity.SHAPE_BLOCK:      a.model.rxSetsPerBlock += dir; break;
                case SessionActivity.SHAPE_HOLD:
                    // 0 means "the level's own", so stepping up from it starts at the
                    // level's actual hold rather than at one second.
                    if (a.model.rxHoldSec == 0) a.model.rxHoldSec = Mint.HOLD_INTERVAL_SEC;
                    a.model.rxHoldSec += dir * 30;
                    break;
                default: return;
            }
            a.model.clampRxShape();
            Store.save(a, a.model);
            showTrainer();
        }
    }

    /** B2 - opens the readiness report with NO routine, which is what tells the overrides
     *  dialog this was opened to report rather than to start something. */
    private final class OpenReadinessReportTap implements View.OnClickListener {
        @Override public void onClick(View v) { a.openReadinessReport(); }
    }

    private final class ToggleResumeWarm implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.rxResumeWarm = !a.model.rxResumeWarm;
            Store.save(a, a.model);
            showTrainer();
        }
    }

    private final class ToggleSplit implements View.OnClickListener {
        private final boolean warmBoth;
        ToggleSplit(boolean w) { warmBoth = w; }
        @Override public void onClick(View v) {
            if (warmBoth) a.model.rxSplitWarmBoth = !a.model.rxSplitWarmBoth;
            else a.model.rxSplit = !a.model.rxSplit;
            Store.save(a, a.model);
            showTrainer();
        }
    }

    private final class ToggleMarksEasily implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.marksEasily = !a.model.marksEasily;
            // Turning it OFF no longer clears the taper. An armed taper may have come from a
            // deload the user REPORTED, which this switch says nothing about, and cancelling
            // somebody's gentle return from an unrelated toggle is a silent switch. The card
            // on Today is the way out of it, and it says so.
            if (!a.model.marksEasily) a.model.bigCylinder = false;
            Store.save(a, a.model);
            showTrainer();
        }
    }

    private final class ToggleDayBudget implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.dayBudgetAdvisory = !a.model.dayBudgetAdvisory;
            Store.save(a, a.model);
            showTrainer();
        }
    }

    private final class ToggleLengthFirst implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.rxLengthFirst = !a.model.rxLengthFirst;
            // t10 R-60: it leads an "Alternate on my days" week too, so the week is told and
            // saved as a schedule edit (schedSaved also saves the model). O2: the person chose
            // the lead, so an upgrader's own stored lead follows it (Schedule#leadWith).
            a.model.sched.leadWith(a.model.rxLengthFirst);
            a.schedSaved();
            showTrainer();
        }
    }

    /* ---------------------------------------- t10 R-63: the Trainer page's three rows */

    /** Long training days, picked from its list (polish TR-22): the same write as the cycle,
     *  with the row's Undo putting the previous value back through it. */
    private final class LongDaysChoice implements Ui.Choice {
        @Override public void choose(int i) {
            LongDays.choose(a.model, i);
            a.model.clampRxShape();
            a.schedSaved();
            a.syncPlanRoutines();
            showTrainer();
        }
    }

    /** Length load after month 3, picked from its list (polish NEW-3). */
    private final class LenLoadChoice implements Ui.Choice {
        @Override public void choose(int i) {
            a.model.lengthLoadMode = PlanCards.lenLoadValue(i);
            a.model.clampRxShape();
            Store.save(a, a.model);
            a.syncPlanRoutines();
            showTrainer();
        }
    }

    /** When girth follows length, picked from its list (polish NEW-4). Model's R4 values are
     *  the list's places (PlanCards#r4Index). */
    private final class R4Choice implements Ui.Choice {
        @Override public void choose(int i) {
            a.model.girthAfterLength = Model.R4_WARM + i;
            a.model.clampRxShape();
            Store.save(a, a.model);
            a.syncPlanRoutines();
            showTrainer();
        }
    }


    /**
     * R7 - THE SHAPES YOU KEPT.
     *
     * Above the dials, not below them: the fastest way to get the settings you want is to
     * recall the ones you already decided on, and burying that under twenty controls would
     * mean scrolling past the slow way to reach the fast one.
     *
     * ABSENT UNTIL THERE IS ONE, apart from the save button. An empty "Saved shapes" card
     * over nothing is a promise with no content behind it; the button alone says what the
     * feature is by doing it.
     */
    /**
     * A6 - WHAT A SHAPE LOOKS LIKE, drawn rather than described.
     *
     * A saved shape's summary line is nine facts in a row, and nine facts in a row is a
     * sentence you read rather than a thing you recognise. The strip is the same session as
     * a picture: warm-up, work, rests, retention, in order and in proportion.
     *
     * THE COLOURS ARE THE APP'S OWN STAGE COLOURS - {@link Model#STAGE_WARM},
     * {@link Model#STAGE_WORK}, {@link Model#STAGE_COOL} - the same three the routine editor
     * and the run screen already paint stages with. Nothing new is invented here, and none
     * of the four state colours is borrowed: lime still means action, amber still means the
     * pump is under command, green still means telemetry confirmed it, and violet still
     * belongs to body measurements. A stage strip is none of those things.
     */
    private void shapeStrip(LinearLayout into, Model.Shape sh) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int h = Ui.dp(a, 6);

        // Proportional to the time each part actually takes, so a long retention reads as a
        // long retention rather than as one more equal block.
        int warm = sh.warmMin * 60;
        int work = Math.max(1, sh.setsPerBlock) * Math.max(60, sh.holdSec > 0 ? sh.holdSec
                                                                              : 120);
        int rest = sh.restSec;
        int ret  = sh.retention ? sh.retentionMin * 60 : 0;

        if (warm > 0) stripCell(row, warm, Model.STAGE_WARM, h);
        stripCell(row, work, Model.STAGE_WORK, h);
        if (rest > 0) stripCell(row, rest, Ui.FAINT, h);
        stripCell(row, work, Model.STAGE_WORK, h);
        if (ret > 0)  stripCell(row, ret, Model.STAGE_COOL, h);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
        lp.topMargin = Ui.dp(a, 6);
        lp.bottomMargin = Ui.dp(a, 2);
        into.addView(row, lp);
        Ui.group(row, "Shape: "
            + (warm > 0 ? "warm-up, " : "") + "work"
            + (rest > 0 ? ", rest, work" : "")
            + (ret > 0 ? ", retention hold" : "") + ".");
    }

    private void stripCell(LinearLayout row, int weight, int colour, int h) {
        View v = new View(a);
        v.setBackground(Ui.roundRect(a, colour, 3));
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(0, h, Math.max(1f, weight / 60f));
        lp.rightMargin = Ui.dp(a, 2);
        row.addView(v, lp);
    }

    private void shapesCard() {
        LinearLayout g = Ui.cardGroup(a, a.body, "Saved shapes", null, Ui.DIM);
        for (int i = 0; i < a.model.shapes.size(); i++) {
            Model.Shape sh = a.model.shapes.get(i);
            Ui.kvRow(a, g, sh.name, sh.summary(a.model), Ui.DIM, new ShapeTap(sh.id));
            shapeStrip(g, sh);
        }
        if (a.model.shapes.isEmpty())
            Ui.note(a, g, "None yet. Save the settings below once you have them where "
                + "you want them, and they are one tap away next time.");
        Button save = Ui.flat(a, g, "Save these settings as a shape");
        save.setOnClickListener(new SaveShapeTap());
        if (!a.model.shapes.isEmpty())
            Ui.noteInfo(a, g,
                "Tap a shape to use it or delete it.",
                "How saved shapes work",
                "Tap a shape to use it or delete it. A shape is a copy of "
                + "these numbers \u2014 using one does not tie the two together, so "
                + "changing a dial afterwards leaves the saved shape as it was.");
    }

    /** One shape's row: use it, or delete it. A dialog rather than two buttons per row -
     *  a delete control sitting permanently beside every shape is a delete control that
     *  gets hit by accident. */
    private final class ShapeTap implements View.OnClickListener {
        private final String id;
        ShapeTap(String id) { this.id = id; }
        @Override public void onClick(View v) {
            Model.Shape sh = a.model.shape(id);
            if (sh == null) return;
            Ui.dress(a, Ui.dialog(a)
                .setTitle(sh.name)
                .setMessage(sh.summary(a.model))
                .setPositiveButton("Use this shape", new UseShape(id))
                .setNeutralButton("Delete", new DeleteShape(id))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class UseShape implements DialogInterface.OnClickListener {
        private final String id;
        UseShape(String id) { this.id = id; }
        @Override public void onClick(DialogInterface d, int w) {
            Model.Shape sh = a.model.shape(id);
            if (sh == null) return;
            sh.applyTo(a.model);
            // "Length first" leads an alternate week (Shape#applyTo tells it): saved as a
            // schedule edit, so the reminders follow (review D, F2).
            a.schedSaved();
            Ui.snack(a, a.rootFrame, "Using \u201c" + sh.name + "\u201d");
            showTrainer();
        }
    }

    private final class DeleteShape implements DialogInterface.OnClickListener {
        private final String id;
        DeleteShape(String id) { this.id = id; }
        @Override public void onClick(DialogInterface d, int w) {
            for (int i = 0; i < a.model.shapes.size(); i++) {
                if (a.model.shapes.get(i).id.equals(id)) { a.model.shapes.remove(i); break; }
            }
            Store.save(a, a.model);
            // The live settings are NOT reverted: deleting the note you wrote a number on
            // does not un-choose the number.
            showTrainer();
        }
    }

    private final class SaveShapeTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.model.shapes.size() >= Model.SHAPES_MAX) {
                Ui.snack(a, a.rootFrame,
                    "That is " + Model.SHAPES_MAX + " shapes \u2014 delete one first");
                return;
            }
            final EditText nameField = new EditText(a);
            final String dflt = "Shape " + (a.model.shapes.size() + 1);
            nameField.setText(dflt);
            nameField.setSelectAllOnFocus(true);
            nameField.setTextColor(Ui.TEXT);
            nameField.setHintTextColor(Ui.DIM);
            int pad = Ui.dp(a, Look.S5);
            nameField.setPadding(pad, pad, pad, pad);
            nameField.setContentDescription("Name for this shape");
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Save these settings")
                .setView(nameField)
                .setPositiveButton("Save", new SaveShapeNamed(nameField, dflt))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class SaveShapeNamed implements DialogInterface.OnClickListener {
        private final EditText field;
        private final String dflt;
        SaveShapeNamed(EditText field, String dflt) { this.field = field; this.dflt = dflt; }
        @Override public void onClick(DialogInterface d, int w) {
            String typed = field.getText().toString().trim();
            String name = typed.isEmpty() ? dflt : typed;
            a.model.clampRxShape();
            a.model.shapes.add(Model.Shape.capture(a.model,
                "sh" + System.currentTimeMillis(), name));
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, "Saved \u201c" + name + "\u201d");
            showTrainer();
        }
    }

    private final class ToggleManualWarm implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.rxManualWarm = !a.model.rxManualWarm;
            Store.save(a, a.model);
            showTrainer();
        }
    }

    /**
     * R6 - THE PRESCRIPTION, RE-EXPRESSED AT THE HOLD LENGTH YOU ASKED FOR.
     *
     * Same net, same pressure, same level - a different division of the work into sets. The
     * net target is carried through UNCHANGED and the set count is derived from it, which is
     * what makes this safe: the only thing a longer hold can buy is fewer, longer sets.
     *
     * Returns the prescription untouched wherever the guide gives no range to honour (every
     * level below L3, every track but girth interval, and a preference of 0), so for almost
     * everybody this is a no-op that costs one comparison.
     */
    /**
     * R3 - THE RETENTION HOLD, after the work.
     *
     * NOT A COOL-DOWN in the sense of easing off to nothing. It is the retention period:
     * the cuff held at roughly 3-5 inHg, about where a natural erection sits, instead of
     * going straight from working pressure to atmosphere. The pump does the job a
     * constriction ring would, which is why it is a stage of the routine rather than
     * something that happens after the routine ends.
     *
     * IT IS EXCLUDED FROM NET TUP, and the stage carries {@link Model.Stage#retention} to
     * say so. This is the whole reason it needed thinking about rather than just adding:
     * net counts every frame at or above the LEVEL FLOOR, and L1's floor is 5 inHg -
     * exactly the top of the retention band. Counted, a ten-minute retention would add ten
     * minutes of "net at pressure" to a session that did no more work, and the gate
     * deciding when you move up would be reading a number the plan never asked you to earn.
     *
     * ONE FIXED HOLD, not a cycle. A retention hold that dropped and re-pulled would be
     * more work; this sits where it is put. It is stitched by Model.Set#ladder if it runs
     * past the wire's reach, which for anything over 4:15 it will.
     */
    /**
     * THE SAME PRIME HOLD, IN FRONT OF A MANUAL RUN.
     *
     * A manual cycle is the one thing in the app that reaches the pump without the plan
     * having written it, and it was the one thing that could not be warmed up: somebody who
     * marks easily had to choose between doing it cold and not doing it.
     *
     * OFF BY DEFAULT. A manual run is a deliberate one-off, and putting four minutes in
     * front of it uninvited would be the app deciding what the run is for.
     *
     * THE PRIME ONLY, no eased cycle: a manual run has no prescribed working pressure to
     * ease toward - the pressure IS whatever was just typed - so the second half of the
     * warm-up has nothing to be relative to.
     */
    /**
     * THE WARM-UP, ON EVERY ROUTINE THE PLAN WRITES.
     *
     * IT USED TO SKIP LEVEL 1 ENTIRELY - {@code if (rx.level <= L1) return null} - so the
     * routine a brand-new user was handed on day one opened by pulling straight to the
     * working pressure from cold. That is exactly the thing a warm-up exists to prevent,
     * and it was missing from precisely the people least conditioned to do without it.
     *
     * TWO PARTS, ONE STAGE:
     *
     *   PRIME   a flat hold at about 4 inHg for the warm-up's length. Not a climb - the
     *           guidance warms up with four minutes held at about 4 inHg, and a ramp would
     *           spend most of its time above the pressure the advice is keeping you under.
     *
     *   EASE    one cycle at your working pressure LESS rxEaseHg (3 hg by default), which
     *           is the guidance's first rep, pumped three hg under the working pressure.
     *
     * WHY THE EASED CYCLE IS IN THE WARM-UP AND NOT THE WORK. Put in the work stage it
     * would either replace a prescribed set - training less than the plan asked while the
     * plan went on scoring you as on target - or add a set the plan then has to account
     * for. In the warm-up it is preparation: excluded from net by
     * {@link Model.Stage#outOfNet}, counted by nothing, and it changes no target. The
     * prescription is untouched; you simply arrive at it prepared.
     *
     * NOTHING HERE COUNTS AS TRAINING VOLUME. The stage is STAGE_WARM, and warm and cool
     * stages are now out of net by rule rather than by the accident of sitting below the
     * floor.
     */
    /**
     * Accepts a level-up (propose-confirm): advances the girth track to the next level and
     * resets its position per the guide, then records a gate-crossing entry in decision
     * history. L1→L2 restarts at the L2 table's week 1 and the 8 hg floor; L2→L3 CARRIES the
     * achieved volume (guide "volume carries") into the table-less L3 via {@code carriedSets}
     * and keeps the working pressure. The old-level mint pointer is cleared so the next render
     * offers the new level's starting routine — whose {@code netTupSec} attributes against the
     * NEW level's floor (Plan.floorKpa), keeping the mint↔attribution chain coherent.
     */
    /** The line that states the divergence in the user's OWN numbers, so a proposal about
     *  their body is not something they have to take on trust. Empty when either series is
     *  too thin to quote, in which case the decision's own reason still stands alone. */
    private String divergenceLine() {
        long now = System.currentTimeMillis();
        java.util.List<Model.Reading> w = Meas.windowFor(
            a.model.measLog, Plan.DIVERGENCE_WEEKS * 7, now);
        double stretched = Meas.seriesChangeCm(w, Model.Reading.METHOD_BPSSL);
        double erect = Meas.seriesChangeCm(w, Model.Reading.METHOD_BPEL);
        if (Double.isNaN(stretched) || Double.isNaN(erect)) return "";
        // Fmt#lenDelta already prints a signed length difference in the user's own unit -
        // the same figures the trends screen shows them, not a second formatting of them.
        return "Stretched " + Model.Fmt.lenDelta(stretched) + " over "
             + Plan.DIVERGENCE_WEEKS + " weeks; erect " + Model.Fmt.lenDelta(erect) + ".";
    }

    /** +0.5 lb, or the load a confirmed high reading drops to - one tap for both, because
     *  both are "the ladder has decided on a load and the user has agreed to it". */
    private final class RaiseLoadTap implements View.OnClickListener {
        private final int track;
        private final double lb;
        /** G2 - the length pressure a climb past the usual top moves to with the load, or a
         *  confirmed cut lowers it to (t10 R-43); NaN for every other load step. */
        private final double kpa;
        /** The decision's rule - what option D, the cut record and the slow step remember of
         *  this step (LengthTrack#acceptLoad). */
        private final String rule;
        RaiseLoadTap(int t, double l, double k, String r) { track = t; lb = l; kpa = k; rule = r; }
        @Override public void onClick(View v) {
            Model.TrainerTrackState st = a.model.trainerLength;
            double from = st.loadLb;
            long now = System.currentTimeMillis();
            // The load (restarting the strain clock), and the climb's pressure, which restarts
            // the pressure clock the next step counts its month from.
            LengthTrack.acceptLoad(a.model, rule, lb, kpa, now);
            // The prescription has changed, so the mint pointer must not claim otherwise -
            // the same clearing every other accepted change does.
            st.lastMintSig = "";
            recordAccepted(track, Plan.ACTION_RAISE_LOAD,
                "load " + Traction.settingLb(from) + " -> " + Traction.settingLb(lb));
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame,
                "Traction load is now " + Traction.boundLb(lb));
            showTrainer();
        }
    }

    private final class AddStrainSetTap implements View.OnClickListener {
        private final int track;
        private final int sets;
        /** The decision's rule - option D's added set, the month-3 hand-over
         *  (LengthTrack#acceptSets). */
        private final String rule;
        AddStrainSetTap(int t, int n, String r) { track = t; sets = n; rule = r; }
        @Override public void onClick(View v) {
            Model.TrainerTrackState st = a.model.trainerLength;
            int from = st.strainSets;
            // Restarts the strain clock.
            LengthTrack.acceptSets(a.model, rule, sets, System.currentTimeMillis());
            st.lastMintSig = "";
            recordAccepted(track, Plan.ACTION_ADD_VOLUME,
                "strain sets " + from + " -> " + sets);
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, "Now " + sets + " strain sets");
            showTrainer();
        }
    }

    /**
     * A BLOCK WITH AN END DATE, not a switch. The track keeps its name, its level and its
     * history; the traction work pauses and the coda doubles, and when the date passes the
     * track hands itself back with nothing asked of anybody - which is why the END is what
     * gets stored rather than a flag somebody would have to remember to clear.
     */
    private final class StartGirthFocusTap implements View.OnClickListener {
        private final int track;
        StartGirthFocusTap(int t) { track = t; }
        @Override public void onClick(View v) {
            long now = System.currentTimeMillis();
            Model.TrainerTrackState st = a.model.trainerLength;
            st.focusBlockUntilMs = now + Plan.GIRTH_FOCUS_WEEKS * 7L * 86400000L;
            st.lastMintSig = "";
            recordAccepted(track, Plan.ACTION_GIRTH_FOCUS,
                Plan.GIRTH_FOCUS_WEEKS + "-week girth-focus block");
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame,
                "Girth focus for " + Plan.GIRTH_FOCUS_WEEKS + " weeks \u2014 it ends on its own");
            showTrainer();
        }
    }

    /** One place a length decision is written to the history, so the three taps above cannot
     *  each invent their own shape of record. */
    private void recordAccepted(int track, int action, String what) {
        Model.TrainerDecision d = new Model.TrainerDecision();
        d.track = track;
        d.action = action;
        d.tag = Plan.TAG_INFERRED;
        d.rule = what;
        d.reason = what;
        d.ts = System.currentTimeMillis();
        d.state = Model.TrainerDecision.STATE_ACCEPTED;
        a.model.addTrainerDecision(d);
    }

    private final class LevelUpTap implements View.OnClickListener {
        private final int track;
        private final int nextLevel;
        LevelUpTap(int t, int nl) { track = t; nextLevel = nl; }
        @Override public void onClick(View v) {
            // LENGTH HAS GATES NOW TOO, so this can no longer assume the girth track. Its
            // gates are calendar months rather than earned metrics (Plan#lengthGateMetNextLevel),
            // but the crossing itself is the same event: propose, confirm, then move.
            Model.TrainerTrackState state = (track == Plan.TRACK_LENGTH)
                ? a.model.trainerLength : a.model.trainerGirth;
            long now = System.currentTimeMillis();
            int from = state.level;
            if (track == Plan.TRACK_LENGTH) {
                /* A LENGTH CROSSING MOVES THE LEVEL AND NOTHING ELSE. The girth branches
                 * below re-floor a pressure, carry a set count and reset a week table -
                 * every one of which is about the girth week tables, which length does not
                 * have. Its load and its strain sets are the ladder's to move, from the
                 * evidence, and a level crossing is not evidence about either. The crossing
                 * is core's (LengthTrack#crossLevel), which marks the morning's one change. */
                LengthTrack.crossLevel(a.model, nextLevel, now);
                Model.TrainerDecision ld = new Model.TrainerDecision();
                ld.track = track; ld.action = Plan.ACTION_LEVEL_UP; ld.tag = Plan.TAG_INFERRED;
                ld.rule = "length month gate " + TrainerTab.levelLabel(from) + "→"
                    + TrainerTab.levelLabel(nextLevel);
                ld.reason = ld.rule + " met — moved up";
                ld.ts = now; ld.state = Model.TrainerDecision.STATE_ACCEPTED;
                a.model.addTrainerDecision(ld);
                Store.save(a, a.model);
                Ui.snack(a, a.rootFrame,
                    "Moved to " + TrainerTab.levelLabel(nextLevel));
                showTrainer();
                return;
            }

            Model.TrainerDecision d = new Model.TrainerDecision();
            d.track = track; d.action = Plan.ACTION_LEVEL_UP;
            // Traditional's L1 gate is the app's own time condition (INFERRED).
            d.tag = (from == Plan.L1 && track == Plan.TRACK_GIRTH_INTERVAL)
                ? Plan.TAG_SOURCE : Plan.TAG_INFERRED;
            d.rule = "gate crossed " + TrainerTab.levelLabel(from) + "→"
                + TrainerTab.levelLabel(nextLevel);
            d.reason = TrainerTab.levelLabel(from) + "→" + TrainerTab.levelLabel(nextLevel)
                + " gate met — moved up to " + TrainerTab.levelLabel(nextLevel);
            d.ts = now; d.state = Model.TrainerDecision.STATE_ACCEPTED;
            a.model.addTrainerDecision(d);

            // THE CROSSING ITSELF IS CORE'S (Mint#crossGirthLevel): the set count carries,
            // and the pressure count restarts only when the working pressure changes (the
            // owner's decision, 2026-09-27) - L2 -> L3 and L3 -> L4 keep it.
            Mint.crossGirthLevel(state, track, nextLevel, now);
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, "Moved to " + TrainerTab.levelLabel(nextLevel));
            showTrainer();
        }
    }

    private final class AdjustMintTap implements View.OnClickListener {
        private final int track;
        AdjustMintTap(int t) { track = t; }
        @Override public void onClick(View v) {
            Model.TrainerTrackState st = (track == Plan.TRACK_LENGTH)
                ? a.model.trainerLength : a.model.trainerGirth;
            SessionActivity.TrainerEval e = a.evalTrack(track, st, System.currentTimeMillis());
            if (e == null || e.rx == null) return;
            /* THE SHEET STARTS FROM WHAT THE PLAN'S ROUTINE RUNS (review M1, M4): the pressure
             * with the Program's bias on it - the figure the card shows before the day's cut -
             * and a hybrid's five-minute holds. It started from the plain prescription, so under
             * Gentle one step down from it was still above the Gentle floor, became the person's
             * pressure, and pulled harder than the plan's own routine. */
            a.mintAdjSets = RxBuild.adjustStartSets(a.model, e.rx);
            a.mintAdjKpa = RxBuild.adjustStartKpa(a.model, e.rx);
            showMintAdjust(track, e);
        }
    }

    private void showMintAdjust(final int track, final SessionActivity.TrainerEval e) {
        LinearLayout col = Ui.col(a);
        int planSets = RxBuild.adjustStartSets(a.model, e.rx);
        int planKpa = RxBuild.adjustStartKpa(a.model, e.rx);
        String unit = RxBuild.runsAs(a.model, e.rx) != e.rx ? " five-minute holds" : " sets";
        String asks = "The plan asks for " + planSets + unit + " at " + Model.Fmt.p(planKpa) + ".";
        // The day's cut still comes off whatever is chosen here, as it does off the plan's own.
        int today = RxBuild.commandedKpa(a.model, e.rx);
        String cut = today < planKpa
            ? " Today is a lighter day: the pump runs it lighter still, as it would the plan's."
            : "";
        Ui.noteInfo(a, col, asks + cut,
            "Why adjustments are recorded",
            asks + " Change either and the difference is recorded "
            + "on the routine, so nothing later presents it as the plan's own work. A pressure "
            + "you change is yours: the Program's gentle or firm setting does not move it.");
        Ui.stepperRow(a, col, "Sets", String.valueOf(a.mintAdjSets),
            new BumpMintSets(-1, track, e), new BumpMintSets(+1, track, e), "sets");
        Ui.stepperRow(a, col, "Working pressure", Model.Fmt.p(a.mintAdjKpa),
            new BumpMintKpa(-1, track, e), new BumpMintKpa(+1, track, e), "working pressure");
        Ui.note(a, col, mintAdjustSummary(e));
        AlertDialog d = Ui.dialog(a)
            .setTitle("Adjust before saving")
            .setView(col)
            .setPositiveButton("Save it", new SaveAdjustedMintTap(track, e))
            .setNegativeButton("Cancel", null)
            .show();
        Ui.dress(a, d);
        a.mintAdjustDialog = d;
    }

    /** The sentence stored on the routine, and shown live while it is being chosen - one
     *  wording, so what is previewed is exactly what is recorded. */
    private String mintAdjustSummary(SessionActivity.TrainerEval e) {
        String s = a.mintAdjustSummaryFor(e, a.mintAdjSets, a.mintAdjKpa);
        return s.length() == 0 ? "Exactly what the plan asked for." : s;
    }

    private final class BumpMintSets implements View.OnClickListener {
        private final int dir, track; private final SessionActivity.TrainerEval e;
        BumpMintSets(int d, int t, SessionActivity.TrainerEval ev) { dir = d; track = t; e = ev; }
        @Override public void onClick(View v) {
            // A hybrid never runs more holds than the guidance's count (review M4).
            a.mintAdjSets = Math.max(1, Math.min(RxBuild.adjustMaxSets(a.model, e.rx),
                                                 a.mintAdjSets + dir));
            reopenMintAdjust(track, e);
        }
    }

    private final class BumpMintKpa implements View.OnClickListener {
        private final int dir, track; private final SessionActivity.TrainerEval e;
        BumpMintKpa(int d, int t, SessionActivity.TrainerEval ev) { dir = d; track = t; e = ev; }
        @Override public void onClick(View v) {
            // THE SAME LIMITS THE PRESCRIPTION'S OWN PRESSURE WENT THROUGH (0.10): the
            // effective top and the hard limits, at the ENGINE'S month (bug d: it read the
            // months since enrolment). An adjustment is a preference, never a way past a
            // limit - and a "+" never lowers the figure (RxBuild#adjustBumpKpa).
            long now = System.currentTimeMillis();
            int want = RxBuild.adjustBumpKpa(a.model, e.rx, a.mintAdjKpa,
                                             a.stepKpa(a.mintAdjKpa, dir), now);
            if (want == a.mintAdjKpa) {
                // Silent refusal reads as a dead button. Say which limit was reached.
                int hard = Scale.hardKpa(a.model, track, TrainerTab.monthIndexNow(a.model, now));
                a.toast(dir < 0 ? "That is as low as it goes"
                    : RxBuild.adjustMaxKpa(a.model, e.rx, now) >= hard ? hardWhy(track, hard)
                    : "That is this level's top at your pressure \u2014 My pressure moves it");
                return;
            }
            a.mintAdjKpa = want;
            reopenMintAdjust(track, e);
        }
    }

    /** The sheet is rebuilt rather than written into, for the same reason every editor on
     *  the set screen rebuilds: two paths that paint the same rows eventually disagree. */
    private void reopenMintAdjust(int track, SessionActivity.TrainerEval e) {
        if (a.mintAdjustDialog != null) { a.mintAdjustDialog.dismiss(); a.mintAdjustDialog = null; }
        showMintAdjust(track, e);
    }

    private final class SaveAdjustedMintTap implements DialogInterface.OnClickListener {
        private final int track; private final SessionActivity.TrainerEval e;
        SaveAdjustedMintTap(int t, SessionActivity.TrainerEval ev) { track = t; e = ev; }
        @Override public void onClick(DialogInterface d, int w) {
            a.saveMint(track, e, a.mintAdjSets, a.mintAdjKpa);
        }
    }

    private final class SaveMintTap implements View.OnClickListener {
        private final int track;
        SaveMintTap(int t) { track = t; }
        @Override public void onClick(View v) {
            Model.TrainerTrackState state = (track == Plan.TRACK_LENGTH)
                ? a.model.trainerLength : a.model.trainerGirth;
            long now = System.currentTimeMillis();
            SessionActivity.TrainerEval e = a.evalTrack(track, state, now);
            // Saving unchanged is saving with the sheet's own starting figures - one path, so
            // an adjusted mint and a plain one cannot drift apart in how they are filed.
            if (e == null || e.rx == null) return;
            a.saveMint(track, e, RxBuild.adjustStartSets(a.model, e.rx),
                       RxBuild.adjustStartKpa(a.model, e.rx));
        }
    }

    private final class SaveFeederTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            // NOT LOCKED. This writes a new routine into the library and stops. It cannot
            // reach the pump, cannot change the plan a running session is playing, and
            // cannot change what a rejoin rebuilds - so refusing it was theatre by the rule
            // stated at lockedDuringRun, and theatre is exactly what that rule forbids.
            long now = System.currentTimeMillis();
            SessionActivity.TrainerEval e = a.evalFeeder(now);
            if (e.decision.action != Plan.ACTION_FEEDER_SUGGEST) { showTrainer(); return; }
            String id = fileFeeder(e, now);
            a.editStageIdx = 0;
            Ui.snack(a, a.rootFrame, "Feeder saved to Library");
            a.showRoutEdit(id);
        }
    }

    /** Files the feeder's routine - the feeder card's own save, shared with the trainer
     *  setup's offer (0.10): minted, its signature kept, the decision accepted, saved. */
    private String fileFeeder(SessionActivity.TrainerEval e, long now) {
        String id = a.mintRoutineFromRx(e.rx);
        a.model.trainerFeederMintId = id;
        a.model.trainerFeederMintSig = e.sig;
        a.acceptDecision(Plan.TRACK_FEEDER, e.decision, now);
        Store.save(a, a.model);
        return id;
    }

    /** Opens an existing mint in the routine editor (the idempotent "already saved" path),
     *  mirroring DupRoutineTap's own post-copy navigation. A mint deleted between draw and
     *  tap falls back to a redraw (the suggestion re-offers). */
    private final class OpenMintTap implements View.OnClickListener {
        private final String routineId;
        OpenMintTap(String id) { routineId = id; }
        @Override public void onClick(View v) {
            if (a.model.routine(routineId) == null) { showTrainer(); return; }
            a.editStageIdx = 0;
            a.showRoutEdit(routineId);
        }
    }

    /** Records a fired decision into the bounded history, but only when it is a genuine
     *  suggestion/event (not a plain HOLD) AND not the same event already at the front for
     *  this track (a decision is an event; the same unchanged decision across renders is ONE
     *  entry — {@link Mint#shouldRecord}). Persists only when it actually appends. */
    private void recordDecisionIfNew(int track, Plan.Decision d, long now) {
        if (d.action == Plan.ACTION_HOLD) return;
        // The in-progress deload week re-emits DELOAD every render with a different rule than
        // the "due" one — don't file a fresh PENDING for it; it was already accepted at start.
        if (d.action == Plan.ACTION_DELOAD && a.inDeloadWeek(now)) return;
        Model.TrainerDecision latest = TrainerTab.latestDecisionForTrack(a.model, track);
        if (!Mint.shouldRecord(latest, d.action, d.tag, d.rule)) return;
        a.model.addTrainerDecision(a.newDecision(track, d, now, Model.TrainerDecision.STATE_PENDING));
        Store.save(a, a.model);
    }

    /** Marks a saved suggestion accepted: flips the matching front decision to ACCEPTED, or
     *  files a fresh ACCEPTED entry when the save was of a plain-HOLD starting routine (no
     *  suggestion event was recorded). */
    /**
     * THE OTHER HALF OF acceptDecision, AND IT HAD NEVER BEEN WRITTEN (audit A16).
     * Model.TrainerDecision.STATE_IGNORED has always existed and decisionStateLabel has
     * always been able to render it as "ignored", but nothing in the app could ever assign
     * it: the cards offered Save, Move up and Start deload, and nothing else. So a
     * suggestion you had considered and consciously turned down was indistinguishable from
     * one you had never looked at \u2014 both sat at "shown" forever, and recordDecisionIfNew
     * kept re-offering it.
     *
     * Deliberately does NOT change the plan. Declining is a statement about this
     * suggestion, not about the position it was derived from; the engine will offer it
     * again when its inputs change, which is the correct behaviour for "not now".
     */
    private void dismissDecision(int track) {
        Model.TrainerDecision latest = TrainerTab.latestDecisionForTrack(a.model, track);
        if (latest == null || latest.state != Model.TrainerDecision.STATE_PENDING) return;
        latest.state = Model.TrainerDecision.STATE_IGNORED;
        Store.save(a, a.model);
        Ui.snack(a, a.rootFrame, "Not now \u2014 kept in your decision history");
        showTrainer();
    }

    /** R11-4 - the answer to "Step up now?", kept with the step it answered. */
    private final class BrakeAnswerTap implements View.OnClickListener {
        private final int track, kind, answer;
        BrakeAnswerTap(int t, int k, int ans) { track = t; kind = k; answer = ans; }
        @Override public void onClick(View v) {
            Model.TrainerTrackState st = track == Plan.TRACK_LENGTH ? a.model.trainerLength
                                                                    : a.model.trainerGirth;
            GainBrake.answer(st, GainBrake.clockKey(st, kind), answer);
            Store.save(a, a.model);
            showTrainer();
        }
    }

    /** "Not now" on a suggestion card. Carries the track so one listener serves every card. */
    private final class DismissDecisionTap implements View.OnClickListener {
        private final int track;
        DismissDecisionTap(int t) { track = t; }
        @Override public void onClick(View v) { dismissDecision(track); }
    }

    /* ------------------------------------------------ t10 R-64: the new decision cards */

    /** A card's question as a heading under its title (polish TR-25). */
    private void cardHeading(LinearLayout g, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(Look.SP_BODY);
        Ui.semibold(t);
        t.setPadding(0, Ui.dp(a, Look.S1), 0, 0);
        g.addView(t);
    }

    /** Where a card sentence's first clause ends - its first " — " or ". " - or -1. */
    static int firstClauseEnd(String s) {
        if (s == null) return -1;
        int d = s.indexOf(" — "), p = s.indexOf(". ");
        if (d < 0) return p;
        if (p < 0) return d;
        return Math.min(d, p);
    }

    /** A label without a trailing "\u25b8" text chevron (polish SYS-5): actions carry none. */
    static String noGlyph(String label) {
        if (label == null) return "";
        String t = label.replace(String.valueOf((char) 0x25B8), "").trim();
        return t;
    }

    /** The feeder's spacing in one sentence, the Trainer's and Today's alike (polish TR-33). */
    static final String FEEDER_SPACING = "Feeders go 4\u20136 h after the day\u2019s main session. "
        + "Starting sooner is your call.";

    /** Whether a HOLD carries words of its own that the track's card says: lane B's time-cap
     *  hold (Decision#r2), and lane C's cut-week and fallback-top holds - and the load step
     *  that waits for a session at the last one (REAL-11), and a length change that waits for
     *  the next morning (t10 parity run 2, A-3; girth, O-1). The 90-minute hold has its own
     *  card (heldAt90Card). */
    private static boolean holdSays(Plan.Decision d) {
        if (d == null || d.action != Plan.ACTION_HOLD || d.reason == null) return false;
        return d.r2() || Plan.LENGTH_CUT_WAIT_RULE.equals(d.rule)
            || Plan.LENGTH_NO_READINGS_TOP_RULE.equals(d.rule)
            || Plan.LENGTH_LOAD_WAITS_RULE.equals(d.rule)
            || Plan.LENGTH_ONE_CHANGE_RULE.equals(d.rule)
            || Plan.GIRTH_ONE_CHANGE_RULE.equals(d.rule);
    }

    /** The latest decision for `track`, still pending, answered as `state`. */
    private void answerLatest(int track, int state) {
        Model.TrainerDecision latest = TrainerTab.latestDecisionForTrack(a.model, track);
        if (latest != null && latest.state == Model.TrainerDecision.STATE_PENDING)
            latest.state = state;
    }

    static final int OFFER_WEEK_OFF = 0, OFFER_OTHER = 1, OFFER_NOT_NOW = 2;

    /**
     * THE OFFER OF A BREAK, ANSWERED (t10 R-23, R-40 D3). Whichever answer it is, the offer is
     * answered: girth's pending add clears and its streak restarts (Mint#answerYieldOffer),
     * and the length offer is spent for the block (LengthTrack#offerAnswered). Then:
     *   a week off - the deload, from tomorrow (StartDeloadTap);
     *   the other - girth: four weeks of length focus, a dated girth rest (girth is paused,
     *     TrainerTab#girthPausedNow, while length runs as planned); length: a girth block
     *     (StartGirthFocusTap);
     *   not now - kept in the history as declined; nothing else moves.
     */
    private final class OfferBreakTap implements View.OnClickListener {
        private final int track, which;
        OfferBreakTap(int t, int w) { track = t; which = w; }
        @Override public void onClick(View v) {
            boolean length = track == Plan.TRACK_LENGTH;
            long now = System.currentTimeMillis();
            // The lanes' own answers (B, C): the girth add stops pending and its streak
            // restarts; the length offer is spent for the block and its week of readings
            // starts afresh. Without them the offer would repeat every morning.
            if (length) LengthTrack.offerAnswered(a.model, now);
            else Mint.answerYieldOffer(a.model.trainerGirth, now);
            if (which == OFFER_NOT_NOW) {
                answerLatest(track, Model.TrainerDecision.STATE_IGNORED);
                Store.save(a, a.model);
                Ui.snack(a, a.rootFrame, "Not now — kept in your decision history");
                showTrainer();
                return;
            }
            answerLatest(track, Model.TrainerDecision.STATE_ACCEPTED);
            Store.save(a, a.model);
            // The week off starts from tomorrow (R-23: "the next training morning").
            if (which == OFFER_WEEK_OFF) {
                a.new StartDeloadTap(track, true).onClick(v);
                return;
            }
            if (length) { new StartGirthFocusTap(track).onClick(v); return; }
            Model.TrainerTrackState st = a.model.trainerGirth;
            TrainerTab.startGirthRest(a.model, now);
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, PlanCards.lengthFocusTaken(a.dayLabel(st.restUntilMs)));
            showTrainer();
        }
    }

    /** t10 R-40 D1 - THE WEEK OFF A FALLEN LENGTH READING BROUGHT FORWARD, taken: the readings
     *  before it are the old block's, so the strain clock starts again (LengthTrack#
     *  fellAccepted), and the week off starts from tomorrow, as the card says. */
    private final class FellWeekOffTap implements View.OnClickListener {
        private final int track;
        FellWeekOffTap(int t) { track = t; }
        @Override public void onClick(View v) {
            LengthTrack.fellAccepted(a.model, System.currentTimeMillis());
            a.new StartDeloadTap(track, true).onClick(v);
        }
    }

    /**
     * "SAME DAYS, STOP GROWING AT 90 MIN" IS HOLDING A TRACK (t10 R-60, R-64). The plan held a
     * volume step because the both-tracks day would pass 90 minutes with it; said once per
     * run of holds (the first hold is filed and answered like any decision), with the
     * one-tap switch to alternate days and its Undo (K10), or "Keep as is".
     */
    private void heldAt90Card() {
        if (a.model.sched == null
                || a.model.sched.longDaysInForce() != Schedule.LONG_CAP90) return;
        long now = System.currentTimeMillis();
        int track = -1;
        if (a.model.trainerGirthOn && heldAt90Owed(a.model.trainerGirthStyle,
                a.model.trainerGirth, now))
            track = a.model.trainerGirthStyle;
        else if (a.model.trainerLengthOn && heldAt90Owed(Plan.TRACK_LENGTH,
                a.model.trainerLength, now))
            track = Plan.TRACK_LENGTH;
        if (track < 0) return;
        LinearLayout g = Ui.cardGroup(a, a.body, PlanCards.HELD90_TITLE, null, Ui.ACCENT);
        // A note like every other card's (polish TR-2), not a body louder than all of them.
        Ui.note(a, g, PlanCards.heldAt90Text(track));
        Button sw = Ui.big(a, g, PlanCards.HELD90_SWITCH, Ui.ACCENT);
        sw.setContentDescription(PlanCards.HELD90_SWITCH + ". " + DayLength.switchNote(a.model));
        sw.setOnClickListener(new HeldAt90Tap(track, true));
        Ui.secondary(a, g, PlanCards.HELD90_KEEP).setOnClickListener(new HeldAt90Tap(track, false));
    }

    /** Whether `track`'s decision today is the 90-minute hold and its card is owed: the first
     *  hold of a run is filed (a hold is otherwise never filed - recordDecisionIfNew), and
     *  the card shows while that filing is unanswered. */
    private boolean heldAt90Owed(int track, Model.TrainerTrackState st, long now) {
        SessionActivity.TrainerEval e = a.evalTrack(track, st, now);
        if (e == null || !PlanCards.isHeldAt90(e.decision)) return false;
        Model.TrainerDecision latest = TrainerTab.latestDecisionForTrack(a.model, track);
        if (Mint.shouldRecord(latest, e.decision.action, e.decision.tag, e.decision.rule)) {
            a.model.addTrainerDecision(a.newDecision(track, e.decision, now,
                Model.TrainerDecision.STATE_PENDING));
            Store.save(a, a.model);
            return true;
        }
        return latest != null && latest.state == Model.TrainerDecision.STATE_PENDING
            && latest.rule != null && latest.rule.startsWith(Plan.HELD_AT_90_RULE);
    }

    /** The 90-minute card's answers: the switch (as the long-day card's, with its Undo), or
     *  keep as is (kept in the history as declined). */
    private final class HeldAt90Tap implements View.OnClickListener {
        private final int track;
        private final boolean switchIt;
        HeldAt90Tap(int t, boolean s) { track = t; switchIt = s; }
        @Override public void onClick(View v) {
            if (!switchIt) { dismissDecision(track); return; }
            answerLatest(track, Model.TrainerDecision.STATE_ACCEPTED);
            Schedule was = a.model.sched.copy();
            DayLength.switchToAlternate(a.model);
            a.schedSaved();
            Schedule wrote = a.model.sched.copy();
            showTrainer();
            Ui.snack(a, a.rootFrame, DayLength.switchedLine(a.model), Ui.UNDO,
                new UndoAlternateTap(was, wrote), Snack.HOLD_MS_ACTIONABLE);
        }
    }

    /**
     * The decision-history list — past suggestions (accepted/shown), safety events and gate
     * crossings, newest first (Model.trainerDecisions, bounded 50). Nothing is re-derived:
     * each row is the frozen copy filed when the event fired (plan: "decisions are events...
     * never retroactively rewritten").
     */
    private void trainerDecisionHistory() {
        if (a.model.trainerDecisions.isEmpty()) return;
        Ui.categoryHeader(a, a.body, "≡", "Decision history", Ui.DIM);
        // One heading for one list (polish TR-6): the card carries no second title.
        LinearLayout g = Ui.cardGroup(a, a.body, null, null);
        /* O9 — TWELVE IS A GOOD DEFAULT AND A POOR CEILING. Fifty are stored; thirty-eight
         * were unreachable, and the question people actually bring to this list — "when
         * did it last step me back?" — is usually older than twelve. The cap stays as the
         * opening state, with a way past it and a filter for the one action being looked
         * for, so a long history answers a question instead of merely being long. */
        // A segmented filter (polish TR-6): the bulleted buttons broke "Step-bac / ks".
        Ui.segmented(a, g, new String[]{ "All", "Accepted", "Not now", "Step-backs" }, null,
            Say.clampI(a.decisionFilter, 0, 3),
            new View.OnClickListener[]{ new DecisionFilterTap(0), new DecisionFilterTap(1),
                                        new DecisionFilterTap(2), new DecisionFilterTap(3) });
        int cap = a.decisionsAllShown ? Integer.MAX_VALUE : 12;
        int matching = 0;
        for (int i = 0; i < a.model.trainerDecisions.size(); i++)
            if (decisionShown(a.model.trainerDecisions.get(i))) matching++;
        int shown = 0;
        for (int i = 0; i < a.model.trainerDecisions.size() && shown < cap; i++) {
            Model.TrainerDecision d = a.model.trainerDecisions.get(i);
            if (d == null || !decisionShown(d)) continue;
            // F5: the provenance tag is no longer appended here either — it told the
            // reader about the source material rather than about their training.
            // E5: the row opens the decision in full. Polish TR-7: one door per decision -
            // its own words and its status ("Accepted", "Not now", "Waiting") - in place of
            // a track · action line over the reason in an outlined button.
            Ui.doorRow(a, g, d.reason == null ? "(no reason recorded)" : Say.sentence(d.reason),
                new String[]{ Say.decisionStateLabel(d.state) }, new DecisionDetailTap(d));
            shown++;
        }
        if (matching == 0)
            Ui.note(a, g, "No decision of that kind has been recorded yet.");
        else if (shown < matching) {
            Button more = Ui.secondary(a, g, "Show all " + matching);
            more.setContentDescription("Show all " + matching
                + " decisions, " + (matching - shown) + " more than the "
                + shown + " listed.");
            more.setOnClickListener(new ShowAllDecisionsTap());
        }
    }

    private boolean decisionShown(Model.TrainerDecision d) {
        if (d == null) return false;
        switch (a.decisionFilter) {
            case 1: return d.state == Model.TrainerDecision.STATE_ACCEPTED;
            case 2: return d.state == Model.TrainerDecision.STATE_IGNORED;
            case 3: return d.action == Plan.ACTION_STEP_BACK
                        || d.action == Plan.ACTION_DELOAD
                        || d.action == Plan.ACTION_REDUCE_VOLUME
                        || d.action == Plan.ACTION_PAUSE_VOLUME;
            default: return true;
        }
    }

    private final class DecisionFilterTap implements View.OnClickListener {
        private final int which;
        DecisionFilterTap(int w) { which = w; }
        @Override public void onClick(View v) {
            a.decisionFilter = which;
            // A new filter starts closed again: the cap is about how much of ONE list to
            // read, and this is a different list.
            a.decisionsAllShown = false;
            showTrainer();
        }
    }

    private final class ShowAllDecisionsTap implements View.OnClickListener {
        @Override public void onClick(View v) { a.decisionsAllShown = true; showTrainer(); }
    }

    /**
     * The reminders block — Ui.categoryHeader + Ui.cardGroup with 4 boolean Ui.kvRow
     * toggles, riding the same "flip, Store.save, redraw" shape every Settings toggle in
     * this app follows (ToggleAppLockOn). Persisted-toggle-only: no alarm is scheduled
     * and nothing fires from these yet (the actual ReminderReceiver wiring is out of
     * scope for this task; the copy says so plainly rather than implying otherwise).
     */
    /**
     * TWO REMINDERS THAT FIRE, INSTEAD OF FOUR THAT DID NOT (G7).
     *
     * All four toggles persisted and none of them ever raised an alarm; the card said so in
     * a note, which is honest but is still four controls that do nothing. Two of them are
     * genuinely time-based and are now real: a training day, and the morning a deload
     * begins. The other two are gone rather than left lying \u2014 "tracking day" duplicated the
     * measurement reminder that already exists in Settings, and "decision ready" is not a
     * time at all: the engine files a decision when it files one, and an alarm cannot know
     * in advance which morning that will be.
     *
     * The FIELDS stay in Model, unread. Removing them would rewrite saved state for no gain,
     * and if a trigger for either is ever defined the toggle can come back without a
     * migration.
     */
    private void remindersBlock() {
        Ui.categoryHeader(a, a.body, "⚑", "Reminders", Ui.DIM);
        LinearLayout g = Ui.cardGroup(a, a.body, "Trainer reminders", null);
        Ui.noteInfo(a, g, "When they are sent", "Trainer reminders",
            "Sent at the same time of day as your measurement reminder.");
        Ui.kvRow(a, g, "Training day", a.model.trainerRemindTrainingDay,
            new ToggleTrainerRemindTap(0));
        Ui.kvRow(a, g, "Deload start", a.model.trainerRemindDeloadStart,
            new ToggleTrainerRemindTap(3));
    }

    private final class ToggleTrainerRemindTap implements View.OnClickListener {
        private final int which;
        ToggleTrainerRemindTap(int w) { which = w; }
        @Override public void onClick(View v) {
            switch (which) {
                case 0: a.model.trainerRemindTrainingDay = !a.model.trainerRemindTrainingDay; break;
                case 1: a.model.trainerRemindTrackingDay = !a.model.trainerRemindTrackingDay; break;
                case 2: a.model.trainerRemindDecisionReady = !a.model.trainerRemindDecisionReady;
                        break;
                default: a.model.trainerRemindDeloadStart = !a.model.trainerRemindDeloadStart; break;
            }
            Store.save(a, a.model);
            Reminders.rescheduleTrainer(a, a.model);
            showTrainer();
        }
    }
}
