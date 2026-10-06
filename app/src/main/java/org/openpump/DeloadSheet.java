package org.openpump;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.Calendar;

/**
 * "I ALREADY TOOK A DELOAD" — the days, and what they mean.
 *
 * A deload the app was not told about is charged as a missed week, steps the plan back as a
 * layoff, and leaves the next deload still due: three penalties for doing exactly what the
 * plan asks. This is the one screen that says otherwise, and it asks for the only thing the
 * app cannot work out for itself — which days they were.
 *
 * Built the same way the readiness report is: one host column rebuilt after every tap, so the
 * summary, the refusal and the Save button can never describe a range that is no longer
 * showing.
 */
public final class DeloadSheet {

    private DeloadSheet() { }

    /** The range being reported, and whether the return is gentle. Days are local midnights. */
    private static final class Draft {
        long fromMs, toMs;
        boolean gentle = true;
        android.app.AlertDialog dialog;
    }

    public static void open(SessionActivity a, long fromMs, long toMs) {
        Draft d = new Draft();
        d.fromMs = Deload.dayStartMs(fromMs);
        d.toMs = Deload.dayStartMs(toMs);
        LinearLayout host = Ui.col(a);
        int pad = Ui.dp(a, Look.S5);
        host.setPadding(pad, pad, pad, pad);
        rebuild(a, host, d);
        d.dialog = Ui.dialog(a)
            .setTitle("Report a deload")
            .setMessage("The days you rested. They count as planned rest, not as missed "
                + "training.")
            .setView(host)
            .setCancelable(true)
            .show();
        Ui.sheet(a, d.dialog);
    }

    private static void rebuild(SessionActivity a, LinearLayout host, Draft d) {
        host.removeAllViews();
        long now = System.currentTimeMillis();
        long todayDay = Summary.dayNumber(now);
        long fromDay = Summary.dayNumber(d.fromMs), toDay = Summary.dayNumber(d.toMs);
        long recordedStart = Deload.startMs(a.model) > 0L
            ? Summary.dayNumber(Deload.startMs(a.model)) : 0L;

        Ui.kvRow(a, host, "From", Say.dayLabel(d.fromMs), Ui.TEXT, new PickDay(a, host, d, true));
        Ui.kvRow(a, host, "To", Say.dayLabel(d.toMs), Ui.TEXT, new PickDay(a, host, d, false));

        long days = toDay - fromDay + 1;
        // PLAN SESSIONS ON ANY TRACK, which is what the line says - it counted girth training
        // DAYS, so a length session in the reported days was missing from the one line
        // promising it would stay in the history.
        int inRange = TrainerTab.planSessionsBetween(a.model, d.fromMs,
            Deload.dayStartPlus(d.toMs, 1));
        Ui.note(a, host, days + (days == 1 ? " day" : " days") + " · "
            + (inRange == 0 ? "no plan sessions in them"
                            : inRange + (inRange == 1 ? " plan session" : " plan sessions")
                              + " in them, which stay in your history"));

        int code = Deload.validate(fromDay, toDay, todayDay, recordedStart);
        if (code != Deload.REPORT_OK) Ui.note(a, host, Deload.reportProblem(code));

        Ui.kvRow(a, host, "Come back gently", d.gentle, new ToggleGentle(a, host, d));
        // "Full" only where nothing else reduces: with the switch off, a larger or accepted
        // oversize cylinder's own cut is still what the first session back runs at.
        double cylinderHg = a.model.cylinderCutHg();
        Ui.note(a, host, d.gentle
            ? "First training day back " + Say.hgUnder(Plan.returnTaperHg(0)) + " under, the "
              + "next " + Say.hgUnder(Plan.returnTaperHg(1)) + " under, with a slower pull."
            : cylinderHg > 0.0
              ? "You come back at your usual pressure, " + Say.hgUnder(cylinderHg)
                + " under for your cylinder."
              : "You come back at your full working pressure.");

        // polish DG-6: Cancel and Save side by side, Save the lime one at the right - and
        // looking unavailable (Ui.setEnabled) when the days cannot be saved, not just ignoring
        // the tap.
        Button[] foot = Ui.row(a, host, new String[]{ "Cancel", "Save" },
            new View.OnClickListener[]{ new CancelTap(d), new SaveTap(a, d) });
        Ui.setEnabled(a, foot[1], code == Deload.REPORT_OK, Ui.ACCENT);
    }

    /** The platform day picker, on the day that row is showing. Never offers a future day. */
    private static final class PickDay implements View.OnClickListener {
        private final SessionActivity a; private final LinearLayout host;
        private final Draft d; private final boolean from;
        PickDay(SessionActivity a, LinearLayout host, Draft d, boolean from) {
            this.a = a; this.host = host; this.d = d; this.from = from;
        }
        @Override public void onClick(View v) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(from ? d.fromMs : d.toMs);
            // polish SYS-14: the app's dark picker, dressed like every other dialog.
            android.app.DatePickerDialog dp = Ui.datePicker(a, new DaySet(a, host, d, from),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            dp.getDatePicker().setMaxDate(System.currentTimeMillis());
            Ui.showPicker(a, dp);
        }
    }

    private static final class DaySet implements android.app.DatePickerDialog.OnDateSetListener {
        private final SessionActivity a; private final LinearLayout host;
        private final Draft d; private final boolean from;
        DaySet(SessionActivity a, LinearLayout host, Draft d, boolean from) {
            this.a = a; this.host = host; this.d = d; this.from = from;
        }
        @Override public void onDateSet(android.widget.DatePicker view, int y, int m, int day) {
            Calendar c = Calendar.getInstance();
            c.set(y, m, day, 0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            long picked = c.getTimeInMillis();
            if (from) {
                d.fromMs = picked;
                if (d.toMs < d.fromMs) d.toMs = d.fromMs;      // a range runs forwards
            } else {
                d.toMs = picked;
                if (d.fromMs > d.toMs) d.fromMs = d.toMs;
            }
            rebuild(a, host, d);
        }
    }

    private static final class ToggleGentle implements View.OnClickListener {
        private final SessionActivity a; private final LinearLayout host; private final Draft d;
        ToggleGentle(SessionActivity a, LinearLayout host, Draft d) {
            this.a = a; this.host = host; this.d = d;
        }
        @Override public void onClick(View v) { d.gentle = !d.gentle; rebuild(a, host, d); }
    }

    private static final class SaveTap implements View.OnClickListener {
        private final SessionActivity a; private final Draft d;
        SaveTap(SessionActivity a, Draft d) { this.a = a; this.d = d; }
        @Override public void onClick(View v) {
            long end = Deload.dayStartPlus(d.toMs, 1);          // the end is exclusive
            String span = Say.dayLabel(d.fromMs) + " – " + Say.dayLabel(d.toMs);
            // A REPORT IS PLAN-WIDE - it names days, not a track - so it files under the
            // girth track, the one the plan's own cadence is anchored to.
            a.recordDeload(a.model.trainerGirthStyle, d.fromMs, end, d.gentle, "deload reported",
                span + " reported after the fact — counted as planned rest"
                + (d.gentle ? "; coming back gently" : ""));
            if (d.dialog != null) { d.dialog.dismiss(); d.dialog = null; }
            Ui.snack(a, a.rootFrame, "Deload recorded · " + span);
            a.showTrainer();
        }
    }

    private static final class CancelTap implements View.OnClickListener {
        private final Draft d;
        CancelTap(Draft d) { this.d = d; }
        @Override public void onClick(View v) {
            if (d.dialog != null) { d.dialog.dismiss(); d.dialog = null; }
        }
    }
}
