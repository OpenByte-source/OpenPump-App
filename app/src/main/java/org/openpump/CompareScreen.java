package org.openpump;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * Compare two dated readings' photos, per Task 9 and proto/pump-console.html's
 * v-compare / renderCompare (cmpMode/cmpStep/cmpSwap/cmpWipe/bindWipe). Renders into the
 * same `body` ViewGroup every other screen uses, following the app's "removeAllViews()
 * then rebuild" idiom.
 *
 * All three of this task's routed defects (prototype-known-defects.md) live here or in
 * Compare.java, the pure layer this class drives:
 *
 *   #1 (REGRESSION, the worst of the three) — the A/B arrows and ⇄ Swap are dead in the
 *   prototype because its per-paint id-resolution unconditionally overwrites the index a
 *   tap just set. Fixed by construction: the pair is held as IDS ONLY, in exactly ONE
 *   place, so there is no second field for a render to clobber. That place is now
 *   {@link PhotoCalendar.Sel}; {@link Compare.Selection} keeps only the mode and the seam
 *   position, and takes its two ids FROM the calendar on every render
 *   ({@link PhotoCalendar#toSelection}). Every control — DayTap, SwapTap — writes the new
 *   ids through {@link PhotoCalendar#tap}/{@link PhotoCalendar#swap} before re-rendering,
 *   never leaving render() to invent its own idea of where the selection is. The one
 *   normalising call per render, {@link PhotoCalendar#prune}, only ever CLEARS an id whose
 *   reading no longer exists; it cannot rewrite a live pick, which is the property the
 *   prototype's unconditional re-derivation lacked.
 *
 * HOW THE PAIR IS CHOSEN (the calendar). The A/B ◀/▶ steppers moved through the list of
 * photo-bearing readings by POSITION. That answers "the last two" and nothing else: reaching
 * three months ago meant counting taps through every reading in between with no idea how
 * many that would be. They are replaced by a month grid — dots on the dates that have a
 * photo of the current view, dim and disabled on the dates that do not, ‹ › bounded by the
 * earliest photo and by today. The decidable half of that (cells, dots, bounds, the
 * selection state machine, every spoken name) is {@link PhotoCalendar}, which is pure and
 * asserted; this class owns the Buttons.
 *
 * What did NOT change, deliberately: the same-view-only rule (upstream — `ps` is
 * Compare.withPhotos' output, so a reading with only another view is not in the grid at
 * all) and the comparability verdict and its sentence (downstream — {@link Compare#verdict},
 * untouched). This task changed which two readings are CHOSEN, never how they are judged.
 * {@link Compare#withStep} is consequently no longer called from any screen; it and its
 * assertions are kept because they pin defect #1's fix on the selection type itself.
 *
 *   #5 — the comparability chip is built by {@link Compare#verdict}, which asks
 *   Meas.windowDirectlyComparable() (built on Model.Reading.comparable(), the one rule
 *   Task 8 established) rather than re-deriving its own notion of "like for like" — so a
 *   reading whose hold duration was never recorded can never be waved through as matching.
 *
 *   #9 — the two per-30-day rates (length AND girth) are both shown below the two delta
 *   chips, each with its own "length …/girth …" label and its own sign colour — see
 *   buildFactsCard()'s second deltaRow() call — never one bare, unlabelled figure sitting
 *   where a reader would attribute it to whichever chip happens to be beside it.
 *
 * Step 2's "drag the seam directly on the image" requirement is CompareStageView's
 * onTouchEvent, not the SeekBar underneath it — the SeekBar stays only as the
 * keyboard/AT route to the same value, exactly as the prototype's own comment for
 * bindWipe() explains.
 */
public final class CompareScreen {

    private final Activity activity;
    private final ViewGroup body;
    private final Model model;
    private final Runnable onBack;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Compare.Selection sel = new Compare.Selection();
    private String photoView = "front";      // "front" | "side" | "top"
    /** POINT 19 - the method whose photos are paired (Compare#methodKey), or null for the
     *  default: the method of this view's newest photo that has a pair. A photo is only ever
     *  compared with photos taken the same way. */
    private String photoMethod;

    /* The CALENDAR picker's state (PhotoCalendar, pure and asserted). `calSel` is THE
     * selection — `sel` holds the mode and the seam position, and takes its two ids FROM
     * calSel on every render via PhotoCalendar.toSelection. There is deliberately no second
     * place a pick can live: the A/B index this screen used to carry alongside the ids is
     * exactly defect #1's shape, and a calendar that kept its own copy of the pair would be
     * the same mistake in a new costume. `calMonth` is null until the first render, which is
     * what makes the picker open on the month of the newest photo of whichever view is
     * showing — and re-open there when the view toggle changes which photos exist. */
    private PhotoCalendar.Sel calSel = new PhotoCalendar.Sel();
    private PhotoCalendar.Month calMonth;

    /**
     * THE DAY WHOSE PHOTOS ARE BEING CHOSEN BETWEEN, or 0 when no such question is open.
     *
     * The calendar has one cell per day and a day can hold several readings, so
     * {@link PhotoCalendar#cells} keeps the newest and its own doc calls the rest
     * "unreachable from the calendar at all" — a real limitation, accepted at the time
     * because a day cell that opened a sub-picker was thought to cost the clarity the
     * calendar exists for. It does not, as long as the sub-picker appears ONLY where the
     * ambiguity actually is: a day with one photo behaves exactly as it always did, with no
     * extra tap, and a day with several asks which one instead of silently picking.
     *
     * A day key, not a list of readings: the strip is rebuilt from the live log on every
     * render, so a reading deleted while the strip is open simply stops appearing in it
     * rather than lingering as a stale row pointing at nothing.
     */
    private int dayChoiceKey = 0;

    private Bitmap bmpA, bmpB;
    private CompareStageView stage;
    private SeekBar wipeSeek;
    private TextView pillLeft, pillRight, blinkPill;
    private boolean blinkShowB = false;
    private final Runnable blinkTick = new BlinkTick();

    public CompareScreen(Activity activity, ViewGroup body, Model model, Runnable onBack) {
        this.activity = activity;
        this.body = body;
        this.model = model;
        this.onBack = onBack;
    }

    /** Entry point from the measurement history screen's "Compare two dates" button.
     *  Deliberately does not reset `sel` — re-opening Compare resumes whatever pair was
     *  last being looked at, the same way the prototype's module-level CMP persists
     *  across visits; there is no "stale selection" bug class here because resolve()
     *  repairs a deleted id on every render regardless of how long ago it was set. */
    public void open() {
        render();
    }

    /* ---------------------------------------------------------------- privacy blur
     *
     * Compare draws MORE of a user's photographs at once than any other screen — a full-
     * size pair on the stage plus a strip of same-day thumbnails — so it is the screen the
     * privacy blur exists for. The rules are the Activity's, restated for this class's own
     * views: presentation only, per item, and a reveal that never outlives the screen.
     *
     * THE REVEAL IS THE ACTIVITY'S, not this class's. It used to keep its own per-item set
     * and its own 👁 per photo; the blur is now ONE page-level toggle, offered on the
     * Progress title and the Photos gallery header, and Compare is reached from both. So it
     * reads SessionActivity#blurRevealSession rather than keeping a second flag that could
     * say something different about the same photographs — and it offers no toggle of its
     * own, because the pair of pages that own the control are one tap behind it.
     */
    private boolean blurred(String key) {
        return model.privacyBlur && !SessionActivity.blurRevealSession;
    }

    /** The key for one photo, matching the Activity's shape so the two never disagree
     *  about what "this item" means. */
    private static String photoKey(String readingId, String view) {
        return "p:" + readingId + ":" + view;
    }

    /** Puts a thumbnail into an ImageView, blurred when that item is obscured — the same
     *  two routes SessionActivity#showPhotoInto uses, over the one shared pixelate(). */
    private void thumbInto(android.widget.ImageView img, Bitmap b, String key) {
        if (b == null) return;
        /* THE CLEAR PATH CRASHED EVERY PHONE BELOW ANDROID 12.
         *
         * setRenderEffect arrived in API 31. The BLUR path checks for it two lines down;
         * this one - the ordinary, unblurred, overwhelmingly common one - called it
         * unguarded, so on this app's own minSdk of 24 through Android 11 the first photo
         * ever drawn threw NoSuchMethodError. Nothing below 31 has ever had an effect set
         * on it, so on those versions there is nothing to clear. */
        if (!blurred(key)) {
            if (android.os.Build.VERSION.SDK_INT >= 31) img.setRenderEffect(null);
            img.setImageBitmap(b);
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            img.setImageBitmap(b);
            img.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                18f, 18f, android.graphics.Shader.TileMode.CLAMP));
            return;
        }
        img.setImageBitmap(Photos.pixelate(b));
    }

    /** Entry point from the Progress "then vs now" card (§8 #6): opens Compare already
     *  showing `view` with exactly this pair picked. The pick is written into `calSel` —
     *  the ONE place a pick lives (see the field note above) — and `calMonth` is cleared
     *  so the calendar re-seats itself on this view's photos; render() then repairs the
     *  ids through the same prune()/seed()/resolve() path every other pick takes, so an
     *  id deleted since the card was drawn degrades exactly like any other stale pick. */
    public void openPair(String view, String method, String aId, String bId) {
        photoView = view;
        photoMethod = method;
        calSel = new PhotoCalendar.Sel(aId, bId);
        calMonth = null;
        dayChoiceKey = 0;
        render();
    }

    /** Stops the blink timer and frees the decoded bitmaps — call from the Activity's
     *  onPause/onDestroy (mirrors CameraScreen#releaseCamera) so a backgrounded app never
     *  keeps a 700ms timer alive or two full-size bitmaps pinned in memory.
     *
     *  FINAL REVIEW, Important: this recycled both bitmaps and nulled only THIS class's
     *  own two fields, while the live CompareStageView still held the same two references
     *  (buildStage: `stage.a = bmpA; stage.b = bmpB;`) and stayed in the view hierarchy —
     *  SessionActivity.onPause() calls stop() unconditionally and nothing re-renders on
     *  resume. Bitmap.getWidth() keeps returning the cached dimension after recycle(), so
     *  drawCover()'s null/size guards fell straight through to Canvas.drawBitmap and the
     *  first draw after the user came back ("wipe" is the default mode, so dragging the
     *  seam calls invalidate() directly) threw "Canvas: trying to use a recycled bitmap"
     *  and killed the process. The stage's references are cleared HERE, with the recycle,
     *  because the two must not be able to disagree; drawCover() also gained an
     *  isRecycled() guard, so neither half alone is load-bearing. */
    public void stop() {
        ui.removeCallbacks(blinkTick);
        releaseBitmaps();
    }

    private void releaseBitmaps() {
        // Clear the VIEW's references first: after this point nothing that draws can
        // reach the bitmaps at all, recycled or not. Doing it in the other order leaves
        // a window (however short) in which a draw pass sees a recycled bitmap.
        if (stage != null) { stage.a = null; stage.b = null; }
        if (bmpA != null) { bmpA.recycle(); bmpA = null; }
        if (bmpB != null) { bmpB.recycle(); bmpB = null; }
    }

    /* ------------------------------------------------------------------- render */

    private void render() {
        ui.removeCallbacks(blinkTick);
        // Any render() (mode switch, step, swap, view toggle) rebuilds the stage from
        // scratch — always restart blink on A, never leave it paused on whatever B/A the
        // timer happened to land on before this render was triggered.
        blinkShowB = false;
        body.removeAllViews();

        LinearLayout root = Ui.col(activity);

        // The app-wide back header (Ui.header): the modest "‹" arrow and the title, one
        // affordance shared with every other pushed screen. BackTap is unchanged — it stops
        // the blink timer and returns to Readings. Swap is a screen action, not navigation,
        // so it is appended to the right of the title (which carries the layout weight).
        // PR-18: "⇄ Swap sides", and only once there is a pair on screen to swap (it is
        // appended below, where the pair is resolved).
        LinearLayout hdr = Ui.header(activity, root, "Compare", new BackTap());

        // Top is on the switch only while old Top photos exist; a pick left on it after the
        // last one was deleted falls back to POV rather than to a view the switch no longer
        // offers.
        if ("top".equals(photoView) && Compare.withPhotos(model.measLog, "top").isEmpty())
            photoView = "front";
        root.addView(viewToggleRow(), wrapLp());

        // POINT 19 - ONE METHOD AT A TIME. The pairs are made within the method chosen below
        // (or the default: the method of the newest photo that has a pair), so a BPEL sitting
        // is never set against a standardised one and given a coloured "change".
        List<String> pairable = Compare.methodsWithPairs(model.measLog, photoView);
        if (photoMethod == null || !pairable.contains(photoMethod))
            photoMethod = Compare.defaultMethod(model.measLog, photoView);
        List<Model.Reading> ps = Compare.withPhotos(model.measLog, photoView, photoMethod);
        if (ps.size() < 2) {
            releaseBitmaps();
            // The library cannot support a comparison at all. This is NOT the empty-month
            // case: the month arrows cannot fix one photo, so the calendar is not drawn and
            // the sentence does not send the user hunting through months for something that
            // is not there. PhotoCalendar.cannotCompareLine says which of the two it is;
            // the paragraph below (unchanged) says where photos come from.
            // Says what to do first, not just what is missing — a first run reaches this
            // screen with an empty photo library and no other clue where photos come from.
            // The OTHER views are checked too, because "0 front, 2 side" is a state the
            // toggle above can fix and "0, 0, 0" is not.
            String otherWithTwo = null;
            String[] views = { "front", "side", "top" };
            for (int i = 0; i < views.length; i++) {
                if (views[i].equals(photoView)) continue;
                if (!Compare.methodsWithPairs(model.measLog, views[i]).isEmpty()) {
                    otherWithTwo = views[i]; break;
                }
            }
            // Two or more photos, but no two taken the same way, is its own sentence: it
            // names the ways, so it cannot read as "one of these is the wrong photo".
            // Counted in photo FILES: a photo shared by a sitting's readings is one photo
            // (the data-integrity review, item 1).
            int files = Compare.photoCount(model.measLog, photoView);
            String why = files >= 2
                ? Compare.noPairLine(photoView, files,
                                     Compare.methodsOf(model.measLog, photoView))
                : PhotoCalendar.cannotCompareLine(files, photoView);
            Ui.noteInfo(activity, root, why,
                "Why there is nothing to compare",
                why
                + (otherWithTwo != null
                    ? "  There are two with a " + Shot.label(otherWithTwo)
                      + " photo: switch the toggle above."
                    : "  Photos are taken from the baseline or Log a reading screen — tap a "
                      + "POV or Side slot there. Two on separate days is the point: one "
                      + "photo has nothing to be compared against.")
                + "\n\nA reading with only another view is not offered here — comparing POV "
                + "against Side would compare framing, not change. And a photo is compared "
                + "only with photos taken the same way: at rest with the same method, or "
                + "standardised.");
            // PR-20: not a dead end - the way to the second photo, from here.
            if (activity instanceof SessionActivity)
                Ui.big(activity, root, ProgressScreen.LOG_WITH_PHOTO, Ui.BODY)
                    .setOnClickListener(((SessionActivity) activity).new OpenLogReadingTap(
                        Nav.PROGRESS));
            body.addView(root, matchParentLp());
            return;
        }
        Button swap = smallButton("⇄ Swap sides");
        swap.setContentDescription("Swap the two sides");
        swap.setOnClickListener(new SwapTap());
        hdr.addView(swap);

        root.addView(methodRow(pairable), wrapLp());

        // The pick, repaired then seeded, in that order. prune() drops an id whose reading
        // was deleted since it was chosen (a dot the grid can no longer draw must not linger
        // as an invisible selection); seed() then fills a COMPLETELY empty pick with the
        // oldest and the newest, so the screen opens on a real comparison rather than on an
        // empty grid waiting for two taps. seed() deliberately leaves a HALF-made pick half
        // made — a user who has just deselected one date is asked for a second, not
        // silently given one.
        calSel = PhotoCalendar.seed(ps, PhotoCalendar.prune(ps, calSel));
        if (calMonth == null) calMonth = PhotoCalendar.initialMonth(ps, System.currentTimeMillis());
        sel = PhotoCalendar.toSelection(calSel, sel);

        // Only one date picked (the user tapped a selected date to clear it). The grid stays
        // up with the remaining pick still lit and asks for the second; the stage, the facts
        // and the verdict are not drawn, because there is no pair to draw them for. Nothing
        // is guessed on the user's behalf.
        if (!calSel.complete()) {
            releaseBitmaps();
            root.addView(calendarCard(ps), wrapLp());
            if (dayChoiceKey != 0) root.addView(dayStripCard(ps), wrapLp());
            body.addView(root, matchParentLp());
            return;
        }

        Model.Reading A = byId(ps, sel.aId);
        Model.Reading B = byId(ps, sel.bId);
        curA = A; curB = B;

        modeRow(root);

        FrameLayout stageWrap = buildStage(A, B);
        LinearLayout.LayoutParams stageLp =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 260));
        stageLp.topMargin = Ui.dp(activity, 4); stageLp.bottomMargin = Ui.dp(activity, 4);
        root.addView(stageWrap, stageLp);

        wipeSeek = new SeekBar(activity);
        wipeSeek.setMax(100);
        wipeSeek.setProgress(sel.wipe);
        // The keyboard/AT route to the seam the stage drags directly (see the class
        // doc). A SeekBar announces its own percentage; this names what the percentage
        // moves. setMinimumHeight guarantees the 48dp touch target the thumb needs.
        wipeSeek.setContentDescription("Drag position between the two photos");   // PR-21
        wipeSeek.setMinimumHeight(Ui.dp(activity, 48));
        wipeSeek.setOnSeekBarChangeListener(new WipeSeekListener());
        wipeSeek.setVisibility("wipe".equals(sel.mode) ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams seekLp = wrapLp();
        seekLp.bottomMargin = Ui.dp(activity, 4);
        root.addView(wipeSeek, seekLp);

        // PR-18: the rows say each photo's ROLE, as the pills on the stage do - the earlier
        // one is Before - rather than the slot letter.
        Compare.Labeled roles = Compare.labelPair(A, B);
        root.addView(pickedRow("Before", roles.before), wrapLp());
        root.addView(pickedRow("After", roles.after), wrapLp());
        root.addView(calendarCard(ps), wrapLp());
        // The same-day question, directly under the grid that raised it — a strip floating
        // elsewhere on the screen would not read as an answer to the tap just made.
        if (dayChoiceKey != 0) root.addView(dayStripCard(ps), wrapLp());

        buildFactsCard(root, A, B);
        buildNotesCard(root, A, B);

        body.addView(root, matchParentLp());

        if ("blink".equals(sel.mode)) startBlink();
    }

    private LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchParentLp() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private Model.Reading byId(List<Model.Reading> ps, String id) {
        for (int i = 0; i < ps.size(); i++) if (ps.get(i).id.equals(id)) return ps.get(i);
        return ps.isEmpty() ? null : ps.get(0);
    }

    /* -------------------------------------------------------------- view toggle */

    /**
     * POINT 19 - THE VIEW SWITCH: the app's own segmented control, "POV | Side", the view
     * by its label. "Top" is offered only while old Top photos exist: no new one can be
     * taken (Shot#TOP), so a third segment on a library without any is a choice that leads
     * nowhere. The stored keys are unchanged ("front"/"side"/"top"); only the LABELS moved.
     */
    private LinearLayout viewToggleRow() {
        boolean legacyTop = !Compare.withPhotos(model.measLog, "top").isEmpty();
        String[] keys = legacyTop ? new String[]{ "front", "side", "top" }
                                  : new String[]{ "front", "side" };
        String[] labels = new String[keys.length];
        View.OnClickListener[] taps = new View.OnClickListener[keys.length];
        int chosen = 0;
        for (int i = 0; i < keys.length; i++) {
            labels[i] = Shot.label(keys[i]);
            taps[i] = new ViewToggleTap(keys[i]);
            if (keys[i].equals(photoView)) chosen = i;
        }
        return Ui.segmented(activity, null, labels, null, chosen, taps).view;
    }

    /**
     * POINT 19 - WHICH METHOD'S PHOTOS ARE PAIRED, said outright. Several methods with a
     * pair: "Photos of" and a small segmented control naming each (Standardised, BPEL...),
     * scrolling sideways if a long list needs to. One: a single line naming it, because the
     * comparison below is only ever within one method and the screen says which.
     */
    private View methodRow(List<String> pairable) {
        LinearLayout box = Ui.col(activity);
        if (pairable.size() <= 1) {
            Ui.note(activity, box, Shot.label(photoView) + " photos, "
                + Compare.methodPhrase(photoMethod) + " — compared with each other.");
            return box;
        }
        Ui.microLabel(activity, box, "Photos of", Ui.DIM);
        String[] labels = new String[pairable.size()];
        View.OnClickListener[] taps = new View.OnClickListener[pairable.size()];
        int chosen = 0;
        for (int i = 0; i < pairable.size(); i++) {
            labels[i] = Compare.methodName(pairable.get(i));
            taps[i] = new MethodTap(pairable.get(i));
            if (pairable.get(i).equals(photoMethod)) chosen = i;
        }
        Ui.Segmented seg = Ui.segmented(activity, null, labels, null, chosen, taps, null, true);
        android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(activity);
        sc.setHorizontalScrollBarEnabled(false);
        sc.addView(seg.view);
        box.addView(sc, wrapLp());
        return box;
    }

    private final class MethodTap implements View.OnClickListener {
        private final String key;
        MethodTap(String k) { key = k; }
        @Override public void onClick(View v) {
            if (key.equals(photoMethod)) return;
            photoMethod = key;
            // Another method is another set of photos: the month re-seats on its newest,
            // and prune() then seed() replace a pick none of whose photos are in it.
            calMonth = null;
            dayChoiceKey = 0;
            render();
        }
    }

    private final class ViewToggleTap implements View.OnClickListener {
        private final String view;
        ViewToggleTap(String v) { view = v; }
        @Override public void onClick(View v) {
            if (photoView.equals(view)) return;
            photoView = view;
            // A different view is a different set of photos, so the month the picker sits on
            // is re-derived: nulling it makes the next render open on the newest photo of
            // the view just chosen, rather than stranding the user in a month that had dots
            // a moment ago and has none now. The PICK itself is left alone — prune() clears
            // whichever ids the new view does not have, and seed() then fills a pick it
            // emptied completely, so a reading that has BOTH views stays selected across the
            // toggle instead of being thrown away.
            calMonth = null;
            render();
        }
    }

    /* ------------------------------------------------------------------ mode row */

    /** PR-17: Drag / Side by side / Blink as the app's own segmented control - the shape
     *  every other one-of-several switch has, with its chosen state said to a reader -
     *  where three flat buttons showed the mode by lime text alone. */
    private void modeRow(LinearLayout root) {
        String[] keys = { "wipe", "side", "blink" };
        int chosen = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(sel.mode)) chosen = i;
        Ui.segmented(activity, root, new String[]{ "Drag", "Side by side", "Blink" },
            new String[]{ "drag", "side by side", "blink" }, chosen,
            new View.OnClickListener[]{ new ModeTap("wipe"), new ModeTap("side"),
                                        new ModeTap("blink") });
    }

    /**
     * Drag / Side by side / Blink. Unlike every other selector in this app these three
     * carry no ●/○ marker at all — which one is active was said by the text COLOUR and
     * by nothing else, so it reached neither a screen-reader user nor a colour-blind
     * sighted one. The state is put on the accessibility node here (the Android
     * equivalent of the aria-pressed the prototype's own accessibility pass sets on
     * exactly this control), leaving the drawn row untouched: these buttons have flat
     * background colours rather than state-list drawables, so setSelected() paints
     * nothing.
     */
    /** The mode's own button label, so the stage describes itself with the word the
     *  user tapped rather than the internal key ("wipe"). */
    private static String modeName(String mode) {
        if ("side".equals(mode)) return "side by side";
        if ("blink".equals(mode)) return "blink";
        return "drag";
    }

    private final class ModeTap implements View.OnClickListener {
        private final String mode;
        ModeTap(String m) { mode = m; }
        @Override public void onClick(View v) {
            if (sel.mode.equals(mode)) return;
            sel = sel.copy();
            sel.mode = mode;
            render();
        }
    }

    /* --------------------------------------------------------------------- stage */

    private FrameLayout buildStage(Model.Reading A, Model.Reading B) {
        setBitmaps(pathFor(A), pathFor(B),
                   A == null ? null : photoKey(A.id, photoView),
                   B == null ? null : photoKey(B.id, photoView));

        FrameLayout wrap = new FrameLayout(activity);
        wrap.setBackgroundColor(Look.GROUND);         // PR-19: the app's ground, not a hex

        stage = new CompareStageView(activity);
        stage.mode = sel.mode;
        stage.wipe = sel.wipe / 100f;
        stage.a = bmpA; stage.b = bmpB;
        stage.blinkShowB = blinkShowB;
        stage.listener = new StageWipeListener();
        wrap.addView(stage, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        Compare.Labeled lab = Compare.labelPair(A, B);
        boolean aIsBefore = lab.aIsBefore;

        // Two photos on a Canvas: nothing here is text, so without a description this is
        // the largest element on the screen and the only one a screen reader cannot see
        // at all. Which reading is "before" comes from Compare.labelPair, the same
        // comparison the pills drawn on top of it use — never a fixed A-is-before
        // assumption, which is wrong whenever the user picked the later reading as A.
        stage.setContentDescription("Photo comparison, " + modeName(sel.mode)
            + " mode. Before: " + dayLabel(lab.before.ts)
            + ". After: " + dayLabel(lab.after.ts) + ".");

        pillLeft = pill(aIsBefore ? "BEFORE" : "AFTER", A);
        pillRight = pill(aIsBefore ? "AFTER" : "BEFORE", B);
        blinkPill = pill(blinkShowB ? (aIsBefore ? "AFTER" : "BEFORE") : (aIsBefore ? "BEFORE" : "AFTER"),
                          blinkShowB ? B : A);

        boolean blink = "blink".equals(sel.mode);
        pillLeft.setVisibility(blink ? View.GONE : View.VISIBLE);
        pillRight.setVisibility(blink ? View.GONE : View.VISIBLE);
        blinkPill.setVisibility(blink ? View.VISIBLE : View.GONE);

        FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        leftLp.gravity = Gravity.TOP | Gravity.LEFT;
        leftLp.leftMargin = Ui.dp(activity, 6); leftLp.topMargin = Ui.dp(activity, 6);
        wrap.addView(pillLeft, leftLp);

        FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        rightLp.gravity = Gravity.TOP | Gravity.RIGHT;
        rightLp.rightMargin = Ui.dp(activity, 6); rightLp.topMargin = Ui.dp(activity, 6);
        wrap.addView(pillRight, rightLp);

        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        centerLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        centerLp.topMargin = Ui.dp(activity, 6);
        wrap.addView(blinkPill, centerLp);

        return wrap;
    }

    private TextView pill(String badge, Model.Reading r) {
        TextView t = new TextView(activity);
        t.setText(badge + "\n" + dayLabel(r.ts));
        t.setTextColor(Ui.TEXT);
        t.setTextSize(Look.SP_MICRO);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(Look.HUD_BG);            // PR-19
        t.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 4), Ui.dp(activity, 8), Ui.dp(activity, 4));
        return t;
    }

    private String pathFor(Model.Reading r) {
        if (r == null) return null;
        Model.Reading.Photo p = Compare.photoOf(r, photoView);   // front | side | top
        return p == null ? null : p.path;
    }

    /** `keyA`/`keyB` name the two items for the privacy blur — null for a slot with no
     *  reading, which has no photo to obscure in the first place. */
    private void setBitmaps(String pathA, String pathB, String keyA, String keyB) {
        releaseBitmaps();
        bmpA = decode(pathA, keyA);
        bmpB = decode(pathB, keyB);
    }

    /** Decoding goes through Photos so the EXIF orientation is applied. Comparing a
     *  photo against a sideways copy of the previous one is not a comparison, and
     *  before Task 14 nothing in this app read that tag anywhere. */
    /**
     * The stage's decode, blurred at the BITMAP when that item is obscured.
     *
     * It cannot use the RenderEffect route: CompareStageView draws these onto a Canvas
     * itself (wipe, blink and side-by-side all need the two images composited by hand), and
     * a render effect belongs to a View, not to a drawBitmap call. So the detail is removed
     * from the pixels — which is the stronger of the two routes anyway.
     *
     * The sharp original is recycled once the blurred copy exists: releaseBitmaps() only
     * knows about the bitmap it is handed, so an un-recycled intermediate here would be a
     * full-size leak on every render. If pixelate() fails, NOTHING is returned rather than
     * the sharp image — showing the thing the setting hides would be the worst outcome
     * available, and an empty stage says plainly that something is wrong.
     */
    private Bitmap decode(String path, String key) {
        Bitmap raw = Photos.decodeOriented(path);
        if (raw == null || key == null || !blurred(key)) return raw;
        Bitmap blur = Photos.pixelate(raw);
        if (blur != raw) raw.recycle();
        return blur;
    }

    private final class StageWipeListener implements CompareStageView.WipeListener {
        @Override public void onWipe(float fraction) {
            int pct = Math.round(fraction * 100);
            sel.wipe = pct;                       // data only — no re-render on every drag pixel
            if (wipeSeek != null && wipeSeek.getProgress() != pct) wipeSeek.setProgress(pct);
        }
    }

    private final class WipeSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
            if (!fromUser) return;
            sel.wipe = progress;
            if (stage != null) { stage.wipe = progress / 100f; stage.invalidate(); }
        }
        @Override public void onStartTrackingTouch(SeekBar sb) { }
        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }

    /* ------------------------------------------------------------------- blink */

    private void startBlink() {
        ui.removeCallbacks(blinkTick);
        blinkShowB = false;
        ui.postDelayed(blinkTick, 700);
    }

    private final class BlinkTick implements Runnable {
        @Override public void run() {
            // A render that navigated away (mode changed, screen left) always calls
            // body.removeAllViews() first — `stage` still pointing at a detached view is
            // harmless, but only keep ticking while THIS is still the live stage.
            if (stage == null || stage.getWindowToken() == null || !"blink".equals(sel.mode)) return;
            blinkShowB = !blinkShowB;
            stage.blinkShowB = blinkShowB;
            stage.invalidate();
            updateBlinkPill();
            ui.postDelayed(blinkTick, 700);
        }
    }

    /** Recomputes the single centred pill blink mode shows, from the SAME date comparison
     *  every other label on this screen uses — never a fixed "A"/"B" badge, so the text is
     *  always correct even when A is the chronologically later reading (the inverted-pick
     *  case Step 2 calls out). curA/curB are set once per render() call, right after the
     *  pair is resolved, so a blink tick firing between renders always reads the pair
     *  actually on screen right now. */
    private void updateBlinkPill() {
        if (blinkPill == null || curA == null || curB == null) return;
        Compare.Labeled lab = Compare.labelPair(curA, curB);
        Model.Reading shown = blinkShowB ? curB : curA;
        boolean shownIsBefore = (shown == lab.before);
        blinkPill.setText((shownIsBefore ? "BEFORE" : "AFTER") + "\n" + dayLabel(shown.ts));
    }

    // The pair actually on screen right now — set once per render() call, right after
    // Compare.resolve() picks A/B, so BlinkTick (which fires between renders) never has to
    // guess which reading is which.
    private Model.Reading curA, curB;

    /* --------------------------------------------------------- the picked pair */

    /**
     * One of the two chosen dates, printed in full with its comparability class. This
     * REPLACES the ◀/▶ stepper row: the arrows moved through a list by position, which
     * cannot answer "me now against three months ago" without counting taps through every
     * reading in between, and gave no clue how many that would be. The calendar below does
     * the choosing; these two rows do the SAYING, so the pair in play is never something the
     * user has to read off the dots.
     *
     * The row is a readout, not a control, so it carries no touch target — and it is grouped
     * for accessibility so "A", the date and the hold badge arrive as one sentence rather
     * than as three unrelated stops.
     */
    private LinearLayout pickedRow(String which, Model.Reading r) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.roundRect(activity, Ui.SURF, Look.R_CARD));     // PR-19
        row.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 9), Ui.dp(activity, 12), Ui.dp(activity, 9));

        // A PREVIEW of the photo this date resolved to. The rows print a date, and a date
        // is not a photograph: with several photos on one day, and a chooser that can now
        // land on any of them, "A · Aug 14" no longer identifies WHICH image is in the
        // comparison. The thumbnail does, at a glance, without opening anything.
        android.widget.ImageView shot = new android.widget.ImageView(activity);
        shot.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        shot.setBackground(Ui.roundRect(activity, Ui.SURFHI, Look.R_CTRL));
        Model.Reading.Photo rp = Compare.photoOf(r, photoView);
        Bitmap rowThumb = rp == null ? null : Photos.decodeThumb(rp.path);
        String rowKey = r == null ? null : photoKey(r.id, photoView);
        thumbInto(shot, rowThumb, rowKey);
        LinearLayout.LayoutParams shotLp = new LinearLayout.LayoutParams(
            Ui.dp(activity, 34), Ui.dp(activity, 34));
        shotLp.rightMargin = Ui.dp(activity, 9);
        row.addView(shot, shotLp);

        TextView badge = new TextView(activity);
        badge.setText(which);
        // TEXT, not a state colour: which slot a reading occupies is not a measurement and
        // not a pump state. (The verdict below it is the thing that wears a colour here.)
        badge.setTextColor(Ui.TEXT);
        badge.setTextSize(Look.SP_LABEL);
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        badge.setMinimumWidth(Ui.dp(activity, 22));
        badge.setPadding(0, 0, Ui.dp(activity, Look.S3), 0);
        row.addView(badge);

        TextView label = new TextView(activity);
        label.setText(dayLabel(r.ts) + holdBadge(r));
        label.setTextColor(Ui.TEXT);
        label.setTextSize(Look.SP_LABEL);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // WHERE THIS SIDE'S PICTURE CAME FROM. It matters most here of all: a comparison is
        // an argument that two photos are of the same thing under the same conditions, and
        // an imported one was framed at an unknown angle. DIM, and only ever on the imported
        // side, so the contrast between the two halves is visible without being shouted.
        boolean imported = rp != null && rp.fromGallery;
        if (imported) {
            TextView src = new TextView(activity);
            src.setText(Gallery.sourceBadge(true));
            src.setTextColor(Ui.DIM);
            src.setTextSize(Look.SP_MICRO);
            src.setPadding(Ui.dp(activity, 6), 0, 0, 0);
            row.addView(src);
        }

        // The pair's reveal lives HERE rather than on the stage: this row already names
        // which slot it belongs to ("A", the date), and the key is the same one the stage
        // decodes under — so one tap uncovers the thumbnail and the big image together,
        // which is what a user asking to see photo A actually means.

        Ui.group(row, which + ", " + dayLabel(r.ts) + holdBadge(r)
            + Gallery.sourceSpoken(imported));
        LinearLayout.LayoutParams lp = wrapLp();
        lp.bottomMargin = Ui.dp(activity, 6);
        row.setLayoutParams(lp);
        return row;
    }

    /* ------------------------------------------------------------- the calendar */

    /**
     * The month grid. The DRAWING moved to {@link CalendarGrid} when the Align screen's
     * ghost picker needed the same grid: two calendars drifting apart on which dates are
     * tappable, how far the arrows travel or what a cell announces is the same class of
     * defect as two screens computing one number two ways. What stays here is what is
     * Compare's own — the A/B pair state machine and the line under the grid.
     *
     * Everything decidable is still PhotoCalendar (pure, asserted). The same-view-only rule
     * is untouched and upstream: `ps` is Compare.withPhotos' output. The comparability
     * verdict is untouched and downstream: this changes which two readings are CHOSEN,
     * never how they are judged.
     */
    private LinearLayout calendarCard(List<Model.Reading> ps) {
        String line = PhotoCalendar.pickLine(
            calSel.aId == null ? null : dayLabelOf(ps, calSel.aId),
            calSel.bId == null ? null : dayLabelOf(ps, calSel.bId));
        return CalendarGrid.card(activity, ps, calMonth, new PairRoles(ps), new GridTaps(), line);
    }

    /** Compare's answer to "what is this date selected as": the A/B letters, via
     *  PhotoCalendar.roleOfDay — by DAY rather than by the cell's own reading id, so a date
     *  whose OLDER photo was chosen from the strip is still lit. Asking roleOf directly
     *  would leave the dot dark on exactly the days the strip exists for. */
    private final class PairRoles implements CalendarGrid.Roles {
        private final List<Model.Reading> ps;
        PairRoles(List<Model.Reading> p) { ps = p; }
        @Override public String roleOf(String readingId) {
            return PhotoCalendar.roleOfDay(ps, calSel, readingId);
        }
    }

    /**
     * THE SAME-DAY STRIP: the photos taken on one date, as thumbnails, so the user picks
     * WHICH one goes into the comparison instead of being handed the newest.
     *
     * Only ever built for a day that genuinely has more than one photo of the current view
     * (see {@link GridTaps#onDay}), and rebuilt from the live list on every render, so it
     * cannot show a reading that has since been deleted.
     *
     * Thumbnails decode through {@link Photos#decodeThumb} — the same upright decode
     * everything else uses, at a grid-sized budget, so a row of them costs a fraction of
     * what the stage's two full decodes do. The TIME is printed under each one because the
     * date is identical by construction and is therefore the one thing that cannot tell
     * them apart.
     */
    private LinearLayout dayStripCard(List<Model.Reading> ps) {
        List<Model.Reading> sameDay = PhotoCalendar.onDay(ps, dayChoiceKey);
        LinearLayout card = Ui.col(activity);
        card.setBackground(Ui.roundRect(activity, Ui.SURF, Look.R_CARD));    // PR-19
        Ui.lift(activity, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10),
                        Ui.dp(activity, 12), Ui.dp(activity, 10));

        TextView line = new TextView(activity);
        line.setText(PhotoCalendar.multiDayLine(sameDay.size(),
            sameDay.isEmpty() ? "that day" : dayLabel(sameDay.get(0).ts)));
        line.setTextColor(Ui.TEXT);
        line.setTextSize(Look.SP_CAPTION);
        card.addView(line);

        LinearLayout strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < sameDay.size(); i++) {
            Model.Reading r = sameDay.get(i);
            LinearLayout cell = Ui.col(activity);
            android.widget.ImageView img = new android.widget.ImageView(activity);
            img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            img.setBackground(Ui.roundRect(activity, Ui.SURFHI, Look.R_CTRL));
            Model.Reading.Photo p = Compare.photoOf(r, photoView);
            Bitmap thumb = p == null ? null : Photos.decodeThumb(p.path);
            String cellKey = photoKey(r.id, photoView);
            thumbInto(img, thumb, cellKey);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 72));
            cell.addView(img, imgLp);

            // The time line doubles as this tile's reveal row: the strip's tiles already
            // mean "pick this photo", so the 👁 needed a target of its own.
            LinearLayout whenRow = new LinearLayout(activity);
            whenRow.setOrientation(LinearLayout.HORIZONTAL);
            whenRow.setGravity(Gravity.CENTER);
            TextView when = new TextView(activity);
            when.setText(PhotoCalendar.timeLabel(r.ts));
            when.setTextColor(Ui.DIM);
            when.setTextSize(Look.SP_MICRO);
            when.setGravity(Gravity.CENTER);
            whenRow.addView(when);
            cell.addView(whenRow, wrapLp());

            cell.setMinimumHeight(Ui.dp(activity, 48));
            cell.setOnClickListener(new DayChoiceTap(r.id));
            // The tile is a picture and a clock time; without this a screen reader reaches
            // a row of identical unnamed buttons and cannot tell which photo is which.
            Ui.group(cell, PhotoCalendar.timeLabel(r.ts) + ", " + Shot.label(photoView) + " photo, "
                + dayLabel(r.ts) + ". Tap to compare this one.");
            LinearLayout.LayoutParams cellLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cellLp.rightMargin = Ui.dp(activity, 5);
            strip.addView(cell, cellLp);
        }
        card.addView(strip, wrapLp());

        Button cancel = Ui.secondary(activity, card, "Cancel");               // PR-21
        cancel.setContentDescription("Close this day's photos without choosing one");
        cancel.setOnClickListener(new DayChoiceCancelTap());
        return card;
    }

    /** A thumbnail in the same-day strip. Applies the ordinary pair state machine with the
     *  reading the user actually chose — never a second selection route that could drift
     *  from PhotoCalendar.tap's rules. */
    private final class DayChoiceTap implements View.OnClickListener {
        private final String id;
        DayChoiceTap(String i) { id = i; }
        @Override public void onClick(View v) {
            dayChoiceKey = 0;
            calSel = PhotoCalendar.tap(calSel, id);
            render();
        }
    }

    private final class DayChoiceCancelTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            // Closing the question changes NOTHING about the pair: the day tap that opened
            // it deliberately did not apply a selection, so there is nothing to undo.
            dayChoiceKey = 0;
            render();
        }
    }

    /**
     * A tap on a dotted date, and the month arrows. The whole pair state machine is
     * PhotoCalendar.tap — first tap picks A, second picks B, a third rolls the old B into A,
     * and tapping an already-selected date DESELECTS it (documented there, and asserted).
     * This decides nothing; it applies the result and re-renders.
     */
    private final class GridTaps implements CalendarGrid.Taps {
        /**
         * THREE CASES, in this order, and the order is the point:
         *
         *   1. This day already holds one of the pair -> DESELECT it. Asked first so a tap
         *      on a lit date keeps meaning what PhotoCalendar.tap's state machine says it
         *      means, even when the lit photo is not the newest one on that date (the cell
         *      names only the newest). Getting this second would turn deselect into
         *      "open the strip" on exactly the days the strip exists for.
         *   2. Several photos on this day -> ask WHICH, and change nothing yet. The
         *      selection is untouched until a thumbnail is tapped, so backing out of the
         *      question leaves the pair exactly as it was.
         *   3. One photo -> the behaviour this screen has always had, with no extra tap.
         */
        @Override public void onDay(String id) {
            List<Model.Reading> ps = Compare.withPhotos(model.measLog, photoView, photoMethod);
            List<Model.Reading> sameDay = PhotoCalendar.siblingsOf(ps, id);
            String already = PhotoCalendar.selectedOnDay(sameDay, calSel);
            dayChoiceKey = 0;
            if (already != null) {
                calSel = PhotoCalendar.tap(calSel, already);
            } else if (sameDay.size() > 1) {
                dayChoiceKey = PhotoCalendar.dayKey(sameDay.get(0).ts);
            } else {
                calSel = PhotoCalendar.tap(calSel, id);
            }
            render();
        }
        @Override public void onMonth(int dir) {
            List<Model.Reading> ps = Compare.withPhotos(model.measLog, photoView, photoMethod);
            PhotoCalendar.Month next =
                PhotoCalendar.stepMonth(ps, calMonth, dir, System.currentTimeMillis());
            // At a bound stepMonth returns the SAME month; re-rendering an identical screen
            // is a wasted redraw, and the arrow is disabled there anyway.
            if (next.equals(calMonth)) return;
            // A month move abandons an open day question — the strip describes a date that
            // is no longer on screen, and leaving it below a different month is a control
            // pointing at something the user can no longer see.
            dayChoiceKey = 0;
            calMonth = next;
            render();
        }
    }

    private String dayLabelOf(List<Model.Reading> ps, String id) {
        for (int i = 0; i < ps.size(); i++)
            if (ps.get(i).id.equals(id)) return dayLabel(ps.get(i).ts);
        return null;
    }

    private String holdBadge(Model.Reading r) {
        // What this photo is, at a glance (point 19): the method both photos share, and for
        // a standardised one the vacuum the pump ACTUALLY delivered (or that it was not
        // measured), never the commanded setpoint.
        // The owner's rule: how the PHOTO was taken (Compare#photoKind), so a standardised
        // photo whose reading was saved at rest says standardised, at what it delivered.
        Model.Reading.Photo p = Compare.photoOf(r, photoView);
        if (Compare.photoKind(r, p) == Compare.KIND_STD)
            return "  ·  " + Compare.photoTakenLine(r, p);
        return "  ·  " + Compare.methodPhrase(Compare.methodKey(r, photoView));
    }

    /** Swap, now written through the CALENDAR's own selection rather than through
     *  Compare.withSwap. The pair lives in exactly one place (see calSel's own note); a Swap
     *  that wrote the other representation would leave the two disagreeing on the very next
     *  render — which is defect #1 exactly, rebuilt. */
    private final class SwapTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (!calSel.complete()) return;
            calSel = PhotoCalendar.swap(calSel);
            render();
            toast("Swapped");
        }
    }

    private final class BackTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            releaseBitmaps();
            ui.removeCallbacks(blinkTick);
            if (onBack != null) onBack.run();
        }
    }

    /* ---------------------------------------------------------------- facts card */

    private void buildFactsCard(LinearLayout root, Model.Reading A, Model.Reading B) {
        LinearLayout card = Ui.col(activity);
        card.setBackground(Ui.roundRect(activity, Ui.SURF, Look.R_CARD));    // PR-19
        Ui.lift(activity, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(activity, 13), Ui.dp(activity, 12), Ui.dp(activity, 13), Ui.dp(activity, 12));

        Compare.Labeled lab = Compare.labelPair(A, B);
        long days = Compare.daysBetween(lab.before, lab.after);
        // POINT 19 - A CHANGE ONLY WHERE THE PAIR IS COMPARABLE. Within one method most
        // pairs are; two standardised photos held at different actual vacuums are not, and
        // the sentence under the figures says so. A signed, coloured figure beside that
        // sentence was the screen contradicting itself - so the figures read "—" there.
        Compare.Verdict verdict = Compare.verdict(A, B, dayLabel(A.ts), dayLabel(B.ts));
        // Task 1's kill-the-spurious-0.0 predicate, reused (not re-invented) at Compare's
        // own delta sites: deltaLen/deltaGir are now Double.NaN whenever either side's
        // method never measured that metric, so a length-only reading paired against a
        // girth-only one (or against anything that skipped one axis) cannot print a
        // fabricated 0.0 change on the one screen whose whole purpose is that number.
        // ratePer30d's days<=0 branch returns a literal 0.0 regardless of its input, so a
        // same-day NaN delta must be short-circuited here rather than passed through —
        // see deltaLen's own doc.
        double dl = verdict.comparable ? Compare.deltaLen(lab.before, lab.after) : Double.NaN;
        double dg = verdict.comparable ? Compare.deltaGir(lab.before, lab.after) : Double.NaN;
        double rl = Double.isNaN(dl) ? Double.NaN : Compare.ratePer30d(dl, days);
        double rg = Double.isNaN(dg) ? Double.NaN : Compare.ratePer30d(dg, days);

        TextView span = new TextView(activity);
        span.setText(dayLabel(lab.before.ts) + " → " + dayLabel(lab.after.ts) + "  ·  "
            + days + (days == 1 ? " day apart" : " days apart"));
        // A date-span heading, not the pump under command — TEXT, not amber. (The trend
        // series below keep their own two hues; this is chrome, and chrome does not wear amber.)
        span.setTextColor(Ui.TEXT);
        span.setTextSize(Look.SP_BODY);
        card.addView(span);

        card.addView(deltaRow("length", dl, "girth", dg), factsLp());
        // Defect #9's fix: BOTH per-30-day rates, each labelled with its own series name —
        // never one bare figure printed where a reader would attribute it to the other.
        card.addView(deltaRow("length", rl, "girth", rg,
                              " " + Model.Fmt.lenUnit() + "/month"), factsLp());     // PR-18

        TextView warn = new TextView(activity);
        warn.setText(verdict.text);
        // A comparability pass about two tape-measure readings is the body-measurement
        // domain, not a telemetry-confirmed vent.
        warn.setTextColor(verdict.comparable ? Ui.BODY : Ui.DIM);
        warn.setTextSize(Look.SP_FIELD_LABEL);
        warn.setPadding(0, Ui.dp(activity, 7), 0, 0);
        card.addView(warn);

        LinearLayout.LayoutParams lp = wrapLp();
        lp.bottomMargin = Ui.dp(activity, 10);
        root.addView(card, lp);
    }

    private LinearLayout.LayoutParams factsLp() {
        LinearLayout.LayoutParams lp = wrapLp();
        lp.topMargin = Ui.dp(activity, 4);
        return lp;
    }

    private LinearLayout deltaRow(String labelL, double vl, String labelR, double vr) {
        return deltaRow(labelL, vl, labelR, vr, " " + Model.Fmt.lenUnit());
    }

    private LinearLayout deltaRow(String labelL, double vl, String labelR, double vr, String suffix) {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(deltaChip(labelL, vl, suffix), chipLp());
        r.addView(deltaChip(labelR, vr, suffix), chipLp());
        return r;
    }

    private LinearLayout.LayoutParams chipLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView deltaChip(String label, double v, String suffix) {
        TextView t = new TextView(activity);
        // Double.isNaN(v): deltaLen/deltaGir/their per-30-day rates are NaN whenever
        // either reading in the pair never measured this metric (Task 1's predicate,
        // reused here) — "—" here, never sgn(v)'s fabricated "+0.00" for a number that
        // was never measured on one or both sides, matching this app's dash convention
        // for every other absent measurement.
        boolean known = !Double.isNaN(v);
        t.setText(label + " " + (known ? sgn(v) + suffix : "—"));
        // PR-18: BODY, whichever way it moved. Green or red by sign alone said a body
        // change was a pass or a fault, by hue only; the sign is in the figure.
        t.setTextColor(known ? Ui.BODY : Ui.DIM);
        t.setTextSize(Look.SP_CAPTION);
        return t;
    }

    /** A signed length CHANGE without its unit word — the chips print the unit themselves
     *  (some of them with a "/30 d" rate suffix attached), so this is the number only, in
     *  whichever size unit is selected. */
    private static String sgn(double v) {
        double shown = Model.Fmt.S_IN.equals(Model.Fmt.sizeUnit)
            ? v / Model.Fmt.CM_PER_IN : v;
        return (v >= 0 ? "+" : "−") + String.format(Locale.US, "%.2f", Math.abs(shown));
    }

    /* ---------------------------------------------------------------- notes card */

    private void buildNotesCard(LinearLayout root, Model.Reading A, Model.Reading B) {
        Compare.Labeled lab = Compare.labelPair(A, B);
        LinearLayout card = Ui.col(activity);
        card.setBackground(Ui.roundRect(activity, Ui.SURF, Look.R_CARD));    // PR-19
        Ui.lift(activity, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(activity, 13), Ui.dp(activity, 12), Ui.dp(activity, 13), Ui.dp(activity, 12));
        TextView title = new TextView(activity);
        title.setText("Notes");
        title.setTextColor(Ui.TEXT); title.setTextSize(Look.SP_LABEL);
        card.addView(title);
        card.addView(noteLine("Before", lab.before));
        card.addView(noteLine("After", lab.after));
        LinearLayout.LayoutParams lp = wrapLp();
        lp.bottomMargin = Ui.dp(activity, 10);
        root.addView(card, lp);
    }

    private TextView noteLine(String badge, Model.Reading r) {
        TextView t = new TextView(activity);
        String note = (r.note == null || r.note.length() == 0) ? "No note" : r.note;
        t.setText(badge + " · " + dayLabel(r.ts) + " — " + note);
        t.setTextColor(Ui.DIM);
        t.setTextSize(Look.SP_FIELD_LABEL);
        t.setPadding(0, Ui.dp(activity, 4), 0, 0);
        return t;
    }

    /* --------------------------------------------------------------------- misc */

    private String dayLabel(long ts) {
        return new SimpleDateFormat("MMM d", Locale.US).format(new java.util.Date(ts));
    }

    private Button smallButton(String text) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(Look.SP_FIELD_LABEL);
        b.setTextColor(Ui.TEXT);
        b.setBackgroundColor(Ui.SURF);
        b.setMinHeight(Ui.dp(activity, 48));
        b.setMinWidth(Ui.dp(activity, 48));
        b.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 6), Ui.dp(activity, 10), Ui.dp(activity, 6));
        return b;
    }

    private void toast(String s) {
        Ui.say(activity, s, false);
    }

    /* ---------------------------------------------------------------- stage view */

    /**
     * Draws the two photos in whichever of the three modes is active, and — Step 2 — owns
     * the direct-on-image drag for the wipe seam via onTouchEvent, not merely a SeekBar
     * underneath it. A is always the LEFT/under image, B the RIGHT/over one; which of them
     * is actually "before" or "after" is a completely separate question, answered by the
     * two pill TextViews CompareScreen positions on top of this view, never by anything
     * drawn here.
     */
    private static final class CompareStageView extends View {
        interface WipeListener { void onWipe(float fraction); }

        String mode = "wipe";
        float wipe = 0.5f;
        Bitmap a, b;
        boolean blinkShowB = false;
        WipeListener listener;

        private final Activity host;
        private final Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint();
        private final Paint seamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dividerPaint = new Paint();
        private final Paint placeholderPaint = new Paint();
        private final Paint placeholderText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect srcRect = new Rect();
        private final Rect dstRect = new Rect();

        CompareStageView(Activity act) {
            super(act);
            host = act;
            bgPaint.setColor(Look.GROUND);
            seamPaint.setColor(Ui.ACCENT);
            seamPaint.setStrokeWidth(3f);
            handleFill.setColor(Ui.ACCENT);
            handleStroke.setColor(Ui.TEXT);
            handleStroke.setStyle(Paint.Style.STROKE);
            handleStroke.setStrokeWidth(2f);
            dividerPaint.setColor(Ui.LINE);
            dividerPaint.setStrokeWidth(2f);
            placeholderPaint.setColor(Ui.SURF);
            placeholderText.setColor(Ui.DIM);
            placeholderText.setTextAlign(Paint.Align.CENTER);
            // PR-19: a size in sp at the reader's font scale, where 28 raw pixels was a
            // different size on every screen density.
            placeholderText.setTextSize(Look.SP_CAPTION
                * act.getResources().getDisplayMetrics().scaledDensity);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            c.drawRect(0, 0, w, h, bgPaint);

            if ("side".equals(mode)) {
                drawCover(c, a, 0, 0, w / 2, h);
                drawCover(c, b, w / 2, 0, w, h);
                c.drawLine(w / 2f, 0, w / 2f, h, dividerPaint);
            } else if ("blink".equals(mode)) {
                drawCover(c, blinkShowB ? b : a, 0, 0, w, h);
            } else {                                        // "wipe"
                drawCover(c, a, 0, 0, w, h);
                int seamX = Math.round(wipe * w);
                if (seamX < w) {
                    c.save();
                    c.clipRect(seamX, 0, w, h);
                    drawCover(c, b, 0, 0, w, h);
                    c.restore();
                }
                c.drawLine(seamX, 0, seamX, h, seamPaint);
                float cy = h / 2f, rad = Ui.dp(host, 12);
                c.drawCircle(seamX, cy, rad, handleFill);
                c.drawCircle(seamX, cy, rad, handleStroke);
            }
        }

        private void drawCover(Canvas c, Bitmap bmp, int left, int top, int right, int bottom) {
            int dw = right - left, dh = bottom - top;
            if (bmp == null || dw <= 0 || dh <= 0) {
                c.drawRect(left, top, right, bottom, placeholderPaint);
                c.drawText("no photo", (left + right) / 2f, (top + bottom) / 2f, placeholderText);
                return;
            }
            // isRecycled() FIRST: getWidth() on a recycled bitmap still returns the
            // cached, non-zero dimension, so the size guard below cannot stand in for
            // this one — drawBitmap on a recycled bitmap throws and kills the process.
            // Drawn as the "no photo" placeholder rather than skipped, so a stale stage
            // reads as empty instead of leaving whatever was underneath on screen.
            if (bmp.isRecycled()) {
                c.drawRect(left, top, right, bottom, placeholderPaint);
                c.drawText("no photo", (left + right) / 2f, (top + bottom) / 2f, placeholderText);
                return;
            }
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            if (bw <= 0 || bh <= 0) return;
            float destAR = (float) dw / dh, srcAR = (float) bw / bh;
            int sw, sh, sx, sy;
            if (srcAR > destAR) { sh = bh; sw = Math.round(bh * destAR); sx = (bw - sw) / 2; sy = 0; }
            else                { sw = bw; sh = Math.round(bw / destAR); sx = 0; sy = (bh - sh) / 2; }
            srcRect.set(sx, sy, sx + sw, sy + sh);
            dstRect.set(left, top, right, bottom);
            c.drawBitmap(bmp, srcRect, dstRect, imgPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!"wipe".equals(mode)) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    setWipeFromX(e.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    setWipeFromX(e.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return false;
            }
        }

        private void setWipeFromX(float x) {
            int w = getWidth();
            if (w <= 0) return;
            float f = x / w;
            f = f < 0 ? 0 : (f > 1 ? 1 : f);
            wipe = f;
            invalidate();
            if (listener != null) listener.onWipe(f);
        }
    }
}
