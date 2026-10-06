package org.openpump;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * SETTINGS › RUN COLOURS (0.10 run-screen redesign): whether the run is coloured by the kind
 * of step playing, where the colour shows (the status line only, by default), and one colour
 * per step kind, with four ready-made sets.
 *
 * STOP IS NOT HERE. It is not a step kind (RunLook), the run screen draws it in the app's own
 * red whatever this says, and a colour too close to that red - or to another step's colour -
 * is refused with the reason, on this sheet (RunLook#rejectWhy). Nothing here reaches the
 * pump; the incognito notification's discreet words do not read any of it.
 */
final class RunColoursSheet {
    private final SessionActivity a;
    private LinearLayout host;
    private TextView said;

    /** The swatches offered: the four sets' colours and a few more, none of them red. */
    private static final int[] SWATCHES = {
        0xFF7CC4F2, 0xFF56B4E9, 0xFF00E5FF, 0xFF8FB8C9, 0xFF6D8BFF, 0xFF9AA6E8, 0xFF0072B2,
        0xFFD4F53C, 0xFFB9D98C, 0xFFFFFF00, 0xFFF0E442, 0xFFE69F00, 0xFFFFB000, 0xFFE0B98C,
        0xFF3FD8B8, 0xFF009E73, 0xFF00FF7F, 0xFF6FC7A4, 0xFFC77DFF, 0xFF9D7BFF, 0xFFF2A0D4,
        0xFFCC79A7, 0xFFFF7AF5, 0xFFE0A6C8, 0xFFE8ECEF, 0xFFFFFFFF
    };

    RunColoursSheet(SessionActivity a) { this.a = a; }

    void open() {
        LinearLayout outer = Ui.col(a);
        int pad = Ui.dp(a, Look.S5);
        outer.setPadding(pad, Ui.dp(a, 4), pad, pad);
        said = new TextView(a);
        said.setTextSize(Look.SP_CAPTION);
        said.setTextColor(Ui.TEXT);
        said.setVisibility(View.GONE);
        host = Ui.col(a);
        outer.addView(host);
        outer.addView(said);
        fill();
        ScrollView sv = new ScrollView(a);
        sv.addView(outer);
        Ui.dress(a, Ui.dialog(a)
            .setTitle("Run colours")
            .setView(sv)
            .setPositiveButton("Done", new Closed())
            .setOnCancelListener(new Cancelled())
            .show());
    }

    private final class Closed implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) { a.showSettings(); }
    }

    private final class Cancelled implements DialogInterface.OnCancelListener {
        @Override public void onCancel(DialogInterface d) { a.showSettings(); }
    }

    private void say(String s) {
        if (s == null || s.isEmpty()) { said.setVisibility(View.GONE); return; }
        a.journalSnack(s);
        said.setText(s);
        said.setVisibility(View.VISIBLE);
    }

    private void save() {
        Store.save(a, a.model);
    }

    private void fill() {
        host.removeAllViews();
        Model m = a.model;
        Ui.kvRow(a, host, "Colour the run by step", m.runColourOn, new ToggleOn());

        TextView where = new TextView(a);
        where.setText("Where the colour shows");
        where.setTextColor(Ui.DIM);
        where.setTextSize(Look.SP_CAPTION);
        where.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 4));
        host.addView(where);
        LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < RunLook.WHERE_NAMES.length; i++)
            chip(chips, RunLook.WHERE_NAMES[i], m.runColourWhere == i, m.runColourOn,
                 new PickWhere(i), (i == 0 ? "The status line only" : RunLook.WHERE_NAMES[i])
                 + (m.runColourWhere == i ? ", selected" : ""));
        host.addView(chips);

        for (int k = 0; k < RunLook.KINDS; k++) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(a, 48));
            View dot = new View(a);
            dot.setBackground(Ui.roundRect(a, m.runColours[k], 12));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22));
            dl.rightMargin = Ui.dp(a, 10);
            row.addView(dot, dl);
            TextView n = new TextView(a);
            n.setText(RunLook.NAMES[k] + (k == RunLook.REST ? " (vented)" : ""));
            n.setTextColor(Ui.TEXT);
            n.setTextSize(Look.SP_BODY);
            row.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // polish DG-7: the swatch says the colour; its hex code is not shown.
            Button ch = Ui.compactStep(a, "Change");
            ch.setTextSize(Look.SP_CHIP);
            ch.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
            ch.setContentDescription("Change the " + RunLook.NAMES[k] + " colour");
            ch.setOnClickListener(new PickKind(k));
            row.addView(ch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Ui.dp(a, 48)));
            host.addView(row);
        }

        TextView sets = new TextView(a);
        // polish DG-7: "Sets" already means something else in this app.
        sets.setText("Palettes");
        sets.setTextColor(Ui.DIM);
        sets.setTextSize(Look.SP_CAPTION);
        sets.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 4));
        host.addView(sets);
        LinearLayout pre = null;
        for (int i = 0; i < RunLook.PRESET_NAMES.length; i++) {
            if (i % 2 == 0) {
                pre = new LinearLayout(a);
                pre.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                pl.bottomMargin = Ui.dp(a, 6);
                host.addView(pre, pl);
            }
            boolean on = java.util.Arrays.equals(m.runColours, RunLook.preset(i));
            chip(pre, RunLook.PRESET_NAMES[i], on, true, new PickPreset(i),
                 RunLook.PRESET_NAMES[i] + " colours" + (on ? ", in use" : ""));
        }

        TextView stop = new TextView(a);
        stop.setText("STOP stays red and can't be changed. A colour too close to STOP red, or "
            + "to another step's colour, is refused.");
        stop.setTextColor(Ui.DIM);
        stop.setTextSize(Look.SP_CAPTION);
        stop.setPadding(0, Ui.dp(a, 8), 0, 0);
        host.addView(stop);
    }

    private void chip(LinearLayout row, String label, boolean sel, boolean enabled,
                      View.OnClickListener l, String cd) {
        Button b = Ui.compactStep(a, label);
        b.setTextSize(Look.SP_CAPTION);
        b.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
        android.graphics.drawable.GradientDrawable bg = Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL);
        bg.setStroke(Ui.dp(a, sel ? 2 : 1), sel ? Ui.ACCENT : Ui.LINE);
        b.setBackground(bg);
        b.setTextColor(sel ? Ui.ACCENT : Ui.TEXT);
        b.setSelected(sel);
        b.setContentDescription(cd);
        // One disabled look (polish SYS-10), never an alpha.
        if (!enabled) Ui.stateFill(a, b, false, false); else b.setEnabled(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f);
        lp.rightMargin = Ui.dp(a, 6);
        row.addView(b, lp);
    }

    private final class ToggleOn implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.runColourOn = !a.model.runColourOn;
            save();
            say(a.model.runColourOn ? "The run is coloured by step." : "The run is not coloured "
                + "— except Hold, which always shows.");
            fill();
        }
    }

    private final class PickWhere implements View.OnClickListener {
        private final int w;
        PickWhere(int w) { this.w = w; }
        @Override public void onClick(View v) {
            a.model.runColourWhere = RunLook.clampWhere(w);
            save();
            say(null);
            fill();
        }
    }

    private final class PickPreset implements View.OnClickListener {
        private final int i;
        PickPreset(int i) { this.i = i; }
        @Override public void onClick(View v) {
            int[] c = RunLook.preset(i);
            String why = RunLook.problem(c);
            if (why != null) { say(why); return; }
            a.model.runColours = c;
            save();
            say(RunLook.PRESET_NAMES[i] + " colours.");
            fill();
        }
    }

    /** Sets `kind` to `colour` if RunLook allows it; says why not otherwise. */
    private void setColour(int kind, int colour) {
        String why = RunLook.rejectWhy(a.model.runColours, kind, colour);
        if (why != null) { say(RunLook.NAMES[kind] + ": " + why + ". Nothing was changed."); return; }
        int[] c = a.model.runColours.clone();
        c[kind] = colour;
        a.model.runColours = c;
        save();
        say(RunLook.NAMES[kind] + " colour changed.");
        fill();
    }

    private final class PickKind implements View.OnClickListener {
        private final int k;
        PickKind(int k) { this.k = k; }
        @Override public void onClick(View v) { openPicker(k); }
    }

    private void openPicker(int kind) {
        LinearLayout box = Ui.col(a);
        int pad = Ui.dp(a, Look.S5);
        box.setPadding(pad, Ui.dp(a, 4), pad, pad);
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout row = null;
        for (int i = 0; i < SWATCHES.length; i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row);
            }
            View sw = new View(a);
            android.graphics.drawable.GradientDrawable bg = Ui.roundRect(a, SWATCHES[i], Look.R_CTRL);
            boolean refused = RunLook.rejectWhy(a.model.runColours, kind, SWATCHES[i]) != null;
            bg.setStroke(Ui.dp(a, 2), SWATCHES[i] == a.model.runColours[kind] ? Ui.TEXT : Ui.SURF);
            sw.setBackground(bg);
            sw.setAlpha(refused ? 0.35f : 1f);
            sw.setContentDescription(RunLook.hex(SWATCHES[i])
                + (refused ? ", too close to another colour" : ""));
            sw.setOnClickListener(new Swatch(kind, SWATCHES[i], holder));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f);
            lp.setMargins(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3));
            row.addView(sw, lp);
        }
        final EditText hex = new EditText(a);
        hex.setHint("#RRGGBB");
        hex.setTextColor(Ui.TEXT);
        hex.setSingleLine(true);
        box.addView(hex);
        holder[0] = Ui.dialog(a)
            .setTitle(RunLook.NAMES[kind] + " colour")
            .setView(box)
            .setPositiveButton("Use this colour", new Typed(kind, hex))
            .setNegativeButton("Cancel", null)
            .show();
        Ui.dress(a, holder[0]);
    }

    private final class Swatch implements View.OnClickListener {
        private final int kind, colour;
        private final AlertDialog[] holder;
        Swatch(int k, int c, AlertDialog[] h) { kind = k; colour = c; holder = h; }
        @Override public void onClick(View v) {
            if (holder[0] != null) holder[0].dismiss();
            setColour(kind, colour);
        }
    }

    private final class Typed implements DialogInterface.OnClickListener {
        private final int kind;
        private final EditText field;
        Typed(int k, EditText f) { kind = k; field = f; }
        @Override public void onClick(DialogInterface d, int w) {
            Integer c = RunLook.parseHex(field.getText().toString());
            if (c == null) { say("That isn't a colour — type it as #RRGGBB."); return; }
            setColour(kind, c.intValue());
        }
    }
}
