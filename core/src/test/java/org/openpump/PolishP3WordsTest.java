package org.openpump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Polish lane P3: the words the Progress, measure, library and settings screens now say. */
public class PolishP3WordsTest {

    /** G-Progress: the empty gallery shows one short line; where photos come from is the
     *  sheet behind its i, and it names the screen as the app labels it. */
    @Test public void emptyGalleryLineIsShortAndTheHowToStaysWhole() {
        assertEquals("No photos yet. They stay on this phone.", Gallery.emptyShort());
        assertTrue(Gallery.emptyLine().contains("Log a reading screen"));
        assertTrue(Gallery.emptyLine().indexOf("Log-a-reading") < 0);
        assertTrue(Gallery.emptyLine().contains("baseline"));
    }
}
