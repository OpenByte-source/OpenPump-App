package org.openpump;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * THE month grid — a title with ‹ › arrows, a weekday header, and rows of seven 48dp day
 * cells, dotted on the dates that carry a photo of the view being looked at.
 *
 * It was Compare's, built inline in CompareScreen. The Align screen's ghost picker needs
 * exactly the same grid asking a slightly different question ("which date do I ghost
 * against?" rather than "which two do I compare?"), and the one thing this codebase cannot
 * afford is a second calendar: two grids drifting apart on which dates are tappable, which
 * are dotted, how far the arrows may travel, or what a cell announces is the same class of
 * defect as two screens computing the same streak two ways. So the grid moved here and BOTH
 * screens draw it — the difference between them is entirely in the two small interfaces
 * below.
 *
 * {@link #doseCard} (Stage D task 3, S7 A) is a THIRD consumer asking a third question
 * ("how heavy was each day, and which are missed?") over a different kind of record
 * (sessions, not photographed readings) — it shares this file's weekday header and 7-column
 * row layout rather than re-deriving them, but paints its own cells and skips the month
 * arrows the photo pickers need and it does not; see its own doc for exactly what it shares
 * and what it does not.
 *
 * Everything DECIDABLE is still {@link PhotoCalendar}, which is pure and asserted: which
 * cells exist, which carry a dot, how far the arrows travel, and every spoken name. This
 * class is Buttons and colours — device-only, verified by reading.
 */
public final class CalendarGrid {
    private CalendarGrid() { }

    /** What a caller's own selection state says about a date: "A"/"B" for Compare's pair,
     *  "●" for the Align picker's single ghost, or null for unselected. Returning a LETTER
     *  rather than a boolean is what keeps a selection off colour alone. */
    public interface Roles {
        String roleOf(String readingId);
    }

    /** The two things a tap can mean. The grid decides neither: it reports, and the caller
     *  applies whatever state machine it owns and re-renders. */
    public interface Taps {
        void onDay(String readingId);
        void onMonth(int dir);
    }

    /**
     * The whole card. `ps` is the caller's already-filtered candidate list — always
     * Compare.withPhotos' output for ONE view, so the same-view-only rule is upstream of
     * this and a reading with only another view is not in the grid at all.
     *
     * `footer` is the caller's own line under the grid ("" for none): Compare's "tap a
     * dotted date to pick the first photo", the Align picker's note about the default.
     */
    public static LinearLayout card(Activity a, List<Model.Reading> ps,
                                     PhotoCalendar.Month month, Roles roles, Taps taps,
                                     String footer) {
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));   // PR-12: the card radius, lifted
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));

        card.addView(monthHeader(a, ps, month, taps), wrapLp());
        card.addView(weekdayHeader(a), wrapLp());

        List<PhotoCalendar.Cell> cells = PhotoCalendar.cells(ps, month);
        LinearLayout week = newWeekRow(a);
        int inRow = 0;
        for (int i = 0; i < cells.size(); i++) {
            week.addView(dayCell(a, cells.get(i), month, roles, taps), cellLp(a));
            inRow++;
            if (inRow == PhotoCalendar.COLS) {
                card.addView(week, wrapLp());
                week = newWeekRow(a);
                inRow = 0;
            }
        }
        // The month's last week is short: pad it with blanks rather than letting the
        // remaining cells stretch to fill the row, which would put the last few dates under
        // the wrong weekday columns.
        if (inRow > 0) {
            while (inRow < PhotoCalendar.COLS) {
                week.addView(dayCell(a, null, month, roles, taps), cellLp(a));
                inRow++;
            }
            card.addView(week, wrapLp());
        }

        // A month the arrows reached that simply has nothing in it — said plainly, and
        // pointed back at the arrows.
        if (PhotoCalendar.photoCount(cells) == 0)
            card.addView(smallLine(a, PhotoCalendar.emptyMonthLine(month)), wrapLp());
        if (footer != null && footer.length() > 0)
            card.addView(smallLine(a, footer), wrapLp());
        return card;
    }

    private static TextView smallLine(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(Ui.DIM);
        t.setTextSize(Look.SP_CAPTION);
        t.setPadding(Ui.dp(a, 2), Ui.dp(a, 8), 0, 0);
        return t;
    }

    private static LinearLayout newWeekRow(Activity a) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private static LinearLayout.LayoutParams cellLp(Activity a) {
        return new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f);
    }

    private static LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                             ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** The month title between two arrows. An arrow at a bound is disabled and dimmed rather
     *  than removed, so the row does not reflow as the user walks through months. */
    private static LinearLayout monthHeader(Activity a, List<Model.Reading> ps,
                                             PhotoCalendar.Month month, Taps taps) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);

        long now = System.currentTimeMillis();
        boolean canPrev = PhotoCalendar.canGoPrev(ps, month, now);
        boolean canNext = PhotoCalendar.canGoNext(month, now);

        return monthNav(a, PhotoCalendar.monthTitle(month),
            canPrev, canNext,
            PhotoCalendar.prevMonthName(month), PhotoCalendar.nextMonthName(month),
            new MonthTap(taps, -1), new MonthTap(taps, 1));
    }

    /**
     * ‹ MONTH ›, THE ONE MONTH-STEPPER BOTH CALENDARS USE.
     *
     * Two arrows at their own width with the title taking what is left, and BOTH ARROWS
     * ALWAYS DRAWN - disabled and dimmed at the edge rather than removed. That is the same
     * rule the stage and set reorder rows follow ("disabled at the edge"), and it is the
     * difference between a row that stays put and one that reflows under the finger every
     * time you reach the end.
     *
     * IT IS SHARED BECAUSE THE OTHER ONE WAS BUILT BY HAND AND WAS BROKEN. The dose heatmap
     * grew its own nav out of Ui.flat, whose layout params are MATCH_PARENT by contract - so
     * in a HORIZONTAL row the first arrow claimed the entire width and pushed the month name
     * and the forward arrow off the screen. Measured at 1002px of a 1002px row. The heatmap
     * could be walked backwards for ever and never forwards, and never said which month it
     * was showing. One stepper, two callers, so they cannot disagree again.
     *
     * FAINT_OFF for a disabled arrow, not FAINT: this is the unavailable ink and the arrow is
     * genuinely disabled, which is exactly what makes it exempt from the contrast floor.
     */
    static LinearLayout monthNav(Activity a, String title,
                                 boolean canPrev, boolean canNext,
                                 String prevDesc, String nextDesc,
                                 View.OnClickListener onPrev, View.OnClickListener onNext) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBaselineAligned(false);

        Button prev = Ui.mini(a, "‹");
        prev.setContentDescription(prevDesc);
        prev.setEnabled(canPrev);
        prev.setTextColor(canPrev ? Ui.TEXT : Ui.FAINT_OFF);
        prev.setOnClickListener(onPrev);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(Look.SP_HEADING);
        t.setGravity(Gravity.CENTER);

        Button next = Ui.mini(a, "›");
        next.setContentDescription(nextDesc);
        next.setEnabled(canNext);
        next.setTextColor(canNext ? Ui.TEXT : Ui.FAINT_OFF);
        next.setOnClickListener(onNext);

        r.addView(prev);
        r.addView(t, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(next);
        return r;
    }

    /** The weekday initials, in the order PhotoCalendar.leadingBlanks pads for — both come
     *  from the same Calendar.getFirstDayOfWeek(), so the header and the dates cannot end up
     *  a column apart. Hidden from accessibility: each cell already names its own date in
     *  full, and seven single letters walked before every grid is noise. */
    private static LinearLayout weekdayHeader(Activity a) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        String[] initials = PhotoCalendar.weekdayInitials();
        for (int i = 0; i < initials.length; i++) {
            TextView t = new TextView(a);
            t.setText(initials[i]);
            t.setTextColor(Ui.FAINT);
            t.setTextSize(Look.SP_MICRO);
            t.setGravity(Gravity.CENTER);
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            r.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        r.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 2));
        return r;
    }

    /**
     * One day. A blank pad is an empty, untouchable View. A day WITHOUT a photo shows its
     * number faintly and is disabled — present, so the month reads as a month, and plainly
     * not a candidate. A day WITH a photo shows its number over a dot, or over the letter of
     * the slot it is selected as; the letter is what keeps the selection off colour alone.
     * 48dp tall, and every one carries a name from PhotoCalendar.cellName.
     */
    private static Button dayCell(Activity a, PhotoCalendar.Cell cell,
                                   PhotoCalendar.Month month, Roles roles, Taps taps) {
        Button b = new Button(a);
        b.setAllCaps(false);
        b.setTextSize(Look.SP_CAPTION);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(Ui.dp(a, 48));
        b.setPadding(0, 0, 0, 0);
        b.setGravity(Gravity.CENTER);

        if (cell == null || cell.isBlank()) {
            b.setText("");
            b.setEnabled(false);
            b.setBackground(null);
            b.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return b;
        }

        String role = roles == null ? null : roles.roleOf(cell.readingId);
        boolean has = cell.hasPhoto();
        b.setText(cell.day + (has ? "\n" + (role != null ? role : "●") : ""));
        b.setContentDescription(
            PhotoCalendar.cellName(cell.day, PhotoCalendar.monthShort(month), has, role));
        /* A dotted date is a real, live datum. A SELECTED one is an affirmative choice the
         * user has made, and it carries its letter as well as its fill, so neither state
         * rests on colour alone. A dateless day is faint and dead. No amber: nothing on
         * either screen that draws this grid commands the pump.
         *
         * AND NO GREEN. Look.SAFE is a telemetry verdict - confirmed vented, or a session
         * completed - and a picked calendar day is neither. Selection is drawn the way every
         * other picker in this app draws it: lime ink over the low-alpha lime wash, which is
         * exactly what ACCENT_DIM's own doc licenses ("legal anywhere the user's own choice
         * is being shown back to them"). R_PILL rather than a typed 11, per its own doc,
         * which names these day cells. */
        // A dateless day is disabled (setEnabled(has) below) - the unavailable ink.
        b.setTextColor(has ? Ui.ACCENT : Ui.FAINT_OFF);
        b.setBackground(role != null
                ? Ui.roundRect(a, Look.ACCENT_DIM, Look.R_PILL) : null);
        b.setEnabled(has);
        b.setSelected(role != null);
        if (has) b.setOnClickListener(new DayTap(taps, cell.readingId));
        return b;
    }

    private static final class DayTap implements View.OnClickListener {
        private final Taps taps; private final String id;
        DayTap(Taps t, String i) { taps = t; id = i; }
        @Override public void onClick(View v) { if (taps != null) taps.onDay(id); }
    }

    private static final class MonthTap implements View.OnClickListener {
        private final Taps taps; private final int dir;
        MonthTap(Taps t, int d) { taps = t; dir = d; }
        @Override public void onClick(View v) { if (taps != null) taps.onMonth(dir); }
    }

    /* ==================================== THE DOSE HEATMAP (Stage D task 3, S7 A) === */

    /** A day cell's tap, for the dose heatmap: reports the DAY NUMBER, never resolving it
     *  to a session itself — the same "the grid decides neither, it reports" discipline
     *  {@link Taps}' own doc states, so the caller's own state (which log, which session
     *  wins on a multi-session day) stays entirely upstream of this file. */
    public interface DoseTaps {
        void onDay(long dayNumber);
    }

    /**
     * THE DOSE HEATMAP (S7 A, round6-options.html's decisions list — "dose heatmap month
     * grid; tap day → session; missed scheduled days outlined"). The SAME 7-column month
     * grid {@link #card} draws for the photo pickers — same weekday header, same 48dp
     * cells, same last-week blank padding, built from the same shared private helpers
     * below — reused rather than rebuilt: a second grid primitive quietly drifting from
     * this one on cell sizing or weekday alignment is exactly the defect class this file's
     * own class doc exists to rule out.
     *
     * What DIFFERS is what a cell paints, because it is answering a different question. A
     * photo cell shows a dot and an A/B letter. A dose cell shows a FILL — how much was
     * delivered that day, {@link Insight#doseTier} — and, only for a scheduled day nothing
     * was filed on, a red OUTLINE ({@link Insight#DAY_MISSED}, from {@link
     * Insight#monthRoles}). Two independent facts about the same day, drawn as fill and
     * stroke rather than forced into one colour — see {@link Insight}'s own class doc for
     * why they are allowed to disagree.
     *
     * THE MONTH IS NAMED BY THE STEPPER ABOVE THIS CARD, not inside it. This card drew its
     * own "September 2026" heading back when it only ever showed the current month and had
     * no stepper at all. Audit E3 gave it one — {@link #monthNav}, the same stepper the
     * photo grid uses — and that names the month between its two arrows, so the card's
     * heading then printed the same month a second time directly underneath. The caller
     * owns the heading; this card draws the grid.
     *
     * `dayNumbers`/`tiers`/`roles` are three parallel arrays, one entry per day 1..N of
     * `month`, oldest first — {@link Insight#doseTiers} and {@link Insight#monthRoles}'
     * own outputs, handed straight through. This method paints; it decides nothing about
     * what a day MEANS.
     */
    public static LinearLayout doseCard(Activity a, PhotoCalendar.Month month,
                                         long[] dayNumbers, int[] tiers, int[] roles,
                                         DoseTaps taps, String footer) {
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.roundRect(a, Ui.SURF, Look.R_CARD));   // PR-12: the card radius, lifted
        Ui.lift(a, card, Ui.ELEV_CARD);
        card.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));

        card.addView(weekdayHeader(a), wrapLp());

        int blanks = PhotoCalendar.leadingBlanks(month);
        int days = dayNumbers == null ? 0 : dayNumbers.length;
        LinearLayout week = newWeekRow(a);
        int inRow = 0;
        for (int i = 0; i < blanks; i++) {
            week.addView(doseDayCell(a, 0, 0, Insight.DOSE_NONE, null, month, taps), cellLp(a));
            inRow++;
            if (inRow == PhotoCalendar.COLS) {
                card.addView(week, wrapLp());
                week = newWeekRow(a);
                inRow = 0;
            }
        }
        for (int d = 0; d < days; d++) {
            week.addView(doseDayCell(a, d + 1, dayNumbers[d], tiers[d],
                Integer.valueOf(roles[d]), month, taps), cellLp(a));
            inRow++;
            if (inRow == PhotoCalendar.COLS) {
                card.addView(week, wrapLp());
                week = newWeekRow(a);
                inRow = 0;
            }
        }
        // Same last-week padding rule as the photo grid: blanks rather than letting the
        // remaining cells stretch, which would put the last few dates under the wrong
        // weekday columns.
        if (inRow > 0) {
            while (inRow < PhotoCalendar.COLS) {
                week.addView(doseDayCell(a, 0, 0, Insight.DOSE_NONE, null, month, taps), cellLp(a));
                inRow++;
            }
            card.addView(week, wrapLp());
        }
        if (footer != null && footer.length() > 0)
            card.addView(smallLine(a, footer), wrapLp());
        return card;
    }

    /**
     * One dose cell. `dayOfMonth` 0 (equivalently `role == null`) is a leading/trailing
     * blank pad — untouchable, matching {@link #dayCell}'s own blank treatment exactly.
     *
     * THE FILL is the day's tier: quiet neutral for none, two low-alpha lime steps for
     * low/medium ({@link Look#DOSE_TIER_LOW}/{@link Look#DOSE_TIER_MED}), full-strength
     * {@link Ui#ACCENT} for the heaviest days — with the label switching to {@link
     * Look#ON_ACCENT} the moment the fill does, the SAME near-black-on-lime pairing the
     * primary button already uses: a plain light label on a solid lime fill is the START
     * button's own contrast bug (style-gaps.html finding #01) reborn on a calendar cell,
     * and {@link Look}'s own SelfTest pins that this pairing, unlike a guessed alpha, is
     * actually AA-safe.
     *
     * THE OUTLINE is schedule-missed ONLY — {@link Insight#DAY_MISSED} in {@link Ui#CRIT},
     * the same red {@link Insight#monthRoles} already earns for a scheduled day with
     * nothing on it. OFF/REST/FUTURE draw no stroke at all: neither is a failure, and this
     * file already has a quiet convention for "nothing to report" (dim ink, neutral fill)
     * without reaching for a border.
     */
    private static Button doseDayCell(Activity a, int dayOfMonth, long dayNumber, int tier,
                                       Integer role, PhotoCalendar.Month month, DoseTaps taps) {
        Button b = new Button(a);
        b.setAllCaps(false);
        b.setTextSize(Look.SP_CAPTION);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(Ui.dp(a, 48));
        b.setPadding(0, 0, 0, 0);
        b.setGravity(Gravity.CENTER);

        if (dayOfMonth == 0 || role == null) {
            b.setText("");
            b.setEnabled(false);
            b.setBackground(null);
            b.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return b;
        }

        int r = role.intValue();
        boolean future = r == Insight.DAY_FUTURE;
        boolean missed = r == Insight.DAY_MISSED;
        boolean quiet  = r == Insight.DAY_OFF || r == Insight.DAY_REST;
        /* A DOSE IS A DOSE WHATEVER THE CALENDAR CALLS THE DAY.
         *
         * DAY_FUTURE is assigned to TODAY as well as to the days after it - so a session
         * delivered this morning was drawn as an empty cell, in the future ink, and the tap
         * that opens its detail was disabled. The role decides the ink for a quiet day and
         * the outline for a missed one; it does not get to say whether a dose happened. */
        boolean hasDose = tier != Insight.DOSE_NONE;

        b.setText(String.valueOf(dayOfMonth));
        b.setContentDescription(
            Insight.doseCellName(dayOfMonth, PhotoCalendar.monthShort(month), tier, r));

        int fill = tier == Insight.DOSE_HIGH ? Ui.ACCENT
                 : tier == Insight.DOSE_MED  ? Look.DOSE_TIER_MED
                 : tier == Insight.DOSE_LOW  ? Look.DOSE_TIER_LOW
                 : future ? Ui.SURF          // only when there is no dose to draw
                 : Ui.SURFHI;
        // R_PILL, whose own doc names these day cells, rather than a typed 11 - the same
        // change the photo grid's cell above took.
        android.graphics.drawable.GradientDrawable g = Ui.roundRect(a, fill, Look.R_PILL);
        if (missed) g.setStroke(Ui.dp(a, 1), Ui.CRIT);
        b.setBackground(g);

        b.setTextColor(tier == Insight.DOSE_HIGH ? Look.ON_ACCENT
                     : hasDose ? Ui.TEXT
                     : future ? Ui.FAINT_OFF      // no dose to show: disabled
                     : quiet ? Ui.DIM
                     : Ui.TEXT);
        b.setEnabled(hasDose);
        if (hasDose) b.setOnClickListener(new DoseDayTap(taps, dayNumber));
        return b;
    }

    private static final class DoseDayTap implements View.OnClickListener {
        private final DoseTaps taps; private final long day;
        DoseDayTap(DoseTaps t, long d) { taps = t; day = d; }
        @Override public void onClick(View v) { if (taps != null) taps.onDay(day); }
    }
}
