package org.openpump;

/**
 * THE ANSWERS A SCREEN ASKS OF THE BUILDER, REMEMBERED - so a redraw with nothing changed builds
 * nothing.
 *
 * WHY. Three questions the Trainer tab asks on every draw are answered by building a routine in
 * a scratch copy of the model: how many holds the offered routine runs (RxBuild#holdsRun, the
 * card and the row), whether a work shape acts at today's prescription (RxBuild#workShapeActs,
 * the Program picker's label) and whether the saved routine is still the plan's
 * (SavedMint#edited, the row's "edited" and whether the track speaks). Each copy is the whole
 * library read back, and on a phone the tab froze for about 650 ms on every visit, most of it
 * spent building the same answers again.
 *
 * KEYED BY EVERYTHING THE BUILD READS, NEVER BY TIME ALONE. The key is the question's own
 * inputs (the prescription, the day, the signature, the saved routine's print) plus the model's
 * {@link #stamp}: which model it is, how many times it has been saved ({@link Model#changed},
 * called by every save), and - so an edit not yet saved cannot be served a stale answer - the
 * settings the builder reads, the day's cut and speed, the month, the rack and the load, read
 * fresh on every ask. A change to any of them is a different key, so the next ask builds again;
 * nothing is ever invalidated by hand, and a stale answer would need a build input that is in
 * none of these and changed without a save.
 *
 * Bounded (least recently used), because keys from past days and past settings are never asked
 * again. One thread asks (the UI thread owns the model); synchronized all the same.
 */
public final class BuildCache {

    private BuildCache() { }

    private static final int MAX = 128;

    private static final java.util.LinkedHashMap<String, Object> MAP =
        new java.util.LinkedHashMap<String, Object>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Object> e) {
                return size() > MAX;
            }
        };

    /** Builds made because the answer was not remembered - what a test counts to know that a
     *  second draw with nothing changed built nothing. */
    private static long builds;

    public static synchronized long builds() { return builds; }

    static synchronized Object get(String key) { return MAP.get(key); }

    /** Remembers `value` for `key`, and counts the build that made it. */
    static synchronized void put(String key, Object value) {
        builds++;
        MAP.put(key, value);
    }

    /** Forgets everything (tests). */
    public static synchronized void clear() { MAP.clear(); }

    /**
     * THE MODEL'S SIDE OF EVERY KEY for a build of `track` at `level`, asked at `nowMs`: the model
     * itself and its save count, then every setting and day state a build reads, read now.
     */
    static String stamp(Model m, int track, int level, long nowMs) {
        StringBuilder b = new StringBuilder();
        b.append(m.instanceId).append('#').append(m.changeGen)
         .append('|').append(nowMs / 3600000L)                       // the hour, belt and braces
         .append('|').append(m.ceilKpa)
         .append('|').append(m.mintShapeTag(track, level, nowMs))    // shape, rack, taper step
         .append('|').append(m.reductionKpa(nowMs)).append('|').append(m.gentleNow(nowMs))
         .append('|').append(TrainerTab.monthIndexNow(m, nowMs))
         .append('|').append(m.rxDropKpa).append('|').append(m.rxRestSec)
         .append('|').append(m.rxRestSecTrad).append('|').append(m.rxSplitWarmBoth)
         .append('|').append(m.rxHoldSec).append('|').append(m.rxSetsPerBlock)
         .append('|').append(m.rxFatigueHoldSec)
         .append('|').append(m.trainerGirthHybrid).append('|').append(m.trainerGirthStyle)
         .append('|').append(m.trainerLength.loadLb).append('|').append(m.trainerLength.strainSets)
         .append('|').append(m.trainerLength.inGirthFocus(nowMs))
         .append('|').append(m.girthForTraction()).append(':').append(m.lengthBoreCm())
         .append('|').append(m.routines.size()).append('|').append(m.sets.size())
         // 0.10 - the person's scale and the hard limits every build is held to.
         .append('|').append(m.trainerGirth.offsetKpa).append('|').append(m.trainerLength.offsetKpa)
         .append('|').append(m.rxWorkMaxKpa).append(':').append(m.rxLengthMaxKpa)
         .append('|').append(m.rxNewToPumping)
         // 0.10 - the ramp settings and the gentle warm-up, which a build reads whatever the
         // Program says today (a picker asks about a shape the model is not in).
         .append('|').append(m.rampStartPct).append(':').append(m.rampShortSteps)
         .append(':').append(m.rampStepHg).append(':').append(m.rampLighterDays)
         .append(':').append(m.rampCountClimb).append(':').append(m.legacyRamp)
         .append(':').append(m.legacyT10)
         .append('|').append(m.marksEasily).append(':').append(m.gentleWarmStartKpa)
         .append(':').append(m.gentleWarmSpeedPct).append(':').append(m.gentleWarmStepHg)
         .append(':').append(m.rxWarmMin)
         // t10 R-07: when girth follows length, what leads it in.
         .append('|').append(m.girthAfterLength);
        Model.Program p = m.programFor(track);
        if (p != null) b.append('|').append(p.warm).append(p.work).append(p.pressure)
                        .append(p.rest).append(p.fatigue);
        return b.toString();
    }

    /** A prescription, every field. */
    static String rx(Mint.Rx rx) {
        if (rx == null) return "-";
        return rx.track + ":" + rx.level + ":" + rx.sets + ":" + rx.holdSec + ":" + rx.restSec
            + ":" + rx.pressureKpa + ":" + rx.fatigue + ":" + rx.netTargetMin + ":" + rx.powerPct
            + ":" + rx.offKpa;
    }

    /** A build's day, every field (its instant to the hour). */
    static String day(Model m, RxBuild.Day d) {
        return (d.atMs / 3600000L) + ":" + d.cutKpa + ":" + d.gentle + ":" + d.girthFocus(m)
            + ":" + d.ownKpa + ":" + d.ownSets + ":" + TrainerTab.monthIndexNow(m, d.atMs)
            + ":" + d.bothTracks + ":" + d.girthAfterLength + ":" + d.skipWarm;
    }
}
