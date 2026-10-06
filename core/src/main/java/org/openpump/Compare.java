package org.openpump;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure logic behind the Compare-two-dates screen (Task 9) — which two readings are
 * picked, what BEFORE/AFTER means for them, and the delta/rate/comparability numbers —
 * kept out of CompareScreen (which owns the real Canvas drawing, bitmaps and touch
 * handling) so the desktop self-test can exercise the exact arithmetic without an
 * Activity. Mirrors proto/pump-console.html's CMP object and cmpStep/cmpSwap/
 * renderCompare, corrected per
 * .superpowers/sdd/2026-08-17-pump-beta/prototype-known-defects.md:
 *
 *   #1 (REGRESSION) — the prototype stores the pair as BOTH an index (CMP.a/CMP.b, what
 *   the ◀/▶ arrows and ⇄ Swap actually write) AND an id (CMP.aid/CMP.bid, written only at
 *   the bottom of renderCompare "to survive a deletion"). Every renderCompare() call
 *   re-derives the index from the id FIRST, unconditionally — so the id, which the arrows
 *   and Swap never touch, silently overwrites the index a tap just changed on the very
 *   next paint. Nothing throws; the pair is pinned to whatever was picked at boot forever.
 *
 *   Fixed here by having exactly ONE representation: {@link Selection} holds only aId/bId
 *   (strings), never an index. An index is never stored — only ever DERIVED, on demand,
 *   by {@link #indexOf}. {@link #resolve} is the sole place a missing id (never set, or
 *   pointing at a reading that was since deleted) gets replaced with a fallback — and it
 *   is a no-op for an id that is still present: resolving an already-valid id maps it to
 *   its own index and immediately back to itself, so a deliberate pick can never be
 *   clobbered by a later render the way the prototype's unconditional re-derivation was.
 *   {@link #withStep} and {@link #withSwap} are the only things that WRITE a new id — the
 *   arrows and Swap button write ids directly, exactly as the fix note prescribes, so
 *   there is no second field left un-updated for the next resolve() to stomp on.
 *
 *   #5 — {@link #verdict} never re-derives its own "were these held the same way" check.
 *   The boolean comes from {@link Meas#windowDirectlyComparable}, called with exactly this
 *   pair — the SAME rule History uses (itself built on {@link Model.Reading#comparable}),
 *   so Compare and History can never disagree about the same two readings (defect #23,
 *   which Task 8 already eliminated, staying eliminated here rather than being
 *   reintroduced by a screen that writes its own second copy of the logic). The prose
 *   explanation is built from the same three primitives that rule is defined on
 *   (holdKpa null-ness/equality, holdSec null-ness/equality) so the two can never drift
 *   apart: a reading whose hold duration was never recorded (holdKpa set, holdSec null)
 *   gets its own "cannot be compared" sentence — added BEFORE the final "like for like"
 *   case, per the fix note — rather than falling through into it the way the prototype's
 *   `A.sec && B.sec && ...` short-circuit did when exactly one side was null.
 *
 *   #9 — {@link #ratePer30d} is computed once and used TWICE by the caller, for length
 *   and for girth independently — CompareScreen is the one that must label each result
 *   with its own series name (never print one bare, unlabelled figure immediately after
 *   the girth chip the way the prototype does), but the maths itself is exercised here so
 *   the self-test can pin the exact numbers regardless of how the screen presents them.
 */
public final class Compare {
    private Compare() { }

    /* ---------------------------------------------------------------- selection */

    /** Which two (photo-bearing) readings are being compared, and how. Deliberately holds
     *  NO index anywhere — see the class doc's #1 section for why an index field is
     *  exactly the bug. `mode` is one of "wipe"/"side"/"blink"; `wipe` is the seam
     *  position, 0..100. */
    public static final class Selection {
        public String aId, bId;
        public String mode = "wipe";
        public int wipe = 50;

        public Selection copy() {
            Selection s = new Selection();
            s.aId = aId; s.bId = bId; s.mode = mode; s.wipe = wipe;
            return s;
        }
    }

    /** Index of the reading whose id is `id` inside `ps`, or -1 if absent — the ONLY place
     *  an id ever gets turned into a position, and always computed fresh, never cached. */
    public static int indexOf(List<Model.Reading> ps, String id) {
        if (ps == null || id == null) return -1;
        for (int i = 0; i < ps.size(); i++)
            if (id.equals(ps.get(i).id)) return i;
        return -1;
    }

    private static int clampIdx(int i, int n) { return i < 0 ? 0 : (i >= n ? n - 1 : i); }

    /**
     * Repairs `sel` against the current photo-bearing list `ps` (newest-first; see
     * {@link #withPhotos}), which must hold at least two readings. Returns a NEW Selection
     * — `sel` itself is never mutated.
     *
     * An id that still resolves to a reading in `ps` is left completely untouched: this is
     * what makes the fix hold — a deliberate pick can never be silently reset by a routine
     * "normalise on render" call, only a genuinely missing id (never set, or deleted since
     * the last render) is replaced, and only with a fallback default. If both ids end up
     * pointing at the SAME reading (possible right after a deletion collapses them, or on
     * the very first call before either was ever set), `aId` is nudged to the neighbouring
     * reading — mirroring the prototype's own collision handling — rather than silently
     * comparing a reading with itself.
     */
    public static Selection resolve(List<Model.Reading> ps, Selection sel) {
        int n = ps.size();
        Selection out = (sel == null) ? new Selection() : sel.copy();
        int ia = indexOf(ps, out.aId);
        int ib = indexOf(ps, out.bId);
        if (ia < 0) ia = n - 1;     // default: the oldest reading with a photo
        if (ib < 0) ib = 0;         // default: the newest
        ia = clampIdx(ia, n);
        ib = clampIdx(ib, n);
        if (ia == ib) ia = (ib == n - 1) ? ib - 1 : ib + 1;
        ia = clampIdx(ia, n);
        out.aId = ps.get(ia).id;
        out.bId = ps.get(ib).id;
        return out;
    }

    /**
     * The ◀/▶ arrows: moves the given side (`"a"` or `"b"`) by `direction` steps through
     * `ps` (positive = older, negative = newer, matching the prototype's own ◀ = older /
     * ▶ = newer wiring) and WRITES the resulting id — never leaving the caller to update a
     * separate index field that a later resolve() could disagree with. Resolves first, so
     * a step is always relative to where the id ACTUALLY is right now (which may have
     * moved if a reading was deleted elsewhere since the last render), and resolves again
     * at the end so the result is never left colliding with the other side.
     */
    public static Selection withStep(List<Model.Reading> ps, Selection sel, String side, int direction) {
        Selection cur = resolve(ps, sel);
        boolean isA = "a".equals(side);
        int n = ps.size();
        int idx = indexOf(ps, isA ? cur.aId : cur.bId);
        if (idx < 0) idx = 0;
        int next = clampIdx(idx + direction, n);
        String newId = ps.get(next).id;
        if (isA) cur.aId = newId; else cur.bId = newId;
        return resolve(ps, cur);
    }

    /** The ⇄ Swap button: exchanges aId/bId directly — the id IS the state, so this is the
     *  whole fix (the prototype's cmpSwap swapped only the index half of a split
     *  representation, leaving aid/bid, and therefore the next render, unchanged). */
    public static Selection withSwap(Selection sel) {
        Selection out = (sel == null) ? new Selection() : sel.copy();
        String t = out.aId; out.aId = out.bId; out.bId = t;
        return out;
    }

    /* ------------------------------------------------------------------- photos */

    /** Whether `r` has a usable photo for `view` ("front", "side" or "top") — the ground
     *  truth for "can this be compared visually" is the actual captured Photo record, not
     *  the editor's separately-toggleable `photo` flag (Task 8's reading editor lets that
     *  flag be flipped by hand, independent of whether a file was ever taken). */
    public static boolean hasPhoto(Model.Reading r, String view) {
        if (r == null) return false;
        Model.Reading.Photo p = photoOf(r, view);
        return p != null && p.path != null && p.path.length() > 0;
    }

    /** The Photo record for a view name, or null — the one place a "front"/"side"/"top"
     *  string maps to a slot, so a new view is added here rather than at every reader. */
    public static Model.Reading.Photo photoOf(Model.Reading r, String view) {
        if (r == null) return null;
        if ("side".equals(view)) return r.photoSide;
        if ("top".equals(view))  return r.photoTop;
        return r.photoFront;
    }

    /** Readings that have a photo for `view`, newest-first (the order `log.all` already
     *  maintains). A reading with the OTHER view but not this one is honestly excluded —
     *  comparing Front against Side would compare framing, not tissue (Step 5: handle a
     *  missing photo honestly, not by silently substituting a mismatched one). */
    public static List<Model.Reading> withPhotos(Model.MeasLog log, String view) {
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        if (log == null) return out;
        for (int i = 0; i < log.all.size(); i++) {
            Model.Reading r = log.all.get(i);
            if (hasPhoto(r, view)) out.add(r);
        }
        return out;
    }

    /**
     * POINT 19 - WAS THIS PHOTO TAKEN UNDER A HOLD. The photo's own record when it has one
     * ({@link Model.Reading.Photo#held}); otherwise - a photo written before that was
     * recorded, or an import - the kind of the reading it is on, which is what every screen
     * assumed before and all such a photo can support.
     */
    public static boolean photoHeld(Model.Reading r, Model.Reading.Photo p) {
        if (p != null && p.held != null) return p.held.booleanValue();
        return Model.Reading.isStandardised(r);
    }

    /** How a photo was taken ({@link #photoKind}): at rest; under a hold that was not served
     *  (its count not completed, or its two minutes passed) - kept on its own; or during a
     *  served hold - standardised, at {@link #photoStdKpa}. */
    public static final int KIND_REST = 0, KIND_HELD = 1, KIND_STD = 2;

    /**
     * THE OWNER'S RULE - A PHOTO IS GROUPED BY HOW IT WAS TAKEN; THE NUMBERS KEEP THEIR OWN
     * KIND. The photo's own record when it has one ({@link Model.Reading.Photo#std}): a photo
     * taken during a served hold is standardised even when its reading's numbers were saved
     * at rest (the hold ended or vented before Save), and one taken under a hold whose count
     * had not completed is held, kept on its own, even when its reading ended up standardised.
     * Without that record - written before it existed, or an import - the kind is derived as
     * every screen did before: held ({@link #photoHeld}) on a standardised reading is
     * standardised, held on another is kept on its own, anything else is at rest.
     */
    public static int photoKind(Model.Reading r, Model.Reading.Photo p) {
        if (p != null && p.std != null) {
            if (p.std.booleanValue()) return KIND_STD;
            return photoHeld(r, p) ? KIND_HELD : KIND_REST;
        }
        if (!photoHeld(r, p)) return KIND_REST;
        return Model.Reading.isStandardised(r) ? KIND_STD : KIND_HELD;
    }

    /** The hold pressure (kPa) a standardised photo was taken at - its own record, or, for a
     *  photo without one, its reading's hold. Null when it is not a standardised photo. */
    public static Double photoStdKpa(Model.Reading r, Model.Reading.Photo p) {
        if (photoKind(r, p) != KIND_STD) return null;
        if (p != null && p.stdKpa != null) return p.stdKpa;
        return r == null ? null : r.holdKpa;
    }

    /**
     * How the photo was taken, as a line says it - the PDF's photo page and Compare's badge:
     * "standardised at −5.8 inHg", "held at −5.8 inHg", "at rest". The pressure is the one
     * the pump DELIVERED at the shutter (the photo's `kpa`; for a photo without one, the
     * vacuum its standardised reading observed), never the commanded setpoint dressed as
     * delivered: "standardised, vacuum not measured" when none was.
     */
    public static String photoTakenLine(Model.Reading r, Model.Reading.Photo p) {
        int kind = photoKind(r, p);
        if (kind == KIND_REST) return "at rest";
        Double at = p != null ? p.kpa : null;
        if (kind == KIND_STD) {
            if (at == null && r != null && Model.Reading.isStandardised(r)) at = r.observedKpa;
            return at == null ? "standardised, vacuum not measured"
                              : "standardised at " + Model.Fmt.p(at.doubleValue());
        }
        return at == null ? "held, vacuum not measured" : "held at " + Model.Fmt.p(at.doubleValue());
    }

    /**
     * The readings with a photo of `view` TAKEN THE SAME WAY - under a hold when `held`, at
     * rest otherwise - newest-first. What a new capture's alignment ghost is chosen from: a
     * standardised photo is framed with the cylinder in view and the phone further back,
     * which is why the reference ANGLE is already kept per mode, and a ghost from the other
     * mode lined the next photo up against the wrong framing.
     */
    public static List<Model.Reading> withPhotosTaken(Model.MeasLog log, String view,
                                                      boolean held) {
        List<Model.Reading> all = withPhotos(log, view);
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        for (int i = 0; i < all.size(); i++) {
            Model.Reading r = all.get(i);
            if (photoHeld(r, photoOf(r, view)) == held) out.add(r);
        }
        return out;
    }

    /* ------------------------------------------------ the method a photo belongs to */

    /**
     * POINT 19 - COMPARE PAIRS PHOTOS OF ONE METHOD. At rest and standardised are two equal
     * ways of tracking progress, and each at-rest method (BPEL, MSEG...) is its own
     * measurement; a photo is compared only with photos taken the same way. Compare used to
     * pair the oldest photo of a view with the newest whatever each was, and print a
     * coloured change between a BPEL sitting and a standardised one.
     *
     * The key is how the PHOTO was taken ({@link #photoKind} - the owner's rule: a photo is
     * grouped by how it was taken, the numbers keep their own kind), and for a photo taken at
     * rest its reading's method:
     *
     *   "std@K"     standardised - taken during a served hold - at hold pressure K kPa
     *               ({@link #stdKey}); only the same way is compared, so another pressure is
     *               another group. A standardised photo on a reading saved at rest (the hold
     *               ended before Save) is here too
     *   "std:N@K"   the same, for a photo WITHOUT its own record on a standardised reading
     *               tagged with at-rest method N (the editor allowed it) - as before
     *   KEY_STD     a standardised photo whose pressure is not recorded anywhere
     *   "held@K"    taken under a hold that was not served (its count not completed, or its
     *               two minutes passed) - M1's "held, kept on its own" - at hold pressure K;
     *               never paired across pressures (the data-integrity review, item 4)
     *   KEY_HELD    the same, with no hold pressure recorded (taken before it was kept)
     *   "mN"        at rest, method N (BPEL, MSEG...)
     *   "rest:hard" at rest on a reading saved before the method axis, recorded erect -
     *   "rest:soft"   or soft: "method not recorded", and erect never with soft (item 4)
     *   KEY_REST    at rest, no method and no state recorded (and Std-line readings whose
     *               hold was not recorded) - "method not recorded", on its own
     *
     * Null when the reading has no photo of `view`.
     */
    public static final String KEY_STD = "std", KEY_HELD = "held", KEY_REST = "rest";

    public static String methodKey(Model.Reading r, String view) {
        if (!hasPhoto(r, view)) return null;
        boolean atRestMethod = Model.Reading.isAtRestMethod(r.method);
        Model.Reading.Photo p = photoOf(r, view);
        int kind = photoKind(r, p);
        if (kind == KIND_STD) {
            String base = (p.std == null && atRestMethod) ? KEY_STD + ":" + r.method : KEY_STD;
            Double k = photoStdKpa(r, p);
            return k == null ? base : base + "@" + kpaKey(k.doubleValue());
        }
        if (kind == KIND_HELD)
            return p.holdKpa == null ? KEY_HELD : KEY_HELD + "@" + kpaKey(p.holdKpa.doubleValue());
        if (atRestMethod) return "m" + r.method;
        // Method not recorded: the state it recorded is the only condition it has, and
        // erect and soft are never the same measurement.
        if (Model.Reading.STATE_HARD.equals(r.state)) return KEY_REST + ":hard";
        if (Model.Reading.STATE_SOFT.equals(r.state)) return KEY_REST + ":soft";
        return KEY_REST;
    }

    /** The hold pressure (kPa) of a "held@K" group, or null. */
    private static Double heldKpaOfKey(String key) {
        if (key == null || !key.startsWith(KEY_HELD + "@")) return null;
        try { return Double.valueOf(Double.parseDouble(key.substring(KEY_HELD.length() + 1))); }
        catch (RuntimeException e) { return null; }
    }

    /** The group key of the standardised photos taken at hold pressure `kpa`. */
    public static String stdKey(double kpa) { return KEY_STD + "@" + kpaKey(kpa); }

    /** A pressure as a key: kPa to one decimal, whatever the display unit - so the same
     *  setting is one group, and float noise below 0.05 kPa is not a new one. */
    private static String kpaKey(double kpa) {
        return String.format(java.util.Locale.US, "%.1f", kpa);
    }

    /** Whether `key` is a standardised group. */
    public static boolean isStdKey(String key) {
        return key != null && (KEY_STD.equals(key) || key.startsWith(KEY_STD + ":")
                               || key.startsWith(KEY_STD + "@"));
    }

    /** The hold pressure (kPa) a standardised group is compared at, or null. */
    public static Double stdKpaOfKey(String key) {
        if (!isStdKey(key)) return null;
        int at = key.indexOf('@');
        if (at < 0) return null;
        try { return Double.valueOf(Double.parseDouble(key.substring(at + 1))); }
        catch (RuntimeException e) { return null; }
    }

    /** The at-rest method N of a "std:N" / "std:N@K" key, or -1. */
    private static int stdMethodOfKey(String key) {
        if (key == null || !key.startsWith(KEY_STD + ":")) return -1;
        int at = key.indexOf('@');
        return intAfter(at < 0 ? key : key.substring(0, at), KEY_STD.length() + 1);
    }

    /** The method's name as a control shows it: "Standardised", "BPEL", "At rest"... */
    public static String methodName(String key) {
        if (key == null) return "";
        if (isStdKey(key)) {
            // "Standardised · −5.9 inHg": the group's hold pressure, which is what makes it one
            // group ("Standardised · BPEL · −5.9 inHg" for an editor-tagged reading).
            int n = stdMethodOfKey(key);
            Double k = stdKpaOfKey(key);
            return "Standardised"
                + (n >= 0 ? " · " + Model.Reading.methodLabel(n) : "")
                + (k != null ? " · " + Model.Fmt.p(k.doubleValue()) : "");
        }
        if (KEY_HELD.equals(key)) return "Held, kept on its own";
        Double held = heldKpaOfKey(key);
        if (held != null) return "Held, kept on its own · " + Model.Fmt.p(held.doubleValue());
        if (KEY_REST.equals(key)) return "At rest · method not recorded";
        if ((KEY_REST + ":hard").equals(key)) return "At rest · erect, method not recorded";
        if ((KEY_REST + ":soft").equals(key)) return "At rest · soft, method not recorded";
        if (key.startsWith("m")) return Model.Reading.methodLabel(intAfter(key, 1));
        return key;
    }

    /** The method as a sentence says it: "standardised", "BPEL at rest", "at rest"... */
    public static String methodPhrase(String key) {
        if (key == null) return "";
        if (key.startsWith("m")) return methodName(key) + " at rest";
        if (KEY_HELD.equals(key)) return "held, kept on its own";
        Double held = heldKpaOfKey(key);
        if (held != null) return "held at " + Model.Fmt.p(held.doubleValue()) + ", kept on its own";
        if (KEY_REST.equals(key)) return "at rest, method not recorded";
        if ((KEY_REST + ":hard").equals(key)) return "at rest, erect, method not recorded";
        if ((KEY_REST + ":soft").equals(key)) return "at rest, soft, method not recorded";
        int n = stdMethodOfKey(key);
        Double k = stdKpaOfKey(key);
        return "standardised" + (n >= 0 ? " (" + Model.Reading.methodLabel(n) + ")" : "")
            + (k != null ? " at " + Model.Fmt.p(k.doubleValue()) : "");
    }

    private static int intAfter(String key, int from) {
        try { return Integer.parseInt(key.substring(from)); }
        catch (RuntimeException e) { return -1; }
    }

    /**
     * {@link #withPhotos} of one method only - the list Compare, the then-vs-now card and its
     * chooser pair from, newest-first - with EACH PHOTO FILE ONCE. A save of several at-rest
     * rows gives every reading of that sitting the same photo (E3), and a standardised
     * photo's group does not split by method, so the same file sat in one group twice and
     * was paired with itself (the data-integrity review, item 1). The first - newest - of
     * the readings sharing a file stands for it, so no pair can be one file twice.
     */
    public static List<Model.Reading> withPhotos(Model.MeasLog log, String view, String key) {
        List<Model.Reading> all = withPhotos(log, view);
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        if (key == null) return out;
        java.util.Set<String> files = new java.util.HashSet<String>();
        for (int i = 0; i < all.size(); i++) {
            Model.Reading r = all.get(i);
            if (!key.equals(methodKey(r, view))) continue;
            if (!files.add(photoOf(r, view).path)) continue;       // this file is listed
            out.add(r);
        }
        return out;
    }

    /** How many photo FILES of `view` there are - a photo shared by several readings of one
     *  sitting counts once, as the photo it is. What "Your N POV photos" counts. */
    public static int photoCount(Model.MeasLog log, String view) {
        List<Model.Reading> all = withPhotos(log, view);
        java.util.Set<String> files = new java.util.HashSet<String>();
        for (int i = 0; i < all.size(); i++) files.add(photoOf(all.get(i), view).path);
        return files.size();
    }

    /** Every method `view` has photos of, ordered by each method's newest photo. */
    public static List<String> methodsOf(Model.MeasLog log, String view) {
        List<Model.Reading> all = withPhotos(log, view);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < all.size(); i++) {
            String k = methodKey(all.get(i), view);
            if (k != null && !out.contains(k)) out.add(k);
        }
        return out;
    }

    /** The methods of `view` that hold at least TWO photos - the ones a comparison can be
     *  made within - ordered by each method's newest photo, newest first. */
    public static List<String> methodsWithPairs(Model.MeasLog log, String view) {
        List<String> keys = methodsOf(log, view);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < keys.size(); i++)
            if (withPhotos(log, view, keys.get(i)).size() >= 2) out.add(keys.get(i));
        return out;
    }

    /** Which method Compare opens on for `view`: the method, among those with a pair, whose
     *  newest photo is newest - so a user who just took a standardised photo is shown their
     *  standardised pair. Null when no method of this view has two photos. */
    public static String defaultMethod(Model.MeasLog log, String view) {
        List<String> pairs = methodsWithPairs(log, view);
        return pairs.isEmpty() ? null : pairs.get(0);
    }

    /**
     * The line when `view` has photos but no two taken the same way: how many, which ways,
     * and what would make a pair - never that one of them is the lesser photo. "Your 2 POV
     * photos were each taken a different way (BPEL at rest and standardised). A comparison
     * needs two taken the same way: take another on a different day."
     */
    public static String noPairLine(String view, int photoCount, List<String> keys) {
        StringBuilder ways = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) ways.append(i == keys.size() - 1 ? " and " : ", ");
            ways.append(methodPhrase(keys.get(i)));
        }
        return "Your " + photoCount + " " + Shot.label(view) + " photos were each taken a "
             + "different way (" + ways + "). A comparison needs two taken the same way: "
             + "take another on a different day.";
    }

    /* ------------------------------------------------------------- then vs now */

    /** The views a photo can belong to, in capture order — the SAME three strings
     *  {@link #photoOf} maps, stated once for anything that scans all views. */
    public static final String[] PHOTO_VIEWS = { "front", "side", "top" };

    /** The Progress card's pick (§8 #6): which view to pair, and the FIRST and LATEST
     *  photo-bearing reading of that view. Ids only — never indices (see the class doc's
     *  #1 section) and never bitmaps; the card resolves ids against the live log when it
     *  draws, so a reading deleted since this was computed simply stops resolving. */
    public static final class ThenNow {
        public final String view;
        /** The method both photos were taken by ({@link #methodKey}) - point 19: the card
         *  pairs within one method and names it. */
        public final String method;
        public final String firstId, latestId;
        ThenNow(String view, String method, String firstId, String latestId) {
            this.view = view; this.method = method;
            this.firstId = firstId; this.latestId = latestId;
        }
    }

    /**
     * The "then vs now" pair for the Progress card, or null when NO view has two photos
     * taken the same way yet (the card then asks for a second photo instead of a pair).
     *
     * WHICH VIEW, AND WHICH METHOD (point 19): the view-and-method with the MOST
     * photo-bearing readings — more photos means a longer, better-populated story to
     * bracket, and a pair is only ever made within one method ({@link #methodKey}). A tie
     * goes to the one whose NEWEST photo is newest: between equally-deep histories, the
     * one still being added to is the one the user is actually using. Within it the pair
     * is simply the oldest and the newest reading that has a photo — the whole span, which
     * is the point of a then-vs-now card. Reuses {@link #withPhotos}' resolution (newest-
     * first, real Photo records only) rather than pairing photos a second way.
     */
    public static ThenNow thenVsNow(Model.MeasLog log) {
        String bestView = null, bestKey = null;
        List<Model.Reading> best = null;
        for (int i = 0; i < PHOTO_VIEWS.length; i++) {
            // POINT 19 - within ONE METHOD: the story a then-vs-now card tells is one way of
            // measuring, start to finish. Among each view's methods with a pair, the deepest.
            List<String> keys = methodsWithPairs(log, PHOTO_VIEWS[i]);
            for (int k = 0; k < keys.size(); k++) {
                List<Model.Reading> ps = withPhotos(log, PHOTO_VIEWS[i], keys.get(k));
                if (ps.size() < 2) continue;             // one photo has nothing to be paired with
                if (best == null
                        || ps.size() > best.size()
                        || (ps.size() == best.size() && ps.get(0).ts > best.get(0).ts)) {
                    bestView = PHOTO_VIEWS[i];
                    bestKey = keys.get(k);
                    best = ps;
                }
            }
        }
        if (best == null) return null;
        return new ThenNow(bestView, bestKey, best.get(best.size() - 1).id, best.get(0).id);
    }

    /* ------------------------------------------------- the then-vs-now chooser */

    /**
     * THE "N AGO" RULE, stated once and asserted: the photo of this view NEAREST to
     * `targetTs`, preferring the nearest one BEFORE it when a before and an after are
     * equally far away.
     *
     *   EXACT wins outright — a photo taken on the target instant is the answer.
     *   NEAREST-BEFORE is preferred on a tie. "Six months ago" is a claim about the past:
     *   given a photo four days before the target and one four days after, the earlier one
     *   is the one that is genuinely six months old, and the later one is five months and
     *   twenty-six days old being called six. The bias is small and it is always in the
     *   honest direction.
     *   NEAREST-AFTER is used only when there is nothing before at all — a target older
     *   than the whole library. Returning null there would make "1 year ago" simply do
     *   nothing on a library six months deep; returning the oldest photo shows the closest
     *   thing that exists, and the card's caption prints its real date, so nothing claims
     *   to be a year old that is not.
     *
     * `ps` is {@link #withPhotos}' output — already one view, already newest-first — so
     * this can never pair across views. Null for an empty list.
     */
    public static Model.Reading nearestTo(List<Model.Reading> ps, long targetTs) {
        if (ps == null || ps.isEmpty()) return null;
        Model.Reading best = null;
        long bestDist = 0;
        for (int i = 0; i < ps.size(); i++) {
            Model.Reading r = ps.get(i);
            if (r == null) continue;
            long dist = Math.abs(r.ts - targetTs);
            if (best == null || dist < bestDist) { best = r; bestDist = dist; continue; }
            // The tie-break, and the only place the before-preference is expressed: an
            // equally-distant candidate replaces the incumbent ONLY when it is the earlier
            // one. Never on a bare `<=`, which would make the answer depend on list order.
            if (dist == bestDist && r.ts < best.ts) best = r;
        }
        return best;
    }

    /**
     * The pair the Progress card actually shows, applying the user's persisted choice on
     * top of the default. Returns null when no view has two photos — the same "nothing to
     * pair" answer {@link #thenVsNow} gives, so the card's empty state is unchanged.
     *
     * WHICH VIEW is still {@link #thenVsNow}'s decision alone: the chooser picks which two
     * PHOTOS of that view are paired, never which view. A view toggle here would give the
     * card two independent pickers and a way to select a pair it then refuses to compare;
     * the view stays derived from where the user's photos actually are.
     *
     * EVERY FALLBACK IS THE DEFAULT, never nothing. A picked id whose reading was deleted,
     * a mode string a hand-edited file invented, an "ago" target older than the library —
     * each degrades to first/latest rather than to an empty card, exactly as
     * {@link PhotoCalendar#prune} degrades a stale Compare pick. And if the two sides
     * resolve to the SAME reading (a "3 months ago" on a library whose only photos are from
     * this week), the left falls back to the oldest, so the card never compares a photo
     * with itself.
     */
    public static ThenNow resolvePick(Model.MeasLog log, Model.ThenNowPick pick, long now) {
        ThenNow base = thenVsNow(log);
        if (base == null) return null;
        if (pick == null) return base;
        List<Model.Reading> ps = withPhotos(log, base.view, base.method);
        if (ps.size() < 2) return base;

        String leftId = base.firstId;
        int days = Model.ThenNowPick.daysAgo(pick.left);
        if (days > 0) {
            Model.Reading near = nearestTo(ps, now - days * 86400000L);
            if (near != null) leftId = near.id;
        } else if (Model.ThenNowPick.LEFT_PICK.equals(pick.left)
                   && indexOf(ps, pick.leftId) >= 0) {
            leftId = pick.leftId;
        }

        String rightId = base.latestId;
        if (Model.ThenNowPick.RIGHT_PICK.equals(pick.right)
                && indexOf(ps, pick.rightId) >= 0) {
            rightId = pick.rightId;
        }

        if (leftId != null && leftId.equals(rightId)) {
            // Collapsed onto one photo. The OLDEST is the honest widening — it is what
            // "then" meant before the user chose anything, and ps holds at least two.
            leftId = ps.get(ps.size() - 1).id;
            if (leftId.equals(rightId)) leftId = ps.get(0).id;
        }
        return new ThenNow(base.view, base.method, leftId, rightId);
    }

    /** "Mar 2026" — a month and year, the unit a then-vs-now caption speaks in. A bare
     *  "MMM d" would put two identically-labelled Marches on a card spanning two years. */
    public static String monthYear(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        String[] names = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                           "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
        return names[c.get(java.util.Calendar.MONTH)] + " " + c.get(java.util.Calendar.YEAR);
    }

    /**
     * WHAT THE CARD IS ACTUALLY SHOWING, in words: "Mar 2026 vs today", "Mar 2026 vs
     * Aug 2026".
     *
     * This exists because the chooser makes the card's content ambiguous in a way it never
     * was. First-versus-latest needed no caption — it was the only thing the card could be.
     * A card that might be showing three months ago, or a photo picked by hand, or the
     * default, and says nothing, is a comparison whose terms the reader has to guess; and a
     * guessed comparison is worse than no comparison, which is this codebase's rule about
     * numbers applied to pictures.
     *
     * "today" rather than a date for a photo taken today, because that is what the user
     * calls it and the card's whole claim is "then, and now".
     */
    public static String pickCaption(long leftTs, long rightTs, long now) {
        String right = PhotoCalendar.dayKey(rightTs) == PhotoCalendar.dayKey(now)
                     ? "today" : monthYear(rightTs);
        return monthYear(leftTs) + " vs " + right;
    }

    /* ------------------------------------------------------------------ labels */

    /** Which of `a`/`b` is chronologically BEFORE and which is AFTER — derived purely from
     *  `ts`, never from which slot (A or B) a reading happens to occupy. Picking a newer
     *  reading as "A" must still label it AFTER: the label follows the calendar, not the
     *  control that happened to select it. A tie (identical ts) treats `a` as before by a
     *  stable convention rather than leaving the pair undefined. */
    public static final class Labeled {
        public final Model.Reading before, after;
        public final boolean aIsBefore;
        Labeled(Model.Reading before, Model.Reading after, boolean aIsBefore) {
            this.before = before; this.after = after; this.aIsBefore = aIsBefore;
        }
    }

    public static Labeled labelPair(Model.Reading a, Model.Reading b) {
        boolean aIsBefore = a.ts <= b.ts;
        return aIsBefore ? new Labeled(a, b, true) : new Labeled(b, a, false);
    }

    /* --------------------------------------------------------- deltas and rate */

    /** Whole days between two readings — never negative, matching Model.MeasLog's own
     *  86400000ms-per-day convention (see MeasLog.window). */
    public static long daysBetween(Model.Reading older, Model.Reading newer) {
        return Math.max(0, (newer.ts - older.ts) / 86400000L);
    }

    /**
     * Newest minus older, in cm — or Double.NaN when EITHER reading's method never
     * measured this metric ({@link Model.Reading#measuredLength}/{@code measuredGirth}),
     * the Task 1 predicate ({@code 64cd5a0}) reused here rather than re-invented: `len`/
     * `gir` are primitive doubles, so a method that never asked for this metric (a
     * length-only BPSSL reading's `gir`, say) leaves it at 0.0, indistinguishable from a
     * real zero-change measurement. Compare exists specifically to show a delta, so
     * feeding that unmeasured zero into a subtraction here would be the single most
     * prominent instance of the bug Task 1 fixed everywhere else.
     *
     * THIS IS A DIFFERENT QUESTION from {@link #verdict}'s pair-level comparability: two
     * readings can be "directly comparable" as readings — e.g. a BPEL and an MSEG
     * reading are both at-rest HARD, which {@link Model.Reading#comparable}'s legacy
     * state fallback (see {@link Model.Reading#stateForMethod}'s own doc) deliberately
     * treats as a match — while one of them never measured the metric this method is
     * being asked to diff. verdict() is not second-guessed or bypassed by this gate, and
     * this gate does not weaken verdict()'s own rule; the two compose by both being
     * checked, the same way {@link Meas#sinceDelta} composes with
     * {@link Model.Reading#comparable} for the exact same reason.
     */
    public static double deltaLen(Model.Reading older, Model.Reading newer) {
        // AND A REAL VALUE AT BOTH ENDS. measuredLength() says the METHOD measures a
        // length; a half-filled standardised reading stores the one nobody typed as 0.0.
        return (older.measuredLength() && newer.measuredLength()
                && older.len > 0 && newer.len > 0)
            ? newer.len - older.len : Double.NaN;
    }

    /** The girth counterpart of {@link #deltaLen} — see that method's doc for the full
     *  rule and why it is a different question from {@link #verdict}'s comparability. */
    public static double deltaGir(Model.Reading older, Model.Reading newer) {
        return (older.measuredGirth() && newer.measuredGirth()
                && older.gir > 0 && newer.gir > 0)
            ? newer.gir - older.gir : Double.NaN;
    }

    /** `delta` extrapolated to a 30-day rate — 0 when the two readings are the same day
     *  (nothing to divide by), never a divide-by-zero NaN/Infinity; Double.NaN in, Double.NaN
     *  out otherwise (NaN divided or multiplied by a finite number is still NaN), so a
     *  caller does not need to re-check {@link #deltaLen}/{@link #deltaGir}'s own gate a
     *  second time UNLESS days is also <= 0 — in which case the caller must check
     *  Double.isNaN(delta) itself before calling this, since the days<=0 branch returns a
     *  literal 0.0 regardless of delta and would otherwise turn an absent metric's NaN
     *  into a fabricated "no change" rate on a same-day pair. Used identically for
     *  length and girth; see the class doc's #9 section for why the CALLER must label each
     *  result with its own series name rather than printing one bare. */
    public static double ratePer30d(double delta, long days) {
        return days <= 0 ? 0.0 : delta / (double) days * 30.0;
    }

    /* ------------------------------------------------------------- comparability */

    public static final class Verdict {
        public final boolean comparable;
        public final String text;
        Verdict(boolean comparable, String text) { this.comparable = comparable; this.text = text; }
    }

    /**
     * The comparability verdict for exactly two readings (Task 19). The boolean is
     * {@link Meas#windowDirectlyComparable} applied to this exact pair — never a second
     * rule invented here — so it can never disagree with what the History screen would say
     * about the same two readings (defect #23). The sentence is
     * {@link Model.Reading#comparabilityReason} on the same pair, so the words and the
     * verdict are built from the same facts and cannot drift apart: it names the REAL
     * reason a pair is not like-for-like — a different class, a different observed vacuum,
     * an unknown observed vacuum, a different state, or an unknown state.
     *
     * `labelA`/`labelB` are unused now the reason sentence names the kind of mismatch
     * rather than the slot; kept in the signature so CompareScreen's call site is
     * untouched.
     */
    public static Verdict verdict(Model.Reading a, Model.Reading b, String labelA, String labelB) {
        boolean comparable = Meas.windowDirectlyComparable(Arrays.asList(a, b));
        return new Verdict(comparable, Model.Reading.comparabilityReason(a, b));
    }
}
