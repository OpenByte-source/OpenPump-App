package org.openpump;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.view.Gravity;
import android.view.MotionEvent;
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

/** THE SESSION SUMMARY and the review wizard that files it.
 *  Lifted out of SessionActivity verbatim - see
 *  docs/superpowers/plans/2026-09-07-sessionactivity-split.md. A screen class
 *  renders into the Activity's own body column and owns no state. */
final class SummaryScreen {
    private final SessionActivity a;

    SummaryScreen(SessionActivity a) { this.a = a; }

    /** AS-7: set when this render drew the "Save changes ›" door, so Done is not a second
     *  filled primary beside it. Reset at the top of every renderSummary. */
    private boolean saveDoorShown;

    /**
     * The session summary, per proto/pump-console.html's #v-summary / renderSummary().
     * Split out of finishSession() so it can be redrawn after a round trip to the
     * after-measurement screen (reached via "Log measurements ›" below), which mirrors
     * the prototype's v-summary offering afterMeasure() regardless of ABORTED state.
     *
     * Everything on this screen is read from `filedSession` — the record already written
     * to History — so the screen and the History row are the same numbers by
     * construction, not two derivations that happen to agree today (defect #11, and
     * defect #02, which is what happens when they stop agreeing). The prototype read
     * peak, dose and duration off the ROUTINE instead and printed them under a card
     * headed "Delivered"; see task-11-addendum.md for the rule that replaces that.
     */
    void renderSummary() {
        // The self-test's run has ended. Its ending is the REPORT, not the summary of a
        // session that was never the person's — and it is judged here, from the recording
        // captured before the run screen came down, because a phase's answer must not be
        // decided by the code that produced its rows.
        if (a.selfTesting) {
            a.ui.removeCallbacks(a.stTick);
            a.captureRows();
            a.judgeVesselPhases();
            a.renderHwReport();
            // `selfTesting` stays TRUE across the closing vent: the stop finishSession just
            // armed is not evidenced yet, V13 is exactly that question, and stillUnsafe()
            // must keep saying so until it is. stTick re-renders the report once it lands.
            a.ui.postDelayed(a.stTick, a.ST_TICK_MS);
            return;
        }
        a.body.removeAllViews();
        saveDoorShown = false;
        a.enterFlow(Nav.SCR_SUMMARY, Nav.STEP_SUMMARY);
        Ui.head(a, a.body, a.sessionAborted ? "Session stopped" : "Session complete");
        /* (the safety review of the in-run Hold's limit) WHY IT STOPPED, IN ONE LINE THAT
         * STAYS. The Hold's limit and the two-hour stop said it in a snackbar gone in three
         * seconds - over a locked phone, never seen. Read from the record (C5), so a summary
         * reopened from History says it of its own session and no other (invariant 134). */
        String stopWhy = a.filedSession == null ? null
            : RunStopReason.line(a.filedSession.stopWhy, a.filedSession.stopLimSec);
        if (a.sessionAborted && stopWhy != null) Ui.note(a, a.body, stopWhy);
        /* ONE SESSION, SAID ONCE (RunParts, the owner's pick on the leftovers, option A): a run
         * rejoined after the app closed, or resumed after STOP, is filed as one session, its
         * parts added together; this line says it was. From the record, so History says it. */
        String rejoined = a.filedSession == null ? null
            : RunParts.line(a.filedSession.rejoins, a.filedSession.resumes);
        if (rejoined != null) Ui.note(a, a.body, rejoined);
        /* IT TOOK THE SCREEN, SO IT SAYS WHY IT TOOK THE SCREEN.
         *
         * A screen that replaces another without a tap has to account for itself, or it
         * reads as the app losing its place. This one has a good reason and can give it:
         * the cuff is still under pressure and this is where that ends. Only shown when the
         * summary actually interrupted something - arriving here from the run screen is
         * where the run screen was always going, and explaining that would be noise. */
        if (a.summaryTookScreen == a.SUMMARY_TOOK_HOLDING)
            Ui.note(a, a.body, "This opened on its own because the cuff is still holding "
                + "for the after measurement — releasing it is on this screen.");
        if (a.interruptedDialog) {
            Ui.note(a, a.body, "A dialog you had open was closed to get here. Nothing it "
                + "was editing was saved.");
            a.interruptedDialog = false;
        }
        /* SAID ONCE, on the summary that caused it. The KEPT flag itself lives until the
         * measurement screen consumes it, which may be several screens later - so a
         * separate one-shot says it here, or every summary from now until you go back would
         * repeat a sentence about an interruption that happened days ago. */
        if (a.logRowsInterruptedSay) {
            Ui.note(a, a.body, "The measurement you were entering was kept — it is "
                + "still there when you go back to it.");
            a.logRowsInterruptedSay = false;
        }
        a.ventWarnHeadline = null;
        a.ventWarnCaption = null;
        // VENTED_INFERRED still shows the block — it is not proof, and the user is told
        // exactly what it is (ventSayWhy) rather than being shown a green "confirmed".
        if (!Session.VENT_STATE_VENTED.equals(a.sessionVentState)) {
            LinearLayout warn = Ui.col(a);
            warn.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
            Ui.lift(a, warn, Ui.ELEV_CARD);
            warn.setPadding(Ui.dp(a, 11), Ui.dp(a, 9), Ui.dp(a, 11), Ui.dp(a, 9));
            a.ventWarnHeadline = new TextView(a);
            a.ventWarnHeadline.setText(a.ventSayHead(a.sessionVentState));
            a.ventWarnHeadline.setTextColor(a.ventSayColor(a.sessionVentState));
            a.ventWarnHeadline.setTextSize(Look.SP_CAPTION);
            TextView cap = new TextView(a);
            a.ventWarnCaption = cap;
            cap.setText(Say.sentence(a.ventSayWhy(a.sessionVentState)));
            cap.setTextColor(Ui.DIM);
            cap.setTextSize(Look.SP_MICRO);
            cap.setPadding(0, Ui.dp(a, 2), 0, 0);
            warn.addView(a.ventWarnHeadline);
            warn.addView(cap);
            LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            warnLp.bottomMargin = Ui.dp(a, 8);
            a.body.addView(warn, warnLp);
        }
        // An interrupt lands here (SCR_SUMMARY): if the closing vent is only inferred the
        // watch exhausts and ventUnevidenced() latches stillUnsafe() — the SAME "I can see
        // the cuff is vented" affordance the run screen offers must be present, or every
        // next action reports "still commanding or venting the pump". syncVentSeenBtn keeps
        // it hidden unless ventWatchExhausted, so it never shows on a clean finish.
        a.addVentSeenBtn();
        Model.Sess s = a.filedSession;
        boolean nothingDelivered = s == null || s.presetsDone <= 0;

        summaryChip(s, nothingDelivered);
        tauChip(s);
        /* M1 - THE ORDER OF THIS SCREEN, decided rather than inherited.
         *
         * It used to run in the order things were written into it, so what was added last sat
         * last however urgent it was. Two different jobs were interleaved: some of this screen
         * is PERISHABLE - the measurement wants taking promptly, and the save offer and the
         * training question vanish when the screen is left - and the rest is a REPORT that can
         * be read at any time.
         *
         * Sorting purely by urgency was the obvious fix and it was wrong: it would open a
         * finished session with a list of chores. THE FIGURES ARE THE PAYOFF and they come
         * first. Then what expires. Then what is worth recording while it is fresh. Then the
         * rest.
         *
         *   the verdict chips  ->  what was delivered  ->  before and after
         *   BEFORE YOU LEAVE   ->  measure, save, count it
         *   HOW IT WENT        ->  how it felt, anything you noticed
         *   the streak, and Done
         */

        // ── Delivered ────────────────────────────────────────────────────────────
        // Every figure in this block came from the session that ran: the elapsed the
        // run screen displayed, the presets actually started, telemetry's own peak, and
        // the dose integrated across the samples that carried a reading. Nothing here is
        // read off the routine (defects #06/#07/#08).
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 13), Ui.dp(a, 12), Ui.dp(a, 13), Ui.dp(a, 12));
        a.countedForCard();
        a.cockringCard();
        a.howDidItGoCard();
        a.tractionAftercareCard();
        cardTitle(card, "Delivered");

        // B3 - PLANNED AGAINST DELIVERED, IN THE COUNT SET TIMING NAMES. By the
        // clock, the duration row carries the routine's own duration beside the elapsed (a
        // stopped run's shortfall, an extended one's excess); timed at pressure, it states
        // the clock and the comparison is the time-under-pressure row further down. One
        // derivation for both rows and for a summary reopened from History: the record.
        PlannedTime.Rows vs = s == null ? null : PlannedTime.rows(a.model, s, a.model.tupTiming);
        String dur = vs != null ? vs.duration : Model.Fmt.t(a.sessionElapsedSec);
        /* THE PRESET COUNT IS GONE FROM THIS LINE.
         *
         * It was true and it answered a question nobody on this screen asks. A preset is a
         * wire slot: a warm-up ramp is four of them and a ten-cycle work block is one, so
         * "5 of 5 presets" reported the shape of the routine rather than anything about the
         * session. Worse on the run it mattered most for - stop at twelve minutes of thirty
         * and it still reads 5 of 5, because every preset WAS armed, while the cycles line
         * directly beneath said 8 of 14 and was the true account.
         *
         * Nothing is lost: cycles below reports delivery, and the duration pair reports
         * time. The preset count remains on the session record and in the as-run detail,
         * where it describes what was sent to the pump - which is what it is for. */
        // AS-6: a fact row, as on every other card - "Duration  5:00", not "duration   5:00".
        factRow(card, "Duration", dur, Ui.TEXT);

        // O6 — PLANNED VERSUS DELIVERED, the question a stopped-early session actually
        // raises. From the record (PlannedTime#cycles), and only when it holds both halves:
        // a line reading "— of 5" would be a report about a recording that does not exist.
        // It was read from the Activity's recording and plan, which are the LAST live run's,
        // so a summary reopened from History showed that run's cycles (C5).
        if (vs != null && vs.cycles != null)
            // A KNOWN FIGURE ON THIS CARD IS Ui.TEXT, exactly as duration, dose and peak
            // are. Amber on a shortfall is amber used as a warning about a filed record.
            factRow(card, "Cycles", vs.cycles, Ui.TEXT);
        // D2 - THE SETS THAT ENDED AT THEIR TIME LIMIT. The run screen says it as each one
        // ends, and a routine's last set ends into this screen and its count question, which
        // draw over that sentence - so it is said here as well, from the record.
        if (vs != null && vs.limit != null) a.fact(card, "", vs.limit, Ui.TEXT);

        boolean measured = s != null && s.peakKpa != null;
        /* AS-4: ONE SESSION, ONE DOSE FIGURE. This printed kPa·s while the Sessions list
         * printed the same session through Model.Fmt.dose - two numbers for one run. It is
         * Fmt.dose here as there; the floor ("above 10 kPa") is method, and it is said in
         * the Dose and peak ⓘ just below. */
        TextView doseFact = factRow(card, "Dose",
             measured ? Model.Fmt.dose(s.doseKpaS) : "—",
             measured ? Ui.TEXT : Ui.DIM);
        // The dose is the round-4 P6 example verbatim ("dose numbers" ease in): a
        // freshly-built summary card used to show the final integrated dose already
        // sitting there the instant the screen arrived, with no build-up at all. Peak and
        // duration above stay exactly as they render today — they are filed facts read
        // straight off the session, not a number this screen computes from two others.
        if (measured) tweenDose(doseFact, s.doseKpaS);
        factRow(card, "Peak", measured ? Model.Fmt.p(s.peakKpa.doubleValue()) : "—",
             measured ? Ui.TEXT : Ui.DIM);
        // PlannedTime's two rows, the time row as a fact row and the level target as the
        // DIM line under it, both in sentence case (PlannedTime keeps its own words).
        if (vs != null && vs.tupLabel != null)
            factRow(card, capFirst(vs.tupLabel), vs.tupValue, Ui.TEXT);
        if (vs != null && vs.levelLabel != null)
            a.fact(card, "", capFirst(vs.levelLabel) + ": " + vs.levelValue, Ui.DIM);
        if (!measured)
            // THE SENTENCE WAS FALSE on the commonest path that reaches it. An attempt
            // abandoned in the seal check never calls Session#beginRun, so no sample was
            // ever ATTRIBUTED to a session — but plenty of telemetry arrived, and the user
            // watched it on the seal-check screen a moment earlier. Telling them none came
            // is a claim about the hardware that the app has no basis for. Only say
            // "no telemetry" when the run genuinely started and saw zero samples. The reason
            // is filed with the session (Summary#noPeakLine), so a reopened one gives its own.
            a.fact(card, "", Summary.noPeakLine(s), Ui.DIM);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = Ui.dp(a, 10);
        a.body.addView(card, cardLp);
        a.weekAfterSession(nothingDelivered);
        // The body's own numbers belong with the pump's: both are what this session
        // produced, and an empty "after" here is the best argument for the Measure card
        // directly below it.
        a.beforeAfterCard(s);
        Ui.noteInfo(a, a.body, "Dose adds up the measured pressure above 10 kPa over the run.",
            "Dose and peak", "Dose adds up the measured pressure above 10 kPa across every "
             + "reading that carried one — a gap where the pump reported no measurement is left "
             + "out, never filled in. Peak is the deepest reading the pump reported, not the "
             + "routine's target.");

        // The tape measure gets its OWN card, in the body-measurement colour — it used to
        // be one "after" line inside Delivered, a block whose every other figure is a
        // pressure the pump reported. Before and after side by side, the change beneath,
        // and the real reason the pair is or is not like-for-like.
        // Save what you ran (§4/§5): the card, the quiet line, or nothing — below the
        // numbers and Before → after, above the feel chips and Done.
        /* BEFORE YOU LEAVE - everything on this screen that does not survive it. Named for
         * what is true about the group rather than for how urgent it is: the measurement is
         * worth taking now, and the save offer and the training question are gone once this
         * screen is.
         *
         * M4 (the owner's item 17): a real section label with the heading role, where it was a
         * caps note a screen reader read as text - and ONLY when something is under it. All
         * three things below can draw nothing (a reopened summary, a manual run, no saved
         * changes, no count to ask), and the heading stood over an empty space. */
        View leave = sectionLabel("Before you leave");
        a.measureAfterCard(s, nothingDelivered);
        a.saveFullScreen = false;
        renderSaveOffer(s);
        a.countTowardTrainingCard();
        a.askCountTowardTraining();
        dropIfNothingUnder(leave);

        View went = sectionLabel("How it went");
        // A note, on the filed record. M4: the one feel question is "How did it go?", higher
        // up - this card's own "How did it feel?" chips were a second one on the same screen.
        noteCard(s);

        noticedCard(s, nothingDelivered);
        dropIfNothingUnder(went);

        // The after-session tape-measure belongs to a routine's measurement cadence; a manual
        // run has none, so it is not offered one — offering it would tie an ephemeral cycle to
        // the baseline machinery it deliberately skips.
        // THE STREAK IS A REWARD, NOT AN ERRAND, so it reads with the rest of the report
        // rather than competing with the things that expire (M1). Completions only, and
        // never for a session that delivered nothing. The number is recomputed FROM THE
        // FILED RECORDS, so it is the streak the app now actually has rather than a preview
        // of one (defect #04). A MANUAL run shows none: it moves no streak, so a streak
        // number here would be a claim it had nothing to do with.
        if (s != null && s.completed && !nothingDelivered && !s.manual) {
            // STAGE H TASK 5: same deload widening as Today's own Summary.of call — this
            // screen's streakBlock() must never disagree with Today's about the same number.
            long[] deloadDays = TrainerTab.deloadDayRange(a.model);
            Summary.Stats st = Summary.of(a.model.sessLog.all, s.ts, a.model.sched,
                deloadDays[0], deloadDays[1]);
            String sub = a.sessionFirstToday
                ? (st.best > st.streak ? (st.best - st.streak) + " to beat your best"
                                        : "a new personal best")
                : "already counted today — a second session does not advance it";
            a.streakBlock(st.streak, "day streak", sub, st.weekDays, st.scheduledDays);
        }

        if (s != null && !s.sim) {
            Button growthTrack = Ui.flat(a, a.body, "GrowthTrack sync status");
            growthTrack.setOnClickListener(new GrowthTrackActivity.OpenTap(a, s));
        }

        boolean man = s != null && s.manual;
        // The after-session measurement now leads the summary rather than closing it - see
        // measureAfterCard(). One action, one place: a second identical button down here
        // would be two doors to a thing that is only worth doing promptly.
        // A TRY LANDS BACK IN THE EDITOR IT WAS LAUNCHED FROM, with the edits it ran still
        // there — the point of trying a set is to change it and try again, and a Done that
        // dropped the person on Today would make that a five-tap round trip. Only for a
        // manual run (a try IS one) and only while the set still exists.
        boolean backToEditor = man && a.tryReturnSetId != null && a.model.set(a.tryReturnSetId) != null;
        // AS-7: ONE FILLED PRIMARY. While "Save changes ›" shows, it is the screen's lime
        // primary and Done is the quieter button under it; otherwise Done keeps its fill.
        Button done = Ui.big(a, a.body, backToEditor ? "Back to the set" : "Done",
                             saveDoorShown ? Ui.SURFHI : Ui.GOOD);
        done.setOnClickListener(backToEditor
            ? (View.OnClickListener) a.new TryReturnTap(a.tryReturnSetId) : a.new Tap(SessionActivity.Tap.HOME));
        a.tryReturnSetId = null;      // consumed — a later run must not inherit this landing
        // M4: the tag as it is SHOWN (Summary#tagShown), the stored words untouched.
        String filedAs = Summary.tagShown(s == null ? Summary.TAG_STOPPED : s.tag);
        // PR-4: the list is called Sessions everywhere, so this says Sessions too.
        Ui.noteInfo(a, a.body,
            "Filed to Sessions as \u201c" + filedAs + "\u201d",
            "How this is filed",
            "Filed to Sessions as \u201c" + filedAs + "\u201d" + (man
                ? ". A manual run is recorded but does not count toward your streak or routine "
                  + "statistics." : a.sessionAborted
                ? ". A stopped session is recorded as an attempt, not a completion, and does "
                  + "not count toward the streak." : "."));
        // M4 (the owner's choice for item 17): NO FILE PATH HERE. "Log: /storage/emulated/0/
        // Android/data/…" is a developer's fact, and on a summary reopened from History it was
        // not even this session's log - it is the app's current one. The path lives in
        // Settings › Diagnostics (SettingsScreen). Invariant 68.
        // AS-8: and no line saying where, either - it was a developer's pointer on every
        // summary. Diagnostics is where it always was.
    }

    /** M4 - a summary section's label: the drawing's uppercase 12 sp label, with the
     *  heading role a caps note never had. See dropIfNothingUnder. */
    private View sectionLabel(String label) {
        View v = Ui.fieldLabel(a, a.body, label, null);
        v.setPadding(0, Ui.dp(a, Look.S6), 0, Ui.dp(a, Look.S3));
        if (android.os.Build.VERSION.SDK_INT >= 28) v.setAccessibilityHeading(true);
        return v;
    }

    /** M4 - no empty section headings: a label with nothing drawn after it is removed. */
    private void dropIfNothingUnder(View label) {
        if (a.body.indexOfChild(label) == a.body.getChildCount() - 1) a.body.removeView(label);
    }

    /** The summary's headline chip: what this session WAS. Every branch describes
     *  something that actually happened — there is deliberately no branch that reports
     *  a response reading, because this app measures no τ; claiming one would be the
     *  configured-number-wearing-a-delivered-label problem in its purest form. */
    private void summaryChip(Model.Sess s, boolean nothingDelivered) {
        if (nothingDelivered) {
            // "nothing was commanded" was NOT TRUE of every session that reaches here.
            // nothingDelivered is presetsDone <= 0, and an abort during the SEAL CHECK has
            // no presets delivered while the seal check itself commanded and held
            // min(20, ceiling) on the person — real pressure, vented on the way out. The
            // chip now says only what presetsDone actually establishes.
            Ui.chip(a, a.body, "Nothing delivered",
                "The routine never got as far as its first step, so there is no delivered "
                + "pressure to report. A seal check that ran before it is not counted here.",
                Ui.SURF, Ui.DIM);
        } else if (s != null && !s.completed) {
            Ui.chip(a, a.body, "Stopped early",
                "Partial session — recorded as an attempt.",
                // DIM, like its three siblings. cardTitle's own doc on this screen: a
                // summary exists BECAUSE the pump is vented, so amber here is the one
                // reading it can never have - and it weakens the amber everywhere else.
                Ui.SURF, Ui.DIM);
        } else if (s != null && s.afterLenCm == null && s.afterGirCm == null) {
            // THE TAPE, AND SAID SO. "No after-measurement" sat right above the tissue-
            // adaptation card, whose after-TEST is also a measurement, and read as that test
            // having failed. This is only about length and girth not being logged. (The
            // filed tag keeps its old words; it is data, and History reads it.)
            Ui.chip(a, a.body, "No measurements after this session",
                "You didn't log length or girth after it.", Ui.SURF, Ui.DIM);
        } else if (s != null && !s.afterComparable) {
            // M4 - two ways of measuring, said as what they are (the owner's equal-methods
            // rule, STUDY-19 R21): neither way is the lesser one.
            Ui.chip(a, a.body, "Measured two ways",
                "Before and after were taken different ways, and each is compared with its "
                + "own kind, so no before-to-after change is worked out.", Ui.SURF, Ui.DIM);
        } else if (s != null) {
            Ui.chip(a, a.body, s.tag, Say.sentence(baselineCaption(s)), Ui.SURF,
                    a.toneColour(Summary.tone(s)));
        }
    }

    /**
     * WHICH baseline the after-delta was actually measured against, said out loud.
     *
     * FINAL REVIEW, Important — this caption was the fixed string "measured against the
     * baseline logged before this session". The delta comes from measLog.latestPre(), the
     * newest reading in the WHOLE log, and the default cadence is "every 5 sessions": four
     * sessions in five log no baseline, so the newest reading is routinely days or weeks
     * old. Model.Reading#comparable() compares hold pressure and hold seconds and never a
     * timestamp, so an old baseline passes as fully like-for-like and the chip presented a
     * multi-session accumulated change as this session's acute one — the opposite of what
     * the note on the measure-after screen tells the user to believe, and the same
     * sentence was persisted into History with the row.
     *
     * Three cases, three different claims, and the third claims nothing it cannot support:
     * this session's own baseline, an older one (named by age, from the record), or a row
     * filed before the baseline was recorded at all.
     */
    private String baselineCaption(Model.Sess s) {
        // S13 (c) (the owner's decision, 2026-09-26): a girth baseline taken soon after a
        // length session is AFTER OTHER WORK - said here, as it is left out of the yield.
        if (s.afterBaseThisSession && TrainerTab.baselineAfterOtherWork(a.model, s))
            return "measured against the baseline logged before this session — after "
                 + "other work: a length session ended less than "
                 + (TrainerTab.AFTER_OTHER_WORK_MS / 3600000L) + " h before it, so the tissue "
                 + "was already expanded, and this session is left out of your girth yield";
        if (s.afterBaseThisSession)
            return "measured against the baseline logged before this session";
        if (s.afterBaseTs > 0) {
            // TASK 4 / PD-5: "logged X days ago" reflects the baseline's CURRENT ts —
            // resolveAfterBase() follows a corrected date, afterBaseTs alone would not.
            Model.Reading base = a.resolveAfterBase(s);
            long ago = base != null ? base.ts : s.afterBaseTs;
            return "measured against your most recent baseline, logged " + a.agoText(ago)
                 + " — not a baseline from this session, so this is the change since then, "
                 + "not the change this session produced";
        }
        return "measured against your most recent baseline — which session that baseline "
             + "belongs to was not recorded for this row";
    }

    /**
     * THE NOTE, as a field - "Add a note", or the note itself - written straight onto the
     * FILED record and persisted on save. The note goes through a dialog, not an inline field
     * - the summary rebuilds on every tap, which would drop the keyboard out from under an
     * inline EditText (RenameConfirm's rule).
     *
     * M4 (the owner's item 17): THE "HOW DID IT FEEL?" CHIPS ARE GONE. The screen asked two
     * feel questions, "How did it go?" (Felt great / Felt ok / Too much, which tunes the plan)
     * and this one (easy / fine / tough, which tuned nothing); the owner kept the first. A
     * session answered on an earlier build keeps its answer on the record - History's note
     * search and the export still read it - and it is said here, read-only, so nothing the
     * summary showed for that session is lost.
     */
    private void noteCard(Model.Sess s) {
        if (s == null) return;
        if (s.feel != 0) {
            String word = Export.feelWord(s.feel);
            if (word.length() > 0) {
                TextView was = new TextView(a);
                was.setText("You said it felt " + word + ".");
                was.setTextColor(Ui.DIM);
                was.setTextSize(Look.SP_CAPTION);
                was.setPadding(0, 0, 0, Ui.dp(a, Look.S2));
                a.body.addView(was);
            }
        }
        boolean has = s.note != null && s.note.length() > 0;
        TextView field = new TextView(a);
        field.setText(has ? s.note : "Add a note");
        field.setTextColor(has ? Ui.TEXT : Ui.FAINT);
        field.setTextSize(Look.SP_BODY);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setMinHeight(Ui.dp(a, 48));
        field.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, Look.S3), Ui.dp(a, Look.S5), Ui.dp(a, Look.S3));
        field.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL));
        field.setClickable(true);
        field.setFocusable(true);
        field.setContentDescription(has ? "Note: " + s.note + ". Double tap to edit."
                                        : "Add a note about this session");
        field.setOnClickListener(new SessNoteTap());
        field.setOnTouchListener(new Ui.Press());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, Look.S4);
        a.body.addView(field, lp);
    }

    /** A dialog, not an inline field — the same reasoning as RenameSetTap. Staged: only
     *  "Save" commits the note; Cancel leaves the record as it was. */
    private final class SessNoteTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.filedSession == null) return;
            EditText input = new EditText(a);
            input.setHint("a note about this session");
            input.setText(a.filedSession.note);
            input.setSelection(input.getText().length());
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Note")
                .setView(input)
                .setPositiveButton("Save", new SessNoteConfirm(input))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class SessNoteConfirm implements DialogInterface.OnClickListener {
        private final EditText input;
        SessNoteConfirm(EditText e) { input = e; }
        @Override public void onClick(DialogInterface d, int w) {
            if (a.filedSession == null) return;
            a.filedSession.note = input.getText().toString().trim();
            Store.save(a, a.model);
            renderSummary();
        }
    }

    /**
     * THE TISSUE RESPONSE TEST'S CARD - deliberately separate from summaryChip() above, which
     * is about the after-MEASUREMENT (tape measure). Two different things, kept apart in code
     * and in copy.
     *
     * M4 (the owner's item 10): it was a chip headed with symbols ("Δτ +4%") over thirty words
     * of qualification, and nothing said whether a change that size meant anything. Now the
     * card is named for the test, its title is the two fill times, and one line says the
     * change and what a change of that size means against normal variation (Tau#NOISE_PCT) -
     * all TauSay's words, the same ones History and the Sessions list use. The settings the
     * test ran at, and what the number is and is not, are behind "What this measures".
     *
     * Every honest outcome still shows whichever one happened: a change, two times that did
     * not match (with the pressures that did not), one end alone, or no number and why.
     * Nothing here paints a direction as good or bad: which way is "better" is not something
     * this app knows.
     */
    private void tauChip(Model.Sess s) {
        if (!TauSay.ran(s)) return;              // the test never ran - say nothing
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, Look.S5), Ui.dp(a, Look.S5), Ui.dp(a, Look.S2));
        Ui.fieldLabel(a, card, TauSay.NAME, null);
        TextView title = new TextView(a);
        title.setText(TauSay.title(s));
        title.setTextColor(Ui.TEXT);
        title.setTextSize(Look.SP_HEADING);
        Ui.tabular(title);
        Ui.semibold(title);
        title.setPadding(0, Ui.dp(a, Look.S1), 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        card.addView(title);
        String verdict = TauSay.verdict(s);
        if (verdict.length() > 0) {
            TextView v = new TextView(a);
            v.setText(verdict);
            v.setTextColor(Ui.TEXT);          // PR-12: prose in TEXT, at the body size
            v.setTextSize(Look.SP_BODY);
            v.setLineSpacing(0f, 1.2f);   // the drawing's 1.4 line height over the font's own
            v.setPadding(0, Ui.dp(a, Look.S1), 0, 0);
            card.addView(v);
        }
        // AND THE PULLS THEMSELVES, when this session kept them. Older sessions load null
        // here and the card is the words alone.
        Store.TauTrace tr = Store.loadTau(a, s.ts);
        if (tr != null && (tr.has(false) || tr.has(true))) {
            // C8: the session's own pull duration, in what the chart says (not "45 s").
            TauPullChart chart = new TauPullChart(a, tr, s,
                Summary.tauChartNote(tr.has(false) && tr.has(true), s.assessDurSec));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 124));
            lp.topMargin = Ui.dp(a, Look.S4);
            card.addView(chart, lp);
            tauLegend(card, tr.has(false), tr.has(true));
        }
        LinearLayout about = new LinearLayout(a);
        about.setOrientation(LinearLayout.HORIZONTAL);
        about.setGravity(Gravity.CENTER_VERTICAL);
        about.setMinimumHeight(Ui.dp(a, 48));
        TextView aw = new TextView(a);
        aw.setText("What this measures");
        aw.setTextColor(Ui.ACCENT);
        aw.setTextSize(Look.SP_CHIP);
        Ui.medium(aw);
        about.addView(aw);
        android.widget.ImageView ai = Ui.iconView(a, R.drawable.ic_info, Ui.ACCENT, 16);
        ((LinearLayout.LayoutParams) ai.getLayoutParams()).leftMargin = Ui.dp(a, Look.S2);
        about.addView(ai);
        Ui.group(about, "What this measures. Opens an explanation.");
        about.setOnClickListener(new AboutTauTap(TauSay.about(s.assessKpa, s.assessSp,
                                                              s.assessDurSec)));
        about.setOnTouchListener(new Ui.Press());
        card.addView(about, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.dp(a, Look.S4);
        a.body.addView(card, clp);
    }

    /** The chart's legend, in words: which line is which, and what the dots are. */
    private void tauLegend(LinearLayout card, boolean before, boolean after) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, Look.S2), 0, 0);
        if (before) legendKey(row, true, "before");
        if (after) legendKey(row, false, "after");
        View gap = new View(a);
        row.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView d = new TextView(a);
        d.setText("dot: the fill time");
        d.setTextColor(Ui.DIM);
        d.setTextSize(Look.SP_CAPTION);
        row.addView(d);
        // Decoration to a reader: the chart itself says which time is which.
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        card.addView(row);
    }

    private void legendKey(LinearLayout row, boolean dashed, String word) {
        View key = new View(a);
        key.setBackground(new LegendLine(dashed ? TauPullChart.BEFORE_INK : Look.COMMANDED,
                                         dashed, a.getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 6));
        klp.rightMargin = Ui.dp(a, Look.S2);
        row.addView(key, klp);
        TextView t = new TextView(a);
        t.setText(word);
        t.setTextColor(Ui.DIM);
        t.setTextSize(Look.SP_CAPTION);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = Ui.dp(a, Look.S5);
        row.addView(t, tlp);
    }

    /** One legend swatch: a short line, dashed for the before pull. */
    private static final class LegendLine extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        LegendLine(int colour, boolean dashed, float density) {
            p.setColor(colour);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth((dashed ? 1.8f : 2.4f) * density);
            if (dashed) p.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{ 5 * density, 4 * density }, 0));
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float y = b.exactCenterY();
            c.drawLine(b.left, y, b.right, y, p);
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            p.setColorFilter(cf);
            invalidateSelf();
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** "What this measures": the test, and what its number is and is not, as a dialog. */
    private final class AboutTauTap implements View.OnClickListener {
        private final String text;
        AboutTauTap(String t) { text = t; }
        @Override public void onClick(View v) {
            if (a.isFinishing()) return;
            AlertDialog d = Ui.dialog(a)
                .setTitle(TauSay.NAME)
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
            Ui.dress(a, d);
            TextView msg = d.findViewById(android.R.id.message);
            if (msg != null) msg.setLineSpacing(0f, Look.PROSE_LINE_MULT);
        }
    }

    /** The summary's slot (below Before → after, above the feel chips): decides between
     *  nothing, the quiet line, the "REVIEW & SAVE ›" door into the wizard — or the
     *  collapsed "Saved ✓" line once a save happened. The wizard replaced the inline
     *  card (W1): everything over the threshold goes through the one lime door, the
     *  skip-only one-question card included (its DROP/KEEP question is now the wizard's
     *  skipped-block step). */
    private void renderSaveOffer(Model.Sess s) {
        if (s == null || !s.hasAsRun) return;
        // THIS SESSION'S OWN RECORDING, as History's own door loads it (openReviewWizardFor).
        // It was the Activity's `asRun` - the last live run's - so a summary reopened from
        // History offered to save another run's blocks (C5). The live run's is the same
        // recording: fileSession wrote it under this ts before the summary was drawn.
        if (a.saveSess != s) a.prepareSaveCard(s, Store.loadAsRun(a, s.ts));
        if (s.savedAt > 0) {
            savedLineSaveCard(a.body);
            // The door stays open while any savable block is still unsaved (§5 reopen): a
            // save of one block used to close the offer on the other five for good.
            if (!a.saveDismissed && !AsRun.allSaved(a.saveDraft, a.saveBlocks)) reviewSaveDoor();
            return;
        }
        if (a.saveDismissed) return;
        switch (a.saveOffer) {
            case AsRun.OFFER_QUIET:     renderQuietLine(); break;
            case AsRun.OFFER_CARD:
            case AsRun.OFFER_SKIP_ONLY: reviewSaveDoor(); break;
            default: break;
        }
    }

    /** The one door into the wizard from the summary: a single lime primary in the slot
     *  the inline card used to fill. Vent verdict, Delivered, Log/Done stay where they
     *  are on the summary — only the review moved behind this button. */
    private void reviewSaveDoor() {
        saveDoorShown = true;   // AS-7: Done draws as the quieter button under this one
        Button b = Ui.big(a, a.body, "Save changes ›", Ui.ACCENT);   // M4: sentence case
        // The quiet line's "Save what you ran" pattern — TalkBack must never read "›".
        b.setContentDescription("Save changes from this run");
        b.setOnClickListener(new ReviewWizardTap());
    }

    /** §4's quiet line: "You nudged Work by 0.3 inHg · save ›" — drawing only. */
    private void renderQuietLine() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, row, Ui.ELEV_CARD);
        row.setPadding(Ui.dp(a, 12), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
        TextView t = new TextView(a);
        t.setText(a.saveChange == null ? "" : a.saveChange.quietText);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(Look.SP_CAPTION);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button save = new Button(a);
        save.setText("save ›");
        save.setAllCaps(false);
        save.setTextSize(Look.SP_LABEL);
        save.setTextColor(Ui.ACCENT);
        save.setBackground(Ui.roundRect(a, Ui.SURFHI, 11));
        save.setMinHeight(Ui.dp(a, 48));   // the app's touch-target floor
        save.setContentDescription("Save what you ran");
        save.setOnTouchListener(new Ui.Press());
        save.setOnClickListener(new ReviewWizardTap());
        row.addView(save);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 10);
        a.body.addView(row, lp);
    }

    /** The summary's door — since item 11 it opens the ONE-SCREEN "Save changes" page,
     *  not the retired per-block wizard. */
    private final class ReviewWizardTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.saveFullScreen = false;
            openSaveChanges(false);
        }
    }

    /** The name a wizard step leads with: the stage for a wholly skipped stage, else the
     *  set — falling back to the stage where a deleted set left the name empty. */
    /**
     * THE W1 REVIEW WIZARD (dist/final-design.html section 3) — one block per step over
     * the SAME AsRun draft the old inline card edited. Steps are saveBlocks indices
     * 0..N-1, then step N is the confirm step. Renders, top to bottom: the mock's header
     * line ("REVIEW n/N" · "<NAME> · <KIND>"), the steps progress bar, the step's own
     * panel (Tasks 2-4 fill it further: overlay chart, edit flow, diff rows), the
     * "what's next" line, and the BACK/NEXT row.
     *
     * A real screen in the Nav flow: Nav.SCR_SAVE_CARD, the id the retired full-screen
     * card owned — a Progress screen whose Back parent is History. Hardware Back IS the
     * footer's "‹ BACK" (backAction below, the same object), so the key and the button
     * can never drift: a step back on step > 0, and on step 0 the exit — the summary, or
     * History when the wizard was entered through a History row (saveFullScreen).
     */
    void showReviewWizard() {
        a.body.removeAllViews();
        // Task 2's carried constraint, answered per render: the chart handle is real ONLY
        // for a step that just drew one. Nulling it here means the confirm step and the
        // plan-less skipped step hold null — a setValues caller can never reach a previous
        // step's detached chart (wizardApplyEdit null-checks before calling).
        a.wizardChartView = null;
        a.enterDest(Nav.SCR_SAVE_CARD);
        a.backAction = new WizardBackTap();       // hardware Back == "‹ BACK"
        int n = a.saveBlocks.size();
        if (a.saveDraft == null || a.saveSess == null || n == 0) {
            Ui.head(a, a.body, "Review & save");
            Ui.note(a, a.body, "This session didn't keep a step-by-step record, so there's nothing to save from it.");
            Ui.row(a, a.body, new String[]{ "‹ BACK" },
                new View.OnClickListener[]{ new WizardBackTap() });
            return;
        }
        // A fully-saved run collapses to ONE screen — the retired full-screen card's own
        // behaviour (e73fd68 renderSaveCardScreen): the Saved ✓ line with its Library
        // deep-link, and the way out. Walking N steps whose every decision is a no-op
        // would be noise. Only reachable from a History row: the summary's door
        // (renderSaveOffer) already hides behind the same allSaved check.
        if (a.saveSess.savedAt > 0 && AsRun.allSaved(a.saveDraft, a.saveBlocks)) {
            Ui.head(a, a.body, "Review & save");
            if (a.saveFullScreen) wizardIdentityLine();
            savedLineSaveCard(a.body);
            Ui.note(a, a.body, "Everything here is in your library — nothing left to review.");
            Ui.row(a, a.body, new String[]{ "‹ BACK" },
                new View.OnClickListener[]{ new WizardBackTap() });
            return;
        }
        if (a.wizardStep < 0) a.wizardStep = 0;
        if (a.wizardStep > n) a.wizardStep = n;
        boolean confirm = a.wizardStep == n;

        // Entered from History, the summary's context is a screen away — say WHICH
        // session this is, the way the old full-screen title did (routine · day).
        if (a.saveFullScreen) wizardIdentityLine();

        // Header — the mock's hdr: "REVIEW n/N" bold left, "<NAME> · <KIND>" cap right.
        LinearLayout hdr = new LinearLayout(a);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView ht = new TextView(a);
        ht.setText(confirm ? "REVIEW · SAVE" : "REVIEW " + (a.wizardStep + 1) + "/" + n);
        ht.setTextColor(Ui.TEXT);
        ht.setTextSize(Look.SP_LABEL);
        ht.setTypeface(ht.getTypeface(), android.graphics.Typeface.BOLD);
        ht.setLetterSpacing(Look.LABEL_TRACKING_EM);
        if (android.os.Build.VERSION.SDK_INT >= 28) ht.setAccessibilityHeading(true);
        hdr.addView(ht, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String cap = confirm
            ? (a.saveDraft.manual ? "Manual" : a.saveSess.routineName)
            : Say.stepName(a.saveBlocks, a.saveBlocks.get(a.wizardStep)) + " · " + Say.stepKind(a.saveBlocks.get(a.wizardStep));
        Ui.microLabel(a, hdr, cap, Ui.DIM);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(a, Look.S2);
        hlp.bottomMargin = Ui.dp(a, Look.S3);
        a.body.addView(hdr, hlp);
        String said = confirm ? "Review, final step: save"
            : "Review step " + (a.wizardStep + 1) + " of " + n + ": " + cap;
        Ui.group(hdr, said);
        // AND SPOKEN ON ARRIVAL. The header is one focus stop, but replacing it says
        // nothing: a step advanced by tapping a decision button moved the whole page under
        // a reader with no announcement of where it had gone. Ui.snack is the only other
        // place in the app that has to announce a view it built by hand, for the same
        // reason (see its own note).
        hdr.announceForAccessibility(said);

        // Steps bar — N equal segments, done and current lime, the rest the raised
        // surface (the mock's `.steps`). Plain drawing; the header already carries the
        // count, so the bar stays out of the accessibility order.
        LinearLayout steps = new LinearLayout(a);
        steps.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < n; i++) {
            View seg = new View(a);
            boolean lit = confirm || i <= a.wizardStep;
            seg.setBackground(Ui.roundRect(a, lit ? Ui.ACCENT : Ui.SURFHI, Look.WIZ_STEP_R_DP));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    0, Ui.dp(a, Look.WIZ_STEP_H_DP), 1f);
            if (i < n - 1) slp.rightMargin = Ui.dp(a, Look.WIZ_STEP_GAP_DP);
            steps.addView(seg, slp);
        }
        steps.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.bottomMargin = Ui.dp(a, Look.S3);
        a.body.addView(steps, stlp);

        // A partially saved run keeps its Library deep-link on the confirm step — the
        // retired full-screen card's own top line ("Saved ✓ · <names> · open ›"), so the
        // History "saved · save more ›" path still has one tap through to what was saved.
        if (confirm && a.saveSess.savedAt > 0) savedLineSaveCard(a.body);

        // The step's own panel — the container Tasks 2-4 render into.
        LinearLayout panel = saveCardFrame(a.body);
        if (confirm) buildWizardConfirm(panel);
        else buildWizardStep(panel, a.wizardStep);

        // "What's next" — the remaining steps in the mock's compact form
        // ("2/4 PULSE·RAMP → 3/4 REST → 4/4 COOL·SKIPPED"), ending at the save.
        if (!confirm) {
            StringBuilder next = new StringBuilder();
            for (int j = a.wizardStep + 1; j < n; j++) {
                if (next.length() > 0) next.append("  →  ");
                next.append(j + 1).append('/').append(n).append(' ')
                    .append(Say.stepName(a.saveBlocks, a.saveBlocks.get(j)))
                    .append('·').append(Say.stepKind(a.saveBlocks.get(j)));
            }
            if (next.length() > 0) next.append("  →  ");
            next.append("SAVE");
            LinearLayout nextRow = Ui.col(a);
            nextRow.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
            nextRow.setPadding(Ui.dp(a, Look.S4), Ui.dp(a, Look.S2),
                               Ui.dp(a, Look.S4), Ui.dp(a, Look.S2));
            Ui.microLabel(a, nextRow, next.toString(), Ui.DIM);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.bottomMargin = Ui.dp(a, Look.S3);
            a.body.addView(nextRow, nlp);
        }

        // The last row: BACK always; NEXT until the confirm step, whose own Save primary
        // (inside the panel) is the way forward.
        if (confirm)
            Ui.row(a, a.body, new String[]{ "‹ BACK" },
                new View.OnClickListener[]{ new WizardBackTap() });
        else
            Ui.row(a, a.body, new String[]{ "‹ BACK", "NEXT ›" },
                new View.OnClickListener[]{ new WizardBackTap(), new WizardNextTap() });

        /* A STEP ARRIVES SHOWING ITS OWN TOP.
         *
         * The mock's wizard is fixed chrome - the header, the step strip and the panel
         * cannot scroll away. Here the whole page is rebuilt inside the app's one
         * ScrollView, which keeps its offset across the rebuild: every decision button
         * sits at the FOOT of a tall step, so tapping one advanced the wizard and left the
         * next step's header, its strip and its chart above the fold. What met the eye was
         * the next block's decision buttons, with nothing on screen saying which block.
         *
         * Only on a genuine page change. The many in-place re-renders - a bump, a slider
         * release, a rename, a mode switch - must NOT jump: a finger is on a control
         * mid-panel. `bodyArrived` is true only when arriving from another screen (see
         * enterDest), and the key distinguishes a step change from an edit-form toggle.
         */
        int wizKey = a.wizardStep * 2 + (a.wizardEditIdx >= 0 ? 1 : 0);
        if (a.bodyArrived || wizKey != a.wizardShownStep) {
            a.wizardShownStep = wizKey;
            View wizTop = a.body.getChildAt(0);
            // Posted, not immediate: getTop() is 0 until the freshly built column has been
            // laid out - the same reason JumpToSettingTap posts its own scroll.
            if (wizTop != null && a.bodyScroll != null) a.bodyScroll.post(a.new ScrollToAnchor(wizTop));
        }
    }

    /** The History-entry identity line — the old full-screen card's title
     *  ("<routine> · <day>", "Manual" for a manual run), kept as a small line over the
     *  step header. Only drawn when the wizard was entered from a History row
     *  (saveFullScreen): from the summary the session IS the screen and needs no naming.
     *  Ellipsized — the routine name is user-typed. */
    private void wizardIdentityLine() {
        TextView t = a.saveCardCaption(a.body,
            (a.saveSess.manual ? "Manual" : a.saveSess.routineName) + " · " + a.dayLabel(a.saveSess.ts),
            Ui.DIM);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    /** "‹ BACK" — and the hardware key, via backAction. A step back, or on step 0 the
     *  exit to wherever the wizard was entered from. */
    private final class WizardBackTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            // Inside ✎ edit mode, BACK closes the editor — not the step, and never the
            // wizard: the values already moved stay pending (each change was written
            // through as it landed); only DONE pre-selects the SAVE decision.
            if (a.wizardEditIdx >= 0) { a.wizardEditIdx = -1; showReviewWizard(); return; }
            if (a.wizardStep > 0) { a.wizardStep--; showReviewWizard(); return; }
            if (a.saveFullScreen) { a.saveFullScreen = false; a.showSessionHistory(); }
            else renderSummary();
        }
    }

    /** "NEXT ›" — forward without changing the decision: every step arrives with the
     *  draft's own current state already selected, so skipping past one changes nothing.
     *  The last block's NEXT is the confirm step. */
    private final class WizardNextTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.wizardEditIdx = -1;     // NEXT past an open editor keeps the pending values
            if (a.wizardStep < a.saveBlocks.size()) a.wizardStep++;
            showReviewWizard();
        }
    }

    /** "saved ›" on a History row / "open ›" after a save: Library, filtered to the sets. */
    private final class OpenSavedSetsTap implements View.OnClickListener {
        private final List<String> ids;
        OpenSavedSetsTap(List<String> ids) { this.ids = ids; }
        @Override public void onClick(View v) {
            a.setsFilter = ids == null || ids.isEmpty() ? null : new ArrayList<String>(ids);
            a.saveFullScreen = false;
            a.showSetsFiltered();
        }
    }

    /** The collapsed card after a save: "Saved ✓ · <names> · open ›". */
    private void savedLineSaveCard(LinearLayout host) {
        Model.Sess s = a.saveSess;
        StringBuilder names = new StringBuilder();
        if (s != null) {
            if (s.savedRoutineId != null) {
                Model.Routine r = a.model.routine(s.savedRoutineId);
                if (r != null) names.append(r.name);
            }
            for (int i = 0; i < s.savedSetIds.size(); i++) {
                Model.Set x = a.model.set(s.savedSetIds.get(i));
                if (x == null) continue;
                if (names.length() > 0) names.append(", ");
                names.append(x.name);
            }
        }
        Button b = Ui.flat(a, host, "Saved ✓" + (names.length() > 0 ? " · " + names : "") + " · open ›");
        b.setTextColor(Ui.GOOD);
        b.setContentDescription("Saved. Open in Library");
        b.setOnClickListener(new OpenSavedSetsTap(s == null ? null : s.savedSetIds));
    }

    /** The wizard panel's rounded surface, added to the host. */
    private LinearLayout saveCardFrame(LinearLayout host) {
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 13), Ui.dp(a, 12), Ui.dp(a, 13), Ui.dp(a, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 10);
        host.addView(card, lp);
        return card;
    }

    /** One FULL sentence per cantUpdateWhy — never the routine name spliced in front of a
     *  clause it does not parse with ("Update is off — Warm + Build the run was stopped
     *  early"). Only the edited case names the routine, because only there is the routine
     *  the subject of the sentence. */
    private String cantUpdateSentence(String why, String routineName) {
        if (why == null) why = "";
        if (why.startsWith("the routine was edited"))
            return "Update is off — " + routineName + " was edited since this run.";
        if (why.startsWith("the run was stopped early"))
            return "Update is off — the run was stopped early.";
        if (why.startsWith("this run was recorded without its routine"))
            return "Update is off — this run was recorded without its routine.";
        if (why.startsWith("this routine no longer exists"))
            return "Update is off — this routine no longer exists.";
        return "Update is off — " + why + ".";
    }

    /** One block's step: the caption + plan-under-ran overlay chart (Task 2), the facts,
     *  the fold row for a too-short block, the draft name,
     *  and the decision buttons. Every decision writes the SAME SaveDraft fields the old
     *  card's toggles wrote: DraftBlock.on for save/keep, DraftBlock.drop and
     *  dropStage[] for a skipped block's drop/keep. */
    private void buildWizardStep(LinearLayout panel, int idx) {
        AsRun.Block b = a.saveBlocks.get(idx);
        AsRun.DraftBlock db = a.saveDraft.blocks.get(idx);
        boolean routineMode = !a.saveDraft.manual;
        // A skipped placeholder never gets the facts row — its planned tiles under an
        // "as planned" headline would describe a block that never played (the old card
        // printed its skip caption instead, and so does this step). Its dashed plan-only
        // chart is drawn inside buildWizardSkippedChoices, under its own skip caption.
        if (b.skipped) { buildWizardSkippedChoices(panel, b, db); return; }
        wizardStepChart(panel, b, idx);
        // Task 3: ✎ edit mode swaps everything under the chart for the editable rows —
        // the chart stays, because the live redraw against its frozen PLAN is the point.
        if (a.wizardEditIdx == idx && a.wizardEditFor(idx) != null && db.mergedInto < 0) {
            buildWizardEditRows(panel, b, idx);
            return;
        }
        // Task 4: the facts' value rows are the locked diff rows — struck plan → ran with
        // a pinned pill word — and a pending non-original edit is disclosed INSIDE that
        // display (blue values + the absorbed "✎ edited" caption), so the step never
        // draws a second, competing value readout. A folded block passes no pending
        // record: it saves as part of its neighbour, so "a save files these values"
        // would be untrue of it (same reason the retired standalone caption sat after
        // the merged-return below) — and since MergeTap drops the record and restores
        // the as-ran values at fold time (wizardDropEdit), the mergedInto guard here is
        // belt-and-braces, not the mechanism.
        SessionActivity.WizardEdit fp = a.wizardEditFor(idx);
        a.blockFacts(panel, a.saveBlocks, b, idx, routineMode, db.savedAs,
            fp != null && !fp.atOriginal() && db.mergedInto < 0 ? fp : null,
            a.holdEffStageLine(a.saveSess, b.stageIdx));

        // Too short: fold into a neighbour, when one qualifies — the old card's rule,
        // through the same pinned AsRun.toggleMerge. Not a decision button: folding is a
        // refinement of THIS step, so it re-renders in place rather than advancing.
        int mt = AsRun.mergeTarget(a.saveBlocks, idx, a.model);
        if (b.tooShort && mt >= 0) {
            boolean merged = db.mergedInto == mt;
            Button mb = Ui.flat(a, panel, (merged ? A11y.MARK_ON : A11y.MARK_OFF) + "fold into block " + (mt + 1));
            Ui.markSelection(mb, (merged ? A11y.MARK_ON : A11y.MARK_OFF) + "fold into block " + (mt + 1));
            mb.setOnClickListener(new MergeTap(idx, mt));
        }
        if (db.mergedInto >= 0) {
            a.saveCardCaption(panel, "folded into block " + (db.mergedInto + 1)
                + " — it saves as part of that block", Ui.DIM);
            return;
        }

        if (b.unchanged && routineMode) {
            // Ran exactly as planned: nothing to save under the contract (commit skips an
            // unchanged routine block whatever its toggle says — it keeps its set id), so
            // KEEP AS PLANNED is the one honest decision and arrives pre-selected.
            a.saveCardCaption(panel, "ran exactly as planned — nothing to save from this block", Ui.DIM);
            a.wizardChoice(panel, "KEEP AS PLANNED", true, false, new WizardDecisionTap(a.WD_KEEP, idx));
            wizardEditButton(panel, idx);
            return;
        }

        // Savable: the draft name (a dialog, never inline — the screen rebuilds per tap).
        Button name = Ui.flat(a, panel, "name   " + db.name);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setContentDescription("Set name: " + db.name + ". Tap to rename");
        name.setOnClickListener(new BlockNameTap(idx));

        a.wizardChoice(panel, "SAVE THESE VALUES", db.on, true, new WizardDecisionTap(a.WD_SAVE, idx));
        // A manual block has no plan of its own in the Library to "keep" — the honest
        // words for its off-state are the old switch's "not saved".
        a.wizardChoice(panel, routineMode ? "KEEP AS PLANNED" : "LEAVE IT OUT",
            !db.on, false, new WizardDecisionTap(a.WD_KEEP, idx));
        wizardEditButton(panel, idx);
    }

    /** A skipped block's step — the DROP / KEEP question, on the SAME draft fields the
     *  old card's checkboxes wrote: dropStage[] for a wholly skipped stage, the block's
     *  own drop flag for one skipped set inside a stage that otherwise ran. The old
     *  skip-only one-question card is retired; this step IS that question, per block. */
    private void buildWizardSkippedChoices(LinearLayout panel, AsRun.Block b, AsRun.DraftBlock db) {
        a.saveCardCaption(panel, (b.setName.length() == 0 ? "Set" : b.setName) + " · you skipped it", Ui.DIM);
        // Task 2: what was PLANNED, dashed and grey, with NO ran line — nothing played
        // (the mock's "skipped = dashed only"). Only when there IS a plan to draw: a
        // skipped placeholder without one carries zeros, and a flat zero line would be
        // an invention. No ● caption here — the skip caption above already leads.
        if (b.hasPlan) wizardStepChart(panel, b, b.idx);
        if (a.saveDraft.manual) {
            a.saveCardCaption(panel, "a manual run has no routine to drop it from — nothing to decide here", Ui.DIM);
            return;
        }
        boolean stageSk = AsRun.stageSkipped(a.saveBlocks, b.stageIdx)
                       && b.stageIdx < a.saveDraft.dropStage.length;
        String what = stageSk ? b.stageName : b.setName;
        if (what == null || what.length() == 0) what = stageSk ? "this stage" : "this set";
        boolean drop = stageSk ? a.saveDraft.dropStage[b.stageIdx] : db.drop;
        a.wizardChoice(panel, "DROP " + what.toUpperCase(Locale.US) + " FROM THE ROUTINE", drop, false,
            new WizardDecisionTap(stageSk ? a.WD_DROP_STAGE : a.WD_DROP_SET, stageSk ? b.stageIdx : b.idx));
        a.wizardChoice(panel, "KEEP IT AS PLANNED", !drop, false,
            new WizardDecisionTap(stageSk ? a.WD_KEEP_STAGE : a.WD_KEEP_SET, stageSk ? b.stageIdx : b.idx));
    }

    /**
     * The step panel's caption + overlay chart (Task 2) — the mock's
     * "● WORK · GENTLE HOLD · ADJ @ 2:14" cap line over the plan-under-ran plot
     * (dist/final-design.html section 3). The caption wears the block's own colour — the
     * same Look.BLOCKS[idx % 6] its badge and chart band wear everywhere else, which for
     * step 1 is the mock's exact --blue. The ADJ time is the block's own start: an
     * adjusted block is SPLIT AROUND its adjustment (AsRun.blocks — off-plan value runs
     * begin where their values began playing), so t0 IS the moment the adjusted values
     * took effect. Block.hasOverride is how the card has always surfaced "adjusted"
     * (blockFacts' "after your change"); no OVERRIDE row timestamp is re-derived here.
     *
     * For a SKIPPED placeholder (called from buildWizardSkippedChoices) the ● caption is
     * omitted — the skip caption above it already leads — and the chart draws dashed
     * plan only.
     */
    private void wizardStepChart(LinearLayout panel, AsRun.Block b, int idx) {
        if (!b.skipped) {
            StringBuilder cap = new StringBuilder("● ");
            StringBuilder say = new StringBuilder();
            if (b.stageName != null && b.stageName.length() > 0) say.append(b.stageName);
            if (b.setName != null && b.setName.length() > 0) {
                if (say.length() > 0) say.append(" · ");
                say.append(b.setName);
            }
            if (say.length() == 0) say.append(Say.stepName(a.saveBlocks, b));
            cap.append(say);
            String spoken = say.toString().replace(" · ", ", ");
            if (b.hasOverride) {
                cap.append(" · ADJ @ ").append(Model.Fmt.t(b.t0 / 1000));
                spoken += ", adjusted at " + Model.Fmt.t(b.t0 / 1000);
            }
            TextView t = Ui.microLabel(a, panel, cap.toString(),
                    Look.BLOCKS[idx % Look.BLOCKS.length]);
            // Without the ● glyph, and "ADJ @" said in words — the marker is visual only.
            t.setContentDescription(spoken);
        }

        // Task 3: a block with a pending edit builds the chart from its ORIGINAL values
        // (the basis stand-in), then moves the RAN line to the pending ones through Task
        // 2's own setValues entry point. Constructing from the mutated block instead would
        // collapse a RAMP's divergence — its PLAN ladder is frozen at construction FROM
        // the block's values (on-plan by construction), so the plan must be rebuilt from
        // what actually ran, never from the edit in progress.
        SessionActivity.WizardEdit pe = a.wizardEditFor(idx);
        AsRun.Block basis = pe == null ? b : pe.chartBasis(b);
        SessionActivity.WizardOverlayChart chart = a.new WizardOverlayChart(a, basis);
        chart.setBackground(Ui.roundRect(a, Look.GROUND, Look.WIZ_CHART_R_DP));
        int deep = Math.max(b.up, b.up2);
        String cd;
        if (b.skipped)
            cd = "Chart: the planned " + Model.Fmt.p(deep) + " shape, dashed — nothing ran";
        else if (b.hasPlan)
            cd = "Chart: ran " + Model.Fmt.p(deep) + " over the planned "
               + Model.Fmt.p(b.kind == AsRun.RAMP ? Math.max(basis.up, basis.up2) : b.pUp);
        else
            cd = "Chart: ran " + Model.Fmt.p(deep) + " — no plan to compare against";
        chart.setContentDescription(cd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, Look.WIZ_CHART_H_DP));
        lp.topMargin = Ui.dp(a, Look.S1);
        lp.bottomMargin = Ui.dp(a, Look.S2);
        panel.addView(chart, lp);
        a.wizardChartView = chart;
        if (pe != null) chart.setValues(b.up, b.lo, b.uh, b.lh, b.up2, b.lo2, b.steps,
                                        b.uh2, b.lh2, b.sp2);
    }

    /** "✎ EDIT BEFORE SAVING" — the mock's blue-lettered third choice. Blue is
     *  Look.BLOCKS[0]: the mock's --blue (#5B9CFF) is that exact hex. Offered on EVERY
     *  value block, the unchanged ones included — mock7's locked rule ("want the warm-up
     *  5 s longer next time? edit it right here"). Opens the inline edit rows
     *  (buildWizardEditRows) over this block's pending record. */
    private void wizardEditButton(LinearLayout panel, int idx) {
        Button b = Ui.flat(a, panel, "✎ EDIT BEFORE SAVING");
        b.setGravity(Gravity.CENTER);
        b.setTextColor(Look.BLOCKS[0]);
        b.setContentDescription("Edit these values before saving");
        b.setOnClickListener(new WizardEditTap(idx));
    }

    /** A decision tap: write the draft field, then auto-advance to the next step (the
     *  mock's behaviour; "NEXT ›" remains the manual path, and BACK re-shows the choice
     *  with the selection marked). `idx` is a block index, or a STAGE index for the
     *  two stage codes. */
    private final class WizardDecisionTap implements View.OnClickListener {
        private final int what, idx;
        WizardDecisionTap(int what, int idx) { this.what = what; this.idx = idx; }
        @Override public void onClick(View v) {
            if (a.saveDraft == null) return;
            switch (what) {
                // The twin-name guard runs on EVERY path that turns a block's save on —
                // an edit left pending via BACK/NEXT reaches SAVE through here, not DONE.
                case SessionActivity.WD_SAVE:       wizardEditedNameGuard(idx);
                                    a.saveDraft.blocks.get(idx).on = true;  break;
                case SessionActivity.WD_KEEP:       a.saveDraft.blocks.get(idx).on = false; break;
                case SessionActivity.WD_DROP_STAGE: a.saveDraft.dropStage[idx] = true;  break;
                case SessionActivity.WD_KEEP_STAGE: a.saveDraft.dropStage[idx] = false; break;
                case SessionActivity.WD_DROP_SET:   a.saveDraft.blocks.get(idx).drop = true;  break;
                case SessionActivity.WD_KEEP_SET:   a.saveDraft.blocks.get(idx).drop = false; break;
                default: break;
            }
            if (a.wizardStep < a.saveBlocks.size()) a.wizardStep++;
            showReviewWizard();
        }
    }

    /** Drops a block's pending ✎ edit and puts every value field back to exactly what
     *  ran (the record's o* originals), `unchanged` included — the inverse of
     *  wizardApplyEdit's write-through. Used when a fold absorbs the block: the
     *  standalone save the edit was shaping no longer exists, and mutated fields left
     *  behind would surface in the diff rows as edited values WITHOUT the blue
     *  provenance ink (buildWizardStep rightly passes no pending record for a folded
     *  block) — values that never ran and, since commit skips folded blocks, never
     *  file. */
    private void wizardDropEdit(int idx) {
        SessionActivity.WizardEdit e = a.wizardEditFor(idx);
        if (e == null || idx >= a.saveBlocks.size()) return;
        AsRun.Block b = a.saveBlocks.get(idx);
        b.up = e.oUp; b.lo = e.oLo; b.uh = e.oUh; b.lh = e.oLh; b.sp = e.oSp;
        b.up2 = e.oUp2; b.lo2 = e.oLo2; b.uh2 = e.oUh2; b.lh2 = e.oLh2; b.sp2 = e.oSp2;
        b.steps = e.oSteps;
        b.unchanged = e.oUnchanged;
        a.wizardEdits[idx] = null;
    }

    /** THE TWIN-NAME GUARD, at the one moment it matters: whenever a SAVE decision lands
     *  (WD_SAVE and ✎ DONE both reach here) on a block whose pending edit made an
     *  ORIGINALLY-UNCHANGED block savable while it still wears the planned set's own name
     *  — draft() names only savable blocks uniquely, so without this the commit would
     *  file a set named identically to the library set it shadows. The " · edited" base
     *  is made unique against the library AND the draft's other names (draft()'s own
     *  " (n)" convention), so two sessions editing the same unchanged set cannot both
     *  file "Work · edited". A name the user already changed is left alone. */
    private void wizardEditedNameGuard(int idx) {
        SessionActivity.WizardEdit e = a.wizardEditFor(idx);
        if (e == null || !e.oUnchanged || e.atOriginal() || a.saveDraft == null) return;
        if (idx >= a.saveBlocks.size() || idx >= a.saveDraft.blocks.size()) return;
        AsRun.Block b = a.saveBlocks.get(idx);
        AsRun.DraftBlock db = a.saveDraft.blocks.get(idx);
        if (!db.name.equals(b.setName)) return;
        String base = (b.setName.length() == 0 ? "Set" : b.setName) + " · edited";
        List<String> taken = new ArrayList<String>();
        for (int i = 0; i < a.model.sets.size(); i++) taken.add(a.model.sets.get(i).name);
        for (int i = 0; i < a.saveDraft.blocks.size(); i++)
            if (i != idx) taken.add(a.saveDraft.blocks.get(i).name);
        String name = base;
        for (int n = 2; taken.contains(name); n++) name = base + " (" + n + ")";
        db.name = name;
    }

    /**
     * THE EDIT MODE (mock7's locked "EDIT = inline steppers+type"): the step's facts and
     * decision rows swap for the app's established editing triple — Ui.paramRow (slider +
     * ± + tap-to-type) per FIXED field, Ui.pairRow per RAMP pair plus a steps row: the
     * same rows, labels and gestures the set editor drives, over the wizard scratch. The
     * chart above stays, and every gesture lands in wizardApplyEdit, so the RAN line
     * moves while the PLAN stays frozen at what the routine planned.
     *
     * NO DURATION ROW, deliberately. A saved set's length is derived by the pinned commit
     * from the run's own delivered span (Block#saveDurSec — whole cycles for FIXED, the
     * span for RAMP, less any time the at-pressure clock held the set, C7); the only way
     * to "edit" it would be rewriting the recording's
     * spanMs, and the recording is a fact. The caption under the rows states what the
     * block will save as, from that same derivation — hold edits move it, because they
     * move the cycle.
     */
    private void buildWizardEditRows(LinearLayout panel, AsRun.Block b, int idx) {
        SessionActivity.WizardEdit e = a.wizardEditFor(idx);
        if (e == null) return;
        Model.Set w = e.work;
        a.saveCardCaption(panel, "✎ editing — the RAN line follows every change; nothing is filed yet",
                Look.BLOCKS[0]);
        if (b.kind == AsRun.RAMP) {
            a.saveCardCaption(panel, "− + steps · tap a number to type it · start → end", Ui.DIM);
            a.wizardEditPair(panel, idx, SessionActivity.Tap.UP, SessionActivity.Tap.UP2, "Pull",      Model.Fmt.p(w.up), Model.Fmt.p(w.up2));
            a.wizardEditPair(panel, idx, SessionActivity.Tap.LO, SessionActivity.Tap.LO2, "Drop",      Model.Fmt.p(w.lo), Model.Fmt.p(w.lo2));
            a.wizardEditPair(panel, idx, SessionActivity.Tap.UH, SessionActivity.Tap.UH2, "Hold",      w.uh + " s", w.uh2 + " s");
            a.wizardEditPair(panel, idx, SessionActivity.Tap.LH, SessionActivity.Tap.LH2, "Hold the release", w.lh + " s", w.lh2 + " s");
            a.wizardEditPair(panel, idx, SessionActivity.Tap.SP, SessionActivity.Tap.SP2, "Suction power", w.sp + " %", w.sp2 + " %");
            a.wizardEditParam(panel, idx, SessionActivity.Tap.STEPS, "Steps", String.valueOf(w.steps), w);
        } else {
            a.saveCardCaption(panel, "− + steps · drag to sweep · tap the number to type it", Ui.DIM);
            a.wizardEditParam(panel, idx, SessionActivity.Tap.UP, "Pull to",        Model.Fmt.p(w.up), w);
            a.wizardEditParam(panel, idx, SessionActivity.Tap.LO, "Release to",     Model.Fmt.p(w.lo), w);
            a.wizardEditParam(panel, idx, SessionActivity.Tap.UH, "Hold the pull", w.uh + " s", w);
            a.wizardEditParam(panel, idx, SessionActivity.Tap.LH, "Hold the release",   w.lh + " s", w);
            a.wizardEditParam(panel, idx, SessionActivity.Tap.SP, "Suction power",  w.sp + " %", w);
        }
        // What the commit will actually file for length — b.saveDurSec on the CURRENT
        // holds, plus any neighbour folded into this block, under Set#clamp's own floor.
        long extra = 0;
        for (int j = 0; j < a.saveBlocks.size() && j < a.saveDraft.blocks.size(); j++)
            if (a.saveDraft.blocks.get(j).mergedInto == idx) extra += a.saveBlocks.get(j).ownSpanMs();
        a.saveCardCaption(panel, "duration comes from the run — this saves as "
                + Model.Fmt.t(Math.max(30, b.saveDurSec(extra))), Ui.DIM);
        Button done = Ui.big(a, panel,
                e.atOriginal() ? "✓ DONE — BACK TO AS RAN" : "✓ DONE — SAVE THESE VALUES",
                Ui.ACCENT);
        done.setContentDescription(e.atOriginal()
                ? "Done editing. The values are back to exactly what ran"
                : "Done editing. Save these values for this block");
        done.setOnClickListener(new WizardEditDoneTap(idx));
    }

    /** "✎ EDIT BEFORE SAVING" tapped: into edit mode for this block, creating its pending
     *  record from the block AS IT NOW STANDS — a re-edit resumes where the last one
     *  stopped, and the record's origin stays what actually ran. */
    private final class WizardEditTap implements View.OnClickListener {
        private final int idx;
        WizardEditTap(int idx) { this.idx = idx; }
        @Override public void onClick(View v) {
            if (idx >= a.saveBlocks.size() || idx >= a.wizardEdits.length) return;
            if (a.wizardEdits[idx] == null) a.wizardEdits[idx] = new SessionActivity.WizardEdit(a.saveBlocks.get(idx));
            a.wizardEditIdx = idx;
            showReviewWizard();
        }
    }

    /** "✓ DONE" — out of edit mode with the SAVE decision applied: DraftBlock.on, the
     *  same field the old card's switch wrote, pre-selected so the decision view returns
     *  saying save-with-these-values. An edit that went exactly back to what ran is no
     *  edit at all — record dropped, nothing pre-selected (for an unchanged routine block
     *  there is still nothing to save, and the step says so again). */
    private final class WizardEditDoneTap implements View.OnClickListener {
        private final int idx;
        WizardEditDoneTap(int idx) { this.idx = idx; }
        @Override public void onClick(View v) {
            SessionActivity.WizardEdit e = a.wizardEditFor(idx);
            a.wizardEditIdx = -1;
            if (e == null || a.saveDraft == null || idx >= a.saveBlocks.size()) {
                showReviewWizard();
                return;
            }
            AsRun.Block b = a.saveBlocks.get(idx);
            AsRun.DraftBlock db = a.saveDraft.blocks.get(idx);
            if (e.atOriginal()) {
                a.wizardEdits[idx] = null;
            } else if (!b.skipped && db.mergedInto < 0) {
                wizardEditedNameGuard(idx);   // centralized — WD_SAVE runs the same guard
                db.on = true;
            }
            showReviewWizard();
        }
    }

    /**
     * THE CONFIRM STEP — the old card's bottom half, behaviour ported verbatim: the mode
     * choice (Update / New routine / Sets only; Separate sets / One routine for a manual
     * run) with the disabled-Update treatment and its captions, the routine-name row, the
     * per-stage "will be:" preview, the one primary gated by AsRun.saveEnabled (pure,
     * pinned — with the emptiesRoutine caption when a draft would empty the routine), and
     * the relocated "don't ask for this routine again" link. The commit is SaveTap →
     * commitSaveCard → AsRun.commit — the same path, never a reimplementation. The old
     * card's "Not now" is gone because leaving IS the BACK path now.
     */
    private void buildWizardConfirm(LinearLayout panel) {
        final Model.Sess s = a.saveSess;
        final AsRun.SaveDraft d = a.saveDraft;
        final List<AsRun.Block> bs = a.saveBlocks;
        boolean manual = d.manual;

        cardTitle(panel, manual ? "Save what you ran" : "As you ran it");
        int valueBlocks = 0;
        for (int i = 0; i < bs.size(); i++) if (!bs.get(i).skipped) valueBlocks++;
        int stageCount = d.dropStage.length;
        String sub = (manual ? "manual run" : s.routineName) + " · "
            + (s.completed ? "done" : "stopped early") + " · " + Model.Fmt.t(s.durSec) + " · "
            + (manual ? valueBlocks + " block" + (valueBlocks == 1 ? "" : "s")
                      : stageCount + " stage" + (stageCount == 1 ? "" : "s"));
        a.saveCardCaption(panel, sub, Ui.DIM);

        // Mode toggle — verbatim from the retired card.
        if (manual) {
            Ui.row(a, panel,
                new String[]{ (d.mode == AsRun.MODE_ROUTINE ? A11y.MARK_OFF : A11y.MARK_ON) + "Separate sets",
                              (d.mode == AsRun.MODE_ROUTINE ? A11y.MARK_ON : A11y.MARK_OFF) + "One routine" },
                new View.OnClickListener[]{ a.new ModeTap(AsRun.MODE_SETS_ONLY), a.new ModeTap(AsRun.MODE_ROUTINE) });
        } else {
            boolean showUpdate = d.routineExists && s.completed;
            List<String> labels = new ArrayList<String>();
            List<View.OnClickListener> taps = new ArrayList<View.OnClickListener>();
            if (showUpdate) {
                labels.add((d.mode == AsRun.MODE_UPDATE ? A11y.MARK_ON : A11y.MARK_OFF) + "Update routine");
                taps.add(a.new ModeTap(AsRun.MODE_UPDATE));
            }
            labels.add((d.mode == AsRun.MODE_ROUTINE ? A11y.MARK_ON : A11y.MARK_OFF) + "New routine");
            taps.add(a.new ModeTap(AsRun.MODE_ROUTINE));
            labels.add((d.mode == AsRun.MODE_SETS_ONLY ? A11y.MARK_ON : A11y.MARK_OFF) + "Sets only");
            taps.add(a.new ModeTap(AsRun.MODE_SETS_ONLY));
            Button[] modeBtns = Ui.row(a, panel, labels.toArray(new String[labels.size()]),
                   taps.toArray(new View.OnClickListener[taps.size()]));
            if (showUpdate && !d.canUpdate) {
                // A mode that cannot be chosen looks like it: disabled + dimmed, with the
                // caption saying why. The tap's toast stays for accessibility focus, but a
                // disabled button never reaches it.
                Ui.setEnabled(a, modeBtns[0], false, Ui.SURFHI);
                a.saveCardCaption(panel, cantUpdateSentence(d.cantUpdateWhy, s.routineName), Ui.DIM);
            }
            else if (!d.routineExists)
                a.saveCardCaption(panel, "This routine no longer exists — a new routine or sets only.", Ui.DIM);
            else if (!s.completed)
                a.saveCardCaption(panel, "Stopped early — the routine is not updated from a partial run.", Ui.DIM);
        }

        // Routine name (Update: the current name, a rename; New: "<name> (as run)").
        if (d.mode != AsRun.MODE_SETS_ONLY) {
            Button rn = Ui.flat(a, panel, "routine   " + (d.routineName.length() == 0 ? "—" : d.routineName));
            rn.setSingleLine(true);
            rn.setEllipsize(android.text.TextUtils.TruncateAt.END);
            rn.setContentDescription("Routine name: " + d.routineName + ". Tap to change");
            rn.setOnClickListener(new RoutineNameTap());
        }

        // The per-stage preview — what each stage will hold after this draft (the old
        // card's "X will be: …" lines), only when the mode touches the routine.
        if (!manual && d.mode != AsRun.MODE_SETS_ONLY) {
            Model.AsRunSnapshot snap = s.asRunSnapshot != null ? s.asRunSnapshot
                                     : a.saveRun != null ? a.saveRun.snap : null;
            for (int si = 0; si < d.dropStage.length; si++) {
                String stageName = snap != null && si < snap.stages.size()
                    ? snap.stages.get(si).name : "Stage " + (si + 1);
                List<String> planned = snap != null && si < snap.stages.size()
                    ? snap.stages.get(si).setIds : new ArrayList<String>();
                List<String> pv = AsRun.stagePreview(bs, d, si, planned, a.model);
                boolean changedSet = false;
                for (int j = 0; j < planned.size() && !changedSet; j++) {
                    List<AsRun.Block> at = AsRun.blocksAt(bs, si, j);
                    for (int k = 0; k < at.size(); k++) if (!at.get(k).unchanged && !at.get(k).skipped) changedSet = true;
                }
                if (pv != null) {
                    StringBuilder line = new StringBuilder(stageName + " will be: ");
                    if (pv.isEmpty()) line.append("dropped");
                    for (int k = 0; k < pv.size(); k++) { if (k > 0) line.append(" → "); line.append(pv.get(k)); }
                    a.saveCardCaption(panel, line.toString(), Ui.TEXT);
                } else if (changedSet) {
                    a.saveCardCaption(panel, stageName + " kept as planned", Ui.DIM);
                }
            }
        }

        // Primary. The enable rule is AsRun.saveEnabled — pure, pinned in SelfTest: a
        // rename-only Update is a save; a New routine with nothing selected and nothing
        // dropped is not (a stray tap would file a plain duplicate of the plan).
        int n = AsRun.setsToSave(d, bs);
        boolean enabled = AsRun.saveEnabled(d, bs, a.model);
        String label;
        if (manual) {
            label = n == 0 ? "Nothing selected"
                  : d.mode == AsRun.MODE_ROUTINE ? "Save routine + " + n + " set" + (n == 1 ? "" : "s")
                  : "Save " + n + " set" + (n == 1 ? "" : "s");
        } else {
            label = enabled ? "Save" : "Nothing selected";
        }
        Button save = Ui.big(a, panel, label, Ui.ACCENT);
        Ui.setEnabled(a, save, enabled, Ui.ACCENT);
        if (enabled) save.setOnClickListener(new SaveTap());
        // A draft that would leave the routine with nothing in it is refused by
        // AsRun.saveEnabled; say WHY, rather than letting the user tap a dead button or
        // (worse) file a routine with no stages.
        if (!enabled && AsRun.emptiesRoutine(d, bs, a.model))
            a.saveCardCaption(panel, "That would leave the routine empty — untick a drop, or keep a stage.", Ui.DIM);

        // The opt-out link, relocated here from the retired inline card.
        if (!manual && d.routineExists) {
            Button dont = Ui.flat(a, panel, DONT_OFFER);
            dont.setTextColor(Ui.DIM);
            dont.setOnClickListener(new DontAskTap());
        }
    }

    private final class MergeTap implements View.OnClickListener {
        private final int idx, target;
        MergeTap(int i, int t) { idx = i; target = t; }
        @Override public void onClick(View v) {
            // Pure (AsRun.toggleMerge, pinned): unfolding restores on = !tooShort — a
            // fold-then-unfold must never leave the block silently dropped.
            AsRun.toggleMerge(a.saveDraft, a.saveBlocks, idx, target);
            // Folding while a ✎ edit is pending: the edit dies WITH the standalone save
            // it was shaping — the block's fields return to what actually ran, so the
            // diff rows keep telling the truth about a block that now saves only as
            // part of its neighbour. An unfold afterwards re-offers a clean as-ran
            // block, editable again from scratch.
            if (a.saveDraft.blocks.get(idx).mergedInto >= 0) wizardDropEdit(idx);
            showReviewWizard();
        }
    }

    /** Name a block's set — a dialog (RenameConfirm shape), never an inline field. */
    private final class BlockNameTap implements View.OnClickListener {
        private final int idx;
        BlockNameTap(int i) { idx = i; }
        @Override public void onClick(View v) {
            EditText input = new EditText(a);
            input.setHint("set name");
            input.setText(a.saveDraft.blocks.get(idx).name);
            input.setSelection(input.getText().length());
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Name this set")
                .setView(input)
                .setPositiveButton("OK", new BlockNameConfirm(idx, input))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class BlockNameConfirm implements DialogInterface.OnClickListener {
        private final int idx; private final EditText input;
        BlockNameConfirm(int i, EditText e) { idx = i; input = e; }
        @Override public void onClick(DialogInterface dlg, int w) {
            String v = input.getText().toString().trim();
            if (v.isEmpty()) { a.toast("A name is needed"); return; }
            for (int i = 0; i < a.model.sets.size(); i++)
                if (a.model.sets.get(i).name.equals(v)) { a.toast("That name is already used"); return; }
            for (int i = 0; i < a.saveDraft.blocks.size(); i++)
                if (i != idx && v.equals(a.saveDraft.blocks.get(i).name)) { a.toast("That name is already used"); return; }
            a.saveDraft.blocks.get(idx).name = v;
            showReviewWizard();
        }
    }

    private final class RoutineNameTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            EditText input = new EditText(a);
            input.setHint("routine name");
            input.setText(a.saveDraft.routineName);
            input.setSelection(input.getText().length());
            Ui.dress(a, Ui.dialog(a)
                .setTitle(a.saveDraft.mode == AsRun.MODE_UPDATE ? "Routine name (rename)" : "Name the new routine")
                .setView(input)
                .setPositiveButton("OK", new RoutineNameConfirm(input))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class RoutineNameConfirm implements DialogInterface.OnClickListener {
        private final EditText input;
        RoutineNameConfirm(EditText e) { input = e; }
        @Override public void onClick(DialogInterface dlg, int w) {
            String v = input.getText().toString().trim();
            if (v.isEmpty()) { a.toast("A name is needed"); return; }
            for (int i = 0; i < a.model.routines.size(); i++) {
                Model.Routine r = a.model.routines.get(i);
                if (r.name.equals(v) && !(a.saveDraft.mode == AsRun.MODE_UPDATE && r.id.equals(a.saveDraft.routineId))) {
                    a.toast("That name is already used"); return;
                }
            }
            a.saveDraft.routineName = v;
            showReviewWizard();
        }
    }

    /** "don't ask for this routine again" → confirm sheet → Routine.noSaveOffer. */
    private final class DontAskTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            String n = a.saveSess == null ? "this routine" : a.saveSess.routineName;
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Don't offer to save after " + n + "?")
                .setMessage("Your changes are still recorded — save any time from Sessions. "
                    + "Change this in Settings › Offer to save.")
                .setPositiveButton("Don't offer", new DontAskConfirm())
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class DontAskConfirm implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface dlg, int w) {
            Model.Routine r = a.saveSess == null ? null : a.model.routine(a.saveSess.routineId);
            if (r != null) { r.noSaveOffer = true; Store.save(a, a.model); }
            String n = a.saveSess == null ? "this routine" : a.saveSess.routineName;
            Ui.snack(a, a.rootFrame, "Won't offer to save after " + n
                + " — change in Settings › Offer to save");
            a.saveDismissed = true;
            if (a.saveFullScreen) { a.saveFullScreen = false; a.showSessionHistory(); }
            else renderSummary();
        }
    }

    private final class SaveTap implements View.OnClickListener {
        @Override public void onClick(View v) { commitSaveCard(); }
    }

    /** Save: AsRun.commit, then the Sess learns what it became, Store.save, snack, land. */
    private void commitSaveCard() {
        if (a.saveDraft == null || a.saveSess == null || a.saveRun == null) return;
        // AUDIT A2 \u2014 THE ONE STRUCTURAL EDIT THAT ESCAPED VERSION HISTORY. An Update
        // rebuilds a routine's stages exactly as moveStage, removeStage and the picker do,
        // and every one of those archives the shape it is about to replace. This path did
        // not, so "save what you ran" was the single way to change a routine with no way
        // back \u2014 and History's versionSince/versionRuns went on describing a shape that no
        // longer existed. Snapshot BEFORE the commit, because afterwards the old shape is
        // gone; archive AFTER it succeeds, so a refused commit files no version.
        Model.AsRunSnapshot updatePre = null;
        Model.Routine updateTarget = null;
        if (!a.saveDraft.manual && a.saveDraft.mode == AsRun.MODE_UPDATE) {
            updateTarget = a.model.routine(a.saveDraft.routineId);
            if (updateTarget != null) updatePre = Model.AsRunSnapshot.of(updateTarget, a.model);
        }
        AsRun.Commit c;
        try {
            c = AsRun.commit(a.saveDraft, a.saveBlocks, a.saveRun, a.model);
        } catch (IllegalStateException e) {
            if ("empty".equals(e.getMessage())) {
                // The draft would leave the routine with no stages. The primary is disabled
                // for that, so we only get here if the draft changed under the tap: say so
                // and re-render, without touching the routine.
                a.toast("That would leave the routine empty — nothing was changed");
                showReviewWizard();
                return;
            }
            // The routine moved under us between the draft and the tap: Update is off now.
            a.saveDraft.canUpdate = false;
            a.saveDraft.cantUpdateWhy = "the routine was edited since this run";
            a.saveDraft.mode = AsRun.MODE_SETS_ONLY;
            a.saveDraft.routineName = "";
            a.toast("This routine was edited since the run — Update is off");
            showReviewWizard();
            return;
        }
        // The shape it replaced becomes a Version, exactly like any other structural edit.
        if (updatePre != null && updateTarget != null && !c.routineCreated)
            a.model.archiveRoutineVersion(updateTarget, updatePre);
        a.saveSess.savedSetIds.addAll(c.createdSetIds);
        // A sets-only commit returns routineId == null; it must not CLOBBER the routine a
        // previous commit of this same session created or updated.
        if (c.routineId != null) a.saveSess.savedRoutineId = c.routineId;
        a.saveSess.savedAt = System.currentTimeMillis();
        Store.save(a, a.model);
        StringBuilder names = new StringBuilder();
        if (c.routineId != null) {
            Model.Routine r = a.model.routine(c.routineId);
            if (r != null) names.append(c.routineCreated ? "\"" + r.name + "\"" : r.name + " updated");
        }
        for (int i = 0; i < c.createdSetIds.size(); i++) {
            Model.Set x = a.model.set(c.createdSetIds.get(i));
            if (x == null) continue;
            if (names.length() > 0) names.append(", ");
            names.append(x.name);
        }
        Ui.snack(a, a.rootFrame, "Saved · " + names + " · open in Library");
        // Remember WHAT this commit just filed, per block index, BEFORE the draft is
        // rebuilt — commit's own step-1 filter, mirrored exactly. An unedited block's
        // savedAs would come back by value match anyway; an EDITED one cannot (the saved
        // set carries the edited values, the rebuilt block the as-ran ones), and without
        // the stamp it would re-arm ON under a fresh "(2)" name (see wizardSavedStamps).
        String[] stamps = a.wizardSavedStamps.get(a.saveSess.ts);
        if (stamps == null || stamps.length != a.saveBlocks.size()) {
            stamps = new String[a.saveBlocks.size()];
            a.wizardSavedStamps.put(a.saveSess.ts, stamps);
        }
        for (int i = 0; i < a.saveBlocks.size() && i < a.saveDraft.blocks.size(); i++) {
            AsRun.Block b = a.saveBlocks.get(i);
            AsRun.DraftBlock db = a.saveDraft.blocks.get(i);
            if (!db.on || db.mergedInto >= 0 || b.skipped) continue;
            if (b.unchanged && !a.saveDraft.manual) continue;
            stamps[i] = db.name;
        }
        // Rebuild the draft against the library as it now stands (the blocks just written
        // come back with savedAs set and OFF — by value match, or by the stamp above),
        // then land where the flow lands after a save: History when the wizard was
        // entered through a History row, else the summary — whose offer slot now shows
        // the Saved ✓ line, and the review door again while any savable block is still
        // unsaved (§5 reopen).
        a.prepareSaveCard(a.saveSess, a.saveRun);
        if (a.saveFullScreen) { a.saveFullScreen = false; a.showSessionHistory(); }
        else renderSummary();
    }

    /* ===================================================================================
     * ITEM 11 — THE ONE-SCREEN "SAVE CHANGES" PAGE, replacing the per-block wizard.
     * Destination FIRST (Update / New routine / Don't save — the primary names it), one
     * diff row per SET at its FINAL values (AsRun.setChanges collapses the "· in"
     * fragments), Update edits IN PLACE (a shared set asks each time: change everywhere,
     * or only here — a copy), skipped sets offer one "remove" tick, and after saving the
     * summary stays put with an Open/Stay dialog. Same Nav id as the wizard
     * (SCR_SAVE_CARD), so Back behaviour is unchanged. The old wizard code stays
     * compiled but unreachable. Nothing here ever transmits (WiringCheck inv. 13).
     * =================================================================================== */

    /** Entry: build the per-set state from the already-prepared save* fields, then show.
     *  `preselectNew` is the Library door's "+ New routine → From a session I ran". */
    void openSaveChanges(boolean preselectNew) {
        a.scChanges = AsRun.setChanges(a.saveBlocks);
        int n = a.scChanges.size();
        a.scUse = new boolean[n];
        a.scDrop = new boolean[n];
        a.scOnlyHere = new int[n];
        for (int i = 0; i < n; i++) {
            AsRun.SetChange c = a.scChanges.get(i);
            a.scUse[i] = c.changed && !c.skipped;
            a.scOnlyHere[i] = -1;
        }
        a.scNewName = null;     // item 11c: each visit starts from the suggested name
        boolean manual = a.saveDraft != null && a.saveDraft.manual;
        if (manual || preselectNew) a.scMode = AsRun.MODE_ROUTINE;
        else a.scMode = a.saveDraft != null && a.saveDraft.canUpdate
                      ? AsRun.MODE_UPDATE : AsRun.MODE_ROUTINE;
        showSaveChanges();
    }

    /** The one screen. Re-rendered whole on every tap, like every screen in this app. */
    void showSaveChanges() {
        a.body.removeAllViews();
        a.enterDest(Nav.SCR_SAVE_CARD);
        a.backAction = new SaveChangesBackTap();
        // AS-9: a pushed screen's own header, its ‹ the way back (the hardware key does the
        // same through backAction) - in place of the foot "‹ BACK" row.
        Ui.header(a, a.body, "Save changes", new SaveChangesBackTap());
        if (a.saveDraft == null || a.saveSess == null || a.scChanges.isEmpty()) {
            Ui.note(a, a.body, "This session didn't keep a step-by-step record, so there's nothing to save from it.");
            return;
        }
        final Model.Sess s = a.saveSess;
        final AsRun.SaveDraft d = a.saveDraft;
        boolean manual = d.manual;
        a.saveCardCaption(a.body, (manual ? "Manual" : s.routineName) + " · "
            + (s.completed ? "done" : "stopped early") + " · " + Model.Fmt.t(s.durSec), Ui.DIM);

        // ---- destination first: the owner's rule 1 --------------------------------------
        LinearLayout dest = saveCardFrame(a.body);
        cardTitle(dest, "Where should these changes go?");
        // Item 11d: a "Try this set" run offers to write the final values back into the
        // TRIED set itself — for THAT session only, and only while the set still exists.
        Model.Set triedSet = a.scTriedSetId != null && s.ts == a.scTriedTs
                           ? a.model.set(a.scTriedSetId) : null;
        if (manual) {
            if (triedSet != null)
                a.wizardChoice(dest, "Update \"" + triedSet.name + "\"",
                    a.scMode == SessionActivity.SC_MODE_TRYSET, false,
                    new SaveChangesModeTap(SessionActivity.SC_MODE_TRYSET));
            a.wizardChoice(dest, "One routine", a.scMode == AsRun.MODE_ROUTINE, false,
                new SaveChangesModeTap(AsRun.MODE_ROUTINE));
            a.wizardChoice(dest, "Don't save", a.scMode == SessionActivity.SC_MODE_NONE, false,
                new SaveChangesModeTap(SessionActivity.SC_MODE_NONE));
        } else {
            boolean showUpdate = d.routineExists && s.completed;
            if (showUpdate) {
                Button up = a.wizardChoice(dest, "Update \"" + s.routineName + "\"",
                    a.scMode == AsRun.MODE_UPDATE, false, new SaveChangesModeTap(AsRun.MODE_UPDATE));
                if (!d.canUpdate) {
                    Ui.setEnabled(a, up, false, Ui.SURFHI);
                    a.saveCardCaption(dest, cantUpdateSentence(d.cantUpdateWhy, s.routineName), Ui.DIM);
                }
            } else if (!d.routineExists) {
                a.saveCardCaption(dest, "This routine no longer exists — save as a new routine.", Ui.DIM);
            } else if (!s.completed) {
                a.saveCardCaption(dest, "Stopped early — the routine is not updated from a partial run.", Ui.DIM);
            }
            a.wizardChoice(dest, "Save as a new routine", a.scMode == AsRun.MODE_ROUTINE, false,
                new SaveChangesModeTap(AsRun.MODE_ROUTINE));
            a.wizardChoice(dest, "Don't save", a.scMode == SessionActivity.SC_MODE_NONE, false,
                new SaveChangesModeTap(SessionActivity.SC_MODE_NONE));
        }

        // ---- the diff, one row per SET at its final values ------------------------------
        int changed = 0, skipped = 0, asPlanned = 0;
        for (int i = 0; i < a.scChanges.size(); i++) {
            AsRun.SetChange c = a.scChanges.get(i);
            if (c.skipped) skipped++;
            else if (c.changed) changed++;
            else asPlanned++;
        }
        LinearLayout diff = saveCardFrame(a.body);
        cardTitle(diff, "What changed");
        boolean toRoutine = a.scMode != SessionActivity.SC_MODE_NONE;
        for (int i = 0; i < a.scChanges.size(); i++) {
            AsRun.SetChange c = a.scChanges.get(i);
            if (c.skipped) continue;
            if (!c.changed) continue;
            // Polish (wave-4 wrap): a try run's change is a change TO THE TRIED SET, so
            // the row wears that set's name, not the ephemeral "Manual run" wrapper's.
            String who = a.scMode == SessionActivity.SC_MODE_TRYSET && triedSet != null
                       ? triedSet.name
                       : manual ? (c.setName.length() == 0 ? c.stageName : c.setName)
                                : c.stageName + " · " + c.setName;
            Button row = a.wizardChoice(diff, who + "\n" + scDiffText(c), a.scUse[i], false,
                new SaveChangesUseTap(i));
            row.setEnabled(toRoutine);
            if (!toRoutine) Ui.setEnabled(a, row, false, Ui.SURFHI);
            // Item 11b (owner pick 3B): a changed RAMP set gets the graph — two handles,
            // start and end each editable before the save. Drawn only while the change is
            // ticked and going somewhere.
            Model.Set rampSet = a.model.set(c.setId);
            if (rampSet == null && a.scMode == SessionActivity.SC_MODE_TRYSET)
                rampSet = a.model.set(a.scTriedSetId);
            if (rampSet != null && rampSet.ramp && toRoutine && a.scUse[i]) {
                a.saveCardCaption(diff,
                    "Ramp — drag a handle: start and end save separately.", Ui.DIM);
                RampHandleView hv = new RampHandleView(c, rampSet);
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 130));
                hlp.topMargin = Ui.dp(a, 4);
                diff.addView(hv, hlp);
            }
        }
        if (changed == 0)
            a.saveCardCaption(diff, "No values changed — only what is ticked below is saved.", Ui.DIM);
        if (!manual) for (int i = 0; i < a.scChanges.size(); i++) {
            AsRun.SetChange c = a.scChanges.get(i);
            if (!c.skipped) continue;
            Button row = a.wizardChoice(diff,
                "Remove " + (c.setName.length() == 0 ? "it" : c.setName) + " from "
                    + c.stageName + " — you skipped it",
                a.scDrop[i], false, new SaveChangesDropTap(i));
            row.setEnabled(toRoutine && a.scMode != SessionActivity.SC_MODE_NONE);
            if (!row.isEnabled()) Ui.setEnabled(a, row, false, Ui.SURFHI);
        }
        if (asPlanned > 0)
            a.saveCardCaption(diff, asPlanned + (asPlanned == 1 ? " set ran" : " sets ran")
                + " exactly as planned", Ui.DIM);

        // ---- the primary names the destination ------------------------------------------
        boolean anyTicked = false;
        for (int i = 0; i < a.scChanges.size(); i++)
            if ((a.scUse[i] && a.scChanges.get(i).changed && !a.scChanges.get(i).skipped)
                || (a.scDrop[i] && a.scChanges.get(i).skipped)) anyTicked = true;
        // Item 11c: the user's own name wins over the suggested "(as run)" one.
        String newName = a.scNewName != null ? a.scNewName
                       : AsRun.suggestedRoutineName(AsRun.MODE_ROUTINE, s, a.model);
        if (a.scMode == AsRun.MODE_ROUTINE) {
            LinearLayout nameCard = saveCardFrame(a.body);
            cardTitle(nameCard, "Name");
            a.wizardChoice(nameCard, newName + "   ✎", false, false,
                new RenameNewRoutineTap(newName));
        }
        String label;
        boolean enabled;
        if (a.scMode == SessionActivity.SC_MODE_NONE) {
            label = "Don't save";
            enabled = true;
        } else if (a.scMode == AsRun.MODE_UPDATE) {
            label = "Save to \"" + s.routineName + "\"";
            enabled = anyTicked;
        } else if (a.scMode == SessionActivity.SC_MODE_TRYSET) {
            label = "Update \"" + (triedSet == null ? "the set" : triedSet.name) + "\"";
            enabled = anyTicked && triedSet != null;
        } else {
            label = "Create \"" + newName + "\"";
            enabled = anyTicked;
        }
        Button primary = Ui.big(a, a.body, label, Ui.ACCENT);
        Ui.setEnabled(a, primary, enabled, Ui.ACCENT);
        if (enabled) primary.setOnClickListener(new SaveChangesPrimaryTap(newName));
        if (!enabled && a.scMode != SessionActivity.SC_MODE_NONE)
            a.saveCardCaption(a.body, "Nothing is ticked — tick a change above to save it.", Ui.DIM);

        // The opt-out link, exactly as the wizard carried it - AS-11: one name for it.
        if (!manual && d.routineExists) {
            Button dont = Ui.secondary(a, a.body, DONT_OFFER);
            dont.setTextColor(Ui.DIM);
            dont.setOnClickListener(new DontAskTap());
        }
    }

    /** AS-11: the opt-out's one name - the button, and the snack that confirms it. */
    static final String DONT_OFFER = "Don't offer to save for this routine";

    /** One set's diff line: only the values that moved, planned → final, every unit
     *  through Fmt. A length-only change reads as a length; no plan reads plainly. */
    private String scDiffText(AsRun.SetChange c) {
        if (!c.hasPlan) return "no plan to compare — saves as it ran";
        StringBuilder sb = new StringBuilder();
        if (c.up != c.pUp) part(sb, "pull " + Model.Fmt.p(c.pUp) + " → " + Model.Fmt.p(c.up));
        if (c.lo != c.pLo) part(sb, "release " + Model.Fmt.p(c.pLo) + " → " + Model.Fmt.p(c.lo));
        if (c.uh != c.pUh) part(sb, "hold " + Model.Fmt.t(c.pUh) + " → " + Model.Fmt.t(c.uh));
        if (c.lh != c.pLh) part(sb, "release hold " + Model.Fmt.t(c.pLh) + " → " + Model.Fmt.t(c.lh));
        if (c.sp != c.pSp) part(sb, "power " + c.pSp + "% → " + c.sp + "%");
        // Item 11b (3B): a dragged ramp end is part of the save, so it is part of the diff.
        if (c.rampEndUp != Integer.MIN_VALUE)
            part(sb, "ramp end → " + Model.Fmt.p(c.rampEndUp));
        // Item 11a: an extension is part of the save now, so it is part of the diff —
        // always said, not only when nothing else moved.
        if (AsRun.extendsPlan(c))
            part(sb, "duration " + Model.Fmt.t(c.planDurSec) + " → " + Model.Fmt.t(c.saveDurSec));
        else if (sb.length() == 0 && c.planDurSec > 0 && c.saveDurSec > 0 && c.planDurSec != c.saveDurSec)
            part(sb, "duration " + Model.Fmt.t(c.planDurSec) + " → " + Model.Fmt.t(c.saveDurSec));
        if (sb.length() == 0) return "ran to different timing — saved at its final values";
        return sb.toString();
    }

    private static void part(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(" · ");
        sb.append(s);
    }

    /** Back / hardware Back: out to wherever this was entered from. */
    private final class SaveChangesBackTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.saveFullScreen) { a.saveFullScreen = false; a.showSessionHistory(); }
            else renderSummary();
        }
    }

    private final class SaveChangesModeTap implements View.OnClickListener {
        private final int mode;
        SaveChangesModeTap(int m) { mode = m; }
        @Override public void onClick(View v) { a.scMode = mode; showSaveChanges(); }
    }

    private final class SaveChangesUseTap implements View.OnClickListener {
        private final int idx;
        SaveChangesUseTap(int i) { idx = i; }
        @Override public void onClick(View v) { a.scUse[idx] = !a.scUse[idx]; showSaveChanges(); }
    }

    private final class SaveChangesDropTap implements View.OnClickListener {
        private final int idx;
        SaveChangesDropTap(int i) { idx = i; }
        @Override public void onClick(View v) { a.scDrop[idx] = !a.scDrop[idx]; showSaveChanges(); }
    }

    private final class SaveChangesPrimaryTap implements View.OnClickListener {
        private final String newName;
        SaveChangesPrimaryTap(String n) { newName = n; }
        @Override public void onClick(View v) {
            if (a.scMode == SessionActivity.SC_MODE_NONE) {
                a.saveDismissed = true;
                if (a.saveFullScreen) { a.saveFullScreen = false; a.showSessionHistory(); }
                else renderSummary();
                return;
            }
            if (a.scMode == AsRun.MODE_UPDATE) askSharedSets();
            else if (a.scMode == SessionActivity.SC_MODE_TRYSET) doSaveChangesUpdateTried();
            else doSaveChangesNewRoutine(newName);
        }
    }

    /** Item 11c: name the new routine right on the save screen — dialog + EditText, the
     *  same shape every other free-text entry in this app uses. */
    private final class RenameNewRoutineTap implements View.OnClickListener {
        private final String current;
        RenameNewRoutineTap(String c) { current = c; }
        @Override public void onClick(View v) {
            final EditText input = new EditText(a);
            input.setText(current);
            input.setSelection(current.length());
            int p = Ui.dp(a, 12);
            input.setPadding(p, p, p, p);
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Name the new routine")
                .setView(input)
                .setPositiveButton("OK", new RenameNewRoutineOk(input))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class RenameNewRoutineOk implements DialogInterface.OnClickListener {
        private final EditText input;
        RenameNewRoutineOk(EditText e) { input = e; }
        @Override public void onClick(DialogInterface dlg, int w) {
            String t = input.getText() == null ? "" : input.getText().toString().trim();
            a.scNewName = t.length() == 0 ? null : t;
            showSaveChanges();
        }
    }

    /** Item 11d: write the ticked final values back into the TRIED set — no routine is
     *  touched (a try has none). Values, dragged ramp end and an extended length all go
     *  through AsRun.applyToSet's one rule set. */
    private void doSaveChangesUpdateTried() {
        Model.Set tried = a.model.set(a.scTriedSetId);
        if (tried == null) {
            a.toast("That set no longer exists");
            a.scMode = AsRun.MODE_ROUTINE;
            showSaveChanges();
            return;
        }
        int ceil = AsRun.saveCeilKpa(a.saveRun, a.model);
        boolean any = false;
        for (int i = 0; i < a.scChanges.size(); i++) {
            AsRun.SetChange c = a.scChanges.get(i);
            if (!a.scUse[i] || !c.changed || c.skipped) continue;
            if (AsRun.applyToSet(c, tried, ceil)) any = true;
        }
        if (!any) { showSaveChanges(); return; }
        a.saveSess.savedAt = System.currentTimeMillis();
        a.scTriedSetId = null;      // consumed — the offer is made once
        Store.save(a, a.model);
        Ui.snack(a, a.rootFrame, "Updated " + tried.name);
        a.prepareSaveCard(a.saveSess, a.saveRun);
        if (a.saveFullScreen) { a.saveFullScreen = false; a.showSessionHistory(); }
        else renderSummary();
    }

    /** Item 11b, owner pick 3B: the ramp drawn as a line with a handle at each end.
     *  Dragging the left handle moves the saved START pull (SetChange#up); the right
     *  one the saved END pull (SetChange#rampEndUp). Values clamp to 1..ceiling; the
     *  labels are the numbers a save will write. Re-rendered on release so the diff
     *  line above stays true. */
    private final class RampHandleView extends View {
        private final AsRun.SetChange c;
        private final Model.Set set;
        private final int ceil;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ghost = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int dragging;   // 0 none, 1 start, 2 end

        RampHandleView(AsRun.SetChange c, Model.Set set) {
            super(a);
            this.c = c;
            this.set = set;
            ceil = AsRun.saveCeilKpa(a.saveRun, a.model);
            line.setColor(Ui.ACCENT);
            line.setStrokeWidth(Ui.dp(a, 3));
            ghost.setColor(Ui.DIM);
            ghost.setStrokeWidth(Ui.dp(a, 2));
            ghost.setAlpha(120);
            dot.setColor(Ui.ACCENT);
            txt.setColor(Ui.TEXT);
            txt.setTextSize(Ui.dp(a, 12));
        }

        private int endKpa() { return c.rampEndUp != Integer.MIN_VALUE ? c.rampEndUp : set.up2; }

        private float yFor(int kpa, float top, float bot) {
            int hi = Math.max(2, ceil);
            float f = (kpa - 1) / (float) (hi - 1);
            return bot - f * (bot - top);
        }

        private int kpaFor(float y, float top, float bot) {
            int hi = Math.max(2, ceil);
            float f = (bot - y) / (bot - top);
            int k = 1 + Math.round(f * (hi - 1));
            return k < 1 ? 1 : (k > hi ? hi : k);
        }

        @Override protected void onDraw(Canvas cv) {
            float padX = Ui.dp(a, 28), top = Ui.dp(a, 18), bot = getHeight() - Ui.dp(a, 18);
            float x0 = padX, x1 = getWidth() - padX;
            // The stored ramp, faint, so the drag is visibly a change from something.
            cv.drawLine(x0, yFor(set.up, top, bot), x1, yFor(set.up2, top, bot), ghost);
            float ys = yFor(c.up, top, bot), ye = yFor(endKpa(), top, bot);
            cv.drawLine(x0, ys, x1, ye, line);
            float r = Ui.dp(a, 7);
            cv.drawCircle(x0, ys, r, dot);
            cv.drawCircle(x1, ye, r, dot);
            // Labels never leave the drawing: clamped below the top edge, so a handle
            // dragged to the ceiling still shows its number.
            float minLy = Ui.dp(a, 14);
            txt.setTextAlign(Paint.Align.LEFT);
            cv.drawText(Model.Fmt.p(c.up), x0 + r + Ui.dp(a, 4),
                        Math.max(minLy, ys - Ui.dp(a, 6)), txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(Model.Fmt.p(endKpa()), x1 - r - Ui.dp(a, 4),
                        Math.max(minLy, ye - Ui.dp(a, 6)), txt);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float top = Ui.dp(a, 18), bot = getHeight() - Ui.dp(a, 18);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = e.getX() < getWidth() / 2f ? 1 : 2;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging == 0) return false;
                    int k = kpaFor(e.getY(), top, bot);
                    if (dragging == 1) c.up = k; else c.rampEndUp = k;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging != 0) { dragging = 0; showSaveChanges(); }
                    return true;
                default:
                    return false;
            }
        }
    }

    /** A SHARED set asks EACH TIME (owner ruling): the first ticked change whose set other
     *  routines also use gets the everywhere / only-here dialog; when every one is
     *  answered, the update runs. Cancel leaves the screen as it was. */
    private void askSharedSets() {
        for (int i = 0; i < a.scChanges.size(); i++) {
            AsRun.SetChange c = a.scChanges.get(i);
            if (!a.scUse[i] || !c.changed || c.skipped || a.scOnlyHere[i] >= 0) continue;
            int others = a.model.usedIn(c.setId) - 1;
            if (others < 1) continue;
            String name = c.setName.length() == 0 ? "This set" : "\"" + c.setName + "\"";
            Ui.dress(a, Ui.dialog(a)
                .setTitle("A shared set")
                .setMessage(name + " is also used in " + others + " other routine"
                    + (others == 1 ? "" : "s") + ".")
                .setPositiveButton("Change everywhere", new SharedSetChoice(i, false))
                .setNegativeButton("Only here (makes a copy)", new SharedSetChoice(i, true))
                .show());
            return;
        }
        doSaveChangesUpdate();
    }

    private final class SharedSetChoice implements DialogInterface.OnClickListener {
        private final int idx;
        private final boolean onlyHere;
        SharedSetChoice(int i, boolean o) { idx = i; onlyHere = o; }
        @Override public void onClick(DialogInterface dlg, int w) {
            a.scOnlyHere[idx] = onlyHere ? 1 : 0;
            askSharedSets();
        }
    }

    /** Update, in place: version archive first (audit A2's rule), then applyInPlace per
     *  ticked change, drops in DESCENDING order so indices stay true, then file + land. */
    private void doSaveChangesUpdate() {
        Model.Routine target = a.model.routine(a.saveDraft.routineId);
        if (target == null || a.saveRun == null || a.saveRun.snap == null
                || !AsRun.snapshotMatches(a.saveRun.snap, a.model)) {
            a.saveDraft.canUpdate = false;
            a.saveDraft.cantUpdateWhy = "the routine was edited since this run";
            a.scMode = AsRun.MODE_ROUTINE;
            a.toast("This routine was edited since the run — Update is off");
            showSaveChanges();
            return;
        }
        if (AsRun.occurrencesLeft(a.scChanges, a.scDrop, target) <= 0) {
            a.toast("That would leave the routine empty — nothing was changed");
            showSaveChanges();
            return;
        }
        Model.AsRunSnapshot pre = Model.AsRunSnapshot.of(target, a.model);
        int ceil = AsRun.saveCeilKpa(a.saveRun, a.model);
        List<String> created = new ArrayList<String>();
        for (int i = 0; i < a.scChanges.size(); i++) {
            AsRun.SetChange c = a.scChanges.get(i);
            if (!a.scUse[i] || !c.changed || c.skipped) continue;
            String id = AsRun.applyInPlace(c, a.model, target, a.scOnlyHere[i] == 1, ceil);
            if (id != null && !id.equals(c.setId)) created.add(id);
        }
        for (int i = a.scChanges.size() - 1; i >= 0; i--) {
            AsRun.SetChange c = a.scChanges.get(i);
            if (a.scDrop[i] && c.skipped) AsRun.dropSkipped(c, a.model, target);
        }
        a.model.archiveRoutineVersion(target, pre);
        // Wave 4 item 10: an only-here copy or a drop can leave an app-made set
        // unreferenced — swept here, user sets untouched.
        a.model.gcOrphanSets();
        a.saveSess.savedSetIds.addAll(created);
        a.saveSess.savedRoutineId = target.id;
        a.saveSess.savedAt = System.currentTimeMillis();
        Store.save(a, a.model);
        Ui.snack(a, a.rootFrame, "Saved to " + target.name);
        finishSaveChanges(target.id, target.name, false);
    }

    /** New routine: the pure builder does the whole splice; an empty result files nothing. */
    private void doSaveChangesNewRoutine(String name) {
        int ceil = AsRun.saveCeilKpa(a.saveRun, a.model);
        AsRun.Commit c = AsRun.buildNewRoutine(a.scChanges, a.scUse, a.scDrop,
                                               a.saveRun, a.model, name, ceil);
        if (c.routineId == null) {
            a.toast("Nothing selected — the new routine would be empty");
            showSaveChanges();
            return;
        }
        a.saveSess.savedSetIds.addAll(c.createdSetIds);
        a.saveSess.savedRoutineId = c.routineId;
        a.saveSess.savedAt = System.currentTimeMillis();
        Store.save(a, a.model);
        Ui.snack(a, a.rootFrame, "Saved as \"" + name + "\"");
        finishSaveChanges(c.routineId, name, true);
    }

    /** After saving, STAY on the summary (or History) and ASK whether to open the routine
     *  just saved to — the owner's rule 6, with a real Open action this time. */
    private void finishSaveChanges(String routineId, String routineName, boolean created) {
        a.prepareSaveCard(a.saveSess, a.saveRun);
        if (a.saveFullScreen) { a.saveFullScreen = false; a.showSessionHistory(); }
        else renderSummary();
        Ui.dress(a, Ui.dialog(a)
            .setTitle("Open \"" + routineName + "\"?")
            .setMessage(created ? "The new routine is in your library."
                                : "Your changes are saved to the routine.")
            .setPositiveButton("Open", new OpenSavedRoutineTap(routineId))
            .setNegativeButton("Stay", null)
            .show());
    }

    private final class OpenSavedRoutineTap implements DialogInterface.OnClickListener {
        private final String routineId;
        OpenSavedRoutineTap(String id) { routineId = id; }
        @Override public void onClick(DialogInterface dlg, int w) { a.showRoutEdit(routineId); }
    }

    /** The "Noticed" card — the prototype's tuning suggestion, rebuilt so that every
     *  number in it is one this session actually produced. The observed peak is compared
     *  against what was COMMANDED (labelled as such), never against the routine's
     *  configured peak target dressed up as an outcome. The words are Summary#noticed's,
     *  from the record, so a summary reopened from History compares its own figures. */
    private void noticedCard(Model.Sess s, boolean nothingDelivered) {
        String text = Summary.noticed(s, nothingDelivered);
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, 12), Ui.dp(a, Look.S5), Ui.dp(a, Look.S5));
        // M4: the section label's own style, as drawn, and the words at reading size.
        Ui.fieldLabel(a, card, "Noticed", null);
        TextView v = new TextView(a);
        v.setText(text);
        v.setTextColor(Ui.TEXT);              // PR-12: prose in TEXT, at the body size
        v.setTextSize(Look.SP_BODY);
        v.setLineSpacing(0f, 1.2f);   // the drawing's 1.45 line height over the font's own
        v.setPadding(0, Ui.dp(a, Look.S2), 0, 0);
        card.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 10);
        a.body.addView(card, lp);
    }

    /**
     * A SUMMARY HEADING IS NOT AMBER.
     *
     * Look.java reserves amber for the pump being under pressure - the narrowest colour in
     * the app - and every one of these three headings sits on a screen that exists BECAUSE
     * the pump is vented: "Delivered", "As you ran it", "Noticed". A colour that means "the
     * cuff is under command right now" printed over a report of a finished session is the
     * one reading it can never have there, and it weakens the amber everywhere else by
     * appearing where it cannot be true.
     */
    private void cardTitle(ViewGroup parent, String t) {
        // AS-5: ONE HEADING STYLE. This was an 11 sp line in whatever case the caller
        // passed, beside fieldLabel and sectionLabel on the same screen; it is the field
        // label now, and every caller passes sentence case (a reader hears it as written).
        View v = Ui.fieldLabel(a, parent, t, null);
        v.setPadding(0, 0, 0, Ui.dp(a, 6));
    }

    /**
     * AS-6: ONE FACT ON THE DELIVERED CARD - Ui.kvRow, the label left and the figure right,
     * as every other card's facts are. Returns the VALUE view (kvRow's second child) so the
     * dose can count up into it.
     */
    private TextView factRow(ViewGroup card, String label, String value, int colour) {
        LinearLayout r = Ui.kvRow(a, card, label, value, colour, null);
        return (TextView) r.getChildAt(1);
    }

    /** "time under pressure" -> "Time under pressure": PlannedTime's words, row case. */
    private static String capFirst(String t) {
        if (t == null || t.length() == 0) return "";
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /**
     * The dose counts up from 0 into its row (round-4 P6, "dose numbers" ease in) - the
     * same ~200 ms decelerated ease SessionActivity's tweenDoseFact gave the old line, now
     * writing only the figure, through Model.Fmt.dose, into the row's value view.
     */
    private void tweenDose(TextView v, double finalDose) {
        if (v == null) return;
        android.animation.ValueAnimator anim =
            android.animation.ValueAnimator.ofFloat(0f, (float) finalDose);
        anim.setDuration(Look.resolveDuration(200, a.animScale()));
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(new DoseRowUpdate(v));
        anim.start();
    }

    /** tweenDose's update listener (no lambda - ValueAnimator wants a named class). */
    private static final class DoseRowUpdate
            implements android.animation.ValueAnimator.AnimatorUpdateListener {
        private final TextView v;
        DoseRowUpdate(TextView v) { this.v = v; }
        @Override public void onAnimationUpdate(android.animation.ValueAnimator anim) {
            v.setText(Model.Fmt.dose((Float) anim.getAnimatedValue()));
        }
    }
}
