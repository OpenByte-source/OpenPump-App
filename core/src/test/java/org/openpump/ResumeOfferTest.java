package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * THE RESUME OFFER AFTER STOP IS NOT SPENT UNDER A DIALOG (the live GrowthTrack test, 9 Nov
 * 2026, finding 1). On a routine the plan did not write, STOP asked "Count this toward
 * training?" in a dialog while the 5-second "Stopped and vented at step 1 · Resume" message
 * ran out underneath it; session e2 could not be resumed.
 */
class ResumeOfferTest {
    @Test void withNoQuestionTheOfferIsShownAtOnceAsBefore() {
        ResumeOffer<String> o = new ResumeOffer<String>();
        assertEquals("resume", o.offer("resume", false), "a plan session, or nothing to count");
        assertFalse(o.waiting());
        assertNull(o.questionGone(), "nothing kept");
    }

    @Test void withTheQuestionComingTheOfferWaitsForIt() {
        ResumeOffer<String> o = new ResumeOffer<String>();
        assertNull(o.offer("resume", true), "not shown under the dialog");
        assertTrue(o.waiting());
        assertEquals("resume", o.questionGone(), "shown, with its full time, once it is answered");
        assertNull(o.questionGone(), "once");
        assertFalse(o.waiting());
    }

    @Test void aNewerStopReplacesAnOfferStillKept() {
        ResumeOffer<String> o = new ResumeOffer<String>();
        o.offer("first", true);
        assertSame("second", o.offer("second", false));
        assertNull(o.questionGone(), "the first STOP's offer is not shown after the second");
    }

    /** SessionActivity keeps STOP's offer while the question is coming, and gives it back when
     *  the question's dialog goes (or is never put). */
    @Test void theRunScreenHoldsTheOfferUntilTheQuestionGoes() throws Exception {
        Path p = Paths.get("..", "app", "src", "main", "java", "org", "openpump", "SessionActivity.java");
        if (!Files.exists(p)) p = Paths.get("app", "src", "main", "java", "org", "openpump", "SessionActivity.java");
        String sa = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        String stop = body(sa, "private void stopWithResumeOffer()");
        assertTrue(stop.contains("resumeOffer.offer("), "STOP's offer goes through ResumeOffer");
        assertTrue(stop.contains("Snack.HOLD_MS_ACTIONABLE") || sa.contains("Snack.HOLD_MS_ACTIONABLE);"),
            "with the actionable time");
        String ask = body(sa, "private final class AskCountRunnable");
        assertTrue(ask.contains("setOnDismissListener("), "the question's dialog hands the offer back when it goes");
        assertTrue(ask.contains("resumeAfterQuestion()"), "and so does a question that is not put after all");
    }

    private static String body(String code, String head) {
        int at = code.indexOf(head);
        assertTrue(at >= 0, "found: " + head);
        int open = code.indexOf('{', at), depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return code.substring(open, i + 1);
        }
        return code.substring(open);
    }
}
