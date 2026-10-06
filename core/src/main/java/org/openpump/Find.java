package org.openpump;

import java.util.Locale;

/**
 * WHAT A TYPED QUERY MATCHES. The whole of the Settings search that can be reasoned about
 * without a screen, kept here so it can be pinned by the desktop harness rather than only
 * by trying it on a phone.
 *
 * The rule is every word, in any order, as a substring. Three decisions worth stating:
 *
 * WORDS, NOT A PHRASE. The search used to compare one substring, so "app lock" found the
 * category and "lock app" found nothing - which is not a difference anybody typing from
 * memory means to express.
 *
 * AND, NOT OR. Each extra word narrows. An OR would widen the result the more precisely a
 * person described what they wanted, which is exactly backwards.
 *
 * SUBSTRING, NOT WHOLE WORD. "reminde" finds "reminder" while it is still being typed, and
 * the filter runs on every keystroke.
 */
public final class Find {
    private Find() { }

    /** Split a raw query into lower-cased terms. Empty query, empty array - callers read
     *  that as "not searching" rather than as "matches nothing". */
    public static String[] terms(String raw) {
        if (raw == null) return new String[0];
        String q = raw.trim().toLowerCase(Locale.US);
        if (q.length() == 0) return new String[0];
        return q.split("\\s+");
    }

    /** True when EVERY term appears somewhere in `hay`, which the caller has already
     *  lower-cased. No terms is true: an empty query hides nothing. */
    public static boolean matchesAll(String hay, String[] terms) {
        if (terms == null || terms.length == 0) return true;
        if (hay == null) return false;
        for (int i = 0; i < terms.length; i++) {
            if (terms[i].length() == 0) continue;
            if (hay.indexOf(terms[i]) < 0) return false;
        }
        return true;
    }
}
