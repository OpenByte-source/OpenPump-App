package org.openpump;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** ExportScreen, lifted out of SessionActivity verbatim - see
 *  docs/superpowers/plans/2026-09-07-sessionactivity-split.md. A screen class
 *  renders into the Activity's own body column and owns no state. */
final class ExportScreen {
    private final SessionActivity a;

    ExportScreen(SessionActivity a) { this.a = a; }

    private final class BackFromExportTap implements View.OnClickListener {
        @Override public void onClick(View v) { a.showSessionHistory(); }
    }

    private final class ExPeriodTap implements View.OnClickListener {
        private final int p;
        ExPeriodTap(int p) { this.p = p; }
        @Override public void onClick(View v) { a.exPeriod = p; show(); }
    }

    /* PR-22: THE EXPORT'S PERIODS ARE PROGRESS'S - 6W / 3M / 6M / 1Y / All, the same
     * rolling windows (Meas#periodCutoff), so the two screens agree about what "3M" holds.
     * The Activity's exPeriod holds the window in days; it starts at 0 (it was "This month"
     * before), which is read as the 3M default, and "All" is kept as ALL_DAYS. */
    private static final int ALL_DAYS = -1;
    private static final int[] WINDOWS = { Meas.PERIOD_6W, Meas.PERIOD_3M, Meas.PERIOD_6M,
                                           Meas.PERIOD_1Y, ALL_DAYS };
    private static final String[] WINDOW_TAGS = { "6W", "3M", "6M", "1Y", "All" };
    private static final String[] WINDOW_WORDS = { "Last 6 weeks", "Last 3 months",
        "Last 6 months", "Last year", "Everything" };

    /** The chosen window's index in WINDOWS - 3M until one is picked. */
    private int windowIndex() {
        for (int i = 0; i < WINDOWS.length; i++) if (WINDOWS[i] == a.exPeriod) return i;
        return 1;
    }

    /** Where the chosen window starts: Progress's own rolling cutoff, or 0 for All. */
    private long windowStart(long now) {
        int w = WINDOWS[windowIndex()];
        return w == ALL_DAYS ? 0L : Meas.periodCutoff(w, now);
    }

    private String windowWords() { return WINDOW_WORDS[windowIndex()]; }

    private final class ExFormatTap implements View.OnClickListener {
        private final int f;
        ExFormatTap(int f) { this.f = f; }
        @Override public void onClick(View v) { a.exFormat = f; show(); }
    }

    /** Ticking photos ON asks first — its own confirm, separate from the Export tap,
     *  stating plainly that the photos will be in the file. Ticking OFF just unticks. */
    private final class ExPhotosTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.exPhotos) { a.exPhotos = false; show(); return; }
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Include photos?")
                .setMessage("Your progress photos will be included in the PDF report as "
                    + "full pages. Anyone the file is sent to will see them.")
                .setPositiveButton("Include photos", new ExPhotosConfirm())
                .setNegativeButton("Keep them out", null)
                .show());
        }
    }

    private final class ExPhotosConfirm implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) { a.exPhotos = true; show(); }
    }

    private final class RunExportTap implements View.OnClickListener {
        @Override public void onClick(View v) { doExport(); }
    }

    void show() {
        a.body.removeAllViews();
        a.enterDest(Nav.SCR_EXPORT);
        a.header(a.body, "Export", new BackFromExportTap());
        // TASK 6 — "Settings & data export", the SAME scope showSettings() asks — this is
        // the door into it that actually lives under Progress rather than Settings (see
        // this method's own class-level doc a few lines up for why), so it gates on the
        // scope its NAME belongs to rather than the tab it happens to be reached from.
        // The header (with its own working back arrow) is drawn BEFORE this check, unlike
        // renderMeasHist()'s gate, so a locked visit still has a named way out besides the
        // lock card's own "Unlock" retry.
        if (a.appLockGate(AppLock.SETTINGS, new Runnable() {
                @Override public void run() { show(); }
            })) return;

        // PR-22 / PR-24: the app's field labels and its own controls - a segmented switch
        // for each one-of-several, a real switch for photos - where ●/○ rows, a ☑ glyph
        // and a second section-label style were.
        Ui.fieldLabel(a, a.body, "Period", null);
        View.OnClickListener[] periodTaps = new View.OnClickListener[WINDOWS.length];
        for (int i = 0; i < WINDOWS.length; i++) periodTaps[i] = new ExPeriodTap(WINDOWS[i]);
        Ui.segmented(a, a.body, WINDOW_TAGS, WINDOW_WORDS, windowIndex(), periodTaps);

        Ui.fieldLabel(a, a.body, "Format", null);
        Ui.segmented(a, a.body, new String[]{ "CSV", "PDF report", "Both" }, null, a.exFormat,
            new View.OnClickListener[]{ new ExFormatTap(0), new ExFormatTap(1),
                                        new ExFormatTap(2) });

        Ui.kvRow(a, a.body, "Include progress photos in the PDF report", a.exPhotos,
                 new ExPhotosTap());
        if (a.exPhotos && a.exFormat == 0)
            Ui.note(a, a.body, "Photos travel only inside the PDF report — choose PDF or "
                + "Both for them to be included.");

        // PR-23: WHAT IS IN IT, as one row a file - the column-by-column paragraph (also
        // the share's message text) is behind the label's ⓘ.
        boolean csv = a.exFormat != 1, pdf = a.exFormat != 0;
        LinearLayout what = Ui.cardGroup(a, a.body, "What's in it", null, "What's in it",
                                         exportContents());
        Ui.kvRow(a, what, "Sessions CSV", csv ? "✓" : "not included", Ui.TEXT, null);
        Ui.kvRow(a, what, "Readings CSV", csv ? "✓" : "not included", Ui.TEXT, null);
        Ui.kvRow(a, what, "PDF report", pdf ? "✓" : "not included", Ui.TEXT, null);
        Ui.kvRow(a, what, "Photos", a.exPhotos && pdf ? "included" : "not included",
                 Ui.TEXT, null);
        if (what.getChildCount() > 0) what.removeViewAt(what.getChildCount() - 1);
        Ui.note(a, a.body, "These files carry your measurements"
            + (a.exPhotos && pdf ? " and photos" : "") + " — that is what an export is for.");

        // PR-24: an empty period says so BEFORE the tap, on a button that is off.
        long from = windowStart(System.currentTimeMillis());
        boolean empty = Export.inPeriod(a.model.sessLog.all, from).isEmpty()
            && Export.readingsInPeriod(a.model.measLog.all, from).isEmpty();
        Button go = Ui.big(a, a.body, "Export and share", Ui.ACCENT);
        go.setOnClickListener(new RunExportTap());
        if (empty) {
            Ui.setEnabled(a, go, false, Ui.ACCENT);
            Ui.note(a, a.body, "Nothing to export in this period — choose a longer one.");
        }
        Ui.noteInfo(a, a.body, "The files are written into this app's private storage.",
            "Export", "The files are written into this app's private storage and "
            + "handed to the share sheet you pick a recipient on. Nothing is sent anywhere "
            + "until you pick one. Each new export replaces the previous export's files.");
    }

    /** The plain sentence naming EXACTLY what the export will contain, shown on the sheet
     *  before anything is written and repeated as the share's message text — so what the
     *  user read and what the recipient is told are one string that cannot drift. */
    private String exportContents() {
        boolean csv = a.exFormat != 1, pdf = a.exFormat != 0;
        StringBuilder s = new StringBuilder("This export will contain: ");
        if (csv)
            s.append("a sessions CSV — one row per session in the period (date, routine, "
                + "duration, whether it completed, peak and dose pressure, measured "
                + "change in cm and whether it was like-for-like, how it went, your "
                + "note, and the " + TauSay.NAME + "'s fill time before and after and "
                + "the change) — and a readings CSV — the measurements you logged in this "
                // NO "state" COLUMN. It was removed from READINGS_HEADER - the at-rest
                // hard/soft axis is a classification the app derives, not a fact the file
                // states - and this consent text went on promising it, which is the one
                // kind of drift a consent screen may not have.
                + "period (date, length and girth in cm, hold and observed pressure, "
                + "and whether a POV/side/top photo exists — never the photos "
                + "themselves)");
        if (csv && pdf) s.append("; and ");
        if (pdf)
            s.append("a PDF report — summary numbers, the measurement trend, expansion "
                + "per session and a session table for the period"
                + (a.exPhotos ? ", with your progress photos as pages" : ", with no photos"));
        s.append(". Period: ").append(windowWords())
         .append(". These files carry your measurements")
         .append(a.exPhotos && pdf ? " and photos" : "")
         .append(" — that is what an export is for. The debug log (Settings) never "
                + "contains measurements or photos.");
        return s.toString();
    }

    /** content://org.openpump.export/&lt;name&gt; — the Uri ExportProvider resolves. */
    private Uri exportUri(String name) {
        return new Uri.Builder().scheme("content").authority(ExportProvider.AUTHORITY)
            .appendPath(name).build();
    }

    private void writeExportFile(File f, String text) throws Exception {
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try { w.write(text); } finally { w.close(); }
    }

    /** Revokes any still-live grant on a previous export's Uris (the same call
     *  CameraScreen#revokeGrant makes on its scratch Uri) and deletes the files. Run at
     *  the TOP of every export AND on a failed one: a Gmail draft or an upload queue can
     *  hold a grant long after the chooser closed, and the grant must die before this
     *  run writes anything — the file's name being freshly stamped is the second guard,
     *  and its bytes being gone is the third. */
    private void clearExportDir(File dir) {
        File[] old = dir.listFiles();
        if (old != null) for (int i = 0; i < old.length; i++) {
            try {
                a.revokeUriPermission(exportUri(old[i].getName()),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            old[i].delete();
        }
    }

    private void doExport() {
        long now = System.currentTimeMillis();
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        long from = windowStart(now);
        List<Model.Sess> oldestFirst = Export.inPeriod(a.model.sessLog.all, from);
        File dir = new File(a.getFilesDir(), Export.DIR_NAME);
        dir.mkdirs();
        // OLD EXPORT FILES: previous grants revoked, then everything deleted, every
        // time — they are shared copies, not history (the history lives in the model).
        // With the per-run stamped names below, a stale grant from an earlier share now
        // fails three independent ways: the grant is revoked, the old name is never
        // written again, and its bytes are gone.
        clearExportDir(dir);
        boolean csv = a.exFormat != 1, pdf = a.exFormat != 0;
        ArrayList<Uri> uris = new ArrayList<Uri>();
        try {
            if (csv) {
                String sn = Export.sessionsName(now, tz);
                String rn = Export.readingsName(now, tz);
                writeExportFile(new File(dir, sn),
                                Export.sessionsCsv(a.model, oldestFirst));
                writeExportFile(new File(dir, rn),
                                Export.readingsCsv(a.model,
                                    Export.readingsInPeriod(a.model.measLog.all, from)));
                uris.add(exportUri(sn));
                uris.add(exportUri(rn));
            }
            if (pdf) {
                String pn = Export.reportName(now, tz);
                writePdfReport(new File(dir, pn), oldestFirst, from, now, a.exPhotos);
                uris.add(exportUri(pn));
            }
        } catch (Throwable e) {
            // Throwable, not Exception: an OutOfMemoryError during PDF composition (a
            // photo page is the likely culprit) is an Error and sailed straight past the
            // old catch, crashing the app. And whatever went wrong, a HALF-WRITTEN file
            // must not sit in the dir for the next share to offer — clear it out.
            try { clearExportDir(dir); } catch (Throwable ignored) { }
            a.toast("Export failed — nothing was written or shared");
            a.log("!! export failed: " + e);
            return;
        }
        shareExport(uris, csv, pdf);
    }

    /** H1 - the export's share, asked again once a hold's vent is confirmed. The files are
     *  already written; only the hand-over waited. */
    private final class ShareExportAgain implements Runnable {
        private final ArrayList<Uri> uris;
        private final boolean csv, pdf;
        ShareExportAgain(ArrayList<Uri> u, boolean c, boolean p) { uris = u; csv = c; pdf = p; }
        @Override public void run() { shareExport(uris, csv, pdf); }
    }

    /** shareLog()'s launch pattern, copied exactly (see its defect-A note): the chooser
     *  carries FLAG_ACTIVITY_NO_USER_ACTION, and ownLaunchPending is set AFTER the launch
     *  returns so a throw can never strand it true. WiringCheck invariant 10 pins both. */
    private void shareExport(ArrayList<Uri> uris, boolean csv, boolean pdf) {
        if (uris.isEmpty()) { a.toast("Nothing to export"); return; }
        // H1 - the share sheet is another app: a hold on the cuff vents first (ventFirst).
        if (a.ventFirst(HoldHandOff.SHARE, null, new ShareExportAgain(uris, csv, pdf), null))
            return;
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            i.setType(pdf ? "application/pdf" : "text/csv");
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            i.setType(csv && pdf ? "*/*" : (pdf ? "application/pdf" : "text/csv"));
        }
        i.putExtra(Intent.EXTRA_SUBJECT, Incognito.exportSubject(Incognito.identity(a.model)));
        i.putExtra(Intent.EXTRA_TEXT, exportContents());
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(i, "Send the export")
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION));
            // AFTER the launch, never before it — the same ordering as shareLog(), for
            // the same reason, pinned by the same invariant.
            a.ownLaunchPending = true;
            a.log("export share sheet opened: " + uris.size() + " file(s), period "
                + windowWords() + ", photos " + (a.exPhotos ? "in" : "out"));
        } catch (Exception e) {
            a.toast("Nothing on this phone can send a file");
            a.log("!! export share failed: " + e);
        }
    }

    /** Page bookkeeping for the report: the document, the open page, a cursor. Pages are
     *  A4 in POINTS while every View draws in PIXELS via Ui.dp — drawView() bridges the
     *  two with canvas.scale(1/density), so a chart measured EXACTLY at (pt × density)
     *  pixels lands on the page at point size, unclipped. */
    private final class PdfPen {
        final android.graphics.pdf.PdfDocument doc = new android.graphics.pdf.PdfDocument();
        android.graphics.pdf.PdfDocument.Page page;
        Canvas c;
        float y;
        int pageNo;

        /** Opens the next page, or refuses at the cap. The footer carries the page number
         *  and the app's name so a printed loose sheet still says what it is. */
        boolean newPage() {
            if (pageNo >= a.PDF_PAGE_CAP) return false;
            closePage();
            pageNo++;
            page = doc.startPage(new android.graphics.pdf.PdfDocument.PageInfo
                    .Builder(a.PDF_W, a.PDF_H, pageNo).create());
            c = page.getCanvas();
            c.drawColor(Look.PRINT_GROUND);
            Paint foot = pdfPaint(Look.PRINT_DIM, 8f, false);
            c.drawText("Pump report — page " + pageNo, a.PDF_M, a.PDF_H - a.PDF_M / 2f, foot);
            y = a.PDF_M;
            return true;
        }

        /** Room for `h` more points on this page, opening a new one if needed. False
         *  means the cap is reached and the caller must stop adding content. */
        boolean ensure(float h) {
            if (y + h <= a.PDF_H - a.PDF_M) return true;
            return newPage();
        }

        /**
         * A LINE THAT STILL PRINTS WHEN THE PAGE CAP HAS BEEN REACHED.
         *
         * The note explaining that older sessions were dropped was itself written through
         * ensure() - which had just returned false, because the cap being reached is the
         * only way that note becomes true. So the one report that needed the caveat was the
         * only report that never carried it, and a capped PDF read as a complete one.
         *
         * It goes in the bottom margin, on the line above the page number, where there is
         * always room because nothing else is ever drawn there.
         */
        void margined(String s, Paint p) {
            if (c != null) c.drawText(s, a.PDF_M, a.PDF_H - a.PDF_M + 8f, p);
        }

        /** One line of text at the cursor. `size` is also the advance, plus leading. */
        void line(String s, Paint p, float size) {
            c.drawText(s, a.PDF_M, y + size, p);
            y += size + size * 0.45f;
        }

        void gap(float h) { y += h; }

        /** Draws a View at the cursor, `hPt` points tall and content-width: measured
         *  EXACTLY in pixels, laid out, then drawn through scale(1/density) so nothing
         *  is clipped to a pixel-sized page. */
        void drawView(View v, float hPt) {
            float density = a.getResources().getDisplayMetrics().density;
            int wPx = Math.round((a.PDF_W - 2 * a.PDF_M) * density);
            int hPx = Math.round(hPt * density);
            v.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
                      View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY));
            v.layout(0, 0, wPx, hPx);
            c.save();
            c.translate(a.PDF_M, y);
            c.scale(1f / density, 1f / density);
            v.draw(c);
            c.restore();
            y += hPt;
        }

        void closePage() { if (page != null) { doc.finishPage(page); page = null; } }

        void writeTo(File f) throws Exception {
            closePage();
            FileOutputStream fo = new FileOutputStream(f);
            try { doc.writeTo(fo); } finally { fo.close(); doc.close(); }
        }
    }

    private Paint pdfPaint(int colour, float size, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colour);
        p.setTextSize(size);
        if (bold) p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return p;
    }

    /** Decodes a photo at roughly the page's own resolution — inSampleSize halving until
     *  the next halving would undershoot the target — so a 12-megapixel capture does not
     *  balloon the PDF nor the heap. Null when the file is gone or unreadable. */
    private android.graphics.Bitmap decodeForPdf(String path, int targetW) {
        try {
            android.graphics.BitmapFactory.Options bounds =
                new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2;
            android.graphics.BitmapFactory.Options opt =
                new android.graphics.BitmapFactory.Options();
            opt.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(path, opt);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** The report: header with the period and the display unit, the summary numbers, the
     *  trend chart, the expansion bars, a table of the period's sessions, and — only when
     *  the user separately confirmed it — the photos, one to a page. Print palette
     *  throughout (Look.PRINT_*): dark ink on white, because a page is paper. */
    private void writePdfReport(File out, List<Model.Sess> oldestFirst, long fromTs,
                                long now, boolean withPhotos) throws Exception {
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        PdfPen pen = new PdfPen();
        pen.newPage();
        Paint title = pdfPaint(Look.PRINT_INK, 20f, true);
        Paint h2    = pdfPaint(Look.PRINT_INK, 12f, true);
        Paint prose = pdfPaint(Look.PRINT_INK, 10f, false);
        Paint dim   = pdfPaint(Look.PRINT_DIM, 9f, false);

        pen.line("Pump report", title, 20f);
        pen.line(windowWords() + "  ·  pressures shown in " + a.model.unit
            + "  ·  generated " + Export.isoLocal(now, tz), dim, 9f);
        pen.gap(8f);

        // ---- summary numbers -------------------------------------------------------
        int completed = 0; long totalSec = 0; Double bestPeak = null; int measured = 0;
        for (int i = 0; i < oldestFirst.size(); i++) {
            Model.Sess s = oldestFirst.get(i);
            if (s.completed) completed++;
            totalSec += s.durSec;
            if (s.peakKpa != null && (bestPeak == null
                    || s.peakKpa.doubleValue() > bestPeak.doubleValue()))
                bestPeak = s.peakKpa;
            if (s.afterLenCm != null || s.afterGirCm != null) measured++;
        }
        List<Model.Reading> window = Export.readingsInPeriod(a.model.measLog.all, fromTs);
        pen.line("SUMMARY", h2, 12f);
        pen.line(oldestFirst.size() + " sessions  ·  " + completed + " completed  ·  "
            + Model.Fmt.t(totalSec) + " total time under the pump", prose, 10f);
        pen.line((bestPeak == null ? "no pressure reading in this period"
                : "deepest pressure " + Model.Fmt.p(bestPeak.doubleValue()))   // PR-24
            + "  ·  " + measured + " sessions with an after-measurement  ·  "
            + window.size() + " readings logged", prose, 10f);
        pen.gap(10f);

        // ---- the trend chart -------------------------------------------------------
        // TWO PLOTS, ONE PER METRIC, since the chart draws one series at a time now (see
        // TrendChart). A page has room for both, and unlike the screen it has no chooser —
        // printing only length would silently drop half the record from an exported report.
        if (window.size() >= 2 && pen.ensure(12f + 150f + 14f)) {
            pen.line("LENGTH TREND", h2, 12f);
            SessionActivity.TrendChart chart = a.new TrendChart(a, true);
            chart.setMetric(a.METRIC_LEN);
            chart.setData(window, now);
            pen.drawView(chart, 150f);
            pen.line("length, scaled to its own range — " + Model.Fmt.lenUnit()
                     + (a.model.goalBpsslCm == null ? ""
                        : ".  The dashed line runs from the first reading toward your goal "
                          + "at the capped rate."), dim, 9f);
            pen.gap(8f);
        }
        if (window.size() >= 2 && pen.ensure(12f + 150f + 14f)) {
            pen.line("GIRTH TREND", h2, 12f);
            SessionActivity.TrendChart gChart = a.new TrendChart(a, true);
            gChart.setMetric(a.METRIC_GIR);
            gChart.setData(window, now);
            pen.drawView(gChart, 150f);
            pen.line("girth, scaled to its own range — " + Model.Fmt.lenUnit()
                     + (a.model.goalMsegCm == null && a.model.goalMssgCm == null ? ""
                        : ".  The dashed line runs from the first reading toward your goal "
                          + "at the capped rate."), dim, 9f);
            pen.gap(8f);
        }

        // ---- expansion per session -------------------------------------------------
        // Only LIKE-FOR-LIKE deltas are plotted: Summary.deltaTone dims a
        // non-comparable delta (TONE_DIM) on every screen that prints one, and a bar
        // chart has no dim — a bar IS a claim of measured growth, so a delta the app
        // itself refuses to colour as growth must not become one here.
        List<Model.Sess> newestFirst = new ArrayList<Model.Sess>();
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            Model.Sess s = oldestFirst.get(i);
            if (Insight.expansionCm(s) != null && !s.afterComparable) continue;
            newestFirst.add(s);
        }
        double[] series = Insight.expansionSeries(newestFirst);
        if (series.length > 0 && pen.ensure(12f + 60f + 14f)) {
            pen.line("EXPANSION PER SESSION", h2, 12f);
            pen.drawView(a.new ExpansionBars(a, series, true), 60f);
            pen.line("after − before, cm, oldest first — sessions with no after-measurement "
                + "or a not-like-for-like one are not shown", dim, 9f);
            pen.gap(8f);
        }

        // ---- the session table ------------------------------------------------------
        if (pen.ensure(12f + 24f)) {
            pen.line("SESSIONS", h2, 12f);
            float[] cols = { 0f, 100f, 240f, 300f, 380f, 460f };
            Paint th = pdfPaint(Look.PRINT_DIM, 8f, true);
            String[] heads = { "DATE", "ROUTINE", "TIME", "PEAK", "DOSE", "FEEL" };
            for (int k = 0; k < heads.length; k++)
                pen.c.drawText(heads[k], a.PDF_M + cols[k], pen.y + 8f, th);
            pen.y += 12f;
            Paint td = pdfPaint(Look.PRINT_INK, 9f, false);
            Paint rule = new Paint();
            rule.setColor(Look.PRINT_LINE);
            boolean truncated = false, anyStopped = false;
            for (int i = oldestFirst.size() - 1; i >= 0; i--) {   // newest at the top
                if (!pen.ensure(13f)) { truncated = true; break; }
                Model.Sess s = oldestFirst.get(i);
                String name = s.manual ? "Manual"
                    : (s.routineName == null ? "" : s.routineName);
                if (name.length() > 26) name = name.substring(0, 25) + "…";
                pen.c.drawLine(a.PDF_M, pen.y, a.PDF_W - a.PDF_M, pen.y, rule);
                float base = pen.y + 10f;
                if (!s.completed) anyStopped = true;
                pen.c.drawText(Export.isoLocal(s.ts, tz).substring(0, 10), a.PDF_M + cols[0], base, td);
                pen.c.drawText(name, a.PDF_M + cols[1], base, td);
                // A stopped-early session is MARKED, not exported look-alike: the CSV
                // says it in the `completed` column, the table says it here.
                pen.c.drawText(Model.Fmt.t(s.durSec) + (s.completed ? "" : " †"),
                               a.PDF_M + cols[2], base, td);
                pen.c.drawText(s.peakKpa == null ? "—"
                    : Model.Fmt.p(s.peakKpa.doubleValue()), a.PDF_M + cols[3], base, td);
                pen.c.drawText(s.peakKpa == null ? "—"
                    : Model.Fmt.dose(s.doseKpaS), a.PDF_M + cols[4], base, td);
                String feel = Export.feelWord(s);   // M4: the CSV's own cell
                pen.c.drawText(feel.length() == 0 ? "—" : feel, a.PDF_M + cols[5], base, td);
                pen.y += 13f;
            }
            String cut = "… older sessions omitted — the report is capped at "
                + a.PDF_PAGE_CAP + " pages; the CSV export carries everything";
            if (truncated) {
                // The two notes share the one margin line when both are true: the dagger is
                // only worth explaining if a dagger was printed, and the cut always is.
                pen.margined(anyStopped ? "† stopped before the routine finished  ·  " + cut
                                        : cut, dim);
            } else {
                if (anyStopped && pen.ensure(11f))
                    pen.line("† stopped before the routine finished", dim, 9f);
            }
        }

        // ---- photos, ONLY behind the separate confirm -------------------------------
        if (withPhotos) appendPhotoPages(pen, window, tz);

        pen.writeTo(out);
    }

    /** One photo per page, oldest first, capped by the page budget: each of the period's
     *  readings contributes its Front/Side/Top captures in that order. Reached ONLY from
     *  writePdfReport with the per-export photo opt-in true — the sheet's own confirm
     *  dialog is the single gate, and nothing here runs without it. */
    private void appendPhotoPages(PdfPen pen, List<Model.Reading> window,
                                  java.util.TimeZone tz) {
        Paint cap = pdfPaint(Look.PRINT_DIM, 9f, false);
        Paint h2 = pdfPaint(Look.PRINT_INK, 12f, true);
        for (int i = 0; i < window.size(); i++) {
            Model.Reading r = window.get(i);
            Model.Reading.Photo[] shots = { r.photoFront, r.photoSide, r.photoTop };
            // Labels only — the stored slots are photoFront/photoSide/photoTop, unchanged.
            String[] views = { Shot.label(Shot.FRONT), Shot.label(Shot.SIDE), Shot.label(Shot.TOP) };
            for (int v = 0; v < shots.length; v++) {
                Model.Reading.Photo ph = shots[v];
                if (ph == null || ph.path == null || ph.path.length() == 0) continue;
                // The target width is CAPPED at 1024 px whatever the screen density: the
                // page is 515 pt wide, so 1024 px is already ~2× print resolution, and an
                // uncapped (pt × density) target on a 3×+ screen decoded photos big
                // enough to threaten the heap across a many-photo report.
                android.graphics.Bitmap bmp = decodeForPdf(ph.path,
                        Math.min(1024, Math.round((a.PDF_W - 2 * a.PDF_M)
                                   * a.getResources().getDisplayMetrics().density)));
                if (bmp == null) continue;
                if (!pen.newPage()) { bmp.recycle(); return; }     // the cap is the cap
                pen.line("PHOTO — " + views[v], h2, 12f);
                // Point 19 and the owner's rule: how the PHOTO was taken (Compare#photoKind) -
                // standardised, held or at rest - not inferred from its reading or from
                // whether a pressure number happened to be recorded.
                pen.line(Export.isoLocal(r.ts, tz) + "  ·  " + Compare.photoTakenLine(r, ph),
                         cap, 9f);
                pen.gap(6f);
                float maxW = a.PDF_W - 2 * a.PDF_M;
                float maxH = a.PDF_H - a.PDF_M - pen.y - 16f;
                float scale = Math.min(maxW / bmp.getWidth(), maxH / bmp.getHeight());
                float dw = bmp.getWidth() * scale, dh = bmp.getHeight() * scale;
                android.graphics.RectF dst = new android.graphics.RectF(
                    a.PDF_M + (maxW - dw) / 2f, pen.y,
                    a.PDF_M + (maxW - dw) / 2f + dw, pen.y + dh);
                pen.c.drawBitmap(bmp, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
                bmp.recycle();
            }
        }
    }
}
