package org.openpump;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared view builders, lifted out of SessionActivity so every later screen (Settings,
 * and whatever comes after it) can build the same look without re-typing it. Every
 * builder here takes the Activity that owns the views and the ViewGroup it should add
 * itself to — nothing here holds state, so two screens can use it at once.
 *
 * Behaviour is unchanged from the private helpers this replaced: same colours, same
 * padding, same text sizes. Moving them here must not change how the app looks.
 */
public final class Ui {
    private Ui() { }

    /*
     * The palette. Task 17 moved the actual hexes into Look (pure, so they are pinned by
     * SelfTest) and colour now maps to pump state; these names are kept and re-pointed at
     * Look so every existing call site keeps compiling while the whole app adopts the new
     * palette in one place. The semantic mapping is UNCHANGED — only the hexes move — so
     * nothing silently inverts meaning:
     *   MEAS = teal   (measured, from the device)      CMD  = amber (the pump under command)
     *   GOOD = green  (telemetry-confirmed safe)       CRIT = red   (unsafe / failed)
     * Amber (CMD) is EARNED: it may appear only where the pump may be applying pressure,
     * which is why card titles below are TEXT, not CMD, as they used to be.
     */
    public static final int BG   = Look.GROUND, SURF = Look.SURFACE, LINE = Look.LINE;
    public static final int SURFHI = Look.SURFACEHI;
    public static final int TEXT = Look.TEXT, DIM  = Look.DIM, FAINT = Look.FAINT,
                        FAINT_OFF = Look.FAINT_OFF;
    public static final int CMD  = Look.COMMANDED;
    /** LIME. The action/progress accent — see Look#ACCENT for why it replaced teal, and
     *  for the line it must not cross (amber, and only amber, means pressure). */
    public static final int ACCENT = Look.ACCENT;
    /** The BODY MEASUREMENT domain — the tape measure, the photographs, girth and length
     *  deltas. Violet, so a measured body figure is never confused with a commanded
     *  pressure (amber) or with a thing to tap (lime). */
    public static final int BODY = Look.BODY;
    public static final int GOOD = Look.SAFE, CRIT = Look.CRITICAL;

    /** A filled rounded rectangle built in code (no XML) — the card/chip/button corner. */
    public static android.graphics.drawable.GradientDrawable roundRect(Activity a, int fill, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(a, radiusDp));
        return g;
    }

    /** Wave 4 item 8 — the CONTROL look: fill plus the 1 dp CTRL_EDGE stroke, so a
     *  thing that acts in place is visibly a control and not a card. */
    public static android.graphics.drawable.GradientDrawable ctrlRect(Activity a, int fill,
            int radiusDp) {
        android.graphics.drawable.GradientDrawable g = roundRect(a, fill, radiusDp);
        g.setStroke(Math.max(1, dp(a, 1)), Look.CTRL_EDGE);
        return g;
    }

    /** The two DIM TINTS, re-pointed at Look so a call site can reach them the way it
     *  reaches ACCENT. ACCENT_DIM is the fill of anything SELECTED; CMD_DIM is still an
     *  AMBER use and is legal only where the pump may be under pressure — see
     *  {@link Look#CMD_DIM}. */
    public static final int ACCENT_DIM = Look.ACCENT_DIM, CMD_DIM = Look.CMD_DIM;
    /** The fail-tile fill — see {@link Look#CRIT_DIM}: legal only where the cell it tints
     *  reports an evidenced failure, never a generic "attention" wash. */
    public static final int CRIT_DIM = Look.CRIT_DIM;

    /**
     * DEPTH. The mockup's whole card system is shadow — `.card{box-shadow:0 6px 18px
     * rgba(0,0,0,.35)}` — and the app had no elevation anywhere, which is most of why it
     * reads as one flat sheet.
     *
     * A GradientDrawable reports a correct rounded outline, so ViewOutlineProvider.BACKGROUND
     * plus an elevation is all that is needed and no XML is involved. The shadow COLOUR must
     * be set explicitly on API 28+: the platform default is 8% black, which is invisible
     * against a #07090D ground, so it is forced to full black and left to the elevation to
     * modulate.
     *
     * IMPORTANT — A CLIPPING PARENT CROPS THE SHADOW. A shadow is drawn OUTSIDE the child's
     * bounds, so any container that clips will slice it off along its edges. The scrolling
     * body a lifted view lives in must set BOTH `setClipToPadding(false)` and
     * `setClipChildren(false)`; {@link #col} does this for every column Ui builds, and a
     * screen that builds its own ScrollView must do the same or the shadows will simply not
     * appear at the top and bottom of the scroll.
     *
     * @param elevDp 3 for a normal card, 6 for an active/"pop" one.
     */
    public static void lift(Activity a, View v, int elevDp) {
        v.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        v.setElevation(dp(a, elevDp));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            v.setOutlineSpotShadowColor(0xFF000000);
            v.setOutlineAmbientShadowColor(0xFF000000);
        }
    }

    /** The elevations {@link #lift} is called with: a resting card, and an active one. */
    public static final int ELEV_CARD = 3, ELEV_POP = 6;

    /**
     * The ACTIVE card's background: the live surface, a card corner, and the mockup's faint
     * lime ring (`0 0 0 1px rgba(200,255,61,.08)`). The ring is drawn at ACCENT_DIM rather
     * than at .08 because eight percent of a lime hairline is invisible on a phone at arm's
     * length — the ring exists to say "this is the one live card", and a ring nobody can see
     * says nothing. Pair with {@link #lift}(v, ELEV_POP).
     */
    public static android.graphics.drawable.GradientDrawable popRing(Activity a) {
        android.graphics.drawable.GradientDrawable g = roundRect(a, SURFHI, Look.R_CARD);
        g.setStroke(dp(a, 1), Look.ACCENT_DIM);
        return g;
    }

    /**
     * A TRUE HAIRLINE. `dp(1)` on a 3x screen is three physical pixels, which is a rule, not
     * a hairline; the mockup's `1px` border is one CSS pixel and reads as the thinnest mark
     * the screen can make. So the height is half a dp in physical pixels, floored at 1 — on
     * a 1x screen that is still one pixel, on a 3x screen it is one, and the line stops
     * looking drawn with a marker.
     */
    public static View divider(Activity a, ViewGroup parent) {
        View v = new View(a);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, (int) (a.getResources().getDisplayMetrics().density / 2f)));
        lp.topMargin = dp(a, Look.S3);
        lp.bottomMargin = dp(a, Look.S3);
        // Decoration, never content: a screen reader must not stop on a line.
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(v, lp);
        return v;
    }

    /**
     * STATE, IN COLOUR, ON EVERY ●/○ CONTROL. Called by {@link #row} for any button whose
     * label carries a radio marker, so the weekday toggles, the unit picker, Fixed/Ramp, the
     * skip switch and the Library segment all state themselves from one place.
     *
     * The rule respects the palette: LIME TINT = on, because lime owns action and a chosen
     * option is the user's own action shown back to them. SURFHI + dim = off. SURF + faint,
     * disabled = unavailable because a parent switch is off — SHOWN rather than hidden, so
     * turning something off stops making its dependents vanish. Amber and green never appear
     * here; a selected pill is not the pump under pressure and is not a vent confirmation.
     *
     * Weight carries it too, so the state does not rest on colour alone for a user who
     * cannot separate lime from grey: on is BOLD, off is NORMAL.
     */
    public static void stateFill(Activity a, Button b, boolean on, boolean enabled) {
        if (!enabled) {
            b.setBackground(roundRect(a, SURF, Look.R_CTRL));
            // FAINT_OFF, not FAINT: this is the unavailable ink, pinned under 3:1 so a
            // switched-off control reads as switched off. See Look#FAINT_OFF.
            b.setTextColor(FAINT_OFF);
            b.setTypeface(android.graphics.Typeface.create(b.getTypeface(),
                    android.graphics.Typeface.NORMAL), android.graphics.Typeface.NORMAL);
            b.setEnabled(false);
            return;
        }
        b.setEnabled(true);
        if (on) {
            b.setBackground(roundRect(a, Look.ACCENT_DIM, Look.R_CTRL));
            b.setTextColor(ACCENT);
            b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        } else {
            b.setBackground(ctrlRect(a, SURFHI, Look.R_CTRL));
            b.setTextColor(DIM);
            b.setTypeface(android.graphics.Typeface.create(b.getTypeface(),
                    android.graphics.Typeface.NORMAL), android.graphics.Typeface.NORMAL);
        }
    }

    /**
     * An UPPERCASE MICRO-LABEL — the mockup's 10-10.5px tracked capitals that caption a
     * number or open a section. Uppercased here rather than with setAllCaps so the tracking
     * applies to the glyphs actually laid out, and always tracked: Android adds no optical
     * spacing to capitals of its own, so an untracked 10.5sp all-caps label is a smudge.
     */
    public static TextView microLabel(Activity a, ViewGroup parent, String label, int colour) {
        TextView t = new TextView(a);
        t.setText(label == null ? "" : label.toUpperCase(java.util.Locale.US));
        t.setTextColor(colour);
        t.setTextSize(Look.SP_MICRO);
        t.setLetterSpacing(Look.LABEL_TRACKING_EM);
        // The label is furniture; the value it captions is the content. Announce the words
        // as written rather than as shouted capitals.
        t.setContentDescription(A11y.collapse(label == null ? "" : label));
        if (parent != null) parent.addView(t);
        return t;
    }

    /**
     * A READ-ONLY FIGURE'S FACE: the normal sans, with TABULAR digits. See Look's type scale
     * for the line this draws - monospace stays for what is live or being edited, and a
     * value that only reports takes the app's own face, its digits still even-width so a
     * column of them lines up. Keeps whatever weight the view already has.
     */
    public static TextView tabular(TextView t) {
        android.graphics.Typeface cur = t.getTypeface();
        int style = cur == null ? android.graphics.Typeface.NORMAL : cur.getStyle();
        t.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, style));
        t.setFontFeatureSettings("tnum");
        return t;
    }

    /** Medium weight (500) — a chip's words, a field label, a gate row's figure. */
    public static TextView medium(TextView t) {
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        return t;
    }

    /** Semibold (600) where the platform can draw it (API 28+), bold below that — a fold
     *  heading has to outrank the rows it heads, and bold is the nearer of the two. */
    public static TextView semibold(TextView t) {
        if (android.os.Build.VERSION.SDK_INT >= 28)
            t.setTypeface(android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, 600, false));
        else
            t.setTypeface(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD);
        return t;
    }

    /**
     * THE UPPERCASE LABEL OVER A FIELD OR A GROUP ("NEVER EXCEED", "JUMP TO"): 12sp medium,
     * tracked, DIM. A trailing `hint` - a range such as "(−2.1 to −16.8 inHg)" - follows in
     * its own case, untracked, in the same colour, so a unit keeps its spelling ("inHg", not
     * "INHG") and the bounds read as a note on the name. Android has no letter-spacing span,
     * so a hinted label is the two words side by side in a {@link Flow}: when they do not
     * fit on one line the range moves to the next line whole instead of breaking mid-unit.
     * One focus stop either way, said as written rather than as shouted capitals.
     */
    public static View fieldLabel(Activity a, ViewGroup parent, String name, String hint) {
        TextView t = fieldLabelText(a, name == null ? "" : name.toUpperCase(java.util.Locale.US));
        t.setLetterSpacing(Look.LABEL_TRACKING_EM);
        String said = A11y.collapse((name == null ? "" : name)
                + (hint == null || hint.length() == 0 ? "" : " " + hint));
        View out = t;
        if (hint != null && hint.length() > 0) {
            Flow f = new Flow(a, dp(a, Look.S1), 0);
            f.addView(t);
            f.addView(fieldLabelText(a, hint));
            group(f, said);
            out = f;
        } else {
            t.setContentDescription(said);
        }
        if (parent != null) parent.addView(out);
        return out;
    }

    private static TextView fieldLabelText(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(DIM);
        t.setTextSize(Look.SP_FIELD_LABEL);
        medium(t);
        t.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** The near-black label a filled amber/green/teal button needs for AA contrast — a
     *  white label on amber fails. Dark fills keep the light TEXT label. Pinned as
     *  Look.meetsAA(ON_AMBER, COMMANDED, false) etc. in SelfTest. */
    public static int labelOn(int fill) {
        // SYS-1: the table lives in Look#inkOn (pure, pinned), which carries the CRIT branch
        // this lacked - a red button's label is Look.ON_RED, 5.8:1, not TEXT at 3.0:1.
        return Look.inkOn(fill);
    }

    /**
     * Press feedback: on ACTION_DOWN the button eases to {@link Look#PRESS_SCALE} (0.985)
     * scale and {@link Look#PRESS_ALPHA} (0.90) content alpha, accelerated, over
     * {@code resolveDuration(Look.MS_PRESS_IN, scale)}; on ACTION_UP/ACTION_CANCEL it eases
     * back to 1.0 scale / 1.0 alpha, decelerated, over
     * {@code resolveDuration(Look.MS_PRESS_OUT, scale)} — the one motion a control makes in
     * response to a direct tap. It NEVER consumes the event (returns false), so the click
     * still fires and accessibility is untouched (TalkBack synthesises clicks without touch,
     * so it simply gets no scale). Honours the system animation scale, so a user with
     * animations off gets an instant, motionless press.
     * A single named class, applied by big()/flat(), because there are no lambdas here.
     */
    /**
     * "Double tap to TYPE", not "double tap to activate" — the announcement a tap-to-type
     * readout carries (see {@link #stepperRow}'s valueTap overload). A named class, not a
     * lambda: this project targets Java 8 source with no lambdas anywhere.
     *
     * It replaces only the CLICK action's label. Everything else about the node — the
     * value's own contentDescription, its focusability, the ± buttons beside it — is left
     * exactly as the row built it.
     */
    public static final class TypeHint extends View.AccessibilityDelegate {
        @Override public void onInitializeAccessibilityNodeInfo(
                View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo
                .AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, "type"));
        }
    }

    /**
     * The same one-action shape as {@link TypeHint}, but the WORD is the caller's rather
     * than a hardcoded "type" — for a control whose click means something different
     * depending on the state it is already in, an accordion row toggling "expand"/
     * "collapse" as it opens and closes being the case this was written for.
     *
     * TalkBack already appends its OWN "double tap to activate" to any clickable node's
     * spoken description, so a caller that also bakes a "double tap to…" sentence into
     * the description itself gets that instruction announced TWICE. Relabelling
     * ACTION_CLICK instead keeps TalkBack's own phrasing intact and only swaps its verb,
     * which is said exactly once.
     */
    public static final class ClickHint extends View.AccessibilityDelegate {
        private final String label;
        public ClickHint(String label) { this.label = label; }
        @Override public void onInitializeAccessibilityNodeInfo(
                View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo
                .AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, label));
        }
    }

    /* ===================== R2 - PRESS AND HOLD TO REPEAT ============================
     *
     * The run screen's plus-minus chips repeated when held; nothing else in the app did. So
     * taking a duration from 60 s to 300 s was eight separate taps, and every stepper on
     * every editor and settings screen behaved differently from the four on the one screen
     * where the pump is running.
     *
     * ONE IMPLEMENTATION, ATTACHED BY THE HELPERS THEMSELVES rather than by each caller, so
     * a stepper added later cannot forget it and there is no second repeat that could drift
     * from this one in speed or in when it stops.
     *
     * DRIVEN OFF THE BUTTON'S OWN PRESSED STATE. The repeat re-posts itself while the View
     * reports pressed and simply returns when it does not, so a lifted finger, a finger slid
     * off the button and a cancelled gesture all end it by the same route. Nothing here sets
     * a flag that could be left set.
     *
     * IT DOES NOT ACCELERATE. A stepper on this screen can be a PRESSURE, and a control that
     * speeds up the longer it is held is a control that overshoots a pressure - the one place
     * an editor's convenience is allowed to cost accuracy.
     */
    public static final long STEP_REPEAT_MS = 220L;

    /** Gives a stepper button hold-to-repeat against its own click listener. Called by the
     *  helpers below; a caller never has to remember it. */
    public static void repeatOnHold(final Button b, final View.OnClickListener action) {
        if (b == null || action == null) return;
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                new StepRepeat(b, action).run();
                // TRUE, so the long press does not also fire the ordinary click on release:
                // the repeat has already delivered every step the press was worth.
                return true;
            }
        });
    }

    private static final class StepRepeat implements Runnable {
        private final Button b; private final View.OnClickListener action;
        StepRepeat(Button btn, View.OnClickListener a) { b = btn; action = a; }
        @Override public void run() {
            if (b == null || !b.isPressed() || !b.isEnabled()) return;
            action.onClick(b);
            b.postDelayed(this, STEP_REPEAT_MS);
        }
    }

    public static final class Press implements View.OnTouchListener {
        @Override public boolean onTouch(View v, android.view.MotionEvent e) {
            int a = e.getActionMasked();
            float scale;
            try {
                scale = android.provider.Settings.Global.getFloat(
                    v.getContext().getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f);
            } catch (Exception ex) { scale = 1.0f; }
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                int d = Look.resolveDuration(Look.MS_PRESS_IN, scale);
                v.animate().cancel();
                v.animate().scaleX(Look.PRESS_SCALE).scaleY(Look.PRESS_SCALE)
                    .alpha(Look.PRESS_ALPHA).setDuration(d)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .start();
            } else if (a == android.view.MotionEvent.ACTION_UP
                    || a == android.view.MotionEvent.ACTION_CANCEL) {
                int d = Look.resolveDuration(Look.MS_PRESS_OUT, scale);
                v.animate().cancel();
                v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(d)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
            }
            return false;   // never consume — the click must still fire
        }
    }

    public static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * The app's vertical column — and, since {@link #lift} gave the cards real shadows, the
     * place the app stops cropping them. A shadow is drawn OUTSIDE its view's bounds, so any
     * clipping ancestor slices it off; every column Ui builds therefore turns both clips off.
     * That is safe here because nothing in this app relies on a column clipping its children
     * (no overlapping scroller, no rounded mask): the columns are plain stacks. A screen that
     * builds its own scroll container must do the same, or the first and last card's shadows
     * will be shaved.
     */
    public static LinearLayout col(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipToPadding(false);
        l.setClipChildren(false);
        return l;
    }

    /**
     * If `label` carries one of the app's radio markers, expose the state it encodes to
     * accessibility services: setSelected() puts it on the AccessibilityNodeInfo (the
     * Android equivalent of the prototype's aria-pressed, which its own accessibility
     * pass sets on exactly these controls), and the description says it in words for a
     * reader that does not surface the flag. Labels with no marker are left untouched —
     * see A11y.MARK_ON for why the detection is deliberately narrow.
     *
     * setSelected() is safe on these buttons specifically because every one of them has
     * a flat setBackgroundColor() rather than a state-list drawable, so there is no
     * selected state for it to paint. Nothing about the drawn appearance changes here.
     */
    public static void markSelection(View v, String label) {
        String said = A11y.describeMarked(label);
        if (said == null) return;
        v.setContentDescription(said);
        v.setSelected(Boolean.TRUE.equals(A11y.markerState(label)));
    }

    /**
     * Makes a composed row read as ONE thing rather than as its parts. Without this a
     * chip announces "Seal holds" and "decay 0.02 kPa/s over 10 s" as two unrelated
     * stops, and the day strip announces a bullet and a letter; with it the group is one
     * focus stop carrying the whole sentence. The children are hidden from the service
     * rather than deleted — they are still what is drawn.
     */
    /**
     * DECORATIVE - NOT A STOP AT ALL.
     *
     * {@link #group} with an empty string was being used to mean "say nothing", but it says
     * nothing while ALSO marking the view important for accessibility and focusable: a
     * screen reader landed on a stop that announced no words, which is worse than the row
     * it was hiding. This is the request that was meant - the subtree is skipped, and the
     * words the caller is deferring to are the ones next to it.
     */
    public static void decorative(View v) {
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        v.setContentDescription(null);
    }

    public static void group(ViewGroup g, String said) {
        g.setContentDescription(said);
        g.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        // setScreenReaderFocusable is the precise request — "one stop for a screen
        // reader" — and it leaves KEYBOARD focus alone. Below API 28 the only way to
        // make a non-interactive group take accessibility focus is setFocusable, which
        // also adds a D-pad stop; that is the lesser cost, and it applies to cards and
        // chips, never to anything that was already a control.
        if (android.os.Build.VERSION.SDK_INT >= 28) g.setScreenReaderFocusable(true);
        else g.setFocusable(true);
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            // A CONTROL INSIDE A GROUP KEEPS ITS OWN STOP. Collapsing a row into one
            // sentence is right for the LABELS that make up that sentence; doing it to a
            // button removes the only way a screen-reader user could operate it, and the
            // row's own description says nothing about what that button does. This is the
            // rule the note above already states for setFocusable ("never to anything that
            // was already a control"), applied where it actually bites.
            //
            // It became load-bearing with the privacy blur: the 👁 that reveals an item
            // sits inside rows that are grouped — the trend headline, a gallery tile, a
            // Compare pick — so without this the reveal exists for sighted users only.
            if (child.isClickable()) continue;
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    public static void head(Activity a, ViewGroup parent, String t) {
        TextView v = new TextView(a);
        v.setText(t);
        v.setTextColor(TEXT);
        v.setTextSize(Look.SP_TITLE);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        v.setPadding(0, dp(a, Look.S4), 0, dp(a, Look.S3));
        // Every screen in this app is one long scroll built into the same `body`; the
        // heading is what tells a screen reader where a screen starts and lets it jump
        // by heading instead of walking every control. API 28+; below that it is simply
        // a large TextView, exactly as it was.
        if (android.os.Build.VERSION.SDK_INT >= 28) v.setAccessibilityHeading(true);
        parent.addView(v);
    }

    /**
     * The one back affordance every PUSHED screen wears — a modest "‹" chevron in a
     * 48dp rounded touch target, never a full-width block. Factored out so the header row
     * and any bespoke header (Compare, which also carries a Swap action) share exactly one
     * control: same size, same press feedback, same "Back" name to accessibility. It is a
     * pure NAVIGATION control, so it carries no state colour — TEXT on surfaceHigh, like
     * the other secondary controls it sits among. The caller supplies the listener, which
     * decides where "back" goes (and, for a staged editor, that back DISCARDS).
     */
    public static Button backArrow(Activity a, View.OnClickListener back) {
        Button b = new Button(a);
        b.setText("‹");
        b.setAllCaps(false);
        b.setTextSize(26);
        b.setTextColor(TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ctrlRect(a, SURFHI, Look.R_CTRL));
        b.setContentDescription("Back");
        // A platform Button carries a large default minWidth (often 88dp) and horizontal
        // padding; both would bloat a 48dp icon target, so they are zeroed and the size is
        // stated outright — the same "state 48 rather than inherit it" the row/flat helpers do.
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, dp(a, 2));   // optically centre the chevron in the square
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48));
        lp.rightMargin = dp(a, Look.S2);
        b.setLayoutParams(lp);
        b.setOnTouchListener(new Press());
        if (back != null) b.setOnClickListener(back);
        return b;
    }

    /**
     * The compact top row a pushed screen uses in place of head(): the back arrow at the
     * left and the screen title beside it. Replaces the full-width "Back"/"Done" buttons
     * those screens used for pure navigation, which read as oversized and inconsistent.
     *
     * The title is an accessibility heading, exactly as head()'s is, so a screen reader can
     * still jump to where the screen starts; it keeps the SP_TITLE face so a pushed screen's
     * heading matches a destination's. The row is RETURNED so a screen with a trailing action
     * (Compare's Swap) can append one to the right of the title — the title carries the
     * layout weight, so any appended control sits at the right edge.
     *
     * This is for the CALM, non-pump pushed screens only. The session-flow screens keep
     * their give-up-gated single exit; nothing here touches that.
     */
    public static LinearLayout header(Activity a, ViewGroup parent, String title,
                                       View.OnClickListener back) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(backArrow(a, back));

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(Look.SP_TITLE);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        r.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, Look.S2);
        lp.bottomMargin = dp(a, Look.S3);
        parent.addView(r, lp);
        return r;
    }

    /** A resting card. See {@link #card(Activity, ViewGroup, String, String, boolean)} for
     *  the active/"pop" variant. */
    public static void card(Activity a, ViewGroup parent, String title, String sub) {
        card(a, parent, title, sub, false);
    }

    /**
     * @param pop true for the ONE live card on a screen: the live surface, the faint lime
     *            ring and the deeper shadow the mockup gives `.card.pop`. Used sparingly —
     *            three popped cards is no cards.
     */
    public static void card(Activity a, ViewGroup parent, String title, String sub, boolean pop) {
        LinearLayout c = col(a);
        // R_CARD corner and a real shadow — the mockup's separation is DEPTH, not only
        // ground colour and space, which is what this comment used to claim.
        c.setBackground(pop ? popRing(a) : roundRect(a, SURF, Look.R_CARD));
        lift(a, c, pop ? ELEV_POP : ELEV_CARD);
        c.setPadding(dp(a, Look.S5), dp(a, Look.S5), dp(a, Look.S5), dp(a, Look.S5));
        TextView t = new TextView(a);
        // TEXT, not CMD: a card title is not the pump under command, and amber is earned.
        t.setText(title); t.setTextColor(TEXT); t.setTextSize(Look.SP_HEADING);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        TextView s = new TextView(a);
        s.setText(sub); s.setTextColor(DIM); s.setTextSize(Look.SP_CAPTION);
        s.setPadding(0, dp(a, Look.S1), 0, 0);
        s.setLineSpacing(0f, Look.PROSE_LINE_MULT);
        c.addView(t); c.addView(s);
        group(c, A11y.collapse(title) + ". " + A11y.collapse(sub));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, Look.S4);
        parent.addView(c, lp);
    }

    /**
     * A CATEGORY HEADER: a coloured left rule, a tinted glyph, and an uppercase label - the
     * Trainer's "Decision history" and "Reminders". Settings drew its categories with this
     * until they became tappable folds; it now uses {@link #foldHeader}, plain words and a
     * chevron, and its cards no longer wear category colours for this rule to match.
     *
     * THE COLOUR MARKS THE CATEGORY, NOT THE ROWS. The rule and the glyph are the only
     * coloured things; every control under the header stays neutral. A screen where each
     * row is painted its section's colour has spent the whole palette on decoration, and
     * then the one colour that must mean something — amber, the pump under pressure —
     * means nothing when it appears. AMBER IS NOT A CATEGORY COLOUR and does not appear in
     * Settings at all; the "Device & developer" category is deliberately given the dim
     * text colour so it recedes rather than competing.
     *
     * The label is a real accessibility heading (API 28+), so a screen reader can jump
     * between categories exactly as the eye now can.
     */
    public static void categoryHeader(Activity a, ViewGroup parent, String glyph,
                                       String label, int colour) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(a, Look.S6), 0, dp(a, Look.S2));

        View rule = new View(a);
        rule.setBackground(roundRect(a, colour, 2));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(a, 3), dp(a, 16));
        rp.rightMargin = dp(a, 9);
        row.addView(rule, rp);

        TextView ic = new TextView(a);
        ic.setText(glyph);
        ic.setTextColor(colour);
        ic.setTextSize(Look.SP_LABEL);
        ic.setPadding(0, 0, dp(a, 7), 0);
        row.addView(ic);

        // The uppercase category name at the micro size, tracked — the mockup's `.sh`.
        TextView t = microLabel(a, row, label, colour);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);

        // One focus stop saying the category name — not "rule, glyph, label" as three.
        group(row, label);
        if (android.os.Build.VERSION.SDK_INT >= 28) row.setAccessibilityHeading(true);
        parent.addView(row);
    }

    /**
     * A FOLD HEADING — the row that opens and closes one Settings category.
     *
     * Plain sentence-case words at SP_FOLD_HEAD, semibold, in a 48dp row with a 1dp LINE
     * rule on top and a chevron at the right: pointing right in DIM while the category is
     * closed, pointing down in ACCENT - with the words in ACCENT too - while it is open.
     *
     * WHY IT REPLACED {@link #categoryHeader} THERE. That header was a coloured rule, a
     * glyph, a ▸/▾ and a tracked uppercase label ("◷ ▸ TRAINING SCHEDULE"), which read
     * like log output rather than a heading, and its colour was one more category colour
     * the cards no longer wear. The chevron is the whole affordance now, in the same shape
     * the app's other rows that open something use.
     *
     * One focus stop, a real heading, announced as "<title>, expanded" / "collapsed", with
     * the click action relabelled "collapse"/"expand" (the run screen's upcoming rows do the
     * same) so the verb is said once, in TalkBack's own phrasing. The caller attaches the
     * listener.
     */
    public static LinearLayout foldHeader(Activity a, ViewGroup parent, String title,
                                          boolean open) {
        LinearLayout box = col(a);

        View rule = new View(a);
        rule.setBackgroundColor(LINE);
        rule.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1))));

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(a, 48));

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(open ? ACCENT : TEXT);
        t.setTextSize(Look.SP_FOLD_HEAD);
        semibold(t);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(iconView(a, open ? R.drawable.ic_chevron_down : R.drawable.ic_chevron_right,
                open ? ACCENT : DIM, 20));
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        group(box, A11y.collapse(title) + ", " + (open ? "expanded" : "collapsed"));
        if (android.os.Build.VERSION.SDK_INT >= 28) box.setAccessibilityHeading(true);
        box.setAccessibilityDelegate(new ClickHint(open ? "collapse" : "expand"));
        box.setOnTouchListener(new Press());
        if (parent != null) parent.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /**
     * A CARD THAT IS A DOOR: one line of words and a chevron at the right, the whole card
     * the target. For a row that leaves the screen it is on ("How this app works") - it
     * sits among cards, so it is drawn as one, not as a bordered control that could be
     * mistaken for a setting.
     */
    public static LinearLayout navCard(Activity a, ViewGroup parent, String label,
                                       View.OnClickListener tap) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(roundRect(a, SURF, Look.R_CARD));
        lift(a, r, ELEV_CARD);
        r.setMinimumHeight(dp(a, 48));
        r.setPadding(dp(a, Look.S5), dp(a, Look.S3), dp(a, Look.S5), dp(a, Look.S3));

        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(TEXT);
        t.setTextSize(Look.SP_BODY);
        r.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.ImageView ch = iconView(a, R.drawable.ic_chevron_right, DIM, 20);
        ((LinearLayout.LayoutParams) ch.getLayoutParams()).leftMargin = dp(a, Look.S3);
        r.addView(ch);

        group(r, A11y.collapse(label));
        r.setClickable(true);
        r.setOnTouchListener(new Press());
        if (tap != null) r.setOnClickListener(tap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, Look.S4);
        if (parent != null) parent.addView(r, lp);
        return r;
    }

    public static void note(Activity a, ViewGroup parent, String s) {
        TextView v = new TextView(a);
        v.setText(s);
        v.setTextColor(DIM);
        // THE CAPTION SIZE, FLUSH WITH WHAT IT EXPLAINS. This was 11.5sp - under the type
        // scale's own caption step - and indented 8dp, so inside a card it hung off to the
        // right of the title and the control it was the sentence for, like a footnote. The
        // approved card mock sets it at SP_CAPTION on the card's own left edge.
        v.setTextSize(Look.SP_CAPTION);
        v.setLineSpacing(0f, NOTE_LINE_MULT);
        v.setPadding(0, dp(a, Look.S2), 0, dp(a, Look.S4));
        parent.addView(v);
    }

    /** A caption's line height - a touch more open than the default, as the mock sets it,
     *  and well short of PROSE_LINE_MULT, which is for paragraphs read end to end. */
    private static final float NOTE_LINE_MULT = 1.15f;

    /**
     * THE SAME PARAGRAPH, DELIBERATELY LEFT ON THE FACE.
     *
     * Identical to {@link #note} in every pixel. The name is the whole point: it is the
     * one way past the build rule that refuses a long {@link #note}, and it says WHY the
     * exception was taken rather than leaving a reviewer to guess.
     *
     * TAKE IT ONLY FOR A FACT SOMEBODY NEEDS WITHOUT TAPPING - what STOP does, that a vent
     * is confirmed before anything moves, the ceiling, the rigid vessel, "numb or cold
     * means take it off", or anything read while a run is live or on the confirm screen
     * immediately before the pump runs. Everything else that is long goes behind a
     * {@link #noteInfo} tap, where the words are all still in the app and none of them are
     * in the way.
     *
     * Reaching for this because a paragraph is inconvenient to shorten is the failure mode
     * the rule exists to catch, and renaming the call is not an argument.
     */
    public static void noteSafety(Activity a, ViewGroup parent, String s) {
        note(a, parent, s);
    }

    /**
     * A ONE-LINE CAPTION WITH ITS PARAGRAPH BEHIND A ⓘ.
     *
     * WHY: the screens carried 155 inline {@link #note} paragraphs and four ⓘ buttons.
     * Every word of those paragraphs is correct, and almost none of it is read twice —
     * left inline it is wallpaper the eye learns to skip, and it pushes the controls the
     * screen exists for below the fold. This keeps ONE short factual line on the face and
     * puts the full paragraph one deliberate tap away.
     *
     * NOTHING IS DELETED: `full` is the original paragraph verbatim, so the words are all
     * still in the app, just not all at once. Safety text — what STOP does, that a vent
     * is confirmed before anything moves, the ceiling, the rigid vessel — stays a plain
     * {@link #note} and never moves behind a tap.
     *
     * The caption keeps note()'s colour, size and padding exactly, so a converted line
     * and an unconverted one sit at the same rhythm; the row adds only the button.
     */
    public static LinearLayout noteInfo(Activity a, ViewGroup parent, String shortLine,
                                 String infoTitle, String full, boolean safetyRelevant) {
        /* THE WHOLE ROW IS THE TARGET, AND THE GLYPH IS A GLYPH.
         *
         * This drew a 34 dp filled disc at the right margin of every explanation. One is
         * unobtrusive. The trainer's track detail stacks SIX of them, and six identical
         * discs at a shared right edge form a strong vertical line - the eye follows it down
         * the page and never reaches "Sessions 2/3 this week", which is the one number on
         * the screen that changes what you do next. The loudest thing on the screen was the
         * furniture around the content.
         *
         * The disc is now an inline mark at the end of the sentence, faint, with no fill:
         * the row itself carries the tap, so the target is LARGER than the disc ever was,
         * not smaller. One focus stop, one description, and no column.
         */
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(a, 48));
        row.setPadding(0, dp(a, Look.S2), dp(a, Look.S2), dp(a, Look.S3));

        TextView v = new TextView(a);
        v.setText(shortLine);
        v.setTextColor(DIM);
        v.setTextSize(Look.SP_CAPTION);
        v.setLineSpacing(0f, NOTE_LINE_MULT);
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView g = new TextView(a);
        g.setText("\u24d8");
        // Amber still marks the safety-relevant essays - that distinction was earned and is
        // kept; it is the DISC that goes, not the difference between the two kinds.
        // Wave 4 item 8: the (i) is BLUE (amber when safety) — FAINT was 1.20:1 from
        // the note text beside it, and the glyph was the only affordance.
        g.setTextColor(safetyRelevant ? CMD : Look.INFO_BLUE);
        g.setTextSize(Look.SP_HEADING);
        g.setPadding(dp(a, Look.S4), 0, 0, 0);
        row.addView(g);

        group(row, A11y.collapse(shortLine) + ". Opens an explanation.");
        row.setOnClickListener(new InfoTap(a, infoTitle, full));
        row.setOnTouchListener(new Press());
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Returned so a caller can re-ink the line (its first child) where the line itself
        // is a warning; every other caller ignores it.
        return row;
    }

    /** {@link #noteInfo(Activity, ViewGroup, String, String, String, boolean)}; not
     *  safety-relevant. */
    public static LinearLayout noteInfo(Activity a, ViewGroup parent, String shortLine,
                                 String infoTitle, String full) {
        return noteInfo(a, parent, shortLine, infoTitle, full, false);
    }

    /** The dp size of an info button — the app's 48dp touch-target floor, stated once so
     *  every call site lays one out the same. */
    public static final int INFO_DP = 48;

    /**
     * THE ⓘ BUTTON. A modest glyph that opens the long explanation the screen used to
     * print inline, as a dialog: title, the text verbatim, and OK.
     *
     * WHY: Today carried three multi-line explainers permanently on the face. Every word
     * of them is correct and reviewed — what SESSIONS and TOTAL TIME actually count, that
     * the ceiling clamps rather than refuses, that STOP vents and there is no pause — and
     * none of it is read twice. Left inline it is wallpaper the eye learns to skip, which
     * is the worst place for a safety fact to end up. Behind a ⓘ it is one deliberate tap
     * away, and the screen keeps the numbers.
     *
     * It is a REAL button, not a glyph on a TextView: 48dp (the caller lays it out at
     * INFO_DP, which is why the size is published here), press feedback like every other
     * control, and a contentDescription that names the subject rather than the shape —
     * "About the safety ceiling", never "circled information source".
     *
     * The dialog is built at TAP TIME by a named listener, not captured in a lambda —
     * this build's android.jar has no LambdaMetafactory — and its OK button takes a null
     * listener, which is the framework's own "dismiss and do nothing".
     */
    public static Button infoButton(Activity a, String about, String title, String text,
                                     boolean safetyRelevant) {
        Button b = new Button(a);
        b.setText("ⓘ");
        b.setAllCaps(false);
        b.setTextSize(Look.SP_HEADING);
        // FAINT on SURFACEHI would fail AA; DIM is the app's secondary and clears it. The
        // glyph is DIM for the ~50 plain explainers, which claim nothing about the pump.
        // Safety-relevant essays (ceiling, vent evidence, seal check, developer gate) get an
        // amber glyph so they read as a different weight of information — CMD is legal here
        // per Look's own rule (amber = pump may be under pressure OR the safety consequence
        // of a control) since every safety-relevant ⓘ in this app explains exactly such a
        // consequence.
        b.setTextColor(safetyRelevant ? CMD : DIM);
        b.setBackground(roundRect(a, SURFHI, INFO_DP / 2));
        b.setPadding(0, 0, 0, 0);
        b.setContentDescription("About " + A11y.collapse(about));
        b.setOnTouchListener(new Press());
        b.setOnClickListener(new InfoTap(a, title, text));
        return b;
    }

    /** {@link #infoButton(Activity, String, String, String, boolean)}; not safety-relevant. */
    public static Button infoButton(Activity a, String about, String title, String text) {
        return infoButton(a, about, title, text, false);
    }

    /** {@link #infoButton} laid out at INFO_DP square and added to `parent`. */
    public static Button infoButton(Activity a, ViewGroup parent, String about,
                                     String title, String text, boolean safetyRelevant) {
        Button b = infoButton(a, about, title, text, safetyRelevant);
        parent.addView(b, new LinearLayout.LayoutParams(dp(a, INFO_DP), dp(a, INFO_DP)));
        return b;
    }

    /** {@link #infoButton(Activity, ViewGroup, String, String, String, boolean)}; not
     *  safety-relevant. */
    public static Button infoButton(Activity a, ViewGroup parent, String about,
                                     String title, String text) {
        return infoButton(a, parent, about, title, text, false);
    }

    /**
     * A VALUE ROW WITH ITS EXPLANATION BEHIND A \u24d8 (polish, the info-button moves): a
     * {@link #kvRow} whose label ends in the same plain blue \u24d8 {@link #noteInfo} draws, and
     * whose tap opens that explanation - for a row whose caption used to sit under it ("Rest
     * between holds", "Training volume"). One target, one focus stop.
     */
    public static LinearLayout kvInfoRow(Activity a, ViewGroup parent, String label,
                                         CharSequence value, int valueColour,
                                         String infoTitle, String full) {
        LinearLayout r = kvRow(a, parent, label, value, valueColour, new InfoTap(a, infoTitle, full));
        TextView g = new TextView(a);
        g.setText("\u24d8");
        g.setTextColor(Look.INFO_BLUE);
        g.setTextSize(Look.SP_HEADING);
        g.setPadding(dp(a, Look.S3), 0, 0, 0);
        r.addView(g, 1);
        group(r, A11y.collapse(label) + ": " + A11y.collapse(String.valueOf(value))
            + ". Opens an explanation.");
        return r;
    }

    /** Opens one info button's dialog. A named class because there are no lambdas here. */
    private static final class InfoTap implements View.OnClickListener {
        private final Activity a;
        private final String title, text;
        InfoTap(Activity a, String title, String text) {
            this.a = a; this.title = title; this.text = text;
        }
        @Override public void onClick(View v) {
            if (a.isFinishing()) return;
            android.app.AlertDialog d = dialog(a)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
            dress(a, d);
            // setMessage() gives Builder no direct TextView handle before show(); the
            // platform alert layout is only reachable through its own id once the window
            // exists. PROSE_LINE_MULT matches the line-height every other block of prose
            // in the app carries — an AlertDialog's message defaults to single-spacing.
            TextView msgView = d.findViewById(android.R.id.message);
            if (msgView != null) msgView.setLineSpacing(0f, Look.PROSE_LINE_MULT);
        }
    }

    /* ---------------------------------------------------------------------------
     * DIALOGS.
     *
     * The app declares no XML theme of its own, so a bare `new AlertDialog.Builder(a)`
     * inherits the platform's LIGHT alert theme: a white card with blue buttons dropped
     * into the middle of a black instrument app. Every dialog in the app is built through
     * dialog() so it starts from the device's DARK alert theme instead, and is then
     * dressed after show() — the window background is only reachable once a Window
     * exists, which is after show().
     *
     * dialog() + dress()  — a confirm or an explainer: a centred card at R_SHEET.
     * dialog() + sheet()  — a picker/adjust surface: the same card pinned to the BOTTOM
     *                       edge, so it reads as the bottom sheet the mockup draws.
     *
     * None of this changes a single button, string or callback: it is the frame only.
     * --------------------------------------------------------------------------- */

    /** The one way to start an AlertDialog in this app: the device's dark alert theme. */
    public static android.app.AlertDialog.Builder dialog(Activity a) {
        return new android.app.AlertDialog.Builder(
                a, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    /** Call after show(). Gives the dialog the app's surface colour, the sheet radius, a
     *  deeper scrim than the platform default, and full width so it is not a narrow chip. */
    /**
     * THE DIALOG THAT IS CURRENTLY UP, if any.
     *
     * Every dialog in the app is dressed here - it is the project's single funnel - so this
     * is the one place that can answer "is the person in the middle of something modal?"
     * without each caller remembering to say so.
     *
     * It exists for the end of a run. A routine that finishes while a dialog is open used to
     * paint the summary underneath it, leaving a dialog floating over a screen that no
     * longer exists and whose OK would write into it. Held weakly in effect - the reference
     * is dropped as soon as the dialog is not showing - so a dismissed dialog cannot keep an
     * Activity alive through this field.
     */
    private static android.app.AlertDialog lastDressed;

    /** True while a dialog raised through {@link #dress} is on screen. */
    public static boolean dialogShowing() {
        if (lastDressed == null) return false;
        if (lastDressed.isShowing()) return true;
        lastDressed = null;
        return false;
    }

    /** Takes down whatever {@link #dialogShowing} is reporting. */
    public static void dismissDialog() {
        if (lastDressed == null) return;
        try { if (lastDressed.isShowing()) lastDressed.dismiss(); }
        catch (IllegalArgumentException e) { /* window already gone */ }
        lastDressed = null;
    }

    public static void dress(Activity a, android.app.AlertDialog d) {
        if (d == null) return;
        lastDressed = d;
        // The debug log records every dialog's opening, closing and touches - dress() is
        // the one place every dialog passes through.
        if (a instanceof SessionActivity) ((SessionActivity) a).journalDialog(d);
        android.view.Window w = d.getWindow();
        if (w == null) return;
        // (the incognito safety review, M6) A dialog is its own window: with "Hide from recent
        // apps" on it carries FLAG_SECURE too (SessionActivity#applySecureFlag, invariant 193).
        if (a instanceof SessionActivity) ((SessionActivity) a).applySecureFlag(w);
        w.setBackgroundDrawable(roundRect(a, Look.SURFACE, Look.R_SHEET));
        w.setDimAmount(0.62f);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        insetBareBody(a, d);
        dressButtons(d);
        dressTitle(a, d);
    }

    /**
     * THE TITLE READS AS A CARD'S TITLE (polish DG-3): the platform's regular-weight title sat
     * above bold card titles. Semibold, in TEXT, at the screen-head size - the title view the
     * alert layout already has, so nothing about the dialog's structure changes. Only exists
     * once shown; before that there is nothing to style.
     */
    private static void dressTitle(Activity a, android.app.AlertDialog d) {
        int id = a.getResources().getIdentifier("alertTitle", "id", "android");
        if (id == 0) return;
        View v = d.findViewById(id);
        if (!(v instanceof TextView)) return;
        TextView t = (TextView) v;
        t.setTextColor(TEXT);
        t.setTextSize(Look.SP_TITLE);
        semibold(t);
    }

    /**
     * THE DIALOG'S OWN BUTTONS SAY WHICH ONE IS THE ANSWER (polish DG-2).
     *
     * Cancel/Start, Close/Undo/Run it now and Not now/Save all came up as equal light-blue
     * platform text buttons, so the primary action had no emphasis at all. Positive: ACCENT,
     * bold - and the unavailable ink when it is disabled, from a state list, so a Save that
     * cannot be pressed looks it without an alpha hack. Negative and neutral: DIM. The
     * platform already orders them neutral, negative, positive left to right.
     *
     * Only the colour, weight and case change. A caller that recolours a button after
     * dress() (the measure sheet's "How to take each", a destructive confirm through
     * {@link #dangerPositive}) still wins, because it runs later. Buttons exist only once the
     * dialog is shown; dressed before that, there is nothing to style and nothing is done.
     */
    private static void dressButtons(android.app.AlertDialog d) {
        Button pos = d.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (pos != null) {
            pos.setAllCaps(false);
            pos.setTextColor(new android.content.res.ColorStateList(
                new int[][] { new int[] { -android.R.attr.state_enabled }, new int[] { } },
                new int[] { FAINT_OFF, ACCENT }));
            pos.setTypeface(pos.getTypeface(), android.graphics.Typeface.BOLD);
        }
        int[] quiet = { android.content.DialogInterface.BUTTON_NEGATIVE,
                        android.content.DialogInterface.BUTTON_NEUTRAL };
        for (int i = 0; i < quiet.length; i++) {
            Button b = d.getButton(quiet[i]);
            if (b == null) continue;
            b.setAllCaps(false);
            b.setTextColor(DIM);
        }
    }

    /**
     * A CONFIRM WHOSE ANSWER DESTROYS SOMETHING: the positive button in CRIT ink, bold. Call
     * after dress() - "Erase all data?" answered by a lime "Erase" would read as the app's
     * recommended action.
     */
    public static void dangerPositive(android.app.AlertDialog d) {
        if (d == null) return;
        Button pos = d.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (pos == null) return;
        pos.setTextColor(CRIT);
        pos.setTypeface(pos.getTypeface(), android.graphics.Typeface.BOLD);
    }

    /**
     * THE PLATFORM PICKERS, DARK (polish SYS-14). A DatePickerDialog or TimePickerDialog built
     * with no theme comes up in the platform's LIGHT theme - a white calendar dropped into a
     * black instrument app (seven of them: the deload sheet, the measure log and edit, the
     * training and measuring times). These build it on the same dark alert theme as
     * {@link #dialog}; show it with {@link #showPicker}, which dresses it like every other
     * dialog. Not shown here, so a caller can still set a max date first.
     */
    public static android.app.DatePickerDialog datePicker(Activity a,
            android.app.DatePickerDialog.OnDateSetListener set, int year, int month, int day) {
        return new android.app.DatePickerDialog(
                a, android.R.style.Theme_DeviceDefault_Dialog_Alert, set, year, month, day);
    }

    /** The time picker on the dark alert theme - see {@link #datePicker}. */
    public static android.app.TimePickerDialog timePicker(Activity a,
            android.app.TimePickerDialog.OnTimeSetListener set, int hour, int minute,
            boolean is24Hour) {
        return new android.app.TimePickerDialog(
                a, android.R.style.Theme_DeviceDefault_Dialog_Alert, set, hour, minute, is24Hour);
    }

    /** Shows a picker from {@link #datePicker} / {@link #timePicker} and dresses it. */
    public static void showPicker(Activity a, android.app.AlertDialog picker) {
        if (picker == null) return;
        picker.show();
        dress(a, picker);
    }

    /**
     * A BODY BUILT WITH NO SIDE INSET GETS THE TITLE'S (t10 device walk, M1).
     *
     * The platform pads its own title, message and buttons, but a view handed to setView() is
     * laid edge to edge. Most custom bodies pad themselves; the ones that did not - a plain
     * Ui.col() of notes and flat buttons, such as "Your sessions were updated" and "Time for a
     * deload week" - put their text at x = 0 and cut their buttons' outlines at both screen
     * edges. So the funnel every dialog passes through gives a column (or the one column inside
     * a scroller) that has NO horizontal padding of its own the same inset as the title above
     * it. A body that already pads itself is left exactly as it was, and so is anything that is
     * not a plain column (a text field keeps its own field padding).
     */
    private static void insetBareBody(Activity a, android.app.AlertDialog d) {
        View custom = d.findViewById(android.R.id.custom);
        if (!(custom instanceof ViewGroup) || ((ViewGroup) custom).getChildCount() != 1) return;
        View body = ((ViewGroup) custom).getChildAt(0);
        if (body instanceof android.widget.ScrollView
                && ((android.widget.ScrollView) body).getChildCount() == 1)
            body = ((android.widget.ScrollView) body).getChildAt(0);
        if (!(body instanceof LinearLayout)) return;
        if (body.getPaddingLeft() != 0 || body.getPaddingRight() != 0) return;
        int side = dp(a, 24);
        android.util.TypedValue tv = new android.util.TypedValue();
        if (d.getContext().getTheme().resolveAttribute(
                android.R.attr.dialogPreferredPadding, tv, true)
                && tv.type == android.util.TypedValue.TYPE_DIMENSION) {
            side = android.util.TypedValue.complexToDimensionPixelSize(tv.data,
                a.getResources().getDisplayMetrics());
        }
        body.setPadding(side, body.getPaddingTop(), side, body.getPaddingBottom());
    }

    /** dress(), then pin the dialog to the bottom edge with a small inset — the picker and
     *  adjust surfaces read as bottom sheets, which is where a thumb already is. */
    public static void sheet(Activity a, android.app.AlertDialog d) {
        dress(a, d);
        if (d == null) return;
        android.view.Window w = d.getWindow();
        if (w == null) return;
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.BOTTOM;
        lp.y = dp(a, Look.S3);
        w.setAttributes(lp);
    }

    /** Adds the button to `parent` and returns it, so the caller can still attach its
     *  own click listener afterwards — attaching a listener after addView is fine. */
    public static Button big(Activity a, ViewGroup parent, String label, int colour) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(Look.SP_HEADING);
        // Label colour tracks the fill so amber/green/teal keep their AA contrast; a dark
        // fill keeps the light TEXT label.
        b.setTextColor(labelOn(colour));
        b.setBackground(roundRect(a, colour, Look.R_CTRL));
        /* IT GROWS, IT DOES NOT CLIP. This was a FIXED 52 dp, so any label that wrapped to
         * two lines had its second line cut off by the button's own edge - and on the one
         * button that starts a session the label is "Start - <routine> - <pressure>", so
         * the half that fell off the bottom was the pressure. A control that arms a pump
         * must never hide what it is about to do.
         *
         * 52 dp is now a MINIMUM. One-line buttons are pixel-identical to what they were;
         * a two-line label makes the button taller instead of losing half of itself. */
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, Look.S3); lp.bottomMargin = dp(a, Look.S3);
        b.setMinHeight(dp(a, 52));
        b.setPadding(dp(a, Look.S4), dp(a, Look.S3), dp(a, Look.S4), dp(a, Look.S3));
        b.setLayoutParams(lp);
        b.setOnTouchListener(new Press());
        // The screen's primary action is the one control that sits ABOVE the cards.
        lift(a, b, ELEV_CARD);
        parent.addView(b);
        return b;
    }

    /**
     * THE SAME BUTTON WITH A SECOND LINE UNDER ITS LABEL - an action, then the detail of
     * what that action will do.
     *
     * "Start - Trainer - Length L1 - 5x2min @ 5.9 inHg" is four facts on one line, and it
     * wrapped. Two of those facts are WHICH ROUTINE and the other two are WHAT IT WILL DO,
     * so they are two lines: the verb and the name at full size, the prescription smaller
     * beneath it. The whole thing is still one control, one focus stop and one tap.
     */
    public static Button bigTwoLine(Activity a, ViewGroup parent, String main, String detail,
                                     int colour) {
        Button b = big(a, parent, main, colour);
        android.text.SpannableStringBuilder sb =
            new android.text.SpannableStringBuilder(main + "\n" + detail);
        int at = main.length() + 1;
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.72f), at, sb.length(),
                   android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // The detail is the same ink at 72% opacity rather than a second colour: it is the
        // same sentence continued, not a different kind of claim.
        sb.setSpan(new android.text.style.ForegroundColorSpan(
                       (0xB8 << 24) | (labelOn(colour) & 0x00FFFFFF)),
                   at, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setText(sb);
        b.setContentDescription(A11y.collapse(main + ". " + detail));
        return b;
    }

    /**
     * Deliberately does NOT call markSelection(): unlike row(), this helper is handed
     * user-typed text (a set's name, a note), and a set named "● warm-up" would be
     * announced as selected when it is nothing of the sort. The two flat rows that do
     * carry selection — the routine editor's open stage and the picker's ticked sets —
     * set their own description on the Button returned here, from state they hold rather
     * than from a glyph they printed.
     */
    public static Button flat(Activity a, ViewGroup parent, String label) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(Look.SP_LABEL);
        b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        b.setTextColor(TEXT);
        // Secondary: surfaceHigh fill, TEXT label, rounded like the cards it sits among.
        b.setBackground(ctrlRect(a, SURFHI, Look.R_CTRL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, Look.S2);
        b.setLayoutParams(lp);
        b.setPadding(dp(a, Look.S5), dp(a, Look.S4), dp(a, Look.S5), dp(a, Look.S4));
        lift(a, b, ELEV_CARD);
        // 48dp explicitly, not left to whatever minHeight the platform Button style
        // happens to carry: this is a WRAP_CONTENT row whose measured height is 13sp of
        // text plus padding, which lands under the floor at default font scale. row() and
        // stepperRow() already state 48 outright; this one relied on a default.
        b.setMinHeight(dp(a, 48));
        b.setOnTouchListener(new Press());
        // A NULL PARENT MEANS "build it, do not place it" — the same contract kvRow()
        // already has. The caller then owns the placement, which is what a row that has to
        // share its line with something else (the privacy blur's 👁) needs.
        if (parent != null) parent.addView(b);
        return b;
    }

    /**
     * A CARD'S SECONDARY ACTION, CENTRED (polish SYS-4): "Later", "No, I just missed them",
     * "Not now", "Start without waiting". Ui.flat is left-aligned because it is a list row,
     * and a secondary under a centred Ui.big sat off-axis from the primary it answers. Same
     * fill, size and 48dp floor as flat; only the words are centred. Use flat for navigation
     * and list rows (which carry the chevron icon), this for actions on a card.
     */
    public static Button secondary(Activity a, ViewGroup parent, String label) {
        Button b = flat(a, parent, label);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    /**
     * A DESTRUCTIVE ACTION (polish SYS-12): a centred secondary with CRIT ink - "Delete this
     * reading…", "Erase all data…", "Pause the plan…". `confirms` true when the tap asks
     * before it acts, which puts the "…" on the label (Say#dangerLabel). Red FILLED buttons
     * (Ui.big with CRIT) are kept for vent and STOP only: a delete is never the screen's
     * primary act. CRIT on SURFACEHI is 5.4:1.
     */
    public static Button danger(Activity a, ViewGroup parent, String label, boolean confirms) {
        Button b = secondary(a, parent, Say.dangerLabel(label, confirms));
        b.setTextColor(CRIT);
        return b;
    }

    /**
     * ONE DISABLED LOOK (polish SYS-10). A button that cannot be pressed takes the
     * unavailable fill and ink from {@link #stateFill} - not an alpha (0.4, 0.45 and 0.55 were
     * all in use), which also faded the label under AA and did not tell a reader anything.
     * Enabling it again puts back `fill` - the colour it was built with: Ui.big's fill (its
     * label ink from {@link #labelOn}), or SURFHI for a flat / secondary (TEXT ink).
     */
    public static void setEnabled(Activity a, Button b, boolean enabled, int fill) {
        setEnabled(a, b, enabled, fill, fill == SURFHI ? TEXT : labelOn(fill));
    }

    /** {@link #setEnabled} with the enabled ink named - {@link #danger} is
     *  (SURFHI, CRIT). */
    public static void setEnabled(Activity a, Button b, boolean enabled, int fill, int ink) {
        if (b == null) return;
        if (!enabled) { stateFill(a, b, false, false); return; }
        b.setEnabled(true);
        b.setBackground(fill == SURFHI ? ctrlRect(a, SURFHI, Look.R_CTRL)
                                       : roundRect(a, fill, Look.R_CTRL));
        b.setTextColor(ink);
    }

    /**
     * CHIPS THAT FLOW: children laid left to right at their own width, wrapping onto as
     * many lines as they need. {@link #row} gives every button an equal share of one line,
     * which is right for "Cancel / Add" and wrong for a list of names: a long name wrapped
     * INSIDE its button and made that line of chips two different heights. Here each chip
     * keeps its words on one line and the LINE breaks instead.
     *
     * `vGapPx` may be negative: a chip that draws 36dp inside a 48dp touch box (see
     * {@link #pill}) has 6dp of empty target above and below it, and a −4dp line gap lets
     * the drawn chips sit 8dp apart while every box still measures the full 48.
     */
    public static final class Flow extends ViewGroup {
        private final int hGap, vGap;
        public Flow(Activity a, int hGapPx, int vGapPx) {
            super(a);
            hGap = hGapPx; vGap = vGapPx;
            setClipChildren(false);
            setClipToPadding(false);
        }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            int avail = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
            boolean bounded = MeasureSpec.getMode(wSpec) != MeasureSpec.UNSPECIFIED;
            int childW = bounded ? MeasureSpec.makeMeasureSpec(Math.max(0, avail),
                    MeasureSpec.AT_MOST) : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int x = 0, y = 0, lineH = 0, widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                int childH = lp != null && lp.height > 0
                        ? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                        : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                c.measure(childW, childH);
                int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
                if (x > 0 && bounded && x + cw > avail) { y += lineH + vGap; x = 0; lineH = 0; }
                widest = Math.max(widest, x + cw);
                x += cw + hGap;
                lineH = Math.max(lineH, ch);
            }
            int w = widest + getPaddingLeft() + getPaddingRight();
            int h = y + lineH + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int avail = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
                if (x > 0 && x + cw > avail) { y += lineH + vGap; x = 0; lineH = 0; }
                int left = getPaddingLeft() + x, top = getPaddingTop() + y;
                c.layout(left, top, left + cw, top + ch);
                x += cw + hGap;
                lineH = Math.max(lineH, ch);
            }
        }
    }

    /**
     * A ONE-LINE CHIP: fully rounded SURFACEHI, SP_CHIP medium words in TEXT, drawn 36dp
     * tall inside a 48dp touch box - the extra 6dp above and below is target, not paint.
     * Never wraps: the words stay on one line and a {@link Flow} breaks the line instead.
     * `said` is what a reader hears, so a chip can print a short name ("Schedule") and
     * still announce the whole one.
     */
    public static Button pill(Activity a, String label, String said, View.OnClickListener tap) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextSize(Look.SP_CHIP);
        b.setTextColor(TEXT);
        medium(b);
        int inset = dp(a, 6);
        b.setBackground(new android.graphics.drawable.InsetDrawable(
                roundRect(a, SURFHI, 18), 0, inset, 0, inset));
        // A platform Button brings an 88dp minimum width, a minimum height and its own
        // padding; all three are stated outright so the chip is exactly as wide as its words.
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(dp(a, 13), 0, dp(a, 13), 0);
        b.setGravity(Gravity.CENTER);
        b.setStateListAnimator(null);
        b.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 48)));
        if (said != null) b.setContentDescription(said);
        b.setOnTouchListener(new Press());
        if (tap != null) b.setOnClickListener(tap);
        return b;
    }

    /**
     * A row of equal-width flat buttons, each wired to its own listener.
     *
     * Many of these rows are radio groups whose chosen option is marked "● " and whose
     * others are marked "○ " — the pressure unit, the measurement cadence, Fixed/Ramp,
     * the stage colour, "taken under a hold". markSelection() reads that marker and
     * exposes the state to accessibility services, so the choice is no longer carried by
     * a glyph and a colour alone. Rows without markers ("Duplicate"/"Rename",
     * "Cancel"/"Add") are left exactly as they read now.
     */
    public static Button[] row(Activity a, ViewGroup parent, String[] labels,
                            View.OnClickListener[] listeners) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        /* A2 - THE SAME DEFECT THE ROUTINE CARD HAD, in the helper every screen uses.
         *
         * A horizontal LinearLayout baseline-aligns its children by default. When one label
         * in a row wraps to two lines and its neighbours do not, the one-liners are pushed
         * DOWN to line their text up and end up SHORT of the 48dp declared three lines below
         * - measured at 46.9dp on the device, in the same row as siblings measuring 48.0.
         * The declared minimum was never wrong and no source check could have seen it; the
         * row was taking it back afterwards. dist/targets.sh is what found it. */
        r.setBaselineAligned(false);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button[] made = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(a);
            b.setText(labels[i]);
            markSelection(b, labels[i]);
            b.setAllCaps(false);
            b.setTextSize(Look.SP_LABEL);
            b.setTextColor(TEXT);
            b.setBackground(ctrlRect(a, SURFHI, Look.R_CTRL));
            // THE STATE, PAINTED. A11y.markerState() already told markSelection() above
            // whether this option is on; until now that answer went only to the screen
            // reader and the drawn button looked identical either way, so the ● / ○ glyph
            // was the sole signal. Rows with no marker ("Cancel"/"Add") are untouched.
            Boolean on = A11y.markerState(labels[i]);
            if (on != null) stateFill(a, b, on.booleanValue(), true);
            b.setOnTouchListener(new Press());
            if (listeners != null && i < listeners.length && listeners[i] != null)
                b.setOnClickListener(listeners[i]);
            // 48dp is Android's minimum touch target, and it binds every screen that
            // uses this helper — it was 46dp, which is under the floor everywhere at once.
            // A2 - see the note on the stepper rows: a minimum, so a large font grows the
            // button instead of being cut off by it.
            b.setMinHeight(dp(a, 48));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            // Every button but the LAST gets the gap — for the two-button rows this is
            // exactly what it always was (index 0 only); a three-button row is now evenly
            // spaced instead of gluing its second and third buttons together.
            lp.rightMargin = (i < labels.length - 1) ? dp(a, Look.S2) : 0;
            r.addView(b, lp);
            made[i] = b;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, Look.S3);
        parent.addView(r, lp);
        return made;
    }

    /**
     * AN IN-PAGE TAB STRIP (Stage B task 9) — the new primitive Progress needed to split
     * one long scroll into TRENDS/PHOTOS/SESSIONS, since nothing like it existed anywhere
     * in this codebase before. N equal-width cells, one selected.
     *
     * DELIBERATELY NOT periodRow()/metricRow()'s grammar. Those mark a SELECTION — SURFHI
     * (or lime-wash) FILL, lime ink on the chosen one — because choosing a period or a
     * metric is picking one option among several that all describe the SAME page. A tab
     * strip picks which PAGE SECTION is showing, a different kind of choice, and the two
     * must never look interchangeable: a filled pill here would make "which section" read
     * as "which filter". So selection here is an UNDERLINE — lime text and a thin lime rule
     * under the selected cell, DIM text and no rule under the rest — the grammar an
     * underlined nav link has always used, not the grammar a chosen filter chip uses.
     *
     * Selection is exposed to accessibility exactly as the bottom tab bar's own
     * markTabSel() does it — setSelected() plus {@link A11y#state}'s "<label>, selected" /
     * "<label>, not selected" — rather than through a radio marker, since these labels
     * ("TRENDS", "PHOTOS") carry none of the app's ●/○ markers for {@link #markSelection}
     * to read.
     */
    public static LinearLayout tabStrip(Activity a, ViewGroup parent, String[] labels,
                                         int selectedIndex, View.OnClickListener[] listeners) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            boolean on = i == selectedIndex;
            LinearLayout cell = col(a);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            // This app's touch-target floor, everywhere — the row is the target, not just
            // the text inside it.
            cell.setMinimumHeight(dp(a, 48));

            TextView t = new TextView(a);
            // Uppercased here rather than with setAllCaps, so the tracking below applies to
            // the glyphs actually laid out — the same reason microLabel() does it this way;
            // setAllCaps alone sets tighter than the mockup at this size (Android adds no
            // optical tracking of its own to capitals).
            t.setText(labels[i] == null ? "" : labels[i].toUpperCase(java.util.Locale.US));
            t.setTextSize(Look.SP_LABEL);
            t.setLetterSpacing(Look.LABEL_TRACKING_EM);
            t.setTextColor(on ? ACCENT : DIM);
            t.setTypeface(t.getTypeface(),
                on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            t.setPadding(0, dp(a, Look.S3), 0, dp(a, Look.S2));
            cell.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View underline = new View(a);
            underline.setBackgroundColor(on ? ACCENT : android.graphics.Color.TRANSPARENT);
            // Decoration, never content — group() below already hides it from a screen
            // reader (it is not clickable), this just keeps a D-pad from ever landing on it.
            underline.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(underline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, Look.TAB_UNDERLINE_DP)));

            cell.setOnTouchListener(new Press());
            if (listeners != null && i < listeners.length && listeners[i] != null)
                cell.setOnClickListener(listeners[i]);
            cell.setSelected(on);
            // One focus stop per tab — "TRENDS, selected" — not two ("TRENDS" from the
            // label, then the cell), the same collapse galleryTile()'s clickable cell uses.
            group(cell, A11y.state(labels[i], on));

            row.addView(cell, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(a, Look.S3);
        if (parent != null) parent.addView(row, rowLp);
        return row;
    }

    /**
     * A SEGMENTED CONTROL: one choice among two or three, drawn as ONE control rather than
     * as a row of separate buttons (polish item 17 - the run screen's "This set | This rep").
     *
     * The track is SURFACEHI, 40 dp tall, inset 3 dp around the segments; the chosen segment
     * is a SURFACE pill 34 dp tall with a 1 dp LINE ring; labels are 14 sp medium, TEXT when
     * chosen and DIM otherwise. Every segment is a full 48 dp touch target - the drawn
     * track sits inside it - and wears Ui.Press like every other control.
     *
     * The choice is never colour or fill alone: each segment is setSelected() and is read as
     * "<label>, selected" / "<label>, not selected" (A11y#state - the tab strip's grammar),
     * followed by `said[i]` when given, the sentence saying what the choice does.
     *
     * The caller owns the state: a tap runs `taps[i]`, and the caller calls
     * {@link Segmented#select} with whatever it then holds - so a choice that is refused, or
     * reset from elsewhere (a new set resets the run screen's scope), is drawn as it is.
     *
     * TWO EXTENSIONS for the Progress chart (polish item 14), through the longer overload:
     *   - selectedInk: the chosen segment's words in a colour of its own, per segment (null
     *     or short: TEXT). Length/Girth inks the chosen word lime or violet, the two domain
     *     colours, so the switch and the line it selects read as one thing.
     *   - small: the compact form that sits beside a caption - a 34 dp track around 30 dp
     *     segments, 13 sp, each segment as wide as its words - still 48 dp to touch.
     * The shorter overload is exactly the run screen's control, unchanged.
     */
    public static final class Segmented {
        public final LinearLayout view;
        private final Activity a;
        private final TextView[] segs;
        private final String[] labels, said;
        private final int[] ink;
        private final boolean small;
        private int chosen = -1;

        private Segmented(Activity a, LinearLayout view, TextView[] segs, String[] labels,
                          String[] said, int[] ink, boolean small) {
            this.a = a; this.view = view; this.segs = segs; this.labels = labels; this.said = said;
            this.ink = ink; this.small = small;
        }

        /** The segment drawn as chosen. */
        public int selected() { return chosen; }

        /** Draws segment `i` as the chosen one and every other as not chosen. */
        public void select(int i) {
            chosen = i;
            for (int k = 0; k < segs.length; k++) {
                boolean on = k == i;
                TextView s = segs[k];
                s.setSelected(on);
                int chosenInk = ink != null && k < ink.length ? ink[k] : TEXT;
                s.setTextColor(on ? chosenInk : DIM);
                if (on) {
                    android.graphics.drawable.GradientDrawable pill =
                        roundRect(a, SURF, small ? SEG_SMALL_RADIUS_DP : Look.R_PILL);
                    pill.setStroke(Math.max(1, dp(a, 1)), LINE);
                    // 34 dp drawn inside the 48 dp target (30 dp for the small form).
                    int inset = small ? 9 : 7;
                    s.setBackground(new android.graphics.drawable.InsetDrawable(pill,
                        0, dp(a, inset), 0, dp(a, inset)));
                } else {
                    s.setBackground(null);
                }
                // A background brings its own padding (an InsetDrawable's is its insets), so
                // the small form's side padding is stated again after every change of fill,
                // or the chosen word would sit hard against its pill.
                if (small) s.setPadding(dp(a, 12), 0, dp(a, 12), 0);
                String d = A11y.state(labels[k], on);
                if (said != null && k < said.length && said[k] != null) d += ". " + said[k];
                s.setContentDescription(d);
            }
        }
    }

    public static Segmented segmented(Activity a, ViewGroup parent, String[] labels,
                                      String[] said, int selected,
                                      View.OnClickListener[] taps) {
        return segmented(a, parent, labels, said, selected, taps, null, false);
    }

    /** {@link #segmented} with a per-segment chosen ink and the small form - see
     *  {@link Segmented}'s doc for both. */
    public static Segmented segmented(Activity a, ViewGroup parent, String[] labels,
                                      String[] said, int selected,
                                      View.OnClickListener[] taps, int[] selectedInk,
                                      boolean small) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // The 40 dp track drawn inside the 48 dp row, 3 dp around the segments; the small
        // form's 34 dp track sits 2 dp around its 30 dp segments.
        int trackInset = small ? 7 : 4, gap = small ? 2 : 3;
        row.setBackground(new android.graphics.drawable.InsetDrawable(
            roundRect(a, SURFHI, small ? Look.R_PILL : Look.R_CTRL),
            0, dp(a, trackInset), 0, dp(a, trackInset)));
        row.setPadding(dp(a, gap), 0, dp(a, gap), 0);
        TextView[] segs = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView s = new TextView(a);
            s.setText(labels[i]);
            s.setTextSize(small ? 13f : Look.SP_CHIP);
            medium(s);
            s.setGravity(Gravity.CENTER);
            s.setSingleLine(true);
            s.setClickable(true);
            s.setFocusable(true);
            s.setOnTouchListener(new Press());
            if (taps != null && i < taps.length && taps[i] != null) s.setOnClickListener(taps[i]);
            LinearLayout.LayoutParams lp;
            if (small) {
                lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 48));
                if (i > 0) lp.leftMargin = dp(a, gap);
            } else {
                lp = new LinearLayout.LayoutParams(0, dp(a, 48), 1f);
            }
            row.addView(s, lp);
            segs[i] = s;
        }
        Segmented seg = new Segmented(a, row, segs, labels, said, selectedInk, small);
        seg.select(selected);
        if (parent != null) parent.addView(row);
        return seg;
    }

    /** The small segmented control's chosen-segment corner: two under its track's, as the
     *  mock nests them. */
    private static final int SEG_SMALL_RADIUS_DP = Look.R_PILL - 2;

    /**
     * AN OUTLINED −/+ SQUARE for a value cell that spans the card (polish item 17): the
     * CTRL_EDGE ring with no fill, radius 11, drawn 44 dp inside a 48 dp touch target so
     * two cells side by side still fit a phone's width. The filled well between two of
     * these is the value; the rings are what change it. Not added to any parent itself.
     */
    public static Button outlineStep(Activity a, String glyph) {
        Button b = new Button(a);
        b.setText(glyph);
        b.setAllCaps(false);
        b.setTextSize(20);
        b.setTextColor(TEXT);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setBackground(new android.graphics.drawable.InsetDrawable(
            ctrlRect(a, android.graphics.Color.TRANSPARENT, Look.R_CTRL), dp(a, 2)));
        b.setStateListAnimator(null);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        b.setOnTouchListener(new Press());
        return b;
    }

    /** Small square −/+ button, used inside stepperRow. Not added to any parent itself. */
    public static Button mini(Activity a, String s) {
        Button b = new Button(a);
        b.setText(s);
        b.setTextSize(18);
        b.setTextColor(TEXT);
        // surfaceHigh circles, 48dp target (already correct).
        b.setBackground(ctrlRect(a, SURFHI, Look.R_CTRL));
        // A2 - the width is a real constraint (a chip beside a label); the HEIGHT is the
        // touch floor and must be able to grow with the glyph inside it.
        b.setMinHeight(dp(a, 48));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                dp(a, 52), ViewGroup.LayoutParams.WRAP_CONTENT));
        // The only two controls in the app that never acknowledged a tap were this and the
        // buttons row() builds; both make the same 0.97 dip as every other control now.
        b.setOnTouchListener(new Press());
        return b;
    }

    /**
     * A COMPACT MEASUREMENT ROW — one 60dp line for a measurement the user has ALREADY
     * added: the method name on the left, the value as the hero to its right in the readout
     * face, and 48dp −/+ beside it — the app's touch-target floor everywhere else, which the
     * row's original 36dp pair, 4dp apart, did not clear.
     *
     * NO ✕ BUTTON. Round-2 decision C4: that glyph sat right past the +, 36dp and one
     * mistap from wiping a typed value with zero confirmation. Removal is now a deliberate
     * gesture instead of a stray tap — swipe the row left past {@link Look#SWIPE_REMOVE_DP}
     * (see {@link SwipeToRemove}) — and `remove` fires only once that gesture has completed,
     * on the understanding that the caller shows an undo affordance rather than losing the
     * value outright (SessionActivity's RemoveLogRow does, via Ui.snack).
     *
     * WHY IT IS NOT {@link #stepperRow}. The stepper puts its label on one line and its
     * control on a second, which is right for a screen with three fields and wrong for a
     * list: nine of them made the multi-measurement log a hundred-dp-per-row scroll that was
     * mostly empty, because the screen offered every method whether or not it was taken.
     * This row only ever exists for a measurement that IS being logged, so it can be a
     * single line.
     *
     * NO RANGE IN THE LABEL. The bounds belong to the box that takes a typed number, not to
     * the name of the method; `valueTap` opens that box and states them there.
     */
    public static void measureRow(Activity a, ViewGroup parent, String label, String value,
                                   View.OnClickListener minus, View.OnClickListener plus,
                                   View.OnClickListener valueTap, View.OnClickListener remove,
                                   String accName) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);

        TextView l = new TextView(a);
        l.setText(label);
        l.setTextColor(DIM);
        l.setTextSize(Look.SP_CAPTION);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        r.addView(l, llp);

        Button mn = miniRow(a, "−");
        if (minus != null) mn.setOnClickListener(minus);
        r.addView(mn);

        TextView v = new TextView(a);
        v.setText(value);
        v.setTextColor(TEXT);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setTextSize(20);
        v.setGravity(Gravity.CENTER);
        v.setMinimumWidth(dp(a, 72));
        // A2 - THE READOUT IS A TARGET, not a label. Tapping it types the number directly,
        // which is the fast path for anyone who does not want to press the stepper eleven
        // times, and it sat 4dp under the floor every other target in the app clears.
        v.setMinimumHeight(dp(a, 48));
        v.setPadding(dp(a, Look.S2), 0, dp(a, Look.S2), 0);
        if (valueTap != null) {
            v.setOnClickListener(valueTap);
            v.setClickable(true);
            v.setFocusable(true);
            v.setBackground(roundRect(a, SURFHI, Look.R_PILL));
            v.setOnTouchListener(new Press());
            if (android.os.Build.VERSION.SDK_INT >= 21)
                v.setAccessibilityDelegate(new TypeHint());
        }
        r.addView(v);

        Button pl = miniRow(a, "+");
        if (plus != null) pl.setOnClickListener(plus);
        r.addView(pl);

        if (accName != null && accName.length() > 0) {
            mn.setContentDescription(A11y.decrease(accName, value));
            pl.setContentDescription(A11y.increase(accName, value));
            v.setContentDescription(A11y.value(accName, value));
        }

        if (remove != null) {
            r.setOnTouchListener(new SwipeToRemove(r, remove));
            // TalkBack's touch-exploration mode intercepts a single-finger swipe for its
            // own navigation before any OnTouchListener sees it, so SwipeToRemove is
            // invisible to a screen-reader user — the deleted ✕ was their only way to
            // remove a row. The row itself must become a stop that carries a discrete
            // action instead: same API-gated focusability Ui.group() uses ("below API 28
            // the only way to make a non-interactive group take accessibility focus is
            // setFocusable"), same one-action AccessibilityDelegate shape as TypeHint.
            if (accName != null && accName.length() > 0) r.setContentDescription(accName);
            r.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            if (android.os.Build.VERSION.SDK_INT >= 28) r.setScreenReaderFocusable(true);
            else r.setFocusable(true);
            if (android.os.Build.VERSION.SDK_INT >= 21)
                r.setAccessibilityDelegate(new RemoveA11y(remove));
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 60));
        parent.addView(r, lp);
        divider(a, parent);

        // First measurement row of the process explains the gesture that replaced the ✕;
        // never shown again after that, and never persisted, so a fresh launch says it once.
        if (remove != null && !swipeHintShown) {
            swipeHintShown = true;
            note(a, parent, "Swipe a row left to remove it.");
        }
    }

    /** Whether {@link #measureRow}'s first-use swipe hint has already been shown in this
     *  process. */
    private static boolean swipeHintShown = false;

    /** {@link #mini} sized for a list row that must still clear the 48dp touch floor — the
     *  round-2 fix for measureRow's −/+, which used to be a 36dp square 4dp from its
     *  neighbour. */
    public static Button miniRow(Activity a, String s) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(TEXT);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(roundRect(a, SURFHI, Look.R_PILL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48));
        lp.leftMargin = dp(a, Look.S2);
        b.setLayoutParams(lp);
        b.setOnTouchListener(new Press());
        return b;
    }

    /**
     * Swipe-left-to-remove for a {@link #measureRow}. Tracks raw horizontal drag distance
     * from ACTION_DOWN; on release, a drag that crossed {@link Look#SWIPE_REMOVE_DP} finishes
     * animating the row off-screen and invokes `remove` once that animation completes
     * ({@link SwipeRemoveFinish}), otherwise the row eases back to rest. Both motions honour
     * the system animation scale via {@link Look#resolveDuration}, exactly as {@link Press}
     * does. Attached only when measureRow is given a non-null `remove` listener.
     *
     * A named class, not a lambda — this project targets Java 8 source with no lambdas
     * anywhere.
     */
    private static final class SwipeToRemove implements View.OnTouchListener {
        private final View row; private final View.OnClickListener remove;
        private float startX; private boolean dragging;
        SwipeToRemove(View row, View.OnClickListener remove) { this.row = row; this.remove = remove; }
        @Override public boolean onTouch(View v, android.view.MotionEvent e) {
            int act = e.getActionMasked();
            if (act == android.view.MotionEvent.ACTION_DOWN) {
                // MUST return true. `row` is a plain, non-clickable LinearLayout: if this
                // listener declined ACTION_DOWN, row.dispatchTouchEvent(DOWN) would return
                // false (nothing else on a non-clickable View claims it), so the PARENT
                // ViewGroup would never register `row` as a touch target — and per
                // ViewGroup.dispatchTouchEvent, once a gesture's DOWN goes unclaimed, every
                // ViewGroup ABOVE `row` forces onInterceptTouchEvent to true for the rest of
                // that gesture and handles MOVE/UP itself instead of re-searching children,
                // so `row` would never be asked again — dragging could never become true and
                // remove() would never fire, for anyone. Returning true here makes `row`
                // itself the registered target, so this listener keeps receiving MOVE/UP/
                // CANCEL for the whole gesture regardless of what it returns from them.
                startX = e.getRawX(); dragging = false; return true;
            } else if (act == android.view.MotionEvent.ACTION_MOVE) {
                float dx = e.getRawX() - startX;
                if (dx < 0) { row.setTranslationX(dx); dragging = true; }
                return dragging;
            } else if (act == android.view.MotionEvent.ACTION_UP
                    || act == android.view.MotionEvent.ACTION_CANCEL) {
                float dx = row.getTranslationX();
                float scale;
                try {
                    scale = android.provider.Settings.Global.getFloat(
                        row.getContext().getContentResolver(),
                        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f);
                } catch (Exception ex) { scale = 1.0f; }
                if (dx < -Ui.dp((Activity) row.getContext(), Look.SWIPE_REMOVE_DP)) {
                    int d = Look.resolveDuration(Look.MS_SWIPE_REMOVE, scale);
                    row.animate().translationX(-row.getWidth()).alpha(0f).setDuration(d)
                        .withEndAction(new SwipeRemoveFinish(row, remove)).start();
                } else {
                    int d = Look.resolveDuration(Look.MS_SWIPE_SNAP_BACK, scale);
                    row.animate().translationX(0f).setDuration(d).start();
                }
                return dragging;
            }
            return false;
        }
    }

    /** Runs `remove.onClick(row)` once {@link SwipeToRemove}'s off-screen animation has
     *  actually finished playing, so the caller reacts to a row that has visually left
     *  rather than one still sliding. */
    private static final class SwipeRemoveFinish implements Runnable {
        private final View row; private final View.OnClickListener remove;
        SwipeRemoveFinish(View row, View.OnClickListener remove) { this.row = row; this.remove = remove; }
        @Override public void run() { remove.onClick(row); }
    }

    /**
     * The screen-reader equivalent of {@link SwipeToRemove}. TalkBack's touch-exploration
     * mode intercepts a single-finger swipe for its own navigation before any
     * View.OnTouchListener ever sees the raw drag, so the gesture above is simply not
     * something a TalkBack user can perform — this is their actual removal path. Same
     * one-action {@link View.AccessibilityDelegate} shape as {@link TypeHint}, but for
     * ACTION_DISMISS (the platform's standard "swipe row away" action id) relabelled
     * "Remove" so TalkBack's local context menu names it the way this screen does, and
     * wired straight to the same `remove` listener the swipe invokes — no animation, since
     * there is no drag for it to finish.
     */
    private static final class RemoveA11y extends View.AccessibilityDelegate {
        private final View.OnClickListener remove;
        RemoveA11y(View.OnClickListener remove) { this.remove = remove; }
        @Override public void onInitializeAccessibilityNodeInfo(
                View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo
                .AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS, "Remove"));
        }
        @Override public boolean performAccessibilityAction(
                View host, int action, android.os.Bundle args) {
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS) {
                remove.onClick(host);
                return true;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    }

    /**
     * A label, a formatted value, and −/+ buttons wired to the given listeners. `value`
     * is already formatted by the caller (e.g. via Fmt.p / Fmt.t) so this works equally
     * for plain counts, seconds and unit-aware pressures.
     *
     * The screen-reader name comes from A11y.fieldName(label) rather than from the call
     * site. There are twenty-five stepper rows across six screens and this helper already
     * receives everything an announcement needs — the field's label and its formatted
     * value — so naming them here names all of them, and a stepper added later is named
     * the moment it is written. Deriving the name from the label ALSO means the name
     * follows a label that changes: the set editor's first stepper is "Pull to" in fixed
     * mode and "Target" in ramp mode, and a name pinned at the call site would have gone
     * on saying one of them after the screen started printing the other.
     *
     * Because `value` is the string already drawn, the announcement is unit-correct for
     * free: inHg's negative sign and a delta's own formatter are both the caller's
     * existing choice, spoken back verbatim. See A11y's THE UNIT RULE.
     */
    public static void stepperRow(Activity a, ViewGroup parent, String label, String value,
                                   View.OnClickListener minus, View.OnClickListener plus) {
        stepperRow(a, parent, label, value, minus, plus, A11y.fieldName(label));
    }

    /**
     * As above, but with the field name given explicitly — for the few rows whose label
     * does not reduce to a good spoken name on its own. `accName` should be the plain
     * field name ("assessment pressure"), not the label, which also carries the range.
     */
    public static void stepperRow(Activity a, ViewGroup parent, String label, String value,
                                   View.OnClickListener minus, View.OnClickListener plus,
                                   String accName) {
        stepperRow(a, parent, label, value, minus, plus, null, accName);
    }

    /**
     * As above, plus TAP-TO-TYPE: `valueTap` is fired when the readout itself is tapped, so
     * a value four hundred steps away can be typed instead of stepped.
     *
     * WHY IT IS THE VALUE THAT IS TAPPABLE, and not a third button: the set editor's
     * {@link #paramRow} already teaches exactly this gesture ("TAPPING THE VALUE types it
     * exactly, through the caller's dialog"), and a stepper row that learned a different
     * one would be a second way to do the same thing on a screen the user reaches from the
     * same app. The row grows by nothing; only the readout becomes a target.
     *
     * The readout becomes a target with a "double tap to type" hint when a listener is
     * supplied, and stays inert — unfocusable, announced as a value — when it is null. It is
     * drawn as the same filled well either way, so the row's shape never depends on it.
     */
    public static void stepperRow(Activity a, ViewGroup parent, String label, String value,
                                   View.OnClickListener minus, View.OnClickListener plus,
                                   View.OnClickListener valueTap, String accName) {
        // THE LABEL GETS ITS OWN LINE. It used to share one row with the −/+ control and
        // carry the range in the same breath ("Max pressure  (−0.3 inHg to −11.8 inHg)"),
        // which on a phone left the label squashed into whatever width the stepper did not
        // want and wrapping mid-unit. Full width, as the uppercase FIELD LABEL, with the
        // range after the name in its own case so its unit keeps its spelling.
        LinearLayout box = col(a);

        // NO LABEL WHERE THE CARD'S TITLE ALREADY NAMES THE FIELD (polish SU-3): an empty label
        // draws no field label, rather than an empty line or the title twice.
        if (labelPartOf(label).length() > 0) {
            View t = fieldLabel(a, box, labelPartOf(label), hintPartOf(label));
            t.setPadding(0, dp(a, Look.S2), 0, dp(a, Look.S3));
        }

        // THE CONTROL SPANS THE ROW: − [ value ] +. The value cell takes every dp the two
        // buttons do not, so the number sits in a well the width of the card rather than
        // in a pill pushed to the right edge - the approved ceiling mock. The −/+ are
        // OUTLINED (the control edge, no fill) so the filled well reads as the value and
        // the two squares read as the things that change it.
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);

        Button mn = mini(a, "−"); mn.setOnClickListener(minus); repeatOnHold(mn, minus);
        mn.setBackground(ctrlRect(a, android.graphics.Color.TRANSPARENT, Look.R_CTRL));
        TextView v = new TextView(a);
        v.setText(value);
        v.setTextColor(TEXT);
        // Tabular sans at 19sp (polish SYS-9 / SU-3): this value is EDITED here, and a number
        // that changes under the finger must not jitter as its digits change width - tabular
        // figures hold the width; monospace is kept for live readouts only, and a word such as
        // "Not set" is never drawn in it.
        tabular(v);
        v.setTextSize(19);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        // A2 - a floor, not a ceiling: the well grows with a large font rather than clip.
        v.setMinimumHeight(dp(a, 48));
        v.setPadding(dp(a, Look.S3), 0, dp(a, Look.S3), 0);
        v.setBackground(roundRect(a, SURFHI, Look.R_CTRL));
        Button pl = mini(a, "+"); pl.setOnClickListener(plus); repeatOnHold(pl, plus);
        pl.setBackground(ctrlRect(a, android.graphics.Color.TRANSPARENT, Look.R_CTRL));
        if (accName != null && accName.length() > 0) {
            mn.setContentDescription(A11y.decrease(accName, value));
            pl.setContentDescription(A11y.increase(accName, value));
            v.setContentDescription(A11y.value(accName, value));
        }
        if (valueTap != null) {
            v.setOnClickListener(valueTap);
            v.setClickable(true);
            v.setFocusable(true);
            v.setOnTouchListener(new Press());
            // Spoken as "double tap to type" rather than the default "activate", so the
            // reader says what the gesture is FOR — the value is not a button that does
            // something, it is a field that can be filled.
            if (android.os.Build.VERSION.SDK_INT >= 21)
                v.setAccessibilityDelegate(new TypeHint());
        }

        // Square 48dp buttons either side; POLISH #12 (wave-4 sweep): breathing room
        // between the well and its −/+ so a wide value never reads as one glyph run.
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48));
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vLp.leftMargin = dp(a, Look.S2); vLp.rightMargin = dp(a, Look.S2);
        r.addView(mn, bLp); r.addView(v, vLp);
        r.addView(pl, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        box.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // NO HAIRLINE UNDER THE ROW any more. It was there so a column of steppers read as
        // separate fields; each field now has its own label and its own filled well, which
        // separates them already, and the rule was one more line in a card of lines.

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, Look.S2);
        parent.addView(box, lp);
    }

    /**
     * A COMPACT PARAMETER ROW — one line per parameter instead of the three the stepper
     * stack takes.
     *
     *   [ label            −   value   + ]      48dp, the whole row
     *   [ ───────────●──────────────────  ]      a thin slider spanning the row
     *
     * WHY IT EXISTS. {@link #stepperRow} puts the label on its own line, the −/+ control on
     * a second, and a hairline under both: roughly 100dp per parameter. Manual mode has six
     * of those (twelve in ramp mode) and the set editor has eight, so the one screen whose
     * job is "see the shape of what you are about to run" could never show its own numbers
     * and its preview at the same time — every value change scrolled something off. This row
     * is about 76dp, so seven parameters fit inside ~530dp and a 600dp viewport holds the
     * lot with the chart still on screen.
     *
     * THE THREE GESTURES ARE DELIBERATELY DIFFERENT INSTRUMENTS, not three copies of one:
     *   - the SLIDER is coarse and fast — get near the number you want;
     *   - −/+ are fine — one step of whatever the caller's own step size is;
     *   - TAPPING THE VALUE types it exactly, through the caller's dialog.
     * All three go through the caller's own clamp, because all three end in the same bump.
     *
     * `sliderMax` is the range the TRACK spans, in the same integer units `value` is given
     * in; the caller passes the bound it actually enforces, so dragging to the end of the
     * track lands on the real maximum rather than somewhere the clamp then rewrites.
     *
     * `value` is pre-formatted by the caller (Fmt.p / Fmt.t / "60 %"), so the row is
     * unit-correct for free, exactly as stepperRow is. Naming follows the same rule too:
     * A11y.fieldName(label) unless `accName` says otherwise.
     */
    public static View[] paramRow(Activity a, ViewGroup parent, String label, String value,
                                 int sliderValue, int sliderMax,
                                 View.OnClickListener minus, View.OnClickListener plus,
                                 View.OnClickListener typeTap,
                                 android.widget.SeekBar.OnSeekBarChangeListener slide,
                                 String accName) {
        String name = accName != null && accName.length() > 0
                    ? accName : A11y.fieldName(label);
        LinearLayout box = col(a);

        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);

        // The label takes whatever the controls do not. The range hint is dropped from the
        // visible label on purpose — at this density it wraps the row onto two lines, which
        // is the whole thing this row exists to avoid — but it is NOT lost: the slider's
        // ends ARE the range, and the spoken name below still carries it.
        TextView l = new TextView(a);
        l.setText(labelPartOf(label));
        l.setTextColor(DIM);
        l.setTextSize(Look.SP_CAPTION);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button mn = compactStep(a, "−");
        mn.setOnClickListener(minus); repeatOnHold(mn, minus);
        mn.setContentDescription(A11y.decrease(name, value));
        r.addView(mn);

        Button v = new Button(a);
        v.setText(value);
        v.setAllCaps(false);
        v.setTextColor(TEXT);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setTextSize(15);
        v.setBackground(ctrlRect(a, SURFHI, Look.R_CTRL));
        v.setPadding(0, 0, 0, 0);
        v.setMinWidth(0); v.setMinimumWidth(dp(a, 72));
        v.setContentDescription("Type " + name + ", currently " + A11y.collapse(value));
        v.setOnClickListener(typeTap);
        v.setOnTouchListener(new Press());
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 40));
        vlp.leftMargin = dp(a, 2); vlp.rightMargin = dp(a, 2);
        r.addView(v, vlp);

        Button pl = compactStep(a, "+");
        pl.setOnClickListener(plus); repeatOnHold(pl, plus);
        pl.setContentDescription(A11y.increase(name, value));
        r.addView(pl);
        // A ROW'S ± ARE REACHABLE FROM ITS VALUE BUTTON, so a repaint in place can
        // re-say all three together. They each announce "currently <value>", and a reader
        // told the old number by the very control that changes it is worse off than one
        // told nothing. See #resay, which is what every in-place repaint calls.
        v.setTag(new View[]{ mn, pl });

        // A2 - MIN, NOT FIXED. 48dp is the touch-target FLOOR; writing it as a fixed
        // height made it a ceiling too, and text is in sp while this is in dp - so at the
        // largest font scale the row stayed 48dp tall while its contents grew past it and
        // clipped. The floor is what the rule was ever about.
        r.setMinimumHeight(dp(a, 48));
        box.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.SeekBar sb = new android.widget.SeekBar(a);
        sb.setMax(Math.max(1, sliderMax));
        sb.setProgress(Math.max(0, Math.min(Math.max(1, sliderMax), sliderValue)));
        sb.setContentDescription(A11y.value(name, value));
        sb.setPadding(0, 0, 0, 0);
        sb.setOnSeekBarChangeListener(slide);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 22));
        box.addView(sb, slp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, Look.S1);
        parent.addView(box, lp);
        // The two LIVE instruments, so a caller that wants to rewrite what this row SAYS
        // without tearing the screen down can (O3). pairRow has returned its buttons since
        // T11 for the same reason; every caller that ignores this return still compiles,
        // because ignoring a value a method did not used to have is not a change.
        return new View[]{ v, sb };
    }

    /**
     * RE-SAYS A ROW WHOSE VALUE CHANGED WITHOUT A REBUILD — the number on the button, what
     * the button says, and what the −/+ either side of it say. Built rows set all three at
     * once (see {@link #paramRow}); a screen that repaints in place had been setting only
     * the first two, so after typing 1260 into a hold the ± went on telling a screen
     * reader "currently 120 s" until the screen was rebuilt.
     *
     * The ± travel in the value button's tag rather than through the caller, so a caller
     * that repaints a row cannot forget them and cannot hold a stale pair: the tag dies
     * with the view it is on.
     */
    public static void resay(Button value, String name, String text) {
        if (value == null) return;
        value.setText(text);
        value.setContentDescription("Type " + name + ", currently " + A11y.collapse(text));
        Object tag = value.getTag();
        if (!(tag instanceof View[])) return;
        View[] steps = (View[]) tag;
        if (steps.length > 0 && steps[0] != null)
            steps[0].setContentDescription(A11y.decrease(name, text));
        if (steps.length > 1 && steps[1] != null)
            steps[1].setContentDescription(A11y.increase(name, text));
    }

    /**
     * A START → END PAIR ROW — one PARAMETER, both of its ends, on one line:
     *
     *   Pull                                              (a caption, DIM)
     *   [ −  −1.8  + ]        →        [ −  −10.0  + ]
     *
     * WHY IT EXISTS. A ramp set has a start value and an end value for the same five
     * parameters, and the editor listed all the starts, then a "Ends at:" heading, then all
     * the ends — so the two halves of "pull goes from here to there" sat five rows and a
     * scroll apart. Reading a ramp meant holding a number in your head while you scrolled
     * to find its partner, and changing one end meant losing sight of the other. The pair
     * is the unit of meaning, so the pair is the unit of layout.
     *
     * WHAT IT REUSES. Exactly {@link #paramRow}'s instruments and nothing new: the same
     * compactStep −/+ buttons at the same 48dp, the same monospace tap-to-type value
     * button, the same Press feedback, the same clamps behind all of them (the caller
     * passes the identical listeners it would have passed to two paramRows). The SLIDER is
     * what is dropped — two tracks on one line would each be about 60dp wide, which is
     * a control too coarse to be worth the height. The ± and the typed value are the
     * precise instruments anyway, and the start/end pair is where precision matters.
     *
     * ACCESSIBILITY. Two focus stops per half exactly as paramRow has, named "start <field>"
     * and "end <field>" so a reader never has to infer which side of the arrow it is on.
     *
     * RETURNS the two halves' own value Buttons — {start, end} — so a caller that needs to
     * rewrite what they SAY without rebuilding the row (T11's drag-the-curve graph, which
     * live-updates the Pull pair's text while a handle is dragged rather than tearing the
     * screen down on every pixel of movement) can do so directly. Every existing caller
     * before T11 called this as a bare statement and still can — ignoring a return value a
     * method didn't used to have is not a breaking change in Java.
     */
    public static Button[] pairRow(Activity a, ViewGroup parent, String label,
                                String startValue, String endValue,
                                View.OnClickListener startMinus, View.OnClickListener startPlus,
                                View.OnClickListener startType,
                                View.OnClickListener endMinus, View.OnClickListener endPlus,
                                View.OnClickListener endType) {
        String field = labelPartOf(label);
        LinearLayout box = col(a);

        TextView l = new TextView(a);
        l.setText(field);
        l.setTextColor(DIM);
        l.setTextSize(Look.SP_CAPTION);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        l.setPadding(0, dp(a, 2), 0, dp(a, 1));
        box.addView(l);

        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);

        Button startBtn = pairHalf(a, r, "start " + field, startValue, startMinus, startPlus, startType);

        TextView arrow = new TextView(a);
        arrow.setText("→");
        arrow.setTextColor(FAINT);
        arrow.setTextSize(15);
        arrow.setGravity(Gravity.CENTER);
        // The arrow is DECORATION: both halves already say "start"/"end" out loud, and a
        // reader stopping on a lone glyph between them adds nothing but a stop.
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                dp(a, 20), ViewGroup.LayoutParams.WRAP_CONTENT);
        r.addView(arrow, alp);

        Button endBtn = pairHalf(a, r, "end " + field, endValue, endMinus, endPlus, endType);

        // A2 - MIN, NOT FIXED. 48dp is the touch-target FLOOR; writing it as a fixed
        // height made it a ceiling too, and text is in sp while this is in dp - so at the
        // largest font scale the row stayed 48dp tall while its contents grew past it and
        // clipped. The floor is what the rule was ever about.
        r.setMinimumHeight(dp(a, 48));
        box.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, Look.S1);
        parent.addView(box, lp);
        return new Button[]{ startBtn, endBtn };
    }

    /** One half of a {@link #pairRow} — the −/value/+ trio, weighted so the two halves
     *  split the row evenly whatever the numbers in them are. Returns its own value
     *  Button (see {@link #pairRow}'s own doc for why). */
    private static Button pairHalf(Activity a, LinearLayout parent, String name, String value,
                                  View.OnClickListener minus, View.OnClickListener plus,
                                  View.OnClickListener type) {
        LinearLayout h = new LinearLayout(a);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);

        Button mn = compactStep(a, "−");
        mn.setOnClickListener(minus); repeatOnHold(mn, minus);
        mn.setContentDescription(A11y.decrease(name, value));
        // Narrower than paramRow's 44dp: two trios share one row, and the 48dp HEIGHT is
        // what keeps the target legal — a 40x48 button is still well over the minimum area.
        mn.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 38), dp(a, 48)));
        h.addView(mn);

        Button v = new Button(a);
        v.setText(value);
        v.setAllCaps(false);
        v.setTextColor(TEXT);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setTextSize(14);
        v.setBackground(ctrlRect(a, SURFHI, Look.R_CTRL));
        v.setPadding(0, 0, 0, 0);
        v.setMinWidth(0); v.setMinimumWidth(0);
        v.setSingleLine(true);
        v.setContentDescription("Type " + name + ", currently " + A11y.collapse(value));
        v.setOnClickListener(type);
        v.setOnTouchListener(new Press());
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                0, dp(a, 40), 1f);
        vlp.leftMargin = dp(a, 2); vlp.rightMargin = dp(a, 2);
        h.addView(v, vlp);

        Button pl = compactStep(a, "+");
        pl.setOnClickListener(plus); repeatOnHold(pl, plus);
        pl.setContentDescription(A11y.increase(name, value));
        pl.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 38), dp(a, 48)));
        h.addView(pl);
        v.setTag(new View[]{ mn, pl });   // see #resay - a pair half repaints like any row

        h.setMinimumHeight(dp(a, 48));      // A2 - a floor, not a ceiling
        parent.addView(h, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    /** The −/+ of a {@link #paramRow}: {@link #mini}'s look at the width a one-line row can
     *  spare, and STILL 48dp tall — the touch target is what must not shrink. Public so a
     *  caller with the same narrow-row problem (S15's NOW-tile nudge chips, which have a
     *  quarter-tile's width to work with and nowhere near {@link #mini}'s 52dp) gets the
     *  identical chip/button visual language instead of a third hand-built −/+ pair. */
    public static Button compactStep(Activity a, String s) {
        Button b = new Button(a);
        b.setText(s);
        b.setTextSize(17);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(ctrlRect(a, SURFHI, Look.R_CTRL));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 44), dp(a, 48)));
        b.setOnTouchListener(new Press());
        return b;
    }

    /** The field name out of a stepper label — everything before the range hint's "(". */
    private static String labelPartOf(String label) {
        if (label == null) return "";
        int at = label.indexOf('(');
        return (at < 0 ? label : label.substring(0, at)).trim();
    }

    /** The range hint out of a stepper label — "(0-255 s)" and friends, or "". */
    private static String hintPartOf(String label) {
        if (label == null) return "";
        int at = label.indexOf('(');
        return at < 0 ? "" : label.substring(at).trim();
    }

    /**
     * A SECTION HEAD — a small coloured rule and an uppercase tracked label, the shape
     * {@link #categoryHeader} already uses in Settings, for the sections INSIDE a page
     * ("Favourites", "This week"). {@link #head} stays the 22sp bold PAGE title and is now
     * used for that only: a screen whose sections are all the same size as its name has no
     * hierarchy, and Today had three of them.
     */
    public static void sectionHead(Activity a, ViewGroup parent, String label, int colour) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(a, Look.S6), 0, dp(a, Look.S2));

        View rule = new View(a);
        rule.setBackground(roundRect(a, colour, 2));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(a, 3), dp(a, 11));
        rp.rightMargin = dp(a, Look.S3);
        row.addView(rule, rp);

        TextView t = microLabel(a, row, label, DIM);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);

        group(row, label);
        if (android.os.Build.VERSION.SDK_INT >= 28) row.setAccessibilityHeading(true);
        parent.addView(row);
    }

    /**
     * A LABEL/VALUE SETTINGS ROW — the mockup's `.kv`: the name of the setting on the left
     * in DIM, its current value hard against the right edge, one 48dp target, a hairline
     * under it.
     *
     * WHY: Settings stated its values inside the label of the control that changes them
     * ("Hold before measuring: on"), so the eye had to read a sentence to the end to learn
     * a state, and no two rows lined their values up. A fixed right-hand value column can
     * be scanned down in one pass. The value is READ-ONLY here, so it takes the app's sans
     * face with tabular digits ({@link #tabular}) - still aligned row to row, as monospace
     * was, and now the same face the same kind of number wears on Today and in Library.
     *
     * The WHOLE ROW is the target — a value is not a separate control from its label — and
     * the row is one focus stop reading "label: value", so the value is never carried by
     * position alone. A null listener leaves it a readout rather than a control, which is
     * what a row like "Last logged" wants.
     */
    public static LinearLayout kvRow(Activity a, ViewGroup parent, String label,
                                      CharSequence value, int valueColour,
                                      View.OnClickListener tap) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(a, 48));
        r.setPadding(0, dp(a, Look.S2), 0, dp(a, Look.S2));

        TextView l = new TextView(a);
        l.setText(label);
        l.setTextColor(DIM);
        l.setTextSize(Look.SP_CAPTION);
        r.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(a);
        v.setText(value);
        v.setTextColor(valueColour);
        v.setTextSize(Look.SP_CAPTION);
        tabular(v);
        v.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        v.setPadding(dp(a, Look.S4), 0, 0, 0);
        /* THE VALUE TAKES TWO THIRDS, NOT EVERYTHING IT WANTS.
         *
         * The value was WRAP_CONTENT with no weight against a weight-1 label, so a long
         * value took the whole row and squeezed the label to ZERO WIDTH - it did not
         * ellipsise, it disappeared. The weeks table showed it plainly: a row reading
         * "6 sets . 12.0 min . -5.0 to -6.0 inHg" with no week number at all, sitting
         * between two rows that had one.
         *
         * Two thirds rather than half because values here are the variable part and labels
         * are short by design; a third is more than "wk 3" or "Peak target" needs, and the
         * value wraps within its share instead of eating its neighbour.
         */
        r.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2f));

        group(r, A11y.collapse(label) + ": " + A11y.collapse(String.valueOf(value)));
        if (tap != null) {
            r.setOnClickListener(tap);
            r.setOnTouchListener(new Press());
        }
        if (parent != null) {
            parent.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            divider(a, parent);
        }
        return r;
    }

    /**
     * A DOOR: the name of a room, the facts that are true inside it, and a chevron.
     *
     * kvRow's shape is wrong for this and it is worth saying why. kvRow puts a label on the
     * left and a value hard against the right, which is right for "Training time  19:00" and
     * wrong for "Shape  prime 8 min . 45s holds . 4 per block . rest 3:00": the value column
     * is two thirds of the row, and a line of facts that long either wraps into a ragged
     * right-aligned block or, drawn the way the proposal mock-up drew it, is cut with an
     * ellipsis. The owner's note on that mock-up was "find a way for the info to not get cut
     * if long".
     *
     * So the facts go UNDER the name, left-aligned, as many lines as they need. They are
     * handed in as a list and joined by Say#doorValue, which makes the spaces inside each
     * fact non-breaking and keeps the ones between facts ordinary - so a long line breaks
     * between two facts and never inside one. This view is never single-line and never
     * ellipsised; that is the whole point of it.
     *
     * One 48dp target, one focus stop, spoken as "name: fact, fact, fact" (Say#doorSaid) -
     * the middle dot and the non-breaking spaces are layout and are not read aloud.
     */
    public static LinearLayout doorRow(Activity a, ViewGroup parent, String title,
                                        String[] facts, View.OnClickListener tap) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(a, 48));
        r.setPadding(0, dp(a, Look.S3), 0, dp(a, Look.S3));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(Look.SP_BODY);
        col.addView(t);

        String value = Say.doorValue(facts);
        if (value.length() > 0) {
            TextView v = new TextView(a);
            v.setText(value);
            v.setTextColor(DIM);
            v.setTextSize(Look.SP_CAPTION);
            tabular(v);   // read-only facts: the sans face, digits kept even-width
            v.setPadding(0, dp(a, Look.S1), 0, 0);
            // DELIBERATELY no setSingleLine and no setEllipsize: the facts wrap, whole.
            col.addView(v);
        }
        r.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // SYS-5: ONE CHEVRON - the icon navCard draws, not a "\u203a" text glyph at body size.
        android.widget.ImageView ch = iconView(a, R.drawable.ic_chevron_right, DIM, 20);
        ((LinearLayout.LayoutParams) ch.getLayoutParams()).leftMargin = dp(a, Look.S3);
        r.addView(ch);

        String said = Say.doorSaid(facts);
        group(r, A11y.collapse(title) + (said.length() > 0 ? ": " + said : ""));
        if (tap != null) {
            r.setOnClickListener(tap);
            r.setOnTouchListener(new Press());
        }
        if (parent != null) {
            parent.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            divider(a, parent);
        }
        return r;
    }

    /* ===================== SYS-2 / SYS-3 - CHOICES THAT LOOK LIKE CHOICES ==================
     *
     * About forty places drew a one-of-N choice as text bullets: "● "/"○ " prefixed to a
     * Ui.flat label, the same outline chosen or not, no checked state for a reader, and the
     * effect line after all of them. And the t10 value rows (Long training days, When girth
     * follows length, the Program rows) were kvRows that silently stepped to the next value on
     * every tap, with no chevron to say they could be changed at all. */

    /**
     * RADIO ROWS (polish SYS-2). One full-width row per option, each at least 48dp: a radio
     * icon, the label, and that option's effect line under it (effects[i]; null or "" for
     * none). The chosen row (`chosen`; -1 for none) is filled ACCENT_DIM with an ACCENT ring
     * and a bold label, and its icon is the filled one - so "chosen" is carried by the fill,
     * the ring, the weight AND the shape, never by colour alone. Every option's effect is
     * shown, chosen or not: you see what an option does before you pick it.
     *
     * A reader hears each row as a radio button, "label. effect", checked or not checked
     * ({@link RadioA11y}). taps[i] runs when row i is tapped; a null tap (or a null array)
     * leaves that row a readout. The rows go into one column that is added to `parent` (when
     * not null) and returned.
     *
     * For two to four SHORT options on one line use {@link #segmented}; this is for options
     * that need their words.
     */
    public static LinearLayout choiceList(Activity a, ViewGroup parent, String[] labels,
                                          String[] effects, int chosen,
                                          View.OnClickListener[] taps) {
        LinearLayout list = col(a);
        for (int i = 0; labels != null && i < labels.length; i++) {
            boolean on = i == chosen;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setMinimumHeight(dp(a, 48));
            row.setPadding(dp(a, Look.S4), dp(a, Look.S3), dp(a, Look.S4), dp(a, Look.S3));
            android.graphics.drawable.GradientDrawable bg =
                roundRect(a, on ? Look.ACCENT_DIM : SURF, Look.R_CTRL);
            bg.setStroke(Math.max(1, dp(a, 1)), on ? ACCENT : LINE);
            row.setBackground(bg);

            android.widget.ImageView dot = iconView(a,
                on ? R.drawable.ic_radio_on : R.drawable.ic_radio_off, on ? ACCENT : DIM, 20);
            LinearLayout.LayoutParams dl = (LinearLayout.LayoutParams) dot.getLayoutParams();
            dl.rightMargin = dp(a, Look.S3);
            dl.topMargin = dp(a, 1);
            row.addView(dot);

            LinearLayout words = new LinearLayout(a);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(a);
            t.setText(labels[i]);
            t.setTextColor(TEXT);
            t.setTextSize(Look.SP_BODY);
            if (on) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            words.addView(t);
            String effect = effects != null && i < effects.length && effects[i] != null
                ? effects[i].trim() : "";
            if (effect.length() > 0) {
                TextView e = new TextView(a);
                e.setText(effect);
                e.setTextColor(DIM);
                e.setTextSize(Look.SP_CAPTION);
                e.setPadding(0, dp(a, Look.S1), 0, 0);
                words.addView(e);
            }
            row.addView(words, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            group(row, A11y.collapse(labels[i])
                + (effect.length() > 0 ? ". " + A11y.collapse(effect) : ""));
            row.setAccessibilityDelegate(new RadioA11y(on));
            row.setSelected(on);
            View.OnClickListener tap = taps != null && i < taps.length ? taps[i] : null;
            if (tap != null) {
                row.setClickable(true);
                row.setOnClickListener(tap);
                row.setOnTouchListener(new Press());
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(a, Look.S2);
            list.addView(row, lp);
        }
        if (parent != null) parent.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return list;
    }

    /** A choiceList row, as a screen reader should hear it: a radio button, checked or not.
     *  A named class, not a lambda (project rule). */
    public static final class RadioA11y extends View.AccessibilityDelegate {
        private final boolean checked;
        public RadioA11y(boolean checked) { this.checked = checked; }
        @Override public void onInitializeAccessibilityNodeInfo(
                View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName("android.widget.RadioButton");
            info.setCheckable(true);
            info.setChecked(checked);
        }
        @Override public void onInitializeAccessibilityEvent(
                View host, android.view.accessibility.AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(host, event);
            event.setClassName("android.widget.RadioButton");
            event.setChecked(checked);
        }
    }

    /** What a {@link #choiceRow} does with the option picked: apply option `index`. Called
     *  again with the old index by the Undo on the snack that follows. */
    public interface Choice {
        void choose(int index);
    }

    /** The words a {@link #choiceRow}'s Undo carries. */
    public static final String UNDO = "Undo";
    /** The value a {@link #choiceRow} shows when no option is chosen. */
    public static final String NOT_SET = "Not set";

    /**
     * A VALUE ROW THAT IS A CHOICE (polish SYS-3): the kvRow shape - name on the left, the
     * current option in TEXT on the right - plus the chevron icon, so it reads as something
     * that opens. A tap opens a bottom sheet titled `label` with every option as a
     * {@link #choiceList} (each with its effect line). NOTHING CHANGES UNTIL AN OPTION IS
     * PICKED: picking the current one, Close, or Back just closes the sheet.
     *
     * Picking another option closes the sheet and calls onChoose.choose(i) - the caller saves
     * and redraws, as its old cycling tap did. Then, when `undoRoot` is given, a snack says
     * "<label>: <option>" (Say#choiceChanged) with "Undo", which calls onChoose.choose(old).
     * Undo restores the previous VALUE through the same path; it is not a rule of its own.
     * A null `undoRoot` shows no snack (for a screen with no root frame).
     */
    public static LinearLayout choiceRow(Activity a, ViewGroup parent, String label,
                                         String[] options, String[] effects, int chosen,
                                         FrameLayout undoRoot, Choice onChoose) {
        String value = options != null && chosen >= 0 && chosen < options.length
            ? options[chosen] : NOT_SET;
        LinearLayout r = kvRow(a, parent, label, value, TEXT,
            new ChoiceRowTap(a, label, options, effects, chosen, undoRoot, onChoose));
        android.widget.ImageView ch = iconView(a, R.drawable.ic_chevron_right, DIM, 20);
        ((LinearLayout.LayoutParams) ch.getLayoutParams()).leftMargin = dp(a, Look.S2);
        r.addView(ch);
        return r;
    }

    /** Opens a {@link #choiceRow}'s sheet. A named class, not a lambda. */
    private static final class ChoiceRowTap implements View.OnClickListener {
        private final Activity a; private final String label;
        private final String[] options, effects; private final int chosen;
        private final FrameLayout undoRoot; private final Choice onChoose;
        ChoiceRowTap(Activity a, String label, String[] options, String[] effects, int chosen,
                     FrameLayout undoRoot, Choice onChoose) {
            this.a = a; this.label = label; this.options = options; this.effects = effects;
            this.chosen = chosen; this.undoRoot = undoRoot; this.onChoose = onChoose;
        }
        @Override public void onClick(View v) {
            if (options == null || options.length == 0) return;
            ChoiceSheet open = new ChoiceSheet(this);
            LinearLayout body = col(a);
            body.setPadding(0, dp(a, Look.S2), 0, 0);
            View.OnClickListener[] taps = new View.OnClickListener[options.length];
            for (int i = 0; i < options.length; i++) taps[i] = new ChoicePick(open, i);
            choiceList(a, body, options, effects, chosen, taps);
            android.widget.ScrollView sc = new android.widget.ScrollView(a);
            sc.addView(body);
            open.dialog = dialog(a).setTitle(label).setView(sc)
                .setNegativeButton("Close", null).show();
            sheet(a, open.dialog);
        }
    }

    /** One open choice sheet: the dialog, and the row it answers for. */
    private static final class ChoiceSheet {
        final ChoiceRowTap row;
        android.app.AlertDialog dialog;
        ChoiceSheet(ChoiceRowTap row) { this.row = row; }
    }

    /** One option in an open choice sheet. */
    private static final class ChoicePick implements View.OnClickListener {
        private final ChoiceSheet sheet; private final int index;
        ChoicePick(ChoiceSheet sheet, int index) { this.sheet = sheet; this.index = index; }
        @Override public void onClick(View v) {
            ChoiceRowTap r = sheet.row;
            if (sheet.dialog != null && sheet.dialog.isShowing()) sheet.dialog.dismiss();
            if (index == r.chosen || r.onChoose == null) return;
            r.onChoose.choose(index);
            if (r.undoRoot != null && r.chosen >= 0)
                snack(r.a, r.undoRoot, Say.choiceChanged(r.label, r.options[index]),
                      UNDO, new ChoiceUndo(r.onChoose, r.chosen), Snack.HOLD_MS_ACTIONABLE);
        }
    }

    /** The snack's Undo: the previous option, through the same apply path. */
    private static final class ChoiceUndo implements View.OnClickListener {
        private final Choice onChoose; private final int previous;
        ChoiceUndo(Choice onChoose, int previous) {
            this.onChoose = onChoose; this.previous = previous;
        }
        @Override public void onClick(View v) { onChoose.choose(previous); }
    }

    /**
     * The BOOLEAN kvRow: the same row, with {@link #switchView} where the value text would
     * be. The spoken form is still "label: on" / "label: off" — the drawn switch is hidden
     * from the reader, exactly as the ●/○ rows put their state in words rather than in the
     * glyph's colour — and the row carries setSelected so a service that surfaces the flag
     * gets it too.
     */
    public static LinearLayout kvRow(Activity a, ViewGroup parent, String label,
                                      boolean on, View.OnClickListener tap) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(a, 48));
        r.setPadding(0, dp(a, Look.S2), 0, dp(a, Look.S2));

        TextView l = new TextView(a);
        l.setText(label);
        l.setTextColor(DIM);
        l.setTextSize(Look.SP_CAPTION);
        r.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View sw = switchView(a, on);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                dp(a, SWITCH_W), dp(a, SWITCH_H));
        sp.leftMargin = dp(a, Look.S4);
        r.addView(sw, sp);

        group(r, A11y.collapse(label) + ": " + (on ? "on" : "off"));
        r.setSelected(on);
        if (tap != null) {
            r.setOnClickListener(tap);
            r.setOnTouchListener(new Press());
        }
        if (parent != null) {
            parent.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            divider(a, parent);
        }
        return r;
    }

    /**
     * A PROGRESS-TOWARD-A-TARGET ROW — one condition of a gate:
     *
     *   ─────────────────────────────────────────────  (a 1dp LINE rule on top)
     *   Net per session                    0.6 / 20.0 min
     *   ▬▬▬▬▬▬▬───────────────────────────────────────  (a 4dp bar, value / target)
     *
     * The label in DIM; the value in TEXT at medium weight with "/ target" after it in DIM
     * regular, so the eye lands on how far you are and reads what it is out of second. The
     * figures are read-only, so they are the sans face with tabular digits.
     *
     * THE BAR shows value/target at a glance - `fill` comes from {@link Look#barFill}, by
     * MAGNITUDE, so −5.0 of −8.0 inHg fills 62%. It is the plan blue while the condition is
     * short of its target and turns lime, with the value, once it is met: lime is the
     * app's "you did this", and a met condition is exactly that. Decoration to a reader,
     * who hears "<label>: <value> of <target>".
     */
    public static LinearLayout progressRow(Activity a, ViewGroup parent, String label,
                                           String value, String target, double fill,
                                           boolean met) {
        LinearLayout box = col(a);

        View rule = new View(a);
        rule.setBackgroundColor(LINE);
        box.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1))));

        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(a, 12), 0, 0);

        TextView l = new TextView(a);
        l.setText(label);
        l.setTextColor(DIM);
        l.setTextSize(Look.SP_BODY);
        r.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(a);
        tabular(v);
        v.setTextSize(Look.SP_BODY);
        v.setTextColor(DIM);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(value == null ? "" : value);
        int end = sb.length();
        sb.setSpan(new android.text.style.ForegroundColorSpan(met ? ACCENT : TEXT), 0, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.TypefaceSpan("sans-serif-medium"), 0, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (target != null && target.length() > 0) sb.append(" / ").append(target);
        v.setText(sb);
        v.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(a, Look.S3);
        r.addView(v, vlp);
        box.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The track, and the fill as a weighted share of it.
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(roundRect(a, SURFHI, Look.GATE_BAR_DP / 2));
        float f = (float) Math.max(0.0, Math.min(1.0, fill));
        if (f > 0f) {
            View done = new View(a);
            // One progress colour (polish TR-12): ACCENT on the LINE track, met or not; the
            // figure's own colour above says met.
            done.setBackground(roundRect(a, ACCENT,
                    Look.GATE_BAR_DP / 2));
            bar.addView(done, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, f));
        }
        if (f < 1f) {
            bar.addView(new View(a), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f - f));
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, Look.GATE_BAR_DP));
        blp.topMargin = dp(a, Look.S3);
        blp.bottomMargin = dp(a, 12);
        box.addView(bar, blp);

        group(box, A11y.collapse(label) + ": " + A11y.collapse(value == null ? "" : value)
                + (target != null && target.length() > 0 ? " of " + A11y.collapse(target) : ""));
        if (parent != null) parent.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** The drawn switch's track size — published so a caller lays one out at the size it
     *  draws itself at. */
    public static final int SWITCH_W = 34, SWITCH_H = 20;

    /**
     * A DRAWN SWITCH — a 34x20dp track with a 14dp thumb that sits left when off and right
     * when on. Drawn rather than an android.widget.Switch because a platform Switch brings
     * the platform's own theme colours with it and this app has no XML to re-theme it in;
     * two GradientDrawables in a FrameLayout are the whole widget.
     *
     * LIME when on, LINE when off. Not green: an on switch in Settings is the user's own
     * choice shown back to them, which is what lime owns — green is a telemetry-confirmed
     * safe state and belongs to the pump, and amber is pressure. The thumb takes the
     * near-black ON_ACCENT on lime (a white thumb on lime is invisible) and DIM on the off
     * track.
     *
     * It states its state in words for a reader; a caller that groups it (kvRow does)
     * hides it and says the same thing on the row.
     */
    public static View switchView(Activity a, boolean on) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(a);

        View track = new View(a);
        track.setBackground(roundRect(a, on ? ACCENT : LINE, SWITCH_H / 2));
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        f.addView(track, new android.widget.FrameLayout.LayoutParams(
                dp(a, SWITCH_W), dp(a, SWITCH_H)));

        View thumb = new View(a);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(on ? Look.ON_ACCENT : DIM);
        thumb.setBackground(g);
        thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        android.widget.FrameLayout.LayoutParams tp =
                new android.widget.FrameLayout.LayoutParams(dp(a, 14), dp(a, 14));
        tp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        tp.leftMargin = dp(a, on ? SWITCH_W - 14 - 3 : 3);
        f.addView(thumb, tp);

        f.setContentDescription(on ? "on" : "off");
        f.setSelected(on);
        return f;
    }

    /**
     * A CATEGORY CARD THE CALLER FILLS. {@link #card} draws a title, a sub and nothing
     * else; Settings needs its controls INSIDE the card that names them, or the category
     * header is a heading floating above a flat column and the grouping exists only in the
     * reader's head.
     *
     * Returns the column to add rows into. Everything Ui builds takes a ViewGroup parent,
     * so `Ui.row(this, g, …)` / `Ui.stepperRow(this, g, …)` / `Ui.note(this, g, …)` land
     * inside the card unchanged.
     */
    public static LinearLayout cardGroup(Activity a, ViewGroup parent, String title,
                                          String sub) {
        return cardGroup(a, parent, title, sub, 0);
    }

    /**
     * The same card WITH A ⓘ ON ITS TITLE ROW — the category's long explanation, which
     * used to be printed under it as a paragraph, one tap away instead.
     *
     * The title keeps its heading semantics and the button names its subject ("About the
     * safety ceiling"), so a reader hears the category and then an explicitly labelled
     * way into the detail rather than a wall of caption.
     */
    public static LinearLayout cardGroup(Activity a, ViewGroup parent, String title,
                                          String sub, String infoTitle, String full) {
        return cardGroup(a, parent, title, sub, 0, infoTitle, full);
    }

    /** The ⓘ title row on a TONED card (see the tone rule on the overload below). */
    public static LinearLayout cardGroup(Activity a, ViewGroup parent, String title,
                                          String sub, int spine, String infoTitle,
                                          String full, boolean safetyRelevant) {
        LinearLayout c = cardGroup(a, parent, title, sub, spine);
        // The title TextView fillCardGroup just added is the card's first child. Lift it
        // into a row and hang the ⓘ off its right end — building the row here rather than
        // in fillCardGroup leaves every existing card's view tree untouched.
        if (title != null && title.length() > 0 && c.getChildCount() > 0) {
            View t = c.getChildAt(0);
            c.removeViewAt(0);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            infoButton(a, row, infoTitle, infoTitle, full, safetyRelevant);
            c.addView(row, 0);
        }
        return c;
    }

    /** {@link #cardGroup(Activity, ViewGroup, String, String, int, String, String, boolean)};
     *  not safety-relevant. */
    public static LinearLayout cardGroup(Activity a, ViewGroup parent, String title,
                                          String sub, int spine, String infoTitle,
                                          String full) {
        return cardGroup(a, parent, title, sub, spine, infoTitle, full, false);
    }

    /**
     * The same card, given a TONE — and a 3dp coloured left edge ONLY IF THAT TONE IS A
     * WARNING OR A FAULT ({@link Look#earnsSpine}: red or amber).
     *
     * WHY THE RULE CHANGED. The edge used to carry a category or grouping colour, so nearly
     * every card had one - Settings' cards in their category's colour, the Trainer's and the
     * summary's in lime, violet or grey. A colour on every card stops signalling anything:
     * the one card that really was a warning wore the same stripe as "Pressure unit". Now
     * the plain card is the norm and the edge is kept for the card that IS the warning -
     * the simulator standing in for the pump, an unsafe result, the pump under pressure.
     *
     * Callers still state their card's tone rather than being edited one by one; this is
     * the one place that decides whether the tone is drawn. `spine` of 0 is no tone at all.
     * The edge is decoration in the accessibility sense: the card's title is what a reader
     * announces, and the colour never carries anything the title does not already say.
     */
    public static LinearLayout cardGroup(Activity a, ViewGroup parent, String title,
                                          String sub, int spine) {
        LinearLayout c = col(a);
        c.setBackground(roundRect(a, SURF, Look.R_CARD));
        lift(a, c, ELEV_CARD);
        c.setPadding(dp(a, Look.S5), dp(a, Look.S5), dp(a, Look.S5), dp(a, Look.S5));
        if (spine != 0 && Look.earnsSpine(spine)) {
            // A horizontal shell holding the rule and the content column. Building it only
            // when a warning edge is drawn keeps the plain card's view tree exactly as it was.
            LinearLayout shell = new LinearLayout(a);
            shell.setOrientation(LinearLayout.HORIZONTAL);
            shell.setBackground(roundRect(a, SURF, Look.R_CARD));
            lift(a, shell, ELEV_CARD);
            shell.setPadding(dp(a, Look.S5), dp(a, Look.S5), dp(a, Look.S5), dp(a, Look.S5));

            View bar = new View(a);
            bar.setBackground(roundRect(a, spine, 2));
            bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(a, 3),
                    ViewGroup.LayoutParams.MATCH_PARENT);
            barLp.rightMargin = dp(a, Look.S4);
            shell.addView(bar, barLp);

            // The inner column carries the title/sub/rows and NO decoration of its own —
            // the shell already drew the card.
            c.setBackground(null);
            c.setPadding(0, 0, 0, 0);
            shell.addView(c, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            fillCardGroup(a, c, title, sub);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.bottomMargin = dp(a, Look.S4);
            parent.addView(shell, slp);
            return c;
        }
        fillCardGroup(a, c, title, sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, Look.S4);
        parent.addView(c, lp);
        return c;
    }

    /** The card's own title and sub — one copy, so the warning-edged and plain shapes above
     *  can never drift apart in type size, colour or heading semantics. */
    private static void fillCardGroup(Activity a, LinearLayout c, String title, String sub) {
        if (title != null && title.length() > 0) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextColor(TEXT);
            t.setTextSize(Look.SP_HEADING);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
            c.addView(t);
        }
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(a);
            s.setText(sub);
            s.setTextColor(DIM);
            s.setTextSize(Look.SP_CAPTION);
            s.setPadding(0, dp(a, Look.S1), 0, dp(a, Look.S2));
            c.addView(s);
        }
    }

    /**
     * A DEPENDENT BLOCK — the rows that only mean something while their parent switch is
     * on. When the parent goes off they are GREYED, never removed: a block that vanishes
     * takes the memory of what was in it with it, and the screen the user is looking at
     * gets shorter for no stated reason. Greyed, the settings stay where they were,
     * visibly unavailable, and turning the parent back on changes nothing but their
     * colour.
     *
     * SURF fill (a step DOWN from the card it sits in), every text FAINT, everything
     * disabled — the same three-state rule {@link #stateFill} paints an unavailable
     * control with. Disabled children KEEP their text, so a reader still reads the block
     * and hears each row announced as disabled rather than finding a hole.
     *
     * Call it AFTER the block's children are built — it walks what is there.
     */
    public static void dependentBlock(Activity a, ViewGroup g, boolean enabled) {
        if (enabled) return;
        g.setBackground(roundRect(a, SURF, Look.R_CTRL));
        g.setPadding(dp(a, Look.S3), dp(a, Look.S3), dp(a, Look.S3), dp(a, Look.S3));
        greyOut(g);
    }

    /** Walks a block and pushes every control into the unavailable state. */
    private static void greyOut(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof ViewGroup) greyOut((ViewGroup) v);
            v.setEnabled(false);
            v.setClickable(false);
            if (v instanceof TextView) ((TextView) v).setTextColor(FAINT_OFF);
        }
    }

    /**
     * A short status readout: a bold headline plus an optional dim caption underneath —
     * matches the prototype's `.chip` (e.g. "At 20.0 kPa / baseline — before the session").
     */
    public static void chip(Activity a, ViewGroup parent, String headline, String caption,
                             int bg, int fg) {
        // THE STATE CHIP — how a result or the pump's condition is announced: the headline
        // in TEXT, the caption in DIM. A 3dp leading bar in the state colour (`fg`) is drawn
        // ONLY for a warning or a fault (Look#earnsSpine: red or amber) — "Seal did NOT
        // reach the target", the pump under pressure. It used to be on every chip, so
        // "Seal holds" and "Stopped early" wore a stripe as loud as a failure and the stripe
        // stopped meaning anything. The headline's words carry the state either way.
        LinearLayout h = new LinearLayout(a);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setBackground(roundRect(a, bg, Look.R_CTRL));
        h.setPadding(dp(a, Look.S4), dp(a, Look.S4), dp(a, Look.S4), dp(a, Look.S4));
        lift(a, h, ELEV_CARD);

        if (Look.earnsSpine(fg)) {
            View bar = new View(a);
            bar.setBackground(roundRect(a, fg, 2));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(a, 3),
                    ViewGroup.LayoutParams.MATCH_PARENT);
            barLp.rightMargin = dp(a, Look.S4);
            h.addView(bar, barLp);
        }

        LinearLayout c = col(a);
        TextView hl = new TextView(a);
        hl.setText(headline);
        hl.setTextColor(TEXT);
        hl.setTextSize(Look.SP_CAPTION);
        hl.setTypeface(hl.getTypeface(), android.graphics.Typeface.BOLD);
        c.addView(hl);
        if (caption != null && caption.length() > 0) {
            TextView s = new TextView(a);
            s.setText(caption);
            s.setTextColor(DIM);
            s.setTextSize(11f);
            s.setPadding(0, dp(a, 2), 0, 0);
            c.addView(s);

        }
        h.addView(c, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // A chip is the app's status readout — "Seal holds", "Not standardised",
        // "Directly comparable" — and its state is in those words, not in a rail.
        // Grouping keeps the headline and the caption that qualifies it together, and hides
        // any warning rail from the reader. setSelected is left unset here because the
        // chip is not a two-state selection control; its meaning is the whole sentence.
        group(h, A11y.collapse(headline)
                 + (caption != null && caption.length() > 0 ? ". " + A11y.collapse(caption) : ""));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, Look.S3);
        parent.addView(h, lp);
    }

    /* ================================================== THE ONE PASSING MESSAGE (0.10)
     *
     * (incognito; found on a device, API 34) A SYSTEM TOAST CARRIES THE APP'S REAL ICON. From
     * Android 12 the platform draws a text Toast itself, with the application's icon beside
     * it - the real OpenPump logo, whatever the home screen says: choosing "Habits" was
     * confirmed by a toast wearing the OpenPump logo. So while a disguise is on, no system
     * Toast is shown at all. The message is drawn by the app instead, in a window of its own
     * (as a Toast is), so it shows over the screen and over any dialog; icon-free, never
     * focused and never touched - every touch goes through to what is under it - and near the
     * top, so it never covers STOP. The run screen gets the same, disguise or not: there a
     * platform Toast, drawn at the foot of the screen, would sit over STOP. Elsewhere, as
     * itself, the app keeps the platform Toast exactly as it was. Every passing message in the
     * app comes through say() (WiringCheck invariant 235);
     * the snackbar (snack, above) is the other in-app message and was never a system one.
     */

    /** How long a passing message stays: a Toast's LENGTH_SHORT and LENGTH_LONG. */
    static final int SAY_SHORT_MS = 2000;
    static final int SAY_LONG_MS = 3500;

    /** A passing message - `longer` for one worth a second read. */
    public static void say(Activity a, String message, boolean longer) {
        if (a == null || message == null) return;
        if (disguisedNow(a)) {
            // Drawn by the app, or - with no window to draw it in (the screen going away) -
            // not at all: the system Toast would show the real logo.
            sayInApp(a, message, longer ? SAY_LONG_MS : SAY_SHORT_MS);
            return;
        }
        if (runScreenNow(a)) {
            // NOT OVER STOP (0.10, the final run screen): the platform draws a Toast at the foot
            // of the screen, where STOP is. On the run screen the app draws its own, near the
            // top and over any sheet - the run screen's own messages sit above its pinned
            // footer (SessionActivity#toast); this is the one said over a sheet.
            sayInApp(a, message, longer ? SAY_LONG_MS : SAY_SHORT_MS);
            return;
        }
        android.widget.Toast.makeText(a, message, longer ? android.widget.Toast.LENGTH_LONG
                                                         : android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * A PASSING MESSAGE UNDER THE TOP BAR, on any screen (device check EMU9b N4): for a message
     * said as the screen changes to one whose action sits at its foot - the after form's Save
     * - where a system Toast would cover it for its whole hold. Drawn by the app, as on the run
     * screen; when it cannot be drawn it is not said (the screen says the same thing).
     */
    public static void sayTop(Activity a, String message, boolean longer) {
        if (a == null || message == null) return;
        sayInApp(a, message, longer ? SAY_LONG_MS : SAY_SHORT_MS);
    }

    /** Whether the app is shown as a disguise now (Incognito#identity). A screen that is not
     *  the app's one screen (the debug console) has no model to ask, and is the app itself. */
    static boolean disguisedNow(Activity a) {
        return a instanceof SessionActivity
            && Incognito.disguised(Incognito.identity(((SessionActivity) a).model));
    }

    /** Whether the run screen is showing - where a system Toast would sit over STOP. */
    static boolean runScreenNow(Activity a) {
        return a instanceof SessionActivity
            && ((SessionActivity) a).currentScreen == Nav.SCR_RUN;
    }

    /** The app's own passing message: a SURFHI card with a hairline border, the snackbar's
     *  look, in an application window over the screen's own. One at a time - a new one takes
     *  the place of the last. Returns false when it could not be drawn. */
    private static boolean sayInApp(Activity a, String message, int holdMs) {
        try {
            if (a.isFinishing()) return false;
            android.view.WindowManager wm = a.getWindowManager();
            View decor = a.getWindow().getDecorView();
            Object prior = decor.getTag(R.id.passing_message);
            if (prior instanceof View) new SayGone(wm, decor, (View) prior).run();

            FrameLayout frame = new FrameLayout(a);
            frame.setPadding(dp(a, Look.S4), 0, dp(a, Look.S4), 0);
            TextView msg = new TextView(a);
            msg.setText(message);
            msg.setTextColor(TEXT);
            msg.setTextSize(Look.SP_BODY);
            android.graphics.drawable.GradientDrawable bg = roundRect(a, SURFHI, Look.R_CARD);
            bg.setStroke(dp(a, 1), LINE);
            msg.setBackground(bg);
            msg.setPadding(dp(a, Look.S5), dp(a, Look.S4), dp(a, Look.S5), dp(a, Look.S4));
            frame.addView(msg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
            // Under the top bar (60 dp), clear of STOP and every control at the bottom.
            lp.gravity = Gravity.TOP;
            lp.y = dp(a, 68);
            lp.windowAnimations = android.R.style.Animation_Toast;
            lp.setTitle("Message");
            wm.addView(frame, lp);
            decor.setTag(R.id.passing_message, frame);
            // A Toast is announced by TalkBack by itself; this window is not, so say it.
            decor.announceForAccessibility(message);
            decor.postDelayed(new SayGone(wm, decor, frame), holdMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Takes a passing message's window down - on its time, or when the next one comes. */
    private static final class SayGone implements Runnable {
        private final android.view.WindowManager wm;
        private final View decor;
        private final View frame;
        SayGone(android.view.WindowManager wm, View decor, View frame) {
            this.wm = wm; this.decor = decor; this.frame = frame;
        }
        @Override public void run() {
            if (decor.getTag(R.id.passing_message) == frame)
                decor.setTag(R.id.passing_message, null);
            try {
                if (frame.isAttachedToWindow()) wm.removeViewImmediate(frame);
            } catch (Exception ignored) { }
        }
    }

    /** THE SNACKBAR — the Look-styled replacement for an OS Toast on actionable/frequent
     *  feedback (saves, deletes, undo). SURFHI card, hairline border, lime action text,
     *  slides up 8dp and fades in over Look.MS_SNACK_IN, auto-hides after `holdMs`. Only
     *  ONE snackbar is ever on screen: showing a new one cancels and removes any prior one
     *  still visible, matching how a real Snackbar queue would read but without the queue
     *  (this app never needs to show two messages in a row fast enough to matter).
     *
     * `actionLabel`/`action` may both be null for a message-only bar (holdMs should then be
     * Snack.HOLD_MS); when an action is supplied, tapping it both fires the listener and
     * dismisses the bar, and holdMs should be Snack.HOLD_MS_ACTIONABLE.
     *
     * The active bar is tracked as a TAG on `root` itself, not a static field: a static
     * would hold a strong reference to whatever View (and transitively whatever Activity)
     * last called this, leaking a destroyed Activity if it went away while its snackbar
     * was still showing. Tagging root scopes the reference to that root's own view
     * hierarchy, so it is collected along with it — matching this class's "nothing here
     * holds state" contract — and two different Activities' roots never fight over one
     * global slot.
     */
    public static void snack(Activity a, FrameLayout root, String message,
                              String actionLabel, View.OnClickListener action, int holdMs) {
        snack(a, root, message, actionLabel, action, holdMs, 0);
    }

    /** Overload for a message-only bar that still needs clearance above something else
     *  pinned to the bottom of `root` (Run's own control bar) — holds for Snack.HOLD_MS. */
    public static void snack(Activity a, FrameLayout root, String message, int bottomInsetPx) {
        snack(a, root, message, null, null, Snack.HOLD_MS, bottomInsetPx);
    }

    /**
     * The full form, with an extra bottom inset above the bar's own S4 margin.
     *
     * `bottomInsetPx` is 0 for every call site except the Run screen's: Run pins its own
     * control bar (including STOP) below `body`'s normal scroll — see SessionActivity's
     * RunScreenHeightPin — and that bar sits in the SAME bottom corner of `root` this
     * snackbar floats in. A touch still reaches STOP through the bar (it does not
     * intercept touches below its own bounds), but for however long the bar is up (a
     * message-only bar's Snack.HOLD_MS, or Snack.HOLD_MS_ACTIONABLE for one with an
     * action), STOP would be VISUALLY covered right after the one class of action most
     * likely to need it next — a live pressure adjustment or a +30 s extension. Callers
     * on the run screen pass their footer's own measured height here so the bar floats
     * above it instead of over it; every other screen passes 0, unchanged from before
     * this parameter existed.
     */
    public static void snack(Activity a, FrameLayout root, String message,
                              String actionLabel, View.OnClickListener action, int holdMs,
                              int bottomInsetPx) {
        if (root == null) return;
        if (a instanceof SessionActivity) ((SessionActivity) a).journalSnack(message);
        Object prior = root.getTag();
        if (prior instanceof View) { root.removeView((View) prior); root.setTag(null); }

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable bg = roundRect(a, SURFHI, Look.R_CARD);
        bg.setStroke(dp(a, 1), LINE);
        bar.setBackground(bg);
        lift(a, bar, ELEV_POP);
        bar.setPadding(dp(a, Look.S5), dp(a, Look.S4), dp(a, Look.S4), dp(a, Look.S4));

        TextView msg = new TextView(a);
        msg.setText(message);
        msg.setTextColor(TEXT);
        msg.setTextSize(Look.SP_BODY);
        bar.addView(msg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (actionLabel != null && action != null) {
            Button act = new Button(a);
            act.setText(actionLabel);
            act.setAllCaps(false);
            act.setTextColor(ACCENT);
            act.setTypeface(act.getTypeface(), android.graphics.Typeface.BOLD);
            act.setBackground(null);
            act.setMinWidth(0); act.setMinimumWidth(0);
            act.setPadding(dp(a, Look.S4), dp(a, Look.S3), 0, dp(a, Look.S3));
            act.setOnTouchListener(new Press());
            final View barRef = bar;
            act.setOnClickListener(new SnackActionTap(root, barRef, action));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 48));
            bar.addView(act, alp);
            // Device check EMU10 (Low): a bar with an action is something to tap, so a tap on
            // it lands on it - on its message too - never on the screen under it (the summary's
            // answers sit right there). A message-only bar stays see-through, as it always was.
            bar.setOnTouchListener(new SnackSwallow());
        }

        android.widget.FrameLayout.LayoutParams lp2 = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        lp2.leftMargin = dp(a, Look.S4); lp2.rightMargin = dp(a, Look.S4);
        lp2.bottomMargin = dp(a, Look.S4) + Math.max(0, bottomInsetPx);
        bar.setAlpha(0f);
        bar.setTranslationY(dp(a, 8));
        root.addView(bar, lp2);
        root.setTag(bar);
        // Marked as held above a pinned footer, so it can follow that footer (snackAbove).
        if (bottomInsetPx > 0) bar.setTag(SNACK_ABOVE);

        // A stock Toast is announced by TalkBack automatically; this hand-rolled bar is
        // just another View being added to the tree and is silent unless told otherwise.
        // ACCESSIBILITY_LIVE_REGION_POLITE asks the service to announce the region once it
        // settles, and announceForAccessibility below covers services that do not pick up
        // a live region on a view added this same frame — belt and suspenders, since the
        // bar auto-hides in a few seconds and this is the one chance a touch-exploring
        // user gets to hear it. The action label is folded into the same announcement
        // (rather than left to be found by touch) for the same reason.
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (android.os.Build.VERSION.SDK_INT >= 19)
            bar.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        String announce = message + (actionLabel != null && action != null
                ? ". " + actionLabel + " available." : "");
        bar.announceForAccessibility(announce);

        float scale;
        try {
            scale = android.provider.Settings.Global.getFloat(a.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f);
        } catch (Exception ex) { scale = 1.0f; }
        int inMs = Look.resolveDuration(Look.MS_SNACK_IN, scale);
        bar.animate().alpha(1f).translationY(0f).setDuration(inMs).start();

        root.postDelayed(new SnackDismiss(root, bar), holdMs);
    }

    /** The tag of a snackbar held above a pinned footer. */
    static final String SNACK_ABOVE = "snack-above-footer";

    /**
     * THE SNACK FOLLOWS THE FOOTER (device check EMU9 H2). The run screen's inset is measured
     * when the snack is shown; a tap that changes the step (Skip) grows the footer a frame
     * later, and the snack placed a moment before was left over STOP. Called when the footer
     * is laid out again: the bar showing, if it is one held above it, moves to `insetPx`.
     */
    public static void snackAbove(Activity a, FrameLayout root, int insetPx) {
        if (root == null || insetPx <= 0) return;
        Object bar = root.getTag();
        if (!(bar instanceof View) || !SNACK_ABOVE.equals(((View) bar).getTag())) return;
        View v = (View) bar;
        if (!(v.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams)) return;
        android.widget.FrameLayout.LayoutParams lp =
            (android.widget.FrameLayout.LayoutParams) v.getLayoutParams();
        int want = dp(a, Look.S4) + insetPx;
        if (lp.bottomMargin == want) return;
        lp.bottomMargin = want;
        v.setLayoutParams(lp);
    }

    /**
     * Marks the snack showing on `root` as one held above the run screen's footer, whatever
     * inset it was shown with (device check EMU9b N2): a footer just rebuilt has no height to
     * measure, and a bar left unmarked was never moved off STOP when it was laid out.
     */
    public static void holdAboveFooter(FrameLayout root) {
        Object bar = root == null ? null : root.getTag();
        if (bar instanceof View) ((View) bar).setTag(SNACK_ABOVE);
    }

    /** Overload for a message-only bar — holds for Snack.HOLD_MS. */
    public static void snack(Activity a, FrameLayout root, String message) {
        snack(a, root, message, null, null, Snack.HOLD_MS);
    }

    /**
     * THE BAR TIMES OUT INTO NOTHING TAPPABLE (device check EMU10, Low). The Resume bar went
     * the instant its time was up, and a tap aimed at "Resume" a moment late landed on the
     * summary under it and recorded a "Too much" nobody chose. A bar that carried an action
     * now stays where it was for Snack.LATE_TAP_GUARD_MS after it times out: faded out, its
     * action switched off, hidden from a screen reader, still swallowing taps - and only then
     * is it removed. It gives up the root's slot at once, so a new bar is never held back.
     */
    private static final class SnackDismiss implements Runnable {
        private final FrameLayout root; private final View bar;
        SnackDismiss(FrameLayout root, View bar) { this.root = root; this.bar = bar; }
        @Override public void run() {
            if (root.getTag() == bar) root.setTag(null);
            if (bar.getParent() != root) return;
            if (!(bar instanceof ViewGroup) || ((ViewGroup) bar).getChildCount() < 2) {
                root.removeView(bar);                  // message only: nothing to tap late
                return;
            }
            ViewGroup g = (ViewGroup) bar;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                c.setEnabled(false);
                c.setClickable(false);
                c.setOnClickListener(null);
            }
            bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            bar.animate().alpha(0f).setDuration(Look.MS_SNACK_IN).start();
            root.postDelayed(new SnackGone(root, bar), Snack.LATE_TAP_GUARD_MS);
        }
    }

    /** The tap guard's end: the faded bar is taken away. */
    private static final class SnackGone implements Runnable {
        private final FrameLayout root; private final View bar;
        SnackGone(FrameLayout root, View bar) { this.root = root; this.bar = bar; }
        @Override public void run() {
            if (bar.getParent() == root) root.removeView(bar);
            if (root.getTag() == bar) root.setTag(null);
        }
    }

    /** A bar with an action keeps every tap on it for itself (its button still gets its own:
     *  a child is offered the touch first). */
    private static final class SnackSwallow implements View.OnTouchListener {
        @Override public boolean onTouch(View v, android.view.MotionEvent e) { return true; }
    }

    private static final class SnackActionTap implements View.OnClickListener {
        private final FrameLayout root; private final View bar; private final View.OnClickListener inner;
        SnackActionTap(FrameLayout root, View bar, View.OnClickListener inner) {
            this.root = root; this.bar = bar; this.inner = inner;
        }
        @Override public void onClick(View v) {
            if (bar.getParent() == root) root.removeView(bar);
            if (root.getTag() == bar) root.setTag(null);
            inner.onClick(v);
        }
    }

    /* ===================== ITEM 14 - TOGGLE CHIP, LINE SWATCH, TEXT LINK ==================
     *
     * The Progress chart's series controls (its one-of-N switches are {@link #segmented}),
     * kept here so any screen that needs an on/off chip or a quiet link draws the same one. */

    /**
     * A CHECKBOX, AS A SCREEN READER SHOULD HEAR IT: "MSEG, before a session, checkbox,
     * checked". A chip built from a row of views carries no such role on its own, and
     * without it the state lives only in a fill colour and a tick. A named class, not a
     * lambda (project rule).
     */
    public static final class CheckboxA11y extends View.AccessibilityDelegate {
        private final boolean checked;
        public CheckboxA11y(boolean checked) { this.checked = checked; }
        @Override public void onInitializeAccessibilityNodeInfo(
                View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName("android.widget.CheckBox");
            info.setCheckable(true);
            info.setChecked(checked);
        }
        @Override public void onInitializeAccessibilityEvent(
                View host, android.view.accessibility.AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(host, event);
            event.setClassName("android.widget.CheckBox");
            event.setChecked(checked);
        }
    }

    /**
     * A TOGGLE CHIP: one line, fully rounded, drawn 36dp tall inside a 48dp target (a
     * {@link Flow} with a -4dp line gap sets them 8dp apart). ON: SURFACEHI fill, TEXT words
     * and a check mark first, so "on" never rests on colour alone. OFF: no fill, a 1dp LINE
     * ring, DIM words, and its `mark` faded to 40%.
     *
     * `mark` is an optional small leading drawable `markWDp` x `markHDp` (the chart's series
     * chips put a sample of the series' own line there, see {@link LineSwatch}); `sub` an
     * optional smaller DIM word after the label ("pre", "post"). `said` is what a reader
     * hears, announced as a checkbox with its state.
     */
    public static LinearLayout toggleChip(Activity a, String label, String sub,
            android.graphics.drawable.Drawable mark, int markWDp, int markHDp, boolean on,
            String said, View.OnClickListener tap) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable fill = roundRect(a,
                on ? SURFHI : android.graphics.Color.TRANSPARENT, 18);
        if (!on) fill.setStroke(Math.max(1, dp(a, 1)), LINE);
        int inset = dp(a, 6);
        c.setBackground(new android.graphics.drawable.InsetDrawable(fill, 0, inset, 0, inset));
        // After the background, which would otherwise replace it with its own insets.
        c.setPadding(dp(a, 10), 0, dp(a, 12), 0);

        if (on) {
            android.widget.ImageView check = iconView(a, R.drawable.ic_check, TEXT, 14);
            ((LinearLayout.LayoutParams) check.getLayoutParams()).rightMargin = dp(a, Look.S3);
            c.addView(check);
        }
        if (mark != null) {
            android.widget.ImageView m = new android.widget.ImageView(a);
            m.setImageDrawable(mark);
            m.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            m.setImageAlpha(on ? 255 : 102);
            decorative(m);
            LinearLayout.LayoutParams mlp =
                    new LinearLayout.LayoutParams(dp(a, markWDp), dp(a, markHDp));
            mlp.rightMargin = dp(a, Look.S3);
            c.addView(m, mlp);
        }
        TextView t = new TextView(a);
        t.setSingleLine(true);
        t.setTextSize(Look.SP_CHIP);
        medium(t);
        t.setTextColor(on ? TEXT : DIM);
        if (sub == null || sub.length() == 0) {
            t.setText(label);
        } else {
            // One TextView, so the two words share a baseline; the phase is the smaller,
            // regular-weight DIM word the mock sets after the method.
            android.text.SpannableString s = new android.text.SpannableString(label + " " + sub);
            int from = label.length() + 1, to = s.length();
            s.setSpan(new android.text.style.RelativeSizeSpan(Look.SP_CAPTION / Look.SP_CHIP),
                    from, to, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new android.text.style.ForegroundColorSpan(DIM),
                    from, to, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new android.text.style.TypefaceSpan("sans-serif"),
                    from, to, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            t.setText(s);
        }
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        c.addView(t);

        c.setContentDescription(said != null ? said : label);
        c.setAccessibilityDelegate(new CheckboxA11y(on));
        c.setClickable(true);
        c.setFocusable(true);
        c.setOnTouchListener(new Press());
        if (tap != null) c.setOnClickListener(tap);
        c.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 48)));
        return c;
    }

    /**
     * A SHORT LINE SAMPLE: a series' own ink, 2.4dp, round ends, dashed when `dashed` - the
     * mark on a chart's series chip, so the chip doubles as the chart's key (colour for the
     * method, dashes for post, exactly as the trace is drawn).
     */
    public static final class LineSwatch extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float density;
        public LineSwatch(Activity a, int colour, boolean dashed) {
            density = a.getResources().getDisplayMetrics().density;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(2.4f * density);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setColor(colour);
            if (dashed)
                p.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{ 4f * density, 3.5f * density }, 0));
        }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float y = b.exactCenterY();
            c.drawLine(b.left + density, y, b.right - density, y, p);
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            p.setColorFilter(cf);
            invalidateSelf();
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return Math.round(18 * density); }
        @Override public int getIntrinsicHeight() { return Math.round(6 * density); }
    }

    /**
     * A SMALL TEXT LINK: lime words, no box, a full 48dp-tall target - a secondary action
     * that sits on a row's edge ("All" and "None" on the chart's series row). `enabled`
     * false greys it to FAINT and makes it inert: a link that would change nothing should
     * not look like it does something, and a reader hears it as unavailable.
     */
    public static TextView textLink(Activity a, String label, boolean enabled,
                                    View.OnClickListener tap) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setSingleLine(true);
        t.setTextSize(13.5f);
        medium(t);
        t.setTextColor(enabled ? ACCENT : FAINT);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(a, 48));
        t.setPadding(dp(a, 7), 0, dp(a, 7), 0);
        t.setEnabled(enabled);
        if (enabled) {
            t.setClickable(true);
            t.setFocusable(true);
            t.setOnTouchListener(new Press());
            if (tap != null) t.setOnClickListener(tap);
        }
        t.setAccessibilityDelegate(new ButtonA11y());
        return t;
    }

    /** Announces a text link as a button, which is what it does. */
    private static final class ButtonA11y extends View.AccessibilityDelegate {
        @Override public void onInitializeAccessibilityNodeInfo(
                View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName("android.widget.Button");
        }
    }

    /**
     * ONE ICON FROM THE APP'S OUTLINE SET (res/drawable/ic_*.xml): a 24-unit grid, a 1.8
     * stroke, round ends, drawn white and tinted here. One set at one weight is what makes the
     * tab bar, rows and buttons read as one designed thing; before it the app mixed unrelated
     * text glyphs (a dot, a list, a half-square, a triangle) at different sizes.
     *
     * The drawable is mutated before tinting, so tinting one view never recolours another that
     * shares the resource.
     */
    public static android.graphics.drawable.Drawable icon(Activity a, int res, int colour) {
        android.graphics.drawable.Drawable d = a.getDrawable(res).mutate();
        d.setTint(colour);
        return d;
    }

    /** {@link #icon} in its own square view, `sizeDp` across. Decorative: the words next to it
     *  (or the control's content description) carry the meaning, so a screen reader skips it. */
    public static android.widget.ImageView iconView(Activity a, int res, int colour, int sizeDp) {
        android.widget.ImageView v = new android.widget.ImageView(a);
        v.setImageDrawable(icon(a, res, colour));
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(a, sizeDp), dp(a, sizeDp)));
        decorative(v);
        return v;
    }

    /*
     * Unit-aware formatting lives on Model.Fmt, not here, even though the brief that
     * asked for it says "in Ui.java is fine". Reason: test.sh's javac invocation
     * compiles exactly Proto.java, Model.java and SelfTest.java for the desktop
     * self-test — it does not (and, per the task constraints, must not be made to)
     * compile Ui.java, which needs android.app/android.view/android.widget. A Fmt
     * nested in Ui would be permanently unreachable from SelfTest, which is exactly
     * the kind of untestable-by-construction logic Task 1's review already flagged
     * once. Model.Fmt has the identical API (Model.Fmt.p/r/t) and behaviour described
     * in the brief — see Model.java.
     */
}
