package org.openpump;

/**
 * THE FACTS OF ONE CAPTURE, TAKEN ONCE, BEFORE ANYTHING IS RELEASED (the emulator pass,
 * app-polish c81dcae).
 *
 * A photo taken under a hold never stored the hold's pressure: the camera released the
 * pending capture - which clears the commanded pressure - and only then read it for the
 * commit, so "hkpa" was always empty and held photos at different pressures shared one
 * group, the pairing 9020d24 set out to prevent. The camera now takes everything the commit
 * needs as this one immutable snapshot first, releases, and commits from the snapshot alone
 * (WiringCheck invariant 125) - so no release can empty a fact on its way to the Shot.
 *
 * What the commit derives from the facts is decided here, once:
 *   - held: a hold was commanded when the capture was armed; unknown for an import, which
 *     has no shutter;
 *   - standardised: the review label's own verdict (PhotoTruth#TAKEN_STANDARDISED - the
 *     count finished, inside its two minutes), at the hold's pressure (the owner's rule);
 *   - the hold's pressure: for ANY held photo, served or not (the data-integrity review,
 *     item 4), so it is grouped by pressure;
 *   - the angle: never for an import.
 */
public final class CaptureFacts {
    /** The image came out of the phone's gallery, not the shutter. */
    public final boolean fromGallery;
    /** The pressure the hold was COMMANDED to when the capture was armed; null at rest. */
    public final Double heldKpa;
    /** The vacuum the pump DELIVERED at the shutter (the live readout's); null unknown. */
    public final Double observedKpa;
    /** PhotoTruth#takenState when the capture was armed. */
    public final int taken;
    /** The phone's tilt and turn at the shutter; null when not known. */
    public final Double tiltDeg, turnDeg;
    /** The note typed for this view. */
    public final String note;

    public CaptureFacts(boolean fromGallery, Double heldKpa, Double observedKpa, int taken,
                        Double tiltDeg, Double turnDeg, String note) {
        this.fromGallery = fromGallery;
        this.heldKpa = heldKpa;
        this.observedKpa = observedKpa;
        this.taken = taken;
        this.tiltDeg = tiltDeg;
        this.turnDeg = turnDeg;
        this.note = note == null ? "" : note;
    }

    /** Commits the capture at `path` (with the align transform `edit`, or null) to `shot`,
     *  against the view it was armed for - from these facts alone. Returns the view
     *  committed, or null when nothing was armed. */
    public String commitTo(Shot shot, String path, Model.EditProfile edit) {
        Boolean held = fromGallery ? null : Boolean.valueOf(heldKpa != null);
        Double std = !fromGallery && taken == PhotoTruth.TAKEN_STANDARDISED ? heldKpa : null;
        Double hold = fromGallery ? null : heldKpa;
        return shot.commit(path, observedKpa, note, fromGallery,
            fromGallery ? null : tiltDeg, fromGallery ? null : turnDeg,
            edit, held, std, hold);
    }
}
