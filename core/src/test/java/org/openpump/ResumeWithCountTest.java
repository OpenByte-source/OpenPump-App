package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * "COUNTED TOWARD THIS WEEK" AND RESUME, BOTH (follow-up to the live GrowthTrack test, 9 Nov
 * 2026). Answering Count showed "Counted toward this week"; the Resume offer, kept for the
 * question, came up a moment later and replaced it. One message shows at a time, so the two
 * now share one: the note before the offer's words, with Resume and its full time.
 */
class ResumeWithCountTest {
    private static final long HOLD = Snack.HOLD_MS_ACTIONABLE;

    @Test void answeredWhileTheOfferWaitsTheNoteGoesWithIt() {
        ResumeOffer<String> o = new ResumeOffer<String>();
        assertNull(o.offer("resume", true));
        assertEquals(ResumeOffer.WITH_OFFER, o.note("Counted toward this week", 1_000L));
        assertEquals("resume", o.questionGone());
        assertEquals("Counted toward this week. Stopped and vented at step 2.",
            ResumeOffer.withNote(o.takeNote(), "Stopped and vented at step 2."));
        assertNull(o.takeNote(), "once");
    }

    @Test void answeredOnTheCardWhileTheOfferIsUpItIsShownAgainWithTheNote() {
        ResumeOffer<String> o = new ResumeOffer<String>();
        assertEquals("resume", o.offer("resume", false));
        o.shown("resume", 10_000L, HOLD);
        assertEquals(ResumeOffer.AGAIN, o.note("Counted toward this week", 12_000L));
        assertSame("resume", o.again(), "shown again, with its full time from now");
        assertEquals("Counted toward this week", o.takeNote());
    }

    @Test void withNoOfferInPlayTheNoteIsShownAlone() {
        ResumeOffer<String> o = new ResumeOffer<String>();
        assertEquals(ResumeOffer.ALONE, o.note("Counted toward this week", 0L), "a plan session: no offer");
        o.offer("resume", false);
        o.shown("resume", 10_000L, HOLD);
        assertEquals(ResumeOffer.ALONE, o.note("Counted toward this week", 10_000L + HOLD), "the offer has gone");
        o.shown("resume", 20_000L, HOLD);
        o.spent();
        assertEquals(ResumeOffer.ALONE, o.note("Counted toward this week", 20_500L), "Resume was tapped");
        assertNull(o.takeNote());
    }

    @Test void theSummaryAndTheOfferUseIt() throws Exception {
        String sa = new String(Files.readAllBytes(Paths.get("../app/src/main/java/org/openpump/SessionActivity.java")),
            StandardCharsets.UTF_8);
        assertTrue(sa.contains("resumeOffer.note(counted,"), "Count's note goes through the offer");
        assertTrue(sa.contains("ResumeOffer.withNote(resumeOffer.takeNote(), message)"), "and the offer's message carries it");
        assertTrue(sa.contains("resumeOffer.shown(this,"), "the offer says when it is on screen");
    }
}
