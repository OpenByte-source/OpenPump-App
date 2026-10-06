package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Restoring a PumpDebug-era backup into OpenPump keeps its photos. */
class BackupRebaseTest {

    private static final String NEW_DIR = "/storage/emulated/0/Android/data/org.openpump/files/Pictures";

    private static Model.Reading.Photo photo(String path) {
        Model.Reading.Photo p = new Model.Reading.Photo();
        p.path = path;
        return p;
    }

    private static Model modelWith(Model.Reading r) {
        Model m = new Model();
        m.measLog.all.add(r);
        return m;
    }

    @Test
    void oldAppIdPathsFollowThePhotosIntoTheNewFolder() {
        Model.Reading r = new Model.Reading();
        r.photoFront = photo("/storage/emulated/0/Android/data/com.pumpdebug/files/Pictures/reading-m1-front.jpg");
        r.photoSide = photo("/data/user/0/com.pumpdebug/files/Pictures/reading-m1-side.jpg");
        Model m = modelWith(r);

        assertEquals(2, Backup.rebasePhotoPaths(m, NEW_DIR));
        assertEquals(NEW_DIR + "/reading-m1-front.jpg", r.photoFront.path);
        assertEquals(NEW_DIR + "/reading-m1-side.jpg", r.photoSide.path);
    }

    @Test
    void pathsAlreadyHomeAreUntouched() {
        Model.Reading r = new Model.Reading();
        r.photoFront = photo(NEW_DIR + "/reading-m2-front.jpg");
        assertEquals(0, Backup.rebasePhotoPaths(modelWith(r), NEW_DIR + "/"));
        assertEquals(NEW_DIR + "/reading-m2-front.jpg", r.photoFront.path);
    }

    @Test
    void anythingThatIsNotABackupPhotoIsLeftAlone() {
        Model.Reading r = new Model.Reading();
        r.photoFront = photo("/somewhere/else/holiday.png");
        r.photoTop = photo("");
        assertEquals(0, Backup.rebasePhotoPaths(modelWith(r), NEW_DIR));
        assertEquals("/somewhere/else/holiday.png", r.photoFront.path);
        assertEquals("", r.photoTop.path);
    }
}
