package de.drremote.dsp408controller.core.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

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
                () -> controller.setInputPeq(DspChannel.IN_B, 1, PeqFilterType.PEAK,
                        1000.0, 1.0, 0.0, false));
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
                () -> controller.setCompressor(DspChannel.IN_A, -20.0, "1:2.0", 10.0, 300.0, 0.0));
        assertThrows(IllegalStateException.class,
                () -> controller.setCrossoverHighPass(DspChannel.OUT_5, 100.0, CrossoverSlope.LR_24, false));
        assertThrows(IllegalStateException.class,
                () -> controller.setFirGenerator(DspChannel.OUT_2, FirFilterType.HIGH_PASS,
                        FirWindowFunction.HAMMING, 100.0, 20000.0, 512));
    }
}
