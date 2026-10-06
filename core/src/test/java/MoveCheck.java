import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PROVES THAT A MOVE WAS A MOVE.
 *
 * The SessionActivity split (docs/superpowers/plans/2026-09-07-sessionactivity-split.md)
 * moves thousands of lines at a time between files, and its one rule is that nothing
 * changes on the way. That is a rule nobody can check by reading a 5,000-line diff: git
 * reports every moved line as a deletion and an addition, so a single altered string
 * literal is one line in five thousand that looks exactly like the other 4,999.
 *
 * This compares the MULTISET of normalised lines across two copies of the package. A line
 * that was moved appears in both and cancels. A line that was CHANGED appears once on each
 * side in two different forms, and both are printed.
 *
 * Usage:  java -cp desktoptest/out MoveCheck &lt;beforeDir&gt; &lt;afterDir&gt;
 * Exit 0 and "MOVE CLEAN" when the two sides hold the same lines; exit 1 otherwise.
 */
public final class MoveCheck {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("usage: MoveCheck <beforeDir> <afterDir>");
            System.exit(2);
        }
        Map<String, Integer> before = tally(args[0]);
        Map<String, Integer> after = tally(args[1]);

        List<String> gone = new ArrayList<String>();
        List<String> added = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int n = e.getValue().intValue() - count(after, e.getKey());
            for (int i = 0; i < n; i++) gone.add(e.getKey());
        }
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            int n = e.getValue().intValue() - count(before, e.getKey());
            for (int i = 0; i < n; i++) added.add(e.getKey());
        }
        java.util.Collections.sort(gone);
        java.util.Collections.sort(added);
        for (int i = 0; i < gone.size(); i++)  System.out.println("- " + gone.get(i));
        for (int i = 0; i < added.size(); i++) System.out.println("+ " + added.get(i));

        // THE VERDICT IS THE `gone` SIDE, AND ONLY THAT SIDE. A split necessarily WRITES
        // lines that did not exist before - a package line, an import block, a class
        // header, a delegating stub - so demanding an empty `added` would make every
        // honest move read as dirty and the tool would say nothing. A line that was
        // CHANGED, though, has to leave its old form behind, and its old form lands in
        // `gone`. So: `gone` empty means nothing was lost or altered, and the added lines
        // are printed above for a person to recognise as scaffolding.
        boolean clean = gone.isEmpty();
        System.out.println(clean
            ? "MOVE CLEAN (+" + added.size() + " new lines, listed above)"
            : "MOVE DIRTY (" + gone.size() + " gone, " + added.size() + " added)");
        System.exit(clean ? 0 : 1);
    }

    private static int count(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        return v == null ? 0 : v.intValue();
    }

    /** Every .java directly under `dir`, as a line multiset. Non-recursive, matching the
     *  one flat package this project keeps its sources in. */
    private static Map<String, Integer> tally(String dir) throws IOException {
        Map<String, Integer> m = new HashMap<String, Integer>();
        File[] fs = new File(dir).listFiles();
        if (fs == null) throw new IOException("no such directory: " + dir);
        for (int i = 0; i < fs.length; i++) {
            if (!fs[i].getName().endsWith(".java")) continue;
            String raw = new String(Files.readAllBytes(fs[i].toPath()), "UTF-8");
            String[] lines = raw.split("\n", -1);
            for (int j = 0; j < lines.length; j++) {
                String s = norm(lines[j]);
                if (s.length() == 0) continue;
                m.put(s, Integer.valueOf(count(m, s) + 1));
            }
        }
        return m;
    }

    /**
     * A line is "the same line" across a move when only its indentation and the way it
     * names the Activity have changed: `body` becomes `a.body`, `SessionActivity.this`
     * becomes `a`, an implicit `this.` becomes explicit. Those are undone here so a moved
     * line matches itself.
     *
     * OUTSIDE STRING LITERALS ONLY, and that is the whole point. The first real move this
     * tool checked had rewritten `body` to `a.body` INSIDE three sentences of prose - "not
     * a forecast about a a.body" - because the rename was a regex over the whole file. A
     * normaliser that forgave the rename everywhere would have called that a clean move and
     * the sentence would have shipped. So the rename is undone in CODE and left alone in
     * TEXT: what a person reads on screen must survive a move character for character.
     */
    private static String norm(String s) {
        s = s.trim();
        // A DECLARATION THE MOVE HAD TO WIDEN. The screen class reads members that were
        // `private` to the Activity, so the compiler makes the split drop that one word -
        // on the declaration line and nowhere else. Undoing it here is the same kind of
        // rewrite as `SessionActivity.this` -> `a`: mechanical, forced, and not a change
        // to what the line does.
        if (s.startsWith("private ")) s = s.substring(8);
        StringBuilder b = new StringBuilder();
        boolean inStr = false;
        char quote = '"';
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!inStr && (c == '"' || c == '\'')) {
                inStr = true; quote = c; b.append(c); continue;
            }
            if (inStr) {
                if (c == '\\' && i + 1 < s.length()) { b.append(c).append(s.charAt(++i)); continue; }
                if (c == quote) inStr = false;
                b.append(c);
                continue;
            }
            if (starts(s, i, "SessionActivity.this.")) { i += 20; continue; }
            // A TYPE OR CONSTANT THAT STAYED BEHIND. `Tap.MEASHIST` inside the Activity is
            // `SessionActivity.Tap.MEASHIST` from a screen class, and a nested type named
            // bare is named `SessionActivity.X` once it is read from outside. Same rewrite,
            // same reason, and still only outside string literals.
            if (starts(s, i, "SessionActivity.") && !starts(s, i, "SessionActivity.this")
                    && (i == 0 || !isIdent(s.charAt(i - 1)))) { i += 15; continue; }
            if (starts(s, i, "SessionActivity.this"))  { i += 19; b.append('a'); continue; }
            if (starts(s, i, "this."))                 { i += 4;  continue; }
            if (starts(s, i, "a.") && (i == 0 || !isIdent(s.charAt(i - 1)))) { i += 1; continue; }
            if (starts(s, i, "this") && (i == 0 || !isIdent(s.charAt(i - 1)))
                    && (i + 4 >= s.length() || !isIdent(s.charAt(i + 4)))) {
                i += 3; b.append('a'); continue;
            }
            b.append(c);
        }
        return b.toString().trim();
    }

    private static boolean starts(String s, int at, String what) {
        return s.startsWith(what, at);
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
    }
}
