package de.drremote.dsp408controller.core.decode;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.OutputPeqBandLocation;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;
import de.drremote.dsp408controller.core.state.DspState;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class OutputPeqBlockDecoder implements BlockDecoder {
    private final DspState state;
    private final Consumer<String> log;
    private final DspLibrary library;

    public OutputPeqBlockDecoder(DspState state, Consumer<String> log, DspLibrary library) {
        this.state = state;
        this.log = log;
        this.library = library;
    }

    @Override
    public void decode(int blockIndex, byte[] data) {
        if (data == null) {
            return;
        }

        List<OutputPeqBandLocation> locations = library.outputPeqLocationsForBlock(blockIndex);
        if (locations.isEmpty()) {
            return;
        }

        for (OutputPeqBandLocation location : locations) {
            Integer gainRaw = readUInt16LE(state.getBlock(location.gain().blockIndex()), location.gain().dataOffset());
            Integer frequencyRaw = readUInt16LE(
                    state.getBlock(location.frequency().blockIndex()),
                    location.frequency().dataOffset()
            );
            Integer qRaw = readUInt8(state.getBlock(location.q().blockIndex()), location.q().dataOffset());
            Integer typeRaw = readUInt8(
                    state.getBlock(location.filterType().blockIndex()),
                    location.filterType().dataOffset()
            );
            Integer bypassMaskRaw = location.bypassMask() == null
                    ? null
                    : readUInt8(
                            state.getBlock(location.bypassMask().blockIndex()),
                            location.bypassMask().dataOffset()
                    );

            if (gainRaw == null || frequencyRaw == null || qRaw == null || typeRaw == null
                    || (location.bypassMask() != null && bypassMaskRaw == null)) {
                continue;
            }

            double gainDb = DspCodecRegistry.peqGain().rawToDouble(gainRaw);
            double frequencyHz = DspCodecRegistry.peqFrequency().rawToDouble(frequencyRaw);
            double q = DspCodecRegistry.peqQ().rawToDouble(qRaw);
            PeqFilterType filterType;
            try {
                filterType = PeqFilterType.fromRaw(typeRaw);
            } catch (IllegalArgumentException e) {
                if (log != null) {
                    log.accept(String.format(
                            Locale.ROOT,
                            "OPEQ RX %s PEQ%d skipped: unsupported type raw=%d",
                            location.channel().displayName(),
                            location.bandIndex(),
                            typeRaw
                    ));
                }
                continue;
            }
            boolean bypass = bypassMaskRaw != null
                    && (bypassMaskRaw & (1 << location.bypassBitIndex())) != 0;

            state.markOutputPeqFromDevice(
                    location.channel(),
                    location.bandIndex(),
                    gainDb,
                    frequencyHz,
                    q,
                    filterType,
                    bypass,
                    String.format(
                            Locale.ROOT,
                            "blocks:%02X@%02X/%02X@%02X/%02X@%02X/%02X@%02X/%02X@%02X raw=%d/%d/%d/%d/%d bit=%d",
                            location.gain().blockIndex(),
                            location.gain().dataOffset(),
                            location.frequency().blockIndex(),
                            location.frequency().dataOffset(),
                            location.q().blockIndex(),
                            location.q().dataOffset(),
                            location.filterType().blockIndex(),
                            location.filterType().dataOffset(),
                            location.bypassMask() == null ? -1 : location.bypassMask().blockIndex(),
                            location.bypassMask() == null ? -1 : location.bypassMask().dataOffset(),
                            gainRaw,
                            frequencyRaw,
                            qRaw,
                            typeRaw,
                            bypassMaskRaw == null ? 0 : bypassMaskRaw,
                            location.bypassBitIndex()
                    )
            );

            if (log != null) {
                log.accept(String.format(
                        Locale.ROOT,
                        "OPEQ RX %s PEQ%d -> type=%s gain=%.1f dB freq=%.2f Hz q=%.2f bypass=%s (block %02X)",
                        location.channel().displayName(),
                        location.bandIndex(),
                        filterType.displayName(),
                        gainDb,
                        frequencyHz,
                        q,
                        bypass ? "on" : "off",
                        blockIndex
                ));
            }
        }
    }

    private static Integer readUInt16LE(byte[] data, int offset) {
        if (data == null || offset < 0 || data.length < offset + 2) {
            return null;
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static Integer readUInt8(byte[] data, int offset) {
        if (data == null || offset < 0 || data.length <= offset) {
            return null;
        }
        return data[offset] & 0xFF;
    }
}
