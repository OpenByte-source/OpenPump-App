package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure logic behind the measurement-history screen (Task 8) — period windowing, list
 * paging, and the trend's comparability verdict — kept out of SessionActivity (which owns
 * the real screen and the Canvas chart) so the desktop self-test can exercise the exact
 * arithmetic without an Activity. Mirrors proto/pump-console.html's mPer/measWindow/
 * measRows/moreRows and the comparability half of renderMeasHist, corrected per
 * .superpowers/sdd/2026-08-17-pump-beta/prototype-known-defects.md:
 *
 *   #10 — the prototype's "directly comparable" verdict scans only `std` (hold pressure)
 *   and never `sec` (hold duration), even though the stated protocol is "same pressure,
 *   SAME DURATION". {@link #comparabilityNote} scans both, via the exact equality
 *   {@link Model.Reading#comparable} already uses — not a second, independently-invented
 *   rule.
 *
 *   #23 — the prototype's History screen and Compare screen can disagree about whether the
 *   same two readings are comparable, because each hand-rolls its own check. Here, the
 *   window-level verdict is built by asking {@link Model.Reading#comparable} about every
 *   reading against a reference — so History can never say "comparable" while Compare,
 *   asked about the same pair, says otherwise; there is exactly one place that decision is
 *   made.
 *
 * A window where every reading agrees with itself on holdKpa/holdSec (including every one
 * being null — nobody held anything) is mutually CONSISTENT under
 * {@link Model.Reading#comparable}, but is deliberately NOT reported as "directly
 * comparable" here: the standardisation protocol was not followed at all, which is exactly
 * what {@link #comparabilityNote} treats as one of its three possible problems, not an
 * exemption from them.
 */
public final class Meas {
    private Meas() { }

    /* ------------------------------------------------ what a session capture files */

    /**
     * C8 - THE METHOD A SESSION CAPTURE SCREEN FILES (the baseline before a session, the
     * after-measurement after one): standardised when the hold was served, the length
     * method chosen on the screen when the reading is at rest, and otherwise - a hold
     * skipped, or ended by its limit, with pressure still up or only just vented - the Std
     * reading filed with no hold, which carries both metrics.
     *
     * ONE DECISION FOR THE SCREEN AND THE SAVE. The screens drew a Girth stepper whatever
     * the method, pre-filled; at rest every method they offer is a length method, so the
     * save kept no girth and the number typed there vanished without a word (study problem
     * 6). A screen now draws a stepper only when {@link Model.Reading#methodMeasuresGirth} /
     * {@link Model.Reading#methodMeasuresLength} of THIS answer says the save will keep it,
     * and the save files this answer - WiringCheck invariant 54 holds both to it.
     */
    public static int captureMethod(boolean standardised, boolean atRest, int chosenAtRest) {
        if (standardised) return Model.Reading.METHOD_STANDARDIZED;
        return atRest ? chosenAtRest : Model.Reading.METHOD_STANDARDIZED;
    }

    /**
     * WHETHER THE CAMERA OPENS AS A HOLD HANDS OVER TO ITS CAPTURE SCREEN - Settings' "Photo
     * during the hold" (measurement polish, item 9; the owner chose "make it work"). The
     * switch was saved and read nowhere, so it did nothing either way.
     *
     * Only for a hold that was SERVED (its count at pressure finished inside the window) and
     * is STILL HOLDING: the photo is the standardised reading's own, taken in the state its
     * numbers are about to be taken in. A skipped hold, one the limit vented, or no hold at
     * all opens nothing - the slots are still on the screen for whoever wants them.
     */
    public static boolean photoDuringHold(boolean switchOn, boolean holdServed,
                                          boolean pressureHeld) {
        return switchOn && holdServed && pressureHeld;
    }

    /* ---------------------------------------------------------- since last time */

    /**
     * WHICH TWO READINGS the Progress strip is allowed to subtract — the whole decision
     * behind "since last session", kept here so a machine can hold it rather than a
     * reading of the screen code.
     *
     * The pair is the NEWEST reading and the one immediately before it, and only if
     * {@link Model.Reading#comparable} says those two may be subtracted at all. There is
     * no scanning back for an older reading that happens to agree: "since last session"
     * names a specific comparison, and quietly swapping in a different, further-back
     * baseline to produce a number would answer a question nobody asked — while looking
     * identical on screen. When the last two are not comparable the strip says so, in the
     * words {@link Model.Reading#comparabilityReason} already uses everywhere else, and
     * prints NO number: a delta between a hard at-rest reading and a soft one is not a
     * small delta, it is not a delta.
     *
     * Returns null when there is no pair — fewer than two readings, or two that are not
     * comparable. The caller words the empty case; {@link #sinceWhy} supplies the reason.
     */
    public static Model.Reading[] sincePair(List<Model.Reading> newestFirst) {
        // POST READINGS ARE NOT ELIGIBLE, on either side. A post reading is taken with the
        // session's swelling still present, so a post-against-pre subtraction is the size
        // of the pump's effect, not of a fortnight's progress — and it would be printed
        // under the words "since last session", which is a different claim entirely. The
        // strip has always meant "my cold measurement against the cold one before it", and
        // that is exactly what preOf() leaves standing.
        List<Model.Reading> cold = preOf(newestFirst);
        if (cold.size() < 2) return null;
        Model.Reading newer = cold.get(0), older = cold.get(1);
        if (!Model.Reading.comparable(newer, older)) return null;
        return new Model.Reading[]{ newer, older };
    }

    /** Why there is no NUMBER to show, in the user's words — or "" when there is one.
     *  Built from the same two readings {@link #sincePair} looks at and the same reason
     *  text the reading editor and Compare print, so the strip can never explain a
     *  refusal differently from the rest of the app.
     *
     *  F3 FIX. This used to return "" whenever {@link #sincePair} found a pair at all,
     *  which is not the same question as "is there a number": {@link #sincePair} and
     *  {@link Model.Reading#comparable} judge HOLD CONDITIONS (a standardised pair's
     *  vacuum, an at-rest pair's hard/soft state) and can pass a pair whose two readings
     *  measured different METRICS entirely — a hard-state BPEL reading and a hard-state
     *  MSEG one both satisfy the state fallback, yet share no metric to subtract.
     *  {@link #sinceDelta} already refuses that case (both slots Double.NaN); this method
     *  now recognises the same case and returns real words instead of "", so the caller
     *  never lands in the "there is a number" branch with nothing to print. */
    /** How the strip opens when the last two readings do not pair (STUDY-19 R19): what is
     *  not done, with the real reason after it. It said "Not comparable to the previous
     *  reading.", which reads as a fault in the newest reading rather than a fact about the
     *  pair. */
    public static final String SINCE_NO_CHANGE =
        "No change is worked out against the previous reading. ";

    public static String sinceWhy(List<Model.Reading> newestFirst) {
        // Counted over the SAME cold readings sincePair is allowed to subtract, so the
        // words and the number can never disagree about how many there are to work with.
        List<Model.Reading> cold = preOf(newestFirst);
        if (cold.isEmpty())
            return "No readings logged yet — the first one becomes the baseline.";
        if (cold.size() < 2)
            return "Only one reading so far — there is nothing yet to compare it against.";
        Model.Reading[] pair = sincePair(newestFirst);
        if (pair == null)
            return SINCE_NO_CHANGE
                 + Model.Reading.comparabilityReason(cold.get(0), cold.get(1));
        boolean sharesLength = pair[0].measuredLength() && pair[1].measuredLength();
        boolean sharesGirth  = pair[0].measuredGirth()  && pair[1].measuredGirth();
        if (!sharesLength && !sharesGirth)
            return "The last two readings measured different things ("
                 + Model.Reading.methodLabel(pair[0].method) + " vs "
                 + Model.Reading.methodLabel(pair[1].method) + "), so there is no "
                 + "length-over-length or girth-over-girth change to show.";
        return "";
    }

    /**
     * {length delta, girth delta} in cm for the pair, newer minus older — or null when
     * there is no pair at all. Never invents a zero: a null here means "no number",
     * which the strip must print as words, and 0.0 is a real measured "no change".
     *
     * EACH SLOT IS ALSO GATED ON ITS OWN METRIC, independently — {@link
     * Model.Reading#comparable} (which {@link #sincePair} defers to) can call two
     * at-rest readings of DIFFERENT methods comparable via its hard/soft state fallback
     * (e.g. a BPEL and an MSEG reading are both "hard"), and that fallback is
     * deliberately kept working for legacy data (see {@link
     * Model.Reading#stateForMethod}'s own doc) — it is not this method's place to
     * second-guess sincePair's choice of PAIR. But a length delta still must not read a
     * girth-only reading's unmeasured `len`, so each slot here is Double.NaN unless BOTH
     * readings in the pair actually measured that specific metric.
     */
    public static double[] sinceDelta(List<Model.Reading> newestFirst) {
        Model.Reading[] pair = sincePair(newestFirst);
        if (pair == null) return null;
        double lenD = (pair[0].measuredLength() && pair[1].measuredLength())
            ? pair[0].len - pair[1].len : Double.NaN;
        double girD = (pair[0].measuredGirth() && pair[1].measuredGirth())
            ? pair[0].gir - pair[1].gir : Double.NaN;
        return new double[]{ lenD, girD };
    }

    /* -------------------------------------------------------------------- periods */

    /** The five period-selector buttons, in days — mirrors the prototype's data-p values.
     *  PERIOD_ALL is 0, meaning "no filter", never "a zero-day window": {@link
     *  Model.MeasLog#window} treats its `days` argument as an actual cutoff, so handing it
     *  a literal 0 would return only readings timestamped at or after `now` — i.e. almost
     *  nothing. {@link #windowFor} is what turns 0 into "everything" instead of asking
     *  MeasLog to do it, which it was never built to do. */
    public static final int PERIOD_6W  = 42;
    public static final int PERIOD_3M  = 90;
    public static final int PERIOD_6M  = 182;
    public static final int PERIOD_1Y  = 365;
    public static final int PERIOD_ALL = 0;

    /** The readings in `log` for the given period, newest-first (the same order
     *  MeasLog.all/{@link Model.MeasLog#window} already maintain). `periodDays <= 0` is
     *  "All" and returns every reading unfiltered; any positive value delegates to
     *  MeasLog.window(), whose own inclusive-boundary behaviour (a reading exactly
     *  `periodDays` old is IN the window) is unchanged here. */
    public static List<Model.Reading> windowFor(Model.MeasLog log, int periodDays, long now) {
        if (log == null) return new ArrayList<Model.Reading>();
        if (periodDays <= 0) return new ArrayList<Model.Reading>(log.all);
        return log.window(periodDays, now);
    }

    /**
     * THE MILLISECOND CUTOFF a period selection names — the exact same arithmetic
     * {@link Model.MeasLog#window} applies when IT filters readings by a period,
     * exposed here so any OTHER period-scoped aggregate, over a DIFFERENT kind of
     * record entirely, can filter by the identical boundary instead of a second copy of
     * `now - days * 86400000L` quietly drifting from this one (Stage B task 10 — the
     * TRENDS tab's HRS tile sums SESSION durations, not readings, but "this period"
     * has to mean the same window of time on both tiles of the same row).
     *
     * `periodDays <= 0` ("All") returns Long.MIN_VALUE, which every real timestamp
     * clears — the same "no filter" meaning PERIOD_ALL already carries for
     * {@link #windowFor}, just expressed as a cutoff instead of as an unfiltered copy.
     */
    public static long periodCutoff(int periodDays, long now) {
        return periodDays <= 0 ? Long.MIN_VALUE : now - (long) periodDays * 86400000L;
    }

    /**
     * {length delta, girth delta} = the newest COLD reading that actually MEASURED that
     * metric, minus the oldest one in the period that is directly COMPARABLE with it —
     * {@link #periodPair}, routed through {@link #windowFor} so "All" is handled correctly
     * instead of degenerating to a near-empty window. (C8: it used to be the oldest of any
     * method, the MeasLog#delta definition; see periodPair.)
     *
     * EACH METRIC'S PAIR IS CHOSEN INDEPENDENTLY (the fix for the bug where this used to
     * take the window's overall newest/oldest reading regardless of method): a window
     * mixing a BPSSL reading with an MSEG one must never subtract the MSEG reading's
     * unmeasured (zero-default) length from the BPSSL one's real length, or vice versa
     * for girth. {@link Model.Reading#measuredLength}/{@code measuredGirth} decide which
     * readings count for which slot.
     *
     * Double.NaN in a slot when no two cold readings in the window measured that metric
     * AND are comparable — the same "an absent value is never a fabricated number" sentinel {@link
     * Session#freshBaselineKpa} already uses for a primitive double — so a caller can
     * tell "nothing to compare" apart from a genuine zero change, which {@code w.size() <
     * 2} alone cannot: a window full of BPEL readings has plenty of readings but not one
     * girth measurement among them.
     */
    public static double[] deltaFor(Model.MeasLog log, int periodDays, long now) {
        Model.Reading[] lp = periodPair(log, periodDays, now, true);
        Model.Reading[] gp = periodPair(log, periodDays, now, false);
        return new double[]{ lp == null ? Double.NaN : lp[0].len - lp[1].len,
                             gp == null ? Double.NaN : gp[0].gir - gp[1].gir };
    }

    /**
     * C8 - THE TWO READINGS A PERIOD'S CHANGE SUBTRACTS, {newer, older}, for one metric -
     * or null when there is no such pair.
     *
     * NEWER is the newest cold reading that measured the metric with a real value - the
     * headline's own reading ({@link #newestColdMeasuring}, the same test). OLDER is the
     * oldest cold reading in the window that is directly comparable with it, by the one
     * rule {@link Model.Reading#comparable} - the rule the chart breaks its lines by
     * ({@link #drawableEdges}) and the since-last strip refuses by. It used to be the oldest
     * reading of ANY method, so "+0.3 this period" under a Std label could be a Std length
     * minus a BPEL one (study problem 7). The headline now names the method (the newer's)
     * and the date (the older's) it compares.
     *
     * OVER THE COLD READINGS ONLY. This is the headline "+X cm", and a window that ended on
     * a post reading would subtract a swollen measurement from a cold one and print the
     * difference as progress. The two series are separated in the chart for exactly this
     * reason; the number above it follows the same rule.
     *
     * A REAL VALUE, NOT JUST THE RIGHT METHOD. A standardised reading files both dimensions
     * but a person can fill in one and leave the other - and an unmeasured length is stored
     * as 0.0, not as absent. Gated on the method alone, that zero was read as a length and
     * the headline printed a delta of minus fifteen centimetres.
     */
    public static Model.Reading[] periodPair(Model.MeasLog log, int periodDays, long now,
                                             boolean lengthMetric) {
        List<Model.Reading> w = preOf(windowFor(log, periodDays, now));   // newest-first
        Model.Reading newer = null;
        int at = -1;
        for (int i = 0; i < w.size() && newer == null; i++)
            if (hasValue(w.get(i), lengthMetric)) { newer = w.get(i); at = i; }
        if (newer == null) return null;
        for (int i = w.size() - 1; i > at; i--) {
            Model.Reading r = w.get(i);
            if (hasValue(r, lengthMetric) && Model.Reading.comparable(newer, r))
                return new Model.Reading[]{ newer, r };
        }
        return null;
    }

    /** Whether `r` measured the metric AND carries a real value for it. */
    private static boolean hasValue(Model.Reading r, boolean lengthMetric) {
        return lengthMetric ? r.measuredLength() && r.len > 0 : r.measuredGirth() && r.gir > 0;
    }

    /** The newest COLD (non-post) reading in `oldestFirst` whose method actually
     *  measured `lengthMetric ? length : girth` — the fix for the headline bug where the
     *  window's newest cold reading of ANY method was read for BOTH the length and the
     *  girth headline, even when its own method never touched the other one. Null when
     *  no cold reading in the window measured that metric at all; the caller renders
     *  that as an absent headline, never a stand-in zero. */
    public static Model.Reading newestColdMeasuring(List<Model.Reading> oldestFirst,
                                                     boolean lengthMetric) {
        if (oldestFirst == null) return null;
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            Model.Reading r = oldestFirst.get(i);
            if (r.phase == Model.Reading.PHASE_POST) continue;
            // C8: a real value, the same test periodPair picks its newer end by - so the
            // figure the headline prints and the reading its change starts from are one.
            if (hasValue(r, lengthMetric)) return r;
        }
        return null;
    }

    /* --------------------------------------------------------------------- paging */

    /** Rows per page. The prototype truncated the list at a fixed 14 rows with a "Show
     *  more" button beneath that only grew the cap by 20 at a time from an unreset global —
     *  workable, but asymmetric for no reason. One uniform page size, always applied the
     *  same way, is what "pages rather than truncates" means here: every reading is
     *  reachable in a bounded, predictable number of taps, never hidden behind dead text. */
    public static final int PAGE_SIZE = 20;

    /** How many rows should be visible having loaded `pagesLoaded` pages of a list holding
     *  `totalRows` readings — never more than `totalRows` (a bug class of its own: showing
     *  past the end would either crash a list adapter or silently render blanks), and
     *  `pagesLoaded < 1` is treated as 1 (the first page is always shown, there is no
     *  "zero pages" state a fresh screen can be in). */
    public static int visibleCount(int totalRows, int pagesLoaded) {
        int pages = Math.max(1, pagesLoaded);
        return Math.min(totalRows, PAGE_SIZE * pages);
    }

    /** How many readings are still hidden beneath the "Show more" button — never negative
     *  (a page beyond the end of the list must clamp at "nothing left to show", not report
     *  a negative count of hidden rows). */
    public static int remaining(int totalRows, int pagesLoaded) {
        return Math.max(0, totalRows - visibleCount(totalRows, pagesLoaded));
    }

    /* ------------------------------------------------------------- comparability note */

    /** The result of assessing one window of readings: whether the whole window is
     *  directly comparable, and the plain-language explanation either way. `text` is empty
     *  for a window too small to say anything about (0 or 1 readings) — the caller shows
     *  its own "nothing to trend yet" copy in that case, matching renderMeasHist's early
     *  return. */
    public static final class Verdict {
        public final boolean comparable;
        public final String text;
        /** How many readings in the window were taken at rest (no hold recorded) — the ones
         *  the trend draws hollow and shaded, kept for the caller's own summary. */
        public final int atRestCount;
        /** How many distinct comparability classes are present (Model.Reading.classOf) —
         *  1 means the window is homogeneous, more than 1 means the trend must not plot
         *  them as one line. */
        public final int classCount;
        Verdict(boolean comparable, String text, int atRestCount, int classCount) {
            this.comparable = comparable; this.text = text;
            this.atRestCount = atRestCount; this.classCount = classCount;
        }
    }

    /**
     * Whether every reading in `w` is directly comparable to every other, per the SAME
     * rule {@link Model.Reading#comparable} enforces everywhere else in the app (defect
     * #23 — one source of truth, so History and Compare can never disagree about the same
     * pair). FULL PAIRWISE, because the standardised class is now judged on the observed
     * vacuum within a tolerance and tolerance is not transitive (Task 19).
     *
     * A window whose first reading is in an UNKNOWN class (a standardised reading with no
     * observed vacuum, or an at-rest reading with no recorded state) immediately fails —
     * INCLUDING when every reading shares that same unknown. Internal self-consistency
     * ("nobody recorded the thing, so at least they all lack it equally") is not the claim
     * "directly comparable" makes; that is what {@link #comparabilityNote}'s own sentences
     * say instead.
     */
    public static boolean windowDirectlyComparable(List<Model.Reading> w) {
        if (w == null || w.isEmpty()) return false;
        // The reference must itself be in a KNOWN class — a standardised reading with a
        // known observed vacuum, or an at-rest reading with a known state. An unknown
        // class fails outright: an entirely-unknown window is not "directly comparable"
        // just because its members equally lack the thing that would make them so.
        if (isUnknownClass(w.get(0))) return false;
        // FULL PAIRWISE, not a single reference: the standardised rule (observed within
        // tolerance) is NOT transitive — a can match b and b match c while a and c sit two
        // tolerances apart — so agreement with one fixed reference would over-claim. For a
        // one-dimensional pressure this is equivalent to max-minus-min <= tolerance, but
        // spelling it as every pair keeps it honest across classes and future axes.
        for (int i = 0; i < w.size(); i++)
            for (int j = i + 1; j < w.size(); j++)
                if (!Model.Reading.comparable(w.get(i), w.get(j))) return false;
        return true;
    }

    private static boolean isUnknownClass(Model.Reading r) {
        int c = Model.Reading.classOf(r);
        return c == Model.Reading.CLASS_STANDARDISED_UNKNOWN
            || c == Model.Reading.CLASS_AT_REST_UNKNOWN;
    }

    /**
     * Assesses a window and produces its comparability verdict and explanation. Three
     * independent problems are each checked and each contribute their own sentence when
     * present — unstandardised readings, differing hold pressures among the ones that WERE
     * held, and differing hold durations among them (defect #10's fix: duration is scanned
     * on exactly the same footing as pressure, not bolted on as an afterthought). All three
     * can be true of the same window at once; the prototype's own history shows that fixing
     * one branch by making it exclusive of another is how a second problem gets hidden
     * behind the first, so none of these conditions early-returns before checking the rest.
     */
    public static Verdict assess(List<Model.Reading> w) {
        if (w == null || w.size() < 2)
            return new Verdict(false, "", 0, 0);

        int atRest = 0;                 // no hold recorded (drawn hollow/shaded in the trend)
        int standardised = 0;           // Model.Reading#isStandardised
        int unknownObserved = 0;        // standardised but observed vacuum unrecorded
        int unknownState = 0;           // at rest but no state ever derived/recorded
        // Two at-rest state buckets (STATE_HARD vs STATE_SOFT) — still counted on the
        // internal classification so the "mixed conditions" branch below fires at exactly
        // the cases windowDirectlyComparable() disagrees on, but never named to the user;
        // see comparabilityReason()'s doc for why naming a specific method here would risk
        // a lying sentence (a window can hold more than two distinct at-rest methods).
        int atRestGroup1 = 0, atRestGroup2 = 0;
        // C8: the distinct at-rest METHODS present (by label, in window order) - two methods
        // are two measurements, whatever state each implies (Model.Reading#comparable).
        List<String> atRestTags = new ArrayList<String>();
        double obsMin = Double.POSITIVE_INFINITY, obsMax = Double.NEGATIVE_INFINITY;
        boolean anyKnownObserved = false;
        boolean[] classSeen = new boolean[5];
        for (int i = 0; i < w.size(); i++) {
            Model.Reading r = w.get(i);
            classSeen[Model.Reading.classOf(r)] = true;
            if (Model.Reading.isStandardised(r)) {
                standardised++;
                if (r.observedKpa == null) unknownObserved++;
                else {
                    anyKnownObserved = true;
                    double o = r.observedKpa.doubleValue();
                    if (o < obsMin) obsMin = o;
                    if (o > obsMax) obsMax = o;
                }
            } else {
                atRest++;
                String tag = Model.Reading.methodLabel(
                    Model.Reading.isAtRestMethod(r.method) ? r.method : Model.Reading.METHOD_STANDARDIZED);
                if (!atRestTags.contains(tag)) atRestTags.add(tag);
                if (Model.Reading.STATE_HARD.equals(r.state)) atRestGroup1++;
                else if (Model.Reading.STATE_SOFT.equals(r.state)) atRestGroup2++;
                // A real method carries its state in its name, so only a reading with no
                // method can be one "with no recorded state".
                else if (!Model.Reading.isAtRestMethod(r.method)) unknownState++;
            }
        }
        int classCount = 0;
        for (int c = 0; c < classSeen.length; c++) if (classSeen[c]) classCount++;

        boolean comparable = windowDirectlyComparable(w);

        if (comparable) {
            // Homogeneous and known: either all standardised at one actual vacuum, or all
            // at rest in one state. classCount == 1 by construction here.
            String txt;
            if (standardised > 0)
                txt = "All " + w.size() + " readings in this window were held at the same "
                    + "actual vacuum (" + Model.Fmt.p(obsMin) + ", within tolerance), so "
                    + "they are directly comparable.";
            else
                txt = "All " + w.size() + " readings in this window were taken at rest "
                    + "under matching conditions, so they are directly comparable.";
            return new Verdict(true, txt, atRest, classCount);
        }

        // Not comparable — each distinct problem contributes its OWN sentence, none
        // early-returning before the rest are checked (the prototype's history is that
        // making one branch exclusive of another is how a second problem gets hidden).
        StringBuilder sb = new StringBuilder();
        if (standardised > 0 && atRest > 0) {
            sb.append(standardised).append(standardised == 1 ? " reading was" : " readings were")
              .append(" standardised under a hold and ").append(atRest)
              .append(atRest == 1 ? " was" : " were").append(" taken at rest. Those are "
              + "different kinds of measurement — the trend shows them apart rather than "
              + "plotting them as one line.");
        }
        if (anyKnownObserved && (obsMax - obsMin) > Model.Reading.COMPARABLE_TOLERANCE_KPA) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("The held readings were taken at different actual vacuums (")
              .append(Model.Fmt.p(obsMin)).append(" to ").append(Model.Fmt.p(obsMax))
              .append("), so they are not directly comparable — the hold did not reach the "
              + "same pressure each time.");
        }
        if (unknownObserved > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(unknownObserved).append(" of the held readings ")
              .append(unknownObserved == 1 ? "has" : "have")
              .append(" no recorded vacuum from the pump, so the pressure ")
              .append(unknownObserved == 1 ? "it was" : "they were")
              .append(" actually taken at is unknown — unknown is not a match.");
        }
        if (atRestTags.size() > 1) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("The at-rest readings were taken ").append(atRestTags.size())
              .append(" different ways (");
            for (int i = 0; i < atRestTags.size(); i++)
                sb.append(i == 0 ? "" : ", ").append(atRestTags.get(i));
            sb.append(") — different protocols are different measurements, so they are not "
              + "directly comparable.");
        }
        // Hard against soft is the reason only between readings with no method (a single
        // tag here): with methods, the sentence above already names the difference.
        if (atRestTags.size() <= 1 && atRestGroup1 > 0 && atRestGroup2 > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("The at-rest readings were taken under different conditions, "
              + "so they are not directly comparable — that difference is condition, not "
              + "tissue change.");
        }
        if (unknownState > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(unknownState).append(" of the at-rest readings ")
              .append(unknownState == 1 ? "has" : "have")
              .append(" no recorded state, so ")
              .append(unknownState == 1 ? "it is" : "they are")
              .append(" not comparable to either.");
        }
        // Defensive fallback — should be unreachable given the conditions above are the
        // ways windowDirectlyComparable() can fail, but an empty explanation paired with
        // "not comparable" would be a worse bug than a generic sentence.
        if (sb.length() == 0)
            sb.append("These readings are not directly comparable.");
        return new Verdict(false, sb.toString(), atRest, classCount);
    }

    /**
     * ITEM 20 / STUDY-19 R18 - THE READINGS SCREEN'S CARD for a window: { headline, one
     * sentence }. What the window holds ("4 standardised, 6 at rest") and how its readings
     * are compared - never a verdict that a normal mix of the two kinds is a fault.
     */
    public static String[] windowSummary(List<Model.Reading> w) {
        int std = 0, rest = 0, unknownObs = 0;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        List<String> methods = new ArrayList<String>();
        for (int i = 0; w != null && i < w.size(); i++) {
            Model.Reading r = w.get(i);
            if (Model.Reading.isStandardised(r)) {
                std++;
                if (r.observedKpa == null) unknownObs++;
                else {
                    lo = Math.min(lo, r.observedKpa.doubleValue());
                    hi = Math.max(hi, r.observedKpa.doubleValue());
                }
            } else {
                rest++;
            }
            String tag = Model.Reading.methodLabel(
                Model.Reading.isAtRestMethod(r.method) ? r.method : Model.Reading.METHOD_STANDARDIZED);
            if (!methods.contains(tag)) methods.add(tag);
        }
        String head = std > 0 && rest > 0 ? std + " standardised, " + rest + " at rest"
                    : std > 0 ? std + " standardised"
                    : rest + " at rest" + (methods.size() == 1 ? " \u00b7 " + methods.get(0) : "");
        String cap;
        if (w != null && w.size() > 1 && windowDirectlyComparable(w))
            cap = "All taken the same way, so each compares with every other.";
        else if (std > 0 && rest > 0)
            cap = "Each kind is drawn as its own line and compared only with its own kind.";
        else if (methods.size() > 1)
            cap = "Each method is drawn as its own line and compared only with itself.";
        else if (std > 0 && hi - lo > Model.Reading.COMPARABLE_TOLERANCE_KPA)
            cap = "The holds reached " + Model.Fmt.p(lo) + " to " + Model.Fmt.p(hi)
                + ", so a reading is compared only with those held at the same vacuum.";
        else if (unknownObs > 0)
            cap = "Some holds have no reading from the pump, so those are not paired with "
                + "others.";
        else
            cap = "Each reading is compared only with others taken the same way.";
        return new String[]{ head, cap };
    }

    /**
     * STUDY-19 V2 - THE ONE READING THE TREND DRAWS APART: one on the Std line with no hold
     * recorded (a skipped hold, one the limit ended, a Std row the old at-rest sheet filed).
     * Every at-rest method's dots are filled, like the standardised ones; they are not the
     * lesser kind.
     */
    public static boolean markedApart(Model.Reading r) {
        return r != null && !Model.Reading.isAtRestMethod(r.method)
            && !Model.Reading.isStandardised(r);
    }

    /** Convenience for callers that only want the text. */
    public static String comparabilityNote(List<Model.Reading> w) { return assess(w).text; }

    /* ------------------------------------------------------------------ trend chart */

    /** Normalised (0..1) horizontal position for each reading in `oldestFirst`, by AGE —
     *  not by index — so the spacing between points reflects real elapsed time, matching
     *  the prototype's X(ago). 0 is the oldest reading in the list, 1 is `now`. A
     *  degenerate window (oldest reading timestamped at `now`) falls back to a 1-day span
     *  rather than dividing by zero. */
    public static double[] xFractionsByAge(List<Model.Reading> oldestFirst, long now) {
        int n = oldestFirst.size();
        double[] out = new double[n];
        if (n == 0) return out;
        long maxAgoMs = now - oldestFirst.get(0).ts;
        if (maxAgoMs <= 0) maxAgoMs = 86400000L;
        for (int i = 0; i < n; i++) {
            long ageMs = now - oldestFirst.get(i).ts;
            out[i] = 1.0 - ((double) ageMs / (double) maxAgoMs);
        }
        return out;
    }

    /** Normalised (0..1) vertical position for a single series (length OR girth), scaled
     *  to ITS OWN min/max with an 18% pad — mirrors the prototype's independent lo1/hi1
     *  vs lo2/hi2 scaling, which is what keeps both series readable in one frame instead of
     *  one flattening against the other's range. 0 = the padded low end, 1 = the padded
     *  high end (the caller inverts for screen Y, where 0 is the top). */
    public static double[] yFractionsFor(double[] values) {
        int n = values.length;
        double[] out = new double[n];
        if (n == 0) return out;
        double lo = values[0], hi = values[0];
        for (int i = 1; i < n; i++) { if (values[i] < lo) lo = values[i]; if (values[i] > hi) hi = values[i]; }
        double pad = Math.max(0.08, (hi - lo) * 0.18);
        lo -= pad; hi += pad;
        double range = (hi - lo) == 0 ? 1 : (hi - lo);
        for (int i = 0; i < n; i++) out[i] = (values[i] - lo) / range;
        return out;
    }

    /** For an oldest-first window, which edges of the trend line may be DRAWN: entry [i]
     *  (for i &gt;= 1) is true iff reading i-1 and reading i are directly comparable, so a
     *  drawn segment always joins two genuinely-comparable readings. Entry [0] is always
     *  false (there is no edge into the first point). The chart lifts the pen — moveTo, not
     *  lineTo — wherever this is false, so a hard-to-soft transition, a held-to-at-rest
     *  boundary, an across-tolerance vacuum step, or an unknown reading BREAKS the line
     *  rather than drawing a slope across data that is not one trend. This is the picture's
     *  half of the same rule History and Compare enforce in words: it routes through the one
     *  {@link Model.Reading#comparable}, so it can never contradict their verdict.
     *
     *  Pure and returned as data so the harness can pin it — the Canvas that consumes it
     *  cannot be executed by the test suite, and the addendum's "the trend must not plot
     *  [incomparable classes] as one line" is exactly the property to assert here. */
    public static boolean[] drawableEdges(List<Model.Reading> oldestFirst) {
        int n = oldestFirst == null ? 0 : oldestFirst.size();
        boolean[] draw = new boolean[n];
        for (int i = 1; i < n; i++)
            draw[i] = Model.Reading.comparable(oldestFirst.get(i - 1), oldestFirst.get(i));
        return draw;
    }

    /** Index ranges [start, end) of every contiguous run of unstandardised (no hold
     *  recorded, Model.Reading#isStandardised) readings in `oldestFirst` — the single scan that both decides which rows to
     *  shade and where their dashed boundaries fall, so the chart and the readings list
     *  can never disagree about which readings are "the unstandardised ones" the way two
     *  independently-written scans could drift apart. */
    public static List<int[]> unstdRuns(List<Model.Reading> oldestFirst) {
        List<int[]> out = new ArrayList<int[]>();
        int n = oldestFirst.size();
        int runStart = -1;
        for (int i = 0; i <= n; i++) {
            boolean isNull = (i < n) && !Model.Reading.isStandardised(oldestFirst.get(i));
            if (isNull) { if (runStart < 0) runStart = i; continue; }
            if (runStart >= 0) { out.add(new int[]{runStart, i}); runStart = -1; }
        }
        return out;
    }

    /* ------------------------------------------------------------ pre / post phases */

    /** The readings of `w` that belong on the PRE line — phase pre AND phase unknown.
     *  Unknown rides with pre deliberately: every reading logged before the phase axis
     *  existed was a session baseline or a standalone cold reading, which is what pre
     *  means, so keeping them together is what stops one continuous history splitting in
     *  two on the day of an update. See {@link Model.Reading#PHASE_UNKNOWN}. */
    public static List<Model.Reading> preOf(List<Model.Reading> w) {
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        if (w == null) return out;
        for (int i = 0; i < w.size(); i++)
            if (w.get(i).phase != Model.Reading.PHASE_POST) out.add(w.get(i));
        return out;
    }

    /** The readings of `w` that belong on the POST line — phase post only. Unknown is
     *  never post: a reading that never said when it was taken must not be drawn as
     *  though it had. */
    public static List<Model.Reading> postOf(List<Model.Reading> w) {
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        if (w == null) return out;
        for (int i = 0; i < w.size(); i++)
            if (w.get(i).phase == Model.Reading.PHASE_POST) out.add(w.get(i));
        return out;
    }

    /* --------------------------------------------------------- measurement methods */

    /** The readings of `w` measured with `method` — an EXACT match, never a fuzzy one.
     *  Quietly folding readings taken different ways onto one line would draw several
     *  different measurements as one trend. See {@link Model.Reading#method}. */
    public static List<Model.Reading> ofMethod(List<Model.Reading> w, int method) {
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        if (w == null) return out;
        for (int i = 0; i < w.size(); i++)
            if (w.get(i).method == method) out.add(w.get(i));
        return out;
    }

    /** The readings of `w` measured with `method` AND taken in `phase` — one plotted
     *  SERIES. This is the filter-by-(method, phase) the chart draws each line from; the
     *  metric (length vs girth) is not filtered here, it is read off the reading by the
     *  caller. */
    public static List<Model.Reading> ofMethodPhase(List<Model.Reading> w, int method, int phase) {
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        if (w == null) return out;
        for (int i = 0; i < w.size(); i++) {
            Model.Reading r = w.get(i);
            if (r.method == method && r.phase == phase) out.add(r);
        }
        return out;
    }

    /** Whether a reading measured with `method` belongs on the chart for a given metric —
     *  delegates to {@link Model.Reading#methodMeasuresLength}/{@code methodMeasuresGirth}
     *  so this decision is made in exactly one place. STANDARDIZED (method 0) belongs on
     *  BOTH metrics — it records a length and a girth held at the same pressure; a length
     *  protocol only on the length chart, a girth protocol only on the girth chart. */
    public static boolean methodOnMetric(int method, boolean lengthMetric) {
        return lengthMetric ? Model.Reading.methodMeasuresLength(method)
                            : Model.Reading.methodMeasuresGirth(method);
    }

    /**
     * Every (method, phase) SERIES that has at least one reading in `w` for the given
     * metric — each as an int[]{method, phase}. This is the enumeration the multi-select
     * checklist above the plot is built from: a series nobody has any readings in is never
     * offered, so a chip can never point at an empty chart with no way to know why.
     *
     * Ordered method-major (standardised first, then the protocols in tag order), phase
     * within (pre, post, unknown), so the list is stable across renders.
     */
    public static List<int[]> seriesWithData(List<Model.Reading> w, boolean lengthMetric) {
        List<int[]> out = new ArrayList<int[]>();
        if (w == null) return out;
        int[] methods = lengthMetric
            ? new int[]{ Model.Reading.METHOD_STANDARDIZED, Model.Reading.METHOD_BPEL,
                         Model.Reading.METHOD_BPSSL, Model.Reading.METHOD_BPSL,
                         Model.Reading.METHOD_NBPEL, Model.Reading.METHOD_NBPSL }
            : new int[]{ Model.Reading.METHOD_STANDARDIZED, Model.Reading.METHOD_MSEG,
                         Model.Reading.METHOD_MSSG };
        int[] phases = { Model.Reading.PHASE_PRE, Model.Reading.PHASE_POST,
                         Model.Reading.PHASE_UNKNOWN };
        for (int mi = 0; mi < methods.length; mi++)
            for (int pi = 0; pi < phases.length; pi++)
                if (!ofMethodPhase(w, methods[mi], phases[pi]).isEmpty())
                    out.add(new int[]{ methods[mi], phases[pi] });
        return out;
    }

    /**
     * WHETHER THE SERIES ROW'S "All" WOULD CHANGE ANYTHING (item 14): only while some
     * available series is still unticked. `ticked` counts the ticked series among the
     * `available` ones (the screen prunes the selection to what has data first). A link
     * that would change nothing is drawn greyed and does nothing, rather than looking like
     * an action and then leaving the chart exactly as it was.
     */
    public static boolean seriesAllWouldAdd(int available, int ticked) {
        return available > 0 && ticked < available;
    }

    /** "None"'s counterpart to {@link #seriesAllWouldAdd}: only while something is ticked. */
    public static boolean seriesNoneWouldClear(int ticked) {
        return ticked > 0;
    }

    /**
     * WHETHER THE CHART SHOULD FALL BACK TO {@link #defaultSeries}: only when nothing is
     * ticked after pruning AND the person did not choose that. Empty because the window or
     * the metric moved under the selection means "choose for me"; empty because they pressed
     * None or unticked the last chip is their answer, and re-ticking the defaults would undo
     * it on the very next render.
     */
    public static boolean seriesNeedsDefault(int tickedAfterPrune, boolean choseNone) {
        return tickedAfterPrune == 0 && !choseNone;
    }

    /**
     * WHICH SERIES TO SELECT BY DEFAULT when the chart is opened with no explicit choice —
     * so it draws something useful immediately instead of a blank plot. `avail` is
     * {@link #seriesWithData}'s output for the current metric; `goalMethod` is the method
     * whose goal line the caller wants on screen without hunting — the metric's OWN goal
     * method now that goals are per-method (BPSSL for length, MSEG for girth; see
     * {@link Model#goalForMethod}), where it used to be a single stored preference.
     *
     * THE RULE. Every STANDARDIZED series present (the line this app defines as directly
     * comparable), PLUS every series of the goalMethod present — so the sloped goal line has
     * a line of its own method to ride on and renders on open rather than only after the user
     * hunts for the right checkbox. If neither is present (a log made entirely of one at-rest
     * protocol whose method is not the goal's), fall back to EVERY series with data, so the
     * user still sees their whole evolution rather than nothing.
     *
     * Returns {method, phase} pairs drawn from `avail` in its own order; the caller encodes
     * them into its series keys.
     */
    public static List<int[]> defaultSeries(List<int[]> avail, int goalMethod) {
        List<int[]> out = new ArrayList<int[]>();
        if (avail == null) return out;
        for (int i = 0; i < avail.size(); i++) {
            int m = avail.get(i)[0];
            if (m == Model.Reading.METHOD_STANDARDIZED || m == goalMethod)
                out.add(avail.get(i));
        }
        if (out.isEmpty())
            for (int i = 0; i < avail.size(); i++) out.add(avail.get(i));
        return out;
    }

    /** WHERE THE GOAL LINE STARTS for `method`: the oldest non-post reading of that
     *  method in the window, or null when the window holds none. Post readings are
     *  skipped for the same reason every other "where am I" question skips them — a post
     *  reading is swollen, and anchoring a year-long target to one starts the whole line
     *  too high and quietly makes it easier. Null means there is nothing to anchor to and
     *  the caller draws no line, rather than anchoring to a reading of another method,
     *  which would state the target against a measurement it was not stated in. */
    public static Model.Reading goalAnchorOfMethod(List<Model.Reading> w, int method) {
        if (w == null) return null;
        for (int i = 0; i < w.size(); i++) {
            Model.Reading r = w.get(i);
            if (r.method == method && r.phase != Model.Reading.PHASE_POST) return r;
        }
        return null;
    }

    /**
     * THE BASELINE A GOAL'S CAP IS MEASURED FROM — the value of the FIRST (oldest) non-post
     * reading taken with the GOAL'S OWN method, or 0 when the log holds none. With one goal
     * PER METHOD each is capped against its own protocol's first reading: the BPSSL length
     * goal against the first BPSSL reading, the MSEG and MSSG girth goals against the first
     * reading of each of THOSE — which is what this method already did, now asked three
     * times instead of once.
     *
     * WHY IT IS THIS AND NOT "the latest reading". The cap is a bound on a GAIN, and a gain
     * is only a gain when both ends are measured the same way. Anchoring it to whatever was
     * logged most recently — of any method — bounds a BPSSL goal against a STANDARDIZED
     * reading, which is a different protocol on the same body: a 12 cm standardized reading
     * bounded a 19 cm BPSSL goal at 14.5 and the stepper simply refused to climb, with no
     * way for the user to see why. The goal line is already anchored per-method
     * ({@link #goalAnchorOfMethod}); this is the same anchor, asked for its value.
     *
     * 0 MEANS NO CAP. {@link Model#capGoal} returns the typed value unchanged for a
     * non-positive baseline, and that is the right answer here: with no reading of the
     * goal's method there is nothing to state a gain over, and inventing one from another
     * protocol is exactly the bug. The caps re-appear the moment a reading of that method
     * exists, which is also the moment they mean something.
     *
     * `newestFirst` is the log's own order (Model.MeasLog#all); the oldest matching reading
     * is therefore the LAST one that matches, not the first.
     */
    public static double goalBaselineCm(List<Model.Reading> newestFirst, int method,
                                        boolean isLength) {
        if (newestFirst == null) return 0;
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            Model.Reading r = newestFirst.get(i);
            if (r.method != method || r.phase == Model.Reading.PHASE_POST) continue;
            double v = isLength ? r.len : r.gir;
            return v > 0 ? v : 0;
        }
        return 0;
    }

    /** A copy of `w` in the opposite order. The log is held newest-first
     *  (Model.MeasLog#all) while every chart window is oldest-first, and two of the
     *  per-method questions here ({@link #goalLatestCm}) are stated over the log's order —
     *  so a caller holding the chart's order flips it here rather than each site growing
     *  its own backwards loop. Never mutates the input. */
    public static List<Model.Reading> reversed(List<Model.Reading> w) {
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        if (w == null) return out;
        for (int i = w.size() - 1; i >= 0; i--) out.add(w.get(i));
        return out;
    }

    /**
     * WHERE THE USER IS NOW IN ONE METHOD — the value of the NEWEST non-post reading taken
     * with `method`, or 0 when the log holds none. The mirror of {@link #goalBaselineCm}
     * (which answers "where did this method start"), and the number a goal is SEEDED from
     * and CLEARED against: "+ " on an unset goal means "a little more than I am now", and a
     * goal stepped below this stops being a goal at all.
     *
     * PER METHOD, for the same reason the baseline is: seeding a BPSSL goal from whatever
     * was logged most recently — of any protocol — starts the target at a number measured a
     * different way, which is how a 12 cm standardized reading used to seed a target that
     * belonged beside 19 cm BPSSL ones.
     *
     * POST readings are skipped: a post reading is swollen, and treating one as "where I am"
     * would seed every goal high and clear goals the user has not actually met.
     *
     * 0 means "no reading of this method". Callers read that as "there is nothing to seed
     * from" (the stepper says so and changes nothing) rather than as a reading of zero.
     *
     * `newestFirst` is the log's own order (Model.MeasLog#all), so the first match wins.
     */
    public static double goalLatestCm(List<Model.Reading> newestFirst, int method,
                                      boolean isLength) {
        if (newestFirst == null) return 0;
        for (int i = 0; i < newestFirst.size(); i++) {
            Model.Reading r = newestFirst.get(i);
            if (r.method != method || r.phase == Model.Reading.PHASE_POST) continue;
            double v = isLength ? r.len : r.gir;
            if (v > 0) return v;
        }
        return 0;
    }

    /* ------------------------------------------------- S6+ the "GOAL REACHED" moment */

    /** ONE GOAL CROSSING — the method, the goal value it is at or above, the anchor it
     *  climbed from and the reading that currently sits at or above it — everything
     *  {@link SessionActivity}'s S6+ "GOAL REACHED" card needs to state the method, the
     *  value, and how long it took ({@link Model#elapsedMonths}(anchorTs, latestTs)).
     *  Built only by {@link #reachedGoals}. */
    public static final class GoalReached {
        public final int method;
        public final double goalCm;
        public final long anchorTs, latestTs;
        GoalReached(int method, double goalCm, long anchorTs, long latestTs) {
            this.method = method; this.goalCm = goalCm;
            this.anchorTs = anchorTs; this.latestTs = latestTs;
        }
    }

    /**
     * EVERY GOAL-BEARING METHOD CURRENTLY AT OR ABOVE ITS OWN GOAL, in {@link
     * Model#GOAL_METHODS} order — what the S6+ one-time "GOAL REACHED" card is built
     * from.
     *
     * RECOMPUTED FRESH off the current log and the current goal values every time this
     * is asked, rather than caught at one specific measurement-log write call site — the
     * same shape {@link Milestones#newlyEarned} already uses for the milestone card (see
     * that method's own doc). A goal can be crossed by more than a fresh log: an edited
     * reading, or a goal LOWERED to sit at or below a value already on record, both
     * genuinely cross it, and re-deriving "is it reached" from scratch here catches
     * every path the same way, rather than only the one write this task happened to
     * hook. The caller (SessionActivity#goalReachedCard) is the one that diffs this
     * against {@link Model#goalReachedShown} and decides what is actually NEW.
     *
     * `oldestFirst` should be the WHOLE log (Model.MeasLog#all, reversed), not a
     * period-filtered window — whether a goal is reached is a fact about the log, not
     * about which date-range pill happens to be selected on Progress right now.
     *
     * The same two gates every other goal readout already applies: a goal must be SET
     * for the method ({@link Model#goalForMethod}), and the method needs at least one
     * non-post reading of its own to anchor to ({@link #goalAnchorOfMethod}) — a goal
     * with no reading of its own method has nothing to be "reached" against yet.
     */
    public static List<GoalReached> reachedGoals(List<Model.Reading> oldestFirst, Model m) {
        List<GoalReached> out = new ArrayList<GoalReached>();
        if (oldestFirst == null || m == null) return out;
        List<Model.Reading> newestFirst = reversed(oldestFirst);
        for (int i = 0; i < Model.GOAL_METHODS.length; i++) {
            int method = Model.GOAL_METHODS[i];
            Double goal = m.goalForMethod(method);
            if (goal == null) continue;
            Model.Reading anc = goalAnchorOfMethod(oldestFirst, method);
            if (anc == null) continue;
            // The LATEST non-post reading of this method: goalAnchorOfMethod's own scan
            // ("first match in the order it is given") asked over the NEWEST-first order
            // instead of the oldest-first one anc came from — the identical trick, run
            // the other way, rather than a second hand-written loop.
            Model.Reading latest = goalAnchorOfMethod(newestFirst, method);
            if (latest == null) continue;
            boolean isLen = Model.goalMethodIsLength(method);
            double latestV = isLen ? latest.len : latest.gir;
            if (latestV <= 0 || latestV < goal.doubleValue()) continue;
            out.add(new GoalReached(method, goal.doubleValue(), anc.ts, latest.ts));
        }
        return out;
    }

    /* ------------------------------------------------- S9 the volume mini chart --------- */

    /**
     * A STANDARD CYLINDRICAL VOLUME ESTIMATE, in cm3 — round6-options.html's S9 mock:
     * treat the girth reading as a CIRCUMFERENCE (radius = girth / 2*pi), then volume =
     * pi * radius^2 * length. Both inputs are already in cm (every {@link Model.Reading}
     * is stored in cm regardless of the display unit — see {@link Model.Fmt}'s own "SIZE"
     * section), so the result is a real physical volume, not a display-unit-scaled one.
     *
     * AN ESTIMATE, NAMED AS ONE. A body is not a cylinder, so this is deliberately the
     * simplest shape whose volume is fully determined by exactly the two numbers this app
     * already asks for — no new measurement, no new protocol. Pure arithmetic; negative
     * or zero inputs (nothing was actually measured, see {@link #hasBothDims}) fall
     * straight out of the formula as zero rather than being special-cased here — it is
     * the CALLER's job to decide whether a reading is volume-capable at all before it
     * ever reaches this method.
     */
    public static double estimatedVolumeCm3(double lenCm, double girCm) {
        double r = girCm / (2.0 * Math.PI);
        return Math.PI * r * r * lenCm;
    }

    /**
     * WHETHER `r` CARRIES A GENUINE MEASUREMENT OF BOTH LENGTH AND GIRTH — the one gate a
     * volume estimate needs before {@link #estimatedVolumeCm3} may honestly be asked about
     * it. {@link #buildLogReadings}'s own rule leaves "the unused metric... at zero" for
     * every single-protocol reading (a BPSSL reading's `gir` is not a measurement anybody
     * took), so estimating a volume off it would multiply by a number nobody measured — a
     * wrong number wearing an estimate's shape, not an estimate. True overwhelmingly for
     * STANDARDIZED readings taken under the hold (the only capture path that stamps both
     * numbers onto one reading by construction; older logs also hold Std readings saved
     * from the at-rest sheet, which offered a Std row per metric until item 8), but never
     * gated on `method` itself — a reading hand-edited to carry both numbers (the reading
     * editor stepped BOTH Length and Girth on any reading until item 18; it now edits only
     * what the method measured, {@link #editsLength}) is just as real as one Std save that
     * did, and older logs hold such readings.
     */
    public static boolean hasBothDims(Model.Reading r) {
        return r != null && r.len > 0 && r.gir > 0;
    }

    /**
     * ITEM 18 - WHICH NUMBERS THE READING EDITOR OFFERS: only what the reading's method
     * measured (the owner chose to hide the other, not show it locked). An at-rest length
     * method edits its length, a girth method its girth; a Std reading both, except a half
     * that was never filled (a Std row saved alone from the old at-rest sheet leaves the
     * other at 0.0), which is not a measurement to correct. If that rule would leave nothing
     * at all, the method's own metrics are offered rather than an editor with no number.
     */
    public static boolean editsLength(Model.Reading r) {
        if (!r.measuredLength()) return false;
        return r.len > 0 || !r.measuredGirth() || r.gir <= 0;
    }

    /** The girth half of {@link #editsLength}. */
    public static boolean editsGirth(Model.Reading r) {
        if (!r.measuredGirth()) return false;
        return r.gir > 0 || !r.measuredLength() || r.len <= 0;
    }

    /** Every reading in `w` {@link #hasBothDims} — the population a volume mini chart may
     *  honestly plot. Order preserved (callers pass oldest-first or newest-first alike). */
    public static List<Model.Reading> volumeCapable(List<Model.Reading> w) {
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        if (w == null) return out;
        for (int i = 0; i < w.size(); i++) if (hasBothDims(w.get(i))) out.add(w.get(i));
        return out;
    }

    /* ------------------------------------------------- S10+ user-controlled smoothing --- */

    /** The rolling-average window's reading COUNT, not a span of days — S10+'s "7-reading
     *  rolling average". A count, deliberately: two readings a week apart and two readings
     *  a year apart are both just "the next reading" to this average, so a long gap in the
     *  log never widens or narrows what the window covers. */
    public static final int SMOOTH_WINDOW = 7;

    /**
     * THE ROLLING AVERAGE OF LENGTH AND GIRTH, one entry per reading in `oldestFirst`, same
     * order — S10+'s "smoothed" chart line (and, composed with {@link #estimatedVolumeCm3}
     * downstream, the smoothed volume line and the smoothed forecast-source actual). Entry
     * i is {ts, avgLen, avgGir} averaged over the last `window` readings
     * UP TO AND INCLUDING i — readings [max(0, i-window+1) .. i] — never a future reading,
     * so the line at any point only ever reflects what was known by that date.
     *
     * FEWER THAN `window` READINGS AVAILABLE: the average is taken over however many exist
     * so far (as few as 1, at i==0, which trivially equals that one reading — there is
     * nothing before the first reading to average it WITH). EXACTLY `window`: the first
     * point whose average spans the full window is index `window - 1`. A GAP IN DATES
     * between two consecutive readings changes nothing here — this is a reading-COUNT
     * average (see {@link #SMOOTH_WINDOW}'s own doc), so it is immune to calendar gaps by
     * construction, not by a special case that has to be tested for and could regress.
     *
     * Both dimensions are always averaged, even for a reading whose OTHER dimension was
     * never measured (see {@link #hasBothDims}) — callers that only want one metric read
     * index 1 (length) or 2 (girth) off each entry and ignore the other, exactly as
     * {@link Model.Reading} itself always carries both fields whether or not both were
     * taken. A caller plotting volume must pre-filter to {@link #volumeCapable} readings
     * itself, the same gate {@link #estimatedVolumeCm3} needs on raw readings — smoothing
     * an ungated mix of length-only and girth-only readings would average real numbers
     * together with the "left at zero" placeholder {@link #buildLogReadings} warns about.
     */
    public static List<double[]> smoothed(List<Model.Reading> oldestFirst, int window) {
        List<double[]> out = new ArrayList<double[]>();
        if (oldestFirst == null || oldestFirst.isEmpty()) return out;
        int w = window > 0 ? window : 1;
        for (int i = 0; i < oldestFirst.size(); i++) {
            int start = Math.max(0, i - w + 1);
            double sumLen = 0, sumGir = 0;
            int n = 0;
            for (int j = start; j <= i; j++) {
                sumLen += oldestFirst.get(j).len;
                sumGir += oldestFirst.get(j).gir;
                n++;
            }
            out.add(new double[]{ (double) oldestFirst.get(i).ts, sumLen / n, sumGir / n });
        }
        return out;
    }

    /**
     * THE CHART'S SMOOTHED LINE, WITHIN COMPARABLE RUNS ONLY (the data-integrity review, item
     * 5). {@link #smoothed} averages across the whole series; on the Std line that averaged a
     * reading at one vacuum with readings at another, and with one that had no hold recorded,
     * while the line itself broke at those very boundaries ({@link #drawableEdges}). Here the
     * window restarts at every incomparable edge, so each run is averaged only with itself -
     * the owner's rule, readings compared only when taken the same way. Within one run it is
     * exactly {@link #smoothed}. Same shape: one {ts, avgLen, avgGir} per reading, in order.
     */
    public static List<double[]> smoothedWithinRuns(List<Model.Reading> oldestFirst, int window) {
        List<double[]> out = new ArrayList<double[]>();
        if (oldestFirst == null || oldestFirst.isEmpty()) return out;
        int w = window > 0 ? window : 1;
        boolean[] joined = drawableEdges(oldestFirst);
        int runStart = 0;
        for (int i = 0; i < oldestFirst.size(); i++) {
            if (i > 0 && !joined[i]) runStart = i;          // a new run: nothing before it counts
            int start = Math.max(runStart, i - w + 1);
            double sumLen = 0, sumGir = 0;
            int n = 0;
            for (int j = start; j <= i; j++) {
                sumLen += oldestFirst.get(j).len;
                sumGir += oldestFirst.get(j).gir;
                n++;
            }
            out.add(new double[]{ (double) oldestFirst.get(i).ts, sumLen / n, sumGir / n });
        }
        return out;
    }

    /** {@link #smoothed(List, int)} at the default {@link #SMOOTH_WINDOW} — the one window
     *  every on-screen "smoothed" line and every forecast-source lookup actually uses. */
    public static List<double[]> smoothed(List<Model.Reading> oldestFirst) {
        return smoothed(oldestFirst, SMOOTH_WINDOW);
    }

    /* ===================================================================== *
     *  THE LENGTH TIER'S SIGNALS (trainer-v3 Phase 2)                        *
     *  Derived from the reading log at evaluation time - nothing here is     *
     *  persisted, and every one of them answers "no data" with null/0/false. *
     * ===================================================================== */

    /** How many days of readings the STRAIN figure looks back over - what the tissue is
     *  doing now. One training week. */
    public static final int STRAIN_WINDOW_DAYS = 7;
    /** And the FATIGUE figure's window - long enough that one hard session cannot make the
     *  plan call somebody fatigued, short enough to notice a month going wrong. */
    public static final int FATIGUE_WINDOW_DAYS = 21;

    /**
     * THE ACUTE RESPONSE, percent: how much a session stretches the tissue, averaged over
     * same-day pre/post pairs in the last {@link #STRAIN_WINDOW_DAYS}.
     *
     * This is exactly the figure the chart caption already prints, over a shorter window -
     * routed through {@link #prePostPct(List, int)} rather than re-derived, so the number
     * the engine acts on and the number the user reads cannot disagree.
     *
     * Null when no same-day pair exists in the window, which the ladder reads as no data
     * and answers with a hold.
     */
    public static Double strainPct(Model.MeasLog log, int method, long now) {
        return prePostPct(windowFor(log, STRAIN_WINDOW_DAYS, now), method);
    }

    /**
     * SUSTAINED RESPONSIVENESS, percent: the same pre/post figure over
     * {@link #FATIGUE_WINDOW_DAYS} instead of one week.
     *
     * WHAT THIS ASSUMES, stated plainly because the spec left it open. "Fatigue" is read
     * here as the tissue no longer responding - three weeks of sessions that move it less
     * than {@link Plan#LENGTH_FATIGUE_MIN}% is a tissue that is not recovering between
     * them, whatever any single session did. The alternative readings (a next-session
     * baseline that has not returned, an acute figure compared against its own trailing
     * mean) need a pairing the log does not reliably carry, and inventing one would put a
     * number nobody measured in front of a deload decision.
     *
     * It also makes the ladder's stated ordering exact rather than merely prudent: a
     * three-week average under 2% and a one-week figure under 4% are routinely true at
     * once, which is precisely why fatigue is asked first.
     */
    public static Double fatiguePct(Model.MeasLog log, int method, long now) {
        return prePostPct(windowFor(log, FATIGUE_WINDOW_DAYS, now), method);
    }

    /**
     * HOW MANY DAYS THE STRAIN FIGURE HAS SAT UNDER `lo` - the ladder's debounce.
     *
     * Walked newest-first over same-day pre/post pairs: the count runs back from the most
     * recent pair and stops at the first day that was AT or above the window, because a
     * single good session means the run of low ones has ended. 0 when there is no pair at
     * all, which is the honest "nothing to debounce yet".
     */
    public static int strainMissDays(Model.MeasLog log, int method, double lo, long now) {
        return strainMissDays(log, method, lo, 0L, now);
    }

    /**
     * THE SAME DEBOUNCE, COUNTED ONLY FROM READINGS AFTER `sinceMs` - when the length work
     * last changed (Model.TrainerTrackState#strainSinceMs). The ladder's rule is one step
     * per window: a strain set (or a load step) is answered by a fresh week of low strain
     * measured on the NEW work, never by the week that already earned the step. Counted
     * from the whole history instead, the run of low days that asked for the first set was
     * still there the morning after it was accepted, so the next set was offered at once -
     * two to twelve sets in eight weeks on readings that never moved. 0 for `sinceMs` is
     * every reading, as before the clock existed.
     */
    public static int strainMissDays(Model.MeasLog log, int method, double lo, long sinceMs,
                                     long now) {
        if (log == null) return 0;
        List<Model.Reading> all = ofMethod(new ArrayList<Model.Reading>(log.all), method);
        List<Model.Reading> w = new ArrayList<Model.Reading>();
        for (int i = 0; i < all.size(); i++)
            if (all.get(i).ts > sinceMs) w.add(all.get(i));   // the work as it stands now
        if (w.isEmpty()) return 0;
        // Grouped by the LOCAL calendar day, the same bucketing prePostPct itself pairs by -
        // but the DAY ARITHMETIC below is done on timestamps, never on the keys. A dayKey is
        // a packed YYYYMMDD, so subtracting two of them reads eight days across a month
        // boundary as seventy-seven.
        java.util.TreeMap<Long, Long> tsOfDay = new java.util.TreeMap<Long, Long>();
        java.util.TreeMap<Long, Double> pctOfDay = new java.util.TreeMap<Long, Double>();
        for (int i = 0; i < w.size(); i++) {
            Long day = Long.valueOf(PhotoCalendar.dayKey(w.get(i).ts));
            if (pctOfDay.containsKey(day)) continue;
            List<Model.Reading> ofDay = new ArrayList<Model.Reading>();
            long newest = 0L;
            for (int j = 0; j < w.size(); j++) {
                if (PhotoCalendar.dayKey(w.get(j).ts) != day.longValue()) continue;
                ofDay.add(w.get(j));
                if (w.get(j).ts > newest) newest = w.get(j).ts;
            }
            Double pct = prePostPct(ofDay);
            if (pct == null) continue;
            pctOfDay.put(day, pct);
            tsOfDay.put(day, Long.valueOf(newest));
        }
        if (pctOfDay.isEmpty()) return 0;
        // Walk back from the newest paired day to the first that was AT or above the window:
        // a single good session means the run of low ones has ended, which is the whole
        // point of a debounce. With no good day at all, the run is the whole history.
        long since = -1L;
        for (java.util.Iterator<Long> it = pctOfDay.descendingKeySet().iterator();
                it.hasNext(); ) {
            Long day = it.next();
            if (pctOfDay.get(day).doubleValue() >= lo) {
                since = tsOfDay.get(day).longValue();
                break;
            }
        }
        if (since < 0) since = tsOfDay.get(tsOfDay.firstKey()).longValue();
        long days = (now - since) / 86400000L;
        return days < 0 ? 0 : (int) days;
    }

    /**
     * G3 - WHEN THE STRAIN FIGURE LAST HAD SOMETHING TO READ: the newest reading of a day whose
     * `method` readings pair (#prePostPct - a session's pre and post), epoch millis, or 0 when
     * no day ever paired. What the length track's no-readings fallback counts its weeks from:
     * a reading the strain rung cannot read is not a measurement it acts on.
     */
    public static long lastPairedMs(Model.MeasLog log, int method) {
        if (log == null) return 0L;
        List<Model.Reading> all = ofMethod(new ArrayList<Model.Reading>(log.all), method);
        long best = 0L;
        for (int i = 0; i < all.size(); i++) {
            Model.Reading r = all.get(i);
            if (r == null || r.ts <= best) continue;
            int day = PhotoCalendar.dayKey(r.ts);
            List<Model.Reading> ofDay = new ArrayList<Model.Reading>();
            long newest = 0L;
            for (int j = 0; j < all.size(); j++) {
                Model.Reading o = all.get(j);
                if (o == null || PhotoCalendar.dayKey(o.ts) != day) continue;
                ofDay.add(o);
                if (o.ts > newest) newest = o.ts;
            }
            if ((prePostPct(ofDay) != null || hasPreAndPost(ofDay)) && newest > best)
                best = newest;
        }
        return best;
    }

    /** t10 R-24 - a day that holds a pre AND a post of the method: the person measured a
     *  session, whether or not the pair is one the ladder reads (not like-for-like, around a
     *  girth session, in a deload). A reading left out still shows they measure, so it resets
     *  the no-readings fallback all the same. */
    private static boolean hasPreAndPost(List<Model.Reading> ofDay) {
        boolean pre = false, post = false;
        for (int i = 0; i < ofDay.size(); i++) {
            Model.Reading r = ofDay.get(i);
            if (r == null) continue;
            if (r.phase == Model.Reading.PHASE_PRE) pre = true;
            if (r.phase == Model.Reading.PHASE_POST) post = true;
        }
        return pre && post;
    }

    /* ===================================================================== *
     *  t10 OPTION D (R-40, R-24): THE ONE AFTER-SESSION READING             *
     *  The owner's length rule (1 Oct 2026) reads one figure: the after-    *
     *  reading of a length session that pulled, against that session's own *
     *  before-reading. Everything below is derived from the logs, never     *
     *  stored, and every one of them answers "no data" with null/0/false.   *
     * ===================================================================== */

    private static final long DAY_MS = 86400000L;

    /**
     * WHETHER `s` IS A SESSION THE LENGTH LADDER READS (t10 R-24, C5): a length session that
     * ran its strain block - its routine pulls (a traction stage), it was not run as expansion
     * only, it ran to the end on the real pump, outside a deload. A pre/post pair around any
     * other session - girth, expansion only, a stopped run - says nothing about the strain
     * blocks, and is left out.
     */
    public static boolean strainSession(Model m, Model.Sess s) {
        if (m == null || s == null || s.manual || s.sim || !s.completed) return false;
        if (RunShape.ranExpansionOnly(s.shape)) return false;
        if (Deload.touches(m, s.ts, s.ts + 1L)) return false;
        Model.Routine r = m.routine(s.routineId);
        return r != null && r.trainerTrack == Plan.TRACK_LENGTH && Say.isTraction(r);
    }

    /**
     * THE LENGTH LADDER'S READINGS: one per day, {post ts, percent, reduced}, oldest first,
     * taken after `sinceMs`. A reading counts only when its after-reading names its own
     * session's before-reading (Reading#pairOf) and that session is one {@link #strainSession}
     * reads; the pair must be like-for-like. A second measured pair on the same day REPLACES
     * the first - a re-measure is the newer answer, never averaged with the one it checks.
     * `reduced` is 1 when the session ran on a reduced return day (Sess#returnDay, t10 O8),
     * else 0: such a reading counts (O5), but not toward the low streak.
     */
    public static List<double[]> strainReadings(Model m, int method, long sinceMs) {
        List<double[]> out = new ArrayList<double[]>();
        if (m == null || m.measLog == null) return out;
        List<Model.Reading> all = ofMethod(new ArrayList<Model.Reading>(m.measLog.all), method);
        java.util.Map<String, Model.Reading> preById =
            new java.util.HashMap<String, Model.Reading>();
        for (int i = 0; i < all.size(); i++) {
            Model.Reading r = all.get(i);
            if (r != null && r.phase == Model.Reading.PHASE_PRE && r.id != null
                    && r.id.length() > 0)
                preById.put(r.id, r);
        }
        java.util.Map<String, Model.Sess> byBase = new java.util.HashMap<String, Model.Sess>();
        for (int i = 0; i < m.sessLog.all.size(); i++) {
            Model.Sess s = m.sessLog.all.get(i);
            if (s != null && s.afterBaseThisSession && s.afterBaseId != null
                    && s.afterBaseId.length() > 0)
                byBase.put(s.afterBaseId, s);
        }
        java.util.TreeMap<Integer, double[]> byDay = new java.util.TreeMap<Integer, double[]>();
        for (int i = 0; i < all.size(); i++) {
            Model.Reading b = all.get(i);
            if (b == null || b.phase != Model.Reading.PHASE_POST || b.ts <= sinceMs) continue;
            if (!linked(b) || Model.Reading.PAIR_NONE.equals(b.pairOf)) continue;
            Model.Reading a = preById.get(b.pairOf);
            if (a == null || !Model.Reading.comparable(a, b)) continue;
            Model.Sess sess = byBase.get(b.pairOf);
            if (!strainSession(m, sess)) continue;
            double pct = pairPct(a, b, false);
            if (Double.isNaN(pct)) continue;
            Integer day = Integer.valueOf(PhotoCalendar.dayKey(b.ts));
            double[] held = byDay.get(day);
            if (held == null || b.ts > held[0])
                byDay.put(day, new double[]{ b.ts, pct, sess.returnDay ? 1.0 : 0.0 });
        }
        out.addAll(byDay.values());
        java.util.Collections.sort(out, new ByTs());
        return out;
    }

    /** Oldest first - a named class, not a lambda, as the codebase writes them. */
    private static final class ByTs implements java.util.Comparator<double[]> {
        @Override public int compare(double[] x, double[] y) { return Double.compare(x[0], y[0]); }
    }

    /**
     * THE ONE READING THE LADDER ACTS ON (option D), percent: the newest of
     * {@link #strainReadings}, while it is at most {@link #STRAIN_WINDOW_DAYS} old - null when
     * there is none, which the ladder answers with a hold.
     *
     * t10 parity run 2, A-5: A WEEK OFF DOES NOT AGE IT. No length session runs in the deload
     * week (S11), so a reading taken just before it is still the newest word on the work when
     * the ladder next looks, the first morning back - and two over 6 % confirmed before the
     * week off then cut at the first length session back, where they had gone stale and three
     * more sessions ran at the load they had asked to come down. Only the recorded week off's
     * own days are left out (Deload#overlapMs, as the layoff counts them).
     */
    public static Double strainPct(Model m, int method, long now) {
        List<double[]> r = strainReadings(m, method, 0L);
        if (r.isEmpty()) return null;
        double[] last = r.get(r.size() - 1);
        long at = (long) last[0];
        if (at > now || now - at - Deload.overlapMs(m, at, now) > STRAIN_WINDOW_DAYS * DAY_MS)
            return null;
        return Double.valueOf(last[1]);
    }

    /**
     * THE DEBOUNCE, OPTION D: how many days since the FIRST of the run of low readings that
     * ends with the newest one - "a low reading still showing a week after the first low one"
     * - counted only over readings after `sinceMs` (the last change to the length work,
     * TrainerTrackState#strainSinceMs). 0 when the newest reading is not low, or there is none.
     *
     * t10 O8 (the owner, 1 Oct 2026): A READING FROM A REDUCED RETURN DAY IS NOT PART OF THE
     * STREAK - it neither starts, extends nor breaks it, so on its own it can never add a set
     * or bring the offer (it still counts for "this block reached 2 %", #reachedSince). The
     * streak is read over the other readings, and only while its own newest one is still
     * showing (at most {@link #STRAIN_WINDOW_DAYS} old - what #strainPct asks of the newest
     * reading of all, which is a return day's when the streak's is not).
     */
    public static int strainMissDays(Model m, int method, double lo, long sinceMs, long now) {
        List<double[]> all = strainReadings(m, method, sinceMs);
        List<double[]> r = new ArrayList<double[]>();
        for (int i = 0; i < all.size(); i++) if (all.get(i)[2] == 0.0) r.add(all.get(i));
        if (r.isEmpty() || r.get(r.size() - 1)[1] >= lo) return 0;
        long newest = (long) r.get(r.size() - 1)[0];
        if (now - newest > STRAIN_WINDOW_DAYS * DAY_MS) return 0;
        long first = (long) r.get(r.size() - 1)[0];
        for (int i = r.size() - 1; i >= 0 && r.get(i)[1] < lo; i--) first = (long) r.get(i)[0];
        long days = (now - first) / DAY_MS;
        return days < 0 ? 0 : (int) days;
    }

    /** t10 REAL-8 - when the newest of {@link #strainReadings} was taken (its after-reading),
     *  epoch millis; 0 when there is none. */
    public static long strainNewestMs(Model m, int method) {
        List<double[]> r = strainReadings(m, method, 0L);
        return r.isEmpty() ? 0L : (long) r.get(r.size() - 1)[0];
    }

    /** t10 O8 - whether the reading {@link #strainPct} reads (the newest, at most a week old)
     *  was taken on a reduced return day: then the low streak (#strainMissDays) answers rung 5
     *  alone (Plan#strainLowForAWeek). */
    public static boolean strainNewestReduced(Model m, int method, long now) {
        List<double[]> r = strainReadings(m, method, 0L);
        if (r.isEmpty()) return false;
        double[] last = r.get(r.size() - 1);
        if (last[0] > now || now - (long) last[0] > STRAIN_WINDOW_DAYS * DAY_MS) return false;
        return last[2] != 0.0;
    }

    /**
     * t10 review C-F1 - "MEASURE AGAIN" FILES THE RE-MEASURE WITH ITS SESSION. An after-reading
     * logged from the over-6 % card on the day of a length session that pulled
     * ({@link #strainSession}) is linked to that session's own before-reading, as the
     * after-session screen links one (#pairFor) - so #strainReadings' same-day replace takes
     * it as the day's answer, and the suspicious reading it checks stops being the one the
     * ladder acts on. Only an unlinked after-reading of the before-reading's own method, taken
     * after the session on the same local day; the newest such session when there are two.
     * Returns whether it linked `r` (it sets Reading#pairOf); a reading it cannot link is
     * filed as before.
     */
    public static boolean linkRemeasure(Model m, Model.Reading r) {
        if (m == null || r == null || r.phase != Model.Reading.PHASE_POST || linked(r))
            return false;
        int day = PhotoCalendar.dayKey(r.ts);
        Model.Sess best = null;
        for (int i = 0; i < m.sessLog.all.size(); i++) {
            Model.Sess s = m.sessLog.all.get(i);
            if (s == null || s.ts > r.ts || PhotoCalendar.dayKey(s.ts) != day) continue;
            if (best != null && s.ts <= best.ts) continue;
            if (!strainSession(m, s)) continue;
            String pre = pairFor(s);
            if (Model.Reading.PAIR_NONE.equals(pre)) continue;
            Model.Reading a = null;
            for (int j = 0; j < m.measLog.all.size() && a == null; j++) {
                Model.Reading c = m.measLog.all.get(j);
                if (c != null && pre.equals(c.id) && c.phase == Model.Reading.PHASE_PRE) a = c;
            }
            if (a == null || a.method != r.method) continue;
            best = s;
        }
        if (best == null) return false;
        r.pairOf = pairFor(best);
        return true;
    }

    /** Whether the newest reading, however old, is over `hi` - what holds the climb and the
     *  slow load step until a reading inside the window comes back (C12, fix f). False with
     *  no reading at all. */
    public static boolean lastStrainHigh(Model m, int method, double hi) {
        List<double[]> r = strainReadings(m, method, 0L);
        return !r.isEmpty() && r.get(r.size() - 1)[1] > hi;
    }

    /**
     * C10 - A HIGH READING CONFIRMED: the two newest readings since `sinceMs` (the last change
     * to the work) are both over `hi`. One high reading asks for another; a re-measure that
     * comes back inside the window ends it (the day's newer pair replaces the high one).
     */
    public static boolean strainHighConfirmed(Model m, int method, double hi, long sinceMs) {
        return strainHighConfirmed(m, method, hi, sinceMs, 0L);
    }

    /** ...NEVER ACROSS A WEEK OFF (N24, the owner, 2 Oct 2026): the two readings are on the same
     *  side of `weekOffEndMs`, the end of the last week off (0: none) - one before it and one
     *  after ask to measure again; a pair wholly before it still confirms (t10 A-5). */
    public static boolean strainHighConfirmed(Model m, int method, double hi, long sinceMs,
                                              long weekOffEndMs) {
        List<double[]> r = strainReadings(m, method, sinceMs);
        int n = r.size();
        if (n < 2 || r.get(n - 1)[1] <= hi || r.get(n - 2)[1] <= hi) return false;
        return weekOffEndMs <= 0L
            || (r.get(n - 2)[0] > weekOffEndMs) == (r.get(n - 1)[0] > weekOffEndMs);
    }

    /** Option D's block question: has any reading since `sinceMs` reached `pct`? */
    public static boolean reachedSince(Model m, int method, long sinceMs, double pct) {
        List<double[]> r = strainReadings(m, method, sinceMs);
        for (int i = 0; i < r.size(); i++) if (r.get(i)[1] >= pct) return true;
        return false;
    }

    /** Whether every reading since `sinceMs` is over `hi` - the run of high readings a cut
     *  continues ("cuts in a row"). False with none. */
    public static boolean allHighSince(Model m, int method, double hi, long sinceMs) {
        List<double[]> r = strainReadings(m, method, sinceMs);
        if (r.isEmpty()) return false;
        for (int i = 0; i < r.size(); i++) if (r.get(i)[1] <= hi) return false;
        return true;
    }

    /**
     * IS STRETCHED LENGTH OUTRUNNING ERECT LENGTH? - the divergence that says the tissue is
     * lengthening without filling.
     *
     * Compares the oldest and newest reading of each series inside the window. True only
     * when the stretched series has actually RISEN and the erect one has not: a fall in
     * either is a different conversation, and "flat" has to mean flat rather than "less
     * than stretched", or every ordinary month of progress would trip it.
     *
     * False whenever either series lacks two readings in the window - absence of data is
     * never a diagnosis.
     */
    public static boolean stretchOutrunningErect(Model.MeasLog log, int weeks, long now) {
        if (log == null || weeks <= 0) return false;
        List<Model.Reading> w = windowFor(log, weeks * 7, now);
        double stretched = seriesChangeCm(w, Model.Reading.METHOD_BPSSL);
        double erect = seriesChangeCm(w, Model.Reading.METHOD_BPEL);
        if (Double.isNaN(stretched) || Double.isNaN(erect)) return false;
        return stretched > FLAT_CM && erect <= FLAT_CM;
    }

    /** Under this, a series has not moved. A tenth of a centimetre is inside the noise of
     *  any hand measurement, so calling it a rise would be reading the tape, not the body. */
    public static final double FLAT_CM = 0.1;

    /** Newest minus oldest for one method inside `w`, cm - NaN when the window holds fewer
     *  than two readings of it, which is "we cannot say", never "no change". */
    public static double seriesChangeCm(List<Model.Reading> w, int method) {
        /* COLD READINGS ONLY, like every other newest-minus-oldest derivation in this file.
         * This one used ofMethod, which keeps POST readings - taken immediately after a
         * session, on swollen tissue, and a centimetre or so long. One at either end of the
         * window moves the change by more than the whole signal it feeds: this is the input
         * to stretchOutrunningErect, which proposes a girth-focus block. A post reading at
         * the new end invents divergence; one at the old end hides it. */
        List<Model.Reading> m = new ArrayList<Model.Reading>();
        for (int i = 0; i < (w == null ? 0 : w.size()); i++) {
            Model.Reading r = w.get(i);
            if (r != null && r.method == method && r.phase != Model.Reading.PHASE_POST)
                m.add(r);
        }
        if (m.size() < 2) return Double.NaN;
        // The log is newest-first; the ends of the list are the ends of the window.
        Model.Reading newest = m.get(0), oldest = m.get(m.size() - 1);
        if (newest.len <= 0 || oldest.len <= 0) return Double.NaN;
        return newest.len - oldest.len;
    }

    /** {@link #prePostPct} for ONE method — the caption under a chart that is drawing one
     *  method's pre and post readings. Pairing across methods would divide a reading taken
     *  one way by one taken another and print the difference as swelling. */
    public static Double prePostPct(List<Model.Reading> readings, int method) {
        return prePostPct(ofMethod(readings, method));
    }

    /**
     * THE SAME PAIRING, ON GIRTH.
     *
     * prePostPct measures a LENGTH percentage. Called with a girth method - which the
     * girth-focus block does, because expansion is what that block is scored on - every
     * reading's `len` is 0, `a.len <= 0` skipped every pair, and the answer was always
     * null. The block could not be scored at all: the card said "no readings were logged"
     * to somebody who had logged eight weeks of them.
     */
    public static Double prePostPctGirth(List<Model.Reading> readings, int method) {
        return prePostPct(ofMethod(readings, method), true);
    }

    /** True when `w` holds at least one reading of each phase — the condition under which
     *  the trend has two lines to draw and a legend that means something. With only one
     *  phase present there is one line, exactly as before this axis existed. */
    public static boolean hasBothPhases(List<Model.Reading> w) {
        return !preOf(w).isEmpty() && !postOf(w).isEmpty();
    }

    /** Local midnight is not knowable from a timestamp alone without a calendar, and this
     *  class is pure — so "the same day" is the same UTC day number. That is the same
     *  bucketing {@link #prePostPct} needs on both sides of a pair, and a pre/post pair
     *  from one session is minutes apart: the only way this mis-buckets a real pair is a
     *  session that straddles UTC midnight, which costs that one pair from the average and
     *  never produces a WRONG pairing across two different days.
     *
     *  PUBLIC since Task 4: a ts edit can move a pre/post-phased reading onto a
     *  DIFFERENT day, which changes which {@link #prePostPct} pairing it takes part in —
     *  a real hazard (backdating can manufacture a same-day pair from readings weeks
     *  apart, the exact thing that method's own doc says pairing-by-day exists to
     *  prevent). SaveMeasTap uses this exact rule, not a re-implementation of it, to warn
     *  when a ts commit crosses that boundary — see its own comment. */
    public static long dayOf(long ts) { return ts / 86400000L; }

    /** One calendar day two or more chart points share — see {@link #sameDayClusters}. */
    public static final class DayCluster {
        public final int dayKey;
        public final long repTs;
        public final int count;
        public DayCluster(int dayKey, long repTs, int count) {
            this.dayKey = dayKey; this.repTs = repTs; this.count = count;
        }
    }

    /**
     * TASK 5 — every calendar day (per {@link PhotoCalendar#dayKey}) that two or more of
     * `ts` land on, as one {@link DayCluster} per such day — the pure logic behind the
     * trend chart's "same sitting" spine, so a multi-protocol batch (N readings across N
     * different (method, phase) series, all sharing one instant — see
     * SessionActivity#buildSelectedSeries's own doc: a sitting IS N one-point series) is
     * recognised as ONE day, not silently missed because no single series repeats. Callers
     * pass every point from every CURRENTLY PLOTTED series at once, not one series at a
     * time, for exactly that reason.
     *
     * DELIBERATELY {@link PhotoCalendar#dayKey}, NOT {@link #dayOf}: `dayOf` is a raw UTC
     * millisecond bucket — wrong in every zone east or west of UTC and across a DST change
     * (see its own doc) — and reusing it here would draw a chart that groups same-day
     * readings one way while {@link #prePostPct}'s caption directly underneath it pairs
     * them another (a real, reachable contradiction for a user in a non-UTC zone). `dayKey`
     * is the rule every OTHER readings surface already shares — Gallery#byDay,
     * Compare#pickCaption, the calendar picker — so this reuses the one existing helper
     * rather than adding a fifth day-bucketing implementation to a codebase that already
     * ships four.
     *
     * The cluster's own `repTs` is the NEWEST timestamp in the group — the same "newest
     * wins" rule {@link PhotoCalendar#cells} and Gallery#daySummary already use, so a
     * caller placing a marker at `repTs` always lands on a point that is actually drawn,
     * without averaging (or otherwise deriving) a value from the group. A day with exactly
     * one point is omitted from the result — there is nothing to explain there.
     *
     * Pure — no Android import — so the day-bucketing itself, the part with the real
     * DST/timezone trap, is covered by test.sh, not only by reading the code. `ts` need
     * not be sorted; this sorts its own copy first — equal dayKeys are then contiguous
     * because `dayKey` is monotonic non-decreasing in `ts` (a Calendar year/month/day
     * triple never goes backwards as the clock moves forward, even across a DST jump).
     */
    public static List<DayCluster> sameDayClusters(List<Long> ts) {
        List<Long> sorted = new ArrayList<Long>(ts);
        java.util.Collections.sort(sorted);
        List<DayCluster> out = new ArrayList<DayCluster>();
        int i = 0;
        while (i < sorted.size()) {
            int key = PhotoCalendar.dayKey(sorted.get(i).longValue());
            long newestTs = sorted.get(i).longValue();
            int j = i;
            while (j < sorted.size() && PhotoCalendar.dayKey(sorted.get(j).longValue()) == key) {
                newestTs = sorted.get(j).longValue();
                j++;
            }
            if (j - i >= 2) out.add(new DayCluster(key, newestTs, j - i));
            i = j;
        }
        return out;
    }

    /**
     * HOW MUCH BIGGER THE POST READINGS ARE THAN THE PRE ONES, as a mean percentage of
     * length — the "post vs pre: +X %" caption under the trend.
     *
     * WHAT IT PAIRS. One pre and one post reading from the SAME DAY. A percentage across
     * days would be comparing a post reading to a pre reading taken under different
     * conditions weeks apart, which is a growth figure wearing a swelling figure's label.
     * Where a day holds several of either, the NEWEST of each is used: a day with two
     * sessions has two post readings, and averaging them against one pre would weight that
     * day twice for no reason anyone asked for.
     *
     * WHAT IT RETURNS. The mean of the per-day percentage differences, or NULL when there
     * is not a single same-day pair. Null means "no number" and the caller hides the line
     * entirely — a 0.0 here would read as "the pump does nothing", which is a claim, and
     * one nobody measured.
     *
     * Pre lengths of zero or less are skipped rather than divided by: a percentage of
     * nothing is not a large percentage, it is not a percentage.
     */
    public static Double prePostPct(List<Model.Reading> readings) {
        return prePostPct(readings, false);
    }

    /**
     * @param girth measure the girth change rather than the length one.
     *
     * S13 (b) (the owner's decision, 2026-09-26): A POST THAT KNOWS ITS SESSION'S PRE IS PAIRED
     * WITH IT. The newest-of-each-per-day rule below let a second measured session replace the
     * first, and paired a session with no baseline of its own with the morning's. A post
     * reading now carries the id of its own session's baseline ({@link Model.Reading#pairOf}),
     * and each such pair counts on its own - two sessions on a day are two pairs. A post
     * marked {@link Model.Reading#PAIR_NONE} (its session took no baseline) pairs with nothing,
     * and a post whose own pre is not in the list (deleted, another method, out of the window)
     * pairs with nothing either: no figure rather than a borrowed one.
     *
     * Readings with NO link - every one saved before this, and one logged on its own - keep the
     * day rule exactly, among themselves: a pre that belongs to a linked pair is not lent to
     * them. So an old history gives the figure it always gave.
     */
    public static Double prePostPct(List<Model.Reading> readings, boolean girth) {
        if (readings == null || readings.isEmpty()) return null;
        double sum = 0;
        int n = 0;
        // THE SESSION PAIRS first, by the post's own link.
        java.util.Map<String, Model.Reading> preById =
            new java.util.HashMap<String, Model.Reading>();
        for (int i = 0; i < readings.size(); i++) {
            Model.Reading r = readings.get(i);
            if (r != null && r.phase == Model.Reading.PHASE_PRE && r.id != null
                    && r.id.length() > 0)
                preById.put(r.id, r);
        }
        java.util.Set<Model.Reading> linkedPre = new java.util.HashSet<Model.Reading>();
        for (int i = 0; i < readings.size(); i++) {
            Model.Reading b = readings.get(i);
            if (b == null || b.phase != Model.Reading.PHASE_POST || !linked(b)) continue;
            Model.Reading a = preById.get(b.pairOf);
            if (a == null) continue;               // its own pre is not here: nothing to pair
            linkedPre.add(a);
            // ONLY A COMPARABLE PAIR (the data-integrity review, item 5), as for a day's.
            if (!Model.Reading.comparable(a, b)) continue;
            double p = pairPct(a, b, girth);
            if (Double.isNaN(p)) continue;
            sum += p;
            n++;
        }
        // THE DAY RULE, unchanged, for the readings with no link.
        java.util.Map<Long, Model.Reading> pre = new java.util.HashMap<Long, Model.Reading>();
        java.util.Map<Long, Model.Reading> post = new java.util.HashMap<Long, Model.Reading>();
        for (int i = 0; i < readings.size(); i++) {
            Model.Reading r = readings.get(i);
            if (r == null || linked(r) || linkedPre.contains(r)) continue;
            // LOCAL calendar day, the same one sameDayClusters and the whole photo
            // calendar use (audit A14). dayOf is raw UTC, so outside UTC this caption
            // could call a sitting unpaired that the chart directly above it had just
            // drawn as a single same-day cluster.
            Long day = Long.valueOf(PhotoCalendar.dayKey(r.ts));
            java.util.Map<Long, Model.Reading> into =
                (r.phase == Model.Reading.PHASE_POST) ? post
                : (r.phase == Model.Reading.PHASE_PRE ? pre : null);
            // Only an EXPLICIT pre may be paired. An unknown-phase reading shares the pre
            // LINE for continuity, but it never claims to be a session baseline, and
            // pairing one with a post reading would invent a before/after that was never
            // recorded as one.
            if (into == null) continue;
            Model.Reading held = into.get(day);
            if (held == null || r.ts > held.ts) into.put(day, r);
        }
        for (java.util.Iterator<Long> it = pre.keySet().iterator(); it.hasNext(); ) {
            Long day = it.next();
            Model.Reading a = pre.get(day), b = post.get(day);
            if (b == null) continue;
            // ONLY A COMPARABLE PAIR (the data-integrity review, item 5): on the Std line a
            // pre at one vacuum and a post at another, or a post with no hold recorded, are
            // not the same measurement - the owner's rule - so the day gives no figure.
            if (!Model.Reading.comparable(a, b)) continue;
            double p = pairPct(a, b, girth);
            if (Double.isNaN(p)) continue;
            sum += p;
            n++;
        }
        return n == 0 ? null : Double.valueOf(sum / n);
    }

    /**
     * S13 (b): THE LINK AN AFTER-READING IS FILED WITH - the id of the baseline its own
     * session took, from the session record the after-reading was just attached to
     * ({@link Model.Sess#afterBaseId}, set only when {@link Model.Sess#afterBaseThisSession}),
     * or {@link Model.Reading#PAIR_NONE} when the session took no baseline of its own, or
     * there is no session record at all.
     */
    public static String pairFor(Model.Sess s) {
        if (s == null || !s.afterBaseThisSession || s.afterBaseId == null
                || s.afterBaseId.length() == 0) return Model.Reading.PAIR_NONE;
        return s.afterBaseId;
    }

    /** Whether a reading carries a session link - a pre's id, or {@link
     *  Model.Reading#PAIR_NONE}. Only a post is ever given one. */
    private static boolean linked(Model.Reading r) {
        return r.pairOf != null && r.pairOf.length() > 0;
    }

    /** One comparable pair's percentage, or NaN when it gives none: a pre of nothing is not
     *  a percentage. Comparability is asked by the caller, where the pairing is. */
    private static double pairPct(Model.Reading a, Model.Reading b, boolean girth) {
        double from = girth ? a.gir : a.len;
        double to = girth ? b.gir : b.len;
        if (from <= 0 || to <= 0) return Double.NaN;
        return (to - from) / from * 100.0;
    }

    /**
     * THE ONE POST-VS-PRE PERCENTAGE A WINDOW OFFERS WHEN NO CHART SERIES SELECTION IS
     * IN PLAY — the same "first method that actually pairs" decision the trend chart's
     * own caption makes over its `selected` series list, asked instead over every
     * DISTINCT method present in `w`, in the order they first appear. Stage B task 10's
     * TRENDS-tab P/P% tile needs a post-vs-pre figure for the window as a whole, built
     * BEFORE the chart (and its own series checkboxes) exists on screen at all — a
     * window with zero or one reading never reaches SessionActivity's trendCard() — so
     * it cannot read the chart's selection state and asks this instead.
     *
     * Routed through the SAME {@link #prePostPct(List, int)} the chart's caption calls,
     * never a second pairing rule: a method with a post reading and no same-day pre one
     * (or vice versa) is skipped exactly as it is there, not counted as a zero.
     *
     * Null when no method in the window pairs at all — the caller prints "—", never an
     * invented percentage.
     */
    public static Double firstPrePostPct(List<Model.Reading> w) {
        if (w == null) return null;
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        for (int i = 0; i < w.size(); i++) {
            int m = w.get(i).method;
            if (!seen.add(Integer.valueOf(m))) continue;
            Double p = prePostPct(w, m);
            if (p != null) return p;
        }
        return null;
    }

    /* ------------------------------------------------------------------ notes search */

    /**
     * THE SESSIONS WHOSE WRITTEN RECORD MATCHES `query` — the History notes filter.
     *
     * Matches the session's NOTE and its FEEL WORD, case-insensitively, as a substring.
     * The feel word is included because "how did it feel" is written down as a number and
     * shown as a word: someone searching "rough" is searching their own record of the
     * session, and a filter that could only see free text would silently answer "no
     * sessions" for the one thing most sessions actually have recorded.
     *
     * `feelValues`/`feelWords` are the summary screen's own PARALLEL arrays — the value at
     * index i is written as the word at index i. They are passed in rather than duplicated
     * here so the filter can never search for a word the chips do not actually offer.
     * feel 0 ("never answered") is in neither array and so matches nothing, which is
     * correct: a session with no answer has nothing written on it to find.
     *
     * A blank or whitespace-only query returns EVERYTHING, because "no filter" and "a
     * filter nothing can match" must not look the same on screen.
     */
    public static List<Model.Sess> matchingNotes(List<Model.Sess> all, String query,
                                                 int[] feelValues, String[] feelWords) {
        List<Model.Sess> out = new ArrayList<Model.Sess>();
        if (all == null) return out;
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.US);
        if (q.length() == 0) { out.addAll(all); return out; }
        for (int i = 0; i < all.size(); i++) {
            Model.Sess s = all.get(i);
            String note = s.note == null ? "" : s.note.toLowerCase(java.util.Locale.US);
            if (note.contains(q)) { out.add(s); continue; }
            String feel = feelWordOf(s.feel, feelValues, feelWords);
            if (feel != null && feel.toLowerCase(java.util.Locale.US).contains(q)) out.add(s);
        }
        return out;
    }

    /** The word for a feel value, or null when it has none — feel 0 (never answered), a
     *  value not in the table, or mismatched/absent tables. Kept as its own method so the
     *  parallel-array lookup is stated once and pinned once. */
    public static String feelWordOf(int feel, int[] feelValues, String[] feelWords) {
        if (feelValues == null || feelWords == null || feel <= 0) return null;
        for (int i = 0; i < feelValues.length && i < feelWords.length; i++)
            if (feelValues[i] == feel) return feelWords[i];
        return null;
    }

    /* --------------------------------------------------------------- staged editing */

    /** Whether `staged` differs from `original` in any field SaveMeasTap commits back —
     *  the single predicate behind "Back discards, Save commits and marks edited": Save
     *  only flips `edited` true when this is true, and the editor's own change-detection
     *  is never re-derived a second way (e.g. a JSON string compare that could silently
     *  pass on a field this misses, or vice versa).
     *
     *  INVARIANT (INV-A, established here): every field this method compares MUST be
     *  committed by SaveMeasTap, and every field SaveMeasTap commits must be compared
     *  here — the two lists must never drift apart, because a compared-but-uncommitted
     *  field is exactly the bug class this predicate exists to prevent (Save reports
     *  "changed", brands the reading `edited`, and persists nothing for that field).
     *
     *  The reading editor's own UI lets the user touch length, girth, note, hold
     *  pressure/duration, the photo flag, and — Task 4 — the date/time. `observedKpa` and
     *  `state` are compared (and committed) anyway, defensively: neither is reachable
     *  through the editor today — `observedKpa` is a MEASUREMENT, not hand-editable (see
     *  renderMeasEdit()'s own comment); `state` has no override row any more, and is a
     *  pure function of `method` for every reading the app writes — but INV-A means a
     *  future change that makes either one reachable again cannot silently reopen this
     *  bug, because the compare side is already there waiting for the commit side to
     *  match it (or vice versa).
     *
     *  id/label/edited/photoFront/photoSide/photoTop are deliberately excluded — the
     *  editor never lets the user touch them directly. (Task 4's date/time control does
     *  move label and the photo objects' own `ts` alongside a `ts` edit, but as SIDE
     *  EFFECTS of the ts commit in SaveMeasTap, not as independently user-editable fields
     *  with their own change detection — id never changes at all, by design; see
     *  Model.Reading#copy()'s own doc.) Whenever one of THESE becomes independently
     *  editable, INV-A requires adding it to BOTH this comparison and SaveMeasTap's
     *  commit block in the same change, and removing it from this sentence. */
    public static boolean changed(Model.Reading original, Model.Reading staged) {
        if (original == null || staged == null) return false;
        if (original.ts != staged.ts) return true;
        if (Math.abs(original.len - staged.len) > 1e-9) return true;
        if (Math.abs(original.gir - staged.gir) > 1e-9) return true;
        String on = original.note == null ? "" : original.note;
        String sn = staged.note == null ? "" : staged.note;
        if (!on.equals(sn)) return true;
        boolean kpaEq = (original.holdKpa == null) == (staged.holdKpa == null)
                     && (original.holdKpa == null
                         || original.holdKpa.doubleValue() == staged.holdKpa.doubleValue());
        if (!kpaEq) return true;
        boolean secEq = (original.holdSec == null) == (staged.holdSec == null)
                     && (original.holdSec == null
                         || original.holdSec.intValue() == staged.holdSec.intValue());
        if (!secEq) return true;
        boolean obsEq = (original.observedKpa == null) == (staged.observedKpa == null)
                     && (original.observedKpa == null
                         || original.observedKpa.doubleValue() == staged.observedKpa.doubleValue());
        if (!obsEq) return true;
        String os = original.state == null ? Model.Reading.STATE_UNKNOWN : original.state;
        String ss = staged.state == null ? Model.Reading.STATE_UNKNOWN : staged.state;
        if (!os.equals(ss)) return true;
        return original.photo != staged.photo;
    }

    /* ================================================ the multi-measurement log entry */

    /**
     * ONE SITTING, SEVERAL METHODS. The standalone "Log a reading" door used to file exactly
     * one Reading with exactly one method, so a person who measures BPEL, NBPEL and MSEG in
     * the same five minutes — which is what anyone following a protocol actually does — had
     * to walk the whole door three times, re-photograph, and end up with three readings
     * whose timestamps disagree by minutes and whose photos are three separate captures of
     * the same body at the same moment.
     *
     * The entry is now a LIST OF ROWS, one per method, each with an empty value. The user
     * fills whichever subset they took. Save files one Reading PER FILLED METHOD, all
     * sharing the same timestamp and the same photos.
     *
     * WHY ROWS AND NOT A MULTI-SELECT PICKER: an empty row is a question the user can
     * ignore, and a filled one is an answer. A picker would ask "which methods?" and then
     * ask again "what were the numbers?", which is the same information in two passes.
     */
    public static final class LogRow {
        /** The Model.Reading method this row files under. */
        public final int method;
        /** Which metric the row's value IS — false = length, true = girth. Every at-rest
         *  method measures exactly one of the two, so each appears once. */
        public final boolean girth;
        /** What the row is called on screen. */
        public final String label;

        LogRow(int method, boolean girth, String label) {
            this.method = method; this.girth = girth; this.label = label;
        }
    }

    /**
     * The rows the entry offers, in order: the five length protocols, then the two girth
     * protocols. One row, one method, one reading.
     *
     * STANDARDIZED IS NOT HERE (measurement polish, item 8). It used to be, as two rows (its
     * length half and its girth half), so the at-rest sheet offered "Std" under both
     * headings. Std means "measured while the pump holds a set pressure", and a row on this
     * list is by construction a reading taken at rest: one filed that way carried the Std
     * tag with no hold behind it, joined the Std line on the chart and was compared with
     * nothing. The session screens already refused it for that reason (MeasureScreens'
     * atRestMethodRow). A standardised reading now comes from one place only - choosing
     * "Standardised" on the log door, which runs the hold and stamps the tag itself.
     */
    public static final LogRow[] LOG_ROWS = {
        new LogRow(Model.Reading.METHOD_BPEL,  false, "BPEL"),
        new LogRow(Model.Reading.METHOD_BPSSL, false, "BPSSL"),
        new LogRow(Model.Reading.METHOD_BPSL,  false, "BPSL"),
        new LogRow(Model.Reading.METHOD_NBPEL, false, "NBPEL"),
        new LogRow(Model.Reading.METHOD_NBPSL, false, "NBPSL"),
        new LogRow(Model.Reading.METHOD_MSEG,  true,  "MSEG"),
        new LogRow(Model.Reading.METHOD_MSSG,  true,  "MSSG"),
    };

    /** How many rows carry a value — what the Save button needs to know before it decides
     *  there is anything to file at all. */
    public static int filledLogRows(Double[] values) {
        int n = 0;
        if (values != null)
            for (int i = 0; i < values.length && i < LOG_ROWS.length; i++)
                if (values[i] != null) n++;
        return n;
    }

    /**
     * BUILDS THE READINGS ONE SAVE FILES. `values` is parallel to {@link #LOG_ROWS}; a null
     * entry is an unanswered row and contributes nothing.
     *
     * ONE READING PER FILLED ROW, and every row is its own method. (There used to be one
     * exception: two Std rows, the length and girth halves of one method, folded into a
     * single Std reading. Std left the list - see {@link #LOG_ROWS} - and the fold went
     * with it.)
     *
     * WHAT THEY SHARE: the timestamp (`ts`, passed in once — read at the tap, never
     * re-read per reading, or three readings taken in one sitting would land milliseconds
     * apart and sort as three separate moments), the label, the note and the phase. What
     * they do NOT share is the id: `idBase` is used verbatim for the FIRST reading — so the
     * photos already written to disk as reading-&lt;idBase&gt;-&lt;view&gt;.jpg belong to a
     * reading that actually carries that id — and idBase + "-m" + method for the rest, so
     * the log never holds two records under one id.
     *
     * THE UNUSED METRIC IS LEFT AT ZERO. A BPEL reading records a length; its `gir` is not a
     * measurement anybody took. CORRECTION (Stage G, Task 1b): this comment used to claim
     * "every reader of a girth value filters by method first, so nothing plots it" — that
     * was false, and being believed as if it were a structural guarantee is what let the
     * write side (a session baseline's unconditional Girth stepper, the after-screen's
     * ungated seed/delta) fabricate and export values from this exact unmeasured zero. The
     * truth: {@link Model.Reading#measuredLength}/{@link Model.Reading#measuredGirth} (and
     * the method-aware helpers built on them here, {@link #methodOnMetric}, {@link
     * #seriesWithData}) are a check a caller must actively make — `len`/`gir` themselves
     * carry no such protection, and any code that reads them without checking the
     * predicate first will read the unmeasured zero as a real measurement. This method
     * DOES check it, which is why it is safe; that is a property of this one reader, not
     * of the field. Writing the OTHER row's number in there instead would be worse: it
     * would look like a measurement.
     *
     * Order out matches LOG_ROWS order, which is the order the rows were shown in.
     */
    public static List<Model.Reading> buildLogReadings(Double[] values, long ts, String label,
                                                       String idBase, String note, int phase) {
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        if (values == null) return out;
        for (int i = 0; i < LOG_ROWS.length && i < values.length; i++) {
            if (values[i] == null) continue;
            LogRow row = LOG_ROWS[i];
            Model.Reading r = new Model.Reading();
            r.id = out.isEmpty() ? idBase : idBase + "-m" + row.method;
            r.ts = ts;
            r.label = label;
            r.note = note == null ? "" : note;
            r.phase = phase;
            r.method = row.method;
            // AT REST, every one of them: this door files no hold. The state is derived
            // from the method exactly as the single-method path derived it, so a
            // multi-method save and a single-method save produce identical records.
            r.holdKpa = null; r.holdSec = null; r.observedKpa = null;
            r.state = Model.Reading.stateForMethod(row.method);
            r.edited = false;
            out.add(r);
            if (row.girth) r.gir = values[i].doubleValue();
            else           r.len = values[i].doubleValue();
        }
        return out;
    }
}
