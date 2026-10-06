package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

/** What the debug log may not say - each rule of {@link Redact}, and the journal using it. */
class RedactTest {

    @Test void registeredNamesBecomeStableTokens() {
        Redact r = new Redact();
        r.register("routine", "Morning Stretch");
        r.register("set", "Long hold");
        assertEquals("--- RUN ‹routine1›: ‹set1› then ‹set1›",
            r.scrub("--- RUN Morning Stretch: Long hold then long HOLD"));
        r.register("routine", "morning stretch");               // same text, any case
        r.register("routine", "Evening");
        assertEquals("‹routine2›", r.scrub("Evening"));
        assertEquals("‹routine1›", r.scrub("Morning Stretch"));
    }

    @Test void namesMatchWholeWordsOnlyAndLongestFirst() {
        Redact r = new Redact();
        r.register("set", "Leg");
        r.register("set", "Leg day");
        assertEquals("Legacy ‹set2› and ‹set1›", r.scrub("Legacy Leg day and Leg"));
    }

    @Test void shortNamesAreNotRegistered() {
        Redact r = new Redact();
        r.register("set", "A");
        r.register("set", "  ab ");
        assertEquals(0, r.size());
        assertEquals("A tab", r.scrub("A tab"));
    }

    @Test void machineIdentifiersAreMasked() {
        Redact r = new Redact();
        assertEquals("connect ‹mac› rssi -60",
            r.scrub("connect AA:BB:CC:11:22:33 rssi -60"));
        assertEquals("from ‹mac›", r.scrub("from aa-bb-cc-11-22-3f"));
        assertEquals("mail ‹email› now", r.scrub("mail someone.else+x@example.co.uk now"));
        assertEquals("!! could not delete ‹path›: denied",
            r.scrub("!! could not delete /storage/emulated/0/Android/data/org.openpump/files/Pictures/reading-3-front.jpg: denied"));
        assertEquals("grant ‹uri›", r.scrub("grant content://org.openpump.log/session-1.txt"));
        // Ordinary text survives: times, ratios, pressures.
        assertEquals("12:30 1/3 40.0 kPa", r.scrub("12:30 1/3 40.0 kPa"));
    }

    @Test void digitsAndFields() {
        assertEquals("Girth ##.# cm", Redact.maskDigits("Girth 12.5 cm"));
        assertEquals("field Note len=7", Redact.field("Note", 7));
        assertEquals("field len=0", Redact.field(null, 0));
    }

    @Test void maskedScreensAreEverythingButPumpAndEditorScreens() throws Exception {
        assertFalse(Redact.maskedScreen(Nav.SCR_RUN));
        assertFalse(Redact.maskedScreen(Nav.SCR_SET_EDIT));
        int[] sensitive = { Nav.SCR_MEAS_HIST, Nav.SCR_COMPARE, Nav.SCR_MEAS_EDIT, Nav.SCR_CAMERA,
            Nav.SCR_PHOTO, Nav.SCR_GALLERY, Nav.SCR_BASELINE, Nav.SCR_MEASURE_AFTER,
            Nav.SCR_LOG_READING, Nav.SCR_PROGRESS, Nav.SCR_SUMMARY, Nav.SCR_TODAY,
            Nav.SCR_TRAINER, 999 };
        for (int s : sensitive) assertTrue(Redact.maskedScreen(s), "screen " + s + " must be masked");
        // Every screen Nav declares has a name in the journal.
        for (Field f : Nav.class.getFields()) {
            if (!f.getName().startsWith("SCR_") || f.getType() != int.class) continue;
            if (!Modifier.isStatic(f.getModifiers())) continue;
            String name = Journal.screenName(f.getInt(null));
            assertFalse(name.startsWith("screen"), f.getName() + " has no journal name");
        }
    }

    @Test void theModelsOwnWordsAreRegistered() {
        Model m = new Model();
        Model.Routine r = Model.Routine.of("r1", "Secret Plan", new String[0]);
        r.stages.add(Model.Stage.of("Private Stage", 0, new String[0]));
        m.routines.add(r);
        Model.Set s = Model.Set.fixed("s1", "Hidden Set", 40, 10, 10, 5, 60, 60);
        m.sets.add(s);
        Redact red = new Redact();
        red.registerModel(m);
        String out = red.scrub("Secret Plan / Private Stage / Hidden Set");
        assertFalse(out.contains("Secret") || out.contains("Private") || out.contains("Hidden"), out);
    }

    @Test void theJournalScrubsWhatItWrites() {
        Journal j = new Journal(0);
        j.redact.register("routine", "Morning Stretch");
        j.nav(0, Nav.SCR_MEAS_EDIT);
        j.tap(100, "Increase girth, now 12.5 cm", true, true, false, null, 1);
        j.toast(200, "Saved 12.5 cm");
        j.dialog(300, true, "Delete reading of 3 May?");
        j.nav(400, Nav.SCR_RUN);
        j.tap(500, "Start Morning Stretch at 40.0 kPa", true, true, false, null, 1);
        j.cmd(600, Proto.startSlot(0), "start Morning Stretch");
        j.app(700, "=== RUN Morning Stretch from /storage/emulated/0/x/y.jpg AA:BB:CC:DD:EE:FF");
        String text = j.drain();
        assertTrue(text.contains("TAP meas-edit \"Increase girth, now ##.# cm\""), text);
        assertTrue(text.contains("UI toast \"Saved ##.# cm\""), text);
        assertTrue(text.contains("NAV dialog \"Delete reading of # May?\""), text);
        assertTrue(text.contains("TAP run \"Start ‹routine1› at 40.0 kPa\""), text);
        assertTrue(text.contains("CMD start slot=0 \"start ‹routine1›\""), text);
        assertTrue(text.contains("APP === RUN ‹routine1› from ‹path› ‹mac›"), text);
        assertFalse(text.contains("12.5"), text);
        assertFalse(text.contains("Morning"), text);
    }

    @Test void theHeaderNamesNobody() {
        Journal j = new Journal(0);
        j.header("0.9.0", 34, "Pixel 7", true, "inHg");
        String h = j.drain();
        assertTrue(h.startsWith("# OpenPump journal v1"), h);
        assertTrue(h.contains("app=0.9.0 android=34 device=Pixel_7 pump=sim unit=inHg"), h);
        assertNotEquals(-1, h.indexOf('\n'));
    }
}
