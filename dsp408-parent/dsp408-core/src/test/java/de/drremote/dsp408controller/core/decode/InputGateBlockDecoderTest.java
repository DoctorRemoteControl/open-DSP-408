package de.drremote.dsp408controller.core.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.DspState;
import de.drremote.dsp408controller.core.state.InputGateState;

class InputGateBlockDecoderTest {

    @Test
    void originalGuiAndLiveReadbackConfirmAllFourInputGates() {
        DspState state = new DspState();
        InputGateBlockDecoder decoder = new InputGateBlockDecoder(
                state,
                null,
                DspLibraryLoader.loadFir408());

        byte[] block00 = new byte[50];
        putU16le(block00, 24, 0);
        putU16le(block00, 26, 9);
        putU16le(block00, 28, 9);
        putU16le(block00, 30, 0);
        state.storeBlock(0x00, block00);
        decoder.decode(0x00, block00);

        byte[] block03 = new byte[50];
        putU16le(block03, 12, 0);
        putU16le(block03, 14, 99);
        putU16le(block03, 16, 99);
        putU16le(block03, 18, 12);
        state.storeBlock(0x03, block03);
        decoder.decode(0x03, block03);

        byte[] block06 = new byte[50];
        putU16le(block06, 0, 0);
        putU16le(block06, 2, 99);
        putU16le(block06, 4, 99);
        putU16le(block06, 6, 155);
        state.storeBlock(0x06, block06);
        decoder.decode(0x06, block06);

        byte[] block08 = new byte[50];
        putU16le(block08, 38, 0);
        putU16le(block08, 40, 99);
        putU16le(block08, 42, 99);
        putU16le(block08, 44, 56);
        state.storeBlock(0x08, block08);
        decoder.decode(0x08, block08);

        assertGate(state.inputGate(DspChannel.IN_A), -90.0, 10.0, 1.0, 10.0);
        assertGate(state.inputGate(DspChannel.IN_B), -84.0, 100.0, 1.0, 100.0);
        assertGate(state.inputGate(DspChannel.IN_C), -12.5, 100.0, 1.0, 100.0);
        assertGate(state.inputGate(DspChannel.IN_D), -62.0, 100.0, 1.0, 100.0);
    }

    private static void assertGate(
            InputGateState gate,
            double thresholdDb,
            double holdMs,
            double attackMs,
            double releaseMs) {
        assertEquals(thresholdDb, gate.thresholdDb());
        assertEquals(holdMs, gate.holdMs());
        assertEquals(attackMs, gate.attackMs());
        assertEquals(releaseMs, gate.releaseMs());
        assertTrue(gate.thresholdConfirmedFromDevice());
        assertTrue(gate.holdConfirmedFromDevice());
        assertTrue(gate.attackConfirmedFromDevice());
        assertTrue(gate.releaseConfirmedFromDevice());
    }

    private static void putU16le(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }
}
