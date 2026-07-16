package de.drremote.dsp408controller.core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class DspProtocolMatrixRouteTest {

    @Test
    void buildsFullMultiInputRouteMask() {
        assertArrayEquals(
                new byte[]{0x00, 0x01, 0x03, 0x3A, 0x08, 0x03},
                DspProtocol.buildMatrixRoutes(DspChannel.OUT_5, List.of(DspChannel.IN_A, DspChannel.IN_B))
        );
    }

    @Test
    void supportsClearingAllRoutes() {
        assertArrayEquals(
                new byte[]{0x00, 0x01, 0x03, 0x3A, 0x09, 0x00},
                DspProtocol.buildMatrixRoutes(DspChannel.OUT_6, List.of())
        );
    }
}
