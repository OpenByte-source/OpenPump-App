package org.openpump;

import static org.junit.jupiter.api.Assertions.*;
import static org.openpump.T10Fix.*;
import org.junit.jupiter.api.Test;

/**
 * A SHARED ROUTINE KEEPS EACH STAGE'S MARKERS.
 *
 * Every share code (PD-ROUTINE-1, PD-ROUTINE-2, PD3) dropped Stage#traction - and the
 * fatigue, retention and climb markers with it - so an imported length session's Fatigue and
 * Strain holds counted as girth work and read so to a connected app. A routine with a marker
 * is now written as packed version 4 / document version 3, which an older build refuses; a
 * routine with none is written byte for byte as before. A code made before is given the
 * builder's pulls back on read, only when the builder made it; a routine the person built is
 * never guessed for. The cylinder never travels (it names a tube in the sender's rack).
 */
class ShareCodeMarksTest {

    /* Made by this build before the fix (HEAD 4508df2), from StageWaitsCarryTest#top: the
     * length session, packed version 3 / document version 2 - the waits, and no marker. */
    static final String HEAD_LENGTH_V3 = "PD3:PBUcmFpbmVyIMK3IExlbmd0aCBMMiDCtyB0cmFjdGlvbiBAIHVwIHRvIDEyLjIgbGIFHhas0qOrc0sbCQOTK2MrC5spBxQEoQMTyQNDC3MkolkCyuwuTaWurgQOjeQcURJHBcYEDS3JDO/8d7PAPAKyuwuTaWurgQOjeQcURJHBcYEDS3JDOEwMeBZbYZ4CywwlhqMwujSzurKQNDe2MjnADwEyjMLo0s7qykDQ3tjIQYVuQMbY0trEQYcubBcAPAqXCAeBUsMNIgjMLo0s7qykDQ3tjIQYcuaAiAPAqWIwYpujkwtLcQNDe2MjnADwCepujkwtLcQNDe2MhBhy5qCID/HpbOX6m7sLgQOjeQPLe6uRAztLk6NBAxvLY0tzIyuRBxQEoQNzK8OhA0uZAyvDgwtzm0t7cQMLoQcURJGJgXGBA0t0oPGBKK8ODC3ObS3t0APAJyK8ODC3ObS3txA0N7YyAiDeAWWTi";
    static final String HEAD_LENGTH_V2 = "PD-ROUTINE-2:eNq1lb9u2zAQxl+F4CwFJEXJsqYsDTpkKJoCHYoOtExbgiVKIKkmRmCgY+eiQB6le/MmeZLy9MexUzmODXSSSB3vu/sd8ekeC2OkMTj5co/njcYJDz28qgVOGPGwqXESuedtJhVOsFhYqbGHK7dYiMLIzVcPf3OhHlailC7ikxa5khr9+Y2upVraDF0zWFgtUptXCl2ipka2QpRdMFTMXDJjxVJ2BWhpLE6sbqSH06qooB6fkgmj04C6SGkhzmlC4I1McRIQ8qzdqDwVSMtCCiPR0/dfaLZGmVBzp1IK1YiiS77xBqm2iR2tYEI4IXwr1TNpVfZOFJXbhb26r8h1obdftSjroQ9j25gIzrD20EJX5ccGeBJXWJNBfkDtvk4c/AZe2MTFZ+7FbeRlXWkr59v0fb+fhS79jubTj5/xBUG5er90KXMXig2dTDmPecymccR9DlKuKMogcZsXhguCUM+HQqih4gZ0Yeql0CuckM0ucMrIsQIO46UsilkLc58vZ2N8yTl8yRjf6B++QdDzpeQw4Cth82UjUVYVc7jEaZGXM/T4EI0yjvywZ8zijjElJ0D2ehYsPodFv+hh8BNh8FNhPD7wMQY89mnPoMt5IoNDF21X25xxv2gU/meoneg41eAVqjcWDHOAGo5BDbnPXkANjkFtyzlGdUd7F+op9ruX7la0VrB259Ay187703WRq7n7HYAXK3lnUW6QvKuFMvAvEBZ8g5Jn5xLpaijgbYZ9aOIRC89w7PGBj1o2ZYcH/opnv9s2D9hHfYT4wYtxH/PqtpZj094qYwgZov8CinVo9g==";
    /* ...and a routine with a wait and no marker (StageShapeCarryTest#waitsOnly), as that build wrote it: a
     * routine with no marker is the same code, byte for byte. */
    static final String WAITS_V3 = "PD3:MpXYWl0cyBvbmx5BR4WxCK7e5NcAPAIiuwuTaAwQoCHgeAim7sLhKB5g";
    static final String WAITS_V2 = "PD-ROUTINE-2:eNpVUM1OwzAMfhXkc5DSrJTRp5jYgQPawXQZrZY0UX6o0LR3x25ptp3i2P7+fAGMUccI7ecFjjlAW78IOHuEVkkB0UPb0Dv1eoQWvlzqQYCj+oQm6utBwA9tChjRalr4wCHFJzeaX1qLCb/1whx0TP8YAZ0zjpWeK9Vs1Uayjk53FlRNrQeIcWSMe57XDjN3KNOA1pdPTPNSzSA1v6fg7Htm/5Jc5Z6aczS1ZMtcVJTB9FRsBQzWu5D0sVCWcMESwUADcBNT+RU449Zjsd7O4FjwmYlZ02I4Qyv5bBxvrzsmkDcFR/OrWO+VQn44l3xV1dumKue6p2luLPsJPbnD7rxSWBwzmuXH4quPP9ozmV4=";

    /* ------------------------------------------------------------- share codes */

    @Test void aV3CodeKeepsEveryMarker() throws Exception {
        Model m = owner();
        Model.Routine r = StageShapeCarryTest.lengthMint(m);
        String code = r.toShareCode(m);
        assertEquals(4, StageWaitsCarryTest.packedVersion(code), "packed version 4: the markers");
        assertEquals(StageShapeCarryTest.marks(r),
            StageShapeCarryTest.marks(owner().importShareCode(code)));
        Model h = new Model();
        Model.Routine mine = StageShapeCarryTest.everyMarker(h);
        assertEquals(StageShapeCarryTest.marks(mine),
            StageShapeCarryTest.marks(new Model().importShareCode(mine.toShareCode(h))),
            "a person's own markers travel too");
    }

    @Test void aV2CodeKeepsEveryMarker() throws Exception {
        Model m = owner();
        Model.Routine r = StageShapeCarryTest.lengthMint(m);
        String code = r.toShareCodeV2(m);
        assertEquals(3, StageWaitsCarryTest.v2Json(code).getInt("v"), "document version 3");
        assertEquals(StageShapeCarryTest.marks(r),
            StageShapeCarryTest.marks(owner().importShareCode(code)));
        Model h = new Model();
        Model.Routine mine = StageShapeCarryTest.everyMarker(h);
        assertEquals(StageShapeCarryTest.marks(mine),
            StageShapeCarryTest.marks(new Model().importShareCode(mine.toShareCodeV2(h))));
    }

    @Test void theCylinderStaysOnThePhoneItNames() throws Exception {
        // A rack id names a tube on the sender's phone; on another it would name a missing
        // one and the run would refuse it - a code never carries it.
        Model h = new Model();
        Model.Routine mine = StageShapeCarryTest.everyMarker(h);
        assertEquals("", new Model().importShareCode(mine.toShareCode(h)).stages.get(1).cylinderId);
        assertEquals("", new Model().importShareCode(mine.toShareCodeV2(h)).stages.get(1).cylinderId);
    }

    @Test void aCodeWithNoMarkerIsTheSameCodeAsBefore() throws Exception {
        Model m = new Model();
        Model.Routine q = StageShapeCarryTest.waitsOnly(m);
        assertEquals(WAITS_V3, q.toShareCode(m), "v3, byte for byte");
        assertEquals(WAITS_V2, q.toShareCodeV2(m), "v2, byte for byte");
        Model.Routine got = new Model().importShareCode(WAITS_V3);
        assertEquals(StageShapeCarryTest.marks(q), StageShapeCarryTest.marks(got));
        assertEquals(StageShapeCarryTest.marks(q),
            StageShapeCarryTest.marks(new Model().importShareCode(WAITS_V2)));
    }

    @Test void everyOldLengthCodeImportsWithItsPulls() throws Exception {
        Model m = owner();
        Model.Routine r = StageShapeCarryTest.lengthMint(m);
        String[] codes = { HEAD_LENGTH_V3, HEAD_LENGTH_V2,
                           StageWaitsCarryTest.OLD_LENGTH_V3, StageWaitsCarryTest.OLD_LENGTH_V2 };
        for (String code : codes) {
            Model.Routine got = owner().importShareCode(code);
            assertEquals(r.stages.size(), got.stages.size());
            for (int i = 0; i < r.stages.size(); i++) {
                assertEquals(r.stages.get(i).traction, got.stages.get(i).traction,
                    "the length marker of stage " + i + " by the builder's pulls, " + code.substring(0, 8));
                assertEquals(r.stages.get(i).fatigueBlock, got.stages.get(i).fatigueBlock,
                    "the fatigue marker of stage " + i);
            }
            assertEquals(StageWaitsCarryTest.waits(r), StageWaitsCarryTest.waits(got), "and the waits");
        }
    }

    @Test void anOldCodeOfAPersonsRoutineIsNeverGivenAMarker() throws Exception {
        // A routine the person built carries none of the builder's words: a stage they named
        // "Strain holds" is still theirs, and nothing is guessed for it.
        Model m = new Model();
        m.ceilKpa = 40;
        m.sets.add(Model.Set.fixed("b", "Pull", 20, 0, 60, 30, 60, 600));
        Model.Routine q = new Model.Routine();
        q.id = "q"; q.name = "Mine";
        q.stages.add(Model.Stage.of(Plan.STRAIN_NAME, Model.STAGE_WORK, new String[]{ "b" }));
        q.stages.add(Model.Stage.of(Plan.FATIGUE_NAME, Model.STAGE_WORK, new String[]{ "b" }));
        m.routines.add(q);
        String code = q.toShareCode(m);
        assertEquals(1, StageWaitsCarryTest.packedVersion(code), "fixture: an unmarked code");
        for (Model.Stage st : new Model().importShareCode(code).stages)
            assertFalse(st.traction || st.fatigueBlock);
        for (Model.Stage st : new Model().importShareCode(q.toShareCodeV2(m)).stages)
            assertFalse(st.traction || st.fatigueBlock);
    }

    @Test void aCodeStillNeverCarriesTheTrainerTrack() throws Exception {
        Model m = owner();
        Model.Routine r = StageShapeCarryTest.lengthMint(m);
        assertEquals(Model.TRAINER_TRACK_NONE, owner().importShareCode(r.toShareCode(m)).trainerTrack);
        assertEquals(Model.TRAINER_TRACK_NONE, owner().importShareCode(r.toShareCodeV2(m)).trainerTrack);
    }
}
