package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * THE GROWTHTRACK SCREEN REFRESHES WHEN A BACKGROUND SEND FINISHES (the live GrowthTrack test,
 * 9 Nov 2026, finding 9): the open status card and the open "Sent and waiting sessions" kept
 * "1 waiting · sends when online" for about two minutes after the job had sent the session.
 */
class GrowthTrackWatchTest {
    private static final class Screen implements GrowthTrackWatch.Watcher {
        int told;
        @Override public void syncChanged() { told++; }
    }

    @Test void aScreenOnDisplayIsToldOnceForEachPassThatFinishes() {
        GrowthTrackWatch w = new GrowthTrackWatch();
        Screen open = new Screen();
        w.watch(open); w.watch(open);
        w.passFinished();
        assertEquals(1, open.told, "once per pass, however often it asked");
        w.passFinished();
        assertEquals(2, open.told);
        w.unwatch(open);
        w.passFinished();
        assertEquals(2, open.told, "off screen, told nothing");
        assertEquals(0, w.watching());
    }

    @Test void aWatcherMayLeaveWhileItIsTold() {
        final GrowthTrackWatch w = new GrowthTrackWatch();
        final Screen other = new Screen();
        GrowthTrackWatch.Watcher leaving = new GrowthTrackWatch.Watcher() {
            @Override public void syncChanged() { w.unwatch(this); w.unwatch(other); }
        };
        w.watch(leaving); w.watch(other);
        w.passFinished();
        assertEquals(0, other.told, "one that left before its turn is not told");
        assertEquals(0, w.watching());
    }

    /** The manager tells the watch when ANY pass ends - the job's included - and the screen
     *  watches only while it is in front, refreshing what it shows. */
    @Test void theManagerTellsAndTheScreenWatchesWhileInFront() throws Exception {
        String mgr = code("GrowthTrackManager.java");
        String sync = body(mgr, "private void sync(");
        assertTrue(sync.contains("main.post(passFinished)"), "every pass, whoever started it, tells the watch");
        assertTrue(mgr.contains("watch.passFinished()"), "on the main thread");
        String gt = code("GrowthTrackActivity.java");
        assertTrue(body(gt, "protected void onResume()").contains("manager.watch.watch("), "watched while in front");
        assertTrue(body(gt, "protected void onPause()").contains("manager.watch.unwatch("), "and not after");
        assertTrue(body(gt, "public void syncChanged()").contains("refresh()"), "a finished pass re-reads and redraws");
    }

    private static String code(String file) throws Exception {
        return new String(Files.readAllBytes(Paths.get("../app/src/main/java/org/openpump/" + file)), StandardCharsets.UTF_8);
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
