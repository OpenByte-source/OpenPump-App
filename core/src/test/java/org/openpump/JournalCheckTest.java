package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The offline replay, against a fixture shaped like a log a phone would send. */
class JournalCheckTest {

    @Test void aSentLogIsReplayedThroughTodaysRules() throws Exception {
        InputStream in = JournalCheckTest.class.getResourceAsStream("/journal/sample-session.txt");
        assertNotNull(in, "fixture missing");
        JournalCheck.Result res = JournalCheck.check(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> rules = JournalWatchTest.rulesOf(res.findings);
        assertTrue(rules.contains(JournalWatch.PUMP_NOT_FOLLOWING), res.findings.toString());
        assertTrue(rules.contains(JournalWatch.TAP_NO_EFFECT), res.findings.toString());
        assertEquals(2, res.findings.size(), res.findings.toString());
        // The pump stalled at 22 kPa against a 40 kPa command.
        assertTrue(res.findings.toString().contains("raised to 40 kPa, best 22.0"), res.findings.toString());
        assertTrue(res.findings.toString().contains("\"Skip\""), res.findings.toString());
        // The phone's own CHK line is reported apart, never replayed.
        assertEquals(1, res.original.size());
        assertEquals(0, res.skipped);
        assertTrue(res.summary.contains("rule PUMP_NOT_FOLLOWING found=1"), res.summary.toString());
    }

    @Test void stampsRoundTrip() {
        assertEquals(0, Journal.parseStamp("+00:00.00"));
        assertEquals(754_320, Journal.parseStamp(Journal.stamp(754_321)));
        assertEquals(6_000_000 + 1_230, Journal.parseStamp("+100:01.23"));
        assertEquals(-1, Journal.parseStamp("12:00"));
    }

    @Test void garbageLinesAreSkippedNotFatal() throws Exception {
        JournalCheck.Result res = JournalCheck.check("hello\n+xx TAP\n\n+00:01.00 PMP 3.0 n=1\n");
        assertEquals(2, res.skipped);
        assertEquals(1, res.events);
    }
}
