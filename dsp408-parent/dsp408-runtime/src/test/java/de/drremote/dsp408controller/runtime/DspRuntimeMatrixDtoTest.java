package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408.api.MatrixCrosspointDto;
import de.drremote.dsp408.api.MatrixOutputDto;
import de.drremote.dsp408.api.StateDto;
import de.drremote.dsp408controller.core.protocol.DspChannel;

class DspRuntimeMatrixDtoTest {

    @Test
    void exposesEveryDecodedMatrixGainThroughPublicState() throws Exception {
        DspRuntimeComponent runtime = new DspRuntimeComponent();
        runtime.withController(controller -> {
            for (DspChannel output : List.of(
                    DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
                    DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8
            )) {
                controller.state().markMatrixRoutes(output, List.of(DspChannel.IN_A));
                for (DspChannel input : List.of(
                        DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D
                )) {
                    double gainDb = output == DspChannel.OUT_1 && input == DspChannel.IN_C
                            ? -60.0
                            : output == DspChannel.OUT_5 && input == DspChannel.IN_B
                                    ? -6.0
                                    : 0.0;
                    controller.state().markMatrixCrosspointGainFromDevice(
                            output,
                            input,
                            gainDb,
                            "test-live-block"
                    );
                }
            }
            return null;
        });

        StateDto state = runtime.requestRuntimeMeters();
        assertEquals(8, state.matrixOutputs().size());
        assertEquals(32, state.matrixOutputs().stream()
                .mapToInt(output -> output.crosspoints().size())
                .sum());

        MatrixOutputDto out1 = matrixOutput(state, "out1");
        MatrixOutputDto out5 = matrixOutput(state, "out5");
        assertEquals(List.of("ina"), out1.routedInputIds());
        assertEquals(-60.0, crosspoint(out1, "inc").gainDb());
        assertEquals(-6.0, crosspoint(out5, "inb").gainDb());
        assertTrue(crosspoint(out1, "inc").gainConfirmedFromDevice());
        assertEquals("test-live-block", crosspoint(out5, "inb").lastGainUpdateSource());
    }

    private static MatrixOutputDto matrixOutput(StateDto state, String id) {
        return state.matrixOutputs().stream()
                .filter(output -> output.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static MatrixCrosspointDto crosspoint(MatrixOutputDto output, String inputId) {
        return output.crosspoints().stream()
                .filter(crosspoint -> crosspoint.inputId().equals(inputId))
                .findFirst()
                .orElseThrow();
    }
}
