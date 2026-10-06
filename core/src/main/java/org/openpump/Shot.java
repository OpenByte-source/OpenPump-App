package org.openpump;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure state behind the camera capture flow (Task 7) — kept out of CameraScreen (which
 * owns the real camera2 device, the TextureView and file IO) so the rules that decide
 * WHICH view a photo belongs to can be exercised by the desktop self-test without a
 * camera. Mirrors proto/pump-console.html's SHOT object, corrected per
 * .superpowers/sdd/2026-08-17-pump-beta/prototype-known-defects.md:
 *
 *   #16 — usePhoto() banks SHOT.taken[SHOT.which] using whatever view is CURRENT at the
 *   moment "Use" is tapped, not the view that was actually framed when the shutter fired.
 *   A shutter double-tap (the shutter and the review screen's Use button occupy the same
 *   screen real estate in the prototype) can flip SHOT.which between the shutter press
 *   and the Use tap, banking the photo under the WRONG view. Fixed here by splitting the
 *   two moments: armShutter() records which view was live the instant the shutter fires
 *   (`pending`), and commit() — called when Use is tapped — banks against `pending`,
 *   never against the live `view`, then clears pending either way. A stray Use tap with
 *   nothing pending (no shutter press since the last commit/reset) is a no-op, signalled
 *   by returning null rather than silently banking the wrong thing.
 *
 *   #18 — usePhoto() flips SHOT.which from 'Front' to 'Side' and re-renders the review
 *   screen's labels BEFORE confirming the navigation to the Side capture screen actually
 *   happened; a refused/duplicate hop leaves the user looking at the Front photo under a
 *   "Side" caption. Fixed here by never letting commit() touch the live `view` at all —
 *   advancing it is confirmAdvance()'s job alone, and the caller (CameraScreen) is
 *   required to call it only AFTER it has actually built and shown the next capture
 *   screen, never before. This mirrors the exact fix shape Task 6 applied to
 *   startStd()/startRelease(): confirm the transition before mutating the state that
 *   names it.
 *
 *   Note-per-view — the prototype accumulates both views' text into ONE SHOT.note string
 *   ("if (typed) SHOT.note = SHOT.note ? SHOT.note + ' - ' + typed : typed"), so whatever
 *   was typed for Front is still sitting in SHOT.note when Side is saved and gets banked
 *   onto the same reading alongside it — a note about the front view literally travels
 *   onto the side capture. Fixed here by keeping notes in a map keyed by view: commit()
 *   writes only into that view's slot, never appends another view's text.
 */
public final class Shot {
    /** THE STORED KEY IS STILL "Front". The user-facing label is now "POV" (see
     *  {@link #label}), but the string that goes on disk, into the file name
     *  reading-&lt;id&gt;-front.jpg, into Compare.PHOTO_VIEWS and into every reading already
     *  logged has NOT changed. Renaming the stored value would orphan every existing photo
     *  and every existing profile; this rename is a label change and nothing else. */
    public static final String FRONT = "Front";
    public static final String SIDE  = "Side";
    /** LEGACY ONLY. Top is no longer part of the capture flow — it is absent from
     *  {@link #VIEWS}, so no new photo can be taken for it. The constant stays because
     *  photos already stored under it must keep rendering (the gallery, Compare, the PDF
     *  and the export all still read `Reading.photoTop`); this is read-only history, not a
     *  slot anything can fill again. */
    public static final String TOP   = "Top";

    /** The views, in the order they are captured and offered — an ordered SET, not a
     *  binary (Task 19). Every "which view is next" question is answered from this array.
     *  TOP was DROPPED from it: three optional slots made the capture flow long enough that
     *  the third was routinely skipped, and a view nobody shoots is a view nothing can be
     *  compared against. Existing top photos are untouched (see {@link #TOP}). */
    public static final String[] VIEWS = { FRONT, SIDE };

    /**
     * THE VIEW AS THE USER SEES IT WRITTEN. "front" (however capitalised) reads POV; every
     * other key is Title-cased as it stands, so "side" is Side and the legacy "top" is Top.
     *
     * Front became POV in the UI only. Nothing here changes what is stored — {@link #FRONT}
     * is still the string "Front" on disk. One function so a tile caption, a slot button, a
     * PDF heading and a spoken content-description cannot drift into three different names
     * for the same photo.
     */
    public static String label(String view) {
        if (view == null || view.length() == 0) return "";
        if (FRONT.equalsIgnoreCase(view)) return "POV";
        return view.substring(0, 1).toUpperCase(Locale.US) + view.substring(1);
    }

    private String view = FRONT;
    private String pending;                                   // armed at the shutter, consumed at commit

    private final Map<String, String> notes = new LinkedHashMap<String, String>();
    private final Map<String, String> paths = new LinkedHashMap<String, String>();
    private final Map<String, Double> kpas  = new LinkedHashMap<String, Double>();
    private final Map<String, Double> tilts = new LinkedHashMap<String, Double>();
    private final Map<String, Double> turns = new LinkedHashMap<String, Double>();
    /** Per view: was this slot filled by IMPORTING an existing image rather than by
     *  shooting one. Keyed the same way everything else here is, so the source travels with
     *  the photo it describes and cannot be read off the wrong view — the same reason notes
     *  are a map and not one string (see the class doc). */
    private final Map<String, Boolean> imported = new LinkedHashMap<String, Boolean>();
    /** Per view: the align transform the saved pixels were baked with — carried exactly the
     *  way tilt/turn are, and for the same reason: it is a fact about the moment the photo
     *  was saved, so it must travel keyed to the view it belongs to and cannot be read off
     *  another view later. Null/absent when align was skipped or the image was imported. */
    private final Map<String, Model.EditProfile> edits =
            new LinkedHashMap<String, Model.EditProfile>();
    /** Per view (point 19): was a hold outstanding when this photo's capture was ARMED -
     *  true under a hold, false at rest, absent for an import (which has no shutter, so
     *  when it was taken is unknown). The one fact that says "at rest" or "held" about the
     *  picture itself; it used to be sampled at the shutter and then dropped, so a saved
     *  photo could only borrow its kind from whichever reading it ended up on. Keyed like
     *  everything else here, so it cannot be read off the wrong view. */
    private final Map<String, Boolean> held = new LinkedHashMap<String, Boolean>();
    /** Per view (the owner's rule): the hold pressure (commanded, kPa) when this photo was
     *  taken during a SERVED hold - the count completed, inside its two minutes - so it is a
     *  standardised photo at that pressure whatever its reading is saved as. Absent when it
     *  was not (at rest, a hold not served or past its two minutes, an import). */
    private final Map<String, Double> stdKpas = new LinkedHashMap<String, Double>();
    /** Per view: the hold pressure (commanded, kPa) when this photo was taken under any hold,
     *  served or not - absent at rest and for an import. */
    private final Map<String, Double> holdKpas = new LinkedHashMap<String, Double>();

    public String view() { return view; }

    private static int indexOf(String v) {
        for (int i = 0; i < VIEWS.length; i++) if (VIEWS[i].equals(v)) return i;
        return -1;
    }

    /** The next view in VIEWS after `v`, WRAPPING back to the first past the end — the
     *  ordered-set replacement for the old binary flip (Task 19). Cycles
     *  Front -> Side -> Top -> Front, so all three are reachable. Static so a render can
     *  name the Flip button's target before the live view has actually moved. */
    public static String viewAfter(String v) {
        int i = indexOf(v);
        return VIEWS[(i < 0 ? 0 : (i + 1) % VIEWS.length)];
    }

    /** The Flip button's target from the live view. */
    public String cycleNext() { return viewAfter(view); }

    /** The capture sequence's auto-advance: the first view AFTER `justCommitted` in VIEWS
     *  order that has not been captured yet, or null once nothing later remains to shoot
     *  (all optional — the flow ends there). Generalises the old Front->Side hop: after
     *  Front it offers Side then Top, after Side it offers Top, after Top it finishes. */
    public String nextToShoot(String justCommitted) {
        int i = indexOf(justCommitted);
        if (i < 0) return null;
        for (int j = i + 1; j < VIEWS.length; j++)
            if (!has(VIEWS[j])) return VIEWS[j];
        return null;
    }

    /** A fresh capture attempt: entering the camera screen for a brand-new reading, or
     *  abandoning whatever was mid-flight. Clears every captured view AND any pending
     *  shutter — a capture armed but never committed must not survive into the next
     *  attempt (the same "clear on every path, including the abandoned one" discipline
     *  defect #24 applies to the camera's open request). */
    public void reset() {
        view = FRONT;
        pending = null;
        notes.clear(); paths.clear(); kpas.clear(); imported.clear(); tilts.clear(); turns.clear();
        edits.clear(); held.clear(); stdKpas.clear(); holdKpas.clear();
    }

    /** POINT 19 - an independent copy of everything captured, for an Undo: the log door's
     *  "Remove these photos" empties the buffer and keeps this copy until the Undo window
     *  closes. The maps are copied, never shared, so the live buffer and the copy cannot
     *  move together. */
    public Shot copy() {
        Shot c = new Shot();
        c.copyFrom(this);
        return c;
    }

    /** Puts `o`'s captures back into THIS buffer (the Activity holds its Shot as a final
     *  field, so an Undo restores into it rather than swapping it). */
    public void copyFrom(Shot o) {
        if (o == null || o == this) return;
        view = o.view;
        pending = o.pending;
        notes.clear(); notes.putAll(o.notes);
        paths.clear(); paths.putAll(o.paths);
        kpas.clear(); kpas.putAll(o.kpas);
        tilts.clear(); tilts.putAll(o.tilts);
        turns.clear(); turns.putAll(o.turns);
        imported.clear(); imported.putAll(o.imported);
        edits.clear(); edits.putAll(o.edits);
        held.clear(); held.putAll(o.held);
        stdKpas.clear(); stdKpas.putAll(o.stdKpas);
        holdKpas.clear(); holdKpas.putAll(o.holdKpas);
    }

    /** The views captured, in {@link #VIEWS} order. */
    public java.util.List<String> taken() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (int i = 0; i < VIEWS.length; i++) if (has(VIEWS[i])) out.add(VIEWS[i]);
        return out;
    }

    /** The shutter fires: bind the capture-in-flight to whichever view is showing RIGHT
     *  NOW. This is the ONE place "which view is this photo" gets decided — see the
     *  class doc's #16 note. */
    public void armShutter() { pending = view; }

    /** The view a shutter press is currently waiting on Use/Retake for, or null if none
     *  is outstanding (nothing shot yet, or the last shot was already committed/retaken). */
    public String pendingView() { return pending; }

    /** Discards whatever is pending without banking it — Retake's job. */
    public void cancelPending() { pending = null; }

    /**
     * Commits the pending capture to the view it was ARMED for (never to whatever `view`
     * happens to be right now — see #16). Returns the view it was banked to, or null if
     * nothing was pending (a stray Use tap with no matching shutter press). Clears
     * pending either way, so a second accidental commit can never re-bank the same shot.
     */
    public String commit(String path, Double kpaAtShot, String note) {
        return commit(path, kpaAtShot, note, false);
    }

    /** commit(), saying where the image CAME FROM. The three-argument form above means
     *  "shot with the camera", which is what every existing caller meant. */
    public String commit(String path, Double kpaAtShot, String note, boolean fromGallery) {
        return commit(path, kpaAtShot, note, fromGallery, null, null);
    }

    /** commit() carrying the phone's tilt/turn at the shutter (null for imports —
     *  the angle an imported image was shot from is unknowable). */
    public String commit(String path, Double kpaAtShot, String note, boolean fromGallery,
                         Double tiltDeg, Double turnDeg) {
        return commit(path, kpaAtShot, note, fromGallery, tiltDeg, turnDeg, null);
    }

    /** commit() also carrying the ALIGN TRANSFORM the saved pixels were baked with. Null
     *  for a skipped align and for an import — in both cases there is no transform to
     *  record, and the identity would be a claim rather than a fact. */
    public String commit(String path, Double kpaAtShot, String note, boolean fromGallery,
                         Double tiltDeg, Double turnDeg, Model.EditProfile edit) {
        return commit(path, kpaAtShot, note, fromGallery, tiltDeg, turnDeg, edit, null);
    }

    /** commit() also saying whether a hold was outstanding when the capture was armed
     *  (point 19) - null when that is unknown, which is what an import is. */
    public String commit(String path, Double kpaAtShot, String note, boolean fromGallery,
                         Double tiltDeg, Double turnDeg, Model.EditProfile edit,
                         Boolean heldAtShot) {
        return commit(path, kpaAtShot, note, fromGallery, tiltDeg, turnDeg, edit, heldAtShot,
                      null);
    }

    /** commit() also carrying the standardised hold pressure when the photo was taken during
     *  a SERVED hold (the owner's rule) - null when it was not, or for an import. */
    public String commit(String path, Double kpaAtShot, String note, boolean fromGallery,
                         Double tiltDeg, Double turnDeg, Model.EditProfile edit,
                         Boolean heldAtShot, Double stdKpaAtShot) {
        return commit(path, kpaAtShot, note, fromGallery, tiltDeg, turnDeg, edit, heldAtShot,
                      stdKpaAtShot, null);
    }

    /** commit() also carrying the hold pressure when the photo was taken under any hold -
     *  null at rest or for an import (the data-integrity review, item 4). */
    public String commit(String path, Double kpaAtShot, String note, boolean fromGallery,
                         Double tiltDeg, Double turnDeg, Model.EditProfile edit,
                         Boolean heldAtShot, Double stdKpaAtShot, Double holdKpaAtShot) {
        String v = pending;
        pending = null;
        if (v == null) return null;
        paths.put(v, path);
        kpas.put(v, kpaAtShot);
        notes.put(v, note == null ? "" : note);      // per-view — never appended to the
        imported.put(v, Boolean.valueOf(fromGallery)); // other view's note (class doc)
        if (tiltDeg != null) tilts.put(v, tiltDeg);
        if (turnDeg != null) turns.put(v, turnDeg);
        if (edit != null) edits.put(v, edit);
        if (heldAtShot != null) held.put(v, heldAtShot); else held.remove(v);
        if (stdKpaAtShot != null && !fromGallery) stdKpas.put(v, stdKpaAtShot);
        else stdKpas.remove(v);
        if (holdKpaAtShot != null && !fromGallery) holdKpas.put(v, holdKpaAtShot);
        else holdKpas.remove(v);
        return v;
    }

    /** Advances the live view to `next`. Callers MUST only call this once they have
     *  actually built/shown the capture screen for `next` — never before, and never
     *  merely because a commit() succeeded (see #18). commit() itself never calls this. */
    public void confirmAdvance(String next) { view = next; }

    public boolean has(String v) { return paths.containsKey(v); }
    public boolean tookAny() {
        for (int i = 0; i < VIEWS.length; i++) if (has(VIEWS[i])) return true;
        return false;
    }
    public String pathFor(String v) { return paths.get(v); }
    public String noteFor(String v) { return notes.containsKey(v) ? notes.get(v) : ""; }
    public Double kpaFor(String v) { return kpas.get(v); }
    public Double tiltFor(String v) { return tilts.get(v); }
    public Double turnFor(String v) { return turns.get(v); }
    /** The align transform this view's saved pixels were baked with, or null. */
    public Model.EditProfile editFor(String v) { return edits.get(v); }
    /** Whether this view's photo was taken under a hold (true), at rest (false), or with
     *  that unknown (null: an import, or nothing captured). */
    public Boolean heldFor(String v) { return held.get(v); }
    /** The hold pressure when this view's photo was taken during a served hold - a
     *  standardised photo at that pressure - or null when it was not (the owner's rule). */
    public Double stdKpaFor(String v) { return stdKpas.get(v); }
    /** The hold pressure when this view's photo was taken under any hold, or null. */
    public Double holdKpaFor(String v) { return holdKpas.get(v); }

    /**
     * THE RECORD SAVED WITH A READING for view `v`, from what this buffer recorded at capture
     * time - never re-read at Save. The one place a Shot becomes a Model.Reading.Photo
     * (SessionActivity#photoFrom asks it), so CaptureFactsTest can follow a capture from its
     * facts to the JSON the model stores:
     *   - path, the per-view note, and the vacuum DELIVERED at the shutter;
     *   - where the image came from (gallery), and the phone's tilt/turn;
     *   - point 19: held or at rest, as the camera recorded it - the photo's own kind;
     *   - the owner's rule: standardised, at the served hold's pressure (std, stdKpa) - null
     *     when how it was taken is unknown, which is what an import is;
     *   - the hold's pressure for ANY held photo (holdKpa), so it is grouped by pressure;
     *   - the align transform the saved pixels were baked with - provenance, never re-applied.
     */
    public Model.Reading.Photo photoRecord(String v, long ts) {
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = pathFor(v);
        p.note = noteFor(v);
        p.kpa = kpaFor(v);
        p.ts = ts;
        p.fromGallery = fromGalleryFor(v);
        p.tiltDeg = tiltFor(v);
        p.turnDeg = turnFor(v);
        p.held = heldFor(v);
        p.stdKpa = stdKpaFor(v);
        p.std = p.held == null ? null : Boolean.valueOf(p.stdKpa != null);
        p.holdKpa = holdKpaFor(v);
        Model.EditProfile e = editFor(v);
        if (e != null) {
            p.editZoom = e.zoom; p.editRot = e.rot;
            p.editPanX = e.panX; p.editPanY = e.panY;
        }
        return p;
    }
    /** Whether the image in this slot was imported rather than shot. A slot that was never
     *  filled reads false — nothing was imported into it. */
    public boolean fromGalleryFor(String v) {
        Boolean b = imported.get(v);
        return b != null && b.booleanValue();
    }

    /** reading-<id>-<front|side>.jpg — the exact on-disk name every capture is saved
     *  under (getExternalFilesDir(Pictures)), lower-cased regardless of how `view` is
     *  capitalised internally. */
    public static String filename(String readingId, String view) {
        return "reading-" + readingId + "-" + view.toLowerCase(Locale.US) + ".jpg";
    }
}
