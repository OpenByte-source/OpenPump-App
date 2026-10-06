package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

/**
 * EVERYTHING THAT ARRIVES FROM OUTSIDE THE APP, treated as hostile.
 *
 * Three doors: a Bluetooth device nobody controls, a backup file anyone can hand the file
 * picker, and a saved model that may be from any earlier version or from none. None of
 * them may crash the app, and none may reach past the place it is allowed to touch.
 */
class HostileInputTest {

    /* ---------------------------------------------------------------- the pump's wire */

    @Test
    void garbageOnTheWireIsNeverASample() {
        assertNull(Proto.parse(null), "no bytes at all");
        assertNull(Proto.parse(new byte[0]), "empty frame");
        assertNull(Proto.parse("hello".getBytes(StandardCharsets.UTF_8)), "no marker");
        assertNull(Proto.parse("#".getBytes(StandardCharsets.UTF_8)), "marker only");
        assertNull(Proto.parse("#AUTO".getBytes(StandardCharsets.UTF_8)), "one field");
        assertNull(Proto.parse("#AUTO,notanumber".getBytes(StandardCharsets.UTF_8)),
            "a pressure that is not a number");
    }

    @Test
    void randomBytesNeverThrow() {
        Random r = new Random(20260922L);
        for (int i = 0; i < 20000; i++) {
            byte[] b = new byte[r.nextInt(64)];
            r.nextBytes(b);
            Proto.parse(b);                 // the assertion is that this returns at all
        }
        // ...including frames that look almost right, which is where a parser usually breaks.
        String[] nearly = {
            "#AUTO,-189,179,0", "#AUTO,-189", "#AUTO,-189,179,0,extra,fields",
            "#AUTO,99999999999999999999,0,0", "#AUTO,-1e400,0,0", "#AUTO,NaN,0,0",
            "#AUTO,-189,99999999999,0", "#,-189,179,0", "##AUTO,-189,179,0",
            "#AUTO,-189,179,0\u0000\u0000", "#ÿþ,-189,179,0", "#AUTO,,,",
        };
        for (String s : nearly) Proto.parse(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anAbsurdPressureIsNotTakenAtFaceValue() {
        String[] absurd = { "#AUTO,-99999,179,0", "#AUTO,-1e400,0,0", "#AUTO,NaN,0,0" };
        for (String a : absurd) {
            Proto.Sample s = Proto.parse(a.getBytes(StandardCharsets.UTF_8));
            // Whatever the parser decides, it may not hand back a figure a dose could
            // integrate into nonsense: either it refuses the frame, or the value is finite.
            if (s != null) {
                assertFalse(Double.isNaN(s.kpa), "NaN would poison every average it enters: " + a);
                assertFalse(Double.isInfinite(s.kpa), "an infinite reading is not a reading: " + a);
            }
        }
    }

    /* ------------------------------------------------------------- a file from anywhere */

    private static byte[] zip(String[] names, byte[][] datas) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        for (int i = 0; i < names.length; i++) {
            z.putNextEntry(new ZipEntry(names[i]));
            z.write(datas[i]);
            z.closeEntry();
        }
        z.close();
        return bo.toByteArray();
    }

    @Test
    void somethingThatIsNotAZipIsNeverRestorable() throws Exception {
        assertThrows(IOException.class, () -> Backup.readAll(null));
        // Not an exception: a stream that is not a zip simply has no entries in it, and
        // an empty list has no model.json, so it cannot be restored.
        List<Backup.Entry> none =
            Backup.readAll(new ByteArrayInputStream("not a zip at all".getBytes()));
        assertTrue(none.isEmpty(), "no entries came out of it");
        assertFalse(Backup.isRestorable(none), "and nothing could be restored from it");
    }

    @Test
    void aZipWithoutAModelIsNotRestorable() throws Exception {
        byte[] b = zip(new String[]{ "readme.txt" }, new byte[][]{ "hello".getBytes() });
        List<Backup.Entry> entries = Backup.readAll(new ByteArrayInputStream(b));
        assertFalse(Backup.isRestorable(entries), "no model.json, no restore");
    }

    @Test
    void anEntryThatTriesToEscapeThePhotosFolderIsNotAPhoto() throws Exception {
        // A real backup stores photos as photos/reading-*.jpg. Everything else here is a
        // name a stranger could put in a zip and hand to the file picker.
        String[] names = {
            "photos/../../reading-y-front.jpg",
            "photos/../model.json",
            "photos/sub/reading-z-front.jpg",
            "photos/not-a-reading.jpg",
            "../../../../data/data/org.openpump/files/reading-x-front.jpg",
            "photos/reading-ok-front.jpg",
        };
        byte[][] datas = new byte[names.length][];
        for (int i = 0; i < names.length; i++) datas[i] = new byte[]{ 1, 2, 3 };
        List<Backup.Entry> entries = Backup.readAll(new ByteArrayInputStream(zip(names, datas)));
        List<String> kept = new ArrayList<String>();
        for (Backup.Entry e : Backup.photoEntries(entries)) kept.add(e.name);
        assertEquals(1, kept.size(), "only the one well-formed photo survives: " + kept);
        assertEquals("photos/reading-ok-front.jpg", kept.get(0));

        // And the name the restore actually writes with is a bare file name, every time:
        // it is what is left after the prefix, and that has to be a name this app wrote.
        for (String kn : kept) {
            String base = kn.substring(Backup.PHOTO_DIR.length());
            assertTrue(Backup.isBackupPhotoName(base), base);
            assertFalse(base.contains("/") || base.contains("\\") || base.contains(".."), base);
        }
    }

    @Test
    void aZipBombIsRefusedRatherThanInflated() throws Exception {
        // One entry of zeros, past the per-entry ceiling: compresses to nothing, inflates
        // to plenty. The read has to give up rather than fill the heap.
        byte[] big = new byte[80 * 1024 * 1024];
        byte[] b = zip(new String[]{ "model.json" }, new byte[][]{ big });
        assertThrows(IOException.class, () -> Backup.readAll(new ByteArrayInputStream(b)));
    }

    @Test
    void aTruncatedZipIsNeverRestorable() throws Exception {
        byte[] good = zip(new String[]{ "model.json" }, new byte[][]{ "{}".getBytes() });
        byte[] cut = new byte[good.length / 2];
        System.arraycopy(good, 0, cut, 0, cut.length);
        try {
            assertFalse(Backup.isRestorable(Backup.readAll(new ByteArrayInputStream(cut))),
                "a half a zip restores nothing");
        } catch (IOException expected) {
            // Equally fine: the decoder may refuse it outright.
        }
    }

    /* ----------------------------------------------------------------- a saved model */

    @Test
    void aJunkSaveFileLoadsAsAUsableModelRatherThanCrashing() {
        String[] junk = {
            "", "   ", "{", "}", "[]", "null", "not json at all",
            "{\"sets\":\"not an array\"}",
            "{\"ceil\":\"not a number\"}",
            "{\"routines\":[{\"id\":null}]}",
            "{\"sets\":[{\"id\":\"s1\",\"up\":999999,\"lo\":-999999,\"dur\":-5}]}",
        };
        for (String j : junk) {
            Model m = Model.fromJson(j);
            assertNotNull(m, "fromJson must always hand back a model: " + j);
            assertTrue(m.ceilKpa > 0, "and one with a usable ceiling: " + j);
        }
    }

    @Test
    void aTamperedSaveFileCannotSmuggleAPressurePastTheCeiling() {
        Model m = Model.fromJson("{\"ceil\":40,\"sets\":[{\"id\":\"s1\",\"name\":\"x\","
            + "\"up\":9999,\"lo\":9998,\"uh\":10,\"lh\":5,\"sp\":50,\"dur\":60}],"
            + "\"routines\":[{\"id\":\"r1\",\"name\":\"r\",\"sets\":[\"s1\"]}]}");
        Model.Routine r = m.routine("r1");
        assertNotNull(r, "the routine still loads");
        List<Model.Preset> plan = m.plan(r);
        assertFalse(plan.isEmpty(), "and still plans");
        for (Model.Preset p : plan)
            assertTrue(p.up <= m.ceilKpa,
                "a plan built from a tampered file still obeys the ceiling: " + p.up);
    }

    /* ------------------------------------------------------------------- time and size */

    @Test
    void aClockThatJumpsBackwardsDoesNotRunTheRunBackwards() {
        Session s = new Session();
        long t0 = 1_700_000_000_000L;
        s.beginRun(t0);
        assertEquals(0L, s.elapsedMs(t0 - 60_000L, 5000L),
            "a minute of clock drift backwards is not negative elapsed time");
    }

    @Test
    void aLongRunCannotGrowTheTraceWithoutBound() {
        Trace.Ring ring = new Trace.Ring();
        for (int i = 0; i < 200_000; i++) ring.push(i % 40, false, 20, 10);
        assertTrue(ring.count() <= Trace.WINDOW,
            "the ring is a window, not a log: " + ring.count());
    }
}
