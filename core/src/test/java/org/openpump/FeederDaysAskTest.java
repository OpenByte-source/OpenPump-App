package org.openpump;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * S16 FOLLOW-UP (the owner's decision, 2026-09-26): SOMEBODY WHO HAD THE FEEDER ON REST DAYS
 * BEFORE THE SWITCH EXISTED IS ASKED, ONCE.
 *
 * 0.10 made "training days only" the default for every file, so an existing trainer who had
 * been offered the feeder on rest days stopped being offered it without saying so. They are
 * asked once, on the first launch after the update, in words that name both guides, and the
 * answer sets the switch. A new install keeps the default without being asked; so does a file
 * whose feeder the question does not concern (no plan, or feeders turned down at setup), and
 * so does one saved after the switch existed. The marker that says "asked" is saved.
 */
class FeederDaysAskTest {

    /** A model.json as 0.9.0 wrote it: enrolled or not, no feeder switch, no marker. */
    private static String oldFile(boolean enrolled, boolean feederOptIn) {
        return "{\"ceil\":40,\"unit\":\"inHg\",\"selected\":\"r1\",\"sets\":[],"
            + "\"routines\":[{\"id\":\"r1\",\"name\":\"R\",\"sets\":[]}],"
            + "\"trainerOn\":" + enrolled + ",\"trainerFeederOptIn\":" + feederOptIn + "}";
    }

    @Test void aNewInstallIsNeverAsked() throws Exception {
        assertFalse(new Model().feederDaysAskDue());
        assertFalse(Model.seed().feederDaysAskDue());
        Model enrolled = Model.seed();
        enrolled.trainerEnrolled = true;
        assertFalse(enrolled.feederDaysAskDue(), "enrolling on 0.10 is not an update");
        assertFalse(Model.fromJson(enrolled.toJson()).feederDaysAskDue(),
            "and its saved file says so");
        assertFalse(enrolled.feederRestDays, "the default stands: training days only");
    }

    @Test void anExistingTrainerIsAskedOnce() throws Exception {
        Model m = Model.fromJson(oldFile(true, true));
        assertTrue(m.feederDaysAskDue(), "saved before the switch, with feeders in the plan");
        assertFalse(m.feederRestDays, "until they answer, the default holds");
        assertTrue(Model.fromJson(m.toJson()).feederDaysAskDue(),
            "a save before the answer does not lose the question");
        m.answerFeederDays(true);
        assertTrue(m.feederRestDays, "their answer sets the switch");
        assertFalse(m.feederDaysAskDue());
        Model back = Model.fromJson(m.toJson());
        assertFalse(back.feederDaysAskDue(), "asked once: the marker is saved");
        assertTrue(back.feederRestDays);
        Model no = Model.fromJson(oldFile(true, true));
        no.answerFeederDays(false);
        assertFalse(no.feederRestDays);
        assertFalse(Model.fromJson(no.toJson()).feederDaysAskDue());
    }

    @Test void onlyThoseTheFeederConcerns() throws Exception {
        assertFalse(Model.fromJson(oldFile(false, true)).feederDaysAskDue(), "no plan");
        assertFalse(Model.fromJson(oldFile(true, false)).feederDaysAskDue(),
            "feeders turned down at setup");
        Model later = Model.fromJson(oldFile(false, true));
        later.trainerEnrolled = true;
        assertFalse(later.feederDaysAskDue(),
            "enrolling after the update is a new plan, with the new default");
    }

    @Test void aFileSavedAfterTheSwitchIsNotAsked() throws Exception {
        JSONObject o = new JSONObject(oldFile(true, true));
        o.put("feederRestDays", false);
        assertFalse(Model.fromJson(o.toString()).feederDaysAskDue(),
            "the switch is in the file: its data does not predate it");
    }

    @Test void theQuestionNamesBothGuides() {
        String q = TrainerTab.FEEDER_DAYS_ASK_TITLE + " " + TrainerTab.FEEDER_DAYS_ASK_TEXT;
        assertTrue(q.contains("rest days"), q);
        assertTrue(q.contains("One part of the guidance") && q.contains("another")
            && !NoBookNamesTest.namesASource(q), q);
        assertTrue(TrainerTab.FEEDER_DAYS_ASK_TEXT.length() <= 140, "short and plain");
    }
}
