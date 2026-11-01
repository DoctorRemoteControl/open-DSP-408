package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.protocol.DspChannel;

class VolumeRoomHandlerSyntaxTest {
    @Test
    void acceptsBareAndLegacyVolumeRoomCommands() {
        assertEquals("in +", VolumeRoomHandler.normalizeRoomCommand("in +"));
        assertEquals("out1 -20", VolumeRoomHandler.normalizeRoomCommand("out1 -20"));
        assertEquals("out -", VolumeRoomHandler.normalizeRoomCommand("!dsp volume out -"));
        assertEquals("ina +", VolumeRoomHandler.normalizeRoomCommand("!dsp vol ina +"));
        assertEquals("lauter", VolumeRoomHandler.normalizeRoomCommand("lauter"));
        assertEquals("leiser", VolumeRoomHandler.normalizeRoomCommand("leiser"));
        assertEquals("help", VolumeRoomHandler.normalizeRoomCommand("help"));
        assertEquals("limiter +", VolumeRoomHandler.normalizeRoomCommand("limiter +"));
        assertEquals("limiter +1", VolumeRoomHandler.normalizeRoomCommand("limiter +1"));
        assertEquals("limiter -1", VolumeRoomHandler.normalizeRoomCommand("limiter -1"));
        assertEquals(
                "limiter out3 -8",
                VolumeRoomHandler.normalizeRoomCommand("!dsp volume limiter out3 -8")
        );
    }

    @Test
    void ignoresNormalConversationAndOtherDspCommands() {
        assertNull(VolumeRoomHandler.normalizeRoomCommand("Hallo zusammen"));
        assertNull(VolumeRoomHandler.normalizeRoomCommand("!dsp gain out1 -10"));
        assertNull(VolumeRoomHandler.normalizeRoomCommand(""));
    }

    @Test
    void resolvesInputOutputAndIndividualTargets() {
        assertArrayEquals(
                new DspChannel[]{DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D},
                VolumeRoomHandler.channelsForTarget("Eingänge"));
        assertArrayEquals(
                new DspChannel[]{
                        DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
                        DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8
                },
                VolumeRoomHandler.channelsForTarget("out"));
        assertArrayEquals(
                new DspChannel[]{DspChannel.OUT_5},
                VolumeRoomHandler.channelsForTarget("out5"));
    }

    @Test
    void formatsDjRoomValuesWithoutInternalDspDetails() {
        assertEquals("-19 dB", VolumeRoomHandler.compactGain(-19.0));
        assertEquals("-19.5 dB", VolumeRoomHandler.compactGain(-19.5));
        assertEquals("? dB", VolumeRoomHandler.compactGain(null));
        assertEquals(" 🔇", VolumeRoomHandler.compactMute(true));
        assertEquals("", VolumeRoomHandler.compactMute(false));
        assertEquals("", VolumeRoomHandler.compactMute(null));
        assertEquals("-10 dB", VolumeRoomHandler.compactLimiterThreshold(-10.0));
    }

    @Test
    void resolvesAndValidatesLimiterTargets() {
        assertEquals(DspChannel.OUT_1, VolumeRoomHandler.limiterOutput("1"));
        assertEquals(DspChannel.OUT_8, VolumeRoomHandler.limiterOutput("out8"));
        assertThrows(IllegalArgumentException.class, () -> VolumeRoomHandler.limiterOutput("ina"));

        assertEquals(-90.0, VolumeRoomHandler.checkedLimiterDb(-90.0));
        assertEquals(20.0, VolumeRoomHandler.checkedLimiterDb(20.0));
        assertThrows(IllegalArgumentException.class, () -> VolumeRoomHandler.checkedLimiterDb(-90.1));
        assertThrows(IllegalArgumentException.class, () -> VolumeRoomHandler.checkedLimiterDb(20.1));
    }
}
