package org.openpump;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The pure logic behind the PHOTO GALLERY — every photo this phone holds, grouped by the
 * DAY it was taken, plus the sentences each group and each photo is described by, plus the
 * one operation that can remove a photo.
 *
 * WHY BY DAY. Photos live on READINGS, and a reading is a moment: three view slots on one
 * record, taken within a minute of each other. Listing them as a flat grid of files makes
 * the user re-derive "these three are the same session" from the thumbnails, and listing
 * them per READING buries the thing they actually navigate by — the date — under a record
 * id they have never seen. A day header with that day's measurements on it answers "what
 * did I look like, and what did I measure, on the 14th" in one row, which is the question.
 *
 * A DAY MAY HOLD SEVERAL READINGS. This is the case Compare's calendar cannot represent
 * (see {@link PhotoCalendar#cells}, which keeps only the newest per day and says so): two
 * readings logged the same day are two separate records with their own photos, and here
 * they are ALL shown, because a gallery's job is to show what exists rather than to pick
 * one. {@link PhotoCalendar#onDay} is the shared answer to "which readings fall on this
 * day", so the gallery and Compare's own per-day photo picker cannot disagree about it.
 *
 * DELETION, and the rule that governs it. {@link #clearPhoto} removes the reference to a
 * photo from the reading that owns it and returns the file path so the caller can delete
 * the file. It NEVER deletes the reading: the numbers on that record — the length, the
 * girth, the vacuum it was taken at — are a measurement, and a measurement is not a photo's
 * caption. Deleting a photograph you dislike must not silently delete the data point it was
 * taken beside, or the trend quietly changes shape because someone was embarrassed by a
 * picture. The confirm dialog says exactly this, and this function is what makes it true.
 *
 * NO ANDROID IMPORT, deliberately: test.sh compiles and runs every line here. The grid of
 * ImageViews SessionActivity builds from it is device-only.
 */
public final class Gallery {

    private Gallery() { }

    /** Columns in the thumbnail grid. Three, and named, so the row arithmetic reads as a
     *  grid rather than as a magic 3 — the same discipline {@link PhotoCalendar#COLS}
     *  applies to the calendar's seven. */
    public static final int COLS = 3;

    /* -------------------------------------------------------------------- items */

    /**
     * One photo in the gallery: which reading owns it, which view slot it fills, where the
     * file is, and the moment it was taken. Ids and a path only — never a bitmap. The
     * gallery decodes thumbnails as it draws them and drops them when the screen is
     * rebuilt, so nothing here can pin a decoded photo in memory.
     */
    public static final class Item {
        public final String readingId;   // the Reading that owns this photo
        public final String view;        // "front" | "side" | "top" — Compare.PHOTO_VIEWS
        public final String path;        // absolute, app-private; never in an export or a log
        public final long ts;            // the PHOTO's own ts, falling back to the reading's
        public final boolean fromGallery; // imported from the phone's photos, not shot here

        Item(String readingId, String view, String path, long ts, boolean fromGallery) {
            this.readingId = readingId; this.view = view; this.path = path; this.ts = ts;
            this.fromGallery = fromGallery;
        }
    }

    /**
     * One day's worth: the day key, the readings that fall on it (newest-first) and every
     * photo on those readings. `ts` is the newest reading's own timestamp, so the header
     * can print a real date rather than reconstructing one from the key.
     */
    public static final class Day {
        public final int dayKey;
        public final long ts;
        public final List<Model.Reading> readings = new ArrayList<Model.Reading>();
        public final List<Item> items = new ArrayList<Item>();

        Day(int dayKey, long ts) { this.dayKey = dayKey; this.ts = ts; }

        public int photoCount() { return items.size(); }
    }

    /**
     * Every photo-bearing day, NEWEST DAY FIRST, each day's readings newest-first and each
     * reading's photos in {@link Compare#PHOTO_VIEWS} order (front, side, top).
     *
     * The ordering is derived from the log's own, not re-sorted: `log.all` is newest-first
     * by construction (see {@link Model.MeasLog}), so walking it in order and appending to
     * whichever day bucket is current produces days newest-first and readings newest-first
     * within them, with no comparator that could disagree with how the rest of the app
     * orders the same records.
     *
     * A reading with NO photo contributes nothing — not an empty day, not a header. The
     * gallery is the photos; the readings list is where a numbers-only record lives.
     */
    public static List<Day> byDay(Model.MeasLog log) {
        List<Day> out = new ArrayList<Day>();
        if (log == null) return out;
        for (int i = 0; i < log.all.size(); i++) {
            Model.Reading r = log.all.get(i);
            if (r == null) continue;
            List<Item> mine = itemsOf(r);
            if (mine.isEmpty()) continue;              // no photo, no row
            int key = PhotoCalendar.dayKey(r.ts);
            Day day = null;
            for (int j = 0; j < out.size(); j++)
                if (out.get(j).dayKey == key) { day = out.get(j); break; }
            if (day == null) { day = new Day(key, r.ts); out.add(day); }
            day.readings.add(r);
            for (int j = 0; j < mine.size(); j++) day.items.add(mine.get(j));
        }
        return out;
    }

    /** One reading's photos, in {@link Compare#PHOTO_VIEWS} order — the same order the
     *  capture flow offers them in, so a reading's three thumbnails always sit in the same
     *  order in every grid. A slot with no file is skipped, never drawn as an empty tile. */
    public static List<Item> itemsOf(Model.Reading r) {
        List<Item> out = new ArrayList<Item>();
        if (r == null) return out;
        for (int i = 0; i < Compare.PHOTO_VIEWS.length; i++) {
            String view = Compare.PHOTO_VIEWS[i];
            if (!Compare.hasPhoto(r, view)) continue;
            Model.Reading.Photo p = Compare.photoOf(r, view);
            // The photo's own ts when it has one; the reading's otherwise. A Photo written
            // before that field carried a value reads 0, and a gallery tile stamped
            // 1 January 1970 is worse than one stamped with the reading it belongs to.
            out.add(new Item(r.id, view, p.path, p.ts > 0 ? p.ts : r.ts, p.fromGallery));
        }
        return out;
    }

    /** Total photos across the whole log — what an empty gallery needs to distinguish "no
     *  photos at all" from "no photos in this filter". E3: PHOTOS, not references - one
     *  save of several methods gives one file to each of its readings, and that is one
     *  photo, not three. */
    public static int photoCount(Model.MeasLog log) {
        List<String> paths = new ArrayList<String>();
        List<Day> days = byDay(log);
        for (int i = 0; i < days.size(); i++)
            for (int j = 0; j < days.get(i).items.size(); j++) {
                String p = days.get(i).items.get(j).path;
                if (!paths.contains(p)) paths.add(p);
            }
        return paths.size();
    }

    /** The Item for one reading id + view, or null — the one place the detail screen turns
     *  the two ids it was opened with back into a photo, so a photo deleted since the grid
     *  was drawn resolves to nothing rather than to a stale path. */
    public static Item find(Model.MeasLog log, String readingId, String view) {
        Model.Reading r = readingOf(log, readingId);
        if (r == null) return null;
        List<Item> items = itemsOf(r);
        for (int i = 0; i < items.size(); i++)
            if (items.get(i).view.equals(view)) return items.get(i);
        return null;
    }

    /** The reading with this id, or null. */
    public static Model.Reading readingOf(Model.MeasLog log, String readingId) {
        if (log == null || readingId == null) return null;
        for (int i = 0; i < log.all.size(); i++) {
            Model.Reading r = log.all.get(i);
            if (r != null && readingId.equals(r.id)) return r;
        }
        return null;
    }

    /* ------------------------------------------------------------------ deletion */

    /**
     * Removes the photo reference for `view` from `r` and returns the file path that is now
     * unreferenced, or null when there was nothing there. The CALLER deletes the file; this
     * only breaks the link, so that a failed file delete leaves a dangling path rather than
     * a record pointing at bytes the user asked to be rid of.
     *
     * THE READING SURVIVES, always. Its length, girth, vacuum, state and note are untouched
     * — see this class's doc for why that is a rule and not an implementation detail. The
     * `photo` FLAG is cleared only when no view has a file left, because that flag means
     * "this reading has a photograph" and it would be a lie in either direction otherwise.
     */
    public static String clearPhoto(Model.Reading r, String view) {
        if (r == null || view == null) return null;
        Model.Reading.Photo p = Compare.photoOf(r, view);
        String path = (p == null || p.path == null || p.path.length() == 0) ? null : p.path;
        if ("side".equals(view))      r.photoSide = null;
        else if ("top".equals(view))  r.photoTop = null;
        else if ("front".equals(view)) r.photoFront = null;
        else return null;                       // an unknown view slot clears nothing
        if (!hasAnyPhoto(r)) r.photo = false;
        return path;
    }

    /**
     * Whether any reading in the log still refers to the file at `path`, on any view. One
     * save of several methods gives the SAME file to every reading in it, so a file is only
     * ever deleted from the phone once this says no reading uses it.
     */
    public static boolean pathInUse(Model.MeasLog log, String path) {
        if (log == null || path == null || path.length() == 0) return false;
        for (int i = 0; i < log.all.size(); i++) {
            Model.Reading r = log.all.get(i);
            if (r == null) continue;
            for (int v = 0; v < Compare.PHOTO_VIEWS.length; v++) {
                Model.Reading.Photo p = Compare.photoOf(r, Compare.PHOTO_VIEWS[v]);
                if (p != null && path.equals(p.path)) return true;
            }
        }
        return false;
    }

    /**
     * E3 - THE OTHER READINGS THAT SHARE THIS READING'S PHOTO of `view`: the same file, from
     * one save of several methods (one sitting, one set of photos). Newest first. Empty when
     * the photo is this reading's alone.
     */
    public static List<Model.Reading> sharers(Model.MeasLog log, Model.Reading r, String view) {
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        Model.Reading.Photo mine = Compare.photoOf(r, view);
        if (log == null || mine == null || mine.path == null || mine.path.length() == 0) return out;
        for (int i = 0; i < log.all.size(); i++) {
            Model.Reading o = log.all.get(i);
            if (o == null || o == r || (r.id != null && r.id.equals(o.id))) continue;
            Model.Reading.Photo p = Compare.photoOf(o, view);
            if (p != null && mine.path.equals(p.path)) out.add(o);
        }
        return out;
    }

    /** How a reading is named in a sentence: its method ("MSEG"), "standardised", or
     *  "at-rest" for one with neither. */
    public static String readingName(Model.Reading r) {
        if (r == null) return "";
        if (Model.Reading.isAtRestMethod(r.method)) return Model.Reading.methodLabel(r.method);
        return Model.Reading.isStandardised(r) ? "standardised" : "at-rest";
    }

    /** "BPEL", "BPEL and NBPEL", "BPEL, NBPEL and MSEG". */
    static String names(List<Model.Reading> rs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rs.size(); i++) {
            if (i > 0) b.append(i == rs.size() - 1 ? " and " : ", ");
            b.append(readingName(rs.get(i)));
        }
        return b.toString();
    }

    /** E3 - the confirm title for a SHARED photo: "Remove this photo from the MSEG reading?" */
    public static String removeTitle(Model.Reading r) {
        return "Remove this photo from the " + readingName(r) + " reading?";
    }

    /** E3 - what removing a shared photo does, said before it is done: "It stays on the other
     *  2 readings from that sitting (BPEL and NBPEL). It leaves the phone only when no
     *  reading uses it." */
    public static String removeMessage(List<Model.Reading> others) {
        int n = others == null ? 0 : others.size();
        return "It stays on the other " + (n == 1 ? "reading" : n + " readings")
             + " from that sitting (" + names(others) + "). It leaves the phone only when no "
             + "reading uses it.";
    }

    /** E3 - the snack once it is removed: "Photo removed from the MSEG reading · still on
     *  BPEL and NBPEL". */
    public static String removedSnack(Model.Reading r, List<Model.Reading> others) {
        return "Photo removed from the " + readingName(r) + " reading · still on "
             + names(others);
    }

    /** E3 - the photo screen's line for a shared photo: which readings it is on, and which
     *  one this screen is showing it on - the one a removal takes it off. */
    public static String sharedLine(Model.Reading r, List<Model.Reading> others) {
        int n = (others == null ? 0 : others.size()) + 1;
        return "On " + n + " readings from that sitting, also " + names(others)
             + ". Shown here on the " + readingName(r) + " reading.";
    }

    /**
     * A tile's caption: the view, and - when the day holds photos taken more than one way -
     * the method it was taken by ("POV · MSEG", "POV · Standardised"). A shared photo shows
     * once per reading it is on, so on such a day the caption is what tells its tiles apart.
     */
    public static String tileLabel(Day day, Item item) {
        String view = viewLabel(item.view);
        if (day == null) return view;
        String mine = null;
        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < day.items.size(); i++) {
            Item it = day.items.get(i);
            String k = Compare.methodKey(readingIn(day, it.readingId), it.view);
            if (k != null && !keys.contains(k)) keys.add(k);
            if (it == item) mine = k;
        }
        if (keys.size() <= 1 || mine == null) return view;
        return view + " · " + Compare.methodName(mine);
    }

    /** A tile's accessibility name, with the same method its caption shows. */
    public static String tileName(Day day, Item item) {
        return tileLabel(day, item) + " photo, " + dayTitle(item.ts) + ". Tap to open it."
             + sourceSpoken(item.fromGallery);
    }

    private static Model.Reading readingIn(Day day, String id) {
        for (int i = 0; i < day.readings.size(); i++)
            if (day.readings.get(i).id != null && day.readings.get(i).id.equals(id))
                return day.readings.get(i);
        return null;
    }

    /** Whether any view slot still holds a file. */
    public static boolean hasAnyPhoto(Model.Reading r) {
        for (int i = 0; i < Compare.PHOTO_VIEWS.length; i++)
            if (Compare.hasPhoto(r, Compare.PHOTO_VIEWS[i])) return true;
        return false;
    }

    /* -------------------------------------------------------------------- words */

    /** "POV" / "Side" / "Top" — the view key as the user sees it written. Delegates to
     *  {@link Shot#label} so the gallery, the capture slots and the PDF cannot end up with
     *  three names for one view; in particular the stored key "front" reads POV everywhere
     *  or nowhere. "Top" still appears here: those photos exist and must stay legible even
     *  though no new one can be taken. */
    public static String viewLabel(String view) {
        return Shot.label(view);
    }

    /* ------------------------------------------------------------------ where from */

    /**
     * THE IMPORTED BADGE, in one place. "▣ imported" for an image that came out of the
     * phone's photo library, and the EMPTY STRING for one this app shot — the ordinary case
     * carries no mark at all, so the badge means something wherever it appears rather than
     * being half of a pair of labels the eye has to read.
     *
     * Always drawn in DIM: it is provenance, not a measurement and not a warning. An
     * imported photo is a perfectly good photo; it simply was not framed through the level
     * guide and was not taken at a vacuum this app watched, so a chain that mixes the two
     * should be able to SEE which is which without being shouted at.
     */
    public static String sourceBadge(boolean fromGallery) {
        return fromGallery ? "▣ imported" : "";
    }

    /** The same fact for a screen reader, as a sentence fragment to append — including the
     *  leading space, so a caller cannot forget it and run two sentences together. Empty for
     *  a captured photo, which needs no explaining. */
    public static String sourceSpoken(boolean fromGallery) {
        return fromGallery
            ? " Imported from this phone's photos, not taken in the app."
            : "";
    }

    /** "14 August 2026" — a gallery day header's date. Written out in full rather than as
     *  Compare's terse "MMM d", because a header is the only place the YEAR appears and a
     *  library two years deep otherwise has two identically-labelled Augusts in it. */
    public static String dayTitle(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(Calendar.DAY_OF_MONTH) + " "
             + PhotoCalendar.monthShort(new PhotoCalendar.Month(c.get(Calendar.YEAR),
                                                                c.get(Calendar.MONTH)))
             + " " + c.get(Calendar.YEAR);
    }

    /**
     * THE DAY HEADER'S SECOND LINE: what was actually MEASURED that day, beside the photos
     * of it, in plain words (E3). Every reading of the day is named, and none is ranked:
     *
     *   "MSEG 12.0 cm · at rest"
     *   "BPEL 15.2 cm · MSEG 12.0 cm · at rest"                  (one sitting, two methods)
     *   "15.4 cm long · 12.3 cm around · standardised at −5.9 inHg"
     *
     * A day holding both kinds gets one line for each (at rest and standardised are two
     * equal methods, and a card naming only the newest said nothing about the other). An
     * at-rest method names its own one number; the old "— long · 12.0 cm around" made a
     * girth-only reading read as a length nobody took.
     *
     * Pressures go through {@link Model.Fmt#p}, so this line follows the display unit like
     * every other pressure in the app — a gallery that printed a bare kPa figure beside a
     * Progress screen showing inHg is the unit defect this codebase has already fixed
     * twice. Lengths go through {@link Model.Fmt#len} for exactly the same reason, so a
     * gallery does not print centimetres beside a Progress screen showing inches.
     *
     * SEVERAL READINGS ON THE DAY are each named, never averaged. An average of two
     * measurements taken hours apart, one standardised and one at rest, is a number nobody
     * took.
     */
    public static String daySummary(List<Model.Reading> readings) {
        if (readings == null || readings.isEmpty()) return "no measurement recorded";
        List<String> lines = new ArrayList<String>();
        StringBuilder atRest = null;
        int atRestLine = -1;
        for (int i = 0; i < readings.size(); i++) {
            Model.Reading r = readings.get(i);
            if (r == null) continue;
            if (!Model.Reading.isStandardised(r) && Model.Reading.isAtRestMethod(r.method)) {
                // The day's at-rest methods share one line, as they share one sitting.
                if (atRest == null) {
                    atRest = new StringBuilder();
                    atRestLine = lines.size();
                    lines.add("");
                } else {
                    atRest.append(" · ");
                }
                atRest.append(measured(r));
            } else {
                lines.add(readingLine(r));
            }
        }
        if (atRest != null) lines.set(atRestLine, atRest + " · at rest");
        if (lines.isEmpty()) return "no measurement recorded";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) b.append('\n');
            b.append(lines.get(i));
        }
        return b.toString();
    }

    /**
     * ONE READING IN PLAIN WORDS: what it measured, then how it was taken - "MSEG 12.0 cm ·
     * at rest", "15.4 cm long · 12.3 cm around · standardised at −5.9 inHg". The same words
     * the day line and the photo screen use, so the two cannot describe one reading two
     * ways.
     */
    public static String readingLine(Model.Reading r) {
        if (r == null) return "";
        return measured(r) + " · " + kind(r);
    }

    /** What a reading measured. An at-rest method names itself and its one number ("MSEG
     *  12.0 cm"); a standardised or method-less reading gives both, a metric its method
     *  never measured (or left at 0.0) as "—", never as a number nobody took. */
    static String measured(Model.Reading r) {
        if (Model.Reading.isAtRestMethod(r.method)) {
            double v = r.measuredLength() ? r.len : r.gir;
            return Model.Reading.methodLabel(r.method) + " " + (v > 0 ? cm(v) : "—");
        }
        return (r.measuredLength() && r.len > 0 ? cm(r.len) : "—") + " long · "
             + (r.measuredGirth() && r.gir > 0 ? cm(r.gir) : "—") + " around";
    }

    /** How a reading was taken: "standardised at −5.9 inHg" (the vacuum the pump actually
     *  reported), "standardised, vacuum not measured", or "at rest". */
    static String kind(Model.Reading r) {
        if (Model.Reading.isStandardised(r))
            return r.observedKpa == null ? "standardised, vacuum not measured"
                 : "standardised at " + Model.Fmt.p(r.observedKpa.doubleValue());
        return "at rest";
    }

    /** A length, in the display size unit — the same Model.Fmt#len the readings screens
     *  use, so one rule decides how a length is printed everywhere. Takes centimetres,
     *  which is what everything in this app stores. */
    public static String cm(double v) {
        return Model.Fmt.len(v);
    }

    /**
     * The full-view heading for one photo: which view it is and when it was taken.
     * "Front · 14 August 2026".
     */
    public static String photoTitle(String view, long ts) {
        return viewLabel(view) + "  ·  " + dayTitle(ts);
    }

    /**
     * THE LINKED READING'S NUMBERS, under the full-size photo — the whole point of opening
     * one. Says what was measured on the record this photo belongs to, and what the photo
     * itself recorded at the shutter, which are two different facts: the reading's
     * `observedKpa` is what the pump delivered for the MEASUREMENT, and the photo's `kpa`
     * is what it delivered when the SHUTTER fired. They are usually the same and are not
     * required to be, so both are printed when both exist rather than one standing in for
     * the other.
     *
     * A photo whose reading has been deleted (not reachable today, but the detail screen
     * resolves ids fresh on every open) says so instead of printing zeros.
     */
    public static String photoFacts(Model.Reading r, String view) {
        if (r == null) return "The measurement this photo belonged to is no longer here.";
        // E3: the reading in the same plain words as the day line above it.
        StringBuilder b = new StringBuilder(readingLine(r));
        Model.Reading.Photo p = Compare.photoOf(r, view);
        // POINT 19 - A PHOTO TAKEN ANOTHER WAY THAN ITS READING says so. The line above is
        // the reading's; the photo is grouped by how IT was taken (the owner's rule,
        // Compare#photoKind), so when that differs from the reading's kind - a standardised
        // photo whose numbers were saved at rest because the hold ended before Save, a photo
        // held before its count was served, one at rest on a standardised reading - the
        // picture is not what that line describes, and this says what it is.
        if (p != null) {
            int kind = Compare.photoKind(r, p);
            boolean readingStd = Model.Reading.isStandardised(r);
            Double stdAt = Compare.photoStdKpa(r, p);
            if (kind == Compare.KIND_STD && !readingStd)
                b.append(stdAt == null ? "\nThis photo was taken standardised."
                    : "\nThis photo was taken standardised, at " + Model.Fmt.p(stdAt.doubleValue())
                      + ", and is compared with your standardised photos.");
            else if (kind == Compare.KIND_HELD)
                b.append("\nThis photo was taken while the pump was holding.");
            else if (kind == Compare.KIND_REST && readingStd)
                b.append("\nThis photo was taken at rest.");
        }
        // AN IMPORTED IMAGE HAS NO SHUTTER. The pressure banked beside it is the one the
        // pump was at when the file was PICKED, which says nothing about when the picture
        // was taken — printing it as "shutter fired at" would be a claim the app cannot
        // make. So the provenance replaces the stamp rather than sitting next to it.
        if (p != null && p.fromGallery)
            b.append("\nImported from this phone's photos — the angle it was shot from and "
                   + "the vacuum at the time are unknown.");
        else if (p != null && p.kpa != null)
            b.append("\nShutter fired at ").append(Model.Fmt.p(p.kpa.doubleValue()));
        if (p != null && p.tiltDeg != null && p.turnDeg != null)
            b.append("\nShot at tilt ")
             .append(String.format(java.util.Locale.US, "%+.1f°", p.tiltDeg))
             .append(" · turn ")
             .append(String.format(java.util.Locale.US, "%+.1f°", p.turnDeg));
        if (p != null && p.note != null && p.note.length() > 0)
            b.append("\n").append(p.note);
        return b.toString();
    }

    /** A thumbnail's accessibility name. The tile is an image and nothing else, so without
     *  this it is a stop a screen reader can land on and learn nothing from. */
    public static String thumbName(String view, long ts) {
        return thumbName(view, ts, false);
    }

    /** The same name with the photo's SOURCE in it — the badge the tile draws, said. The
     *  two-argument form above means "shot in the app", which every caller meant before
     *  importing existed. */
    public static String thumbName(String view, long ts, boolean fromGallery) {
        return viewLabel(view) + " photo, " + dayTitle(ts) + ". Tap to open it."
             + sourceSpoken(fromGallery);
    }

    /** The confirm dialog's message. The second sentence is the promise {@link #clearPhoto}
     *  keeps, said before the user commits rather than discovered afterwards. */
    public static String deletePrompt() {
        return "Delete this photo? The measurement record keeps its numbers.";
    }

    /** What the gallery says when there is nothing in it. Names where photos come from,
     *  because a first run reaches this screen with no other clue. */
    public static String emptyLine() {
        return "No photos yet. Photos are taken from the baseline or Log a reading screen — "
             + "tap a POV or Side slot there. They stay on this phone.";
    }

    /** G-Progress: the empty gallery's ONE visible line - that there are none, and where
     *  they stay. Where they come from ({@link #emptyLine}) is behind its ⓘ. */
    public static String emptyShort() {
        return "No photos yet. They stay on this phone.";
    }
}
