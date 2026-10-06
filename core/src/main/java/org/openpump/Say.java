package org.openpump;

import java.util.Locale;

/**
 * THE WORDS AND FIGURES THE SCREENS PRINT, WITH NO SCREEN ATTACHED.
 *
 * Every method here was a private static helper inside SessionActivity - which carries
 * `import android` and is therefore invisible to test.sh, the desktop harness that
 * auto-discovers every source file WITHOUT such an import. Half this codebase lives behind
 * that line, and this session found defect after defect in exactly this class of code: a
 * readout that named a unit it had not converted, a caption that counted a different thing
 * from the bar above it, a sign printed beside a word that already carried the direction.
 * Those are pure functions of their arguments and could always have been asserted; they
 * simply had no way to be reached.
 *
 * Nothing here decides anything. These are formatters and small clamps: given a number or
 * a code, what does the screen say. The rule for what belongs is that rule exactly - a
 * method that needs a Context, a View or the model's live state stays where it is.
 */
final class Say {
    private Say() {}


    /** A gate figure: whole where it is whole (months), one decimal where it is not
     *  (pressure). A trailing ".0" on "12 months" reads as precision nobody claimed. */
    static String gateNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v)
             : String.format(Locale.US, "%.1f", Double.valueOf(v));
    }

    static String fmtMin(double min) {
        return String.format(Locale.US, "%.1f", min);
    }

    static String tagLabel(int tag) {
        switch (tag) {
            case Plan.TAG_INFERRED: return "· a rule the plan infers";
            case Plan.TAG_ADAPTED:  return "· a rule the plan adapts";
            case Plan.TAG_DERIVED:  return "· a value the plan works out";
            default: return null;   // TAG_SOURCE — stated in the guide, no flag
        }
    }

    /** A pressure DIFFERENCE in inches of mercury - "4 hg", never "-4.0 inHg". Fmt.p is
     *  for absolute pressures and signs them for vacuum; a reduction is a distance, and
     *  printing it as a negative pressure would read as the pressure itself. */
    static String hgUnder(double hg) {
        return String.format(java.util.Locale.US,
            hg == Math.rint(hg) ? "%.0f hg" : "%.1f hg", Double.valueOf(hg));
    }

    static String capitalise(String s) {
        if (s == null || s.length() == 0) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * ONE SENTENCE, ONCE (polish SYS-6). Trimmed, the first letter a capital, and a full stop
     * added only when the text has no closing punctuation of its own (. ! ? : or …).
     *
     * Cards built their sentences as `reason + "."`, and the reasons already ended in a full
     * stop ("…carries either way..") or started in lower case ("month 12 and your
     * sessions…"). A doubled stop left by such a join (but never an ellipsis "...") is
     * folded back to one. A closing quote or bracket after the punctuation counts as closed.
     * Null or blank gives "".
     */
    static String sentence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() == 0) return "";
        t = capitalise(t);
        if (t.endsWith("..") && !t.endsWith("...")) t = t.substring(0, t.length() - 1);
        int end = t.length() - 1;
        while (end > 0 && "\"'\u201d\u2019)]".indexOf(t.charAt(end)) >= 0) end--;
        char last = t.charAt(end);
        if (".!?:\u2026".indexOf(last) >= 0) return t;
        return t + ".";
    }

    /**
     * "a" or "an" before a number as it is SAID: "an 8-week block", "an 11-day gap",
     * "a 12-week block" (polish SYS-6, TR-26). Eight, eleven, eighteen and every number said
     * starting "eight…" (80-89, 800-899, 8,000…) take "an"; so do eleven and eighteen
     * thousand / million.
     */
    static String aOrAn(long n) {
        long v = Math.abs(n);
        String d = String.valueOf(v);
        if (d.charAt(0) == '8') return "an";
        // 11 and 18 - and the same two said before "thousand" or "million".
        int len = d.length();
        if ((len == 2 || len == 5 || len == 8)
                && (d.startsWith("11") || d.startsWith("18"))) return "an";
        return "a";
    }

    /** "a"/"an" before a word: a vowel letter or a leading digit said with a vowel. Plain
     *  spelling, no exceptions list: the app's own words ("hour", "unit") are not put
     *  through it. */
    static String aOrAn(String word) {
        if (word == null || word.trim().length() == 0) return "a";
        String w = word.trim();
        char c = w.charAt(0);
        if (Character.isDigit(c)) {
            int i = 0;
            while (i < w.length() && Character.isDigit(w.charAt(i))) i++;
            return aOrAn(Long.parseLong(w.substring(0, Math.min(i, 18))));
        }
        return "aeiouAEIOU".indexOf(c) >= 0 ? "an" : "a";
    }

    /**
     * A ROUTINE'S TITLE ON A CARD (polish TD-4 / LB-4): "Girth · Level 3", not
     * "Trainer · Girth L3 · 10×2min @ −8.9 inHg", which wraps at title size and says the
     * shape the line under it already says (that line is Model.Fmt#shape).
     *
     * DISPLAY ONLY: the stored name is never changed - Mint#isMintedName reads it. A routine
     * the user named is shown exactly as named. For a minted name the track and level are
     * spelled out and whatever else the mint appended to tell two routines apart ("part 1 of
     * 2", "girth focus") is kept, as #mintTitle keeps it; the shape is dropped.
     */
    static String routineDisplayTitle(Model.Routine r) {
        return r == null ? "" : routineDisplayTitle(r.name);
    }

    static String routineDisplayTitle(String name) {
        if (name == null) return "";
        if (!Mint.isMintedName(name)) return name;
        String[] parts = mintTitle(name).trim().split("\\s+\u00b7\\s+");
        if (parts.length < 2) return name;
        StringBuilder b = new StringBuilder(trackLevelWords(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            String p = parts[i];
            // A traction mint's "traction @ 30 lb" is its shape - the line under says it.
            if (p.startsWith("traction @")) continue;
            b.append(" \u00b7 ").append(p);
        }
        return b.toString();
    }

    /** "Girth L3" → "Girth · Level 3"; "Girth·trad L2" → "Traditional girth · Level 2". */
    private static String trackLevelWords(String part) {
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("(.+?)\\s+L(\\d)").matcher(part);
        if (!m.matches()) return part;
        String track = m.group(1);
        if ("Girth\u00b7trad".equals(track)) track = "Traditional girth";
        return track + " \u00b7 Level " + m.group(2);
    }

    /** The snack after a value row changes (polish SYS-3): "Long training days: Alternate
     *  on my days". The option is said as the row shows it. */
    static String choiceChanged(String row, String option) {
        return (row == null ? "" : row.trim()) + ": " + (option == null ? "" : option.trim());
    }

    /**
     * A DESTRUCTIVE BUTTON'S WORDS (polish SYS-12): "…" at the end exactly when the tap asks
     * before it acts ("Erase all data…"), never doubled, and never on one that acts at once.
     */
    static String dangerLabel(String label, boolean confirms) {
        String t = label == null ? "" : label.trim();
        boolean has = t.endsWith("\u2026") || t.endsWith("...");
        if (confirms) return has ? t : t + "\u2026";
        if (t.endsWith("\u2026")) return t.substring(0, t.length() - 1);
        if (t.endsWith("...")) return t.substring(0, t.length() - 3);
        return t;
    }

    /** A decision's status as the history and its filters say it (polish TR-7): "Accepted",
     *  "Not now" (the filter's own word for an ignored card), or "Waiting" while it is still
     *  asked. */
    static String decisionStateLabel(int st) {
        switch (st) {
            case Model.TrainerDecision.STATE_ACCEPTED: return "Accepted";
            case Model.TrainerDecision.STATE_IGNORED: return "Not now";
            default: return "Waiting";
        }
    }

    /** The decision popup's answer line (polish TR-8): "Your answer: not yet" while it waits. */
    static String decisionAnswerLine(int st) {
        return "Your answer: " + (st == Model.TrainerDecision.STATE_ACCEPTED
            || st == Model.TrainerDecision.STATE_IGNORED ? decisionStateLabel(st) : "not yet");
    }

    static String actionLabel(int action) {
        switch (action) {
            case Plan.ACTION_ADD_VOLUME: return "add volume";
            case Plan.ACTION_RAISE_PRESSURE: return "raise pressure";
            case Plan.ACTION_DELOAD: return "deload";
            case Plan.ACTION_STEP_BACK: return "step back";
            case Plan.ACTION_CEILING_DEADLOCK: return "ceiling deadlock";
            case Plan.ACTION_FEEDER_SUGGEST: return "feeder";
            case Plan.ACTION_FEEDER_PAUSED: return "feeder paused";
            case Plan.ACTION_PAUSE_VOLUME: return "pause volume";
            case Plan.ACTION_REDUCE_VOLUME: return "reduce volume";
            case Plan.ACTION_LEVEL_UP: return "level up";
            case Plan.ACTION_RAISE_LOAD: return "raise load";
            case Plan.ACTION_GIRTH_FOCUS: return "girth focus";
            case Plan.ACTION_REMEASURE: return "re-measure";
            case Plan.ACTION_OFFER_BREAK: return "a week off or a block";   // t10 R-23, R-40
            default: return "hold";
        }
    }

    /** "\u25cf " if `sel` equals `code`, otherwise "\u25cb " \u2014 the radio marker every method chip
     *  wears, in one place so the glyph and the state never drift apart. */
    static String methodMark(int sel, int code) {
        return (sel == code ? "\u25cf " : "\u25cb ");
    }

    static double clamp1(double v, double lo, double hi) {
        v = Math.round(v * 10) / 10.0;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static String fmt1(double v) { return String.format(Locale.US, "%.1f", v); }

    /** A length stepper's printed RANGE, in the display size unit — "5.0–30.0 cm" or
     *  "1.97–11.81 in". The bounds themselves are always the same centimetres (that is what
     *  clamp1 enforces); this is only how they are stated, and the two must agree or the
     *  row claims a range it does not keep. */
    static String lenRange(double loCm, double hiCm) {
        return Model.Fmt.lenNum(loCm) + "–" + Model.Fmt.len(hiCm);
    }

    /** The unit word of an already-formatted value ("−5.3 inHg" → "inHg"), or "". */
    static String unitWordOf(String v) {
        int at = v == null ? -1 : v.lastIndexOf(' ');
        return at < 0 ? "" : v.substring(at + 1);
    }

    /** The same value without its unit word ("−5.3 inHg" → "−5.3"). */
    static String valueWordOf(String v) {
        int at = v == null ? -1 : v.lastIndexOf(' ');
        return at < 0 ? (v == null ? "" : v) : v.substring(0, at);
    }

    /** A pressure stepper's printed RANGE from two already-formatted ends: the unit is said
     *  ONCE, after the second end, when both ends carry the same one ("−2.1 inHg" and
     *  "−16.8 inHg" → "−2.1 to −16.8 inHg"). Said twice it made the label under the field
     *  twice as long as the field it bounds. Ends in different units, or with none, are
     *  left whole - a range is never allowed to lose a unit it needs. */
    static String rangeWords(String lo, String hi) {
        String l = lo == null ? "" : lo, h = hi == null ? "" : hi;
        String unit = unitWordOf(l);
        if (unit.length() > 0 && unit.equals(unitWordOf(h)))
            return valueWordOf(l) + " to " + h;
        return l + " to " + h;
    }

    /** The one-line label on a Settings "Jump to" chip. A chip that wrapped to two lines
     *  ("How a run / behaves") made one row of chips two heights, so the three long
     *  category names are shortened here - where the meaning is still unambiguous - and
     *  every other name is used as it is. The category itself keeps its full name; this
     *  is only what the chip prints. */
    static String jumpLabel(String category) {
        if (category == null) return "";
        if (category.equals("Training schedule")) return "Schedule";
        if (category.equals("How a run behaves")) return "Run behaviour";
        if (category.equals("Device & developer")) return "Device";
        return category;
    }

    /** THE SIGNED PERCENTAGE TEXT a {@link Meas#prePostPct} value is always printed as —
     *  "+2.1 %" / "−2.1 %" — factored out (Stage B task 10) so the trend chart's own
     *  "post vs pre" caption and the TRENDS tab's P/P% tile can never disagree about how
     *  the SAME percentage is worded, the way two independently-typed format strings for
     *  one number eventually would. The trailing " %" is deliberate even on the tile:
     *  paramTile splits a value on its last space, so this becomes the figure "+2.1" over
     *  a faint "%" caption line — the same shape every other tile's unit takes. */
    static String prePostPctText(double pct) {
        return (pct >= 0 ? "+" : "−") + String.format(Locale.US, "%.1f", Math.abs(pct)) + " %";
    }

    /** Model.TREND_* to the glyph and words the mock states verbatim — "▲ ahead of
     *  trend" / "● on trend" / "▼ below trend" — read on the M1 goal instrument and
     *  echoed on the log-confirmation snack ({@link #trendSnackSuffix}), so the two can
     *  never say something different. */
    static String trendLabel(int state) {
        if (state == Model.TREND_AHEAD) return "▲ ahead of trend";
        if (state == Model.TREND_ON) return "● on trend";
        return "▼ below trend";
    }

    /** A horizon label for the card's heading. */
    static String horizonLabel(int months) {
        if (months == 6) return "6 months";
        if (months == 24) return "2 years";
        if (months == 36) return "3 years";
        return "1 year";
    }

    /** One letter for what a day runs. A dash for the plan's own choice: the absence of a
     *  preference is not a preference, and a symbol for it is one more thing to learn. */
    static String planLetter(int plan) {
        switch (plan) {
            case Schedule.PLAN_GIRTH:  return "G";
            case Schedule.PLAN_LENGTH: return "L";
            case Schedule.PLAN_BOTH:   return "B";
            default:                   return "\u2013";
        }
    }

    static int clampI(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** A plotted series' label \u2014 the method's short name and the phase word, e.g.
     *  "BPEL pre", "Standardised post", "MSEG \u2014". */
    static String seriesLabel(int method, int phase) {
        return Model.Reading.methodLabel(method) + " " + phaseWord(phase);
    }

    static String phaseWord(int phase) {
        if (phase == Model.Reading.PHASE_PRE) return "pre";
        if (phase == Model.Reading.PHASE_POST) return "post";
        return "\u2014";   // \u2014 for unknown
    }

    /** A SERIES CHIP'S SMALL PHASE WORD on the Progress chart (item 14): "pre", "post", or
     *  NOTHING for a reading whose phase was never tagged. The checklist it replaced printed
     *  "Std —" there, and a lone dash reads as a stray mark or a minus sign; an untagged
     *  reading has no phase to name, so the chip names only the method. */
    static String phaseTag(int phase) {
        if (phase == Model.Reading.PHASE_PRE) return "pre";
        if (phase == Model.Reading.PHASE_POST) return "post";
        return "";
    }

    /** What a series chip SAYS to a screen reader: the method, then the phase in words -
     *  "MSEG, before a session", "BPSSL, after a session", or just "Standardised" when the phase was
     *  never tagged. The printed "pre"/"post" is small shorthand; a listener gets words. */
    static String seriesSaid(int method, int phase) {
        String m = Model.Reading.methodLabel(method);
        if (phase == Model.Reading.PHASE_PRE) return m + ", before a session";
        if (phase == Model.Reading.PHASE_POST) return m + ", after a session";
        return m;
    }

    /** The caption over the Progress chart naming what it draws: the metric and the unit
     *  its axis is in - "Girth, cm", "Length, in". */
    static String trendAxisCaption(boolean length, String unit) {
        return (length ? "Length" : "Girth") + ", " + unit;
    }

    /** A monthly length RATE's NUMBER ONLY, in the display unit, two decimals — "0.16", not
     *  "0.16 cm/mo": a monthly rate is routinely under a tenth of a centimetre, the same
     *  reason Fmt#lenDelta already doubles Fmt#lenNum's one decimal for a delta, and the
     *  PACE NEED/ACTUAL row prints its own unit once at the end rather than after each
     *  number. Presentation only — no new goal math, just this row's own precision. */
    static String rateNum(double cmPerMonth) {
        boolean inches = Model.Fmt.S_IN.equals(Model.Fmt.sizeUnit);
        double shown = inches ? cmPerMonth / Model.Fmt.CM_PER_IN : cmPerMonth;
        return String.format(Locale.US, "%.2f", shown);
    }

    /**
     * THE COMPARABILITY CLASS OF A READING, at a glance - a standardised reading names the
     * vacuum the pump actually delivered (or says it is unknown), an at-rest reading names
     * the protocol it was taken under.
     */
    /**
     * HOW A WEEK COUNTS, IN PLAIN WORDS (week B, the owner's decision of 2026-10-03): a track's
     * week counts with 2 full sessions of it, or 3 days (TrainingWeek). Said wherever the rule
     * is explained, so every screen says it the one way.
     */
    public static final String WEEK_RULE =
        "Each track moves on with 2 full sessions a week (or 3 shorter ones).";

    /** Why two full sessions do not count yet, said where they show (week B timing). */
    public static final String WEEK_WAITS = "counts after this week's last training day";

    /** WHEN THEY COUNT, in one sentence - the This week card's ⓘ. */
    public static final String WEEK_WHEN = "Two full sessions count after the track's last "
        + "training day of the week; three days count on the third, mid-week too.";

    /**
     * ONE TRACK'S WEEK SO FAR, against the rule: "1 of 2 full sessions · 2 of 3 days" while it
     * does not count yet, then what counted it - "2 full sessions — counted", "3 days —
     * counted". Never an overshoot ("4 of 3"): once the week counts the figure stands alone.
     * Two full sessions with a scheduled day of the track still to come say they wait for it
     * (TrainingWeek#tally: they count only once the week's training days are done).
     * The full sessions shown are whole ones, never more than the days that ran
     * (TrainingWeek.Tally#fullShown), so the figure reaches 2 exactly when the rule is met.
     */
    public static String weekProgress(TrainingWeek.Tally t) {
        int need = Plan.TRAINING_WEEK_FULL_SESSIONS, days = Plan.TRAINING_WEEK_MIN_DAYS;
        if (t.qualifies) {
            if (t.days >= days) return t.days + " days \u2014 counted";
            return t.fullShown() + " full sessions \u2014 counted";
        }
        if (t.volumeMet)
            return t.fullShown() + " of " + need + " full sessions \u00b7 " + WEEK_WAITS;
        return t.fullShown() + " of " + need + " full sessions \u00b7 " + t.days + " of " + days
            + " days";
    }

    /** The plan-wide week as one short figure: "Counted"; one track's "1 of 2 full sessions";
     *  both tracks' "Girth 1/2 · Length 2/2" - the This week card's big figure and the
     *  summary's "This week" row. */
    public static String planWeekShort(TrainingWeek.PlanWeek p) {
        if (p.qualifies) return "Counted";
        int need = Plan.TRAINING_WEEK_FULL_SESSIONS;
        if (p.activeTracks() == 2)
            return "Girth " + p.girth.fullShown() + "/" + need + " \u00b7 Length "
                + p.length.fullShown() + "/" + need;
        TrainingWeek.Tally t = p.lengthActive ? p.length : p.girth;
        if (t.waiting())
            return t.fullShown() + " of " + need + " full sessions \u00b7 " + WEEK_WAITS;
        return t.fullShown() + " of " + need + " full sessions";
    }

    /** Whether the card shows {@link #planWeekLine} under its figure: while the week does not
     *  count, unless the figure already says all of it (one track, two full sessions waiting,
     *  which {@link #planWeekShort} says with its reason). */
    public static boolean planWeekLineShown(TrainingWeek.PlanWeek p) {
        if (p.qualifies) return false;
        if (p.activeTracks() == 2) return true;
        TrainingWeek.Tally t = p.lengthActive ? p.length : p.girth;
        return !t.waiting();
    }

    /** The plan-wide week in a sentence, under the card: each track's progress, and with both
     *  tracks on the days of both together - three of them, each track in at least one, also
     *  count it (W2). */
    public static String planWeekLine(TrainingWeek.PlanWeek p) {
        if (p.qualifies) return "This week counts toward your plan.";
        if (p.activeTracks() == 2)
            return "This week: girth " + weekProgress(p.girth) + "; length "
                + weekProgress(p.length) + "; " + p.days + " of "
                + Plan.TRAINING_WEEK_MIN_DAYS + " training days in all.";
        TrainingWeek.Tally t = p.lengthActive ? p.length : p.girth;
        return "This week: " + weekProgress(t) + ".";
    }

    static String readingClass(Model.Reading r) {
        // Item 20: "held at −6.7 inHg actual" says the same thing in plainer words.
        if (r.holdKpa != null)
            return r.observedKpa == null ? "standardised \u00b7 vacuum not read"
                 : "standardised \u00b7 measured at " + Model.Fmt.p(r.observedKpa.doubleValue());
        return Model.Reading.isAtRestMethod(r.method)
             ? "at rest \u00b7 " + Model.Reading.methodLabel(r.method)
             : "no hold recorded";
    }

    /** ITEM 18 - the editor's "Taken" fact: how the reading was taken, read-only.
     *  "Standardised at −5.9 inHg, held 30 s" (the hold as configured then), "At rest, no
     *  hold", or "No hold recorded" for a Std-tagged reading without one (a skipped hold,
     *  or one the old at-rest sheet filed). */
    static String takenWords(Model.Reading r) {
        if (Model.Reading.isStandardised(r))
            return "Standardised at " + Model.Fmt.p(r.holdKpa.doubleValue())
                + (r.holdSec != null ? ", held " + r.holdSec.intValue() + " s" : "");
        return Model.Reading.isAtRestMethod(r.method) ? "At rest, no hold" : "No hold recorded";
    }

    /** ITEM 18 - the editor's "Photos" fact, from the photos actually on the reading (the
     *  old toggle flipped a flag with no photo behind it): "POV and Side", "POV", "Side",
     *  "Top" for an old one, or "None". */
    static String photosWords(Model.Reading r) {
        java.util.List<String> v = new java.util.ArrayList<String>();
        if (r.photoFront != null) v.add(Shot.label(Shot.FRONT));
        if (r.photoSide != null) v.add(Shot.label(Shot.SIDE));
        if (r.photoTop != null) v.add(Shot.label(Shot.TOP));
        if (v.isEmpty()) return "None";
        if (v.size() == 1) return v.get(0);
        if (v.size() == 2) return v.get(0) + " and " + v.get(1);
        return v.get(0) + ", " + v.get(1) + " and " + v.get(2);
    }

    /**
     * WHAT KIND OF READING `r` IS, in the words the capture screens use: "standardised", "at
     * rest \u00b7 BPEL", or "at rest" for one with no hold and no at-rest method. Two equal ways
     * of measuring, each named for what it is.
     */
    static String readingKind(Model.Reading r) {
        if (Model.Reading.isStandardised(r)) return "standardised";
        return Model.Reading.isAtRestMethod(r.method)
             ? "at rest \u00b7 " + Model.Reading.methodLabel(r.method)
             : "at rest";
    }

    /**
     * E2 - THE AFTER SCREEN'S CHIP, { headline, caption }: what this reading is, and what it
     * is compared with. The before screen always had this line; the after screen, where the
     * comparison matters most, had none.
     *
     * Every case says what IS, never that a reading falls short (the owner's rule): a
     * standardised reading names its hold; an at-rest one its method; one taken under a hold
     * that did not run its count says exactly that. When the pair does not compare, the
     * caption says what the before reading was and that each kind is compared with its own -
     * so no before-to-after change is worked out, and the screen shows none.
     *
     * `holdSec` is the configured count, for the one case the reading itself cannot say.
     */
    static String[] afterChip(Model.Reading before, Model.Reading after, boolean comparable,
                              int holdSec) {
        boolean std = Model.Reading.isStandardised(after);
        boolean rest = !std && Model.Reading.isAtRestMethod(after.method);
        String head = std
            ? "Standardised at " + Model.Fmt.p(after.holdKpa.doubleValue())
              + " \u00b7 held " + (after.holdSec != null ? after.holdSec.intValue() : holdSec) + " s"
            : rest ? "At rest \u00b7 " + Model.Reading.methodLabel(after.method)
            : "Held, without the " + holdSec + " s count";
        String cap;
        if (comparable)
            cap = "taken the same way as your before reading, so the two compare";
        else if (!std && !rest)
            cap = "saved with no hold recorded, so it is kept apart from your standardised "
                + "readings";
        else if (std && before != null && Model.Reading.isStandardised(before))
            cap = before.observedKpa == null || after.observedKpa == null
                ? "the pump's vacuum was not read for one of the two, so no before-to-after "
                  + "change is worked out"
                : "your before reading was held at "
                  + Model.Fmt.p(before.observedKpa.doubleValue()) + ", this one at "
                  + Model.Fmt.p(after.observedKpa.doubleValue())
                  + ", so no before-to-after change is worked out";
        else
            cap = "your before reading was " + (before == null ? "not found" : readingKind(before))
                + ", and each kind is compared with its own, so no before-to-after change is "
                + "worked out";
        return new String[]{ head, cap };
    }

    /**
     * ONE ROW OF THE READINGS LIST: the date, the two figures, the change against the row
     * below it, and the tags.
     *
     * THE DELTA IS ONLY PRINTED FOR A COMPARABLE PAIR. Without that test the row printed a
     * confident signed change for exactly the pairs the chart refuses to join and "since
     * last session" declines to put a number on - one screen, two verdicts.
     * Model.Reading#comparable is the same test both of those use. A metric the reading's
     * own method never measured is a dash, never the primitive's 0.0.
     */
    static String measRow(Model.Reading e, Model.Reading prev, String dateLabel) {
        return dateLabel + "   " + measFigures(e, prev) + "\n" + measTags(e);
    }

    /** The row's first line (item 20's card): the two figures, a dash for what the method
     *  never measured, and the change only for a pair that compares. */
    static String measFigures(Model.Reading e, Model.Reading prev) {
        boolean deltaOk = prev != null && e.measuredLength() && prev.measuredLength()
                && Model.Reading.comparable(e, prev);
        double d1 = deltaOk ? e.len - prev.len : 0;
        String delta = deltaOk ? "  " + Model.Fmt.lenDelta(d1) : "";
        // The unit rides on the last number shown: "15.6 / 12.6 cm", "— / 12.0 cm", and a
        // length with no girth beside it keeps its own - "15.0 cm / —", not "15.0 / —".
        String lenText = !e.measuredLength() ? "\u2014"
            : e.measuredGirth() ? Model.Fmt.lenNum(e.len) : Model.Fmt.len(e.len);
        String girText = e.measuredGirth() ? Model.Fmt.len(e.gir) : "\u2014";
        return lenText + " / " + girText + delta;
    }

    /** The row's second line: what kind of reading it is, then photo and edited. */
    static String measTags(Model.Reading e) {
        return readingClass(e) + (e.photo ? " \u00b7 photo" : "")
                + (e.edited ? " \u00b7 edited" : "");
    }

    /** The wizard step's own name: the stage's when the WHOLE stage was skipped (one step
     *  stands for all of it), else the set's, else the stage's, else its position. */
    static String stepName(java.util.List<AsRun.Block> blocks, AsRun.Block b) {
        if (b.skipped && AsRun.stageSkipped(blocks, b.stageIdx)
            && b.stageName != null && b.stageName.length() > 0) return b.stageName;
        if (b.setName != null && b.setName.length() > 0) return b.setName;
        if (b.stageName != null && b.stageName.length() > 0) return b.stageName;
        return "Block " + (b.idx + 1);
    }

    /** The kind word beside it - SKIPPED / RAMP / FIXED. A REST entry never yields a block
     *  (AsRun's REST rows carry no values, and neither split a block nor count toward one),
     *  so REST never appears in the wizard at all - not as a step, and not on the overlay
     *  chart either: a Block carries no rest segment to draw, so the mock's "rest = flat
     *  line" case is unrepresentable from real data. Documented deviation from
     *  dist/final-design.html section 3's why-line. */
    static String stepKind(AsRun.Block b) {
        if (b.skipped) return "SKIPPED";
        return b.kind == AsRun.RAMP ? "RAMP" : "FIXED";
    }

    /** What CHANGED in a prescription, as a headline: the sets, the pressure, or both. A
     *  pair that differs in neither is still an update - the plan may have rewritten a
     *  routine for a reason this line does not carry - so it says so plainly. */
    static String planChange(Mint.Rx was, Mint.Rx now) {
        if (was == null || now == null) return "Your routine was updated";
        boolean sets = was.sets != now.sets;
        boolean kpa = was.pressureKpa != now.pressureKpa;
        if (sets && kpa && now.sets > was.sets && now.pressureKpa > was.pressureKpa)
            return "More sets, and a little deeper";
        // Any other pair says both figures, so a lighter or shorter session is never called
        // "more" and "deeper".
        if (sets && kpa) return was.sets + " → " + now.sets + " sets, "
            + Model.Fmt.p(was.pressureKpa) + " → " + Model.Fmt.p(now.pressureKpa);
        if (sets) return was.sets + " \u2192 " + now.sets + " sets";
        if (kpa) return Model.Fmt.p(was.pressureKpa) + " \u2192 " + Model.Fmt.p(now.pressureKpa);
        return "Your routine was updated";
    }

    /** The headline {@link #planChange} gives when nothing it counts moved. */
    static final String PLAN_UPDATED = "Your routine was updated";

    /** 0.10 - the headline of the one rewrite that brings a traditional routine onto the
     *  guidance's growth ({@link #traditionalGrowthDetail}). */
    static final String TRADITIONAL_GROWS = "Traditional girth now grows";

    /**
     * 0.10 - TRADITIONAL GIRTH NOW GROWS AS THE GUIDANCE DOES (the owner's decision), said on
     * the rewrite that brings a routine the old rule built onto it - "" otherwise. The old rule
     * built 2 holds at every level, so a traditional routine whose own holds less the yield sets
     * it was built with (`yieldSets`, before this rewrite's own) are under the new rule's fewest
     * ({@link Plan#TRAD_L1_START_SETS}) was the old rule's: under the new rule they are never
     * fewer than 3. Says that it was rewritten once for it, and that Undo puts it back.
     */
    static String traditionalGrowthDetail(String wasSig, String nowSig, int yieldSets) {
        Mint.Rx was = SavedMint.rxOf(wasSig), now = SavedMint.rxOf(nowSig);
        if (was == null || now == null) return "";
        if (was.track != Plan.TRACK_GIRTH_TRADITIONAL || now.track != Plan.TRACK_GIRTH_TRADITIONAL)
            return "";
        if (was.sets - Math.max(0, yieldSets) >= Plan.TRAD_L1_START_SETS) return "";
        return TRADITIONAL_GROWS_RULE + " Your traditional routine was "
            + "rewritten once for this, from your level's first week — Undo puts it back as it was.";
    }

    private static final String TRADITIONAL_GROWS_RULE =
        "Traditional girth now grows as the guidance does: Level 1 starts at "
            + Plan.TRAD_L1_START_SETS + " five-minute holds and adds one every "
            + Plan.TRAD_WEEKS_PER_HOLD + " training weeks up to " + Plan.TRAD_L1_TOP_SETS
            + "; Level 2 runs " + Plan.TRAD_L2_SETS + "; Level 3 runs " + Plan.TRAD_L3_START_SETS
            + " after the fatigue intervals and adds one every " + Plan.TRAD_WEEKS_PER_HOLD
            + " training weeks up to " + Plan.TRAD_L3_TOP_SETS + "; Level 4 runs "
            + Plan.TRAD_L4_SETS + ". It used to stay at " + Plan.TRAD_OLD_SETS + " holds.";

    /**
     * 0.10 - THE HALF START, SAID (the owner's decision): the first rewrite of a traditional
     * routine that builds up to its level's count (Mint#startWeekGrowth, above Level 1) says the
     * growth, and that this routine builds up to it rather than jumping - from `sets` holds now,
     * one more every Plan#TRAD_BUILD_WEEKS_PER_HOLD training weeks, to the level's `count`.
     * Said once (Model.TrainerTrackState#buildSaid), in place of {@link #traditionalGrowthDetail}.
     */
    static String traditionalBuildUpDetail(int sets, int count) {
        return TRADITIONAL_GROWS_RULE + " Your traditional routine builds up to your level's "
            + count + " instead of jumping to it: " + sets + " holds now, one more every "
            + Plan.TRAD_BUILD_WEEKS_PER_HOLD + " training weeks until it reaches " + count
            + ". It was rewritten for this — Undo puts it back as it was.";
    }

    /**
     * 0.10 - WHAT A REWRITE CHANGED IN THE RAMPS OR THE WARM-UP, as a headline, or null where
     * neither moved: the ramp settings a Ramped routine was minted with (Model#rampTag), or the
     * gentle warm-up of somebody who marks easily (Model#gentleWarmTag). Said instead of "Your
     * routine was updated" when the sets and the pressure stayed where they were.
     */
    static String shapeChange(String wasSig, String nowSig) {
        if (rampMoved(wasSig, nowSig)) return "Your ramps changed";
        if (gentleMoved(wasSig, nowSig)) return "Your warm-up changed";
        if (t10Built(wasSig, nowSig)) return T10_BUILD_HEAD;
        if (fatigueMoved(wasSig, nowSig)) return FATIGUE_HOLDS_HEAD;
        return null;
    }

    /** R11-5 - the headline of a rewrite that changed only the fatigue block's holds. */
    static final String FATIGUE_HOLDS_HEAD = "Your fatigue holds changed";

    /** R11-5 - whether the fatigue block's hold moved (Model#rxShapeTag's "F" token; a routine
     *  minted before it ran 45 s holds). */
    static boolean fatigueMoved(String wasSig, String nowSig) {
        String b = SavedMint.shapeToken(nowSig, 'F');
        if (b == null || wasSig == null || wasSig.length() == 0) return false;
        String a = SavedMint.shapeToken(wasSig, 'F');
        return !b.equals(a == null ? "F" + Mint.HOLD_FATIGUE_SEC : a);
    }

    /** "The fatigue block is now 15 holds of 30 s - the same minutes at pressure. ..." */
    static String fatigueWords(String nowSig) {
        String b = SavedMint.shapeToken(nowSig, 'F');
        int sec;
        try { sec = Integer.parseInt(b.substring(1)); } catch (RuntimeException e) { return ""; }
        return "The fatigue block is now " + Mint.fatigueHolds(Plan.FATIGUE_BLOCK_SETS, sec)
            + " holds of " + sec + " s — the same minutes at pressure. You can pick 30, 45 or "
            + "60 s in What it writes › Hold lengths.";
    }

    /**
     * The sentences a plan-change notice adds for a change of ramp or warm-up - "" where neither
     * moved. The first rewrite of a Ramped routine saved before the ramp settings existed says
     * how the ramp itself changed (the owner's decision: the new defaults reach existing Ramped
     * routines, and the notice says so).
     */
    static String shapeChangeDetail(Model m, String wasSig, String nowSig) {
        StringBuilder b = new StringBuilder();
        if (rampMoved(wasSig, nowSig)) {
            if (SavedMint.shapeToken(wasSig, 'Y') == null && wasRamped(wasSig))
                b.append("Ramps work differently now: they used to climb from your level's "
                    + "floor across the whole block. ");
            b.append(rampWords(m)).append(' ');
        }
        if (gentleMoved(wasSig, nowSig)) {
            if (SavedMint.shapeToken(nowSig, 'G') != null)
                b.append("Because you mark or bruise easily, the warm-up starts lower and slower "
                    + "and climbs a little each rep to your working pressure. ");
            else
                b.append("The warm-up is back to the usual one. ");
        }
        if (t10Built(wasSig, nowSig)) b.append(t10BuildWords(nowSig)).append(' ');
        if (fatigueMoved(wasSig, nowSig)) b.append(fatigueWords(nowSig)).append(' ');
        return b.toString().trim();
    }

    /** The ramp settings in a sentence: "Each block climbs from 80% of your working pressure, at
     *  most 1.0 inHg a hold; after a rest it climbs 2 steps." */
    static String rampWords(Model m) {
        String after = m.rampShortSteps <= 1 ? "after a rest it starts at your working pressure"
            : "after a rest it climbs " + m.rampShortSteps + " steps";
        return "Each block climbs from " + m.rampStartPct + "% of your working pressure, at most "
            + Model.Fmt.mag(m.rampStepHg * Model.Fmt.KPA_PER_INHG) + " a hold; " + after
            + (m.rampLighterDays ? "." : ". Lighter days run flat.")
            + (m.rampCountClimb ? "" : " Only holds at the working pressure count.");
    }

    private static boolean rampMoved(String wasSig, String nowSig) {
        String a = SavedMint.shapeToken(wasSig, 'Y'), b = SavedMint.shapeToken(nowSig, 'Y');
        return b != null && !b.equals(a);
    }

    /** t10 - the headline of the first rewrite of a routine minted before the t10 build. */
    static final String T10_BUILD_HEAD = "Your sessions were updated";

    /** Whether `wasSig` was minted before the t10 build and `nowSig` by it
     *  (Model#T10_BUILD_TOKEN). */
    static boolean t10Built(String wasSig, String nowSig) {
        return wasSig != null && wasSig.length() > 0
            && t10Token(nowSig) && !t10Token(wasSig);
    }

    private static boolean t10Token(String sig) {
        String tag = sig == null ? null : SavedMint.shapeToken(sig, 'V');
        return Model.T10_BUILD_TOKEN.equals(tag);
    }

    /** What the t10 build changed in a routine saved before it, in plain words: the warm-up
     *  (unless it is the gentle one, or none), and a traditional session's rests. */
    static String t10BuildWords(String nowSig) {
        StringBuilder b = new StringBuilder();
        Mint.Rx rx = SavedMint.builtRx(nowSig);
        boolean gentle = SavedMint.shapeToken(nowSig, 'G') != null;
        String prog = SavedMint.shapeToken(nowSig, 'P');
        boolean none = prog != null && prog.length() == 6
            && prog.charAt(1) - '0' == Model.Program.WARM_NONE;
        if (!gentle && !none)
            b.append("The warm-up is now about 5 minutes of short holds that climb to your "
                + "pressure (30 s growing to 60 s). ");
        if (rx != null && rx.track == Plan.TRACK_GIRTH_TRADITIONAL)
            b.append("Rests between holds are " + Plan.TRAD_REST_SEC + " s. ");
        return b.toString().trim();
    }

    private static boolean gentleMoved(String wasSig, String nowSig) {
        String a = SavedMint.shapeToken(wasSig, 'G'), b = SavedMint.shapeToken(nowSig, 'G');
        return a == null ? b != null : !a.equals(b);
    }

    /** Whether a signature's Program ran its work Ramped (Model.Program#tag: "P" + warm, work,
     *  ...). */
    private static boolean wasRamped(String sig) {
        String p = SavedMint.shapeToken(sig, 'P');
        return p != null && p.length() == 6 && p.charAt(2) - '0' == Model.Program.WORK_RAMP_IN_SET;
    }

    /** True when any stage of the routine is a traction stage. */
    static boolean isTraction(Model.Routine r) {
        if (r == null) return false;
        for (int i = 0; i < r.stages.size(); i++)
            if (r.stages.get(i) != null && r.stages.get(i).traction) return true;
        return false;
    }

    static String girthLevel(int level) {
        return "Girth \u00b7 " + TrainerTab.levelLabel(level);
    }

    /** How long since the last session, in the words the cards use - and "" when there has
     *  never been one, because "0 h rested" over an empty log is a claim about nothing. The
     *  clock stays with the screen; the wording is what belongs here. */
    static String rested(long h) {
        // Under an hour is "just now", and "0 h rested" read as a fault (polish TR-14).
        if (h < 1) return "";
        if (h < 24) return h + " h rested";
        long d = h / 24;
        return d + (d == 1 ? " day rested" : " days rested");
    }

    /** A day as a person names it: "Thu 3 Sep". Used by the deload report and the prompt. */
    static String dayLabel(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        String[] wd = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
        String[] mo = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
        return wd[c.get(java.util.Calendar.DAY_OF_WEEK) - 1] + " "
             + c.get(java.util.Calendar.DAY_OF_MONTH) + " "
             + mo[c.get(java.util.Calendar.MONTH)];
    }

    /** The letter a scheduled day wears - "" for a day that is not scheduled at all, which
     *  is what keeps an unscheduled day from reading as a plan nobody chose. */
    static String planGlyph(Schedule sched, int weekdayIdx) {
        if (sched == null || !sched.trainsOn(weekdayIdx)) return "";
        switch (sched.planOn(weekdayIdx)) {
            case Schedule.PLAN_GIRTH:  return "G";
            case Schedule.PLAN_LENGTH: return "L";
            case Schedule.PLAN_BOTH:   return "B";
            default:                   return "";
        }
    }

    /** The week strip's whole sentence for a screen reader: seven days, each said as
     *  trained or not, with the plan named where the day carries one. The strip is ONE focus
     *  stop, so this is the only chance to say any of it. */
    static String weekStripSaid(Schedule sched, boolean[] on) {
        StringBuilder b = new StringBuilder("This week: ");
        for (int i = 0; i < 7; i++) {
            b.append(Schedule.DAY_ABBR[i]).append(' ');
            b.append(on[i] ? "trained" : "not trained");
            String g = planGlyph(sched, i);
            if (g.length() > 0) b.append(", ").append(Schedule.planLabel(sched.planOn(i)));
            b.append(". ");
        }
        return b.toString();
    }

    /**
     * "N days trained in six months", or the honest silence of "no sessions yet".
     *
     * Takes the daily net minutes rather than the log: the walk over six months of
     * sessions belongs to the screen that has the model, and counting non-zero days is
     * what this sentence actually is.
     */
    static String historySummary(double[] dailyNetMin) {
        int trained = 0;
        for (int i = 0; dailyNetMin != null && i < dailyNetMin.length; i++)
            if (dailyNetMin[i] > 0) trained++;
        return trained == 0 ? "no sessions yet" : trained + " days trained in six months";
    }

    /**
     * WHERE THE GIRTH TRACK STANDS, short enough for a chip: the level, and - only where
     * there is a real week table to count against - the week within it. Traditional girth
     * has no table, so it gets the level alone rather than a position in a table it is not
     * following.
     */
    /* ================================== THE VALUE LINE ON A DOOR ====================== */

    /** Between two facts on a door. Ordinary spaces either side of the dot, so a value line
     *  too long for its row breaks HERE - between facts - and nowhere else. */
    static final String DOOR_SEP = "  \u00b7  ";

    /**
     * A DOOR'S VALUE LINE: the facts, whole, in order, never cut.
     *
     * Asked for in so many words - "find a way for the info to not get cut if long" - about a
     * mock-up that drew this line with an ellipsis. So:
     *
     *   - every space INSIDE a fact becomes non-breaking, so "warm-up 8 min" stays one piece;
     *   - the separator keeps ordinary spaces, so the line breaks between facts;
     *   - null and blank facts are skipped rather than leaving a dangling dot;
     *   - nothing is shortened. The row that prints this wraps to as many lines as it needs.
     *
     * A single fact longer than a whole line (a six-day schedule on a narrow phone) still
     * wraps rather than overflowing - the platform breaks an unbreakable run at the edge
     * rather than clipping it - so the rule is "never cut", not "never break".
     */
    static String doorValue(String[] parts) {
        StringBuilder b = new StringBuilder();
        if (parts == null) return "";
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i] == null ? "" : parts[i].trim();
            if (t.length() == 0) continue;
            if (b.length() > 0) b.append(DOOR_SEP);
            b.append(t.replace(' ', '\u00a0'));
        }
        return b.toString();
    }

    /** The same facts, for a screen reader: ordinary spaces, and a SEMICOLON between facts.
     *  A non-breaking space and a middle dot are layout, and layout is not something to say.
     *  Not a comma, because a fact can itself be a comma list - the schedule is "Mon, Tue,
     *  Wed" - and joining facts with commas made the training time sound like another day. */
    static String doorSaid(String[] parts) {
        StringBuilder b = new StringBuilder();
        if (parts == null) return "";
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i] == null ? "" : parts[i].trim();
            if (t.length() == 0) continue;
            if (b.length() > 0) b.append("; ");
            b.append(t);
        }
        return b.toString();
    }

    /**
     * "WHERE I AM" - the position, in the order somebody asks about it: which level, how far
     * through it, at what pressure. The length track, when it is on, leads with its LOAD and
     * not its pressure, for the reason its own card gives: length work is governed by pounds,
     * and the same pressure pulls a different load in a different cylinder.
     *
     * THE LOAD THE PULL IS BUILT AT (owner, 2026-09-30): the plan's load with the length
     * offset on it (Scale#pullLoadLb), which is the length pressure answered in the length
     * cylinder - the plan's load alone said "length 2.5 lb" beside a pressure that pulls 11.8.
     */
    static String[] whereIAmParts(Model m) {
        return whereIAmParts(m, System.currentTimeMillis());
    }

    static String[] whereIAmParts(Model m, long nowMs) {
        java.util.List<String> o = new java.util.ArrayList<String>();
        o.add("Girth " + TrainerTab.levelLabel(m.trainerGirth.level));
        if (m.trainerGirthStyle == Plan.TRACK_GIRTH_INTERVAL)
            o.add("wk " + Math.max(1, m.trainerGirth.weekIndex));
        // The whole kPa it commands: the plan keeps its figure exact (t10 REAL-15).
        o.add(Model.Fmt.p(Math.round(m.trainerGirth.pressureKpa)));
        // As delivered at the whole-kPa command, the figure the length card says (device walk).
        if (m.trainerLengthOn)
            o.add("length " + Traction.settingLb(Scale.shownLoadLb(m, nowMs)));
        return o.toArray(new String[0]);
    }

    /** "WHEN IT RUNS" - the schedule the plan counts weeks over, and the ceiling every
     *  prescription is clamped to. All three are Settings' values, read, never copied. */
    static String[] whenItRunsParts(Model m) {
        return new String[]{ m.sched.daysLine(), m.sched.hhmm(),
                             "ceiling " + Model.Fmt.p(m.ceilKpa) };
    }

    static String trainerPosition(int level, int girthStyle, int weekIndex) {
        String lvl = TrainerTab.levelLabel(level);
        if (girthStyle != Plan.TRACK_GIRTH_INTERVAL) return lvl;
        // Levels 3 and 4 have no table to be a week of; Level 2's position is a row of its
        // own table, read on the tables' one week scale (TrainerTab#tableWeekNum).
        if (level != Plan.L1 && level != Plan.L2) return lvl;
        return lvl + "  \u00b7  wk " + TrainerTab.tableWeekNum(level, weekIndex)
             + " of " + Plan.intervalMasterPlan().size();
    }

    /* ---- small counts, said as a person would (t10 device walk) ---------------------------- */

    /** "Enrolled": "this month", "1 month ago", "7 months ago" - never "1 months ago". */
    static String monthsAgo(int months) {
        if (months <= 0) return "this month";
        return months + (months == 1 ? " month ago" : " months ago");
    }

    /* ---- the gentle return, as Today and the Trainer say it ------------------------------
     *
     * EVERY "FULL PRESSURE" BELOW IS ASKED OF WHAT IS IN FORCE, never of the taper alone.
     *
     * Once the taper's cut reaches nothing, Model#reductionHg falls back to the cylinder's own
     * 2 hg - the larger cylinder, or an accepted oversize one. These lines used to be built
     * from the taper's state, so somebody in that cylinder was told their next session was
     * "back at full pressure", and the Trainer that the cylinder's own cut "is not added", while
     * the pump ran 2 hg under. Pure, so the harness can hold every state to it.
     */

    /** The face of Today's "Coming back gently" card: what today ran or runs at, and what the
     *  next training day will. */
    static String taperFace(Model m, long nowMs) {
        long today = Summary.dayNumber(nowMs);
        // t10 REAL-5: a week off answered for a later day has not begun - nothing is cut yet.
        if (Deload.notBegunOn(m, today))
            return "Your deload week starts " + dayLabel(Deload.startMs(m))
                 + ". Until then your sessions run as usual.";
        if (m.returnDayRun == today && m.returnHeld)
            return "Today ran " + hgUnder(Plan.returnTaperHg(m.returnStep))
                 + " under. Your next training day repeats it.";
        if (m.returnDayRun == today) {
            long tomorrowMs = Deload.dayStartPlus(nowMs, 1);
            double next = m.reductionHg(tomorrowMs);
            return "Today ran " + hgUnder(Plan.returnTaperHg(m.returnStep))
                 + " under. Next training day: "
                 + (next <= 0.0 ? "full pressure."
                    : hgUnder(next) + " under"
                      + (Deload.cutHg(m, Summary.dayNumber(tomorrowMs)) > 0.0
                         ? "." : ", for your cylinder."));
        }
        double cut = Deload.cutHg(m, today);
        if (cut > 0.0 && Deload.beforeReturn(m, nowMs))
            return "Your deload week: a light session in it runs " + hgUnder(cut)
                 + " under, with a slower pull.";
        if (cut > 0.0)
            return "Today runs " + hgUnder(cut) + " under your working pressure, with a slower pull.";
        double inForce = m.reductionHg(nowMs);
        return inForce > 0.0
            ? "The gentle return is done. Your cylinder still runs " + hgUnder(inForce) + " under."
            : "Your next session is back at full pressure.";
    }

    /**
     * t10 device walk M5 - THE CARD'S TITLE, by where the armed taper is. "Coming back gently"
     * is the return after a week off. A week off answered for a later day arms the taper at the
     * answer (REAL-5), so the card was up - titled for the return, offering "full pressure from
     * here" - on a day that runs at full pressure anyway and before any week off had begun.
     */
    static String taperTitle(Model m, long nowMs) {
        if (Deload.notBegunOn(m, Summary.dayNumber(nowMs))) return "Deload week ahead";
        if (Deload.beforeReturn(m, nowMs)) return "Deload week";
        return "Coming back gently";
    }

    /** Whether the card offers its answers (stay one more day; end it). Not before the week off
     *  begins: nothing is cut yet, and ending the taper there would drop the return the week
     *  off was armed for. */
    static boolean taperAnswerable(Model m, long nowMs) {
        return Deload.armed(m) && !Deload.notBegunOn(m, Summary.dayNumber(nowMs));
    }

    /** The card's way out - "full pressure from here" only where ending it gives that. */
    static String taperEndLabel(Model m, long nowMs) {
        if (Deload.finished(m, Summary.dayNumber(nowMs))) return "Got it";
        return m.cylinderCutHg() > 0.0
            ? "I\u2019m fine \u2014 end the gentle return"
            : "I\u2019m fine \u2014 full pressure from here";
    }

    /** The Trainer's "Coming back gently" row. */
    static String taperRow(Model m, long nowMs) {
        long day = Summary.dayNumber(nowMs);
        if (Deload.notBegunOn(m, day))
            return "deload week from " + dayLabel(Deload.startMs(m));
        double cut = Deload.cutHg(m, day);
        if (cut > 0.0 && Deload.beforeReturn(m, nowMs))
            return "deload week \u00b7 a light session runs " + hgUnder(cut) + " under";
        if (cut > 0.0)
            return "day " + (Deload.stepOn(m, day) + 1) + " of " + Plan.RETURN_TAPER_STEPS
                 + " \u00b7 " + hgUnder(cut) + " under";
        double inForce = m.reductionHg(nowMs);
        return inForce > 0.0
            ? "done \u00b7 your cylinder\u2019s " + hgUnder(inForce) + " under"
            : "next session at full pressure";
    }

    /** The face of the Trainer's "If you mark easily" card while a taper is armed. */
    static String taperMarkFace(Model m, long nowMs) {
        if (Deload.notBegunOn(m, Summary.dayNumber(nowMs)))
            return "Your deload week starts " + dayLabel(Deload.startMs(m))
                 + " — until then your sessions run as usual.";
        double cut = Deload.cutHg(m, Summary.dayNumber(nowMs));
        double cylinder = m.cylinderCutHg();
        if (cut > 0.0)
            return (Deload.beforeReturn(m, nowMs)
                    ? "Your deload week \u2014 a light session in it runs " + hgUnder(cut)
                    : "Coming back gently \u2014 today runs " + hgUnder(cut))
                 + " under, with a slower pull"
                 + (cylinder > 0.0
                    ? ". The cylinder\u2019s own " + hgUnder(cylinder) + " is not added to it."
                    : ".");
        double inForce = m.reductionHg(nowMs);
        return inForce > 0.0
            ? "Coming back gently is done \u2014 the cylinder\u2019s own " + hgUnder(inForce)
              + " under applies again."
            : "Coming back gently \u2014 your next session is at full pressure.";
    }

    /* ---- Today's resume card and loaded row (polish item 7) ---- */

    /** A mint's prescription part: "10×2min @ −5.0 inHg" (older mints wrote the
     *  pressure unsigned, "@ 5.0 inHg"). */
    private static final java.util.regex.Pattern RX_PART =
        java.util.regex.Pattern.compile("\\d+\u00d7\\d+(\\.\\d+)?min @ .+");

    /**
     * A TRAINER ROUTINE'S NAME AS A CARD TITLE: "Trainer · Girth L1", not "Trainer ·
     * Girth L1 · 10×2min @ −5.0 inHg · the rest".
     *
     * Mint.name packs the prescription into the name because in a LIST that is what tells
     * two routines apart. Today's resume card and loaded row print the numbers on the line
     * under the title, so the prescription would be the same figures twice, and "the rest"
     * is what the card itself is saying. Everything else a mint appends ("part 1 of 2",
     * "traction @ 30 lb") still tells two routines apart, so it stays.
     *
     * A routine the user named keeps its name exactly: only a name in the mint's own
     * "Trainer · ..." form is trimmed. The split needs spaces round the dot, so a track
     * written "Girth·trad" is one part, as it is meant to be.
     */
    static String mintTitle(String name) {
        if (name == null) return "";
        String[] parts = name.trim().split("\\s+\u00b7\\s+");
        if (parts.length < 3 || !"Trainer".equals(parts[0])) return name;
        StringBuilder b = new StringBuilder(parts[0]).append(" \u00b7 ").append(parts[1]);
        for (int i = 2; i < parts.length; i++) {
            String p = parts[i];
            if (RX_PART.matcher(p).matches() || "the rest".equals(p)) continue;
            b.append(" \u00b7 ").append(p);
        }
        return b.toString();
    }

    /**
     * WHAT IS LEFT, IN PLAIN TERMS: "5 of 10 cycles left · 2 min each at −5.0 inHg".
     *
     * `left` is the cycles the stopped session still owes; `total` is how many it planned,
     * printed only when it can honestly contain `left` (a total that is smaller says
     * nothing true about it, so the line falls back to "5 cycles left"). The hold and the
     * pressure are the routine's own work set, and each is left out when there is none
     * rather than printed as a zero.
     */
    static String leftToRun(int left, int total, int holdSec, String pressure) {
        StringBuilder b = new StringBuilder();
        b.append(left);
        if (total > 0 && total >= left) b.append(" of ").append(total);
        int n = total > 0 && total >= left ? total : left;
        b.append(n == 1 ? " cycle" : " cycles").append(" left");
        boolean p = pressure != null && pressure.trim().length() > 0;
        if (holdSec > 0) {
            b.append(" \u00b7 ").append(holdWords(holdSec)).append(" each");
            if (p) b.append(" at ").append(pressure.trim());
        } else if (p) {
            b.append(" \u00b7 at ").append(pressure.trim());
        }
        return b.toString();
    }

    /** A hold as it is said: "45 s", "2 min", "1.5 min". */
    static String holdWords(int sec) {
        if (sec < 60) return sec + " s";
        if (sec % 60 == 0) return (sec / 60) + " min";
        return String.format(Locale.US, "%.1f min", Double.valueOf(sec / 60.0));
    }

    /* ---- "Count this toward training?" - one question, one line, and the rest on "Why?" */

    /** Why a run was not counted by itself: it was not from the plan at all, it was one set
     *  run on its own (the Manual screen, Quick run, or "Try this set"), or it was a feeder,
     *  which the plan marks but keeps out of what it counts. */
    static final int UNCOUNTED_OFF_PLAN = 0, UNCOUNTED_ONE_SET = 1, UNCOUNTED_FEEDER = 2;

    /** Which of those it was, from what the run was. A single set wins: it is what the
     *  person just did, whatever routine happens to be loaded behind it. */
    static int uncountedWhy(boolean singleSet, int trainerTrack) {
        if (singleSet) return UNCOUNTED_ONE_SET;
        if (trainerTrack == Plan.TRACK_FEEDER) return UNCOUNTED_FEEDER;
        return UNCOUNTED_OFF_PLAN;
    }

    /** The ONE line under the question. It says why the run is waiting to be counted, in the
     *  reason that is actually true of it: "not from your plan" said of a feeder the plan
     *  wrote would be false. */
    static String uncountedLine(int why) {
        switch (why) {
            case UNCOUNTED_ONE_SET:
                return "It was a single set, not a plan session, so it isn't counted yet.";
            case UNCOUNTED_FEEDER:
                return "Feeder sets aren't counted on their own, so it isn't counted yet.";
            default:
                return "It wasn't from your plan, so it isn't counted yet.";
        }
    }

    /** Behind "Why?": what the run delivered and what counting it does and does not do -
     *  the accounting the dialog and the card used to print before their buttons. `track`
     *  is the track it would join, as TrainerTab#trackLabel names it. */
    static String countWhy(double netSec, String track) {
        return "It delivered " + fmtMin(netSec / 60.0) + " min at or above your working "
             + "pressure. Counting it adds that time to this week's delivered total on "
             + (track == null ? "" : track.toLowerCase(Locale.US)) + ". It doesn't make today "
             + "a training day, and it doesn't change the figures your level gates are judged "
             + "on — a short extra session should never be able to hold you back.";
    }

    /* ---- Library's routine tiles: labels that agree with their numbers ------------------ */

    /** The label under a count, agreeing with it: "Stage" under 1, "Stages" under 0 or 2. */
    static String countLabel(int n, String one, String many) {
        return n == 1 ? one : many;
    }

    /** The Runs tile's figure: the count, or "None yet" for a routine never run. "0x" read
     *  as code, and a zero says less than the words do. */
    static String runsFigure(int runs) {
        return runs <= 0 ? "None yet" : String.valueOf(runs);
    }
}
