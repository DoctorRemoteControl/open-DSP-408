package de.drremote.dsp408controller.core.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.protocol.DspChannel;

class Fir408GateReadLocationTest {

    @Test
    void usesOnlyObservedFir408GateLocations() {
        DspLibrary library = DspLibraryLoader.loadFir408();

        Map<DspChannel, InputGateLocation> actual = IntStream
                .rangeClosed(0, library.maxParameterBlockIndex())
                .boxed()
                .flatMap(block -> library.inputGateLocationsForBlock(block).stream())
                .distinct()
                .collect(Collectors.toMap(InputGateLocation::channel, location -> location));

        Map<DspChannel, InputGateLocation> expected = Map.of(
                DspChannel.IN_A,
                location(DspChannel.IN_A, 0x00, 24, 26, 28, 30),
                DspChannel.IN_B,
                location(DspChannel.IN_B, 0x03, 12, 14, 16, 18),
                DspChannel.IN_C,
                location(DspChannel.IN_C, 0x06, 0, 2, 4, 6),
                DspChannel.IN_D,
                location(DspChannel.IN_D, 0x08, 38, 40, 42, 44)
        );

        assertEquals(expected, actual);
    }

    private static InputGateLocation location(
            DspChannel channel,
            int blockIndex,
            int attackOffset,
            int releaseOffset,
            int holdOffset,
            int thresholdOffset) {
        return new InputGateLocation(
                channel,
                new PeqFieldLocation(blockIndex, attackOffset),
                new PeqFieldLocation(blockIndex, releaseOffset),
                new PeqFieldLocation(blockIndex, holdOffset),
                new PeqFieldLocation(blockIndex, thresholdOffset)
        );
    }
}
