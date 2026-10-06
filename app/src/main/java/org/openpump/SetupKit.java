package org.openpump;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * THE FIRST-RUN SETUP'S OWN CONTROLS, shared with the trainer's setup (0.10).
 *
 * The owner liked the first-run setup's cylinder step better than the trainer's - cards with
 * an Edit, a dashed "Add another cylinder" and a bottom sheet with a drawing of where to
 * measure - and asked for the trainer's step to be the same thing. So it is the same code:
 * the pieces SetupScreen drew for itself live here, and both screens draw them. With them go
 * the small controls they are made of (the setup's buttons, steppers and chips) and the
 * pinned step bar and footer, which the trainer's setup now wears too.
 *
 * Every word comes from {@link SetupText}. Nothing here commands the pump. A change to the
 * rack is written to the model and saved the moment it is made, through
 * SessionActivity#addCylinder for a new cylinder, and the caller is told so it can redraw.
 */
final class SetupKit {
    private final SessionActivity a;

    SetupKit(SessionActivity a) { this.a = a; }

    /** Told after the rack changed; `saved` when a cylinder was added or edited (not removed). */
    interface RackListener { void rackChanged(boolean saved); }

    /* ================================================================ small pieces */

    int dp(int v) { return Ui.dp(a, v); }

    static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    LinearLayout.LayoutParams gapped(int topDp) {
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(topDp);
        return lp;
    }

    TextView text(String s, float sp, int colour) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(colour);
        return t;
    }

    TextView quietCaps(String s) {
        TextView t = text(s.toUpperCase(java.util.Locale.US), 12f, Ui.DIM);
        Ui.semibold(t);
        t.setLetterSpacing(0.06f);
        return t;
    }

    /** − value + , each button 48 dp. */
    LinearLayout stepper(String value, String minusSaid, String plusSaid,
                         View.OnClickListener minus, View.OnClickListener plus) {
        LinearLayout s = new LinearLayout(a);
        s.setOrientation(LinearLayout.HORIZONTAL);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.addView(stepKey("−", minusSaid, minus), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView v = text(value, 15f, Ui.TEXT);
        v.setTypeface(Typeface.MONOSPACE);
        v.setGravity(Gravity.CENTER);
        v.setMinWidth(dp(84));
        s.addView(v);
        s.addView(stepKey("+", plusSaid, plus), new LinearLayout.LayoutParams(dp(48), dp(48)));
        return s;
    }

    Button stepKey(String glyph, String said, View.OnClickListener tap) {
        Button b = new Button(a);
        b.setText(glyph);
        b.setAllCaps(false);
        b.setTextSize(20f);
        b.setTextColor(Ui.TEXT);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(new android.graphics.drawable.InsetDrawable(
            Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL), dp(3)));
        b.setContentDescription(said);
        b.setOnClickListener(tap);
        b.setOnTouchListener(new Ui.Press());
        return b;
    }

    /** A footer or row button: lime and filled for the way forward, outlined otherwise. */
    Button button(String label, boolean primary) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(Look.SP_BODY);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(dp(16), 0, dp(16), 0);
        if (primary) {
            b.setTextColor(Look.ON_ACCENT);
            b.setBackground(Ui.roundRect(a, Ui.ACCENT, Look.R_CARD));
        } else {
            b.setTextColor(Ui.TEXT);
            android.graphics.drawable.GradientDrawable g = Ui.roundRect(a,
                android.graphics.Color.TRANSPARENT, Look.R_CARD);
            g.setStroke(Math.max(1, dp(1)), Ui.LINE);
            b.setBackground(g);
        }
        b.setOnTouchListener(new Ui.Press());
        return b;
    }

    /** A size or quick-name chip, 48 dp tall, ringed in lime when chosen. */
    Button chip(String label, boolean on) {
        Button q = new Button(a);
        q.setText(label);
        q.setAllCaps(false);
        q.setTextSize(Look.SP_LABEL);
        q.setTextColor(on ? Ui.ACCENT : Ui.TEXT);
        q.setPadding(dp(4), 0, dp(4), 0);
        q.setMinWidth(0);
        q.setMinimumWidth(0);
        q.setMinHeight(0);
        q.setMinimumHeight(0);
        q.setStateListAnimator(null);
        android.graphics.drawable.GradientDrawable g = Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL);
        g.setStroke(dp(2), on ? Ui.ACCENT : Look.SETUP_SEG_EDGE);
        q.setBackground(new android.graphics.drawable.InsetDrawable(g, 0, dp(4), 0, dp(4)));
        q.setSelected(on);
        q.setOnTouchListener(new Ui.Press());
        return q;
    }

    /* ================================================================ pinned chrome */

    /** The ground the pinned pieces stand on: opaque and lifted, so a step scrolling under
     *  them never shows through - the treatment the app's own step bar has. */
    LinearLayout chrome() {
        LinearLayout bar = Ui.col(a);
        bar.setBackgroundColor(Ui.BG);
        bar.setElevation(dp(8));
        return bar;
    }

    /** A pinned "TITLE  n/N" bar and its N segments, step (0-based) and those before it lit.
     *  A step at or past N lights every segment and says `doneAt` in place of the count. */
    View stepBar(String title, int step, int steps, String doneAt) {
        LinearLayout bar = chrome();
        bar.setPadding(dp(14), dp(Look.S3), dp(14), dp(Look.S4));
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        // polish G-Setup: one plain line - "Trainer setup · step 1 of 6" - in place of a
        // capitals title and a bare "1/6".
        boolean done = step >= steps && doneAt != null;
        TextView name = text(title + " · " + (done ? doneAt : "step " + (step + 1) + " of " + steps),
            12.5f, Ui.DIM);
        Ui.semibold(name);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.group(top, title + (done ? ", " + doneAt : ", step " + (step + 1) + " of " + steps));
        bar.addView(top, full());

        LinearLayout segs = new LinearLayout(a);
        segs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < steps; i++) {
            View seg = new View(a);
            seg.setBackground(Ui.roundRect(a, i <= step ? Ui.ACCENT : Ui.LINE, Look.WIZ_STEP_R_DP));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(Look.WIZ_STEP_H_DP), 1f);
            if (i < steps - 1) sl.rightMargin = dp(Look.WIZ_STEP_GAP_DP);
            segs.addView(seg, sl);
        }
        segs.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.addView(segs, gapped(Look.S3));
        return bar;
    }

    /**
     * A pinned footer: Back, and the way forward filling the rest. `backShown` false keeps
     * Back's place (so the forward button does not jump between steps); a null `nextLabel`
     * leaves Back alone, for a step whose own button is the way forward.
     */
    View footer(String backLabel, boolean backShown, View.OnClickListener back,
                String nextLabel, boolean nextEnabled, View.OnClickListener next) {
        LinearLayout bar = chrome();
        bar.setPadding(dp(14), dp(Look.S4), dp(14), dp(Look.S5));
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button b = button(backLabel, false);
        b.setOnClickListener(back);
        b.setVisibility(backShown ? View.VISIBLE : View.INVISIBLE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        bl.rightMargin = dp(Look.S3);
        row.addView(b, bl);
        if (nextLabel != null) {
            Button n = button(nextLabel, true);
            n.setOnClickListener(next);
            // One disabled look (polish SYS-10): the unavailable fill, never an alpha.
            if (nextEnabled) n.setEnabled(true); else Ui.stateFill(a, n, false, false);
            row.addView(n, new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        bar.addView(row, full());
        return bar;
    }

    /* ================================================================ the rack */

    /**
     * THE RACK AS CARDS: one card per cylinder (its drawing, its name with an ACTIVE badge on
     * the one in use, its size and an Edit), then the dashed "Add" card. `pickable` makes a
     * card that is not in use a target that puts it in use; `confirmRemove` asks before the
     * editor's Remove, for a rack that may already have readings beside it.
     */
    void rack(LinearLayout parent, RackListener listener, boolean pickable, boolean confirmRemove) {
        int n = a.model.cylinders.size();
        int active = a.model.activeCylinderIndex();
        for (int i = 0; i < n; i++)
            cylinderCard(parent, a.model.cylinders.get(i), i, i == active,
                pickable, listener, confirmRemove);
        addCylinderButton(parent, n == 0, listener, confirmRemove);
    }

    private void cylinderCard(LinearLayout parent, Model.Cylinder cyl, int index, boolean active,
                              boolean pickable, RackListener listener, boolean confirmRemove) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(12), dp(Look.S4), dp(Look.S3), dp(Look.S4));
        android.graphics.drawable.GradientDrawable bg = Ui.roundRect(a, Ui.SURFHI, Look.R_CARD);
        bg.setStroke(Math.max(1, dp(1)), Look.SETUP_SEG_EDGE);
        row.setBackground(bg);
        CylinderPic pic = new CylinderPic(a);
        pic.setBackground(Ui.roundRect(a, Ui.BG, Look.R_CTRL));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(44), dp(44));
        pl.rightMargin = dp(12);
        row.addView(pic, pl);
        LinearLayout col = Ui.col(a);
        LinearLayout nameRow = new LinearLayout(a);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        String label = cyl.label == null || cyl.label.trim().length() == 0
            ? "Cylinder " + (index + 1) : cyl.label;
        TextView name = text(label, 14f, Ui.TEXT);
        Ui.medium(name);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameRow.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
        if (active) {
            TextView tag = text(SetupText.CYL_ACTIVE.toUpperCase(java.util.Locale.US), 10f,
                Look.ON_ACCENT);
            tag.setTypeface(Typeface.DEFAULT_BOLD);
            tag.setBackground(Ui.roundRect(a, Ui.ACCENT, 5));
            tag.setPadding(dp(5), 0, dp(5), 0);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.leftMargin = dp(Look.S2);
            nameRow.addView(tag, tl);
        }
        col.addView(nameRow);
        // What it is used for leads the line (owner, 2026-09-30): "Length · 4.5 cm inside · ...".
        String line = SetupText.cylinderLine(cyl.role, cyl.boreCm, cyl.lengthCm);
        col.addView(text(line, 12.5f, Ui.DIM));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button edit = button(SetupText.CYL_EDIT, false);
        edit.setTextSize(Look.SP_LABEL);
        edit.setContentDescription(SetupText.CYL_EDIT + " " + label);
        edit.setOnClickListener(new EditCylinder(index, listener, confirmRemove));
        row.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        boolean picks = pickable && !active;
        Ui.group(row, label + (active ? ", " + SetupText.CYL_ACTIVE : "") + ". " + line
            + (picks ? ". " + SetupText.CYL_PICK_SAID : ""));
        if (picks) {
            row.setClickable(true);
            row.setOnClickListener(new PickCylinder(index, listener));
            row.setOnTouchListener(new Ui.Press());
        }
        parent.addView(row, gapped(Look.S3));
    }

    private void addCylinderButton(LinearLayout parent, boolean first, RackListener listener,
                                   boolean confirmRemove) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setMinimumHeight(dp(56));
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        android.graphics.drawable.GradientDrawable bg = Ui.roundRect(a,
            android.graphics.Color.TRANSPARENT, Look.R_CARD);
        bg.setStroke(dp(2), Look.SETUP_SEG_EDGE, dp(5), dp(4));
        b.setBackground(bg);
        TextView plus = text("+", 22f, Ui.ACCENT);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL));
        Ui.decorative(plus);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(36), dp(36));
        pl.rightMargin = dp(12);
        b.addView(plus, pl);
        LinearLayout col = Ui.col(a);
        String title = first ? SetupText.CYL_ADD_FIRST : SetupText.CYL_ADD_MORE;
        String sub = first ? SetupText.CYL_ADD_FIRST_LINE : SetupText.CYL_ADD_MORE_LINE;
        TextView t = text(title, 14.5f, Ui.TEXT);
        Ui.medium(t);
        col.addView(t);
        col.addView(text(sub, 12.5f, Ui.DIM));
        b.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.group(b, title + ". " + sub);
        b.setClickable(true);
        b.setOnClickListener(new EditCylinder(-1, listener, confirmRemove));
        b.setOnTouchListener(new Ui.Press());
        parent.addView(b, gapped(Look.S3));
    }

    private final class EditCylinder implements View.OnClickListener {
        private final int index;
        private final RackListener listener;
        private final boolean confirmRemove;
        EditCylinder(int i, RackListener l, boolean c) { index = i; listener = l; confirmRemove = c; }
        @Override public void onClick(View v) {
            new CylinderEditor(index, listener, confirmRemove).open();
        }
    }

    /** Puts a cylinder in use - Settings' own pick (PickCylinderTap), from its card. */
    private final class PickCylinder implements View.OnClickListener {
        private final int index;
        private final RackListener listener;
        PickCylinder(int i, RackListener l) { index = i; listener = l; }
        @Override public void onClick(View v) {
            if (index < 0 || index >= a.model.cylinders.size()) return;
            a.model.activeCylinder = index;
            Store.save(a, a.model);
            if (listener != null) listener.rackChanged(false);
        }
    }

    /** A cylinder's small drawing on its card. */
    private static final class CylinderPic extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();

        CylinderPic(android.content.Context c) {
            super(c);
            p.setColor(Look.INFO_BLUE);
            p.setStyle(Paint.Style.STROKE);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            float u = getWidth() / 44f;
            p.setStrokeWidth(2 * u);
            box.set(12 * u, 10 * u, 32 * u, 34 * u);
            c.drawRoundRect(box, 4 * u, 4 * u, p);
            c.drawLine(12 * u, 15 * u, 32 * u, 15 * u, p);
        }
    }

    /**
     * Where to measure: the inside diameter across the opening, and the length from the base
     * of the flange to the end. Drawn on a 300-wide, 96 dp-tall plan with the "length" word
     * UNDER its dimension line, clear of every stroke, and the view is made tall enough for
     * that word at whatever size the phone's font setting draws it ({@link #heightPx}).
     */
    private static final class MeasureDrawing extends View {
        /** The drawing's own height in dp, above the "length" word. */
        private static final int PLAN_DP = 96;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();

        MeasureDrawing(android.content.Context c) {
            super(c);
            p.setTextSize(11f * c.getResources().getDisplayMetrics().scaledDensity);
        }

        /** The whole view's height: the plan, a small gap, and one line of the word. */
        int heightPx() {
            float d = getResources().getDisplayMetrics().density;
            Paint.FontMetrics fm = p.getFontMetrics();
            return (int) Math.ceil(PLAN_DP * d + 4 * d + (fm.descent - fm.ascent));
        }

        @Override protected void onDraw(Canvas c) {
            float u = getWidth() / 300f;
            float d = getResources().getDisplayMetrics().density;
            p.setStrokeWidth(2 * d);
            // The cylinder and its flange.
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Look.INFO_BLUE);
            r.set(70 * u, 16 * d, 260 * u, 72 * d);
            c.drawRoundRect(r, 10 * d, 10 * d, p);
            r.set(58 * u, 10 * d, 70 * u, 78 * d);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Look.SURFACEHI);
            c.drawRoundRect(r, 3 * d, 3 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Look.INFO_BLUE);
            c.drawRoundRect(r, 3 * d, 3 * d, p);
            // The inside diameter: across the opening, at the flange, its word to the left -
            // moved right on a narrow sheet so the word never runs off the edge.
            float word = p.measureText(SetupText.ED_DRAWING_INSIDE);
            float bx = Math.max(40 * u, word + 12 * d);
            p.setColor(Look.ACCENT);
            c.drawLine(bx, 24 * d, bx, 64 * d, p);
            c.drawLine(bx - 4 * d, 24 * d, bx + 4 * d, 24 * d, p);
            c.drawLine(bx - 4 * d, 64 * d, bx + 4 * d, 64 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(SetupText.ED_DRAWING_INSIDE, bx - 7 * d, 48 * d, p);
            // The length: from the base of the flange to the end, 8 dp under the flange, and
            // its word under the line.
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Look.TEXT);
            c.drawLine(70 * u, 88 * d, 260 * u, 88 * d, p);
            c.drawLine(70 * u, 84 * d, 70 * u, 92 * d, p);
            c.drawLine(260 * u, 84 * d, 260 * u, 92 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(SetupText.ED_DRAWING_LENGTH, 165 * u, PLAN_DP * d + 4 * d - fm.ascent, p);
        }
    }

    /* ================================================================ the cylinder editor */

    /**
     * ADD OR EDIT ONE CYLINDER, as a bottom sheet: a name (with quick names), a drawing of
     * where to measure, the inside diameter and the length - common sizes as chips, and an
     * exact stepper for each. It keeps its own working copy until Add or Save; the rack is
     * written through SessionActivity#addCylinder (a new one) or in place (an edit), within
     * Model.Cylinder#clamp's bounds. The first cylinder becomes the active one; later ones
     * leave the active one as it is. "Used for" is the person's to say (owner, 2026-09-30):
     * it used to be read from the size against a logged girth, so a length cylinder did not
     * pull until a girth was logged. The quick name "Length" picks Length for them.
     */
    private final class CylinderEditor {
        private final int index;
        private final RackListener listener;
        private final boolean confirmRemove;
        private double bore = 5.0, len = 23.0;
        private String role = Model.Cylinder.ROLE_GIRTH;
        private EditText name;
        private LinearLayout sizes;
        private LinearLayout roles;
        private AlertDialog dialog;

        CylinderEditor(int index, RackListener listener, boolean confirmRemove) {
            this.index = index;
            this.listener = listener;
            this.confirmRemove = confirmRemove;
            Model.Cylinder c = cylinderAt(index);
            if (c != null) { bore = c.boreCm; len = c.lengthCm; role = c.role; }
        }

        void open() {
            Model.Cylinder c = cylinderAt(index);
            LinearLayout col = Ui.col(a);
            int pad = dp(Look.S5);
            col.setPadding(pad, dp(Look.S1), pad, pad);

            col.addView(text(SetupText.ED_NAME, 12.5f, Ui.DIM));
            name = new EditText(a);
            name.setSingleLine(true);
            name.setHint(SetupText.ED_NAME_HINT);
            name.setTextColor(Ui.TEXT);
            name.setHintTextColor(Ui.DIM);
            name.setTextSize(Look.SP_BODY);
            name.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(SetupText.ED_NAME_MAX) });
            android.graphics.drawable.GradientDrawable nb = Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL);
            nb.setStroke(dp(2), Look.SETUP_SEG_EDGE);
            name.setBackground(nb);
            name.setPadding(dp(12), dp(11), dp(12), dp(11));
            name.setMinimumHeight(dp(48));
            String start = c != null ? c.label
                : a.model.cylinders.isEmpty() ? SetupText.ED_FIRST_NAME : "";
            name.setText(start);
            name.addTextChangedListener(new NameWatch());
            col.addView(name, gapped(Look.S2));
            /* polish SU-8: the name suggestions are a small "Name ideas" line of text links
             * under the field - four big Girth / Length chips right above "Used for: Girth /
             * Length" read as the same choice twice. */
            LinearLayout quick = new LinearLayout(a);
            quick.setOrientation(LinearLayout.HORIZONTAL);
            quick.setGravity(Gravity.CENTER_VERTICAL);
            quick.addView(text(NAME_IDEAS, 12.5f, Ui.DIM));
            for (int i = 0; i < SetupText.ED_QUICK.length; i++) {
                String full = SetupText.ED_QUICK[i] + SetupText.ED_QUICK_SUFFIX;
                TextView q = Ui.textLink(a, SetupText.ED_QUICK[i], true, new QuickName(full));
                q.setContentDescription(NAME_IDEAS + " " + full);
                quick.addView(q);
            }
            col.addView(quick, gapped(Look.S1));

            roles = Ui.col(a);
            col.addView(roles, full());
            fillRoles();

            MeasureDrawing drawing = new MeasureDrawing(a);
            drawing.setContentDescription(SetupText.ED_DRAWING_SAID);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, drawing.heightPx());
            dl.topMargin = dp(Look.S4);
            col.addView(drawing, dl);

            sizes = Ui.col(a);
            col.addView(sizes, full());
            fillSizes();

            ScrollView sv = new ScrollView(a);
            sv.addView(col);
            AlertDialog.Builder b = Ui.dialog(a)
                .setTitle(c == null ? SetupText.ED_ADD_TITLE : SetupText.ED_EDIT_TITLE)
                .setView(sv)
                .setPositiveButton(c == null ? SetupText.ED_ADD : SetupText.ED_SAVE, new SaveTap());
            if (c == null) b.setNegativeButton(SetupText.ED_CANCEL, null);
            else b.setNegativeButton(SetupText.ED_REMOVE, new RemoveTap());
            dialog = b.show();
            Ui.sheet(a, dialog);
            nameChanged();
        }

        /** "Used for": Girth / Length as the app's two-button switch (polish SU-8), with what
         *  Length does behind the label's info button. */
        private void fillRoles() {
            roles.removeAllViews();
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(text(SetupText.ED_USED_FOR, 12.5f, Ui.DIM), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Ui.infoButton(a, head, SetupText.ED_USED_FOR, SetupText.ED_USED_FOR,
                SetupText.ED_ROLE_LENGTH_NOTE);
            roles.addView(head, gapped(Look.S4));
            String[] ids = { Model.Cylinder.ROLE_GIRTH, Model.Cylinder.ROLE_LENGTH };
            String[] words = { SetupText.ED_ROLE_GIRTH, SetupText.ED_ROLE_LENGTH };
            int chosen = Model.Cylinder.ROLE_LENGTH.equals(role) ? 1 : 0;
            Ui.Segmented seg = Ui.segmented(a, null, words,
                new String[]{ SetupText.ED_USED_FOR + " girth", SetupText.ED_USED_FOR + " length" },
                chosen, new View.OnClickListener[]{ new SetRole(ids[0]), new SetRole(ids[1]) });
            roles.addView(seg.view, gapped(Look.S2));
        }

        /** The name suggestions' lead-in (polish SU-8). */
        private static final String NAME_IDEAS = "Name ideas:";

        private final class SetRole implements View.OnClickListener {
            private final String r;
            SetRole(String r) { this.r = r; }
            @Override public void onClick(View v) { role = r; fillRoles(); }
        }

        private void fillSizes() {
            sizes.removeAllViews();
            boolean inches = Model.Fmt.S_IN.equals(Model.Fmt.sizeUnit);
            double[] bores = inches ? new double[]{ 1.75, 2.00, 2.25, 2.50 }
                                    : new double[]{ 4.5, 5.0, 5.5, 6.0, 6.5 };
            double[] lens = inches ? new double[]{ 8, 9, 10, 12 } : new double[]{ 20, 23, 25, 30 };
            sizeBlock(SetupText.ED_BORE, bores, inches, bore, true);
            sizeBlock(SetupText.ED_LENGTH, lens, inches, len, false);
        }

        private void sizeBlock(String title, double[] chips, boolean inches, double now, boolean isBore) {
            sizes.addView(text(title, 12.5f, Ui.DIM), gapped(Look.S5));
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < chips.length; i++) {
                double cm = inches ? chips[i] * Model.Fmt.CM_PER_IN : chips[i];
                boolean on = Math.abs(now - cm) < 0.01;
                Button q = chip(Model.Fmt.lenNum(cm), on);
                Ui.tabular(q);   // polish SYS-9: a figure in the tabular sans
                q.setContentDescription(A11y.state(Model.Fmt.len(cm), on));
                q.setOnClickListener(new SetSize(isBore, cm));
                LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(0, dp(48), 1f);
                if (i > 0) ql.leftMargin = dp(Look.S2);
                row.addView(q, ql);
            }
            sizes.addView(row, gapped(Look.S2));
            LinearLayout exact = new LinearLayout(a);
            exact.setOrientation(LinearLayout.HORIZONTAL);
            exact.setGravity(Gravity.CENTER_VERTICAL);
            exact.addView(text(SetupText.ED_EXACT, 12.5f, Ui.DIM), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            double step = isBore ? 0.1 : 0.5;
            exact.addView(stepper(Model.Fmt.len(now),
                isBore ? SetupText.ED_SMALLER_SAID : SetupText.ED_SHORTER_SAID,
                isBore ? SetupText.ED_BIGGER_SAID : SetupText.ED_LONGER_SAID,
                new SetSize(isBore, now - step), new SetSize(isBore, now + step)));
            sizes.addView(exact, gapped(Look.S1));
        }

        private String typed() { return name == null ? "" : name.getText().toString().trim(); }

        /** Add or Save is off while the name is empty. */
        private void nameChanged() {
            if (dialog == null) return;
            Button ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (ok == null) return;
            boolean can = typed().length() > 0;
            // Ui.dress gives the positive button a disabled ink of its own (polish SYS-10).
            ok.setEnabled(can);
        }

        private final class NameWatch implements TextWatcher {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int n) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { nameChanged(); }
        }

        private final class QuickName implements View.OnClickListener {
            private final String full;
            QuickName(String f) { full = f; }
            @Override public void onClick(View v) {
                name.setText(full);
                name.setSelection(name.getText().length());
                // The "Length" name picks Length; "Girth" picks Girth back (owner, 2026-09-30).
                if (full.startsWith(SetupText.ED_ROLE_LENGTH)) role = Model.Cylinder.ROLE_LENGTH;
                else if (full.startsWith(SetupText.ED_ROLE_GIRTH)) role = Model.Cylinder.ROLE_GIRTH;
                fillRoles();
            }
        }

        /** A size chip or a step: rounded to the thousandth (an inch chip is 4.445 cm) and
         *  held inside Model.Cylinder#clamp's bounds. */
        private final class SetSize implements View.OnClickListener {
            private final boolean isBore;
            private final double cm;
            SetSize(boolean b, double cm) { isBore = b; this.cm = cm; }
            @Override public void onClick(View v) {
                Model.Cylinder t = new Model.Cylinder("", isBore ? cm : bore, isBore ? len : cm);
                t.clamp();
                bore = Math.rint(t.boreCm * 1000.0) / 1000.0;
                len = Math.rint(t.lengthCm * 1000.0) / 1000.0;
                fillSizes();
            }
        }

        private final class SaveTap implements DialogInterface.OnClickListener {
            @Override public void onClick(DialogInterface d, int which) {
                String label = typed();
                if (label.length() == 0) return;
                Model m = a.model;
                Model.Cylinder c = cylinderAt(index);
                if (c == null) {
                    boolean first = m.cylinders.isEmpty();
                    int keep = m.activeCylinder;
                    a.addCylinder(label, bore, len, role);
                    if (!first) {
                        m.activeCylinder = keep;
                        Store.save(a, m);
                    }
                } else {
                    c.label = label;
                    c.boreCm = bore;
                    c.lengthCm = len;
                    c.role = role;
                    c.clamp();
                    Store.save(a, m);
                }
                if (listener != null) listener.rackChanged(true);
            }
        }

        /** Removed at once in the first-run setup, as the mock's editor does: a first-run
         *  rack has no readings yet. Where it may have (`confirmRemove`), asked first, with
         *  Settings' own words - a reading keeps the dimensions it was taken with. */
        private final class RemoveTap implements DialogInterface.OnClickListener {
            @Override public void onClick(DialogInterface d, int which) {
                if (!confirmRemove) { remove(); return; }
                Ui.dress(a, Ui.dialog(a)
                    .setTitle(SetupText.ED_REMOVE_ASK)
                    .setMessage(SetupText.ED_REMOVE_WHY)
                    .setPositiveButton(SetupText.ED_REMOVE, new RemoveConfirmed())
                    .setNegativeButton(SetupText.ED_CANCEL, null)
                    .show());
            }
        }

        private final class RemoveConfirmed implements DialogInterface.OnClickListener {
            @Override public void onClick(DialogInterface d, int which) { remove(); }
        }

        private void remove() {
            Model m = a.model;
            if (index >= 0 && index < m.cylinders.size()) {
                m.cylinders.remove(index);
                if (m.activeCylinder == index) m.activeCylinder = 0;
                else if (m.activeCylinder > index) m.activeCylinder--;
                Store.save(a, m);
            }
            if (listener != null) listener.rackChanged(false);
        }
    }

    private Model.Cylinder cylinderAt(int index) {
        if (index < 0 || index >= a.model.cylinders.size()) return null;
        return a.model.cylinders.get(index);
    }
}
