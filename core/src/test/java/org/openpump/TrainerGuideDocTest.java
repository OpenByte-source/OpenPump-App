package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * THE TRAINER GUIDE'S NUMBERS ARE THE CODE'S NUMBERS.
 *
 * docs/trainer-guide.md explains the trainer in words, and most of what it says is a number
 * the code holds as a constant. A guide that drifts from the code is worse than no guide, so
 * next to each checked number sits an HTML comment that GitHub does not show:
 *
 *     A session runs for at most 2 hours <!-- check Plan.GROSS_CAP_SEC/3600 = 2 -->.
 *
 * WHAT A MARKER CHECKS. The expression on the left is evaluated against the code - static
 * fields and static methods of org.openpump classes, or a default such as Model().rxWarmMin
 * (the field on a newly made object), with + - * / and brackets - and must
 * equal the value on the right ("=" exactly; "~" rounded to the decimals the value is written
 * with). The value must also appear, as a number, in the visible text of the same line, so a
 * marker cannot sit beside a sentence that says something else.
 *
 * A WEEK TABLE is checked row by row: a line reading
 * {@code <!-- check-table Plan.GIRTH_INTERVAL_L1 -->} is followed by a Markdown table whose
 * rows must match that array, row for row - the week, the sets, the minutes, the table's
 * pressure and which rows are deloads.
 *
 * APPENDIX A is the guide's list of every number, so there every number in a row must be
 * covered by a marker on that row, and a row with none fails.
 *
 * A malformed marker fails too: a typo must not become a check that silently passes.
 */
class TrainerGuideDocTest {

    /** The core module is the working directory for these tests (see DesktopToolsStayOutTest). */
    static final Path GUIDE = Paths.get("../docs/trainer-guide.md");

    private static final Pattern MARKER =
        Pattern.compile("<!--\\s*check\\s+(.+?)\\s*([=~])\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*-->");
    private static final Pattern ANY_CHECK = Pattern.compile("<!--\\s*check[\\s-]");
    private static final Pattern TABLE_MARKER =
        Pattern.compile("<!--\\s*check-table\\s+([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*-->");
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->");
    private static final Pattern NUMBER =
        Pattern.compile("(?<![0-9.])[0-9]+(?:\\.[0-9]+)?(?![0-9])");

    /* ============================================================== the guide */

    @Test void everyMarkerMatchesTheCode() throws IOException {
        List<String> lines = guide();
        List<String> bad = new ArrayList<String>();
        int checked = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            List<String> problems = checkLine(line);
            for (String p : problems) bad.add("line " + (i + 1) + ": " + p);
            checked += countMarkers(line);
        }
        assertTrue(bad.isEmpty(), "docs/trainer-guide.md no longer matches the code:\n  "
            + String.join("\n  ", bad));
        assertTrue(checked > 0, "no check markers found in the guide");
    }

    @Test void everyWeekTableMatchesItsArray() throws IOException {
        List<String> lines = guide();
        List<String> bad = new ArrayList<String>();
        int tables = 0;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = TABLE_MARKER.matcher(lines.get(i));
            if (!m.find()) continue;
            tables++;
            Object value = staticField(m.group(1), m.group(2));
            if (!(value instanceof Plan.Week[])) {
                bad.add("line " + (i + 1) + ": " + m.group(1) + "." + m.group(2)
                    + " is not a week table");
                continue;
            }
            List<String> rows = tableRows(lines, i + 1);
            bad.addAll(checkWeekTable((Plan.Week[]) value, rows, i + 1));
        }
        assertTrue(tables >= 2, "the guide should restate both girth week tables");
        assertTrue(bad.isEmpty(), "a week table in the guide no longer matches the code:\n  "
            + String.join("\n  ", bad));
    }

    @Test void appendixAHasAMarkerForEveryNumber() throws IOException {
        List<String> lines = guide();
        int start = -1, end = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (start < 0 && l.startsWith("## Appendix A")) { start = i; continue; }
            if (start >= 0 && l.startsWith("## ")) { end = i; break; }
        }
        assertTrue(start >= 0, "the guide has no Appendix A");
        List<String> bad = new ArrayList<String>();
        int rows = 0;
        for (int i = start; i < end; i++) {
            String l = lines.get(i).trim();
            if (!l.startsWith("|") || l.matches("\\|[\\s:|-]+\\|?")) continue;
            if (isHeader(lines, i)) continue;
            rows++;
            String problem = appendixRowProblem(l);
            if (problem != null) bad.add("line " + (i + 1) + ": " + problem);
        }
        assertTrue(rows >= 20, "Appendix A should list the trainer's numbers (found " + rows + " rows)");
        assertTrue(bad.isEmpty(), "Appendix A rows without a check for every number:\n  "
            + String.join("\n  ", bad));
    }

    /* ============================================================== the checker checks */

    /** The checker has to be able to fail, or a passing guide proves nothing. */
    @Test void aWrongValueIsCaught() {
        assertTrue(checkLine("At most 2 hours <!-- check Plan.GROSS_CAP_SEC/3600 = 2 --> a session.")
            .isEmpty());
        assertFalse(checkLine("At most 3 hours <!-- check Plan.GROSS_CAP_SEC/3600 = 3 --> a session.")
            .isEmpty(), "a value the code does not hold must fail");
        assertFalse(checkLine("At most 2 hours <!-- check Plan.GROSS_CAP_SEC/3600 = 3 --> a session.")
            .isEmpty(), "a marker disagreeing with the code must fail");
    }

    @Test void aValueMissingFromTheTextIsCaught() {
        assertFalse(checkLine("Two hours <!-- check Plan.GROSS_CAP_SEC/3600 = 2 --> at most")
            .isEmpty(), "a number written only in words is not in the sentence");
        assertTrue(checkLine("At most 2 hours <!-- check Plan.GROSS_CAP_SEC/3600 = 2 -->")
            .isEmpty());
        assertFalse(checkLine("At most 12 hours <!-- check Plan.GROSS_CAP_SEC/3600 = 2 -->")
            .isEmpty(), "2 inside 12 is not the number 2");
    }

    @Test void roundingIsToTheDecimalsWritten() {
        assertTrue(checkLine("about 50.8 kPa <!-- check Plan.ABSOLUTE_CAP_KPA ~ 50.8 -->").isEmpty());
        assertFalse(checkLine("about 50.7 kPa <!-- check Plan.ABSOLUTE_CAP_KPA ~ 50.7 -->").isEmpty());
        assertFalse(checkLine("about 50.8 kPa <!-- check Plan.ABSOLUTE_CAP_KPA = 50.8 -->").isEmpty(),
            "= means exact; a rounded figure needs ~");
    }

    @Test void methodsAndArithmeticEvaluate() {
        assertEquals(28.0, eval("Plan.netMilestoneMin(4)"), 1e-9);
        assertEquals(4.0, eval("Plan.returnTaperHg(0)"), 1e-9);
        assertEquals(15.0, eval("Plan.ABSOLUTE_CAP_KPA / Plan.HG"), 1e-9);
        assertEquals(-2.0, eval("-(1 + 1)"), 1e-9);
        assertEquals(6.0, eval("(Plan.DELOAD_REPORT_MAX_DAYS - 3) / 3"), 1e-9);
        assertEquals(4.0, eval("Model().rxWarmMin"), 1e-9);
        assertEquals(1.0, eval("Model$Reading.COMPARABLE_TOLERANCE_KPA"), 1e-9);
    }

    @Test void aMalformedMarkerFails() {
        assertFalse(checkLine("2 hours <!-- check Plan.NO_SUCH_FIELD = 2 -->").isEmpty());
        assertFalse(checkLine("2 hours <!-- check Plan.GROSS_CAP_SEC/ = 2 -->").isEmpty());
        assertFalse(checkLine("2 hours <!-- check Plan.GROSS_CAP_SEC -->").isEmpty(),
            "a check with no value is a typo, not a comment");
    }

    @Test void anAppendixRowNeedsEveryNumberChecked() {
        assertEquals(null, appendixRowProblem(
            "| Longest session | 2 h <!-- check Plan.GROSS_CAP_SEC/3600 = 2 --> |"));
        assertTrue(appendixRowProblem("| Longest session | 2 h |") != null);
        assertTrue(appendixRowProblem(
            "| Feeder | 2 a day <!-- check Plan.FEEDER_PER_DAY = 2 -->, 10 min |") != null,
            "10 has no marker");
    }

    /* ============================================================== the machinery */

    private static List<String> guide() throws IOException {
        assertTrue(Files.isRegularFile(GUIDE),
            "docs/trainer-guide.md not found from " + Paths.get("").toAbsolutePath());
        return Files.readAllLines(GUIDE, StandardCharsets.UTF_8);
    }

    private static int countMarkers(String line) {
        int n = 0;
        Matcher m = MARKER.matcher(line);
        while (m.find()) n++;
        return n;
    }

    /** Every problem with the markers on one line; empty when they all hold. */
    static List<String> checkLine(String line) {
        List<String> out = new ArrayList<String>();
        int wellFormed = 0;
        Matcher m = MARKER.matcher(line);
        String visible = COMMENT.matcher(line).replaceAll(" ");
        while (m.find()) {
            wellFormed++;
            String expr = m.group(1), op = m.group(2), stated = m.group(3);
            double actual;
            try {
                actual = eval(expr);
            } catch (RuntimeException e) {
                out.add("cannot evaluate \"" + expr + "\": " + e.getMessage());
                continue;
            }
            double want = Double.parseDouble(stated);
            boolean same;
            if (op.equals("=")) {
                same = Math.abs(actual - want) <= 1e-9 * Math.max(1.0, Math.abs(want));
            } else {
                int dp = stated.indexOf('.') < 0 ? 0 : stated.length() - stated.indexOf('.') - 1;
                double scale = Math.pow(10, dp);
                same = Math.round(actual * scale) == Math.round(want * scale);
            }
            if (!same)
                out.add(expr + " is " + actual + " in the code, the guide says " + stated);
            if (!visibleHas(visible, want))
                out.add("the value " + stated + " (" + expr + ") is not in the visible text");
        }
        int any = 0;
        Matcher a = ANY_CHECK.matcher(line);
        while (a.find()) any++;
        int tables = TABLE_MARKER.matcher(line).find() ? 1 : 0;
        if (any > wellFormed + tables)
            out.add("a check marker that does not parse: " + line.trim());
        return out;
    }

    private static boolean visibleHas(String visible, double want) {
        Matcher n = NUMBER.matcher(visible);
        while (n.find()) {
            if (Math.abs(Double.parseDouble(n.group()) - Math.abs(want)) < 1e-9) return true;
        }
        return false;
    }

    /** Null when every number in the row's value cells is covered by a marker on the row. */
    static String appendixRowProblem(String row) {
        if (countMarkers(row) == 0) return "no check marker: " + row;
        List<Double> stated = new ArrayList<Double>();
        Matcher m = MARKER.matcher(row);
        while (m.find()) stated.add(Math.abs(Double.parseDouble(m.group(3))));
        String visible = COMMENT.matcher(row).replaceAll(" ");
        String[] cells = visible.split("\\|");
        // cells[0] is before the first bar and cells[1] is the label; numbers there are names.
        for (int c = 2; c < cells.length; c++) {
            Matcher n = NUMBER.matcher(cells[c]);
            while (n.find()) {
                double v = Double.parseDouble(n.group());
                boolean covered = false;
                for (double s : stated) if (Math.abs(s - v) < 1e-9) { covered = true; break; }
                if (!covered) return "the number " + n.group() + " has no check: " + row;
            }
        }
        for (String p : checkLine(row)) return p;
        return null;
    }

    private static boolean isHeader(List<String> lines, int i) {
        return i + 1 < lines.size() && lines.get(i + 1).trim().matches("\\|[\\s:|-]+\\|?");
    }

    /** The body rows of the Markdown table starting at or after `from`. */
    private static List<String> tableRows(List<String> lines, int from) {
        List<String> rows = new ArrayList<String>();
        int i = from;
        while (i < lines.size() && !lines.get(i).trim().startsWith("|")) i++;
        if (i + 1 >= lines.size()) return rows;
        i += 2;                                           // header and separator
        while (i < lines.size() && lines.get(i).trim().startsWith("|")) rows.add(lines.get(i++));
        return rows;
    }

    /**
     * Columns: week | sets | minutes at pressure | the table's pressure, hg | anything else.
     * A deload row says "deload" in the sets column and leaves the next two empty.
     */
    private static List<String> checkWeekTable(Plan.Week[] table, List<String> rows, int at) {
        List<String> bad = new ArrayList<String>();
        if (rows.size() != table.length) {
            bad.add("table at line " + at + " has " + rows.size() + " rows, the code has "
                + table.length);
            return bad;
        }
        for (int r = 0; r < rows.size(); r++) {
            String[] c = COMMENT.matcher(rows.get(r)).replaceAll(" ").split("\\|");
            Plan.Week w = table[r];
            String where = "table at line " + at + ", row " + (r + 1) + ": ";
            if (c.length < 5) { bad.add(where + "too few columns"); continue; }
            if (parseInt(c[1]) != w.num) bad.add(where + "week " + c[1].trim() + ", code " + w.num);
            boolean deload = c[2].toLowerCase().contains("deload");
            if (deload != w.deload) {
                bad.add(where + "deload " + deload + ", code " + w.deload);
                continue;
            }
            if (deload) continue;
            if (parseInt(c[2]) != w.sets) bad.add(where + "sets " + c[2].trim() + ", code " + w.sets);
            if (Math.abs(parseD(c[3]) - w.netTupMin) > 1e-9)
                bad.add(where + "minutes " + c[3].trim() + ", code " + w.netTupMin);
            double[] p = range(c[4]);
            if (Math.abs(p[0] - w.pressHgLo) > 1e-9 || Math.abs(p[1] - w.pressHgHi) > 1e-9)
                bad.add(where + "pressure " + c[4].trim() + ", code " + w.pressHgLo + "-"
                    + w.pressHgHi);
        }
        return bad;
    }

    private static int parseInt(String s) {
        Matcher n = NUMBER.matcher(s);
        return n.find() ? (int) Math.round(Double.parseDouble(n.group())) : Integer.MIN_VALUE;
    }

    private static double parseD(String s) {
        Matcher n = NUMBER.matcher(s);
        return n.find() ? Double.parseDouble(n.group()) : Double.NaN;
    }

    /** "5" is {5, 5}; "5–6" (either dash) is {5, 6}. */
    private static double[] range(String s) {
        Matcher n = NUMBER.matcher(s);
        double lo = n.find() ? Double.parseDouble(n.group()) : Double.NaN;
        double hi = n.find() ? Double.parseDouble(n.group()) : lo;
        return new double[]{ lo, hi };
    }

    /* ---- a small expression evaluator: numbers, Class.FIELD, Class.method(args), + - * / ( ) */

    static double eval(String expr) {
        Parser p = new Parser(expr);
        double v = p.expr();
        p.skip();
        if (p.pos != p.s.length()) throw new IllegalArgumentException("unexpected \""
            + p.s.substring(p.pos) + "\"");
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;
        Parser(String s) { this.s = s; }

        void skip() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

        boolean take(char c) {
            skip();
            if (pos < s.length() && s.charAt(pos) == c) { pos++; return true; }
            return false;
        }

        double expr() {
            double v = term();
            while (true) {
                if (take('+')) v += term();
                else if (take('-')) v -= term();
                else return v;
            }
        }

        double term() {
            double v = unary();
            while (true) {
                if (take('*')) v *= unary();
                else if (take('/')) v /= unary();
                else return v;
            }
        }

        double unary() {
            if (take('-')) return -unary();
            return primary();
        }

        double primary() {
            skip();
            if (take('(')) {
                double v = expr();
                if (!take(')')) throw new IllegalArgumentException("missing )");
                return v;
            }
            if (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                int st = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.'))
                    pos++;
                return Double.parseDouble(s.substring(st, pos));
            }
            String cls = ident();
            if (take('(')) {
                // Model().rxWarmMin - a DEFAULT: the field as a fresh instance holds it.
                if (!take(')') || !take('.'))
                    throw new IllegalArgumentException("expected Class().field at " + pos);
                return defaultField(cls, ident());
            }
            if (!take('.')) throw new IllegalArgumentException("expected Class.member at " + pos);
            String member = ident();
            if (take('(')) {
                List<Double> args = new ArrayList<Double>();
                if (!take(')')) {
                    do { args.add(expr()); } while (take(','));
                    if (!take(')')) throw new IllegalArgumentException("missing ) after arguments");
                }
                return call(cls, member, args);
            }
            Object v = staticField(cls, member);
            if (v instanceof Number) return ((Number) v).doubleValue();
            throw new IllegalArgumentException(cls + "." + member + " is not a number");
        }

        String ident() {
            skip();
            int st = pos;
            // '$' names a nested class: Model$Reading.COMPARABLE_TOLERANCE_KPA.
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos))
                   || s.charAt(pos) == '_' || s.charAt(pos) == '$')) pos++;
            if (st == pos) throw new IllegalArgumentException("expected a name at " + pos);
            return s.substring(st, pos);
        }
    }

    private static Class<?> cls(String name) {
        try {
            return Class.forName("org.openpump." + name);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("no class org.openpump." + name);
        }
    }

    static Object staticField(String c, String f) {
        try {
            Field fld = cls(c).getField(f);
            if (!Modifier.isStatic(fld.getModifiers()))
                throw new IllegalArgumentException(c + "." + f + " is not static");
            return fld.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalArgumentException("no public static field " + c + "." + f);
        }
    }

    /** A public instance field's value on a newly constructed object - the app's default. */
    static double defaultField(String c, String f) {
        try {
            Object o = cls(c).getConstructor().newInstance();
            Object v = cls(c).getField(f).get(o);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof Boolean) return ((Boolean) v) ? 1.0 : 0.0;
            throw new IllegalArgumentException(c + "()." + f + " is not a number");
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("no public default " + c + "()." + f);
        }
    }

    private static double call(String c, String name, List<Double> args) {
        for (Method m : cls(c).getMethods()) {
            if (!m.getName().equals(name) || !Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] types = m.getParameterTypes();
            if (types.length != args.size()) continue;
            Object[] a = new Object[types.length];
            boolean ok = true;
            for (int i = 0; i < types.length && ok; i++) {
                double v = args.get(i);
                if (types[i] == int.class) a[i] = Integer.valueOf((int) Math.round(v));
                else if (types[i] == long.class) a[i] = Long.valueOf(Math.round(v));
                else if (types[i] == double.class) a[i] = Double.valueOf(v);
                else if (types[i] == boolean.class) a[i] = Boolean.valueOf(v != 0.0);
                else ok = false;
            }
            if (!ok) continue;
            try {
                Object r = m.invoke(null, a);
                if (r instanceof Number) return ((Number) r).doubleValue();
                if (r instanceof Boolean) return ((Boolean) r) ? 1.0 : 0.0;
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(c + "." + name + " failed: " + e);
            }
        }
        throw new IllegalArgumentException("no public static " + c + "." + name + " taking "
            + args.size() + " number(s)");
    }
}
