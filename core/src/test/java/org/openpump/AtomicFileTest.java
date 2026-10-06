package org.openpump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DURABLE SAVE (0.10): model.json, asrun.json and tau.json are written tmp -> forced to the
 * disk -> renamed (AtomicFile). The sync itself cannot be observed from a test; what is held
 * here is the shape around it: the bytes land whole, the tmp never lingers, a refused write
 * never touches the good file, and the in-place fallback runs only after a refused rename.
 * WiringCheck invariant 220 holds Store to writing through AtomicFile and AtomicFile to
 * syncing before it renames.
 */
class AtomicFileTest {

    @TempDir Path dir;

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test void aFirstSaveLandsWholeAndLeavesNoTmp() throws Exception {
        File tmp = dir.resolve("model.json.tmp").toFile();
        File dst = dir.resolve("model.json").toFile();
        assertEquals(AtomicFile.REPLACED, AtomicFile.save(tmp, dst, b("{\"a\":1}")));
        assertArrayEquals(b("{\"a\":1}"), Files.readAllBytes(dst.toPath()));
        assertFalse(tmp.exists(), "the scratch file never lingers");
    }

    @Test void aSaveOverAnOldFileReplacesItWhole() throws Exception {
        File tmp = dir.resolve("model.json.tmp").toFile();
        File dst = dir.resolve("model.json").toFile();
        Files.write(dst.toPath(), b("{\"old\":\"a much longer previous library\"}"));
        // REPLACED where rename replaces (Android, Linux); RENAME_REFUSED where it may not
        // (Windows) - either way the save lands, whole, and the tmp is gone.
        int how = AtomicFile.save(tmp, dst, b("{\"new\":2}"));
        assertTrue(how == AtomicFile.REPLACED || how == AtomicFile.RENAME_REFUSED);
        assertArrayEquals(b("{\"new\":2}"), Files.readAllBytes(dst.toPath()));
        assertFalse(tmp.exists());
    }

    @Test void aRefusedWriteKeepsTheGoodFileExactly() throws Exception {
        // The tmp name is taken by a directory: the write is refused, as a full disk would.
        File tmp = dir.resolve("blocked").toFile();
        assertTrue(new File(tmp, "inside").mkdirs());
        File dst = dir.resolve("model.json").toFile();
        Files.write(dst.toPath(), b("{\"library\":\"everything\"}"));
        assertEquals(AtomicFile.WRITE_REFUSED, AtomicFile.save(tmp, dst, b("{}")));
        assertArrayEquals(b("{\"library\":\"everything\"}"), Files.readAllBytes(dst.toPath()),
            "a refused write must never fall back to truncating the good file");
    }

    @Test void aRefusedRenameLeavesTheSyncedTmpForTheFallback() throws Exception {
        File tmp = dir.resolve("x.tmp").toFile();
        // The destination is a non-empty directory: no platform renames a file over it.
        File dst = dir.resolve("x").toFile();
        assertTrue(new File(dst, "keep").mkdirs());
        assertEquals(AtomicFile.RENAME_REFUSED, AtomicFile.replace(tmp, dst, b("whole")));
        assertArrayEquals(b("whole"), Files.readAllBytes(tmp.toPath()),
            "the bytes were accepted and synced; only the publish step failed");
        assertTrue(dst.isDirectory(), "never delete the old name before the new one is in");
    }

    @Test void theSyncedWriteWritesEveryByte() throws Exception {
        File f = dir.resolve("staged.jpg").toFile();
        byte[] big = new byte[300_000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        assertTrue(AtomicFile.writeSynced(f, big));
        assertArrayEquals(big, Files.readAllBytes(f.toPath()));
        assertFalse(AtomicFile.writeSynced(dir.toFile(), big), "a directory is not writable");
    }
}
