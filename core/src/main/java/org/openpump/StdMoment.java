package org.openpump;

/**
 * THE VACUUM IS RECORDED AT THE STANDARDISED MOMENT (0.10, the owner's call).
 *
 * THE RULE. A standardised reading records the vacuum the pump REPORTED at the END OF THE
 * STANDARDISATION COUNT - the tick on which the dwell at pressure is served and the two
 * minutes open (SessionActivity's StdTick). That is the moment the protocol standardises: the
 * cuff has been at the hold pressure for the whole count. The vacuum at Save is kept ONLY as
 * the fallback, when no fresh sample existed at the count's end. Comparability
 * (Model.Reading#comparable, within COMPARABLE_TOLERANCE_KPA) is therefore judged on the
 * count-end value.
 *
 * WHY. The reading used to take the vacuum at Save. The pump pulls once and then coasts in a
 * long hold, and Save can come up to two minutes after the count: two readings standardised
 * at the same pressure could be filed more than 1 kPa apart from drift alone, and were then
 * not compared - though the protocol had put both at the same pressure.
 *
 * THE FRESHNESS WINDOWS ARE UNCHANGED. The reading's value is taken through the app's one
 * freshness rule for readings (Session#freshBaselineKpa, TELEMETRY_FRESH_MS, the wall clock);
 * the photo's through the live readout's (PhotoTruth#shutterKpa, LINK_TIMEOUT_MS, the hold
 * clock). Each is sampled at the count's end by its own rule; a sample that was not fresh
 * then is null, never a stale number, and the value at Save / the shutter is used instead.
 *
 * PHOTOS. A photo taken during the served hold (the two minutes open - HoldWindow#OPEN)
 * records the count-end vacuum too, by the same rule; any other photo records the vacuum at
 * its shutter, as before.
 *
 * MIGRATION. Readings saved before this carry no {@link Model.Reading#observedAt}: their
 * stored value is kept exactly as it is (it was taken at Save), and it is compared as before.
 *
 * Pure, so StdMomentTest holds it; WiringCheck invariant 223 holds the Activity to it.
 */
public final class StdMoment {
    private StdMoment() { }

    /** {@link Model.Reading#observedAt}: taken at the end of the standardisation count. */
    public static final String AT_COUNT_END = "count";
    /** {@link Model.Reading#observedAt}: taken at Save - no fresh sample at the count's end. */
    public static final String AT_SAVE = "save";

    /** The vacuum a standardised reading records: the count-end sample, else the one at
     *  Save, else null (unknown - never the commanded setpoint). */
    public static Double readingKpa(Double atCountEnd, Double atSave) {
        return atCountEnd != null ? atCountEnd : atSave;
    }

    /** Which of the two {@link #readingKpa} took: AT_COUNT_END, AT_SAVE, or null when
     *  neither existed. */
    public static String readingKpaAt(Double atCountEnd, Double atSave) {
        return atCountEnd != null ? AT_COUNT_END : atSave != null ? AT_SAVE : null;
    }

    /** The vacuum a photo records: the count-end sample when it is taken inside a served
     *  hold's two minutes (`holdState` is HoldWindow's verdict at the shutter) and one
     *  existed; otherwise the vacuum at its shutter. */
    public static Double photoKpa(int holdState, Double atCountEnd, Double atShutter) {
        return HoldWindow.standardised(holdState) && atCountEnd != null ? atCountEnd : atShutter;
    }
}
