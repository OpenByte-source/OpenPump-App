package org.openpump;

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
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * THE LIBRARY AND ITS EDITORS - the set list, the routine list, the set editor,
 * the routine editor, the stage editor, "used in", the version history and the
 * picker - lifted out of SessionActivity verbatim. See
 * docs/superpowers/plans/2026-09-07-sessionactivity-split.md.
 *
 * THE DRAFT AND THE PARAMETER MACHINERY STAY ON THE ACTIVITY. An editor session is
 * state that must survive this screen being rebuilt, and the parameter helpers are
 * shared with the manual-run screen and with the typed-value dispatcher. This class
 * is a renderer over both.
 */
final class LibraryScreen {
    private final SessionActivity a;

    LibraryScreen(SessionActivity a) { this.a = a; }

    /** The LIBRARY destination: reopen whichever section the user last had. */
    void showLibrary() {
        if (a.libraryOnSets) a.showSets(); else showRoutines();
    }

    private void snapshotRoutine(Model.Routine r) {
        if (r == null) return;
        if (r.id != null && r.id.equals(a.routSnapId)) return;
        a.routSnapId = r.id;
        a.routSnapJson = a.jsonOf(r);
        a.routDirty = false;
        a.setSnapId = null; a.setSnapJson = null; a.setDirty = false;
    }

    /** The lime SAVE the editors put under their controls. */
    private final class EditorSaveTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.commitEditor();
            Ui.snack(a, a.rootFrame, "Saved");
            if (a.setSnapId != null) showSetEdit(a.setSnapId);
            else if (a.routSnapId != null) showRoutEdit(a.routSnapId);
        }
    }

    /** The editors' explicit Save row: lime when there is something to save, and a plain
     *  "no changes" line when there is not — a Save button that is always live teaches
     *  people it does nothing. */
    private void editorSaveButton(ViewGroup parent) {
        if (a.editorDirty()) {
            Button save = Ui.big(a, parent, "Save", Ui.ACCENT);
            save.setContentDescription("Save the changes to " + a.editorName());
            save.setOnClickListener(new EditorSaveTap());
        } else {
            Ui.note(a, parent, "No unsaved changes.");
        }
    }

    /**
     * Set editor, per #v-step-edit / renderSetEdit(): name (via a Rename dialog — an
     * inline EditText would lose focus/keyboard on every stepper tap, since the whole
     * screen is rebuilt on every change), Fixed/Ramp mode, the six numbers, and in ramp
     * mode the end target/drop/power, step count, and the resulting preset ladder listed
     * explicitly — the prototype calls this "load-bearing honesty" about the hardware.
     * Every pressure goes through Model.Fmt.p; every printed range is the range clamp()
     * actually enforces (min(57, ceiling), never the bare theoretical max).
     */
    /**
     * THE LONG-SET ADVISORY, in its own slot so every edit can re-decide it. Called by the
     * build below and by {@link SessionActivity#afterSetValueEdit} — the repaint that
     * runs after a bump or a typed value, which is exactly when a set becomes a long one.
     * Drawn once by the build, it said nothing until the editor was closed and reopened,
     * and this screen is the only place a set can be made this long.
     *
     * Says minutes rather than {@link Model.Fmt#t}'s clock form: "longer than the 20:00 the
     * guidance suggests" reads as a time of day, where "longer than 20 minutes" reads as a
     * length.
     */
    static void paintLongHoldNote(SessionActivity a, Model.Set s) {
        if (a.setEditHoldNote == null) return;
        a.setEditHoldNote.removeAllViews();
        if (s == null || s.uh <= Plan.SET_ADVISORY_SEC) return;
        String advised = (Plan.SET_ADVISORY_SEC / 60) + " minutes";
        // LB-19: "the guidance", never a named or unnamed source.
        Ui.noteInfo(a, a.setEditHoldNote,
            "Longer than " + advised + " — the guidance is under "
            + (Plan.SET_ADVISORY_SEC / 60) + " per set, ideally 10.",
            "Long holds",
            "The guidance is explicit that no single set should run longer than " + advised
            + ", and suggests ten minutes or less. This set holds for "
            + Model.Fmt.t(s.uh) + ".\n\nNothing is blocked and nothing is changed — a "
            + "routine you built by hand is yours. Nothing the trainer writes for you is "
            + "ever this long; the longest hold any prescription produces is five minutes.");
    }

    void showSetEdit(String id) {
        Model.Set s = a.model.set(id);
        if (s == null) { a.showSets(); return; }     // a render must never navigate past a
        a.editSetId = id;                             // deleted set — but this IS navigation
                                                      // triggered by the caller opening a
                                                      // stale id, not a background render.
        a.snapshotSet(s);
        a.body.removeAllViews();
        // T11: every render starts with no live Pull-row Buttons captured — the ramp
        // branch below re-captures them if and when it actually builds that row, so a
        // rest set or a fixed set (neither of which reaches it) never leaves a stale
        // reference to a Button that belonged to a screen no longer on display.
        a.graphPullStartBtn = null; a.graphPullEndBtn = null;
        // O3 — every capture from the PREVIOUS render dies with the views it pointed at.
        // A Button belonging to a screen that has been removed must never be written to.
        a.setRowValue.clear(); a.setRowSlider.clear(); a.setRowLabel.clear();
        a.setEditSay = null; a.setEditFoot = null; a.setEditLadderHint = null; a.setChartView = null;
        a.setEditHoldNote = null;
        // Leaving is no longer an unconditional return: edits are still written live, but
        // the chevron (and the hardware Back, which is the same listener) asks Save/Discard
        // when anything has changed since the editor opened — see EditorLeave.
        // Wave 4 item 1: opened from a routine, the editor returns TO that routine —
        // Back used to dump the user on the Sets page they never visited.
        a.header(a.body, s.name, a.new EditorLeave(a.setEditReturnRout != null
                ? new ReturnToRoutineTap(a.setEditReturnRout)
                : a.new Tap(SessionActivity.Tap.SETS)));

        // A REST HAS ONE SETTING, so its editor has one control. The Fixed/Ramp choice, the
        // waveform preview, the five pressure parameters, the preset ladder, the cycle
        // arithmetic and the estimated dose below are all statements about pressure the
        // pump is commanded to deliver — and a rest commands none. Showing them greyed
        // would suggest a rest could be given a pressure; showing them live would let
        // someone set one that is silently never sent. So the screen ends here.
        if (s.rest) {
            Ui.card(a, a.body, "Rest", s.say());
            // LB-18: a value row, "Name — Rest", not a button reading "Name: Rest".
            Ui.kvRow(a, a.body, "Name", s.name, Ui.TEXT, a.new RenameSetTap());
            a.setParam(a.body, SessionActivity.Tap.DUR, "Rest for", Model.Fmt.t(s.dur), s);
            a.markSwatchRow(a.body, s.mark, false);
            int usedRest = a.model.usedIn(s.id);
            Ui.note(a, a.body, "Used in " + usedRest + " routine"
                + (usedRest == 1 ? "" : "s") + ".");
            Ui.note(a, a.body,
                "The run screen shows REST for its whole length, and the recording files it as rest — not as a hold.");
            return;
        }

        // LB-9: a two-way switch, where two ●/○ buttons were.
        Ui.segmented(a, a.body, new String[]{ "Fixed", "Ramp" },
            new String[]{ "the same values throughout", "values that climb from start to end" },
            s.ramp ? 1 : 0,
            new View.OnClickListener[]{ a.new SetModeTap(false), a.new SetModeTap(true) });

        a.markSwatchRow(a.body, s.mark, false);

        // T11 — the drag-the-curve graph is a RAMP-only idea (a fixed set has nothing to
        // ramp: SetWaveView already degenerates a fixed set's envelope to a flat line, and
        // dragging one flat point to another flat point reshapes nothing). The toggle only
        // exists once there is a curve worth dragging.
        if (s.ramp)
            // LB-9: both options were graphs; they are named for what the chart does.
            Ui.segmented(a, a.body, new String[]{ "View", "Drag to edit" },
                new String[]{ "the chart shows the set", "drag the chart's handles to edit" },
                a.setGraphMode ? 1 : 0,
                new View.OnClickListener[]{ a.new SetGraphModeTap(false),
                                            a.new SetGraphModeTap(true) });

        // The SAME pressure-waveform preview manual mode shows — one chart for a set,
        // wherever it is edited, fixed or ramp alike (a ramp draws as a rising staircase).
        // Previously this screen had only a text list, so a set looked different here than
        // in manual mode; now the picture is identical and the exact preset list stays below.
        //
        // T11: in Graph mode (ramp sets only) this SAME card shows RampDragView instead —
        // the drag-editable pull curve — never both at once, so there is only ever one
        // chart's worth of touch handling live on this screen at a time.
        boolean showGraph = s.ramp && a.setGraphMode;
        LinearLayout setChart = Ui.col(a);
        setChart.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, setChart, Ui.ELEV_CARD);
        setChart.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 8));
        if (showGraph) {
            setChart.addView(a.new RampDragView(a, s), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 130)));
            Ui.group(setChart, "Drag the start or end point to reshape the pull curve of "
                + "this ramp. The Pull row below shows the same two values and can edit "
                + "them precisely instead.");
        } else {
            a.setChartView = a.new SetWaveView(a, s, false);
            // F15b - TAP A REPETITION ON THE CHART TO OPEN IT. Armed only when this set
            // HAS repetitions to open and per-repetition editing is on; otherwise the
            // chart stays the read-only preview it has always been.
            boolean repChart = a.model.repOverridesEnabled && !s.ramp && !s.rest
                             && s.repCount() > 1;
            if (repChart) a.setChartView.withRepTaps();
            setChart.addView(a.setChartView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 92)));
            Ui.group(setChart, repChart
                ? ("The pressure this set commands over time, " + s.repCount()
                   + " repetitions of it. Tap anywhere on the chart to open the repetition "
                   + "playing at that point and give it its own values. Repetitions that "
                   + "already have their own are shaded.")
                : "Preview of the pressure this set commands over time");
            if (repChart)
                Ui.note(a, setChart, "Tap the chart to edit one repetition · "
                    + "shaded stretches already differ");
        }
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.topMargin = Ui.dp(a, 6); scLp.bottomMargin = Ui.dp(a, 10);
        a.body.addView(setChart, scLp);

        int cap = Math.min(57, a.model.ceilKpa);
        a.setEditSay = Ui.col(a);
        a.fillSetSay(a.setEditSay, s);
        a.body.addView(a.setEditSay, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // O1 — SAID BEFORE THE ROWS, NOT AFTER THEM. Sets are shared by reference:
        // deleting one is guarded, editing one never was, so changing a hold time here
        // changed it in every routine using this set with nothing on screen saying so. The
        // count was already known (it is the same one the delete refusal reads); the only
        // thing missing was saying it where the editing happens, before it happens.
        int shared = a.model.usedIn(s.id);
        if (shared > 1) {
            Ui.card(a, a.body, "Used in " + shared + " routines",
                "Every change on this screen applies to all " + shared + " of them — sets are "
                + "shared, not copied. To change it in only one place, duplicate it below and "
                + "swap the copy in from that routine.");
            Button forkIt = Ui.flat(a, a.body, "Duplicate this set and edit the copy");
            forkIt.setContentDescription("Duplicate " + s.name
                + " and open the copy, leaving all " + shared + " routines on the original.");
            forkIt.setOnClickListener(new ForkSharedSetTap(s.id));
        }

        // The DROP range is dynamic: clamp() re-asserts lo < up, so the real maximum is
        // up-1, not min(56, cap). Printing the static bound advertised room the stepper
        // refuses — with a 20 kPa target and a 40 kPa ceiling it read "0.0 to 40.0 kPa"
        // while stopping dead at 19. Same defect class as a printed range quoting a
        // ceiling that is not enforced, one level down.
        if (s.ramp) {
            /* ONE ROW PER PARAMETER, START AND END SIDE BY SIDE. A ramp walks five things
             * from a start value to an end value, and this screen used to list all five
             * starts, then a heading, then all five ends — so "pull goes from −1.8 to
             * −10.0" was two rows and a scroll apart, and you could not change one end
             * while looking at the other. Ui#pairRow puts the pair on one line; the
             * instruments and the clamps behind them are exactly the fixed editor's.
             * Steps and duration have no end value, so they stay single rows below. */
            Ui.note(a, a.body, "Tap − or + to step, or tap a number to type it. Left is the "
                + "start, right the end.");                                    // LB-11
            // T11: the Pull row's own value Buttons are captured so RampDragView can
            // rewrite their text live while a handle is being dragged — see
            // #graphPullStartBtn's own doc. Every OTHER pair row is unaffected: setPair's
            // return value is new, but every other call below still ignores it exactly as
            // it did when the method returned void.
            Button[] pullBtns = a.setPair(a.body, SessionActivity.Tap.UP, SessionActivity.Tap.UP2, "Pull to",
                Model.Fmt.p(s.up), Model.Fmt.p(s.up2));
            a.graphPullStartBtn = pullBtns[0]; a.graphPullEndBtn = pullBtns[1];
            a.setPair(a.body, SessionActivity.Tap.LO,  SessionActivity.Tap.LO2,  "Release to", Model.Fmt.p(s.lo), Model.Fmt.p(s.lo2));
            a.setPair(a.body, SessionActivity.Tap.UH,  SessionActivity.Tap.UH2,  "Hold the pull", s.uh + " s",    s.uh2 + " s");
            a.setPair(a.body, SessionActivity.Tap.LH,  SessionActivity.Tap.LH2,  "Hold the release", s.lh + " s", s.lh2 + " s");
            a.setPair(a.body, SessionActivity.Tap.SP,  SessionActivity.Tap.SP2,  "Suction power", s.sp + " %",    s.sp2 + " %");
            a.setParam(a.body, SessionActivity.Tap.STEPS, "Steps", String.valueOf(s.steps), s);
            a.setParam(a.body, SessionActivity.Tap.DUR, "Runs for", Model.Fmt.t(s.dur), s);
            // WHAT THAT DURATION IS, as the fixed editor says it: "2 steps × 6 cycles (4:12
            // per step)" - every step whole cycles of its own hold and drop (0.10).
            Ui.note(a, a.body, s.rampSaid() + ".");
            // The preset ladder moved into fillSetFoot with the rest of the derived text
            // (O3) — it is a consequence of the rows above, so it re-renders with them.
        } else {
            Ui.note(a, a.body, "Tap − or + to step, drag to sweep, or tap the number to type "
                + "it.");                                                      // LB-11
            /* ONE VOCABULARY, AND ONE THAT SAYS WHAT A CYCLE IS.
             *
             * Six numbers used to have three sets of names - the set editor's, the manual
             * screen's ("Max pressure", "Drop time") and the run tiles' - so learning one
             * screen did not let you read another. They are now the set editor's everywhere,
             * with the run tiles keeping their short uppercase forms, which is a compression
             * of the same words rather than a third language.
             *
             * The hold pair is also renamed. "Hold at target" and "Hold at drop" introduced
             * two nouns - target, drop - for the two pressures the rows above them already
             * name. Each duration now names the pressure it belongs to, so the four rows read
             * as the two phases they are: pull to X and hold the pull, release to Y and hold
             * the release. That is the cycle, in the order the pump performs it. */
            a.setParam(a.body, SessionActivity.Tap.UP, "Pull to", Model.Fmt.p(s.up), s);
            a.setParam(a.body, SessionActivity.Tap.LO, "Release to", Model.Fmt.p(s.lo), s);
            a.setParam(a.body, SessionActivity.Tap.UH, "Hold the pull", s.uh + " s", s);
            /* WHAT THE GUIDANCE SAYS ABOUT A LONG SET, said where a long one can be built.
             * Model.Set#HOLD_MAX_SEC allows an hour, correctly - ladder() stitches a hold
             * that long and the wire carries it. The guidance is separately explicit that a
             * SET should not: it caps a single set at 20 minutes, ideally 10 or less. Nothing
             * the plan writes reaches this; only a hand-built set can, and this is the one
             * place one is built. Advisory, like the oedema note on the run screen - it
             * states what the guidance says and changes nothing. */
            // ITS OWN SLOT, so the advisory can appear the moment the hold crosses the
            // line rather than the next time this screen is built — see
            // paintLongHoldNote, which the repaint after every edit calls too.
            a.setEditHoldNote = Ui.col(a);
            a.body.addView(a.setEditHoldNote, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            paintLongHoldNote(a, s);
            a.setParam(a.body, SessionActivity.Tap.LH, "Hold the release", s.lh + " s", s);
            a.setParam(a.body, SessionActivity.Tap.SP, "Suction power", s.sp + " %", s);
            a.setParam(a.body, SessionActivity.Tap.DUR, "Runs for", Model.Fmt.t(s.dur), s);
            // WHAT THAT DURATION IS, IN THE UNIT YOU THINK IN. The editor asks for a length
            // and a cycle; the number a person plans in is repetitions, and it was derivable
            // from two other rows and stated nowhere. Through Manual#cycles - the same
            // division the chart's bands and the run screen's own counter use.
            int reps = Manual.cycles(s.uh, s.lh, s.dur);
            Ui.note(a, a.body, reps <= 1
                ? ("One repetition of " + Model.Fmt.t(s.cycle()) + ".")
                : (reps + " repetitions of " + Model.Fmt.t(s.cycle()) + "."));
        }

        // O2 — THE RUNGS. Placed under the rows it describes, and hidden outright in kPa
        // where the step is the unit and there is no gap to explain.
        a.setEditLadderHint = new TextView(a);
        a.setEditLadderHint.setTextColor(Ui.DIM);
        a.setEditLadderHint.setTextSize(Look.SP_CAPTION * 0.9f);
        a.setEditLadderHint.setPadding(Ui.dp(a, 2), 0, 0, Ui.dp(a, 6));
        a.paintLadderHint(s);
        a.body.addView(a.setEditLadderHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // F15b - the per-repetition list, between the rows and the derived text: it is a
        // control, so it belongs above the sentences the rows imply, and it is about the
        // rows above it, so it belongs below them.
        repOverrideRows(a.body, s);

        a.setEditFoot = Ui.col(a);
        a.fillSetFoot(a.setEditFoot, s);
        a.body.addView(a.setEditFoot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The estimated dose and the Used-in door both live in fillSetFoot now (O3): both
        // are derived from the rows, so both re-render when a row changes. The dose says
        // "estimated" because it is a claim about the COMMANDED shape — a real cuff climbs
        // over seconds and leaks a little at the top, so a run always measures somewhat under.

        // ▶ TRY THIS SET — run what the editor is showing, once, without saving it. The
        // seal check follows the Manual screen's own toggle, because this IS a manual run.
        Button tryIt = Ui.big(a, a.body, "▶ Try this set", Ui.CMD);
        tryIt.setContentDescription("Try " + s.name + " — run these values once now, "
            + "without saving the set");
        tryIt.setOnClickListener(a.new TrySetTap());

        // STAR IT, AND IT IS ON TODAY (F3). Sets could always be run on their own
        // through the button above; what was missing was a way to get here in fewer
        // than three taps. A rest set is excluded — there is nothing to run.
        if (!s.rest) {
            Button fav = Ui.flat(a, a.body,
                (s.star ? "★ On Today" : "☆ Add to Today"));
            fav.setContentDescription(s.star
                ? "Remove " + s.name + " from Today's favourites"
                : "Add " + s.name + " to Today's favourites");
            fav.setOnClickListener(a.new ToggleSetStarTap());
        }
        Ui.noteInfo(a, a.body, "Runs these values once, now — nothing is saved.",
            "Try this set",
            "Runs these values once, now — nothing is saved. It is recorded in "
            + "Sessions as a manual run. Change the seal check on the Manual screen.");

        editorSaveButton(a.body);

        Ui.row(a, a.body, new String[]{ "Duplicate", "Rename" },
            new View.OnClickListener[]{ a.new DupSetTap(), a.new RenameSetTap() });
        boolean setLocked = a.running && a.runRoutine != null && a.runRoutine.usesSet(a.editSetId);
        /* LB-14: A DELETE THAT WOULD BE REFUSED SAYS SO BEFORE THE TAP. A set a routine
         * uses cannot be deleted; the red button used to refuse with a toast. It is the
         * quiet danger button now, off while the set is in use, with the reason under it. */
        int usedBy = a.model.usedIn(a.editSetId);
        Button del = Ui.danger(a, a.body, setLocked ? "Delete" + a.AFTER_RUN : "Delete",
                               !setLocked && usedBy == 0);
        if (setLocked) del.setTextColor(Ui.FAINT);
        del.setOnClickListener(a.new DelSetTap());
        if (usedBy > 0 && !setLocked) {
            Ui.setEnabled(a, del, false, Ui.SURFHI, Ui.CRIT);
            Ui.note(a, a.body, "Used in " + usedBy + " routine" + (usedBy == 1 ? "" : "s")
                + " — remove it from " + (usedBy == 1 ? "that routine" : "them")
                + " to delete.");
        }
        // The full-width "Done" that used to sit here was pure navigation back to Sets —
        // now the header arrow. Delete stays as the screen's one destructive action.
    }

    /** O1 — duplicate a shared set and open the COPY, leaving every routine that used the
     *  original still using the original. The one action that turns "this changes three
     *  routines" into "this changes the one I am about to point it at". */
    private final class ForkSharedSetTap implements View.OnClickListener {
        private final String id;
        ForkSharedSetTap(String id) { this.id = id; }
        @Override public void onClick(View v) {
            Model.Set src = a.model.set(id);
            if (src == null) return;
            // The SAME duplicate DupSetTap makes — Set#copy names it, clears fromRun and
            // `imported`, and carries the colour mark across (see its own note).
            String nid = a.model.newSetId();
            a.model.sets.add(src.copy(nid));
            Store.save(a, a.model);
            a.toast("Copied — the " + a.model.usedIn(id) + " routines still use the original");
            showSetEdit(nid);
        }
    }

    /* ===================== F15b - ONE REPETITION AT A TIME ===========================
     *
     * A fixed set repeats one cycle for its whole duration, and every repetition of it was
     * identical by construction. Wanting the fourth one deeper meant splitting the set in
     * two and losing the fact that it was one thing.
     *
     * THE LIST IS THE SET'S REPETITIONS, each saying whether it is the set's own values or
     * its own. Tapping one opens the same value rows the set has, seeded from whatever that
     * repetition currently plays; "back to the set" clears it. Clearing writes nothing -
     * it removes the override, so a later edit of the set still reaches that repetition,
     * which is the whole difference between "the same as the set" and "a copy of what the
     * set was when I looked".
     *
     * OFF UNTIL THE SEAM TEST. An overridden set reaches the pump as one preset per
     * repetition rather than one preset repeated, and whether the device plays adjacent
     * presets as one continuous set is a hardware fact. The section says so and offers the
     * switch rather than pretending the question is settled.
     */
    private void repOverrideRows(LinearLayout into, Model.Set s) {
        if (s.ramp || s.rest) return;          // a ramp's repetition IS its step
        int n = s.repCount();
        if (n <= 1 && s.reps.isEmpty()) return; // nothing repeats; nothing to vary
        LinearLayout g = Ui.cardGroup(a, into, "Repetitions", null, Ui.DIM);
        if (!a.model.repOverridesEnabled && s.reps.isEmpty()) {
            Ui.noteSafety(a, g, "This set plays " + n + " identical repetitions of its cycle. "
                + "They can be varied one at a time \u2014 a deeper third rep, a longer last "
                + "one \u2014 but a varied set is sent to the pump as one preset per "
                + "repetition instead of one repeated, and whether your device plays those "
                + "back to back without a break has not been tested on it yet.");
            Button on = Ui.flat(a, g, "I have run the seam test \u2014 enable this");
            on.setContentDescription("Enable per-repetition overrides. Only do this after "
                + "the seam test has shown your pump plays adjacent presets without a break.");
            on.setOnClickListener(new EnableRepOverridesTap());
            return;
        }
        Ui.note(a, g, n + " repetitions of " + Model.Fmt.t(s.cycle())
            + ". A repetition with no values of its own plays the set's.");
        for (int i = 1; i <= n; i++) {
            Model.Set.Rep ov = s.repAt(i);
            Button row = Ui.flat(a, g, "Repetition " + i + "  ·  "
                + (ov == null ? "same as the set"
                              : Model.Fmt.p(ov.up) + " \u2192 " + Model.Fmt.p(ov.lo)
                                + "  \u00b7  " + ov.uh + "/" + ov.lh + " s  \u00b7  " + ov.sp + " %"));
            row.setContentDescription("Repetition " + i + " of " + n + ", "
                + (ov == null ? "playing the set's own values"
                              : "with its own values") + ". Opens it.");
            row.setOnClickListener(a.new EditRepTap(s.id, i));
        }
        if (!s.reps.isEmpty())
            Ui.note(a, g, "This set now sends " + n + " presets instead of one. It plays "
                + "the same total time either way.");
    }

    private final class EnableRepOverridesTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.repOverridesEnabled = true;
            Store.save(a, a.model);
            showSetEdit(a.editSetId);
        }
    }

    /**
     * Routines library, per proto/pump-console.html's #v-routines / renderRoutines():
     * each row pairs a small "show on Today" toggle (mirrors the prototype's ★/☆, here
     * a plain ●/○ since this app has one selected routine, not the prototype's
     * multi-favourite star) with a wider button that opens the routine editor — two
     * separate touch targets, both ≥48dp, rather than overloading one tap with two
     * meanings.
     */
    void showRoutines() {
        a.body.removeAllViews();
        a.enterDest(Nav.SCR_ROUTINES);
        a.libraryOnSets = false;
        Ui.head(a, a.body, "Library");
        // WAVE 4 ITEM 10: the Sets tab is GONE — Library is routines. Sets are met where
        // they are used (a stage's "+ Add sets from library" picker, now the set browser)
        // and the full list stays reachable through that picker and Settings › Data.
        // The definition is behind the count's ⓘ (result screens: Library).
        Ui.noteInfo(a, a.body, "Routines  ·  " + a.model.routines.size(), "Routines",
             "Routines  ·  " + a.model.routines.size()
             + " — an ordered list of sets, grouped into stages. It is what START runs.");
        // "+ New routine" is the screen's one main action. "Add from code" is a second way
        // in, so it is a small lime link BESIDE it rather than a second full-width button of
        // the same weight under it (the owner's note on the Library tiles).
        LinearLayout adds = new LinearLayout(a);
        adds.setOrientation(LinearLayout.HORIZONTAL);
        adds.setGravity(Gravity.CENTER_VERTICAL);
        a.body.addView(adds, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button add = Ui.big(a, adds, "+ New routine", Ui.ACCENT);
        LinearLayout.LayoutParams addLp = (LinearLayout.LayoutParams) add.getLayoutParams();
        addLp.width = 0;
        addLp.weight = 1f;
        add.setOnClickListener(new NewRoutineTap());
        // LB-18: the app's own text link, where a link was built by hand.
        TextView addFromCode = Ui.textLink(a, "Add from code", true, new AddFromCodeTap());
        addFromCode.setTextSize(Look.SP_BODY);
        addFromCode.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 6), 0);
        addFromCode.setContentDescription("Add from code. Paste a routine someone shared "
            + "with you.");
        adds.addView(addFromCode, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* THE ROUTE THE PHRASE "ROUTINE BUILDER" ACTUALLY POINTS AT.
         *
         * A routine here is hand-built. The TRAINER writes routines too, and the controls
         * that shape what it writes - warm-up, prime, ease-in, retention, hold length, rest,
         * block shape - were reachable only from inside the girth track's detail view, which
         * is not a place anybody looking for a builder would go. The owner's report was that
         * they had never seen them.
         *
         * Not a copy of the controls: a door to the one place they live. */
        // WITH ITS VALUES, like every door into that room (Ui#doorRow) - and the same facts
        // the room's own "Saved shapes" rows print, so the two cannot disagree.
        String[] shapeFacts = Model.Shape.capture(a.model, "", "").summaryParts(a.model);
        LinearLayout shaped = Ui.doorRow(a, a.body, "How the trainer shapes its routines",
            shapeFacts, a.new OpenTrainerShapeTap());
        if (shaped != null)
            shaped.setContentDescription("How the trainer shapes its routines: "
                + Say.doorSaid(shapeFacts) + ". Opens the Trainer.");

        /* O5 - ONE VERTICAL SCALE FOR EVERY TRACE ON THIS SCREEN, and it has to be able
         * to HOLD the data. The first version scaled to the safety ceiling alone, which
         * was wrong twice over: the ceiling is a cap applied to a routine AT RUN TIME
         * (AsRun#ceilKpa), not a bound on what a stored routine may ask for, so any
         * routine targeting more than the current ceiling was clipped to the top of the
         * box - and once two routines are both clipped they draw as the SAME full-height
         * block however far apart their peaks are, which destroys the one comparison the
         * row exists to make. The scale is the ceiling OR the deepest pull any routine on
         * this screen commands, whichever is larger: never clipped, and still shared, so a
         * gentle routine still looks lower than a hard one rather than being normalised up
         * to match it. */
        int traceCeil = Math.max(1, Math.min(57, a.model.ceilKpa));
        for (int i = 0; i < a.model.routines.size(); i++) {
            java.util.List<Model.Preset> ps = a.model.plan(a.model.routines.get(i));
            for (int j = 0; j < ps.size(); j++)
                if (!ps.get(j).rest && ps.get(j).up > traceCeil) traceCeil = ps.get(j).up;
        }

        for (int i = 0; i < a.model.routines.size(); i++) {
            Model.Routine r = a.model.routines.get(i);
            boolean sel = r.id.equals(a.model.selected);

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            /* A2 - WHY THE OPEN BUTTON WAS LOSING TEN DP OF ITS OWN HEIGHT.
             *
             * A horizontal LinearLayout baseline-aligns its children by default, and this row
             * mixes one- and two-line buttons: the ★ and ● glyphs beside a routine name that
             * wraps. Aligning the first text baseline pushed the name button DOWN inside the
             * row and left it 38.2dp tall against the 48dp its own layout params ask for -
             * measured on the device, not inferred. Every other row in this file already sets
             * CENTER_VERTICAL and does not have the problem; this one never did.
             *
             * Both lines, because they fix different halves: baseline alignment is what
             * shrinks it, centring is what the rest of the app does with a mixed-height row. */
            row.setBaselineAligned(false);
            row.setGravity(Gravity.CENTER_VERTICAL);

            // ★ decides what appears in Today's Favourites; ● decides which routine
            // START actually runs. Two separate meanings, two separate ≥48dp targets —
            // the prototype has the same split (r.star vs SELR).
            Button star = new Button(a);
            star.setText(r.star ? "★" : "☆");
            // The filled/hollow glyph carries the state; the colour is only emphasis, so a
            // bright-vs-dim pair says it without borrowing amber (a favourite is not the pump
            // under command) and without reaching for the LIME the ● select marker beside it
            // wears (Task 13 fix round — selection is lime-on-lime-wash everywhere in this
            // app, see the pick button below; a favourite star is a third, unrelated state
            // and must not borrow either colour).
            star.setTextColor(r.star ? Ui.TEXT : Ui.DIM);
            star.setTextSize(Look.SP_HEADING);
            // The two glyph squares are CONTROLS, not dividers: LINE is the app's hairline
            // colour and a 52dp block of it reads as a gap in the card. SURFHI is the
            // raised-control surface every other tappable square uses.
            star.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL));
            star.setContentDescription(r.star ? "Favourite, on: " + r.name + " is shown on Today"
                                               : "Favourite, off: " + r.name
                                                 + " is not shown on Today");
            star.setSelected(r.star);
            LinearLayout.LayoutParams starLp =
                new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 48));
            starLp.rightMargin = Ui.dp(a, 6);
            star.setOnClickListener(new StarRoutineTap(r.id));
            row.addView(star, starLp);

            // LB-2: WHICH ROUTINE START RUNS, IN WORDS - a "Use" / "In use" chip where an
            // unlabelled ○/● square sat beside the favourite star.
            LinearLayout pick = Ui.toggleChip(a, sel ? "In use" : "Use", null, null, 0, 0, sel,
                sel ? r.name + ": in use, this is what START runs"
                    : r.name + ": not in use, tap to make START run this",
                new SelectRoutineTap(r.id));
            LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 48));
            pickLp.rightMargin = Ui.dp(a, 6);
            row.addView(pick, pickLp);

            Button open = new Button(a);
            open.setText(Say.routineDisplayTitle(r));      // LB-4: the display title
            open.setAllCaps(false);
            open.setTextSize(Look.SP_BODY);
            open.setTypeface(open.getTypeface(), android.graphics.Typeface.BOLD);
            open.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            open.setTextColor(Ui.TEXT);
            /* TWO LINES BEFORE AN ELLIPSIS. A minted name ends in its prescription, and in a
             * LIST that prescription is the whole of what tells two routines apart - so
             * truncating from the end kept the word "Trainer" and cut the only informative
             * part. Today prints the numbers on their own line beneath; here there is no
             * beneath, so the name gets the room instead. */
            open.setSingleLine(false);
            open.setMaxLines(2);
            open.setEllipsize(android.text.TextUtils.TruncateAt.END);
            open.setBackgroundColor(0x00000000);
            open.setPadding(Ui.dp(a, 2), 0, 0, 0);
            open.setContentDescription("Open " + r.name + "." + a.markSay(r.mark));
            open.setOnClickListener(new OpenRoutEditTap(r.id));
            LinearLayout.LayoutParams openLp =
                new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f);
            row.addView(open, openLp);

            // THE SELECTED PILL, on the title row of the one routine START will run. The
            // ● square already says it, but it says it in a glyph two controls to the
            // left of the name; the pill puts the word on the name itself. LIME tint —
            // selection is the user's own choice shown back, never amber (nothing here is
            // the pump under pressure) and never green (nothing here is confirmed safe).
            if (sel) {
                // LB-1: "Runs on Start", with no star - ★ means favourite on this same row.
                TextView selPill = Ui.microLabel(a, null, "Runs on Start", Ui.ACCENT);
                selPill.setBackground(Ui.roundRect(a, Ui.ACCENT_DIM, Look.R_PILL));
                selPill.setPadding(Ui.dp(a, 8), Ui.dp(a, 2), Ui.dp(a, 8), Ui.dp(a, 2));
                // The ● button beside it already announces the selection in full; a second
                // stop saying the same thing is noise, so the pill is drawn only.
                selPill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(selPill);
            }

            /* THE ROUTINE CARD. The row of controls, then the stage RAIL — segments in
             * proportion to how long each stage runs — then the four figures that decide
             * whether this is the routine to run today, as tiles rather than as a
             * dot-separated sentence nobody parses. The SELECTED routine also lists its
             * stages with the rail's own colours as dots, so the bar above and the list
             * below are visibly the same thing rather than two descriptions of it. */
            LinearLayout card = Ui.col(a);
            // The selected routine is this screen's ONE live card — the mockup's `.card.pop`:
            // the lime ring and the deeper shadow, not a slightly lighter grey nobody sees.
            card.setBackground(sel ? Ui.popRing(a)
                                   : Ui.roundRect(a, Ui.SURF, Look.R_CARD));
            Ui.lift(a, card, sel ? Ui.ELEV_POP : Ui.ELEV_CARD);
            card.setPadding(Ui.dp(a, 11), Ui.dp(a, 8), Ui.dp(a, 11), Ui.dp(a, 10));
            card.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            a.stageRailFor(card, r);

            // O5 — the shape, at a scale shared with every other row on this screen.
            java.util.List<Model.Preset> shape = a.model.plan(r);
            if (!shape.isEmpty()) {
                SessionActivity.RoutineTraceView trace = a.new RoutineTraceView(a, shape, traceCeil);
                // Decorative: the tiles right below say duration and peak in words, and a
                // reader announcing "chart" adds a stop that carries nothing new.
                trace.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 22));
                trLp.topMargin = Ui.dp(a, 5);
                card.addView(trace, trLp);
            }

            LinearLayout tiles = new LinearLayout(a);
            tiles.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.topMargin = Ui.dp(a, 6);
            card.addView(tiles, tLp);
            routineTiles(tiles, r, sel);

            if (sel) {
                long[] durs = a.model.stageDurationsMs(r);
                for (int k = 0; k < r.stages.size(); k++) {
                    Model.Stage stg = r.stages.get(k);
                    /* THE SAME RULE THE BAR ABOVE IT USES. This computed its own colour from
                     * the name-and-position heuristic, so the six dots and the six segments
                     * nine pixels above them described one routine two different ways:
                     * fatigue, rest and Work 1 as amber dots under lime bars, and the last
                     * work block as a GREEN dot - green, which in this app means telemetry
                     * confirmed the pump vented - under a blue one. */
                    int colour = a.railColourOf(stg);
                    LinearLayout sr = new LinearLayout(a);
                    sr.setOrientation(LinearLayout.HORIZONTAL);
                    sr.setGravity(Gravity.CENTER_VERTICAL);
                    sr.setPadding(0, Ui.dp(a, 4), 0, 0);
                    View dot = new View(a);
                    dot.setBackground(Ui.roundRect(a, colour, 4));
                    LinearLayout.LayoutParams dLp =
                        new LinearLayout.LayoutParams(Ui.dp(a, 8), Ui.dp(a, 8));
                    dLp.rightMargin = Ui.dp(a, 8);
                    sr.addView(dot, dLp);
                    TextView nm = new TextView(a);
                    // THE STAGE, AND WHAT IS IN IT. A stage name is the user's own word
                    // ("Work", "Cool") and says nothing about what the pump will do there;
                    // the sets are the actual programs. Second line, smaller and FAINT, so
                    // the stage names still read as the list and the sets as their contents.
                    String setsLine = a.stageSetNames(stg);
                    // LB-4: a sub-line that only repeats the stage's own name is not drawn.
                    if (setsLine.equals(stg.name)) setsLine = "";
                    if (setsLine.length() == 0) {
                        nm.setText(stg.name);
                    } else {
                        String both = stg.name + "\n" + setsLine;
                        android.text.SpannableString sp = new android.text.SpannableString(both);
                        int at = stg.name.length() + 1;
                        sp.setSpan(new android.text.style.RelativeSizeSpan(0.82f), at, both.length(),
                                   android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sp.setSpan(new android.text.style.ForegroundColorSpan(Ui.FAINT),
                                   at, both.length(),
                                   android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        nm.setText(sp);
                    }
                    nm.setTextColor(Ui.DIM);
                    nm.setTextSize(Look.SP_CAPTION);
                    sr.addView(nm, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    TextView tm = new TextView(a);
                    tm.setText(Model.Fmt.t((k < durs.length ? durs[k] : 0) / 1000));
                    tm.setTextColor(Ui.DIM);
                    Ui.tabular(tm);
                    tm.setTextSize(Look.SP_CAPTION * 0.9f);
                    sr.addView(tm);
                    Ui.group(sr, stg.name + ", "
                        + Model.Fmt.t((k < durs.length ? durs[k] : 0) / 1000)
                        + (setsLine.length() == 0 ? ""
                            : ". " + A11y.collapse(setsLine.replace("·", "and"))));
                    card.addView(sr);
                }
            }

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = Ui.dp(a, 8);
            a.addWithMark(a.body, card, r.mark, 8);
        }
        if (a.model.routines.isEmpty())
            Ui.card(a, a.body, "No routines yet",
                "A routine is an ordered list of sets, grouped into stages — it is what "
                + "START runs. Tap “+ New routine” above"
                + (a.model.sets.isEmpty()
                    ? " — it starts empty, and a stage's “+ Add sets” has a “+ New set” "
                      + "button right inside it."
                    : "; it starts with one stage holding your first set, and you can add more."));
        Ui.noteInfo(a, a.body, "Tap ☆ to favourite a routine; “Use” picks the one START runs.",
            "Routines", "Tap ★ to list a routine in Today's Favourites, “Use” to select the one "
            + "START runs. Open one to edit its stages and the sets inside them. Editing a set "
            + "changes it in every routine that uses it.");

        // The considered entrance: the same first-paint cascade Today uses (staggerBodyIn),
        // extended to Library's Routines section.
        a.staggerBodyIn();
    }

    /**
     * F3 - A NEW ROUTINE IS ASKED WHERE IT COMES FROM, rather than assembled out of an
     * accident.
     *
     * This used to make a stage called "Work" and put {@code model.sets.get(0)} in it -
     * whatever set happened to be first in the library - then open the editor on that. The
     * name, the one stage and the set were all things nobody chose, and the set was
     * somebody else's work borrowed without being named.
     *
     * The app already knew how to build a routine out of something meaningful: the review
     * splices one out of a session you actually ran ({@link AsRun#MODE_ROUTINE}). That door
     * existed only from a History row, where nobody looking to make a routine would think to
     * go. So the question is asked here, with the two honest answers - from a run, or empty -
     * and no third path is invented for it.
     */
    private final class NewRoutineTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            Ui.dress(a, Ui.dialog(a)
                .setTitle("New routine")
                .setMessage("Build it from a session you actually ran — the review keeps what "
                    + "happened and files it as a routine — or start with an empty stage and "
                    + "add sets yourself.")
                .setPositiveButton("From a session I ran", new NewFromSessionTap())
                .setNeutralButton("Start empty", new NewEmptyRoutineTap())        // LB-18
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    /**
     * THE OTHER DOOR, AND IT NOW OPENS.
     *
     * It used to raise a toast — "pick the session to build it from, open it and choose
     * review & save" — and drop the reader into History to go and find it. That is how the
     * per-stage review page came to be the thing the owner reported never finding: the one
     * place that names it points at a list without saying which rows lead to it, and on a
     * list where most rows have no recording, the door it describes is not drawn at all.
     *
     * So it offers the sessions that can actually become a routine, and opening one goes
     * straight to the wizard.
     */
    private final class NewFromSessionTap implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) { showSessionPicker(); }
    }

    /** How many recent candidates the picker offers. A sheet, not a screen: this is a
     *  choice between a handful of recent runs, and the full list is one tab away for
     *  anybody who wants to go further back. */
    private static final int PICK_SESSIONS_MAX = 12;

    /**
     * THE SESSIONS A ROUTINE CAN BE BUILT FROM, and nothing else on the list.
     *
     * Chosen by {@link AsRun#historyLabel} — the SAME predicate the History rows use to
     * decide whether to draw their own door, which reads the filed Sess alone (hasAsRun,
     * differs, savedAt, manual) and loads no recordings. So this picker cannot offer a row
     * that History would not, and it costs one pass over the log rather than one file read
     * per row.
     */
    private void showSessionPicker() {
        LinearLayout col = Ui.col(a);
        col.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, Look.S5),
                        Ui.dp(a, Look.S5), Ui.dp(a, Look.S5));
        Ui.head(a, col, "Build from a session");
        Ui.note(a, col, "Each one opens the review: one step per stage, where you choose "
            + "what that stage becomes and can edit its numbers before saving.");

        int shown = 0;
        for (int i = 0; i < a.model.sessLog.all.size() && shown < PICK_SESSIONS_MAX; i++) {
            final Model.Sess e = a.model.sessLog.all.get(i);
            int kind = AsRun.historyLabel(e);
            if (kind == AsRun.HIST_NONE) continue;
            shown++;
            String title = (e.routineName == null || e.routineName.length() == 0)
                ? "(routine deleted)" : e.routineName;
            Button b = Ui.flat(a, col, title + "\n" + a.dayLabel(e.ts) + "  \u00b7  "
                + Model.Fmt.t(e.durSec) + "  \u00b7  " + AsRun.historyDoorText(kind));
            b.setContentDescription("Build a routine from " + title + ", " + a.dayLabel(e.ts)
                + ". Opens the review, one step per stage.");
            b.setOnClickListener(new PickSessionTap(e));
        }
        if (shown == 0)
            Ui.noteInfo(a, col, "Nothing here can become a routine yet.",
                "Building a routine from a session",
                "A session becomes a routine when there is something in it the plan did not "
                + "already say: an adjustment made mid-run, a stage skipped, a rest inserted, "
                + "or a manual cycle, which has no plan to differ from at all.\n\n"
                + "A run that went exactly as prescribed has nothing to save that the "
                + "routine it came from does not already hold \u2014 so it offers no door, "
                + "here or in the session list.\n\n"
                + "Run something and change it while it runs, and it will be on this list "
                + "afterwards.");

        ScrollView sc = new ScrollView(a);
        if (android.os.Build.VERSION.SDK_INT >= 29) sc.setEdgeEffectColor(Look.ACCENT);
        sc.addView(col);
        pickSheet = Ui.dialog(a).setView(sc).create();
        pickSheet.show();
        Ui.sheet(a, pickSheet);
    }

    /** Held so a pick can take its own sheet down before the wizard replaces the screen
     *  under it — the same reason every other custom-view sheet in this app keeps one. */
    private AlertDialog pickSheet;

    private final class PickSessionTap implements View.OnClickListener {
        private final Model.Sess sess;
        PickSessionTap(Model.Sess s) { sess = s; }
        @Override public void onClick(View v) {
            if (pickSheet != null) { pickSheet.dismiss(); pickSheet = null; }
            // The person asked for a NEW routine: the Save-changes page preselects it.
            a.openReviewWizardFor(sess, true);
        }
    }

    private final class NewEmptyRoutineTap implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            String id = "r" + System.currentTimeMillis();
            // Through model.newRoutine() so the assessment starts at the defaults the
            // CEILING permits, rather than at a value the next clampAll() would have to
            // correct after the editor had already printed it.
            Model.Routine r = a.model.newRoutine(id, "New routine");
            Model.Stage st = new Model.Stage();
            st.name = "Work"; st.colour = Model.STAGE_WORK;
            // EMPTY MEANS EMPTY. It used to borrow model.sets.get(0) here, so a brand-new
            // routine arrived already commanding a set the person had never chosen and might
            // never have seen.
            r.stages.add(st);
            a.freshRoutineId = id;      // W2 - untouched until something edits it
            a.model.routines.add(r);
            // A DRAFT, exactly as "+ New set" is: in the list so the editor works, not
            // committed, and deleted outright if it is left without saving.
            a.draftRoutId = id;
            a.routSnapId = null;         // force snapshotRoutine to re-snap onto the draft
            a.toast("New routine — Save keeps it, leaving discards it");
            showRoutEdit(id);
        }
    }

    /**
     * A ROUTINE CARD'S FOUR FIGURES, SAID PROPERLY: Length, Peak, Stage(s), Runs.
     *
     * They were the run screen's parameter tiles, which read as code here: "1 STAGES", "0x"
     * over RUNS, and a pressure whose unit dropped to a line of its own under the number,
     * so one tile of four stood a line taller. Now a label agrees with its number, a routine
     * never run says "None yet", and a unit sits BESIDE its number, small and dim, on the
     * same line.
     *
     * The peak goes through Model.Fmt.p, so it follows the display unit and reads negative
     * in inHg - never a bare number with kPa assumed. It is TEXT, not amber: this is what a
     * stored routine asks for, not a pump under pressure.
     *
     * A tile is one step away from its card: SURFACEHI on a plain card, as the mock draws
     * it, and SURFACE on the selected card, whose own fill is already SURFACEHI.
     */
    private void routineTiles(LinearLayout row, Model.Routine r, boolean sel) {
        row.removeAllViews();
        int fill = sel ? Ui.SURF : Ui.SURFHI;
        int stages = r.stages.size();
        String peak = Model.Fmt.p(a.model.workPeakKpa(r));
        // LB-3: "Duration" - "Length" is a body measurement in this app.
        libTile(row, fill, Model.Fmt.t(a.model.routineSec(r)), "", "Duration", true, true);
        libTile(row, fill, Say.valueWordOf(peak), Say.unitWordOf(peak), "Peak", true, true);
        libTile(row, fill, String.valueOf(stages), "",
                Say.countLabel(stages, "Stage", "Stages"), true, true);
        libTile(row, fill, String.valueOf(Math.max(0, r.runs)), "",
                Say.countLabel(r.runs, "Run", "Runs"),
                r.runs > 0, false);
    }

    /** The unit beside a figure, as a share of the figure's size: 11 sp beside 17 sp. A
     *  RELATIVE size, so when a tile shrinks its figure to fit, the unit shrinks with it. */
    private static final float TILE_UNIT_SCALE = 11f / 17f;

    /**
     * One tile: `fill` at the control radius, the figure at 17 sp semibold in the sans
     * face with tabular digits (it only reports), and the label under it at 10.5 sp capitals
     * in DIM. `counted` false is a tile saying there is nothing to count yet - its words
     * are 13 sp DIM, not a figure.
     *
     * THE FIGURE STAYS ON ONE LINE, UNIT AND ALL. A quarter of the screen is narrow, and a
     * "-42.7 cmHg" or a large font scale can outgrow it; so the figure shrinks to fit (down
     * to 9 sp, API 26+) rather than wrapping its unit away. Below API 26 it is one line and
     * clipped, which is still never a wrapped unit.
     */
    private void libTile(LinearLayout row, int fill, String figure, String unit, String label,
                         boolean counted, boolean gapAfter) {
        LinearLayout t = Ui.col(a);
        t.setBackground(Ui.roundRect(a, fill, Look.R_CTRL));
        t.setPadding(Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 9));
        t.setGravity(Gravity.CENTER);
        t.setMinimumHeight(Ui.dp(a, 48));

        TextView v = new TextView(a);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(figure);
        if (unit != null && unit.length() > 0) {
            int at = sb.length();
            sb.append(' ').append(unit);
            sb.setSpan(new android.text.style.RelativeSizeSpan(TILE_UNIT_SCALE), at,
                       sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.DIM), at, sb.length(),
                       android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.TypefaceSpan("sans-serif"), at,
                       sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        v.setText(sb);
        v.setTextColor(counted ? Ui.TEXT : Ui.DIM);
        v.setTextSize(counted ? 17f : 13f);
        v.setTypeface(counted ? SessionActivity.SEMIBOLD
                              : android.graphics.Typeface.create("sans-serif-medium",
                                    android.graphics.Typeface.NORMAL));
        v.setFontFeatureSettings("tnum");
        v.setGravity(Gravity.CENTER);
        v.setMaxLines(1);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            v.setAutoSizeTextTypeUniformWithConfiguration(9, counted ? 17 : 13, 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
        else
            v.setSingleLine(true);
        t.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView l = Ui.microLabel(a, null, label, Ui.DIM);
        l.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                      android.graphics.Typeface.NORMAL));
        l.setGravity(Gravity.CENTER);
        l.setMaxLines(1);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp.topMargin = Ui.dp(a, 4);
        t.addView(l, lLp);

        Ui.group(t, label + ": " + A11y.collapse(figure
                + (unit != null && unit.length() > 0 ? " " + unit : "")));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (gapAfter) lp.rightMargin = Ui.dp(a, 6);
        row.addView(t, lp);
    }

    /** "Add from code" (S19) — the Library-level counterpart to "+ New routine": pastes in
     *  a routine someone else shared, rather than building one from scratch. Opens the
     *  same dialog+EditText shape SearchNotesTap already uses for free-text entry, sized
     *  for a multi-line paste rather than the one-line name fields Rename uses. */
    private final class AddFromCodeTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            final EditText input = new EditText(a);
            input.setHint("paste the routine code here");
            input.setTextColor(Ui.TEXT);
            input.setSingleLine(false);
            input.setMinLines(3);
            input.setMaxLines(6);
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Add from code")
                .setMessage("Paste a routine code someone shared with you. It brings its own "
                    + "sets along — nothing already in your library is changed.")
                .setView(input)
                .setPositiveButton("Add", new AddFromCodeConfirm(input))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class AddFromCodeConfirm implements DialogInterface.OnClickListener {
        private final EditText input;
        AddFromCodeConfirm(EditText e) { input = e; }
        /**
         * The boundary this app has to validate for real (per the project's "only validate
         * at system boundaries" rule): `input` is text a person typed or pasted from who
         * knows where, and {@link Model.Routine#fromShareCode} — which {@link
         * Model#importShareCode} calls first, before touching this library at all — is
         * where that validation actually happens. A malformed or corrupt code throws
         * {@link org.json.JSONException} and is reported here as ONE clear line, never a
         * crash; nothing about this library is touched when it does (importShareCode's own
         * guarantee).
         */
        @Override public void onClick(DialogInterface d, int w) {
            String code = input.getText().toString();
            Model.Routine imported;
            try {
                imported = a.model.importShareCode(code);
            } catch (org.json.JSONException e) {
                Ui.snack(a, a.rootFrame, "That doesn't look like a routine code");
                a.log("!! add from code failed: " + e);
                return;
            }
            // Committed immediately, like Duplicate — not a draft like "+ New routine"'s
            // placeholder: a pasted code is already a deliberate, fully-formed routine, not
            // an empty shell the user might abandon untouched.
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, "Added “" + imported.name + "”");
            showRoutEdit(imported.id);
        }
    }

    private final class StarRoutineTap implements View.OnClickListener {
        private final String id;
        StarRoutineTap(String id) { this.id = id; }
        @Override public void onClick(View v) {
            Model.Routine r = a.model.routine(id);
            if (r == null) { showRoutines(); return; }
            r.star = !r.star;
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame,
                r.star ? r.name + " shown on Today" : r.name + " removed from Today");
            showRoutines();
        }
    }

    private final class SelectRoutineTap implements View.OnClickListener {
        private final String id;
        SelectRoutineTap(String id) { this.id = id; }
        @Override public void onClick(View v) {
            a.model.selected = id;
            Store.save(a, a.model);
            showRoutines();
        }
    }

    private final class OpenRoutEditTap implements View.OnClickListener {
        private final String id;
        OpenRoutEditTap(String id) { this.id = id; }
        @Override public void onClick(View v) { showRoutEdit(id); }
    }

    /**
     * Routine editor, per #v-rout-edit / renderRoutEdit(): name, metric tiles
     * (duration, peak, preset count), the 9-slot warning when the routine writes more
     * than the pump's table holds, and the stage rail with add/edit. Renaming, colour
     * and set membership all live one level down in the stage editor, matching the
     * prototype's split between rout-edit (structure) and stage-edit (stage detail).
     */
    void showRoutEdit(String id) {
        Model.Routine r = a.model.routine(id);
        if (r == null) { showRoutines(); return; }  // a stale id from the caller, not a
        a.editRoutId = id;                              // background render ejecting the user
        if (a.editStageIdx >= r.stages.size()) a.editStageIdx = Math.max(0, r.stages.size() - 1);

        snapshotRoutine(r);
        a.body.removeAllViews();
        // Back to the Library's Routines section — through the Save/Discard question when
        // anything has changed since the editor opened (EditorLeave), so the chevron and the
        // hardware Back key ask the same thing.
        a.header(a.body, r.name, a.new EditorLeave(a.new Tap(SessionActivity.Tap.ROUTINES)));

        // Duplicate beside Rename beside Share (S19) — all three non-destructive, so they
        // share Ui.row's one neutral SURFHI treatment. Delete is the destructive one and
        // stays at the bottom of the screen in CRIT, away from these three.
        // D2 - what this routine is, against what the plan asked for. Said where the
        // routine is READ rather than only where it was made: months later, "12 sets" means
        // nothing without "not the 14 the plan wanted", and the app is the only thing that
        // still knows.
        if (r.mintedAs != null && r.mintedAs.length() > 0)
            Ui.note(a, a.body, r.mintedAs);
        Ui.row(a, a.body, new String[]{ "Duplicate", "Rename", "Share" },
            new View.OnClickListener[]{ a.new DupRoutineTap(), a.new RenameRoutineTap(),
                                         new ShareRoutineTap() });

        // TASK 6 (T10+) — "History ›", near Duplicate/Rename/Share rather than folded
        // into that row: it opens a whole screen, not a dialog, so it gets its own flat
        // row the same way "+ Add sets from library" sits below the stage list it acts
        // on. Neutral SURFHI like its neighbours above — a version list is exactly as
        // non-destructive as Duplicate/Rename/Share, never CMD (this reads no live
        // pressure) and never GOOD (nothing here is a telemetry-confirmed safe state).
        a.markSwatchRow(a.body, r.mark, true);

        Button hist = Ui.flat(a, a.body, "Version history ›");             // LB-17
        hist.setContentDescription("Version history — " + r.history.size()
            + " past version" + (r.history.size() == 1 ? "" : "s") + " kept");
        hist.setOnClickListener(new OpenRoutineHistoryTap());

        List<Model.Preset> plan = a.model.plan(r);
        int presetCount = plan.size();
        // LB-5: the routine's facts in plain words - steps, not presets; no slot table.
        Ui.note(a, a.body, Model.Fmt.t(a.model.routineSec(r))
            + "  ·  peak " + Model.Fmt.p(a.model.workPeakKpa(r)) + a.model.checkPullSuffix(r)
            + "  ·  " + presetCount + (presetCount == 1 ? " step" : " steps"));
        if (presetCount > Proto.SLOTS)
            Ui.note(a, a.body, "More steps than the pump holds at once — sent in parts "
                + "automatically.");
        // The same predicted integral the set editor prints, summed over every set of every
        // stage — so the cost of adding a stage is visible before it is run. "Estimated"
        // because it is a claim about the commanded shape, never about a run.
        Ui.note(a, a.body, "Estimated dose ≈ " + Model.Fmt.dose(a.model.estDoseKpaS(r)));
        // LB-5: the seal-check line ("seal check: off — Settings") is gone - a developer's
        // pointer to a global setting, said on every routine.

        Ui.noteInfo(a, a.body, "Stages are grouping only: a named, coloured group of sets.",
            "Stages", "Stages — grouping only. A stage is a named, coloured group of "
            + "sets; it changes nothing the pump does, it organises the routine."
            + "\n\n▲▼ moves a stage — the routine runs them top to bottom.");

        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            long stDur = 0;
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = a.model.set(st.setIds.get(j));
                if (s != null) stDur += s.dur;
            }
            if (st.rest) stDur = st.restSec;
            boolean sel = i == a.editStageIdx;

            // ▲▼ BESIDE THE STAGE, matching the set rows inside the stage editor exactly:
            // same glyphs, same 48dp mini buttons, same "disabled at the edge" rule, same
            // naming ("move X up (2 of 4)") — reordering a routine and reordering a stage
            // are the same gesture at two levels and must not look like two features.
            LinearLayout stRow = new LinearLayout(a);
            stRow.setOrientation(LinearLayout.HORIZONTAL);
            stRow.setGravity(Gravity.CENTER_VERTICAL);

            String stWhere = " (" + (i + 1) + " of " + r.stages.size() + ")";
            Button stUp = Ui.mini(a, "▲");
            stUp.setEnabled(i > 0);
            stUp.setContentDescription("move stage " + st.name + " up" + stWhere);
            stUp.setOnClickListener(new MoveStageTap(i, -1));
            Button stDown = Ui.mini(a, "▼");
            stDown.setEnabled(i < r.stages.size() - 1);
            stDown.setContentDescription("move stage " + st.name + " down" + stWhere);
            stDown.setOnClickListener(new MoveStageTap(i, +1));

            // A REST STAGE HAS NO SETS TO COUNT. Printing "0 sets" against it would read as
            // an empty stage somebody forgot to fill, which is the opposite of what it is —
            // so it says what it does, in its own words, and Stage#restLine owns them.
            int stWork = a.model.workSetCount(st);   // wave 3a (D6): work sets, not filler
            String stSub = st.rest ? st.restLine()
                : stWork + " set" + (stWork == 1 ? "" : "s")
                  + "  ·  " + Model.Fmt.t(stDur);
            // Wave 4 item 1: EVERY row says it can open — ▸ closed, ▾ open, the same
            // convention the History rows already use, with the open row lifted to
            // SURFHI so the state is visible beyond one glyph.
            // LB-7: the app's chevron icon - down when open, right when closed - where the
            // ▾ / ▸ text glyphs were.
            Button b = Ui.flat(a, stRow, st.name + "\n" + stSub);
            b.setBackground(Ui.roundRect(a, sel ? Ui.SURFHI : Ui.SURF, Look.R_CTRL));
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                Ui.icon(a, sel ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right,
                        Ui.DIM), null);
            // The rest row is drawn in the DIM ink its stage colour already is, so a rest
            // reads as a gap in the list rather than as one more thing being done. Nothing
            // else about the row changes: it reorders, opens and deletes like any stage.
            if (st.rest) b.setTextColor(Ui.DIM);
            // "›" marks the open stage. Said here rather than sniffed by Ui.flat: the
            // marker's absence is what means "not open", and only this loop knows that.
            b.setContentDescription(st.name + ", " + stSub
                + (sel ? ", expanded" : ", collapsed"));
            b.setSelected(sel);
            // F2 - opens the stage UNDER this row instead of replacing the screen.
            b.setOnClickListener(new ToggleStageTap(i));

            // ✕ DELETE THE STAGE, beside the reorder arrows — the control this editor has
            // been missing. A stage could be ADDED and never removed: the only route out
            // was the Used-in screen's "remove set and stage" sheet, which needs the stage
            // to hold exactly one set and is reached from the Sets library, not from here.
            // So a stage added by mistake, or a rest that is no longer wanted, was
            // permanent. It sits at the far end of the row, away from ▲▼, behind a confirm,
            // and the sets it referenced stay in the library exactly as ✕ inside the stage
            // editor already promises.
            // LB-6: NO ✕ ON THE ROW. Three 52 dp glyph buttons took most of the width from
            // the stage's name; delete moved into the opened stage (inlineStage), with Undo.
            // Ui.flat added the button to stRow already; re-weight it so the two arrows keep
            // their fixed width and the label takes the rest.
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bLp.rightMargin = Ui.dp(a, 6);
            b.setLayoutParams(bLp);
            stRow.addView(stUp); stRow.addView(stDown);
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stLp.bottomMargin = Ui.dp(a, Look.S2);   // the margin Ui.flat's own lp carried
            a.body.addView(stRow, stLp);

            // F2 - THE OPEN STAGE EXPANDS HERE, rather than replacing the screen.
            //
            // Changing one value used to cost five levels - Library, routine, stage, set,
            // field - and the three rows on the way were the smallest buttons in the app.
            // Opening the stage IN PLACE removes the middle one for the common path: the
            // stage list stays on screen, this stage's sets are listed under it with the
            // values they command, and a tap on a set opens that set's editor directly.
            //
            // NOTHING IS LOST. The stage's own editor still owns everything structural -
            // the name, the colour, reordering sets, removing them - and is one row away,
            // named for what it holds. This shows the values; that changes the shape.
            if (sel) inlineStage(r, st, i);
        }
        if (r.stages.isEmpty())
            Ui.note(a, a.body, "No stages yet — add one, then put sets in it.");
        else if (presetCount == 0)
            Ui.noteInfo(a, a.body,
                "Nothing to run yet: no stage holds a usable set.",
                "Nothing to run yet",
                "Nothing to run yet: no stage holds a usable set. Tap a stage "
                + "above and add sets from the library"
                + (a.model.sets.isEmpty() ? " — the library is empty: open a stage and tap "
                                            + "“+ New set”."
                                        : "."));                              // LB-15

        // "+ Add rest" IS A SIBLING OF "+ Add stage", not a third thing hidden a level
        // down. A rest is a stage — one that commands nothing — so it is created where
        // stages are created, and from there it reorders, opens and deletes exactly like
        // the others. This is where the old "+ Rest" in the SET picker went: a rest was
        // never a program the pump could run, and keeping it in the library put it beside
        // the things that are.
        Ui.row(a, a.body, new String[]{ "+ Add stage", "+ Add rest" },
            new View.OnClickListener[]{ new AddStageTap(), new AddRestStageTap() });
        Ui.noteInfo(a, a.body, "A rest is a stage that commands nothing.", "Rests",
            "A rest is a stage that commands nothing: the cuff is vented and "
            + "the run waits. It has one setting — how long.");

        showAssessEditor(r);

        editorSaveButton(a.body);

        // OPTION C - the delete says so before the tap, and only for the routine that is
        // actually playing; every other routine deletes normally mid-run.
        boolean routLocked = a.running && a.runRoutine != null && a.runRoutine.id.equals(a.editRoutId);
        // SYS-12: the quiet danger button, "…" because it asks first.
        Button del = Ui.danger(a, a.body, routLocked ? "Delete routine" + a.AFTER_RUN
                                                      : "Delete routine", !routLocked);
        if (routLocked) del.setTextColor(Ui.FAINT);
        del.setOnClickListener(a.new DelRoutineTap());
        // "Done" (pure navigation to Routines) is now the header arrow; Delete stays as the
        // one destructive action at the bottom.
    }

    /** TASK 6 (T10+) — "History ›"'s own listener. A fresh view every time: histOpenIdx
     *  resets so a row left expanded on an earlier visit — possibly to a different
     *  routine — never appears pre-expanded here. */
    private final class OpenRoutineHistoryTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.histOpenIdx = -1;
            showRoutineHistory();
        }
    }

    /**
     * Version history (Task 6 / T10+) — the version list dist/round7-options.html's
     * "T10+" section specs: last {@link Model.Routine#MAX_VERSIONS} kept, newest first
     * with the CURRENT (unarchived) shape always on top, each row's date range / run
     * count / peak-steps-duration summary, and a ▾/▸ toggle that expands ONE row at a
     * time into the full step-by-step detail — the same KV voice {@link
     * #buildUpcomingDetail} already uses (Pull to / Release to / Hold ↑/↓ / Speed /
     * Runs for through {@link Ui#kvRow}, read-only, null listener), reused rather than
     * invented fresh.
     *
     * VERSION NUMBERING reads oldest-kept-first: `history` itself is stored that way
     * (index 0 is the oldest surviving entry), so "v1" is whichever entry has survived
     * the {@link Model.Routine#MAX_VERSIONS}-deep cap the longest — not necessarily this
     * routine's true first-ever edit, once more than that many edits have happened. The
     * screen is honest about this rather than claiming a "v1" that may already have
     * rolled off: see the oldest row's own "created" wording below.
     *
     * A ROW'S FROZEN DETAIL CAN BE STALE FOR PART OF ITS OWN DATE RANGE — see {@link
     * Model.Routine.Version#snapshot}'s own doc. Peak/steps/duration and the expanded
     * per-set values all come from whatever a linked Set said at the instant the NEXT
     * structural edit archived this row, not necessarily what it said for the whole
     * `since`..`until` span: a linked Set's own fields can be edited independently (no
     * version cut, by design) at any point during that span. The date range, run count
     * and version count are always exact regardless.
     *
     * READ-ONLY IN THE DIRTY-TRACKING SENSE: this never calls snapshotRoutine or
     * snapshotSet, so leaving it and returning to the routine editor carries forward
     * whatever unsaved-edit state that editor already had — History is a sub-view of
     * the editor, not a second editable object with its own Save/Discard question.
     */
    private void showRoutineHistory() {
        Model.Routine r = a.model.routine(a.editRoutId);
        if (r == null) { showRoutines(); return; }
        a.body.removeAllViews();
        a.header(a.body, "History  ·  " + r.name, a.new DoneStageTap());

        int kept = r.history.size();
        /* ONE ANSWER TO "WHAT CUTS A VERSION", AND THE ACCURATE ONE.
         *
         * With no history this screen printed the rule twice - once here and once at the
         * foot - and the two disagreed. This one was also wrong: a value change to a set
         * does NOT cut a version (a linked set is edited independently, by design); what
         * cuts one is adding, removing or reordering a stage or a set, or changing a rest
         * stage's length, which is what Model#archiveRoutineVersion's own doc says. */
        // POLISH #9: "No past versions yet." sat directly above a "v1 · current" card —
        // one line saying nothing exists, one card showing something. Same fact, said
        // so the two cannot read as a contradiction.
        if (kept == 0)
            Ui.noteInfo(a, a.body, "v1 is the only version so far.",
                "Version history",
                "Adding, removing or reordering a stage or a set — or changing a rest "
                + "stage's length — cuts a new version, up to "
                + Model.Routine.MAX_VERSIONS + " kept, with the oldest dropping off the "
                + "next one. Renaming, or editing a linked set's own values, does not cut "
                + "one.");
        else
            Ui.note(a, a.body, kept + " of " + Model.Routine.MAX_VERSIONS + " past version"
                + (kept == 1 ? "" : "s") + " kept — the oldest drops off the next shape "
                + "change.");

        // THE CURRENT SHAPE, always on top — the mock's "v4 · current since 12 Aug".
        // Numbered one past the last kept historical row, matching how the rows below
        // are numbered (the oldest kept entry is v1).
        //
        // PEAK HERE IS THE PLAN'S OWN PEAK ONLY — unlike the editor's own top note a
        // few lines above (model.peak(r), which folds in Tau.commandedKpa: the tissue
        // adaptation assessment's pull, a per-routine setting that is NOT part of the
        // versioned stage/set shape and is never captured in a Version's snapshot —
        // there is no historical assessment pressure to show for a past row, so the
        // current row uses the same plan-only basis as every row below it, for one
        // consistent, comparable "peak" meaning down this whole screen rather than two
        // different "peak"s on one screen with no way to tell them apart.
        int curVer = kept + 1;
        List<Model.Preset> curPlan = a.model.plan(r);
        int curPeak = 0;
        long curDurMs = 0;
        for (int i = 0; i < curPlan.size(); i++) {
            if (curPlan.get(i).up > curPeak) curPeak = curPlan.get(i).up;
            curDurMs += curPlan.get(i).durMs;
        }
        LinearLayout curCard = Ui.col(a);
        curCard.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CARD));
        Ui.lift(a, curCard, Ui.ELEV_CARD);
        curCard.setPadding(Ui.dp(a, 11), Ui.dp(a, 9), Ui.dp(a, 11), Ui.dp(a, 10));
        Ui.kvRow(a, curCard, "Version " + curVer + " (current)",
            r.versionSince == 0 ? "since creation" : "since " + a.dayLabel(r.versionSince),
            Ui.TEXT, null);
        Ui.note(a, curCard, "peak " + Model.Fmt.p(curPeak) + "  ·  " + curPlan.size()
            + " steps  ·  " + Model.Fmt.t(curDurMs / 1000) + "  ·  " + ranTimes(r.versionRuns));
        LinearLayout.LayoutParams curLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        curLp.bottomMargin = Ui.dp(a, Look.S2);
        a.body.addView(curCard, curLp);

        // HISTORICAL ROWS, NEWEST FIRST — the mock's v3, v2, v1 reading down the screen.
        // `history` is stored oldest-first, so this walk counts DOWN from the newest
        // kept entry to the oldest.
        for (int i = kept - 1; i >= 0; i--) buildHistoryRow(r, i);

    }

    /**
     * One PAST version's row: number, date range, run count, the peak/steps/duration
     * summary, a ▾/▸ toggle into the full step-by-step detail, and "Restore as copy".
     * `idx` is the raw, oldest-first index into `r.history`. The summary and the
     * expanded detail can be stale for part of this row's own date range — see
     * showRoutineHistory's own doc, and Model.Routine.Version#snapshot's.
     */
    private void buildHistoryRow(Model.Routine r, int idx) {
        Model.Routine.Version v = r.history.get(idx);
        int verNum = idx + 1;
        boolean open = a.histOpenIdx == idx;
        boolean oldest = idx == 0;

        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, open ? Ui.SURFHI : Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 11), Ui.dp(a, 9), Ui.dp(a, 11), Ui.dp(a, 10));

        // THE OLDEST KEPT ROW HAS NO EARLIER BOUNDARY TO SHOW — see this method's own
        // (and showRoutineHistory's) doc on why "v1" is not necessarily this routine's
        // true first edit once more than MAX_VERSIONS have happened. "created" states
        // exactly what is known: this shape existed from this date, full stop.
        String dateRange = oldest ? "created " + a.dayLabel(v.since)
            : a.dayLabel(v.since) + " – " + a.dayLabel(v.until);
        // LB-17 / LB-7: "Version 2 · date", and the chevron icon for open / closed.
        Button head = Ui.flat(a, card, "Version " + verNum + " · " + dateRange);
        head.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
            Ui.icon(a, open ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right,
                    Ui.DIM), null);
        head.setContentDescription(A11y.state("Version " + verNum + ", " + dateRange, open));
        head.setOnClickListener(new ToggleHistoryRowTap(idx));

        Model.AsRunSnapshot snap = v.snapshot;
        List<Model.Preset> vPlan = snap != null ? Model.planFromSnapshot(snap) : new ArrayList<Model.Preset>();
        int vPeak = 0;
        long vDurMs = 0;
        for (int i = 0; i < vPlan.size(); i++) {
            if (vPlan.get(i).up > vPeak) vPeak = vPlan.get(i).up;
            vDurMs += vPlan.get(i).durMs;
        }
        String summary = snap == null
            ? "no detail recorded for this version"
            : "peak " + Model.Fmt.p(vPeak) + "  ·  " + vPlan.size() + " steps  ·  "
              + Model.Fmt.t(vDurMs / 1000) + "  ·  " + ranTimes(v.runs);
        Ui.note(a, card, summary);

        if (open && snap != null) buildHistoryStepDetail(card, snap);
        else if (open)
            Ui.note(a, card, "This version's stage/set values were not recorded — only "
                + "the date range and run count survive.");

        if (snap != null) {
            Button restore = Ui.flat(a, card, "Restore as copy");
            restore.setContentDescription("Restore version " + verNum
                + " as a new routine — " + r.name + " itself is not changed");
            restore.setOnClickListener(new RestoreVersionTap(idx));
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, Look.S2);
        a.body.addView(card, lp);
    }

    /**
     * The expanded detail a history row's ▾ reveals — every stage of that version, in
     * the exact KV voice {@link #buildUpcomingDetail} already established for a
     * read-only readout (label/value through {@link Ui#kvRow}, null listener, {@link
     * Ui#TEXT}): reused here rather than a new row style, per this task's own brief.
     * One caption per stage names it and its kind; a rest stage gets the same two rows
     * {@link #buildUpcomingDetail} draws for a rest preset, a commanding set gets Pull
     * to / Release to / Hold ↑/↓ / Speed / Runs for. A ramp's start→end prints as ONE
     * "X → Y" line rather than through {@link #rangeTile} — that helper's two-line,
     * unit-once fold is tuned for the Sets library's narrow tile, and forcing an
     * embedded newline into kvRow's compact single-line value slot would wrap the row
     * rather than read as the same voice.
     */
    private void buildHistoryStepDetail(LinearLayout card, Model.AsRunSnapshot snap) {
        for (int i = 0; i < snap.stages.size(); i++) {
            Model.AsRunSnapshot.StageSnap st = snap.stages.get(i);
            Ui.microLabel(a, card, (i + 1) + "  " + st.name
                + (st.rest ? "  ·  rest" : ""), Ui.DIM);
            if (st.rest) {
                Ui.kvRow(a, card, "What happens", "Cuff vented — nothing commanded",
                    Ui.TEXT, null);
                Ui.kvRow(a, card, "Runs for", Model.Fmt.t(st.restSec), Ui.TEXT, null);
                continue;
            }
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.AsRunSnapshot.SetSnap ss = st.setAt(j);
                if (ss == null) continue;      // unrecorded position — nothing to show
                Ui.kvRow(a, card, "Pull to",
                    ss.ramp && ss.up != ss.up2 ? Model.Fmt.p(ss.up) + " → " + Model.Fmt.p(ss.up2)
                                                : Model.Fmt.p(ss.up), Ui.TEXT, null);
                Ui.kvRow(a, card, "Release to",
                    ss.ramp && ss.lo != ss.lo2 ? Model.Fmt.p(ss.lo) + " → " + Model.Fmt.p(ss.lo2)
                                                : Model.Fmt.p(ss.lo), Ui.TEXT, null);
                Ui.kvRow(a, card, "Hold the pull",
                    (ss.ramp && ss.uh != ss.uh2 ? ss.uh + " → " + ss.uh2 : String.valueOf(ss.uh))
                    + " s", Ui.TEXT, null);
                Ui.kvRow(a, card, "Hold the release",
                    (ss.ramp && ss.lh != ss.lh2 ? ss.lh + " → " + ss.lh2 : String.valueOf(ss.lh))
                    + " s", Ui.TEXT, null);
                Ui.kvRow(a, card, "Suction power",
                    (ss.ramp ? ss.sp + " → " + ss.sp2 : String.valueOf(ss.sp)) + " %",
                    Ui.TEXT, null);
                Ui.kvRow(a, card, "Runs for", Model.Fmt.t(ss.dur), Ui.TEXT, null);
            }
        }
    }

    /**
     * F2 - ONE STAGE'S SETS, DRAWN UNDER ITS ROW, with the values each one commands.
     *
     * Indented against the stage list so the nesting is visible without a second screen, and
     * every set row is a full-width control that opens that set's editor - the same
     * {@link SessionActivity.OpenSetEdit} listener the stage editor's own rows and the Sets
     * library both use, so a set is edited by one code path however you reached it.
     *
     * A REST STAGE HAS NO SETS. It says how long it waits, in its own words
     * ({@link Model.Stage#restLine}), because printing "0 sets" against it would read as a
     * stage somebody forgot to fill.
     */
    private void inlineStage(Model.Routine r, Model.Stage st, int idx) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CTRL));
        int pad = Ui.dp(a, Look.S2);
        box.setPadding(pad, pad, pad, pad);

        if (st.rest) {
            Ui.note(a, box, st.restLine() + "  —  the cuff is vented and the run waits.");
        } else if (st.setIds.isEmpty()) {
            Ui.note(a, box, "No sets in this stage yet.");
        } else {
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = a.model.set(st.setIds.get(j));
                if (s == null) continue;        // a dangling id is never a phantom row
                // The stage editor's own set line, verbatim - two places describing one set
                // in two ways is how they come to disagree.
                // LB-8: "−8.3 inHg, drop to −1.5 · 75% power · 1:40" - the slash between
                // the pull and the drop was a code.
                String sub = s.rest ? s.line()
                    : Model.Fmt.p(s.up) + ", drop to " + Say.valueWordOf(Model.Fmt.p(s.lo))
                      + "  ·  " + s.sp + "% power  ·  " + Model.Fmt.t(s.dur);
                Button sb = Ui.flat(a, box, s.name + " ›\n" + sub);
                sb.setContentDescription("Edit " + s.name + ", " + sub
                    + ", opens the set editor");
                sb.setOnClickListener(new OpenSetFromStage(s.id));
            }
        }

        // The way to everything this view deliberately does not offer. Named for what it
        // holds rather than "Edit", so it is obvious what is through it and what is not.
        Button more = Ui.flat(a, box, st.rest
            ? "Rest name and length ›"
            : "Edit stage (name, colour, order) ›");
        more.setContentDescription("Stage settings for " + st.name
            + " — rename, recolour, reorder or remove its sets");
        more.setOnClickListener(new EditStageTap(idx));
        // LB-6 / LB-16: deleting a stage is here, in the opened stage, and takes effect at
        // once with Undo on the snack - no confirm saying it cannot be undone.
        Button delStage = Ui.danger(a, box, "Delete stage", false);
        delStage.setContentDescription("Delete stage " + st.name + ". You can undo it.");
        delStage.setOnClickListener(new DelStageTap(idx));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(a, Look.S3);
        lp.bottomMargin = Ui.dp(a, Look.S2);
        a.body.addView(box, lp);
    }

    /** A set inside the open stage, opened straight into the set editor — the middle screen
     *  the tree used to charge for. Same destination the stage editor's own rows reach. */
    private final class ReturnToRoutineTap implements View.OnClickListener {
        private final String routId;
        ReturnToRoutineTap(String id) { routId = id; }
        @Override public void onClick(View v) {
            a.setEditReturnRout = null;
            showRoutEdit(routId);
        }
    }

    private final class OpenSetFromStage implements View.OnClickListener {
        private final String id;
        OpenSetFromStage(String s) { id = s; }
        @Override public void onClick(View v) {
            a.setEditReturnRout = a.editRoutId;   // wave 4 item 1: Back returns HERE
            showSetEdit(id);
        }
    }

    /**
     * F2 - OPENS THE STAGE IN PLACE, or closes it if it was already open.
     *
     * One at a time, exactly as {@code ToggleHistoryRowTap} does and as `editStageIdx` has
     * always meant. This is what the stage row used to do by REPLACING the screen; the
     * stage's own editor is still there, reached from inside the expansion.
     */
    private final class ToggleStageTap implements View.OnClickListener {
        private final int idx;
        ToggleStageTap(int i) { idx = i; }
        @Override public void onClick(View v) {
            a.editStageIdx = (a.editStageIdx == idx) ? -1 : idx;
            showRoutEdit(a.editRoutId);
        }
    }

    /** Expands or collapses one history row — one open at a time, mirroring how the
     *  routine editor keeps at most one stage (`editStageIdx`) open. */
    private final class ToggleHistoryRowTap implements View.OnClickListener {
        private final int idx;
        ToggleHistoryRowTap(int i) { idx = i; }
        @Override public void onClick(View v) {
            a.histOpenIdx = (a.histOpenIdx == idx) ? -1 : idx;
            showRoutineHistory();
        }
    }

    /**
     * "Restore as copy" (Task 6 / T10+) — hands the reconstruction straight to {@link
     * Model#restoreRoutineVersion}, which builds the new routine through {@link
     * Model.Routine#copy} and never touches the routine open in this editor. Navigates
     * to the NEW routine's own editor, the same discipline {@link DupRoutineTap} already
     * follows: what the tap creates is what the screen shows next.
     */
    private final class RestoreVersionTap implements View.OnClickListener {
        private final int idx;
        RestoreVersionTap(int i) { idx = i; }
        @Override public void onClick(View v) {
            Model.Routine restored = a.model.restoreRoutineVersion(a.editRoutId, idx);
            if (restored == null) {
                a.toast("Could not restore that version");
                return;
            }
            Store.save(a, a.model);
            a.histOpenIdx = -1;
            Ui.snack(a, a.rootFrame, "Restored as " + restored.name);
            showRoutEdit(restored.id);
        }
    }

    /**
     * The routine editor's TISSUE RESPONSE TEST block: a card with the test's name and an
     * on/off switch, what it is, then when it runs (Before / After / Both) and the pressure /
     * motor speed / duration steppers. ONE set of numbers drives both ends - two fill times
     * measured at two different settings cannot be compared - so there is deliberately no
     * separate before/after configuration.
     *
     * M4 (the owner's item 13): it had no title, its on/off and "before + after" were buttons
     * tapped to cycle, and its ranges read "5-100 %". It is now a proper block - a switch, the
     * app's segmented control, en-dash ranges - and a NEW routine starts with it OFF, while an
     * existing one keeps its setting (Model.Assess#on). Off, the settings stay on screen,
     * greyed (Ui#dependentBlock), so
     * what turning it on would run is visible before it is chosen.
     *
     * The two warnings are the point of the feature, not decoration:
     *   - changing ANY of the three numbers ends comparison with earlier sessions, and that
     *     is said at the point of change (the toast) and again for as long as it holds (the
     *     note), measured against the last session actually tested for this routine -
     *     Summary.lastAssessed + Tau.sameStimulus, the same pair History marks its rows with,
     *     so the two can never give opposite answers;
     *   - one end only gives a fill time with nothing to compare it with, and says so rather
     *     than implying a change it cannot produce.
     */
    void showAssessEditor(Model.Routine r) {
        Model.Assess as = r.assess;
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, Look.S3), Ui.dp(a, Look.S5), Ui.dp(a, Look.S5));

        // THE TITLE ROW IS THE SWITCH: one 48 dp target, said as "Tissue response test: on".
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(Ui.dp(a, 48));
        TextView title = new TextView(a);
        title.setText(TauSay.NAME);
        title.setTextColor(Ui.TEXT);
        title.setTextSize(Look.SP_HEADING);
        Ui.semibold(title);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View sw = Ui.switchView(a, as.on);
        head.addView(sw, new LinearLayout.LayoutParams(Ui.dp(a, Ui.SWITCH_W), Ui.dp(a, Ui.SWITCH_H)));
        Ui.group(head, TauSay.NAME + ": " + (as.on ? "on" : "off"));
        head.setSelected(as.on);
        if (android.os.Build.VERSION.SDK_INT >= 28) head.setAccessibilityHeading(true);
        head.setOnClickListener(new AssessToggleTap());
        head.setOnTouchListener(new Ui.Press());
        card.addView(head);

        Ui.noteInfo(a, card, as.on
                ? "The same short pull before and after the routine, to see how fast the "
                  + "cylinder fills."
                : "Off for this routine until you turn it on.",
            TauSay.NAME, TauSay.about(as.kpa, as.sp, as.dur) + "\n\nIt is set per routine, "
            + "and it is not the standardisation hold: that one holds a pressure so a "
            + "measurement is taken the same way each time, and times nothing.");

        // WHAT IT WOULD RUN - greyed, not hidden, while the switch is off.
        LinearLayout dep = Ui.col(a);
        Ui.fieldLabel(a, dep, "Run it", null).setPadding(0, Ui.dp(a, Look.S2), 0, 0);
        String[] whenLabels = { "Before", "After", "Both" };
        String[] whenVals = { Model.Assess.WHEN_BEFORE, Model.Assess.WHEN_AFTER,
                              Model.Assess.WHEN_BOTH };
        int sel = 2;
        for (int i = 0; i < whenVals.length; i++) if (whenVals[i].equals(as.when)) sel = i;
        Ui.segmented(a, dep, whenLabels,
            new String[]{ "before the routine only", "after the routine only",
                          "before and after the routine" },
            sel, new View.OnClickListener[]{ new AssessWhenSetTap(whenVals[0]),
                new AssessWhenSetTap(whenVals[1]), new AssessWhenSetTap(whenVals[2]) });

        int cap = Math.min(57, a.model.ceilKpa);
        Ui.stepperRow(a, dep, "Pressure  (" + a.rangeLabel(5, cap) + ")",
            Model.Fmt.p(as.kpa), new BumpAssess(a.ASSESS_KPA, -1), new BumpAssess(a.ASSESS_KPA, +1),
            "test pressure");
        Ui.stepperRow(a, dep, "Suction power  (5–100 %)", as.sp + " %",
            new BumpAssess(a.ASSESS_SP, -1), new BumpAssess(a.ASSESS_SP, +1),
            "test suction power");
        Ui.stepperRow(a, dep, "Runs for  (15–180 s, in 15 s steps)", as.dur + " s",
            new BumpAssess(a.ASSESS_DUR, -1), new BumpAssess(a.ASSESS_DUR, +1),
            "test duration");

        // What it costs, from the settings: Tau#assessDur reads 0 while it is off, and the
        // greyed block says what it WOULD add.
        int adds = as.dur * (Model.Assess.WHEN_BOTH.equals(as.when) ? 2 : 1);
        Ui.note(a, dep, "Adds " + Model.Fmt.t(adds) + " to the routine"
            + (Model.Assess.WHEN_BOTH.equals(as.when) ? ": two pulls of " + as.dur + " s."
                                                       : ": one pull of " + as.dur + " s."));

        if (!Model.Assess.WHEN_BOTH.equals(as.when))
            Ui.note(a, dep, (Model.Assess.WHEN_AFTER.equals(as.when) ? "After" : "Before")
                + " only: one fill time a session, with nothing to compare it with, so no "
                + "change is worked out.");

        Model.Sess prev = Summary.lastAssessed(a.model.sessLog.all, r.id);
        if (prev != null && !Tau.sameStimulus(prev.assessKpa, prev.assessSp, prev.assessDurSec,
                                               as.kpa, as.sp, as.dur))
            Ui.noteInfo(a, dep,
                "These settings differ from the last test, so fill times from here on are "
                + "compared only with each other.",
                "Comparing with earlier sessions",
                "The fill time changes with pump speed, pressure and duration: these settings "
                + "differ from the last session's test (" + Tau.stimulus(prev.assessKpa,
                    prev.assessSp, prev.assessDurSec) + "), so fill times from here on are "
                + "compared only with each other, not with earlier sessions. History and "
                + "Progress mark the change rather than drawing across it.");
        card.addView(dep, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.dependentBlock(a, dep, as.on);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, Look.S3);
        lp.bottomMargin = Ui.dp(a, Look.S4);
        a.body.addView(card, lp);
    }

    private final class AssessToggleTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            Model.Routine r = a.model.routine(a.editRoutId);
            if (r == null) return;
            r.assess.on = !r.assess.on;
            a.markEditorDirty();
            Store.save(a, a.model);
            a.toast(TauSay.NAME + (r.assess.on ? " on for this routine" : " off for this routine"));
            showRoutEdit(a.editRoutId);
        }
    }

    /** One segment of "Run it". Inert while the test is off, as the greyed block says. */
    private final class AssessWhenSetTap implements View.OnClickListener {
        private final String when;
        AssessWhenSetTap(String w) { when = w; }
        @Override public void onClick(View v) {
            Model.Routine r = a.model.routine(a.editRoutId);
            if (r == null || !r.assess.on || when.equals(r.assess.when)) return;
            r.assess.when = when;
            a.markEditorDirty();
            Store.save(a, a.model);
            a.toast(Model.Assess.WHEN_BOTH.equals(when)
                ? "Before and after: the two fill times are compared"
                : (Model.Assess.WHEN_AFTER.equals(when) ? "After" : "Before")
                  + " only: one fill time, nothing to compare it with");
            showRoutEdit(a.editRoutId);
        }
    }

    /**
     * The assessment steppers. Ranges are the prototype's asF() exactly: pressure 5 kPa
     * to min(57, ceiling), speed 5-100 in steps of 5, duration 15-180 in steps of 15.
     *
     * The duration floor is 15 s with a 15 s step — DELIBERATELY different from a set's
     * 30 s floor and 30 s step. They are different things and are not unified.
     *
     * Every change is checked against the last assessed session for this routine and
     * toasted the moment it breaks comparability — "flag it at the point of change",
     * not only on a trend somebody may never open.
     */
    private final class BumpAssess implements View.OnClickListener {
        private final int field, delta;
        BumpAssess(int f, int d) { field = f; delta = d; }
        @Override public void onClick(View v) {
            Model.Routine r = a.model.routine(a.editRoutId);
            if (r == null) return;
            Model.Assess as = r.assess;
            Model.Sess prev = Summary.lastAssessed(a.model.sessLog.all, r.id);
            boolean wasComparable = prev != null && Tau.sameStimulus(
                prev.assessKpa, prev.assessSp, prev.assessDurSec, as.kpa, as.sp, as.dur);

            if (field == a.ASSESS_KPA)      as.kpa = a.stepKpa(as.kpa, delta);
            else if (field == a.ASSESS_SP)  as.sp = as.sp + delta * 5;
            else if (field == a.ASSESS_DUR) as.dur = as.dur + delta * 15;
            // Clamped through the a.model's own clamp, not a second copy of the bounds
            // here — the printed range and the enforced range are the same code.
            as.clamp(a.model.ceilKpa);

            boolean nowComparable = prev != null && Tau.sameStimulus(
                prev.assessKpa, prev.assessSp, prev.assessDurSec, as.kpa, as.sp, as.dur);
            a.markEditorDirty();
            Store.save(a, a.model);
            if (wasComparable && !nowComparable)
                a.toast("New test settings: fill times from here on are compared only with "
                    + "each other");
            showRoutEdit(a.editRoutId);
        }
    }

    /**
     * "Share" (S19) — hands the OPEN routine to the Android share sheet as plain text, via
     * {@link Model.Routine#toShareCode}, which embeds every set the routine's stages
     * reference (not ids: a recipient's library is not this one). This joins {@code
     * shareLog()} and the Progress export as another site in this file raising {@code
     * Intent.createChooser}, and follows the exact same launch discipline WiringCheck's
     * invariant 10 pins at those: {@code FLAG_ACTIVITY_NO_USER_ACTION} on the chooser (the
     * app raised this sheet; it must not read as the user leaving) and {@code
     * ownLaunchPending} set only AFTER {@code startActivity} returns (so a throw from it —
     * no app on the phone can handle plain text, which is realistically never true but
     * costs nothing to guard — cannot leave the flag suppressing onStop()'s give-up check
     * for the rest of this Activity's life).
     */
    private final class ShareRoutineTap implements View.OnClickListener, Runnable {
        /** H1 - the same share, asked again once a hold's vent is confirmed. */
        @Override public void run() { onClick(null); }
        @Override public void onClick(View v) {
            Model.Routine r = a.model.routine(a.editRoutId);
            if (r == null) return;
            // H1 - the share sheet is another app: a hold on the cuff vents first.
            if (a.ventFirst(HoldHandOff.SHARE, null, this, null)) return;
            String code;
            try {
                code = r.toShareCode(a.model);
            } catch (org.json.JSONException e) {
                // Encoding this routine's OWN in-memory data should not be able to fail —
                // there is no boundary here, unlike the "Add from code" paste — so this is
                // purely defensive. A Library-local snack, not a toast: the failure is
                // about THIS screen's action, not about the share sheet itself.
                Ui.snack(a, a.rootFrame, "Could not build a share code");
                a.log("!! share code encode failed for " + r.id + ": " + e);
                return;
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, r.name);
            i.putExtra(Intent.EXTRA_TEXT, code);
            try {
                a.startActivity(Intent.createChooser(i, "Share " + r.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION));
                a.ownLaunchPending = true;
                a.log("share sheet opened for routine " + r.id);
            } catch (Exception e) {
                a.toast("Nothing on this phone can share text");
                a.log("!! routine share failed: " + e);
            }
        }
    }

    private final class AddStageTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            Model.Routine r = a.model.routine(a.editRoutId);
            if (r == null) return;
            Model.Stage st = new Model.Stage();
            st.name = "Stage " + (r.stages.size() + 1);
            st.colour = Model.STAGE_COOL;
            // TASK 6 — snapshot-before-commit: adding a stage changes the routine's
            // SHAPE, so the pre-edit shape is archived into version history before the
            // add lands. See Model#archiveRoutineVersion's own doc for why this is the
            // real commit path (not a rename) and why capture-then-commit-after is the
            // safe order here.
            Model.AsRunSnapshot histPre = Model.AsRunSnapshot.of(r, a.model);
            r.stages.add(st);
            a.model.archiveRoutineVersion(r, histPre);
            a.editStageIdx = r.stages.size() - 1;
            a.markEditorDirty();
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, "Stage added");
            a.showStageEdit();
        }
    }

    /** "+ Add rest" — a stage that commands nothing. Model.Stage#restOf owns the defaults
     *  (the rest colour, the 2:00 duration, the "holds no sets" invariant); this only
     *  appends it and opens its editor, exactly as AddStageTap does one line up. */
    private final class AddRestStageTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            Model.Routine r = a.model.routine(a.editRoutId);
            if (r == null) return;
            // TASK 6 — same snapshot-before-commit hook as AddStageTap, one line up.
            Model.AsRunSnapshot histPre = Model.AsRunSnapshot.of(r, a.model);
            r.stages.add(Model.Stage.restOf("Rest", 120));
            a.model.archiveRoutineVersion(r, histPre);
            a.editStageIdx = r.stages.size() - 1;
            a.markEditorDirty();
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, "Rest added");
            a.showStageEdit();
        }
    }

    /**
     * DELETING A STAGE, from the routine editor's own ✕ — the control this screen was
     * missing entirely. Sets have had a ✕ inside the stage editor since it was written;
     * stages had ▲▼ and nothing else, so a stage added by a mis-tap could be renamed,
     * recoloured, emptied and moved, but never removed.
     *
     * REMOVING THE LAST STAGE IS ALLOWED. A routine with zero stages is a real state the
     * app already draws — the editor's "No stages yet" note, and Today's "nothing to run"
     * — so refusing here would be protecting the user from a screen that already exists.
     *
     * The sets it referenced are UNTOUCHED, which is the same promise the stage editor's ✕
     * makes one level down: a routine holds ids, not copies.
     */
    /* LB-16: THE RUNNING CHECK COMES FIRST, AND THE DELETE CAN BE UNDONE. It asked "This
     * cannot be undone" - in an editor that has Discard - and only said the routine was
     * running after the confirm. Now a running routine refuses at the tap, and otherwise the
     * stage goes at once with "Undo" on the snack (LB-18: a snack, as every other delete). */
    private final class DelStageTap implements View.OnClickListener {
        private final int idx;
        DelStageTap(int i) { idx = i; }
        @Override public void onClick(View v) {
            Model.Routine r = a.model.routine(a.editRoutId);
            if (r == null || idx < 0 || idx >= r.stages.size()) return;
            // Refuse while that routine is the one actually running: the live plan was
            // built from its stages, and pulling one out from under a run would leave the
            // stage rail and the as-run recording describing a routine that no longer has
            // that shape. The same rule DelRoutineConfirm applies, one level down.
            if (a.running && a.runRoutine != null && a.runRoutine.id.equals(r.id)) {
                a.toast("That routine is running — stop the session first");
                return;
            }
            Model.Stage gone = r.stages.get(idx);
            String name = gone.name;
            // TASK 6 — captured BEFORE the removal, committed to history only once
            // Model#removeStage reports it actually happened: that method's own
            // "every refusal mutates nothing" contract means the mutation (if any) is
            // already done by the time it returns, so archiving cannot run first and
            // learn afterwards whether to keep what it captured. See
            // Model#archiveRoutineVersion's own doc for this ordering.
            Model.AsRunSnapshot histPre = Model.AsRunSnapshot.of(r, a.model);
            // Model#removeStage is the removal — bounds, and "the sets stay in the library",
            // are its rules and are pinned by SelfTest there.
            if (!a.model.removeStage(r.id, idx)) return;
            a.model.archiveRoutineVersion(r, histPre);
            // The OPEN stage must not be left pointing past the end, or at a different
            // stage than the one that was open. Anything at or after the removed index
            // shifts down one; showRoutEdit re-clamps, but doing it here means the marker
            // never flickers onto the wrong row.
            if (a.editStageIdx >= idx) a.editStageIdx = Math.max(0, a.editStageIdx - 1);
            a.markEditorDirty();
            Store.save(a, a.model);
            showRoutEdit(a.editRoutId);
            Ui.snack(a, a.rootFrame, name + " deleted", Ui.UNDO,
                new UndoDelStageTap(r.id, idx, gone), Snack.HOLD_MS_ACTIONABLE);
        }
    }

    /** LB-16's Undo: the removed stage goes back where it was, as one more edit of the
     *  routine (cut as a version, like the delete was). Refused while that routine runs. */
    private final class UndoDelStageTap implements View.OnClickListener {
        private final String routineId;
        private final int idx;
        private final Model.Stage stage;
        UndoDelStageTap(String id, int i, Model.Stage s) { routineId = id; idx = i; stage = s; }
        @Override public void onClick(View v) {
            Model.Routine r = a.model.routine(routineId);
            if (r == null) return;
            if (a.running && a.runRoutine != null && a.runRoutine.id.equals(r.id)) {
                a.toast("That routine is running — stop the session first");
                return;
            }
            Model.AsRunSnapshot histPre = Model.AsRunSnapshot.of(r, a.model);
            r.stages.add(Math.max(0, Math.min(idx, r.stages.size())), stage);
            a.model.archiveRoutineVersion(r, histPre);
            a.markEditorDirty();
            Store.save(a, a.model);
            if (routineId.equals(a.editRoutId)) showRoutEdit(routineId);
        }
    }

    /** LB-17: "Ran 4 times", where the history rows said "ran 4×". */
    private static String ranTimes(int n) {
        return n == 1 ? "Ran once" : "Ran " + n + " times";
    }

    private final class EditStageTap implements View.OnClickListener {
        private final int idx;
        EditStageTap(int i) { idx = i; }
        @Override public void onClick(View v) { a.editStageIdx = idx; a.showStageEdit(); }
    }

    /** The routine editor's ▲▼, one level up from {@link MoveSetTap} and the same shape. */
    private final class MoveStageTap implements View.OnClickListener {
        private final int idx, delta;
        MoveStageTap(int i, int d) { idx = i; delta = d; }
        @Override public void onClick(View v) {
            // TASK 6 — same capture-then-commit-after hook as MoveSetTap, one block up.
            Model.Routine r = a.model.routine(a.editRoutId);
            Model.AsRunSnapshot histPre = r != null ? Model.AsRunSnapshot.of(r, a.model) : null;
            if (!a.model.moveStage(a.editRoutId, idx, delta)) return;
            a.model.archiveRoutineVersion(r, histPre);
            // The OPEN stage travels with the row that moved, so "the one I was looking at"
            // is still the one marked ▸ afterwards rather than whichever stage took its
            // index. Only when the open stage is one of the two that swapped.
            if (a.editStageIdx == idx) a.editStageIdx = idx + (delta < 0 ? -1 : 1);
            else if (a.editStageIdx == idx + (delta < 0 ? -1 : 1)) a.editStageIdx = idx;
            a.markEditorDirty();
            Store.save(a, a.model);
            showRoutEdit(a.editRoutId);
        }
    }
}
