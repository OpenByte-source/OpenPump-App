package org.openpump;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RE-CHECK A LOG SOMEBODY SENT. Reads a session journal (session-*.txt from Settings ›
 * Export debug log), replays every TAP/NAV/CMD/ACK/RX/PMP/UI/LINK line through a fresh
 * {@link JournalWatch}, and prints what it finds - so a log sent months ago is judged by
 * today's rules, and a rule change can be tried against real logs before it ships.
 *
 *   ./gradlew :core:journalCheck -Pfile=path/to/session-20260923-101500.txt
 *
 * The CHK lines already in the file are what the phone found at the time; they are counted
 * and listed separately, never fed back in.
 *
 * A DESKTOP TOOL, SO IT LIVES IN THE TEST SOURCE SET. It used to sit in core/src/main, which
 * the app compiles in, so a class with a main() and a file reader shipped in the release
 * APK (release checklist B7: no test or desktop classes in the APK). The journalCheck task
 * and JournalCheckTest both run on the test classpath, so nothing that uses it moved;
 * DesktopToolsStayOutTest keeps it here.
 */
public final class JournalCheck {

    private JournalCheck() { }

    /** The outcome of one replay. */
    public static final class Result {
        public final List<String> findings = new ArrayList<String>();
        public final List<String> original = new ArrayList<String>();
        public final List<String> summary = new ArrayList<String>();
        public int lines, events, skipped;
    }

    public static Result check(Reader in) throws IOException {
        BufferedReader r = new BufferedReader(in);
        JournalWatch w = new JournalWatch();
        Result res = new Result();
        List<String> out = new ArrayList<String>();
        long last = 0;
        String line;
        while ((line = r.readLine()) != null) {
            res.lines++;
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            int sp1 = line.indexOf(' ');
            if (sp1 < 0) { res.skipped++; continue; }
            long t = Journal.parseStamp(line.substring(0, sp1));
            if (t < 0) { res.skipped++; continue; }
            int sp2 = line.indexOf(' ', sp1 + 1);
            String tag = sp2 < 0 ? line.substring(sp1 + 1) : line.substring(sp1 + 1, sp2);
            String detail = sp2 < 0 ? "" : line.substring(sp2 + 1);
            if ("CHK".equals(tag)) { res.original.add(line); continue; }
            res.events++;
            // Time also passes between lines: tick through it so a timeout lands when it
            // would have on the phone, not at the next line.
            for (long s = last + 500; s < t; s += 500) {
                w.tick(s, out);
                drain(s, out, res);
            }
            last = t;
            w.onLine(t, tag, detail, out);
            drain(t, out, res);
        }
        // No extrapolation past the last line: whatever was still pending when the log
        // ended (an ack in flight, a pull under way) is undecided, not a finding.
        res.summary.addAll(w.summary());
        return res;
    }

    private static void drain(long t, List<String> out, Result res) {
        for (int i = 0; i < out.size(); i++)
            res.findings.add(Journal.stamp(t) + " CHK " + out.get(i));
        out.clear();
    }

    public static Result check(String text) throws IOException {
        return check(new StringReader(text));
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args[0].length() == 0) {
            System.err.println("usage: journalCheck -Pfile=<session-*.txt>");
            System.exit(2);
        }
        Result res;
        try (Reader in = new InputStreamReader(new FileInputStream(args[0]), StandardCharsets.UTF_8)) {
            res = check(in);
        }
        System.out.println("journalCheck " + args[0]);
        System.out.println(res.lines + " lines, " + res.events + " events replayed, "
            + res.skipped + " unreadable");
        System.out.println();
        System.out.println("Findings on replay (" + res.findings.size() + "):");
        for (int i = 0; i < res.findings.size(); i++) System.out.println("  " + res.findings.get(i));
        System.out.println();
        System.out.println("Found on the phone at the time (" + res.original.size() + "):");
        for (int i = 0; i < res.original.size(); i++) System.out.println("  " + res.original.get(i));
        System.out.println();
        for (int i = 0; i < res.summary.size(); i++) System.out.println(res.summary.get(i));
    }
}
