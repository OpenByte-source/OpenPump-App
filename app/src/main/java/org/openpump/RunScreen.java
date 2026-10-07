package org.openpump;

import android.widget.ScrollView;
import android.widget.SeekBar;
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

/** THE RUN SCREEN - the live drawing and the controls. The engine stays on SessionActivity.
 *  Lifted out of SessionActivity verbatim - see
 *  docs/superpowers/plans/2026-09-07-sessionactivity-split.md. A screen class
 *  renders into the Activity's own body column and owns no state. */
final class RunScreen {
    private final SessionActivity a;

    RunScreen(SessionActivity a) { this.a = a; }

    /**
     * THE LINE UNDER THE NOW BLOCK, once the oedema advisory has fired.
     *
     * Not a sheet, not a stop, not a gate - the spec's own words are "no sheet, no stop", and
     * this is the soft end of the scale. It sits in the run screen's own column under the
     * instrument it is about, and it stays there for the rest of the run rather than
     * scrolling past in three seconds.
     */
    private void edemaLine(LinearLayout host) {
        if (!a.edemaAdvised) return;
        Ui.note(a, host, Model.Fmt.t(Plan.EDEMA_ADVISORY_SEC) + " sealed. Past about here, "
            + "swelling starts to be what you are adding rather than expansion \u2014 worth "
            + "knowing, not a reason to stop.");
    }

    /**
     * THE RUN SCREEN, as a gym/boxing timer.
     *
     * What a person needs while a routine is playing, in the order they need it: how long
     * is left in THIS phase, what phase it is, what comes next, and — one step down — what
     * the cuff is actually reading against what was commanded. That is the order this
     * screen is now built in.
     *
     * THE COUNTDOWN IS THE HERO. It is the biggest thing on the screen, MONOSPACE so the
     * digits do not shuffle sideways as they tick, and it comes from the wall clock
     * (`presetFireAt - now`, via the existing tickRun) — never from counting ticks, which
     * drifts the moment a tick is late. It is NOT teal: teal means a measured value from
     * the device, and a countdown is neither measured nor a pressure. And, like the live
     * pressure, it is never animated.
     *
     * THE LIVE PRESSURE STAYS, one rank down: still teal, still MONOSPACE, still the
     * unanimated instrument readout, now beside the commanded figure so measured-versus-
     * commanded is one glance rather than two.
     *
     * NOTHING HERE ARMS THE PUMP. Skip and +30 s reuse the sequencing that was already
     * here — Advance/pendingAdvance/presetFireAt and playPreset(), which goes through
     * sendStartSlot() — and the STOP button, the give-up path and the vent wiring are
     * untouched.
     */
    void showRun(Model.Routine r) {
        a.body.removeAllViews();
        a.enterFlow(Nav.SCR_RUN, Nav.STEP_RUN);

        // RUN is the one screen whose bottom control bar must never scroll out of
        // reach — it holds STOP, the control that vents the pump. Every other screen
        // shares the app's single scrolling `body` column; this screen instead builds
        // its own scrolling content column PLUS a pinned, non-scrolling footer, both
        // added to `body` as ONE child (`runScreen`), so leaving Run — which always
        // goes through the existing `body.removeAllViews()` at the top of every
        // showXxx() — tears the whole thing down with no extra cleanup path to invent
        // or maintain.
        //
        // Plain MATCH_PARENT/weight on that child is not sufficient by itself, though:
        // `body` is itself the scrolling child of `bodyScroll`, and a ScrollView
        // ALWAYS measures its child's height as UNSPECIFIED — that is exactly what
        // lets an ordinary screen's content grow taller than the display and still
        // scroll — and UNSPECIFIED propagates to any MATCH_PARENT/weighted descendant
        // (ViewGroup.getChildMeasureSpec never upgrades an UNSPECIFIED parent mode to
        // EXACTLY for a MATCH_PARENT child). Left as plain MATCH_PARENT, `runScreen`
        // would simply wrap to the FULL height of its own content — footer included —
        // instead of clipping to the visible frame, so the "pinned" footer would still
        // ride along at the bottom of that oversized column and scroll away with
        // everything else: the exact defect this screen exists to fix, just rebuilt
        // one level deeper. RunScreenHeightPin below closes that gap once real
        // geometry exists: it stamps `bodyScroll`'s actual pixel height onto
        // `runScreen` as an EXPLICIT height, which always measures EXACTLY regardless
        // of an ancestor's UNSPECIFIED mode — the one thing that makes the weight-1
        // ScrollView above the footer genuinely clip instead of wrapping.
        LinearLayout runScreen = new LinearLayout(a);
        runScreen.setOrientation(LinearLayout.VERTICAL);

        ScrollView runScroll = new ScrollView(a);
        // Same reason bodyScroll/body clear both flags, below: a card's shadow is
        // drawn OUTSIDE its bounds (Ui.lift), and a scroll container built by hand
        // defaults to clipping to its padding, which crops every card's shadow at the
        // top, the bottom and both edges. Ui.col already clears both on `runContent`;
        // this ScrollView is built by hand and has to be told the same thing.
        runScroll.setClipToPadding(false);
        runScroll.setClipChildren(false);
        LinearLayout runContent = Ui.col(a);
        // THE SCREEN RUNS EDGE TO EDGE (polish item 17) so the control bar and its rule can:
        // runScreen gives back the page's side gutter (negative margins below) and the
        // scrolling content takes it again here, so every card sits exactly where it did.
        int gutL = a.body.getPaddingLeft(), gutR = a.body.getPaddingRight();
        runContent.setPadding(gutL, 0, gutR, Ui.dp(a, Look.S3));
        runScroll.addView(runContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        runScreen.addView(runScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        a.runFooter = new LinearLayout(a);
        a.runFooter.setOrientation(LinearLayout.VERTICAL);
        // Same rule as runScroll/runContent, and for the same reason: STOP is Ui.lift'd
        // (Ui.big gives it a real shadow), which draws OUTSIDE its bounds, and a
        // LinearLayout defaults to clipping to its own padding box — which would shave
        // STOP's shadow off at the footer's own padding edge otherwise.
        a.runFooter.setClipToPadding(false);
        a.runFooter.setClipChildren(false);
        a.runFooter.addOnLayoutChangeListener(new SnackFollowsFooter());
        /* THE BAR IS THE PAGE'S GROUND, UNDER A 1 dp RULE (polish items 8 and 17). It was a
         * SURFACE panel butted against the scrolling cards, so the card cut off at the top
         * edge of the bar looked as if the bar floated over it. It is its own region: the
         * scroll above ends where the rule begins, and everything in the scroll reaches
         * the rule by scrolling - nothing is ever behind the bar. */
        android.graphics.drawable.LayerDrawable barBg = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[]{
                new android.graphics.drawable.ColorDrawable(Ui.BG),
                new android.graphics.drawable.ColorDrawable(Ui.LINE) });
        barBg.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        barBg.setLayerHeight(1, Math.max(1, Ui.dp(a, 1)));
        a.runFooter.setBackground(barBg);
        a.runFooter.setPadding(gutL, Ui.dp(a, 11), gutR, Ui.dp(a, 2));
        runScreen.addView(a.runFooter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams screenLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        screenLp.leftMargin = -gutL;
        screenLp.rightMargin = -gutR;
        a.body.addView(runScreen, screenLp);
        // See RunScreenHeightPin: without this, `runScreen`'s MATCH_PARENT height is
        // inert (body sits inside bodyScroll, a ScrollView) and the split above would
        // silently fail to pin anything.
        //
        // STAMPED SYNCHRONOUSLY FIRST, when the answer is already known — true on every
        // showRun() rebuild after the very first, since Hold/Skip/+30s/Rest/Adjust all
        // funnel through redrawRunIfStillRunning() -> showRun() again, and bodyScroll was
        // already laid out by the run screen's own first entry. Without this, EVERY one
        // of those taps would draw one real frame with `runScreen` unpinned before
        // RunScreenHeightPin's listener got a chance to fire (onGlobalLayout runs after
        // layout but before draw, so its own requestLayout() only takes effect on the
        // NEXT traversal, one frame late).
        int knownH = a.bodyScroll.getHeight() - a.body.getPaddingTop() - a.body.getPaddingBottom();
        if (knownH > 0) {
            ViewGroup.LayoutParams lp0 = runScreen.getLayoutParams();
            lp0.height = knownH;
            runScreen.setLayoutParams(lp0);
        }
        // THEN kept tracking for as long as this runScreen instance stays on screen — not
        // a one-shot. AndroidManifest.xml's <activity> absorbs orientation/screenSize/
        // smallestScreenSize/screenLayout in configChanges (split-screen, multi-window and
        // foldable resizes do not recreate the Activity and do not re-run showRun()), so a
        // pin that fired once and unregistered would leave `runScreen` at a STALE pixel
        // height after such a resize while `bodyScroll` had genuinely changed size — the
        // outer scroll would regain range and `runFooter`/STOP would go below the fold
        // again, mid-run, with nothing left to re-stamp it. Registered on bodyScroll's OWN
        // observer, not runScreen's — see RunScreenHeightPin's doc for why that is the
        // stable one, and how it still avoids outliving this one Run visit.
        a.bodyScroll.getViewTreeObserver().addOnGlobalLayoutListener(
                new SessionActivity.RunScreenHeightPin(runScreen, a.bodyScroll, a.body));

        /* ---- THE TOP (0.10 run-screen redesign, owner-approved) -------------------------
         * The live STAGE BAR (the Today card's bar, filling as the run plays), the STATUS
         * LINE (what is happening and what is next; elapsed / planned on its right), and the
         * NOW card, which is now the timer. They share one region so the colour setting's
         * soft or strong tint (Settings › Run colours) can wash over exactly them. There is
         * no big hero timer any more: the time left is said in the NOW card and, in a rest,
         * in the chart's rest band - nowhere else. */
        a.runTop = Ui.col(a);
        a.runTop.setPadding(0, Ui.dp(a, 8), 0, 0);
        runContent.addView(a.runTop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        a.runStageBar = new StageBarView(a);
        a.runTop.addView(a.runStageBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 12)));
        // The bar's words: the stages' names, a tick for the ones done - never a time left.
        a.runBarLabels = new TextView(a);
        a.runBarLabels.setTextColor(Ui.DIM);
        a.runBarLabels.setTextSize(Look.SP_FIELD_LABEL);
        a.runBarLabels.setPadding(0, Ui.dp(a, 5), 0, 0);
        a.runTop.addView(a.runBarLabels);

        /* THE STATUS LINE. Coloured by the step playing when "Colour the run by step" is on
         * (RunLook; STOP's red is never among its colours), white under a Hold whatever the
         * setting, and ALWAYS saying in words what the colour says. */
        a.runStatus = new LinearLayout(a);
        a.runStatus.setOrientation(LinearLayout.HORIZONTAL);
        a.runStatus.setGravity(Gravity.CENTER_VERTICAL);
        a.runStatus.setMinimumHeight(Ui.dp(a, 34));
        a.runStatus.setPadding(Ui.dp(a, 12), Ui.dp(a, 6), Ui.dp(a, 12), Ui.dp(a, 6));
        a.runStatus.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_PILL));
        a.runStatusL = new TextView(a);
        a.runStatusL.setTextSize(Look.SP_FIELD_LABEL);
        a.runStatusL.setLetterSpacing(0.06f);
        // ONE LINE, always (device check): the paused line is said short when it does not fit
        // (paintRunTop), and cut with an ellipsis only as a last resort.
        a.runStatusL.setSingleLine(true);
        a.runStatusL.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ui.semibold(a.runStatusL);
        a.runStatusL.setTextColor(Ui.TEXT);
        a.runStatus.addView(a.runStatusL, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        a.runStatusR = new TextView(a);
        a.runStatusR.setTypeface(android.graphics.Typeface.MONOSPACE);
        a.runStatusR.setTextSize(Look.SP_FIELD_LABEL);
        a.runStatusR.setSingleLine(true);
        a.runStatusR.setTextColor(Ui.TEXT);
        a.runStatusR.setPadding(Ui.dp(a, 8), 0, 0, 0);
        a.runStatus.addView(a.runStatusR);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = Ui.dp(a, 8);
        a.runTop.addView(a.runStatus, stLp);

        /* ---- NOW: the timer. The set playing, its time in the step's colour, and one line
         * of what comes next. MONOSPACE so the digits do not shuffle as they tick, off the
         * wall clock (presetFireAt - now, as it always was), and never animated. */
        LinearLayout nowTimer = Ui.col(a);
        nowTimer.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        nowTimer.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, 10), Ui.dp(a, Look.S5), Ui.dp(a, 10));
        Ui.lift(a, nowTimer, Ui.ELEV_CARD);
        LinearLayout.LayoutParams ntLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ntLp.topMargin = Ui.dp(a, 8);
        ntLp.bottomMargin = Ui.dp(a, 8);
        a.runTop.addView(nowTimer, ntLp);
        a.nowName = new TextView(a);
        a.nowName.setTextColor(Ui.TEXT);
        a.nowName.setTextSize(Look.SP_CHIP);
        Ui.semibold(a.nowName);
        nowTimer.addView(a.nowName);
        a.runCountdown = new TextView(a);
        a.runCountdown.setTextColor(Ui.TEXT);
        a.runCountdown.setTypeface(android.graphics.Typeface.MONOSPACE);
        a.runCountdown.setTextSize(Look.SP_READOUT);
        a.runCountdown.setIncludeFontPadding(false);
        a.runCountdown.setLetterSpacing(-0.03f);
        a.runCountdown.setText("—");
        nowTimer.addView(a.runCountdown);
        a.runNext = new TextView(a);
        a.runNext.setTextColor(Ui.DIM);
        a.runNext.setTextSize(Look.SP_CHIP);
        a.runNext.setPadding(0, Ui.dp(a, 2), 0, 0);
        nowTimer.addView(a.runNext);
        // The routine and its stage, and whether the rest is vented yet: the quiet line it
        // always was, under the time rather than over a hero.
        a.nowKicker = new TextView(a);
        a.nowKicker.setTextColor(Ui.DIM);
        a.nowKicker.setTextSize(Look.SP_CAPTION);
        a.nowKicker.setPadding(0, Ui.dp(a, 2), 0, 0);
        // polish RN-3: the routine's name behind the line's info mark - one tap, 48 dp.
        a.nowKicker.setText("Routine ⓘ · starting");
        a.nowKicker.setMinHeight(Ui.dp(a, 48));
        a.nowKicker.setGravity(Gravity.CENTER_VERTICAL);
        kickerWhere = r.name;
        a.nowKicker.setOnClickListener(new KickerTap());
        a.nowKicker.setOnTouchListener(new Ui.Press());
        nowTimer.addView(a.nowKicker);

        /* ---- the stage rail ---------------------------------------------------- */
        a.runRail = new LinearLayout(a);
        a.runRail.setOrientation(LinearLayout.HORIZONTAL);
        a.runRail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        /* R1 - IT IS BUILT, AND IT IS NOT ADDED. paintStageRail writes to it on every tick
         * and a null there would be a crash on the one screen that must never crash; the
         * stage bar at the top says what it would. */
        a.runRail.setVisibility(View.GONE);
        LinearLayout.LayoutParams railLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        runContent.addView(a.runRail, railLp);

        /* ---- THE PRESSURE TRACE ------------------------------------------------ */
        // A plain SURFACE card like every other card on the screen (polish item 10): the
        // GROUND-dark box read as a hole in the page rather than as the instrument.
        LinearLayout ecgBox = Ui.col(a);
        ecgBox.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        ecgBox.setPadding(Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 10), Ui.dp(a, 8));
        Ui.lift(a, ecgBox, Ui.ELEV_CARD);

        /* THE ROUTINE STRIP IS BUILT AND NOT SHOWN (0.10). The stage bar at the top is the
         * routine's progress now, and its "elapsed / planned" is on the status line - the
         * strip and its figure are still painted every tick (paintRoutineStrip, WiringCheck
         * invariant 170 holds how the figure is made) and the status line reads that same
         * figure, so the two can never say different things. */
        LinearLayout stripHead = new LinearLayout(a);
        stripHead.setOrientation(LinearLayout.HORIZONTAL);
        stripHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView stripLbl = cardLabel(Trace.ROUTINE_LABEL);
        stripHead.addView(stripLbl, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        a.ecgElapsed = new TextView(a);
        a.ecgElapsed.setTextColor(Ui.DIM);
        a.ecgElapsed.setTypeface(android.graphics.Typeface.MONOSPACE);
        a.ecgElapsed.setTextSize(Look.SP_FIELD_LABEL);
        a.ecgElapsed.setText("");
        stripHead.addView(a.ecgElapsed);
        stripHead.setVisibility(View.GONE);
        ecgBox.addView(stripHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        a.routineStrip = a.new RoutineStrip(a);
        a.routineStrip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        a.routineStrip.setVisibility(View.GONE);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 4));
        ecgBox.addView(a.routineStrip, stripLp);

        /* THE LIVE READOUT, ONCE, ABOVE THE CHART (polish item 10). The pressure used to
         * appear twice - large in a caption floated over the top of the plot, and again in
         * small type on the trace beside the dashed commanded line. One readout, in the
         * label row above the plot, leaves the line itself clear; the commanded values are
         * the dashed lines, read against the axis. */
        LinearLayout ecgHead = new LinearLayout(a);
        ecgHead.setOrientation(LinearLayout.HORIZONTAL);
        ecgHead.setGravity(Gravity.CENTER_VERTICAL);
        ecgHead.setBaselineAligned(false);
        TextView ecgLbl = cardLabel("PRESSURE · LIVE");
        ecgHead.addView(ecgLbl, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // The live pressure figure. Amber, mono, never animated. bigP keeps its field name
        // because refreshLiveReadout already knows it; the number is 26 sp and its unit
        // 15 sp (readoutText), so the figure is what the eye lands on.
        a.bigP = new TextView(a);
        a.bigP.setTextColor(Ui.CMD);
        a.bigP.setTypeface(android.graphics.Typeface.MONOSPACE);
        a.bigP.setTextSize(READOUT_SP);
        a.bigP.setIncludeFontPadding(false);
        a.bigP.setText("—");
        ecgHead.addView(a.bigP);
        ecgBox.addView(ecgHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        a.ecg = a.new EcgTrace(a);
        a.ecg.setContentDescription("Live pressure over the last half minute, against the "
            + "commanded pressure. The current reading is stated above this trace, and a "
            + "break in the line is a moment the device reported no measurement. In a rest "
            + "the chart shows the whole rest and when the next pull comes.");
        // BIGGER (0.10): the room the hero timer took is the chart's now - about 300 dp for
        // the card, the plot most of it.
        LinearLayout.LayoutParams ecgPlotLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 236));
        ecgPlotLp.topMargin = Ui.dp(a, 4);
        ecgBox.addView(a.ecg, ecgPlotLp);

        /* ONE LINE UNDER THE CHART (polish item 10): the phase at the left, the key at the
         * right - two lines of shouted capitals before. */
        LinearLayout ecgFoot = new LinearLayout(a);
        ecgFoot.setOrientation(LinearLayout.HORIZONTAL);
        ecgFoot.setGravity(Gravity.CENTER_VERTICAL);
        a.subP = new TextView(a);
        // DIM, not FAINT (final review pass — the identical fix stageSub() already got in
        // Task 13's own review round). This carries live prose ("waiting for telemetry",
        // "HOLD · 0:12 of 0:20"), not a label/unit, at normal caption size — FAINT is
        // documented "labels and units ONLY" and SelfTest's own existing pin already proves
        // Look.meetsAA(Look.FAINT, Look.GROUND, false) is false.
        a.subP.setTextColor(Ui.DIM);
        a.subP.setTextSize(Look.SP_FIELD_LABEL);
        a.subP.setSingleLine(true);
        a.subP.setEllipsize(android.text.TextUtils.TruncateAt.END);
        a.subP.setText("waiting for telemetry");
        ecgFoot.addView(a.subP, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // THE LEGEND. Two lines share this plot, so the chart has to say which is which —
        // in the strokes themselves rather than in a colour key, because both are amber and
        // the difference is solid versus dashed. No units: the figures on this screen
        // already follow the display unit, and a literal one here would contradict them the
        // moment it is changed. Sentence case now, beside the phase.
        TextView ecgKey = new TextView(a);
        ecgKey.setText(Trace.KEY);
        ecgKey.setTextColor(Ui.DIM);
        ecgKey.setTextSize(Look.SP_FIELD_LABEL);
        ecgKey.setSingleLine(true);
        ecgKey.setPadding(Ui.dp(a, Look.S3), 0, 0, 0);
        ecgFoot.addView(ecgKey);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footLp.topMargin = Ui.dp(a, 4);
        ecgBox.addView(ecgFoot, footLp);

        LinearLayout.LayoutParams ecgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ecgLp.bottomMargin = Ui.dp(a, 6);
        runContent.addView(ecgBox, ecgLp);
        layScreen = runScreen; layScroll = runScroll; layContent = runContent; layEcg = ecgBox;
        layFit = null; layChartFirst = false;

        /* THE − / + STRIP (0.10 final): the one place the step playing is changed. Built once
         * here and only ever repainted (paintStrip), so a tick never takes a button out from
         * under a finger. WHERE it sits is the person's choice (Settings › On the run screen):
         * pinned in the footer above the buttons - the default - or here, in the page right
         * after the chart, which then keeps its full height. */
        boolean stripUnderChart = a.model.runStripWhere == Model.RUN_STRIP_UNDER_CHART;
        // NEVER SQUEEZED OFF THE SCREEN (owner report): once the pinned footer was found too
        // tall for this screen (StripFit), the strip stays under the chart from then on - it
        // does not jump back and forth as the step changes the footer's height.
        boolean stripSqueezed = !stripUnderChart && a.stripSqueezed;
        LinearLayout strip = buildStrip();
        if (stripUnderChart || stripSqueezed) {
            Ui.lift(a, strip, Ui.ELEV_CARD);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.bottomMargin = Ui.dp(a, 6);
            runContent.addView(strip, sLp);
        }

        /* THE STATUS LINE (polish item 11): an 8 dp dot and one plain sentence - "On
         * target", or how far short and of what (RunChip). The dot carries the colour and
         * the words carry the meaning, so neither is alone. One focus stop. */
        LinearLayout chipRow = new LinearLayout(a);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipRow.setMinimumHeight(Ui.dp(a, 40));
        chipRow.setPadding(Ui.dp(a, 2), 0, Ui.dp(a, 2), 0);
        a.runChipDot = new View(a);
        a.runChipDot.setBackground(dot(Ui.DIM));
        a.runChipDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                Ui.dp(a, 8), Ui.dp(a, 8));
        dotLp.rightMargin = Ui.dp(a, 10);
        chipRow.addView(a.runChipDot, dotLp);
        a.runChip = new TextView(a);
        a.runChip.setTextColor(Ui.TEXT);
        a.runChip.setTextSize(Look.SP_CHIP);
        chipRow.addView(a.runChip, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = Ui.dp(a, 4);
        chipLp.bottomMargin = Ui.dp(a, 6);
        runContent.addView(chipRow, chipLp);

        /* COMING STEPS (0.10): what is still to come, to skip or change for this run only.
         * A button of its own (the owner kept it rather than a tap on the bar), in the scroll
         * and never in the footer, so nothing moves STOP. */
        a.comingBtn = Ui.flat(a, runContent, "Coming steps · skip or change ›");
        a.comingBtn.setOnClickListener(new ComingStepsTap());
        a.comingBtn.setContentDescription("Coming steps. Skip a step still to come, or change "
            + "its sets, hold, drop, rest or ramp, for this run only. Pressure can't be raised "
            + "there.");

        /* ---- NOW ---------------------------------------------------------------
         * The set that is playing: its name, its waveform with the CURRENT step's band
         * lit, its four parameters as readable tiles, and — the point of this screen —
         * "adjust", which opens the live override. */
        /* A PLAIN SURFACE CARD (polish item 17). The amber edge and the lime ring said
         * "live" a third time on a screen whose countdown and trace already do; the title
         * row carries the set and its time left, and ONE caption line the position and
         * the whole run's end - the set's name was said twice before, once in the title
         * and again leading the caption. */
        LinearLayout now = Ui.col(a);
        now.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        now.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, Look.S5), Ui.dp(a, Look.S5),
                       Ui.dp(a, Look.S5));
        Ui.lift(a, now, Ui.ELEV_CARD);
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nowLp.bottomMargin = Ui.dp(a, 6);
        runContent.addView(now, nowLp);
        // The set's name and its time left moved up into the NOW card at the top (0.10): the
        // time left is said there and nowhere else, so this card starts with where the set
        // is and when the run ends - and, when the pump is not answering, says that first.
        a.nowTime = null;
        a.nowSub = new TextView(a);
        a.nowSub.setTextColor(Ui.DIM);
        a.nowSub.setTextSize(Look.SP_CHIP);
        now.addView(a.nowSub);
        // The whole-run figure is on the caption line now (wave 1 §3 still holds: it is the
        // same RunEdit#runEndsLine the +30 s toast quotes). No separate line.
        a.runEnds = null;

        /* NOTHING ON THIS CARD EDITS THE STEP PLAYING (0.10 final: one place per job). The
         * − / + strip does, and its More › opens the full sheet; the old value cells, the
         * scope switch, the speed bar and the "SET ›" row are gone from here. What stays is
         * the whole-routine offset (ROUTINE ›), which moves the steps still to come. The
         * cells' journal figures are kept in views of their own (journalRunScreen), in the
         * form the journal's watch compares with the wire. */
        a.cellPull = new TextView(a); a.cellPullUnit = new TextView(a);
        a.cellDrop = new TextView(a); a.cellDropUnit = new TextView(a);
        a.cellHold = new TextView(a); a.cellHoldUnit = new TextView(a);
        a.cellDropT = new TextView(a); a.cellDropTUnit = new TextView(a);

        // BOTH INLINE, in the scroll, under the instrument they belong to - neither is a
        // sheet and neither can cover STOP. See midRunCheckRow's own doc for why that is
        // not negotiable even on a vented rest.
        edemaLine(now);
        a.midRunCheckRow(now);
        a.targetMetCard(now);

        routineOffsetRow(now);

        // LIVE NET TUP (G4). Net TUP decides the plan's gates, and until now it could only
        // be read AFTER the session \u2014 so mid-run there was no way to know whether this
        // week's target had been met, which is exactly when knowing changes what you do.
        // One line, under the instrument and above the upcoming list: net so far, and the
        // target beside it. Deliberately not a second big number competing with pressure.
        a.nowNetRow = new TextView(a);
        a.nowNetRow.setTextColor(Ui.DIM);
        a.nowNetRow.setTextSize(Look.SP_CAPTION);
        a.nowNetRow.setPadding(Ui.dp(a, 2), Ui.dp(a, 4), 0, 0);
        a.nowNetRow.setText("");
        now.addView(a.nowNetRow);

        /* ---- THE BUTTONS FOLLOW THE STEP (0.10) — pinned, in runFooter, never in the scroll.
         * refreshTimerControls names them every tick (below). STOP is untouched: same place,
         * same red, same words, same confirmation path. */
        // PINNED (the default): the strip heads the footer, above the buttons, 8 dp apart.
        if (!stripUnderChart && !stripSqueezed) {
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.bottomMargin = Ui.dp(a, 8);
            a.runFooter.addView(strip, sLp);
            layFit = new StripFit(runScreen, a.runFooter, strip, runContent, ecgBox);
            runScreen.addOnLayoutChangeListener(layFit);
        }
        layStrip = strip;

        /* PAUSE IS ALWAYS THE FIRST BUTTON (0.10 final), in every step: Pause · Skip block ·
         * +30 s hold · Rest in work; Pause · End rest · +30 s rest in a rest (Pause greyed -
         * the cuff is vented); Pause · Skip step · +30 s step on a ramp; Pause · Skip warm-up
         * · +30 s in the warm-up. Pause is the old Hold, renamed: the pump keeps the pressure
         * it is reading and the clock waits (enterHold) - this device has no pause of its own,
         * only STOP vents. */
        LinearLayout ctl = new LinearLayout(a);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        // Every button top-aligned in its 48 dp (device check EMU9 M8: a two-line label sat
        // lower than the rest, on STOP's edge - a row aligns its children's baselines).
        ctl.setBaselineAligned(false);
        a.holdBtn = a.timerControl(ctl, "Pause", true, Look.CMD_DIM, Ui.CMD);
        a.holdBtn.setOnClickListener(new HoldTap());
        a.skipBtn = a.timerControl(ctl, SKIP_SETS, true, Ui.SURFHI, Ui.TEXT);
        a.skipBtn.setOnClickListener(new StepSkipTap());
        a.extendBtn = a.timerControl(ctl, "+30 s hold", true, Ui.SURFHI, Ui.TEXT);
        a.extendBtn.setOnClickListener(new StepExtendTap());
        // REST — plain surface, never amber: it is the one control on this row that takes
        // pressure OFF, and amber in this app means "this is commanded".
        a.restBtn = a.timerControl(ctl, "Rest", false, Ui.SURFHI, Ui.TEXT);
        a.restBtn.setOnClickListener(new RestNowTap());
        LinearLayout.LayoutParams ctlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        a.runFooter.addView(ctl, ctlLp);

        // Full width, the same red, the same words and the same confirmation path. The
        // label is near-black on the red (Look.ON_RED, AA - the white label was 3.4:1).
        Button stop = Ui.big(a, a.runFooter, "STOP · vent now", Ui.CRIT);
        stop.setTextColor(Look.ON_RED);
        stop.setOnClickListener(a.new Tap(SessionActivity.Tap.STOP));

        // polish RN-2: it asks first, then offers Resume as STOP does (EndSessionAskTap).
        Button endBtn = Ui.secondary(a, runContent, "End session…");
        endBtn.setOnClickListener(a.new EndSessionAskTap());

        LinearLayout helpRow = new LinearLayout(a);
        helpRow.setOrientation(LinearLayout.HORIZONTAL);
        helpRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView helpLbl = new TextView(a);
        helpLbl.setText("What these controls do");
        helpLbl.setTextColor(Ui.DIM);
        helpLbl.setTextSize(Look.SP_CAPTION);
        helpRow.addView(helpLbl, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // polish RN-12: one short paragraph per control, in sentence case, and the drop floor
        // in the unit the display is set to (it said "−3.0 inHg (10 kPa)" whatever the unit).
        Ui.infoButton(a, helpRow, "the live controls", "Adjusting a running routine",
            "Pause keeps the pump at the pressure it is reading and stops the clock; Resume "
          + "carries on where it was. This pump has no pause of its own, so a pause is a real "
          + "command to stay put — amber for that reason — and it vents at the same limit every "
          + "hold has. In a rest there is nothing to pause.\n\n"
          + "The − / + change the step playing, and only that: its pull, drop, hold and drop "
          + "time, sent when you stop tapping, the countdown carrying on. A ramp's step and the "
          + "warm-up also have their length; a rest has only its length. A drop never goes past "
          + Model.Fmt.p(RunEdit.DROP_FLOOR_KPA) + ", or past a step's own planned drop. Press and "
          + "hold to repeat; a key at its limit is dimmed, and a tap says which limit. More › "
          + "opens the full sheet.\n\n"
          + "On a ramp, + step adds a step at its top (the pump takes at most "
          + ComingSteps.RAMP_STEPS_MAX + "), and − step takes an added one out before it "
          + "starts.\n\n"
          + "Whole-routine offset moves the pull of every remaining work set — never the "
          + "warm-up, a rest, the fatigue block or the retention hold, never past the plan's own "
          + "step on a trainer routine, and not at all on a reduced day.\n\n"
          + "Rest vents the cuff and pauses the run for as long as you pick, 30 seconds to five "
          + "minutes, then puts you back on the step you were on, with the time it had left.\n\n"
          + "Skip ends the sets playing now (a ramp's step, or the warm-up); for a few seconds "
          + "after, the same button reads Undo skip and puts them back. +30 s adds half a minute to the hold, warm-up or rest "
          + "playing (on a ramp step, one more cycle) — a hold goes on from where it is, 4:15 at most.\n\n"
          + "Coming steps lists the steps to come: change a later block, rest or ramp, or skip "
          + "one. This run only, the pull never raised.\n\n"
          + "STOP vents at once. Where the − / + sit is in Settings › On the run screen.", true);
        runContent.addView(helpRow);

        // "Chart first on ramps": the screen is built in the usual order and, when a ramp's
        // step or the warm-up is what is playing, put in the ramp order before it is first
        // drawn - the same move the tick makes when the step changes kind.
        followRampLayout();
        startEcg();
        refreshRunScreen(a.session.elapsedMs(System.currentTimeMillis(), a.LINK_TIMEOUT_MS));
    }

    /* ---- "CHART FIRST ON RAMPS" (0.10, owner decision) -----------------------------------
     * Settings › On the run screen › Where the − / + controls sit has a third answer. While a
     * ramp's step or the warm-up plays the chart is the FIRST thing on the page and the strip
     * sits right under it; on every other step the page is in its usual order (the status
     * block and timer card first, the chart after them) and the strip is pinned above the
     * buttons. The layout changes when - and only when - the step playing changes kind, and it
     * changes by MOVING the views showRun built (never building them again): the strip keeps
     * its cells, its listeners and its repeat state, and nothing a finger is on is taken from
     * under it (a strip key that is held down defers the move to the next tick). Which order
     * is wanted is QuickAdjust#chartFirst / #stripInPage - pure, and pinned by
     * QuickAdjustTest. */
    private LinearLayout layScreen, layContent;
    private ScrollView layScroll;
    private View layEcg, layStrip;
    private StripFit layFit;
    /** The order the page is in now: true = chart first (a ramp order). */
    private boolean layChartFirst;

    /** Puts the page in the order the step playing wants, if that is not the one it is in.
     *  Called every tick and once at the end of showRun; does nothing but compare unless the
     *  setting is "Chart first on ramps" and the step's kind has just changed. */
    void followRampLayout() {
        if (layContent == null || layEcg == null || layStrip == null) return;
        if (Model.clampRunStripWhere(a.model.runStripWhere) != Model.RUN_STRIP_RAMP_FIRST) return;
        int mode = QuickAdjust.modeAt(a.model, a.runRoutine, a.plan, a.planIdx, a.restingNow);
        boolean first = QuickAdjust.chartFirst(a.model.runStripWhere, mode);
        if (first == layChartFirst) return;
        if (layEcg.getParent() != layContent) return;
        if (anyPressed(layStrip)) return;
        boolean inPage = QuickAdjust.stripInPage(a.model.runStripWhere, mode, a.stripSqueezed);
        ViewGroup.LayoutParams ecgLp = layEcg.getLayoutParams();
        layContent.removeView(layEcg);
        int at = 0;
        if (!first) {
            // Back in the usual place: after the status block, the timer card and the hidden rail.
            at = a.runRail != null && a.runRail.getParent() == layContent
                ? layContent.indexOfChild(a.runRail) + 1 : layContent.indexOfChild(a.runTop) + 1;
        }
        layContent.addView(layEcg, at, ecgLp);
        if (layStrip.getParent() instanceof ViewGroup)
            ((ViewGroup) layStrip.getParent()).removeView(layStrip);
        if (layFit != null) layScreen.removeOnLayoutChangeListener(layFit);
        if (inPage) {
            Ui.lift(a, layStrip, Ui.ELEV_CARD);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.bottomMargin = Ui.dp(a, 6);
            layContent.addView(layStrip, layContent.indexOfChild(layEcg) + 1, sLp);
        } else {
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.bottomMargin = Ui.dp(a, 8);
            a.runFooter.addView(layStrip, 0, sLp);
            layFit = new StripFit(layScreen, a.runFooter, layStrip, layContent, layEcg);
            layScreen.addOnLayoutChangeListener(layFit);
        }
        layChartFirst = first;
        // The page is in a different order: show its top, where the chart (or the status block)
        // now is, rather than wherever the old order had been scrolled to.
        if (layScroll != null) layScroll.scrollTo(0, 0);
    }

    /** Is a finger on any view under this one (a strip key being held to repeat)? */
    private static boolean anyPressed(View v) {
        if (v.isPressed()) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++)
                if (anyPressed(g.getChildAt(i))) return true;
        }
        return false;
    }

    /** The status line's dot: a filled circle in the line's colour. */
    private android.graphics.drawable.GradientDrawable dot(int colour) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(colour);
        return g;
    }

    /** Below this a rest's reading is the vented level (the vent watch's own margin is wider;
     *  this only decides whether the chart's readout says "vented"). */
    private static final double VENTED_READOUT_KPA = 1.0;

    /** The live readout above the chart: the number at this size, its unit smaller. */
    private static final float READOUT_SP = 26f;
    private static final float READOUT_UNIT_SP = 15f;

    /** A label inside the chart card - "ROUTINE", "PRESSURE · LIVE": the field-label face
     *  (12 sp medium, uppercase, tracked, DIM), the same one the rest of the app now labels
     *  its fields with. Decoration for a screen reader: the readout and the trace carry
     *  their own descriptions. */
    private TextView cardLabel(String text) {
        TextView t = new TextView(a);
        t.setText(text.toUpperCase(java.util.Locale.ROOT));
        t.setTextColor(Ui.DIM);
        t.setTextSize(Look.SP_FIELD_LABEL);
        t.setLetterSpacing(Look.LABEL_TRACKING_EM);
        Ui.medium(t);
        return t;
    }

    /** "−6.5 inHg" with the unit (and anything after the number) at the smaller size.
     *  The text itself is unchanged - the journal reads it back as a pressure. */
    private static CharSequence readoutText(String shown) {
        int sp = shown.indexOf(' ');
        if (sp <= 0) return shown;
        android.text.SpannableString s = new android.text.SpannableString(shown);
        s.setSpan(new android.text.style.RelativeSizeSpan(READOUT_UNIT_SP / READOUT_SP),
                  sp, shown.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private TextView stageTime(LinearLayout row, int colour) {
        TextView t = new TextView(a);
        t.setTextColor(colour);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(Look.SP_CAPTION);
        row.addView(t);
        return t;
    }

    /**
     * THE ROUTINE OFFSET ROW on the NOW card - `Routine offset …` on the left, `ROUTINE ›`
     * on the right. Wave 2 §4: the whole-routine offset lives behind its own named row -
     * never an inline mode a later tap could hit by accident. (0.10 final: the "CYCLE … SET ›"
     * row above it is gone - the − / + strip and its More › change the step playing.)
     */
    private void routineOffsetRow(LinearLayout parent) {
        // polish RN-10: plain words - "None" for no offset, never a dash; no capitals.
        LinearLayout rrow = Ui.kvRow(a, null, OFFSET_LABEL, "None ›", Ui.TEXT,
                                     new OpenRoutineOffsetTap());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(a, Look.S3);
        parent.addView(rrow, rlp);
        routineOffsetRow = rrow;
    }

    /** The routine offset row, repainted with the offset now in force. */
    private LinearLayout routineOffsetRow;

    private void paintRoutineOffsetRow() {
        if (routineOffsetRow == null) return;
        TextView val = (TextView) routineOffsetRow.getChildAt(1);
        String s = offsetWords(a.routineOffsetKpa) + " ›";
        if (!s.contentEquals(val.getText())) val.setText(s);
        routineOffsetRow.setContentDescription(OFFSET_LABEL + ": "
            + offsetWords(a.routineOffsetKpa) + ". Opens the whole-routine offset.");
    }

    private static final String OFFSET_LABEL = "Whole-routine offset";

    /** "None", "+0.3 inHg", "−0.6 inHg" - the offset in force, in the display unit. */
    static String offsetWords(int kpa) {
        if (kpa == 0) return "None";
        return (kpa > 0 ? "+" : "−") + Model.Fmt.dMag(kpa);
    }

    private final class OpenRoutineOffsetTap implements View.OnClickListener {
        @Override public void onClick(View v) { openRoutineOffset(); }
    }

    /** Wave 2 §4: the offsets sheet — one Δ-pull control, work sets only, with the
     *  preview named before anything moves. Each tap applies one display-unit step
     *  through SessionActivity#applyRoutineOffset (caps: ceiling, the trainer's own
     *  progression step, nothing on a reduced day).
     *
     *  A ROUTINE THAT TAKES NO OFFSET IS TOLD SO BEFORE ANYTHING IS PRESSED (owner report: on
     *  a Length routine the − and + looked dead - every tap was refused, in a snackbar drawn
     *  under this sheet). The same check the Activity asks, RoutineOffset#refusal, and its
     *  words stand IN PLACE OF the buttons. Every other answer a tap gets is written on the
     *  line under them, never behind the sheet (WiringCheck invariant 140). */
    private void openRoutineOffset() {
        if (a.isFinishing()) return;
        if (!a.canCommandNow()) { a.toast(a.whyNothingToAdjust()); return; }
        LinearLayout host = Ui.col(a);
        int pad = Ui.dp(a, Look.S5);
        host.setPadding(pad, pad, pad, pad);
        String refused = RoutineOffset.refusal(a.runRoutine);
        if (refused != null) {
            TextView why = new TextView(a);
            why.setText(refused);
            why.setTextColor(Ui.TEXT);
            why.setTextSize(Look.SP_BODY);
            host.addView(why);
            Ui.dress(a, a.holdRunSheet(Ui.dialog(a)
                .setTitle("Whole-routine offset")
                .setView(host)
                .setNegativeButton("Close", null)
                .show()));
            return;
        }
        final TextView line = new TextView(a);
        line.setTextColor(Ui.TEXT);
        line.setTextSize(Look.SP_CAPTION);
        host.addView(line);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        Button minus = Ui.compactStep(a, "−");
        Button plus  = Ui.compactStep(a, "+");
        row.addView(minus); row.addView(plus);
        host.addView(row);
        // What the last tap was told - a refusal, the cap - ON the sheet, under the buttons.
        TextView said = sheetLine();
        host.addView(said);
        minus.setContentDescription("Lower every remaining work pull by one step");
        plus.setContentDescription("Raise every remaining work pull by one step");
        minus.setOnClickListener(new RoutineOffsetStep(line, said, -1));
        plus.setOnClickListener(new RoutineOffsetStep(line, said, 1));
        paintRoutineOffsetLine(line);
        Ui.dress(a, a.holdRunSheet(Ui.dialog(a)
            .setTitle("Whole-routine offset")
            .setMessage("Moves the pull of every remaining work set — never the warm-up, "
                + "a rest, the fatigue block or the retention hold. This run only; the "
                + "saved routine is untouched.")
            .setView(host)
            .setNegativeButton("Close", null)
            .show()));
    }

    /** A run sheet's own message line: empty and out of the way until something is said. */
    private TextView sheetLine() {
        TextView t = new TextView(a);
        t.setTextSize(Look.SP_CAPTION);
        t.setPadding(0, Ui.dp(a, Look.S2), 0, Ui.dp(a, Look.S2));
        t.setVisibility(View.GONE);
        return t;
    }

    /** Writes on a sheet's message line (null clears it), journalled like every other
     *  message. The sheet keeps it until the next thing is said. */
    private void writeOnSheet(TextView line, String s, boolean important) {
        if (line == null) return;
        if (s == null || s.length() == 0) {
            line.setText("");
            line.setVisibility(View.GONE);
            return;
        }
        a.journalSnack(s);
        line.setText(s);
        line.setTextColor(important ? Ui.TEXT : Ui.DIM);
        line.setVisibility(View.VISIBLE);
    }

    private void paintRoutineOffsetLine(TextView line) {
        int[] pv = a.routineOffsetPreview(0);
        // polish RN-10: "Offset +0.3 inHg · 6 work steps left · peak −6.2 inHg".
        line.setText("Offset " + offsetWords(a.routineOffsetKpa).toLowerCase(java.util.Locale.ROOT)
            + "  ·  " + pv[0] + (pv[0] == 1 ? " work step left" : " work steps left")
            + "  ·  peak " + Model.Fmt.p(pv[1]));
    }

    private final class RoutineOffsetStep implements View.OnClickListener {
        private final TextView line, said;
        private final int dir;
        RoutineOffsetStep(TextView l, TextView s, int d) { line = l; said = s; dir = d; }
        @Override public void onClick(View v) {
            // One display-unit step, as a SIGNED delta (nudgeKpa steps a value, so the
            // delta is measured off an arbitrary anchor).
            int d = Model.Fmt.nudgeKpa(10, dir) - 10;
            /* PAST THE USUAL CAP IS ASKED ONCE (0.10, the owner's decision) - the trainer's
             * +2 kPa, a length routine's nothing - on a dialog above the sheet; the answer
             * lands on the sheet like every other. */
            String ask = a.routineOffsetWarning(d);
            if (ask != null) {
                Ui.dress(a, a.holdRunSheet(Ui.dialog(a)
                    .setTitle("Past the usual offset?")
                    .setMessage(ask)
                    .setPositiveButton(RoutineOffset.WARN_GO,
                        new ConfirmRoutineOffset(line, said, d))
                    .setNegativeButton(RoutineOffset.WARN_KEEP,
                        new KeepRoutineOffset(said))
                    .show()));
                return;
            }
            stepRoutineOffset(line, said, d);
        }
    }

    /** One run-offset step, said on the sheet (invariant 140). */
    private void stepRoutineOffset(TextView line, TextView said, int d) {
        // Its answer goes on the sheet: a snack would draw behind it (invariant 140).
        writeOnSheet(said, a.applyRoutineOffset(d), true);
        paintRoutineOffsetLine(line);
        a.refreshRunScreen(a.session.elapsedMs(System.currentTimeMillis(),
                a.LINK_TIMEOUT_MS));
    }

    /** The warning answered "go on": this run's cap rises to the new offset, then the step. */
    private final class ConfirmRoutineOffset implements DialogInterface.OnClickListener {
        private final TextView line, said;
        private final int d;
        ConfirmRoutineOffset(TextView l, TextView s, int dd) { line = l; said = s; d = dd; }
        @Override public void onClick(DialogInterface dlg, int w) {
            a.routineOffsetWarnedKpa = Math.max(a.routineOffsetWarnedKpa, a.routineOffsetKpa + d);
            stepRoutineOffset(line, said, d);
        }
    }

    /** ...and "keep it": nothing moved, said on the sheet. */
    private final class KeepRoutineOffset implements DialogInterface.OnClickListener {
        private final TextView said;
        KeepRoutineOffset(TextView s) { said = s; }
        @Override public void onClick(DialogInterface dlg, int w) {
            writeOnSheet(said, "Nothing was changed.", false);
        }
    }

    private void paintNetRow() {
        if (a.nowNetRow == null) return;
        Model.Routine r = a.runRoutine;
        if (r == null || r.trainerTrack == Model.TRAINER_TRACK_NONE
                || r.trainerTrack == Plan.TRACK_FEEDER) {
            a.nowNetRow.setText("");
            // Nothing to say, so no empty line at the foot of the NOW card either.
            a.nowNetRow.setVisibility(View.GONE);
            return;
        }
        Model.TrainerTrackState st = r.trainerTrack == Plan.TRACK_LENGTH
            ? a.model.trainerLength : a.model.trainerGirth;
        if (st == null) {
            a.nowNetRow.setText("");
            a.nowNetRow.setVisibility(View.GONE);
            return;
        }
        a.nowNetRow.setVisibility(View.VISIBLE);
        int level = r.trainerLevel > 0 ? r.trainerLevel : st.level;
        /* ONCE A SECOND, NOT 2.5 TIMES A SECOND. netGrossTupSec is not incremental: every
         * call unboxes five ArrayLists that grow for the length of the run into fresh
         * arrays, and allocates a sixth. This row prints WHOLE MINUTES, so recomputing it
         * on every 400 ms frame spent that walk to redraw a figure that cannot have
         * changed. The cached minute is redrawn every frame; only the walk is gated. */
        long now = System.currentTimeMillis();
        if (a.netRowAt == 0 || now - a.netRowAt >= 1000L) {
            a.netRowAt = now;
            // On the routine's own scale (0.10): the line the session is scored against.
            double flr = a.model.netFloorKpa(Model.scaledLevelFloorKpa(r, level));
            double[] tup = a.session.netGrossTupSec(flr, a.model.tupCountTolKpa(flr));
            a.netRowMin = (int) Math.round(tup[0] / 60.0);
        }
        int netMin = a.netRowMin;
        // THE THRESHOLD, IN WORDS, BESIDE THE FIGURE IT PRODUCES. The chart draws a dotted
        // line labelled "counts from here", which can only be read against the axis. This row
        // already carries the number that line decides; saying the pressure here keeps the two
        // halves of one fact a line apart rather than a chart apart.
        // polish RN-11: in words - "12 min at pressure so far (counted from −5.0 inHg)".
        a.nowNetRow.setText(netMin + " min at pressure so far (counted from "
            // THE LEVEL floor, not `flr` - tupCountFloorKpa applies the reduction itself,
            // so passing the already-net figure subtracted it twice.
            + Model.Fmt.p(a.model.tupCountFloorKpa(Model.scaledLevelFloorKpa(r, level))) + ")");
    }

    /** Draws the adjust sheet's scope switch as the scope stands - after a tap, and every
     *  tick, because a new set resets the scope to the whole block from inside the
     *  sequencing (playPreset). */
    void paintScopeSwitch() {
        if (a.scopeSwitch == null) return;
        int want = a.ovScopeRep ? 1 : 0;
        if (a.scopeSwitch.selected() != want) a.scopeSwitch.select(want);
    }

    /** The adjust sheet's `[ Rest of this block | This set only ]` (0.10 final - the scope
     *  switch moved here from the NOW card, in the approved words). Rest of this block is the
     *  default and the switch resets to it at every set boundary (playPreset). Routine scope
     *  is deliberately NOT here: a whole-routine change is never one stray tap - it lives
     *  behind the ROUTINE › row. */
    private final class ScopeTap implements View.OnClickListener {
        private final boolean rep;
        ScopeTap(boolean r) { rep = r; }
        @Override public void onClick(View v) {
            // ONE PUMP PROGRAM HAS NO "THIS SET ONLY". A block the pump repeats on its own
            // (one preset, several sets) can only be changed for what is left of it: the app
            // cannot see where one of its sets ends. Said, and left on the whole block.
            Model.Preset p = (a.planIdx >= 0 && a.planIdx < a.plan.size())
                ? a.plan.get(a.planIdx) : null;
            boolean ramp = a.rampOfStepsAt(a.planIdx);
            if (rep && p != null && !ramp && RunLook.setsIn(p) > 1) {
                a.ovScopeRep = false;
                paintScopeSwitch();
                sheetSay("This block runs as one program on the pump, so a change is for the "
                    + "rest of it.", true);
                return;
            }
            a.ovScopeRep = rep;
            paintScopeSwitch();
            if (ramp)
                sheetSay(rep ? "Changes here are for this step only — the ramp's later steps "
                               + "keep their plan."
                             : "Changes here move the rest of this ramp by the same amount, "
                               + "never past your ceiling.", false);
            else
                sheetSay(rep ? "Changes here are for this set only — the next one plays the plan."
                             : "Changes here are for the rest of this block.", false);
        }
    }

    /** WHY THE FIGURE DID NOT MOVE — names the limit and where it comes from, so a
     *  refusal is an answer rather than a dead chip. */
    private String nudgeRefusedWhy(int field, int delta, int at) {
        boolean up = delta > 0;
        switch (field) {
            case OvSlide.UP:
                return up ? "That is your safety ceiling, " + Model.Fmt.p(a.model.ceilKpa)
                            + " — Settings › Session is where it moves"
                          : "The pull is already down at " + Model.Fmt.p(at);
            case OvSlide.LO:
                return up ? "The drop stays under the pull, " + Model.Fmt.p(a.ovUp)
                          : "The drop is already down at " + Model.Fmt.p(at);
            case OvSlide.UH:
                return up ? "The hold is as long as the pump accepts, 255 s"
                          : "The hold is as short as it goes, 1 s";
            case OvSlide.LH:
                return up ? "The drop time is as long as the pump accepts, 255 s"
                          : "The drop time is already 0 s";
            default:
                // polish LB-12: the run sheet calls it suction power, so its limit does too.
                return up ? "Suction power is already at 100%" : "Suction power is already at 0%";
        }
    }

    /**
     * ONE − / + TAP OF AN EXACT SIZE (the strip, 0.10 final): nudgeOverride's road with the
     * strip's own steps - the pull and the drop 1 kPa, the hold 5 s, the drop time 1 s, and
     * "+30 s hold" 30 s (QuickAdjust) - through LiveEdit#tapBy, so the pending target, the
     * wire's limits, the hold-only lock and the debounced settle are the ones every live edit
     * has. The refusal is RETURNED (the caller says it).
     */
    private String nudgeBy(int field, int delta) {
        if (!a.canCommandNow()) return a.whyNothingToAdjust();
        long now = System.currentTimeMillis();
        boolean locked = a.planIdx >= 0 && a.planIdx < a.plan.size()
            && RunEdit.dropLocked(a.plan.get(a.planIdx));
        int[] inForce = a.inForceTuple();
        int[] work = a.ovTuple();
        int before = a.liveEdit.needsSeed(a.planIdx) ? inForce[field] : work[field];
        int result = a.liveEdit.tapBy(work, field, delta, inForce, a.model.ceilKpa, locked,
                                      a.planIdx, now);
        a.ovFrom(work);
        if (result == LiveEdit.LOCKED)
            return "This step only holds — it has no drop to change";
        if (result == LiveEdit.AT_LIMIT) return nudgeRefusedWhy(field, delta, before);
        overrideChanged();
        a.repaintRunCells();
        return null;
    }

    /* ================================ THE − / + STRIP (0.10 final) ==========================
     *
     * The owner-approved "quick adjust": one card with the step playing's figures between their
     * own − and +, 48 × 48 dp keys either end of a 48 dp cell. In a set: "Pull to · target",
     * "Hold time", "Drop to · target", "Drop time", and More › for the full sheet. A ramp's
     * step and the warm-up: the same four (owner request, 0.10), and under them one cell, how
     * long the step or the warm-up runs. A rest: that one cell alone. It changes the step
     * PLAYING and nothing else, and it is the one place on the page that does:
     *
     *   - the four set figures go through nudgeBy - LiveEdit's pending target, applyOverride's
     *     one road to the pump - so a change is sent when the tapping settles, the countdown
     *     carries on where it is (RunEdit#countdownPreserved) and every safety rule the live
     *     edit has holds (the ceiling, the drop under the pull, nothing over a pause, nothing
     *     while the pump has not answered);
     *   - a length goes through the Activity's quick* methods: the app's own advance clock,
     *     re-timed the way +30 s re-times it (the same Advance re-posted), never a wire value.
     *
     * BUILT ONCE, REPAINTED IN PLACE. showRun builds every view the strip can need - the 2 × 2
     * grid and the single cell - and paintStrip only sets text, colours and visibility, and
     * only when they change, so a tick never takes a key out from under a finger (a repeat is
     * a press that outlives several ticks).
     *
     * A KEY AT ITS LIMIT is dimmed, never disabled: a tap on it says which limit it met
     * (QuickAdjust's words) with a firmer tick. Press and hold repeats after 500 ms, every
     * 150 ms, and stops at a limit. Each step ticks lightly; the ticks are the view's own
     * haptic feedback, so the system's touch-feedback setting decides whether there are any. */

    /** Press and hold: the first repeat, and every one after it. */
    static final long STRIP_REPEAT_FIRST_MS = 500L, STRIP_REPEAT_MS = 150L;

    private LinearLayout stripBox, stripGrid, stripOne, stripSteps;
    /** The ramp's steps row: "Step 2 of 5", "− step", "+ step" (owner request). */
    private TextView stripStepsSaid;
    private Button stripStepLess, stripStepMore;
    private TextView stripHead;
    private Button stripMore;
    /** The set's four cells (PULL, HOLD, DROP, DROP_TIME, in the mock's order) and the one
     *  length cell, whose field follows the step. */
    private final StripCell[] stripCells = new StripCell[4];
    private StripCell stripLen;

    /** What the strip shows and what a tap is judged against, read off the run in ONE place
     *  (stripNow) for the tick and for the tap alike. */
    private static final class StripNow {
        int mode = QuickAdjust.MODE_NONE;
        /** The four figures of the step playing (pull, hold, drop, drop time) are shown and
         *  change it: a work set, a ramp's step, the warm-up's preset. */
        boolean grid;
        final int[] v = new int[QuickAdjust.FIELDS];
        /** How far the step playing has run, for the lengths; -1 when not a length. */
        int elapsed = -1;
        /** The partner pressures: the drop for the pull (-1 when the step has no drop), the
         *  pull for the drop. */
        int pullOther = -1, dropOther = -1;
        boolean dropLocked;
        /** Why no length can change here (the cylinder change), else null. */
        String blocked;
        /** On a ramp's step: one cycle of it as it is in force (hold + drop time) - what one
         *  tap of Time per step moves it by - and how many steps the ramp has. */
        int stepCycle, rampSteps;
        int lenField = -1;
        String head = "";
        /** A set figure on its way to the pump, and the pump not answering. */
        final boolean[] sending = new boolean[QuickAdjust.FIELDS];
        boolean notConfirmed;
    }

    private final class StripCell {
        int field;
        final LinearLayout box;
        final Button minus, plus;
        final TextView label, value;
        StripCell(LinearLayout b, Button m, Button p, TextView l, TextView v) {
            box = b; minus = m; plus = p; label = l; value = v;
        }
    }

    /** Builds the strip - every view it can need - and returns it for showRun to place. */
    private LinearLayout buildStrip() {
        LinearLayout qk = Ui.col(a);
        stripBox = qk;
        qk.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        int p8 = Ui.dp(a, 8);
        qk.setPadding(p8, p8, p8, p8);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(Ui.dp(a, 22));
        head.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
        stripHead = new TextView(a);
        stripHead.setTextColor(Ui.DIM);
        stripHead.setTextSize(Look.SP_FIELD_LABEL);
        stripHead.setLetterSpacing(0.046f);
        stripHead.setSingleLine(true);
        stripHead.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ui.medium(stripHead);
        head.addView(stripHead, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stripMore = new Button(a);
        stripMore.setText("More ›");
        stripMore.setAllCaps(false);
        stripMore.setTextColor(Ui.TEXT);
        stripMore.setTextSize(Look.SP_CAPTION);
        Ui.medium(stripMore);
        stripMore.setBackground(null);
        stripMore.setStateListAnimator(null);
        stripMore.setMinWidth(0); stripMore.setMinimumWidth(0);
        stripMore.setMinHeight(0); stripMore.setMinimumHeight(0);
        stripMore.setPadding(Ui.dp(a, 2), 0, Ui.dp(a, 2), 0);
        stripMore.setContentDescription("More. The full sheet for the step playing: pull, drop, "
            + "hold, drop time and suction power, and whether a change is for the rest of this block "
            + "(or ramp) or this step only.");
        stripMore.setOnClickListener(new OpenOverrideTap());
        head.addView(stripMore, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 32)));
        qk.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // More › is drawn 32 dp tall (the mock's header row) and TOUCHED 48 dp: a delegate on
        // the strip itself (whose bounds reach past the header) widens its hit area, set again
        // whenever the strip is laid out (it is GONE, then shown, as the step changes).
        qk.addOnLayoutChangeListener(new MoreTouchArea(qk, stripMore));

        stripGrid = Ui.col(a);
        LinearLayout r1 = stripRow(stripGrid, 0), r2 = stripRow(stripGrid, 6);
        stripCells[0] = stripCell(r1, QuickAdjust.PULL, true);
        stripCells[1] = stripCell(r1, QuickAdjust.HOLD, false);
        stripCells[2] = stripCell(r2, QuickAdjust.DROP, true);
        stripCells[3] = stripCell(r2, QuickAdjust.DROP_TIME, false);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = Ui.dp(a, 6);
        qk.addView(stripGrid, gl);

        stripOne = Ui.col(a);
        stripLen = stripCell(stripRow(stripOne, 0), QuickAdjust.REST, true);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.topMargin = Ui.dp(a, 6);
        qk.addView(stripOne, ol);
        stripOne.setVisibility(View.GONE);

        /* THE RAMP'S STEPS (owner request, 0.10): which step of how many, "+ step" - one more
         * at the ramp's top, its time per step - and "− step" to take an added one out again
         * before it starts. Built here with the rest, shown only on a ramp's step. */
        stripSteps = new LinearLayout(a);
        stripSteps.setOrientation(LinearLayout.HORIZONTAL);
        stripSteps.setGravity(Gravity.CENTER_VERTICAL);
        stripStepsSaid = new TextView(a);
        stripStepsSaid.setTextColor(Ui.DIM);
        stripStepsSaid.setTextSize(Look.SP_CAPTION);
        stripStepsSaid.setSingleLine(true);
        stripStepsSaid.setPadding(Ui.dp(a, 4), 0, 0, 0);
        stripSteps.addView(stripStepsSaid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stripStepLess = stepsButton("− step");
        stripStepLess.setOnClickListener(new StripStepsTap(-1));
        // polish RN-6: 48 dp, the floor - this is the screen used under pressure.
        stripSteps.addView(stripStepLess, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 48)));
        stripStepMore = stepsButton("+ step");
        stripStepMore.setOnClickListener(new StripStepsTap(1));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 48));
        ml.leftMargin = Ui.dp(a, 6);
        stripSteps.addView(stripStepMore, ml);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(a, 4);
        qk.addView(stripSteps, sl);
        stripSteps.setVisibility(View.GONE);
        return qk;
    }

    /** A steps-row button: text, the strip's own surface, a 48 dp row. */
    private Button stepsButton(String text) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Ui.TEXT);
        b.setTextSize(Look.SP_CHIP);
        Ui.medium(b);
        b.setBackground(Ui.roundRect(a, Ui.SURFHI, 9));
        b.setStateListAnimator(null);
        b.setMinWidth(0); b.setMinimumWidth(Ui.dp(a, 64));
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
        return b;
    }

    /** "+ step" / "− step": the ramp playing, one step more or the added one out - said. */
    private final class StripStepsTap implements View.OnClickListener {
        private final int dir;
        StripStepsTap(int d) { dir = d; }
        @Override public void onClick(View v) {
            String no = dir > 0 ? RunEdit.addStepRefusal(a.plan, a.planIdx)
                                : RunEdit.removeStepRefusal(a.plan, a.planIdx);
            String said = no != null ? no : dir > 0 ? a.addRampStep() : a.removeAddedRampStep();
            boolean refused = no != null || said == null || !(said.startsWith("Step ")
                || said.startsWith("The added step"));
            a.toast(said);
            stripHaptic(v, refused);
            paintStrip();
        }
    }

    /**
     * THE STRIP IS NEVER SQUEEZED OFF THE SCREEN (owner report: on a Samsung phone, in a trainer
     * session, only the buttons showed - no − / + card). Pinned, the strip heads the footer; a
     * footer that is taller than the screen can give it (a short screen, a large font or display
     * size - a ramp's step shows the grid and its length cell, three rows) would squeeze the page
     * above it to nothing, or push the footer past the bottom. So after every layout of the run
     * screen the footer is measured against it (QuickAdjust#fitsPinned): when it leaves the page
     * less than MIN_PAGE_DP, the strip moves into the page, right under the chart - where it
     * scrolls and is never clipped - and stays there (SessionActivity#stripSqueezed). The
     * buttons and STOP stay pinned. The move is posted, never made inside a layout pass.
     */
    private final class StripFit implements View.OnLayoutChangeListener, Runnable {
        private final View screen, strip, anchor;
        private final LinearLayout footer, content;
        private boolean moving;
        StripFit(View s, LinearLayout f, View st, LinearLayout c, View an) {
            screen = s; footer = f; strip = st; content = c; anchor = an;
        }
        @Override public void onLayoutChange(View v, int l, int t, int r0, int b, int ol, int ot,
                                             int or, int ob) {
            if (moving || strip.getParent() != footer) return;
            if (QuickAdjust.fitsPinned(screen.getHeight(), footer.getHeight(),
                    Ui.dp(a, QuickAdjust.MIN_PAGE_DP))) return;
            moving = true;
            a.ui.post(this);
        }
        @Override public void run() {
            // The strip may have been moved out of the footer since this was posted ("Chart
            // first on ramps" does that on a ramp's step): then there is nothing to squeeze, and
            // the listener stays for when it is back in the footer.
            if (strip.getParent() != footer) { moving = false; return; }
            screen.removeOnLayoutChangeListener(this);
            if (!screen.isAttachedToWindow()) return;
            a.stripSqueezed = true;
            footer.removeView(strip);
            Ui.lift(a, strip, Ui.ELEV_CARD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(a, 6);
            content.addView(strip, content.indexOfChild(anchor) + 1, lp);
            a.log("--- the − / + controls moved under the chart: pinned, the footer left the "
                + "page less than " + QuickAdjust.MIN_PAGE_DP + " dp on this screen ---");
        }
    }

    /** More ›'s 48 dp touch area, set once the strip has its size - in the strip's own
     *  coordinates, so a touch in its padding round the header reaches More ›. */
    private static final class MoreTouchArea implements View.OnLayoutChangeListener {
        private final ViewGroup parent;
        private final View child;
        MoreTouchArea(ViewGroup p, View c) { parent = p; child = c; }
        @Override public void onLayoutChange(View v, int l, int t, int r0, int b, int ol, int ot,
                                             int or, int ob) {
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) {
                parent.setTouchDelegate(null);
                return;
            }
            android.graphics.Rect r = new android.graphics.Rect();
            child.getDrawingRect(r);
            parent.offsetDescendantRectToMyCoords(child, r);
            float d = child.getResources().getDisplayMetrics().density;
            int want = (int) (48 * d + 0.5f);
            int growV = Math.max(0, (want - r.height()) / 2 + 1);
            int growH = Math.max(0, (want - r.width()) / 2 + 1);
            r.top -= growV; r.bottom += growV; r.left -= growH; r.right += growH;
            parent.setTouchDelegate(new android.view.TouchDelegate(r, child));
        }
    }

    private LinearLayout stripRow(LinearLayout parent, int topDp) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, topDp);
        parent.addView(row, lp);
        return row;
    }

    /** One cell: [−] label-over-figure [+], 48 dp tall, the keys 48 × 48 dp. */
    private StripCell stripCell(LinearLayout row, int field, boolean first) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f);
        if (!first) blp.leftMargin = Ui.dp(a, 6);
        row.addView(box, blp);

        Button minus = stripKey("−", true);
        box.addView(minus, new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
        LinearLayout mid = Ui.col(a);
        mid.setGravity(Gravity.CENTER);
        mid.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView label = new TextView(a);
        label.setTextColor(Ui.DIM);
        label.setTextSize(Look.SP_MICRO);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        mid.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView value = new TextView(a);
        value.setTextColor(Ui.TEXT);
        value.setTextSize(Look.SP_BODY);
        value.setTypeface(android.graphics.Typeface.MONOSPACE);
        value.setSingleLine(true);
        value.setGravity(Gravity.CENTER);
        value.setIncludeFontPadding(false);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = Ui.dp(a, 2);
        mid.addView(value, vlp);
        box.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button plus = stripKey("+", false);
        box.addView(plus, new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));

        StripCell c = new StripCell(box, minus, plus, label, value);
        c.field = field;
        StripKey m = new StripKey(c, -1), p = new StripKey(c, 1);
        minus.setOnTouchListener(m); minus.setOnClickListener(m);
        plus.setOnTouchListener(p); plus.setOnClickListener(p);
        return c;
    }

    /** A 48 × 48 dp key, its outer corners rounded with the cell's, a shade lighter pressed. */
    private Button stripKey(String glyph, boolean left) {
        Button b = new Button(a);
        b.setText(glyph);
        b.setAllCaps(false);
        b.setTextSize(Look.SP_VERDICT);
        b.setTextColor(Ui.TEXT);
        b.setIncludeFontPadding(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setHapticFeedbackEnabled(true);
        float r = Ui.dp(a, Look.R_CTRL);
        float[] radii = left ? new float[] { r, r, 0, 0, 0, 0, r, r }
                             : new float[] { 0, 0, r, r, r, r, 0, 0 };
        android.graphics.drawable.GradientDrawable up = new android.graphics.drawable.GradientDrawable();
        up.setColor(Look.STEP_KEY);
        up.setCornerRadii(radii);
        android.graphics.drawable.GradientDrawable down = new android.graphics.drawable.GradientDrawable();
        down.setColor(Look.STEP_KEY_DOWN);
        down.setCornerRadii(radii);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_pressed }, down);
        sl.addState(new int[0], up);
        b.setBackground(sl);
        return b;
    }

    /**
     * ONE KEY: a tap is one step (onClick - also what a screen reader's double-tap and a
     * keyboard reach); a press held past 500 ms repeats every 150 ms until it is lifted,
     * slides off, is taken by the page's scroll, or meets a limit. The repeat is this key's
     * own Runnable on the UI thread, removed on every way a press can end, so nothing can
     * outlive the finger.
     */
    private final class StripKey implements View.OnTouchListener, View.OnClickListener, Runnable {
        private final StripCell cell;
        private final int dir;
        private View held;
        private boolean repeated, cancelled;
        StripKey(StripCell c, int d) { cell = c; dir = d; }

        @Override public boolean onTouch(View v, android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    held = v; repeated = false; cancelled = false;
                    v.setPressed(true);
                    a.ui.removeCallbacks(this);
                    a.ui.postDelayed(this, STRIP_REPEAT_FIRST_MS);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!cancelled && (e.getX() < 0 || e.getY() < 0
                            || e.getX() > v.getWidth() || e.getY() > v.getHeight())) {
                        cancelled = true;
                        end(v);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    boolean click = !repeated && !cancelled;
                    end(v);
                    if (click) v.performClick();
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    cancelled = true;
                    end(v);
                    return true;
                default:
                    return true;
            }
        }

        private void end(View v) {
            a.ui.removeCallbacks(this);
            v.setPressed(false);
            held = null;
        }

        @Override public void onClick(View v) { stripStep(cell, dir, v, false); }

        @Override public void run() {
            View v = held;
            if (v == null || !v.isPressed() || !v.isAttachedToWindow()) return;
            repeated = true;
            if (!stripStep(cell, dir, v, true)) { end(v); return; }
            a.ui.postDelayed(this, STRIP_REPEAT_MS);
        }
    }

    /**
     * ONE STEP of one cell, from a tap or a repeat. Asked first against the strip's own ranges
     * (QuickAdjust) on the run as it stands now, then sent through the one road for its kind.
     * True when the figure moved; false when it was refused - said, with a firmer tick.
     */
    private boolean stripStep(StripCell cell, int dir, View key, boolean repeating) {
        StripNow s = stripNow(System.currentTimeMillis());
        int field = cell.field;
        /* PAST THE STRIP'S 10.0 inHg IS ASKED ONCE (0.10, the owner's decision) - for each new
         * highest pull this run, and only when nothing else refuses the tap; past the hard
         * limit it is refused as ever (QuickAdjust#pullWarning). */
        if (field == QuickAdjust.PULL && dir > 0 && s.grid) {
            int next = s.v[QuickAdjust.PULL] + QuickAdjust.STEP[QuickAdjust.PULL];
            String ask = a.pullWarningNow(next);
            if (ask != null && stripRefusal(s, field, dir, next) == null) {
                stripHaptic(key, true);
                // The highest pull this "+" puts anywhere - with "Rest of this ramp", a later
                // step's (review 2, finding 2) - is what the person confirms.
                int peak = a.pullPeakNow(next)[0];
                Ui.dress(a, a.holdRunSheet(Ui.dialog(a)
                    .setTitle("Past the usual pull?")
                    .setMessage(ask)
                    .setPositiveButton(QuickAdjust.pullWarnGo(peak),
                        new ConfirmPastPull(cell, dir, peak))
                    .setNegativeButton("Keep it", null)
                    .show()));
                return false;
            }
        }
        /* A DROP PAST THE FLOOR IS ASKED ONCE A RUN (owner, 2026-09-30) - from at or under it
         * to above it, and only when nothing else refuses the tap; past 1.0 inHg under the pull
         * it is refused as ever (QuickAdjust#refusal). Confirmed, it is the person's call. */
        if (field == QuickAdjust.DROP && dir > 0 && s.grid) {
            int from = s.v[QuickAdjust.DROP];
            if (RunEdit.dropNeedsWarning(from, from + QuickAdjust.STEP[QuickAdjust.DROP],
                    a.dropWarned) && stripRefusal(s, field, dir) == null) {
                stripHaptic(key, true);
                Ui.dress(a, a.holdRunSheet(Ui.dialog(a)
                    .setTitle(RunEdit.DROP_WARN_TITLE)
                    .setMessage(RunEdit.dropWarning())
                    .setPositiveButton(RunEdit.DROP_WARN_GO, new ConfirmPastDrop(cell, dir))
                    .setNegativeButton(RunEdit.DROP_WARN_BACK, null)
                    .show()));
                return false;
            }
        }
        String no = stripRefusal(s, field, dir);
        if (no == null) no = stripApply(s, field, dir);
        if (no != null) {
            a.toast(no);
            stripHaptic(key, true);
            return false;
        }
        stripHaptic(key, false);
        paintStrip();
        return true;
    }

    /** The strip's warning answered "pull to": the highest pull it asked about remembered for
     *  this run, then the step. */
    private final class ConfirmPastPull implements DialogInterface.OnClickListener {
        private final StripCell cell;
        private final int dir, peak;
        ConfirmPastPull(StripCell c, int d, int p) { cell = c; dir = d; peak = p; }
        @Override public void onClick(DialogInterface dlg, int w) {
            a.pullWarnedKpa = Math.max(a.pullWarnedKpa, peak);
            stripStep(cell, dir, null, false);
        }
    }

    /** The strip's drop warning answered "keep it": remembered for this run, then the step. */
    private final class ConfirmPastDrop implements DialogInterface.OnClickListener {
        private final StripCell cell;
        private final int dir;
        ConfirmPastDrop(StripCell c, int d) { cell = c; dir = d; }
        @Override public void onClick(DialogInterface dlg, int w) {
            a.dropWarned = true;
            stripStep(cell, dir, null, false);
        }
    }

    /** Why one step of `field` is refused on the run as `s` reads it, or null - with the
     *  pull's limit given: the strip's own (a.stripPullCapKpa()), or, asking whether a warning
     *  is owed, the pull the person would be confirming. */
    private String stripRefusal(StripNow s, int field, int dir, int pullCap) {
        if (s.mode == QuickAdjust.MODE_NONE) return "Nothing is playing to adjust";
        // A ONE-CYCLE STEP: its length IS its hold (QuickAdjust#oneCycle) - asked as the hold.
        if (oneCycleLength(s, field)) {
            if (a.holdMayBeUp()) return "Paused — resume first. Nothing was changed.";
            int nu = s.v[QuickAdjust.HOLD] + (dir < 0 ? -QuickAdjust.STEP[field] : QuickAdjust.STEP[field]);
            if (nu < QuickAdjust.MIN[QuickAdjust.HOLD])
                return QuickAdjust.oneCycleShortest(QuickAdjust.LABEL[field]);
            if (nu > QuickAdjust.MAX[QuickAdjust.HOLD]) return "4:15 is the longest the pump takes.";
            return a.cycleRefusalNow(nu, s.v[QuickAdjust.DROP_TIME]);
        }
        boolean gridField = field == QuickAdjust.PULL || field == QuickAdjust.HOLD
            || field == QuickAdjust.DROP || field == QuickAdjust.DROP_TIME;
        if (s.grid && gridField) {
            if (a.holdMayBeUp()) return "Paused — resume first. Nothing was changed.";
            if (s.dropLocked && (field == QuickAdjust.DROP || field == QuickAdjust.DROP_TIME))
                return "This step only holds — it has no drop to change";
            int other = field == QuickAdjust.PULL ? s.pullOther
                      : field == QuickAdjust.DROP ? s.dropOther : -1;
            String no = QuickAdjust.stepRefusal(field, s.v[field], dir, other, -1, a.model.ceilKpa,
                                                pullCap);
            // HOW IT WOULD GO ON THE PUMP (HoldCarryOn): refused on a one-cycle step in its drop,
            // or when what it adds passes the two-hour stop.
            if (no == null) {
                int step = dir < 0 ? -QuickAdjust.STEP[field] : QuickAdjust.STEP[field];
                no = a.carryOnRefusal(
                    s.v[QuickAdjust.HOLD] + (field == QuickAdjust.HOLD ? step : 0),
                    s.v[QuickAdjust.DROP_TIME] + (field == QuickAdjust.DROP_TIME ? step : 0));
            }
            // A RAMP'S STEP AND THE WARM-UP'S: the cycle never outgrows the step (the one rule,
            // QuickAdjust#cycleRefusal) - here, and on the sheet's road (applyOverride).
            if (no == null && s.lenField >= 0
                    && (field == QuickAdjust.HOLD || field == QuickAdjust.DROP_TIME)) {
                int nu = s.v[QuickAdjust.HOLD] + (field == QuickAdjust.HOLD ? QuickAdjust.STEP[field] : 0);
                int nl = s.v[QuickAdjust.DROP_TIME]
                    + (field == QuickAdjust.DROP_TIME ? QuickAdjust.STEP[field] : 0);
                no = a.cycleRefusalNow(nu, nl);
            }
            return no;
        }
        if (s.blocked != null) return s.blocked;
        // TIME PER STEP ON A RAMP'S STEP OF MORE THAN ONE CYCLE moves by one whole cycle
        // (quickRampStep): asked here as it will go - never under one cycle, never past the
        // step's share of the hour. Not the wire's 255 s: a step's length is the app's clock.
        if (field == QuickAdjust.STEP_TIME && s.mode == QuickAdjust.MODE_RAMP) {
            int v = s.v[field];
            int next = QuickAdjust.stepTimeNext(v, dir, s.stepCycle);
            String no = QuickAdjust.stepTimeRefusal(v, next, s.stepCycle, s.rampSteps);
            return no != null ? no : QuickAdjust.refusal(field, v, next - v, -1, s.elapsed, a.model.ceilKpa);
        }
        return QuickAdjust.stepRefusal(field, s.v[field], dir, -1, s.elapsed, a.model.ceilKpa,
                                       pullCap);
    }

    /** The same at the strip's own pull limit. */
    private String stripRefusal(StripNow s, int field, int dir) {
        return stripRefusal(s, field, dir, a.stripPullCapKpa());
    }

    /** The step itself, through the one road for its kind; the refusal, or null. */
    private String stripApply(StripNow s, int field, int dir) {
        int d = dir < 0 ? -QuickAdjust.STEP[field] : QuickAdjust.STEP[field];
        switch (field) {
            case QuickAdjust.PULL:      return nudgeBy(LiveEdit.UP, d);
            case QuickAdjust.HOLD:      return nudgeBy(LiveEdit.UH, d);
            // A RAISE IS THE PERSON'S OWN DROP for the set playing (allowDropRaise): asked and
            // allowed above, so the wire and the set's later steps keep it, not the floor.
            case QuickAdjust.DROP:      if (d > 0) a.allowDropRaise(s.v[QuickAdjust.DROP] + d);
                                        return nudgeBy(LiveEdit.LO, d);
            case QuickAdjust.DROP_TIME: return nudgeBy(LiveEdit.LH, d);
            case QuickAdjust.REST:      return a.quickRest(d);
            // A one-cycle step's length is its hold: the same road, the same scope, and the
            // step's time follows when the pump takes it (SessionActivity#commitEdit).
            case QuickAdjust.STEP_TIME: return oneCycleLength(s, field) ? nudgeBy(LiveEdit.UH, d)
                                                                         : a.quickRampStep(d);
            case QuickAdjust.WARM:      return oneCycleLength(s, field) ? nudgeBy(LiveEdit.UH, d)
                                                                         : a.quickWarm(d);
            default:                    return "Nothing to change.";
        }
    }

    /** Is this the length cell of a step that is exactly one cycle - a ramp's step or the
     *  warm-up's - so that its length and its hold are one figure? */
    private boolean oneCycleLength(StripNow s, int field) {
        if (!s.grid) return false;
        boolean len = (s.mode == QuickAdjust.MODE_RAMP && field == QuickAdjust.STEP_TIME)
                   || (s.mode == QuickAdjust.MODE_WARM && field == QuickAdjust.WARM);
        return len && a.oneCycleNow();
    }

    /** A light tick per step, a firmer one at a limit - the view's own haptic feedback, so the
     *  system's touch-feedback setting is honoured. */
    private static void stripHaptic(View key, boolean atLimit) {
        if (key == null) return;
        int kind = !atLimit ? android.view.HapticFeedbackConstants.CLOCK_TICK
            : android.os.Build.VERSION.SDK_INT >= 30
                ? android.view.HapticFeedbackConstants.REJECT
                : android.view.HapticFeedbackConstants.LONG_PRESS;
        key.performHapticFeedback(kind);
    }

    /** The run, as the strip reads it: which cells, their figures, and what a tap is judged
     *  against. The step PLAYING (planIdx) - the one every edit acts on. */
    private StripNow stripNow(long now) {
        StripNow s = new StripNow();
        // ONE READING OF WHAT IS PLAYING (QuickAdjust#modeAt), the one the tests build the
        // trainer's routines against: every step at pressure gets the four figures.
        int mode = QuickAdjust.modeAt(a.model, a.runRoutine, a.plan, a.planIdx, a.restingNow);
        if (mode == QuickAdjust.MODE_NONE) return s;
        Model.Preset cur = a.plan.get(a.planIdx);
        if (a.restingNow) {
            s.mode = QuickAdjust.MODE_REST;
            s.lenField = QuickAdjust.REST;
            s.head = QuickAdjust.HEAD_REST;
            long st = a.restNowStartAt > 0 ? a.restNowStartAt : now;
            s.v[QuickAdjust.REST] = (int) ((Math.max(0L, a.restNowEndAt - st) + 500L) / 1000L);
            s.elapsed = (int) (Math.max(0L, now - st) / 1000L);
            return s;
        }
        if (mode == QuickAdjust.MODE_REST) {
            s.mode = QuickAdjust.MODE_REST;
            s.lenField = QuickAdjust.REST;
            s.head = ByHand.is(cur) ? ByHand.HEAD : QuickAdjust.HEAD_REST;
            s.v[QuickAdjust.REST] = (int) ((cur.durMs + 500L) / 1000L);
            s.elapsed = (int) (Math.max(0L, cur.durMs - Math.max(0L, a.presetFireAt - now)) / 1000L);
            if (cur.awaitAck) s.blocked = "The cylinder change has no length — it waits for you.";
            else if (a.awaitingAck && ByHand.waitsAfterClock(cur))
                s.blocked = "Its time is up \u2014 press Done when you are.";
            return s;
        }
        long left = Math.max(0L, a.presetFireAt - now);
        if (mode == QuickAdjust.MODE_WARM) {
            s.mode = QuickAdjust.MODE_WARM;
            s.lenField = QuickAdjust.lengthField(mode);
            s.head = QuickAdjust.headOf(mode);
            long total = 0, before = 0;
            for (int i = 0; i < a.plan.size(); i++) {
                Model.Preset p = a.plan.get(i);
                if (p.stageIdx != cur.stageIdx) continue;
                total += p.durMs;
                if (i < a.planIdx) before += p.durMs;
            }
            s.v[QuickAdjust.WARM] = (int) ((total + 500L) / 1000L);
            s.elapsed = (int) ((before + Math.max(0L, cur.durMs - left)) / 1000L);
            if (QuickAdjust.showsGrid(mode)) fillGrid(s, cur);
            return s;
        }
        if (mode == QuickAdjust.MODE_RAMP) {
            s.mode = QuickAdjust.MODE_RAMP;
            s.lenField = QuickAdjust.lengthField(mode);
            // WHERE THE RAMP GOES, not only where this step is: "THIS RAMP · −8.0 → −10.3 inHg".
            // The Pull cell is the step's own pull - the ramp's start on its first step - and
            // nothing on the strip said where it climbs to (owner report, 0.10).
            s.head = QuickAdjust.rampHeadAt(a.plan, a.planIdx, a.model.ceilKpa);
            int[] fc = a.inForceTuple();
            s.stepCycle = fc[LiveEdit.UH] + fc[LiveEdit.LH];
            s.rampSteps = RunEdit.stepOfSet(a.plan, a.planIdx)[1];
            // TIME PER STEP IS THE STEP'S LENGTH AS PLANNED - on a one-cycle step, its cycle
            // (hold + drop time): a Resume that ran it whole lengthens this step's clock, never
            // the time per step the cell edits.
            int[] fz = a.inForceTuple();
            s.v[QuickAdjust.STEP_TIME] = a.oneCycleNow() ? fz[LiveEdit.UH] + fz[LiveEdit.LH]
                                                         : (int) ((cur.durMs + 500L) / 1000L);
            s.elapsed = (int) (Math.max(0L, cur.durMs - left) / 1000L);
            if (QuickAdjust.showsGrid(mode)) fillGrid(s, cur);
            return s;
        }
        s.mode = QuickAdjust.MODE_WORK;
        s.head = QuickAdjust.headOf(mode);
        if (QuickAdjust.showsGrid(mode)) fillGrid(s, cur);
        return s;
    }

    /**
     * THE FOUR FIGURES OF THE STEP PLAYING - a work set's, a ramp step's (owner request: the
     * ramp is edited live, step by step) or the warm-up preset's: what is in force, or the
     * target on its way. The same figures, limits and road for every one of them.
     */
    private void fillGrid(StripNow s, Model.Preset cur) {
        s.grid = true;
        int[] inForce = a.inForceTuple();
        int[] work = a.ovTuple();
        s.v[QuickAdjust.PULL] = a.liveEdit.shown(LiveEdit.UP, work, inForce, a.planIdx);
        s.v[QuickAdjust.HOLD] = a.liveEdit.shown(LiveEdit.UH, work, inForce, a.planIdx);
        s.v[QuickAdjust.DROP] = a.liveEdit.shown(LiveEdit.LO, work, inForce, a.planIdx);
        s.v[QuickAdjust.DROP_TIME] = a.liveEdit.shown(LiveEdit.LH, work, inForce, a.planIdx);
        s.dropLocked = RunEdit.dropLocked(cur);
        s.pullOther = s.dropLocked ? -1 : s.v[QuickAdjust.DROP];
        s.dropOther = s.v[QuickAdjust.PULL];
        s.sending[QuickAdjust.PULL] = a.liveEdit.pending(LiveEdit.UP, work, a.planIdx);
        s.sending[QuickAdjust.HOLD] = a.liveEdit.pending(LiveEdit.UH, work, a.planIdx);
        s.sending[QuickAdjust.DROP] = a.liveEdit.pending(LiveEdit.LO, work, a.planIdx);
        s.sending[QuickAdjust.DROP_TIME] = a.liveEdit.pending(LiveEdit.LH, work, a.planIdx);
        s.notConfirmed = a.notAnsweringMayBeUp() >= 0;
        if (s.notConfirmed)
            s.v[QuickAdjust.PULL] = Math.max(a.notAnsweringMayBeUp(), s.v[QuickAdjust.PULL]);
    }

    /** The warm-up is playing: its stage is the warm-up every builder writes. */
    private boolean warmUpNow(Model.Preset cur) {
        return QuickAdjust.warmUpAt(a.runRoutine, cur);
    }

    /** A ramp is playing: its set is one, and it has more than one step. */
    private boolean rampNow(Model.Preset cur) {
        return rampNowAt(cur, a.planIdx);
    }

    /** The preset at `idx` is a step of a ramp of more than one step. */
    private boolean rampNowAt(Model.Preset cur, int idx) {
        return cur != null && QuickAdjust.rampAt(a.model, a.plan, idx);
    }

    /** Repaints the strip from the run as it stands - text, colours and visibility only, and
     *  only what changed (the views are showRun's, never rebuilt here). */
    void paintStrip() {
        paintStrip(-1);
    }

    /** @param pullShown the pull the cell shows - refreshRunScreen's figure, the higher one
     *        while the pump is not answering - or -1 to read it here. */
    void paintStrip(int pullShown) {
        if (stripHead == null) return;
        StripNow s = stripNow(System.currentTimeMillis());
        if (pullShown >= 0 && s.grid) s.v[QuickAdjust.PULL] = pullShown;
        // NEVER HIDDEN (owner report: the buttons without the card): with nothing playing yet
        // it keeps its place and says when the − / + come.
        setVisible(stripBox, true);
        setText(stripHead, s.mode == QuickAdjust.MODE_NONE ? QuickAdjust.HEAD_NONE : s.head);
        boolean work = s.grid;
        setVisible(stripMore, work);
        setVisible(stripGrid, work);
        setVisible(stripOne, s.lenField >= 0);
        boolean steps = s.mode == QuickAdjust.MODE_RAMP;
        setVisible(stripSteps, steps);
        if (steps) {
            int[] kn = RunEdit.stepOfSet(a.plan, a.planIdx);
            setText(stripStepsSaid, "Step " + kn[0] + " of " + kn[1]);
            String lessWhy = RunEdit.removeStepRefusal(a.plan, a.planIdx);
            String moreWhy = RunEdit.addStepRefusal(a.plan, a.planIdx);
            stripStepLess.setTextColor(lessWhy != null ? Look.STEP_KEY_AT_LIMIT : Ui.TEXT);
            stripStepMore.setTextColor(moreWhy != null ? Look.STEP_KEY_AT_LIMIT : Ui.TEXT);
            String ld = "Take the added step out" + (lessWhy != null ? ". " + lessWhy : "");
            String md = "Add a step at the ramp's top, step " + (kn[1] + 1)
                + (moreWhy != null ? ". " + moreWhy : "");
            if (!ld.contentEquals(orEmpty(stripStepLess.getContentDescription())))
                stripStepLess.setContentDescription(ld);
            if (!md.contentEquals(orEmpty(stripStepMore.getContentDescription())))
                stripStepMore.setContentDescription(md);
        }
        if (work)
            for (int i = 0; i < stripCells.length; i++) paintCell(stripCells[i], s);
        if (s.lenField >= 0) {
            stripLen.field = s.lenField;
            paintCell(stripLen, s);
        }
        // The journal's figures, in the form its watch compares with the wire.
        if (work && a.cellPull != null) {
            int[] w = a.ovTuple();
            String pu = Model.Fmt.p(s.v[QuickAdjust.PULL]);
            a.cellPull.setText(Say.valueWordOf(pu));
            a.cellPullUnit.setText(cellCaption(LiveEdit.UP, w, a.planIdx, Say.unitWordOf(pu)));
            if (s.dropLocked) {
                a.cellDrop.setText("—"); a.cellDropUnit.setText("hold only");
                a.cellDropT.setText("—"); a.cellDropTUnit.setText("hold only");
            } else {
                String dp = Model.Fmt.p(s.v[QuickAdjust.DROP]);
                a.cellDrop.setText(Say.valueWordOf(dp));
                a.cellDropUnit.setText(cellCaption(LiveEdit.LO, w, a.planIdx, Say.unitWordOf(dp)));
                a.cellDropT.setText(String.valueOf(s.v[QuickAdjust.DROP_TIME]));
                a.cellDropTUnit.setText(cellCaption(LiveEdit.LH, w, a.planIdx, "s"));
            }
            a.cellHold.setText(String.valueOf(s.v[QuickAdjust.HOLD]));
            a.cellHoldUnit.setText(cellCaption(LiveEdit.UH, w, a.planIdx, "s"));
        }
    }

    private void paintCell(StripCell c, StripNow s) {
        int f = c.field;
        boolean locked = s.grid && s.dropLocked
            && (f == QuickAdjust.DROP || f == QuickAdjust.DROP_TIME);
        setText(c.label, RunEdit.stripLabel(f));
        setText(c.value, locked ? "—" : QuickAdjust.value(f, s.v[f]));
        c.value.setTextColor(s.sending[f] || s.notConfirmed ? Ui.CMD : locked ? Ui.DIM : Ui.TEXT);
        String lessWhy = stripRefusal(s, f, -1), moreWhy = stripRefusal(s, f, 1);
        c.minus.setTextColor(lessWhy != null ? Look.STEP_KEY_AT_LIMIT : Ui.TEXT);
        c.plus.setTextColor(moreWhy != null ? Look.STEP_KEY_AT_LIMIT : Ui.TEXT);
        String shown = (locked ? "none" : QuickAdjust.value(f, s.v[f]))
            + (s.sending[f] ? ", sending" : s.notConfirmed ? ", not confirmed" : "");
        String name = QuickAdjust.SPOKEN[f];
        String less = "Less " + name + ", " + shown + " now" + (lessWhy != null ? ". " + lessWhy : "");
        String more = "More " + name + ", " + shown + " now" + (moreWhy != null ? ". " + moreWhy : "");
        if (!less.contentEquals(orEmpty(c.minus.getContentDescription())))
            c.minus.setContentDescription(less);
        if (!more.contentEquals(orEmpty(c.plus.getContentDescription())))
            c.plus.setContentDescription(more);
    }

    private static CharSequence orEmpty(CharSequence s) { return s == null ? "" : s; }

    private static void setText(TextView t, String s) {
        if (t != null && !s.contentEquals(t.getText())) t.setText(s);
    }

    private static void setVisible(View v, boolean on) {
        int want = on ? View.VISIBLE : View.GONE;
        if (v != null && v.getVisibility() != want) v.setVisibility(want);
    }

    /**
     * THE CEILING ON THE PULL RIGHT NOW, appended to the live pressure while a TRACTION
     * stage is running.
     *
     * It is derived from the MEASURED pressure rather than the commanded one, which is what
     * earns it a place on a screen that already shows the load on a card: a leak sags the
     * pressure and the ceiling sags with it. But measuring the PRESSURE does not measure the
     * TENSION - friction at the seal and the tissue's compliance still subtract, and nothing
     * here can see them - so it is written with Traction's short bound form rather than as a
     * bare figure. This is the one surface where an explainer is not allowed (no sheet over a
     * live run), so the symbol has to carry the whole claim on its own.
     *
     * On a girth stage there is no load the plan governs, so nothing is appended.
     *
     * S17 - THIS IS ONE LEG OF THE PRESSURE <-> LB CONVERSION, LIVE (Traction#loadLb,
     * pressure into load, off the SAME logged erect girth every other reading of it uses).
     * The other leg - load into pressure - is what the traction stage is already commanding:
     * RxBuild/Mint chose it via Traction#kpaForLb when the routine was minted, off the same
     * girth. Both legs read from Traction and both depend on the girth; this line just
     * cannot say so in words without becoming the explainer this surface refuses to be. The
     * Length setup screen (TrainerScreen#buildOnboardLengthPressure/#buildOnboardStep2)
     * says it in words, for the same two figures, before a routine is ever minted.
     * (Since 2026-09-30 both legs convert at the length cylinder's BORE, not a girth -
     * Traction#loadLbAtBore / #kpaForLbAtBore, the owner's decision.)
     */
    private String liveLoadSuffix(double measuredKpa, int stageIdx) {
        if (a.runRoutine == null) return "";
        Model.Stage st = (stageIdx >= 0 && stageIdx < a.runRoutine.stages.size())
            ? a.runRoutine.stages.get(stageIdx) : null;
        if (st == null || !st.traction) return "";
        // In the tube this stage pulls in, by its bore (owner, 2026-09-30) - not whichever is
        // marked for length now, which may be none (review I1: the readout went blank).
        double bore = Scale.stageBoreCm(a.model, st);
        if (bore <= 0 || measuredKpa <= 0) return "";
        return "  \u00b7  " + Traction.boundLbShort(Traction.loadLbAtBore(measuredKpa, bore));
    }

    private void startEcg() {
        a.ui.removeCallbacks(a.ecgFrame);
        a.ecgRunning = true;
        // Resolved ONCE here, alongside the tick itself getting armed — not on every
        // onDraw, which would mean a Settings.Global read 25x/second for the length of a
        // run. See ecgPulseMs's own doc for why this matches every other animation-scale
        // read in the codebase.
        a.ecgPulseMs = Look.resolveDuration(Look.MS_ENDPOINT_PULSE, a.animScale());
        a.ensureHalo();
        a.ui.postDelayed(a.ecgFrame, Trace.FRAME_MS);
    }

    private void openOverride() { openOverride(null); }

    /** @param say written on the new sheet's message line at once - what refused a gesture
     *        on the sheet this one replaces (OverrideApplyNow), else null. */
    private void openOverride(String say) {
        if (!a.canCommandNow()) {
            a.toast("Nothing is playing to adjust");
            return;
        }
        // A target still pending from the cells is what the sheet shows and edits - reseeding
        // here would throw it away (bug B1).
        a.seedOverrideIfIdle();
        a.ovHost = Ui.col(a);
        a.ovHost.setPadding(Ui.dp(a, 18), Ui.dp(a, 6), Ui.dp(a, 18), 0);
        fillOverrideSheet();
        // A RAMP SET fills ovHost with up to 10 sliders plus the Reshape button and both
        // notes — tall enough at default font scale, and taller still at a larger system
        // one, to push Apply/Revert/Close off the bottom of the dialog with no way to
        // reach them. Wrapped in a ScrollView exactly as showUsedSheet's sheets are, with
        // both clip flags cleared per Ui.lift's own rule so a lifted control's shadow is
        // not sliced off at the scroll edges.
        ScrollView sc = new ScrollView(a);
        sc.setClipToPadding(false);
        sc.setClipChildren(false);
        if (android.os.Build.VERSION.SDK_INT >= 29) sc.setEdgeEffectColor(Look.ACCENT);
        sc.addView(a.ovHost);
        /* ITS OWN MESSAGE LINE, above the scroll so it is on screen wherever the sheet is
         * scrolled to. What a control on the sheet has to say is written here (sheetSay),
         * never as a snack - that draws behind the sheet (invariant 140). */
        LinearLayout frame = Ui.col(a);
        a.ovSaid = sheetLine();
        a.ovSaid.setPadding(Ui.dp(a, 18), Ui.dp(a, Look.S2), Ui.dp(a, 18), 0);
        frame.addView(a.ovSaid);
        frame.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        writeOnSheet(a.ovSaid, say, true);
        a.ovDialog = Ui.dialog(a)
            .setTitle("Adjust the running set")
            .setView(frame)
            .setPositiveButton("Apply now", new OverrideApplyNow())
            .setNeutralButton("Revert", new OverrideRevert())
            .setNegativeButton("Close", null)
            .setCancelable(true)
            .show();
        a.holdRunSheet(a.ovDialog);     // closed when the plan ends (invariant 116)
        Ui.sheet(a, a.ovDialog);
    }

    private void fillOverrideSheet() {
        if (a.ovHost == null) return;
        a.ovHost.removeAllViews();
        Model.Preset p = (a.planIdx >= 0 && a.planIdx < a.plan.size()) ? a.plan.get(a.planIdx) : null;

        TextView which = new TextView(a);
        which.setText(p == null ? "—" : p.label);
        which.setTextColor(Ui.TEXT);
        which.setTextSize(Look.SP_BODY);
        a.ovHost.addView(which);

        /* polish S-RunAdjust: the one fact needed on the face - this run only - and how the
         * change is applied behind its info button, word for word. */
        Ui.noteInfo(a, a.ovHost, "This run only — the saved routine is not changed.",
            "Adjusting the running set", a.rampOfStepsAt(a.planIdx)
            ? "Applies to the pump now; the countdown doesn't restart. This run only — the saved "
              + "routine is not changed. It is for this step, or for the rest of this ramp — "
              + "the switch below says which."
            : "Applies to the pump now; the countdown doesn't restart. This run only — "
              + "the saved routine is not changed. It stays in force for the rest of this block "
              + "until you revert or the next block begins.");

        /* THE SCOPE (0.10 final - moved here from the NOW card, in the approved words): the rest
         * of this block (the default, and what every set boundary resets it to) or this set only.
         * One segmented control: one choice, not two actions. */
        boolean rampHere = a.rampOfStepsAt(a.planIdx);
        a.scopeSwitch = Ui.segmented(a, null,
            QuickAdjust.scopeNames(rampHere),
            rampHere
                ? new String[]{ "A change here moves the rest of this ramp by the same amount",
                                "A change here is for this step only; the ramp's later steps "
                                + "keep their plan" }
                : new String[]{ "A change here is for the rest of this block",
                                "A change here is for this set only; the next set plays the plan" },
            a.ovScopeRep ? 1 : 0,
            new View.OnClickListener[]{ new ScopeTap(false), new ScopeTap(true) });
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.bottomMargin = Ui.dp(a, 8);
        a.ovHost.addView(a.scopeSwitch.view, scLp);

        // ONE PULL LIMIT EVERYWHERE: the strip's (QuickAdjust#pullCeiling - 10.0 inHg, or the
        // safety ceiling when lower), or where the pull already is when that is higher.
        // polish RN-7: shown under the names the strip and the Library use (sheetName).
        overrideSlider(OvSlide.UP, "pull", Model.Fmt.p(a.ovUp), a.ovUp, Math.max(1, sheetPullCap()));
        // The drop goes as far as the strip's Drop + does: 1.0 inHg under the pull, past the
        // floor once confirmed (sheetDrop), or where it already is when that is higher.
        overrideSlider(OvSlide.LO, "drop", Model.Fmt.p(a.ovLo), a.ovLo,
                       Math.max(1, sheetDropCap()));
        overrideSlider(OvSlide.UH, "hold", a.ovUh + " s", a.ovUh, 255);
        overrideSlider(OvSlide.LH, "drop time", a.ovLh + " s", a.ovLh, 255);
        overrideSlider(OvSlide.SP, "speed", a.ovSp + "%", a.ovSp, 100);

        fillRampSection();

        // The ceiling stays on the face (safety); how a change lands mid-hold is behind the info.
        Ui.noteSafety(a, a.ovHost, "The drop is always kept below the pull, and neither can pass "
            + "the safety ceiling of " + Model.Fmt.p(a.model.ceilKpa) + " — the figures above are "
            + "the ones that will actually be sent.");
        Ui.noteInfo(a, a.ovHost, "Tap a figure to type it.", "Changing figures mid-hold",
            "Tap a figure to type it. A change carries the hold under way on from where it is — "
            + "it is never started again — and the block's end moves by what it adds; in the "
            + "drop, it goes in with the next hold. The − / + on the run screen change the same "
            + "figures a tap at a time, and a rest's, a ramp step's or the warm-up's length.");
        // What this drawing stands for - the step, and the NOW figures the sliders show.
        a.ovSheetIdx = a.planIdx;
        a.ovSheetShows = a.ovTuple();
        a.ovSheetEnd = a.planEndTuple();
    }

    /**
     * IS THE ADJUST SHEET STILL DRAWN FOR WHAT A GESTURE ON IT WOULD CHANGE? (safety review
     * of D1; WiringCheck invariant 46.) Every control on the sheet sets an ABSOLUTE figure
     * picked against what it shows. A sheet left open while the step changed, or after the
     * live edit it showed was refused, shows something else: a small leftward drag on the
     * pull slider of a sheet drawn on a −3.8 inHg step, left open into a −2.1 one, sent
     * −3.6 - a gesture toward less pull that raised the pump. So when the sheet is not
     * current the gesture is refused, nothing is sent, the sheet is redrawn from what is in
     * force (or the target still pending) and the person is told to check it.
     */
    private boolean sheetCurrent() {
        if (sheetShowsNow()) return true;
        String why = staleWhy(a.ovSheetIdx);
        redrawSheet();
        sheetSay(why + " — nothing was sent. Check the figures.", true);
        return false;
    }

    /**
     * ...AND FOR THE RAMP END CONTROLS (Reshape, the end sliders and a typed end):
     * the sheet's END copy must still be the end the ramp has now (safety review of D1,
     * finding 2). A sheet left open into another ramp reshaped that ramp up to the old one's
     * end; a "This set" change moves the whole remaining ramp, its end included, and "smaller
     * steps - the end comes down" from the old copy lifted the end back up past it.
     */
    private boolean sheetEndCurrent() {
        if (!sheetCurrent()) return false;
        if (LiveEdit.viewCurrent(a.ovSheetIdx, a.ovSheetEnd, a.planIdx, a.planEndTuple())) return true;
        redrawSheet();
        sheetSay("This ramp's end has moved since the sheet was drawn — nothing was "
            + "changed. Check the figures.", true);
        return false;
    }

    private boolean sheetShowsNow() {
        return LiveEdit.viewCurrent(a.ovSheetIdx, a.ovSheetShows, a.planIdx,
            a.liveEdit.startFrom(a.ovTuple(), a.inForceTuple(), a.planIdx));
    }

    private void redrawSheet() {
        if (a.planIdx >= 0 && a.planIdx < a.plan.size()) a.seedOverrideIfIdle();
        fillOverrideSheet();
    }

    /**
     * NOTHING ON THE RAMP CHANGES UNDER A HOLD (safety review of D1, round 2; WiringCheck
     * invariant 48). Reshape and the step count rewrite the pump's table from the next
     * step - the hold's own entry included (the simulator stops, and so vents, when the entry
     * it is running is deleted) - and a shift is an adjustment that would be written over the
     * hold. The sheet can be open during a hold, so each of them asks first. Says so above the
     * sheet and returns true when it refused.
     */
    private boolean refuseWhileHolding() {
        // Nor under a START that may still be answered, or waits to be written, or a
        // convergence (invariant 155): the reshape and the recount rewrite the table.
        String waits = a.ctl.editWaits(android.os.SystemClock.elapsedRealtime());
        if (waits != null && !a.holdMayBeUp()) {
            sheetSay(waits, true);
            return true;
        }
        // A Hold that MAY be up - written and not ruled out, or released and not yet known to
        // be off - is refused like one that is (the review of refusal-2, LOW).
        if (!a.holdMayBeUp()) return false;
        sheetSay("Paused \u2014 resume first. The ramp was not changed.", true);
        return true;
    }

    /** Why a view is no longer current, for the message that refuses its gesture. */
    private String staleWhy(int viewIdx) {
        if (viewIdx != a.planIdx && a.planIdx >= 0 && a.planIdx < a.plan.size())
            return a.plan.get(a.planIdx).label + " is playing now";
        return "What the pump has changed since then";
    }

    /** What sheetSay last said - carried onto the sheet OverrideApplyNow opens in place of
     *  the one it closed. */
    private String lastSheetSaid;

    /** A message for someone looking at the adjust sheet: written ON it, on its message
     *  line (a snackbar draws behind the sheet's scrim, unseen, and a Toast is cut to two
     *  lines on Android 12 and later), journalled like every other message. With no adjust
     *  sheet showing, Ui.say - it draws in its own window above whatever is up, and on the run
     *  screen that is the app's own message near the top, never a system Toast over STOP. */
    /** The pump's answer to a live change (SessionActivity#changeOutcome): written on the
     *  adjust sheet while it is open, where the change was most likely made, else shown. */
    void sayOfChange(String s) {
        sheetSay(s, true);
    }

    private void sheetSay(String s, boolean important) {
        lastSheetSaid = s;
        if (a.ovDialog != null && a.ovDialog.isShowing() && a.ovSaid != null) {
            writeOnSheet(a.ovSaid, s, important);
            return;
        }
        a.journalSnack(s);
        Ui.say(a, s, important);
    }

    /**
     * EVERY TICK: a sheet still open after the step changed, or after the live edit it showed
     * ended without being sent, is redrawn, so what it shows is what a gesture on it would
     * change. The gesture guard (sheetCurrent) stays - this only narrows the window to one
     * tick, and a gesture landing inside it is still refused.
     */
    void refreshSheetIfStale() {
        if (a.ovDialog == null || !a.ovDialog.isShowing() || a.ovHost == null) return;
        if (sheetShowsNow() && java.util.Arrays.equals(a.ovSheetEnd, a.planEndTuple())) return;
        boolean otherStep = a.ovSheetIdx != a.planIdx;
        String why = staleWhy(a.ovSheetIdx);
        redrawSheet();
        if (otherStep) sheetSay(why + " — the sheet shows it", false);
    }

    /**
     * THE RAMP SECTION — shown only while the running set has steps still to come.
     *
     * A ramp adjusted through the five sliders above FLATTENS: the adjustment carries for
     * the rest of the stage, so every remaining step plays that one preset and the climb
     * stops climbing (RunEdit's reshape note). That is the right behaviour for a fixed set
     * and the wrong one for a ramp, so a ramp gets a second, honest control: the END, with
     * the remaining steps re-interpolated FROM WHERE THE RAMP IS NOW to it.
     *
     * The "now" line is deliberately READ-ONLY here. It is the same five values the sliders
     * above edit, and giving them a second set of controls in the same sheet would be two
     * ways to change one thing — the caption says which control does which instead.
     */
    private void fillRampSection() {
        int steps = RunEdit.remainingStepsOfSet(a.plan, a.planIdx);
        if (steps <= 0) return;

        /* polish RN-7 / NEW-22: "Ramp — 4 holds left" in sentence case, with how a reshape
         * works behind its info button; the monospace "now ..." line that repeated the
         * sliders is gone. */
        View rampHead = Ui.noteInfo(a, a.ovHost,
            "Ramp — " + steps + (steps == 1 ? " hold left" : " holds left"), "Ramp",
            rampSheetCaption());
        rampHead.setPadding(0, Ui.dp(a, 14), rampHead.getPaddingRight(),
            rampHead.getPaddingBottom());

        overrideSlider(OvSlide.E_UP, "end pull", Model.Fmt.p(a.ovEndUp), a.ovEndUp,
                       Math.max(1, sheetEndPullCap()));
        overrideSlider(OvSlide.E_LO, "end drop", Model.Fmt.p(a.ovEndLo), a.ovEndLo,
                       Math.max(1, sheetEndDropCap()));
        overrideSlider(OvSlide.E_UH, "end hold", a.ovEndUh + " s", a.ovEndUh, 255);
        overrideSlider(OvSlide.E_LH, "end drop time", a.ovEndLh + " s", a.ovEndLh, 255);
        overrideSlider(OvSlide.E_SP, "end speed", a.ovEndSp + "%", a.ovEndSp, 100);

        // Built through Ui.flat rather than by hand, so it inherits the 48dp floor,
        // Look.SP_LABEL sizing and Ui.lift every other flat control in the app gets — it
        // used to sit at 44dp/SP_CAPTION, under floor at larger font scale.
        Button go = Ui.secondary(a, a.ovHost, "Reshape the remaining holds");
        go.setContentDescription("Reshape the " + steps + " remaining holds of this ramp");
        go.setOnClickListener(new RampReshapeTap());
        ((LinearLayout.LayoutParams) go.getLayoutParams()).topMargin = Ui.dp(a, 6);

        /* ONE "REST OF THIS RAMP" SECTION (0.10): the end values, Reshape and the step count.
         * The sheet's "shift whole ramp ±" and "step size ±" are gone - the strip's − / + with
         * "Rest of this ramp" is the shift (and the shift button quietly flipped the scope),
         * and the end values are the step size. STEP COUNT (wave-4 wrap, owner accepted the
         * resize risk) re-divides the remaining time into a different number of steps -
         * SessionActivity#resizeRemainingRamp owns that mid-run plan resize. */

        LinearLayout crow = new LinearLayout(a);
        crow.setOrientation(LinearLayout.HORIZONTAL);
        crow.setGravity(Gravity.CENTER_VERTICAL);
        TextView cl = new TextView(a);
        cl.setText("Holds left  " + steps);
        cl.setTextColor(Ui.DIM);
        cl.setTextSize(Look.SP_CAPTION);
        crow.addView(cl, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button cM = Ui.compactStep(a, "−"), cP = Ui.compactStep(a, "+");
        crow.addView(cM); crow.addView(cP);
        cM.setContentDescription("Fewer, longer remaining steps — same total time");
        cP.setContentDescription("More, shorter remaining steps — same total time");
        cM.setOnClickListener(new RampStepCountTap(-1));
        cP.setOnClickListener(new RampStepCountTap(1));
        a.ovHost.addView(crow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** What the ramp's section of the sheet does, in the words of the scope it is in. */
    private String rampSheetCaption() {
        return "The figures above change the step playing"
            + (a.ovScopeRep ? " only — the later steps keep their plan (Rest of this ramp "
                            + "moves them with it)." : ", and every step after it by the same "
                            + "amount (Rest of this ramp).")
            + " The end below reshapes the steps after this one, from here to the new end; "
            + "the step count re-divides them.";
    }

    /** Step count ± : re-divides the remaining climb into one more / one fewer step,
     *  time and end values kept. The one mid-run plan resize — see
     *  SessionActivity#resizeRemainingRamp. */
    private final class RampStepCountTap implements View.OnClickListener {
        private final int dir;
        RampStepCountTap(int d) { dir = d; }
        @Override public void onClick(View v) {
            if (refuseWhileHolding()) return;           // invariant 48
            if (!sheetCurrent()) return;     // the count shown is another ramp's (invariant 46)
            int steps = RunEdit.remainingStepsOfSet(a.plan, a.planIdx);
            // Every answer ON the sheet - a snack would draw behind it (invariant 140).
            if (steps <= 0) { sheetSay("The ramp's last step is playing — nothing to recount", true); return; }
            int want = steps + dir;
            if (want < 1) { sheetSay("At least one step has to remain", true); return; }
            // The pump's table: the ramp, done and to come, is never more than its steps.
            int done = RunEdit.stepOfSet(a.plan, a.planIdx)[0];
            if (done + want > ComingSteps.RAMP_STEPS_MAX) {
                sheetSay("The pump takes at most " + ComingSteps.RAMP_STEPS_MAX + " steps.", true);
                return;
            }
            String said = a.resizeRemainingRamp(want);
            fillOverrideSheet();
            sheetSay(said, false);
        }
    }

    /** One slider row: label, track, and a tap-to-type figure. */
    private void overrideSlider(int field, String label, String shown, int value, int max) {
        label = sheetName(field, label);
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);

        TextView l = new TextView(a);
        l.setText(label);
        l.setTextColor(Ui.DIM);
        l.setTextSize(Look.SP_CAPTION);
        // Wide enough for "Suction power" (polish RN-7's names).
        r.addView(l, new LinearLayout.LayoutParams(Ui.dp(a, 84),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar sb = new SeekBar(a);
        sb.setMax(Math.max(1, max));
        sb.setProgress(Math.max(0, Math.min(max, value)));
        sb.setContentDescription(A11y.value(label, shown));
        sb.setOnSeekBarChangeListener(new OvSlide(field));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,
                Ui.dp(a, 48), 1f);
        r.addView(sb, sp);

        Button v = new Button(a);
        v.setText(shown);
        v.setAllCaps(false);
        v.setTextColor(Ui.TEXT);
        Ui.tabular(v);   // polish SYS-9: the tabular sans for a value, not monospace
        v.setTextSize(Look.SP_CAPTION);
        v.setBackground(Ui.roundRect(a, Ui.SURFHI, 9));
        v.setMinWidth(0); v.setMinimumWidth(Ui.dp(a, 62));
        v.setPadding(0, 0, 0, 0);
        v.setContentDescription("Type " + label + ", currently " + A11y.collapse(shown));
        v.setOnClickListener(new OvTypeTap(field, label, true));
        v.setOnTouchListener(new Ui.Press());
        r.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Ui.dp(a, 48)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 2);
        a.ovHost.addView(r, lp);
    }

    /** The highest pull the sheet dials for the step playing: the strip's limit
     *  (QuickAdjust#pullCeiling), or the pull in force when that is higher already. */
    private int sheetPullCap() {
        // (0.10: the strip's limit now - its 10.0 inHg, or past it as far as the person has
        // confirmed this run on the strip, never past the hard limit - QuickAdjust#pullLimit.)
        return Math.max(a.stripPullCapKpa(), a.inForceTuple()[LiveEdit.UP]);
    }

    /** The same for the ramp's end: the strip's limit, or the end's own pull when higher. */
    private int sheetEndPullCap() {
        int[] end = a.planEndTuple();
        return Math.max(a.stripPullCapKpa(), end == null ? 0 : end[LiveEdit.UP]);
    }

    /** The highest drop the step playing may be given: 1.0 inHg under the sheet's pull
     *  (RunEdit#dropTopKpa - the strip's Drop + and Coming steps' rule, owner 2026-09-30), or
     *  the step's own highest when that is higher already (RunEdit#dropCapOf) - but never past
     *  the gap under the pull the sheet shows (RunEdit#dropKept, review I4/I5: a pull dragged
     *  down left the slider a drop the apply would refuse). */
    private int sheetDropCap() {
        Model.Preset p = a.planIdx >= 0 && a.planIdx < a.plan.size() ? a.plan.get(a.planIdx) : null;
        int up = RunEdit.clampUpper(a.ovUp, a.model.ceilKpa);
        return RunEdit.dropKept(Math.max(RunEdit.dropCapOf(p, a.inForceTuple()[LiveEdit.LO]),
                                         RunEdit.dropTopKpa(up)), up, false);
    }

    /** The ramp's last remaining step - the end the sheet's END controls reshape to - or null. */
    private Model.Preset sheetEndStep() {
        int left = RunEdit.remainingStepsOfSet(a.plan, a.planIdx);
        return left > 0 ? a.plan.get(a.planIdx + left) : null;
    }

    /** The same for the ramp's end: under the end's pull, or its last step's own highest. */
    private int sheetEndDropCap() {
        Model.Preset last = sheetEndStep();
        int[] end = a.planEndTuple();
        int up = RunEdit.clampUpper(a.ovEndUp, a.model.ceilKpa);
        return RunEdit.dropKept(Math.max(RunEdit.dropCapOf(last, end == null ? 0 : end[LiveEdit.LO]),
                                         RunEdit.dropTopKpa(up)), up, false);
    }

    /** The sheet's drop warning, while it is up: further drags wait for its answer. */
    private boolean sheetDropAsking;

    /**
     * A DROP DIALLED ON THE SHEET (the step's, or the ramp end's - a slider or a typed figure),
     * held as the strip's Drop + holds it (owner, 2026-09-30): never past `cap` (sheetDropCap /
     * sheetEndDropCap: 1.0 inHg under the pull); from at or under the floor to above it, asked
     * once a run (RunEdit#dropNeedsWarning, the same "A higher drop?") - the drop stays where it
     * was until the answer, and "Keep it" puts the figure asked for. Allowed past the step's
     * highest, the raise becomes the person's own on the set playing only when it is APPLIED
     * (SessionActivity#allowDropRaiseToApply, the settle and Apply now; a reshape raises the
     * end's) - never as the slider moves, so Revert, Close or a refused settle leave the set's
     * highest drop where it was (review M2).
     */
    private int sheetDrop(int field, int raw, int from, int cap) {
        int want = Math.min(Math.max(0, raw), cap);
        if (RunEdit.dropNeedsWarning(from, want, a.dropWarned)) {
            if (!sheetDropAsking) {
                sheetDropAsking = true;
                AlertDialog d = a.holdRunSheet(Ui.dialog(a)
                    .setTitle(RunEdit.DROP_WARN_TITLE)
                    .setMessage(RunEdit.dropWarning())
                    .setPositiveButton(RunEdit.DROP_WARN_GO, new ConfirmSheetDrop(field, want))
                    .setNegativeButton(RunEdit.DROP_WARN_BACK, null)
                    .show());
                Ui.dress(a, d);
                d.setOnDismissListener(new SheetDropAsked());
            }
            return from;
        }
        return want;
    }

    /** The sheet's drop warning answered "keep it": remembered for this run, then the figure. */
    private final class ConfirmSheetDrop implements DialogInterface.OnClickListener {
        private final int field, want;
        ConfirmSheetDrop(int f, int w) { field = f; want = w; }
        @Override public void onClick(DialogInterface d, int w) {
            a.dropWarned = true;
            sheetDropAsking = false;
            setOverrideField(field, want);
            fillOverrideSheet();
            if (!isRampEndField(field)) overrideChanged();
        }
    }

    /** The sheet's drop warning closed, however: the next drag may ask again (if not kept). */
    private final class SheetDropAsked implements DialogInterface.OnDismissListener {
        @Override public void onDismiss(DialogInterface d) { sheetDropAsking = false; }
    }

    /** Puts one field of the working copy to `raw`, re-clamping the pair through
     *  uploadBatch()'s own two lines so the sheet can never show a value the wire would
     *  rewrite. Returns nothing — the caller repaints. */
    private void setOverrideField(int field, int raw) {
        if (!isRampEndField(field)) {
            // THE NOW FIELDS go through LiveEdit#set - the pull under the ceiling and the
            // drop under the pull, the hold 1..255 s, the drop time 0..255 s (0 s means "do
            // not dwell at the bottom" and the wire carries it), the speed 0..100 % - the
            // same clamps a − / + tap passes, so no editor can send a figure another would not.
            // And it starts from what is IN FORCE unless an edit is pending for the step
            // playing (LiveEdit#startFrom) - the sheet seeds when it opens, and one left open
            // across a step change would otherwise send the old step's other four figures
            // with the one field moved (seen on the emulator: a drop slider raised the pull).
            int[] w = a.liveEdit.startFrom(a.ovTuple(), a.inForceTuple(), a.planIdx);
            // THE DROP'S LIMIT (sheetDrop): 1.0 inHg under the pull, past the floor once
            // confirmed - a slider, a typed figure or a tap alike.
            if (field == OvSlide.LO) raw = sheetDrop(field, raw, w[LiveEdit.LO], sheetDropCap());
            if (field == OvSlide.UP) raw = Math.min(raw, sheetPullCap());
            LiveEdit.set(w, field, raw, a.model.ceilKpa);
            a.ovFrom(w);
        } else if (field == OvSlide.E_UP) {
            a.ovEndUp = RunEdit.clampUpper(Math.min(Math.max(0, raw), sheetEndPullCap()), a.model.ceilKpa);
            // The end's drop follows its pull down, as the reshape will write it (RunEdit#dropKept).
            a.ovEndLo = RunEdit.dropKept(sheetEndStep(), a.ovEndLo, a.ovEndUp);
        } else if (field == OvSlide.E_LO) {
            a.ovEndLo = RunEdit.dropKept(sheetEndStep(),
                sheetDrop(field, raw, a.ovEndLo, sheetEndDropCap()), a.ovEndUp);
        } else if (field == OvSlide.E_UH) {
            a.ovEndUh = RunEdit.clampSeconds(raw);
        } else if (field == OvSlide.E_LH) {
            a.ovEndLh = RunEdit.clampSeconds(raw);
        } else {
            a.ovEndSp = RunEdit.clampSpeed(raw);
        }
    }

    private final class OvSlide implements SeekBar.OnSeekBarChangeListener {
        /** The "now" fields are LiveEdit's own numbering, so a field means one thing in
         *  both places. */
        static final int UP = LiveEdit.UP, LO = LiveEdit.LO, UH = LiveEdit.UH,
                         SP = LiveEdit.SP, LH = LiveEdit.LH;
        /** The RAMP END fields. They edit the PLAN, not the pump, so they are deliberately
         *  above every "now" field: {@link SessionActivity#isRampEndField} is the single
         *  test that keeps them out of the debounce that writes to the device. */
        static final int E_UP = 5, E_LO = 6, E_UH = 7, E_LH = 8, E_SP = 9;
        private final int field;
        OvSlide(int f) { field = f; }
        @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
            if (!fromUser) return;
            // The position is ABSOLUTE, picked against what the sheet shows: refused, the
            // sheet redrawn, when that is no longer what a change would start from (46).
            if (!(isRampEndField(field) ? sheetEndCurrent() : sheetCurrent())) return;
            setOverrideField(field, progress);
            fillOverrideSheet();
            // The pump hears only the settled value — see openOverride()'s doc. A RAMP END
            // slider commands NOTHING on its own: it describes where the tail should
            // finish, and only "Reshape the remaining steps" acts on it.
            if (!isRampEndField(field)) overrideChanged();
        }
        @Override public void onStartTrackingTouch(SeekBar sb) { }
        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }

    /** Tap-to-type. A slider is fast and imprecise; typing is precise and slow. The typed
     *  value goes through exactly the same clamps the slider does. */
    private final class OvTypeTap implements View.OnClickListener {
        private final int field;
        private final String label;
        /** Opened from the adjust sheet (true) or from a run-screen cell. */
        private final boolean fromSheet;
        OvTypeTap(int f, String l, boolean sheet) { field = f; label = l; fromSheet = sheet; }
        @Override public void onClick(View v) {
            if (a.isFinishing()) return;
            // A figure typed against a sheet drawn for another step is picked against the
            // wrong picture: redrawn first, and nothing opened (invariant 46).
            if (fromSheet && !(isRampEndField(field) ? sheetEndCurrent() : sheetCurrent())) return;
            // Inline cells reach this without openOverride() in front of it, so reseed
            // exactly as a nudge would — a stale working copy re-sent whole is how an
            // old pressure would ride along with a typed hold. A pending target is kept:
            // the typed figure lands on top of it (LiveEdit#needsSeed).
            a.seedOverrideIfIdle();
            final EditText e = new EditText(a);
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            e.setTextColor(Ui.TEXT);
            e.setHint(label);
            Ui.dress(a, a.holdRunSheet(Ui.dialog(a)
                .setTitle(label)
                .setView(e)
                .setPositiveButton("Set", new OvTypeSet(field, e, fromSheet, a.planIdx,
                    a.liveEdit.startFrom(a.ovTuple(), a.inForceTuple(), a.planIdx),
                    isRampEndField(field) ? a.planEndTuple() : null))
                .setNegativeButton("Cancel", null)
                .show()));
        }
    }

    /**
     * A TYPED FIGURE is absolute and typed against what was showing when the box opened -
     * "a bit lower than −3.8". It is taken only if that still holds when Set is pressed: the
     * same step, and the same figures an edit would start from (not refused or superseded
     * meanwhile). Otherwise nothing is set and the person is told what is playing now
     * (invariant 46).
     */
    private final class OvTypeSet implements DialogInterface.OnClickListener {
        private final int field;
        private final EditText e;
        private final boolean fromSheet;
        private final int viewIdx;
        private final int[] shows;
        /** For a ramp END field: the end the ramp had when the box opened, else null. */
        private final int[] endShows;
        OvTypeSet(int f, EditText edit, boolean sheet, int idx, int[] showing, int[] end) {
            field = f; e = edit; fromSheet = sheet; viewIdx = idx; shows = showing; endShows = end;
        }
        @Override public void onClick(DialogInterface d, int w) {
            if (!LiveEdit.viewCurrent(viewIdx, shows, a.planIdx,
                    a.liveEdit.startFrom(a.ovTuple(), a.inForceTuple(), a.planIdx))
                || (endShows != null && !java.util.Arrays.equals(endShows, a.planEndTuple()))) {
                String why = staleWhy(viewIdx) + " — that figure was not set. Check it and "
                    + "type it again.";
                if (fromSheet) { redrawSheet(); sheetSay(why, true); }
                else a.toast(why);
                return;
            }
            try {
                // Typed in whatever unit the app is DISPLAYING, so a pressure comes back
                // through the same converter every other typed pressure uses.
                int raw = Integer.parseInt(e.getText().toString().trim());
                if (field == OvSlide.UP || field == OvSlide.LO
                    || field == OvSlide.E_UP || field == OvSlide.E_LO) raw = Math.abs(raw);
                setOverrideField(field, raw);
                fillOverrideSheet();
                if (!isRampEndField(field)) overrideChanged();
            } catch (NumberFormatException ex) {
                // Under the typed box is the adjust sheet when it was opened from one: said on
                // it, not in a snack behind it (invariant 140). From a cell, the run screen.
                if (fromSheet) sheetSay("Not a number — nothing changed", true);
                else a.toast("Not a number — nothing changed");
            }
        }
    }

    private final class OpenOverrideTap implements View.OnClickListener {
        @Override public void onClick(View v) { openOverride(); }
    }

    private final class OverrideApplyNow implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            // The button has already closed the sheet: when what it showed no longer holds,
            // nothing is sent and the sheet comes back, drawn for what is playing (46). Asked
            // before the settle is cancelled, so a refusal abandons nothing (invariant 17).
            if (!sheetCurrent()) { openOverride(lastSheetSaid); return; }
            a.ui.removeCallbacks(a.ovDebounce);
            // A pending target is what is applied; with nothing pending, what is in force for
            // the step playing now - never figures the sheet was opened with on an earlier step.
            a.ovFrom(a.liveEdit.startFrom(a.ovTuple(), a.inForceTuple(), a.planIdx));
            // A drop raised on the sheet is the person's as it is applied (review M2).
            int[] stamps = a.allowDropRaiseToApply(a.ovLo);
            if (!a.applyOverride(a.ovUp, a.ovLo, a.ovUh, a.ovLh, a.ovSp, "manual adjustment"))
                a.putDropStamps(stamps);
        }
    }

    private final class RampReshapeTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (refuseWhileHolding()) return;           // invariant 48
            if (!sheetEndCurrent()) return;  // the end is the ramp's end (46, finding 2)
            applyRampReshape();
        }
    }

    /**
     * RESHAPE THE REMAINING STEPS of the running ramp, from where it is NOW to the end the
     * user just set. This is a PLAN edit and nothing else: the same mutation-then-rewrite
     * applyEditUpcoming() makes, over a run of presets instead of one.
     *
     * THE RUNNING STEP IS NOT TOUCHED. RunEdit#reshapeRemaining starts at planIdx + 1 and
     * rewriteTableKeepingCountdown(planIdx + 1) is exactly the re-upload an upcoming edit
     * uses — the pump keeps cycling the preset it was started on out of its deleted table
     * entry, and the countdown is put back for the time that was really left. So nothing
     * jumps: the ramp finishes the step the user is under and climbs the new tail from
     * there.
     *
     * THE ANCHOR IS WHAT IS IN FORCE, not what the plan says: the carry* snapshot (what
     * the pump was GIVEN) while an adjustment carries into the running step, else the
     * running preset clamped as it was sent. Anchoring on the plan while the cuff is
     * somewhere else would make the first reshaped step a jump rather than a continuation.
     *
     * AND IT CLEARS THE CARRY (ovStage = -1). The two mechanisms answer the same question
     * and must not both answer it: a carried adjustment REPLACES every remaining preset of
     * the stage with one fixed preset, which is precisely the flattening a reshape exists
     * to undo — leaving it in force would mean the user reshaped a tail that then never
     * played. The plan is now what the user asked for, so the plan is what plays, and the
     * reshaped steps go out as honest PLAN rows. The running step keeps the adjustment it
     * is already under (the pump is not re-armed here), which is why the carry is dropped
     * rather than reverted. The other direction is unchanged: moving a "now" slider on a
     * ramp still carries exactly as it always did.
     */
    private void applyRampReshape() {
        if (refuseWhileHolding()) return;                 // invariant 48
        // Its answers go on the sheet it is pressed on (invariant 140).
        if (!a.canCommandNow()) {
            sheetSay("Nothing is playing to reshape", true);
            return;
        }
        // A change still on its way would go in force AFTER the reshape - the carry back, the
        // tail it shaped shifted - so it waits for the pump's answer (the pump-refusal fix).
        if (a.pendingChange.pending(PendingChange.EDIT)) {
            sheetSay("A change is still on its way to the pump — reshape again in a moment.",
                     true);
            return;
        }
        int steps = RunEdit.remainingStepsOfSet(a.plan, a.planIdx);
        if (steps <= 0) {
            sheetSay("No steps left in this ramp to reshape", true);
            return;
        }
        // A NOW slider nudged within the debounce window and then Reshape tapped: the
        // settle would fire AFTER the reshape, re-arm the carry unbounded (applyOverride
        // writes ovStage and clears the bound) and flatten the tail the user just shaped.
        // Cancelled first, before anything is read, for the same reason revertOverride()
        // and finishSession() cancel it.
        a.ui.removeCallbacks(a.ovDebounce);
        // ...AND THE LIVE EDIT THAT SETTLE WAS GOING TO SEND ENDS WITH IT (D1). Cancelling
        // the job alone left the edit DIALLING with nothing left to send it: the cell said
        // "sending…" to the end of the step, and the next edit built on the dead figure - a
        // pull − from a dialled 30 over the 20 in force wrote 29, a MINUS that RAISED the
        // pressure. So, as enterHold() does: note whether a change was being dialled (said
        // below), end the edit, and put the NOW working copy back to what is in force, so
        // the sheet refilled below and every later edit start from what the pump has.
        // ovFrom(inForceTuple()), not loadOverrideFromRunning(): that also re-reads the ramp
        // END copy from the plan, and would throw away the end the user is reshaping to.
        boolean dropped = a.liveEdit.dialling(a.planIdx);
        a.liveEdit.refused();
        a.ovFrom(a.inForceTuple());
        // What is IN FORCE on the running step — the carried tuple when one carries, else
        // the plan's own preset under the same clamps it was sent with.
        boolean carries = a.overrideCarriesNow();
        Model.Preset cur = a.plan.get(a.planIdx);
        int aUp = carries ? a.carryUp : RunEdit.clampUpper(cur.up, a.model.ceilKpa);
        int aLo = carries ? a.carryLo : RunEdit.dropKept(cur, cur.lo, aUp);
        int aUh = carries ? a.carryUh : cur.uh;
        int aLh = carries ? a.carryLh : cur.lh;
        int aSp = carries ? a.carrySp : cur.sp;

        // THE ONE RULE ON THE RESHAPE TOO: no step's cycle outgrows the step (QuickAdjust).
        int rMode = QuickAdjust.modeAt(a.model, a.runRoutine, a.plan, a.planIdx, a.restingNow);
        String tooLong = QuickAdjust.reshapeCycleRefusal(a.plan, a.planIdx, aUh, aLh,
            a.ovEndUh, a.ovEndLh, QuickAdjust.LABEL[rMode == QuickAdjust.MODE_WARM
                ? QuickAdjust.WARM : QuickAdjust.STEP_TIME]);
        if (tooLong == null)
            tooLong = QuickAdjust.dropRefusal(a.planEndTuple()[LiveEdit.LO], a.ovEndLo, sheetEndDropCap());
        if (tooLong == null && a.ovEndUp > sheetEndPullCap())
            tooLong = Model.Fmt.p(sheetEndPullCap()) + " is the most the end may pull.";
        if (tooLong != null) {
            sheetSay(tooLong + " Nothing was reshaped.", true);
            fillOverrideSheet();
            return;
        }
        // The end's drop, raised on the sheet past the steps' highest, is the person's as the
        // reshape is applied (review M2: no longer as the slider moved).
        if (a.ovEndLo > RunEdit.DROP_FLOOR_KPA) a.allowDropRaise(a.ovEndLo);
        int n = RunEdit.reshapeRemaining(a.plan, a.planIdx, aUp, aLo, aUh, aLh, aSp,
                                         a.ovEndUp, a.ovEndLo, a.ovEndUh, a.ovEndLh, a.ovEndSp,
                                         a.model.ceilKpa);
        // The reshaped steps end as their own cycles end (0.10 follow-up): whole cycles of each
        // one's new hold + drop, nearest its length, at least one (wholeCyclesAfter).
        a.wholeCyclesAfter(n);
        // The "This set" shift's base described the tail this just replaced: dropped, so the
        // next set-scope ± moves the reshaped steps by its own change and never rebuilds the
        // old tail (safety review, finding 3: a pull − after a reshape to a lower end put a
        // 36 kPa last step back). SetShift would notice the edit on its own; this says so here.
        a.setShift.forget();
        a.log("--- RESHAPE: " + n + " remaining step(s) of " + cur.label + " re-anchored from "
            + Model.Fmt.p(aUp) + " / " + Model.Fmt.p(aLo) + " " + aUh + "/" + aLh + " s "
            + aSp + "% to " + Model.Fmt.p(a.ovEndUp) + " / " + Model.Fmt.p(a.ovEndLo) + " "
            + a.ovEndUh + "/" + a.ovEndLh + " s " + a.ovEndSp + "% (this run only, not saved) ---");
        // See the doc: the carry and the reshape answer the same question, so applying one
        // drops the other. Cleared BEFORE the rewrite, so playPreset() finds no carry when
        // the first reshaped step starts.
        if (carries) {
            // BOUNDED, NOT DROPPED — see RunEdit#carryActiveAt. The pump was never re-armed
            // for the step under way, so it is still cycling the carried preset: dropping
            // the carry outright made the run screen describe a step the wire was not
            // playing. The bound keeps the carry alive for the RUNNING index only; the
            // reshaped steps (planIdx + 1 onwards) find no carry in playPreset and go out
            // as honest PLAN rows.
            a.ovEndsAfterIdx = a.planIdx;
            a.log("--- the carried adjustment now ends with this step: the reshaped plan is "
                + "what plays from the next one ---");
        }
        if (a.planIdx + 1 < a.plan.size()) a.rewriteTableKeepingCountdown(a.planIdx + 1);
        // NOT Ui.snack — this button lives inside ovHost, the adjust sheet's own setView
        // content, and ovDialog is still showing when this fires. A snackbar added to
        // rootFrame draws BEHIND the dialog's scrim and is invisible. (It had drifted to
        // a.toast(), which on the run screen IS the snackbar; with D1 it can say a change was
        // not sent, which must be seen.) So it is written ON the sheet, on its message line -
        // sheetSay, journalled like every other message (invariant 140).
        String said = n == 1 ? "Reshaped the last step of this ramp"
                             : "Reshaped the " + n + " remaining steps of this ramp";
        if (dropped) said += ". The reshape came first — that change was not sent.";
        sheetSay(said, dropped);
        fillOverrideSheet();
        a.redrawRunIfStillRunning();
    }

    private final class OverrideRevert implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            // The button has already closed the sheet: a Revert that waits - a START behind the
            // table, the pump still answering (RunControl#revert) - brings it back, saying why.
            String waits = a.revertOverride();
            if (waits != null) openOverride(waits);
        }
    }

    /** Skip set in work; End rest in a rest. A rest the user INSERTED ends the way its own
     *  timer ends it (endInsertedRest), putting the run back on the step it interrupted - a
     *  Skip there would have ended that step too. The changeover's "I've swapped" is this
     *  control's other name, as it always was. */
    private final class StepSkipTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            long tapAt = System.currentTimeMillis();
            // UNDO IS THIS BUTTON WEARING ITS OTHER NAME (device check EMU9 H2): the snack it
            // was sat over STOP, and a tap meant for Undo just after it went ended the run.
            // Decided by what the button SAYS, not by the clock: a tap on "Undo skip" is never
            // a skip, even one a tick after the window closed ("Too late to undo").
            if (skipSaysUndo) {
                String said = a.undoSkip();
                skipSaysUndo = false;
                undoGoneAt = tapAt;
                a.redrawRunIfStillRunning();
                refreshTimerControls();
                a.toast(said);
                return;
            }
            // ...and for a moment after it turns back into Skip, a late tap meant for Undo
            // does nothing rather than skip the step the run is now on.
            if (RunEdit.skipLateTapGuarded(undoGoneAt, tapAt)) return;
            if (a.restingNow && !a.awaitingAck) { a.endInsertedRest(true); a.redrawRunIfStillRunning(); return; }
            // The changeover's "I've swapped", a rest's End rest and a ramp's Skip step end the
            // one step playing - the skip this button always was.
            int mode = stripNow(System.currentTimeMillis()).mode;
            if (a.awaitingAck || mode == QuickAdjust.MODE_REST || mode == QuickAdjust.MODE_RAMP
                    || mode == QuickAdjust.MODE_NONE) {
                a.skipPreset();
                return;
            }
            // Skip block ends what is left of the block of sets playing; Skip warm-up, what is
            // left of the warm-up (SessionActivity#skipRestOf).
            boolean warm = mode == QuickAdjust.MODE_WARM;
            Model.Preset was = a.planIdx >= 0 && a.planIdx < a.plan.size()
                ? a.plan.get(a.planIdx) : null;
            String no = a.skipRestOf(warm);
            if (no != null) { a.toast(no); return; }
            // polish RN-9: a whole block can go in one tap, so Undo follows it - on this same
            // button, for as long as the Undo is taken (RunEdit#SKIP_UNDO_MS). Nothing is laid
            // over STOP or the other controls; the message only says what happened.
            if (a.skipUndoable()) {
                String what = warm ? "the warm-up"
                    : was != null && was.label != null && was.label.length() > 0 ? was.label
                    : "these sets";
                skipSaysUndo = true;
                refreshTimerControls();
                a.toast("Skipped " + what);
            }
        }
    }

    /** A snack shown above the footer moves with it when it is laid out again (Ui#snackAbove). */
    private final class SnackFollowsFooter implements View.OnLayoutChangeListener, Runnable {
        @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                             int ol, int ot, int or, int ob) {
            if (t == ot && b == ob) return;
            // Not inside this layout pass: the bar's own re-layout goes after it.
            v.post(this);
        }
        @Override public void run() {
            Ui.snackAbove(a, a.rootFrame, a.runFooterInsetPx());
        }
    }

    /** Skip's Undo, in the Skip button's place (device check EMU9 H2). */
    static final String UNDO_SKIP = "Undo skip";
    /** The Skip button says "Undo skip" now. */
    private boolean skipSaysUndo;
    /** When it last stopped saying so - the late-tap guard (RunEdit#skipLateTapGuarded). */
    private long undoGoneAt;

    /** A slider's name on the adjust sheet (polish RN-7): the words the strip and the Library
     *  use - "Pull to", "Suction power" - in place of the sheet's own lowercase ones. */
    private static String sheetName(int field, String fallback) {
        switch (field) {
            case OvSlide.UP:   return RunEdit.SHEET_PULL;
            case OvSlide.LO:   return RunEdit.SHEET_DROP;
            case OvSlide.UH:   return RunEdit.SHEET_HOLD;
            case OvSlide.LH:   return RunEdit.SHEET_DROP_TIME;
            case OvSlide.SP:   return RunEdit.SHEET_POWER;
            case OvSlide.E_UP: return "End pull to";
            case OvSlide.E_LO: return "End drop to";
            case OvSlide.E_UH: return "End hold";
            case OvSlide.E_LH: return "End drop time";
            case OvSlide.E_SP: return "End suction power";
            default:           return fallback;
        }
    }

    /**
     * THE +30 s BUTTON SAYS WHAT IT ADDS (0.10 final). "+30 s hold" in a set: the hold of the set
     * playing, 30 s longer and 4:15 at most, through the strip's own live road (nudgeBy). "+30 s
     * step" on a ramp: the step playing, half a minute longer. "+30 s rest" and the warm-up's
     * "+30 s": the step playing, exactly as +30 s always extended it; an inserted rest's end
     * moves out.
     */
    private final class StepExtendTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.restingNow) {
                a.toast(a.extendInsertedRest(RunEdit.EXTEND_MS));
                a.refreshRunScreen(a.session.elapsedMs(System.currentTimeMillis(),
                        a.LINK_TIMEOUT_MS));
                return;
            }
            StripNow s = stripNow(System.currentTimeMillis());
            if (s.mode == QuickAdjust.MODE_WORK) {
                if (a.holdMayBeUp()) {
                    a.toast("Paused — resume first. Nothing was changed.");
                    stripHaptic(v, true);
                    return;
                }
                // NEVER REFUSED IN SILENCE - in the drop phase or anywhere else: every refusal
                // (4:15 already, the block ending, the live edit's own) is said in the toast.
                int uh = s.v[QuickAdjust.HOLD];
                int to = QuickAdjust.plusHold(uh);
                long left = a.presetFireAt - System.currentTimeMillis();
                String no = QuickAdjust.plusHoldRefusal(uh, left);
                // THE HOLD UNDER WAY GOES ON 30 s LONGER FROM WHERE IT IS (HoldCarryOn) - or, in
                // the drop, the next hold is the longer one; said as it goes.
                HoldCarryOn.Plan co = no == null ? a.carryOnPlanFor(to, s.v[QuickAdjust.DROP_TIME]) : null;
                if (no == null) no = a.carryOnRefusal(to, s.v[QuickAdjust.DROP_TIME]);
                if (no == null) no = nudgeBy(LiveEdit.UH, to - uh);
                boolean inDrop = co != null ? co.kind == HoldCarryOn.AFTER_DROP : dropNow;
                if (no == null && inDrop) a.carryOnWaitSaid = true;     // said once, here
                a.toast(no != null ? no : HoldCarryOn.plusHoldSaid(to - uh, inDrop,
                    co != null && co.laterToo, co != null && co.oneCycle));
                stripHaptic(v, no != null);
                return;
            }
            if (s.mode == QuickAdjust.MODE_RAMP && oneCycleLength(s, QuickAdjust.STEP_TIME)) {
                // A ONE-CYCLE STEP: 30 s more step is 30 s more hold - the step follows it when
                // the pump takes it ("Step is now 2:35 — one cycle", commitEdit).
                if (a.holdMayBeUp()) {
                    a.toast("Paused — resume first. Nothing was changed.");
                    stripHaptic(v, true);
                    return;
                }
                int uh = s.v[QuickAdjust.HOLD];
                int to = Math.min(QuickAdjust.MAX[QuickAdjust.HOLD], uh + QuickAdjust.PLUS_HOLD_SEC);
                String no = to <= uh ? "4:15 is the longest the pump takes."
                                     : a.cycleRefusalNow(to, s.v[QuickAdjust.DROP_TIME]);
                if (no == null) no = a.carryOnRefusal(to, s.v[QuickAdjust.DROP_TIME]);
                if (no == null) no = nudgeBy(LiveEdit.UH, to - uh);
                a.toast(no != null ? no : HoldCarryOn.plusHoldSaid(to - uh, false, false, true));
                stripHaptic(v, no != null);
                return;
            }
            if (s.mode == QuickAdjust.MODE_RAMP) {
                // ONE WHOLE CYCLE (0.10 follow-up): the step playing grows by one cycle of what
                // is in force - the button says so, "+0:42 step" - so it still ends after a
                // drop; never past its share of the hour (the app's clock; 255 s is one hold,
                // not a step).
                int len = s.v[QuickAdjust.STEP_TIME];
                long ext = RunEdit.rampExtendMs(s.stepCycle);
                String no = QuickAdjust.stepTimeRefusal(len, len + (int) (ext / 1000L),
                    s.stepCycle, s.rampSteps);
                if (no != null) { a.toast(no); stripHaptic(v, true); return; }
                a.extendRunningStep(ext);
                return;
            }
            a.extendPreset();
        }
    }

    /** Is this one of the ramp END sliders? The single place the two halves of the sheet
     *  are told apart — a "now" field is debounced onto the pump, an "end" field is not. */
    private static boolean isRampEndField(int field) {
        return field >= OvSlide.E_UP;
    }

    /** A slider moved. Nothing is sent yet — the debounce job is cancelled and relaunched,
     *  so only the value the user SETTLES on ever reaches the pump. */
    private void overrideChanged() {
        // WHICH STEP THIS IS FOR, and when it last moved. Stamped with the value, at the
        // moment of the touch, so the settle 400 ms later can refuse to apply it to a
        // different step (RunEdit#debouncedStillSameStep) and waits for it to stop moving.
        a.liveEdit.edited(a.inForceTuple(), a.planIdx, System.currentTimeMillis());
        a.postOverrideSettle();         // stamped with the step and phase it is for (148)
    }

    private final class RestNowTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (!RunEdit.canOverride(a.running, a.planIdx, a.plan.size())) return;
            // Not while something else owns the pump - WiringCheck invariant 114.
            String frozen = a.commandFreezeReason();
            if (frozen != null) { a.toast(frozen); return; }
            if (a.restingNow) { a.toast("Already resting"); return; }
            if (a.resting) { a.toast("The routine is already resting here"); return; }
            // Held, so the plan's end closes it (invariant 116) rather than leaving a
            // chooser whose every answer would be refused.
            Ui.dress(a, a.holdRunSheet(Ui.dialog(a)
                .setTitle("Rest for how long?")
                .setItems(a.REST_NOW_LABEL, new RestNowPick())
                .setNegativeButton("Cancel", null)
                .show()));
        }
    }

    private final class RestNowPick implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int which) {
            if (which < 0 || which >= a.REST_NOW_SEC.length) return;
            insertRest(a.REST_NOW_SEC[which]);
        }
    }

    /** Opens the rest window. Everything it does is described in the section note above. */
    private void insertRest(int seconds) {
        if (!RunEdit.canOverride(a.running, a.planIdx, a.plan.size())) return;
        // ASKED AGAIN HERE: the plan's end closes the chooser (invariant 116), but a pick can
        // land in the same instant, after the plan has ended into the after-test, which
        // canOverride cannot see (WiringCheck invariant 114).
        String frozen = a.commandFreezeReason();
        if (frozen != null) { a.toast(frozen); return; }
        if (a.restingNow || a.resting) return;
        // A hold and a rest are two answers to the same question and cannot both be up. The
        // hold is stood DOWN rather than released (exitHold(false) clears the flags without
        // re-arming anything): the vent below is what happens next, and resuming into the
        // hold pressure first would be a pointless extra command.
        if (a.holdMayBeUp()) a.exitHold(false);
        long now = System.currentTimeMillis();
        Model.Preset p = a.plan.get(a.planIdx);
        a.restingNow = true;
        // The step waits through the rest: its set count too (SetClock) - resumed with it.
        if (a.setClock.forIdx() == a.planIdx) a.setClock.pause(now);
        a.ctl.restChanged();            // nothing posted before the rest is written into it
        /* AN INSERTED REST VENTS, SO IT OWES THE SAME RE-ARM A PLANNED ONE DOES.
         *
         * Reported from a device: skipping out of an inserted rest left the pump doing
         * nothing until a value was nudged, which is the same symptom a planned rest once
         * had. The cause is the same too - a StopWork may empty the device's table, so the
         * slot index the app still believes in starts an empty entry - and the fix already
         * exists: restRearmPending makes the next step rewrite the table from itself.
         *
         * Only the PLANNED rest set it. Skip out of an inserted rest reaches playPreset by
         * the same road with the flag clear, so it started a slot that may no longer hold
         * anything. Set here, where the vent is issued, rather than in one of the two exits
         * - the debt is owed by the STOP, not by how the rest ends.
         */
        a.restRearmPending = true;
        a.restNowEndAt = now + (long) seconds * 1000L;
        a.restNowStartAt = now;
        // The countdown freezes from this instant — the same field a hold ticks against.
        a.heldLastTickAt = now;
        // A REST row, opened immediately: there is no ack coming, because nothing is being
        // sent to be acked. Zeros for every value, like the plan's own rest rows.
        a.asRun.open(AsRun.REST, a.asRunNow(), p, 0, 0, 0, 0, 0, 0, 0);
        a.asRunPersist();
        // THE VENT, through the one stop chokepoint, watched like every other deliberate
        // stop. A StopWork with nothing watching would leave ventStopOutstanding set with
        // nothing able to clear it and stillUnsafe() would latch for the rest of the run.
        a.startVentWatch(0.0, "StopWork (rest inserted during the run)", a.new RestVentUpdate());
        if (a.pendingRestEnd != null) a.ui.removeCallbacks(a.pendingRestEnd);
        a.pendingRestEnd = new EndInsertedRest();
        a.ui.postDelayed(a.pendingRestEnd, (long) seconds * 1000L);
        a.log("--- REST inserted: " + Model.Fmt.t(seconds) + " during " + p.label
            + " — venting, nothing armed ---");
        a.toast("Rest — " + Model.Fmt.t(seconds) + ", the cuff is venting");
        a.redrawRunIfStillRunning();
    }

    private final class EndInsertedRest implements Runnable {
        @Override public void run() { a.endInsertedRest(true); }
    }

    /** PAUSE / RESUME (0.10 final: the Hold button, renamed and first in every step). */
    private final class HoldTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.holding) { a.exitHold(true); return; }
            // In a rest there is nothing to pause: the cuff is vented. The button keeps its
            // place, greyed, and a tap says so.
            Model.Preset cur = (a.planIdx >= 0 && a.planIdx < a.plan.size())
                ? a.plan.get(a.planIdx) : null;
            if (inRest(cur)) { a.toast("Nothing to pause in a rest: the cuff is vented."); return; }
            /* A CONTROL THAT REFUSES HAS TO SAY SO. enterHold() returns in silence when
             * canCommandNow() is false, which during a REST - the commonest way to meet it -
             * left a lit button doing nothing at all. The sentence is whyNothingToAdjust()'s,
             * the same one the +/- chips already answer with, so the two controls on this
             * card cannot give different accounts of the same refusal.
             *
             * Asked only on the way IN. Releasing a hold is never refused (exitHold is the
             * exit from one, not an entry into it) and must not start being - it is the
             * first thing asked, above. */
            if (!a.canCommandNow()) { a.toast(a.whyNothingToAdjust()); return; }
            a.enterHold();
        }
    }

    private final class RevertOverrideTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            String waits = a.revertOverride();
            if (waits != null) sheetSay(waits, true);
        }
    }

    /**
     * The second half of the run screen's REST label: "vented" once the vent watch has
     * EVIDENCE of the fall, "venting" while the stop is still only sent.
     *
     * It reads the watch rather than keeping a flag of its own, because "the stop was
     * written" and "the pressure was seen to fall" are two different facts and this app
     * has one place that knows the difference. Saying "vented" off the back of a write
     * is precisely the claim the whole vent-confirmation machinery exists to refuse.
     */
    /** @see #tickRestVent - a rest that never came down says so here, in the one place
     *  the rest already names its own state, rather than in a second parallel line. */
    private String restStateWord() {
        if (a.restNotResting()) return "still under pressure";
        return restStateWordVented();
    }

    private String restStateWordVented() {
        return a.ventWatcher.vented() ? "vented" : "venting";
    }

    /**
     * WHAT THE RUN SCREEN SAID, for the debug log - read back from the views themselves
     * after this tick painted them, so it is what a person saw rather than what the code
     * meant to show. The journal keeps a line only when a value changes; its watcher
     * compares the cells with what was last sent to the pump.
     */
    private void journalRunScreen(boolean noReadingNow) {
        Journal j = a.journal();
        if (j == null) return;
        long t = JournalHost.now();
        String unit = Model.Fmt.unit;
        String state = a.restingNow ? "rest-inserted" : a.resting ? "rest"
            : a.holding ? "hold" : !a.presetArmed() ? "starting"
            : a.awaitingAck ? "waiting" : a.overrideCarriesNow() ? "adjusted" : "run";
        j.uiState(t, "state", state);
        if (a.cellPull != null) {
            j.pressureCell(t, "pull", a.cellPull.getText(), a.cellPullUnit.getText(), unit);
            j.pressureCell(t, "drop", a.cellDrop.getText(), a.cellDropUnit.getText(), unit);
            j.plainCell(t, "hold", a.cellHold.getText(), a.cellHoldUnit.getText());
            j.plainCell(t, "dropt", a.cellDropT.getText(), a.cellDropTUnit.getText());
        }
        if (a.subP != null) {
            // The phase word only: the rest of the line is a running clock.
            String line = a.subP.getText().toString();
            int dot = line.indexOf('\u00B7');
            j.uiText(t, "phase", (dot > 0 ? line.substring(0, dot) : line).trim());
        }
        if (a.runCountdown != null) {
            String c = a.runCountdown.getText().toString();
            j.countdown(t, "waiting".equals(c) ? -1 : clockSeconds(c));
        }
        if (a.bigP != null) {
            // "vented" (0.10 final) stands for the reading at the vented level: the journal's
            // watch compares it with what the pump reports, as it compares a figure.
            String big = a.bigP.getText().toString();
            j.bigKpa = noReadingNow ? Double.NaN
                : "vented".equals(big) ? a.lastKpa : Journal.shownKpa(big, unit);
        }
    }

    /** "1:05" or "1:02:03" as seconds; -1 when it is not a clock. */
    private static int clockSeconds(String c) {
        int total = 0;
        String[] p = c.trim().split(":");
        try {
            for (int i = 0; i < p.length; i++) total = total * 60 + Integer.parseInt(p[i].trim());
        } catch (NumberFormatException e) { return -1; }
        return total;
    }

    /** A cell's caption: its unit, or "sending…" while its target is on the way to the
     *  pump (the journal reads that word as a pending edit - JournalWatch compares such a
     *  cell with nothing until it settles). A write the pump refused or never answered is not
     *  in force, so the cell simply shows what is, and the run says why (the pump-refusal fix)
     *  - there is no "not confirmed" figure any more. */
    private String cellCaption(int field, int[] work, int idx, String unit) {
        if (a.liveEdit.pending(field, work, idx)) return "sending\u2026";
        // WHILE THE PUMP IS NOT ANSWERING, NO CELL IS A FACT (the review of refusal-2, HIGH):
        // each says so, and the pull shows the higher figure it may be at.
        if (a.notAnsweringMayBeUp() >= 0) return NOT_CONFIRMED;
        return unit;
    }

    /** The caption of every cell while the pump is not answering. The journal reads it as a
     *  figure that is not a fact (Journal, JournalWatch). */
    static final String NOT_CONFIRMED = "not confirmed";

    void refreshRunScreen(long elapsed) {
        if (a.plan.isEmpty() || a.presetDurMs.length == 0) return;
        /* ONE INDEX, THE ARMED ONE (RunEdit#liveIndex). This card used to resolve the
         * live preset from the wall clock while the pump was given `planIdx` — so at a
         * boundary, and for every second of a paused clock, the tiles, the deviation and
         * the dashed band could all be talking about the NEXT preset. */
        int dispIdx = a.liveIdxNow(System.currentTimeMillis());
        if (dispIdx < 0) return;
        double presetFrac = Session.liveStageFraction(a.presetDurMs, elapsed, dispIdx);
        Model.Preset cur = a.plan.get(dispIdx);
        /* AND THE PHASE COUNTS FROM WHEN THIS PRESET WAS ACTUALLY STARTED, not from a
         * fraction of the wall clock: the two agree while nothing has paused, and only
         * the armed stamp stays right when something has. */
        long elapsedInPreset = a.presetArmed() && a.presetStartedAtMs() > 0
            ? System.currentTimeMillis() - a.presetStartedAtMs()
            : (long) (presetFrac * cur.durMs);
        int si = Session.liveStageIndex(a.stageDurMs, elapsed);
        double stageFrac = Session.liveStageFraction(a.stageDurMs, elapsed, si);
        Model.Routine r = a.runRoutine;
        Model.Stage stage = (r != null && si >= 0 && si < r.stages.size()) ? r.stages.get(si) : null;

        // While the adjustment CARRIES into the stage playing now (RunEdit.overrideCarries
        // on the DISPLAYED preset's stage), the tiles and the deviation read the carried
        // tuple — what is actually on the wire — not the routine's preset.
        boolean carriesHere = RunEdit.carryActiveAt(a.ovStage, cur.stageIdx, dispIdx, a.ovEndsAfterIdx);
        int tileUp = carriesHere ? a.carryUp : Math.min(cur.up, a.model.ceilKpa);
        int tileLo = carriesHere ? a.carryLo : cur.lo;
        int tileUh = carriesHere ? a.carryUh : cur.uh;
        int tileLh = carriesHere ? a.carryLh : cur.lh;
        int tileSp = carriesHere ? a.carrySp : cur.sp;
        /* A PRESS IS VISIBLE THE INSTANT IT LANDS, AND STAYS VISIBLE WHILE IT IS SENT.
         *
         * The cells show the pending TARGET (LiveEdit#shown) from the tap until the pump
         * acknowledges the write that carries it, and say it is on its way; everything that
         * describes what the PUMP is doing — the phase, the deviation, the band — still
         * reads the applied tuple above. Reported from a device: "it takes a few seconds to
         * show on the number" — and a person whose press does not land presses again,
         * which is how a pull walked up to the pressure that broke the seal. The pending
         * mark used to drop the moment the write left, one repaint before the app recorded
         * it as in force, so the cell flashed the OLD value mid-write (bug B1). */
        int[] work = a.ovTuple();
        int[] tile = new int[LiveEdit.FIELDS];
        tile[LiveEdit.UP] = tileUp; tile[LiveEdit.LO] = tileLo; tile[LiveEdit.UH] = tileUh;
        tile[LiveEdit.LH] = tileLh; tile[LiveEdit.SP] = tileSp;
        int showUp = a.liveEdit.shown(LiveEdit.UP, work, tile, dispIdx);
        int showLo = a.liveEdit.shown(LiveEdit.LO, work, tile, dispIdx);
        int showUh = a.liveEdit.shown(LiveEdit.UH, work, tile, dispIdx);
        int showLh = a.liveEdit.shown(LiveEdit.LH, work, tile, dispIdx);
        int showSp = a.liveEdit.shown(LiveEdit.SP, work, tile, dispIdx);
        /* THE PHASE READS THE CARRIED TUPLE TOO. It was derived above, from the ROUTINE's
         * uh/lh, while every other element on this card reads the adjusted ones - so during
         * a live adjustment the HOLD/DROP line under the trace counted against a hold the
         * pump was no longer running. */
        // Counted from where the pump's cycle last began (SetClock): a live change or a resume
        // starts the slot, and the pump's cycle with it, again.
        boolean counted = dispIdx == a.planIdx && clockSet(cur, System.currentTimeMillis()) != null;
        long cycleEl = counted ? a.setClock.inCycleMs(System.currentTimeMillis()) : elapsedInPreset;
        long phaseMs = Session.phaseElapsedMs(cycleEl, tileUh, tileLh);
        boolean upper = Session.isUpperPhase(cycleEl, tileUh, tileLh);
        /* AND THE DEVIATION IS MEASURED AGAINST WHAT IS ACTUALLY COMMANDED. A hold is an
         * override at whatever pressure the user paused at; comparing the
         * reading to the ROUTINE's pull made the chip read "below commanded" while the pump
         * was doing exactly what it had been told. */
        int commandedKpa = (a.holding && a.holdAtKpa > 0) ? a.holdAtKpa : tileUp;
        boolean noReadingNow = a.lastNoReading || a.lastSampleAt <= 0
            || (System.currentTimeMillis() - a.lastSampleAt) > a.LINK_TIMEOUT_MS;
        Double dev = Session.deviationKpaOrNull(false, noReadingNow, a.lastKpa, commandedKpa);

        long now = System.currentTimeMillis();

        /* ---- THE COUNTDOWN. Straight off the wall clock, exactly as the sequencing
         * itself is: presetFireAt is the instant pendingAdvance will fire, so the time
         * left in this preset is that instant minus now. Never a tick count, and never
         * derived from `elapsed` a second way — a skip or a +30 s moves presetFireAt, and
         * a countdown computed from the plan's durations instead would keep counting to
         * the old deadline. Floored at zero: the last few hundred milliseconds before the
         * advance actually lands must read 0:00, not a negative time. */
        if (a.runCountdown != null) {
            /* A CHANGEOVER HAS NO TIME LEFT IN IT, so it must not print one.
             *
             * The gate keeps presetFireAt and the preset's duration moving together, which
             * is what stops the coda starting - and leaves this subtraction constant. The
             * largest element on the screen therefore sat at a fixed 0:31 for as long as
             * somebody took to swap a tube: a countdown that does not count reads as an app
             * that has died, on the one screen where "is this thing still running" matters.
             *
             * The word, not a number, and the app's own word for it: the TUP clock already
             * prints "paused" rather than a frozen figure for exactly this reason. */
            if (a.awaitingAck && ByHand.waitsAfterClock(cur)) {
                // THE RELEASE'S GUIDE HAS RUN OUT (ByHand): it reads 0:00, and the line under it
                // says "Done when you are" - it waits, it has not stopped.
                a.runCountdown.setText(Model.Fmt.t(0));
            } else if (a.awaitingAck) {
                a.runCountdown.setText("waiting");
            } else if (a.restingNow) {
                // THE NOW CARD IS THE TIMER (0.10): in a rest the user inserted it counts
                // THAT rest down - the step it interrupted is frozen and says so in the
                // kicker below - so the one big time on the screen is the one that is
                // moving. Off the wall clock, like every other countdown here.
                long leftMs = Math.max(0L, a.restNowEndAt - now);
                a.runCountdown.setText(Model.Fmt.t((leftMs + 999) / 1000));
            } else {
                long leftMs = a.presetFireAt - now;
                if (leftMs < 0) leftMs = 0;
                a.runCountdown.setText(Model.Fmt.t((leftMs + 999) / 1000));
            }
        }

        /* ---- THE NOW / UPCOMING RAIL. Everything here comes from the plan the run is
         * really playing, so a skip, a +30 s, an edited upcoming preset or a live override
         * shows the change rather than what the saved routine used to say. */
        Model.Preset nxt = (dispIdx + 1 < a.plan.size()) ? a.plan.get(dispIdx + 1) : null;

        if (a.nowKicker != null && r != null) {
            // ADJUSTED only while the adjustment CARRIES into the preset playing now —
            // ovStage == the current stage — never from overrideInForce alone, which a
            // hold also sets (a hold is "HOLDING", and its release into the routine slot
            // is plain "RUNNING").
            boolean adjusted = a.overrideCarriesNow();
            // TWO STEP COUNTERS ARE ONE TOO MANY. The flow's stepBar above already says
            // where the SESSION is — seal check, running, summary — and this line used to
            // say "STEP n OF m · RUNNING" directly under it, so the screen showed two
            // different step numbers and two different totals stacked on top of each other.
            // This line keeps only the fact the stepBar cannot know: which preset of the
            // routine is playing, and whether it is being held or adjusted. It does not say
            // STEP, and it does not repeat RUNNING — running is what the stepBar is for.
            // A REST OUTRANKS BOTH. During a rest nothing is adjusted and nothing is
            // held - the routine has vented the cuff and is waiting - so saying either
            // of those here would describe a pressure state the pump is not in.
            // AN INSERTED REST OUTRANKS THEM ALL, for exactly the reason a planned one
            // does: the cuff has been vented, so "holding" or "adjusted" would name a
            // pressure state the pump is not in. It also says how long is left, because
            // unlike a planned rest this one is not what the countdown above is counting —
            // that countdown is frozen on the step the rest interrupted.
            // (0.10) The inserted rest's time left is the NOW card's big figure now, so it is
            // not said a second time here.
            String state = a.restingNow
                ? " · resting " + restStateWord() + ", the step waits"
                : a.resting ? " · " + restStateWord()
                : a.holding ? " · paused"
                : (adjusted ? " · adjusted" : "");
            /* WHICH SET OF THIS STAGE, NOT WHICH PRESET OF THE WHOLE PLAN.
             *
             * plan.size() counts EXPANDED presets - Set#ladder emits one per ramp step and
             * one per repetition of a stitched hold - so "preset 7 of 23" counted something
             * nobody built and nobody can find on their own routine. The locked reference
             * names the stage and the position within it. Read against cur.stageIdx, the
             * stage the DISPLAYED preset actually came from, rather than the local `stage`,
             * which comes from a second index derived from elapsed time. */
            Model.Stage ks = (r != null && cur.stageIdx >= 0 && cur.stageIdx < r.stages.size())
                    ? r.stages.get(cur.stageIdx) : null;
            // A PLANNED REST SAYS "REST" ONCE (0.10 final): the card's title already names it,
            // so the kicker is the routine and the rest's state - "RS Routine · vented".
            // ...but a step done by hand keeps its name on this line (ByHand): it is the one
            // rest that asks the person to do something.
            boolean restHere = a.resting && !a.restingNow && cur.rest && !cur.manual;
            /* polish RN-3 (option A): the routine's name is behind this line's info mark, not
             * printed - the NOW card above already names the step. The line keeps the state
             * (paused, adjusted, resting) the colour below is about. */
            String where = r.name
                + (ks == null || restHere ? "" : " · " + ks.name)
                // Wave 3a (D6): counted among WORK sets — a legacy stage of ten holds
                // and nine rest sets used to read "5/19" while hold 3 played.
                + (ks != null && !restHere && a.model.workSetCount(ks) > 1
                   ? " " + a.model.workSetPos(ks, cur.pos) + "/"
                     + a.model.workSetCount(ks) : "");
            String kick = "Routine ⓘ" + state;
            if (!kick.contentEquals(a.nowKicker.getText())) a.nowKicker.setText(kick);
            kickerWhere = where;
            a.nowKicker.setContentDescription("Routine: " + A11y.collapse(where) + state
                + ". Opens its name.");
            // A VENTED REST IS QUIET (0.10 final: DIM, the approved "… · vented"); amber for a
            // rest ONLY until the vent is evidenced - a stop that has been sent and not yet
            // seen is exactly the case where the cuff may still be under pressure - and amber
            // for the commanded states, a pause and a carried adjustment.
            a.nowKicker.setTextColor(a.resting || a.restingNow
                ? (a.ventWatcher.vented() && !a.restNotResting() ? Ui.DIM : Ui.CMD)
                : (a.holding || adjusted ? Ui.CMD : Ui.DIM));
        }
        if (a.runCountdown != null)
            // A clock that is not counting (at-pressure-only waiting) is dimmed: the number is
            // still true, but a stopped timer must not read with a running one's weight. A
            // PAUSE turns it grey instead (paintRunTop), at full strength.
            a.runCountdown.setAlpha(!a.holding && a.tupClockPaused ? 0.55f : 1f);

        if (a.nowName != null) {
            // Wave 2 §4: the title names the SET playing (the ± edits the set), the
            // kicker keeps routine · stage. The stage name led here while the ± chips
            // below edited something else — which is item 9's whole complaint.
            Model.Set nowSet = cur.setId == null ? null : a.model.set(cur.setId);
            String who = nowSet != null ? nowSet.name
                       : cur.label != null ? cur.label
                       : stage != null ? stage.name : "—";
            // "REST" once in a rest (0.10 final) - never "REST · Rest"; "PAUSED · <block>"
            // while the run is paused; "NOW · <block>" otherwise.
            // A STEP DONE BY HAND IS NAMED, never "REST" (the owner's decision, 2026-10-07):
            // "BY HAND · Tunica release"; the changeover keeps its own sentence.
            a.nowName.setText(ByHand.is(cur) && !a.restingNow ? ByHand.title(stageNameOf(cur))
                              : a.resting || a.restingNow ? "REST"
                              : (a.holding ? "PAUSED · " : "NOW · ") + who);
        }
        if (a.nowSub != null) {
            String setName = cur.label == null ? "" : cur.label;
            // A PAUSED SET REPORTS AGAINST WHAT IT PROMISED, with the paused time named.
            // While "at pressure only" is holding the clock, cur.durMs is growing by every
            // paused second - so reporting against it showed a total climbing as fast as the
            // elapsed, which reads as a set getting longer rather than one not being
            // delivered. Only for the preset actually being tracked: dispIdx can lead
            // planIdx across an advance, and the figures below belong to one preset.
            boolean tracked = a.model.tupTiming && a.tupBaseDurMs > 0 && dispIdx == a.planIdx;
            long total = tracked ? a.tupBaseDurMs : cur.durMs;
            // DONE IS THE LENGTH LESS WHAT IS LEFT (RunEdit#doneOfStepMs): time added to the
            // step - +30 s, a longer hold, a set run again whole - goes to its length, never to
            // what is done, and one never passes the other.
            long shown = dispIdx == a.planIdx && a.presetArmed() && !a.awaitingAck
                ? RunEdit.doneOfStepMs(total, a.presetFireAt, now)
                : tracked ? Math.max(0, elapsedInPreset - a.tupPausedMs) : elapsedInPreset;
            // AND WHAT TO DO WITH THE REST, on the live card as well as in the list above
            // it. The third of the three places a rest is described, and the one somebody is
            // actually looking at while the rest runs - all three read restInstruction() so
            // they cannot tell a person two different things about the same two minutes.
            String doing = cur.rest ? " · " + a.restInstruction() : "";
            /* AND WHILE THE GATE IS UP, THERE IS NO TOTAL TO REPORT AGAINST.
             *
             * cur.durMs grows by every waited second - that is how the gate holds the coda
             * back - so "of" printed a total climbing exactly as fast as the elapsed:
             * 0:22 of 0:50, then 1:31 of 1:59, a stage apparently getting longer the longer
             * somebody took. It is the same defect the paused-clock branch above already
             * names, arriving by the other route. A wait has an elapsed and no length. */
            // ONE LINE (polish item 17): where the set is, then when the run ends. The set's
            // name is the title's; a rest's instruction still follows.
            // S03 - AND WHEN THIS RUN HAS AN AFTER-TEST, THIS LINE SAYS SO: it already
            // counts the after-assessment's length (runRemainingMsNow adds Tau#trackedAssessSec),
            // which the ROUTINE card's own total deliberately does not (paintRoutineStrip
            // sums the routine's own stages, and the after-test is not one of them) - two
            // "time left" figures on the same screen that used to differ with no reason
            // given. Owner ruling (2026-09-26): keep both, name the gap here.
            String ends = " · " + RunEdit.runEndsLine(a.runRemainingMsNow(now),
                Tau.trackedAssessSec(a.runRoutine));
            int mayBeUp = a.notAnsweringMayBeUp();
            if (mayBeUp >= 0) {
                // UNTIL IT ANSWERS AGAIN, THE CARD SAYS SO - the higher figure, in the unit on
                // screen, and that it is not confirmed.
                a.nowSub.setText("The pump isn't answering \u2014 may be at "
                    + Model.Fmt.p(Math.max(mayBeUp, showUp)) + " (" + NOT_CONFIRMED + ")");
            } else
            a.nowSub.setText(a.awaitingAck
                ? Model.Fmt.t(shown / 1000) + " so far" + ends + doing
                : Model.Fmt.t(shown / 1000) + " of " + Model.Fmt.t(total / 1000)
                    + (tracked && a.tupPausedMs >= 1000
                       ? " · +" + Model.Fmt.t(a.tupPausedMs / 1000) + " paused" : "")
                    + ends + doing);
        }
        if (a.nowTime != null) {
            // The same frozen subtraction as the big countdown, in the card's corner. An
            // em dash rather than the word, because the word is already the loudest thing
            // on the screen an inch above and saying it twice is furniture.
            if (a.awaitingAck) a.nowTime.setText("\u2014");
            else {
                long leftMs = Math.max(0, a.presetFireAt - now);
                a.nowTime.setText(Model.Fmt.t((leftMs + 999) / 1000));
            }
        }
        if (a.runEnds != null)
            // Dead today (a.runEnds is always null - see buildRunScreen's own note), but kept
            // matching the live line above (S03) rather than left to disagree if this is
            // ever revived.
            a.runEnds.setText(RunEdit.runEndsLine(a.runRemainingMsNow(now),
                Tau.trackedAssessSec(a.runRoutine)));
        paintNetRow();
        paintRoutineOffsetRow();
        // THE − / + STRIP: its figures, labels and keys, painted in place (never rebuilt). While
        // the pump is not answering, the pull shows the higher of what was confirmed and what
        // it may be running (invariant 152), captioned "not confirmed".
        int mayBe = a.notAnsweringMayBeUp();
        followRampLayout();
        paintStrip(mayBe >= 0 ? Math.max(mayBe, showUp) : showUp);

        boolean assessAfter = Tau.runsAfter(r);

        if (a.bigP != null) {
            /* "vented", in the rest colour, where a vented rest used to print "—" (0.10 final):
             * only in a rest, only once the vent watch has EVIDENCE of the fall, and only while
             * the reading is at the vented level or the device reports none (a vented cuff
             * reads as no measurement). Anything else is the live figure, as it always was. */
            boolean restNow = cur.rest || a.restingNow;
            boolean vented = restNow && a.ventWatcher.vented() && !a.restNotResting()
                && (noReadingNow || a.lastKpa < VENTED_READOUT_KPA);
            if (vented) {
                if (!"vented".contentEquals(a.bigP.getText())) {
                    android.text.SpannableString v = new android.text.SpannableString("vented");
                    v.setSpan(new android.text.style.RelativeSizeSpan(18f / READOUT_SP), 0, 6,
                              android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    v.setSpan(new android.text.style.TypefaceSpan("sans-serif"), 0, 6,
                              android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    a.bigP.setText(v);
                }
                a.bigP.setTextColor(RunLook.colourOf(a.model.runColours, RunLook.REST));
                a.bigP.setContentDescription("Pressure: vented");
            } else {
                a.bigP.setText(noReadingNow ? "—"
                    : readoutText(Model.Fmt.p(a.lastKpa) + liveLoadSuffix(a.lastKpa, cur.stageIdx)));
                a.bigP.setTextColor(Ui.CMD);
                a.bigP.setContentDescription(null);
            }
        }
        if (a.subP != null) {
            /* THE PHASE LINE READS THE PRESSURE (PhaseTracker), not a clock. The pump runs
             * its cycle on its own timer, which the app cannot see, and the reconstruction
             * below drifted both ways on hardware - "DROP 0:02 of 0:05" while the pump was
             * still holding short of a target the seal could not reach. The clock version
             * stays only as the fallback for when no reading is coming in. */
            String line;
            if (cur.rest && ByHand.is(cur) && a.awaitingAck)
                // A wait has an elapsed and no length (the gate grows cur.durMs as it waits).
                line = ByHand.WORD + " · " + Model.Fmt.t(Math.max(0, elapsedInPreset) / 1000)
                    + " so far";
            else if (cur.rest)
                line = (ByHand.is(cur) ? ByHand.WORD : "REST") + " · "
                    + Model.Fmt.t(Math.max(0, elapsedInPreset) / 1000)
                    + " of " + Model.Fmt.t(cur.durMs / 1000);
            else if (a.holding)
                // (the owner's decision) and when it vents - the same limit every hold has.
                // FIRST, because this line shares its row with the chart's legend and is cut
                // with an ellipsis: on the emulator "· vents in" was what got cut. The
                // pressure is the big figure above; the time held is the least needed.
                line = "PAUSED · " + a.runHoldVentsIn() + " · at "
                    + Model.Fmt.p(commandedKpa) + " · held "
                    + Model.Fmt.t(a.phaseTracker.phaseElapsedMs(now) / 1000);
            else if (a.phaseTracker.started() && !noReadingNow)
                line = a.phaseTracker.line(now, tileUh, tileLh, Model.Fmt.p(commandedKpa));
            else
                line = (upper ? "HOLD" : "DROP") + " · " + Model.Fmt.t(phaseMs / 1000)
                    + " of " + Model.Fmt.t(upper ? tileUh : tileLh);
            a.subP.setText(line);
        }
        journalRunScreen(noReadingNow);

        if (a.runChip != null) {
            /* ONE PLAIN SENTENCE, MEASURED AGAINST WHAT THIS PHASE ASKED FOR (polish item
             * 11, RunChip). The pull while the pump climbs, the hold's own pressure once it
             * is there (the hold's setpoint while HOLD is up - comparing a hold against the
             * routine's pull read "below commanded" while the pump did exactly what it was
             * told), and the DROP pressure while it comes down: measured against the pull,
             * every drop read as a shortfall of the whole band. The paused clock still
             * outranks the gap and still quotes the figure the decision used; a rest still
             * commands nothing, so it is never short of anything. */
            boolean drops = tileLh > 0 && tileLo < tileUp;
            boolean tracked = a.phaseTracker.started() && !noReadingNow;
            PhaseTracker.Phase ph = tracked ? a.phaseTracker.phase() : null;
            int chipPhase = RunChip.HOLD;
            double chipTarget = commandedKpa;
            if (a.holding) {
                chipPhase = RunChip.HOLD;
            } else if (drops && (tracked ? ph == PhaseTracker.Phase.DROP : !upper)) {
                chipPhase = RunChip.DROP; chipTarget = tileLo;
            } else if (tracked && ph == PhaseTracker.Phase.PULLING) {
                chipPhase = RunChip.PULL;
            }
            boolean restingHere = cur.rest || a.restingNow;
            RunChip chip = RunChip.of(a.presetArmed(), restingHere,
                    restingHere && a.restNotResting(),
                    restingHere && a.ventWatcher.vented(),
                    // At pressure only while the step is live: the clock that pauses is
                    // judged against the commanded pull.
                    a.tupClockPaused, dev != null, a.lastKpa,
                    a.tupClockPaused ? commandedKpa : chipTarget,
                    a.tupClockPaused ? RunChip.PULL : chipPhase);
            a.runChip.setText(chip.text);
            if (a.runChipDot != null) a.runChipDot.setBackground(dot(chip.tone));
        }

        a.paintStageRail(r, si, stageFrac);
        // 0.10: the top of the screen - after paintStageRail, whose elapsed / planned figure
        // the status line reads back.
        boolean dropsHere = tileLh > 0 && tileLo < tileUp;
        boolean trackedHere = a.phaseTracker.started() && !noReadingNow;
        boolean dropPhase = dropsHere && (trackedHere
            ? a.phaseTracker.phase() == PhaseTracker.Phase.DROP : !upper);
        int nextUpTop = nxt == null ? 0
            : (RunEdit.carryActiveAt(a.ovStage, nxt.stageIdx, dispIdx + 1, a.ovEndsAfterIdx)
               ? a.carryUp : Math.min(nxt.up, a.model.ceilKpa));
        paintRunTop(cur, nxt, dispIdx, elapsedInPreset, commandedKpa, dropPhase, assessAfter,
                    nextUpTop, now);
        paintScopeSwitch();
        refreshSheetIfStale();
        refreshComingIfMoved();
        refreshTimerControls();

        a.devHistory.add(dev);
        while (a.devHistory.size() > 60) a.devHistory.remove(0);
        if (a.devChart != null) a.devChart.invalidate();
    }


    /* ============================ COMING STEPS, THE SHEET (0.10 final) ====================
     *
     * The owner-approved "1A settings list": one card per step from the one playing on.
     *
     *   - THE STEP PLAYING IS READ-ONLY: its card has the work colour's border, a NOW tag,
     *     "playing now · change it with the − / + on the run screen" and one summary line - and
     *     no control. The − / + strip on the run screen is the one place it changes.
     *   - A LATER WORK BLOCK: a WORK group (Sets, Hold each) and a DROP group (Drop to, Drop
     *     time); a later REST: its Length; a later RAMP: its staircase, Steps and Time per step,
     *     and the pressures it climbs between, read-only ("steps re-spread between these"); the
     *     warm-up: nothing to change here.
     *   - A Skip switch on every later step but the warm-up (and never the cylinder change); a
     *     skipped step is struck through, and hatched on the stage bar.
     *   - A changed card carries an amber dot and "Undo this change" / "Undo these changes";
     *     the foot has "Undo all" and "Done"; the head the new total, a ± chip and "was m:ss".
     *
     * Every tap goes through SessionActivity's comingX methods - the same guarded roads every
     * other mid-run edit takes, each asking its ComingSteps rule first - and a refusal is
     * written in the card it was tapped in (invariant 140: never a snack behind the sheet).
     * THE PULL IS NEVER RAISED HERE; the drop is held under the floor and under the pull. The
     * saved routine is never touched. The sheet is rebuilt after each tap and when the step
     * playing changes under it - never on the tick. */

    private LinearLayout comingHost;
    private AlertDialog comingDialog;
    /** The step the sheet was drawn for: redrawn when another is playing. */
    private int comingDrawnIdx = -1;
    /** The limit a card's last tap met, said in that card (the mock's hint), by stage. */
    private final java.util.Map<Integer, String> comingHint = new java.util.HashMap<Integer, String>();

    private final class ComingStepsTap implements View.OnClickListener {
        @Override public void onClick(View v) { openComingSteps(); }
    }

    private void openComingSteps() {
        if (a.isFinishing()) return;
        if (a.runRoutine == null || !RunEdit.canOverride(a.running, a.planIdx, a.plan.size())) {
            a.toast("Nothing is playing yet — Coming steps opens once the run is under way.");
            return;
        }
        comingHint.clear();
        comingHost = Ui.col(a);
        int pad = Ui.dp(a, 18);
        comingHost.setPadding(pad, Ui.dp(a, 14), pad, Ui.dp(a, 12));
        fillComingSteps();
        ScrollView sv = new ScrollView(a);
        sv.setClipToPadding(false);
        sv.addView(comingHost);
        comingDialog = a.holdRunSheet(Ui.dialog(a).setView(sv).show());
        Ui.sheet(a, comingDialog);
    }

    /** Called every tick: the sheet is redrawn only when the step playing has changed under it. */
    void refreshComingIfMoved() {
        if (comingDialog == null || !comingDialog.isShowing() || comingHost == null) return;
        if (comingDrawnIdx != a.planIdx) fillComingSteps();
    }

    /** Rebuilds the sheet from the run as it stands - after every tap, so it cannot show a
     *  figure the plan no longer holds. */
    private void fillComingSteps() {
        if (comingHost == null) return;
        comingHost.removeAllViews();
        comingDrawnIdx = a.planIdx;
        Model.Routine r = a.runRoutine;
        if (r == null || a.plan.isEmpty()) return;
        int pi = Math.max(0, Math.min(a.planIdx, a.plan.size() - 1));
        // THE CARDS ARE BLOCKS - a set's place in its stage (ComingSteps#key) - not stages: a
        // stage of several sets lists each on its own card (device check).
        int curKey = a.planIdx >= 0 ? ComingSteps.key(a.plan.get(pi)) : -1;
        long now = System.currentTimeMillis();

        // ---- the head: the title, what this is, and the total (± and "was" once changed)
        long total = ComingSteps.totalMs(a.plan);
        long was = total;
        for (java.util.Map.Entry<Integer, ComingSteps.Shape> e : a.comingOrig.entrySet()) {
            int si = e.getKey().intValue();
            if (!ComingSteps.after(si, curKey) || a.comingSkipped.containsKey(e.getKey())) continue;
            was += e.getValue().ms - a.comingShape(si).ms;
        }
        for (java.util.Map.Entry<Integer, List<Model.Preset>> e : a.comingSkipped.entrySet()) {
            ComingSteps.Shape o = a.comingOrig.get(e.getKey());
            was += o != null ? o.ms : ComingSteps.lengthOf(e.getValue());
        }
        // THE TOTAL IS LAID OUT FIRST (device check: "10:" and a bare "was", then "11:" over
        // "19"): a horizontal row measures the unweighted total column at its natural width
        // first and gives the title the rest, and the total's lines never wrap.
        LinearLayout hd = new LinearLayout(a);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        hd.setGravity(Gravity.TOP);
        LinearLayout hl = Ui.col(a);
        TextView h3 = new TextView(a);
        h3.setText("Coming steps");
        h3.setTextColor(Ui.TEXT);
        h3.setTextSize(19f);
        h3.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hl.addView(h3);
        TextView sub = new TextView(a);
        sub.setText("This run only. Pressure can’t be raised here.");
        sub.setTextColor(Ui.DIM);
        sub.setTextSize(Look.SP_FIELD_LABEL);
        hl.addView(sub);
        LinearLayout tot = Ui.col(a);
        tot.setGravity(Gravity.END);
        tot.setId(View.generateViewId());
        TextView tb = new TextView(a);
        tb.setText(Model.Fmt.t((total + 500L) / 1000L));
        tb.setTextColor(Ui.TEXT);
        tb.setTextSize(20f);
        tb.setTypeface(android.graphics.Typeface.MONOSPACE);
        tb.setGravity(Gravity.END);
        tb.setSingleLine(true);
        tot.addView(tb);
        long dSec = (total + 500L) / 1000L - (was + 500L) / 1000L;
        if (dSec != 0) {
            TextView chip = new TextView(a);
            chip.setText((dSec > 0 ? "+" : "−") + Model.Fmt.t(Math.abs(dSec)));
            chip.setTextColor(Ui.TEXT);
            chip.setTextSize(Look.SP_MICRO);
            chip.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD));
            chip.setBackground(Ui.roundRect(a, Look.COMING_WELL, 6));
            chip.setPadding(Ui.dp(a, 6), Ui.dp(a, 2), Ui.dp(a, 6), Ui.dp(a, 2));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.gravity = Gravity.END;
            cl.topMargin = Ui.dp(a, 2);
            tot.addView(chip, cl);
            TextView ws = new TextView(a);
            ws.setText("was " + Model.Fmt.t((was + 500L) / 1000L));
            ws.setTextColor(Ui.DIM);
            ws.setTextSize(Look.SP_FIELD_LABEL);
            ws.setGravity(Gravity.END);
            ws.setSingleLine(true);
            tot.addView(ws);
        } else {
            TextView ws = new TextView(a);
            ws.setText("total");
            ws.setTextColor(Ui.DIM);
            ws.setTextSize(Look.SP_FIELD_LABEL);
            ws.setGravity(Gravity.END);
            tot.addView(ws);
        }
        // The column never narrower than its widest line (on the device the dialog's first,
        // narrower measuring pass left it at the chip's width and clipped "10:25" to "0:25").
        float need = 0f;
        for (int ci = 0; ci < tot.getChildCount(); ci++) {
            View cv = tot.getChildAt(ci);
            if (cv instanceof TextView) {
                TextView tv = (TextView) cv;
                need = Math.max(need, tv.getPaint().measureText(tv.getText().toString())
                        + tv.getPaddingLeft() + tv.getPaddingRight());
            }
        }
        tot.setMinimumWidth((int) Math.ceil(need) + Ui.dp(a, 2));
        hd.addView(hl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMarginStart(Ui.dp(a, 10));
        hd.addView(tot, tl);
        comingHost.addView(hd, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- an inserted rest playing: its own NOW card, read-only
        if (a.restingNow) {
            LinearLayout card = comingCard(RunLook.colourOf(a.model.runColours, RunLook.WORK), true);
            comingHead(card, RunLook.colourOf(a.model.runColours, RunLook.REST),
                RunLook.colourOf(a.model.runColours, RunLook.WORK), "Rest you added", true, 0, "playing now · change it with the − / + on the run screen",
                Model.Fmt.t(Math.max(0L, a.restNowEndAt - now + 999) / 1000), null);
            comingNowSum(card, "rest " + Model.Fmt.t(Math.max(0L,
                a.restNowEndAt - (a.restNowStartAt > 0 ? a.restNowStartAt : now)) / 1000)
                + " · then back to the step it paused");
        }

        // ---- one card per block from the one playing on, skipped ones in their places
        java.util.List<Integer> blocks = ComingSteps.blocksFrom(a.plan, pi, a.comingSkipped.keySet());
        for (int bk = 0; bk < blocks.size(); bk++) {
            Integer key = blocks.get(bk);
            int s = key.intValue();
            if (ComingSteps.stageOf(s) >= r.stages.size()) continue;
            Model.Stage st = r.stages.get(ComingSteps.stageOf(s));
            boolean skipped = a.comingSkipped.containsKey(key);
            // What this card reads its figures from: a skipped block's presets are out of the
            // plan (ComingSteps#cardPresets - device check EMU9 H1).
            List<Model.Preset> src = ComingSteps.cardPresets(a.plan, a.comingSkipped, s);
            boolean isCur = s == curKey;
            long ms = 0;
            Model.Preset firstP = null;
            if (!skipped) {
                for (int i = Math.max(0, a.planIdx); i < a.plan.size(); i++) {
                    Model.Preset p = a.plan.get(i);
                    if (!ComingSteps.inBlock(p, s)) continue;
                    if (firstP == null) firstP = p;
                    ms += i == a.planIdx ? Math.max(0L, a.presetFireAt - now) : p.durMs;
                }
                if (ms <= 0) continue;
            } else if (!a.comingSkipped.get(key).isEmpty()) {
                firstP = a.comingSkipped.get(key).get(0);
                // Its own length, struck through - never 0:00 (device check EMU9b N3).
                ms = ComingSteps.lengthOf(a.comingSkipped.get(key));
            }
            // A rest set inside a work stage is a rest.
            int kind = firstP != null && firstP.rest ? RunLook.REST : RunLook.kindOf(st);
            int col = RunLook.colourOf(a.model.runColours, kind);
            boolean nowCard = isCur && !a.restingNow;
            ComingSteps.Shape sh = skipped
                ? (a.comingOrig.containsKey(key) ? a.comingOrig.get(key)
                   : ComingSteps.skippedShape(a.comingSkipped.get(key), s, a.comingIsRamp(s)))
                : a.comingShape(s);
            // The block playing reads as the pump runs it now: its sets as counted, the hold,
            // drop and drop time in force (the − / + strip's own figures).
            if (isCur && sh.kind == ComingSteps.SHAPE_BLOCK && a.planIdx >= 0) {
                int[] f = a.inForceTuple();
                sh.uh = f[LiveEdit.UH]; sh.lh = f[LiveEdit.LH]; sh.dropKpa = f[LiveEdit.LO];
                if (a.setClock.counts(a.planIdx)) sh.sets = a.setClock.sets();
            }
            ComingSteps.Shape orig = a.comingOrig.get(key);
            int changes = isCur || skipped ? 0 : ComingSteps.changes(orig, sh);
            boolean warmUp = RunShape.isWarmUp(st) && !st.retention;
            int workCol = RunLook.colourOf(a.model.runColours, RunLook.WORK);
            LinearLayout card = comingCard(nowCard ? workCol : col, nowCard);
            String name = comingName(s, st, sh, kind, skipped, src);
            String subLine = isCur ? (nowCard ? "playing now · change it with the − / + on the run "
                    + "screen" : "resumes after the rest")
                : comingSub(s, st, sh, src);
            boolean offerSkip = !isCur && !warmUp && (skipped
                || ComingSteps.skipRefusal(r.stages, a.plan, s, a.planIdx) == null);
            comingHead(card, col, nowCard ? workCol : col, name, nowCard, changes, subLine,
                Model.Fmt.t((ms + 999) / 1000), offerSkip ? new ComingSkipTap(s, skipped) : null);
            if (skipped) {
                card.setAlpha(0.6f);
                continue;
            }
            if (isCur) {
                comingNowSum(card, comingSummary(s, st, sh));
                continue;
            }
            if ((st.rest && st.awaitAck) || (firstP != null && firstP.awaitAck)) {
                comingWhy(card, "Waits for you to change the cylinder.");
            } else if (st.rest && st.manual) {
                comingWhy(card, "Done by hand, with the pump vented; waits for you to press Done.");
            } else if (sh.kind == ComingSteps.SHAPE_BLOCK) {
                int bi = ComingSteps.blockOf(a.plan, s);
                Model.Preset p = a.plan.get(bi);
                comingGroup(card, "Work", cycleText(sh), false);
                LinearLayout w = comingList(card, RunLook.colourOf(a.model.runColours, RunLook.WORK), false);
                comingRow(w, "Sets", String.valueOf(sh.sets), "", false,
                    new ComingAct(ComingAct.SETS, s, -1), new ComingAct(ComingAct.SETS, s, 1),
                    ComingSteps.setsRefusal(sh.sets, sh.sets - 1, 1, RunLook.cycleMs(p), total),
                    ComingSteps.setsRefusal(sh.sets, sh.sets + 1, 1, RunLook.cycleMs(p), total));
                comingRow(w, "Hold each", Model.Fmt.t(sh.uh), "", false,
                    new ComingAct(ComingAct.HOLD, s, -ComingSteps.HOLD_STEP_SEC),
                    new ComingAct(ComingAct.HOLD, s, ComingSteps.HOLD_STEP_SEC),
                    ComingSteps.holdRefusal(sh.uh, ComingSteps.nextHold(sh.uh, -ComingSteps.HOLD_STEP_SEC),
                        sh.lh, sh.sets, total),
                    ComingSteps.holdRefusal(sh.uh, ComingSteps.nextHold(sh.uh, ComingSteps.HOLD_STEP_SEC),
                        sh.lh, sh.sets, total));
                comingGroup(card, "Drop", null, true);
                LinearLayout d = comingList(card, Ui.CMD, true);
                int up = RunEdit.clampUpper(p.up, a.model.ceilKpa);
                comingRow(d, "Drop to", Model.Fmt.pBare(sh.dropKpa), Model.Fmt.unitWord(), true,
                    new ComingAct(ComingAct.DROP, s, -ComingSteps.DROP_STEP_KPA),
                    new ComingAct(ComingAct.DROP, s, ComingSteps.DROP_STEP_KPA),
                    ComingSteps.dropRefusal(sh.dropKpa, sh.dropKpa - ComingSteps.DROP_STEP_KPA, up),
                    ComingSteps.dropRefusal(sh.dropKpa, sh.dropKpa + ComingSteps.DROP_STEP_KPA, up));
                comingRow(d, "Drop time", Model.Fmt.t(sh.lh), "", true,
                    new ComingAct(ComingAct.DROP_TIME, s, -ComingSteps.DROP_TIME_STEP_SEC),
                    new ComingAct(ComingAct.DROP_TIME, s, ComingSteps.DROP_TIME_STEP_SEC),
                    ComingSteps.dropTimeRefusal(sh.lh, sh.lh - 1, sh.uh, sh.sets, total),
                    ComingSteps.dropTimeRefusal(sh.lh, sh.lh + 1, sh.uh, sh.sets, total));
                if (sh.lh == 0) comingWarn(card, "No drop: each set becomes one continuous hold.");
            } else if (sh.kind == ComingSteps.SHAPE_REST) {
                LinearLayout l = comingList(card, RunLook.colourOf(a.model.runColours, RunLook.REST), false);
                comingRow(l, "Length", Model.Fmt.t(sh.restSec), "", false,
                    new ComingAct(ComingAct.REST, s, -ComingSteps.REST_STEP_SEC),
                    new ComingAct(ComingAct.REST, s, ComingSteps.REST_STEP_SEC),
                    ComingSteps.restRefusal(sh.restSec, sh.restSec - ComingSteps.REST_STEP_SEC, -1L, total),
                    ComingSteps.restRefusal(sh.restSec, sh.restSec + ComingSteps.REST_STEP_SEC, -1L, total));
            } else if (sh.kind == ComingSteps.SHAPE_RAMP) {
                int first = ComingSteps.rampOf(a.plan, s);
                comingGroup(card, "Ramp", sh.steps + " × " + Model.Fmt.t(sh.stepSec), false);
                comingStairs(card, first, sh.steps);
                LinearLayout l = comingList(card, RunLook.colourOf(a.model.runColours, RunLook.WORK), false);
                comingRow(l, "Steps", String.valueOf(sh.steps), "", false,
                    new ComingAct(ComingAct.RAMP_STEPS, s, -1), new ComingAct(ComingAct.RAMP_STEPS, s, 1),
                    ComingSteps.rampStepsRefusal(sh.steps, sh.steps - 1, sh.stepSec, total),
                    ComingSteps.rampStepsRefusal(sh.steps, sh.steps + 1, sh.stepSec, total));
                // One whole cycle a step a tap (0.10): the figure says how many.
                comingRow(l, "Time per step", Model.Fmt.t(sh.stepSec) + " · " + sh.cycles
                        + (sh.cycles == 1 ? " cycle" : " cycles"), "", false,
                    new ComingAct(ComingAct.RAMP_STEP_TIME, s, -ComingSteps.STEP_TIME_STEP_SEC),
                    new ComingAct(ComingAct.RAMP_STEP_TIME, s, ComingSteps.STEP_TIME_STEP_SEC),
                    ComingSteps.rampStepTimeRefusal(a.plan, first, sh.steps, sh.cycles - 1, total,
                        ComingSteps.rampCycleSec(a.plan, first, sh.steps)),
                    ComingSteps.rampStepTimeRefusal(a.plan, first, sh.steps, sh.cycles + 1, total,
                        ComingSteps.rampCycleSec(a.plan, first, sh.steps)));
                comingPressureRow(l, first, sh.steps);
            } else if (warmUp) {
                comingWhy(card, "The warm-up can’t be changed here.");
            } else {
                comingWhy(card, "A long hold or a set of its own repetitions: it can be skipped.");
            }
            String hint = comingHint.get(key);
            if (hint != null) comingHintLine(card, hint);
            if (changes > 0) comingUndoLink(card, s, changes);
        }

        // Nothing after the step playing: said under its card, as the mock says it.
        boolean anyLater = false;
        for (int bk = 0; bk < blocks.size(); bk++)
            if (ComingSteps.after(blocks.get(bk).intValue(), curKey)) anyLater = true;
        if (!anyLater) {
            TextView none = new TextView(a);
            none.setText("Nothing comes after this step.");
            none.setTextColor(Ui.DIM);
            none.setTextSize(Look.SP_FIELD_LABEL);
            none.setPadding(Ui.dp(a, 2), Ui.dp(a, 10), 0, 0);
            comingHost.addView(none);
        }

        // ---- the foot: Undo all, Done
        boolean any = !a.comingSkipped.isEmpty();
        for (java.util.Map.Entry<Integer, ComingSteps.Shape> e : a.comingOrig.entrySet()) {
            int si = e.getKey().intValue();
            if (ComingSteps.after(si, curKey) && !a.comingSkipped.containsKey(e.getKey())
                    && ComingSteps.changes(e.getValue(), a.comingShape(si)) > 0) any = true;
        }
        LinearLayout foot = new LinearLayout(a);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        Button undo = new Button(a);
        undo.setText("Undo all");
        undo.setAllCaps(false);
        undo.setTextColor(Ui.TEXT);
        undo.setTextSize(Look.SP_BODY);
        undo.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.graphics.drawable.GradientDrawable ub = Ui.roundRect(a, 0, Look.R_CARD);
        ub.setStroke(Math.max(1, Ui.dp(a, 1)), Ui.LINE);
        undo.setBackground(ub);
        undo.setStateListAnimator(null);
        undo.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
        // polish RN-14: nothing to undo is a real disabled button, not a faded one that
        // still took the tap.
        if (!any) Ui.stateFill(a, undo, false, false);
        undo.setContentDescription(any ? "Undo all. Every change and skip in this sheet put back"
                                       : "Undo all, nothing to undo");
        undo.setOnClickListener(new ComingAct(ComingAct.UNDO_ALL, -1));
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(a, 48));
        ul.rightMargin = Ui.dp(a, 8);
        foot.addView(undo, ul);
        Button done = new Button(a);
        done.setText("Done");
        done.setAllCaps(false);
        done.setTextSize(Look.SP_BODY);
        done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        // The app's primary colour, not the Work step's (polish RN-14): Done is an action.
        done.setTextColor(Ui.labelOn(Ui.ACCENT));
        done.setBackground(Ui.roundRect(a, Ui.ACCENT, Look.R_CARD));
        done.setStateListAnimator(null);
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (comingDialog != null && comingDialog.isShowing()) comingDialog.dismiss();
            }
        });
        foot.addView(done, new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = Ui.dp(a, 10);
        comingHost.addView(foot, fl);
    }

    /** The name of the stage `p` was expanded from, or its own label without one. */
    private String stageNameOf(Model.Preset p) {
        Model.Routine r = a.runRoutine;
        if (p != null && r != null && p.stageIdx >= 0 && p.stageIdx < r.stages.size())
            return r.stages.get(p.stageIdx).name;
        return p == null ? "" : p.label;
    }

    /** "Sets 6–10", "Ramp · 5 steps", "Rest", "Warm-up" - or the stage's own name. */
    private String comingName(int s, Model.Stage st, ComingSteps.Shape sh, int kind,
                              boolean skipped, List<Model.Preset> src) {
        if (sh.kind == ComingSteps.SHAPE_BLOCK && kind == RunLook.WORK) {
            // A skipped block is named by its own count, never a range: the set numbers are
            // the run's, and a skip renumbers what follows it (device check EMU9b N3 - two
            // skipped blocks, and the live one after them, all read "Sets 1–5").
            if (skipped) return ComingSteps.skippedSetsName(sh.sets);
            int bi = ComingSteps.blockOf(a.plan, s);
            int first = bi >= 0 ? firstSetOf(bi) : 0;
            if (first > 0)
                return sh.sets > 1 ? "Sets " + first + "–" + (first + sh.sets - 1) : "Set " + first;
        }
        if (sh.kind == ComingSteps.SHAPE_RAMP) return "Ramp · " + sh.steps + " steps";
        // A step done by hand is named, never "Rest": "Tunica release (by hand)" (ByHand).
        if (st.rest && st.manual) return ByHand.coming(st.name);
        if (st.rest) return st.awaitAck ? (st.name == null ? "Change cylinder" : st.name) : "Rest";
        if (kind == RunLook.REST) return "Rest";
        // A stage of several sets names each by its set; a stage of one set by the stage.
        if (st.setIds != null && st.setIds.size() > 1) {
            String set = blockSetName(s, src);
            if (set != null) return set;
        }
        return st.name == null || st.name.trim().length() == 0 ? RunLook.nowWord(RunLook.kindOf(st))
                                                              : st.name.trim();
    }

    /** The name of the set block `key` plays, or null - read from `src`, the card's own
     *  presets (a skipped block's are out of the plan: it was named by its stage, EMU9b N3). */
    private String blockSetName(int key, List<Model.Preset> src) {
        for (int i = 0; src != null && i < src.size(); i++) {
            Model.Preset p = src.get(i);
            if (!ComingSteps.inBlock(p, key) || p.setId == null) continue;
            Model.Set set = a.model.set(p.setId);
            return set == null || set.name == null ? null : set.name.trim();
        }
        return null;
    }

    /** A later card's sub line: what a rest leads to, a ramp's pressures, a block's pull. */
    private String comingSub(int s, Model.Stage st, ComingSteps.Shape sh, List<Model.Preset> src) {
        if (sh.kind == ComingSteps.SHAPE_REST || st.rest) {
            int nx = nextRealBlock(s);
            if (nx < 0) return "then the end";
            ComingSteps.Shape n = a.comingShape(nx);
            Model.Stage ns = a.runRoutine.stages.get(ComingSteps.stageOf(nx));
            if (n.kind == ComingSteps.SHAPE_RAMP) return "then the ramp";
            if (n.kind == ComingSteps.SHAPE_BLOCK && RunLook.kindOf(ns) == RunLook.WORK) {
                int bi = ComingSteps.blockOf(a.plan, nx);
                int f = bi >= 0 ? firstSetOf(bi) : 0;
                if (f > 0) return "then set " + f;
            }
            return "then " + (ns.name == null ? "the next step" : ns.name.trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (sh.kind == ComingSteps.SHAPE_RAMP) {
            int f = ComingSteps.rampOf(src, s);
            return rampRange(src, f, sh.steps);
        }
        if (sh.kind == ComingSteps.SHAPE_BLOCK) {
            int bi = ComingSteps.blockOf(src, s);
            if (bi < 0) return "";
            return "at " + Model.Fmt.p(RunEdit.clampUpper(src.get(bi).up, a.model.ceilKpa));
        }
        return "";
    }

    /** The playing card's one summary line. */
    private String comingSummary(int s, Model.Stage st, ComingSteps.Shape sh) {
        if (sh.kind == ComingSteps.SHAPE_BLOCK)
            return cycleText(sh) + " · drop to " + Model.Fmt.p(sh.dropKpa);
        if (sh.kind == ComingSteps.SHAPE_RAMP)
            return sh.steps + " × " + Model.Fmt.t(sh.stepSec) + " · "
                + rampRange(ComingSteps.rampOf(a.plan, s), sh.steps);
        if (sh.kind == ComingSteps.SHAPE_REST) return "rest " + Model.Fmt.t(sh.restSec);
        long ms = sh.ms;
        if (RunShape.isWarmUp(st)) return "warm-up " + Model.Fmt.t((ms + 500) / 1000);
        return (st.name == null ? "step" : st.name.trim()) + " " + Model.Fmt.t((ms + 500) / 1000);
    }

    /** "5 × (1:00 hold + 0:05 drop)". */
    private static String cycleText(ComingSteps.Shape sh) {
        return sh.sets + " × (" + Model.Fmt.t(sh.uh) + " hold + " + Model.Fmt.t(sh.lh) + " drop)";
    }

    /** "−3.0 → −5.9 inHg": a ramp's first and last pulls, as they will be sent. */
    private String rampRange(int first, int n) {
        return rampRange(a.plan, first, n);
    }

    /** The same, read from `src` (a skipped ramp's own presets - ComingSteps#cardPresets). */
    private String rampRange(List<Model.Preset> src, int first, int n) {
        if (src == null || first < 0 || n < 1 || first + n > src.size()) return "";
        int from = RunEdit.clampUpper(src.get(first).up, a.model.ceilKpa);
        int to = RunEdit.clampUpper(src.get(first + n - 1).up, a.model.ceilKpa);
        return Model.Fmt.pBare(from) + " → " + Model.Fmt.p(to);
    }

    /** The next block after `key` the run will play (skipped ones are out of the plan), or
     *  -1. */
    private int nextRealBlock(int key) {
        for (int i = 0; i < a.plan.size(); i++) {
            int k = ComingSteps.key(a.plan.get(i));
            if (ComingSteps.after(k, key)) return k;
        }
        return -1;
    }

    private LinearLayout comingCard(int colour, boolean current) {
        LinearLayout card = Ui.col(a);
        android.graphics.drawable.GradientDrawable bg = Ui.roundRect(a, Ui.SURFHI, 14);
        bg.setStroke(Math.max(1, Ui.dp(a, 1)), current ? colour : Ui.LINE);
        card.setBackground(bg);
        int p10 = Ui.dp(a, 10);
        card.setPadding(p10, p10, p10, p10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 10);
        comingHost.addView(card, lp);
        return card;
    }

    /** A card's first row: the dot, the name (NOW tag, changed dot), its sub line, its time
     *  and - on a later step - the Skip switch. */
    private void comingHead(LinearLayout card, int colour, int nowColour, String name, boolean now,
                            int changes, String sub, String time, View.OnClickListener skip) {
        LinearLayout h = new LinearLayout(a);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        View d = new View(a);
        d.setBackground(dot(colour));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(a, 10), Ui.dp(a, 10));
        dl.rightMargin = Ui.dp(a, 8);
        h.addView(d, dl);
        LinearLayout nm = Ui.col(a);
        TextView n = new TextView(a);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(name);
        boolean skipped = skip instanceof ComingSkipTap && ((ComingSkipTap) skip).skipped;
        if (skipped)
            sb.setSpan(new android.text.style.StrikethroughSpan(), 0, name.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (now) {
            sb.append("  NOW");
            int at = sb.length() - 3;
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.68f), at, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.BackgroundColorSpan(nowColour), at, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.ForegroundColorSpan(RunLook.inkOn(nowColour)), at,
                sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (changes > 0) {
            sb.append("  ●");
            int at = sb.length() - 1;
            sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.CMD), at, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.6f), at, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        n.setText(sb);
        n.setTextColor(Ui.TEXT);
        n.setTextSize(Look.SP_CHIP);
        n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nm.addView(n);
        if (sub != null && sub.length() > 0) {
            TextView sv = new TextView(a);
            sv.setText(sub);
            sv.setTextColor(Ui.DIM);
            sv.setTextSize(Look.SP_FIELD_LABEL);
            nm.addView(sv);
        }
        h.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(a);
        t.setText(time);
        t.setTextColor(Ui.TEXT);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(Look.SP_CHIP);
        t.setPadding(Ui.dp(a, 6), 0, 0, 0);
        h.addView(t);
        if (skip != null) h.addView(comingSwitch(skipped, name, skip));
        h.setContentDescription(name + (now ? ", playing now" : "") + (skipped ? ", skipped" : "")
            + (changes > 0 ? ", changed" : "") + ". " + (sub == null ? "" : sub + ". ") + time);
        card.addView(h);
    }

    /** The Skip switch: the app's one switch (Ui.switchView, polish RN-15), in a row 48 dp
     *  tall. */
    private View comingSwitch(boolean on, String name, View.OnClickListener tap) {
        LinearLayout sw = new LinearLayout(a);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.setMinimumHeight(Ui.dp(a, 48));
        sw.setPadding(Ui.dp(a, 8), 0, 0, 0);
        TextView w = new TextView(a);
        w.setText(on ? "Skipped" : "Skip");
        w.setTextColor(Ui.TEXT);
        w.setTextSize(Look.SP_CAPTION);
        w.setPadding(0, 0, Ui.dp(a, 6), 0);
        sw.addView(w);
        sw.addView(Ui.switchView(a, on));
        sw.setClickable(true);
        sw.setFocusable(true);
        sw.setOnClickListener(tap);
        sw.setContentDescription((on ? "Skipped: " : "Skip ") + name + ". Switch, "
            + (on ? "on" : "off"));
        sw.setSelected(on);
        return sw;
    }

    private void comingNowSum(LinearLayout card, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(Ui.TEXT);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(Look.SP_LABEL);
        t.setPadding(0, Ui.dp(a, 8), 0, 0);
        card.addView(t);
    }

    /** A group's label: "WORK  5 × (1:00 hold + 0:05 drop)" in the work colour, "DROP" in amber. */
    private void comingGroup(LinearLayout card, String label, String note, boolean drop) {
        LinearLayout g = new LinearLayout(a);
        g.setOrientation(LinearLayout.HORIZONTAL);
        g.setGravity(Gravity.BOTTOM);
        TextView l = new TextView(a);
        l.setText(label.toUpperCase(java.util.Locale.ROOT));
        l.setTextColor(drop ? Ui.CMD : RunLook.colourOf(a.model.runColours, RunLook.WORK));
        l.setTextSize(Look.SP_MICRO);
        l.setLetterSpacing(0.12f);
        l.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        g.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (note != null) {
            TextView n = new TextView(a);
            n.setText(note);
            n.setTextColor(Ui.DIM);
            n.setTextSize(Look.SP_FIELD_LABEL);
            g.addView(n);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 8);
        card.addView(g, lp);
    }

    /** The well a group's rows sit in, with its colour's edge down the left. */
    private LinearLayout comingList(LinearLayout card, int edge, boolean drop) {
        LinearLayout l = Ui.col(a);
        android.graphics.drawable.GradientDrawable well = Ui.roundRect(a, Look.COMING_WELL, 12);
        well.setStroke(Math.max(1, Ui.dp(a, 1)), drop ? ((0x73 << 24) | (Ui.CMD & 0xFFFFFF)) : Ui.LINE);
        View bar = new View(a);
        bar.setBackgroundColor(edge);
        // The group's colour down its left edge, inside the well's rounded corners.
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setBackground(well);
        outer.setClipToOutline(true);
        outer.addView(bar, new LinearLayout.LayoutParams(Ui.dp(a, 3), ViewGroup.LayoutParams.MATCH_PARENT));
        outer.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 6);
        card.addView(outer, lp);
        return l;
    }

    /** One row: its name at the left, [−] figure [+] at the right. A key its rule refuses is
     *  dimmed and still tappable - the tap says which limit, in the card. */
    private void comingRow(LinearLayout list, String label, String value, String unit, boolean drop,
                           View.OnClickListener less, View.OnClickListener more,
                           String lessWhy, String moreWhy) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(a, 52));
        row.setPadding(Ui.dp(a, 10), Ui.dp(a, 2), Ui.dp(a, 4), Ui.dp(a, 2));
        if (list.getChildCount() > 0) {
            View rule = new View(a);
            rule.setBackgroundColor(Ui.LINE);
            list.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(a, 1))));
        }
        TextView l = new TextView(a);
        l.setText(label);
        l.setTextColor(Ui.TEXT);
        l.setTextSize(Look.SP_LABEL);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String now = value + (unit != null && unit.length() > 0 ? " " + unit : "") + " now";
        row.addView(comingKey("−", "Less " + label.toLowerCase(java.util.Locale.ROOT) + ", "
            + now, less, lessWhy));
        TextView v = new TextView(a);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(value);
        if (unit != null && unit.length() > 0) {
            int at = sb.length();
            sb.append(" ").append(unit);
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.75f), at, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.DIM), at, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.TypefaceSpan("sans-serif"), at, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        v.setText(sb);
        v.setTextColor(drop ? Ui.CMD : Ui.TEXT);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setTextSize(Look.SP_CHIP);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setMinWidth(Ui.dp(a, 60));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(v);
        row.addView(comingKey("+", "More " + label.toLowerCase(java.util.Locale.ROOT) + ", "
            + now, more, moreWhy));
        list.addView(row, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** A 42 dp key drawn inside a 48 dp touch target. */
    private Button comingKey(String glyph, String said, View.OnClickListener tap, String why) {
        Button b = new Button(a);
        b.setText(glyph);
        b.setAllCaps(false);
        b.setTextColor(Ui.TEXT);
        b.setTextSize(Look.SP_HEADING);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setIncludeFontPadding(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        android.graphics.drawable.GradientDrawable k = Ui.roundRect(a, Look.COMING_KEY, Look.R_CTRL);
        k.setStroke(Math.max(1, Ui.dp(a, 1)), Ui.LINE);
        int in = Ui.dp(a, 3);
        b.setBackground(new android.graphics.drawable.InsetDrawable(k, in, in, in, in));
        b.setAlpha(why != null ? 0.3f : 1f);
        b.setContentDescription(said + (why != null ? ". " + why : ""));
        b.setOnClickListener(tap);
        b.setOnTouchListener(new Ui.Press());
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
        return b;
    }

    /** The ramp's pressures, read-only: they are the ends its steps re-spread between. */
    private void comingPressureRow(LinearLayout list, int first, int n) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(a, 52));
        row.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 4));
        View rule = new View(a);
        rule.setBackgroundColor(Ui.LINE);
        list.addView(rule, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(a, 1))));
        TextView l = new TextView(a);
        l.setText("Pressure");
        l.setTextColor(Ui.TEXT);
        l.setTextSize(Look.SP_LABEL);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout ro = Ui.col(a);
        ro.setGravity(Gravity.END);
        TextView v = new TextView(a);
        v.setText(rampRange(first, n));
        v.setTextColor(Ui.TEXT);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setTextSize(Look.SP_CHIP);
        v.setGravity(Gravity.END);
        ro.addView(v);
        TextView s = new TextView(a);
        s.setText("steps re-spread between these");
        s.setTextColor(Ui.DIM);
        s.setTextSize(Look.SP_MICRO);
        s.setGravity(Gravity.END);
        ro.addView(s);
        row.addView(ro);
        list.addView(row);
    }

    /** The ramp's staircase: its steps as they will climb. */
    private void comingStairs(LinearLayout card, int first, int n) {
        if (first < 0 || n < 1) return;
        int[] ups = new int[n];
        for (int k = 0; k < n; k++) ups[k] = a.plan.get(first + k).up;
        View v = new StairsView(a, ups, RunLook.colourOf(a.model.runColours, RunLook.WORK));
        v.setContentDescription("Ramp steps, climbing from " + rampRange(first, n));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 34));
        lp.topMargin = Ui.dp(a, 6);
        card.addView(v, lp);
    }

    /** A ramp drawn as the mock draws it: one level per step, a riser between. */
    private static final class StairsView extends View {
        private final int[] ups;
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        StairsView(Activity a, int[] u, int colour) {
            super(a);
            ups = u;
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(1.8f * a.getResources().getDisplayMetrics().density);
            ink.setColor(colour);
            ink.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || ups.length == 0) return;
            int max = 1;
            for (int k = 0; k < ups.length; k++) max = Math.max(max, ups[k]);
            float pad = 4f, bottom = h - 2f, span = h - 6f;
            float sw = (w - 2 * pad) / ups.length;
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(pad, bottom);
            for (int k = 0; k < ups.length; k++) {
                float y = bottom - (ups[k] / (float) max) * span;
                p.lineTo(pad + k * sw, y);
                p.lineTo(pad + (k + 1) * sw, y);
            }
            c.drawPath(p, ink);
        }
    }

    private void comingWhy(LinearLayout card, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(Ui.DIM);
        t.setTextSize(Look.SP_FIELD_LABEL);
        t.setPadding(0, Ui.dp(a, 6), 0, 0);
        card.addView(t);
    }

    private void comingWarn(LinearLayout card, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(Look.WARN_INK);
        t.setTextSize(Look.SP_FIELD_LABEL);
        t.setPadding(0, Ui.dp(a, 8), 0, 0);
        card.addView(t);
    }

    private void comingHintLine(LinearLayout card, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(Ui.CMD);
        t.setTextSize(Look.SP_FIELD_LABEL);
        t.setPadding(0, Ui.dp(a, 8), 0, 0);
        t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(t);
    }

    private void comingUndoLink(LinearLayout card, int stage, int changes) {
        TextView l = new TextView(a);
        android.text.SpannableString s = new android.text.SpannableString(
            changes > 1 ? "Undo these changes" : "Undo this change");
        s.setSpan(new android.text.style.UnderlineSpan(), 0, s.length(),
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        l.setText(s);
        l.setTextColor(Ui.DIM);
        l.setTextSize(Look.SP_CAPTION);
        Ui.medium(l);
        l.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        l.setMinHeight(Ui.dp(a, 48));
        l.setPadding(Ui.dp(a, 12), 0, 0, 0);
        l.setClickable(true);
        l.setFocusable(true);
        l.setOnClickListener(new ComingAct(ComingAct.UNDO, stage));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        card.addView(l, lp);
    }

    /** The Skip switch's tap: through comingSkip, like every skip. */
    private final class ComingSkipTap implements View.OnClickListener {
        final int stage;
        final boolean skipped;
        ComingSkipTap(int s, boolean on) { stage = s; skipped = on; }
        @Override public void onClick(View v) {
            new ComingAct(ComingAct.SKIP, stage, skipped ? 0 : 1).onClick(v);
        }
    }

    /** One tap on the sheet: the change, through the Activity's guarded method, a refusal in
     *  the card it was tapped in, and the sheet redrawn. */
    private final class ComingAct implements View.OnClickListener {
        static final int SETS = 0, HOLD = 1, REST = 2, SKIP = 3, DROP = 4, DROP_TIME = 5,
                         RAMP_STEPS = 6, RAMP_STEP_TIME = 7, UNDO = 8, UNDO_ALL = 9;
        private final int what, stage, delta;
        ComingAct(int w, int s) { this(w, s, 0); }
        ComingAct(int w, int s, int d) { what = w; stage = s; delta = d; }
        @Override public void onClick(View v) {
            // A LATER BLOCK'S DROP PAST THE FLOOR IS ASKED ONCE A RUN (owner, 2026-09-30), on
            // a dialog above the sheet; confirmed, the tap goes through as any other.
            if (what == DROP && a.comingDropAsks(stage, delta)) {
                Ui.dress(a, a.holdRunSheet(Ui.dialog(a)
                    .setTitle(RunEdit.DROP_WARN_TITLE)
                    .setMessage(RunEdit.dropWarning())
                    .setPositiveButton(RunEdit.DROP_WARN_GO, new ConfirmComingDrop(this, v))
                    .setNegativeButton(RunEdit.DROP_WARN_BACK, null)
                    .show()));
                return;
            }
            String said;
            boolean moved;
            switch (what) {
                case SETS: said = a.comingSets(stage, delta); moved = said != null && said.endsWith("this run only."); break;
                case HOLD: said = a.comingHold(stage, delta); moved = said != null && said.endsWith("this run only."); break;
                case REST: said = a.comingRest(stage, delta); moved = said != null && said.endsWith("this run only."); break;
                case DROP: said = a.comingDrop(stage, delta); moved = said != null && said.endsWith("this run only."); break;
                case DROP_TIME: said = a.comingDropTime(stage, delta); moved = said != null && said.endsWith("this run only."); break;
                case RAMP_STEPS: said = a.comingRampSteps(stage, delta); moved = said != null && said.endsWith("this run only."); break;
                case RAMP_STEP_TIME: said = a.comingRampStepTime(stage, delta); moved = said != null && said.endsWith("this run only."); break;
                case SKIP: said = a.comingSkip(stage, delta != 0); moved = said != null && said.endsWith("this run only."); break;
                case UNDO: said = a.comingUndo(stage); moved = "Put back as it was.".equals(said); break;
                case UNDO_ALL:
                    if (v != null && v.getAlpha() < 0.5f) return;   // nothing to undo
                    said = a.comingUndoAll();
                    moved = true;
                    comingHint.clear();
                    break;
                default: said = null; moved = false;
            }
            Integer key = Integer.valueOf(stage);
            if (what != UNDO_ALL) {
                if (moved) comingHint.remove(key);
                else if (said != null) comingHint.put(key, said);
            }
            if (!moved && v != null)
                v.performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
                    ? android.view.HapticFeedbackConstants.REJECT
                    : android.view.HapticFeedbackConstants.LONG_PRESS);
            fillComingSteps();
            if (said != null && comingHost != null) comingHost.announceForAccessibility(said);
        }
    }

    /** Coming steps' drop warning answered "keep it": remembered for this run, then the tap. */
    private final class ConfirmComingDrop implements DialogInterface.OnClickListener {
        private final ComingAct act;
        private final View view;
        ConfirmComingDrop(ComingAct c, View v) { act = c; view = v; }
        @Override public void onClick(DialogInterface d, int w) {
            a.dropWarned = true;
            act.onClick(view);
        }
    }

    /* ========================= THE TOP OF THE RUN SCREEN (0.10) =========================
     *
     * The live stage bar, the status line and the NOW card's time and next line, painted
     * every tick from the SAME dispIdx, preset and elapsed refreshRunScreen derived (defect
     * #25's rule) - nothing here reads the clock or the plan a second way. The colours are
     * RunLook's, from Settings › Run colours; none of them is STOP's red and nothing here
     * touches STOP. */

    /** Is a rest playing: the plan's own, or one the user inserted. */
    private boolean inRest(Model.Preset cur) {
        return a.restingNow || (cur != null && cur.rest);
    }

    /** Which set of the routine's WORK sets is playing, and of how many - numbered across the
     *  whole routine, as a person counts them ("set 6 of 10" after five and a rest). A block
     *  that is not work (the fatigue block, traction) counts its own sets. {0, 0} when the
     *  step does not cycle. */
    /** {set playing, sets in the block} for the step playing, as the pump runs it
     *  (SessionActivity#setClock: kept across a live change and a pause), or null when it
     *  does not count this step. */
    private int[] clockSet(Model.Preset cur, long now) {
        if (cur == null || a.planIdx < 0 || a.planIdx >= a.plan.size()
                || a.plan.get(a.planIdx) != cur || !a.setClock.counts(a.planIdx))
            return null;
        return new int[] { a.setClock.set(now), a.setClock.sets() };
    }

    private int[] setOf(Model.Preset cur, int dispIdx, long inPresetMs) {
        if (stepsNotSets(cur)) return new int[] { 0, 0 };
        int k = RunLook.setAt(cur, inPresetMs);
        int n = RunLook.setsIn(cur);
        int[] live = dispIdx == a.planIdx ? clockSet(cur, System.currentTimeMillis()) : null;
        if (live != null && n > 0) { k = live[0]; n = live[1]; }
        if (k <= 0 || n <= 0) return new int[] { 0, 0 };
        Model.Routine r = a.runRoutine;
        if (r == null) return new int[] { k, n };
        // R11-2: one count for the words and the bar (StageBar#setNumber) - FIX11 F2: the
        // fatigue block's too, over its stage, its climb's holds included.
        countSteps();
        return StageBar.setNumber(stepKinds, stepRamp, stepSets, stepStage, dispIdx, k, n);
    }

    /** R11-2 - per plan index, as the run counts it: the stage's kind, whether it is a ramp's
     *  step, and the sets it runs (SessionActivity#setsOfStep; 0 for a step not counted in
     *  sets). Read by the status line's "set N of M" and the live bar, so they are one count. */
    private int[] stepKinds = new int[0], stepSets = new int[0], stepStage = new int[0];
    private boolean[] stepRamp = new boolean[0];

    private void countSteps() {
        int m = a.plan.size();
        if (stepKinds.length != m) {
            stepKinds = new int[m];
            stepSets = new int[m];
            stepStage = new int[m];
            stepRamp = new boolean[m];
        }
        for (int i = 0; i < m; i++) {
            Model.Preset p = a.plan.get(i);
            int k = kindAt(p);
            stepKinds[i] = k;
            stepStage[i] = p.stageIdx;
            stepRamp[i] = stepsNotSets(p);
            stepSets[i] = stepRamp[i] || (k != RunLook.WORK && k != RunLook.FATIGUE
                                          && k != RunLook.TRACTION) ? 0 : a.setsOfStep(i);
        }
    }

    /** The first set number a preset would start on, counted as {@link #setOf} counts. */
    private int firstSetOf(int idx) {
        if (idx < 0 || idx >= a.plan.size()) return 0;
        Model.Preset p = a.plan.get(idx);
        if (RunLook.setsIn(p) <= 0 || stepsNotSets(p)) return 0;
        countSteps();
        int n = stepSets[idx] > 0 ? stepSets[idx] : RunLook.setsIn(p);
        return StageBar.setNumber(stepKinds, stepRamp, stepSets, stepStage, idx, 1, n)[0];
    }

    /**
     * FIX11 F2 - IS THIS STEP COUNTED IN STEPS RATHER THAN SETS? A ramp's step is (a warm-up or
     * a work block's own climb says "RAMP · STEP 2 OF 4") - but not the fatigue block's
     * two-hold climb: those are two of the block's fifteen holds, as the notice and the bar
     * count them, so the status line counts them as the block's sets 1 and 2.
     */
    private boolean stepsNotSets(Model.Preset p) {
        return rampStep(p) && kindAt(p) != RunLook.FATIGUE;
    }

    /** Is this preset a step of a ramp (its set is one)? A ramp counts steps, never sets. */
    private boolean rampStep(Model.Preset p) {
        if (p == null || p.setId == null) return false;
        Model.Set set = a.model.set(p.setId);
        return set != null && set.ramp;
    }

    private int kindAt(Model.Preset p) {
        Model.Routine r = a.runRoutine;
        if (p == null) return RunLook.WORK;
        if (p.rest) return RunLook.REST;
        Model.Stage st = (r != null && p.stageIdx >= 0 && p.stageIdx < r.stages.size())
            ? r.stages.get(p.stageIdx) : null;
        return RunLook.kindOf(st);
    }

    /** The colour of `kind` as the run shows it, or -1 when the run is not coloured. A HOLD
     *  is always its colour (white by default): a held run must never read as a running one. */
    private int runColour(int kind) {
        if (a.model.runColourOn || kind == RunLook.HOLD)
            return RunLook.colourOf(a.model.runColours, kind);
        return -1;
    }

    /** Does `s` fit on `t`'s one line as it is laid out now? True before the first layout. */
    private static boolean fitsOneLine(TextView t, String s) {
        int avail = t.getWidth() - t.getPaddingLeft() - t.getPaddingRight();
        if (avail <= 0) return true;
        return t.getPaint().measureText(s) <= avail;
    }

    /** The step playing is in its drop phase, as the status line last said. */
    private boolean dropNow;

    private void paintRunTop(Model.Preset cur, Model.Preset nxt, int dispIdx, long inPresetMs,
                             int commandedKpa, boolean dropPhase, boolean assessAfter,
                             int nextUp, long now) {
        if (a.runStatus == null) return;
        Model.Routine r = a.runRoutine;
        boolean resting = inRest(cur);
        int stageKind = cur.rest ? RunLook.REST : kindAt(cur);
        int[] set = resting ? new int[] { 0, 0 } : setOf(cur, dispIdx, inPresetMs);

        RunLook.Now n = new RunLook.Now();
        n.armed = a.presetArmed() || a.restingNow;
        n.holding = a.holding;
        n.awaitingAck = a.awaitingAck;
        // The release says BY HAND on this line, never REST (ByHand).
        n.byHand = !a.restingNow && ByHand.waitsAfterClock(cur);
        n.resting = resting;
        n.restLeftMs = a.restingNow ? a.restNowEndAt - now : a.presetFireAt - now;
        // After an inserted rest the step it interrupted comes back under pressure; after a
        // planned one, the next step - or the after-test's pull - does.
        n.pullNext = a.restingNow || (nxt != null ? !nxt.rest : assessAfter);
        n.stageKind = stageKind;
        n.dropPhase = dropPhase && !a.holding;
        dropNow = n.dropPhase && !resting;
        n.setK = set[0];
        n.setN = set[1];
        n.pressure = commandedKpa > 0 ? Model.Fmt.p(commandedKpa) : "";
        // A RAMP COUNTS ITS STEPS (0.10 final) - a warm-up that climbs is still the warm-up.
        if (!resting && !warmUpNow(cur) && rampNowAt(cur, dispIdx) && stepsNotSets(cur)) {
            int[] kn = RunEdit.stepOfSet(a.plan, dispIdx);
            n.ramp = true;
            n.stepK = kn[0];
            n.stepN = kn[1];
        }
        int kind = RunLook.liveKind(a.holding, resting, stageKind, n.dropPhase);
        int col = runColour(kind);

        n.narrow = a.holding && !fitsOneLine(a.runStatusL, RunLook.PAUSED);
        /* ---- the status line: words always, colour when the setting says so ---- */
        a.runStatusL.setText(RunLook.statusLeft(n));
        // The right half is paintRoutineStrip's own figure (invariant 170), read back.
        a.runStatusR.setText(a.ecgElapsed == null ? "" : a.ecgElapsed.getText());
        if (col != -1) {
            int ink = RunLook.inkOn(col);
            a.runStatus.setBackground(Ui.roundRect(a, col, Look.R_PILL));
            a.runStatusL.setTextColor(ink);
            a.runStatusR.setTextColor(ink);
        } else {
            a.runStatus.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_PILL));
            a.runStatusL.setTextColor(Ui.TEXT);
            a.runStatusR.setTextColor(a.ecgElapsed == null ? Ui.DIM : a.ecgElapsed.getCurrentTextColor());
        }
        a.runStatus.setContentDescription(a.runStatusL.getText() + ". "
            + a.runStatusR.getText() + " elapsed of planned.");

        /* ---- the last ten seconds of a rest: a gentle pulse, and the optional buzz ---- */
        boolean warn = RunLook.pullWarning(n);
        if (warn) {
            if (a.runPulse == null || !a.runPulse.isRunning()) {
                a.runPulse = android.animation.ObjectAnimator.ofFloat(a.runStatus, "alpha", 1f, 0.55f);
                a.runPulse.setDuration(650);
                a.runPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                a.runPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                a.runPulse.start();
            }
            long key = a.restingNow ? a.restNowEndAt : a.presetFireAt;
            if (a.model.pullBuzz && a.pullBuzzedFor != key) {
                a.pullBuzzedFor = key;
                a.vibrateNow(300);
            }
        } else {
            if (a.runPulse != null) { a.runPulse.cancel(); a.runPulse = null; }
            a.runStatus.setAlpha(1f);
        }

        /* ---- the tint over the top region, when the setting asks for one ---- */
        float tint = a.model.runColourOn ? RunLook.tintAlpha(a.model.runColourWhere) : 0f;
        if (tint > 0f && col != -1) {
            int top = (Math.round(255 * tint) << 24) | (col & 0x00FFFFFF);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { top, col & 0x00FFFFFF });
            g.setCornerRadius(Ui.dp(a, Look.R_CARD));
            a.runTop.setBackground(g);
        } else {
            a.runTop.setBackground(null);
        }

        /* ---- the NOW card: the time in the step's colour, and what comes next ----
         * Grey while paused (0.10 final): the clock is waiting, and must not read as one that
         * is running; the status line and the card's title say PAUSED in words. */
        if (a.runCountdown != null)
            a.runCountdown.setTextColor(a.holding ? Ui.DIM : col != -1 ? col : Ui.TEXT);
        if (a.runNext != null) {
            String next;
            if (a.restingNow) {
                // An inserted rest goes back to the step it interrupted.
                next = "back to " + RunLook.nextStep(cur, RunLook.setAt(cur, inPresetMs) > 0
                        ? setOf(cur, dispIdx, inPresetMs)[0] : 0,
                    Model.Fmt.p(Math.min(cur.up, a.model.ceilKpa)));
            } else if (a.awaitingAck) {
                next = ByHand.waitsAfterClock(cur) ? "the next step when you press Done"
                                                   : "the next step when you press “I’ve swapped”";
            } else if (nxt != null) {
                next = rampNowAt(nxt, dispIdx + 1) && stepsNotSets(nxt)
                    ? RunLook.nextRamp(Model.Fmt.p(nextUp))
                    : RunLook.nextStep(nxt, firstSetOf(dispIdx + 1),
                                       nxt.rest ? "" : Model.Fmt.p(nextUp));
            } else {
                next = assessAfter ? "the after-test pull" : null;
            }
            /* THE NOW CARD SAYS WHAT THE MOCK SAYS (0.10 final): a ramp in steps and the next
             * step's pressure; a set of a block and the next set of that block; a rest what
             * comes after it; the warm-up its pressure. An inserted rest, the changeover and a
             * step that fits none of these keep the plain line. */
            String line;
            boolean rampHere = !resting && !a.awaitingAck && !warmUpNow(cur)
                && rampNowAt(cur, dispIdx) && stepsNotSets(cur);
            if (ByHand.is(cur) && !a.restingNow) {
                // A STEP DONE BY HAND SAYS WHAT TO DO, not "Rest": "Pump vented · do it by hand
                // now", "Done when you are" once the release's time is up, and the changeover
                // its own instruction (ByHand).
                line = ByHand.nowLine(cur.awaitAck, a.awaitingAck);
            } else if (a.restingNow || a.awaitingAck) {
                line = RunLook.nowLine(RunLook.nowWord(kind == RunLook.DROP ? RunLook.WORK : kind),
                    set[0], set[1], next);
            } else if (resting) {
                line = RunLook.nowLineRest(next);
            } else if (rampHere) {
                int[] kn = RunEdit.stepOfSet(a.plan, dispIdx);
                String nextStepAt = kn[0] < kn[1] && dispIdx + 1 < a.plan.size()
                    ? Model.Fmt.p(nextUp) : null;
                // THIS STEP'S PULL AS IT IS NOW - a live change included, not the plan's figure;
                // the next step's is nextUp (the plan, or the ramp as "Rest of this ramp" moved it).
                int atUp = dispIdx == a.planIdx ? a.inForceTuple()[LiveEdit.UP]
                                                : RunEdit.clampUpper(cur.up, a.model.ceilKpa);
                line = RunLook.nowLineRamp(kn[0], kn[1], Model.Fmt.p(atUp), nextStepAt, next);
            } else if (warmUpNow(cur)) {
                line = RunLook.nowLineWarm(commandedKpa > 0 ? Model.Fmt.p(commandedKpa) : "", next);
            } else if (set[1] > 0) {
                int[] blk = clockSet(cur, now);
                int bk = blk != null ? blk[0] : RunLook.setAt(cur, inPresetMs);
                int bn = blk != null ? blk[1] : RunLook.setsIn(cur);
                line = RunLook.nowLineWork(dropPhase && !a.holding, set[0], set[1], bk >= bn, next);
            } else {
                line = RunLook.nowLine(RunLook.nowWord(kind == RunLook.DROP ? RunLook.WORK : kind),
                    set[0], set[1], next);
            }
            if (!line.contentEquals(a.runNext.getText())) a.runNext.setText(line);
        }

        /* ---- the stage bar and its words ---- */
        if (a.runStageBar != null && r != null) {
            int ns = r.stages.size();
            /* R11-2 - THE BAR IS THE RUN ACTUALLY PLAYING: one part per set, from the run's own
             * count of each step's sets (countSteps) and the set the clock is in - the same
             * count the status line's "set N of M" is made of, so set N is the Nth part. */
            countSteps();
            int setNow = 0;
            float setFrac = 0f;
            if (!resting && cur != null && !stepsNotSets(cur)) {
                int[] blk = clockSet(cur, now);
                if (blk != null) {
                    setNow = blk[0];
                    long cyc = a.setClock.cycleMs();
                    setFrac = cyc > 0 ? a.setClock.inCycleMs(now) / (float) cyc : 0f;
                    if (dispIdx >= 0 && dispIdx < stepSets.length && stepSets[dispIdx] > 0)
                        stepSets[dispIdx] = blk[1];
                } else {
                    setNow = RunLook.setAt(cur, inPresetMs);
                    long cyc = RunLook.cycleMs(cur);
                    setFrac = cyc > 0 && setNow > 0
                        ? (inPresetMs - (setNow - 1) * cyc) / (float) cyc : 0f;
                }
            }
            // FIX11 F4: and what Skip these sets / Skip warm-up cut out, hatched where it sat.
            java.util.List<StageBar.Segment> segs = StageBar.buildLive(r.stages, a.plan, dispIdx,
                inPresetMs, stepSets, setNow, setFrac, a.comingSkippedMs(ns),
                a.comingChangedFlags(ns), a.comingSkipped, a.skipCutMs());
            int[] cols = new int[segs.size()];
            for (int i = 0; i < cols.length; i++) {
                int c = runColour(segs.get(i).kind);
                cols[i] = c != -1 ? c : a.railColourOf(r.stages.get(segs.get(i).stage));
            }
            a.runStageBar.set(segs, cols, true);
            a.runBarLabels.setText(barWords(segs, cols));
        }
    }

    /**
     * THE WORDS UNDER THE BAR (0.10 final): only "now: <Current>" - bold, in the step's colour
     * (white while paused) - then "· next: <Next>", the next stage the run will actually play
     * (a skipped one is passed over, StageBar#nowAndNext), or "· then the end". The bar itself
     * already draws every stage; the words say only the two that matter now.
     */
    private CharSequence barWords(java.util.List<StageBar.Segment> segs, int[] cols) {
        /* polish RN-3 (the owner's option A): NOW AND NEXT WERE SAID THREE TIMES - this line,
         * the band and the NOW card. The band and the card say what plays; this line says
         * only what comes after it. */
        int[] nn = StageBar.nowAndNext(segs);
        if (nn[0] < 0) return "";
        return nn[1] >= 0 ? "Next: " + segName(segs.get(nn[1])) : "Then the end";
    }

    /** What the NOW card's routine line opens: the routine and the stage playing. */
    private String kickerWhere = "";

    /** The routine line's info mark: which routine is running, and where in it (polish RN-3). */
    private final class KickerTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.isFinishing()) return;
            Ui.dress(a, a.holdRunSheet(Ui.dialog(a)
                .setTitle("Running now")
                .setMessage(kickerWhere)
                .setPositiveButton("OK", null)
                .show()));
        }
    }

    /** "Skip block" said as what it does (polish RN-9); Undo follows it. */
    static final String SKIP_SETS = "Skip these sets";

    /**
     * ONE DISABLED LOOK FOR THE TIMER CONTROLS (polish SYS-10): the unavailable fill and ink
     * (Ui.stateFill), never an alpha that faded the label under AA. Called after a control's
     * own fill is painted, so a disabled one always ends unavailable; an enabled one keeps
     * the fill just painted and its semibold label.
     */
    private void timerAvailable(Button b, boolean on) {
        if (!on) { Ui.stateFill(a, b, false, false); return; }
        b.setEnabled(true);
        Ui.semibold(b);
    }

    /** An available timer control painted in its own fill and ink (timerControl's look). */
    private void timerFill(Button b, int fill, int ink) {
        b.setEnabled(true);
        b.setBackground(Ui.roundRect(a, fill, Look.R_CTRL));
        b.setTextColor(ink);
        Ui.semibold(b);
    }

    /** A stage's name on the bar, or its kind's when it has none. */
    private static String segName(StageBar.Segment g) {
        return g.name != null && g.name.trim().length() > 0 ? g.name.trim() : RunLook.nowWord(g.kind);
    }

    /**
     * Enables, disables and NAMES the three timer controls from the same planIdx the
     * sequencing is really on — never from "the run screen is showing", which is true for
     * the 600 ms while the batch is still being written and nothing has played yet.
     *
     * A disabled control is left visible and greyed rather than removed: a row of buttons
     * that appears and disappears under the user's thumb during a run is worse than one
     * that is plainly not available yet.
     */
    private void refreshTimerControls() {
        boolean can = RunEdit.canSkip(a.running, a.planIdx, a.plan.size());
        // THE BUTTONS FOLLOW THE STEP (0.10 final), Pause always first: in a set Pause · Skip
        // block · +30 s hold · Rest; in a rest - the plan's or an inserted one - Pause · End
        // rest · +30 s rest; on a ramp Pause · Skip step · +30 s step; in the warm-up Pause ·
        // Skip warm-up · +30 s.
        Model.Preset curP = (a.planIdx >= 0 && a.planIdx < a.plan.size())
            ? a.plan.get(a.planIdx) : null;
        boolean restNow = inRest(curP);
        StripNow sn = stripNow(System.currentTimeMillis());
        int mode = sn.mode;
        boolean ramp = mode == QuickAdjust.MODE_RAMP, warm = mode == QuickAdjust.MODE_WARM;
        // ON A RAMP'S STEP OF SEVERAL CYCLES THE BUTTON ADDS ONE WHOLE CYCLE, and says so:
        // "+0:42 step" (RunEdit#rampExtendLabel). A one-cycle step's is 30 s more hold - its
        // step stays one cycle - so it keeps "+30 s step".
        boolean cycleStep = ramp && !a.oneCycleNow() && sn.stepCycle > 0;
        // Skip's Undo ends with its window, or as soon as the step the skip started is over.
        long nowMs = System.currentTimeMillis();
        if (skipSaysUndo && (a.awaitingAck || !a.skipUndoable())) {
            skipSaysUndo = false;
            undoGoneAt = nowMs;
        }
        boolean lateGuard = !skipSaysUndo && RunEdit.skipLateTapGuarded(undoGoneAt, nowMs);
        // THE RELEASE ENDS ON DONE, before its time is up or after (ByHand): the same skip, named
        // and filled as the changeover's acknowledgement is.
        boolean done = !a.restingNow && ByHand.waitsAfterClock(curP);
        boolean ack = a.awaitingAck || done;
        if (a.skipBtn != null) {
            /* THE ACKNOWLEDGEMENT IS THIS CONTROL WEARING ITS OTHER NAME.
             *
             * It already performs exactly the right action - cancel the pending advance and
             * play the next preset - and re-labelling it adds no tap target beside STOP and
             * cannot move STOP under a thumb mid-run, which a new full-width button in the
             * footer would. Two states, named in words and in setSelected as well as in the
             * fill, because this app never lets a colour carry a state alone. */
            setText(a.skipBtn, done ? ByHand.DONE
                : a.awaitingAck ? "I\u2019ve swapped \u203a"
                : skipSaysUndo ? UNDO_SKIP
                : restNow ? "End rest" : ramp ? "Skip step" : warm ? "Skip warm-up"
                : SKIP_SETS);
            a.skipBtn.setSelected(ack);
            a.skipBtn.setBackground(Ui.roundRect(a,
                ack ? Ui.ACCENT : Ui.SURFHI, Look.R_CTRL));
            a.skipBtn.setTextColor(ack ? Ui.labelOn(Ui.ACCENT) : Ui.TEXT);
            timerAvailable(a.skipBtn, can && !lateGuard);
            a.skipBtn.setContentDescription(!can
                ? "Skip, not available until a preset is playing"
                : skipSaysUndo ? "Undo skip. Puts back what Skip just took out, for a few "
                                 + "seconds after the skip"
                : lateGuard ? "Skip, available again in a moment"
                : done
                ? "Done. The pump is vented and nothing is commanded while you do this by hand; "
                  + "the next step starts when you press this."
                : (a.awaitingAck
                   ? "Continue. The cuff is vented and the run is waiting for you to swap "
                     + "cylinders; nothing advances until you press this."
                   : restNow ? "End rest. Ends the rest now and goes on to the next step"
                   : ramp ? "Skip step. Ends this step of the ramp now and goes on to the next"
                   : warm ? "Skip warm-up. Ends the warm-up now and goes on to the next step"
                   : "Skip these sets. Ends this block of sets now, the sets still to come in it "
                     + "included, and goes on to the next step. Undo puts them back."));
        }
        if (a.extendBtn != null) {
            if (!can) Ui.stateFill(a, a.extendBtn, false, false);
            else timerFill(a.extendBtn, Ui.SURFHI, Ui.TEXT);
            // polish RN-5: the warm-up's says what it adds to, as the other three do.
            setText(a.extendBtn, restNow ? "+30 s rest"
                : cycleStep ? RunEdit.rampExtendLabel(sn.stepCycle) : ramp ? "+30 s step"
                : warm ? "+30 s warm-up" : "+30 s hold");
            a.extendBtn.setContentDescription(!can
                ? "Add thirty seconds, not available until a preset is playing"
                : restNow ? "Thirty seconds more rest"
                : cycleStep ? RunEdit.rampExtendSaid(sn.stepCycle)
                : ramp ? "Thirty seconds more on this step of the ramp"
                : warm ? "Thirty seconds more warm-up"
                : "Thirty seconds more hold on the set playing, four minutes fifteen at most");
        }
        /* PAUSE / RESUME - the old Hold, first in every step. It names its state rather than
         * only showing a colour: a two-state control, with setSelected putting that state where
         * a screen reader can reach it, so the amber is never the only thing carrying it. Idle:
         * the amber TINT (a pause is pressure - see timerControl). Paused: SOLID amber, because
         * the pump is keeping pressure right now. In a rest: amber at 5 % behind 35 % ink -
         * nothing to pause, the cuff is vented - and a tap says so (HoldTap). */
        if (a.holdBtn != null) {
            boolean nothing = restNow && !a.holding;
            setText(a.holdBtn, a.holding ? "Resume" : "Pause");
            a.holdBtn.setBackground(Ui.roundRect(a, a.holding ? Ui.CMD
                : nothing ? Look.CMD_OFF_FILL : Look.CMD_DIM, Look.R_CTRL));
            a.holdBtn.setTextColor(a.holding ? Ui.labelOn(Ui.CMD)
                : nothing ? Look.CMD_OFF_INK : Ui.CMD);
            a.holdBtn.setSelected(a.holding);
            timerAvailable(a.holdBtn, can);
            a.holdBtn.setContentDescription(!can
                ? "Pause, not available until a preset is playing"
                : a.holding
                  ? "Resume. The run is paused: the pump is keeping the current pressure and the "
                    + "clock is waiting; the pause " + a.runHoldVentsIn() + ". Selected."
                  : nothing
                    ? "Pause, not available in a rest: the cuff is vented."
                    : "Pause. Keeps the pump at the pressure it is reading now and stops the "
                      + "clock. The pump keeps the pressure; STOP vents.");
        }
        // REST — available whenever something is playing that is not ALREADY a rest.
        // Resting on a rest is not a second rest, it is the same nothing, so the control
        // says so rather than pretending to do it again. Offered in a set only (0.10 final).
        if (a.restBtn != null) {
            setVisible(a.restBtn, !restNow && !ramp && !warm);
            boolean canRest = can && !a.resting && !a.restingNow;
            if (!canRest) Ui.stateFill(a, a.restBtn, false, false);
            else timerFill(a.restBtn, Ui.SURFHI, Ui.TEXT);
            a.restBtn.setSelected(a.restingNow);
            a.restBtn.setContentDescription(!can
                ? "Rest, not available until a preset is playing"
                : a.resting ? "Rest, not available — the routine is already resting here"
                : "Rest. Vents the cuff and pauses the run for a length you choose, then "
                  + "carries on where it left off.");
        }
        // polish RN-14: Coming steps is unavailable until a step plays, and looks it - it was
        // a live-looking button whose tap only said so in a toast.
        if (a.comingBtn != null) {
            boolean open = a.runRoutine != null
                && RunEdit.canOverride(a.running, a.planIdx, a.plan.size());
            if (open != a.comingBtn.isEnabled()) {
                Ui.setEnabled(a, a.comingBtn, open, Ui.SURFHI);
                a.comingBtn.setContentDescription(open
                    ? "Coming steps. Skip a step still to come, or change one"
                    : "Coming steps, not available until the run is under way");
            }
        }
        // +30 s is the last button when Rest is not offered: no gap after it then.
        if (a.extendBtn != null && a.restBtn != null
                && a.extendBtn.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) a.extendBtn.getLayoutParams();
            int want = a.restBtn.getVisibility() == View.VISIBLE ? Ui.dp(a, 8) : 0;
            if (lp.rightMargin != want) { lp.rightMargin = want; a.extendBtn.setLayoutParams(lp); }
        }
    }
}
