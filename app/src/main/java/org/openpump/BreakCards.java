package org.openpump;

import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * THE MONTH-12 BREAK ON THE TRAINER (t10-K): the card while it is coming, while it runs and in
 * the gentle week after it, and the two taps - take it, come back early. Every rule is
 * MonthBreak's (core); this only draws it and asks.
 *
 * A REST THAT SAYS NOTHING IS INDISTINGUISHABLE FROM A BUG. While the break runs nothing is
 * offered and nothing reminds, so this card is the one thing that says why, and when it ends.
 */
final class BreakCards {
    private final SessionActivity a;
    private final TrainerScreen t;

    BreakCards(SessionActivity a, TrainerScreen t) { this.a = a; this.t = t; }

    private static final long DAY_MS = 86400000L;

    /** The break's card, at the top of the Trainer - or nothing when no break is near. */
    void cards() {
        Model m = a.model;
        long now = System.currentTimeMillis();
        if (!MonthBreak.taken(m)) return;
        String backOn = a.dayLabel(MonthBreak.returnMs(m));
        if (MonthBreak.pending(m, now)) {
            LinearLayout g = Ui.cardGroup(a, a.body, "Your break starts tomorrow", null);
            Ui.note(a, g, MonthBreak.pendingText(backOn));
            // A centred secondary, as every card's other answer is (polish SYS-4).
            Button cancel = Ui.secondary(a, g, "Keep training instead");
            cancel.setOnClickListener(new BackTap());
            return;
        }
        if (MonthBreak.on(m, now)) {
            long days = Math.max(0L, (MonthBreak.returnMs(m) - now + DAY_MS - 1L) / DAY_MS);
            LinearLayout g = Ui.cardGroup(a, a.body, "On a break", null);
            // The dates on the face; why it is quiet, behind the \u24d8 (polish, info moves).
            String on = MonthBreak.onText(backOn, days);
            int k = on.indexOf(" to go. ");
            if (k > 0)
                Ui.noteInfo(a, g, on.substring(0, k + 7), "On a break", on.substring(k + 8));
            else Ui.note(a, g, on);
            Button back = Ui.secondary(a, g, "Come back now");
            back.setOnClickListener(new BackTap());
            return;
        }
        if (MonthBreak.gentleOn(m, now)) {
            LinearLayout g = Ui.cardGroup(a, a.body, "Gentle week", null);
            // This week's pressures on the face; what comes after it, behind the \u24d8.
            String gt = MonthBreak.gentleText(m, a.dayLabel(MonthBreak.gentleEndMs(m) - 1L), now);
            int k = gt.indexOf(" Length runs at ");
            if (k > 0) Ui.noteInfo(a, g, gt.substring(0, k), "Gentle week", gt.substring(k + 1));
            else Ui.note(a, g, gt);
        }
    }

    /** B1 - the month-12 card's break, from either track: asks first, saying what is true
     *  afterwards and on the way back. */
    final class TakeTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            long back = Deload.dayStartPlus(System.currentTimeMillis(),
                                            1 + Plan.LENGTH_BREAK_WEEKS * 7);
            Ui.dress(a, Ui.dialog(a)
                .setTitle(MonthBreak.TAKE_TITLE)
                .setMessage(MonthBreak.takeText(a.model, a.dayLabel(back)))
                .setPositiveButton("Take the break", new TakeConfirm())
                .setNegativeButton("Keep training", null)
                .show());
        }
    }

    /** The break taken: dated, both tracks, from tomorrow. The week and the reminders are
     *  told at once - saved as a schedule edit (review D, F2). */
    final class TakeConfirm implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            MonthBreak.take(a.model, System.currentTimeMillis());
            a.schedSaved();
            Ui.snack(a, a.rootFrame, "Break from tomorrow, back on "
                + a.dayLabel(MonthBreak.returnMs(a.model)) + " — both tracks rest");
            t.showTrainer();
        }
    }

    /** "Come back now" (or, before it starts, not taking it after all) - asked first. */
    private final class BackTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            boolean pending = MonthBreak.pending(a.model, System.currentTimeMillis());
            Ui.dress(a, Ui.dialog(a)
                .setTitle(pending ? "Keep training instead?" : MonthBreak.BACK_TITLE)
                .setMessage(pending
                    ? "The break is not taken, and your plan carries on as it is."
                    : MonthBreak.BACK_TEXT)
                .setPositiveButton(pending ? "Keep training" : "Come back",
                                   new BackConfirm())
                .setNegativeButton(pending ? "Keep the break" : "Keep resting", null)
                .show());
        }
    }

    private final class BackConfirm implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            boolean pending = MonthBreak.pending(a.model, System.currentTimeMillis());
            MonthBreak.comeBackNow(a.model, System.currentTimeMillis());
            a.schedSaved();
            Ui.snack(a, a.rootFrame, pending ? "No break — your plan carries on"
                : "Welcome back — the gentle week starts today");
            t.showTrainer();
        }
    }
}
