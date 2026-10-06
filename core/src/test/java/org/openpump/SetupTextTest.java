package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE SETUP'S WORDS (0.10): the owner's chosen versions, verbatim, with the runtime parts
 * following the pressure unit. Also that no line names a source (NoBookNamesTest covers the
 * literals; this covers what the functions build), and that every line uses the app's own
 * typographic apostrophe rather than a straight one.
 */
class SetupTextTest {

    private String unitBefore;
    @BeforeEach void unit() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_INHG; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    @Test void theCeilingSubtitleHasTwoBranches() {
        assertEquals("Below Level 1 · sessions stop here", SetupText.ceilingSubtitle(16));
        assertEquals("A hard stop the pump never passes", SetupText.ceilingSubtitle(17));
        assertEquals("A hard stop the pump never passes", SetupText.ceilingSubtitle(34));
        // Above the plan's usual top too: "plans never go above −10.0 inHg" stopped being true
        // when a programme cap became warned once and each track got its own maximum (E-M2).
        assertEquals("A hard stop the pump never passes", SetupText.ceilingSubtitle(35));
        assertEquals("A hard stop the pump never passes", SetupText.ceilingSubtitle(43));
    }

    @Test void theCeilingLinesFollowThePressureUnit() {
        Model.Fmt.unit = Model.Fmt.U_KPA;
        assertEquals("Below Level 1 · sessions stop here", SetupText.ceilingSubtitle(10));
        assertEquals("That’s below where Level 1 starts (17.0 kPa), so sessions will stop here. "
            + "You can raise it any time.", SetupText.ceilingWarning(10));
        Model.Fmt.unit = Model.Fmt.U_INHG;
        assertEquals("That’s below where Level 1 starts (−5.0 inHg), so sessions will stop "
            + "here. You can raise it any time.", SetupText.ceilingWarning(10));
    }

    @Test void theCeilingWarningIsOnlyBelowLevelOne() {
        assertFalse(SetupText.ceilingWarning(16).isEmpty());
        assertEquals("", SetupText.ceilingWarning(17));
        assertEquals("", SetupText.ceilingWarning(40));
    }

    @Test void unitResponsesById() {
        assertEquals("Matches most gauges.", SetupText.unitResponse("inHg"));
        assertEquals("About 2.5 × the inHg numbers.", SetupText.unitResponse("cmHg"));
        assertEquals("Matches the pump’s manual.", SetupText.unitResponse("kPa"));
        assertEquals("", SetupText.unitResponse("psi"));
    }

    @Test void experienceResponsesById() {
        assertTrue(SetupText.experienceResponse("new").startsWith("No problem!"));
        assertTrue(SetupText.experienceResponse("some").startsWith("Great."));
        assertTrue(SetupText.experienceResponse("exp").endsWith("may start you a level higher."));
        assertEquals("", SetupText.experienceResponse("?"));
    }

    @Test void daysLabelAndResponseByKey() {
        assertEquals("Training days · 3 a week", SetupText.daysLabel(3));
        assertEquals("That’s a lot! Rest days are when the gains happen.",
            SetupText.daysResponse(FirstRun.daysKey(6)));
        assertEquals("That works. Just expect slower progress with fewer than 3 days.",
            SetupText.daysResponse(FirstRun.daysKey(2)));
        assertEquals("Nice balance. That fits the plan well.",
            SetupText.daysResponse(FirstRun.daysKey(3)));
    }

    @Test void theMeasureLabelAndResponse() {
        assertEquals("Ask me to measure · about every 12 days",
            SetupText.measureLabel(Model.Meas.CAD_SESSIONS, 3));
        assertEquals("Ask me to measure", SetupText.measureLabel(Model.Meas.CAD_WEEK, 3));
        assertEquals("Ask me to measure", SetupText.measureLabel(Model.Meas.CAD_NEVER, 3));
        assertEquals("", SetupText.measureResponse(Model.Meas.CAD_SESSIONS));
        assertTrue(SetupText.measureResponse(Model.Meas.CAD_WEEK).contains("1st and 4th"));
        assertTrue(SetupText.measureResponse(Model.Meas.CAD_NEVER).startsWith("We won’t ask."));
    }

    @Test void theRemindersSubtitle() {
        Model m = Model.seed();
        FirstRun.begin(m);
        assertEquals("Android will ask to allow notifications", SetupText.remindersSubtitle(m.sched));
        m.sched.remind = true;
        assertEquals("Mon, Wed, Fri at 19:00", SetupText.remindersSubtitle(m.sched));
    }

    @Test void theNotificationPreview() {
        assertEquals("OpenPump", SetupText.notificationTitle(Incognito.REAL));
        assertEquals("Fitness log", SetupText.notificationTitle(Incognito.FITNESS_LOG));
        assertEquals("Habits", SetupText.notificationTitle(Incognito.HABITS));
        assertEquals("Notes", SetupText.notificationTitle(Incognito.NOTES));
        assertEquals("Set 2 of 5 · −5.9 inHg · 1:29 left", SetupText.notificationBody(false));
        Model.Fmt.unit = Model.Fmt.U_KPA;
        assertEquals("Set 2 of 5 · 20.0 kPa · 1:29 left", SetupText.notificationBody(false));
        assertEquals("Session running · 1:29 left", SetupText.notificationBody(true));
        assertEquals("Notifications show your set, pressure and time left.",
            SetupText.notificationLine(false));
        assertTrue(SetupText.notificationLine(true).startsWith("Disguised:"));
    }

    @Test void theFirstSessionLines() {
        assertTrue(SetupText.firstResponse("trainer", 3).startsWith("Six quick questions"));
        assertEquals("A gentle 3-minute routine to get to know your pump. "
            + "You can set up the trainer after.", SetupText.firstResponse("starter", 3));
        assertEquals("A gentle 7-minute routine to get to know your pump. "
            + "You can set up the trainer after.", SetupText.starterResponse(7));
        assertTrue(SetupText.firstResponse("own", 3).startsWith("Opens the Library"));
        assertEquals("Set up the trainer ›", SetupText.finalButton("trainer"));
        assertEquals("Start the Starter routine ›", SetupText.finalButton("starter"));
        assertEquals("Open the Library ›", SetupText.finalButton("own"));
    }

    @Test void theScreenFurniture() {
        String was = Model.Fmt.sizeUnit;
        try {
            Model.Fmt.sizeUnit = Model.Fmt.S_CM;
            assertEquals("5.0 cm inside · 23.0 cm long", SetupText.cylinderLine(5.0, 23.0));
            Model.Fmt.sizeUnit = Model.Fmt.S_IN;
            assertEquals("1.75 in inside · 8.00 in long", SetupText.cylinderLine(4.445, 20.32));
        } finally {
            Model.Fmt.sizeUnit = was;
        }
        // The summary says the cadence the setup's own "Every 5" stores - the seed's.
        Model.Meas m = Model.seed().meas;
        assertEquals("Every " + FirstRun.MEASURE_EVERY_SESSIONS + " sessions",
            SetupText.measureSummary(m));
        m.chooseSegment(Model.Meas.CAD_WEEK);
        assertEquals("1st and 4th session of each week", SetupText.measureSummary(m));
        m.chooseSegment(Model.Meas.CAD_NEVER);
        assertEquals(SetupText.MEASURE_NEVER, SetupText.measureSummary(m));
        assertEquals(30, SetupText.ED_NAME_MAX);
    }

    /** Every constant and every function's output, gathered for the whole-text checks. */
    private static List<String> everyLine() throws Exception {
        List<String> all = new ArrayList<>();
        for (Field f : SetupText.class.getDeclaredFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                all.add((String) f.get(null));
        for (int k = 5; k <= 57; k += 4) {
            all.add(SetupText.ceilingSubtitle(k));
            all.add(SetupText.ceilingWarning(k));
        }
        for (String u : new String[] { "inHg", "cmHg", "kPa" }) all.add(SetupText.unitResponse(u));
        for (String e : new String[] { "new", "some", "exp" }) all.add(SetupText.experienceResponse(e));
        for (String d : new String[] { "many", "few", "ok" }) all.add(SetupText.daysResponse(d));
        for (int s = 0; s <= 3; s++) {
            all.add(SetupText.measureLabel(s, 3));
            all.add(SetupText.measureResponse(s));
        }
        for (String c : new String[] { "trainer", "starter", "own" }) {
            all.add(SetupText.firstResponse(c, 3));
            all.add(SetupText.finalButton(c));
        }
        all.add(SetupText.notificationBody(true));
        all.add(SetupText.notificationBody(false));
        all.add(SetupText.notificationLine(true));
        all.add(SetupText.notificationLine(false));
        for (String q : SetupText.ED_QUICK) all.add(q + SetupText.ED_QUICK_SUFFIX);
        all.add(SetupText.cylinderLine(5.0, 23.0));
        Model.Meas m = Model.seed().meas;
        for (int s = 0; s <= 3; s++) {
            m.chooseSegment(s);
            all.add(SetupText.measureSummary(m));
        }
        return all;
    }

    @Test void noLineNamesABook() throws Exception {
        for (String s : everyLine())
            assertFalse(NoBookNamesTest.namesASource(s), "names a source: " + s);
    }

    @Test void noStraightApostropheOrQuote() throws Exception {
        for (String s : everyLine()) {
            assertFalse(s.indexOf('\'') >= 0, "straight apostrophe: " + s);
            assertFalse(s.indexOf('"') >= 0, "straight quote: " + s);
        }
    }
}
