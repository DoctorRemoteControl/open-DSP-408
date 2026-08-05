package de.drremote.dsp408controller.core.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.DspState;
import de.drremote.dsp408controller.core.state.MatrixOutputState;
import de.drremote.dsp408controller.util.Hex;

class Fir408MatrixCrosspointLiveDumpTest {
    private static final List<String> MATRIX_BLOCK_PAYLOADS = List.of(
            "01 00 34 24 0B DC 00 AA 00 23 00 00 02 01 00 B4 00 00 00 00 00 08 00 54 4F 50 5F 4C 31 00 00 03 00 18 01 18 01 00 00 18 01 00 00 13 00 4B 00 12 0A 10 10 00 06 6E 00",
            "01 00 34 24 0D 23 00 78 00 1D 01 23 00 0F 00 00 00 2B 01 00 00 BB 00 18 01 00 00 00 00 01 00 54 4F 50 5F 4C 32 00 00 0C 00 18 01 18 01 18 01 18 01 00 00 13 00 4B 00",
            "01 00 34 24 0F DE 00 23 00 78 00 FD 00 23 00 78 00 1D 01 23 00 0F 00 00 00 2B 01 00 00 BF 00 DC 00 00 00 00 00 02 00 54 4F 50 5F 52 31 00 00 04 00 18 01 18 01 18 01",
            "01 00 34 24 10 18 01 00 00 42 00 1F 01 0A 0A 10 10 00 06 6E 00 2C 01 08 00 F0 00 2C 01 0A 03 78 00 3F 00 23 00 78 00 5F 00 23 00 78 00 7F 00 23 00 78 00 9E 00 23 00",
            "01 00 34 24 12 08 00 18 01 18 01 18 01 18 01 00 00 42 00 20 01 0A 0A 10 10 00 06 6E 00 2C 01 08 00 78 00 1F 00 23 00 78 00 3F 00 23 00 78 00 5F 00 23 00 78 00 7F 00",
            "01 00 34 24 14 42 41 53 53 5F 4D 4F 4E 01 00 18 01 DC 00 18 01 18 01 00 00 58 00 1F 01 0A 0A 10 10 00 06 6E 00 2C 01 08 00 78 00 1F 00 23 00 78 00 3F 00 23 00 78 00",
            "01 00 34 24 16 82 00 00 00 00 00 10 00 52 45 53 5F 36 00 00 00 02 00 18 01 18 01 18 01 18 01 00 00 58 00 1F 01 0A 0A 0A 0A 00 06 6E 00 2C 01 08 00 78 00 1F 00 23 00",
            "01 00 34 24 18 00 00 2B 01 00 00 B2 00 82 00 00 00 00 00 20 00 52 45 53 5F 37 00 00 00 01 00 18 01 18 01 18 01 18 01 00 00 58 00 1F 01 0A 0A 0A 0A 00 06 6E 00 2C 01",
            "01 00 34 24 1A 78 00 1D 01 23 00 0F 00 00 00 2B 01 00 00 B4 00 82 00 00 00 00 00 40 00 52 45 53 5F 38 00 00 00 02 00 18 01 18 01 18 01 18 01 00 00 58 00 1F 01 0A 0A"
    );

    @Test
    void decodesAllThirtyTwoCrosspointGainsFromCurrentFir408Dump() {
        DspState state = new DspState();
        DspPayloadRouter router = new DspPayloadRouter(
                state,
                null,
                null,
                null,
                DspLibraryLoader.loadFir408()
        );

        MATRIX_BLOCK_PAYLOADS.stream()
                .map(Hex::parseFlexible)
                .forEach(router::onPayload);

        Map<DspChannel, Set<DspChannel>> expectedRoutes = Map.of(
                DspChannel.OUT_1, Set.of(DspChannel.IN_A, DspChannel.IN_B),
                DspChannel.OUT_2, Set.of(DspChannel.IN_C, DspChannel.IN_D),
                DspChannel.OUT_3, Set.of(DspChannel.IN_C),
                DspChannel.OUT_4, Set.of(DspChannel.IN_D),
                DspChannel.OUT_5, Set.of(DspChannel.IN_A),
                DspChannel.OUT_6, Set.of(DspChannel.IN_B),
                DspChannel.OUT_7, Set.of(DspChannel.IN_A),
                DspChannel.OUT_8, Set.of(DspChannel.IN_B)
        );

        int decoded = 0;
        for (DspChannel output : List.of(
                DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
                DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8
        )) {
            MatrixOutputState matrix = state.matrixOutput(output);
            assertEquals(expectedRoutes.get(output), matrix.routedInputsSnapshot(), output.displayName());

            for (DspChannel input : List.of(
                    DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D
            )) {
                double expectedDb = output == DspChannel.OUT_1 && input == DspChannel.IN_C
                        ? -60.0
                        : output == DspChannel.OUT_5 && input == DspChannel.IN_B
                                ? -6.0
                                : 0.0;
                assertEquals(expectedDb, matrix.crosspointGain(input), 0.0001,
                        output.displayName() + " <- " + input.displayName());
                assertTrue(matrix.crosspointGainConfirmedFromDevice(input),
                        output.displayName() + " <- " + input.displayName());
                decoded++;
            }
        }

        assertEquals(32, decoded);
    }
}
