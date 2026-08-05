package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408.api.PeqChannelDto;
import de.drremote.dsp408.api.StateDto;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;

class DspRuntimePeqDtoTest {

    @Test
    void exposesAll104DecodedPeqBypassStatesThroughPublicState() throws Exception {
        DspRuntimeComponent runtime = new DspRuntimeComponent();
        runtime.withController(controller -> {
            for (DspChannel input : List.of(
                    DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D)) {
                for (int band = 1; band <= 8; band++) {
                    controller.state().markInputPeqFromDevice(
                            input, band, 0.0, 1000.0, 1.0, PeqFilterType.PEAK, true, "test-live-block");
                }
            }
            for (DspChannel output : List.of(
                    DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
                    DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8)) {
                for (int band = 1; band <= 9; band++) {
                    controller.state().markOutputPeqFromDevice(
                            output, band, 0.0, 1000.0, 1.0, PeqFilterType.PEAK, true, "test-live-block");
                }
            }
            return null;
        });

        StateDto state = runtime.requestRuntimeMeters();
        assertEquals(12, state.peqChannels().size());
        assertEquals(104, state.peqChannels().stream().mapToInt(channel -> channel.bands().size()).sum());
        assertTrue(state.peqChannels().stream()
                .flatMap(channel -> channel.bands().stream())
                .allMatch(band -> Boolean.TRUE.equals(band.bypass()) && band.bypassConfirmedFromDevice()));

        PeqChannelDto inB = runtime.getPeqChannel("inb");
        PeqChannelDto out8 = runtime.getPeqChannel("out8");
        assertEquals(8, inB.bandCount());
        assertTrue(inB.input());
        assertEquals(9, out8.bandCount());
        assertTrue(!out8.input());
    }
}
