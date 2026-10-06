package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * THE VACUUM AT THE STANDARDISED MOMENT (0.10, StdMoment). A standardised reading records
 * what the pump reported at the END OF THE 30 s COUNT; the value at Save only when no fresh
 * sample existed then; comparability is judged on what is recorded. A photo inside the
 * served hold's two minutes follows the same rule. Readings saved before keep their value.
 * WiringCheck invariant 223 holds the Activity to asking this at every standardised save.
 */
class StdMomentTest {

    private static final Double COUNT_END = Double.valueOf(19.8);
    private static final Double AT_SAVE = Double.valueOf(18.4);   // coasted during the two minutes

    @Test void theCountsEndIsRecordedAndSaveIsOnlyTheFallback() {
        assertEquals(COUNT_END, StdMoment.readingKpa(COUNT_END, AT_SAVE));
        assertEquals(StdMoment.AT_COUNT_END, StdMoment.readingKpaAt(COUNT_END, AT_SAVE));
        assertEquals(AT_SAVE, StdMoment.readingKpa(null, AT_SAVE));
        assertEquals(StdMoment.AT_SAVE, StdMoment.readingKpaAt(null, AT_SAVE));
        assertEquals(COUNT_END, StdMoment.readingKpa(COUNT_END, null), "no fresh sample at Save");
        assertNull(StdMoment.readingKpa(null, null), "unknown - never the commanded setpoint");
        assertNull(StdMoment.readingKpaAt(null, null));
    }

    @Test void aPhotoInsideTheServedHoldFollowsTheSameRule() {
        Double shutter = Double.valueOf(18.1);
        assertEquals(COUNT_END, StdMoment.photoKpa(HoldWindow.OPEN, COUNT_END, shutter));
        assertEquals(shutter, StdMoment.photoKpa(HoldWindow.OPEN, null, shutter),
            "no fresh sample at the count's end - the shutter's");
        assertEquals(shutter, StdMoment.photoKpa(HoldWindow.SHORT, COUNT_END, shutter),
            "before the count is served there is no standardised moment");
        assertEquals(shutter, StdMoment.photoKpa(HoldWindow.LAPSED, COUNT_END, shutter));
        assertEquals(shutter, StdMoment.photoKpa(HoldWindow.AT_REST, COUNT_END, shutter));
        assertNull(StdMoment.photoKpa(HoldWindow.AT_REST, COUNT_END, null));
    }

    private static Model.Reading std(Double countEnd, Double atSave, long ts) {
        Model.Reading r = new Model.Reading();
        r.id = "m" + ts; r.ts = ts; r.len = 15;
        r.method = Model.Reading.METHOD_STANDARDIZED;
        r.holdKpa = Double.valueOf(20); r.holdSec = Integer.valueOf(30);
        r.observedKpa = StdMoment.readingKpa(countEnd, atSave);
        r.observedAt = StdMoment.readingKpaAt(countEnd, atSave);
        return r;
    }

    @Test void comparabilityRestsOnTheCountsEnd_driftBeforeSaveNoLongerSplitsAPair() {
        // Two readings of one protocol: at the count's end 19.9 and 19.6 kPa. The first was
        // saved at once; the second after the pump had coasted to 18.4 by Save.
        Model.Reading first = std(Double.valueOf(19.9), Double.valueOf(19.9), 1000L);
        Model.Reading second = std(Double.valueOf(19.6), AT_SAVE, 2000L);
        assertTrue(Model.Reading.comparable(first, second));
        // Recorded at Save, as before 0.10, the same pair was 1.5 kPa apart: not compared.
        Model.Reading oldSecond = std(null, AT_SAVE, 3000L);
        assertFalse(Model.Reading.comparable(first, oldSecond));
    }

    @Test void whenItWasTakenRoundTrips() throws Exception {
        Model.Reading r = std(COUNT_END, AT_SAVE, 1000L);
        Model.Reading back = Model.Reading.fromJson(r.toJson());
        assertEquals(COUNT_END, back.observedKpa);
        assertEquals(StdMoment.AT_COUNT_END, back.observedAt);
        assertEquals(StdMoment.AT_COUNT_END, back.copy().observedAt, "an edited copy keeps it");
    }

    @Test void aReadingSavedBeforeKeepsItsValueAndIsSavedAsItWas() throws Exception {
        JSONObject old = new JSONObject("{\"id\":\"m1\",\"ts\":\"1000\",\"len\":\"15.0\","
            + "\"holdKpa\":\"20.0\",\"holdSec\":30,\"observedKpa\":\"18.4\",\"method\":0}");
        Model.Reading r = Model.Reading.fromJson(old);
        assertEquals(Double.valueOf(18.4), r.observedKpa, "the stored value is kept");
        assertNull(r.observedAt, "unknown when it was taken - it was at Save");
        assertFalse(r.toJson().has("okAt"), "an older reading is saved exactly as it was");
        Model.Reading atRest = new Model.Reading();
        assertFalse(atRest.toJson().has("okAt"));
    }
}
