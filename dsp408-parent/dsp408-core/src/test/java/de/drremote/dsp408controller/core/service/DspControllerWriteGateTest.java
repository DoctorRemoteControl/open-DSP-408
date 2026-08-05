package de.drremote.dsp408controller.core.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.protocol.CrossoverSlope;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.FirFilterType;
import de.drremote.dsp408controller.core.protocol.FirWindowFunction;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;

class DspControllerWriteGateTest {
    private static final Path FIR_LIBRARY =
            Path.of("src", "main", "resources", "dsp", "DspLib-408-fir.json");

    @Test
    void blocksUnverifiedFir408WritesBeforeNetworkAccess() {
        DspController controller = new DspController(FIR_LIBRARY);

        assertThrows(UnsupportedDspOperationException.class,
                () -> controller.setInputGeq(DspChannel.IN_B, 1, 0.0));
        assertThrows(UnsupportedDspOperationException.class,
                () -> controller.setInputGate(DspChannel.IN_C, -40.0, 100.0, 10.0, 300.0));
        assertThrows(UnsupportedDspOperationException.class,
                () -> controller.setCrossoverHighPass(DspChannel.IN_A, 100.0, CrossoverSlope.LR_24, false));
        assertThrows(UnsupportedDspOperationException.class,
                () -> controller.setCompressor(DspChannel.IN_B, -20.0, "1:2.0", 10.0, 300.0, 0.0));
        assertThrows(UnsupportedDspOperationException.class,
                () -> controller.setFirGenerator(DspChannel.OUT_3, FirFilterType.HIGH_PASS,
                        FirWindowFunction.HAMMING, 100.0, 20000.0, 512));
        assertThrows(UnsupportedDspOperationException.class,
                () -> controller.uploadExternalFir(DspChannel.OUT_2, "test", new double[512]));
    }

    @Test
    void verifiedFir408WritesReachTheNormalConnectionGuard() {
        DspController controller = new DspController(FIR_LIBRARY);

        assertThrows(IllegalStateException.class,
                () -> controller.setPhase(DspChannel.IN_B, false));
        assertThrows(IllegalStateException.class,
                () -> controller.setInputPeq(DspChannel.IN_A, 1, PeqFilterType.PEAK,
                        1000.0, 1.0, 0.0, false));
        assertThrows(IllegalStateException.class,
                () -> controller.setInputPeq(DspChannel.IN_B, 1, PeqFilterType.PEAK,
                        1000.0, 1.0, 0.0, true));
        assertThrows(IllegalStateException.class,
                () -> controller.setInputPeq(DspChannel.IN_C, 8, PeqFilterType.PEAK,
                        1000.0, 1.0, 0.0, true));
        assertThrows(IllegalStateException.class,
                () -> controller.setCompressor(DspChannel.IN_A, -20.0, "1:2.0", 10.0, 300.0, 0.0));
        assertThrows(IllegalStateException.class,
                () -> controller.setCrossoverHighPass(DspChannel.OUT_5, 100.0, CrossoverSlope.LR_24, false));
        assertThrows(IllegalStateException.class,
                () -> controller.setFirGenerator(DspChannel.OUT_2, FirFilterType.HIGH_PASS,
                        FirWindowFunction.HAMMING, 100.0, 20000.0, 512));
    }

    @Test
    void allPeqBypassPreflightsAll104BandsBeforeNetworkAccess() {
        DspController controller = new DspController(FIR_LIBRARY);

        for (DspChannel input : List.of(
                DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D)) {
            for (int band = 1; band <= 8; band++) {
                controller.state().markInputPeqFromDevice(
                        input, band, 0.0, 1000.0, 1.0, PeqFilterType.PEAK, false, "test");
            }
        }
        for (DspChannel output : List.of(
                DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
                DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8)) {
            int lastBand = output == DspChannel.OUT_8 ? 8 : 9;
            for (int band = 1; band <= lastBand; band++) {
                controller.state().markOutputPeqFromDevice(
                        output, band, 0.0, 1000.0, 1.0, PeqFilterType.PEAK, false, "test");
            }
        }

        IllegalStateException incomplete = assertThrows(
                IllegalStateException.class,
                () -> controller.setAllPeqBypass(true)
        );
        assertTrue(incomplete.getMessage().startsWith("Band state incomplete"));

        controller.state().markOutputPeqFromDevice(
                DspChannel.OUT_8, 9, 0.0, 1000.0, 1.0, PeqFilterType.PEAK, false, "test");
        IllegalStateException disconnected = assertThrows(
                IllegalStateException.class,
                () -> controller.setAllPeqBypass(true)
        );
        assertTrue(disconnected.getMessage().startsWith("Not connected"));
    }
}
