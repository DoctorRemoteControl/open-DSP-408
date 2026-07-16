package de.drremote.dsp408controller.core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class DspProtocolLimiterTest {

    @Test
    void buildsFir408LimiterPayloadWithRatioAndKneePadding() {
        assertArrayEquals(
                new byte[]{
                        0x00, 0x01, 0x0C, 0x3F, 0x04,
                        0x0F, 0x00,
                        0x04, 0x00,
                        0x2B, 0x01,
                        0x00, 0x00,
                        (byte) 0x8C, 0x00
                },
                DspProtocol.buildLimiter(DspChannel.OUT_1, 4, 299, 0, 140)
        );
    }
}
