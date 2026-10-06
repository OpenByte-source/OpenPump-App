package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every rule, driven by synthetic event streams: a {@link SimPump} answering real frames
 * through a real {@link Journal}, with one fault injected per case. Each fault case has a
 * clean twin that must stay silent, because a check that fires on a healthy session is worse
 * than none - it teaches everyone to ignore the "!!".
 */
class JournalWatchTest {

    /** A phone, a pump and a clock. The pump's telemetry can be tampered with per case. */
    static final class Rig {
        final Journal j = new Journal(0);
        final SimPump pump = new SimPump();
        long now = 0;
        long lastTick = 0;
        boolean dropAcks;
        /** When set, telemetry reads this instead of the pump's real pressure. */
        Double forceKpa;
        /** When set, telemetry never exceeds this. */
        Double capKpa;
        boolean silent;
        boolean chartVisible;
        boolean chartStuck;
        long fed;

        Rig() { j.link(0, "Connected", true); }

        void send(byte[] f, String what) {
            j.cmd(now, f, what);
            pump.write(f);
        }

        /** The app's own upload shape: clear nine, add, start. */
        void upload(int sp, int up, int uh, int lo, int lh) {
            for (int i = 0; i < Proto.SLOTS; i++) send(Proto.deleteSlot(0), "clear");
            send(Proto.addPreset(sp, up, uh, lo, lh), "preset");
            send(Proto.startSlot(0), "start");
        }

        void run(long ms) {
            long end = now + ms;
            while (now < end) {
                long step = Math.min(SimPump.TELEM_PERIOD_MS, end - now);
                now += step;
                pump.tick(step);
                List<byte[]> fr = pump.drain();
                for (int i = 0; i < fr.size(); i++) {
                    Proto.Sample s = Proto.parse(fr.get(i));
                    if (s == null) {
                        if (dropAcks && fr.get(i).length == 2) continue;
                        j.frame(now, fr.get(i));
                        continue;
                    }
                    if (silent) continue;
                    double k = s.kpa;
                    if (forceKpa != null) k = forceKpa.doubleValue();
                    if (capKpa != null) k = Math.min(k, capKpa.doubleValue());
                    fed++;
                    j.chartFed = fed;
                    if (!chartStuck) j.chartDrawn = fed;
                    j.sample(now, k, k == 0.0, s.speedPct, s.mode);
                }
                if (now - lastTick >= 500) { lastTick = now; j.tick(now, chartVisible); }
            }
        }

        List<String> chk() { return j.findings(); }

        boolean found(String rule) {
            List<String> c = chk();
            for (int i = 0; i < c.size(); i++) if (c.get(i).contains("!! " + rule + " ")) return true;
            return false;
        }

        int count(String rule) {
            int n = 0;
            List<String> c = chk();
            for (int i = 0; i < c.size(); i++) if (c.get(i).contains("!! " + rule + " ")) n++;
            return n;
        }
    }

    private static final Pattern LINE = Pattern.compile(
        "^\\+\\d{2,}:\\d{2}\\.\\d{2} (TAP|BACK|NAV|CMD|ACK|RX|PMP|UI|LINK|APP|CHK)( .*)?$");

    @Test void aHealthyRoutineFindsNothing() {
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_RUN);
        r.upload(75, 40, 30, 10, 5);
        r.run(60000);
        r.send(Proto.stop(), "StopWork");
        r.run(15000);
        assertTrue(r.chk().isEmpty(), "clean run produced " + r.chk());
        String text = r.j.drain();
        for (String line : text.split("\n")) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            assertTrue(LINE.matcher(line).matches(), "unparseable line: " + line);
        }
        assertTrue(text.contains("CMD add spd=75 up=40 uh=30 lo=10 lh=5 \"preset\""), text);
        assertTrue(text.contains("CMD start slot=0 \"start\""));
        assertTrue(text.contains("ACK start ms="), "acks carry their latency");
    }

    @Test void noAckIsReportedOnceWithItsCommand() {
        Rig r = new Rig();
        r.dropAcks = true;
        r.upload(75, 40, 10, 10, 5);
        r.run(5000);
        assertTrue(r.found(JournalWatch.NO_ACK), r.chk().toString());
        // The add and the start both went unanswered, inside one cooldown: one line.
        assertEquals(1, r.count(JournalWatch.NO_ACK));
        assertTrue(r.chk().get(0).contains("add up=40"), r.chk().toString());
    }

    @Test void aPumpThatStallsShortOfTheTargetIsCaught() {
        Rig r = new Rig();
        r.capKpa = 25.0;                        // a seal that gives at 25 kPa
        r.upload(75, 40, 10, 10, 5);
        r.run(40000);
        assertTrue(r.found(JournalWatch.PUMP_NOT_FOLLOWING), r.chk().toString());
        String c = r.chk().get(0);
        assertTrue(c.contains("raised to 40 kPa, best 25.0"), c);
    }

    @Test void raisingMidRunIsFollowedWhenThePumpDoesIt() {
        Rig r = new Rig();
        r.upload(75, 30, 20, 10, 5);
        r.run(20000);
        // A live adjustment: the app appends an override preset and starts it.
        r.send(Proto.addPreset(75, 40, 20, 10, 5), "override");
        r.send(Proto.startSlot(1), "start override");
        r.run(30000);
        assertFalse(r.found(JournalWatch.PUMP_NOT_FOLLOWING), r.chk().toString());
    }

    @Test void raisingMidRunThatThePumpIgnoresIsCaught() {
        Rig r = new Rig();
        r.upload(75, 30, 20, 10, 5);
        r.run(15000);
        r.capKpa = 30.0;                        // it keeps pulling to the OLD target
        r.send(Proto.addPreset(75, 40, 20, 10, 5), "override");
        r.send(Proto.startSlot(1), "start override");
        r.run(60000);
        assertTrue(r.found(JournalWatch.PUMP_NOT_FOLLOWING), r.chk().toString());
    }

    @Test void loweringThatNeverComesDownIsCaught() {
        Rig r = new Rig();
        r.upload(75, 40, 30, 35, 5);
        r.run(20000);
        r.forceKpa = 40.0;
        r.send(Proto.addPreset(75, 20, 10, 10, 5), "lower");
        r.send(Proto.startSlot(1), "start lower");
        r.run(40000);
        assertTrue(r.found(JournalWatch.PUMP_NOT_FOLLOWING), r.chk().toString());
        assertTrue(r.chk().toString().contains("lowered to 20"), r.chk().toString());
        // While the lowering is still pending the reading is expected to be high: no
        // ABOVE_COMMAND on top of it.
        assertFalse(r.found(JournalWatch.PUMP_ABOVE_COMMAND), r.chk().toString());
    }

    @Test void aPumpAboveItsCommandIsCaught() {
        Rig r = new Rig();
        r.upload(75, 40, 30, 35, 5);
        r.run(20000);
        r.forceKpa = 45.0;
        r.run(4000);
        assertTrue(r.found(JournalWatch.PUMP_ABOVE_COMMAND), r.chk().toString());
    }

    @Test void aStopThatDoesNotVentIsCaught() {
        Rig r = new Rig();
        r.upload(75, 40, 30, 35, 5);
        r.run(20000);
        r.send(Proto.stop(), "StopWork");
        r.forceKpa = 38.0;
        r.run(15000);
        assertTrue(r.found(JournalWatch.STOP_NOT_VENTING), r.chk().toString());
    }

    @Test void silenceOnALiveLinkIsCaughtOnceAndItsLengthLogged() {
        Rig r = new Rig();
        r.run(3000);
        r.silent = true;
        r.run(4000);
        r.silent = false;
        r.run(2000);
        assertEquals(1, r.count(JournalWatch.READINGS_SILENT), r.chk().toString());
        String text = String.join("\n", r.j.recent(400));
        assertTrue(text.contains("PMP silent"), text);
        assertTrue(Pattern.compile("PMP \\S+ n=\\d+.* gap=4\\.\\d").matcher(text).find(), text);
    }

    @Test void silenceWithTheLinkDownIsNotAFinding() {
        Rig r = new Rig();
        r.run(3000);
        r.j.link(r.now, "Disconnected", false);
        r.silent = true;
        r.run(5000);
        assertFalse(r.found(JournalWatch.READINGS_SILENT), r.chk().toString());
    }

    @Test void aChartThatStopsDrawingIsCaught() {
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_RUN);
        r.chartVisible = true;
        r.upload(75, 40, 30, 35, 5);
        r.run(5000);
        assertFalse(r.found(JournalWatch.CHART_FROZEN), r.chk().toString());
        r.chartStuck = true;
        r.run(4000);
        assertTrue(r.found(JournalWatch.CHART_FROZEN), r.chk().toString());
    }

    @Test void aChartNobodyFeedsIsCaught() {
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_RUN);
        r.chartVisible = true;
        r.upload(75, 40, 30, 35, 5);
        r.run(3000);
        long frozenAt = r.fed;
        // Samples keep arriving, but the chart's ring stops taking them.
        Journal j = r.j;
        for (int i = 0; i < 20; i++) {
            r.now += 240;
            j.sample(r.now, 20 + i, false, 75, "AUTO");
            j.chartFed = frozenAt;
            j.chartDrawn = frozenAt;
            if (i % 2 == 0) j.tick(r.now, true);
        }
        assertTrue(r.found(JournalWatch.CHART_FROZEN), r.chk().toString());
        assertTrue(r.chk().toString().contains("no reading has reached the chart"), r.chk().toString());
    }

    @Test void aCellThatDisagreesWithTheWireIsCaught() {
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_RUN);
        r.upload(75, 40, 30, 35, 5);
        r.j.uiState(r.now, "state", "run");
        r.j.pressureCell(r.now, "pull", "40.0", "kPa", "kPa");
        r.j.plainCell(r.now, "hold", "30", "s");
        r.j.plainCell(r.now, "speed", "75 %", "");
        r.run(3000);
        assertFalse(r.found(JournalWatch.SCREEN_MISMATCH), r.chk().toString());
        r.j.pressureCell(r.now, "pull", "35.0", "kPa", "kPa");
        r.run(2000);
        assertTrue(r.found(JournalWatch.SCREEN_MISMATCH), r.chk().toString());
        assertTrue(r.chk().toString().contains("pull shows"), r.chk().toString());
    }

    @Test void aCellInMercuryIsConvertedBeforeItIsCompared() {
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_RUN);
        r.upload(75, 40, 30, 35, 5);
        r.j.uiState(r.now, "state", "run");
        // 40 kPa is 11.8 inHg; the screen prints it negative.
        r.j.pressureCell(r.now, "pull", "−11.8", "inHg", "inHg");
        r.run(3000);
        assertFalse(r.found(JournalWatch.SCREEN_MISMATCH), r.chk().toString());
    }

    @Test void aCellSayingNotConfirmedIsComparedWithNothing() {
        // While the pump is not answering, the pull shows the HIGHER figure it may be at, and
        // every cell says "not confirmed" (the review of refusal-2): no claim to hold the wire to.
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_RUN);
        r.upload(75, 40, 30, 35, 5);
        r.j.uiState(r.now, "state", "run");
        r.j.pressureCell(r.now, "pull", "44.0", "not confirmed", "kPa");
        r.run(20000);
        assertFalse(r.found(JournalWatch.SCREEN_MISMATCH), r.chk().toString());
        r.j.pressureCell(r.now, "pull", "35.0", "kPa", "kPa");
        r.run(3000);
        assertTrue(r.found(JournalWatch.SCREEN_MISMATCH), "answering again: compared again");
    }

    @Test void aPendingEditIsNotAMismatchUntilItOverstays() {
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_RUN);
        r.upload(75, 40, 30, 35, 5);
        r.j.uiState(r.now, "state", "run");
        r.j.pressureCell(r.now, "pull", "41.0", "sending…", "kPa");
        r.run(3000);
        assertFalse(r.found(JournalWatch.SCREEN_MISMATCH), "a pending edit within its window");
        r.run(5000);
        assertTrue(r.found(JournalWatch.SCREEN_MISMATCH), "stuck on sending: " + r.chk());
        assertTrue(r.chk().toString().contains("unsent edit"), r.chk().toString());
    }

    @Test void anAdjustmentsShortenedHoldIsNotAMismatch() {
        // The override carries the hold LEFT in the step; the cell shows the step's length.
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_RUN);
        r.upload(50, 14, 240, 13, 0);
        r.send(Proto.addPreset(50, 16, 206, 13, 0), "override");
        r.send(Proto.startSlot(1), "start override");
        r.j.uiState(r.now, "state", "adjusted");
        r.j.pressureCell(r.now, "pull", "16.0", "kPa", "kPa");
        r.j.plainCell(r.now, "hold", "240", "s");
        r.run(3000);
        assertFalse(r.found(JournalWatch.SCREEN_MISMATCH), r.chk().toString());
        r.j.pressureCell(r.now, "pull", "14.0", "kPa", "kPa");
        r.run(2000);
        assertTrue(r.found(JournalWatch.SCREEN_MISMATCH), "the pressure is still compared");
    }

    @Test void aRefusalIsJournalledAsOneAndAnswersItsCommand() {
        // The owner's journal of 22 Sep: `RX 2C FD` 23 times, shown as bare hex, and every
        // refused START then counted as owing an ack. A refusal is an answer - the pump said
        // no - so it is written as one, with its latency, and it settles the command.
        Rig r = new Rig();
        r.upload(75, 40, 30, 10, 5);
        r.run(3000);
        r.send(Proto.startSlot(8), "start override slot 8");     // nothing in slot 8
        r.run(5000);
        String text = r.j.drain();
        assertTrue(text.contains("ACK start REFUSED ms="), text);
        assertFalse(text.contains("RX 2C FD"), "not left as bare hex: " + text);
        assertFalse(r.found(JournalWatch.NO_ACK), "a refusal is an answer: " + r.chk());
    }

    @Test void aRefusedStartLeavesTheCellsComparedWithWhatThePumpRuns() {
        // A live change's START refused (`2C FD`): the pump cycles on what it ran, and the
        // screen, rightly, goes back to it. The watch compared the cells with the refused preset
        // and reported SCREEN_MISMATCH (seen on the emulator, pump-refusal).
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_RUN);
        r.upload(75, 17, 120, 3, 5);
        r.j.uiState(r.now, "state", "run");
        r.j.pressureCell(r.now, "pull", "17.0", "kPa", "kPa");
        r.run(3000);
        r.j.cmd(r.now, Proto.addPreset(75, 19, 120, 3, 5), "override add[1]");  // lost on its way
        r.send(Proto.startSlot(1), "start override slot 1");                   // empty: refused
        r.run(4000);
        assertTrue(r.j.drain().contains("ACK start REFUSED"));
        assertFalse(r.found(JournalWatch.SCREEN_MISMATCH), r.chk().toString());
        // ...and a START the pump takes is compared as before.
        r.send(Proto.addPreset(75, 19, 120, 3, 5), "override add[1]");
        r.send(Proto.startSlot(1), "start override slot 1");
        r.run(4000);
        assertTrue(r.found(JournalWatch.SCREEN_MISMATCH), "17 on screen, 19 running: " + r.chk());
    }

    @Test void anAllZeroPresetTakesNoEntryInTheWatchesTable() {
        // The pump does not store `66 2A 2B 00 00 00 00 00` (the owner's journal of 22 Sep).
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_RUN);
        for (int i = 0; i < Proto.SLOTS; i++) r.send(Proto.deleteSlot(0), "clear");
        r.send(Proto.addPreset(75, 20, 120, 3, 5), "add[0]");
        r.send(Proto.addPreset(0, 0, 0, 0, 0), "add[1] rest");
        r.send(Proto.addPreset(75, 25, 120, 3, 5), "add[2]");
        r.send(Proto.startSlot(1), "start slot 1");        // the pump's slot 1 is the 25
        r.j.uiState(r.now, "state", "run");
        r.j.pressureCell(r.now, "pull", "25.0", "kPa", "kPa");
        r.run(4000);
        assertFalse(r.found(JournalWatch.SCREEN_MISMATCH), r.chk().toString());
    }

    @Test void ackLatencyIsMeasuredOnTheJournalsOwnClock() {
        Journal j = new Journal(80_000_000L);
        j.cmd(80_001_000L, Proto.stop(), "StopWork");
        j.frame(80_001_250L, new byte[]{ (byte) Proto.OP_STOP, 1 });
        String text = j.drain();
        assertTrue(text.contains("+00:01.25 ACK stop ms=250"), text);
    }

    @Test void aHoldIsNotComparedAgainstTheRoutinesCells() {
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_RUN);
        r.upload(75, 40, 30, 35, 5);
        r.j.uiState(r.now, "state", "hold");
        r.j.pressureCell(r.now, "pull", "30.0", "kPa", "kPa");
        r.run(4000);
        assertFalse(r.found(JournalWatch.SCREEN_MISMATCH), r.chk().toString());
    }

    @Test void aTapThatChangesNothingIsCaughtAndADisabledOneIsOnlyLogged() {
        Rig r = new Rig();
        r.j.nav(0, Nav.SCR_SETTINGS);
        r.j.tap(1000, "Increase ceiling", true, true, false, null, 0xABCDEF);
        r.j.tapAfter(1800, 0xABCDEF);
        assertEquals(1, r.count(JournalWatch.TAP_NO_EFFECT), r.chk().toString());

        r.j.tap(3000, "Increase ceiling", true, true, false, null, 0x123456);
        r.j.tapAfter(3800, 0x654321);                    // the screen changed
        r.j.tap(5000, "Start", true, true, false, null, 0x111111);
        r.send(Proto.stop(), "StopWork");                // it commanded the pump
        r.j.tapAfter(5800, 0x111111);
        r.j.tap(7000, "Increase drop", false, true, false, null, 0x222222);
        r.j.tapAfter(7800, 0x222222);
        assertEquals(1, r.count(JournalWatch.TAP_NO_EFFECT), r.chk().toString());
        String text = String.join("\n", r.j.recent(50));
        assertTrue(text.contains("TAP settings \"Increase drop\" disabled"), text);
    }

    @Test void aRepeatingFaultIsOneLineAndACount() {
        Rig r = new Rig();
        r.dropAcks = true;
        for (int i = 0; i < 3; i++) {
            r.send(Proto.stop(), "StopWork");
            r.run(2500);
        }
        assertEquals(1, r.count(JournalWatch.NO_ACK), r.chk().toString());
        r.j.summary(r.now, "test");
        String text = r.j.drain();
        assertTrue(text.contains("# rule NO_ACK found=1 repeats=2"), text);
        assertTrue(text.contains("# events TAP=0"), text);
    }

    @Test void steadyReadingsAreCompressed() {
        Journal j = new Journal(0);
        j.link(0, "Connected", true);
        int samples = 0;
        for (long t = 0; t < 60000; t += 240) { j.sample(t, 30.0 + ((t / 240) % 2) * 0.1, false, 75, "AUTO"); samples++; }
        int pmp = j.count("PMP");
        assertTrue(pmp <= 32, "a flat minute should be about one line per 2 s, was " + pmp);
        // n= adds up to every sample: nothing is lost, only collapsed.
        int n = 0;
        for (String line : j.drain().split("\n")) {
            int at = line.indexOf(" n=");
            if (line.contains(" PMP ") && at > 0) n += Integer.parseInt(line.substring(at + 3).split(" ")[0]);
        }
        assertTrue(samples - n <= 8, "collapsed counts cover the samples: " + n + " of " + samples);
    }

    @Test void pastTheCapOnlyTheDetailIsDropped() {
        Journal j = new Journal(0);
        j.link(0, "Connected", true);
        long t = 0;
        for (int i = 0; i < 200000 && j.bytes() < Journal.FILE_CAP + 10000; i++) {
            t += 240;
            j.sample(t, (i % 2) * 5.0 + 10, false, 75, "AUTO");
            if (i % 1000 == 0) j.drain();
        }
        j.drain();
        j.sample(t + 240, 50, false, 75, "AUTO");
        j.tap(t + 300, "Stop", true, true, false, null, 1);
        String after = j.drain();
        assertFalse(after.contains(" PMP "), after);
        assertTrue(after.contains(" TAP run") || after.contains(" TAP none"), after);
        String all = String.join("\n", j.recent(Journal.RING));
        assertTrue(all.contains(" PMP 50.0"), "the ring keeps what the file dropped");
    }

    @Test void theWatchSeesOnlyWhatIsWritten() throws Exception {
        // Replaying the file a phone wrote finds what the phone found.
        Rig r = new Rig();
        r.capKpa = 25.0;
        StringBuilder file = new StringBuilder(r.j.drain());
        r.upload(75, 40, 10, 10, 5);
        r.run(40000);
        r.dropAcks = true;
        r.send(Proto.stop(), "StopWork");
        r.run(15000);
        file.append(r.j.drain());
        JournalCheck.Result res = JournalCheck.check(file.toString());
        List<String> live = rulesOf(r.chk()), replay = rulesOf(res.findings);
        assertEquals(live, replay, "live " + r.chk() + "\nreplay " + res.findings);
        assertEquals(r.chk().size(), res.original.size());
    }

    static List<String> rulesOf(List<String> chk) {
        List<String> out = new ArrayList<String>();
        for (String c : chk) {
            int at = c.indexOf("!! ");
            out.add(c.substring(at + 3).split(" ")[0]);
        }
        return out;
    }
}
