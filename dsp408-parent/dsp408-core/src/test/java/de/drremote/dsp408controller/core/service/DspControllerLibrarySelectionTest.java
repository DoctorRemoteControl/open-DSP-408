package de.drremote.dsp408controller.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DspControllerLibrarySelectionTest {

    @Test
    void selectsFirLibraryBeforeConnecting() {
        DspController controller = new DspController();

        controller.setPreferredLibraryType("fir408");

        assertTrue(controller.hasFirFeatures());
        assertEquals(0x1F, controller.maxParameterBlockIndex());
    }

    @Test
    void rejectsUnknownLibrary() {
        DspController controller = new DspController();

        assertThrows(
                IllegalArgumentException.class,
                () -> controller.setPreferredLibraryType("something-else")
        );
    }
}
