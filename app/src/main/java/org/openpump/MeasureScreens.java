package org.openpump;

import android.widget.ScrollView;
import java.util.Calendar;
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

/**
 * THE FOUR MEASUREMENT SCREENS - the session baseline, log-a-reading, the
 * after-measurement and the reading editor - lifted out of SessionActivity
 * verbatim. See docs/superpowers/plans/2026-09-07-sessionactivity-split.md.
 *
 * THEY SHARE ONE DRAFT AND IT IS NOT HELD HERE. logRowVals, the phase, the method,
 * the standardised flag and the two capture figures are fields on the Activity: a
 * draft owned by a screen would be discarded on every rebuild, and these screens
 * rebuild on every bump. This class is a renderer over that draft, nothing more.
 */
final class MeasureScreens {
    private final SessionActivity a;

    MeasureScreens(SessionActivity a) { this.a = a; }

    /** POINT 19 - this visit to the log door has already filed its at-rest reading on the
     *  way into a hold (option A), so "Release without measuring?" can say it is kept. */
    private boolean atRestSavedThisVisit;
    /** Whether the person set the date or time on this visit. If not, a standardised
     *  reading is dated when its hold starts, not when the door was opened. */
    private boolean logTsPicked;

    /** Entry point: seeds the steppers from the last logged reading (or a sane
     *  default for a fresh install) and renders. Bump/note handlers below call
     *  renderMeasureBefore() directly, never this — re-seeding on every stepper tap
     *  would silently discard whatever the user just typed. */
    void showMeasureBefore() {
        seedBaseline();
        beginBaseline();
        renderMeasureBefore();
    }

    /**
     * POINT 19 - THE BASELINE READING IS RESERVED BEFORE ITS HOLD: a new id and an empty photo
     * buffer. It used to happen as the baseline screen was first drawn, which with the hold on
     * is AFTER the hold - so a photo taken while the pump held for this very reading was wiped
     * by the screen it was taken for. SessionActivity#beginSession calls this before
     * startStd("measure"), and the hand-over draws the screen without it (WiringCheck
     * invariant 84).
     */
    void beginBaseline() {
        // Privacy: the capture before this one is discarded first - its unsaved photos go
        // (SessionActivity#discardCapture), under ITS id, before the new id is reserved.
        a.discardCapture();
        a.measReadingId = "m" + System.currentTimeMillis();
    }

    /** The baseline screen as a hold hands over to it (SessionActivity#continueFromHold): its
     *  reading was reserved before the hold (beginBaseline), so its photos are kept. Opened
     *  fresh its numbers are seeded; resumed after "Hold again" on it (M1) it is drawn as it
     *  was left. */
    void showBaselineAfterHold(boolean resume) {
        if (!resume) seedBaseline();
        renderMeasureBefore();
    }

    /** The baseline steppers' starting numbers, the note and the method - never the reading's
     *  id or its photos, which beginBaseline owns. */
    private void seedBaseline() {
        Model.Reading last = a.model.measLog.latestPre();
        // Gated on measuredLength/measuredGirth (Task 1, 64cd5a0): the newest PRE reading
        // can be girth-only (e.g. the last thing logged was an MSEG row), and seeding the
        // LENGTH stepper from its unmeasured `len` would hand the user a fabricated 0.00 —
        // the fresh-install default is the honest fallback here, same as `last == null`.
        // M1 (safety review): after a hold ended (a skip, its limit, the notification) this
        // is measured again at rest - nothing is seeded from before (invariant 96).
        a.measLen = a.holdEnded() ? Double.NaN
            : last != null && last.measuredLength() ? last.len : 15.0;
        a.measGir = a.holdEnded() ? Double.NaN
            : last != null && last.measuredGirth() ? last.gir : 12.0;
        a.measNote = "";
        a.measMethod = Model.Reading.METHOD_BPEL;
    }

    /** M1 (safety review) - a capture number, or "—" while it is unset: after a hold ended
     *  under the capture, the at-rest reading is measured again (SessionActivity#
     *  clearHeldDraft), and nothing typed under the hold is shown as its value. */
    private static String lenOrUnset(double cm) {
        return Double.isNaN(cm) ? "\u2014" : Model.Fmt.len(cm);
    }

    /** Where the first −/+ on an unset number starts: that method's own last reading of the
     *  metric - never a number typed under a hold - else the long-standing default. */
    private double seedMetric(int method, boolean girth) {
        List<Model.Reading> mine = Meas.ofMethod(a.model.measLog.all, method);
        for (int i = 0; i < mine.size(); i++) {
            double v = girth ? mine.get(i).gir : mine.get(i).len;
            if (v > 0) return v;
        }
        return girth ? 12.0 : 15.0;
    }

    /** A save that must wait for the vent's confirmation, drawn as one that cannot be tapped
     *  yet - the chip above says why. */
    private void waitsForVent(Button save) {
        Ui.setEnabled(a, save, false, Ui.GOOD);
        save.setContentDescription("Confirming the vent \u2014 save once the pump reports "
            + "the pressure falling");
    }

    /**
     * M1 - THE ONE CHIP EVERY CAPTURE SCREEN DRAWS FOR THE HOLD IT IS TAKEN UNDER, from the
     * verdict it was drawn with (HoldWindow#state), in the owner's words: what the reading
     * IS and what it is compared with - never "flagged" or "not standardised".
     *   OPEN    - standardised, and the two minutes live beneath it: they run until Save;
     *   LAPSED  - held past the two minutes: Hold again, or save it as it is;
     *   SHORT   - held, but not yet for the count: Hold for it, or save it as it is;
     *   AT_REST - after a hold the pump vented (its limit, the notification, a skip), says
     *             so; a capture that was never held draws nothing here.
     */
    private void holdChip(int state) {
        String p = Model.Fmt.p(a.model.std.kpa);
        int sec = a.model.std.sec;
        if (state == HoldWindow.OPEN) {
            Ui.chip(a, a.body, "Standardised at " + p,
                "held " + sec + " s \u00b7 compared with your other standardised readings",
                Ui.SURF, Ui.GOOD);
            a.captureWindowLine(a.body);
        } else if (state == HoldWindow.LAPSED) {
            Ui.chip(a, a.body, HoldWindow.LAPSED_TITLE, HoldWindow.lapsedSentence(p, sec),
                Ui.SURF, Ui.DIM);
            a.holdAgainControl(a.body, true);
        } else if (state == HoldWindow.SHORT) {
            Ui.chip(a, a.body, HoldWindow.shortTitle(sec), HoldWindow.shortSentence(p, sec),
                Ui.SURF, Ui.DIM);
            a.holdAgainControl(a.body, false);
        } else if (state == HoldWindow.VENTING && a.keptLinkDown()) {
            // KEEP THE READING - the link is lost: the vent was sent and cannot be confirmed.
            // What is true, and the two ways on: reconnect (the pump chip), or the person's
            // own eyes - offered at once while the link is down, by the kept capture's own
            // button, which opens this reading's save and settles no stop (WiringCheck 122,
            // 123). With the link back the usual vent wording below takes over.
            Ui.chip(a, a.body, KeepReading.LINK_LOST_TITLE, KeepReading.LINK_LOST_SENTENCE,
                Ui.SURF, Ui.CRIT);
            a.addKeptSeenBtn();
        } else if (state == HoldWindow.VENTING) {
            // M1 (safety review) - THE VENT IS SENT, NOT YET SEEN: nothing here says it has
            // happened (SAFETY.md #2). The one vent wording: "Confirming the vent…", or, once
            // retrying or exhausted, why it is not confirmed - with the person's own "I can
            // see the cuff is vented" when the retries are spent.
            String[] w = a.ventPendingWords();
            Ui.chip(a, a.body, w[0], (a.holdTimedOut ? "The hold reached its time limit \u2014 "
                : "") + w[1], Ui.SURF, Ui.CMD);
            a.addVentSeenBtn();
        } else if (a.holdTimedOut) {
            // Only drawn at rest - once the vent is evidenced: a reported fall, an inferred
            // one (said as "the pump reads no vacuum", never as confirmed), or the person's eyes.
            Ui.chip(a, a.body, "At rest", HoldWindow.limitSentence(a.ventHow()),
                Ui.SURF, Ui.DIM);
        } else if (a.holdLinkLost) {
            Ui.chip(a, a.body, "At rest",
                KeepReading.atRestSentence(KeepReading.LINK_LOST, a.ventHow()), Ui.SURF, Ui.DIM);
        } else if (a.holdLeftApp) {
            Ui.chip(a, a.body, "At rest",
                KeepReading.atRestSentence(KeepReading.LEFT_APP, a.ventHow()), Ui.SURF, Ui.DIM);
        } else if (a.holdEndedForApp) {
            // H1 - vented so another app (the phone's camera app, the photo picker) could
            // open: the same event as a release, with its own reason said.
            Ui.chip(a, a.body, "At rest", HoldHandOff.endedSentence(a.ventHow()),
                Ui.SURF, Ui.DIM);
        } else if (a.holdLetGo || a.holdSkipped) {
            Ui.chip(a, a.body, "At rest", HoldWindow.skippedSentence(a.ventHow()),
                Ui.SURF, Ui.DIM);
        }
    }

    /** M1 - THE SAVE FILES WHAT THE SCREEN SAID. The verdict is asked again at Save (the two
     *  minutes are counted until Save); if it moved on since the screen was drawn - the
     *  minutes ran out, or the pump vented, under the person's finger - the screen is
     *  redrawn saying so and nothing is filed. The next tap files what is then on screen. */
    boolean holdMovedOnSinceDrawn(int state) {
        if (state == a.captureDrawnState()) return false;
        a.toast(state == HoldWindow.LAPSED
                ? "The two minutes just ran out \u2014 hold again, or save it as it is"
            : state == HoldWindow.AT_REST
                ? HoldWindow.ventedToast(a.ventHow())
            : state == HoldWindow.VENTING
                ? "The pump is venting \u2014 save once it reports the pressure falling"
                : "The hold changed \u2014 check the reading, then save");
        reRenderCapture();
        return true;
    }

    /** C8 - why an at-rest session screen has no Girth stepper, where the stepper was.
     *  Every method these screens offer at rest is a length method, so a girth typed here
     *  could not be kept; the log door is where an at-rest girth is filed, as its own
     *  reading under its own girth method. */
    private void girthAtRestNote(int filing) {
        // G-Measure (B): it names the real items - there is no "Girth" item, the sheet offers
        // MSEG and MSSG, each said by what it means.
        Ui.noteInfo(a, a.body, "Length only, so no girth is saved. Log girth as MSEG or MSSG "
            + "in Log a reading.",
            "Logging girth", "This method measures length only, so no girth is saved. To log "
            + "girth, use Log a reading › Add measurement › MSEG (around the middle, "
            + "erect) or MSSG (around the middle, soft).");
    }

    /**
     * Baseline screen, per proto/pump-console.html's #v-measure / renders around
     * logBaseline(): length, girth, an optional note, and the privacy statement.
     * "Save and start session" logs the reading and starts the pump; "Skip
     * measurements" starts the pump without logging (and without resetting the
     * cadence counters — a skipped measurement is still due next time); "Cancel"
     * abandons the whole start attempt.
     */
    private void renderMeasureBefore() {
        a.body.removeAllViews();
        a.enterFlow(Nav.SCR_BASELINE, Nav.STEP_BASELINE);
        Ui.head(a, a.body, "Measurements  ·  before");
        a.heldHeader(a.body);
        // The standardisation badge — mirrors the prototype's mStdChip/renderStdBadge(),
        // driven by the measured dwell, never by which button ended the hold screen (defect
        // #14's fix made visible here) - and (M1) by the verdict as it stands NOW, because
        // the two minutes are counted until Save. Item 20 / STUDY-19 R4, R5: each case says
        // what the reading IS and what it is compared with (holdChip).
        int held = a.captureHoldState();
        a.captureDrawn(held);
        holdChip(held);
        if (a.model.std.on && HoldWindow.atRest(held) && !a.holdEnded())
            Ui.chip(a, a.body, "At rest", "compared with your other at-rest readings "
                + "of the same method", Ui.SURF, Ui.DIM);
        // Item 20: it read "Baseline · 2 min before starting", which looked like a timer,
        // and nothing on this screen takes two minutes. It is advice, so it says the advice.
        // Method advice, read once rather than every session: one line, the rest behind ⓘ.
        Ui.noteInfo(a, a.body, "Measure the same way each time.",
             "Measure the same way each time",
             "Same time of day, same state, same reference point. Being consistent "
             + "matters more than being precise.");
        // The hold is STILL OUTSTANDING on this screen — exitStdHold deliberately does not
        // clear heldKpa — so the same live line belongs here, for the whole time the user
        // spends entering numbers.
        if (a.session.heldKpa() != null) a.addLiveReadout();

        // AT REST, THE METHOD is what is asked — and only the method. The old hard/soft
        // state chip was removed: the method name already encodes it (BPEL is erect, the
        // stretched/flaccid protocols are soft), so a separate state question was redundant.
        // The reading's state is now derived from the chosen method at save time
        // (Model.Reading#stateForMethod), which keeps a same-session before/after pair
        // comparable exactly as before. M1: at rest is the drawn verdict - never held, or no
        // hold live any more (vented by its limit, the notification or a skip) - the same
        // test the after screen makes, so the two ends of a pair agree about it.
        // (M1, safety review: while the vent is being confirmed the reading WILL be an at-rest
        // one, so it is drawn as one - and cannot be saved until the fall is seen.)
        boolean atRest = HoldWindow.atRest(held) || held == HoldWindow.VENTING;
        if (atRest) {
            atRestMethodRow(a.measMethod, new View.OnClickListener[]{
                new MeasMethodTap(Model.Reading.METHOD_BPEL),
                new MeasMethodTap(Model.Reading.METHOD_BPSSL),
                new MeasMethodTap(Model.Reading.METHOD_BPSL),
                new MeasMethodTap(Model.Reading.METHOD_NBPEL),
                new MeasMethodTap(Model.Reading.METHOD_NBPSL) });
        }

        // C8 - ONLY THE STEPPERS THE SAVE WILL KEEP. The same decision SaveBaselineTap files
        // (Meas#captureMethod): at rest every method offered here is a length method, so a
        // Girth stepper was typed into and then silently dropped. See girthAtRestNote().
        int filing = Meas.captureMethod(HoldWindow.standardised(held), atRest, a.measMethod);
        Ui.stepperRow(a, a.body, "Length  (" + Say.lenRange(5.0, 30.0) + ")", lenOrUnset(a.measLen),
                new BumpMeas(0, -1), new BumpMeas(0, 1),
                a.new TypeTap(SessionActivity.TypedField.MEAS_LEN), "length");
        if (Model.Reading.methodMeasuresGirth(filing)) {
            Ui.stepperRow(a, a.body, "Girth  (" + Say.lenRange(5.0, 25.0) + ")", lenOrUnset(a.measGir),
                    new BumpMeas(1, -1), new BumpMeas(1, 1),
                    a.new TypeTap(SessionActivity.TypedField.MEAS_GIR), "girth");
        }
        Ui.note(a, a.body, a.holdEnded() ? "Measure again at rest \u2014 nothing taken under "
            + "the hold is used. Tap a value to type it." : "Tap a value to type it.");
        if (!Model.Reading.methodMeasuresGirth(filing)) girthAtRestNote(filing);
        // G1 - asked ahead of the length session for the girth session that follows today.
        else if (a.girthBeforeFirst) Ui.note(a, a.body, SameDay.GIRTH_BEFORE_FIRST_NOTE);

        Button note = Ui.flat(a, a.body, noteLabel(a.measNote));
        note.setOnClickListener(new EditMeasNoteTap());

        // Photo slots — per proto/pump-console.html's #v-measure dashed Front/Side
        // placeholders. Camera capture is Task 7's job (see PhotoSlotTap below); these
        // are tappable placeholders only, each reflecting whether ITS view has been
        // captured this attempt so Task 7 can wire the real camera in without adding
        // the slots themselves.
        Ui.fieldLabel(a, a.body, "Photos", "optional");
        addPhotoRow();

        stayOnPhone();

        Button save = Ui.big(a, a.body, "Save and start session", Ui.GOOD);
        save.setOnClickListener(new SaveBaselineTap());
        if (!HoldWindow.mayFile(held)) waitsForVent(save);
        skipLink(new SkipMeasTap(), "Skip measurements and start the session");
        Button cancel = Ui.big(a, a.body, "Cancel", Ui.SURFHI);
        cancel.setOnClickListener(new CancelBaselineTap());
    }

    /** G-Measure (B): ONE NAME FOR THE NOTE - "Add a note" until one is written, then the
     *  note itself - on every capture screen and the editor. */
    private static String noteLabel(String note) {
        return note == null || note.isEmpty() ? "Add a note" : "Note: " + note;
    }

    /** G-Measure (B): ONE SKIP - a centred text link, "Skip measurements", under the one
     *  primary, the same on the before and after screens. */
    private void skipLink(View.OnClickListener tap, String said) {
        TextView skip = Ui.textLink(a, "Skip measurements", true, tap);
        skip.setTextColor(Ui.TEXT);
        skip.setTextSize(Look.SP_BODY);
        skip.setContentDescription(said);
        a.body.addView(skip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * ITEM 20 - "STAYS ON THIS PHONE", one line with the detail behind its (i). It was a
     * large developer-voice card, "Private by construction", ending "No cloud, no account,
     * no backup" - while Settings offers "Back up now", whose zip holds every photo. The
     * sentence behind the (i) says what is true of both.
     */
    private void stayOnPhone() {
        LinearLayout card = Ui.cardGroup(a, a.body, null, null, 0);
        card.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, 2), Ui.dp(a, Look.S5), Ui.dp(a, 2));
        LinearLayout row = Ui.noteInfo(a, card, "Stays on this phone", "Stays on this phone",
            "Measurements and photos are kept on this phone and never go into the debug log. "
            + "They leave it only in an export or a backup that you make.");
        if (row.getChildCount() > 0 && row.getChildAt(0) instanceof TextView) {
            TextView t = (TextView) row.getChildAt(0);
            t.setTextColor(Ui.TEXT);
            t.setTextSize(Look.SP_BODY);
        }
        android.widget.ImageView phone = Ui.iconView(a, R.drawable.ic_phone, Ui.TEXT, 20);
        LinearLayout.LayoutParams ip = (LinearLayout.LayoutParams) phone.getLayoutParams();
        ip.rightMargin = Ui.dp(a, Look.S4);
        row.addView(phone, 0, ip);
    }

    /**
     * FIX ROUND 2, Critical — this was `new Tap(Tap.HOME)`, i.e. showHome() and nothing
     * else, and it is the ONE exit from the standardisation flow that left the pump
     * holding.
     *
     * exitStdHold() deliberately does not clear heldKpa — "only the release gate that runs
     * next gets to say that vacuum is gone" — and Cancel is precisely the exit where no
     * release gate runs next. Every sibling vents: Save and Skip go through
     * proceedAfterMeasure() → startRelease(); the after-screen's Back and Save go through
     * ventIfHeld(); the hold preview arms its own watch on the way out. This one rendered
     * Today over a cuff still at the hold pressure, with `running` false, `sealChecking`
     * false, stdTick already removed and — because armStdHold()/sendHoldPreset() each call
     * cancelVentWatch() — nothing polling and no stop frame ever written. The device holds
     * both setpoints equal indefinitely; only Proto.stop() ends it.
     *
     * (Pre-existing rather than introduced here — the button predates BASE — but it is the
     * same hazard class as the handoff fix, closed at only one of its two exits.)
     */
    private final class CancelBaselineTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            boolean held = a.session.heldKpa() != null;
            a.ventIfHeld("StopWork (baseline cancelled)");
            if (held) a.toast("Cancelled — venting the pump");
            a.showHome();
        }
    }

    /**
     * THE METHOD PICKER THE AT-REST SESSION SCREENS SHOW (baseline and after) - the five
     * at-rest LENGTH protocols. A session baseline/after is a length-primary reading, so it
     * carries a length method; the standalone "Log a reading" door offers the girth
     * protocols too.
     *
     * STANDARDIZED IS NOT ON IT, on purpose. That method means "captured under the
     * standardisation hold", and the hold path stamps it itself; offering it here would let
     * a reading taken at rest claim a hold that never ran.
     *
     * THE APP'S OWN CONTROL, AND WHAT THE CHOICE MEANS (measurement polish, items 7 and
     * 20). This was two rows of radio-glyph buttons carrying bare codes. It is now the
     * segmented control the rest of the app uses for one-of-several, and the line under it
     * says in words what the chosen code means (Model.Reading#methodMeaning, the one table),
     * with how to take it behind the (i): nowhere in the app said what any code was.
     *
     * `taps` is the five listeners in tag order: BPEL, BPSSL, BPSL, NBPEL, NBPSL.
     */
    private void atRestMethodRow(int sel, View.OnClickListener[] taps) {
        int[] order = { Model.Reading.METHOD_BPEL, Model.Reading.METHOD_BPSSL,
                        Model.Reading.METHOD_BPSL, Model.Reading.METHOD_NBPEL,
                        Model.Reading.METHOD_NBPSL };
        String[] labels = new String[order.length], said = new String[order.length];
        int chosen = 0;
        for (int i = 0; i < order.length; i++) {
            labels[i] = Model.Reading.methodLabel(order[i]);
            said[i] = Model.Reading.methodMeaning(order[i]);
            if (order[i] == sel) chosen = i;
        }
        Ui.fieldLabel(a, a.body, "Measured as", null);
        /* G-MEASURE (option B): THE CODES SAY WHAT THEY MEAN BEFORE THE TAP. Five bare codes
         * in a segmented row explained only the chosen one; these are the "Add a
         * measurement" sheet's own cells - each code over its meaning, two to a line - with
         * the chosen one ringed, so a first visit can compare them without tapping each. */
        for (int k = 0; k < order.length; k += 2) {
            boolean pair = k + 1 < order.length;
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = Ui.dp(a, Look.S3);
            a.body.addView(line, rp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    pair ? ViewGroup.LayoutParams.MATCH_PARENT
                         : ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Ui.dp(a, Look.S3);
            line.addView(pickCell(labels[k], said[k], k == chosen, taps[k]), lp);
            if (pair)
                line.addView(pickCell(labels[k + 1], said[k + 1], k + 1 == chosen, taps[k + 1]),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            else
                line.addView(new View(a), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        int m = order[chosen];
        Ui.noteInfo(a, a.body, Model.Reading.methodMeaning(m) + ".",
            Model.Reading.methodLabel(m), Model.Reading.methodHowTo(m) + "\n\n"
            + "Each method is drawn as its own line on the trend and compared only with "
            + "itself. A reading taken under the standardisation hold is filed as "
            + "Standardised by that hold, never chosen here.");
    }

    /** One method to pick on the session screens: methodCell's code over its meaning, the
     *  chosen one on the selected fill with the accent ring, heard as a radio button. */
    private LinearLayout pickCell(String code, String meaning, boolean on,
                                  View.OnClickListener tap) {
        LinearLayout cell = codeCell(code, meaning);
        if (on) {
            android.graphics.drawable.GradientDrawable bg =
                Ui.roundRect(a, Ui.ACCENT_DIM, Look.R_CTRL);
            bg.setStroke(Math.max(1, Ui.dp(a, 1)), Ui.ACCENT);
            cell.setBackground(bg);
        }
        cell.setAccessibilityDelegate(new Ui.RadioA11y(on));
        cell.setSelected(on);
        cell.setClickable(true);
        cell.setOnTouchListener(new Ui.Press());
        if (tap != null) cell.setOnClickListener(tap);
        return cell;
    }

    private final class MeasMethodTap implements View.OnClickListener {
        private final int method;
        MeasMethodTap(int m) { method = m; }
        @Override public void onClick(View v) { a.measMethod = method; renderMeasureBefore(); }
    }

    private final class BumpMeas implements View.OnClickListener {
        private final int field, dir;
        BumpMeas(int f, int d) { field = f; dir = d; }
        @Override public void onClick(View v) {
            // An unset number (measured again at rest after a hold ended) starts from the
            // filing method's own last reading, never from anything typed under the hold.
            int st = a.captureHoldState();
            int m = HoldWindow.atRest(st) || st == HoldWindow.VENTING ? a.measMethod
                  : Model.Reading.METHOD_STANDARDIZED;
            if (field == 0) a.measLen = Say.clamp1(Double.isNaN(a.measLen) ? seedMetric(m, false)
                                                   : a.measLen + dir * 0.1, 5.0, 30.0);
            else            a.measGir = Say.clamp1(Double.isNaN(a.measGir) ? seedMetric(m, true)
                                                   : a.measGir + dir * 0.1, 5.0, 25.0);
            reRenderCapture();
        }
    }

    /** A dialog, not an inline field — the same reasoning as RenameSetTap: the
     *  screen rebuilds on every stepper tap, which would drop focus/keyboard out
     *  from under an inline EditText. Staged: only "Save" commits the note. */
    private final class EditMeasNoteTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            EditText input = new EditText(a);
            // An EditText in a dialog has no label view to associate with, and the
            // AlertDialog title is not one — a hint IS announced as the field's label,
            // and unlike a contentDescription it does not replace what the user typed.
            input.setHint("note for this reading");
            input.setText(a.measNote);
            input.setSelection(input.getText().length());
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Note")
                .setView(input)
                .setPositiveButton("Save", new MeasNoteConfirm(input))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class MeasNoteConfirm implements DialogInterface.OnClickListener {
        private final EditText input;
        MeasNoteConfirm(EditText e) { input = e; }
        @Override public void onClick(DialogInterface d, int w) {
            a.measNote = input.getText().toString().trim();
            reRenderCapture();
        }
    }

    /** Re-render whichever capture screen is live — the session baseline or the standalone
     *  "Log a reading" — so the shared stepper/note/photo handlers (BumpMeas,
     *  MeasNoteConfirm, PhotosDoneTap) never redraw the wrong one. */
    void reRenderCapture() {
        if (a.currentScreen == Nav.SCR_LOG_READING) renderLogReading();
        else if (a.currentScreen == Nav.SCR_MEASURE_AFTER) renderMeasureAfter();
        else renderMeasureBefore();
    }

    /** A tappable placeholder card for one photo slot, styled after the prototype's
     *  dashed Front/Side buttons — a filled background standing in for the dashed
     *  border (Android buttons have no simple dashed-border style), a check mark
     *  once that view is taken, a plus while it isn't. minHeight guarantees the
     *  48dp touch target regardless of how little text it holds. */
    private Button photoSlotButton(boolean taken, String label) {
        Button b = new Button(a);
        b.setText((taken ? "✓ " : "+ ") + label + "\n" + (taken ? "taken" : "not taken"));
        // "✓"/"+" read as "check mark"/"plus" — say what the slot is and what tapping it
        // does instead. Not the radio-marker path: a photo slot is not one of a choice.
        b.setContentDescription(label + " photo, " + (taken ? "taken" : "not taken")
            + ". Opens the camera.");
        b.setAllCaps(false);
        b.setTextSize(Look.SP_LABEL);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(taken ? Ui.ACCENT : Ui.DIM);   // pass F - progress, not a verdict
        b.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CTRL));
        b.setMinHeight(Ui.dp(a, 56));
        b.setPadding(Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14));
        return b;
    }

    /** The POV / Side photo-slot row, shared by the session baseline screen and the
     *  standalone "Log a reading" screen. Driven off {@link Shot#VIEWS}, which no longer
     *  contains Top — so dropping a view from the capture flow is a change in one array,
     *  not a hunt through the screens. Each slot opens the capture flow ON that view; the
     *  booleans are set only once the flow reports back (PhotosDoneTap), never from a
     *  render. The button carries the LABEL, the tap carries the stored KEY. */
    private void addPhotoRow() {
        LinearLayout photoRow = new LinearLayout(a);
        photoRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Shot.VIEWS.length; i++) {
            Button b = photoSlotButton(a.shot.has(Shot.VIEWS[i]), Shot.label(Shot.VIEWS[i]));
            b.setOnClickListener(new PhotoSlotTap(Shot.VIEWS[i]));
            LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < Shot.VIEWS.length - 1) lp.rightMargin = Ui.dp(a, 6);
            photoRow.addView(b, lp);
        }
        LinearLayout.LayoutParams photoRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        photoRowLp.bottomMargin = Ui.dp(a, 8);
        a.body.addView(photoRow, photoRowLp);
    }

    /** Item 9 - "Photo during the hold": the capture flow opened straight away, on the first
     *  view this reading has not got yet, from SessionActivity#photoDuringHold. The same
     *  flow a slot tap opens, handing back to the same screen. Nothing when every view is
     *  already taken. */
    void openCameraForHold() {
        for (int i = 0; i < Shot.VIEWS.length; i++)
            if (!a.shot.has(Shot.VIEWS[i])) {
                a.camera.open(a.measReadingId, a.shot, Shot.VIEWS[i], new PhotosDoneTap());
                return;
            }
    }

    /** Opens the real capture flow (Task 7) for the tapped view, tied to this attempt's
     *  reserved measReadingId. PhotosDoneTap below is where the slots' booleans get set
     *  once the flow reports back — a render must never be what decides that; this tap,
     *  and the callback it hands to CameraScreen, are. */
    private final class PhotoSlotTap implements View.OnClickListener {
        private final String view;             // Shot.FRONT / Shot.SIDE / Shot.TOP
        PhotoSlotTap(String v) { view = v; }
        @Override public void onClick(View v) {
            a.camera.open(a.measReadingId, a.shot, view, new PhotosDoneTap());
        }
    }

    /** CameraScreen's whole capture attempt (any views, or a skip) is over — reflect what
     *  actually got captured (Shot is the single source of truth) and re-render the screen
     *  that opened it. Never called from inside a render. */
    private final class PhotosDoneTap implements CameraScreen.Callback {
        @Override public void onCameraDone(boolean tookAny) {
            if (a.currentScreen == Nav.SCR_LOG_READING) renderLogReading();
            else if (a.currentScreen == Nav.SCR_MEASURE_AFTER) renderMeasureAfter();
            else renderMeasureBefore();
        }
    }

    private final class SaveBaselineTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            Model.Reading reading = new Model.Reading();
            // A4 - stamped at the moment it is taken, from the cylinder that is active NOW.
            // Never back-filled onto an older reading: which cylinder that one used is not
            // knowable, and -1 says so.
            reading.cylinder = a.model.activeCylinderIndex();
            reading.cylinderId = a.model.activeCylinderId();
            // Reuses the SAME id CameraScreen already wrote reading-<id>-<view>.jpg
            // under (measReadingId is reserved in showMeasureBefore, before any photo
            // is taken) — a fresh id here would silently orphan any capture already on
            // disk from this attempt.
            reading.id = a.measReadingId != null ? a.measReadingId : "m" + System.currentTimeMillis();
            reading.ts = System.currentTimeMillis();
            reading.label = a.dayLabel(reading.ts);
            // Derived from the measured dwell, never from which button ended the hold
            // screen (defect #14) — null/null when no hold ran or the hold was cut short,
            // the real configured values when it genuinely completed. Never a lone 0.0
            // standing in for "no hold". M1: and asked NOW, at Save - the two minutes are
            // counted until Save - and refused if it moved on since the screen was drawn.
            int held = a.captureHoldState();
            if (holdMovedOnSinceDrawn(held)) return;
            // M1 (safety review): nothing is filed while the vent is being confirmed.
            if (!HoldWindow.mayFile(held)) {
                a.toast("Confirming the vent \u2014 save once the pump reports the pressure falling");
                return;
            }
            boolean standardised = HoldWindow.standardised(held);
            boolean atRest = HoldWindow.atRest(held);
            // THE METHOD IS DECIDED FIRST (F2 fix) — state, holdKpa/holdSec-derived
            // comparability, and len/gir below all key off this SAME decision, so none
            // of them can disagree about what kind of reading this is. The hold path
            // stamps STANDARDIZED because it is the only path that knows the hold ran,
            // an at-rest baseline carries the length protocol the user chose, and a
            // SKIPPED hold (pressure up, not standardised, no state) falls back to
            // STANDARDIZED — the taxonomy has no legacy sentinel, and holdKpa still
            // records the pressure.
            int method = Meas.captureMethod(standardised, atRest, a.measMethod);
            // WRITE ONLY WHAT THE METHOD ACTUALLY MEASURES (F2 fix). The screen used to
            // show both the Length and the Girth stepper — the at-rest picker above
            // offers five LENGTH protocols only, there is no at-rest girth choice on
            // this screen — so an at-rest baseline saved under e.g. BPSSL used to write
            // `measGir` into `gir` regardless, even though BPSSL never asked for girth.
            // That real, user-entered number then poisoned every consumer gated on
            // Model.Reading#measuredGirth (Task 1, 64cd5a0): std OFF suppressed it
            // everywhere (CSV included) because the method said "length only", while
            // the raw field silently kept carrying it — an ambiguity manufactured here,
            // on the write side, that no consumer-side gate could ever resolve. Left at
            // the primitive double's 0.0 default, methodMeasuresGirth() correctly
            // reports it absent, matching what every gated consumer already shows.
            // (C8: and the screen no longer offers a Girth stepper this would drop - it
            // asks the same Meas#captureMethod before it draws one.)
            // M1 (safety review): a number unset since a hold ended must be measured first.
            if ((Model.Reading.methodMeasuresLength(method) && Double.isNaN(a.measLen))
                    || (Model.Reading.methodMeasuresGirth(method) && Double.isNaN(a.measGir))) {
                a.toast("Enter what you measured at rest first");
                return;
            }
            if (Model.Reading.methodMeasuresLength(method)) reading.len = a.measLen;
            if (Model.Reading.methodMeasuresGirth(method))  reading.gir = a.measGir;
            reading.note = a.measNote;
            reading.holdKpa = standardised ? Double.valueOf(a.model.std.kpa) : null;
            reading.holdSec = standardised ? Integer.valueOf(a.model.std.sec) : null;
            // Task 19 change 1: record the pressure the pump ACTUALLY reported, not the
            // one it was commanded. Comparability is judged on this, not on holdKpa. A
            // standardised reading with no fresh telemetry gets observedKpa == null
            // (unknown) — never backfilled from the commanded setpoint. freshBaselineKpa()
            // is the app's single freshness rule (NaN for stale/noReading); no second one
            // is invented here. 0.10 (StdMoment): taken at the END OF THE COUNT, the moment
            // the protocol standardises; the value at Save only as the fallback.
            if (standardised) a.fileObservedKpa(reading);
            else reading.observedKpa = null;
            // At rest, the state DERIVED from the chosen method (stateForMethod: erect
            // protocols hard, stretched/flaccid soft); standardised (or a hold that was
            // skipped, pressure still up) carries none — the same split SaveLogReadingTap
            // makes. This is what lets a same-session at-rest before/after pair be
            // comparable, now on the method axis with the state as its derived fallback.
            reading.state = atRest
                ? Model.Reading.stateForMethod(a.measMethod) : Model.Reading.STATE_UNKNOWN;
            reading.method = method;
            // A SESSION BASELINE IS THE "PRE" READING — that is what a baseline is, so it
            // is stamped here rather than asked about. The standalone door asks, because
            // there a reading genuinely might be either.
            reading.phase = Model.Reading.PHASE_PRE;
            // Folds the per-view slots into the single photo flag the model has — mirrors
            // the prototype's tookAny(): ANY view captured counts.
            reading.photo = a.shot.tookAny();
            if (a.shot.has(Shot.FRONT)) reading.photoFront = a.photoFrom(Shot.FRONT, reading.ts);
            if (a.shot.has(Shot.SIDE))  reading.photoSide  = a.photoFrom(Shot.SIDE, reading.ts);
            // No photoTop line: Top left Shot.VIEWS, so nothing can capture into it. The
            // FIELD stays on Reading — old top photos still render (Shot#TOP).
            reading.edited = false;
            a.model.measLog.log(reading, a.model.meas);
            // Which baseline the after-measurement will be diffed against, and the fact
            // that it belongs to THIS session — see Model.Sess#afterBaseTs.
            a.sessionBaselineTs = reading.ts;
            Store.save(a, a.model);
            // What it IS (the owner's rule, STUDY-19 R6): standardised, at rest under its
            // method, or held outside the count or the two minutes - never "flagged".
            Ui.snack(a, a.rootFrame,
                (standardised ? "Baseline logged \u00b7 standardised"
                    : atRest ? "Baseline logged \u00b7 at rest \u00b7 "
                               + Model.Reading.methodLabel(method)
                    : HoldWindow.keptSnack(true))
                + a.trendSnackSuffix());
            a.proceedAfterMeasure();
        }
    }

    private final class SkipMeasTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.toast("Measurements skipped for this session");
            a.proceedAfterMeasure();
        }
    }

    /** Fresh entry: seed the steppers from the last reading, reserve the id (so a photo
     *  taken before Save lands under the same reading-<id>-<view>.jpg), and start at rest. */
    void showLogReading(final int origin, final boolean remeasure) {
        // `remeasure`: the length card's "Measure again" (SessionActivity#logRemeasure).
        // THE FRONT DOOR WAS THE ONE MEASUREMENT SCREEN WITH NO GATE (audit A34). Every
        // other one asks — renderMeasHist, the Photos shortcut, the trends content — but
        // Today's own "Log a reading" button, its popup-nav twin and the launcher shortcut
        // all landed here directly. The steppers below are seeded from the last real length
        // and girth, so an unlocked open put those numbers on screen outright, and the
        // privacy blur does not cover this screen either. Gated HERE, at the entry, so one
        // check covers every caller.
        /* CLEAR FIRST, exactly as OpenComparePairTap does above and for the same reason:
         * nothing has cleared `body` at this point on the locked branch. renderLogReading()
         * is what normally clears it, and it never runs at all when this returns true - so
         * without this the lock plate was appended UNDER whatever screen was on a moment
         * ago, leaving Today with a lock hanging off the bottom of it and the Today tab
         * still lit. The only gate in the app that was missing this. */
        a.body.removeAllViews();
        a.enterDest(Nav.SCR_LOG_READING);
        if (a.appLockGate(AppLock.MEASUREMENTS, new Runnable() {
                @Override public void run() { showLogReading(origin, remeasure); }
            })) return;
        a.logReadingOrigin = origin;
        a.logRemeasure = remeasure;
        a.holdTimedOut = false;   // C8: a fresh visit, no hold behind it
        a.holdLetGo = false;      // M1
        a.holdSkipped = false;    // M1
        a.holdEndedForApp = false;   // H1
        a.endKeptCapture();          // keep the reading
        Model.Reading last = a.model.measLog.latestPre();
        // Gated on measuredLength/measuredGirth (Task 1, 64cd5a0): the newest PRE reading
        // can be girth-only (e.g. the last thing logged was an MSEG row), and seeding the
        // LENGTH stepper from its unmeasured `len` would hand the user a fabricated 0.00 —
        // the fresh-install default is the honest fallback here, same as `last == null`.
        a.measLen = last != null && last.measuredLength() ? last.len : 15.0;
        a.measGir = last != null && last.measuredGirth() ? last.gir : 12.0;
        a.measNote = "";
        // Privacy: the last capture is discarded under its own id before the new one is
        // reserved - an unsaved photo from it does not stay on the phone.
        a.discardCapture();
        a.measReadingId = "m" + System.currentTimeMillis();
        a.logReadingTs = System.currentTimeMillis();
        /* EVERY ROW EMPTY on entry - see logRowVals. `measLen`/`measGir` above still seed the
         * HELD (standardising) entry, which is a single reading with both metrics and has
         * not changed.
         *
         * UNLESS THE LAST VISIT WAS INTERRUPTED BY A RUN ENDING. Clearing is right for a
         * fresh visit and wrong for coming back to one the app itself walked away from: the
         * rows survive the jump on their own, and wiping them here would be the app
         * destroying work it interrupted. The flag is consumed, so the visit after this one
         * starts empty like any other. */
        if (a.logRowsInterrupted) a.logRowsInterrupted = false;
        else for (int i = 0; i < a.logRowVals.length; i++) a.logRowVals[i] = null;
        a.logReadingPhase = Model.Reading.PHASE_UNKNOWN;
        // C-F1: a re-measure of the session's after-reading is an after-reading.
        if (remeasure) a.logReadingPhase = Model.Reading.PHASE_POST;
        // P4 - a fresh visit starts at rest. The type is a property of the reading being
        // built, so it resets with the rest of it rather than leaking in from last time.
        a.logStandardised = false;
        atRestSavedThisVisit = false;
        logTsPicked = false;
        renderLogReading();
    }

    void renderLogReading() {
        a.body.removeAllViews();
        // Pressure outstanding == we are in the standardised capture that follows the hold.
        // The tab bar is absent for the whole time the pump holds real vacuum (enterFlow),
        // exactly as it is for the session baseline; the at-rest capture is a true front
        // door and keeps the bar (enterDest).
        boolean held = a.session.heldKpa() != null;
        // M1 - THE CAPTURE IS THE HELD ONE WHILE A HOLD IS LIVE (HoldWindow#state, asked now).
        // Once the pump has been vented under it - its limit, the notification, a skip - the
        // reading is an at-rest one and this is the at-rest door again, with its methods
        // (the C8 follow-up: it used to keep the held shape and file a reading with no
        // method, compared with nothing). Nothing typed under the hold is carried into it:
        // the at-rest rows are measured again (safety review; each kind is compared only
        // with its own). While the vent is being confirmed (VENTING) it is drawn as that
        // at-rest door and cannot be saved. The tab bar stays away while the vent is still
        // being confirmed (`held`).
        int holdState = a.captureHoldState();
        a.captureDrawn(holdState);
        boolean capture = !HoldWindow.atRest(holdState) && holdState != HoldWindow.VENTING;
        boolean ended = !capture && (a.holdEnded() || holdState == HoldWindow.VENTING);
        if (held) a.enterFlow(Nav.SCR_LOG_READING, -1);
        else       a.enterDest(Nav.SCR_LOG_READING);
        Ui.head(a, a.body, "Log a reading");

        /* P4 / P3-C - THE TYPE IS A TYPE; THE BUTTON IS THE ACTION.
         *
         * This row used to be BOTH. "Standardise first" sat directly above "When was this
         * taken?", looked identical to it, and was read as the same kind of thing - except
         * that one records a fact about the reading and the other pulled a vacuum on a
         * person. A radio that pressurises you is a control lying about what it is.
         *
         * The resolution is not to disguise the type as a button: standardised-versus-at-rest
         * genuinely IS a kind of measurement, and belongs with the other facts about the
         * reading. What changes is that CHOOSING it no longer commands anything. The type is
         * recorded here; the pump is commanded by the button at the foot of the screen, whose
         * label and colour follow the type - and which, for a standardised reading, leads to
         * a capture screen that holds, takes the two numbers and releases.
         *
         * While a hold IS outstanding the row becomes a readout rather than a control: the
         * capture is under way, and its ending is the release control below, not a radio.
         */
        // (M1: while the pump is still venting after a hold ended, there is no type row - the
        // reading is at rest, and the chip below says why.)
        // (Item 20: the segmented control, where these were ●/○ buttons.)
        if (!(ended && held)) Ui.note(a, a.body, "What kind of reading is this?");
        String[] kinds = { "At rest", "Standardised" };
        String[] kindsSaid = { "no pressure on the cuff",
                               "measured while the pump holds a set pressure" };
        if (capture) {
            Ui.segmented(a, a.body, kinds, kindsSaid, 1,
                new View.OnClickListener[]{ new LogModeTap(false), null });
        } else if (!(ended && held)) {
            Ui.segmented(a, a.body, kinds, kindsSaid, a.logStandardised ? 1 : 0,
                new View.OnClickListener[]{ new LogTypeTap(false), new LogTypeTap(true) });
            // At rest says nothing more here: the segment's own words already say it.
            if (a.logStandardised)
                Ui.note(a, a.body, "Taken while the pump holds a set pressure, and compared "
                    + "with your other standardised readings.");
        }

        if (ended) {
            holdChip(holdState);
            // The fall, live, while it is still being confirmed.
            if (held) a.addLiveReadout();
        } else if (capture) {
            a.heldHeader(a.body);
            holdChip(holdState);
            // The measured pressure, live, so the user sees what is actually being recorded.
            a.addLiveReadout();
        } else if (a.logStandardised) {
            /* THE CHOSEN TYPE, NOT THE PUMP'S STATE. Everything below the radio branched on
             * `held` - a hold OUTSTANDING - so picking "Standardised" before any pressure
             * exists left the screen describing the reading as taken at rest and offering
             * the at-rest method rows, whose values the "Standardise and measure" button
             * then discards on its way to the capture screen. The hold decides what the
             * capture screen shows; the radio decides what THIS screen says. */
            Ui.chip(a, a.body, "Standardised  ·  not yet held",
                "the numbers are taken on the capture screen, under the hold the button at "
                + "the foot of this screen starts — nothing is measured or commanded here",
                Ui.SURF, Ui.CMD);
        } else {
            // How readings are compared is method: the one line, with it behind the ⓘ.
            Ui.noteInfo(a, a.body, "Taken at rest", "Taken at rest",
                "Each method is compared with your other at-rest readings of that method, "
                + "and the trend draws it as its own line.");
            // NO METHOD PICKER ANY MORE at rest: the method is not a question with one
            // answer here. Every method is offered as its own ROW below, and whichever rows
            // the user fills are the methods they took — see the row list. There is still no
            // separate hard/soft state chip, because each method name already encodes it
            // (BPEL erect, BPSSL/BPSL/NBPSL/MSSG soft) and the state is derived at save.
        }

        // WHEN, RELATIVE TO A SESSION. Optional on purpose — "—" is a real answer and the
        // default one. It decides which line of the trend this reading joins, and whether
        // it can be half of a post-vs-pre pair; it changes nothing about the measurement.
        // Item 20: plain words on the app's own control. "—" was an answer nobody could
        // read; it is the reading that is neither said to be before nor after a session.
        Ui.note(a, a.body, "When was this taken? (optional)");
        Ui.segmented(a, a.body,
            new String[]{ "Before", "After", "Not said" },
            new String[]{ "before a session", "after a session", "not said" },
            a.logReadingPhase == Model.Reading.PHASE_PRE ? 0
                : a.logReadingPhase == Model.Reading.PHASE_POST ? 1 : 2,
            new View.OnClickListener[]{
                new LogPhaseTap(Model.Reading.PHASE_PRE),
                new LogPhaseTap(Model.Reading.PHASE_POST),
                new LogPhaseTap(Model.Reading.PHASE_UNKNOWN) });
        Ui.noteInfo(a, a.body, "Before a session, or after one.",
            "When this was taken", "Before a session, or after one. Readings taken after a session "
            + "are drawn as their own line on the trend — they are larger for reasons that "
            + "wear off, and mixing them into one line would read as growth.");

        // DATE & TIME (Task 4). Defaults to the real clock at screen-open, overridable
        // here before Save — the "when relative to a session" chip above answers a
        // different question (before/after) from this one (an actual calendar date).
        // A future date is refused at Save, not here: setMaxDate below stops the day
        // picker but cannot constrain the time-of-day picker, so "today at 23:59" is
        // still reachable from an earlier clock — see SaveLogReadingTap/saveAtRestRows.
        Ui.fieldLabel(a, a.body, "Date & time", null);
        Ui.kvRow(a, a.body, "Date", a.dayLabel(a.logReadingTs), Ui.TEXT, new PickLogDate());
        Ui.kvRow(a, a.body, "Time", a.timeLabel(a.logReadingTs), Ui.TEXT, new PickLogTime());

        // THE MEASUREMENTS. Two shapes, and they are genuinely different entries:
        //
        //   HELD — the standardisation capture. One reading, filed as STANDARDIZED, both
        //   metrics, exactly as it always was. The hold decided the method; there is nothing
        //   to choose and no second method that could have been taken under the same hold.
        //
        //   AT REST — one row per method, every value empty. Fill any subset; Save files one
        //   reading per filled method, all sharing this moment and these photos. This is what
        //   a person following a protocol actually does in one sitting, and filing it as
        //   three walks through the door produced three timestamps and three sets of photos
        //   of the same body at the same moment.
        Ui.fieldLabel(a, a.body, "Measurements", null);
        if (capture) {
            Ui.stepperRow(a, a.body, "Length  (" + Say.lenRange(5.0, 30.0) + ")", lenOrUnset(a.measLen),
                    new BumpMeas(0, -1), new BumpMeas(0, 1),
                    a.new TypeTap(SessionActivity.TypedField.MEAS_LEN), "length");
            Ui.stepperRow(a, a.body, "Girth  (" + Say.lenRange(5.0, 25.0) + ")", lenOrUnset(a.measGir),
                    new BumpMeas(1, -1), new BumpMeas(1, 1),
                    a.new TypeTap(SessionActivity.TypedField.MEAS_GIR), "girth");
            Ui.note(a, a.body, "Tap a value to type it.");
        } else if (a.logStandardised) {
            // NO ROWS FOR A HOLD THAT HAS NOT HAPPENED. They are taken on the capture
            // screen, and rows offered here are rows the next tap throws away.
            Ui.note(a, a.body, "Taken on the next screen, under the hold.");
            // POINT 19 - nothing entered at rest is hidden or moved by choosing Standardised:
            // it is named here, and saved as its own at-rest reading when the hold starts.
            atRestCard();
        } else {
            if (ended)
                Ui.note(a, a.body, "Measure again at rest and add what you take \u2014 "
                    + "nothing taken under the hold is used for an at-rest reading.");
            logMethodRows();
        }

        Button note = Ui.flat(a, a.body, noteLabel(a.measNote));
        note.setOnClickListener(new EditMeasNoteTap());

        // The two view names are the labels on the buttons directly below: addPhotoRow
        // draws one per Shot.VIEWS, so naming them again here is the by-hand copy
        // of that array the helper exists to make unnecessary.
        // POINT 19 - NO PHOTO SLOTS BEFORE THE HOLD. A photo taken on the "Standardised, not
        // yet held" screen is taken at rest, and it used to end up on the standardised
        // reading; the rows above are refused here for the same reason. The standardised
        // reading's photos are taken on the capture screen, under its hold.
        if (AtRestFirst.photoSlotsShown(capture, a.logStandardised)) {
            Ui.fieldLabel(a, a.body, "Photos", "optional");
            addPhotoRow();
            Ui.noteInfo(a, a.body, "Each slot opens your camera.", "Photos",
                "A photo slot opens your device's camera; review it and add a note before it is saved.");
        } else {
            Ui.note(a, a.body, "Photos for the standardised reading are taken on the next "
                + "screen, under the hold.");
        }

        // THE SAVE BUTTON COUNTS. At rest the entry is a list the user built, so the button
        // says how much of it is about to be filed — and says plainly that an empty list
        // files nothing, instead of offering "Save reading" and then refusing with a toast.
        /* P3-C - THE ONE CONTROL THAT COMMANDS ANYTHING, at the foot, after everything that
         * needs no pressure has been filled in. At rest it saves. Standardised, it goes to
         * the capture screen - which holds, takes the two numbers under that hold, and
         * releases - so the only stretch under vacuum is the stretch that has to be. */
        if (!capture && a.logStandardised) {
            // POINT 19, OPTION A - with an at-rest reading started, the button says it is
            // saved first: "Save at rest, then standardise ›". Photos taken at rest with no
            // measurement to file them with hold it back, and it says why.
            int first = atRestState();
            Button go = Ui.big(a, a.body, AtRestFirst.buttonLabel(first),
                first == AtRestFirst.NEEDS_NUMBER ? Ui.SURFHI : Ui.CMD);
            if (first == AtRestFirst.NEEDS_NUMBER) {
                Ui.setEnabled(a, go, false, Ui.SURFHI);
                go.setContentDescription(AtRestFirst.waitingReason(a.shot.taken().size()));
            } else {
                go.setContentDescription((first == AtRestFirst.SAVE_FIRST
                        ? "Save your at-rest reading, then pull to " : "Pull to ")
                    + A11y.collapse(Model.Fmt.p(a.model.std.kpa))
                    + ", hold it, and take the measurements under that hold");
                go.setOnClickListener(new StartStandardisedCaptureTap());
            }
            Ui.note(a, a.body, AtRestFirst.buttonNote(first, Model.Fmt.p(a.model.std.kpa)));
            Ui.noteInfo(a, a.body, "Nothing is commanded until you tap that.",
                "Standardising", "Everything above is recorded before any pressure is "
                + "applied, which is why the button is at the bottom. The pull starts when "
                + "you tap it and not before.");
            a.staggerBodyIn();
            return;
        }
        int filledNow = capture ? 1 : Meas.filledLogRows(a.logRowVals);
        String saveLabel = capture ? "Save reading"
            : (filledNow == 0 ? "Nothing to save"
                              : "Save " + filledNow + (filledNow == 1 ? " reading" : " readings"));
        Button save = Ui.big(a, a.body, saveLabel, filledNow == 0 ? Ui.SURFHI : Ui.GOOD);
        if (filledNow == 0) {
            Ui.setEnabled(a, save, false, Ui.SURFHI);
            save.setContentDescription("Nothing to save — add a measurement first");
        }
        save.setOnClickListener(new SaveLogReadingTap());
        if (!HoldWindow.mayFile(holdState)) waitsForVent(save);
        Button cancel = Ui.big(a, a.body,
            capture ? "Give up — vent the pump and exit" : "Cancel", Ui.SURFHI);
        cancel.setOnClickListener(new CancelLogReadingTap());
    }

    private final class LogPhaseTap implements View.OnClickListener {
        private final int phase;
        LogPhaseTap(int p) { phase = p; }
        @Override public void onClick(View v) { a.logReadingPhase = phase; renderLogReading(); }
    }

    /** Opens the platform date picker on logReadingTs's current date. PD-6: this is a
     *  PLATFORM dialog, not a Ui.dialog() one — matches PickSchedTime/PickMeasTime's own
     *  bare, undressed presentation rather than forcing Ui.dress onto it. setMaxDate
     *  stops the day picker from offering a future date at all (the day half of the
     *  future-date refusal — see SaveLogReadingTap for the time-of-day half, which this
     *  cannot constrain). */
    private final class PickLogDate implements View.OnClickListener {
        @Override public void onClick(View v) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(a.logReadingTs);
            // SYS-14: the app's dark picker, dressed.
            android.app.DatePickerDialog d = Ui.datePicker(a, new LogDateSet(),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            d.getDatePicker().setMaxDate(System.currentTimeMillis());
            Ui.showPicker(a, d);
        }
    }

    private final class LogDateSet implements android.app.DatePickerDialog.OnDateSetListener {
        @Override public void onDateSet(android.widget.DatePicker view, int y, int m, int day) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(a.logReadingTs);
            c.set(y, m, day);
            a.logReadingTs = c.getTimeInMillis();
            logTsPicked = true;
            renderLogReading();
        }
    }

    private final class PickLogTime implements View.OnClickListener {
        @Override public void onClick(View v) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(a.logReadingTs);
            Ui.showPicker(a, Ui.timePicker(a, new LogTimeSet(),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true));
        }
    }

    private final class LogTimeSet implements android.app.TimePickerDialog.OnTimeSetListener {
        @Override public void onTimeSet(android.widget.TimePicker tp, int h, int m) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(a.logReadingTs);
            c.set(Calendar.HOUR_OF_DAY, ((h % 24) + 24) % 24);
            c.set(Calendar.MINUTE, ((m % 60) + 60) % 60);
            a.logReadingTs = c.getTimeInMillis();
            logTsPicked = true;
            renderLogReading();
        }
    }

    /**
     * THE STANDALONE DOOR'S METHOD ROWS (at-rest path) — one row per measurement method,
     * every value EMPTY until the user puts something in it.
     *
     * This replaces the old single-method picker. The picker asked "which one measurement is
     * this?", which is the wrong question for the way the door is used: someone following a
     * protocol takes BPEL, NBPEL and MSEG within a couple of minutes of each other, off one
     * body in one state, and often photographs it once. Under the picker that was three
     * separate walks through the door, three timestamps minutes apart, and three separate
     * captures of the same moment.
     *
     * EMPTY IS THE DEFAULT AND EMPTY IS AN ANSWER. A row that has never been touched reads
     * "—" and files nothing. Nothing is pre-seeded from the last reading: a value already
     * sitting in a row is a measurement the app made up, and Save cannot tell it apart from
     * one the user meant. The FIRST tap on a stepper seeds from that method's own last
     * reading (or a sane default), which is the moment the user has said they are answering
     * this row.
     *
     * ADD-FLOW, NOT A FORM. The screen no longer draws nine rows and asks which of them are
     * true; it draws the measurements you have ADDED and one button that offers the rest.
     * Nine stepper rows was ~900dp of mostly-empty screen for a sitting that is typically
     * two or three methods, and the empty rows were indistinguishable from the filled ones
     * at a glance. What is on this screen now IS what will be saved.
     *
     * ADDED == FILLED. There is no separate "added but empty" state: picking a method from
     * the sheet seeds it (from that method's own last reading, else a mid default) and
     * swiping the row away (see RemoveLogRow) takes it away again, so `logRowVals` remains
     * the single source of truth and {@link Meas#filledLogRows} / {@link Meas#buildLogReadings}
     * are untouched. A seeded
     * value is safe here in a way it was not on the old always-visible form: the user asked
     * for this row by name, so a number in it is a number they were shown and can correct,
     * not one the app slipped into a row they never looked at.
     *
     * Tapping the value types it exactly (the range hint lives in that box, not in the
     * label); −/+ step by 0.1 cm through the same clamp.
     */
    private void logMethodRows() {
        int lens = 0, girs = 0;
        for (int i = 0; i < Meas.LOG_ROWS.length; i++)
            if (a.logRowVals[i] != null) { if (Meas.LOG_ROWS[i].girth) girs++; else lens++; }

        if (lens + girs == 0) {
            Ui.noteInfo(a, a.body, "Nothing added yet. Add the methods you actually took.",
            "Methods", "Nothing added yet. Add the methods you actually took — "
                + "each one is filed as its own reading, all stamped with this moment and "
                + "sharing these photos.");
        } else {
            // GROUPED ONLY WHEN THERE IS SOMETHING TO GROUP: a section label over a single
            // group is a heading that separates nothing. Lengths first, then girths.
            boolean both = lens > 0 && girs > 0;
            if (both) Ui.sectionHead(a, a.body, "Length", Ui.ACCENT);
            logRowsOfMetric(false);
            if (both) Ui.sectionHead(a, a.body, "Girth", Ui.ACCENT);
            logRowsOfMetric(true);
        }

        Button add = Ui.big(a, a.body, "+  Add measurement", Ui.ACCENT);
        add.setContentDescription("Add a measurement method");
        add.setOnClickListener(new AddMeasureTap());
    }

    /** The added rows of one metric, in LOG_ROWS order. */
    private void logRowsOfMetric(boolean girth) {
        for (int i = 0; i < Meas.LOG_ROWS.length; i++) {
            Meas.LogRow row = Meas.LOG_ROWS[i];
            if (row.girth != girth || a.logRowVals[i] == null) continue;
            Ui.measureRow(a, a.body, row.label,
                Model.Fmt.len(a.logRowVals[i].doubleValue()),
                new BumpLogRow(i, -1), new BumpLogRow(i, 1),
                a.new TypeTap(SessionActivity.TypedField.LOG_ROW_BASE + i),
                new RemoveLogRow(i),
                row.label + " " + (girth ? "girth" : "length"));
        }
    }

    /** Opens the picker. */
    private final class AddMeasureTap implements View.OnClickListener {
        @Override public void onClick(View v) { openAddMeasureSheet(); }
    }

    /**
     * THE METHOD PICKER — a bottom sheet in the two sections the methods actually fall
     * into, with everything already on the screen dimmed and inert so the sheet can never
     * add a second row for one method.
     *
     * EACH CODE SAYS WHAT IT MEANS (item 7). Seven bare codes in pills read as a quiz; each
     * cell is now the code over its meaning, two to a line, both from the one table
     * (Model.Reading#methodMeaning). "How to take each" at the foot opens the longer words
     * for all of them, over this sheet, so nothing already picked is lost.
     *
     * NO "Std" (item 8). This is the at-rest sheet, and Std means measured under the hold;
     * the foot of the sheet says where a standardised reading is taken instead - the type
     * control at the top of the page - so the absence is explained where it is noticed.
     */
    private void openAddMeasureSheet() {
        if (a.isFinishing()) return;
        LinearLayout box = Ui.col(a);
        int p = Ui.dp(a, Look.S5);
        box.setPadding(p, 0, p, Ui.dp(a, Look.S2));
        Ui.fieldLabel(a, box, "Length", null);
        addMethodGrid(box, false);
        View gl = Ui.fieldLabel(a, box, "Girth", null);
        gl.setPadding(0, Ui.dp(a, Look.S5), 0, 0);
        addMethodGrid(box, true);
        Ui.note(a, box, "Standardised readings are taken under the hold: choose "
            + "\u201cStandardised\u201d at the top of the page.");
        ScrollView sc = new ScrollView(a);
        if (android.os.Build.VERSION.SDK_INT >= 29) sc.setEdgeEffectColor(Look.ACCENT);
        sc.addView(box);
        a.addMeasureSheet = Ui.dialog(a)
            .setTitle("Add a measurement")
            .setView(sc)
            .setNeutralButton("How to take each", null)
            .setNegativeButton("Cancel", null)
            .show();
        Ui.sheet(a, a.addMeasureSheet);
        Button how = a.addMeasureSheet.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (how != null) {
            // Its own listener rather than the dialog's, which would take the sheet down on
            // the way: the explanation opens over the sheet, and Back returns to it.
            how.setAllCaps(false);
            how.setTextColor(Ui.ACCENT);
            how.setCompoundDrawablesRelativeWithIntrinsicBounds(
                Ui.icon(a, R.drawable.ic_info, Ui.ACCENT), null, null, null);
            how.setCompoundDrawablePadding(Ui.dp(a, Look.S2));
            how.setOnClickListener(new HowToMeasureTap());
        }
        Button cancel = a.addMeasureSheet.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (cancel != null) { cancel.setAllCaps(false); cancel.setTextColor(Ui.TEXT); }
    }

    /** One section's cells, two to a line: each method's code over what it means. */
    private void addMethodGrid(LinearLayout box, boolean girth) {
        List<Integer> rows = new ArrayList<Integer>();
        for (int i = 0; i < Meas.LOG_ROWS.length; i++)
            if (Meas.LOG_ROWS[i].girth == girth) rows.add(Integer.valueOf(i));
        for (int k = 0; k < rows.size(); k += 2) {
            boolean pair = k + 1 < rows.size();
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = Ui.dp(a, Look.S3);
            box.addView(line, rp);
            // A pair shares one height, so a meaning that wraps does not leave its neighbour
            // short. A last cell on its own keeps its own height and half the width, as a grid
            // does, rather than stretching across the line as if it were a different thing.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    pair ? ViewGroup.LayoutParams.MATCH_PARENT
                         : ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Ui.dp(a, Look.S3);
            line.addView(methodCell(rows.get(k).intValue()), lp);
            if (pair)
                line.addView(methodCell(rows.get(k + 1).intValue()),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            else
                line.addView(new View(a), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    /** One cell: the code, and under it what the code means. One already on the screen is
     *  dimmed and inert, and said so. */
    private LinearLayout methodCell(int idx) {
        Meas.LogRow row = Meas.LOG_ROWS[idx];
        boolean added = a.logRowVals[idx] != null;
        String meaning = Model.Reading.methodMeaning(row.method);
        LinearLayout cell = codeCell(row.label, meaning);
        Ui.group(cell, row.label + ", " + meaning + (added ? ", already added" : ""));
        if (added) {
            cell.setEnabled(false);
            // SYS-10: the unavailable fill and ink (Ui.dependentBlock's), not an alpha.
            Ui.dependentBlock(a, cell, false);
        } else {
            cell.setClickable(true);
            cell.setOnTouchListener(new Ui.Press());
            cell.setOnClickListener(new PickMeasureTap(idx));
            cell.setAccessibilityDelegate(new SaidAsButton());
        }
        return cell;
    }

    /** The cell itself - the code, and under it what the code means - shared by the "Add a
     *  measurement" sheet and the session screens' method picker. */
    private LinearLayout codeCell(String label, String meaning) {
        LinearLayout cell = Ui.col(a);
        cell.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL));
        cell.setPadding(Ui.dp(a, 12), Ui.dp(a, 9), Ui.dp(a, 12), Ui.dp(a, 10));
        cell.setMinimumHeight(Ui.dp(a, 48));
        TextView code = new TextView(a);
        code.setText(label);
        code.setTextColor(Ui.TEXT);
        code.setTextSize(Look.SP_BODY);
        Ui.semibold(code);
        cell.addView(code);
        TextView what = new TextView(a);
        what.setText(meaning);
        what.setTextColor(Ui.DIM);
        what.setTextSize(Look.SP_CAPTION);
        what.setPadding(0, Ui.dp(a, 2), 0, 0);
        cell.addView(what);
        Ui.group(cell, label + ", " + meaning);
        return cell;
    }

    /** A cell that adds a method is announced as the button it is. */
    private static final class SaidAsButton extends View.AccessibilityDelegate {
        @Override public void onInitializeAccessibilityNodeInfo(
                View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName("android.widget.Button");
        }
    }

    /** "How to take each", from the sheet's foot. */
    private final class HowToMeasureTap implements View.OnClickListener {
        @Override public void onClick(View v) { showHowToMeasure(); }
    }

    /**
     * HOW TO TAKE EACH - the one page that says how every method is measured (item 7), read
     * from the same table as the sheet's one-line meanings (Model.Reading#methodHowTo), in
     * the sheet's order and under its headings, with Standardised last: it is taken under the
     * hold, not picked from the sheet. A dialog over the sheet rather than a screen of its
     * own, so reading it costs nothing already typed or picked.
     */
    void showHowToMeasure() {
        if (a.isFinishing()) return;
        LinearLayout box = Ui.col(a);
        int p = Ui.dp(a, Look.S5);
        box.setPadding(p, 0, p, Ui.dp(a, Look.S3));
        Ui.fieldLabel(a, box, "Length", null);
        for (int i = 0; i < Meas.LOG_ROWS.length; i++)
            if (!Meas.LOG_ROWS[i].girth) howToEntry(box, Meas.LOG_ROWS[i].method);
        View gl = Ui.fieldLabel(a, box, "Girth", null);
        gl.setPadding(0, Ui.dp(a, Look.S5), 0, 0);
        for (int i = 0; i < Meas.LOG_ROWS.length; i++)
            if (Meas.LOG_ROWS[i].girth) howToEntry(box, Meas.LOG_ROWS[i].method);
        View sl = Ui.fieldLabel(a, box, "Standardised", null);
        sl.setPadding(0, Ui.dp(a, Look.S5), 0, 0);
        howToEntry(box, Model.Reading.METHOD_STANDARDIZED);
        Ui.note(a, box, "Whichever you use, measure the same way each time.");
        ScrollView sc = new ScrollView(a);
        if (android.os.Build.VERSION.SDK_INT >= 29) sc.setEdgeEffectColor(Look.ACCENT);
        sc.addView(box);
        Ui.dress(a, Ui.dialog(a)
            .setTitle("How to take each")
            .setView(sc)
            .setPositiveButton("Back", null)
            .show());
    }

    /** One method on the how-to page: its code and meaning, then how to take it. */
    private void howToEntry(LinearLayout box, int method) {
        LinearLayout e = Ui.col(a);
        e.setPadding(0, Ui.dp(a, Look.S3), 0, Ui.dp(a, Look.S2));
        TextView head = new TextView(a);
        head.setText(Model.Reading.methodLabel(method) + "  \u00b7  "
            + Model.Reading.methodMeaning(method));
        head.setTextColor(Ui.TEXT);
        head.setTextSize(Look.SP_CHIP);
        Ui.medium(head);
        e.addView(head);
        TextView how = new TextView(a);
        how.setText(Model.Reading.methodHowTo(method));
        how.setTextColor(Ui.DIM);
        how.setTextSize(Look.SP_CAPTION);
        how.setLineSpacing(0f, 1.15f);
        how.setPadding(0, Ui.dp(a, 2), 0, 0);
        e.addView(how);
        Ui.group(e, Model.Reading.methodLabel(method) + ", "
            + Model.Reading.methodMeaning(method) + ". " + Model.Reading.methodHowTo(method));
        box.addView(e);
    }

    /** A chip: seed the row, close the sheet, redraw with the row on the screen. */
    private final class PickMeasureTap implements View.OnClickListener {
        private final int idx;
        PickMeasureTap(int i) { idx = i; }
        @Override public void onClick(View v) {
            boolean gir = Meas.LOG_ROWS[idx].girth;
            a.logRowVals[idx] = Double.valueOf(Say.clamp1(seedForRow(idx), 5.0, gir ? 25.0 : 30.0));
            if (a.addMeasureSheet != null) { a.addMeasureSheet.dismiss(); a.addMeasureSheet = null; }
            renderLogReading();
        }
    }

    /** Swipe-to-remove's landing spot (Ui.measureRow's SwipeToRemove invokes this once the
     *  row has finished animating off-screen): unsay this measurement. Nulling the slot is
     *  the whole removal — the row exists exactly because the slot is non-null — but unlike
     *  the ✕ this replaced, a swipe is not undo-proof: the value is captured before it is
     *  cleared and handed to an UNDO snackbar, so an over-eager swipe is never a silent
     *  loss. */
    private final class RemoveLogRow implements View.OnClickListener {
        private final int idx;
        RemoveLogRow(int i) { idx = i; }
        @Override public void onClick(View v) {
            Double removed = a.logRowVals[idx];
            a.logRowVals[idx] = null;
            renderLogReading();
            if (removed == null) return;
            Meas.LogRow row = Meas.LOG_ROWS[idx];
            Ui.snack(a, a.rootFrame, "Removed " + row.label,
                "UNDO", new UndoLogRemoveTap(idx, removed.doubleValue()),
                Snack.HOLD_MS_ACTIONABLE);
        }
    }

    /** UNDO on the snackbar {@link RemoveLogRow} raises: puts the exact value it captured
     *  back in the exact slot it came from. Holding the value on this tap (rather than, say,
     *  re-deriving it) means several swipes in a row each restore correctly even if their
     *  snackbars overlap, since Ui.snack only ever shows the latest one. */
    private final class UndoLogRemoveTap implements View.OnClickListener {
        private final int idx;
        private final double value;
        UndoLogRemoveTap(int i, double v) { idx = i; value = v; }
        @Override public void onClick(View v) {
            a.logRowVals[idx] = Double.valueOf(value);
            if (a.currentScreen == Nav.SCR_LOG_READING) renderLogReading();
        }
    }

    /** A row's −/+ . The FIRST tap on an empty row seeds it (that method's own last
     *  reading, or the same default the door has always opened on) rather than stepping from
     *  zero; every tap after that moves by the ordinary 0.1 cm, through the same clamp1 the
     *  typed path uses, so neither gesture can reach a value the other cannot. */
    private final class BumpLogRow implements View.OnClickListener {
        private final int idx, dir;
        BumpLogRow(int i, int d) { idx = i; dir = d; }
        @Override public void onClick(View v) {
            boolean gir = Meas.LOG_ROWS[idx].girth;
            double lo = gir ? 5.0 : 5.0, hi = gir ? 25.0 : 30.0;
            Double cur = a.logRowVals[idx];
            double base = cur != null ? cur.doubleValue() + dir * 0.1 : seedForRow(idx);
            a.logRowVals[idx] = Double.valueOf(Say.clamp1(base, lo, hi));
            renderLogReading();
        }
    }

    /** The value an empty row starts from on its first tap: the newest reading of THAT
     *  method (its own metric), falling back to the door's long-standing defaults. Reading a
     *  BPEL row's seed off an MSEG reading would put a girth number in a length row. */
    private double seedForRow(int idx) {
        Meas.LogRow row = Meas.LOG_ROWS[idx];
        List<Model.Reading> mine = Meas.ofMethod(a.model.measLog.all, row.method);
        if (!mine.isEmpty()) {
            Model.Reading r = mine.get(0);
            double v = row.girth ? r.gir : r.len;
            if (v > 0) return v;
        }
        return row.girth ? 12.0 : 15.0;
    }

    private final class LogTypeTap implements View.OnClickListener {
        private final boolean std;
        LogTypeTap(boolean s) { std = s; }
        @Override public void onClick(View v) {
            a.logStandardised = std;
            renderLogReading();
        }
    }

    /** P3-C - the one control that commands. Arms the hold and shows its screen through
     *  startStd, exactly as before; what changed is WHEN, and that a form full of answers is
     *  already recorded by the time it happens. */
    private final class StartStandardisedCaptureTap implements View.OnClickListener {
        @Override public void onClick(View v) { beginStandardisedCapture(); }
    }

    /** Which of AtRestFirst's three states the door is in: at-rest rows filled, photos
     *  taken at rest with no row, or nothing entered at rest. */
    private int atRestState() {
        return AtRestFirst.state(Meas.filledLogRows(a.logRowVals), a.shot.taken().size());
    }

    /**
     * POINT 19, OPTION A - "SAVE AT REST, THEN STANDARDISE". The one way into the log door's
     * hold, in this order:
     *
     *   1. Refuse before anything is filed when the hold could not start (no pump), or when
     *      photos taken at rest have no measurement to be filed with - the app never drops
     *      them on its own (the card offers to add the measurement, or to remove them).
     *   2. An at-rest reading started here is SAVED, exactly as the at-rest Save files it: one
     *      reading per filled method, the phase as chosen, the photos shared. Before any
     *      pressure is applied, so neither a give-up nor the hold limit can lose it.
     *   3. A NEW reading is begun for the hold: a fresh id, an empty photo buffer, no rows,
     *      and - unless the person set a date or time - the time of the hold. So a photo
     *      taken under the hold can never overwrite an at-rest photo's file, and nothing
     *      taken at rest can be carried onto the standardised reading.
     *   4. The hold starts.
     *
     * WiringCheck invariant 82 holds every log-door startStd to steps 3-4's order.
     */
    private void beginStandardisedCapture() {
        if (!a.link.isReady()) { a.toast("Not connected to the pump — cannot standardise"); return; }
        int first = atRestState();
        if (first == AtRestFirst.NEEDS_NUMBER) {
            a.toast("Add the at-rest measurement for your photos, or remove them, first");
            return;
        }
        if (first == AtRestFirst.SAVE_FIRST) {
            int photos = a.shot.taken().size();
            List<Model.Reading> filed = fileAtRestRows();
            if (filed == null) return;                 // refused, and the toast said why
            List<String> methods = new ArrayList<String>();
            for (int i = 0; i < filed.size(); i++)
                methods.add(Model.Reading.methodLabel(filed.get(i).method));
            Ui.snack(a, a.rootFrame, AtRestFirst.savedSnack(methods, photos));
            atRestSavedThisVisit = true;
        }
        // A NEW READING FOR THE HOLD - see the doc: fresh id, empty buffer, no rows. The
        // at-rest photos were just filed, so discarding keeps them (a reading uses them).
        a.discardCapture();
        a.measReadingId = "m" + System.currentTimeMillis();
        for (int i = 0; i < a.logRowVals.length; i++) a.logRowVals[i] = null;
        if (first == AtRestFirst.SAVE_FIRST || !logTsPicked) {
            a.logReadingTs = System.currentTimeMillis();
            logTsPicked = false;
        }
        a.startStd("log-reading", false);
        // startStd can still refuse (a second tap inside its re-entry guard). The draft was
        // begun afresh either way, so the door is drawn again to show what it now holds.
        if (a.currentScreen == Nav.SCR_LOG_READING) renderLogReading();
    }

    /**
     * POINT 19 - "YOUR AT-REST READING", on the Standardised screen before the hold: what has
     * been entered at rest and what will happen to it. With photos but no measurement it
     * asks for the measurement they go with, or lets the person remove them (with Undo).
     */
    private void atRestCard() {
        int state = atRestState();
        if (state == AtRestFirst.NOTHING) return;
        List<String> views = a.shot.taken();
        Ui.card(a, a.body, AtRestFirst.CARD_TITLE,
            AtRestFirst.cardLine(a.logRowVals, views, a.timeLabel(a.logReadingTs)) + "\n"
            + AtRestFirst.cardCaption(state, views.size()));
        if (state == AtRestFirst.NEEDS_NUMBER) {
            Button add = Ui.big(a, a.body, "+  Add the at-rest measurement", Ui.ACCENT);
            add.setContentDescription("Add the at-rest measurement your photos go with");
            add.setOnClickListener(new AddAtRestMeasurementTap());
            Button remove = Ui.flat(a, a.body,
                views.size() == 1 ? "Remove this photo" : "Remove these photos");
            remove.setOnClickListener(new RemoveAtRestPhotosTap());
        }
    }

    /** Back to At rest, with the method sheet open: the measurement is added where the
     *  at-rest rows live, so its value can be checked and corrected before it is filed. */
    private final class AddAtRestMeasurementTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.logStandardised = false;
            renderLogReading();
            openAddMeasureSheet();
        }
    }

    /**
     * The person's own choice to remove photos taken at rest that have no measurement. The
     * buffer is emptied at once and a new id reserved (so a photo taken next cannot reuse
     * the removed file's name); UNDO puts both back while the draft is still the same
     * at-rest one. The files themselves are deleted only once the Undo window has closed,
     * and only if nothing has come to use them - and never while an Undo, this one's or a
     * later Remove's, can still put them back (PhotoUndos: the review's Remove, Undo,
     * Remove, first clean-up, Undo deleted the photos the second Undo put back).
     */
    private final class RemoveAtRestPhotosTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            Shot kept = a.shot.copy();
            int n = kept.taken().size();
            if (n == 0) return;
            PhotoUndos.Removal removal = a.photoUndos.removed(kept, a.measReadingId);
            a.shot.reset();
            a.measReadingId = "m" + System.currentTimeMillis();
            renderLogReading();
            ForgetRemovedPhotos forget = new ForgetRemovedPhotos(removal);
            Ui.snack(a, a.rootFrame, AtRestFirst.removedSnack(n), "UNDO",
                new UndoRemoveAtRestPhotosTap(removal, forget, a.measReadingId),
                Snack.HOLD_MS_ACTIONABLE);
            a.ui.postDelayed(forget, Snack.HOLD_MS_ACTIONABLE + 1500);
        }
    }

    private final class UndoRemoveAtRestPhotosTap implements View.OnClickListener {
        private final PhotoUndos.Removal removal;
        private final Runnable forget;
        private final String newId;
        UndoRemoveAtRestPhotosTap(PhotoUndos.Removal r, Runnable f, String nid) {
            removal = r; forget = f; newId = nid;
        }
        @Override public void onClick(View v) {
            // Only back onto the SAME at-rest draft: never into a hold, never onto another
            // visit, never over photos taken since - and never once its clean-up has run.
            boolean same = a.photoUndos.isOpen(removal)
                && a.currentScreen == Nav.SCR_LOG_READING
                && a.session.heldKpa() == null && !a.holdTimedOut
                && newId.equals(a.measReadingId) && !a.shot.tookAny();
            if (!same) { a.toast("Too late to undo — the photos were not put back"); return; }
            // The Undo is used: its clean-up must never run, and the removal is closed, so a
            // clean-up that ran anyway would delete nothing (PhotoUndos#cleanUp).
            a.ui.removeCallbacks(forget);
            a.photoUndos.undone(removal);
            a.shot.copyFrom(removal.kept());
            a.measReadingId = removal.keptId();
            renderLogReading();
        }
    }

    /** The removed photos' files, once the Undo window is over (PhotoUndos#cleanUp): the
     *  same rule as any discarded capture, minus what the live buffer holds again and what
     *  any still-open Undo can put back - and nothing at all if this one was undone. */
    private final class ForgetRemovedPhotos implements Runnable {
        private final PhotoUndos.Removal removal;
        ForgetRemovedPhotos(PhotoUndos.Removal r) { removal = r; }
        @Override public void run() {
            List<String> gone = a.photoUndos.cleanUp(removal, a.shot, a.model.measLog);
            for (int i = 0; i < gone.size(); i++) a.deletePhotoFile(gone.get(i));
        }
    }

    private final class ConfirmReleaseCapture implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            a.logStandardised = false;
            cancelLogReading();
        }
    }

    private final class LogModeTap implements View.OnClickListener {
        private final boolean standardise;
        LogModeTap(boolean s) { standardise = s; }
        @Override public void onClick(View v) {
            boolean held = a.session.heldKpa() != null;
            if (standardise && !held) {
                beginStandardisedCapture();
            } else if (!standardise && held) {
                // P5 - THIS VENTS. It used to do so on the tap, which made a radio button a
                // destructive control with no warning: the capture is abandoned and the
                // reading taken under the hold cannot be recovered, because it was never
                // taken. Asked first, and named for what it does.
                AlertDialog d = Ui.dialog(a)
                    .setTitle("Release without measuring?")
                    // POINT 19 (T6) - what releasing really does: this dialog promised that
                    // "anything typed so far is kept", and the release then left the door,
                    // whose next visit started empty. The numbers and photos taken under
                    // this hold are not saved; an at-rest reading saved on the way in is.
                    .setMessage("The pump is holding " + Model.Fmt.p(a.model.std.kpa)
                        + " for this reading. Releasing vents it and ends this standardised "
                        + "reading: the numbers and photos taken under the hold are not saved."
                        + (atRestSavedThisVisit
                            ? " Your at-rest reading from this visit is already saved." : ""))
                    .setPositiveButton("Release", new ConfirmReleaseCapture())
                    .setNegativeButton("Keep holding", null)
                    .show();
                Ui.dress(a, d);
            }
            // else: already in the requested mode — nothing to do.
        }
    }

    /**
     * SAVE, in two shapes, matching the two entries renderLogReading draws.
     *
     * HELD — one standardised reading, unchanged.
     *
     * AT REST — one reading per FILLED method row. Everything they share is read ONCE here,
     * before the loop: the timestamp, the day label, the note, the phase and the photos. In
     * particular a single `System.currentTimeMillis()` is taken for all of them, so three
     * measurements made in one sitting sort as one moment rather than as three that happen
     * to be milliseconds apart.
     *
     * HOW THE PHOTOS ARE SHARED, and what "shared" costs. The SAME Model.Reading.Photo
     * objects — built once from Shot — are attached to every reading in the batch. They hold
     * a PATH, not pixels: there is exactly one JPEG per view on disk no matter how many
     * readings reference it, and no file is copied or re-encoded. What is duplicated is a
     * handful of strings and doubles per reading in model.json, which is the cheapest
     * linkage available without inventing a photo table and a reference count for a feature
     * whose whole scope is "these readings were taken at the same moment".
     *
     * The consequence to be aware of: deleting the photo from ONE of these readings (Gallery
     * clears the reference and unlinks the file) leaves the other readings in the batch
     * pointing at a path that is no longer there. Every reader in this app already treats a
     * missing file as "no photo" (Photos#decodeOriented returns null and each caller has a
     * no-photo rendering), so that degrades to a blank slot rather than to a crash — which
     * is the same thing that happens today if a file is removed from underneath any single
     * reading.
     */
    private final class SaveLogReadingTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            // M1 - asked NOW, at Save (the two minutes are counted until Save), and refused
            // if it moved on since the screen was drawn. At rest - never held, or vented
            // under it - it is the at-rest rows, under the methods the person chose.
            int holdState = a.captureHoldState();
            if (holdMovedOnSinceDrawn(holdState)) return;
            // M1 (safety review): nothing is filed while the vent is being confirmed.
            if (!HoldWindow.mayFile(holdState)) {
                a.toast("Confirming the vent \u2014 save once the pump reports the pressure falling");
                return;
            }
            if (HoldWindow.atRest(holdState)) { saveAtRestRows(); return; }
            // The held capture's two numbers, entered (unset only if a hold ended earlier in
            // this visit, and this one then began).
            if (Double.isNaN(a.measLen) || Double.isNaN(a.measGir)) {
                a.toast("Enter both numbers first");
                return;
            }
            // TASK 4: reject a future date at the one commit point, rather than at the
            // picker (setMaxDate constrains the DAY there, never the time-of-day — see
            // PickLogDate's own comment). Refuse, don't silently snap to now: a silent
            // correction would be the app inventing a measurement, the exact bug class
            // this screen's own doc rails against for a stepper value.
            if (a.logReadingTs > System.currentTimeMillis()) {
                a.toast("That date is in the future. A measurement is dated when it was taken.");
                return;
            }
            // The hold "ran" is the measured dwell, never which button was tapped (defect
            // #14), inside the two minutes as they stand at this tap.
            boolean standardised = HoldWindow.standardised(holdState);
            Model.Reading reading = new Model.Reading();
            // A4 - stamped at the moment it is taken, from the cylinder that is active NOW.
            // Never back-filled onto an older reading: which cylinder that one used is not
            // knowable, and -1 says so.
            reading.cylinder = a.model.activeCylinderIndex();
            reading.cylinderId = a.model.activeCylinderId();
            reading.id = a.measReadingId != null ? a.measReadingId : "m" + System.currentTimeMillis();
            reading.ts = a.logReadingTs;
            reading.label = a.dayLabel(reading.ts);
            reading.len = a.measLen; reading.gir = a.measGir;
            reading.note = a.measNote;
            if (standardised) {
                reading.holdKpa = Double.valueOf(a.model.std.kpa);
                reading.holdSec = Integer.valueOf(a.model.std.sec);
                // The pressure the pump ACTUALLY reported, not the commanded setpoint - at
                // the end of the count, at Save only as the fallback (StdMoment).
                a.fileObservedKpa(reading);
                reading.state = Model.Reading.STATE_UNKNOWN;   // a held reading carries no state
            } else {
                // Held, but outside the count or the two minutes: pressure was up, so this is
                // not an at-rest reading and carries no derived state.
                reading.holdKpa = null; reading.holdSec = null; reading.observedKpa = null;
                reading.state = Model.Reading.STATE_UNKNOWN;
            }
            // Both held branches file STANDARDIZED (no legacy sentinel; holdKpa still
            // records the pressure when the hold actually ran).
            reading.method = Model.Reading.METHOD_STANDARDIZED;
            reading.phase = a.logReadingPhase;
            attachPhotos(reading, buildSharedPhotos(reading.ts));
            reading.edited = false;
            // C-F1: "Measure again" files the re-measure with the day's length session.
            if (a.logRemeasure) Meas.linkRemeasure(a.model, reading);
            // A POST reading does not reset the measurement cadence (null cfg): "when is
            // the next reading due" is a question about cold baselines, and a post reading
            // is not one. Every other reading logs through the cadence as it always has.
            a.model.measLog.log(reading,
                reading.phase == Model.Reading.PHASE_POST ? null : a.model.meas);
            Store.save(a, a.model);
            // Every exit from a commanded-pressure state vents — through the shared vent
            // machinery, not a fork (matches CancelBaselineTap / onHoldLinkLost).
            a.ventIfHeld("StopWork (standalone reading logged)");
            // STUDY-19 R7: what the reading is, never "flagged".
            Ui.snack(a, a.rootFrame, (standardised ? "Reading logged \u00b7 standardised"
                               : HoldWindow.keptSnack(false))
                               + a.trendSnackSuffix());
            returnFromLogReading();
        }
    }

    /** The at-rest save: N filled rows become N readings sharing one moment and one set of
     *  photos. Refuses an entirely empty entry rather than filing a reading of nothing. */
    private void saveAtRestRows() {
        List<Model.Reading> batch = fileAtRestRows();
        if (batch == null) return;
        Ui.snack(a, a.rootFrame, (batch.size() == 1 ? "Reading logged"
                                : batch.size() + " readings logged — same moment, same photos")
                                + a.trendSnackSuffix());
        returnFromLogReading();
    }

    /** Files the at-rest rows and their photos - the whole of the at-rest save except where
     *  it goes next - and returns the readings filed, or null when it refused (with a toast
     *  saying why). Shared by Save and by "Save at rest, then standardise" (point 19), so
     *  the at-rest reading saved on the way into a hold is exactly the one Save would file. */
    private List<Model.Reading> fileAtRestRows() {
        if (Meas.filledLogRows(a.logRowVals) == 0) {
            a.toast("Fill in at least one measurement first");
            return null;
        }
        // TASK 4: same refusal as the held path (SaveLogReadingTap) — see its comment.
        if (a.logReadingTs > System.currentTimeMillis()) {
            a.toast("That date is in the future. A measurement is dated when it was taken.");
            return null;
        }
        long ts = a.logReadingTs;                                // ONE moment for all of them
        List<Model.Reading> batch = Meas.buildLogReadings(
            a.logRowVals, ts, a.dayLabel(ts),
            a.measReadingId != null ? a.measReadingId : "m" + ts,
            a.measNote, a.logReadingPhase);
        Model.Reading.Photo[] shared = buildSharedPhotos(ts);  // built ONCE, attached to each
        for (int i = 0; i < batch.size(); i++) {
            Model.Reading r = batch.get(i);
            // A4 - THE FOURTH DOOR. Meas#buildLogReadings is pure and has no rack to ask, so
            // this batch used to reach the log carrying no cylinder at all: provenance
            // depended on which logging screen the user came through. Stamped here, from the
            // same two accessors the other three doors use, so it cannot.
            r.cylinder   = a.model.activeCylinderIndex();
            r.cylinderId = a.model.activeCylinderId();
            attachPhotos(r, shared);
            // C-F1: "Measure again" files the re-measure with the day's length session.
            if (a.logRemeasure) Meas.linkRemeasure(a.model, r);
            a.model.measLog.log(r,
                r.phase == Model.Reading.PHASE_POST ? null : a.model.meas);
        }
        Store.save(a, a.model);
        return batch;
    }

    /** This sitting's photos, one entry per {@link Shot#VIEWS} slot (null where nothing was
     *  captured). Built ONCE per save so every reading in a batch references the identical
     *  records — and, more to the point, the identical FILES. */
    private Model.Reading.Photo[] buildSharedPhotos(long ts) {
        Model.Reading.Photo[] out = new Model.Reading.Photo[Shot.VIEWS.length];
        for (int i = 0; i < Shot.VIEWS.length; i++)
            out[i] = a.shot.has(Shot.VIEWS[i]) ? a.photoFrom(Shot.VIEWS[i], ts) : null;
        return out;
    }

    /** Puts the shared photos onto one reading. `photo` is the ANY-view flag the rest of the
     *  app reads; photoTop is never written here — Top left the capture flow (Shot#TOP). */
    private void attachPhotos(Model.Reading r, Model.Reading.Photo[] shared) {
        boolean any = false;
        for (int i = 0; i < Shot.VIEWS.length && i < shared.length; i++) {
            if (shared[i] == null) continue;
            any = true;
            if (Shot.FRONT.equals(Shot.VIEWS[i]))     r.photoFront = shared[i];
            else if (Shot.SIDE.equals(Shot.VIEWS[i])) r.photoSide  = shared[i];
        }
        r.photo = any;
    }

    private final class CancelLogReadingTap implements View.OnClickListener {
        @Override public void onClick(View v) { cancelLogReading(); }
    }

    /** Abandon the standalone reading. If a hold was outstanding it is vented through the
     *  shared background watch (the pump is still holding real vacuum regardless of how the
     *  screen is left — defect #12's rule), and we land on Today so its pressure line says
     *  what happened, exactly like CancelBaselineTap. */
    private void cancelLogReading() {
        boolean held = a.session.heldKpa() != null;
        a.ventIfHeld("StopWork (standalone reading cancelled)");
        if (held) a.toast("Cancelled — venting the pump");
        returnFromLogReading();
    }

    /** Return to whichever tab opened the door — unless a hold was just vented, in which
     *  case land on Today (heldKpa clears only on telemetry, so it may still be set here;
     *  Today's pressure line is where the vent's progress is shown). */
    private void returnFromLogReading() {
        if (a.session.heldKpa() != null) { a.showHome(); return; }
        if (a.logReadingOrigin == Nav.PROGRESS) { a.openMeasHist(); return; }
        a.showHome();
    }

    /** Entry point: seeds the steppers from the baseline being compared against.
     *  Guarded by the caller (OpenMeasureAfterTap) against an empty log — there is
     *  nothing to show here without a "before" to diff against. */
    void showMeasureAfter() {
        seedAfter();
        beginAfter();
        renderMeasureAfter();
    }

    /** POINT 19 - the after reading reserved BEFORE its hold (a new id, an empty photo
     *  buffer), for the same reason as beginBaseline: SessionActivity's OpenMeasureAfterTap
     *  calls it before startStd("measure-after") (WiringCheck invariant 84). */
    void beginAfter() {
        // Reserve the id and clear the shot buffer so a photo taken on the standardized
        // after screen (Task: std-under-vacuum capture) lands under reading-<id>-<view>.jpg
        // and links to the after-reading filed below — the same reservation the baseline and
        // standalone doors make before any shutter fires. Privacy: the capture before this
        // one is discarded first, under its own id (SessionActivity#discardCapture).
        a.discardCapture();
        a.measReadingId = "a" + System.currentTimeMillis();
    }

    /** The after screen as a hold hands over to it: reserved before the hold (beginAfter),
     *  so its photos are kept; seeded when opened fresh, drawn as left when resumed after
     *  "Hold again" on it (M1). */
    void showMeasureAfterHold(boolean resume) {
        if (!resume) seedAfter();
        renderMeasureAfter();
    }

    /** The after steppers' starting numbers and method - never the id or the photos. */
    private void seedAfter() {
        Model.Reading before = a.model.measLog.latestPre();
        // Gated on measuredLength/measuredGirth (F1 fix): `before` may be an at-rest
        // reading whose method never asked for one of the two metrics (a BPSSL baseline
        // never measured girth), and `len`/`gir` stay primitive doubles at their 0.0
        // default for the metric it did not ask for — seeding the after-stepper from
        // that default would hand the user a fabricated "was 0.0 cm" starting point for
        // a metric nobody ever measured. Same fallback as `before == null`.
        // M1 (safety review): after a hold ended, measured again at rest (invariant 96).
        a.afterLen = a.holdEnded() ? Double.NaN
            : before != null && before.measuredLength() ? before.len : 15.0;
        a.afterGir = a.holdEnded() ? Double.NaN
            : before != null && before.measuredGirth() ? before.gir : 12.0;
        // E2 - AT REST, THE SAME METHOD AS THE BEFORE READING, until the person says
        // otherwise. It always started on BPEL, so a BPSSL baseline was met by a BPEL after
        // and the screen then said the two could not be compared - over a choice nobody made.
        a.afterMethod = before != null && Model.Reading.methodIsLength(before.method)
            ? before.method : Model.Reading.METHOD_BPEL;
        // afterReadingTaken is NOT reset here: it belongs to the filed record, and a
        // second visit to this screen that is then skipped must leave the summary's
        // caption describing the after-reading that was actually filed.
    }

    /**
     * After-session screen, per proto/pump-console.html's #v-measure-after /
     * renderAfter(). The delta is DERIVED from the real baseline (model.measLog's
     * newest reading), never a fixed string, and printed only when
     * Model.Reading.comparable() stands behind the pair (prototype defect #11).
     *
     * E2 - IT TELLS YOU AS MUCH AS THE BEFORE SCREEN. This is where the comparison matters
     * most, and it said least: no line saying how the reading is being taken (the before
     * screen has one), photo slots only under a hold, and "Skip" at the same weight as
     * "Save and finish". Now:
     *   - the chip says what this reading is (standardised, at rest by which method, or held
     *     without the count) and what it is compared with - Say#afterChip, the same words
     *     for every case, none of them ranking one kind below the other;
     *   - the change is a small card of plain values, "Length since before", in the text
     *     colour: a body measurement moving is not a pass or a fail, and green is the
     *     confirmed vent's colour;
     *   - POV and Side are offered at rest too - an at-rest reading is a reading, not a
     *     quick number (STUDY-19 B1);
     *   - Skip is a text button under the one primary.
     */
    void renderMeasureAfter() {
        Model.Reading before = a.model.measLog.latestPre();
        a.body.removeAllViews();
        // The post-summary measurement epilogue: still commands a standardisation hold, so
        // the bar stays absent; no session step number applies (-1 hides the indicator).
        a.enterFlow(Nav.SCR_MEASURE_AFTER, -1);
        Ui.head(a, a.body, "Measurements  \u00b7  after");
        a.heldHeader(a.body);
        // M1 - the hold this is taken under, as it stands now (counted until Save).
        int holdState = a.captureHoldState();
        a.captureDrawn(holdState);
        if (before == null) {
            // G-Measure (B): a clear note and Done - no new route for a reading that could
            // not be compared or trended.
            Ui.note(a, a.body, "No before reading yet, so there's nothing to compare. Log one "
                + "before your next session to see the change.");
            Button back = Ui.big(a, a.body, "Done", Ui.SURFHI);
            back.setOnClickListener(a.new SkipAfterTap());
            return;
        }
        SessionActivity.AfterDelta d = a.afterDelta();
        boolean comparable = d != null && d.comparable;

        // WHAT THIS READING IS, first - the before screen's chip, answered for this side
        // (Say#afterChip, item 20). M1: past the two minutes or short of the count, the chip
        // is the hold's own, with Hold again; after the pump vented under this screen (its
        // limit, the notification) it says so and what the reading now is; inside the two
        // minutes they run live beneath it.
        if (holdState == HoldWindow.LAPSED || holdState == HoldWindow.SHORT
                || holdState == HoldWindow.VENTING
                || (HoldWindow.atRest(holdState) && a.holdEnded())) {
            holdChip(holdState);
        } else if (d != null) {
            String[] chip = Say.afterChip(before, d.after, comparable, a.model.std.sec);
            // G-Measure (B): BODY, never green - green is the confirmed vent's colour, and a
            // body figure is not a pass.
            Ui.chip(a, a.body, chip[0], chip[1], Ui.SURF,
                    Model.Reading.isStandardised(d.after) ? Ui.BODY : Ui.DIM);
            if (holdState == HoldWindow.OPEN) a.captureWindowLine(a.body);
        }
        // "Session complete" was hardcoded here once, but the summary offers this screen after
        // a STOPPED session too - so a run the user aborted was congratulated two taps later.
        // The drawn screen has no line for either; a stopped session keeps its one fact.
        if (a.sessionAborted)
            Ui.note(a, a.body, "Session stopped \u00b7 this is recorded as an attempt, not a "
                + "completion.");

        // The baseline's AGE, on the screen where the comparison is actually being made.
        // "(was 15.2 cm)" with no date reads as this session's before-state whatever its
        // real age; agoText() has existed all along and was simply not used here.
        boolean baseIsThisSession = a.sessionBaselineTs > 0 && before.ts == a.sessionBaselineTs;
        String was = baseIsThisSession ? "" : ", " + a.agoText(before.ts);
        if (a.session.heldKpa() != null) a.addLiveReadout();
        // The after-reading is judged like any other reading: standardised (held, under the
        // vacuum the pump actually reports) or at rest. At rest the METHOD is asked (no
        // separate hard/soft chip - the method name encodes it); the state is derived from it
        // at save time. (M1: the drawn verdict, the one afterCandidate files - and drawn at
        // rest while the vent is being confirmed, when it cannot yet be saved.)
        boolean afterRest = HoldWindow.atRest(holdState) || holdState == HoldWindow.VENTING;
        if (afterRest) {
            atRestMethodRow(a.afterMethod, new View.OnClickListener[]{
                new AfterMethodTap(Model.Reading.METHOD_BPEL),
                new AfterMethodTap(Model.Reading.METHOD_BPSSL),
                new AfterMethodTap(Model.Reading.METHOD_BPSL),
                new AfterMethodTap(Model.Reading.METHOD_NBPEL),
                new AfterMethodTap(Model.Reading.METHOD_NBPSL) });
        }
        // Gated on measuredLength/measuredGirth (F1 fix): a "(was X)" caption is a claim
        // that X was actually measured on the before reading. An at-rest, single-metric
        // before (e.g. a BPSSL baseline) never measured girth, so `before.gir` there is
        // the primitive's unmeasured 0.0 default, not a real prior girth — printing
        // "Girth (was 0.0 cm)" would assert a measurement that was never taken.
        String lenLabel = before.measuredLength()
                ? "Length  (was " + Model.Fmt.len(before.len) + was + ")" : "Length";
        String girLabel = before.measuredGirth()
                ? "Girth  (was " + Model.Fmt.len(before.gir) + was + ")" : "Girth";
        // C8 - only the steppers the save will keep; the same decision afterCandidate files.
        // (By its own hold alone, as afterCandidate files it - not by "Hold before
        // measuring", which is about the baseline: WiringCheck invariant 71.)
        int filing = Meas.captureMethod(HoldWindow.standardised(holdState), afterRest,
                                        a.afterMethod);
        Ui.stepperRow(a, a.body, lenLabel,
                lenOrUnset(a.afterLen), new BumpAfter(0, -1), new BumpAfter(0, 1),
                a.new TypeTap(SessionActivity.TypedField.AFTER_LEN), "length");
        if (Model.Reading.methodMeasuresGirth(filing)) {
            Ui.stepperRow(a, a.body, girLabel,
                    lenOrUnset(a.afterGir), new BumpAfter(1, -1), new BumpAfter(1, 1),
                    a.new TypeTap(SessionActivity.TypedField.AFTER_GIR), "girth");
        }
        Ui.note(a, a.body, a.holdEnded() ? "Measure again at rest \u2014 nothing taken under "
            + "the hold is used. Tap a value to type it." : "Tap a value to type it.");
        if (!Model.Reading.methodMeasuresGirth(filing)) girthAtRestNote(filing);

        // THE CHANGE, as plain values. Per metric (F1 fix): d.len/d.gir are null, not 0.0,
        // when either end never measured that metric, so only what both ends measured is
        // shown. Not comparable: no card - the chip above has already said why.
        if (comparable && (d.len != null || d.gir != null)) {
            LinearLayout since = Ui.cardGroup(a, a.body, null, null, 0);
            if (d.len != null) Ui.kvRow(a, since, "Length since before",
                Model.Fmt.lenDelta(d.len.doubleValue()), Ui.TEXT, null);
            if (d.gir != null) Ui.kvRow(a, since, "Girth since before",
                Model.Fmt.lenDelta(d.gir.doubleValue()), Ui.TEXT, null);
            // The last row's hairline would be a line under nothing.
            if (since.getChildCount() > 0) since.removeViewAt(since.getChildCount() - 1);
        }

        // PHOTOS, at rest as under a hold (STUDY-19 B1). They were offered only under the
        // hold - "an at-rest after-reading is a quick number, not a photo session" - which is
        // the one place the app treated an at-rest reading as the lesser kind. The two view
        // names are the labels on the buttons directly below.
        Ui.fieldLabel(a, a.body, "Photos", "optional");
        addPhotoRow();

        if (baseIsThisSession)
            Ui.noteInfo(a, a.body, "Straight after a session this is mostly swelling, and it fades.",
            "After the session", "Straight after a session the change is mostly swelling, "
                + "and it settles within hours. The number that matters is the baseline trend "
                + "across weeks.");
        else if (!comparable)
            // No change is shown (the chip says why), so the line does not claim one.
            Ui.noteInfo(a, a.body,
                "No baseline this session, so “before” is your reading from "
                    + a.agoText(before.ts) + ".",
                "No baseline this session",
                "No baseline was logged for this session, so the before reading is your "
                + "reading from " + a.agoText(before.ts) + ". The number that matters is the "
                + "baseline trend across weeks.");
        else
            Ui.noteInfo(a, a.body,
                "No baseline this session; this is the change since your reading " + a.agoText(before.ts) + ".",
                "No baseline this session",
                "No baseline was logged for this session, so this is measured "
                + "against your reading from " + a.agoText(before.ts) + " — it is the change "
                + "since then, not the change this session produced. The number that matters "
                + "is the baseline trend across weeks either way.");

        Button save = Ui.big(a, a.body, "Save and finish", Ui.GOOD);
        save.setOnClickListener(a.new SaveAfterTap());
        if (!HoldWindow.mayFile(holdState)) waitsForVent(save);
        // ONE PRIMARY. Skip was a second full-width button of the same weight; it is the
        // quieter choice, drawn as a text button in the text colour under the primary.
        skipLink(a.new SkipAfterTap(), "Skip the after-measurement");
    }

    private final class BumpAfter implements View.OnClickListener {
        private final int field, dir;
        BumpAfter(int f, int d) { field = f; dir = d; }
        @Override public void onClick(View v) {
            int st = a.captureHoldState();
            int m = HoldWindow.atRest(st) || st == HoldWindow.VENTING ? a.afterMethod
                  : Model.Reading.METHOD_STANDARDIZED;
            if (field == 0) a.afterLen = Say.clamp1(Double.isNaN(a.afterLen)
                ? seedMetric(m, false) : a.afterLen + dir * 0.1, 5.0, 30.0);
            else            a.afterGir = Say.clamp1(Double.isNaN(a.afterGir)
                ? seedMetric(m, true) : a.afterGir + dir * 0.1, 5.0, 25.0);
            renderMeasureAfter();
        }
    }

    private final class AfterMethodTap implements View.OnClickListener {
        private final int method;
        AfterMethodTap(int m) { method = m; }
        @Override public void onClick(View v) { a.afterMethod = method; renderMeasureAfter(); }
    }

    void showMeasEdit(String id) {
        Model.Reading r = a.findReading(id);
        if (r == null) { a.toast("That reading is gone"); a.renderMeasHist(); return; }
        a.edId = id;
        a.edOriginal = r;
        a.edWorking = r.copy();
        renderMeasEdit();
    }

    /**
     * THE READING EDITOR (measurement polish, item 18) - it matches how readings are taken.
     *
     * HOW IT WAS TAKEN IS A FACT, SO IT IS READ-ONLY. The method, the hold and the photos
     * are a card of values at the top. The editor used to let "At a hold pressure" be
     * switched on afterwards (a reading taken at rest can never become a standardised one
     * that way - it has no pump reading behind it), and "Photo attached: no" flipped a flag
     * with no photo behind it. Both controls are gone; the facts they claimed to change are
     * stated instead.
     *
     * ONLY THE NUMBER THE METHOD MEASURED CAN BE EDITED. An MSEG reading is a girth, so it
     * shows a Girth stepper and no Length - the owner's choice for the measurement the method
     * did not take is "hide it". It used to show "LENGTH 0.00", and one tap on + made it a
     * 1.0 cm length nobody measured. The steppers are in the unit Settings chooses, over the
     * same ranges the capture screens use (5-30 length, 5-25 girth), and the value can be
     * typed, as it can everywhere else.
     */
    void renderMeasEdit() {
        Model.Reading m = a.edWorking;
        if (m == null || a.edOriginal == null) { a.renderMeasHist(); return; }
        a.body.removeAllViews();
        // STAGED editor: the header arrow is DISCARD (CancelMeasTap drops edWorking without
        // touching model.measLog — Back must never be a silent commit, the known bug class
        // this editor's staging exists to prevent). The commit stays a distinct primary
        // "Save" at the bottom (SaveMeasTap), so back and save remain two different acts.
        a.header(a.body, a.dayLabel(m.ts), new CancelMeasTap());

        LinearLayout facts = Ui.cardGroup(a, a.body, null, null, 0);
        Ui.kvRow(a, facts, "Method", Model.Reading.methodLabel(m.method), Ui.TEXT, null);
        Ui.kvRow(a, facts, "Taken", Say.takenWords(m), Ui.TEXT, null);
        if (Model.Reading.isStandardised(m))
            Ui.kvRow(a, facts, "Pump reported", m.observedKpa == null ? "not read"
                : Model.Fmt.p(m.observedKpa.doubleValue()), Ui.TEXT, null);
        Ui.kvRow(a, facts, "Photos", Say.photosWords(m), Ui.TEXT, null);
        if (a.edOriginal.edited)
            Ui.kvRow(a, facts, "Previously edited", "Yes", Ui.TEXT, null);
        // The last row's hairline would be a line under nothing.
        if (facts.getChildCount() > 0) facts.removeViewAt(facts.getChildCount() - 1);

        // DATE & TIME (Task 4) — the one field this editor excluded before: edWorking
        // already carried ts (Reading#copy()), but nothing let the user change it. The
        // header's title above re-renders live off edWorking.
        Ui.kvRow(a, a.body, "Date", a.dayLabel(m.ts), Ui.TEXT, new PickEdDate());
        Ui.kvRow(a, a.body, "Time", a.timeLabel(m.ts), Ui.TEXT, new PickEdTime());

        boolean len = Meas.editsLength(m), gir = Meas.editsGirth(m);
        if (len)
            Ui.stepperRow(a, a.body, "Length  (" + Say.lenRange(5.0, 30.0) + ")",
                Model.Fmt.len(m.len), new BumpEd(0, -1), new BumpEd(0, 1),
                a.new TypeTap(SessionActivity.TypedField.ED_LEN), "length");
        if (gir)
            Ui.stepperRow(a, a.body, "Girth  (" + Say.lenRange(5.0, 25.0) + ")",
                Model.Fmt.len(m.gir), new BumpEd(1, -1), new BumpEd(1, 1),
                a.new TypeTap(SessionActivity.TypedField.ED_GIR), "girth");
        Ui.note(a, a.body, "Tap a value to type it.");

        Button note = Ui.flat(a, a.body, noteLabel(m.note));
        note.setOnClickListener(new EditEdNoteTap());

        // G-Measure (B): Delete is the quiet danger button, and asks first ("…"); it stays
        // ABOVE Save - Save is the last button in every editor.
        Button del = Ui.danger(a, a.body, "Delete this reading", true);
        del.setOnClickListener(new DelMeasTap());

        // The commit, anchored at the bottom as this app's editors put their primary GOOD
        // action last. This is the ONLY place edWorking is written back (SaveMeasTap); the
        // header arrow above discards it. Two distinct acts, never merged.
        Button save = Ui.big(a, a.body, "Save", Ui.GOOD);
        save.setOnClickListener(new SaveMeasTap());
    }

    /** The editor's date/time control (Task 4) — same platform-dialog idiom as
     *  PickLogDate/PickLogTime on the log-a-reading screen (PD-6: no Ui.dress), reading
     *  and writing edWorking.ts directly rather than a separate screen-level field, the
     *  same way BumpEd touches edWorking's other fields. setMaxDate stops the
     *  day picker offering a future date; SaveMeasTap holds the belt-and-braces
     *  time-of-day clamp, same split as the log screen. */
    private final class PickEdDate implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.edWorking == null) return;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(a.edWorking.ts);
            android.app.DatePickerDialog d = Ui.datePicker(a, new EdDateSet(),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            d.getDatePicker().setMaxDate(System.currentTimeMillis());
            Ui.showPicker(a, d);
        }
    }

    private final class EdDateSet implements android.app.DatePickerDialog.OnDateSetListener {
        @Override public void onDateSet(android.widget.DatePicker view, int y, int m, int day) {
            if (a.edWorking == null) return;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(a.edWorking.ts);
            c.set(y, m, day);
            a.edWorking.ts = c.getTimeInMillis();
            renderMeasEdit();
        }
    }

    private final class PickEdTime implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.edWorking == null) return;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(a.edWorking.ts);
            Ui.showPicker(a, Ui.timePicker(a, new EdTimeSet(),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true));
        }
    }

    private final class EdTimeSet implements android.app.TimePickerDialog.OnTimeSetListener {
        @Override public void onTimeSet(android.widget.TimePicker tp, int h, int m) {
            if (a.edWorking == null) return;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(a.edWorking.ts);
            c.set(Calendar.HOUR_OF_DAY, ((h % 24) + 24) % 24);
            c.set(Calendar.MINUTE, ((m % 60) + 60) % 60);
            a.edWorking.ts = c.getTimeInMillis();
            renderMeasEdit();
        }
    }

    /** Steps the staged copy's length or girth, over the capture screens' own ranges
     *  (5-30 length, 5-25 girth) - the editor used 1-40, so one tap on + turned an unmeasured
     *  0.0 into a 1.0 cm reading. Only a metric {@link Meas#editsLength} / {@link
     *  Meas#editsGirth} shows is ever stepped: the one the reading's method measured
     *  (item 18; it used to be both, on every reading). */
    private final class BumpEd implements View.OnClickListener {
        private final int field, dir;
        BumpEd(int f, int d) { field = f; dir = d; }
        @Override public void onClick(View v) {
            if (a.edWorking == null) return;
            if (field == 0) a.edWorking.len = Say.clamp1(a.edWorking.len + dir * 0.1, 5.0, 30.0);
            else            a.edWorking.gir = Say.clamp1(a.edWorking.gir + dir * 0.1, 5.0, 25.0);
            renderMeasEdit();
        }
    }

    private final class EditEdNoteTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.edWorking == null) return;
            EditText input = new EditText(a);
            input.setHint("note for this reading");
            input.setText(a.edWorking.note);
            input.setSelection(input.getText().length());
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Note")
                .setView(input)
                .setPositiveButton("Save", new EdNoteConfirm(input))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class EdNoteConfirm implements DialogInterface.OnClickListener {
        private final EditText input;
        EdNoteConfirm(EditText e) { input = e; }
        @Override public void onClick(DialogInterface d, int w) {
            if (a.edWorking == null) return;
            a.edWorking.note = input.getText().toString().trim();
            renderMeasEdit();
        }
    }

    /** Back/Discard: drop the staged copy without touching model.measLog at all — this is
     *  the whole point of staging (bug class: edits must be staged, not written live). */
    private final class CancelMeasTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.edWorking = null; a.edOriginal = null; a.edId = null;
            Ui.snack(a, a.rootFrame, "Change discarded");
            a.renderMeasHist();
        }
    }

    /** Save: commit the staged copy's editable fields onto the real reading, and mark
     *  `edited` ONLY when Meas.changed() finds an actual difference — a re-save of
     *  unmodified values must not manufacture an edit history the reading never had.
     *
     *  INV-A (Meas.changed()'s doc): every field that predicate compares is committed
     *  here, including `observedKpa`/`state` (no editor handler writes either today, so
     *  both commits are no-ops in practice, but omitting them would let a future change
     *  that made either editable silently reopen "Save reports changed, persists
     *  nothing") and — Task 4 — `ts`/`label`. Two things a `ts` commit specifically
     *  requires beyond the plain field-for-field copy every other row here is:
     *
     *  1. RE-SORT (PD-3). A `ts` edit can move this reading anywhere in the newest-first
     *     order ~25 sites across the app trust; {@link Model.MeasLog#resort} restores it,
     *     same call {@link Model.MeasLog#log}/{@link Model.MeasLog#fromJson} make.
     *
     *  2. NO `afterBaseTs` MIGRATION HERE (PD-5). A `ts` edit does NOT need to walk
     *     model.sessLog rewriting old-ts references to new-ts — that was the originally-
     *     considered fix and it is WRONG: a multi-protocol sitting files N readings
     *     sharing one identical ts, so a ts-keyed rewrite cannot tell which sibling a
     *     session actually meant and can re-point it at the wrong one. Instead,
     *     {@link Model.Sess#afterBaseId} — written once at attachAssessment() time and
     *     read by {@link #resolveAfterBase} — already identifies the reading by `id`,
     *     which this method never touches. There is nothing to migrate: the link was
     *     never keyed on the field that just changed. */
    private final class SaveMeasTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.edWorking == null || a.edOriginal == null) { a.renderMeasHist(); return; }
            // Same refusal, same reasoning, as the log-a-reading screen's Save (see
            // SaveLogReadingTap) — checked first, so a rejected date leaves the staged
            // edit exactly as the user left it rather than partially committing.
            if (a.edWorking.ts > System.currentTimeMillis()) {
                a.toast("That date is in the future. A measurement is dated when it was taken.");
                return;
            }
            boolean changed = Meas.changed(a.edOriginal, a.edWorking);
            // I10: a ts edit that moves a PRE or POST-phased reading onto a DIFFERENT day
            // can change which post-vs-pre percentage it pairs into (Meas#prePostPct
            // pairs by day; phase itself is not editable here, so ts is the only lever
            // this editor gives that can move a reading between day-buckets). Not
            // blocked — a genuine date correction legitimately can and should move a
            // reading's day bucket — but surfaced in the Save snack below rather than
            // left silent, computed BEFORE ts is overwritten.
            boolean crossedDay = a.edWorking.ts != a.edOriginal.ts
                && (a.edOriginal.phase == Model.Reading.PHASE_PRE
                    || a.edOriginal.phase == Model.Reading.PHASE_POST)
                && PhotoCalendar.dayKey(a.edWorking.ts) != PhotoCalendar.dayKey(a.edOriginal.ts);
            a.edOriginal.len = a.edWorking.len;
            a.edOriginal.gir = a.edWorking.gir;
            a.edOriginal.note = a.edWorking.note;
            a.edOriginal.holdKpa = a.edWorking.holdKpa;
            a.edOriginal.holdSec = a.edWorking.holdSec;
            a.edOriginal.observedKpa = a.edWorking.observedKpa;
            a.edOriginal.observedAt = a.edWorking.observedAt;   // travels with its value
            a.edOriginal.state = a.edWorking.state;
            a.edOriginal.photo = a.edWorking.photo;
            a.edOriginal.ts = a.edWorking.ts;
            a.edOriginal.label = a.dayLabel(a.edWorking.ts);
            // photoFront/photoSide/photoTop are SHARED BY REFERENCE with edWorking
            // (Reading#copy()'s own doc — the editor never changes which files a reading
            // points to), so their own `ts` is touched ONLY here, at commit, never during
            // picking: PickEdDate/PickEdTime's onDateSet/onTimeSet write edWorking.ts, a
            // primitive long independent of edOriginal's, so the staged-edit rule (never
            // write the live object before Save) holds until this line. Propagated
            // because a photo in this app is only ever captured at the SAME moment as
            // the reading it is attached to (buildSharedPhotos/photoFrom share one `ts`
            // at creation) — the two were never independently-tracked instants, so
            // correcting the reading's date without its photos would newly split one
            // fact into two disagreeing ones (Gallery.itemsOf reads photo.ts in
            // preference to reading.ts).
            if (a.edOriginal.photoFront != null) a.edOriginal.photoFront.ts = a.edWorking.ts;
            if (a.edOriginal.photoSide  != null) a.edOriginal.photoSide.ts  = a.edWorking.ts;
            if (a.edOriginal.photoTop   != null) a.edOriginal.photoTop.ts   = a.edWorking.ts;
            if (changed) a.edOriginal.edited = true;
            a.model.measLog.resort();
            a.edWorking = null; a.edOriginal = null; a.edId = null;
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, (changed ? "Saved and marked edited" : "No change")
                + (crossedDay ? " — moved to a different day, may change its post-vs-pre pairing" : ""));
            a.renderMeasHist();
        }
    }

    private final class DelMeasTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            android.app.AlertDialog d = Ui.dialog(a)
                .setTitle("Delete this reading?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete", new DelMeasConfirm())
                .setNegativeButton("Cancel", null)
                .show();
            Ui.dress(a, d);
            Ui.dangerPositive(d);
        }
    }

    private final class DelMeasConfirm implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            String id = a.edId;
            int photosGone = 0;
            for (int i = 0; i < a.model.measLog.all.size(); i++)
                if (a.model.measLog.all.get(i).id.equals(id)) {
                    // The FILES go with the row. Removing the Reading alone left both
                    // JPEGs on disk with nothing referencing them: invisible to the user,
                    // counted by nothing, and only ever reachable through Settings' global
                    // "Erase photos" sweep. A body photo the user believes they deleted
                    // must actually be deleted. Done BEFORE the row is removed, because
                    // the row is the only thing that knows the paths.
                    // E3: a photo this reading SHARES with others from the same sitting stays
                    // with them - the row goes, and the file leaves the phone only when no
                    // reading uses it any more (Gallery#pathInUse, asked after the removal).
                    Model.Reading gone = a.model.measLog.all.remove(i);
                    Model.Reading.Photo[] shots = { gone.photoFront, gone.photoSide, gone.photoTop };
                    for (int k = 0; k < shots.length; k++)
                        if (shots[k] != null && !Gallery.pathInUse(a.model.measLog, shots[k].path)
                                && a.deletePhotoFile(shots[k].path)) photosGone++;
                    break;
                }
            a.edWorking = null; a.edOriginal = null; a.edId = null;
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, photosGone == 0 ? "Reading deleted"
                : "Reading deleted · " + photosGone + (photosGone == 1 ? " photo" : " photos"));
            a.renderMeasHist();
        }
    }
}
