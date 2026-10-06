package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * INCOGNITO (0.10, the owner's decisions of 2026-09-26) - the pure decisions: every feature
 * its own switch, the master over the person's chosen set, the one launcher entry, quick
 * hide's action, and the words (no pressure, no name, STOP always).
 */
class IncognitoTest {

    @AfterEach void clearLock() { AppLock.clearAllSessionState(); }

    /* ---------------------------------------------------------------- defaults */

    @Test void everythingIsOffOnAFreshInstallAndOnAnOldSave() {
        Model fresh = new Model();
        assertEquals(0, Incognito.mask(fresh));
        assertFalse(fresh.incognito);
        assertEquals(Incognito.QH_STOP, fresh.quickHideAction, "C, leave and STOP, by default");
        Model old = Model.fromJson(new Model().toJson().replace("\"incog\"", "\"x_incog\""));
        assertEquals(0, Incognito.mask(old));
        assertFalse(old.incognito);
    }

    @Test void everySwitchSurvivesASave() {
        Model m = new Model();
        Incognito.setMaster(m, true);
        Incognito.setFeature(m, Incognito.RECENTS, true);
        m.quickHideAction = Incognito.QH_HOLD;
        Model back = Model.fromJson(m.toJson());
        assertEquals(Incognito.mask(m), Incognito.mask(back));
        assertEquals(Incognito.ALL, Incognito.mask(back));
        assertTrue(back.incognito);
        assertEquals(m.incognitoSet, back.incognitoSet);
        assertEquals(Incognito.QH_HOLD, back.quickHideAction);
    }

    @Test void aStrayQuickHideChoiceLoadsAsTheDefault() {
        Model m = new Model();
        m.quickHideAction = 7;
        assertEquals(Incognito.QH_STOP, Model.fromJson(m.toJson()).quickHideAction);
        assertEquals(Incognito.QH_STOP, Incognito.quickHideChoice(-1));
    }

    /* ---------------------------------------------------------------- the master */

    @Test void theMasterBringsTheFirstSetWhichLeavesScreenshotsAlone() {
        Model m = new Model();
        Incognito.setMaster(m, true);
        assertEquals(Incognito.FIRST_SET, Incognito.mask(m));
        assertFalse(m.secureWindow, "hide from recent apps blocks screenshots: its own switch, "
            + "default OFF");
        Incognito.setMaster(m, false);
        assertEquals(0, Incognito.mask(m));
    }

    @Test void eachFeatureCanBeOnWithoutTheMaster() {
        Model m = new Model();
        Incognito.setFeature(m, Incognito.NOTIFICATIONS, true);
        assertTrue(m.discreetNotifications);
        assertFalse(m.incognito);
        assertEquals(0, m.incognitoSet, "a switch flipped with the master off chooses nothing");
    }

    @Test void aSwitchThatWasAlreadyOnStaysTheirsThroughTheMaster() {
        Model m = new Model();
        m.discreetNotifications = true;               // T17's switch, on before incognito
        Incognito.setMaster(m, true);
        Incognito.setMaster(m, false);
        assertTrue(m.discreetNotifications, "master OFF takes away only what it brought");
        assertFalse(m.disguiseIcon);
    }

    @Test void underTheMasterTheSwitchesChooseTheSet() {
        Model m = new Model();
        Incognito.setMaster(m, true);
        Incognito.setFeature(m, Incognito.WIDGET, false);
        Incognito.setFeature(m, Incognito.RECENTS, true);
        Incognito.setMaster(m, false);
        assertEquals(0, Incognito.mask(m));
        Incognito.setMaster(m, true);
        assertFalse(m.hideWidget, "left out of the set: stays off");
        assertTrue(m.secureWindow, "added to the set: comes back");
        assertEquals((Incognito.FIRST_SET & ~Incognito.WIDGET) | Incognito.RECENTS,
            Incognito.mask(m));
    }

    @Test void aMasterThatWouldDoNothingBringsTheFirstSet() {
        Model m = new Model();
        Incognito.setMaster(m, true);
        for (int f : Incognito.FEATURES) Incognito.setFeature(m, f, false);
        Incognito.setMaster(m, false);
        Incognito.setMaster(m, true);
        assertEquals(Incognito.FIRST_SET, Incognito.mask(m));
    }

    @Test void theCoversLineSaysWhatTheMasterTurnsOn() {
        Model m = new Model();
        assertTrue(Incognito.coversLine(m).startsWith("Covers icon, notifications"));
        assertTrue(Incognito.coversLine(m).endsWith(" and widget."));
        assertFalse(Incognito.coversLine(m).contains("recent apps"));
        Incognito.setMaster(m, true);
        for (int f : Incognito.FEATURES)
            if (f != Incognito.ICON && f != Incognito.WIDGET) Incognito.setFeature(m, f, false);
        assertEquals("Covers icon and widget.", Incognito.coversLine(m));
    }

    /* ---------------------------------------------------------------- the launcher */

    /** Every identity the app can be shown as: itself, and each disguise. */
    private static int[] identities() {
        int[] out = new int[1 + Incognito.DISGUISES.length];
        out[0] = Incognito.REAL;
        for (int i = 0; i < Incognito.DISGUISES.length; i++) out[1 + i] = Incognito.DISGUISES[i];
        return out;
    }

    @Test void exactlyOneLauncherIsEnabledWhateverTheChoice() {
        // A stray identity (a newer app's, a bad index) is the app itself - still exactly one.
        int[] ids = identities();
        int[] all = java.util.Arrays.copyOf(ids, ids.length + 2);
        all[ids.length] = 7;
        all[ids.length + 1] = -5;
        for (int id : all) {
            int enabled = 0;
            for (String l : Incognito.LAUNCHERS)
                if (Incognito.launcherEnabled(l, id)) enabled++;
            assertEquals(1, enabled, "identity=" + id);
        }
        assertEquals(Incognito.LAUNCHER_REAL, Incognito.launcherFor(Incognito.REAL));
        assertEquals("org.openpump.LauncherFitnessLog", Incognito.launcherFor(Incognito.FITNESS_LOG));
        assertEquals("org.openpump.LauncherHabits", Incognito.launcherFor(Incognito.HABITS));
        assertEquals("org.openpump.LauncherNotes", Incognito.launcherFor(Incognito.NOTES));
        assertEquals("Fitness log", Incognito.shownName(Incognito.FITNESS_LOG));
        assertEquals("Habits", Incognito.shownName(Incognito.HABITS));
        assertEquals("Notes", Incognito.shownName(Incognito.NOTES));
        assertEquals("OpenPump", Incognito.shownName(Incognito.REAL));
    }

    /** Four launcher entries - the real one and three disguises - each its own, each disguise
     *  with its own name, icon, look, shortcuts and lock host, none of them the real one's. */
    @Test void everyDisguiseHasItsOwnEverything() {
        assertEquals(4, Incognito.LAUNCHERS.length);
        assertEquals(Incognito.LAUNCHER_REAL, Incognito.LAUNCHERS[0]);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String l : Incognito.LAUNCHERS) assertTrue(seen.add(l), "a launcher twice: " + l);
        String[][] tables = { Incognito.DISGUISE_NAMES, Incognito.DISGUISE_LOOKS,
            Incognito.DISGUISE_LAUNCHERS, Incognito.DISGUISE_ICONS, Incognito.DISGUISE_SHORTCUTS,
            Incognito.DISGUISE_LOCK_HOSTS, Incognito.DISGUISE_KEYS };
        for (String[] t : tables) {
            assertEquals(Incognito.DISGUISES.length, t.length, "a table a row short or long");
            assertEquals(t.length, new java.util.HashSet<String>(java.util.Arrays.asList(t)).size(),
                "two disguises share a value: " + java.util.Arrays.toString(t));
        }
        for (int d : Incognito.DISGUISES) {
            assertTrue(Incognito.disguised(d));
            assertFalse(Incognito.shownName(d).toLowerCase().contains("pump"));
            assertFalse(Incognito.iconName(d).equals(Incognito.REAL_ICON));
            assertFalse(Incognito.shortcutsFor(d).equals(Incognito.REAL_SHORTCUTS));
            assertTrue(Incognito.lockHostFor(d) != null
                && Incognito.lockHostFor(d).startsWith("org.openpump.LockHost$"));
            assertEquals(d, Incognito.disguiseFromKey(Incognito.disguiseKey(d)));
        }
        assertFalse(Incognito.disguised(Incognito.REAL));
        assertEquals(Incognito.REAL_ICON, Incognito.iconName(Incognito.REAL));
        assertEquals(Incognito.REAL_SHORTCUTS, Incognito.shortcutsFor(Incognito.REAL));
        assertEquals(null, Incognito.lockHostFor(Incognito.REAL), "the app itself asks from its "
            + "own screen");
    }

    /** What leaves the app under its name - a backup's file, an export's subject - never says
     *  "pump" under a disguise, and says what it always did as the app itself. */
    @Test void aBackupAndAnExportCarryTheDisguisesWords() {
        assertEquals("pump-backup.zip", Incognito.backupFileName(Incognito.REAL));
        assertEquals("Pump data export", Incognito.exportSubject(Incognito.REAL));
        assertEquals("notes-backup.zip", Incognito.backupFileName(Incognito.NOTES));
        for (int d : Incognito.DISGUISES) {
            assertFalse(Incognito.backupFileName(d).toLowerCase().contains("pump"));
            assertFalse(Incognito.exportSubject(d).toLowerCase().contains("pump"));
            assertTrue(Incognito.backupFileName(d).endsWith(".zip"));
        }
    }

    /** What the app is shown as: the icon switch decides whether, the choice which. */
    @Test void theIdentityIsTheSwitchAndTheChoice() {
        Model m = new Model();
        assertEquals(Incognito.FITNESS_LOG, m.disguiseAs, "a fresh install's choice");
        assertEquals(Incognito.REAL, Incognito.identity(m));
        m.disguiseAs = Incognito.NOTES;
        assertEquals(Incognito.REAL, Incognito.identity(m), "chosen but off: the app itself");
        m.disguiseIcon = true;
        assertEquals(Incognito.NOTES, Incognito.identity(m));
        m.disguiseAs = 42;
        assertEquals(Incognito.FITNESS_LOG, Incognito.identity(m), "a stray choice");
        assertEquals(Incognito.REAL, Incognito.identity(null));
    }

    /** The sheet's pick goes through the switch's own rule: under the master it chooses the
     *  set; picking the app itself keeps the disguise for next time. */
    @Test void choosingADisguiseIsTheIconSwitch() {
        Model m = new Model();
        Incognito.choose(m, Incognito.HABITS);
        assertTrue(m.disguiseIcon);
        assertEquals(Incognito.HABITS, Incognito.identity(m));
        assertEquals(0, m.incognitoSet, "with the master off it chooses nothing");
        Incognito.choose(m, Incognito.REAL);
        assertFalse(m.disguiseIcon);
        assertEquals(Incognito.HABITS, m.disguiseAs, "kept for next time");
        Incognito.setMaster(m, true);
        assertEquals(Incognito.HABITS, Incognito.identity(m), "the master brings the one chosen");
        Incognito.choose(m, Incognito.REAL);
        assertEquals(0, m.incognitoSet & Incognito.ICON, "under the master: left out of the set");
        Incognito.choose(m, Incognito.NOTES);
        assertTrue((m.incognitoSet & Incognito.ICON) != 0, "under the master: back in the set");
        Incognito.setMaster(m, false);
        assertEquals(Incognito.REAL, Incognito.identity(m));
        Incognito.setMaster(m, true);
        assertEquals(Incognito.NOTES, Incognito.identity(m));
    }

    /** The choice is saved as a word. An old save - no word - is "Fitness log", so a person
     *  with the disguise on sees no change; an unknown word is too; the rest of the file's keys
     *  (a newer app's) are kept. */
    @Test void theChoiceSurvivesASaveAndOldSavesKeepFitnessLog() {
        Model m = new Model();
        Incognito.choose(m, Incognito.NOTES);
        String json = m.toJson();
        assertTrue(json.contains("\"disguiseAs\":\"notes\""), json);
        Model back = Model.fromJson(json);
        assertEquals(Incognito.NOTES, Incognito.identity(back));

        Model old = Model.fromJson(json.replace("\"disguiseAs\"", "\"x_future_key\""));
        assertTrue(old.disguiseIcon);
        assertEquals(Incognito.FITNESS_LOG, Incognito.identity(old), "an old save with it on");
        assertTrue(old.toJson().contains("\"x_future_key\""), "an unknown key is dropped");

        Model odd = Model.fromJson(json.replace("\"notes\"", "\"calendar\""));
        assertEquals(Incognito.FITNESS_LOG, Incognito.identity(odd), "an unknown disguise");
        assertEquals(Incognito.FITNESS_LOG, Incognito.disguiseFromKey(null));
        assertEquals(Incognito.HABITS, Incognito.disguiseFromKey(" habits "));
    }

    /** (the incognito safety review, I1) Switching the icon removes the task its launcher entry
     *  rooted - the trampoline's, which held no screen. Only the screen's own task going is a
     *  run interrupted; one with no name is taken as the screen's. */
    @Test void onlyTheScreensOwnTaskGoingSaysAnything() {
        assertTrue(Incognito.isScreenTask("org.openpump.SessionActivity"));
        assertTrue(Incognito.isScreenTask(null), "unknown: the truthful notice is not skipped");
        assertTrue(Incognito.isScreenTask(""));
        assertFalse(Incognito.isScreenTask(Incognito.LAUNCHER_REAL), "the icon's trampoline task");
        for (String l : Incognito.DISGUISE_LAUNCHERS) assertFalse(Incognito.isScreenTask(l));
        assertFalse(Incognito.isScreenTask("org.openpump.LauncherTrampoline"));
        assertFalse(Incognito.isScreenTask("org.openpump.ShortcutTrampoline"));
    }

    /* ---------------------------------------------------------------- quick hide */

    @Test void outsideARunQuickHideJustLeaves() {
        for (int c : new int[]{ Incognito.QH_LEAVE, Incognito.QH_HOLD, Incognito.QH_STOP })
            assertEquals(Incognito.DO_LEAVE, Incognito.quickHide(c, false, false, false));
    }

    @Test void duringARunItIsThePersonsChoiceAndStopByDefault() {
        assertEquals(Incognito.DO_STOP_THEN_LEAVE,
            Incognito.quickHide(Incognito.QH_DEFAULT, true, true, false));
        assertEquals(Incognito.DO_LEAVE,
            Incognito.quickHide(Incognito.QH_LEAVE, true, true, false));
        assertEquals(Incognito.DO_HOLD_THEN_LEAVE,
            Incognito.quickHide(Incognito.QH_HOLD, true, true, false));
        assertEquals(Incognito.DO_STOP_THEN_LEAVE,
            Incognito.quickHide(99, true, true, false), "a stray choice is the default");
    }

    @Test void pauseNeverReleasesAHoldAndStopsWhatCannotBePaused() {
        assertEquals(Incognito.DO_LEAVE,
            Incognito.quickHide(Incognito.QH_HOLD, true, true, true),
            "a Hold already up stays up - B never toggles it off");
        assertEquals(Incognito.DO_STOP_THEN_LEAVE,
            Incognito.quickHide(Incognito.QH_HOLD, true, false, false),
            "a self-test or validation has no Hold: the safe side of pause is STOP");
    }

    /** (the incognito safety review, I2) B chose pause over "the run keeps going": a Hold that
     *  cannot go up - refused on the spot (a rest, a frozen phase, no reading, the pump busy) -
     *  is STOP, never a run left going with the app hidden. */
    @Test void aPauseThatCannotBeIsStopNeverARunLeftGoing() {
        assertEquals(Incognito.PAUSED, Incognito.pauseAsked(true, false), "up at once");
        assertEquals(Incognito.PAUSE_WAITS, Incognito.pauseAsked(false, true),
            "written, the pump has not said: it waits on the answer");
        assertEquals(Incognito.PAUSE_STOPS, Incognito.pauseAsked(false, false),
            "refused on the spot: STOP");
    }

    /** ...and a pause that waits ends as HOLDING or as STOP: refused, never written, undone for
     *  want of an answer, the pump not answering, taken as the run moved on - all STOP. */
    @Test void aPauseEndsHoldingOrStopped() {
        assertEquals(Incognito.PAUSED, Incognito.pauseAnswered(true));
        assertEquals(Incognito.PAUSE_STOPS, Incognito.pauseAnswered(false));
    }

    /** The whole of B, as quick hide runs it: the decision, then the pause. With a Hold already
     *  up B just leaves; with none it is asked for, and a refusal is STOP. */
    @Test void quickHideBDuringARestStops() {
        int what = Incognito.quickHide(Incognito.QH_HOLD, true, true, false);
        assertEquals(Incognito.DO_HOLD_THEN_LEAVE, what);
        // A rest between sets: enterHold returns at canCommandNow() - nothing written.
        assertEquals(Incognito.PAUSE_STOPS, Incognito.pauseAsked(false, false));
        assertEquals(Incognito.DO_LEAVE, Incognito.quickHide(Incognito.QH_HOLD, true, true, true),
            "a Hold already up stays, with its limit");
    }

    @Test void aDoubleTapIsTwoTapsInsideTheTimeout() {
        assertFalse(Incognito.isDoubleTap(0L, 1000L, 300L), "a first tap");
        assertTrue(Incognito.isDoubleTap(1000L, 1250L, 300L));
        assertFalse(Incognito.isDoubleTap(1000L, 1400L, 300L));
        assertFalse(Incognito.isDoubleTap(1000L, 900L, 300L), "a clock that went back");
    }

    /* ---------------------------------------------------------------- the words */

    @Test void theDiscreetRunLineIsOnlyTheTime() {
        assertEquals("12:30 left", Incognito.runText("12:30", ""));
        assertEquals("Paused · ends in 4:32",
            Incognito.runText("1:30", InRunHold.ventsIn(272_000L)));
        assertEquals("In progress", Incognito.runText(null, null));
        assertEquals("Session running", Session.runNotificationTitle(true, "Girth L3"));
        String body = Session.runNotificationText(true, "Girth L3", 4, 10, "12:30", "-8.6 inHg");
        assertEquals("12:30 left", body);
        assertFalse(body.contains("inHg") || body.contains("Girth") || body.contains("4 of"));
    }

    @Test void theDiscreetHoldLineSaysWhenItEndsAndNothingElse() {
        assertEquals("Ends on its own in 4:32", Incognito.heldText(272));
        assertEquals("Ends on its own in 0:00", Incognito.heldText(-5));
    }

    @Test void theLockScreenWordsAreNeutralAndStopStays() {
        for (String s : new String[]{ Incognito.HIDDEN, Incognito.SAFETY_LOCKED_TITLE,
                                      Incognito.SAFETY_LOCKED_TEXT, Incognito.REMINDER_TITLE,
                                      Incognito.RUN_TITLE, Incognito.STOP_LABEL_LOCKED }) {
            String l = s.toLowerCase(java.util.Locale.ROOT);
            assertFalse(l.contains("pump") || l.contains("vent") || l.contains("pressure")
                || l.contains("cuff") || l.contains("inhg") || l.contains("kpa"), s);
        }
        assertTrue(Incognito.STOP_LABEL.startsWith("STOP"));
        assertTrue(Incognito.STOP_LABEL_LOCKED.startsWith("STOP"));
        assertTrue(Incognito.SAFETY_LOCKED_TITLE.contains("now"), "still urgent");
    }

    /** (the incognito safety review, M1) Every ordinary STOP shows the venting notice for a few
     *  seconds: on the lock screen it is calm, never the alarm's words, and says nothing of
     *  the pump. */
    @Test void theVentingNoticeIsCalmOnTheLockScreen() {
        String l = Incognito.ENDING_LOCKED_TITLE.toLowerCase(java.util.Locale.ROOT);
        assertFalse(l.contains("pump") || l.contains("vent") || l.contains("pressure")
            || l.contains("cuff"), Incognito.ENDING_LOCKED_TITLE);
        assertFalse(l.contains("attention") || l.contains("now") || l.contains("unlock"),
            "the alarm's words are kept for the alarm");
        assertFalse(Incognito.ENDING_LOCKED_TITLE.equals(Incognito.SAFETY_LOCKED_TITLE));
    }

    /* ---------------------------------------------------------------- the app lock */

    @Test void incognitosAppLockIsTheWholeAppGate() {
        Model m = new Model();
        assertFalse(AppLock.isLocked(m, AppLock.WHOLE_APP));
        m.incognitoLock = true;
        assertTrue(AppLock.isLocked(m, AppLock.WHOLE_APP));
        assertFalse(AppLock.isLocked(m, AppLock.PHOTOS), "the whole-app gate supersedes areas");
        assertFalse(m.appLockOn, "the App lock card's own switches are left as they were");
        AppLock.markUnlocked(AppLock.WHOLE_APP);
        assertFalse(AppLock.isLocked(m, AppLock.WHOLE_APP), "passed once, like the card's own");
    }

    /** (the incognito safety review, M5) Quick hide re-arms the whole-app lock - only when it
     *  is on - and the way back asks for it only with nothing about the pump live or owed and
     *  no safety question in front of the person. */
    @Test void quickHideReArmsTheWholeAppLockWhenItIsOn() {
        Model m = new Model();
        AppLock.markUnlocked(AppLock.WHOLE_APP);
        AppLock.relockWholeApp(m);
        assertFalse(AppLock.isLocked(m, AppLock.WHOLE_APP), "no lock on: nothing to re-arm");
        m.incognitoLock = true;
        assertFalse(AppLock.isLocked(m, AppLock.WHOLE_APP), "passed at the cold open");
        AppLock.relockWholeApp(m);
        assertTrue(AppLock.isLocked(m, AppLock.WHOLE_APP), "armed again by the quick hide");
        AppLock.markUnlocked(AppLock.WHOLE_APP);
        assertFalse(AppLock.isLocked(m, AppLock.WHOLE_APP));
    }

    @Test void theLockAfterAQuickHideIsNeverInFrontOfThePump() {
        int none = HoldForeground.NONE;
        assertTrue(AppLock.relockAsks(true, false, none, false), "nothing live: it asks");
        assertFalse(AppLock.relockAsks(false, false, none, false), "not armed: nothing to ask");
        assertFalse(AppLock.relockAsks(true, true, none, false),
            "a run, a hold, a check or a stop not yet confirmed: never in front of it");
        for (int phase : new int[]{ HoldForeground.HELD, HoldForeground.VENTING,
                                    HoldForeground.UNCONFIRMED, HoldForeground.UNCONFIRMED_SEEN })
            assertFalse(AppLock.relockAsks(true, false, phase, false), "hold phase " + phase);
        assertFalse(AppLock.relockAsks(true, false, none, true),
            "the give-up question or a one-time safety message is up");
    }

    @Test void theScopedLockIsUnchangedWithIncognitoOff() {
        Model m = new Model();
        m.appLockOn = true;
        m.appLockPhotos = true;
        assertTrue(AppLock.isLocked(m, AppLock.PHOTOS));
        assertFalse(AppLock.isLocked(m, AppLock.WHOLE_APP));
        m.appLockOn = false;
        m.appLockWholeApp = true;
        assertFalse(AppLock.isLocked(m, AppLock.WHOLE_APP), "the card's master still rules it");
    }
}
