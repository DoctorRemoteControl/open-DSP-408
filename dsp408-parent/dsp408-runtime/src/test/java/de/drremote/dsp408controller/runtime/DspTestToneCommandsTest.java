package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DspTestToneCommandsTest {
    @Test
    void normalizesSourceAliases() {
        assertEquals("pinknoise", DspTestToneCommands.normalizeSource(" Pink-Noise "));
        assertEquals("analoginput", DspTestToneCommands.normalizeSource("analog_input"));
        assertEquals("", DspTestToneCommands.normalizeSource(null));
    }
}
