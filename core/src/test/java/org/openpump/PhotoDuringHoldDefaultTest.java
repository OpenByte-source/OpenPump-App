package org.openpump;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * THE OWNER'S DECISION (2026-09-24): "PHOTO DURING THE HOLD" IS OFF UNLESS THE PERSON TURNS IT
 * ON.
 *
 * The switch did nothing for as long as it existed, and it was stored ON by default - so
 * every existing install carries photo = true that nobody chose. Once the switch started
 * opening the camera after every served hold, that stored true would have started doing
 * something nobody asked for. So:
 *   - a new install starts off;
 *   - an existing install (any model saved without the one-time marker, including a backup
 *     made before 0.9.0 and restored later) is switched off ONCE, on the load that first
 *     sees it;
 *   - after that the value is the person's: turning it on survives every later save, load,
 *     backup and restore, and nothing turns it off again.
 */
class PhotoDuringHoldDefaultTest {

    /** A model.json as a build before the marker wrote it: the std block, no marker. */
    private static String oldFile(boolean photo) {
        return "{\"ceil\":40,\"unit\":\"inHg\",\"selected\":\"r1\","
            + "\"sets\":[{\"id\":\"s1\",\"name\":\"Warm\",\"up\":14,\"lo\":7,"
            + "\"uh\":20,\"lh\":5,\"sp\":60,\"dur\":180}],"
            + "\"routines\":[{\"id\":\"r1\",\"name\":\"R\",\"sets\":[\"s1\"]}],"
            + "\"std\":[{\"on\":true,\"kpa\":20,\"sec\":30,\"photo\":" + photo
            + ",\"release\":5}]}";
    }

    @Test void aNewInstallStartsOff() {
        assertFalse(new Model().std.photo, "a new Model");
        assertFalse(Model.seed().std.photo, "the first-run seed");
        assertFalse(new Model.Std().photo, "a Std built on its own");
    }

    @Test void anExistingInstallIsSwitchedOffOnce() throws Exception {
        Model m = Model.fromJson(oldFile(true));
        assertFalse(m.std.photo, "the stored true nobody chose is switched off on the update");
        assertTrue(m.std.on, "...and nothing else about the hold moves");
        assertTrue(new JSONObject(m.toJson()).optBoolean(Model.STD_PHOTO_OFF_KEY, false),
            "the model now carries the marker, so the switch-off is never repeated");
        assertFalse(Model.fromJson(oldFile(false)).std.photo, "an old false stays false");
    }

    @Test void aFileWithNoStdBlockAtAllComesUpOff() {
        String bare = "{\"ceil\":40,\"unit\":\"inHg\",\"selected\":\"r1\","
            + "\"sets\":[{\"id\":\"s1\",\"name\":\"W\",\"up\":14,\"lo\":7,\"uh\":20,\"lh\":5,"
            + "\"sp\":60,\"dur\":180}],\"routines\":[{\"id\":\"r1\",\"name\":\"R\","
            + "\"sets\":[\"s1\"]}]}";
        assertFalse(Model.fromJson(bare).std.photo);
    }

    @Test void turnedOnAfterTheUpdateItStaysOnThroughEverySaveAndLoad() throws Exception {
        Model m = Model.fromJson(oldFile(true));
        assertFalse(m.std.photo);
        m.std.photo = true;                               // the person turns it on
        String saved = m.toJson();
        for (int i = 0; i < 3; i++) {                     // restart after restart
            Model back = Model.fromJson(saved);
            assertTrue(back.std.photo, "load " + (i + 1) + " keeps the person's choice");
            saved = back.toJson();
        }
        // ...and so does a backup made now and restored later (restore reads model.json
        // through the same Model.fromJson).
        assertTrue(Model.fromJson(saved).std.photo, "a restored backup made after the update");
    }

    @Test void aNewInstallTurnedOnStaysOn() {
        Model m = Model.seed();
        m.std.photo = true;
        assertTrue(Model.fromJson(m.toJson()).std.photo);
    }

    @Test void aBackupFromBeforeTheUpdateGoesOffOnRestoreUnlessItsMarkerSaysOtherwise()
            throws Exception {
        // A pre-0.9.0 backup's model.json is an old file: no marker.
        assertFalse(Model.fromJson(oldFile(true)).std.photo, "an old backup goes off once");
        // A backup whose marker says it already migrated keeps the person's value.
        JSONObject migrated = new JSONObject(oldFile(true));
        migrated.put(Model.STD_PHOTO_OFF_KEY, true);
        assertTrue(Model.fromJson(migrated.toString()).std.photo,
            "the marker says it migrated, so the stored true is the person's own");
        // A marker written as false has not migrated yet.
        JSONObject notYet = new JSONObject(oldFile(true));
        notYet.put(Model.STD_PHOTO_OFF_KEY, false);
        assertFalse(Model.fromJson(notYet.toString()).std.photo);
    }

    @Test void theMarkerIsWrittenOnEverySave() throws Exception {
        assertTrue(new JSONObject(new Model().toJson()).optBoolean(Model.STD_PHOTO_OFF_KEY, false));
        assertTrue(new JSONObject(Model.seed().toJson()).optBoolean(Model.STD_PHOTO_OFF_KEY, false));
        JSONArray std = new JSONObject(new Model().toJson()).optJSONArray("std");
        assertFalse(std.optJSONObject(0).optBoolean("photo", true), "stored off");
    }
}
