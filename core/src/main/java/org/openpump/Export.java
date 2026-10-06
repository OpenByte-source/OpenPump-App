package org.openpump;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The DATA EXPORT, pure (§8 #9): builds the CSV text the Progress › Export sheet writes
 * into files/export/ and {@link ExportProvider} serves. No `import android`, so test.sh
 * compiles it and SelfTest pins every rule below — the same split LogPaths/CapturePaths
 * use for their providers.
 *
 * WHAT THIS EXPORT IS, stated plainly because the app's other export (the DEBUG LOG,
 * LogProvider) promises the opposite: this file DELIBERATELY CONTAINS MEASUREMENTS. It is
 * the user's own data, leaving the phone only because the user tapped Export and then
 * chose a recipient on the share sheet. The debug log's promise is unchanged and worded
 * as what it is: the debug log never contains measurements or photos.
 *
 * THE COLUMN RULES, each of which SelfTest pins:
 *   - Raw kPa columns (peak_kpa, dose_kpa_s, hold_kpa, observed_kpa) are PLAIN NUMBERS,
 *     never a unit string — a spreadsheet must be able to sum them.
 *   - *_display columns go through Model.Fmt and follow the display unit, so an inHg user
 *     sees the negative vacuum convention there while the raw column stays positive kPa.
 *   - display_unit names the unit the display columns are in.
 *   - A MISSING value is an EMPTY field, never 0: 0.0 kPa = no measurement (Proto.parse's
 *     rule), and a session that produced no reading exports "" for peak and dose rather
 *     than a claim that zero was measured.
 *   - RFC 4180 quoting: a field containing a comma, a double quote, or a line break is
 *     wrapped in double quotes with embedded quotes doubled. Records end in CRLF.
 *   - ts_iso is LOCAL ISO-8601 (yyyy-MM-ddTHH:mm:ss), built from Calendar fields — no
 *     formatting library, no timezone suffix: it is the wall-clock time the user saw.
 *   - `feel` (M4) is the "How did it go?" answer as filed (Sess.signal): "great", "ok" or
 *     "too much", EMPTY when nobody answered. A session answered on an earlier build, whose
 *     summary asked "How did it feel?" instead, still exports the word it had ("easy",
 *     "fine", "tough") - the column's name and place are unchanged, only its values grew.
 *   - The Tissue response test's columns (M4) are seconds and a percentage, plain numbers
 *     like every raw column. A fill time the test did not produce is EMPTY, and so is the
 *     change whenever the summary shows none (Tau#sessionDeltaPct: one end missing, or two
 *     pulls that did not start from or reach the same pressure) - a spreadsheet can never
 *     find a change the app itself refused to state.
 */
public final class Export {

    private Export() { }

    /** Must equal the AndroidManifest &lt;provider android:authorities&gt; for
     *  ExportProvider — kept here, pure, so the self-test can pin it. Deliberately a
     *  THIRD authority: not the log provider's (which must never serve measurements) and
     *  not the capture provider's (which only ever writes one scratch file). */
    public static final String AUTHORITY = "org.openpump.export";

    /** The one directory ExportProvider serves: getFilesDir()/DIR_NAME. App-internal, so
     *  nothing else on the phone can read it; a recipient reaches a file only through the
     *  per-share read grant on the exact Uri the app passed. */
    public static final String DIR_NAME = "export";

    /** The filename shapes one export produces — STAMPED per run, never constant.
     *  Constant names were the original design ("exports are shared copies, not
     *  history") but a constant name is exactly what lets a still-live Uri grant from an
     *  EARLIER share resolve to a LATER, broader export: a Gmail draft holding
     *  report.pdf would read whatever report.pdf the NEXT export wrote — potentially one
     *  with photos in it that the first recipient was never offered. Every run therefore
     *  stamps its own names (LogPaths' own yyyyMMdd-HHmmss grammar), the sheet still
     *  deletes the whole dir before writing, and doExport() revokes the previous names'
     *  grants on top — three independent reasons a stale grant resolves to nothing. */
    public static final String SESSIONS_PREFIX = "sessions-";
    public static final String READINGS_PREFIX = "readings-";
    public static final String REPORT_PREFIX   = "report-";
    public static final String CSV_SUFFIX = ".csv";
    public static final String PDF_SUFFIX = ".pdf";

    /** The stamp one export run carries in all its filenames: LogPaths.STAMP's exact
     *  shape (yyyyMMdd-HHmmss), built from Calendar fields so it stays pure and
     *  timezone-explicit like {@link #isoLocal}. */
    public static String stamp(long ts, TimeZone tz) {
        GregorianCalendar c = new GregorianCalendar(tz);
        c.setTimeInMillis(ts);
        return pad(c.get(GregorianCalendar.YEAR), 4)
             + pad(c.get(GregorianCalendar.MONTH) + 1, 2)
             + pad(c.get(GregorianCalendar.DAY_OF_MONTH), 2) + "-"
             + pad(c.get(GregorianCalendar.HOUR_OF_DAY), 2)
             + pad(c.get(GregorianCalendar.MINUTE), 2)
             + pad(c.get(GregorianCalendar.SECOND), 2);
    }

    public static String sessionsName(long ts, TimeZone tz) {
        return SESSIONS_PREFIX + stamp(ts, tz) + CSV_SUFFIX;
    }
    public static String readingsName(long ts, TimeZone tz) {
        return READINGS_PREFIX + stamp(ts, tz) + CSV_SUFFIX;
    }
    public static String reportName(long ts, TimeZone tz) {
        return REPORT_PREFIX + stamp(ts, tz) + PDF_SUFFIX;
    }

    /**
     * The MIME type ExportProvider answers for a name, or null for "not this provider's
     * to serve". Only the three shapes the writers above actually produce are admitted:
     * sessions-&lt;stamp&gt;.csv, readings-&lt;stamp&gt;.csv, report-&lt;stamp&gt;.pdf —
     * writer and gatekeeper defined in terms of each other, the LogPaths discipline.
     * Everything else — a photo name, a log name, the model JSON, a name with a
     * separator, an UNSTAMPED legacy name, a right prefix under the wrong suffix — is
     * null, and the provider treats null as DENY. Photos never travel as files through
     * this provider at all: the per-export photo opt-in embeds them as pages INSIDE the
     * PDF report, so the narrow list is not a gap, it is the boundary.
     */
    public static String mimeFor(String name) {
        if (!LogPaths.isBareFileName(name)) return null;
        if (isExportName(name, SESSIONS_PREFIX, CSV_SUFFIX)) return "text/csv";
        if (isExportName(name, READINGS_PREFIX, CSV_SUFFIX)) return "text/csv";
        if (isExportName(name, REPORT_PREFIX, PDF_SUFFIX))   return "application/pdf";
        return null;
    }

    private static boolean isExportName(String name, String prefix, String suffix) {
        if (!name.startsWith(prefix) || !name.endsWith(suffix)) return false;
        int start = prefix.length(), end = name.length() - suffix.length();
        if (end <= start) return false;
        return isStamp(name.substring(start, end));
    }

    /** yyyyMMdd-HHmmss: eight digits, a hyphen, six digits — LogPaths#isStamp's exact
     *  rule, restated here because that one is private to its own gate. SelfTest pins
     *  the two grammars against the same examples so they cannot drift. */
    private static boolean isStamp(String s) {
        if (s.length() != 15) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i == 8) { if (c != '-') return false; }
            else if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /* ------------------------------------------------------------ the period */

    public static final int PERIOD_MONTH = 0, PERIOD_3_MONTHS = 1, PERIOD_ALL = 2;

    /** The inclusive start of a period, epoch millis: the first instant of the current
     *  month, of the month two before it (a "last 3 months" window is the current month
     *  and the two whole months preceding it), or 0 for everything. */
    public static long periodStart(int period, long now, TimeZone tz) {
        if (period == PERIOD_ALL) return 0L;
        GregorianCalendar c = new GregorianCalendar(tz);
        c.setTimeInMillis(now);
        c.set(GregorianCalendar.DAY_OF_MONTH, 1);
        c.set(GregorianCalendar.HOUR_OF_DAY, 0);
        c.set(GregorianCalendar.MINUTE, 0);
        c.set(GregorianCalendar.SECOND, 0);
        c.set(GregorianCalendar.MILLISECOND, 0);
        if (period == PERIOD_3_MONTHS) c.add(GregorianCalendar.MONTH, -2);
        return c.getTimeInMillis();
    }

    public static String periodLabel(int period) {
        if (period == PERIOD_MONTH) return "This month";
        if (period == PERIOD_3_MONTHS) return "Last 3 months";
        return "Everything";
    }

    /* ------------------------------------------------------------- timestamps */

    /** Local ISO-8601 without a formatting library: the Calendar fields, zero-padded.
     *  No zone suffix — this is the wall-clock time the user's screens showed. */
    public static String isoLocal(long ts, TimeZone tz) {
        GregorianCalendar c = new GregorianCalendar(tz);
        c.setTimeInMillis(ts);
        return pad(c.get(GregorianCalendar.YEAR), 4) + "-"
             + pad(c.get(GregorianCalendar.MONTH) + 1, 2) + "-"
             + pad(c.get(GregorianCalendar.DAY_OF_MONTH), 2) + "T"
             + pad(c.get(GregorianCalendar.HOUR_OF_DAY), 2) + ":"
             + pad(c.get(GregorianCalendar.MINUTE), 2) + ":"
             + pad(c.get(GregorianCalendar.SECOND), 2);
    }

    private static String pad(int v, int width) {
        StringBuilder s = new StringBuilder(String.valueOf(v));
        while (s.length() < width) s.insert(0, '0');
        return s.toString();
    }

    /* -------------------------------------------------------------- the CSVs */

    /** The exact header rows — pinned in SelfTest, because a silently reordered column
     *  under a stable header is the worst spreadsheet bug there is.
     *
     *  sessions.csv carries `completed` and `comparable` because without them a
     *  stopped-early session and a "not like-for-like" delta (every screen dims it and
     *  says so — Summary.TONE_DIM) exported byte-identical to validated, completed ones.
     *  `comparable` is EMPTY when there is no after-measurement at all: with nothing
     *  measured there is nothing to be comparable, and false there would read as a
     *  judgement that was never made.
     *
     *  readings.csv used to export Reading.label under a column named `view` — a DAY
     *  label ("Aug 19"), not a view. The three has_* booleans are the real per-view
     *  facts (a Front/Side/Top photo exists on this reading); the label column is gone
     *  because ts_iso already carries the date. The photos themselves stay out of every
     *  CSV — they travel only inside the PDF, behind their own confirm. */
    /** THE RAW COLUMNS ARE ALWAYS THE STORED UNIT, and the display columns sit BESIDE
     *  them rather than replacing them. `*_kpa` and `*_cm` mean exactly what they say
     *  whatever the app is set to show, so a file exported in inches and a file exported
     *  in centimetres are the same numbers and can be concatenated; `*_display` plus
     *  `display_unit`/`size_unit` carry what the person was actually reading, so a
     *  spreadsheet can quote it back to them in the unit they think in. Rewriting the raw
     *  column into the display unit instead would make the column NAME a lie and silently
     *  break every file already exported. */
    public static final String SESSIONS_HEADER =
        "ts_iso,routine,duration_s,completed,peak_kpa,peak_display,display_unit,"
      + "dose_kpa_s,expansion_len_cm,expansion_gir_cm,"
      + "expansion_len_display,expansion_gir_display,size_unit,comparable,feel,note,"
      + "fill_time_before_s,fill_time_after_s,fill_time_change_pct";
    /* M4 - THE TISSUE RESPONSE TEST's three columns go at the END, after note: a file read
     * by column position keeps every column it had, and the new ones are simply more. */
    /* HOW EACH READING WAS TAKEN - three columns at the END (the data-integrity review,
     * item 2), so a file read by column position keeps every column it had:
     *   method  STD, or the at-rest code: BPEL, NBPEL, BPSSL, BPSL, NBPSL, MSEG, MSSG
     *           (empty for a code this version does not know)
     *   phase   before / after (a session), empty when not said
     *   kind    standardised - a hold recorded (hold_kpa); at_rest - an at-rest method;
     *           no_hold - on the Std line with no hold recorded (a skipped or ended hold, a
     *           Std row an older version filed at rest): never at rest, never standardised
     * Only readings taken the same way are compared (the owner's rule); these say which way. */
    public static final String READINGS_HEADER =
        "ts_iso,len_cm,gir_cm,len_display,gir_display,size_unit,"
      + "hold_kpa,observed_kpa,has_front,has_side,has_top,method,phase,kind";

    private static final String CRLF = "\r\n";

    /**
     * M4 - WHAT A SESSION'S `feel` CELL SAYS, and the PDF table's FEEL column with it (one
     * derivation for both). The summary's easy / fine / tough question is gone; "How did it
     * go?" is the one feel question, filed as Sess.signal. So the column carries that answer -
     * "great", "ok", "too much" - and is empty only when nobody answered (SIGNAL_NONE is
     * "never asked", never "great").
     *
     * AN OLD ROW EXPORTS WHAT IT HAD. A session answered on an earlier build carries a
     * non-zero `feel`, which this build can no longer set, so its presence marks a row filed
     * before the change: that row keeps the word it always exported, even where it also has a
     * signal.
     */
    public static String feelWord(Model.Sess s) {
        if (s == null) return "";
        String old = feelWord(s.feel);
        if (old.length() > 0) return old;
        if (s.signal == Model.Sess.SIGNAL_GREEN) return "great";
        if (s.signal == Model.Sess.SIGNAL_ORANGE) return "ok";
        if (s.signal == Model.Sess.SIGNAL_RED) return "too much";
        return "";
    }

    /** The word a pre-M4 feel value exports as. 0 is EMPTY — nobody answered, which is not
     *  "fine" — matching Sess.feel's own rule. */
    public static String feelWord(int feel) {
        if (feel == 1) return "easy";
        if (feel == 2) return "fine";
        if (feel == 3) return "tough";
        return "";
    }

    /** One row per session, in the order given (the caller passes its period window,
     *  oldest first, so the file reads top-to-bottom in time). Display columns follow
     *  `m.unit`; Fmt.unit is set from it for the formatting and restored afterwards, so
     *  building an export never moves the global display unit under a live screen. */
    public static String sessionsCsv(Model m, List<Model.Sess> sessions) {
        return sessionsCsv(m, sessions, TimeZone.getDefault());
    }

    public static String sessionsCsv(Model m, List<Model.Sess> sessions, TimeZone tz) {
        String savedUnit = Model.Fmt.unit;
        String savedSize = Model.Fmt.sizeUnit;
        Model.Fmt.unit = m.unit;
        Model.Fmt.sizeUnit = m.sizeUnit;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(SESSIONS_HEADER).append(CRLF);
            if (sessions != null) for (int i = 0; i < sessions.size(); i++) {
                Model.Sess s = sessions.get(i);
                sb.append(field(isoLocal(s.ts, tz))).append(',');
                sb.append(field(s.manual ? "Manual"
                        : (s.routineName == null ? "" : s.routineName))).append(',');
                sb.append(s.durSec).append(',');
                sb.append(s.completed).append(',');
                // peak and dose together: a run that produced NO real reading has no
                // peak AND no measured dose — empty, never a fabricated 0.
                if (s.peakKpa == null) {
                    sb.append(',').append(',').append(field(m.unit)).append(',').append(',');
                } else {
                    double pk = s.peakKpa.doubleValue();
                    sb.append(num(pk)).append(',');
                    sb.append(field(Model.Fmt.p(pk))).append(',');
                    sb.append(field(m.unit)).append(',');
                    sb.append(num(s.doseKpaS)).append(',');
                }
                sb.append(s.afterLenCm == null ? "" : num(s.afterLenCm.doubleValue())).append(',');
                sb.append(s.afterGirCm == null ? "" : num(s.afterGirCm.doubleValue())).append(',');
                // The same two changes as the person read them. Empty where the raw column
                // is empty: nothing measured is nothing to restate.
                sb.append(s.afterLenCm == null ? ""
                        : field(Model.Fmt.lenDelta(s.afterLenCm.doubleValue()))).append(',');
                sb.append(s.afterGirCm == null ? ""
                        : field(Model.Fmt.lenDelta(s.afterGirCm.doubleValue()))).append(',');
                sb.append(field(m.sizeUnit == null ? "cm" : m.sizeUnit)).append(',');
                // comparable: EMPTY when nothing was measured after — never a
                // false-by-default that would read as a like-for-like judgement.
                sb.append(s.afterLenCm == null && s.afterGirCm == null
                        ? "" : String.valueOf(s.afterComparable)).append(',');
                sb.append(feelWord(s)).append(',');   // M4: "How did it go?", or the old word
                sb.append(field(s.note == null ? "" : s.note)).append(',');
                // The Tissue response test: before and after in seconds, and the change the
                // summary states - empty wherever the summary states none.
                sb.append(s.tauBeforeSec == null ? "" : num(s.tauBeforeSec.doubleValue())).append(',');
                sb.append(s.tauAfterSec == null ? "" : num(s.tauAfterSec.doubleValue())).append(',');
                Double change = Tau.sessionDeltaPct(s);
                sb.append(change == null ? "" : num(change.doubleValue()));
                sb.append(CRLF);
            }
            return sb.toString();
        } finally {
            Model.Fmt.unit = savedUnit;
            Model.Fmt.sizeUnit = savedSize;
        }
    }

    /** The given readings, in the order given — {@link #readingsInPeriod} hands them over
     *  OLDEST FIRST, the same order sessions.csv reads in, and the same window the sheet
     *  and the share message name. Taking the list rather than reaching into the log is
     *  what makes the file honour the chosen period: it used to dump every reading ever
     *  logged under a sheet that said "Period: This month".
     *
     *  hold/observed are raw kPa, empty when null: unknown is never 0. len/gir
     *  (raw and display alike) follow the SAME rule now: a metric the reading's method
     *  never measured (Model.Reading#measuredLength/measuredGirth) is an empty cell, not
     *  the primitive double's 0.0 default masquerading as a real measurement. The at-rest
     *  hard/soft state axis carries no column here at all — it is a comparability
     *  classification the app derives from the method, not a fact this export states. */
    public static String readingsCsv(Model m, List<Model.Reading> readings) {
        return readingsCsv(m, readings, TimeZone.getDefault());
    }

    public static String readingsCsv(Model m, List<Model.Reading> readings, TimeZone tz) {
        // The display columns are written in the MODEL's unit, not in whatever a screen
        // left the static on — the same borrow-and-restore sessionsCsv makes for pressure.
        String savedSize = Model.Fmt.sizeUnit;
        if (m != null && m.sizeUnit != null) Model.Fmt.sizeUnit = m.sizeUnit;
        try {
        StringBuilder sb = new StringBuilder();
        sb.append(READINGS_HEADER).append(CRLF);
        if (readings != null) for (int i = 0; i < readings.size(); i++) {
            Model.Reading r = readings.get(i);
            sb.append(field(isoLocal(r.ts, tz))).append(',');
            // AN EMPTY CELL MEANS "NOT MEASURED" (the review, item 3): the method must
            // measure it AND a value must be there - a half-filled Std reading left its
            // length at the 0.0 default and exported len_cm=0.
            boolean hasLen = lenMeasured(r), hasGir = girMeasured(r);
            sb.append(hasLen ? num(r.len) : "").append(',');
            sb.append(hasGir ? num(r.gir) : "").append(',');
            // ...and the same two, as the person reads them. The raw columns above are
            // centimetres whatever this says — see the header note.
            sb.append(hasLen ? field(Model.Fmt.lenNum(r.len)) : "").append(',');
            sb.append(hasGir ? field(Model.Fmt.lenNum(r.gir)) : "").append(',');
            sb.append(field(m == null || m.sizeUnit == null ? "cm" : m.sizeUnit)).append(',');
            sb.append(r.holdKpa == null ? "" : num(r.holdKpa.doubleValue())).append(',');
            sb.append(r.observedKpa == null ? "" : num(r.observedKpa.doubleValue())).append(',');
            sb.append(hasPhoto(r.photoFront)).append(',');
            sb.append(hasPhoto(r.photoSide)).append(',');
            sb.append(hasPhoto(r.photoTop)).append(',');
            sb.append(methodCode(r.method)).append(',');
            sb.append(phaseWord(r.phase)).append(',');
            sb.append(kindWord(r));
            sb.append(CRLF);
        }
        return sb.toString();
        } finally {
            Model.Fmt.sizeUnit = savedSize;
        }
    }

    /** Whether `r` has a length to export: its method measures length AND one was entered.
     *  A length is never 0 - the 0.0 is the unset default, not a measurement. */
    public static boolean lenMeasured(Model.Reading r) {
        return r != null && r.measuredLength() && r.len > 0;
    }

    /** The girth counterpart of {@link #lenMeasured}. */
    public static boolean girMeasured(Model.Reading r) {
        return r != null && r.measuredGirth() && r.gir > 0;
    }

    /** readings.csv's `method`: STD, or the at-rest method's code; empty for a code this
     *  version does not know - never guessed into one it does. */
    public static String methodCode(int method) {
        if (method == Model.Reading.METHOD_STANDARDIZED) return "STD";
        if (Model.Reading.isAtRestMethod(method)) return Model.Reading.methodLabel(method);
        return "";
    }

    /** readings.csv's `phase`: before / after a session, empty when it was not said. */
    public static String phaseWord(int phase) {
        if (phase == Model.Reading.PHASE_PRE) return "before";
        if (phase == Model.Reading.PHASE_POST) return "after";
        return "";
    }

    /** readings.csv's `kind`: standardised (a hold recorded), at_rest (an at-rest method),
     *  or no_hold - on the Std line with no hold recorded (Meas#markedApart), the reading
     *  the trend draws apart; never written as at rest. */
    public static String kindWord(Model.Reading r) {
        if (Model.Reading.isStandardised(r)) return "standardised";
        if (Model.Reading.isAtRestMethod(r.method)) return "at_rest";
        return "no_hold";
    }

    /** Whether a view slot holds a real capture — the same test the PDF's photo pages
     *  apply before embedding one, so the CSV's claim and the PDF's contents agree. */
    public static boolean hasPhoto(Model.Reading.Photo p) {
        return p != null && p.path != null && p.path.length() > 0;
    }

    /* ------------------------------------------------------------- the atoms */

    /**
     * RFC 4180 quoting: quote a field that contains a comma, a double quote, or a line
     * break, doubling embedded quotes. Anything else passes through untouched — quoting
     * everything would be legal but makes the file needlessly hostile to `cut`/`awk`.
     */
    public static String field(String f) {
        if (f == null) return "";
        boolean needs = false;
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') { needs = true; break; }
        }
        if (!needs) return f;
        return "\"" + f.replace("\"", "\"\"") + "\"";
    }

    /** A raw number column: plain digits, Locale.US decimal point, up to three decimals
     *  with trailing zeros trimmed — "34.0 kPa" the display column's business, "34" this
     *  one's. Never a unit string. */
    public static String num(double v) {
        String s = String.format(Locale.US, "%.3f", v);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    /** The sessions inside a period window, OLDEST FIRST — the order both CSV rows and
     *  the PDF's table read in. Input is the stored log (newest first). */
    public static List<Model.Sess> inPeriod(List<Model.Sess> newestFirst, long fromTs) {
        List<Model.Sess> out = new ArrayList<Model.Sess>();
        if (newestFirst != null)
            for (int i = newestFirst.size() - 1; i >= 0; i--)
                if (newestFirst.get(i).ts >= fromTs) out.add(newestFirst.get(i));
        return out;
    }

    /** The readings inside a period window, OLDEST FIRST — what the PDF's trend chart
     *  plots. Same shape as {@link #inPeriod} for the same reason. */
    public static List<Model.Reading> readingsInPeriod(List<Model.Reading> newestFirst,
                                                       long fromTs) {
        List<Model.Reading> out = new ArrayList<Model.Reading>();
        if (newestFirst != null)
            for (int i = newestFirst.size() - 1; i >= 0; i--)
                if (newestFirst.get(i).ts >= fromTs) out.add(newestFirst.get(i));
        return out;
    }
}
