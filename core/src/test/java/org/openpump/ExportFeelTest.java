package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

/**
 * M4 follow-up - THE EXPORT'S `feel` COLUMN IS NOT LEFT EMPTY.
 *
 * The summary's "How did it feel?" chips (easy / fine / tough) are gone; "How did it go?" is
 * the one feel question (Felt great / Felt ok / Too much, filed as Sess.signal). The column
 * keeps its name and its place and now carries that answer - "great", "ok", "too much" -
 * while a session answered on an earlier build still exports the word it had.
 */
class ExportFeelTest {

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private static Model.Sess sess(int signal, int feel) {
        Model.Sess s = new Model.Sess();
        s.ts = 1_000_000L;
        s.routineName = "Pulse";
        s.durSec = 600;
        s.completed = true;
        s.signal = signal;
        s.feel = feel;
        s.note = "";
        return s;
    }

    /** The `feel` cell of the one row sessionsCsv writes for `s`. */
    private static String feelCell(Model.Sess s) {
        List<Model.Sess> one = new ArrayList<Model.Sess>();
        one.add(s);
        String csv = Export.sessionsCsv(new Model(), one, GMT);
        String row = csv.split("\r\n")[1];
        String[] head = Export.SESSIONS_HEADER.split(",");
        int at = -1;
        for (int i = 0; i < head.length; i++) if ("feel".equals(head[i])) at = i;
        assertTrue(at >= 0, "the column is still called feel");
        return row.split(",", -1)[at];
    }

    @Test void howDidItGoFillsTheColumn() {
        assertEquals("great", feelCell(sess(Model.Sess.SIGNAL_GREEN, 0)));
        assertEquals("ok", feelCell(sess(Model.Sess.SIGNAL_ORANGE, 0)));
        assertEquals("too much", feelCell(sess(Model.Sess.SIGNAL_RED, 0)));
    }

    @Test void nobodyAnsweredIsStillEmpty() {
        assertEquals("", feelCell(sess(Model.Sess.SIGNAL_NONE, 0)),
            "never asked is not \"great\"");
        assertEquals("", Export.feelWord(sess(9, 0)), "an out-of-range answer claims nothing");
    }

    @Test void anOldRowExportsWhatItHad() {
        assertEquals("tough", feelCell(sess(Model.Sess.SIGNAL_NONE, 3)));
        assertEquals("easy", feelCell(sess(Model.Sess.SIGNAL_GREEN, 1)),
            "a session answered on an earlier build keeps the word that row always exported");
    }

    @Test void thePdfTableReadsTheSameWord() {
        Model.Sess s = sess(Model.Sess.SIGNAL_RED, 0);
        assertEquals(feelCell(s), Export.feelWord(s),
            "one derivation for the CSV cell and the report's FEEL column");
    }
}
