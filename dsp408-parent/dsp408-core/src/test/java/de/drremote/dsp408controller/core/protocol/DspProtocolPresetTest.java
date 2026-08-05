package de.drremote.dsp408controller.core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DspProtocolPresetTest {
    @Test
    void buildsObservedFir408StoreSequencePayloads() {
        assertArrayEquals(
                new byte[]{0x00, 0x01, 0x02, 0x21, 0x13},
                DspProtocol.storePreset("U19")
        );

        byte[] expectedName = new byte[]{
                0x00, 0x01, 0x0F, 0x26,
                'L', 'i', 'v', 'e', ' ', 'A',
                0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20
        };
        assertArrayEquals(expectedName, DspProtocol.buildPresetName("Live A"));
    }

    @Test
    void rejectsFactoryAndNonexistentU20AsStoreTargets() {
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.storePreset("F00"));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.storePreset("U20"));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.loadPreset("U20"));
    }

    @Test
    void decodesPresetNameReply() {
        byte[] name = "Live A        ".getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[5 + name.length];
        payload[0] = 0x01;
        payload[1] = 0x00;
        payload[2] = 0x10;
        payload[3] = 0x29;
        payload[4] = 0x03;
        System.arraycopy(name, 0, payload, 5, name.length);

        assertTrue(DspProtocol.isPresetNameResponse(payload));
        assertEquals(3, DspProtocol.presetNameResponseIndex(payload));
        assertEquals("Live A", DspProtocol.presetNameResponseName(payload));
    }

    @Test
    void validatesPresetNameForHardwareField() {
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.buildPresetName(""));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.buildPresetName("123456789012345"));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.buildPresetName("Ümlaut"));
    }
}
