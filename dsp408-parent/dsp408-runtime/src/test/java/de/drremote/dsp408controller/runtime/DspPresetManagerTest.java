package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.protocol.DspProtocol;

class DspPresetManagerTest {
    @Test
    void decodesCurrentNameFromBlockZero() {
        byte[] block = new byte[16];
        byte[] name = "Living Room".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, block, 2, name.length);

        assertEquals("Living Room", DspPresetManager.decodeCurrentName(block));
        assertNull(DspPresetManager.decodeCurrentName(new byte[2]));
    }

    @Test
    void decodesNormalizedNameFromWritePayload() {
        byte[] payload = DspProtocol.buildPresetName("  Main Setup  ");

        assertEquals("Main Setup", DspPresetManager.decodeNamePayload(payload));
    }
}
