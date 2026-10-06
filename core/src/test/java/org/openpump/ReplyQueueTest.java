package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * THE PUMP'S ANSWERS NAME NO START: EACH ONE IS GIVEN EVERY START IT MAY BE FOR, AND IS ONE
 * START'S ONLY WHEN THAT IS THE ONLY ONE.
 *
 * The safety review of pump-refusal (MEDIUM): a placeholder never marked written sat at the head
 * of the queue and ate the next START's answer. The review of refusal-2 (LOW): an answer more
 * than 2.8 s late - past the window and the tombstone - shifted every pairing after it by one,
 * and a refused lowering was committed on a borrowed `01` (seed 910).
 */
class ReplyQueueTest {

    private static final long HORIZON = 5000, UNSENT = 15000;

    private static ReplyQueue<String> q() { return new ReplyQueue<String>(HORIZON, UNSENT, 16); }

    private static List<String> list(String... s) { return Arrays.asList(s); }

    @Test void anAnswerIsTheOnlyStartsItCanBe() {
        ReplyQueue<String> r = q();
        r.enqueue("a", false, 0); r.sent("a", 10);
        assertEquals(list("a"), r.answer(60));
        r.enqueue("b", false, 70); r.sent("b", 80);
        assertEquals(list("b"), r.answer(120), "a's answer came: the next can only be b's");
        assertTrue(r.quiet(130));
    }

    @Test void aLostAnswerMakesTheNextOneUncertainNotBorrowed() {
        // a's answer is lost; b's arrives. It may be a's late one: it is not b's for certain.
        ReplyQueue<String> r = q();
        r.enqueue("a", false, 0); r.sent("a", 0);
        r.enqueue("b", false, 900); r.sent("b", 900);
        assertEquals(list("a", "b"), r.answer(950));
        assertEquals(list("b"), r.answer(1000), "answers keep their order: the next is b's");
    }

    @Test void seed910ATooLateAnswerShiftsNothing() {
        // The review's seed 910: #10's answer came 3182 ms after its write, past the 800 ms window
        // and the 2 s tombstone. Paired in order, it was taken for the next START's, and every
        // pairing after it was one off. Here it may be #10's or #11's - neither is certain - and
        // the answer after it can only be #11's.
        ReplyQueue<String> r = q();
        r.enqueue("#10", false, 0); r.sent("#10", 5);
        r.enqueue("#11", false, 3000); r.sent("#11", 3005);
        assertEquals(list("#10", "#11"), r.answer(3187));
        assertEquals(list("#11"), r.answer(3250));
        assertTrue(r.answer(3300).isEmpty(), "a third answer has no START left to be for");
    }

    @Test void answersKeepTheOrderTheStartsWereWrittenIn() {
        ReplyQueue<String> r = q();
        for (String s : new String[]{ "a", "b", "c" }) { r.enqueue(s, false, 0); r.sent(s, 0); }
        assertEquals(list("a", "b", "c"), r.answer(100));
        assertEquals(list("b", "c"), r.answer(110));
        assertEquals(list("c"), r.answer(120));
    }

    @Test void aPlaceholderIsWrittenAndGoesPastTheHorizonLikeAnyStart() {
        // +30 s refreshes the adjustment: a START with no row. Its answer is lost.
        ReplyQueue<String> r = q();
        r.enqueue("placeholder", true, 0);
        r.sent("placeholder", 5);
        assertFalse(r.quiet(1000), "it may still get its late answer");
        r.enqueue("change", false, HORIZON + 100);
        r.sent("change", HORIZON + 110);
        assertEquals(list("change"), r.answer(HORIZON + 150),
            "not eaten by a placeholder left waiting for the 15 s expiry");
    }

    @Test void asShippedThePlaceholderAteTheNextAnswer() {
        // The shape the first review found: the placeholder is queued but never marked written,
        // so it met only the 15 s expiry; FIFO pairing handed it the next answer. Kept so the
        // rule above is known to be the fix.
        ArrayDeque<String[]> shipped = new ArrayDeque<String[]>();
        shipped.addLast(new String[]{ "placeholder", "discard" });
        shipped.addLast(new String[]{ "change", "note" });
        String[] head = shipped.pollFirst();               // the change's own answer, at 2.9 s
        assertEquals("placeholder", head[0], "eaten: the change is left unanswered");
    }

    @Test void anAnswerIsNeverForAStartNotYetWritten() {
        // A late answer to an earlier START, arriving while the next is still queued behind a
        // table rewrite, cannot be the queued one's.
        ReplyQueue<String> r = q();
        r.enqueue("queued", false, 0);
        assertTrue(r.answer(50).isEmpty(), "an orphan: nothing moves");
        r.sent("queued", 600);
        assertEquals(list("queued"), r.answer(650), "its own answer still finds it");
    }

    @Test void aLaterWriteRetiresAnEarlierStartThatNeverLeft() {
        ReplyQueue<String> r = q();
        r.enqueue("dropped", false, 0);                    // the stack refused it three times
        r.enqueue("next", false, 100);
        r.sent("next", 150);
        assertEquals(list("next"), r.answer(200), "writes leave in order: the first was dropped");
        assertTrue(r.quiet(210));
    }

    @Test void anAnswerPastTheHorizonIsAnOrphan() {
        ReplyQueue<String> r = q();
        r.enqueue("a", false, 0); r.sent("a", 0);
        assertEquals(list("a"), r.answer(HORIZON), "at the horizon it may still be a's");
        ReplyQueue<String> r2 = q();
        r2.enqueue("a", false, 0); r2.sent("a", 0);
        assertTrue(r2.answer(HORIZON + 1).isEmpty(), "past it, it can be no one's");
    }

    @Test void quietOnlyWhenNothingMayStillBeAnswered() {
        ReplyQueue<String> r = q();
        assertTrue(r.quiet(0));
        r.enqueue("a", false, 0);
        assertFalse(r.quiet(1), "queued, about to be written");
        r.sent("a", 2);
        assertFalse(r.quiet(3), "written, inside its window");
        assertFalse(r.quiet(2900), "past its window it may still be answered");
        assertTrue(r.quiet(HORIZON + 3), "and past the horizon nothing can");
        r.enqueue("never", false, 6000);
        assertTrue(r.quiet(6000 + UNSENT + 1), "a frame never written expires too");
        assertTrue(r.waiting("x", 0) == false);
    }
}
