package de.drremote.dsp408controller.core.decode;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.InputPeqBandLocation;
import de.drremote.dsp408controller.core.library.InputPeqBypassLocation;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;
import de.drremote.dsp408controller.core.state.PeqBandState;
import de.drremote.dsp408controller.core.state.DspState;

public final class InputPeqBlockDecoder implements BlockDecoder {
    private static final List<DspChannel> INPUTS = List.of(
            DspChannel.IN_A,
            DspChannel.IN_B,
            DspChannel.IN_C,
            DspChannel.IN_D
    );

    private final DspState state;
    private final Consumer<String> log;
    private final DspLibrary library;
    private final Map<DspChannel, Map<Integer, InputPeqBandLocation>> bandLocationsByChannel;
    private final Map<DspChannel, Map<Integer, InputPeqBypassLocation>> bypassLocationsByChannel;

    public InputPeqBlockDecoder(DspState state, Consumer<String> log, DspLibrary library) {
        this.state = state;
        this.log = log;
        this.library = library;
        this.bandLocationsByChannel = buildBandLocations(library);
        this.bypassLocationsByChannel = buildBypassLocations(library);
    }

    @Override
    public void decode(int blockIndex, byte[] data) {
        if (data == null) {
            return;
        }

        boolean hasBandFields = !library.inputPeqLocationsForBlock(blockIndex).isEmpty();
        boolean isBypassBlock = library.isInputPeqBypassBlock(blockIndex);
        if (!hasBandFields && !isBypassBlock) {
            return;
        }

        for (DspChannel input : INPUTS) {
            Map<Integer, InputPeqBandLocation> bands = bandLocationsByChannel.get(input);
            if (bands == null || bands.isEmpty()) {
                continue;
            }

            for (int bandIndex = 1; bandIndex <= 8; bandIndex++) {
                InputPeqBandLocation location = bands.get(bandIndex);
                if (location != null) {
                    decodeBandIfComplete(location);
                }
            }
        }
    }

    private void decodeBandIfComplete(InputPeqBandLocation location) {
        Integer gainRaw = readUInt16LE(
                state.getBlock(location.gain().blockIndex()),
                location.gain().dataOffset()
        );
        Integer frequencyRaw = readUInt16LE(
                state.getBlock(location.frequency().blockIndex()),
                location.frequency().dataOffset()
        );
        Integer qRaw = readUInt8(
                state.getBlock(location.q().blockIndex()),
                location.q().dataOffset()
        );
        Integer typeRaw = readUInt8(
                state.getBlock(location.filterType().blockIndex()),
                location.filterType().dataOffset()
        );

        if (gainRaw == null || frequencyRaw == null || qRaw == null || typeRaw == null) {
            return;
        }

        final PeqFilterType filterType;
        try {
            filterType = decodeFilterType(typeRaw);
        } catch (IllegalArgumentException e) {
            if (log != null) {
                log.accept("Input PEQ decode skipped for "
                        + location.channel().displayName()
                        + " PEQ" + location.bandIndex()
                        + ": " + e.getMessage());
            }
            return;
        }

        double gainDb = DspCodecRegistry.peqGain().rawToDouble(gainRaw);
        double frequencyHz = DspCodecRegistry.peqFrequency().rawToDouble(frequencyRaw);
        double q = DspCodecRegistry.peqQ().rawToDouble(qRaw);

        String fieldSource = String.format(
                Locale.ROOT,
                "blocks:%02X@%02X/%02X@%02X/%02X@%02X/%02X@%02X raw=%d/%d/%d/%d",
                location.gain().blockIndex(),
                location.gain().dataOffset(),
                location.frequency().blockIndex(),
                location.frequency().dataOffset(),
                location.q().blockIndex(),
                location.q().dataOffset(),
                location.filterType().blockIndex(),
                location.filterType().dataOffset(),
                gainRaw,
                frequencyRaw,
                qRaw,
                typeRaw
        );

        PeqBandState band = state.inputPeq(location.channel()).ensureBand(location.bandIndex());
        band.updateGain(gainDb, fieldSource, true);
        band.updateFrequency(frequencyHz, fieldSource, true);
        band.updateQ(q, fieldSource, true);
        band.updateFilterType(filterType, fieldSource, true);

        Boolean bypass = readBypass(location.channel(), location.bandIndex());
        if (bypass != null) {
            band.updateBypass(bypass, formatBypassSource(location.channel(), location.bandIndex()), true);
        }

        if (log != null) {
            log.accept(String.format(
                    Locale.ROOT,
                    "IPEQ RX %s PEQ%d -> type=%s gain=%.1f dB freq=%.2f Hz q=%.2f bypass=%s",
                    location.channel().displayName(),
                    location.bandIndex(),
                    filterType.displayName(),
                    gainDb,
                    frequencyHz,
                    q,
                    bypass == null ? "unknown" : (bypass ? "on" : "off")
            ));
        }
    }

    private static Map<DspChannel, Map<Integer, InputPeqBandLocation>> buildBandLocations(DspLibrary library) {
        Map<DspChannel, Map<Integer, InputPeqBandLocation>> byChannel = new EnumMap<>(DspChannel.class);

        for (int blockIndex = 0x00; blockIndex <= 0x1C; blockIndex++) {
            for (InputPeqBandLocation location : library.inputPeqLocationsForBlock(blockIndex)) {
                byChannel
                        .computeIfAbsent(location.channel(), key -> new TreeMap<>())
                        .putIfAbsent(location.bandIndex(), location);
            }
        }

        return byChannel;
    }

    private static Map<DspChannel, Map<Integer, InputPeqBypassLocation>> buildBypassLocations(DspLibrary library) {
        Map<DspChannel, Map<Integer, InputPeqBypassLocation>> byChannel = new EnumMap<>(DspChannel.class);

        for (DspChannel channel : INPUTS) {
            Map<Integer, InputPeqBypassLocation> byBand = new TreeMap<>();
            for (InputPeqBypassLocation location : library.inputPeqBypassLocations(channel)) {
                byBand.put(location.bitIndex() + 1, location);
            }
            byChannel.put(channel, byBand);
        }

        return byChannel;
    }

    private Boolean readBypass(DspChannel channel, int bandIndex) {
        Map<Integer, InputPeqBypassLocation> byBand = bypassLocationsByChannel.get(channel);
        if (byBand == null) {
            return null;
        }

        InputPeqBypassLocation location = byBand.get(bandIndex);
        if (location == null) {
            return null;
        }

        Integer bitmask = readUInt8(state.getBlock(location.blockIndex()), location.dataOffset());
        if (bitmask == null) {
            return null;
        }

        return ((bitmask >>> location.bitIndex()) & 0x01) == 0x01;
    }

    private String formatBypassSource(DspChannel channel, int bandIndex) {
        Map<Integer, InputPeqBypassLocation> byBand = bypassLocationsByChannel.get(channel);
        if (byBand == null) {
            return "-";
        }

        InputPeqBypassLocation location = byBand.get(bandIndex);
        if (location == null) {
            return "-";
        }

        return String.format(
                Locale.ROOT,
                "%02X@%02X#%d",
                location.blockIndex(),
                location.dataOffset(),
                location.bitIndex()
        );
    }

    private static PeqFilterType decodeFilterType(int raw) {
        return PeqFilterType.fromRaw(raw);
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
