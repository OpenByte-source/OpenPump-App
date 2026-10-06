package org.openpump;

import android.graphics.DashPathEffect;
import android.graphics.Path;
import java.util.Calendar;
import android.widget.ImageView;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** THE PROGRESS TAB - the trends, photos and sessions views behind its tab strip.
 *  Lifted out of SessionActivity verbatim - see
 *  docs/superpowers/plans/2026-09-07-sessionactivity-split.md. A screen class
 *  renders into the Activity's own body column and owns no state. */
final class ProgressScreen {
    private final SessionActivity a;

    ProgressScreen(SessionActivity a) { this.a = a; }

    /**
     * EXPANSION PER SESSION — after minus before, one bar per session that HAS an after
     * reading, oldest at the left, the best in lime.
     *
     * A session with no after-reading is DROPPED, not drawn as zero. Zero is a measured
     * result — "this session changed nothing" — and a missing measurement is not that; a
     * chart that drew them the same would report unmeasured sessions as failures. That
     * decision is Insight#expansionCm's and is asserted there.
     */
    private void expansionCard(List<Model.Sess> newestFirst) {
        final double[] series = Insight.expansionSeries(newestFirst);
        if (series.length == 0) return;

        Ui.sectionHead(a, a.body, "Expansion per session", Ui.BODY);
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 11), Ui.dp(a, 10), Ui.dp(a, 11), Ui.dp(a, 10));

        SessionActivity.ExpansionBars bars = a.new ExpansionBars(a, series, false);
        card.addView(bars, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 46)));

        int best = Insight.bestIndex(series);
        String caption = "after − before  ·  " + series.length + " measured session"
            + (series.length == 1 ? "" : "s")
            + (best >= 0 ? "  ·  best " + Model.Fmt.lenDelta(series[best]) : "");
        TextView cap = new TextView(a);
        cap.setText(caption);
        // DIM, not FAINT (Task 13): "after − before · N measured sessions · best +X" is a
        // sentence, not a label/unit, and FAINT only clears AA as large text.
        cap.setTextColor(Ui.DIM);
        cap.setTextSize(Look.SP_CAPTION * 0.92f);
        cap.setPadding(0, Ui.dp(a, 4), 0, 0);
        card.addView(cap);

        Ui.group(card, "Expansion per session, oldest first. " + caption
            + ". Sessions with no after-reading are not shown, because a missing "
            + "measurement is not a measured zero.");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 10);
        a.body.addView(card, lp);
    }

    /**
     * M4 - THE TISSUE RESPONSE TEST OVER TIME (the owner's item 12). The test runs twice a
     * session, and its result could only be read one session at a time, as a line in History.
     * One bar per session that produced a change, oldest at the left, normal variation shaded
     * and the settings changes marked - TauSay#trend decides which sessions and where the
     * breaks are, TauTrendChart draws them.
     *
     * Draws nothing at all when no session has a change to show (a new routine starts with
     * the test off, so for some people this card is simply absent) - never an empty
     * card, and never a session with no result drawn as "no change".
     */
    private void tauTrendCard(List<Model.Sess> newestFirst) {
        List<TauSay.Point> pts = TauSay.trend(newestFirst);
        if (pts.isEmpty()) return;
        // G-Progress (owner: explanations behind ⓘ): the method note is the title's ⓘ.
        LinearLayout card = Ui.cardGroup(a, a.body, TauSay.NAME,
            "Change in fill time, session by session", TauSay.NAME,
            "Only sessions run with the same test settings are compared.");
        TauTrendChart chart = new TauTrendChart(a, pts);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 150));
        clp.topMargin = Ui.dp(a, Look.S2);
        card.addView(chart, clp);

        LinearLayout key = new LinearLayout(a);
        key.setOrientation(LinearLayout.HORIZONTAL);
        key.setGravity(Gravity.CENTER_VERTICAL);
        key.setPadding(0, Ui.dp(a, Look.S3), 0, 0);
        trendKey(key, Look.PLAN_BLUE, Tau.NOISE_PCT + " % or more");
        trendKey(key, Look.CTRL_EDGE, "normal variation (shaded)");
        // The chart's own description says the same; the key is for the eye.
        key.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        card.addView(key);
    }

    private void trendKey(LinearLayout row, int colour, String word) {
        View sw = new View(a);
        sw.setBackground(Ui.roundRect(a, colour, 2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(a, 10), Ui.dp(a, 10));
        slp.rightMargin = Ui.dp(a, Look.S2);
        row.addView(sw, slp);
        TextView t = new TextView(a);
        t.setText(word);
        t.setTextColor(Ui.DIM);
        t.setTextSize(Look.SP_CAPTION);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = Ui.dp(a, Look.S5);
        row.addView(t, tlp);
    }

    /** The row's door into the save card (§5): "save as you ran it ›" / "save a copy as a
     *  set ›" / "saved ›" / nothing — AsRun.historyLabel decides; this only draws. It reads
     *  the Sess ALONE (Sess.differs was computed once, at filing) — History re-renders on
     *  every tap and must never call Store.loadAsRun per row; only OpenSaveCardTap loads,
     *  on the tap that actually opens the card. */
    private void historyDoorSaveCard(LinearLayout parent, Model.Sess e) {
        int kind = AsRun.historyLabel(e);
        if (kind == AsRun.HIST_NONE) return;
        // POLISH #5: drawn INTO the session's own card (see the caller) — the row and
        // its action are one object now.
        Button b = Ui.flat(a, parent, AsRun.historyDoorText(kind));
        // pass F - "already saved" is a state of a RECORD, not a verdict about the pump.
        b.setTextColor(kind == AsRun.HIST_SAVED ? Ui.DIM : Ui.ACCENT);
        // EVERY door opens the card — a saved run's included. The card shows what was
        // already saved (with a tap through to the Library) and leaves the rest offerable;
        // routing a saved row straight to the Library is what made one partial save final.
        b.setOnClickListener(a.new OpenSaveCardTap(e));
    }

    /**
     * "THEN VS NOW" (§8 #6) — the FIRST and the LATEST photo of the same view, side by
     * side on Progress. WHICH pair is Compare.thenVsNow's decision alone (pure,
     * asserted): the view with the most photos, first and latest of it. Nothing here
     * re-pairs photos — the card resolves the two ids against the same
     * Compare.withPhotos list the Compare screen itself reads, so the pair the card
     * shows IS the pair a tap opens.
     *
     * Photos decode through Photos.decodeOriented — downsampled and EXIF-upright, the
     * same loader Compare and the camera ghost use — NEVER through LogProvider, which
     * serves the debug log and nothing else. A file that fails to decode leaves an
     * honest empty slot; the card never substitutes another view's photo.
     *
     * Fewer than two photos of ANY view: the card asks for a second photo and opens the
     * Log-a-reading flow (the camera's front door from Progress) instead of a pair.
     */
    private void thenVsNowCard() {
        // WHICH PAIR is Compare.resolvePick's decision: thenVsNow's default view and pair,
        // with the user's persisted choice applied on top. An untouched choice resolves to
        // exactly what thenVsNow returns, so a phone that has never opened the chooser sees
        // the card it always saw.
        long tnNow = System.currentTimeMillis();
        Compare.ThenNow tn = Compare.resolvePick(a.model.measLog, a.model.thenNow, tnNow);
        // What the card ended up showing, in words — computed once inside the pair branch
        // below and reused by the chooser door's spoken name, so the card and the door can
        // never describe the same choice differently.
        String tnCaption = "";

        a.sectionLabel("THEN VS NOW");
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 11), Ui.dp(a, 10), Ui.dp(a, 11), Ui.dp(a, 10));

        if (tn == null) {
            // G-Progress: the how-to goes behind the ⓘ, and the card's next step is a real
            // button (PR-14's "Log a reading with a photo") rather than a tappable card.
            Ui.noteInfo(a, card, "Take a second photo to see then vs now", "Then vs now",
                "Photos are taken from the baseline or Log a reading screen — "
                + "two of the same view, taken the same way on separate days, make a "
                + "comparison.");
            Ui.secondary(a, card, LOG_WITH_PHOTO)
                .setOnClickListener(a.new OpenLogReadingTap(Nav.PROGRESS));
        } else {
            // Point 19: the pair is within ONE method, and the list it resolves against is
            // that method's, the same one Compare and the chooser read.
            List<Model.Reading> ps = Compare.withPhotos(a.model.measLog, tn.view, tn.method);
            int fi = Compare.indexOf(ps, tn.firstId);
            int li = Compare.indexOf(ps, tn.latestId);
            Model.Reading first = fi >= 0 ? ps.get(fi) : null;
            Model.Reading latest = li >= 0 ? ps.get(li) : null;

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(thenNowCell("THEN", first, tn.view),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            View gap = new View(a);
            row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(a, 6), 1));
            row.addView(thenNowCell("NOW", latest, tn.view),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(row);

            // WHAT IS BEING SHOWN, said outright. First-versus-latest needed no caption —
            // it was the only thing the card could be. Now that it might be three months
            // ago, or a photo picked by hand, or the default, a card that does not name its
            // own terms is a comparison the reader has to guess at.
            TextView cap = new TextView(a);
            long days = (first != null && latest != null)
                ? Compare.daysBetween(first, latest) : 0;
            String shown = (first != null && latest != null)
                ? Compare.pickCaption(first.ts, latest.ts, tnNow) : "";
            tnCaption = shown;
            cap.setText(shown + "  ·  " + Shot.label(tn.view) + " view  ·  "
                + Compare.methodPhrase(tn.method) + "  ·  " + days
                + " day" + (days == 1 ? "" : "s") + " apart  ·  tap to compare");
            // DIM, not FAINT (Task 13): this sentence is what the card is showing, not a
            // label/unit, and must clear AA at its normal (non-large) size.
            cap.setTextColor(Ui.DIM);
            cap.setTextSize(Look.SP_CAPTION * 0.92f);
            cap.setPadding(0, Ui.dp(a, 4), 0, 0);
            card.addView(cap);

            card.setOnClickListener(new OpenThenNowTap(tn));
            card.setOnLongClickListener(new ThenNowLongPress());
            Ui.group(card, "Then versus now, " + Shot.label(tn.view) + " view, "
                + Compare.methodPhrase(tn.method) + ". Showing " + shown
                + ". Then photo "
                + (first == null ? "unavailable" : a.dayLabel(first.ts)) + ", now photo "
                + (latest == null ? "unavailable" : a.dayLabel(latest.ts))
                + ". Tap to open the comparison, long press to choose which photos.");
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 10);
        a.body.addView(card, lp);

        // THE CHOOSER'S DOOR, deliberately OUTSIDE the card rather than a gear inside it.
        // Ui.group() makes the card one accessibility stop by hiding its children from the
        // service — which is right for a card, and would have made a button nested inside
        // it unreachable to exactly the users who cannot use the long press either. A
        // sibling door is reachable by every route: tap, keyboard, screen reader.
        // The long press on the card above opens the same dialog for anyone who reaches
        // for the gesture the ask named.
        if (tn != null) {
            Button pickDoor = Ui.flat(a, a.body, "⚙ Choose which photos ›");
            pickDoor.setContentDescription("Choose which two photos then versus now "
                + "compares. Currently " + tnCaption + ".");
            pickDoor.setOnClickListener(new ThenNowPickTap());
        }
    }

    /** One half of the pair: the label + date over the photo. The bitmap comes from
     *  Photos.decodeOriented (downsampled, upright) and is simply left to the view — the
     *  whole card is rebuilt on every render, so no field pins a bitmap past its
     *  ImageView the way CompareScreen's stage needed managing. */
    private LinearLayout thenNowCell(String label, Model.Reading r, String view) {
        LinearLayout cell = Ui.col(a);
        // The label line carries the 👁, so each half of the pair reveals on its own.
        LinearLayout lRow = new LinearLayout(a);
        lRow.setOrientation(LinearLayout.HORIZONTAL);
        lRow.setGravity(Gravity.CENTER_VERTICAL);
        // PR-7: the app's tracked micro label, not a hand-built 9.5 sp line.
        TextView l = Ui.microLabel(a, null, label + (r == null ? "" : " · " + a.dayLabel(r.ts)),
                                   Ui.DIM);
        lRow.addView(l, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String key = r == null ? null : a.photoKey(r.id, view);
        cell.addView(lRow);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL));
        android.graphics.Bitmap b = null;
        if (r != null) {
            Model.Reading.Photo p = Compare.photoOf(r, view);
            if (p != null) b = Photos.decodeOriented(p.path);
        }
        if (b != null) a.showPhotoInto(img, b, key);
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 110));
        imgLp.topMargin = Ui.dp(a, 3);
        cell.addView(img, imgLp);
        return cell;
    }

    private final class OpenThenNowTap implements View.OnClickListener {
        private final Compare.ThenNow tn;
        OpenThenNowTap(Compare.ThenNow t) { tn = t; }
        @Override public void onClick(View v) {
            a.enterDest(Nav.SCR_COMPARE);
            // TASK 6 — "Photos & compare" gate, the SAME scope openCompare() asks below:
            // this is the OTHER door into Compare (a specific then/now pair rather than the
            // picker), and a scope must gate every door to a screen or it gates none of
            // them. body.removeAllViews() first: unlike progressPhotosContent()'s own gate,
            // nothing has cleared `body` yet at this point on the locked branch —
            // compare.openPair() does its own removeAllViews() as part of every screen's
            // "clear then rebuild" idiom (see CompareScreen's own class doc), but that call
            // never happens at all when this returns true, so this stands in for it rather
            // than leaving the lock card appended under whatever was on screen a moment ago.
            a.body.removeAllViews();
            if (a.appLockGate(AppLock.PHOTOS, new Runnable() {
                    @Override public void run() {
                        a.compare.openPair(tn.view, tn.method, tn.firstId, tn.latestId);
                    }
                })) return;
            a.compare.openPair(tn.view, tn.method, tn.firstId, tn.latestId);
        }
    }

    /**
     * THE CHOOSER. One dialog, both sides, each option marked with whether it is the one in
     * force — rather than two sequential dialogs (a two-step wizard to change one setting)
     * or a nested submenu (a second tap before the options are even visible).
     *
     * Each row is a complete instruction ("Then: 6 months ago"), so a screen reader gets the
     * side and the choice in one utterance, and the ●/○ marker carries the current state to
     * a user who cannot see which row is highlighted — the same marker discipline
     * Ui.markSelection applies everywhere else in the app.
     *
     * The MODES are what is stored, not the photos they currently resolve to: "6 months ago"
     * keeps meaning six months ago as time passes and new photos arrive, rather than
     * freezing onto whichever photo happened to be nearest six months ago on the day it was
     * chosen. Only "pick a photo" stores an id, because there the id IS the meaning.
     */
    private void openThenNowChooser() {
        if (a.isFinishing()) return;
        Model.ThenNowPick pick = a.model.thenNow;
        final String[] leftModes = {
            Model.ThenNowPick.LEFT_FIRST, Model.ThenNowPick.LEFT_1Y,
            Model.ThenNowPick.LEFT_6M, Model.ThenNowPick.LEFT_3M,
            Model.ThenNowPick.LEFT_PICK };
        final String[] rightModes = {
            Model.ThenNowPick.RIGHT_LATEST, Model.ThenNowPick.RIGHT_PICK };
        String[] items = new String[leftModes.length + rightModes.length];
        for (int i = 0; i < leftModes.length; i++) {
            boolean on = leftModes[i].equals(pick.left);
            items[i] = (on ? "● " : "○ ") + "Then: "
                     + Model.ThenNowPick.leftLabel(leftModes[i]);
        }
        for (int i = 0; i < rightModes.length; i++) {
            boolean on = rightModes[i].equals(pick.right);
            items[leftModes.length + i] = (on ? "● " : "○ ") + "Now: "
                     + Model.ThenNowPick.rightLabel(rightModes[i]);
        }
        Ui.dress(a, Ui.dialog(a)
            .setTitle("Then vs now")
            .setItems(items, new ThenNowChoice(leftModes, rightModes))
            .setNegativeButton("Cancel", null)
            .show());
    }

    /** Applies one row of the chooser. A "pick a photo" row opens the date list rather than
     *  committing a mode with no id behind it — a LEFT_PICK stored without an id resolves,
     *  correctly but confusingly, straight back to the default. */
    private final class ThenNowChoice implements DialogInterface.OnClickListener {
        private final String[] left, right;
        ThenNowChoice(String[] l, String[] r) { left = l; right = r; }
        @Override public void onClick(DialogInterface d, int which) {
            boolean isLeft = which < left.length;
            String mode = isLeft ? left[which] : right[which - left.length];
            if (Model.ThenNowPick.LEFT_PICK.equals(mode)) {
                openThenNowPhotoPicker(isLeft);
                return;
            }
            if (isLeft) a.model.thenNow.left = mode; else a.model.thenNow.right = mode;
            Store.save(a, a.model);
            showSessionHistory();
        }
    }

    /**
     * "Pick a photo": the dates of every photo of the view the card is pairing, newest
     * first, as a plain list.
     *
     * The LIST IS OF THE SAME VIEW the card already chose, because the chooser changes which
     * two PHOTOS are paired and never which view — a view toggle here would let the user
     * assemble a pair the card then refuses to compare (Compare pairs one view only), which
     * is a control that can produce an invalid answer.
     *
     * A library with no photos cannot reach this: the door is only drawn when the card has a
     * pair, and a pair needs two.
     */
    private void openThenNowPhotoPicker(final boolean isLeft) {
        if (a.isFinishing()) return;
        Compare.ThenNow tn = Compare.resolvePick(a.model.measLog, a.model.thenNow,
                                                 System.currentTimeMillis());
        if (tn == null) { a.toast("No photos to choose from yet"); return; }
        final List<Model.Reading> ps = Compare.withPhotos(a.model.measLog, tn.view, tn.method);
        if (ps.isEmpty()) { a.toast("No photos to choose from yet"); return; }
        String[] items = new String[ps.size()];
        for (int i = 0; i < ps.size(); i++) {
            Model.Reading r = ps.get(i);
            // Photos exist on readings of ANY method — a girth-only (MSEG/MSSG) reading
            // can carry a photo just as a length one can, and its `len` was never
            // measured; "—" here, not that field's 0.0 default worn as a length.
            items[i] = a.dayLabel(r.ts) + "  ·  " + Compare.monthYear(r.ts) + "  ·  "
                     + (r.measuredLength() ? Gallery.cm(r.len) : "—") + " long";
        }
        Ui.dress(a, Ui.dialog(a)
            .setTitle(isLeft ? "Then — which photo" : "Now — which photo")
            .setItems(items, new ThenNowPhotoChoice(isLeft, ps))
            .setNegativeButton("Cancel", null)
            .show());
    }

    private final class ThenNowPhotoChoice implements DialogInterface.OnClickListener {
        private final boolean isLeft;
        private final List<Model.Reading> ps;
        ThenNowPhotoChoice(boolean l, List<Model.Reading> p) { isLeft = l; ps = p; }
        @Override public void onClick(DialogInterface d, int which) {
            if (which < 0 || which >= ps.size()) return;
            String id = ps.get(which).id;
            if (isLeft) {
                a.model.thenNow.left = Model.ThenNowPick.LEFT_PICK;
                a.model.thenNow.leftId = id;
            } else {
                a.model.thenNow.right = Model.ThenNowPick.RIGHT_PICK;
                a.model.thenNow.rightId = id;
            }
            Store.save(a, a.model);
            showSessionHistory();
        }
    }

    private final class ThenNowPickTap implements View.OnClickListener {
        @Override public void onClick(View v) { openThenNowChooser(); }
    }

    /** The long press the ask named. Returns true so the tap-to-compare listener on the same
     *  card does not also fire when the finger comes up. */
    private final class ThenNowLongPress implements View.OnLongClickListener {
        @Override public boolean onLongClick(View v) { openThenNowChooser(); return true; }
    }

    /**
     * EVERY PHOTO THIS PHONE HOLDS, grouped by the day it was taken: a date header carrying
     * that day's measurements, then a three-column grid of that day's thumbnails. The
     * PHOTOS tab's content (Stage B task 9) — folded in from what used to be its own pushed
     * screen, Nav.SCR_GALLERY, whose door was the "Photos ›" button below Compare's on the
     * TRENDS tab (now progressTrendsContent()) via OpenGalleryTap. That was the ONLY door to
     * it anywhere in this file — nothing else ever called enterDest(Nav.SCR_GALLERY) or
     * otherwise navigated to it by its own Nav constant — so folding its content into a tab
     * strands no other entry point. Nav.SCR_GALLERY itself is left declared in Nav.java
     * (destinationOf/parentOf still answer for it) but nothing here enters it any more;
     * showSessionHistory() draws this content under its own enterDest(Nav.SCR_SESSION_HIST)
     * instead. Nav.SCR_PHOTO — one photo, full size, with its Delete button — is UNCHANGED
     * and still a real pushed screen (see showPhotoDetail()): a single photo is a genuine
     * drill-down with a destructive action of its own, not a page section, so it keeps its
     * own chevron; that chevron (PhotoBackTap) now returns to showSessionHistory() instead
     * of to this method directly, landing back on this same tab (see progressTab's doc).
     *
     * WHY IT EXISTS. Until now a photo could only ever be seen through a comparison — the
     * Compare stage, the then-vs-now card, the align ghost — each of which shows exactly
     * two, chosen by a rule. There was no way to see what the library actually contains, no
     * way to find one particular photo, and, most consequentially, NO WAY TO DELETE ONE. A
     * body-photo app that can only ever accumulate is one people stop putting photos into.
     *
     * THE GROUPING is {@link Gallery#byDay} — pure and asserted — so the day boundaries here
     * and the day boundaries Compare's calendar draws come from one rule
     * ({@link PhotoCalendar#dayKey}, local time) and cannot disagree.
     *
     * MEMORY. Every tile decodes through {@link Photos#decodeThumb}: the same upright decode
     * the full-size paths use, capped at a grid-sized long edge, so a scroll holding thirty
     * tiles costs roughly what two full-size decodes cost. Nothing here holds a bitmap in a
     * field — the screen is rebuilt from scratch on every render, exactly as thenNowCell
     * does, so there is no bitmap lifecycle to get wrong.
     *
     * PRIVACY is unchanged: the files are read straight off app-private external storage by
     * path. Nothing here goes near LogProvider, which serves the debug log and nothing else.
     * No page-eye of its own any more, either — showSessionHistory()'s own titleRow already
     * carries one whose `where` text ("your measurements and photos") covers this tab, so a
     * second control right below it would only have been a second way to do the same thing.
     */
    private void progressPhotosContent() {
        // TASK 6 — "Photos & compare". `body` already carries the Progress title row and
        // the TRENDS/PHOTOS/SESSIONS tab strip by the time showSessionHistory() reaches
        // this branch (see its own dispatch a few lines above), so this does NOT clear it
        // — the lock card appends below the strip, same as this tab's real content would.
        // The re-entry callback goes through showSessionHistory(), not this method
        // directly: this method never rebuilds the title/strip itself, so calling it alone
        // a second time would append a second copy of the tab content under whatever is
        // already there instead of replacing it.
        if (a.appLockGate(AppLock.PHOTOS, new Runnable() {
                @Override public void run() { showSessionHistory(); }
            })) return;
        List<Gallery.Day> days = Gallery.byDay(a.model.measLog);
        if (days.isEmpty()) {
            // PR-14 + G-Progress: the one short line, the how-to behind its ⓘ, and the
            // next step as a button.
            Ui.noteInfo(a, a.body, Gallery.emptyShort(), "Photos", Gallery.emptyLine());
            Ui.big(a, a.body, LOG_WITH_PHOTO, Ui.BODY)
                .setOnClickListener(a.new OpenLogReadingTap(Nav.PROGRESS));
            return;
        }

        int total = Gallery.photoCount(a.model.measLog);
        Ui.noteInfo(a, a.body, total + (total == 1 ? " photo" : " photos") + " on " + days.size()
            + (days.size() == 1 ? " day" : " days") + " — all on this phone.",
            "Photos", total + (total == 1 ? " photo" : " photos") + " on "
            + days.size() + (days.size() == 1 ? " day" : " days")
            + ". They stay on this phone — the debug log never contains them, and they only "
            + "leave through an export you explicitly ask to include them in.");

        for (int i = 0; i < days.size(); i++) buildGalleryDay(days.get(i));
    }

    /** One day: the header (date + that day's measurements) and the grid under it. */
    private void buildGalleryDay(Gallery.Day day) {
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 11), Ui.dp(a, 10), Ui.dp(a, 11), Ui.dp(a, 10));

        LinearLayout head = Ui.col(a);
        TextView date = new TextView(a);
        date.setText(Gallery.dayTitle(day.ts));
        date.setTextColor(Ui.TEXT);
        date.setTextSize(Look.SP_LABEL);
        head.addView(date);
        // The day's own numbers, through Gallery.daySummary — pressures via Model.Fmt.p, so
        // this line follows the display unit exactly like every other pressure in the app.
        // It is a body measurement, so the privacy blur masks it like every other one; the
        // key is the DAY, because that is the granularity this one line summarises.
        String dayKey = a.measKey("day:" + day.dayKey);
        LinearLayout measRow = new LinearLayout(a);
        measRow.setOrientation(LinearLayout.HORIZONTAL);
        measRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView meas = new TextView(a);
        meas.setText(a.shown(Gallery.daySummary(day.readings), dayKey));
        meas.setTextColor(Ui.DIM);
        meas.setTextSize(Look.SP_CAPTION * 0.92f);
        measRow.addView(meas, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(measRow);
        // One stop for the header: the date and the measurements are one fact about the day,
        // not two unrelated ones.
        Ui.group(head, Gallery.dayTitle(day.ts) + ". " + Gallery.daySummary(day.readings)
            + ". " + day.photoCount() + (day.photoCount() == 1 ? " photo" : " photos") + ".");
        card.addView(head, a.galleryWrapLp());

        LinearLayout row = null;
        for (int i = 0; i < day.items.size(); i++) {
            if (i % Gallery.COLS == 0) {
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = a.galleryWrapLp();
                rowLp.topMargin = Ui.dp(a, 5);
                card.addView(row, rowLp);
            }
            row.addView(galleryTile(day, day.items.get(i)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        // Pad the last row so a day with one photo draws a tile the same size as a day with
        // three, rather than one stretched across the whole card.
        if (row != null) {
            int rem = day.items.size() % Gallery.COLS;
            if (rem != 0) for (int i = rem; i < Gallery.COLS; i++)
                row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        }

        LinearLayout.LayoutParams lp = a.galleryWrapLp();
        lp.bottomMargin = Ui.dp(a, 8);
        a.body.addView(card, lp);
    }

    /** One thumbnail. A tile is a picture and nothing else, so its whole meaning is in the
     *  name Gallery.tileName gives it. `day` is what the caption needs to know whether the
     *  day holds photos taken more than one way (E3: then each tile names its method). */
    private LinearLayout galleryTile(Gallery.Day day, Gallery.Item item) {
        LinearLayout cell = Ui.col(a);
        cell.setPadding(0, 0, Ui.dp(a, 5), 0);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL));
        String key = a.photoKey(item.readingId, item.view);
        android.graphics.Bitmap b = Photos.decodeThumb(item.path);
        a.showPhotoInto(img, b, key);
        cell.addView(img, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 96)));

        // The caption row carries the 👁 when this tile is obscured — beside the label
        // rather than over the picture, so revealing is a deliberate tap on a control and
        // never a mis-tap on the tile itself (which opens the photo full-size).
        LinearLayout capRow = new LinearLayout(a);
        capRow.setOrientation(LinearLayout.HORIZONTAL);
        capRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView cap = Ui.microLabel(a, null, Gallery.tileLabel(day, item), Ui.DIM);
        capRow.addView(cap, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // THE IMPORTED MARK, in DIM beside the view label. Only imported tiles carry one,
        // so the mark means something wherever the eye lands on it — a captured photo is
        // the ordinary case and is left unlabelled (Gallery#sourceBadge).
        if (item.fromGallery) {
            Ui.microLabel(a, capRow, Gallery.sourceBadge(true), Ui.DIM);
        }
        cell.addView(capRow, a.galleryWrapLp());

        cell.setMinimumHeight(Ui.dp(a, 48));
        cell.setOnClickListener(new GalleryTileTap(item.readingId, item.view));
        Ui.group(cell, Gallery.tileName(day, item));
        return cell;
    }

    private final class GalleryTileTap implements View.OnClickListener {
        private final String readingId, view;
        GalleryTileTap(String id, String v) { readingId = id; view = v; }
        @Override public void onClick(View v) { a.showPhotoDetail(readingId, view); }
    }

    private final class SearchNotesTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            final EditText input = new EditText(a);
            input.setHint("a word in a note, or easy / fine / tough");
            input.setText(a.noteFilter);
            input.setSelection(input.getText().length());
            input.setTextColor(Ui.TEXT);
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Search notes")
                .setMessage("Shows only the sessions whose note — or the word you chose "
                    + "for how it felt — contains what you type. Leave it empty to show "
                    + "them all.")
                .setView(input)
                .setPositiveButton("Search", new SearchNotesConfirm(input))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class SearchNotesConfirm implements DialogInterface.OnClickListener {
        private final EditText input;
        SearchNotesConfirm(EditText e) { input = e; }
        @Override public void onClick(DialogInterface d, int w) {
            a.noteFilter = input.getText().toString().trim();
            showSessionHistory();
        }
    }

    private final class ClearNoteFilterTap implements View.OnClickListener {
        @Override public void onClick(View v) { a.noteFilter = ""; showSessionHistory(); }
    }

    /** A tap on one of the three tab-strip cells: pick it and redraw the whole screen —
     *  the strip itself included, so its own selected cell repaints too. */
    private final class ProgressTabTap implements View.OnClickListener {
        private final int tab;
        ProgressTabTap(int t) { tab = t; }
        @Override public void onClick(View v) { a.progressTab = tab; showSessionHistory(); }
    }

    void showSessionHistory() {
        // A FRESH ARRIVAL, not a same-screen redraw or a return from one of Progress's own
        // pushed screens: the same test enterDest()'s own `arrived` runs, generalised from
        // "the exact same screen" to "the same DESTINATION" — Nav.destinationOf tags
        // Readings, Compare, a save card, Export and a full photo all PROGRESS, so returning
        // from any of them lands back on whichever tab it left rather than snapping to
        // TRENDS. Read BEFORE enterDest() below overwrites currentScreen.
        boolean freshEntry = Nav.destinationOf(a.currentScreen) != Nav.PROGRESS;
        a.body.removeAllViews();
        a.enterDest(Nav.SCR_SESSION_HIST);
        if (freshEntry) { a.progressTab = a.PROGRESS_TRENDS; a.mhMethodFilter = -1; }
        // A ONE-SHOT TAB REQUEST survives the freshEntry reset above (audit A39). The
        // Photos shortcut used to land on Progress and then call this method a SECOND
        // time to select the tab, because a fresh arrival resets to Trends. With the
        // app lock on, each call raised its own challenge and pendingUnlockScope is a
        // single field, not a queue — so the second overwrote the first, and completing
        // the first returned to a scope that had already been cleared. That scope then
        // stayed locked with nothing left to unlock it.
        if (a.progressTabOnce >= 0) { a.progressTab = a.progressTabOnce; a.progressTabOnce = -1; }

        // THE PAGE TITLE CARRIES THE BLUR TOGGLE. One control for the whole page — every
        // tab's tiles, both trend headlines, the chart, the photos and the recent list —
        // rather than an 👁 beside each of eleven numbers. See the PRIVACY BLUR section
        // note for why that changed, and enterDest()'s own note for why the reveal survives
        // a screen change (Progress, the readings list, a photo and Compare are ONE act of
        // reading).
        LinearLayout titleRow = new LinearLayout(a);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        Ui.head(a, titleRow, "Progress");
        // Ui.head added its label already; re-weight it so the eye keeps a fixed width at
        // the right and the title takes the rest of the line.
        View headLbl = titleRow.getChildAt(titleRow.getChildCount() - 1);
        headLbl.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        a.pageEye(titleRow, "your measurements and photos");
        a.body.addView(titleRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // THE TAB STRIP (Stage B task 9, dist/final-design.html section 4/PROGRESS):
        // TRENDS/PHOTOS/SESSIONS, replacing what used to be one long scroll (and, for
        // PHOTOS, a separate pushed screen — see progressPhotosContent()'s doc). One
        // Nav.SCR_SESSION_HIST screen underneath all three, so a tab tap is a same-screen
        // redraw, never a navigation — no give-up gate, no back-stack entry, exactly the
        // "page-section switch" Ui.tabStrip's own doc distinguishes from a filter pill.
        Ui.tabStrip(a, a.body, new String[]{ "TRENDS", "PHOTOS", "SESSIONS" }, a.progressTab,
            new View.OnClickListener[]{
                new ProgressTabTap(a.PROGRESS_TRENDS),
                new ProgressTabTap(a.PROGRESS_PHOTOS),
                new ProgressTabTap(a.PROGRESS_SESSIONS) });

        if (a.progressTab == a.PROGRESS_PHOTOS) progressPhotosContent();
        else if (a.progressTab == a.PROGRESS_SESSIONS) progressSessionsContent();
        else progressTrendsContent();

        // The considered entrance: the same first-paint cascade Today uses (staggerBodyIn),
        // extended to Progress so every destination's arrival reads as one considered motion
        // rather than Today alone feeling finished and the rest feeling unstyled. A tab tap
        // is a same-screen redraw (bodyArrived stays false), so this plays only on arrival.
        a.staggerBodyIn();
    }

    /**
     * TRENDS (Stage B task 9) — period pills, the body/training tiles, the length·girth
     * trend, the readings and Compare drill-downs, consistency, expansion, then-vs-now and
     * the export door. Everything showSessionHistory() used to draw between the period row
     * and "Recent", unchanged, just moved into its own tab — and the home for Tasks 10-12's
     * goal instrument (dist/final-design.html's M1), which lands inside this method.
     */
    private void progressTrendsContent() {
        // TASK 6 — "Measurements & goals". Same append-only shape as
        // progressPhotosContent()'s own gate a few hundred lines up — see that one's doc
        // for why `body` is not cleared here and why the callback re-enters through
        // showSessionHistory() rather than this method. This ALSO covers everything this
        // tab's own buttons lead to only in the sense of standing between the user and the
        // buttons themselves (Compare/Photos/Export/All-readings) — each of THOSE doors
        // asks its own gate independently the moment it is reached (PHOTOS for Compare and
        // the Photos shortcut, MEASUREMENTS again for renderMeasHist, SETTINGS for
        // showExport), so a scope left off here while another is on can never be walked
        // around by tapping a button drawn on a screen that scope's own gate happened to
        // let through.
        if (a.appLockGate(AppLock.MEASUREMENTS, new Runnable() {
                @Override public void run() { showSessionHistory(); }
            })) return;
        a.periodRow(new String[]{ "6W", "3M", "6M", "1Y", "All" },
                  new int[]{ Meas.PERIOD_6W, Meas.PERIOD_3M, Meas.PERIOD_6M, Meas.PERIOD_1Y,
                             Meas.PERIOD_ALL });
        a.sinceLastStrip();
        a.unmeasuredLevelCard();
        a.levelSpanCard();

        long mhNow = System.currentTimeMillis();
        // STAGE H TASK 5: same deload widening as Today's own Summary.of call — see there.
        long[] mhDeloadDays = TrainerTab.deloadDayRange(a.model);
        Summary.Stats st = Summary.of(a.model.sessLog.all, mhNow, a.model.sched,
            mhDeloadDays[0], mhDeloadDays[1]);
        List<Model.Reading> mw = Meas.windowFor(a.model.measLog, a.mhPeriod, mhNow); // newest-first
        double[] mdelta = Meas.deltaFor(a.model.measLog, a.mhPeriod, mhNow);
        // C8: which method each tile's change is within (Meas#periodPair - the same pair
        // deltaFor subtracts), so the tile says whose change it is.
        Model.Reading[] lenPair = Meas.periodPair(a.model.measLog, a.mhPeriod, mhNow, true);
        Model.Reading[] girPair = Meas.periodPair(a.model.measLog, a.mhPeriod, mhNow, false);

        // THE 4-DIAL STAT ROW (Stage B task 10, dist/final-design.html section
        // 4/PROGRESS) — LEN, GIR, P/P% and HRS, exactly the four tiles the locked
        // mockup draws as ONE row, in the colours those two body figures already wear
        // everywhere else: lime is progress, violet is a body measurement. Neither is
        // green; nothing here is a safety verdict.
        //
        // THIS REPLACES what used to be two separate 3-tile rows here — length/girth/
        // readings, then streak/this-week/completed — rather than sitting alongside
        // them: the locked spec draws one row of four, not six numbers spread across
        // two, and every figure that only lived in the dropped rows still has a home.
        // STRK, the week ring and completed% are Today's own streak card, built from
        // this exact SAME Summary.Stats — this tab never was their only appearance.
        // The readings count that used to be the dropped third tile is restated below
        // this row, for sighted users, once there is a window to state it about — see
        // the caption just above trendCard() a few lines down.
        Double ppct = Meas.firstPrePostPct(mw);
        long hrsCutoff = Meas.periodCutoff(a.mhPeriod, mhNow);
        long hrsSec = Summary.totalSecInWindow(a.model.sessLog.all, hrsCutoff);
        /* P3 - AN EMPTY INSTRUMENT ROW IS NOT AN INSTRUMENT ROW.
         *
         * With no readings logged, three of these four tiles are an em-dash and the trend
         * below them is a paragraph explaining why there is no trend. That was the first
         * thing on the screen people open to see whether anything is happening: four boxes,
         * three of them empty, above an apology.
         *
         * The row is drawn only once there is something in it. What replaces it is one card
         * that says what to do to fill it and offers the control that does so - and the
         * moment a second reading exists, everything below comes back untouched. Nothing is
         * removed from the app; it is withheld until it can say something. */
        boolean anyReading = !Double.isNaN(mdelta[0]) || !Double.isNaN(mdelta[1])
                          || ppct != null;
        if (!anyReading) {
            // G-Progress: how a trend starts is behind the title's ⓘ; the button says what
            // to do. PR-2: the time row has the name the filled state's tile has.
            LinearLayout eg = Ui.cardGroup(a, a.body, "Nothing to trend yet", null, Ui.BODY,
                "Nothing to trend yet", "Two readings make a trend. The first is logged "
                + "from the baseline screen at the start of a session, or on its own with "
                + "Log a reading.", false);
            Button lg = Ui.big(a, eg, "Log a reading", Ui.BODY);
            lg.setOnClickListener(a.new OpenLogReadingTap(Nav.PROGRESS));
            if (hrsSec > 0)
                Ui.kvRow(a, eg, "Time under pressure", timeWords(hrsSec), Ui.TEXT, null);
        }
        LinearLayout statTiles = new LinearLayout(a);
        statTiles.setOrientation(LinearLayout.HORIZONTAL);
        // The two cm figures mask under the privacy blur; P/P% is ALSO a body
        // measurement (a same-day post-vs-pre percentage, same as the two beside it —
        // see trendLead()'s own "the headline figures are body measurements" rule a few
        // hundred lines down, which this tile is a restatement of) and masks the same
        // way, through the same key, so revealing the row reveals all three together.
        // HRS does not mask — a training-time sum is not a measurement of the body,
        // the same reasoning that kept the readings count it replaced unmasked.
        String tileKey = a.measKey("period-tiles");
        // Double.isNaN(mdelta[i]), not mw.size() < 2: deltaFor() now gates each metric
        // independently on whether it was actually measured (Model.Reading#measuredLength/
        // measuredGirth) — a window full of readings can still have no GIRTH delta at all
        // if every one of them is a length-only method, which the old readings-count check
        // could not see.
        String[] tileVals = {
            Double.isNaN(mdelta[0]) ? "—" : a.shown(Model.Fmt.lenDelta(mdelta[0]), tileKey),
            Double.isNaN(mdelta[1]) ? "—" : a.shown(Model.Fmt.lenDelta(mdelta[1]), tileKey),
            ppct == null ? "—" : a.shown(Say.prePostPctText(ppct.doubleValue()), tileKey),
            timeWords(hrsSec) };
        a.fillTiles(statTiles, tileVals,
            new String[]{ "Length", "Girth", "After vs before", "Time" },
            new int[]{ Ui.ACCENT, Ui.BODY, Ui.TEXT, Ui.TEXT });
        // PR-1: plain tile labels; WHICH METHOD each change is within is said to a reader
        // (it was the "LEN · BPSSL" label), and the time is "45 min", never "45:00".
        String[] tileSaid = {
            "Length" + methodSaid(lenPair), "Girth" + methodSaid(girPair),
            "After versus before, same day", "Time under pressure" };
        for (int ti = 0; ti < statTiles.getChildCount() && ti < tileSaid.length; ti++) {
            statTiles.getChildAt(ti).setContentDescription(tileSaid[ti] + ": "
                + A11y.collapse(tileVals[ti]));
        }
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.bottomMargin = Ui.dp(a, Look.S3);
        if (anyReading) a.body.addView(statTiles, stLp);

        // THE HEADLINE OF THE SCREEN, on the screen. The length/girth trend used to live
        // behind a second tab, which put the one picture this page exists for one tap away
        // from where it belongs. Same window, same pure helpers, one place.
        // OLDEST-FIRST, hoisted above the branch below: Task 12's goal instrument card
        // (after consistencyCard, a few lines down) needs the exact same window trendCard's
        // own goal line already reads from, and re-deriving it a second time down there
        // would risk the two disagreeing about which readings the anchor and "today" come
        // from. Reversing an empty or single-reading list is harmless, so hoisting this
        // above the size check costs nothing on the "too few readings" path.
        List<Model.Reading> oldestFirst = new ArrayList<Model.Reading>(mw);
        Collections.reverse(oldestFirst);
        if (mw.size() < 2) {
            /* NOT WHEN THE EMPTY CARD ALREADY SAID IT. With nothing logged at all, the
             * "Nothing to trend yet" card above is this same sentence with a button on it -
             * and printing both left three separate places on one screen all announcing
             * that there are no readings. The card is the better of the two, because it
             * offers the thing it is explaining the absence of. */
            if (!a.model.measLog.all.isEmpty())
                Ui.note(a, a.body, mw.isEmpty()
                    ? "No readings in this window. Widen the period."
                    : "One reading in this window — nothing to trend yet. Widen the period"
                      + (a.model.measLog.all.size() > 1 ? "."
                                                      : ", or log another next session."));
        } else {
            // THE READINGS COUNT, restated here for SIGHTED users. The dropped third
            // tile used to be the one place this screen stated it in the clear; the
            // trend chart's own contentDescription says a number too, but that is
            // screen-reader-only AND scoped to the SELECTED chart series (mhSeries),
            // so a user with any series unchecked would hear a smaller count than the
            // window actually holds. This line states the true window total (mw.size(),
            // the same figure LEN/GIR/P-P% above were computed from) to everyone who
            // looks at the screen, not only to a screen reader. Never masked — a count
            // of how many times you measured is not a measurement of you.
            TextView readingsCap = new TextView(a);
            readingsCap.setText(mw.size() + " readings in this window");
            // DIM, not FAINT (Task 13 colour-discipline sweep): this line is stated "to
            // everyone who looks at the screen" (see the doc above) — normal-size prose a
            // sighted user must read, not a label/unit, so it must clear AA at this size,
            // which FAINT does not (Look#FAINT's own doc: clears AA only as large text).
            readingsCap.setTextColor(Ui.DIM);
            readingsCap.setTextSize(Look.SP_MICRO);
            LinearLayout.LayoutParams rcLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rcLp.bottomMargin = Ui.dp(a, Look.S1);
            a.body.addView(readingsCap, rcLp);
            a.trendCard(oldestFirst, mdelta, mhNow);
        }

        // The readings drill-down — a LINK, not a sibling tab: unlike TRENDS/PHOTOS/SESSIONS
        // above it, every row, its editor, the paging and the comparability verdict all
        // still live on a PUSHED screen reached from here, not on a tab of their own.
        Button allReadings = Ui.flat(a, a.body, "All readings ›");
        allReadings.setOnClickListener(a.new Tap(SessionActivity.Tap.MEASHIST));
        Button cmp = Ui.flat(a, a.body, "Compare two dates ›");
        cmp.setOnClickListener(a.new Tap(SessionActivity.Tap.COMPARE));
        // THE GALLERY'S DOOR, beside Compare's: a shortcut straight onto the PHOTOS tab,
        // worded exactly as the door always was. It stopped being a drill-down onto a
        // separate pushed screen when Stage B task 9 folded that screen's content into the
        // PHOTOS tab (see progressPhotosContent()), so tapping it is now the same act as
        // tapping PHOTOS in the strip above — a same-screen redraw, not a navigation.
        int photoTotal = Gallery.photoCount(a.model.measLog);
        Button photos = Ui.flat(a, a.body, "Photos ›");
        photos.setContentDescription(photoTotal == 0
            ? "Photos. None yet."
            : "Photos. " + photoTotal + (photoTotal == 1 ? " photo" : " photos")
              + ", grouped by day.");
        photos.setOnClickListener(new ProgressTabTap(a.PROGRESS_PHOTOS));

        List<Model.Sess> all = a.model.sessLog.all;

        // THE PICTURES OF THE FILED LOG. Consistency over the last week, what you have never
        // beaten, and a month of dose. All are derivations of the same log the SESSIONS tab
        // PRINTS - which is the distinction the two tabs now draw: this one summarises the
        // log, that one lists it. All live in the pure layer where they are asserted, because
        // a chart that is quietly wrong is worse than no chart.
        a.consistencyCard(st);
        // B3 - these two came from the top of SESSIONS, where they sat above the list that
        // tab is named for. Records first, because "best ever" reads as a header to a month
        // of dose rather than a footnote under it.
        prCard(all);
        doseHeatmapCard(all);
        // THE M1 GOAL INSTRUMENT (Stage B Task 12, dist/final-design.html section
        // 4/PROGRESS's "GOAL · BPSSL 22.9 · IF THE TREND HOLDS" panel) — its own dedicated
        // card, placed here (after the 4-dial row and the consistency strip, before
        // expansion/then-vs-now) per the locked mockup's own order. Same window trendCard's
        // goal line/pace sentence already reads from; draws nothing at all when no
        // goal-bearing method has both a goal set and a resolvable anchor in it — see the
        // method's own doc for exactly which goal it headlines.
        //
        // GATED ON THE SAME `mw.size() >= 2` the "nothing to trend yet" note above is
        // gated on (review round 1, finding 5) — this call sits structurally AFTER that
        // if/else (it has to, per the locked placement: after the consistency strip), so
        // the guard is repeated explicitly here rather than inherited from the branch.
        // Without it, a window with exactly one reading showed BOTH "One reading in this
        // window — nothing to trend yet" AND a full dated goal-crossing projection with a
        // non-zero percentage — two cards actively contradicting each other about whether
        // this window has anything to show.
        //
        // THE S6+ "GOAL REACHED" MOMENT sits directly above the instrument it is about,
        // matching the locked mock's own order — and is asked over the WHOLE log
        // (Meas#reversed(model.measLog.all)), never the period-filtered `oldestFirst`:
        // whether a goal is reached must not depend on which date-range pill happens to
        // be selected right now.
        a.goalReachedCard();
        if (mw.size() >= 2) a.goalInstrumentCard(oldestFirst);
        expansionCard(all);
        tauTrendCard(all);
        thenVsNowCard();
        insightsCard();

        // The data-export door (§8 #9): the one place the user's measurements can leave
        // this phone, and only ever by their own hand from here.
        Button exportBtn = Ui.flat(a, a.body, "Export your data ›");
        exportBtn.setOnClickListener(a.new OpenExportTap());
    }

    /**
     * THE INSIGHTS CARD (S8 A, round6-options.html's decisions list — "insights card: 3
     * computed lines, weekly, min-sample floors, on-device"). Up to three plain-language
     * lines from {@link Insight#insightLines} — consistency vs schedule, which body metric
     * is growing fastest, and the completion rate — each already gated on its own named
     * floor, so whatever comes back is already the honest set to show; this method only
     * lays the lines out.
     *
     * RECOMPUTED ON RENDER, never persisted: every input is already an in-memory list this
     * screen holds for its other cards, so asking again on every open costs nothing a
     * background job would improve on. "Weekly" (S8 A's own wording) describes how often
     * the picture can meaningfully change, not a schedule this method has to keep — {@link
     * Insight#INSIGHT_WINDOW_DAYS}'s trailing 28-day window moves roughly a week's worth
     * with every real week that passes, and asking on every render is what keeps it moving.
     *
     * Draws nothing at all when no line has cleared its own floor yet — never an empty
     * card, and never a placeholder guessing from too little data.
     *
     * THE TREND LINE MASKS under the app's privacy blur, the same way every other
     * measurement-derived figure on this tab does ({@link #trendLead}, the volume
     * mini-chart, {@link #goalReachedCard}) — it is a cm delta read off the user's own
     * readings. The consistency and quality lines are session statistics, not body
     * measurements, and are never blurred.
     */
    private void insightsCard() {
        long now = System.currentTimeMillis();
        // POLISH #3: the consistency line starts counting at enrolment, never before.
        List<String> lines = Insight.insightLines(a.model.sessLog.all, a.model.sched,
            a.model.measLog, now, a.model.trainerEnrolledAt > 0
                ? Summary.dayNumber(a.model.trainerEnrolledAt) : 0L);
        if (lines.isEmpty()) return;

        // THE TREND LINE ("Length/Girth is growing fastest..." or "Little change..."),
        // when present, is the one BODY-MEASUREMENT line of the three: a cm delta read
        // straight off the user's own readings, the same category trendLead()'s own
        // headline figures already mask. Consistency ("Trained N of M scheduled days...")
        // and quality ("P% of sessions ran to completion...") are SESSION statistics, not
        // body measurements, and stay unmasked — the same "sessions are not blurred"
        // treatment prCard()'s longest-hold/deepest-pressure records and the dose heatmap
        // already get.
        //
        // Identified by VALUE, not position: insightLines() only includes whichever of
        // the three lines cleared its own floor, so consistency or quality can be absent
        // while trend still shows, and trend's index inside `lines` is not fixed.
        // Recomputing it here is the same pure (measLog, now) call insightLines() already
        // makes internally — this whole card is already recomputed on every render (see
        // this method's own doc above), so a second call costs nothing new.
        String trendText = Insight.trendLine(a.model.measLog, now);
        final String trendKey = a.measKey("insights-trend");
        final boolean hideTrend = trendText != null && a.blurred(trendKey);

        Ui.sectionHead(a, a.body, "Insights", Ui.ACCENT);
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 11), Ui.dp(a, 10), Ui.dp(a, 11), Ui.dp(a, 10));

        StringBuilder said = new StringBuilder("Insights. ");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean isTrend = trendText != null && line.equals(trendText);
            TextView t = new TextView(a);
            t.setText(isTrend ? a.shown(line, trendKey) : line);
            t.setTextColor(Ui.TEXT);
            t.setTextSize(Look.SP_BODY);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) tlp.topMargin = Ui.dp(a, Look.S2);
            card.addView(t, tlp);
            // A HIDDEN VALUE IS HIDDEN FROM THE READER TOO — trendLead()'s own rule,
            // applied to the one line here that can carry a measurement.
            said.append(isTrend && hideTrend
                ? "trend hidden — reveal to read it."
                : line).append(' ');
        }
        Ui.group(card, said.toString().trim());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 10);
        a.body.addView(card, lp);
    }

    /**
     * SESSIONS (Stage B task 9) — the "Recent" list: every session actually filed, newest
     * first, with the notes search, its per-row read-only detail sheet and its save-as-you-
     * ran-it door. Identical to what showSessionHistory() always drew here — only the
     * position moved, into its own tab rather than the tail of one long scroll.
     */
    private void progressSessionsContent() {
        // TASK 6 — "Session history". Same append-only shape as progressPhotosContent()'s
        // own gate — see that one's doc.
        if (a.appLockGate(AppLock.SESSIONS, new Runnable() {
                @Override public void run() { showSessionHistory(); }
            })) return;
        List<Model.Sess> all = a.model.sessLog.all;

        /* B3 - RECORDS AND THE HEATMAP MOVED TO TRENDS.
         *
         * This tab was doing three jobs stacked in one scroll: personal records, a month of
         * dose, and then the session list - so the list this tab is NAMED for started two
         * cards down, and somebody coming here to find a session scrolled past two summaries
         * every time. Both of those are trend content by nature: they are derivations over
         * the whole log rather than entries in it, which is exactly what TRENDS holds. The
         * tab strip carries the separation now, instead of the scroll. */
        Ui.sectionHead(a, a.body, "All sessions", Ui.DIM);     // PR-4: one name, Sessions

        // SEARCH THE NOTES. A dialog rather than an inline field, because this screen
        // is rebuilt from scratch on every tap - an EditText living in `body` would
        // lose its focus and its keyboard on the next render. That is the same reason
        // the set editor renames through a dialog and the reading editor writes its
        // note through one. The row STATES the current filter, so this list is never
        // quietly showing a subset with nothing on screen to say so.
        Button search = Ui.flat(a, a.body, a.noteFilter.length() == 0
            ? "Search notes ›"
            : "Notes matching “" + a.noteFilter + "” ›");
        search.setContentDescription(a.noteFilter.length() == 0
            ? "Search notes. Filters this list to sessions whose note or feel matches."
            : "Notes matching " + a.noteFilter + ". Tap to change the search.");
        search.setOnClickListener(new SearchNotesTap());

        // The filtering itself is Meas#matchingNotes - pure and pinned, so what
        // "matches" means is asserted in the harness rather than read off this screen.
        List<Model.Sess> listed = Meas.matchingNotes(all, a.noteFilter, a.FEEL_VALUES, a.FEEL_WORDS);
        if (a.noteFilter.length() > 0) {
            // The clear-filter chip. A filter is a MODE this screen is in, and leaving
            // it has to be one obvious tap rather than "open the dialog and empty it".
            Ui.row(a, a.body,
                new String[]{ "✕ Clear filter · " + listed.size()
                              + (listed.size() == 1 ? " session" : " sessions") },
                new View.OnClickListener[]{ new ClearNoteFilterTap() });
        }

        if (all.isEmpty()) {
            Ui.noteInfo(a, a.body, "No sessions filed yet.",
            "Sessions", "No sessions filed yet. Every session is filed here when it "
                 + "ends — completed or stopped early.");
        } else if (listed.isEmpty()) {
            // A filter that matches nothing names the filter AND says the sessions are
            // still there. An empty history under a search the user has forgotten
            // setting is indistinguishable from data loss unless the screen says which.
            Ui.note(a, a.body, "No session's note or feel matches “" + a.noteFilter
                + "”. Nothing has been deleted — clear the filter to see "
                + "all " + all.size() + ".");
        }
        int shown = Math.min(listed.size(), 40);
        for (int i = 0; i < shown; i++) {
            Model.Sess e = listed.get(i);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);

            // The tone rail, composed exactly as Ui.chip composes its own: the row now has
            // a ROUNDED background, and a square-ended bar flush against a rounded corner
            // would hang outside it (a background shape does not clip children). So the row
            // carries a small inset and the bar is itself a rounded rect — the same shape
            // relationship the state chip uses everywhere else in the app.
            //
            // ONLY FOR A FAULT (Look#earnsSpine): a red rail for a session that measured a
            // shrink. It was on every row - green, grey or red - and a stripe on every row is
            // a stripe nobody reads; now the one that matters is the only one there.
            int railTone = a.toneColour(Summary.tone(e));
            boolean rail = Look.earnsSpine(railTone);
            // A row without a rail starts its words where a railed row's words start (the
            // 4dp rail plus its 8dp gap), so the list keeps one left edge for its text.
            row.setPadding(Ui.dp(a, rail ? Look.S2 : Look.S2 + 12), Ui.dp(a, Look.S2),
                           Ui.dp(a, Look.S2), Ui.dp(a, Look.S2));
            if (rail) {
                View bar = new View(a);
                bar.setBackground(Ui.roundRect(a, railTone, 2));
                LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                        Ui.dp(a, 4), ViewGroup.LayoutParams.MATCH_PARENT);
                barLp.rightMargin = Ui.dp(a, 8);
                row.addView(bar, barLp);
            }

            TextView t = new TextView(a);
            String delivered = e.peakKpa == null ? "no reading"
                : "peak " + Model.Fmt.p(e.peakKpa.doubleValue())
                  + "  ·  " + Model.Fmt.dose(e.doseKpaS);   // follows the display unit
            // The tissue response test's row. A row whose test settings differ from the
            // newest assessed session for the same routine is marked as such - a trend must
            // never put two incomparable numbers on one line, and the editor's warning and
            // this mark come from the same Tau.sameStimulus decision. M4: said as what it IS
            // (other test settings, named) rather than "different stimulus - not comparable".
            String tauLine = Summary.tauTag(e);
            if (tauLine.length() > 0) {
                Model.Sess newest = Summary.lastAssessed(all, e.routineId);
                if (newest != null && e.assessDurSec > 0
                        && !Tau.sameStimulus(newest.assessKpa, newest.assessSp,
                                             newest.assessDurSec,
                                             e.assessKpa, e.assessSp, e.assessDurSec))
                    tauLine += "  ·  earlier test settings ("
                             + Tau.stimulus(e.assessKpa, e.assessSp, e.assessDurSec) + ")";
                tauLine = "\n" + tauLine;
            }
            t.setText((e.routineName == null || e.routineName.length() == 0
                        ? "(routine deleted)" : e.routineName)
                + "\n" + a.dayLabel(e.ts) + "  ·  " + Model.Fmt.t(e.durSec) + "  ·  "
                + Summary.tagShown(e.tag)
                + "\n" + cyclesPart(e) + delivered
                + tauLine);
            t.setTextColor(Ui.TEXT);
            t.setTextSize(Look.SP_CAPTION);
            t.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
            row.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // POLISH #5: ONE card per session — the save door lives INSIDE it, on its
            // bottom line, instead of floating beneath as a detached grey button that
            // read as disabled. The card body still opens the detail sheet; the door
            // is its own child target, so the two listeners never intercept each other.
            row.setOnClickListener(a.new OpenSessionDetail(e));
            row.setOnTouchListener(new Ui.Press());
            row.setMinimumHeight(Ui.dp(a, 48));
            LinearLayout outer = Ui.col(a);
            outer.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
            Ui.lift(a, outer, Ui.ELEV_CARD);
            outer.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            historyDoorSaveCard(outer, e);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = Ui.dp(a, 6);
            a.body.addView(outer, rowLp);
        }
        if (listed.size() > shown)
            Ui.note(a, a.body, (listed.size() - shown) + " older session"
                 + (listed.size() - shown == 1 ? "" : "s") + " not shown.");

        Ui.noteInfo(a, a.body, "A stopped session is filed as an attempt.",
            "Sessions", "A stopped session is filed as an attempt with the duration the "
             + "summary showed — the same number, from the same derivation, never a second one.");
    }

    /**
     * THE PR CARD (S5 A, round6-options.html's decisions list — "PR card in Progress >
     * Sessions; lime flash on a new record") — the longest hold and the deepest pressure
     * ever filed, read off {@link Milestones#longestHoldSec}/{@link
     * Milestones#deepestPeakKpa}. See those two methods' own doc for why this is NOT a
     * milestone: no badge language, no seen-once persistence, nothing that nudges toward
     * a bigger number next time.
     *
     * THE LIME FLASH is drawn fresh on every render, never persisted — see {@link
     * Milestones#isNewHoldRecord}'s own doc for why recomputing "did the newest session
     * set the record" on every open is correct rather than a shortcut, unlike S6+'s
     * goal-reached card a few methods down, which IS a one-time-ever moment.
     */
    private void prCard(List<Model.Sess> all) {
        if (all == null || all.isEmpty()) return;
        Long bestHold = Milestones.longestHoldSec(all);
        Double bestPeak = Milestones.deepestPeakKpa(all);
        if (bestHold == null && bestPeak == null) return;

        Ui.sectionHead(a, a.body, "Personal records", Ui.ACCENT);
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 11), Ui.dp(a, 10), Ui.dp(a, 11), Ui.dp(a, 10));

        if (bestHold != null)
            prRow(card, "Longest hold", Model.Fmt.t(bestHold.longValue()),
                Milestones.isNewHoldRecord(all));
        if (bestPeak != null)
            prRow(card, "Deepest pressure", Model.Fmt.p(bestPeak.doubleValue()),
                Milestones.isNewPeakRecord(all));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 10);
        a.body.addView(card, lp);
    }

    /** One PR row: label/value through the usual {@link Ui#kvRow}, plus — only when
     *  `isNew` — a lime-tinted background and "· New record" on the value, flashed in
     *  once ({@link #flashLime}) so the eye catches it once rather than it snapping in
     *  looking identical to every other row. */
    private void prRow(ViewGroup parent, String label, String value, boolean isNew) {
        LinearLayout row = Ui.kvRow(a, parent, label,
            isNew ? value + "  ·  New record" : value, isNew ? Ui.ACCENT : Ui.TEXT, null);
        if (!isNew) return;
        row.setBackground(Ui.roundRect(a, Ui.ACCENT_DIM, Look.R_CARD));
        row.setPadding(Ui.dp(a, Look.S2), row.getPaddingTop(),
                       Ui.dp(a, Look.S2), row.getPaddingBottom());
        a.flashLime(row);
    }

    private void doseHeatmapCard(List<Model.Sess> all) {
        if (all == null || all.isEmpty()) return;
        long now = System.currentTimeMillis();
        PhotoCalendar.Month month = PhotoCalendar.monthOf(now);
        // Walk back one month at a time rather than doing calendar arithmetic here: prevMonth
        // already knows about year boundaries and is the same helper the photo grid uses.
        for (int i = 0; i < a.doseMonthsBack; i++) month = PhotoCalendar.prevMonth(month);
        int days = PhotoCalendar.daysInMonth(month);
        long[] dayNumbers = new long[days];
        for (int d = 1; d <= days; d++)
            dayNumbers[d - 1] = Summary.dayNumber(month.year, month.month0 + 1, d);

        java.util.Set<Long> done = new java.util.HashSet<Long>();
        for (int i = 0; i < all.size(); i++) {
            Model.Sess s = all.get(i);
            // Summary.trainedDay, not `completed` \u2014 the heatmap and the strip above must
            // agree with the streak about which days were trained (audit A5).
            if (s.manual || !Summary.trainedDay(s)) continue;
            done.add(Long.valueOf(Summary.dayNumber(s.ts)));
        }
        long today = Summary.dayNumber(now);
        // STAGE H TASK 5: same deload widening as consistencyCard's own Summary.of/monthRoles
        // calls — the heatmap and the consistency strip must never disagree about a deload
        // day either, exactly like the pre-existing "never disagree about missed days" note
        // above.
        long[] deloadDays = TrainerTab.deloadDayRange(a.model);
        Summary.Stats st = Summary.of(all, now, a.model.sched, deloadDays[0], deloadDays[1]);
        long protectedDay = (st.protectedIdx >= 0)
            ? today - (Summary.WEEK_DAYS - 1 - st.protectedIdx) : -1;

        int[] roles = Insight.monthRoles(dayNumbers, done, a.model.sched, today, protectedDay,
            deloadDays[0], deloadDays[1]);
        // POLISH #3: nothing before the plan existed on this phone reads as missed.
        Insight.clampRolesSince(roles, dayNumbers, a.model.trainerEnrolledAt > 0
            ? Summary.dayNumber(a.model.trainerEnrolledAt) : 0L);
        int[] tiers = Insight.doseTiers(dayNumbers, all);

        // PR-11: named for what it is, and the key - which was nowhere - behind its ⓘ
        // (G-Progress: the owner's choice over a footer line).
        headWithInfo("Training calendar", Ui.ACCENT, "Training calendar",
            "Brighter = more time under pressure that day · red outline = a planned day "
            + "you missed");
        // MONTH NAVIGATION (audit E8). The card only ever drew the CURRENT month, so
        // every day before the 1st was unreachable — on the 2nd of the month the whole
        // history was two squares. Back stops at the oldest filed session, forward at this
        // month: between them is exactly the range there is anything to colour.
        /* THE MONTH STEPPER IS CalendarGrid#monthNav, the same one the photo calendar uses.
         *
         * This row was built by hand out of Ui.flat, whose layout params are MATCH_PARENT by
         * contract - so in a HORIZONTAL row the ‹ claimed the whole width (1002px of 1002px,
         * measured) and pushed the month name and the › clean off the screen. The heatmap
         * could be walked backwards for ever, never forwards, and never said which month you
         * were looking at.
         *
         * BOUNDED AT BOTH ENDS NOW, not just at today. Back was "unbounded" and that meant a
         * person could step into an unlimited run of blank months with nothing to say they
         * had left their own history; it stops at the month of the oldest filed session,
         * which is as far back as there is anything to colour. */
        long oldestTs = now;
        for (int i = 0; i < all.size(); i++) {
            Model.Sess s = all.get(i);
            if (s != null && s.ts > 0 && s.ts < oldestTs) oldestTs = s.ts;
        }
        PhotoCalendar.Month first = PhotoCalendar.monthOf(oldestTs);
        boolean canPrev = month.year > first.year
                       || (month.year == first.year && month.month0 > first.month0);
        String[] monNames = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                              "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
        LinearLayout monthNav = CalendarGrid.monthNav(a,
            monNames[month.month0] + " " + month.year,
            canPrev, a.doseMonthsBack > 0,
            "Show the previous month", "Show the next month",
            a.new DoseMonthTap(1), a.new DoseMonthTap(-1));
        a.body.addView(monthNav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout grid = CalendarGrid.doseCard(a, month, dayNumbers, tiers, roles,
            new DoseDayOpen(all), "");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 10);
        a.body.addView(grid, lp);
    }

    /** A dose-heatmap day tap: opens the session {@link Insight#sessionOnDay} names for
     *  that day, through the SAME read-only detail sheet the "Recent" list's own rows
     *  open — one door into a session's detail, not a second one this screen invented. */
    private final class DoseDayOpen implements CalendarGrid.DoseTaps {
        private final List<Model.Sess> log;
        DoseDayOpen(List<Model.Sess> log) { this.log = log; }
        @Override public void onDay(long dayNumber) {
            Model.Sess s = Insight.sessionOnDay(log, dayNumber);
            if (s != null) a.showSessionDetail(s);
        }
    }

    /** The real entry point (from Settings) — resets to the default period and the first
     *  page, so re-opening the screen never resumes some other period a previous visit
     *  left scrolled deep into. Returning here from the reading editor (Save/Discard/
     *  Delete) calls renderMeasHist() directly instead, which deliberately does NOT reset
     *  these — editing a row should not punt the user back to page 1 of the default period. */
    void openMeasHist() {
        a.mhPeriod = Meas.PERIOD_3M;
        // LENGTH, always. It used to open on whichever metric a stored flag said the one
        // goal was about; with a goal per method there is no single "the goal" to open on,
        // and length is the metric this app's first chip has always been. The girth goals
        // are one chip away and draw their own lines when it is tapped.
        a.mhMetric = a.METRIC_LEN;
        a.mhSeries.clear();           // re-defaults (Meas#defaultSeries) on next render
        a.mhSeriesNone = false;
        a.mhPagesLoaded = 1;
        renderMeasHist();
    }

    void renderMeasHist() {
        a.body.removeAllViews();
        a.enterDest(Nav.SCR_MEAS_HIST);
        // TASK 6 — "Measurements & goals", the SAME scope progressTrendsContent() asks.
        // Gated HERE rather than at openMeasHist() (the "fresh entry from Settings/Trends"
        // door) because this method is ALSO the return path from the reading editor's own
        // Save/Discard/Delete (see the doc a few lines below on openMeasHist() for that
        // distinction) — gating the content function itself, not each door to it, is what
        // makes every path into the readings list ask the same question once, including
        // one this task did not have to go hunting for separately.
        if (a.appLockGate(AppLock.MEASUREMENTS, new Runnable() {
                @Override public void run() { renderMeasHist(); }
            })) return;
        // The DRILL-DOWN, not a sibling tab. Progress is TRENDS/PHOTOS/SESSIONS as of Stage
        // B task 9 (showSessionHistory); this is the every-reading list behind TRENDS's "All
        // readings ›" link, and it says so in its own title rather than repeating the page
        // name it was reached from.
        Ui.head(a, a.body, "Readings");

        // PR-5: NO READINGS, ONE CARD. The tiles, the notes, Compare and an empty list were
        // five widgets announcing the same absence; the card says it once and offers the fix.
        if (a.model.measLog.all.isEmpty()) {
            LinearLayout none = Ui.cardGroup(a, a.body, "No readings yet", null, Ui.BODY);
            Ui.big(a, none, "Log a reading", Ui.BODY)
                .setOnClickListener(a.new OpenLogReadingTap(Nav.PROGRESS));
            return;
        }

        periodRow(new String[]{ "6W", "3M", "6M", "1Y", "All" },
                  new int[]{ Meas.PERIOD_6W, Meas.PERIOD_3M, Meas.PERIOD_6M, Meas.PERIOD_1Y,
                             Meas.PERIOD_ALL });

        // The standalone front door (Task 19), the second place it is reachable from —
        // logging a reading is a Progress action, not only a Today one.
        Button logDoor = Ui.flat(a, a.body, "Log a reading ›");      // PR-16
        logDoor.setContentDescription("Log a reading, on its own with no routine or session");
        logDoor.setOnClickListener(a.new OpenLogReadingTap(Nav.PROGRESS));

        long now = System.currentTimeMillis();
        List<Model.Reading> w = Meas.windowFor(a.model.measLog, a.mhPeriod, now);   // newest-first
        double[] delta = Meas.deltaFor(a.model.measLog, a.mhPeriod, now);

        // Double.isNaN(delta[i]), not w.size() < 2 — see the identical note on the TRENDS
        // tab's LEN/GIR tiles: deltaFor() gates each metric on whether it was actually
        // measured, independently of how many readings (of ANY method) are in the window.
        // C8: and each change is within one method, which its label now names.
        a.statRow(new String[]{
                    pairLabel("Length change", Meas.periodPair(a.model.measLog, a.mhPeriod, now, true)),
                    pairLabel("Girth change", Meas.periodPair(a.model.measLog, a.mhPeriod, now, false)),
                    "Readings" },
                new String[]{ Double.isNaN(delta[0]) ? "—" : Model.Fmt.lenDelta(delta[0]),
                              Double.isNaN(delta[1]) ? "—" : Model.Fmt.lenDelta(delta[1]),
                              String.valueOf(w.size()) });

        // "Widen the period" is only advice when widening could actually help. With an
        // empty log it names an action whose precondition cannot be met — every window
        // over nothing is still nothing — so a first run has to be told where readings
        // come from instead.
        boolean noReadingsAtAll = a.model.measLog.all.isEmpty();
        if (w.size() < 2) {
            Ui.noteInfo(a, a.body,
                (noReadingsAtAll ? "No readings yet. Two are needed before anything can be trended." : w.isEmpty() ? "No readings in this window. Widen the period." : a.model.measLog.all.size() > 1 ? "One reading in this window, nothing to trend yet. Widen the period." : "One reading so far. Log another next session."),
                "Not enough readings",
                noReadingsAtAll
                ? "No readings yet. One is logged from the baseline screen at the start of a "
                  + "session — Settings › Ask for measurements decides how often you are asked. "
                  + "Two readings are needed before anything can be trended."
                : w.isEmpty()
                  ? "No readings in this window. Widen the period."
                  : "One reading in this window — nothing to trend yet. Widen the period"
                    + (a.model.measLog.all.size() > 1 ? "." : ", or log another next session."));
        } else {
            List<Model.Reading> oldestFirst = new ArrayList<Model.Reading>(w);
            Collections.reverse(oldestFirst);

            trendCard(oldestFirst, delta, now);
            // The two facts that change how this plot should be READ — that a gap means the
            // two readings either side are not comparable, and that a hollow dot was taken
            // without the hold — are not deducible from the shapes at all, so they are said
            // in words. (This line used to open "Solid line is length, dashed is girth",
            // from before the plot drew one metric at a time; dashes mean POST now, and the
            // series chips in the card say which line is which.)
            // STUDY-19 R17: the (i) said hollow dots and shaded stretches were readings
            // "comparable to each other, not to the held ones" - nothing is shaded any more,
            // and at-rest dots are filled like every other (V2).
            /* ITEM 20 / STUDY-19 R18 - WHAT THE WINDOW HOLDS, NOT A VERDICT ON IT: how many
             * of each kind (Meas#windowSummary). G-Progress: one line - the count - with the
             * one sentence on how they are compared and the Trend sheet behind its ⓘ, where
             * a separate "Each method is drawn as its own line." row and a card used to be. */
            String[] kinds = Meas.windowSummary(w);
            Ui.noteInfo(a, a.body, kinds[0], "Trend", kinds[1] + "\n\n"
                 + "Each method is its own line, and a reading is only compared with "
                 + "others of the same method. A break in a line means the readings either "
                 + "side were taken differently (for standardised readings, held at a "
                 + "different vacuum), so the step between them is not a change to read. "
                 + "A hollow dot on the Standardised line is a reading saved there with no "
                 + "hold recorded.");
        }

        Ui.noteInfo(a, a.body, "Only baseline readings are plotted.",
            "Trend", "Only baseline readings are plotted — readings right after a session "
            + "are swollen and settle within hours, so they would swamp the real trend.");

        Button openCompare = Ui.flat(a, a.body, "Compare two dates ›");     // PR-6
        openCompare.setOnClickListener(a.new Tap(SessionActivity.Tap.COMPARE));

        View readingsHeader = Ui.fieldLabel(a, a.body, "Readings", null);
        readingsHeader.setPadding(0, Ui.dp(a, Look.S6), 0, Ui.dp(a, Look.S2));

        if (w.isEmpty()) {
            Ui.note(a, a.body, noReadingsAtAll
                ? "Nothing logged yet — this fills in as you log baselines."
                : "No readings in this window.");
        } else {
            int visible = Meas.visibleCount(w.size(), a.mhPagesLoaded);
            // TASK 5 — a day header (the same sectionLabel() idiom "THEN VS NOW" already
            // uses) on every calendar-day change, so a multi-protocol sitting's rows read
            // as one grouped block instead of N rows each repeating their own date. Every
            // row still renders individually — see measRowText, unchanged, method label and
            // all (PD-1: that label already lives in Say.readingClass(), added nowhere a
            // second time here). Relies on Task 4's ordering guarantee (MeasLog is stable-
            // descending-by-ts at every write path) for same-day rows to be adjacent in `w`;
            // PhotoCalendar#dayKey, not Meas#dayOf, for the same reason every other readings
            // surface uses it — see Meas#sameDayClusters' own doc. The whole visible range
            // is walked from i=0 on every render (paging just grows `visible` and reruns
            // this method), so a page boundary landing inside a day's readings can never
            // orphan a header — the day's own header is (re)computed fresh either way.
            // ONE PROTOCOL AT A TIME, WHEN ASKED (audit A26). The chips list only the
            // methods actually present in this window \u2014 offering a filter for a protocol
            // with no readings in view would be a control that does nothing.
            java.util.ArrayList<Integer> present = new java.util.ArrayList<Integer>();
            for (int i = 0; i < w.size(); i++) {
                Integer m = Integer.valueOf(w.get(i).method);
                if (!present.contains(m)) present.add(m);
            }
            /* AND A FILTER MUST NEVER OUTLIVE THE ROW THAT CLEARS IT. The chips were drawn
             * only when the window held more than one protocol - so a filter set in a wide
             * period, carried into a narrower one holding a single method, left a list
             * filtered to a protocol with nothing in it and NO CHIP ANYWHERE to switch back
             * with. The filter is dropped when its protocol is not in view at all, and the
             * row is drawn whenever one is active whatever the window holds. */
            if (a.mhMethodFilter >= 0 && !present.contains(Integer.valueOf(a.mhMethodFilter)))
                a.mhMethodFilter = -1;
            if (present.size() > 1 || a.mhMethodFilter >= 0) methodFilter(present);
            if (a.mhMethodFilter >= 0) {
                java.util.ArrayList<Model.Reading> kept =
                    new java.util.ArrayList<Model.Reading>();
                for (int i = 0; i < w.size(); i++)
                    if (w.get(i).method == a.mhMethodFilter) kept.add(w.get(i));
                w = kept;
                visible = Meas.visibleCount(w.size(), a.mhPagesLoaded);
            }

            int lastDayKey = Integer.MIN_VALUE;
            for (int i = 0; i < visible; i++) {
                Model.Reading e = w.get(i);
                int dayKey = PhotoCalendar.dayKey(e.ts);
                if (dayKey != lastDayKey) {
                    a.sectionLabel(rowDateLabel(e.ts));
                    lastDayKey = dayKey;
                }
                Model.Reading prev = (i + 1 < w.size()) ? w.get(i + 1) : null;
                readingRow(e, prev);
            }
            int remaining = Meas.remaining(w.size(), a.mhPagesLoaded);
            if (remaining > 0) {
                // Secondary surfaceHigh, like the "Cancel" big buttons elsewhere — a paging
                // control is not the pump under command, so it must not wear amber.
                // PR-16: paging is a quiet row, not a primary-sized button.
                Button more = Ui.flat(a, a.body,
                    "Show " + Math.min(Meas.PAGE_SIZE, remaining) + " more (" + remaining
                    + " left)");
                more.setOnClickListener(new ShowMoreRowsTap());
            }
        }
        Ui.noteInfo(a, a.body, "Tap any reading to correct or delete it.",
            "Readings", "Tap any reading to correct or delete it. A corrected reading "
            + "is marked edited and stays marked.");
    }

    /** Three side-by-side stat tiles — no shared Ui helper exists for this shape yet
     *  (mirrors the prototype's .statgrid), so it is built inline the same way the photo
     *  row and other ad hoc layouts elsewhere in this file are. */
    /**
     * "SINCE LAST SESSION" — one compact strip at the top of Progress, before anything
     * else: how far length and girth moved between the newest reading and the one before
     * it.
     *
     * WHICH TWO READINGS is not decided here. {@link Meas#sincePair} decides it, and it
     * refuses any pair {@link Model.Reading#comparable} will not stand behind — a hard
     * at-rest reading against a soft one, a standardised one against an at-rest one, two
     * holds that reached visibly different actual vacuums. In every one of those cases
     * this strip prints WORDS and no number, because the difference between such a pair
     * is not a small change, it is not a change at all: printing "+0.4 cm" in teal would
     * be the screen asserting a measurement nobody took. It also never scans further back
     * for an older reading that would have agreed — see sincePair's own note.
     *
     * TEAL, because these are MEASURED values, and signed via Summary.cm so a bare
     * magnitude can never be read as growth it was not. Nothing here is amber: no pump is
     * under pressure on this screen.
     */
    void sinceLastStrip() {
        List<Model.Reading> readings = a.model.measLog.all;
        double[] delta = Meas.sinceDelta(readings);

        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));

        // One key for the whole strip: length and girth here are two halves of a single
        // "how far did I move" statement, and revealing one without the other would be an
        // odd half-answer rather than a smaller disclosure.
        final String sinceKey = a.measKey("since-last");
        LinearLayout headRow = new LinearLayout(a);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView head = Ui.microLabel(a, null, "Since last session", Ui.DIM);   // PR-7
        headRow.addView(head, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(headRow);

        // F3 FIX: `delta` can be non-null with BOTH slots Double.NaN — sincePair() found
        // a hold-conditions-comparable pair (its only test), but the two readings shared
        // no metric at all (e.g. a hard-state BPEL paired with a hard-state MSEG). That
        // used to fall into the "there is a number" branch below and print two bare "—"
        // cells with no explanation, even though this card's own doc promises words
        // whenever there is nothing to show. Meas.sinceWhy() now recognises this case too
        // and returns real words instead of "", so route it through the same words branch.
        boolean bothAbsent = delta != null && Double.isNaN(delta[0]) && Double.isNaN(delta[1]);
        if (delta == null || bothAbsent) {
            // No number, and the REAL reason — the same sentence the reading editor and
            // Compare use for this pair, so the app never explains a refusal two ways.
            TextView why = new TextView(a);
            why.setText(Meas.sinceWhy(readings));
            why.setTextColor(Ui.TEXT);
            why.setTextSize(Look.SP_CAPTION);
            why.setPadding(0, Ui.dp(a, 4), 0, 0);
            card.addView(why);
            Ui.group(card, "Since last session. " + Meas.sinceWhy(readings));
        } else {
            // delta[i] is Double.NaN when the pair sincePair() chose is not BOTH
            // measured-that-metric — e.g. sincePair() can pair a hard-state BPEL reading
            // with a hard-state MSEG one (Model.Reading#comparable's state fallback is
            // method-agnostic by design), and a length delta must not read the MSEG side's
            // unmeasured `len`. "—" outside shown(), matching every other absent-value stat
            // in this app: a dash is never a masked figure to reveal.
            String lenTxt = Double.isNaN(delta[0]) ? "—" : a.shown(Summary.cm(delta[0]), sinceKey);
            String girTxt = Double.isNaN(delta[1]) ? "—" : a.shown(Summary.cm(delta[1]), sinceKey);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(sinceCell("LENGTH", lenTxt),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(sinceCell("GIRTH", girTxt),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = Ui.dp(a, 4);
            card.addView(row, rowLp);
            // Said label-first, so each number arrives already knowing what it measures —
            // the same order statRow speaks in.
            Ui.group(card, "Since last session. Length " + lenTxt + ", girth " + girTxt + ".");
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 8);
        a.body.addView(card, lp);
    }

    /** One measured figure in the strip: the value with TABULAR figures (so +0.40 and
     *  −0.05 do not shuffle sideways beside each other) in the sans face - it only reports,
     *  see Look's type scale - and its label under it. */
    private LinearLayout sinceCell(String label, String value) {
        LinearLayout cell = Ui.col(a);
        TextView v = new TextView(a);
        v.setText(value);
        v.setTextColor(Ui.BODY);
        v.setTextSize(Look.SP_HEADING);
        Ui.tabular(v);
        TextView l = Ui.microLabel(a, null, label, Ui.DIM);   // PR-7
        cell.addView(v); cell.addView(l);
        return cell;
    }

    /** The 6W/3M/6M/1Y/All period selector: one segmented control (item 14), single choice,
     *  setting the window for the whole Trends tab and the readings drill-down. It was five
     *  separate lime ●/○ buttons; a segmented control says "one of these" by its shape, in a
     *  third of the height, and every segment is still a 48dp target (see Ui#segmented).
     *  The chosen period is TEXT on a raised segment rather than lime: choosing a window
     *  starts nothing and confirms nothing about the pump. */
    void periodRow(String[] labels, int[] periods) {
        int sel = -1;
        View.OnClickListener[] taps = new View.OnClickListener[periods.length];
        for (int i = 0; i < periods.length; i++) {
            if (a.mhPeriod == periods[i]) sel = i;
            taps[i] = new PeriodTap(periods[i]);
        }
        Ui.Segmented r = Ui.segmented(a, null, labels, null, sel, taps, null, false);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.bottomMargin = Ui.dp(a, Look.S3);
        a.body.addView(r.view, rLp);
    }

    /**
     * THE ROW OVER THE PLOT (item 14): a caption at left naming what is drawn - the metric
     * and the unit its axis is in, "Girth, cm" - and at right S10+'s chart line choice, a
     * small "Smoothed | Raw" segmented control. It sits on the chart's own edge because it
     * changes how the line is DRAWN, not what it measures. The chosen segment is TEXT like
     * every segmented control; it was lime when this was a pair of ●/○ buttons, and a way
     * of drawing a line is not an action.
     *
     * Persisted ({@link Model#chartSmoothed}), and shared by the S9 volume mini chart
     * drawn under this one — one control for both pictures, since they are two views of
     * the same reading history rather than two things a user would ever want smoothed
     * independently.
     */
    private void chartHead(ViewGroup parent, boolean lenSel) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView cap = new TextView(a);
        cap.setText(Say.trendAxisCaption(lenSel, Model.Fmt.lenUnit()));
        cap.setTextColor(Ui.DIM);
        cap.setTextSize(Look.SP_CAPTION);
        row.addView(cap, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.segmented(a, row, new String[]{ "Smoothed", "Raw" }, null,
                a.model.chartSmoothed ? 0 : 1,
                new View.OnClickListener[]{ new ChartSmoothTap(true), new ChartSmoothTap(false) },
                null, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, Look.S2);
        parent.addView(row, lp);
    }

    /**
     * THE GOAL KEY under the plot, one line per drawn goal: "· · · goal (MSEG), at the
     * fastest honest pace". The one-line-per-series key that used to sit here went with
     * item 14: the chips above the plot carry each series' own line sample (colour for the
     * method, dashes for post) and say it in words to a reader, so they are the key now. A
     * goal line has no chip, so it keeps its line here, directly under the chart it is on.
     * Asked of goalLines, the same place the chart gets its goal lines from, so the key and
     * the picture cannot disagree.
     */
    private void goalKey(ViewGroup parent, List<Model.Reading> oldestFirst) {
        List<SessionActivity.GoalLine> gls = a.goalLines(oldestFirst);
        if (gls.isEmpty()) return;
        StringBuilder key = new StringBuilder();
        StringBuilder said = new StringBuilder("Chart key: ");
        for (int i = 0; i < gls.size(); i++) {
            String ml = Model.Reading.methodLabel(gls.get(i).method);
            if (i > 0) { key.append('\n'); said.append("; "); }
            key.append("· · · goal (").append(ml)
               .append("), at the planned pace");
            said.append("the dotted ").append(ml)
                .append(" line slopes from your first ").append(ml)
                .append(" reading toward that goal at the planned pace");
        }
        TextView legend = new TextView(a);
        legend.setText(key.toString());
        legend.setContentDescription(said.toString());
        legend.setTextColor(Ui.DIM);
        legend.setTextSize(Look.SP_CAPTION);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, Look.S2);
        parent.addView(legend, lp);
    }

    private final class ChartSmoothTap implements View.OnClickListener {
        private final boolean smoothed;
        ChartSmoothTap(boolean s) { smoothed = s; }
        @Override public void onClick(View v) {
            a.model.chartSmoothed = smoothed;
            Store.save(a, a.model);
            a.rerenderCurrent();
        }
    }

    /**
     * PRUNE, THEN DEFAULT. Drop any selected series that no longer has data (the window or
     * the metric changed under it), and if nothing is left choose a default: the
     * standardized series present, or \u2014 failing that \u2014 every series with data. So the chart
     * always draws something the chips agree with, and never a chip for an empty line.
     */
    private void ensureSeriesSelection(List<int[]> avail) {
        java.util.Set<Integer> present = new java.util.HashSet<Integer>();
        for (int i = 0; i < avail.size(); i++)
            present.add(Integer.valueOf(a.seriesCode(avail.get(i)[0], avail.get(i)[1])));
        a.mhSeries.retainAll(present);
        // ...unless the person chose none, which is a choice and stays one (see
        // SessionActivity#mhSeriesNone).
        if (!Meas.seriesNeedsDefault(a.mhSeries.size(), a.mhSeriesNone)) return;
        // The default is the pure, pinned Meas#defaultSeries: the standardized series PLUS
        // this metric's own GOAL METHOD's series (so a goal line renders on open),
        // falling back to every series with data. So the chart is useful the instant it
        // opens and never blank when this metric holds readings. The method is derived from
        // the metric now rather than read from a stored preference: length goals are BPSSL,
        // girth goals are MSEG (and MSSG, which the "All" chip reaches in one tap).
        List<int[]> def = Meas.defaultSeries(avail, a.mhMetric == a.METRIC_LEN
            ? Model.Reading.METHOD_BPSSL : Model.Reading.METHOD_MSEG);
        for (int i = 0; i < def.size(); i++)
            a.mhSeries.add(Integer.valueOf(a.seriesCode(def.get(i)[0], def.get(i)[1])));
    }

    /**
     * THE SERIES CHIPS above the plot (item 14): a "SERIES" label with All / None as small
     * links at its right, then one chip per (method, phase) with data for the current
     * metric. Each chip carries a sample of its own line - the method's ink, dashed for
     * post - so the chips are the chart's key too, and the one-line-per-series legend that
     * used to sit under the plot is gone.
     *
     * WHY A MULTI-SELECT, NOT ONE MERGED LINE. Readings measured different ways are different
     * measurements of the same body; the gaps between them are protocol, not growth, and a
     * single line through all of them would show a change on the day someone switched
     * methods and call it progress. Each selected series is its own line - colour per
     * method, dashed for post - so every line means what it looks like it means.
     */
    private void seriesPicker(ViewGroup parent, List<Model.Reading> oldestFirst) {
        List<int[]> avail = a.availSeries(oldestFirst);
        ensureSeriesSelection(avail);
        // ensureSeriesSelection has just pruned the set to series with data, so this is the
        // number of chips below that are ticked.
        int ticked = a.mhSeries.size();

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.fieldLabel(a, null, "Series", null), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Each link acts only when it would change something; otherwise it is greyed and
        // inert rather than a tap that leaves the chart exactly as it was.
        head.addView(Ui.textLink(a, "All", Meas.seriesAllWouldAdd(avail.size(), ticked),
                new SeriesAllTap(true)));
        TextView none = Ui.textLink(a, "None", Meas.seriesNoneWouldClear(ticked),
                new SeriesAllTap(false));
        none.setPadding(none.getPaddingLeft(), 0, 0, 0);   // flush with the card's edge
        head.addView(none);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.topMargin = Ui.dp(a, Look.S1);
        parent.addView(head, headLp);

        // Chips draw 36dp inside 48dp targets: an 8dp gap across, and a -4dp line gap so the
        // drawn chips sit 8dp apart down as well.
        Ui.Flow chips = new Ui.Flow(a, Ui.dp(a, Look.S3), -Ui.dp(a, Look.S1));
        for (int i = 0; i < avail.size(); i++) {
            int method = avail.get(i)[0], phase = avail.get(i)[1];
            int code = a.seriesCode(method, phase);
            boolean on = a.mhSeries.contains(Integer.valueOf(code));
            chips.addView(Ui.toggleChip(a, Model.Reading.methodLabel(method),
                    Say.phaseTag(phase),
                    new Ui.LineSwatch(a, Look.seriesInk(method),
                                      phase == Model.Reading.PHASE_POST),
                    18, 6, on, Say.seriesSaid(method, phase), new SeriesToggleTap(code)));
        }
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Up under the label row by the chip's own 6dp of empty target, so the drawn chips
        // sit where the mock puts them rather than a finger's margin lower.
        chipsLp.topMargin = -Ui.dp(a, 6);
        parent.addView(chips, chipsLp);

        if (a.mhSeries.size() > 6) {
            // A plain caption, not amber: amber (COMMANDED) means the pump may be under
            // pressure, and this is only a note about the picture. Item 14's mock drew it
            // amber; the colour rule wins over the mock.
            Ui.noteInfo(a, parent, "That is a lot of lines at once.",
                "Series", "That is a lot of lines at once — the plot still draws "
                    + "them all, but fewer series read more clearly.");
        }
    }

    private final class SeriesToggleTap implements View.OnClickListener {
        private final int code;
        SeriesToggleTap(int c) { code = c; }
        @Override public void onClick(View v) {
            Integer key = Integer.valueOf(code);
            if (a.mhSeries.contains(key)) a.mhSeries.remove(key);
            else                        a.mhSeries.add(key);
            // Unticking the last chip is choosing none, not asking for the defaults back.
            a.mhSeriesNone = a.mhSeries.isEmpty();
            a.rerenderCurrent();
        }
    }

    private final class SeriesAllTap implements View.OnClickListener {
        private final boolean all;
        SeriesAllTap(boolean own) { all = own; }
        @Override public void onClick(View v) {
            a.mhSeries.clear();
            a.mhSeriesNone = !all;
            if (all) {
                long now = System.currentTimeMillis();
                List<Model.Reading> w = Meas.windowFor(a.model.measLog, a.mhPeriod, now);
                List<Model.Reading> oldestFirst = new ArrayList<Model.Reading>(w);
                Collections.reverse(oldestFirst);
                List<int[]> avail = a.availSeries(oldestFirst);
                for (int i = 0; i < avail.size(); i++)
                    a.mhSeries.add(Integer.valueOf(a.seriesCode(avail.get(i)[0], avail.get(i)[1])));
            }
            a.rerenderCurrent();
        }
    }

    /** One readings-list row: date, values with the delta from the previous (older) entry,
     *  tags, and the hold badge — showing hold TIME next to the hold pressure (defect #10:
     *  a row that only showed pressure could not explain why two same-pressure rows were
     *  flagged as not comparable).
     *
     *  BOTH the values and the delta are gated on {@link Model.Reading#measuredLength} —
     *  a metric this row's own method never measured prints "—", never its primitive
     *  double's 0.0 default; and the length delta (this row is a LENGTH-only figure, as
     *  it always was) is only shown when `e` AND `prev` both actually measured length —
     *  `prev` is simply the next-older row in the list, of whatever method IT happens to
     *  be, so a BPSSL row sitting under an MSEG one must not diff a real length against
     *  the girth row's unmeasured zero. */
    /** One method chip, in the shape periodRow and metricRow already use. */
    /**
     * ONE PROTOCOL AT A TIME, AS THE APP'S OWN CONTROL (item 20): "All" and each method in
     * view, as a segmented control where there were ●/○ buttons. Up to four fit the width;
     * more take the compact form, each segment as wide as its word, and scroll sideways
     * rather than squeeze a code into a sliver.
     */
    private void methodFilter(java.util.List<Integer> present) {
        int n = present.size() + 1;
        String[] labels = new String[n];
        String[] said = new String[n];
        View.OnClickListener[] taps = new View.OnClickListener[n];
        labels[0] = "All";
        said[0] = "every method";
        taps[0] = new MethodFilterTap(-1);
        int chosen = 0;
        for (int i = 0; i < present.size(); i++) {
            int m = present.get(i).intValue();
            labels[i + 1] = Model.Reading.methodLabel(m);
            said[i + 1] = Model.Reading.methodMeaning(m);
            taps[i + 1] = new MethodFilterTap(m);
            if (a.mhMethodFilter == m) chosen = i + 1;
        }
        if (n <= 4) {
            Ui.segmented(a, a.body, labels, said, chosen, taps);
            return;
        }
        android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(a);
        sc.setHorizontalScrollBarEnabled(false);
        Ui.Segmented seg = Ui.segmented(a, null, labels, said, chosen, taps, null, true);
        sc.addView(seg.view);
        a.body.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** A segment of the filter. It REDRAWS THE READINGS - it used to call
     *  showSessionHistory(), which left this screen for Progress's tabs on every tap. */
    private final class MethodFilterTap implements View.OnClickListener {
        private final int method;
        MethodFilterTap(int m) { method = m; }
        @Override public void onClick(View v) {
            if (a.mhMethodFilter == method) return;
            a.mhMethodFilter = method;
            a.mhPagesLoaded = 1;      // a new filter starts at the first page, not mid-list
            renderMeasHist();
        }
    }

    /**
     * ONE READING, AS A CARD (item 20): the two figures (and the change against the reading
     * below it, only for a pair that compares) over what kind of reading it is - "at rest ·
     * MSEG · photo", "standardised · measured at −6.7 inHg". The day is the label above
     * the group, so the card no longer repeats it. Say#measFigures and #measTags are the
     * same words the old one-line row printed.
     */
    private void readingRow(Model.Reading e, Model.Reading prev) {
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        card.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, Look.S4), Ui.dp(a, Look.S5), Ui.dp(a, Look.S4));
        card.setMinimumHeight(Ui.dp(a, 48));
        String figures = Say.measFigures(e, prev), tags = Say.measTags(e);
        TextView f = new TextView(a);
        f.setText(figures);
        f.setTextColor(Ui.TEXT);
        f.setTextSize(Look.SP_BODY);
        Ui.tabular(Ui.medium(f));
        card.addView(f);
        TextView t = new TextView(a);
        t.setText(tags);
        t.setTextColor(Ui.DIM);
        t.setTextSize(Look.SP_LABEL);
        card.addView(t);
        Ui.group(card, rowDateLabel(e.ts) + ", " + figures + ", " + tags
            + ". Opens the reading to correct or delete it.");
        card.setClickable(true);
        card.setOnTouchListener(new Ui.Press());
        card.setOnClickListener(a.new OpenMeasEditTap(e.id));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, Look.S2);
        a.body.addView(card, lp);
    }

    /** Appends a two-digit year once the period is wide enough that same-named days from
     *  different years could appear together — mirrors the prototype's dl2 (MPER === 0 ||
     *  MPER > 182). */
    private String rowDateLabel(long ts) {
        String base = a.dayLabel(ts);
        if (a.mhPeriod == Meas.PERIOD_ALL || a.mhPeriod > Meas.PERIOD_6M) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(ts);
            int yy = cal.get(Calendar.YEAR) % 100;
            base += " '" + (yy < 10 ? "0" : "") + yy;
        }
        return base;
    }

    private final class PeriodTap implements View.OnClickListener {
        private final int period;
        PeriodTap(int p) { period = p; }
        @Override public void onClick(View v) {
            a.mhPeriod = period;
            a.mhPagesLoaded = 1;         // a new period starts back at page 1 — a stale
                                       // page count from the last period is not meaningful
            // The pills now head TWO screens — Progress itself and the readings
            // drill-down. Re-render the one that is actually on screen; re-rendering the
            // other would silently navigate the user off the page they tapped on.
            if (a.currentScreen == Nav.SCR_MEAS_HIST) renderMeasHist();
            else showSessionHistory();
        }
    }

    private final class ShowMoreRowsTap implements View.OnClickListener {
        @Override public void onClick(View v) { a.mhPagesLoaded++; renderMeasHist(); }
    }

    /**
     * THE TREND, AS ONE CARD — "Length · girth", the two headline numbers, the plot and
     * its key, on a single lifted surface (the mockup's `.card.pop` block). It is built
     * here rather than inline on either screen because BOTH the Progress page and the
     * readings drill-down draw it, from the same period window, and two copies of a chart
     * are two chances for them to disagree about what the period means.
     *
     * `oldestFirst` must be the window Meas#windowFor gave for `mhPeriod`, and `delta` the
     * matching Meas#deltaFor — the headline numbers and the plotted points are then the
     * same data by construction, not by coincidence.
     */
    /**
     * A4 - SAYS SO WHEN A TREND IS DRAWN FROM MORE THAN ONE CYLINDER.
     *
     * A measurement is comparable with another only when both were taken in the same
     * cylinder: a different bore changes what a girth reading means, and a different length
     * changes how much tissue is inside it. A line through both is a line through two
     * different measurements of two different things.
     *
     * IT SAYS SO RATHER THAN SPLITTING THE LINE. Splitting would be a stronger claim than the
     * data supports - the app does not know how the two cylinders compare, only that they
     * differ - and would quietly halve a trend somebody has been building for months. Naming
     * it lets the reader decide what the step in the line is.
     *
     * READINGS WITH NO CYLINDER RECORDED ARE NOT COUNTED AS A MIX. Every reading saved before
     * this existed carries -1, and "not recorded" is not evidence of anything; treating it as
     * a difference would put this warning on every existing trend in the app.
     */
    private void cylinderMixNote(LinearLayout into, List<Model.Reading> readings) {
        if (readings == null || readings.size() < 2) return;
        // COMPARED BY IDENTITY, NOT BY POSITION. The index is where the cylinder sat in the
        // rack when the reading was taken; deleting a cylinder shifts every later index, so
        // two readings from DIFFERENT tubes could come to share one (hiding a real mix) and
        // two from the SAME tube could come to differ (inventing one). The id says which tube
        // it actually was, and keeps saying it after the tube is gone.
        //
        // An empty id is still "not recorded" and still not a difference: readings saved
        // before ids existed are given one by Model#linkReadingCylinders on load wherever
        // their index resolved, so what reaches here empty genuinely has no cylinder behind it.
        String first = "";
        boolean mixed = false;
        for (int i = 0; i < readings.size(); i++) {
            String c = readings.get(i).cylinderId;
            if (c == null || c.length() == 0) continue;
            if (first.length() == 0) first = c;
            else if (!c.equals(first)) { mixed = true; break; }
        }
        if (!mixed) return;
        Ui.noteInfo(a, into, "This trend spans more than one cylinder.",
            "Two cylinders in one line", "Some of these readings were taken in a different "
            + "cylinder from the others. A girth reading depends on the bore it was taken in "
            + "and a length reading on how much of the tissue the cylinder holds, so a step "
            + "in this line where the cylinder changed may be the cylinder rather than "
            + "you.\n\nThe line is not split, because the app does not know how your "
            + "cylinders compare — only that they are not the same one. Which reading came "
            + "from which is on each reading in the list below.");
    }

    /** C8 - a change's label with the method it is within ("LEN · Std"), or the bare label
     *  when there is no change to name. */
    private static String pairLabel(String label, Model.Reading[] pair) {
        return pair == null ? label
            : label + " · " + methodName(pair[0].method);
    }

    /** PR-13: the default method by its name, "Standardised", on this screen's labels. */
    private static String methodName(int method) {
        return method == Model.Reading.METHOD_STANDARDIZED ? "Standardised"
             : Model.Reading.methodLabel(method);
    }

    /** PR-1: which method a tile's change is within, for a reader - ", BPSSL: stretched,
     *  pressed to the bone". Empty when the window has no pair. */
    private static String methodSaid(Model.Reading[] pair) {
        if (pair == null) return "";
        return ", " + methodName(pair[0].method) + ", "
             + Model.Reading.methodMeaning(pair[0].method);
    }

    /** "Log a reading with a photo" - the next step on every empty photo surface. */
    static final String LOG_WITH_PHOTO = "Log a reading with a photo";

    /**
     * PR-1: TIME UNDER PRESSURE AS WORDS - "45 min", "2.5 h". "45:00" under a tile called
     * HRS read as forty-five hours. Whole minutes under an hour, hours to one place over.
     */
    static String timeWords(long sec) {
        if (sec <= 0) return "0 min";
        if (sec < 3600) return Math.max(1, Math.round(sec / 60.0)) + " min";
        double h = Math.round(sec / 360.0) / 10.0;
        return (h == Math.floor(h) ? String.valueOf((long) h) : String.valueOf(h)) + " h";
    }

    /**
     * PR-3: the cycles a session delivered against its plan - "8 of 14 cycles", the count
     * the Summary reports, where the row said "5 of 5 presets" (a preset is a wire slot,
     * and every one was armed even on a run stopped at twelve minutes). Nothing when the
     * record has no cycle count.
     */
    private static String cyclesPart(Model.Sess e) {
        if (e.cyclesDone < 0 || e.cyclesPlanned <= 0) return "";
        if (e.tupPausedSec > 0 && e.cyclesDone > e.cyclesPlanned)
            return e.cyclesDone + " cycles  ·  ";
        return e.cyclesDone + " of " + e.cyclesPlanned + " cycles  ·  ";
    }

    /** A section head with its explanation behind a ⓘ at the right end of the row - the
     *  section form of cardGroup's ⓘ title row. */
    private void headWithInfo(String label, int colour, String infoTitle, String full) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        Ui.sectionHead(a, row, label, colour);
        row.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.infoButton(a, row, infoTitle, infoTitle, full);
        a.body.addView(row);
    }

    void trendCard(List<Model.Reading> oldestFirst, double[] delta, long now) {
        // A RESTING CARD LIKE EVERY OTHER (item 14): SURFACE, the card padding, a heading-
        // size title. It was the live SURFACEHI with 11dp padding and a caption-size title,
        // and the segmented controls inside it are SURFACEHI tracks: on a SURFACEHI card
        // they would have had no edge at all.
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, Look.S5), Ui.dp(a, Look.S5),
                        Ui.dp(a, Look.S5));

        TextView title = new TextView(a);
        title.setText("Length · girth");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(Look.SP_HEADING);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(title);

        // Lead with the number: the current value and its change over the window, read
        // first, before the shape. delta[] is the same length/girth change the chart's
        // description states, so the headline and the picture cannot disagree.
        // THE NEWEST COLD READING **THAT ACTUALLY MEASURED THIS METRIC**, not the last row
        // in the window of ANY method (the bug: a window's overall newest cold reading
        // might be a girth-only method, which must not stand in for "current length").
        // Each metric looks up its OWN newest cold reading independently; null (no cold
        // reading in the window ever measured it) becomes Double.NaN, which trendLead
        // renders as "—" rather than a fabricated 0.0.
        Model.Reading lenLatest = Meas.newestColdMeasuring(oldestFirst, true);
        Model.Reading girLatest = Meas.newestColdMeasuring(oldestFirst, false);
        // C8 - THE HEADLINE NAMES WHAT IT COMPARES: the method beside the figure is the one on
        // both ends (Meas#periodPair keeps the change within one comparability rule, and its
        // newer end is this same newest reading), and the older end's date is said.
        Model.Reading[] lenPair = Meas.periodPair(a.model.measLog, a.mhPeriod, now, true);
        Model.Reading[] girPair = Meas.periodPair(a.model.measLog, a.mhPeriod, now, false);
        a.trendLead(card, "LENGTH", lenLatest != null ? lenLatest.len : Double.NaN, delta[0],
            lenLatest == null ? null : Model.Reading.methodLabel(lenLatest.method),
            lenPair == null ? null : "since " + a.dayLabel(lenPair[1].ts));
        a.trendLead(card, "GIRTH",  girLatest != null ? girLatest.gir : Double.NaN, delta[1],
            girLatest == null ? null : Model.Reading.methodLabel(girLatest.method),
            girPair == null ? null : "since " + a.dayLabel(girPair[1].ts));

        // WHICH SERIES THE PLOT DRAWS. Both headline numbers stay above — they are numbers,
        // not lines, and they share no axis to be confused about — but the PICTURE is one
        // metric at a time, and within that metric a MULTI-SELECT of (method, phase) lines.
        a.metricRow(card);
        seriesPicker(card, oldestFirst);

        boolean lenSel = a.mhMetric == a.METRIC_LEN;
        // What the plot draws, named, and S10+'s smoothed/raw choice — one control for THIS
        // chart and the S9 volume mini chart below it (see #chartHead's own doc).
        chartHead(card, lenSel);
        List<SessionActivity.SeriesSpec> selected = a.buildSelectedSeries(oldestFirst);
        int totalPts = 0;
        for (int i = 0; i < selected.size(); i++) totalPts += selected.get(i).pts.size();

        cylinderMixNote(a.body, oldestFirst);
        SessionActivity.TrendChart chart = a.new TrendChart(a, false);
        chart.setMetric(a.mhMetric);
        chart.setSeries(selected, now);
        chart.setSmoothed(a.model.chartSmoothed);
        // A GOAL LINE PER PLOTTED SERIES. Each of the three goals rides on the chart only
        // when a series of ITS OWN method is selected — asked in one place (goalLines) so
        // the chart, the legend and the caption can never disagree about which lines are
        // there. The old rule drew at most one line, gated on a stored goalMethod; a chart
        // showing BPSSL and MSEG at once now shows both targets.
        chart.setGoals(a.goalLines(oldestFirst));
        // A Canvas has no text. The description carries the same facts the plot does — how
        // many series, how many points, and over what span — read from the SAME window the
        // chart is drawn from, so the two cannot describe different data.
        double leadDelta = lenSel ? delta[0] : delta[1];
        chart.setContentDescription((lenSel ? "Length" : "Girth") + " trend chart, "
            + selected.size() + " series, " + totalPts + " readings. "
            + (Double.isNaN(leadDelta) ? "no comparable change" : Model.Fmt.lenDelta(leadDelta))
            + " over this period.");
        /* INSIDE THE CARD, ON THE CARD (bug B2).
         *
         * This used to be a full-bleed band: negative side margins of the card's and the
         * body's padding, a darker ground and a hairline above and below, to win back the
         * 50dp of plot width the two paddings cost. On the phone it read as what it was - a
         * chart that had escaped its card, running to both screen edges with its own low
         * label cut by the line - and the owner flagged it. The plot now sits in the card's
         * padding like every other picture on this page, left-aligned with the volume chart
         * under it; TrendChart gives its labels a gutter of their own, so the width it gives
         * up buys a legible axis rather than being lost. */
        // ONLY WHEN THERE IS A PICTURE TO DRAW. An empty selection already gets the note
        // below; an empty plot area costs 150dp saying nothing.
        if (totalPts > 0) {
            LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 150));
            chartLp.topMargin = Ui.dp(a, Look.S1);
            card.addView(chart, chartLp);
            goalKey(card, oldestFirst);
        }

        // S9 — THE VOLUME MINI CHART, a SEPARATE picture under the measurement chart
        // (round6-options.html S9 B: "separate volume mini chart... aligned X (no
        // overlay)"), never overlaid on the plot above. X-aligned to the SAME span that
        // chart just drew (#seriesTimeRange of the exact `selected` series it used) —
        // volume's own population is a different, usually narrower, subset of readings
        // (Meas#volumeCapable), and would otherwise stretch its own dates to fill the
        // view, breaking the alignment a reader needs to line a length spike up with a
        // volume one. Skipped entirely when the chart above has nothing to align to, or
        // when this window holds no volume-capable reading at all.
        long[] volRange = a.seriesTimeRange(selected);
        List<SessionActivity.SeriesSpec> volSeries = a.volumeSeries(oldestFirst);
        int volPts = 0;
        for (int i = 0; i < volSeries.size(); i++) volPts += volSeries.get(i).pts.size();
        if (volRange != null && volPts > 0) {
            SessionActivity.TrendChart volChart = a.new TrendChart(a, false);
            volChart.setMetric(a.METRIC_VOL);
            volChart.setSeries(volSeries, now);
            volChart.setSmoothed(a.model.chartSmoothed);
            volChart.setTimeWindow(volRange[0], volRange[1]);
            // Fewer gridlines than the measurement chart above (item 14): this chart is
            // only Look.VOL_CHART_HEIGHT_DP tall, 60% of that chart's 150dp.
            volChart.setAxisMaxLines(Look.VOL_CHART_AXIS_MAX_LINES);
            // No goals: a goal is stated in length or girth, never cm3 (Model#goalForMethod
            // has no volume method to look up), so this chart draws none — TrendChart's
            // own `goals` field defaults to empty and is simply never set here.

            // LEAD WITH THE NUMBER here too (the same rule trendLead() states for length
            // and girth above): the newest volume-capable reading IN THIS WINDOW, oldest-
            // first so the last entry is the newest — read once and stated in both the
            // caption and the description, so a sighted reader and a screen reader are
            // never told two different numbers. MASKED the same way trendLead()'s own
            // headline figures are: this is a body measurement derived from length and
            // girth, the identical category the privacy blur already covers everywhere
            // else on this card, and "the reader is hidden too" (trendLead's own rule) —
            // a screen reader must not learn the number a sighted user's blur is covering.
            List<Model.Reading> volCapable = Meas.volumeCapable(oldestFirst);
            Model.Reading newestVol = volCapable.get(volCapable.size() - 1);
            double newestVolCm3 = Meas.estimatedVolumeCm3(newestVol.len, newestVol.gir);
            String volKey = a.measKey("volume");
            boolean volHidden = a.blurred(volKey);
            String newestVolStr = a.shown(Model.Fmt.vol(newestVolCm3), volKey);

            // ONE GUTTER FOR BOTH. The two views are the same width (both fill this card),
            // so the same left gutter gives both plots the same left edge, and the span
            // they share lands on the same x in each. The wider of the two charts' needs
            // wins, so neither chart's labels are cut to fit the other's.
            float gutter = Math.max(chart.neededGutter(), volChart.neededGutter());
            chart.setGutter(gutter);
            volChart.setGutter(gutter);

            volChart.setContentDescription("Estimated volume trend chart, " + volPts
                + " readings, cylindrical estimate from length and girth. "
                + (volHidden ? "Latest volume hidden — reveal to read it."
                             : "Latest: " + newestVolStr + "."));
            LinearLayout.LayoutParams volLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, Look.VOL_CHART_HEIGHT_DP));
            // A step of space above it: with both charts' labels now in one gutter, the
            // measurement chart's low label and this chart's high one would otherwise
            // stack into one column of four numbers that reads as a single axis.
            volLp.topMargin = Ui.dp(a, Look.S3); volLp.bottomMargin = Ui.dp(a, 6);
            card.addView(volChart, volLp);

            TextView volLbl = new TextView(a);
            volLbl.setText("Estimated volume (cylindrical) — latest " + newestVolStr);
            volLbl.setTextColor(Ui.DIM);
            volLbl.setTextSize(Look.SP_MICRO);
            card.addView(volLbl);
        }

        // AN EMPTY SELECTION SAYS SO. A blank plot with no sentence under it looks like a
        // bug; it is not — it is a selection this window holds too few readings for, and
        // the fix is to select more series, log one, or widen the period.
        if (totalPts < 2) {
            Ui.note(a, card, selected.isEmpty()
                ? "No series ticked. Tick one above to draw it."
                : totalPts == 0
                ? "No readings in the selected series for this period. Select more above, "
                  + "or a longer period."
                : "One reading in the selected series — a trend needs two.");
        }

        // POST VS PRE - the mean same-day difference, for the FIRST selected method that
        // has both a pre and a post reading. Cheap: one method, not a caption per line.
        // null (no same-day pair) means the line is ABSENT rather than a printed zero.
        int pctMethod = -1;
        Double pct = null;
        java.util.Set<Integer> seenMethods = new java.util.HashSet<Integer>();
        for (int i = 0; i < selected.size() && pct == null; i++) {
            int m = selected.get(i).method;
            if (!seenMethods.add(Integer.valueOf(m))) continue;
            Double p = Meas.prePostPct(oldestFirst, m);
            if (p != null) { pct = p; pctMethod = m; }
        }
        if (pct != null) {
            TextView pp = new TextView(a);
            String amount = Say.prePostPctText(pct.doubleValue());
            pp.setText("After vs before, same day (" + Model.Reading.methodLabel(pctMethod) + "): "
                + amount);
            pp.setContentDescription("On the days with both, after-session length in "
                + Model.Reading.methodLabel(pctMethod) + " averaged " + amount
                + " compared with before.");
            pp.setTextColor(Ui.DIM);
            pp.setTextSize(Look.SP_MICRO);
            card.addView(pp);
        }

        // WHERE THE GOAL LINE LANDS, and how far there is to go — for the metric on the
        // plot only, because that is the line whose slope the sentence is explaining.
        // MASKS (final review pass — pre-existing leak, out of Task 10's and Task 12's own
        // scope, closed here while the goalInstrumentCard mask above is fixing the exact
        // same class of defect): "N cm to go on BPSSL" is a distance to the goal computed
        // straight off the user's own readings, the same body-measurement category
        // trendLead()'s headline figures already mask. One key for the whole caption — it
        // can hold one line per drawn goal, and they are one statement about this window,
        // not several independently-revealable ones.
        String toGo = goalPaceLine(oldestFirst);
        if (toGo != null) {
            TextView g = new TextView(a);
            g.setText(a.shown(toGo, a.measKey("goal-pace")));
            g.setTextColor(lenSel ? Ui.ACCENT : Ui.BODY);
            g.setTextSize(Look.SP_MICRO);
            card.addView(g);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 10);
        a.body.addView(card, lp);
    }

    /**
     * THE CAPTION UNDER THE PLOT, one line per drawn goal: "N cm to go on BPSSL · on pace:
     * <date>" while there is still distance, or "BPSSL goal reached" when there is not.
     *
     * The date is {@link Model#goalReachedAt} against the SAME anchor and the SAME slope
     * {@link TrendChart} draws the line from, so the sentence and the picture cannot
     * describe two different trajectories. It says "at the capped rate", explicitly,
     * because the cap is a bound on what the app will DRAW and never a prediction about a
     * body.
     *
     * Null when no goal is drawn on this chart at all — an absent line, not a printed zero.
     */
    private String goalPaceLine(List<Model.Reading> oldestFirst) {
        List<SessionActivity.GoalLine> gls = a.goalLines(oldestFirst);
        if (gls.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gls.size(); i++) {
            SessionActivity.GoalLine g = gls.get(i);
            if (sb.length() > 0) sb.append("\n");
            String ml = Model.Reading.methodLabel(g.method);
            // "Where am I now" is asked of THIS method's own newest reading, not of the
            // window's newest reading of any method — the same discipline the anchor and
            // the cap follow. goalLatestCm already scans only readings of g.method, so it
            // can fail to find one (nowV <= 0) only in the degenerate case where the sole
            // qualifying reading is the anchor itself with a non-positive value. THE FIX:
            // the old fallback read metricOf() off the window's newest COLD reading of ANY
            // method — which could be a different method entirely, printing a spurious 0.0
            // (or someone else's number) as "where you are now" on THIS goal's line. The
            // anchor is guaranteed to be of g.method and already resolved (goalLines()
            // required a non-null one to build this GoalLine at all), so it is the correct,
            // always-same-method fallback rather than a second, ungated method lookup.
            double nowV = Meas.goalLatestCm(Meas.reversed(oldestFirst), g.method,
                                            a.mhMetric == a.METRIC_LEN);
            if (nowV <= 0) nowV = g.anchorCm;
            double togo = g.goalCm - nowV;
            if (togo <= 0) { sb.append(ml).append(" goal reached"); continue; }
            sb.append(Model.Fmt.len(togo)).append(" to go on ").append(ml);
            long at = Model.goalReachedAt(g.anchorTs, g.anchorCm, g.goalCm, g.ratePerYear);
            if (at > 0) sb.append("  ·  on pace: ").append(a.dayLabel(at))
                          .append(" at the planned pace");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * THE ONE-TIME "GOAL REACHED" CARD (S6+, round6-options.html's amendment — "Shown
     * once on the measurement log that crossed the goal, then archived. No confetti.").
     *
     * One card per goal actually reached and not yet shown ({@link Meas#reachedGoals}
     * against {@link Model#goalReachedShown}), drawn directly above the M1 instrument
     * the mock places it beside. Recomputed fresh every render — see {@link
     * Meas#reachedGoals}'s own doc for why "recompute and diff against shown" is used
     * instead of catching the transition at one specific measurement-log write call
     * site — and marked SHOWN the moment it draws, the exact same-render "mark it seen
     * as it is drawn" shape {@link #milestoneCard} already uses (see that method's own
     * doc for why: a card the user has to dismiss to be rid of reappears if they
     * navigate away before doing so, which for a one-time moment is worse than showing
     * it once and being done).
     *
     * NO CONFETTI, literally — the mock's own words. This is a quiet card in the normal
     * Progress flow, not a blocking dialog or a celebration that interrupts anything;
     * logging a measurement never pauses to show it, it simply appears the next time
     * Progress is opened after the goal was crossed.
     */
    void goalReachedCard() {
        List<Meas.GoalReached> reached = Meas.reachedGoals(
            Meas.reversed(a.model.measLog.all), a.model);
        if (reached.isEmpty()) return;
        boolean any = false;
        for (int i = 0; i < reached.size(); i++) {
            Meas.GoalReached g = reached.get(i);
            String key = Model.goalReachedKey(g.method, g.goalCm);
            if (a.model.goalReachedShown.contains(key)) continue;
            any = true;

            // A PLAIN CARD, the surface colour, elevated. It wore a lime left edge like
            // milestoneCard(); coloured edges are now kept for warnings and faults only
            // (Look#earnsSpine), and a goal reached is neither. The HEADING text below is
            // not lime either — see its own note a few lines down: the locked mock (round6-options.html:69)
            // paints "GOAL REACHED" itself in `--good` green, not lime, and Look.TREND_GOOD
            // is what carries that without reusing Look.SAFE's pump-safety meaning.
            LinearLayout h = new LinearLayout(a);
            h.setOrientation(LinearLayout.HORIZONTAL);
            h.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));
            Ui.lift(a, h, Ui.ELEV_CARD);
            h.setPadding(Ui.dp(a, 13), Ui.dp(a, 12), Ui.dp(a, 13), Ui.dp(a, 12));

            LinearLayout card = Ui.col(a);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            h.addView(card, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView head = new TextView(a);
            head.setText("GOAL REACHED");
            // GREEN, matching the locked mock's own `class="g"` (`var(--good)`) exactly —
            // Look.TREND_GOOD, not Ui.ACCENT/lime and not Ui.GOOD/Look.SAFE. See
            // Look.TREND_GOOD's own doc: same hex as SAFE, deliberately a separate
            // constant so "green means telemetry-confirmed vented" stays true everywhere
            // SAFE is read, while this card (a body-measurement verdict, not a pump one)
            // gets its own name for the identical colour.
            head.setTextColor(Look.TREND_GOOD);
            head.setTextSize(Look.SP_HEADING);
            head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
            head.setGravity(Gravity.CENTER_HORIZONTAL);
            card.addView(head);

            int months = Model.elapsedMonths(g.anchorTs, g.latestTs);
            String monthsWord = months + (months == 1 ? " month" : " months");
            // THE GOAL VALUE MASKS under the SAME key the M1 instrument uses for this
            // method (goalInstrumentCard's own goalKey shape) — it is a body-measurement
            // figure exactly like every number that card prints, and "GOAL REACHED"
            // being good news is no reason for this one card to break the privacy blur
            // every other measurement-derived figure in the app already honours.
            // "N months" is a DURATION, not a body measurement, and stays unmasked the
            // same way NEXT MEASUREMENT's own cadence text does a few methods down.
            String reachedKey = a.measKey("goal:" + g.method);
            String valueText = a.blurred(reachedKey) ? a.MASK : Model.Fmt.len(g.goalCm);
            TextView note = new TextView(a);
            note.setText(Model.Reading.methodLabel(g.method) + " " + valueText
                + "  ·  " + monthsWord);
            note.setTextColor(Ui.DIM);
            note.setTextSize(Look.SP_CAPTION);
            note.setGravity(Gravity.CENTER_HORIZONTAL);
            note.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 6));
            card.addView(note);

            // "Set next goal ›" — the exact chip the mock draws, opening the same door
            // the girth-goals nudge on the M1 instrument already uses (Tap.SETTINGS).
            Button chip = Ui.flat(a, card, "Set next goal ›");
            chip.setTextColor(Ui.ACCENT);
            chip.setOnClickListener(a.new Tap(SessionActivity.Tap.SETTINGS));

            Ui.group(h, "Goal reached. " + Model.Reading.methodLabel(g.method) + " "
                + valueText + ", " + monthsWord + " after you started toward it.");

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(a, 10);
            a.body.addView(h, lp);

            a.model.goalReachedShown.add(key);
        }
        // Persist immediately, the same "the card has now been on screen" reasoning
        // milestoneCard() states for its own save — a kill before the next save would
        // otherwise show it again.
        if (any) Store.save(a, a.model);
    }

    /**
     * THE TREND BADGE'S ECHO on the log-confirmation snack (S6+ — "the same state is
     * echoed on the log confirmation"). Reads the SAME primary goal the M1 instrument
     * headlines ({@link #primaryGoalLine}), asked over the WHOLE log rather than a
     * period window — a snack fired the instant after a save must not depend on which
     * date-range pill Progress happens to have selected. "" when no goal-bearing method
     * resolves a line at all (nothing to say), so every caller can append it
     * unconditionally without an extra null check.
     */
    String trendSnackSuffix() {
        List<Model.Reading> oldestFirst = Meas.reversed(a.model.measLog.all);
        SessionActivity.GoalLine g = a.primaryGoalLine(oldestFirst);
        if (g == null) return "";
        // The method's newest non-post reading: goalAnchorOfMethod's own "first match in
        // the order it is given" scan, asked over the log's NEWEST-first order (the
        // same reuse-in-reverse Meas#reachedGoals already documents) rather than the
        // oldest-first `oldestFirst` g itself was anchored from.
        Model.Reading latest = Meas.goalAnchorOfMethod(a.model.measLog.all, g.method);
        if (latest == null) return "";
        boolean isLen = Model.goalMethodIsLength(g.method);
        // S10+: the ACTUAL side of the comparison is resolved through the forecast-source
        // setting (see #projectionActualCm's own doc) — never latest.len/gir directly.
        // hist is the SAME cold, oldest-first, this-method-only population `latest` was
        // read from, built fresh here because trendSnackSuffix (unlike goalInstrumentCard)
        // has no `hist` of its own already in scope.
        List<Model.Reading> hist = Meas.preOf(Meas.ofMethod(oldestFirst, g.method));
        double actualV = projectionActualCm(hist, isLen);
        double projectedNowCm = Model.goalProjectionCmAt(latest.ts, g.anchorTs, g.anchorCm,
                                                          g.goalCm, g.ratePerYear);
        int trend = Model.goalTrendState(actualV, projectedNowCm, Model.GOAL_TREND_NOISE_CM);
        String goalKey = a.measKey("goal:" + g.method);
        return "  ·  " + (a.blurred(goalKey) ? a.MASK : Say.trendLabel(trend));
    }

    /**
     * THE "ACTUAL" SIDE of every M1-projection comparison (S10+, {@link
     * Model#goalProjectionSmoothed}) — the newest reading in `hist`'s own value, resolved
     * through the forecast-source setting: the reading itself, un-touched, when the
     * setting is raw; that same reading's {@link Meas#smoothed} rolling-average value when
     * it is smoothed (the default). `hist` must be one method's COLD readings, oldest-
     * first ({@code Meas.preOf(Meas.ofMethod(oldestFirst, method))}) — the exact
     * population both call sites ({@link #trendSnackSuffix}, {@link #goalInstrumentCard})
     * already build theirs from, so this never re-derives a different history to average
     * over than the one the caller's own `latest` reading came from.
     *
     * Never touches {@link Model#goalProgressFraction}'s own baseline/current pair — the
     * progress bar and its percentage answer "how far along am I", a plain fact about the
     * literal latest measurement, not "what does the projection say", so they stay on the
     * raw reading regardless of this setting. Only the trend badge and PACE ACTUAL — the
     * two figures the mock's own row names ("Goal projection uses") — read this.
     *
     * Empty `hist` returns 0 (the same "nothing to compute yet" zero this file's other
     * degenerate goal readouts return); both current call sites already guard against an
     * empty history before reaching here.
     */
    private double projectionActualCm(List<Model.Reading> hist, boolean isLength) {
        if (hist == null || hist.isEmpty()) return 0;
        if (!a.model.goalProjectionSmoothed) {
            Model.Reading last = hist.get(hist.size() - 1);
            return isLength ? last.len : last.gir;
        }
        List<double[]> sm = Meas.smoothedWithinRuns(hist, Meas.SMOOTH_WINDOW);
        double[] last = sm.get(sm.size() - 1);
        return isLength ? last[1] : last[2];
    }

    /**
     * THE M1 GOAL INSTRUMENT CARD (Stage B Task 12, dist/final-design.html section
     * 4/PROGRESS's "GOAL · BPSSL 22.9 · IF THE TREND HOLDS" panel, reworded — see the
     * header's own note) — a dedicated, standalone picture for ONE goal: history through
     * TODAY, a dashed projection out to the dated point it crosses the goal, then a
     * progress bar, a PACE NEED-vs-ACTUAL row, NEXT MEASUREMENT, and (only while they are
     * still unset) a nudge toward the two girth goals.
     *
     * RENDERING ONLY. Every figure here is read off the SAME goal math the plot above
     * already draws its one dotted line and pace sentence from — {@link Model#goalLineAt},
     * {@link Model#goalSlopePerYear}, {@link Model#capGoal} (via {@link #primaryGoalLine}/
     * {@link #goalLineForMethod}, which cap exactly where {@link #goalLines} already does),
     * {@link Meas#goalAnchorOfMethod} — plus two small NEW pure readouts of that same math,
     * {@link Model#goalProgressFraction} and {@link Model#actualRatePerYear}, pinned in
     * SelfTest beside goalLineAt/capGoal's own coverage. No new slope, anchor or cap
     * arithmetic exists anywhere in this method.
     *
     * THE DASHED LINE IS A PLAN, NOT A TREND FIT. {@link Model#goalReachedAt} algebraically
     * reduces to `anchorTs + horizonMonths` once {@link Model#goalSlopePerYear}'s own
     * `(goalCm - baseCm) / horizonYears` is substituted back in — the goalCm/anchorCm terms
     * cancel, so the crossing date carries NO information about how the actual readings
     * have moved; it is the same date whether the user is ahead, on pace, or behind. Every
     * user-facing string this card prints says so explicitly (see the header and the
     * crossing label below) rather than only the chart's contentDescription saying it.
     *
     * WHICH GOAL IT HEADLINES: {@link #primaryGoalLine}'s pick, independent of the plot
     * above's current metric pill — this card is its own instrument, not a view onto that
     * chart's selection. Nothing is drawn at all when no goal-bearing method qualifies.
     *
     * CALLER MUST GATE ON "AT LEAST TWO READINGS" (review round 1, finding 5): this method
     * does not re-check window size itself — it is only ever called from inside
     * progressTrendsContent()'s own `mw.size() >= 2` branch, the same guard trendCard()
     * already renders behind, so the two can never disagree about whether this window has
     * enough to show.
     */
    void goalInstrumentCard(List<Model.Reading> oldestFirst) {
        SessionActivity.GoalLine g = a.primaryGoalLine(oldestFirst);
        if (g == null) return;
        // MASKING (final review pass — the identical defect Task 10's own review round
        // fixed for the P/P% tile: an unmasked body-derived figure shipping beside others
        // that correctly mask). Every number this card prints — the goal value in the
        // header, the progress percentage (drawn AND spoken), the on-canvas "GOAL n.n"
        // label, and PACE NEED/ACTUAL — is read straight off this same goal's own
        // readings, exactly the category trendLead()'s headline figures already mask
        // under a few hundred lines up. ONE key for the whole card (not per-figure): they
        // are one statement about one goal, and revealing the percentage while the pace
        // stayed covered would be the same odd half-answer sinceLastStrip()'s own doc
        // warns against. GoalInstrumentChart reconstructs the identical key from its own
        // `goal.method` field (set via chart.set() below) rather than capturing this local,
        // since it draws on its own onDraw call, not from this method's stack.
        final String goalKey = a.measKey("goal:" + g.method);
        boolean isLength = Model.goalMethodIsLength(g.method);
        // COLD readings of exactly this method, oldest-first — Meas#preOf composed with
        // Meas#ofMethod, the same "no post readings" discipline goalAnchorOfMethod already
        // holds the anchor to, now applied to the whole history line: a post reading is
        // swollen, and drawing one into this trajectory would start "today" higher than it
        // really is. hist.get(0) is therefore the SAME reading g was anchored to.
        List<Model.Reading> hist = Meas.preOf(Meas.ofMethod(oldestFirst, g.method));
        if (hist.isEmpty()) return;   // defensive; goalLineForMethod already guarantees one

        Model.Reading latest = hist.get(hist.size() - 1);
        double latestV = isLength ? latest.len : latest.gir;
        // S10+'s forecast-source setting resolved (see #projectionActualCm's own doc):
        // PACE ACTUAL and the trend badge below read `actualV`, never `latestV` directly —
        // `latestV` above stays reserved for the progress fraction a few lines down, which
        // answers "how far along am I", a plain fact about the literal latest reading, not
        // "what does the projection say" — the mock's own "Goal projection uses" row names
        // only the projection-comparison figures, not the progress bar.
        double actualV = projectionActualCm(hist, isLength);
        // THE PROGRESS FRACTION'S OWN BASELINE IS NOT g.anchorCm (review round 1, finding
        // 4). g.anchorCm is the WINDOW-scoped anchor (Meas#goalAnchorOfMethod) — correct
        // for the chart and for PACE NEED/ACTUAL, which are both about THIS window's own
        // trajectory. But capGoal (Model#capGoal), the function that actually bounded this
        // goal when it was SAVED, was anchored against Meas#goalBaselineCm — the oldest
        // reading of this method across the WHOLE log, not just whatever period pill
        // happens to be selected. Reading the percentage off g.anchorCm would make "34% of
        // the way to goal" silently shift every time 6W/3M/1Y is tapped, while presenting
        // itself as one absolute fact both on screen and to a screen reader. goalBaselineCm
        // is an EXISTING function — no new math — so this only changes which of two
        // already-computed anchors the fraction is read against.
        double progressBaseline = Meas.goalBaselineCm(a.model.measLog.all, g.method, isLength);
        // Defensive fallback (round 1 finding 4's own fix; WIDENED in round 2, finding 4 —
        // a real regression, not a hypothetical). `goalLineForMethod` only guarantees
        // `g.goalCm > g.anchorCm`, the WINDOW anchor — it says nothing about
        // `goalBaselineCm`'s FULL-LOG baseline. `Model#capGoal`'s own doc states plainly
        // that "a goal BELOW the baseline is never capped", so a goal legitimately kept
        // below the lifetime baseline (the very first-ever reading was unusually high, or
        // the user lowered their target since) is entirely ordinary — and the ORIGINAL
        // guard here only caught `progressBaseline <= 0`, not `progressBaseline >=
        // g.goalCm`. Left as written, THAT case fed goalProgressFraction a non-positive
        // span, which returns 1 ("already there") unconditionally — a confident, wrong
        // "100%" and a fully-filled bar for a user who may be nowhere near this goal.
        // `g.anchorCm` is always safely below `g.goalCm` here (goalLineForMethod's own
        // guarantee), so it is the correct fallback for BOTH degenerate cases, not just
        // the `<= 0` one.
        if (progressBaseline <= 0 || progressBaseline >= g.goalCm) progressBaseline = g.anchorCm;
        double frac = Model.goalProgressFraction(latestV, progressBaseline, g.goalCm);

        LinearLayout card = Ui.col(a);
        // popRing's lime-tinted stroke over the live surface — the same "this is the one
        // live card" ring Ui#popRing already draws elsewhere, reused for the mockup's own
        // `.panel{border-color:#3f4a1a}` (a dim LIME border): this card is the one
        // instrument the goal is drawn on, and the ring is the same colour the header text
        // and the progress fill both already wear.
        card.setBackground(Ui.popRing(a));
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 11), Ui.dp(a, 10), Ui.dp(a, 11), Ui.dp(a, 10));

        // HEADER: "GOAL · <METHOD> <value> · AT THE CAPPED RATE" (lime) left, the progress
        // percentage (dim) right — the mockup's panel head, generalised past its one worked
        // example (BPSSL 22.9) to whichever method primaryGoalLine picked, and RE-WORDED
        // (review round 1, finding 1) away from the mockup's own "IF THE TREND HOLDS": the
        // dashed line is Model#goalLineAt's capped PLAN, not a fit to the actual readings —
        // goalCm and anchorCm cancel out of goalReachedAt's date entirely, so "the trend"
        // is not what puts the crossing date where it lands. "At the capped rate" is the
        // EXACT phrase goalPaceLine()'s own caption and the chart legend above already use
        // for this identical qualification ("on pace: <date> at the capped rate" / "at the
        // fastest honest pace") — reused here rather than a third, competing phrasing.
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // Both the goal figure and the progress percentage go through shown(), the same
        // mask LEN/GIR/P-P% already use — Ui.microLabel sets its own contentDescription
        // FROM the text it is given, so passing the already-masked string covers the
        // visual label and the spoken form in one place, with no separate a11y branch to
        // keep in sync.
        TextView headline = Ui.microLabel(a, head,
            "GOAL · " + Model.Reading.methodLabel(g.method) + " "
            + a.shown(Model.Fmt.lenNum(g.goalCm), goalKey)
            + " · at the planned pace", Ui.ACCENT);
        headline.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        GoalInstrumentChart chart = new GoalInstrumentChart(a);
        chart.set(hist, g, isLength);
        long crossTs = Model.goalReachedAt(g.anchorTs, g.anchorCm, g.goalCm, g.ratePerYear);
        // G-Progress: the band's honesty note is the headline's ⓘ (it was a caption under
        // the chart), still only while the band is drawn.
        if (crossTs > latest.ts)
            Ui.infoButton(a, head, "the shaded band", "The shaded band",
                "Shaded band is illustrative, not a computed confidence range.");
        Ui.microLabel(a, head, a.shown(Math.round(frac * 100) + "%", goalKey), Ui.DIM);
        card.addView(head);
        // canDrawProjection mirrors GoalInstrumentChart#onDraw's own `canProject` exactly
        // (crossTs > the last actual reading's ts) — computed here too so the caption below
        // the chart and the description spoken FOR it agree with what is actually drawn.
        boolean canDrawProjection = crossTs > latest.ts;
        chart.setContentDescription(Model.Reading.methodLabel(g.method) + " goal instrument. "
            + "History through today, then the plan projected forward at the planned pace"
            + (canDrawProjection
                ? ", crossing the goal around " + a.dayLabel(crossTs) + ". This date is fixed "
                  + "by the goal's horizon and does not reflect how your own readings have "
                  + "actually moved."
                : "."));
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, Look.GOAL_CHART_HEIGHT_DP));
        chartLp.topMargin = Ui.dp(a, Look.S2);
        card.addView(chart, chartLp);
        // THE WEDGE'S HONESTY, ON SCREEN (review round 1, finding 2) — the shaded band
        // around the dashed projection is the canonical "uncertainty band" visual, and
        // nothing in Model or Meas backs it with a real variance or confidence figure (see
        // Look#GOAL_WEDGE_HALF_DP's own doc). That was already true when it shipped, but
        // its honesty lived only in code comments no user ever reads. A short caption here
        // says the same thing where a person can actually see it, only while the wedge is
        // actually drawn.

        // THE PROGRESS BAR: current vs goal, as a filled fraction of the track — two
        // weighted Views in a row rather than the platform ProgressBar, so the track and
        // fill can be the mockup's own 6dp/2dp shapes instead of the system's chrome.
        // Weights are PARTS PER THOUSAND rather than 0..1: a LinearLayout weight of exactly
        // 0.0 on an unreached goal is a legal but easy-to-misread "no weight at all" edge,
        // and an integer part count makes "how much of the whole" unambiguous either way.
        LinearLayout track = new LinearLayout(a);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.GOAL_BAR_RADIUS_DP));
        int filledParts = (int) Math.round(1000 * frac);
        View fill = new View(a);
        fill.setBackground(Ui.roundRect(a, Ui.ACCENT, Look.GOAL_BAR_RADIUS_DP));
        track.addView(fill, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, filledParts));
        View rest = new View(a);
        track.addView(rest, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1000 - filledParts));
        // The FILL is never masked — this app's consistent stance (see the multi-select
        // trend chart above, whose lines and points never mask either) is that a bar or a
        // chart's shape does not mask, only the numeric TEXT that states it does. Its
        // spoken form is text, though, and states the identical percentage the header's
        // own microLabel above just masked — so the sentence goes through the SAME
        // shown() call, not a second, uncovered route to the same fact.
        track.setContentDescription(
            a.shown(Math.round(frac * 100) + " percent", goalKey) + " of the way to the goal");
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, Look.GOAL_BAR_HEIGHT_DP));
        trackLp.topMargin = Ui.dp(a, Look.S2); trackLp.bottomMargin = Ui.dp(a, Look.S2);
        card.addView(track, trackLp);

        // PACE NEED · ACTUAL, both per month — NEED is g.ratePerYear/12 (the rate that lands
        // ON the goal at the horizon's end, Model#goalSlopePerYear, already resolved above
        // by primaryGoalLine); ACTUAL is the same maths run over the READINGS instead of
        // the plan (Model#actualRatePerYear, the SAME anchor and the S10+-resolved actual
        // of this method — actualV, not latestV: see #projectionActualCm's own doc for why
        // this row reads the forecast-source setting while the progress bar above does
        // not). Ahead-or-on-pace reads lime, the same "above the line is ahead" rule the
        // plot's own pace caption already states in words; behind pace is left the row's
        // own default ink — a slower month is a fact this row already states in numbers,
        // not yet a warning.
        double needPerMo = g.ratePerYear / 12.0;
        double actualPerMo = Model.actualRatePerYear(g.anchorTs, g.anchorCm,
                                                      latest.ts, actualV) / 12.0;
        // NEED/ACTUAL are both read straight off this goal's own readings (ACTUAL literally
        // IS actualV, the S10+-resolved measurement, run through actualRatePerYear), so
        // both numbers mask through the same shown()/goalKey as the rest of the card — same
        // reasoning trendLead() already applies to its own headline figures. The UNIT
        // stays visible unmasked either way, the same shape trendLead()'s own unit
        // TextView already takes (" cm" stays lit beside a masked value up there too).
        // The ahead-of-pace colour is itself a derived fact about the same readings —
        // painting ACTUAL lime while its digits read "•••" would leak that signal through
        // the one channel the mask left open, so the span is only applied unmasked.
        boolean paceHidden = a.blurred(goalKey);
        android.text.SpannableStringBuilder paceVal = new android.text.SpannableStringBuilder();
        paceVal.append(a.shown(Say.rateNum(needPerMo), goalKey)).append(" · ");
        int actualAt = paceVal.length();
        paceVal.append(a.shown(Say.rateNum(actualPerMo), goalKey)).append(" ").append(Model.Fmt.lenUnit())
               .append("/month");
        if (!paceHidden && actualPerMo >= needPerMo)
            paceVal.setSpan(new android.text.style.ForegroundColorSpan(Ui.ACCENT), actualAt,
                paceVal.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Ui.kvRow(a, card, "Pace needed · yours", paceVal, Ui.TEXT, null);     // PR-8, PR-9

        // THE TREND BADGE (S6+), next to PACE per the mock: this reading against the M1
        // projection's OWN value for this reading's date — Model#goalProjectionCmAt, a
        // plain readout of goalLineAt (see that method's own doc: S10+ resolved the
        // forecast-source redirect to the OTHER side of this comparison, actualV below,
        // not to goalProjectionCmAt itself). ▲/●/▼ classified once, purely, by
        // Model#goalTrendState — never re-derived here.
        //
        // MASKED the same way PACE ACTUAL's own colour is (see the note a few lines up):
        // the badge IS a derived fact about the same readings, so hiding the digits
        // while still painting green/blue would leak the one signal masking exists to
        // cover. Hidden, it reads as the same "•••" mask every other figure on this card
        // uses, in the row's own default ink — no colour, no glyph.
        double projectedNowCm = Model.goalProjectionCmAt(latest.ts, g.anchorTs, g.anchorCm,
                                                          g.goalCm, g.ratePerYear);
        int trend = Model.goalTrendState(actualV, projectedNowCm, Model.GOAL_TREND_NOISE_CM);
        boolean trendHidden = a.blurred(goalKey);
        Ui.kvRow(a, card, "This reading vs projection",
            trendHidden ? a.MASK : Say.trendLabel(trend),
            trendHidden ? Ui.TEXT : a.trendColour(trend), null);

        // NEXT MEASUREMENT — measDueText(), the SAME cadence status Settings' own "Next
        // reading" row already prints, not a new reminder computation.
        Ui.kvRow(a, card, "Next measurement", a.measDueText(), Ui.TEXT, null);

        // THE UNSET-GIRTH-GOALS NUDGE — only beside a LENGTH headline (this card's one
        // worked example, and the only case with a clean answer to "which OTHER goals are
        // still unset"): when the instrument itself IS a girth goal, a second girth-goals
        // row underneath it would be redundant with the card already on screen.
        if (g.method == Model.Reading.METHOD_BPSSL
                && (a.model.goalMsegCm == null || a.model.goalMssgCm == null)) {
            String mseg = a.model.goalMsegCm == null ? "—"
                : Model.Fmt.lenNum(a.model.goalMsegCm.doubleValue());
            String mssg = a.model.goalMssgCm == null ? "—"
                : Model.Fmt.lenNum(a.model.goalMssgCm.doubleValue());
            // PR-8: "Not set ›" when neither is, rather than two dashes and a shouted SET.
            boolean neither = a.model.goalMsegCm == null && a.model.goalMssgCm == null;
            Ui.kvRow(a, card, "Girth goals",
                neither ? "Not set ›" : "MSEG " + mseg + " · MSSG " + mssg + " · Set ›", Ui.TEXT,
                a.new Tap(SessionActivity.Tap.SETTINGS));
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, 10);
        a.body.addView(card, lp);
    }

    /**
     * THE M1 INSTRUMENT'S OWN CHART — history solid through TODAY, then a dashed
     * projection with a translucent uncertainty wedge out to the dated point it crosses the
     * goal. A SEPARATE View from {@link TrendChart} on purpose: that one draws a
     * MULTI-SELECT of series sharing one axis; this one is always exactly ONE series plus
     * its own single projection, answering a different question ("is THIS goal on pace"
     * rather than "what have my selected series done"), so the two do not share a canvas or
     * a scale.
     *
     * Consumes only {@link Model#goalLineAt}/{@link Model#goalReachedAt} for the numbers —
     * everything below is pixel geometry, never a second estimate of the trajectory those
     * two already compute. Internal stroke widths, dash intervals and label sizes are left
     * as plain literals here, matching {@link TrendChart#onDraw}'s own established
     * convention for this same kind of one-off Canvas geometry immediately above.
     */
    private final class GoalInstrumentChart extends View {
        private List<Model.Reading> hist = new ArrayList<Model.Reading>();
        private SessionActivity.GoalLine goal;
        private boolean isLength;

        GoalInstrumentChart(Activity own) {
            super(own);
            setWillNotDraw(false);
        }

        void set(List<Model.Reading> histCold, SessionActivity.GoalLine g, boolean lengthMetric) {
            hist = histCold; goal = g; isLength = lengthMetric;
        }

        private double val(Model.Reading r) { return isLength ? r.len : r.gir; }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || goal == null || hist.isEmpty()) return;

            float padL = 4f, padR = 4f, padT = 10f, padB = 12f;
            float plotW = w - padL - padR, plotH = h - padT - padB;
            if (plotW <= 0 || plotH <= 0) return;

            long todayTs = hist.get(hist.size() - 1).ts;

            long crossTs = Model.goalReachedAt(goal.anchorTs, goal.anchorCm,
                                               goal.goalCm, goal.ratePerYear);
            boolean canProject = crossTs > todayTs;

            long tMin = goal.anchorTs;
            long tMax = canProject ? crossTs : todayTs;
            if (tMax <= tMin) tMax = tMin + 1;   // guards a same-instant span
            long span = tMax - tMin;

            // The projected value AT today — the SAME goalLineAt the dashed segment below
            // is an endpoint of, needed here too so the shared vertical scale includes it
            // before any y-position is computed, exactly as TrendChart's own goal endpoints
            // already do for its axis.
            double lineAtToday = canProject
                ? Model.goalLineAt(todayTs, goal.anchorTs, goal.anchorCm, goal.goalCm,
                                   goal.ratePerYear)
                : 0;

            double vMin = Double.POSITIVE_INFINITY, vMax = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < hist.size(); i++) {
                double v = val(hist.get(i));
                if (v < vMin) vMin = v;
                if (v > vMax) vMax = v;
            }
            if (goal.goalCm < vMin) vMin = goal.goalCm;
            if (goal.goalCm > vMax) vMax = goal.goalCm;
            if (canProject) {
                if (lineAtToday < vMin) vMin = lineAtToday;
                if (lineAtToday > vMax) vMax = lineAtToday;
            }
            if (vMax <= vMin) vMax = vMin + 1;
            double vspan = vMax - vMin;

            float todayX = padL + (float) ((todayTs - tMin) / (double) span) * plotW;
            float goalY  = padT + (float) (1 - (goal.goalCm - vMin) / vspan) * plotH;
            // The ACTUAL latest reading's own y — needed below (finding 3) to draw the seam
            // between it and the plan's own value at today, honestly, instead of a smooth
            // same-colour jump.
            float actualTodayY = padT
                + (float) (1 - (val(hist.get(hist.size() - 1)) - vMin) / vspan) * plotH;
            // PR-10: the axis size, at the reader's font scale, as TauTrendChart's labels.
            float lblSize = Ui.dp(a, 1) * Look.SP_AXIS
                          * a.getResources().getConfiguration().fontScale;

            // THE GOAL, as a horizontal reference across the whole width — capGoal already
            // fixed this number when the goal was saved; this only marks where it sits on
            // the shared axis, so the crossing point (below) reads as "where the dashed
            // line meets this same line" rather than a second, disconnected fact.
            Paint goalLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            goalLinePaint.setStyle(Paint.Style.STROKE);
            goalLinePaint.setColor(Ui.ACCENT);
            goalLinePaint.setAlpha(200);
            goalLinePaint.setStrokeWidth(1.2f);
            goalLinePaint.setPathEffect(new DashPathEffect(new float[]{5f, 4f}, 0));
            c.drawLine(padL, goalY, padL + plotW, goalY, goalLinePaint);
            Paint goalLbl = new Paint(Paint.ANTI_ALIAS_FLAG);
            goalLbl.setColor(Ui.ACCENT);
            goalLbl.setTextSize(lblSize);
            /* PLACED FROM THE PAINT'S METRICS, NOT FROM PIXEL CONSTANTS.
             *
             * This text is 9 dp — 25 px on a 440 dpi phone — and the two offsets here
             * used to be raw pixels: 3 px above the rule, and 9 px below it when there was
             * no room above. The goal is the TOP of the plot in every chart that is not
             * already at it, so the "no room" branch is the normal one, and it dropped the
             * baseline by nine pixels under letters eighteen pixels tall: the rule ran
             * straight through "GOAL 13.5". Ascent and descent are what decide it now, so
             * the label clears the line at any text size or screen density. */
            Paint.FontMetrics gm = goalLbl.getFontMetrics();
            float lblGap = Ui.dp(a, 2);
            float goalLblY = goalY - lblGap - gm.descent;      // sitting on the rule
            if (goalLblY + gm.ascent < 1f)                     // no room above it
                goalLblY = goalY + lblGap - gm.ascent;
            // The LABEL masks (it is numeric TEXT drawn on the canvas, printing the exact
            // same figure the header's own microLabel just masked above); the horizontal
            // reference LINE it captions does not — this app's consistent stance is that a
            // chart's own line/points never mask, only the text stating a number does. Same
            // key the rest of the card uses, reconstructed from this method's own `goal`
            // field since onDraw has no access to goalInstrumentCard()'s local.
            labelOn(c, "GOAL " + a.shown(Model.Fmt.lenNum(goal.goalCm),
                    a.measKey("goal:" + goal.method)), padL, goalLblY, goalLbl);

            // TODAY — the vertical divider between what was measured and what is projected.
            Paint todayLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            todayLinePaint.setStyle(Paint.Style.STROKE);
            todayLinePaint.setColor(Look.LINE);
            todayLinePaint.setStrokeWidth(1f);
            todayLinePaint.setPathEffect(new DashPathEffect(new float[]{2f, 3f}, 0));
            c.drawLine(todayX, 2f, todayX, h - 2f, todayLinePaint);
            Paint todayLbl = new Paint(Paint.ANTI_ALIAS_FLAG);
            todayLbl.setColor(Ui.DIM);
            todayLbl.setTextSize(lblSize);
            todayLbl.setTextAlign(Paint.Align.RIGHT);
            // CLAMPED (review round 1, finding 5) — todayX sits at padL itself whenever the
            // window holds exactly one reading of this method (anchor == today, the most
            // common state for someone who just set a goal), and right-aligned text ending
            // 3px left of THAT draws its whole width off the left edge of the view. This
            // risk grows with anything early in its own horizon, not only the one-reading
            // case, so the clamp is unconditional rather than special-cased to it.
            float todayLblX = todayX - 3f;
            float todayLblW = todayLbl.measureText("TODAY");
            if (todayLblX - todayLblW < 0f) todayLblX = todayLblW + 1f;
            // The foot of the plot is where the earliest reading sits when a line climbs,
            // so this word and the history's first point share a corner. It is drawn on a
            // halo rather than moved: there is no empty corner to move it to.
            Paint.FontMetrics tm = todayLbl.getFontMetrics();
            labelOn(c, "TODAY", todayLblX, h - 1f - tm.descent, todayLbl);

            // HISTORY — the actual readings, solid, through today. Look.BODY: the body
            // measurement domain's own colour (the tape measure, the photographs, the
            // before/after pair), reused here so this dedicated instrument reads as "your
            // body's own line", distinct from the multi-series picker chart above it, which
            // colours by METHOD instead.
            Paint histPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            histPaint.setStyle(Paint.Style.STROKE);
            histPaint.setColor(Look.BODY);
            histPaint.setStrokeWidth(2f);
            Paint histDot = new Paint(Paint.ANTI_ALIAS_FLAG);
            histDot.setColor(Look.BODY);
            // TASK 5 — this instrument is already single-method (goalInstrumentCard's own
            // filter), so it never sees a multi-protocol sitting's N-different-series
            // cluster; the one case it CAN see is the same method logged twice in a day.
            // Computed once, over every history point, not re-derived per point.
            List<Long> histTs = new ArrayList<Long>();
            for (int i = 0; i < hist.size(); i++) histTs.add(Long.valueOf(hist.get(i).ts));
            List<Meas.DayCluster> histClusters = Meas.sameDayClusters(histTs);
            Paint histRing = new Paint(Paint.ANTI_ALIAS_FLAG);
            histRing.setStyle(Paint.Style.STROKE);
            histRing.setColor(Look.BODY);
            histRing.setStrokeWidth(1.2f);
            Path histPath = new Path();
            for (int i = 0; i < hist.size(); i++) {
                Model.Reading r = hist.get(i);
                float x = padL + (float) ((r.ts - tMin) / (double) span) * plotW;
                float y = padT + (float) (1 - (val(r) - vMin) / vspan) * plotH;
                if (i == 0) histPath.moveTo(x, y); else histPath.lineTo(x, y);
                if (sameDayClusterCount(r.ts, histClusters) >= 2)
                    c.drawCircle(x, y, Look.DAY_CLUSTER_RING_R, histRing);
                c.drawCircle(x, y, 2.5f, histDot);
            }
            c.drawPath(histPath, histPaint);

            if (!canProject) return;

            float crossX = padL + plotW;
            float crossY = goalY;   // goalLineAt(crossTs, ...) == goal.goalCm by construction
            float todayLineY = padT + (float) (1 - (lineAtToday - vMin) / vspan) * plotH;

            // THE UNCERTAINTY WEDGE — a FIXED visual half-height (Look.GOAL_WEDGE_HALF_DP;
            // see its own doc there — no variance or confidence figure exists anywhere in
            // Model or Meas for this projection, so this is a DECIDED width, not a computed
            // one), pinched to zero at today (the last actual reading, where there is no
            // uncertainty yet) and open by that many dp above/below the projection line by
            // the time it reaches the goal.
            float wedgeHalfPx = Ui.dp(a, Look.GOAL_WEDGE_HALF_DP);
            Path wedge = new Path();
            wedge.moveTo(todayX, todayLineY);
            wedge.lineTo(crossX, crossY - wedgeHalfPx);
            wedge.lineTo(crossX, crossY + wedgeHalfPx);
            wedge.close();
            Paint wedgeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            wedgeFill.setColor(Look.GOAL_WEDGE_FILL);
            wedgeFill.setStyle(Paint.Style.FILL);
            c.drawPath(wedge, wedgeFill);

            // THE TODAY SEAM (review round 1, finding 3) — the dashed projection starts
            // from the PLAN's own value at today (todayLineY), which is generally NOT the
            // same pixel as the actual latest reading (actualTodayY) the solid history line
            // just ended at — that gap is the entire reason a plan and a trend are two
            // different lines. Joining them with one smooth, same-coloured stroke would
            // draw a silent "correction" at the exact instant a behind-pace user's real
            // trajectory disagrees with the plan, which is a flattering distortion, not an
            // honest picture. So the plan's own starting point is drawn as its own small
            // hollow dot (the projection's ink, distinct from history's filled violet dot),
            // and — only when the two pixels actually differ — a short, plainly different
            // dotted tick joins them: a visible seam instead of an invisible jump. Nothing
            // about the projection's own math changes, and neither of its two ENDPOINTS
            // (todayLineY, crossY) moves by a pixel — this only makes the existing
            // divergence visible instead of silently smoothed over. The tick is drawn in
            // the projection's OWN ink (BLOCKS[0]) at a lower alpha, tied visually to the
            // plan dot rather than to the grey TODAY divider it happens to sit on top of —
            // sharing Look.LINE's grey here would read as a rendering glitch (two
            // differently-spaced dash patterns stacked on the same dim line) rather than a
            // deliberate seam.
            if (Math.abs(todayLineY - actualTodayY) > 1f) {
                Paint seam = new Paint(Paint.ANTI_ALIAS_FLAG);
                seam.setStyle(Paint.Style.STROKE);
                seam.setColor(Look.BLOCKS[0]);
                seam.setAlpha(140);
                seam.setStrokeWidth(1.2f);
                seam.setPathEffect(new DashPathEffect(new float[]{1.5f, 2f}, 0));
                c.drawLine(todayX, actualTodayY, todayX, todayLineY, seam);
            }
            Paint planDot = new Paint(Paint.ANTI_ALIAS_FLAG);
            planDot.setStyle(Paint.Style.STROKE);
            planDot.setStrokeWidth(1.5f);
            planDot.setColor(Look.BLOCKS[0]);
            c.drawCircle(todayX, todayLineY, 3f, planDot);

            // THE PROJECTION — Model#goalLineAt from today to crossTs, dashed, in the same
            // ink (BLOCKS[0], the mockup's own --blue) the wedge is a translucent tint of.
            Paint projPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            projPaint.setStyle(Paint.Style.STROKE);
            projPaint.setColor(Look.BLOCKS[0]);
            projPaint.setStrokeWidth(1.8f);
            projPaint.setPathEffect(new DashPathEffect(new float[]{4f, 3f}, 0));
            c.drawLine(todayX, todayLineY, crossX, crossY, projPaint);

            Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            markPaint.setColor(Look.BLOCKS[0]);
            c.drawCircle(crossX, crossY, 3f, markPaint);

            // THE DATED CROSSING LABEL. dayLabel() is the SAME "MMM d" formatter the pace
            // caption above already prints its own "on pace: <date>" with — no second date
            // format invented for this card. Right-aligned to the plot's own right edge so
            // it can never run off the view.
            //
            // "AT THE CAPPED RATE" IS NOT DECORATION (review round 1, finding 1). This date
            // is goalReachedAt(anchorTs, anchorCm, goalCm, ratePerYear) — and because
            // ratePerYear IS (goalCm - anchorCm) / horizonYears, the goalCm/anchorCm terms
            // cancel and the date reduces to `anchorTs + horizonMonths`, full stop. It
            // carries NO information about whether the actual readings are ahead, on pace
            // or behind — a user who has gained nothing sees this exact same date. The
            // chart's contentDescription already said "capped pace projected forward" for
            // TalkBack; this is the identical qualifier, in the identical words
            // goalPaceLine()'s own caption already uses ("on pace: <date> at the capped
            // rate"), now on screen for sighted users too, not only spoken.
            //
            // NO "±N WK" FIGURE. Nothing in Model or Meas estimates the projection's
            // uncertainty as a time span (see the wedge's own note above) — printing one
            // here would be a fabricated precision this app has no math behind.
            Paint crossLbl = new Paint(Paint.ANTI_ALIAS_FLAG);
            crossLbl.setColor(Look.BLOCKS[0]);
            crossLbl.setTextSize(lblSize);
            crossLbl.setTextAlign(Paint.Align.RIGHT);
            // Under the goal rule where there is room, over it where there is not —
            // metrics again, for the reason the goal label's own note gives: the raw 12 px
            // this used to drop by is half the height of the letters it was moving.
            Paint.FontMetrics cm = crossLbl.getFontMetrics();
            float crossLblY = crossY + lblGap - cm.ascent;
            if (crossLblY + cm.descent > h - 1f) crossLblY = crossY - lblGap - cm.descent;
            labelOn(c, a.dayLabel(crossTs) + " at the planned pace", w - padR, crossLblY, crossLbl);
        }

        /**
         * A LABEL ON THIS CHART HAS TO SURVIVE WHAT PASSES BEHIND IT. Every one of the
         * three names a line that runs the width of the plot, so there is no position that
         * is reliably clear — the goal rule spans the chart, the projection crosses it,
         * and the history can end anywhere. So each is drawn twice: once as a STROKE in the
         * colour of the card this view sits on (Ui#popRing's fill), which erases whatever
         * crosses the letters' edges, then as the fill on top.
         *
         * The stroke is a dp, floored at 2 px: a line drawn at 1.2 px needs about that much
         * cover either side of a glyph's outline, and a halo thicker than the letters'
         * strokes starts to read as a box around the words.
         */
        private void labelOn(Canvas c, String text, float x, float y, Paint p) {
            Paint halo = new Paint(p);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeJoin(Paint.Join.ROUND);
            halo.setStrokeWidth(Math.max(2f, Ui.dp(a, 1)));
            halo.setColor(Ui.SURFHI);
            c.drawText(text, x, y, halo);
            c.drawText(text, x, y, p);
        }

        /** How many of `clusters` (see Meas#sameDayClusters) `ts` falls inside — 0 or 1 when
         *  its day is not a cluster at all (dayKey simply is not one of the days listed),
         *  otherwise that cluster's own count. A linear scan over `clusters`, not a map: this
         *  chart's history is one method's readings, so `clusters` is short. */
        private int sameDayClusterCount(long ts, List<Meas.DayCluster> clusters) {
            int key = PhotoCalendar.dayKey(ts);
            for (int i = 0; i < clusters.size(); i++)
                if (clusters.get(i).dayKey == key) return clusters.get(i).count;
            return 0;
        }
    }
}
