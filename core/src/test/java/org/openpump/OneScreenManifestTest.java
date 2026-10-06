package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * (re-review 4) ONE SCREEN, AND NO SHORTCUT THAT CAN END IT - the manifest's half.
 *
 * SessionActivity owns the pump's one link, the run and the hold. A second instance created
 * over it took RunService away from the first (setLive), replaced its notice, opened a second
 * PumpLink - and the service, asking the new screen, found nothing live and stopped, leaving
 * the first one's run or hold with no foreground, no STOP and no kill protection. So:
 *   1. SessionActivity is launchMode="singleTask": every intent - the launcher icon, the
 *      notices, the widget, the reminders, the Play Store's "Open", a shortcut - reaches the
 *      one instance through onNewIntent (WiringCheck invariant 108 holds its code to that);
 *   2. the static shortcuts target ShortcutTrampoline, never SessionActivity: Android starts a
 *      static shortcut with NEW_TASK | CLEAR_TASK, which would clear the running screen's task
 *      and end the run in its onDestroy. The trampoline has its own taskAffinity, so the clear
 *      takes only its own task; it is noHistory, excludeFromRecents and shows nothing, and it
 *      forwards to the one screen (invariant 118).
 */
class OneScreenManifestTest {

    private static final Path SRC = Paths.get("../app/src/main");

    @Test void theSessionScreenIsSingleTask() throws IOException {
        String a = activity("org.openpump.SessionActivity", ".SessionActivity");
        assertNotNull(a, "SessionActivity is not declared");
        assertTrue(a.contains("android:launchMode=\"singleTask\""),
            "SessionActivity is not singleTask - a tap into the app can create a second screen "
            + "that takes the service away from the running one");
        assertFalse(a.contains("android:taskAffinity"),
            "SessionActivity must keep the app's own task, where every entry finds it");
    }

    @Test void theTrampolineHasItsOwnTaskAndShowsNothing() throws IOException {
        String a = activity("org.openpump.ShortcutTrampoline", ".ShortcutTrampoline");
        assertNotNull(a, "ShortcutTrampoline is not declared");
        Matcher m = Pattern.compile("android:taskAffinity=\"([^\"]*)\"").matcher(a);
        assertTrue(m.find(), "the trampoline has no taskAffinity of its own - a shortcut's "
            + "CLEAR_TASK would clear the running screen's task");
        String aff = m.group(1);
        assertFalse(aff.isEmpty() || aff.equals("org.openpump"),
            "the trampoline's affinity must not be the app's own: " + aff);
        assertTrue(a.contains("android:noHistory=\"true\""), "the trampoline keeps history");
        assertTrue(a.contains("android:excludeFromRecents=\"true\""),
            "the trampoline shows in Recents");
        assertTrue(a.contains("@android:style/Theme.NoDisplay"),
            "the trampoline draws something");
        assertFalse(a.contains("android:launchMode"), "the trampoline needs no launch mode");
    }

    @Test void everyShortcutGoesThroughTheTrampoline() throws IOException {
        // Incognito (0.10): each launcher entry carries its own file - every one is held to it.
        String[] files = new String[1 + Incognito.DISGUISES.length];
        files[0] = "res/xml/" + Incognito.shortcutsFor(Incognito.REAL) + ".xml";
        for (int i = 0; i < Incognito.DISGUISES.length; i++)
            files[1 + i] = "res/xml/" + Incognito.shortcutsFor(Incognito.DISGUISES[i]) + ".xml";
        for (String f : files) {
            String x = withoutXmlComments(read(SRC.resolve(f)));
            Matcher m = Pattern.compile("android:targetClass=\"([^\"]*)\"").matcher(x);
            int n = 0;
            while (m.find()) {
                n++;
                assertEquals("org.openpump.ShortcutTrampoline", m.group(1),
                    f + ": a shortcut targets " + m.group(1) + " directly - its NEW_TASK | "
                    + "CLEAR_TASK would end a run on that screen");
            }
            assertEquals(3, n, f + ": expected the three static shortcuts");
        }
    }

    /**
     * INCOGNITO (0.10) - THE LAUNCHER ENTRIES ARE ALIASES OF A TRAMPOLINE TO THE ONE SCREEN.
     *
     * The home-screen icon is "OpenPump" or a disguise - "Fitness log", "Habits", "Notes" -
     * one activity-alias each. Only the real one is enabled as installed, each carries its own
     * shortcuts, and neither SessionActivity nor the trampoline has a launcher filter - or the
     * drawer would show an undisguised extra icon.
     *
     * (the incognito safety review, I1) Every alias targets LauncherTrampoline, never
     * SessionActivity: switching an alias off removes every task whose root intent names it,
     * and a screen opened from the icon had the alias as its task's root - the switch finished
     * the running screen. The trampoline has its own taskAffinity, keeps no history, stays out
     * of Recents and shows nothing; it starts SessionActivity by name (WiringCheck invariant
     * 198), so the screen's task is never an alias's. And Android needs the target declared
     * before its aliases.
     */
    @Test void theLauncherEntriesAreAliasesOfTheOneScreen() throws IOException {
        String x = withoutXmlComments(read(SRC.resolve("AndroidManifest.xml")));
        String session = activity("org.openpump.SessionActivity", ".SessionActivity");
        assertFalse(session.contains("android.intent.category.LAUNCHER"),
            "SessionActivity has its own launcher filter - a third, undisguised icon");
        String t = activity("org.openpump.LauncherTrampoline", ".LauncherTrampoline");
        assertNotNull(t, "LauncherTrampoline is not declared");
        Matcher aff = Pattern.compile("android:taskAffinity=\"([^\"]*)\"").matcher(t);
        assertTrue(aff.find(), "the launcher trampoline has no taskAffinity of its own");
        assertFalse(aff.group(1).isEmpty() || aff.group(1).equals("org.openpump"),
            "the launcher trampoline's affinity must not be the app's own: " + aff.group(1));
        assertTrue(t.contains("android:noHistory=\"true\""), "the launcher trampoline keeps history");
        assertTrue(t.contains("android:excludeFromRecents=\"true\""),
            "the launcher trampoline shows in Recents");
        assertTrue(t.contains("@android:style/Theme.NoDisplay"),
            "the launcher trampoline draws something");
        assertFalse(t.contains("android.intent.category.LAUNCHER"),
            "the launcher trampoline has its own launcher filter - a third icon");
        assertFalse(t.contains("android:launchMode"), "the launcher trampoline needs no launch mode");
        int trampolineAt = x.indexOf("android:name=\".LauncherTrampoline\"");
        int enabled = 0;
        for (String full : Incognito.LAUNCHERS) {
            String rel = full.substring("org.openpump".length());
            String a = aliasIn(x, full, rel);
            assertNotNull(a, full + " is not declared");
            assertTrue(a.contains("android:targetActivity=\".LauncherTrampoline\"")
                || a.contains("android:targetActivity=\"org.openpump.LauncherTrampoline\""),
                full + " must start LauncherTrampoline - an alias of SessionActivity roots the "
                + "running screen's task, and switching the icon removes it");
            assertTrue(trampolineAt >= 0 && trampolineAt < x.indexOf(a),
                full + " is declared before the activity it targets");
            assertTrue(a.contains("android.intent.category.LAUNCHER"), full + " has no launcher filter");
            assertTrue(a.contains("android.app.shortcuts"), full + " carries no shortcuts");
            boolean on = a.contains("android:enabled=\"true\"");
            if (on) {
                enabled++;
                assertEquals(Incognito.LAUNCHER_REAL, full, "only the real icon ships enabled");
            } else {
                assertTrue(a.contains("android:enabled=\"false\""), full + " must say its state");
            }
        }
        assertEquals(1, enabled, "exactly one launcher entry is enabled as installed");
        Matcher m = Pattern.compile("<activity-alias\\b").matcher(x);
        int aliases = 0;
        while (m.find()) aliases++;
        assertEquals(Incognito.LAUNCHERS.length, aliases, "an alias Incognito does not switch");
    }

    /**
     * THE DISGUISES (incognito, 0.10; the owner, 2026-09-28) - EACH IS WHOLE, AND ITS OWN.
     *
     * Incognito holds every disguise as one row: a name, a launcher alias, an icon, shortcuts, a
     * lock host. Here the manifest and the resources are held to those rows, so a disguise can
     * never reach the home screen, a long-press, the PIN prompt or the splash half-drawn, or
     * wearing another's icon or the real one:
     *   - each launcher alias has its identity's label, icon, round icon and shortcuts file;
     *   - each disguise's icon is an adaptive icon (background, foreground and a themed layer)
     *     with a round form, and a whole vector below Android 8, as Fitness log's always was;
     *   - each shortcuts file shows its own identity's icon on all three shortcuts;
     *   - each lock host is declared with its disguise's label and icons, not exported, in the
     *     screen's own task, drawing nothing, and never a launcher entry (WiringCheck 233);
     *   - the splash below Android 13 names no logo, and from 13 each disguise has a splash
     *     theme with its own icon (WiringCheck 234).
     * Exactly one alias enabled as installed is theLauncherEntriesAreAliasesOfTheOneScreen's.
     */
    @Test void everyLauncherEntryCarriesItsIdentity() throws IOException {
        String x = withoutXmlComments(read(SRC.resolve("AndroidManifest.xml")));
        for (int id : identities()) {
            String full = Incognito.launcherFor(id);
            String a = aliasIn(x, full, full.substring("org.openpump".length()));
            assertNotNull(a, full + " is not declared");
            String icon = Incognito.iconName(id);
            assertEquals(Incognito.shownName(id), attr(a, "label"), full + ": the wrong name");
            assertEquals("@mipmap/" + icon, attr(a, "icon"), full + ": the wrong icon");
            assertEquals("@mipmap/" + icon + "_round", attr(a, "roundIcon"),
                full + ": the wrong round icon");
            assertEquals("@xml/" + Incognito.shortcutsFor(id), attr(a, "resource"),
                full + ": the wrong shortcuts");
        }
    }

    @Test void everyDisguiseHasAWholeIconOfItsOwn() throws IOException {
        for (int d : Incognito.DISGUISES) {
            String icon = Incognito.iconName(d);
            for (String form : new String[]{ icon, icon + "_round" }) {
                Path adaptive = SRC.resolve("res/mipmap-anydpi-v26/" + form + ".xml");
                assertTrue(Files.exists(adaptive), form + ": no adaptive icon");
                String ai = withoutXmlComments(read(adaptive));
                assertTrue(ai.contains("<adaptive-icon"), form + " is not an adaptive icon");
                for (String layer : new String[]{ "background", "foreground", "monochrome" }) {
                    Matcher m = Pattern.compile("<" + layer + "\\s+android:drawable=\"@drawable/"
                        + "([A-Za-z0-9_]+)\"").matcher(ai);
                    assertTrue(m.find(), form + ": no " + layer + " layer");
                    assertTrue(m.group(1).startsWith(icon + "_"),
                        form + "'s " + layer + " is not its own: " + m.group(1));
                    assertTrue(Files.exists(SRC.resolve("res/drawable/" + m.group(1) + ".xml")),
                        form + ": " + m.group(1) + " is missing");
                }
                assertTrue(Files.exists(SRC.resolve("res/mipmap-anydpi/" + form + ".xml")),
                    form + ": nothing to draw below Android 8");
            }
            // Its shortcuts wear its icon, never the real one.
            String sc = withoutXmlComments(read(SRC.resolve("res/xml/"
                + Incognito.shortcutsFor(d) + ".xml")));
            Matcher m = Pattern.compile("android:icon=\"([^\"]*)\"").matcher(sc);
            int n = 0;
            while (m.find()) {
                n++;
                assertEquals("@mipmap/" + icon, m.group(1),
                    Incognito.shortcutsFor(d) + ": a shortcut shows another icon");
            }
            assertEquals(3, n, Incognito.shortcutsFor(d) + ": expected the three shortcuts");
        }
    }

    @Test void everyDisguiseAsksForThePinUnderItsOwnName() throws IOException {
        String x = withoutXmlComments(read(SRC.resolve("AndroidManifest.xml")));
        for (int d : Incognito.DISGUISES) {
            String full = Incognito.lockHostFor(d);
            String h = activityIn(x, full, full.substring("org.openpump".length()));
            assertNotNull(h, full + " is not declared - the PIN would be asked under the real name");
            String icon = Incognito.iconName(d);
            assertEquals(Incognito.shownName(d), attr(h, "label"), full + ": the wrong name");
            assertEquals("@mipmap/" + icon, attr(h, "icon"), full + ": the wrong icon");
            assertEquals("@mipmap/" + icon + "_round", attr(h, "roundIcon"),
                full + ": the wrong round icon");
            assertEquals("false", attr(h, "exported"), full + " must not be exported");
            assertFalse(h.contains("android.intent.category.LAUNCHER"),
                full + " has a launcher filter - an extra icon");
            assertFalse(h.contains("android:taskAffinity"),
                full + " must ask in the screen's own task");
            assertFalse(h.contains("android:noHistory=\"true\""),
                full + " keeps no history - the PIN screen's answer would never reach it");
            assertTrue(h.contains("Translucent"), full + " draws something");
        }
    }

    @Test void theSplashNeverShowsTheRealLogoWhereItCannotBeSwapped() throws IOException {
        for (String dir : new String[]{ "values", "values-v31" }) {
            String st = withoutXmlComments(read(SRC.resolve("res/" + dir + "/styles.xml")));
            assertFalse(st.contains("ic_launcher") || st.contains("splash_background"),
                dir + ": the splash names the real logo, which no disguise can swap there");
        }
        String v31 = withoutXmlComments(read(SRC.resolve("res/values-v31/styles.xml")));
        assertTrue(v31.contains("windowSplashScreenAnimatedIcon"),
            "Android 12's own splash would draw the real launcher icon");
        String v33 = withoutXmlComments(read(SRC.resolve("res/values-v33/styles.xml")));
        for (int d : Incognito.DISGUISES) {
            String host = Incognito.lockHostFor(d);
            String name = "Splash" + host.substring(host.lastIndexOf('$') + 1);
            Matcher m = Pattern.compile("(?s)<style\\s+name=\"" + name + "\"[^>]*>(.*?)</style>")
                .matcher(v33);
            assertTrue(m.find(), name + " is not declared for Android 13");
            assertTrue(m.group(1).contains("@mipmap/" + Incognito.iconName(d)),
                name + " does not show its disguise's icon");
        }
    }

    /** The real identity first, then each disguise. */
    private static int[] identities() {
        int[] out = new int[1 + Incognito.DISGUISES.length];
        out[0] = Incognito.REAL;
        for (int i = 0; i < Incognito.DISGUISES.length; i++) out[1 + i] = Incognito.DISGUISES[i];
        return out;
    }

    /** An android: attribute's value in an element, or null. */
    private static String attr(String element, String name) {
        Matcher m = Pattern.compile("android:" + Pattern.quote(name) + "=\"([^\"]*)\"")
            .matcher(element);
        return m.find() ? m.group(1) : null;
    }

    @Test void theRuleSeesWhatItIsFor() {
        String single = "<activity android:name=\".SessionActivity\" android:launchMode=\"singleTask\" />";
        assertTrue(single.contains("android:launchMode=\"singleTask\""));
        assertFalse(withoutXmlComments("<!-- android:launchMode=\"singleTask\" -->")
            .contains("singleTask"));
        assertNotNull(activityIn("<application><activity android:name=\".X\" a=\"1\">"
            + "<intent-filter/></activity></application>", "org.openpump.X", ".X"));
    }

    /**
     * THE RING TIMER'S ALARM (0.10, M4). "Take it off" is an AlarmManager alarm delivered to
     * RingTimerReceiver, so it arrives with the process gone. The receiver is this app's own
     * (exported=false), hears a reboot (a reboot drops every alarm) and the exact-alarm
     * permission's change; SCHEDULE_EXACT_ALARM is declared for every API (no maxSdkVersion,
     * or newer Android could never allow it), and USE_EXACT_ALARM - Play's alarm-clock-app permission -
     * is not.
     */
    @Test void theRingTimerHasItsReceiverAndItsPermission() throws IOException {
        String x = withoutXmlComments(read(SRC.resolve("AndroidManifest.xml")));
        String r = receiverIn(x, "org.openpump.RingTimerReceiver", ".RingTimerReceiver");
        assertNotNull(r, "RingTimerReceiver is not declared - the ring alarm has nowhere to land");
        assertTrue(r.contains("android:exported=\"false\""),
            "the ring timer's receiver must be exported=false: only this app's alarm fires it");
        assertTrue(r.contains("android.intent.action.BOOT_COMPLETED"),
            "the ring timer's receiver must hear a reboot, which drops every alarm");
        assertTrue(r.contains("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"),
            "the ring timer's receiver must hear exact alarms being allowed");
        Matcher perm = Pattern.compile("<uses-permission\\b[^>]*android:name=\""
            + "android\\.permission\\.SCHEDULE_EXACT_ALARM\"[^>]*>").matcher(x);
        assertTrue(perm.find(), "SCHEDULE_EXACT_ALARM is not declared");
        assertFalse(perm.group().contains("maxSdkVersion"),
            "SCHEDULE_EXACT_ALARM must not stop at an API level - newer Android could never allow it");
        assertFalse(x.contains("android.permission.USE_EXACT_ALARM"),
            "USE_EXACT_ALARM is for alarm-clock and calendar apps; this app asks for "
            + "SCHEDULE_EXACT_ALARM instead");
        assertNotNull(receiverIn("<receiver android:name=\".A\" android:exported=\"false\" />",
            "org.openpump.A", ".A"), "the receiver finder reads a self-closed tag");
    }

    /* ------------------------------------------------------------------------ helpers */

    /** The whole <receiver ...> element. */
    static String receiverIn(String xml, String full, String rel) {
        Matcher m = Pattern.compile("<receiver\\b[^>]*android:name=\"(?:" + Pattern.quote(full)
            + "|" + Pattern.quote(rel) + ")\"[^>]*>").matcher(xml);
        if (!m.find()) return null;
        String open = m.group();
        if (open.endsWith("/>")) return open;
        int close = xml.indexOf("</receiver>", m.end());
        return close < 0 ? open : xml.substring(m.start(), close);
    }

    private static String activity(String full, String rel) throws IOException {
        return activityIn(withoutXmlComments(read(SRC.resolve("AndroidManifest.xml"))), full, rel);
    }

    /** The whole <activity-alias ...> element. */
    static String aliasIn(String xml, String full, String rel) {
        Matcher m = Pattern.compile("<activity-alias\\b[^>]*android:name=\"(?:" + Pattern.quote(full)
            + "|" + Pattern.quote(rel) + ")\"[^>]*>").matcher(xml);
        if (!m.find()) return null;
        String open = m.group();
        if (open.endsWith("/>")) return open;
        int close = xml.indexOf("</activity-alias>", m.end());
        return close < 0 ? open : xml.substring(m.start(), close);
    }

    /** The whole <activity ...> element (open tag to its close, or the self-closed tag). */
    static String activityIn(String xml, String full, String rel) {
        Matcher m = Pattern.compile("<activity\\b[^>]*android:name=\"(?:" + Pattern.quote(full)
            + "|" + Pattern.quote(rel) + ")\"[^>]*>").matcher(xml);
        if (!m.find()) return null;
        String open = m.group();
        if (open.endsWith("/>")) return open;
        int close = xml.indexOf("</activity>", m.end());
        return close < 0 ? open : xml.substring(m.start(), close);
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    static String withoutXmlComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }
}
