package org.openpump;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The pure logic behind Compare's month CALENDAR picker — which day cells a month has,
 * which of them carry a photo, how far the month arrows may travel, and what tapping a day
 * does to the A/B selection.
 *
 * WHY A CALENDAR REPLACES THE ARROWS. The A/B ◀/▶ steppers move through a LIST of
 * photo-bearing readings by position. That is fine for two adjacent readings and useless
 * for the question the screen actually exists to answer — "how do I look now against three
 * months ago" — because reaching three months ago means counting taps through every
 * reading in between with no idea how many that is. A calendar puts the DATES on screen, so
 * the choice is made in the units the user thinks in, and the dots say which dates are even
 * possible before a single tap is spent.
 *
 * WHAT THIS DOES NOT TOUCH. It changes how the two readings are CHOSEN, and nothing about
 * how they are JUDGED. The same-view-only rule is upstream of everything here (`ps` is
 * {@link Compare#withPhotos}' output, already filtered to one view), and the comparability
 * verdict and its sentence stay exactly where they are, in {@link Compare#verdict}. A
 * calendar cell knows a date and a reading id; it has no opinion about whether the pair is
 * like-for-like.
 *
 * NO ANDROID IMPORT, deliberately — test.sh compiles and runs every line here. The grid of
 * Buttons CompareScreen builds from it is device-only and verified by reading. `Calendar`
 * is java.util, so month arithmetic (leap years, month lengths, the weekday a month starts
 * on, DST) is the platform's own and not re-derived here.
 */
public final class PhotoCalendar {

    private PhotoCalendar() { }

    /** Columns in the grid. Seven, and named, so the row arithmetic below reads as
     *  "a week" rather than as a magic 7. */
    public static final int COLS = 7;

    /* ------------------------------------------------------------------ day keys */

    /**
     * A timestamp reduced to the DAY it falls on, as yyyymmdd — the key a reading and a
     * calendar cell are matched on. Derived through Calendar in the DEFAULT time zone,
     * which is the one the user reads dates in: matching on raw milliseconds divided by
     * 86400000 would put a photo taken at 11 pm local on the next day's cell for any zone
     * east of UTC, and Compare's own day labels would then disagree with the calendar
     * sitting above them.
     */
    public static int dayKey(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(Calendar.YEAR) * 10000
             + (c.get(Calendar.MONTH) + 1) * 100
             + c.get(Calendar.DAY_OF_MONTH);
    }

    /** The day key for an explicit year / 0-based month / day-of-month, without going
     *  through a timestamp at all. */
    public static int dayKey(int year, int month0, int dayOfMonth) {
        return year * 10000 + (month0 + 1) * 100 + dayOfMonth;
    }

    /* --------------------------------------------------------------------- month */

    /** A year and a 0-based month — the unit the ‹ › arrows move, kept as a value so
     *  navigation is a pure function rather than two mutable ints on the screen. */
    public static final class Month {
        public final int year, month0;
        public Month(int year, int month0) { this.year = year; this.month0 = month0; }

        /** A single comparable ordinal, so "is this month before that one" is a subtraction
         *  rather than a two-field comparison that can be got the wrong way round. */
        public int ordinal() { return year * 12 + month0; }

        public boolean equals(Object o) {
            if (!(o instanceof Month)) return false;
            Month m = (Month) o;
            return m.year == year && m.month0 == month0;
        }
        public int hashCode() { return ordinal(); }
    }

    /** The month a timestamp falls in. */
    public static Month monthOf(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return new Month(c.get(Calendar.YEAR), c.get(Calendar.MONTH));
    }

    public static Month prevMonth(Month m) {
        return m.month0 == 0 ? new Month(m.year - 1, 11) : new Month(m.year, m.month0 - 1);
    }

    public static Month nextMonth(Month m) {
        return m.month0 == 11 ? new Month(m.year + 1, 0) : new Month(m.year, m.month0 + 1);
    }

    /** Days in the month — Calendar's own answer, so February 2024 has 29 without this
     *  class knowing what a leap year is. */
    public static int daysInMonth(Month m) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(m.year, m.month0, 1);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    /**
     * How many blank cells precede the 1st, so day 1 lands under its real weekday column.
     * Measured against Calendar's own FIRST_DAY_OF_WEEK — which is Sunday in some locales
     * and Monday in others — because the header row CompareScreen draws is built from the
     * same source. A hardcoded "Sunday is column 0" would put every date one column out for
     * half the world.
     */
    public static int leadingBlanks(Month m) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(m.year, m.month0, 1);
        int dow = c.get(Calendar.DAY_OF_WEEK);           // 1..7, Sunday = 1
        int first = c.getFirstDayOfWeek();               // 1..7
        int blanks = dow - first;
        return blanks < 0 ? blanks + COLS : blanks;
    }

    /** The weekday initials for the header row, in the same order {@link #leadingBlanks}
     *  assumes. Derived, never hardcoded, for the same reason. */
    public static String[] weekdayInitials() {
        Calendar c = Calendar.getInstance();
        int first = c.getFirstDayOfWeek();
        String[] names = { "S", "M", "T", "W", "T", "F", "S" };   // index 0 = Sunday
        String[] out = new String[COLS];
        for (int i = 0; i < COLS; i++) out[i] = names[(first - 1 + i) % COLS];
        return out;
    }

    /** "August 2026" — the month title, in the app's own Locale.US date vocabulary (the
     *  same one Compare's "MMM d" day labels use, so the two read as one screen). */
    public static String monthTitle(Month m) {
        String[] names = { "January", "February", "March", "April", "May", "June", "July",
                           "August", "September", "October", "November", "December" };
        int i = m.month0 < 0 || m.month0 > 11 ? 0 : m.month0;
        return names[i] + " " + m.year;
    }

    /** The short month name a day cell's accessibility label uses. */
    public static String monthShort(Month m) {
        String[] names = { "January", "February", "March", "April", "May", "June", "July",
                           "August", "September", "October", "November", "December" };
        int i = m.month0 < 0 || m.month0 > 11 ? 0 : m.month0;
        return names[i];
    }

    /* ---------------------------------------------------------------------- cells */

    /**
     * One position in the grid. A BLANK (day == 0) is a leading pad before the 1st; a real
     * day carries its number, and a day that has a photo also carries the id of the reading
     * that photo belongs to. `readingId == null` is exactly "no photo", so a cell cannot
     * claim a dot it has nothing to select.
     */
    public static final class Cell {
        public final int day;              // 1..31, or 0 for a blank pad
        public final String readingId;     // null when this day has no photo
        public final long ts;              // the reading's own ts; 0 without one

        Cell(int day, String readingId, long ts) {
            this.day = day; this.readingId = readingId; this.ts = ts;
        }
        public boolean isBlank() { return day == 0; }
        public boolean hasPhoto() { return readingId != null; }
    }

    /**
     * The month's grid: {@link #leadingBlanks} pads followed by one cell per day, each
     * carrying the reading whose photo falls on it.
     *
     * TWO READINGS ON ONE DAY. Possible, and the calendar has exactly one cell for the day,
     * so one of them must win. The NEWEST is chosen — `ps` is newest-first, and the first
     * match is kept — because a second reading logged later the same day is a correction or
     * a better shot, and the more recent photo is the one the user means by that date. This
     * is a real limitation, not a solved problem: the older reading cannot be selected from
     * the calendar at all. It is accepted because the alternative (a day cell that opens a
     * sub-picker) buys a case that barely happens at the cost of the clarity the calendar
     * exists for. The A/B labels below the grid always print the exact reading in play, so
     * the user is never misled about WHICH reading a date resolved to.
     */
    public static List<Cell> cells(List<Model.Reading> ps, Month m) {
        List<Cell> out = new ArrayList<Cell>();
        int blanks = leadingBlanks(m);
        for (int i = 0; i < blanks; i++) out.add(new Cell(0, null, 0));
        int days = daysInMonth(m);
        for (int d = 1; d <= days; d++) {
            int key = dayKey(m.year, m.month0, d);
            String id = null;
            long ts = 0;
            if (ps != null) {
                for (int i = 0; i < ps.size(); i++) {
                    Model.Reading r = ps.get(i);
                    if (r == null) continue;
                    if (dayKey(r.ts) == key) { id = r.id; ts = r.ts; break; }   // newest wins
                }
            }
            out.add(new Cell(d, id, ts));
        }
        return out;
    }

    /**
     * EVERY reading in `ps` that falls on `key`, newest-first — the answer {@link #cells}
     * deliberately throws away.
     *
     * The limitation cells() documents (one cell per day, newest wins, the older reading
     * unreachable from the grid) was accepted because the alternative was thought to be a
     * sub-picker that muddied the calendar. It is not: the calendar stays exactly as it is,
     * and the sub-picker only appears on the days where this returns more than one — which
     * is precisely the days where the calendar alone would have quietly picked for the
     * user. A day with one photo behaves as it always did, with no extra tap.
     *
     * Ordering is `ps`' own (newest-first), never re-sorted, so the strip of thumbnails and
     * the "newest wins" default the grid still shows agree about which photo is first.
     */
    public static List<Model.Reading> onDay(List<Model.Reading> ps, int key) {
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        if (ps == null) return out;
        for (int i = 0; i < ps.size(); i++) {
            Model.Reading r = ps.get(i);
            if (r != null && dayKey(r.ts) == key) out.add(r);
        }
        return out;
    }

    /** The readings sharing a day with `id`, newest-first — the strip a tap on that date
     *  offers. One entry (or none, for an id that is not in `ps`) means there is nothing to
     *  choose and the caller must not put a picker in the way. */
    public static List<Model.Reading> siblingsOf(List<Model.Reading> ps, String id) {
        int i = Compare.indexOf(ps, id);
        if (i < 0) return new ArrayList<Model.Reading>();
        return onDay(ps, dayKey(ps.get(i).ts));
    }

    /**
     * WHICH of the readings sharing a day is currently picked as A or B, or null when none
     * of them is. A is preferred when both slots somehow land on the same day, so the
     * answer is deterministic rather than list-order dependent.
     *
     * This is what keeps a tap on an already-selected date meaning DESELECT once a day can
     * hold several photos. The grid's cell carries only the NEWEST reading's id (cells()'
     * documented rule), so a user who deliberately picked the OLDER photo of that day
     * would otherwise tap the lit cell and get "select the newest" instead of the
     * deselection {@link #tap}'s state machine promises — the one control on the screen
     * that would have stopped meaning what it says.
     */
    public static String selectedOnDay(List<Model.Reading> sameDay, Sel sel) {
        if (sameDay == null || sel == null) return null;
        for (int i = 0; i < sameDay.size(); i++) {
            Model.Reading r = sameDay.get(i);
            if (r != null && sel.isA(r.id)) return r.id;
        }
        for (int i = 0; i < sameDay.size(); i++) {
            Model.Reading r = sameDay.get(i);
            if (r != null && sel.isB(r.id)) return r.id;
        }
        return null;
    }

    /**
     * The A/B letter a CELL should wear, given that the cell names only one reading but the
     * day may hold several. Delegates to {@link #roleOf} for whichever reading on that day
     * is actually picked, so a dot is lit whenever THAT DATE is in the pair — not only when
     * the newest photo on it happens to be the one chosen.
     */
    public static String roleOfDay(List<Model.Reading> ps, Sel sel, String cellReadingId) {
        String picked = selectedOnDay(siblingsOf(ps, cellReadingId), sel);
        return picked == null ? null : roleOf(sel, picked);
    }

    /** The strip's prompt, naming the day and how many photos are on it. Said outright:
     *  a row of near-identical thumbnails with no sentence above it does not explain why
     *  a tap on a date suddenly produced a second question. */
    public static String multiDayLine(int count, String dayLabel) {
        return count + " photos on " + dayLabel + " — tap the one to compare.";
    }

    /** The time-of-day label separating two photos taken on the SAME date — the only thing
     *  that CAN separate them, since the date is identical by construction. 24-hour, so it
     *  needs no am/pm and sorts the way it reads. */
    public static String timeLabel(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    /** How many cells in this month carry a photo — what the screen needs to say "no photos
     *  in August" without walking the grid itself. */
    public static int photoCount(List<Cell> cells) {
        int n = 0;
        if (cells != null) for (int i = 0; i < cells.size(); i++)
            if (cells.get(i).hasPhoto()) n++;
        return n;
    }

    /* ------------------------------------------------------------------ navigation */

    /** The earliest month with a photo, or null when there are none. `ps` is newest-first,
     *  so the last entry is the oldest — but the minimum is taken outright rather than
     *  relying on that ordering, because a bound that is silently wrong when the list order
     *  changes is a bound that is not really enforced. */
    public static Month earliestMonth(List<Model.Reading> ps) {
        if (ps == null || ps.isEmpty()) return null;
        Month best = null;
        for (int i = 0; i < ps.size(); i++) {
            Model.Reading r = ps.get(i);
            if (r == null) continue;
            Month m = monthOf(r.ts);
            if (best == null || m.ordinal() < best.ordinal()) best = m;
        }
        return best;
    }

    /**
     * The month the picker opens on: the month of the NEWEST photo, or the current month
     * when there are none. Opening on the newest photo rather than on today means the first
     * thing the user sees is a month with dots in it — a calendar that opens on an empty
     * month reads as broken.
     */
    public static Month initialMonth(List<Model.Reading> ps, long nowTs) {
        if (ps == null || ps.isEmpty()) return monthOf(nowTs);
        Month best = null;
        for (int i = 0; i < ps.size(); i++) {
            Model.Reading r = ps.get(i);
            if (r == null) continue;
            Month m = monthOf(r.ts);
            if (best == null || m.ordinal() > best.ordinal()) best = m;
        }
        return best == null ? monthOf(nowTs) : best;
    }

    /**
     * Whether ‹ may move back from `m`. The floor is the earliest month that HAS a photo:
     * there is nothing to find before it, and a picker that scrolls into 2019 forever
     * invites the user to go looking. With no photos at all the floor is the current month,
     * so the arrows are simply dead.
     */
    public static boolean canGoPrev(List<Model.Reading> ps, Month m, long nowTs) {
        Month floor = earliestMonth(ps);
        if (floor == null) floor = monthOf(nowTs);
        return m.ordinal() > floor.ordinal();
    }

    /** Whether › may move forward. The ceiling is the CURRENT month — a photo cannot be
     *  taken in the future, so a month beyond today can only ever be empty. */
    public static boolean canGoNext(Month m, long nowTs) {
        return m.ordinal() < monthOf(nowTs).ordinal();
    }

    /** ‹ applied, or the same month back when the bound refuses. Returning the SAME month
     *  rather than null means a caller that forgets to check the bound gets a no-op instead
     *  of a crash. */
    public static Month stepMonth(List<Model.Reading> ps, Month m, int dir, long nowTs) {
        if (dir < 0) return canGoPrev(ps, m, nowTs) ? prevMonth(m) : m;
        if (dir > 0) return canGoNext(m, nowTs) ? nextMonth(m) : m;
        return m;
    }

    /* ------------------------------------------------------------ selection machine */

    /**
     * Which two readings are picked, by id. Deliberately the same representation
     * {@link Compare.Selection} uses — ids, never indices — for the reason that class's doc
     * spells out at length: an index and an id held side by side is defect #1, and it comes
     * back the moment a second representation exists. This one converts INTO a
     * Compare.Selection and never alongside one.
     */
    public static final class Sel {
        public String aId, bId;

        public Sel() { }
        public Sel(String a, String b) { aId = a; bId = b; }
        public Sel copy() { return new Sel(aId, bId); }

        public boolean isA(String id) { return id != null && id.equals(aId); }
        public boolean isB(String id) { return id != null && id.equals(bId); }
        public boolean complete() { return aId != null && bId != null; }
    }

    /**
     * What a tap on a dotted day does. THE STATE MACHINE, stated once and asserted, because
     * a two-slot picker has more edges than it looks like it has:
     *
     *   nothing selected      -> the tap becomes A.
     *   A selected only       -> the tap becomes B, and the pair is complete.
     *   B selected only       -> the tap becomes A. (Reachable by deselecting A.)
     *   both selected         -> the pair ROLLS: the old B becomes A and the tap becomes B.
     *                            So a third tap always compares "the last one I picked"
     *                            against "the one before it", which is what repeatedly
     *                            tapping down a column of dates should feel like — never a
     *                            silent refusal, and never an arbitrary choice of which of
     *                            the two existing picks to evict.
     *   tapping A or B itself -> DESELECTS that slot. Documented and deliberate: a tap on
     *                            an already-selected date has no other meaning (re-selecting
     *                            it is a no-op the user cannot see), and without deselection
     *                            there is no way back to a one-slot state at all. The other
     *                            slot is left exactly where it is.
     *
     * A tap on a day with no photo never reaches here — those cells are disabled — and a
     * null id is a no-op rather than a slot set to null by accident.
     *
     * Returns a NEW Sel; `sel` is never mutated, so a caller cannot half-apply a tap.
     */
    public static Sel tap(Sel sel, String id) {
        Sel out = sel == null ? new Sel() : sel.copy();
        if (id == null) return out;

        if (out.isA(id)) { out.aId = null; return out; }
        if (out.isB(id)) { out.bId = null; return out; }

        if (out.aId == null && out.bId == null) { out.aId = id; return out; }
        if (out.bId == null) { out.bId = id; return out; }
        if (out.aId == null) { out.aId = id; return out; }

        out.aId = out.bId;      // roll: the previous B becomes A
        out.bId = id;
        return out;
    }

    /** Swap, kept here so the calendar's own state has one — the screen's Swap button
     *  writes through this and then converts, rather than reaching past it into
     *  Compare.withSwap and leaving the two representations disagreeing. */
    public static Sel swap(Sel sel) {
        Sel out = sel == null ? new Sel() : sel.copy();
        String t = out.aId; out.aId = out.bId; out.bId = t;
        return out;
    }

    /** Drops any id that no longer names a reading in `ps` — a photo deleted since the
     *  selection was made leaves an id pointing at nothing, and a dot that cannot be found
     *  must clear rather than linger as a selection the grid does not draw. */
    public static Sel prune(List<Model.Reading> ps, Sel sel) {
        Sel out = sel == null ? new Sel() : sel.copy();
        if (out.aId != null && Compare.indexOf(ps, out.aId) < 0) out.aId = null;
        if (out.bId != null && Compare.indexOf(ps, out.bId) < 0) out.bId = null;
        return out;
    }

    /**
     * Seeds an empty selection from what Compare would have picked anyway — the oldest and
     * the newest photo — so opening the screen shows a real comparison rather than an empty
     * grid waiting for two taps. Only fills slots that are EMPTY: a deliberate pick is never
     * overwritten, the no-clobber property Compare.resolve's doc explains at length and for
     * the same reason. Does nothing at all when there are fewer than two photos, because
     * there is no pair to seed.
     */
    public static Sel seed(List<Model.Reading> ps, Sel sel) {
        Sel out = sel == null ? new Sel() : sel.copy();
        if (ps == null || ps.size() < 2) return out;
        if (out.aId == null && out.bId == null) {
            out.aId = ps.get(ps.size() - 1).id;      // oldest
            out.bId = ps.get(0).id;                  // newest
        }
        return out;
    }

    /** The calendar's selection as the Selection the rest of Compare already speaks, with
     *  the mode and seam position of whatever was on screen carried across untouched — the
     *  picker changes WHICH two readings, never how they are shown or judged. */
    public static Compare.Selection toSelection(Sel sel, Compare.Selection current) {
        Compare.Selection out = current == null ? new Compare.Selection() : current.copy();
        out.aId = sel == null ? null : sel.aId;
        out.bId = sel == null ? null : sel.bId;
        return out;
    }

    /* -------------------------------------------------------------------- a11y */

    /** The A/B letter a cell is selected as, or null — the one place a slot becomes a
     *  spoken word, so the dot's colour is never the only thing carrying it. */
    public static String roleOf(Sel sel, String id) {
        if (sel == null || id == null) return null;
        if (sel.isA(id)) return "A";
        if (sel.isB(id)) return "B";
        return null;
    }

    /**
     * A day cell's accessibility name: "14 August, has photo, selected as A".
     *
     * Every fact a sighted user gets from the cell is in here, because none of them survive
     * otherwise — the dot is a drawn circle, the selection is a colour, and a disabled cell
     * is a dimmer shade of the same number. A day with no photo says so rather than staying
     * silent, so a user walking the grid learns which dates are candidates instead of
     * tapping into nothing.
     */
    public static String cellName(int day, String monthName, boolean hasPhoto, String role) {
        StringBuilder b = new StringBuilder();
        b.append(day).append(' ').append(monthName);
        if (!hasPhoto) { b.append(", no photo"); return b.toString(); }
        b.append(", has photo");
        if (role != null) b.append(", selected as ").append(role);
        return b.toString();
    }

    /** The month arrows' names, which must say WHICH month they go to — "previous month" on
     *  its own leaves the user to guess where they are about to land. */
    public static String prevMonthName(Month m) { return "Previous month, " + monthTitle(prevMonth(m)); }
    public static String nextMonthName(Month m) { return "Next month, " + monthTitle(nextMonth(m)); }

    /**
     * What the screen says under the grid about the state of the pick. The three states a
     * two-slot picker can be in, each said outright rather than left to be inferred from
     * which dots happen to be lit:
     *
     *   neither picked -> what to do.
     *   one picked     -> what is picked and what is still needed.
     *   both picked    -> nothing; the A/B rows below already print both dates in full, and
     *                     a fourth sentence saying so is noise.
     *
     * `dayA`/`dayB` are the already-drawn date strings, so this cannot format a date
     * differently from the rows beside it — the same discipline A11y's UNIT RULE applies to
     * values.
     */
    public static String pickLine(String dayA, String dayB) {
        boolean a = dayA != null, b = dayB != null;
        if (a && b) return "";
        if (!a && !b) return "Tap a dotted date to pick the first photo.";
        return "Picked " + (a ? dayA : dayB) + " — now tap a second dotted date.";
    }

    /**
     * The sentence for a month with nothing in it. Names the month, so it is clear the
     * emptiness is about WHERE the user is rather than about the library as a whole, and
     * points at the arrows, which are the way out.
     */
    public static String emptyMonthLine(Month m) {
        return "No " + monthShort(m).toLowerCase(Locale.US) + " photos of this view — "
             + "use the arrows to find a month that has some.";
    }

    /**
     * The sentence when the whole library cannot support a comparison at all: one photo, or
     * none. Distinct from the empty-month line above, because the arrows do NOT fix this and
     * saying "try another month" would send the user hunting for something that is not
     * there. Returns null when there ARE two or more and the calendar should simply be shown.
     */
    public static String cannotCompareLine(int photoCount, String view) {
        if (photoCount >= 2) return null;
        // Point 19: the view by its LABEL ("POV"), never the stored key "front".
        String name = Shot.label(view);
        if (photoCount == 1) {
            return "Only one " + name + " photo so far. Take another on a different day to "
                 + "compare.";
        }
        return "No " + name + " photos yet. A comparison needs two, taken on different days.";
    }
}
