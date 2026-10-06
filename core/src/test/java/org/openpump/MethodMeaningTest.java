package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MEASUREMENT POLISH, ITEM 7 - EACH METHOD CODE SAYS WHAT IT MEANS, FROM ONE TABLE.
 *
 * BPEL, BPSSL, BPSL, NBPEL, NBPSL, MSEG and MSSG appeared with no explanation anywhere in
 * the app. Model.Reading#methodMeaning and #methodHowTo are the one place the words live;
 * the sheet, the at-rest picker and the how-to page all read them. The meanings are the
 * ones the owner approved as written - pinned here so any change to them is a deliberate
 * one-line edit in the table and here.
 */
class MethodMeaningTest {

    @Test void theApprovedMeaningsAreTheOnesInTheTable() {
        assertEquals("Erect, pressed to the bone",
            Model.Reading.methodMeaning(Model.Reading.METHOD_BPEL));
        assertEquals("Stretched, pressed to the bone",
            Model.Reading.methodMeaning(Model.Reading.METHOD_BPSSL));
        assertEquals("Soft, pressed to the bone",
            Model.Reading.methodMeaning(Model.Reading.METHOD_BPSL));
        assertEquals("Erect, not pressed",
            Model.Reading.methodMeaning(Model.Reading.METHOD_NBPEL));
        assertEquals("Soft, not pressed",
            Model.Reading.methodMeaning(Model.Reading.METHOD_NBPSL));
        assertEquals("Around the middle, erect",
            Model.Reading.methodMeaning(Model.Reading.METHOD_MSEG));
        assertEquals("Around the middle, soft",
            Model.Reading.methodMeaning(Model.Reading.METHOD_MSSG));
        assertEquals("Standardised: measured during the pump's hold at a set pressure",
            Model.Reading.methodMeaning(Model.Reading.METHOD_STANDARDIZED));
    }

    @Test void everyMethodHasAMeaningAndAWayToTakeIt() {
        for (int m = Model.Reading.METHOD_STANDARDIZED; m <= Model.Reading.METHOD_MSSG; m++) {
            assertFalse(Model.Reading.methodMeaning(m).trim().isEmpty(), "meaning of " + m);
            assertFalse(Model.Reading.methodHowTo(m).trim().isEmpty(), "how to take " + m);
            assertFalse(Model.Reading.methodMeaning(m).equals(Model.Reading.methodHowTo(m)),
                "the how-to says more than the one-line meaning, for " + m);
        }
    }

    @Test void anUnknownMethodReadsAsStdAsItsLabelDoes() {
        // methodLabel shows an unrecognised int as "Standardised"; its meaning must agree with it.
        String std = Model.Reading.methodMeaning(Model.Reading.METHOD_STANDARDIZED);
        assertEquals("Standardised", Model.Reading.methodLabel(99));
        assertEquals(std, Model.Reading.methodMeaning(99));
        assertEquals(std, Model.Reading.methodMeaning(-1));
        assertEquals(Model.Reading.methodHowTo(Model.Reading.METHOD_STANDARDIZED),
            Model.Reading.methodHowTo(42));
    }

    @Test void theWordsAgreeWithTheStateTheAppDerivesFromEachMethod() {
        // stateForMethod files the erect protocols as hard and the rest as soft; a meaning
        // that said otherwise would describe one reading while the app filed another.
        for (int m = Model.Reading.METHOD_BPEL; m <= Model.Reading.METHOD_MSSG; m++) {
            String what = Model.Reading.methodMeaning(m).toLowerCase(java.util.Locale.US);
            boolean hard = Model.Reading.STATE_HARD.equals(Model.Reading.stateForMethod(m));
            assertEquals(hard, what.contains("erect"),
                Model.Reading.methodLabel(m) + ": \"" + what + "\" against its state");
        }
    }

    @Test void lengthsAreTakenWithARulerAndGirthsWithATape() {
        for (int m = Model.Reading.METHOD_BPEL; m <= Model.Reading.METHOD_MSSG; m++) {
            String how = Model.Reading.methodHowTo(m);
            if (Model.Reading.methodIsGirth(m))
                assertTrue(how.contains("tape") && how.contains("around"),
                    Model.Reading.methodLabel(m) + " is a girth: " + how);
            else
                assertTrue(how.contains("ruler") && how.contains("tip"),
                    Model.Reading.methodLabel(m) + " is a length: " + how);
        }
    }
}
