package org.openpump;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * "HOW THIS APP WORKS" — the help screen, lifted out of SessionActivity verbatim.
 *
 * It renders into the Activity's own `body`, exactly as it did when it lived there, and
 * holds no state of its own: everything it reads is on `a`. That is the whole pattern for
 * the split (docs/superpowers/plans/2026-09-07-sessionactivity-split.md) — a screen class
 * is a RENDERER over the Activity's state, never an owner of it, because state that moved
 * into a screen would be state that dies when the screen is rebuilt.
 *
 * Nothing here commands the pump, which is why this screen went first.
 */
final class HelpScreen {
    private final SessionActivity a;

    HelpScreen(SessionActivity a) { this.a = a; }

    private final class HelpBackTap implements View.OnClickListener {
        @Override public void onClick(View v) { a.showSettings(); }
    }

    /**
     * A HELP SECTION LABEL — quiet uppercase text, nothing else. Round-2 decision C7:
     * Settings' {@link Ui#categoryHeader} pairs a coloured rule and glyph with real
     * interactive controls underneath it, and that colour is what lets the eye jump to a
     * category worth acting on. Help's sections are prose cards with nothing to tap, so the
     * same rule/glyph would promise an interactivity this screen does not have. This is
     * {@link Ui#microLabel} alone, in {@link Ui#FAINT}, with the same {@link Look#S6} top
     * spacing {@code categoryHeader} used before its own label — and the same
     * accessibility-heading behaviour, so a screen reader still jumps section to section.
     */
    private void helpSectionLabel(ViewGroup parent, String label) {
        TextView t = Ui.microLabel(a, parent, label, Ui.FAINT);
        t.setPadding(0, Ui.dp(a, Look.S6), 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
    }

    /**
     * "HOW THIS APP WORKS" — one scrollable screen of plain prose, one card per screen of
     * the app, in the order a person meets them.
     *
     * WHY IT EXISTS AND WHAT IT IS NOT. Every screen already carries its own \u24d8 button and
     * its own notes, and those stay: they explain a control while you are looking at it.
     * What no screen could give was the SHAPE of the whole thing — which screen is for
     * what, what the run controls actually do to the pump, what the app refuses to do and
     * why. That is a document, not a tooltip, so it is a screen.
     *
     * IT DESCRIBES BEHAVIOUR THAT EXISTS. Every sentence here is about something the build
     * does today, in the wording the screen it describes already uses, so the two cannot
     * drift into saying different things about one feature. Nothing here is a plan.
     *
     * A Settings screen with a Back arrow (Nav#SCR_HELP): the tab bar stays, because
     * reading this commands nothing and leaving it is never a give-up.
     */
    void show() {
        a.body.removeAllViews();
        a.enterDest(Nav.SCR_HELP);
        a.header(a.body, "How this app works", new HelpBackTap());

        Ui.noteInfo(a, a.body, "This is the map: what each screen is for.",
            "Help", "Every screen also has its own \u24d8 button for the detail. This is "
            + "the map: what each screen is for, and what the app will and will not do.");

        helpSectionLabel(a.body, "Today");
        Ui.card(a, a.body, "Today \u2014 the front door",
            "The ring at the top is your STREAK, counted over your training days only (set "
            + "them in Settings \u203a Training schedule). A day you do not train on neither adds "
            + "to it nor breaks it; one missed training day per run is covered by a rest day, "
            + "and a second ends it.\n\n"
            + "Under it sits the routine you have selected, with its stages. \u201cStart\u201d runs it: "
            + "the app connects to the pump if it is not connected, takes a baseline "
            + "measurement if your cadence says one is due, and then plays the routine.\n\n"
            + "\u201cManual run\u201d drives the pump directly from one editable cycle \u2014 pull, hold, "
            + "release, speed \u2014 with no routine and no stages. \u201cLog a reading\u201d records a "
            + "measurement on its own, with no session at all: pick the protocol you measured "
            + "with, type or step the numbers, add photos if you want them.");

        helpSectionLabel(a.body, "The run screen");
        Ui.card(a, a.body, "Watching a run",
            "The chart is live pressure over the last stretch of the run. The shaded band "
            + "behind it is the COMMANDED band \u2014 the pull and release the current preset "
            + "asked for \u2014 so the trace inside it is the pump doing what it was told, and a "
            + "trace outside it is worth noticing.\n\n"
            + "The NOW card names the step that is playing and its time; the line under the "
            + "bar names what comes next, so nothing arrives unannounced.");
        // polish RN-8: the controls on the run screen today - the four it described (HOLD,
        // SKIP, "+30s", NOW / NEXT) were renamed or gone.
        Ui.card(a, a.body, "The run controls",
            "Pause keeps the pressure and stops the clock; Resume carries on.\n\n"
            + "Skip these sets ends the block of sets playing (on a ramp, Skip step ends the "
            + "step; in the warm-up, Skip warm-up ends it). For a few seconds after, the same "
            + "button reads Undo skip and puts them back.\n\n"
            + "+30 s adds time to the hold, step, warm-up or rest playing.\n\n"
            + "Rest vents the cuff for as long as you pick, then carries on where you were.\n\n"
            + "STOP vents at once. \u201cEnd session\u2026\u201d asks first, then vents; after "
            + "either, Resume picks the routine up where it stopped.\n\n"
            + "The status line says what is playing; Coming steps lists the rest, and lets you "
            + "change or skip a later step.\n\n"
            + "The adjust sheet changes the live numbers \u2014 pull, drop, hold, suction power \u2014 "
            + "and for a ramp it reshapes the ramp itself (its start, its end and its steps). "
            + "Everything you change there is bounded by your safety ceiling, and after the "
            + "run the app can offer to save what you actually ran as a set or a routine.");

        helpSectionLabel(a.body, "Library");
        Ui.card(a, a.body, "Routines, stages and sets",
            "A SET is one pump behaviour: a pull, a release, how long each is held, the "
            + "motor speed, and how long the whole thing runs \u2014 or, for a ramp, a start, an "
            + "end and the number of steps between them.\n\n"
            + "A STAGE is a named group of sets. A ROUTINE is an ordered list of stages. "
            + "Both can be reordered, and a stage can be a REST stage \u2014 a named gap that "
            + "commands no pressure at all, so a routine can build in recovery instead of "
            + "leaving it to you to pause.\n\n"
            + "Editing a routine or a set keeps a DRAFT: your edits apply as you make them, a "
            + "lime Save keeps them, and leaving with unsaved changes asks first \u2014 Save or "
            + "Discard. Back is never a silent commit.");

        helpSectionLabel(a.body, "Progress");
        Ui.card(a, a.body, "The trend",
            "The chart draws ONE metric (length or girth) and, within it, every series you "
            + "tick: each measurement PROTOCOL is its own line, solid before a session and "
            + "dashed after one. Readings measured different ways are different measurements "
            + "of the same body \u2014 the gaps between the lines are protocol, not growth \u2014 so "
            + "the app never merges them into one line, and a line breaks wherever two "
            + "readings are not directly comparable.");
        Ui.card(a, a.body, "Goals",
            "You can keep three targets, each stated in the one protocol it is measured in: "
            + "a BPSSL length goal, an MSEG erect girth goal and an MSSG soft girth goal. "
            + "Set them in Settings \u203a Goals \u2014 step them, or tap the value to type it.\n\n"
            + "Each goal is drawn on the trend as a dotted line in its own protocol's "
            + "colour, sloping from your FIRST reading in that protocol toward the target and "
            + "landing on it exactly at the end of the shared horizon. Above the line is "
            + "ahead of pace; below it is behind. A goal only appears when that protocol's "
            + "series is on the chart, because sloping a target across a differently-measured "
            + "trend would read the gap between two protocols as distance still to go.\n\n"
            + "Goals are CAPPED, at one inch of length and half an inch of girth per year of "
            + "the horizon, and never more than a fixed lifetime gain over your first reading "
            + "in that protocol. That is not a forecast about a body; it is a refusal to draw "
            + "a target that would make honest progress look like failure.");
        Ui.card(a, a.body, "Photos, compare and then-vs-now",
            "The gallery holds every photo you have taken, grouped by day. COMPARE puts two "
            + "dates side by side, matched by VIEW \u2014 POV against POV, side against side "
            + "\u2014 never one view against another. THEN VS NOW is the same idea as a standing "
            + "card: by default your first photo against your latest, and you can pin either "
            + "end to a specific reading.\n\n"
            + "The \u1d49\u02b8\u1d49 button beside the Progress title uncovers blurred photos and numbers "
            + "while you are reading and covers them again. It does NOT change the Privacy "
            + "switch in Settings, and it is forgotten when the app closes.");

        helpSectionLabel(a.body, "Photos");
        Ui.card(a, a.body, "Taking a photo you can actually compare",
            "The viewfinder draws a LEVEL BUBBLE: the phone's own tilt and turn, live. A "
            + "photo taken at a different angle from the last one is a different photo of the "
            + "same body, which is exactly the thing a comparison must not confuse with "
            + "change \u2014 so each capture records the angle it was shot at.\n\n"
            + "You can set a REFERENCE ANGLE once, and the bubble then reads deviation from "
            + "it rather than from upright, so the next photo repeats the last one. ALIGN "
            + "lets you rotate a captured frame to match; imported photos (from the gallery) "
            + "are MARKED as imported, because the app cannot know what angle they were taken "
            + "at and will not pretend otherwise.");

        helpSectionLabel(a.body, "Settings");
        Ui.card(a, a.body, "What is in here",
            "TRAINING SCHEDULE \u2014 which weekdays you train, at what time. Your streak is "
            + "counted over these days.\n\n"
            + "REMINDERS \u2014 optional notifications: one at your training time on your training "
            + "days, and one that checks whether a measurement is due and stays silent on the "
            + "days it is not.\n\n"
            + "SEAL CHECK \u2014 an optional short hold before a routine run, to prove the cuff is "
            + "sealed before anything longer starts. Off by default.\n\n"
            + "SAFETY CEILING \u2014 see below. UNITS \u2014 pressure in kPa, inHg or cmHg and size in "
            + "cm or inches; changing a unit only changes what is PRINTED, never what is "
            + "stored.\n\n"
            + "PRIVACY \u2014 blur photos and measurements as they are drawn. Your readings, your "
            + "photo files and every export are untouched by it.\n\n"
            + "OFFER TO SAVE \u2014 how much you have to change mid-run before the app offers to "
            + "keep it, and which routines never ask.\n\n"
            + "DEVELOPER \u2014 behind the code 0000. That is a speed bump against a mis-tap, not "
            + "security: it guards the tools that command the pump unattended and the debug "
            + "log, and no measurement or photo is behind it.");

        helpSectionLabel(a.body, "Safety");
        Ui.card(a, a.body, "What the app will not do",
            "STOP VENTS. The red STOP on the run screen does not pause anything \u2014 it "
            + "commands the pump to release, and the same button lives on the run's "
            + "notification so it is reachable with the app off screen.\n\n"
            + "EVERY EXIT ABORTS. Leaving a live session \u2014 Back, a tab, the app being "
            + "destroyed \u2014 ends it and vents. If the app cannot confirm the vent it says so "
            + "and keeps saying so: it will not let a new session start on top of an "
            + "unconfirmed one.\n\n"
            + "VENT EVIDENCE. \u201cVented\u201d means the pump's own telemetry reported the pressure "
            + "down, not that the app sent a command and hoped. Until that evidence arrives "
            + "the session stays flagged.\n\n"
            + "THE CEILING. Your Settings ceiling is enforced app-side before EVERY write. A "
            + "set above it is lowered to it and flagged in its own editor \u2014 never silently "
            + "sent \u2014 and every live adjustment during a run is bounded by it too.");

        Ui.noteInfo(a, a.body, "This app is a controller and a logbook.",
            "What the app will not do", "This app is a controller and a logbook. It measures nothing "
            + "about you on its own, it makes no medical claim, and every number it draws "
            + "is one you typed or one the pump reported.\n\n"
            + "It is not a medical device and not medical advice. You use it at your own "
            + "risk, with no warranty. Stop if anything hurts or feels wrong, and take "
            + "health questions to a doctor.\n\n"
            + "The full disclaimer is DISCLAIMER.md in the OpenPump source repository.");
    }
}
