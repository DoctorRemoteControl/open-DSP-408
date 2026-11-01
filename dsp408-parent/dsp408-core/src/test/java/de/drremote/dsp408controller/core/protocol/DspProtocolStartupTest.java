package de.drremote.dsp408controller.core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DspProtocolStartupTest {
    @Test
    void buildsObservedEditorStartupRequests() {
        assertArrayEquals(
                new byte[]{0x00, 0x01, 0x01, 0x22},
                DspProtocol.startupZeroBlockRequest()
        );
        assertArrayEquals(
                new byte[]{0x00, 0x01, 0x01, 0x14},
                DspProtocol.startupStatusRequest()
        );
        assertArrayEquals(
                new byte[]{0x00, 0x01, 0x04, 0x56, 0x00, (byte) 0x9D, 0x04},
                DspProtocol.readFirStartupWindow(1181)
        );
    }

    @Test
    void rejectsInvalidFirStartupOffsets() {
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.readFirStartupWindow(-1));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.readFirStartupWindow(0x10000));
    }
}
