package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ONE DOSE, TWO SPELLINGS OF IT. Found on an emulator run: the summary and History read
 * "1097 cmHg·s" while the saved record's "dose" held 1462.45. Both are right and they are
 * the same figure: the record keeps the dose in kPa·s (Sess#doseKpaS, the CSV's dose_kpa_s)
 * and every screen shows it through Model.Fmt.dose in the person's unit, and
 * 1462.45 kPa·s × 0.75006 = 1097 cmHg·s. Nothing stored changes meaning, so no Migrate case.
 * This pins that the stored figure and every screen's figure stay one number.
 */
class DoseOneFigureTest {
    private String unitBefore;
    @BeforeEach void cmHg() { unitBefore = Model.Fmt.unit; Model.Fmt.unit = Model.Fmt.U_CMHG; }
    @AfterEach void restore() { Model.Fmt.unit = unitBefore; }

    @Test void theSavedKpaSecondsAreWhatTheScreensShowInTheChosenUnit() throws Exception {
        Model.Sess s = new Model.Sess(); s.id = "sess1"; s.ts = 1_700_000_000_000L; s.durSec = 225;
        s.doseKpaS = 1462.4509999999984; s.peakKpa = 20.0;
        JSONObject saved = s.toJson();
        assertEquals(1462.4509999999984, Double.parseDouble(saved.getString("dose")), 1e-9,
            "the record keeps kPa·s");
        Model.Sess back = Model.Sess.fromJson(saved);
        assertEquals("1097 cmHg·s", Model.Fmt.dose(back.doseKpaS), "the run-6 routine, as the summary and History showed it");
        assertEquals("405 cmHg·s", Model.Fmt.dose(540.5760000000001), "the run-6 manual run");
        Model.Fmt.unit = Model.Fmt.U_KPA;
        assertEquals("1462 kPa·s", Model.Fmt.dose(back.doseKpaS), "in kPa the record's own figure");
    }
}
