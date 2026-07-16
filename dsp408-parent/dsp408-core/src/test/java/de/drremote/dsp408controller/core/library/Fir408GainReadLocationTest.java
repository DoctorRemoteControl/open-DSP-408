package de.drremote.dsp408controller.core.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.protocol.DspChannel;

class Fir408GainReadLocationTest {

    @Test
    void usesFir408RecordStridesForAllChannelGains() {
        DspLibrary library = DspLibraryLoader.loadFir408();

        Map<DspChannel, DspFieldLocation> actual = library.gainReadLocationsByBlock()
                .values()
                .stream()
                .flatMap(java.util.Collection::stream)
                .collect(Collectors.toMap(DspFieldLocation::channel, location -> location));

        Map<DspChannel, DspFieldLocation> expected = Stream.of(
                location(DspChannel.IN_A, 0x02, 46),
                location(DspChannel.IN_B, 0x05, 34),
                location(DspChannel.IN_C, 0x08, 22),
                location(DspChannel.IN_D, 0x0B, 10),
                location(DspChannel.OUT_1, 0x0D, 18),
                location(DspChannel.OUT_2, 0x0F, 26),
                location(DspChannel.OUT_3, 0x11, 34),
                location(DspChannel.OUT_4, 0x13, 42),
                location(DspChannel.OUT_5, 0x16, 0),
                location(DspChannel.OUT_6, 0x18, 8),
                location(DspChannel.OUT_7, 0x1A, 16),
                location(DspChannel.OUT_8, 0x1C, 24)
        ).collect(Collectors.toMap(DspFieldLocation::channel, location -> location));

        assertEquals(expected, actual);
    }

    private static DspFieldLocation location(DspChannel channel, int blockIndex, int dataOffset) {
        return new DspFieldLocation(channel, blockIndex, dataOffset);
    }
}
