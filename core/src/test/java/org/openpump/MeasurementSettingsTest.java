package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MEASUREMENT POLISH, ITEM 9 - SETTINGS: ONE HOME FOR MEASUREMENTS.
 *
 * The cadence is one segmented control of four (Sessions, Hours, Week, Never) where there
 * were five radio buttons; "Every session" is Sessions with an interval of 1. The stored
 * modes must not move, or the question asked at START would change under a person who never
 * touched the setting. And "Photo during the hold", a switch that did nothing, now decides
 * whether the camera opens as a served hold hands over to its capture screen.
 */
class MeasurementSettingsTest {

    private static Model.Meas cfg(String mode, int n) {
        Model.Meas m = new Model.Meas();
        m.mode = mode;
        m.n = n;
        return m;
    }

    @Test void everyStoredModeIsDrawnAsOneOfTheFourSegments() {
        assertEquals(Model.Meas.CAD_SESSIONS, cfg("every", 5).cadenceSegment());
        assertEquals(Model.Meas.CAD_SESSIONS, cfg("sessions", 5).cadenceSegment());
        assertEquals(Model.Meas.CAD_HOURS, cfg("hours", 5).cadenceSegment());
        assertEquals(Model.Meas.CAD_WEEK, cfg(Model.MeasLog.MEAS_WEEK_14, 5).cadenceSegment());
        assertEquals(Model.Meas.CAD_NEVER, cfg("off", 5).cadenceSegment());
        assertEquals(Model.Meas.CAD_SESSIONS, cfg("nonsense", 5).cadenceSegment(),
            "an unknown mode reads as Sessions, the default clampAll rewrites it to");
    }

    @Test void everySessionIsSessionsWithAnIntervalOfOne() {
        Model.Meas every = cfg("every", 5);
        assertEquals(1, every.everySessions(), "\"every\" shows as 1, whatever n was left at");
        assertEquals(7, cfg("sessions", 7).everySessions());
    }

    @Test void theStepperMovesBetweenEveryAndSessionsWithoutChangingWhatIsAsked() {
        Model.Meas m = cfg("every", 5);
        m.stepSessions(+1);
        assertEquals("sessions", m.mode, "from 1, + asks every 2 sessions");
        assertEquals(2, m.n);
        m.stepSessions(-1);
        assertEquals("every", m.mode, "back to 1 is the every-session mode it always was");
        m.stepSessions(-1);
        assertEquals("every", m.mode, "1 is the floor");
        assertEquals(1, m.everySessions());
        Model.Meas top = cfg("sessions", 20);
        top.stepSessions(+1);
        assertEquals(20, top.n, "20 is the ceiling");
        assertEquals("sessions", top.mode);
    }

    @Test void choosingASegmentStoresTheModeDueAsks() {
        Model.Meas m = cfg("sessions", 5);
        m.chooseSegment(Model.Meas.CAD_HOURS);
        assertEquals("hours", m.mode);
        m.chooseSegment(Model.Meas.CAD_WEEK);
        assertEquals(Model.MeasLog.MEAS_WEEK_14, m.mode);
        m.chooseSegment(Model.Meas.CAD_NEVER);
        assertEquals("off", m.mode);
        m.chooseSegment(Model.Meas.CAD_SESSIONS);
        assertEquals("sessions", m.mode, "Sessions comes back at the interval it had");
        assertEquals(5, m.n);
        Model.Meas one = cfg("off", 1);
        one.chooseSegment(Model.Meas.CAD_SESSIONS);
        assertEquals("every", one.mode, "an interval of 1 is stored as every");
    }

    @Test void dueAnswersAsBeforeForEveryMode() {
        Model.MeasLog log = new Model.MeasLog();
        Model.Meas every = cfg("every", 5);
        every.sinceN = 0;
        assertTrue(log.due(every), "every asks even straight after a reading");
        Model.Meas five = cfg("sessions", 5);
        five.sinceN = 4;
        assertFalse(log.due(five));
        five.sinceN = 5;
        assertTrue(log.due(five));
        assertFalse(log.due(cfg("off", 1)));
    }

    @Test void photoDuringTheHoldOpensTheCameraOnlyForAServedHoldStillHolding() {
        assertTrue(Meas.photoDuringHold(true, true, true));
        assertFalse(Meas.photoDuringHold(false, true, true), "switch off: nothing opens");
        assertFalse(Meas.photoDuringHold(true, false, true),
            "a skipped hold, or one past its window, is not the standardised reading's photo");
        assertFalse(Meas.photoDuringHold(true, true, false),
            "no pressure held (the limit vented it): not a photo during the hold");
    }
}
