package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.protocol.DspChannel;

class Fir408WriteVerifierTest {

    @Test
    void mapsEveryChannelGainToItsConfirmedFir408ReadLocation() {
        assertGain(DspChannel.IN_A, 0x02, 46);
        assertGain(DspChannel.IN_B, 0x05, 34);
        assertGain(DspChannel.IN_C, 0x08, 22);
        assertGain(DspChannel.IN_D, 0x0B, 10);
        assertGain(DspChannel.OUT_1, 0x0D, 18);
        assertGain(DspChannel.OUT_2, 0x0F, 26);
        assertGain(DspChannel.OUT_3, 0x11, 34);
        assertGain(DspChannel.OUT_4, 0x13, 42);
        assertGain(DspChannel.OUT_5, 0x16, 0);
        assertGain(DspChannel.OUT_6, 0x18, 8);
        assertGain(DspChannel.OUT_7, 0x1A, 16);
        assertGain(DspChannel.OUT_8, 0x1C, 24);
    }

    @Test
    void decodesOut2LimiterFieldsUsedForPhysicalWriteConfirmation() {
        Fir408WriteVerifier.LimiterLocation location =
                Fir408WriteVerifier.limiterLocation(DspChannel.OUT_2);
        byte[] block = new byte[50];
        writeUInt16LE(block, 18, 4);
        writeUInt16LE(block, 20, 299);
        writeUInt16LE(block, 22, 0);
        writeUInt16LE(block, 24, 204);

        Fir408WriteVerifier.LimiterRaw raw = location.read(block);

        assertEquals(0x0F, location.blockIndex());
        assertTrue(raw.matches(4, 299, 0, 204));
    }

    private static void assertGain(DspChannel channel, int blockIndex, int offset) {
        Fir408WriteVerifier.GainLocation location = Fir408WriteVerifier.gainLocation(channel);
        assertEquals(blockIndex, location.blockIndex());
        assertEquals(offset, location.dataOffset());
    }

    private static void writeUInt16LE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }
}
