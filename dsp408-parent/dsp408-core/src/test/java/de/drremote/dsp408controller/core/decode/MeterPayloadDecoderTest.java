package de.drremote.dsp408controller.core.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.DspState;

class MeterPayloadDecoderTest {

    @Test
    void decodesPhysicalFir408FortySixByteResponse() {
        DspLibrary library = DspLibraryLoader.loadFir408();
        DspState state = new DspState();
        MeterPayloadDecoder decoder = new MeterPayloadDecoder(state, null, library);

        byte[] payload = new byte[46];
        payload[0] = 0x01;
        payload[2] = 0x2B;
        payload[3] = 0x40;

        putMeter(payload, 0, 0x3C26, 0x91);
        putMeter(payload, 4, 0x3BA4, 0x76);
        putMeter(payload, 11, 0x3D22, 0xDD);
        payload[40] = 0x23;
        payload[41] = (byte) 0x81;
        payload[42] = 0x11;
        payload[43] = 0x22;
        payload[44] = 0x33;
        payload[45] = 0x44;

        decoder.decode(payload);

        assertEquals(46, library.meterLayout().responseLengthBytes());
        assertEquals(0x3C26, state.meterState().channel(DspChannel.IN_A).rawValue());
        assertEquals(0x91, state.meterState().channel(DspChannel.IN_A).slotByte2());
        assertEquals(0x3BA4, state.meterState().channel(DspChannel.OUT_1).rawValue());
        assertEquals(0x3D22, state.meterState().channel(DspChannel.OUT_8).rawValue());
        assertEquals(0x23, state.meterState().statusByteRaw());
        assertTrue(state.limiter(DspChannel.OUT_1).runtimeActive());
        assertFalse(state.limiter(DspChannel.OUT_2).runtimeActive());
        assertTrue(state.limiter(DspChannel.OUT_8).runtimeActive());
    }

    private static void putMeter(byte[] payload, int slot, int rawValue, int aux) {
        int offset = 4 + (slot * 3);
        payload[offset] = (byte) (rawValue & 0xFF);
        payload[offset + 1] = (byte) ((rawValue >>> 8) & 0xFF);
        payload[offset + 2] = (byte) aux;
    }
}
