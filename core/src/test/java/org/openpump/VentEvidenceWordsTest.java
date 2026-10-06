package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * (the final review, M4) The journal says how a vent was known, one way everywhere: a reported
 * fall is evidenced; no pressure from a pump still talking is inferred - gated on as a vent,
 * never logged as one "confirmed by telemetry".
 */
class VentEvidenceWordsTest {

    @Test
    void aReportedFallIsEvidenced() {
        assertEquals("evidenced (VENTED)", Session.ventEvidence(Session.VENT_STATE_VENTED));
    }

    @Test
    void noPressureFromAPumpStillTalkingIsInferredNeverConfirmed() {
        String s = Session.ventEvidence(Session.VENT_STATE_VENTED_INFERRED);
        assertEquals("inferred (VENTED_INFERRED)", s);
        assertFalse(s.toLowerCase(Locale.US).contains("confirmed"), s);
        assertFalse(s.toLowerCase(Locale.US).contains("evidenced"), s);
    }

    @Test
    void anythingElseIsNotEvidenced() {
        assertEquals("not evidenced (UNCONFIRMED)",
            Session.ventEvidence(Session.VENT_STATE_UNCONFIRMED));
        assertEquals("not evidenced (SENT_UNVERIFIED)",
            Session.ventEvidence(Session.VENT_STATE_SENT_UNVERIFIED));
        assertEquals("not evidenced (null)", Session.ventEvidence(null));
    }
}
